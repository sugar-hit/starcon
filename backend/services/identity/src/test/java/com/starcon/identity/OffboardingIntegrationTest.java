package com.starcon.identity;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.starcon.tenancy.TenantTransactions;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 17.2.2.3 离职与停用。
 *
 * <p>职责里的四块各自都要有真实读出口，所以断言也按四块组织，而不是只测「接口没报错」：
 *
 * <ol>
 *   <li><b>停用成员（非删除）</b>——成员变 INACTIVE 但行还在，角色分配被撤销，账号可查；
 *   <li><b>转移在职单据与审批责任</b>——责任台账从 PENDING 走到 TRANSFERRED，未结清时案卷保持 OPEN；
 *   <li><b>回收待办与任务</b>——待办关闭、任务改派是两种不同结论，台账要能分辨；
 *   <li><b>保留历史操作记录</b>——审计可查、案卷行不被级联清掉、membership 物理删除被 DB 拒绝。
 * </ol>
 *
 * <p>时间字段全部按值断言：{@code effectiveAt} 用 +08:00 偏移写入，读回必须是同一个瞬时，
 * 否则用 {@code Timestamp.valueOf(LocalDateTime)} 绑 timestamptz 的写法会静默偏 −28800 秒。
 *
 * <p><b>服务边界</b>：审批（17.4.2.2）与工作台待办（01）服务尚未交付，本测试**不跨服务读表**；
 * 台账条目由本服务自己登记，模拟下游经授权 API 登记后的状态流转，据此验证转移与回收是否真落库。
 */
class OffboardingIntegrationTest extends IdentityIntegrationTestBase {

  @org.springframework.beans.factory.annotation.Autowired IdentityController identity;

  private TenantTransactions tx() {
    return new TenantTransactions(sql, new TransactionTemplate(manager));
  }

  /** 会话 token 的哈希：member_names 这类窄权限入口以它为条件解析用户名。 */
  private String hash(String token) {
    return IdentityController.hash(identity.token("Bearer " + token));
  }

  /** 不预设状态码地发一次请求，返回响应与状态 —— 用来断言错误文案而不只是错误码。 */
  private org.springframework.mock.web.MockHttpServletResponse rawRequest(
      String token, String method, String path, Object body) throws Exception {
    var builder =
        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request(
                org.springframework.http.HttpMethod.valueOf(method), path)
            .header("Authorization", "Bearer " + token)
            .header("X-Tenant-Id", UUID.randomUUID())
            .contentType("application/json");
    if (body != null) builder.content(json.writeValueAsString(body));
    return http.perform(builder).andReturn().getResponse();
  }

  private UUID role(String token, String code) throws Exception {
    return UUID.fromString(
        request(
                token,
                "POST",
                "/identity/roles",
                Map.of(
                    "code", code,
                    "name", "离职测试角色 " + code,
                    "status", "ACTIVE",
                    // identity.member.read：让被停用的成员在离职前能自证「本来可以读」，
                    // 否则停用后的 401 缺少对照，读起来像权限本来就没生效。
                    "permissions",
                        List.of("catalog.spu.read", "identity.member.read")),
                200)
            .asText());
  }

  /** 建一个启用中的普通成员（带角色），返回 membership id。 */
  private UUID member(String token, String username, UUID roleId) throws Exception {
    return UUID.fromString(
        request(
                token,
                "POST",
                "/identity/members",
                memberInput(username, null, List.of(Map.of("roleId", roleId, "scopeMode", "ALL"))),
                200)
            .asText());
  }

  private String tenantId(String token) throws Exception {
    return request(token, "GET", "/identity/tenants/current", null, 200).get("tenantId").asText();
  }

  /** 按用户名在成员列表里找一行，找不到返回 null。 */
  private JsonNode memberByName(String token, String username) throws Exception {
    for (JsonNode m : request(token, "GET", "/identity/members", null, 200))
      if (username.equals(m.get("username").asText())) return m;
    return null;
  }

  /** 按 reason 标记在案卷列表里找一行 —— 用测试专属标记，避免依赖共享租户的全量条数。 */
  private JsonNode caseByReason(String token, String reason) throws Exception {
    for (JsonNode c : request(token, "GET", "/identity/offboarding", null, 200))
      if (reason.equals(c.get("reason").asText())) return c;
    return null;
  }

  private Map<String, Object> offboardInput(UUID membershipId, String reason, String effectiveAt) {
    var input = new LinkedHashMap<String, Object>();
    input.put("membershipId", membershipId.toString());
    input.put("reason", reason);
    input.put("effectiveAt", effectiveAt);
    input.put("responsibilityItems", List.of());
    input.put("reclaimItems", List.of());
    return input;
  }

  private Map<String, Object> responsibility(String kind, String source, String title) {
    var item = new LinkedHashMap<String, Object>();
    item.put("itemKind", kind);
    item.put("sourceSystem", source);
    item.put("referenceId", UUID.randomUUID().toString());
    item.put("referenceNo", "REF-" + UUID.randomUUID().toString().substring(0, 6));
    item.put("title", title);
    return item;
  }

