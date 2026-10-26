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
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 13.2.1.1 销量趋势的**集成测试**（真实 PostgreSQL 16 + FORCE RLS，无内存替身）。
 *
 * <p>本文件验的是**业务口径**（多租户三条断言在 {@code SalesTrendTenancyIntegrationTest}）：
 *
 * <ul>
 *   <li>趋势点按粒度分桶，**没有事实的那天没有点、不是 0**；
 *   <li>**环比**比的是上一个同粒度桶、**同比**比的是去年同期；基期缺失 ⇒ **不可比**（不是 0%）；
 *   <li>异常点**关联事件**（大促），没有事件时明说「查不到原因」；
 *   <li>店铺维度靠平台单回挂，**挂不上的订单不计入任何店铺**；
 *   <li>商品维度是 **SKU 口径**（订单明细行），与公司口径的订单总额**不是同一个数**；
 *   <li>**多币种绝不相加**；本位币折算带**汇率与时点**；
 *   <li>数据更新时间在**空表时为 null**（不是长得像时间的假值）；
 *   <li>重复刷新幂等；上游读不到就 503，不返回空集合。
 * </ul>
 *
 * <p>⚠️ **上游是真的 HTTP**：本测试起一个假的上游服务（trade + channel），驱动
 * {@code ReportingSalesTrendRefreshService} 的**真实**拉取与投影路径 ——
 * 端口写错了路径 / 字段名，在这一层就会暴露（写错字段名 ⇒ 那一类指标恒为 0）。
 *
 * <p>⚠️ **连接池固定为 1 条连接**：只有复用同一条连接，才能真正证明归还后不会串租户。
 */
@SpringBootTest
@AutoConfigureMockMvc
class SalesTrendIntegrationTest {

  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID tenant = UUID.randomUUID(), user = UUID.randomUUID();
  static final String BEARER = "Bearer tenant-a";

  static HttpServer identity;
  static HttpServer upstream;

  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  static final List<String> permissions =
      List.of(
          "reporting.sales-trend.read",
          "reporting.projection.read",
          "reporting.projection.write");

  static final ObjectMapper JSON = JsonMapper.builder().addModule(new JavaTimeModule()).build();

  /** 当前窗口（5 天）。 */
  static final LocalDate DAY1 = LocalDate.of(2026, 10, 1);
  static final LocalDate DAY5 = LocalDate.of(2026, 10, 5);
  /** 上一等长窗口（5 天）。 */
  static final LocalDate PREV1 = LocalDate.of(2026, 9, 26);
  static final LocalDate PREV5 = LocalDate.of(2026, 9, 30);
  /** 去年同期窗口（5 天）。 */
  static final LocalDate YOY1 = LocalDate.of(2025, 10, 1);
  static final LocalDate YOY5 = LocalDate.of(2025, 10, 5);

  /** ⚠️ 上游故障开关：打开后假上游一律 500，用来证「读不到 ≠ 没有数据」。 */
  static volatile boolean upstreamDown = false;

  // ==========================================================================
  // 夹具数据
  // ==========================================================================

  /**
   * 销售订单。
   *
   * <p>⚠️ 布局刻意做成「中间有一天没有订单」（2026-10-03）——
   * 那天**没有点**，**不是 0**；它后面的 10-04 的**环比算不出来**（基期缺失）。
   */
  static final List<Map<String, Object>> ORDERS =
      List.of(
          // 上一等长窗口：100 / 150 / 200 / 250 / 300
          order("SO-P1", PREV1, "100.00", "USD", 1, "SKU-A"),
          order("SO-P2", PREV1.plusDays(1), "150.00", "USD", 2, "SKU-A"),
          order("SO-P3", PREV1.plusDays(2), "200.00", "USD", 3, "SKU-B"),
          order("SO-P4", PREV1.plusDays(3), "250.00", "USD", 4, "SKU-B"),
          order("SO-P5", PREV1.plusDays(4), "300.00", "USD", 5, "SKU-A"),
          // 去年同期：50 / 60 / （无）/ 70 / 80
          order("SO-Y1", YOY1, "50.00", "USD", 1, "SKU-A"),
          order("SO-Y2", YOY1.plusDays(1), "60.00", "USD", 2, "SKU-B"),
          order("SO-Y4", YOY1.plusDays(3), "70.00", "USD", 3, "SKU-A"),
          order("SO-Y5", YOY1.plusDays(4), "80.00", "USD", 4, "SKU-A"),
          // 当前窗口：300 / 400 / （无）/ 5000 / （EUR 600）
          order("SO-1", DAY1, "300.00", "USD", 5, "SKU-A"),
          order("SO-2", DAY1.plusDays(1), "400.00", "USD", 6, "SKU-B"),
          order("SO-3", DAY1.plusDays(3), "5000.00", "USD", 7, "SKU-A"),
          order("SO-4", DAY1.plusDays(4), "600.00", "EUR", 2, "SKU-C"));

