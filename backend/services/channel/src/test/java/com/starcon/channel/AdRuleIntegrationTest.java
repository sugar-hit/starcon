package com.starcon.channel;

import static com.starcon.channel.AdRuleModels.*;
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
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 14.2.2.2 自动规则。
 *
 * <p>本测试类钉住这几组事：
 *
 * <ol>
 *   <li><b>多租户三断言</b>（AGENTS.md 硬性要求，缺一不可）：两租户互不可见、伪造 tenant header 无效、
 *       连接池归还后不串租户
 *   <li><b>🔴 上限是必填且真的拦得住</b>：没有上限的规则建不出来；超过单次上限 / 总上限 / 上下限的调整
 *       **被拒绝并留痕**，而且**不是被静默截断**（after_value 仍是本来会变成的值，applied_delta 为空）
 *   <li><b>🔴 试运行与真实执行分得开</b>：试运行产生命中记录与「本来会变成多少」，但**不产生待下发动作**
 *   <li><b>🔴 优先级真的参与判定</b>：同目标多条命中只执行一条，落选的那条**照样留痕**并写明为什么没选它
 *   <li><b>🔴 比率不可比 ≠ 0</b>：归因销售额为 0 时 ACOS 为 null，规则**不命中**且命中记录写明「不可比」
 *   <li><b>时间字段写回值正确</b>（AGENTS.md 要求断言写回值，不能只断言没抛异常）
 *   <li><b>效果回溯</b>：执行后没有新数据 ⇒ INSUFFICIENT_DATA（合法答案）；数据到齐后重算 ⇒ 有结论
 *   <li><b>真分页真筛选</b>；跨租户与不存在返回逐字相同的 404
 * </ol>
 *
 * <p><b>时刻一律用固定的 {@link OffsetDateTime} 钉死</b>，不用 {@code now()}：用墙钟会让「这次写入
 * 落在哪个断言分支上」成为一个 flaky 断言。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AdRuleIntegrationTest {

  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID user = UUID.randomUUID();
  static HttpServer identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static final ObjectMapper JSON = new ObjectMapper();

  /**
   * 🔴 每个用例一对**全新的租户**。
   *
   * <p>刻意不给整个测试类共用一对租户 UUID：同一个 Testcontainers 容器里所有用例共享一个库，
   * 共用租户就意味着前一个用例建的规则会被后一个用例的 total / 列表断言数进去。
   */
  UUID a, b;
  AccessService.Access actorA, actorB;
  String bearerA;

  /** 🔴 钉死的时刻。用墙钟会让「写回值对不对」变成 flaky 断言。 */
  static final OffsetDateTime WINDOW_START = OffsetDateTime.parse("2026-01-01T00:00:00+08:00");
  static final OffsetDateTime WINDOW_END = OffsetDateTime.parse("2026-01-02T00:00:00+08:00");
  static final OffsetDateTime AS_OF = OffsetDateTime.parse("2026-01-02T00:00:00+08:00");
  /** 回溯用的「更晚的一行指标」：必须晚于执行时刻（now），否则回溯找不到它。 */
  static final OffsetDateTime LATER_AS_OF = OffsetDateTime.parse("2030-01-01T00:00:00+08:00");
  static final OffsetDateTime LATER_WINDOW_START = OffsetDateTime.parse("2026-06-01T00:00:00+08:00");
  static final OffsetDateTime LATER_WINDOW_END = OffsetDateTime.parse("2026-06-02T00:00:00+08:00");

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

  /** 每个用例一对新租户 + 对应的会话（MockMvc 用的 bearer 也随之更新）。 */
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
   * 播种全局码表（平台目录）。
   *
   * <p>🔴 刻意**不在静态块里**播种：静态块早于 Spring 上下文刷新，而 Flyway 是在上下文刷新
   * 时才建表的。全部 {@code on conflict do nothing}：这些是全局共享码表，多个用例会反复播种同一条。
   */
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

  @Autowired AdRuleService service;
  @Autowired AccessService access;
  @Autowired MockMvc http;

  // ==================================================================
  // 辅助
  // ==================================================================

  <T> T as(AccessService.Access who, String permission, java.util.function.Supplier<T> work) {
    return access.run(who, permission, work);
  }

  /** 以管理员（超级用户，RLS 不生效）连接执行 SQL，用来验证 DB 层的兜底。 */
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

  /** 播种一个店铺（幂等：店铺码是 UNIQUE(tenant_id, code)）。 */
  String seedShop(UUID tenant, String code) {
    UUID id = UUID.randomUUID();
    execAsTenant(tenant,
        "insert into shop (tenant_id,id,platform_id,code,name) values ('" + tenant + "','" + id
            + "','" + AMAZON + "','" + code + "','测试店铺 " + code + "')"
            + " on conflict (tenant_id,code) do nothing");
    return code;
  }

  /** 一条「ACOS 超标降价」的规则请求（默认值可被逐个覆盖）。 */
  SaveRuleRequest ruleRequest(
      String code,
      String shop,
      String campaignId,
      BigDecimal singleCap,
      BigDecimal totalCap,
      int totalCount,
      boolean dryRun,
      int priority,
      BigDecimal minBid,
      BigDecimal maxBid) {
    return new SaveRuleRequest(
        null,
        code,
        code + " 规则",
        AMAZON,
        shop,
        "CAMPAIGN",
        campaignId,
        "",
        "DECREASE_BID",
        "ABSOLUTE",
        new BigDecimal("0.2000"),
        singleCap,
        BigDecimal.ZERO,
        totalCap,
        totalCount,
        24,
        null,
        null,
        minBid,
        maxBid,
        priority,
        dryRun,
        24,
        "ACTIVE",
        "集成测试规则",
        List.of(
            new SaveRuleConditionRequest(
                "ACOS", "GT", new BigDecimal("0.350000"), null, "ACOS 超过 0.35", 10)));
  }

  /** 登记一条指标：花费 100、归因销售额 250 ⇒ ACOS = 0.4、ROAS = 2.5。 */
  SaveMetricRequest metricRequest(
      String shop, String campaignId, BigDecimal spend, BigDecimal revenue, BigDecimal bid) {
    return new SaveMetricRequest(
        null,
        AMAZON,
        shop,
        "CAMPAIGN",
        campaignId,
        "",
        "USD",
        "ENABLED",
        new BigDecimal("100.0000"),
        bid,
        new BigDecimal("1000"),
        new BigDecimal("50"),
        new BigDecimal("4"),
        spend,
        revenue,
        AS_OF,
        WINDOW_START,
        WINDOW_END,
        "test-batch-1",
        "集成测试指标");
  }

  UUID saveRule(SaveRuleRequest request) {
    return as(actorA, "channel.operation.create", () -> service.saveRule(request)).rule().id();
  }

  // ==================================================================
  // 1. 上限与试运行是必填项
  // ==================================================================

  @Test
  @DisplayName("14.2.2.2 没有上限的规则建不出来 —— 单次上限 / 总上限必须为正")
  void ruleWithoutCapIsRejected() {
    String shop = seedShop(a, "CAP-AMZ-1");

    assertThatThrownBy(() -> as(actorA, "channel.operation.create",
        () -> service.saveRule(ruleRequest(
            "NO-CAP-1", shop, "CAMP-1", BigDecimal.ZERO, new BigDecimal("5"), 3, true, 10, null, null))))
        .hasMessageContaining("单次调整金额上限必须为正数");

    assertThatThrownBy(() -> as(actorA, "channel.operation.create",
        () -> service.saveRule(ruleRequest(
            "NO-CAP-2", shop, "CAMP-1", new BigDecimal("0.5"), BigDecimal.ZERO, 3, true, 10, null, null))))
        .hasMessageContaining("总上限");

    // 次数上限为 0 也不许
    assertThatThrownBy(() -> as(actorA, "channel.operation.create",
        () -> service.saveRule(ruleRequest(
            "NO-CAP-3", shop, "CAMP-1", new BigDecimal("0.5"), new BigDecimal("5"), 0, true, 10, null, null))))
        .hasMessageContaining("总上限（窗口内累计次数）必须至少为 1");
  }

  @Test
  @DisplayName("14.2.2.2 没有条件的规则建不出来（服务层与 DB 层各拒一次）")
  void ruleWithoutConditionIsRejected() {
    String shop = seedShop(a, "COND-AMZ-1");

    assertThatThrownBy(() -> as(actorA, "channel.operation.create",
        () -> service.saveRule(new SaveRuleRequest(
            null, "NO-COND-1", "没条件的规则", AMAZON, shop, "CAMPAIGN", "CAMP-1", "",
            "DECREASE_BID", "ABSOLUTE", new BigDecimal("0.2000"),
            new BigDecimal("0.5000"), BigDecimal.ZERO, new BigDecimal("5.0000"), 3, 24,
            null, null, null, null, 10, true, 24, "ACTIVE", "", List.of()))))
        .hasMessageContaining("至少要有一个条件");

    // 🔴 DB 层兜底：绕过服务层直接写 SQL，启用状态的规则没有条件 ⇒ 提交时被拒
    UUID ruleId = UUID.randomUUID();
    assertThatThrownBy(() -> execAsTenant(a,
        "insert into ad_rule (tenant_id,id,rule_code,rule_name,platform_id,shop_code,target_type,"
            + "external_campaign_id,external_keyword_id,action_type,adjustment_mode,adjustment_value,"
            + "single_adjust_cap_amount,single_adjust_cap_percent,total_cap_amount,total_cap_count,"
            + "total_cap_window_hours,priority,dry_run,evaluation_window_hours,status)"
            + " values ('" + a + "','" + ruleId + "','DB-NO-COND','DB 没条件的规则','" + AMAZON
            + "','" + shop + "','CAMPAIGN','CAMP-1','','DECREASE_BID','ABSOLUTE',0.2,"
            + "0.5,0,5,3,24,10,true,24,'ACTIVE')"))
        .hasMessageContaining("至少有一个条件");
  }

  @Test
  @DisplayName("14.2.2.2 比率口径与金额口径的阈值不许混填")
  void thresholdUnitMismatchIsRejected() {
    String shop = seedShop(a, "UNIT-AMZ-1");

    // ACOS 是比率口径 ⇒ 不许填金额阈值
    assertThatThrownBy(() -> as(actorA, "channel.operation.create",
        () -> service.saveRule(new SaveRuleRequest(
            null, "UNIT-1", "口径错配", AMAZON, shop, "CAMPAIGN", "CAMP-1", "",
            "DECREASE_BID", "ABSOLUTE", new BigDecimal("0.2000"),
            new BigDecimal("0.5000"), BigDecimal.ZERO, new BigDecimal("5.0000"), 3, 24,
            null, null, null, null, 10, true, 24, "ACTIVE", "",
            List.of(new SaveRuleConditionRequest(
                "ACOS", "GT", null, new BigDecimal("0.35"), "", 10))))))
        .hasMessageContaining("阈值不能填 thresholdAmount");

    // ACOS 是比率口径 ⇒ 阈值必须填在 thresholdValue 上
    assertThatThrownBy(() -> as(actorA, "channel.operation.create",
        () -> service.saveRule(new SaveRuleRequest(
            null, "UNIT-3", "口径缺失", AMAZON, shop, "CAMPAIGN", "CAMP-1", "",
            "DECREASE_BID", "ABSOLUTE", new BigDecimal("0.2000"),
            new BigDecimal("0.5000"), BigDecimal.ZERO, new BigDecimal("5.0000"), 3, 24,
            null, null, null, null, 10, true, 24, "ACTIVE", "",
            List.of(new SaveRuleConditionRequest(
                "ACOS", "GT", null, null, "", 10))))))
        .hasMessageContaining("阈值必须填 thresholdValue");

    // SPEND 是金额口径 ⇒ 不许填比率阈值
    assertThatThrownBy(() -> as(actorA, "channel.operation.create",
        () -> service.saveRule(new SaveRuleRequest(
            null, "UNIT-2", "口径错配", AMAZON, shop, "CAMPAIGN", "CAMP-1", "",
            "DECREASE_BID", "ABSOLUTE", new BigDecimal("0.2000"),
            new BigDecimal("0.5000"), BigDecimal.ZERO, new BigDecimal("5.0000"), 3, 24,
            null, null, null, null, 10, true, 24, "ACTIVE", "",
            List.of(new SaveRuleConditionRequest(
                "SPEND", "GT", new BigDecimal("100"), null, "", 10))))))
        .hasMessageContaining("阈值不能填 thresholdValue");
  }

  // ==================================================================
  // 2. 试运行 vs 真实执行
  // ==================================================================

  @Test
  @DisplayName("14.2.2.2 试运行：照常命中、算出本来会变成多少，但不产生待下发动作")
  void dryRunProducesHitButNoDispatch() {
    String shop = seedShop(a, "DRY-AMZ-1");
    as(actorA, "channel.operation.create",
        () -> service.saveMetric(metricRequest(shop, "CAMP-1", new BigDecimal("100"), new BigDecimal("250"), new BigDecimal("1.50"))));
    saveRule(ruleRequest("DRY-1", shop, "CAMP-1", new BigDecimal("0.50"), new BigDecimal("5"), 3, true, 10, null, null));

    var detail = as(actorA, "channel.operation.create",
        () -> service.run(new RunRequest("APPLY", null, "试运行用")));

    // 🔴 规则自己开着试运行 ⇒ 一次 APPLY 的运行**不能**把它推成真实执行
    assertThat(detail.run().dryRunCount()).isEqualTo(1);
    assertThat(detail.run().appliedCount()).isZero();
    assertThat(detail.run().rejectedCount()).isZero();
    assertThat(detail.run().hitCount()).isEqualTo(1);

    var execution = detail.executions().get(0);
    assertThat(execution.outcome()).isEqualTo("DRY_RUN");
    assertThat(execution.beforeValue()).isEqualByComparingTo("1.5000");
    assertThat(execution.afterValue()).isEqualByComparingTo("1.3000");
    assertThat(execution.capCheck()).isEqualTo("PASSED");
    assertThat(execution.boundCheck()).isEqualTo("PASSED");

    // 🔴 试运行**不产生**待下发动作
    assertThat(as(actorA, "channel.operation.read", () -> service.dispatches(null, null, 0, 50)).items())
        .isEmpty();
  }

  @Test
  @DisplayName("14.2.2.2 真实执行：产生待下发动作（平台侧未交付 ⇒ 状态是「待下发」不是「已生效」）")
  void realExecutionEnqueuesPendingDispatch() {
    String shop = seedShop(a, "APPLY-AMZ-1");
    as(actorA, "channel.operation.create",
        () -> service.saveMetric(metricRequest(shop, "CAMP-1", new BigDecimal("100"), new BigDecimal("250"), new BigDecimal("1.50"))));
    saveRule(ruleRequest("APPLY-1", shop, "CAMP-1", new BigDecimal("0.50"), new BigDecimal("5"), 3, false, 10, null, null));

    var detail = as(actorA, "channel.operation.create", () -> service.run(new RunRequest("APPLY", null, "真执行")));
    assertThat(detail.run().appliedCount()).isEqualTo(1);

    var execution = detail.executions().get(0);
    assertThat(execution.outcome()).isEqualTo("APPLIED");
    assertThat(execution.afterValue()).isEqualByComparingTo("1.3000");
    assertThat(execution.appliedDelta()).isEqualByComparingTo("-0.2000");

    var dispatches = as(actorA, "channel.operation.read", () -> service.dispatches(null, null, 0, 50));
    assertThat(dispatches.items()).hasSize(1);
    assertThat(dispatches.pendingCount()).isEqualTo(1);
    var dispatch = dispatches.items().get(0);
    assertThat(dispatch.dispatchStatus()).isEqualTo("PENDING");
    assertThat(dispatch.actionType()).isEqualTo("DECREASE_BID");
    assertThat(dispatch.actionPayload().get("bidBefore").asText()).isEqualTo("1.5000");
    assertThat(dispatch.actionPayload().get("bidAfter").asText()).isEqualTo("1.3000");
  }

  // ==================================================================
  // 3. 🔴 三道闸：超限要**被拒绝并留痕**，不是被静默截断
  // ==================================================================

  @Test
  @DisplayName("14.2.2.2 超过单次上限 ⇒ 拒绝并留痕，且**不是**被静默截断")
  void singleCapExceededIsRejectedNotTruncated() {
    String shop = seedShop(a, "SCAP-AMZ-1");
    as(actorA, "channel.operation.create",
        () -> service.saveMetric(metricRequest(shop, "CAMP-1", new BigDecimal("100"), new BigDecimal("250"), new BigDecimal("1.50"))));
    // 单次上限 0.10，但规则想降 0.20
    saveRule(ruleRequest("SCAP-1", shop, "CAMP-1", new BigDecimal("0.10"), new BigDecimal("5"), 3, false, 10, null, null));

    var detail = as(actorA, "channel.operation.create", () -> service.run(new RunRequest("APPLY", null, "")));
    assertThat(detail.run().rejectedCount()).isEqualTo(1);
    assertThat(detail.run().appliedCount()).isZero();

    var execution = detail.executions().get(0);
    assertThat(execution.outcome()).isEqualTo("REJECTED");
    assertThat(execution.rejectReason()).isEqualTo("SINGLE_CAP_EXCEEDED");
    assertThat(execution.capCheck()).isEqualTo("SINGLE_CAP_EXCEEDED");
    assertThat(execution.rejectDetail()).contains("被拒绝").contains("不是被静默截断");
    // 🔴 关键：after_value 仍是「本来会变成多少」，applied_delta 为空 —— 说明**没有**偷偷调一个更小的数
    assertThat(execution.afterValue()).isEqualByComparingTo("1.3000");
    assertThat(execution.appliedDelta()).isNull();

    // 🔴 被拒绝 ⇒ 没有待下发动作
    assertThat(as(actorA, "channel.operation.read", () -> service.dispatches(null, null, 0, 50)).items())
        .isEmpty();
  }

  @Test
  @DisplayName("14.2.2.2 超过总上限 ⇒ 拒绝并留痕（窗口内累计）")
  void totalCapExceededIsRejected() {
    String shop = seedShop(a, "TCAP-AMZ-1");
    as(actorA, "channel.operation.create",
        () -> service.saveMetric(metricRequest(shop, "CAMP-1", new BigDecimal("100"), new BigDecimal("250"), new BigDecimal("1.50"))));
    // 总上限 0.25：第一次降 0.20 通过，第二次再降 0.20 就超了
    saveRule(ruleRequest("TCAP-1", shop, "CAMP-1", new BigDecimal("0.50"), new BigDecimal("0.25"), 3, false, 10, null, null));

    var first = as(actorA, "channel.operation.create", () -> service.run(new RunRequest("APPLY", null, "第一次")));
    assertThat(first.run().appliedCount()).isEqualTo(1);

    var second = as(actorA, "channel.operation.create", () -> service.run(new RunRequest("APPLY", null, "第二次")));
    assertThat(second.run().rejectedCount()).isEqualTo(1);
    var execution = second.executions().get(0);
    assertThat(execution.outcome()).isEqualTo("REJECTED");
    assertThat(execution.rejectReason()).isEqualTo("TOTAL_CAP_EXCEEDED");
    assertThat(execution.capCheck()).isEqualTo("TOTAL_CAP_EXCEEDED");
    // 🔴 窗口内已用额度要落在记录上，能复算
    assertThat(execution.windowUsedAmount()).isEqualByComparingTo("0.2000");
    assertThat(execution.windowUsedCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("14.2.2.2 越过上下限保护 ⇒ 拒绝并留痕")
  void boundViolationIsRejected() {
    String shop = seedShop(a, "BOUND-AMZ-1");
    as(actorA, "channel.operation.create",
        () -> service.saveMetric(metricRequest(shop, "CAMP-1", new BigDecimal("100"), new BigDecimal("250"), new BigDecimal("1.50"))));
    // 出价下限 1.40，降到 1.30 就越界
    saveRule(ruleRequest("BOUND-1", shop, "CAMP-1", new BigDecimal("0.50"), new BigDecimal("5"), 3, false, 10,
        new BigDecimal("1.40"), null));

    var detail = as(actorA, "channel.operation.create", () -> service.run(new RunRequest("APPLY", null, "")));
    assertThat(detail.run().rejectedCount()).isEqualTo(1);
    var execution = detail.executions().get(0);
    assertThat(execution.outcome()).isEqualTo("REJECTED");
    assertThat(execution.rejectReason()).isEqualTo("BOUND_VIOLATION");
    assertThat(execution.boundCheck()).isEqualTo("BOUND_VIOLATION");
    assertThat(execution.rejectDetail()).contains("下限保护");
  }

  // ==================================================================
  // 4. 🔴 优先级真的参与判定
  // ==================================================================

  @Test
  @DisplayName("14.2.2.2 同目标多条命中：按优先级取一条，落选的照样留痕并写明为什么没选它")
  void prioritySelectsExactlyOneRule() {
    String shop = seedShop(a, "PRIO-AMZ-1");
    as(actorA, "channel.operation.create",
        () -> service.saveMetric(metricRequest(shop, "CAMP-1", new BigDecimal("100"), new BigDecimal("250"), new BigDecimal("1.50"))));

    // 优先级 10 的规则：ACOS 超标降价
    saveRule(ruleRequest("PRIO-HIGH", shop, "CAMP-1", new BigDecimal("0.50"), new BigDecimal("5"), 3, false, 10, null, null));
    // 优先级 20 的规则：ROAS 低于 3.0 时加预算（同样命中：当前 ROAS = 250/100 = 2.5）
    UUID lowRule = as(actorA, "channel.operation.create",
        () -> service.saveRule(new SaveRuleRequest(
            null, "PRIO-LOW", "优先级低的规则", AMAZON, shop, "CAMPAIGN", "CAMP-1", "",
            "INCREASE_BUDGET", "ABSOLUTE", new BigDecimal("10.0000"),
            new BigDecimal("5.0000"), BigDecimal.ZERO, new BigDecimal("50.0000"), 3, 24,
            null, null, null, null, 20, false, 24, "ACTIVE", "",
            List.of(new SaveRuleConditionRequest(
                "ROAS", "LT", new BigDecimal("3.000000"), null, "", 10)))))
        .rule().id();

    var detail = as(actorA, "channel.operation.create", () -> service.run(new RunRequest("APPLY", null, "")));

    // 两条都命中
    assertThat(detail.run().hitCount()).isEqualTo(2);
    // 🔴 但只执行一条
    assertThat(detail.run().selectedCount()).isEqualTo(1);
    assertThat(detail.run().executionCount()).isEqualTo(1);
    assertThat(detail.executions().get(0).ruleId()).isNotEqualTo(lowRule);

    var lowHit = detail.hits().stream().filter(h -> h.ruleId().equals(lowRule)).findFirst().orElseThrow();
    assertThat(lowHit.matched()).isTrue();
    assertThat(lowHit.selected()).isFalse();
    assertThat(lowHit.selectionNote()).contains("优先级更高").contains("PRIO-HIGH");

    var highHit = detail.hits().stream().filter(h -> !h.ruleId().equals(lowRule)).findFirst().orElseThrow();
    assertThat(highHit.selected()).isTrue();
    assertThat(highHit.selectionNote()).contains("被选中执行");
  }

  // ==================================================================
  // 5. 🔴 比率不可比 ≠ 0
  // ==================================================================

  @Test
  @DisplayName("14.2.2.2 归因销售额为 0 ⇒ ACOS 为 null（不可比），规则不命中且命中记录写明「不可比」")
  void uncomparableRatioDoesNotMatch() {
    String shop = seedShop(a, "RATIO-AMZ-1");
    as(actorA, "channel.operation.create",
        () -> service.saveMetric(metricRequest(shop, "CAMP-1", new BigDecimal("100"), BigDecimal.ZERO, new BigDecimal("1.50"))));
    saveRule(ruleRequest("RATIO-1", shop, "CAMP-1", new BigDecimal("0.50"), new BigDecimal("5"), 3, false, 10, null, null));

    // 🔴 分母为 0 ⇒ ACOS 是 null 而不是 0
    var metric = as(actorA, "channel.operation.read", () -> service.metrics(AMAZON, shop, "CAMPAIGN", "CAMP-1", 0, 50));
    assertThat(metric.items().get(0).acos()).isNull();
    assertThat(metric.items().get(0).roas()).isNull();

    var detail = as(actorA, "channel.operation.create", () -> service.run(new RunRequest("APPLY", null, "")));
    assertThat(detail.run().hitCount()).isZero();
    var hit = detail.hits().get(0);
    assertThat(hit.matched()).isFalse();
    assertThat(hit.hitReason()).contains("不可比").contains("ACOS");
    assertThat(detail.run().executionCount()).isZero();
  }

  // ==================================================================
  // 6. 时间字段写回值
  // ==================================================================

  @Test
  @DisplayName("14.2.2.2 timestamptz 往返断言**写回值**（AGENTS.md 要求）")
  void timestampRoundTripsWithoutShift() {
    String shop = seedShop(a, "TS-AMZ-1");
    as(actorA, "channel.operation.create",
        () -> service.saveMetric(metricRequest(shop, "CAMP-1", new BigDecimal("100"), new BigDecimal("250"), new BigDecimal("1.50"))));

    var metric = as(actorA, "channel.operation.read", () -> service.metrics(AMAZON, shop, "CAMPAIGN", "CAMP-1", 0, 50)).items().get(0);
    // 🔴 断言写回值本身，而不是「没抛异常」：偏 −28800s 这种问题只有断言值才抓得到
    assertThat(metric.metricsAsOf()).isEqualTo(AS_OF);
    assertThat(metric.windowStart()).isEqualTo(WINDOW_START);
    assertThat(metric.windowEnd()).isEqualTo(WINDOW_END);
  }

  // ==================================================================
  // 7. 数据截至时间：区分「没有数据」与「没同步到」
  // ==================================================================

  @Test
  @DisplayName("14.2.2.2 规则列表显式给出数据截至时间：没有数据 vs 没同步到要分得开")
  void dataFreshnessIsExplicit() {
    String shop = seedShop(a, "FRESH-AMZ-1");
    UUID ruleId = saveRule(ruleRequest("FRESH-1", shop, "CAMP-1", new BigDecimal("0.50"), new BigDecimal("5"), 3, true, 10, null, null));

    // 还没有指标 ⇒ latestMetricsAsOf 为空（**没有数据**）
    var withoutData = as(actorA, "channel.operation.read", () -> service.rule(ruleId.toString())).rule();
    assertThat(withoutData.latestMetricsAsOf()).isNull();
    assertThat(withoutData.metricsStale()).isFalse();

    // 登记一条旧指标 ⇒ 有数据截至时间，但早于评估窗口 ⇒ **没同步到**
    as(actorA, "channel.operation.create",
        () -> service.saveMetric(metricRequest(shop, "CAMP-1", new BigDecimal("100"), new BigDecimal("250"), new BigDecimal("1.50"))));
    var stale = as(actorA, "channel.operation.read", () -> service.rule(ruleId.toString())).rule();
    assertThat(stale.latestMetricsAsOf()).isEqualTo(AS_OF);
    assertThat(stale.metricsStale()).isTrue();
  }

  // ==================================================================
  // 8. 效果回溯
  // ==================================================================

  @Test
  @DisplayName("14.2.2.2 效果回溯：执行后没有新数据 ⇒ INSUFFICIENT_DATA（合法答案，不是 0）")
  void effectWithoutNewDataIsInsufficient() {
    String shop = seedShop(a, "EFF-AMZ-1");
    as(actorA, "channel.operation.create",
        () -> service.saveMetric(metricRequest(shop, "CAMP-1", new BigDecimal("100"), new BigDecimal("250"), new BigDecimal("1.50"))));
    saveRule(ruleRequest("EFF-1", shop, "CAMP-1", new BigDecimal("0.50"), new BigDecimal("5"), 3, false, 10, null, null));
    as(actorA, "channel.operation.create", () -> service.run(new RunRequest("APPLY", null, "")));

    var effects = as(actorA, "channel.operation.create", () -> service.evaluateEffects(new EffectEvaluateRequest(null, "")));
    assertThat(effects.items()).hasSize(1);
    var effect = effects.items().get(0);
    assertThat(effect.verdict()).isEqualTo("INSUFFICIENT_DATA");
    assertThat(effect.verdictNote()).contains("还没有更新的指标数据");
    assertThat(effect.beforeAcos()).isEqualByComparingTo("0.400000");
    assertThat(effect.afterAcos()).isNull();
  }

  @Test
  @DisplayName("14.2.2.2 效果回溯：数据到齐后重算 ⇒ 有结论（ACOS 变好 = IMPROVED）")
  void effectRecomputedWhenNewDataArrives() {
    String shop = seedShop(a, "EFF2-AMZ-1");
    as(actorA, "channel.operation.create",
        () -> service.saveMetric(metricRequest(shop, "CAMP-1", new BigDecimal("100"), new BigDecimal("250"), new BigDecimal("1.50"))));
    saveRule(ruleRequest("EFF-1", shop, "CAMP-1", new BigDecimal("0.50"), new BigDecimal("5"), 3, false, 10, null, null));
    as(actorA, "channel.operation.create", () -> service.run(new RunRequest("APPLY", null, "")));

    // 先回溯一次 ⇒ 数据不足
    assertThat(as(actorA, "channel.operation.create", () -> service.evaluateEffects(new EffectEvaluateRequest(null, "")))
            .items().get(0).verdict())
        .isEqualTo("INSUFFICIENT_DATA");

    // 新一��指标到齐：花费 80、销售额 400 ⇒ ACOS 0.2
    as(actorA, "channel.operation.create",
        () -> service.saveMetric(new SaveMetricRequest(
            null, AMAZON, shop, "CAMPAIGN", "CAMP-1", "", "USD", "ENABLED",
            new BigDecimal("100.0000"), new BigDecimal("1.3000"),
            new BigDecimal("1000"), new BigDecimal("50"), new BigDecimal("6"),
            new BigDecimal("80.0000"), new BigDecimal("400.0000"),
            LATER_AS_OF, LATER_WINDOW_START, LATER_WINDOW_END, "test-batch-2", "更晚的一行")));

    var effects = as(actorA, "channel.operation.create", () -> service.evaluateEffects(new EffectEvaluateRequest(null, "")));
    assertThat(effects.items()).hasSize(1);
    var effect = effects.items().get(0);
    assertThat(effect.verdict()).isEqualTo("IMPROVED");
    assertThat(effect.beforeAcos()).isEqualByComparingTo("0.400000");
    assertThat(effect.afterAcos()).isEqualByComparingTo("0.200000");
    assertThat(effect.acosDelta()).isEqualByComparingTo("-0.200000");
    assertThat(effects.improvedCount()).isEqualTo(1);
  }

  // ==================================================================
  // 9. 真分页 / 真筛选 / 404
  // ==================================================================

  @Test
  @DisplayName("14.2.2.2 真分页真筛选")
  void paginationAndFiltersAreReal() {
    String shop = seedShop(a, "PAGE-AMZ-1");
    for (int i = 1; i <= 3; i++) {
      saveRule(ruleRequest("PAGE-" + i, shop, "CAMP-" + i, new BigDecimal("0.50"), new BigDecimal("5"), 3, true, 10 * i, null, null));
    }

    var page1 = as(actorA, "channel.operation.read", () -> service.rules(null, null, null, null, null, 0, 2));
    var page2 = as(actorA, "channel.operation.read", () -> service.rules(null, null, null, null, null, 1, 2));
    assertThat(page1.total()).isEqualTo(3);
    assertThat(page1.items()).hasSize(2);
    assertThat(page2.items()).hasSize(1);
    assertThat(page1.items()).extracting(AdRuleRow::id)
        .doesNotContainAnyElementsOf(page2.items().stream().map(AdRuleRow::id).toList());

    // 筛选：按店铺 + 试运行状态
    var filtered = as(actorA, "channel.operation.read", () -> service.rules(null, null, shop, null, true, 0, 50));
    assertThat(filtered.total()).isEqualTo(3);
    var other = as(actorA, "channel.operation.read", () -> service.rules(null, null, "NOPE", null, null, 0, 50));
    assertThat(other.total()).isZero();
  }

  @Test
  @DisplayName("14.2.2.2 跨租户与不存在返回逐字相同的 404")
  void crossTenantAndMissingAreIdenticalNotFound() {
    String shopA = seedShop(a, "NF-AMZ-1");
    String shopB = seedShop(b, "NF-AMZ-2");
    UUID ruleA = saveRule(ruleRequest("NF-A", shopA, "CAMP-A", new BigDecimal("0.50"), new BigDecimal("5"), 3, true, 10, null, null));

    var missing = catchThrowable(() -> as(actorA, "channel.operation.read", () -> service.rule(UUID.randomUUID().toString())));
    var crossTenant = catchThrowable(() -> as(actorB, "channel.operation.read", () -> service.rule(ruleA.toString())));
    assertThat(missing).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    assertThat(crossTenant).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    // 🔴 跨租户与不存在必须**逐字相同** —— 否则「这个 id 不存在」会泄露成「这个 id 在别的租户」
    assertThat(missing.getMessage()).isEqualTo(crossTenant.getMessage());
    assertThat(missing.getMessage()).contains("自动规则不存在");
    assertThat(((org.springframework.web.server.ResponseStatusException) missing).getStatusCode().value())
        .isEqualTo(404);
  }

  // ==================================================================
  // 10. 端点
  // ==================================================================

  @Test
  @DisplayName("14.2.2.2 端点：能力声明 / 列表 / 详情 / 运行 / 待下发 / 回溯")
  void endpointsWork() throws Exception {
    String shop = seedShop(a, "EP-AMZ-1");
    as(actorA, "channel.operation.create",
        () -> service.saveMetric(metricRequest(shop, "CAMP-1", new BigDecimal("100"), new BigDecimal("250"), new BigDecimal("1.50"))));
    saveRule(ruleRequest("EP-1", shop, "CAMP-1", new BigDecimal("0.50"), new BigDecimal("5"), 3, false, 10, null, null));

    http.perform(get("/channel/ad-rules/capabilities").header("Authorization", bearerA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.dimensions[?(@.dimension=='platform_dispatch')].available",
            org.hamcrest.Matchers.contains(false)))
        .andExpect(jsonPath("$.dimensions[?(@.dimension=='platform_dispatch')].ownerModule",
            org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.containsString("平台广告"))));

    http.perform(get("/channel/ad-rules/rules").header("Authorization", bearerA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1));

    http.perform(post("/channel/ad-rules/runs")
            .header("Authorization", bearerA)
            .contentType("application/json")
            .content("{\"runMode\":\"APPLY\",\"note\":\"端点跑一次\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.run.appliedCount").value(1));

    http.perform(get("/channel/ad-rules/dispatches").header("Authorization", bearerA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.pendingCount").value(1));

    http.perform(post("/channel/ad-rules/effects/evaluate")
            .header("Authorization", bearerA)
            .contentType("application/json")
            .content("{}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].verdict").value("INSUFFICIENT_DATA"));

    // 401：无会话
    http.perform(get("/channel/ad-rules/rules")).andExpect(status().isUnauthorized());
    // 400：运行口径不合法
    http.perform(post("/channel/ad-rules/runs")
            .header("Authorization", bearerA)
            .contentType("application/json")
            .content("{\"runMode\":\"WHATEVER\"}"))
        .andExpect(status().isBadRequest());
    // 404：不存在的规则
    http.perform(get("/channel/ad-rules/rules/" + UUID.randomUUID())
            .header("Authorization", bearerA))
        .andExpect(status().isNotFound());
  }

  // ==================================================================
  // 11. 多租户三断言
  // ==================================================================

  @Test
  @DisplayName("14.2.2.2 断言一：两租户互不可见")
  void tenantsAreIsolated() {
    String shopA = seedShop(a, "ISO-AMZ-1");
    String shopB = seedShop(b, "ISO-AMZ-2");
    UUID ruleA = saveRule(ruleRequest("ISO-A", shopA, "CAMP-A", new BigDecimal("0.50"), new BigDecimal("5"), 3, false, 10, null, null));
    UUID ruleB = as(actorB, "channel.operation.create",
        () -> service.saveRule(ruleRequest("ISO-B", shopB, "CAMP-B", new BigDecimal("0.50"), new BigDecimal("5"), 3, true, 10, null, null)))
        .rule().id();

    as(actorA, "channel.operation.create",
        () -> service.saveMetric(metricRequest(shopA, "CAMP-A", new BigDecimal("100"), new BigDecimal("250"), new BigDecimal("1.50"))));
    as(actorB, "channel.operation.create",
        () -> service.saveMetric(metricRequest(shopB, "CAMP-B", new BigDecimal("100"), new BigDecimal("250"), new BigDecimal("1.50"))));

    // 规则互不可见
    assertThat(as(actorA, "channel.operation.read", () -> service.rules(null, null, null, null, null, 0, 50)).items())
        .extracting(AdRuleRow::id).containsExactly(ruleA);
    assertThat(as(actorB, "channel.operation.read", () -> service.rules(null, null, null, null, null, 0, 50)).items())
        .extracting(AdRuleRow::id).containsExactly(ruleB);

    // 指标互不可见
    assertThat(as(actorA, "channel.operation.read", () -> service.metrics(null, null, null, null, 0, 50)).items())
        .extracting(AdRuleMetricRow::externalCampaignId).containsExactly("CAMP-A");
    assertThat(as(actorB, "channel.operation.read", () -> service.metrics(null, null, null, null, 0, 50)).items())
        .extracting(AdRuleMetricRow::externalCampaignId).containsExactly("CAMP-B");

    // 🔴 命中 / 执行 / 待下发 也一样互不可见：A 跑一次，B 的队列里不该出现任何东西
    as(actorA, "channel.operation.create", () -> service.run(new RunRequest("APPLY", null, "")));
    assertThat(as(actorA, "channel.operation.read", () -> service.hits(null, null, null, null, 0, 50)).total()).isEqualTo(1);
    assertThat(as(actorB, "channel.operation.read", () -> service.hits(null, null, null, null, 0, 50)).total()).isZero();
    assertThat(as(actorA, "channel.operation.read", () -> service.dispatches(null, null, 0, 50)).items()).hasSize(1);
    assertThat(as(actorB, "channel.operation.read", () -> service.dispatches(null, null, 0, 50)).items()).isEmpty();
  }

  @Test
  @DisplayName("14.2.2.2 断言二：伪造 tenant header 无效（租户只来自已认证成员关系）")
  void forgedTenantHeaderIsIgnored() throws Exception {
    String shopA = seedShop(a, "FORGE-AMZ-1");
    String shopB = seedShop(b, "FORGE-AMZ-2");
    UUID ruleA = saveRule(ruleRequest("FORGE-A", shopA, "CAMP-A", new BigDecimal("0.50"), new BigDecimal("5"), 3, true, 10, null, null));
    UUID ruleB = as(actorB, "channel.operation.create",
        () -> service.saveRule(ruleRequest("FORGE-B", shopB, "CAMP-B", new BigDecimal("0.50"), new BigDecimal("5"), 3, true, 10, null, null)))
        .rule().id();

    // A 的会话 + B 的头 ⇒ 仍然只看得见 A 的规则
    http.perform(get("/channel/ad-rules/rules")
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[*].id",
            org.hamcrest.Matchers.contains(ruleA.toString())));

    // 伪造租户头也不能让 B 的规则被 A 读到
    http.perform(get("/channel/ad-rules/rules/" + ruleB)
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isNotFound());

    // 无会话一律 401
    http.perform(get("/channel/ad-rules/rules")
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("14.2.2.2 断言三：连接池归还后不串租户（池压到 1，A→B→A 三轮）")
  void pooledConnectionsDoNotBleedTenants() {
    String shopA = seedShop(a, "POOL-AMZ-1");
    String shopB = seedShop(b, "POOL-AMZ-2");
    UUID ruleA = saveRule(ruleRequest("POOL-A", shopA, "CAMP-A", new BigDecimal("0.50"), new BigDecimal("5"), 3, true, 10, null, null));
    UUID ruleB = as(actorB, "channel.operation.create",
        () -> service.saveRule(ruleRequest("POOL-B", shopB, "CAMP-B", new BigDecimal("0.50"), new BigDecimal("5"), 3, true, 10, null, null)))
        .rule().id();
    as(actorA, "channel.operation.create",
        () -> service.saveMetric(metricRequest(shopA, "CAMP-A", new BigDecimal("100"), new BigDecimal("250"), new BigDecimal("1.50"))));
    as(actorB, "channel.operation.create",
        () -> service.saveMetric(metricRequest(shopB, "CAMP-B", new BigDecimal("100"), new BigDecimal("250"), new BigDecimal("1.50"))));

    for (int i = 0; i < 3; i++) {
      assertThat(as(actorA, "channel.operation.read", () -> service.rules(null, null, null, null, null, 0, 50)).items())
          .extracting(AdRuleRow::id).containsExactly(ruleA);
      assertThat(as(actorB, "channel.operation.read", () -> service.rules(null, null, null, null, null, 0, 50)).items())
          .extracting(AdRuleRow::id).containsExactly(ruleB);
      assertThat(as(actorA, "channel.operation.read", () -> service.metrics(null, null, null, null, 0, 50)).items())
          .extracting(AdRuleMetricRow::externalCampaignId).containsExactly("CAMP-A");
      assertThat(as(actorB, "channel.operation.read", () -> service.metrics(null, null, null, null, 0, 50)).items())
          .extracting(AdRuleMetricRow::externalCampaignId).containsExactly("CAMP-B");
      assertThat(as(actorA, "channel.operation.read", () -> service.hits(null, null, null, null, 0, 50)).total()).isZero();
      assertThat(as(actorB, "channel.operation.read", () -> service.hits(null, null, null, null, 0, 50)).total()).isZero();
    }
  }
}
