package com.starcon.platform;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.platform.AutoApprovalModels.*;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 18.3.3.1「自动审批」的**功能**集成测试，跑在真实 PostgreSQL 16 上。
 *
 * <p><b>真实 PostgreSQL 16 + FORCE RLS，不使用任何内存替身。</b>
 *
 * <p>本测试盯住蓝图 18.3.3.1 的**四件事**与**那条加粗纪律**：
 *
 * <ol>
 *   <li><b>自动通过 = 三条件缺一不可</b>：小额 <b>且</b> 供应商已验证 <b>且</b> 无风险信号；
 *       <b>少一条就走人工</b>。
 *   <li><b>自动驳回只对「明显不合规」</b>：有<b>确定性</b>违规信号才驳回；
 *       「风险偏高」这种<b>主观判断不驳回</b>，走人工。
 *   <li><b>金额分层路由审批人</b>：分层边界来自上游的金额分支，区间语义与上游同口径
 *       （{@code [min, max)} 左闭右开），金额比较用 {@code compareTo}（scale 不同也判得对）。
 *   <li><b>超时自动升级</b>：判定用<b>上游 DB 钟</b>的停留秒数，落库时刻取<b>注入的 Clock</b>；
 *       没有 {@code Timestamp.valueOf(LocalDateTime.now())}，也没有 sleep。
 *   <li><b>决策依据必须能查</b>：记的是<b>当时</b>依据的规则与风险信号（快照），不是事后读当下的规则。
 * </ol>
 *
 * <p>🔴 <b>上游 identity 是个夹具</b>，但它**按上游的真实形状**作答（审批流详情含金额分支与
 * 超时规则、建单、审批记录、审批动作、成员清单）—— 本模块与上游之间的契约就是这些形状。
 *
 * <p>多租户隔离的三条断言在 {@code AutoApprovalTenancyIntegrationTest} 里（同一批表、同一套夹具）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AutoApprovalIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID tenant = UUID.randomUUID();
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static HttpServer identity;

  /** 上游那条审批流（金额分层与超时规则都挂在它上面）。 */
  static final UUID flowId = UUID.fromString("11111111-1111-4111-8111-111111111111");

  /** 上游那条分支指向的节点上的角色 / 成员。 */
  static final UUID roleId = UUID.fromString("22222222-2222-4222-8222-222222222222");

  static final UUID membershipId = UUID.fromString("33333333-3333-4333-8333-333333333333");

  /** 上游建出来的审批实例。 */
  static final UUID instanceId = UUID.fromString("44444444-4444-4444-8444-444444444444");

  static final ObjectMapper json =
      new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

  /** 本模块的四张表。 */
  static final List<String> TABLES =
      List.of(
          "platform_auto_approval_policy",
          "platform_auto_approval_signal",
          "platform_auto_approval_decision",
          "platform_auto_approval_escalation");

  static final List<String> PERMISSIONS =
      List.of(
          "platform.auto_approval.read",
          "platform.auto_approval.create",
          "platform.auto_approval.update",
          "platform.auto_approval.decide");

  // ---- 上游夹具的可控状态 ----

  /** 上游算出来的停留秒数（超时判定用它）。 */
  static final AtomicInteger dwellSeconds = new AtomicInteger(0);

  static final AtomicInteger submitCalls = new AtomicInteger(0);
  static final AtomicInteger decideCalls = new AtomicInteger(0);
  static final AtomicInteger transferCalls = new AtomicInteger(0);

  /** 上游动作端点的返回码（用来模拟「不是当前审批人」等域内拒绝）。 */
  static final AtomicInteger actionStatus = new AtomicInteger(200);

  /** 上游建单端点的返回码（用来模拟上游不可用）。 */
  static final AtomicInteger submitStatus = new AtomicInteger(200);

  /** 本模块自己的时钟：固定住，超时判定与落库时刻都不依赖真实时间。 */
  static final Instant FIXED_NOW = Instant.parse("2026-01-01T00:00:00Z");

  @TestConfiguration
  static class FixedClockConfiguration {
    @Bean
    @Primary
    Clock fixedAutoApprovalClock() {
      return Clock.fixed(FIXED_NOW, java.time.ZoneOffset.UTC);
    }
  }

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

      // ① 已认证会话（AccessService 走这条）
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

      // ② 审批流详情：节点 + **金额分支** + **超时升级规则**（上游的真实形状）
      identity.createContext(
          "/identity/approval-flows/",
          e ->
              respond(
                  e,
                  200,
                  map(
                      "flow",
                          map(
                              "id", flowId.toString(),
                              "documentTypeCode", "PURCHASE_ORDER",
                              "documentTypeName", "采购单",
                              "flowName", "采购审批流",
                              "status", "ACTIVE",
                              "version", 1L,
                              "nodeCount", 2,
                              "branchCount", 2,
                              "escalationCount", 1),
                      "nodes",
                          List.of(
                              map(
                                  "id", "55555555-5555-4555-8555-555555555555",
                                  "nodeKey", "MANAGER",
                                  "nodeName", "采购经理审批",
                                  "nodeOrder", 1,
                                  "stageIndex", 0,
                                  "approverType", "ROLE",
                                  "roleId", roleId.toString(),
                                  "roleCode", "PURCHASE_MANAGER",
                                  "approvalMode", "SEQUENTIAL"),
                              map(
                                  "id", "66666666-6666-4666-8666-666666666666",
                                  "nodeKey", "DIRECTOR",
                                  "nodeName", "采购总监审批",
                                  "nodeOrder", 2,
                                  "stageIndex", 0,
                                  "approverType", "USER",
                                  "membershipId", membershipId.toString(),
                                  "approverLabel", "采购总监",
                                  "approvalMode", "SEQUENTIAL")),
                      "branches",
                          List.of(
                              map(
                                  "id", "77777777-7777-4777-8777-777777777777",
                                  "branchName", "小额档",
                                  "currencyCode", "USD",
                                  "minAmount", null,
                                  "maxAmount", 1000,
                                  "targetNodeKey", "MANAGER",
                                  "priority", 10),
                              map(
                                  "id", "88888888-8888-4888-8888-888888888888",
                                  "branchName", "大额档",
                                  "currencyCode", "USD",
                                  "minAmount", 1000,
                                  "maxAmount", null,
                                  "targetNodeKey", "DIRECTOR",
                                  "priority", 10)),
                      "escalations",
                          List.of(
                              map(
                                  "id", "99999999-9999-4999-8999-999999999999",
                                  "nodeKey", "MANAGER",
                                  "timeoutAction", "ESCALATE",
                                  "timeoutHours", 48,
                                  "escalateApproverType", "USER",
                                  "escalateMembershipId", membershipId.toString(),
                                  "escalateLabel", "采购总监")))));

      // ③ 建单（上游唯一的程序化通道）
      identity.createContext(
          "/identity/approval-instances",
          e -> {
            submitCalls.incrementAndGet();
            if (submitStatus.get() != 200) {
              respond(e, submitStatus.get(), map("message", "上游建单失败"));
              return;
            }
            respond(
                e,
                200,
                map(
                    "id", instanceId.toString(),
                    "instanceNo", "AP-000000000001",
                    "flowId", flowId.toString(),
                    "currentNodeKey", "MANAGER",
                    "currentNodeName", "采购经理审批",
                    "applicantId", "99999999-1111-4111-8111-111111111111",
                    "routedBy", "AMOUNT_BRANCH",
                    "submittedAt", "2026-01-01T00:00:00Z"));
          });

      // ④ 审批记录（超时判定用的停留秒数来自它）
      identity.createContext(
          "/identity/approval-records/",
          e ->
              respond(
                  e,
                  200,
                  map(
                      "instance",
                          map(
                              "id", instanceId.toString(),
                              "instanceNo", "AP-000000000001",
                              "documentTypeCode", "PURCHASE_ORDER",
                              "currentNodeKey", "MANAGER",
                              "currentNodeName", "采购经理审批",
                              "status", "PENDING",
                              "dwellSeconds", dwellSeconds.get()))));

      // ⑤ 审批动作（decide / transfer）：上游只认**当前审批人自己**的会话
      identity.createContext(
          "/identity/my-approvals/",
          e -> {
            var path = e.getRequestURI().getPath();
            if (path.endsWith("/decide")) decideCalls.incrementAndGet();
            else transferCalls.incrementAndGet();
            if (actionStatus.get() != 200) {
              respond(e, actionStatus.get(), map("message", "上游拒绝"));
              return;
            }
            respond(
                e,
                200,
                map(
                    "id", instanceId.toString(),
                    "instanceNo", "AP-000000000001",
                    "status", path.endsWith("/decide") ? "APPROVED" : "PENDING"));
          });

      // ⑥ 成员清单（把「升级给某个角色」解析成具体成员时用）
      identity.createContext(
          "/identity/members",
          e ->
              respond(
                  e,
                  200,
                  List.of(
                      map(
                          "id", membershipId.toString(),
                          "userId", "aaaaaaaa-1111-4111-8111-111111111111",
                          "username", "director",
                          "status", "ACTIVE",
                          "assignments",
                          List.of(map("roleId", roleId.toString(), "scopeMode", "ALL")),
                          "owner", false))));

      identity.setExecutor(Executors.newFixedThreadPool(4));
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
    r.add("CATALOG_URL", () -> "http://127.0.0.1:1");
    r.add("PLATFORM_IO_STORAGE_ROOT", () -> "target/platform-auto-approval-store");
  }

  @AfterAll
  static void stopContainers() {
    if (identity != null) identity.stop(0);
    if (pg.isRunning()) pg.stop();
  }

  @BeforeEach
  void reset() throws Exception {
    dwellSeconds.set(0);
    submitCalls.set(0);
    decideCalls.set(0);
    transferCalls.set(0);
    actionStatus.set(200);
    submitStatus.set(200);
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate table platform_auto_approval_escalation, platform_auto_approval_decision,"
              + " platform_auto_approval_signal, platform_auto_approval_policy cascade");
      s.execute("alter sequence platform_auto_approval_decision_no_seq restart with 1");
    }
  }

  @Autowired MockMvc http;

  // ================================================================ 夹具：规则与信号

  /** 登记一条生效规则（小额上限 1000、硬上限 10000、挂上游那条流）。 */
  void seedPolicy(BigDecimal smallLimit, BigDecimal hardLimit) throws Exception {
    http.perform(
            post("/platform/auto-approvals/policies")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        map(
                            "ruleCode", "PO-USD",
                            "ruleName", "采购单美元自动审批",
                            "documentTypeCode", "PURCHASE_ORDER",
                            "currencyCode", "USD",
                            "autoApproveMaxAmount", smallLimit,
                            "amountHardLimit", hardLimit,
                            "upstreamFlowId", flowId.toString(),
                            "status", "ACTIVE",
                            "priority", 10,
                            "remark", ""))))
        .andExpect(status().isOk());
  }

  /** 登记一个风险信号码。 */
  void seedSignal(String code, String kind) throws Exception {
    http.perform(
            post("/platform/auto-approvals/signals")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        map(
                            "signalCode", code,
                            "signalName", code + " 的中文名",
                            "signalKind", kind,
                            "description", ""))))
        .andExpect(status().isOk());
  }

  /** 发起一次判定。 */
  com.fasterxml.jackson.databind.JsonNode decide(
      String amount, boolean verified, List<Map<String, Object>> signals) throws Exception {
    var body = new LinkedHashMap<String, Object>();
    body.put("documentTypeCode", "PURCHASE_ORDER");
    body.put("documentRef", "PO-2026-0001");
    body.put("subjectTitle", "采购单 PO-2026-0001");
    body.put("sourceSystem", "trade");
    body.put("currencyCode", "USD");
    if (amount != null) body.put("amount", new BigDecimal(amount));
    body.put("supplierId", "bbbbbbbb-1111-4111-8111-111111111111");
    body.put("supplierVerified", verified);
    body.put("riskSignals", signals);
    var response =
        http.perform(
                post("/platform/auto-approvals/decisions")
                    .header("Authorization", "session")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(body)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();
    return json.readTree(response.getContentAsString());
  }

  // ================================================================ 红线 1：三条件缺一不可

  @Test
  void 三条件齐备才自动通过() throws Exception {
    seedPolicy(new BigDecimal("1000"), new BigDecimal("10000"));
    var result = decide("100.00", true, List.of());
    assertThat(result.get("decision").asText()).isEqualTo("AUTO_APPROVED");
    assertThat(result.get("reasonCode").asText()).isEqualTo("SAFE_LOW_AMOUNT_VERIFIED_SUPPLIER");
    assertThat(result.get("executionState").asText()).isEqualTo("NOT_REQUIRED");
    // 🔴 自动通过**不建上游实例**：没有待办就没有审批人，也不该惊动上游。
    assertThat(submitCalls.get()).as("自动通过不该提交上游").isZero();
    // 依据逐条落库：三条件都成立。
    var codes = new java.util.ArrayList<String>();
    result.get("checks").forEach(c -> codes.add(c.get("code").asText()));
    assertThat(codes).contains("SMALL_AMOUNT", "SUPPLIER_VERIFIED", "NO_RISK_SIGNAL");
  }

  @Test
  void 金额不是小额就走人工() throws Exception {
    seedPolicy(new BigDecimal("1000"), new BigDecimal("10000"));
    var result = decide("1000.01", true, List.of());
    assertThat(result.get("decision").asText()).isEqualTo("MANUAL_REQUIRED");
    assertThat(result.get("reasonCode").asText()).isEqualTo("SMALL_AMOUNT");
    // 走人工 ⇒ 真的提交上游（上游的 intake 是唯一的程序化通道）。
    assertThat(result.get("executionState").asText()).isEqualTo("SUBMITTED_TO_APPROVAL");
    assertThat(result.get("upstreamInstanceId").asText()).isEqualTo(instanceId.toString());
    assertThat(submitCalls.get()).isEqualTo(1);
  }

  @Test
  void 供应商未验证就走人工() throws Exception {
    seedPolicy(new BigDecimal("1000"), new BigDecimal("10000"));
    var result = decide("100.00", false, List.of());
    assertThat(result.get("decision").asText()).isEqualTo("MANUAL_REQUIRED");
    assertThat(result.get("reasonCode").asText()).isEqualTo("SUPPLIER_VERIFIED");
    assertThat(submitCalls.get()).isEqualTo(1);
  }

  @Test
  void 有任何风险信号就走人工() throws Exception {
    seedPolicy(new BigDecimal("1000"), new BigDecimal("10000"));
    seedSignal("HIGH_RISK", "RISK");
    var result =
        decide(
            "100.00",
            true,
            List.of(map("code", "HIGH_RISK", "detail", "风险偏高")));
    assertThat(result.get("decision").asText()).isEqualTo("MANUAL_REQUIRED");
    assertThat(result.get("reasonCode").asText()).isEqualTo("NO_RISK_SIGNAL");
    assertThat(submitCalls.get()).isEqualTo(1);
  }

  @Test
  void 没有生效规则就走人工() throws Exception {
    var result = decide("100.00", true, List.of());
    assertThat(result.get("decision").asText()).isEqualTo("MANUAL_REQUIRED");
    assertThat(result.get("reasonCode").asText()).isEqualTo("POLICY_ACTIVE");
    // 没有规则 ⇒ 也就没有上游流程可提交 ⇒ 如实记 SUBMIT_FAILED，不假装已提交。
    assertThat(result.get("executionState").asText()).isEqualTo("SUBMIT_FAILED");
    assertThat(submitCalls.get()).isZero();
  }

  // ================================================================ 红线 2：只有确定性违规才驳回

  @Test
  void 确定性违规信号才自动驳回() throws Exception {
    seedPolicy(new BigDecimal("1000"), new BigDecimal("10000"));
    seedSignal("BLACKLIST_HIT", "VIOLATION");
    var result =
        decide("100.00", true, List.of(map("code", "BLACKLIST_HIT", "detail", "命中黑名单")));
    assertThat(result.get("decision").asText()).isEqualTo("AUTO_REJECTED");
    assertThat(result.get("reasonCode").asText()).isEqualTo("DETERMINISTIC_VIOLATION");
    assertThat(result.get("executionState").asText()).isEqualTo("NOT_REQUIRED");
    // 自动驳回同样不建上游实例。
    assertThat(submitCalls.get()).as("自动驳回不该提交上游").isZero();
  }

  @Test
  void 主观风险信号绝不自动驳回() throws Exception {
    seedPolicy(new BigDecimal("1000"), new BigDecimal("10000"));
    seedSignal("RISK_ELEVATED", "RISK");
    var result =
        decide(
            "100.00",
            true,
            List.of(map("code", "RISK_ELEVATED", "detail", "风险偏高")));
    assertThat(result.get("decision").asText()).isEqualTo("MANUAL_REQUIRED");
    assertThat(result.get("reasonCode").asText()).isNotEqualTo("DETERMINISTIC_VIOLATION");
    assertThat(submitCalls.get()).isEqualTo(1);
  }

  @Test
  void 未登记的信号码按从严处理不能拿来驳单() throws Exception {
    seedPolicy(new BigDecimal("1000"), new BigDecimal("10000"));
    var result =
        decide(
            "100.00",
            true,
            List.of(map("code", "MADE_UP_VIOLATION", "detail", "自称的违规")));
    assertThat(result.get("decision").asText())
        .as("调用方编一个码就想驳单：不可能")
        .isEqualTo("MANUAL_REQUIRED");
    var signals = result.get("riskSignals");
    assertThat(signals.get(0).get("registered").asBoolean()).isFalse();
    assertThat(signals.get(0).get("kind").asText()).isEqualTo("RISK");
  }

  @Test
  void 金额越硬上限才驳回且边界用比较而不是相等() throws Exception {
    seedPolicy(new BigDecimal("1000"), new BigDecimal("10000"));
    // 恰好等于硬上限 ⇒ 不越界（BigDecimal.compareTo：10000 与 10000.00 相等）
    var onBoundary = decide("10000.00", true, List.of());
    assertThat(onBoundary.get("decision").asText()).isEqualTo("MANUAL_REQUIRED");
    // 越过硬上限 ⇒ 确定性违规 ⇒ 驳回
    var above = decide("10000.01", true, List.of());
    assertThat(above.get("decision").asText()).isEqualTo("AUTO_REJECTED");
    assertThat(above.get("reasonCode").asText()).isEqualTo("AMOUNT_ABOVE_HARD_LIMIT");
  }

  @Test
  void 小额上限按数值比较不按scale相等() throws Exception {
    seedPolicy(new BigDecimal("1000"), new BigDecimal("10000"));
    // 100.00 与 1000 是相等的数值、不同 scale：equals 会判不等，compareTo 判相等。
    var result = decide("1000.00", true, List.of());
    assertThat(result.get("decision").asText())
        .as("1000.00 必须落在 1000 的小额范围内")
        .isEqualTo("AUTO_APPROVED");
  }

  // ================================================================ 红线 3：金额分层路由审批人

  @Test
  void 金额分层路由读上游的分支() throws Exception {
    seedPolicy(new BigDecimal("100000"), new BigDecimal("1000000"));
    // 2000 落在上游的「大额档」（[1000, ∞)）⇒ 目标节点 DIRECTOR
    var result = decide("2000.00", true, List.of(map("code", "SOME_RISK", "detail", "x")));
    var routing = result.get("routing");
    assertThat(routing.get("routed").asBoolean()).isTrue();
    assertThat(routing.get("branchName").asText()).isEqualTo("大额档");
    assertThat(routing.get("targetNodeKey").asText()).isEqualTo("DIRECTOR");
    assertThat(routing.get("targetNodeName").asText()).isEqualTo("采购总监审批");
    assertThat(routing.get("approverType").asText()).isEqualTo("USER");
    assertThat(routing.get("membershipId").asText()).isEqualTo(membershipId.toString());
    // 上游自己的路由结论也一并记下来。
    assertThat(routing.get("upstreamNodeKey").asText()).isEqualTo("MANAGER");
    assertThat(routing.get("upstreamRoutedBy").asText()).isEqualTo("AMOUNT_BRANCH");
  }

  @Test
  void 金额分层区间语义与上游一致左闭右开() throws Exception {
    seedPolicy(new BigDecimal("100000"), new BigDecimal("1000000"));
    // 1000 恰好落在上游大额档的下界（[1000, ∞)）⇒ 大额档，不是小额档
    var result = decide("1000.00", true, List.of(map("code", "SOME_RISK", "detail", "x")));
    assertThat(result.get("routing").get("branchName").asText()).isEqualTo("大额档");
    // 999.99 落在小额档（(−∞, 1000)）
    var below = decide("999.99", true, List.of(map("code", "SOME_RISK", "detail", "x")));
    assertThat(below.get("routing").get("branchName").asText()).isEqualTo("小额档");
    assertThat(below.get("routing").get("targetNodeKey").asText()).isEqualTo("MANAGER");
  }

  // ================================================================ 红线 5：决策依据是快照

  @Test
  void 决策依据记的是当时的规则快照而不是事后的规则() throws Exception {
    seedPolicy(new BigDecimal("1000"), new BigDecimal("10000"));
    var first = decide("100.00", true, List.of());
    var decisionId = first.get("id").asText();
    // 改规则：把小额上限降到 1
    var policies =
        json.readTree(
            http.perform(get("/platform/auto-approvals/policies").header("Authorization", "session"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    var policyId = policies.get(0).get("id").asText();
    http.perform(
            put("/platform/auto-approvals/policies/" + policyId)
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        map(
                            "ruleCode", "PO-USD",
                            "ruleName", "采购单美元自动审批",
                            "documentTypeCode", "PURCHASE_ORDER",
                            "currencyCode", "USD",
                            "autoApproveMaxAmount", 1,
                            "amountHardLimit", new BigDecimal("10000"),
                            "upstreamFlowId", flowId.toString(),
                            "status", "ACTIVE",
                            "priority", 10,
                            "remark", ""))))
        .andExpect(status().isOk());

    // 再看一眼那条决策：依据仍是**当初**的小额上限 1000（规则快照），不是改之后的 1。
    var detail =
        json.readTree(
            http.perform(
                    get("/platform/auto-approvals/decisions/" + decisionId)
                        .header("Authorization", "session"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    var snapshot = detail.get("policy");
    // 🔴 金额断言比**数值**（不是标度）：快照里的上限是 numeric(19,6)，回读带 6 位小数。
    assertThat(new BigDecimal(snapshot.get("autoApproveMaxAmount").asText()))
        .isEqualByComparingTo(new BigDecimal("1000"));
    assertThat(snapshot.get("ruleCode").asText()).isEqualTo("PO-USD");
    assertThat(detail.get("checks").toString()).contains("小额上限 1000");
  }

  @Test
  void 决策台账只追加不可改写() throws Exception {
    seedPolicy(new BigDecimal("1000"), new BigDecimal("10000"));
    var result = decide("100.00", true, List.of());
    var decisionId = result.get("id").asText();
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      assertThatThrownBy(
              () ->
                  s.execute(
                      "update platform_auto_approval_decision set reason_code='REWRITTEN'"
                          + " where id='"
                          + decisionId
                          + "'"))
          .hasStackTraceContaining("只追加");
      assertThatThrownBy(
              () ->
                  s.execute(
                      "delete from platform_auto_approval_decision where id='"
                          + decisionId
                          + "'"))
          .hasStackTraceContaining("只追加");
    }
  }

  // ================================================================ 红线 4：超时自动升级

  @Test
  void 超时判定用上游的停留秒数且到点才触发() throws Exception {
    seedPolicy(new BigDecimal("100000"), new BigDecimal("1000000"));
    // 先制造一条「走人工、已提交上游」的单
    decide("2000.00", true, List.of(map("code", "SOME_RISK", "detail", "x")));

    // 阈值 48 小时 ⇒ 172800 秒。差一秒 ⇒ 不该触发。
    dwellSeconds.set(48 * 3600 - 1);
    var before =
        json.readTree(
            http.perform(
                    post("/platform/auto-approvals/escalations/scan")
                        .header("Authorization", "session"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(before.get("detected").asInt()).isZero();

    // 到了阈值 ⇒ 触发。
    dwellSeconds.set(48 * 3600);
    var after =
        json.readTree(
            http.perform(
                    post("/platform/auto-approvals/escalations/scan")
                        .header("Authorization", "session"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(after.get("detected").asInt()).isEqualTo(1);
    var escalation = after.get("escalations").get(0);
    assertThat(escalation.get("timeoutHours").asInt()).isEqualTo(48);
    assertThat(escalation.get("timeoutAction").asText()).isEqualTo("ESCALATE");
    assertThat(escalation.get("escalateMembershipId").asText()).isEqualTo(membershipId.toString());
    assertThat(new BigDecimal(escalation.get("elapsedHours").asText()))
        .isEqualByComparingTo(new BigDecimal("48.000000"));
    // 🔴 落库时刻取**注入的 Clock**（固定住 ⇒ 可断言），不是应用服务器墙钟。
    //    ⚠️ 断言的是**时刻**而不是文本：timestamptz 回读时会按会话时区渲染
    //    （同一个时刻可能是 "…T08:00:00+08:00" 也可能是 "…T00:00:00Z"），
    //    把会话时区当成被测值就是 18.2.3.1 车道踩过的坑。
    assertThat(java.time.OffsetDateTime.parse(escalation.get("detectedAt").asText()).toInstant())
        .isEqualTo(FIXED_NOW);
  }

  @Test
  void 重复扫描不会把同一条单反复升级() throws Exception {
    seedPolicy(new BigDecimal("100000"), new BigDecimal("1000000"));
    decide("2000.00", true, List.of(map("code", "SOME_RISK", "detail", "x")));
    dwellSeconds.set(48 * 3600);
    http.perform(post("/platform/auto-approvals/escalations/scan").header("Authorization", "session"))
        .andExpect(status().isOk());
    var second =
        json.readTree(
            http.perform(
                    post("/platform/auto-approvals/escalations/scan")
                        .header("Authorization", "session"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(second.get("detected").asInt()).as("同一节点同一时限只升级一次").isZero();
  }

  @Test
  void 执行升级以调用者自己的身份走上游() throws Exception {
    seedPolicy(new BigDecimal("100000"), new BigDecimal("1000000"));
    decide("2000.00", true, List.of(map("code", "SOME_RISK", "detail", "x")));
    dwellSeconds.set(48 * 3600);
    var scan =
        json.readTree(
            http.perform(
                    post("/platform/auto-approvals/escalations/scan")
                        .header("Authorization", "session"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    var escalationId = scan.get("escalations").get(0).get("id").asText();
    var executed =
        json.readTree(
            http.perform(
                    post("/platform/auto-approvals/escalations/" + escalationId + "/execute")
                        .header("Authorization", "session"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(executed.get("status").asText()).isEqualTo("EXECUTED");
    assertThat(transferCalls.get()).as("ESCALATE ⇒ 走上游的转交通道").isEqualTo(1);
    assertThat(decideCalls.get()).isZero();
  }

  @Test
  void 上游拒绝时如实留痕而不是假装成功() throws Exception {
    seedPolicy(new BigDecimal("100000"), new BigDecimal("1000000"));
    decide("2000.00", true, List.of(map("code", "SOME_RISK", "detail", "x")));
    dwellSeconds.set(48 * 3600);
    var scan =
        json.readTree(
            http.perform(
                    post("/platform/auto-approvals/escalations/scan")
                        .header("Authorization", "session"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    var escalationId = scan.get("escalations").get(0).get("id").asText();
    // 上游说「这条待办不在你的待办里」（404）
    actionStatus.set(404);
    var executed =
        json.readTree(
            http.perform(
                    post("/platform/auto-approvals/escalations/" + escalationId + "/execute")
                        .header("Authorization", "session"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(executed.get("status").asText()).isEqualTo("FAILED");
    assertThat(executed.get("executionDetail").asText()).contains("不在当前用户的待办里");
  }

  // ================================================================ 边界与能力声明

  @Test
  void 能力声明如实说上游没有程序化决策通道() throws Exception {
    var capabilities =
        json.readTree(
            http.perform(
                    get("/platform/auto-approvals/capabilities").header("Authorization", "session"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(capabilities.get("upstreamProgrammaticDecisionChannel").asBoolean()).isFalse();
    assertThat(capabilities.get("upstreamDecisionChannelNote").asText()).contains("没有程序化通过/驳回通道");
    assertThat(capabilities.get("upstreamIntakeChannel").asText()).contains("approval-instances");
    assertThat(capabilities.get("decisionOwnership").asText()).contains("归本模块");
  }

  @Test
  void 上游建单失败也留下决策且如实标失败() throws Exception {
    seedPolicy(new BigDecimal("100000"), new BigDecimal("1000000"));
    submitStatus.set(500);
    var result = decide("2000.00", true, List.of(map("code", "SOME_RISK", "detail", "x")));
    assertThat(result.get("decision").asText()).isEqualTo("MANUAL_REQUIRED");
    assertThat(result.get("executionState").asText()).isEqualTo("SUBMIT_FAILED");
    assertThat(result.get("reasonMessage").asText()).contains("提交上游失败");
    // 决策照样落台账。
    var decisions =
        json.readTree(
            http.perform(get("/platform/auto-approvals/decisions").header("Authorization", "session"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(decisions.size()).isEqualTo(1);
  }

  @Test
  void 概览是真数() throws Exception {
    seedPolicy(new BigDecimal("1000"), new BigDecimal("10000"));
    decide("100.00", true, List.of());
    decide("100.00", false, List.of());
    var overview =
        json.readTree(
            http.perform(get("/platform/auto-approvals/overview").header("Authorization", "session"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(overview.get("policyCount").asLong()).isEqualTo(1);
    assertThat(overview.get("decisionCount").asLong()).isEqualTo(2);
    assertThat(overview.get("autoApprovedCount").asLong()).isEqualTo(1);
    assertThat(overview.get("manualRequiredCount").asLong()).isEqualTo(1);
  }

  // ================================================================ 配置面

  @Test
  void 同一单据类型与币种只能有一条生效规则() throws Exception {
    seedPolicy(new BigDecimal("1000"), new BigDecimal("10000"));
    http.perform(
            post("/platform/auto-approvals/policies")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        map(
                            "ruleCode", "PO-USD-2",
                            "ruleName", "第二条同类型规则",
                            "documentTypeCode", "PURCHASE_ORDER",
                            "currencyCode", "USD",
                            "autoApproveMaxAmount", 500,
                            "amountHardLimit", new BigDecimal("10000"),
                            "status", "ACTIVE"))))
        .andExpect(status().isConflict());
  }

  @Test
  void 停用规则不删历史() throws Exception {
    seedPolicy(new BigDecimal("1000"), new BigDecimal("10000"));
    var policies =
        json.readTree(
            http.perform(get("/platform/auto-approvals/policies").header("Authorization", "session"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    var policyId = policies.get(0).get("id").asText();
    http.perform(
            post("/platform/auto-approvals/policies/" + policyId + "/disable")
                .header("Authorization", "session"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("DISABLED"));
    // 停用之后没有生效规则 ⇒ 判定走人工。
    var result = decide("100.00", true, List.of());
    assertThat(result.get("decision").asText()).isEqualTo("MANUAL_REQUIRED");
  }

  @Test
  void 小额上限不能大于硬上限() throws Exception {
    http.perform(
            post("/platform/auto-approvals/policies")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        map(
                            "ruleCode", "PO-BAD",
                            "ruleName", "上下限颠倒的规则",
                            "documentTypeCode", "PURCHASE_ORDER",
                            "currencyCode", "USD",
                            "autoApproveMaxAmount", 10000,
                            "amountHardLimit", 1000,
                            "status", "ACTIVE"))))
        .andExpect(status().isBadRequest());
  }

  @Test
  void 没有权限就调不动() throws Exception {
    actors.put("readonly", actor(UUID.randomUUID(), tenant, List.of("platform.auto_approval.read")));
    http.perform(
            post("/platform/auto-approvals/policies")
                .header("Authorization", "readonly")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        map(
                            "ruleCode", "PO-NO-PERM",
                            "ruleName", "没有写权限",
                            "documentTypeCode", "PURCHASE_ORDER",
                            "currencyCode", "USD"))))
        .andExpect(status().isForbidden());
  }

  @Test
  void 四张表都强制rls() throws Exception {
    for (var table : TABLES) {
      var row =
          sql.fetchOne(
              "select relrowsecurity, relforcerowsecurity from pg_class where relname = ?", table);
      assertThat(row).as("V420 必须真的建出 " + table).isNotNull();
      assertThat(row.get(0, Boolean.class)).as(table + " 未启用 RLS").isTrue();
      assertThat(row.get(1, Boolean.class)).as(table + " 只有 FORCE 才挡得住 owner 身份").isTrue();
      var nullable =
          sql.fetchOne(
                  "select is_nullable from information_schema.columns"
                      + " where table_name = ? and column_name = 'tenant_id'",
                  table)
              .get(0, String.class);
      assertThat(nullable).as(table + " 的 tenant_id 必须 NOT NULL").isEqualTo("NO");
    }
  }

  @Autowired org.jooq.DSLContext sql;
}
