package com.starcon.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.starcon.tenancy.TenantTransactions;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 角色授权的「生效时间」必须与授权判定**同钟域**。
 *
 * <p>🔴 <b>为什么这是真问题（2026-10-28 L2 批次闸 38 个 403 的成因）</b>：
 *
 * <ul>
 *   <li>授权判定在 {@code V47__member_role_assignment.sql} 的 {@code session_access()} 里，
 *       用的判据是 {@code ra.effective_from <= now()}。PostgreSQL 的 {@code now()} 是
 *       {@code transaction_timestamp()}，也就是**当前事务的开始时刻**，而且它属于
 *       <b>数据库自己的时钟域</b>；</li>
 *   <li>而 {@code AdministrationController.assignments()}（建成员时落授权）把
 *       {@code effective_from} 写成 {@code OffsetDateTime.now()} —— <b>应用服务器的墙钟</b>。
 *       两端因此分属两个钟域。</li>
 * </ul>
 *
 * <p>后果：只要「应用服务器钟」比「数据库钟」快出一小段（宿主机与 Docker 虚拟机在满载 /
 * 挂起恢复后的钟差正是这个量级），刚建好的授权就会被判定成<b>尚未生效</b>，
 * 成员拿不到任何授权码，于是每一个请求都落到
 * {@code require(... "当前角色没有此操作权限")} 那条 403 分支上。
 *
 * <p>这解释了 2026-10-28 那次 L2 批次闸红点的全部特征：<b>36 个 403 全部落在
 * 「通过 {@code POST /identity/members} 建的成员」身上，一个都没落在 local-admin 身上</b>
 * —— local-admin 的授权是 {@code LocalBootstrap} 写的，没带 {@code effective_from}，
 * 吃的是列默认值 {@code now()}（数据库钟），所以永远不受影响。
 *
 * <p>修法与 {@code 32a16a6a}（审批待办超时夹具改用 DB 时钟）同一个纪律：
 * <b>写入侧与判定侧拉进同一个钟域</b> —— 授权生效时间改由数据库自己的 {@code now()} 给出。
 *
 * <p>本类把两件事钉死：
 *
 * <ol>
 *   <li><b>写入侧确实取自数据库钟</b>：落库的 {@code effective_from} 必须落在
 *       「建成员请求前后的两次数据库时钟读数」之间。这是一条<b>纯数据库钟域内</b>的断言，
 *       换句话说它与应用服务器和数据库之间的钟差无关；</li>
 *   <li><b>判定早于写入时，新授权同样生效</b>：这是把「403 复现」确定化的用例 ——
 *       把建成员与随后的授权判定放进<b>同一个事务</b>，判据用的 {@code now()} 就早于写入时刻，
 *       旧写法必然拿不到授权（403），改用数据库钟后必然拿得到（200）。</li>
 * </ol>
 */
class RoleAssignmentEffectiveFromClockDomainIntegrationTest extends IdentityIntegrationTestBase {

  /**
   * 授权判定要用的权限码：审批待办的读权限 + 审批权限（决定时刻也要钉住）。
   *
   * <p>「配置操作权限时必须同时允许该页面查看」—— approve 带着 read 是库内规则，这里天然满足。
   */
  private static final List<String> PERMISSIONS =
      List.of("identity.approval_inbox.read", "identity.approval_inbox.approve");

  @Test
  void 新建成员的授权立即生效且生效时间取自数据库时钟() throws Exception {
    var owner = login();
    var tag = suffix();
    var role = createRole(owner, tag);
    var username = "clk-" + tag.toLowerCase();

    var before = dbNow();
    request(
        owner,
        "POST",
        "/identity/members",
        memberInput(
            username, null, List.of(Map.of("roleId", role.toString(), "scopeMode", "ALL"))),
        200);
    var after = dbNow();

    var stored =
        tx()
            .run(
                tenantOf(owner),
                () ->
                    sql.fetchOne(
                            "select effective_from from role_assignment where role_id=?", role)
                        .get(0, OffsetDateTime.class));

    // 纯数据库钟域内的区间断言：写入侧取的就是数据库自己的钟。
    // 这里**故意不留容差** —— 端点与端点同钟域，容差只会把「写入侧取错钟」这件事盖住。
    assertThat(stored.toInstant())
        .as("授权生效时间必须取自数据库时钟（与授权判定用的 now() 同钟域），不能取应用服务器墙钟")
        .isBetween(before, after);

    // 该成员**立即**可用：一个等待窗口都不给。
    var approver = loginUser(username);
    request(approver, "GET", "/identity/approval-todos", null, 200);
  }