  private Map<String, Object> reclaim(String kind, String source, String title) {
    var item = new LinkedHashMap<String, Object>();
    item.put("itemKind", kind);
    item.put("sourceSystem", source);
    item.put("referenceId", UUID.randomUUID().toString());
    item.put("title", title);
    return item;
  }

  /** 造一个子租户并返回它的所有者 token。 */
  private String childOwnerToken(String ownerToken, String tag) throws Exception {
    String username = "ob-child-" + tag;
    request(
        ownerToken,
        "POST",
        "/identity/children",
        Map.of(
            "code", "OB_" + tag,
            "name", "离职测试子租户",
            "ownerUsername", username,
            "ownerPassword", "test-member-password"),
        200);
    return loginUser(username);
  }

  // ------------------------------------------------------------------
  // 停用成员（非删除）
  // ------------------------------------------------------------------

  /**
   * 停用不是删除：成员变 INACTIVE，但 membership 行、app_user 行、离职案卷都在。
   *
   * <p>蓝图「停用而非删除」是硬约束，所以这里逐条查三张表：人没被删、账号没被删、
   * 离职记录留下来了。少查一张就证明不了「历史操作记录还在」。
   */
  @Test
  void deactivatingAMemberNeverDeletesThem() throws Exception {
    String token = login(), tag = suffix();
    UUID roleId = role(token, "OBV_" + tag);
    String username = "ob-victim-" + tag;
    UUID membershipId = member(token, username, roleId);
    String reason = "离职测试-" + tag;

    UUID caseId =
        UUID.fromString(
            request(
                    token,
                    "POST",
                    "/identity/offboarding",
                    offboardInput(membershipId, reason, "2026-03-01T09:30:00+08:00"),
                    200)
                .asText());

    // 1) 成员行还在，只是状态变了
    var view = memberByName(token, username);
    assertThat(view).as("被停用的成员必须仍在成员列表里").isNotNull();
    assertThat(view.get("id").asText()).isEqualTo(membershipId.toString());
    assertThat(view.get("status").asText()).isEqualTo("INACTIVE");

    // 2) 角色分配被撤销：离职与撤权同时发生，不能留「已离职但仍持有角色」的窗口
    assertThat(view.get("assignments")).as("离职后不应再持有角色分配").isEmpty();

    // 3) 直查库：人没有被物理删除，离职案卷也还在
    UUID tid = UUID.fromString(tenantId(token));
    tx()
        .run(
            tid,
            () -> {
              // membership 行仍在，只是状态变了（不是被删掉后重建）
              assertThat(
                      sql.fetchOne(
                              "select status from membership where tenant_id=? and id=?",
                              tid,
                              membershipId)
                          .get("status", String.class))
                  .isEqualTo("INACTIVE");
              // membership 行仍在（没被删掉重建），用户名仍能解析出来
              assertThat(
                      sql.fetchOne(
                              "select username from member_names(?) n join membership m on m.id=n.id where n.id=?",
                              hash(token),
                              membershipId)
                          .get("username", String.class))
                  .isEqualTo(username);
              // 离职案卷在
              assertThat(
                      sql.fetchExists(
                          sql.selectOne().from("offboarding_case").where("tenant_id=? and id=?", tid, caseId)))
                  .isTrue();
              // 角色分配已撤销
              assertThat(
                      sql.fetch(
                              "select 1 from role_assignment where tenant_id=? and membership_id=?",
                              tid,
                              membershipId))
                  .isEmpty();
              return null;
            });
  }

  /** 停用成员后他登录不了（memberships_for 只认 ACTIVE），但账号本身没被删。 */
  @Test
  void aDeactivatedMemberCanNoLongerSignIn() throws Exception {
    String token = login(), tag = suffix();
    UUID roleId = role(token, "OBL_" + tag);
    String username = "ob-leave-" + tag;
    member(token, username, roleId);
    String memberToken = loginUser(username);
    // 停用前可读
    request(memberToken, "GET", "/identity/members", null, 200);

    request(
        token,
        "POST",
        "/identity/offboarding",
        offboardInput(
            UUID.fromString(memberByName(token, username).get("id").asText()),
            "离职停用登录-" + tag,
            "2026-03-01T09:30:00+08:00"),
        200);

    // 停用后旧会话立刻失效：session_access 经 memberships_for 解析，INACTIVE 不在其中
    request(memberToken, "GET", "/identity/members", null, 401);
    // 重新登录也被挡住。凭据本身没被删（app_user 仍是 ACTIVE），
    // 是 membership 已停用导致建会话失败，所以是 403 而不是 401。
    request("", "POST", "/auth/login", Map.of("username", username, "password", "test-member-password"), 403);
  }

