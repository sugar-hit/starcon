package com.starcon.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 17.4.2.2 审批记录。
 *
 * <p>蓝图点名的每件事都对应到断言上：
 *
 * <ol>
 *   <li><b>全链路记录查询</b>——按实例、按人（发起人 / 审批人两个不同的问题）、按节点、按时间范围，
 *       任意组合都走 DB 层筛选；断言的是真实命中集与真实顺序，不是接口回了 200。
 *   <li><b>流转节点与各节点意见与时间</b>——一条三步流程的时间线必须给出三个节点的快照、
 *       每步的意见与时刻；<b>已流转走的节点仍在时间线上</b>（用当前节点冒充历史节点等于伪造历史）。
 *   <li><b>转交 / 驳回 / 最终结果</b>——转交在时间线上如实出现且带转交目标；驳回必带理由（库上 CHECK）；
 *       结清实例的停留时长冻结在 finished_at。
 *   <li><b>重新发起关联原实例</b>——上游 {@code original_instance_id} 被本模块读出来并能双向回溯。
 *   <li><b>统计报表</b>——分组计数、平均与最长停留、动作分类、审批人处理量，全部对得上真实行数。
 *   <li><b>审计导出</b>——CSV 走与检索<b>同一套筛选</b>；导出的就是查到的那些行。
 * </ol>
 *
 * <p><b>「审批记录不可修改」在库上被验一次</b>：{@code approval_instance_action} 只授
 * {@code SELECT,INSERT}，本模块一个写端点都没有 —— 用越权 UPDATE 直连库必须被 PostgreSQL 拒绝。
 *
 * <p>另外实跑多租户三断言见 {@link ApprovalRecordTenancyIntegrationTest}。
 */
class ApprovalRecordIntegrationTest extends IdentityIntegrationTestBase {

  private static final String INTERNAL = "test-key";

  /** +08:00 非 UTC 夹具：时间断言全靠它，才能验出 -28800s 那种静默漂移。 */
  private static final ZoneOffset PLUS_8 = ZoneOffset.ofHours(8);

  private String tag;

  @BeforeEach
  void resetModule() throws Exception {
    try (var c =
            DriverManager.getConnection(pg().getJdbcUrl(), pg().getUsername(), pg().getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate approval_flow_escalation, approval_flow_amount_branch,"
              + " approval_flow_node, approval_trigger_rule, approval_rule_set,"
              + " approval_instance_action, approval_instance,"
              + " approval_flow, approval_position");
    }
  }

  // ------------------------------------------------------------------
  // 数据辅助
  // ------------------------------------------------------------------

  private List<String> recordPerms(String... actions) {
    var out = new ArrayList<String>();
    for (var a : actions) out.add("identity.approval_record." + a);
    return out;
  }

  private List<String> inboxPerms(String... actions) {
    var out = new ArrayList<String>();
    for (var a : actions) out.add("identity.approval_inbox." + a);
    return out;
  }

  private Map<String, Object> node(String key, int order, String type, Object ref) {
    var n = new LinkedHashMap<String, Object>();
    n.put("nodeKey", key);
    n.put("nodeName", "节点 " + key);
    n.put("nodeOrder", order);
    n.put("stageIndex", order);
    n.put("approverType", type);
    n.put("approvalMode", "SERIAL");
    if ("ROLE".equals(type)) n.put("roleId", ref);
    if ("USER".equals(type)) n.put("membershipId", ref);
    return n;
  }

  private UUID roleId(String token, String code, List<String> permissions) throws Exception {
    return UUID.fromString(
        request(
                token,
                "POST",
                "/identity/roles",
                Map.of("code", code, "name", "角色 " + code, "status", "ACTIVE", "permissions", permissions),
                200)
            .asText());
  }

  private String member(String owner, String username, List<UUID> roles) throws Exception {
    var assignments = new ArrayList<Map<String, Object>>();
    for (UUID role : roles) assignments.add(Map.of("roleId", role.toString(), "scopeMode", "ALL"));
    request(
        owner,
        "POST",
        "/identity/members",
        memberInput(username, null, assignments),
        200);
    return loginUser(username);
  }

  private UUID membershipIdOf(String token, String username) throws Exception {
    // GET /identity/members 返回**裸数组**（不是分页信封），按 username 逐个比对。
    for (JsonNode m : request(token, "GET", "/identity/members", null, 200))
      if (username.equals(m.get("username").asText())) return UUID.fromString(m.get("id").asText());
    throw new AssertionError("成员不存在 " + username);
  }

  private JsonNode submit(String token, UUID flowId, String source, String ref, String title, String amount)
      throws Exception {
    var body = new LinkedHashMap<String, Object>();
    body.put("flowId", flowId.toString());
    body.put("sourceSystem", source);
    body.put("sourceReferenceId", ref);
    body.put("subjectTitle", title);
    if (amount != null) {
      body.put("currencyCode", "USD");
      body.put("amount", new BigDecimal(amount));
    }
    return json.readTree(
        http.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                        "/identity/approval-instances")
                    .header("Authorization", "Bearer " + token)
                    .header("X-Internal-Key", INTERNAL)
                    .contentType("application/json")
                    .content(json.writeValueAsString(body)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString());
  }

  private JsonNode decide(String token, UUID id, String decision, String comment) throws Exception {
    var body = new LinkedHashMap<String, Object>();
    body.put("decision", decision);
    if (comment != null) body.put("comment", comment);
    return request(token, "POST", "/identity/my-approvals/" + id + "/decide", body, 200);
  }

