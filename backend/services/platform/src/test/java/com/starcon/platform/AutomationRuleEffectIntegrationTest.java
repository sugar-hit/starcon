package com.starcon.platform;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.platform.AutomationRuleEffectModels.*;
import com.starcon.tenancy.TenantTransactions;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import org.jooq.DSLContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 18.3.2.2「规则效果」的**功能**集成测试，跑在真实 PostgreSQL 16 上。
 *
 * <p><b>真实 PostgreSQL 16 + FORCE RLS，不使用任何内存替身。</b>
 *
 * <p>蓝图 18.3.2.2 的职责是六件事，本测试逐条钉死：
 *
 * <ol>
 *   <li><b>触发次数</b>：五档结论（成功 / 失败 / 跳过 / 超时 / <b>未执行</b>）分列，
 *       「未执行」<b>既不算成功也不算失败</b>，也<b>不进</b>成功率的母；
 *   <li><b>成功率</b>：分母 = 成功 + 失败 + 超时；分母为 0 时返回 {@code null}
 *       （**不是 0%** ——「没有样本」与「成功率是零」是两件事）；
 *   <li><b>平均耗时</b>：**整数毫秒**，只在**真正跑过**的 run 上平均
 *       （把「没跑过」的 0 一起平均进去会让报表说这条规则跑得飞快）；
 *   <li><b>节省工时预估</b>：口径由**租户自己登记**，随报表一起返回
 *       （口径 / 公式 / 排除项）；没有口径的规则<b>不计入</b>合计（是空，不是 0）；
 *   <li><b>误触发反馈</b>：能定位到规则与执行；反馈指向**别的规则**的执行记录时被拒；
 *   <li><b>规则优化建议</b>：带**依据**、幂等重生成、已处理的状态不被系统覆盖。
 * </ol>
 *
 * <p>🔴 <b>本模块统计的是「记录」，不是「真实执行」</b>：18.3.2 执行引擎<b>尚未交付</b> ⇒
 * 能力声明必须如实答「未交付」，绝不说成「规则自动触发了几次」。
 *
 * <p>🔴 <b>只读上游</b>：18.3.2.1 的两张表与 18.3.1.1 的规则档案表，本模块
 * <b>一个字都不改</b> —— 有一条断言专门盯这件事。
 *
 * <p><b>🔴 时间纪律</b>：落库时间列用 {@code ?::timestamptz} 绑定，并<b>断言写回的值</b>
 * （按 epoch 比对，且故意用 +08:00 的偏移量 —— 用 {@code Timestamp.valueOf(LocalDateTime…)}
 * 会静默偏 −28800s），不只断言「没抛异常」。
 *
 * <p><b>🔴 数量纪律</b>：条数一律 {@link BigDecimal}，断言用 {@code isEqualByComparingTo}
 * （比的是数值，不是标度）。<b>耗时</b>一律<b>整数毫秒</b>。
 *
 * <p>多租户隔离的三条断言在 {@code AutomationRuleEffectTenancyIntegrationTest} 里
 * （同一批表、同一套夹具）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AutomationRuleEffectIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID tenant = UUID.randomUUID();
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static HttpServer identity;

  static final ObjectMapper json =
      new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

  /** 本模块自己的三张表。 */
  static final List<String> TABLES =
      List.of(
          "platform_automation_rule_effect_baseline",
          "platform_automation_rule_effect_feedback",
          "platform_automation_rule_effect_advice");

  static final List<String> PERMISSIONS =
      List.of(
          "platform.automation_rule_effect.read",
          "platform.automation_rule_effect.feedback",
          "platform.automation_rule_effect.configure");

  static final String EVENT_TYPE = "order.created";

  /** 参照行 ID 按租户派生：主键是**全局** uuid，两个租户不能用同一批固定 UUID。 */
  static UUID idFor(int ordinal) {
    return UUID.nameUUIDFromBytes(
        ("starcon-platform-automation-effect-" + tenant + "-" + ordinal).getBytes());
  }

  // ---------------------------------------------------------------- 夹具工具

  static Map<String, Object> map(Object... keyValues) {
    var out = new LinkedHashMap<String, Object>();
    for (int i = 0; i + 1 < keyValues.length; i += 2) {
      out.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
    }
    return out;
  }

  static AccessService.Access actor(UUID uid, UUID tid, List<String> permissions) {
    return new AccessService.Access(
        uid,
        tid,
        "Test",
        UUID.randomUUID(),
        null,
        permissions,
        List.of(new AccessService.Grant(UUID.randomUUID(), "TEST", "ALL", permissions)));
  }

  static {
    pg.start();
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      // 运行角色必须 NOBYPASSRLS，否则它会绕过策略，隔离断言全部变成空断言。
      s.execute("create role starcon_app nologin");
      s.execute("create role platform_app login password 'test-only' nosuperuser nobypassrls");
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
    actors.put("session", actor(UUID.randomUUID(), tenant, PERMISSIONS));
    try {
      identity = HttpServer.create(new InetSocketAddress(0), 0);
      identity.createContext(
          "/internal/access",
          e -> {
            var access = actors.get(e.getRequestHeaders().getFirst("Authorization"));
            if (access == null) {
              e.sendResponseHeaders(401, -1);
              e.close();
              return;
            }
            byte[] body = json.writeValueAsBytes(access);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      identity.setExecutor(Executors.newFixedThreadPool(2));
      identity.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "platform_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://127.0.0.1:" + identity.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
  }

  @Autowired TenantTransactions tenants;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;
  @Autowired AutomationRuleEffectService effects;

  /** 一个本租户的操作人（服务层写入时要记 {@code created_by} / {@code reported_by}）。 */
  AccessService.Access actor() {
    return actor(UUID.randomUUID(), tenant, PERMISSIONS);
  }

  @AfterAll
  static void stopContainers() {
    if (identity != null) identity.stop(0);
    if (pg.isRunning()) pg.stop();
  }

  /** 规则档案 id（18.3.1.1 拥有；本模块**只读**它）。 */
  static UUID ruleId;

  /** 第二条规则（用来验证「按规则」维度是真的分开统计的）。 */
  static UUID otherRuleId;

  @BeforeEach
  void reset() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate table platform_automation_rule_effect_advice,"
              + " platform_automation_rule_effect_feedback,"
              + " platform_automation_rule_effect_baseline,"
              + " platform_automation_rule_run_step, platform_automation_rule_run,"
              + " platform_automation_rule_scope, platform_automation_rule_action,"
              + " platform_automation_rule_condition, platform_automation_rule,"
              + " platform_webhook_event_type restart identity cascade");
    }
    seedRules();
    seedRuns();
  }

  /** 在本租户名下登记两条规则。 */
  void seedRules() {
    tenants.run(
        tenant,
        () -> {
          var now = OffsetDateTime.now(ZoneOffset.UTC);
          ruleId = idFor(1);
          otherRuleId = idFor(2);
          insertRule(ruleId, "改价规则", now);
          insertRule(otherRuleId, "建单规则", now);
          return null;
        });
  }

  void insertRule(UUID id, String name, OffsetDateTime now) {
    sql.execute(
        "insert into platform_automation_rule(id,tenant_id,name,description,trigger_event_type,"
            + " trigger_schema_version,priority,enabled,status,dry_run,"
            + " consecutive_failure_threshold,remark,created_by,created_at,updated_at)"
            + " values(?,?,?,'',?,1,100,true,'ACTIVE',false,3,'',null,?::timestamptz,"
            + " ?::timestamptz)",
        id, tenant, name, EVENT_TYPE, now, now);
  }

  /**
   * 登记一批执行记录，**故意五档都有**：
   *
   * <ul>
   *   <li>ruleId：成功 2 次（1000ms / 3000ms）、失败 1 次（500ms）、超时 1 次（8000ms）、
   *       跳过 1 次（0ms）、**未执行 1 次（0ms）**；
   *   <li>otherRuleId：成功 1 次（2000ms）。
   * </ul>
   *
   * <p>🔴 预期口径：
   *
   * <ul>
   *   <li>ruleId 的**有效执行** = 2 成功 + 1 失败 + 1 超时 = 4 ⇒ 成功率 = 2/4 = 0.5；
   *   <li>ruleId 的平均耗时 = (1000 + 3000 + 500 + 8000) / 4 = 3125ms
   *       （跳过与未执行的 0 <b>不参与</b>平均）；
   *   <li>otherRuleId 的有效执行 = 1 ⇒ 成功率 = 1.0，平均耗时 = 2000ms。
   * </ul>
   */
  void seedRuns() {
    tenants.run(
        tenant,
        () -> {
          var now = OffsetDateTime.now(ZoneOffset.UTC);
          // ruleId
          insertRun(idFor(10), ruleId, "改价规则", "evt-a1", "SUCCEEDED", 1000, now);
          insertRun(idFor(11), ruleId, "改价规则", "evt-a2", "SUCCEEDED", 3000, now);
          insertRun(idFor(12), ruleId, "改价规则", "evt-a3", "FAILED", 500, now);
          insertRun(idFor(13), ruleId, "改价规则", "evt-a4", "TIMEOUT", 8000, now);
          insertRun(idFor(14), ruleId, "改价规则", "evt-a5", "SKIPPED", 0, now);
          insertRun(idFor(15), ruleId, "改价规则", "evt-a6", "NOT_ATTEMPTED", 0, now);
          // otherRuleId
          insertRun(idFor(16), otherRuleId, "建单规则", "evt-b1", "SUCCEEDED", 2000, now);
          return null;
        });
  }

  /**
   * 登记一条执行记录。
   *
   * <p>🔴 **V422 的 CHECK 约束必须被遵守**（否则插入直接被拒）：
   *
   * <ul>
   *   <li>{@code outcome='FAILED'} ⇒ 必须写清 {@code failure_reason}，
   *       且 {@code failure_class} 与它**配套**（一个空另一个也必须空）；
   *   <li>{@code outcome='NOT_ATTEMPTED'} ⇒ 必须写清 {@code not_attempted_reason}，
   *       且**只有**这一种结论允许有未接通原因；
   *   <li>{@code side_effects_applied=false} ⇒ {@code executed_actions} 必须为 0。
   * </ul>
   */
  void insertRun(
      UUID id,
      UUID rule,
      String ruleName,
      String eventId,
      String outcome,
      long durationMs,
      OffsetDateTime now) {
    var failureReason = "FAILED".equals(outcome) ? "动作执行失败" : "";
    var failureClass = "FAILED".equals(outcome) ? "ACTION_FAILED" : "";
    var notAttemptedReason = "NOT_ATTEMPTED".equals(outcome) ? "规则执行引擎（18.3.2）尚未接通" : "";
    sql.execute(
        "insert into platform_automation_rule_run(id,tenant_id,rule_id,rule_name,event_id,"
            + " idempotency_key,trigger_event_type,triggered_by,event_payload,outcome,source,"
            + " dry_run,side_effects_applied,matched_conditions,planned_actions,"
            + " executed_actions,duration_ms,failure_reason,failure_class,error_code,"
            + " error_message,not_attempted_reason,rerun_of_run_id,rerun_seq,"
            + " rerun_requested_by,rerun_requested_at,rerun_reason,started_at,finished_at,"
            + " created_by,created_at)"
            + " values(?,?,?,?,?,?,?,?,'{}'::jsonb,?,'MANUAL',false,false,1,1,0,?,?,?,'','',"
            + " ?,null,null,null,null,'',?::timestamptz,?::timestamptz,null,?::timestamptz)",
        id,
        tenant,
        rule,
        ruleName,
        eventId,
        "evt:" + eventId,
        EVENT_TYPE,
        "trade-service",
        outcome,
        durationMs,
        failureReason,
        failureClass,
        notAttemptedReason,
        now,
        now,
        now);
  }

  // ---------------------------------------------------------------- 请求助手

  String postJson(String path, Object body, int expectedStatus) throws Exception {
    var response =
        http.perform(
                post(path)
                    .header("Authorization", "session")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(body)))
            .andReturn()
            .getResponse();
    assertThat(response.getStatus())
        .as("POST %s 的状态码（响应：%s）", path, response.getContentAsString())
        .isEqualTo(expectedStatus);
    return response.getContentAsString();
  }

  String putJson(String path, Object body, int expectedStatus) throws Exception {
    var response =
        http.perform(
                put(path)
                    .header("Authorization", "session")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(body)))
            .andReturn()
            .getResponse();
    assertThat(response.getStatus())
        .as("PUT %s 的状态码（响应：%s）", path, response.getContentAsString())
        .isEqualTo(expectedStatus);
    return response.getContentAsString();
  }

  <T> T getJson(String path, Class<T> type) throws Exception {
    var response =
        http.perform(get(path).header("Authorization", "session"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();
    return json.readValue(response.getContentAsString(), type);
  }

  /** 🔴 **必须在可信租户事务里读**：RLS 挡住事务外的读（这正是隔离断言要验的）。 */
  long countOf(String table) {
    return tenants.run(
        tenant, () -> sql.fetchOne("select count(*) from " + table).get(0, Long.class));
  }

  // ==========================================================================
  // 能力声明
  // ==========================================================================
  @Test
  void 能力声明如实答出执行引擎未交付() throws Exception {
    var capabilities =
        getJson("/platform/automation/effect/capabilities", AutomationRuleEffectCapabilities.class);
    // 🔴 执行引擎如实答「未交付」，并点名缺在 18.3.2。
    assertThat(capabilities.capabilities())
        .anyMatch(
            c ->
                c.name().contains("规则执行引擎")
                    && !c.available()
                    && c.ownerModule().equals("18.3.2")
                    && c.note().contains("记录"));
    // 🔴 六项真交付的能力**如实答已交付**，并写明依据。
    assertThat(capabilities.capabilities())
        .anyMatch(c -> c.name().contains("触发次数") && c.available())
        .anyMatch(c -> c.name().contains("成功率") && c.available())
        .anyMatch(c -> c.name().contains("平均耗时") && c.available())
        .anyMatch(c -> c.name().contains("节省工时") && c.available())
        .anyMatch(c -> c.name().contains("误触发反馈") && c.available())
        .anyMatch(c -> c.name().contains("优化建议") && c.available());
    // 🔴 关闭动作归下游 18.3.1.1 —— 本模块**不做**。
    assertThat(capabilities.capabilities())
        .anyMatch(c -> c.name().contains("规则档案") && c.available());
  }

  // ==========================================================================
  // 触发次数 / 成功率 / 平均耗时
  // ==========================================================================
  @Test
  void 概览的五档结论分列且未执行不进分母() throws Exception {
    var overview = getJson("/platform/automation/effect/overview", AutomationRuleEffectOverview.class);

    assertThat(overview.totalRuns()).isEqualTo(7);
    assertThat(overview.succeededRuns()).isEqualTo(3);
    assertThat(overview.failedRuns()).isEqualTo(1);
    assertThat(overview.skippedRuns()).isEqualTo(1);
    assertThat(overview.timeoutRuns()).isEqualTo(1);
    // 🔴 「未执行」单列一档 —— 压成失败或成功，报表就会说谎。
    assertThat(overview.notAttemptedRuns()).isEqualTo(1);
    // 🔴 有效执行 = 成功 + 失败 + 超时（跳过与未执行都**不进母**）。
    assertThat(overview.ratedRuns()).isEqualTo(5);
    // 🔴 成功率 = 3 / 5。
    assertThat(overview.successRate()).isEqualByComparingTo(new BigDecimal("0.600000"));
    assertThat(overview.rulesWithRuns()).isEqualTo(2);
    // 🔴 总耗时 = 1000 + 3000 + 500 + 8000 + 0 + 0 + 2000 = 14500。
    assertThat(overview.totalDurationMs()).isEqualTo(14500L);
    // 🔴 有效执行上的耗时 = 1000 + 3000 + 500 + 8000 + 2000 = 14500（跳过/未执行是 0）。
    assertThat(overview.ratedDurationMs()).isEqualTo(14500L);
    // 🔴 平均耗时 = 14500 / 5 = 2900ms（**整数毫秒**）。
    assertThat(overview.avgDurationMs()).isEqualTo(2900L);
    // 🔴 可下钻：最近 run id 真的给得出来。
    assertThat(overview.recentRunIds()).isNotEmpty();
  }

  @Test
  void 没有样本时成功率是空而不是零() throws Exception {
    // 🔴 执行台账是**只追加**的（触发器钉死 DELETE）⇒ 用 TRUNCATE（迁移角色）清空。
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute("truncate table platform_automation_rule_run cascade");
    }

    var overview = getJson("/platform/automation/effect/overview", AutomationRuleEffectOverview.class);
    assertThat(overview.totalRuns()).isZero();
    assertThat(overview.ratedRuns()).isZero();
    // 🔴 「没有样本」与「成功率是零」是两件事 ⇒ 这里必须是 null。
    assertThat(overview.successRate()).isNull();
    assertThat(overview.avgDurationMs()).isZero();
    // 🔴 误触发率同理。
    assertThat(overview.falseTriggerRate()).isNull();
  }

  @Test
  void 按规则的效果明细把五档结论分开算() throws Exception {
    var page =
        getJson("/platform/automation/effect/rules", AutomationRuleEffectRulePage.class);
    assertThat(page.total()).isEqualTo(2);
    assertThat(page.items()).hasSize(2);

    var a =
        page.items().stream()
            .filter(r -> r.ruleId().equals(ruleId))
            .findFirst()
            .orElseThrow();
    // 🔴 ruleId：成功 2 / 失败 1 / 超时 1 / 跳过 1 / 未执行 1。
    assertThat(a.totalRuns()).isEqualTo(6);
    assertThat(a.succeededRuns()).isEqualTo(2);
    assertThat(a.failedRuns()).isEqualTo(1);
    assertThat(a.timeoutRuns()).isEqualTo(1);
    assertThat(a.skippedRuns()).isEqualTo(1);
    assertThat(a.notAttemptedRuns()).isEqualTo(1);
    assertThat(a.ratedRuns()).isEqualTo(4);
    // 🔴 成功率 = 2 / 4 = 0.5。
    assertThat(a.successRate()).isEqualByComparingTo(new BigDecimal("0.500000"));
    // 🔴 平均耗时 = (1000 + 3000 + 500 + 8000) / 4 = 3125ms（跳过与未执行的 0 不参与）。
    assertThat(a.avgDurationMs()).isEqualTo(3125L);
    assertThat(a.ruleName()).isEqualTo("改价规则");
    // 🔴 没有登记口径 ⇒ 节省工时是**空**，不是 0。
    assertThat(a.manualMinutesPerRun()).isNull();
    assertThat(a.savedMinutes()).isNull();

    var b =
        page.items().stream()
            .filter(r -> r.ruleId().equals(otherRuleId))
            .findFirst()
            .orElseThrow();
    assertThat(b.totalRuns()).isEqualTo(1);
    assertThat(b.ratedRuns()).isEqualTo(1);
    assertThat(b.successRate()).isEqualByComparingTo(new BigDecimal("1.000000"));
    assertThat(b.avgDurationMs()).isEqualTo(2000L);
  }

  @Test
  void 逐规则明细支持真筛选与真分页() throws Exception {
    // 关键词筛选（真筛选：后端 SQL 过滤）。
    var byKeyword =
        getJson(
            "/platform/automation/effect/rules?keyword=" + "改价", AutomationRuleEffectRulePage.class);
    assertThat(byKeyword.total()).isEqualTo(1);
    assertThat(byKeyword.items().get(0).ruleId()).isEqualTo(ruleId);

    // 分页：size=1 ⇒ 真分页（总数仍然是 2）。
    var firstPage =
        getJson("/platform/automation/effect/rules?size=1", AutomationRuleEffectRulePage.class);
    assertThat(firstPage.total()).isEqualTo(2);
    assertThat(firstPage.items()).hasSize(1);
    assertThat(firstPage.size()).isEqualTo(1);
    var secondPage =
        getJson(
            "/platform/automation/effect/rules?size=1&page=1", AutomationRuleEffectRulePage.class);
    assertThat(secondPage.items()).hasSize(1);
    assertThat(secondPage.items().get(0).ruleId()).isNotEqualTo(firstPage.items().get(0).ruleId());
  }

  @Test
  void 单规则详情带反馈建议与可下钻的执行记录() throws Exception {
    // 先登记一条误触发反馈，让详情里真的有东西。
    postJson(
        "/platform/automation/effect/feedbacks",
        map(
            "ruleId", ruleId.toString(),
            "runId", idFor(12).toString(),
            "falseTriggerType", "WRONG_CONDITION",
            "note", "条件写错了"),
        200);

    var detail =
        getJson(
            "/platform/automation/effect/rules/" + ruleId, AutomationRuleEffectRuleDetail.class);
    assertThat(detail.rule().ruleId()).isEqualTo(ruleId);
    assertThat(detail.runCount()).isEqualTo(6);
    // 🔴 反馈能定位到**具体那一次执行**。
    assertThat(detail.feedbacks()).hasSize(1);
    assertThat(detail.feedbacks().get(0).runId()).isEqualTo(idFor(12));
    assertThat(detail.feedbacks().get(0).falseTriggerType()).isEqualTo("WRONG_CONDITION");
    assertThat(detail.feedbacks().get(0).status()).isEqualTo("OPEN");
    // 🔴 可下钻的执行记录。
    assertThat(detail.recentRuns()).hasSize(6);
    assertThat(detail.recentRuns().get(0).outcome()).isIn("SUCCEEDED", "FAILED", "TIMEOUT", "SKIPPED", "NOT_ATTEMPTED");
  }

  @Test
  void 执行记录下钻支持按结论筛选() throws Exception {
    var page = getJson("/platform/automation/effect/runs?outcome=SUCCEEDED", AutomationRuleEffectRunPage.class);
    assertThat(page.total()).isEqualTo(3);
    assertThat(page.items()).hasSize(3);
    assertThat(page.items()).allMatch(r -> r.outcome().equals("SUCCEEDED"));

    // 非法结论 ⇒ 400（**假筛选**比没有筛选更糟）。
    var bad =
        http.perform(
                get("/platform/automation/effect/runs?outcome=NOPE")
                    .header("Authorization", "session"))
            .andReturn()
            .getResponse();
    assertThat(bad.getStatus()).isEqualTo(400);
  }

  // ==========================================================================
  // 节省工时预估（带口径）
  // ==========================================================================
  @Test
  void 节省工时预估必须带口径且没有口径的规则不计入() throws Exception {
    // 🔴 登记口径之前：合计是**空**（不是 0）—— 因为「按 0 分钟算」会让人误以为已评估过。
    var before = getJson("/platform/automation/effect/overview", AutomationRuleEffectOverview.class);
    assertThat(before.savedMinutes()).isNull();
    assertThat(before.rulesWithBaseline()).isZero();
    assertThat(before.rulesWithoutBaseline()).isEqualTo(2);

    // 登记口径：一次成功执行替代 4 分钟人工（**人工估算**，必须写清怎么算出来的）。
    var baseline =
        json.readValue(
            putJson(
                "/platform/automation/effect/baselines",
                map(
                    "ruleId", ruleId.toString(),
                    "manualMinutesPerRun", "4",
                    "baselineSource", "MANUAL_ESTIMATE",
                    "baselineNote", "客服手工改一次价格约 4 分钟，按近 30 天 200 单平均得出"),
                200),
            AutomationRuleEffectBaselineRow.class);
    assertThat(baseline.ruleId()).isEqualTo(ruleId);
    // 🔴 数量是 BigDecimal（禁浮点）。
    assertThat(baseline.manualMinutesPerRun()).isEqualByComparingTo(new BigDecimal("4"));
    assertThat(baseline.baselineSource()).isEqualTo("MANUAL_ESTIMATE");

    var after = getJson("/platform/automation/effect/overview", AutomationRuleEffectOverview.class);
    // 🔴 节省工时 = **成功次数**（2 次）× 4 分钟 = 8 分钟。
    //    失败 / 跳过 / 超时 / 未执行都不计 —— 没跑成的活没省下时间。
    assertThat(after.savedMinutes()).isEqualByComparingTo(new BigDecimal("8"));
    assertThat(after.rulesWithBaseline()).isEqualTo(1);
    assertThat(after.rulesWithoutBaseline()).isEqualTo(1);
    // 🔴 口径随报表一起返回（只给数字不给口径，那个数字不可用于决策）。
    assertThat(after.savingBasis()).isNotNull();
    assertThat(after.savingBasis().sourceTable()).isEqualTo("platform_automation_rule_effect_baseline");
    assertThat(after.savingBasis().sourceField()).isEqualTo("manual_minutes_per_run");
    assertThat(after.savingBasis().formula()).contains("成功执行次数");
    assertThat(after.savingBasis().excluded()).contains("未执行");
    assertThat(after.savingBasis().unit()).isEqualTo("分钟");
  }

  @Test
  void 口径必须写清来源与算法() throws Exception {
    // 🔴 非法来源 ⇒ 400。
    var bad =
        http.perform(
                put("/platform/automation/effect/baselines")
                    .header("Authorization", "session")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(
                            map(
                                "ruleId", ruleId.toString(),
                                "manualMinutesPerRun", "4",
                                "baselineSource", "WHATEVER",
                                "baselineNote", "随便写的"))))
            .andReturn()
            .getResponse();
    assertThat(bad.getStatus()).isEqualTo(400);

    // 🔴 「公司口径」也必须写清依据（只给一个数不说来源，这个数不可用）。
    var noNote =
        http.perform(
                put("/platform/automation/effect/baselines")
                    .header("Authorization", "session")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(
                            map(
                                "ruleId", ruleId.toString(),
                                "manualMinutesPerRun", "4",
                                "baselineSource", "POLICY",
                                "baselineNote", ""))))
            .andReturn()
            .getResponse();
    assertThat(noNote.getStatus()).isEqualTo(400);
    // 🔴 一条都没落库（拒绝是**真的**拒绝，不是「记下了但没生效」）。
    assertThat(countOf("platform_automation_rule_effect_baseline")).isZero();

    // 🔴 拒绝的话**必须说清为什么**（MockMvc 不派发到 /error ⇒ 断言打在服务层）。
    assertThatThrownBy(
            () ->
                tenants.run(
                    tenant,
                    () ->
                        effects.upsertBaseline(
                            new AutomationRuleEffectBaselineInput(
                                ruleId,
                                new BigDecimal("4"),
                                "POLICY",
                                "",
                                null),
                            actor())))
        .as("「公司口径」也必须写清这个分钟数怎么算出来的")
        .hasMessageContaining("怎么算出来");
  }

  @Test
  void 口径是一条规则一条当前口径重复提交是upsert() throws Exception {
    putJson(
        "/platform/automation/effect/baselines",
        map(
            "ruleId", ruleId.toString(),
            "manualMinutesPerRun", "4",
            "baselineSource", "MANUAL_ESTIMATE",
            "baselineNote", "第一次登记"),
        200);
    putJson(
        "/platform/automation/effect/baselines",
        map(
            "ruleId", ruleId.toString(),
            "manualMinutesPerRun", "6",
            "baselineSource", "OBSERVED_AVERAGE",
            "baselineNote", "改成实测均值"),
        200);

    // 🔴 upsert ⇒ **一条**当前口径，不是一串历史行。
    assertThat(countOf("platform_automation_rule_effect_baseline")).isEqualTo(1);
    var page =
        getJson(
            "/platform/automation/effect/baselines?ruleId=" + ruleId,
            AutomationRuleEffectBaselinePage.class);
    assertThat(page.total()).isEqualTo(1);
    assertThat(page.items().get(0).manualMinutesPerRun()).isEqualByComparingTo(new BigDecimal("6"));
    assertThat(page.items().get(0).baselineSource()).isEqualTo("OBSERVED_AVERAGE");
  }

  // ==========================================================================
  // 误触发反馈
  // ==========================================================================
  @Test
  void 误触发反馈能定位到规则与执行() throws Exception {
    var row =
        json.readValue(
            postJson(
                "/platform/automation/effect/feedbacks",
                map(
                    "ruleId", ruleId.toString(),
                    "runId", idFor(12).toString(),
                    "falseTriggerType", "TOO_FREQUENT",
                    "note", "每次事件都跑，运营受不了"),
                200),
            AutomationRuleEffectFeedbackRow.class);
    assertThat(row.ruleId()).isEqualTo(ruleId);
    assertThat(row.runId()).isEqualTo(idFor(12));
    assertThat(row.falseTriggerType()).isEqualTo("TOO_FREQUENT");
    assertThat(row.status()).isEqualTo("OPEN");
    assertThat(countOf("platform_automation_rule_effect_feedback")).isEqualTo(1);

    var overview = getJson("/platform/automation/effect/overview", AutomationRuleEffectOverview.class);
    assertThat(overview.falseTriggerFeedbacks()).isEqualTo(1);
    // 🔴 分子指向**有效执行**（失败那次是 rated 的）。
    assertThat(overview.falseTriggerRuns()).isEqualTo(1);
    assertThat(overview.unlinkedFeedbacks()).isZero();
    // 🔴 误触发率 = 1 / 5（ruleId 的 rated 是 4，otherRuleId 的 rated 是 1）。
    assertThat(overview.falseTriggerRate()).isEqualByComparingTo(new BigDecimal("0.200000"));
  }

  @Test
  void 没有指向具体run的反馈单列且不进分子() throws Exception {
    postJson(
        "/platform/automation/effect/feedbacks",
        map(
            "ruleId", ruleId.toString(),
            "falseTriggerType", "WRONG_SCOPE",
            "note", "这条规则老乱触发，说不清是哪一次"),
        200);
    var overview = getJson("/platform/automation/effect/overview", AutomationRuleEffectOverview.class);
    assertThat(overview.falseTriggerFeedbacks()).isEqualTo(1);
    // 🔴 没有指向具体 run ⇒ 不进分子，但**如实显示**（少算的那部分必须让人看见）。
    assertThat(overview.falseTriggerRuns()).isZero();
    assertThat(overview.unlinkedFeedbacks()).isEqualTo(1);
    assertThat(overview.falseTriggerRate()).isEqualByComparingTo(BigDecimal.ZERO);
  }

  @Test
  void 反馈指向别的规则的执行记录会被拒() throws Exception {
    var bad =
        http.perform(
                post("/platform/automation/effect/feedbacks")
                    .header("Authorization", "session")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(
                            map(
                                "ruleId", ruleId.toString(),
                                "runId", idFor(16).toString(),
                                "falseTriggerType", "OTHER",
                                "note", "挂在别的规则的执行上"))))
            .andReturn()
            .getResponse();
    assertThat(bad.getStatus()).isEqualTo(400);
    // 🔴 一条都没落库（拒绝是**真的**拒绝）。
    assertThat(countOf("platform_automation_rule_effect_feedback")).isZero();
    // 🔴 拒绝的话**必须说清为什么**（MockMvc 不派发到 /error ⇒ 断言打在服务层）。
    assertThatThrownBy(
            () ->
                tenants.run(
                    tenant,
                    () ->
                        effects.recordFeedback(
                            new AutomationRuleEffectFeedbackInput(
                                ruleId, idFor(16), "OTHER", "挂在别的规则的执行上"),
                            actor())))
        .as("反馈挂到别的规则的执行上必须被拒，并说清原因")
        .hasMessageContaining("不属于这条规则");
  }

  @Test
  void 其它类反馈必须写清说明() throws Exception {
    var bad =
        http.perform(
                post("/platform/automation/effect/feedbacks")
                    .header("Authorization", "session")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(
                            map(
                                "ruleId", ruleId.toString(),
                                "falseTriggerType", "OTHER",
                                "note", ""))))
            .andReturn()
            .getResponse();
    assertThat(bad.getStatus()).isEqualTo(400);
    // 🔴 一条都没落库。
    assertThat(countOf("platform_automation_rule_effect_feedback")).isZero();
    // 🔴 拒绝的话**必须说清为什么**（MockMvc 不派发到 /error ⇒ 断言打在服务层）。
    assertThatThrownBy(
            () ->
                tenants.run(
                    tenant,
                    () ->
                        effects.recordFeedback(
                            new AutomationRuleEffectFeedbackInput(ruleId, null, "OTHER", ""),
                            actor())))
        .as("「其它」类反馈必须写清说明")
        .hasMessageContaining("说明");
  }

  // ==========================================================================
  // 规则优化建议
  // ==========================================================================
  @Test
  void 系统建议依当前聚合重新生成且幂等() throws Exception {
    var first =
        json.readValue(
            postJson("/platform/automation/effect/advices/refresh", "{}", 200),
            AutomationRuleEffectAdviceRefreshResult.class);
    assertThat(first.scannedRules()).isEqualTo(2);
    // 🔴 ruleId 有效执行 4 次、成功率 0.5、样本够；otherRuleId 样本太少。
    assertThat(first.createdAdvices()).isPositive();

    var countAfterFirst = countOf("platform_automation_rule_effect_advice");

    // 🔴 幂等：再生成一次**不会**多出一行（同规则 + 同类型 ⇒ 至多一条）。
    var second =
        json.readValue(
            postJson("/platform/automation/effect/advices/refresh", "{}", 200),
            AutomationRuleEffectAdviceRefreshResult.class);
    assertThat(second.createdAdvices()).isZero();
    assertThat(second.updatedAdvices()).isPositive();
    assertThat(countOf("platform_automation_rule_effect_advice")).isEqualTo(countAfterFirst);
  }

  @Test
  void 建议带依据且写明关闭动作归规则档案() throws Exception {
    postJson("/platform/automation/effect/advices/refresh", "{}", 200);

    var page = getJson("/platform/automation/effect/advices", AutomationRuleEffectAdvicePage.class);
    assertThat(page.total()).isPositive();
    // 🔴 没有依据的建议是空话 —— 每条系统建议都必须带 evidence。
    assertThat(page.items()).allMatch(a -> a.evidence() != null && !a.evidence().isBlank());
    // 🔴 动作归属恒为 18.3.1.1（本模块**不做**关闭动作）。
    assertThat(page.items()).allMatch(a -> a.targetModule().equals("18.3.1.1"));
    assertThat(page.items()).allMatch(a -> a.targetRef().startsWith("/platform/automation-rules/"));
  }

  @Test
  void 误触发率高的规则会被建议关闭并可快速定位() throws Exception {
    // 给 ruleId 登记**足够多**的误触发反馈，把误触发率顶到门槛之上。
    //   ruleId 的 rated = 4 ⇒ 误触发 2 次 ⇒ 2/4 = 0.5 ≥ 0.20 ⇒ 建议关闭。
    postJson(
        "/platform/automation/effect/feedbacks",
        map(
            "ruleId", ruleId.toString(),
            "runId", idFor(12).toString(),
            "falseTriggerType", "WRONG_CONDITION",
            "note", "条件写错了"),
        200);
    postJson(
        "/platform/automation/effect/feedbacks",
        map(
            "ruleId", ruleId.toString(),
            "runId", idFor(13).toString(),
            "falseTriggerType", "WRONG_CONDITION",
            "note", "又写错了"),
        200);

    postJson("/platform/automation/effect/advices/refresh", "{}", 200);

    var page = getJson("/platform/automation/effect/advices", AutomationRuleEffectAdvicePage.class);
    var falseTriggerAdvice =
        page.items().stream()
            .filter(a -> a.adviceType().equals("FALSE_TRIGGER_HIGH"))
            .findFirst()
            .orElseThrow();
    assertThat(falseTriggerAdvice.severity()).isEqualTo("CRITICAL");
    assertThat(falseTriggerAdvice.ruleId()).isEqualTo(ruleId);
    // 🔴 建议**带依据**：证据里必须有算它用的数字。
    assertThat(falseTriggerAdvice.evidence()).contains("falseTriggerRuns");
    assertThat(falseTriggerAdvice.evidence()).contains("ratedRuns");
    // 🔴 建议里写明「关闭动作归 18.3.1.1」。
    assertThat(falseTriggerAdvice.detail()).contains("18.3.1.1");

    // 🔴 逐规则明细里**能快速定位**： adviseDisable 标志 + 理由 + 规则名快照。
    var rules = getJson("/platform/automation/effect/rules", AutomationRuleEffectRulePage.class);
    var a =
        rules.items().stream()
            .filter(r -> r.ruleId().equals(ruleId))
            .findFirst()
            .orElseThrow();
    assertThat(a.adviseDisable()).isTrue();
    assertThat(a.adviseReason()).contains("18.3.1.1");
    assertThat(a.ruleName()).isEqualTo("改价规则");
    assertThat(a.falseTriggerRate()).isEqualByComparingTo(new BigDecimal("0.500000"));
  }

  @Test
  void 人工建议不会被系统重新生成覆盖() throws Exception {
    postJson(
        "/platform/automation/effect/advices",
        map(
            "ruleId", ruleId.toString(),
            "adviceType", "FALSE_TRIGGER_HIGH",
            "severity", "INFO",
            "title", "人工登记：这条规则该关了",
            "detail", "运营自己写的建议"),
        200);

    var before =
        getJson("/platform/automation/effect/advices?source=MANUAL", AutomationRuleEffectAdvicePage.class);
    assertThat(before.total()).isEqualTo(1);
    assertThat(before.items().get(0).severity()).isEqualTo("INFO");

    postJson("/platform/automation/effect/advices/refresh", "{}", 200);

    var after =
        getJson("/platform/automation/effect/advices?source=MANUAL", AutomationRuleEffectAdvicePage.class);
    // 🔴 人工建议**一个字都没被改**（系统刷新不动 MANUAL 行）。
    assertThat(after.total()).isEqualTo(1);
    assertThat(after.items().get(0).severity()).isEqualTo("INFO");
    assertThat(after.items().get(0).title()).isEqualTo("人工登记：这条规则该关了");
  }

  // ==========================================================================
  // 处理反馈与建议（状态必须真的能被改，否则那个筛选器是假的）
  // ==========================================================================
  @Test
  void 反馈状态能被处理且处理不改误触发率() throws Exception {
    var row =
        json.readValue(
            postJson(
                "/platform/automation/effect/feedbacks",
                map(
                    "ruleId", ruleId.toString(),
                    "runId", idFor(12).toString(),
                    "falseTriggerType", "WRONG_CONDITION",
                    "note", "条件写错了"),
                200),
            AutomationRuleEffectFeedbackRow.class);
    assertThat(row.status()).isEqualTo("OPEN");

    // 🔴 状态筛选器不许是假的：ACCEPTED 必须真的能被筛出来。
    var before = getJson("/platform/automation/effect/feedbacks", AutomationRuleEffectFeedbackPage.class);
    assertThat(before.total()).isEqualTo(1);

    var accepted =
        json.readValue(
            putJson(
                "/platform/automation/effect/feedbacks/" + row.id() + "/status?status=ACCEPTED",
                "{}",
                200),
            AutomationRuleEffectFeedbackRow.class);
    assertThat(accepted.status()).isEqualTo("ACCEPTED");

    var acceptedPage =
        getJson(
            "/platform/automation/effect/feedbacks?status=ACCEPTED",
            AutomationRuleEffectFeedbackPage.class);
    assertThat(acceptedPage.total()).isEqualTo(1);
    assertThat(acceptedPage.items().get(0).id()).isEqualTo(row.id());

    // 🔴 非法状态 ⇒ 400（不是默默忽略）。
    var bad =
        http.perform(
                put("/platform/automation/effect/feedbacks/" + row.id() + "/status?status=NOPE")
                    .header("Authorization", "session")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"))
            .andReturn()
            .getResponse();
    assertThat(bad.getStatus()).isEqualTo(400);

    // 🔴 处理**不改变**误触发率 —— 一条被「已采纳」的反馈仍然是发生过的误触发。
    var overview = getJson("/platform/automation/effect/overview", AutomationRuleEffectOverview.class);
    assertThat(overview.falseTriggerFeedbacks()).isEqualTo(1);
    assertThat(overview.falseTriggerRuns()).isEqualTo(1);
  }

  @Test
  void 建议状态能被处理且系统重新生成不覆盖它() throws Exception {
    postJson("/platform/automation/effect/advices/refresh", "{}", 200);

    var page = getJson("/platform/automation/effect/advices", AutomationRuleEffectAdvicePage.class);
    assertThat(page.total()).isPositive();
    var target = page.items().get(0);
    assertThat(target.status()).isEqualTo("OPEN");

    var adopted =
        json.readValue(
            putJson(
                "/platform/automation/effect/advices/" + target.id() + "/status?status=ADOPTED",
                "{}",
                200),
            AutomationRuleEffectAdviceRow.class);
    assertThat(adopted.status()).isEqualTo("ADOPTED");

    // 🔴 系统重新生成**不覆盖**人的决定（否则那句「不覆盖」就是空话）。
    postJson("/platform/automation/effect/advices/refresh", "{}", 200);
    var after = getJson("/platform/automation/effect/advices", AutomationRuleEffectAdvicePage.class);
    var still =
        after.items().stream()
            .filter(a -> a.id().equals(target.id()))
            .findFirst()
            .orElseThrow();
    assertThat(still.status()).isEqualTo("ADOPTED");
  }

  // ==========================================================================
  // 🔴 只读上游：一个字节都不改 18.3.2.1 / 18.3.1.1 的表
  // ==========================================================================
  @Test
  void 本模块只读上游不写上游的表() throws Exception {
    var before =
        tenants.run(
            tenant,
            () ->
                sql.fetchOne(
                    "select count(*) as runs, coalesce(sum(duration_ms),0) as duration"
                        + " from platform_automation_rule_run"));

    // 走一遍本模块的**全部写入口**（口径 / 反馈 / 建议 / 重新生成建议）。
    putJson(
        "/platform/automation/effect/baselines",
        map(
            "ruleId", ruleId.toString(),
            "manualMinutesPerRun", "4",
            "baselineSource", "MANUAL_ESTIMATE",
            "baselineNote", "人工估算"),
        200);
    postJson(
        "/platform/automation/effect/feedbacks",
        map(
            "ruleId", ruleId.toString(),
            "runId", idFor(12).toString(),
            "falseTriggerType", "OTHER",
            "note", "随便一条"),
        200);
    postJson(
        "/platform/automation/effect/advices",
        map(
            "ruleId", ruleId.toString(),
            "adviceType", "SLOW",
            "severity", "INFO",
            "title", "人工建议",
            "detail", "人工写的"),
        200);
    postJson("/platform/automation/effect/advices/refresh", "{}", 200);

    var after =
        tenants.run(
            tenant,
            () ->
                sql.fetchOne(
                    "select count(*) as runs, coalesce(sum(duration_ms),0) as duration"
                        + " from platform_automation_rule_run"));

    // 🔴 18.3.2.1 的执行台账：**行数与总耗时一个都没变**。
    assertThat(after.get("runs", Long.class)).isEqualTo(before.get("runs", Long.class));
    assertThat(after.get("duration", Long.class)).isEqualTo(before.get("duration", Long.class));
    // 🔴 规则档案也没被改（本模块不碰 18.3.1.1）。
    assertThat(
            tenants.run(
                tenant,
                () ->
                    sql.fetchOne(
                            "select count(*) from platform_automation_rule")
                        .get(0, Long.class)))
        .isEqualTo(2);
  }

  // ==========================================================================
  // 请求体不接受 tenantId
  // ==========================================================================
  @Test
  void 请求体不接受自称的租户() throws Exception {
    var bad =
        http.perform(
                post("/platform/automation/effect/feedbacks")
                    .header("Authorization", "session")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(
                            map(
                                "ruleId", ruleId.toString(),
                                "falseTriggerType", "OTHER",
                                "note", "带一个自称的租户",
                                "tenantId", UUID.randomUUID().toString()))))
            .andReturn()
            .getResponse();
    // 🔴 Jackson fail-on-unknown-properties 直接 400（不是被静默忽略）。
    assertThat(bad.getStatus()).isEqualTo(400);
    assertThat(countOf("platform_automation_rule_effect_feedback")).isZero();
  }

  // ==========================================================================
  // 权限
  // ==========================================================================
  @Test
  void 没有configure权限就配不了口径() throws Exception {
    actors.put(
        "read-only",
        actor(UUID.randomUUID(), tenant, List.of("platform.automation_rule_effect.read")));

    var bad =
        http.perform(
                put("/platform/automation/effect/baselines")
                    .header("Authorization", "read-only")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(
                            map(
                                "ruleId", ruleId.toString(),
                                "manualMinutesPerRun", "4",
                                "baselineSource", "MANUAL_ESTIMATE",
                                "baselineNote", "没权限也来配"))))
            .andReturn()
            .getResponse();
    assertThat(bad.getStatus()).isEqualTo(403);
    assertThat(countOf("platform_automation_rule_effect_baseline")).isZero();
  }
}
