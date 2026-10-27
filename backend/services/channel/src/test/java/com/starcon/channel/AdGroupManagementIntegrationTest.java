package com.starcon.channel;

import static com.starcon.channel.AdGroupManagementModels.*;
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
 * 14.1.2.1 广告组管理。
 *
 * <p>本测试类钉住本模块最容易做丢的几件事：
 *
 * <ol>
 *   <li><b>多租户三断言</b>（AGENTS.md 硬性要求，缺一不可）：两租户互不可见、伪造 tenant header 无效、
 *       连接池归还后不串租户
 *   <li><b>🔴 组级表现拿不到就写「拿不到」</b>：{@code performance.available=false} +
 *       ownerModule=14.3.1.2 + 影响说明；**不许**把活动级指标冒充组级指标
 *   <li><b>🔴 组级报表入口是入口不是报表</b>：available=true + 路由标识 + 责任模块号
 *   <li><b>🔴 本地已改 ≠ 平台侧已改</b>：新建 / 改设置 / 设投放商品都落本地 + 标「待回写平台」
 *   <li><b>🔴 新建的组状态一律暂停</b>，平台侧 id 是 {@code LOCAL-} 本地标记（不是平台 id）
 *   <li><b>🔴 定向与匹配类型只存原文</b>（不解读）
 *   <li><b>🔴 SKU 只读带出、不做归因</b>：映射现状带出，归因责任显式标给 14.3.1.3
 *   <li><b>🔴 默认竞价不在本端点里</b>：给了会被明确拒绝（不是静默忽略）
 *   <li><b>时间字段写回值正确</b>（AGENTS.md 要求断言写回值，不能只断言没抛异常）
 *   <li>真分页真筛选真排序；跨租户与不存在返回逐字相同的 404
 * </ol>
 *
 * <p><b>时刻一律用固定的 {@link OffsetDateTime} 钉死</b>，不用 {@code now()}：用墙钟会让
 * 「这次写入落在哪个断言分支上」成为一个 flaky 断言。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AdGroupManagementIntegrationTest {

  static final PostgreSQLContainer<?> pg =
      new PostgreSQLContainer<>("postgres:16-alpine").withUrlParam("sslmode", "disable");
  static final UUID user = UUID.randomUUID();
  static HttpServer identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  /** 🔴 每个用例一对**全新的租户**（共用租户会让前一个用例的数据被后一个用例数进去）。 */
  UUID a, b;
  AccessService.Access actorA, actorB;
  String bearerA;

  static final OffsetDateTime DATA_AS_OF = OffsetDateTime.parse("2026-10-01T09:00:00+08:00");
  static final OffsetDateTime STARTED_AT = OffsetDateTime.parse("2026-10-01T08:00:00+08:00");
  static final OffsetDateTime FINISHED_AT = OffsetDateTime.parse("2026-10-01T09:05:00+08:00");
  static final LocalDate DAY_1 = LocalDate.of(2026, 10, 1);

  static final UUID AMAZON = UUID.fromString("11111111-1111-1111-1111-111111111111");
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
    // 🔴 广告类型档案的归一口径由 2.1.2.1 解析 ⇒ 必须先有这条平台默认映射。
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

  @Autowired AdGroupManagementService service;
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

  /** 播种一条 listing_variant（listing → 内部 SKU 的映射现状，只读带出用）。 */
  void seedVariant(UUID tenant, UUID listingId, String externalVariantId, UUID internalSkuId) {
    execAsTenant(tenant,
        "insert into listing_variant (id,tenant_id,listing_id,external_variant_id,attributes,"
            + " mapping_status,internal_sku_id,component_quantity,version,created_at,updated_at)"
            + " values ('" + UUID.randomUUID() + "','" + tenant + "','" + listingId + "','"
            + externalVariantId + "','{}'::jsonb,"
            + (internalSkuId == null ? "'UNMAPPED'" : "'MAPPED'") + ","
            + (internalSkuId == null ? "null" : "'" + internalSkuId + "'") + ","
            + "null,0,now(),now())");
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
                            new BigDecimal("120.0000"), new BigDecimal("600.0000"))),
                    "测试登记")))));
    String id =
        execOneAsTenant(
            who.tenantId(),
            "select id from ad_campaign where tenant_id = '" + who.tenantId()
                + "' and external_campaign_id = '" + externalId + "'");
    return UUID.fromString(id);
  }

  /** 新建一个广告组（本模块的真实写入口）。 */
  UUID createGroup(
      AccessService.Access who, UUID campaignId, String name, BigDecimal bid, BigDecimal budget) {
    return as(who, "channel.operation.create",
        () -> service.create(new AdGroupCreateRequest(
                campaignId, name, bid, null, budget, "AUTO", "BROAD", "geo=US", "新建测试")))
        .groupId();
  }

  // ==================================================================
  // 1. 能力声明：未交付的段必须显式 available=false
  // ==================================================================

  @Test
  @DisplayName("14.1.2.1 能力声明：组级指标 / 关键词拆分 / 竞价策略 / 定位解读 / SKU 归因 / 平台回写显式未交付")
  void capabilitiesDeclareUndeliveredParts() {
    var caps = as(actorA, "channel.operation.read", () -> service.capabilities());
    Map<String, AdGroupCapabilityDimension> dims = new java.util.HashMap<>();
    caps.dimensions().forEach(d -> dims.put(d.dimension(), d));

    // ---- 已交付 ----
    assertThat(dims.get("group_records").available()).isTrue();
    assertThat(dims.get("group_management").available()).isTrue();
    assertThat(dims.get("targeted_products").available()).isTrue();
    assertThat(dims.get("match_type").available()).isTrue();
    assertThat(dims.get("targeting_settings").available()).isTrue();
    assertThat(dims.get("default_bid").available()).isTrue();
    // 🔴 默认竞价是**复用**上游的入口，不另起同义端点
    assertThat(dims.get("default_bid").source()).contains("ad-campaign-details");
    assertThat(dims.get("default_bid").source()).contains("bids");

    // ---- 🔴 组级报表入口：入口已交付，路由标识与责任模块都写清楚 ----
    assertThat(dims.get("group_report_entry").available()).isTrue();
    assertThat(dims.get("group_report_entry").ownerModule()).contains("14.3.1.2");
    assertThat(caps.reportEntry().routeIdentifier()).isEqualTo("14.3.1.2");
    assertThat(caps.reportEntry().reportRoute()).isEqualTo("/ads/reports");
    assertThat(caps.reportEntry().available()).isTrue();

    // ---- 🔴 组级指标：拿不到就写拿不到 ----
    assertThat(dims.get("group_performance_metrics").available()).isFalse();
    assertThat(dims.get("group_performance_metrics").ownerModule()).contains("14.3.1.2");
    assertThat(dims.get("group_performance_metrics").impact()).contains("拿不到");
    assertThat(caps.performance().available()).isFalse();
    assertThat(caps.performance().note()).contains("ad_campaign_daily_metric");
    assertThat(caps.performance().note()).contains("group_id");

    // ---- 其余未交付段 ----
    assertThat(dims.get("group_keyword_breakdown").available()).isFalse();
    assertThat(dims.get("group_keyword_breakdown").ownerModule()).contains("14.1.3.1");
    assertThat(dims.get("bid_strategy").available()).isFalse();
    assertThat(dims.get("bid_strategy").ownerModule()).contains("14.2.1.1");
    assertThat(dims.get("targeting_interpretation").available()).isFalse();
    assertThat(dims.get("targeting_interpretation").ownerModule()).contains("14.1.3.2");
    assertThat(dims.get("sku_attribution").available()).isFalse();
    assertThat(dims.get("sku_attribution").ownerModule()).contains("14.3.1.3");
    assertThat(dims.get("platform_writeback").available()).isFalse();
    assertThat(dims.get("platform_group_sync").available()).isFalse();

    // ---- 护栏 ----
    assertThat(caps.guardrails().maxGroupDailyBudget()).isEqualByComparingTo("100000");
    assertThat(caps.guardrails().maxDefaultBid()).isEqualByComparingTo("1000");
    assertThat(caps.guardrails().maxProductsPerGroup()).isEqualTo(100);
  }

  // ==================================================================
  // 2. 新建广告组：本地真落库 + 状态暂停 + 平台侧本地标记 + 待回写
  // ==================================================================

  @Test
  @DisplayName("14.1.2.1 新建广告组：真的落库，状态一律暂停，平台侧 id 是 LOCAL- 本地标记，标着待回写")
  void createGroupLandsRealRowAndMarksPendingWriteback() {
    String shop = seedShop(a, AMAZON, "GRP-AMZ-1");
    UUID profile = saveProfile(actorA, AMAZON, "sponsored_products", "AUTO_DISCOVERY");
    UUID campaignId = seedCampaign(actorA, AMAZON, shop, profile, "EXT-GRP-1", "广告组管理活动", "100.0000");

    var result = as(actorA, "channel.operation.create",
        () -> service.create(new AdGroupCreateRequest(
            campaignId, "手动组", new BigDecimal("1.5000"), null, new BigDecimal("50.0000"),
            "AUTO", "BROAD", "geo=US", "新建")));

    assertThat(result.groupId()).isNotNull();
    // 🔴 平台侧 id 是本地标记，**不是**平台 id
    assertThat(result.externalGroupId()).startsWith("LOCAL-");
    assertThat(result.externalGroupId()).isNotEqualTo(result.groupId().toString());
    // 🔴 状态一律暂停：平台侧还没建这个组
    assertThat(result.status()).isEqualTo("PAUSED");
    assertThat(result.pendingPlatformWriteback()).isTrue();
    assertThat(result.defaultBid()).isEqualByComparingTo("1.5000");
    assertThat(result.dailyBudget()).isEqualByComparingTo("50.0000");
    assertThat(result.targetingType()).isEqualTo("AUTO");
    assertThat(result.matchType()).isEqualTo("BROAD");

    // 🔴 真的落库了（从库里读回来）
    String stored =
        execOneAsTenant(a,
            "select group_name || '|' || status || '|' || pending_platform_writeback || '|' || match_type"
                + " from ad_campaign_group where tenant_id = '" + a + "' and id = '" + result.groupId() + "'");
    assertThat(stored).isEqualTo("手动组|PAUSED|true|BROAD");

    // 🔴 审计留痕（新建）
    String audit =
        execOneAsTenant(a,
            "select operation || '|' || result from ad_group_operation_log where tenant_id = '" + a
                + "' and group_id = '" + result.groupId() + "'");
    assertThat(audit).isEqualTo("CREATE_GROUP|APPLIED");
  }

  @Test
  @DisplayName("14.1.2.1 新建广告组：默认竞价超上限被拒；组级预算超上限被拒")
  void createGroupRejectsOverLimitMoney() {
    String shop = seedShop(a, AMAZON, "GRP-AMZ-2");
    UUID profile = saveProfile(actorA, AMAZON, "sponsored_products", "AUTO_DISCOVERY");
    UUID campaignId = seedCampaign(actorA, AMAZON, shop, profile, "EXT-GRP-2", "护栏活动", "100.0000");

    assertThatThrownBy(() -> as(actorA, "channel.operation.create",
        () -> service.create(new AdGroupCreateRequest(
            campaignId, "越界组", new BigDecimal("1000.0001"), null, null, null, null, null, null))))
        .hasMessageContaining("上限");

    assertThatThrownBy(() -> as(actorA, "channel.operation.create",
        () -> service.create(new AdGroupCreateRequest(
            campaignId, "越界组", null, null, new BigDecimal("100000.0001"), null, null, null, null))))
        .hasMessageContaining("上限");

    // 币种不一致 ⇒ 400
    assertThatThrownBy(() -> as(actorA, "channel.operation.create",
        () -> service.create(new AdGroupCreateRequest(
            campaignId, "币种组", null, "EUR", null, null, null, null, null))))
        .hasMessageContaining("币种");
  }

  // ==================================================================
  // 3. 改组设置：部分更新 + 没改就不落库（但仍留痕）
  // ==================================================================

  @Test
  @DisplayName("14.1.2.1 改组设置：只改给定的字段，变更后标着待回写平台")
  void updateSettingsChangesOnlyGivenFields() {
    String shop = seedShop(a, AMAZON, "GRP-AMZ-3");
    UUID profile = saveProfile(actorA, AMAZON, "sponsored_products", "AUTO_DISCOVERY");
    UUID campaignId = seedCampaign(actorA, AMAZON, shop, profile, "EXT-GRP-3", "改设置活动", "100.0000");
    UUID groupId = createGroup(actorA, campaignId, "原组名", new BigDecimal("1.5000"), new BigDecimal("50.0000"));

    var result = as(actorA, "channel.operation.update",
        () -> service.updateSettings(groupId.toString(),
            new AdGroupSettingsUpdateRequest(
                "新组名", null, new BigDecimal("80.0000"), null, null, "EXACT", null, null, "调整")));

    assertThat(result.changedFields()).containsExactlyInAnyOrder("groupName", "dailyBudget", "matchType");
    assertThat(result.group().groupName()).isEqualTo("新组名");
    assertThat(result.group().dailyBudget()).isEqualByComparingTo("80.0000");
    assertThat(result.group().matchType()).isEqualTo("EXACT");
    // 🔴 没给的字段不动
    assertThat(result.group().targetingType()).isEqualTo("AUTO");
    assertThat(result.group().status()).isEqualTo("PAUSED");
    assertThat(result.group().defaultBid()).isEqualByComparingTo("1.5000");
    assertThat(result.pendingPlatformWriteback()).isTrue();

    // 🔴 库里的组也标着待回写
    String flag =
        execOneAsTenant(a,
            "select pending_platform_writeback::text from ad_campaign_group where tenant_id = '" + a
                + "' and id = '" + groupId + "'");
    assertThat(flag).isEqualTo("true");

    // 🔴 什么都没改 ⇒ SKIPPED，不落库、也不重新标记待回写
    var noop = as(actorA, "channel.operation.update",
        () -> service.updateSettings(groupId.toString(),
            new AdGroupSettingsUpdateRequest(
                "新组名", null, new BigDecimal("80.0000"), null, null, "EXACT", null, null, "没改")));
    assertThat(noop.changedFields()).isEmpty();
    assertThat(noop.note()).contains("没有");

    String skipped =
        execOneAsTenant(a,
            "select count(*) from ad_group_operation_log where tenant_id = '" + a + "' and group_id = '"
                + groupId + "' and result = 'SKIPPED'");
    assertThat(skipped).isEqualTo("1");
  }

  @Test
  @DisplayName("14.1.2.1 改组设置：组级预算可显式恢复成「继承活动预算」；同时给数值与清空开关被拒")
  void updateSettingsHandlesGroupBudgetSemantics() {
    String shop = seedShop(a, AMAZON, "GRP-AMZ-4");
    UUID profile = saveProfile(actorA, AMAZON, "sponsored_products", "AUTO_DISCOVERY");
    UUID campaignId = seedCampaign(actorA, AMAZON, shop, profile, "EXT-GRP-4", "预算语义活动", "100.0000");
    UUID groupId = createGroup(actorA, campaignId, "预算组", null, new BigDecimal("50.0000"));

    // 🔴 恢复成「继承活动预算」：null ≠ 0
    var cleared = as(actorA, "channel.operation.update",
        () -> service.updateSettings(groupId.toString(),
            new AdGroupSettingsUpdateRequest(
                null, null, null, Boolean.TRUE, null, null, null, null, "恢复继承")));
    assertThat(cleared.group().dailyBudget()).isNull();
    assertThat(cleared.changedFields()).containsExactly("dailyBudget");

    // 同时给数值与清空开关 ⇒ 400
    assertThatThrownBy(() -> as(actorA, "channel.operation.update",
        () -> service.updateSettings(groupId.toString(),
            new AdGroupSettingsUpdateRequest(
                null, null, new BigDecimal("10.0000"), Boolean.TRUE, null, null, null, null, null))))
        .hasMessageContaining("不能同时");

    // 🔴 明确拒绝默认竞价（不是静默忽略）
    assertThatThrownBy(() -> as(actorA, "channel.operation.update",
        () -> service.updateSettings(groupId.toString(),
            new AdGroupSettingsUpdateRequest(
                null, null, null, null, null, null, null, new BigDecimal("9.0000"), null))))
        .hasMessageContaining("默认竞价不在本端点");
  }

  // ==================================================================
  // 4. 投放商品：替换语义 + 跨店铺被拒 + SKU 映射现状只读带出
  // ==================================================================

  @Test
  @DisplayName("14.1.2.1 设投放商品：替换语义真的落库；跨店铺的 listing 被拒")
  void setProductsReplacesAndValidatesShopScope() {
    String shopA = seedShop(a, AMAZON, "GRP-AMZ-5A");
    String shopB = seedShop(a, AMAZON, "GRP-AMZ-5B");
    UUID profile = saveProfile(actorA, AMAZON, "sponsored_products", "AUTO_DISCOVERY");
    UUID campaignId = seedCampaign(actorA, AMAZON, shopA, profile, "EXT-GRP-5", "投放商品活动", "100.0000");
    UUID groupId = createGroup(actorA, campaignId, "商品组", null, null);

    UUID listing1 = seedListing(a, shopA, "ASIN-GRP-1", "商品一");
    UUID listing2 = seedListing(a, shopA, "ASIN-GRP-2", "商品二");
    UUID foreign = seedListing(a, shopB, "ASIN-GRP-FOREIGN", "别的店铺的商品");

    var first = as(actorA, "channel.operation.update",
        () -> service.setProducts(groupId.toString(),
            new AdGroupProductUpdateRequest(List.of(listing1, listing2), "挂两个")));
    assertThat(first.addedListingIds()).containsExactly(listing1, listing2);
    assertThat(first.removedListingIds()).isEmpty();
    assertThat(first.productCount()).isEqualTo(2);
    assertThat(first.pendingPlatformWriteback()).isTrue();

    // 🔴 替换语义：没列出的会被解除关联
    var second = as(actorA, "channel.operation.update",
        () -> service.setProducts(groupId.toString(),
            new AdGroupProductUpdateRequest(List.of(listing2), "只留一个")));
    assertThat(second.addedListingIds()).isEmpty();
    assertThat(second.removedListingIds()).containsExactly(listing1);
    assertThat(second.productCount()).isEqualTo(1);

    String stored =
        execOneAsTenant(a,
            "select count(*) from ad_campaign_group_product where tenant_id = '" + a
                + "' and group_id = '" + groupId + "'");
    assertThat(stored).isEqualTo("1");

    // 🔴 跨店铺的 listing 被拒
    assertThatThrownBy(() -> as(actorA, "channel.operation.update",
        () -> service.setProducts(groupId.toString(),
            new AdGroupProductUpdateRequest(List.of(foreign), "跨店铺"))))
        .hasMessageContaining("店铺");

    // 不存在的 listing ⇒ 404
    assertThatThrownBy(() -> as(actorA, "channel.operation.update",
        () -> service.setProducts(groupId.toString(),
            new AdGroupProductUpdateRequest(List.of(UUID.randomUUID()), "不存在"))))
        .hasMessageContaining("不存在");
  }

  @Test
  @DisplayName("14.1.2.1 详情：投放商品带出 SKU 映射现状（只读），组级指标显式不可得")
  void detailCarriesSkuMappingButNoGroupMetrics() {
    String shop = seedShop(a, AMAZON, "GRP-AMZ-6");
    UUID profile = saveProfile(actorA, AMAZON, "sponsored_products", "AUTO_DISCOVERY");
    UUID campaignId = seedCampaign(actorA, AMAZON, shop, profile, "EXT-GRP-6", "详情活动", "100.0000");
    UUID groupId = createGroup(actorA, campaignId, "详情组", null, null);

    UUID listing = seedListing(a, shop, "ASIN-GRP-6", "详情商品");
    UUID sku = UUID.randomUUID();
    seedVariant(a, listing, "VAR-1", sku);
    seedVariant(a, listing, "VAR-2", null);
    as(actorA, "channel.operation.update",
        () -> service.setProducts(groupId.toString(),
            new AdGroupProductUpdateRequest(List.of(listing), "挂商品")));

    var detail = as(actorA, "channel.operation.read", () -> service.detail(groupId.toString()));

    assertThat(detail.products()).hasSize(1);
    AdGroupProductRow product = detail.products().get(0);
    assertThat(product.listingId()).isEqualTo(listing);
    assertThat(product.externalItemId()).isEqualTo("ASIN-GRP-6");
    assertThat(product.listingStatus()).isEqualTo("ON_SALE");
    // 🔴 SKU 映射现状**只读带出**
    assertThat(product.internalSkuIds()).containsExactly(sku);
    assertThat(product.unmappedVariantCount()).isEqualTo(1);
    assertThat(product.attributionNote()).contains("14.3.1.3");

    // 🔴 组级指标**不可得**，且不许拿活动级指标冒充
    assertThat(detail.performance().available()).isFalse();
    assertThat(detail.performance().ownerModule()).contains("14.3.1.2");
    assertThat(detail.performance().impact()).contains("拿不到");

    // 🔴 报表入口是入口：available=true + 路由标识
    assertThat(detail.reportEntry().available()).isTrue();
    assertThat(detail.reportEntry().routeIdentifier()).isEqualTo("14.3.1.2");
    assertThat(detail.reportEntry().reportRoute()).isEqualTo("/ads/reports");

    // 🔴 下游入口：改竞价复用上游（available=true），竞价策略与定位解读不可用
    Map<String, AdGroupEntry> entries = new java.util.HashMap<>();
    detail.entries().forEach(e -> entries.put(e.entry(), e));
    assertThat(entries.get("ADJUST_BID").available()).isTrue();
    assertThat(entries.get("ADJUST_BID").source()).contains("ad-campaign-details");
    assertThat(entries.get("BID_STRATEGY").available()).isFalse();
    assertThat(entries.get("TARGETING_EFFECT").available()).isFalse();
    assertThat(entries.get("SKU_ATTRIBUTION").available()).isFalse();
  }

  // ==================================================================
  // 5. 候选投放商品：只列活动所在店铺的 listing
  // ==================================================================

  @Test
  @DisplayName("14.1.2.1 候选投放商品：只来自活动所在店铺的 listing，已挂上的标记为已选")
  void productCandidatesComeFromShopListings() {
    String shopA = seedShop(a, AMAZON, "GRP-AMZ-7A");
    String shopB = seedShop(a, AMAZON, "GRP-AMZ-7B");
    UUID profile = saveProfile(actorA, AMAZON, "sponsored_products", "AUTO_DISCOVERY");
    UUID campaignId = seedCampaign(actorA, AMAZON, shopA, profile, "EXT-GRP-7", "候选活动", "100.0000");
    UUID groupId = createGroup(actorA, campaignId, "候选组", null, null);

    UUID own = seedListing(a, shopA, "ASIN-CAND-1", "本店商品");
    seedListing(a, shopB, "ASIN-CAND-FOREIGN", "别店商品");

    var page = as(actorA, "channel.operation.read",
        () -> service.productCandidates(campaignId.toString(), groupId.toString(), null, 0, 50));
    assertThat(page.items()).hasSize(1);
    assertThat(page.items().get(0).listingId()).isEqualTo(own);
    assertThat(page.items().get(0).selected()).isFalse();

    as(actorA, "channel.operation.update",
        () -> service.setProducts(groupId.toString(),
            new AdGroupProductUpdateRequest(List.of(own), "挂上")));
    var after = as(actorA, "channel.operation.read",
        () -> service.productCandidates(campaignId.toString(), groupId.toString(), null, 0, 50));
    assertThat(after.items().get(0).selected()).isTrue();
  }

  // ==================================================================
  // 6. 列表：真分页 + 真筛选 + 真排序
  // ==================================================================

  @Test
  @DisplayName("14.1.2.1 列表：真分页不重叠、真筛选命中、真排序按名字")
  void listPaginatesFiltersAndSorts() {
    String shop = seedShop(a, AMAZON, "GRP-AMZ-8");
    UUID profile = saveProfile(actorA, AMAZON, "sponsored_products", "AUTO_DISCOVERY");
    UUID campaignId = seedCampaign(actorA, AMAZON, shop, profile, "EXT-GRP-8", "列表活动", "100.0000");

    createGroup(actorA, campaignId, "组甲", new BigDecimal("1.0000"), null);
    createGroup(actorA, campaignId, "组乙", new BigDecimal("2.0000"), null);
    createGroup(actorA, campaignId, "组丙", new BigDecimal("3.0000"), null);

    var page1 = as(actorA, "channel.operation.read",
        () -> service.groups(campaignId.toString(), null, "组", null, null, null, null, 0, 2));
    assertThat(page1.total()).isEqualTo(3);
    assertThat(page1.items()).hasSize(2);

    var page2 = as(actorA, "channel.operation.read",
        () -> service.groups(campaignId.toString(), null, "组", null, null, null, null, 1, 2));
    assertThat(page2.items()).hasSize(1);
    // 两页不重叠
    assertThat(page1.items().stream().map(AdGroupRow::id))
        .doesNotContainAnyElementsOf(page2.items().stream().map(AdGroupRow::id).toList());

    // 真筛选：关键词只命中组甲
    var filtered = as(actorA, "channel.operation.read",
        () -> service.groups(campaignId.toString(), null, "甲", null, null, null, null, 0, 50));
    assertThat(filtered.total()).isEqualTo(1);
    assertThat(filtered.items().get(0).groupName()).isEqualTo("组甲");

    // 真排序：按组名升序
    var sorted = as(actorA, "channel.operation.read",
        () -> service.groups(campaignId.toString(), null, null, null, null, null, "nameAsc", 0, 50));
    assertThat(sorted.items().stream().map(AdGroupRow::groupName).toList())
        .containsExactly("组丙", "组乙", "组甲");

    // 非法排序方式 ⇒ 400
    assertThatThrownBy(() -> as(actorA, "channel.operation.read",
        () -> service.groups(campaignId.toString(), null, null, null, null, null, "id; drop table x", 0, 50)))
        .hasMessageContaining("排序方式");

    // 汇总：只给条数，不做跨币种金额相加
    var summary = as(actorA, "channel.operation.read",
        () -> service.summary(campaignId.toString(), null, null, null, null, null));
    assertThat(summary.total()).isEqualTo(3);
    assertThat(summary.campaignCount()).isEqualTo(1);
    assertThat(summary.pendingWritebackCount()).isEqualTo(3);
    assertThat(summary.note()).contains("币种");
  }

  // ==================================================================
  // 7. 🔴 时间字段：写回值逐字相等（AGENTS.md 要求）
  // ==================================================================

  @Test
  @DisplayName("14.1.2.1 时间字段：timestamptz 写回值逐字相等（不偏 −28800s）")
  void timestamptzRoundTripIsExact() {
    String shop = seedShop(a, AMAZON, "GRP-AMZ-9");
    UUID profile = saveProfile(actorA, AMAZON, "sponsored_products", "AUTO_DISCOVERY");
    UUID campaignId = seedCampaign(actorA, AMAZON, shop, profile, "EXT-GRP-9", "时间活动", "100.0000");
    UUID groupId = createGroup(actorA, campaignId, "时间组", null, null);

    var detail = as(actorA, "channel.operation.read", () -> service.detail(groupId.toString()));
    OffsetDateTime localChangedAt = detail.group().localChangedAt();
    assertThat(localChangedAt).isNotNull();
    OffsetDateTime dataAsOf = detail.group().dataAsOf();
    assertThat(dataAsOf).isNotNull();

    // 🔴 从库里按微秒读回来，逐字相等
    String rawLocal =
        execOneAsTenant(a,
            "select ((extract(epoch from local_changed_at)*1000000)::bigint)::text"
                + " from ad_campaign_group where tenant_id = '" + a + "' and id = '" + groupId + "'");
    assertThat(rawLocal).isEqualTo(micros(localChangedAt));

    String rawAsOf =
        execOneAsTenant(a,
            "select ((extract(epoch from data_as_of)*1000000)::bigint)::text"
                + " from ad_campaign_group where tenant_id = '" + a + "' and id = '" + groupId + "'");
    assertThat(rawAsOf).isEqualTo(micros(dataAsOf));

    // 🔴 改设置后 local_changed_at 会往前走（且不是墙钟本地时区偏移）
    OffsetDateTime before = localChangedAt;
    var updated = as(actorA, "channel.operation.update",
        () -> service.updateSettings(groupId.toString(),
            new AdGroupSettingsUpdateRequest(
                "改名了", null, null, null, null, null, null, null, "改名")));
    assertThat(updated.group().localChangedAt()).isAfterOrEqualTo(before);
  }

  private static String micros(OffsetDateTime value) {
    return String.valueOf(value.toEpochSecond() * 1_000_000L + value.getNano() / 1000L);
  }

  // ==================================================================
  // 8. 多租户三断言（AGENTS.md 硬性要求，缺一不可）
  // ==================================================================

  @Test
  @DisplayName("14.1.2.1 断言一：两租户互不可见")
  void tenantsAreIsolated() {
    String shopA = seedShop(a, AMAZON, "GRP-ISO-AMZ-1");
    String shopB = seedShop(b, AMAZON, "GRP-ISO-AMZ-2");
    UUID profileA = saveProfile(actorA, AMAZON, "sponsored_products", "AUTO_DISCOVERY");
    UUID profileB = saveProfile(actorB, AMAZON, "sponsored_products", "AUTO_DISCOVERY");
    UUID campaignA = seedCampaign(actorA, AMAZON, shopA, profileA, "EXT-ISO-A", "A 活动", "100.0000");
    UUID campaignB = seedCampaign(actorB, AMAZON, shopB, profileB, "EXT-ISO-B", "B 活动", "100.0000");
    UUID groupA = createGroup(actorA, campaignA, "A 组", null, null);

    // A 看得到自己的组
    assertThat(as(actorA, "channel.operation.read", () -> service.detail(groupA.toString())).group().groupName())
        .isEqualTo("A 组");
    // B 看不到 A 的组（404）
    assertThatThrownBy(() -> as(actorB, "channel.operation.read",
        () -> service.detail(groupA.toString())))
        .hasMessageContaining("不存在");
    // B 的列表里没有 A 的组
    assertThat(as(actorB, "channel.operation.read", () -> service.groups(null, null, null, null, null, null, null, 0, 50))
        .total()).isZero();
    // B 改 A 的组 ⇒ 404
    assertThatThrownBy(() -> as(actorB, "channel.operation.update",
        () -> service.updateSettings(groupA.toString(),
            new AdGroupSettingsUpdateRequest(
                "越权改名", null, null, null, null, null, null, null, null))))
        .hasMessageContaining("不存在");
    // B 的审计也拿不到 A 的组（与详情一致：跨租户 ⇒ 404，不是空列表）
    assertThatThrownBy(() -> as(actorB, "channel.operation.read",
        () -> service.operations(groupA.toString(), 0, 50)))
        .hasMessageContaining("不存在");
  }

  @Test
  @DisplayName("14.1.2.1 断言二：伪造 tenant header 无效（租户只来自已认证成员关系）")
  void forgedTenantHeaderIsIgnored() throws Exception {
    String shopA = seedShop(a, AMAZON, "GRP-FORGE-AMZ-1");
    UUID profileA = saveProfile(actorA, AMAZON, "sponsored_products", "AUTO_DISCOVERY");
    UUID campaignA = seedCampaign(actorA, AMAZON, shopA, profileA, "EXT-FORGE-A", "A 活动", "100.0000");
    UUID groupA = createGroup(actorA, campaignA, "A 组", null, null);

    // A 的会话 + B 的头 ⇒ 仍然是 A 的租户
    http.perform(get("/channel/ad-groups/" + groupA)
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.group.groupName", org.hamcrest.Matchers.is("A 组")));

    // 伪造租户头也不能让 A 的会话看到别的东西
    http.perform(get("/channel/ad-groups/" + UUID.randomUUID())
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isNotFound());

    // 无会话一律 401
    http.perform(get("/channel/ad-groups/" + groupA)
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("14.1.2.1 断言三：连接池归还后不串租户（池压到 1，A→B→A 三轮）")
  void pooledConnectionsDoNotBleedTenants() {
    String shopA = seedShop(a, AMAZON, "GRP-POOL-AMZ-1");
    String shopB = seedShop(b, AMAZON, "GRP-POOL-AMZ-2");
    UUID profileA = saveProfile(actorA, AMAZON, "sponsored_products", "AUTO_DISCOVERY");
    UUID profileB = saveProfile(actorB, AMAZON, "sponsored_products", "AUTO_DISCOVERY");
    UUID campaignA = seedCampaign(actorA, AMAZON, shopA, profileA, "EXT-POOL-A", "A 活动", "100.0000");
    UUID campaignB = seedCampaign(actorB, AMAZON, shopB, profileB, "EXT-POOL-B", "B 活动", "100.0000");
    UUID groupA = createGroup(actorA, campaignA, "A 组", null, null);
    createGroup(actorB, campaignB, "B 组", null, null);

    for (int i = 0; i < 3; i++) {
      assertThat(as(actorA, "channel.operation.read", () -> service.detail(groupA.toString())).group().groupName())
          .isEqualTo("A 组");
      assertThat(as(actorB, "channel.operation.read",
          () -> service.groups(campaignB.toString(), null, null, null, null, null, null, 0, 50)).total())
          .isEqualTo(1);
      assertThat(as(actorA, "channel.operation.read",
          () -> service.groups(campaignA.toString(), null, null, null, null, null, null, 0, 50)).total())
          .isEqualTo(1);
      assertThat(as(actorB, "channel.operation.read", () -> service.groups(null, null, null, null, null, null, null, 0, 50))
          .total()).isEqualTo(1);
    }
  }

  // ==================================================================
  // 9. 跨租户与不存在返回逐字相同的 404
  // ==================================================================

  @Test
  @DisplayName("14.1.2.1 跨租户与不存在返回逐字相同的 404")
  void crossTenantAndMissingAreIdentical() {
    String shopA = seedShop(a, AMAZON, "GRP-404-AMZ-1");
    UUID profileA = saveProfile(actorA, AMAZON, "sponsored_products", "AUTO_DISCOVERY");
    UUID campaignA = seedCampaign(actorA, AMAZON, shopA, profileA, "EXT-404-A", "A 活动", "100.0000");
    UUID groupA = createGroup(actorA, campaignA, "A 组", null, null);

    var missing = org.assertj.core.api.Assertions.catchThrowable(
        () -> as(actorA, "channel.operation.read", () -> service.detail(UUID.randomUUID().toString())));
    var crossTenant = org.assertj.core.api.Assertions.catchThrowable(
        () -> as(actorB, "channel.operation.read", () -> service.detail(groupA.toString())));

    assertThat(missing).isNotNull();
    assertThat(crossTenant).isNotNull();
    assertThat(missing.getMessage()).isEqualTo(crossTenant.getMessage());
    assertThat(missing.getMessage()).contains("不存在");
  }

  // ==================================================================
  // 10. 端点：401 / 400 / 404
  // ==================================================================

  @Test
  @DisplayName("14.1.2.1 端点：无会话 401；非法 id 400；不存在 404；缺必填字段 400")
  void endpointGuardsBehave() throws Exception {
    String shop = seedShop(a, AMAZON, "GRP-GUARD-AMZ-1");
    UUID profile = saveProfile(actorA, AMAZON, "sponsored_products", "AUTO_DISCOVERY");
    UUID campaignId = seedCampaign(actorA, AMAZON, shop, profile, "EXT-GUARD-1", "守卫活动", "100.0000");

    // 无会话
    http.perform(get("/channel/ad-groups/capabilities"))
        .andExpect(status().isUnauthorized());
    http.perform(get("/channel/ad-groups"))
        .andExpect(status().isUnauthorized());

    // 非法 id
    http.perform(get("/channel/ad-groups/not-a-uuid")
            .header("Authorization", bearerA))
        .andExpect(status().isBadRequest());

    // 不存在
    http.perform(get("/channel/ad-groups/" + UUID.randomUUID())
            .header("Authorization", bearerA))
        .andExpect(status().isNotFound());

    // 新建缺必填字段
    http.perform(post("/channel/ad-groups")
            .header("Authorization", bearerA)
            .contentType("application/json")
            .content("{\"groupName\":\"\"}"))
        .andExpect(status().isBadRequest());

    // 改设置：默认竞价被明确拒绝（不是静默忽略）
    http.perform(patch("/channel/ad-groups/" + UUID.randomUUID())
            .header("Authorization", bearerA)
            .contentType("application/json")
            .content("{\"defaultBid\":\"9.0000\"}"))
        .andExpect(status().isNotFound());
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