  @Test
  void 授权判定早于授权写入时新授权同样生效() throws Exception {
    var owner = login();
    var tag = suffix();
    var role = createRole(owner, tag);
    var username = "clk-" + tag.toLowerCase();

    // 把「建成员（写授权）」与随后的「授权判定」放进**同一个事务**：
    // 判据里的 now() = transaction_timestamp() = 这个事务的开始时刻，
    // 因此它**早于**授权写入的那一刻 —— 授权生效时间若取自应用服务器墙钟，
    // 就会落在判据之后，授权被判「尚未生效」，成员拿到的是 403 而不是 200。
    //
    // 断言落在 session_access() 的返回值上（授权判定本身就是它做的）：
    // 直接看判据认不认这条授权，比看 HTTP 状态码更准，也不会把 403 抛成
    // 「事务被标记为 rollback-only」而盖掉真正的结论。
    var grants =
        tx()
            .run(
                tenantOf(owner),
                () -> {
                  try {
                    request(
                        owner,
                        "POST",
                        "/identity/members",
                        memberInput(
                            username,
                            null,
                            List.of(Map.of("roleId", role.toString(), "scopeMode", "ALL"))),
                        200);
                    var approver = loginUser(username);
                    return sql.fetchOne(
                            "select grants::text as grants from session_access(?)",
                            IdentityController.hash(approver))
                        .get("grants", String.class);
                  } catch (Exception e) {
                    throw new IllegalStateException(e);
                  }
                });

    assertThat(grants)
        .as("写入侧与判定侧同钟域时，判定时刻早于写入时刻也必须判定为已生效")
        .contains("identity.approval_inbox.read");
  }

