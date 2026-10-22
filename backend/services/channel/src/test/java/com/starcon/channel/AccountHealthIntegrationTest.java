package com.starcon.channel;

import static com.starcon.channel.AccountHealthModels.*;
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
import java.time.ZoneOffset;
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
 * 16.1.1.1 账号健康总览的集成测试（**真实 PostgreSQL 16**）。
 *
 * <p>🔴 这组用例守的是本模块最容易做丢的七件事：
 *
 * <ol>
 *   <li><b>平台绩效同步未接入</b>：同步端点**恒 409 且一条数据都不写**；</li>
 *   <li><b>手工录入的指标恒标 MANUAL</b>，不冒充平台同步；</li>
 *   <li><b>量不到 ≠ 0、量不到 ≠ 低风险</b>：缺一项 ⇒ 等级 UNKNOWN，不是 HEALTHY；</li>
 *   <li><b>判定有依据</b>：哪一项指标、什么值、命中哪条阈值，逐条给出；</li>
 *   <li><b>排序真的按风险排</b>，且每一家都带得出「为什么排在这个位置」；</li>
 *   <li><b>阈值在服务端</b>：改阈值推进版本，历史判定**不追溯改变**（追加式凭据）；</li>
 *   <li><b>店铺清单来自真实店铺目录表</b>，不是写死的常量。</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class AccountHealthIntegrationTest {

  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID user = UUID.randomUUID();
  static HttpServer identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static final ObjectMapper JSON = new ObjectMapper();

  static final UUID AMAZON = UUID.fromString("11111111-1111-1111-1111-111111111101");
  static final String PERIOD_START = "2026-10-01";
  static final String PERIOD_END = "2026-10-31";

  static final String ALL_SCOPE_GRANTS =
      "[{\"permissions\":[\"channel.operation.read\",\"channel.operation.create\","
          + "\"channel.operation.update\",\"channel.operation.delete\","
          + "\"channel.sync.read\",\"channel.sync.trigger\"],\"scopeMode\":\"ALL\"}]";

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

  static AccessService.Access actor(UUID uid, UUID tenantId, List<AccessService.Grant> grants) {
    return new AccessService.Access(
        uid, tenantId, "Test", UUID.randomUUID(), null,
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
  void freshTenant() {
    tenant = UUID.randomUUID();
    bearer = "Bearer t-" + tenant;
    List<AccessService.Grant> grants = List.of(grant("ALL", allPermissions));
    actors.put(bearer, actor(user, tenant, grants));
    who = actors.get(bearer);
  }

  UUID tenant;
  AccessService.Access who;
  String bearer;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "channel_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://localhost:" + identity.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
  }

  @Autowired AccountHealthService service;
  @Autowired AccessService access;
  @Autowired MockMvc http;

  // ==================================================================
  // 辅助
  // ==================================================================

  <T> T as(String permission, Supplier<T> work) {
    return access.run(who, permission, work);
  }

  void execAsTenant(UUID tenantId, String statement) {
    try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute("select set_config('app.current_tenant','" + tenantId + "',false)");
      s.execute("select set_config('app.actor','" + user + "',false)");
      s.execute("select set_config('app.department','',false)");
      s.execute("select set_config('app.permission','channel.operation.create',false)");
      s.execute("select set_config('app.grants','" + ALL_SCOPE_GRANTS + "',false)");
      s.execute(statement);
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  static String scalar(String statement) {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement();
        var rs = s.executeQuery(statement)) {
      return rs.next() ? rs.getString(1) : null;
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  static long countRows(String statement) {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement();
        var rs = s.executeQuery(statement)) {
      return rs.next() ? rs.getLong(1) : 0L;
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  UUID seedShop(String code) {
    UUID id = UUID.randomUUID();
    execAsTenant(
        tenant,
        "insert into shop (tenant_id,id,platform_id,code,name) values ('"
            + tenant
            + "','"
            + id
            + "','"
            + AMAZON
            + "','"
            + code
            + "','测试店铺 "
            + code
            + "')");
    return id;
  }

  AccountHealthShopCard record(
      UUID shopId, String metricCode, String value, String periodStart, String periodEnd) {
    return as(
        "channel.operation.create",
        () ->
            service.recordMetric(
                shopId.toString(),
                new AccountHealthMetricInput(
                    metricCode,
                    value == null ? null : new BigDecimal(value),
                    periodStart,
                    periodEnd,
                    "集成测试录入")));
  }

  /** 录满四项且都不命中阈值。 */
  void recordHealthy(UUID shopId) {
    record(shopId, "ODR", "0.0050", PERIOD_START, PERIOD_END);
    record(shopId, "LATE_SHIPMENT_RATE", "0.0100", PERIOD_START, PERIOD_END);
    record(shopId, "CANCELLATION_RATE", "0.0100", PERIOD_START, PERIOD_END);
    record(shopId, "VALID_TRACKING_RATE", "0.9900", PERIOD_START, PERIOD_END);
  }

  // ==================================================================
  // 能力声明
  // ==================================================================

  @Test
  @DisplayName("16.1.1.1 能力声明：四段下游 available=false + ownerModule（2.4 真未交付；另三个已交付但本模块未接入）")
  void capabilitiesDeclareDownstreamTruthfully() {
    var caps = as("channel.operation.read", () -> service.capabilities());

    assertThat(caps.gaps()).isNotEmpty();
    assertThat(caps.gaps())
        .allSatisfy(
            gap -> {
              assertThat(gap.available()).as("%s 本模块未接入 ⇒ 必须标 available=false", gap.moduleId()).isFalse();
              assertThat(gap.ownerModule()).as("%s 必须写清归谁", gap.moduleId()).isNotBlank();
              assertThat(gap.impact()).as("%s 必须说清影响", gap.moduleId()).isNotBlank();
            });

    assertThat(caps.gaps())
        .extracting(AccountHealthCapabilityGap::moduleId)
        .contains("2.4", "16.1.1.2", "16.1.2.1", "16.1.2.2");

    var adapterGap =
        caps.gaps().stream()
            .filter(gap -> gap.moduleId().equals("2.4"))
            .findFirst()
            .orElseThrow();
    assertThat(adapterGap.impact()).contains("未交付").contains("MANUAL");

    // 🔴 16.1.1.2 / 16.1.2.1 / 16.1.2.2 **已交付**（同服务同库），但本模块**没有接入**它们的表
    //    ⇒ available 仍是 false，理由必须说清「已交付但本模块未接入」，不能再写「未交付」。
    for (var moduleId : new String[] {"16.1.1.2", "16.1.2.1", "16.1.2.2"}) {
      var gap =
          caps.gaps().stream()
              .filter(candidate -> candidate.moduleId().equals(moduleId))
              .findFirst()
              .orElseThrow();
      assertThat(gap.available()).as("%s 本模块未接入 ⇒ available=false", moduleId).isFalse();
      assertThat(gap.impact()).as("%s 的理由必须写「已交付」", moduleId).contains("已交付");
      assertThat(gap.impact()).as("%s 的理由必须写「本模块未接入」", moduleId).contains("本模块未接入");
      assertThat(gap.impact()).as("%s 不能再声称下游未交付", moduleId).doesNotContain("未交付");
    }
    assertThat(caps.downstreamNote()).contains("已交付").contains("本模块未接入");

    // 🔴 「量不到 ≠ 0、量不到 ≠ 低风险」的口径必须写在声明里（页面原样显示）。
    assertThat(caps.unknownSemantics()).contains("UNKNOWN").contains("不是 0");
    assertThat(caps.metricValueSemantics()).contains("0..1 比值");
    assertThat(caps.rankingSemantics()).contains("先处理最可能掉号的店铺");
    assertThat(caps.platformSyncNote()).contains("409").contains("MANUAL");
  }

  // ==================================================================
  // 平台绩效同步：恒 409 且一条数据都不写
  // ==================================================================

  @Test
  @DisplayName("16.1.1.1 🔴 平台绩效同步未接入 ⇒ 恒 409 且一条数据都不写")
  void platformSyncIsRejectedAndWritesNothing() throws Exception {
    UUID shopId = seedShop("SYNC-1");

    long before = countRows("select count(*) from account_health_metric where tenant_id='" + tenant + "'");
    assertThat(before).isZero();

    var thrown =
        catchThrowable(
            () -> as("channel.sync.trigger", () -> service.sync(shopId.toString(), bearer)));
    assertThat(thrown).isNotNull();
    assertThat(thrown.getMessage()).contains("2.4").contains("未接入");

    assertThat(countRows("select count(*) from account_health_metric where tenant_id='" + tenant + "'"))
        .as("同步被拒后一条数据都不许写")
        .isZero();

    // HTTP 层同样是 409
    http.perform(post("/channel/account-health/shops/" + shopId + "/sync")
            .header("Authorization", bearer)
            .contentType("application/json")
            .content("{}"))
        .andExpect(status().isConflict());
    assertThat(countRows("select count(*) from account_health_metric where tenant_id='" + tenant + "'"))
        .isZero();
  }

  // ==================================================================
  // 手工录入：恒标 MANUAL
  // ==================================================================

  @Test
  @DisplayName("16.1.1.1 手工录入的指标 source 恒为 MANUAL（不冒充平台同步）")
  void manualMetricIsAlwaysMarkedManual() {
    UUID shopId = seedShop("MANUAL-1");
    var card = record(shopId, "ODR", "0.0300", PERIOD_START, PERIOD_END);

    var metric = card.metrics().stream().filter(m -> m.metricCode().equals("ODR")).findFirst().orElseThrow();
    assertThat(metric.source()).isEqualTo("MANUAL");
    assertThat(metric.sourceLabel()).isEqualTo("人工录入");

    assertThat(
            scalar(
                "select source from account_health_metric where tenant_id='"
                    + tenant
                    + "' and shop_id='"
                    + shopId
                    + "' and metric_code='ODR'"))
        .as("库里的 source 也必须是 MANUAL")
        .isEqualTo("MANUAL");
  }

  @Test
  @DisplayName("16.1.1.1 指标值是 0..1 比值（0.0280 不是 2.8），且写回值逐字相等")
  void metricValueIsARatioAndRoundTripsExactly() {
    UUID shopId = seedShop("RATIO-1");
    var card = record(shopId, "ODR", "0.0280", PERIOD_START, PERIOD_END);

    var metric = card.metrics().stream().filter(m -> m.metricCode().equals("ODR")).findFirst().orElseThrow();
    assertThat(metric.metricValue()).isEqualByComparingTo("0.0280");

    // 🔴 写回的值必须逐字相等（不偏 28800 秒、不变成百分数）
    assertThat(scalar("select metric_value from account_health_metric where shop_id='" + shopId + "'"))
        .isEqualTo("0.0280");
    assertThat(scalar("select period_start from account_health_metric where shop_id='" + shopId + "'"))
        .isEqualTo(PERIOD_START);
    assertThat(scalar("select period_end from account_health_metric where shop_id='" + shopId + "'"))
        .isEqualTo(PERIOD_END);

    // captured_at 是 timestamptz：断言写回值是**同一个时刻**，不是「没抛异常」
    String epoch =
        scalar(
            "select floor(extract(epoch from captured_at))::bigint from account_health_metric where shop_id='"
                + shopId
                + "'");
    assertThat(epoch).isNotBlank();
    assertThat(Long.parseLong(epoch))
        .as("写回的时刻必须与读到的时刻是同一刻（不偏 28800 秒）")
        .isEqualTo(OffsetDateTime.parse(metric.capturedAt()).toEpochSecond());
  }

  @Test
  @DisplayName("16.1.1.1 非法指标值 / 非法指标编码被拒（422）")
  void invalidMetricInputIsRejected() {
    UUID shopId = seedShop("BAD-1");

    assertThat(
            catchThrowable(
                () ->
                    as(
                        "channel.operation.create",
                        () ->
                            service.recordMetric(
                                shopId.toString(),
                                new AccountHealthMetricInput(
                                    "ODR", new BigDecimal("2.8"), PERIOD_START, PERIOD_END, "")))))
        .as("2.8 是百分数不是比值，必须被拒")
        .isNotNull();

    assertThat(
            catchThrowable(
                () ->
                    as(
                        "channel.operation.create",
                        () ->
                            service.recordMetric(
                                shopId.toString(),
                                new AccountHealthMetricInput(
                                    "NOT_A_METRIC", new BigDecimal("0.1"), PERIOD_START, PERIOD_END, "")))))
        .isNotNull();
  }

  // ==================================================================
  // 判定：量不到 ≠ 0、量不到 ≠ 低风险
  // ==================================================================

  @Test
  @DisplayName("16.1.1.1 🔴 指标不全 ⇒ 等级 UNKNOWN（不是 HEALTHY、不是低风险）")
  void missingMetricMakesLevelUnknownNotHealthy() {
    UUID shopId = seedShop("PARTIAL-1");
    // 只录 ODR，且值很好（0.0050 远低于阈值）
    var card = record(shopId, "ODR", "0.0050", PERIOD_START, PERIOD_END);

    assertThat(card.riskLevel()).isEqualTo("UNKNOWN");
    assertThat(card.knownMetricCount()).isEqualTo(1);
    assertThat(card.breachCount()).isZero();
    assertThat(card.partial()).isTrue();
    assertThat(card.unknownMetricCodes())
        .containsExactlyInAnyOrder("LATE_SHIPMENT_RATE", "CANCELLATION_RATE", "VALID_TRACKING_RATE");

    // 量不到的那三项必须各有一条依据，且说清「不算通过」
    assertThat(card.reasons())
        .filteredOn(reason -> reason.thresholdKind().equals("UNKNOWN"))
        .hasSize(3)
        .allSatisfy(reason -> assertThat(reason.message()).contains("量不到").contains("不算通过"));
  }

  @Test
  @DisplayName("16.1.1.1 四项齐全且都未命中阈值 ⇒ HEALTHY")
  void allFourMetricsWithinThresholdGivesHealthy() {
    UUID shopId = seedShop("HEALTHY-1");
    recordHealthy(shopId);

    var card = as("channel.operation.read", () -> service.shop(shopId.toString()));
    assertThat(card.riskLevel()).isEqualTo("HEALTHY");
    assertThat(card.knownMetricCount()).isEqualTo(4);
    assertThat(card.breachCount()).isZero();
    assertThat(card.partial()).isFalse();
    assertThat(card.reasons()).allSatisfy(reason -> assertThat(reason.thresholdKind()).isEqualTo("NONE"));
  }

  @Test
  @DisplayName("16.1.1.1 命中严重阈值 ⇒ CRITICAL + 逐条依据（哪一项、什么值、命中哪条阈值）")
  void breachingMetricGivesCriticalWithEvidence() {
    UUID shopId = seedShop("CRIT-1");
    recordHealthy(shopId);
    // ODR 5% 远超严重阈值 2.8%
    record(shopId, "ODR", "0.0500", PERIOD_START, PERIOD_END);

    var card = as("channel.operation.read", () -> service.shop(shopId.toString()));
    assertThat(card.riskLevel()).isEqualTo("CRITICAL");
    assertThat(card.breachCount()).isEqualTo(1);

    var reason =
        card.reasons().stream()
            .filter(r -> r.metricCode().equals("ODR"))
            .findFirst()
            .orElseThrow();
    assertThat(reason.level()).isEqualTo("CRITICAL");
    assertThat(reason.thresholdKind()).isEqualTo("CRITICAL");
    assertThat(reason.metricValue()).isEqualByComparingTo("0.0500");
    assertThat(reason.thresholdValue()).isEqualByComparingTo("0.0280");
    assertThat(reason.direction()).isEqualTo("ABOVE_IS_BAD");
    assertThat(reason.message()).contains("5.00%").contains("2.80%");
  }

  @Test
  @DisplayName("16.1.1.1 有效追踪率是「越小越危险」：95% ⇒ CRITICAL")
  void validTrackingRateUsesBelowIsBadDirection() {
    UUID shopId = seedShop("TRACK-1");
    recordHealthy(shopId);
    record(shopId, "VALID_TRACKING_RATE", "0.9000", PERIOD_START, PERIOD_END);

    var card = as("channel.operation.read", () -> service.shop(shopId.toString()));
    assertThat(card.riskLevel()).isEqualTo("CRITICAL");
    var reason =
        card.reasons().stream()
            .filter(r -> r.metricCode().equals("VALID_TRACKING_RATE"))
            .findFirst()
            .orElseThrow();
    assertThat(reason.direction()).isEqualTo("BELOW_IS_BAD");
    assertThat(reason.thresholdValue()).isEqualByComparingTo("0.9500");
  }

  // ==================================================================
  // 跨店铺风险排序
  // ==================================================================

  @Test
  @DisplayName("16.1.1.1 🔴 风险排序真的按风险排，且每一家都带得出「为什么排在这个位置」")
  void rankingSortsByRiskAndExplainsWhy() {
    UUID healthy = seedShop("RANK-HEALTHY");
    UUID critical = seedShop("RANK-CRITICAL");
    UUID warning = seedShop("RANK-WARNING");
    UUID unknown = seedShop("RANK-UNKNOWN");

    recordHealthy(healthy);
    recordHealthy(critical);
    record(critical, "ODR", "0.0900", PERIOD_START, PERIOD_END);
    recordHealthy(warning);
    record(warning, "CANCELLATION_RATE", "0.0300", PERIOD_START, PERIOD_END);
    record(unknown, "ODR", "0.0050", PERIOD_START, PERIOD_END);

    var ranking =
        as(
            "channel.operation.read",
            () -> service.ranking(null, null, null, null, 0, 50));

    List<String> order =
        ranking.items().stream().map(AccountHealthRankingRow::shopId).toList();
    assertThat(order)
        .as("严重 → 预警 → 判不了 → 健康")
        .startsWith(critical.toString(), warning.toString(), unknown.toString(), healthy.toString());

    assertThat(ranking.items().get(0).riskLevel()).isEqualTo("CRITICAL");
    assertThat(ranking.items().get(0).rank()).isEqualTo(1);
    assertThat(ranking.items().get(0).rankReason()).isNotBlank().contains("命中");
    assertThat(ranking.items().get(2).rankReason()).contains("判不了");

    assertThat(ranking.criticalCount()).isEqualTo(1);
    assertThat(ranking.warningCount()).isEqualTo(1);
    assertThat(ranking.unknownCount()).isEqualTo(1);
    assertThat(ranking.healthyCount()).isEqualTo(1);
    assertThat(ranking.total()).isEqualTo(4);
  }

  @Test
  @DisplayName("16.1.1.1 排序名次是全局名次（分页时第 2 页从第 11 位开始）")
  void rankingRanksAreGlobalAcrossPages() {
    for (int index = 0; index < 12; index++) {
      UUID shopId = seedShop("PAGE-" + index);
      recordHealthy(shopId);
      if (index < 3) {
        record(shopId, "ODR", "0.0900", PERIOD_START, PERIOD_END);
      }
    }
    var first = as("channel.operation.read", () -> service.ranking(null, null, null, null, 0, 5));
    var second = as("channel.operation.read", () -> service.ranking(null, null, null, null, 1, 5));

    assertThat(first.items()).hasSize(5);
    assertThat(second.items()).hasSize(5);
    assertThat(second.items().get(0).rank()).isEqualTo(6);
    assertThat(first.total()).isEqualTo(second.total()).isEqualTo(12);
  }

  // ==================================================================
  // 阈值：服务端算、可配置、有版本
  // ==================================================================

  @Test
  @DisplayName("16.1.1.1 阈值在服务端算：改阈值后等级随之变化（前端不重算）")
  void thresholdOverrideChangesLevelOnServer() {
    UUID shopId = seedShop("THRESH-1");
    recordHealthy(shopId);
    // ODR 1.0%：默认阈值下只是 WARNING（警告 1.0% / 严重 2.8%）
    record(shopId, "ODR", "0.0100", PERIOD_START, PERIOD_END);

    var before = as("channel.operation.read", () -> service.shop(shopId.toString()));
    assertThat(before.riskLevel()).isEqualTo("WARNING");

    var saved =
        as(
            "channel.operation.update",
            () ->
                service.saveThreshold(
                    "ODR",
                    new AccountHealthThresholdInput(
                        "ABOVE_IS_BAD", new BigDecimal("0.0050"), new BigDecimal("0.0080"), "收紧阈值")));
    assertThat(saved.ruleSource()).isEqualTo("TENANT_CUSTOM");
    assertThat(saved.ruleVersion()).as("租户第一次保存阈值 ⇒ 第一版").isEqualTo(1);

    var after = as("channel.operation.read", () -> service.shop(shopId.toString()));
    assertThat(after.riskLevel()).as("阈值收紧后同一条数据必须升级为 CRITICAL").isEqualTo("CRITICAL");
    assertThat(after.ruleSource())
        .as("只覆盖了 ODR 一项，其余三项还是内置默认 ⇒ 混合来源")
        .isEqualTo("MIXED");
    assertThat(after.ruleVersion()).isEqualTo(1);

    // 再改一次 ⇒ 版本前进（规则变更留版本）
    var savedAgain =
        as(
            "channel.operation.update",
            () ->
                service.saveThreshold(
                    "ODR",
                    new AccountHealthThresholdInput(
                        "ABOVE_IS_BAD", new BigDecimal("0.0040"), new BigDecimal("0.0070"), "再收紧")));
    assertThat(savedAgain.ruleVersion()).as("再改一次必须推进版本").isEqualTo(2);
  }

  @Test
  @DisplayName("16.1.1.1 🔴 规则变更不追溯改变历史判定（判定凭据带阈值快照 + append-only）")
  void ruleChangeDoesNotRewriteHistoricalVerdict() {
    UUID shopId = seedShop("FROZEN-1");
    recordHealthy(shopId);
    record(shopId, "ODR", "0.0100", PERIOD_START, PERIOD_END);

    var verdict = as("channel.operation.create", () -> service.assess(shopId.toString()));
    assertThat(verdict.riskLevel()).isEqualTo("WARNING");
    assertThat(verdict.ruleVersion()).isEqualTo(1);

    // 改阈值（推进版本）
    as(
        "channel.operation.update",
        () ->
            service.saveThreshold(
                "ODR",
                new AccountHealthThresholdInput(
                    "ABOVE_IS_BAD", new BigDecimal("0.0001"), new BigDecimal("0.0002"), "改严")));

    // 🔴 历史凭据里的阈值快照与等级**原样不变**
    assertThat(scalar("select risk_level from account_health_assessment where id='" + verdict.assessmentId() + "'"))
        .isEqualTo("WARNING");
    assertThat(scalar("select rule_version from account_health_assessment where id='" + verdict.assessmentId() + "'"))
        .isEqualTo("1");
    assertThat(scalar("select thresholds::text from account_health_assessment where id='" + verdict.assessmentId() + "'"))
        .contains("0.0100")
        .contains("0.0280");

    // 🔴 判定凭据不可改写（DB 触发器真的拒）
    assertThat(
            catchThrowable(
                () ->
                    execAsTenant(
                        tenant,
                        "update account_health_assessment set risk_level='HEALTHY' where id='"
                            + verdict.assessmentId()
                            + "'")))
        .as("判定凭据是追加式的，事后改写必须被 DB 拒绝")
        .isNotNull();
    assertThat(scalar("select risk_level from account_health_assessment where id='" + verdict.assessmentId() + "'"))
        .isEqualTo("WARNING");

    // 🔴 判定凭据不可删除
    assertThat(
            catchThrowable(
                () ->
                    execAsTenant(
                        tenant,
                        "delete from account_health_assessment where id='"
                            + verdict.assessmentId()
                            + "'")))
        .isNotNull();
  }

  @Test
  @DisplayName("16.1.1.1 🔴 改阈值不推进版本 ⇒ DB 拒绝（规则变更必须留版本）")
  void thresholdChangeWithoutVersionBumpIsRejected() {
    UUID shopId = seedShop("VERSION-1");
    record(shopId, "ODR", "0.0100", PERIOD_START, PERIOD_END);
    as(
        "channel.operation.update",
        () ->
            service.saveThreshold(
                "ODR",
                new AccountHealthThresholdInput(
                    "ABOVE_IS_BAD", new BigDecimal("0.0200"), new BigDecimal("0.0300"), "第一版")));

    assertThat(
            catchThrowable(
                () ->
                    execAsTenant(
                        tenant,
                        "update account_health_threshold set warn_value=0.0300, critical_value=0.0400"
                            + " where tenant_id='"
                            + tenant
                            + "' and metric_code='ODR'")))
        .as("改值不推进 rule_version 必须被 DB 拒绝")
        .isNotNull();

    assertThat(
            scalar(
                "select warn_value from account_health_threshold where tenant_id='"
                    + tenant
                    + "' and metric_code='ODR'"))
        .isEqualTo("0.0200");
  }

  @Test
  @DisplayName("16.1.1.1 阈值清单：内置默认 4 条且标 BUILTIN_DEFAULT")
  void thresholdsExposeBuiltinDefaults() {
    var list = as("channel.operation.read", () -> service.thresholds());
    assertThat(list.items()).hasSize(4);
    assertThat(list.items()).allSatisfy(view -> assertThat(view.builtin()).isTrue());
    assertThat(list.items()).extracting(AccountHealthThresholdView::ruleSource)
        .containsOnly("BUILTIN_DEFAULT");
    assertThat(list.note()).contains("服务端");
  }

  // ==================================================================
  // 店铺目录与需处理项
  // ==================================================================

  @Test
  @DisplayName("16.1.1.1 店铺清单来自真实店铺目录表（新增一家店就出现在卡片里）")
  void shopDirectoryComesFromRealShopTable() {
    UUID shopId = seedShop("REAL-SHOP");
    record(shopId, "ODR", "0.0050", PERIOD_START, PERIOD_END);

    var page = as("channel.operation.read", () -> service.shops(null, null, null, null, null, 0, 50));
    assertThat(page.items()).extracting(AccountHealthShopCard::shopId).contains(shopId.toString());
    assertThat(page.items()).extracting(AccountHealthShopCard::shopCode).contains("REAL-SHOP");
    assertThat(page.items()).extracting(AccountHealthShopCard::platformCode).contains("AMAZON");
  }

  @Test
  @DisplayName("16.1.1.1 需处理项只给真实存在的待处理项，跳转目标是本模块自己的页面")
  void pendingItemsOnlyPointAtRealTargets() {
    UUID shopId = seedShop("PENDING-1");
    record(shopId, "ODR", "0.0900", PERIOD_START, PERIOD_END);

    var pending = as("channel.operation.read", () -> service.pendingItems(null, null));
    assertThat(pending.items()).isNotEmpty();
    assertThat(pending.items())
        .allSatisfy(
            item -> {
              assertThat(item.targetPath()).startsWith("/compliance/account");
              assertThat(item.targetModule()).isIn("16.1.1.1", "2.4");
              assertThat(item.description()).isNotBlank();
            });
    // 🔴 本模块**未接入**的违规 / 申诉**不许**出现在待处理项里（那两维没有数据）
    assertThat(pending.items())
        .allSatisfy(item -> assertThat(item.label()).doesNotContain("违规").doesNotContain("申诉"));
    assertThat(pending.items())
        .extracting(AccountHealthPendingItem::kind)
        .contains("RISK_BREACH", "PLATFORM_SYNC_UNAVAILABLE");
  }

  @Test
  @DisplayName("16.1.1.1 指标行的店铺必须真实存在（跨表引用由触发器校验，不用外键）")
  void metricForUnknownShopIsRejected() {
    assertThat(
            catchThrowable(
                () ->
                    as(
                        "channel.operation.create",
                        () ->
                            service.recordMetric(
                                UUID.randomUUID().toString(),
                                new AccountHealthMetricInput(
                                    "ODR", new BigDecimal("0.01"), PERIOD_START, PERIOD_END, "")))))
        .as("不存在的店铺必须被拒（触发器 guard_account_health_shop_ref）")
        .isNotNull();
  }

  // ==================================================================
  // HTTP 端点
  // ==================================================================

  @Test
  @DisplayName("16.1.1.1 HTTP 端点：真分页 + 真筛选 + 能力声明")
  void httpEndpointsSupportRealPaginationAndFilters() throws Exception {
    UUID critical = seedShop("HTTP-CRIT");
    UUID healthy = seedShop("HTTP-HEALTHY");
    recordHealthy(healthy);
    recordHealthy(critical);
    record(critical, "ODR", "0.0900", PERIOD_START, PERIOD_END);

    http.perform(get("/channel/account-health/capabilities").header("Authorization", bearer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.gaps", org.hamcrest.Matchers.hasSize(4)))
        .andExpect(jsonPath("$.gaps[?(@.available==false)]", org.hamcrest.Matchers.hasSize(4)))
        .andExpect(jsonPath("$.gaps[0].ownerModule").isNotEmpty());

    http.perform(
            get("/channel/account-health/shops")
                .header("Authorization", bearer)
                .param("size", "1")
                .param("page", "0"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.size").value(1));

    http.perform(
            get("/channel/account-health/shops")
                .header("Authorization", bearer)
                .param("riskLevel", "CRITICAL"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.items[0].shopId").value(critical.toString()))
        .andExpect(jsonPath("$.items[0].riskLevel").value("CRITICAL"));

    http.perform(
            get("/channel/account-health/ranking")
                .header("Authorization", bearer)
                .param("keyword", "HTTP-CRIT"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.items[0].rank").value(1))
        .andExpect(jsonPath("$.items[0].rankReason").isNotEmpty());

    http.perform(get("/channel/account-health/shops/" + healthy).header("Authorization", bearer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.shopId").value(healthy.toString()))
        .andExpect(jsonPath("$.metrics.length()").value(4));

    // 跨租户 / 不存在 ⇒ 逐字相同的 404
    http.perform(get("/channel/account-health/shops/" + UUID.randomUUID()).header("Authorization", bearer))
        .andExpect(status().isNotFound());

    http.perform(get("/channel/account-health/thresholds").header("Authorization", bearer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(4));

    http.perform(
            post("/channel/account-health/shops/" + healthy + "/assess")
                .header("Authorization", bearer)
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.assessmentId").isNotEmpty())
        .andExpect(jsonPath("$.reasons.length()").value(4));

    http.perform(get("/channel/account-health/assessments").header("Authorization", bearer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1));
  }

  @Test
  @DisplayName("16.1.1.1 手工录入走 HTTP 也是 MANUAL，且写入的是真实数据")
  void httpManualMetricEntryPersistsRealData() throws Exception {
    UUID shopId = seedShop("HTTP-MANUAL");

    http.perform(
            post("/channel/account-health/shops/" + shopId + "/metrics")
                .header("Authorization", bearer)
                .contentType("application/json")
                .content(
                    "{\"metricCode\":\"ODR\",\"metricValue\":0.0300,\"periodStart\":\""
                        + PERIOD_START
                        + "\",\"periodEnd\":\""
                        + PERIOD_END
                        + "\",\"note\":\"人工核对后台导出\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.metrics[?(@.metricCode=='ODR')].source").value("MANUAL"));

    assertThat(
            scalar(
                "select note from account_health_metric where shop_id='"
                    + shopId
                    + "' and metric_code='ODR'"))
        .isEqualTo("人工核对后台导出");
  }
}
