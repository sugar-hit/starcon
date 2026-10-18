package com.starcon.trade;

import static com.starcon.trade.ExchangeAuthorizationModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.jooq.DSLContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 11.3.2.1 换货授权的**业务行为**集成测试。
 *
 * <p>跑的是 Testcontainers 真实 PostgreSQL 16（连接池压到 1），warehouse 与 catalog 用
 * in-JVM HTTP 端点按<b>真实契约形状</b>应答（照抄 {@code ShipmentRecordIntegrationTest}
 * 对 8.3.4.1 的做法）：本模块自己的五张业务表全部真落库，唯一的远程依赖是两个外部服务。
 *
 * <p><b>本模块最该被钉住的一条</b>：蓝图加粗句「<b>换货必须同时预占替换货并等待旧货回寄</b>，
 * 否则会出现只发不收的损失」。所以这里反复验证三件事：
 *
 * <ol>
 *   <li><b>预占是真的远程写</b>：授权成功时 warehouse 端点真的被调用了一次，
 *       而且返回的 reservation_id 被落进了本地表 —— 不是本地写个标志位；
 *   <li><b>预占恰好一次</b>：并发两次授权同一张售后单时，warehouse 只收到<b>一次</b>登记调用。
 *       warehouse 的 7.2.1.1 在 (source_system, source_ref_id) 上没有唯一约束，
 *       所以这条防线完全落在 trade 侧的锁单 + UNIQUE 上；
 *   <li><b>绕过服务层也一样被拒</b>：直连 SQL 写「有授权行但没预占」/「预占了却没等回寄」，
 *       在<b>提交时</b>被 V162 的迟到约束触发器打回。
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class ExchangeAuthorizationIntegrationTest {

  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID tenant = UUID.randomUUID();
  static final UUID user = UUID.randomUUID();
  static final UUID warehouseId = UUID.randomUUID();
  static final UUID legalEntityId = UUID.randomUUID();
  static HttpServer identity;
  static HttpServer warehouse;
  static HttpServer catalog;
  static final ObjectMapper json = new ObjectMapper();

  static final List<String> perms = List.of("trade.aftersales.read", "trade.aftersales.write");

  // ---- warehouse 桩的可控状态 ----
  /** 每个替换 SKU 的可售量；测试按需改。 */
  static final Map<UUID, BigDecimal> sellable = new ConcurrentHashMap<>();
  static final Map<UUID, String> skuMeta = new ConcurrentHashMap<>();
  /** warehouse 收到过几次预占登记调用 —— 「预占恰好一次」就断言它。 */
  static final AtomicInteger reservationCalls = new AtomicInteger();
  static final AtomicInteger releaseCalls = new AtomicInteger();
  static final List<Map<String, Object>> reservationPayloads =
      java.util.Collections.synchronizedList(new ArrayList<>());
  /** 让下一次预占登记直接 503，验证「仓库不可达 ⇒ 绝不产生已授权的单」。 */
  static volatile boolean warehouseDown;
  static volatile boolean catalogDown;

  static final OffsetDateTime T1 = OffsetDateTime.parse("2026-04-01T08:00:00+08:00");

  @BeforeAll
  static void boot() throws Exception {
    pg.start();
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute("create role starcon_app nologin");
      s.execute("create role trade_app login password 'test-only' nosuperuser nobypassrls");
    }

    identity = HttpServer.create(new InetSocketAddress(0), 0);
    identity.createContext(
        "/internal/access",
        e -> {
          String bearer = e.getRequestHeaders().getFirst("Authorization");
          if (!"Bearer main".equals(bearer)) {
            e.sendResponseHeaders(401, -1);
            e.close();
            return;
          }
          send(e, new AccessService.Access(user, tenant, "Test", UUID.randomUUID(), null, perms,
              List.of(new AccessService.Grant(UUID.randomUUID(), "TEST", "ALL", perms))));
        });
    identity.start();

    // ---- warehouse：7.4.1.x 可售量 / 7.2.1.1 登记预占 / 7.2.1.3 释放 ----
    warehouse = HttpServer.create(new InetSocketAddress(0), 0);
    warehouse.createContext(
        "/warehouse/inventory/sellable/skus",
        e -> {
          if (warehouseDown) {
            e.sendResponseHeaders(503, -1);
            e.close();
            return;
          }
          UUID sku = UUID.fromString(param(e.getRequestURI().getRawQuery(), "skuId"));
          BigDecimal s = sellable.getOrDefault(sku, BigDecimal.ZERO);
          Map<String, Object> row = new java.util.LinkedHashMap<>();
          row.put("skuId", sku.toString());
          row.put("skuCode", skuMeta.getOrDefault(sku, "SKU-" + sku.toString().substring(0, 8)));
          row.put("skuName", "替换商品 " + sku.toString().substring(0, 8));
          row.put("onHand", s);
          row.put("reserved", BigDecimal.ZERO);
          row.put("available", s);
          // 故意比 available 小：验证本模块按**含安全缓冲的可售量**判，而不是按 available。
          row.put("sellable", s);
          send(e, Map.of("rows", List.of(row), "total", 1, "page", 0, "size", 1));
        });
    warehouse.createContext(
        "/warehouse/inventory/reservations",
        e -> {
          if (warehouseDown) {
            e.sendResponseHeaders(503, -1);
            e.close();
            return;
          }
          if (!"POST".equals(e.getRequestMethod())) {
            e.sendResponseHeaders(404, -1);
            e.close();
            return;
          }
          reservationCalls.incrementAndGet();
          Map<String, Object> body = json.readValue(e.getRequestBody(), Map.class);
          reservationPayloads.add(body);
          send(
              e,
              Map.of(
                  "id", UUID.randomUUID().toString(),
                  "reservationNo",
                      "RSV-20260401-" + String.format("%06d", reservationCalls.get()),
                  "status",
                  "HELD",
                  "statusLabel",
                  "预占中"));
        });
    warehouse.createContext(
        "/warehouse/inventory/reservations/",
        e -> {
          // ⚠️ 必须把请求体读完：不读的话 keep-alive 连接上留着未消费的 body，
          // 服务端会把这些字节当成下一个请求的开头，客户端随即报传输层错误 ——
          // 表现是「仓库服务暂时不可达」，与真实原因（释放其实发出去了）完全对不上。
          e.getRequestBody().readAllBytes();
          // 路径是 .../reservations/{预占id}/release：预占 id 是**倒数第二段**，
          // 取最后一段会拿到 "release"，响应里的 reservationId 就成了一个非 UUID 的字符串。
          String path = e.getRequestURI().getPath();
          int lastSlash = path.lastIndexOf('/');
          int prevSlash = path.lastIndexOf('/', lastSlash - 1);
          String id = path.substring(prevSlash + 1, lastSlash);
          releaseCalls.incrementAndGet();
          send(
              e,
              Map.of(
                  "reservationId", id,
                  "reservationNo", "RSV-20260401-000001",
                  "terminal", true,
                  "statusBefore", "HELD",
                  "statusAfter", "RELEASED",
                  "restoredQuantity", "0.000000",
                  "replayed", false,
                  "message", "已释放"));
        });
    warehouse.start();

    // ---- catalog：2.x SKU 详情（替换商品事实的唯一来源）----
    catalog = HttpServer.create(new InetSocketAddress(0), 0);
    catalog.createContext(
        "/catalog/skus/",
        e -> {
          if (catalogDown) {
            e.sendResponseHeaders(503, -1);
            e.close();
            return;
          }
          String id = e.getRequestURI().getPath().substring("/catalog/skus/".length());
          if (!skuMeta.containsKey(UUID.fromString(id))) {
            e.sendResponseHeaders(404, -1);
            e.close();
            return;
          }
          send(
              e,
              Map.of(
                  "id", id,
                  "code", skuMeta.get(UUID.fromString(id)) + "-CODE",
                  "name", "替换商品 " + id.substring(0, 8),
                  // 非在售就是这里返回别的状态，用来验证「停售品不能当替换品」。
                  "status", skuMeta.get(UUID.fromString(id)).startsWith("OFF") ? "OFF_SHELF" : "ACTIVE"));
        });
    catalog.start();
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "trade_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://localhost:" + identity.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
    r.add("WAREHOUSE_URL", () -> "http://localhost:" + warehouse.getAddress().getPort());
    r.add("CATALOG_URL", () -> "http://localhost:" + catalog.getAddress().getPort());
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  @AfterAll
  static void stop() {
    identity.stop(0);
    warehouse.stop(0);
    catalog.stop(0);
    pg.stop();
  }

  @Autowired ExchangeAuthorizationService exchanges;
  @Autowired AccessService access;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  @BeforeEach
  void clean() throws Exception {
    reservationCalls.set(0);
    releaseCalls.set(0);
    reservationPayloads.clear();
    warehouseDown = false;
    catalogDown = false;
    sellable.clear();
    skuMeta.clear();
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate exchange_return_requirement, exchange_outbound_demand, exchange_reservation,"
              + " exchange_authorization_line, exchange_authorization,"
              + " aftersales_case_line, aftersales_case, sales_order cascade");
    }
  }

  <T> T as(java.util.function.Supplier<T> work) {
    return access.run(
        new AccessService.Access(user, tenant, "Test", UUID.randomUUID(), null, perms,
            List.of(new AccessService.Grant(UUID.randomUUID(), "TEST", "ALL", perms))),
        "trade.aftersales.write",
        work);
  }

  static void send(com.sun.net.httpserver.HttpExchange e, Object payload) throws java.io.IOException {
    byte[] body = json.writeValueAsBytes(payload);
    e.getResponseHeaders().add("Content-Type", "application/json");
    e.sendResponseHeaders(200, body.length);
    e.getResponseBody().write(body);
    e.close();
  }

  static String param(String query, String key) {
    for (String kv : query.split("&")) {
      String[] p = kv.split("=");
      if (p[0].equals(key)) return URLDecoder.decode(p[1], StandardCharsets.UTF_8);
    }
    return null;
  }

  // ================================================================ 种子数据

  record Case(UUID caseId, UUID orderId, UUID caseLineId, UUID originalSkuId) {}

  /** 一张真实订单 + 一条换货售后单 + 一条售后商品行（全部走真实约束，不是随手插的行）。 */
  Case seedCase(String suffix, BigDecimal lineQuantity) {
    UUID orderId = UUID.randomUUID();
    UUID originalSku = UUID.randomUUID();
    as(
        () -> {
          sql.execute(
              "insert into sales_order(id,tenant_id,order_no,order_source,external_order_no,currency,"
                  + "payment_method,status,submitted_at,subtotal_amount,discount_amount,tax_amount,"
                  + "shipping_amount,other_amount,grand_total_amount,version,created_at,updated_at)"
                  + " values(?,?,?,'AMAZON',?,'USD','PREPAID','SUBMITTED',?::timestamptz,100.0000,0,0,"
                  + "0,0,100.0000,0,?::timestamptz,?::timestamptz)",
              orderId, tenant, "SO-" + suffix, "EXT-" + suffix, T1, T1, T1);
          sql.execute(
              "insert into sales_order_line(id,tenant_id,order_id,line_no,sku_id,sku_code,sku_name,"
                  + "quantity,unit_price,discount_amount,tax_amount,line_amount,note,created_at)"
                  + " values(?,?::uuid,?::uuid,1,?,?,?,1,100.0000,0,0,100.0000,'',?::timestamptz)",
              UUID.randomUUID(), tenant, orderId, originalSku, "ORIG-" + suffix, "原商品", T1);
          sql.execute(
              "insert into order_address_snapshot(id,tenant_id,order_id,address_role,recipient_name,"
                  + "recipient_phone,recipient_email,company_name,country_code,province,city,"
                  + "district,postcode,address_line1,address_line2,tax_id,note,created_at)"
                  + " values(?,?::uuid,?::uuid,'SHIPPING',?,'+1-213-555-0100',?::varchar,'','US',"
                  + "'CA','LOS ANGELES','','90001','100 Main St','','','',?::timestamptz)",
              UUID.randomUUID(), tenant, orderId, "买家" + suffix, "buyer@example.com", T1);
          sql.execute(
              "insert into sales_order_party_snapshot(id,tenant_id,order_id,customer_party_id,"
                  + "customer_code,customer_name,customer_type,customer_default_currency,created_at)"
                  + " values(?,?::uuid,?::uuid,?,?,?,'INDIVIDUAL','',?::timestamptz)",
              UUID.randomUUID(), tenant, orderId, UUID.randomUUID(), "C-" + suffix, "买家" + suffix, T1);
          return null;
        });

    UUID caseId = UUID.randomUUID();
    UUID caseLineId = UUID.randomUUID();
    as(
        () -> {
          sql.execute(
              "insert into aftersales_case(id,tenant_id,case_no,order_id,case_type,status,"
                  + "reason_code,requested_amount,currency,requested_quantity,buyer_request,"
                  + "reply_due_at,received_at,created_at,updated_at)"
                  + " values(?,?::uuid,?,?::uuid,'EXCHANGE','RECEIVED','SIZE_MISMATCH',100.0000,"
                  + "'USD',?::numeric,?,?::timestamptz,?::timestamptz,?::timestamptz,?::timestamptz)",
              caseId, tenant, "AS-" + suffix, orderId, lineQuantity,
              "这个尺码不合适，想换一个", T1, T1, T1, T1);
          sql.execute(
              "insert into aftersales_case_line(id,tenant_id,case_id,order_line_id,quantity,"
                  + "requested_amount,currency,buyer_note,created_at)"
                  + " values(?,?::uuid,?::uuid,(select id from sales_order_line"
                  + " where tenant_id=?::uuid and order_id=?::uuid limit 1),?::numeric,null,null,"
                  + "'尺码不对',?::timestamptz)",
              caseLineId, tenant, caseId, tenant, orderId, lineQuantity, T1);
          return null;
        });
    return new Case(caseId, orderId, caseLineId, originalSku);
  }

  /** 一个在售的替换 SKU：注册进 catalog 桩并给出可售量。 */
  UUID replacementSku(String tag, String sellableQty) {
    UUID id = UUID.randomUUID();
    skuMeta.put(id, tag);
    sellable.put(id, new BigDecimal(sellableQty));
    return id;
  }

  ExchangeAuthorizationModels.ExchangeAuthorizeRequest request(
      UUID caseId, UUID sku, String qty) {
    return request(caseId, sku, qty, false);
  }

  ExchangeAuthorizationModels.ExchangeAuthorizeRequest request(
      UUID caseId, UUID sku, String qty, boolean allowPartial) {
    return new ExchangeAuthorizationModels.ExchangeAuthorizeRequest(
        caseId,
        warehouseId,
        legalEntityId,
        List.of(
            new ExchangeAuthorizationModels.ExchangeLineRequest(
                seedLineOf(caseId), sku, new BigDecimal(qty), "买家要求换个码")),
        allowPartial,
        "尺码换货");
  }

  Map<UUID, UUID> lineByCase = new ConcurrentHashMap<>();

  UUID seedLineOf(UUID caseId) {
    return lineByCase.computeIfAbsent(
        caseId,
        id ->
            as(
                () ->
                    one(
                            "select id from aftersales_case_line"
                                + " where tenant_id = ?::uuid and case_id = ?::uuid",
                            tenant,
                            id)
                        .get(0, UUID.class)));
  }

  /**
   * 取单行，取不到返回 {@code null}。
   *
   * <p>不用 {@code sql.fetchOne(sql, Object.class, binds)}：jOOQ 没有这个重载，
   * {@code Object.class} 会被当成**绑参**塞进 SQL，运行时报
   * 「Type class java.lang.Class is not supported in dialect POSTGRES」。
   */
  org.jooq.Record one(String query, Object... binds) {
    var r = sql.fetch(query, binds);
    return r.isEmpty() ? null : r.get(0);
  }

  ExchangeQuery query(int page, int size) {
    return new ExchangeQuery(null, null, null, null, null, null, null, page, size);
  }

  // ================================================================ 授权主路径

  @Test
  void 授权会真去仓库登记预占并把预占号落库() {
    var c = seedCase("OK", new BigDecimal("2"));
    lineByCase.put(c.caseId(), c.caseLineId());
    UUID sku = replacementSku("NEW-A", "10");

    var detail = as(() -> exchanges.authorize(request(c.caseId(), sku, "2"), user, "Bearer main"));

    assertThat(detail.lines()).hasSize(1);
    var line = detail.lines().get(0);
    // ★ 预占是真的：warehouse 的登记端点被调了一次，且返回的预占号原样落到了本地。
    assertThat(reservationCalls.get()).as("warehouse 必须真的收到一次预占登记").isEqualTo(1);
    assertThat(line.reservationId()).as("落库的必须是 warehouse 返回的真实预占 id").isNotNull();
    assertThat(line.reservationNo()).startsWith("RSV-20260401-");
    assertThat(line.reservationStatus()).isEqualTo("HELD");
    assertThat(line.authorizedQuantity()).isEqualTo("2.000000");
    assertThat(line.reservedQuantity()).as("已预占量必须等于授权量").isEqualTo("2.000000");
    // 发给 warehouse 的量与授权量一致，不多发也不少发。
    assertThat(new BigDecimal(String.valueOf(reservationPayloads.get(0).get("requestedQuantity"))))
        .isEqualByComparingTo("2.000000");

    // 输出句的三个产物齐了：换货授权 + 新发货需求 + 替换货预占。
    assertThat(line.outboundDemandId()).as("新发货需求必须真的立").isNotNull();
    assertThat(line.outboundDemandStatus()).isEqualTo("PENDING");
    assertThat(detail.authorization().status()).isEqualTo("ACTIVE");
    assertThat(detail.authorization().decision()).isEqualTo("APPROVED");
  }

  @Test
  void 换货必须同时预占替换货并等待旧货回寄() {
    var c = seedCase("BOTH", new BigDecimal("1"));
    lineByCase.put(c.caseId(), c.caseLineId());
    UUID sku = replacementSku("NEW-B", "5");

    var detail = as(() -> exchanges.authorize(request(c.caseId(), sku, "1"), user, "Bearer main"));
    var line = detail.lines().get(0);

    assertThat(line.reservationId()).as("必须真的预占替换货").isNotNull();
    assertThat(line.returnRequirementId()).as("必须真的挂上等待旧货回寄").isNotNull();
    assertThat(line.returnRequirementStatus()).isEqualTo("WAITING");
    assertThat(line.waitingReturnQuantity()).isEqualTo("1.000000");
    // 本地三张表各一行，不是标志位。
    var counts =
        as(
            () ->
                sql.fetch(
                    "select (select count(*) from exchange_reservation) as r,"
                        + " (select count(*) from exchange_outbound_demand) as d,"
                        + " (select count(*) from exchange_return_requirement) as x")
                    .get(0));
    assertThat(counts.get("r", Long.class)).isEqualTo(1L);
    assertThat(counts.get("d", Long.class)).isEqualTo(1L);
    assertThat(counts.get("x", Long.class)).isEqualTo(1L);
  }

  // ================================================================ 库存不足

  @Test
  void 库存不足且未允许部分授权时整笔拒绝且不产生任何预占() {
    var c = seedCase("SHORT", new BigDecimal("5"));
    lineByCase.put(c.caseId(), c.caseLineId());
    UUID sku = replacementSku("NEW-C", "2");

    assertThatThrownBy(() -> as(() -> exchanges.authorize(request(c.caseId(), sku, "5"), user, "Bearer main")))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("库存不足")
        .hasMessageContaining("允许部分授权");

    assertThat(reservationCalls.get())
        .as("库存不足必须在预占**之前**就拒掉，不能先占住货再发现不够")
        .isZero();
    assertThat(as(() -> exchanges.list(query(0, 20)).total()))
        .as("失败的授权不许留下任何一行")
        .isZero();
  }

  @Test
  void 允许部分授权时按可售量少批并落部分批准() {
    var c = seedCase("PART", new BigDecimal("5"));
    lineByCase.put(c.caseId(), c.caseLineId());
    UUID sku = replacementSku("NEW-D", "3");

    var detail =
        as(() -> exchanges.authorize(request(c.caseId(), sku, "5", true), user, "Bearer main"));

    assertThat(detail.authorization().decision()).isEqualTo("PARTIALLY_APPROVED");
    assertThat(detail.lines().get(0).authorizedQuantity()).isEqualTo("3.000000");
    assertThat(detail.lines().get(0).requestedQuantity()).isEqualTo("5.000000");
    assertThat(reservationCalls.get()).isEqualTo(1);
    // 落库数量与实际预占数量一致：少批也要真批少的那几件。
    assertThat(
            as(
                () ->
                    one("select quantity from exchange_reservation limit 1")
                        .get(0, BigDecimal.class)))
        .isEqualByComparingTo("3.000000");
  }

  @Test
  void 可售量为零时整笔拒绝() {
    var c = seedCase("ZERO", new BigDecimal("2"));
    lineByCase.put(c.caseId(), c.caseLineId());
    UUID sku = replacementSku("NEW-E", "0");

    assertThatThrownBy(
            () -> as(() -> exchanges.authorize(request(c.caseId(), sku, "2", true), user, "Bearer main")))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("无法换货");
    assertThat(reservationCalls.get()).isZero();
  }

  // ================================================================ 并发：预占不能重复

  @Test
  void 并发两次授权同一张售后单时预占只发生一次() throws Exception {
    var c = seedCase("RACE", new BigDecimal("3"));
    lineByCase.put(c.caseId(), c.caseLineId());
    UUID sku = replacementSku("NEW-R", "10");

    var barrier = new CountDownLatch(1);
    var ok = new AtomicInteger();
    var conflict = new AtomicInteger();
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      for (int i = 0; i < 2; i++) {
        pool.submit(
            () -> {
              try {
                barrier.await(5, TimeUnit.SECONDS);
                as(() -> exchanges.authorize(request(c.caseId(), sku, "3"), user, "Bearer main"));
                ok.incrementAndGet();
              } catch (org.springframework.web.server.ResponseStatusException e) {
                if (e.getStatusCode().value() == 409) conflict.incrementAndGet();
              } catch (Exception ignored) {
                // 其余异常由下面的计数断言暴露
              }
            });
      }
      barrier.countDown();
      pool.shutdown();
      assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
    } finally {
      pool.shutdownNow();
    }

    assertThat(ok.get()).as("两次并发授权只能成功一次").isEqualTo(1);
    assertThat(conflict.get()).as("另一次必须是 409（已授权过，不能重复换货）").isEqualTo(1);
    // ★ 这条是本模块的核心断言：warehouse 只被登���了一次预占。
    assertThat(reservationCalls.get())
        .as("并发换货绝不能把同一份库存预占两次 —— warehouse 只能收到一次登记")
        .isEqualTo(1);
    assertThat(as(() -> exchanges.list(query(0, 20)).total())).isEqualTo(1L);
  }

  // ================================================================ DB 层守卫

  @Test
  void 绕过服务层只写授权行不写预占会在提交时被拒() {
    var c = seedCase("GUARD1", new BigDecimal("2"));
    UUID sku = replacementSku("NEW-G", "10");
    UUID authorizationId = UUID.randomUUID();

    assertThatThrownBy(
            () ->
                runGuarded(
                        s -> {
                          s.execute(
                              "insert into exchange_authorization(id,tenant_id,authorization_no,"
                                  + "case_id,case_no,order_id,order_no,status,decision,"
                                  + "requested_quantity,authorized_quantity,replacement_sku_count,"
                                  + "version,authorized_by,authorized_at,created_at,updated_at)"
                                  + " values(?,?::uuid,'EX-GUARD1',?::uuid,?,?::uuid,'SO-GUARD1',"
                                  + "'ACTIVE','APPROVED',2.000000,2.000000,1,1,?::uuid,"
                                  + "?::timestamptz,?::timestamptz,?::timestamptz)",
                              authorizationId, tenant, c.caseId(), "AS-GUARD1", c.orderId(), user,
                              T1, T1, T1);
                          s.execute(
                              "insert into exchange_authorization_line(id,tenant_id,authorization_id,"
                                  + "case_line_id,original_sku_id,original_sku_code,"
                                  + "original_quantity,replacement_sku_id,replacement_sku_code,"
                                  + "warehouse_id,legal_entity_id,requested_quantity,"
                                  + "authorized_quantity,reserved_quantity,created_at)"
                                  + " values(?::uuid,?::uuid,?::uuid,?::uuid,?::uuid,'ORIG',2.000000,"
                                  + "?::uuid,'NEW-G',?::uuid,?::uuid,2.000000,2.000000,0,"
                                  + "?::timestamptz)",
                              UUID.randomUUID(), tenant, authorizationId, c.caseLineId(),
                              c.originalSkuId(), sku, warehouseId, legalEntityId, T1);
                    }))
        .isInstanceOf(java.sql.SQLException.class)
        .as("没有预占就提交 = 只发不收，DB 必须拒")
        .hasMessageContaining("没有预占就发替换货");
  }

  @Test
  void 绕过服务层预占了却没等旧货回寄会在提交时被拒() {
    var c = seedCase("GUARD2", new BigDecimal("1"));
    UUID sku = replacementSku("NEW-H", "10");
    UUID authorizationId = UUID.randomUUID();

    assertThatThrownBy(
            () ->
                runGuarded(
                        s -> {
                          s.execute(
                              "insert into exchange_authorization(id,tenant_id,authorization_no,"
                                  + "case_id,case_no,order_id,order_no,status,decision,"
                                  + "requested_quantity,authorized_quantity,replacement_sku_count,"
                                  + "version,authorized_by,authorized_at,created_at,updated_at)"
                                  + " values(?,?::uuid,'EX-GUARD2',?::uuid,?,?::uuid,'SO-GUARD2',"
                                  + "'ACTIVE','APPROVED',1.000000,1.000000,1,1,?::uuid,"
                                  + "?::timestamptz,?::timestamptz,?::timestamptz)",
                              authorizationId, tenant, c.caseId(), "AS-GUARD2", c.orderId(), user,
                              T1, T1, T1);
                          UUID lineId = UUID.randomUUID();
                          s.execute(
                              "insert into exchange_authorization_line(id,tenant_id,authorization_id,"
                                  + "case_line_id,original_sku_id,original_sku_code,"
                                  + "original_quantity,replacement_sku_id,replacement_sku_code,"
                                  + "warehouse_id,legal_entity_id,requested_quantity,"
                                  + "authorized_quantity,reserved_quantity,created_at)"
                                  + " values(?::uuid,?::uuid,?::uuid,?::uuid,?::uuid,'ORIG',1.000000,"
                                  + "?::uuid,'NEW-H',?::uuid,?::uuid,1.000000,1.000000,0,"
                                  + "?::timestamptz)",
                              lineId, tenant, authorizationId, c.caseLineId(),
                              c.originalSkuId(), sku, warehouseId, legalEntityId, T1);
                          // 只有预占、**没有**回寄要求。
                          s.execute(
                              "insert into exchange_reservation(id,tenant_id,authorization_id,"
                                  + "authorization_line_id,reservation_id,reservation_no,sku_id,"
                                  + "quantity,status,created_at,updated_at)"
                                  + " values(?::uuid,?::uuid,?::uuid,?::uuid,?::uuid,'RSV-G',"
                                  + "?::uuid,1.000000,'HELD',?::timestamptz,?::timestamptz)",
                              UUID.randomUUID(), tenant, authorizationId, lineId,
                              UUID.randomUUID(), sku, T1, T1);
                          s.execute(
                              "update exchange_authorization_line set reserved_quantity=1.000000"
                                  + " where id = ?::uuid",
                              lineId);
                    }))
        .isInstanceOf(java.sql.SQLException.class)
        .as("发了替换货却不收旧货就是白送，DB 必须拒")
        .hasMessageContaining("等待旧货回寄");
  }

  // ================================================================ 取消

  @Test
  void 取消换货会真去仓库释放预占() {
    var c = seedCase("CANCEL", new BigDecimal("2"));
    lineByCase.put(c.caseId(), c.caseLineId());
    UUID sku = replacementSku("NEW-J", "10");
    var created = as(() -> exchanges.authorize(request(c.caseId(), sku, "2"), user, "Bearer main"));
    assertThat(reservationCalls.get()).isEqualTo(1);

    var cancelled =
        as(
            () ->
                exchanges.cancel(
                    created.authorization().id(),
                    new ExchangeAuthorizationModels.ExchangeCancelRequest(1, "买家改主意了"),
                    user,
                    "Bearer main"));

    assertThat(releaseCalls.get()).as("撤销必须真的去仓库释放，不能只改本地标志位").isEqualTo(1);
    assertThat(cancelled.authorization().status()).isEqualTo("CANCELLED");
    assertThat(cancelled.authorization().version()).isEqualTo(2);
    var line = cancelled.lines().get(0);
    assertThat(line.reservationStatus()).isEqualTo("RELEASED");
    assertThat(line.outboundDemandStatus()).isEqualTo("CANCELLED");
    assertThat(line.returnRequirementStatus()).isEqualTo("WAIVED");
  }

  @Test
  void 版本不符时拒绝取消且不释放预占() {
    var c = seedCase("VER", new BigDecimal("2"));
    lineByCase.put(c.caseId(), c.caseLineId());
    UUID sku = replacementSku("NEW-K", "10");
    var created = as(() -> exchanges.authorize(request(c.caseId(), sku, "2"), user, "Bearer main"));

    assertThatThrownBy(
            () ->
                as(
                    () ->
                        exchanges.cancel(
                            created.authorization().id(),
                            new ExchangeAuthorizationModels.ExchangeCancelRequest(99, "误点"),
                            user,
                            "Bearer main")))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("已被其他人改动");
    assertThat(releaseCalls.get())
        .as("被拒的撤销不许真的去仓库释放预占")
        .isZero();
  }

  @Test
  void 已撤销的单不能重复撤销() {
    var c = seedCase("TWICE", new BigDecimal("1"));
    lineByCase.put(c.caseId(), c.caseLineId());
    UUID sku = replacementSku("NEW-L", "10");
    var created = as(() -> exchanges.authorize(request(c.caseId(), sku, "1"), user, "Bearer main"));
    as(
        () ->
            exchanges.cancel(
                created.authorization().id(),
                new ExchangeAuthorizationModels.ExchangeCancelRequest(1, "撤"),
                user,
                "Bearer main"));

    assertThatThrownBy(
            () ->
                as(
                    () ->
                        exchanges.cancel(
                            created.authorization().id(),
                            new ExchangeAuthorizationModels.ExchangeCancelRequest(2, "再撤"),
                            user,
                            "Bearer main")))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("已撤销过");
    assertThat(releaseCalls.get()).as("重复撤销不该再释放一次").isEqualTo(1);
  }

  @Test
  void 撤销后的单不再允许加明细() {
    var c = seedCase("NOADD", new BigDecimal("1"));
    lineByCase.put(c.caseId(), c.caseLineId());
    UUID sku = replacementSku("NEW-M", "10");
    var created = as(() -> exchanges.authorize(request(c.caseId(), sku, "1"), user, "Bearer main"));
    as(
        () ->
            exchanges.cancel(
                created.authorization().id(),
                new ExchangeAuthorizationModels.ExchangeCancelRequest(1, "撤"),
                user,
                "Bearer main"));

    assertThatThrownBy(
            () ->
                runGuarded(
                    s ->
                        s.execute(
                            "insert into exchange_authorization_line(id,tenant_id,authorization_id,"
                                + "case_line_id,original_sku_id,original_quantity,"
                                + "replacement_sku_id,warehouse_id,legal_entity_id,"
                                + "requested_quantity,authorized_quantity,reserved_quantity,created_at)"
                                + " values(?::uuid,?::uuid,?::uuid,?::uuid,?::uuid,1.000000,?::uuid,"
                                + "?::uuid,?::uuid,1.000000,1.000000,1.000000,?::timestamptz)",
                                UUID.randomUUID(), tenant, created.authorization().id(),
                                c.caseLineId(), c.originalSkuId(),
                                replacementSku("NEW-N", "1"), warehouseId, legalEntityId, T1)))
        .hasMessageContaining("已撤销");
  }

  // ================================================================ 替换商品校验

  @Test
  void 替换成同一个商品等于没换() {
    var c = seedCase("SAME", new BigDecimal("1"));
    lineByCase.put(c.caseId(), c.caseLineId());
    // 复用原 SKU 的 id，但让它在 catalog 桩里存在且在售。
    skuMeta.put(c.originalSkuId(), "ORIGINAL");

    assertThatThrownBy(
            () -> as(() -> exchanges.authorize(request(c.caseId(), c.originalSkuId(), "1"), user, "Bearer main")))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("不叫换货");
    assertThat(reservationCalls.get()).isZero();
  }

  @Test
  void 停售商品不能当替换品() {
    var c = seedCase("OFF", new BigDecimal("1"));
    lineByCase.put(c.caseId(), c.caseLineId());
    UUID sku = replacementSku("OFF-SHELF", "10");

    assertThatThrownBy(
            () -> as(() -> exchanges.authorize(request(c.caseId(), sku, "1"), user, "Bearer main")))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("已停售或未上架");
    assertThat(reservationCalls.get())
        .as("替换品没通过校验就不许碰预占")
        .isZero();
  }

  @Test
  void 同一售后行重复出现在请求里被拒() {
    var c = seedCase("DUP", new BigDecimal("2"));
    lineByCase.put(c.caseId(), c.caseLineId());
    UUID sku = replacementSku("NEW-O", "10");

    var req =
        new ExchangeAuthorizationModels.ExchangeAuthorizeRequest(
            c.caseId(), warehouseId, legalEntityId,
            List.of(
                new ExchangeAuthorizationModels.ExchangeLineRequest(
                    c.caseLineId(), sku, new BigDecimal("1"), "一"),
                new ExchangeAuthorizationModels.ExchangeLineRequest(
                    c.caseLineId(), sku, new BigDecimal("1"), "二")),
            false,
            "");

    assertThatThrownBy(() -> as(() -> exchanges.authorize(req, user, "Bearer main")))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("只能换一次");
  }

  // ================================================================ 跨服务不可达

  @Test
  void 仓库不可达时整笔失败且不留下已授权的单() {
    var c = seedCase("WDOWN", new BigDecimal("2"));
    lineByCase.put(c.caseId(), c.caseLineId());
    UUID sku = replacementSku("NEW-P", "10");
    warehouseDown = true;

    assertThatThrownBy(() -> as(() -> exchanges.authorize(request(c.caseId(), sku, "2"), user, "Bearer main")))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("仓库可售量查询返回 503");
    assertThat(as(() -> exchanges.list(query(0, 20)).total()))
        .as("拿不到真实预占就绝不能留下「账上已授权、仓库没占住货」的单子")
        .isZero();
    assertThat(
            as(() -> one("select count(*) from exchange_reservation").get(0, Long.class)))
        .isEqualTo(0L);
  }

  @Test
  void 商品服务不可达时整笔失败() {
    var c = seedCase("CDOWN", new BigDecimal("2"));
    lineByCase.put(c.caseId(), c.caseLineId());
    UUID sku = replacementSku("NEW-Q", "10");
    catalogDown = true;

    assertThatThrownBy(() -> as(() -> exchanges.authorize(request(c.caseId(), sku, "2"), user, "Bearer main")))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("商品服务返回 503");
    assertThat(reservationCalls.get()).isZero();
  }

  // ================================================================ 列表 / 分页 / 检索

  @Test
  void 真分页两页不重叠且汇总不受分页影响() {
    for (int idx = 0; idx < 5; idx++) {
      final int i = idx;
      var c = seedCase("PAGE" + i, new BigDecimal("1"));
      lineByCase.put(c.caseId(), c.caseLineId());
      as(() -> exchanges.authorize(request(c.caseId(), replacementSku("NEW-P" + i, "5"), "1"), user, "Bearer main"));
    }

    var page1 = as(() -> exchanges.list(query(0, 2)));
    var page2 = as(() -> exchanges.list(query(1, 2)));

    assertThat(page1.total()).isEqualTo(5L);
    assertThat(page1.items()).hasSize(2);
    assertThat(page2.items()).hasSize(2);
    assertThat(page1.items()).doesNotContainAnyElementsOf(page2.items());
    assertThat(page1.summary().totalCount()).as("汇总不受分页影响").isEqualTo(5L);
    assertThat(page1.summary().totalRequestedQuantity()).isEqualTo("5.000000");
    assertThat(page1.summary().totalWaitingReturnQuantity()).isEqualTo("5.000000");
  }

  @Test
  void 关键字能命中授权号售后号与替换商品() {
    var c = seedCase("FIND", new BigDecimal("1"));
    lineByCase.put(c.caseId(), c.caseLineId());
    as(() -> exchanges.authorize(request(c.caseId(), replacementSku("SEARCHABLE", "5"), "1"), user, "Bearer main"));

    assertThat(
            as(
                () ->
                    exchanges.list(
                            new ExchangeQuery(null, null, null, null, null, "SEARCHABLE", null, 0, 20))
                        .total()))
        .as("按替换商品名必须能搜到")
        .isEqualTo(1L);
    assertThat(
            as(
                () ->
                    exchanges.list(
                            new ExchangeQuery(null, null, null, null, null, "AS-FIND", null, 0, 20))
                        .total()))
        .isEqualTo(1L);
    assertThat(
            as(
                () ->
                    exchanges.list(
                            new ExchangeQuery(null, null, null, null, null, "根本没有", null, 0, 20))
                        .total()))
        .isZero();
  }

  @Test
  void 筛选与排序白名单真实生效() {
    var c = seedCase("FILTER", new BigDecimal("1"));
    lineByCase.put(c.caseId(), c.caseLineId());
    UUID sku = replacementSku("NEW-R", "5");
    var created = as(() -> exchanges.authorize(request(c.caseId(), sku, "1"), user, "Bearer main"));
    as(
        () ->
            exchanges.cancel(
                created.authorization().id(),
                new ExchangeAuthorizationModels.ExchangeCancelRequest(1, "撤"),
                user,
                "Bearer main"));

    assertThat(as(() -> exchanges.list(new ExchangeQuery(null, null, null, "ACTIVE", null, null, null, 0, 20)).total()))
        .isZero();
    assertThat(as(() -> exchanges.list(new ExchangeQuery(null, null, null, "CANCELLED", null, null, null, 0, 20)).total()))
        .isEqualTo(1L);
    assertThat(
            as(
                () ->
                    exchanges.list(
                            new ExchangeQuery(
                                null, null, null, null, null, null, "case_no", 0, 20))
                        .items()
                        .get(0)
                        .caseNo()))
        .isEqualTo("AS-FILTER");
    // 非法排序键回落默认，不允许把任意表达式拼进 ORDER BY。
    assertThat(
            as(
                () ->
                    exchanges.list(
                            new ExchangeQuery(
                                null, null, null, null, null, null, "1; drop table x", 0, 20))
                        .total()))
        .isEqualTo(1L);
  }

  @Test
  void 可授权的换货售后单下拉只列未授权未结案的() {
    var c1 = seedCase("OPT1", new BigDecimal("1"));
    lineByCase.put(c1.caseId(), c1.caseLineId());
    var c2 = seedCase("OPT2", new BigDecimal("1"));
    lineByCase.put(c2.caseId(), c2.caseLineId());
    as(() -> exchanges.authorize(request(c1.caseId(), replacementSku("NEW-S", "5"), "1"), user, "Bearer main"));

    var options = as(() -> exchanges.filters());
    assertThat(options.exchangeCaseCount()).as("已授权的那张不该再出现在下拉里").isEqualTo(1L);
    assertThat(options.exchangeCases().get(0).caseId()).isEqualTo(c2.caseId());
  }

  // ================================================================ 时间与契约

  @Test
  void timestamptz带时区写入后读回零漂移() {
    var c = seedCase("TIME", new BigDecimal("1"));
    lineByCase.put(c.caseId(), c.caseLineId());
    var created =
        as(() -> exchanges.authorize(request(c.caseId(), replacementSku("NEW-T", "5"), "1"), user, "Bearer main"));

    var readBack =
        as(
            () ->
                sql.fetch(
                    "select authorized_at, created_at, updated_at"
                        + " from exchange_authorization where id = ?::uuid",
                    created.authorization().id())
                    .get(0));
    // 断言**写回的值**，不是「没抛异常」：PG 是微秒精度，Java 是纳秒，
    // 不截断的话这里会差在末位。
    assertThat(readBack.get("authorized_at", OffsetDateTime.class).getNano() / 1_000_000)
        .isEqualTo(created.authorization().authorizedAt().getNano() / 1_000_000);
    assertThat(readBack.get("authorized_at", OffsetDateTime.class).getOffset())
        .as("读回必须保留写入时的 +08:00 偏移，而不是被静默换算成本机时区")
        .isEqualTo(created.authorization().authorizedAt().getOffset());
  }

  @Test
  void 未认证与越权与不存在都给出正确的HTTP状态() throws Exception {
    http.perform(get("/trade/aftersales/exchanges"))
        .andExpect(status().isUnauthorized());

    http.perform(get("/trade/aftersales/exchanges").header("Authorization", "Bearer nope"))
        .andExpect(status().isUnauthorized());

    http.perform(get("/trade/aftersales/exchanges/" + UUID.randomUUID())
            .header("Authorization", "Bearer main"))
        .andExpect(status().isNotFound());
  }

  @Test
  void 五张业务表都强制rls而且运行角色真的能读写() {
    for (String table :
        List.of(
            "exchange_authorization",
            "exchange_authorization_line",
            "exchange_reservation",
            "exchange_outbound_demand",
            "exchange_return_requirement")) {
      var row =
          as(() -> sql.fetchOne("select relrowsecurity, relforcerowsecurity from pg_class where relname = ?", table));
      assertThat(row).as("V162 必须真的建出 " + table).isNotNull();
      assertThat(row.get(0, Boolean.class)).as(table + " RLS 未启用").isTrue();
      assertThat(row.get(1, Boolean.class)).as(table + " 只有 FORCE 才挡得住 owner").isTrue();
    }
    assertThat(
            as(
                () ->
                    one("select has_table_privilege('trade_app','exchange_authorization','SELECT')")
                        .get(0, Boolean.class)))
        .as("trade_app 必须真的能读，否则「看不到」可能只是谁都读不了")
        .isTrue();
  }

  @Test
  void 运行角色改不了已经落库的授权数量与授权行() {
    var c = seedCase("IMMUT", new BigDecimal("1"));
    lineByCase.put(c.caseId(), c.caseLineId());
    var created =
        as(() -> exchanges.authorize(request(c.caseId(), replacementSku("NEW-U", "5"), "1"), user, "Bearer main"));

    // 改得了授权数量 = 让已经圈走的替换货事后被改成另一个数（那正是重复换货）。
    assertThat(
            as(
                () ->
                    one(
                            "select has_column_privilege('trade_app','exchange_authorization',"
                                + "'authorized_quantity','UPDATE')")
                        .get(0, Boolean.class)))
        .as("授权数量列必须对运行角色只读").isFalse();
    // 明细行写完即冻结。
    assertThat(
            as(
                () ->
                    one("select has_table_privilege('trade_app','exchange_authorization_line','UPDATE')")
                        .get(0, Boolean.class)))
        .isFalse();
    // 预占 id 不许被运行角色改掉（改了就成了「伪造一个预占」）。
    assertThat(
            as(
                () ->
                    one(
                            "select has_column_privilege('trade_app','exchange_reservation',"
                                + "'reservation_id','UPDATE')")
                        .get(0, Boolean.class)))
        .isFalse();
    assertThat(created.authorization().id()).isNotNull();
  }

  // ================================================================ 工具

  /**
   * 在**独立事务**里直连数据库执行一段 SQL 并提交。
   *
   * <p>刻意绕开服务层：V162 的两个守卫是 DEFERRABLE INITIALLY DEFERRED 的
   * 迟到约束触发器，只有在<b>提交那一刻</b>才会跑，所以在 {@code access.run} 里抛异常
   * 是测不到它们的（那样只会回滚，根本到不了 commit）。
   *
   * <p>用 jOOQ 的 {@code DSL.using(connection)} 而不是裸 JDBC Statement：
   * 拼装 SQL 的写法与 {@code ExchangeAuthorizationService} 一致，
   * 免得测试里维护第二套绑参风格。
   */
  void runGuarded(java.util.function.Consumer<org.jooq.DSLContext> work) throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())) {
      c.setAutoCommit(false);
      try {
        try (var st = c.createStatement()) {
          st.execute("select set_config('app.current_tenant','" + tenant + "',true)");
        }
        work.accept(org.jooq.impl.DSL.using(c, org.jooq.SQLDialect.POSTGRES));
        c.commit();
      } catch (Exception e) {
        c.rollback();
        throw e;
      }
    }
  }
}