  @Test
  void 审批实例的提交时刻与进入节点时刻同样取自数据库时钟() throws Exception {
    var owner = login();
    var tag = suffix();
    var role = createRole(owner, tag);
    var username = "clka-" + tag.toLowerCase();
    request(
        owner,
        "POST",
        "/identity/members",
        memberInput(
            username, null, List.of(Map.of("roleId", role.toString(), "scopeMode", "ALL"))),
        200);
    var approver = loginUser(username);

    // 单据类型必须**已注册且启用**（approval_document_type），而一个单据类型在同租户下
    // 只能有一条**生效**流程 —— 容器与 LOCAL 租户是所有测试类共享的，
    // 别的类留下的生效流程会让这里 409。所以这里注册一个**每次唯一**的单据类型：
    // 既不依赖别的类的数据，也不会和谁撞。该表是全局目录表（无 tenant_id、无 RLS），
    // 且没有任何测试枚举它，所以留一行不影响别人。
    var docType = "CLK" + tag;
    try (var c =
            java.sql.DriverManager.getConnection(
                pg().getJdbcUrl(), pg().getUsername(), pg().getPassword());
        var st = c.createStatement()) {
      st.execute(
          "insert into approval_document_type(code,name,description,sort_order,status)"
              + " values('"
              + docType
              + "','时钟单据类型 "
              + tag
              + "','时钟域回归用例',0,'ACTIVE')");
    }

    // 建一条首节点为该角色的流程
    request(
        owner,
        "POST",
        "/identity/approval-flows",
        Map.of(
            "documentTypeCode",
            docType,
            "flowName",
            "时钟流程 " + tag,
            "status",
            "ACTIVE",
            "description",
            "",
            "nodes",
            List.of(
                Map.of(
                    "nodeKey",
                    "R1",
                    "nodeName",
                    "节点 R1",
                    "nodeOrder",
                    1,
                    "stageIndex",
                    1,
                    "approverType",
                    "ROLE",
                    "approvalMode",
                    "SERIAL",
                    "roleId",
                    role.toString())),
            "branches",
            List.of(),
            "escalations",
            List.of()),
        200);
    var flowId =
        UUID.fromString(
            request(owner, "GET", "/identity/approval-flows", null, 200).get(0).get("id").asText());

    var before = dbNow();
    var instanceId = submitInstance(approver, flowId, tag);
    var after = dbNow();

    var row =
        tx()
            .run(
                tenantOf(owner),
                () ->
                    sql.fetchOne(
                        "select submitted_at, node_entered_at from approval_instance where id=?",
                        instanceId));

    // 停留时长在 DB 侧算：approval_dwell_seconds(now(), node_entered_at)、
    // 以及 node_entered_at <= now() - N * interval —— 判据里的 now() 是 DB 的钟。
    // 写入侧若用应用服务器墙钟，两端分属两个钟域，停留时长会被钟差整体带偏。
    assertThat(row.get("submitted_at", OffsetDateTime.class).toInstant())
        .as("审批实例的提交时刻必须取自数据库时钟")
        .isBetween(before, after);
    assertThat(row.get("node_entered_at", OffsetDateTime.class).toInstant())
        .as("审批实例进入节点的时刻必须取自数据库时钟")
        .isBetween(before, after);

    // 审批动作的决定时刻同样与 node_entered_at 相减
    // （ApprovalRecordController 的 avg_dwell / avg_handle / avg_action），
    // 所以它也必须与它们同钟域。
    var decidedBefore = dbNow();
    request(
        approver,
        "POST",
        "/identity/my-approvals/" + instanceId + "/decide",
        Map.of("decision", "APPROVE", "comment", "同意"),
        200);
    var decidedAfter = dbNow();
    var decidedAt =
        tx()
            .run(
                tenantOf(owner),
                () ->
                    sql.fetchOne(
                            "select decided_at from approval_instance_action where instance_id=?",
                            instanceId)
                        .get(0, OffsetDateTime.class));
    assertThat(decidedAt.toInstant())
        .as("审批动作的决定时刻必须取自数据库时钟")
        .isBetween(decidedBefore, decidedAfter);
  }

  @Test
  void 部门负责人代管的生效起点同样取自数据库时钟() throws Exception {
    var owner = login();
    var tag = suffix();
    var deptCode = "CLK" + tag;

    var deptId =
        UUID.fromString(
            request(
                    owner,
                    "POST",
                    "/identity/departments",
                    Map.of("code", deptCode, "name", "时钟部门 " + tag),
                    200)
                .asText());

    var leaderName = "clkl-" + tag.toLowerCase();
    var leaderId =
        UUID.fromString(
            request(
                    owner,
                    "POST",
                    "/identity/members",
                    memberInput(leaderName, deptId.toString(), List.of()),
                    200)
                .asText());
    request(
        owner,
        "PUT",
        "/identity/departments/" + deptId + "/leader",
        Map.of(
            "membershipId",
            leaderId.toString(),
            "scopeMode",
            "DEPARTMENT",
            "approvalLimit",
            "1000.00",
            "approvalCurrency",
            "USD"),
        200);

    var proxyName = "clkp-" + tag.toLowerCase();
    var proxyId =
        UUID.fromString(
            request(
                    owner,
                    "POST",
                    "/identity/members",
                    memberInput(proxyName, deptId.toString(), List.of()),
                    200)
                .asText());

    var before = dbNow();
    request(
        owner,
        "POST",
        "/identity/departments/" + deptId + "/leader/proxies",
        Map.of(
            "membershipId",
            proxyId.toString(),
            "reason",
            "时钟代管 " + tag,
            "endsAt",
            java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).plusDays(1).toString()),
        200);
    var after = dbNow();

    var startsAt =
        tx()
            .run(
                tenantOf(owner),
                () ->
                    sql.fetchOne(
                            "select starts_at from department_leader_proxy where department_id=?",
                            deptId)
                        .get(0, OffsetDateTime.class));

