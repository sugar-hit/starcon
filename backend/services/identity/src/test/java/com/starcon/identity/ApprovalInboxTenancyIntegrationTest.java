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
 * 17.4.2.1 待我审批的**多租户三断言**。
 *
 * <p>{@link ApprovalInboxIntegrationTest} 里也有一份三断言，但那个类的主职责是业务行为（金额分支、
 * 批量、转交、条件节点）；隔离是它的附带覆盖。本类把三条铁律**单独拉出来实跑**，因为本模块的隔离面
 * 比一般列表模块更大，三处都直接暴露在「别人能不能看见这条待办 / 能不能替我批」上：
 *
 * <ol>
 *   <li>列表与详情都按成员关系过滤 —— 越权访问一律 404；
 *   <li>{@code approval_instance_action} 是**审批留痕**，跨租户读到就等于跨租户读到「谁批了谁」；
 *   <li>提交入口虽然是内部调用，但租户同样只来自会话 —— 拿 A 租户的会话去提交 B 租户的流程必须落不下去。
 * </ol>
 *
 * <p><b>断言落在真实业务表上</b>：{@code approval_instance} / {@code approval_instance_action}，
 * 并且**按主键直查对方的行、断言读到零行**。只在 HTTP 层断言「列表里没有对方数据」是不够的 ——
 * 那个结论可能来自过滤条件写对了，而底下的行照样在库里可见。
 *
 * <p><b>连接池压到 1 条连接</b>（{@link IdentityIntegrationTestBase} 已设）：只有复用同一条物理连接，
 * 「归还后不串租户」才是真的被验证过 —— 池大于 1 时每次可能拿到另一条干净连接，「没串」可能只是碰巧。
 */
class ApprovalInboxTenancyIntegrationTest extends IdentityIntegrationTestBase {

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
  void 两租户的待审批实例与审批留痕互不可见且按主键直查对方行读到零行() throws Exception {
    var parent = login();
    tag = suffix();
    var parentRole = roleId(parent, "PT" + tag, perms("read", "approve", "transfer"));
    var parentApprover = approver(parent, "pt-a-" + tag.toLowerCase(), parentRole);
    var parentFlow = flowFor(parent, "父租户待审 " + tag, parentRole);
    var parentInstance =
        UUID.fromString(
            submit(parent, parentFlow, "trade", "PT-" + tag, "父租户单 " + tag, "100")
                .get("id")
                .asText());
    var child = createChild(parent, "PTC" + tag, "ptc-" + tag.toLowerCase());
    var childRole = roleId(child, "CT" + tag, perms("read", "approve", "transfer"));
    var childApprover = approver(child, "ct-a-" + tag.toLowerCase(), childRole);
    var childFlow = flowFor(child, "子租户待审 " + tag, childRole);
    var childInstance =
        UUID.fromString(
            submit(child, childFlow, "warehouse", "CT-" + tag, "子租户单 " + tag, "200")
                .get("id")
                .asText());
    var parentTenant = tenantOf(parent);
    var childTenant = tenantOf(child);

    // 列表层：各自的待办只有自己的那一条
    assertThat(inbox(parentApprover, "").toString())
        .contains("父租户单 " + tag)
        .doesNotContain("子租户单 " + tag);
    assertThat(inbox(childApprover, "").toString())
        .contains("子租户单 " + tag)
        .doesNotContain("父租户单 " + tag);

    // **按主键直查**：子租户租户事务里读父租户的实例行 → 零行。
    // 只断言列表不含对方标题是不够的，那可能只是过滤条件写对了、底下的行照样可见。
    assertThat(rowsIn(childTenant, "select id from approval_instance where id=?", parentInstance))
        .as("子租户按主键直查父租户的审批实例必须读到零行")
        .isEmpty();
    assertThat(rowsIn(parentTenant, "select id from approval_instance where id=?", childInstance))
        .as("父租户按主键直查子租户的审批实例必须读到零行")
        .isEmpty();

    // 各自租户里读自己的行确实读得到 —— 证明上面那两个「零行」不是因为表本身查不到
    assertThat(rowsIn(parentTenant, "select id from approval_instance where id=?", parentInstance))
        .hasSize(1);
    assertThat(rowsIn(childTenant, "select id from approval_instance where id=?", childInstance))
        .hasSize(1);

    // 跨租户详情 / 审批一律 404（403 会泄露「这条记录存在」）
    request(childApprover, "GET", "/identity/my-approvals/" + parentInstance, null, 404);
    request(parentApprover, "GET", "/identity/my-approvals/" + childInstance, null, 404);
    decide(childApprover, parentInstance, "APPROVE", "越权", 404);
    decide(parentApprover, childInstance, "APPROVE", "越权", 404);
  }

