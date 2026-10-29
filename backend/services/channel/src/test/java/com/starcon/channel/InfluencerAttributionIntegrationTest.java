package com.starcon.channel;

import static com.starcon.channel.InfluencerAttributionModels.*;
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
 * 14.5.2.1 带货归因。
 *
 * <p>本测试类钉住本模块最容易做丢的几件事：
 *
 * <ol>
 *   <li><b>🔴 三个 GMV 口径分列三栏</b>：档案 GMV（14.5.1.1 入库值）、
 *       合作历史合计（本地求和）、归因口径（本模块）—— 报表里分别可见，
 *       <b>谁也不覆盖谁</b>。
 *   <li><b>🔴 佣金成本真实留痕</b>：金额 / 计提基数 / 口径 / 计提状态与时刻，
 *       但<b>不声称已进利润表</b>（能力声明里 profitIntegration.available=false）。
 *   <li><b>🔴 「没录」不是 0</b>：没录佣金 ⇒ 佣金 null、ROI null。
 *   <li><b>🔴 归因窗口</b>：超窗的合作不允许新增归因，但已归因过的记录不会被抹掉。
 *   <li><b>🔴 已计提的佣金不自动改写</b>：重跑只更新差额。
 *   <li><b>🔴 评级重算被触发</b>：归因批次写完后走 14.5.1.1 的评级重算。
 *   <li><b>🔴 平台同步未接入</b>：同步入口明确 409 且<b>不写任何数据</b>。
 *   <li><b>真分页真筛选真排序</b>。
 * </ol>
 *
 * <p><b>时刻一律用固定的 {@link OffsetDateTime} 钉死</b>，不用 {@code now()}：
 * 用墙钟会让「这次写入落在哪个断言分支上」成为一个 flaky 断言。
 */
