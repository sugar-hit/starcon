package com.starcon.platform;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.platform.AutomationRuleEditorModels.*;
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
 * 18.3.1.2「规则编辑器」的**租户隔离**证明，跑在 AGENTS.md 明文要求的三条断言上。
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
 * <p>🔴 **本模块的隔离面比兄弟模块更窄但更深**：规则档案四张表由 18.3.1.1 拥有
 * （它的隔离由 {@code AutomationRuleTenancyIntegrationTest} 证明），
 * 本模块**自己**新增的事实只有一张表 {@code platform_automation_rule_test_run} ——
 * 但这张表里存着**样本载荷**与**条件求值明细**（业务字段值），
 * 串租户意味着「A 的订单样本出现在 B 的测试历史里」⇒ 一样是最严重的一类越界。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AutomationRuleEditorTenancyIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID();
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static HttpServer identity;

  static final ObjectMapper json =
      new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

  /** 本模块自己拥有的表（只有一张：样本测试台账）。 */
  static final List<String> TABLES = List.of("platform_automation_rule_test_run");

  static final List<String> PERMISSIONS =
      List.of(
          "platform.automation_rule_editor.read",
          "platform.automation_rule_editor.create",
          "platform.automation_rule_editor.test");

  static final String EVENT_TYPE = "order.created";

  /** 事件注册表里登记的 payload_schema（JSON Schema）—— 字段目录要从它摊平。 */
  static final String PAYLOAD_SCHEMA =
      """
      {
        "type": "object",
        "properties": {
          "order": {
            "type": "object",
            "properties": {
              "totalAmount": {"type": "number", "description": "订单金额"},
              "status": {"type": "string", "description": "订单状态",
                         "enum": ["CREATED", "PAID", "SHIPPED"]}
            },
            "required": ["totalAmount"]
          }
        },
        "required": ["order"]
      }
      """;

  /** 参照行 ID 按租户派生：主键是**全局** uuid，两个租户不能用同一批固定 UUID。 */
  static UUID idFor(UUID tenant, int ordinal) {
    return UUID.nameUUIDFromBytes(
        ("starcon-platform-automation-rule-editor-" + tenant + "-" + ordinal).getBytes());
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
          "truncate table platform_automation_rule_test_run, platform_automation_rule_scope,"
              + " platform_automation_rule_action, platform_automation_rule_condition,"
              + " platform_automation_rule, platform_webhook_event_type restart identity cascade");
    }
    seedTenant(a, "tenant-a");
    seedTenant(b, "tenant-b");
  }

  /**
   * 在可信租户事务里插：事件登记 + 规则档案 + 两条样本测试台账（一条草稿、一条挂规则）。
   *
   * <p>🔴 台账里存着**样本载荷**（业务字段值）—— 串租户意味着
   * 「A 的订单样本出现在 B 的测试历史里」，这是本模块最严重的一类越界。
   */
  void seedTenant(UUID tenant, String marker) {
    tenants.run(
        tenant,
        () -> {
          var now = OffsetDateTime.now(ZoneOffset.UTC);
          sql.execute(
              "insert into platform_webhook_event_type(id,tenant_id,event_type,schema_version,status,"
                  + " payload_schema,source_service,source_artifact,registered_at)"
                  + " values(?,?,?,1,'ACTIVE',?::jsonb,'trade','SkuDetailService.java:1',"
                  + " ?::timestamptz)",
              UUID.randomUUID(),
              tenant,
              EVENT_TYPE,
              PAYLOAD_SCHEMA,
              now);
          sql.execute(
              "insert into platform_automation_rule(id,tenant_id,name,description,trigger_event_type,"
                  + " trigger_schema_version,priority,enabled,status,dry_run,"
                  + " consecutive_failure_threshold,remark,created_at,updated_at)"
                  + " values(?,?,?,?,?,1,100,true,'ACTIVE',false,3,?,?::timestamptz,?::timestamptz)",
              idFor(tenant, 1),
              tenant,
              "规则-" + marker,
              "说明-" + marker,
              EVENT_TYPE,
              marker,
              now,
              now);
          // 台账 1：草稿测试（rule_id 为空）。
          sql.execute(
              "insert into platform_automation_rule_test_run(id,tenant_id,rule_id,rule_name,run_no,"
                  + " trigger_event_type,trigger_schema_version,config_snapshot,sample_payload,matched,"
                  + " total_conditions,matched_conditions,planned_actions,evaluation,failure_reason,"
                  + " created_by,created_at)"
                  + " values(?,?,null,?,1,?,1,?::jsonb,?::jsonb,true,1,1,1,?::jsonb,'',null,"
                  + " ?::timestamptz)",
              idFor(tenant, 2),
              tenant,
              "草稿-" + marker,
              EVENT_TYPE,
              "{\"name\":\"" + marker + "\"}",
              "{\"order\":{\"totalAmount\":100}}",
              "{\"conditions\":[],\"actions\":[],\"scopeApplicable\":true}",
              now);
          // 台账 2：挂在这条规则名下的测试（rule_id 有值 ⇒ FK 兜底）。
          sql.execute(
              "insert into platform_automation_rule_test_run(id,tenant_id,rule_id,rule_name,run_no,"
                  + " trigger_event_type,trigger_schema_version,config_snapshot,sample_payload,matched,"
                  + " total_conditions,matched_conditions,planned_actions,evaluation,failure_reason,"
                  + " created_by,created_at)"
                  + " values(?,?,?,?,1,?,1,?::jsonb,?::jsonb,true,1,1,1,?::jsonb,'',null,"
                  + " ?::timestamptz)",
              idFor(tenant, 3),
              tenant,
              idFor(tenant, 1),
              "规则-" + marker,
              EVENT_TYPE,
              "{\"name\":\"" + marker + "\"}",
              "{\"order\":{\"totalAmount\":200}}",
              "{\"conditions\":[],\"actions\":[],\"scopeApplicable\":true}",
              now);
          return null;
        });
  }

  /** 以指定租户身份读测试台账里的规则名。 */
  List<String> testRunNamesOf(UUID tenant) {
    return tenants.run(
        tenant,
        () ->
            sql.fetch("select rule_name from platform_automation_rule_test_run order by rule_name")
                .map(r -> r.get(0, String.class)));
  }

  /** 以指定租户身份读样本载荷（串租户 ⇒ 样本会串）。 */
  List<String> samplePayloadsOf(UUID tenant) {
    return tenants.run(
        tenant,
        () ->
            sql.fetch("select sample_payload::text from platform_automation_rule_test_run order by id")
                .map(r -> r.get(0, String.class)));
  }

  /** 事务外读：没有任何租户上下文时能看见几行。 */
  int visibleWithoutTenant() {
    return sql.fetch("select id from platform_automation_rule_test_run").size();
  }

  // ==========================================================================
  // 迁移确实落地了，且 RLS 是**强制**的
  // ==========================================================================
  @Test
  void 本模块的台账表强制rls而不是只启用() throws Exception {
    for (String table : TABLES) {
      var row =
          sql.fetchOne(
              "select relrowsecurity, relforcerowsecurity from pg_class where relname = ?", table);
      assertThat(row).as("V421 必须真的建出 " + table).isNotNull();
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
    assertThat(testRunNamesOf(a)).containsExactly("草稿-tenant-a", "规则-tenant-a");
    assertThat(testRunNamesOf(b)).containsExactly("草稿-tenant-b", "规则-tenant-b");

    // 更强的一格：A 明确按 ID 查 B 的行也拿不到（不只是列表过滤）。
    for (var ordinal : List.of(2, 3)) {
      var cross =
          tenants.run(
              a,
              () ->
                  sql.fetch(
                          "select id from platform_automation_rule_test_run where id = ?",
                          idFor(b, ordinal))
                      .size());
      assertThat(cross).as("按主键直查对方的测试台账也必须被挡").isZero();
    }

    // 🔴 样本载荷也不串（业务字段值最敏感）。
    assertThat(samplePayloadsOf(a)).noneMatch(p -> p.contains("tenant-b"));
    assertThat(samplePayloadsOf(b)).noneMatch(p -> p.contains("tenant-a"));

    // 没有租户上下文时一行都看不见。
    assertThat(visibleWithoutTenant()).as("事务外必须一行都看不见").isZero();
  }

  @Test
  void 断言1_端点只看得到本租户的测试历史() throws Exception {
    var aRuns =
        json.readValue(
            http.perform(
                    get("/platform/automation-rule-editor/test-runs")
                        .header("Authorization", "session-a"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            AutomationRuleEditorTestRunRow[].class);
    assertThat(aRuns).hasSize(2);
    assertThat(aRuns).allMatch(r -> r.ruleName().endsWith("tenant-a"));

    var bRuns =
        json.readValue(
            http.perform(
                    get("/platform/automation-rule-editor/test-runs")
                        .header("Authorization", "session-b"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            AutomationRuleEditorTestRunRow[].class);
    assertThat(bRuns).hasSize(2);
    assertThat(bRuns).allMatch(r -> r.ruleName().endsWith("tenant-b"));

    // 🔴 A 直接按 B 的规则 id 取测试历史 ⇒ 取不到（B 的字段一个字都不许回给 A）。
    var crossTenant =
        http.perform(
                get("/platform/automation-rule-editor/test-runs?ruleId=" + idFor(b, 1))
                    .header("Authorization", "session-a"))
            .andReturn()
            .getResponse();
    assertThat(crossTenant.getStatus()).isBetween(400, 499);
    assertThat(crossTenant.getContentAsString()).doesNotContain("tenant-b");
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
                    "insert into platform_automation_rule_test_run(id,tenant_id,rule_id,rule_name,"
                        + " run_no,trigger_event_type,trigger_schema_version,config_snapshot,"
                        + " sample_payload,matched,total_conditions,matched_conditions,planned_actions,"
                        + " evaluation,failure_reason,created_by,created_at)"
                        + " values(?,?,null,'forged',1,?,1,'{}'::jsonb,'{}'::jsonb,true,0,0,0,"
                        + " '{}'::jsonb,'',null,?::timestamptz)",
                    UUID.randomUUID(),
                    forged,
                    EVENT_TYPE,
                    now))
        .as("没有可信租户上下文时，写入必须被 RLS 挡住 —— 租户不是客户端自称的")
        // ⚠️ 用 hasStackTraceContaining 而不是 hasMessageContaining：
        //   jOOQ 把 PG 的报错包成「bad SQL grammar」，真正的原因在 cause 里。
        .hasStackTraceContaining("row-level security");

    // 伪造的行一条都没落库。
    assertThat(sql.fetch("select rule_name from platform_automation_rule_test_run").size())
        .isZero();
  }

  @Test
  void 断言2_伪造的租户头改不动会话租户() throws Exception {
    // 伪造的 X-Tenant-Id 头 + 真实会话 ⇒ 测试台账行必须落在**会话租户**名下。
    var response =
        http.perform(
                post("/platform/automation-rule-editor/test")
                    .header("Authorization", "session-a")
                    .header("X-Tenant-Id", b.toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(testRequest("伪造头写入的测试"))))
            .andReturn()
            .getResponse();
    assertThat(response.getStatus()).isEqualTo(200);

    // 行落在 A 名下（不是伪造的 B）。
    assertThat(
            tenants.run(a, () -> sql.fetch("select id from platform_automation_rule_test_run").size()))
        .as("行必须落在**会话租户**名下")
        .isEqualTo(3);
    assertThat(
            tenants.run(b, () -> sql.fetch("select id from platform_automation_rule_test_run").size()))
        .as("伪造的头不能把行写进别的租户")
        .isEqualTo(2);
    // 名字也落在 A 名下。
    assertThat(testRunNamesOf(a)).contains("伪造头写入的测试");
    assertThat(testRunNamesOf(b)).doesNotContain("伪造头写入的测试");
  }

  // ==========================================================================
  // 断言 3 —— 连接池归还后不串租户
  // ==========================================================================
  @Test
  void 断言3_连接池归还后不串租户() throws Exception {
    // 连接池固定为 1 条连接 ⇒ 每一次读写都复用**同一条**连接。
    for (int i = 0; i < 5; i++) {
      assertThat(testRunNamesOf(a)).containsExactly("草稿-tenant-a", "规则-tenant-a");
      assertThat(testRunNamesOf(b)).containsExactly("草稿-tenant-b", "规则-tenant-b");
      assertThat(samplePayloadsOf(a)).allMatch(p -> p.contains("100") || p.contains("200"));
      assertThat(samplePayloadsOf(b)).allMatch(p -> p.contains("100") || p.contains("200"));
      // 🔴 数量断言用 BigDecimal 比数值（不是标度）。
      assertThat(
              tenants.run(
                  a,
                  () ->
                      sql.fetch(
                              "select total_conditions from platform_automation_rule_test_run"
                                  + " order by id"))
                          .map(r -> r.get(0, BigDecimal.class)))
          .containsExactly(new BigDecimal("1.000000"), new BigDecimal("1.000000"));
    }
    // 交替读：连续两次读不同租户，中间不夹任何事务边界。
    assertThat(testRunNamesOf(a)).containsExactly("草稿-tenant-a", "规则-tenant-a");
    assertThat(testRunNamesOf(b)).containsExactly("草稿-tenant-b", "规则-tenant-b");
    assertThat(testRunNamesOf(a)).containsExactly("草稿-tenant-a", "规则-tenant-a");
  }

  // ==========================================================================
  // 附：样本测试端点本身也按租户隔离
  // ==========================================================================
  @Test
  void 样本测试端点在两个租户名下各自独立() throws Exception {
    var aResult =
        json.readValue(
            http.perform(
                    post("/platform/automation-rule-editor/test")
                        .header("Authorization", "session-a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(testRequest("A 的测试"))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            AutomationRuleEditorTestResult.class);
    assertThat(aResult.ruleName()).isEqualTo("A 的测试");

    var bResult =
        json.readValue(
            http.perform(
                    post("/platform/automation-rule-editor/test")
                        .header("Authorization", "session-b")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(testRequest("B 的测试"))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            AutomationRuleEditorTestResult.class);
    assertThat(bResult.ruleName()).isEqualTo("B 的测试");

    // 两边的测试序号**各自**从自己的最大值 +1 起算（不共用一个计数器）：
    // 夹具里两个租户各有一条草稿测试（run_no=1）⇒ 各自的新测试都是第 2 次。
    // 若序号是全局共用的，其中一边会变成 3。
    assertThat(aResult.runNo()).isEqualTo(2);
    assertThat(bResult.runNo()).isEqualTo(2);
    assertThat(testRunNamesOf(a)).contains("A 的测试").doesNotContain("B 的测试");
    assertThat(testRunNamesOf(b)).contains("B 的测试").doesNotContain("A 的测试");
  }

  // ---------------------------------------------------------------- 请求助手

  /** 一次样本测试请求体（草稿规则 + 样本载荷）。 */
  static Map<String, Object> testRequest(String ruleName) {
    return map(
        "rule",
            map(
                "name", ruleName,
                "triggerEventType", EVENT_TYPE,
                "conditions",
                    List.of(
                        map(
                            "fieldPath", "order.totalAmount",
                            "operator", "GT",
                            "valueNumber", "100")),
                "actions", List.of(),
                "scopes", List.of(map("scopeType", "TENANT"))),
        "payload", "{\"order\":{\"totalAmount\":250.5}}");
  }
}
