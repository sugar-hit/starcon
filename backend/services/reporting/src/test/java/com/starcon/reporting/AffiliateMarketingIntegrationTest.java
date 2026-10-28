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
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
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
 * 13.3.2.2 关联营销效果的**集成测试**（真实 PostgreSQL 16 + FORCE RLS，无内存替身）。
 *
 * <p>本文件验的是**业务口径**（多租户三条断言在 {@code AffiliateMarketingTenancyIntegrationTest}）：
 *
 * <ul>
 *   <li>🔴 **归因规则必须显式**：同一对达人 × 活动用过两条不同规则 ⇒ 报表**分两行**，
 *       每行带自己的规则快照；<b>ROI 只在同一条规则内部相除</b>（混算会得到第三个数）；
 *   <li>🔴 **规则快照不是「当前规则」**：归因记录自带的那份快照被**逐字**落库；
 *   <li>🔴 **两套口径不混算**：达人归因口径（成交归因 / 投入 / ROI）与落地商品口径
 *       （曝光 / 点击 / 点击率）各归各的，**互不冒充**；
 *   <li>🔴 **绝不跨币种相加**：同一营销位上的 USD 与 EUR **分列**，ROI **按同币种**算；
 *   <li>🔴 **「本轮不可用」不是 0**：加购 / 关联视频 / Store 导流 / 达人站外曝光 ⇒
 *       {@code NOT_AVAILABLE} + 归谁管，**不是 0**；
 *   <li>🔴 **缺币种 ⇒ 金额不落库**：上游没给币种的归因金额是 {@code null}，不是 0；
 *   <li>🔴 **timestamptz 往返**：归因发生时刻写回后**逐字相等**（用 +08:00 的偏移时刻做夹具，
 *       用 {@code Timestamp.valueOf(LocalDateTime)} 会静默偏 −28800s）；
 *   <li>重复刷新幂等；上游读不到就 503，不返回空集合；DB 拒绝给派生指标 / 不可用指标落事实行。
 * </ul>
 *
 * <p>⚠️ **上游是真的 HTTP**：本测试起一个假的上游服务（channel），驱动
 * {@code ReportingAffiliateMarketingRefreshService} 的**真实**拉取与投影路径 ——
 * 端口写错了路径 / 字段名，在这一层就会暴露（写错字段名 ⇒ 那一类指标恒为 0）。
 *
 * <p>⚠️ **连接池固定为 1 条连接**：只有复用同一条连接，才能真正证明归还后不会串租户。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AffiliateMarketingIntegrationTest {

  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID tenant = UUID.randomUUID(), user = UUID.randomUUID();
  static final String BEARER = "Bearer tenant-a";

  static HttpServer identity;
  static HttpServer upstream;

  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  static final List<String> permissions =
      List.of(
          "reporting.affiliate-marketing.read",
          // ⚠️ 写权限必须在**同一个 grant** 里同时命中对应的 .read ——
          //   服务端的判据是「能写不能读」的组合不放行，所以这里必须带上 reporting.projection.read。
          "reporting.projection.read",
          "reporting.projection.write");

  static final ObjectMapper JSON = JsonMapper.builder().addModule(new JavaTimeModule()).build();

  // ==========================================================================
  // 夹具日期（**固定日历日** ⇒ 窗口边界是确定的，不依赖运行时刻）
  // ==========================================================================

  /** D1 = 2026-01-30（周五）。 */
  static final LocalDate D1 = LocalDate.of(2026, 1, 30);
  /** D2 = 2026-01-31（周六）。 */
  static final LocalDate D2 = LocalDate.of(2026, 1, 31);
  /** D3 = 2026-02-01（周日，**窗口外**）。 */
  static final LocalDate D3 = LocalDate.of(2026, 2, 1);

  static final UUID INFLUENCER_A = UUID.randomUUID();
  static final UUID INFLUENCER_B = UUID.randomUUID();
  static final UUID INFLUENCER_C = UUID.randomUUID();
  static final UUID CAMPAIGN_1 = UUID.randomUUID();
  static final UUID CAMPAIGN_2 = UUID.randomUUID();
  static final UUID LISTING_A = UUID.randomUUID();
  static final UUID LISTING_B = UUID.randomUUID();

  /**
   * 🔴 **归因夹具用 +08:00 的偏移时刻**：写库必须用 {@code OffsetDateTime} 绑定
   * （values 位置 {@code ?::timestamptz}）—— 用 {@code Timestamp.valueOf(LocalDateTime)}
   * 会静默偏 −28800s。写回后的**绝对时刻**必须逐字相等。
   */
  static final OffsetDateTime ATTR_1_AT = OffsetDateTime.parse("2026-01-30T18:00:00+08:00");

  /** ⚠️ 上游故障开关：打开后假上游一律 500，用来证「读不到 ≠ 没有数据」。 */
  static volatile boolean upstreamDown = false;

  // ==========================================================================
  // 夹具数据
  // ==========================================================================

  /** 一条归因记录（**带规则快照**）。 */
  static Map<String, Object> attribution(
      UUID influencerId,
      String influencerName,
      UUID campaignId,
      String campaignName,
      String ruleCode,
      String attributionModel,
      String poolWeightBasis,
      String currency,
      String attributedGmv,
      String orders,
      String commission,
      OffsetDateTime at) {
    var row = new LinkedHashMap<String, Object>();
    row.put("id", UUID.randomUUID());
    row.put("influencerId", influencerId);
    row.put("influencerName", influencerName);
    row.put("campaignId", campaignId);
    row.put("campaignName", campaignName);
    row.put("cooperationId", UUID.randomUUID());
    row.put("shopCode", "shop-a");
    row.put("ruleCode", ruleCode);
    row.put("attributionModel", attributionModel);
    row.put("poolWeightBasis", poolWeightBasis);
    row.put("attributionWindowDays", 7);
    row.put("refundTreatment", "GROSS");
    row.put("commissionBasis", "COOPERATION_COMMISSION");
    row.put("commissionRate", new BigDecimal("0.15"));
    row.put("attributionWeight", new BigDecimal("1.0"));
    row.put("attributedGmv", attributedGmv == null ? null : new BigDecimal(attributedGmv));
    row.put("attributedOrderCount", new BigDecimal(orders));
    row.put("effectiveOrderCount", new BigDecimal(orders));
    row.put("currency", currency);
    row.put("commissionAmount", commission == null ? null : new BigDecimal(commission));
    row.put("attributedAt", at == null ? null : at.toString());
    return row;
  }

  /**
   * 归因记录清单。
   *
   * <ul>
   *   <li>达人 A × 活动 1 · 规则 RULE-1：1000.00 USD 归因成交 / 10 单 / 100.00 USD 佣金 ⇒ ROI 10；
   *   <li>达人 A × 活动 1 · 规则 RULE-2：**同一个营销位、另一条规则** ⇒ 报表必须分两行；
   *       500.00 USD / 5 单 / 200.00 USD 佣金 ⇒ ROI 2.5（混算会得到 5.0 —— 第三个数）；
   *   <li>达人 B × 活动 2 · 规则 RULE-1：USD 与 EUR **各一条** ⇒ 绝不跨币种相加；
   *   <li>达人 C × 活动 2 · 规则 RULE-1：**没有币种** ⇒ 金额**不落库**（是 null，不是 0）；
   *   <li>达人 A × 活动 1 · 规则 RULE-1：D3（**窗口外**）⇒ 不进这个窗口。
   * </ul>
   */
  static final List<Map<String, Object>> ATTRIBUTIONS =
      List.of(
          attribution(
              INFLUENCER_A, "达人甲", CAMPAIGN_1, "春季活动", "RULE-1", "COOPERATION_FULL",
              "FOLLOWER_SHARE", "USD", "1000.00", "10", "100.00", ATTR_1_AT),
          attribution(
              INFLUENCER_A, "达人甲", CAMPAIGN_1, "春季活动", "RULE-2", "CAMPAIGN_POOL_SPLIT",
              "COOPERATION_GMV_SHARE", "USD", "500.00", "5", "200.00",
              OffsetDateTime.parse("2026-01-31T10:00:00Z")),
          attribution(
              INFLUENCER_B, "达人乙", CAMPAIGN_2, "夏季活动", "RULE-1", "COOPERATION_FULL",
              "FOLLOWER_SHARE", "USD", "200.00", "2", "25.00",
              OffsetDateTime.parse("2026-01-30T10:00:00Z")),
          attribution(
              INFLUENCER_B, "达人乙", CAMPAIGN_2, "夏季活动", "RULE-1", "COOPERATION_FULL",
              "FOLLOWER_SHARE", "EUR", "300.00", "3", "30.00",
              OffsetDateTime.parse("2026-01-30T11:00:00Z")),
          attribution(
              INFLUENCER_C, "达人丙", CAMPAIGN_2, "夏季活动", "RULE-1", "COOPERATION_FULL",
              "FOLLOWER_SHARE", null, "900.00", "9", "90.00",
              OffsetDateTime.parse("2026-01-30T12:00:00Z")),
          attribution(
              INFLUENCER_A, "达人甲", CAMPAIGN_1, "春季活动", "RULE-1", "COOPERATION_FULL",
              "FOLLOWER_SHARE", "USD", "777.00", "7", "77.00",
              OffsetDateTime.parse("2026-02-01T10:00:00Z")));

  /** 内容回写触发（**A+ 营销位存在的证据**）。 */
  static final List<Map<String, Object>> WRITEBACKS =
      List.of(
          Map.of(
              "writebackId", UUID.randomUUID(),
              "triggerKind", "SINGLE",
              "changeTypes", List.of("A_PLUS"),
              "listingIds", List.of(LISTING_A, LISTING_B)));

  /** 一行平台表现（listing 级，**落地商品口径**）。 */
  static Map<String, Object> performance(UUID listingId, String title, String statDate,
      String impressions, String clicks) {
    var row = new LinkedHashMap<String, Object>();
    row.put("listingId", listingId);
    row.put("externalItemId", "EXT-" + listingId.toString().substring(0, 8));
    row.put("title", title);
    row.put("shopCode", "shop-a");
    row.put("platformCode", "AMAZON");
    row.put("statDate", statDate);
    row.put("impressions", new BigDecimal(impressions));
    row.put("clicks", new BigDecimal(clicks));
    row.put("currency", "USD");
    return row;
  }

  /** 平台表现清单（只有 **LISTING_A 真的写过 A+** ⇒ 只有它成为 A+ 营销位）。 */
  static final List<Map<String, Object>> PERFORMANCES =
      List.of(
          performance(LISTING_A, "带 A+ 的商品", D1.toString(), "1000", "100"),
          performance(LISTING_A, "带 A+ 的商品", D2.toString(), "500", "60"),
          performance(LISTING_B, "没 A+ 的商品", D1.toString(), "9999", "9999"));

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
      upstream.createContext("/channel/attributions/records", e -> respond(e, attributionBody(e)));
      upstream.createContext("/channel/listing-content-writeback/writebacks",
          e -> respond(e, writebacksBody(e)));
      upstream.createContext("/channel/listing-content-writeback/listings",
          e -> respond(e, contentHistoryBody(e)));
      upstream.createContext("/channel/listing-performance", e -> respond(e, performanceBody(e)));
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
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  @Autowired TenantTransactions tenants;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;
  @Autowired ReportingAffiliateMarketingRefreshService refresh;
  @Autowired ReportingAffiliateMarketingService service;

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
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate reporting_affiliate_marketing_metric_definition,"
              + " reporting_affiliate_marketing_slot,"
              + " reporting_affiliate_marketing_fact,"
              + " reporting_affiliate_marketing_attribution_fact cascade");
    }
  }

  // ==========================================================================
  // 归因规则：必须显式，且规则一变历史不能跟着变
  // ==========================================================================

  @Test
  void 断言_同一个营销位用过两条规则时报表分两行且各带规则快照() {
    refresh.refresh(tenant, BEARER, D1, D2);
    var report = service.report(tenant, D1, D2);

    var rows = rowsOf(report, ReportingAffiliateMarketingCatalog.SlotType.SOCIAL_MEDIA);
    // 达人甲 × 活动 1 有两条规则 ⇒ **两行**；达人乙 × 活动 2 一条 ⇒ 一行；达人丙一条 ⇒ 一行。
    assertThat(rows).hasSize(4);

    var rule1 = rowOf(rows, INFLUENCER_A + ":" + CAMPAIGN_1, "RULE-1");
    var rule2 = rowOf(rows, INFLUENCER_A + ":" + CAMPAIGN_1, "RULE-2");
    assertThat(rule1).isNotNull();
    assertThat(rule2).isNotNull();
    // 🔴 每行都带自己的规则快照（**归因模型 / 分摊基准 / 归因窗口 / 退款口径 / 佣金口径**）。
    assertThat(rule1.attributionModel()).isEqualTo("COOPERATION_FULL");
    assertThat(rule1.poolWeightBasis()).isEqualTo("FOLLOWER_SHARE");
    assertThat(rule1.attributionWindowDays()).isEqualTo(7);
    assertThat(rule1.refundTreatment()).isEqualTo("GROSS");
    assertThat(rule1.commissionBasis()).isEqualTo("COOPERATION_COMMISSION");
    assertThat(rule2.attributionModel()).isEqualTo("CAMPAIGN_POOL_SPLIT");
    assertThat(rule2.poolWeightBasis()).isEqualTo("COOPERATION_GMV_SHARE");
  }

  @Test
  void 断言_ROI只在同一条规则内部相除() {
    refresh.refresh(tenant, BEARER, D1, D2);
    var report = service.report(tenant, D1, D2);
    var rows = rowsOf(report, ReportingAffiliateMarketingCatalog.SlotType.SOCIAL_MEDIA);

    // 规则一：1000.00 ÷ 100.00 = 10
    var rule1 = rowOf(rows, INFLUENCER_A + ":" + CAMPAIGN_1, "RULE-1");
    assertThat(metric(rule1, ReportingAffiliateMarketingCatalog.ROI, "USD").value())
        .isEqualByComparingTo("10");
    // 规则二：500.00 ÷ 200.00 = 2.5
    var rule2 = rowOf(rows, INFLUENCER_A + ":" + CAMPAIGN_1, "RULE-2");
    assertThat(metric(rule2, ReportingAffiliateMarketingCatalog.ROI, "USD").value())
        .isEqualByComparingTo("2.5");
    // 🔴 **混算会得到第三个数**（1500 ÷ 300 = 5）—— 报表里绝不能出现这个数。
    for (var row : rows) {
      for (var roi : row.metrics()) {
        if (!ReportingAffiliateMarketingCatalog.ROI.equals(roi.metricCode())) continue;
        if (roi.value() == null) continue;
        assertThat(roi.value()).as("混算两条规则的 ROI（5.0）不许出现在任何一行里")
            .isNotEqualByComparingTo("5");
      }
    }
  }

  @Test
  void 断言_归因事实逐字保存上游的规则快照而不是当前规则() {
    refresh.refresh(tenant, BEARER, D1, D2);
    var rule1 =
        tenants.run(
            tenant,
            () ->
                sql.fetch(
                        "select rule_code, attribution_model, pool_weight_basis, "
                            + "attribution_window_days, refund_treatment, commission_basis "
                            + "from reporting_affiliate_marketing_attribution_fact "
                            + "where tenant_id = ? and rule_code = ? limit 1",
                        tenant,
                        "RULE-1")
                    .get(0));
    assertThat(rule1.get(0, String.class)).isEqualTo("RULE-1");
    assertThat(rule1.get(1, String.class)).isEqualTo("COOPERATION_FULL");
    assertThat(rule1.get(2, String.class)).isEqualTo("FOLLOWER_SHARE");
    assertThat(rule1.get(3, Integer.class)).isEqualTo(7);
    assertThat(rule1.get(4, String.class)).isEqualTo("GROSS");
    assertThat(rule1.get(5, String.class)).isEqualTo("COOPERATION_COMMISSION");
  }

  // ==========================================================================
  // 币种
  // ==========================================================================

  @Test
  void 断言_绝不跨币种相加且ROI按同币种算() {
    refresh.refresh(tenant, BEARER, D1, D2);
    var report = service.report(tenant, D1, D2);
    var rows = rowsOf(report, ReportingAffiliateMarketingCatalog.SlotType.SOCIAL_MEDIA);

    var b = rowOf(rows, INFLUENCER_B + ":" + CAMPAIGN_2, "RULE-1");
    assertThat(b).isNotNull();
    // USD 与 EUR **分列**，各是各的。
    assertThat(metric(b, ReportingAffiliateMarketingCatalog.ATTRIBUTED_GMV, "USD").value())
        .isEqualByComparingTo("200");
    assertThat(metric(b, ReportingAffiliateMarketingCatalog.ATTRIBUTED_GMV, "EUR").value())
        .isEqualByComparingTo("300");
    // ROI 按**同币种**算：200 ÷ 25 = 8；300 ÷ 30 = 10。
    assertThat(metric(b, ReportingAffiliateMarketingCatalog.ROI, "USD").value())
        .isEqualByComparingTo("8");
    assertThat(metric(b, ReportingAffiliateMarketingCatalog.ROI, "EUR").value())
        .isEqualByComparingTo("10");
    // 归因订单数与货币无关 ⇒ 跨币种合计（2 + 3 = 5）。
    assertThat(metric(b, ReportingAffiliateMarketingCatalog.ATTRIBUTED_ORDERS, "").quantity())
        .isEqualByComparingTo("5");
  }

  @Test
  void 断言_缺币种的归因金额不落库而不是记成零() {
    refresh.refresh(tenant, BEARER, D1, D2);
    var report = service.report(tenant, D1, D2);
    var rows = rowsOf(report, ReportingAffiliateMarketingCatalog.SlotType.SOCIAL_MEDIA);

    var c = rowOf(rows, INFLUENCER_C + ":" + CAMPAIGN_2, "RULE-1");
    assertThat(c).isNotNull();
    // 🔴 上游没给币种 ⇒ 金额**不落库** ⇒ 那一格是「没有数据」，**不是 0**。
    var gmv = metric(c, ReportingAffiliateMarketingCatalog.ATTRIBUTED_GMV, "");
    assertThat(gmv.value()).isNull();
    assertThat(gmv.availability()).isEqualTo("NO_DATA");
    // 订单数与货币无关 ⇒ 照样落。
    assertThat(metric(c, ReportingAffiliateMarketingCatalog.ATTRIBUTED_ORDERS, "").quantity())
        .isEqualByComparingTo("9");

    // 库里那一行的金额必须是 **NULL**（**不是 0**，也不是 900）。
    var stored =
        tenants.run(
            tenant,
            () ->
                sql.fetch(
                        "select attributed_gmv, commission_amount "
                            + "from reporting_affiliate_marketing_attribution_fact "
                            + "where tenant_id = ? and currency = ''",
                        tenant)
                    .get(0));
    assertThat(stored.get(0, BigDecimal.class)).as("缺币种 ⇒ 归因金额必须是 NULL").isNull();
    assertThat(stored.get(1, BigDecimal.class)).as("缺币种 ⇒ 佣金金额必须是 NULL").isNull();
  }

  // ==========================================================================
  // 两套口径分列
  // ==========================================================================

  @Test
  void 断言_落地商品口径只给曝光点击且归因与ROI不可算() {
    refresh.refresh(tenant, BEARER, D1, D2);
    var report = service.report(tenant, D1, D2);
    var rows = rowsOf(report, ReportingAffiliateMarketingCatalog.SlotType.A_PLUS);

    assertThat(rows).as("只有真的写过 A+ 的 listing 才是 A+ 营销位").hasSize(1);
    var row = rows.get(0);
    assertThat(row.slotKey()).isEqualTo(LISTING_A.toString());
    assertThat(row.caliber()).isEqualTo("LANDING_LISTING");
    assertThat(metric(row, ReportingAffiliateMarketingCatalog.IMPRESSIONS, "").quantity())
        .isEqualByComparingTo("1500");
    assertThat(metric(row, ReportingAffiliateMarketingCatalog.CLICKS, "").quantity())
        .isEqualByComparingTo("160");
    assertThat(metric(row, ReportingAffiliateMarketingCatalog.CTR, "").quantity())
        .isEqualByComparingTo("0.1067");
    // 🔴 落地商品口径**没有**归因与投入 ⇒ ROI **不可算**（不是 0）。
    assertThat(metric(row, ReportingAffiliateMarketingCatalog.ROI, "").availability())
        .isEqualTo("NOT_AVAILABLE");
    assertThat(metric(row, ReportingAffiliateMarketingCatalog.ATTRIBUTED_GMV, "").availability())
        .isEqualTo("NOT_AVAILABLE");
  }

  @Test
  void 断言_没写过A加的listing不会成为营销位() {
    refresh.refresh(tenant, BEARER, D1, D2);
    var rows = rowsOf(service.report(tenant, D1, D2), ReportingAffiliateMarketingCatalog.SlotType.A_PLUS);
    assertThat(rows).noneSatisfy(row -> assertThat(row.slotKey()).isEqualTo(LISTING_B.toString()));
    // 它的落地页流量（9999 曝光）**不许**被算到别处去。
    var totalImpressions = 0L;
    for (var row : rows) {
      var value = metric(row, ReportingAffiliateMarketingCatalog.IMPRESSIONS, "").quantity();
      totalImpressions += value == null ? 0L : value.longValue();
    }
    assertThat(totalImpressions).isEqualTo(1500L);
  }

  @Test
  void 断言_达人营销位的曝光与点击本轮不可算而不是零() {
    refresh.refresh(tenant, BEARER, D1, D2);
    var rows = rowsOf(service.report(tenant, D1, D2), ReportingAffiliateMarketingCatalog.SlotType.SOCIAL_MEDIA);
    assertThat(rows).isNotEmpty();
    assertThat(rows)
        .allSatisfy(
            row -> {
              assertThat(metric(row, ReportingAffiliateMarketingCatalog.IMPRESSIONS, "").availability())
                  .isEqualTo("NOT_AVAILABLE");
              assertThat(metric(row, ReportingAffiliateMarketingCatalog.CLICKS, "").availability())
                  .isEqualTo("NOT_AVAILABLE");
              assertThat(metric(row, ReportingAffiliateMarketingCatalog.IMPRESSIONS, "").quantity())
                  .isNull();
            });
  }

  @Test
  void 断言_加购本轮不可用且写明了归谁管() {
    refresh.refresh(tenant, BEARER, D1, D2);
    var rows = rowsOf(service.report(tenant, D1, D2), ReportingAffiliateMarketingCatalog.SlotType.SOCIAL_MEDIA);
    var addToCart = metric(rows.get(0), ReportingAffiliateMarketingCatalog.ADD_TO_CART, "");
    assertThat(addToCart.availability()).isEqualTo("NOT_AVAILABLE");
    assertThat(addToCart.availabilityNote()).contains("本轮不可用").contains("不是 0");
  }

  @Test
  void 断言_未交付的营销位类型在能力声明里逐类登记() {
    var capabilities = service.capabilities(tenant);
    var relatedVideo =
        capabilities.slotTypes().stream()
            .filter(s -> "RELATED_VIDEO".equals(s.slotType()))
            .findFirst()
            .orElseThrow();
    assertThat(relatedVideo.available()).isFalse();
    assertThat(relatedVideo.owner()).contains("4.2.1.1");
    assertThat(relatedVideo.note()).contains("本轮不可用");

    var storeTraffic =
        capabilities.slotTypes().stream()
            .filter(s -> "STORE_TRAFFIC".equals(s.slotType()))
            .findFirst()
            .orElseThrow();
    assertThat(storeTraffic.available()).isFalse();

    var socialMedia =
        capabilities.slotTypes().stream()
            .filter(s -> "SOCIAL_MEDIA".equals(s.slotType()))
            .findFirst()
            .orElseThrow();
    assertThat(socialMedia.available()).isTrue();
    assertThat(socialMedia.caliber()).isEqualTo("INFLUENCER_ATTRIBUTION");
  }

  // ==========================================================================
  // 归因明细下钻
  // ==========================================================================

  @Test
  void 断言_归因明细可下钻且每行带规则快照() {
    refresh.refresh(tenant, BEARER, D1, D2);
    var page = service.attributions(tenant, D1, D2, null, null, 0, 50);

    // 窗口内 5 条（D3 那条在窗口外）。
    assertThat(page.total()).isEqualTo(5);
    assertThat(page.items()).hasSize(5);
    assertThat(page.items())
        .allSatisfy(
            row -> {
              assertThat(row.ruleCode()).isNotEmpty();
              assertThat(row.attributionModel()).isNotEmpty();
              assertThat(row.attributedAt()).isNotNull();
            });
    // 规则一与规则二都在明细里。
    assertThat(page.items()).extracting(r -> r.ruleCode()).contains("RULE-1", "RULE-2");
  }

  @Test
  void 断言_归因明细可按营销位类型与规则码筛选() {
    refresh.refresh(tenant, BEARER, D1, D2);
    var page = service.attributions(tenant, D1, D2, "SOCIAL_MEDIA", "RULE-2", 0, 50);
    assertThat(page.total()).isEqualTo(1);
    assertThat(page.items().get(0).ruleCode()).isEqualTo("RULE-2");
  }

  @Test
  void 断言_归因发生时刻按timestamptz逐字往返() {
    refresh.refresh(tenant, BEARER, D1, D2);
    var stored =
        tenants.run(
            tenant,
            () ->
                sql.fetch(
                        "select attributed_at from reporting_affiliate_marketing_attribution_fact "
                            + "where tenant_id = ? and influencer_id = ? order by attributed_at limit 1",
                        tenant,
                        INFLUENCER_A)
                    .get(0)
                    .get(0, OffsetDateTime.class));
    // 🔴 夹具是 +08:00 的 18:00 ⇒ 绝对时刻必须是 2026-01-30T10:00Z。
    //   用 Timestamp.valueOf(LocalDateTime) 会静默偏 −28800s ⇒ 这里会失败。
    assertThat(stored.toInstant()).isEqualTo(ATTR_1_AT.toInstant());
  }

  @Test
  void 断言_归因统计日按归因发生日的UTC自然日切桶() {
    refresh.refresh(tenant, BEARER, D1, D2);
    var statDates =
        tenants.run(
            tenant,
            () ->
                sql.fetch(
                        "select distinct stat_date from reporting_affiliate_marketing_attribution_fact "
                            + "where tenant_id = ? order by stat_date",
                        tenant)
                    .stream()
                    .map(r -> r.get(0, LocalDate.class))
                    .toList());
    // ATTR_1_AT = 2026-01-30T18:00+08:00 = 2026-01-30T10:00Z ⇒ UTC 自然日仍是 01-30。
    assertThat(statDates).containsExactly(LocalDate.of(2026, 1, 30), LocalDate.of(2026, 1, 31));
  }

  // ==========================================================================
  // 幂等 / 失败 / 数据库不变量
  // ==========================================================================

  @Test
  void 断言_重复刷新幂等不翻倍() {
    refresh.refresh(tenant, BEARER, D1, D2);
    var first = countFacts();
    refresh.refresh(tenant, BEARER, D1, D2);
    var second = countFacts();
    assertThat(second).isEqualTo(first);

    var report = service.report(tenant, D1, D2);
    var rows = rowsOf(report, ReportingAffiliateMarketingCatalog.SlotType.SOCIAL_MEDIA);
    var rule1 = rowOf(rows, INFLUENCER_A + ":" + CAMPAIGN_1, "RULE-1");
    assertThat(metric(rule1, ReportingAffiliateMarketingCatalog.ATTRIBUTED_GMV, "USD").value())
        .isEqualByComparingTo("1000");
  }

  @Test
  void 断言_窗口是重建不是叠加所以同一窗口不会翻倍() {
    // 先刷一个宽窗口（D3 那条也进来）。
    refresh.refresh(tenant, BEARER, D1, D3);
    var wide = service.report(tenant, D1, D3);
    var wideRule1 =
        rowOf(rowsOf(wide, ReportingAffiliateMarketingCatalog.SlotType.SOCIAL_MEDIA),
            INFLUENCER_A + ":" + CAMPAIGN_1, "RULE-1");
    // 1000.00（D1）+ 777.00（D3）
    assertThat(metric(wideRule1, ReportingAffiliateMarketingCatalog.ATTRIBUTED_GMV, "USD").value())
        .isEqualByComparingTo("1777.00");

    // 再刷同一个宽窗口 ⇒ **重建**（先删窗口内旧行再整段重写），绝不翻倍。
    refresh.refresh(tenant, BEARER, D1, D3);
    var again = service.report(tenant, D1, D3);
    var againRule1 =
        rowOf(rowsOf(again, ReportingAffiliateMarketingCatalog.SlotType.SOCIAL_MEDIA),
            INFLUENCER_A + ":" + CAMPAIGN_1, "RULE-1");
    assertThat(metric(againRule1, ReportingAffiliateMarketingCatalog.ATTRIBUTED_GMV, "USD").value())
        .isEqualByComparingTo("1777.00");
  }

  @Test
  void 断言_上游读不到就503而不是空集合() {
    upstreamDown = true;
    assertThrows(
        org.springframework.web.server.ResponseStatusException.class,
        () -> refresh.refresh(tenant, BEARER, D1, D2));
    assertThat(countFacts()).isZero();
  }

  @Test
  void 断言_数据库拒绝对派生指标落事实行() {
    refresh.refresh(tenant, BEARER, D1, D2);
    var error =
        assertThrows(
            org.springframework.dao.DataIntegrityViolationException.class,
            () ->
                tenants.run(
                    tenant,
                    () ->
                        sql.execute(
                            """
                            insert into reporting_affiliate_marketing_fact
                              (id,tenant_id,slot_type,slot_key,metric_code,stat_date,currency,value,quantity,
                               attribution_rule_code,source_service,source_reference,updated_at)
                            values (gen_random_uuid(),?,'SOCIAL_MEDIA','x',?,?,'USD',1,0,'RULE-1','channel','x',now())
                            """,
                            tenant,
                            ReportingAffiliateMarketingCatalog.ROI,
                            D1)));
    assertThat(rootMessage(error)).contains("派生指标");
  }

  @Test
  void 断言_数据库拒绝给不可用指标落事实行() {
    refresh.refresh(tenant, BEARER, D1, D2);
    var error =
        assertThrows(
            org.springframework.dao.DataIntegrityViolationException.class,
            () ->
                tenants.run(
                    tenant,
                    () ->
                        sql.execute(
                            """
                            insert into reporting_affiliate_marketing_fact
                              (id,tenant_id,slot_type,slot_key,metric_code,stat_date,currency,value,quantity,
                               attribution_rule_code,source_service,source_reference,updated_at)
                            values (gen_random_uuid(),?,'SOCIAL_MEDIA','x',?,?,'',0,3,'','channel','x',now())
                            """,
                            tenant,
                            ReportingAffiliateMarketingCatalog.ADD_TO_CART,
                            D1)));
    assertThat(rootMessage(error)).contains("本轮不可用");
  }

  @Test
  void 断言_数据库拒绝币种形状不对的事实行() {
    refresh.refresh(tenant, BEARER, D1, D2);
    var error =
        assertThrows(
            org.springframework.dao.DataIntegrityViolationException.class,
            () ->
                tenants.run(
                    tenant,
                    () ->
                        sql.execute(
                            """
                            insert into reporting_affiliate_marketing_fact
                              (id,tenant_id,slot_type,slot_key,metric_code,stat_date,currency,value,quantity,
                               attribution_rule_code,source_service,source_reference,updated_at)
                            values (gen_random_uuid(),?,'SOCIAL_MEDIA','x',?,?,'',100,0,'RULE-1','channel','x',now())
                            """,
                            tenant,
                            ReportingAffiliateMarketingCatalog.ATTRIBUTED_GMV,
                            D1)));
    assertThat(rootMessage(error)).contains("币种");
  }

  @Test
  void 断言_口径表按租户播种() {
    var definitions = service.metricDefinitions(tenant);
    assertThat(definitions).isNotEmpty();
    assertThat(definitions)
        .extracting(d -> d.metricCode())
        .contains(
            ReportingAffiliateMarketingCatalog.IMPRESSIONS,
            ReportingAffiliateMarketingCatalog.ATTRIBUTED_GMV,
            ReportingAffiliateMarketingCatalog.ROI);
    // 派生指标在口径表里**只写引用**。
    var roi =
        definitions.stream()
            .filter(d -> ReportingAffiliateMarketingCatalog.ROI.equals(d.metricCode()))
            .findFirst()
            .orElseThrow();
    assertThat(roi.aggregation()).isEqualTo("DERIVED_RATIO");
    assertThat(roi.numeratorMetricCode()).isEqualTo(ReportingAffiliateMarketingCatalog.ATTRIBUTED_GMV);
    assertThat(roi.denominatorMetricCode()).isEqualTo(ReportingAffiliateMarketingCatalog.SPEND);
  }

  @Test
  void 断言_端点可用且租户不来自请求头() throws Exception {
    refresh.refresh(tenant, BEARER, D1, D2);
    http.perform(get("/reporting/affiliate-marketing").header("Authorization", BEARER))
        .andExpect(status().isOk());
    http.perform(get("/reporting/affiliate-marketing/capabilities").header("Authorization", BEARER))
        .andExpect(status().isOk());
    http.perform(get("/reporting/affiliate-marketing/metrics").header("Authorization", BEARER))
        .andExpect(status().isOk());
    http.perform(get("/reporting/affiliate-marketing/attributions").header("Authorization", BEARER))
        .andExpect(status().isOk());
  }

  // ==========================================================================
  // 工具
  // ==========================================================================

  /** 异常链上的**根因消息**（Spring 会把 PostgreSQL 的原文包在 cause 里）。 */
  private static String rootMessage(Throwable error) {
    var current = error;
    while (current.getCause() != null) current = current.getCause();
    return String.valueOf(current.getMessage());
  }

  private int countFacts() {
    return tenants.run(
        tenant,
        () ->
            sql.fetch("select id from reporting_affiliate_marketing_fact").size()
                + sql.fetch("select id from reporting_affiliate_marketing_attribution_fact").size());
  }

  private List<ReportingAffiliateMarketingModels.AffiliateMarketingSlotRow> rowsOf(
      ReportingAffiliateMarketingModels.AffiliateMarketingResponse report,
      ReportingAffiliateMarketingCatalog.SlotType slotType) {
    return report.slots().stream().filter(r -> slotType.name().equals(r.slotType())).toList();
  }

  private ReportingAffiliateMarketingModels.AffiliateMarketingSlotRow rowOf(
      List<ReportingAffiliateMarketingModels.AffiliateMarketingSlotRow> rows,
      String slotKey,
      String ruleCode) {
    return rows.stream()
        .filter(r -> slotKey.equals(r.slotKey()) && ruleCode.equals(r.attributionRuleCode()))
        .findFirst()
        .orElse(null);
  }

  private ReportingAffiliateMarketingModels.AffiliateMarketingMetricValue metric(
      ReportingAffiliateMarketingModels.AffiliateMarketingSlotRow row,
      String metricCode,
      String currency) {
    return row.metrics().stream()
        .filter(m -> metricCode.equals(m.metricCode()))
        .filter(m -> currency.equals(m.currency()))
        .findFirst()
        .orElseThrow(
            () ->
                new AssertionError(
                    "指标 " + metricCode + "（币种 " + currency + "）不在这一行里："
                        + row.metrics()));
  }

  // ---------------------------------------------------------------------------
  // 假上游
  // ---------------------------------------------------------------------------

  private static Object attributionBody(com.sun.net.httpserver.HttpExchange e)
      throws java.io.IOException {
    if (upstreamDown) return fail(e);
    var page = new LinkedHashMap<String, Object>();
    page.put("items", ATTRIBUTIONS);
    page.put("page", 0);
    page.put("size", 200);
    page.put("total", ATTRIBUTIONS.size());
    page.put("caliberNote", "口径说明");
    page.put("commissionNote", "佣金说明");
    return page;
  }

  private static Object writebacksBody(com.sun.net.httpserver.HttpExchange e)
      throws java.io.IOException {
    if (upstreamDown) return fail(e);
    return WRITEBACKS;
  }

  /** 内容回写历史：只有 LISTING_A 真的被写过 A+。 */
  private static Object contentHistoryBody(com.sun.net.httpserver.HttpExchange e)
      throws java.io.IOException {
    if (upstreamDown) return fail(e);
    // 路径形状：/channel/listing-content-writeback/listings/{listingId}/content-history
    var path = e.getRequestURI().getPath();
    var withoutSuffix = path.substring(0, path.length() - "/content-history".length());
    var listingId = withoutSuffix.substring(withoutSuffix.lastIndexOf('/') + 1);
    var entry = new LinkedHashMap<String, Object>();
    entry.put("writebackId", UUID.randomUUID());
    entry.put("triggerState", "SUCCEEDED");
    entry.put("createdAt", "2026-01-20T10:00:00Z");
    entry.put("taskState", "SUCCEEDED");
    if (LISTING_A.toString().equals(listingId)) {
      entry.put("changes", List.of(Map.of("field", "A_PLUS")));
    } else {
      entry.put("changes", List.of(Map.of("field", "TITLE")));
    }
    return List.of(entry);
  }

  private static Object performanceBody(com.sun.net.httpserver.HttpExchange e)
      throws java.io.IOException {
    if (upstreamDown) return fail(e);
    var page = new LinkedHashMap<String, Object>();
    page.put("items", PERFORMANCES);
    page.put("page", 0);
    page.put("size", 200);
    page.put("total", PERFORMANCES.size());
    page.put("syncCount", PERFORMANCES.size());
    return page;
  }

  /** 上游故障 ⇒ 500（本模块的端口会把它翻成 503）。 */
  private static Object fail(com.sun.net.httpserver.HttpExchange e) throws java.io.IOException {
    e.sendResponseHeaders(500, -1);
    e.close();
    return null;
  }

  private static void respond(com.sun.net.httpserver.HttpExchange e, Object body)
      throws java.io.IOException {
    if (body == null) return;
    byte[] bytes = JSON.writeValueAsBytes(body);
    e.getResponseHeaders().add("Content-Type", "application/json");
    e.sendResponseHeaders(200, bytes.length);
    e.getResponseBody().write(bytes);
    e.close();
  }
}
