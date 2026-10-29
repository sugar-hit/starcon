package com.starcon.reporting;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
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
 * 13.3.2.1 流量转化的**集成测试**（真实 PostgreSQL 16 + FORCE RLS，无内存替身）。
 *
 * <p>本文件验的是**业务口径**（多租户三条断言在 {@code TrafficConversionTenancyIntegrationTest}）：
 *
 * <ul>
 *   <li>漏斗四组（平台商品口径 / 自然流量推导值 / 广告口径 / 订单口径）都真的出得来，
 *       每一格都带自己的口径标签；
 *   <li>🔴 **自然流量是推导值**（平台商品口径 − 广告口径），且**相减为负时报「口径冲突」、
 *       绝不截成 0**；
 *   <li>🔴 **两套口径分列**：平台转化率、订单口径客单价、平台口径客单价**互不冒充**；
 *   <li>🔴 **订单口径的转化率算不出来**（内部订单没有 Session）⇒ 「不可比」，不是 0%；
 *   <li>🔴 **统计日的日历口径也是口径**：平台自然日 vs UTC 自然日；趋势的**日 / 周 / 月**
 *       桶边界（含**跨月**与**跨周**边界）都对；
 *   <li>🔴 **timestamptz 往返**：订单的发生时刻写回后**逐字相等**（用 +08:00 的偏移时刻做夹具，
 *       用 {@code Timestamp.valueOf(LocalDateTime)} 会静默偏 −28800s）；
 *   <li>异常诊断四类规则（曝光断崖 / 点击率骤降 / 转化率骤降 / Session-点击口径冲突）
 *       与窗口级的**口径背离**都真的报得出来；
 *   <li>构成明细可下钻到 listing / 广告活动 / 内部订单；
 *   <li>重复刷新幂等；上游读不到就 503，不返回空集合；币种缺失的金额**不落库**。
 * </ul>
 *
 * <p>⚠️ **上游是真的 HTTP**：本测试起一个假的上游服务（channel + trade），
 * 驱动 {@code ReportingTrafficConversionRefreshService} 的**真实**拉取与投影路径 ——
 * 端口写错了路径 / 字段名，在这一层就会暴露（写错字段名 ⇒ 那一类指标恒为 0）。
 *
 * <p>⚠️ **连接池固定为 1 条连接**：只有复用同一条连接，才能真正证明归还后不会串租户。
 */
@SpringBootTest
@AutoConfigureMockMvc
class TrafficConversionIntegrationTest {

  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID tenant = UUID.randomUUID(), user = UUID.randomUUID();
  static final String BEARER = "Bearer tenant-a";

  static HttpServer identity;
  static HttpServer upstream;

  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  static final List<String> permissions =
      List.of(
          "reporting.traffic-conversion.read",
          // ⚠️ 写权限必须在**同一个 grant** 里同时命中对应的 .read ——
          //   服务端的判据是「能写不能读」的组合不放行，所以这里必须带上 reporting.projection.read。
          "reporting.projection.read",
          "reporting.projection.write");

  static final ObjectMapper JSON = JsonMapper.builder().addModule(new JavaTimeModule()).build();

  // ==========================================================================
  // 夹具日期（**固定日历日** ⇒ 桶边界是确定的，不依赖运行时刻）
  // ==========================================================================

  /** D1 = 2026-01-30（周五）。 */
  static final LocalDate D1 = LocalDate.of(2026, 1, 30);
  /** D2 = 2026-01-31（周六，**跨月边界的前一天**）。 */
  static final LocalDate D2 = LocalDate.of(2026, 1, 31);
  /** D3 = 2026-02-01（周日，**跨月边界**）。 */
  static final LocalDate D3 = LocalDate.of(2026, 2, 1);
  /** D4 = 2026-02-02（周一，**跨周边界**）。 */
  static final LocalDate D4 = LocalDate.of(2026, 2, 2);

  static final UUID LISTING_A = UUID.randomUUID();
  static final UUID LISTING_B = UUID.randomUUID();
  static final UUID LISTING_C = UUID.randomUUID();
  static final UUID LISTING_D = UUID.randomUUID();
  static final String CAMPAIGN = "campaign-1";

  /**
   * 🔴 **订单夹具用 +08:00 的偏移时刻**：写库必须用 {@code OffsetDateTime} 绑定
   * （values 位置 {@code ?::timestamptz}）—— 用 {@code Timestamp.valueOf(LocalDateTime)}
   * 会静默偏 −28800s。写回后的**绝对时刻**必须逐字相等（断言写在
   * {@code 断言_timestamptz往返逐字相等}）。
   */
  static final OffsetDateTime ORDER_1_AT = OffsetDateTime.parse("2026-01-30T18:00:00+08:00");

  /** ⚠️ 上游故障开关：打开后假上游一律 500，用来证「读不到 ≠ 没有数据」。 */
  static volatile boolean upstreamDown = false;

