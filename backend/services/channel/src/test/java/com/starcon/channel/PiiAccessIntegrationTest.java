package com.starcon.channel;

import static com.starcon.channel.PiiAccessModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jooq.DSLContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 16.6.1.1 PII 管理。
 *
 * <p>本测试类钉住这几组事：
 *
 * <ol>
 *   <li><b>🔴 默认脱敏是硬默认</b>：不带 reveal 时返回的**永远是脱敏值**，
 *       且返回值里**不含完整明文**。</li>
 *   <li><b>🔴 看完整值必须走授权判定</b>：没有授权 ⇒ 403，**且这次越权尝试留在审计里**。</li>
 *   <li><b>🔴 每一次看完整值都留痕</b>：放行与被拒都有审计行。</li>
 *   <li><b>🔴 导出必须走审批</b>：未审批的导出请求**一律拒绝**（且这次尝试也留痕）；
 *       <b>审批人 ≠ 申请人</b>；<b>导出对象必须逐条列明</b>；<b>条数有上限</b>。</li>
 *   <li><b>🔴 审计 append-only</b>：应用账号与迁移超级账号**各试一次**改与删，都必须被拒。</li>
 *   <li><b>🔴 上游读不到 ⇒ 503</b>，绝不返回空集合冒充「这个人没有 PII」；
 *       上游正常但查无此人 ⇒ known=false。</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class PiiAccessIntegrationTest {

  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID user = UUID.randomUUID();
  static final UUID otherUser = UUID.randomUUID();
  static HttpServer identity;
  static PiiAccessUpstreamStub upstream;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static final ObjectMapper JSON = new ObjectMapper();

  UUID tenant;
  AccessService.Access actor;
  String bearer;

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

  static AccessService.Access actor(UUID uid, UUID tenantId, List<AccessService.Grant> grants) {
    return new AccessService.Access(
        uid, tenantId, "Test", UUID.randomUUID(), null,
        grants.stream().flatMap(g -> g.permissions().stream()).distinct().toList(),
        grants);
  }

  static AccessService.Grant grant(String scope, List<String> permissions, String roleCode) {
    return new AccessService.Grant(UUID.randomUUID(), roleCode, scope, permissions);
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
      identity.start();
      upstream = new PiiAccessUpstreamStub();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @BeforeEach
  void freshTenant() {
    tenant = UUID.randomUUID();
    bearer = "Bearer t-" + tenant;
    actors.put(bearer, actor(user, tenant, List.of(grant("ALL", allPermissions, "SUPPORT"))));
    actor = actors.get(bearer);
    upstream.reset();
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "channel_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://localhost:" + identity.getAddress().getPort());
    r.add("TRADE_URL", upstream::tradeBase);
    r.add("CRM_URL", upstream::crmBase);
    r.add("INTERNAL_KEY", () -> "test-key");
  }

  @Autowired PiiAccessService service;
  @Autowired AccessService access;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  // ==================================================================
  // 辅助
  // ==================================================================

  <T> T as(String permission, java.util.function.Supplier<T> work) {
    return access.run(actor, permission, work);
  }

  /** 造一张带完整 PII 的订单。 */
  String seedOrder(String orderId, String name, String phone, String email) {
    upstream.putOrder(orderId, "SO-" + orderId, OffsetDateTime.now(ZoneOffset.UTC).minusDays(1));
    upstream.putShippingAddress(
        orderId, name, phone, email, "US", "New York", "New York", "Manhattan", "10001",
        "742 Evergreen Terrace");
    return orderId;
  }

  /** 登记一条授权（角色 × 字段）。 */
  void grantField(String roleCode, String field, String scope) {
    as(
        "channel.operation.update",
        () ->
            service.saveGrant(
                actor,
                new PiiAccessGrantInput(
                    roleCode,
                    field,
                    scope,
                    Boolean.TRUE,
                    OffsetDateTime.now(ZoneOffset.UTC).minusDays(1),
                    null,
                    "测试授权")));
  }

  /** 从主体详情里按字段码取一栏。 */
  PiiAccessFieldValue field(PiiAccessSubjectDetail detail, String fieldCode) {
    return detail.fields().stream()
        .filter(f -> f.fieldCode().equals(fieldCode))
        .findFirst()
        .orElseThrow(() -> new AssertionError("详情里没有字段 " + fieldCode));
  }

  /** 换成一个别的成员（同租户），用来做「审批人 ≠ 申请人」。 */
  AccessService.Access asOtherMember() {
    AccessService.Access other =
        actor(otherUser, tenant, List.of(grant("ALL", allPermissions, "SUPPORT")));
    actors.put("Bearer other-" + tenant, other);
    return other;
  }

  // ==================================================================
  // 一、默认脱敏
  // ==================================================================

  @Test
  @DisplayName("16.6.1.1 默认就是脱敏：返回值里不含完整明文")
  void defaultIsMasked() {
    String orderId = seedOrder("o-default", "Alice Smith", "+1-555-0100", "alice@example.com");

    PiiAccessRevealOutcome outcome =
        as("channel.operation.read", () -> service.subject(actor, bearer, "ORDER", orderId, false, null, null));

    assertThat(outcome.allowed()).isTrue();
    assertThat(outcome.detail().revealed()).isFalse();
    PiiAccessFieldValue name = field(outcome.detail(), "NAME");
    assertThat(name.masked()).isTrue();
    assertThat(name.value()).isEqualTo("A**** Smith");
    assertThat(name.value()).doesNotContain("Alice Smith");

    PiiAccessFieldValue phone = field(outcome.detail(), "PHONE");
    assertThat(phone.value()).doesNotContain("+1-555-0100");

    PiiAccessFieldValue email = field(outcome.detail(), "EMAIL");
    assertThat(email.value()).doesNotContain("alice@example.com");

    PiiAccessFieldValue address = field(outcome.detail(), "ADDRESS");
    assertThat(address.value()).doesNotContain("742 Evergreen Terrace");
    assertThat(address.value()).doesNotContain("10001");
  }

  @Test
  @DisplayName("16.6.1.1 脱敏视图也留痕（谁看过哪个主体）")
  void maskedViewIsAudited() {
    String orderId = seedOrder("o-audit", "Bob Jones", "+1-555-0200", "bob@example.com");

    as("channel.operation.read", () -> service.subject(actor, bearer, "ORDER", orderId, false, null, null));

    PiiAccessAuditPage page = as("channel.operation.read", () -> service.audit(null, null, null, null, null, 0, 50));
    assertThat(page.items())
        .as("脱敏视图必须留痕")
        .anyMatch(r -> r.subjectRef().equals(orderId) && r.action().equals("VIEW_MASKED")
            && r.decision().equals("ALLOWED") && r.fieldCode().equals("ALL"));
  }

  @Test
  @DisplayName("16.6.1.1 主体列表默认脱敏（且带分页与来源说明）")
  void subjectListIsMaskedAndPaged() {
    seedOrder("o-1", "Alice Smith", "+1-555-0100", "alice@example.com");
    seedOrder("o-2", "Bob Jones", "+1-555-0200", "bob@example.com");

    PiiAccessSubjectPage page =
        as("channel.operation.read", () -> service.subjects(actor, bearer, "ORDER", "", 0, 10));

    assertThat(page.items()).hasSize(2);
    assertThat(page.total()).isEqualTo(2);
    for (PiiAccessSubjectView view : page.items()) {
      for (PiiAccessSubjectField f : view.fields()) {
        assertThat(f.maskedValue()).doesNotContain("Alice Smith").doesNotContain("Bob Jones");
      }
    }
  }

  @Test
  @DisplayName("16.6.1.1 15 客户来源：电话与地址标成「上游没给」，不是空值")
  void crmSourceReportsMissingFields() {
    upstream.putBuyerEmail("buyer-1", "Carol White", "carol@example.com");

    PiiAccessSubjectPage page =
        as("channel.operation.read", () -> service.subjects(actor, bearer, "CUSTOMER", "", 0, 10));

    assertThat(page.items()).hasSize(1);
    PiiAccessSubjectView view = page.items().get(0);
    assertThat(view.availableFields()).contains("NAME", "EMAIL");
    assertThat(view.missingFields()).contains("PHONE", "ADDRESS");
    assertThat(view.fields().stream()
            .filter(f -> f.fieldCode().equals("NAME"))
            .map(PiiAccessSubjectField::maskedValue)
            .findFirst()
            .orElse(""))
        .isEqualTo("C**** White");
  }

  // ==================================================================
  // 二、授权判定
  // ==================================================================

  @Test
  @DisplayName("16.6.1.1 没有授权 ⇒ 403，且这次越权尝试留在审计里")
  void revealWithoutGrantIsDeniedAndAudited() {
    String orderId = seedOrder("o-denied", "Dave Brown", "+1-555-0300", "dave@example.com");

    PiiAccessRevealOutcome outcome =
        as(
            "channel.operation.read",
            () ->
                service.subject(
                    actor, bearer, "ORDER", orderId, true, List.of("ADDRESS"), "工单 #123"));

    assertThat(outcome.allowed()).isFalse();
    assertThat(outcome.denialReason()).contains("地址");

    PiiAccessAuditPage page =
        as("channel.operation.read", () -> service.audit(null, orderId, "VIEW_FULL", "DENIED", null, 0, 50));
    assertThat(page.items())
        .as("越权尝试必须留痕（不能被事务回滚带走）")
        .anyMatch(r -> r.action().equals("VIEW_FULL") && r.decision().equals("DENIED")
            && r.fieldCode().equals("ADDRESS") && r.decisionReason().contains("没有授权"));
  }

  @Test
  @DisplayName("16.6.1.1 HTTP 层：未授权的完整值查看返回 403")
  void revealWithoutGrantReturns403() throws Exception {
    String orderId = seedOrder("o-403", "Erin Hill", "+1-555-0400", "erin@example.com");

    http.perform(
            get("/channel/pii-access/subjects/ORDER/" + orderId)
                .param("reveal", "true")
                .param("fields", "ADDRESS")
                .header("Authorization", bearer))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("16.6.1.1 授权通过 ⇒ 返回完整值并逐字段留痕")
  void revealWithGrantReturnsPlaintextAndAudits() {
    String orderId = seedOrder("o-ok", "Alice Smith", "+1-555-0100", "alice@example.com");
    grantField("SUPPORT", "ADDRESS", "ALL");

    PiiAccessRevealOutcome outcome =
        as(
            "channel.operation.read",
            () ->
                service.subject(
                    actor, bearer, "ORDER", orderId, true, List.of("ADDRESS"), "买家要求改地址"));

    assertThat(outcome.allowed()).isTrue();
    assertThat(outcome.detail().revealed()).isTrue();
    assertThat(field(outcome.detail(), "ADDRESS").masked()).isFalse();
    assertThat(field(outcome.detail(), "ADDRESS").value()).contains("742 Evergreen Terrace");
    assertThat(field(outcome.detail(), "ADDRESS").present()).isTrue();
    // 没申请的那几栏仍然是脱敏的
    assertThat(field(outcome.detail(), "NAME").masked()).isTrue();

    PiiAccessAuditPage page =
        as("channel.operation.read", () -> service.audit(null, orderId, "VIEW_FULL", "ALLOWED", null, 0, 50));
    assertThat(page.items())
        .anyMatch(r -> r.action().equals("VIEW_FULL") && r.decision().equals("ALLOWED")
            && r.fieldCode().equals("ADDRESS") && r.grantRoleCode().equals("SUPPORT")
            && r.reason().equals("买家要求改地址"));
  }

  @Test
  @DisplayName("16.6.1.1 任一字段没授权 ⇒ 整个请求被拒（不给半份完整值）")
  void partialGrantDeniesWholeRequest() {
    String orderId = seedOrder("o-partial", "Alice Smith", "+1-555-0100", "alice@example.com");
    grantField("SUPPORT", "NAME", "ALL");

    PiiAccessRevealOutcome outcome =
        as(
            "channel.operation.read",
            () ->
                service.subject(
                    actor, bearer, "ORDER", orderId, true, List.of("NAME", "ADDRESS"), "查单"));

    assertThat(outcome.allowed()).as("一半有授权一半没有 ⇒ 整个被拒").isFalse();
    assertThat(outcome.detail().revealed()).isFalse();
  }

  @Test
  @DisplayName("16.6.1.1 撤销授权后再看 ⇒ 被拒")
  void revokedGrantNoLongerAllows() {
    String orderId = seedOrder("o-revoked", "Alice Smith", "+1-555-0100", "alice@example.com");
    grantField("SUPPORT", "ADDRESS", "ALL");

    PiiAccessGrantView grant =
        as("channel.operation.read", () -> service.grants("SUPPORT", "ACTIVE", 0, 50)).items().get(0);
    as(
        "channel.operation.update",
        () -> service.revokeGrant(actor, grant.grantId().toString(),
            new PiiAccessGrantRevokeInput("临时授权到期")));

    PiiAccessRevealOutcome outcome =
        as(
            "channel.operation.read",
            () ->
                service.subject(
                    actor, bearer, "ORDER", orderId, true, List.of("ADDRESS"), "查单"));

    assertThat(outcome.allowed()).isFalse();
  }

  @Test
  @DisplayName("16.6.1.1 SELF 范围：订单主体判不了 ⇒ 拒绝并说清原因")
  void selfScopeOnOrderIsUndecidable() {
    String orderId = seedOrder("o-self", "Alice Smith", "+1-555-0100", "alice@example.com");
    grantField("SUPPORT", "ADDRESS", "SELF");

    PiiAccessRevealOutcome outcome =
        as(
            "channel.operation.read",
            () ->
                service.subject(
                    actor, bearer, "ORDER", orderId, true, List.of("ADDRESS"), "查单"));

    assertThat(outcome.allowed()).isFalse();
    assertThat(outcome.denialReason()).contains("判不了");
  }

  @Test
  @DisplayName("16.6.1.1 理由必填：没写理由 ⇒ 422")
  void revealWithoutReasonIsRejected() {
    String orderId = seedOrder("o-reason", "Alice Smith", "+1-555-0100", "alice@example.com");
    grantField("SUPPORT", "ADDRESS", "ALL");

    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    as(
                        "channel.operation.read",
                        () ->
                            service.subject(
                                actor, bearer, "ORDER", orderId, true, List.of("ADDRESS"), ""))))
        .isNotNull();
  }

  @Test
  @DisplayName("16.6.1.1 reveal 必须指明字段：不指明 ⇒ 400")
  void revealWithoutFieldsIsRejected() {
    String orderId = seedOrder("o-nofields", "Alice Smith", "+1-555-0100", "alice@example.com");
    grantField("SUPPORT", "ADDRESS", "ALL");

    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    as(
                        "channel.operation.read",
                        () ->
                            service.subject(
                                actor, bearer, "ORDER", orderId, true, null, "查单"))))
        .isNotNull();
  }

  // ==================================================================
  // 三、查无此人 vs 读不到
  // ==================================================================

  @Test
  @DisplayName("16.6.1.1 上游正常但查无此人 ⇒ known=false（不是「这个人没有 PII」）")
  void unknownSubjectIsKnownFalse() {
    PiiAccessRevealOutcome outcome =
        as("channel.operation.read", () -> service.subject(actor, bearer, "ORDER", "no-such-order", false, null, null));

    assertThat(outcome.allowed()).isTrue();
    assertThat(outcome.detail().known()).isFalse();
    assertThat(outcome.detail().revealNote()).contains("查无此人");
  }

  @Test
  @DisplayName("16.6.1.1 上游不可达 ⇒ 503（明确报「读不到」，不返回空集合冒充）")
  void upstreamDownIs503() {
    upstream.tradeDown = true;
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    as(
                        "channel.operation.read",
                        () -> service.subjects(actor, bearer, "ORDER", "", 0, 10))))
        .isNotNull()
        .satisfies(t -> assertThat(t.getMessage()).contains("读不到").contains("不可达"));
  }

  // ==================================================================
  // 四、导出审批
  // ==================================================================

  PiiAccessExportRequestInput exportInput(String orderId, int count) {
    return new PiiAccessExportRequestInput(
        "ORDER", "ADDRESS", count, "发货异常排查", List.of(orderId));
  }

  @Test
  @DisplayName("16.6.1.1 导出必须走审批：未审批的导出请求一律拒绝，且这次尝试留痕")
  void unapprovedExportIsRejected() {
    String orderId = seedOrder("o-exp1", "Alice Smith", "+1-555-0100", "alice@example.com");

    PiiAccessExportRequestView request =
        as("channel.operation.create", () -> service.createExportRequest(actor, exportInput(orderId, 1)));
    assertThat(request.status()).isEqualTo("PENDING");

    PiiAccessExportOutcome outcome =
        as("channel.operation.create", () -> service.executeExport(actor, bearer, request.requestId().toString()));

    assertThat(outcome.allowed()).isFalse();
    assertThat(outcome.denialReason()).contains("还没有获批");

    PiiAccessAuditPage page =
        as("channel.operation.read", () -> service.audit(null, null, "EXPORT", "DENIED", null, 0, 50));
    assertThat(page.items())
        .as("未审批就执行的尝试必须留痕")
        .anyMatch(r -> r.action().equals("EXPORT") && r.decision().equals("DENIED")
            && r.exportRequestId().equals(request.requestId()));
  }

  @Test
  @DisplayName("16.6.1.1 审批人 ≠ 申请人：自己批自己 ⇒ 403")
  void selfApprovalIsRejected() {
    String orderId = seedOrder("o-exp2", "Alice Smith", "+1-555-0100", "alice@example.com");
    PiiAccessExportRequestView request =
        as("channel.operation.create", () -> service.createExportRequest(actor, exportInput(orderId, 1)));

    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    as(
                        "channel.operation.update",
                        () ->
                            service.approveExport(
                                actor, request.requestId().toString(),
                                new PiiAccessExportDecisionInput("同意")))))
        .isNotNull();
  }

  @Test
  @DisplayName("16.6.1.1 审批通过后执行 ⇒ 真的拿到 PII，并落导出凭据与审计")
  void approvedExportReturnsRealPii() {
    String orderId = seedOrder("o-exp3", "Alice Smith", "+1-555-0100", "alice@example.com");
    PiiAccessExportRequestView request =
        as("channel.operation.create", () -> service.createExportRequest(actor, exportInput(orderId, 1)));

    AccessService.Access approver = asOtherMember();
    access.run(
        approver,
        "channel.operation.update",
        () -> service.approveExport(approver, request.requestId().toString(),
            new PiiAccessExportDecisionInput("已核对工单，同意导出")));

    PiiAccessExportOutcome outcome =
        as("channel.operation.create", () -> service.executeExport(actor, bearer, request.requestId().toString()));

    assertThat(outcome.allowed()).isTrue();
    assertThat(outcome.result().rows()).hasSize(1);
    assertThat(outcome.result().rows().get(0).value()).contains("742 Evergreen Terrace");
    assertThat(outcome.result().recordCount()).isEqualTo(1);

    // 导出凭据：只存摘要，不存 PII 明文
    String digest =
        scalar(
            "select subject_digest from pii_access_export_batch where export_request_id='"
                + request.requestId()
                + "'");
    assertThat(digest).hasSize(32).matches("^[0-9a-f]{32}$");
    assertThat(digest).doesNotContain("742 Evergreen Terrace");

    // 审计：导出放行留痕
    PiiAccessAuditPage page =
        as("channel.operation.read", () -> service.audit(null, null, "EXPORT", "ALLOWED", null, 0, 50));
    assertThat(page.items())
        .anyMatch(r -> r.action().equals("EXPORT") && r.decision().equals("ALLOWED")
            && r.exportRequestId().equals(request.requestId()));
  }

  @Test
  @DisplayName("16.6.1.1 导出对象必须逐条列明：清单条数与申请条数不一致 ⇒ DB 层拒绝")
  void exportItemCountMismatchIsRejected() {
    String orderId = seedOrder("o-exp4", "Alice Smith", "+1-555-0100", "alice@example.com");
    // 申请 2 条、只给 1 个 ref ⇒ 服务层先拒
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    as(
                        "channel.operation.create",
                        () ->
                            service.createExportRequest(
                                actor,
                                new PiiAccessExportRequestInput(
                                    "ORDER", "ADDRESS", 2, "排查", List.of(orderId))))))
        .isNotNull();
  }

  @Test
  @DisplayName("16.6.1.1 导出条数有上限：超过上限 ⇒ 422")
  void exportOverLimitIsRejected() {
    as(
        "channel.operation.update",
        () ->
            service.savePolicy(
                new PiiAccessPolicyInput("严格策略", 1, 24, 0, true, null)));

    String orderId = seedOrder("o-exp5", "Alice Smith", "+1-555-0100", "alice@example.com");
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    as(
                        "channel.operation.create",
                        () ->
                            service.createExportRequest(
                                actor,
                                new PiiAccessExportRequestInput(
                                    "ORDER", "ADDRESS", 1, "排查",
                                    List.of(orderId, orderId + "-2"))))))
        .isNotNull();
  }

  @Test
  @DisplayName("16.6.1.1 导出理由必填")
  void exportPurposeIsRequired() {
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    as(
                        "channel.operation.create",
                        () ->
                            service.createExportRequest(
                                actor,
                                new PiiAccessExportRequestInput(
                                    "ORDER", "ADDRESS", 1, "", List.of("x"))))))
        .isNotNull();
  }

  // ==================================================================
  // 五、审计 append-only
  // ==================================================================

  @Test
  @DisplayName("16.6.1.1 审计表 append-only：应用账号改不动、删不掉")
  void auditIsAppendOnlyForAppRole() {
    String orderId = seedOrder("o-immutable", "Alice Smith", "+1-555-0100", "alice@example.com");
    as("channel.operation.read", () -> service.subject(actor, bearer, "ORDER", orderId, false, null, null));

    List<String> ids =
        as("channel.operation.read", () -> service.audit(null, null, null, null, null, 0, 50))
            .items().stream().map(r -> r.auditId().toString()).toList();
    assertThat(ids).isNotEmpty();

    for (String id : ids) {
      assertThat(
              org.assertj.core.api.Assertions.catchThrowable(
                  () -> execAsApp("update pii_access_view_audit set decision = 'ALLOWED' where id='"
                      + id + "'")))
          .as("应用账号不许改审计行")
          .isNotNull();
      assertThat(
              org.assertj.core.api.Assertions.catchThrowable(
                  () -> execAsApp("delete from pii_access_view_audit where id='" + id + "'")))
          .as("应用账号不许删审计行")
          .isNotNull();
    }
  }

  @Test
  @DisplayName("16.6.1.1 审计表 append-only：迁移超级账号也改不动、删不掉")
  void auditIsAppendOnlyForSuperuser() {
    String orderId = seedOrder("o-immutable2", "Alice Smith", "+1-555-0100", "alice@example.com");
    as("channel.operation.read", () -> service.subject(actor, bearer, "ORDER", orderId, false, null, null));

    String id =
        as("channel.operation.read", () -> service.audit(null, null, null, null, null, 0, 50))
            .items().get(0).auditId().toString();

    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () -> execAsSuper("update pii_access_view_audit set decision = 'ALLOWED' where id='"
                    + id + "'")))
        .as("连迁移超级账号都改不动审计行")
        .isNotNull();
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () -> execAsSuper("delete from pii_access_view_audit where id='" + id + "'")))
        .as("连迁移超级账号都删不掉审计行")
        .isNotNull();
  }

  @Test
  @DisplayName("16.6.1.1 审计表的写权限只有 SELECT 与 INSERT")
  void auditGrantsAreSelectInsertOnly() {
    for (String table : List.of("pii_access_view_audit", "pii_access_export_item", "pii_access_export_batch")) {
      Map<String, String> grants = new java.util.LinkedHashMap<>();
      for (String privilege : List.of("SELECT", "INSERT", "UPDATE", "DELETE")) {
        grants.put(privilege, privilegeOf(table, privilege));
      }
      assertThat(grants.get("UPDATE")).as(table + " 不许 UPDATE（实际：" + grants + "）").isEqualTo("f");
      assertThat(grants.get("DELETE")).as(table + " 不许 DELETE（实际：" + grants + "）").isEqualTo("f");
      assertThat(grants.get("SELECT")).as(table + " 要能 SELECT（实际：" + grants + "）").isEqualTo("t");
      assertThat(grants.get("INSERT")).as(table + " 要能 INSERT（实际：" + grants + "）").isEqualTo("t");
    }
  }

  // ==================================================================
  // 六、策略与能力声明
  // ==================================================================

  @Test
  @DisplayName("16.6.1.1 策略没登记时 configured=false，不预填默认值")
  void policyDefaultsToNotConfigured() {
    PiiAccessPolicyView policy = as("channel.operation.read", () -> service.policy());
    assertThat(policy.configured()).isFalse();
    assertThat(policy.policyName()).isEmpty();
  }

  @Test
  @DisplayName("16.6.1.1 能力声明写清了脱敏、授权、审计、导出与边界")
  void capabilitiesDescribeTheRules() {
    PiiAccessCapabilities caps = as("channel.operation.read", () -> service.capabilities());
    assertThat(caps.maskingSemantics()).contains("硬默认").contains("可逆编码");
    assertThat(caps.authorizationSemantics()).contains("授权判定").contains("范围");
    assertThat(caps.auditSemantics()).contains("append-only");
    assertThat(caps.exportSemantics()).contains("审批人 ≠ 申请人").contains("一键全量导出");
    assertThat(caps.storageSemantics()).contains("不复制买家 PII");
    assertThat(caps.fields()).hasSize(4);
  }

  // ==================================================================
  // 工具
  // ==================================================================

  void execAsApp(String statement) {
    try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), "channel_app", "test-only");
        var s = c.createStatement()) {
      s.execute("select set_config('app.current_tenant','" + tenant + "',false)");
      s.execute(statement);
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  void execAsSuper(String statement) {
    try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute("select set_config('app.current_tenant','" + tenant + "',false)");
      s.execute(statement);
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  static String scalar(String statement) {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement();
        var rs = s.executeQuery(statement)) {
      return rs.next() ? rs.getString(1) : null;
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * channel_app 在某张表上有没有某个权限（'t' / 'f'）。
   *
   * <p>🔴 表名**必须带 schema 限定**（{@code public.xxx}）：不带的话走 search_path 解析，
   * 解析不到时 {@code has_table_privilege} 给的是 null 而不是 'f' ——
   * 断言「不许 UPDATE」会因为 null 假通过，而断言「要能 SELECT」会假失败，
   * 两种都在掩盖真正的问题。
   */
  String privilegeOf(String table, String privilege) {
    String value =
        scalar(
            "select has_table_privilege('channel_app', 'public." + table + "', '" + privilege
                + "')::text");
    if (value == null) {
      return "null";
    }
    // PostgreSQL 的 boolean::text 给的是 'true' / 'false'，不是 psql 的 't' / 'f'。
    return "true".equals(value) ? "t" : "false".equals(value) ? "f" : value;
  }
}
