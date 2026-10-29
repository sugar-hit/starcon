package com.starcon.channel;

import static com.starcon.channel.PerformanceMetricModels.*;
import static com.starcon.channel.PerformanceMetricRules.*;
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
 * 16.1.1.2 绩效指标监控 的集成测试（**真实 PostgreSQL 16**）。
 *
 * <p>🔴 这组用例守的是本模块最容易做丢的九件事：
 *
 * <ol>
 *   <li><b>双来源如实表达</b>：四个码来自 16.1.1.1 的表、两个码来自本模块自己的表，
 *       每条序列标注来源；</li>
 *   <li><b>趋势是区间口径</b>：一个点 = 一个区间，不是瞬时点；</li>
 *   <li><b>量不到 ≠ 0</b>：NULL 的点不进已知数、均值分母写清楚；</li>
 *   <li><b>环比不可知</b>：上一个周期量不到 ⇒ 环比为 null 并说明原因；</li>
 *   <li><b>预警凭据带阈值快照</b>：改阈值**不追溯改变历史预警**；</li>
 *   <li><b>改阈值必须推进版本</b>（DB 触发器钉住）；</li>
 *   <li><b>凭据 append-only</b>：UPDATE / DELETE 被 DB 拒；</li>
 *   <li><b>预测不许造假</b>：点数不足 ⇒ CANNOT_PREDICT + 原因，落库可查；</li>
 *   <li><b>时间字段往返正确</b>：timestamptz 写回不是 −28800s。</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class PerformanceMetricIntegrationTest {

  static final PostgreSQLContainer<?> pg =
      new PostgreSQLContainer<>("postgres:16-alpine").withUrlParam("sslmode", "disable");
  static final UUID user = UUID.randomUUID();
  static HttpServer identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static final ObjectMapper JSON = new ObjectMapper();

  static final UUID AMAZON = UUID.fromString("11111111-1111-1111-1111-111111111101");

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

  @Autowired PerformanceMetricService service;
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

  /** 🔴 直接往**上游 16.1.1.1 的表**里写一条指标（value 传 null 即「量不到」）。 */
  void seedUpstreamMetric(
      UUID shopId, String code, String value, String periodStart, String periodEnd) {
    execAsTenant(
        tenant,
        "insert into account_health_metric"
            + " (id, tenant_id, shop_id, period_start, period_end, metric_code, metric_value,"
            + "  source, captured_at, recorded_by, note, created_at, updated_at)"
            + " values (gen_random_uuid(), '"
            + tenant
            + "','"
            + shopId
            + "','"
            + periodStart
            + "','"
            + periodEnd
            + "','"
            + code
            + "',"
            + (value == null ? "null" : value)
            + ", 'MANUAL', now(), null, '集成测试录入', now(), now())");
  }

  // ==================================================================
  // 能力声明
  // ==================================================================

  @Test
  @DisplayName("16.1.1.2 能力声明：两个未交付下游 available=false + ownerModule；双来源如实表达")
  void capabilitiesDeclareUndeliveredDownstreamsAndTwoSources() {
    var caps = as("channel.operation.read", () -> service.capabilities());

    assertThat(caps.gaps())
        .allSatisfy(
            gap -> {
              assertThat(gap.available()).as("%s 未交付必须标 available=false", gap.moduleId()).isFalse();
              assertThat(gap.ownerModule()).as("%s 必须写清归谁", gap.moduleId()).isNotBlank();
              assertThat(gap.impact()).as("%s 必须说清影响", gap.moduleId()).isNotBlank();
            });
    assertThat(caps.gaps()).extracting(PerformanceMetricCapabilityGap::moduleId).contains("1", "9");

    // 🔴 六项指标分别归谁、数据来自哪张表，必须在声明里写清。
    assertThat(caps.metricCatalog()).hasSize(6);
    for (PerformanceMetricDescriptor descriptor : caps.metricCatalog()) {
      if (UPSTREAM_METRIC_CODES.contains(descriptor.metricCode())) {
        assertThat(descriptor.sourceModule()).as("上游四码必须标出来自 16.1.1.1").contains("16.1.1.1");
        assertThat(descriptor.ownerModule()).isEqualTo("16.1.1.1");
        assertThat(descriptor.unit()).isEqualTo(UNIT_RATIO);
      } else {
        assertThat(descriptor.sourceModule()).as("本模块两码必须标出来自本模块").contains("16.1.1.2");
        assertThat(descriptor.ownerModule()).isEqualTo("16.1.1.2");
      }
      assertThat(descriptor.note()).isNotBlank();
    }

    assertThat(caps.granularitySemantics()).contains("区间口径").contains("不是瞬时点");
    assertThat(caps.unknownSemantics()).contains("不是 0").contains("不算通过");
    assertThat(caps.thresholdSemantics()).contains("不追溯改变历史预警");
    assertThat(caps.forecastSemantics()).contains("最小二乘线性回归").contains("无法预测");
    assertThat(caps.sourceNote()).contains("account_health_metric").contains("account_health_performance_metric");
  }

  // ==================================================================
  // 趋势：双来源 + 区间口径 + 量不到
  // ==================================================================

  @Test
  @DisplayName("16.1.1.2 趋势：双来源都读得到、区间口径、量不到的点是「未知」不是 0")
  void trendReadsBothSourcesAndKeepsIntervalGranularity() {
    UUID shopId = seedShop("TREND-1");
    seedUpstreamMetric(shopId, "ODR", "0.0100", "2026-07-01", "2026-07-31");
    seedUpstreamMetric(shopId, "ODR", null, "2026-08-01", "2026-08-31");
    seedUpstreamMetric(shopId, "ODR", "0.0300", "2026-09-01", "2026-09-30");

    var own =
        as(
            "channel.operation.create",
            () ->
                service.recordMetric(
                    shopId.toString(),
                    new PerformanceMetricMetricInput(
                        "RESPONSE_TIME", new BigDecimal("24"), "2026-07-01", "2026-07-31", "手工录入")));
    assertThat(own.metricCode()).isEqualTo("RESPONSE_TIME");
    assertThat(own.sourceModule()).contains("16.1.1.2");

    var page = as("channel.operation.read", () -> service.trends(null, null, null, null, null, 0, 50));

    var odr =
        page.items().stream()
            .filter(item -> item.metricCode().equals("ODR"))
            .findFirst()
            .orElseThrow();
    assertThat(odr.sourceModule()).as("上游四码必须标出来自 16.1.1.1").contains("16.1.1.1");
    assertThat(odr.granularity()).isEqualTo("PERIOD_RANGE");
    assertThat(odr.points()).hasSize(3);
    // 🔴 区间口径：每个点都带 period_start ~ period_end，**不是一个瞬时点**。
    assertThat(odr.points().get(0).periodStart()).isEqualTo("2026-07-01");
    assertThat(odr.points().get(0).periodEnd()).isEqualTo("2026-07-31");
    // 🔴 量不到的那个点：value 为 null、known=false（**不是 0**）。
    var unknown = odr.points().get(1);
    assertThat(unknown.known()).isFalse();
    assertThat(unknown.value()).isNull();
    assertThat(unknown.periodEnd()).isEqualTo("2026-08-31");
    // 均值只用量到的点：分母 = 2（不是 3），且分母写清楚。
    assertThat(odr.average()).isEqualByComparingTo("0.0200");
    assertThat(odr.averageNote()).contains("2 个周期").contains("1 个量不到");
    assertThat(odr.knownPointCount()).isEqualTo(2);
    assertThat(odr.unknownPointCount()).isEqualTo(1);

    var responseTime =
        page.items().stream()
            .filter(item -> item.metricCode().equals("RESPONSE_TIME"))
            .findFirst()
            .orElseThrow();
    assertThat(responseTime.sourceModule()).contains("16.1.1.2");
    assertThat(responseTime.unit()).isEqualTo(UNIT_HOURS);
    assertThat(responseTime.points().get(0).value()).isEqualByComparingTo("24");
  }

  @Test
  @DisplayName("16.1.1.2 环比：上一个周期量不到 ⇒ 不可知（null + 说明）")
  void changeIsUnknowableWhenPreviousPeriodIsUnmeasurable() {
    UUID shopId = seedShop("TREND-2");
    seedUpstreamMetric(shopId, "LATE_SHIPMENT_RATE", null, "2026-08-01", "2026-08-31");
    seedUpstreamMetric(shopId, "LATE_SHIPMENT_RATE", "0.0500", "2026-09-01", "2026-09-30");

    var page = as("channel.operation.read", () -> service.trends(null, null, null, null, null, 0, 50));
    var series =
        page.items().stream()
            .filter(item -> item.metricCode().equals("LATE_SHIPMENT_RATE"))
            .findFirst()
            .orElseThrow();

    assertThat(series.latestChange()).as("上一个周期量不到 ⇒ 环比不可知").isNull();
    assertThat(series.changeNote()).contains("不可知");
    assertThat(series.latestValue()).isEqualByComparingTo("0.0500");
  }

  // ==================================================================
  // 预警：凭据 + 阈值快照 + 版本
  // ==================================================================

  @Test
  @DisplayName("16.1.1.2 预警评估：命中阈值落凭据并冻住阈值快照；量不到 ⇒ UNKNOWN；未命中不落行")
  void alertEvaluationPersistsSnapshotAndUnknown() {
    UUID shopId = seedShop("ALERT-1");
    seedUpstreamMetric(shopId, "ODR", "0.0300", "2026-09-01", "2026-09-30");

    var evaluation = as("channel.operation.create", () -> service.evaluateAlerts(shopId.toString()));

    assertThat(evaluation.evaluatedMetricCount()).isEqualTo(6);
    assertThat(evaluation.knownMetricCount()).isEqualTo(1);
    assertThat(evaluation.unknownMetricCount()).isEqualTo(5);

    var odr =
        evaluation.alerts().stream()
            .filter(alert -> alert.metricCode().equals("ODR"))
            .findFirst()
            .orElseThrow();
    assertThat(odr.severity()).isEqualTo(SEVERITY_CRITICAL);
    assertThat(odr.thresholdKind()).isEqualTo(THRESHOLD_CRITICAL);
    assertThat(odr.thresholdValue()).isEqualByComparingTo("0.0280");
    assertThat(odr.ruleSource()).isEqualTo(RULE_SOURCE_BUILTIN);
    // 🔴 阈值快照冻在凭据里。
    assertThat(odr.thresholds()).containsKey("ODR");
    assertThat(odr.thresholds().get("ODR")).isInstanceOf(Map.class);
    @SuppressWarnings("unchecked")
    Map<String, Object> snapshot = (Map<String, Object>) odr.thresholds().get("ODR");
    assertThat(snapshot.get("criticalValue")).isNotNull();

    // 🔴 量不到的指标 ⇒ UNKNOWN 凭据（量不到 ≠ 通过）。
    var unknown =
        evaluation.alerts().stream()
            .filter(alert -> alert.metricCode().equals("CANCELLATION_RATE"))
            .findFirst()
            .orElseThrow();
    assertThat(unknown.severity()).isEqualTo(SEVERITY_UNKNOWN);
    assertThat(unknown.metricValue()).isNull();
    assertThat(unknown.thresholdValue()).isNull();

    // 落库可查。
    assertThat(
            countRows(
                "select count(*) from account_health_performance_alert where tenant_id='"
                    + tenant
                    + "' and shop_id='"
                    + shopId
                    + "'"))
        .isEqualTo(6);
    assertThat(
            scalar(
                "select thresholds::text from account_health_performance_alert where tenant_id='"
                    + tenant
                    + "' and metric_code='ODR' and severity='CRITICAL'"))
        .contains("0.0280");
  }

  @Test
  @DisplayName("16.1.1.2 已知且未命中阈值 ⇒ 不产预警凭据")
  void knownAndHealthyProducesNoAlertCredential() {
    UUID shopId = seedShop("ALERT-2");
    seedUpstreamMetric(shopId, "ODR", "0.0050", "2026-09-01", "2026-09-30");

    var evaluation = as("channel.operation.create", () -> service.evaluateAlerts(shopId.toString()));

    assertThat(evaluation.alerts())
        .noneSatisfy(alert -> assertThat(alert.metricCode()).isEqualTo("ODR"));
    assertThat(
            countRows(
                "select count(*) from account_health_performance_alert where tenant_id='"
                    + tenant
                    + "' and shop_id='"
                    + shopId
                    + "' and metric_code='ODR'"))
        .isZero();
  }

  @Test
  @DisplayName("16.1.1.2 改阈值不追溯改变历史预警（凭据里的快照还是旧的）")
  void changingThresholdDoesNotRewriteHistoricalAlerts() {
    UUID shopId = seedShop("ALERT-3");
    seedUpstreamMetric(shopId, "ODR", "0.0200", "2026-09-01", "2026-09-30");

    var before = as("channel.operation.create", () -> service.evaluateAlerts(shopId.toString()));
    var oldAlert =
        before.alerts().stream()
            .filter(alert -> alert.metricCode().equals("ODR"))
            .findFirst()
            .orElseThrow();
    assertThat(oldAlert.severity()).isEqualTo(SEVERITY_WARNING);
    assertThat(oldAlert.ruleVersion()).isEqualTo(1L);

    // 改阈值（服务端行为，推进版本）：第一次保存建规则、第二次保存才推进版本。
    as(
        "channel.operation.update",
        () ->
            service.saveAlertRule(
                "ODR",
                new PerformanceMetricAlertRuleInput(
                    "ABOVE_IS_BAD", new BigDecimal("0.0250"), new BigDecimal("0.0300"), "收紧 ODR 阈值")));
    var rule =
        as(
            "channel.operation.update",
            () ->
                service.saveAlertRule(
                    "ODR",
                    new PerformanceMetricAlertRuleInput(
                        "ABOVE_IS_BAD", new BigDecimal("0.0250"), new BigDecimal("0.0300"), "再改一次，推进版本")));
    assertThat(rule.ruleVersion()).isEqualTo(2L);
    assertThat(rule.ruleSource()).isEqualTo(RULE_SOURCE_CUSTOM);

    // 🔴 历史预警**没有被追溯改变**：快照、版本与来源仍然是旧的（内置默认第 1 版）。
    var after = as("channel.operation.read", () -> service.alerts(null, null, null, 0, 50));
    var oldAgain =
        after.items().stream()
            .filter(alert -> alert.alertId().equals(oldAlert.alertId()))
            .findFirst()
            .orElseThrow();
    assertThat(oldAgain.severity()).isEqualTo(SEVERITY_WARNING);
    assertThat(oldAgain.ruleVersion()).isEqualTo(1L);
    assertThat(oldAgain.ruleSource()).isEqualTo(RULE_SOURCE_BUILTIN);
    assertThat(oldAgain.thresholdValue()).isEqualByComparingTo("0.0100");
    @SuppressWarnings("unchecked")
    Map<String, Object> snapshot = (Map<String, Object>) oldAgain.thresholds().get("ODR");
    assertThat(snapshot.get("criticalValue").toString()).contains("0.028");

    // 新阈值下再评估一次：0.0200 已经不算命中 ⇒ 新一次评估不产 ODR 预警。
    var reevaluated = as("channel.operation.create", () -> service.evaluateAlerts(shopId.toString()));
    assertThat(reevaluated.alerts())
        .noneSatisfy(alert -> assertThat(alert.metricCode()).isEqualTo("ODR"));
  }

  @Test
  @DisplayName("16.1.1.2 改阈值不推进 rule_version ⇒ DB 拒绝；版本不许回退")
  void alertRuleVersionIsGuarded() {
    UUID shopId = seedShop("ALERT-4");
    as(
        "channel.operation.update",
        () ->
            service.saveAlertRule(
                "ODR",
                new PerformanceMetricAlertRuleInput(
                    "ABOVE_IS_BAD", new BigDecimal("0.0100"), new BigDecimal("0.0280"), "初始")));

    // 不推进版本就改值 ⇒ 被触发器拒
    var thrown =
        catchThrowable(
            () ->
                execAsTenant(
                    tenant,
                    "update account_health_performance_alert_rule set warn_value = 0.0120"
                        + " where tenant_id='"
                        + tenant
                        + "' and metric_code='ODR'"));
    assertThat(thrown).as("改值不推进版本必须被 DB 拒绝").isNotNull();
    assertThat(thrown.getMessage()).contains("rule_version");

    // 版本回退 ⇒ 被触发器拒
    var backwards =
        catchThrowable(
            () ->
                execAsTenant(
                    tenant,
                    "update account_health_performance_alert_rule set rule_version = 0, warn_value = 0.0130"
                        + " where tenant_id='"
                        + tenant
                        + "' and metric_code='ODR'"));
    assertThat(backwards).as("版本回退必须被 DB 拒绝").isNotNull();
    assertThat(backwards.getMessage()).contains("backwards");
  }

  @Test
  @DisplayName("16.1.1.2 预警凭据 append-only：UPDATE / DELETE 被 DB 拒")
  void alertCredentialsAreAppendOnly() {
    UUID shopId = seedShop("ALERT-5");
    seedUpstreamMetric(shopId, "ODR", "0.0300", "2026-09-01", "2026-09-30");
    as("channel.operation.create", () -> service.evaluateAlerts(shopId.toString()));

    var update =
        catchThrowable(
            () ->
                execAsTenant(
                    tenant,
                    "update account_health_performance_alert set severity = 'WARNING'"
                        + " where tenant_id='"
                        + tenant
                        + "'"));
    assertThat(update).as("凭据不可改写").isNotNull();
    assertThat(update.getMessage()).contains("append-only");

    var delete =
        catchThrowable(
            () ->
                execAsTenant(
                    tenant, "delete from account_health_performance_alert where tenant_id='" + tenant + "'"));
    assertThat(delete).as("凭据不可删除").isNotNull();
    assertThat(delete.getMessage()).contains("append-only");
  }

  // ==================================================================
  // 预测
  // ==================================================================

  @Test
  @DisplayName("16.1.1.2 点数不足 ⇒ CANNOT_PREDICT + 原因，落库可查（绝不给假预测）")
  void forecastCannotPredictIsPersistedWithReason() {
    UUID shopId = seedShop("FC-1");
    seedUpstreamMetric(shopId, "ODR", "0.0100", "2026-09-01", "2026-09-30");

    var run = as("channel.operation.create", () -> service.forecast(shopId.toString(), null));

    var odr =
        run.results().stream()
            .filter(item -> item.metricCode().equals("ODR"))
            .findFirst()
            .orElseThrow();
    assertThat(odr.result()).isEqualTo(RESULT_CANNOT_PREDICT);
    assertThat(odr.resultReason()).contains("已知点数不足");
    assertThat(odr.knownPoints()).isEqualTo(1);
    assertThat(odr.predictedValue()).isNull();
    assertThat(odr.breachInPeriods()).isNull();
    assertThat(odr.method()).isEqualTo(METHOD_LINEAR);
    assertThat(odr.methodNote()).contains("最小二乘线性回归");

    assertThat(
            scalar(
                "select result from account_health_performance_forecast where tenant_id='"
                    + tenant
                    + "' and shop_id='"
                    + shopId
                    + "' and metric_code='ODR'"))
        .isEqualTo(RESULT_CANNOT_PREDICT);
    assertThat(
            scalar(
                "select result_reason from account_health_performance_forecast where tenant_id='"
                    + tenant
                    + "' and shop_id='"
                    + shopId
                    + "' and metric_code='ODR'"))
        .contains("已知点数不足");
  }

  @Test
  @DisplayName("16.1.1.2 上涨趋势 ⇒ WILL_BREACH，凭据里写明第几个周期与用到的周期")
  void forecastPredictsBreachAndFreezesTheMethod() {
    UUID shopId = seedShop("FC-2");
    seedUpstreamMetric(shopId, "ODR", "0.0100", "2026-07-01", "2026-07-31");
    seedUpstreamMetric(shopId, "ODR", "0.0150", "2026-08-01", "2026-08-31");
    seedUpstreamMetric(shopId, "ODR", "0.0200", "2026-09-01", "2026-09-30");
    as(
        "channel.operation.update",
        () ->
            service.saveAlertRule(
                "ODR",
                new PerformanceMetricAlertRuleInput(
                    "ABOVE_IS_BAD", new BigDecimal("0.0300"), new BigDecimal("0.0400"), "预测用阈值")));

    var run = as("channel.operation.create", () -> service.forecast(shopId.toString(), null));

    var odr =
        run.results().stream()
            .filter(item -> item.metricCode().equals("ODR"))
            .findFirst()
            .orElseThrow();
    assertThat(odr.result()).isEqualTo(RESULT_WILL_BREACH);
    assertThat(odr.breachLevel()).isEqualTo(THRESHOLD_WARN);
    assertThat(odr.breachInPeriods()).isEqualTo(2);
    assertThat(odr.predictedValue()).isEqualByComparingTo("0.030000");
    assertThat(odr.thresholdValue()).isEqualByComparingTo("0.030000");
    assertThat(odr.knownPoints()).isEqualTo(3);
    assertThat(odr.usedPeriods()).hasSize(3);
    assertThat(odr.slope()).isNotNull();
    assertThat(odr.ruleSource()).isEqualTo(RULE_SOURCE_CUSTOM);
    assertThat(odr.thresholds()).containsKey("ODR");

    // 凭据落库后仍然可查。
    var page = as("channel.operation.read", () -> service.forecasts(null, null, null, 0, 50));
    assertThat(page.items())
        .anySatisfy(
            item -> {
              assertThat(item.metricCode()).isEqualTo("ODR");
              assertThat(item.result()).isEqualTo(RESULT_WILL_BREACH);
              assertThat(item.breachInPeriods()).isEqualTo(2);
            });
  }

  @Test
  @DisplayName("16.1.1.2 预测凭据 append-only：UPDATE / DELETE 被 DB 拒")
  void forecastCredentialsAreAppendOnly() {
    UUID shopId = seedShop("FC-3");
    seedUpstreamMetric(shopId, "ODR", "0.0100", "2026-09-01", "2026-09-30");
    as("channel.operation.create", () -> service.forecast(shopId.toString(), null));

    var update =
        catchThrowable(
            () ->
                execAsTenant(
                    tenant,
                    "update account_health_performance_forecast set result = 'NO_BREACH'"
                        + " where tenant_id='"
                        + tenant
                        + "'"));
    assertThat(update).as("预测凭据不可改写").isNotNull();
    assertThat(update.getMessage()).contains("append-only");

    var delete =
        catchThrowable(
            () ->
                execAsTenant(
                    tenant,
                    "delete from account_health_performance_forecast where tenant_id='" + tenant + "'"));
    assertThat(delete).as("预测凭据不可删除").isNotNull();
    assertThat(delete.getMessage()).contains("append-only");
  }

  // ==================================================================
  // 本模块自有指标录入
  // ==================================================================

  @Test
  @DisplayName("16.1.1.2 本模块自有指标：source 恒 MANUAL，timestamptz 往返不是 −28800s")
  void ownMetricIsAlwaysManualAndTimestampRoundTrips() throws Exception {
    UUID shopId = seedShop("OWN-1");

    var series =
        as(
            "channel.operation.create",
            () ->
                service.recordMetric(
                    shopId.toString(),
                    new PerformanceMetricMetricInput(
                        "ACCOUNT_RATING", new BigDecimal("4.5"), "2026-09-01", "2026-09-30", "手工录入")));

    assertThat(series.points()).hasSize(1);
    assertThat(series.points().get(0).source()).isEqualTo(SOURCE_MANUAL);
    assertThat(series.points().get(0).sourceLabel()).isEqualTo("人工录入");
    assertThat(series.points().get(0).value()).isEqualByComparingTo("4.5");
    assertThat(
            scalar(
                "select source from account_health_performance_metric where tenant_id='"
                    + tenant
                    + "' and shop_id='"
                    + shopId
                    + "'"))
        .isEqualTo("MANUAL");

    // 🔴 时间往返：写回的值必须和 DB 时钟**同一个时点**，不是被偏了 8 小时。
    try (Connection c =
            DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement();
        var rs =
            s.executeQuery(
                "select captured_at, now(), abs(extract(epoch from (captured_at - now())))"
                    + " from account_health_performance_metric where tenant_id='"
                    + tenant
                    + "' and shop_id='"
                    + shopId
                    + "'")) {
      assertThat(rs.next()).isTrue();
      OffsetDateTime captured = rs.getObject("captured_at", OffsetDateTime.class);
      OffsetDateTime dbNow = rs.getObject("now", OffsetDateTime.class);
      double driftSeconds = rs.getDouble(3);
      assertThat(captured).isNotNull();
      assertThat(driftSeconds)
          .as("captured_at 与 DB 时钟的偏差必须极小（用 Timestamp.valueOf 会偏 28800 秒）")
          .isLessThan(60.0);
      assertThat(Math.abs(captured.toInstant().getEpochSecond() - dbNow.toInstant().getEpochSecond()))
          .isLessThan(60L);
    }
  }

  @Test
  @DisplayName("16.1.1.2 上游四个码不接受在本模块录入（它们归 16.1.1.1）")
  void upstreamMetricCodesAreRejectedHere() {
    UUID shopId = seedShop("OWN-2");

    var thrown =
        catchThrowable(
            () ->
                as(
                    "channel.operation.create",
                    () ->
                        service.recordMetric(
                            shopId.toString(),
                            new PerformanceMetricMetricInput(
                                "ODR", new BigDecimal("0.02"), "2026-09-01", "2026-09-30", "越界录入"))));
    assertThat(thrown).isNotNull();
    assertThat(thrown.getMessage()).contains("16.1.1.1");
  }

  // ==================================================================
  // HTTP：真分页、真筛选、404
  // ==================================================================

  @Test
  @DisplayName("16.1.1.2 HTTP 真分页真筛选；不存在的店铺 404")
  void httpPaginationFilteringAndNotFound() throws Exception {
    UUID shopA = seedShop("HTTP-A");
    UUID shopB = seedShop("HTTP-B");
    seedUpstreamMetric(shopA, "ODR", "0.0300", "2026-09-01", "2026-09-30");
    seedUpstreamMetric(shopB, "ODR", "0.0300", "2026-09-01", "2026-09-30");

    http.perform(get("/channel/performance-metrics/trends").param("page", "0").param("size", "1")
            .header("Authorization", bearer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.size").value(1))
        .andExpect(jsonPath("$.total").value(2));

    http.perform(get("/channel/performance-metrics/trends").param("shopId", shopA.toString())
            .header("Authorization", bearer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.items[0].shopId").value(shopA.toString()));

    // 预警：跑一次再按严重程度筛。
    http.perform(post("/channel/performance-metrics/shops/" + shopA + "/alert-evaluations")
            .header("Authorization", bearer)
            .contentType("application/json")
            .content("{}"))
        .andExpect(status().isOk());
    http.perform(get("/channel/performance-metrics/alerts").param("severity", "CRITICAL")
            .param("shopId", shopA.toString()).header("Authorization", bearer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.items[0].metricCode").value("ODR"))
        .andExpect(jsonPath("$.items[0].thresholds.ODR").exists());

    http.perform(get("/channel/performance-metrics/alerts").param("severity", "NOPE")
            .header("Authorization", bearer))
        .andExpect(status().isUnprocessableEntity());

    // 不存在的店铺 ⇒ 404（跨租户与不存在逐字相同）
    http.perform(get("/channel/performance-metrics/shops/" + UUID.randomUUID() + "/trends")
            .header("Authorization", bearer))
        .andExpect(status().isNotFound());

    // 无会话 ⇒ 401
    http.perform(get("/channel/performance-metrics/capabilities"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("16.1.1.2 店铺清单来自真实店铺目录，不存在的店铺被拒")
  void shopListComesFromRealShopDirectory() {
    UUID shopId = seedShop("REAL-1");
    seedUpstreamMetric(shopId, "ODR", "0.0100", "2026-09-01", "2026-09-30");

    var page = as("channel.operation.read", () -> service.trends(null, null, null, null, null, 0, 50));
    assertThat(page.items()).extracting(PerformanceMetricSeries::shopId).contains(shopId.toString());

    // 不存在的店铺 ⇒ 抛错（不是返回空序列）
    var thrown =
        catchThrowable(
            () -> as("channel.operation.read", () -> service.shopTrends(UUID.randomUUID().toString(), null, null)));
    assertThat(thrown).isNotNull();
  }

  // ==================================================================
  // 阈值清单
  // ==================================================================

  @Test
  @DisplayName("16.1.1.2 店铺范围清单来自真实店铺目录，并给出每个店铺的指标覆盖情况")
  void shopScopeListComesFromRealShopDirectory() {
    UUID shopId = seedShop("SCOPE-1");
    seedUpstreamMetric(shopId, "ODR", "0.0100", "2026-09-01", "2026-09-30");
    seedUpstreamMetric(shopId, "ODR", null, "2026-08-01", "2026-08-31");

    var page = as("channel.operation.read", () -> service.shops(null, null, 0, 50));

    var shop =
        page.items().stream()
            .filter(item -> item.shopId().equals(shopId.toString()))
            .findFirst()
            .orElseThrow();
    assertThat(shop.shopCode()).isEqualTo("SCOPE-1");
    assertThat(shop.seriesCount()).as("只有 ODR 有记录 ⇒ 一条序列").isEqualTo(1);
    assertThat(shop.knownPointCount()).isEqualTo(1);
    assertThat(shop.unknownPointCount()).as("量不到的那个点也要算进覆盖情况").isEqualTo(1);
    assertThat(page.total()).isPositive();
  }

  @Test
  @DisplayName("16.1.1.2 阈值清单六条齐全，量纲与来源写清")
  void alertRuleListIsComplete() {
    var list = as("channel.operation.read", () -> service.alertRules());

    assertThat(list.items()).hasSize(6);
    assertThat(list.items())
        .extracting(PerformanceMetricAlertRule::metricCode)
        .containsExactlyInAnyOrder(
            "ODR", "LATE_SHIPMENT_RATE", "CANCELLATION_RATE", "VALID_TRACKING_RATE",
            "RESPONSE_TIME", "ACCOUNT_RATING");
    assertThat(list.items())
        .allSatisfy(
            rule -> {
              assertThat(rule.ruleSource()).isNotBlank();
              assertThat(rule.ruleSourceLabel()).isNotBlank();
              assertThat(rule.unit()).isIn("RATIO", "HOURS", "SCORE");
            });
    assertThat(list.note()).contains("不追溯改变历史预警");
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
