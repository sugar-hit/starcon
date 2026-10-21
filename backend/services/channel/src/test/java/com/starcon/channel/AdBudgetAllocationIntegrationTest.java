package com.starcon.channel;

import static com.starcon.channel.AdBudgetAllocationModels.*;
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
 * 14.2.2.1 预算分配。
 *
 * <p>本测试类钉住本模块最容易做丢的几件事：
 *
 * <ol>
 *   <li><b>多租户三断言</b>（AGENTS.md 硬性要求，缺一不可）：两租户互不可见、伪造 tenant header 无效、
 *       连接池归还后不串租户
 *   <li><b>🔴 分配额之和严格等于预算总额</b>：少分是预算凭空消失，超发是超预算 ——
 *       断言的是**从库里读回**的逐条分配额之和，不是服务层算出来的中间值
 *   <li><b>🔴 利润维度未交付</b>：能力声明里 available=false + ownerModule；
 *       利润权重 &gt; 0 在服务层被拒；<b>绕服务层直接写 SQL 也被 DB 的 CHECK 拒</b>
 *   <li><b>🔴 闲置预算与预算受限是两种相反的事实</b>：能分开识别
 *   <li><b>🔴 没参与分配的活动要看得见</b>：币种不一致 / 没有评分依据都逐条列明原因
 *   <li><b>🔴 执行任务逐条留痕</b>：被护栏拒绝的那几条照样有记录（不是静默截断），
 *       且改动只落本地并标记「待回写平台」
 *   <li><b>时间字段写回值正确</b>（AGENTS.md 要求断言写回值，不能只断言没抛异常）
 *   <li><b>真分页真筛选</b>；跨租户与不存在返回逐字相同的 404
 * </ol>
 *
 * <p><b>时刻一律用固定的 {@link OffsetDateTime} 钉死</b>，不用 {@code now()}：用墙钟会让
 * 「这次写入落在哪个断言分支上」成为一个 flaky 断言。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AdBudgetAllocationIntegrationTest {

  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID user = UUID.randomUUID();
  static HttpServer identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  /** 🔴 每个用例一对**全新的租户**（共用租户会让前一个用例的数据被后一个用例数进去）。 */
  UUID a, b;
  AccessService.Access actorA, actorB;
  String bearerA;

  /** 🔴 钉死的时刻与窗口。 */
  static final OffsetDateTime DATA_AS_OF = OffsetDateTime.parse("2026-10-03T09:00:00+08:00");
  static final OffsetDateTime STARTED_AT = OffsetDateTime.parse("2026-10-03T08:00:00+08:00");
  static final OffsetDateTime FINISHED_AT = OffsetDateTime.parse("2026-10-03T09:05:00+08:00");
  static final LocalDate DAY_1 = LocalDate.of(2026, 10, 1);
  static final LocalDate DAY_2 = LocalDate.of(2026, 10, 2);

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

  @Autowired AdBudgetAllocationService service;
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

  /** 播种一条 2.1.2.1 的平台默认映射（全局码表）：归一口径的真相在那里。 */
  static void seedPlatformMapping(UUID platformId, String external, String internal) {
    exec("insert into platform_enum_mapping (id,platform_id,enum_kind,external_value,internal_value)"
        + " values ('" + UUID.randomUUID() + "','" + platformId + "','AD_TYPE','" + external
        + "','" + internal + "')"
        + " on conflict (platform_id,enum_kind,external_value) do nothing");
  }

  /** 通过**上游 14.1.2.2** 登记一个广告类型档案。 */
  UUID saveProfile(AccessService.Access who, UUID platformId, String external, String internal) {
    return as(who, "channel.operation.create",
        () -> adTypes.saveProfile(new AdTypeDiffModels.SaveProfileRequest(
            null, platformId, external, internal, external + " 展示名", "CPC", "SALES",
            true, true, true, true, false,
            "UNMAPPED".equals(internal) ? "UNMAPPED" : "PLATFORM_DEFAULT", "测试登记")))
        .id();
  }

  /** 通过**上游 14.1.1.1** 登记一条广告活动与它的每日指标。 */
  void importCampaign(
      AccessService.Access who,
      UUID platformId,
      String shop,
      UUID profileId,
      String externalId,
      String name,
      String status,
      String currency,
      String budget,
      List<AdCampaignModels.ImportDailyMetric> metrics) {
    as(who, "channel.operation.create",
        () -> campaigns.importCampaigns(new AdCampaignModels.ImportRequest(
            platformId, shop,
            new AdCampaignModels.ImportSyncRun("SUCCESS", DATA_AS_OF, STARTED_AT, FINISHED_AT, "", "batch-1"),
            List.of(new AdCampaignModels.ImportCampaign(
                externalId, name, profileId, status, currency,
                budget == null ? null : new BigDecimal(budget),
                null, null, metrics, "测试登记")))));
  }

  static AdCampaignModels.ImportDailyMetric metric(
      LocalDate date, String impressions, String clicks, String orders, String spend, String sales) {
    return new AdCampaignModels.ImportDailyMetric(
        date, new BigDecimal(impressions), new BigDecimal(clicks), new BigDecimal(orders),
        new BigDecimal(spend), new BigDecimal(sales));
  }

  /** 一条规则的权重位（销售目标 + ROAS；利润位恒为 0）。 */
  static List<SaveBudgetWeight> weights() {
    return List.of(
        new SaveBudgetWeight("SALES_TARGET", new BigDecimal("1"), "销售目标权重"),
        new SaveBudgetWeight("ROAS", new BigDecimal("1"), "历史 ROAS 权重"),
        new SaveBudgetWeight("PROFIT", BigDecimal.ZERO, "利润权重位：13.4 未交付，当前为 0"));
  }

  AdBudgetRuleDetail saveRule(AccessService.Access who, String code, List<SaveBudgetTarget> targets) {
    return as(who, "channel.operation.create",
        () -> service.saveRule(new SaveBudgetRuleRequest(
            code, "规则 " + code, "USD", null, null, null, null, null, null,
            weights(), targets, "测试登记")));
  }

  AdBudgetPoolRow savePool(AccessService.Access who, UUID ruleId, String total) {
    return as(who, "channel.operation.create",
        () -> service.savePool(new SaveBudgetPoolRequest(
            "POOL-" + UUID.randomUUID(), "测试预算池", "USD", new BigDecimal(total),
            DAY_1, DAY_2, ruleId, "测试登记")));
  }

  /**
   * 播种一条标准的四活动场景：
   *
   * <ul>
   *   <li>{@code A} 预算受限 + ROAS 可比：日预算 50，两天消耗 200、销售额 1000
   *       ⇒ 日均消耗 100、利用率 2.0、ROAS 5
   *   <li>{@code B} 闲置：日预算 300，两天消耗 0 ⇒ 利用率 0
   *   <li>{@code C} 没有评分依据：既没有销售目标，也没有可比的 ROAS
   *   <li>{@code D} 币种不一致（EUR）⇒ 不参与分配
   * </ul>
   */
  record Fixture(UUID profileId, UUID campaignA, UUID campaignB, UUID campaignC, UUID campaignD) {}

  Fixture seedScenarios(AccessService.Access who, String shopCode) {
    UUID platformId = AMAZON;
    seedPlatformMapping(platformId, "sponsored_products", "AUTO_DISCOVERY");
    String shop = seedShop(who.tenantId(), platformId, shopCode);
    UUID profile = saveProfile(who, platformId, "sponsored_products", "AUTO_DISCOVERY");

    importCampaign(who, platformId, shop, profile, "EXT-A", "活动 A（受限高 ROAS）", "ENABLED", "USD",
        "50.0000",
        List.of(
            metric(DAY_1, "1000", "50", "5", "100.0000", "500.0000"),
            metric(DAY_2, "1000", "50", "5", "100.0000", "500.0000")));
    importCampaign(who, platformId, shop, profile, "EXT-B", "活动 B（闲置）", "ENABLED", "USD",
        "300.0000",
        List.of(
            metric(DAY_1, "100", "5", "0", "0.0000", "0.0000"),
            metric(DAY_2, "100", "5", "0", "0.0000", "0.0000")));
    importCampaign(who, platformId, shop, profile, "EXT-C", "活动 C（无依据）", "ENABLED", "USD",
        "10.0000",
        List.of(metric(DAY_1, "10", "1", "0", "0.0000", "0.0000")));
    importCampaign(who, platformId, shop, profile, "EXT-D", "活动 D（币种不同）", "ENABLED", "EUR",
        "10.0000",
        List.of(metric(DAY_1, "10", "1", "0", "0.0000", "0.0000")));

    UUID idA = campaignIdOf(who.tenantId(), "EXT-A");
    UUID idB = campaignIdOf(who.tenantId(), "EXT-B");
    UUID idC = campaignIdOf(who.tenantId(), "EXT-C");
    UUID idD = campaignIdOf(who.tenantId(), "EXT-D");
    return new Fixture(profile, idA, idB, idC, idD);
  }

  UUID campaignIdOf(UUID tenant, String externalId) {
    java.sql.Connection conn = null;
    try {
      conn = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
      try (var ps = conn.prepareStatement(
          "select id from ad_campaign where tenant_id = ? and external_campaign_id = ?")) {
        ps.setObject(1, tenant);
        ps.setString(2, externalId);
        try (var rs = ps.executeQuery()) {
          if (rs.next()) {
            return rs.getObject("id", UUID.class);
          }
        }
      }
    } catch (SQLException e) {
      throw new RuntimeException(e);
    } finally {
      if (conn != null) {
        try {
          conn.close();
        } catch (SQLException ignored) {
          // 关闭失败不影响断言
        }
      }
    }
    throw new IllegalStateException("活动不存在：" + externalId);
  }

  String currentBudgetOf(UUID campaignId) {
    java.sql.Connection conn = null;
    try {
      conn = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
      try (var ps = conn.prepareStatement(
          "select daily_budget, pending_platform_writeback from ad_campaign where id = ?")) {
        ps.setObject(1, campaignId);
        try (var rs = ps.executeQuery()) {
          if (rs.next()) {
            BigDecimal budget = rs.getBigDecimal("daily_budget");
            boolean pending = rs.getBoolean("pending_platform_writeback");
            return budget == null ? "null/" + pending : budget.toPlainString() + "/" + pending;
          }
        }
      }
    } catch (SQLException e) {
      throw new RuntimeException(e);
    } finally {
      if (conn != null) {
        try {
          conn.close();
        } catch (SQLException ignored) {
          // 关闭失败不影响断言
        }
      }
    }
    return "缺失";
  }

  // ==================================================================
  // 1. 能力声明：利润维度必须显式未交付
  // ==================================================================

  @Test
  @DisplayName("14.2.2.1 能力声明：利润加权 / 汇率折算 / 平台回写显式 available=false + ownerModule")
  void capabilitiesDeclareUndeliveredParts() {
    var caps = as(actorA, "channel.operation.read", () -> service.capabilities());
    Map<String, AdBudgetCapabilityDimension> dims =
        caps.dimensions().stream()
            .collect(java.util.stream.Collectors.toMap(AdBudgetCapabilityDimension::dimension, d -> d));

    assertThat(dims.get("allocation_plan").available()).isTrue();
    assertThat(dims.get("rebalance_suggestion").available()).isTrue();
    assertThat(dims.get("execution_task").available()).isTrue();
    assertThat(dims.get("budget_constrained_and_idle").available()).isTrue();

    // 🔴 13.4 利润未交付 ⇒ 显式 false + ownerModule + 影响说明
    assertThat(dims.get("profit_weight").available()).isFalse();
    assertThat(dims.get("profit_weight").ownerModule()).contains("13.4");
    assertThat(dims.get("profit_weight").impact()).contains("销售目标").contains("ROAS");

    assertThat(dims.get("fx_conversion").available()).isFalse();
    assertThat(dims.get("fx_conversion").ownerModule()).contains("5.2.2.1");
    assertThat(dims.get("platform_writeback").available()).isFalse();

    // 🔴 护栏上限必须给出来，界面上要能写明天花板
    assertThat(caps.guardrails().maxSingleBudgetDelta()).isEqualByComparingTo("5000");
    assertThat(caps.guardrails().maxTotalBudgetDelta()).isEqualByComparingTo("20000");
    assertThat(caps.guardrails().maxCampaignBudget()).isEqualByComparingTo("100000");
    assertThat(caps.guardrails().maxCampaignsPerTask()).isEqualTo(200);

    // 🔴 利润不可用的说明必须写在返回值里（界面要让人看见这个状态）
    assertThat(caps.profitNote()).contains("13.4").contains("假利润");
  }

  // ==================================================================
  // 2. 规则：利润权重位可插拔，但当前不可用
  // ==================================================================

  @Test
  @DisplayName("14.2.2.1 规则：利润权重位在，但非零利润权重被拒（13.4 未交付）")
  void profitWeightIsRejectedButSlotExists() {
    assertThatThrownBy(() ->
            as(actorA, "channel.operation.create",
                () -> service.saveRule(new SaveBudgetRuleRequest(
                    "R-" + UUID.randomUUID(), "带利润权重", "USD", null, null, null, null, null, null,
                    List.of(
                        new SaveBudgetWeight("SALES_TARGET", BigDecimal.ONE, ""),
                        new SaveBudgetWeight("PROFIT", new BigDecimal("2"), "")),
                    List.of(), ""))))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
        .hasMessageContaining("13.4")
        .hasMessageContaining("利润");

    // 🔴 全 0 权重的规则建不出来（算出来的分配额全是 0，留着只会让人以为它在生效）
    assertThatThrownBy(() ->
            as(actorA, "channel.operation.create",
                () -> service.saveRule(new SaveBudgetRuleRequest(
                    "R-" + UUID.randomUUID(), "全零权重", "USD", null, null, null, null, null, null,
                    List.of(new SaveBudgetWeight("SALES_TARGET", BigDecimal.ZERO, "")),
                    List.of(), ""))))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
        .hasMessageContaining("至少一个权重大于 0");
  }

  @Test
  @DisplayName("14.2.2.1 DB 层兜底：绕服务层直接写非零利润权重也被 CHECK 拒")
  void dbRejectsNonZeroProfitWeight() {
    Fixture f = seedScenarios(actorA, "PROFIT-GATE-AMZ");
    var rule = saveRule(actorA, "R-" + UUID.randomUUID(), List.of(
        new SaveBudgetTarget(f.campaignA(), new BigDecimal("1000"), null, null, ""),
        new SaveBudgetTarget(f.campaignB(), new BigDecimal("500"), null, null, "")));

    // 🔴 绕过服务层直接写 SQL：CHECK 必须把它拒掉
    assertThatThrownBy(() ->
            execAsTenant(a,
                "insert into ad_budget_rule_weight (tenant_id,id,rule_id,weight_source,weight,note,"
                    + " position,created_at) values ('" + a + "','" + UUID.randomUUID() + "','"
                    + rule.rule().id() + "','PROFIT',5,'绕过服务层',0,now())"))
        .hasMessageContaining("ad_budget_rule_weight");
  }

  @Test
  @DisplayName("14.2.2.1 规则与预算池：币种不一致的池被拒；规则详情看得到权重位与销售目标")
  void ruleAndPoolBasics() {
    Fixture f = seedScenarios(actorA, "BASIC-AMZ");
    var rule = saveRule(actorA, "R-" + UUID.randomUUID(), List.of(
        new SaveBudgetTarget(f.campaignA(), new BigDecimal("1000"), null, null, ""),
        new SaveBudgetTarget(f.campaignB(), new BigDecimal("500"), null, null, "")));

    assertThat(rule.rule().currency()).isEqualTo("USD");
    assertThat(rule.rule().targetCount()).isEqualTo(2);
    assertThat(rule.rule().weights()).hasSize(3);
    assertThat(rule.rule().weights())
        .filteredOn(w -> w.weightSource().equals("PROFIT"))
        .singleElement()
        .satisfies(w -> assertThat(w.weight()).isEqualByComparingTo("0"));

    // 🔴 池的币种必须与规则一致（跨币种要汇率，而汇率没交付）
    assertThatThrownBy(() ->
            as(actorA, "channel.operation.create",
                () -> service.savePool(new SaveBudgetPoolRequest(
                    "POOL-" + UUID.randomUUID(), "欧元池", "EUR", new BigDecimal("1000"),
                    DAY_1, DAY_2, rule.rule().id(), ""))))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
        .hasMessageContaining("币种必须与分配规则一致");

    var pool = savePool(actorA, rule.rule().id(), "1000");
    assertThat(pool.totalAmount()).isEqualByComparingTo("1000.0000");
    assertThat(pool.currency()).isEqualTo("USD");
  }

  // ==================================================================
  // 3. 分配方案：分配额之和严格等于预算总额
  // ==================================================================

  @Test
  @DisplayName("14.2.2.1 分配额之和严格等于预算总额（从库里读回复核，不是看服务层的中间值）")
  void allocationSumsExactlyToPoolTotal() {
    Fixture f = seedScenarios(actorA, "SUM-AMZ");
    var rule = saveRule(actorA, "R-" + UUID.randomUUID(), List.of(
        new SaveBudgetTarget(f.campaignA(), new BigDecimal("1000"), null, null, ""),
        new SaveBudgetTarget(f.campaignB(), new BigDecimal("500"), null, null, "")));
    var pool = savePool(actorA, rule.rule().id(), "1000");

    var plan =
        as(actorA, "channel.operation.create",
            () -> service.computePlan(new ComputeBudgetPlanRequest(
                pool.id(), DAY_1, DAY_2, "测试计算")));

    // 🔴 服务层返回的逐条分配额之和 == 池总额
    BigDecimal sum =
        plan.lines().stream()
            .map(AdBudgetPlanLineRow::allocatedBudget)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    assertThat(sum).isEqualByComparingTo("1000");
    assertThat(plan.plan().allocatedAmount()).isEqualByComparingTo("1000");
    assertThat(plan.plan().unallocatedAmount()).isEqualByComparingTo("0");

    // 🔴 再从库里读一遍逐条分配额之和（服务的返回值可能自己算错，库里不会）
    assertThat(planAllocatedSumInDb(plan.plan().id())).isEqualByComparingTo("1000");

    // 🔴 时间字段断言**写回值**：方案的计算时刻也要逐字往返
    assertThat(plan.plan().computedAt()).isNotNull();
    assertThat(plan.plan().computedAt().toInstant())
        .isEqualTo(planComputedAtInDb(plan.plan().id()).toInstant());

    // 🔴 权重确实向 ROAS 高的活动倾斜（不是按销售额平均分）
    AdBudgetPlanLineRow lineA = lineOf(plan, "活动 A（受限高 ROAS）");
    AdBudgetPlanLineRow lineB = lineOf(plan, "活动 B（闲置）");
    assertThat(lineA.allocatedBudget()).isEqualByComparingTo("833.3333");
    assertThat(lineB.allocatedBudget()).isEqualByComparingTo("166.6667");
    assertThat(lineA.allocatedBudget()).isGreaterThan(lineB.allocatedBudget());
  }

  // ==================================================================
  // 4. 闲置预算与预算受限能分开识别
  // ==================================================================

  @Test
  @DisplayName("14.2.2.1 预算受限与闲置预算是两种相反的事实，能分开识别")
  void constrainedAndIdleAreDistinguished() {
    Fixture f = seedScenarios(actorA, "IDLE-AMZ");
    var rule = saveRule(actorA, "R-" + UUID.randomUUID(), List.of(
        new SaveBudgetTarget(f.campaignA(), new BigDecimal("1000"), null, null, ""),
        new SaveBudgetTarget(f.campaignB(), new BigDecimal("500"), null, null, "")));
    var pool = savePool(actorA, rule.rule().id(), "1000");

    var plan =
        as(actorA, "channel.operation.create",
            () -> service.computePlan(new ComputeBudgetPlanRequest(
                pool.id(), DAY_1, DAY_2, "测试计算")));

    AdBudgetPlanLineRow a = lineOf(plan, "活动 A（受限高 ROAS）");
    AdBudgetPlanLineRow b = lineOf(plan, "活动 B（闲置）");

    // A：日均消耗 100 / 日预算 50 ⇒ 利用率 2.0 ≥ 0.95 ⇒ 受限
    assertThat(a.utilization()).isEqualByComparingTo("2.000000");
    assertThat(a.dailyAvgSpend()).isEqualByComparingTo("100.0000");
    assertThat(a.budgetConstrained()).isTrue();
    assertThat(a.idleBudget()).isFalse();
    assertThat(a.recommendation()).isEqualTo("INCREASE");
    assertThat(a.reason()).contains("预算受限");

    // B：日均消耗 0 / 日预算 300 ⇒ 利用率 0 < 0.30 ⇒ 闲置
    assertThat(b.utilization()).isEqualByComparingTo("0.000000");
    assertThat(b.budgetConstrained()).isFalse();
    assertThat(b.idleBudget()).isTrue();
    assertThat(b.recommendation()).isEqualTo("DECREASE");
    assertThat(b.reason()).contains("闲置预算");

    // 🔴 汇总里的两个数含义不同，不能混
    assertThat(plan.plan().idleBudget()).isEqualByComparingTo("300");
    assertThat(plan.plan().constrainedShortfall()).isEqualByComparingTo("50");
    assertThat(plan.plan().releasedAmount()).isEqualByComparingTo("133.3333");
  }

  // ==================================================================
  // 5. 没参与分配的活动要看得见
  // ==================================================================

  @Test
  @DisplayName("14.2.2.1 未参与分配的活动逐条列明原因（币种不一致 / 没有评分依据）")
  void skippedCampaignsAreVisibleWithReasons() {
    Fixture f = seedScenarios(actorA, "SKIP-AMZ");
    var rule = saveRule(actorA, "R-" + UUID.randomUUID(), List.of(
        new SaveBudgetTarget(f.campaignA(), new BigDecimal("1000"), null, null, ""),
        new SaveBudgetTarget(f.campaignB(), new BigDecimal("500"), null, null, "")));
    var pool = savePool(actorA, rule.rule().id(), "1000");

    var plan =
        as(actorA, "channel.operation.create",
            () -> service.computePlan(new ComputeBudgetPlanRequest(
                pool.id(), DAY_1, DAY_2, "测试计算")));

    assertThat(plan.skipped()).hasSize(2);
    AdBudgetSkippedRow currency = plan.skipped().stream()
        .filter(s -> s.skipReason().equals("CURRENCY_MISMATCH")).findFirst().orElseThrow();
    assertThat(currency.campaignName()).isEqualTo("活动 D（币种不同）");
    assertThat(currency.reason()).contains("不做折算");

    AdBudgetSkippedRow noBasis = plan.skipped().stream()
        .filter(s -> s.skipReason().equals("NO_SCORE_BASIS")).findFirst().orElseThrow();
    assertThat(noBasis.campaignName()).isEqualTo("活动 C（无依据）");
    assertThat(noBasis.reason()).contains("没有可用的分配依据");

    // 🔴 未参与分配的活动**不在分配明细里**（它的现有预算不受本次分配影响）
    assertThat(plan.lines()).extracting(AdBudgetPlanLineRow::campaignName)
        .containsExactlyInAnyOrder("活动 A（受限高 ROAS）", "活动 B（闲置）");
  }

  // ==================================================================
  // 6. 再平衡建议
  // ==================================================================

  @Test
  @DisplayName("14.2.2.1 再平衡建议把闲置的钱挪给受限的活动，且总额不变")
  void rebalanceMovesIdleBudgetToConstrainedCampaign() {
    Fixture f = seedScenarios(actorA, "REB-AMZ");
    var rule = saveRule(actorA, "R-" + UUID.randomUUID(), List.of(
        new SaveBudgetTarget(f.campaignA(), new BigDecimal("1000"), null, null, ""),
        new SaveBudgetTarget(f.campaignB(), new BigDecimal("500"), null, null, "")));
    var pool = savePool(actorA, rule.rule().id(), "1000");

    var plan =
        as(actorA, "channel.operation.create",
            () -> service.computePlan(new ComputeBudgetPlanRequest(
                pool.id(), DAY_1, DAY_2, "测试计算")));

    assertThat(plan.rebalances()).hasSize(1);
    AdBudgetRebalanceRow rb = plan.rebalances().get(0);
    assertThat(rb.fromCampaignName()).isEqualTo("活动 B（闲置）");
    assertThat(rb.toCampaignName()).isEqualTo("活动 A（受限高 ROAS）");
    // A 的缺口 = 日均消耗 100 − 日预算 50 = 50
    assertThat(rb.amount()).isEqualByComparingTo("50");
    assertThat(rb.reason()).contains("闲置预算").contains("预算受限");
    assertThat(rb.status()).isEqualTo("PROPOSED");
  }

  // ==================================================================
  // 7. 执行任务：逐条落预算 + 逐条留痕
  // ==================================================================

  @Test
  @DisplayName("14.2.2.1 执行任务逐条落预算，改动只落本地并标记待回写平台")
  void executionTaskAppliesBudgetsAndMarksPendingWriteback() {
    Fixture f = seedScenarios(actorA, "TASK-AMZ");
    var rule = saveRule(actorA, "R-" + UUID.randomUUID(), List.of(
        new SaveBudgetTarget(f.campaignA(), new BigDecimal("1000"), null, null, ""),
        new SaveBudgetTarget(f.campaignB(), new BigDecimal("500"), null, null, "")));
    var pool = savePool(actorA, rule.rule().id(), "1000");

    var plan =
        as(actorA, "channel.operation.create",
            () -> service.computePlan(new ComputeBudgetPlanRequest(
                pool.id(), DAY_1, DAY_2, "测试计算")));

    var task =
        as(actorA, "channel.operation.create",
            () -> service.createTask(new CreateBudgetTaskRequest(plan.plan().id(), "测试执行")));
    assertThat(task.task().status()).isEqualTo("PENDING");
    assertThat(task.lines()).hasSize(2);

    var applied = as(actorA, "channel.operation.update", () -> service.applyTask(task.task().id().toString()));

    assertThat(applied.task().status()).isEqualTo("APPLIED");
    assertThat(applied.lines()).allMatch(l -> l.result().equals("APPLIED"));

    // 🔴 预算真的改了，而且标记了「待回写平台」
    assertThat(currentBudgetOf(f.campaignA())).isEqualTo("833.3333/true");
    assertThat(currentBudgetOf(f.campaignB())).isEqualTo("166.6667/true");

    // 🔴 时间字段断言**写回值**（AGENTS.md）：偏移被比掉或偏了 −28800s 都要红。
    assertThat(applied.task().appliedAt()).isNotNull();
    assertThat(applied.task().appliedAt().toInstant())
        .isEqualTo(taskAppliedAtInDb(task.task().id()).toInstant());
  }

  @Test
  @DisplayName("14.2.2.1 执行任务被护栏拒绝的条目照样留痕（不是静默截断）")
  void executionTaskRejectsOverGuardrailAndKeepsEvidence() {
    Fixture f = seedScenarios(actorA, "GUARD-AMZ");
    var rule = saveRule(actorA, "R-" + UUID.randomUUID(), List.of(
        new SaveBudgetTarget(f.campaignA(), new BigDecimal("1000"), null, null, ""),
        new SaveBudgetTarget(f.campaignB(), new BigDecimal("500"), null, null, "")));
    // 🔴 池总额放大到 10 万 ⇒ 单条调整额远超单活动上限 5000
    var pool = savePool(actorA, rule.rule().id(), "100000");

    var plan =
        as(actorA, "channel.operation.create",
            () -> service.computePlan(new ComputeBudgetPlanRequest(
                pool.id(), DAY_1, DAY_2, "测试计算")));

    var task =
        as(actorA, "channel.operation.create",
            () -> service.createTask(new CreateBudgetTaskRequest(plan.plan().id(), "测试护栏")));
    var applied = as(actorA, "channel.operation.update", () -> service.applyTask(task.task().id().toString()));

    assertThat(applied.task().status()).isEqualTo("FAILED");
    assertThat(applied.lines()).isNotEmpty();
    assertThat(applied.lines()).allMatch(l -> l.result().equals("REJECTED"));
    assertThat(applied.lines()).allMatch(l -> l.appliedBudget() == null);
    assertThat(applied.lines().get(0).message()).contains("上限");

    // 🔴 被拒的条目没有把预算改掉
    assertThat(currentBudgetOf(f.campaignA())).isEqualTo("50.0000/false");
  }

  // ==================================================================
  // 8. 真分页真筛选 + 404 口径
  // ==================================================================

  @Test
  @DisplayName("14.2.2.1 列表真分页真筛选；跨租户与不存在返回逐字相同的 404")
  void listsArePagedAndFiltered() {
    Fixture f = seedScenarios(actorA, "PAGE-AMZ");
    var rule = saveRule(actorA, "R-" + UUID.randomUUID(), List.of(
        new SaveBudgetTarget(f.campaignA(), new BigDecimal("1000"), null, null, ""),
        new SaveBudgetTarget(f.campaignB(), new BigDecimal("500"), null, null, "")));
    var pool = savePool(actorA, rule.rule().id(), "1000");
    as(actorA, "channel.operation.create",
        () -> service.computePlan(new ComputeBudgetPlanRequest(pool.id(), DAY_1, DAY_2, "")));

    var plans = as(actorA, "channel.operation.read", () -> service.plans(null, null, null, 0, 50));
    assertThat(plans.total()).isEqualTo(1);

    var firstPage = as(actorA, "channel.operation.read", () -> service.plans(null, null, null, 0, 1));
    var secondPage = as(actorA, "channel.operation.read", () -> service.plans(null, null, null, 1, 1));
    assertThat(firstPage.total()).isEqualTo(1);
    assertThat(secondPage.total()).isEqualTo(1);
    assertThat(firstPage.items()).hasSize(1);
    assertThat(secondPage.items()).isEmpty();

    var filtered =
        as(actorA, "channel.operation.read", () -> service.plans("SUPERSEDED", null, null, 0, 50));
    assertThat(filtered.total()).isEqualTo(0);

    // 🔴 先把 id 取出来：租户上下文是**事务内**的，嵌套调用会改写 app.current_tenant
    UUID planAId = planIdOfFirst(actorA);
    var notThere =
        org.assertj.core.api.Assertions.catchThrowable(
            () -> as(actorA, "channel.operation.read", () -> service.plan(UUID.randomUUID().toString())));
    var crossTenant =
        org.assertj.core.api.Assertions.catchThrowable(
            () -> as(actorB, "channel.operation.read", () -> service.plan(planAId.toString())));
    assertThat(notThere).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    assertThat(crossTenant).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    assertThat(crossTenant.getMessage()).isEqualTo(notThere.getMessage());
  }

  UUID planIdOfFirst(AccessService.Access who) {
    var page = as(who, "channel.operation.read", () -> service.plans(null, null, null, 0, 1));
    return page.items().get(0).id();
  }

  // ==================================================================
  // 9. 多租户三断言（AGENTS.md 硬性要求，缺一不可）
  // ==================================================================

  @Test
  @DisplayName("14.2.2.1 断言一：两租户互不可见")
  void tenantsAreIsolated() {
    Fixture fa = seedScenarios(actorA, "ISO-A-AMZ");
    Fixture fb = seedScenarios(actorB, "ISO-B-AMZ");

    var ruleA = saveRule(actorA, "R-" + UUID.randomUUID(), List.of(
        new SaveBudgetTarget(fa.campaignA(), new BigDecimal("1000"), null, null, ""),
        new SaveBudgetTarget(fa.campaignB(), new BigDecimal("500"), null, null, "")));
    var poolA = savePool(actorA, ruleA.rule().id(), "1000");
    as(actorA, "channel.operation.create",
        () -> service.computePlan(new ComputeBudgetPlanRequest(poolA.id(), DAY_1, DAY_2, "")));

    var ruleB = saveRule(actorB, "R-" + UUID.randomUUID(), List.of(
        new SaveBudgetTarget(fb.campaignA(), new BigDecimal("2000"), null, null, ""),
        new SaveBudgetTarget(fb.campaignB(), new BigDecimal("500"), null, null, "")));
    var poolB = savePool(actorB, ruleB.rule().id(), "2000");
    as(actorB, "channel.operation.create",
        () -> service.computePlan(new ComputeBudgetPlanRequest(poolB.id(), DAY_1, DAY_2, "")));

    // 方案互不可见
    assertThat(as(actorA, "channel.operation.read", () -> service.plans(null, null, null, 0, 50)).items())
        .extracting(AdBudgetPlanRow::totalAmount).containsExactly(new BigDecimal("1000.0000"));
    assertThat(as(actorB, "channel.operation.read", () -> service.plans(null, null, null, 0, 50)).items())
        .extracting(AdBudgetPlanRow::totalAmount).containsExactly(new BigDecimal("2000.0000"));

    // 规则互不可见
    assertThat(as(actorA, "channel.operation.read", () -> service.rules(null, null, null, 0, 50)).items())
        .hasSize(1);
    assertThat(as(actorB, "channel.operation.read", () -> service.rules(null, null, null, 0, 50)).items())
        .hasSize(1);

    // 预算池互不可见
    assertThat(as(actorA, "channel.operation.read", () -> service.pools(null, null, 0, 50)).items())
        .hasSize(1);
    assertThat(as(actorB, "channel.operation.read", () -> service.pools(null, null, 0, 50)).items())
        .hasSize(1);

    // 明细也互不可见
    var planA = as(actorA, "channel.operation.read", () -> service.plans(null, null, null, 0, 1)).items().get(0);
    var planB = as(actorB, "channel.operation.read", () -> service.plans(null, null, null, 0, 1)).items().get(0);
    assertThat(as(actorA, "channel.operation.read", () -> service.plan(planA.id().toString())).lines())
        .hasSize(2);
    assertThatThrownBy(() -> as(actorB, "channel.operation.read", () -> service.plan(planA.id().toString())))
        .hasMessageContaining("不存在");
    assertThat(as(actorB, "channel.operation.read", () -> service.plan(planB.id().toString())).lines())
        .hasSize(2);
  }

  @Test
  @DisplayName("14.2.2.1 断言二：伪造 tenant header 无效（租户只来自已认证成员关系）")
  void forgedTenantHeaderIsIgnored() throws Exception {
    Fixture fa = seedScenarios(actorA, "FORGE-A-AMZ");
    seedScenarios(actorB, "FORGE-B-AMZ");

    var ruleA = saveRule(actorA, "R-" + UUID.randomUUID(), List.of(
        new SaveBudgetTarget(fa.campaignA(), new BigDecimal("1000"), null, null, ""),
        new SaveBudgetTarget(fa.campaignB(), new BigDecimal("500"), null, null, "")));
    var poolA = savePool(actorA, ruleA.rule().id(), "1000");
    as(actorA, "channel.operation.create",
        () -> service.computePlan(new ComputeBudgetPlanRequest(poolA.id(), DAY_1, DAY_2, "")));

    // A 的会话 + B 的头 ⇒ 仍然只看得见 A 的方案
    http.perform(get("/channel/ad-budgets/plans")
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()", org.hamcrest.Matchers.is(1)));

    // 无会话一律 401
    http.perform(get("/channel/ad-budgets/plans")
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("14.2.2.1 断言三：连接池归还后不串租户（池压到 1，A→B→A 三轮）")
  void pooledConnectionsDoNotBleedTenants() {
    Fixture fa = seedScenarios(actorA, "POOL-A-AMZ");
    Fixture fb = seedScenarios(actorB, "POOL-B-AMZ");

    var ruleA = saveRule(actorA, "R-" + UUID.randomUUID(), List.of(
        new SaveBudgetTarget(fa.campaignA(), new BigDecimal("1000"), null, null, ""),
        new SaveBudgetTarget(fa.campaignB(), new BigDecimal("500"), null, null, "")));
    var poolA = savePool(actorA, ruleA.rule().id(), "1000");
    as(actorA, "channel.operation.create",
        () -> service.computePlan(new ComputeBudgetPlanRequest(poolA.id(), DAY_1, DAY_2, "")));

    var ruleB = saveRule(actorB, "R-" + UUID.randomUUID(), List.of(
        new SaveBudgetTarget(fb.campaignA(), new BigDecimal("2000"), null, null, ""),
        new SaveBudgetTarget(fb.campaignB(), new BigDecimal("500"), null, null, "")));
    var poolB = savePool(actorB, ruleB.rule().id(), "2000");
    as(actorB, "channel.operation.create",
        () -> service.computePlan(new ComputeBudgetPlanRequest(poolB.id(), DAY_1, DAY_2, "")));

    for (int i = 0; i < 3; i++) {
      assertThat(as(actorA, "channel.operation.read", () -> service.plans(null, null, null, 0, 50)).items())
          .extracting(AdBudgetPlanRow::totalAmount).containsExactly(new BigDecimal("1000.0000"));
      assertThat(as(actorB, "channel.operation.read", () -> service.plans(null, null, null, 0, 50)).items())
          .extracting(AdBudgetPlanRow::totalAmount).containsExactly(new BigDecimal("2000.0000"));
      assertThat(as(actorA, "channel.operation.read", () -> service.rules(null, null, null, 0, 50)).items())
          .hasSize(1);
      assertThat(as(actorB, "channel.operation.read", () -> service.rules(null, null, null, 0, 50)).items())
          .hasSize(1);
      assertThat(as(actorA, "channel.operation.read", () -> service.pools(null, null, 0, 50)).items())
          .hasSize(1);
      assertThat(as(actorB, "channel.operation.read", () -> service.pools(null, null, 0, 50)).items())
          .hasSize(1);
    }
  }

  /** 从库里读回方案逐条分配额之和（**分配额之和 == 池总额**的最终判据）。 */
  BigDecimal planAllocatedSumInDb(UUID planId) {
    java.sql.Connection conn = null;
    try {
      conn = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
      try (var ps = conn.prepareStatement(
          "select coalesce(sum(allocated_budget),0) from ad_budget_plan_line where plan_id = ?")) {
        ps.setObject(1, planId);
        try (var rs = ps.executeQuery()) {
          rs.next();
          return rs.getBigDecimal(1);
        }
      }
    } catch (SQLException e) {
      throw new RuntimeException(e);
    } finally {
      if (conn != null) {
        try {
          conn.close();
        } catch (SQLException ignored) {
          // 关闭失败不影响断言
        }
      }
    }
  }

  /** 从库里读回方案的计算时刻（**写回值**逐字往返的判据）。 */
  OffsetDateTime planComputedAtInDb(UUID planId) {
    java.sql.Connection conn = null;
    try {
      conn = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
      try (var ps = conn.prepareStatement("select computed_at from ad_budget_plan where id = ?")) {
        ps.setObject(1, planId);
        try (var rs = ps.executeQuery()) {
          rs.next();
          return rs.getObject("computed_at", OffsetDateTime.class);
        }
      }
    } catch (SQLException e) {
      throw new RuntimeException(e);
    } finally {
      if (conn != null) {
        try {
          conn.close();
        } catch (SQLException ignored) {
          // 关闭失败不影响断言
        }
      }
    }
  }

  /** 从库里读回任务的执行时刻（**写回值**逐字往返的判据）。 */
  OffsetDateTime taskAppliedAtInDb(UUID taskId) {
    java.sql.Connection conn = null;
    try {
      conn = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
      try (var ps = conn.prepareStatement("select applied_at from ad_budget_task where id = ?")) {
        ps.setObject(1, taskId);
        try (var rs = ps.executeQuery()) {
          rs.next();
          return rs.getObject("applied_at", OffsetDateTime.class);
        }
      }
    } catch (SQLException e) {
      throw new RuntimeException(e);
    } finally {
      if (conn != null) {
        try {
          conn.close();
        } catch (SQLException ignored) {
          // 关闭失败不影响断言
        }
      }
    }
  }

  // ==================================================================
  // 辅助断言
  // ==================================================================

  static AdBudgetPlanLineRow lineOf(AdBudgetPlanDetail plan, String campaignName) {
    return plan.lines().stream()
        .filter(l -> l.campaignName().equals(campaignName))
        .findFirst()
        .orElseThrow(() -> new AssertionError("方案里没有活动：" + campaignName));
  }
}
