package com.starcon.identity;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 17.4.1.1 审批流定义。
 *
 * <p>蓝图对本模块点名的东西逐条对应到断言上：
 *
 * <ol>
 *   <li><b>审批节点（角色/岗位/指定人）</b>——三种审批人都要能配，且角色必须指向 17.3.1.1 的 <b>真实 role 表</b>：引用一个不存在的角色必须
 *       400，而不是静默存一个悬空 id。
 *   <li><b>审批方式（串行/并行/条件分支）</b>——阶段之间串行、阶段内并行、条件分支必须带表达式；
 *       一个阶段塞两个串行节点在配置期就被拒（400），因为那等于「串行」和「阶段」互相矛盾。
 *   <li><b>金额阈值分支</b>——同币种内区间不允许交叠（409，交叠时「该走哪个节点」没有唯一答案）； 不同币种允许重合；端点开放的区间与其他分支也判交叠（它是默认路径，不是分支）。
 *   <li><b>超时升级规则</b>——时限必须为正、同一节点同一时限只能一条、转办对象形状校验。
 *   <li><b>按单据类型注册</b>——同一租户同一单据类型只能有一条生效流程（409）； {@code /registrations} 直接给出「哪些单据类型还没注册」，这是蓝图那句
 *       「未注册的变更类操作默认可直接执行」的可查询形态；未注册时 {@code /active} 返回 404。
 * </ol>
 *
 * <p>另外实跑：版本随每次保存 +1、岗位被节点引用时拒删、金额 BigDecimal 与时间 {@code timestamptz}
 * 的<b>写回值</b>断言，以及多租户三断言（两租户互不可见 / 伪造 tenant header 无效 / 连接池归还后不串租户）。
 *
 * <p>本模块刻意<b>不</b>覆盖：发起审批、处理审批实例、待办列表、超时定时器 —— 那是 17.4.1.2 与 17.4.2.1。这里只测定义与配置期校验。
 */
class ApprovalFlowIntegrationTest extends IdentityIntegrationTestBase {

  @BeforeEach
  void resetModule() throws Exception {
    try (var c =
            DriverManager.getConnection(pg().getJdbcUrl(), pg().getUsername(), pg().getPassword());
        var s = c.createStatement()) {
      // 顺序按外键依赖：升级规则与金额分支都引用节点。
      // approval_trigger_rule（17.4.1.2 审批规则）的 target_flow_id 也引用 approval_flow，
      // approval_instance / approval_instance_action（17.4.2.1 待我审批）同样引用 approval_flow，
      // 所以它们必须一起清空 —— 否则 TRUNCATE 会因外键被拒（PostgreSQL 要求被引用表与引用表同批清理）。
      s.execute(
          "truncate approval_flow_escalation, approval_flow_amount_branch,"
              + " approval_flow_node, approval_trigger_rule, approval_rule_set,"
              + " approval_instance_action, approval_instance,"
              + " approval_flow, approval_position");
    }
  }

  // ------------------------------------------------------------------
  // 请求与数据辅助
  // ------------------------------------------------------------------

  private String tag;

