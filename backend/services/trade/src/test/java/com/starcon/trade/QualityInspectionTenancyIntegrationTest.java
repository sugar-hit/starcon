package com.starcon.trade;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jooq.DSLContext;
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
 * 6.4.2.1 质检单的<b>多租户隔离</b>复验。
 *
 * <p>跑的是 AGENTS.md 明文要求的三条断言：
 *
 * <ol>
 *   <li>两租户互不可见；</li>
 *   <li>伪造租户上下文无效；</li>
 *   <li>连接池归还后不串租户。</li>
 * </ol>
 *
 * <p><b>真实 PostgreSQL 16 + FORCE RLS，不使用任何内存替身。</b>
 *
 * <p><b>连接池固定为 1 条连接</b>：只有复用同一条连接，才能真正证明归还后不会串租户 ——
 * 池大于 1 时每次可能拿到不同连接，「没串」可能只是碰巧换了条干净连接。
 *
 * <p>⚠️ 直查断言全部走**应用账号**（{@code trade_app}）—— 用超级用户连会绕过 RLS，
 * 断言本身失效。
 */
@SpringBootTest
@AutoConfigureMockMvc
class QualityInspectionTenancyIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity, warehouse, catalog;
  static final ObjectMapper json =
      new ObjectMapper()
          .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
          .enable(
              com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  static final UUID receiptId = UUID.randomUUID();
  static final String RECEIPT_CODE = "GR-TENANT-0001";
  static final UUID skuId = UUID.nameUUIDFromBytes("starcon-quality-tenancy-sku".getBytes());
  static final String SKU_CODE = "SKU-TENANT";

  static final List<String> allPermissions =
      List.of(
          "trade.quality-inspection.read",
          "trade.quality-inspection.update",
          "trade.quality-inspection.concession");

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

  static AccessService.Grant grant(String scope, List<String> permissions) {
    return new AccessService.Grant(UUID.randomUUID(), "TEST", scope, permissions);
  }

  static void write(HttpServer server, com.sun.net.httpserver.HttpExchange e, int code, Object body)
      throws java.io.IOException {
    byte[] bytes = json.writeValueAsBytes(body);
    e.getResponseHeaders().add("Content-Type", "application/json");
    e.sendResponseHeaders(code, bytes.length);
    e.getResponseBody().write(bytes);
    e.close();
  }

  static UUID tenantOf(String bearer) {
    var access = actors.get(bearer);
    return access == null ? null : access.tenantId();
  }

  /** 造一个键值对多于 10 个的 map（{@code Map.of} 最多只收 10 对）。 */
  static Map<String, Object> map(Object... keyValues) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (int i = 0; i + 1 < keyValues.length; i += 2) {
      result.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
    }
    return result;
  }

  static {
    pg.start();
    try {
      try (var c =
              DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
          var s = c.createStatement()) {
        s.execute("create role starcon_app nologin");
        // 运行角色必须 NOBYPASSRLS，否则它会绕过策略，三条断言全部变成空断言。
        s.execute("create role trade_app login password 'test-only' nosuperuser nobypassrls");
      }
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
            byte[] body = json.writeValueAsBytes(access);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      actors.put("Bearer tenant-a", actor(user, a, List.of(grant("ALL", allPermissions))));
      actors.put("Bearer tenant-b", actor(user, b, List.of(grant("ALL", allPermissions))));
      identity.start();

      // ---- warehouse stub：收货单（两个租户共用同一份上游事实）----
      warehouse = HttpServer.create(new InetSocketAddress(0), 0);
      warehouse.createContext(
          "/warehouse/goods-receipts",
          e -> {
            if (tenantOf(e.getRequestHeaders().getFirst("Authorization")) == null) {
              write(warehouse, e, 401, Map.of("message", "未认证"));
              return;
            }
            String path = e.getRequestURI().getPath();
            if (path.equals("/warehouse/goods-receipts")) {
              write(
                  warehouse,
                  e,
                  200,
                  Map.of(
                      "rows",
                      List.of(Map.of("id", receiptId.toString(), "receiptCode", RECEIPT_CODE)),
                      "total",
                      1,
                      "page",
                      0,
                      "size",
                      20));
              return;
            }
            write(
                warehouse,
                e,
                200,
                map(
                    "id",
                    receiptId.toString(),
                    "receiptCode",
                    RECEIPT_CODE,
                    "sourceType",
                    "TRANSFER",
                    "sourceRefCode",
                    "TO-2026-0001",
                    "warehouseCode",
                    "WH-LA-01",
                    "status",
                    "RECEIVED",
                    "expectedQuantity",
                    100,
                    "receivedQuantity",
                    100,
                    "rejectedQuantity",
                    0,
                    "outstandingQuantity",
                    0,
                    "lines",
                    List.of(
                        Map.of(
                            "id",
                            UUID.nameUUIDFromBytes("tenant-line".getBytes()).toString(),
                            "skuId",
                            skuId.toString(),
                            "skuCode",
                            SKU_CODE,
                            "skuName",
                            "租户测试商品",
                            "qualityStatusCode",
                            "PENDING_INSPECTION",
                            "qualityStatusName",
                            "待检",
                            "expectedQuantity",
                            100,
                            "receivedQuantity",
                            100,
                            "rejectedQuantity",
                            0,
                            "outstandingQuantity",
                            0))));
          });
      warehouse.start();

      // ---- catalog stub：物理安全属性（该 SKU 无档案 ⇒ NOT_COLLECTED）----
      catalog = HttpServer.create(new InetSocketAddress(0), 0);
      catalog.createContext(
          "/catalog/physical-safety",
          e -> {
            if (tenantOf(e.getRequestHeaders().getFirst("Authorization")) == null) {
              write(catalog, e, 401, Map.of("message", "未认证"));
              return;
            }
            write(catalog, e, 404, Map.of("message", "没有安全属性档案"));
          });
      catalog.start();
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
    r.add("WAREHOUSE_URL", () -> "http://localhost:" + warehouse.getAddress().getPort());
    r.add("CATALOG_URL", () -> "http://localhost:" + catalog.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
    // 单连接：只有复用同一条连接才能证明连接池归还后不串租户。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  @AfterAll
  static void stop() {
    identity.stop(0);
    warehouse.stop(0);
    catalog.stop(0);
    pg.stop();
  }

  @Autowired AccessService access;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  // ================================================================ 数据准备

  @BeforeEach
  void seed() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate quality_inspection_concession, quality_inspection_order_item,"
              + " quality_inspection_order, quality_inspection_template_item,"
              + " quality_inspection_template cascade");
    }
  }

  // ================================================================ 工具

  @SuppressWarnings("unchecked")
  Map<String, Object> body(String payload) throws Exception {
    return json.readValue(payload, Map.class);
  }

  @SuppressWarnings("unchecked")
  List<Map<String, Object>> rowsOf(String payload) throws Exception {
    return (List<Map<String, Object>>) body(payload).getOrDefault("rows", List.of());
  }

  /** 建一个模板（两个租户各建各的）。 */
  String createTemplate(String bearer, String code) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("code", code);
    body.put("name", "模板 " + code);
    body.put("inspectionType", "INCOMING");
    body.put("samplingRatio", new BigDecimal("0.1"));
    body.put("minSampleQuantity", new BigDecimal("1"));
    body.put("maxSampleQuantity", new BigDecimal("0"));
    body.put("roundingMode", "CEILING");
    body.put("criticalFailureAction", "DEDUCT_ONLY");
    body.put("defectiveTarget", "PENDING_INSPECTION");
    body.put(
        "items",
        List.of(
            Map.of(
                "itemCode",
                "APPEARANCE",
                "name",
                "外观",
                "critical",
                false,
                "acceptanceCriteria",
                "无划痕")));
    String payload =
        http.perform(
                post("/trade/quality-inspections/templates")
                    .header("Authorization", bearer)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(body)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return String.valueOf(json.readTree(payload).get("id").asText());
  }

  /** 建一张质检单。 */
  String createOrder(String bearer, String templateId) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("templateId", templateId);
    body.put("skuCode", SKU_CODE);
    body.put("receiptCode", RECEIPT_CODE);
    String payload =
        http.perform(
                post("/trade/quality-inspections")
                    .header("Authorization", bearer)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(body)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return String.valueOf(json.readTree(payload).get("id").asText());
  }

  String orders(String bearer) throws Exception {
    return http.perform(get("/trade/quality-inspections").header("Authorization", bearer))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  /** 当前连接上 {@code app.current_tenant} 的**原始**值。 */
  String rawTenantSetting() {
    return sql.fetch("select current_setting('app.current_tenant', true)").stream()
        .findFirst()
        .map(r -> r.get(0, String.class))
        .orElse(null);
  }

  /** RLS 策略**实际**使用的值：空串与 NULL 都归一成 null，也就是「没有租户」。 */
  UUID effectiveTenant() {
    var row = sql.fetchOne("select nullif(current_setting('app.current_tenant', true),'')::uuid");
    return row == null ? null : row.get(0, UUID.class);
  }

  /** 事务外读：没有任何租户上下文时能看见几行。 */
  int visibleWithoutTenant() {
    return sql.fetch("select id from quality_inspection_order").size();
  }

  // ================================================================ 断言 1

  @Test
  void 断言1_两租户互不可见() throws Exception {
    String templateA = createTemplate("Bearer tenant-a", "TPL-TENANT-A");
    String templateB = createTemplate("Bearer tenant-b", "TPL-TENANT-B");
    String orderA = createOrder("Bearer tenant-a", templateA);
    String orderB = createOrder("Bearer tenant-b", templateB);

    var rowsA = rowsOf(orders("Bearer tenant-a"));
    var rowsB = rowsOf(orders("Bearer tenant-b"));
    assertThat(rowsA).hasSize(1);
    assertThat(rowsB).hasSize(1);
    assertThat(rowsA.get(0).get("id")).isEqualTo(orderA);
    assertThat(rowsB.get(0).get("id")).isEqualTo(orderB);

    // 更强的一格：A 明确按 ID 查 B 的行也拿不到（不只是列表过滤）。
    http.perform(
            get("/trade/quality-inspections/" + orderB).header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isNotFound());

    // 模板同样互不可见。
    http.perform(
            get("/trade/quality-inspections/templates/" + templateB)
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isNotFound());

    // 汇总视图各自只算自己的单。
    String summaryA =
        http.perform(
                get("/trade/quality-inspections/summary").header("Authorization", "Bearer tenant-a"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(json.readTree(summaryA).get("orderCount").asLong()).isEqualTo(1L);
  }

  // ================================================================ 断言 2

  @Test
  void 断言2_伪造租户上下文无效() throws Exception {
    String templateA = createTemplate("Bearer tenant-a", "TPL-FORGED-A");
    String templateB = createTemplate("Bearer tenant-b", "TPL-FORGED-B");
    createOrder("Bearer tenant-a", templateA);
    createOrder("Bearer tenant-b", templateB);

    // (1) HTTP 边界上没有伪造入口：X-Tenant-Id 不改任何东西。
    String honest = orders("Bearer tenant-a");
    String forged =
        http.perform(
                get("/trade/quality-inspections")
                    .header("Authorization", "Bearer tenant-a")
                    .header("X-Tenant-Id", b.toString()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(forged).as("伪造 X-Tenant-Id 不得改变看到的那份清单").isEqualTo(honest);
    assertThat(rowsOf(forged)).hasSize(1);

    // 伪造头也不能让写入落到别的租户：用 A 的身份建一张带伪造头的单，
    // 然后用 B 的身份查 —— 查不到，且**行确实落在自称的租户名下**。
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("templateId", templateA);
    body.put("skuCode", SKU_CODE);
    body.put("receiptCode", RECEIPT_CODE);
    String created =
        http.perform(
                post("/trade/quality-inspections")
                    .header("Authorization", "Bearer tenant-a")
                    .header("X-Tenant-Id", b.toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(body)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String createdId = String.valueOf(json.readTree(created).get("id").asText());
    // B 只看得到**自己那一张**（伪造头建的那张不在 B 的清单里）。
    var rowsB = rowsOf(orders("Bearer tenant-b"));
    assertThat(rowsB).hasSize(1);
    assertThat(rowsB.get(0).get("id")).isNotEqualTo(createdId);
    // 行确实落在**自称的租户**名下：直查必须走应用账号（trade_app），
    // 而且必须在该租户的 RLS 事务里查 —— 事务外查是零行（这本身就是隔离的证据）。
    UUID landedTenant =
        access.run(
            actors.get("Bearer tenant-a"),
            "trade.quality-inspection.read",
            () ->
                sql.fetchOne(
                        "select tenant_id from quality_inspection_order where id=?::uuid",
                        UUID.fromString(createdId))
                    .get(0, UUID.class));
    assertThat(landedTenant).isEqualTo(a);

    // (2) 事务外没有「环境租户」：app.current_tenant 要么未设置、要么已被复位成空串，
    //     两种情况归一后都是 null，于是任何租户的行都看不见。
    assertThat(rawTenantSetting()).isNullOrEmpty();
    assertThat(effectiveTenant()).isNull();
    assertThat(visibleWithoutTenant()).as("未绑定租户时不应看到任何行").isZero();

    // (3) 事务内绑定的值精确等于可信参数（来自已认证成员关系），提交后立刻失效。
    var bound =
        access.run(
            actors.get("Bearer tenant-b"),
            "trade.quality-inspection.read",
            () -> {
              assertThat(effectiveTenant()).as("事务内只看得到本租户").isEqualTo(b);
              return rawTenantSetting();
            });
    assertThat(bound).isEqualTo(b.toString());
    assertThat(effectiveTenant()).as("提交后租户上下文必须已清空").isNull();
    assertThat(visibleWithoutTenant()).isZero();
  }

  // ================================================================ 断言 3

  @Test
  void 断言3_连接池归还后不串租户() throws Exception {
    String templateA = createTemplate("Bearer tenant-a", "TPL-POOL-A");
    String templateB = createTemplate("Bearer tenant-b", "TPL-POOL-B");
    String orderA = createOrder("Bearer tenant-a", templateA);
    String orderB = createOrder("Bearer tenant-b", templateB);

    // 池大小为 1：下面每次读写都复用同一条连接，只有事务局部绑定 + 提交即清空
    // 才能保证第 N 次读到的仍然是 B 而不是上一轮残留的 A。
    for (int i = 0; i < 5; i++) {
      var rowsA = rowsOf(orders("Bearer tenant-a"));
      var rowsB = rowsOf(orders("Bearer tenant-b"));
      assertThat(rowsA).as("第 %d 轮租户 A", i).hasSize(1);
      assertThat(rowsB).as("第 %d 轮租户 B", i).hasSize(1);
      assertThat(rowsA.get(0).get("id")).as("第 %d 轮租户 A", i).isEqualTo(orderA);
      assertThat(rowsB.get(0).get("id")).as("第 %d 轮租户 B", i).isEqualTo(orderB);
      assertThat(rowsA.get(0).get("id")).isNotEqualTo(rowsB.get(0).get("id"));
      assertThat(visibleWithoutTenant()).as("第 %d 轮事务外", i).isZero();
    }
    // 归还后再单独借一次，仍然干净。
    assertThat(rowsOf(orders("Bearer tenant-a")).get(0).get("id"))
        .isNotEqualTo(rowsOf(orders("Bearer tenant-b")).get(0).get("id"));
  }

  // ================================================================ 跨租户写入

  @Test
  void 跨租户按id操作会被拒() throws Exception {
    String templateA = createTemplate("Bearer tenant-a", "TPL-XW-A");
    String orderA = createOrder("Bearer tenant-a", templateA);
    // B 拿着 A 的质检单 id 去改量 ⇒ 404（RLS 下查不到），不是 403（403 会泄露存在性）。
    http.perform(
            post("/trade/quality-inspections/" + orderA + "/cancel")
                .header("Authorization", "Bearer tenant-b")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("cancelReason", "跨租户作废"))))
        .andExpect(status().isNotFound());
  }
}