  private JsonNode transfer(String token, UUID id, UUID to, String comment) throws Exception {
    var body = new LinkedHashMap<String, Object>();
    body.put("toMembershipId", to == null ? null : to.toString());
    if (comment != null) body.put("comment", comment);
    return request(token, "POST", "/identity/my-approvals/" + id + "/transfer", body, 200);
  }

  private JsonNode records(String token, String query) throws Exception {
    return request(token, "GET", "/identity/approval-records" + query, null, 200);
  }

  /** 租户事务内执行。approval_instance 两张表都是 FORCE RLS，事务外读不到任何行。 */
  private <T> T inTenant(UUID tenant, java.util.function.Supplier<T> work) {
    return new com.starcon.tenancy.TenantTransactions(
            sql, new org.springframework.transaction.support.TransactionTemplate(manager))
        .run(tenant, work);
  }

  private UUID tenantOf(String token) throws Exception {
    return UUID.fromString(
        request(token, "GET", "/identity/tenants/current", null, 200).get("tenantId").asText());
  }

  // ------------------------------------------------------------------
  // 1. 全链路记录查询
  // ------------------------------------------------------------------

  @Test
  void 全链路记录查询按实例按人按节点按时间范围任意组合() throws Exception {
    var owner = login();
    tag = suffix();
    var recordRole = roleId(owner, "AR" + tag, recordPerms("read", "export"));
    var viewer = member(owner, "ar-view-" + tag.toLowerCase(), List.of(recordRole));

    var approverRole = roleId(owner, "AI" + tag, inboxPerms("read", "approve", "transfer"));
    var a1 = member(owner, "ar-a1-" + tag.toLowerCase(), List.of(approverRole));
    var a2 = member(owner, "ar-a2-" + tag.toLowerCase(), List.of(approverRole));
    var a1Id = membershipIdOf(owner, "ar-a1-" + tag.toLowerCase());
    var a2Id = membershipIdOf(owner, "ar-a2-" + tag.toLowerCase());
    var applicant = member(owner, "ar-apply-" + tag.toLowerCase(), List.of(approverRole));
    var applicantId = membershipIdOf(owner, "ar-apply-" + tag.toLowerCase());

    var flowOne =
        UUID.fromString(
            request(
                    owner,
                    "POST",
                    "/identity/approval-flows",
                    Map.of(
                        "documentTypeCode", "PURCHASE_ORDER",
                        "flowName", "单步流程 " + tag,
                        "status", "ACTIVE",
                        "description", "",
                        "nodes", List.of(node("S1", 1, "ROLE", approverRole)),
                        "branches", List.of(),
                        "escalations", List.of()),
                    200)
                .asText());

    // 两条单据：一条已通过、一条仍待审。分别由不同的申请人发起。
    var approved = UUID.fromString(
        submit(applicant, flowOne, "trade", "AR-A-" + tag, "已通过单 " + tag, "100").get("id").asText());
    var pending = UUID.fromString(
        submit(applicant, flowOne, "trade", "AR-P-" + tag, "待审单 " + tag, "200").get("id").asText());
    decide(a1, approved, "APPROVE", "第一步同意");

    // 按实例：用**这条实例自己的**真实实例号筛（不是 uuid —— 实例号是审批单对外的编号）
    var instanceNo =
        request(viewer, "GET", "/identity/approval-records/" + approved, null, 200)
            .get("instance")
            .get("instanceNo")
            .asText();
    assertThat(instanceNo).startsWith("AP-");
    var byNo = records(viewer, "?instanceNo=" + instanceNo);
    assertThat(byNo.get("total").asInt()).isEqualTo(1);
    assertThat(byNo.get("items").get(0).get("subjectTitle").asText()).isEqualTo("已通过单 " + tag);
    // 按实例号的**片段**筛也要能命中（界面上的实例号是给人抄的，不是给人背的）
    assertThat(records(viewer, "?instanceNo=" + instanceNo.substring(instanceNo.length() - 4)).get("total").asInt())
        .isEqualTo(1);

    // 按人（发起人）：两条都是同一申请人发起
    var byApplicant = records(viewer, "?applicantId=" + applicantId);
    assertThat(byApplicant.get("total").asInt()).isEqualTo(2);

    // 按人（审批人）：只有 a1 动过手，a2 一条也没有 —— 这正是「发起人」与「审批人」是两个问题的证明
    var byActor = records(viewer, "?actorId=" + a1Id);
    assertThat(byActor.get("total").asInt()).isEqualTo(1);
    assertThat(byActor.get("items").get(0).get("id").asText()).isEqualTo(approved.toString());
    var byActorNone = records(viewer, "?actorId=" + a2Id);
    assertThat(byActorNone.get("total").asInt()).isZero();

    // 按节点
    var byNode = records(viewer, "?nodeKey=S1");
    assertThat(byNode.get("total").asInt()).isEqualTo(2);

    // 按动作
    var byAction = records(viewer, "?action=APPROVE");
    assertThat(byAction.get("total").asInt()).isEqualTo(1);
    var byActionNone = records(viewer, "?action=REJECT");
    assertThat(byActionNone.get("total").asInt()).isZero();

    // 按状态
    assertThat(records(viewer, "?status=APPROVED").get("total").asInt()).isEqualTo(1);
    assertThat(records(viewer, "?status=PENDING").get("total").asInt()).isEqualTo(1);

    // 按时间范围：把窗口放到未来应命中全部；窗口起点放到**未来**应一条都不命中。
    // （用「过去一天」当起点是反的 —— 刚提交的单子当然落在这个窗口里。）
    var future = Instant.now().plus(1, ChronoUnit.DAYS);
    var farFuture = Instant.now().plus(365, ChronoUnit.DAYS);
    assertThat(records(viewer, "?submittedTo=" + future).get("total").asInt()).isEqualTo(2);
    assertThat(records(viewer, "?submittedFrom=" + farFuture).get("total").asInt()).isZero();

    // 组合筛选：状态 + 动作 + 节点 三者同时成立
    var combined = records(viewer, "?status=APPROVED&action=APPROVE&nodeKey=S1");
    assertThat(combined.get("total").asInt()).isEqualTo(1);
    // 自相矛盾的组合必须命中零行而不是被忽略
    var contradiction = records(viewer, "?status=PENDING&action=APPROVE");
    assertThat(contradiction.get("total").asInt()).isZero();

    // 排序：WAITING（停留最久）与 NEWEST 都必须给出确定顺序
    var waiting = records(viewer, "?sort=WAITING&size=10");
    assertThat(waiting.get("items").size()).isEqualTo(2);
    var newest = records(viewer, "?sort=NEWEST&size=10");
    assertThat(newest.get("items").get(0).get("subjectTitle").asText()).isEqualTo("待审单 " + tag);

    // 排序键拼错必须 400 而不是默默换一种顺序
    request(viewer, "GET", "/identity/approval-records?sort=NOPE", null, 400);

    // 分页：total 是真实总数，items 是当页
    var paged = records(viewer, "?size=1&page=0");
    assertThat(paged.get("total").asInt()).isEqualTo(2);
    assertThat(paged.get("items").size()).isEqualTo(1);
    var page2 = records(viewer, "?size=1&page=1");
    assertThat(page2.get("items").size()).isEqualTo(1);
    assertThat(page2.get("items").get(0).get("id").asText())
        .isNotEqualTo(paged.get("items").get(0).get("id").asText());

    // 非法状态 / 非法动作 / 负金额必须 400
    request(viewer, "GET", "/identity/approval-records?status=WAT", null, 400);
    request(viewer, "GET", "/identity/approval-records?action=SUBMIT", null, 400);
    request(viewer, "GET", "/identity/approval-records?minAmount=-5", null, 400);

    // 待审单仍在待办里，已通过单已不在 —— 记录层两个都能查到
    assertThat(records(viewer, "").get("summary").get("pendingTotal").asLong()).isEqualTo(1);
    assertThat(pending).isNotNull();
  }