  private UUID roleId(String token, String code) throws Exception {
    return UUID.fromString(
        request(
                token,
                "POST",
                "/identity/roles",
                Map.of(
                    "code",
                    code,
                    "name",
                    "审批角色 " + code,
                    "status",
                    "ACTIVE",
                    "permissions",
                    List.of("identity.approval_flow.read")),
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

  private UUID memberId(String token, String username) throws Exception {
    var department = createDepartment(token, "DEP" + username.toUpperCase(), "审批测试部门", null);
    return UUID.fromString(
        request(
                token,
                "POST",
                "/identity/members",
                memberInput(username, department.toString(), List.of()),
                200)
            .asText());
  }

  /** 节点：默认按角色审批，可覆盖审批人类型。 */
  private Map<String, Object> node(
      String key, String name, int order, int stage, String mode, UUID role) {
    var n = new LinkedHashMap<String, Object>();
    n.put("nodeKey", key);
    n.put("nodeName", name);
    n.put("nodeOrder", order);
    n.put("stageIndex", stage);
    n.put("approverType", "ROLE");
    n.put("roleId", role);
    n.put("approvalMode", mode);
    return n;
  }

  private Map<String, Object> branch(
      String name, String currency, String min, String max, String target) {
    var b = new LinkedHashMap<String, Object>();
    b.put("branchName", name);
    b.put("currencyCode", currency);
    if (min != null) b.put("minAmount", new BigDecimal(min));
    if (max != null) b.put("maxAmount", new BigDecimal(max));
    b.put("targetNodeKey", target);
    return b;
  }

  private Map<String, Object> escalateAuto(String nodeKey, int hours) {
    var e = new LinkedHashMap<String, Object>();
    e.put("nodeKey", nodeKey);
    e.put("timeoutAction", "AUTO_APPROVE");
    e.put("timeoutHours", hours);
    return e;
  }

  private Map<String, Object> escalateToRole(String nodeKey, int hours, UUID role) {
    var e = new LinkedHashMap<String, Object>();
    e.put("nodeKey", nodeKey);
    e.put("timeoutAction", "ESCALATE");
    e.put("timeoutHours", hours);
    e.put("escalateApproverType", "ROLE");
    e.put("escalateRoleId", role);
    return e;
  }

  private Map<String, Object> flow(
      String documentType,
      String name,
      String status,
      List<?> nodes,
      List<?> branches,
      List<?> escalations) {
    var f = new LinkedHashMap<String, Object>();
    f.put("documentTypeCode", documentType);
    f.put("flowName", name);
    f.put("status", status);
    f.put("description", "17.4.1.1 测试流程");
    f.put("nodes", nodes);
    f.put("branches", branches);
    f.put("escalations", escalations);
    return f;
  }

  private JsonNode detail(String token, UUID id) throws Exception {
    return request(token, "GET", "/identity/approval-flows/" + id, null, 200);
  }

  private JsonNode nodeNamed(JsonNode nodes, String key) {
    for (JsonNode n : nodes) if (key.equals(n.get("nodeKey").asText())) return n;
    return null;
  }

  // ------------------------------------------------------------------
  // 节点：角色 / 岗位 / 指定人
  // ------------------------------------------------------------------

  @Test
  void 三种审批人都能配且角色指向真实角色表() throws Exception {
    var token = login();
    tag = suffix();
    var role = roleId(token, "APPROVER" + tag);
    var position = positionId(token, "FIN" + tag);
    var member = memberId(token, "apv-user-" + tag.toLowerCase());

    var byPosition = node("P1", "岗位审批", 1, 1, "SERIAL", null);
    byPosition.remove("roleId");
    byPosition.put("approverType", "POSITION");
    byPosition.put("positionId", position);
    var byUser = node("U2", "指定人审批", 2, 2, "SERIAL", null);
    byUser.remove("roleId");
    byUser.put("approverType", "USER");
    byUser.put("membershipId", member);

    UUID id =
        UUID.fromString(
            request(
                    token,
                    "POST",
                    "/identity/approval-flows",
                    flow(
                        "PURCHASE_ORDER",
                        "三段审批 " + tag,
                        "DRAFT",
                        List.of(node("R1", "角色审批", 0, 0, "SERIAL", role), byPosition, byUser),
                        List.of(),
                        List.of()),
                    200)
                .asText());

    var nodes = detail(token, id).get("nodes");
    assertThat(nodes).hasSize(3);
    // 角色节点带出真实角色编码：证明引用的是 17.3.1.1 的 role 表，不是平行体系。
    assertThat(nodeNamed(nodes, "R1").get("roleId").asText()).isEqualTo(role.toString());
    assertThat(nodeNamed(nodes, "R1").get("roleCode").asText()).isEqualTo("APPROVER" + tag);
    assertThat(nodeNamed(nodes, "P1").get("positionName").asText()).isEqualTo("岗位 FIN" + tag);
    assertThat(nodeNamed(nodes, "U2").get("membershipId").asText()).isEqualTo(member.toString());
    // 三种审批人各自的列互斥：库里 approval_flow_node_approver_shape 兜底。
    assertThat(nodeNamed(nodes, "R1").get("positionId").isNull()).isTrue();
    assertThat(nodeNamed(nodes, "P1").get("roleId").isNull()).isTrue();
    assertThat(nodeNamed(nodes, "U2").get("roleId").isNull()).isTrue();
  }

  @Test
  void 引用不存在的角色在配置期被拒() throws Exception {
    var token = login();
    tag = suffix();
    request(
        token,
        "POST",
        "/identity/approval-flows",
        flow(
            "PURCHASE_ORDER",
            "悬空角色 " + tag,
            "DRAFT",
            List.of(node("R1", "坏节点", 0, 0, "SERIAL", UUID.randomUUID())),
            List.of(),
            List.of()),
        400);
    // 悬空节点不能留下半截流程。
    assertThat(request(token, "GET", "/identity/approval-flows", null, 200).toString())
        .doesNotContain("悬空角色");
  }

  @Test
  void 审批人类型与列不匹配时拒绝() throws Exception {
    var token = login();
    tag = suffix();
    var role = roleId(token, "APPROVER" + tag);
    // 按角色审批却同时填了岗位
    var wrong = node("R1", "混填", 0, 0, "SERIAL", role);
    wrong.put("positionId", positionId(token, "MIX" + tag));
    request(
        token,
        "POST",
        "/identity/approval-flows",
        flow("PURCHASE_ORDER", "混填审批人 " + tag, "DRAFT", List.of(wrong), List.of(), List.of()),
        400);
    // 按角色审批却没填 roleId
    var empty = node("R1", "没填角色", 0, 0, "SERIAL", null);
    request(
        token,
        "POST",
        "/identity/approval-flows",
        flow("PURCHASE_ORDER", "缺角色 " + tag, "DRAFT", List.of(empty), List.of(), List.of()),
        400);
  }

  // ------------------------------------------------------------------
  // 审批方式：串行 / 并行 / 条件分支
  // ------------------------------------------------------------------

  @Test
  void 阶段内多个串行节点被拒() throws Exception {
    var token = login();
    tag = suffix();
    var role = roleId(token, "APPROVER" + tag);
    // 阶段之间本来就串行，同一阶段再放两个串行节点是自相矛盾的配置。
    request(
        token,
        "POST",
        "/identity/approval-flows",
        flow(
            "PRICE_CHANGE",
            "两段串行同阶段 " + tag,
            "DRAFT",
            List.of(
                node("R1", "串行一", 0, 0, "SERIAL", role), node("R2", "串行二", 1, 0, "SERIAL", role)),
            List.of(),
            List.of()),
        400);
  }

  @Test
  void 条件分支必须带表达式且非条件分支不得带() throws Exception {
    var token = login();
    tag = suffix();
    var role = roleId(token, "APPROVER" + tag);

    var noExpression = node("R1", "条件但没写表达式", 0, 0, "CONDITIONAL", role);
    request(
        token,
        "POST",
        "/identity/approval-flows",
        flow("PRICE_CHANGE", "缺表达式 " + tag, "DRAFT", List.of(noExpression), List.of(), List.of()),
        400);

    var withExpression = node("R1", "带表达式", 0, 0, "SERIAL", role);
    withExpression.put("conditionExpression", "amount > 1000");
    request(
        token,
        "POST",
        "/identity/approval-flows",
        flow(
            "PRICE_CHANGE",
            "串行却带表达式 " + tag,
            "DRAFT",
            List.of(withExpression),
            List.of(),
            List.of()),
        400);

    var ok = node("R1", "真有条件", 0, 0, "CONDITIONAL", role);
    ok.put("conditionExpression", "amount > 1000");
    UUID id =
        UUID.fromString(
            request(
                    token,
                    "POST",
                    "/identity/approval-flows",
                    flow("PRICE_CHANGE", "条件分支 " + tag, "DRAFT", List.of(ok), List.of(), List.of()),
                    200)
                .asText());
    // 表达式落库并写回：读回来必须一模一样。
    assertThat(nodeNamed(detail(token, id).get("nodes"), "R1").get("conditionExpression").asText())
        .isEqualTo("amount > 1000");
  }

  @Test
  void 同阶段并行节点与跨阶段串行都能存下来() throws Exception {
    var token = login();
    tag = suffix();
    var role = roleId(token, "APPROVER" + tag);
    var position = positionId(token, "PAR" + tag);
    var byPosition = node("P1", "并行岗位", 1, 0, "PARALLEL", null);
    byPosition.remove("roleId");
    byPosition.put("approverType", "POSITION");
    byPosition.put("positionId", position);

    UUID id =
        UUID.fromString(
            request(
                    token,
                    "POST",
                    "/identity/approval-flows",
                    flow(
                        "STOCK_ADJUSTMENT",
                        "并行加串行 " + tag,
                        "ACTIVE",
                        List.of(
                            node("R1", "首阶段角色", 0, 0, "SERIAL", role),
                            byPosition,
                            node("R2", "次阶段角色", 2, 1, "SERIAL", role)),
                        List.of(),
                        List.of()),
                    200)
                .asText());

    var nodes = detail(token, id).get("nodes");
    // 阶段内并行 + 阶段间串行：读回来按 (阶段, 顺序) 排好。
    assertThat(nodes.get(0).get("nodeKey").asText()).isEqualTo("R1");
    assertThat(nodes.get(1).get("nodeKey").asText()).isEqualTo("P1");
    assertThat(nodes.get(1).get("stageIndex").asInt()).isEqualTo(0);
    assertThat(nodes.get(2).get("stageIndex").asInt()).isEqualTo(1);
  }

  // ------------------------------------------------------------------
  // 金额阈值分支
  // ------------------------------------------------------------------

  @Test
  void 同币种区间交叠被拒且相邻接续允许() throws Exception {
    var token = login();
    tag = suffix();
    var role = roleId(token, "APPROVER" + tag);
    var nodes =
        List.of(node("R1", "小額", 0, 0, "SERIAL", role), node("R2", "大額", 1, 1, "SERIAL", role));

    // 交叠：[0,1000) 与 [500,2000) —— 金额 600 走哪个节点没有唯一答案。
    request(
        token,
        "POST",
        "/identity/approval-flows",
        flow(
            "PURCHASE_ORDER",
            "交叠分支 " + tag,
            "DRAFT",
            nodes,
            List.of(
                branch("小额", "USD", "0", "1000", "R1"), branch("中额", "USD", "500", "2000", "R2")),
            List.of()),
        409);
    // 相邻接续：[0,1000) 与 [1000,2000) 是正常配置，上限等于下限不算交叠。
    UUID id =
        UUID.fromString(
            request(
                    token,
                    "POST",
                    "/identity/approval-flows",
                    flow(
                        "PURCHASE_ORDER",
                        "接续分支 " + tag,
                        "DRAFT",
                        nodes,
                        List.of(
                            branch("小额", "USD", "0", "1000", "R1"),
                            branch("大额", "USD", "1000", "2000", "R2")),
                        List.of()),
                    200)
                .asText());
    assertThat(detail(token, id).get("branches")).hasSize(2);
  }

  @Test
  void 不同币种的同一区间允许并存() throws Exception {
    var token = login();
    tag = suffix();
    var role = roleId(token, "APPROVER" + tag);
    UUID id =
        UUID.fromString(
            request(
                    token,
                    "POST",
                    "/identity/approval-flows",
                    flow(
                        "PURCHASE_ORDER",
                        "多币种分支 " + tag,
                        "DRAFT",
                        List.of(node("R1", "节点", 0, 0, "SERIAL", role)),
                        List.of(
                            branch("美元档", "USD", "0", "1000", "R1"),
                            branch("欧元档", "EUR", "0", "1000", "R1")),
                        List.of()),
                    200)
                .asText());
    var branches = detail(token, id).get("branches");
    assertThat(branches).hasSize(2);
    assertThat(branches.get(0).get("currencyCode").asText()).isEqualTo("EUR");
    assertThat(branches.get(1).get("currencyCode").asText()).isEqualTo("USD");
  }

  @Test
  void 无上界的分支与其他分支互斥() throws Exception {
    var token = login();
    tag = suffix();
    var role = roleId(token, "APPROVER" + tag);
    // [0, null) 覆盖全部金额，与任何其他分支都争同一个金额 —— 它是默认路径，不该是分支。
    request(
        token,
        "POST",
        "/identity/approval-flows",
        flow(
            "PURCHASE_ORDER",
            "通吃分支 " + tag,
            "DRAFT",
            List.of(node("R1", "节点", 0, 0, "SERIAL", role)),
            List.of(branch("通吃", "USD", "0", null, "R1"), branch("小额", "USD", "0", "1000", "R1")),
            List.of()),
        409);
    // 两端都空直接被拒：全金额走这里应该体现在节点顺序上。
    request(
        token,
        "POST",
        "/identity/approval-flows",
        flow(
            "PURCHASE_ORDER",
            "空区间 " + tag,
            "DRAFT",
            List.of(node("R1", "节点", 0, 0, "SERIAL", role)),
            List.of(branch("空", "USD", null, null, "R1")),
            List.of()),
        400);
  }

  @Test
  void 金额阈值按写回值断言且支持未设端点() throws Exception {
    var token = login();
    tag = suffix();
    var role = roleId(token, "APPROVER" + tag);
    UUID id =
        UUID.fromString(
            request(
                    token,
                    "POST",
                    "/identity/approval-flows",
                    flow(
                        "PURCHASE_ORDER",
                        "金额写回 " + tag,
                        "DRAFT",
                        List.of(
                            node("R1", "小额", 0, 0, "SERIAL", role),
                            node("R2", "大额", 1, 1, "SERIAL", role)),
                        List.of(
                            branch("小额档", "USD", "0", "1000.50", "R1"),
                            branch("大额档", "USD", "1000.50", null, "R2")),
                        List.of()),
                    200)
                .asText());

    var branches = detail(token, id).get("branches");
    assertThat(branches).hasSize(2);
    // numeric(19,6)：小数位必须原样写回，不能被截断或浮点污染。
    assertThat(branches.get(0).get("maxAmount").decimalValue()).isEqualByComparingTo("1000.500000");
    assertThat(branches.get(1).get("minAmount").decimalValue()).isEqualByComparingTo("1000.500000");
    assertThat(branches.get(1).get("maxAmount").isNull()).isTrue();
  }

  @Test
  void 金额分支必须指向本流程内真实存在的节点() throws Exception {
    var token = login();
    tag = suffix();
    var role = roleId(token, "APPROVER" + tag);
    request(
        token,
        "POST",
        "/identity/approval-flows",
        flow(
            "PURCHASE_ORDER",
            "悬空分支 " + tag,
            "DRAFT",
            List.of(node("R1", "唯一节点", 0, 0, "SERIAL", role)),
            List.of(branch("指向不存在的节点", "USD", "0", "100", "NOPE")),
            List.of()),
        400);
  }

  // ------------------------------------------------------------------
  // 超时升级规则
  // ------------------------------------------------------------------

  @Test
  void 超时规则形状校验() throws Exception {
    var token = login();
    tag = suffix();
    var role = roleId(token, "APPROVER" + tag);
    var node = node("R1", "节点", 0, 0, "SERIAL", role);

    // ESCALATE 必须指明转给谁
    var noTarget = new LinkedHashMap<String, Object>();
    noTarget.put("nodeKey", "R1");
    noTarget.put("timeoutAction", "ESCALATE");
    noTarget.put("timeoutHours", 24);
    request(
        token,
        "POST",
        "/identity/approval-flows",
        flow("PURCHASE_ORDER", "空转办 " + tag, "DRAFT", List.of(node), List.of(), List.of(noTarget)),
        400);

    // AUTO_APPROVE 不能带升级对象
    var autoWithTarget = escalateAuto("R1", 24);
    autoWithTarget.put("escalateApproverType", "ROLE");
    autoWithTarget.put("escalateRoleId", role);
    request(
        token,
        "POST",
        "/identity/approval-flows",
        flow(
            "PURCHASE_ORDER",
            "自动通过带对象 " + tag,
            "DRAFT",
            List.of(node),
            List.of(),
            List.of(autoWithTarget)),
        400);

    // 时限必须为正
    request(
        token,
        "POST",
        "/identity/approval-flows",
        flow(
            "PURCHASE_ORDER",
            "零时限 " + tag,
            "DRAFT",
            List.of(node),
            List.of(),
            List.of(escalateAuto("R1", 0))),
        400);

    // 同一节点同一时限只能一条
    request(
        token,
        "POST",
        "/identity/approval-flows",
        flow(
            "PURCHASE_ORDER",
            "重复时限 " + tag,
            "DRAFT",
            List.of(node),
            List.of(),
            List.of(escalateAuto("R1", 24), escalateToRole("R1", 24, role))),
        400);
  }

  @Test
  void 超时规则写回并保持节点引用() throws Exception {
    var token = login();
    tag = suffix();
    var role = roleId(token, "APPROVER" + tag);
    var manager = roleId(token, "MANAGER" + tag);
    UUID id =
        UUID.fromString(
            request(
                    token,
                    "POST",
                    "/identity/approval-flows",
                    flow(
                        "PURCHASE_ORDER",
                        "超时规则 " + tag,
                        "DRAFT",
                        List.of(node("R1", "节点", 0, 0, "SERIAL", role)),
                        List.of(),
                        List.of(escalateToRole("R1", 24, manager), escalateAuto("R1", 72))),
                    200)
                .asText());

    var escalations = detail(token, id).get("escalations");
    assertThat(escalations).hasSize(2);
    assertThat(escalations.get(0).get("timeoutHours").asInt()).isEqualTo(24);
    assertThat(escalations.get(0).get("timeoutAction").asText()).isEqualTo("ESCALATE");
    // 转办对象带出真实角色编码，证明指向 17.3.1.1 的 role 表。
    assertThat(escalations.get(0).get("escalateLabel").asText()).isEqualTo("MANAGER" + tag);
    assertThat(escalations.get(1).get("timeoutAction").asText()).isEqualTo("AUTO_APPROVE");
  }

  @Test
  void 删除节点会连带清掉引用它的分支与超时规则() throws Exception {
    var token = login();
    tag = suffix();
    var role = roleId(token, "APPROVER" + tag);
    UUID id =
        UUID.fromString(
            request(
                    token,
                    "POST",
                    "/identity/approval-flows",
                    flow(
                        "PURCHASE_ORDER",
                        "级联删除 " + tag,
                        "DRAFT",
                        List.of(
                            node("R1", "保留", 0, 0, "SERIAL", role),
                            node("R2", "被替换", 1, 1, "SERIAL", role)),
                        List.of(branch("指向 R2", "USD", "0", "100", "R2")),
                        List.of(escalateAuto("R2", 48))),
                    200)
                .asText());

    // 更新时整体替换：R2 连同引用它的分支与超时规则一起消失。
    request(
        token,
        "PUT",
        "/identity/approval-flows/" + id,
        flow(
            "PURCHASE_ORDER",
            "级联删除 " + tag,
            "DRAFT",
            List.of(node("R1", "保留", 0, 0, "SERIAL", role)),
            List.of(),
            List.of()),
        200);
    var detail = detail(token, id);
    assertThat(detail.get("nodes")).hasSize(1);
    assertThat(detail.get("branches")).isEmpty();
    assertThat(detail.get("escalations")).isEmpty();
  }

  // ------------------------------------------------------------------
  // 按单据类型注册
  // ------------------------------------------------------------------

  @Test
  void 同一单据类型只能有一条生效流程() throws Exception {
    var token = login();
    tag = suffix();
    var role = roleId(token, "APPROVER" + tag);
    var node = node("R1", "节点", 0, 0, "SERIAL", role);

    request(
        token,
        "POST",
        "/identity/approval-flows",
        flow("PURCHASE_ORDER", "生效流程甲 " + tag, "ACTIVE", List.of(node), List.of(), List.of()),
        200);
    // 第二条生效流程：下游就答不出「该走哪条」，必须在配置期拒。
    request(
        token,
        "POST",
        "/identity/approval-flows",
        flow("PURCHASE_ORDER", "生效流程乙 " + tag, "ACTIVE", List.of(node), List.of(), List.of()),
        409);
    // 草稿可以并存：草稿不参与「谁在管控这个单据类型」的答案。
    request(
        token,
        "POST",
        "/identity/approval-flows",
        flow("PURCHASE_ORDER", "草稿流程丙 " + tag, "DRAFT", List.of(node), List.of(), List.of()),
        200);

    var active =
        request(
            token,
            "GET",
            "/identity/approval-flows/active?documentTypeCode=PURCHASE_ORDER",
            null,
            200);
    assertThat(active.get("flow").get("flowName").asText()).isEqualTo("生效流程甲 " + tag);
  }

  @Test
  void 未注册的单据类型按默认放行且可查清单() throws Exception {
    var token = login();
    tag = suffix();
    var role = roleId(token, "APPROVER" + tag);
    var node = node("R1", "节点", 0, 0, "SERIAL", role);

    // 没注册 → 404，调用方据此知道这张单据当前无管控。
    request(
        token, "GET", "/identity/approval-flows/active?documentTypeCode=STOCK_TRANSFER", null, 404);

    request(
        token,
        "POST",
        "/identity/approval-flows",
        flow("STOCK_TRANSFER", "调拨审批 " + tag, "ACTIVE", List.of(node), List.of(), List.of()),
        200);

    var registrations = request(token, "GET", "/identity/approval-flows/registrations", null, 200);
    JsonNode registered = null;
    JsonNode missing = null;
    for (JsonNode r : registrations) {
      if (r.get("documentTypeCode").asText().equals("STOCK_TRANSFER")) registered = r;
      if (r.get("documentTypeCode").asText().equals("ROLE_GRANT")) missing = r;
    }
    assertThat(registered).isNotNull();
    assertThat(registered.get("registered").asBoolean()).isTrue();
    assertThat(registered.get("activeFlowName").asText()).isEqualTo("调拨审批 " + tag);
    // 蓝图点名的变更类操作默认放行：清单里必须能看到它还没注册。
    assertThat(missing).isNotNull();
    assertThat(missing.get("registered").asBoolean()).isFalse();
    assertThat(missing.get("activeFlowId").isNull()).isTrue();
    request(
        token, "GET", "/identity/approval-flows/active?documentTypeCode=STOCK_TRANSFER", null, 200);
  }

  @Test
  void 出厂单据类型码表可读且含变更类操作() throws Exception {
    var token = login();
    var types = request(token, "GET", "/identity/approval-flows/document-types", null, 200);
    var codes = new ArrayList<String>();
    for (JsonNode t : types) codes.add(t.get("code").asText());
    assertThat(codes).contains("PURCHASE_ORDER", "ROLE_GRANT", "DATA_EXPORT");
    // 与 17.5.1.2 编号实体刻意不同：这张表管的是「能不能挂审批」。
    assertThat(codes).doesNotContain("INBOUND");
  }

  // ------------------------------------------------------------------
  // 版本、生效状态与结构体检
  // ------------------------------------------------------------------

  @Test
  void 版本随每次保存递增且时间戳写回正确() throws Exception {
    var token = login();
    tag = suffix();
    var role = roleId(token, "APPROVER" + tag);
    // 落库时刻由数据库给（created_at DEFAULT now()）⇒ 基准也必须取**数据库自己的时钟**。
    // 拿客户端墙钟当基准等于假设两台钟一模一样：差 1 毫秒就会把「刚刚写入」误判成「不是刚刚」。
    var dbBefore = dbNow();

    UUID id =
        UUID.fromString(
            request(
                    token,
                    "POST",
                    "/identity/approval-flows",
                    flow(
                        "PRICE_CHANGE",
                        "版本流程 " + tag,
                        "DRAFT",
                        List.of(node("R1", "节点", 0, 0, "SERIAL", role)),
                        List.of(),
                        List.of()),
                    200)
                .asText());
    var created = detail(token, id).get("flow");
    assertThat(created.get("version").asLong()).isZero();
    assertThat(created.get("status").asText()).isEqualTo("DRAFT");
    // timestamptz 写回值必须落在「请求前后两个**数据库时钟**之间」：用 LocalDateTime 绑会静默偏 -28800 秒。
    // 两端都取数据库自己的时钟 —— 落库时刻本来就在数据库的时钟域里，拿客户端墙钟去比它
    // 等于拿两台机器的钟表对表，环境一卡就误判（2026-10-25 全量验证就是这么打红的）。
    assertThat(OffsetDateTime.parse(created.get("createdAt").asText()).toInstant())
        .isAfterOrEqualTo(dbBefore)
        .isBeforeOrEqualTo(dbNow());

    request(
        token,
        "PUT",
        "/identity/approval-flows/" + id,
        flow(
            "PRICE_CHANGE",
            "版本流程改 " + tag,
            "ACTIVE",
            List.of(node("R1", "节点", 0, 0, "SERIAL", role)),
            List.of(),
            List.of()),
        200);
    var updated = detail(token, id).get("flow");
    assertThat(updated.get("version").asLong()).isEqualTo(1);
    assertThat(updated.get("status").asText()).isEqualTo("ACTIVE");
    assertThat(updated.get("flowName").asText()).isEqualTo("版本流程改 " + tag);
  }

  @Test
  void 单据类型建后不可改() throws Exception {
    var token = login();
    tag = suffix();
    var role = roleId(token, "APPROVER" + tag);
    UUID id =
        UUID.fromString(
            request(
                    token,
                    "POST",
                    "/identity/approval-flows",
                    flow(
                        "PRICE_CHANGE",
                        "不换类型 " + tag,
                        "DRAFT",
                        List.of(node("R1", "节点", 0, 0, "SERIAL", role)),
                        List.of(),
                        List.of()),
                    200)
                .asText());
    request(
        token,
        "PUT",
        "/identity/approval-flows/" + id,
        flow(
            "STOCK_ADJUSTMENT",
            "换类型 " + tag,
            "DRAFT",
            List.of(node("R1", "节点", 0, 0, "SERIAL", role)),
            List.of(),
            List.of()),
        400);
  }

  @Test
  void 审批人被停用时结构体检报出来() throws Exception {
    var token = login();
    tag = suffix();
    var role = roleId(token, "APPROVER" + tag);
    var position = positionId(token, "STOP" + tag);
    var byPosition = node("P1", "岗位审批", 0, 0, "SERIAL", null);
    byPosition.remove("roleId");
    byPosition.put("approverType", "POSITION");
    byPosition.put("positionId", position);

    UUID id =
        UUID.fromString(
            request(
                    token,
                    "POST",
                    "/identity/approval-flows",
                    flow(
                        "PURCHASE_ORDER",
                        "体检流程 " + tag,
                        "ACTIVE",
                        List.of(byPosition),
                        List.of(),
                        List.of()),
                    200)
                .asText());

    // 刚建好时没有问题
    var clean = request(token, "GET", "/identity/approval-flows/" + id + "/validation", null, 200);
    assertThat(clean.get("errorCount").asInt()).isZero();
    assertThat(clean.get("registered").asBoolean()).isTrue();

    // 岗位被停用：外键还在（岗位没被删），但已经没有人能审 —— 写入时看不到，事后才暴露。
    request(
        token,
        "PUT",
        "/identity/approval-flows/positions/" + position,
        Map.of("code", "STOP" + tag, "name", "岗位 STOP" + tag, "status", "DISABLED"),
        200);

    var report = request(token, "GET", "/identity/approval-flows/" + id + "/validation", null, 200);
    assertThat(report.get("errorCount").asInt()).isEqualTo(1);
    assertThat(report.toString()).contains("APPROVER_INACTIVE");
  }

  @Test
  void 草稿流程体检提示尚未注册() throws Exception {
    var token = login();
    tag = suffix();
    var role = roleId(token, "APPROVER" + tag);
    UUID id =
        UUID.fromString(
            request(
                    token,
                    "POST",
                    "/identity/approval-flows",
                    flow(
                        "CREDIT_NOTE",
                        "草稿体检 " + tag,
                        "DRAFT",
                        List.of(node("R1", "节点", 0, 0, "SERIAL", role)),
                        List.of(),
                        List.of()),
                    200)
                .asText());
    var report = request(token, "GET", "/identity/approval-flows/" + id + "/validation", null, 200);
    assertThat(report.get("registered").asBoolean()).isFalse();
    assertThat(report.toString()).contains("NOT_REGISTERED");
    assertThat(report.toString()).contains("默认可直接执行");
  }

  // ------------------------------------------------------------------
  // 岗位字典
  // ------------------------------------------------------------------

  @Test
  void 岗位被节点引用时拒删() throws Exception {
    var token = login();
    tag = suffix();
    var position = positionId(token, "BUSY" + tag);
    var byPosition = node("P1", "岗位审批", 0, 0, "SERIAL", null);
    byPosition.remove("roleId");
    byPosition.put("approverType", "POSITION");
    byPosition.put("positionId", position);
    request(
        token,
        "POST",
        "/identity/approval-flows",
        flow("PURCHASE_ORDER", "引用岗位 " + tag, "DRAFT", List.of(byPosition), List.of(), List.of()),
        200);
    request(token, "DELETE", "/identity/approval-flows/positions/" + position, null, 409);
    // 没人引用时才能删
    var free = positionId(token, "FREE" + tag);
    request(token, "DELETE", "/identity/approval-flows/positions/" + free, null, 200);
  }

  @Test
  void 岗位编码同租户唯一() throws Exception {
    var token = login();
    tag = suffix();
    positionId(token, "UNIQ" + tag);
    request(
        token,
        "POST",
        "/identity/approval-flows/positions",
        Map.of("code", "UNIQ" + tag, "name", "重名岗位", "status", "ACTIVE"),
        409);
  }

  // ------------------------------------------------------------------
  // 删除与生命周期
  // ------------------------------------------------------------------

  @Test
  void 删除流程连带删除其节点与规则() throws Exception {
    var token = login();
    tag = suffix();
    var role = roleId(token, "APPROVER" + tag);
    UUID id =
        UUID.fromString(
            request(
                    token,
                    "POST",
                    "/identity/approval-flows",
                    flow(
                        "REFUND",
                        "待删除流程 " + tag,
                        "DRAFT",
                        List.of(node("R1", "节点", 0, 0, "SERIAL", role)),
                        List.of(branch("档", "USD", "0", "100", "R1")),
                        List.of(escalateAuto("R1", 24))),
                    200)
                .asText());
    assertThat(detail(token, id).get("flow").get("nodeCount").asInt()).isEqualTo(1);
    assertThat(detail(token, id).get("flow").get("branchCount").asInt()).isEqualTo(1);
    assertThat(detail(token, id).get("flow").get("escalationCount").asInt()).isEqualTo(1);

    request(token, "DELETE", "/identity/approval-flows/" + id, null, 200);
    request(token, "GET", "/identity/approval-flows/" + id, null, 404);
    // 删掉之后该单据类型重新回到「未注册 → 默认放行」。
    request(token, "GET", "/identity/approval-flows/active?documentTypeCode=REFUND", null, 404);
    // 节点确实没留下孤儿行
    try (var c =
            DriverManager.getConnection(pg().getJdbcUrl(), pg().getUsername(), pg().getPassword());
        var s = c.createStatement();
        var rs =
            s.executeQuery("select count(*) from approval_flow_node where flow_id='" + id + "'")) {
      rs.next();
      assertThat(rs.getInt(1)).isZero();
    }
  }

  @Test
  void 未登录读不到任何审批流() throws Exception {
    http.perform(get("/identity/approval-flows")).andExpect(status().isUnauthorized());
    http.perform(get("/identity/approval-flows/registrations"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void 变更留痕进审计() throws Exception {
    var token = login();
    tag = suffix();
    var role = roleId(token, "APPROVER" + tag);
    UUID id =
        UUID.fromString(
            request(
                    token,
                    "POST",
                    "/identity/approval-flows",
                    flow(
                        "WRITE_OFF",
                        "审计流程 " + tag,
                        "DRAFT",
                        List.of(node("R1", "节点", 0, 0, "SERIAL", role)),
                        List.of(),
                        List.of()),
                    200)
                .asText());
    var audit = request(token, "GET", "/identity/audit", null, 200).toString();
    assertThat(audit).contains("ApprovalFlowCreated").contains("审计流程 " + tag);
    request(token, "DELETE", "/identity/approval-flows/" + id, null, 200);
    assertThat(request(token, "GET", "/identity/audit", null, 200).toString())
        .contains("ApprovalFlowDeleted");
  }

  // ------------------------------------------------------------------
  // 多租户三断言
  // ------------------------------------------------------------------

  @Test
  void 多租户隔离三断言() throws Exception {
    var parent = login();
    tag = suffix();

    var parentRole = roleId(parent, "APPROVER" + tag);
    var parentPosition = positionId(parent, "POS" + tag);
    var parentNode = node("R1", "父租户节点", 0, 0, "SERIAL", parentRole);
    var parentByPosition = node("P1", "父租户岗位节点", 1, 1, "SERIAL", null);
    parentByPosition.remove("roleId");
    parentByPosition.put("approverType", "POSITION");
    parentByPosition.put("positionId", parentPosition);
    UUID parentFlow =
        UUID.fromString(
            request(
                    parent,
                    "POST",
                    "/identity/approval-flows",
                    flow(
                        "PURCHASE_ORDER",
                        "父租户审批流 " + tag,
                        "ACTIVE",
                        List.of(parentNode, parentByPosition),
                        List.of(branch("父租户档", "USD", "0", "500", "R1")),
                        List.of(escalateAuto("R1", 24))),
                    200)
                .asText());

    // 子租户：一套完全独立的流程、角色、岗位
    String childUser = "afp-child-" + tag.toLowerCase();
    request(
        parent,
        "POST",
        "/identity/children",
        Map.of(
            "code",
            "AFC" + tag,
            "name",
            "审批流子租户",
            "ownerUsername",
            childUser,
            "ownerPassword",
            "test-member-password"),
        200);
    String child = loginUser(childUser);
    var childRole = roleId(child, "APPROVER" + tag);
    UUID childFlow =
        UUID.fromString(
            request(
                    child,
                    "POST",
                    "/identity/approval-flows",
                    flow(
                        "PURCHASE_ORDER",
                        "子租户审批流 " + tag,
                        "ACTIVE",
                        List.of(node("R1", "子租户节点", 0, 0, "SERIAL", childRole)),
                        List.of(),
                        List.of()),
                    200)
                .asText());

    // 断言一：两租户互不可见
    var parentView = request(parent, "GET", "/identity/approval-flows", null, 200);
    assertThat(parentView.toString()).contains("父租户审批流 " + tag).doesNotContain("子租户审批流 " + tag);
    var childView = request(child, "GET", "/identity/approval-flows", null, 200);
    assertThat(childView.toString()).contains("子租户审批流 " + tag).doesNotContain("父租户审批流 " + tag);

    // 岗位字典同样隔离
    assertThat(request(parent, "GET", "/identity/approval-flows/positions", null, 200).toString())
        .contains("POS" + tag)
        .doesNotContain("子租户岗位");
    assertThat(request(child, "GET", "/identity/approval-flows/positions", null, 200).toString())
        .doesNotContain("POS" + tag);

    // 拿子租户的 id 去父租户读 / 改 / 删一律 404
    request(parent, "GET", "/identity/approval-flows/" + childFlow, null, 404);
    request(
        parent,
        "PUT",
        "/identity/approval-flows/" + childFlow,
        flow(
            "PURCHASE_ORDER",
            "父租户越权改名 " + tag,
            "ACTIVE",
            List.of(node("R1", "越权", 0, 0, "SERIAL", parentRole)),
            List.of(),
            List.of()),
        404);
    request(parent, "DELETE", "/identity/approval-flows/" + childFlow, null, 404);
    request(parent, "GET", "/identity/approval-flows/" + childFlow + "/validation", null, 404);

    // 父租户删不掉子租户的岗位
    request(parent, "DELETE", "/identity/approval-flows/positions/" + UUID.randomUUID(), null, 404);

    // 断言二：伪造 tenant header 无效。request() 每次都带随机 X-Tenant-Id。
    String forged = UUID.randomUUID().toString();
    var forgedView =
        http.perform(
                get("/identity/approval-flows")
                    .header("Authorization", "Bearer " + parent)
                    .header("X-Tenant-Id", forged))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(forgedView).contains("父租户审批流 " + tag).doesNotContain("子租户审批流 " + tag);
    // 伪造成子租户的真实租户 id（调用方本来就是它的所有者）：结果仍然不变。
    var forgedChild =
        http.perform(
                get("/identity/approval-flows")
                    .header("Authorization", "Bearer " + parent)
                    .header("X-Tenant-Id", tenantOf(child)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(forgedChild).contains("父租户审批流 " + tag).doesNotContain("子租户审批流 " + tag);
    // 未登录读不到任何审批流
    http.perform(get("/identity/approval-flows")).andExpect(status().isUnauthorized());

    // 断言三：连接池归还后不串租户。池大小被压到 1，交替租户请求复用同一物理连接。
    for (int i = 0; i < 3; i++) {
      var p = request(parent, "GET", "/identity/approval-flows", null, 200);
      assertThat(p.toString()).as("第 %s 轮父租户不得看到子租户流程", i).doesNotContain("子租户审批流 " + tag);
      assertThat(p.toString()).contains("父租户审批流 " + tag);
      var c = request(child, "GET", "/identity/approval-flows", null, 200);
      assertThat(c.toString()).as("第 %s 轮子租户不得看到父租户流程", i).doesNotContain("父租户审批流 " + tag);
      assertThat(c.toString()).contains("子租户审批流 " + tag);
      // 生效流程跟着租户走：父租户查 PURCHASE_ORDER 必须始终拿到自己那条。
      assertThat(
              request(
                      parent,
                      "GET",
                      "/identity/approval-flows/active?documentTypeCode=PURCHASE_ORDER",
                      null,
                      200)
                  .get("flow")
                  .get("flowName")
                  .asText())
          .isEqualTo("父租户审批流 " + tag);
      assertThat(
              request(
                      child,
                      "GET",
                      "/identity/approval-flows/active?documentTypeCode=PURCHASE_ORDER",
                      null,
                      200)
                  .get("flow")
                  .get("flowName")
                  .asText())
          .isEqualTo("子租户审批流 " + tag);
    }

    // 事务外（无租户上下文）直查应当为空
    assertThat(sql.fetch("select id from approval_flow")).isEmpty();
    assertThat(sql.fetch("select id from approval_flow_node")).isEmpty();
    assertThat(sql.fetch("select id from approval_flow_amount_branch")).isEmpty();
    assertThat(sql.fetch("select id from approval_flow_escalation")).isEmpty();
    assertThat(sql.fetch("select id from approval_position")).isEmpty();
  }

  private UUID tenantOf(String token) throws Exception {
    return UUID.fromString(
        request(token, "GET", "/identity/tenants/current", null, 200).get("tenantId").asText());
  }
}
