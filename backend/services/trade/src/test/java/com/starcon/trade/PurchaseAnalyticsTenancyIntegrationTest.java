package com.starcon.trade;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 6.5.2.1 采购分析的**多租户隔离**复验。
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
 * <p>断言用<b>应用账号 trade_app</b> 连（超级用户会绕过 RLS，断言本身失效）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class PurchaseAnalyticsTenancyIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static final ObjectMapper json =
      new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

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
    r.add("IDENTITY_URL", () -> "http://localhost:8081");
    r.add("INTERNAL_KEY", () -> "test-key");
    // 单连接：只有复用同一条连接才能证明连接池归还后不串租户。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  @AfterAll
  static void stop() {
    pg.stop();
  }

  static final List<String> permissions =
      List.of("trade.purchase-analytics.read", "trade.purchase-analytics.update");

  static AccessService.Access actor(UUID tenantId) {
    return new AccessService.Access(
        user,
        tenantId,
        "Test",
        UUID.randomUUID(),
        null,
        permissions,
        List.of(new AccessService.Grant(UUID.randomUUID(), "TEST", "ALL", permissions)));
  }

  @MockitoSpyBean PurchaseAnalyticsCatalogPort catalog;
  @MockitoSpyBean PurchaseAnalyticsWarehousePort warehouse;
  @MockitoSpyBean AccessService access;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  @BeforeEach
  void stubIdentity() {
    org.mockito.Mockito
        .doAnswer(
            inv -> {
              String permission = inv.getArgument(1, String.class);
              boolean allowed =
                  permissions.contains(permission)
                      && permissions.contains(
                          permission.substring(0, permission.lastIndexOf('.')) + ".read");
              if (!allowed) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前角色没有此操作权限");
              }
              return actor(TENANT.get());
            })
        .when(access)
        .require(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    org.mockito.Mockito.doAnswer(
            inv -> {
              UUID skuId = inv.getArgument(0, UUID.class);
              return new PurchaseAnalyticsCatalogPort.SkuView(
                  skuId, "SKU-X", "商品", "CAT-X", "PCS", List.of());
            })
        .when(catalog)
        .sku(org.mockito.ArgumentMatchers.any(UUID.class), org.mockito.ArgumentMatchers.anyString());
    org.mockito.Mockito.doAnswer(
            inv -> List.of())
        .when(catalog)
        .rates(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyString());
    org.mockito.Mockito.doAnswer(
            inv ->
                List.of(
                    new PurchaseAnalyticsCatalogPort.FunctionalCurrencyRow(
                        UUID.randomUUID(), "LE-01", "主体", "CNY")))
        .when(catalog)
        .functionalCurrencies(org.mockito.ArgumentMatchers.anyString());
    org.mockito.Mockito.doAnswer(
            inv ->
                new PurchaseAnalyticsWarehousePort.ReceiptScan(
                    List.of(), 0, false, "夹具"))
        .when(warehouse)
        .receiptLines(
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt());
  }

  /** 当前请求要落在哪个租户上（测试用它来模拟两个不同的登录身份）。 */
  static final ThreadLocal<UUID> TENANT = new ThreadLocal<>();

  @BeforeEach
  void clean() throws Exception {
    TENANT.set(a);
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate purchase_analytics_fact, purchase_analytics_sync_run,"
              + " purchase_analytics_sku_dimension cascade");
    }
  }

  /** 直接在**租户事务**里落一条本模块自己的事实（不经 HTTP：HTTP 那条路另有测试）。 */
  void seedFact(UUID tenantId, String tag) {
    access.run(
        actor(tenantId),
        "trade.purchase-analytics.read",
        () -> {
          sql.execute(
              "insert into purchase_analytics_fact (id, tenant_id, fact_key, fact_kind, source_type,"
                  + " source_doc_no, occurred_at, occurred_date, supplier_code, supplier_name,"
                  + " quantity, amount, currency, fx_status, created_at, updated_at)"
                  + " values (?::uuid, ?::uuid, ?::varchar, 'COST', 'ARRIVAL_RECEIPT', ?::varchar,"
                  + " ?::timestamptz, ?::date, ?::varchar, ?::varchar, ?::numeric, ?::numeric,"
                  + " ?::varchar, 'NOT_APPLICABLE', now(), now())",
              UUID.randomUUID(),
              tenantId,
              "ARRIVAL_RECEIPT:" + tag,
              "TRACK-" + tag,
              OffsetDateTime.parse("2026-03-05T02:00:00Z"),
              LocalDate.of(2026, 3, 5),
              "SUP-" + tag,
              "供应商-" + tag,
              new java.math.BigDecimal("10"),
              new java.math.BigDecimal("250"),
              "USD");
          return null;
        });
  }

  String readText(String path) throws Exception {
    return http.perform(get(path).header("Authorization", "Bearer tenant"))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  @SuppressWarnings("unchecked")
  List<Map<String, Object>> rowsOf(String payload) throws Exception {
    Map<String, Object> parsed = json.readValue(payload, Map.class);
    return (List<Map<String, Object>>) parsed.getOrDefault("items", List.of());
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
    return sql.fetch("select id from purchase_analytics_fact").size();
  }

  // ================================================================ 迁移确实落地了，且 RLS 是**强制**的

  @Test
  void 本模块的表强制rls而不是只启用() {
    for (String table :
        List.of(
            "purchase_analytics_fact",
            "purchase_analytics_sync_run",
            "purchase_analytics_sku_dimension")) {
      var row =
          sql.fetchOne(
              "select relrowsecurity, relforcerowsecurity from pg_class where relname = ?", table);
      assertThat(row).as("V251 必须真的建出 %s 这张表", table).isNotNull();
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
    seedFact(a, "A");
    seedFact(b, "B");

    TENANT.set(a);
    var rowsA = rowsOf(readText("/trade/purchase-analytics/facts?from=2026-03-01&to=2026-03-31"));
    TENANT.set(b);
    var rowsB = rowsOf(readText("/trade/purchase-analytics/facts?from=2026-03-01&to=2026-03-31"));

    assertThat(rowsA).hasSize(1);
    assertThat(rowsB).hasSize(1);
    assertThat(rowsA.get(0).get("sourceDocNo")).isEqualTo("TRACK-A");
    assertThat(rowsB.get(0).get("sourceDocNo")).isEqualTo("TRACK-B");

    // 更强的一格：A 明确按 ID 查 B 的行也拿不到（不只是列表过滤）。
    TENANT.set(a);
    String factB = String.valueOf(rowsB.get(0).get("id"));
    http.perform(
            get("/trade/purchase-analytics/facts/" + factB).header("Authorization", "Bearer tenant"))
        .andExpect(status().isNotFound());

    // 汇总与报表同样按租户分开。
    TENANT.set(a);
    var summaryA =
        json.readTree(readText("/trade/purchase-analytics/summary?from=2026-03-01&to=2026-03-31"));
    assertThat(summaryA.get("costFactCount").asInt()).isEqualTo(1);
    TENANT.set(b);
    var summaryB =
        json.readTree(readText("/trade/purchase-analytics/summary?from=2026-03-01&to=2026-03-31"));
    assertThat(summaryB.get("costFactCount").asInt()).isEqualTo(1);
    assertThat(summaryA.get("purchaseAmountTotal").decimalValue())
        .isEqualByComparingTo(summaryB.get("purchaseAmountTotal").decimalValue());
  }

  // ================================================================ 断言 2

  @Test
  void 断言2_伪造租户上下文无效() throws Exception {
    seedFact(a, "A");
    seedFact(b, "B");

    // (1) HTTP 边界上没有伪造入口：X-Tenant-Id 不改任何东西。
    TENANT.set(a);
    String honest = readText("/trade/purchase-analytics/facts?from=2026-03-01&to=2026-03-31");
    String forged =
        http.perform(
                get("/trade/purchase-analytics/facts?from=2026-03-01&to=2026-03-31")
                    .header("Authorization", "Bearer tenant")
                    .header("X-Tenant-Id", b.toString()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(forged).as("伪造 X-Tenant-Id 不得改变看到的那份清单").isEqualTo(honest);
    assertThat(rowsOf(forged)).hasSize(1);

    // (2) 事务外没有「环境租户」：app.current_tenant 要么未设置、要么已被复位成空串，
    //     两种情况归一后都是 null，于是任何租户的行都看不见 —— 不存在一个可以被沿用的默认租户。
    assertThat(rawTenantSetting()).isNullOrEmpty();
    assertThat(effectiveTenant()).isNull();
    assertThat(visibleWithoutTenant()).as("未绑定租户时不应看到任何行").isZero();

    // (3) 事务内绑定的值精确等于可信参数（来自已认证成员关系），提交后立刻失效。
    var bound =
        access.run(
            actor(b),
            "trade.purchase-analytics.read",
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
    seedFact(a, "A");
    seedFact(b, "B");

    // 池大小为 1：下面每次读写都复用同一条连接，只有事务局部绑定 + 提交即清空
    // 才能保证第 N 次读到的仍然是 B 而不是上一轮残留的 A。
    for (int i = 0; i < 5; i++) {
      TENANT.set(a);
      var rowsA = rowsOf(readText("/trade/purchase-analytics/facts?from=2026-03-01&to=2026-03-31"));
      TENANT.set(b);
      var rowsB = rowsOf(readText("/trade/purchase-analytics/facts?from=2026-03-01&to=2026-03-31"));
      assertThat(rowsA).as("第 %d 轮租户 A", i).hasSize(1);
      assertThat(rowsB).as("第 %d 轮租户 B", i).hasSize(1);
      assertThat(rowsA.get(0).get("id")).as("第 %d 轮租户 A", i).isNotEqualTo(rowsB.get(0).get("id"));
      assertThat(rowsA.get(0).get("sourceDocNo")).isEqualTo("TRACK-A");
      assertThat(rowsB.get(0).get("sourceDocNo")).isEqualTo("TRACK-B");
      assertThat(visibleWithoutTenant()).as("第 %d 轮事务外", i).isZero();
    }
  }

  // ================================================================ 跨租户按 id 改写会被拒

  @Test
  void 跨租户按id读会被拒() throws Exception {
    seedFact(a, "A");
    seedFact(b, "B");

    TENANT.set(a);
    var rowsA = rowsOf(readText("/trade/purchase-analytics/facts?from=2026-03-01&to=2026-03-31"));
    String idA = String.valueOf(rowsA.get(0).get("id"));
    TENANT.set(b);
    // B 拿着 A 的事实 id 去查 ⇒ 404（RLS 下查不到），不是 403（403 会泄露存在性）。
    http.perform(
            get("/trade/purchase-analytics/facts/" + idA).header("Authorization", "Bearer tenant"))
        .andExpect(status().isNotFound());
    assertThat(rowsOf(readText("/trade/purchase-analytics/facts?from=2026-03-01&to=2026-03-31"))).hasSize(1);
  }
}