  /** 重复停用与自我停用都要被挡住，前者撞「同租户只能一条在办离职」。 */
  @Test
  void doubleOffboardingAndSelfOffboardingAreRejected() throws Exception {
    String token = login(), tag = suffix();
    UUID roleId = role(token, "OBD_" + tag);
    UUID membershipId = member(token, "ob-dup-" + tag, roleId);
    String reason = "重复离职-" + tag;
    String path = "/identity/offboarding";

    // 带一条未结清的责任条目 → 案卷停在 OPEN
    var input = offboardInput(membershipId, reason, "2026-03-01T09:30:00+08:00");
    input.put("responsibilityItems", List.of(responsibility("DOCUMENT", "trade", "在办单")));
    request(token, "POST", path, input, 200);
    assertThat(caseByReason(token, reason).get("status").asText()).isEqualTo("OPEN");

    // 同一人再发一次 → 部分唯一索引 (tenant, membership) WHERE status='OPEN'
    request(token, "POST", path, offboardInput(membershipId, reason, "2026-03-01T09:30:00+08:00"), 409);

    // 已经 INACTIVE 的人再发 → 明确拒绝（此处案卷已结案，不会撞唯一索引）
    UUID other = member(token, "ob-dup2-" + tag, roleId);
    request(token, "POST", path, offboardInput(other, reason + "-2", "2026-03-01T09:30:00+08:00"), 200);
    request(token, "POST", path, offboardInput(other, reason + "-2", "2026-03-01T09:30:00+08:00"), 400);
  }

  // ------------------------------------------------------------------
  // 转移在职单据与审批责任
  // ------------------------------------------------------------------

  /**
   * 责任转移台账：未结清时案卷保持 OPEN，转移后记下接手人与时刻，全部结清才 COMPLETED。
   *
   * <p>「先结案再补」是这里最容易出的错：停用那一刻下游还没消费台账，说处理完了就是假的。
   * 所以案卷状态由待结条目推导，而不是由操作人随手点一下。
   */
  @Test
  void responsibilityTransfersAreRecordedAndGateTheCaseStatus() throws Exception {
    String token = login(), tag = suffix();
    UUID roleId = role(token, "OBR_" + tag);
    UUID leavingId = member(token, "ob-resp-" + tag, roleId);
    String successorName = "ob-successor-" + tag;
    UUID successorId = member(token, successorName, roleId);

    var input = offboardInput(leavingId, "责任转移-" + tag, "2026-03-01T09:30:00+08:00");
    input.put(
        "responsibilityItems",
        List.of(
            responsibility("DOCUMENT", "trade", "在办销售单"),
            responsibility("APPROVAL", "workflow", "待审采购单")));
    UUID caseId =
        UUID.fromString(request(token, "POST", "/identity/offboarding", input, 200).asText());

    // 停用后案卷是 OPEN：2 条待结
    var open = caseByReason(token, "责任转移-" + tag);
    assertThat(open.get("status").asText()).isEqualTo("OPEN");
    assertThat(open.get("responsibilityTotal").asInt()).isEqualTo(2);
    assertThat(open.get("responsibilityPending").asInt()).isEqualTo(2);
    assertThat(open.get("memberName").asText()).isEqualTo("ob-resp-" + tag);

    var detail = request(token, "GET", "/identity/offboarding/" + caseId, null, 200);
    assertThat(detail.get("responsibilityItems")).hasSize(2);
    for (JsonNode item : detail.get("responsibilityItems")) {
      assertThat(item.get("status").asText()).isEqualTo("PENDING");
      // 「还没定接手人」必须如实显示为空，不能拿个占位值顶替
      assertThat(item.get("successorMembershipId").isNull()).isTrue();
      assertThat(item.get("transferredAt").isNull()).isTrue();
      assertThat(item.get("ownerMembershipId").asText()).isEqualTo(leavingId.toString());
    }

    // 转移第一条
    UUID firstId =
        UUID.fromString(detail.get("responsibilityItems").get(0).get("id").asText());
    Instant before = Instant.now();
    request(
        token,
        "POST",
        "/identity/offboarding/" + caseId + "/responsibility-items/" + firstId + "/transfer",
        Map.of("successorMembershipId", successorId.toString()),
        200);

    var mid = request(token, "GET", "/identity/offboarding/" + caseId, null, 200);
    var moved = mid.get("responsibilityItems").get(0);
    assertThat(moved.get("status").asText()).isEqualTo("TRANSFERRED");
    assertThat(moved.get("successorMembershipId").asText()).isEqualTo(successorId.toString());
    assertThat(moved.get("successorName").asText()).isEqualTo(successorName);
    assertThat(OffsetDateTime.parse(moved.get("transferredAt").asText()).toInstant())
        .isAfterOrEqualTo(before.minusSeconds(60))
        .isBefore(Instant.now().plusSeconds(60));
    assertThat(mid.get("offboardingCase").get("responsibilityPending").asInt()).isEqualTo(1);
    assertThat(mid.get("offboardingCase").get("status").asText()).isEqualTo("OPEN");

    // 已转移过的不能再转一次
    request(
        token,
        "POST",
        "/identity/offboarding/" + caseId + "/responsibility-items/" + firstId + "/transfer",
        Map.of("successorMembershipId", successorId.toString()),
        400);

    // 转完剩下那条 → 案卷结清
    UUID secondId =
        UUID.fromString(mid.get("responsibilityItems").get(1).get("id").asText());
    request(
        token,
        "POST",
        "/identity/offboarding/" + caseId + "/responsibility-items/" + secondId + "/transfer",
        Map.of("successorMembershipId", successorId.toString()),
        200);
    var done = request(token, "GET", "/identity/offboarding/" + caseId, null, 200);
    assertThat(done.get("offboardingCase").get("status").asText()).isEqualTo("COMPLETED");
    assertThat(done.get("offboardingCase").get("responsibilityPending").asInt()).isZero();
  }