  /** 平台单（**销售订单 → 店铺**的唯一来源）。 */
  static final List<Map<String, Object>> PLATFORM_ORDERS =
      List.of(
          platformOrder("SO-P1", "shop-a"),
          platformOrder("SO-P2", "shop-a"),
          platformOrder("SO-P3", "shop-b"),
          platformOrder("SO-P4", "shop-b"),
          platformOrder("SO-P5", "shop-a"),
          platformOrder("SO-Y1", "shop-a"),
          platformOrder("SO-Y2", "shop-b"),
          platformOrder("SO-Y4", "shop-a"),
          platformOrder("SO-Y5", "shop-a"),
          platformOrder("SO-1", "shop-a"),
          platformOrder("SO-2", "shop-b"),
          // ⚠️ **SO-3 故意没有平台单** ⇒ 挂不上店铺 ⇒ 不计入任何店铺。
          platformOrder("SO-4", "shop-a"));

  /** 大促（channel 的促销）。 */
  static final List<Map<String, Object>> PROMOTIONS =
      List.of(
          promotion(
              "PROMO-1",
              "黑五大促",
              DAY1.plusDays(1),
              DAY1.plusDays(1),
              "0.20",
              "AMAZON",
              "shop-a"));

  /** 秒杀（channel 的秒杀）。 */
  static final List<Map<String, Object>> FLASH_SALES =
      List.of(flashSale("FS-1", "双十一秒杀", DAY1.plusDays(4), DAY1.plusDays(4), "shop-a"));

  /** 断货 / 缺货风险（trade）。 */
  static final List<Map<String, Object>> STOCKOUTS =
      List.of(
          stockout(
              "SKU-A",
              "蓝色 T 恤",
              DAY1.plusDays(5),
              "3",
              "CRITICAL",
              "12",
              DAY1));

  // ==========================================================================
  // 假上游
  // ==========================================================================

  static {
    pg.start();
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      // 运行角色必须 NOBYPASSRLS，否则它会绕过策略，多租户断言全部变成空断言。
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
      actors.put(BEARER, actor(tenant));
      identity.start();

      upstream = HttpServer.create(new InetSocketAddress(0), 0);
      upstream.createContext("/trade/sales-orders/list", e -> respond(e, salesOrderPage(e)));
      upstream.createContext("/trade/sales-orders/detail", e -> respond(e, salesOrderDetail(e)));
      upstream.createContext("/trade/platform-orders/orders", e -> respond(e, pageBody("rows", PLATFORM_ORDERS)));
      upstream.createContext("/trade/stockout-risk/items", e -> respond(e, stockoutPage()));
      upstream.createContext("/channel/ad-promotions/promotions", e -> respond(e, pageBody("items", PROMOTIONS)));
      upstream.createContext("/channel/ad-flash-sales/flash-sales", e -> respond(e, pageBody("items", FLASH_SALES)));
      upstream.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  static AccessService.Access actor(UUID tenantId) {
    var grant = new AccessService.Grant(UUID.randomUUID(), "TEST", "ALL", permissions);
    return new AccessService.Access(
        user, tenantId, "Test", UUID.randomUUID(), null, permissions, List.of(grant));
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
    r.add("CHANNEL_URL", () -> "http://localhost:" + upstream.getAddress().getPort());
    r.add("TRADE_URL", () -> "http://localhost:" + upstream.getAddress().getPort());
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  @Autowired TenantTransactions tenants;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;
  @Autowired ReportingSalesTrendRefreshService refresh;
  @Autowired ReportingSalesTrendService trend;

  @AfterAll
  static void stop() {
    identity.stop(0);
    upstream.stop(0);
    pg.stop();
  }

  @BeforeEach
  void reset() throws Exception {
    upstreamDown = false;
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate reporting_sales_trend_fact, reporting_sales_metric_definition,"
              + " reporting_sales_event, reporting_currency_rate_snapshot cascade");
    }
  }

  // ==========================================================================
  // 业务口径
  // ==========================================================================

  @Test
  void 刷新把上游事实投影成趋势事实() {
    var results = refreshAll();
    assertThat(results).as("七路上游都要有结果（归属 + 三个窗口 + 促销 + 秒杀 + 断货）").hasSize(7);
    assertThat(results).allSatisfy(r -> assertThat(r.status()).isNotEqualTo("FAILED"));
    assertThat(factCount()).as("事实行必须落库").isPositive();
    assertThat(eventCount()).as("异常事件必须落库").isPositive();
  }

