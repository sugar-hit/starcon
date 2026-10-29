package com.starcon.channel;

import static com.starcon.channel.AdCampaignDetailModels.*;
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
 * 14.1.1.2 活动详情。
 *
 * <p>本测试类钉住本模块最容易做丢的几件事：
 *
 * <ol>
 *   <li><b>多租户三断言</b>（AGENTS.md 硬性要求，缺一不可）：两租户互不可见、伪造 tenant header 无效、
 *       连接池归还后不串租户
 *   <li><b>🔴 平台侧修改历史显式不可用</b>：{@code available=false} + ownerModule + impact，
 *       且当前展示的**本地**操作审计与「平台侧改历史」在措辞上分得开
 *   <li><b>🔴 复制结构的副本是真的、平台侧还不是</b>：目标活动真落库 + 标「待回写平台」+
 *       状态暂停 + 平台侧 id 是本地标记 + <b>关键词数恒为 0</b>
 *   <li><b>🔴 关键词读上游</b>：本模块不建第二张关键词表，读的是 14.1.3.1 的 ad_keyword
 *   <li><b>🔴 改竞价的护栏</b>：单次上限与绝对上限逐条拒绝，被拒的也留审计
 *   <li><b>🔴 替换语义</b>：结构登记里没出现的广告组真的被删掉
 *   <li><b>时间字段写回值正确</b>（AGENTS.md 要求断言写回值，不能只断言没抛异常）
 *   <li>真分页真筛选；跨租户与不存在返回逐字相同的 404
 * </ol>
 *
 * <p><b>时刻一律用固定的 {@link OffsetDateTime} 钉死</b>，不用 {@code now()}：用墙钟会让
 * 「这次写入落在哪个断言分支上」成为一个 flaky 断言。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AdCampaignDetailIntegrationTest {

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
  static final OffsetDateTime STRUCTURE_AS_OF = OffsetDateTime.parse("2026-10-05T11:30:00+08:00");
  static final LocalDate DAY_1 = LocalDate.of(2026, 10, 1);
  static final LocalDate DAY_2 = LocalDate.of(2026, 10, 2);
  static final LocalDate DAY_3 = LocalDate.of(2026, 10, 3);

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

  /** 播种一个 2.1.2.1 的平台默认映射（全局码表）。 */
  static void seedPlatformMapping(UUID platformId, String external, String internal) {
    exec("insert into platform_enum_mapping (id,platform_id,enum_kind,external_value,internal_value)"
        + " values ('" + UUID.randomUUID() + "','" + platformId + "','AD_TYPE','" + external
        + "','" + internal + "')"
        + " on conflict (platform_id,enum_kind,external_value) do nothing");
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
    // 🔴 广告类型档案的归一口径由 2.1.2.1 解析 ⇒ 必须先有这条平台默认映射，
    //   否则 14.1.2.2 会拒「声明的归一类型与解析结果不一致」。
    seedPlatformMapping(AMAZON, "sponsored_products", "AUTO_DISCOVERY");
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

  @Autowired AdCampaignDetailService service;
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

  /**
   * 以租户身份**查**一个值。
   *
   * <p>🔴 为什么不能直接用管理员连接查：这些表开了 {@code FORCE ROW LEVEL SECURITY}，
   * 连表所有者（迁移用户）都要过策略 —— 管理员连接上 {@code app.current_tenant} 是空的，
   * 查出来永远是 0 行。
   */
  static String execOneAsTenant(UUID tenant, String sql) {
    try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute("select set_config('app.current_tenant','" + tenant + "',false)");
      s.execute("select set_config('app.actor','" + user + "',false)");
      s.execute("select set_config('app.department','',false)");
      s.execute("select set_config('app.permission','channel.operation.create',false)");
      s.execute("select set_config('app.grants','" + ALL_SCOPE_GRANTS + "',false)");
      try (var rs = s.executeQuery(sql)) {
        if (rs.next()) {
          return rs.getString(1);
        }
        throw new IllegalStateException("查询没有返回行：" + sql);
      }
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

  /** 播种一个 listing（4.1.1.1，同库同服务）。 */
  UUID seedListing(UUID tenant, String shopCode, String externalItemId, String title) {
    UUID shopId =
        UUID.fromString(
            execOneAsTenant(
                tenant,
                "select id from shop where tenant_id = '" + tenant + "' and code = '" + shopCode + "'"));
    UUID id = UUID.randomUUID();
    execAsTenant(tenant,
        "insert into listing (id,tenant_id,shop_id,external_item_id,title,status)"
            + " values ('" + id + "','" + tenant + "','" + shopId + "','" + externalItemId + "','" + title
            + "','ON_SALE')");
    return id;
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

  /** 通过**上游 14.1.1.1** 登记一个活动（含每日指标）。 */
  UUID seedCampaign(
      AccessService.Access who, UUID platformId, String shop, UUID profileId, String externalId,
      String name, String budget) {
    as(who, "channel.operation.create",
        () -> campaigns.importCampaigns(new ImportRequest(
            platformId, shop,
            new ImportSyncRun("SUCCESS", DATA_AS_OF, STARTED_AT, FINISHED_AT, "", "batch-1"),
            List.of(
                new ImportCampaign(
                    externalId, name, profileId, "ENABLED", "USD",
                    budget == null ? null : new BigDecimal(budget),
                    null, null,
                    List.of(
                        new ImportDailyMetric(
                            DAY_1, new BigDecimal("1000"), new BigDecimal("50"), new BigDecimal("5"),
                            new BigDecimal("120.0000"), new BigDecimal("600.0000")),
                        new ImportDailyMetric(
                            DAY_2, new BigDecimal("800"), new BigDecimal("40"), new BigDecimal("4"),
                            new BigDecimal("100.0000"), new BigDecimal("500.0000")),
                        new ImportDailyMetric(
                            DAY_3, new BigDecimal("600"), new BigDecimal("30"), new BigDecimal("3"),
                            new BigDecimal("80.0000"), new BigDecimal("400.0000"))),
                    "测试登记")))));
    String id =
        execOneAsTenant(
            who.tenantId(),
            "select id from ad_campaign where tenant_id = '" + who.tenantId()
                + "' and external_campaign_id = '" + externalId + "'");
    return UUID.fromString(id);
  }

  /** 登记结构（广告组 + 商品 + 创意）。 */
  AdCampaignStructureImportResult importStructure(
      AccessService.Access who, String campaignId, List<AdCampaignGroupImport> groups,
      List<AdCampaignCreativeImport> creatives) {
    return as(who, "channel.operation.create",
        () -> service.importStructure(
            campaignId,
            new AdCampaignStructureImportRequest(STRUCTURE_AS_OF, groups, creatives)));
  }

  static AdCampaignGroupImport group(String externalId, String name, List<AdCampaignProductImport> products,
      List<AdCampaignCreativeImport> creatives) {
    return new AdCampaignGroupImport(
        externalId, name, "ENABLED", new BigDecimal("1.5000"), "USD",
        new BigDecimal("50.0000"), "AUTO", products, creatives);
  }

  // ==================================================================
  // 1. 能力声明：未交付的段必须显式 available=false
  // ==================================================================

  @Test
  @DisplayName("14.1.1.2 能力声明：平台侧修改历史 / 平台回写 / 广告组管理 / 竞价策略显式未交付")
  void capabilitiesDeclareUndeliveredParts() {
    var caps = as(actorA, "channel.operation.read", () -> service.capabilities());
    Map<String, AdCampaignDetailCapabilityDimension> dims =
        caps.dimensions().stream()
            .collect(java.util.stream.Collectors.toMap(AdCampaignDetailCapabilityDimension::dimension, d -> d));

    assertThat(dims.get("campaign_panorama").available()).isTrue();
    assertThat(dims.get("budget_curve").available()).isTrue();
    assertThat(dims.get("ad_group_list").available()).isTrue();
    assertThat(dims.get("targeted_products").available()).isTrue();
    assertThat(dims.get("creatives").available()).isTrue();
    assertThat(dims.get("keywords").available()).isTrue();
    assertThat(dims.get("daily_performance").available()).isTrue();
    assertThat(dims.get("bid_change").available()).isTrue();
    assertThat(dims.get("copy_structure").available()).isTrue();

    // 🔴 平台侧修改历史：没有同步源 ⇒ 显式 false + ownerModule + 影响
    assertThat(dims.get("platform_change_history").available()).isFalse();
    assertThat(dims.get("platform_change_history").ownerModule()).contains("平台广告 API 同步");
    assertThat(dims.get("platform_change_history").impact()).contains("拿不到");
    assertThat(dims.get("platform_writeback").available()).isFalse();
    assertThat(dims.get("group_management").available()).isFalse();
    assertThat(dims.get("group_management").ownerModule()).isEqualTo("14.1.2.1 广告组管理");
    assertThat(dims.get("bid_strategy").available()).isFalse();
    assertThat(dims.get("bid_strategy").ownerModule()).isEqualTo("14.2.1.1 竞价管理");
    assertThat(dims.get("targeting").available()).isFalse();
    assertThat(dims.get("keyword_copy").available()).isFalse();
    assertThat(dims.get("report").available()).isFalse();

    // 🔴 护栏上限必须给出来，界面上要能写明天花板
    assertThat(caps.bidGuardrails().maxBid()).isEqualByComparingTo("1000");
    assertThat(caps.bidGuardrails().maxSingleBidDelta()).isEqualByComparingTo("200");
    assertThat(caps.bidGuardrails().maxGroupsPerRequest()).isEqualTo(100);
  }

  // ==================================================================
  // 2. 结构真的落库
  // ==================================================================

  @Test
  @DisplayName("14.1.1.2 结构登记真的落库：广告组 / 投放商品 / 创意都读得回来")
  void structureImportLandsRealRows() {
    String shop = seedShop(a, AMAZON, "DET-AMZ-1");
    UUID profile = saveProfile(actorA, AMAZON, "sponsored_products", "AUTO_DISCOVERY");
    UUID campaignId = seedCampaign(actorA, AMAZON, shop, profile, "EXT-DET-1", "详情活动", "100.0000");
    UUID listingId = seedListing(a, shop, "ASIN-DET-1", "详情测试商品");

    var result =
        importStructure(
            actorA,
            campaignId.toString(),
            List.of(
                group(
                    "GRP-1",
                    "组一",
                    List.of(new AdCampaignProductImport(listingId, null, null)),
                    List.of(
                        new AdCampaignCreativeImport(
                            "CR-VIDEO-1", "视频素材", "VIDEO", "https://cdn.example/v1.mp4", 30, "ENABLED"),
                        new AdCampaignCreativeImport(
                            "CR-IMG-1", "图片素材", "IMAGE", "https://cdn.example/i1.png", null, "ENABLED")))),
            List.of(new AdCampaignCreativeImport("CR-CAMP-1", "活动级图集", "CAROUSEL", "https://cdn.example/c1", null, "ENABLED")));

    assertThat(result.groupCount()).isEqualTo(1);
    assertThat(result.productCount()).isEqualTo(1);
    assertThat(result.creativeCount()).isEqualTo(3);

    // 广告组列表读得回来
    var groups = as(actorA, "channel.operation.read", () -> service.groups(campaignId.toString(), null, null, 0, 50));
    assertThat(groups.total()).isEqualTo(1);
    assertThat(groups.items()).hasSize(1);
    AdCampaignGroupRow g = groups.items().get(0);
    assertThat(g.externalGroupId()).isEqualTo("GRP-1");
    assertThat(g.groupName()).isEqualTo("组一");
    assertThat(g.defaultBid()).isEqualByComparingTo("1.5000");
    assertThat(g.bidCurrency()).isEqualTo("USD");
    assertThat(g.dailyBudget()).isEqualByComparingTo("50.0000");
    assertThat(g.targetingType()).isEqualTo("AUTO");
    assertThat(g.productCount()).isEqualTo(1);
    assertThat(g.creativeCount()).isEqualTo(2);
    // 🔴 时间字段断言**写回值**（AGENTS.md）
    assertThat(g.dataAsOf().toInstant()).isEqualTo(STRUCTURE_AS_OF.toInstant());

    // 投放商品：listing 没给时**从 listing 读真实值**，不编
    var products = as(actorA, "channel.operation.read", () -> service.products(campaignId.toString(), null, 0, 50));
    assertThat(products.total()).isEqualTo(1);
    AdCampaignProductRow p = products.items().get(0);
    assertThat(p.listingId()).isEqualTo(listingId);
    assertThat(p.externalItemId()).isEqualTo("ASIN-DET-1");
    assertThat(p.title()).isEqualTo("详情测试商品");
    assertThat(p.listingStatus()).isEqualTo("ON_SALE");
    assertThat(p.groupName()).isEqualTo("组一");

    // 创意：组内 2 条 + 活动级 1 条
    var creatives = as(actorA, "channel.operation.read", () -> service.creatives(campaignId.toString(), null, 0, 50));
    assertThat(creatives.total()).isEqualTo(3);
    assertThat(creatives.items()).extracting(AdCampaignCreativeRow::creativeType)
        .containsExactlyInAnyOrder("VIDEO", "IMAGE", "CAROUSEL");
    AdCampaignCreativeRow video =
        creatives.items().stream()
            .filter(c -> c.creativeType().equals("VIDEO"))
            .findFirst()
            .orElseThrow();
    assertThat(video.durationSeconds()).isEqualTo(30);
    assertThat(video.groupName()).isEqualTo("组一");
    assertThat(video.dataAsOf().toInstant()).isEqualTo(STRUCTURE_AS_OF.toInstant());

    // 按类型筛选
    var onlyVideo = as(actorA, "channel.operation.read",
        () -> service.creatives(campaignId.toString(), "VIDEO", 0, 50));
    assertThat(onlyVideo.total()).isEqualTo(1);
  }

  @Test
  @DisplayName("14.1.1.2 结构登记是替换语义：载荷里没出现的广告组真的被删掉")
  void structureImportReplacesRemovedGroups() {
    String shop = seedShop(a, AMAZON, "DET-AMZ-2");
    UUID profile = saveProfile(actorA, AMAZON, "sponsored_products", "AUTO_DISCOVERY");
    UUID campaignId = seedCampaign(actorA, AMAZON, shop, profile, "EXT-DET-2", "替换活动", "100.0000");

    importStructure(actorA, campaignId.toString(),
        List.of(group("GRP-A", "组 A", List.of(), List.of()),
               group("GRP-B", "组 B", List.of(), List.of())),
        List.of());

    var result = importStructure(actorA, campaignId.toString(),
        List.of(group("GRP-A", "组 A", List.of(), List.of())), List.of());

    assertThat(result.groupCount()).isEqualTo(1);
    assertThat(result.removedGroupCount()).isEqualTo(1);

    var groups = as(actorA, "channel.operation.read", () -> service.groups(campaignId.toString(), null, null, 0, 50));
    assertThat(groups.total()).isEqualTo(1);
    assertThat(groups.items()).extracting(AdCampaignGroupRow::externalGroupId).containsExactly("GRP-A");
  }

  // ==================================================================
  // 3. 详情全景 + 预算与消耗曲线
  // ==================================================================

  @Test
  @DisplayName("14.1.1.2 全景：基本设置 + 预算与消耗曲线 + 每日明细 + 结构条数")
  void panoramaShowsSettingsCurveAndStructure() {
    String shop = seedShop(a, AMAZON, "DET-AMZ-3");
    UUID profile = saveProfile(actorA, AMAZON, "sponsored_products", "AUTO_DISCOVERY");
    UUID campaignId = seedCampaign(actorA, AMAZON, shop, profile, "EXT-DET-3", "全景活动", "100.0000");
    UUID listingId = seedListing(a, shop, "ASIN-DET-3", "全景商品");
    importStructure(actorA, campaignId.toString(),
        List.of(group("GRP-1", "组一", List.of(new AdCampaignProductImport(listingId, null, null)), List.of())),
        List.of());

    var view = as(actorA, "channel.operation.read",
        () -> service.panorama(campaignId.toString(), null, null));

    // 基本设置（复用上游 14.1.1.1 的形状）
    assertThat(view.campaign().campaignName()).isEqualTo("全景活动");
    assertThat(view.campaign().dailyBudget()).isEqualByComparingTo("100.0000");
    assertThat(view.campaign().currency()).isEqualTo("USD");
    assertThat(view.campaign().platformAdType()).isEqualTo("sponsored_products");
    assertThat(view.campaign().internalAdType()).isEqualTo("AUTO_DISCOVERY");

    // 预算与消耗曲线
    assertThat(view.budgetCurve().dailyBudget()).isEqualByComparingTo("100.0000");
    assertThat(view.budgetCurve().windowDays()).isEqualTo(3);
    assertThat(view.budgetCurve().totalSpend()).isEqualByComparingTo("300.0000");
    assertThat(view.budgetCurve().totalSales()).isEqualByComparingTo("1500.0000");
    // 🔴 区间 ACOS = 总消耗 / 总销售额（先汇总再相除）
    assertThat(view.budgetCurve().acos()).isEqualByComparingTo("0.200000");
    assertThat(view.budgetCurve().roas()).isEqualByComparingTo("5.000000");
    assertThat(view.budgetCurve().windowBudget()).isEqualByComparingTo("300.0000");
    assertThat(view.budgetCurve().points()).hasSize(3);
    AdCampaignBudgetCurvePoint first = view.budgetCurve().points().get(0);
    assertThat(first.date()).isEqualTo(DAY_1);
    assertThat(first.spend()).isEqualByComparingTo("120.0000");
    // 🔴 预算使用率 = 消耗 / 日预算
    assertThat(first.budgetUsageRate()).isEqualByComparingTo("1.200000");
    assertThat(first.dailyBudget()).isEqualByComparingTo("100.0000");

    // 每日表现明细
    assertThat(view.dailyMetrics()).hasSize(3);

    // 结构条数与新鲜度
    assertThat(view.structure().groupCount()).isEqualTo(1);
    assertThat(view.structure().productCount()).isEqualTo(1);
    assertThat(view.structure().creativeCount()).isZero();
    assertThat(view.structure().structureAsOf().toInstant()).isEqualTo(STRUCTURE_AS_OF.toInstant());

    // 操作入口：四个动作在、平台侧两件不在
    Map<String, AdCampaignOperationEntry> ops =
        view.operations().entries().stream()
            .collect(java.util.stream.Collectors.toMap(AdCampaignOperationEntry::operation, o -> o));
    assertThat(ops.get("SET_BUDGET").available()).isTrue();
    assertThat(ops.get("PAUSE").available()).isTrue();
    assertThat(ops.get("ADJUST_BID").available()).isTrue();
    assertThat(ops.get("COPY_STRUCTURE").available()).isTrue();
    assertThat(ops.get("PLATFORM_WRITEBACK").available()).isFalse();
    assertThat(ops.get("PLATFORM_CHANGE_HISTORY").available()).isFalse();
  }

  @Test
  @DisplayName("14.1.1.2 曲线窗口超过上限被拒；没有日预算时预算使用率不可比而不是 0")
  void curveWindowIsBoundedAndBudgetUsageIsHonest() {
    String shop = seedShop(a, AMAZON, "DET-AMZ-4");
    UUID profile = saveProfile(actorA, AMAZON, "sponsored_products", "AUTO_DISCOVERY");
    UUID campaignId = seedCampaign(actorA, AMAZON, shop, profile, "EXT-DET-4", "无预算活动", null);

    var view = as(actorA, "channel.operation.read",
        () -> service.panorama(campaignId.toString(), null, null));
    // 🔴 没有日预算 ⇒ 预算使用率不可比（null），不是 0
    assertThat(view.budgetCurve().dailyBudget()).isNull();
    assertThat(view.budgetCurve().windowBudget()).isNull();
    assertThat(view.budgetCurve().points()).isNotEmpty();
    assertThat(view.budgetCurve().points().get(0).budgetUsageRate()).isNull();

    assertThatThrownBy(() -> as(actorA, "channel.operation.read",
        () -> service.panorama(campaignId.toString(), DAY_1.minusDays(500), DAY_3)))
        .hasMessageContaining("预算曲线区间最多");
  }

  // ==================================================================
  // 4. 🔴 平台侧修改历史：显式不可用 + 本地操作审计
  // ==================================================================

  @Test
  @DisplayName("14.1.1.2 平台侧修改历史显式不可用；当前展示的是本地操作审计")
  void platformChangeHistoryIsExplicitlyUnavailable() {
    String shop = seedShop(a, AMAZON, "DET-AMZ-5");
    UUID profile = saveProfile(actorA, AMAZON, "sponsored_products", "AUTO_DISCOVERY");
    UUID campaignId = seedCampaign(actorA, AMAZON, shop, profile, "EXT-DET-5", "改历史活动", "100.0000");

    // 先在上游做一次真实操作，产生一条本地审计
    as(actorA, "channel.operation.update",
        () -> campaigns.bulkOperation(new BulkOperationRequest(
            List.of(campaignId), "PAUSE", null, null, "测试暂停")));

    var history = as(actorA, "channel.operation.read",
        () -> service.changeHistory(campaignId.toString()));

    // 🔴 没有同步源 ⇒ 显式不可用
    assertThat(history.available()).isFalse();
    assertThat(history.ownerModule()).contains("平台广告 API 同步");
    assertThat(history.impact()).contains("拿不到");
    assertThat(history.note()).contains("平台侧修改历史");
    // 🔴 当前展示的是**本地**操作审计，措辞上要与「平台侧改历史」分得开
    assertThat(history.localAuditNote()).contains("本地");
    assertThat(history.localAuditNote()).contains("不是");
    assertThat(history.localAudit()).isNotEmpty();
    assertThat(history.localAudit().get(0).operation()).isEqualTo("PAUSE");
  }

  // ==================================================================
  // 5. 🔴 复制结构
  // ==================================================================

  @Test
  @DisplayName("14.1.1.2 复制结构：结构副本真的落库，平台侧还没有；关键词数恒为 0")
  void copyStructureLandsRealCopyAndMarksPendingWriteback() {
    String shop = seedShop(a, AMAZON, "DET-AMZ-6");
    UUID profile = saveProfile(actorA, AMAZON, "sponsored_products", "AUTO_DISCOVERY");
    UUID campaignId = seedCampaign(actorA, AMAZON, shop, profile, "EXT-DET-6", "跑得好���活动", "100.0000");
    UUID listingId = seedListing(a, shop, "ASIN-DET-6", "源商品");
    importStructure(actorA, campaignId.toString(),
        List.of(
            group("GRP-1", "组一", List.of(new AdCampaignProductImport(listingId, null, null)),
                List.of(new AdCampaignCreativeImport("CR-1", "素材", "IMAGE", "https://cdn/x.png", null, "ENABLED"))),
            group("GRP-2", "组二", List.of(), List.of())),
        List.of());

    var result = as(actorA, "channel.operation.create",
        () -> service.copy(campaignId.toString(),
            new AdCampaignCopyRequest(shop, null, "KEEP_PRODUCTS", null, "复制到同店")));

    // 🔴 结构副本真的落库
    assertThat(result.targetCampaignId()).isNotNull().isNotEqualTo(campaignId);
    assertThat(result.groupCount()).isEqualTo(2);
    assertThat(result.productCount()).isEqualTo(1);
    assertThat(result.creativeCount()).isEqualTo(1);
    // 🔴 关键词不复制
    assertThat(result.keywordCount()).isZero();
    // 🔴 平台侧还没有这份结构
    assertThat(result.pendingPlatformWriteback()).isTrue();
    assertThat(result.targetStatus()).isEqualTo("PAUSED");
    assertThat(result.note()).contains("平台侧还没有这份结构");
    assertThat(result.note()).contains("关键词");

    // 目标活动真的在库里，且是「本地草稿」
    String targetId = result.targetCampaignId().toString();
    var target = as(actorA, "channel.operation.read", () -> service.panorama(targetId, null, null));
    assertThat(target.campaign().campaignName()).contains("结构副本");
    assertThat(target.campaign().status()).isEqualTo("PAUSED");
    assertThat(target.campaign().pendingPlatformWriteback()).isTrue();
    // 🔴 平台侧 id 是**本地标记**，不是像平台 id 的字符串
    assertThat(target.campaign().externalCampaignId()).startsWith("LOCAL-COPY-");
    // 🔴 复制的是结构不是钱：预算没有跟着复制
    assertThat(target.campaign().dailyBudget()).isNull();
    // 结构真的复制过去了
    var targetGroups = as(actorA, "channel.operation.read", () -> service.groups(targetId, null, null, 0, 50));
    assertThat(targetGroups.total()).isEqualTo(2);
    var targetProducts = as(actorA, "channel.operation.read", () -> service.products(targetId, null, 0, 50));
    assertThat(targetProducts.total()).isEqualTo(1);
    assertThat(targetProducts.items().get(0).listingId()).isEqualTo(listingId);
    var targetCreatives = as(actorA, "channel.operation.read", () -> service.creatives(targetId, null, 0, 50));
    assertThat(targetCreatives.total()).isEqualTo(1);

    // 源活动不受影响
    var source = as(actorA, "channel.operation.read", () -> service.panorama(campaignId.toString(), null, null));
    assertThat(source.structure().groupCount()).isEqualTo(2);
  }

  @Test
  @DisplayName("14.1.1.2 复制到新 ASIN：改投目标 listing，且必须属于目标店铺")
  void copyStructureToNewListingSwapsProducts() {
    String shop = seedShop(a, AMAZON, "DET-AMZ-7");
    UUID profile = saveProfile(actorA, AMAZON, "sponsored_products", "AUTO_DISCOVERY");
    UUID campaignId = seedCampaign(actorA, AMAZON, shop, profile, "EXT-DET-7", "换 ASIN 活动", "100.0000");
    UUID sourceListing = seedListing(a, shop, "ASIN-OLD", "老商品");
    UUID targetListing = seedListing(a, shop, "ASIN-NEW", "新商品");
    importStructure(actorA, campaignId.toString(),
        List.of(group("GRP-1", "组一", List.of(new AdCampaignProductImport(sourceListing, null, null)), List.of())),
        List.of());

    var result = as(actorA, "channel.operation.create",
        () -> service.copy(campaignId.toString(),
            new AdCampaignCopyRequest(shop, targetListing, "TARGET_LISTING", "新 ASIN 活动", null)));

    assertThat(result.productMode()).isEqualTo("TARGET_LISTING");
    assertThat(result.targetListingId()).isEqualTo(targetListing);
    assertThat(result.productCount()).isEqualTo(1);

    var targetProducts = as(actorA, "channel.operation.read",
        () -> service.products(result.targetCampaignId().toString(), null, 0, 50));
    assertThat(targetProducts.items().get(0).listingId()).isEqualTo(targetListing);
    assertThat(targetProducts.items().get(0).externalItemId()).isEqualTo("ASIN-NEW");
  }

  @Test
  @DisplayName("14.1.1.2 复制结构的边界：跨店铺沿用商品被拒 / 跨平台店铺被拒")
  void copyStructureRejectsInvalidTargets() {
    String shop = seedShop(a, AMAZON, "DET-AMZ-8");
    String otherShop = seedShop(a, AMAZON, "DET-AMZ-9");
    String otherPlatformShop = seedShop(a, TIKTOK, "DET-TT-1");
    UUID profile = saveProfile(actorA, AMAZON, "sponsored_products", "AUTO_DISCOVERY");
    UUID campaignId = seedCampaign(actorA, AMAZON, shop, profile, "EXT-DET-8", "边界活动", "100.0000");

    // 🔴 listing 属于店铺 ⇒ 跨店铺「沿用源投放商品」在物理上就不成立
    assertThatThrownBy(() -> as(actorA, "channel.operation.create",
        () -> service.copy(campaignId.toString(),
            new AdCampaignCopyRequest(otherShop, null, "KEEP_PRODUCTS", null, null))))
        .hasMessageContaining("同一个店铺");

    // 🔴 广告类型档案按平台归一 ⇒ 跨平台复制不成立
    assertThatThrownBy(() -> as(actorA, "channel.operation.create",
        () -> service.copy(campaignId.toString(),
            new AdCampaignCopyRequest(otherPlatformShop, null, "KEEP_PRODUCTS", null, null))))
        .hasMessageContaining("同一平台");

    // 目标店铺不存在 ⇒ 404
    assertThatThrownBy(() -> as(actorA, "channel.operation.create",
        () -> service.copy(campaignId.toString(),
            new AdCampaignCopyRequest("NO-SUCH-SHOP", null, "KEEP_PRODUCTS", null, null))))
        .hasMessageContaining("不存在");
  }

  // ==================================================================
  // 6. 🔴 改竞价：护栏 + 审计
  // ==================================================================

  @Test
  @DisplayName("14.1.1.2 改竞价：生效 / 跳过 / 超限逐条拒绝，被拒的也留审计")
  void bidUpdateAppliesGuardsAndAudits() {
    String shop = seedShop(a, AMAZON, "DET-AMZ-10");
    UUID profile = saveProfile(actorA, AMAZON, "sponsored_products", "AUTO_DISCOVERY");
    UUID campaignId = seedCampaign(actorA, AMAZON, shop, profile, "EXT-DET-10", "改竞价活动", "100.0000");
    UUID listingId = seedListing(a, shop, "ASIN-DET-10", "竞价商品");
    importStructure(actorA, campaignId.toString(),
        List.of(group("GRP-1", "组一", List.of(new AdCampaignProductImport(listingId, null, null)), List.of())),
        List.of());
    UUID groupId = as(actorA, "channel.operation.read", () -> service.groups(campaignId.toString(), null, null, 0, 50))
        .items().get(0).id();

    // 第一次请求：生效一条 + 一条指向不存在的组被拒
    var applied = as(actorA, "channel.operation.update",
        () -> service.updateBids(campaignId.toString(),
            new AdCampaignBidUpdateRequest(List.of(
                new AdCampaignBidUpdateItem(groupId, new BigDecimal("2.0000"), "提价"),
                new AdCampaignBidUpdateItem(UUID.randomUUID(), new BigDecimal("2.0000"), "乱指")))));
    assertThat(applied.applied()).isEqualTo(1);
    assertThat(applied.rejected()).isEqualTo(1);
    assertThat(applied.items().get(0).beforeBid()).isEqualByComparingTo("1.5000");
    assertThat(applied.items().get(0).afterBid()).isEqualByComparingTo("2.0000");

    // 🔴 同一次请求里同一个组不能重复 ⇒ 400
    assertThatThrownBy(() -> as(actorA, "channel.operation.update",
        () -> service.updateBids(campaignId.toString(),
            new AdCampaignBidUpdateRequest(List.of(
                new AdCampaignBidUpdateItem(groupId, new BigDecimal("3.0000"), "a"),
                new AdCampaignBidUpdateItem(groupId, new BigDecimal("4.0000"), "b"))))))
        .hasMessageContaining("不能重复调整同一个广告组");

    var overCap = as(actorA, "channel.operation.update",
        () -> service.updateBids(campaignId.toString(),
            new AdCampaignBidUpdateRequest(List.of(
                new AdCampaignBidUpdateItem(groupId, new BigDecimal("2.0000"), "不变")))));
    assertThat(overCap.skipped()).isEqualTo(1);
    assertThat(overCap.items().get(0).result()).isEqualTo("SKIPPED");

    var tooHigh = as(actorA, "channel.operation.update",
        () -> service.updateBids(campaignId.toString(),
            new AdCampaignBidUpdateRequest(List.of(
                new AdCampaignBidUpdateItem(groupId, new BigDecimal("1000.0000"), "越界")))));
    assertThat(tooHigh.rejected()).isEqualTo(1);
    assertThat(tooHigh.items().get(0).message()).contains("上限");

    var tooBigDelta = as(actorA, "channel.operation.update",
        () -> service.updateBids(campaignId.toString(),
            new AdCampaignBidUpdateRequest(List.of(
                new AdCampaignBidUpdateItem(groupId, new BigDecimal("500.0000"), "越界2")))));
    assertThat(tooBigDelta.rejected()).isEqualTo(1);
    assertThat(tooBigDelta.items().get(0).message()).contains("上限");

    // 生效的那条真的落库了
    var group = as(actorA, "channel.operation.read", () -> service.groups(campaignId.toString(), null, null, 0, 50))
        .items().get(0);
    assertThat(group.defaultBid()).isEqualByComparingTo("2.0000");

    // 🔴 审计留痕：生效 / 被拒 / 跳过**都**留（5 条：1 生效 + 3 被拒 + 1 跳过）
    String logCount = execOneAsTenant(
        a, "select count(*) from ad_campaign_group_bid_log where tenant_id = '" + a + "'");
    assertThat(logCount).isEqualTo("5");
    String appliedCount = execOneAsTenant(
        a, "select count(*) from ad_campaign_group_bid_log where tenant_id = '" + a + "' and result = 'APPLIED'");
    assertThat(appliedCount).isEqualTo("1");
    String rejectedCount = execOneAsTenant(
        a, "select count(*) from ad_campaign_group_bid_log where tenant_id = '" + a + "' and result = 'REJECTED'");
    assertThat(rejectedCount).isEqualTo("3");
    String skippedCount = execOneAsTenant(
        a, "select count(*) from ad_campaign_group_bid_log where tenant_id = '" + a + "' and result = 'SKIPPED'");
    assertThat(skippedCount).isEqualTo("1");
  }

  @Test
  @DisplayName("14.1.1.2 改竞价：广告组不属于本活动时被逐条拒绝")
  void bidUpdateRejectsForeignGroup() {
    String shop = seedShop(a, AMAZON, "DET-AMZ-11");
    UUID profile = saveProfile(actorA, AMAZON, "sponsored_products", "AUTO_DISCOVERY");
    UUID campaignId = seedCampaign(actorA, AMAZON, shop, profile, "EXT-DET-11", "改竞价活动 2", "100.0000");

    var result = as(actorA, "channel.operation.update",
        () -> service.updateBids(campaignId.toString(),
            new AdCampaignBidUpdateRequest(List.of(
                new AdCampaignBidUpdateItem(UUID.randomUUID(), new BigDecimal("2.0000"), "乱指")))));

    assertThat(result.rejected()).isEqualTo(1);
    assertThat(result.items().get(0).message()).contains("不属于当前活动");
  }

  // ==================================================================
  // 7. 关键词读上游（不建第二张表）
  // ==================================================================

  @Test
  @DisplayName("14.1.1.2 关键词读上游 14.1.3.1 的表；本模块不建第二张关键词表")
  void keywordsComeFromUpstreamTable() {
    String shop = seedShop(a, AMAZON, "DET-AMZ-12");
    UUID profile = saveProfile(actorA, AMAZON, "sponsored_products", "AUTO_DISCOVERY");
    UUID campaignId = seedCampaign(actorA, AMAZON, shop, profile, "EXT-DET-12", "关键词活动", "100.0000");
    String shopId = execOneAsTenant(a,
        "select id from shop where tenant_id = '" + a + "' and code = '" + shop + "'");

    execAsTenant(a,
        "insert into ad_keyword (id,tenant_id,platform_id,shop_id,external_campaign_id,"
            + " external_keyword_id,keyword,internal_ad_type,platform_ad_type,bid,bid_currency,"
            + " metric_currency,status,note,created_at,updated_at)"
            + " values ('" + UUID.randomUUID() + "','" + a + "','" + AMAZON + "','" + shopId
            + "','EXT-DET-12','KW-1','蓝牙耳机','AUTO_DISCOVERY','sponsored_products',"
            + "1.2000,'USD','USD','ACTIVE','测试关键词',now(),now())");

    var page = as(actorA, "channel.operation.read", () -> service.keywords(campaignId.toString(), null, 0, 50));
    assertThat(page.total()).isEqualTo(1);
    AdCampaignKeywordRow kw = page.items().get(0);
    assertThat(kw.keyword()).isEqualTo("蓝牙耳机");
    assertThat(kw.externalKeywordId()).isEqualTo("KW-1");
    assertThat(kw.bid()).isEqualByComparingTo("1.2000");
    // 🔴 原始标记与归一类型并存
    assertThat(kw.platformAdType()).isEqualTo("sponsored_products");
    assertThat(kw.internalAdType()).isEqualTo("AUTO_DISCOVERY");

    // 全景里的关键词条数与列表一致
    var view = as(actorA, "channel.operation.read", () -> service.panorama(campaignId.toString(), null, null));
    assertThat(view.structure().keywordCount()).isEqualTo(1);
  }

  // ==================================================================
  // 8. 多租户三断言（AGENTS.md 硬性要求，缺一不可）
  // ==================================================================

  @Test
  @DisplayName("14.1.1.2 断言一：两租户互不可见")
  void tenantsAreIsolated() {
    String shopA = seedShop(a, AMAZON, "DET-ISO-AMZ-1");
    String shopB = seedShop(b, AMAZON, "DET-ISO-AMZ-2");
    UUID profileA = saveProfile(actorA, AMAZON, "sponsored_products", "AUTO_DISCOVERY");
    UUID profileB = saveProfile(actorB, AMAZON, "sponsored_products", "AUTO_DISCOVERY");
    UUID campaignA = seedCampaign(actorA, AMAZON, shopA, profileA, "EXT-ISO-A", "A 活动", "100.0000");
    UUID campaignB = seedCampaign(actorB, AMAZON, shopB, profileB, "EXT-ISO-B", "B 活动", "100.0000");
    UUID listingA = seedListing(a, shopA, "ASIN-ISO-A", "A 商品");
    importStructure(actorA, campaignA.toString(),
        List.of(group("GRP-1", "组一", List.of(new AdCampaignProductImport(listingA, null, null)), List.of())),
        List.of());

    // A 看得到自己的结构
    assertThat(as(actorA, "channel.operation.read", () -> service.groups(campaignA.toString(), null, null, 0, 50)).total())
        .isEqualTo(1);
    // B 看不到 A 的活动（全景 404）
    assertThatThrownBy(() -> as(actorB, "channel.operation.read",
        () -> service.panorama(campaignA.toString(), null, null)))
        .hasMessageContaining("不存在");
    // B 看自己的
    assertThat(as(actorB, "channel.operation.read", () -> service.groups(campaignB.toString(), null, null, 0, 50)).total())
        .isZero();
    // B 看不到 A 的广告组 id
    assertThat(as(actorB, "channel.operation.read", () -> service.groups(campaignB.toString(), null, null, 0, 50)).items())
        .isEmpty();
  }

  @Test
  @DisplayName("14.1.1.2 断言二：伪造 tenant header 无效（租户只来自已认证成员关系）")
  void forgedTenantHeaderIsIgnored() throws Exception {
    String shopA = seedShop(a, AMAZON, "DET-FORGE-AMZ-1");
    UUID profileA = saveProfile(actorA, AMAZON, "sponsored_products", "AUTO_DISCOVERY");
    UUID campaignA = seedCampaign(actorA, AMAZON, shopA, profileA, "EXT-FORGE-A", "A 活动", "100.0000");

    // A 的会话 + B 的头 ⇒ 仍然是 A 的租户
    http.perform(get("/channel/ad-campaign-details/campaigns/" + campaignA)
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.campaign.campaignName", org.hamcrest.Matchers.is("A 活动")));

    // 伪造租户头也不能让 A 的会话看到别的东西
    http.perform(get("/channel/ad-campaign-details/campaigns/" + UUID.randomUUID())
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isNotFound());

    // 无会话一律 401
    http.perform(get("/channel/ad-campaign-details/campaigns/" + campaignA)
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("14.1.1.2 断言三：连接池归还后不串租户（池压到 1，A→B→A 三轮）")
  void pooledConnectionsDoNotBleedTenants() {
    String shopA = seedShop(a, AMAZON, "DET-POOL-AMZ-1");
    String shopB = seedShop(b, AMAZON, "DET-POOL-AMZ-2");
    UUID profileA = saveProfile(actorA, AMAZON, "sponsored_products", "AUTO_DISCOVERY");
    UUID profileB = saveProfile(actorB, AMAZON, "sponsored_products", "AUTO_DISCOVERY");
    UUID campaignA = seedCampaign(actorA, AMAZON, shopA, profileA, "EXT-POOL-A", "A 活动", "100.0000");
    UUID campaignB = seedCampaign(actorB, AMAZON, shopB, profileB, "EXT-POOL-B", "B 活动", "100.0000");

    for (int i = 0; i < 3; i++) {
      assertThat(as(actorA, "channel.operation.read", () -> service.panorama(campaignA.toString(), null, null))
          .campaign().campaignName()).isEqualTo("A 活动");
      assertThat(as(actorB, "channel.operation.read", () -> service.panorama(campaignB.toString(), null, null))
          .campaign().campaignName()).isEqualTo("B 活动");
      assertThat(as(actorA, "channel.operation.read", () -> service.groups(campaignA.toString(), null, null, 0, 50)).total())
          .isZero();
      assertThat(as(actorB, "channel.operation.read", () -> service.groups(campaignB.toString(), null, null, 0, 50)).total())
          .isZero();
    }
  }

  // ==================================================================
  // 9. 跨租户与不存在返回逐字相同的 404
  // ==================================================================

  @Test
  @DisplayName("14.1.1.2 跨租户与不存在返回逐字相同的 404")
  void crossTenantAndMissingAreIdentical() {
    String shopA = seedShop(a, AMAZON, "DET-404-AMZ-1");
    UUID profileA = saveProfile(actorA, AMAZON, "sponsored_products", "AUTO_DISCOVERY");
    UUID campaignA = seedCampaign(actorA, AMAZON, shopA, profileA, "EXT-404-A", "A 活动", "100.0000");

    var missing = org.assertj.core.api.Assertions.catchThrowable(
        () -> as(actorA, "channel.operation.read", () -> service.panorama(UUID.randomUUID().toString(), null, null)));
    var crossTenant = org.assertj.core.api.Assertions.catchThrowable(
        () -> as(actorB, "channel.operation.read", () -> service.panorama(campaignA.toString(), null, null)));

    assertThat(missing).isNotNull();
    assertThat(crossTenant).isNotNull();
    assertThat(missing.getMessage()).isEqualTo(crossTenant.getMessage());
    assertThat(missing.getMessage()).contains("不存在");
  }

  // ==================================================================
  // 10. 端点：401 / 400 / 404
  // ==================================================================

  @Test
  @DisplayName("14.1.1.2 端点：无会话 401；非法 id 与非法结构载荷 400；不存在 404")
  void endpointGuardsBehave() throws Exception {
    String shop = seedShop(a, AMAZON, "DET-GUARD-AMZ-1");
    UUID profile = saveProfile(actorA, AMAZON, "sponsored_products", "AUTO_DISCOVERY");
    UUID campaignId = seedCampaign(actorA, AMAZON, shop, profile, "EXT-GUARD-1", "守卫活动", "100.0000");

    // 无会话
    http.perform(get("/channel/ad-campaign-details/capabilities"))
        .andExpect(status().isUnauthorized());
    http.perform(get("/channel/ad-campaign-details/campaigns/" + campaignId))
        .andExpect(status().isUnauthorized());

    // 非法 id
    http.perform(get("/channel/ad-campaign-details/campaigns/not-a-uuid")
            .header("Authorization", bearerA))
        .andExpect(status().isBadRequest());

    // 不存在
    http.perform(get("/channel/ad-campaign-details/campaigns/" + UUID.randomUUID())
            .header("Authorization", bearerA))
        .andExpect(status().isNotFound());

    // 非法结构载荷（缺 dataAsOf / 缺组）
    http.perform(post("/channel/ad-campaign-details/campaigns/" + campaignId + "/structure")
            .header("Authorization", bearerA)
            .contentType("application/json")
            .content("{\"groups\":[]}"))
        .andExpect(status().isBadRequest());

    // 非法创意类型
    http.perform(post("/channel/ad-campaign-details/campaigns/" + campaignId + "/structure")
            .header("Authorization", bearerA)
            .contentType("application/json")
            .content("{\"dataAsOf\":\"2026-10-05T11:30:00+08:00\",\"groups\":[{\"externalGroupId\":\"G1\","
                + "\"creativeType\":\"IMAGE\",\"creatives\":[{\"externalCreativeId\":\"C1\","
                + "\"creativeType\":\"NOT_A_TYPE\"}]}]}"))
        .andExpect(status().isBadRequest());

    // 复制结构：缺目标店铺
    http.perform(post("/channel/ad-campaign-details/campaigns/" + campaignId + "/copy")
            .header("Authorization", bearerA)
            .contentType("application/json")
            .content("{\"targetShopCode\":\"\",\"productMode\":\"KEEP_PRODUCTS\"}"))
        .andExpect(status().isBadRequest());
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
