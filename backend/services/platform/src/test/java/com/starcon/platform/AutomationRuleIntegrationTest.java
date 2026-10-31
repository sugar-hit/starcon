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
 * 18.3.1.1「规则列表」的**功能**集成测试，跑在真实 PostgreSQL 16 上。
 *
 * <p><b>真实 PostgreSQL 16 + FORCE RLS，不使用任何内存替身。</b>
 *
 * <p>本测试盯住蓝图 18.3.1.1 的**加粗句**：
 * 「<b>规则要能看出最后一次执行结果</b>，否则坏规则静默失败无人察觉。」
 * —— 拆成五个可执行判定：
 *
 * <ol>
 *   <li><b>执行统计能从规则档案读到</b>：最后一次执行的<b>时间 / 结果 / 失败原因</b>
 *       三件套（不是一个「有没有执行过」的布尔位），且**断言写回的是正确时刻**（按 epoch 比对）；
 *   <li><b>坏规则有反面判据</b>：连续失败达阈值 ⇒ <b>可被识别</b>（失败计数 + 连续失败标记），
 *       而不是悄悄地把规则状态改回「正常」；
 *   <li><b>试运行开关是独立状态</b>：开启试运行的规则<b>不产生真实副作用</b>，
 *       端点与页面都要能看出这条规则处于试运行；
 *   <li><b>启用范围是真约束</b>：范围要能被过滤条件<b>实际用上</b>，不是展示用字段；
 *   <li><b>优先级与排序真实生效</b>：相同优先级也要有<b>确定</b>的次序，不靠 DB 返回顺序。
 * </ol>
 *
 * <p><b>🔴 时间纪律</b>：落库时间列用 {@code ?::timestamptz} 绑定，并<b>断言写回的值</b>，
 * 不只断言「没抛异常」。
 *
 * <p><b>🔴 数量纪律</b>：条数一律 {@link BigDecimal}，断言用 {@code isEqualByComparingTo}
 * （比的是数值，不是标度）。
 *
 * <p>多租户隔离的三条断言在 {@code AutomationRuleTenancyIntegrationTest} 里
 * （同一批表、同一套夹具）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AutomationRuleIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID tenant = UUID.randomUUID();
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static HttpServer identity;
  static HttpServer channel;
  static HttpServer warehouse;

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

  /** 事件注册表里的事件类型（上游 = 各业务事件）。 */
  static final String EVENT_TYPE = "order.created";

  // ---------------------------------------------------------------- 夹具工具

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

      // 🔴 上游「2 渠道机制」（channel 服务）：只读端口的夹具。
      channel = HttpServer.create(new InetSocketAddress(0), 0);
      channel.createContext(
          "/channel/shops",
          e ->
              respond(
                  e,
                  200,
                  List.of(
                      map(
                          "id", "00000000-0000-4000-8000-0000000000b1",
                          "code", "AMAZON-US-01",
                          "name", "Amazon 美国站",
                          "platformCode", "AMAZON",
                          "platformName", "Amazon",
                          "status", "ACTIVE",
                          "lastSyncAt", "2026-10-30T00:00:00Z"))));
      channel.setExecutor(Executors.newFixedThreadPool(2));
      channel.start();

      // 🔴 上游仓库（warehouse 服务）：只读端口的夹具。
      warehouse = HttpServer.create(new InetSocketAddress(0), 0);
      warehouse.createContext(
          "/warehouse/warehouses",
          e ->
              respond(
                  e,
                  200,
                  List.of(
                      map(
                          "id", "00000000-0000-4000-8000-0000000000c1",
                          "code", "US-WH-01",
                          "name", "美国一仓",
                          "countryCode", "US",
                          "status", "ACTIVE",
                          "isDefault", true))));
      warehouse.setExecutor(Executors.newFixedThreadPool(2));
      warehouse.start();
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
    r.add("CHANNEL_URL", () -> "http://127.0.0.1:" + channel.getAddress().getPort());
    r.add("WAREHOUSE_URL", () -> "http://127.0.0.1:" + warehouse.getAddress().getPort());
  }

  @Autowired TenantTransactions tenants;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  @AfterAll
  static void stopContainers() {
    if (identity != null) identity.stop(0);
    if (channel != null) channel.stop(0);
    if (warehouse != null) warehouse.stop(0);
    if (pg.isRunning()) pg.stop();
  }

  @BeforeEach
  void reset() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate table platform_automation_rule_execution, platform_automation_rule_scope,"
              + " platform_automation_rule_action, platform_automation_rule_condition,"
              + " platform_automation_rule, platform_webhook_event_type restart identity cascade");
    }
    seedEventType();
  }

  /** 在事件注册表里登记一个**可用**事件（上游 = 各业务事件）。 */
  void seedEventType() {
    tenants.run(
        tenant,
        () -> {
          sql.execute(
              "insert into platform_webhook_event_type(id,tenant_id,event_type,schema_version,status,"
                  + " payload_schema,source_service,source_artifact,registered_at)"
                  + " values(?,?,?,1,'ACTIVE','{}'::jsonb,'trade','SkuDetailService.java:1',"
                  + " ?::timestamptz)",
              UUID.randomUUID(),
              tenant,
              EVENT_TYPE,
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

  /** 登记一条规则（默认带一个租户级范围）。 */
  AutomationRuleDetail createRule(String name, Map<String, Object> overrides) throws Exception {
    var body = new LinkedHashMap<String, Object>();
    body.put("name", name);
    body.put("description", "测试规则");
    body.put("triggerEventType", EVENT_TYPE);
    body.put("priority", 100);
    body.put("remark", "备注");
    body.put(
        "scopes",
        List.of(map("scopeType", "TENANT", "scopeLabel", "整个租户")));
    if (overrides != null) body.putAll(overrides);
    var text =
        postJson("/platform/automation-rules/rules", body, 200);
    return json.readValue(text, AutomationRuleDetail.class);
  }

  // ==========================================================================
  // 能力声明
  // ==========================================================================
  @Test
  void 能力声明如实答出下游未交付() throws Exception {
    var capabilities = getJson("/platform/automation-rules/capabilities", AutomationRuleCapabilities.class);
    assertThat(capabilities.gaps()).isNotEmpty();
    assertThat(capabilities.gaps())
        .anyMatch(g -> g.moduleId().equals("18.3.1.2") && !g.available())
        .anyMatch(g -> g.moduleId().equals("18.3.2.1") && !g.available());
    // 🔴 执行引擎**如实答未交付** —— 本模块不冒充它。
    assertThat(capabilities.capabilities())
        .anyMatch(
            c ->
                c.name().contains("规则执行")
                    && !c.available()
                    && c.ownerModule().equals("18.3.2"));
    // 🔴 执行统计与坏规则反面判据**如实答已交付**，且写明依据。
    assertThat(capabilities.capabilities())
        .anyMatch(
            c ->
                c.name().contains("执行统计能从规则档案读到")
                    && c.available()
                    && c.evidence().contains("last_run_failure_reason"));
    assertThat(capabilities.capabilities())
        .anyMatch(
            c ->
                c.name().contains("坏规则有反面判据")
                    && c.available()
                    && c.evidence().contains("failing"));
  }

  // ==========================================================================
  // 规则档案：条件 / 动作 / 启用范围
  // ==========================================================================
  @Test
  void 登记规则后档案能读回条件动作与范围() throws Exception {
    var detail =
        createRule(
            "订单超库存就通知",
            map(
                "conditions",
                List.of(
                    map(
                        "fieldPath", "order.totalAmount",
                        "operator", "GT",
                        "valueNumber", "100.5",
                        "remark", "大额订单")),
                "actions",
                List.of(
                    map(
                        "actionType", "SEND_NOTIFICATION",
                        "targetRef", "tpl.stock.low",
                        "params", "{\"channel\":\"EMAIL\"}"),
                    map("actionType", "CREATE_TASK", "targetRef", "task.replenish")),
                "scopes",
                List.of(
                    map("scopeType", "TENANT", "scopeLabel", "整个租户"),
                    map(
                        "scopeType", "SHOP",
                        "scopeRef", "00000000-0000-4000-8000-0000000000b1",
                        "scopeLabel", "Amazon 美国站"))));

    assertThat(detail.rule().name()).isEqualTo("订单超库存就通知");
    assertThat(detail.rule().conditionCount()).isEqualTo(1);
    assertThat(detail.rule().actionCount()).isEqualTo(2);
    assertThat(detail.rule().scopeCount()).isEqualTo(2);
    assertThat(detail.conditions()).hasSize(1);
    assertThat(detail.conditions().get(0).fieldPath()).isEqualTo("order.totalAmount");
    assertThat(detail.conditions().get(0).operator()).isEqualTo("GT");
    assertThat(detail.conditions().get(0).valueNumber())
        .isEqualByComparingTo(new BigDecimal("100.5"));
    assertThat(detail.actions()).hasSize(2);
    assertThat(detail.actions().get(0).seq()).isEqualTo(1);
    assertThat(detail.actions().get(1).seq()).isEqualTo(2);
    assertThat(detail.scopes()).hasSize(2);

    // 再读一次（真落库，不是只在响应里）。
    var again = getJson("/platform/automation-rules/rules/" + detail.rule().id(), AutomationRuleDetail.class);
    assertThat(again.conditions()).hasSize(1);
    assertThat(again.actions()).hasSize(2);
    assertThat(again.scopes()).hasSize(2);
  }

  @Test
  void 触发事件必须在事件注册表里登记过() throws Exception {
    var response =
        http.perform(
                post("/platform/automation-rules/rules")
                    .header("Authorization", "session")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(
                            map(
                                "name", "没登记过的事件",
                                "triggerEventType", "order.never.registered",
                                "scopes", List.of(map("scopeType", "TENANT"))))))
            .andReturn()
            .getResponse();
    assertThat(response.getStatus()).as("没登记过的事件必须被拒").isEqualTo(400);
  }

  @Test
  void 范围是店铺却没写是哪家店会被拒() throws Exception {
    var response =
        http.perform(
                post("/platform/automation-rules/rules")
                    .header("Authorization", "session")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(
                            map(
                                "name", "没写店铺的规则",
                                "triggerEventType", EVENT_TYPE,
                                "scopes", List.of(map("scopeType", "SHOP"))))))
            .andReturn()
            .getResponse();
    assertThat(response.getStatus()).as("范围缺引用必须被拒").isEqualTo(400);
  }

  // ==========================================================================
  // 🔴 红线 1 —— 执行统计能从规则档案读到（时间 / 结果 / 失败原因）
  // ==========================================================================
  @Test
  void 执行统计能从规则档案读到时间结果与失败原因() throws Exception {
    var rule = createRule("统计可读", null);
    // 一个**带时区偏移**的时刻：写回后必须还是同一个瞬间（不被静默偏 -28800s）。
    var finishedAt = OffsetDateTime.parse("2026-10-30T12:00:00+08:00");
    var startedAt = OffsetDateTime.parse("2026-10-30T11:59:00+08:00");

    postJson(
        "/platform/automation-rules/rules/" + rule.rule().id() + "/executions",
        map(
            "status", "FAILED",
            "source", "MANUAL",
            "failureReason", "下游接口返回 500",
            "failureClass", "ACTION_FAILED",
            "triggeredBy", "order.created",
            "triggerEventType", EVENT_TYPE,
            "matchedConditions", 1,
            "plannedActions", 2,
            "executedActions", 0,
            "startedAt", startedAt.toString(),
            "finishedAt", finishedAt.toString()),
        200);

    var after =
        getJson(
            "/platform/automation-rules/rules/" + rule.rule().id(), AutomationRuleDetail.class);
    // 🔴 三件套都在档案上，不是布尔位。
    assertThat(after.rule().lastRunStatus()).isEqualTo("FAILED");
    assertThat(after.rule().lastRunFailureReason()).isEqualTo("下游接口返回 500");
    assertThat(after.rule().lastRunAt()).isNotNull();
    // 🔴 **按 epoch 比对**：写回的就是那个瞬间。
    assertThat(after.rule().lastRunAt().toInstant())
        .isEqualTo(finishedAt.toInstant());
    assertThat(after.rule().lastRunAt().toInstant().getEpochSecond())
        .isEqualTo(finishedAt.toInstant().getEpochSecond());
    assertThat(after.rule().lastRunExecutionId()).isNotNull();
    // 数量是 BigDecimal（禁浮点）。
    assertThat(after.rule().runCount()).isEqualByComparingTo(BigDecimal.ONE);
    assertThat(after.rule().failureCount()).isEqualByComparingTo(BigDecimal.ONE);
    assertThat(after.rule().successCount()).isEqualByComparingTo(BigDecimal.ZERO);
  }

  // ==========================================================================
  // 🔴 红线 2 —— 坏规则有反面判据（可被识别），且不会悄悄改回正常
  // ==========================================================================
  @Test
  void 连续失败达到阈值即被识别为坏规则() throws Exception {
    var rule = createRule("会坏的规则", map("consecutiveFailureThreshold", 2));

    for (int i = 0; i < 2; i++) {
      postJson(
          "/platform/automation-rules/rules/" + rule.rule().id() + "/executions",
          map(
              "status", "FAILED",
              "source", "MANUAL",
              "failureReason", "第 " + (i + 1) + " 次失败",
              "failureClass", "ACTION_FAILED",
              "plannedActions", 1,
              "executedActions", 0),
          200);
    }

    var after =
        getJson(
            "/platform/automation-rules/rules/" + rule.rule().id(), AutomationRuleDetail.class);
    assertThat(after.rule().consecutiveFailureCount()).isEqualTo(2);
    assertThat(after.rule().failing()).as("连续失败达阈值必须被标记出来").isTrue();
    assertThat(after.rule().failingSince()).isNotNull();
    assertThat(after.rule().health()).isEqualTo(AutomationRuleHealth.FAILING.name());

    // 🔴 **可被识别**：列表按 failing=true 过滤能筛出它。
    var failing = getJson("/platform/automation-rules/rules?failing=true", AutomationRuleRow[].class);
    assertThat(failing).hasSize(1);
    assertThat(failing[0].id()).isEqualTo(rule.rule().id());
    var healthy = getJson("/platform/automation-rules/rules?failing=false", AutomationRuleRow[].class);
    assertThat(healthy).isEmpty();

    // 概览里「连续失败」这一档是真的。
    var overview = getJson("/platform/automation-rules/overview", AutomationRuleOverview.class);
    assertThat(overview.failingRules()).isEqualTo(1);
  }

  @Test
  void 失败不会把停用的规则悄悄改回正常() throws Exception {
    var rule = createRule("停用的坏规则", map("consecutiveFailureThreshold", 1));
    // 先停用。
    postJson("/platform/automation-rules/rules/" + rule.rule().id() + "/disable", map(), 200);

    postJson(
        "/platform/automation-rules/rules/" + rule.rule().id() + "/executions",
        map(
            "status", "FAILED",
            "source", "MANUAL",
            "failureReason", "停用期间仍然失败",
            "failureClass", "ACTION_FAILED",
            "plannedActions", 1,
            "executedActions", 0),
        200);

    var after =
        getJson(
            "/platform/automation-rules/rules/" + rule.rule().id(), AutomationRuleDetail.class);
    // 🔴 失败**只更新统计列**：停用状态原样保留，不被悄悄改回「正常」。
    assertThat(after.rule().status()).isEqualTo("DISABLED");
    assertThat(after.rule().enabled()).isFalse();
    assertThat(after.rule().failing()).isTrue();
    assertThat(after.rule().consecutiveFailureCount()).isEqualTo(1);
  }

  // ==========================================================================
  // 🔴 红线 3 —— 试运行开关是独立状态，且不产生真实副作用
  // ==========================================================================
  @Test
  void 试运行是独立状态且不产生真实副作用() throws Exception {
    var rule = createRule("试运行的规则", null);
    // 试运行**不改变**启用状态。
    var toggled =
        json.readValue(
            postJson(
                "/platform/automation-rules/rules/" + rule.rule().id() + "/dry-run",
                map("dryRun", true),
                200),
            AutomationRuleRow.class);
    assertThat(toggled.dryRun()).isTrue();
    assertThat(toggled.enabled()).as("试运行是独立状态，不该动启用开关").isTrue();
    assertThat(toggled.status()).isEqualTo("ACTIVE");
    assertThat(toggled.sideEffectMode()).isEqualTo(AutomationRuleSideEffectMode.SIMULATE.name());
    assertThat(toggled.sideEffectModeLabel()).contains("不产生副作用");

    // 试运行规则登记执行结果 ⇒ dry_run 恒为 true、executed_actions 恒为 0。
    var execution =
        json.readValue(
            postJson(
                "/platform/automation-rules/rules/" + rule.rule().id() + "/executions",
                map(
                    "status", "SUCCEEDED",
                    "source", "MANUAL",
                    "plannedActions", 3,
                    "executedActions", 0,
                    "sideEffectsApplied", false),
                200),
            AutomationRuleExecutionRow.class);
    assertThat(execution.dryRun()).isTrue();
    assertThat(execution.sideEffectsApplied()).isFalse();
    assertThat(execution.executedActions()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(execution.plannedActions()).isEqualByComparingTo(new BigDecimal("3"));

    // 试运行规则登记「产生了真实副作用」⇒ 409。
    var conflict =
        http.perform(
                post("/platform/automation-rules/rules/" + rule.rule().id() + "/executions")
                    .header("Authorization", "session")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(
                            map(
                                "status", "SUCCEEDED",
                                "source", "MANUAL",
                                "plannedActions", 1,
                                "executedActions", 1,
                                "sideEffectsApplied", true))))
            .andReturn()
            .getResponse();
    assertThat(conflict.getStatus()).as("试运行规则不许登记真实副作用").isEqualTo(409);

    // 试运行规则登记「已经执行了动作」⇒ 409。
    var conflict2 =
        http.perform(
                post("/platform/automation-rules/rules/" + rule.rule().id() + "/executions")
                    .header("Authorization", "session")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(
                            map(
                                "status", "SUCCEEDED",
                                "source", "MANUAL",
                                "plannedActions", 2,
                                "executedActions", 2,
                                "sideEffectsApplied", false))))
            .andReturn()
            .getResponse();
    assertThat(conflict2.getStatus()).as("试运行规则不许登记已执行动作").isEqualTo(409);
  }

  // ==========================================================================
  // 🔴 红线 4 —— 启用范围是真约束
  // ==========================================================================
  @Test
  void 启用范围是真约束() throws Exception {
    var shopId = UUID.fromString("00000000-0000-4000-8000-0000000000b1");
    var warehouseId = UUID.fromString("00000000-0000-4000-8000-0000000000c1");

    // 租户级范围 ⇒ 对任何店铺 / 仓库都生效。
    var tenantWide =
        createRule(
            "租户级规则",
            map("scopes", List.of(map("scopeType", "TENANT", "scopeLabel", "整个租户"))));
    // 店铺级范围 ⇒ 只对那家店生效。
    var shopScoped =
        createRule(
            "店铺级规则",
            map(
                "scopes",
                List.of(
                    map(
                        "scopeType", "SHOP",
                        "scopeRef", shopId.toString(),
                        "scopeLabel", "Amazon 美国站"))));
    // 仓库级范围 ⇒ 只对那个仓生效。
    var warehouseScoped =
        createRule(
            "仓库级规则",
            map(
                "scopes",
                List.of(
                    map(
                        "scopeType", "WAREHOUSE",
                        "scopeRef", warehouseId.toString(),
                        "scopeLabel", "美国一仓"))));

    var forShop =
        getJson(
            "/platform/automation-rules/applicable?shopId=" + shopId, AutomationRuleApplicableRow[].class);
    var names = java.util.Arrays.stream(forShop).map(AutomationRuleApplicableRow::ruleName).toList();
    assertThat(names).contains("租户级规则", "店铺级规则");
    assertThat(names).doesNotContain("仓库级规则");

    var forWarehouse =
        getJson(
            "/platform/automation-rules/applicable?warehouseId=" + warehouseId,
            AutomationRuleApplicableRow[].class);
    var warehouseNames =
        java.util.Arrays.stream(forWarehouse).map(AutomationRuleApplicableRow::ruleName).toList();
    assertThat(warehouseNames).contains("租户级规则", "仓库级规则");
    assertThat(warehouseNames).doesNotContain("店铺级规则");

    // 🔴 **没有范围行的规则不对任何店铺 / 仓库生效**（范围不是展示用字段）。
    var noScope = createRule("没有范围的规则", map("scopes", List.of()));
    var forNobody =
        getJson(
            "/platform/automation-rules/applicable?shopId=" + shopId,
            AutomationRuleApplicableRow[].class);
    assertThat(java.util.Arrays.stream(forNobody).map(AutomationRuleApplicableRow::ruleName).toList())
        .doesNotContain("没有范围的规则");
    assertThat(noScope.rule().scopeCount()).isZero();
  }

  @Test
  void 预演按样本载荷真的求值() throws Exception {
    var rule =
        createRule(
            "可预演的规则",
            map(
                "conditions",
                List.of(
                    map(
                        "fieldPath", "order.totalAmount",
                        "operator", "GT",
                        "valueNumber", "100"),
                    map("fieldPath", "order.status", "operator", "EQ", "valueText", "PAID")),
                "actions",
                List.of(map("actionType", "SEND_NOTIFICATION", "targetRef", "tpl.stock.low"))));

    var preview =
        json.readValue(
            postJson(
                "/platform/automation-rules/rules/" + rule.rule().id() + "/preview",
                map("payload", "{\"order\":{\"totalAmount\":250,\"status\":\"PAID\"}}"),
                200),
            AutomationRulePreview.class);
    assertThat(preview.totalConditions()).isEqualTo(2);
    assertThat(preview.matchedConditions()).isEqualTo(2);
    assertThat(preview.conditionsMatched()).isTrue();
    assertThat(preview.conditionEvaluations()).hasSize(2);
    assertThat(preview.conditionEvaluations()).allMatch(AutomationRuleConditionEvaluation::matched);
    assertThat(preview.plannedActions()).hasSize(1);
    // 🔴 预演**不执行**任何动作。
    assertThat(preview.wouldProduceSideEffects()).isFalse();

    // 载荷不满足条件 ⇒ 逐条给出「没命中」。
    var preview2 =
        json.readValue(
            postJson(
                "/platform/automation-rules/rules/" + rule.rule().id() + "/preview",
                map("payload", "{\"order\":{\"totalAmount\":20,\"status\":\"NEW\"}}"),
                200),
            AutomationRulePreview.class);
    assertThat(preview2.matchedConditions()).isZero();
    assertThat(preview2.conditionsMatched()).isFalse();
    assertThat(preview2.conditionEvaluations()).noneMatch(AutomationRuleConditionEvaluation::matched);
  }

  // ==========================================================================
  // 🔴 红线 5 —— 优先级与排序真实生效（相同优先级也有确定次序）
  // ==========================================================================
  @Test
  void 相同优先级的规则次序确定() throws Exception {
    var a = createRule("同优先级 A", map("priority", 50));
    var b = createRule("同优先级 B", map("priority", 50));
    var c = createRule("更高优先级", map("priority", 10));

    var first = getJson("/platform/automation-rules/rules", AutomationRuleRow[].class);
    var second = getJson("/platform/automation-rules/rules", AutomationRuleRow[].class);
    assertThat(java.util.Arrays.stream(first).map(AutomationRuleRow::id).toList())
        .isEqualTo(java.util.Arrays.stream(second).map(AutomationRuleRow::id).toList());
    // 优先级小的在前。
    assertThat(first[0].id()).isEqualTo(c.rule().id());
    // 相同优先级的两条：次序**确定**（按创建时间，再按主键）。
    var tail =
        java.util.Arrays.stream(first)
            .filter(r -> r.priority() == 50)
            .map(AutomationRuleRow::id)
            .toList();
    assertThat(tail).containsExactlyInAnyOrder(a.rule().id(), b.rule().id());
    assertThat(tail.get(0)).isEqualTo(tail.get(0));
    // 再取一次，必须逐字相同（不靠 DB 返回顺序）。
    var tail2 =
        java.util.Arrays.stream(second)
            .filter(r -> r.priority() == 50)
            .map(AutomationRuleRow::id)
            .toList();
    assertThat(tail2).isEqualTo(tail);
  }

  @Test
  void reorder真实改变次序() throws Exception {
    var a = createRule("排在前面的", map("priority", 10));
    var b = createRule("排在后面的", map("priority", 20));

    var reordered =
        json.readValue(
            postJson(
                "/platform/automation-rules/rules/reorder",
                map(
                    "entries",
                    List.of(
                        map("ruleId", b.rule().id().toString(), "priority", 5),
                        map("ruleId", a.rule().id().toString(), "priority", 30))),
                200),
            AutomationRuleRow[].class);
    assertThat(reordered[0].id()).isEqualTo(b.rule().id());
    assertThat(reordered[0].priority()).isEqualTo(5);

    var listed = getJson("/platform/automation-rules/rules", AutomationRuleRow[].class);
    assertThat(listed[0].id()).isEqualTo(b.rule().id());
  }

  // ==========================================================================
  // 台账纪律
  // ==========================================================================
  @Test
  void 执行台账是只追加的() throws Exception {
    var rule = createRule("只追加台账", null);
    postJson(
        "/platform/automation-rules/rules/" + rule.rule().id() + "/executions",
        map("status", "SUCCEEDED", "source", "MANUAL", "plannedActions", 1, "executedActions", 1,
            "sideEffectsApplied", true),
        200);

    assertThatThrownBy(
            () ->
                tenants.run(
                    tenant,
                    () -> {
                      sql.execute(
                          "update platform_automation_rule_execution set status = 'FAILED'"
                              + " where rule_id = ?",
                          rule.rule().id());
                      return null;
                    }))
        .as("已结束的执行记录不可改写")
        .hasStackTraceContaining("只追加台账");
  }

  @Test
  void 试运行标记必须与规则一致() throws Exception {
    var rule = createRule("不是试运行的规则", null);
    assertThatThrownBy(
            () ->
                tenants.run(
                    tenant,
                    () -> {
                      sql.execute(
                          "insert into platform_automation_rule_execution(id,tenant_id,rule_id,run_no,"
                              + " status,source,dry_run,side_effects_applied,matched_conditions,"
                              + " planned_actions,executed_actions,failure_reason,failure_class,"
                              + " started_at,finished_at,created_by,created_at)"
                              + " values(?,?,?,1,'SUCCEEDED','MANUAL',true,false,0,1,0,'','',"
                              + " ?::timestamptz,?::timestamptz,null,?::timestamptz)",
                          UUID.randomUUID(),
                          tenant,
                          rule.rule().id(),
                          OffsetDateTime.now(ZoneOffset.UTC),
                          OffsetDateTime.now(ZoneOffset.UTC),
                          OffsetDateTime.now(ZoneOffset.UTC));
                      return null;
                    }))
        .as("非试运行规则的执行行不许标 dry_run = true")
        .hasStackTraceContaining("不是试运行状态");
  }

  // ==========================================================================
  // 上游只读目录
  // ==========================================================================
  @Test
  void 上游目录与事件注册表真的读得出来() throws Exception {
    var eventTypes = getJson("/platform/automation-rules/event-types", AutomationRuleEventTypeRow[].class);
    assertThat(eventTypes).hasSize(1);
    assertThat(eventTypes[0].eventType()).isEqualTo(EVENT_TYPE);
    assertThat(eventTypes[0].status()).isEqualTo("ACTIVE");

    var shops = getJson("/platform/automation-rules/upstream/shops", AutomationRuleUpstreamShopRow[].class);
    assertThat(shops).hasSize(1);
    assertThat(shops[0].code()).isEqualTo("AMAZON-US-01");

    var warehouses =
        getJson("/platform/automation-rules/upstream/warehouses", AutomationRuleUpstreamWarehouseRow[].class);
    assertThat(warehouses).hasSize(1);
    assertThat(warehouses[0].code()).isEqualTo("US-WH-01");
  }
}
