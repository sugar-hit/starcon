package com.starcon.platform;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.platform.AutomationRuleRunModels.*;
import com.starcon.tenancy.TenantTransactions;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
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
 * 18.3.2.1「执行日志」的**功能**集成测试，跑在真实 PostgreSQL 16 上。
 *
 * <p><b>真实 PostgreSQL 16 + FORCE RLS，不使用任何内存替身。</b>
 *
 * <p>本测试盯住蓝图 18.3.2.1 的**加粗句**：
 * 「<b>规则必须幂等</b>：同一事件重复触发不能重复执行副作用（如重复改价、重复建单）。」
 * —— 拆成五个可执行判定：
 *
 * <ol>
 *   <li><b>幂等闸在服务层</b>：同一规则 + 同一事件 id 重复登记 ⇒ <b>409</b>，
 *       且**库里没有第二条 run**（不能凭空多出一条并宣称已执行）；
 *   <li><b>幂等闸在 DB 层</b>：绕过服务层直接插第二条同事件 id 的 run ⇒
 *       唯一约束报错（应用层校验不是唯一防线）；
 *   <li><b>手动重跑是「新的一次执行」</b>：新 run、{@code rerun_of_run_id} 指向原 run、
 *       <b>原 run 一个字都没改</b>；
 *   <li><b>手动重跑不伪造成功</b>：落成 {@code outcome = NOT_ATTEMPTED} +
 *       写清未接通原因、{@code side_effects_applied = false}、{@code executed_actions = 0}；
 *   <li><b>每步都可定位</b>：逐步明细逐条记条件命中情况 / 命中的是哪条 condition /
 *       错误码与错误信息；失败的那一步没写错误信息 ⇒ 服务层 400。
 * </ol>
 *
 * <p><b>🔴 时间纪律</b>：落库时间列用 {@code ?::timestamptz} 绑定，并<b>断言写回的值</b>
 * （按 epoch 比对，且故意用 +08:00 的偏移量 —— 用 {@code Timestamp.valueOf(LocalDateTime…)}
 * 会静默偏 −28800s），不只断言「没抛异常」。
 *
 * <p><b>🔴 数量纪律</b>：条数一律 {@link BigDecimal}，断言用 {@code isEqualByComparingTo}
 * （比的是数值，不是标度）。<b>耗时</b>一律<b>整数毫秒</b>。
 *
 * <p>多租户隔离的三条断言在 {@code PlatformAutomationRuleRunTenancyIntegrationTest} 里
 * （同一批表、同一套夹具）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class PlatformAutomationRuleRunIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID tenant = UUID.randomUUID();
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static HttpServer identity;

  static final ObjectMapper json =
      new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

  /** 本模块的两张表。 */
  static final List<String> TABLES =
      List.of("platform_automation_rule_run", "platform_automation_rule_run_step");

  static final List<String> PERMISSIONS =
      List.of(
          "platform.automation_rule_run.read",
          "platform.automation_rule_run.record",
          "platform.automation_rule_run.rerun");

  static final String EVENT_TYPE = "order.created";

  /** 参照行 ID 按租户派生：主键是**全局** uuid，两个租户不能用同一批固定 UUID。 */
  static UUID idFor(int ordinal) {
    return UUID.nameUUIDFromBytes(("starcon-platform-automation-run-" + tenant + "-" + ordinal).getBytes());
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

  @AfterAll
  static void stopContainers() {
    if (identity != null) identity.stop(0);
    if (pg.isRunning()) pg.stop();
  }

  /** 规则档案 id（18.3.1.1 拥有；本模块**只读**它）。 */
  static UUID ruleId;
  static UUID dryRunRuleId;

  @BeforeEach
  void reset() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate table platform_automation_rule_run_step, platform_automation_rule_run,"
              + " platform_automation_rule_scope, platform_automation_rule_action,"
              + " platform_automation_rule_condition, platform_automation_rule,"
              + " platform_webhook_event_type restart identity cascade");
    }
    seedRules();
  }

  /** 在本租户名下登记两条规则：一条正常、一条**试运行**。 */
  void seedRules() {
    tenants.run(
        tenant,
        () -> {
          var now = OffsetDateTime.now(ZoneOffset.UTC);
          ruleId = idFor(1);
          dryRunRuleId = idFor(2);
          sql.execute(
              "insert into platform_automation_rule(id,tenant_id,name,description,trigger_event_type,"
                  + " trigger_schema_version,priority,enabled,status,dry_run,"
                  + " consecutive_failure_threshold,remark,created_by,created_at,updated_at)"
                  + " values(?,?,?,'',?,1,100,true,'ACTIVE',false,3,'',null,?::timestamptz,"
                  + " ?::timestamptz)",
              ruleId, tenant, "改价规则", EVENT_TYPE, now, now);
          sql.execute(
              "insert into platform_automation_rule(id,tenant_id,name,description,trigger_event_type,"
                  + " trigger_schema_version,priority,enabled,status,dry_run,"
                  + " consecutive_failure_threshold,remark,created_by,created_at,updated_at)"
                  + " values(?,?,?,'',?,1,100,true,'ACTIVE',true,3,'',null,?::timestamptz,"
                  + " ?::timestamptz)",
              dryRunRuleId, tenant, "试运行规则", EVENT_TYPE, now, now);
          return null;
        });
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

  <T> T getJson(String path, Class<T> type) throws Exception {
    var response =
        http.perform(get(path).header("Authorization", "session"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();
    return json.readValue(response.getContentAsString(), type);
  }

  /** 登记一次执行（默认成功、两条步骤）。 */
  Map<String, Object> runBody(String eventId) {
    var body = new LinkedHashMap<String, Object>();
    body.put("ruleId", ruleId);
    body.put("eventId", eventId);
    body.put("triggerEventType", EVENT_TYPE);
    body.put("triggeredBy", "trade-service");
    body.put("eventPayload", "{\"orderNo\":\"SO-1\"}");
    body.put("outcome", "SUCCEEDED");
    body.put("source", "ENGINE");
    body.put("sideEffectsApplied", true);
    body.put("matchedConditions", 2);
    body.put("plannedActions", 2);
    body.put("executedActions", 2);
    body.put("durationMs", 1234);
    body.put(
        "steps",
        List.of(
            map(
                "stepNo", 1,
                "stepKind", "CONDITION",
                "conditionSeq", 1,
                "fieldPath", "order.totalAmount",
                "operator", "GT",
                "expectedValue", "100",
                "actualValue", "250",
                "matched", true,
                "result", "SUCCEEDED",
                "durationMs", 12),
            map(
                "stepNo", 2,
                "stepKind", "ACTION",
                "actionType", "UPDATE_STATUS",
                "targetRef", "order-1",
                "params", "{\"orderStatus\":\"PAID\"}",
                "result", "SUCCEEDED",
                "durationMs", 1200,
                "sideEffectApplied", true)));
    return body;
  }

  /**
   * 试运行规则的登记体：**不许**声称产生副作用（步骤里也不许）。
   *
   * <p>🔴 试运行标记由**规则档案**推导 ⇒ 客户端自称不算数；
   * 步骤里声称「这一步产生了副作用」同样被拒。
   */
  Map<String, Object> dryRunBody(String eventId) {
    var body = runBody(eventId);
    body.put("ruleId", dryRunRuleId);
    body.put("sideEffectsApplied", false);
    body.put("executedActions", 0);
    body.put(
        "steps",
        List.of(
            map(
                "stepNo", 1,
                "stepKind", "CONDITION",
                "conditionSeq", 1,
                "fieldPath", "order.totalAmount",
                "operator", "GT",
                "expectedValue", "100",
                "actualValue", "250",
                "matched", true,
                "result", "SUCCEEDED",
                "durationMs", 12)));
    return body;
  }

  /** 🔴 **必须在可信租户事务里读**：RLS 挡住事务外的读（这正是隔离断言要验的）。 */
  long countRuns() {
    return tenants.run(
        tenant,
        () -> sql.fetchOne("select count(*) from platform_automation_rule_run").get(0, Long.class));
  }

  /** 🔴 同上：逐步明细的行数。 */
  long countSteps() {
    return tenants.run(
        tenant,
        () ->
            sql.fetchOne("select count(*) from platform_automation_rule_run_step").get(0, Long.class));
  }

  // ==========================================================================
  // 能力声明
  // ==========================================================================
  @Test
  void 能力声明如实答出执行器未交付() throws Exception {
    var capabilities =
        getJson("/platform/automation/runs/capabilities", AutomationRuleRunCapabilities.class);
    assertThat(capabilities.capabilities())
        .anyMatch(
            c ->
                c.name().contains("规则执行引擎")
                    && !c.available()
                    && c.ownerModule().equals("18.3.2")
                    && c.note().contains("未接通"));
    // 🔴 本模块真交付的能力**如实答已交付**，并写明依据。
    assertThat(capabilities.capabilities())
        .anyMatch(
            c -> c.name().contains("逐步明细") && c.available() && c.evidence().contains("run_step"))
        .anyMatch(c -> c.name().contains("幂等") && c.available())
        .anyMatch(c -> c.name().contains("失败原因定位") && c.available())
        .anyMatch(c -> c.name().contains("查看上下文") && c.available());
  }

  // ==========================================================================
  // 登记一次执行
  // ==========================================================================
  @Test
  void 登记一次执行会落下执行记录与逐步明细() throws Exception {
    var text = postJson("/platform/automation/runs", runBody("evt-1"), 200);
    var detail = json.readValue(text, AutomationRuleRunDetail.class);

    assertThat(detail.run().ruleId()).isEqualTo(ruleId);
    assertThat(detail.run().eventId()).isEqualTo("evt-1");
    assertThat(detail.run().outcome()).isEqualTo("SUCCEEDED");
    assertThat(detail.run().source()).isEqualTo("ENGINE");
    assertThat(detail.run().sideEffectsApplied()).isTrue();
    assertThat(detail.run().dryRun()).isFalse();
    // 🔴 数量是 BigDecimal（比数值，不比标度）。
    assertThat(detail.run().matchedConditions()).isEqualByComparingTo(BigDecimal.valueOf(2));
    assertThat(detail.run().executedActions()).isEqualByComparingTo(BigDecimal.valueOf(2));
    // 🔴 耗时是**整数毫秒**。
    assertThat(detail.run().durationMs()).isEqualTo(1234L);

    // 逐步明细两条，**全序**（step_no 1 → 2）。
    assertThat(detail.steps()).hasSize(2);
    assertThat(detail.steps().get(0).stepNo()).isEqualTo(1);
    assertThat(detail.steps().get(0).stepKind()).isEqualTo("CONDITION");
    // 🔴 条件命中情况与「命中的是哪条 condition」都在。
    assertThat(detail.steps().get(0).matched()).isTrue();
    assertThat(detail.steps().get(0).conditionSeq()).isEqualTo(1);
    assertThat(detail.steps().get(0).fieldPath()).isEqualTo("order.totalAmount");
    assertThat(detail.steps().get(0).durationMs()).isEqualTo(12L);
    assertThat(detail.steps().get(1).stepKind()).isEqualTo("ACTION");
    assertThat(detail.steps().get(1).actionType()).isEqualTo("UPDATE_STATUS");
    assertThat(detail.steps().get(1).durationMs()).isEqualTo(1200L);
    assertThat(detail.steps().get(1).sideEffectApplied()).isTrue();

    // 库里真的有一行（不是只返回了一个对象）。
    assertThat(countRuns()).isEqualTo(1L);
    assertThat(countSteps()).isEqualTo(2L);
  }

  @Test
  void 登记执行会写回正确的时间与耗时() throws Exception {
    // 🔴 故意用 +08:00 的偏移量：`Timestamp.valueOf(LocalDateTime…)` 会静默偏 −28800s。
    var body = runBody("evt-time");
    body.put("startedAt", "2026-11-02T08:00:00+08:00");
    body.put("finishedAt", "2026-11-02T08:00:05+08:00");
    body.remove("durationMs");

    var detail =
        json.readValue(
            postJson("/platform/automation/runs", body, 200), AutomationRuleRunDetail.class);

    var startedAt = OffsetDateTime.parse("2026-11-02T08:00:00+08:00");
    var finishedAt = OffsetDateTime.parse("2026-11-02T08:00:05+08:00");
    assertThat(detail.run().startedAt().toInstant().toEpochMilli())
        .as("开始时间写回值必须是同一个瞬间（用 epoch 比对，不受时区表示影响）")
        .isEqualTo(startedAt.toInstant().toEpochMilli());
    assertThat(detail.run().finishedAt().toInstant().toEpochMilli())
        .isEqualTo(finishedAt.toInstant().toEpochMilli());
    // 🔴 耗时从起止时间**推**出来，整数毫秒。
    assertThat(detail.run().durationMs()).isEqualTo(5000L);

    // 库里那一行也是同一个瞬间（不是只在响应里对）。
    var stored =
        tenants.run(
            tenant,
            () ->
                sql.fetchOne(
                        "select started_at from platform_automation_rule_run where id = ?",
                        detail.run().id())
                    .get(0, OffsetDateTime.class));
    assertThat(stored.toInstant().toEpochMilli()).isEqualTo(startedAt.toInstant().toEpochMilli());
  }

  // ==========================================================================
  // 🔴 幂等（蓝图加粗句）
  // ==========================================================================
  @Test
  void 同一事件重复登记会被拒绝且库里没有第二条() throws Exception {
    postJson("/platform/automation/runs", runBody("evt-dup"), 200);

    // 第二次登记同一事件 ⇒ 409。
    var response =
        http.perform(
                post("/platform/automation/runs")
                    .header("Authorization", "session")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(runBody("evt-dup"))))
            .andReturn()
            .getResponse();
    assertThat(response.getStatus()).as("同一事件重复触发必须被拒").isEqualTo(409);

    // 🔴 库里**只有一条** run —— 不能凭空多出一条并宣称已执行。
    assertThat(countRuns()).as("重复触发不得产生第二条执行记录").isEqualTo(1L);
  }

  @Test
  void 绕过服务层直接插第二条同事件的记录也会被唯一约束挡住() throws Exception {
    postJson("/platform/automation/runs", runBody("evt-dup2"), 200);
    var existing =
        tenants.run(
            tenant,
            () ->
                sql.fetchOne("select id, tenant_id from platform_automation_rule_run limit 1"));

    // 🔴 应用层校验**不是唯一防线**：直连 SQL 也撞唯一约束。
    // ⚠️ 这一行故意用**不同的幂等键**（'evt:evt-dup2-alt'）⇒ 只可能被
    //    **事件维度的部分唯一索引**挡住（幂等键那条约束这时不冲突），
    //    这样断言才真的在验「同一事件 ⇒ 至多一条执行记录」这条约束本身。
    assertThatThrownBy(
            () ->
                tenants.run(
                    tenant,
                    () ->
                        sql.execute(
                            "insert into platform_automation_rule_run(id,tenant_id,rule_id,rule_name,"
                                + " event_id,idempotency_key,trigger_event_type,triggered_by,"
                                + " event_payload,outcome,source,dry_run,side_effects_applied,"
                                + " matched_conditions,planned_actions,executed_actions,duration_ms,"
                                + " failure_reason,failure_class,error_code,error_message,"
                                + " not_attempted_reason,rerun_of_run_id,rerun_seq,"
                                + " rerun_requested_by,rerun_requested_at,rerun_reason,started_at,"
                                + " finished_at,created_by,created_at)"
                                + " values(?,?,?,?,?,'evt:evt-dup2-alt','order.created','x','{}'::jsonb,"
                                + " 'SUCCEEDED','ENGINE',false,true,1,1,1,1,'','','','','',null,null,"
                                + " null,null,'',?::timestamptz,?::timestamptz,null,?::timestamptz)",
                            UUID.randomUUID(),
                            existing.get(1, UUID.class),
                            ruleId,
                            "改价规则",
                            "evt-dup2",
                            OffsetDateTime.now(ZoneOffset.UTC),
                            OffsetDateTime.now(ZoneOffset.UTC),
                            OffsetDateTime.now(ZoneOffset.UTC))))
        .as("DB 层唯一约束必须挡住重复事件")
        .hasStackTraceContaining("platform_automation_rule_run_event_unique");

    assertThat(countRuns()).isEqualTo(1L);
  }

  @Test
  void 不同规则可以用同一个事件id() throws Exception {
    postJson("/platform/automation/runs", runBody("evt-shared"), 200);
    // 另一条规则用**同一个事件 id** ⇒ 允许（幂等的粒度是「规则 + 事件」）。
    postJson("/platform/automation/runs", dryRunBody("evt-shared"), 200);
    assertThat(countRuns()).isEqualTo(2L);
  }

  // ==========================================================================
  // 试运行
  // ==========================================================================
  @Test
  void 试运行规则不许登记产生了真实副作用() throws Exception {
    var body = runBody("evt-dry");
    body.put("ruleId", dryRunRuleId);
    postJson("/platform/automation/runs", body, 409);

    var detail =
        json.readValue(
            postJson("/platform/automation/runs", dryRunBody("evt-dry2"), 200),
            AutomationRuleRunDetail.class);
    // 🔴 试运行标记由**规则档案**推导。
    assertThat(detail.run().dryRun()).isTrue();
    assertThat(detail.run().sideEffectsApplied()).isFalse();
  }

  @Test
  void 请求体不接受自称的试运行标记() throws Exception {
    var body = runBody("evt-dryflag");
    body.put("dryRun", true);
    // 🔴 客户端自称的试运行标记**不算数** ⇒ 直接 400（Jackson 拒绝未知字段）。
    postJson("/platform/automation/runs", body, 400);
  }

  // ==========================================================================
  // 逐步明细
  // ==========================================================================
  @Test
  void 失败的那一步必须写清错误码与错误信息() throws Exception {
    var body = runBody("evt-step-fail");
    body.put("outcome", "FAILED");
    body.put("failureReason", "改价接口返回 500");
    body.put("failureClass", "ACTION_FAILED");
    body.put(
        "steps",
        List.of(
            map(
                "stepNo", 1,
                "stepKind", "ACTION",
                "actionType", "UPDATE_STATUS",
                "result", "FAILED",
                "durationMs", 30)));
    postJson("/platform/automation/runs", body, 400);

    var withError = runBody("evt-step-fail2");
    withError.put("outcome", "FAILED");
    withError.put("failureReason", "改价接口返回 500");
    withError.put("failureClass", "ACTION_FAILED");
    withError.put(
        "steps",
        List.of(
            map(
                "stepNo", 1,
                "stepKind", "ACTION",
                "actionType", "UPDATE_STATUS",
                "result", "FAILED",
                "errorCode", "HTTP_500",
                "errorMessage", "改价接口返回 500",
                "durationMs", 30)));
    var detail =
        json.readValue(
            postJson("/platform/automation/runs", withError, 200), AutomationRuleRunDetail.class);
    assertThat(detail.steps()).hasSize(1);
    assertThat(detail.steps().get(0).errorCode()).isEqualTo("HTTP_500");
    assertThat(detail.steps().get(0).errorMessage()).isEqualTo("改价接口返回 500");
    // 🔴 失败原因能从 run 直接下钻到 step。
    assertThat(detail.run().failureReason()).isEqualTo("改价接口返回 500");
    assertThat(detail.run().failureClass()).isEqualTo("ACTION_FAILED");
  }

  @Test
  void 步号重复会被拒() throws Exception {
    var body = runBody("evt-dup-step");
    body.put(
        "steps",
        List.of(
            map("stepNo", 1, "stepKind", "ACTION", "actionType", "SEND_NOTIFICATION", "result",
                "SUCCEEDED"),
            map("stepNo", 1, "stepKind", "ACTION", "actionType", "CREATE_TASK", "result",
                "SUCCEEDED")));
    postJson("/platform/automation/runs", body, 400);
  }

  @Test
  void 条件步骤必须给出命中与否() throws Exception {
    var body = runBody("evt-no-matched");
    body.put(
        "steps",
        List.of(
            map(
                "stepNo", 1,
                "stepKind", "CONDITION",
                "fieldPath", "order.totalAmount",
                "result", "NOT_MATCHED")));
    postJson("/platform/automation/runs", body, 400);
  }

  // ==========================================================================
  // 🔴 手动重跑
  // ==========================================================================
  @Test
  void 手动重跑是新的执行记录且不伪造成功() throws Exception {
    var original =
        json.readValue(
            postJson("/platform/automation/runs", runBody("evt-rerun"), 200),
            AutomationRuleRunDetail.class);

    var rerun =
        json.readValue(
            postJson(
                "/platform/automation/runs/" + original.run().id() + "/rerun",
                map("reason", "价格改错了，再跑一次"),
                200),
            AutomationRuleRunRerunResult.class);

    // 🔴 **新的一次执行**，与原 run 显式关联。
    assertThat(rerun.detail().run().id()).isNotEqualTo(original.run().id());
    assertThat(rerun.detail().run().rerunOfRunId()).isEqualTo(original.run().id());
    assertThat(rerun.detail().run().rerunSeq()).isEqualTo(1);
    assertThat(rerun.detail().run().source()).isEqualTo("RERUN");

    // 🔴 **未执行**，不是「已重跑成功」。
    assertThat(rerun.detail().run().outcome()).isEqualTo("NOT_ATTEMPTED");
    assertThat(rerun.detail().run().notAttemptedReason()).isNotBlank();
    assertThat(rerun.detail().run().notAttemptedReason()).contains("未接通");
    assertThat(rerun.detail().run().sideEffectsApplied()).isFalse();
    assertThat(rerun.detail().run().executedActions()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(rerun.detail().run().failureClass()).isEqualTo("EXECUTOR_UNAVAILABLE");
    assertThat(rerun.detail().run().errorCode()).isEqualTo("EXECUTOR_NOT_CONNECTED");
    assertThat(rerun.detail().run().rerunReason()).isEqualTo("价格改错了，再跑一次");

    // 🔴 原 run **一个字都没改**（不能把原 run 改写成重跑结果）。
    var stillThere =
        tenants.run(
            tenant,
            () ->
                sql.fetchOne(
                        "select outcome from platform_automation_rule_run where id = ?",
                        original.run().id())
                    .get(0, String.class));
    assertThat(stillThere).as("原 run 的结论不许被重跑改写").isEqualTo("SUCCEEDED");
  }

  @Test
  void 同一个幂等键重复提交重跑只产生一条() throws Exception {
    var original =
        json.readValue(
            postJson("/platform/automation/runs", runBody("evt-rerun-idem"), 200),
            AutomationRuleRunDetail.class);
    var key = "rerun-key-1";
    var first =
        json.readValue(
            postJson(
                "/platform/automation/runs/" + original.run().id() + "/rerun",
                map("idempotencyKey", key),
                200),
            AutomationRuleRunRerunResult.class);
    var second =
        json.readValue(
            postJson(
                "/platform/automation/runs/" + original.run().id() + "/rerun",
                map("idempotencyKey", key),
                200),
            AutomationRuleRunRerunResult.class);

    assertThat(first.duplicate()).isFalse();
    assertThat(second.duplicate()).isTrue();
    assertThat(second.detail().run().id()).isEqualTo(first.detail().run().id());
    assertThat(countRuns()).as("同一个幂等键不得产生第二次重跑").isEqualTo(2L);
    var reruns =
        tenants.run(
            tenant,
            () ->
                sql.fetchOne(
                        "select count(*) from platform_automation_rule_run where source = 'RERUN'")
                    .get(0, Long.class));
    assertThat(reruns).as("同一个幂等键不得产生第二次重跑").isEqualTo(1L);
  }

  @Test
  void 重跑请求体不接受租户与成员字段() throws Exception {
    var original =
        json.readValue(
            postJson("/platform/automation/runs", runBody("evt-rerun-tenant"), 200),
            AutomationRuleRunDetail.class);
    // 🔴 请求体**不接受** tenantId / userId / membershipId ⇒ Jackson 直接 400 拒掉。
    postJson(
        "/platform/automation/runs/" + original.run().id() + "/rerun",
        map("tenantId", UUID.randomUUID().toString()),
        400);
    postJson(
        "/platform/automation/runs/" + original.run().id() + "/rerun",
        map("membershipId", UUID.randomUUID().toString()),
        400);
  }

  // ==========================================================================
  // 列表与详情
  // ==========================================================================
  @Test
  void 列表是真分页且筛选真的生效() throws Exception {
    for (var i = 0; i < 7; i++) {
      postJson("/platform/automation/runs", runBody("evt-page-" + i), 200);
    }
    var failed = runBody("evt-page-failed");
    failed.put("outcome", "FAILED");
    failed.put("failureReason", "改价接口返回 500");
    failed.put("failureClass", "ACTION_FAILED");
    failed.put("sideEffectsApplied", false);
    failed.put("executedActions", 0);
    postJson("/platform/automation/runs", failed, 200);

    var page0 = getJson("/platform/automation/runs?page=0&size=3", AutomationRuleRunPage.class);
    assertThat(page0.items()).hasSize(3);
    assertThat(page0.total()).isEqualTo(8L);
    assertThat(page0.size()).isEqualTo(3);
    assertThat(page0.page()).isEqualTo(0);

    var page2 = getJson("/platform/automation/runs?page=2&size=3", AutomationRuleRunPage.class);
    assertThat(page2.items()).hasSize(2);
    assertThat(page2.total()).isEqualTo(8L);

    // 真筛选：按结论筛。
    var failedPage =
        getJson("/platform/automation/runs?outcome=FAILED", AutomationRuleRunPage.class);
    assertThat(failedPage.total()).isEqualTo(1L);
    assertThat(failedPage.items().get(0).eventId()).isEqualTo("evt-page-failed");

    // 真筛选：按事件 id 筛。
    var byEvent =
        getJson("/platform/automation/runs?eventId=evt-page-3", AutomationRuleRunPage.class);
    assertThat(byEvent.total()).isEqualTo(1L);

    // 真筛选：按规则筛。
    var byRule =
        getJson("/platform/automation/runs?ruleId=" + ruleId, AutomationRuleRunPage.class);
    assertThat(byRule.total()).isEqualTo(8L);

    // 真筛选：关键词。
    var byKeyword =
        getJson("/platform/automation/runs?keyword=evt-page-failed", AutomationRuleRunPage.class);
    assertThat(byKeyword.total()).isEqualTo(1L);

    // 非法筛选值 ⇒ 400（不是默默忽略）。
    http.perform(get("/platform/automation/runs?outcome=NOPE").header("Authorization", "session"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void 详情含逐步明细且重跑次数可查() throws Exception {
    var original =
        json.readValue(
            postJson("/platform/automation/runs", runBody("evt-detail"), 200),
            AutomationRuleRunDetail.class);
    postJson(
        "/platform/automation/runs/" + original.run().id() + "/rerun",
        map("reason", "再跑一次"),
        200);

    var detail =
        getJson(
            "/platform/automation/runs/" + original.run().id(), AutomationRuleRunDetail.class);
    assertThat(detail.steps()).hasSize(2);
    assertThat(detail.rerunCount()).isEqualTo(1L);
    assertThat(detail.run().eventPayload()).contains("SO-1");
  }

  @Test
  void 概览的数与库里对得上() throws Exception {
    postJson("/platform/automation/runs", runBody("evt-ov-1"), 200);
    var failed = runBody("evt-ov-2");
    failed.put("outcome", "FAILED");
    failed.put("failureReason", "动作失败");
    failed.put("failureClass", "ACTION_FAILED");
    failed.put("sideEffectsApplied", false);
    failed.put("executedActions", 0);
    var detail = json.readValue(postJson("/platform/automation/runs", failed, 200), AutomationRuleRunDetail.class);

    var overview = getJson("/platform/automation/runs/overview", AutomationRuleRunOverview.class);
    assertThat(overview.runs()).isEqualTo(2L);
    assertThat(overview.succeededRuns()).isEqualTo(1L);
    assertThat(overview.failedRuns()).isEqualTo(1L);
    assertThat(overview.notAttemptedRuns()).isEqualTo(0L);
    assertThat(overview.totalDurationMs()).isGreaterThan(0L);
    assertThat(overview.failedSteps()).isZero();
    assertThat(detail.run().outcome()).isEqualTo("FAILED");
  }

  // ==========================================================================
  // 授权
  // ==========================================================================
  @Test
  void 没有权限码就拿不到数据() throws Exception {
    actors.put("session-readonly", actor(UUID.randomUUID(), tenant, List.of("platform.automation_rule_run.read")));
    http.perform(get("/platform/automation/runs").header("Authorization", "session-readonly"))
        .andExpect(status().isOk());
    // 🔴 「登记执行」与「看」是两个独立的码。
    http.perform(
            post("/platform/automation/runs")
                .header("Authorization", "session-readonly")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(runBody("evt-perm"))))
        .andExpect(status().isForbidden());
    // 🔴 「手动重跑」也是独立的码。
    var original =
        json.readValue(
            postJson("/platform/automation/runs", runBody("evt-perm-2"), 200),
            AutomationRuleRunDetail.class);
    http.perform(
            post("/platform/automation/runs/" + original.run().id() + "/rerun")
                .header("Authorization", "session-readonly")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void 没有会话就拿不到数据() throws Exception {
    http.perform(get("/platform/automation/runs")).andExpect(status().isUnauthorized());
  }
}