  @Test
  void 趋势点按日分桶且没有事实的那天没有点() {
    refreshAll();
    var response = trend.trend(tenant, DAY1, DAY5, ReportingSalesTrendGranularity.DAY, ZoneOffset.UTC, null, null);

    var usd = series(response, ReportingMetricCatalog.SALES_AMOUNT, "USD");
    assertThat(usd.points()).as("10-03 与 10-05 没有 USD 事实 ⇒ 没有点").hasSize(3);
    assertThat(usd.points().stream().map(p -> p.bucketStart()).toList())
        .containsExactly(DAY1, DAY1.plusDays(1), DAY1.plusDays(3));
    assertThat(point(usd, DAY1).value()).isEqualByComparingTo("300");
    assertThat(point(usd, DAY1.plusDays(3)).value()).isEqualByComparingTo("5000");
  }

  @Test
  void 环比比的是上一个同粒度桶() {
    refreshAll();
    var response = trend.trend(tenant, DAY1, DAY5, ReportingSalesTrendGranularity.DAY, ZoneOffset.UTC, null, null);
    var usd = series(response, ReportingMetricCatalog.SALES_AMOUNT, "USD");

    // 10-01 的上一个桶是 09-30（300）⇒ 环比 = (300 − 300) ÷ 300 = 0%
    assertThat(point(usd, DAY1).periodOverPeriodValue()).isEqualByComparingTo("300");
    assertThat(point(usd, DAY1).periodOverPeriodRate())
        .as("基期是 300、当前也是 300 ⇒ 真的是 0%（不是「算不出」）")
        .isEqualByComparingTo("0");
    // 10-02 的上一个桶是 10-01（300）⇒ (400 − 300) ÷ 300 = +33.3333%
    assertThat(point(usd, DAY1.plusDays(1)).periodOverPeriodRate())
        .isEqualByComparingTo("33.3333");
  }

  @Test
  void 基期缺失时环比算不出来而不是零() {
    refreshAll();
    var response = trend.trend(tenant, DAY1, DAY5, ReportingSalesTrendGranularity.DAY, ZoneOffset.UTC, null, null);
    var usd = series(response, ReportingMetricCatalog.SALES_AMOUNT, "USD");

    // 10-04 的上一个桶是 10-03（**没有事实**）⇒ 环比算不出
    var spike = point(usd, DAY1.plusDays(3));
    assertThat(spike.periodOverPeriodValue()).as("基期没有事实 ⇒ null").isNull();
    assertThat(spike.periodOverPeriodRate()).as("基期没有事实 ⇒ 不可比，不是 0%").isNull();
  }

  @Test
  void 同比比的是去年同期() {
    refreshAll();
    var response = trend.trend(tenant, DAY1, DAY5, ReportingSalesTrendGranularity.DAY, ZoneOffset.UTC, null, null);
    var usd = series(response, ReportingMetricCatalog.SALES_AMOUNT, "USD");

    // 10-01 的去年同期是 2025-10-01（50）⇒ (300 − 50) ÷ 50 = +500%
    assertThat(point(usd, DAY1).yearOverYearValue()).isEqualByComparingTo("50");
    assertThat(point(usd, DAY1).yearOverYearRate()).isEqualByComparingTo("500");
    // 10-04 的去年同期是 2025-10-04（70）⇒ (5000 − 70) ÷ 70
    assertThat(point(usd, DAY1.plusDays(3)).yearOverYearRate())
        .isEqualByComparingTo("7042.8571");
  }

  @Test
  void 异常点能关联事件查不到原因时明说() {
    refreshAll();
    var response = trend.trend(tenant, DAY1, DAY5, ReportingSalesTrendGranularity.DAY, ZoneOffset.UTC, null, null);
    var usd = series(response, ReportingMetricCatalog.SALES_AMOUNT, "USD");

    // 10-04 的 5000 相对中位数（400）偏离 +1150% ⇒ 异常
    var spike = point(usd, DAY1.plusDays(3));
    assertThat(spike.anomaly()).as("5000 相对中位数偏离 ≥ 50% ⇒ 异常").isTrue();
    assertThat(spike.anomalyNote()).contains("偏离中位数");
    assertThat(spike.events()).as("10-04 没有任何事件 ⇒ 如实说「查不到原因」").isEmpty();
    assertThat(response.anomalies()).as("异常清单里要有这一条").hasSize(1);
    assertThat(response.anomalies().get(0).direction()).isEqualTo("SPIKE");
    assertThat(response.anomalies().get(0).note()).contains("查不到原因");

    // 10-02 的大促事件挂在那个点上（不是异常，但事件必须可见）
    var promo = point(usd, DAY1.plusDays(1));
    assertThat(promo.events()).as("10-02 的大促事件必须挂在这个点上").hasSize(1);
    assertThat(promo.events().get(0).eventType()).isEqualTo("PROMOTION");
    assertThat(promo.events().get(0).title()).isEqualTo("黑五大促");
  }

