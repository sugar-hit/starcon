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
 * 13.4.3.1 利润预警的**集成测试**（真实 PostgreSQL 16 + FORCE RLS，无内存替身）。
 *
 * <p>跑的是本模块的**业务口径**：
 *
 * <ol>
 *   <li><b>五类预警各自真的命中</b>（SKU 毛利转负 / 店铺利润率骤降 / 费用占比异常 /
 *       广告亏损超标 / 亏损订单占比突增）；</li>
 *   <li><b>「算不出来」≠「等于 0」</b>：缺成本项 ⇒ 毛利是 {@code null} + 原因，
 *       该条预警进「判不出来」清单，**不是「没异常」**；</li>
 *   <li><b>基线来自真实历史投影</b>（前 N 期同口径窗口），历史不够 ⇒ 判不了；</li>
 *   <li><b>阈值来自租户自己的规则表</b>（改阈值会真的改变判定结果）；</li>
 *   <li><b>通知是幂等的投递记录</b>（同一窗口同一预警不重复通知），且
 *       <b>终态不可复活</b>；</li>
 *   <li><b>多币种绝不相加</b>；</li>
 *   <li><b>数据更新时间</b>：没有投影时是 {@code null}（不是 {@code '-infinity'} 假值）。</li>
 * </ol>
 *
 * <p>租户隔离的三条断言在 {@code ProfitAlertTenancyIntegrationTest}（同一批跑）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ProfitAlertIntegrationTest {

  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  static final List<String> permissions =
      List.of(
          "reporting.profit-alert.read",
          "reporting.profit-alert.write",
          "reporting.projection.read",
          "reporting.projection.write");

  static final ObjectMapper JSON = JsonMapper.builder().addModule(new JavaTimeModule()).build();

  /** 当前窗口（1 天 ⇒ 基线窗口是前 1 天、前 2 天）。 */
  static final LocalDate DAY = LocalDate.of(2026, 10, 10);

  static final LocalDate DAY_MINUS_1 = LocalDate.of(2026, 10, 9);
  static final LocalDate DAY_MINUS_2 = LocalDate.of(2026, 10, 8);

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
  @Autowired ReportingProfitAlertService alerts;

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
          "truncate reporting_profit_alert_notification, reporting_profit_alert_rule,"
              + " reporting_profit_fact, reporting_profit_shop_profile,"
              + " reporting_metric_fact, reporting_drilldown_fact, reporting_projection_event,"
              + " reporting_projection_cursor, reporting_currency_rate_snapshot,"
              + " reporting_metric_definition cascade");
    }
  }

  // ==========================================================================
  // 夹具
  // ==========================================================================

  /** 一条利润事实（写库前的手工 SQL：与投影器写进去的形状逐字一致）。 */
  private void fact(
      UUID tenant,
      String mode,
      String component,
      String shop,
      String sku,
      String orderRef,
      LocalDate period,
      String currency,
      String amount) {
    tenants.run(
        tenant,
        () -> {
          sql.execute(
              """
              insert into reporting_profit_fact
                (id,tenant_id,operating_mode,component,shop_code,sku_code,spu_code,msku_code,order_ref,
                 period_start,period_end,currency,amount,quantity,source_service,source_reference,updated_at)
              values (?,?,?,?,?,?,'','',?,?,?,?,?,0,'test','test:'||?,now())
              """,
              UUID.randomUUID(),
              tenant,
              mode,
              component,
              shop,
              sku,
              orderRef,
              period,
              period,
              currency,
              new BigDecimal(amount),
              UUID.randomUUID().toString());
          return null;
        });
  }

  /** 店铺口径的一笔（收入用**订单总额**口径：sku 传空串）。 */
  private void shopFact(
      UUID tenant, String component, String shop, LocalDate period, String currency, String amount) {
    fact(tenant, "SELF_FULFILLED", component, shop, "", "", period, currency, amount);
  }

  /** 订单口径的一笔（收入用**订单总额**口径，挂在订单上）。 */
  private void orderFact(
      UUID tenant,
      String component,
      String shop,
      String orderRef,
      LocalDate period,
      String currency,
      String amount) {
    fact(tenant, "SELF_FULFILLED", component, shop, "", orderRef, period, currency, amount);
  }

  /** 预警清单（当前窗口）。 */
  private ReportingProfitAlertModels.ProfitAlertPage page(UUID tenant) {
    return alerts.alerts(tenant, DAY, DAY, null, null, null, null, 0, 200);
  }

  /** 某类预警在某归属取值上的那一条（找不到 ⇒ null）。 */
  private ReportingProfitAlertModels.ProfitAlert alertOf(
      UUID tenant, String alertType, String scopeKey) {
    return page(tenant).alerts().stream()
        .filter(x -> x.alertType().equals(alertType) && x.scopeKey().equals(scopeKey))
        .findFirst()
        .orElse(null);
  }

  // ==========================================================================
  // 断言 1：SKU 毛利转负
  // ==========================================================================

  @Test
  void 断言1_SKU毛利转负真的命中() {
    fact(a, "SELF_FULFILLED", "REVENUE", "SHOP-1", "SKU-1", "ORDER-1", DAY, "USD", "100");
    fact(a, "SELF_FULFILLED", "COGS", "SHOP-1", "SKU-1", "", DAY, "USD", "120");

    var alert = alertOf(a, "SKU_NEGATIVE_GROSS_PROFIT", "SKU-1");

    assertThat(alert).as("SKU 毛利转负必须真的命中").isNotNull();
    assertThat(alert.observedValue()).as("毛利率 -20%").isEqualByComparingTo("-20");
    assertThat(alert.grossProfit()).as("毛利 -20").isEqualByComparingTo("-20");
    assertThat(alert.currency()).isEqualTo("USD");
    assertThat(alert.severity()).isEqualTo("CRITICAL");
    assertThat(alert.thresholdValue()).as("默认阈值 0").isEqualByComparingTo("0");
    assertThat(alert.reason()).contains("SKU-1");
    // 处置入口必须是**已经存在**的站内页面，且**逐条列出**。
    assertThat(alert.actions()).isNotEmpty();
    assertThat(alert.actions()).allSatisfy(a -> assertThat(a.href()).startsWith("/"));
  }

  // ==========================================================================
  // 断言 2：缺成本项 ⇒ 算不出来（不是 0，也不是「没异常」）
  // ==========================================================================

  @Test
  void 断言2_缺成本项时判不出来而不是报零() {
    // SKU-1 有成本（⇒ COGS 在 SKU 维度上**适用**），SKU-2 只有收入。
    fact(a, "SELF_FULFILLED", "REVENUE", "SHOP-1", "SKU-1", "ORDER-1", DAY, "USD", "100");
    fact(a, "SELF_FULFILLED", "COGS", "SHOP-1", "SKU-1", "", DAY, "USD", "120");
    fact(a, "SELF_FULFILLED", "REVENUE", "SHOP-1", "SKU-2", "ORDER-2", DAY, "USD", "50");

    var response = page(a);

    assertThat(alertOf(a, "SKU_NEGATIVE_GROSS_PROFIT", "SKU-1")).isNotNull();
    assertThat(alertOf(a, "SKU_NEGATIVE_GROSS_PROFIT", "SKU-2"))
        .as("缺成本项 ⇒ 毛利算不出来 ⇒ **不许报成「毛利 0」**")
        .isNull();
    var undecided =
        response.undecided().stream()
            .filter(x -> x.scopeKey().equals("SKU-2"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("SKU-2 应当进「判不出来」清单，实际：" + response.undecided()));
    assertThat(undecided.reason()).contains("算不出来");
    assertThat(undecided.reason()).contains("不是");
  }

  // ==========================================================================
  // 断言 3：店铺利润率骤降（基线来自真实历史投影）
  // ==========================================================================

  @Test
  void 断言3_店铺利润率骤降按历史基线命中() {
    // 基线两期：毛利率 30%（收入 100 / 成本 70）。
    shopFact(a, "REVENUE", "SHOP-1", DAY_MINUS_1, "USD", "100");
    shopFact(a, "COGS", "SHOP-1", DAY_MINUS_1, "USD", "70");
    shopFact(a, "REVENUE", "SHOP-1", DAY_MINUS_2, "USD", "100");
    shopFact(a, "COGS", "SHOP-1", DAY_MINUS_2, "USD", "70");
    // 当前窗口：毛利率 10%（收入 100 / 成本 90）⇒ 下降 20 个百分点。
    shopFact(a, "REVENUE", "SHOP-1", DAY, "USD", "100");
    shopFact(a, "COGS", "SHOP-1", DAY, "USD", "90");

    var alert = alertOf(a, "SHOP_MARGIN_DROP", "SHOP-1");

    assertThat(alert).as("毛利率从 30% 掉到 10% ⇒ 必须命中").isNotNull();
    assertThat(alert.observedValue()).as("当前毛利率 10%").isEqualByComparingTo("10");
    assertThat(alert.baselineValue()).as("基线 = 前两期均值 30%").isEqualByComparingTo("30");
    assertThat(alert.delta()).as("下降 20 个百分点").isEqualByComparingTo("20");
    assertThat(alert.thresholdMode()).isEqualTo("BASELINE");
    assertThat(alert.thresholdUnit()).isEqualTo("PERCENTAGE_POINTS");
  }

  // ==========================================================================
  // 断言 4：历史不够 ⇒ 判不了（不是「没骤降」）
  // ==========================================================================

  @Test
  void 断言4_历史期数不足时判不出来而不是没骤降() {
    // 只有一期基线（规则要求 2 期）⇒ 基线算不出来。
    shopFact(a, "REVENUE", "SHOP-1", DAY_MINUS_1, "USD", "100");
    shopFact(a, "COGS", "SHOP-1", DAY_MINUS_1, "USD", "70");
    shopFact(a, "REVENUE", "SHOP-1", DAY, "USD", "100");
    shopFact(a, "COGS", "SHOP-1", DAY, "USD", "90");

    var response = page(a);

    assertThat(alertOf(a, "SHOP_MARGIN_DROP", "SHOP-1"))
        .as("基线算不出来 ⇒ 不许报「没骤降」")
        .isNull();
    var undecided =
        response.undecided().stream()
            .filter(x -> x.alertType().equals("SHOP_MARGIN_DROP") && x.scopeKey().equals("SHOP-1"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("应当进「判不出来」清单"));
    assertThat(undecided.reason()).contains("历史基线");
    assertThat(undecided.reason()).contains("不是");
  }

  // ==========================================================================
  // 断言 5：费用占比异常
  // ==========================================================================

  @Test
  void 断言5_费用占比超阈值命中() {
    // 收入 100，费用 40+20+25 = 85 ⇒ 费用占比 85% ≥ 80%。
    shopFact(a, "REVENUE", "SHOP-1", DAY, "USD", "100");
    shopFact(a, "COGS", "SHOP-1", DAY, "USD", "10");
    shopFact(a, "PLATFORM_FEE", "SHOP-1", DAY, "USD", "40");
    shopFact(a, "LOGISTICS_FEE", "SHOP-1", DAY, "USD", "20");
    shopFact(a, "AD_FEE", "SHOP-1", DAY, "USD", "25");

    var alert = alertOf(a, "FEE_RATIO_ANOMALY", "SHOP-1");

    assertThat(alert).as("费用占比 85% ≥ 80% ⇒ 必须命中").isNotNull();
    assertThat(alert.observedValue()).as("费用占比 85%").isEqualByComparingTo("85");
    assertThat(alert.thresholdValue()).isEqualByComparingTo("80");
    assertThat(alert.observedUnit()).isEqualTo("PERCENT");
  }

  // ==========================================================================
  // 断言 6：广告亏损超标（广告费 ÷ 毛利 的倍数）
  // ==========================================================================

  @Test
  void 断言6_广告亏损超标按倍数命中() {
    // 收入 100，成本 10+5+5+50 = 70 ⇒ 毛利 30；广告费 50 ⇒ 倍数 1.67 ≥ 1。
    shopFact(a, "REVENUE", "SHOP-1", DAY, "USD", "100");
    shopFact(a, "COGS", "SHOP-1", DAY, "USD", "10");
    shopFact(a, "PLATFORM_FEE", "SHOP-1", DAY, "USD", "5");
    shopFact(a, "LOGISTICS_FEE", "SHOP-1", DAY, "USD", "5");
    shopFact(a, "AD_FEE", "SHOP-1", DAY, "USD", "50");

    var alert = alertOf(a, "AD_LOSS_EXCEEDED", "SHOP-1");

    assertThat(alert).as("广告费是毛利的 1.67 倍 ≥ 1 ⇒ 必须命中").isNotNull();
    assertThat(alert.observedValue()).isEqualByComparingTo("1.66666667");
    assertThat(alert.observedUnit()).isEqualTo("MULTIPLE");
    assertThat(alert.grossProfit()).as("毛利 30").isEqualByComparingTo("30");
    assertThat(alert.reason()).contains("广告");
  }

  // ==========================================================================
  // 断言 7：亏损订单占比突增
  // ==========================================================================

  @Test
  void 断言7_亏损订单占比突增按基线命中() {
    // 基线两期：2 个订单都盈利 ⇒ 亏损占比 0%。
    for (var day : List.of(DAY_MINUS_1, DAY_MINUS_2)) {
      orderFact(a, "REVENUE", "SHOP-1", "ORDER-B1", day, "USD", "100");
      orderFact(a, "COGS", "SHOP-1", "ORDER-B1", day, "USD", "50");
      orderFact(a, "REVENUE", "SHOP-1", "ORDER-B2", day, "USD", "100");
      orderFact(a, "COGS", "SHOP-1", "ORDER-B2", day, "USD", "50");
    }
    // 当前窗口：2 个订单里 1 个亏损 ⇒ 亏损占比 50%（基线 0% ⇒ 上升 50 个百分点）。
    orderFact(a, "REVENUE", "SHOP-1", "ORDER-C1", DAY, "USD", "100");
    orderFact(a, "COGS", "SHOP-1", "ORDER-C1", DAY, "USD", "150");
    orderFact(a, "REVENUE", "SHOP-1", "ORDER-C2", DAY, "USD", "100");
    orderFact(a, "COGS", "SHOP-1", "ORDER-C2", DAY, "USD", "50");

    var alert = alertOf(a, "LOSS_ORDER_RATIO_SPIKE", "SHOP-1");

    assertThat(alert).as("亏损订单占比 0% → 50% ⇒ 必须命中").isNotNull();
    assertThat(alert.observedValue()).as("亏损占比 50%").isEqualByComparingTo("50");
    assertThat(alert.baselineValue()).as("基线 0%").isEqualByComparingTo("0");
    assertThat(alert.delta()).as("上升 50 个百分点").isEqualByComparingTo("50");
    assertThat(alert.reason()).contains("可比");
  }

  // ==========================================================================
  // 断言 8：多币种绝不相加
  // ==========================================================================

  @Test
  void 断言8_多币种不相加() {
    fact(a, "SELF_FULFILLED", "REVENUE", "SHOP-1", "SKU-1", "ORDER-1", DAY, "USD", "100");
    fact(a, "SELF_FULFILLED", "COGS", "SHOP-1", "SKU-1", "", DAY, "USD", "120");
    fact(a, "SELF_FULFILLED", "REVENUE", "SHOP-2", "SKU-2", "ORDER-2", DAY, "EUR", "100");
    fact(a, "SELF_FULFILLED", "COGS", "SHOP-2", "SKU-2", "", DAY, "EUR", "120");

    var alertsUsd =
        page(a).alerts().stream().filter(x -> x.currency().equals("USD")).toList();
    var alertsEur =
        page(a).alerts().stream().filter(x -> x.currency().equals("EUR")).toList();

    assertThat(alertsUsd).hasSize(1);
    assertThat(alertsEur).hasSize(1);
    // 🔴 **没有任何一条是跨币种相加的结果**（毛利必须是 -20，不是 -40）。
    assertThat(page(a).alerts())
        .allSatisfy(x -> assertThat(x.grossProfit()).isEqualByComparingTo("-20"));
  }

  // ==========================================================================
  // 断言 9：阈值来自租户自己的规则表（改阈值真的改变判定）
  // ==========================================================================

  @Test
  void 断言9_改阈值真的改变判定结果() {
    fact(a, "SELF_FULFILLED", "REVENUE", "SHOP-1", "SKU-1", "ORDER-1", DAY, "USD", "100");
    fact(a, "SELF_FULFILLED", "COGS", "SHOP-1", "SKU-1", "", DAY, "USD", "110");

    // 毛利率 -10%：默认阈值 0 ⇒ 命中。
    assertThat(alertOf(a, "SKU_NEGATIVE_GROSS_PROFIT", "SKU-1")).isNotNull();

    // 把阈值调到 -30（更严格）⇒ 不再命中。
    alerts.updateRule(
        a,
        "SKU_NEGATIVE_GROSS_PROFIT",
        new ReportingProfitAlertModels.ProfitAlertRuleInput(
            "FIXED", new BigDecimal("-30"), null, null, null));
    assertThat(alertOf(a, "SKU_NEGATIVE_GROSS_PROFIT", "SKU-1"))
        .as("阈值更严格 ⇒ 不再命中（阈值确实在起作用）")
        .isNull();

    // 规则读回来是改后的值。
    var rule =
        alerts.rules(a).stream()
            .filter(r -> r.alertType().equals("SKU_NEGATIVE_GROSS_PROFIT"))
            .findFirst()
            .orElseThrow();
    assertThat(rule.thresholdValue()).isEqualByComparingTo("-30");
    assertThat(rule.thresholdUnit()).isEqualTo("PERCENT");
    assertThat(rule.updatedAt()).as("改过就有修改时刻").isNotNull();
  }

  @Test
  void 断言10_非法阈值被挡下来() {
    // 「SKU 毛利转负」的阈值必须 ≤ 0。
    assertThatThrownBy(
            () ->
                alerts.updateRule(
                    a,
                    "SKU_NEGATIVE_GROSS_PROFIT",
                    new ReportingProfitAlertModels.ProfitAlertRuleInput(
                        "FIXED", new BigDecimal("10"), null, null, null)))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
        .hasMessageContaining("毛利率上限");
    // 规则类型不认识 ⇒ 一句能展示的话。
    assertThatThrownBy(
            () ->
                alerts.updateRule(
                    a,
                    "NOT-A-TYPE",
                    new ReportingProfitAlertModels.ProfitAlertRuleInput(null, null, null, null, null)))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
        .hasMessageContaining("alertType");
    // 「广告亏损超标」不支持 BASELINE 口径。
    assertThatThrownBy(
            () ->
                alerts.updateRule(
                    a,
                    "AD_LOSS_EXCEEDED",
                    new ReportingProfitAlertModels.ProfitAlertRuleInput(
                        "BASELINE", new BigDecimal("1"), null, null, null)))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
        .hasMessageContaining("BASELINE");
  }

  // ==========================================================================
  // 断言 11：通知是幂等的投递记录
  // ==========================================================================

  @Test
  void 断言11_通知登记是幂等的() {
    fact(a, "SELF_FULFILLED", "REVENUE", "SHOP-1", "SKU-1", "ORDER-1", DAY, "USD", "100");
    fact(a, "SELF_FULFILLED", "COGS", "SHOP-1", "SKU-1", "", DAY, "USD", "120");

    var first = alerts.registerNotifications(a, DAY, DAY);
    assertThat(first.matched()).isEqualTo(1);
    assertThat(first.created()).isEqualTo(1);
    assertThat(first.skipped()).isZero();

    var second = alerts.registerNotifications(a, DAY, DAY);
    assertThat(second.created()).as("同一窗口同一预警不重复通知").isZero();
    assertThat(second.skipped()).isEqualTo(1);

    var ledger = alerts.notifications(a, null, null, 0, 50);
    assertThat(ledger.total()).isEqualTo(1);
    assertThat(ledger.pendingCount()).isEqualTo(1);
    var record = ledger.notifications().get(0);
    assertThat(record.status()).isEqualTo("PENDING");
    assertThat(record.channel()).isEqualTo("CONSOLE");
    assertThat(record.observedValue()).isEqualByComparingTo("-20");
    assertThat(record.alertFingerprint()).contains("SKU_NEGATIVE_GROSS_PROFIT");
  }

  // ==========================================================================
  // 断言 12：通知状态机（终态不可复活）
  // ==========================================================================

  @Test
  void 断言12_通知终态不可复活() {
    fact(a, "SELF_FULFILLED", "REVENUE", "SHOP-1", "SKU-1", "ORDER-1", DAY, "USD", "100");
    fact(a, "SELF_FULFILLED", "COGS", "SHOP-1", "SKU-1", "", DAY, "USD", "120");
    alerts.registerNotifications(a, DAY, DAY);
    var record = alerts.notifications(a, null, null, 0, 50).notifications().get(0);
    var id = UUID.fromString(record.id());

    var acked =
        alerts.updateNotification(
            a,
            user,
            id,
            new ReportingProfitAlertModels.ProfitAlertNotificationInput("ACKNOWLEDGED"));
    assertThat(acked.status()).isEqualTo("ACKNOWLEDGED");
    assertThat(acked.acknowledgedAt()).isNotNull();
    assertThat(acked.acknowledgedBy()).isEqualTo(user.toString());

    assertThatThrownBy(
            () ->
                alerts.updateNotification(
                    a,
                    user,
                    id,
                    new ReportingProfitAlertModels.ProfitAlertNotificationInput("DISMISSED")))
        .as("终态不可复活")
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
        .hasMessageContaining("终态不可复活");

    assertThatThrownBy(
            () ->
                alerts.updateNotification(
                    a,
                    user,
                    id,
                    new ReportingProfitAlertModels.ProfitAlertNotificationInput("PENDING")))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
  }

  // ==========================================================================
  // 断言 13：数据更新时间
  // ==========================================================================

  @Test
  void 断言13_数据更新时间没投影时是空而不是哨兵值() {
    var empty = page(a);
    assertThat(empty.alerts()).isEmpty();
    assertThat(empty.dataUpdatedAt()).as("尚未投影任何利润事实 ⇒ null").isNull();
    assertThat(empty.dataUpdatedNote()).as("没有投影时也要有一句说明").isNotBlank();

    fact(a, "SELF_FULFILLED", "REVENUE", "SHOP-1", "SKU-1", "ORDER-1", DAY, "USD", "100");
    fact(a, "SELF_FULFILLED", "COGS", "SHOP-1", "SKU-1", "", DAY, "USD", "120");

    var withFact = page(a);
    assertThat(withFact.dataUpdatedAt()).isNotNull();
    // 🔴 **断言写回的值**：库里的 updated_at 与响应里的 dataUpdatedAt 必须是同一个时刻。
    var stored =
        tenants.run(
            a,
            () ->
                sql.fetchOne("select max(updated_at) from reporting_profit_fact where tenant_id = ?", a));
    assertThat(stored.get(0, OffsetDateTime.class)).isEqualTo(withFact.dataUpdatedAt());
  }

  // ==========================================================================
  // 断言 14：真分页 + 真计数
  // ==========================================================================

  @Test
  void 断言14_预警清单是真分页真计数() {
    for (int i = 0; i < 5; i++) {
      fact(a, "SELF_FULFILLED", "REVENUE", "SHOP-1", "SKU-" + i, "ORDER-" + i, DAY, "USD", "100");
      fact(a, "SELF_FULFILLED", "COGS", "SHOP-1", "SKU-" + i, "", DAY, "USD", "120");
    }

    var first = alerts.alerts(a, DAY, DAY, null, null, null, null, 0, 2);
    assertThat(first.total()).as("真计数").isEqualTo(5);
    assertThat(first.alerts()).hasSize(2);
    assertThat(first.page()).isZero();
    assertThat(first.size()).isEqualTo(2);

    var second = alerts.alerts(a, DAY, DAY, null, null, null, null, 1, 2);
    assertThat(second.alerts()).hasSize(2);
    assertThat(second.alerts().get(0).scopeKey())
        .as("翻页不重复")
        .isNotEqualTo(first.alerts().get(0).scopeKey());
  }

  // ==========================================================================
  // 断言 15：HTTP 端点
  // ==========================================================================

  @Test
  void 断言15_预警端点可用() throws Exception {
    fact(a, "SELF_FULFILLED", "REVENUE", "SHOP-1", "SKU-1", "ORDER-1", DAY, "USD", "100");
    fact(a, "SELF_FULFILLED", "COGS", "SHOP-1", "SKU-1", "", DAY, "USD", "120");

    http.perform(
            get("/reporting/profit-alert/alerts")
                .header("Authorization", "Bearer tenant-a")
                .param("from", DAY.toString())
                .param("to", DAY.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.alerts[0].alertType").value("SKU_NEGATIVE_GROSS_PROFIT"))
        .andExpect(jsonPath("$.alerts[0].observedValue").value(-20.0))
        .andExpect(jsonPath("$.summary.total").value(1));

    http.perform(get("/reporting/profit-alert/rules").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(5));

    http.perform(
            get("/reporting/profit-alert/capabilities").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.upstreamConnected").value(true))
        .andExpect(jsonPath("$.notDelivered").isArray());

    http.perform(
            post("/reporting/profit-alert/notifications")
                .header("Authorization", "Bearer tenant-a")
                .param("from", DAY.toString())
                .param("to", DAY.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.created").value(1));

    http.perform(
            get("/reporting/profit-alert/notifications").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1));
  }

  @Test
  void 断言16_非法参数给一句能展示的话() throws Exception {
    http.perform(
            get("/reporting/profit-alert/alerts")
                .header("Authorization", "Bearer tenant-a")
                .param("alertType", "NOT-A-TYPE"))
        .andExpect(status().isBadRequest());

    http.perform(
            get("/reporting/profit-alert/alerts")
                .header("Authorization", "Bearer tenant-a")
                .param("scopeType", "NOT-A-SCOPE"))
        .andExpect(status().isBadRequest());

    http.perform(
            get("/reporting/profit-alert/alerts")
                .header("Authorization", "Bearer tenant-a")
                .param("from", DAY.toString())
                .param("to", DAY.minusDays(1).toString()))
        .andExpect(status().isBadRequest());

    http.perform(get("/reporting/profit-alert/alerts")).andExpect(status().isUnauthorized());
  }
}
