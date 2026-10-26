package com.starcon.reporting;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.starcon.tenancy.TenantTransactions;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jooq.DSLContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 13.4.2.2 费用差异的**集成测试**（真实 PostgreSQL 16 + FORCE RLS，无内存替身）。
 *
 * <p>跑的是本模块的**业务口径**：
 *
 * <ol>
 *   <li>差异 = 实际 − 预计（**符号口径固定**，页面与建议都按它说话）；
 *   <li>「算不出来」与「等于 0」分开（预计缺失 / 实际缺失都不是 0）；
 *   <li>差异率用 BigDecimal 显式定标（**没有浮点**）；
 *   <li>预计与实际**分属两张表**（不合并成一个字段）；
 *   <li>规则调优建议只给**系统性**偏差（阈值 + 最少样本）；
 *   <li>同一事件重投被幂等跳过（费用不翻倍）；
 *   <li>重建会清掉费用事实（重建 = 回到零）；
 *   <li>跨币种绝不相加。
 * </ol>
 *
 * <p>租户隔离的三条断言在 {@code ExpenseVarianceTenancyIntegrationTest}（同一批跑）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ExpenseVarianceIntegrationTest {

  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  static final List<String> permissions =
      List.of(
          "reporting.expense-variance.read",
          "reporting.projection.read",
          "reporting.projection.write");

  static final ObjectMapper JSON = JsonMapper.builder().addModule(new JavaTimeModule()).build();

  static final LocalDate DAY1 = LocalDate.of(2026, 10, 1);
  static final LocalDate DAY2 = LocalDate.of(2026, 10, 2);
  static final LocalDate DAY3 = LocalDate.of(2026, 10, 3);

  static {
    pg.start();
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      // 运行角色必须 NOBYPASSRLS，否则它会绕过策略，三条断言全部变成空断言。
      s.execute("create role starcon_app nologin");
      s.execute(
          "create role reporting_app login password 'test-only' nosuperuser nobypassrls");
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
      actors.put("Bearer tenant-a", actor(a));
      actors.put("Bearer tenant-b", actor(b));
      identity.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  static AccessService.Access actor(UUID tenant) {
    var grant = new AccessService.Grant(UUID.randomUUID(), "TEST", "ALL", permissions);
    return new AccessService.Access(
        user, tenant, "Test", UUID.randomUUID(), null, permissions, List.of(grant));
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "reporting_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://localhost:" + identity.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
    // ⚠️ 连接池固定为 1 条连接：只有复用同一条连接，才能真正证明归还后不会串租户。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  @Autowired TenantTransactions tenants;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;
  @Autowired ReportingProjectionService projection;
  @Autowired ReportingExpenseService expenses;

  @AfterAll
  static void stop() {
    identity.stop(0);
    pg.stop();
  }

  @BeforeEach
  void reset() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate reporting_metric_fact, reporting_drilldown_fact, reporting_projection_event,"
              + " reporting_projection_cursor, reporting_currency_rate_snapshot,"
              + " reporting_metric_definition,"
              + " reporting_expense_estimate_fact, reporting_expense_actual_fact cascade");
    }
  }

  // ==========================================================================
  // 夹具
  // ==========================================================================

  /** 一条费用事实（预计或实际）。 */
  private static ReportingProjectionModels.ProjectionExpenseFact expenseFact(
      String kind,
      String feeType,
      String feeSubType,
      String platformCode,
      String shopCode,
      String skuCode,
      LocalDate periodStart,
      LocalDate periodEnd,
      String currency,
      String amount,
      String base,
      String reference) {
    return new ReportingProjectionModels.ProjectionExpenseFact(
        kind,
        feeType,
        feeSubType,
        platformCode,
        "",
        "",
        "",
        "",
        shopCode,
        skuCode,
        periodStart,
        periodEnd,
        currency,
        new BigDecimal(amount),
        base == null ? null : new BigDecimal(base),
        null,
        reference);
  }

  /** 一个费用事件（预计与实际**在同一个事件里**落地 ⇒ 幂等键共用「上游那一行」）。 */
  private static ReportingProjectionModels.ProjectionEvent event(
      String key,
      LocalDate date,
      List<ReportingProjectionModels.ProjectionExpenseFact> expenseFacts) {
    return new ReportingProjectionModels.ProjectionEvent(
        "catalog",
        "catalog:expense:" + key,
        ReportingProjectionModels.ReportingEventTypes.EXPENSE_FACT,
        null,
        0,
        date.atStartOfDay(ZoneOffset.UTC).toOffsetDateTime(),
        "UTC",
        List.of(),
        List.of(),
        expenseFacts);
  }

  /** 平台费的一天：预计 100（基数 1000），实际 85。 */
  private void seedPlatformFee(LocalDate date, String reference) {
    projection.applyBatch(
        a,
        List.of(
            event(
                reference,
                date,
                List.of(
                    expenseFact(
                        "ESTIMATE",
                        "PLATFORM_FEE",
                        "COMMISSION",
                        "AMAZON",
                        "",
                        "",
                        date,
                        date,
                        "USD",
                        "100.0000",
                        "1000.0000",
                        reference),
                    expenseFact(
                        "ACTUAL",
                        "PLATFORM_FEE",
                        "COMMISSION",
                        "AMAZON",
                        "",
                        "",
                        date,
                        date,
                        "USD",
                        "85.0000",
                        "1000.0000",
                        reference)))));
  }

  // ==========================================================================
  // 业务口径
  // ==========================================================================

  @Test
  void 差异等于实际减预计_符号口径固定() {
    seedPlatformFee(DAY1, "PF-1");
    var response = expenses.variance(a, DAY1, DAY2, "DAY", null, null, null, null);

    assertThat(response.rows()).as("一天一组差异").hasSize(1);
    var row = response.rows().get(0);
    assertThat(row.estimatedAmount()).isEqualByComparingTo("100.0000");
    assertThat(row.actualAmount()).isEqualByComparingTo("85.0000");
    assertThat(row.varianceAmount()).as("差异 = 实际 − 预计 = 85 − 100").isEqualByComparingTo("-15.0000");
    assertThat(row.varianceRate()).as("差异率 = −15 / 100 = −15%").isEqualByComparingTo("-15.0000");
    assertThat(row.comparable()).isTrue();
    assertThat(response.varianceSignNote()).contains("实际 − 预计");
  }

  @Test
  void 差异率用BigDecimal定标_没有浮点() {
    seedPlatformFee(DAY1, "PF-1");
    var row = expenses.variance(a, DAY1, DAY2, "DAY", null, null, null, null).rows().get(0);
    assertThat(row.varianceRate().scale()).as("差异率固定 4 位小数").isEqualTo(4);
    // 1/3 这种除不尽的数也必须定标，不允许出现 0.3333333333333333 这种浮点尾巴。
    projection.applyBatch(
        a,
        List.of(
            event(
                "PF-3",
                DAY2,
                List.of(
                    expenseFact(
                        "ESTIMATE",
                        "PLATFORM_FEE",
                        "COMMISSION",
                        "AMAZON",
                        "",
                        "",
                        DAY2,
                        DAY2,
                        "USD",
                        "3.0000",
                        null,
                        "PF-3"),
                    expenseFact(
                        "ACTUAL",
                        "PLATFORM_FEE",
                        "COMMISSION",
                        "AMAZON",
                        "",
                        "",
                        DAY2,
                        DAY2,
                        "USD",
                        "4.0000",
                        null,
                        "PF-3")))));
    var second =
        expenses.variance(a, DAY1, DAY2, "DAY", null, null, null, null).rows().stream()
            .filter(r -> r.periodStart().equals(DAY2))
            .findFirst()
            .orElseThrow();
    assertThat(second.varianceRate()).isEqualByComparingTo("33.3333");
    assertThat(second.varianceRate().scale()).isEqualTo(4);
  }

  @Test
  void 只有一侧有数据时差异算不出来_不是0() {
    // 只有预计，没有实际。
    projection.applyBatch(
        a,
        List.of(
            event(
                "PF-ONLY-EST",
                DAY1,
                List.of(
                    expenseFact(
                        "ESTIMATE",
                        "PLATFORM_FEE",
                        "COMMISSION",
                        "AMAZON",
                        "",
                        "",
                        DAY1,
                        DAY1,
                        "USD",
                        "100.0000",
                        null,
                        "PF-ONLY-EST")))));
    var response = expenses.variance(a, DAY1, DAY2, "DAY", null, null, null, null);
    var row = response.rows().get(0);
    assertThat(row.comparable()).as("只有一侧时不可比").isFalse();
    assertThat(row.varianceAmount()).as("算不出来不是 0").isNull();
    assertThat(row.varianceRate()).isNull();
  }

  @Test
  void 有实际无预计的单列出来_差异率算不出来() {
    // 只有实际（12.3.1.2 那一侧：平台 + 店铺维度，没有可对照的预计）。
    projection.applyBatch(
        a,
        List.of(
            event(
                "FIN-1",
                DAY1,
                List.of(
                    expenseFact(
                        "ACTUAL",
                        "PLATFORM_FEE",
                        "COMMISSION",
                        "AMAZON",
                        "SHOP-1",
                        "",
                        DAY1,
                        DAY1,
                        "USD",
                        "42.0000",
                        null,
                        "FIN-1")))));
    var response = expenses.variance(a, DAY1, DAY2, "DAY", null, null, null, null);
    assertThat(response.rows()).as("没有预计就没有差异行").isEmpty();
    assertThat(response.unmatchedActuals()).as("有实际无预计必须单列").hasSize(1);
    var unmatched = response.unmatchedActuals().get(0);
    assertThat(unmatched.actualAmount()).isEqualByComparingTo("42.0000");
    assertThat(unmatched.reason()).contains("销售额");
  }

  @Test
  void 预计与实际分属两张表_不合并成一个字段() {
    seedPlatformFee(DAY1, "PF-1");
    var estimates =
        tenants.run(
            a,
            () ->
                sql.fetch(
                    "select estimated_amount from reporting_expense_estimate_fact where tenant_id = ?",
                    a).size());
    var actuals =
        tenants.run(
            a,
            () ->
                sql.fetch(
                    "select actual_amount from reporting_expense_actual_fact where tenant_id = ?",
                    a).size());
    assertThat(estimates).as("预计费用有自己的表").isEqualTo(1);
    assertThat(actuals).as("实际费用有自己的表").isEqualTo(1);
  }

  @Test
  void 规则调优建议只给系统性偏差() {
    // 三天连续高估 15%（预计 100 / 实际 85），样本够、偏差够大 ⇒ 出一条建议。
    // ⚠️ 口径与 seedPlatformFee 一致（实际 85 ⇒ 差异 −15 ⇒ 差异率 −15%），
    //   与「差异等于实际减预计」那条断言用的是同一个夹具。
    seedPlatformFee(DAY1, "PF-1");
    seedPlatformFee(DAY2, "PF-2");
    seedPlatformFee(DAY3, "PF-3");
    var response = expenses.variance(a, DAY1, DAY3, "DAY", null, null, null, null);
    assertThat(response.recommendations()).as("系统性偏差要给建议").hasSize(1);
    var recommendation = response.recommendations().get(0);
    assertThat(recommendation.direction()).as("实际比预计少 ⇒ 预计高估").isEqualTo("OVER_ESTIMATED");
    assertThat(recommendation.varianceRate()).as("三天合计：−45 ÷ 300 = −15%").isEqualByComparingTo("-15.0000");
    assertThat(recommendation.currentRate()).as("当前有效费率 = 预计 ÷ 基数 = 300 ÷ 3000").isEqualByComparingTo("0.100000");
    assertThat(recommendation.impliedRate()).as("实际隐含费率 = 实际 ÷ 基数 = 255 ÷ 3000").isEqualByComparingTo("0.085000");
    assertThat(recommendation.suggestedRate()).isEqualByComparingTo("0.085000");
    assertThat(recommendation.ownerModule()).as("建议要写明去哪个模块改规则").isEqualTo("5.2.3.1");
    assertThat(recommendation.suggestion()).contains("5.2.3.1");
  }

  @Test
  void 样本太少不给建议() {
    seedPlatformFee(DAY1, "PF-1");
    var response = expenses.variance(a, DAY1, DAY2, "DAY", null, null, null, null);
    assertThat(response.recommendations()).as("一两笔的偏差不是系统性偏差").isEmpty();
  }

  @Test
  void 同一事件重投被幂等跳过_费用不翻倍() {
    seedPlatformFee(DAY1, "PF-1");
    var again =
        projection.applyBatch(
            a,
            List.of(
                event(
                    "PF-1",
                    DAY1,
                    List.of(
                        expenseFact(
                            "ESTIMATE",
                            "PLATFORM_FEE",
                            "COMMISSION",
                            "AMAZON",
                            "",
                            "",
                            DAY1,
                            DAY1,
                            "USD",
                            "100.0000",
                            "1000.0000",
                            "PF-1"),
                        expenseFact(
                            "ACTUAL",
                            "PLATFORM_FEE",
                            "COMMISSION",
                            "AMAZON",
                            "",
                            "",
                            DAY1,
                            DAY1,
                            "USD",
                            "85.0000",
                            "1000.0000",
                            "PF-1")))));
    assertThat(again.appliedCount()).as("同一事件重投必须被跳过").isZero();
    assertThat(again.skippedCount()).isEqualTo(1);
    var row = expenses.variance(a, DAY1, DAY2, "DAY", null, null, null, null).rows().get(0);
    assertThat(row.actualAmount()).as("费用不翻倍").isEqualByComparingTo("85.0000");
  }

  @Test
  void 重建会清掉费用事实() {
    seedPlatformFee(DAY1, "PF-1");
    assertThat(expenses.variance(a, DAY1, DAY2, "DAY", null, null, null, null).rows()).hasSize(1);
    projection.clearProjection(a);
    assertThat(expenses.variance(a, DAY1, DAY2, "DAY", null, null, null, null).rows()).isEmpty();
  }

  @Test
  void 跨币种绝不相加() {
    projection.applyBatch(
        a,
        List.of(
            event(
                "PF-USD",
                DAY1,
                List.of(
                    expenseFact(
                        "ESTIMATE",
                        "PLATFORM_FEE",
                        "COMMISSION",
                        "AMAZON",
                        "",
                        "",
                        DAY1,
                        DAY1,
                        "USD",
                        "100.0000",
                        null,
                        "PF-USD"),
                    expenseFact(
                        "ACTUAL",
                        "PLATFORM_FEE",
                        "COMMISSION",
                        "AMAZON",
                        "",
                        "",
                        DAY1,
                        DAY1,
                        "USD",
                        "90.0000",
                        null,
                        "PF-USD"))),
            event(
                "PF-EUR",
                DAY1,
                List.of(
                    expenseFact(
                        "ESTIMATE",
                        "PLATFORM_FEE",
                        "COMMISSION",
                        "AMAZON",
                        "",
                        "",
                        DAY1,
                        DAY1,
                        "EUR",
                        "80.0000",
                        null,
                        "PF-EUR"),
                    expenseFact(
                        "ACTUAL",
                        "PLATFORM_FEE",
                        "COMMISSION",
                        "AMAZON",
                        "",
                        "",
                        DAY1,
                        DAY1,
                        "EUR",
                        "80.0000",
                        null,
                        "PF-EUR")))));
    var response = expenses.variance(a, DAY1, DAY2, "DAY", null, null, null, null);
    assertThat(response.rows()).as("两个币种两行，不合并").hasSize(2);
    assertThat(response.rows().stream().map(r -> r.currency()).distinct().count()).isEqualTo(2);
  }

  @Test
  void SKU级差异按SKU与期间展示差异率() {
    projection.applyBatch(
        a,
        List.of(
            event(
                "HF-1",
                DAY1,
                List.of(
                    expenseFact(
                        "ESTIMATE",
                        "HEAD_FREIGHT",
                        "FIRST_MILE",
                        "",
                        "",
                        "SKU-1",
                        DAY1,
                        DAY1,
                        "USD",
                        "12.0000",
                        null,
                        "HF-1"),
                    expenseFact(
                        "ACTUAL",
                        "HEAD_FREIGHT",
                        "FIRST_MILE",
                        "",
                        "",
                        "SKU-1",
                        DAY1,
                        DAY1,
                        "USD",
                        "15.0000",
                        null,
                        "HF-1")))));
    var page = expenses.skuVariance(a, DAY1, DAY2, null, 0, 50);
    assertThat(page.total()).isEqualTo(1);
    var row = page.items().get(0);
    assertThat(row.skuCode()).isEqualTo("SKU-1");
    assertThat(row.varianceAmount()).isEqualByComparingTo("3.0000");
    assertThat(row.varianceRate()).isEqualByComparingTo("25.0000");
  }

  @Test
  void 数据更新时间常驻() {
    seedPlatformFee(DAY1, "PF-1");
    var response = expenses.variance(a, DAY1, DAY2, "DAY", null, null, null, null);
    assertThat(response.dataUpdatedAt()).as("数据更新时间必须常驻").isNotNull();
    assertThat(response.dataUpdatedNote()).contains("投影落库时刻");
  }

  @Test
  void 端点返回差异表_且拒绝未知费用类型() throws Exception {
    seedPlatformFee(DAY1, "PF-1");
    http.perform(
            get("/reporting/expense-variance/variance")
                .header("Authorization", "Bearer tenant-a")
                .param("from", DAY1.toString())
                .param("to", DAY2.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rows.length()").value(1))
        .andExpect(jsonPath("$.rows[0].varianceAmount").value(-15.0));

    http.perform(
            get("/reporting/expense-variance/variance")
                .header("Authorization", "Bearer tenant-a")
                .param("feeType", "NOT_A_FEE_TYPE"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void 能力声明写清哪些算不出来() {
    var capabilities = expenses.capabilities();
    assertThat(capabilities.upstreamConnected()).isTrue();
    assertThat(capabilities.feeTypes()).contains("平台费", "物流费", "广告费", "头程分摊");
    assertThat(capabilities.notDelivered()).isNotEmpty();
    assertThat(capabilities.notDelivered().toString()).contains("SKU");
  }
}
