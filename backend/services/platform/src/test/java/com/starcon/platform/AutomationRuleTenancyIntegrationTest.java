package com.starcon.platform;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.platform.AutomationRuleModels.*;
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
 * 18.3.1.1「规则列表」的**租户隔离**证明，跑在 AGENTS.md 明文要求的三条断言上。
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
 * <p>🔴 本模块的隔离面比别的模块**宽一层**：规则档案之外，
 * 条件 / 动作 / 启用范围 / 执行台账**五张表**都带 tenant_id ——
 * 条件与动作里写着业务字段（字段路径、动作目标），
 * 串租户意味着「A 的规则条件出现在 B 的界面上」。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AutomationRuleTenancyIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID();
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static HttpServer identity;

  static final ObjectMapper json =
      new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

  /** 本模块的五张表。 */
  static final List<String> TABLES =
      List.of(
          "platform_automation_rule",
          "platform_automation_rule_condition",
          "platform_automation_rule_action",
          "platform_automation_rule_scope",
          "platform_automation_rule_execution");

  static final List<String> PERMISSIONS =
      List.of(
          "platform.automation_rule.read",
          "platform.automation_rule.create",
          "platform.automation_rule.update",
          "platform.automation_rule.record");

  static final String EVENT_TYPE = "order.created";

  /** 参照行 ID 按租户派生：主键是**全局** uuid，两个租户不能用同一批固定 UUID。 */
  static UUID idFor(UUID tenant, int ordinal) {
    return UUID.nameUUIDFromBytes(("starcon-platform-automation-rule-" + tenant + "-" + ordinal).getBytes());
  }

  static AccessService.Access actor(UUID uid, UUID tenant, List<String> permissions) {
    return new AccessService.Access(
        uid,
        tenant,
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

  static void respond(HttpExchange e, int status, Object body) throws IOException {
    byte[] bytes = json.writeValueAsBytes(body);
    e.getResponseHeaders().add("Content-Type", "application/json");
    e.sendResponseHeaders(status, bytes.length);
    e.getResponseBody().write(bytes);
    e.close();
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
    r.add("CHANNEL_URL", () -> "http://127.0.0.1:1");
    r.add("WAREHOUSE_URL", () -> "http://127.0.0.1:1");
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
          "truncate table platform_automation_rule_execution, platform_automation_rule_scope,"
              + " platform_automation_rule_action, platform_automation_rule_condition,"
              + " platform_automation_rule, platform_webhook_event_type restart identity cascade");
    }
    seedTenant(a, "tenant-a");
    seedTenant(b, "tenant-b");
  }

  /**
   * 在可信租户事务里插一条规则 + 条件 + 动作 + 范围 + 一条执行记录。
   *
   * <p>🔴 条件里写着业务字段（字段路径 / 动作目标）—— 串租户意味着
   * 「A 的规则条件出现在 B 的界面上」，这是本模块最严重的一类越界。
   */
  void seedTenant(UUID tenant, String marker) {
    tenants.run(
        tenant,
        () -> {
          var now = OffsetDateTime.now(ZoneOffset.UTC);
          sql.execute(
              "insert into platform_webhook_event_type(id,tenant_id,event_type,schema_version,status,"
                  + " payload_schema,source_service,source_artifact,registered_at)"
                  + " values(?,?,?,1,'ACTIVE','{}'::jsonb,'trade','SkuDetailService.java:1',"
                  + " ?::timestamptz)",
              UUID.randomUUID(),
              tenant,
              EVENT_TYPE,
              now);
          sql.execute(
              "insert into platform_automation_rule(id,tenant_id,name,description,trigger_event_type,"
                  + " trigger_schema_version,priority,enabled,status,dry_run,"
                  + " consecutive_failure_threshold,last_run_at,last_run_status,last_run_failure_reason,"
                  + " last_run_execution_id,run_count,success_count,failure_count,"
                  + " consecutive_failure_count,failing,failing_since,remark,created_by,created_at,"
                  + " updated_at)"
                  + " values(?,?,?,? ,?,1,100,true,'ACTIVE',false,3,?::timestamptz,'SUCCEEDED','',"
                  + " ?::uuid,1,1,0,0,false,null,?,null,?::timestamptz,?::timestamptz)",
              idFor(tenant, 1),
              tenant,
              "规则-" + marker,
              "说明-" + marker,
              EVENT_TYPE,
              now,
              idFor(tenant, 5),
              marker,
              now,
              now);
          sql.execute(
              "insert into platform_automation_rule_condition(id,tenant_id,rule_id,seq,field_path,"
                  + " operator,value_text,value_number,value_bool,enabled,remark,created_at)"
                  + " values(?,?,?,1,'order.totalAmount','GT','',100,null,true,?,?::timestamptz)",
              idFor(tenant, 2),
              tenant,
              idFor(tenant, 1),
              marker,
              now);
          sql.execute(
              "insert into platform_automation_rule_action(id,tenant_id,rule_id,seq,action_type,"
                  + " target_ref,params,enabled,remark,created_at)"
                  + " values(?,?,?,1,'SEND_NOTIFICATION',?,'{}'::jsonb,true,?,?::timestamptz)",
              idFor(tenant, 3),
              tenant,
              idFor(tenant, 1),
              "tpl." + marker,
              marker,
              now);
          sql.execute(
              "insert into platform_automation_rule_scope(id,tenant_id,rule_id,scope_type,scope_ref,"
                  + " scope_label,created_at)"
                  + " values(?,?,?,'TENANT',null,?,?::timestamptz)",
              idFor(tenant, 4),
              tenant,
              idFor(tenant, 1),
              "范围-" + marker,
              now);
          sql.execute(
              "insert into platform_automation_rule_execution(id,tenant_id,rule_id,run_no,status,"
                  + " source,dry_run,side_effects_applied,triggered_by,trigger_event_type,"
                  + " matched_conditions,planned_actions,executed_actions,failure_reason,"
                  + " failure_class,started_at,finished_at,created_by,created_at)"
                  + " values(?,?,?,1,'SUCCEEDED','MANUAL',false,true,?,?,1,1,1,'','',"
                  + " ?::timestamptz,?::timestamptz,null,?::timestamptz)",
              idFor(tenant, 5),
              tenant,
              idFor(tenant, 1),
              marker,
              EVENT_TYPE,
              now,
              now,
              now);
          return null;
        });
  }

  /** 以指定租户身份读规则名（串租户意味着名字也会串）。 */
  List<String> ruleNamesOf(UUID tenant) {
    return tenants.run(
        tenant,
        () ->
            sql.fetch("select name from platform_automation_rule order by name")
                .map(r -> r.get(0, String.class)));
  }

  /** 以指定租户身份读条件字段路径。 */
  List<String> conditionPathsOf(UUID tenant) {
    return tenants.run(
        tenant,
        () ->
            sql.fetch("select field_path from platform_automation_rule_condition order by field_path")
                .map(r -> r.get(0, String.class)));
  }

  /** 以指定租户身份读执行记录的动作数。 */
  List<BigDecimal> executedActionsOf(UUID tenant) {
    return tenants.run(
        tenant,
        () ->
            sql.fetch(
                    "select executed_actions from platform_automation_rule_execution order by run_no")
                .map(r -> r.get(0, BigDecimal.class)));
  }

  /** 事务外读：没有任何租户上下文时能看见几行。 */
  int visibleWithoutTenant() {
    return sql.fetch("select id from platform_automation_rule").size();
  }

  // ==========================================================================
  // 迁移确实落地了，且 RLS 是**强制**的
  // ==========================================================================
  @Test
  void 本模块的五张表强制rls而不是只启用() throws Exception {
    for (String table : TABLES) {
      var row =
          sql.fetchOne(
              "select relrowsecurity, relforcerowsecurity from pg_class where relname = ?", table);
      assertThat(row).as("V419 必须真的建出 " + table).isNotNull();
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

  // ==========================================================================
  // 断言 1 —— 两租户互不可见
  // ==========================================================================
  @Test
  void 断言1_两租户互不可见() throws Exception {
    assertThat(ruleNamesOf(a)).containsExactly("规则-tenant-a");
    assertThat(ruleNamesOf(b)).containsExactly("规则-tenant-b");

    // 更强的一格：A 明确按 ID 查 B 的行也拿不到（不只是列表过滤）。
    var byId =
        tenants.run(
            a,
            () ->
                sql.fetch("select name from platform_automation_rule where id = ?", idFor(b, 1))
                    .size());
    assertThat(byId).as("按主键直查也必须被 RLS 挡住").isZero();

    // 子表同理：条件 / 动作 / 范围 / 执行台账。
    for (var ordinal : List.of(2, 3, 4, 5)) {
      var table =
          switch (ordinal) {
            case 2 -> "platform_automation_rule_condition";
            case 3 -> "platform_automation_rule_action";
            case 4 -> "platform_automation_rule_scope";
            default -> "platform_automation_rule_execution";
          };
      var cross =
          tenants.run(
              a,
              () -> sql.fetch("select id from " + table + " where id = ?", idFor(b, ordinal)).size());
      assertThat(cross).as("按主键直查对方的 " + table + " 也必须被挡").isZero();
    }

    // 没有租户上下文时一行都看不见。
    assertThat(visibleWithoutTenant()).as("事务外必须一行都看不见").isZero();
  }

  @Test
  void 断言1_端点只看得到本租户的数据() throws Exception {
    var aRules =
        json.readValue(
            http.perform(
                    get("/platform/automation-rules/rules").header("Authorization", "session-a"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            AutomationRuleRow[].class);
    assertThat(aRules).hasSize(1);
    assertThat(aRules[0].name()).isEqualTo("规则-tenant-a");

    var bRules =
        json.readValue(
            http.perform(
                    get("/platform/automation-rules/rules").header("Authorization", "session-b"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            AutomationRuleRow[].class);
    assertThat(bRules).hasSize(1);
    assertThat(bRules[0].name()).isEqualTo("规则-tenant-b");

    // 🔴 A 直接按 B 的规则 id 取详情 ⇒ 取不到（B 的字段一个字都不许回给 A）。
    var crossTenant =
        http.perform(
                get("/platform/automation-rules/rules/" + idFor(b, 1))
                    .header("Authorization", "session-a"))
            .andReturn()
            .getResponse();
    assertThat(crossTenant.getStatus()).isBetween(400, 499);
    assertThat(crossTenant.getContentAsString()).doesNotContain("tenant-b");

    // 🔴 A 直接按 B 的规则 id 取执行历史 ⇒ 同样取不到。
    var crossExecutions =
        http.perform(
                get("/platform/automation-rules/rules/" + idFor(b, 1) + "/executions")
                    .header("Authorization", "session-a"))
            .andReturn()
            .getResponse();
    assertThat(crossExecutions.getStatus()).isBetween(400, 499);
    assertThat(crossExecutions.getContentAsString()).doesNotContain("tenant-b");
  }

  // ==========================================================================
  // 断言 2 —— 伪造租户上下文无效
  // ==========================================================================
  @Test
  void 断言2_伪造租户上下文无效() throws Exception {
    // 事务外没有任何租户上下文：伪造一个 tenant_id 往里写，策略直接拒绝。
    UUID forged = UUID.randomUUID();
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    assertThatThrownBy(
            () ->
                sql.execute(
                    "insert into platform_automation_rule(id,tenant_id,name,description,"
                        + " trigger_event_type,trigger_schema_version,priority,enabled,status,dry_run,"
                        + " consecutive_failure_threshold,remark,created_by,created_at,updated_at)"
                        + " values(?,?,'forged','',?,1,100,true,'ACTIVE',false,3,'',null,"
                        + " ?::timestamptz,?::timestamptz)",
                    UUID.randomUUID(),
                    forged,
                    EVENT_TYPE,
                    now,
                    now))
        .as("没有可信租户上下文时，写入必须被 RLS 挡住 —— 租户不是客户端自称的")
        // ⚠️ 用 hasStackTraceContaining 而不是 hasMessageContaining：
        //   jOOQ 把 PG 的报错包成「bad SQL grammar」，真正的原因在 cause 里。
        .hasStackTraceContaining("row-level security");

    // 伪造的行一条都没落库。
    assertThat(sql.fetch("select name from platform_automation_rule where name = 'forged'").size())
        .isZero();
  }

  @Test
  void 断言2_伪造的租户头改不动会话租户() throws Exception {
    // 伪造的 X-Tenant-Id 头 + 真实会话 ⇒ 行必须落在**会话租户**名下。
    var response =
        http.perform(
                post("/platform/automation-rules/rules")
                    .header("Authorization", "session-a")
                    .header("X-Tenant-Id", b.toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(
                            map(
                                "name", "伪造头写入的规则",
                                "triggerEventType", EVENT_TYPE,
                                "scopes", List.of(map("scopeType", "TENANT"))))))
            .andReturn()
            .getResponse();
    assertThat(response.getStatus()).isEqualTo(200);

    // 行落在 A 名下（不是伪造的 B）。
    assertThat(
            tenants.run(a, () -> sql.fetch("select id from platform_automation_rule").size()))
        .as("行必须落在**会话租户**名下")
        .isEqualTo(2);
    assertThat(
            tenants.run(b, () -> sql.fetch("select id from platform_automation_rule").size()))
        .as("伪造的头不能把行写进别的租户")
        .isEqualTo(1);
    // 名字也落在 A 名下。
    assertThat(ruleNamesOf(a)).contains("伪造头写入的规则");
    assertThat(ruleNamesOf(b)).doesNotContain("伪造头写入的规则");
  }

  // ==========================================================================
  // 断言 3 —— 连接池归还后不串租户
  // ==========================================================================
  @Test
  void 断言3_连接池归还后不串租户() throws Exception {
    // 连接池固定为 1 条连接 ⇒ 每一次读写都复用**同一条**连接。
    for (int i = 0; i < 5; i++) {
      assertThat(ruleNamesOf(a)).containsExactly("规则-tenant-a");
      assertThat(ruleNamesOf(b)).containsExactly("规则-tenant-b");
      assertThat(conditionPathsOf(a)).containsExactly("order.totalAmount");
      assertThat(conditionPathsOf(b)).containsExactly("order.totalAmount");
      assertThat(executedActionsOf(a)).containsExactly(new BigDecimal("1.000000"));
      assertThat(executedActionsOf(b)).containsExactly(new BigDecimal("1.000000"));
    }
    // 交替读：连续两次读不同租户，中间不夹任何事务边界。
    assertThat(ruleNamesOf(a)).containsExactly("规则-tenant-a");
    assertThat(ruleNamesOf(b)).containsExactly("规则-tenant-b");
    assertThat(ruleNamesOf(a)).containsExactly("规则-tenant-a");
  }

  // ==========================================================================
  // 附：规则档案上的执行统计不许跨租户出现
  // ==========================================================================
  @Test
  void 执行统计在两个租户名下各自独立() throws Exception {
    var aRule =
        json.readValue(
            http.perform(
                    get("/platform/automation-rules/rules/" + idFor(a, 1))
                        .header("Authorization", "session-a"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            AutomationRuleDetail.class);
    assertThat(aRule.rule().lastRunStatus()).isEqualTo("SUCCEEDED");
    assertThat(aRule.rule().runCount()).isEqualByComparingTo(BigDecimal.ONE);
    assertThat(aRule.recentExecutions()).hasSize(1);
    assertThat(aRule.recentExecutions().get(0).ruleName()).isEqualTo("规则-tenant-a");

    var bRule =
        json.readValue(
            http.perform(
                    get("/platform/automation-rules/rules/" + idFor(b, 1))
                        .header("Authorization", "session-b"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            AutomationRuleDetail.class);
    assertThat(bRule.recentExecutions().get(0).ruleName()).isEqualTo("规则-tenant-b");
  }
}
