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
 * 1.1.1.3 审批待办（工作台只读聚合层）的**多租户三断言**。
 *
 * <p>本模块是审批待办的**另一个读出口** —— 权威写侧在 17.4.2.1，权威列表在
 * {@code GET /identity/my-approvals}。工作台多一个读出口，就多一个可能把别人的待办漏出去的地方，
 * 所以三条铁律在这里**单独实跑**，而不是当作业务测试的附带覆盖：
 *
 * <ol>
 *   <li><b>两租户互不可见</b>：清单里没有对方的数据，<b>按主键直查对方的行也读到零行</b>；</li>
 *   <li><b>伪造 {@code X-Tenant-Id} 头无效</b>：租户只来自已认证会话的成员关系；</li>
 *   <li><b>连接池归还后不串租户</b>：池固定 1 条连接，多轮交替读写。</li>
 * </ol>
 *
 * <p><b>断言落在真实业务表上</b>（{@code approval_instance}），
 * 并且**按主键直查对方的行、断言读到零行** ——
 * 只在 HTTP 层断言「列表里没有对方数据」是不够的，那个结论可能来自过滤条件写对了，
 * 而底下的行照样在库里可见。
 *
 * <p><b>连接池压到 1 条连接</b>（{@link IdentityIntegrationTestBase} 已设）：只有复用同一条物理连接，
 * 「归还后不串租户」才是真的被验证过 —— 池大于 1 时每次可能拿到另一条干净连接，「没串」可能只是碰巧。
 */
class ApprovalTodoTenancyIntegrationTest extends IdentityIntegrationTestBase {

  private static final String INTERNAL = "test-key";

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
  void 两租户的待审批清单互不可见且按主键直查对方行读到零行() throws Exception {
    var parent = login();
    var tag = suffix();
    var parentRole = roleId(parent, "TT" + tag, perms("read", "approve", "transfer"));
    var parentApprover = approver(parent, "tt-a-" + tag.toLowerCase(), parentRole);
    var parentFlow = flowFor(parent, "父租户待办 " + tag, parentRole);
    var parentInstance =
        submit(parent, parentFlow, "trade", "TT-" + tag, "父租户单 " + tag, "100");
    var child = createChild(parent, "TTC" + tag, "ttc-" + tag.toLowerCase());
    var childRole = roleId(child, "CT" + tag, perms("read", "approve", "transfer"));
    var childApprover = approver(child, "ct-a-" + tag.toLowerCase(), childRole);
    var childFlow = flowFor(child, "子租户待办 " + tag, childRole);
    var childInstance =
        submit(child, childFlow, "warehouse", "CT-" + tag, "子租户单 " + tag, "200");
    var parentTenant = tenantOf(parent);
    var childTenant = tenantOf(child);

    // 清单层：各自的待办只有自己的那一条
    assertThat(todos(parentApprover, "").toString())
        .contains("父租户单 " + tag)
        .doesNotContain("子租户单 " + tag);
    assertThat(todos(childApprover, "").toString())
        .contains("子租户单 " + tag)
        .doesNotContain("父租户单 " + tag);

    // **按主键直查**：子租户租户事务里读父租户的实例行 → 零行
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

    // 分档计数也不能把对方的单子算进来
    assertThat(todos(parentApprover, "").get("summary").get("pendingTotal").asLong()).isEqualTo(1);
    assertThat(todos(childApprover, "").get("summary").get("pendingTotal").asLong()).isEqualTo(1);
  }

  // ------------------------------------------------------------------
  // 断言二：伪造 X-Tenant-Id 头无效
  // ------------------------------------------------------------------

  @Test
  void 伪造租户头无效且看不到对方的待办() throws Exception {
    var parent = login();
    var tag = suffix();
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
          http.perform(get("/identity/approval-todos")
                  .header("Authorization", "Bearer " + parentApprover)
                  .header("X-Tenant-Id", forged))
              .andExpect(status().isOk())
              .andReturn().getResponse().getContentAsString();
      assertThat(view)
          .as("伪造 X-Tenant-Id=" + forged + " 不得改变可见范围")
          .contains("父租户单 " + tag)
          .doesNotContain("子租户单 " + tag);
    }

    // 伪造头在子租户那一侧同样无效
    var childView =
        http.perform(get("/identity/approval-todos")
                .header("Authorization", "Bearer " + childApprover)
                .header("X-Tenant-Id", tenantOf(parent).toString()))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
    assertThat(childView)
        .contains("子租户单 " + tag)
        .doesNotContain("父租户单 " + tag);