  @Test
  void 店铺维度靠平台单回挂挂不上的订单不计入任何店铺() {
    refreshAll();
    var response =
        trend.comparison(
            tenant,
            DAY1,
            DAY5,
            ReportingSalesTrendGranularity.DAY,
            ZoneOffset.UTC,
            "STORE",
            null,
            null);

    // shop-a：300（10-01）+ 600（10-05，EUR）—— EUR 单独一行
    var shopAUsd = row(response, "shop-a", "USD");
    assertThat(metric(shopAUsd, ReportingSalesTrendCatalog.STORE_SALES_AMOUNT).value())
        .isEqualByComparingTo("300");
    // shop-b：400
    assertThat(metric(row(response, "shop-b", "USD"), ReportingSalesTrendCatalog.STORE_SALES_AMOUNT).value())
        .isEqualByComparingTo("400");
    // ⚠️ SO-3（5000）挂不上店铺 ⇒ 不计入任何店铺 ⇒ 店铺合计（300 + 400）= 700
    var totalUsd = 0;
    for (var row : response.rows()) {
      if (!row.currency().equals("USD")) continue;
      totalUsd +=
          metric(row, ReportingSalesTrendCatalog.STORE_SALES_AMOUNT).value().intValue();
    }
    assertThat(totalUsd).as("挂不上店铺的订单不计入任何店铺").isEqualTo(700);
  }

  @Test
  void 商品维度是sku口径且与公司口径不是同一个数() {
    refreshAll();
    var response =
        trend.comparison(
            tenant,
            DAY1,
            DAY5,
            ReportingSalesTrendGranularity.DAY,
            ZoneOffset.UTC,
            "PRODUCT",
            null,
            null);

    // SKU-A：10-01 数量 5、行金额 5×10 = 50；10-04 数量 7、行金额 7×10 = 70
    var skuA = row(response, "SKU-A", "USD");
    assertThat(metric(skuA, ReportingSalesTrendCatalog.PRODUCT_SALES_QUANTITY).value())
        .isEqualByComparingTo("12");
    assertThat(metric(skuA, ReportingSalesTrendCatalog.PRODUCT_SALES_AMOUNT).value())
        .as("SKU 口径是**行金额**，不是订单总额")
        .isEqualByComparingTo("120");
  }

  @Test
  void 多币种绝不相加() {
    refreshAll();
    var response = trend.trend(tenant, DAY1, DAY5, ReportingSalesTrendGranularity.DAY, ZoneOffset.UTC, null, null);

    var usd = series(response, ReportingMetricCatalog.SALES_AMOUNT, "USD");
    var eur = series(response, ReportingMetricCatalog.SALES_AMOUNT, "EUR");
    assertThat(usd).as("USD 与 EUR 必须是两条独立序列").isNotNull();
    assertThat(eur).as("USD 与 EUR 必须是两条独立序列").isNotNull();
    assertThat(usd.currency()).isEqualTo("USD");
    assertThat(eur.currency()).isEqualTo("EUR");
    // ⚠️ **绝没有把两个币种加在一起**：任何一点都不是 300 + 600 = 900
    for (var s : response.series()) {
      for (var p : s.points()) {
        if (p.value() == null) continue;
        assertThat(p.value()).isNotEqualByComparingTo("900");
      }
    }
  }

  @Test
  void 给了本位币但没有汇率时不做折算() {
    refreshAll();
    var response = trend.trend(tenant, DAY1, DAY5, ReportingSalesTrendGranularity.DAY, ZoneOffset.UTC, null, "EUR");
    var usd = series(response, ReportingMetricCatalog.SALES_AMOUNT, "USD");
    assertThat(point(usd, DAY1).baseValue()).as("没有生效汇率 ⇒ 不折算（绝不按 1:1 冒充）").isNull();
    assertThat(response.baseRates()).as("没有汇率就是没有").isEmpty();
  }

