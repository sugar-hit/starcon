package com.starcon.channel;

import static com.starcon.channel.AdEfficiencyDiagnosisModels.*;
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
 * 14.3.2.2 效率诊断。
 *
 * <p>本测试类钉住本模块最容易做丢的几件事：
 *
 * <ol>
 *   <li><b>多租户三断言</b>（AGENTS.md 硬性要求，缺一不可）：两租户互不可见、伪造 tenant header 无效、
 *       连接池归还后不串租户
 *   <li><b>🔴 五种判定口径</b>：高花费零转化 / ACOS 长期超标 / 预算受限 / 曝光不足各命中一条，
 *       且互不串台（预算是瓶颈时只报预算受限，不重复报曝光不足）
 *   <li><b>🔴 ACOS 不可比</b>：销售额为 0 时 ACOS 是**空值**（不可比），不是 0 ——
 *       拿 0 冒充会让「烧了钱没转化」看起来像「效率极高」
 *   <li><b>🔴 创意衰退数据源未就绪</b>：该规则**不产出任何结论**，运行记录里写明为什么
 *   <li><b>🔴 一键调整四道闸</b>：试运行只算不调 / 真实执行落本地并留审计 / 幂等不重复调 /
 *       护栏越限**拒绝并留痕**（不静默截断）
 *   <li><b>🔴 竞价动作显式不可用</b>：14.2.1.1 未交付 ⇒ 点不动，且不跳过它直接写平台
 *   <li><b>时间字段写回值正确</b>（AGENTS.md 要求断言写回值，不能只断言没抛异常）
 *   <li><b>真分页真筛选</b>；跨租户与不存在返回逐字相同的 404
 * </ol>
 *
 * <p><b>时刻一律用固定的 {@link OffsetDateTime} 钉死</b>，不用 {@code now()}。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AdEfficiencyDiagnosisIntegrationTest {

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
  static final LocalDate DAY_7 = LocalDate.of(2026, 10, 7);

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

  /** 播种全局码表（平台目录 + 平台默认映射）。刻意**不在静态块里**播种（那时表还没建）。 */
  @BeforeEach
  void seedGlobalCatalog() {
    exec("insert into platform (id,code,name,integration,support_status,position)"
        + " values ('" + AMAZON + "','AMAZON','Amazon','OFFICIAL_API','AVAILABLE',1)"
        + " on conflict (id) do nothing");
    // 🔴 归一类型的真相在 2.1.2.1：没有这条映射，广告类型档案只能解析成 UNMAPPED
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

  @Autowired AdEfficiencyDiagnosisService service;
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

  /** 一条活动的每日指标。 */
  record Day(LocalDate date, String impressions, String clicks, String orders, String spend, String sales) {}

  /** 通过 14.1.1.1 的**真实写入口**登记活动与每日指标。 */
  void importCampaign(
      AccessService.Access who, UUID platformId, String shop, UUID profileId, String externalId,
      String budget, List<Day> days) {
    List<AdCampaignModels.ImportDailyMetric> metrics =
        days.stream()
            .map(
                d ->
                    new AdCampaignModels.ImportDailyMetric(
                        d.date(),
                        new BigDecimal(d.impressions()),
                        new BigDecimal(d.clicks()),
                        new BigDecimal(d.orders()),
                        new BigDecimal(d.spend()),
                        new BigDecimal(d.sales())))
            .toList();
    as(who, "channel.operation.create",
        () ->
            campaigns.importCampaigns(
                new AdCampaignModels.ImportRequest(
                    platformId,
                    shop,
                    new AdCampaignModels.ImportSyncRun(
                        "SUCCESS", DATA_AS_OF, STARTED_AT, FINISHED_AT, "", "batch-1"),
                    List.of(
                        new AdCampaignModels.ImportCampaign(
                            externalId,
                            "活动 " + externalId,
                            profileId,
                            "ENABLED",
                            "USD",
                            budget == null ? null : new BigDecimal(budget),
                            null,
                            null,
                            metrics,
                            "测试登记")))));
  }

  /** 连续 7 天、每天同样的量。 */
  static List<Day> days(String impressions, String clicks, String orders, String spend, String sales) {
    return java.util.stream.IntStream.rangeClosed(1, 7)
        .mapToObj(
            i ->
                new Day(
                    DAY_1.plusDays(i - 1L), impressions, clicks, orders, spend, sales))
        .toList();
  }

  /** 播种一套「四种问题各一条」的活动（租户 a）。 */
  void seedFourFindings(AccessService.Access who, UUID platformId, String shop, UUID profile) {
    // ① 高花费零转化：210 花费、0 订单；预算没花满（30 < 100）⇒ 不会串到「预算受限」
    importCampaign(who, platformId, shop, profile, "EXT-HIGH-SPEND", "100.0000",
        days("1000", "50", "0", "30.0000", "0.0000"));
    // ② ACOS 长期超标：ACOS = 350/700 = 0.5 > 0.3，每天都超标；预算没花满
    importCampaign(who, platformId, shop, profile, "EXT-ACOS", "100.0000",
        days("1000", "50", "1", "50.0000", "100.0000"));
    // ③ 预算受限：每天把 50 的预算花满；ACOS = 350/1400 = 0.25 ≤ 0.3 ⇒ 不会串到「ACOS 超标」
    importCampaign(who, platformId, shop, profile, "EXT-BUDGET", "50.0000",
        days("1000", "50", "1", "50.0000", "200.0000"));
    // ④ 曝光不足：日均曝光 2，远低于同平台中位数 1000；预算没花满 ⇒ 不与「预算受限」重复
    importCampaign(who, platformId, shop, profile, "EXT-LOW-IMP", "100.0000",
        days("2", "0", "0", "5.0000", "0.0000"));
  }

  /** 初始化默认规则并跑一次诊断。 */
  AdDiagnosisRunDetail initAndRun(AccessService.Access who) {
    as(who, "channel.operation.create", () -> service.initDefaultRules());
    return as(who, "channel.operation.create", () -> service.run(new RunDiagnosisRequest(null, null, null)));
  }

  // ==================================================================
  // 1. 能力声明：未交付的段必须显式 available=false
  // ==================================================================

  @Test
  @DisplayName("14.3.2.2 能力声明：竞价 / 创意衰退显式未交付，预算动作可执行")
  void capabilitiesDeclareUndeliveredParts() {
    var caps = as(actorA, "channel.operation.read", () -> service.capabilities());
    Map<String, AdDiagnosisCapabilityDimension> dims =
        caps.dimensions().stream()
            .collect(java.util.stream.Collectors.toMap(AdDiagnosisCapabilityDimension::dimension, d -> d));

    assertThat(dims.get("diagnosis_rule").available()).isTrue();
    assertThat(dims.get("diagnosis_run").available()).isTrue();
    assertThat(dims.get("diagnosis_finding").available()).isTrue();
    assertThat(dims.get("recommended_action").available()).isTrue();
    assertThat(dims.get("budget_action").available()).isTrue();

    // 🔴 竞价：14.2.1.1 未交付 ⇒ 显式 false + ownerModule + 影响，不许用空列表冒充已交付
    assertThat(dims.get("bid_action").available()).isFalse();
    assertThat(dims.get("bid_action").ownerModule()).isEqualTo("14.2.1.1 竞价管理");
    assertThat(dims.get("bid_action").impact()).contains("14.2.1.1");

    // 🔴 创意衰退：创意数据源未就绪 ⇒ 显式 false + ownerModule
    assertThat(dims.get("creative_decay").available()).isFalse();
    assertThat(dims.get("creative_decay").ownerModule()).contains("14.1.1.2");

    // 关键词层：14.1.3.1 已交付 ⇒ 可用
    assertThat(dims.get("keyword_action").available()).isTrue();
    // 指标来源：14.1.1.1 已交付事实表 ⇒ 可用（本模块不等 14.3.1.1 报表）
    assertThat(dims.get("report_upstream").available()).isTrue();
  }

  // ==================================================================
  // 2. 诊断规则：默认规则是种子值，幂等
  // ==================================================================

  @Test
  @DisplayName("14.3.2.2 默认规则落成真实库行且幂等；创意衰退规则数据源未就绪")
  void defaultRulesAreRealRowsAndIdempotent() {
    var first = as(actorA, "channel.operation.create", () -> service.initDefaultRules());
    assertThat(first.created()).isEqualTo(5);
    assertThat(first.existing()).isEqualTo(0);
    assertThat(first.rules()).hasSize(5);

    // 幂等：第二次不覆盖
    var second = as(actorA, "channel.operation.create", () -> service.initDefaultRules());
    assertThat(second.created()).isEqualTo(0);
    assertThat(second.existing()).isEqualTo(5);

    AdDiagnosisRuleRow creative =
        first.rules().stream()
            .filter(r -> r.ruleType().equals("CREATIVE_DECAY"))
            .findFirst()
            .orElseThrow();
    // 🔴 创意衰退：数据源未就绪 ⇒ 规则在，但不产出结论
    assertThat(creative.dataSourceReady()).isFalse();
    assertThat(creative.actionType()).isEqualTo("NONE");

    AdDiagnosisRuleRow acos = first.rules().stream()
        .filter(r -> r.ruleType().equals("ACOS_OVER_TARGET"))
        .findFirst()
        .orElseThrow();
    assertThat(acos.thresholdRatio()).isEqualByComparingTo("0.300000");
    assertThat(acos.minOverTargetDays()).isEqualTo(3);
    // 🔴 阈值是钱 ⇒ BigDecimal，无 float
    assertThat(acos.thresholdRatio()).isInstanceOf(BigDecimal.class);
  }

  @Test
  @DisplayName("14.3.2.2 DB 层兜底：阈值与规则类型不匹配的直接写 SQL 也被拒")
  void databaseRejectsInconsistentRuleThresholds() {
    // 高花费零转化规则没有金额门槛 ⇒ 延迟约束触发器必须拒
    var missingThreshold =
        org.assertj.core.api.Assertions.catchThrowable(
            () ->
                execAsTenant(
                    a,
                    "insert into ad_diagnosis_rule (tenant_id,id,rule_code,rule_name,rule_type,"
                        + " severity,enabled,lookback_days,min_metric_days,action_type,"
                        + " data_source_ready,dry_run,version,created_at,updated_at)"
                        + " values ('" + a + "','" + UUID.randomUUID() + "','BAD-1','缺门槛',"
                        + "'HIGH_SPEND_NO_CONVERSION','HIGH',true,14,3,'PAUSE_CAMPAIGN',true,true,0,now(),now())"));
    assertThat(missingThreshold).isNotNull();
    assertThat(missingThreshold.getMessage()).contains("高花费零转化");
    assertThat(sqlState(missingThreshold)).isEqualTo("23514");

    // 创意衰退规则声称数据源已就绪 ⇒ 必须被拒（不许拿假数据凑）
    var fakeCreative =
        org.assertj.core.api.Assertions.catchThrowable(
            () ->
                execAsTenant(
                    a,
                    "insert into ad_diagnosis_rule (tenant_id,id,rule_code,rule_name,rule_type,"
                        + " severity,enabled,lookback_days,min_metric_days,action_type,"
                        + " data_source_ready,dry_run,version,created_at,updated_at)"
                        + " values ('" + a + "','" + UUID.randomUUID() + "','BAD-2','假创意',"
                        + "'CREATIVE_DECAY','LOW',true,30,7,'NONE',true,true,0,now(),now())"));
    assertThat(fakeCreative).isNotNull();
    assertThat(fakeCreative.getMessage()).contains("创意衰退");
    assertThat(sqlState(fakeCreative)).isEqualTo("23514");
  }

  /** 取出底层 PostgreSQL 的 SQLSTATE（23514 = check_violation）。 */
  static String sqlState(Throwable thrown) {
    for (Throwable t = thrown; t != null; t = t.getCause()) {
      if (t instanceof SQLException sql && sql.getSQLState() != null) return sql.getSQLState();
    }
    return "";
  }

  // ==================================================================
  // 3. 四种判定口径各命中一条，互不串台
  // ==================================================================

  @Test
  @DisplayName("14.3.2.2 四种问题各命中一条：观察量快照可复算，ACOS 不可比给空值")
  void fourFindingsAreDetectedWithReproducibleSnapshots() {
    String shop = seedShop(a, AMAZON, "DIAG-AMZ-1");
    UUID profile = saveProfile(actorA, AMAZON, "sponsored_products", "AUTO_DISCOVERY");
    seedFourFindings(actorA, AMAZON, shop, profile);

    var detail = initAndRun(actorA);

    assertThat(detail.run().status()).isEqualTo("SUCCESS");
    assertThat(detail.run().diagnosisCount()).isEqualTo(4);
    assertThat(detail.diagnoses()).hasSize(4);

    // 🔴 四种口径各一条
    assertThat(detail.diagnoses())
        .extracting(AdDiagnosisRow::ruleType)
        .containsExactlyInAnyOrder(
            "HIGH_SPEND_NO_CONVERSION", "ACOS_OVER_TARGET", "BUDGET_CAPPED", "LOW_DELIVERY");

    AdDiagnosisRow highSpend =
        detail.diagnoses().stream()
            .filter(d -> d.ruleType().equals("HIGH_SPEND_NO_CONVERSION"))
            .findFirst()
            .orElseThrow();
    // 🔴 观察量快照：7 天 × 30 = 210，可复算
    assertThat(highSpend.spend()).isEqualByComparingTo("210.0000");
    assertThat(highSpend.orders()).isEqualByComparingTo("0");
    assertThat(highSpend.sales()).isEqualByComparingTo("0");
    assertThat(highSpend.metricDays()).isEqualTo(7);
    // 🔴 销售额为 0 ⇒ ACOS **不可比**（空值），不是 0
    assertThat(highSpend.acos()).isNull();
    // 🔴 但 ROAS = 销售额 / 花费 = 0 / 210 = 0 是**可比但真的是零** ⇒ 0，不是空值。
    //   「算不出来」与「算出来是零」是相反的两件事：把不可比写成 0，会让
    //   「烧了 210 块、零订单」看起来像「效率极高」。
    assertThat(highSpend.roas()).isEqualByComparingTo("0.000000");
    // 🔴 诊断要给动作而不是只报警
    assertThat(highSpend.recommendation()).contains("暂停");
    assertThat(highSpend.evidence()).contains("210");

    AdDiagnosisRow acos =
        detail.diagnoses().stream()
            .filter(d -> d.ruleType().equals("ACOS_OVER_TARGET"))
            .findFirst()
            .orElseThrow();
    // 🔴 ACOS = 350 / 700 = 0.5（先汇总再相除）
    assertThat(acos.acos()).isEqualByComparingTo("0.500000");
    assertThat(acos.spend()).isEqualByComparingTo("350.0000");
    assertThat(acos.sales()).isEqualByComparingTo("700.0000");
    // 🔴 「长期」= 超标天数 ≥ 3
    assertThat(acos.overTargetDays()).isEqualTo(7);

    AdDiagnosisRow budget =
        detail.diagnoses().stream()
            .filter(d -> d.ruleType().equals("BUDGET_CAPPED"))
            .findFirst()
            .orElseThrow();
    assertThat(budget.budgetUtilization()).isEqualByComparingTo("1.000000");
    assertThat(budget.dailyBudget()).isEqualByComparingTo("50.0000");

    AdDiagnosisRow lowDelivery =
        detail.diagnoses().stream()
            .filter(d -> d.ruleType().equals("LOW_DELIVERY"))
            .findFirst()
            .orElseThrow();
    // 🔴 曝光相对倍数 = 日均曝光 2 / 同平台中位数 1000
    assertThat(lowDelivery.deliveryRatio()).isEqualByComparingTo("0.002000");

    // 🔴 互不串台：预算是瓶颈的那条只报「预算受限」，不重复报「曝光不足」
    assertThat(detail.diagnoses().stream().filter(d -> d.ruleType().equals("BUDGET_CAPPED")).count())
        .isEqualTo(1);
    assertThat(
            detail.diagnoses().stream()
                .filter(
                    d ->
                        d.ruleType().equals("LOW_DELIVERY")
                            && d.campaignName().contains("EXT-BUDGET"))
                .count())
        .isEqualTo(0);

    // 🔴 观察窗口的末端 = 本租户最新有数据的自然日（不是墙钟）
    assertThat(detail.run().windowTo()).isEqualTo(DAY_7);
    // 🔴 时间字段断言**写回值**
    assertThat(detail.run().dataAsOf().toInstant()).isEqualTo(DATA_AS_OF.toInstant());
  }

  @Test
  @DisplayName("14.3.2.2 创意衰退：数据源未就绪 ⇒ 不产出结论，规则被跳过并写明原因")
  void creativeDecayIsSkippedNotFaked() {
    String shop = seedShop(a, AMAZON, "DIAG-AMZ-2");
    UUID profile = saveProfile(actorA, AMAZON, "sponsored_products", "AUTO_DISCOVERY");
    seedFourFindings(actorA, AMAZON, shop, profile);

    var detail = initAndRun(actorA);

    AdDiagnosisRunRuleRow creative =
        detail.ruleResults().stream()
            .filter(r -> r.ruleType().equals("CREATIVE_DECAY"))
            .findFirst()
            .orElseThrow();
    // 🔴 没产出结论也要留痕：跳过 + 写清「差在哪」
    assertThat(creative.evaluated()).isFalse();
    assertThat(creative.skipReason()).contains("数据源未就绪");
    assertThat(creative.matchedCampaignCount()).isEqualTo(0);
    // 🔴 一条创意衰退结论都不许有
    assertThat(detail.diagnoses().stream().filter(d -> d.ruleType().equals("CREATIVE_DECAY")).count())
        .isEqualTo(0);
    assertThat(detail.run().skippedRuleCount()).isEqualTo(1);
  }

  // ==================================================================
  // 4. 🔴 一键调整：四道闸
  // ==================================================================

  @Test
  @DisplayName("14.3.2.2 一键调整试运行只算不调，真实执行落本地并留审计，重复提交幂等")
  void applyActionHonoursDryRunAuditAndIdempotency() {
    String shop = seedShop(a, AMAZON, "DIAG-AMZ-3");
    UUID profile = saveProfile(actorA, AMAZON, "sponsored_products", "AUTO_DISCOVERY");
    seedFourFindings(actorA, AMAZON, shop, profile);
    var detail = initAndRun(actorA);

    AdDiagnosisActionRow increase =
        detail.actions().stream()
            .filter(x -> x.actionType().equals("INCREASE_BUDGET"))
            .findFirst()
            .orElseThrow();
    // 🔴 预算动作现在就能做
    assertThat(increase.actionMode()).isEqualTo("WRITE");
    assertThat(increase.available()).isTrue();
    assertThat(increase.beforeValue()).isEqualByComparingTo("50.0000");
    // +20% ⇒ 60
    assertThat(increase.afterValue()).isEqualByComparingTo("60.0000");

    // ---- 闸 ③：试运行只算不调 ----
    var dry = as(actorA, "channel.operation.update",
        () -> service.applyAction(increase.id().toString(), new ApplyActionRequest(true, "先看看")));
    assertThat(dry.outcome()).isEqualTo("DRY_RUN");
    assertThat(dry.action().afterValue()).isEqualByComparingTo("60.0000");
    // 🔴 预算**没有**被改
    assertThat(currentBudget(a, increase.campaignId())).isEqualByComparingTo("50.0000");

    // ---- 真实执行 ----
    var applied = as(actorA, "channel.operation.update",
        () -> service.applyAction(increase.id().toString(), new ApplyActionRequest(false, "提高预算")));
    assertThat(applied.outcome()).isEqualTo("APPLIED");
    assertThat(currentBudget(a, increase.campaignId())).isEqualByComparingTo("60.0000");
    // 🔴 审计留痕（走 14.1.1.1 已交付的写路径）
    assertThat(operationLogCount(a, increase.campaignId())).isEqualTo(1);
    // 🔴 诊断结论状态跟着走
    var diag = as(actorA, "channel.operation.read", () -> service.diagnosis(applied.action().diagnosisId().toString()));
    assertThat(diag.diagnosis().status()).isEqualTo("ACTIONED");

    // ---- 闸 ②：幂等 ----
    var again = as(actorA, "channel.operation.update",
        () -> service.applyAction(increase.id().toString(), new ApplyActionRequest(false, "再点一次")));
    assertThat(again.outcome()).isEqualTo("SKIPPED");
    // 🔴 预算没有被调两遍
    assertThat(currentBudget(a, increase.campaignId())).isEqualByComparingTo("60.0000");
    assertThat(operationLogCount(a, increase.campaignId())).isEqualTo(1);
  }

  @Test
  @DisplayName("14.3.2.2 竞价动作显式不可用：点不动并写明 14.2.1.1，不跳过它直接写平台")
  void bidActionIsExplicitlyUnavailable() {
    String shop = seedShop(a, AMAZON, "DIAG-AMZ-4");
    UUID profile = saveProfile(actorA, AMAZON, "sponsored_products", "AUTO_DISCOVERY");
    seedFourFindings(actorA, AMAZON, shop, profile);
    var detail = initAndRun(actorA);

    AdDiagnosisActionRow bid =
        detail.actions().stream()
            .filter(x -> x.actionType().equals("INCREASE_BID"))
            .findFirst()
            .orElseThrow();
    assertThat(bid.actionMode()).isEqualTo("UNAVAILABLE");
    assertThat(bid.available()).isFalse();
    assertThat(bid.ownerModule()).isEqualTo("14.2.1.1 竞价管理");
    assertThat(bid.impact()).contains("14.2.1.1");

    // 🔴 点不动：409 + 写明责任模块
    assertThatThrownBy(
            () ->
                as(actorA, "channel.operation.update",
                    () -> service.applyAction(bid.id().toString(), new ApplyActionRequest(false, "提高竞价"))))
        .hasMessageContaining("14.2.1.1 竞价管理");

    // 🔴 关键词层优化：14.1.3.1 已交付 ⇒ 可导航
    AdDiagnosisActionRow keywords =
        detail.actions().stream()
            .filter(x -> x.actionType().equals("OPTIMIZE_KEYWORDS"))
            .findFirst()
            .orElseThrow();
    assertThat(keywords.actionMode()).isEqualTo("NAVIGATE");
    assertThat(keywords.available()).isTrue();
    assertThat(keywords.targetModule()).isEqualTo("14.1.3.1 关键词管理");
    assertThat(keywords.targetPath()).isEqualTo("/ads/keywords");
  }

  @Test
  @DisplayName("14.3.2.2 护栏越限：拒绝并留痕，不静默截断")
  void guardrailRejectsInsteadOfTruncating() {
    String shop = seedShop(a, AMAZON, "DIAG-AMZ-5");
    UUID profile = saveProfile(actorA, AMAZON, "sponsored_products", "AUTO_DISCOVERY");
    // 预算 100000（单活动上限），每天花满 95000（= 预算 × 0.95 ⇒ 预算受限成立）
    // ⇒ 建议 +20% = +20000，超过单次调整上限 5000，且结果 120000 超过单活动上限 100000
    importCampaign(actorA, AMAZON, shop, profile, "EXT-GUARD", "100000.0000",
        days("1000", "50", "1", "95000.0000", "500000.0000"));
    var detail = initAndRun(actorA);

    AdDiagnosisActionRow increase =
        detail.actions().stream()
            .filter(x -> x.actionType().equals("INCREASE_BUDGET"))
            .findFirst()
            .orElseThrow();

    var result = as(actorA, "channel.operation.update",
        () -> service.applyAction(increase.id().toString(), new ApplyActionRequest(false, "加预算")));
    // 🔴 被拒，不是被静默截断
    assertThat(result.outcome()).isEqualTo("REJECTED");
    assertThat(result.message()).isNotBlank();
    assertThat(result.guardrailNote()).contains("护栏");
    // 🔴 预算没被动过
    assertThat(currentBudget(a, increase.campaignId())).isEqualByComparingTo("100000.0000");
    // 🔴 被拒的也要留审计
    assertThat(operationLogCount(a, increase.campaignId())).isEqualTo(1);
  }

  @Test
  @DisplayName("14.3.2.2 暂停动作：走 14.1.1.1 的写路径，活动真的变成 PAUSED")
  void pauseActionGoesThroughTheDeliveredWritePath() {
    String shop = seedShop(a, AMAZON, "DIAG-AMZ-6");
    UUID profile = saveProfile(actorA, AMAZON, "sponsored_products", "AUTO_DISCOVERY");
    seedFourFindings(actorA, AMAZON, shop, profile);
    var detail = initAndRun(actorA);

    AdDiagnosisActionRow pause =
        detail.actions().stream()
            .filter(x -> x.actionType().equals("PAUSE_CAMPAIGN"))
            .findFirst()
            .orElseThrow();

    var result = as(actorA, "channel.operation.update",
        () -> service.applyAction(pause.id().toString(), new ApplyActionRequest(false, "先止损")));
    assertThat(result.outcome()).isEqualTo("APPLIED");
    assertThat(campaignStatus(a, pause.campaignId())).isEqualTo("PAUSED");
    // 🔴 本地已改但还没回写平台
    assertThat(pendingWriteback(a, pause.campaignId())).isTrue();
  }

  // ==================================================================
  // 5. 真分页真筛选 + 404 口径
  // ==================================================================

  @Test
  @DisplayName("14.3.2.2 真分页真筛选；跨租户与不存在返回逐字相同的 404")
  void paginationFiltersAndIdenticalNotFound() {
    String shop = seedShop(a, AMAZON, "DIAG-AMZ-7");
    UUID profile = saveProfile(actorA, AMAZON, "sponsored_products", "AUTO_DISCOVERY");
    seedFourFindings(actorA, AMAZON, shop, profile);
    initAndRun(actorA);

    var page0 = as(actorA, "channel.operation.read", () -> service.diagnoses(null, null, null, null, null, 0, 2));
    assertThat(page0.total()).isEqualTo(4);
    assertThat(page0.items()).hasSize(2);
    var page1 = as(actorA, "channel.operation.read", () -> service.diagnoses(null, null, null, null, null, 1, 2));
    assertThat(page1.items()).hasSize(2);
    assertThat(page0.items()).doesNotContainAnyElementsOf(page1.items());

    // 真筛选
    var filtered = as(actorA, "channel.operation.read",
        () -> service.diagnoses("ACOS_OVER_TARGET", null, null, null, null, 0, 50));
    assertThat(filtered.total()).isEqualTo(1);
    assertThat(filtered.items().get(0).ruleType()).isEqualTo("ACOS_OVER_TARGET");

    // 跨租户与不存在：逐字相同的 404
    var first = as(actorA, "channel.operation.read", () -> service.diagnoses(null, null, null, null, null, 0, 1));
    var missing =
        org.assertj.core.api.Assertions.catchThrowable(
            () ->
                as(actorA, "channel.operation.read",
                    () -> service.diagnosis(UUID.randomUUID().toString())));
    var crossTenant =
        org.assertj.core.api.Assertions.catchThrowable(
            () ->
                as(actorB, "channel.operation.read",
                    () -> service.diagnosis(first.items().get(0).id().toString())));
    assertThat(missing).isNotNull();
    assertThat(crossTenant).isNotNull();
    assertThat(crossTenant.getMessage()).isEqualTo(missing.getMessage());
    assertThat(missing.getMessage()).contains("不存在");
  }

  @Test
  @DisplayName("14.3.2.2 没有规则时给出「还没配置规则」的空态，不拿硬编码常量当数据源")
  void emptyStateSeparatesNoRulesFromNoFindings() {
    var page = as(actorA, "channel.operation.read",
        () -> service.diagnoses(null, null, null, null, null, 0, 50));
    assertThat(page.total()).isEqualTo(0);
    assertThat(page.emptyReason()).isEqualTo("NO_RULES");
    assertThat(page.emptyNote()).contains("诊断规则");
  }

  // ==================================================================
  // 6. 多租户三断言（AGENTS.md 硬性要求，缺一不可）
  // ==================================================================

  @Test
  @DisplayName("14.3.2.2 断言一：两租户互不可见")
  void tenantsAreIsolated() {
    String shopA = seedShop(a, AMAZON, "ISO-DIAG-1");
    String shopB = seedShop(b, AMAZON, "ISO-DIAG-2");
    UUID profileA = saveProfile(actorA, AMAZON, "sponsored_products", "AUTO_DISCOVERY");
    UUID profileB = saveProfile(actorB, AMAZON, "sponsored_products", "AUTO_DISCOVERY");
    seedFourFindings(actorA, AMAZON, shopA, profileA);
    seedFourFindings(actorB, AMAZON, shopB, profileB);

    initAndRun(actorA);
    initAndRun(actorB);

    // 诊断清单互不可见
    var listA = as(actorA, "channel.operation.read",
        () -> service.diagnoses(null, null, null, null, null, 0, 200));
    var listB = as(actorB, "channel.operation.read",
        () -> service.diagnoses(null, null, null, null, null, 0, 200));
    assertThat(listA.total()).isEqualTo(4);
    assertThat(listB.total()).isEqualTo(4);
    assertThat(listA.items()).allMatch(d -> d.campaignName().contains("EXT-"));

    // 规则互不可见
    assertThat(as(actorA, "channel.operation.read", () -> service.rules(null, null, 0, 200)).total())
        .isEqualTo(5);
    assertThat(as(actorB, "channel.operation.read", () -> service.rules(null, null, 0, 200)).total())
        .isEqualTo(5);

    // 运行历史互不可见
    assertThat(as(actorA, "channel.operation.read", () -> service.runs(0, 50)).total()).isEqualTo(1);
    assertThat(as(actorB, "channel.operation.read", () -> service.runs(0, 50)).total()).isEqualTo(1);

    // 详情也互不可见
    var firstA = listA.items().get(0);
    assertThatThrownBy(
            () ->
                as(actorB, "channel.operation.read",
                    () -> service.diagnosis(firstA.id().toString())))
        .hasMessageContaining("不存在");
  }

  @Test
  @DisplayName("14.3.2.2 断言二：伪造 tenant header 无效（租户只来自已认证成员关系）")
  void forgedTenantHeaderIsIgnored() throws Exception {
    String shopA = seedShop(a, AMAZON, "FORGE-DIAG-1");
    String shopB = seedShop(b, AMAZON, "FORGE-DIAG-2");
    UUID profileA = saveProfile(actorA, AMAZON, "sponsored_products", "AUTO_DISCOVERY");
    UUID profileB = saveProfile(actorB, AMAZON, "sponsored_products", "AUTO_DISCOVERY");
    seedFourFindings(actorA, AMAZON, shopA, profileA);
    seedFourFindings(actorB, AMAZON, shopB, profileB);
    initAndRun(actorA);
    initAndRun(actorB);

    // A 的会话 + B 的头 ⇒ 仍然只看得见 A 的诊断结论
    http.perform(get("/channel/ad-diagnostics/diagnoses")
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total", org.hamcrest.Matchers.is(4)));

    // 伪造租户头也不能让 B 的结论被 A 读到
    var listB = as(actorB, "channel.operation.read",
        () -> service.diagnoses(null, null, null, null, null, 0, 1));
    http.perform(get("/channel/ad-diagnostics/diagnoses/" + listB.items().get(0).id())
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isNotFound());

    // 无会话一律 401
    http.perform(get("/channel/ad-diagnostics/diagnoses")
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("14.3.2.2 断言三：连接池归还后不串租户（池压到 1，A→B→A 三轮）")
  void pooledConnectionsDoNotBleedTenants() {
    String shopA = seedShop(a, AMAZON, "POOL-DIAG-1");
    String shopB = seedShop(b, AMAZON, "POOL-DIAG-2");
    UUID profileA = saveProfile(actorA, AMAZON, "sponsored_products", "AUTO_DISCOVERY");
    UUID profileB = saveProfile(actorB, AMAZON, "sponsored_products", "AUTO_DISCOVERY");
    seedFourFindings(actorA, AMAZON, shopA, profileA);
    seedFourFindings(actorB, AMAZON, shopB, profileB);
    initAndRun(actorA);
    initAndRun(actorB);

    for (int i = 0; i < 3; i++) {
      var listA = as(actorA, "channel.operation.read",
          () -> service.diagnoses(null, null, null, null, null, 0, 200));
      var listB = as(actorB, "channel.operation.read",
          () -> service.diagnoses(null, null, null, null, null, 0, 200));
      assertThat(listA.total()).isEqualTo(4);
      assertThat(listB.total()).isEqualTo(4);
      // 结论的活动名互不串台：两个租户用了同一批外部 id，所以断言活动 id 集合不相交
      assertThat(listA.items().stream().map(AdDiagnosisRow::campaignId).toList())
          .doesNotContainAnyElementsOf(
              listB.items().stream().map(AdDiagnosisRow::campaignId).toList());
      // 建议动作也互不可见
      assertThat(as(actorA, "channel.operation.read", () -> service.actions(null, null, null, null, null, 0, 200)).total())
          .isEqualTo(5);
      assertThat(as(actorB, "channel.operation.read", () -> service.actions(null, null, null, null, null, 0, 200)).total())
          .isEqualTo(5);
    }
  }

  // ==================================================================
  // 辅助查询（以超级用户连接读，避开 RLS）
  // ==================================================================

  static BigDecimal currentBudget(UUID tenant, UUID campaignId) {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement();
        var rs = s.executeQuery(
            "select daily_budget from ad_campaign where tenant_id = '" + tenant + "' and id = '"
                + campaignId + "'")) {
      assertThat(rs.next()).isTrue();
      return rs.getBigDecimal(1);
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  static String campaignStatus(UUID tenant, UUID campaignId) {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement();
        var rs = s.executeQuery(
            "select status from ad_campaign where tenant_id = '" + tenant + "' and id = '"
                + campaignId + "'")) {
      assertThat(rs.next()).isTrue();
      return rs.getString(1);
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  static boolean pendingWriteback(UUID tenant, UUID campaignId) {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement();
        var rs = s.executeQuery(
            "select pending_platform_writeback from ad_campaign where tenant_id = '" + tenant
                + "' and id = '" + campaignId + "'")) {
      assertThat(rs.next()).isTrue();
      return rs.getBoolean(1);
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  static int operationLogCount(UUID tenant, UUID campaignId) {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement();
        var rs = s.executeQuery(
            "select count(*) from ad_campaign_operation_log where tenant_id = '" + tenant
                + "' and campaign_id = '" + campaignId + "'")) {
      assertThat(rs.next()).isTrue();
      return rs.getInt(1);
    } catch (SQLException e) {
      throw new RuntimeException(e);
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