  @Test
  void 审批留痕表在两租户之间同样互不可见() throws Exception {
    var parent = login();
    tag = suffix();
    var parentRole = roleId(parent, "LT" + tag, perms("read", "approve", "transfer"));
    var parentApprover = approver(parent, "lt-a-" + tag.toLowerCase(), parentRole);
    var parentFlow = flowFor(parent, "父租户留痕 " + tag, parentRole);
    var parentInstance =
        UUID.fromString(
            submit(parent, parentFlow, "trade", "LT-" + tag, "父租户留痕单 " + tag, "100")
                .get("id")
                .asText());
    var child = createChild(parent, "LTC" + tag, "ltc-" + tag.toLowerCase());
    var childRole = roleId(child, "LC" + tag, perms("read", "approve", "transfer"));
    var childApprover = approver(child, "lc-a-" + tag.toLowerCase(), childRole);
    var childFlow = flowFor(child, "子租户留痕 " + tag, childRole);
    var childInstance =
        UUID.fromString(
            submit(child, childFlow, "trade", "LC-" + tag, "子租户留痕单 " + tag, "100")
                .get("id")
                .asText());

    // 父租户批一条 → 产生一条审批留痕；子租户也批一条 → 各自一条
    decide(parentApprover, parentInstance, "APPROVE", null, 200);
    decide(childApprover, childInstance, "APPROVE", null, 200);
    var parentTenant = tenantOf(parent);
    var childTenant = tenantOf(child);

    // 留痕确实各自写下了
    assertThat(rowsIn(parentTenant, "select id from approval_instance_action where instance_id=?", parentInstance))
        .hasSize(1);
    assertThat(rowsIn(childTenant, "select id from approval_instance_action where instance_id=?", childInstance))
        .hasSize(1);

    // 按主键直查对方的留痕 → 零行。「谁批了谁」跨租户读不到
    assertThat(rowsIn(childTenant, "select id from approval_instance_action where instance_id=?", parentInstance))
        .as("子租户按主键直查父租户的审批留痕必须读到零行")
        .isEmpty();
    assertThat(rowsIn(parentTenant, "select id from approval_instance_action where instance_id=?", childInstance))
        .as("父租户按主键直查子租户的审批留痕必须读到零行")
        .isEmpty();

    // 跨租户批量动作也不能碰到对方的单子：整批逐条 404，仍然零条生效
    var batch =
        request(
            childApprover,
            "POST",
            "/identity/my-approvals/batch",
            Map.of(
                "action", "APPROVE",
                "items", List.of(Map.of("instanceId", parentInstance.toString()))),
            200);
    assertThat(batch.get("succeeded").asInt()).isZero();
    assertThat(batch.get("results").get(0).get("code").asText()).isEqualTo("NOT_MINE");
    // 父租户那条仍停在自己租户里、且仍是他自己批完的那个状态（子租户的批量没有碰它）
    assertThat(
            rowsIn(
                    parentTenant,
                    "select id from approval_instance where id=? and status='APPROVED'",
                    parentInstance))
        .as("子租户的批量不得改动父租户的实例")
        .hasSize(1);
    // 子租户那次失败的批量不得留下任何审批留痕
    assertThat(
            rowsIn(
                parentTenant,
                "select id from approval_instance_action where instance_id=?",
                parentInstance))
        .as("失败的批量不得留下第二条审批留痕")
        .hasSize(1);
  }

  // ------------------------------------------------------------------
  // 断言二：伪造 X-Tenant-Id 头无效
  // ------------------------------------------------------------------