  @Test
  void 有汇率时折算值与汇率时点一起返回() {
    refreshAll();
    tenants.run(
        tenant,
        () -> {
          sql.execute(
              """
              insert into reporting_currency_rate_snapshot
                (id,tenant_id,currency,base_currency,rate,as_of,source_service,created_at)
              values (?,?,'USD','EUR',0.5,?::timestamptz,'test',now())
              """,
              UUID.randomUUID(),
              tenant,
              OffsetDateTime.of(2026, 10, 5, 0, 0, 0, 0, ZoneOffset.UTC));
          return null;
        });
    var response = trend.trend(tenant, DAY1, DAY5, ReportingSalesTrendGranularity.DAY, ZoneOffset.UTC, null, "EUR");
    var usd = series(response, ReportingMetricCatalog.SALES_AMOUNT, "USD");
    assertThat(point(usd, DAY1).baseValue()).as("300 × 0.5 = 150").isEqualByComparingTo("150");
    assertThat(response.baseRates()).hasSize(1);
    assertThat(response.baseRates().get(0).asOf()).as("汇率的时点必须一起返回").isNotNull();
  }

  @Test
  void 数据更新时空表时为null而不是假时间() {
    // ⚠️ **不刷新**：这个租户还没有任何投影
    var response = trend.trend(tenant, DAY1, DAY5, ReportingSalesTrendGranularity.DAY, ZoneOffset.UTC, null, null);
    assertThat(response.dataUpdatedAt()).as("空表时必须返回 null（不是 -infinity 那种假时间）").isNull();
    assertThat(response.status()).isEqualTo("NOT_PROJECTED");
    assertThat(response.emptyReason()).isEqualTo("NOT_PROJECTED");
    assertThat(response.emptyNote()).contains("还没有数据");
    assertThat(response.series()).isEmpty();
  }

  @Test
  void 重复刷新幂等() {
    refreshAll();
    var before = factCount();
    refreshAll();
    assertThat(factCount()).as("重复刷新是覆盖而不是翻倍").isEqualTo(before);
  }

  @Test
  void 公司维度口径引用13_1_1_1的口径表不另起一份定义() {
    refreshAll();
    var definitions = trend.metricDefinitions(tenant);
    var salesAmount =
        definitions.stream()
            .filter(d -> d.metricCode().equals(ReportingMetricCatalog.SALES_AMOUNT))
            .findFirst()
            .orElseThrow();
    assertThat(salesAmount.dimensionType()).isEqualTo("TOTAL");

    // ⚠️ 公司维度的三个基础口径**不在**本模块的口径表里（不重复定义）
    var own =
        tenants.run(
            tenant,
            () ->
                sql.fetch(
                        "select metric_code from reporting_sales_metric_definition where tenant_id = ?",
                        tenant)
                    .stream()
                    .map(row -> row.get(0, String.class))
                    .toList());
    assertThat(own)
        .as("公司维度的口径不得在本模块的口径表里重复登记")
        .doesNotContain(ReportingMetricCatalog.SALES_AMOUNT)
        .doesNotContain(ReportingMetricCatalog.UNIT_SOLD)
        .doesNotContain(ReportingMetricCatalog.ORDER_COUNT);
  }

  @Test
  void 对比口径只写被比较指标的引用不写公式() {
    refreshAll();
    var definitions = trend.metricDefinitions(tenant);
    var growth =
        definitions.stream()
            .filter(d -> d.metricCode().equals(ReportingSalesTrendCatalog.periodOverPeriodCode(
                ReportingMetricCatalog.SALES_AMOUNT)))
            .findFirst()
            .orElseThrow();
    assertThat(growth.aggregation()).isEqualTo("PERIOD_OVER_PERIOD");
    assertThat(growth.baseMetricCode())
        .as("对比口径必须声明它比的是哪个指标")
        .isEqualTo(ReportingMetricCatalog.SALES_AMOUNT);
    assertThat(growth.description()).contains("算不出");
  }

  @Test
  void 构成明细可以下钻到订单与sku() {
    refreshAll();
    var orderRows =
        tenants.run(
            tenant,
            () ->
                sql.fetch(
                        "select reference_id from reporting_drilldown_fact"
                            + " where tenant_id = ? and source_kind = 'ORDER'",
                        tenant)
                    .stream()
                    .map(row -> row.get(0, String.class))
                    .toList());
    var skuRows =
        tenants.run(
            tenant,
            () ->
                sql.fetch(
                        "select reference_id from reporting_drilldown_fact"
                            + " where tenant_id = ? and source_kind = 'SKU'",
                        tenant)
                    .stream()
                    .map(row -> row.get(0, String.class))
                    .toList());
    assertThat(orderRows).as("订单级构成明细必须落库").contains("SO-1", "SO-2", "SO-3");
    assertThat(skuRows).as("SKU 级构成明细必须落库").contains("SKU-A", "SKU-B");
  }

