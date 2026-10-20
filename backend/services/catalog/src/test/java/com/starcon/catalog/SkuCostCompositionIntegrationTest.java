package com.starcon.catalog;

import static com.starcon.catalog.CatalogModels.*;
import static com.starcon.catalog.SkuCostCompositionModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.jooq.DSLContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 5.2.1.1 成本构成：真实 PostgreSQL 16 + 强制 RLS 下的成本法反例、租户隔离三断言、
 * 手工调整的原因必填、时间戳写回，以及上游不可达时**不拿 0 冒充「没有这笔成本」**。
 *
 * <p><b>真实 PostgreSQL 16 + 强制 RLS</b>：构成、调整项、历史快照全部真的落库。
 * 连接池固定为 1 条连接：只有复用同一条连接，才能真正证明归还后不会串租户。
 *
 * <p><b>跨服务依赖的处理</b>：头程分摊与关税来自 warehouse 服务（另一个数据库）。
 * 生产路径是真实的 {@code RestClient} HTTP 调用，本测试用一个 {@link HttpServer} 起一个
 * <b>确定性的替身</b>来满足它 —— 替身只存在于测试代码里，且被断言「确实收到了 catalog 透传的
 * {@code Authorization} 头」，所以它证明的是<b>生产代码真的走 HTTP 且透传了租户上下文</b>，
 * 而不是把替身逻辑塞进了生产代码。
 *
 * <p><b>本文件最重要的五个断言</b>：
 *
 * <ol>
 *   <li>{@link #同一SKU在不同成本法下采购成本不同且历史可查()}：蓝图加粗句的技术落点。
 *       同一份到货事实，三种成本法必须给出**三个不同的**采购成本，
 *       而且这三个数必须能在成本变动历史里逐条查到 —— 否则「明示口径」只是一句装饰。
 *   <li>{@link #租户隔离三断言覆盖构成调整与历史()}：成本是最敏感的数据之一
 *       （能看出别的租户在什么价位采购），A 读到 B 的成本会直接让 A 的利润算错。
 *   <li>{@link #手工调整项必须带原因()}：原因不是备注，是必填；DB 层也有 CHECK 兜底。
 *   <li>{@link #生效时间按时刻写回不被时区偏移()}：timestamptz 走 {@code ?::timestamptz} +
 *       {@code OffsetDateTime} 绑定；断言**写回的值**，不是只断言没抛异常。
 *   <li>{@link #上游不可达时读端点返回503而不是把头程与关税当成0()}：拿不到时显示 0
 *       会让单位成本偏低、毛利偏高，而页面上看不出任何异常。
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class SkuCostCompositionIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  static HttpServer warehouse;
  static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
  static final Map<String, AccessService.Access> actors =
      new java.util.concurrent.ConcurrentHashMap<>();
  static final AtomicInteger SEQ = new AtomicInteger();

  /** 成本口径窗口：两份到货都落在这段区间里。 */
  static final LocalDate DAY_OLD = LocalDate.now().minusDays(30);
  static final LocalDate DAY_NEW = LocalDate.now().minusDays(10);
  static final LocalDate TODAY = LocalDate.now();

  /** warehouse 替身的头程发运单数据（tenant → 发运单）。 */
  static final Map<UUID, List<Map<String, Object>>> SHIPMENTS =
      new java.util.concurrent.ConcurrentHashMap<>();
  /** warehouse 替身的关税估算单数据（tenant → 估算单）。 */
  static final Map<UUID, List<Map<String, Object>>> DUTIES =
      new java.util.concurrent.ConcurrentHashMap<>();

  /** 替身最近一次收到的 Authorization 头：用来断言租户上下文真的透传出去了。 */
  static final AtomicReference<String> lastBearer = new AtomicReference<>();

  /** 置位后替身一律回 503：模拟「仓库服务不可用」。 */
  static final AtomicBoolean warehouseDown = new AtomicBoolean();

  static AccessService.Access actor(
      UUID uid, UUID tenant, UUID dept, List<AccessService.Grant> grants) {
    return new AccessService.Access(
        uid,
        tenant,
        "Test",
        UUID.randomUUID(),
        dept,
        grants.stream().flatMap(g -> g.permissions().stream()).distinct().toList(),
        grants);
  }

  static AccessService.Grant grant(String scope, List<String> permissions) {
    return new AccessService.Grant(UUID.randomUUID(), "TEST", scope, permissions);
  }

  static List<String> allPermissions() {
    return java.util.stream.Stream.of("spu", "sku", "attribute", "research", "compliance", "assets")
        .flatMap(
            p ->
                java.util.stream.Stream.of("read", "create", "update", "delete")
                    .map(op -> "catalog." + p + "." + op))
        .toList();
  }

  static {
    pg.start();
    try (var conn =
            DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = conn.createStatement()) {
      s.execute("create role starcon_app nologin");
      s.execute("create role catalog_app login password 'test-only' nosuperuser nobypassrls");
      identity = HttpServer.create(new InetSocketAddress(0), 0);
      identity.createContext(
          "/internal/access",
          e -> {
            var access = actors.get(e.getRequestHeaders().getFirst("Authorization"));
            if (access == null) {
              e.sendResponseHeaders(401, -1);
              e.close();
              return;
            }
            byte[] body = JSON.writeValueAsBytes(access);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      startWarehouseStub();
      actors.put("Bearer tenant-a", actor(user, a, null, List.of(grant("ALL", allPermissions()))));
      actors.put("Bearer tenant-b", actor(user, b, null, List.of(grant("ALL", allPermissions()))));
      // 只有商品读权限：写端点必须 403。
      actors.put(
          "Bearer reader",
          actor(user, a, null, List.of(grant("ALL", List.of("catalog.spu.read", "catalog.sku.read")))));
      identity.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  /**
   * warehouse 替身：一个真实的 HTTP 服务器，形状与 warehouse 的
   * {@code FirstMilePage} / {@code FirstMileDetail} / {@code DutyEstimatePage} /
   * {@code DutyEstimateLineRow} 逐字一致。
   *
   * <p>它刻意<b>不</b>校验 Authorization（真实 warehouse 会向 identity 校验），
   * 因为本测试要断言的正是「catalog 把这个头透传出去了」。租户由该头决定，
   * 与真实 warehouse 从已认证成员关系解析租户同构。
   */
  static void startWarehouseStub() throws Exception {
    warehouse = HttpServer.create(new InetSocketAddress(0), 0);
    warehouse.createContext(
        "/warehouse",
        e -> {
          lastBearer.set(e.getRequestHeaders().getFirst("Authorization"));
          if (warehouseDown.get()) {
            e.sendResponseHeaders(503, -1);
            e.close();
            return;
          }
          var path = e.getRequestURI().getPath();
          UUID tenant =
              "Bearer tenant-b".equals(lastBearer.get())
                  ? b
                  : "Bearer tenant-a".equals(lastBearer.get()) ? a : UUID.randomUUID();
          Object body;
          if (path.equals("/warehouse/first-mile-shipments")) {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (var shipment : SHIPMENTS.getOrDefault(tenant, List.of())) {
              var row = new LinkedHashMap<String, Object>();
              row.put("id", shipment.get("id"));
              row.put("shipmentCode", shipment.get("shipmentCode"));
              row.put("currency", shipment.get("currency"));
              row.put("freightCost", shipment.get("freightCost"));
              row.put("insuranceCost", shipment.get("insuranceCost"));
              row.put("handlingCost", shipment.get("handlingCost"));
              row.put("status", shipment.get("status"));
              row.put("createdAt", shipment.get("createdAt"));
              rows.add(row);
            }
            body = Map.of("rows", rows, "total", rows.size(), "page", 0, "size", rows.size());
          } else if (path.startsWith("/warehouse/first-mile-shipments/")) {
            String id = path.substring("/warehouse/first-mile-shipments/".length());
            Map<String, Object> shipment = null;
            for (var candidate : SHIPMENTS.getOrDefault(tenant, List.of())) {
              if (id.equals(candidate.get("id"))) shipment = candidate;
            }
            if (shipment == null) {
              e.sendResponseHeaders(404, -1);
              e.close();
              return;
            }
            var row = new LinkedHashMap<String, Object>();
            row.put("id", shipment.get("id"));
            row.put("shipmentCode", shipment.get("shipmentCode"));
            row.put("currency", shipment.get("currency"));
            row.put("freightCost", shipment.get("freightCost"));
            row.put("insuranceCost", shipment.get("insuranceCost"));
            row.put("handlingCost", shipment.get("handlingCost"));
            row.put("status", shipment.get("status"));
            row.put("createdAt", shipment.get("createdAt"));
            body = Map.of("shipment", row, "lines", shipment.get("lines"));
          } else if (path.equals("/warehouse/duty-taxes")) {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (var estimate : DUTIES.getOrDefault(tenant, List.of())) {
              var row = new LinkedHashMap<String, Object>();
              row.put("id", estimate.get("id"));
              row.put("estimateCode", estimate.get("estimateCode"));
              row.put("estimateCurrency", estimate.get("estimateCurrency"));
              row.put("status", estimate.get("status"));
              row.put("createdAt", estimate.get("createdAt"));
              rows.add(row);
            }
            body = Map.of("rows", rows, "page", 0, "size", rows.size(), "totalPages", 1);
          } else if (path.startsWith("/warehouse/duty-taxes/") && path.endsWith("/lines")) {
            String id = path.substring("/warehouse/duty-taxes/".length(), path.length() - "/lines".length());
            Map<String, Object> estimate = null;
            for (var candidate : DUTIES.getOrDefault(tenant, List.of())) {
              if (id.equals(candidate.get("id"))) estimate = candidate;
            }
            if (estimate == null) {
              e.sendResponseHeaders(404, -1);
              e.close();
              return;
            }
            body = estimate.get("lines");
          } else {
            e.sendResponseHeaders(404, -1);
            e.close();
            return;
          }
          byte[] out = JSON.writeValueAsBytes(body);
          e.getResponseHeaders().add("Content-Type", "application/json");
          e.sendResponseHeaders(200, out.length);
          e.getResponseBody().write(out);
          e.close();
        });
    warehouse.start();
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "catalog_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://localhost:" + identity.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
    r.add("WAREHOUSE_URL", () -> "http://localhost:" + warehouse.getAddress().getPort());
    // 单连接：只有复用同一条连接，才能真正证明归还之后不会串租户。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  @Autowired SkuCostCompositionService costs;
  @Autowired CatalogService catalog;
  @Autowired PartyService parties;
  @Autowired AccessService access;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  @AfterAll
  static void stop() {
    warehouse.stop(0);
    identity.stop(0);
    pg.stop();
  }

  AccessService.Access actor;
  AccessService.Access actorB;

  @BeforeEach
  void setUp() {
    actor = actors.get("Bearer tenant-a");
    actorB = actors.get("Bearer tenant-b");
    unit(actor, "PCS");
    unit(actor, "BOX");
    unit(actorB, "PCS");
    unit(actorB, "BOX");
    warehouseDown.set(false);
    lastBearer.set(null);
  }

  <T> T tx(AccessService.Access who, java.util.function.Supplier<T> work) {
    return access.run(who, "catalog.spu.read", work);
  }

  <T> T write(AccessService.Access who, java.util.function.Supplier<T> work) {
    return access.run(who, "catalog.spu.update", work);
  }

  void writeVoid(AccessService.Access who, Runnable work) {
    access.run(
        who,
        "catalog.spu.update",
        () -> {
          work.run();
          return null;
        });
  }

  static String unique(String prefix) {
    return prefix + "-" + SEQ.incrementAndGet();
  }

  // ================================================================ 夹具

  /** 登记一个计量单位（到货记录的 purchase_unit_code 外键指向它）。 */
  void unit(AccessService.Access who, String code) {
    tx(
        who,
        () -> {
          sql.execute(
              "insert into unit_of_measure(id,tenant_id,code,name,dimension,scale,allow_purchase,"
                  + "allow_sale,allow_storage,enabled) values(?::uuid,?::uuid,?,?, 'COUNT',0,true,true,true,true)"
                  + " on conflict (tenant_id,code) do nothing",
              UUID.randomUUID(),
              who.tenantId(),
              code,
              code);
          return null;
        });
  }

  /** 建一个 SPU + SKU。 */
  UUID sku(AccessService.Access who, String prefix) {
    String code = unique(prefix);
    UUID spuId =
        write(
            who,
            () ->
                catalog.saveSpu(
                    who,
                    null,
                    new SpuInput(
                        code + "-SPU", "SPU " + code, "", "Brand", "Category", "DRAFT", 0)));
    return write(
        who,
        () ->
            catalog.saveSku(
                who,
                spuId,
                null,
                new SkuInput(code, "SKU " + code, unique("BC"), "PCS", "ACTIVE", List.of(), 0)));
  }

  /** 建一个带供应商身份的主体，返回它的 SUPPLIER 角色 id（到货记录要挂角色上）。 */
  UUID supplierRole(AccessService.Access who, String prefix) {
    String code = unique(prefix);
    UUID partyId =
        write(
                who,
                () ->
                    parties.save(
                        who,
                        null,
                        new PartyInput(
                            code,
                            "供应商 " + code,
                            "ORGANIZATION",
                            "CN",
                            "mail@" + code.toLowerCase() + ".example",
                            null,
                            null,
                            "ACTIVE",
                            List.of(new PartyRoleInput("SUPPLIER", "ACTIVE", "")),
                            0)))
            .id();
    return tx(
            who,
            () ->
                sql.fetchOne(
                        "select id from party_role where tenant_id=? and party_id=? and role_type='SUPPLIER'",
                        who.tenantId(),
                        partyId)
                    .get(0, UUID.class));
  }

  /** 写一条**实际到货**事实（V137 的 vendor_delivery_record）。 */
  void delivery(
      AccessService.Access who,
      UUID roleId,
      UUID skuId,
      String ref,
      LocalDate received,
      String quantity,
      String amount,
      String currency,
      String purchaseUnit) {
    write(
        who,
        () -> {
          sql.execute(
              "insert into vendor_delivery_record(id,tenant_id,role_id,delivery_ref,sku_id,purchase_unit_code,"
                  + "quantity,amount,currency,ordered_at,due_date,received_date,note)"
                  + " values(?::uuid,?::uuid,?::uuid,?,?::uuid,?,?,?,?,?::timestamptz,?,?,?)",
              UUID.randomUUID(),
              who.tenantId(),
              roleId,
              ref,
              skuId,
              purchaseUnit,
              new BigDecimal(quantity),
              new BigDecimal(amount),
              currency,
              received.minusDays(3).atStartOfDay().atOffset(ZoneOffset.UTC),
              received,
              received,
              "");
          return null;
        });
  }

  /** 给 SKU 登记一个「采购单位 → 基础单位」的换算版本。 */
  void purchaseConversion(
      AccessService.Access who, UUID skuId, String unitCode, String quantityPerBase) {
    write(
        who,
        () -> {
          sql.execute(
              "insert into sku_unit_conversion(id,tenant_id,sku_id,unit_code,role,quantity_per_base,effective_from)"
                  + " values(?::uuid,?::uuid,?::uuid,?,'PURCHASE',?,?)",
              UUID.randomUUID(),
              who.tenantId(),
              skuId,
              unitCode,
              new BigDecimal(quantityPerBase),
              DAY_OLD.minusDays(1));
          return null;
        });
  }

  /** 给 warehouse 替身放一份头程发运单（运费摊给该 SKU）。 */
  void shipment(UUID tenant, UUID skuId, String currency, String freight, String qty) {
    var line = new LinkedHashMap<String, Object>();
    line.put("skuId", skuId.toString());
    line.put("plannedQuantity", new BigDecimal(qty));
    line.put("shippedQuantity", new BigDecimal(qty));
    var shipment = new LinkedHashMap<String, Object>();
    shipment.put("id", UUID.randomUUID().toString());
    shipment.put("shipmentCode", "FM-" + UUID.randomUUID().toString().substring(0, 8));
    shipment.put("currency", currency);
    shipment.put("freightCost", new BigDecimal(freight));
    shipment.put("insuranceCost", BigDecimal.ZERO);
    shipment.put("handlingCost", BigDecimal.ZERO);
    shipment.put("status", "DISPATCHED");
    shipment.put("createdAt", TODAY.minusDays(5).atStartOfDay().atOffset(ZoneOffset.UTC).toString());
    shipment.put("lines", List.of(line));
    SHIPMENTS.computeIfAbsent(tenant, k -> new ArrayList<>()).add(shipment);
  }

  /** 给 warehouse 替身放一份关税估算单。 */
  void dutyEstimate(UUID tenant, UUID skuId, String currency, String uom, String qty, String duty) {
    var line = new LinkedHashMap<String, Object>();
    line.put("skuId", skuId.toString());
    line.put("uom", uom);
    line.put("quantity", new BigDecimal(qty));
    line.put("estimatedDutyAmount", new BigDecimal(duty));
    line.put("estimatedVatAmount", new BigDecimal("0"));
    line.put("estimatedLineTotalTax", new BigDecimal(duty));
    var estimate = new LinkedHashMap<String, Object>();
    estimate.put("id", UUID.randomUUID().toString());
    estimate.put("estimateCode", "DT-" + UUID.randomUUID().toString().substring(0, 8));
    estimate.put("estimateCurrency", currency);
    estimate.put("status", "LOCKED");
    estimate.put("createdAt", TODAY.minusDays(5).atStartOfDay().atOffset(ZoneOffset.UTC).toString());
    estimate.put("lines", List.of(line));
    DUTIES.computeIfAbsent(tenant, k -> new ArrayList<>()).add(estimate);
  }

  /**
   * 一个标准夹具：老批 10.00/件（1000 元 100 件），新批 20.00/件（2000 元 100 件）。
   *
   * <p>⇒ 移动加权 15.00、先进先出 10.00、批次 20.00 —— 三个数互不相同。
   */
  UUID twoLotSku(AccessService.Access who, String prefix) {
    UUID roleId = supplierRole(who, prefix + "-SUP");
    UUID skuId = sku(who, prefix + "-SKU");
    delivery(who, roleId, skuId, unique("PO-OLD"), DAY_OLD, "100", "1000", "USD", "PCS");
    delivery(who, roleId, skuId, unique("PO-NEW"), DAY_NEW, "100", "2000", "USD", "PCS");
    return skuId;
  }

  /** 保存一条构成（成本法 + 币种 + 包装成本参数 + 生效时间 + 变更原因）。 */
  CostSaved save(
      AccessService.Access who,
      UUID skuId,
      String method,
      String currency,
      BigDecimal packaging,
      OffsetDateTime effectiveFrom,
      String reason,
      long version) {
    return write(
        who,
        () ->
            costs.save(
                who,
                skuId,
                new ProfileInput(method, currency, effectiveFrom, packaging, reason, version),
                "Bearer " + (who.tenantId().equals(a) ? "tenant-a" : "tenant-b")));
  }

  CostDetail detail(AccessService.Access who, UUID skuId, String currency) {
    return tx(
        who,
        () ->
            costs.detail(
                skuId,
                currency,
                DAY_OLD.minusDays(1),
                TODAY,
                "Bearer " + (who.tenantId().equals(a) ? "tenant-a" : "tenant-b")));
  }

  static CostComponentRow component(CostDetail detail, String code) {
    for (var component : detail.components()) {
      if (component.code().equals(code)) return component;
    }
    throw new AssertionError("没有这个分项：" + code);
  }

  // ================================================================ 成本法反例（本模块的核心）

  /**
   * 同一 SKU 在不同成本法下采购成本不同，且**历史可查**。
   *
   * <p>蓝图加粗句「成本口径必须在页面上明示用了哪种成本法，否则同一 SKU 今天显示毛利 30% 明天 25%
   * 无法解释」的技术落点：如果三种成本法算出同一个数，那「明示口径」就只是一句装饰 ——
   * 口径其实没起作用，而毛利波动仍然无法解释。
   */
  @Test
  void 同一SKU在不同成本法下采购成本不同且历史可查() {
    UUID skuId = twoLotSku(actor, "METHOD");
    var effective = TODAY.atStartOfDay().atOffset(ZoneOffset.UTC);
    var bearer = "Bearer tenant-a";

    // 1) 移动加权 = (1000 + 2000) / (100 + 100) = 15.00
    var v1 =
        save(actor, skuId, "MOVING_AVERAGE", "USD", null, effective, "初次维护：移动加权", 0);
    var moving = detail(actor, skuId, "USD");
    assertThat(moving.costMethod()).isEqualTo("MOVING_AVERAGE");
    assertThat(moving.costMethodLabel()).isEqualTo("移动加权");
    assertThat(component(moving, "PURCHASE").amount()).isEqualByComparingTo("15.00");
    // 明细页必须把口径写在脸上（成本法 + 生效时间 + 计价单位）。
    assertThat(moving.basis()).contains("移动加权").contains("USD").contains("PCS");
    assertThat(moving.effectiveFrom()).isNotNull();

    // 2) 换先进先出 = 10.00（消耗到最近一批的量为止，落在最早那一批）
    var v2 = save(actor, skuId, "FIFO", "USD", null, effective, "换口径：先进先出", v1.version());
    var fifo = detail(actor, skuId, "USD");
    assertThat(fifo.costMethod()).isEqualTo("FIFO");
    assertThat(component(fifo, "PURCHASE").amount()).isEqualByComparingTo("10.00");

    // 3) 换批次 = 20.00（最近一批的单价）
    save(actor, skuId, "LOT", "USD", null, effective, "换口径：批次", v2.version());
    var lot = detail(actor, skuId, "USD");
    assertThat(lot.costMethod()).isEqualTo("LOT");
    assertThat(component(lot, "PURCHASE").amount()).isEqualByComparingTo("20.00");

    // 🔴 三个数必须互不相同 —— 这才是「口径真的在参与算术」的证据。
    assertThat(
            List.of(
                component(moving, "PURCHASE").amount(),
                component(fifo, "PURCHASE").amount(),
                component(lot, "PURCHASE").amount()))
        .doesNotHaveDuplicates();

    // 4) 历史可查：三条快照各自记着**当时**的成本法与当时的采购成本。
    var revisions = tx(actor, () -> costs.revisions(skuId, "USD"));
    assertThat(revisions).hasSize(3);
    assertThat(revisions.get(0).revisionNo()).isEqualTo(3);
    assertThat(revisions.get(0).costMethod()).isEqualTo("LOT");
    assertThat(revisions.get(0).purchaseCost()).isEqualByComparingTo("20.00");
    assertThat(revisions.get(1).costMethod()).isEqualTo("FIFO");
    assertThat(revisions.get(1).purchaseCost()).isEqualByComparingTo("10.00");
    assertThat(revisions.get(2).costMethod()).isEqualTo("MOVING_AVERAGE");
    assertThat(revisions.get(2).purchaseCost()).isEqualByComparingTo("15.00");
    // 变更原因也在历史里 —— 半年后没人知道当时为什么换的口径。
    assertThat(revisions.get(0).changeReason()).isEqualTo("换口径：批次");
    assertThat(revisions.get(1).changeReason()).isEqualTo("换口径：先进先出");

    // 5) 批次法要能看到「有哪些批次、哪一批被选中」。
    assertThat(lot.lots()).hasSize(2);
    assertThat(lot.lots().stream().filter(LotRow::selected)).hasSize(1);
    assertThat(lot.lots().get(1).selected()).isTrue();
    assertThat(lot.lots().get(1).unitCost()).isEqualByComparingTo("20.00");
  }

  /** 换成本法**不覆盖历史**：旧版本原样留着（5.2.1.3 的「调整必须留历史版本，不能就地覆盖」）。 */
  @Test
  void 换成本法只追加历史版本而不覆盖旧版本() {
    UUID skuId = twoLotSku(actor, "HIST");
    var effective = TODAY.atStartOfDay().atOffset(ZoneOffset.UTC);
    var v1 = save(actor, skuId, "MOVING_AVERAGE", "USD", null, effective, "第一版", 0);
    save(actor, skuId, "LOT", "USD", null, effective, "第二版", v1.version());

    // 版本号递增，且第一版仍能查到。
    var revisions = tx(actor, () -> costs.revisions(skuId, "USD"));
    assertThat(revisions).hasSize(2);
    assertThat(revisions.get(0).revisionNo()).isEqualTo(2);
    assertThat(revisions.get(1).revisionNo()).isEqualTo(1);
    assertThat(revisions.get(1).costMethod()).isEqualTo("MOVING_AVERAGE");

    // DB 层：历史行是 append-only 的，只追加不更新。
    var count =
        tx(
                actor,
                () ->
                    sql.fetchOne(
                            "select count(*) from sku_cost_revision where tenant_id=? and sku_id=?",
                            a,
                            skuId)
                        .get(0, Long.class))
            .longValue();
    assertThat(count).isEqualTo(2);
  }

  /** 用过期版本号保存 ⇒ 409，不静默覆盖别人的修改。 */
  @Test
  void 用过期版本号保存成本构成会冲突() {
    UUID skuId = twoLotSku(actor, "VER");
    var effective = TODAY.atStartOfDay().atOffset(ZoneOffset.UTC);
    var v1 = save(actor, skuId, "MOVING_AVERAGE", "USD", null, effective, "第一版", 0);
    // 第二次保存成功（回传当前版本号），库里的版本随即 +1。
    var v2 = save(actor, skuId, "LOT", "USD", null, effective, "第二版", v1.version());
    assertThat(v2.version()).isEqualTo(v1.version() + 1);
    // 第三次仍拿着**过期**的版本号 ⇒ 409，不静默覆盖别人的修改。
    assertThatThrownBy(() -> save(actor, skuId, "FIFO", "USD", null, effective, "过期版本", v1.version()))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("版本冲突");
  }

  // ================================================================ 租户隔离三断言

  /**
   * 租户隔离三断言，<b>并且覆盖构成行、调整项行与历史快照行</b>。
   *
   * <p>只测主体等于没测：成本是租户自己的成本事实，「A 读到自己的采购成本时混进 B 的到货金额」
   * 会直接让 A 的毛利算错，而页面上看不出任何异常。
   */
  @Test
  void 租户隔离三断言覆盖构成调整与历史() throws Exception {
    UUID mySku = twoLotSku(actor, "ISO-A");
    UUID theirSku = twoLotSku(actorB, "ISO-B");
    var effective = TODAY.atStartOfDay().atOffset(ZoneOffset.UTC);

    save(actor, mySku, "MOVING_AVERAGE", "USD", new BigDecimal("0.5"), effective, "A 的构成", 0);
    save(actorB, theirSku, "LOT", "USD", new BigDecimal("9.9"), effective, "B 的构成", 0);
    write(
        actor,
        () ->
            costs.addAdjustment(
                actor,
                mySku,
                new AdjustmentInput(
                    "OTHER_DIRECT", new BigDecimal("3.5"), "USD", "A 的手工调整", effective),
                "Bearer tenant-a"));

    // 两边都必须真的有数据 —— 否则下面的断言就是空的。
    assertThat(component(detail(actor, mySku, "USD"), "OTHER_DIRECT").amount())
        .isEqualByComparingTo("3.5");
    assertThat(component(detail(actorB, theirSku, "USD"), "OTHER_DIRECT").amount())
        .isEqualByComparingTo("0.00");

    // 1) 两租户互不可见
    // A 读不到 B 的 SKU 的构成。
    assertThatThrownBy(() -> detail(actor, theirSku, "USD"))
        .isInstanceOf(ResponseStatusException.class);
    // B 读不到 A 的 SKU 的构成。
    assertThatThrownBy(() -> detail(actorB, mySku, "USD"))
        .isInstanceOf(ResponseStatusException.class);
    // A 读不到 B 的历史。
    assertThat(tx(actor, () -> costs.revisions(theirSku, "USD"))).isEmpty();
    // A 的列表里混不进 B 的 SKU。
    var myRows =
        tx(actor, () -> costs.list(new CostListQuery(null, null, null, null, null, null, 0, 200)))
            .items();
    assertThat(myRows).isNotEmpty();
    assertThat(myRows.stream().map(CostRow::skuId)).doesNotContain(theirSku);
    assertThat(myRows.stream().map(CostRow::skuId)).contains(mySku);
    // 直接查库也读不到对方的那一行（RLS 兜底，不只是服务层过滤）。
    assertThat(
            tx(
                    actor,
                    () ->
                        sql.fetchOne(
                                "select count(*) from sku_cost_profile where tenant_id=? and sku_id=?",
                                b,
                                mySku)
                            .get(0, Long.class))
                .longValue())
        .isZero();
    // 调整项同样互不可见。
    assertThat(
            tx(
                    actorB,
                    () ->
                        sql.fetchOne(
                                "select count(*) from sku_cost_adjustment where tenant_id=? and sku_id=?",
                                b,
                                mySku)
                            .get(0, Long.class))
                .longValue())
        .isZero();

    // 2) 伪造 X-Tenant-Id 头无效：租户只来自已认证成员关系
    http.perform(
            get("/catalog/sku-cost-compositions/" + theirSku)
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b)
                .param("currency", "USD"))
        .andExpect(status().isNotFound());
    http.perform(
            get("/catalog/sku-cost-compositions/" + mySku)
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a)
                .param("currency", "USD"))
        .andExpect(status().isNotFound());
    // 伪造头下的写入仍落在**调用者自己**名下 —— 断言行确实落在 A 名下，不只断言 B 看不见。
    http.perform(
            put("/catalog/sku-cost-compositions/" + mySku)
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b)
                .contentType("application/json")
                .content(
                    JSON.writeValueAsString(
                        new ProfileInput(
                            "FIFO", "USD", effective, null, "伪造头下的保存", 0))))
        .andExpect(status().isOk());
    assertThat(
            tx(
                    actor,
                    () ->
                        sql.fetchOne(
                                "select tenant_id, cost_method from sku_cost_profile where sku_id=?",
                                mySku)
                            .get(0, UUID.class)))
        .isEqualTo(a);
    assertThat(
            tx(
                    actor,
                    () ->
                        sql.fetchOne(
                                "select cost_method from sku_cost_profile where sku_id=?",
                                mySku)
                            .get(0, String.class)))
        .isEqualTo("FIFO");

    // 3) 连接池归还后不串租户（单连接反复交替），逐轮断言两个集合不相交
    for (int i = 0; i < 5; i++) {
      var mySet =
          tx(
                  actor,
                  () ->
                      costs.list(new CostListQuery(null, null, null, null, null, null, 0, 200))
                          .items()
                          .stream()
                          .map(CostRow::skuId)
                          .collect(java.util.stream.Collectors.toSet()));
      var theirSet =
          tx(
                  actorB,
                  () ->
                      costs.list(new CostListQuery(null, null, null, null, null, null, 0, 200))
                          .items()
                          .stream()
                          .map(CostRow::skuId)
                          .collect(java.util.stream.Collectors.toSet()));
      assertThat(mySet).isNotEmpty();
      assertThat(theirSet).isNotEmpty();
      assertThat(java.util.Collections.disjoint(mySet, theirSet)).isTrue();
      // 明细也不串：A 读 B 的 SKU 必须是异常，不是「读到了 B 的成本」。
      assertThatThrownBy(() -> detail(actor, theirSku, "USD"))
          .as("A 读 B 的成本构成必须失败而不是串过去")
          .isInstanceOf(ResponseStatusException.class);
    }
  }

  // ================================================================ 手工调整项必须带原因

  /** 手工调整项**必须带原因**（蓝图输入句「手工调整项及原因」），DB 层也有 CHECK 兜底。 */
  @Test
  void 手工调整项必须带原因() throws Exception {
    UUID skuId = twoLotSku(actor, "ADJ");
    var effective = TODAY.atStartOfDay().atOffset(ZoneOffset.UTC);
    save(actor, skuId, "MOVING_AVERAGE", "USD", null, effective, "先有构成", 0);

    // 1) 服务层：空原因被拒。
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        costs.addAdjustment(
                            actor,
                            skuId,
                            new AdjustmentInput(
                                "OTHER_DIRECT", new BigDecimal("1.0"), "USD", "   ", effective),
                            "Bearer tenant-a")))
        .isInstanceOf(ResponseStatusException.class);

    // 2) HTTP 层：空白原因 ⇒ 400。
    http.perform(
            post("/catalog/sku-cost-compositions/" + skuId + "/adjustments")
                .header("Authorization", "Bearer tenant-a")
                .contentType("application/json")
                .content(
                    JSON.writeValueAsString(
                        new AdjustmentInput(
                            "OTHER_DIRECT", new BigDecimal("1.0"), "USD", "  ", effective))))
        .andExpect(status().isBadRequest());

    // 3) DB 层：绕过服务层直连写入，空原因被 CHECK 挡住。
    assertThatThrownBy(
            () ->
                writeVoid(
                    actor,
                    () ->
                        sql.execute(
                            "insert into sku_cost_adjustment(id,tenant_id,sku_id,component,amount,currency,"
                                + "reason,effective_from,is_void,void_reason,created_by,created_at)"
                                + " values(?::uuid,?::uuid,?::uuid,'OTHER_DIRECT',1.0,'USD','',?::timestamptz,false,'',?::uuid,now())",
                            UUID.randomUUID(),
                            a,
                            skuId,
                            effective,
                            user)))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

    // 4) 有原因的调整能落库，且金额进入「其他直接成本」分项。
    write(
        actor,
        () ->
            costs.addAdjustment(
                actor,
                skuId,
                new AdjustmentInput(
                    "OTHER_DIRECT", new BigDecimal("2.25"), "USD", "供应商临时加价", effective),
                "Bearer tenant-a"));
    var detail = detail(actor, skuId, "USD");
    assertThat(component(detail, "OTHER_DIRECT").amount()).isEqualByComparingTo("2.25");
    assertThat(detail.adjustments()).hasSize(1);
    assertThat(detail.adjustments().get(0).reason()).isEqualTo("供应商临时加价");
  }

  /** 派生分项不能手工改（采购成本是派生值、头程与关税是上游事实）。 */
  @Test
  void 派生分项不允许手工调整() {
    UUID skuId = twoLotSku(actor, "DERIVED");
    var effective = TODAY.atStartOfDay().atOffset(ZoneOffset.UTC);
    save(actor, skuId, "MOVING_AVERAGE", "USD", null, effective, "先有构成", 0);
    for (String component : List.of("PURCHASE", "FIRST_MILE", "DUTY")) {
      assertThatThrownBy(
              () ->
                  write(
                      actor,
                      () ->
                          costs.addAdjustment(
                              actor,
                              skuId,
                              new AdjustmentInput(
                                  component, new BigDecimal("1.0"), "USD", "想改派生值", effective),
                              "Bearer tenant-a")))
          .as(component + " 是派生分项，不能手工改")
          .isInstanceOf(ResponseStatusException.class);
    }
  }

  /** 作废调整项必须给理由，且作废后该笔不再计入合计（但行还在库里）。 */
  @Test
  void 作废调整项要理由且不计入合计() {
    UUID skuId = twoLotSku(actor, "VOID");
    var effective = TODAY.atStartOfDay().atOffset(ZoneOffset.UTC);
    save(actor, skuId, "MOVING_AVERAGE", "USD", null, effective, "先有构成", 0);
    write(
        actor,
        () ->
            costs.addAdjustment(
                actor,
                skuId,
                new AdjustmentInput(
                    "OTHER_DIRECT", new BigDecimal("4.0"), "USD", "录错了", effective),
                "Bearer tenant-a"));
    assertThat(component(detail(actor, skuId, "USD"), "OTHER_DIRECT").amount())
        .isEqualByComparingTo("4.0");

    var adjustmentId = detail(actor, skuId, "USD").adjustments().get(0).id();
    // 空理由的作废被服务层拒。
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        costs.voidAdjustment(
                            actor, skuId, adjustmentId, new VoidInput("  "), "Bearer tenant-a")))
        .isInstanceOf(ResponseStatusException.class);

    write(
        actor,
        () ->
            costs.voidAdjustment(
                actor, skuId, adjustmentId, new VoidInput("录错了，冲回"), "Bearer tenant-a"));
    var after = detail(actor, skuId, "USD");
    assertThat(component(after, "OTHER_DIRECT").amount()).isEqualByComparingTo("0.00");
    // 行还在库里 —— 「这个 SKU 的成本历史上被改过几次」必须可追溯。
    assertThat(after.adjustments()).hasSize(1);
    assertThat(after.adjustments().get(0).isVoid()).isTrue();
    assertThat(after.adjustments().get(0).voidReason()).isEqualTo("录错了，冲回");
  }

  // ================================================================ 时间戳写回

  /**
   * 生效时间按**时刻**写回，不被时区偏移。
   *
   * <p>timestamptz 列必须用 {@code OffsetDateTime} 绑定 + {@code ?::timestamptz}。
   * 用 {@code Timestamp.valueOf(LocalDateTime...)} 会静默偏 −28800s（东八区）——
   * 而且不抛异常，只会让「成本从哪一刻起算数」悄悄偏 8 小时。
   * ⇒ 这里断言**写回的值**，不是只断言没抛异常。
   */
  @Test
  void 生效时间按时刻写回不被时区偏移() {
    UUID skuId = twoLotSku(actor, "TIME");
    // 东八区的 08:30 —— 落库后换算成 UTC 是 00:30。
    var effective = OffsetDateTime.of(2026, 3, 1, 8, 30, 0, 0, ZoneOffset.ofHours(8));
    save(actor, skuId, "MOVING_AVERAGE", "USD", null, effective, "时间戳断言", 0);

    var written =
        tx(
                actor,
                () ->
                    sql.fetchOne(
                            "select effective_from from sku_cost_profile where sku_id=?", skuId)
                        .get(0, OffsetDateTime.class))
            .toInstant();
    assertThat(written).isEqualTo(effective.toInstant());
    // 反例断言：如果错用 Timestamp.valueOf(LocalDateTime)，这里会偏 8 小时。
    assertThat(written).isNotEqualTo(effective.toLocalDateTime().atOffset(ZoneOffset.UTC).toInstant());

    // 历史快照里的生效时间也必须是同一个时刻。
    var revision =
        tx(actor, () -> costs.revisions(skuId, "USD")).get(0);
    assertThat(revision.effectiveFrom().toInstant()).isEqualTo(effective.toInstant());
  }

  // ================================================================ 上游不可达

  /**
   * 上游不可达 ⇒ 读端点返回 503，**不**把头程与关税当成 0。
   *
   * <p>拿不到时显示 0 会让单位成本偏低、毛利偏高，而页面上看不出任何异常 ——
   * 这是本模块最贵的错误（它不会报错，它只会让利润算错）。
   */
  @Test
  void 上游不可达时读端点返回503而不是把头程与关税当成0() throws Exception {
    UUID skuId = twoLotSku(actor, "DOWN");
    var effective = TODAY.atStartOfDay().atOffset(ZoneOffset.UTC);
    save(actor, skuId, "MOVING_AVERAGE", "USD", null, effective, "先有构成", 0);

    warehouseDown.set(true);
    try {
      assertThatThrownBy(() -> detail(actor, skuId, "USD"))
          .isInstanceOf(ResponseStatusException.class)
          .satisfies(
              e -> {
                var status = (ResponseStatusException) e;
                assertThat(status.getStatusCode().value()).isEqualTo(503);
                assertThat(status.getReason()).contains("读不到");
              });
      // HTTP 层同样是 503，不是 200 + 空集合。
      http.perform(
              get("/catalog/sku-cost-compositions/" + skuId)
                  .header("Authorization", "Bearer tenant-a")
                  .param("currency", "USD"))
          .andExpect(status().isServiceUnavailable());
    } finally {
      warehouseDown.set(false);
    }
  }

  /** 跨服务调用必须透传调用方自己的 Authorization 头（租户由 warehouse 从成员关系解析）。 */
  @Test
  void 跨服务调用透传调用方的Authorization头() {
    UUID skuId = twoLotSku(actor, "BEARER");
    var effective = TODAY.atStartOfDay().atOffset(ZoneOffset.UTC);
    save(actor, skuId, "MOVING_AVERAGE", "USD", null, effective, "先有构成", 0);
    lastBearer.set(null);
    detail(actor, skuId, "USD");
    assertThat(lastBearer.get()).isEqualTo("Bearer tenant-a");
  }

  // ================================================================ 来源标注

  /**
   * 采购成本的来源必须**明示**：实际到货金额，不是报价。
   *
   * <p>6.3 采购单未交付 ⇒ 采购成本取 catalog 同库的实际到货事实；
   * 供应商报价只作只读参考，**不计入合计**。
   */
  @Test
  void 采购成本标注明示来源且报价不计入合计() {
    UUID roleId = supplierRole(actor, "SRC-SUP");
    UUID skuId = sku(actor, "SRC-SKU");
    delivery(actor, roleId, skuId, unique("PO-SRC"), DAY_OLD, "10", "100", "USD", "PCS");
    var effective = TODAY.atStartOfDay().atOffset(ZoneOffset.UTC);
    save(actor, skuId, "MOVING_AVERAGE", "USD", null, effective, "先有构成", 0);

    var detail = detail(actor, skuId, "USD");
    var purchase = component(detail, "PURCHASE");
    // 来源码 = catalog 同库的实际到货事实。
    assertThat(purchase.source()).isEqualTo("CATALOG_VENDOR_DELIVERY");
    assertThat(purchase.kind()).isEqualTo("DERIVED");
    // 口径说明里必须点明「不是 06 采购单的成交价」与「报价另作只读参考」。
    assertThat(purchase.basis()).contains("vendor_delivery_record").contains("不是 06 采购单");
    // 采购成本本身 = 100 / 10 = 10.00
    assertThat(purchase.amount()).isEqualByComparingTo("10.00");
    // 合计里只有这个 10.00（头程 / 关税没有上游事实 ⇒ 取不到）。
    assertThat(detail.totalAmount()).isEqualByComparingTo("10.00");
    assertThat(detail.totalComplete()).isFalse();
    assertThat(detail.missingComponents()).contains("FIRST_MILE", "DUTY");
    // 报价参考单列，且明确写着「不是采购成本」「不计入合计」。
    assertThat(detail.quoteReference().note()).contains("报价不是采购成本").contains("不计入");
  }

  /** 包装成本是**参数**：没维护就显示「未维护」，不显示 0.00。 */
  @Test
  void 包装成本未维护时标为取不到而不是0() {
    UUID skuId = twoLotSku(actor, "PACK");
    var effective = TODAY.atStartOfDay().atOffset(ZoneOffset.UTC);
    var v0 = save(actor, skuId, "MOVING_AVERAGE", "USD", null, effective, "不填包装成本", 0);
    var detail = detail(actor, skuId, "USD");
    var packaging = component(detail, "PACKAGING");
    assertThat(packaging.available()).isFalse();
    assertThat(packaging.amount()).isNull();
    assertThat(packaging.kind()).isEqualTo("PARAMETER");
    assertThat(packaging.unavailableReason()).contains("未维护");
    // 维护之后就有值了。
    save(actor, skuId, "MOVING_AVERAGE", "USD", new BigDecimal("0.35"), effective, "补上包装成本", v0.version());
    var after = detail(actor, skuId, "USD");
    assertThat(component(after, "PACKAGING").available()).isTrue();
    assertThat(component(after, "PACKAGING").amount()).isEqualByComparingTo("0.35");
  }

  /** 折算不出基础数量的到货行：排除并如实报出条数，采购成本分项标为「部分」。 */
  @Test
  void 折算不出基础数量的到货行被排除并如实报出条数() {
    UUID roleId = supplierRole(actor, "CONV-SUP");
    UUID skuId = sku(actor, "CONV-SKU");
    // PCS = 基础单位（可折算）；BOX 没有换算版本 ⇒ 这一行折不出来。
    delivery(actor, roleId, skuId, unique("PO-OK"), DAY_OLD, "10", "100", "USD", "PCS");
    delivery(actor, roleId, skuId, unique("PO-BOX"), DAY_OLD, "2", "80", "USD", "BOX");
    var effective = TODAY.atStartOfDay().atOffset(ZoneOffset.UTC);
    save(actor, skuId, "MOVING_AVERAGE", "USD", null, effective, "先有构成", 0);

    var detail = detail(actor, skuId, "USD");
    assertThat(detail.excludedDeliveryCount()).isEqualTo(1);
    var purchase = component(detail, "PURCHASE");
    assertThat(purchase.partial()).isTrue();
    assertThat(purchase.amount()).isEqualByComparingTo("10.00");
    assertThat(purchase.note()).contains("1").contains("排除");
    // 合计是「已知部分合计」，不能拿去直接算利润。
    assertThat(detail.totalComplete()).isFalse();
    assertThat(detail.partialComponents()).contains("PURCHASE");
    assertThat(detail.note()).contains("已知部分合计");
  }

  /** 有换算版本时，数量折算到基础单位再相除。 */
  @Test
  void 到货数量按换算版本折算到基础单位() {
    UUID roleId = supplierRole(actor, "BOX-SUP");
    UUID skuId = sku(actor, "BOX-SKU");
    // 1 箱 = 12 件；到货 2 箱共 240 元 ⇒ 每件 10.00。
    purchaseConversion(actor, skuId, "BOX", "12");
    delivery(actor, roleId, skuId, unique("PO-BOX-OK"), DAY_OLD, "2", "240", "USD", "BOX");
    var effective = TODAY.atStartOfDay().atOffset(ZoneOffset.UTC);
    save(actor, skuId, "MOVING_AVERAGE", "USD", null, effective, "先有构成", 0);

    var detail = detail(actor, skuId, "USD");
    assertThat(detail.excludedDeliveryCount()).isZero();
    assertThat(component(detail, "PURCHASE").amount()).isEqualByComparingTo("10.00");
  }

  /** 头程与关税来自 warehouse，按上游事实折成单位成本。 */
  @Test
  void 头程与关税从warehouse读来并折成单位成本() {
    UUID skuId = twoLotSku(actor, "UPSTREAM");
    shipment(a, skuId, "USD", "100", "100");
    dutyEstimate(a, skuId, "USD", "PCS", "100", "50");
    var effective = TODAY.atStartOfDay().atOffset(ZoneOffset.UTC);
    save(actor, skuId, "MOVING_AVERAGE", "USD", null, effective, "先有构成", 0);

    var detail = detail(actor, skuId, "USD");
    // 头程：100 摊到 100 件 ⇒ 1.00/件
    assertThat(component(detail, "FIRST_MILE").amount()).isEqualByComparingTo("1.00");
    assertThat(component(detail, "FIRST_MILE").source()).isEqualTo("WAREHOUSE_FIRST_MILE");
    // 关税：50 / 100 = 0.50/件（增值税不并入关税）
    assertThat(component(detail, "DUTY").amount()).isEqualByComparingTo("0.50");
    assertThat(component(detail, "DUTY").source()).isEqualTo("WAREHOUSE_DUTY_TAX");
    // 合计 = 15.00 + 1.00 + 0.50（包装未维护 ⇒ 缺项，合计是「已知部分合计」）
    assertThat(detail.totalAmount()).isEqualByComparingTo("16.50");
    assertThat(detail.missingComponents()).contains("PACKAGING");
  }

  // ================================================================ 能力声明

  /** 能力声明必须点名「6.3 采购单未交付」与「12 财务不存在」，且列出三种成本法。 */
  @Test
  void 能力声明明示口径来源与未交付的上游() {
    var capabilities = tx(actor, () -> costs.capabilities());
    assertThat(capabilities.costMethods()).hasSize(3);
    assertThat(capabilities.costMethods())
        .extracting(MethodOption::value)
        .containsExactly("MOVING_AVERAGE", "FIFO", "LOT");
    assertThat(capabilities.costBasisNote()).contains("成本法");

    var purchase =
        capabilities.components().stream()
            .filter(c -> c.code().equals("PURCHASE"))
            .findFirst()
            .orElseThrow();
    assertThat(purchase.source()).isEqualTo("CATALOG_VENDOR_DELIVERY");
    assertThat(purchase.impact()).contains("6.3 采购单").contains("未交付");
    assertThat(purchase.impact()).contains("报价").contains("不计入");

    var duty =
        capabilities.components().stream()
            .filter(c -> c.code().equals("DUTY"))
            .findFirst()
            .orElseThrow();
    assertThat(duty.ownerModule()).contains("10.5.1.3");
    assertThat(duty.impact()).contains("12 财务").contains("不存在");
  }
}
