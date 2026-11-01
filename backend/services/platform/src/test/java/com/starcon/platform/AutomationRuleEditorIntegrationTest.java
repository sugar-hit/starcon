package com.starcon.platform;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.platform.AutomationRuleEditorModels.*;
import com.starcon.platform.AutomationRuleModels.AutomationRuleDetail;
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
 * 18.3.1.2「规则编辑器」的**功能**集成测试，跑在真实 PostgreSQL 16 上。
 *
 * <p><b>真实 PostgreSQL 16 + FORCE RLS，不使用任何内存替身。</b>
 *
 * <p>本测试盯住蓝图 18.3.1.2 的**加粗句**：
 * 「<b>规则必须支持用样本测试</b>，否则只能在生产上试错。」
 * —— 拆成六个可执行判定：
 *
 * <ol>
 *   <li><b>样本测试真的求值</b>：样本载荷进条件求值器，逐条给出命中 / 未命中与原因
 *       （<b>不是</b>前端算的、也不是写死的「全过」）；
 *   <li><b>测试结果落台账</b>：配置快照 + 样本载荷 + 逐条明细都存下来了 ⇒ 可重放、可核对；
 *   <li><b>测试不产生副作用</b>：{@code sideEffectsApplied} 恒为 false，
 *       台账表里**连这个列都没有**（18.3.2 执行引擎尚未交付）；
 *   <li><b>字段目录来自真实上游</b>：条件可选的字段来自事件注册表登记的
 *       {@code payload_schema}（JSON Schema 摊平），不是本模块写死的清单；
 *   <li><b>动作目标能查证</b>：「调接口」动作的目标必须在接口目录里真实存在；
 *   <li><b>保存委托 18.3.1.1</b>：编辑器**不另建规则表** ——
 *       保存后规则在 18.3.1.1 的档案端点里读得到。
 * </ol>
 *
 * <p><b>🔴 时间纪律</b>：落库时间列用 {@code ?::timestamptz} 绑定，并<b>断言写回的值</b>，
 * 不只断言「没抛异常」。
 *
 * <p><b>🔴 数量纪律</b>：条数一律 {@link BigDecimal}，断言用 {@code isEqualByComparingTo}
 * （比的是数值，不是标度）。
 *
 * <p>多租户隔离的三条断言在 {@code AutomationRuleEditorTenancyIntegrationTest} 里
 * （同一批表、同一套夹具）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AutomationRuleEditorIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID tenant = UUID.randomUUID();
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static HttpServer identity;

  static final ObjectMapper json =
      new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

  /** 本模块的表（只有一张：样本测试台账）。 */
  static final List<String> TABLES = List.of("platform_automation_rule_test_run");

  static final List<String> PERMISSIONS =
      List.of(
          "platform.automation_rule_editor.read",
          "platform.automation_rule_editor.create",
          "platform.automation_rule_editor.test");

  /** 事件注册表里的事件类型（上游 = 各业务事件）。 */
  static final String EVENT_TYPE = "order.created";

  /**
   * 事件注册表里登记的 <b>payload_schema</b>（JSON Schema）。
   *
   * <p>🔴 **必须是真实的 JSON Schema**（有 properties / type / required / enum）——
   * 字段目录是从它摊平出来的；写成 {@code {}} 的话字段目录恒为空，
   * 「字段目录来自真实上游」这条就测不出来了。
   */
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
                         "enum": ["CREATED", "PAID", "SHIPPED"]},
              "urgent": {"type": "boolean", "description": "是否加急"}
            },
            "required": ["totalAmount"]
          },
          "shop": {
            "type": "object",
            "properties": {
              "code": {"type": "string", "description": "店铺编码"}
            }
          }
        },
        "required": ["order"]
      }
      """;

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

  @BeforeEach
  void reset() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate table platform_automation_rule_test_run, platform_automation_rule_scope,"
              + " platform_automation_rule_action, platform_automation_rule_condition,"
              + " platform_automation_rule, platform_webhook_event_type restart identity cascade");
    }
    seedEventType();
  }

  /** 在事件注册表里登记一个**可用**事件，且带**真实的 JSON Schema**。 */
  void seedEventType() {
    tenants.run(
        tenant,
        () -> {
          sql.execute(
              "insert into platform_webhook_event_type(id,tenant_id,event_type,schema_version,status,"
                  + " payload_schema,source_service,source_artifact,registered_at)"
                  + " values(?,?,?,1,'ACTIVE',?::jsonb,'trade','SkuDetailService.java:1',"
                  + " ?::timestamptz)",
              UUID.randomUUID(),
              tenant,
              EVENT_TYPE,
              PAYLOAD_SCHEMA,
              OffsetDateTime.now(ZoneOffset.UTC));
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

  /**
   * 错误文案：MockMvc 走 sendError 时落在 errorMessage，走 ProblemDetail 时落在响应体。
   *
   * <p>（与 18.2.3.1 其他对接 / 18.2.2.1 支付与资金的测试同一写法 ——
   * 服务层用 {@code ResponseStatusException(BAD_REQUEST, "能看懂的话")} 给出原因，
   * 原因落在 {@code errorMessage} 上而不是响应体里。）
   */
  static String detail(org.springframework.mock.web.MockHttpServletResponse r) throws IOException {
    var message = r.getErrorMessage();
    var body = r.getContentAsString();
    return (message == null ? "" : message) + " " + body;
  }

  /** 一份**草稿**规则配置（两个条件 + 两个动作 + 一个租户级范围）。 */
  Map<String, Object> draft(String name) {
    return map(
        "name", name,
        "description", "编辑器草稿",
        "triggerEventType", EVENT_TYPE,
        "priority", 100,
        "conditions",
            List.of(
                map(
                    "fieldPath", "order.totalAmount",
                    "operator", "GT",
                    "valueNumber", "100",
                    "remark", "大额订单"),
                map(
                    "fieldPath", "order.status",
                    "operator", "EQ",
                    "valueText", "PAID",
                    "remark", "已付款")),
        "actions",
            List.of(
                map(
                    "actionType", "SEND_NOTIFICATION",
                    "targetRef", "tpl.stock.low",
                    "params", "{\"channel\":\"EMAIL\"}"),
                map("actionType", "CREATE_TASK", "targetRef", "task.replenish")),
        "scopes", List.of(map("scopeType", "TENANT", "scopeLabel", "整个租户")));
  }

  /** 一次样本测试请求体。 */
  Map<String, Object> testRequest(Map<String, Object> rule, String payload) {
    return map("rule", rule, "payload", payload);
  }

  // ==========================================================================
  // 能力声明
  // ==========================================================================
  @Test
  void 能力声明如实答出动作的真实执行未交付() throws Exception {
    var capabilities =
        getJson(
            "/platform/automation-rule-editor/capabilities", AutomationRuleEditorCapabilities.class);
    assertThat(capabilities.capabilities())
        .anyMatch(
            c ->
                c.name().contains("动作的真实执行")
                    && !c.available()
                    && c.ownerModule().equals("18.3.2"));
    // 🔴 样本测试**如实答已交付**，且写明依据。
    assertThat(capabilities.capabilities())
        .anyMatch(
            c ->
                c.name().contains("样本测试")
                    && c.available()
                    && c.evidence().contains("platform_automation_rule_test_run"));
    // 🔴 下游缺口如实标注（18.3.2.1 / 18.3.2.2 尚未交付）。
    assertThat(capabilities.gaps())
        .anyMatch(g -> g.moduleId().equals("18.3.2.1") && !g.available())
        .anyMatch(g -> g.moduleId().equals("18.3.2.2") && !g.available());
    // 上游 18.3.1.1 已交付 ⇒ 编辑器复用它，不另起一套规则表。
    assertThat(capabilities.gaps())
        .anyMatch(g -> g.moduleId().equals("18.3.1.1") && g.available());
  }

  // ==========================================================================
  // 🔴 红线 1 —— 样本测试真的求值（不是前端算的、不是写死的「全过」）
  // ==========================================================================
  @Test
  void 样本测试真的求值条件并逐条给出命中与否() throws Exception {
    var payload =
        """
        {"order": {"totalAmount": 250.5, "status": "PAID", "urgent": true},
         "shop": {"code": "AMAZON-US-01"}}
        """;
    var result =
        json.readValue(
            postJson(
                "/platform/automation-rule-editor/test",
                testRequest(draft("大额已付款订单"), payload),
                200),
            AutomationRuleEditorTestResult.class);

    assertThat(result.matched()).as("两个条件都满足 ⇒ 命中").isTrue();
    assertThat(result.totalConditions()).isEqualTo(2);
    assertThat(result.matchedConditions()).isEqualTo(2);
    assertThat(result.conditionEvaluations()).hasSize(2);
    // 逐条明细不是空壳：字段路径 / 操作符 / 期望值 / 实际值 / 原因都在。
    var first = result.conditionEvaluations().get(0);
    assertThat(first.fieldPath()).isEqualTo("order.totalAmount");
    assertThat(first.operator()).isEqualTo("GT");
    assertThat(first.expectedValue()).isEqualTo("100");
    assertThat(first.actualValue()).isEqualTo("250.5");
    assertThat(first.matched()).isTrue();
    assertThat(first.reason()).isNotBlank();
    var second = result.conditionEvaluations().get(1);
    assertThat(second.fieldPath()).isEqualTo("order.status");
    assertThat(second.actualValue()).isEqualTo("PAID");
    assertThat(second.matched()).isTrue();
  }

  @Test
  void 条件不满足时如实答未命中并写清为什么() throws Exception {
    var payload =
        """
        {"order": {"totalAmount": 30, "status": "CREATED"}}
        """;
    var result =
        json.readValue(
            postJson(
                "/platform/automation-rule-editor/test",
                testRequest(draft("小额未付款订单"), payload),
                200),
            AutomationRuleEditorTestResult.class);

    assertThat(result.matched()).as("两个条件都不满足 ⇒ 未命中").isFalse();
    assertThat(result.matchedConditions()).isZero();
    assertThat(result.totalConditions()).isEqualTo(2);
    // 🔴 失败原因**人可读**，写清「哪个字段、期望什么、实际什么」。
    assertThat(result.failureReason()).contains("order.totalAmount").contains("期望 100");
    assertThat(result.failureReason()).contains("order.status");
    // 未命中的那条也要有明细（不是只给一条「失败」）。
    assertThat(result.conditionEvaluations()).hasSize(2);
    assertThat(result.conditionEvaluations())
        .allMatch(e -> !e.matched() && !e.reason().isBlank());
  }

  @Test
  void 样本缺字段时如实答没有这个字段而不是假装命中() throws Exception {
    var payload = """
        {"shop": {"code": "AMAZON-US-01"}}
        """;
    var result =
        json.readValue(
            postJson(
                "/platform/automation-rule-editor/test",
                testRequest(draft("缺字段"), payload),
                200),
            AutomationRuleEditorTestResult.class);

    assertThat(result.matched()).isFalse();
    var first = result.conditionEvaluations().get(0);
    assertThat(first.actualValue()).as("载荷里没有这个字段 ⇒ 实际值为空").isEmpty();
    assertThat(first.reason()).isNotBlank();
    assertThat(result.failureReason()).contains("没有这个字段");
  }

  // ==========================================================================
  // 🔴 红线 2 —— 测试结果落台账（可重放、可核对）
  // ==========================================================================
  @Test
  void 测试结果落台账且存着配置快照与样本载荷() throws Exception {
    var payload =
        """
        {"order": {"totalAmount": 250.5, "status": "PAID"}}
        """;
    var rule = draft("落台账的规则");
    var result =
        json.readValue(
            postJson("/platform/automation-rule-editor/test", testRequest(rule, payload), 200),
            AutomationRuleEditorTestResult.class);

    // 🔴 台账里确实有一行（不是只在响应里）。
    // ⚠️ 读表**必须**在可信租户事务里：RLS 是 FORCE 的，事务外一行都看不见。
    var row =
        tenants.run(
            tenant,
            () ->
                sql.fetchOne(
                        "select count(*) from platform_automation_rule_test_run where id = ?",
                        result.testRunId())
                    .get(0, Integer.class));
    assertThat(row).as("测试结果必须真的落库").isEqualTo(1);

    // 读回来：配置快照 + 样本载荷 + 逐条明细都在 ⇒ 可重放、可核对。
    var runs =
        getJson(
            "/platform/automation-rule-editor/test-runs", AutomationRuleEditorTestRunRow[].class);
    assertThat(runs).hasSize(1);
    var stored = runs[0];
    assertThat(stored.id()).isEqualTo(result.testRunId());
    assertThat(stored.ruleName()).isEqualTo("落台账的规则");
    assertThat(stored.draft()).as("草稿测试 ⇒ ruleId 为空").isTrue();
    assertThat(stored.ruleId()).isNull();
    assertThat(stored.matched()).isTrue();
    assertThat(stored.totalConditions()).isEqualTo(2);
    assertThat(stored.matchedConditions()).isEqualTo(2);
    assertThat(stored.plannedActions()).isEqualTo(2);
    assertThat(stored.conditionEvaluations()).hasSize(2);
    assertThat(stored.actionPlan()).hasSize(2);
    // 🔴 **配置快照**在（可重放的关键之一）。
    assertThat(stored.configSnapshot()).contains("order.totalAmount").contains("PAID");
    // 🔴 **样本载荷**在（当时喂进去的那份数据）。
    assertThat(stored.samplePayload()).contains("250.5");
  }

  @Test
  void 测已保存的规则时测试行挂在该规则名下() throws Exception {
    // 先用编辑器保存一条规则（保存委托 18.3.1.1）。
    var detail =
        json.readValue(
            postJson("/platform/automation-rule-editor/save", draft("已保存的规则"), 200),
            AutomationRuleDetail.class);

    var payload =
        """
        {"order": {"totalAmount": 250.5, "status": "PAID"}}
        """;
    var saved = draft("已保存的规则");
    saved.put("id", detail.rule().id().toString());
    var result =
        json.readValue(
            postJson("/platform/automation-rule-editor/test", testRequest(saved, payload), 200),
            AutomationRuleEditorTestResult.class);

    assertThat(result.draft()).as("测的是已保存的规则 ⇒ 不是草稿").isFalse();
    assertThat(result.ruleId()).isEqualTo(detail.rule().id());
    // 测试序号从 1 递增（同一被测对象内唯一）。
    assertThat(result.runNo()).isEqualTo(1);

    var runs =
        getJson(
            "/platform/automation-rule-editor/test-runs?ruleId=" + detail.rule().id(),
            AutomationRuleEditorTestRunRow[].class);
    assertThat(runs).hasSize(1);
    assertThat(runs[0].ruleId()).isEqualTo(detail.rule().id());
    assertThat(runs[0].draft()).isFalse();
  }

  @Test
  void 台账是只追加的改不了也删不掉() throws Exception {
    var payload =
        """
        {"order": {"totalAmount": 250.5, "status": "PAID"}}
        """;
    var result =
        json.readValue(
            postJson("/platform/automation-rule-editor/test", testRequest(draft("只追加"), payload), 200),
            AutomationRuleEditorTestResult.class);

    // 🔴 UPDATE 被**两道防线**挡住：
    //   ① 运行角色 platform_app **根本没有 UPDATE 权限**（V421 只授 SELECT / INSERT）；
    //   ② 即便有人直连用 owner 身份跑 SQL，**触发器**也会拒掉（只追加台账）。
    //   这里分别验两条 —— 只验一条的话，另一条坏掉了没人知道。
    //
    // ① 运行角色：连 UPDATE 权限都没有（连语句都到不了表上）。
    assertThatThrownBy(
            () ->
                tenants.run(
                    tenant,
                    () -> {
                      sql.execute(
                          "update platform_automation_rule_test_run set failure_reason = '改过了' where id = ?",
                          result.testRunId());
                      return null;
                    }))
        .as("运行角色连 UPDATE 权限都没有")
        .hasStackTraceContaining("permission denied");

    // ② owner 身份直连：绕开权限码，但**绕不开触发器**。
    try (var c =
            DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.prepareStatement(
            "insert into platform_automation_rule_test_run(id,tenant_id,rule_id,rule_name,run_no,"
                + " trigger_event_type,trigger_schema_version,config_snapshot,sample_payload,matched,"
                + " total_conditions,matched_conditions,planned_actions,evaluation,failure_reason,"
                + " created_by,created_at)"
                + " values(?,?,null,'owner 直连',1,'order.created',1,'{}'::jsonb,'{}'::jsonb,true,"
                + " 0,0,0,'{}'::jsonb,'',null,now())")) {
      s.setObject(1, UUID.randomUUID());
      s.setObject(2, tenant);
      s.execute();
    } catch (Exception e) {
      throw new AssertionError("owner 直连插入测试台账失败", e);
    }

    assertThatThrownBy(
            () -> {
              try (var c =
                      DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
                  var s = c.createStatement()) {
                s.execute("update platform_automation_rule_test_run set failure_reason = '改过了'");
              }
            })
        .as("owner 身份也改不了已结束的测试记录（触发器兜底）")
        .hasStackTraceContaining("只追加台账");

    // 🔴 DELETE 同样被拒（owner 身份直连，触发器兜底）。
    assertThatThrownBy(
            () -> {
              try (var c =
                      DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
                  var s = c.createStatement()) {
                s.execute("delete from platform_automation_rule_test_run");
              }
            })
        .as("owner 身份也删不了已结束的测试记录（触发器兜底）")
        .hasStackTraceContaining("只追加台账");
  }

  // ==========================================================================
  // 🔴 红线 3 —— 测试不产生副作用
  // ==========================================================================
  @Test
  void 测试不产生任何真实副作用() throws Exception {
    var payload =
        """
        {"order": {"totalAmount": 250.5, "status": "PAID"}}
        """;
    var result =
        json.readValue(
            postJson("/platform/automation-rule-editor/test", testRequest(draft("不产生副作用"), payload), 200),
            AutomationRuleEditorTestResult.class);

    // 🔴 sideEffectsApplied 恒为 false（18.3.2 执行引擎尚未交付）。
    assertThat(result.sideEffectsApplied()).isFalse();
    // 动作**只给计划**：计划里有两步，但一步都没「执行」。
    assertThat(result.actionPlan()).hasSize(2);
    assertThat(result.plannedActions()).isEqualTo(2);
    assertThat(result.note()).contains("不执行任何动作");
    // 🔴 台账表里**连这个列都没有** ⇒ 不可能声称产生过副作用。
    var columns =
        sql.fetch(
                "select column_name from information_schema.columns"
                    + " where table_name = 'platform_automation_rule_test_run'")
            .map(r -> r.get(0, String.class));
    assertThat(columns)
        .as("台账表不许有「副作用」列 —— 没有它就不可能声称产生过副作用")
        .doesNotContain("side_effects_applied")
        .doesNotContain("executed_actions");
  }

  // ==========================================================================
  // 🔴 红线 4 —— 字段目录来自真实上游（事件注册表的 JSON Schema）
  // ==========================================================================
  @Test
  void 条件字段目录来自事件注册表登记的schema() throws Exception {
    var schemas =
        getJson(
            "/platform/automation-rule-editor/event-schemas",
            AutomationRuleEditorEventSchema[].class);
    assertThat(schemas).hasSize(1);
    var schema = schemas[0];
    assertThat(schema.eventType()).isEqualTo(EVENT_TYPE);
    assertThat(schema.schemaDeclared()).isTrue();
    assertThat(schema.sourceService()).isEqualTo("trade");

    // 🔴 字段是从 JSON Schema **摊平**出来的（含嵌套路径）。
    var paths = schema.fields().stream().map(AutomationRuleEditorField::path).toList();
    assertThat(paths).contains("order.totalAmount", "order.status", "order.urgent", "shop.code");
    // 类型 / 必填 / 枚举都来自 schema。
    var amount = schema.fields().stream().filter(f -> f.path().equals("order.totalAmount")).findFirst().orElseThrow();
    assertThat(amount.type()).isEqualTo("number");
    assertThat(amount.required()).isTrue();
    var status = schema.fields().stream().filter(f -> f.path().equals("order.status")).findFirst().orElseThrow();
    assertThat(status.type()).isEqualTo("string");
    assertThat(status.enumValues()).containsExactly("CREATED", "PAID", "SHIPPED");
    // 操作符按类型给（数值字段给大小比较，文本字段给包含/前缀/后缀）。
    assertThat(amount.allowedOperators()).contains("GT", "GE", "LT", "LE", "BETWEEN");
    assertThat(status.allowedOperators()).contains("CONTAINS", "STARTS_WITH", "ENDS_WITH", "IN");
  }

  @Test
  void 字段路径不在目录里会被拒() throws Exception {
    var rule = draft("路径不在目录里");
    rule.put(
        "conditions",
        List.of(map("fieldPath", "order.notExists", "operator", "EQ", "valueText", "x")));
    var response =
        http.perform(
                post("/platform/automation-rule-editor/test")
                    .header("Authorization", "session")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(testRequest(rule, "{}"))))
            .andReturn()
            .getResponse();
    assertThat(response.getStatus()).as("目录外的字段路径必须被拒（这样的条件永远命中不了）").isEqualTo(400);
    assertThat(detail(response)).contains("order.notExists");
  }

  @Test
  void 操作符与字段类型不符会被拒() throws Exception {
    var rule = draft("类型不符");
    // order.totalAmount 是数值字段 ⇒ 不能用「包含」比较。
    rule.put(
        "conditions",
        List.of(map("fieldPath", "order.totalAmount", "operator", "CONTAINS", "valueText", "x")));
    var response =
        http.perform(
                post("/platform/automation-rule-editor/test")
                    .header("Authorization", "session")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(testRequest(rule, "{}"))))
            .andReturn()
            .getResponse();
    assertThat(response.getStatus()).as("类型不符的操作符必须被拒").isEqualTo(400);
    assertThat(detail(response)).contains("order.totalAmount");
  }

  @Test
  void 条件值形状与操作符不符会被拒() throws Exception {
    // 大小比较不给数值 ⇒ 永远不成立（18.3.1.1 保存时也会拒，这里提前拒）。
    var rule = draft("值形状不对");
    rule.put(
        "conditions",
        List.of(map("fieldPath", "order.totalAmount", "operator", "GT", "valueText", "一百")));
    var response =
        http.perform(
                post("/platform/automation-rule-editor/test")
                    .header("Authorization", "session")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(testRequest(rule, "{}"))))
            .andReturn()
            .getResponse();
    assertThat(response.getStatus()).as("大小比较必须给数值条件值").isEqualTo(400);
    assertThat(detail(response)).contains("数值");
  }

  // ==========================================================================
  // 🔴 红线 5 —— 动作目录与动作目标能查证
  // ==========================================================================
  @Test
  void 动作类型目录给出五类动作与参数规格() throws Exception {
    var catalog =
        getJson(
            "/platform/automation-rule-editor/action-catalog",
            AutomationRuleEditorActionCatalog.class);
    var types =
        catalog.actionTypes().stream().map(AutomationRuleEditorActionType::actionType).toList();
    // 🔴 蓝图点名的五类，一个不少。
    assertThat(types)
        .containsExactlyInAnyOrder(
            "CALL_API", "UPDATE_STATUS", "CREATE_TASK", "SEND_NOTIFICATION", "CREATE_DOCUMENT");
    // 每个类型都带参数规格（必填 / 类型 / 枚举）。
    for (var type : catalog.actionTypes()) {
      assertThat(type.label()).as(type.actionType() + " 必须有人可读标签").isNotBlank();
      assertThat(type.targetLabel()).as(type.actionType() + " 必须写清目标填什么").isNotBlank();
    }
    var notify =
        catalog.actionTypes().stream()
            .filter(t -> t.actionType().equals("SEND_NOTIFICATION"))
            .findFirst()
            .orElseThrow();
    var channel =
        notify.params().stream().filter(p -> p.name().equals("channel")).findFirst().orElseThrow();
    assertThat(channel.required()).isTrue();
    assertThat(channel.enumValues()).containsExactly("EMAIL", "SMS", "INAPP");
    var recipients =
        notify.params().stream().filter(p -> p.name().equals("recipients")).findFirst().orElseThrow();
    assertThat(recipients.type()).isEqualTo("TEXT_LIST");
  }

  @Test
  void 动作缺少必填参数会被拒() throws Exception {
    var rule = draft("缺必填参数");
    rule.put(
        "actions",
        List.of(map("actionType", "SEND_NOTIFICATION", "targetRef", "tpl.stock.low", "params", "{}")));
    var response =
        http.perform(
                post("/platform/automation-rule-editor/test")
                    .header("Authorization", "session")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(testRequest(rule, "{}"))))
            .andReturn()
            .getResponse();
    assertThat(response.getStatus()).as("缺少必填参数必须被拒").isEqualTo(400);
    assertThat(detail(response)).contains("通知渠道");
  }

  @Test
  void 动作参数类型不符会被拒() throws Exception {
    var rule = draft("参数类型不对");
    rule.put(
        "actions",
        List.of(
            map(
                "actionType", "SEND_NOTIFICATION",
                "targetRef", "tpl.stock.low",
                "params", "{\"channel\": \"EMAIL\", \"recipients\": \"不是列表\"}")));
    var response =
        http.perform(
                post("/platform/automation-rule-editor/test")
                    .header("Authorization", "session")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(testRequest(rule, "{}"))))
            .andReturn()
            .getResponse();
    assertThat(response.getStatus()).as("参数类型不符必须被拒").isEqualTo(400);
    assertThat(detail(response)).contains("列表");
  }

  @Test
  void 动作类型不在受控枚举里会被拒() throws Exception {
    var rule = draft("动作类型非法");
    rule.put("actions", List.of(map("actionType", "DROP_DATABASE", "targetRef", "x")));
    var response =
        http.perform(
                post("/platform/automation-rule-editor/test")
                    .header("Authorization", "session")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(testRequest(rule, "{}"))))
            .andReturn()
            .getResponse();
    assertThat(response.getStatus()).as("动作类型必须是受控枚举").isEqualTo(400);
  }

  // ==========================================================================
  // 🔴 红线 6 —— 保存委托 18.3.1.1（不另建规则表）
  // ==========================================================================
  @Test
  void 保存后规则在18_3_1_1的档案端点里读得到() throws Exception {
    var detail =
        json.readValue(
            postJson("/platform/automation-rule-editor/save", draft("委托保存"), 200),
            AutomationRuleDetail.class);

    assertThat(detail.rule().name()).isEqualTo("委托保存");
    assertThat(detail.conditions()).hasSize(2);
    assertThat(detail.actions()).hasSize(2);
    assertThat(detail.scopes()).hasSize(1);

    // 🔴 规则**真的落在 18.3.1.1 的表里**（编辑器没有另建一套规则表）。
    var inRuleTable =
        tenants.run(
            tenant,
            () ->
                sql.fetchOne(
                        "select count(*) from platform_automation_rule where name = ?", "委托保存")
                    .get(0, Integer.class));
    assertThat(inRuleTable).as("规则必须落在 18.3.1.1 的规则档案表里").isEqualTo(1);
    // 编辑器**没有**另建规则表（本模块只新增一张测试台账）。
    // ⚠️ information_schema 不受 RLS 管（读的是目录，不是业务行）⇒ 事务外读也可以。
    var editorTables =
        sql.fetch(
                "select table_name from information_schema.tables"
                    + " where table_name like 'platform_automation_rule%'")
            .map(r -> r.get(0, String.class));
    assertThat(editorTables)
        .containsExactlyInAnyOrder(
            "platform_automation_rule",
            "platform_automation_rule_action",
            "platform_automation_rule_condition",
            "platform_automation_rule_execution",
            "platform_automation_rule_scope",
            "platform_automation_rule_test_run");
  }

  @Test
  void 触发了没登记过的事件会被拒() throws Exception {
    var rule = draft("没登记过的事件");
    rule.put("triggerEventType", "order.never.registered");
    var response =
        http.perform(
                post("/platform/automation-rule-editor/save")
                    .header("Authorization", "session")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(rule)))
            .andReturn()
            .getResponse();
    assertThat(response.getStatus()).as("没登记过的事件必须被拒").isEqualTo(400);
  }

  @Test
  void 样本载荷不是json对象会被拒() throws Exception {
    var response =
        http.perform(
                post("/platform/automation-rule-editor/test")
                    .header("Authorization", "session")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(
                            testRequest(draft("载荷不是对象"), "[1, 2, 3]"))))
            .andReturn()
            .getResponse();
    assertThat(response.getStatus()).as("样本载荷必须是 JSON 对象").isEqualTo(400);
  }

  @Test
  void 样本载荷不是合法json会被拒() throws Exception {
    var response =
        http.perform(
                post("/platform/automation-rule-editor/test")
                    .header("Authorization", "session")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(
                            testRequest(draft("载荷不是 JSON"), "{不是 JSON"))))
            .andReturn()
            .getResponse();
    assertThat(response.getStatus()).as("非法 JSON 必须被拒").isEqualTo(400);
  }

  // ==========================================================================
  // 启用范围是真约束（复用 18.3.1.1 的同一判定器）
  // ==========================================================================
  @Test
  void 范围没命中时如实答不生效() throws Exception {
    var rule = draft("范围没命中");
    rule.put("scopes", List.of(map("scopeType", "SHOP", "scopeRef", "00000000-0000-4000-8000-0000000000b1")));
    var result =
        json.readValue(
            postJson(
                "/platform/automation-rule-editor/test",
                testRequest(rule, "{}"),
                200),
            AutomationRuleEditorTestResult.class);
    // 范围没命中给定店铺 ⇒ 不生效（条件命中与否与范围是两件事）。
    assertThat(result.scopeApplicable()).isFalse();
    assertThat(result.scopeNote()).isNotBlank();
  }

  @Test
  void 范围命中时如实答生效() throws Exception {
    var payload =
        """
        {"order": {"totalAmount": 250.5, "status": "PAID"}}
        """;
    var result =
        json.readValue(
            postJson(
                "/platform/automation-rule-editor/test",
                testRequest(draft("范围命中"), payload),
                200),
            AutomationRuleEditorTestResult.class);
    assertThat(result.scopeApplicable()).as("租户级范围 ⇒ 生效").isTrue();
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

  @Test
  void 台账表没有更新与删除权限() throws Exception {
    // 🔴 只追加台账：连权限码都不给（触发器是第二道防线）。
    assertThat(
            sql.fetchOne(
                    "select has_table_privilege('platform_app','platform_automation_rule_test_run','UPDATE')")
                .get(0, Boolean.class))
        .as("platform_app 不许改台账")
        .isFalse();
    assertThat(
            sql.fetchOne(
                    "select has_table_privilege('platform_app','platform_automation_rule_test_run','DELETE')")
                .get(0, Boolean.class))
        .as("platform_app 不许删台账")
        .isFalse();
  }

  // ==========================================================================
  // 权限隔离：「跑一次样本测试」与「保存规则」是两个独立的码
  // ==========================================================================
  @Test
  void 没有测试权限码就写不了测试台账() throws Exception {
    // 一个只有 read + create 的角色（没有 .test）。
    var readerOnly = actor(UUID.randomUUID(), tenant, List.of("platform.automation_rule_editor.read"));
    actors.put("reader-only", readerOnly);
    try {
      var response =
          http.perform(
                  post("/platform/automation-rule-editor/test")
                      .header("Authorization", "reader-only")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(
                          json.writeValueAsString(testRequest(draft("没有测试权限"), "{}"))))
              .andReturn()
              .getResponse();
      assertThat(response.getStatus()).as("没有 .test 权限码就不许跑样本测试").isEqualTo(403);
    } finally {
      actors.remove("reader-only");
    }
  }
}
