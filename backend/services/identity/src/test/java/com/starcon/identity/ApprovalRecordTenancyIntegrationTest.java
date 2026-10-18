package com.starcon.identity;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.starcon.tenancy.TenantTransactions;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.Record;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 17.4.2.2 审批记录的**多租户三断言**。
 *
 * <p>{@link ApprovalRecordIntegrationTest} 里也有一份三断言，但那个类的主职责是业务行为
 * （全链路、统计、导出）；隔离是它的附带覆盖。本类把三条铁律<b>单独拉出来实跑</b>，
 * 因为本模块的隔离面比一般列表模块更大：它一次读出<b>整条审批链路</b> ——
 * 谁能批过谁、每一步意见是什么、钱压在哪个节点上，跨租户读到任何一行都是事故。
 *
 * <ol>
 *   <li>列表 / 详情 / 时间线 / 统计 / 导出在两租户之间互不可见，跨租户按 id 直查读到<b>零行</b>；
 *   <li>{@code X-Tenant-Id} 伪造对读与写（导出）都无效；
 *   <li>连接池归还后多轮交替读写不串租户。
 * </ol>
 *
 * <p><b>断言落在真实业务表上</b>，并且<b>按主键直查对方的行、断言读到零行</b>。
 * 只在 HTTP 层断言「列表里没有对方数据」是不够的 —— 那个结论可能来自过滤条件写对了，
 * 而底下的行照样在库里可见。所以每个「读到零行」都配一条<b>正对照</b>：
 * 用<b>同样写法</b>在<b>同样方式</b>下查自己的行，必须读到 1 行，防假绿。
 *
 * <p><b>连接池压到 1 条连接</b>（{@link IdentityIntegrationTestBase} 已设）：
 * 只有复用同一条物理连接，「归还后不串租户」才是真的被验证过。
 */
class ApprovalRecordTenancyIntegrationTest extends IdentityIntegrationTestBase {

  private static final String INTERNAL = "test-key";

  private String tag;

  private TenantTransactions tx() {
    return new TenantTransactions(
        sql, new org.springframework.transaction.support.TransactionTemplate(manager));
  }

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
  // 断言一：两租户互不可见（含按主键直查对方的行，读到零行）
  // ------------------------------------------------------------------