  /** 接手人必须是启用中的其他成员，否则转移会把它写成永久悬空的责任。 */
  @Test
  void aResponsibilityTransferNeedsAnActiveDifferentSuccessor() throws Exception {
    String token = login(), tag = suffix();
    UUID roleId = role(token, "OBS_" + tag);
    UUID leavingId = member(token, "ob-suc-" + tag, roleId);
    String goneName = "ob-gone-" + tag;
    UUID goneId = member(token, goneName, roleId);

    var input = offboardInput(leavingId, "接手人校验-" + tag, "2026-03-01T09:30:00+08:00");
    input.put("responsibilityItems", List.of(responsibility("DOCUMENT", "trade", "在办单")));
    UUID caseId =
        UUID.fromString(request(token, "POST", "/identity/offboarding", input, 200).asText());
    UUID itemId =
        UUID.fromString(
            request(token, "GET", "/identity/offboarding/" + caseId, null, 200)
                .get("responsibilityItems")
                .get(0)
                .get("id")
                .asText());

    // 自己也停掉了，不能当接手人
    request(
        token,
        "POST",
        "/identity/offboarding",
        offboardInput(goneId, "先停用-" + tag, "2026-03-01T09:30:00+08:00"),
        200);
    String transferPath =
        "/identity/offboarding/" + caseId + "/responsibility-items/" + itemId + "/transfer";
    // 已停用的人 → 400
    request(
        token, "POST", transferPath, Map.of("successorMembershipId", goneId.toString()), 400);
    // 原责任人本人 → 同样 400：停用检查先命中（他已经是 INACTIVE），
    // 「不能转给自己」这条更靠后，但两条都拒绝，条目不会被写坏。
    request(
        token, "POST", transferPath, Map.of("successorMembershipId", leavingId.toString()), 400);
    // 别的租户的成员 → 查不到（RLS 让它不可见）
    request(
        token,
        "POST",
        transferPath,
        Map.of("successorMembershipId", UUID.randomUUID().toString()),
        404);

    // 条目仍然是 PENDING：被拒的转移不许留下半个结果
    assertThat(
            request(token, "GET", "/identity/offboarding/" + caseId, null, 200)
                .get("responsibilityItems")
                .get(0)
                .get("status")
                .asText())
        .isEqualTo("PENDING");
  }

  // ------------------------------------------------------------------
  // 回收待办与任务
  // ------------------------------------------------------------------

  /**
   * 待办与任务的处置不同：待办关闭、任务改派。台账必须能分辨「关掉了」和「转给别人了」。
   *
   * <p>如果只留一个布尔「已处理」，事后没人能回答这条待办到底是消失了还是换了人。
   */
  @Test
  void todosAreReclaimedAndTasksAreReassigned() throws Exception {
    String token = login(), tag = suffix();
    UUID roleId = role(token, "OBT_" + tag);
    UUID leavingId = member(token, "ob-todo-" + tag, roleId);
    String successorName = "ob-picker-" + tag;
    UUID successorId = member(token, successorName, roleId);

    var input = offboardInput(leavingId, "待办回收-" + tag, "2026-03-01T09:30:00+08:00");
    input.put(
        "reclaimItems",
        List.of(
            reclaim("TODO", "workbench", "待办：确认补发"),
            reclaim("TASK", "workbench", "任务：季度盘点")));
    UUID caseId =
        UUID.fromString(request(token, "POST", "/identity/offboarding", input, 200).asText());

    var detail = request(token, "GET", "/identity/offboarding/" + caseId, null, 200);
    assertThat(detail.get("reclaimItems")).hasSize(2);
    assertThat(detail.get("offboardingCase").get("status").asText()).isEqualTo("OPEN");
    assertThat(detail.get("offboardingCase").get("reclaimPending").asInt()).isEqualTo(2);

    UUID todoId =
        UUID.fromString(detail.get("reclaimItems").get(0).get("id").asText());
    UUID taskId =
        UUID.fromString(detail.get("reclaimItems").get(1).get("id").asText());

    Instant before = Instant.now();
    // 待办：直接回收，不给接手人
    request(
        token,
        "POST",
        "/identity/offboarding/" + caseId + "/reclaim-items/" + todoId + "/reclaim",
        Map.of(),
        200);
    // 任务：改派
    request(
        token,
        "POST",
        "/identity/offboarding/" + caseId + "/reclaim-items/" + taskId + "/reclaim",
        Map.of("successorMembershipId", successorId.toString()),
        200);

    var after = request(token, "GET", "/identity/offboarding/" + caseId, null, 200);
    var todo = after.get("reclaimItems").get(0);
    var task = after.get("reclaimItems").get(1);

    assertThat(todo.get("status").asText()).isEqualTo("RECLAIMED");
    assertThat(todo.get("successorMembershipId").isNull()).isTrue();
    assertThat(task.get("status").asText()).isEqualTo("REASSIGNED");
    assertThat(task.get("successorMembershipId").asText()).isEqualTo(successorId.toString());
    assertThat(task.get("successorName").asText()).isEqualTo(successorName);
    // 两种结论都留下操作人与时刻，事后能答出「谁在什么时候处理的」
    for (JsonNode item : after.get("reclaimItems")) {
      assertThat(item.get("reclaimedByMembershipId").asText()).isNotBlank();
      assertThat(OffsetDateTime.parse(item.get("reclaimedAt").asText()).toInstant())
          .isAfterOrEqualTo(before.minusSeconds(60))
          .isBefore(Instant.now().plusSeconds(60));
    }
    assertThat(after.get("offboardingCase").get("status").asText()).isEqualTo("COMPLETED");
    assertThat(after.get("offboardingCase").get("reclaimPending").asInt()).isZero();

    // 已回收的不能再回收
    request(
        token,
        "POST",
        "/identity/offboarding/" + caseId + "/reclaim-items/" + todoId + "/reclaim",
        Map.of(),
        400);
  }

