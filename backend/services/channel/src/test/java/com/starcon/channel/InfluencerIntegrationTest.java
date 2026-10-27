package com.starcon.channel;

import static com.starcon.channel.InfluencerModels.*;
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
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 14.5.1.1 达人库。
 *
 * <p>本测试类钉住本模块最容易做丢的几件事：
 *
 * <ol>
 *   <li><b>🔴 效果数据入库</b>（蓝图加粗句）：GMV / 互动率 / 退款率真落库，
 *       <b>没录就是 null（不可比），不是 0</b>。
 *   <li><b>🔴 粉丝层级由 DB 触发器推导</b>：绕服务层直接改粉丝数，层级跟着变 ——
 *       服务层写什么都不作数。
 *   <li><b>🔴 评级是算出来的</b>：按评分口径算出分数、落档位；缺必需数据 ⇒
 *       <b>null（不是 0 分）</b>；权重之和不为 1 被服务层拒。
 *   <li><b>🔴 标签筛选</b>：按标签 / 标签分组反查达人，只命中被打标的那些。
 *   <li><b>🔴 黑名单排除</b>：拉黑后从正常检索里排除；解除后回到正常检索，
 *       而且解除**留痕**（时刻 + 理由），不删行。
 *   <li><b>🔴 GMV / 互动率区间筛选</b>：真筛选，不是前端过滤。
 *   <li><b>🔴 合作历史</b>：达人 × 活动（真实存在的 ad_campaign）+ 效果数据；
 *       有合作历史的达人不许物理删除。
 *   <li><b>🔴 平台同步未接入</b>：同步入口明确返回「未接入」并且**不写任何数据**。
 *   <li><b>🔴 时间字段写回值正确</b>（AGENTS.md：断言写回值，不能只断言没抛异常）。
 *   <li><b>真分页真排序</b>。
 * </ol>
 *
 * <p><b>时刻一律用固定的 {@link OffsetDateTime} 钉死</b>，不用 {@code now()}：用墙钟会让
 * 「这次写入落在哪个断言分支上」成为一个 flaky 断言。
 */
@SpringBootTest
@AutoConfigureMockMvc
class InfluencerIntegrationTest {

  static final PostgreSQLContainer<?> pg =
      new PostgreSQLContainer<>("postgres:16-alpine").withUrlParam("sslmode", "disable");
  static final UUID user = UUID.randomUUID();
  static HttpServer identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  /** 🔴 每个用例一对**全新的租户**（共用租户会让前一个用例的数据被后一个用例数进去）。 */
  UUID a, b;
  AccessService.Access actorA, actorB;
  String bearerA, bearerB;

