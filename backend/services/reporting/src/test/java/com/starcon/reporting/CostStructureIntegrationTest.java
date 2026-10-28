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
 * 13.4.2.1 成本结构的**集成测试**（真实 PostgreSQL 16 + FORCE RLS，无内存替身）。
 *
 * <p>跑的是本模块的**业务口径**：
 *
 * <ol>
 *   <li>七类成本（采购 / 头程 / 平台费 / 物流 / 广告 / 退货 / 其它）都能拆出来；</li>
 *   <li>占比的分母口径写明，且**绝不跨币种相加**；</li>
 *   <li>「算不出来」与「等于 0」分开（没有事实 ⇒ 占比不可比，**不是 0%**）；</li>
 *   <li>基线来自**真实历史投影数据**（前 N 期同口径占比），环比 / 同比都是真算的；</li>
 *   <li>异常阈值**可配置**（固定百分点 / 基线标准差两种口径），判不出来就说判不出来；</li>
 *   <li>同一批上游事实重放被幂等跳过（成本不翻倍）；</li>
 *   <li>上游事实按七类成本归桶，并补上 SKU → 类目映射。</li>
 * </ol>
 *
 * <p>租户隔离的三条断言在 {@code CostStructureTenancyIntegrationTest}（同一批跑）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class CostStructureIntegrationTest {

  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  static HttpServer upstream;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static final Map<String, String> upstreamAuth = new ConcurrentHashMap<>();

  static final List<String> permissions =
      List.of(
          "reporting.cost-structure.read",
          "reporting.cost-structure.write",
          "reporting.projection.read",
          "reporting.projection.write");

  static final ObjectMapper JSON = JsonMapper.builder().addModule(new JavaTimeModule()).build();

  /** 当前窗口。 */
  static final LocalDate DAY = LocalDate.of(2026, 10, 10);

  /** 历史基线窗口（基线要前 3 期 ⇒ 10-07 / 10-08 / 10-09）。 */
  static final LocalDate HIST1 = LocalDate.of(2026, 10, 9);
  static final LocalDate HIST2 = LocalDate.of(2026, 10, 8);
  static final LocalDate HIST3 = LocalDate.of(2026, 10, 7);

  /** 去年同期。 */
  static final LocalDate YOY = LocalDate.of(2025, 10, 10);

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

      // 假上游：只服务刷新路径真正会打到的两个端点（SKU 列表 + 成本计算条目）。
      upstream = HttpServer.create(new InetSocketAddress(0), 0);
      upstream.createContext(
          "/catalog/skus",
          e -> {
            upstreamAuth.put("/catalog/skus", e.getRequestHeaders().getFirst("Authorization"));
            byte[] body =
                ("{\"items\":[{\"id\":\"11111111-1111-1111-1111-111111111111\","
                        + "\"code\":\"SKU-1\",\"category\":\"家居收纳\"}],"
                        + "\"total\":1,\"page\":0,\"size\":200}")
                    .getBytes();
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      upstream.createContext(
          "/finance/cost-calculation/entries",
          e -> {
            upstreamAuth.put(
                "/finance/cost-calculation/entries", e.getRequestHeaders().getFirst("Authorization"));
            byte[] body =
                ("{\"items\":[{\"id\":\"22222222-2222-2222-2222-222222222222\","
                        + "\"occurredAt\":\"2026-10-10T00:00:00Z\",\"businessType\":\"SALES_OUTBOUND\","
                        + "\"skuId\":\"11111111-1111-1111-1111-111111111111\",\"skuCode\":\"SKU-1\","
                        + "\"issueQuantity\":\"2\",\"unitCost\":\"30\",\"costAmount\":\"60\","
                        + "\"currency\":\"USD\",\"status\":\"CALCULATED\"}],"
                        + "\"page\":0,\"size\":200,\"total\":1}")
                    .getBytes();
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      upstream.start();
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
    r.add("CATALOG_URL", () -> "http://localhost:" + upstream.getAddress().getPort());
    r.add("FINANCE_URL", () -> "http://localhost:" + upstream.getAddress().getPort());
    r.add("TRADE_URL", () -> "http://localhost:" + upstream.getAddress().getPort());
    // ⚠️ 连接池固定为 1 条连接：只有复用同一条连接，才能真正证明归还后不会串租户。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  @Autowired TenantTransactions tenants;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;
  @Autowired ReportingCostStructureService costStructure;
  @Autowired ReportingCostStructureRefreshService refresh;

  @AfterAll
  static void stop() {
    identity.stop(0);
    upstream.stop(0);
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
              + " reporting_cost_structure_fact, reporting_cost_structure_rule cascade");
    }
    upstreamAuth.clear();
  }

  // ==========================================================================
  // 夹具
  // ==========================================================================

  /** 幂等键（必须与 V399 的 identity 唯一索引逐字一致）。 */
  private static final String IDENTITY_CONFLICT =
      """
      on conflict (tenant_id,cost_category,cost_sub_type,
                   platform_code,site_code,channel_code,carrier_code,shipping_method_code,
                   shop_code,sku_code,category_code,
                   period_start,period_end,currency,source_service,source_reference)
      do update set amount = excluded.amount, quantity = excluded.quantity, updated_at = now()
      """;

  /** 写一条成本事实（直接落投影表 —— 与刷新路径写的是同一张表、同一套幂等键）。 */
  private void fact(
      String costCategory,
      String costSubType,
      LocalDate date,
      String skuCode,
      String currency,
      String amount) {
    fact(costCategory, costSubType, date, skuCode, "", currency, amount);
  }

  /** 写一条成本事实（带类目维度）。 */
  private void fact(
      String costCategory,
      String costSubType,
      LocalDate date,
      String skuCode,
      String categoryCode,
      String currency,
      String amount) {
    tenants.run(
        a,
        () -> {
          sql.execute(
              """
              insert into reporting_cost_structure_fact
                (id,tenant_id,cost_category,cost_sub_type,
                 platform_code,site_code,channel_code,carrier_code,shipping_method_code,
                 shop_code,sku_code,category_code,
                 period_start,period_end,currency,amount,quantity,
                 source_service,source_reference,updated_at)
              values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,now())
              """
                  + IDENTITY_CONFLICT,
              java.util.UUID.randomUUID(),
              a,
              costCategory,
              costSubType,
              "",
              "",
              "",
              "",
              "",
              "",
              skuCode,
              categoryCode,
              date,
              date,
              currency,
              new BigDecimal(amount),
              BigDecimal.ZERO,
              "test",
              "test:" + costCategory + ":" + date + ":" + skuCode + ":" + currency);
          return null;
        });
  }

  /** 写一条销售额事实（收入口径的分母）。 */
  private void revenue(LocalDate date, String currency, String value) {
    tenants.run(
        a,
        () -> {
          // ⚠️ DB 层有守卫触发器：指标码**必须先在口径表登记**才能落事实。
          sql.execute(
              """
              insert into reporting_metric_definition
                (id,tenant_id,metric_code,display_name,unit,aggregation,
                 numerator_metric_code,denominator_metric_code,currency_aware,
                 description,owner_module,enabled,created_at,updated_at)
              values (?,?,?,?,?,?,?,?,?,?,?,?,now(),now())
              on conflict (tenant_id, metric_code) do nothing
              """,
              java.util.UUID.randomUUID(),
              a,
              "SALES_AMOUNT",
              "销售额",
              "CURRENCY",
              "SUM",
              null,
              null,
              true,
              "测试用标准口径",
              "13.2.1.1",
              true);
          sql.execute(
              """
              insert into reporting_metric_fact
                (id,tenant_id,scope_type,legal_entity_id,shop_id,metric_code,
                 bucket_date,currency,value,timezone,source_service,source_reference,updated_at)
              values (?,?,?,?,?,?,?,?,?,?,?,?,now())
              """,
              java.util.UUID.randomUUID(),
              a,
              "COMPANY",
              null,
              null,
              "SALES_AMOUNT",
              date,
              currency,
              new BigDecimal(value),
              "UTC",
              "test",
              "test:revenue:" + date + ":" + currency);
          return null;
        });
  }

  /** 一次结构查询（默认口径：总成本 / 全部维度 / 按日）。 */
  private ReportingCostStructureModels.CostStructureResponse structure(
      LocalDate from, LocalDate to) {
    return costStructure.structure(a, from, to, "DAY", "ALL", "TOTAL_COST", null, null, null, null);
  }

  /** 找到某一类成本的那一行。 */
  private static ReportingCostStructureModels.CostStructureRow rowOf(
      ReportingCostStructureModels.CostStructureResponse response, String costCategory) {
    return response.rows().stream()
        .filter(row -> row.costCategory().equals(costCategory))
        .findFirst()
        .orElseThrow(() -> new AssertionError("没有 " + costCategory + " 这一行"));
  }

  // ==========================================================================
  // 业务口径
  // ==========================================================================

  @Test
  void 七类成本都能拆出来_占比之和为百分之百() {
    fact("PURCHASE", "SALES_OUTBOUND", DAY, "SKU-1", "USD", "60.0000");
    fact("HEAD_MILE", "SHIPMENT", DAY, "SKU-1", "USD", "10.0000");
    fact("PLATFORM_FEE", "COMMISSION", DAY, "", "USD", "15.0000");
    fact("LOGISTICS_FEE", "FBA_FEE", DAY, "", "USD", "5.0000");
    fact("AD_FEE", "ADVERTISING", DAY, "", "USD", "8.0000");
    fact("RETURN", "REFUND", DAY, "", "USD", "2.0000");
    fact("OTHER", "SUBSCRIPTION_FEE", DAY, "", "USD", "0.0000");

    var response = structure(DAY, DAY);
    assertThat(response.rows()).as("七类成本都要有一行").hasSize(7);

    var total = BigDecimal.ZERO;
    for (var row : response.rows()) {
      assertThat(row.share()).as(row.costCategory() + " 的占比必须算得出来").isNotNull();
      assertThat(row.share().scale()).as("占比固定 4 位小数").isEqualTo(4);
      total = total.add(row.share());
    }
    assertThat(total).as("总成本口径下占比之和 = 100%").isEqualByComparingTo("100.0000");

    assertThat(rowOf(response, "PURCHASE").share()).isEqualByComparingTo("60.0000");
    assertThat(rowOf(response, "HEAD_MILE").share()).isEqualByComparingTo("10.0000");
    assertThat(rowOf(response, "AD_FEE").share()).isEqualByComparingTo("8.0000");
    assertThat(rowOf(response, "OTHER").share()).as("有事实且金额为 0 ⇒ 真的是 0%").isEqualByComparingTo("0.0000");
  }

  @Test
  void 没有事实的类别占比不可比_不是零() {
    fact("PURCHASE", "SALES_OUTBOUND", DAY, "SKU-1", "USD", "60.0000");
    fact("AD_FEE", "ADVERTISING", DAY, "", "USD", "40.0000");

    var response = structure(DAY, DAY);
    assertThat(response.rows()).as("只有有事实的类别才有行").hasSize(2);
    assertThat(response.rows().stream().map(r -> r.costCategory()))
        .containsExactlyInAnyOrder("PURCHASE", "AD_FEE");
    assertThat(response.warnings()).anyMatch(w -> w.contains("不可比") && w.contains("不是 0%"));
    assertThat(response.warnings()).anyMatch(w -> w.contains("头程分摊"));
  }

  @Test
  void 分母为零时占比算不出来_不是零() {
    fact("PURCHASE", "SALES_OUTBOUND", DAY, "SKU-1", "USD", "0.0000");
    fact("AD_FEE", "ADVERTISING", DAY, "", "USD", "0.0000");

    var response = structure(DAY, DAY);
    var purchase = rowOf(response, "PURCHASE");
    assertThat(purchase.share()).as("分母为 0 ⇒ 占比算不出来").isNull();
    assertThat(purchase.comparable()).isFalse();
    assertThat(purchase.note()).contains("不是 0%");
  }

  @Test
  void 环比与同比基线来自真实历史投影数据() {
    // 前 3 期：采购 / 广告 各占一半。
    fact("PURCHASE", "SALES_OUTBOUND", HIST3, "SKU-1", "USD", "100.0000");
    fact("AD_FEE", "ADVERTISING", HIST3, "", "USD", "100.0000");
    fact("PURCHASE", "SALES_OUTBOUND", HIST2, "SKU-1", "USD", "100.0000");
    fact("AD_FEE", "ADVERTISING", HIST2, "", "USD", "100.0000");
    fact("PURCHASE", "SALES_OUTBOUND", HIST1, "SKU-1", "USD", "100.0000");
    fact("AD_FEE", "ADVERTISING", HIST1, "", "USD", "100.0000");
    // 当期：广告占比冲到 75%。
    fact("PURCHASE", "SALES_OUTBOUND", DAY, "SKU-1", "USD", "50.0000");
    fact("AD_FEE", "ADVERTISING", DAY, "", "USD", "150.0000");
    // 去年同期：采购占 80%。
    fact("PURCHASE", "SALES_OUTBOUND", YOY, "SKU-1", "USD", "80.0000");
    fact("AD_FEE", "ADVERTISING", YOY, "", "USD", "20.0000");

    var response = structure(DAY, DAY);
    var purchase = rowOf(response, "PURCHASE");
    assertThat(purchase.share()).isEqualByComparingTo("25.0000");
    assertThat(purchase.previousShare()).as("环比基线 = 前一期同长度窗口的占比").isEqualByComparingTo("50.0000");
    assertThat(purchase.baselineShare()).as("历史基线 = 前 3 期占比均值").isEqualByComparingTo("50.0000");
    assertThat(purchase.yoyShare()).as("同比基线 = 去年同期占比").isEqualByComparingTo("80.0000");
    assertThat(purchase.deltaVsPrevious()).isEqualByComparingTo("-25.0000");
    assertThat(purchase.deltaVsYoY()).isEqualByComparingTo("-55.0000");
  }

  @Test
  void 历史期数不足时基线算不出来_判不了异常() {
    fact("PURCHASE", "SALES_OUTBOUND", DAY, "SKU-1", "USD", "50.0000");
    fact("AD_FEE", "ADVERTISING", DAY, "", "USD", "150.0000");

    var response = structure(DAY, DAY);
    var purchase = rowOf(response, "PURCHASE");
    assertThat(purchase.baselineShare()).as("没有历史 ⇒ 基线算不出来").isNull();
    assertThat(purchase.anomaly()).as("基线算不出来时不能报异常").isFalse();
    assertThat(purchase.anomalyReason()).contains("历史");
  }

  @Test
  void 异常阈值可配置_改规则后判定跟着变() {
    fact("PURCHASE", "SALES_OUTBOUND", HIST3, "SKU-1", "USD", "100.0000");
    fact("AD_FEE", "ADVERTISING", HIST3, "", "USD", "100.0000");
    fact("PURCHASE", "SALES_OUTBOUND", HIST2, "SKU-1", "USD", "100.0000");
    fact("AD_FEE", "ADVERTISING", HIST2, "", "USD", "100.0000");
    fact("PURCHASE", "SALES_OUTBOUND", HIST1, "SKU-1", "USD", "100.0000");
    fact("AD_FEE", "ADVERTISING", HIST1, "", "USD", "100.0000");
    fact("PURCHASE", "SALES_OUTBOUND", DAY, "SKU-1", "USD", "50.0000");
    fact("AD_FEE", "ADVERTISING", DAY, "", "USD", "150.0000");

    // 默认阈值 5 个百分点 ⇒ 广告 +25、采购 −25，两边都超阈值 ⇒ 两条异常。
    var response = structure(DAY, DAY);
    assertThat(rowOf(response, "AD_FEE").anomaly()).as("超过默认阈值 ⇒ 异常").isTrue();
    assertThat(rowOf(response, "PURCHASE").anomaly()).as("下降也超阈值（BOTH 方向）").isTrue();
    assertThat(response.anomalies()).hasSize(2);
    assertThat(response.anomalies().stream().map(a -> a.costCategory()).toList())
        .containsExactlyInAnyOrder("AD_FEE", "PURCHASE");

    // 把阈值改成 50 个百分点 ⇒ 不再是异常（**阈值不是写死的**）。
    for (var category : List.of("AD_FEE", "PURCHASE")) {
      costStructure.updateRule(
          a,
          category,
          new ReportingCostStructureModels.CostStructureRuleInput(
              null, new BigDecimal("50.000000"), null, null, null));
    }
    var after = structure(DAY, DAY);
    assertThat(rowOf(after, "AD_FEE").anomaly()).as("阈值改大后不再异常").isFalse();
    assertThat(after.anomalies()).isEmpty();
    assertThat(rowOf(after, "AD_FEE").anomalyReason()).contains("未达阈值");
  }

  @Test
  void 判定方向可配置_只看占比上升() {
    fact("PURCHASE", "SALES_OUTBOUND", HIST3, "SKU-1", "USD", "100.0000");
    fact("AD_FEE", "ADVERTISING", HIST3, "", "USD", "100.0000");
    fact("PURCHASE", "SALES_OUTBOUND", HIST2, "SKU-1", "USD", "100.0000");
    fact("AD_FEE", "ADVERTISING", HIST2, "", "USD", "100.0000");
    fact("PURCHASE", "SALES_OUTBOUND", HIST1, "SKU-1", "USD", "100.0000");
    fact("AD_FEE", "ADVERTISING", HIST1, "", "USD", "100.0000");
    fact("PURCHASE", "SALES_OUTBOUND", DAY, "SKU-1", "USD", "150.0000");
    fact("AD_FEE", "ADVERTISING", DAY, "", "USD", "50.0000");

    // 采购占比 +25、广告占比 −25。
    costStructure.updateRule(
        a,
        "PURCHASE",
        new ReportingCostStructureModels.CostStructureRuleInput(
            null, null, null, "UP", null));
    costStructure.updateRule(
        a,
        "AD_FEE",
        new ReportingCostStructureModels.CostStructureRuleInput(
            null, null, null, "UP", null));

    var response = structure(DAY, DAY);
    assertThat(rowOf(response, "PURCHASE").anomaly()).as("占比上升 ⇒ 命中 UP 方向").isTrue();
    assertThat(rowOf(response, "AD_FEE").anomaly()).as("占比下降 ⇒ 不命中 UP 方向").isFalse();
  }

  @Test
  void 标准差口径的阈值随历史波动伸缩() {
    // 三期历史占比：50% / 60% / 40% ⇒ 均值 50、标准差 10。
    fact("PURCHASE", "SALES_OUTBOUND", HIST3, "SKU-1", "USD", "40.0000");
    fact("AD_FEE", "ADVERTISING", HIST3, "", "USD", "60.0000");
    fact("PURCHASE", "SALES_OUTBOUND", HIST2, "SKU-1", "USD", "60.0000");
    fact("AD_FEE", "ADVERTISING", HIST2, "", "USD", "40.0000");
    fact("PURCHASE", "SALES_OUTBOUND", HIST1, "SKU-1", "USD", "50.0000");
    fact("AD_FEE", "ADVERTISING", HIST1, "", "USD", "50.0000");
    // 当期广告占比 75%（相对基线 50 差 25 个百分点）。
    fact("PURCHASE", "SALES_OUTBOUND", DAY, "SKU-1", "USD", "50.0000");
    fact("AD_FEE", "ADVERTISING", DAY, "", "USD", "150.0000");

    costStructure.updateRule(
        a,
        "AD_FEE",
        new ReportingCostStructureModels.CostStructureRuleInput(
            "STDDEV", new BigDecimal("2.000000"), null, null, null));

    var response = structure(DAY, DAY);
    var ad = rowOf(response, "AD_FEE");
    assertThat(ad.anomaly()).as("25 个百分点 ≥ 2 × 标准差(10) ⇒ 异常").isTrue();
    assertThat(response.anomalies().get(0).thresholdMode()).isEqualTo("STDDEV");
    assertThat(response.anomalies().get(0).effectiveThreshold()).isEqualByComparingTo("20.0000");
  }

  @Test
  void 基线没有波动时标准差口径判不了异常() {
    fact("PURCHASE", "SALES_OUTBOUND", HIST3, "SKU-1", "USD", "100.0000");
    fact("AD_FEE", "ADVERTISING", HIST3, "", "USD", "100.0000");
    fact("PURCHASE", "SALES_OUTBOUND", HIST2, "SKU-1", "USD", "100.0000");
    fact("AD_FEE", "ADVERTISING", HIST2, "", "USD", "100.0000");
    fact("PURCHASE", "SALES_OUTBOUND", HIST1, "SKU-1", "USD", "100.0000");
    fact("AD_FEE", "ADVERTISING", HIST1, "", "USD", "100.0000");
    fact("PURCHASE", "SALES_OUTBOUND", DAY, "SKU-1", "USD", "50.0000");
    fact("AD_FEE", "ADVERTISING", DAY, "", "USD", "150.0000");

    costStructure.updateRule(
        a,
        "AD_FEE",
        new ReportingCostStructureModels.CostStructureRuleInput(
            "STDDEV", new BigDecimal("2.000000"), null, null, null));

    var response = structure(DAY, DAY);
    var ad = rowOf(response, "AD_FEE");
    assertThat(ad.anomaly()).as("标准差为 0 ⇒ 判不了，不是「没异常」").isFalse();
    assertThat(ad.anomalyReason()).contains("标准差");
  }

  @Test
  void 收入口径的分母是销售额且口径写在响应里() {
    fact("PURCHASE", "SALES_OUTBOUND", DAY, "SKU-1", "USD", "60.0000");
    fact("AD_FEE", "ADVERTISING", DAY, "", "USD", "40.0000");
    revenue(DAY, "USD", "1000.0000");

    var response =
        costStructure.structure(a, DAY, DAY, "DAY", "ALL", "REVENUE", null, null, null, null);
    assertThat(response.denominatorCaliber()).isEqualTo("REVENUE");
    assertThat(response.denominatorNote()).contains("销售额");
    assertThat(rowOf(response, "PURCHASE").share()).isEqualByComparingTo("6.0000");
    assertThat(rowOf(response, "AD_FEE").share()).isEqualByComparingTo("4.0000");
  }

  @Test
  void 跨币种绝不相加() {
    fact("PURCHASE", "SALES_OUTBOUND", DAY, "SKU-1", "USD", "60.0000");
    fact("AD_FEE", "ADVERTISING", DAY, "", "USD", "40.0000");
    fact("PURCHASE", "SALES_OUTBOUND", DAY, "SKU-2", "EUR", "30.0000");
    fact("AD_FEE", "ADVERTISING", DAY, "", "EUR", "70.0000");

    var response = structure(DAY, DAY);
    assertThat(response.rows()).as("两个币种各一组").hasSize(4);
    var usd = response.rows().stream().filter(r -> r.currency().equals("USD")).toList();
    var eur = response.rows().stream().filter(r -> r.currency().equals("EUR")).toList();
    assertThat(usd).hasSize(2);
    assertThat(eur).hasSize(2);
    assertThat(rowOf(response, "PURCHASE").denominatorAmount()).isNotNull();
    for (var row : response.rows()) {
      assertThat(row.denominatorAmount()).as("分母只在本币种内合计").isLessThanOrEqualTo(new BigDecimal("100"));
    }
    // 币种过滤只回一个币种。
    var onlyEur =
        costStructure.structure(a, DAY, DAY, "DAY", "ALL", "TOTAL_COST", "EUR", null, null, null);
    assertThat(onlyEur.rows()).hasSize(2);
    assertThat(onlyEur.rows().stream().map(r -> r.currency()).distinct().toList()).containsExactly("EUR");
  }

  @Test
  void 趋势按期间粒度分桶_分母算不出来时没有点() {
    fact("PURCHASE", "SALES_OUTBOUND", DAY, "SKU-1", "USD", "60.0000");
    fact("AD_FEE", "ADVERTISING", DAY, "", "USD", "40.0000");
    fact("PURCHASE", "SALES_OUTBOUND", HIST1, "SKU-1", "USD", "100.0000");

    var response = structure(DAY, DAY);
    assertThat(response.trend()).isNotEmpty();
    for (var point : response.trend()) {
      assertThat(point.comparable()).as("有分母的点必须可比").isTrue();
      assertThat(point.share()).isNotNull();
    }
  }

  @Test
  void 数据更新时间为空时是空值_不是负无穷() {
    var response = structure(DAY, DAY);
    assertThat(response.dataUpdatedAt()).as("没有事实 ⇒ 没有数据更新时间").isNull();
    assertThat(response.rows()).isEmpty();

    fact("PURCHASE", "SALES_OUTBOUND", DAY, "SKU-1", "USD", "60.0000");
    var after = structure(DAY, DAY);
    assertThat(after.dataUpdatedAt()).as("有事实 ⇒ 有数据更新时间").isNotNull();
  }

  // ==========================================================================
  // 刷新路径（真实上游 ⇒ 投影表）
  // ==========================================================================

  @Test
  void 刷新把上游事实归到七类成本并补上类目映射() {
    var summaries =
        refresh.refresh(a, "Bearer tenant-a", DAY, DAY, List.of("PURCHASE"));
    assertThat(summaries).hasSize(1);
    assertThat(summaries.get(0).status()).isEqualTo("OK");
    assertThat(summaries.get(0).factCount()).isEqualTo(1);

    // ⚠️ **bearer 必须原样转发给上游**（租户只来自已认证成员关系）。
    assertThat(upstreamAuth.get("/finance/cost-calculation/entries")).isEqualTo("Bearer tenant-a");
    assertThat(upstreamAuth.get("/catalog/skus")).isEqualTo("Bearer tenant-a");

    var rows =
        tenants.run(
            a,
            () ->
                sql.fetch(
                    "select cost_category, sku_code, category_code, currency, amount "
                        + "from reporting_cost_structure_fact where tenant_id = ?",
                    a));
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).get(0, String.class)).isEqualTo("PURCHASE");
    assertThat(rows.get(0).get(1, String.class)).isEqualTo("SKU-1");
    assertThat(rows.get(0).get(2, String.class)).as("类目由 catalog 的 SKU 档案映射").isEqualTo("家居收纳");
    assertThat(rows.get(0).get(3, String.class)).isEqualTo("USD");
    assertThat(rows.get(0).get(4, BigDecimal.class)).isEqualByComparingTo("60.0000");
  }

  @Test
  void 同一批上游事实重放被幂等跳过_成本不翻倍() {
    refresh.refresh(a, "Bearer tenant-a", DAY, DAY, List.of("PURCHASE"));
    refresh.refresh(a, "Bearer tenant-a", DAY, DAY, List.of("PURCHASE"));

    var count =
        tenants.run(
            a, () -> sql.fetch("select id from reporting_cost_structure_fact where tenant_id = ?", a).size());
    assertThat(count).as("重放不会翻倍").isEqualTo(1);

    var response = structure(DAY, DAY);
    assertThat(rowOf(response, "PURCHASE").amount()).isEqualByComparingTo("60.0000");
  }

  // ==========================================================================
  // HTTP 边界
  // ==========================================================================

  @Test
  void 端点返回结构化结果() throws Exception {
    fact("PURCHASE", "SALES_OUTBOUND", DAY, "SKU-1", "USD", "60.0000");
    fact("AD_FEE", "ADVERTISING", DAY, "", "USD", "40.0000");

    http.perform(get("/reporting/cost-structure/capabilities").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.costCategories.length()").value(7));
    http.perform(
            get("/reporting/cost-structure/structure")
                .param("from", DAY.toString())
                .param("to", DAY.toString())
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rows.length()").value(2))
        .andExpect(jsonPath("$.denominatorCaliber").value("TOTAL_COST"));
    http.perform(
            get("/reporting/cost-structure/trend")
                .param("from", DAY.toString())
                .param("to", DAY.toString())
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk());
    http.perform(
            get("/reporting/cost-structure/anomalies")
                .param("from", DAY.toString())
                .param("to", DAY.toString())
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk());
    http.perform(get("/reporting/cost-structure/rules").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(7));
  }

  @Test
  void 非法参数给一句能读懂的话() throws Exception {
    http.perform(
            get("/reporting/cost-structure/structure")
                .param("dimension", "NOPE")
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isBadRequest());
    http.perform(
            get("/reporting/cost-structure/structure")
                .param("denominatorCaliber", "NOPE")
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isBadRequest());
    http.perform(
            get("/reporting/cost-structure/structure")
                .param("from", "2026-10-10")
                .param("to", "2026-10-01")
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isBadRequest());
  }
}
