package com.starcon.channel;

import static com.starcon.channel.AdCampaignModels.*;
import static com.starcon.channel.AdCampaignReportModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 14.3.1.1 活动报表。
 *
 * <p>本测试类钉住本模块最容易做丢的几件事：
 *
 * <ol>
 *   <li><b>多租户三断言</b>（AGENTS.md 硬性要求，缺一不可）：两租户互不可见、伪造 tenant header 无效、
 *       连接池归还后不串租户
 *   <li><b>🔴 汇总先汇总再相除</b>：不是各行比率的平均（两条 ACOS 100% / 50% 平均成 75%，
 *       而整体其实是 27.5% —— 这就是数学错误长什么样）
 *   <li><b>🔴 分母为 0 给空值</b>：不可比 ≠ 0
 *   <li><b>🔴 TACOS 没有分母就不出数值</b>：能力声明里显式 available=false + ownerModule 指向 13.4
 *   <li><b>🔴 不同币种不合并</b>：没有汇率源 ⇒ 汇总按币种拆开
 *   <li><b>🔴 时间范围必填 + 跨度封顶</b>：不给区间直接 400，不做「悄悄截断」
 *   <li><b>🔴 真分页真聚合</b>：分页真的分页，汇总统计的是全集而不是当前页
 *   <li><b>🔴 导出口径写进表头 + 导出留痕</b>；留痕里的 timestamptz <b>写回值</b>逐字相等
 *       （AGENTS.md 要求断言写回值，不能只断言没抛异常）
 *   <li><b>三种「空」分开说</b>：没同步到数据 / 这个区间没有指标 / 被筛掉了
 * </ol>
 *
 * <p><b>时刻一律用固定的 {@link OffsetDateTime} 钉死</b>，不用 {@code now()}：用墙钟会让
 * 「这次写入落在哪个断言分支上」成为一个 flaky 断言。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AdCampaignReportIntegrationTest {

  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID user = UUID.randomUUID();
  static HttpServer identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  /** 🔴 每个用例一对**全新的租户**（共用租户会让前一个用例的数据被后一个用例数进去）。 */
  UUID a, b;
  AccessService.Access actorA, actorB;
  String bearerA;

  /** 🔴 钉死的时刻。 */
  static final OffsetDateTime DATA_AS_OF = OffsetDateTime.parse("2026-10-01T09:00:00+08:00");
  static final OffsetDateTime STARTED_AT = OffsetDateTime.parse("2026-10-01T08:00:00+08:00");
  static final OffsetDateTime FINISHED_AT = OffsetDateTime.parse("2026-10-01T09:05:00+08:00");

  /** 2026-10-01 是周四；那一周的周一是 2026-09-28（date_trunc('week') 的起算）。 */
  static final LocalDate DAY_1 = LocalDate.of(2026, 10, 1);
  static final LocalDate DAY_2 = LocalDate.of(2026, 10, 2);
  static final LocalDate WEEK_START = LocalDate.of(2026, 9, 28);
  static final LocalDate MONTH_START = LocalDate.of(2026, 10, 1);

  // 🔴 用 V1 平台目录里**真实存在**的 id：...-101 = AMAZON、...-104 = TIKTOK_SHOP。
  //   （...-111 是 ETSY —— 抄错会让「按平台分组」拿到 Etsy 这个名字。）
  static final UUID AMAZON = UUID.fromString("11111111-1111-1111-1111-111111111101");
  static final UUID TIKTOK = UUID.fromString("11111111-1111-1111-1111-111111111104");
  static final String ALL_SCOPE_GRANTS =
      "[{\"permissions\":[\"channel.operation.read\",\"channel.operation.create\","
          + "\"channel.operation.update\",\"channel.operation.delete\"],\"scopeMode\":\"ALL\"}]";

  static final List<String> allPermissions =
      java.util.stream.Stream.of("registry", "connection", "shop", "sync", "operation")
          .flatMap(
              p ->
                  java.util.stream.Stream.concat(
                      java.util.stream.Stream.of("read", "create", "update", "delete")
                          .map(op -> "channel." + p + "." + op),
                      p.equals("sync")
                          ? java.util.stream.Stream.of("channel.sync.trigger", "channel.sync.complete")
                          : java.util.stream.Stream.empty()))
          .distinct()
          .toList();

  static AccessService.Access actor(UUID uid, UUID tenant, List<AccessService.Grant> grants) {
    return new AccessService.Access(
        uid, tenant, "Test", UUID.randomUUID(), null,
        grants.stream().flatMap(g -> g.permissions().stream()).distinct().toList(),
        grants);
  }

  static AccessService.Grant grant(String scope, List<String> permissions) {
    return new AccessService.Grant(UUID.randomUUID(), "TEST", scope, permissions);
  }

  static {
    pg.start();
    try {
      try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
          var s = c.createStatement()) {
        s.execute("do $$ begin if not exists(select 1 from pg_roles where rolname='starcon_app')"
            + " then create role starcon_app nologin; end if; end $$");
        s.execute("do $$ begin if not exists(select 1 from pg_roles where rolname='channel_app')"
            + " then create role channel_app login password 'test-only' nosuperuser nobypassrls; end if; end $$");
        s.execute("alter role channel_app login password 'test-only' nosuperuser nobypassrls");
      }
      identity = HttpServer.create(new InetSocketAddress(0), 0);
      identity.createContext(
          "/internal/access",
          e -> {
            AccessService.Access who = actors.get(e.getRequestHeaders().getFirst("Authorization"));
            if (who == null) {
              e.sendResponseHeaders(401, -1);
              e.close();
              return;
            }
            byte[] body = new ObjectMapper().writeValueAsBytes(who);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      List<AccessService.Grant> grants = List.of(grant("ALL", allPermissions));
      identity.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @BeforeEach
  void freshTenants() {
    a = UUID.randomUUID();
    b = UUID.randomUUID();
    bearerA = "Bearer t-a-" + a;
    String bearerB = "Bearer t-b-" + b;
    List<AccessService.Grant> grants = List.of(grant("ALL", allPermissions));
    actors.put(bearerA, actor(user, a, grants));
    actors.put(bearerB, actor(user, b, grants));
    actorA = actors.get(bearerA);
    actorB = actors.get(bearerB);
  }

  /**
   * 播种平台默认映射（全局码表）。
   *
   * <p>🔴 平台目录（{@code platform}）本身由 V1 播种，**本测试不自己插** ——
   * 插的话会撞上 V1 的 code 唯一约束，而且平台 id 抄错会让「按平台分组」拿到别的平台名字。
   */
  @BeforeEach
  void seedGlobalCatalog() {
    exec("insert into platform_enum_mapping (id,platform_id,enum_kind,external_value,internal_value)"
        + " values ('" + UUID.randomUUID() + "','" + AMAZON + "','AD_TYPE','sponsored_products','AUTO_DISCOVERY')"
        + " on conflict (platform_id,enum_kind,external_value) do nothing");
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "channel_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://localhost:" + identity.getAddress().getPort());
    r.add("CATALOG_URL", () -> "http://127.0.0.1:1");
    r.add("INTERNAL_KEY", () -> "test-key");
    // 连接池压到 1：多租户串租户在单连接下最容易暴露。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> "1");
  }

  @Autowired AdCampaignReportService service;
  @Autowired AdCampaignService campaigns;
  @Autowired AdTypeDiffService adTypes;
  @Autowired AccessService access;
  @Autowired MockMvc http;

  // ==================================================================
  // 辅助
  // ==================================================================

  <T> T as(AccessService.Access who, String permission, java.util.function.Supplier<T> work) {
    return access.run(who, permission, work);
  }

  /** 以管理员（超级用户，RLS 不生效）连接执行 SQL。 */
  static void exec(String sql) {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(sql);
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  /** 以「已认证的租户操作者」身份执行 SQL：绑定租户 + actor + 权限 + 数据范围。 */
  static void execAsTenant(UUID tenant, String sql) {
    try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute("select set_config('app.current_tenant','" + tenant + "',false)");
      s.execute("select set_config('app.actor','" + user + "',false)");
      s.execute("select set_config('app.department','',false)");
      s.execute("select set_config('app.permission','channel.operation.create',false)");
      s.execute("select set_config('app.grants','" + ALL_SCOPE_GRANTS + "',false)");
      s.execute(sql);
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  /** 播种一个店铺（幂等）。 */
  String seedShop(UUID tenant, UUID platformId, String code) {
    UUID id = UUID.randomUUID();
    execAsTenant(tenant,
        "insert into shop (tenant_id,id,platform_id,code,name) values ('" + tenant + "','" + id
            + "','" + platformId + "','" + code + "','测试店铺 " + code + "')"
            + " on conflict (tenant_id,code) do nothing");
    return code;
  }

  /** 通过**上游 14.1.2.2** 登记一个广告类型档案（归一口径由它带下来）。 */
  UUID saveProfile(AccessService.Access who, UUID platformId, String external, String internal) {
    return as(who, "channel.operation.create",
        () -> adTypes.saveProfile(new AdTypeDiffModels.SaveProfileRequest(
            null, platformId, external, internal, external + " 展示名", "CPC", "SALES",
            true, true, true, true, false,
            "UNMAPPED".equals(internal) ? "UNMAPPED" : "PLATFORM_DEFAULT", "测试登记")))
        .id();
  }

  ImportDailyMetric metric(LocalDate date, String impressions, String clicks, String orders,
      String spend, String sales) {
    return new ImportDailyMetric(
        date, new BigDecimal(impressions), new BigDecimal(clicks), new BigDecimal(orders),
        new BigDecimal(spend), new BigDecimal(sales));
  }

  /** 通过**上游 14.1.1.1** 登记一条广告活动与它的每日指标（本模块的事实来源）。 */
  void importCampaign(
      AccessService.Access who, UUID platformId, String shop, UUID profileId, String externalId,
      String name, String currency, List<ImportDailyMetric> metrics) {
    ImportCampaign campaign =
        new ImportCampaign(
            externalId, name, profileId, "ENABLED", currency,
            new BigDecimal("100.0000"), null, null, metrics, "测试登记");
    ImportRequest request =
        new ImportRequest(
            platformId, shop,
            new ImportSyncRun("SUCCESS", DATA_AS_OF, STARTED_AT, FINISHED_AT, "", "batch-report"),
            List.of(campaign));
    as(who, "channel.operation.create", () -> campaigns.importCampaigns(request));
  }

  /** 跑一次报表查询（走服务层的归一化与校验）。 */
  AdCampaignReportPage report(
      AccessService.Access who, String granularity, String dimension, LocalDate from, LocalDate to,
      int page, int size) {
    return as(who, "channel.operation.read",
        () -> service.report(
            service.normalize(from, to, granularity, dimension, "SPEND_DESC", null, null, null,
                null, page, size)));
  }

  /** 跑一次趋势查询。 */
  AdCampaignReportTrend trend(
      AccessService.Access who, String granularity, LocalDate from, LocalDate to) {
    return as(who, "channel.operation.read",
        () -> service.trend(
            service.normalize(from, to, granularity, dimensionOf(), "SPEND_DESC", null, null, null,
                null, 0, 50)));
  }

  static String dimensionOf() {
    return "CAMPAIGN";
  }

  /** 播种一条标准的三活动数据集（租户 a 的「报表活动 1 / 2 / 零值活动」）。 */
  void seedStandard(AccessService.Access who, UUID tenant, String shopPrefix) {
    String shop = seedShop(tenant, AMAZON, shopPrefix);
    UUID profile = saveProfile(who, AMAZON, "sponsored_products", "AUTO_DISCOVERY");
    importCampaign(who, AMAZON, shop, profile, "EXT-REP-1", "报表活动 1", "USD",
        List.of(metric(DAY_1, "1000", "50", "5", "120.0000", "600.0000")));
    importCampaign(who, AMAZON, shop, profile, "EXT-REP-2", "报表活动 2", "USD",
        List.of(metric(DAY_1, "500", "25", "2", "100.0000", "200.0000")));
    importCampaign(who, AMAZON, shop, profile, "EXT-REP-3", "零值活动", "USD",
        List.of(metric(DAY_1, "0", "0", "0", "0.0000", "0.0000")));
  }

  // ==================================================================
  // 1. 能力声明：TACOS 与跨币种统一必须显式未交付
  // ==================================================================

  @Test
  @DisplayName("14.3.1.1 能力声明：TACOS / 跨币种统一 / 关键词维度显式 available=false")
  void capabilitiesDeclareUndeliveredParts() {
    var caps = as(actorA, "channel.operation.read", () -> service.capabilities());
    Map<String, AdReportCapabilityDimension> dims =
        caps.dimensions().stream()
            .collect(java.util.stream.Collectors.toMap(AdReportCapabilityDimension::dimension, d -> d));

    assertThat(dims.get("campaign_report").available()).isTrue();
    assertThat(dims.get("granularity").available()).isTrue();
    assertThat(dims.get("multi_dimension").available()).isTrue();
    assertThat(dims.get("trend").available()).isTrue();
    assertThat(dims.get("export").available()).isTrue();

    // 🔴 TACOS：总销售额口径（13.4）未接入 ⇒ 没有分母 ⇒ 不出数值。
    assertThat(dims.get("tacos").available()).isFalse();
    assertThat(dims.get("tacos").ownerModule()).contains("13.4");
    assertThat(dims.get("tacos").impact()).contains("不出 TACOS 数值");

    // 🔴 跨币种统一：本仓没有汇率源。
    assertThat(dims.get("multi_currency_unification").available()).isFalse();
    assertThat(dims.get("multi_currency_unification").impact()).contains("按币种拆开");

    // 🔴 关键词级 / 商品级 / 归因都归别的模块，不许在这里假装已交付。
    assertThat(dims.get("ad_group_keyword_report").available()).isFalse();
    assertThat(dims.get("ad_group_keyword_report").ownerModule()).contains("14.3.1.2");
    assertThat(dims.get("product_dimension_report").available()).isFalse();
    assertThat(dims.get("attribution").available()).isFalse();

    // 🔴 三个口径的定义随能力声明一起返回（表头照它渲染）。
    Map<String, AdReportMetricDefinition> metrics =
        caps.metricDefinitions().stream()
            .collect(java.util.stream.Collectors.toMap(AdReportMetricDefinition::metric, m -> m));
    assertThat(metrics.get("acos").available()).isTrue();
    assertThat(metrics.get("acos").formula()).contains("ACOS = Σ花费 / Σ广告销售额");
    assertThat(metrics.get("roas").formula()).contains("ROAS = Σ广告销售额 / Σ花费");
    assertThat(metrics.get("tacos").available()).isFalse();
    assertThat(metrics.get("tacos").formula()).contains("TACOS = 广告花费 / 总销售额");
    assertThat(metrics.get("tacos").unavailableReason()).contains("未接入");
    // 🔴 CTR / CPC / 转化率也是职责句点名的指标，一个都不能少。
    assertThat(metrics).containsKeys("spend", "impressions", "clicks", "ctr", "cpc", "orders",
        "cvr", "sales", "acos", "roas", "tacos");
  }

  // ==================================================================
  // 2. 报表真的出数
  // ==================================================================

  @Test
  @DisplayName("14.3.1.1 报表真的落库出数：十项指标齐全且口径正确")
  void reportReadsRealMetrics() {
    seedStandard(actorA, a, "REP-AMZ-1");

    var page = report(actorA, "DAY", "CAMPAIGN", DAY_1, DAY_1, 0, 50);

    assertThat(page.total()).isEqualTo(3);
    assertThat(page.items()).hasSize(3);

    AdCampaignReportRow row =
        page.items().stream()
            .filter(r -> "报表活动 1".equals(r.dimensionLabel()))
            .findFirst()
            .orElseThrow();

    assertThat(row.dimension()).isEqualTo("CAMPAIGN");
    assertThat(row.currency()).isEqualTo("USD");
    assertThat(row.impressions()).isEqualByComparingTo("1000");
    assertThat(row.clicks()).isEqualByComparingTo("50");
    assertThat(row.orders()).isEqualByComparingTo("5");
    assertThat(row.spend()).isEqualByComparingTo("120.0000");
    assertThat(row.sales()).isEqualByComparingTo("600.0000");
    // 🔴 CTR = 50/1000 = 0.05；CPC = 120/50 = 2.4；转化率 = 5/50 = 0.1
    assertThat(row.ctr()).isEqualByComparingTo("0.05");
    assertThat(row.cpc()).isEqualByComparingTo("2.4");
    assertThat(row.cvr()).isEqualByComparingTo("0.1");
    // 🔴 ACOS = 120/600 = 0.2；ROAS = 600/120 = 5
    assertThat(row.acos()).isEqualByComparingTo("0.2");
    assertThat(row.roas()).isEqualByComparingTo("5");
    assertThat(row.metricFrom()).isEqualTo(DAY_1);
    assertThat(row.metricTo()).isEqualTo(DAY_1);
  }

  // ==================================================================
  // 3. 🔴 汇总先汇总再相除（不是比率的平均）
  // ==================================================================

  @Test
  @DisplayName("14.3.1.1 汇总先汇总再相除：整体 ACOS 是 27.5% 而不是各行平均的 35%")
  void summaryAggregatesBeforeDividing() {
    seedStandard(actorA, a, "REP-AMZ-1");

    var page = report(actorA, "DAY", "CAMPAIGN", DAY_1, DAY_1, 0, 50);

    assertThat(page.summaries()).hasSize(1);
    AdCampaignReportSummary sum = page.summaries().get(0);
    assertThat(sum.currency()).isEqualTo("USD");
    assertThat(sum.groupCount()).isEqualTo(3);
    assertThat(sum.totalSpend()).isEqualByComparingTo("220.0000");
    assertThat(sum.totalSales()).isEqualByComparingTo("800.0000");
    // 🔴 Σ消耗 / Σ销售额 = 220/800 = 0.275；
    //   各行 ACOS（0.2 / 0.5 / 不可比）的平均是 0.35 —— 那是数学错误。
    assertThat(sum.acos()).isEqualByComparingTo("0.275");
    assertThat(sum.roas()).isEqualByComparingTo("3.636364");
    // Σ点击 / Σ曝光 = 75/1500 = 0.05；Σ花费 / Σ点击 = 220/75；Σ订单 / Σ点击 = 7/75
    assertThat(sum.ctr()).isEqualByComparingTo("0.05");
    assertThat(sum.cpc()).isEqualByComparingTo("2.933333");
    assertThat(sum.cvr()).isEqualByComparingTo("0.093333");
    assertThat(sum.metricDefinitionNote()).contains("先汇总再相除");
  }

  // ==================================================================
  // 4. 🔴 分母为 0 给空值（不可比），不是 0
  // ==================================================================

  @Test
  @DisplayName("14.3.1.1 分母为 0 的比率返回空值（不可比），不是 0")
  void zeroDenominatorGivesNullNotZero() {
    seedStandard(actorA, a, "REP-AMZ-1");

    var page = report(actorA, "DAY", "CAMPAIGN", DAY_1, DAY_1, 0, 50);
    AdCampaignReportRow zero =
        page.items().stream()
            .filter(r -> "零值活动".equals(r.dimensionLabel()))
            .findFirst()
            .orElseThrow();

    // 🔴 null = 不可比（分母为 0）；0 = 可比但真的是零。两者含义相反。
    assertThat(zero.ctr()).isNull();
    assertThat(zero.cpc()).isNull();
    assertThat(zero.cvr()).isNull();
    assertThat(zero.acos()).isNull();
    assertThat(zero.roas()).isNull();
    // 花费与销售额是真的 0（可比），不是空。
    assertThat(zero.spend()).isEqualByComparingTo("0");
    assertThat(zero.sales()).isEqualByComparingTo("0");
  }

  // ==================================================================
  // 5. 🔴 TACOS 不出数值
  // ==================================================================

  @Test
  @DisplayName("14.3.1.1 TACOS 没有分母：不出数值，也不用广告销售额顶替")
  void tacosHasNoValue() {
    seedStandard(actorA, a, "REP-AMZ-1");

    var page = report(actorA, "DAY", "CAMPAIGN", DAY_1, DAY_1, 0, 50);

    // 🔴 表头（metricDefinitions）把 TACOS 显式标成不可用并说清原因。
    AdReportMetricDefinition tacos =
        page.metricDefinitions().stream()
            .filter(m -> "tacos".equals(m.metric()))
            .findFirst()
            .orElseThrow();
    assertThat(tacos.available()).isFalse();
    assertThat(tacos.unavailableReason()).contains("未接入");

    // 🔴 口径说明里也点明「当前不出 TACOS 数值」。
    assertThat(page.metricDefinitionNote()).contains("TACOS");
    assertThat(page.metricDefinitionNote()).contains("不出 TACOS 数值");
  }

  // ==================================================================
  // 6. 🔴 不同币种不合并
  // ==================================================================

  @Test
  @DisplayName("14.3.1.1 不同币种不合并：汇总按币种拆开")
  void currenciesAreNotMixed() {
    String shop = seedShop(a, AMAZON, "REP-AMZ-CUR");
    UUID profile = saveProfile(actorA, AMAZON, "sponsored_products", "AUTO_DISCOVERY");
    importCampaign(actorA, AMAZON, shop, profile, "EXT-CUR-USD", "美元活动", "USD",
        List.of(metric(DAY_1, "100", "10", "1", "100.0000", "200.0000")));
    importCampaign(actorA, AMAZON, shop, profile, "EXT-CUR-EUR", "欧元活动", "EUR",
        List.of(metric(DAY_1, "100", "10", "1", "50.0000", "100.0000")));

    var page = report(actorA, "DAY", "CAMPAIGN", DAY_1, DAY_1, 0, 50);

    // 🔴 汇总按币种拆成两份 —— 没有汇率源，把 100 USD 与 50 EUR 加起来就是假总数。
    assertThat(page.summaries()).hasSize(2);
    AdCampaignReportSummary usd =
        page.summaries().stream().filter(s -> "USD".equals(s.currency())).findFirst().orElseThrow();
    AdCampaignReportSummary eur =
        page.summaries().stream().filter(s -> "EUR".equals(s.currency())).findFirst().orElseThrow();
    assertThat(usd.totalSpend()).isEqualByComparingTo("100");
    assertThat(eur.totalSpend()).isEqualByComparingTo("50");
    // 🔴 没有任何一份汇总是 150。
    assertThat(page.summaries())
        .extracting(AdCampaignReportSummary::totalSpend)
        .noneMatch(v -> v.compareTo(new BigDecimal("150")) == 0);
  }

  // ==================================================================
  // 7. 🔴 时间范围必填 + 跨度封顶
  // ==================================================================

  @Test
  @DisplayName("14.3.1.1 时间范围必填：不给区间直接 400（不做默认区间）")
  void dateRangeIsRequired() {
    seedStandard(actorA, a, "REP-AMZ-1");

    assertThatThrownBy(
            () ->
                as(actorA, "channel.operation.read",
                    () ->
                        service.report(
                            service.normalize(null, null, "DAY", "CAMPAIGN", "SPEND_DESC", null,
                                null, null, null, 0, 50))))
        .hasMessageContaining("必填");

    assertThatThrownBy(
            () ->
                as(actorA, "channel.operation.read",
                    () ->
                        service.report(
                            service.normalize(DAY_2, DAY_1, "DAY", "CAMPAIGN", "SPEND_DESC", null,
                                null, null, null, 0, 50))))
        .hasMessageContaining("不能晚于");
  }

  @Test
  @DisplayName("14.3.1.1 时间跨度封顶：超上限直接 400，不做悄悄截断")
  void dateRangeIsCapped() {
    seedStandard(actorA, a, "REP-AMZ-1");

    assertThatThrownBy(
            () -> report(actorA, "DAY", "CAMPAIGN", LocalDate.of(2020, 1, 1),
                LocalDate.of(2026, 12, 31), 0, 50))
        .hasMessageContaining("跨度过大");

    // 400 桶之内就放行。
    assertThat(report(actorA, "DAY", "CAMPAIGN", LocalDate.of(2026, 1, 1),
        LocalDate.of(2026, 10, 5), 0, 50)).isNotNull();
  }

  // ==================================================================
  // 8. 🔴 真分页 + 汇总是全集
  // ==================================================================

  @Test
  @DisplayName("14.3.1.1 真分页：分页真的分页，汇总统计的是全集不是当前页")
  void paginationIsReal() {
    seedStandard(actorA, a, "REP-AMZ-1");

    var first = report(actorA, "DAY", "CAMPAIGN", DAY_1, DAY_1, 0, 2);
    var second = report(actorA, "DAY", "CAMPAIGN", DAY_1, DAY_1, 1, 2);

    assertThat(first.items()).hasSize(2);
    assertThat(second.items()).hasSize(1);
    assertThat(first.total()).isEqualTo(3);
    assertThat(second.total()).isEqualTo(3);
    // 🔴 两页的行不重叠。
    var firstKeys = first.items().stream().map(AdCampaignReportRow::dimensionKey).toList();
    var secondKeys = second.items().stream().map(AdCampaignReportRow::dimensionKey).toList();
    assertThat(secondKeys).noneMatch(firstKeys::contains);
    // 🔴 汇总不受分页影响：两页拿到的汇总是同一份全集。
    assertThat(first.summaries().get(0).totalSpend())
        .isEqualByComparingTo(second.summaries().get(0).totalSpend());
    assertThat(first.summaries().get(0).totalSpend()).isEqualByComparingTo("220");
  }

  // ==================================================================
  // 9. 🔴 按日/周/月分桶
  // ==================================================================

  @Test
  @DisplayName("14.3.1.1 按日/周/月分桶正确（周从周一开始）")
  void granularityBucketsCorrectly() {
    String shop = seedShop(a, AMAZON, "REP-AMZ-GRAN");
    UUID profile = saveProfile(actorA, AMAZON, "sponsored_products", "AUTO_DISCOVERY");
    importCampaign(actorA, AMAZON, shop, profile, "EXT-GRAN-1", "跨周活动", "USD",
        List.of(
            metric(DAY_1, "100", "10", "1", "10.0000", "20.0000"),
            metric(DAY_2, "100", "10", "1", "20.0000", "40.0000")));

    var day = trend(actorA, "DAY", DAY_1, DAY_2);
    assertThat(day.series()).hasSize(1);
    assertThat(day.series().get(0).points()).hasSize(2);
    assertThat(day.series().get(0).points().get(0).periodStart()).isEqualTo(DAY_1);
    assertThat(day.series().get(0).points().get(1).periodStart()).isEqualTo(DAY_2);

    var week = trend(actorA, "WEEK", DAY_1, DAY_2);
    assertThat(week.series().get(0).points()).hasSize(1);
    // 🔴 2026-10-01 是周四 ⇒ 那一周的周一是 2026-09-28。
    assertThat(week.series().get(0).points().get(0).periodStart()).isEqualTo(WEEK_START);
    // 🔴 该周合计：花费 30、销售额 60、点击 20、曝光 200
    assertThat(week.series().get(0).points().get(0).spend()).isEqualByComparingTo("30");
    assertThat(week.series().get(0).points().get(0).sales()).isEqualByComparingTo("60");
    assertThat(week.series().get(0).points().get(0).ctr()).isEqualByComparingTo("0.1");

    var month = trend(actorA, "MONTH", DAY_1, DAY_2);
    assertThat(month.series().get(0).points()).hasSize(1);
    assertThat(month.series().get(0).points().get(0).periodStart()).isEqualTo(MONTH_START);
    assertThat(month.series().get(0).points().get(0).spend()).isEqualByComparingTo("30");
  }

  // ==================================================================
  // 10. 🔴 多维对比
  // ==================================================================

  @Test
  @DisplayName("14.3.1.1 多维对比：按平台 / 店铺 / 归一后类型分组")
  void groupsByMultipleDimensions() {
    seedStandard(actorA, a, "REP-AMZ-1");

    var byPlatform =
        as(actorA, "channel.operation.read",
            () ->
                service.report(
                    service.normalize(DAY_1, DAY_1, "DAY", "PLATFORM", "SPEND_DESC", null, null,
                        null, null, 0, 50)));
    assertThat(byPlatform.total()).isEqualTo(1);
    assertThat(byPlatform.items().get(0).dimensionLabel()).isEqualTo("Amazon");

    var byShop =
        as(actorA, "channel.operation.read",
            () ->
                service.report(
                    service.normalize(DAY_1, DAY_1, "DAY", "SHOP", "SPEND_DESC", null, null, null,
                        null, 0, 50)));
    assertThat(byShop.total()).isEqualTo(1);
    assertThat(byShop.items().get(0).dimensionLabel()).isEqualTo("测试店铺 REP-AMZ-1");

    var byType =
        as(actorA, "channel.operation.read",
            () ->
                service.report(
                    service.normalize(DAY_1, DAY_1, "DAY", "AD_TYPE", "SPEND_DESC", null, null,
                        null, null, 0, 50)));
    assertThat(byType.total()).isEqualTo(1);
    // 🔴 归一后类型的显示名走 14.1.1.1 的同一份映射。
    assertThat(byType.items().get(0).dimensionLabel()).isEqualTo("自动广告");

    var byCurrency =
        as(actorA, "channel.operation.read",
            () ->
                service.report(
                    service.normalize(DAY_1, DAY_1, "DAY", "CURRENCY", "SPEND_DESC", null, null,
                        null, null, 0, 50)));
    assertThat(byCurrency.total()).isEqualTo(1);
    assertThat(byCurrency.items().get(0).dimensionKey()).isEqualTo("USD");
  }

  // ==================================================================
  // 11. 🔴 导出：口径写进表头 + 导出留痕 + timestamptz 写回值
  // ==================================================================

  @Test
  @DisplayName("14.3.1.1 导出：口径写进 CSV 表头，TACOS 写「不可用」，留痕的写回值逐字相等")
  void exportWritesDefinitionIntoHeaderAndLeavesTrace() {
    seedStandard(actorA, a, "REP-AMZ-1");

    var query =
        as(actorA, "channel.operation.read",
            () ->
                service.normalize(DAY_1, DAY_1, "DAY", "CAMPAIGN", "SPEND_DESC", null, null, null,
                    null, 0, 50));

    // 留痕：谁、什么条件、多少行、对哪个水位。
    var trace = as(actorA, "channel.operation.read", () -> service.recordExport(query));
    assertThat(trace.rowCount()).isEqualTo(3);
    assertThat(trace.granularity()).isEqualTo("DAY");
    assertThat(trace.dimension()).isEqualTo("CAMPAIGN");
    // 🔴 timestamptz **写回值**逐字相等（AGENTS.md：不能只断言没抛异常）。
    assertThat(trace.dataAsOf().toInstant()).isEqualTo(DATA_AS_OF.toInstant());

    // 🔴 留痕真的落库了，而且 data_as_of 写回来仍然逐字相等。
    var exports = as(actorA, "channel.operation.read", () -> service.exports(0, 50));
    assertThat(exports.total()).isEqualTo(1);
    AdCampaignReportExportRow row = exports.items().get(0);
    assertThat(row.rowCount()).isEqualTo(3);
    assertThat(row.dataAsOf().toInstant()).isEqualTo(DATA_AS_OF.toInstant());
    assertThat(row.requestedBy()).isEqualTo(user);
    assertThat(row.metricDefinitionNote()).contains("ACOS");

    // 🔴 CSV：口径写进表头；TACOS 写「不可用」而不是 0。
    StringBuilder csv = new StringBuilder();
    service.streamCsv(actorA, query, csv::append, "按活动");
    String text = csv.toString();
    assertThat(text).contains("ACOS(Σ花费/Σ销售额)");
    assertThat(text).contains("ROAS(Σ销售额/Σ花费)");
    assertThat(text).contains("TACOS(广告花费/总销售额)");
    assertThat(text).contains("不可用（总销售额口径未接入）");
    // 🔴 导出的是真实数据行，不是只有表头。
    assertThat(text.lines().filter(l -> l.startsWith("按活动,")).count()).isEqualTo(3);
    // 🔴 比率不可比写「不可比」而不是 0。
    assertThat(text).contains("不可比");
  }

  @Test
  @DisplayName("14.3.1.1 导出的每一批都重新开租户事务（不会因为没有租户上下文而导出 0 行）")
  void exportStreamsInsideTenantTransaction() {
    seedStandard(actorA, a, "REP-AMZ-1");
    var query =
        as(actorA, "channel.operation.read",
            () ->
                service.normalize(DAY_1, DAY_1, "DAY", "CAMPAIGN", "SPEND_DESC", null, null, null,
                    null, 0, 50));

    // 🔴 刻意**不在** access.run 里调用：模拟生产里「流式写发生在另一个线程」的那条路径。
    StringBuilder csv = new StringBuilder();
    service.streamCsv(actorA, query, csv::append, "按活动");

    assertThat(csv.toString().lines().filter(l -> l.startsWith("按活动,")).count()).isEqualTo(3);
  }

  // ==================================================================
  // 12. 🔴 数据截至时刻与三种「空」
  // ==================================================================

  @Test
  @DisplayName("14.3.1.1 数据截至时刻与三种「空」分开说")
  void emptyStatesAreDistinguished() {
    // 🔴 还没同步过 ⇒ 只知道「还没同步到数据」，不能说「这个区间没有投放」。
    var beforeSync = report(actorA, "DAY", "CAMPAIGN", DAY_1, DAY_1, 0, 50);
    assertThat(beforeSync.emptyReason()).isEqualTo("NO_SYNCED_DATA");
    assertThat(beforeSync.dataAsOf()).isNull();

    seedStandard(actorA, a, "REP-AMZ-1");

    var synced = report(actorA, "DAY", "CAMPAIGN", DAY_1, DAY_1, 0, 50);
    assertThat(synced.emptyReason()).isNull();
    assertThat(synced.dataAsOf().toInstant()).isEqualTo(DATA_AS_OF.toInstant());

    // 🔴 同步成功过，但这个区间里没有指标 ⇒ NO_METRICS_IN_RANGE。
    var outOfRange = report(actorA, "DAY", "CAMPAIGN", LocalDate.of(2026, 1, 1),
        LocalDate.of(2026, 1, 31), 0, 50);
    assertThat(outOfRange.emptyReason()).isEqualTo("NO_METRICS_IN_RANGE");

    // 🔴 区间里有指标，但被筛选条件排除了 ⇒ FILTERED_EMPTY。
    var filtered =
        as(actorA, "channel.operation.read",
            () ->
                service.report(
                    service.normalize(DAY_1, DAY_1, "DAY", "CAMPAIGN", "SPEND_DESC", TIKTOK, null,
                        null, null, 0, 50)));
    assertThat(filtered.emptyReason()).isEqualTo("FILTERED_EMPTY");
  }

  // ==================================================================
  // 13. 端点（HTTP 层）
  // ==================================================================

  @Test
  @DisplayName("14.3.1.1 端点可用：报表 / 趋势 / 能力声明 / 选项")
  void endpointsRespond() throws Exception {
    seedStandard(actorA, a, "REP-AMZ-1");

    http.perform(get("/channel/ad-reports/capabilities").header("Authorization", bearerA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.metricDefinitionNote").value(org.hamcrest.Matchers.containsString("TACOS")));

    http.perform(get("/channel/ad-reports/options").header("Authorization", bearerA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.granularities.length()").value(3));

    http.perform(
            get("/channel/ad-reports/rows")
                .header("Authorization", bearerA)
                .param("from", DAY_1.toString())
                .param("to", DAY_1.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(3))
        .andExpect(jsonPath("$.summaries[0].currency").value("USD"))
        .andExpect(jsonPath("$.items[0].spend").exists())
        .andExpect(jsonPath("$.items[0].currency").value("USD"));

    http.perform(
            get("/channel/ad-reports/trend")
                .header("Authorization", bearerA)
                .param("from", DAY_1.toString())
                .param("to", DAY_1.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.series[0].points.length()").value(1));

    // 🔴 时间范围必填：端点层也必须拒。
    http.perform(get("/channel/ad-reports/rows").header("Authorization", bearerA))
        .andExpect(status().isBadRequest());

    // 无会话一律 401。
    http.perform(get("/channel/ad-reports/rows")).andExpect(status().isUnauthorized());
  }

  // ==================================================================
  // 14. 多租户三断言（AGENTS.md 硬性要求，缺一不可）
  // ==================================================================

  @Test
  @DisplayName("14.3.1.1 断言一：两租户互不可见")
  void tenantsAreIsolated() {
    seedStandard(actorA, a, "ISO-AMZ-1");
    seedStandard(actorB, b, "ISO-AMZ-2");

    var pageA = report(actorA, "DAY", "CAMPAIGN", DAY_1, DAY_1, 0, 50);
    var pageB = report(actorB, "DAY", "CAMPAIGN", DAY_1, DAY_1, 0, 50);

    assertThat(pageA.total()).isEqualTo(3);
    assertThat(pageB.total()).isEqualTo(3);
    assertThat(pageA.items())
        .extracting(AdCampaignReportRow::dimensionLabel)
        .containsExactlyInAnyOrder("报表活动 1", "报表活动 2", "零值活动");

    // 🔴 汇总也互不可见：A 的花费合计是 220（EUR 那条不在 A 租户里）。
    assertThat(pageA.summaries().get(0).totalSpend()).isEqualByComparingTo("220");
    assertThat(pageB.summaries().get(0).totalSpend()).isEqualByComparingTo("220");

    // 🔴 导出留痕也互不可见。
    var exportsA = as(actorA, "channel.operation.read", () -> service.exports(0, 50));
    var exportsB = as(actorB, "channel.operation.read", () -> service.exports(0, 50));
    assertThat(exportsA.total()).isZero();
    assertThat(exportsB.total()).isZero();
  }

  @Test
  @DisplayName("14.3.1.1 断言二：伪造 tenant header 无效（租户只来自已认证成员关系）")
  void forgedTenantHeaderIsIgnored() throws Exception {
    seedStandard(actorA, a, "FORGE-AMZ-1");
    seedStandard(actorB, b, "FORGE-AMZ-2");

    // A 的会话 + B 的头 ⇒ 仍然只看得见 A 的数据。
    http.perform(
            get("/channel/ad-reports/rows")
                .header("Authorization", bearerA)
                .header("X-Tenant-Id", b.toString())
                .param("from", DAY_1.toString())
                .param("to", DAY_1.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(3));

    // 🔴 伪造的租户头也不能让 B 的数据被 A 读到：两条数据集长得一样，
    //   所以要看**店铺名**（A 的店铺是 FORGE-AMZ-1，B 的是 FORGE-AMZ-2）。
    http.perform(
            get("/channel/ad-reports/rows")
                .header("Authorization", bearerA)
                .header("X-Tenant-Id", b.toString())
                .param("from", DAY_1.toString())
                .param("to", DAY_1.toString())
                .param("dimension", "SHOP"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].shopName").value("测试店铺 FORGE-AMZ-1"));

    // 无会话一律 401。
    http.perform(
            get("/channel/ad-reports/rows")
                .header("X-Tenant-Id", b.toString())
                .param("from", DAY_1.toString())
                .param("to", DAY_1.toString()))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("14.3.1.1 断言三：连接池归还后不串租户（池压到 1，A→B→A 三轮）")
  void pooledConnectionsDoNotBleedTenants() {
    seedStandard(actorA, a, "POOL-AMZ-1");
    seedStandard(actorB, b, "POOL-AMZ-2");

    for (int round = 0; round < 3; round++) {
      var pageA = report(actorA, "DAY", "CAMPAIGN", DAY_1, DAY_1, 0, 50);
      assertThat(pageA.total()).isEqualTo(3);
      assertThat(pageA.summaries().get(0).totalSpend()).isEqualByComparingTo("220");

      var pageB = report(actorB, "DAY", "CAMPAIGN", DAY_1, DAY_1, 0, 50);
      assertThat(pageB.total()).isEqualTo(3);
      assertThat(pageB.summaries().get(0).totalSpend()).isEqualByComparingTo("220");

      // 🔴 A 的导出留痕不能被 B 看见。
      var exportsA = as(actorA, "channel.operation.read", () -> service.exports(0, 50));
      assertThat(exportsA.total()).isZero();
    }
  }
}
