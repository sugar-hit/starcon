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
import java.time.OffsetDateTime;
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
 * 13.1.1.1 经营总览的**集成测试**（真实 PostgreSQL 16 + FORCE RLS，无内存替身）。
 *
 * <p>跑的是 AGENTS.md 明文要求的三条断言：
 *
 * <ol>
 *   <li>两租户互不可见；
 *   <li>伪造租户上下文无效；
 *   <li>连接池归还后不串租户。
 * </ol>
 *
 * <p><b>断言 2 在 HTTP 边界上是完整的</b>（不是「只能证一半」）：reporting 虽然是**投影
 * 服务**（不产生业务事实），但它有**写入口** —— {@code POST /reporting/projection/events}
 * 把上游事件投影成读模型。所以「伪造 {@code X-Tenant-Id} 头无效」可以真的证：
 * 带合法会话 + 伪造头 ⇒ 事件仍然落在**会话租户**名下，另一个租户看不见。
 *
 * <p><b>连接池固定为 1 条连接</b>：只有复用同一条连接，才能真正证明归还后不会串租户 ——
 * 池大于 1 时每次可能拿到不同连接，「没串」可能只是碰巧换了条干净连接。
 *
 * <p>另一半断言（业务口径）也在本文件里：指标卡按**口径表**算（不重算公式）、
 * 存量型取最近一天的快照而不是累加、派生型指标按分子分母求值、同一事件重投被幂等跳过、
 * 下钻能钻到构成明细、数据更新时间常驻。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReportingOverviewIntegrationTest {

  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  static final List<String> permissions =
      List.of("reporting.overview.read", "reporting.projection.read", "reporting.projection.write");

  static final ObjectMapper JSON =
      JsonMapper.builder().addModule(new JavaTimeModule()).build();

  static final LocalDate DAY1 = LocalDate.of(2026, 10, 1);
  static final LocalDate DAY2 = LocalDate.of(2026, 10, 2);

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
    var grant =
        new AccessService.Grant(UUID.randomUUID(), "TEST", "ALL", permissions);
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
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  @Autowired TenantTransactions tenants;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;
  @Autowired ReportingProjectionService projection;
  @Autowired ReportingOverviewService overview;

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
              + " reporting_metric_definition cascade");
    }
  }

  // ==========================================================================
  // 夹具
  // ==========================================================================

  private static ReportingProjectionModels.ProjectionFact companyFact(
      String metricCode, LocalDate date, String currency, String value, String reference) {
    return new ReportingProjectionModels.ProjectionFact(
        "COMPANY",
        null,
        null,
        metricCode,
        date,
        currency,
        new BigDecimal(value),
        BigDecimal.ZERO,
        reference);
  }

  private static ReportingProjectionModels.ProjectionDrilldown companyDrilldown(
      String metricCode,
      LocalDate date,
      String currency,
      String kind,
      String referenceId,
      String label,
      String quantity,
      String value,
      String reference) {
    return new ReportingProjectionModels.ProjectionDrilldown(
        "COMPANY",
        null,
        null,
        metricCode,
        date,
        currency,
        kind,
        referenceId,
        label,
        new BigDecimal(quantity),
        new BigDecimal(value),
        reference);
  }

  private static ReportingProjectionModels.ProjectionEvent event(
      String sourceService,
      String sourceEventId,
      String eventType,
      LocalDate date,
      List<ReportingProjectionModels.ProjectionFact> facts,
      List<ReportingProjectionModels.ProjectionDrilldown> drilldowns) {
    return new ReportingProjectionModels.ProjectionEvent(
        sourceService,
        sourceEventId,
        eventType,
        null,
        0,
        date.atStartOfDay(ZoneOffset.UTC).toOffsetDateTime(),
        "UTC",
        facts,
        drilldowns);
  }

  /** 租户 A 的一整套事实：两天销售额 + 一天成本 + 两天应收快照。 */
  private void seedTenantA() {
    projection.applyBatch(
        a,
        List.of(
            event(
                "trade",
                "trade:orders:SO-1",
                ReportingProjectionModels.ReportingEventTypes.SALES_FACT,
                DAY1,
                List.of(
                    companyFact(ReportingMetricCatalog.SALES_AMOUNT, DAY1, "USD", "1000.00", "SO-1"),
                    companyFact(ReportingMetricCatalog.ORDER_COUNT, DAY1, "USD", "1", "SO-1"),
                    companyFact(ReportingMetricCatalog.UNIT_SOLD, DAY1, "USD", "3", "SO-1#SKU-1")),
                List.of(
                    companyDrilldown(
                        ReportingMetricCatalog.SALES_AMOUNT,
                        DAY1,
                        "USD",
                        "ORDER",
                        "SO-1",
                        "SO-1",
                        "0",
                        "1000.00",
                        "SO-1"))),
            event(
                "finance",
                "finance:cost:COST-1",
                ReportingProjectionModels.ReportingEventTypes.COST_FACT,
                DAY1,
                List.of(
                    companyFact(ReportingMetricCatalog.COGS_AMOUNT, DAY1, "USD", "600.00", "COST-1")),
                List.of()),
            event(
                "trade",
                "trade:orders:SO-2",
                ReportingProjectionModels.ReportingEventTypes.SALES_FACT,
                DAY2,
                List.of(
                    companyFact(ReportingMetricCatalog.SALES_AMOUNT, DAY2, "USD", "500.00", "SO-2"),
                    companyFact(ReportingMetricCatalog.ORDER_COUNT, DAY2, "USD", "1", "SO-2")),
                List.of(
                    companyDrilldown(
                        ReportingMetricCatalog.SALES_AMOUNT,
                        DAY2,
                        "USD",
                        "ORDER",
                        "SO-2",
                        "SO-2",
                        "0",
                        "500.00",
                        "SO-2"))),
            event(
                "finance",
                "finance:receivables:2026-10-01",
                ReportingProjectionModels.ReportingEventTypes.RECEIVABLE_SNAPSHOT,
                DAY1,
                List.of(
                    companyFact(
                        ReportingMetricCatalog.RECEIVABLE_BALANCE,
                        DAY1,
                        "USD",
                        "2000.00",
                        "ROLLUP:" + ReportingMetricCatalog.RECEIVABLE_BALANCE)),
                List.of()),
            event(
                "finance",
                "finance:receivables:2026-10-02",
                ReportingProjectionModels.ReportingEventTypes.RECEIVABLE_SNAPSHOT,
                DAY2,
                List.of(
                    companyFact(
                        ReportingMetricCatalog.RECEIVABLE_BALANCE,
                        DAY2,
                        "USD",
                        "2500.00",
                        "ROLLUP:" + ReportingMetricCatalog.RECEIVABLE_BALANCE)),
                List.of())));
  }

  // ==========================================================================
  // 断言 1：两租户互不可见
  // ==========================================================================

  @Test
  void 断言1_两租户互不可见() {
    seedTenantA();
    projection.applyBatch(
        b,
        List.of(
            event(
                "trade",
                "trade:orders:SO-B",
                ReportingProjectionModels.ReportingEventTypes.SALES_FACT,
                DAY1,
                List.of(
                    companyFact(ReportingMetricCatalog.SALES_AMOUNT, DAY1, "USD", "77.00", "SO-B")),
                List.of())));

    var cardA = card(a, ReportingMetricCatalog.SALES_AMOUNT);
    var cardB = card(b, ReportingMetricCatalog.SALES_AMOUNT);
    assertThat(cardA).as("租户 A 只能看到自己的销售额").isEqualTo("1500");
    assertThat(cardB).as("租户 B 只能看到自己的销售额").isEqualTo("77");

    // 更强的一格：A 明确按指标 + 维度直查 B 的行也拿不到。
    var cross =
        tenants.run(
            a,
            () ->
                sql.fetch(
                        "select source_reference from reporting_metric_fact "
                            + "where tenant_id = ? and metric_code = ?",
                        b,
                        ReportingMetricCatalog.SALES_AMOUNT)
                    .size());
    assertThat(cross).as("按主键直查也必须被 RLS 挡住").isZero();
  }

  // ==========================================================================
  // 断言 2：伪造租户上下文无效
  // ==========================================================================

  @Test
  void 断言2_伪造租户头无效() throws Exception {
    seedTenantA();

    // (1) 事务外没有「环境租户」：不存在一个可以被沿用的默认租户。
    assertThat(rawTenantSetting()).isNullOrEmpty();
    assertThat(visibleFactsWithoutTenant()).as("未绑定租户时不应看到任何行").isZero();

    // (2) 事务内绑定的值精确等于可信参数，提交后立刻失效。
    var bound =
        tenants.run(
            b,
            () -> {
              assertThat(effectiveTenant()).isEqualTo(b);
              return rawTenantSetting();
            });
    assertThat(bound).isEqualTo(b.toString());
    assertThat(effectiveTenant()).as("提交后租户上下文必须已清空").isNull();
    assertThat(visibleFactsWithoutTenant()).isZero();

    // (3) HTTP 边界：带**租户 A 的合法会话** + **伪造的 X-Tenant-Id（租户 B）** ⇒
    //     事件仍然落在会话租户 A 名下，租户 B 看不见。
    var body =
        JSON.writeValueAsBytes(
            List.of(
                event(
                    "trade",
                    "trade:orders:SO-FORGED",
                    ReportingProjectionModels.ReportingEventTypes.SALES_FACT,
                    DAY1,
                    List.of(
                        companyFact(
                            ReportingMetricCatalog.SALES_AMOUNT, DAY1, "USD", "999.00", "SO-FORGED")),
                    List.of())));
    http.perform(
            post("/reporting/projection/events")
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b.toString())
                .contentType("application/json")
                .content(body))
        .andExpect(status().isOk());

    assertThat(card(a, ReportingMetricCatalog.SALES_AMOUNT))
        .as("伪造头不得让事件落到别的租户名下")
        .isEqualTo("2499");
    assertThat(card(b, ReportingMetricCatalog.SALES_AMOUNT))
        .as("被伪造的租户不得看见别人的事实")
        .isNull();
  }

  // ==========================================================================
  // 断言 3：连接池归还后不串租户
  // ==========================================================================

  @Test
  void 断言3_连接池归还后不串租户() {
    seedTenantA();
    for (int i = 0; i < 5; i++) {
      assertThat(card(a, ReportingMetricCatalog.SALES_AMOUNT)).as("第 %d 轮租户 A", i).isEqualTo("1500");
      assertThat(card(b, ReportingMetricCatalog.SALES_AMOUNT)).as("第 %d 轮租户 B", i).isNull();
      assertThat(visibleFactsWithoutTenant()).as("第 %d 轮事务外", i).isZero();
    }
    // 归还后再单独借一次，仍然干净。
    assertThat(card(a, ReportingMetricCatalog.SALES_AMOUNT)).isEqualTo("1500");
    assertThat(card(b, ReportingMetricCatalog.SALES_AMOUNT)).isNull();
  }

  // ==========================================================================
  // 业务口径
  // ==========================================================================

  @Test
  void 指标卡按口径表求值_不重算公式() {
    seedTenantA();
    var response = overview.overview(a, DAY1, DAY2, "COMPANY", null, null, null);

    // 流量型：窗口内相加。
    assertThat(value(response, ReportingMetricCatalog.SALES_AMOUNT)).isEqualByComparingTo("1500.00");
    assertThat(value(response, ReportingMetricCatalog.ORDER_COUNT)).isEqualByComparingTo("2");
    assertThat(value(response, ReportingMetricCatalog.UNIT_SOLD)).isEqualByComparingTo("3");
    assertThat(value(response, ReportingMetricCatalog.COGS_AMOUNT)).isEqualByComparingTo("600.00");
    // 派生差：销售额 − 成本（**按口径表**求值，不是在代码里写死公式）。
    assertThat(value(response, ReportingMetricCatalog.GROSS_PROFIT)).isEqualByComparingTo("900.00");
    // 派生比：毛利额 ÷ 销售额 = 60%（PERCENT 单位，值是百分数）。
    assertThat(value(response, ReportingMetricCatalog.GROSS_MARGIN)).isEqualByComparingTo("60.0000");
    // 存量型：取窗口内**最近一天**的快照（2000 + 2500 ≠ 2500 的口径是「余额」）。
    assertThat(value(response, ReportingMetricCatalog.RECEIVABLE_BALANCE)).isEqualByComparingTo("2500.00");
  }

  @Test
  void 没有数据的指标报不可得而不是0() {
    seedTenantA();
    var response = overview.overview(a, DAY1, DAY2, "COMPANY", null, null, null);
    var card =
        response.cards().stream()
            .filter(c -> c.metricCode().equals(ReportingMetricCatalog.RETURN_RATE))
            .findFirst()
            .orElseThrow();
    assertThat(card.availability()).as("没有事实的指标必须报 NO_DATA，不能报 0").isEqualTo("NO_DATA");
    assertThat(card.values()).as("NO_DATA 的指标不得返回 0 冒充").isEmpty();
  }

  @Test
  void 同一事件重投被幂等跳过_投影不翻倍() {
    seedTenantA();
    var again =
        projection.applyBatch(
            a,
            List.of(
                event(
                    "trade",
                    "trade:orders:SO-1",
                    ReportingProjectionModels.ReportingEventTypes.SALES_FACT,
                    DAY1,
                    List.of(
                        companyFact(ReportingMetricCatalog.SALES_AMOUNT, DAY1, "USD", "1000.00", "SO-1")),
                    List.of())));
    assertThat(again.appliedCount()).as("同一事件重投必须被跳过").isZero();
    assertThat(again.skippedCount()).isEqualTo(1);
    assertThat(card(a, ReportingMetricCatalog.SALES_AMOUNT)).isEqualTo("1500");
  }

  @Test
  void 下钻能钻到构成明细() {
    seedTenantA();
    var page =
        overview.drilldown(
            a, ReportingMetricCatalog.SALES_AMOUNT, "COMPANY", null, null, DAY1, DAY2, 0, 50);
    assertThat(page.total()).isEqualTo(2);
    assertThat(page.items()).hasSize(2);
    assertThat(page.items().get(0).referenceId()).isEqualTo("SO-1");
    assertThat(page.items().get(0).value()).isEqualByComparingTo("1000.00");
    assertThat(page.items().get(0).sourceKind()).isEqualTo("ORDER");
  }

  @Test
  void 趋势图只画有事实的天() {
    seedTenantA();
    var points =
        overview.trend(
            a, List.of(ReportingMetricCatalog.SALES_AMOUNT), "COMPANY", null, null, DAY1, DAY2);
    assertThat(points).hasSize(2);
    assertThat(points.get(0).date()).isEqualTo(DAY1);
    assertThat(points.get(0).value()).isEqualByComparingTo("1000.00");
    assertThat(points.get(1).date()).isEqualTo(DAY2);
    assertThat(points.get(1).value()).isEqualByComparingTo("500.00");
  }

  @Test
  void 数据更新时间常驻且可查() {
    seedTenantA();
    var response = overview.overview(a, DAY1, DAY2, "COMPANY", null, null, null);
    assertThat(response.dataUpdatedAt()).as("数据更新时间必须常驻").isNotNull();
    assertThat(response.dataUpdatedNote()).contains("投影落库时刻");
  }

  @Test
  void 环比与同比按等长窗口取数() {
    seedTenantA();
    // 上一等长周期（9-30 ~ 10-01）没有事实 ⇒ 变化率不可比，必须说出来而不是显示 0%。
    var response = overview.overview(a, DAY1, DAY2, "COMPANY", null, null, null);
    var card =
        response.cards().stream()
            .filter(c -> c.metricCode().equals(ReportingMetricCatalog.SALES_AMOUNT))
            .findFirst()
            .orElseThrow();
    assertThat(card.previousPeriod()).isNotNull();
    // 环比窗口 = 紧邻的等长周期：[10-01, 10-02] 之前的那两天。
    assertThat(card.previousPeriod().from()).isEqualTo(LocalDate.of(2026, 9, 29));
    assertThat(card.previousPeriod().to()).isEqualTo(LocalDate.of(2026, 9, 30));
    assertThat(card.yearOverYear().from()).isEqualTo(LocalDate.of(2025, 10, 1));
    assertThat(card.yearOverYear().to()).isEqualTo(LocalDate.of(2025, 10, 2));
  }

  @Test
  void 口径目录是唯一定义处() {
    seedTenantA();
    var definitions = overview.metricDefinitions(a);
    assertThat(definitions).isNotEmpty();
    var grossMargin =
        definitions.stream()
            .filter(d -> d.metricCode().equals(ReportingMetricCatalog.GROSS_MARGIN))
            .findFirst()
            .orElseThrow();
    // 派生型只写分子分母**引用**，不写公式 SQL。
    assertThat(grossMargin.numeratorMetricCode()).isEqualTo(ReportingMetricCatalog.GROSS_PROFIT);
    assertThat(grossMargin.denominatorMetricCode()).isEqualTo(ReportingMetricCatalog.SALES_AMOUNT);
    assertThat(grossMargin.aggregation()).isEqualTo("DERIVED_RATIO");
  }

  @Test
  void 清空重建后同一批事件能重新投影_账本只追加不删行() {
    seedTenantA();
    // 账本是 append-only（DB 层 REVOKE 了 UPDATE/DELETE）⇒ 清空靠**换纪元**，
    // 旧账本原样留着当证据。
    var cleared = projection.clearProjection(a);
    assertThat(cleared).as("清掉的事实条数").isEqualTo(8);
    assertThat(card(a, ReportingMetricCatalog.SALES_AMOUNT)).as("清空后确实没有事实了").isNull();

    // 同一批事件重投：纪元变了 ⇒ 不再被幂等键挡掉 ⇒ 事实回到原样。
    seedTenantA();
    assertThat(card(a, ReportingMetricCatalog.SALES_AMOUNT)).as("重放后事实回到原样").isEqualTo("1500");

    // 旧账本行还在（举证凭据没被抹掉），且两轮各有一条：5 个事件 × 2 轮 = 10 行。
    var ledgerRows =
        tenants.run(
            a,
            () ->
                sql.fetch(
                        "select epoch from reporting_projection_event where tenant_id = ? order by epoch",
                        a)
                    .map(r -> r.get(0, Integer.class)));
    assertThat(ledgerRows)
        .as("两轮各留一批账本，旧的不删")
        .containsExactly(0, 0, 0, 0, 0, 1, 1, 1, 1, 1);
  }

  // ==========================================================================
  // 时间字段：timestamptz 写回后必须逐秒相等（AGENTS.md 硬要求 5）
  // ==========================================================================

  @Test
  void 事件时刻写回数据库后是同一瞬间_不偏28800秒() {
    // ⚠️ **必须用一个非 UTC 的时刻**（+08:00）：拿 UTC 时刻去测，
    //   「用 Timestamp.valueOf(LocalDateTime) 会静默偏 −28800s」这条 bug 根本不会被触发
    //   —— 那样测试会假绿，而报表的分桶日已经错了。
    var occurredAt = OffsetDateTime.of(2026, 10, 1, 8, 30, 0, 0, ZoneOffset.ofHours(8));
    projection.applyBatch(
        a,
        List.of(
            new ReportingProjectionModels.ProjectionEvent(
                "trade",
                "trade:orders:SO-TZ",
                ReportingProjectionModels.ReportingEventTypes.SALES_FACT,
                null,
                0,
                occurredAt,
                "Asia/Shanghai",
                List.of(
                    companyFact(
                        ReportingMetricCatalog.SALES_AMOUNT, DAY1, "USD", "1000.00", "SO-TZ")),
                List.of())));

    // ⚠️ 断言**写回的值**，不是「没抛异常」。
    var raw =
        tenants.run(
            a,
            () ->
                sql.fetchOne(
                    "select occurred_at, applied_at from reporting_projection_event "
                        + "where tenant_id = ? and source_event_id = ?",
                    a,
                    "trade:orders:SO-TZ"));
    assertThat(raw).as("账本行必须落库").isNotNull();
    assertThat(raw.get("occurred_at", OffsetDateTime.class).toInstant())
        .as("occurred_at 必须与写进去的是同一瞬间（偏 28800s 就会红）")
        .isEqualTo(occurredAt.toInstant());

    // applied_at 是「数据更新时间」的来源之一，必须有值且落在合理区间。
    var appliedAt = raw.get("applied_at", OffsetDateTime.class);
    assertThat(appliedAt).as("applied_at 必须有值").isNotNull();
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    assertThat(appliedAt.toInstant())
        .as("applied_at 是投影落库时刻，必须落在当下")
        .isBetween(now.minusMinutes(5).toInstant(), now.plusMinutes(5).toInstant());

    // 事实行的 updated_at 同样是「数据更新时间」的来源，也必须有值。
    var factAt =
        tenants.run(
            a,
            () ->
                sql.fetchOne(
                        "select updated_at, timezone from reporting_metric_fact "
                            + "where tenant_id = ? and metric_code = ? and source_reference = ?",
                        a,
                        ReportingMetricCatalog.SALES_AMOUNT,
                        "SO-TZ")
                    .get("updated_at", OffsetDateTime.class));
    assertThat(factAt).as("事实行的 updated_at 必须有值（数据更新时间取它与 applied_at 的较晚者）").isNotNull();

    // 分桶所用时区必须**落库**（跨时区汇总要显示所用时区）。
    var storedZone =
        tenants.run(
            a,
            () ->
                sql.fetchOne(
                        "select timezone from reporting_metric_fact "
                            + "where tenant_id = ? and metric_code = ? and source_reference = ?",
                        a,
                        ReportingMetricCatalog.SALES_AMOUNT,
                        "SO-TZ")
                    .get(0, String.class));
    assertThat(storedZone).as("分桶时区必须落库，不能在查询期现猜").isEqualTo("Asia/Shanghai");
  }

  // ==========================================================================
  // 能力声明
  // ==========================================================================

  @Test
  void 能力声明逐条列出真读过的上游与确实拿不到的部分() throws Exception {
    var body =
        http.perform(
                get("/reporting/overview/capabilities").header("Authorization", "Bearer tenant-a"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var capabilities = JSON.readTree(body);

    // 🔴 **不许用空列表冒充「都接通了」**：能力声明是前端判断「哪些块能画」的依据，
    //   空列表会被读成「上游没问题」，而实际上店铺维度下有一半指标根本读不到。
    assertThat(capabilities.get("upstreamModules").size())
        .as("能力声明必须逐条列出真读过的上游模块")
        .isGreaterThan(0);
    assertThat(capabilities.get("notDelivered").size())
        .as("确实拿不到的部分必须逐条说清楚（每条带原因）")
        .isGreaterThan(0);
    assertThat(body).as("上游要写模块号与模块名，而不只是「接通了」").contains("9.1.1.1 订单列表");
    assertThat(body).contains("14.3.1.1 活动报表");
  }

  // ==========================================================================
  // 工具
  // ==========================================================================

  /** 从总览响应里取某个指标的取值（字符串形式，便于断言精确标度）。 */
  private String card(UUID tenant, String metricCode) {
    var response = overview.overview(tenant, DAY1, DAY2, "COMPANY", null, null, null);
    var card =
        response.cards().stream()
            .filter(c -> c.metricCode().equals(metricCode))
            .findFirst()
            .orElseThrow(() -> new AssertionError("指标卡里没有 " + metricCode));
    if ("NO_DATA".equals(card.availability()) || card.values().isEmpty()) return null;
    var value = card.values().get(0).value();
    return value == null ? null : value.stripTrailingZeros().toPlainString();
  }

  private BigDecimal value(
      ReportingOverviewModels.OverviewResponse response, String metricCode) {
    var card =
        response.cards().stream()
            .filter(c -> c.metricCode().equals(metricCode))
            .findFirst()
            .orElseThrow(() -> new AssertionError("指标卡里没有 " + metricCode));
    assertThat(card.values()).as(metricCode + " 必须有取值").isNotEmpty();
    return card.values().get(0).value();
  }

  private String rawTenantSetting() {
    return sql.fetch("select current_setting('app.current_tenant', true)").stream()
        .findFirst()
        .map(r -> r.get(0, String.class))
        .orElse(null);
  }

  private UUID effectiveTenant() {
    var row = sql.fetchOne("select nullif(current_setting('app.current_tenant', true),'')::uuid");
    return row == null ? null : row.get(0, UUID.class);
  }

  private int visibleFactsWithoutTenant() {
    return sql.fetch("select id from reporting_metric_fact").size();
  }
}
