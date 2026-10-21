package com.starcon.trade;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.ArrayList;
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
 * 6.2.1.2 补货建议的**多租户隔离**复验。
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
 * <p>本测试刻意不碰任何卷 06 的其它业务形状：采购计划（6.2.1.3）与采购申请（6.2.2.1）
 * 属于后续车道。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReplenishmentSuggestionTenancyIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity, warehouse, catalog;
  static final ObjectMapper json =
      new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  static final LocalDate AS_OF = LocalDate.of(2026, 1, 15);

  static final List<String> allPermissions =
      List.of(
          "trade.replenishment.read",
          "trade.replenishment.update",
          "trade.stockout.read",
          "trade.stockout.update");

  /** 租户 → 该租户 warehouse 的可用量行。 */
  static final Map<UUID, List<Map<String, Object>>> stockRows = new ConcurrentHashMap<>();

  /** 租户 → SKU id → 报价候选。 */
  static final Map<UUID, Map<UUID, List<Map<String, Object>>>> quoteRows = new ConcurrentHashMap<>();

  /** 租户 → SKU id → SKU 详情。 */
  static final Map<UUID, Map<UUID, Map<String, Object>>> skuRows = new ConcurrentHashMap<>();

  static UUID skuFor(UUID tenant, int ordinal) {
    return UUID.nameUUIDFromBytes(
        ("starcon-replenishment-tenancy-sku-" + tenant + "-" + ordinal).getBytes());
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

  static Map<String, Object> stockRow(UUID skuId, String code, String sellable) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("skuId", skuId.toString());
    row.put("skuCode", code);
    row.put("skuName", code + " 名称");
    row.put("onHand", new BigDecimal(sellable));
    row.put("reserved", BigDecimal.ZERO);
    row.put("available", new BigDecimal(sellable));
    row.put("sellableBeforeBuffer", new BigDecimal(sellable));
    row.put("safetyBuffer", BigDecimal.ZERO);
    row.put("sellable", new BigDecimal(sellable));
    return row;
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

      warehouse = HttpServer.create(new InetSocketAddress(0), 0);
      warehouse.createContext(
          "/warehouse/inventory/sellable/skus",
          e -> {
            UUID tenant = tenantOf(e.getRequestHeaders().getFirst("Authorization"));
            if (tenant == null) {
              write(warehouse, e, 401, Map.of("message", "未认证"));
              return;
            }
            var rows = stockRows.getOrDefault(tenant, List.of());
            write(
                warehouse,
                e,
                200,
                Map.of("rows", rows, "total", rows.size(), "page", 0, "size", 200));
          });
      warehouse.createContext(
          "/warehouse/inventory/in-transit",
          e -> {
            UUID tenant = tenantOf(e.getRequestHeaders().getFirst("Authorization"));
            if (tenant == null) {
              write(warehouse, e, 401, Map.of("message", "未认证"));
              return;
            }
            write(warehouse, e, 200, Map.of("rows", List.of(), "total", 0, "page", 0, "size", 200));
          });
      warehouse.start();

      catalog = HttpServer.create(new InetSocketAddress(0), 0);
      catalog.createContext(
          "/catalog/supplier-quotes/effective",
          e -> {
            UUID tenant = tenantOf(e.getRequestHeaders().getFirst("Authorization"));
            if (tenant == null) {
              write(catalog, e, 401, Map.of("message", "未认证"));
              return;
            }
            var params = new LinkedHashMap<String, String>();
            String raw = e.getRequestURI().getRawQuery();
            if (raw != null) {
              for (String pair : raw.split("&")) {
                int eq = pair.indexOf('=');
                params.put(
                    eq < 0 ? pair : pair.substring(0, eq),
                    eq < 0 ? "" : pair.substring(eq + 1));
              }
            }
            UUID skuId = UUID.fromString(params.get("skuId"));
            List<Map<String, Object>> candidates =
                quoteRows.getOrDefault(tenant, Map.of()).getOrDefault(skuId, List.of());
            write(
                catalog,
                e,
                200,
                Map.of("candidates", candidates, "ambiguous", false, "note", ""));
          });
      catalog.createContext(
          "/catalog/skus/",
          e -> {
            UUID tenant = tenantOf(e.getRequestHeaders().getFirst("Authorization"));
            if (tenant == null) {
              write(catalog, e, 401, Map.of("message", "未认证"));
              return;
            }
            String path = e.getRequestURI().getPath();
            UUID skuId = UUID.fromString(path.substring(path.lastIndexOf('/') + 1));
            Map<String, Object> sku = skuRows.getOrDefault(tenant, Map.of()).get(skuId);
            if (sku == null) {
              write(catalog, e, 404, Map.of("message", "商品不存在"));
              return;
            }
            write(catalog, e, 200, sku);
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

  @BeforeEach
  void seed() throws Exception {
    stockRows.clear();
    quoteRows.clear();
    skuRows.clear();
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate replenishment_suggestion_decision, replenishment_suggestion_step,"
              + " replenishment_suggestion, replenishment_suggestion_batch,"
              + " replenishment_coverage_policy, replenishment_order_policy,"
              + " stockout_sales_velocity cascade");
    }
    for (UUID tenant : List.of(a, b)) {
      String p = tenant.equals(a) ? "A" : "B";
      UUID sku = skuFor(tenant, 1);
      stockRows.put(tenant, List.of(stockRow(sku, "SKU-" + p + "-01", "50")));

      Map<String, Object> quote = new LinkedHashMap<>();
      quote.put("id", UUID.randomUUID().toString());
      quote.put("supplierRoleId", UUID.randomUUID().toString());
      quote.put("supplierId", UUID.randomUUID().toString());
      quote.put("supplierName", "宏远科技");
      quote.put("purchaseUnitCode", "CTN");
      quote.put("currency", "USD");
      quote.put("moqQuantity", BigDecimal.ONE);
      quote.put("leadTimeDays", 15);
      quote.put("priority", 1);
      Map<String, Object> tier = new LinkedHashMap<>();
      tier.put("minQuantity", BigDecimal.ONE);
      tier.put("unitPrice", new BigDecimal("14.2857"));
      quote.put("tiers", List.of(tier));
      quoteRows.put(tenant, Map.of(sku, List.of(quote)));

      Map<String, Object> skuDetail = new LinkedHashMap<>();
      skuDetail.put("id", sku.toString());
      skuDetail.put("code", "SKU-" + p + "-01");
      skuDetail.put("name", "SKU-" + p + "-01 名称");
      skuDetail.put("category", "CAT-01");
      skuDetail.put("baseUnit", "PCS");
      Map<String, Object> conversion = new LinkedHashMap<>();
      conversion.put("unitCode", "CTN");
      conversion.put("role", "PURCHASE");
      conversion.put("quantityPerBase", new BigDecimal("12"));
      conversion.put("current", true);
      skuDetail.put("unitConversions", List.of(conversion));
      skuRows.put(tenant, Map.of(sku, skuDetail));
    }
  }

  // ================================================================ 工具

  void registerVelocity(String bearer, UUID skuId, String code) throws Exception {
    http.perform(
            post("/trade/stockout-risk/velocities")
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "skuId", skuId.toString(),
                            "skuCode", code,
                            "dailySalesQuantity", new BigDecimal("10"),
                            "windowStart", "2025-12-01",
                            "windowEnd", "2025-12-31",
                            "effectiveFrom", "2026-01-01",
                            "sourceKind", "MANUAL"))))
        .andExpect(status().isOk());
  }

  void compute(String bearer) throws Exception {
    http.perform(
            post("/trade/replenishment-suggestions/batches")
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of("asOfDate", AS_OF.toString(), "defaultCoverageDays", 30))))
        .andExpect(status().isOk());
  }

  String suggestions(String bearer) throws Exception {
    return http.perform(
            get("/trade/replenishment-suggestions/suggestions").header("Authorization", bearer))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  @SuppressWarnings("unchecked")
  List<Map<String, Object>> rowsOf(String payload) throws Exception {
    Map<String, Object> parsed = json.readValue(payload, Map.class);
    return (List<Map<String, Object>>) parsed.getOrDefault("rows", List.of());
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
    return sql.fetch("select id from replenishment_suggestion").size();
  }

  // ================================================================ 迁移确实落地了，且 RLS 是**强制**的

  @Test
  void 本模块的表强制rls而不是只启用() {
    for (String table :
        List.of(
            "replenishment_coverage_policy",
            "replenishment_order_policy",
            "replenishment_suggestion_batch",
            "replenishment_suggestion",
            "replenishment_suggestion_step",
            "replenishment_suggestion_decision")) {
      var row =
          sql.fetchOne(
              "select relrowsecurity, relforcerowsecurity from pg_class where relname = ?", table);
      assertThat(row).as("V238 必须真的建出 %s 这张表", table).isNotNull();
      assertThat(row.get(0, Boolean.class)).as("%s 的 RLS 未启用", table).isTrue();
      assertThat(row.get(1, Boolean.class)).as("%s 只有 FORCE 才挡得住「运行角色被误授 owner」", table)
          .isTrue();
      assertThat(
              sql.fetchOne(
                      "select has_table_privilege('trade_app',?,'SELECT')", table)
                  .get(0, Boolean.class))
          .as("trade_app 必须真的能读 %s", table)
          .isTrue();
    }
  }

  // ================================================================ 断言 1

  @Test
  void 断言1_两租户互不可见() throws Exception {
    registerVelocity("Bearer tenant-a", skuFor(a, 1), "SKU-A-01");
    registerVelocity("Bearer tenant-b", skuFor(b, 1), "SKU-B-01");
    compute("Bearer tenant-a");
    compute("Bearer tenant-b");

    var rowsA = rowsOf(suggestions("Bearer tenant-a"));
    var rowsB = rowsOf(suggestions("Bearer tenant-b"));
    assertThat(rowsA).hasSize(1);
    assertThat(rowsB).hasSize(1);
    assertThat(rowsA.get(0).get("skuCode")).isEqualTo("SKU-A-01");
    assertThat(rowsB.get(0).get("skuCode")).isEqualTo("SKU-B-01");

    // 更强的一格：A 明确按 ID 查 B 的行也拿不到（不只是列表过滤）。
    String bSuggestionId = String.valueOf(rowsB.get(0).get("id"));
    http.perform(
            get("/trade/replenishment-suggestions/suggestions/" + bSuggestionId)
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isNotFound());

    // 销售速度登记同样互不可见（6.2.1.1 的登记表是本模块的输入事实）。
    http.perform(get("/trade/stockout-risk/velocities").header("Authorization", "Bearer tenant-a"))
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.rows[0].skuCode").value("SKU-A-01"));
    http.perform(get("/trade/stockout-risk/velocities").header("Authorization", "Bearer tenant-b"))
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.rows[0].skuCode").value("SKU-B-01"));

    // 批次列表也互不可见。
    var batchA =
        http.perform(get("/trade/replenishment-suggestions/batches").header("Authorization", "Bearer tenant-a"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var batchB =
        http.perform(get("/trade/replenishment-suggestions/batches").header("Authorization", "Bearer tenant-b"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(json.readTree(batchA).get("total").asLong()).as("A 的批次数").isEqualTo(1);
    assertThat(json.readTree(batchB).get("total").asLong()).as("B 的批次数").isEqualTo(1);
    assertThat(json.readTree(batchA).get("rows").get(0).get("batchNo").asText())
        .isNotEqualTo(json.readTree(batchB).get("rows").get(0).get("batchNo").asText());
  }

  // ================================================================ 断言 2

  @Test
  void 断言2_伪造租户上下文无效() throws Exception {
    registerVelocity("Bearer tenant-a", skuFor(a, 1), "SKU-A-01");
    registerVelocity("Bearer tenant-b", skuFor(b, 1), "SKU-B-01");
    compute("Bearer tenant-a");
    compute("Bearer tenant-b");

    // (1) HTTP 边界上没有伪造入口：X-Tenant-Id 不改任何东西。
    String honest = suggestions("Bearer tenant-a");
    String forged =
        http.perform(
                get("/trade/replenishment-suggestions/suggestions")
                    .header("Authorization", "Bearer tenant-a")
                    .header("X-Tenant-Id", b.toString()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(forged).as("伪造 X-Tenant-Id 不得改变看到的那份清单").isEqualTo(honest);
    assertThat(rowsOf(forged)).hasSize(1);
    assertThat(rowsOf(forged).get(0).get("skuCode")).isEqualTo("SKU-A-01");

    // 伪造头也不能让写入落到别的租户。
    http.perform(
            post("/trade/replenishment-suggestions/coverage-policies")
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "scope", "DEFAULT",
                            "coverageDays", 45,
                            "effectiveFrom", "2026-01-01"))))
        .andExpect(status().isOk());
    http.perform(
            get("/trade/replenishment-suggestions/coverage-policies")
                .header("Authorization", "Bearer tenant-b"))
        .andExpect(jsonPath("$.total").value(0));

    // (2) 事务外没有「环境租户」：app.current_tenant 要么未设置、要么已被复位成空串，
    //     两种情况归一后都是 null，于是任何租户的行都看不见 —— 不存在一个可以被沿用的默认租户。
    assertThat(rawTenantSetting()).isNullOrEmpty();
    assertThat(effectiveTenant()).isNull();
    assertThat(visibleWithoutTenant()).as("未绑定租户时不应看到任何行").isZero();

    // (3) 事务内绑定的值精确等于可信参数（来自已认证成员关系），提交后立刻失效。
    var bound =
        access.run(
            actors.get("Bearer tenant-b"),
            "trade.replenishment.read",
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
    registerVelocity("Bearer tenant-a", skuFor(a, 1), "SKU-A-01");
    registerVelocity("Bearer tenant-b", skuFor(b, 1), "SKU-B-01");
    compute("Bearer tenant-a");
    compute("Bearer tenant-b");

    // 池大小为 1：下面每次读写都复用同一条连接，只有事务局部绑定 + 提交即清空
    // 才能保证第 N 次读到的仍然是 B 而不是上一轮残留的 A。
    for (int i = 0; i < 5; i++) {
      var rowsA = rowsOf(suggestions("Bearer tenant-a"));
      var rowsB = rowsOf(suggestions("Bearer tenant-b"));
      assertThat(rowsA).as("第 %d 轮租户 A", i).hasSize(1);
      assertThat(rowsB).as("第 %d 轮租户 B", i).hasSize(1);
      assertThat(rowsA.get(0).get("skuCode")).as("第 %d 轮租户 A", i).isEqualTo("SKU-A-01");
      assertThat(rowsB.get(0).get("skuCode")).as("第 %d 轮租户 B", i).isEqualTo("SKU-B-01");
      assertThat(visibleWithoutTenant()).as("第 %d 轮事务外", i).isZero();
    }
    // 归还后再单独借一次，仍然干净。
    assertThat(rowsOf(suggestions("Bearer tenant-a")).get(0).get("skuCode")).isEqualTo("SKU-A-01");
    assertThat(rowsOf(suggestions("Bearer tenant-b")).get(0).get("skuCode")).isEqualTo("SKU-B-01");
  }
}