  // ------------------------------------------------------------------
  // 保留历史操作记录
  // ------------------------------------------------------------------

  /**
   * 离职本身与后续每一次转移/回收都进审计，且操作人可读。
   *
   * <p>「保留历史操作记录」是本模块存在的理由，所以按 resourceId 精确核对条数与动作序列，
   * 而不是笼统断言「有审计」。
   */
  @Test
  void everyOffboardingStepIsAudited() throws Exception {
    String token = login(), tag = suffix();
    UUID roleId = role(token, "OBA_" + tag);
    UUID leavingId = member(token, "ob-audit-" + tag, roleId);
    UUID successorId = member(token, "ob-audsucc-" + tag, roleId);

    var input = offboardInput(leavingId, "审计留痕-" + tag, "2026-03-01T09:30:00+08:00");
    input.put("responsibilityItems", List.of(responsibility("APPROVAL", "workflow", "待审单")));
    UUID caseId =
        UUID.fromString(request(token, "POST", "/identity/offboarding", input, 200).asText());

    UUID itemId =
        UUID.fromString(
            request(token, "GET", "/identity/offboarding/" + caseId, null, 200)
                .get("responsibilityItems")
                .get(0)
                .get("id")
                .asText());
    request(
        token,
        "POST",
        "/identity/offboarding/" + caseId + "/responsibility-items/" + itemId + "/transfer",
        Map.of("successorMembershipId", successorId.toString()),
        200);

    var entries =
        request(
            token,
            "GET",
            "/identity/audit?resourceType=identity.offboarding&resourceId=" + caseId,
            null,
            200);
    // 停用 + 一次转移 = 两条
    assertThat(entries.get("total").asInt()).isEqualTo(2);

    var actions = new ArrayList<String>();
    for (JsonNode e : entries.get("items")) actions.add(e.get("action").asText());
    assertThat(actions).contains("MemberOffboarded", "ResponsibilityTransferred");

    // 按动作分别核对载荷：停用留的是案卷快照，转移留的是条目快照，
    // 两者形状不同，混着断言会读到一个不存在的字段然后报 NPE（而不是真的失败）。
    JsonNode offboard = null, transfer = null;
    for (JsonNode e : entries.get("items")) {
      if ("MemberOffboarded".equals(e.get("action").asText())) offboard = e;
      if ("ResponsibilityTransferred".equals(e.get("action").asText())) transfer = e;
    }
    assertThat(offboard).isNotNull();
    assertThat(transfer).isNotNull();

    var offboardChanges =
        request(token, "GET", "/identity/audit/" + offboard.get("id").asText(), null, 200)
            .get("changes");
    assertThat(offboardChanges.get("after").get("reason").asText()).isEqualTo("审计留痕-" + tag);
    assertThat(offboardChanges.get("after").get("memberName").asText()).isEqualTo("ob-audit-" + tag);
    assertThat(offboardChanges.get("after").get("responsibilityTotal").asInt()).isEqualTo(1);
    // before 留的是停用前的成员档案：status 是 ACTIVE、角色也还在
    assertThat(offboardChanges.get("before").get("status").asText()).isEqualTo("ACTIVE");

    var transferChanges =
        request(token, "GET", "/identity/audit/" + transfer.get("id").asText(), null, 200)
            .get("changes");
    assertThat(transferChanges.get("before").get("status").asText()).isEqualTo("PENDING");
    assertThat(transferChanges.get("after").get("status").asText()).isEqualTo("TRANSFERRED");
    assertThat(transferChanges.get("after").get("successorMembershipId").asText())
        .isEqualTo(successorId.toString());
  }