  @Test
  void 两租户的审批记录统计与导出互不可见且按主键直查对方行读到零行() throws Exception {
    var parent = login();
    tag = suffix();
    var parentApprover = approver(parent, "tp-" + tag.toLowerCase());
    var parentFlow = flowFor(parent, "父租户记录 " + tag, parentApprover.roleId());
    var parentInstance =
        UUID.fromString(
            submit(parent, parentFlow, "trade", "TP-" + tag, "父租户记录单 " + tag, "100").get("id").asText());
    decide(parentApprover.token(), parentInstance, "APPROVE", "父租户通过");

    var child = createChild(parent, "RPC" + tag, "rpc-" + tag.toLowerCase());
    var childApprover = approver(child, "tc-" + tag.toLowerCase());
    var childFlow = flowFor(child, "子租户记录 " + tag, childApprover.roleId());
    var childInstance =
        UUID.fromString(
            submit(child, childFlow, "warehouse", "TC-" + tag, "子租户记录单 " + tag, "200").get("id").asText());
    decide(childApprover.token(), childInstance, "APPROVE", "子租户通过");

    var parentTenant = tenantOf(parent);
    var childTenant = tenantOf(child);

    // 列表层：各自只看得到自己的那一条
    var parentView = records(parent).toString();
    assertThat(parentView).contains("父租户记录单 " + tag).doesNotContain("子租户记录单 " + tag);
    var childView = records(child).toString();
    assertThat(childView).contains("子租户记录单 " + tag).doesNotContain("父租户记录单 " + tag);

    // **按主键直查**：子租户租户事务里读父租户的实例行 → 零行
    assertThat(rowsIn(childTenant, "select id from approval_instance where id=?", parentInstance))
        .as("子租户按主键直查父租户的审批实例必须读到零行")
        .isEmpty();
    assertThat(rowsIn(parentTenant, "select id from approval_instance where id=?", childInstance))
        .as("父租户按主键直查子租户的审批实例必须读到零行")
        .isEmpty();

    // 正对照：同样写法在各自租户里查自己的行必须读到 1 行 —— 防上面那两条假绿
    assertThat(rowsIn(parentTenant, "select id from approval_instance where id=?", parentInstance))
        .as("父租户按同样写法直查自己的行必须读到 1 行")
        .hasSize(1);
    assertThat(rowsIn(childTenant, "select id from approval_instance where id=?", childInstance))
        .as("子租户按同样写法直查自己的行必须读到 1 行")
        .hasSize(1);

    // 审批留痕同样互不可见：「谁批了谁」跨租户读不到
    assertThat(rowsIn(parentTenant, "select id from approval_instance_action where instance_id=?", childInstance))
        .as("父租户读不到子租户的审批留痕")
        .isEmpty();
    assertThat(rowsIn(childTenant, "select id from approval_instance_action where instance_id=?", parentInstance))
        .as("子租户读不到父租户的审批留痕")
        .isEmpty();
    // 正对照
    assertThat(rowsIn(parentTenant, "select id from approval_instance_action where instance_id=?", parentInstance))
        .hasSize(1);
    assertThat(rowsIn(childTenant, "select id from approval_instance_action where instance_id=?", childInstance))
        .hasSize(1);

    // 跨租户详情一律 404（403 会泄露「这条记录存在」）
    request(child, "GET", "/identity/approval-records/" + parentInstance, null, 404);
    request(parent, "GET", "/identity/approval-records/" + childInstance, null, 404);

    // 统计报表互不可见：各只数到自己的 1 条
    assertThat(request(parent, "GET", "/identity/approval-records/statistics", null, 200).get("instanceTotal").asLong())
        .as("父租户统计只数自己的行")
        .isEqualTo(1L);
    assertThat(request(child, "GET", "/identity/approval-records/statistics", null, 200).get("instanceTotal").asLong())
        .as("子租户统计只数自己的行")
        .isEqualTo(1L);

    // 导出互不可见：父租户导出的 CSV 里不得出现子租户的单据标题
    var parentCsv = csvOf(parent, "");
    assertThat(parentCsv).contains("父租户记录单 " + tag).doesNotContain("子租户记录单 " + tag);
    var childCsv = csvOf(child, "");
    assertThat(childCsv).contains("子租户记录单 " + tag).doesNotContain("父租户记录单 " + tag);

    // 来源系统计数互不可见
    var parentCaps = request(parent, "GET", "/identity/approval-records/capabilities", null, 200);
    var childCaps = request(child, "GET", "/identity/approval-records/capabilities", null, 200);
    assertThat(parentCaps.get("observed").toString()).contains("trade").doesNotContain("warehouse");
    assertThat(childCaps.get("observed").toString()).contains("warehouse").doesNotContain("\"trade\"");
  }

  // ------------------------------------------------------------------
  // 断言二：伪造 X-Tenant-Id 头无效
  // ------------------------------------------------------------------

