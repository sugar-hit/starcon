package com.starcon.identity;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.starcon.tenancy.TenantTransactions;
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
import org.jooq.Record;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 17.4.2.1 待我审批。
 *
 * <p>蓝图点名的每件事都对应到断言上：
 *
 * <ol>
 *   <li><b>单据类型 / 金额 / 申请人 / 停留时长 / 当前节点</b>——五个字段都从库里的真实列读出来，停留时长还断言了
 *       <b>写回值</b>（+08:00 夹具，与直查库零漂移）。
 *   <li><b>筛选与排序在 DB 层</b>——停留时长筛选命中 {@code node_entered_at}，默认排序就是「最久的排最前」，
 *       都由数据库完成，断言的是真实命中集与真实顺序。
 *   <li><b>批量审批与转交</b>——批量逐条回报、与请求<b>同序同长</b>、<b>一条失败不影响其余</b>。
 *       审批动作不是「要么全成要么全败」的业务，这是本模块最关键的一条。
 *   <li><b>转交</b>——转出后原审批人立刻看不见、被转交的人立刻能批；停留时长**不**因转交重置
 *       （否则转交就成了刷老化指标的开关）。
 *   <li><b>权限判定在服务端</b>——列表、详情、审批都按「节点审批人 + 角色分配」判定，非本人的待办一律 404。
 * </ol>
 *
 * <p>另外实跑多租户三断言（两租户互不可见 / 伪造 tenant header 无效 / 连接池归还后不串租户，池压到 1）、
 * 两张表 FORCE RLS 检查、{@code approval_instance_action} 只授 SELECT/INSERT（审批记录不可修改），
 * 以及 <b>CONDITIONAL 条件节点不被静默执行</b>（返回 409 而不是猜一个节点）。
 */
class ApprovalInboxIntegrationTest extends IdentityIntegrationTestBase {

  /** 内部提交入口的密钥。 */
  private static final String INTERNAL = "test-key";

  /** +08:00 非 UTC 夹具：停留时长全靠时间，用本地偏移才能验出 -28800s 那种静默漂移。 */
  private static final ZoneOffset PLUS_8 = ZoneOffset.ofHours(8);

  @BeforeEach
  void resetModule() throws Exception {
    try (var c =
            DriverManager.getConnection(pg().getJdbcUrl(), pg().getUsername(), pg().getPassword());
        var s = c.createStatement()) {
      // 本模块的表引用 approval_flow / approval_flow_node / membership，
      // 所以流程表必须与实例表同批清空（PostgreSQL 的 TRUNCATE 要求被引用表与引用表一起）。
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

  private String tag;

  private TenantTransactions tx() {
    return new TenantTransactions(
        sql, new org.springframework.transaction.support.TransactionTemplate(manager));
  }

  /** 审批节点的三个权限码。 */
  private List<String> perms(String... actions) {
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
    if ("POSITION".equals(type)) n.put("positionId", ref);
    return n;
  }

  private Map<String, Object> branch(String name, String cur, String min, String max, String target) {
    var b = new LinkedHashMap<String, Object>();
    b.put("branchName", name);
    b.put("currencyCode", cur);
    if (min != null) b.put("minAmount", new BigDecimal(min));
    if (max != null) b.put("maxAmount", new BigDecimal(max));
    b.put("targetNodeKey", target);
    return b;
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

  private UUID positionId(String token, String code) throws Exception {
    return UUID.fromString(
        request(
                token,
                "POST",
                "/identity/approval-flows/positions",
                Map.of("code", code, "name", "岗位 " + code, "status", "ACTIVE"),
                200)
            .asText());
  }

  /** 没有任何角色的普通成员。 */
  private UUID plainMember(String token, String username) throws Exception {
    return UUID.fromString(
        request(token, "POST", "/identity/members", memberInput(username, null, List.of()), 200).asText());
  }

  /**
   * 造一个**不是所有者**的审批人，返回他的登录 token。
   *
   * <p>待审批列表的本质是「每个人的工作台」，所以必须造得出非所有者审批人：只有这样才证明列表真的按成员关系过滤，
   * 而不是「谁登录谁看见全部」。
   */
  private String approver(String owner, String username, UUID role) throws Exception {
    var id =
        UUID.fromString(
            request(
                    owner,
                    "POST",
                    "/identity/members",
                    memberInput(username, null, List.of(Map.of("roleId", role.toString(), "scopeMode", "ALL"))),
                    200)
                .asText());
    assertThat(id).isNotNull();
    return loginUser(username);
  }

  /** 用内部入口提交一条待审批。 */
  private JsonNode submit(
      String token, UUID flowId, String source, String ref, String title, String cur, String amount)
      throws Exception {
    var result =
        internal(token, Map.of("flowId", flowId.toString(), "sourceSystem", source,
                "sourceReferenceId", ref, "subjectTitle", title), cur, amount, 200);
    return json.readTree(result);
  }

  private JsonNode inbox(String token, String query) throws Exception {
    return request(token, "GET", "/identity/my-approvals" + query, null, 200);
  }

  private JsonNode decide(String token, UUID id, String decision, String comment, int expected)
      throws Exception {
    var body = new LinkedHashMap<String, Object>();
    body.put("decision", decision);
    if (comment != null) body.put("comment", comment);
    return request(token, "POST", "/identity/my-approvals/" + id + "/decide", body, expected);
  }

  private JsonNode transfer(String token, UUID id, UUID to, String comment, int expected)
      throws Exception {
    var body = new LinkedHashMap<String, Object>();
    if (to != null) body.put("toMembershipId", to.toString());
    if (comment != null) body.put("comment", comment);
    return request(token, "POST", "/identity/my-approvals/" + id + "/transfer", body, expected);
  }

  private JsonNode batch(String token, String action, List<?> items, String comment, UUID to)
      throws Exception {
    var body = new LinkedHashMap<String, Object>();
    body.put("action", action);
    body.put("items", items);
    if (comment != null) body.put("comment", comment);
    if (to != null) body.put("toMembershipId", to.toString());
    return request(token, "POST", "/identity/my-approvals/batch", body, 200);
  }

  private Map<String, Object> batchItem(UUID id) {
    return Map.of("instanceId", id.toString());
  }

  /** 打内部提交入口并断言状态码，返回响应体。 */
  private String internal(String token, Map<String, Object> body, String cur, String amount, int expected)
      throws Exception {
    var full = new LinkedHashMap<String, Object>(body);
    if (cur != null) full.put("currencyCode", cur);
    if (amount != null) full.put("amount", new BigDecimal(amount));
    var response =
        http.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                        "/identity/approval-instances")
                    .header("Authorization", "Bearer " + token)
                    .header("X-Internal-Key", INTERNAL)
                    .contentType("application/json")
                    .content(json.writeValueAsString(full)))
            .andExpect(status().is(expected))
            .andReturn()
            .getResponse();
    return response.getContentAsString();
  }

  /** 租户事务内直查（RLS 生效，与应用侧同一条路径）。 */
  private Record direct(UUID tenant, String statement, Object... bindings) {
    return tx().run(
        tenant,
        () -> {
          var r = sql.fetchOne(statement, bindings);
          assertThat(r).as("租户事务内直查应当查到一行").isNotNull();
          return r;
        });
  }

  private List<Record> directAll(UUID tenant, String statement, Object... bindings) {
    return tx().run(tenant, () -> List.copyOf(sql.fetch(statement, bindings)));
  }

  private UUID tenantOf(String token) throws Exception {
    return UUID.fromString(
        request(token, "GET", "/identity/tenants/current", null, 200).get("tenantId").asText());
  }

  private UUID membershipOf(String token) throws Exception {
    return UUID.fromString(
        request(token, "GET", "/auth/session", null, 200).get("membershipId").asText());
  }

  // ------------------------------------------------------------------
  // 提交入口
  // ------------------------------------------------------------------

  @Test
  void 提交入口按金额分支决定起始节点且租户与申请人来自会话() throws Exception {
    var owner = login();
    tag = suffix();
    var role = roleId(owner, "FIN" + tag, perms("read", "approve"));
    var flowId =
        UUID.fromString(
            request(
                    owner,
                    "POST",
                    "/identity/approval-flows",
                    Map.of(
                        "documentTypeCode", "PURCHASE_ORDER",
                        "flowName", "采购审批 " + tag,
                        "status", "ACTIVE",
                        "description", "17.4.2.1 测试流程",
                        "nodes",
                            List.of(node("R1", 1, "ROLE", role), node("R2", 2, "ROLE", role)),
                        "branches", List.of(branch("大额", "USD", "10000", null, "R2")),
                        "escalations", List.of()),
                    200)
                .asText());

    var small = submit(owner, flowId, "trade", "PO-" + tag + "-1", "小额采购单 " + tag, "USD", "500");
    assertThat(small.get("currentNodeKey").asText()).isEqualTo("R1");
    assertThat(small.get("routedBy").asText()).isEqualTo("FLOW_ORDER");

    // 落入 [10000, ∞) 分支 → 起点直接是 R2：金额分支是真的被用上的，不是定义完就摆着
    var big = submit(owner, flowId, "trade", "PO-" + tag + "-2", "大额采购单 " + tag, "USD", "20000");
    assertThat(big.get("currentNodeKey").asText()).isEqualTo("R2");
    assertThat(big.get("routedBy").asText()).isEqualTo("AMOUNT_BRANCH");

    // 租户与申请人来自会话，不是请求体：库里那两列就是会话成员关系
    var stored =
        direct(
            tenantOf(owner),
            "select tenant_id, applicant_membership_id, amount, currency_code, status from approval_instance where id=?",
            UUID.fromString(big.get("id").asText()));
    assertThat(stored.get("tenant_id", UUID.class)).isEqualTo(tenantOf(owner));
    assertThat(stored.get("applicant_membership_id", UUID.class)).isEqualTo(membershipOf(owner));
    assertThat(stored.get("amount", BigDecimal.class)).isEqualByComparingTo("20000");
    assertThat(stored.get("status", String.class)).isEqualTo("PENDING");

    // 草稿流程不产生待办
    var draftRole = roleId(owner, "DRF" + tag, perms("read"));
    var draft =
        UUID.fromString(
            request(
                    owner,
                    "POST",
                    "/identity/approval-flows",
                    Map.of(
                        "documentTypeCode", "REFUND",
                        "flowName", "草稿流 " + tag,
                        "status", "DRAFT",
                        "description", "",
                        "nodes", List.of(node("R1", 1, "ROLE", draftRole)),
                        "branches", List.of(),
                        "escalations", List.of()),
                    200)
                .asText());
    internal(
        owner,
        Map.of("flowId", draft.toString(), "sourceSystem", "trade",
            "sourceReferenceId", "RF-" + tag, "subjectTitle", "草稿流不该受理"),
        null, null, 400);

    // 幂等：同一来源单据在存活期间只能有一条实例
    submit(owner, flowId, "trade", "PO-" + tag + "-3", "幂等单 " + tag, "USD", "100");
    internal(
        owner,
        Map.of("flowId", flowId.toString(), "sourceSystem", "trade",
            "sourceReferenceId", "PO-" + tag + "-3", "subjectTitle", "幂等单 " + tag),
        "USD", "100", 409);

    // 没有内部密钥 = 拒绝内部调用
    http.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                    "/identity/approval-instances")
                .header("Authorization", "Bearer " + owner)
                .header("X-Internal-Key", "wrong")
                .contentType("application/json")
                .content(
                    json.writeValueAsString(
                        Map.of("flowId", flowId.toString(), "sourceSystem", "trade",
                            "sourceReferenceId", "X", "subjectTitle", "X"))))
        .andExpect(status().isForbidden());