    // 无令牌一律 401
    http.perform(get("/identity/approval-todos")).andExpect(status().isUnauthorized());
  }

  // ------------------------------------------------------------------
  // 断言三：连接池归还后不串租户（池固定 1 条，多轮交替读写）
  // ------------------------------------------------------------------

  @Test
  void 连接池归还后多轮交替读写不串租户() throws Exception {
    var parent = login();
    var tag = suffix();
    var parentRole = roleId(parent, "PT" + tag, perms("read", "approve", "transfer"));
    var parentApprover = approver(parent, "pt-a-" + tag.toLowerCase(), parentRole);
    var parentFlow = flowFor(parent, "父租户轮换 " + tag, parentRole);
    submit(parent, parentFlow, "trade", "PR-" + tag, "父租户轮换单 " + tag, "100");
    var child = createChild(parent, "PTR" + tag, "ptr-" + tag.toLowerCase());
    var childRole = roleId(child, "CR" + tag, perms("read", "approve", "transfer"));
    var childApprover = approver(child, "cr-a-" + tag.toLowerCase(), childRole);
    var childFlow = flowFor(child, "子租户轮换 " + tag, childRole);
    var parentTenant = tenantOf(parent);
    var childTenant = tenantOf(child);

    // 池大小是 1（见基类），下面每一轮都必然复用同一条物理连接。
    // 每轮做三件事：读父租户 → 读子租户 → 再读父租户，且每步都断言对方的数据不出现。
    for (int i = 0; i < 4; i++) {
      var roundChild =
          submit(
              child,
              childFlow,
              "trade",
              "CR" + i + "-" + tag,
              "子租户轮换单 " + tag + "-" + i,
              "100");

      var p = todos(parentApprover, "").toString();
      assertThat(p)
          .as("第 %s 轮：父租户读必须只见父租户", i)
          .contains("父租户轮换单 " + tag)
          .doesNotContain("子租户轮换单 " + tag + "-" + i);
      assertThat(
              rowsIn(
                  parentTenant, "select id from approval_instance where id=?", roundChild))
          .as("第 %s 轮：父租户直查不得看到子租户的行", i)
          .isEmpty();

      // 中间切到子租户读一次 —— 连接刚被父租户用过
      var c = todos(childApprover, "").toString();
      assertThat(c)
          .as("第 %s 轮：子租户读必须只见子租户", i)
          .contains("子租户轮换单 " + tag + "-" + i)
          .doesNotContain("父租户轮换单 " + tag);

      // 再回到父租户读：连接刚被子租户用过，父租户必须仍然只见父租户
      var p2 = todos(parentApprover, "").toString();
      assertThat(p2)
          .as("第 %s 轮：子租户用过连接之后父租户仍不得串", i)
          .contains("父租户轮换单 " + tag)
          .doesNotContain("子租户轮换单 " + tag + "-" + i);
      assertThat(
              rowsIn(
                  parentTenant,
                  "select id from approval_instance where id=? and status='PENDING'",
                  UUID.fromString(
                      todos(parentApprover, "").get("items").get(0).get("id").asText())))
          .as("第 %s 轮：父租户实例应始终停在待办", i)
          .hasSize(1);
    }
  }

  @Test
  void 无租户上下文时查不到任何行且审批实例表为强制RLS() throws Exception {
    // 事务外（app.current_tenant 未设置）直查必须为空：租户上下文不是「默认放行」
    assertThat(sql.fetch("select id from approval_instance")).isEmpty();

    // 表必须是 FORCE RLS —— 只 ENABLE 的话，表 owner 连接会绕过隔离
    assertThat(
            sql.fetchOne("select relforcerowsecurity as forced from pg_class where relname=?",
                    "approval_instance")
                .get("forced", Boolean.class))
        .as("approval_instance 必须 FORCE RLS")
        .isTrue();

    // 顺带证明连接确实来自非超级用户、也没有 bypassrls —— 上面两条才有意义
    assertThat(
            sql.fetchOne("select rolbypassrls, rolsuper from pg_roles where rolname=current_user")
                .get("rolbypassrls", Boolean.class))
        .as("应用连接不得持有 bypassrls，否则 FORCE RLS 形同虚设")
        .isFalse();
  }

  // ------------------------------------------------------------------
  // 小工具（与 ApprovalTodoIntegrationTest 同形，各自独立持有）
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

  /** 走上游的提交入口建一条待办（内部密钥 + 已认证会话）。 */
  private UUID submit(
      String token, UUID flowId, String source, String ref, String title, String amount)
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
            .andExpect(status().is(200))
            .andReturn()
            .getResponse();
    return UUID.fromString(json.readTree(response.getContentAsString()).get("id").asText());
  }

  private JsonNode todos(String token, String query) throws Exception {
    return request(token, "GET", "/identity/approval-todos" + query, null, 200);
  }

  /** 建子租户并返回子租户所有者的 token。 */
  private String createChild(String parent, String code, String ownerUsername) throws Exception {
    request(
            parent,
            "POST",
            "/identity/children",
            Map.of(
                "code", code,
                "name", "待办子租户 " + code,
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