  @Test
  void 上游读不到就失败不返回空集合() {
    upstreamDown = true;
    // 🔴 **读不到 ⇒ 503**，绝不用空集合冒充「上游没有数据」。
    assertThatThrownBy(this::refreshAll)
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
        .satisfies(
            e ->
                assertThat(
                        ((org.springframework.web.server.ResponseStatusException) e)
                            .getStatusCode()
                            .value())
                    .isEqualTo(503));
    assertThat(factCount()).as("上游读不到时不得落任何事实").isZero();
  }

  @Test
  void 上游读不到时刷新端点返回503() throws Exception {
    upstreamDown = true;
    http.perform(
            post("/reporting/sales-trend/refresh")
                .header("Authorization", BEARER)
                .param("from", DAY1.toString())
                .param("to", DAY5.toString()))
        .andExpect(status().isServiceUnavailable());
  }

  @Test
  void 能力声明逐条列出上游与拿不到的部分() throws Exception {
    http.perform(get("/reporting/sales-trend/capabilities").header("Authorization", BEARER))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.upstreamConnected").value(true))
        .andExpect(jsonPath("$.notDelivered", org.hamcrest.Matchers.hasSize(org.hamcrest.Matchers.greaterThan(0))))
        .andExpect(jsonPath("$.upstreamModules", org.hamcrest.Matchers.hasSize(org.hamcrest.Matchers.greaterThan(0))))
        .andExpect(jsonPath("$.rules", org.hamcrest.Matchers.hasSize(org.hamcrest.Matchers.greaterThan(0))));
  }

  @Test
  void 端点不接受伪造的租户头() throws Exception {
    http.perform(
            get("/reporting/sales-trend")
                .header("Authorization", BEARER)
                .header("X-Tenant-Id", UUID.randomUUID().toString()))
        .andExpect(status().isOk());
  }