    // V44 的 department_approval_chain 用 `p.starts_at <= coalesce(as_of, now())`
    // 判「代管此刻是否生效」—— 那是授权判定，判据用的是 DB 的 now()。
    assertThat(startsAt.toInstant())
        .as("代管生效起点必须取自数据库时钟（与授权判定同钟域）")
        .isBetween(before, after);
  }

  @Test
  void 编号规则的默认生效起点同样取自数据库时钟() throws Exception {
    var owner = login();
    var tag = suffix();

    // 编号规则表与其它测试类共享（容器共享），先清空再配，
    // 否则同实体上遗留的主规则会撞冲突校验（与 NumberRuleIntegrationTest 同款做法）。
    try (var c =
            java.sql.DriverManager.getConnection(
                pg().getJdbcUrl(), pg().getUsername(), pg().getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate document_number_issued, document_number_sequence,"
              + " document_number_rule_segment, document_number_rule");
    }

    var input = new LinkedHashMap<String, Object>();
    input.put("entityCode", "ORDER");
    input.put("ruleName", "时钟规则 " + tag);
    input.put("ruleKind", "PRIMARY");
    input.put("status", "ACTIVE");
    input.put("timezone", "Asia/Shanghai");
    input.put("description", "时钟域回归用例");
    input.put(
        "segments",
        List.of(
            Map.of("segmentType", "PREFIX", "literalText", "CLK" + tag),
            Map.of(
                "segmentType",
                "SEQUENCE",
                "sequenceWidth",
                4,
                "sequenceStart",
                1L,
                "sequenceReset",
                "NONE",
                "sequenceScope",
                "ENTITY")));

    var before = dbNow();
    var ruleId =
        UUID.fromString(
            request(owner, "POST", "/identity/numbering/rules", input, 200).asText());
    var after = dbNow();

    var effectiveFrom =
        tx()
            .run(
                tenantOf(owner),
                () ->
                    sql.fetchOne(
                            "select effective_from from document_number_rule where id=?", ruleId)
                        .get(0, OffsetDateTime.class));

    // 规则是否生效的判据是 `r.effective_from<=now()`（DB 的钟）。
    // 默认生效起点是应用自己造的「此刻」，所以它必须取数据库的钟：
    // 否则新建的规则可能「刚建好却还没生效」。
    assertThat(effectiveFrom.toInstant())
        .as("编号规则的默认生效起点必须取自数据库时钟")
        .isBetween(before, after);
  }

  private UUID submitInstance(String token, UUID flowId, String tag) throws Exception {
    var body = new LinkedHashMap<String, Object>();
    body.put("flowId", flowId.toString());
    body.put("sourceSystem", "trade");
    body.put("sourceReferenceId", "CLK-" + tag + "-1");
    body.put("subjectTitle", "时钟单 " + tag);
    body.put("currencyCode", "USD");
    body.put("amount", new BigDecimal("100"));
    var response =
        http.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                        "/identity/approval-instances")
                    .header("Authorization", "Bearer " + token)
                    .header("X-Internal-Key", "test-key")
                    .contentType("application/json")
                    .content(json.writeValueAsString(body)))
            .andExpect(status().is(200))
            .andReturn()
            .getResponse();
    return UUID.fromString(json.readTree(response.getContentAsString()).get("id").asText());
  }

  private UUID createRole(String owner, String tag) throws Exception {
    return UUID.fromString(
        request(
                owner,
                "POST",
                "/identity/roles",
                Map.of(
                    "code",
                    "CLK" + tag,
                    "name",
                    "时钟角色 " + tag,
                    "status",
                    "ACTIVE",
                    "permissions",
                    PERMISSIONS),
                200)
            .asText());
  }

  private UUID tenantOf(String token) throws Exception {
    return UUID.fromString(
        request(token, "GET", "/identity/tenants/current", null, 200).get("tenantId").asText());
  }

  private TenantTransactions tx() {
    return new TenantTransactions(
        sql, new org.springframework.transaction.support.TransactionTemplate(manager));
  }
}