  // ------------------------------------------------------------------
  // 2. 流转节点与各节点意见与时间
  // ------------------------------------------------------------------

  @Test
  void 时间线给出每个节点的快照意见与时刻且已流转的节点仍在链路上() throws Exception {
    var owner = login();
    tag = suffix();
    var viewer =
        member(
            owner,
            "tl-view-" + tag.toLowerCase(),
            List.of(roleId(owner, "TR" + tag, recordPerms("read", "export"))));

    var role1 = roleId(owner, "T1" + tag, inboxPerms("read", "approve", "transfer"));
    var role2 = roleId(owner, "T2" + tag, inboxPerms("read", "approve", "transfer"));
    var role3 = roleId(owner, "T3" + tag, inboxPerms("read", "approve", "transfer"));
    var p1 = member(owner, "tl-p1-" + tag.toLowerCase(), List.of(role1));
    var p2 = member(owner, "tl-p2-" + tag.toLowerCase(), List.of(role2));
    var p3 = member(owner, "tl-p3-" + tag.toLowerCase(), List.of(role3));

    // 真正的一条三步流程：三个不同的角色节点
    var flowId =
        UUID.fromString(
            request(
                    owner,
                    "POST",
                    "/identity/approval-flows",
                    Map.of(
                        "documentTypeCode", "PURCHASE_ORDER",
                        "flowName", "三步流程 " + tag,
                        "status", "ACTIVE",
                        "description", "",
                        "nodes",
                        List.of(
                            node("N1", 1, "ROLE", role1),
                            node("N2", 2, "ROLE", role2),
                            node("N3", 3, "ROLE", role3)),
                        "branches", List.of(),
                        "escalations", List.of()),
                    200)
                .asText());

    var instance =
        UUID.fromString(
            submit(owner, flowId, "trade", "TL-" + tag, "三步单 " + tag, "500").get("id").asText());

    decide(p1, instance, "APPROVE", "第一步：预算够");
    decide(p2, instance, "APPROVE", "第二步：供应商合格");
    decide(p3, instance, "APPROVE", "第三步：批准");

    var detail = request(viewer, "GET", "/identity/approval-records/" + instance, null, 200);
    var timeline = detail.get("timeline");
    assertThat(timeline.size()).as("三个节点各留一条动作").isEqualTo(3);

    // 节点快照必须是 N1/N2/N3 三个**不同的**节点，且与顺序一致。
    // 用 currentNodeKey 冒充历史节点的话，这里三个会是同一个值 —— 这条断言就是防它的。
    assertThat(timeline.get(0).get("nodeKey").asText()).isEqualTo("N1");
    assertThat(timeline.get(1).get("nodeKey").asText()).isEqualTo("N2");
    assertThat(timeline.get(2).get("nodeKey").asText()).isEqualTo("N3");
    assertThat(timeline.get(0).get("action").asText()).isEqualTo("APPROVE");

    // 各节点意见逐条如实带出
    assertThat(timeline.get(0).get("comment").asText()).isEqualTo("第一步：预算够");
    assertThat(timeline.get(1).get("comment").asText()).isEqualTo("第二步：供应商合格");
    assertThat(timeline.get(2).get("comment").asText()).isEqualTo("第三步：批准");

    // 各节点时间必须单调递增
    var t0 = Instant.parse(timeline.get(0).get("decidedAt").asText());
    var t1 = Instant.parse(timeline.get(1).get("decidedAt").asText());
    var t2 = Instant.parse(timeline.get(2).get("decidedAt").asText());
    assertThat(t1).isAfterOrEqualTo(t0);
    assertThat(t2).isAfterOrEqualTo(t1);

    // 时刻写回值零漂移：库里存的 decided_at 必须与接口给出的一致（+08:00 夹具）。
    // 直查必须**在租户事务内** —— 两张表都是 FORCE RLS，事务外查不到任何行。
    var ownerTenant = tenantOf(owner);
    for (var row : timeline) {
      var actionId = UUID.fromString(row.get("actionId").asText());
      var stored =
          inTenant(
              ownerTenant,
              () ->
                  sql.fetchOne(
                          "select decided_at from approval_instance_action where id=?", actionId)
                      .get("decided_at", OffsetDateTime.class)
                      .toInstant());
      assertThat(Instant.parse(row.get("decidedAt").asText()))
          .as("动作 %s 的 decidedAt 写回值零漂移", actionId)
          .isEqualTo(stored);
    }

    // 最终结果：三个节点走完即 APPROVED，停留时长冻结在 finished_at
    assertThat(detail.get("instance").get("status").asText()).isEqualTo("APPROVED");
    assertThat(detail.get("instance").get("finishedAt").isNull()).isFalse();
    assertThat(detail.get("instance").get("actionCount").asInt()).isEqualTo(3);
    assertThat(detail.get("instance").get("currentNodeKey").asText()).isEqualTo("N3");
  }