  @Test
  void 伪造租户头无效且写不进别人的数据() throws Exception {
    var parent = login();
    tag = suffix();
    var parentRole = roleId(parent, "FT" + tag, perms("read", "approve", "transfer"));
    var parentApprover = approver(parent, "ft-a-" + tag.toLowerCase(), parentRole);
    var parentFlow = flowFor(parent, "父租户伪造 " + tag, parentRole);
    submit(parent, parentFlow, "trade", "FT-" + tag, "父租户单 " + tag, "100");
    var child = createChild(parent, "FTC" + tag, "ftc-" + tag.toLowerCase());
    var childRole = roleId(child, "FC" + tag, perms("read", "approve", "transfer"));
    var childApprover = approver(child, "fc-a-" + tag.toLowerCase(), childRole);
    var childFlow = flowFor(child, "子租户伪造 " + tag, childRole);
    submit(child, childFlow, "warehouse", "FC-" + tag, "子租户单 " + tag, "100");
    var childTenant = tenantOf(child);

    // 父租户审批人伪造随机租户 id 与子租户**真实**租户 id：结果都必须不变。
    // 用子租户真实 id 才是真正有杀伤力的那一档 —— 调用方确实知道一个存在的租户 id。
    for (var forged : List.of(UUID.randomUUID().toString(), childTenant.toString())) {
      var view =
          http.perform(get("/identity/my-approvals")
                  .header("Authorization", "Bearer " + parentApprover)
                  .header("X-Tenant-Id", forged))
              .andExpect(status().isOk())
              .andReturn().getResponse().getContentAsString();
      assertThat(view)
          .as("伪造 X-Tenant-Id=" + forged + " 不得改变可见范围")
          .contains("父租户单 " + tag)
          .doesNotContain("子租户单 " + tag);
    }

    // 伪造头的**写**同样无效。这里刻意断言 200 而不是 4xx：
    // 父租户审批人本来就有权批父租户的这一条，伪造一个头既不该让他**多**看到东西，
    // 也不该凭空**剥夺**他已有的权限。真正要证明的是——这次写仍然落回**父租户**自己的库，
    // 子租户那边一个字节都没变。断言 404 反而是错的：它会把「伪造头让正当审批人失去权限」写成期望行为。
    var parentInstance =
        UUID.fromString(inbox(parentApprover, "").get("items").get(0).get("id").asText());
    var forgedWrite =
        http.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .post("/identity/my-approvals/" + parentInstance + "/decide")
                    .header("Authorization", "Bearer " + parentApprover)
                    .header("X-Tenant-Id", childTenant.toString())
                    .contentType("application/json")
                    .content(json.writeValueAsString(Map.of("decision", "APPROVE"))))
            .andExpect(status().isOk())
            .andReturn().getResponse();
    assertThat(forgedWrite.getContentAsString()).contains("\"status\":\"APPROVED\"");
    // 写落在**父租户**的库里
    assertThat(
            rowsIn(
                    tenantOf(parent),
                    "select id from approval_instance where id=? and status='APPROVED'",
                    parentInstance))
        .as("伪造租户头的写仍然落在父租户自己的库")
        .hasSize(1);
    // 子租户的库完全没有被这次写碰到（既没有这行，也没有任何留痕）
    assertThat(rowsIn(childTenant, "select id from approval_instance where id=?", parentInstance))
        .as("伪造租户头的写不得把行写进子租户的库")
        .isEmpty();
    assertThat(
            rowsIn(
                childTenant,
                "select id from approval_instance_action where instance_id=?",
                parentInstance))
        .as("伪造租户头的写不得把留痕写进子租户的库")
        .isEmpty();

    // 伪造租户头也**写不进**别人的库：拿子租户流程去父租户会话提交，落不下去（404）
    internal(
        parentApprover,
        Map.of(
            "flowId", childFlow.toString(),
            "sourceSystem", "trade",
            "sourceReferenceId", "FORGED-" + tag,
            "subjectTitle", "伪造提交 " + tag),
        null,
        null,
        404);
    assertThat(rowsIn(tenantOf(parent), "select id from approval_instance where source_reference_id=?", "FORGED-" + tag))
        .as("伪造租户头的提交不得在父租户库里留下行")
        .isEmpty();
    assertThat(rowsIn(childTenant, "select id from approval_instance where source_reference_id=?", "FORGED-" + tag))
        .as("也不得把行写进子租户的库")
        .isEmpty();