    // 金额与币种必须同进同出：只有金额没有币种，阈值分支匹配没有唯一答案
    internal(
        owner,
        Map.of("flowId", flowId.toString(), "sourceSystem", "trade",
            "sourceReferenceId", "PO-" + tag + "-4", "subjectTitle", "只有金额"),
        null, "100", 400);
  }

  // ------------------------------------------------------------------
  // 待审批列表
  // ------------------------------------------------------------------

  @Test
  void 待审批列表按节点审批人过滤并展示五个字段() throws Exception {
    var owner = login();
    tag = suffix();
    var role = roleId(owner, "APR" + tag, perms("read", "approve"));
    var other = roleId(owner, "OTH" + tag, perms("read", "approve"));
    var user = approver(owner, "apr-u1-" + tag.toLowerCase(), role);
    var user2 = approver(owner, "apr-u2-" + tag.toLowerCase(), other);
    var flowId =
        UUID.fromString(
            request(
                    owner,
                    "POST",
                    "/identity/approval-flows",
                    Map.of(
                        "documentTypeCode", "PURCHASE_PAYMENT",
                        "flowName", "付款审批 " + tag,
                        "status", "ACTIVE",
                        "description", "",
                        "nodes",
                            List.of(node("R1", 1, "ROLE", role), node("R2", 2, "ROLE", other)),
                        "branches", List.of(),
                        "escalations", List.of()),
                    200)
                .asText());
    submit(owner, flowId, "trade", "PP-" + tag + "-1", "归我的一单 " + tag, "USD", "8800.5");
    submit(owner, flowId, "trade", "PP-" + tag + "-2", "也归我 " + tag, "USD", "1200");

    var mine = inbox(user, "");
    assertThat(mine.get("items")).hasSize(2);
    assertThat(mine.get("total").asLong()).isEqualTo(2);
    // 蓝图点名的五个字段都有真实值
    var first = mine.get("items").get(0);
    assertThat(first.get("documentTypeCode").asText()).isEqualTo("PURCHASE_PAYMENT");
    assertThat(first.get("documentTypeName").asText()).isNotBlank();
    assertThat(first.get("currentNodeKey").asText()).isEqualTo("R1");
    assertThat(first.get("applicantId").asText()).isEqualTo(membershipOf(owner).toString());
    assertThat(first.get("dwellSeconds").asLong()).isGreaterThanOrEqualTo(0);
    assertThat(first.get("subjectTitle").asText()).isNotBlank();
    // 金额是 numeric(19,6)，不是浮点：契约里是 JSON 数字，库里的 scale 是 6
    var amounts = new ArrayList<BigDecimal>();
    for (JsonNode i : mine.get("items")) {
      assertThat(i.get("amount").isNumber()).as("金额必须是 JSON 数字而不是字符串").isTrue();
      amounts.add(i.get("amount").decimalValue());
    }
    assertThat(amounts)
        .usingElementComparator(BigDecimal::compareTo)
        .containsExactlyInAnyOrder(new BigDecimal("8800.500000"), new BigDecimal("1200.000000"));
    var dbAmount =
        direct(tenantOf(owner), "select amount from approval_instance where subject_title=?",
            "归我的一单 " + tag).get("amount", BigDecimal.class);
    assertThat(dbAmount.scale()).as("库里的金额必须保持 numeric(19,6) 的 6 位小数").isEqualTo(6);
    assertThat(dbAmount).isEqualByComparingTo("8800.500000");

    // 第二个角色不在 R1 节点上 → 什么都看不见（**不是**「都给他看」）
    assertThat(inbox(user2, "").get("items")).isEmpty();
    // 所有者也不自动是审批人：他没有那个节点角色的分配
    assertThat(inbox(owner, "").get("items")).isEmpty();
    // 指定人节点（USER）也能判定
    var me = membershipOf(user);
    var byUser =
        UUID.fromString(
            request(
                    owner,
                    "POST",
                    "/identity/approval-flows",
                    Map.of(
                        "documentTypeCode", "DATA_EXPORT",
                        "flowName", "导出审批 " + tag,
                        "status", "ACTIVE",
                        "description", "",
                        "nodes", List.of(node("U1", 1, "USER", me)),
                        "branches", List.of(),
                        "escalations", List.of()),
                    200)
                .asText());
    submit(owner, byUser, "identity", "DE-" + tag, "导出申请 " + tag, null, null);
    assertThat(inbox(user, "").get("items")).hasSize(3);
    assertThat(inbox(user2, "").get("items")).isEmpty();
  }

  @Test
  void 停留时长筛选与排序在DB层且时间写回值零漂移() throws Exception {
    var owner = login();
    tag = suffix();
    var role = roleId(owner, "APR" + tag, perms("read", "approve"));
    var user = approver(owner, "apr-dwell-" + tag.toLowerCase(), role);
    var flowId =
        UUID.fromString(
            request(
                    owner,
                    "POST",
                    "/identity/approval-flows",
                    Map.of(
                        "documentTypeCode", "STOCK_ADJUSTMENT",
                        "flowName", "调整审批 " + tag,
                        "status", "ACTIVE",
                        "description", "",
                        "nodes", List.of(node("R1", 1, "ROLE", role)),
                        "branches", List.of(),
                        "escalations", List.of()),
                    200)
                .asText());
    var oldId = UUID.fromString(
        submit(owner, flowId, "warehouse", "ADJ-" + tag + "-1", "压了很久 " + tag, "USD", "100")
            .get("id").asText());
    submit(owner, flowId, "warehouse", "ADJ-" + tag + "-2", "刚到的 " + tag, "USD", "200");

    // 把「压了很久」那一单往前拨 3 天。+08:00 夹具 + 显式断言写回值：
    // 用 Timestamp.valueOf(LocalDateTime) 会静默偏 -28800s，这里必须验出来。
    var tenant = tenantOf(owner);
    var back = OffsetDateTime.now(PLUS_8).minusDays(3).truncatedTo(ChronoUnit.SECONDS);
    tx().run(
        tenant,
        () -> {
          sql.execute(
              "update approval_instance set node_entered_at=?::timestamptz, submitted_at=?::timestamptz where id=?",
              back, back, oldId);
          return null;
        });
    var stored = direct(tenant, "select node_entered_at, submitted_at from approval_instance where id=?", oldId);
    assertThat(stored.get("node_entered_at", OffsetDateTime.class).toInstant())
        .as("node_entered_at 写回值必须与 +08:00 夹具零漂移")
        .isEqualTo(back.toInstant());
    assertThat(stored.get("submitted_at", OffsetDateTime.class).toInstant())
        .as("submitted_at 写回值必须与 +08:00 夹具零漂移")
        .isEqualTo(back.toInstant());

    // 默认排序 = 最久的排最前（DB 侧 order by node_entered_at asc）
    assertThat(inbox(user, "").get("items").get(0).get("subjectTitle").asText())
        .isEqualTo("压了很久 " + tag);
    // 停留时长筛选只有压了 3 天那单命中
    var filtered = inbox(user, "?minDwellSeconds=3600");
    assertThat(filtered.get("items")).hasSize(1);
    assertThat(filtered.get("items").get(0).get("subjectTitle").asText()).isEqualTo("压了很久 " + tag);
    assertThat(filtered.get("total").asLong()).isEqualTo(1);
    // 表头汇总是**未过滤**的待办总数
    assertThat(filtered.get("summary").get("pendingTotal").asLong()).isEqualTo(2);
    assertThat(filtered.get("summary").get("longestDwellSeconds").asLong())
        .isGreaterThanOrEqualTo(3L * 24 * 3600);

    // 展示值由 DB 函数算：压 3 天那单的 dwell 至少 3 天
    var page = inbox(user, "");
    long dwell = 0;
    for (JsonNode i : page.get("items"))
      if (("压了很久 " + tag).equals(i.get("subjectTitle").asText())) dwell = i.get("dwellSeconds").asLong();
    assertThat(dwell).isGreaterThanOrEqualTo(3L * 24 * 3600);

    assertThat(inbox(user, "?sort=AMOUNT_DESC").get("items").get(0).get("amount").decimalValue())
        .isEqualByComparingTo("200");
    assertThat(inbox(user, "?sort=OLDEST").get("items").get(0).get("subjectTitle").asText())
        .isEqualTo("压了很久 " + tag);
    request(user, "GET", "/identity/my-approvals?sort=NOPE", null, 400);
    assertThat(inbox(user, "?size=1").get("total").asLong()).isEqualTo(2);
    assertThat(inbox(user, "?size=1").get("items")).hasSize(1);
    assertThat(inbox(user, "?size=1&page=1").get("items")).hasSize(1);
    request(user, "GET", "/identity/my-approvals?size=0", null, 400);
    request(user, "GET", "/identity/my-approvals?size=500", null, 400);
    request(user, "GET", "/identity/my-approvals?page=-1", null, 400);
    request(user, "GET", "/identity/my-approvals?minDwellSeconds=-1", null, 400);
  }

  @Test
  void 多维筛选组合生效() throws Exception {
    var owner = login();
    tag = suffix();
    var role = roleId(owner, "APR" + tag, perms("read", "approve"));
    var user = approver(owner, "apr-filter-" + tag.toLowerCase(), role);
    var purchase =
        UUID.fromString(
            request(
                    owner,
                    "POST",
                    "/identity/approval-flows",
                    Map.of("documentTypeCode", "PURCHASE_ORDER", "flowName", "采购 " + tag, "status", "ACTIVE",
                        "description", "", "nodes", List.of(node("R1", 1, "ROLE", role)),
                        "branches", List.of(), "escalations", List.of()),
                    200)
                .asText());
    var price =
        UUID.fromString(
            request(
                    owner,
                    "POST",
                    "/identity/approval-flows",
                    Map.of("documentTypeCode", "PRICE_CHANGE", "flowName", "调价 " + tag, "status", "ACTIVE",
                        "description", "", "nodes", List.of(node("R1", 1, "ROLE", role)),
                        "branches", List.of(), "escalations", List.of()),
                    200)
                .asText());
    submit(owner, purchase, "trade", "PO-F-" + tag, "采购 500 " + tag, "USD", "500");
    submit(owner, purchase, "trade", "PO-F-" + tag + "b", "采购 5000 " + tag, "USD", "5000");
    submit(owner, price, "channel", "PC-F-" + tag, "调价 200 " + tag, "EUR", "200");

    assertThat(inbox(user, "?documentTypeCode=PURCHASE_ORDER").get("total").asLong()).isEqualTo(2);
    assertThat(inbox(user, "?sourceSystem=channel").get("total").asLong()).isEqualTo(1);
    assertThat(inbox(user, "?currencyCode=EUR").get("total").asLong()).isEqualTo(1);
    assertThat(inbox(user, "?minAmount=1000").get("total").asLong()).isEqualTo(1);
    assertThat(inbox(user, "?minAmount=400&maxAmount=6000").get("total").asLong()).isEqualTo(2);
    assertThat(inbox(user, "?documentTypeCode=PRICE_CHANGE&currencyCode=USD").get("total").asLong())
        .isEqualTo(0);
    assertThat(inbox(user, "?applicantId=" + membershipOf(owner)).get("total").asLong()).isEqualTo(3);
    request(user, "GET", "/identity/my-approvals?minAmount=900&maxAmount=100", null, 400);
    request(user, "GET", "/identity/my-approvals?minAmount=-5", null, 400);
    request(user, "GET", "/identity/my-approvals?submittedFrom=not-a-time", null, 400);
    request(
        user,
        "GET",
        "/identity/my-approvals?submittedFrom=2030-01-01T00:00:00Z&submittedTo=2020-01-01T00:00:00Z",
        null,
        400);
  }

  @Test
  void 岗位节点解析不出人时如实报出无人可审() throws Exception {
    var owner = login();
    tag = suffix();
    var role = roleId(owner, "APR" + tag, perms("read", "approve"));
    var position = positionId(owner, "FIN" + tag);
    var user = approver(owner, "apr-pos-" + tag.toLowerCase(), role);
    var flowId =
        UUID.fromString(
            request(
                    owner,
                    "POST",
                    "/identity/approval-flows",
                    Map.of("documentTypeCode", "STOCK_TRANSFER", "flowName", "调拨 " + tag, "status", "ACTIVE",
                        "description", "", "nodes", List.of(node("P1", 1, "POSITION", position)),
                        "branches", List.of(), "escalations", List.of()),
                    200)
                .asText());
    submit(owner, flowId, "warehouse", "TR-" + tag, "调拨单 " + tag, null, null);

    var page = inbox(user, "");
    // 岗位字典不带成员绑定 → 它**不在**任何人的列表里，但如实报出来，不让它静默消失
    assertThat(page.get("items")).isEmpty();
    var unclaimed = page.get("unclaimedNodes");
    assertThat(unclaimed).hasSize(1);
    assertThat(unclaimed.get(0).get("nodeKey").asText()).isEqualTo("P1");
    assertThat(unclaimed.get(0).get("pendingCount").asLong()).isEqualTo(1);
  }

  // ------------------------------------------------------------------
  // 审批动作
  // ------------------------------------------------------------------

  @Test
  void 通过推进到下一个节点且停留时长重新起算() throws Exception {
    var owner = login();
    tag = suffix();
    var role = roleId(owner, "APR" + tag, perms("read", "approve"));
    var role2 = roleId(owner, "APR2" + tag, perms("read", "approve"));
    var user = approver(owner, "apr-adv-" + tag.toLowerCase(), role);
    var user2 = approver(owner, "apr-adv2-" + tag.toLowerCase(), role2);
    var flowId =
        UUID.fromString(
            request(
                    owner,
                    "POST",
                    "/identity/approval-flows",
                    Map.of("documentTypeCode", "PURCHASE_PAYMENT", "flowName", "两级付款 " + tag, "status", "ACTIVE",
                        "description", "", "nodes", List.of(node("R1", 1, "ROLE", role), node("R2", 2, "ROLE", role2)),
                        "branches", List.of(), "escalations", List.of()),
                    200)
                .asText());
    var id =
        UUID.fromString(
            submit(owner, flowId, "trade", "PP-A-" + tag, "两级单 " + tag, "USD", "9999").get("id").asText());
    var tenant = tenantOf(owner);

    // 第一级通过 → 推进到 R2，实例仍未结，停留时长重新起算
    var after = decide(user, id, "APPROVE", "同意付款", 200);
    assertThat(after.get("currentNodeKey").asText()).isEqualTo("R2");
    assertThat(after.get("status").asText()).isEqualTo("PENDING");
    var moved = direct(tenant, "select current_node_key, node_entered_at, finished_at from approval_instance where id=?", id);
    assertThat(moved.get("finished_at", OffsetDateTime.class)).as("未结实例不应有结束时刻").isNull();
    assertThat(moved.get("node_entered_at", OffsetDateTime.class).toInstant())
        .isAfterOrEqualTo(Instant.now().minusSeconds(60));

    // 第二个人现在才看见它，第一个人看不到了
    assertThat(inbox(user, "").get("items")).isEmpty();
    assertThat(inbox(user2, "").get("items")).hasSize(1);

    // 最后一个节点通过 → 结为 APPROVED
    var done = decide(user2, id, "APPROVE", "最终同意", 200);
    assertThat(done.get("status").asText()).isEqualTo("APPROVED");
    var closed = direct(tenant, "select status, finished_at from approval_instance where id=?", id);
    assertThat(closed.get("status", String.class)).isEqualTo("APPROVED");
    assertThat(closed.get("finished_at", OffsetDateTime.class)).isNotNull();
    assertThat(inbox(user2, "").get("items")).isEmpty();
    // 已结的单子不能再批
    decide(user2, id, "APPROVE", "重复", 404);
    // 详情对非本人 404（403 会泄露存在性）
    request(user, "GET", "/identity/my-approvals/" + id, null, 404);
    // 两个节点各一条动作，节点归属正确
    var actions =
        directAll(tenant, "select action, node_key from approval_instance_action where instance_id=? order by decided_at, id", id);
    assertThat(actions).hasSize(2);
    assertThat(actions.get(0).get("node_key", String.class)).isEqualTo("R1");
    assertThat(actions.get(1).get("node_key", String.class)).isEqualTo("R2");
  }

  @Test
  void 驳回必须写理由且驳回即终止() throws Exception {
    var owner = login();
    tag = suffix();
    var role = roleId(owner, "APR" + tag, perms("read", "approve"));
    var user = approver(owner, "apr-rej-" + tag.toLowerCase(), role);
    var flowId =
        UUID.fromString(
            request(
                    owner,
                    "POST",
                    "/identity/approval-flows",
                    Map.of("documentTypeCode", "CREDIT_NOTE", "flowName", "贷项 " + tag, "status", "ACTIVE",
                        "description", "", "nodes", List.of(node("R1", 1, "ROLE", role), node("R2", 2, "ROLE", role)),
                        "branches", List.of(), "escalations", List.of()),
                    200)
                .asText());
    var tenant = tenantOf(owner);
    var id =
        UUID.fromString(
            submit(owner, flowId, "finance", "CN-" + tag, "贷项单 " + tag, "USD", "300").get("id").asText());

    decide(user, id, "REJECT", null, 400);
    decide(user, id, "REJECT", "   ", 400);
    // 那次失败没有把状态改掉
    assertThat(direct(tenant, "select status from approval_instance where id=?", id).get("status", String.class))
        .isEqualTo("PENDING");

    assertThat(decide(user, id, "REJECT", "金额与单据对不上", 200).get("status").asText())
        .isEqualTo("REJECTED");
    assertThat(direct(tenant, "select finished_at from approval_instance where id=?", id)
            .get("finished_at", OffsetDateTime.class))
        .isNotNull();
    assertThat(inbox(user, "").get("items")).isEmpty();

    var actions =
        directAll(tenant, "select action, actor_membership_id, node_key, comment from approval_instance_action where instance_id=?", id);
    assertThat(actions).hasSize(1);
    assertThat(actions.get(0).get("action", String.class)).isEqualTo("REJECT");
    assertThat(actions.get(0).get("comment", String.class)).isEqualTo("金额与单据对不上");
    // 「审批记录不可修改」是权限级保证：identity_app 只拿到 SELECT,INSERT
    assertThat(grantsOf("approval_instance_action")).isEqualTo("INSERT,SELECT");
  }

  @Test
  void 转交把待办真正交给那个人() throws Exception {
    var owner = login();
    tag = suffix();
    var role = roleId(owner, "APR" + tag, perms("read", "approve", "transfer"));
    var user = approver(owner, "apr-tf-" + tag.toLowerCase(), role);
    // 被转交的人**不是**任何节点的审批人角色，但有页面权限 —— 这样才能证明「转交」本身产生了归属
    var bystander = roleId(owner, "BY" + tag, perms("read", "approve"));
    var target = approver(owner, "apr-tf-target-" + tag.toLowerCase(), bystander);
    var targetId = membershipOf(target);
    var flowId =
        UUID.fromString(
            request(
                    owner,
                    "POST",
                    "/identity/approval-flows",
                    Map.of("documentTypeCode", "VENDOR_ONBOARD", "flowName", "供应商 " + tag, "status", "ACTIVE",
                        "description", "", "nodes", List.of(node("R1", 1, "ROLE", role)),
                        "branches", List.of(), "escalations", List.of()),
                    200)
                .asText());
    var tenant = tenantOf(owner);
    var id =
        UUID.fromString(
            submit(owner, flowId, "trade", "VO-" + tag, "供应商准入 " + tag, null, null).get("id").asText());

    // 转出去之前：只有原审批人看得见
    assertThat(inbox(user, "").get("items")).hasSize(1);
    assertThat(inbox(target, "").get("items")).isEmpty();

    var moved = transfer(user, id, targetId, "我在休假", 200);
    assertThat(moved.get("assignedToId").asText()).isEqualTo(targetId.toString());
    var stored = direct(tenant, "select assigned_membership_id, node_entered_at, status from approval_instance where id=?", id);
    assertThat(stored.get("assigned_membership_id", UUID.class)).isEqualTo(targetId);
    assertThat(stored.get("status", String.class)).isEqualTo("PENDING");

    // 转交真的换了人：原审批人立刻看不见，被转交的人立刻能看见并能批
    assertThat(inbox(user, "").get("items")).isEmpty();
    assertThat(inbox(target, "").get("items")).hasSize(1);
    request(user, "GET", "/identity/my-approvals/" + id, null, 404);
    assertThat(decide(target, id, "APPROVE", "我代批", 200).get("status").asText()).isEqualTo("APPROVED");

    // 转给自己 / 转给申请人 / 转给不存在的人 都 400
    var second =
        UUID.fromString(
            submit(owner, flowId, "trade", "VO-" + tag + "2", "第二张 " + tag, null, null).get("id").asText());
    transfer(user, second, membershipOf(user), "转给自己", 400);
    transfer(user, second, membershipOf(owner), "转给申请人", 400);
    transfer(user, second, UUID.randomUUID(), "转给不存在的人", 400);
    // 转给没有页面权限的普通成员：转交本身允许，但那人**动不了**（权限闸仍在）
    var plain = plainMember(owner, "apr-tf-plain-" + tag.toLowerCase());
    transfer(user, second, plain, "转给没权限的人", 200);
    request(loginUser("apr-tf-plain-" + tag.toLowerCase()), "GET", "/identity/my-approvals", null, 403);
  }

  @Test
  void 转交不重置停留时长() throws Exception {
    var owner = login();
    tag = suffix();
    var role = roleId(owner, "APR" + tag, perms("read", "approve", "transfer"));
    var user = approver(owner, "apr-tfd-" + tag.toLowerCase(), role);
    var flowId =
        UUID.fromString(
            request(
                    owner,
                    "POST",
                    "/identity/approval-flows",
                    Map.of("documentTypeCode", "MEMBER_OFFBOARD", "flowName", "离职停用 " + tag, "status", "ACTIVE",
                        "description", "", "nodes", List.of(node("R1", 1, "ROLE", role)),
                        "branches", List.of(), "escalations", List.of()),
                    200)
                .asText());
    var tenant = tenantOf(owner);
    var id =
        UUID.fromString(
            submit(owner, flowId, "identity", "MO-" + tag, "离职停用 " + tag, null, null).get("id").asText());

    var back = OffsetDateTime.now(PLUS_8).minusDays(2).truncatedTo(ChronoUnit.SECONDS);
    tx().run(
        tenant,
        () -> {
          sql.execute("update approval_instance set node_entered_at=?::timestamptz where id=?", back, id);
          return null;
        });
    assertThat(inbox(user, "").get("items").get(0).get("dwellSeconds").asLong())
        .isGreaterThanOrEqualTo(2L * 24 * 3600);

    transfer(user, id, plainMember(owner, "apr-tfd-t-" + tag.toLowerCase()), "先转出去", 200);
    // 转交**不得**重置停留时长的起算点，否则转交就成了刷老化指标的开关
    assertThat(direct(tenant, "select node_entered_at from approval_instance where id=?", id)
            .get("node_entered_at", OffsetDateTime.class).toInstant())
        .isEqualTo(back.toInstant());
  }

  @Test
  void 转交回去恢复节点配置的审批人() throws Exception {
    var owner = login();
    tag = suffix();
    var role = roleId(owner, "APR" + tag, perms("read", "approve", "transfer"));
    var user = approver(owner, "apr-tfb-" + tag.toLowerCase(), role);
    // 被转交的人也要有 transfer 权限 —— 「转交回去」是**当前持有者**的动作，
    // 原审批人把单子转出去之后就不再持有它，自然也交不回来。
    var holder = roleId(owner, "HLD" + tag, perms("read", "approve", "transfer"));
    var holderToken = approver(owner, "apr-tfb-h-" + tag.toLowerCase(), holder);
    var flowId =
        UUID.fromString(
            request(
                    owner,
                    "POST",
                    "/identity/approval-flows",
                    Map.of("documentTypeCode", "ROLE_GRANT", "flowName", "授权 " + tag, "status", "ACTIVE",
                        "description", "", "nodes", List.of(node("R1", 1, "ROLE", role)),
                        "branches", List.of(), "escalations", List.of()),
                    200)
                .asText());
    var tenant = tenantOf(owner);
    var id =
        UUID.fromString(
            submit(owner, flowId, "identity", "RG-" + tag, "角色授权 " + tag, null, null).get("id").asText());

    transfer(user, id, membershipOf(holderToken), "先转出去", 200);
    assertThat(inbox(user, "").get("items")).isEmpty();
    assertThat(inbox(holderToken, "").get("items")).hasSize(1);
    // 原审批人已经交出了控制权，交不回来
    transfer(user, id, null, "我要交回", 404);
    // 当前持有者交回节点配置的审批人
    var back = transfer(holderToken, id, null, "我自己回来处理", 200);
    assertThat(back.get("assignedToId").isNull()).as("交回后实例级指派被清空").isTrue();
    assertThat(direct(tenant, "select assigned_membership_id from approval_instance where id=?", id)
            .get("assigned_membership_id", UUID.class))
        .isNull();
    assertThat(inbox(user, "").get("items")).hasSize(1);
    assertThat(inbox(holderToken, "").get("items")).isEmpty();
    var actions = directAll(tenant, "select action, to_membership_id from approval_instance_action where instance_id=? order by decided_at, id", id);
    assertThat(actions).hasSize(2);
    assertThat(actions.get(1).get("to_membership_id", UUID.class)).isNull();
  }

  @Test
  void 条件节点不被静默执行而是明确拒绝() throws Exception {
    var owner = login();
    tag = suffix();
    var role = roleId(owner, "APR" + tag, perms("read", "approve"));
    var user = approver(owner, "apr-cond-" + tag.toLowerCase(), role);
    var conditional = new LinkedHashMap<String, Object>(node("C1", 2, "ROLE", role));
    conditional.put("approvalMode", "CONDITIONAL");
    conditional.put("conditionExpression", "amount > 1000");
    var flowId =
        UUID.fromString(
            request(
                    owner,
                    "POST",
                    "/identity/approval-flows",
                    Map.of("documentTypeCode", "PURCHASE_ORDER", "flowName", "条件流转 " + tag, "status", "ACTIVE",
                        "description", "", "nodes", List.of(node("R1", 1, "ROLE", role), conditional),
                        "branches", List.of(), "escalations", List.of()),
                    200)
                .asText());
    var tenant = tenantOf(owner);
    var id =
        UUID.fromString(
            submit(owner, flowId, "trade", "CO-" + tag, "条件单 " + tag, "USD", "5000").get("id").asText());

    var response =
        http.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                        "/identity/my-approvals/" + id + "/decide")
                    .header("Authorization", "Bearer " + user)
                    .contentType("application/json")
                    .content("{\"decision\":\"APPROVE\",\"comment\":\"同意\"}"))
            .andExpect(status().isConflict())
            .andReturn()
            .getResponse();
    // 单条端点的 409 来自 ResponseStatusException，MockMvc 不渲染 body；
    // 结论本身从批量出口读 —— 那里把「为什么没做」如实带给调用方。
    var outcome = batch(user, "APPROVE", List.of(batchItem(id)), "同意", null);
    var one = outcome.get("results").get(0);
    assertThat(one.get("ok").asBoolean()).isFalse();
    assertThat(one.get("code").asText()).isEqualTo("CONDITIONAL_UNRESOLVED");
    assertThat(one.get("message").asText()).contains("条件节点");
    assertThat(response.getStatus()).isEqualTo(409);
    // 实例没被动过：既没推进也没结清
    var unchanged = direct(tenant, "select status, current_node_key from approval_instance where id=?", id);
    assertThat(unchanged.get("current_node_key", String.class)).isEqualTo("R1");
    assertThat(unchanged.get("status", String.class)).isEqualTo("PENDING");
  }

  // ------------------------------------------------------------------
  // 批量
  // ------------------------------------------------------------------

  @Test
  void 批量逐条回报且一条失败不影响其余() throws Exception {
    var owner = login();
    tag = suffix();
    var role = roleId(owner, "APR" + tag, perms("read", "approve", "transfer"));
    // 第二节点用**另一个**角色：否则同一个人既审 R1 又审 R2，「同一条批两次」就会真的批两次成功，
    // 测不到「重复审批被挡下」这条。
    var role2 = roleId(owner, "APR2" + tag, perms("read", "approve", "transfer"));
    var user = approver(owner, "apr-batch-" + tag.toLowerCase(), role);
    var flowId =
        UUID.fromString(
            request(
                    owner,
                    "POST",
                    "/identity/approval-flows",
                    Map.of("documentTypeCode", "WRITE_OFF", "flowName", "核销 " + tag, "status", "ACTIVE",
                        "description", "", "nodes", List.of(node("R1", 1, "ROLE", role), node("R2", 2, "ROLE", role2)),
                        "branches", List.of(), "escalations", List.of()),
                    200)
                .asText());
    var tenant = tenantOf(owner);
    var a = UUID.fromString(submit(owner, flowId, "finance", "WO-" + tag + "-1", "批量 A " + tag, "USD", "100").get("id").asText());
    var b = UUID.fromString(submit(owner, flowId, "finance", "WO-" + tag + "-2", "批量 B " + tag, "USD", "200").get("id").asText());
    var c = UUID.fromString(submit(owner, flowId, "finance", "WO-" + tag + "-3", "批量 C " + tag, "USD", "300").get("id").asText());

    // 中间那条对同一实例批两次：第一次成功，第二次被「已不在待办」挡下
    var result =
        batch(
            user,
            "APPROVE",
            List.of(batchItem(a), batchItem(b), batchItem(b), batchItem(c)),
            "批量通过",
            null);
    assertThat(result.get("total").asInt()).isEqualTo(4);
    assertThat(result.get("results")).hasSize(4);
    assertThat(result.get("succeeded").asInt()).isEqualTo(3);
    assertThat(result.get("failed").asInt()).isEqualTo(1);

    // 同序同长：第 i 条结果对应第 i 条请求
    var results = result.get("results");
    assertThat(results.get(0).get("instanceId").asText()).isEqualTo(a.toString());
    assertThat(results.get(1).get("instanceId").asText()).isEqualTo(b.toString());
    assertThat(results.get(2).get("instanceId").asText()).isEqualTo(b.toString());
    assertThat(results.get(3).get("instanceId").asText()).isEqualTo(c.toString());

    assertThat(results.get(0).get("ok").asBoolean()).isTrue();
    assertThat(results.get(0).get("code").asText()).isEqualTo("APPROVED");
    assertThat(results.get(0).get("status").asText()).as("推进到 R2，实例未结").isEqualTo("PENDING");
    assertThat(results.get(1).get("ok").asBoolean()).isTrue();
    assertThat(results.get(2).get("ok").asBoolean()).isFalse();
    assertThat(results.get(2).get("code").asText()).isEqualTo("NOT_MINE");
    assertThat(results.get(2).get("message").asText()).isNotBlank();
    assertThat(results.get(3).get("ok").asBoolean()).isTrue();

    // **一条失败没有影响其余**：a 与 c 真的推进到了 R2
    assertThat(direct(tenant, "select current_node_key from approval_instance where id=?", a).get("current_node_key", String.class))
        .isEqualTo("R2");
    assertThat(direct(tenant, "select current_node_key from approval_instance where id=?", c).get("current_node_key", String.class))
        .isEqualTo("R2");

    // 不存在的 id 只是这一条失败，且带机器可判定的结果码（用一张**新的**待办验证「其余不受影响」）
    var e = UUID.fromString(submit(owner, flowId, "finance", "WO-" + tag + "-9", "批量 E " + tag, "USD", "900").get("id").asText());
    var withGhost = batch(user, "APPROVE", List.of(batchItem(UUID.randomUUID()), batchItem(e)), "再通过", null);
    assertThat(withGhost.get("succeeded").asInt()).isEqualTo(1);
    assertThat(withGhost.get("failed").asInt()).isEqualTo(1);
    assertThat(withGhost.get("results").get(0).get("code").asText()).isEqualTo("NOT_MINE");
    assertThat(withGhost.get("results").get(1).get("ok").asBoolean()).isTrue();

    // 批量转交也逐条回报（用一张还停在 R1 的新单：a 已被推进到 R2，不再归 user）
    var target = plainMember(owner, "apr-batch-t-" + tag.toLowerCase());
    var f = UUID.fromString(submit(owner, flowId, "finance", "WO-" + tag + "-5", "批量 F " + tag, "USD", "500").get("id").asText());
    var transferred = batch(user, "TRANSFER", List.of(batchItem(f)), "批量转交", target);
    assertThat(transferred.get("results").get(0).get("ok").asBoolean()).isTrue();
    assertThat(transferred.get("results").get(0).get("code").asText()).isEqualTo("TRANSFERRED");
    assertThat(direct(tenant, "select assigned_membership_id from approval_instance where id=?", f)
            .get("assigned_membership_id", UUID.class))
        .isEqualTo(target);

    // 批量驳回：一条给了理由、另一条没给 → 前者成功后者失败并拿到可判定原因
    var d = UUID.fromString(submit(owner, flowId, "finance", "WO-" + tag + "-4", "批量 D " + tag, "USD", "400").get("id").asText());
    var mixed =
        batch(user, "REJECT",
            List.of(Map.of("instanceId", d.toString(), "comment", "有理由"), batchItem(c)),
            null, null);
    assertThat(mixed.get("succeeded").asInt()).isEqualTo(1);
    assertThat(mixed.get("failed").asInt()).isEqualTo(1);
    assertThat(mixed.get("results").get(0).get("status").asText()).isEqualTo("REJECTED");
    assertThat(mixed.get("results").get(1).get("code").asText()).isEqualTo("REJECTED_BY_RULE");
    assertThat(mixed.get("results").get(1).get("message").asText()).contains("驳回必须填写理由");
  }

  @Test
  void 没有审批权限整批403而不是回一批全失败() throws Exception {
    var owner = login();
    tag = suffix();
    var readOnly = roleId(owner, "RO" + tag, perms("read"));
    var user = approver(owner, "apr-ro-" + tag.toLowerCase(), readOnly);
    request(user, "POST", "/identity/my-approvals/batch",
        Map.of("action", "APPROVE", "items", List.of(batchItem(UUID.randomUUID()))), 403);
    request(user, "POST", "/identity/my-approvals/" + UUID.randomUUID() + "/decide",
        Map.of("decision", "APPROVE"), 403);
    request(user, "POST", "/identity/my-approvals/" + UUID.randomUUID() + "/transfer", Map.of(), 403);
    // 但读列表可以
    request(user, "GET", "/identity/my-approvals", null, 200);
  }

  @Test
  void 无令牌一律401() throws Exception {
    http.perform(get("/identity/my-approvals")).andExpect(status().isUnauthorized());
    http.perform(get("/identity/my-approvals/" + UUID.randomUUID())).andExpect(status().isUnauthorized());
    http.perform(get("/identity/my-approvals/capabilities")).andExpect(status().isUnauthorized());
  }

  // ------------------------------------------------------------------
  // 对接能力声明
  // ------------------------------------------------------------------

  @Test
  void 对接状态如实报出未交付并附真实计数() throws Exception {
    var owner = login();
    tag = suffix();
    var role = roleId(owner, "APR" + tag, perms("read", "approve"));
    var user = approver(owner, "apr-cap-" + tag.toLowerCase(), role);
    var flowId =
        UUID.fromString(
            request(
                    owner,
                    "POST",
                    "/identity/approval-flows",
                    Map.of("documentTypeCode", "PURCHASE_ORDER", "flowName", "能力 " + tag, "status", "ACTIVE",
                        "description", "", "nodes", List.of(node("R1", 1, "ROLE", role)),
                        "branches", List.of(), "escalations", List.of()),
                    200)
                .asText());
    submit(owner, flowId, "trade", "CAP-" + tag, "能力单 " + tag, "USD", "100");

    var caps = request(user, "GET", "/identity/my-approvals/capabilities", null, 200);
    assertThat(caps.get("intakeContract").asText()).contains("/identity/approval-instances");
    var sources = caps.get("sourceSystems");
    assertThat(sources).hasSize(5);
    for (JsonNode s : sources) {
      assertThat(s.get("delivered").asBoolean())
          .as("来源系统 %s 不应谎报已交付", s.get("sourceSystem").asText())
          .isFalse();
      assertThat(s.get("owningModule").asText()).isNotBlank();
      assertThat(s.get("note").asText()).isNotBlank();
    }
    // observed 来自真实聚合，不是摆在那里的样例
    var observed = caps.get("observed");
    assertThat(observed).hasSize(1);
    assertThat(observed.get(0).get("sourceSystem").asText()).isEqualTo("trade");
    assertThat(observed.get(0).get("instanceCount").asLong()).isEqualTo(1);
    assertThat(observed.get(0).get("pendingCount").asLong()).isEqualTo(1);
  }

  // ------------------------------------------------------------------
  // 多租户三断言
  // ------------------------------------------------------------------

  @Test
  void 多租户隔离三断言() throws Exception {
    var parent = login();
    tag = suffix();
    var role = roleId(parent, "APR" + tag, perms("read", "approve", "transfer"));
    var parentUser = approver(parent, "apr-p-" + tag.toLowerCase(), role);
    var parentFlow =
        UUID.fromString(
            request(
                    parent,
                    "POST",
                    "/identity/approval-flows",
                    Map.of("documentTypeCode", "PURCHASE_ORDER", "flowName", "父租户待审 " + tag, "status", "ACTIVE",
                        "description", "", "nodes", List.of(node("R1", 1, "ROLE", role)),
                        "branches", List.of(), "escalations", List.of()),
                    200)
                .asText());
    var parentId =
        UUID.fromString(
            submit(parent, parentFlow, "trade", "PO-P-" + tag, "父租户单 " + tag, "USD", "100").get("id").asText());

    // 子租户：一套完全独立的流程与待办
    String childUser = "apr-c-" + tag.toLowerCase();
    request(
        parent,
        "POST",
        "/identity/children",
        Map.of("code", "APC" + tag, "name", "待审批子租户",
            "ownerUsername", childUser, "ownerPassword", "test-member-password"),
        200);
    var child = loginUser(childUser);
    var childRole = roleId(child, "ACR" + tag, perms("read", "approve"));
    var childApprover = approver(child, "apr-ca-" + tag.toLowerCase(), childRole);
    var childFlow =
        UUID.fromString(
            request(
                    child,
                    "POST",
                    "/identity/approval-flows",
                    Map.of("documentTypeCode", "PURCHASE_ORDER", "flowName", "子租户待审 " + tag, "status", "ACTIVE",
                        "description", "", "nodes", List.of(node("R1", 1, "ROLE", childRole)),
                        "branches", List.of(), "escalations", List.of()),
                    200)
                .asText());
    submit(child, childFlow, "trade", "PO-C-" + tag, "子租户单 " + tag, "USD", "100");
    submit(child, childFlow, "trade", "PO-C2-" + tag, "子租户第二单 " + tag, "USD", "200");

    // 断言一：两租户互不可见
    assertThat(inbox(parentUser, "").toString())
        .contains("父租户单 " + tag)
        .doesNotContain("子租户单 " + tag, "子租户第二单 " + tag);
    assertThat(inbox(childApprover, "").toString())
        .contains("子租户单 " + tag)
        .doesNotContain("父租户单 " + tag);
    // 拿子租户的流程 id 去父租户提交 → 404（RLS 下查不到）
    internal(
        parent,
        Map.of("flowId", childFlow.toString(), "sourceSystem", "trade",
            "sourceReferenceId", "X-" + tag, "subjectTitle", "跨租户提交"),
        null, null, 404);
    // 子租户拿父租户的实例 id 审批 → 404
    decide(childApprover, parentId, "APPROVE", "越权", 404);

    // 断言二：伪造 tenant header 无效。request() 每次都带随机 X-Tenant-Id。
    String forged = UUID.randomUUID().toString();
    var forgedView =
        http.perform(get("/identity/my-approvals")
                .header("Authorization", "Bearer " + parentUser)
                .header("X-Tenant-Id", forged))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
    assertThat(forgedView).contains("父租户单 " + tag).doesNotContain("子租户单 " + tag);
    // 伪造成子租户的真实租户 id（调用方本来就是父租户的审批人）：结果仍然不变
    var forgedChild =
        http.perform(get("/identity/my-approvals")
                .header("Authorization", "Bearer " + parentUser)
                .header("X-Tenant-Id", tenantOf(child).toString()))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
    assertThat(forgedChild).contains("父租户单 " + tag).doesNotContain("子租户单 " + tag);
    http.perform(get("/identity/my-approvals")).andExpect(status().isUnauthorized());

    // 断言三：连接池归还后不串租户。池大小被压到 1，交替租户请求复用同一物理连接。
    for (int i = 0; i < 3; i++) {
      var p = inbox(parentUser, "").toString();
      assertThat(p).as("第 %s 轮父租户不得看到子租户待办", i)
          .doesNotContain("子租户单 " + tag, "子租户第二单 " + tag);
      assertThat(p).contains("父租户单 " + tag);
      var c = inbox(childApprover, "").toString();
      assertThat(c).as("第 %s 轮子租户不得看到父租户待办", i).doesNotContain("父租户单 " + tag);
      assertThat(c).contains("子租户单 " + tag, "子租户第二单 " + tag);
      // 审批动作也必须跟着租户走：子租户批不了父租户的单
      decide(childApprover, parentId, "APPROVE", "越权", 404);
    }
    // 转交也必须在自己的租户里落库：转出后父租户这单不再待办，子租户那边毫无变化
    var transferTarget = plainMember(parent, "apr-p-t-" + tag.toLowerCase());
    var moved = batch(parentUser, "TRANSFER", List.of(batchItem(parentId)), "父租户转交", transferTarget);
    assertThat(moved.get("results").get(0).get("code").asText()).isEqualTo("TRANSFERRED");
    assertThat(inbox(parentUser, "").get("items")).as("转交后父租户这单已不在待办里").isEmpty();
    assertThat(inbox(childApprover, "").get("total").asLong()).isEqualTo(2);

    // 事务外（无租户上下文）直查应当为空
    assertThat(sql.fetch("select id from approval_instance")).isEmpty();
    assertThat(sql.fetch("select id from approval_instance_action")).isEmpty();
    // 两张表都必须是 FORCE RLS，否则表 owner 连接会绕过隔离
    for (var table : List.of("approval_instance", "approval_instance_action")) {
      assertThat(
              sql.fetchOne("select relforcerowsecurity as forced from pg_class where relname=?", table)
                  .get("forced", Boolean.class))
          .as("%s 必须 FORCE RLS", table)
          .isTrue();
    }
    // 转交出去之后父租户这个审批人也读不到它了（他已交出控制权）
    request(parentUser, "GET", "/identity/my-approvals/" + parentId, null, 404);
    // 但转交确实落在了**父租户**这一库里，子租户那条完全没被碰到
    assertThat(
            direct(
                    tenantOf(parent),
                    "select assigned_membership_id, status from approval_instance where id=?",
                    parentId)
                .get("assigned_membership_id", UUID.class))
        .isEqualTo(transferTarget);
    assertThat(inbox(childApprover, "").get("total").asLong()).isEqualTo(2);
  }
  // ------------------------------------------------------------------
  // 小工具
  // ------------------------------------------------------------------

  /** identity_app 在某张表上的权限串（用来断言「审批记录不可修改」是权限级保证）。 */
  private String grantsOf(String table) {
    var r =
        sql.fetchOne(
            "select string_agg(privilege_type, ',' order by privilege_type) as p"
                + " from information_schema.table_privileges"
                + " where grantee='identity_app' and table_name=?",
            table);
    return r == null || r.get("p", String.class) == null ? "" : r.get("p", String.class);
  }
}
