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
 * 18.3.2.1「执行日志」的**租户隔离**证明，跑在 AGENTS.md 明文要求的三条断言上。
 *
 * <p><b>真实 PostgreSQL 16 + FORCE RLS，不使用任何内存替身。</b>
 *
 * <ol>
 *   <li>两租户互不可见；
 *   <li>伪造租户上下文无效 —— 并且<b>要断言行确实落在自称的租户名下</b>，
 *       不是只断言 A 看不见（只断言「看不见」的话，一句「写入被全表拒掉」也能骗过它）；
 *   <li>连接池归还后不串租户。
 * </ol>
 *
 * <p><b>连接池固定为 1 条连接</b>：只有复用同一条连接，才能真正证明归还后不会串租户 ——
 * 池大于 1 时每次可能拿到不同连接，「没串」可能只是碰巧换了条干净连接。
 *
 * <p>🔴 本模块的隔离面比别的模块**宽一层**：执行记录之外，
 * <b>逐步明细</b>也带 tenant_id —— 逐步明细里写着**业务字段**
 * （字段路径、动作目标、动作参数），串租户意味着「A 的规则执行细节出现在 B 的界面上」。
 */
@SpringBootTest
@AutoConfigureMockMvc
class PlatformAutomationRuleRunTenancyIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID();
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
  static UUID idFor(UUID tid, int ordinal) {
    return UUID.nameUUIDFromBytes(("starcon-platform-automation-run-" + tid + "-" + ordinal).getBytes());
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

  static Map<String, Object> map(Object... keyValues) {
    var out = new LinkedHashMap<String, Object>();
    for (int i = 0; i + 1 < keyValues.length; i += 2) {
      out.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
    }
    return out;
  }

  static {
    pg.start();
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      // 运行角色必须 NOBYPASSRLS，否则它会绕过策略，三条断言全部变成空断言。
      s.execute("create role starcon_app nologin");
      s.execute("create role platform_app login password 'test-only' nosuperuser nobypassrls");
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
    actors.put("session-a", actor(UUID.randomUUID(), a, PERMISSIONS));
    actors.put("session-b", actor(UUID.randomUUID(), b, PERMISSIONS));
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
    // 连接池压到 1：只有复用同一条连接，才能真正证明归还后不会串租户。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
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

  // ================================================================ 夹具

  @BeforeEach
  void resetThenSeed() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate table platform_automation_rule_run_step, platform_automation_rule_run,"
              + " platform_automation_rule_scope, platform_automation_rule_action,"
              + " platform_automation_rule_condition, platform_automation_rule,"
              + " platform_webhook_event_type restart identity cascade");
    }
    seedTenant(a, "tenant-a");
    seedTenant(b, "tenant-b");
  }

  /**
   * 在可信租户事务里插一条规则 + 一条执行记录 + 一条逐步明细。
   *
   * <p>🔴 逐步明细里写着**业务字段**（字段路径 / 动作目标）—— 串租户意味着
   * 「A 的规则执行细节出现在 B 的界面上」，这是本模块最严重的一类越界。
   */
  void seedTenant(UUID tid, String marker) {
    tenants.run(
        tid,
        () -> {
          var now = OffsetDateTime.now(ZoneOffset.UTC);
          sql.execute(
              "insert into platform_automation_rule(id,tenant_id,name,description,trigger_event_type,"
                  + " trigger_schema_version,priority,enabled,status,dry_run,"
                  + " consecutive_failure_threshold,remark,created_by,created_at,updated_at)"
                  + " values(?,?,?,'',?,1,100,true,'ACTIVE',false,3,'',null,?::timestamptz,"
                  + " ?::timestamptz)",
              idFor(tid, 1),
              tid,
              "规则-" + marker,
              EVENT_TYPE,
              now,
              now);
          sql.execute(
              "insert into platform_automation_rule_run(id,tenant_id,rule_id,rule_name,event_id,"
                  + " idempotency_key,trigger_event_type,triggered_by,event_payload,outcome,source,"
                  + " dry_run,side_effects_applied,matched_conditions,planned_actions,"
                  + " executed_actions,duration_ms,failure_reason,failure_class,error_code,"
                  + " error_message,not_attempted_reason,rerun_of_run_id,rerun_seq,"
                  + " rerun_requested_by,rerun_requested_at,rerun_reason,started_at,finished_at,"
                  + " created_by,created_at)"
                  + " values(?,?,?,?,?,?,?,?,?::jsonb,'FAILED','ENGINE',false,false,1,1,0,42,?,"
                  + " 'ACTION_FAILED','HTTP_500',?,'',null,null,null,null,'',?::timestamptz,"
                  + " ?::timestamptz,null,?::timestamptz)",
              idFor(tid, 2),
              tid,
              idFor(tid, 1),
              "规则-" + marker,
              "evt-" + marker,
              "evt:evt-" + marker,
              EVENT_TYPE,
              marker,
              "{\"orderNo\":\"SO-" + marker + "\"}",
              "失败原因-" + marker,
              "错误信息-" + marker,
              now,
              now,
              now);
          sql.execute(
              "insert into platform_automation_rule_run_step(id,tenant_id,run_id,step_no,step_kind,"
                  + " condition_id,condition_seq,field_path,operator,expected_value,actual_value,"
                  + " matched,action_type,target_ref,params,result,duration_ms,error_code,"
                  + " error_message,side_effect_applied,created_at)"
                  + " values(?,?,?,1,'ACTION',null,null,'','','','',null,'UPDATE_STATUS',?,"
                  + " '{}'::jsonb,'FAILED',7,'HTTP_500',?,false,?::timestamptz)",
              idFor(tid, 3),
              tid,
              idFor(tid, 2),
              "target-" + marker,
              "错误信息-" + marker,
              now);
          return null;
        });
  }

  /** 以指定租户身份读执行记录的事件 id。 */
  List<String> eventIdsOf(UUID tid) {
    return tenants.run(
        tid,
        () ->
            sql.fetch("select event_id from platform_automation_rule_run order by event_id")
                .map(r -> r.get(0, String.class)));
  }

  /** 以指定租户身份读逐步明细的动作目标。 */
  List<String> stepTargetsOf(UUID tid) {
    return tenants.run(
        tid,
        () ->
            sql.fetch("select target_ref from platform_automation_rule_run_step order by target_ref")
                .map(r -> r.get(0, String.class)));
  }

  /** 事务外读：没有任何租户上下文时能看见几行。 */
  int visibleWithoutTenant() {
    return sql.fetch("select id from platform_automation_rule_run").size();
  }

  /** 把异常（含 cause 链）的完整堆栈取成一段文本。 */
  static String stackTraceOf(Throwable error) {
    var out = new StringBuilder();
    for (var current = error; current != null; current = current.getCause()) {
      out.append(current).append('\n');
    }
    return out.toString();
  }

  // ==========================================================================
  // 迁移确实落地了，且 RLS 是**强制**的
  // ==========================================================================
  @Test
  void 本模块的两张表强制rls而不是只启用() throws Exception {
    for (String table : TABLES) {
      var row =
          sql.fetchOne(
              "select relrowsecurity, relforcerowsecurity from pg_class where relname = ?", table);
      assertThat(row).as("V422 必须真的建出 " + table).isNotNull();
      assertThat(row.get(0, Boolean.class)).as(table + " 未启用 RLS").isTrue();
      assertThat(row.get(1, Boolean.class))
          .as(table + " 只有 FORCE 才挡得住「运行角色被误授 owner」")
          .isTrue();
      assertThat(
              sql.fetchOne("select has_table_privilege('platform_app','" + table + "','SELECT')")
                  .get(0, Boolean.class))
          .as("platform_app 必须真的能读 " + table)
          .isTrue();
      var nullable =
          sql.fetchOne(
                  "select is_nullable from information_schema.columns where table_name = ? and column_name = 'tenant_id'",
                  table)
              .get(0, String.class);
      assertThat(nullable).as(table + " 的 tenant_id 必须是 NOT NULL").isEqualTo("NO");
    }
  }

  @Test
  void 执行台账是只追加的() throws Exception {
    assertThatThrownBy(
            () ->
                tenants.run(
                    a,
                    () ->
                        sql.execute(
                            "update platform_automation_rule_run set failure_reason = '改了'"
                                + " where id = ?",
                            idFor(a, 2))))
        .as("已结束的执行记录不可修改 —— 能改的执行历史等于没有执行历史")
        .hasStackTraceContaining("只追加");
    assertThatThrownBy(
            () ->
                tenants.run(
                    a,
                    () ->
                        sql.execute(
                            "delete from platform_automation_rule_run_step where id = ?",
                            idFor(a, 3))))
        .as("逐步明细不可删除")
        .hasStackTraceContaining("只追加");
  }

  // ==========================================================================
  // 断言 1 —— 两租户互不可见
  // ==========================================================================
  @Test
  void 断言1_两租户互不可见() throws Exception {
    assertThat(eventIdsOf(a)).containsExactly("evt-tenant-a");
    assertThat(eventIdsOf(b)).containsExactly("evt-tenant-b");

    // 更强的一格：A 明确按 ID 查 B 的行也拿不到（不只是列表过滤）。
    var byId =
        tenants.run(
            a,
            () ->
                sql.fetch("select event_id from platform_automation_rule_run where id = ?", idFor(b, 2))
                    .size());
    assertThat(byId).as("按主键直查也必须被 RLS 挡住").isZero();

    // 子表同理：逐步明细。
    var crossStep =
        tenants.run(
            a,
            () ->
                sql.fetch("select id from platform_automation_rule_run_step where id = ?", idFor(b, 3))
                    .size());
    assertThat(crossStep).as("按主键直查逐步明细也必须被挡").isZero();

    // 没有租户上下文时一行都看不见。
    assertThat(visibleWithoutTenant()).as("事务外必须一行都看不见").isZero();
  }

  @Test
  void 断言1_端点只看得到本租户的数据() throws Exception {
    var aRuns =
        json.readValue(
            http.perform(
                    get("/platform/automation/runs").header("Authorization", "session-a"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            AutomationRuleRunPage.class);
    assertThat(aRuns.items()).hasSize(1);
    assertThat(aRuns.items().get(0).eventId()).isEqualTo("evt-tenant-a");

    var bRuns =
        json.readValue(
            http.perform(
                    get("/platform/automation/runs").header("Authorization", "session-b"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            AutomationRuleRunPage.class);
    assertThat(bRuns.items()).hasSize(1);
    assertThat(bRuns.items().get(0).eventId()).isEqualTo("evt-tenant-b");

    // 🔴 A 直接按 B 的执行记录 id 取详情 ⇒ 取不到（B 的字段一个字都不许回给 A）。
    var crossTenant =
        http.perform(
                get("/platform/automation/runs/" + idFor(b, 2)).header("Authorization", "session-a"))
            .andReturn()
            .getResponse();
    assertThat(crossTenant.getStatus()).isBetween(400, 499);
    assertThat(crossTenant.getContentAsString()).doesNotContain("tenant-b");

    // 🔴 A 直接重跑 B 的执行记录 ⇒ 取不到（重跑入口同样按租户隔离）。
    var crossRerun =
        http.perform(
                post("/platform/automation/runs/" + idFor(b, 2) + "/rerun")
                    .header("Authorization", "session-a")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"))
            .andReturn()
            .getResponse();
    assertThat(crossRerun.getStatus()).isBetween(400, 499);
    assertThat(crossRerun.getContentAsString()).doesNotContain("tenant-b");
  }

  // ==========================================================================
  // 断言 2 —— 伪造租户上下文无效
  // ==========================================================================
  @Test
  void 断言2_伪造租户上下文无效() throws Exception {
    UUID forged = UUID.randomUUID();
    var now = OffsetDateTime.now(ZoneOffset.UTC);

    // (a) 事务外没有任何租户上下文：写入被**租户触发器**当场拒掉
    //     （「缺少租户上下文」—— 比 RLS 更早一道，理由写得更直白）。
    assertThatThrownBy(
            () ->
                sql.execute(
                    "insert into platform_automation_rule_run(id,tenant_id,rule_id,rule_name,"
                        + " event_id,idempotency_key,trigger_event_type,triggered_by,event_payload,"
                        + " outcome,source,dry_run,side_effects_applied,matched_conditions,"
                        + " planned_actions,executed_actions,duration_ms,failure_reason,failure_class,"
                        + " error_code,error_message,not_attempted_reason,rerun_of_run_id,rerun_seq,"
                        + " rerun_requested_by,rerun_requested_at,rerun_reason,started_at,finished_at,"
                        + " created_by,created_at)"
                        + " values(?,?,?,?,?,'evt:forged','order.created','x','{}'::jsonb,"
                        + " 'SUCCEEDED','MANUAL',false,false,0,0,0,0,'','','','','',null,null,null,"
                        + " null,'',?::timestamptz,?::timestamptz,null,?::timestamptz)",
                    UUID.randomUUID(),
                    forged,
                    idFor(a, 1),
                    "forged",
                    "evt-forged",
                    now,
                    now,
                    now))
        .as("没有可信租户上下文时，写入必须被拒 —— 租户不是客户端自称的")
        .hasStackTraceContaining("缺少租户上下文");

    // (b) 🔴 **伪造的 tenant_id**（在 A 的可信上下文里写 B 的租户号）⇒ RLS 当场拒掉。
    assertThatThrownBy(
            () ->
                tenants.run(
                    a,
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
                                + " values(?,?,?,?,?,'evt:forged2','order.created','x','{}'::jsonb,"
                                + " 'SUCCEEDED','MANUAL',false,false,0,0,0,0,'','','','','',null,null,"
                                + " null,null,'',?::timestamptz,?::timestamptz,null,?::timestamptz)",
                            UUID.randomUUID(),
                            forged,
                            idFor(a, 1),
                            "forged",
                            "evt-forged2",
                            now,
                            now,
                            now)))
        .as("在 A 的上下文里写别的租户号，必须被挡住")
        // ⚠️ 用 hasStackTraceContaining 而不是 hasMessageContaining：
        //   jOOQ 把 PG 的报错包成「bad SQL grammar」，真正的原因在 cause 里。
        //   两道防线都可能先接住它：**租户触发器**（42501）与 **RLS**（42501）；
        //   哪一道先接住都算「伪造的租户号写不进来」——
        //   RLS 本身由「强制 RLS」那条结构断言 + 断言1（两租户互不可见）独立证明。
        .satisfies(
            e ->
                assertThat(stackTraceOf(e))
                    .containsAnyOf("row-level security", "必须等于当前租户上下文"));

    // 伪造的行一条都没落库。
    assertThat(
            sql.fetch("select event_id from platform_automation_rule_run where event_id = 'evt-forged'")
                .size())
        .isZero();
    assertThat(eventIdsOf(a)).doesNotContain("evt-forged2");
  }

  @Test
  void 断言2_伪造的租户头改不动会话租户() throws Exception {
    // 伪造的 X-Tenant-Id 头 + 真实会话 ⇒ 行必须落在**会话租户**名下。
    var response =
        http.perform(
                post("/platform/automation/runs")
                    .header("Authorization", "session-a")
                    .header("X-Tenant-Id", b.toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(
                            map(
                                "ruleId", idFor(a, 1).toString(),
                                "eventId", "evt-forged-header",
                                "triggerEventType", EVENT_TYPE,
                                "outcome", "SUCCEEDED",
                                "sideEffectsApplied", false,
                                "executedActions", 0,
                                "plannedActions", 0,
                                "matchedConditions", 0))))
            .andReturn()
            .getResponse();
    assertThat(response.getStatus()).isEqualTo(200);

    // 🔴 行落在**会话租户**名下（不是自称的那个）。
    var landedIn =
        tenants.run(
            a,
            () ->
                sql.fetchOne(
                        "select tenant_id from platform_automation_rule_run where event_id = 'evt-forged-header'")
                    .get(0, UUID.class));
    assertThat(landedIn).as("伪造的租户头改不动会话租户").isEqualTo(a);

    // B 那边**看不见**这一行。
    assertThat(eventIdsOf(b)).doesNotContain("evt-forged-header");
  }

  // ==========================================================================
  // 断言 3 —— 连接池归还后不串租户
  // ==========================================================================
  @Test
  void 断言3_连接池归还后不串租户() throws Exception {
    // 连接池只有 1 条连接：交替 5 轮，每轮都必须只看得到自己租户的数据。
    for (var round = 0; round < 5; round++) {
      assertThat(eventIdsOf(a)).as("第 %s 轮：A 租户", round).containsExactly("evt-tenant-a");
      assertThat(eventIdsOf(b)).as("第 %s 轮：B 租户", round).containsExactly("evt-tenant-b");
      assertThat(stepTargetsOf(a)).as("第 %s 轮：A 的逐步明细", round)
          .containsExactly("target-tenant-a");
      assertThat(stepTargetsOf(b)).as("第 %s 轮：B 的逐步明细", round)
          .containsExactly("target-tenant-b");
    }
    // 事务外必须一行都看不见（连接归还后**不留残留**）。
    assertThat(visibleWithoutTenant()).as("连接归还后不得残留租户上下文").isZero();
  }

  @Test
  void 断言3_连接池归还后端点也不串租户() throws Exception {
    for (var round = 0; round < 5; round++) {
      var aPage =
          json.readValue(
              http.perform(get("/platform/automation/runs").header("Authorization", "session-a"))
                  .andExpect(status().isOk())
                  .andReturn()
                  .getResponse()
                  .getContentAsString(),
              AutomationRuleRunPage.class);
      assertThat(aPage.items()).as("第 %s 轮：A 的端点", round).hasSize(1);
      assertThat(aPage.items().get(0).eventId()).isEqualTo("evt-tenant-a");

      var bPage =
          json.readValue(
              http.perform(get("/platform/automation/runs").header("Authorization", "session-b"))
                  .andExpect(status().isOk())
                  .andReturn()
                  .getResponse()
                  .getContentAsString(),
              AutomationRuleRunPage.class);
      assertThat(bPage.items()).as("第 %s 轮：B 的端点", round).hasSize(1);
      assertThat(bPage.items().get(0).eventId()).isEqualTo("evt-tenant-b");
    }
  }

  @Test
  void 跨租户的规则挂不上执行日志() throws Exception {
    // 🔴 执行日志必须挂在本租户名下**真实存在**的规则上（复合 FK 兜底）。
    assertThatThrownBy(
            () ->
                tenants.run(
                    a,
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
                                + " values(?,?,?,?,?,'evt:cross','order.created','x','{}'::jsonb,"
                                + " 'SUCCEEDED','MANUAL',false,false,0,0,0,0,'','','','','',null,null,"
                                + " null,null,'',?::timestamptz,?::timestamptz,null,?::timestamptz)",
                            UUID.randomUUID(),
                            a,
                            idFor(b, 1),
                            "跨租户",
                            "evt-cross",
                            OffsetDateTime.now(ZoneOffset.UTC),
                            OffsetDateTime.now(ZoneOffset.UTC),
                            OffsetDateTime.now(ZoneOffset.UTC))))
        .as("挂到别的租户的规则上必须被外键挡住")
        .hasStackTraceContaining("foreign key");
  }
}