  /** 🔴 钉死的时刻与金额。 */
  static final OffsetDateTime EFFECTIVE_AT = OffsetDateTime.parse("2026-10-01T09:30:00+08:00");
  static final OffsetDateTime STARTED_AT = OffsetDateTime.parse("2026-09-01T00:00:00+08:00");
  static final OffsetDateTime ENDED_AT = OffsetDateTime.parse("2026-10-31T23:59:59+08:00");

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
    bearerB = "Bearer t-b-" + b;
    List<AccessService.Grant> grants = List.of(grant("ALL", allPermissions));
    actors.put(bearerA, actor(user, a, grants));
    actors.put(bearerB, actor(user, b, grants));
    actorA = actors.get(bearerA);
    actorB = actors.get(bearerB);
  }

  /** 播种全局码表（平台目录）。刻意**不在静态块里**播种（那时表还没建）。 */
  @BeforeEach
  void seedGlobalCatalog() {
    exec("insert into platform (id,code,name,integration,support_status,position)"
        + " values ('" + AMAZON + "','AMAZON','Amazon','OFFICIAL_API','AVAILABLE',1)"
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

  @Autowired InfluencerService service;
  @Autowired AccessService access;
  @Autowired MockMvc http;

  // ==================================================================
  // 辅助
  // ==================================================================

  <T> T as(AccessService.Access who, String permission, Supplier<T> work) {
    return access.run(who, permission, work);
  }

  /** 写动作（返回 void 的）单独一个入口 —— 免得 void 表达式塞不进 {@link Supplier}。 */
  void asVoid(AccessService.Access who, String permission, Runnable work) {
    access.run(
        who,
        permission,
        () -> {
          work.run();
          return null;
        });
  }

  /**
   * 达人列表查询（把 18 个位置参数收在一个地方，避免调用点传错位）。
   *
   * <p>筛选维度：类目 / 粉丝层级 / 标签 / 标签分组 / 评级档位 / GMV 区间 /
   * 互动率区间 / 关键字 / 是否含黑名单 / 排序 / 分页。
   */
  InfluencerPage filter(
      AccessService.Access who,
      String category,
      String followerTier,
      UUID tagId,
      String tagCode,
      String tagGroupCode,
      String ratingTierCode,
      BigDecimal minGmv,
      BigDecimal maxGmv,
      BigDecimal minEngagement,
      BigDecimal maxEngagement,
      String keyword,
      boolean includeBlacklisted,
      String sort,
      int page,
      int size) {
    return as(
        who,
        "channel.operation.read",
        () ->
            service.profiles(
                category,
                followerTier,
                tagId,
                tagCode,
                null,
                tagGroupCode,
                ratingTierCode,
                minGmv,
                maxGmv,
                minEngagement,
                maxEngagement,
                null,
                null,
                keyword,
                includeBlacklisted,
                sort,
                page,
                size));
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
  String seedShop(UUID tenant, String code) {
    execAsTenant(tenant,
        "insert into shop (tenant_id,id,platform_id,code,name) values ('" + tenant + "','"
            + UUID.randomUUID() + "','" + AMAZON + "','" + code + "','测试店铺 " + code + "')"
            + " on conflict (tenant_id,code) do nothing");
    return code;
  }

  /**
   * 播种一条 14.1.1.1 广告活动（合作历史的「活动」必须真实存在）。
   *
   * <p>走 2.1.2.1 的平台默认映射 + 14.1.2.2 的广告类型档案 —— 与真实链路一致，
   * 不手写绕过触发器。
   */
  UUID seedCampaign(UUID tenant, String shopCode, String externalId, String name) {
    exec("insert into platform_enum_mapping (id,platform_id,enum_kind,external_value,internal_value)"
        + " values ('" + UUID.randomUUID() + "','" + AMAZON + "','AD_TYPE','sponsored_products','SPONSORED_BRAND')"
        + " on conflict (platform_id,enum_kind,external_value) do nothing");
    UUID profileId = UUID.randomUUID();
    execAsTenant(tenant,
        "insert into ad_type_profile (tenant_id,id,platform_id,platform_ad_type,internal_ad_type,"
            + " display_name,auction_model,objective,mapping_source,enum_kind)"
            + " values ('" + tenant + "','" + profileId + "','" + AMAZON + "','sponsored_products',"
            + "'SPONSORED_BRAND','赞助商品','CPC','SALES','PLATFORM_DEFAULT','AD_TYPE')"
            + " on conflict (tenant_id,id) do nothing");
    UUID campaignId = UUID.randomUUID();
    execAsTenant(tenant,
        "insert into ad_campaign (tenant_id,id,platform_id,shop_code,ad_type_profile_id,"
            + " external_campaign_id,campaign_name,status,currency,data_as_of,last_synced_at)"
            + " values ('" + tenant + "','" + campaignId + "','" + AMAZON + "','" + shopCode + "','"
            + profileId + "','" + externalId + "','" + name + "','ENABLED','USD',now(),now())");
    return campaignId;
  }

  /** 一个达人档案的输入（按需覆盖）。 */
  record Profile(
      String name,
      String category,
      Long followers,
      String engagement,
      String gmv,
      String refundRate) {}

  InfluencerDetail save(AccessService.Access who, String shopCode, Profile profile) {
    return as(
        who,
        "channel.operation.create",
        () ->
            service.saveProfile(
                new SaveInfluencerRequest(
                    AMAZON,
                    shopCode,
                    "",
                    profile.name(),
                    "@" + profile.name(),
                    profile.category(),
                    profile.followers(),
                    profile.engagement() == null ? null : new BigDecimal(profile.engagement()),
                    profile.gmv() == null ? null : new BigDecimal(profile.gmv()),
                    "USD",
                    profile.refundRate() == null ? null : new BigDecimal(profile.refundRate()),
                    "ACTIVE",
                    "测试录入")));
  }

  InfluencerPage list(AccessService.Access who) {
    return filter(
        who, null, null, null, null, null, null, null, null, null, null, null, false, "GMV_DESC", 0,
        50);
  }

  // ==================================================================
  // 达人档案
  // ==================================================================

  @Test
  @DisplayName("14.5.1.1 手动录入：档案真落库，「没录」是 null 不是 0，同步状态诚实")
  void manualEntryWritesRealRow() {
    String shop = seedShop(a, "MANUAL-1");
    var detail = save(actorA, shop, new Profile("露西", "美妆", 120_000L, null, null, null));

    assertThat(detail.influencer().displayName()).isEqualTo("露西");
    assertThat(detail.influencer().category()).isEqualTo("美妆");
    assertThat(detail.influencer().followerCount()).isEqualTo(120_000L);
    assertThat(detail.influencer().followerTier()).isEqualTo("MID");
    // 🔴 没录 = null（不可比），不是 0
    assertThat(detail.influencer().gmv()).as("没录 GMV 必须是 null，不是 0").isNull();
    assertThat(detail.influencer().engagementRate()).isNull();
    assertThat(detail.influencer().refundRate()).isNull();
    // 🔴 手动录入 ⇒ 平台侧还没有
    assertThat(detail.influencer().syncSource()).isEqualTo("MANUAL");
    assertThat(detail.influencer().platformSyncStatus()).isEqualTo("LOCAL_ONLY");
    // 🔴 评分口径未配置 ⇒ 没有评分（不是 0 分）
    assertThat(detail.influencer().ratingScore()).isNull();
    assertThat(detail.influencer().ratingTierCode()).isNull();

    // 从库里读回断言（只断言没抛异常不算数）
    String row = scalar(
        "select (gmv is null)::text || '|' || (engagement_rate is null)::text || '|' || follower_tier"
            + " || '|' || sync_source || '|' || platform_sync_status"
            + " from influencer where tenant_id = '" + a + "' and id = '" + detail.influencer().id() + "'");
    assertThat(row).isEqualTo("true|true|MID|MANUAL|LOCAL_ONLY");
  }

  @Test
  @DisplayName("🔴 粉丝层级由 DB 触发器推导：绕服务层改粉丝数，层级跟着变")
  void followerTierIsDerivedByTrigger() {
    String shop = seedShop(a, "TIER-1");
    var detail = save(actorA, shop, new Profile("层级测试", "家居", 5_000L, null, null, null));
    assertThat(detail.influencer().followerTier()).isEqualTo("NANO");

    // 绕服务层直接改粉丝数 ⇒ 触发器必须改层级（服务层写什么都不作数）
    execAsTenant(a,
        "update influencer set follower_count = 300000 where tenant_id = '" + a + "' and id = '"
            + detail.influencer().id() + "'");
    assertThat(scalar(
            "select follower_tier from influencer where id = '" + detail.influencer().id() + "'"))
        .isEqualTo("MID");

    // 直接插一行伪造层级 ⇒ 触发器仍然按粉丝数覆盖
    UUID forged = UUID.randomUUID();
    execAsTenant(a,
        "insert into influencer (tenant_id,id,platform_id,shop_code,display_name,category,"
            + " follower_count,follower_tier,sync_source,platform_sync_status,status,created_at,updated_at)"
            + " values ('" + a + "','" + forged + "','" + AMAZON + "','" + shop + "','伪造层级','美妆',"
            + " 10,'TOP','MANUAL','LOCAL_ONLY','ACTIVE',now(),now())");
    assertThat(scalar("select follower_tier from influencer where id = '" + forged + "'"))
        .as("伪造的 TOP 层级必须被触发器改回 NANO")
        .isEqualTo("NANO");
  }

  @Test
  @DisplayName("🔴 效果数据入库后能被 GMV / 互动率区间真筛出来")
  void gmvAndEngagementFiltersWork() {
    String shop = seedShop(a, "FILTER-1");
    save(actorA, shop, new Profile("高 GMV 高互动", "美妆", 200_000L, "0.08", "50000", "0.02"));
    save(actorA, shop, new Profile("低 GMV 低互动", "美妆", 200_000L, "0.01", "1000", "0.02"));
    save(actorA, shop, new Profile("没录效果", "美妆", 200_000L, null, null, null));

    var byGmv =
        filter(actorA, null, null, null, null, null, null, new BigDecimal("10000"), null, null, null, null,
            false, "GMV_DESC", 0, 50);
    assertThat(byGmv.items())
        .as("GMV 区间筛选是真筛选")
        .extracting(InfluencerRow::displayName)
        .containsExactly("高 GMV 高互动");

    var byEngagement =
        filter(actorA, null, null, null, null, null, null, null, null, new BigDecimal("0.05"), null, null,
            false, "GMV_DESC", 0, 50);
    assertThat(byEngagement.items())
        .extracting(InfluencerRow::displayName)
        .containsExactly("高 GMV 高互动");

    // 🔴 没录效果数据的达人不该被区间筛选命中（null 不是 0）
    var none =
        filter(actorA, null, null, null, null, null, null, null, null, new BigDecimal("0"),
            new BigDecimal("1"), null, false, "GMV_DESC", 0, 50);
    assertThat(none.items()).extracting(InfluencerRow::displayName).doesNotContain("没录效果");
  }

  @Test
  @DisplayName("真分页 + 真排序")
  void paginationAndSortAreReal() {
    String shop = seedShop(a, "PAGE-1");
    for (int i = 1; i <= 5; i++) {
      save(actorA, shop, new Profile("分页达人 " + i, "美妆", 1_000L, "0.01",
          String.valueOf(i * 1000), "0.01"));
    }
    var page =
        filter(actorA, null, null, null, null, null, null, null, null, null, null, null, false,
            "GMV_DESC", 1, 2);
    assertThat(page.total()).isEqualTo(5);
    assertThat(page.items()).hasSize(2);
    assertThat(page.items().get(0).displayName()).isEqualTo("分页达人 3");
    assertThat(page.items().get(1).displayName()).isEqualTo("分页达人 2");
  }

  // ==================================================================
  // 评级
  // ==================================================================

  @Test
  @DisplayName("🔴 评级是算出来的：分数、档位都对；缺必需数据 ⇒ null 不是 0 分")
  void ratingIsComputedFromRule() {
    String shop = seedShop(a, "RATE-1");
    // 口径：GMV 0.5 / 互动率 0.3 / 退款率 0.2，上限 10 万 / 0.1 / 0.3
    as(
        actorA,
        "channel.operation.update",
        () ->
            service.saveRatingRule(
                new SaveInfluencerRatingRuleRequest(
                    "DEFAULT",
                    new BigDecimal("0.5"),
                    new BigDecimal("0.3"),
                    new BigDecimal("0.2"),
                    new BigDecimal("100000"),
                    new BigDecimal("0.1"),
                    new BigDecimal("0.3"),
                    "ACTIVE",
                    "测试口径")));
    // 档位：S ≥ 0.9 / A ≥ 0.7 / B ≥ 0.4
    as(
        actorA,
        "channel.operation.create",
        () ->
            service.saveRatingTier(
                new SaveInfluencerRatingTierRequest(
                    "S", "顶级达人", new BigDecimal("0.9"), new BigDecimal("1.0"), 100, "ACTIVE", "")));
    as(
        actorA,
        "channel.operation.create",
        () ->
            service.saveRatingTier(
                new SaveInfluencerRatingTierRequest(
                    "A", "优质达人", new BigDecimal("0.7"), new BigDecimal("0.9"), 90, "ACTIVE", "")));
    as(
        actorA,
        "channel.operation.create",
        () ->
            service.saveRatingTier(
                new SaveInfluencerRatingTierRequest(
                    "B", "普通达人", new BigDecimal("0.4"), new BigDecimal("0.7"), 80, "ACTIVE", "")));

    // 0.5×0.5 + 0.3×0.8 + 0.2×0.9 = 0.25 + 0.24 + 0.18 = 0.67 ⇒ B 档
    var good = save(actorA, shop, new Profile("评分甲", "美妆", 100_000L, "0.08", "50000", "0.03"));
    assertThat(good.influencer().ratingScore()).isEqualByComparingTo(new BigDecimal("0.67"));
    assertThat(good.influencer().ratingTierCode()).isEqualTo("B");
    assertThat(good.influencer().ratingTierName()).isEqualTo("普通达人");

    // 🔴 缺退款率 ⇒ 没有评分（不是 0 分），也不落任何档位
    var incomplete = save(actorA, shop, new Profile("评分乙", "美妆", 100_000L, "0.08", "50000", null));
    assertThat(incomplete.influencer().ratingScore()).as("缺必需数据 ⇒ 没有评分，不是 0 分").isNull();
    assertThat(incomplete.influencer().ratingTierCode()).isNull();

    // 改效果数据 ⇒ 评分跟着变（不是停留在旧数据上）
    var updated =
        as(
            actorA,
            "channel.operation.update",
            () ->
                service.updateProfile(
                    good.influencer().id().toString(),
                    new SaveInfluencerRequest(
                        AMAZON, shop, "", "评分甲", "@评分甲", "美妆", 100_000L,
                        new BigDecimal("0.10"), new BigDecimal("100000"), "USD",
                        new BigDecimal("0.0"), "ACTIVE", "")));
    // 0.5×1 + 0.3×1 + 0.2×1 = 1.00 ⇒ S 档
    assertThat(updated.influencer().ratingScore()).isEqualByComparingTo(new BigDecimal("1.00"));
    assertThat(updated.influencer().ratingTierCode()).isEqualTo("S");
  }

  @Test
  @DisplayName("🔴 按评级排序：档位排序位说了算，不是档位编码的字典序")
  void ratingSortUsesTierOrder() {
    String shop = seedShop(a, "RATE-2");
    as(
        actorA,
        "channel.operation.update",
        () ->
            service.saveRatingRule(
                new SaveInfluencerRatingRuleRequest(
                    "DEFAULT",
                    new BigDecimal("1"),
                    new BigDecimal("0"),
                    new BigDecimal("0"),
                    new BigDecimal("100000"),
                    new BigDecimal("0.1"),
                    new BigDecimal("0.3"),
                    "ACTIVE", "")));
    // 档位编码故意用字典序相反的顺序：B 的排序位高于 A
    as(
        actorA,
        "channel.operation.create",
        () ->
            service.saveRatingTier(
                new SaveInfluencerRatingTierRequest(
                    "A", "A 档", new BigDecimal("0"), new BigDecimal("0.5"), 50, "ACTIVE", "")));
    as(
        actorA,
        "channel.operation.create",
        () ->
            service.saveRatingTier(
                new SaveInfluencerRatingTierRequest(
                    "B", "B 档", new BigDecimal("0.5"), new BigDecimal("1"), 90, "ACTIVE", "")));

    save(actorA, shop, new Profile("低分达人", "美妆", 1_000L, null, "10000", null));
    save(actorA, shop, new Profile("高分达人", "美妆", 1_000L, null, "90000", null));

    var sorted =
        filter(actorA, null, null, null, null, null, null, null, null, null, null, null, false,
            "RATING_DESC", 0, 50);
    assertThat(sorted.items())
        .extracting(InfluencerRow::displayName)
        .containsExactly("高分达人", "低分达人");
    assertThat(sorted.items().get(0).ratingTierCode()).isEqualTo("B");
  }

  @Test
  @DisplayName("🔴 评分权重之和必须为 1")
  void ratingRuleWeightsMustSumToOne() {
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    as(
                        actorA,
                        "channel.operation.update",
                        () ->
                            service.saveRatingRule(
                                new SaveInfluencerRatingRuleRequest(
                                    "DEFAULT",
                                    new BigDecimal("0.5"),
                                    new BigDecimal("0.3"),
                                    new BigDecimal("0.3"),
                                    new BigDecimal("100000"),
                                    new BigDecimal("0.1"),
                                    new BigDecimal("0.3"),
                                    "ACTIVE", "")))))
        .isNotNull();
  }

  // ==================================================================
  // 标签与标签分组
  // ==================================================================

  @Test
  @DisplayName("🔴 标签筛选：按标签 / 标签分组只命中被打标的达人")
  void tagFilterFindsOnlyTaggedInfluencers() {
    String shop = seedShop(a, "TAG-1");
    var group =
        as(
            actorA,
            "channel.operation.create",
            () ->
                service.saveTagGroup(
                    new SaveInfluencerTagGroupRequest("BEAUTY", "美妆达人", "美妆类目", "ACTIVE", "")));
    var tag =
        as(
            actorA,
            "channel.operation.create",
            () ->
                service.saveTag(
                    new SaveInfluencerTagRequest(
                        group.id(), "HIGH_REFUND", "高退货", "退货率偏高", "ACTIVE", "")));

    var tagged = save(actorA, shop, new Profile("被打标的达人", "美妆", 10_000L, "0.02", "5000", "0.2"));
    save(actorA, shop, new Profile("没被打标的达人", "美妆", 10_000L, "0.02", "5000", "0.2"));

    as(
        actorA,
        "channel.operation.update",
        () -> service.assignTags(tagged.influencer().id().toString(),
            new TagInfluencersRequest(List.of(tag.id()), List.of(), "测试打标")));

    var byTag =
        filter(actorA, null, null, tag.id(), null, null, null, null, null, null, null, null, false,
            "GMV_DESC", 0, 50);
    assertThat(byTag.items()).extracting(InfluencerRow::displayName).containsExactly("被打标的达人");

    var byTagCode =
        filter(actorA, null, null, null, "HIGH_REFUND", null, null, null, null, null, null, null, false,
            "GMV_DESC", 0, 50);
    assertThat(byTagCode.items()).extracting(InfluencerRow::displayName).containsExactly("被打标的达人");

    var byGroup =
        filter(actorA, null, null, null, null, "BEAUTY", null, null, null, null, null, null, false,
            "GMV_DESC", 0, 50);
    assertThat(byGroup.items()).extracting(InfluencerRow::displayName).containsExactly("被打标的达人");

    // 标签反查达人（按标签分组归拢）
    var tags = as(actorA, "channel.operation.read", () -> service.tags(null, null, null, 0, 50));
    assertThat(tags.items()).hasSize(1);
    assertThat(tags.items().get(0).influencerCount()).isEqualTo(1);
    assertThat(tags.items().get(0).groupCode()).isEqualTo("BEAUTY");

    // 移除标签后不再命中
    as(
        actorA,
        "channel.operation.update",
        () -> service.removeTag(tagged.influencer().id().toString(), tag.id().toString()));
    var afterRemove =
        filter(actorA, null, null, null, "HIGH_REFUND", null, null, null, null, null, null, null, false,
            "GMV_DESC", 0, 50);
    assertThat(afterRemove.items()).isEmpty();
  }

  @Test
  @DisplayName("🔴 标签正被引用时不许删（悄悄解绑会改变筛选结果）")
  void tagInUseCannotBeDeleted() {
    String shop = seedShop(a, "TAG-2");
    var group =
        as(
            actorA,
            "channel.operation.create",
            () ->
                service.saveTagGroup(
                    new SaveInfluencerTagGroupRequest("G2", "分组二", "", "ACTIVE", "")));
    var tag =
        as(
            actorA,
            "channel.operation.create",
            () ->
                service.saveTag(
                    new SaveInfluencerTagRequest(group.id(), "T2", "标签二", "", "ACTIVE", "")));
    var influencer = save(actorA, shop, new Profile("引用者", "美妆", 1_000L, null, null, null));
    as(
        actorA,
        "channel.operation.update",
        () ->
            service.assignTags(
                influencer.influencer().id().toString(),
                new TagInfluencersRequest(List.of(tag.id()), List.of(), "")));

    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () -> asVoid(actorA, "channel.operation.delete", () -> service.deleteTag(tag.id().toString()))))
        .as("还被引用的标签不许删")
        .isNotNull();
  }

  // ==================================================================
  // 黑名单
  // ==================================================================

  @Test
  @DisplayName("🔴 拉黑后从正常检索里排除；解除后回到正常检索且解除留痕")
  void blacklistExcludesFromNormalSearch() {
    String shop = seedShop(a, "BL-1");
    var influencer = save(actorA, shop, new Profile("被拉黑的达人", "美妆", 50_000L, "0.05", "20000", "0.02"));
    save(actorA, shop, new Profile("正常达人", "美妆", 50_000L, "0.05", "20000", "0.02"));

    var blacklist =
        as(
            actorA,
            "channel.operation.create",
            () ->
                service.blacklistInfluencer(
                    new BlacklistInfluencerRequest(
                        influencer.influencer().id(),
                        "POOR_QUALITY",
                        "合作三次零产出",
                        EFFECTIVE_AT,
                        "测试拉黑")));

    // 🔴 从正常检索里排除
    assertThat(list(actorA).items())
        .as("拉黑后必须从正常检索里排除")
        .extracting(InfluencerRow::displayName)
        .containsExactly("正常达人");

    // 显式要看得见黑名单里的达人时也能看到
    var withBlacklisted =
        filter(actorA, null, null, null, null, null, null, null, null, null, null, null, true,
            "GMV_DESC", 0, 50);
    assertThat(withBlacklisted.items())
        .extracting(InfluencerRow::displayName)
        .containsExactlyInAnyOrder("正常达人", "被拉黑的达人");
    assertThat(withBlacklisted.items().stream().filter(InfluencerRow::blacklisted).count())
        .isEqualTo(1);

    // 黑名单列表：原因、生效时间都在
    var blacklists =
        as(actorA, "channel.operation.read", () -> service.blacklist(null, null, null, null, 0, 50));
    assertThat(blacklists.items()).hasSize(1);
    assertThat(blacklists.items().get(0).reasonCode()).isEqualTo("POOR_QUALITY");
    assertThat(blacklists.items().get(0).reason()).isEqualTo("合作三次零产出");
    assertThat(blacklists.items().get(0).status()).isEqualTo("ACTIVE");

    // 🔴 生效时刻写回值正确（+08:00 的时刻不能偏 −28800s）
    assertThat(blacklists.items().get(0).effectiveAt().toInstant())
        .as("timestamptz 写回值必须与写入的时刻同一个瞬间")
        .isEqualTo(EFFECTIVE_AT.toInstant());

    // 重复拉黑被拒
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    as(
                        actorA,
                        "channel.operation.create",
                        () ->
                            service.blacklistInfluencer(
                                new BlacklistInfluencerRequest(
                                    influencer.influencer().id(),
                                    "FRAUD",
                                    "重复拉黑",
                                    null,
                                    "")))))
        .as("同一达人同时只能有一条生效中的黑名单")
        .isNotNull();

    // 🔴 解除：留痕（解除时刻 + 理由），不删行
    var released =
        as(
            actorA,
            "channel.operation.update",
            () -> service.releaseBlacklist(blacklist.id().toString(), new ReleaseBlacklistRequest("误拉黑，已复核")));
    assertThat(released.status()).isEqualTo("RELEASED");
    assertThat(released.releasedAt()).as("解除必须留痕").isNotNull();
    assertThat(released.releasedReason()).isEqualTo("误拉黑，已复核");
    assertThat(released.effectiveAt().toInstant()).isEqualTo(EFFECTIVE_AT.toInstant());

    // 解除后回到正常检索，但历史记录还在
    assertThat(list(actorA).items())
        .extracting(InfluencerRow::displayName)
        .containsExactlyInAnyOrder("正常达人", "被拉黑的达人");
    var history =
        as(actorA, "channel.operation.read", () -> service.blacklist("RELEASED", null, null, null, 0, 50));
    assertThat(history.items()).as("已解除的黑名单记录必须留痕").hasSize(1);
  }

  @Test
  @DisplayName("🔴 黑名单原因码是封闭词表（没有「其它」桶）")
  void blacklistReasonCodeIsClosed() {
    String shop = seedShop(a, "BL-2");
    var influencer = save(actorA, shop, new Profile("原因码测试", "美妆", 1_000L, null, null, null));
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    as(
                        actorA,
                        "channel.operation.create",
                        () ->
                            service.blacklistInfluencer(
                                new BlacklistInfluencerRequest(
                                    influencer.influencer().id(), "OTHER", "其它", null, "")))))
        .as("「其它」不是一个合法的拉黑原因码")
        .isNotNull();
  }

  @Test
  @DisplayName("🔴 有黑名单记录的达人不许物理删除")
  void influencerWithEvidenceCannotBeDeleted() {
    String shop = seedShop(a, "BL-3");
    var influencer = save(actorA, shop, new Profile("有证据的达人", "美妆", 1_000L, null, null, null));
    as(
        actorA,
        "channel.operation.create",
        () ->
            service.blacklistInfluencer(
                new BlacklistInfluencerRequest(
                    influencer.influencer().id(), "FRAUD", "刷量", null, "")));

    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () -> asVoid(actorA, "channel.operation.delete", () -> service.deleteProfile(
                    influencer.influencer().id().toString()))))
        .as("有黑名单记录的达人是举证凭据，不许物理删除")
        .isNotNull();
  }

  // ==================================================================
  // 合作历史
  // ==================================================================

  @Test
  @DisplayName("🔴 合作历史：达人 × 活动 + 效果数据；档案 GMV 与合作合计是两个口径")
  void cooperationRecordsEffectAndHistory() {
    String shop = seedShop(a, "COOP-1");
    UUID campaignId = seedCampaign(a, shop, "EXT-COOP-1", "秋季达人专场");
    var influencer = save(actorA, shop, new Profile("合作达人", "美妆", 100_000L, "0.06", "30000", "0.03"));

    var cooperation =
        as(
            actorA,
            "channel.operation.create",
            () ->
                service.saveCooperation(
                    new SaveCooperationRequest(
                        influencer.influencer().id(),
                        campaignId,
                        "COMPLETED",
                        STARTED_AT,
                        ENDED_AT,
                        new BigDecimal("12345.6789"),
                        new BigDecimal("321"),
                        new BigDecimal("1234.5678"),
                        new BigDecimal("0.04"),
                        "USD",
                        "测试合作")));

    assertThat(cooperation.campaignName()).isEqualTo("秋季达人专场");
    assertThat(cooperation.status()).isEqualTo("COMPLETED");
    assertThat(cooperation.gmv()).isEqualByComparingTo(new BigDecimal("12345.6789"));
    assertThat(cooperation.orderCount()).isEqualByComparingTo(new BigDecimal("321"));
    assertThat(cooperation.commission()).isEqualByComparingTo(new BigDecimal("1234.5678"));
    assertThat(cooperation.refundRate()).isEqualByComparingTo(new BigDecimal("0.04"));

    // 🔴 合作起止时刻写回值正确
    assertThat(cooperation.startedAt().toInstant()).isEqualTo(STARTED_AT.toInstant());
    assertThat(cooperation.endedAt().toInstant()).isEqualTo(ENDED_AT.toInstant());

    // 详情里能看到合作历史，合作 GMV 合计是本地求和
    var detail = as(actorA, "channel.operation.read", () -> service.profile(influencer.influencer().id().toString()));
    assertThat(detail.cooperations()).hasSize(1);
    assertThat(detail.influencer().cooperationCount()).isEqualTo(1);
    assertThat(detail.influencer().gmvFromCooperations())
        .as("合作合计是本地按合作行求和")
        .isEqualByComparingTo(new BigDecimal("12345.6789"));
    assertThat(detail.influencer().gmv())
        .as("档案 GMV 与合作合计是两个口径，不能混成一个数")
        .isEqualByComparingTo(new BigDecimal("30000"));

    // 合作历史列表（跨达人）
    var cooperations =
        as(actorA, "channel.operation.read", () -> service.cooperations(null, null, null, null, 0, 50));
    assertThat(cooperations.items()).hasSize(1);
    assertThat(cooperations.items().get(0).influencerName()).isEqualTo("合作达人");

    // 🔴 有合作历史的达人不许物理删除
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () -> asVoid(actorA, "channel.operation.delete", () -> service.deleteProfile(
                    influencer.influencer().id().toString()))))
        .isNotNull();
  }

  @Test
  @DisplayName("🔴 达人与活动必须在同一个店铺下")
  void cooperationRequiresSameShop() {
    String shopOne = seedShop(a, "COOP-S1");
    String shopTwo = seedShop(a, "COOP-S2");
    UUID campaignId = seedCampaign(a, shopTwo, "EXT-COOP-2", "别的店铺的活动");
    var influencer = save(actorA, shopOne, new Profile("跨店铺达人", "美妆", 1_000L, null, null, null));

    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    as(
                        actorA,
                        "channel.operation.create",
                        () ->
                            service.saveCooperation(
                                new SaveCooperationRequest(
                                    influencer.influencer().id(), campaignId, "PLANNED", null, null,
                                    null, null, null, null, "USD", "")))))
        .as("跨店铺挂合作会让「按店铺收敛的可见性」与事实对不上")
        .isNotNull();
  }

  // ==================================================================
  // 平台同步能力
  // ==================================================================

  @Test
  @DisplayName("🔴 平台达人数据同步未接入：明确说未接入，且不写任何数据")
  void platformSyncIsNotIntegrated() {
    String shop = seedShop(a, "SYNC-1");
    long before = countInfluencers(a);

    var status = as(actorA, "channel.operation.read", () -> service.syncStatus());
    assertThat(status.platforms()).isNotEmpty();
    assertThat(status.platforms())
        .allSatisfy(
            p -> {
              assertThat(p.available()).as("平台达人数据适配器未交付").isFalse();
              assertThat(p.status()).isEqualTo("NOT_INTEGRATED");
              assertThat(p.reason()).contains("未交付");
            });
    assertThat(status.syncNote()).contains("手动录入");

    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    as(
                        actorA,
                        "channel.operation.create",
                        () -> service.sync(new SyncInfluencersRequest("AMAZON", shop)))))
        .as("同步入口必须明确拒绝（不编造平台接口）")
        .isNotNull();

    assertThat(countInfluencers(a))
        .as("同步失败不得写入任何达人数据")
        .isEqualTo(before);
  }

  // ==================================================================
  // HTTP 端点
  // ==================================================================

  @Test
  @DisplayName("HTTP：列表端点真分页；缺权限时 403")
  void httpEndpointsWork() throws Exception {
    String shop = seedShop(a, "HTTP-1");
    save(actorA, shop, new Profile("接口达人", "美妆", 1_000L, "0.01", "1000", "0.01"));

    http.perform(get("/channel/influencers/profiles")
            .header("Authorization", bearerA)
            .param("size", "10"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.items[0].displayName").value("接口达人"));

    http.perform(get("/channel/influencers/capabilities").header("Authorization", bearerA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.dimensions.length()").value(org.hamcrest.Matchers.greaterThan(0)));

    http.perform(get("/channel/influencers/profiles")).andExpect(status().isUnauthorized());
  }

  // ==================================================================
  // 小工具
  // ==================================================================

  String scalar(String sql) {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement();
        var rs = s.executeQuery(sql)) {
      return rs.next() ? rs.getString(1) : null;
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  long countInfluencers(UUID tenant) {
    String value = scalar("select count(*) from influencer where tenant_id = '" + tenant + "'");
    return value == null ? 0L : Long.parseLong(value);
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