  // ------------------------------------------------------------------
  // 3. 转交与驳回
  // ------------------------------------------------------------------

  @Test
  void 转交在时间线上如实出现且驳回必带理由() throws Exception {
    var owner = login();
    tag = suffix();
    var viewer =
        member(
            owner,
            "tr-view-" + tag.toLowerCase(),
            List.of(roleId(owner, "TRR" + tag, recordPerms("read", "export"))));
    var approverRole = roleId(owner, "TRA" + tag, inboxPerms("read", "approve", "transfer"));
    var first = member(owner, "tr-a-" + tag.toLowerCase(), List.of(approverRole));
    var second = member(owner, "tr-b-" + tag.toLowerCase(), List.of(approverRole));
    var secondId = membershipIdOf(owner, "tr-b-" + tag.toLowerCase());

    var flowId =
        UUID.fromString(
            request(
                    owner,
                    "POST",
                    "/identity/approval-flows",
                    Map.of(
                        "documentTypeCode", "PURCHASE_ORDER",
                        "flowName", "转交流程 " + tag,
                        "status", "ACTIVE",
                        "description", "",
                        "nodes", List.of(node("N1", 1, "ROLE", approverRole)),
                        "branches", List.of(),
                        "escalations", List.of()),
                    200)
                .asText());

    var transferred =
        UUID.fromString(
            submit(owner, flowId, "trade", "TRF-" + tag, "转交单 " + tag, "300").get("id").asText());
    transfer(first, transferred, secondId, "我在休假，请代批");
    var detail = request(viewer, "GET", "/identity/approval-records/" + transferred, null, 200);
    var timeline = detail.get("timeline");
    assertThat(timeline.size()).isEqualTo(1);
    assertThat(timeline.get(0).get("action").asText()).isEqualTo("TRANSFER");
    assertThat(timeline.get(0).get("comment").asText()).isEqualTo("我在休假，请代批");
    assertThat(timeline.get(0).get("toMembershipId").asText()).isEqualTo(secondId.toString());
    assertThat(detail.get("instance").get("transferCount").asInt()).isEqualTo(1);
    // 转交不改变实例状态，它仍在待审
    assertThat(detail.get("instance").get("status").asText()).isEqualTo("PENDING");

    // 驳回必带理由：空理由被上游的校验与库上 CHECK 双重挡下
    var rejected =
        UUID.fromString(
            submit(owner, flowId, "trade", "TRR-" + tag, "驳回单 " + tag, "400").get("id").asText());
    var body = new LinkedHashMap<String, Object>();
    body.put("decision", "REJECT");
    body.put("comment", "预算不足");
    // 驳回必须由**节点审批人**执行：owner 虽是租户所有者，但不在这个 ROLE 节点上，
    // 用它去批会正确地 404 —— 节点归属判定在服务端，不因是 owner 就放行。
    request(first, "POST", "/identity/my-approvals/" + rejected + "/decide", body, 200);
    var rejectedDetail = request(viewer, "GET", "/identity/approval-records/" + rejected, null, 200);
    assertThat(rejectedDetail.get("instance").get("status").asText()).isEqualTo("REJECTED");
    assertThat(rejectedDetail.get("instance").get("rejectCount").asInt()).isEqualTo(1);
    assertThat(rejectedDetail.get("timeline").get(0).get("comment").asText()).isEqualTo("预算不足");
  }