  /**
   * 「停用而非删除」在 DB 层也要成立：物理删除留有离职记录的成员必须被拒绝。
   *
   * <p>控制器没有 DELETE 端点只是应用层自律。这里直连表删一次，证明防线不依赖调用方自觉：
   * 外键 RESTRICT + membership 上的删除保护触发器（沿用 V5 法人删除保护的先例）。
   */
  @Test
  void aMemberWithOffboardingHistoryCannotBePhysicallyDeleted() throws Exception {
    String token = login(), tag = suffix();
    UUID roleId = role(token, "OBD_" + tag);
    String username = "ob-guard-" + tag;
    UUID membershipId = member(token, username, roleId);
    UUID tid = UUID.fromString(tenantId(token));
    String reason = "删除保护-" + tag;
    UUID caseId =
        UUID.fromString(
            request(
                    token,
                    "POST",
                    "/identity/offboarding",
                    offboardInput(membershipId, reason, "2026-03-01T09:30:00+08:00"),
                    200)
                .asText());

    assertThatThrownBy(() -> tx().run(tid, () -> sql.execute("delete from membership where id=?", membershipId)))
        .hasMessageContaining("离职与停用记录");

    // 案卷本身也删不掉：物理删除在语义上不存在
    assertThatThrownBy(() -> tx().run(tid, () -> sql.execute("delete from offboarding_case where id=?", caseId)))
        .isNotNull();

    // 记录仍然完好
    assertThat(caseByReason(token, reason).get("id").asText()).isEqualTo(caseId.toString());
    assertThat(memberByName(token, username).get("status").asText()).isEqualTo("INACTIVE");
  }

  /** 本模块不提供任何物理删除端点：DELETE 方法在案卷与台账路径上都不映射。 */
  @Test
  void thereIsNoPhysicalDeleteEndpoint() throws Exception {
    String token = login(), tag = suffix();
    UUID roleId = role(token, "OBE_" + tag);
    UUID leavingId = member(token, "ob-nodel-" + tag, roleId);
    UUID caseId =
        UUID.fromString(
            request(
                    token,
                    "POST",
                    "/identity/offboarding",
                    offboardInput(leavingId, "无删除端点-" + tag, "2026-03-01T09:30:00+08:00"),
                    200)
                .asText());

    // 路径存在但方法不支持 → 405，而不是静默返回 200
    request(token, "DELETE", "/identity/offboarding/" + caseId, null, 405);
    request(token, "DELETE", "/identity/offboarding", null, 405);
  }

  // ------------------------------------------------------------------
  // 可逆
  // ------------------------------------------------------------------

  /**
   * 撤销离职：成员恢复启用，案卷标 REVERTED 并记下撤销人与时刻。
   *
   * <p>恢复的是案卷里记下的 {@code previousStatus}，而不是硬编码 ACTIVE。
   * 已转移的条目不回滚 —— 它们已经发生在下游，identity 无权假装没发生。
   */
  @Test
  void offboardingIsReversible() throws Exception {
    String token = login(), tag = suffix();
    UUID roleId = role(token, "OBV2_" + tag);
    String username = "ob-back-" + tag;
    UUID membershipId = member(token, username, roleId);
    String reason = "复职-" + tag;
    UUID caseId =
        UUID.fromString(
            request(
                    token,
                    "POST",
                    "/identity/offboarding",
                    offboardInput(membershipId, reason, "2026-03-01T09:30:00+08:00"),
                    200)
                .asText());

    assertThat(memberByName(token, username).get("status").asText()).isEqualTo("INACTIVE");

    Instant before = Instant.now();
    request(token, "POST", "/identity/offboarding/" + caseId + "/revert", Map.of(), 200);

    var reverted = caseByReason(token, reason);
    assertThat(reverted.get("status").asText()).isEqualTo("REVERTED");
    assertThat(reverted.get("revertedByMembershipId").asText()).isNotBlank();
    assertThat(OffsetDateTime.parse(reverted.get("revertedAt").asText()).toInstant())
        .isAfterOrEqualTo(before.minusSeconds(60))
        .isBefore(Instant.now().plusSeconds(60));
    assertThat(memberByName(token, username).get("status").asText()).isEqualTo("ACTIVE");

    // 已撤销的不能再撤销，也不能再追加条目
    request(token, "POST", "/identity/offboarding/" + caseId + "/revert", Map.of(), 400);
    request(
        token,
        "POST",
        "/identity/offboarding/" + caseId + "/responsibility-items",
        responsibility("DOCUMENT", "trade", "撤销后再登记"),
        400);

    // 撤销后可以再次发起离职（复职后二次离职是正常业务）
    request(
        token,
        "POST",
        "/identity/offboarding",
        offboardInput(membershipId, reason + "-2", "2026-03-01T09:30:00+08:00"),
        200);
  }

  // ------------------------------------------------------------------
  // 部门负责人移交
  // ------------------------------------------------------------------