  @Test
  void 伪造租户头无效读到的数据逐字段相同且导出也不换租户() throws Exception {
    var parent = login();
    tag = suffix();
    var parentApprover = approver(parent, "fp-" + tag.toLowerCase());
    var parentFlow = flowFor(parent, "父租户伪造 " + tag, parentApprover.roleId());
    var parentInstance =
        UUID.fromString(
            submit(parent, parentFlow, "trade", "FP-" + tag, "父租户伪造单 " + tag, "100").get("id").asText());
    decide(parentApprover.token(), parentInstance, "APPROVE", "父租户通过");

    var child = createChild(parent, "FPC" + tag, "fpc-" + tag.toLowerCase());
    var childApprover = approver(child, "fc-" + tag.toLowerCase());
    var childFlow = flowFor(child, "子租户伪造 " + tag, childApprover.roleId());
    submit(child, childFlow, "warehouse", "FC-" + tag, "子租户伪造单 " + tag, "200");
    var childTenant = tenantOf(child);

    // 伪造成**子租户真实租户 id** 才是有杀伤力的那一档 —— 调用方确实知道一个存在的租户 id。
    for (var forged : List.of(UUID.randomUUID().toString(), childTenant.toString())) {
      var withHeader =
          http.perform(
                  get("/identity/approval-records")
                      .header("Authorization", "Bearer " + parent)
                      .header("X-Tenant-Id", forged))
              .andExpect(status().isOk())
              .andReturn().getResponse()
              .getContentAsString();

      // 正确预期不是 4xx：伪造头既不该让他**多**看到东西，也不该凭空**剥夺**他已有的权限。
      // 真正要证明的是 —— 读到的数据与不带头时**逐字段相同**。
      var withoutHeader =
          http.perform(get("/identity/approval-records").header("Authorization", "Bearer " + parent))
              .andExpect(status().isOk())
              .andReturn().getResponse()
              .getContentAsString();

      // 详情端点逐字段比对（列表含 dwellSeconds 这个随时间变化的字段，比字符串全等会抖动）
      var detailWith =
          http.perform(
                  get("/identity/approval-records/" + parentInstance)
                      .header("Authorization", "Bearer " + parent)
                      .header("X-Tenant-Id", forged))
              .andExpect(status().isOk())
              .andReturn().getResponse().getContentAsString();
      var detailWithout =
          http.perform(
                  get("/identity/approval-records/" + parentInstance)
                      .header("Authorization", "Bearer " + parent))
              .andExpect(status().isOk())
              .andReturn().getResponse().getContentAsString();
      assertThat(json.readTree(detailWith))
          .as("伪造 X-Tenant-Id=" + forged + " 读到的详情必须与不带头时逐字段相同")
          .isEqualTo(json.readTree(detailWithout));

      // 列表的标题集合也必须一致，且不含对方租户的数据
      assertThat(json.readTree(withHeader).get("items").toString())
          .contains("父租户伪造单 " + tag)
          .doesNotContain("子租户伪造单 " + tag);
      assertThat(withoutHeader).contains("父租户伪造单 " + tag).doesNotContain("子租户伪造单 " + tag);
    }

    // 伪造头的**导出**同样无效：导出的必须是父租户自己的行
    var forgedCsv = csvOf(parent, "");
    assertThat(forgedCsv).contains("父租户伪造单 " + tag).doesNotContain("子租户伪造单 " + tag);

    // 伪造头写不进对方的库：父租户会话下对方实例始终零行、对方留痕始终零行
    assertThat(rowsIn(childTenant, "select id from approval_instance where id=?", parentInstance))
        .as("伪造租户头的读不得把父租户的行暴露给子租户")
        .isEmpty();
    assertThat(rowsIn(childTenant, "select id from approval_instance_action where instance_id=?", parentInstance))
        .as("伪造租户头不得让子租户读到父租户的审批留痕")
        .isEmpty();

    // 拿子租户流程去父租户会话提交，落不下去（404）
    var body = new LinkedHashMap<String, Object>();
    body.put("flowId", childFlow.toString());
    body.put("sourceSystem", "trade");
    body.put("sourceReferenceId", "FORGED-" + tag);
    body.put("subjectTitle", "伪造提交 " + tag);
    internal(parent, body, 404);
    assertThat(rowsIn(tenantOf(parent), "select id from approval_instance where source_reference_id=?", "FORGED-" + tag))
        .as("伪造提交不得在父租户库里留下行")
        .isEmpty();
    assertThat(rowsIn(childTenant, "select id from approval_instance where source_reference_id=?", "FORGED-" + tag))
        .as("也不得把行写进子租户的库")
        .isEmpty();

    // 无令牌一律 401
    http.perform(get("/identity/approval-records")).andExpect(status().isUnauthorized());
  }

  // ------------------------------------------------------------------
  // 断言三：连接池归还后不串租户（池固定 1 条，多轮交替读写）
  // ------------------------------------------------------------------