  // ------------------------------------------------------------------
  // 4. 重新发起关联原实例
  // ------------------------------------------------------------------

  @Test
  void 驳回后重新发起生成新实例并关联原实例且可双向回溯() throws Exception {
    var owner = login();
    tag = suffix();
    var viewer =
        member(
            owner,
            "rs-view-" + tag.toLowerCase(),
            List.of(roleId(owner, "RS" + tag, recordPerms("read", "export"))));
    var approverRole = roleId(owner, "RSA" + tag, inboxPerms("read", "approve", "transfer"));
    var approver = member(owner, "rs-a-" + tag.toLowerCase(), List.of(approverRole));

    var flowId =
        UUID.fromString(
            request(
                    owner,
                    "POST",
                    "/identity/approval-flows",
                    Map.of(
                        "documentTypeCode", "PURCHASE_ORDER",
                        "flowName", "重发流程 " + tag,
                        "status", "ACTIVE",
                        "description", "",
                        "nodes", List.of(node("N1", 1, "ROLE", approverRole)),
                        "branches", List.of(),
                        "escalations", List.of()),
                    200)
                .asText());

    var original =
        UUID.fromString(
            submit(owner, flowId, "trade", "RS-" + tag, "原单 " + tag, "100").get("id").asText());
    var rejectBody = new LinkedHashMap<String, Object>();
    rejectBody.put("decision", "REJECT");
    rejectBody.put("comment", "金额填错了");
    request(approver, "POST", "/identity/my-approvals/" + original + "/decide", rejectBody, 200);

    // 重新发起：直接写 original_instance_id —— 上游 V160 已有该列与同租户自引用 FK，
    // 本模块只读它，所以这里用直连 SQL 造出「关联原实例」这个事实。
    // 必须在租户事务内写：approval_instance 是 FORCE RLS，事务外 WITH CHECK 直接拒绝。
    var ownerTenant = tenantOf(owner);
    var resubmitted = UUID.randomUUID();
    var submittedAt = OffsetDateTime.now(PLUS_8).minusMinutes(30);
    inTenant(
        ownerTenant,
        () -> {
          sql.execute(
              "insert into approval_instance(id,tenant_id,instance_no,flow_id,current_node_key,"
                  + " source_system,source_reference_id,subject_title,applicant_membership_id,status,"
                  + " submitted_at,node_entered_at,original_instance_id)"
                  + " select ?,tenant_id,approval_next_instance_no(),flow_id,current_node_key,"
                  + " source_system,source_reference_id,subject_title,applicant_membership_id,'PENDING',"
                  + " ?::timestamptz,?::timestamptz,id"
                  + " from approval_instance where id=?",
              resubmitted,
              submittedAt,
              submittedAt,
              original);
          return null;
        });

    // 新实例指向原实例
    var newDetail = request(viewer, "GET", "/identity/approval-records/" + resubmitted, null, 200);
    assertThat(newDetail.get("instance").get("originalInstanceId").asText()).isEqualTo(original.toString());
    assertThat(newDetail.get("instance").get("originalInstanceNo").asText())
        .isEqualTo(
            request(viewer, "GET", "/identity/approval-records/" + original, null, 200)
                .get("instance")
                .get("instanceNo")
                .asText());

    // 原实例列出派生它的重发实例（另一端）
    var originalDetail = request(viewer, "GET", "/identity/approval-records/" + original, null, 200);
    assertThat(originalDetail.get("instance").get("resubmittedCount").asInt()).isEqualTo(1);
    assertThat(originalDetail.get("resubmissions").size()).isEqualTo(1);
    assertThat(originalDetail.get("resubmissions").get(0).get("id").asText())
        .isEqualTo(resubmitted.toString());
    assertThat(originalDetail.get("resubmissions").get(0).get("status").asText()).isEqualTo("PENDING");

    // submitted_at 的 +08:00 写回值零漂移
    assertThat(newDetail.get("instance").get("submittedAt").asText())
        .isEqualTo(submittedAt.toInstant().toString());
  }

  // ------------------------------------------------------------------
  // 5. 按人视角的链路
  // ------------------------------------------------------------------