  /**
   * 离职成员是部门负责人时必须指定接手人，否则拒绝。
   *
   * <p>否则部门会指向一个已经离职的人，审批链兜底人选一个停用账号等于没有兜底。
   */
  @Test
  void departmentLeadershipMustBeHandedOverOnOffboarding() throws Exception {
    String token = login(), tag = suffix();
    UUID roleId = role(token, "OBDP_" + tag);
    String leaderName = "ob-leader-" + tag;
    UUID leaderId = member(token, leaderName, roleId);
    String successorName = "ob-newleader-" + tag;
    UUID successorId = member(token, successorName, roleId);

    UUID deptId =
        UUID.fromString(
            request(
                    token,
                    "POST",
                    "/identity/departments",
                    departmentInput("OBDEPT_" + tag, "离职测试部门", null, null, leaderId),
                    200)
                .asText());

    // 没指定接手人 → 403，并说明有几个部门待移交
    var response =
        rawRequest(
            token,
            "POST",
            "/identity/offboarding",
            offboardInput(leaderId, "负责人离职-" + tag, "2026-03-01T09:30:00+08:00"));
    // 被拒后成员仍然是启用状态、案卷没有落地：拒绝不能留下半个结果。
    // 错误正文由统一异常处理序列化，测试只钉状态码与「状态没变」这两件真正要紧的事。
    assertThat(response.getStatus()).isEqualTo(403);
    assertThat(memberByName(token, leaderName).get("status").asText()).isEqualTo("ACTIVE");
    assertThat(caseByReason(token, "负责人离职-" + tag)).isNull();

    // 指定接手人 → 通过，负责人指针真的换人了
    var input = offboardInput(leaderId, "负责人离职-" + tag, "2026-03-01T09:30:00+08:00");
    input.put("departmentLeaderSuccessorMembershipId", successorId.toString());
    request(token, "POST", "/identity/offboarding", input, 200);

    var node = findNode(request(token, "GET", "/identity/departments", null, 200), "OBDEPT_" + tag);
    assertThat(node).isNotNull();
    assertThat(node.get("leaderMembershipId").asText()).isEqualTo(successorId.toString());
    assertThat(node.get("leaderName").asText()).isEqualTo(successorName);
    assertThat(deptId).isNotNull();
  }

  // ------------------------------------------------------------------
  // 权限
  // ------------------------------------------------------------------

  /** 读按页面权限码，写要求 OWNER：普通成员既读不到也不能发起离职。 */
  @Test
  void offboardingRequiresPagePermissionAndOwnership() throws Exception {
    String token = login(), tag = suffix();
    UUID roleId = role(token, "OBP_" + tag);
    String username = "ob-plain-" + tag;
    UUID membershipId = member(token, username, roleId);
    String memberToken = loginUser(username);

    // 没有 identity.offboarding.read
    request(memberToken, "GET", "/identity/offboarding", null, 403);
    // 发起离职是组织变更 → 非 OWNER 一律拒绝
    request(
        memberToken,
        "POST",
        "/identity/offboarding",
        offboardInput(membershipId, "越权离职-" + tag, "2026-03-01T09:30:00+08:00"),
        403);

    // 不能停用租户所有者；acting user 就是所有者本人，所以这条同时覆盖「不能停用自己」
    UUID ownerId = null;
    for (JsonNode m : request(token, "GET", "/identity/members", null, 200))
      if (m.get("owner").asBoolean()) ownerId = UUID.fromString(m.get("id").asText());
    assertThat(ownerId).as("共享租户里必须能找到一个所有者").isNotNull();
    request(
        token,
        "POST",
        "/identity/offboarding",
        offboardInput(ownerId, "停用所有者-" + tag, "2026-03-01T09:30:00+08:00"),
        403);
    // 被拒后所有者仍然是启用状态，且没有落地案卷
    var owner = memberByName(token, "local-admin");
    assertThat(owner).isNotNull();
    assertThat(owner.get("status").asText()).isEqualTo("ACTIVE");
    assertThat(caseByReason(token, "停用所有者-" + tag)).isNull();

    // 停用别人是允许的：上一条被拒只是因为那恰好是自己，不是「所有人都不能停用」
    request(
        token,
        "POST",
        "/identity/offboarding",
        offboardInput(membershipId, "正常停用-" + tag, "2026-03-01T09:30:00+08:00"),
        200);
  }

  // ------------------------------------------------------------------
  // timestamptz 往返
  // ------------------------------------------------------------------

  /**
   * effectiveAt 是真实写入并原样读回的 timestamptz。
   *
   * <p>09:30 +08:00 == 01:30Z：同一个瞬时两种写法结果必须一致。若绑定时走
   * {@code Timestamp.valueOf(LocalDateTime)}，库里会静默偏 −28800 秒，
   * 而只断言「没抛异常」是发现不了的。
   */
  @Test
  void effectiveAtIsARealTimestamptzWrittenAndReadBack() throws Exception {
    String token = login(), tag = suffix();
    UUID roleId = role(token, "OBTS_" + tag);
    UUID membershipId = member(token, "ob-time-" + tag, roleId);
    String reason = "时间戳-" + tag;
    UUID caseId =
        UUID.fromString(
            request(
                    token,
                    "POST",
                    "/identity/offboarding",
                    offboardInput(membershipId, reason, "2026-01-01T09:30:00+08:00"),
                    200)
                .asText());

    Instant expected = OffsetDateTime.parse("2026-01-01T09:30:00+08:00").toInstant();
    var view = caseByReason(token, reason);
    assertThat(OffsetDateTime.parse(view.get("effectiveAt").asText()).toInstant())
        .as("离职生效时刻必须原样往返（漂移 8 小时说明 timestamptz 绑定错了）")
        .isEqualTo(expected);

    UUID tid = UUID.fromString(tenantId(token));
    var stored =
        tx()
            .run(
                tid,
                () ->
                    sql.fetchOne("select effective_at from offboarding_case where id=?", caseId)
                        .get("effective_at", OffsetDateTime.class));
    assertThat(stored).isNotNull();
    assertThat(stored.toInstant()).isEqualTo(expected);
  }