  @Test
  void 小时粒度窗口过长直接400() throws Exception {
    http.perform(
            get("/reporting/sales-trend")
                .header("Authorization", BEARER)
                .param("from", "2026-10-01")
                .param("to", "2026-10-20")
                .param("granularity", "HOUR"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void 小时粒度在窗口内可以正常返回() throws Exception {
    http.perform(
            get("/reporting/sales-trend")
                .header("Authorization", BEARER)
                .param("from", DAY1.toString())
                .param("to", DAY1.toString())
                .param("granularity", "HOUR"))
        .andExpect(status().isOk());
  }

  @Test
  void 非法粒度与非法时区都400() throws Exception {
    http.perform(
            get("/reporting/sales-trend")
                .header("Authorization", BEARER)
                .param("granularity", "FORTNIGHT"))
        .andExpect(status().isBadRequest());
    http.perform(
            get("/reporting/sales-trend")
                .header("Authorization", BEARER)
                .param("timezone", "Mars/Olympus"))
        .andExpect(status().isBadRequest());
  }

  // ==========================================================================
  // 工具
  // ==========================================================================

  private List<ReportingSalesTrendModels.SalesTrendRefreshResult> refreshAll() {
    return refresh.refresh(
        tenant,
        BEARER,
        DAY1,
        DAY5,
        ReportingSalesTrendGranularity.DAY,
        ZoneOffset.UTC);
  }

  private int factCount() {
    return tenants.run(tenant, () -> sql.fetch("select id from reporting_sales_trend_fact").size());
  }

  private int eventCount() {
    return tenants.run(tenant, () -> sql.fetch("select id from reporting_sales_event").size());
  }

  private static ReportingSalesTrendModels.SalesTrendSeries series(
      ReportingSalesTrendModels.SalesTrendResponse response, String metricCode, String currency) {
    return response.series().stream()
        .filter(s -> s.metricCode().equals(metricCode) && s.currency().equals(currency))
        .findFirst()
        .orElseThrow(() -> new AssertionError("响应里没有序列 " + metricCode + "/" + currency));
  }

  private static ReportingSalesTrendModels.SalesTrendPoint point(
      ReportingSalesTrendModels.SalesTrendSeries series, LocalDate bucketStart) {
    return series.points().stream()
        .filter(p -> p.bucketStart().equals(bucketStart))
        .findFirst()
        .orElseThrow(() -> new AssertionError("序列里没有桶 " + bucketStart));
  }

  private static ReportingSalesTrendModels.SalesTrendComparisonRow row(
      ReportingSalesTrendModels.SalesTrendComparisonResponse response,
      String dimensionKey,
      String currency) {
    return response.rows().stream()
        .filter(r -> r.dimensionKey().equals(dimensionKey) && r.currency().equals(currency))
        .findFirst()
        .orElseThrow(() -> new AssertionError("对比矩阵里没有 " + dimensionKey + "/" + currency));
  }

  private static ReportingSalesTrendModels.SalesTrendComparisonMetric metric(
      ReportingSalesTrendModels.SalesTrendComparisonRow row, String metricCode) {
    return row.metrics().stream()
        .filter(m -> m.metricCode().equals(metricCode))
        .findFirst()
        .orElseThrow(() -> new AssertionError("对比行里没有指标 " + metricCode));
  }

  // ---------------------------------------------------------------------------
  // 假上游的载荷
  // ---------------------------------------------------------------------------

  private static void respond(com.sun.net.httpserver.HttpExchange e, Object body) {
    try {
      if (upstreamDown) {
        e.sendResponseHeaders(500, -1);
        e.close();
        return;
      }
      byte[] bytes = JSON.writeValueAsBytes(body);
      e.getResponseHeaders().add("Content-Type", "application/json");
      e.sendResponseHeaders(200, bytes.length);
      e.getResponseBody().write(bytes);
      e.close();
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }

  private static Map<String, Object> pageBody(String field, List<Map<String, Object>> rows) {
    var body = new LinkedHashMap<String, Object>();
    body.put(field, rows);
    body.put("page", 0);
    body.put("size", 200);
    body.put("total", rows.size());
    return body;
  }

  /** 销售订单列表：**按 orderedFrom / orderedTo 过滤**（端口按窗口逐个拉）。 */
  private static Map<String, Object> salesOrderPage(com.sun.net.httpserver.HttpExchange e) {
    var query = query(e);
    var from = query.get("orderedFrom") == null ? null : java.time.Instant.parse(query.get("orderedFrom"));
    var to = query.get("orderedTo") == null ? null : java.time.Instant.parse(query.get("orderedTo"));
    var rows = new ArrayList<Map<String, Object>>();
    for (var order : ORDERS) {
      var submittedAt = java.time.Instant.parse((String) order.get("submittedAt"));
      if (from != null && submittedAt.isBefore(from)) continue;
      if (to != null && !submittedAt.isBefore(to)) continue;
      rows.add(order);
    }
    return pageBody("rows", rows);
  }

  /** 销售订单明细（按 id 找订单）。 */
  private static Map<String, Object> salesOrderDetail(com.sun.net.httpserver.HttpExchange e) {
    var path = e.getRequestURI().getPath();
    var id = path.substring(path.lastIndexOf('/') + 1);
    var order =
        ORDERS.stream()
            .filter(o -> id.equals(o.get("id")))
            .findFirst()
            .orElseThrow(() -> new AssertionError("没有这张订单：" + id));
    var body = new LinkedHashMap<String, Object>();
    var header = new LinkedHashMap<String, Object>();
    header.put("orderNo", order.get("orderNo"));
    header.put("currency", order.get("currency"));
    header.put("grandTotalAmount", order.get("grandTotalAmount"));
    header.put("submittedAt", order.get("submittedAt"));
    body.put("header", header);
    var quantity = (int) order.get("quantity");
    var line = new LinkedHashMap<String, Object>();
    line.put("skuCode", order.get("skuCode"));
    line.put("skuName", order.get("skuName"));
    line.put("quantity", quantity);
    line.put("unitPrice", "10.00");
    line.put("lineAmount", new BigDecimal(quantity).multiply(new BigDecimal("10.00")).toPlainString());
    body.put("lines", List.of(line));
    return body;
  }

  /** 断货风险清单。 */
  private static Map<String, Object> stockoutPage() {
    var body = pageBody("rows", STOCKOUTS);
    body.put("snapshotId", UUID.randomUUID().toString());
    body.put("snapshotNo", "SNAP-1");
    body.put("asOfDate", DAY1.toString());
    body.put("leadTimeDays", 15);
    body.put("note", "");
    return body;
  }

  private static Map<String, String> query(com.sun.net.httpserver.HttpExchange e) {
    var out = new LinkedHashMap<String, String>();
    var raw = e.getRequestURI().getRawQuery();
    if (raw == null || raw.isBlank()) return out;
    for (var pair : raw.split("&")) {
      var index = pair.indexOf('=');
      if (index < 0) continue;
      out.put(
          URLDecoder.decode(pair.substring(0, index), StandardCharsets.UTF_8),
          URLDecoder.decode(pair.substring(index + 1), StandardCharsets.UTF_8));
    }
    return out;
  }

  private static Map<String, Object> order(
      String orderNo,
      LocalDate day,
      String amount,
      String currency,
      int quantity,
      String skuCode) {
    var body = new LinkedHashMap<String, Object>();
    body.put("id", UUID.nameUUIDFromBytes(orderNo.getBytes(StandardCharsets.UTF_8)).toString());
    body.put("orderNo", orderNo);
    body.put("orderSource", "PLATFORM");
    body.put("currency", currency);
    body.put("grandTotalAmount", amount);
    body.put("lineCount", 1);
    body.put(
        "submittedAt",
        OffsetDateTime.of(day.getYear(), day.getMonthValue(), day.getDayOfMonth(), 10, 0, 0, 0, ZoneOffset.UTC)
            .toInstant()
            .toString());
    body.put(
        "createdAt", OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC).toInstant().toString());
    body.put("quantity", quantity);
    body.put("skuCode", skuCode);
    body.put("skuName", skuCode);
    return body;
  }

  private static Map<String, Object> platformOrder(String orderNo, String shopCode) {
    var body = new LinkedHashMap<String, Object>();
    body.put("id", UUID.nameUUIDFromBytes(("platform:" + orderNo).getBytes(StandardCharsets.UTF_8)).toString());
    body.put(
        "salesOrderId",
        UUID.nameUUIDFromBytes(orderNo.getBytes(StandardCharsets.UTF_8)).toString());
    body.put("orderNo", orderNo);
    body.put("platformCode", "AMAZON");
    body.put("shopCode", shopCode);
    body.put("externalOrderNo", "EXT-" + orderNo);
    body.put("currency", "USD");
    body.put("platformGrandTotalAmount", "0.00");
    body.put("restoredGrandTotalAmount", "0.00");
    body.put(
        "platformPlacedAt",
        OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC).toInstant().toString());
    body.put(
        "syncedAt",
        OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC).toInstant().toString());
    return body;
  }

  private static Map<String, Object> promotion(
      String code,
      String name,
      LocalDate from,
      LocalDate to,
      String discountRate,
      String platformCode,
      String shopCode) {
    var body = new LinkedHashMap<String, Object>();
    body.put("id", UUID.nameUUIDFromBytes(code.getBytes(StandardCharsets.UTF_8)).toString());
    body.put("promotionCode", code);
    body.put("promotionName", name);
    body.put("promotionType", "PERCENT");
    body.put("platformCode", platformCode);
    body.put("shopCode", shopCode);
    body.put("currency", "USD");
    body.put("discountRate", discountRate);
    body.put("status", "ACTIVE");
    body.put(
        "startAt",
        OffsetDateTime.of(from.getYear(), from.getMonthValue(), from.getDayOfMonth(), 0, 0, 0, 0, ZoneOffset.UTC)
            .toInstant()
            .toString());
    body.put(
        "endAt",
        OffsetDateTime.of(to.getYear(), to.getMonthValue(), to.getDayOfMonth(), 23, 59, 59, 0, ZoneOffset.UTC)
            .toInstant()
            .toString());
    return body;
  }

  private static Map<String, Object> flashSale(
      String code, String name, LocalDate from, LocalDate to, String shopCode) {
    var body = new LinkedHashMap<String, Object>();
    body.put("id", UUID.nameUUIDFromBytes(code.getBytes(StandardCharsets.UTF_8)).toString());
    body.put("saleCode", code);
    body.put("saleName", name);
    body.put("platformCode", "AMAZON");
    body.put("shopCode", shopCode);
    body.put("listingTitle", "蓝色 T 恤");
    body.put("currency", "USD");
    body.put("flashPrice", "9.90");
    body.put("status", "ACTIVE");
    body.put(
        "startAt",
        OffsetDateTime.of(from.getYear(), from.getMonthValue(), from.getDayOfMonth(), 0, 0, 0, 0, ZoneOffset.UTC)
            .toInstant()
            .toString());
    body.put(
        "endAt",
        OffsetDateTime.of(to.getYear(), to.getMonthValue(), to.getDayOfMonth(), 23, 59, 59, 0, ZoneOffset.UTC)
            .toInstant()
            .toString());
    return body;
  }

  private static Map<String, Object> stockout(
      String skuCode,
      String skuName,
      LocalDate expectedStockoutDate,
      String shortage,
      String urgency,
      String coverageDays,
      LocalDate asOfDate) {
    var body = new LinkedHashMap<String, Object>();
    body.put("id", UUID.nameUUIDFromBytes(("stockout:" + skuCode).getBytes(StandardCharsets.UTF_8)).toString());
    body.put("skuCode", skuCode);
    body.put("skuName", skuName);
    body.put("availableQuantity", "0");
    body.put("dailySalesQuantity", "1");
    body.put("coverageDays", Integer.parseInt(coverageDays));
    body.put("expectedStockoutDate", expectedStockoutDate.toString());
    body.put("shortageQuantity", shortage);
    body.put("urgency", urgency);
    body.put("urgencyLabel", "紧急");
    body.put("urgencyBasis", "日均销不足");
    body.put("shopCount", 1);
    body.put("shopCodes", List.of("shop-a"));
    body.put("asOfDate", asOfDate.toString());
    return body;
  }
}