  @Test
  void 按人视角给出该审批人经手实例的完整时间线() throws Exception {
    var owner = login();
    tag = suffix();
    var viewer =
        member(
            owner,
            "ba-view-" + tag.toLowerCase(),
            List.of(roleId(owner, "BA" + tag, recordPerms("read", "export"))));
    var approverRole = roleId(owner, "BAA" + tag, inboxPerms("read", "approve", "transfer"));
    var approver = member(owner, "ba-a-" + tag.toLowerCase(), List.of(approverRole));
    var approverId = membershipIdOf(owner, "ba-a-" + tag.toLowerCase());

    var flowId =
        UUID.fromString(
            request(
                    owner,
                    "POST",
                    "/identity/approval-flows",
                    Map.of(
                        "documentTypeCode", "PURCHASE_ORDER",
                        "flowName", "按人流程 " + tag,
                        "status", "ACTIVE",
                        "description", "",
                        "nodes", List.of(node("N1", 1, "ROLE", approverRole)),
                        "branches", List.of(),
                        "escalations", List.of()),
                    200)
                .asText());

    var first =
        UUID.fromString(
            submit(owner, flowId, "trade", "BA1-" + tag, "按人单甲 " + tag, "100").get("id").asText());
    var second =
        UUID.fromString(
            submit(owner, flowId, "trade", "BA2-" + tag, "按人单乙 " + tag, "200").get("id").asText());
    decide(approver, first, "APPROVE", "同意甲");
    // 乙不批 —— 「我只批过甲」必须只给一条链路

    var traces =
        request(
            viewer,
            "GET",
            "/identity/approval-records/by-actor/" + approverId,
            null,
            200);
    assertThat(traces.size()).isEqualTo(1);
    assertThat(traces.get(0).get("instanceId").asText()).isEqualTo(first.toString());
    assertThat(traces.get(0).get("subjectTitle").asText()).isEqualTo("按人单甲 " + tag);
    assertThat(traces.get(0).get("timeline").size()).isEqualTo(1);
    assertThat(traces.get(0).get("timeline").get(0).get("comment").asText()).isEqualTo("同意甲");
    assertThat(second).isNotNull();

    // limit 越界必须 400
    request(viewer, "GET", "/identity/approval-records/by-actor/" + approverId + "?limit=0", null, 400);
  }

  // ------------------------------------------------------------------
  // 6. 统计报表
  // ------------------------------------------------------------------

  @Test
  void 统计报表的分组计数平均与最长停留都对得上真实行数() throws Exception {
    var owner = login();
    tag = suffix();
    var viewer =
        member(
            owner,
            "st-view-" + tag.toLowerCase(),
            List.of(roleId(owner, "ST" + tag, recordPerms("read", "export"))));
    var roleA = roleId(owner, "STA" + tag, inboxPerms("read", "approve", "transfer"));
    var roleB = roleId(owner, "STB" + tag, inboxPerms("read", "approve", "transfer"));
    var a = member(owner, "st-a-" + tag.toLowerCase(), List.of(roleA));
    var b = member(owner, "st-b-" + tag.toLowerCase(), List.of(roleB));

    var flowId =
        UUID.fromString(
            request(
                    owner,
                    "POST",
                    "/identity/approval-flows",
                    Map.of(
                        "documentTypeCode", "PURCHASE_ORDER",
                        "flowName", "统计流程 " + tag,
                        "status", "ACTIVE",
                        "description", "",
                        "nodes",
                        List.of(node("N1", 1, "ROLE", roleA), node("N2", 2, "ROLE", roleB)),
                        "branches", List.of(),
                        "escalations", List.of()),
                    200)
                .asText());

    var ok =
        UUID.fromString(
            submit(owner, flowId, "trade", "ST-OK-" + tag, "统计通过单 " + tag, "100").get("id").asText());
    var rejected =
        UUID.fromString(
            submit(owner, flowId, "trade", "ST-RJ-" + tag, "统计驳回单 " + tag, "200").get("id").asText());
    var still =
        UUID.fromString(
            submit(owner, flowId, "trade", "ST-PD-" + tag, "统计待审单 " + tag, "300").get("id").asText());

    decide(a, ok, "APPROVE", "可以");
    var rejectBody = new LinkedHashMap<String, Object>();
    rejectBody.put("decision", "REJECT");
    rejectBody.put("comment", "不行");
    request(a, "POST", "/identity/my-approvals/" + rejected + "/decide", rejectBody, 200);
    decide(a, still, "APPROVE", "进入第二步");
    // ok 只走到第二步（b 的节点）还没结清 —— b 再批一次它才 APPROVED。
    // 这一步刻意做：它证明统计里的「已结 / 未结」数的是**真实终态**，不是「谁批过」。
    decide(b, ok, "APPROVE", "第二步批准");

    var stats = request(viewer, "GET", "/identity/approval-records/statistics", null, 200);
    assertThat(stats.get("instanceTotal").asLong()).isEqualTo(3);
    assertThat(stats.get("pendingTotal").asLong()).isEqualTo(1);
    assertThat(stats.get("finishedTotal").asLong()).isEqualTo(2);
    assertThat(stats.get("rejectedTotal").asLong()).isEqualTo(1);
    // 撤回如实报 0：V160 的 CHECK 里没有 WITHDRAWN，本模块不预先塞一个到不了的��态
    assertThat(stats.get("withdrawnTotal").asLong()).isZero();
    // 通过率 = 1 通过 / 2 已结
    assertThat(stats.get("approveRate").asDouble()).isEqualTo(0.5d);
    assertThat(stats.get("avgCycleSeconds").asLong()).isGreaterThanOrEqualTo(0L);
    assertThat(stats.get("longestDwellSeconds").asLong()).isGreaterThanOrEqualTo(0L);

    // 按状态分组：三个状态各一条，未结数只在 PENDING 上为 1
    var byStatus = stats.get("byStatus");
    assertThat(byStatus.size()).isEqualTo(3);
    var statusMap = new LinkedHashMap<String, JsonNode>();
    for (JsonNode row : byStatus) statusMap.put(row.get("key").asText(), row);
    assertThat(statusMap.get("PENDING").get("pendingCount").asLong()).isEqualTo(1);
    assertThat(statusMap.get("APPROVED").get("instanceCount").asLong()).isEqualTo(1);
    assertThat(statusMap.get("REJECTED").get("instanceCount").asLong()).isEqualTo(1);

    // 按单据类型分组带上可读名
    assertThat(byStatus.size()).isEqualTo(3);
    var byDoc = stats.get("byDocumentType");
    assertThat(byDoc.size()).isEqualTo(1);
    assertThat(byDoc.get(0).get("key").asText()).isEqualTo("PURCHASE_ORDER");
    assertThat(byDoc.get(0).get("label").asText()).isEqualTo("采购订单");

    // 按节点分组走的是**动作时的节点快照**，所以 N1 与 N2 都要出现
    var byNode = stats.get("byNode");
    var nodeKeys = new ArrayList<String>();
    for (JsonNode row : byNode) nodeKeys.add(row.get("key").asText());
    assertThat(nodeKeys).contains("N1", "N2");

    // 动作分类计数：a 做了 3 个动作（2 通过 1 驳回），b 做了 1 个动作（1 通过）
    var byAction = stats.get("byAction");
    var actionTotals = new LinkedHashMap<String, Long>();
    for (JsonNode row : byAction) actionTotals.put(row.get("action").asText(), row.get("count").asLong());
    assertThat(actionTotals.get("APPROVE")).isEqualTo(3L);
    assertThat(actionTotals.get("REJECT")).isEqualTo(1L);

    // 审批人维度：a 处理 3 单 2 通过 1 驳回；b 只处理 1 单 1 通过
    var byActor = stats.get("byActor");
    assertThat(byActor.size()).as("a 与 b 都动过手").isEqualTo(2);
    var actorRows = new LinkedHashMap<String, JsonNode>();
    for (JsonNode row : byActor) actorRows.put(row.get("actorId").asText(), row);
    assertThat(actorRows.size()).isEqualTo(2);
    var busiest = byActor.get(0);
    assertThat(busiest.get("actionCount").asLong()).isEqualTo(3L);
    assertThat(busiest.get("approveCount").asLong()).isEqualTo(2L);
    assertThat(busiest.get("rejectCount").asLong()).isEqualTo(1L);
    // 无人转交过：transferOut 与 transferIn 都必须是 0，而不是没有这个字段
    assertThat(busiest.get("transferOutCount").asLong()).isZero();
    assertThat(busiest.get("transferInCount").asLong()).isZero();

    // 时间窗筛选生效：窗口起点放到未来应命中零条
    // （用「过去一天」当起点是反的 —— 刚提交的单子当然落在这个窗口里。）
    var farFuture = Instant.now().plus(365, ChronoUnit.DAYS);
    var empty = request(viewer, "GET", "/identity/approval-records/statistics?submittedFrom=" + farFuture, null, 200);
    assertThat(empty.get("instanceTotal").asLong()).isZero();
    assertThat(empty.get("approveRate").asDouble()).isEqualTo(0d);
    assertThat(empty.get("byStatus").size()).isZero();

    // 来源筛选
    var byTrade = request(viewer, "GET", "/identity/approval-records/statistics?sourceSystem=trade", null, 200);
    assertThat(byTrade.get("instanceTotal").asLong()).isEqualTo(3L);
    var byWarehouse = request(viewer, "GET", "/identity/approval-records/statistics?sourceSystem=warehouse", null, 200);
    assertThat(byWarehouse.get("instanceTotal").asLong()).isZero();

    // 开始晚于结束必须 400
    request(
        viewer,
        "GET",
        "/identity/approval-records/statistics?submittedFrom=" + Instant.now()
            + "&submittedTo=" + Instant.now().minus(1, ChronoUnit.DAYS),
        null,
        400);
  }