  /** ⚠️ 口径冲突开关：打开后广告曝光 > 平台曝光 ⇒ 自然流量（推导值）为负。 */
  static volatile boolean conflictMode = false;

  // ==========================================================================
  // 夹具数据
  // ==========================================================================

  /** 一条平台表现（listing 级，**页面口径**）。 */
  static Map<String, Object> listing(
      UUID id, LocalDate statDate, String impressions, String clicks, String sessions,
      String units, String sales) {
    var row = new LinkedHashMap<String, Object>();
    row.put("listingId", id);
    row.put("externalItemId", "EXT-" + id.toString().substring(0, 8));
    row.put("title", "商品 " + id.toString().substring(0, 4));
    row.put("shopCode", "shop-a");
    row.put("platformCode", "AMAZON");
    row.put("statDate", statDate.toString());
    row.put("impressions", new BigDecimal(impressions));
    row.put("clicks", new BigDecimal(clicks));
    row.put("sessions", new BigDecimal(sessions));
    row.put("unitsOrdered", new BigDecimal(units));
    row.put("salesAmount", new BigDecimal(sales));
    row.put("currency", "USD");
    return row;
  }

  /** 一条广告报表（活动 × 日，**广告口径**）。 */
  static Map<String, Object> ad(
      LocalDate metricDate, String impressions, String clicks, String orders,
      String spend, String sales) {
    var row = new LinkedHashMap<String, Object>();
    row.put("dimension", "CAMPAIGN");
    row.put("dimensionKey", CAMPAIGN);
    row.put("dimensionLabel", "春季活动");
    row.put("currency", "USD");
    row.put("platformName", "亚马逊");
    row.put("shopName", "亚马逊旗舰店");
    row.put("externalCampaignId", "EXT-CAMPAIGN-1");
    row.put("impressions", new BigDecimal(impressions));
    row.put("clicks", new BigDecimal(clicks));
    row.put("orders", new BigDecimal(orders));
    row.put("spend", new BigDecimal(spend));
    row.put("sales", new BigDecimal(sales));
    row.put("metricFrom", metricDate.toString());
    row.put("metricTo", metricDate.toString());
    return row;
  }

  /** 一张内部订单（**订单口径**）。 */
  static Map<String, Object> order(String orderNo, OffsetDateTime at, String amount) {
    var row = new LinkedHashMap<String, Object>();
    row.put("id", UUID.nameUUIDFromBytes(orderNo.getBytes(StandardCharsets.UTF_8)));
    row.put("orderNo", orderNo);
    row.put("orderSource", "MANUAL");
    row.put("currency", "USD");
    row.put("grandTotalAmount", new BigDecimal(amount));
    row.put("lineCount", 1);
    row.put("submittedAt", at.toString());
    row.put("createdAt", at.toString());
    return row;
  }

  /** 平台表现清单（D1 曝光 1000 → D2 曝光 100 ⇒ 断崖；D3 点击 50 > Session 10 ⇒ 口径冲突）。 */
  static final List<Map<String, Object>> LISTINGS =
      List.of(
          listing(LISTING_A, D1, "1000", "100", "80", "8", "800.00"),
          listing(LISTING_B, D2, "100", "5", "20", "1", "100.00"),
          listing(LISTING_C, D3, "1000", "50", "10", "2", "200.00"),
          listing(LISTING_D, D4, "1000", "100", "100", "10", "1000.00"));

  /** 广告报表。 */
  static final List<Map<String, Object>> ADS =
      List.of(
          ad(D1, "300", "30", "3", "60.00", "300.00"),
          ad(D2, "50", "2", "0", "10.00", "0.00"),
          ad(D3, "200", "20", "1", "40.00", "100.00"),
          ad(D4, "200", "20", "2", "40.00", "200.00"));

  /** 内部订单（D1 那一单故意用 +08:00 偏移时刻）。 */
  static final List<Map<String, Object>> ORDERS =
      List.of(
          order("SO-1", ORDER_1_AT, "500.00"),
          order("SO-2", OffsetDateTime.parse("2026-01-31T10:00:00Z"), "100.00"),
          order("SO-3", OffsetDateTime.parse("2026-02-01T10:00:00Z"), "200.00"),
          order("SO-4", OffsetDateTime.parse("2026-02-02T10:00:00Z"), "300.00"));

  // ==========================================================================
  // 生命周期
  // ==========================================================================

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
      actors.put(BEARER, actor(tenant));
      identity.start();

      upstream = HttpServer.create(new InetSocketAddress(0), 0);
      upstream.createContext("/channel/listing-performance", e -> respond(e, listingBody(e)));
      upstream.createContext("/channel/ad-reports/rows", e -> respond(e, adBody(e)));
      upstream.createContext("/trade/sales-orders/list", e -> respond(e, ordersBody(e)));
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
  @Autowired ReportingTrafficConversionRefreshService refresh;
  @Autowired ReportingTrafficConversionService service;