  // ------------------------------------------------------------------
  // 多租户三断言
  // ------------------------------------------------------------------

  /**
   * 租户隔离三断言：两租户互不可见 / 伪造 tenant header 无效 / 连接池归还后不串租户。
   *
   * <p>底座把连接池压到 1，所以每个请求必然复用同一条物理连接——租户设置泄漏到连接上，
   * 第二个租户就会读到第一个租户的离职案卷。
   */
  @Test
  void tenantIsolationHoldsAcrossConnectionReuse() throws Exception {
    String ownerToken = login(), tag = suffix();
    String childToken = childOwnerToken(ownerToken, tag);
    UUID parentId = UUID.fromString(tenantId(ownerToken));
    UUID childId = UUID.fromString(tenantId(childToken));
    assertThat(parentId).isNotEqualTo(childId);

    UUID parentRole = role(ownerToken, "OBIP_" + tag);
    UUID childRole = role(childToken, "OBIC_" + tag);
    UUID parentMember = member(ownerToken, "ob-iso-p-" + tag, parentRole);
    UUID childMember = member(childToken, "ob-iso-c-" + tag, childRole);
    String parentReason = "父租户离职-" + tag;
    String childReason = "子租户离职-" + tag;

    request(
        ownerToken,
        "POST",
        "/identity/offboarding",
        offboardInput(parentMember, parentReason, "2026-03-01T09:30:00+08:00"),
        200);
    request(
        childToken,
        "POST",
        "/identity/offboarding",
        offboardInput(childMember, childReason, "2026-03-01T09:30:00+08:00"),
        200);

    // 1) 两租户互不可见（交替 3 次，覆盖连接复用）
    for (int i = 0; i < 3; i++) {
      var parentCases = request(ownerToken, "GET", "/identity/offboarding", null, 200);
      assertThat(caseByReasonIn(parentCases, parentReason)).isNotNull();
      assertThat(caseByReasonIn(parentCases, childReason)).isNull();

      var childCases = request(childToken, "GET", "/identity/offboarding", null, 200);
      assertThat(caseByReasonIn(childCases, childReason)).isNotNull();
      assertThat(caseByReasonIn(parentCases, childReason)).isNull();
    }

    // 2) 伪造 X-Tenant-Id：即使写成一个真实存在的别家租户，也换不来它的离职案卷
    var forged =
        http.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                        "/identity/offboarding")
                    .header("Authorization", "Bearer " + ownerToken)
                    .header("X-Tenant-Id", childId.toString()))
            .andExpect(
                org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(forged).doesNotContain(childReason).doesNotContain(childId.toString());

    // 跨租户按 id 读详情同样读不到
    var childCase = caseByReasonIn(
        request(childToken, "GET", "/identity/offboarding", null, 200), childReason);
    request(ownerToken, "GET", "/identity/offboarding/" + childCase.get("id").asText(), null, 404);

    // 3) 连接归还后不串租户：直连表按当前租户设置过滤，池里只有一条连接
    var parentRows =
        tx()
            .run(
                parentId, () -> sql.fetch("select id from offboarding_case").getValues("id", UUID.class));
    var childRows =
        tx()
            .run(
                childId, () -> sql.fetch("select id from offboarding_case").getValues("id", UUID.class));
    assertThat(parentRows).isNotEmpty().doesNotContainAnyElementsOf(childRows);
    assertThat(childRows).isNotEmpty();
    // 没有 app.current_tenant 时一条都读不到：租户判定不在连接上残留
    assertThat(sql.fetch("select id from offboarding_case")).isEmpty();

    // 运行时角色绕不过 FORCE RLS（三张表逐张查）
    var runtime = sql.fetchOne("select rolsuper,rolbypassrls from pg_roles where rolname=current_user");
    assertThat(runtime.get("rolsuper", Boolean.class)).isFalse();
    assertThat(runtime.get("rolbypassrls", Boolean.class)).isFalse();
    assertThat(
            sql.fetch(
                "select relname from pg_class join pg_namespace n on n.oid=relnamespace"
                    + " where n.nspname='public'"
                    + " and relname in ('offboarding_case','offboarding_responsibility_item','offboarding_reclaim_item')"
                    + " and (not relrowsecurity or not relforcerowsecurity)"))
        .as("三张表都必须 ENABLE + FORCE RLS")
        .isEmpty();
  }

  private JsonNode caseByReasonIn(JsonNode cases, String reason) {
    for (JsonNode c : cases) if (reason.equals(c.get("reason").asText())) return c;
    return null;
  }
}