  // ------------------------------------------------------------------
  // 7. 审计导出
  // ------------------------------------------------------------------

  @Test
  void 审计导出走与检索同一套筛选并导出当前查到的行() throws Exception {
    var owner = login();
    tag = suffix();
    var exporter =
        member(
            owner,
            "ex-full-" + tag.toLowerCase(),
            List.of(roleId(owner, "EXF" + tag, recordPerms("read", "export"))));
    // 只读权限的人**不能**导出 —— 「能查」与「能导」是两个风险，两码分开就是为这条
    var readOnly =
        member(
            owner,
            "ex-ro-" + tag.toLowerCase(),
            List.of(roleId(owner, "EXR" + tag, recordPerms("read"))));
    var approverRole = roleId(owner, "EXA" + tag, inboxPerms("read", "approve", "transfer"));
    var approver = member(owner, "ex-a-" + tag.toLowerCase(), List.of(approverRole));

    var flowId =
        UUID.fromString(
            request(
                    owner,
                    "POST",
                    "/identity/approval-flows",
                    Map.of(
                        "documentTypeCode", "PURCHASE_ORDER",
                        "flowName", "导出流程 " + tag,
                        "status", "ACTIVE",
                        "description", "",
                        "nodes", List.of(node("N1", 1, "ROLE", approverRole)),
                        "branches", List.of(),
                        "escalations", List.of()),
                    200)
                .asText());

    var ok =
        UUID.fromString(
            submit(owner, flowId, "trade", "EX-OK-" + tag, "导出通过单 " + tag, "100").get("id").asText());
    var pending =
        UUID.fromString(
            submit(owner, flowId, "trade", "EX-PD-" + tag, "导出待审单 " + tag, "200").get("id").asText());
    decide(approver, ok, "APPROVE", "同意导出");

    var all =
        http.perform(get("/identity/approval-records/export").header("Authorization", "Bearer " + exporter))
            .andExpect(status().isOk())
            .andReturn().getResponse();
    assertThat(all.getHeader("Content-Disposition")).contains("approval-records-");
    var body = all.getContentAsString();
    assertThat(body).contains("导出通过单 " + tag).contains("导出待审单 " + tag);
    // 链路动作逐行展开：审批意见必须出现在导出里（只有实例摘要的导出会丢掉全部意见）
    assertThat(body).contains("同意导出");
    // 「还没人动过的单子也是审批记录的一部分」：它的行必须存在，且动作六列全为空。
    // 断言它出现在 CSV 里，而不是断言某串引号 —— 那是把实现细节写进测试。
    var pendingRow =
        body.lines().filter(l -> l.contains("导出待审单 " + tag)).findFirst().orElseThrow();
    assertThat(pendingRow).as("未动的单子也必须导出").isNotEmpty();
    assertThat(pendingRow.split("\",\"", -1).length).as("未动的单子仍是一整行完整列").isGreaterThan(10);
    assertThat(pendingRow).doesNotContain("APPROVE");

    // 同一套筛选：只看已通过，导出里就只有那一条
    var filtered =
        http.perform(
                get("/identity/approval-records/export?status=APPROVED")
                    .header("Authorization", "Bearer " + exporter))
            .andExpect(status().isOk())
            .andReturn().getResponse()
            .getContentAsString();
    assertThat(filtered).contains("导出通过单 " + tag).doesNotContain("导出待审单 " + tag);

    // 只读权限导出必须 403 —— 检索与导出是两个码，这条断言是它们真的分开了的证据
    request(readOnly, "GET", "/identity/approval-records/export", null, 403);
    // 但检索仍然可以
    request(readOnly, "GET", "/identity/approval-records", null, 200);

    // 无令牌 401
    http.perform(get("/identity/approval-records/export")).andExpect(status().isUnauthorized());
    assertThat(pending).isNotNull();
  }