  @AfterAll
  static void stop() {
    // ⚠️ **先停外部服务、再停容器**（顺序反了会让 ryuk 的回收窗口变长）。
    identity.stop(0);
    upstream.stop(0);
    pg.stop();
  }

  @BeforeEach
  void reset() throws Exception {
    upstreamDown = false;
    conflictMode = false;
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate reporting_traffic_conversion_metric_definition,"
              + " reporting_traffic_conversion_fact,"
              + " reporting_traffic_conversion_drilldown_fact cascade");
    }
  }

  // ==========================================================================
  // 漏斗
  // ==========================================================================

  @Test
  void 断言_漏斗四组都出得来且每格带口径标签() {
    refresh.refresh(tenant, BEARER, D1, D4);
    var report = service.report(tenant, D1, D4, "DAY");

    assertThat(report.funnel()).hasSize(4);

    var listing = group(report, "PLATFORM_LISTING");
    assertThat(listing.caliber()).isEqualTo("PLATFORM");
    assertThat(listing.trafficSource()).isEqualTo("LISTING");
    assertThat(stage(listing, "IMPRESSIONS").value()).isEqualByComparingTo("3100");
    assertThat(stage(listing, "CLICKS").value()).isEqualByComparingTo("255");
    assertThat(stage(listing, "SESSIONS").value()).isEqualByComparingTo("210");
    assertThat(stage(listing, "CONVERSION").value()).isEqualByComparingTo("21");
    // 点击率 = 255 / 3100
    assertThat(stage(listing, "CLICKS").stepRate()).isEqualByComparingTo("0.0823");
    // 成交件数 21 / 曝光 3100
    assertThat(stage(listing, "CONVERSION").overallRate()).isEqualByComparingTo("0.0068");

    var ad = group(report, "AD");
    assertThat(ad.caliber()).isEqualTo("PLATFORM");
    assertThat(ad.trafficSource()).isEqualTo("AD");
    assertThat(stage(ad, "AD_IMPRESSIONS").value()).isEqualByComparingTo("750");
    assertThat(stage(ad, "AD_CLICKS").value()).isEqualByComparingTo("72");
    assertThat(stage(ad, "AD_CONVERSION").value()).isEqualByComparingTo("6");

    var order = group(report, "ORDER");
    assertThat(order.caliber()).isEqualTo("ORDER");
    assertThat(order.trafficSource()).isEqualTo("ORDER");
    assertThat(stage(order, "ORDER_COUNT").value()).isEqualByComparingTo("4");
  }

  @Test
  void 断言_自然流量是推导值() {
    refresh.refresh(tenant, BEARER, D1, D4);
    var report = service.report(tenant, D1, D4, "DAY");
    var organic = group(report, "ORGANIC");

    // 3100 − 750 = 2350；255 − 72 = 183
    assertThat(stage(organic, "ORGANIC_IMPRESSIONS").value()).isEqualByComparingTo("2350");
    assertThat(stage(organic, "ORGANIC_CLICKS").value()).isEqualByComparingTo("183");
    // 推导值必须带自己的说明，不能让人误以为平台直接给了这个数。
    assertThat(stage(organic, "ORGANIC_IMPRESSIONS").availabilityNote()).contains("推导值");
  }

  @Test
  void 断言_自然流量相减为负时报口径冲突且不截成零() {
    conflictMode = true;
    refresh.refresh(tenant, BEARER, D1, D4);
    var report = service.report(tenant, D1, D4, "DAY");
    var organic = group(report, "ORGANIC");

    var impressions = stage(organic, "ORGANIC_IMPRESSIONS");
    assertThat(impressions.availability()).isEqualTo("CALIBER_CONFLICT");
    // 🔴 绝不能截成 0 —— 截成 0 会把口径问题藏起来。
    assertThat(impressions.value()).isNotNull();
    assertThat(impressions.value().signum()).isNegative();
    assertThat(impressions.availabilityNote()).contains("没有截成 0");
  }

  // ==========================================================================
  // 两套口径分列
  // ==========================================================================

  @Test
  void 断言_转化率与客单价分属不同口径且互不冒充() {
    refresh.refresh(tenant, BEARER, D1, D4);
    var report = service.report(tenant, D1, D4, "DAY");

    // 平台口径转化率 = 成交件数 21 ÷ Session 210 = 0.1
    var conversion = metric(report, ReportingTrafficConversionCatalog.CONVERSION_RATE);
    assertThat(conversion.caliber()).isEqualTo("PLATFORM");
    assertThat(conversion.value()).isEqualByComparingTo("0.1");

    // 平台口径客单价 = 2100 ÷ 21 = 100
    var platformAov = metric(report, ReportingTrafficConversionCatalog.PLATFORM_AOV);
    assertThat(platformAov.caliber()).isEqualTo("PLATFORM");
    assertThat(platformAov.currency()).isEqualTo("USD");
    assertThat(platformAov.value()).isEqualByComparingTo("100");

    // 订单口径客单价 = 1100 ÷ 4 = 275
    var orderAov = metric(report, ReportingTrafficConversionCatalog.ORDER_AOV);
    assertThat(orderAov.caliber()).isEqualTo("ORDER");
    assertThat(orderAov.currency()).isEqualTo("USD");
    assertThat(orderAov.value()).isEqualByComparingTo("275");

    // 🔴 平台口径客单价 ≠ 订单口径客单价 —— 两者不能互相冒充。
    assertThat(orderAov.value()).isNotEqualByComparingTo(platformAov.value());
  }

  @Test
  void 断言_订单口径的转化率算不出来而不是零() {
    refresh.refresh(tenant, BEARER, D1, D4);
    var report = service.report(tenant, D1, D4, "DAY");

    // 订单口径**没有**转化率指标：内部订单不带 Session ⇒ 分母缺失不是 0。
    var orderCaliberMetrics =
        report.keyMetrics().stream()
            .filter(m -> "ORDER".equals(m.caliber()))
            .map(ReportingTrafficConversionModels.TrafficMetricValue::metricCode)
            .toList();
    assertThat(orderCaliberMetrics)
        .containsExactlyInAnyOrder(
            ReportingTrafficConversionCatalog.ORDER_COUNT,
            ReportingTrafficConversionCatalog.ORDER_AMOUNT,
            ReportingTrafficConversionCatalog.ORDER_AOV);
    assertThat(orderCaliberMetrics)
        .as("订单口径不许出现转化率（分母缺失算不出来）")
        .doesNotContain(ReportingTrafficConversionCatalog.CONVERSION_RATE);
  }

  @Test
  void 断言_绝不跨币种相加且币种分别列出() {
    refresh.refresh(tenant, BEARER, D1, D4);
    var report = service.report(tenant, D1, D4, "DAY");

    var sales = report.keyMetrics().stream()
        .filter(m -> ReportingTrafficConversionCatalog.PLATFORM_SALES_AMOUNT.equals(m.metricCode()))
        .toList();
    assertThat(sales).hasSize(1);
    assertThat(sales.get(0).currency()).isEqualTo("USD");
    assertThat(sales.get(0).value()).isEqualByComparingTo("2100");

    // 与货币无关的量：币种是空串（= 与币种无关，**不是「币种未知」**）。
    var impressions = report.keyMetrics().stream()
        .filter(m -> ReportingTrafficConversionCatalog.IMPRESSIONS.equals(m.metricCode()))
        .toList();
    assertThat(impressions).hasSize(1);
    assertThat(impressions.get(0).currency()).isEmpty();
  }

  // ==========================================================================
  // 趋势（**跨日 / 跨周 / 跨月边界**）
  // ==========================================================================

  @Test
  void 断言_趋势按日分桶跨月边界正确() {
    refresh.refresh(tenant, BEARER, D1, D4);
    var report = service.report(tenant, D1, D4, "DAY");

    assertThat(report.trend()).hasSize(4);
    assertThat(report.trend().get(0).bucketStart()).isEqualTo("2026-01-30");
    assertThat(report.trend().get(1).bucketStart()).isEqualTo("2026-01-31");
    assertThat(report.trend().get(2).bucketStart()).isEqualTo("2026-02-01");
    assertThat(report.trend().get(3).bucketStart()).isEqualTo("2026-02-02");
    // 桶里的量按天分摊（D2 的曝光只有 100，D3 又是 1000）。
    assertThat(trendValue(report, 0, ReportingTrafficConversionCatalog.IMPRESSIONS))
        .isEqualByComparingTo("1000");
    assertThat(trendValue(report, 1, ReportingTrafficConversionCatalog.IMPRESSIONS))
        .isEqualByComparingTo("100");
    assertThat(trendValue(report, 2, ReportingTrafficConversionCatalog.IMPRESSIONS))
        .isEqualByComparingTo("1000");
  }

  @Test
  void 断言_趋势按月分桶跨月边界正确() {
    refresh.refresh(tenant, BEARER, D1, D4);
    var report = service.report(tenant, D1, D4, "MONTH");

    // 2026-01 桶（D1 + D2）与 2026-02 桶（D3 + D4）—— **跨月边界必须切开**。
    assertThat(report.trend()).hasSize(2);
    assertThat(report.trend().get(0).bucketStart()).isEqualTo("2026-01-01");
    assertThat(report.trend().get(0).bucketEnd()).isEqualTo("2026-01-31");
    assertThat(report.trend().get(1).bucketStart()).isEqualTo("2026-02-01");
    assertThat(report.trend().get(1).bucketEnd()).isEqualTo("2026-02-28");
    assertThat(trendValue(report, 0, ReportingTrafficConversionCatalog.IMPRESSIONS))
        .isEqualByComparingTo("1100");
    assertThat(trendValue(report, 1, ReportingTrafficConversionCatalog.IMPRESSIONS))
        .isEqualByComparingTo("2000");
  }

  @Test
  void 断言_趋势按周分桶跨周边界正确() {
    refresh.refresh(tenant, BEARER, D1, D4);
    var report = service.report(tenant, D1, D4, "WEEK");

    // ISO 周（周一为一周之始）：D1~D3 落在 2026-01-26 那一周，D4 落在 2026-02-02 那一周。
    assertThat(report.trend()).hasSize(2);
    assertThat(report.trend().get(0).bucketStart()).isEqualTo("2026-01-26");
    assertThat(report.trend().get(0).bucketEnd()).isEqualTo("2026-02-01");
    assertThat(report.trend().get(1).bucketStart()).isEqualTo("2026-02-02");
    assertThat(trendValue(report, 0, ReportingTrafficConversionCatalog.IMPRESSIONS))
        .isEqualByComparingTo("2100");
    assertThat(trendValue(report, 1, ReportingTrafficConversionCatalog.IMPRESSIONS))
        .isEqualByComparingTo("1000");
  }

  // ==========================================================================
  // 异常诊断
  // ==========================================================================

  @Test
  void 断言_异常诊断报出四类桶级规则与窗口级口径背离() {
    refresh.refresh(tenant, BEARER, D1, D4);
    var report = service.report(tenant, D1, D4, "DAY");

    assertThat(report.diagnoses())
        .extracting(ReportingTrafficConversionModels.TrafficDiagnosis::ruleCode)
        .contains(
            ReportingTrafficConversionCatalog.RULE_IMPRESSION_COLLAPSE,
            ReportingTrafficConversionCatalog.RULE_CTR_DROP,
            ReportingTrafficConversionCatalog.RULE_CONVERSION_RATE_DROP,
            ReportingTrafficConversionCatalog.RULE_SESSION_CLICK_GAP,
            ReportingTrafficConversionCatalog.RULE_CALIBER_GAP);

    // 曝光断崖：D2 曝光 100 vs D1 1000 ⇒ −90%
    var collapse =
        diagnosis(report, ReportingTrafficConversionCatalog.RULE_IMPRESSION_COLLAPSE);
    assertThat(collapse.severity()).isEqualTo("HIGH");
    assertThat(collapse.observed()).isEqualByComparingTo("100");
    assertThat(collapse.baseline()).isEqualByComparingTo("1000");
    assertThat(collapse.changeRate()).isEqualByComparingTo("-0.9");
    assertThat(collapse.bucketStart()).isEqualTo(D2.toString());

    // 口径背离：平台口径成交件数 21 vs 订单口径订单笔数 4
    var gap = diagnosis(report, ReportingTrafficConversionCatalog.RULE_CALIBER_GAP);
    assertThat(gap.scope()).isEqualTo("WINDOW");
    assertThat(gap.observed()).isEqualByComparingTo("21");
    assertThat(gap.baseline()).isEqualByComparingTo("4");
    assertThat(gap.message()).contains("口径");
  }

  @Test
  void 断言_窗口第一个桶没有基线所以不诊断() {
    refresh.refresh(tenant, BEARER, D1, D4);
    var report = service.report(tenant, D1, D4, "DAY");

    // 第一个桶（D1）没有上一个桶可比 ⇒ **不诊断**（不是「没有异常」）。
    assertThat(report.diagnoses())
        .filteredOn(d -> "BUCKET".equals(d.scope()) && D1.toString().equals(d.bucketStart()))
        .isEmpty();
  }

  // ==========================================================================
  // 下钻
  // ==========================================================================

  @Test
  void 断言_构成明细可下钻到平台商品广告活动与内部订单() {
    refresh.refresh(tenant, BEARER, D1, D4);

    var listings =
        service.drilldown(tenant, D1, D4, "LISTING", ReportingTrafficConversionCatalog.IMPRESSIONS, 0, 50);
    assertThat(listings.rows()).isNotEmpty();
    assertThat(listings.rows()).allMatch(r -> "LISTING".equals(r.sourceKind()));
    assertThat(listings.rows().get(0).shopCode()).isEqualTo("shop-a");

    var ads =
        service.drilldown(tenant, D1, D4, "AD", ReportingTrafficConversionCatalog.AD_CLICKS, 0, 50);
    assertThat(ads.rows()).isNotEmpty();
    assertThat(ads.rows()).allMatch(r -> "AD_CAMPAIGN".equals(r.sourceKind()));
    assertThat(ads.rows().get(0).referenceId()).isEqualTo(CAMPAIGN);

    var orders =
        service.drilldown(tenant, D1, D4, "ORDER", ReportingTrafficConversionCatalog.ORDER_COUNT, 0, 50);
    assertThat(orders.rows()).hasSize(4);
    assertThat(orders.rows()).allMatch(r -> "ORDER".equals(r.sourceKind()));
    assertThat(orders.rows()).allMatch(r -> r.orderNo() != null && !r.orderNo().isEmpty());
  }

  @Test
  void 断言_下钻是真分页() {
    refresh.refresh(tenant, BEARER, D1, D4);
    var page0 = service.drilldown(tenant, D1, D4, null, null, 0, 2);
    var page1 = service.drilldown(tenant, D1, D4, null, null, 1, 2);
    assertThat(page0.totalRows()).isGreaterThan(4);
    assertThat(page0.rows()).hasSize(2);
    assertThat(page1.rows()).hasSize(2);
    assertThat(page0.rows().get(0).referenceId())
        .as("第二页不能与第一页重复")
        .isNotEqualTo(page1.rows().get(0).referenceId());
  }

  // ==========================================================================
  // 时间往返
  // ==========================================================================

  @Test
  void 断言_timestamptz往返逐字相等() {
    refresh.refresh(tenant, BEARER, D1, D4);

    var written =
        tenants.run(
            tenant,
            () ->
                sql.fetch(
                        """
                        select occurred_at from reporting_traffic_conversion_drilldown_fact
                         where tenant_id = ? and source_kind = 'ORDER' and reference_id = ?
                        """,
                        tenant,
                        "SO-1")
                    .stream()
                    .findFirst()
                    .map(r -> r.get(0, OffsetDateTime.class))
                    .orElse(null));

    assertThat(written).as("订单的发生时刻必须落库").isNotNull();
    // 🔴 断言**写回的值**（绝对时刻逐字相等），不是「没抛异常」。
    //   夹具是 2026-01-30T18:00:00+08:00 = 2026-01-30T10:00:00Z；
    //   用 Timestamp.valueOf(LocalDateTime) 会静默偏 −28800s ⇒ 这里必然对不上。
    assertThat(written.toInstant()).isEqualTo(Instant.parse("2026-01-30T10:00:00Z"));
  }

  @Test
  void 断言_订单按UTC自然日切桶() {
    refresh.refresh(tenant, BEARER, D1, D4);

    var dates =
        tenants.run(
            tenant,
            () ->
                sql.fetch(
                        """
                        select distinct stat_date from reporting_traffic_conversion_fact
                         where tenant_id = ? and traffic_source = 'ORDER'
                         order by stat_date
                        """,
                        tenant)
                    .stream()
                    .map(r -> r.get(0, LocalDate.class))
                    .toList());

    // 2026-01-30T18:00+08:00 = 2026-01-30T10:00Z ⇒ UTC 自然日仍是 01-30。
    assertThat(dates).containsExactly(D1, D2, D3, D4);
  }

  // ==========================================================================
  // 幂等 / 故障 / 币种
  // ==========================================================================

  @Test
  void 断言_重复刷新幂等() {
    refresh.refresh(tenant, BEARER, D1, D4);
    var first = service.report(tenant, D1, D4, "DAY");
    refresh.refresh(tenant, BEARER, D1, D4);
    var second = service.report(tenant, D1, D4, "DAY");

    assertThat(stage(group(second, "PLATFORM_LISTING"), "IMPRESSIONS").value())
        .isEqualByComparingTo(stage(group(first, "PLATFORM_LISTING"), "IMPRESSIONS").value());
    assertThat(stage(group(second, "AD"), "AD_IMPRESSIONS").value())
        .isEqualByComparingTo(stage(group(first, "AD"), "AD_IMPRESSIONS").value());
    assertThat(stage(group(second, "ORDER"), "ORDER_COUNT").value())
        .isEqualByComparingTo(stage(group(first, "ORDER"), "ORDER_COUNT").value());
  }

  @Test
  void 断言_上游读不到就失败不返回空集合() {
    upstreamDown = true;
    var error =
        assertThrows(
            org.springframework.web.server.ResponseStatusException.class,
            () -> refresh.refresh(tenant, BEARER, D1, D4));
    assertThat(error.getStatusCode().value()).isEqualTo(503);
    assertThat(error.getReason()).contains("读不到");
  }

  @Test
  void 断言_口径表按租户播种且派生指标引用已登记() {
    refresh.refresh(tenant, BEARER, D1, D4);

    var definitions = service.metricDefinitions(tenant);
    assertThat(definitions).isNotEmpty();
    assertThat(definitions)
        .extracting(ReportingTrafficConversionModels.TrafficMetricDefinitionView::metricCode)
        .contains(
            ReportingTrafficConversionCatalog.IMPRESSIONS,
            ReportingTrafficConversionCatalog.ORDER_COUNT,
            ReportingTrafficConversionCatalog.ORGANIC_IMPRESSIONS);

    var organic =
        definitions.stream()
            .filter(
                d ->
                    ReportingTrafficConversionCatalog.ORGANIC_IMPRESSIONS.equals(d.metricCode()))
            .findFirst()
            .orElseThrow();
    assertThat(organic.aggregation()).isEqualTo("DERIVED_DIFF");
    assertThat(organic.numeratorMetricCode())
        .isEqualTo(ReportingTrafficConversionCatalog.IMPRESSIONS);
    assertThat(organic.denominatorMetricCode())
        .isEqualTo(ReportingTrafficConversionCatalog.AD_IMPRESSIONS);
    assertThat(organic.caliber()).isEqualTo("PLATFORM");
  }

  @Test
  void 断言_数据库拒绝对派生指标落事实行() {
    refresh.refresh(tenant, BEARER, D1, D4);

    var error =
        assertThrows(
            org.springframework.dao.DataIntegrityViolationException.class,
            () ->
                tenants.run(
                    tenant,
                    () ->
                        sql.execute(
                            """
                            insert into reporting_traffic_conversion_fact
                              (id,tenant_id,traffic_source,metric_code,stat_date,bucket_basis,currency,
                               value,quantity,shop_code,platform_code,source_service,source_reference)
                            values (gen_random_uuid(),?, 'LISTING', ?, ?::date, 'PLATFORM_DAY', '', 0, 1,
                                    '', '', 'channel', 'hack')
                            """,
                            tenant,
                            ReportingTrafficConversionCatalog.ORGANIC_IMPRESSIONS,
                            D1)));
    assertThat(error.getMessage()).contains("派生指标");
  }

  @Test
  void 断言_数据库拒绝币种形状不对的事实行() {
    refresh.refresh(tenant, BEARER, D1, D4);

    // 涉币指标（平台成交金额）的事实行必须带 3 位大写币种码。
    var error =
        assertThrows(
            org.springframework.dao.DataIntegrityViolationException.class,
            () ->
                tenants.run(
                    tenant,
                    () ->
                        sql.execute(
                            """
                            insert into reporting_traffic_conversion_fact
                              (id,tenant_id,traffic_source,metric_code,stat_date,bucket_basis,currency,
                               value,quantity,shop_code,platform_code,source_service,source_reference)
                            values (gen_random_uuid(),?, 'LISTING', ?, ?::date, 'PLATFORM_DAY', '', 1, 0,
                                    '', '', 'channel', 'hack')
                            """,
                            tenant,
                            ReportingTrafficConversionCatalog.PLATFORM_SALES_AMOUNT,
                            D1)));
    assertThat(error.getMessage()).contains("币种");
  }

  @Test
  void 断言_能力声明与口径说明在响应里() {
    refresh.refresh(tenant, BEARER, D1, D4);
    var report = service.report(tenant, D1, D4, "DAY");

    assertThat(report.caliberNotes()).isNotEmpty();
    assertThat(report.caliberNotes())
        .anyMatch(note -> note.contains("平台流量数据与内部订单数据口径不同"));
    assertThat(report.caliberNotes()).anyMatch(note -> note.contains("自然流量是推导值"));
    assertThat(report.dataUpdatedAt()).as("投影落库后必须有数据更新时间").isNotNull();
    assertThat(report.status()).isEqualTo("OK");
  }

  @Test
  void 断言_端点可读且参数校验生效() throws Exception {
    http.perform(get("/reporting/traffic-conversion/capabilities").header("Authorization", BEARER))
        .andExpect(status().isOk());
    http.perform(get("/reporting/traffic-conversion/metrics").header("Authorization", BEARER))
        .andExpect(status().isOk());
    http.perform(
            get("/reporting/traffic-conversion")
                .header("Authorization", BEARER)
                .param("from", D1.toString())
                .param("to", D4.toString()))
        .andExpect(status().isOk());
    // 粒度只认 DAY / WEEK / MONTH。
    http.perform(
            get("/reporting/traffic-conversion")
                .header("Authorization", BEARER)
                .param("granularity", "HOUR"))
        .andExpect(status().isBadRequest());
  }

  // ==========================================================================
  // 工具
  // ==========================================================================

  private static ReportingTrafficConversionModels.TrafficFunnelGroup group(
      ReportingTrafficConversionModels.TrafficConversionResponse report, String groupKey) {
    return report.funnel().stream()
        .filter(g -> groupKey.equals(g.groupKey()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("漏斗里没有分组：" + groupKey));
  }

  private static ReportingTrafficConversionModels.TrafficFunnelStage stage(
      ReportingTrafficConversionModels.TrafficFunnelGroup group, String stageKey) {
    return group.stages().stream()
        .filter(s -> stageKey.equals(s.stageKey()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("漏斗里没有环节：" + stageKey));
  }

  private static ReportingTrafficConversionModels.TrafficMetricValue metric(
      ReportingTrafficConversionModels.TrafficConversionResponse report, String metricCode) {
    return report.keyMetrics().stream()
        .filter(m -> metricCode.equals(m.metricCode()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("关键指标里没有：" + metricCode));
  }

  private static BigDecimal trendValue(
      ReportingTrafficConversionModels.TrafficConversionResponse report,
      int index,
      String metricCode) {
    return report.trend().get(index).metrics().stream()
        .filter(m -> metricCode.equals(m.metricCode()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("趋势桶里没有：" + metricCode))
        .value();
  }

  private static ReportingTrafficConversionModels.TrafficDiagnosis diagnosis(
      ReportingTrafficConversionModels.TrafficConversionResponse report, String ruleCode) {
    return report.diagnoses().stream()
        .filter(d -> ruleCode.equals(d.ruleCode()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("诊断里没有规则：" + ruleCode));
  }

  // ==========================================================================
  // 假上游
  // ==========================================================================

  private static void respond(com.sun.net.httpserver.HttpExchange e, Object body)
      throws java.io.IOException {
    if (upstreamDown) {
      e.sendResponseHeaders(500, -1);
      e.close();
      return;
    }
    if (body == null) return;
    byte[] bytes = JSON.writeValueAsBytes(body);
    e.getResponseHeaders().add("Content-Type", "application/json");
    e.sendResponseHeaders(200, bytes.length);
    e.getResponseBody().write(bytes);
    e.close();
  }

  /** 平台表现快照（**按平台统计日过滤**）。 */
  private static Object listingBody(com.sun.net.httpserver.HttpExchange e)
      throws java.io.IOException {
    var query = query(e.getRequestURI().getRawQuery());
    var from = parseDate(query.get("from"));
    var to = parseDate(query.get("to"));
    var items = new ArrayList<Map<String, Object>>();
    for (var row : LISTINGS) {
      var statDate = LocalDate.parse((String) row.get("statDate"));
      if (from != null && statDate.isBefore(from)) continue;
      if (to != null && statDate.isAfter(to)) continue;
      items.add(row);
    }
    return Map.of(
        "items", items,
        "page", 0,
        "size", 200,
        "total", items.size(),
        "syncCount", items.size());
  }

  /** 广告活动报表（**按统计日过滤**）。 */
  private static Object adBody(com.sun.net.httpserver.HttpExchange e) throws java.io.IOException {
    var query = query(e.getRequestURI().getRawQuery());
    var from = parseDate(query.get("from"));
    var to = parseDate(query.get("to"));
    var items = new ArrayList<Map<String, Object>>();
    for (var row : ADS) {
      var metricDate = LocalDate.parse((String) row.get("metricFrom"));
      if (from != null && metricDate.isBefore(from)) continue;
      if (to != null && metricDate.isAfter(to)) continue;
      var copy = new LinkedHashMap<>(row);
      if (conflictMode) {
        // 🔴 口径冲突模式：广告曝光远大于平台曝光 ⇒ 自然流量（推导值）为负。
        copy.put("impressions", new BigDecimal("99999"));
      }
      items.add(copy);
    }
    var page = new LinkedHashMap<String, Object>();
    page.put("items", items);
    page.put("page", 0);
    page.put("size", 200);
    page.put("total", items.size());
    page.put("granularity", "DAY");
    page.put("dimension", "CAMPAIGN");
    page.put("emptyReason", items.isEmpty() ? "NO_SYNCED_DATA" : null);
    page.put("emptyNote", items.isEmpty() ? "没有广告数据" : "");
    return page;
  }

  /** 内部销售订单（**按下单时刻过滤**）。 */
  private static Object ordersBody(com.sun.net.httpserver.HttpExchange e)
      throws java.io.IOException {
    var query = query(e.getRequestURI().getRawQuery());
    var from = parseInstant(query.get("orderedFrom"));
    var to = parseInstant(query.get("orderedTo"));
    var rows = new ArrayList<Map<String, Object>>();
    for (var row : ORDERS) {
      var at = OffsetDateTime.parse((String) row.get("submittedAt")).toInstant();
      if (from != null && at.isBefore(from)) continue;
      if (to != null && !at.isBefore(to)) continue;
      rows.add(row);
    }
    return Map.of("rows", rows, "page", 0, "size", 200, "total", rows.size());
  }

  private static Map<String, String> query(String rawQuery) {
    var out = new LinkedHashMap<String, String>();
    if (rawQuery == null || rawQuery.isBlank()) return out;
    for (var pair : rawQuery.split("&")) {
      var index = pair.indexOf('=');
      if (index < 0) continue;
      out.put(
          URLDecoder.decode(pair.substring(0, index), StandardCharsets.UTF_8),
          URLDecoder.decode(pair.substring(index + 1), StandardCharsets.UTF_8));
    }
    return out;
  }

  private static LocalDate parseDate(String value) {
    if (value == null || value.isBlank()) return null;
    try {
      return LocalDate.parse(value.trim());
    } catch (java.time.format.DateTimeParseException e) {
      return null;
    }
  }

  private static Instant parseInstant(String value) {
    if (value == null || value.isBlank()) return null;
    try {
      return Instant.parse(value.trim());
    } catch (java.time.format.DateTimeParseException e) {
      return null;
    }
  }
}
