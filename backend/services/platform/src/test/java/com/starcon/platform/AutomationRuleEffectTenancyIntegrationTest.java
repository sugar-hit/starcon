package com.starcon.platform;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.platform.AutomationRuleEffectModels.*;
import com.starcon.tenancy.TenantTransactions;
import com.sun.net.httpserver.HttpServer;
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
 * 18.3.2.2「规则效果」的**租户隔离**证明，跑在 AGENTS.md 明文要求的三条断言上。
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
 * <p>🔴 本模块的隔离面比别的模块**宽一层**：除了本模块自己的三张表
 * （口径 / 反馈 / 建议），它还**聚合 18.3.2.1 的执行台账**与 18.3.1.1 的规则档案 ——
 * 「A 的规则执行次数出现在 B 的效果报表上」同样是一类越界，
 * 所以断言不只跑在本模块自己的表上，还钉住**聚合来源**。
 *
 * <p>🔴 另有一条独立断言钉住 <b>FORCE</b> RLS 而不是只 ENABLE，
 * 以及「反馈挂不上别的租户的执行记录（复合 FK 兜底）」。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AutomationRuleEffectTenancyIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID();
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
  static UUID idFor(UUID tid, int ordinal) {
    return UUID.nameUUIDFromBytes(
        ("starcon-platform-automation-effect-" + tid + "-" + ordinal).getBytes());
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
          "truncate table platform_automation_rule_effect_advice,"
              + " platform_automation_rule_effect_feedback,"
              + " platform_automation_rule_effect_baseline,"
              + " platform_automation_rule_run_step, platform_automation_rule_run,"
              + " platform_automation_rule_scope, platform_automation_rule_action,"
              + " platform_automation_rule_condition, platform_automation_rule,"
              + " platform_webhook_event_type restart identity cascade");
    }
    seedTenant(a, "tenant-a");
    seedTenant(b, "tenant-b");
  }

  /**
   * 在可信租户事务里插一条规则 + 两条执行记录 + 一条误触发反馈 + 一条口径 + 一条建议。
   *
   * <p>🔴 每个租户的数据里都带上**租户标记**（规则名 / 反馈说明 / 建议标题），
   * 串租户时能一眼看出是谁的数据漏到了谁那里。
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
          // 一次成功执行（耗时 1000ms）—— 聚合来源。
          sql.execute(
              "insert into platform_automation_rule_run(id,tenant_id,rule_id,rule_name,event_id,"
                  + " idempotency_key,trigger_event_type,triggered_by,event_payload,outcome,source,"
                  + " dry_run,side_effects_applied,matched_conditions,planned_actions,"
                  + " executed_actions,duration_ms,failure_reason,failure_class,error_code,"
                  + " error_message,not_attempted_reason,rerun_of_run_id,rerun_seq,"
                  + " rerun_requested_by,rerun_requested_at,rerun_reason,started_at,finished_at,"
                  + " created_by,created_at)"
                  + " values(?,?,?,?,?,?,?,?,'{}'::jsonb,'SUCCEEDED','MANUAL',false,false,1,1,0,"
                  + " 1000,'','','','','',null,null,null,null,'',?::timestamptz,?::timestamptz,"
                  + " null,?::timestamptz)",
              idFor(tid, 2),
              tid,
              idFor(tid, 1),
              "规则-" + marker,
              "evt-" + marker,
              "evt:evt-" + marker,
              EVENT_TYPE,
              marker,
              now,
              now,
              now);
          // 一条误触发反馈（指向本租户那次执行）。
          sql.execute(
              "insert into platform_automation_rule_effect_feedback(id,tenant_id,rule_id,"
                  + " rule_name,run_id,false_trigger_type,note,status,reported_by,created_at,"
                  + " updated_at)"
                  + " values(?,?,?,?,?,'WRONG_CONDITION',?,'OPEN',null,?::timestamptz,"
                  + " ?::timestamptz)",
              idFor(tid, 3),
              tid,
              idFor(tid, 1),
              "规则-" + marker,
              idFor(tid, 2),
              "反馈-" + marker,
              now,
              now);
          // 一条口径。
          sql.execute(
              "insert into platform_automation_rule_effect_baseline(id,tenant_id,rule_id,"
                  + " rule_name,manual_minutes_per_run,baseline_source,baseline_note,"
                  + " effective_from,created_by,created_at,updated_at)"
                  + " values(?,?,?,?,5,'MANUAL_ESTIMATE',?,?::timestamptz,null,?::timestamptz,"
                  + " ?::timestamptz)",
              idFor(tid, 4),
              tid,
              idFor(tid, 1),
              "规则-" + marker,
              "口径-" + marker,
              now,
              now,
              now);
          // 一条建议。
          sql.execute(
              "insert into platform_automation_rule_effect_advice(id,tenant_id,rule_id,rule_name,"
                  + " advice_type,severity,title,detail,evidence,source,status,active,target_module,"
                  + " target_ref,created_by,created_at,updated_at)"
                  + " values(?,?,?,?,'SLOW','INFO',?,?,?::jsonb,'MANUAL','OPEN',true,'18.3.1.1',?,"
                  + " null,?::timestamptz,?::timestamptz)",
              idFor(tid, 5),
              tid,
              idFor(tid, 1),
              "规则-" + marker,
              "建议-" + marker,
              "明细-" + marker,
              "{\"marker\":\"" + marker + "\"}",
              "/platform/automation-rules/rules/" + idFor(tid, 1),
              now,
              now);
          return null;
        });
  }

  /** 以指定租户身份读**反馈说明**（本模块表里带租户标记的字段）。 */
  List<String> feedbackNotesOf(UUID tid) {
    return tenants.run(
        tid,
        () ->
            sql.fetch("select note from platform_automation_rule_effect_feedback order by note")
                .map(r -> r.get(0, String.class)));
  }

  /** 以指定租户身份读**口径说明**。 */
  List<String> baselineNotesOf(UUID tid) {
    return tenants.run(
        tid,
        () ->
            sql.fetch(
                    "select baseline_note from platform_automation_rule_effect_baseline"
                        + " order by baseline_note")
                .map(r -> r.get(0, String.class)));
  }

  /** 以指定租户身份读**建议标题**。 */
  List<String> adviceTitlesOf(UUID tid) {
    return tenants.run(
        tid,
        () ->
            sql.fetch(
                    "select title from platform_automation_rule_effect_advice order by title")
                .map(r -> r.get(0, String.class)));
  }

  /** 以指定租户身份读**执行记录的事件 id**（聚合来源）。 */
  List<String> eventIdsOf(UUID tid) {
    return tenants.run(
        tid,
        () ->
            sql.fetch("select event_id from platform_automation_rule_run order by event_id")
                .map(r -> r.get(0, String.class)));
  }

  /** 事务外读：没有任何租户上下文时能看见几行。 */
  int visibleWithoutTenant() {
    return sql.fetch("select id from platform_automation_rule_effect_feedback").size();
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
  void 本模块的三张表强制rls而不是只启用() throws Exception {
    for (String table : TABLES) {
      var row =
          sql.fetchOne(
              "select relrowsecurity, relforcerowsecurity from pg_class where relname = ?", table);
      assertThat(row).as("V428 必须真的建出 " + table).isNotNull();
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
  void 数量与口径列都是numeric不是浮点() throws Exception {
    var type =
        sql.fetchOne(
                "select data_type, numeric_precision, numeric_scale"
                    + " from information_schema.columns where table_name = ? and column_name = ?",
                "platform_automation_rule_effect_baseline",
                "manual_minutes_per_run");
    assertThat(type.get("data_type", String.class)).isEqualTo("numeric");
    assertThat(type.get("numeric_precision", Integer.class)).isEqualTo(19);
    assertThat(type.get("numeric_scale", Integer.class)).isEqualTo(6);
  }

  // ==========================================================================
  // 断言 1 —— 两租户互不可见
  // ==========================================================================
  @Test
  void 断言1_两租户互不可见() throws Exception {
    assertThat(feedbackNotesOf(a)).containsExactly("反馈-tenant-a");
    assertThat(feedbackNotesOf(b)).containsExactly("反馈-tenant-b");
    assertThat(baselineNotesOf(a)).containsExactly("口径-tenant-a");
    assertThat(baselineNotesOf(b)).containsExactly("口径-tenant-b");
    assertThat(adviceTitlesOf(a)).containsExactly("建议-tenant-a");
    assertThat(adviceTitlesOf(b)).containsExactly("建议-tenant-b");

    // 更强的一格：A 明确按 ID 查 B 的行也拿不到（不只是列表过滤）。
    var byId =
        tenants.run(
            a,
            () ->
                sql.fetch(
                        "select id from platform_automation_rule_effect_feedback where id = ?",
                        idFor(b, 3))
                    .size());
    assertThat(byId).as("按主键直查也必须被 RLS 挡住").isZero();

    // 没有租户上下文时一行都看不见。
    assertThat(visibleWithoutTenant()).as("事务外必须一行都看不见").isZero();
  }

  @Test
  void 断言1_端点只看得到本租户的数据() throws Exception {
    var aFeedbacks =
        json.readValue(
            http.perform(
                    get("/platform/automation/effect/feedbacks")
                        .header("Authorization", "session-a"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            AutomationRuleEffectFeedbackPage.class);
    assertThat(aFeedbacks.items()).hasSize(1);
    assertThat(aFeedbacks.items().get(0).note()).isEqualTo("反馈-tenant-a");

    var bFeedbacks =
        json.readValue(
            http.perform(
                    get("/platform/automation/effect/feedbacks")
                        .header("Authorization", "session-b"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            AutomationRuleEffectFeedbackPage.class);
    assertThat(bFeedbacks.items()).hasSize(1);
    assertThat(bFeedbacks.items().get(0).note()).isEqualTo("反馈-tenant-b");

    // 🔴 A 直接按 B 的规则 id 取效果详情 ⇒ 取不到（B 的字段一个字都不许回给 A）。
    var crossTenant =
        http.perform(
                get("/platform/automation/effect/rules/" + idFor(b, 1))
                    .header("Authorization", "session-a"))
            .andReturn()
            .getResponse();
    assertThat(crossTenant.getStatus()).isBetween(400, 499);
    assertThat(crossTenant.getContentAsString()).doesNotContain("tenant-b");

    // 🔴 **聚合来源**也不许串：A 的效果报表里看不到 B 的执行记录。
    var aOverview =
        json.readValue(
            http.perform(
                    get("/platform/automation/effect/overview")
                        .header("Authorization", "session-a"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            AutomationRuleEffectOverview.class);
    assertThat(aOverview.totalRuns()).isEqualTo(1);
    assertThat(aOverview.rulesWithRuns()).isEqualTo(1);
    assertThat(aOverview.falseTriggerFeedbacks()).isEqualTo(1);

    var bOverview =
        json.readValue(
            http.perform(
                    get("/platform/automation/effect/overview")
                        .header("Authorization", "session-b"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            AutomationRuleEffectOverview.class);
    assertThat(bOverview.totalRuns()).isEqualTo(1);
    assertThat(bOverview.rulesWithRuns()).isEqualTo(1);
    assertThat(bOverview.falseTriggerFeedbacks()).isEqualTo(1);
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
                    "insert into platform_automation_rule_effect_feedback(id,tenant_id,rule_id,"
                        + " rule_name,run_id,false_trigger_type,note,status,reported_by,created_at,"
                        + " updated_at)"
                        + " values(?,?,?,?,null,'OTHER','forged','OPEN',null,?::timestamptz,"
                        + " ?::timestamptz)",
                    UUID.randomUUID(),
                    forged,
                    idFor(a, 1),
                    "forged",
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
                            "insert into platform_automation_rule_effect_feedback(id,tenant_id,"
                                + " rule_id,rule_name,run_id,false_trigger_type,note,status,"
                                + " reported_by,created_at,updated_at)"
                                + " values(?,?,?,?,null,'OTHER','forged2','OPEN',null,"
                                + " ?::timestamptz,?::timestamptz)",
                            UUID.randomUUID(),
                            forged,
                            idFor(a, 1),
                            "forged",
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
    assertThat(feedbackNotesOf(a)).doesNotContain("forged", "forged2");
    assertThat(feedbackNotesOf(b)).doesNotContain("forged", "forged2");
  }

  @Test
  void 断言2_伪造的租户头改不动会话租户() throws Exception {
    // 伪造的 X-Tenant-Id 头 + 真实会话 ⇒ 行必须落在**会话租户**名下。
    var response =
        http.perform(
                post("/platform/automation/effect/feedbacks")
                    .header("Authorization", "session-a")
                    .header("X-Tenant-Id", b.toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(
                            map(
                                "ruleId", idFor(a, 1).toString(),
                                "falseTriggerType", "OTHER",
                                "note", "forged-header"))))
            .andReturn()
            .getResponse();
    assertThat(response.getStatus()).isEqualTo(200);

    // 🔴 行落在**会话租户**名下（不是自称的那个）。
    var landedIn =
        tenants.run(
            a,
            () ->
                sql.fetchOne(
                        "select tenant_id from platform_automation_rule_effect_feedback"
                            + " where note = 'forged-header'")
                    .get(0, UUID.class));
    assertThat(landedIn).as("伪造的租户头改不动会话租户").isEqualTo(a);

    // B 那边**看不见**这一行。
    assertThat(feedbackNotesOf(b)).doesNotContain("forged-header");
  }

  // ==========================================================================
  // 断言 3 —— 连接池归还后不串租户
  // ==========================================================================
  @Test
  void 断言3_连接池归还后不串租户() throws Exception {
    // 连接池只有 1 条连接：交替 5 轮，每轮都必须只看得到自己租户的数据。
    for (var round = 0; round < 5; round++) {
      assertThat(feedbackNotesOf(a)).as("第 %s 轮：A 的反馈", round).containsExactly("反馈-tenant-a");
      assertThat(feedbackNotesOf(b)).as("第 %s 轮：B 的反馈", round).containsExactly("反馈-tenant-b");
      assertThat(baselineNotesOf(a)).as("第 %s 轮：A 的口径", round).containsExactly("口径-tenant-a");
      assertThat(baselineNotesOf(b)).as("第 %s 轮：B 的口径", round).containsExactly("口径-tenant-b");
      assertThat(adviceTitlesOf(a)).as("第 %s 轮：A 的建议", round).containsExactly("建议-tenant-a");
      assertThat(adviceTitlesOf(b)).as("第 %s 轮：B 的建议", round).containsExactly("建议-tenant-b");
      // 聚合来源（上游执行台账）同样不许串。
      assertThat(eventIdsOf(a)).as("第 %s 轮：A 的执行记录", round).containsExactly("evt-tenant-a");
      assertThat(eventIdsOf(b)).as("第 %s 轮：B 的执行记录", round).containsExactly("evt-tenant-b");
    }
    // 事务外必须一行都看不见（连接归还后**不留残留**）。
    assertThat(visibleWithoutTenant()).as("连接归还后不得残留租户上下文").isZero();
  }

  @Test
  void 断言3_连接池归还后端点也不串租户() throws Exception {
    for (var round = 0; round < 5; round++) {
      var aPage =
          json.readValue(
              http.perform(
                      get("/platform/automation/effect/feedbacks")
                          .header("Authorization", "session-a"))
                  .andExpect(status().isOk())
                  .andReturn()
                  .getResponse()
                  .getContentAsString(),
              AutomationRuleEffectFeedbackPage.class);
      assertThat(aPage.items()).as("第 %s 轮：A 的端点", round).hasSize(1);
      assertThat(aPage.items().get(0).note()).isEqualTo("反馈-tenant-a");

      var bPage =
          json.readValue(
              http.perform(
                      get("/platform/automation/effect/feedbacks")
                          .header("Authorization", "session-b"))
                  .andExpect(status().isOk())
                  .andReturn()
                  .getResponse()
                  .getContentAsString(),
              AutomationRuleEffectFeedbackPage.class);
      assertThat(bPage.items()).as("第 %s 轮：B 的端点", round).hasSize(1);
      assertThat(bPage.items().get(0).note()).isEqualTo("反馈-tenant-b");
    }
  }

  // ==========================================================================
  // 跨租户挂不上（复合 FK 兜底）
  // ==========================================================================
  @Test
  void 跨租户的规则挂不上口径与反馈() throws Exception {
    // 🔴 口径必须挂在本租户名下**真实存在**的规则上（复合 FK 兜底）。
    assertThatThrownBy(
            () ->
                tenants.run(
                    a,
                    () ->
                        sql.execute(
                            "insert into platform_automation_rule_effect_baseline(id,tenant_id,"
                                + " rule_id,rule_name,manual_minutes_per_run,baseline_source,"
                                + " baseline_note,effective_from,created_by,created_at,updated_at)"
                                + " values(?,?,?,?,5,'MANUAL_ESTIMATE','跨租户',?::timestamptz,null,"
                                + " ?::timestamptz,?::timestamptz)",
                            UUID.randomUUID(),
                            a,
                            idFor(b, 1),
                            "跨租户",
                            OffsetDateTime.now(ZoneOffset.UTC),
                            OffsetDateTime.now(ZoneOffset.UTC),
                            OffsetDateTime.now(ZoneOffset.UTC))))
        .as("挂到别的租户的规则上必须被外键挡住")
        .hasStackTraceContaining("foreign key");

    // 🔴 反馈指向**别的租户**的执行记录 ⇒ 被挡住。
    //   ⚠️ 两道防线都可能先接住它：**触发器**（BEFORE，钉死「run 必须属于同一条规则」）
    //   与**复合 FK**（AFTER）。PG 按触发器名字的字母序跑 BEFORE 触发器 ⇒
    //   本例中「run_guard」先于「tenant_guard」接住它 ⇒ 断言两句话都接受，
    //   真正要证明的是「**跨租户的引用挂不上**」这一件事。
    assertThatThrownBy(
            () ->
                tenants.run(
                    a,
                    () ->
                        sql.execute(
                            "insert into platform_automation_rule_effect_feedback(id,tenant_id,"
                                + " rule_id,rule_name,run_id,false_trigger_type,note,status,"
                                + " reported_by,created_at,updated_at)"
                                + " values(?,?,?,?,?,'OTHER','跨租户','OPEN',null,?::timestamptz,"
                                + " ?::timestamptz)",
                            UUID.randomUUID(),
                            a,
                            idFor(a, 1),
                            "跨租户",
                            idFor(b, 2),
                            OffsetDateTime.now(ZoneOffset.UTC),
                            OffsetDateTime.now(ZoneOffset.UTC))))
        .as("指向别的租户的执行记录必须被挡住")
        .satisfies(
            e ->
                assertThat(stackTraceOf(e))
                    .containsAnyOf(
                        "foreign key", "误触发反馈指向的执行记录不存在或不属于本租户"));
  }
}
