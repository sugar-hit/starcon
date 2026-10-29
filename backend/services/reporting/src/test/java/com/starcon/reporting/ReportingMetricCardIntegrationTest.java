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
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 1.1.2.1 核心指标卡的**集成测试**（真实 PostgreSQL 16 + FORCE RLS，无内存替身）。
 *
 * <p>本文件验的是**七件事**，每一件都是这个模块最容易做错的地方：
 *
 * <ol>
 *   <li><b>口径不重算</b>：毛利按口径表的分子分母求值（1000 − 600 = 400），
 *       而不是代码里写死的公式；</li>
 *   <li><b>「算不出来」与「等于 0」分开</b>：基期没数据 ⇒ 变化率是 {@code null}
 *       （不是 0%、不是「持平」）；</li>
 *   <li><b>多币种不相加</b>：不给本位币就没有主值；给了本位币且有汇率快照才折算，
 *       没有汇率就是不可判定（不是按 1:1 算）；</li>
 *   <li><b>窗口边界按租户时区</b>：固定时钟落在 UTC 与 Asia/Shanghai 的**不同日期**上，
 *       断言 asOfDate 是租户时区的那一天 —— 写死 UTC 就会红；</li>
 *   <li><b>回款走授权 API</b>：取的是上游自己算好的按币种合计；上游读不到时报
 *       UPSTREAM_UNAVAILABLE（不是回款为 0）；</li>
 *   <li><b>在途金额按「未接通」显示</b>：available=false + 归属模块，**不给公式也不给分母**；</li>
 *   <li><b>下钻只指向已存在的页面</b>：有构成明细才有构成明细入口。</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReportingMetricCardIntegrationTest {

  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  static HttpServer finance;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  static final List<String> permissions =
      List.of("reporting.overview.read", "reporting.projection.read", "reporting.projection.write");

  static final ObjectMapper JSON = JsonMapper.builder().addModule(new JavaTimeModule()).build();

  /**
   * 固定时钟。
   *
   * <p>⚠️ 这个时刻是**故意挑的**：UTC 是 2026-10-25，而 Asia/Shanghai 已经是 2026-10-26。
   * 于是「窗口边界按租户时区算」这件事可以被**确定性**地断言 ——
   * 实现里写死 UTC（13.1.1.2 栽过的那一类）会直接红。
   */
  static final Instant FIXED_NOW = Instant.parse("2026-10-25T20:00:00Z");

  static final String TENANT_ZONE = "Asia/Shanghai";

  static final LocalDate TODAY = LocalDate.of(2026, 10, 26);
  static final LocalDate YESTERDAY = LocalDate.of(2026, 10, 25);
  static final LocalDate LAST_YEAR = LocalDate.of(2025, 10, 26);

  static {
    pg.start();
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      // 运行角色必须 NOBYPASSRLS，否则它会绕过策略，隔离断言全部变成空断言。
      s.execute("create role starcon_app nologin");
      s.execute("create role reporting_app login password 'test-only' nosuperuser nobypassrls");
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

      // 12 财务的假上游：按**调用方自己的 bearer**返回不同的汇总 ——
      // 上游是按已认证成员关系判租户的，测试要把这一层也钉住。
      finance = HttpServer.create(new InetSocketAddress(0), 0);
      finance.createContext(
          "/finance/receipts",
          e -> {
            var bearer = e.getRequestHeaders().getFirst("Authorization");
            var from = queryParam(e.getRequestURI().getQuery(), "receivedFrom");
            var amount = receiptAmount(bearer, from);
            byte[] body =
                JSON.writeValueAsBytes(
                    Map.of(
                        "items",
                        List.of(),
                        "page",
                        0,
                        "size",
                        200,
                        "total",
                        0,
                        "summaryByCurrency",
                        List.of(
                            Map.of(
                                "currency",
                                "USD",
                                "amount",
                                amount,
                                "settledAmount",
                                BigDecimal.ZERO,
                                "unsettledAmount",
                                amount,
                                "count",
                                1,
                                "advanceCount",
                                0,
                                "advanceAmount",
                                BigDecimal.ZERO))));
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      finance.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  /** 假上游的收款金额：按窗口起日给不同数字（用来验环比 / 同比）。 */
  static BigDecimal receiptAmount(String bearer, String from) {
    if (bearer == null || !bearer.contains("tenant-b")) {
      return switch (from == null ? "" : from) {
        case "2026-10-26" -> new BigDecimal("300.00");
        case "2026-10-25" -> new BigDecimal("150.00");
        case "2025-10-26" -> new BigDecimal("100.00");
        default -> new BigDecimal("0.00");
      };
    }
    // 租户 B：只在自己的窗口里有钱，且数字与 A 不同 ⇒ 能证明「上游按调用方身份判租户」。
    return "2026-10-26".equals(from) ? new BigDecimal("900.00") : new BigDecimal("0.00");
  }

  static String queryParam(String query, String name) {
    if (query == null) return null;
    for (var pair : query.split("&")) {
      var idx = pair.indexOf('=');
      if (idx < 0) continue;
      if (pair.substring(0, idx).equals(name)) {
        return URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8);
      }
    }
    return null;
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
    r.add("FINANCE_URL", () -> "http://localhost:" + finance.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
    // 连接池固定为 1 条：只有复用同一条连接，才能真正证明归还后不会串租户。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  /** 固定时钟（覆盖生产用的系统时钟）。 */
  @TestConfiguration
  static class FixedClock {
    @Bean
    @Primary
    Clock fixedClock() {
      return Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
    }
  }

  @Autowired TenantTransactions tenants;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;
  @Autowired ReportingProjectionService projection;
  @Autowired ReportingMetricCardService cards;

  @AfterAll
  static void stop() {
    identity.stop(0);
    finance.stop(0);
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
        "COMPANY", null, null, metricCode, date, currency, new BigDecimal(value), BigDecimal.ZERO, reference);
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
        TENANT_ZONE,
        facts,
        drilldowns);
  }

  /**
   * 租户 A 的一整套事实：今天 / 昨天 / 去年同一天。
   *
   * <p>今天：销售额 1000、成本 600、订单 2 单、销量 5 件、库存快照 5000；
   * 昨天：销售额 500、成本 300、订单 1 单、库存快照 4500；
   * 去年同一天：销售额 800、成本 400。
   */
  private void seedTenantA() {
    projection.applyBatch(
        a,
        List.of(
            event(
                "trade",
                "trade:orders:SO-TODAY",
                ReportingProjectionModels.ReportingEventTypes.SALES_FACT,
                TODAY,
                List.of(
                    companyFact(ReportingMetricCatalog.SALES_AMOUNT, TODAY, "USD", "1000.00", "SO-TODAY"),
                    companyFact(ReportingMetricCatalog.ORDER_COUNT, TODAY, "USD", "2", "SO-TODAY"),
                    companyFact(ReportingMetricCatalog.UNIT_SOLD, TODAY, "USD", "5", "SO-TODAY#SKU-1")),
                List.of(
                    companyDrilldown(
                        ReportingMetricCatalog.SALES_AMOUNT,
                        TODAY,
                        "USD",
                        "ORDER",
                        "SO-TODAY",
                        "SO-TODAY",
                        "0",
                        "1000.00",
                        "SO-TODAY"))),
            event(
                "finance",
                "finance:cost:COST-TODAY",
                ReportingProjectionModels.ReportingEventTypes.COST_FACT,
                TODAY,
                List.of(
                    companyFact(ReportingMetricCatalog.COGS_AMOUNT, TODAY, "USD", "600.00", "COST-TODAY")),
                List.of()),
            event(
                "warehouse",
                "warehouse:stock:SNAP-TODAY",
                ReportingProjectionModels.ReportingEventTypes.INVENTORY_SNAPSHOT,
                TODAY,
                List.of(
                    companyFact(
                        ReportingMetricCatalog.INVENTORY_VALUE, TODAY, "USD", "5000.00", "SNAP-TODAY")),
                List.of()),
            event(
                "trade",
                "trade:orders:SO-YESTERDAY",
                ReportingProjectionModels.ReportingEventTypes.SALES_FACT,
                YESTERDAY,
                List.of(
                    companyFact(
                        ReportingMetricCatalog.SALES_AMOUNT, YESTERDAY, "USD", "500.00", "SO-YESTERDAY"),
                    companyFact(ReportingMetricCatalog.ORDER_COUNT, YESTERDAY, "USD", "1", "SO-YESTERDAY")),
                List.of()),
            event(
                "finance",
                "finance:cost:COST-YESTERDAY",
                ReportingProjectionModels.ReportingEventTypes.COST_FACT,
                YESTERDAY,
                List.of(
                    companyFact(
                        ReportingMetricCatalog.COGS_AMOUNT, YESTERDAY, "USD", "300.00", "COST-YESTERDAY")),
                List.of()),
            event(
                "warehouse",
                "warehouse:stock:SNAP-YESTERDAY",
                ReportingProjectionModels.ReportingEventTypes.INVENTORY_SNAPSHOT,
                YESTERDAY,
                List.of(
                    companyFact(
                        ReportingMetricCatalog.INVENTORY_VALUE, YESTERDAY, "USD", "4500.00", "SNAP-YESTERDAY")),
                List.of()),
            event(
                "trade",
                "trade:orders:SO-LASTYEAR",
                ReportingProjectionModels.ReportingEventTypes.SALES_FACT,
                LAST_YEAR,
                List.of(
                    companyFact(
                        ReportingMetricCatalog.SALES_AMOUNT, LAST_YEAR, "USD", "800.00", "SO-LASTYEAR")),
                List.of()),
            event(
                "finance",
                "finance:cost:COST-LASTYEAR",
                ReportingProjectionModels.ReportingEventTypes.COST_FACT,
                LAST_YEAR,
                List.of(
                    companyFact(
                        ReportingMetricCatalog.COGS_AMOUNT, LAST_YEAR, "USD", "400.00", "COST-LASTYEAR")),
                List.of())));
  }

  // ==========================================================================
  // 断言
  // ==========================================================================

  @Test
  void 能力声明逐条报出上游口径归属与未接通项() throws Exception {
    seedTenantA();
    http.perform(get("/reporting/metric-cards/capabilities").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.metrics.length()").value(7))
        // 五张投影卡：口径来自口径表（分子 / 分母引用，不是公式）。
        .andExpect(jsonPath("$.metrics[?(@.metricCode=='GROSS_PROFIT')].available").value(true))
        .andExpect(jsonPath("$.metrics[?(@.metricCode=='GROSS_PROFIT')].numeratorMetricCode").value("SALES_AMOUNT"))
        .andExpect(jsonPath("$.metrics[?(@.metricCode=='GROSS_PROFIT')].denominatorMetricCode").value("COGS_AMOUNT"))
        .andExpect(jsonPath("$.metrics[?(@.metricCode=='GROSS_PROFIT')].aggregation").value("DERIVED_DIFF"))
        // 回款：口径归 12 财务，走授权 API。
        .andExpect(jsonPath("$.metrics[?(@.metricCode=='CASH_RECEIVED')].available").value(true))
        .andExpect(jsonPath("$.metrics[?(@.metricCode=='CASH_RECEIVED')].sourceKind").value("AUTHORIZED_API"))
        .andExpect(jsonPath("$.metrics[?(@.metricCode=='CASH_RECEIVED')].ownerModule").value("12.2.1.1 收款单（finance）"))
        // 在途金额：未接通 ⇒ available=false + 归属模块 + **不给公式也不给分母**。
        .andExpect(jsonPath("$.metrics[?(@.metricCode=='IN_TRANSIT_VALUE')].available").value(false))
        .andExpect(jsonPath("$.metrics[?(@.metricCode=='IN_TRANSIT_VALUE')].ownerModule")
            .value("06 采购在途（trade）/ 07 库存在途（warehouse）"))
        .andExpect(jsonPath("$.metrics[?(@.metricCode=='IN_TRANSIT_VALUE')].unavailableReason")
            .value(org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.containsString("口径"))))
        // 未接通清单逐条列出（不是一句「都接通了」）。
        .andExpect(jsonPath("$.notDelivered.length()").value(3));

    // 未接通的指标**不给公式也不给分母**（null，而不是一个编出来的口径）。
    var transit =
        cards.capabilities(a).metrics().stream()
            .filter(m -> m.metricCode().equals("IN_TRANSIT_VALUE"))
            .findFirst()
            .orElseThrow();
    assertThat(transit.available()).isFalse();
    assertThat(transit.numeratorMetricCode()).as("未接通 ⇒ 不给分子").isNull();
    assertThat(transit.denominatorMetricCode()).as("未接通 ⇒ 不给分母").isNull();
    assertThat(transit.caliber()).as("未接通 ⇒ 不给口径说明").isEmpty();
    assertThat(transit.ownerModule()).contains("06 采购在途");
  }

  @Test
  void 五张投影卡按口径表求值_毛利是分子分母相减而不是代码里的公式() {
    seedTenantA();
    var response = cards.cards(a, "Bearer tenant-a", "TODAY", null, null, null);

    assertThat(card(response, "SALES_AMOUNT").headline().value()).isEqualByComparingTo("1000.0000");
    assertThat(card(response, "ORDER_COUNT").headline().value()).isEqualByComparingTo("2.0000");
    assertThat(card(response, "UNIT_SOLD").headline().value()).isEqualByComparingTo("5.0000");
    // 派生差：1000 − 600 = 400（口径表说减，不是代码写死）。
    assertThat(card(response, "GROSS_PROFIT").headline().value()).isEqualByComparingTo("400.0000");
    // 存量型：窗口内最近一天的快照，不是把两天加起来。
    assertThat(card(response, "INVENTORY_VALUE").headline().value()).isEqualByComparingTo("5000.0000");
    // 回款来自上游授权 API 的按币种合计。
    assertThat(card(response, "CASH_RECEIVED").headline().value()).isEqualByComparingTo("300.0000");
    // 在途金额：未接通 ⇒ 没有主值（不是 0）。
    assertThat(card(response, "IN_TRANSIT_VALUE").status()).isEqualTo("NOT_CONNECTED");
    assertThat(card(response, "IN_TRANSIT_VALUE").headline()).isNull();
    assertThat(card(response, "IN_TRANSIT_VALUE").values()).isEmpty();
  }

  @Test
  void 环比同比由后端算出_趋势箭头也跟着变化率走() {
    seedTenantA();
    var response = cards.cards(a, "Bearer tenant-a", "TODAY", null, null, null);

    var sales = card(response, "SALES_AMOUNT");
    // 今日 1000 / 昨日 500 ⇒ +100.00%
    assertThat(sales.previousPeriod().changeRate()).isEqualByComparingTo("100.00");
    assertThat(sales.previousPeriod().comparable()).isTrue();
    assertThat(sales.previousPeriod().from()).isEqualTo(YESTERDAY);
    assertThat(sales.previousPeriod().to()).isEqualTo(YESTERDAY);
    // 今日 1000 / 去年同一天 800 ⇒ +25.00%
    assertThat(sales.yearOverYear().changeRate()).isEqualByComparingTo("25.00");
    assertThat(sales.yearOverYear().from()).isEqualTo(LAST_YEAR);
    // 趋势箭头由后端算出的变化率决定。
    assertThat(sales.trend()).isEqualTo("UP");
    assertThat(sales.trendBasis()).isEqualTo("环比");

    // 回款：今日 300 / 昨日 150 ⇒ +100.00%；去年同一天 100 ⇒ +200.00%
    var received = card(response, "CASH_RECEIVED");
    assertThat(received.previousPeriod().changeRate()).isEqualByComparingTo("100.00");
    assertThat(received.yearOverYear().changeRate()).isEqualByComparingTo("200.00");
  }

  @Test
  void 基期没有数据时变化率不可判定_不是0也不是持平() {
    // 只播种今天（昨天与去年同一天都没有事实）。
    projection.applyBatch(
        a,
        List.of(
            event(
                "trade",
                "trade:orders:SO-ONLY-TODAY",
                ReportingProjectionModels.ReportingEventTypes.SALES_FACT,
                TODAY,
                List.of(
                    companyFact(
                        ReportingMetricCatalog.SALES_AMOUNT, TODAY, "USD", "1000.00", "SO-ONLY-TODAY")),
                List.of())));
    var response = cards.cards(a, "Bearer tenant-a", "TODAY", null, null, null);
    var sales = card(response, "SALES_AMOUNT");

    assertThat(sales.headline().value()).isEqualByComparingTo("1000.0000");
    assertThat(sales.previousPeriod().changeRate()).as("基期没数据 ⇒ 不可判定").isNull();
    assertThat(sales.previousPeriod().comparable()).isFalse();
    assertThat(sales.previousPeriod().reason()).contains("缺少");
    assertThat(sales.trend()).as("没有可比的变化率就没有箭头").isEqualTo("UNKNOWN");
  }

  @Test
  void 基期取值为0时变化率不可比_不是负100() {
    projection.applyBatch(
        a,
        List.of(
            event(
                "trade",
                "trade:orders:SO-TODAY",
                ReportingProjectionModels.ReportingEventTypes.SALES_FACT,
                TODAY,
                List.of(
                    companyFact(
                        ReportingMetricCatalog.SALES_AMOUNT, TODAY, "USD", "1000.00", "SO-TODAY")),
                List.of()),
            event(
                "trade",
                "trade:orders:SO-ZERO",
                ReportingProjectionModels.ReportingEventTypes.SALES_FACT,
                YESTERDAY,
                List.of(
                    companyFact(
                        ReportingMetricCatalog.SALES_AMOUNT, YESTERDAY, "USD", "0.00", "SO-ZERO")),
                List.of())));
    var response = cards.cards(a, "Bearer tenant-a", "TODAY", null, null, null);
    var sales = card(response, "SALES_AMOUNT");

    assertThat(sales.previousPeriod().changeRate()).as("基期为 0 ⇒ 不可比").isNull();
    assertThat(sales.previousPeriod().reason()).contains("0");
  }

  @Test
  void 窗口边界按租户时区算_不是写死UTC() {
    seedTenantA();
    var response = cards.cards(a, "Bearer tenant-a", "TODAY", null, null, null);

    // 固定时钟落在 UTC 的 2026-10-25，而 Asia/Shanghai 已经是 2026-10-26。
    assertThat(response.timezone()).isEqualTo(TENANT_ZONE);
    assertThat(response.timezoneSource()).isEqualTo("PROJECTION");
    assertThat(response.asOfDate()).isEqualTo(TODAY);
    assertThat(response.from()).isEqualTo(TODAY);
    assertThat(response.to()).isEqualTo(TODAY);
  }

  @Test
  void 本月窗口按自然月整月_环比对比上月整月() {
    seedTenantA();
    var response = cards.cards(a, "Bearer tenant-a", "THIS_MONTH", null, null, null);

    assertThat(response.from()).isEqualTo(LocalDate.of(2026, 10, 1));
    assertThat(response.to()).isEqualTo(LocalDate.of(2026, 10, 31));
    // 本月尚未结束 ⇒ 如实说明（不假装本月已完整）。
    assertThat(response.warnings()).anyMatch(w -> w.contains("本月尚未结束"));
  }

  @Test
  void 没有投影事实的租户时区落回UTC并说明() {
    var response = cards.cards(a, "Bearer tenant-a", "TODAY", null, null, null);

    assertThat(response.timezone()).isEqualTo("UTC");
    assertThat(response.timezoneSource()).isEqualTo("FALLBACK_UTC");
    assertThat(response.warnings()).anyMatch(w -> w.contains("还没有任何投影事实"));
  }

  @Test
  void 多币种不给本位币就没有主值_给了且有汇率才折算() {
    projection.applyBatch(
        a,
        List.of(
            event(
                "trade",
                "trade:orders:SO-USD",
                ReportingProjectionModels.ReportingEventTypes.SALES_FACT,
                TODAY,
                List.of(
                    companyFact(
                        ReportingMetricCatalog.SALES_AMOUNT, TODAY, "USD", "1000.00", "SO-USD")),
                List.of()),
            event(
                "trade",
                "trade:orders:SO-EUR",
                ReportingProjectionModels.ReportingEventTypes.SALES_FACT,
                TODAY,
                List.of(
                    companyFact(
                        ReportingMetricCatalog.SALES_AMOUNT, TODAY, "EUR", "200.00", "SO-EUR")),
                List.of())));

    // 没有汇率快照 ⇒ 多币种没有主值（不是随便挑一个币种充数）。
    var withoutBase = cards.cards(a, "Bearer tenant-a", "TODAY", null, null, null);
    assertThat(card(withoutBase, "SALES_AMOUNT").headline()).isNull();
    assertThat(card(withoutBase, "SALES_AMOUNT").values()).hasSize(2);
    assertThat(card(withoutBase, "SALES_AMOUNT").previousPeriod().changeRate()).isNull();
    assertThat(card(withoutBase, "SALES_AMOUNT").previousPeriod().reason()).contains("本位币");

    // 补一条汇率快照（EUR → USD，生效日不晚于窗口末）。
    tenants.run(
        a,
        () -> {
          sql.execute(
              "insert into reporting_currency_rate_snapshot"
                  + " (id,tenant_id,currency,base_currency,rate,as_of,source_service,created_at)"
                  + " values (?,?,?,?,?,?::timestamptz,?,now())",
              UUID.randomUUID(),
              a,
              "EUR",
              "USD",
              new BigDecimal("1.10000000"),
              TODAY.atStartOfDay(ZoneOffset.UTC).toOffsetDateTime(),
              "test");
          return null;
        });

    var withBase = cards.cards(a, "Bearer tenant-a", "TODAY", null, null, "USD");
    var sales = card(withBase, "SALES_AMOUNT");
    assertThat(sales.headline().currency()).isEqualTo("USD");
    assertThat(sales.headline().value()).isEqualByComparingTo("1220.0000"); // 1000 + 200 × 1.1
  }

  @Test
  void 多币种缺汇率就是不可判定_不是按1比1算() {
    projection.applyBatch(
        a,
        List.of(
            event(
                "trade",
                "trade:orders:SO-USD",
                ReportingProjectionModels.ReportingEventTypes.SALES_FACT,
                TODAY,
                List.of(
                    companyFact(
                        ReportingMetricCatalog.SALES_AMOUNT, TODAY, "USD", "1000.00", "SO-USD")),
                List.of()),
            event(
                "trade",
                "trade:orders:SO-EUR",
                ReportingProjectionModels.ReportingEventTypes.SALES_FACT,
                TODAY,
                List.of(
                    companyFact(
                        ReportingMetricCatalog.SALES_AMOUNT, TODAY, "EUR", "200.00", "SO-EUR")),
                List.of())));

    var response = cards.cards(a, "Bearer tenant-a", "TODAY", null, null, "USD");
    var sales = card(response, "SALES_AMOUNT");
    // 缺 EUR→USD 的汇率 ⇒ 折不出来 ⇒ 主值没有、变化率不可判定。
    assertThat(sales.headline()).isNull();
    assertThat(sales.previousPeriod().changeRate()).isNull();
  }

  @Test
  void 下钻入口只指向已存在的页面_没有构成明细就没有明细入口() {
    seedTenantA();
    var response = cards.cards(a, "Bearer tenant-a", "TODAY", null, null, null);

    // 销售额有构成明细（投影器登记过 ORDER 行）。
    var sales = card(response, "SALES_AMOUNT").drilldown();
    assertThat(sales.available()).isTrue();
    assertThat(sales.detailPath()).contains("reporting/overview/drilldown");
    assertThat(sales.detailPath()).contains("metricCode=SALES_AMOUNT");
    assertThat(sales.detailPath()).contains("from=" + TODAY);
    assertThat(sales.reportPath()).startsWith("/analytics");

    // 订单量没有构成明细（投影器只给销售额登记 ORDER 行）⇒ 只有报表页入口。
    var orders = card(response, "ORDER_COUNT").drilldown();
    assertThat(orders.detailPath()).isNull();
    assertThat(orders.reportPath()).startsWith("/analytics");
    assertThat(orders.reason()).contains("没有这个指标的构成明细");

    // 毛利是派生指标 ⇒ 没有单一构成明细行。
    assertThat(card(response, "GROSS_PROFIT").drilldown().detailPath()).isNull();

    // 在途金额未接通 ⇒ 完全没有下钻入口。
    var transit = card(response, "IN_TRANSIT_VALUE").drilldown();
    assertThat(transit.available()).isFalse();
    assertThat(transit.detailPath()).isNull();
    assertThat(transit.reportPath()).isNull();
  }

  @Test
  void 未认证的请求被拒() throws Exception {
    http.perform(get("/reporting/metric-cards")).andExpect(status().isUnauthorized());
    http.perform(get("/reporting/metric-cards/capabilities")).andExpect(status().isUnauthorized());
  }

  @Test
  void 非法窗口参数报400() throws Exception {
    http.perform(
            get("/reporting/metric-cards").param("window", "LAST_WEEK").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isBadRequest());
    http.perform(
            get("/reporting/metric-cards")
                .param("window", "CUSTOM")
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isBadRequest());
    http.perform(
            get("/reporting/metric-cards")
                .param("window", "CUSTOM")
                .param("from", "2026-10-10")
                .param("to", "2026-10-01")
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void 口径说明与数据截至时刻随卡片一起给出() {
    seedTenantA();
    var response = cards.cards(a, "Bearer tenant-a", "TODAY", null, null, null);

    assertThat(card(response, "SALES_AMOUNT").caliber()).contains("口径归属");
    assertThat(card(response, "GROSS_PROFIT").caliber()).contains("毛利额");
    assertThat(response.dataUpdatedAt()).isNotNull();
    assertThat(response.dataUpdatedNote()).contains("投影落库时刻");
  }

  // ==========================================================================
  // 工具
  // ==========================================================================

  private static ReportingMetricCardModels.MetricCardEntry card(
      ReportingMetricCardModels.MetricCardsResponse response, String metricCode) {
    return response.cards().stream()
        .filter(c -> c.metricCode().equals(metricCode))
        .findFirst()
        .orElseThrow(() -> new AssertionError("响应里没有这张卡：" + metricCode));
  }
}