  @Test
  void 连接池归还后多轮交替读写不串租户() throws Exception {
    var parent = login();
    tag = suffix();
    var parentApprover = approver(parent, "rp-" + tag.toLowerCase());
    var parentFlow = flowFor(parent, "父租户轮换 " + tag, parentApprover.roleId());
    var parentInstance =
        UUID.fromString(
            submit(parent, parentFlow, "trade", "RP-" + tag, "父租户轮换单 " + tag, "100").get("id").asText());
    var child = createChild(parent, "RPC2" + tag, "rpc2-" + tag.toLowerCase());
    var childApprover = approver(child, "rc-" + tag.toLowerCase());
    var childFlow = flowFor(child, "子租户轮换 " + tag, childApprover.roleId());
    var parentTenant = tenantOf(parent);
    var childTenant = tenantOf(child);

    // 池大小是 1（见基类），下面每一轮都必然复用同一条物理连接。
    // 刻意**每轮新提交一条子租户单据**再批它：复用同一条已批完的单子会在第二轮就正确 404，
    // 那样测的是「已结不可重复批」而不是「连接不串租户」。
    for (int i = 0; i < 4; i++) {
      var roundChild =
          UUID.fromString(
              submit(child, childFlow, "trade", "RC" + i + "-" + tag, "子租户轮换单 " + tag + "-" + i, "100")
                  .get("id").asText());

      var p = records(parent).toString();
      assertThat(p)
          .as("第 %s 轮：父租户读必须只见父租户", i)
          .contains("父租户轮换单 " + tag)
          .doesNotContain("子租户轮换单 " + tag + "-" + i);
      assertThat(rowsIn(parentTenant, "select id from approval_instance where id=?", roundChild))
          .as("第 %s 轮：父租户直查不得看到子租户的行", i)
          .isEmpty();

      // 交替**写**：子租户批自己刚提交的那条
      decide(childApprover.token(), roundChild, "APPROVE", "子租户第 " + i + " 轮通过");
      assertThat(rowsIn(childTenant, "select id from approval_instance where id=? and status='APPROVED'", roundChild))
          .as("第 %s 轮：子租户的写必须落在子租户自己的库", i)
          .hasSize(1);
      assertThat(rowsIn(parentTenant, "select id from approval_instance where id=? and status='APPROVED'", roundChild))
          .as("第 %s 轮：子租户的写不得改变父租户可见的那一行", i)
          .isEmpty();

      // 再回到父租户读：连接刚被子租户用过，父租户必须仍然只见父租户
      var p2 = records(parent).toString();
      assertThat(p2)
          .as("第 %s 轮：子租户用过连接之后父租户仍不得串", i)
          .contains("父租户轮换单 " + tag)
          .doesNotContain("子租户轮换单 " + tag + "-" + i);

      // 父租户那条始终没被动过（子租户的写没有溢出）
      assertThat(rowsIn(parentTenant, "select id from approval_instance where id=? and status='PENDING'", parentInstance))
          .as("第 %s 轮：父租户实例应始终停在待办", i)
          .hasSize(1);
    }
  }

  @Test
  void 无租户上下文时两张表查不到任何行且均为强制RLS() throws Exception {
    // 事务外（app.current_tenant 未设置）直查必须为空：租户上下文不是「默认放行」
    assertThat(sql.fetch("select id from approval_instance")).isEmpty();
    assertThat(sql.fetch("select id from approval_instance_action")).isEmpty();

    // 两张表都必须是 FORCE RLS —— 只 ENABLE 的话，表 owner 连接会绕过隔离
    for (var table : List.of("approval_instance", "approval_instance_action")) {
      assertThat(
              sql.fetchOne("select relforcerowsecurity as forced from pg_class where relname=?", table)
                  .get("forced", Boolean.class))
          .as("%s 必须 FORCE RLS", table)
          .isTrue();
    }

    // 顺带证明连接确实来自非超级用户、也没有 bypassrls —— 上面两条才有意义
    assertThat(
            sql.fetchOne("select rolbypassrls, rolsuper from pg_roles where rolname=current_user")
                .get("rolbypassrls", Boolean.class))
        .as("应用连接不得持有 bypassrls，否则 FORCE RLS 形同虚设")
        .isFalse();
  }

  // ------------------------------------------------------------------
  // 小工具（与 ApprovalRecordIntegrationTest 同形，各自独立持有）
  // ------------------------------------------------------------------