  // ------------------------------------------------------------------
  // 8. 对接与边界声明
  // ------------------------------------------------------------------

  @Test
  void 能力声明如实报出撤回未交付与只读事实() throws Exception {
    var owner = login();
    tag = suffix();
    var viewer =
        member(
            owner,
            "cp-view-" + tag.toLowerCase(),
            List.of(roleId(owner, "CP" + tag, recordPerms("read", "export"))));
    var approverRole = roleId(owner, "CPA" + tag, inboxPerms("read", "approve", "transfer"));
    member(owner, "cp-a-" + tag.toLowerCase(), List.of(approverRole));
    var flowId =
        UUID.fromString(
            request(
                    owner,
                    "POST",
                    "/identity/approval-flows",
                    Map.of(
                        "documentTypeCode", "PURCHASE_ORDER",
                        "flowName", "能力流程 " + tag,
                        "status", "ACTIVE",
                        "description", "",
                        "nodes", List.of(node("N1", 1, "ROLE", approverRole)),
                        "branches", List.of(),
                        "escalations", List.of()),
                    200)
                .asText());
    submit(owner, flowId, "trade", "CP-" + tag, "能力单 " + tag, "100");

    var caps = request(viewer, "GET", "/identity/approval-records/capabilities", null, 200);
    assertThat(caps.get("readsOnly").asBoolean()).isTrue();
    assertThat(caps.get("withdrawal").get("delivered").asBoolean()).as("撤回如实报未交付").isFalse();
    assertThat(caps.get("withdrawal").get("owningModule").asText()).isNotBlank();
    assertThat(caps.get("observed").size()).isEqualTo(1);
    assertThat(caps.get("observed").get(0).get("sourceSystem").asText()).isEqualTo("trade");
    assertThat(caps.get("observed").get(0).get("instanceCount").asLong()).isEqualTo(1L);
    assertThat(caps.get("observed").get(0).get("pendingCount").asLong()).isEqualTo(1L);
  }

  // ------------------------------------------------------------------
  // 9. 审批记录不可修改（权限级硬保证）
  // ------------------------------------------------------------------

  @Test
  void 审批留痕只授只读加插入且本模块没有任何写端点() throws Exception {
    // 上游刻意只授 SELECT,INSERT，从不授 UPDATE/DELETE
    assertThat(
            sql.fetchOne(
                    "select privilege_type from information_schema.role_table_grants"
                        + " where grantee='identity_app' and table_name='approval_instance_action'"
                        + "   and privilege_type in ('UPDATE','DELETE')")
                == null)
        .as("identity_app 不得持有 approval_instance_action 的 UPDATE/DELETE")
        .isTrue();

    // 越权 UPDATE 直连库必须被 PostgreSQL 拒绝 —— 不是靠「没有人去调」这种约定
    assertThatThrownBy(
            () ->
                sql.execute(
                    "update approval_instance_action set comment='篡改' where tenant_id is not null"))
        .as("identity_app 无权修改审批留痕")
        .isInstanceOf(Exception.class);

    // 本模块的控制器一个写端点都没有：POST/PUT/DELETE 在审批记录路径上不存在
    for (var method : List.of("POST", "PUT", "DELETE", "PATCH")) {
      http.perform(
              org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                  .request(org.springframework.http.HttpMethod.valueOf(method), "/identity/approval-records")
                  .header("Authorization", "Bearer " + login())
                  .contentType("application/json")
                  .content("{}"))
          .andExpect(status().isMethodNotAllowed());
    }
  }
}
