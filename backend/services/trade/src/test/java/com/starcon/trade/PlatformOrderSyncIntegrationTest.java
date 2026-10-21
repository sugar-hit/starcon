package com.starcon.trade;

import static com.starcon.trade.PlatformOrderSyncModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
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
import org.jooq.Record;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 9.1.2.1 平台订单同步。
 *
 * <p>本测试逐条验证蓝图本模块的硬要求，外加 AGENTS.md 明文要求的多租户三断言。全部走
 * <b>HTTP 端点 + 真实 PostgreSQL 16 + 强制 RLS</b>，不使用任何内存替身：走端点才能同时证明
 * 「服务层 + DB 层」两层都没把闸门漏掉。
 *
 * <p><b>刻意不提供 channel 的 stub 做订单落库的前提</b>：平台事件是本模块的输入，
 * 映射副本的同步是另一条路径（2.1.2.1 已交付的 channel 侧），订单落库只依赖本模块自己的
 * 映射副本（租户自维护 / 2.1.2.1 同步来的），不依赖跨服务调用 —— 这一点由
 * {@code 订单同步不需要 channel 在场} 一条钉住。
 */
@SpringBootTest
@AutoConfigureMockMvc
class PlatformOrderSyncIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  static final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  static final List<String> allPermissions =
      java.util.stream.Stream.of("orders")
          .flatMap(
              p ->
                  java.util.stream.Stream.of("read", "create", "update", "delete")
                      .map(op -> "trade." + p + "." + op))
          .toList();

  static AccessService.Grant grant(String scope, List<String> permissions) {
    return new AccessService.Grant(UUID.randomUUID(), "TEST", scope, permissions);
  }

  static AccessService.Access actor(UUID uid, UUID tenant, List<AccessService.Grant> grants) {
    return new AccessService.Access(
        uid,
        tenant,
        "Test",
        UUID.randomUUID(),
        null,
        grants.stream().flatMap(g -> g.permissions().stream()).distinct().toList(),
        grants);
  }

  static {
    pg.start();
    try {
      try (var c =
              DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
          var s = c.createStatement()) {
        s.execute("create role starcon_app nologin");
        s.execute("create role trade_app login password 'test-only' nosuperuser nobypassrls");
      }
      identity = HttpServer.create(new InetSocketAddress(0), 0);
      identity.createContext(
          "/internal/access",
          e -> {
            var found = actors.get(e.getRequestHeaders().getFirst("Authorization"));
            if (found == null) {
              e.sendResponseHeaders(401, -1);
              e.close();
              return;
            }
            byte[] body = json.writeValueAsBytes(found);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      actors.put("Bearer tenant-a", actor(user, a, List.of(grant("ALL", allPermissions))));
      actors.put("Bearer tenant-b", actor(user, b, List.of(grant("ALL", allPermissions))));
      actors.put(
          "Bearer read-only", actor(user, a, List.of(grant("ALL", List.of("trade.orders.read")))));
      identity.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
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
    r.add("CHANNEL_URL", () -> "http://127.0.0.1:1");
    // ⚠️ 本类**刻意不把连接池压到 1**：并发重复事件那一格必须真的并发
    // （池大小为 1 时请求排队执行，DB 层的并发闸根本没被碰到，那条断言就成了空断言）。
    // 连接池归还后不串租户那一格在 PlatformOrderSyncTenancyIntegrationTest 里
    // （那个类把池压到 1）。
  }

  @AfterAll
  static void stop() {
    identity.stop(0);
    pg.stop();
  }

  @Autowired PlatformOrderSyncService service;
  @Autowired AccessService access;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  AccessService.Access actorA, actorB;

  static final String PLATFORM = "AMAZON";
  static final String SHOP = "SHOP-A";

  @BeforeEach
  void seed() throws Exception {
    actorA = actors.get("Bearer tenant-a");
    actorB = actors.get("Bearer tenant-b");
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate platform_order_sync_exception, platform_order_component, platform_order,"
              + " platform_order_sync_event, platform_order_sync_batch, platform_order_enum_mapping,"
              + " order_charge, order_address_snapshot, sales_order_party_snapshot,"
              + " sales_order_line, order_line_attribute_snapshot, sales_order_status_history,"
              + " sales_order cascade");
    }
  }

  // ================================================================ 工具

  <T> T inA(java.util.function.Supplier<T> work) {
    return access.run(actorA, "trade.orders.read", work);
  }

  <T> T inB(java.util.function.Supplier<T> work) {
    return access.run(actorB, "trade.orders.read", work);
  }

  /** 登记一条租户自维护的订单状态映射（没有它，平台状态一律拒收 —— 不猜状态码）。 */
  void registerStatusMapping(String platform, String external, String internal) throws Exception {
    http.perform(
            post("/trade/platform-orders/mappings")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsBytes(
                        Map.of(
                            "platformCode", platform,
                            "enumKind", "ORDER_STATUS",
                            "externalValue", external,
                            "internalValue", internal))))
        .andExpect(status().is2xxSuccessful());
  }

  void registerStatusMappingFor(String token, String platform, String external, String internal)
      throws Exception {
    http.perform(
            post("/trade/platform-orders/mappings")
                .header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsBytes(
                        Map.of(
                            "platformCode", platform,
                            "enumKind", "ORDER_STATUS",
                            "externalValue", external,
                            "internalValue", internal))))
        .andExpect(status().is2xxSuccessful());
  }

  /** 一条能通过全部闸门的标准平台事件（2 行商品 + 收货地址 + 整单费用）。 */
  Map<String, Object> event(String externalOrderNo, String platformStatus) {
    Map<String, Object> e = new LinkedHashMap<>();
    e.put("eventType", "ORDER_CREATED");
    e.put("externalOrderNo", externalOrderNo);
    e.put("platformStatus", platformStatus);
    e.put("platformEventVersion", 1L);
    e.put("platformPlacedAt", "2026-03-01T08:00:00Z");
    e.put("platformUpdatedAt", "2026-03-01T08:00:00Z");
    e.put("currency", "USD");
    e.put("paymentMethod", "PREPAID");
    e.put("buyerId", "BUYER-1");
    e.put("buyerName", "Acme Trading Co.");
    e.put("buyerType", "ORGANIZATION");
    e.put("discountAmount", "3.00");
    e.put("taxAmount", "1.50");
    e.put("shippingAmount", "8.00");
    e.put("otherAmount", "2.00");
    e.put("platformGrandTotalAmount", "40.50");
    e.put("shippingAddress", address());
    e.put("lines", List.of(line(1, "SKU-1", "10.50", "1.00", "2.00"), line(2, "SKU-2", "5.00", "0", "0")));
    return e;
  }

  Map<String, Object> address() {
    Map<String, Object> a = new LinkedHashMap<>();
    a.put("recipientName", "Jane Doe");
    a.put("recipientPhone", "+1-555-0100");
    a.put("recipientEmail", "jane@acme.example");
    a.put("companyName", "Acme Trading Co.");
    a.put("countryCode", "US");
    a.put("province", "CA");
    a.put("city", "Los Angeles");
    a.put("district", "");
    a.put("postcode", "90001");
    a.put("addressLine1", "100 Main St");
    a.put("addressLine2", "");
    a.put("taxId", "");
    a.put("note", "");
    return a;
  }

  Map<String, Object> line(int no, String skuCode, String unitPrice, String discount, String tax) {
    Map<String, Object> l = new LinkedHashMap<>();
    l.put("lineNo", no);
    l.put("skuId", UUID.randomUUID().toString());
    l.put("skuCode", skuCode);
    l.put("skuName", "商品 " + skuCode);
    l.put("specSnapshot", "蓝色 / M");
    l.put("skuMappingVersion", "map-v3");
    l.put("quantity", "2");
    l.put("unitPrice", unitPrice);
    l.put("discountAmount", discount);
    l.put("taxAmount", tax);
    l.put("note", "");
    l.put("attributes", List.of());
    l.put("components", List.of());
    return l;
  }

  Map<String, Object> webhookBody(String platform, String shop, Map<String, Object>... events) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("platformCode", platform);
    body.put("shopCode", shop);
    body.put("events", List.of(events));
    return body;
  }

  com.fasterxml.jackson.databind.JsonNode postWebhook(
      Map<String, Object> body, String token) throws Exception {
    var res =
        http.perform(
                post("/trade/platform-orders/webhook")
                    .header("Authorization", token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsBytes(body)))
            .andExpect(status().is2xxSuccessful())
            .andReturn();
    return json.readTree(res.getResponse().getContentAsString());
  }

  // ================================================================
  // 硬要求 1：统一订单模型 —— 平台单落 sales_order 簇，读方不改一行就能读到
  // ================================================================

  @Test
  void 硬要求1_平台单落在sales_order簇_手工单与平台单共用一张表() throws Exception {
    registerStatusMapping(PLATFORM, "Shipped", "CONFIRMED");
    String external = "AMZ-" + UUID.randomUUID().toString().substring(0, 8);
    postWebhook(webhookBody(PLATFORM, SHOP, event(external, "Shipped")), "Bearer tenant-a");

    // 只有一张销售订单表：不存在 platform_sales_order 之类的「平台订单专用表」。
    List<String> tables =
        inA(
            () ->
                sql.fetch("select tablename from pg_tables where schemaname = current_schema()")
                    .getValues("tablename", String.class));
    assertThat(tables)
        .as("来源是字段不是表：出现平台订单专用表就意味着平台单另走了一套流程")
        .contains("sales_order")
        .doesNotContain("platform_sales_order", "manual_sales_order");

    Record row =
        inA(
            () ->
                sql.fetchOne(
                    "select o.order_source, o.manual_kind, o.external_order_no, o.status,"
                        + " o.currency, o.payment_method, o.grand_total_amount, o.subtotal_amount,"
                        + " o.discount_amount, o.tax_amount, o.shipping_amount, o.other_amount"
                        + " from sales_order o join platform_order p"
                        + " on p.tenant_id = o.tenant_id and p.sales_order_id = o.id"
                        + " where p.external_order_no = ?",
                    external));
    assertThat(row).as("平台单必须落到 sales_order").isNotNull();
    assertThat(row.get("order_source", String.class))
        .as("来源是字段：平台单取平台来源值，手工单取 MANUAL")
        .isEqualTo("AMAZON");
    assertThat(row.get("manual_kind", String.class))
        .as("平台单不是手工单：manual_kind 留空")
        .isEmpty();
    assertThat(row.get("status", String.class))
        .as("已确认的平台单进审单队列（SUBMITTED）")
        .isEqualTo("SUBMITTED");
    assertThat(row.get("currency", String.class)).isEqualTo("USD");
    // 口径：行1 2×10.50=21.0000 折扣 -1.0000 税 +2.0000；行2 2×5.00=10.0000。
    //   小计 31.0000 ｜ 折扣 4.0000 ｜ 税 3.5000 ｜ 运费 8.0000 ｜ 其他 2.0000 ｜ 合计 40.5000
    assertThat(row.get("subtotal_amount", BigDecimal.class)).isEqualByComparingTo("31.0000");
    assertThat(row.get("discount_amount", BigDecimal.class)).isEqualByComparingTo("4.0000");
    assertThat(row.get("tax_amount", BigDecimal.class)).isEqualByComparingTo("3.5000");
    assertThat(row.get("shipping_amount", BigDecimal.class)).isEqualByComparingTo("8.0000");
    assertThat(row.get("other_amount", BigDecimal.class)).isEqualByComparingTo("2.0000");
    assertThat(row.get("grand_total_amount", BigDecimal.class)).isEqualByComparingTo("40.5000");
  }

  @Test
  void 硬要求1_平台单带行地址费用与客户快照() throws Exception {
    registerStatusMapping(PLATFORM, "Shipped", "CONFIRMED");
    String external = "AMZ-" + UUID.randomUUID().toString().substring(0, 8);
    postWebhook(webhookBody(PLATFORM, SHOP, event(external, "Shipped")), "Bearer tenant-a");

    UUID salesOrderId =
        UUID.fromString(
            inA(
                    () ->
                        sql.fetchOne(
                                "select sales_order_id from platform_order where external_order_no = ?",
                                external)
                            .get(0, UUID.class))
                .toString());

    assertThat(
            inA(
                () ->
                    sql.fetch("select id from sales_order_line where order_id = ?", salesOrderId)
                        .size()))
        .as("订单行必须落库")
        .isEqualTo(2);
    assertThat(
            inA(
                () ->
                    sql.fetch(
                            "select id from order_address_snapshot where order_id = ?", salesOrderId)
                        .size()))
        .as("地址快照必须落库")
        .isEqualTo(1);
    assertThat(
            inA(
                () ->
                    sql.fetch("select id from order_charge where order_id = ?", salesOrderId).size()))
        .as("费用明细必须落库（运费 / 折扣 / 税 / 其它各一行）")
        .isEqualTo(4);
    assertThat(
            inA(
                () ->
                    sql.fetch(
                            "select id from sales_order_party_snapshot where order_id = ?",
                            salesOrderId)
                        .size()))
        .as("客户快照必须落库（V99 的提交闸门要求它）")
        .isEqualTo(1);
  }

  @Test
  void 硬要求1_地址是快照_平台改了地址也不改写历史订单() throws Exception {
    registerStatusMapping(PLATFORM, "Shipped", "CONFIRMED");
    String external = "AMZ-" + UUID.randomUUID().toString().substring(0, 8);
    postWebhook(webhookBody(PLATFORM, SHOP, event(external, "Shipped")), "Bearer tenant-a");

    UUID salesOrderId =
        inA(
            () ->
                sql.fetchOne(
                        "select sales_order_id from platform_order where external_order_no = ?",
                        external)
                    .get(0, UUID.class));
    String before =
        inA(
            () ->
                sql.fetchOne(
                        "select address_line1 from order_address_snapshot where order_id = ?::uuid",
                        salesOrderId.toString())
                    .get(0, String.class));

    // 平台侧改地址（同一张单、同一版本、内容变了）⇒ 拒收并记异常，历史快照不动。
    Map<String, Object> changed = event(external, "Shipped");
    @SuppressWarnings("unchecked")
    Map<String, Object> addr = (Map<String, Object>) changed.get("shippingAddress");
    addr.put("addressLine1", "999 Changed St");
    changed.put("platformEventVersion", 2L);
    var res =
        postWebhook(webhookBody(PLATFORM, SHOP, changed), "Bearer tenant-a");

    assertThat(res.get("results").get(0).get("outcome").asText()).isEqualTo("REJECTED");
    assertThat(res.get("results").get(0).get("errorCode").asText())
        .isEqualTo("ORDER_SNAPSHOT_FROZEN");

    String after =
        inA(
            () ->
                sql.fetchOne(
                        "select address_line1 from order_address_snapshot where order_id = ?::uuid",
                        salesOrderId.toString())
                    .get(0, String.class));
    assertThat(after)
        .as("地址是下单当时的快照：平台改了地址也不许改写历史订单")
        .isEqualTo(before);
  }

  // ================================================================
  // 硬要求 2：幂等 —— 同一外部订单号在同一来源范围内唯一，重复事件走 inbox 去重
  // ================================================================

  @Test
  void 硬要求2_重复事件走inbox去重_绝不产生第二张订单() throws Exception {
    registerStatusMapping(PLATFORM, "Shipped", "CONFIRMED");
    String external = "AMZ-" + UUID.randomUUID().toString().substring(0, 8);

    // 同一份事件再投一次：**内容必须逐字相同**（inbox 按事件键去重，不是按外部单号去重），
    // 否则那叫「平台又报了一次内容不同的订单」，不是重复投递。
    Map<String, Object> body = webhookBody(PLATFORM, SHOP, event(external, "Shipped"));
    var first = postWebhook(body, "Bearer tenant-a");
    assertThat(first.get("createdCount").asInt()).isEqualTo(1);

    var second = postWebhook(body, "Bearer tenant-a");
    assertThat(second.get("createdCount").asInt()).isZero();
    assertThat(second.get("duplicateCount").asInt()).isEqualTo(1);
    assertThat(second.get("results").get(0).get("outcome").asText()).isEqualTo("DUPLICATE");

    assertThat(
            inA(
                () ->
                    sql.fetch("select id from platform_order where external_order_no = ?", external)
                        .size()))
        .as("重复事件绝不产生第二张平台单")
        .isEqualTo(1);
    assertThat(
            inA(
                () ->
                    sql.fetch(
                            "select id from sales_order where external_order_no = ?", external)
                        .size()))
        .as("重复事件绝不产生第二张统一销售订单")
        .isEqualTo(1);
  }

  @Test
  void 硬要求2_并发重复事件被数据库闸门挡住() throws Exception {
    registerStatusMapping(PLATFORM, "Shipped", "CONFIRMED");
    String external = "AMZ-" + UUID.randomUUID().toString().substring(0, 8);
    Map<String, Object> body = webhookBody(PLATFORM, SHOP, event(external, "Shipped"));

    int threads = 6;
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(threads);
    AtomicInteger created = new AtomicInteger();
    AtomicInteger duplicate = new AtomicInteger();
    AtomicInteger other = new AtomicInteger();
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      for (int i = 0; i < threads; i++) {
        pool.submit(
            () -> {
              try {
                start.await();
                var res =
                    http.perform(
                            post("/trade/platform-orders/webhook")
                                .header("Authorization", "Bearer tenant-a")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsBytes(body)))
                        .andReturn();
                var node = json.readTree(res.getResponse().getContentAsString());
                String outcome = node.get("results").get(0).get("outcome").asText();
                if ("APPLIED".equals(outcome)) {
                  created.incrementAndGet();
                } else if ("DUPLICATE".equals(outcome)) {
                  duplicate.incrementAndGet();
                } else {
                  other.incrementAndGet();
                }
              } catch (Exception e) {
                other.incrementAndGet();
              } finally {
                done.countDown();
              }
            });
      }
      start.countDown();
      assertThat(done.await(60, TimeUnit.SECONDS)).as("并发用例超时").isTrue();
    } finally {
      pool.shutdownNow();
    }

    assertThat(other.get()).as("并发投递不应出现除「已落地 / 重复」以外的其它结论").isZero();
    assertThat(created.get()).as("同一份并发事件只允许有一个线程落地").isEqualTo(1);
    assertThat(duplicate.get()).as("其余线程必须被判为重复").isEqualTo(threads - 1);
    assertThat(
            inA(
                () ->
                    sql.fetch("select id from platform_order where external_order_no = ?", external)
                        .size()))
        .as("并发重复事件绝不产生第二张平台单")
        .isEqualTo(1);
    assertThat(
            inA(
                () ->
                    sql.fetch("select id from sales_order where external_order_no = ?", external)
                        .size()))
        .as("并发重复事件绝不产生第二张统一销售订单")
        .isEqualTo(1);
  }

  @Test
  void 硬要求2_同号订单来自另一家店铺时拒收而不是默默判重复() throws Exception {
    registerStatusMapping(PLATFORM, "Shipped", "CONFIRMED");
    String external = "AMZ-" + UUID.randomUUID().toString().substring(0, 8);
    postWebhook(webhookBody(PLATFORM, SHOP, event(external, "Shipped")), "Bearer tenant-a");

    var other =
        postWebhook(
            webhookBody(PLATFORM, "SHOP-B", event(external, "Shipped")), "Bearer tenant-a");
    assertThat(other.get("results").get(0).get("outcome").asText()).isEqualTo("REJECTED");
    assertThat(other.get("results").get(0).get("errorCode").asText())
        .isEqualTo("EXTERNAL_ORDER_NO_COLLISION");
    assertThat(
            inA(
                () ->
                    sql.fetch("select id from platform_order where external_order_no = ?", external)
                        .size()))
        .as("撞号不得产生第二张订单")
        .isEqualTo(1);
  }

  // ================================================================
  // 硬要求 3：字段解析 + 状态归一
  // ================================================================

  @Test
  void 硬要求3_平台状态按映射归一_查不到映射就拒收不猜() throws Exception {
    String external = "AMZ-" + UUID.randomUUID().toString().substring(0, 8);
    // 没有登记任何映射 ⇒ 拒收（不猜一个内部状态码）。
    var res = postWebhook(webhookBody(PLATFORM, SHOP, event(external, "Shipped")), "Bearer tenant-a");
    assertThat(res.get("results").get(0).get("outcome").asText()).isEqualTo("REJECTED");
    assertThat(res.get("results").get(0).get("errorCode").asText())
        .isEqualTo("UNMAPPED_PLATFORM_STATUS");
    assertThat(
            inA(
                () ->
                    sql.fetch("select id from platform_order where external_order_no = ?", external)
                        .size()))
        .as("状态没归一出来就不许落库（猜出来的状态会污染订单状态机）")
        .isZero();

    // 登记映射之后同一事件就能落库，并归一到 CONFIRMED。
    registerStatusMapping(PLATFORM, "Shipped", "CONFIRMED");
    var ok = postWebhook(webhookBody(PLATFORM, SHOP, event(external, "Shipped")), "Bearer tenant-a");
    assertThat(ok.get("results").get(0).get("outcome").asText()).isEqualTo("APPLIED");
    assertThat(ok.get("results").get(0).get("normalizedStatus").asText()).isEqualTo("CONFIRMED");
  }

  @Test
  void 硬要求3_租户自维护的映射优先于同步来的() throws Exception {
    registerStatusMapping(PLATFORM, "Shipped", "CONFIRMED");
    registerStatusMapping(PLATFORM, "Shipped", "IN_FULFILLMENT");
    String external = "AMZ-" + UUID.randomUUID().toString().substring(0, 8);
    var res = postWebhook(webhookBody(PLATFORM, SHOP, event(external, "Shipped")), "Bearer tenant-a");
    assertThat(res.get("results").get(0).get("normalizedStatus").asText())
        .as("同一外部值两行并存时，租户自维护优先（租户显式登记的口径不该被一次同步覆盖回去）")
        .isEqualTo("IN_FULFILLMENT");
  }

  @Test
  void 硬要求3_状态推进不改写订单快照_只把单据推进到审单队列() throws Exception {
    registerStatusMapping(PLATFORM, "Pending", "PENDING_CONFIRMATION");
    registerStatusMapping(PLATFORM, "Shipped", "CONFIRMED");
    String external = "AMZ-" + UUID.randomUUID().toString().substring(0, 8);

    Map<String, Object> pending = event(external, "Pending");
    pending.put("platformEventVersion", 1L);
    var first = postWebhook(webhookBody(PLATFORM, SHOP, pending), "Bearer tenant-a");
    assertThat(first.get("results").get(0).get("normalizedStatus").asText())
        .isEqualTo("PENDING_CONFIRMATION");
    assertThat(
            inA(
                () ->
                    sql.fetchOne(
                            "select status from sales_order where external_order_no = ?", external)
                        .get(0, String.class)))
        .as("待确认的平台单还没进审单队列")
        .isEqualTo("DRAFT");

    // 状态推进事件的内容必须与首投**逐字相同**（只有平台状态与事件版本变了）：
    // 平台单的内容是写一次的快照，商品 / 金额变了要归 9.1.1.3，不该混在状态推进里。
    Map<String, Object> shipped = new LinkedHashMap<>(pending);
    shipped.put("platformStatus", "Shipped");
    shipped.put("platformEventVersion", 2L);
    var second = postWebhook(webhookBody(PLATFORM, SHOP, shipped), "Bearer tenant-a");
    assertThat(second.get("results").get(0).get("outcome").asText()).isEqualTo("APPLIED");
    assertThat(second.get("updatedCount").asInt()).isEqualTo(1);
    assertThat(
            inA(
                () ->
                    sql.fetchOne(
                            "select status from sales_order where external_order_no = ?", external)
                        .get(0, String.class)))
        .as("状态推进到已确认 ⇒ 进 9.2 审单队列")
        .isEqualTo("SUBMITTED");
  }

  // ================================================================
  // 硬要求 4：金额与税费还原
  // ================================================================

  @Test
  void 硬要求4_金额还原写回的值逐项相符() throws Exception {
    registerStatusMapping(PLATFORM, "Shipped", "CONFIRMED");
    String external = "AMZ-" + UUID.randomUUID().toString().substring(0, 8);
    postWebhook(webhookBody(PLATFORM, SHOP, event(external, "Shipped")), "Bearer tenant-a");

    Record row =
        inA(
            () ->
                sql.fetchOne(
                    "select p.platform_original_amount, p.platform_discount_amount,"
                        + " p.platform_tax_amount, p.platform_shipping_amount,"
                        + " p.platform_grand_total_amount, p.restored_grand_total_amount,"
                        + " p.currency from platform_order p where p.external_order_no = ?",
                    external));
    assertThat(row.get("platform_original_amount", BigDecimal.class)).isEqualByComparingTo("31.0000");
    assertThat(row.get("platform_discount_amount", BigDecimal.class)).isEqualByComparingTo("4.0000");
    assertThat(row.get("platform_tax_amount", BigDecimal.class)).isEqualByComparingTo("3.5000");
    assertThat(row.get("platform_shipping_amount", BigDecimal.class)).isEqualByComparingTo("8.0000");
    assertThat(row.get("restored_grand_total_amount", BigDecimal.class))
        .isEqualByComparingTo("40.5000");
    // 行小计由服务层算、DB 用 CHECK 复算：两者不一致时提交会被 DB 挡下。
    List<BigDecimal> lineAmounts =
        inA(
            () ->
                sql.fetch(
                        "select line_amount from sales_order_line l join platform_order p"
                            + " on p.tenant_id = l.tenant_id and p.sales_order_id = l.order_id"
                            + " where p.external_order_no = ? order by l.line_no",
                        external)
                    .getValues("line_amount", BigDecimal.class));
    assertThat(lineAmounts).containsExactly(new BigDecimal("21.0000"), new BigDecimal("10.0000"));
  }

  @Test
  void 硬要求4_平台报的总额与还原结果对不上就拒收() throws Exception {
    registerStatusMapping(PLATFORM, "Shipped", "CONFIRMED");
    String external = "AMZ-" + UUID.randomUUID().toString().substring(0, 8);
    Map<String, Object> e = event(external, "Shipped");
    e.put("platformGrandTotalAmount", "41.00");
    var res = postWebhook(webhookBody(PLATFORM, SHOP, e), "Bearer tenant-a");
    assertThat(res.get("results").get(0).get("outcome").asText()).isEqualTo("REJECTED");
    assertThat(res.get("results").get(0).get("errorCode").asText()).isEqualTo("AMOUNT_MISMATCH");
    assertThat(
            inA(
                () ->
                    sql.fetch("select id from platform_order where external_order_no = ?", external)
                        .size()))
        .as("金额对不上就不许落库")
        .isZero();
    assertThat(
            inA(
                () ->
                    sql.fetch(
                            "select id from platform_order_sync_exception where error_code = ?",
                            "AMOUNT_MISMATCH")
                        .size()))
        .as("拒收必须留下异常明细")
        .isEqualTo(1);
  }

  @Test
  void 硬要求4_折后价由服务端折算成行折扣() throws Exception {
    registerStatusMapping(PLATFORM, "Shipped", "CONFIRMED");
    String external = "AMZ-" + UUID.randomUUID().toString().substring(0, 8);
    Map<String, Object> e = event(external, "Shipped");
    @SuppressWarnings("unchecked")
    List<Object> lines = (List<Object>) e.get("lines");
    @SuppressWarnings("unchecked")
    Map<String, Object> first = (Map<String, Object>) lines.get(0);
    first.remove("discountAmount");
    first.put("discountedUnitPrice", "9.50");
    e.put("platformGrandTotalAmount", "39.50");
    var res = postWebhook(webhookBody(PLATFORM, SHOP, e), "Bearer tenant-a");
    assertThat(res.get("results").get(0).get("outcome").asText()).isEqualTo("APPLIED");
    BigDecimal discount =
        inA(
            () ->
                sql.fetchOne(
                        "select discount_amount from sales_order_line l join platform_order p"
                            + " on p.tenant_id = l.tenant_id and p.sales_order_id = l.order_id"
                            + " where p.external_order_no = ? and l.line_no = 1",
                        external)
                    .get(0, BigDecimal.class));
    assertThat(discount)
        .as("折后价 9.50 × 2 与原价 10.50 × 2 的差 = 2.0000，折扣恒为负")
        .isEqualByComparingTo("-2.0000");
  }

  // ================================================================
  // 硬要求 5：组件展开（按映射版本）
  // ================================================================

  @Test
  void 硬要求5_组件按映射版本展开_没有显式组件的行落它自己() throws Exception {
    registerStatusMapping(PLATFORM, "Shipped", "CONFIRMED");
    String external = "AMZ-" + UUID.randomUUID().toString().substring(0, 8);
    Map<String, Object> e = event(external, "Shipped");
    @SuppressWarnings("unchecked")
    List<Object> lines = (List<Object>) e.get("lines");
    // 显式组件挂在**第二行**：第一行没有显式组件 ⇒ 它落它自己（LINE），
    // 两行合起来证明「有组件的行展开、没有组件的行落自己」两种形态都成立。
    @SuppressWarnings("unchecked")
    Map<String, Object> bundle = (Map<String, Object>) lines.get(1);
    bundle.put(
        "components",
        List.of(
            Map.of(
                "componentCode", "PART-A",
                "componentName", "零件 A",
                "componentType", "PART",
                "quantity", "1",
                "unitAmount", "3.00"),
            Map.of(
                "componentCode", "PART-A",
                "componentName", "零件 A（第二个）",
                "componentType", "PART",
                "quantity", "1",
                "unitAmount", "3.00")));
    postWebhook(webhookBody(PLATFORM, SHOP, e), "Bearer tenant-a");

    List<Record> components =
        inA(
            () ->
                sql.fetch(
                    "select c.line_no, c.component_source, c.component_seq, c.component_code,"
                        + " c.component_type, c.external_type, c.quantity, c.mapping_version"
                        + " from platform_order_component c join platform_order p"
                        + " on p.tenant_id = c.tenant_id and p.id = c.order_id"
                        + " where p.external_order_no = ? order by c.line_no, c.component_source,"
                        + " c.component_seq",
                    external));
    assertThat(components).hasSize(3);
    assertThat(components.get(0).get("component_source", String.class)).isEqualTo("LINE");
    assertThat(components.get(0).get("component_code", String.class)).isEqualTo("SKU-1");
    assertThat(components.get(1).get("component_source", String.class)).isEqualTo("COMPONENT");
    assertThat(components.get(2).get("component_source", String.class)).isEqualTo("COMPONENT");
    assertThat(components.get(1).get("component_code", String.class)).isEqualTo("PART-A");
    assertThat(components.get(2).get("component_code", String.class)).isEqualTo("PART-A");
    assertThat(components.get(1).get("component_type", String.class))
        .as("组件类型没登记映射时归 OTHER（只影响展示归类，猜错不污染状态机）")
        .isEqualTo("OTHER");
    assertThat(components.get(1).get("external_type", String.class))
        .as("平台原始类型词必须留着：界面要分得出「平台真报的 OTHER」与「我们兜底的 OTHER」")
        .isEqualTo("PART");
    String version = components.get(0).get("mapping_version", String.class);
    assertThat(version).as("映射版本必须随快照一起存").isNotBlank();
    assertThat(components.get(2).get("mapping_version", String.class)).isEqualTo(version);
  }

  // ================================================================
  // 硬要求 6：webhook 实时接入与轮询补拉走同一条落库路径
  // ================================================================

  @Test
  void 硬要求6_轮询补拉走同一条落库路径() throws Exception {
    registerStatusMapping(PLATFORM, "Shipped", "CONFIRMED");
    String external = "AMZ-" + UUID.randomUUID().toString().substring(0, 8);
    Map<String, Object> e = event(external, "Shipped");
    e.remove("eventType");

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("platformCode", PLATFORM);
    body.put("shopCode", SHOP);
    body.put("windowStart", "2026-03-01T00:00:00Z");
    body.put("windowEnd", "2026-03-02T00:00:00Z");
    body.put("orders", List.of(e));

    var res =
        http.perform(
                post("/trade/platform-orders/poll")
                    .header("Authorization", "Bearer tenant-a")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsBytes(body)))
            .andExpect(status().is2xxSuccessful())
            .andReturn();
    var node = json.readTree(res.getResponse().getContentAsString());
    assertThat(node.get("triggerType").asText()).isEqualTo("POLL");
    assertThat(node.get("createdCount").asInt()).isEqualTo(1);
    assertThat(node.get("results").get(0).get("outcome").asText()).isEqualTo("APPLIED");

    // 轮询与 webhook 落的是同一张统一订单模型。
    assertThat(
            inA(
                () ->
                    sql.fetchOne(
                            "select o.order_source from sales_order o join platform_order p"
                                + " on p.tenant_id = o.tenant_id and p.sales_order_id = o.id"
                                + " where p.external_order_no = ?",
                            external)
                        .get(0, String.class)))
        .isEqualTo("AMAZON");

    // 批次必须带时间窗（webhook 不带）—— DB 的 CHECK 钉着这一条。
    Record batch =
        inA(
            () ->
                sql.fetchOne(
                    "select trigger_type, window_start_at, window_end_at"
                        + " from platform_order_sync_batch order by created_at desc limit 1"));
    assertThat(batch.get("trigger_type", String.class)).isEqualTo("POLL");
    assertThat(batch.get("window_start_at", OffsetDateTime.class)).isNotNull();
    assertThat(batch.get("window_end_at", OffsetDateTime.class)).isNotNull();
  }

  @Test
  void 硬要求6_时间字段写回的值正确_不是静默偏八小时() throws Exception {
    registerStatusMapping(PLATFORM, "Shipped", "CONFIRMED");
    String external = "AMZ-" + UUID.randomUUID().toString().substring(0, 8);
    Map<String, Object> e = event(external, "Shipped");
    e.put("platformPlacedAt", "2026-03-01T08:00:00Z");
    e.put("platformUpdatedAt", "2026-03-01T09:30:00Z");
    postWebhook(webhookBody(PLATFORM, SHOP, e), "Bearer tenant-a");

    OffsetDateTime placed =
        inA(
            () ->
                sql.fetchOne(
                        "select platform_placed_at from platform_order where external_order_no = ?",
                        external)
                    .get(0, OffsetDateTime.class));
    assertThat(placed.toInstant())
        .as("timestamptz 必须按绝对时刻写回（用 Timestamp.valueOf 会静默偏 −28800s）")
        .isEqualTo(OffsetDateTime.parse("2026-03-01T08:00:00Z").toInstant());
    OffsetDateTime updated =
        inA(
            () ->
                sql.fetchOne(
                        "select platform_updated_at from platform_order where external_order_no = ?",
                        external)
                    .get(0, OffsetDateTime.class));
    assertThat(updated.toInstant())
        .isEqualTo(OffsetDateTime.parse("2026-03-01T09:30:00Z").toInstant());
  }

  // ================================================================
  // 读端：批次列表 / 订单列表 / 异常明细 / 能力声明
  // ================================================================

  @Test
  void 读端_批次列表与订单列表是真分页且带汇总() throws Exception {
    registerStatusMapping(PLATFORM, "Shipped", "CONFIRMED");
    for (int i = 0; i < 3; i++) {
      postWebhook(
          webhookBody(PLATFORM, SHOP, event("AMZ-PAGE-" + i, "Shipped")), "Bearer tenant-a");
    }
    var batches =
        json.readTree(
            http.perform(get("/trade/platform-orders/batches").header("Authorization", "Bearer tenant-a"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(batches.get("total").asLong()).isEqualTo(3);
    assertThat(batches.get("rows").size()).isEqualTo(3);
    assertThat(batches.get("webhookCount").asLong()).isEqualTo(3);

    var page1 =
        json.readTree(
            http.perform(
                    get("/trade/platform-orders/batches?page=0&size=2")
                        .header("Authorization", "Bearer tenant-a"))
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(page1.get("rows").size()).isEqualTo(2);
    assertThat(page1.get("total").asLong())
        .as("总数与分页无关")
        .isEqualTo(3);

    var orders =
        json.readTree(
            http.perform(get("/trade/platform-orders/orders").header("Authorization", "Bearer tenant-a"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(orders.get("total").asLong()).isEqualTo(3);
    assertThat(orders.get("confirmedCount").asLong()).isEqualTo(3);
  }

  @Test
  void 读端_异常明细能查到被拒收的事件() throws Exception {
    String external = "AMZ-" + UUID.randomUUID().toString().substring(0, 8);
    postWebhook(webhookBody(PLATFORM, SHOP, event(external, "Shipped")), "Bearer tenant-a");
    var exceptions =
        json.readTree(
            http.perform(
                    get("/trade/platform-orders/exceptions?errorCode=UNMAPPED_PLATFORM_STATUS")
                        .header("Authorization", "Bearer tenant-a"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(exceptions.get("total").asLong()).isEqualTo(1);
    assertThat(exceptions.get("rows").get(0).get("externalOrderNo").asText()).isEqualTo(external);
    assertThat(exceptions.get("unmappedStatusCount").asLong()).isEqualTo(1);
  }

  @Test
  void 能力声明把未交付的下游写明归属模块() throws Exception {
    var caps =
        json.readTree(
            http.perform(
                    get("/trade/platform-orders/capabilities")
                        .header("Authorization", "Bearer tenant-a"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(caps.get("sharedOrderModel").asBoolean()).isTrue();
    assertThat(caps.get("enumMappingSyncAvailable").asBoolean()).isTrue();
    assertThat(caps.get("downstreamGaps").isArray()).isTrue();
    assertThat(caps.get("downstreamGaps").size()).isGreaterThan(0);
  }

  /** 只读令牌不许受理平台事件：受理等于让履约按这张单工作，不是读权限能带过去的动作。 */
  @Test
  void 只读角色不能受理平台事件() throws Exception {
    http.perform(
            post("/trade/platform-orders/webhook")
                .header("Authorization", "Bearer read-only")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(webhookBody(PLATFORM, SHOP, event("AMZ-RO", "Shipped")))))
        .andExpect(status().isForbidden());
  }

  // ================================================================
  // 迁移确实落地了，且 RLS 是**强制**的
  // ================================================================

  @Test
  void 迁移确实落地了而且六张业务表都强制rls() {
    for (String table :
        List.of(
            "platform_order_sync_batch",
            "platform_order_sync_event",
            "platform_order",
            "platform_order_component",
            "platform_order_enum_mapping",
            "platform_order_sync_exception")) {
      var row =
          sql.fetchOne(
              "select relrowsecurity, relforcerowsecurity from pg_class where relname = ?", table);
      assertThat(row).as("V207 必须真的建出 " + table).isNotNull();
      assertThat(row.get(0, Boolean.class)).as(table + " RLS 未启用").isTrue();
      assertThat(row.get(1, Boolean.class)).as(table + " 只有 FORCE 才挡得住 owner").isTrue();
      assertThat(
              sql.fetchOne(
                      "select has_table_privilege('trade_app',?,'SELECT')",
                      table)
                  .get(0, Boolean.class))
          .as("trade_app 必须真的能读 " + table + "，否则「看不到」可能只是谁都读不了")
          .isTrue();
    }
  }
}