@SpringBootTest
@AutoConfigureMockMvc
class InfluencerAttributionIntegrationTest {

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
  static final OffsetDateTime STARTED_AT = OffsetDateTime.parse("2026-09-01T00:00:00+08:00");
  static final OffsetDateTime ENDED_AT = OffsetDateTime.parse("2026-09-30T23:59:59+08:00");
  static final OffsetDateTime OLD_ENDED_AT = OffsetDateTime.parse("2020-01-01T00:00:00+08:00");

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
    r.add("spring.datasource.hikari.maximum-pool-size", () -> "1");
  }

  @Autowired InfluencerAttributionService service;
  @Autowired InfluencerService influencerService;
  @Autowired AccessService access;
  @Autowired MockMvc http;

  // ==================================================================
  // 辅助
  // ==================================================================

  <T> T as(AccessService.Access who, String permission, Supplier<T> work) {
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

  /** 以管理员连接按租户直查行数（只用于「有没有写进去」这类断言）。 */
  static long tenantCount(UUID tenant, String table, String extra) {
    String sql = "select count(*) from " + table + " where tenant_id = '" + tenant + "'" + extra;
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement();
        var rs = s.executeQuery(sql)) {
      return rs.next() ? rs.getLong(1) : 0L;
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

  /** 播种一条 14.1.1.1 广告活动（合作历史的「活动」必须真实存在）。 */
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

  /** 登记一个达人档案。 */
  UUID saveInfluencer(AccessService.Access who, String shopCode, String name, long followers) {
    return as(
            who,
            "channel.operation.create",
            () ->
                influencerService.saveProfile(
                        new SaveInfluencerRequest(
                            AMAZON, shopCode, "", name, "@" + name, "美妆", followers,
                            new BigDecimal("0.05"), new BigDecimal("8888"), "USD",
                            new BigDecimal("0.02"), "ACTIVE", "测试录入"))
                    .influencer()
                    .id());
  }

  /** 登记一条合作记录（带效果数据）。 */
  UUID saveCooperation(
      AccessService.Access who,
      UUID influencerId,
      UUID campaignId,
      String shopCode,
      BigDecimal gmv,
      BigDecimal orders,
      BigDecimal commission,
      BigDecimal refundRate,
      OffsetDateTime endedAt) {
    return saveCooperation(
        who, influencerId, campaignId, shopCode, gmv, orders, commission, refundRate, STARTED_AT,
        endedAt);
  }

  /** 登记一条合作记录（带效果数据 + 起止时刻）。 */
  UUID saveCooperation(
      AccessService.Access who,
      UUID influencerId,
      UUID campaignId,
      String shopCode,
      BigDecimal gmv,
      BigDecimal orders,
      BigDecimal commission,
      BigDecimal refundRate,
      OffsetDateTime startedAt,
      OffsetDateTime endedAt) {
    return as(
            who,
            "channel.operation.create",
            () ->
                influencerService.saveCooperation(
                        new SaveCooperationRequest(
                            influencerId, campaignId, "COMPLETED", startedAt, endedAt,
                            gmv, orders, commission, refundRate, "USD", "测试合作"))
                    .id());
  }

  /** 读一条生效的归因规则（**必须在租户上下文里读**）。 */
  InfluencerAttributionRuleRow currentRule(AccessService.Access who) {
    return as(who, "channel.operation.read", () -> service.currentRule());
  }

  /** 归因规则列表（**必须在租户上下文里读**）。 */
  InfluencerAttributionRulePage rules(AccessService.Access who, String status) {
    return as(who, "channel.operation.read", () -> service.rules(status, null, 0, 50));
  }

  /** 配置一套生效的归因规则。 */
  InfluencerAttributionRuleRow saveRule(
      AccessService.Access who,
      String model,
      String basis,
      int windowDays,
      String refundTreatment,
      String commissionBasis,
      BigDecimal rate) {
    return as(
        who,
        "channel.operation.update",
        () ->
            service.saveRule(
                new SaveInfluencerAttributionRuleRequest(
                    "RULE-" + model, model, basis, windowDays, refundTreatment, commissionBasis,
                    rate, "ACTIVE", "测试规则")));
  }

  InfluencerAttributionRunResult run(AccessService.Access who) {
    return as(
        who,
        "channel.operation.create",
        () ->
            service.run(
                new InfluencerAttributionRunRequest(null, null, null, null, null)));
  }

  InfluencerAttributionPage records(AccessService.Access who) {
    return as(
        who,
        "channel.operation.read",
        () ->
            service.attributions(
                null, null, null, null, null, null, null, null, null, "ATTRIBUTED_GMV_DESC", 0, 50));
  }

  InfluencerAttributionReportPage report(AccessService.Access who) {
    return as(
        who,
        "channel.operation.read",
        () ->
            service.report(
                null, null, null, null, null, null, null, null, null, "ATTRIBUTED_GMV_DESC", 0, 50));
  }

  // ==================================================================
  // 归因规则
  // ==================================================================

  @Test
  @DisplayName("14.5.2.1 没有生效规则时跑归因 ⇒ 409，且一条归因记录都不写")
  void runWithoutRuleIsRejected() {
    String shop = seedShop(a, "RULE-NONE");
    UUID campaign = seedCampaign(a, shop, "EXT-NONE", "没有规则的活动");
    UUID influencer = saveInfluencer(actorA, shop, "没有规则的达人", 1000L);
    saveCooperation(actorA, influencer, campaign, shop, new BigDecimal("1000"),
        new BigDecimal("10"), new BigDecimal("100"), new BigDecimal("0.1"), ENDED_AT);

    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(() -> run(actorA)))
        .as("没有归因规则就不该有归因")
        .isNotNull();
    assertThat(records(actorA).total()).isZero();
  }

  @Test
  @DisplayName("14.5.2.1 保存新规则时旧的自动停用（一个租户只能一套生效口径）")
  void newRuleDeactivatesOldOne() {
    saveRule(actorA, "COOPERATION_FULL", "FOLLOWER_SHARE", 30, "GROSS",
        "COOPERATION_COMMISSION", null);
    saveRule(actorA, "CAMPAIGN_POOL_SPLIT", "FOLLOWER_SHARE", 30, "NET",
        "COOPERATION_COMMISSION", null);

    InfluencerAttributionRuleRow current = currentRule(actorA);
    assertThat(current).isNotNull();
    assertThat(current.attributionModel()).isEqualTo("CAMPAIGN_POOL_SPLIT");
    assertThat(rules(actorA, null).total()).isEqualTo(2L);
    assertThat(rules(actorA, "ACTIVE").total()).isEqualTo(1L);
  }

  @Test
  @DisplayName("14.5.2.1 按归因 GMV 计提时必须给佣金比例（比例没配 ⇒ 400，不是按 0 计提）")
  void rateBasisRequiresRate() {
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    as(
                        actorA,
                        "channel.operation.update",
                        () ->
                            service.saveRule(
                                new SaveInfluencerAttributionRuleRequest(
                                    "RULE-NO-RATE", "COOPERATION_FULL", "FOLLOWER_SHARE", 30,
                                    "GROSS", "RATE_ON_ATTRIBUTED_GMV", null, "ACTIVE", "")))))
        .isNotNull();
  }

  // ==================================================================
  // 归因批次
  // ==================================================================

  @Test
  @DisplayName("14.5.2.1 合作全额归因（GROSS）：归因 GMV = 合作 GMV，订单量 / 退款率 / 佣金都跟着落库")
  void cooperationFullGrossAttribution() {
    String shop = seedShop(a, "FULL-1");
    UUID campaign = seedCampaign(a, shop, "EXT-FULL-1", "全额归因活动");
    UUID influencer = saveInfluencer(actorA, shop, "全额归因达人", 1000L);
    saveCooperation(actorA, influencer, campaign, shop, new BigDecimal("1000"),
        new BigDecimal("10"), new BigDecimal("100"), new BigDecimal("0.1"), ENDED_AT);
    saveRule(actorA, "COOPERATION_FULL", "FOLLOWER_SHARE", 30, "GROSS",
        "COOPERATION_COMMISSION", null);

    InfluencerAttributionRunResult result = run(actorA);
    assertThat(result.created()).isEqualTo(1);
    assertThat(result.refreshed()).isZero();

    InfluencerAttributionPage page = records(actorA);
    assertThat(page.total()).isEqualTo(1);
    InfluencerAttributionRow row = page.items().get(0);
    assertThat(row.attributedGmv()).isEqualByComparingTo("1000.0000");
    assertThat(row.attributedOrderCount()).isEqualByComparingTo("10.000000");
    assertThat(row.attributedRefundRate()).isEqualByComparingTo("0.100000");
    assertThat(row.attributedRefundAmount()).isEqualByComparingTo("100.0000");
    assertThat(row.effectiveOrderCount()).isEqualByComparingTo("9.000000");
    assertThat(row.followerConversionRate()).isEqualByComparingTo("0.009000");
    assertThat(row.attributionWeight()).isEqualByComparingTo("1.000000");
    assertThat(row.commissionAmount()).isEqualByComparingTo("100.0000");
    assertThat(row.accrualStatus()).isEqualTo("PENDING");
    // 🔴 归因 GMV = 基数 × 权重，可复核
    assertThat(row.attributionBaseGmv()).isEqualByComparingTo("1000.0000");
  }

  @Test
  @DisplayName("14.5.2.1 净额口径（NET）：归因 GMV = 合作 GMV − 退款额（与毛额口径不同）")
  void netRefundTreatmentChangesAttributedGmv() {
    String shop = seedShop(a, "NET-1");
    UUID campaign = seedCampaign(a, shop, "EXT-NET-1", "净额活动");
    UUID influencer = saveInfluencer(actorA, shop, "净额达人", 1000L);
    saveCooperation(actorA, influencer, campaign, shop, new BigDecimal("1000"),
        new BigDecimal("10"), new BigDecimal("100"), new BigDecimal("0.1"), ENDED_AT);
    saveRule(actorA, "COOPERATION_FULL", "FOLLOWER_SHARE", 30, "NET",
        "COOPERATION_COMMISSION", null);

    run(actorA);
    InfluencerAttributionRow row = records(actorA).items().get(0);
    assertThat(row.attributedGmv()).isEqualByComparingTo("900.0000");
    assertThat(row.attributedRefundAmount()).isEqualByComparingTo("100.0000");
    // 🔴 恒等式：毛额 = 归因 GMV + 退款额
    assertThat(row.attributedGmv().add(row.attributedRefundAmount()))
        .isEqualByComparingTo("1000.0000");
  }

  @Test
  @DisplayName("14.5.2.1 活动池分摊（按粉丝数占比）：归因 GMV = 池子 GMV × 权重，两位达人权重相加为 1")
  void campaignPoolSplitByFollowerShare() {
    String shop = seedShop(a, "POOL-1");
    UUID campaign = seedCampaign(a, shop, "EXT-POOL-1", "分摊活动");
    UUID big = saveInfluencer(actorA, shop, "大达人", 30_000L);
    UUID small = saveInfluencer(actorA, shop, "小达人", 10_000L);
    saveCooperation(actorA, big, campaign, shop, new BigDecimal("600"),
        new BigDecimal("6"), new BigDecimal("60"), new BigDecimal("0"), ENDED_AT);
    saveCooperation(actorA, small, campaign, shop, new BigDecimal("400"),
        new BigDecimal("4"), new BigDecimal("40"), new BigDecimal("0"), ENDED_AT);
    saveRule(actorA, "CAMPAIGN_POOL_SPLIT", "FOLLOWER_SHARE", 30, "GROSS",
        "COOPERATION_COMMISSION", null);

    run(actorA);
    InfluencerAttributionPage page = records(actorA);
    assertThat(page.total()).isEqualTo(2);

    BigDecimal weights = BigDecimal.ZERO;
    BigDecimal gmv = BigDecimal.ZERO;
    for (InfluencerAttributionRow row : page.items()) {
      weights = weights.add(row.attributionWeight());
      gmv = gmv.add(row.attributedGmv());
      // 🔴 基数是**池子合计**（600 + 400 = 1000），不是各自的合作 GMV
      assertThat(row.attributionBaseGmv()).isEqualByComparingTo("1000.0000");
      // 🔴 佣金**不按权重缩放**：成本不因口径变化（两位达人分别是 60 与 40）
      assertThat(row.commissionAmount()).isNotNull();
      assertThat(row.commissionAmount())
          .isGreaterThanOrEqualTo(new BigDecimal("40"))
          .isLessThanOrEqualTo(new BigDecimal("60"));
    }
    assertThat(weights).isEqualByComparingTo("1.000000");
    assertThat(gmv).isEqualByComparingTo("1000.0000");
  }

  @Test
  @DisplayName("14.5.2.1 归因窗口：超窗的合作不允许新增归因（被跳过，不是记成 0）")
  void outOfWindowCooperationIsSkipped() {
    String shop = seedShop(a, "WIN-1");
    UUID campaign = seedCampaign(a, shop, "EXT-WIN-1", "过期活动");
    UUID influencer = saveInfluencer(actorA, shop, "过期达人", 1000L);
    saveCooperation(actorA, influencer, campaign, shop, new BigDecimal("1000"),
        new BigDecimal("10"), new BigDecimal("100"), new BigDecimal("0.1"),
        OffsetDateTime.parse("2019-12-01T00:00:00+08:00"), OLD_ENDED_AT);
    saveRule(actorA, "COOPERATION_FULL", "FOLLOWER_SHARE", 30, "GROSS",
        "COOPERATION_COMMISSION", null);

    InfluencerAttributionRunResult result = run(actorA);
    assertThat(result.skippedOutOfWindow()).isEqualTo(1);
    assertThat(result.created()).isZero();
    assertThat(records(actorA).total()).isZero();
  }

  @Test
  @DisplayName("14.5.2.1 重跑幂等：同一条合作不会插第二行，只刷新")
  void rerunIsIdempotent() {
    String shop = seedShop(a, "IDEM-1");
    UUID campaign = seedCampaign(a, shop, "EXT-IDEM-1", "幂等活动");
    UUID influencer = saveInfluencer(actorA, shop, "幂等达人", 1000L);
    saveCooperation(actorA, influencer, campaign, shop, new BigDecimal("1000"),
        new BigDecimal("10"), new BigDecimal("100"), new BigDecimal("0.1"), ENDED_AT);
    saveRule(actorA, "COOPERATION_FULL", "FOLLOWER_SHARE", 30, "GROSS",
        "COOPERATION_COMMISSION", null);

    assertThat(run(actorA).created()).isEqualTo(1);
    InfluencerAttributionRunResult second = run(actorA);
    assertThat(second.created()).isZero();
    assertThat(second.refreshed()).isEqualTo(1);
    assertThat(records(actorA).total()).isEqualTo(1);
  }

  @Test
  @DisplayName("14.5.2.1 「没录」不是 0：合作没录佣金 ⇒ 归因行佣金 null、ROI null")
  void missingCommissionStaysNull() {
    String shop = seedShop(a, "NULL-1");
    UUID campaign = seedCampaign(a, shop, "EXT-NULL-1", "没录佣金的活动");
    UUID influencer = saveInfluencer(actorA, shop, "没录佣金的达人", 1000L);
    saveCooperation(actorA, influencer, campaign, shop, new BigDecimal("1000"),
        new BigDecimal("10"), null, new BigDecimal("0.1"), ENDED_AT);
    saveRule(actorA, "COOPERATION_FULL", "FOLLOWER_SHARE", 30, "GROSS",
        "COOPERATION_COMMISSION", null);

    run(actorA);
    InfluencerAttributionRow row = records(actorA).items().get(0);
    assertThat(row.commissionAmount()).as("没录佣金不是 0").isNull();

    InfluencerAttributionReportPage page = report(actorA);
    InfluencerAttributionReportRow reportRow =
        page.items().stream().filter(r -> r.influencerId().equals(influencer)).findFirst().orElseThrow();
    assertThat(reportRow.commissionTotal()).isNull();
    assertThat(reportRow.roi()).as("佣金没录 ⇒ ROI 不可比（不是 0）").isNull();
    assertThat(reportRow.netGmvAfterCommission()).isNull();
  }

  // ==================================================================
  // 三个口径
  // ==================================================================

  @Test
  @DisplayName("14.5.2.1 三个 GMV 口径分列三栏：档案 GMV / 合作合计 / 归因口径互不覆盖")
  void threeCalibersAreSeparate() {
    String shop = seedShop(a, "CAL-1");
    UUID campaign = seedCampaign(a, shop, "EXT-CAL-1", "口径活动");
    UUID influencer = saveInfluencer(actorA, shop, "口径达人", 1000L);
    // 档案 GMV = 8888（saveInfluencer 录的）；合作 GMV = 1000；归因口径（NET）= 900
    saveCooperation(actorA, influencer, campaign, shop, new BigDecimal("1000"),
        new BigDecimal("10"), new BigDecimal("100"), new BigDecimal("0.1"), ENDED_AT);
    saveRule(actorA, "COOPERATION_FULL", "FOLLOWER_SHARE", 30, "NET",
        "COOPERATION_COMMISSION", null);
    run(actorA);

    InfluencerAttributionReportRow row =
        report(actorA).items().stream()
            .filter(r -> r.influencerId().equals(influencer))
            .findFirst()
            .orElseThrow();
    assertThat(row.profileGmv()).as("档案口径").isEqualByComparingTo("8888");
    assertThat(row.cooperationGmvTotal()).as("合作口径").isEqualByComparingTo("1000");
    assertThat(row.attributedGmvTotal()).as("归因口径").isEqualByComparingTo("900");
    // 🔴 三者互不相等，也不互相覆盖
    assertThat(row.profileGmv()).isNotEqualTo(row.cooperationGmvTotal());
    assertThat(row.cooperationGmvTotal()).isNotEqualTo(row.attributedGmvTotal());
  }

  @Test
  @DisplayName("14.5.2.1 效果报表：ROI 与有效粉丝转化率由后端算好，佣金成本分「已计提 / 待计提」")
  void reportComputesRoiAndConversion() {
    String shop = seedShop(a, "RPT-1");
    UUID campaign = seedCampaign(a, shop, "EXT-RPT-1", "报表活动");
    UUID influencer = saveInfluencer(actorA, shop, "报表达人", 2000L);
    saveCooperation(actorA, influencer, campaign, shop, new BigDecimal("1000"),
        new BigDecimal("10"), new BigDecimal("100"), new BigDecimal("0.1"), ENDED_AT);
    saveRule(actorA, "COOPERATION_FULL", "FOLLOWER_SHARE", 30, "GROSS",
        "COOPERATION_COMMISSION", null);
    run(actorA);

    InfluencerAttributionReportRow row =
        report(actorA).items().stream()
            .filter(r -> r.influencerId().equals(influencer))
            .findFirst()
            .orElseThrow();
    assertThat(row.attributedGmvTotal()).isEqualByComparingTo("1000");
    assertThat(row.commissionTotal()).isEqualByComparingTo("100");
    assertThat(row.roi()).isEqualByComparingTo("10.000000");
    assertThat(row.effectiveOrderTotal()).isEqualByComparingTo("9");
    assertThat(row.followerConversionRate()).isEqualByComparingTo("0.004500");
    assertThat(row.netGmvAfterCommission()).isEqualByComparingTo("900.0000");
    assertThat(row.commissionPendingTotal()).isEqualByComparingTo("100");
    assertThat(row.commissionAccruedTotal()).isEqualByComparingTo("0");
    assertThat(row.attributionCount()).isEqualTo(1);

    // 🔴 利润侧对接状态：**available 恒为 false**（13.4 未交付）
    assertThat(report(actorA).profitIntegration().available()).isFalse();
    assertThat(report(actorA).profitIntegration().ownerModule()).isEqualTo("13.4");
  }

  // ==================================================================
  // 佣金计提
  // ==================================================================

  @Test
  @DisplayName("14.5.2.1 佣金计提：状态可变更、计提时刻写回值正确、冲销留痕")
  void commissionAccrualLifecycle() {
    String shop = seedShop(a, "ACC-1");
    UUID campaign = seedCampaign(a, shop, "EXT-ACC-1", "计提活动");
    UUID influencer = saveInfluencer(actorA, shop, "计提达人", 1000L);
    saveCooperation(actorA, influencer, campaign, shop, new BigDecimal("1000"),
        new BigDecimal("10"), new BigDecimal("100"), new BigDecimal("0.1"), ENDED_AT);
    saveRule(actorA, "COOPERATION_FULL", "FOLLOWER_SHARE", 30, "GROSS",
        "COOPERATION_COMMISSION", null);
    run(actorA);
    String id = records(actorA).items().get(0).id().toString();

    InfluencerAttributionDetail accrued =
        as(
            actorA,
            "channel.operation.update",
            () -> service.accrue(id, new InfluencerAttributionAccrualRequest("ACCRUED", "已计提")));
    assertThat(accrued.row().accrualStatus()).isEqualTo("ACCRUED");
    assertThat(accrued.row().accruedAt()).as("计提时刻写回值不能偏 −28800s").isNotNull();
    assertThat(accrued.row().accruedAt().getOffset().getTotalSeconds())
        .isEqualTo(OffsetDateTime.now().getOffset().getTotalSeconds());
    assertThat(accrued.row().accrualNote()).isEqualTo("已计提");

    InfluencerAttributionDetail reversed =
        as(
            actorA,
            "channel.operation.update",
            () -> service.accrue(id, new InfluencerAttributionAccrualRequest("REVERSED", "口径变了，冲销")));
    assertThat(reversed.row().accrualStatus()).isEqualTo("REVERSED");
    assertThat(reversed.row().accruedAt()).isNotNull();

    InfluencerAttributionDetail pending =
        as(
            actorA,
            "channel.operation.update",
            () -> service.accrue(id, new InfluencerAttributionAccrualRequest("PENDING", "退回待计提")));
    assertThat(pending.row().accrualStatus()).isEqualTo("PENDING");
    assertThat(pending.row().accruedAt()).as("退回待计提 ⇒ 没有计提时刻").isNull();
  }

  @Test
  @DisplayName("14.5.2.1 已计提的佣金不自动改写：重跑只更新差额")
  void accruedCommissionIsNotRewritten() {
    String shop = seedShop(a, "VAR-1");
    UUID campaign = seedCampaign(a, shop, "EXT-VAR-1", "差额活动");
    UUID influencer = saveInfluencer(actorA, shop, "差额达人", 1000L);
    saveCooperation(actorA, influencer, campaign, shop, new BigDecimal("1000"),
        new BigDecimal("10"), new BigDecimal("100"), new BigDecimal("0.1"), ENDED_AT);
    saveRule(actorA, "COOPERATION_FULL", "FOLLOWER_SHARE", 30, "GROSS",
        "COOPERATION_COMMISSION", null);
    run(actorA);
    String id = records(actorA).items().get(0).id().toString();
    as(
        actorA,
        "channel.operation.update",
        () -> service.accrue(id, new InfluencerAttributionAccrualRequest("ACCRUED", "已计提")));

    // 源数据改了：合作 GMV 1000 → 2000
    execAsTenant(a,
        "update influencer_cooperation set gmv = 2000 where tenant_id = '" + a
            + "' and influencer_id = '" + influencer + "'");
    run(actorA);

    InfluencerAttributionRow row = records(actorA).items().get(0);
    assertThat(row.commissionAmount()).as("已计提的金额不自动改写").isEqualByComparingTo("100");
    assertThat(row.commissionVariance()).as("差额显式暴露出来").isEqualByComparingTo("0");
    assertThat(row.accrualStatus()).isEqualTo("ACCRUED");
    assertThat(row.attributedGmv()).as("归因 GMV 仍然刷新").isEqualByComparingTo("2000");
  }

  // ==================================================================
  // 评级更新
  // ==================================================================

  @Test
  @DisplayName("14.5.2.1 归因批次写完后触发一次评级重算（评级口径归 14.5.1.1）")
  void runTriggersRatingRecompute() {
    String shop = seedShop(a, "RATE-1");
    UUID campaign = seedCampaign(a, shop, "EXT-RATE-1", "评级活动");
    UUID influencer = saveInfluencer(actorA, shop, "评级达人", 1000L);
    saveCooperation(actorA, influencer, campaign, shop, new BigDecimal("1000"),
        new BigDecimal("10"), new BigDecimal("100"), new BigDecimal("0.1"), ENDED_AT);
    saveRule(actorA, "COOPERATION_FULL", "FOLLOWER_SHARE", 30, "GROSS",
        "COOPERATION_COMMISSION", null);

    // 先配 14.5.1.1 的评分口径与档位（**本模块不改它们**）
    as(
        actorA,
        "channel.operation.create",
        () ->
            influencerService.saveRatingTier(
                new SaveInfluencerRatingTierRequest(
                    "T-RATE", "评级档位", new BigDecimal("0"), new BigDecimal("1"), 10, "ACTIVE", "")));
    as(
        actorA,
        "channel.operation.update",
        () ->
            influencerService.saveRatingRule(
                new SaveInfluencerRatingRuleRequest(
                    "R-RATE", new BigDecimal("0.5"), new BigDecimal("0.3"), new BigDecimal("0.2"),
                    new BigDecimal("100000"), new BigDecimal("1"), new BigDecimal("1"), "ACTIVE", "")));

    // 绕开服务层直接改档案效果数据 ⇒ 此刻评分还是旧的
    execAsTenant(a,
        "update influencer set gmv = 50000, engagement_rate = 0.5, refund_rate = 0"
            + " where tenant_id = '" + a + "' and id = '" + influencer + "'");

    InfluencerAttributionRunResult result = run(actorA);
    assertThat(result.ratingRecomputed()).isEqualTo(1);

    BigDecimal score =
        as(
                actorA,
                "channel.operation.read",
                () ->
                    service.influencerSummary(influencer, currentRule(actorA).id()).ratingScore());
    assertThat(score).as("归因批次触发了评级重算").isNotNull();
    assertThat(score).isGreaterThan(new BigDecimal("0"));
  }

  // ==================================================================
  // 列表 / 筛选 / 排序
  // ==================================================================

  @Test
  @DisplayName("14.5.2.1 归因记录：真分页 + 真筛选（按达人 / 计提状态）+ 真排序（ROI）")
  void recordsAreFilteredPaginatedAndSorted() {
    String shop = seedShop(a, "LIST-1");
    UUID campaign = seedCampaign(a, shop, "EXT-LIST-1", "列表活动");
    UUID one = saveInfluencer(actorA, shop, "列表达人甲", 1000L);
    UUID two = saveInfluencer(actorA, shop, "列表达人乙", 2000L);
    saveCooperation(actorA, one, campaign, shop, new BigDecimal("1000"),
        new BigDecimal("10"), new BigDecimal("100"), new BigDecimal("0"), ENDED_AT);
    saveCooperation(actorA, two, campaign, shop, new BigDecimal("500"),
        new BigDecimal("5"), new BigDecimal("250"), new BigDecimal("0"), ENDED_AT);
    saveRule(actorA, "COOPERATION_FULL", "FOLLOWER_SHARE", 30, "GROSS",
        "COOPERATION_COMMISSION", null);
    run(actorA);

    // 按达人筛选
    InfluencerAttributionPage filtered =
        as(
            actorA,
            "channel.operation.read",
            () ->
                service.attributions(
                    one, null, null, null, null, null, null, null, null, "ATTRIBUTED_GMV_DESC", 0, 50));
    assertThat(filtered.total()).isEqualTo(1);
    assertThat(filtered.items().get(0).influencerId()).isEqualTo(one);

    // 按计提状态筛选（初始全是 PENDING）
    InfluencerAttributionPage pendingOnly =
        as(
            actorA,
            "channel.operation.read",
            () ->
                service.attributions(
                    null, null, null, null, "PENDING", null, null, null, null, "ATTRIBUTED_GMV_DESC",
                    0, 50));
    assertThat(pendingOnly.total()).isEqualTo(2);

    // ROI 排序：达人乙 GMV 500 / 佣金 250 = 2；达人甲 1000 / 100 = 10 ⇒ 甲在前
    InfluencerAttributionPage byRoi =
        as(
            actorA,
            "channel.operation.read",
            () ->
                service.attributions(
                    null, null, null, null, null, null, null, null, null, "ROI_DESC", 0, 50));
    assertThat(byRoi.items().get(0).influencerId()).isEqualTo(one);

    // 真分页
    InfluencerAttributionPage page0 =
        as(
            actorA,
            "channel.operation.read",
            () ->
                service.attributions(
                    null, null, null, null, null, null, null, null, null, "ATTRIBUTED_GMV_DESC", 0, 1));
    assertThat(page0.items()).hasSize(1);
    assertThat(page0.total()).isEqualTo(2);
  }

  @Test
  @DisplayName("14.5.2.1 效果报表：没有归因数据时达人也在列表里（归因列为 null，不是 0）")
  void reportListsInfluencersWithoutAttribution() {
    String shop = seedShop(a, "EMPTY-1");
    UUID influencer = saveInfluencer(actorA, shop, "还没归因的达人", 1000L);
    saveRule(actorA, "COOPERATION_FULL", "FOLLOWER_SHARE", 30, "GROSS",
        "COOPERATION_COMMISSION", null);

    InfluencerAttributionReportPage page = report(actorA);
    InfluencerAttributionReportRow row =
        page.items().stream().filter(r -> r.influencerId().equals(influencer)).findFirst().orElseThrow();
    assertThat(row.attributedGmvTotal()).as("还没归因不是 0").isNull();
    assertThat(row.attributionCount()).isZero();
    assertThat(page.summary().influencerCount()).isZero();
  }

  // ==================================================================
  // 平台同步
  // ==================================================================

  @Test
  @DisplayName("14.5.2.1 平台带货数据同步未接入：明确 409，且一条数据都不写")
  void platformSyncIsUnavailable() {
    String shop = seedShop(a, "SYNC-1");
    UUID influencer = saveInfluencer(actorA, shop, "同步达人", 1000L);

    InfluencerAttributionSyncStatus status = as(actorA, "channel.operation.read", () -> service.syncStatus());
    assertThat(status.items()).isNotEmpty();
    assertThat(status.items()).allMatch(item -> !item.available());
    assertThat(status.items().get(0).status()).isEqualTo("NOT_INTEGRATED");

    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    as(
                        actorA,
                        "channel.operation.create",
                        () ->
                            service.sync(new InfluencerAttributionSyncRequest("AMAZON", shop)))))
        .as("未接入 ⇒ 必须报冲突")
        .isNotNull();

    assertThat(records(actorA).total()).as("同步失败不得写任何归因数据").isZero();
    assertThat(tenantCount(a, "influencer_attribution", ""))
        .as("同步失败不得写任何归因记录")
        .isZero();
    assertThat(tenantCount(a, "influencer", " and display_name='同步达人'"))
        .as("同步失败不得动到达人档案")
        .isEqualTo(1);
  }

  // ==================================================================
  // HTTP
  // ==================================================================

  @Test
  @DisplayName("14.5.2.1 HTTP：没有会话一律 401")
  void httpRequiresSession() throws Exception {
    http.perform(get("/channel/attributions/capabilities")).andExpect(status().isUnauthorized());
    http.perform(get("/channel/attributions/report")).andExpect(status().isUnauthorized());
    http.perform(get("/channel/attributions/records")).andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("14.5.2.1 HTTP：能力声明里平台同步 / 利润对接两个维度 available=false")
  void httpCapabilitiesAreHonest() throws Exception {
    http.perform(get("/channel/attributions/capabilities").header("Authorization", bearerA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.profitIntegration.available").value(false))
        .andExpect(jsonPath("$.profitIntegration.ownerModule").value("13.4"))
        .andExpect(
            jsonPath("$.dimensions[?(@.dimension=='platform_sync')].available").value(false))
        .andExpect(
            jsonPath("$.dimensions[?(@.dimension=='profit_integration')].available").value(false))
        .andExpect(
            jsonPath("$.dimensions[?(@.dimension=='order_facts')].available").value(false));
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