    // 无令牌一律 401
    http.perform(get("/identity/my-approvals")).andExpect(status().isUnauthorized());
  }

  // ------------------------------------------------------------------
  // 断言三：连接池归还后不串租户（池固定 1 条，多轮交替读写）
  // ------------------------------------------------------------------

  @Test
  void 连接池归还后多轮交替读写不串租户() throws Exception {
    var parent = login();
    tag = suffix();
    var parentRole = roleId(parent, "PT" + tag, perms("read", "approve", "transfer"));
    var parentApprover = approver(parent, "pt-a-" + tag.toLowerCase(), parentRole);
    var parentFlow = flowFor(parent, "父租户轮换 " + tag, parentRole);
    var parentInstance =
        UUID.fromString(
            submit(parent, parentFlow, "trade", "PR-" + tag, "父租户轮换单 " + tag, "100")
                .get("id")
                .asText());
    var child = createChild(parent, "PTR" + tag, "ptr-" + tag.toLowerCase());
    var childRole = roleId(child, "CR" + tag, perms("read", "approve", "transfer"));
    var childApprover = approver(child, "cr-a-" + tag.toLowerCase(), childRole);
    var childFlow = flowFor(child, "子租户轮换 " + tag, childRole);
    var parentTenant = tenantOf(parent);
    var childTenant = tenantOf(child);

    // 池大小是 1（见基类），下面每一轮都必然复用同一条物理连接。
    // 每轮做三件事：读父租户 → 写子租户 → 再读父租户，且每步都断言对方的数据不出现。
    // 刻意**每轮新提交一条子租户待办**再批它：复用同一条已批完的单子会在第二轮就正确地
    // 404（已结实例不再进待办），那样测的是「已结不可重复批」而不是「连接不串租户」，
    // 断言会被一个正确的 404 掩盖掉真正的隔离结论。
    for (int i = 0; i < 4; i++) {
      var roundChildInstance =
          UUID.fromString(
              submit(child, childFlow, "trade", "CR" + i + "-" + tag, "子租户轮换单 " + tag + "-" + i, "100")
                  .get("id")
                  .asText());

      var p = inbox(parentApprover, "").toString();
      assertThat(p)
          .as("第 %s 轮：父租户读必须只见父租户", i)
          .contains("父租户轮换单 " + tag)
          .doesNotContain("子租户轮换单 " + tag + "-" + i);
      assertThat(
              rowsIn(
                  parentTenant, "select id from approval_instance where id=?", roundChildInstance))
          .as("第 %s 轮：父租户直查不得看到子租户的行", i)
          .isEmpty();

      // 交替**写**：子租户批自己刚提交的那条
      decide(childApprover, roundChildInstance, "APPROVE", null, 200);
      assertThat(
              rowsIn(
                  childTenant,
                  "select id from approval_instance where id=? and status='APPROVED'",
                  roundChildInstance))
          .as("第 %s 轮：子租户的写必须落在子租户自己的库", i)
          .hasSize(1);
      assertThat(
              rowsIn(
                  parentTenant,
                  "select id from approval_instance where id=? and status='APPROVED'",
                  roundChildInstance))
          .as("第 %s 轮：子租户的写不得改变父租户可见的那一行", i)
          .isEmpty();

      // 再回到父租户读：连接刚被子租户用过，父租户必须仍然只见父租户
      var p2 = inbox(parentApprover, "").toString();
      assertThat(p2)
          .as("第 %s 轮：子租户用过连接之后父租户仍不得串", i)
          .contains("父租户轮换单 " + tag)
          .doesNotContain("子租户轮换单 " + tag + "-" + i);

      // 父租户那条始终没被动过（子租户的写没有溢出）
      assertThat(
              rowsIn(
                  parentTenant,
                  "select id from approval_instance where id=? and status='PENDING'",
                  parentInstance))
          .as("第 %s 轮：父租户实例应始终停在待办", i)
          .hasSize(1);
    }
  }

  @Test
  void 无租户上下文时两张表查不到任何行且均为强制RLS() throws Exception {
    // 事务外（app.current_tenant 未设置）直查必须为空：租户上下文不是「默认放行」
    assertThat(sql.fetch("select id from approval_instance")).isEmpty();
    assertThat(sql.fetch("select id from approval_instance_action")).isEmpty();

    // 两张新表都必须是 FORCE RLS —— 只 ENABLE 的话，表 owner 连接会绕过隔离
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
  // 小工具（与 ApprovalInboxIntegrationTest 同形，各自独立持有）
  // ------------------------------------------------------------------

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

  private String approver(String owner, String username, UUID role) throws Exception {
    request(
            owner,
            "POST",
            "/identity/members",
            memberInput(username, null, List.of(Map.of("roleId", role.toString(), "scopeMode", "ALL"))),
            200);
    return loginUser(username);
  }

  /** 建一条生效流程，首节点是指定角色的 ROLE 节点。 */
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

  private JsonNode submit(String token, UUID flowId, String source, String ref, String title, String amount)
      throws Exception {
    return json.readTree(
        internal(
            token,
            Map.of(
                "flowId", flowId.toString(),
                "sourceSystem", source,
                "sourceReferenceId", ref,
                "subjectTitle", title),
            "USD",
            amount,
            200));
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

  private String internal(
      String token, Map<String, Object> body, String cur, String amount, int expected)
      throws Exception {
    var full = new LinkedHashMap<String, Object>(body);
    if (cur != null) full.put("currencyCode", cur);
    if (amount != null) full.put("amount", new java.math.BigDecimal(amount));
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

  /** 建子租户并返回子租户所有者的 token。 */
  private String createChild(String parent, String code, String ownerUsername) throws Exception {
    request(
        parent,
        "POST",
        "/identity/children",
        Map.of(
            "code", code,
            "name", "待审批子租户 " + code,
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
