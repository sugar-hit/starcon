package com.starcon.channel;

import static com.starcon.channel.AdCampaignModels.*;
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
 * 14.1.1.1 广告活动列表。
 *
 * <p>本测试类钉住本模块最容易做丢的几件事：
 *
 * <ol>
 *   <li><b>多租户三断言</b>（AGENTS.md 硬性要求，缺一不可）：两租户互不可见、伪造 tenant header 无效、
 *       连接池归还后不串租户
 *   <li><b>🔴 原始类型标记与归一类型并存</b>：只留归一类型，「平台 API 原文是什么」就永远答不出来；
 *       UNMAPPED 是显式合法值，不是兜底
 *   <li><b>🔴「数据截至 X 时间」不推进假水位</b>：失败的同步不写数据、不推进水位，
 *       「没投放」与「还没同步到数据」是两种不同的空态
 *   <li><b>🔴 ACOS / ROAS 口径</b>：分母为 0 给空值而不是 0；汇总是「先汇总再相除」不是比率的平均
 *   <li><b>🔴 批量护栏</b>：单次上限、总额上限、逐条结果；被拒的那几条也要留审计
 *   <li><b>时间字段写回值正确</b>（AGENTS.md 要求断言写回值，不能只断言没抛异常）
 *   <li><b>真分页真筛选</b>；跨租户与不存在返回逐字相同的 404
 * </ol>
 *
 * <p><b>时刻一律用固定的 {@link OffsetDateTime} 钉死</b>，不用 {@code now()}：用墙钟会让
 * 「这次写入落在哪个断言分支上」成为一个 flaky 断言。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AdCampaignIntegrationTest {

  static final PostgreSQLContainer<?> pg =
      new PostgreSQLContainer<>("postgres:16-alpine").withUrlParam("sslmode", "disable");
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
  static final LocalDate DAY_1 = LocalDate.of(2026, 10, 1);
  static final LocalDate DAY_2 = LocalDate.of(2026, 10, 2);

  static final UUID AMAZON = UUID.fromString("11111111-1111-1111-1111-111111111111");
  static final UUID TIKTOK = UUID.fromString("22222222-2222-2222-2222-222222222222");
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

  /** 播种全局码表（平台目录 + 平台默认映射）。刻意**不在静态块里**播种（那时表还没建）。 */
  @BeforeEach
  void seedGlobalCatalog() {
    exec("insert into platform (id,code,name,integration,support_status,position)"
        + " values ('" + AMAZON + "','AMAZON','Amazon','OFFICIAL_API','AVAILABLE',1)"
        + " on conflict (id) do nothing");
    exec("insert into platform (id,code,name,integration,support_status,position)"
        + " values ('" + TIKTOK + "','TIKTOK','TikTok Shop','OFFICIAL_API','AVAILABLE',2)"
        + " on conflict (id) do nothing");
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

  @Autowired AdCampaignService service;
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

  /** 播种一条 2.1.2.1 的平台默认映射（全局码表）。 */
  static void seedPlatformMapping(UUID platformId, String external, String internal) {
    exec("insert into platform_enum_mapping (id,platform_id,enum_kind,external_value,internal_value)"
        + " values ('" + UUID.randomUUID() + "','" + platformId + "','AD_TYPE','" + external
        + "','" + internal + "')"
        + " on conflict (platform_id,enum_kind,external_value) do nothing");
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

  /** 通过**上游 14.1.2.2** 登记一个广告类型档案（本模块的归一口径由它带下来）。 */
  UUID saveProfile(AccessService.Access who, UUID platformId, String external, String internal) {
    return as(who, "channel.operation.create",
        () -> adTypes.saveProfile(new AdTypeDiffModels.SaveProfileRequest(
            null, platformId, external, internal, external + " 展示名", "CPC", "SALES",
            true, true, true, true, false,
            "UNMAPPED".equals(internal) ? "UNMAPPED" : "PLATFORM_DEFAULT", "测试登记")))
        .id();
  }

  ImportCampaign campaign(
      String externalId, String name, UUID profileId, String status, String budget,
      List<ImportDailyMetric> metrics) {
    return new ImportCampaign(
        externalId, name, profileId, status, "USD",
        budget == null ? null : new BigDecimal(budget),
        null, null, metrics, "测试登记");
  }

  ImportDailyMetric metric(LocalDate date, String impressions, String clicks, String orders,
      String spend, String sales) {
    return new ImportDailyMetric(
        date, new BigDecimal(impressions), new BigDecimal(clicks), new BigDecimal(orders),
        new BigDecimal(spend), new BigDecimal(sales));
  }

  ImportRequest importRequest(UUID platformId, String shop, UUID profileId, String externalId,
      List<ImportDailyMetric> metrics) {
    return new ImportRequest(
        platformId, shop,
        new ImportSyncRun("SUCCESS", DATA_AS_OF, STARTED_AT, FINISHED_AT, "", "batch-1"),
        List.of(campaign(externalId, "活动 " + externalId, profileId, "ENABLED", "100.0000", metrics)));
  }

  // ==================================================================
  // 1. 能力声明：未交付的段必须显式 available=false
  // ==================================================================

  @Test
  @DisplayName("14.1.1.1 能力声明：按筛选全选 / 异步批量任务 / 平台回写 / 活动详情显式未交付")
  void capabilitiesDeclareUndeliveredParts() {
    var caps = as(actorA, "channel.operation.read", () -> service.capabilities());
    Map<String, AdCampaignCapabilityDimension> dims =
        caps.dimensions().stream()
            .collect(java.util.stream.Collectors.toMap(AdCampaignCapabilityDimension::dimension, d -> d));

    assertThat(dims.get("campaign_list").available()).isTrue();
    assertThat(dims.get("data_as_of").available()).isTrue();
    assertThat(dims.get("bulk_pause_enable_budget").available()).isTrue();

    // 🔴 14.1.1.3 的三件能力必须显式 false + ownerModule，不许用空列表冒充已交付
    assertThat(dims.get("bulk_select_by_filter").available()).isFalse();
    assertThat(dims.get("bulk_select_by_filter").ownerModule()).isEqualTo("14.1.1.3 批量操作");
    assertThat(dims.get("bulk_async_task").available()).isFalse();
    assertThat(dims.get("platform_writeback").available()).isFalse();
    assertThat(dims.get("campaign_detail").available()).isFalse();
    assertThat(dims.get("auto_budget_rules").available()).isFalse();

    // 🔴 护栏上限必须给出来，界面上要能写明天花板
    assertThat(caps.guardrails().maxSingleBudgetDelta()).isEqualByComparingTo("5000");
    assertThat(caps.guardrails().maxTotalBudgetDelta()).isEqualByComparingTo("20000");
    assertThat(caps.guardrails().maxCampaignBudget()).isEqualByComparingTo("100000");
    assertThat(caps.guardrails().maxCampaignsPerRequest()).isEqualTo(200);
  }

  // ==================================================================
  // 2. 真实写入口：活动与每日指标真的落库
  // ==================================================================

  @Test
  @DisplayName("14.1.1.1 导入把活动与每日指标真的落库，数据截至时刻写回值逐字相等")
  void importLandsCampaignsAndMetrics() {
    seedPlatformMapping(AMAZON, "sponsored_products", "AUTO_DISCOVERY");
    String shop = seedShop(a, AMAZON, "IMP-AMZ-1");
    UUID profile = saveProfile(actorA, AMAZON, "sponsored_products", "AUTO_DISCOVERY");

    var result =
        as(actorA, "channel.operation.create",
            () -> service.importCampaigns(importRequest(AMAZON, shop, profile, "EXT-IMP-1",
                List.of(metric(DAY_1, "1000", "50", "5", "120.0000", "600.0000")))));

    assertThat(result.createdCount()).isEqualTo(1);
    assertThat(result.metricRowCount()).isEqualTo(1);
    assertThat(result.campaigns()).hasSize(1);

    var page = as(actorA, "channel.operation.read", () -> service.campaigns(
        null, null, null, null, null, null, null, null, "SPEND_DESC", 0, 50));

    assertThat(page.total()).isEqualTo(1);
    AdCampaignRow row = page.items().get(0);
    assertThat(row.externalCampaignId()).isEqualTo("EXT-IMP-1");
    assertThat(row.campaignName()).isEqualTo("活动 EXT-IMP-1");
    assertThat(row.impressions()).isEqualByComparingTo("1000");
    assertThat(row.clicks()).isEqualByComparingTo("50");
    assertThat(row.orders()).isEqualByComparingTo("5");
    assertThat(row.spend()).isEqualByComparingTo("120.0000");
    assertThat(row.sales()).isEqualByComparingTo("600.0000");
    assertThat(row.dailyBudget()).isEqualByComparingTo("100.0000");

    // 🔴 ACOS = 消耗 / 销售额 = 120/600 = 0.2；ROAS = 600/120 = 5
    assertThat(row.acos()).isEqualByComparingTo("0.200000");
    assertThat(row.roas()).isEqualByComparingTo("5.000000");

    // 🔴 时间字段断言**写回值**（AGENTS.md）：用 toInstant 比，否则 +08:00 的偏移会被比掉。
    //   这里如果用 Timestamp.valueOf(...toLocalDateTime()) 绑定，偏 8 小时，断言立刻红。
    assertThat(row.dataAsOf().toInstant()).isEqualTo(DATA_AS_OF.toInstant());
    assertThat(page.dataAsOf().toInstant()).isEqualTo(DATA_AS_OF.toInstant());
  }

  // ==================================================================
  // 3. 原始标记与归一类型并存
  // ==================================================================

  @Test
  @DisplayName("14.1.1.1 原始类型标记与归一类型并存；UNMAPPED 是显式值而不是兜底")
  void originalMarkerAndNormalizedTypeCoexist() {
    seedPlatformMapping(AMAZON, "sponsored_brand", "SPONSORED_BRAND");
    String shop = seedShop(a, AMAZON, "TYPE-AMZ-1");
    UUID profile = saveProfile(actorA, AMAZON, "sponsored_brand", "SPONSORED_BRAND");

    // 另一个原始类型在 2.1.2.1 两层都查不到 ⇒ 显式未映射
    UUID unmappedProfile = saveProfile(actorA, AMAZON, "legacy_promo", "UNMAPPED");

    as(actorA, "channel.operation.create",
        () -> service.importCampaigns(new ImportRequest(
            AMAZON, shop,
            new ImportSyncRun("SUCCESS", DATA_AS_OF, STARTED_AT, FINISHED_AT, "", "batch-1"),
            List.of(
                campaign("EXT-SB", "SB 活动", profile, "ENABLED", "100.0000",
                    List.of(metric(DAY_1, "100", "10", "1", "10.0000", "50.0000"))),
                campaign("EXT-LEGACY", "老活动", unmappedProfile, "ENABLED", "50.0000",
                    List.of(metric(DAY_1, "20", "2", "0", "5.0000", "1.0000")))))));

    var page = as(actorA, "channel.operation.read", () -> service.campaigns(
        null, null, null, null, null, null, null, null, "SPEND_DESC", 0, 50));

    AdCampaignRow sb = page.items().stream()
        .filter(r -> r.externalCampaignId().equals("EXT-SB")).findFirst().orElseThrow();
    // 🔴 平台原文与归一类型同时给出来
    assertThat(sb.platformAdType()).isEqualTo("sponsored_brand");
    assertThat(sb.internalAdType()).isEqualTo("SPONSORED_BRAND");
    assertThat(sb.adTypeMappingSource()).isEqualTo("PLATFORM_DEFAULT");
    assertThat(sb.adTypeComparable()).isTrue();

    AdCampaignRow legacy = page.items().stream()
        .filter(r -> r.externalCampaignId().equals("EXT-LEGACY")).findFirst().orElseThrow();
    // 🔴 未映射：活动照样存在、照样带完整原始标记，只是不参与横向比较
    assertThat(legacy.platformAdType()).isEqualTo("legacy_promo");
    assertThat(legacy.internalAdType()).isEqualTo("UNMAPPED");
    assertThat(legacy.adTypeMappingSource()).isEqualTo("UNMAPPED");
    assertThat(legacy.adTypeComparable()).isFalse();
  }

  @Test
  @DisplayName("14.1.1.1 绕过服务层写 SQL 也改不了归一口径：触发器从档案复制回来")
  void databaseTriggerOverwritesInjectedAdType() {
    seedPlatformMapping(AMAZON, "sponsored_display", "SPONSORED_DISPLAY");
    String shop = seedShop(a, AMAZON, "TRG-AMZ-1");
    UUID profile = saveProfile(actorA, AMAZON, "sponsored_display", "SPONSORED_DISPLAY");
    as(actorA, "channel.operation.create",
        () -> service.importCampaigns(importRequest(AMAZON, shop, profile, "EXT-TRG-1",
            List.of(metric(DAY_1, "10", "1", "0", "1.0000", "5.0000")))));

    // 🔴 试图把一条 SD 报成自动广告 —— 触发器会改回来
    execAsTenant(a,
        "update ad_campaign set platform_ad_type='sponsored_products',"
            + " internal_ad_type='AUTO_DISCOVERY', ad_type_mapping_source='PLATFORM_DEFAULT'"
            + " where external_campaign_id='EXT-TRG-1'");

    var row = as(actorA, "channel.operation.read",
        () -> service.campaigns(null, null, null, null, null, null, null, null,
            "SPEND_DESC", 0, 50)).items().get(0);
    assertThat(row.platformAdType()).isEqualTo("sponsored_display");
    assertThat(row.internalAdType()).isEqualTo("SPONSORED_DISPLAY");
  }

  // ==================================================================
  // 4. 「数据截至 X 时间」与三种空态
  // ==================================================================

  @Test
  @DisplayName("14.1.1.1 失败的同步不写活动也不推进水位；空态区分「没同步」与「没投放」")
  void failedSyncDoesNotAdvanceWatermark() {
    seedPlatformMapping(AMAZON, "sponsored_products", "AUTO_DISCOVERY");
    String shop = seedShop(a, AMAZON, "FAIL-AMZ-1");
    UUID profile = saveProfile(actorA, AMAZON, "sponsored_products", "AUTO_DISCOVERY");

    // 从来没有成功同步过 ⇒ NO_SYNCED_DATA（**不是**「没有投放」）
    var empty = as(actorA, "channel.operation.read", () -> service.campaigns(
        null, null, null, null, null, null, null, null, "SPEND_DESC", 0, 50));
    assertThat(empty.total()).isZero();
    assertThat(empty.emptyReason()).isEqualTo("NO_SYNCED_DATA");
    assertThat(empty.emptyNote()).contains("还没有任何一次**成功的广告数据同步**");
    assertThat(empty.dataAsOf()).isNull();

    // 一次失败的同步：只登记失败，不写活动
    var failed =
        as(actorA, "channel.operation.create",
            () -> service.importCampaigns(new ImportRequest(
                AMAZON, shop,
                new ImportSyncRun("FAILED", null, STARTED_AT, FINISHED_AT, "平台接口 503", "batch-x"),
                List.of(campaign("EXT-FAIL", "不该存在的活动", profile, "ENABLED", "10.0000",
                    List.of(metric(DAY_1, "1", "1", "0", "1.0000", "1.0000")))))));
    assertThat(failed.campaignCount()).isZero();
    assertThat(failed.dataAsOf()).isNull();

    var stillEmpty = as(actorA, "channel.operation.read", () -> service.campaigns(
        null, null, null, null, null, null, null, null, "SPEND_DESC", 0, 50));
    assertThat(stillEmpty.total()).isZero();
    // 🔴 失败不推进水位 —— 界面上的「数据截至」不会虚高
    assertThat(stillEmpty.dataAsOf()).isNull();
    assertThat(stillEmpty.emptyReason()).isEqualTo("NO_SYNCED_DATA");
    // 🔴 但失败原因必须看得见
    assertThat(stillEmpty.syncStates()).hasSize(1);
    assertThat(stillEmpty.syncStates().get(0).status()).isEqualTo("FAILED");
    assertThat(stillEmpty.syncStates().get(0).message()).contains("平台接口 503");
    assertThat(stillEmpty.syncStates().get(0).dataAsOf()).isNull();

    // 一次成功的同步之后 ⇒ 空态变成「真的没有投放」？不，先有活动，再看筛选
    as(actorA, "channel.operation.create",
        () -> service.importCampaigns(importRequest(AMAZON, shop, profile, "EXT-OK-1",
            List.of(metric(DAY_1, "10", "1", "0", "1.0000", "5.0000")))));
    var withData = as(actorA, "channel.operation.read", () -> service.campaigns(
        null, null, null, null, null, null, null, null, "SPEND_DESC", 0, 50));
    assertThat(withData.total()).isEqualTo(1);
    assertThat(withData.dataAsOf().toInstant()).isEqualTo(DATA_AS_OF.toInstant());
    assertThat(withData.emptyReason()).isNull();

    // 🔴 被筛掉 ⇒ FILTERED_EMPTY（不是「没有投放」）
    var filtered = as(actorA, "channel.operation.read", () -> service.campaigns(
        null, null, "PAUSED", null, null, null, null, null, "SPEND_DESC", 0, 50));
    assertThat(filtered.total()).isZero();
    assertThat(filtered.emptyReason()).isEqualTo("FILTERED_EMPTY");
  }

  @Test
  @DisplayName("14.1.1.1 同步成功但这个范围没有活动 ⇒ NO_CAMPAIGNS（真的没有投放）")
  void emptyScopeAfterSuccessfulSyncIsNoCampaigns() {
    seedPlatformMapping(AMAZON, "sponsored_products", "AUTO_DISCOVERY");
    seedPlatformMapping(TIKTOK, "video_promotion", "PROMOTION_EVENT");
    String shopA = seedShop(a, AMAZON, "SCOPE-AMZ-1");
    String shopB = seedShop(a, TIKTOK, "SCOPE-TK-1");
    UUID profile = saveProfile(actorA, AMAZON, "sponsored_products", "AUTO_DISCOVERY");
    UUID profileTk = saveProfile(actorA, TIKTOK, "video_promotion", "PROMOTION_EVENT");

    as(actorA, "channel.operation.create",
        () -> service.importCampaigns(importRequest(AMAZON, shopA, profile, "EXT-A-1",
            List.of(metric(DAY_1, "10", "1", "0", "1.0000", "5.0000")))));
    as(actorA, "channel.operation.create",
        () -> service.importCampaigns(importRequest(TIKTOK, shopB, profileTk, "EXT-B-1",
            List.of(metric(DAY_1, "10", "1", "0", "1.0000", "5.0000")))));

    // 平台筛选到一个没有活动的范围：同步成功过 ⇒ 「真的没有投放」
    var page = as(actorA, "channel.operation.read", () -> service.campaigns(
        TIKTOK, "SCOPE-TK-NONE", null, null, null, null, null, null, "SPEND_DESC", 0, 50));
    assertThat(page.total()).isZero();
    assertThat(page.emptyReason()).isEqualTo("NO_CAMPAIGNS");
  }

  // ==================================================================
  // 5. ACOS / ROAS 口径
  // ==================================================================

  @Test
  @DisplayName("14.1.1.1 销售额为 0 时比率为空（不可比）而不是 0；汇总是先汇总再相除")
  void ratiosFollowDeclaredDefinition() {
    seedPlatformMapping(AMAZON, "sponsored_products", "AUTO_DISCOVERY");
    String shop = seedShop(a, AMAZON, "RATIO-AMZ-1");
    UUID profile = saveProfile(actorA, AMAZON, "sponsored_products", "AUTO_DISCOVERY");

    as(actorA, "channel.operation.create",
        () -> service.importCampaigns(new ImportRequest(
            AMAZON, shop,
            new ImportSyncRun("SUCCESS", DATA_AS_OF, STARTED_AT, FINISHED_AT, "", "batch-1"),
            List.of(
                // 活动一：消耗 100、销售额 0 ⇒ ACOS 不可比
                campaign("EXT-R-1", "零销售活动", profile, "ENABLED", "10.0000",
                    List.of(metric(DAY_1, "1000", "50", "0", "100.0000", "0.0000"))),
                // 活动二：消耗 100、销售额 400 ⇒ ACOS 25%
                campaign("EXT-R-2", "有销售活动", profile, "ENABLED", "10.0000",
                    List.of(metric(DAY_1, "1000", "50", "2", "100.0000", "400.0000")))))));

    var page = as(actorA, "channel.operation.read", () -> service.campaigns(
        null, null, null, null, null, null, null, null, "SPEND_DESC", 0, 50));

    AdCampaignRow zero = page.items().stream()
        .filter(r -> r.externalCampaignId().equals("EXT-R-1")).findFirst().orElseThrow();
    // 🔴 分母为 0 ⇒ null（不可比），不是 0
    assertThat(zero.acos()).isNull();
    assertThat(zero.sales()).isEqualByComparingTo("0");

    // 🔴 汇总 ACOS = 总消耗 / 总销售额 = 200/400 = 0.5
    //   （不是 (不可比 + 0.25) / 2 那种比率平均）
    assertThat(page.summary().totalSpend()).isEqualByComparingTo("200");
    assertThat(page.summary().totalSales()).isEqualByComparingTo("400");
    assertThat(page.summary().acos()).isEqualByComparingTo("0.500000");
    assertThat(page.summary().roas()).isEqualByComparingTo("2.000000");
    assertThat(page.summary().metricDefinitionNote()).contains("ACOS = 消耗 / 销售额");
  }

  // ==================================================================
  // 6. 批量操作与护栏
  // ==================================================================

  @Test
  @DisplayName("14.1.1.1 批量暂停 / 启用 / 调预算逐条返回结果，被拒的那几条也留审计")
  void bulkOperationReturnsPerItemResults() {
    seedPlatformMapping(AMAZON, "sponsored_products", "AUTO_DISCOVERY");
    String shop = seedShop(a, AMAZON, "BULK-AMZ-1");
    UUID profile = saveProfile(actorA, AMAZON, "sponsored_products", "AUTO_DISCOVERY");

    as(actorA, "channel.operation.create",
        () -> service.importCampaigns(new ImportRequest(
            AMAZON, shop,
            new ImportSyncRun("SUCCESS", DATA_AS_OF, STARTED_AT, FINISHED_AT, "", "batch-1"),
            List.of(
                campaign("EXT-B-1", "活动一", profile, "ENABLED", "100.0000",
                    List.of(metric(DAY_1, "10", "1", "0", "1.0000", "5.0000"))),
                // 第二条本来就是暂停的 ⇒ 批量暂停时应当 SKIPPED，而不是再改一遍
                campaign("EXT-B-2", "活动二", profile, "PAUSED", "100.0000",
                    List.of(metric(DAY_1, "10", "1", "0", "1.0000", "5.0000"))),
                campaign("EXT-B-3", "活动三", profile, "ENABLED", "100.0000",
                    List.of(metric(DAY_1, "10", "1", "0", "1.0000", "5.0000"))),
                campaign("EXT-B-4", "活动四", profile, "ENABLED", "100.0000",
                    List.of(metric(DAY_1, "10", "1", "0", "1.0000", "5.0000"))),
                campaign("EXT-B-5", "活动五", profile, "ENABLED", "100.0000",
                    List.of(metric(DAY_1, "10", "1", "0", "1.0000", "5.0000")))))));

    var page = as(actorA, "channel.operation.read", () -> service.campaigns(
        null, null, null, null, null, null, null, null, "SPEND_DESC", 0, 50));
    UUID id1 = page.items().stream()
        .filter(r -> r.externalCampaignId().equals("EXT-B-1")).findFirst().orElseThrow().id();
    UUID id2 = page.items().stream()
        .filter(r -> r.externalCampaignId().equals("EXT-B-2")).findFirst().orElseThrow().id();
    List<UUID> allIds = page.items().stream().map(AdCampaignRow::id).toList();

    // 暂停：一条生效，一条本来就已经是暂停 ⇒ SKIPPED
    var paused =
        as(actorA, "channel.operation.update",
            () -> service.bulkOperation(
                new BulkOperationRequest(List.of(id1, id2), "PAUSE", null, null, "止损")));
    assertThat(paused.applied()).isEqualTo(1);
    assertThat(paused.skipped()).isEqualTo(1);
    assertThat(paused.items()).hasSize(2);

    // 启用
    var enabled =
        as(actorA, "channel.operation.update",
            () -> service.bulkOperation(
                new BulkOperationRequest(List.of(id1), "ENABLE", null, null, "恢复")));
    assertThat(enabled.applied()).isEqualTo(1);
    assertThat(enabled.items().get(0).beforeStatus()).isEqualTo("PAUSED");
    assertThat(enabled.items().get(0).afterStatus()).isEqualTo("ENABLED");

    // 🔴 请求级校验：目标预算超过单活动预算上限 ⇒ 整批拒（它对任何一条都不可能成立）
    var overAbsoluteCap = catchThrowable(() ->
        as(actorA, "channel.operation.update",
            () -> service.bulkOperation(
                new BulkOperationRequest(List.of(id1), "SET_BUDGET",
                    new BigDecimal("999999.0000"), null, "手滑"))));
    assertThat(overAbsoluteCap).isNotNull();
    assertThat(overAbsoluteCap.getMessage()).contains("单活动预算上限");

    // 🔴 单活动级护栏：一次调整额超过单活动上限 ⇒ **逐条拒绝**（当前预算 100 → 目标 6000 = 调 5900）
    var tooBig =
        as(actorA, "channel.operation.update",
            () -> service.bulkOperation(
                new BulkOperationRequest(List.of(id1), "SET_BUDGET",
                    new BigDecimal("6000.0000"), null, "手滑")));
    assertThat(tooBig.rejected()).isEqualTo(1);
    assertThat(tooBig.items().get(0).result()).isEqualTo("REJECTED");
    assertThat(tooBig.items().get(0).message()).contains("上限");
    // 🔴 被拒的那条**没有生效**，预算原样
    assertThat(tooBig.items().get(0).afterBudget()).isEqualByComparingTo("100.0000");

    // 🔴 总额上限：单次上限 5000、总额上限 20000 ⇒ 五条各调 5000 时，
    //   前四条用掉 20000，第五条被总额上限拒掉（逐条拒，不是整批失败）。
    var total = as(actorA, "channel.operation.update",
        () -> service.bulkOperation(new BulkOperationRequest(
            allIds, "ADJUST_BUDGET", null, new BigDecimal("5000.0000"), "加预算")));
    assertThat(total.applied()).isEqualTo(4);
    assertThat(total.rejected()).isEqualTo(1);
    assertThat(total.items().get(4).message()).contains("总预算调整上限");

    // 审计：成功与被拒都要留
    var detail = as(actorA, "channel.operation.read", () -> service.campaign(id1.toString()));
    assertThat(detail.operationLogs()).isNotEmpty();
    assertThat(detail.operationLogs()).extracting(AdCampaignOperationLogRow::result)
        .contains("APPLIED", "REJECTED");

    // 🔴 本地改动要标记「待回写平台」
    assertThat(detail.campaign().pendingPlatformWriteback()).isTrue();
    assertThat(detail.campaign().localChangedAt()).isNotNull();
  }

  @Test
  @DisplayName("14.1.1.1 本地未回写的改动不会被平台回读静默覆盖")
  void pendingLocalChangeSurvivesPlatformReadBack() {
    seedPlatformMapping(AMAZON, "sponsored_products", "AUTO_DISCOVERY");
    String shop = seedShop(a, AMAZON, "PEND-AMZ-1");
    UUID profile = saveProfile(actorA, AMAZON, "sponsored_products", "AUTO_DISCOVERY");

    as(actorA, "channel.operation.create",
        () -> service.importCampaigns(importRequest(AMAZON, shop, profile, "EXT-P-1",
            List.of(metric(DAY_1, "10", "1", "0", "1.0000", "5.0000")))));
    var page = as(actorA, "channel.operation.read", () -> service.campaigns(
        null, null, null, null, null, null, null, null, "SPEND_DESC", 0, 50));
    UUID id = page.items().get(0).id();

    // 本地暂停（平台回写队列未交付）
    as(actorA, "channel.operation.update",
        () -> service.bulkOperation(
            new BulkOperationRequest(List.of(id), "PAUSE", null, null, "本地暂停")));

    // 平台又回读了一次（状态还是 ENABLED，因为平台侧根本没改）
    as(actorA, "channel.operation.create",
        () -> service.importCampaigns(importRequest(AMAZON, shop, profile, "EXT-P-1",
            List.of(metric(DAY_1, "20", "2", "0", "2.0000", "10.0000")))));

    var after = as(actorA, "channel.operation.read", () -> service.campaign(id.toString()));
    // 🔴 本地暂停没有被平台回读抹掉
    assertThat(after.campaign().status()).isEqualTo("PAUSED");
    assertThat(after.campaign().pendingPlatformWriteback()).isTrue();
    // 但指标是更新的
    assertThat(after.dailyMetrics()).hasSize(1);
    assertThat(after.dailyMetrics().get(0).impressions()).isEqualByComparingTo("20");
  }

  @Test
  @DisplayName("14.1.1.1 批量操作只认显式选中的 id：不存在的 id 逐条拒绝，不影响其余条目")
  void bulkOperationRejectsUnknownIdsPerItem() {
    seedPlatformMapping(AMAZON, "sponsored_products", "AUTO_DISCOVERY");
    String shop = seedShop(a, AMAZON, "UNKNOWN-AMZ-1");
    UUID profile = saveProfile(actorA, AMAZON, "sponsored_products", "AUTO_DISCOVERY");
    as(actorA, "channel.operation.create",
        () -> service.importCampaigns(importRequest(AMAZON, shop, profile, "EXT-U-1",
            List.of(metric(DAY_1, "10", "1", "0", "1.0000", "5.0000")))));
    var page = as(actorA, "channel.operation.read", () -> service.campaigns(
        null, null, null, null, null, null, null, null, "SPEND_DESC", 0, 50));
    UUID id = page.items().get(0).id();

    var result =
        as(actorA, "channel.operation.update",
            () -> service.bulkOperation(
                new BulkOperationRequest(List.of(id, UUID.randomUUID()), "PAUSE", null, null, "混合")));
    assertThat(result.applied()).isEqualTo(1);
    assertThat(result.rejected()).isEqualTo(1);
    assertThat(result.items().get(1).result()).isEqualTo("REJECTED");
    assertThat(result.items().get(1).message()).contains("不存在或不属于当前租户");
  }

  // ==================================================================
  // 7. 真分页真筛选
  // ==================================================================

  @Test
  @DisplayName("14.1.1.1 真分页 + 真筛选 + 真排序；汇总统计的是全集而不是当前页")
  void paginationFiltersAndSummary() {
    seedPlatformMapping(AMAZON, "sponsored_products", "AUTO_DISCOVERY");
    String shop = seedShop(a, AMAZON, "PAGE-AMZ-1");
    UUID profile = saveProfile(actorA, AMAZON, "sponsored_products", "AUTO_DISCOVERY");

    List<ImportCampaign> campaigns = new java.util.ArrayList<>();
    for (int i = 1; i <= 3; i++) {
      campaigns.add(campaign("EXT-P-" + i, "分页活动 " + i, profile,
          i == 3 ? "PAUSED" : "ENABLED", "100.0000",
          List.of(metric(DAY_1, "100", "10", "1",
              new BigDecimal(i * 10).toPlainString(), "50.0000"))));
    }
    as(actorA, "channel.operation.create",
        () -> service.importCampaigns(new ImportRequest(
            AMAZON, shop,
            new ImportSyncRun("SUCCESS", DATA_AS_OF, STARTED_AT, FINISHED_AT, "", "batch-1"),
            campaigns)));

    // 第 0 页 2 条
    var first = as(actorA, "channel.operation.read", () -> service.campaigns(
        null, null, null, null, null, null, null, null, "SPEND_DESC", 0, 2));
    assertThat(first.items()).hasSize(2);
    assertThat(first.total()).isEqualTo(3);
    // 🔴 汇总不受分页影响：三条都在里面
    assertThat(first.summary().campaignCount()).isEqualTo(3);
    assertThat(first.summary().totalSpend()).isEqualByComparingTo("60");
    assertThat(first.summary().enabledCount()).isEqualTo(2);
    assertThat(first.summary().pausedCount()).isEqualTo(1);

    // 第 2 页 1 条
    var second = as(actorA, "channel.operation.read", () -> service.campaigns(
        null, null, null, null, null, null, null, null, "SPEND_DESC", 1, 2));
    assertThat(second.items()).hasSize(1);

    // 排序：消耗高到低（🔴 BigDecimal 的 equals 比的是**含标度**的值，30 与 30.0000 不相等）
    assertThat(first.items()).extracting(AdCampaignRow::spend)
        .containsExactly(new BigDecimal("30.0000"), new BigDecimal("20.0000"));

    // 状态筛选
    var pausedOnly = as(actorA, "channel.operation.read", () -> service.campaigns(
        null, null, "PAUSED", null, null, null, null, null, "SPEND_DESC", 0, 50));
    assertThat(pausedOnly.total()).isEqualTo(1);
    assertThat(pausedOnly.items().get(0).externalCampaignId()).isEqualTo("EXT-P-3");

    // 关键词筛选
    var searched = as(actorA, "channel.operation.read", () -> service.campaigns(
        null, null, null, null, null, "EXT-P-2", null, null, "SPEND_DESC", 0, 50));
    assertThat(searched.total()).isEqualTo(1);
    assertThat(searched.items().get(0).externalCampaignId()).isEqualTo("EXT-P-2");

    // 日期筛选：只保留该区间内有数据的活动
    var dated = as(actorA, "channel.operation.read", () -> service.campaigns(
        null, null, null, null, null, null, DAY_1, DAY_1, "SPEND_DESC", 0, 50));
    assertThat(dated.total()).isEqualTo(3);
    var otherDay = as(actorA, "channel.operation.read", () -> service.campaigns(
        null, null, null, null, null, null, DAY_2, DAY_2, "SPEND_DESC", 0, 50));
    assertThat(otherDay.total()).isZero();
    assertThat(otherDay.emptyReason()).isEqualTo("FILTERED_EMPTY");
  }

  @Test
  @DisplayName("14.1.1.1 按平台侧 id 取活动的当前状态与预算（供 14.2.2.2 规则引擎调用）")
  void externalReadShape() {
    seedPlatformMapping(AMAZON, "sponsored_products", "AUTO_DISCOVERY");
    String shop = seedShop(a, AMAZON, "EXT-AMZ-1");
    UUID profile = saveProfile(actorA, AMAZON, "sponsored_products", "AUTO_DISCOVERY");
    as(actorA, "channel.operation.create",
        () -> service.importCampaigns(importRequest(AMAZON, shop, profile, "EXT-READ-1",
            List.of(metric(DAY_1, "1000", "50", "5", "120.0000", "600.0000")))));

    var view = as(actorA, "channel.operation.read",
        () -> service.externalView(AMAZON, shop, "EXT-READ-1"));
    assertThat(view.status()).isEqualTo("ENABLED");
    assertThat(view.dailyBudget()).isEqualByComparingTo("100.0000");
    assertThat(view.spend()).isEqualByComparingTo("120");
    assertThat(view.sales()).isEqualByComparingTo("600");
    assertThat(view.acos()).isEqualByComparingTo("0.200000");
    assertThat(view.roas()).isEqualByComparingTo("5.000000");
    assertThat(view.dataAsOf().toInstant()).isEqualTo(DATA_AS_OF.toInstant());

    // 不存在 ⇒ 与跨租户同样的 404
    assertThatThrownBy(() -> as(actorA, "channel.operation.read",
        () -> service.externalView(AMAZON, shop, "NOT-THERE")))
        .hasMessageContaining("不存在");
  }

  @Test
  @DisplayName("14.1.1.1 跨租户与不存在返回逐字相同的 404")
  void crossTenantAndMissingAreIdentical() {
    seedPlatformMapping(AMAZON, "sponsored_products", "AUTO_DISCOVERY");
    String shopA = seedShop(a, AMAZON, "404-AMZ-1");
    UUID profile = saveProfile(actorA, AMAZON, "sponsored_products", "AUTO_DISCOVERY");
    as(actorA, "channel.operation.create",
        () -> service.importCampaigns(importRequest(AMAZON, shopA, profile, "EXT-404-1",
            List.of(metric(DAY_1, "10", "1", "0", "1.0000", "5.0000")))));
    var page = as(actorA, "channel.operation.read", () -> service.campaigns(
        null, null, null, null, null, null, null, null, "SPEND_DESC", 0, 50));
    UUID id = page.items().get(0).id();

    String missing = "99999999-9999-9999-9999-999999999999";
    var crossTenant = catchThrowable(() -> as(actorB, "channel.operation.read",
        () -> service.campaign(id.toString())));
    var notThere = catchThrowable(() -> as(actorB, "channel.operation.read",
        () -> service.campaign(missing)));
    // 🔴 两个都要真的抛了，且理由逐字相同 —— 否则「跨租户 404」可能被「恰好也报同样的话」骗过
    assertThat(crossTenant).isNotNull();
    assertThat(notThere).isNotNull();
    assertThat(crossTenant).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    assertThat(notThere).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    assertThat(crossTenant.getMessage()).isEqualTo(notThere.getMessage());
  }

  // ==================================================================
  // 8. 多租户三断言（AGENTS.md 硬性要求，缺一不可）
  // ==================================================================

  @Test
  @DisplayName("14.1.1.1 断言一：两租户互不可见")
  void tenantsAreIsolated() {
    seedPlatformMapping(AMAZON, "sponsored_products", "AUTO_DISCOVERY");
    String shopA = seedShop(a, AMAZON, "ISO-AMZ-1");
    String shopB = seedShop(b, AMAZON, "ISO-AMZ-2");
    UUID profileA = saveProfile(actorA, AMAZON, "sponsored_products", "AUTO_DISCOVERY");
    UUID profileB = saveProfile(actorB, AMAZON, "sponsored_products", "AUTO_DISCOVERY");

    as(actorA, "channel.operation.create",
        () -> service.importCampaigns(importRequest(AMAZON, shopA, profileA, "EXT-ISO-A",
            List.of(metric(DAY_1, "10", "1", "0", "1.0000", "5.0000")))));
    as(actorB, "channel.operation.create",
        () -> service.importCampaigns(importRequest(AMAZON, shopB, profileB, "EXT-ISO-B",
            List.of(metric(DAY_1, "20", "2", "0", "2.0000", "10.0000")))));

    // 活动列表互不可见
    assertThat(as(actorA, "channel.operation.read", () -> service.campaigns(
        null, null, null, null, null, null, null, null, "SPEND_DESC", 0, 50)).items())
        .extracting(AdCampaignRow::externalCampaignId).containsExactly("EXT-ISO-A");
    assertThat(as(actorB, "channel.operation.read", () -> service.campaigns(
        null, null, null, null, null, null, null, null, "SPEND_DESC", 0, 50)).items())
        .extracting(AdCampaignRow::externalCampaignId).containsExactly("EXT-ISO-B");

    // 汇总也互不可见
    assertThat(as(actorA, "channel.operation.read", () -> service.campaigns(
        null, null, null, null, null, null, null, null, "SPEND_DESC", 0, 50))
        .summary().totalSpend()).isEqualByComparingTo("1");
    assertThat(as(actorB, "channel.operation.read", () -> service.campaigns(
        null, null, null, null, null, null, null, null, "SPEND_DESC", 0, 50))
        .summary().totalSpend()).isEqualByComparingTo("2");

    // 同步状态互不可见
    assertThat(as(actorA, "channel.operation.read", () -> service.campaigns(
        null, null, null, null, null, null, null, null, "SPEND_DESC", 0, 50)).syncStates())
        .extracting(AdSyncState::shopCode).containsExactly("ISO-AMZ-1");
    assertThat(as(actorB, "channel.operation.read", () -> service.campaigns(
        null, null, null, null, null, null, null, null, "SPEND_DESC", 0, 50)).syncStates())
        .extracting(AdSyncState::shopCode).containsExactly("ISO-AMZ-2");

    // 详情也互不可见
    var pageA = as(actorA, "channel.operation.read", () -> service.campaigns(
        null, null, null, null, null, null, null, null, "SPEND_DESC", 0, 50));
    assertThatThrownBy(() -> as(actorB, "channel.operation.read",
        () -> service.campaign(pageA.items().get(0).id().toString())))
        .hasMessageContaining("不存在");
  }

  @Test
  @DisplayName("14.1.1.1 断言二：伪造 tenant header 无效（租户只来自已认证成员关系）")
  void forgedTenantHeaderIsIgnored() throws Exception {
    seedPlatformMapping(AMAZON, "sponsored_products", "AUTO_DISCOVERY");
    String shopA = seedShop(a, AMAZON, "FORGE-AMZ-1");
    String shopB = seedShop(b, AMAZON, "FORGE-AMZ-2");
    UUID profileA = saveProfile(actorA, AMAZON, "sponsored_products", "AUTO_DISCOVERY");
    UUID profileB = saveProfile(actorB, AMAZON, "sponsored_products", "AUTO_DISCOVERY");

    as(actorA, "channel.operation.create",
        () -> service.importCampaigns(importRequest(AMAZON, shopA, profileA, "EXT-FORGE-A",
            List.of(metric(DAY_1, "10", "1", "0", "1.0000", "5.0000")))));
    as(actorB, "channel.operation.create",
        () -> service.importCampaigns(importRequest(AMAZON, shopB, profileB, "EXT-FORGE-B",
            List.of(metric(DAY_1, "20", "2", "0", "2.0000", "10.0000")))));

    // A 的会话 + B 的头 ⇒ 仍然只看得见 A 的活动
    http.perform(get("/channel/ad-campaigns/campaigns")
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[*].externalCampaignId",
            org.hamcrest.Matchers.contains("EXT-FORGE-A")));

    // 伪造租户头也不能让 B 的活动被 A 读到
    var pageB = as(actorB, "channel.operation.read", () -> service.campaigns(
        null, null, null, null, null, null, null, null, "SPEND_DESC", 0, 50));
    http.perform(get("/channel/ad-campaigns/campaigns/" + pageB.items().get(0).id())
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isNotFound());

    // 无会话一律 401
    http.perform(get("/channel/ad-campaigns/campaigns")
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("14.1.1.1 断言三：连接池归还后不串租户（池压到 1，A→B→A 三轮）")
  void pooledConnectionsDoNotBleedTenants() {
    seedPlatformMapping(AMAZON, "sponsored_products", "AUTO_DISCOVERY");
    String shopA = seedShop(a, AMAZON, "POOL-AMZ-1");
    String shopB = seedShop(b, AMAZON, "POOL-AMZ-2");
    UUID profileA = saveProfile(actorA, AMAZON, "sponsored_products", "AUTO_DISCOVERY");
    UUID profileB = saveProfile(actorB, AMAZON, "sponsored_products", "AUTO_DISCOVERY");

    as(actorA, "channel.operation.create",
        () -> service.importCampaigns(importRequest(AMAZON, shopA, profileA, "EXT-POOL-A",
            List.of(metric(DAY_1, "10", "1", "0", "1.0000", "5.0000")))));
    as(actorB, "channel.operation.create",
        () -> service.importCampaigns(importRequest(AMAZON, shopB, profileB, "EXT-POOL-B",
            List.of(metric(DAY_1, "20", "2", "0", "2.0000", "10.0000")))));

    for (int i = 0; i < 3; i++) {
      // 列表 / 汇总 / 同步状态 / 详情是同一个连接池上的四个入口
      assertThat(as(actorA, "channel.operation.read", () -> service.campaigns(
          null, null, null, null, null, null, null, null, "SPEND_DESC", 0, 50)).items())
          .extracting(AdCampaignRow::externalCampaignId).containsExactly("EXT-POOL-A");
      assertThat(as(actorB, "channel.operation.read", () -> service.campaigns(
          null, null, null, null, null, null, null, null, "SPEND_DESC", 0, 50)).items())
          .extracting(AdCampaignRow::externalCampaignId).containsExactly("EXT-POOL-B");
      assertThat(as(actorA, "channel.operation.read", () -> service.campaigns(
          null, null, null, null, null, null, null, null, "SPEND_DESC", 0, 50))
          .summary().totalSpend()).isEqualByComparingTo("1");
      assertThat(as(actorB, "channel.operation.read", () -> service.campaigns(
          null, null, null, null, null, null, null, null, "SPEND_DESC", 0, 50))
          .summary().totalSpend()).isEqualByComparingTo("2");
      assertThat(as(actorA, "channel.operation.read", () -> service.campaigns(
          null, null, null, null, null, null, null, null, "SPEND_DESC", 0, 50)).syncStates())
          .extracting(AdSyncState::shopCode).containsExactly("POOL-AMZ-1");
      assertThat(as(actorB, "channel.operation.read", () -> service.campaigns(
          null, null, null, null, null, null, null, null, "SPEND_DESC", 0, 50)).syncStates())
          .extracting(AdSyncState::shopCode).containsExactly("POOL-AMZ-2");
    }
  }

  /**
   * 🔴 收尾：停掉本地桩与 Testcontainers 容器。
   *
   * <p>不回收容器的话，Ryuk 只在 JVM 退出时按 session 标签统一回收 —— 整个模块跑完之前容器一直活着
   * （实测 channel 一个服务就能堆到 110+ 个 postgres 容器），容器内存 + Docker VM 把机器压进 swap，
   * JVM 被卡住十几分钟 ⇒ 全量跑时断言假红、SSL 握手失败。
   *
   * <p>顺序：先停外部服务，再停容器。反了会出现「容器已停但还有 in-flight 请求打进来」。
   */
  @AfterAll
  static void stopTestResources() {
    if (identity != null) identity.stop(0);
    pg.stop();
  }
}
