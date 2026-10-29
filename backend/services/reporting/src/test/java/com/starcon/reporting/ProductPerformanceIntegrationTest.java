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
import java.time.ZoneId;
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
 * 13.3.1.1 产品表现分析的**集成测试**（真实 PostgreSQL 16 + FORCE RLS，无内存替身）。
 *
 * <p>本文件验的是**业务口径**（多租户三条断言在 {@code ProductPerformanceTenancyIntegrationTest}）：
 *
 * <ul>
 *   <li>十项指标（销量 / 销售额 / 毛利 / 流量 / 转化 / 退货 / 库存 / 库龄 / 广告 / 竞品）
 *       每一项都真的从**上游授权只读 API**落到库里；
 *   <li>毛利 = 销售额 − 出库成本，**按币种分开**，**不跨币种相加**；
 *   <li>「算不出来」与「等于 0」分开：上游未交付的指标是 {@code NOT_DELIVERED}，不是 0；
 *   <li>环比基线 = 上一个等长周期，**服务端算**；基线没有事实 ⇒ 变化量是 null（不是 0）；
 *   <li>排名分位按**销量**排、同层级没有事实的**不进榜**；
 *   <li>重复刷新幂等；上游读不到就 503，不返回空集合。
 * </ul>
 *
 * <p>⚠️ **上游是真的 HTTP**：本测试起一个假的上游服务（catalog + channel + trade +
 * warehouse + finance），驱动 {@code ReportingProductPerformanceRefreshService} 的**真实**
 * 拉取与投影路径 —— 端口写错了路径 / 字段名，在这一层就会暴露。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ProductPerformanceIntegrationTest {

  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID tenant = UUID.randomUUID(), user = UUID.randomUUID();
  static final String BEARER = "Bearer tenant-a";

  static HttpServer identity;
  static HttpServer upstream;

  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  static final List<String> permissions =
      List.of(
          "reporting.product-performance.read",
          // ⚠️ 写权限必须在**同一个 grant** 里同时命中对应的 .read ——
          //   服务端的判据是「能写不能读」的组合不放行，所以这里必须带上 reporting.projection.read。
          "reporting.projection.read",
          "reporting.projection.write");

  static final ObjectMapper JSON = JsonMapper.builder().addModule(new JavaTimeModule()).build();

  /** 当期窗口（两天）。 */
  static final LocalDate DAY1 = LocalDate.of(2026, 10, 1);
  static final LocalDate DAY2 = LocalDate.of(2026, 10, 2);

  /** 基线窗口（上一个等长周期：09-29 ~ 09-30）。 */
  static final LocalDate BASE1 = LocalDate.of(2026, 9, 29);
  static final LocalDate BASE2 = LocalDate.of(2026, 9, 30);

  static final ZoneId ZONE = ZoneId.of("UTC");

  /** ⚠️ 上游故障开关：打开后假上游一律 500，用来证「读不到 ≠ 没有数据」。 */
  static volatile boolean upstreamDown = false;

  // ==========================================================================
  // 夹具数据
  // ==========================================================================

  static final UUID SPU_A = UUID.randomUUID();
  static final UUID SPU_C = UUID.randomUUID();
  static final UUID SKU_A = UUID.randomUUID();
  static final UUID SKU_B = UUID.randomUUID();
  static final UUID SKU_C = UUID.randomUUID();
  static final UUID LISTING_1 = UUID.randomUUID();
  static final UUID LISTING_2 = UUID.randomUUID();
  static final UUID CAMPAIGN_1 = UUID.randomUUID();
  static final UUID CAMPAIGN_2 = UUID.randomUUID();
  static final UUID COST_RUN = UUID.randomUUID();
  static final UUID FLOW_A = UUID.randomUUID();
  static final UUID FLOW_A_BASE = UUID.randomUUID();
  static final UUID FLOW_B = UUID.randomUUID();
  static final UUID CASE_1 = UUID.randomUUID();

  /** 商品主数据（catalog）。 */
  static final List<Map<String, Object>> SKUS =
      List.of(
          sku(SKU_A, "SKU-A", "红杯 S", SPU_A, "SPU-A"),
          sku(SKU_B, "SKU-B", "红杯 L", SPU_A, "SPU-A"),
          sku(SKU_C, "SKU-C", "蓝杯 S", SPU_C, "SPU-C"));

  /** 平台商品（channel）。🔴 SKU-A 同时挂在两个 Listing 上 ⇒ 它的销量归不了任何 Listing。 */
  static final List<Map<String, Object>> LISTINGS =
      List.of(
          listing(LISTING_1, "shop-a", "AMAZON", "AMAZON-A", List.of(SKU_A)),
          listing(LISTING_2, "shop-b", "TIKTOK", "TT-A", List.of(SKU_A, SKU_B)));

  /** 销售订单（trade）—— 13.2.2.2 投影它们。 */
  static final List<Map<String, Object>> ORDERS =
      List.of(
          // 基线窗口：SKU-A ×1 @ 20.00 USD
          order("SO-0", BASE2, "USD", "SKU-A", "1", "20.00"),
          // 当期窗口：SKU-A ×2 @ 25.00 USD
          order("SO-1", DAY1, "USD", "SKU-A", "2", "50.00"),
          // 当期窗口：SKU-B ×1 @ 30.00 EUR（**跨币种**）
          order("SO-2", DAY2, "EUR", "SKU-B", "1", "30.00"));

  /** 平台表现（channel 4.5.1.1）。 */
  static final List<Map<String, Object>> PERFORMANCE =
      List.of(
          performance(LISTING_1, DAY1, "1000", "50", "40", "3", "75.00", "USD"),
          performance(LISTING_2, DAY1, "500", "20", "15", "1", "20.00", "USD"));

  /** 售后单（trade）。 */
  static final List<Map<String, Object>> CASES =
      List.of(
          Map.of(
              "id", CASE_1.toString(),
              "caseNo", "AS-1",
              "orderNo", "SO-1",
              "status", "COMPLETED",
              "requestedAmount", "25.00",
              "currency", "USD",
              "requestedQuantity", "1",
              "receivedAt", OffsetDateTime.of(2026, 10, 2, 9, 0, 0, 0, ZoneOffset.UTC).toString()));

  /** 售后单明细（trade）：只有 SKU-A 那一行是涉及行。 */
  static final List<Map<String, Object>> CASE_LINES =
      List.of(
          map(
              "id", UUID.randomUUID().toString(),
              "lineNo", 1,
              "skuId", SKU_A.toString(),
              "skuCode", "SKU-A",
              "skuName", "红杯 S",
              "quantity", "2",
              "involved", true,
              "aftersalesQuantity", "1",
              "aftersalesAmount", "25.00"),
          map(
              "id", UUID.randomUUID().toString(),
              "lineNo", 2,
              "skuId", SKU_B.toString(),
              "skuCode", "SKU-B",
              "skuName", "红杯 L",
              "quantity", "1",
              "involved", false,
              "aftersalesQuantity", null,
              "aftersalesAmount", null));

  /** 成本批次（finance 12.4.1.2）。 */
  static final List<Map<String, Object>> COST_RUNS =
      List.of(
          Map.of(
              "id", COST_RUN.toString(),
              "scopeType", "LEGAL_ENTITY",
              "scopeRefId", UUID.randomUUID().toString(),
              "valuationMethod", "MOVING_AVERAGE",
              "currency", "USD",
              "periodFrom", OffsetDateTime.of(2026, 9, 1, 0, 0, 0, 0, ZoneOffset.UTC).toString(),
              "periodTo", OffsetDateTime.of(2026, 10, 31, 0, 0, 0, 0, ZoneOffset.UTC).toString(),
              "status", "COMPLETED",
              "entryCount", 3));

  /** 成本分录（finance 12.4.1.2）。 */
  static final List<Map<String, Object>> COST_ENTRIES =
      List.of(
          costEntry(COST_RUN, FLOW_A_BASE, SKU_A, "SKU-A", BASE2, "10.00", "USD"),
          costEntry(COST_RUN, FLOW_A, SKU_A, "SKU-A", DAY1, "30.00", "USD"),
          costEntry(COST_RUN, FLOW_B, SKU_B, "SKU-B", DAY2, "12.00", "EUR"));

  /** 库存余额（warehouse 7 库存）。 */
  static final List<Map<String, Object>> BALANCES =
      List.of(
          Map.of(
              "balanceId", UUID.randomUUID().toString(),
              "skuId", SKU_A.toString(),
              "skuCode", "SKU-A",
              "skuName", "红杯 S",
              "onHand", "100",
              "reserved", "10",
              "available", "90"),
          Map.of(
              "balanceId", UUID.randomUUID().toString(),
              "skuId", SKU_B.toString(),
              "skuCode", "SKU-B",
              "skuName", "红杯 L",
              "onHand", "20",
              "reserved", "0",
              "available", "20"));

  /** 库龄（warehouse 7 库存库龄）。 */
  static final List<Map<String, Object>> AGING =
      List.of(
          map(
              "bucketId", UUID.randomUUID().toString(),
              "skuId", SKU_A.toString(),
              "skuCode", "SKU-A",
              "skuName", "红杯 S",
              "agingDays", 45,
              "agingBucket", "31-60",
              "agingBucketLabel", "31-60 天",
              "onHand", "100",
              "reserved", "10",
              "available", "90",
              "slowMovingStatus", "SLOW_MOVING"),
          map(
              "bucketId", UUID.randomUUID().toString(),
              "skuId", SKU_B.toString(),
              "skuCode", "SKU-B",
              "skuName", "红杯 L",
              "agingDays", 10,
              "agingBucket", "0-30",
              "agingBucketLabel", "0-30 天",
              "onHand", "20",
              "reserved", "0",
              "available", "20",
              "slowMovingStatus", "NORMAL"));

  /** 广告活动（channel 14.3.1.1）。 */
  static final List<Map<String, Object>> AD_ROWS =
      List.of(
          adRow(CAMPAIGN_1, "活动一", "12.00", "800", "40", "2", "60.00", "USD"),
          adRow(CAMPAIGN_2, "活动二", "8.00", "400", "20", "1", "30.00", "USD"));

  /** 活动 ↔ Listing 绑定（channel 14.1.1.2）。 */
  static final Map<String, List<UUID>> CAMPAIGN_LISTINGS =
      Map.of(CAMPAIGN_1.toString(), List.of(LISTING_1), CAMPAIGN_2.toString(), List.of(LISTING_2));

  /** 跟卖记录（channel 4.5.2.1）。 */
  static final List<Map<String, Object>> HIJACKERS =
      List.of(
          map(
              "recordId", UUID.randomUUID().toString(),
              "watchId", UUID.randomUUID().toString(),
              "listingId", LISTING_1.toString(),
              "externalItemId", "AMAZON-A",
              "hijackerSellerId", "SELLER-1",
              "hijackerShopName", "跟卖小店",
              "hijackerKind", "EXTERNAL",
              "hijackerPrice", "22.00",
              "currency", "USD",
              "referencePrice", "25.00",
              "priceGap", "3.00",
              "priceGapRatio", "0.12",
              "observedAt", OffsetDateTime.of(2026, 10, 2, 12, 0, 0, 0, ZoneOffset.UTC).toString(),
              "dataSource", "PLATFORM_SCRAPED"));

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
      upstream.createContext("/catalog/skus", e -> respond(e, page(SKUS, "items")));
      upstream.createContext("/channel/listings", e -> respond(e, page(LISTINGS, "items")));
      upstream.createContext("/trade/sales-orders/list", e -> respond(e, ordersBody(e)));
      upstream.createContext("/trade/sales-orders/detail", e -> respond(e, detailBody(e)));
      upstream.createContext("/channel/listing-performance", e -> respond(e, page(PERFORMANCE, "items")));
      upstream.createContext("/channel/listing-hijackers/records", e -> respond(e, page(HIJACKERS, "items")));
      upstream.createContext("/channel/ad-reports/rows", e -> respond(e, adRowsBody(e)));
      upstream.createContext("/channel/ad-campaign-details", e -> respond(e, campaignProductsBody(e)));
      upstream.createContext("/warehouse/inventory/overview", e -> respond(e, page(BALANCES, "rows")));
      upstream.createContext("/warehouse/inventory/aging/slow-moving", e -> respond(e, page(AGING, "rows")));
      upstream.createContext("/trade/aftersales/cases", e -> respond(e, casesBody(e)));
      upstream.createContext("/finance/cost-calculation/runs", e -> respond(e, page(COST_RUNS, "items")));
      upstream.createContext("/finance/cost-calculation/entries", e -> respond(e, costEntriesBody(e)));
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
    r.add("CATALOG_URL", () -> "http://localhost:" + upstream.getAddress().getPort());
    r.add("CHANNEL_URL", () -> "http://localhost:" + upstream.getAddress().getPort());
    r.add("TRADE_URL", () -> "http://localhost:" + upstream.getAddress().getPort());
    r.add("WAREHOUSE_URL", () -> "http://localhost:" + upstream.getAddress().getPort());
    r.add("FINANCE_URL", () -> "http://localhost:" + upstream.getAddress().getPort());
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  @Autowired TenantTransactions tenants;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;
  @Autowired ReportingProductSalesRefreshService productSalesRefresh;
  @Autowired ReportingProductPerformanceRefreshService refresh;
  @Autowired ReportingProductPerformanceService performance;

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
          "truncate reporting_product_performance_fact, reporting_product_performance_snapshot,"
              + " reporting_product_performance_metric_definition, reporting_product_metric_fact,"
              + " reporting_product_drilldown_fact, reporting_product_metric_definition,"
              + " reporting_product_listing_sku, reporting_product_dimension cascade");
    }
  }

  // ==========================================================================
  // 业务口径
  // ==========================================================================

  @Test
  void 刷新把十项指标的事实都投影落库() {
    var results = refreshAll();
    assertThat(results).as("七路上游都要有结果").hasSize(7);
    assertThat(results).allSatisfy(r -> assertThat(r.status()).isIn("SUCCEEDED", "TRUNCATED"));
    assertThat(factCount()).as("事实行必须落库").isPositive();
    assertThat(snapshotCount()).as("快照行必须落库").isPositive();
  }

  @Test
  void 销量与销售额来自内部订单口径() {
    refreshAll();
    var response = analyze("SKU", "SKU-A");

    assertThat(metric(response, "PRODUCT_SALES_QUANTITY", ""))
        .as("SKU-A 销量 = 2 件").isEqualByComparingTo("2");
    assertThat(metric(response, "PRODUCT_SALES_AMOUNT", "USD"))
        .as("SKU-A 成交金额 = 50.00 USD").isEqualByComparingTo("50.00");
    // 🔴 SKU-B 是 EUR —— 绝不与 USD 相加。
    var other = analyze("SKU", "SKU-B");
    assertThat(metric(other, "PRODUCT_SALES_AMOUNT", "EUR")).isEqualByComparingTo("30.00");
    assertThat(metric(other, "PRODUCT_SALES_AMOUNT", "USD")).as("EUR 的那一格不能出现 USD").isNull();
  }

  @Test
  void 毛利等于销售额减去出库成本() {
    refreshAll();
    var response = analyze("SKU", "SKU-A");

    assertThat(metric(response, "PRODUCT_COGS_AMOUNT", "USD"))
        .as("出库成本 = 30.00 USD").isEqualByComparingTo("30.00");
    assertThat(metric(response, "PRODUCT_GROSS_PROFIT_AMOUNT", "USD"))
        .as("毛利额 = 50.00 − 30.00 = 20.00").isEqualByComparingTo("20.00");
    assertThat(metric(response, "PRODUCT_GROSS_MARGIN", "USD"))
        .as("毛利率 = 20.00 ÷ 50.00 = 40%").isEqualByComparingTo("40.0000");
  }

  @Test
  void 毛利不跨币种相加() {
    refreshAll();
    // SKU-B：销售额 30.00 EUR，出库成本 12.00 EUR ⇒ 毛利 18.00 EUR。
    var response = analyze("SKU", "SKU-B");
    assertThat(metric(response, "PRODUCT_GROSS_PROFIT_AMOUNT", "EUR")).isEqualByComparingTo("18.00");
    assertThat(metric(response, "PRODUCT_GROSS_PROFIT_AMOUNT", "USD")).as("不能出现 USD 的毛利").isNull();
  }

  @Test
  void 流量与转化来自平台口径() {
    refreshAll();
    var response = analyze("SKU", "SKU-A");

    // SKU-A 挂在两个 Listing 上 ⇒ 曝光 = 1000 + 500 = 1500（**每个 Listing 只算一次**）。
    assertThat(metric(response, "PRODUCT_IMPRESSIONS", ""))
        .as("SKU-A 曝光 = 1000 + 500 = 1500").isEqualByComparingTo("1500");
    assertThat(metric(response, "PRODUCT_CLICKS", "")).isEqualByComparingTo("70");
    assertThat(metric(response, "PRODUCT_SESSIONS", "")).isEqualByComparingTo("55");
    assertThat(metric(response, "PRODUCT_CONVERSION_RATE", ""))
        .as("转化率 = 平台口径销量 4 ÷ Session 55").isEqualByComparingTo("7.2727");
  }

  @Test
  void 退货按SKU归集() {
    refreshAll();
    var response = analyze("SKU", "SKU-A");

    assertThat(metric(response, "PRODUCT_RETURNED_QUANTITY", ""))
        .as("SKU-A 退货 1 件（售后明细里涉及的那一行）").isEqualByComparingTo("1");
    assertThat(metric(response, "PRODUCT_RETURNED_AMOUNT", "USD")).isEqualByComparingTo("25.00");
    assertThat(metric(response, "PRODUCT_RETURN_RATE", ""))
        .as("退货率 = 1 ÷ 2 = 50%").isEqualByComparingTo("50.0000");
    // 未涉及的 SKU 不该有退货。
    assertThat(metric(analyze("SKU", "SKU-B"), "PRODUCT_RETURNED_QUANTITY", "")).isNull();
  }

  @Test
  void 库存与库龄是时点快照() {
    refreshAll();
    var response = analyze("SKU", "SKU-A");

    assertThat(metric(response, "PRODUCT_ON_HAND_QUANTITY", "")).isEqualByComparingTo("100");
    assertThat(metric(response, "PRODUCT_RESERVED_QUANTITY", "")).isEqualByComparingTo("10");
    assertThat(metric(response, "PRODUCT_AVAILABLE_QUANTITY", "")).isEqualByComparingTo("90");
    assertThat(metric(response, "PRODUCT_AGING_DAYS", "")).isEqualByComparingTo("45");
    // 🔴 时点快照没有可比基线。
    var onHand = metricOf(response, "PRODUCT_ON_HAND_QUANTITY", "");
    assertThat(onHand.baselineValue()).as("库存没有可比基线").isNull();
    assertThat(onHand.change()).isNull();
    assertThat(onHand.changeRate()).isNull();
  }

  @Test
  void 广告是活动级_商品维度ACOS不可用() {
    refreshAll();
    var response = analyze("SKU", "SKU-A");

    // SKU-A 覆盖两个 Listing ⇒ 两个活动各算一次。
    assertThat(metric(response, "PRODUCT_AD_SPEND", "USD"))
        .as("广告花费 = 12.00 + 8.00 = 20.00（活动级）").isEqualByComparingTo("20.00");
    assertThat(metric(response, "PRODUCT_AD_IMPRESSIONS", "")).isEqualByComparingTo("1200");

    // 🔴 商品维度 ACOS / ROAS **本轮未交付** ⇒ NOT_DELIVERED（不是 0）。
    var acos = metricOf(response, "PRODUCT_AD_ACOS", "");
    assertThat(acos.availability()).isEqualTo("NOT_DELIVERED");
    assertThat(acos.value()).as("未交付的指标不能有值").isNull();
    assertThat(acos.availabilityNote()).contains("14.3.1.3");
  }

  @Test
  void 竞品对比是参考指标() {
    refreshAll();
    var response = analyze("SKU", "SKU-A");

    var competitors = section(response, "COMPETITION").competitors();
    assertThat(competitors).as("跟卖记录要出现在竞品段").hasSize(1);
    var row = competitors.get(0);
    assertThat(row.hijackerPrice()).isEqualByComparingTo("22.00");
    assertThat(row.referencePrice()).isEqualByComparingTo("25.00");
    assertThat(row.priceGap()).as("价格差 = 25.00 − 22.00 = 3.00").isEqualByComparingTo("3.00");
    assertThat(row.priceGapRatio()).as("价格差比例 = 3.00 ÷ 25.00 = 12%").isEqualByComparingTo("12.0000");
    assertThat(row.sellerKind()).isEqualTo("EXTERNAL");
    // 🔴 断言**写回的值**，不只是「没抛异常」：上游观测时刻走 ?::timestamptz 落进
    //   source_observed_at，再原样读回 —— 跨钟域 / 精度被悄悄改掉时这里会红。
    assertThat(row.observedAt())
        .as("跟卖记录的观测时刻要按 ?::timestamptz 原样写回")
        .isEqualTo(OffsetDateTime.of(2026, 10, 2, 12, 0, 0, 0, ZoneOffset.UTC));
  }

  @Test
  void 环比基线是上一个等长周期且服务端算() {
    refreshAll();
    var response = analyze("SKU", "SKU-A");

    assertThat(response.baselineFrom()).isEqualTo(BASE1);
    assertThat(response.baselineTo()).isEqualTo(BASE2);
    assertThat(response.baselineType()).isEqualTo("PREVIOUS_PERIOD");

    var quantity = metricOf(response, "PRODUCT_SALES_QUANTITY", "");
    assertThat(quantity.baselineValue()).as("基线销量 = 1 件").isEqualByComparingTo("1");
    assertThat(quantity.change()).as("变化量 = 2 − 1 = 1").isEqualByComparingTo("1.0000");
    assertThat(quantity.changeRate()).as("变化率 = 1 ÷ 1 = 100%").isEqualByComparingTo("100.0000");
  }

  @Test
  void 基线没有事实时变化量是null而不是0() {
    refreshAll();
    // SKU-B 在基线窗口没有订单 ⇒ 基线没有事实。
    var response = analyze("SKU", "SKU-B");
    var quantity = metricOf(response, "PRODUCT_SALES_QUANTITY", "");
    assertThat(quantity.value()).isEqualByComparingTo("1");
    assertThat(quantity.baselineValue()).as("基线没有事实 ⇒ null（不是 0）").isNull();
    assertThat(quantity.change()).isNull();
    assertThat(quantity.changeRate()).isNull();
  }

  @Test
  void 排名分位按同层级销量排_没有事实的不进榜() {
    refreshAll();
    var response = analyze("SKU", "SKU-A");

    var ranking = response.ranking();
    assertThat(ranking.rankMetricCode()).isEqualTo("PRODUCT_SALES_QUANTITY");
    // 同层级有事实的 SKU 只有 SKU-A（2 件）与 SKU-B（1 件）⇒ SKU-C（0 件）不进榜。
    assertThat(ranking.total()).as("没有事实的 SKU 不进榜").isEqualTo(2);
    assertThat(ranking.rank()).isEqualTo(1);
    assertThat(ranking.percentile()).as("第一名 = 100%").isEqualByComparingTo("100.0000");
  }

  @Test
  void 趋势逐日一行且没有假数据() {
    refreshAll();
    var response = analyze("SKU", "SKU-A");

    assertThat(response.trend()).as("窗口两天 ⇒ 两个点").hasSize(2);
    var day1 = response.trend().get(0);
    assertThat(day1.date()).isEqualTo(DAY1);
    assertThat(day1.salesQuantity()).isEqualByComparingTo("2");
    assertThat(day1.impressions()).isEqualByComparingTo("1500");
    var day2 = response.trend().get(1);
    assertThat(day2.date()).isEqualTo(DAY2);
    assertThat(day2.salesQuantity()).as("SKU-B 的销量不计入 SKU-A").isNull();
  }

  @Test
  void Listing层的订单侧指标只算可唯一归属的那部分() {
    refreshAll();
    var response = analyze("LISTING", LISTING_1.toString());

    // 🔴 SKU-A 挂在两个 Listing 上 ⇒ 它的销量归不了 LISTING-1 ⇒ 这一格是「没有数据」，不是 0。
    var quantity = metricOf(response, "PRODUCT_SALES_QUANTITY", "");
    assertThat(quantity.value()).as("无法唯一归属 ⇒ 没有数据").isNull();
    assertThat(quantity.availability()).isEqualTo("NO_DATA");
    // 平台侧指标是 Listing 原生的 ⇒ 有值。
    assertThat(metric(response, "PRODUCT_IMPRESSIONS", "")).isEqualByComparingTo("1000");
  }

  @Test
  void 重复刷新幂等() {
    refreshAll();
    var first = analyze("SKU", "SKU-A");
    refreshAll();
    var second = analyze("SKU", "SKU-A");

    assertThat(metric(second, "PRODUCT_SALES_QUANTITY", ""))
        .as("重复刷新不得把销量翻倍").isEqualByComparingTo("2");
    assertThat(metric(second, "PRODUCT_IMPRESSIONS", "")).isEqualByComparingTo("1500");
    assertThat(metric(second, "PRODUCT_SALES_QUANTITY", ""))
        .isEqualByComparingTo(metric(first, "PRODUCT_SALES_QUANTITY", ""));
  }

  @Test
  void 上游读不到就503不返回空集合() {
    upstreamDown = true;
    assertThatThrownBy(() -> refresh.refresh(tenant, BEARER, DAY1, DAY2, BASE1, BASE2, ZONE))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
        .satisfies(
            e ->
                assertThat(((org.springframework.web.server.ResponseStatusException) e).getStatusCode().value())
                    .isEqualTo(503));
  }

  @Test
  void 能力声明写清未交付项与归属模块() throws Exception {
    http.perform(get("/reporting/product-performance/capabilities").header("Authorization", BEARER))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.notDelivered.length()").value(org.hamcrest.Matchers.greaterThan(0)))
        .andExpect(jsonPath("$.upstreamModules.length()").value(org.hamcrest.Matchers.greaterThan(0)));
  }

  @Test
  void 端点一次拉齐返回全部区块() throws Exception {
    refreshAll();
    http.perform(
            get("/reporting/product-performance")
                .header("Authorization", BEARER)
                .param("dimensionType", "SKU")
                .param("dimensionKey", "SKU-A")
                .param("from", DAY1.toString())
                .param("to", DAY2.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sections.length()").value(9))
        .andExpect(jsonPath("$.trend.length()").value(2))
        .andExpect(jsonPath("$.ranking.rank").value(1))
        .andExpect(jsonPath("$.dataUpdatedAt").exists());
  }

  // ==========================================================================
  // 工具
  // ==========================================================================

  private List<ReportingProductPerformanceModels.ProductPerformanceRefreshResult> refreshAll() {
    // ⚠️ 先刷 13.2.2.2（商品档案 + 销量事实），再刷本模块 —— 本模块**只读**它的投影。
    productSalesRefresh.refresh(tenant, BEARER, BASE1, DAY2, ZONE);
    return refresh.refresh(tenant, BEARER, DAY1, DAY2, BASE1, BASE2, ZONE);
  }

  private ReportingProductPerformanceModels.ProductPerformanceResponse analyze(
      String dimensionType, String dimensionKey) {
    return performance.analyze(tenant, DAY1, DAY2, BASE1, BASE2, dimensionType, dimensionKey, null);
  }

  private BigDecimal metric(
      ReportingProductPerformanceModels.ProductPerformanceResponse response,
      String metricCode,
      String currency) {
    var metric = metricOf(response, metricCode, currency);
    return metric == null ? null : metric.value();
  }

  private ReportingProductPerformanceModels.PerformanceMetric metricOf(
      ReportingProductPerformanceModels.ProductPerformanceResponse response,
      String metricCode,
      String currency) {
    for (var section : response.sections()) {
      for (var metric : section.metrics()) {
        if (metric.metricCode().equals(metricCode) && metric.currency().equals(currency)) return metric;
      }
    }
    return null;
  }

  private ReportingProductPerformanceModels.PerformanceSection section(
      ReportingProductPerformanceModels.ProductPerformanceResponse response, String sectionCode) {
    return response.sections().stream()
        .filter(s -> s.sectionCode().equals(sectionCode))
        .findFirst()
        .orElseThrow();
  }

  private int factCount() {
    return tenants.run(tenant, () -> sql.fetch("select id from reporting_product_performance_fact").size());
  }

  private int snapshotCount() {
    return tenants.run(
        tenant, () -> sql.fetch("select id from reporting_product_performance_snapshot").size());
  }

  // ---------------------------------------------------------------------------
  // 夹具
  // ---------------------------------------------------------------------------

  private static Map<String, Object> sku(UUID id, String code, String name, UUID spuId, String spuCode) {
    return Map.of(
        "id", id.toString(),
        "code", code,
        "name", name,
        "spuId", spuId.toString(),
        "spuCode", spuCode,
        "spuName", spuCode,
        "variantSignature", code,
        "status", "ACTIVE");
  }

  private static Map<String, Object> listing(
      UUID id, String shopCode, String platformCode, String externalItemId, List<UUID> skuIds) {
    return Map.of(
        "id", id.toString(),
        "shopCode", shopCode,
        "shopName", shopCode,
        "platformCode", platformCode,
        "marketplaceCode", platformCode,
        "externalItemId", externalItemId,
        "title", externalItemId,
        "status", "ACTIVE",
        "mappingStatus", "MAPPED",
        "internalSkuIds", skuIds.stream().map(UUID::toString).toList());
  }

  private static Map<String, Object> order(
      String orderNo, LocalDate day, String currency, String skuCode, String quantity, String amount) {
    return Map.of(
        "id", UUID.randomUUID().toString(),
        "orderNo", orderNo,
        "skuCode", skuCode,
        "orderSource", "MANUAL",
        "currency", currency,
        "grandTotalAmount", amount,
        "quantity", quantity,
        "lineCount", 1,
        "submittedAt",
            OffsetDateTime.of(day, java.time.LocalTime.of(10, 0), ZoneOffset.UTC).toString(),
        "createdAt",
            OffsetDateTime.of(day, java.time.LocalTime.of(10, 0), ZoneOffset.UTC).toString());
  }

  private static Map<String, Object> performance(
      UUID listingId,
      LocalDate day,
      String impressions,
      String clicks,
      String sessions,
      String units,
      String sales,
      String currency) {
    return map(
        "listingId", listingId.toString(),
        "title", "listing",
        "shopCode", "shop",
        "platformCode", "AMAZON",
        "statDate", day.toString(),
        "impressions", impressions,
        "clicks", clicks,
        "sessions", sessions,
        "unitsOrdered", units,
        "salesAmount", sales,
        "currency", currency);
  }

  private static Map<String, Object> costEntry(
      UUID runId, UUID flowId, UUID skuId, String skuCode, LocalDate day, String amount, String currency) {
    return map(
        "id", UUID.randomUUID().toString(),
        "runId", runId.toString(),
        "flowTransactionId", flowId.toString(),
        "occurredAt",
            OffsetDateTime.of(day, java.time.LocalTime.of(8, 0), ZoneOffset.UTC).toString(),
        "businessType", "SALES_OUTBOUND",
        "skuId", skuId.toString(),
        "skuCode", skuCode,
        "issueQuantity", "1",
        "costAmount", amount,
        "currency", currency,
        "status", "CALCULATED");
  }

  private static Map<String, Object> adRow(
      UUID campaignId,
      String label,
      String spend,
      String impressions,
      String clicks,
      String orders,
      String sales,
      String currency) {
    return map(
        "dimension", "CAMPAIGN",
        "dimensionKey", campaignId.toString(),
        "dimensionLabel", label,
        "currency", currency,
        "platformName", "AMAZON",
        "shopName", "shop-a",
        "externalCampaignId", "EXT-" + label,
        "impressions", impressions,
        "clicks", clicks,
        "orders", orders,
        "spend", spend,
        "sales", sales);
  }

  // ---------------------------------------------------------------------------
  // 假上游
  // ---------------------------------------------------------------------------

  private static void respond(com.sun.net.httpserver.HttpExchange e, Object body) {
    try {
      if (upstreamDown) {
        e.sendResponseHeaders(500, -1);
        e.close();
        return;
      }
      if (body == null) {
        e.sendResponseHeaders(404, -1);
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

  /** 造一个保持顺序的 Map（{@code Map.of} 最多 10 对，夹具常常超）。 */
  private static Map<String, Object> map(Object... pairs) {
    var out = new LinkedHashMap<String, Object>();
    for (int i = 0; i + 1 < pairs.length; i += 2) {
      out.put(String.valueOf(pairs[i]), pairs[i + 1]);
    }
    return out;
  }

  /** 一页数据（列表字段名由 {@code field} 决定）。 */
  private static Map<String, Object> page(List<Map<String, Object>> rows, String field) {
    var body = new LinkedHashMap<String, Object>();
    body.put(field, rows);
    body.put("page", 0);
    body.put("size", 200);
    body.put("total", rows.size());
    return body;
  }

  private static Map<String, Object> ordersBody(com.sun.net.httpserver.HttpExchange e)
      throws java.io.IOException {
    var query = query(e.getRequestURI().getRawQuery());
    var from = parseInstant(query.get("orderedFrom"));
    var to = parseInstant(query.get("orderedTo"));
    var matched = new ArrayList<Map<String, Object>>();
    for (var row : ORDERS) {
      var at = parseInstant(String.valueOf(row.get("submittedAt")));
      if (at == null) continue;
      if (from != null && at.isBefore(from)) continue;
      if (to != null && !at.isBefore(to)) continue;
      matched.add(row);
    }
    return page(matched, "rows");
  }

  private static Map<String, Object> detailBody(com.sun.net.httpserver.HttpExchange e)
      throws java.io.IOException {
    var path = e.getRequestURI().getPath();
    var id = path.substring(path.lastIndexOf('/') + 1);
    var matched = new ArrayList<Map<String, Object>>();
    for (var row : ORDERS) {
      if (!row.get("id").equals(id)) continue;
      var skuCode = String.valueOf(row.get("skuCode"));
      matched.add(
          Map.of(
              "skuCode", skuCode,
              "skuName", skuCode,
              "quantity", row.get("quantity"),
              "unitPrice", row.get("grandTotalAmount"),
              "lineAmount", row.get("grandTotalAmount")));
    }
    var body = new LinkedHashMap<String, Object>();
    body.put("header", Map.of("orderNo", "SO", "currency", "USD"));
    body.put("lines", matched);
    return body;
  }

  private static Map<String, Object> casesBody(com.sun.net.httpserver.HttpExchange e)
      throws java.io.IOException {
    var path = e.getRequestURI().getPath();
    // 🔴 /trade/aftersales/cases 与 /trade/aftersales/cases/{id} 落在**同一个上下文**里
    //   （HttpServer 按前缀匹配）⇒ 这里自己按路径分流。
    if (path.split("/").length > 4) return caseDetailBody();
    var query = query(e.getRequestURI().getRawQuery());
    var from = parseInstant(query.get("receivedFrom"));
    var to = parseInstant(query.get("receivedTo"));
    var matched = new ArrayList<Map<String, Object>>();
    for (var row : CASES) {
      var at = parseInstant(String.valueOf(row.get("receivedAt")));
      if (at == null) continue;
      if (from != null && at.isBefore(from)) continue;
      if (to != null && !at.isBefore(to)) continue;
      matched.add(row);
    }
    return page(matched, "rows");
  }

  /** 售后单详情：只有 SKU-A 那一行是「涉及行」。 */
  private static Map<String, Object> caseDetailBody() {
    var body = new LinkedHashMap<String, Object>();
    body.put("orderLines", CASE_LINES);
    body.put("lines", List.of());
    return body;
  }

  private static Map<String, Object> costEntriesBody(com.sun.net.httpserver.HttpExchange e)
      throws java.io.IOException {
    var query = query(e.getRequestURI().getRawQuery());
    var businessType = query.get("businessType");
    var matched = new ArrayList<Map<String, Object>>();
    for (var row : COST_ENTRIES) {
      if (businessType != null && !businessType.equals(row.get("businessType"))) continue;
      matched.add(row);
    }
    return page(matched, "items");
  }

  private static Map<String, Object> adRowsBody(com.sun.net.httpserver.HttpExchange e)
      throws java.io.IOException {
    var query = query(e.getRequestURI().getRawQuery());
    var from = parseDate(query.get("from"));
    var to = parseDate(query.get("to"));
    var matched = new ArrayList<Map<String, Object>>();
    for (var row : AD_ROWS) {
      // 假上游按「窗口」返回同一批活动（窗口聚合口径）。
      if (from == null || to == null) continue;
      matched.add(row);
    }
    return page(matched, "items");
  }

  private static Map<String, Object> campaignProductsBody(com.sun.net.httpserver.HttpExchange e)
      throws java.io.IOException {
    var path = e.getRequestURI().getPath();
    // /channel/ad-campaign-details/campaigns/{id}/products
    var parts = path.split("/");
    var campaignId = parts.length >= 5 ? parts[4] : "";
    var listings = CAMPAIGN_LISTINGS.getOrDefault(campaignId, List.of());
    var items = new ArrayList<Map<String, Object>>();
    for (var listingId : listings) {
      items.add(
          Map.of(
              "id", UUID.randomUUID().toString(),
              "groupId", UUID.randomUUID().toString(),
              "groupName", "group",
              "listingId", listingId.toString(),
              "externalItemId", "EXT",
              "title", "listing"));
    }
    return page(items, "items");
  }

  private static Map<String, String> query(String raw) {
    var out = new LinkedHashMap<String, String>();
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

  /**
   * 解析上游时刻。
   *
   * <p>🔴 **两种格式都要认**��{@code OffsetDateTime.toString()} 在秒为 0 时会写成
   * {@code 2026-10-01T10:00Z}（省略秒），而 {@code Instant.parse} 要求秒 ——
   * 只认一种格式会让夹具「静默变成零行」，看起来像上游没数据。
   */
  private static java.time.Instant parseInstant(String raw) {
    if (raw == null || raw.isBlank()) return null;
    try {
      return java.time.Instant.parse(raw);
    } catch (RuntimeException ignored) {
      // 落到下面按 OffsetDateTime 解析。
    }
    try {
      return OffsetDateTime.parse(raw).toInstant();
    } catch (RuntimeException e) {
      return null;
    }
  }

  private static LocalDate parseDate(String raw) {
    if (raw == null || raw.isBlank()) return null;
    try {
      return LocalDate.parse(raw);
    } catch (RuntimeException e) {
      return null;
    }
  }
}