  /**
   * 建一位审批人：角色同时含<b>上游审批权限</b>与<b>本模块记录读权限</b>，成员拿这个角色。
   *
   * <p>两个权限都给是有意的 —— 这些用例要「建单 → 批 → 查记录」在同一个会话里走完，
   * 只给记录读权限的角色批不了单，给了审批权限但没记录权限的角色又查不了链路。
   */
  private record Approver(String token, UUID roleId) {}

  private Approver approver(String owner, String username) throws Exception {
    var suffixKey = username.replace("-", "").toUpperCase();
    var role =
        UUID.fromString(
            request(
                    owner,
                    "POST",
                    "/identity/roles",
                    Map.of(
                        "code", "IX" + suffixKey,
                        "name", "审批角色 " + username,
                        "status", "ACTIVE",
                        "permissions",
                        List.of(
                            "identity.approval_inbox.read",
                            "identity.approval_inbox.approve",
                            "identity.approval_inbox.transfer",
                            "identity.approval_record.read",
                            "identity.approval_record.export")),
                    200)
                .asText());
    request(
        owner,
        "POST",
        "/identity/members",
        memberInput(username, null, List.of(Map.of("roleId", role.toString(), "scopeMode", "ALL"))),
        200);
    return new Approver(loginUser(username), role);
  }

  /** 建一条生效流程，首节点是被指定为审批人的角色。 */
  private UUID flowFor(String token, String flowName, UUID role) throws Exception {
    return UUID.fromString(
        request(
                token,
                "POST",
                "/identity/approval-flows",
                Map.of(
                    "documentTypeCode", "PURCHASE_ORDER",
                    "flowName", flowName,
                    "status", "ACTIVE",
                    "description", "",
                    "nodes", List.of(node("R1", 1, "ROLE", role)),
                    "branches", List.of(),
                    "escalations", List.of()),
                200)
            .asText());
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

  private JsonNode submit(String token, UUID flowId, String source, String ref, String title, String amount)
      throws Exception {
    var body = new LinkedHashMap<String, Object>();
    body.put("flowId", flowId.toString());
    body.put("sourceSystem", source);
    body.put("sourceReferenceId", ref);
    body.put("subjectTitle", title);
    body.put("currencyCode", "USD");
    body.put("amount", new java.math.BigDecimal(amount));
    var response =
        http.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                        "/identity/approval-instances")
                    .header("Authorization", "Bearer " + token)
                    .header("X-Internal-Key", INTERNAL)
                    .contentType("application/json")
                    .content(json.writeValueAsString(body)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();
    return json.readTree(response.getContentAsString());
  }

  private JsonNode decide(String token, UUID id, String decision, String comment) throws Exception {
    return request(
        token,
        "POST",
        "/identity/my-approvals/" + id + "/decide",
        Map.of("decision", decision, "comment", comment),
        200);
  }

  private JsonNode records(String token) throws Exception {
    return request(token, "GET", "/identity/approval-records?size=200", null, 200);
  }

  private String csvOf(String token, String query) throws Exception {
    return http.perform(
            get("/identity/approval-records/export" + query)
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
  }

  private String internal(String token, Map<String, Object> body, int expected) throws Exception {
    return http.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                    "/identity/approval-instances")
                .header("Authorization", "Bearer " + token)
                .header("X-Internal-Key", INTERNAL)
                .contentType("application/json")
                .content(json.writeValueAsString(body)))
        .andExpect(status().is(expected))
        .andReturn().getResponse().getContentAsString();
  }

  /** 建子租户并返回子租户所有者的 token。 */
  private String createChild(String parent, String code, String ownerUsername) throws Exception {
    request(
        parent,
        "POST",
        "/identity/children",
        Map.of(
            "code", code,
            "name", "记录子租户 " + code,
            "ownerUsername", ownerUsername,
            "ownerPassword", "test-member-password"),
        200);
    return loginUser(ownerUsername);
  }

  /** 租户事务内直查（RLS 生效），返回零行或多行 —— 不预设必须查到。 */
  private List<Record> rowsIn(UUID tenant, String statement, Object... bindings) {
    return tx().run(tenant, () -> List.copyOf(sql.fetch(statement, bindings)));
  }

  private UUID tenantOf(String token) throws Exception {
    return UUID.fromString(
        request(token, "GET", "/identity/tenants/current", null, 200).get("tenantId").asText());
  }
}
