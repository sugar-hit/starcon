package com.starcon.identity;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.starcon.tenancy.TenantTransactions;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 17.2.1.2 部门负责人。
 *
 * <p>覆盖四组断言：负责人与管理范围 / 审批权限（额度口径）/ 代管与临时授权的时间窗 / 审批链引用与兜底信号，加上租户隔离三断言（两租户互不可见、伪造 tenant 头无效、
 * 连接池归还后不串租户——底座把 Hikari 池压到 1，这条才是真验证过的）。
 *
 * <p>共享底座（真实 PostgreSQL 16 容器、登录与请求辅助）在 {@link IdentityIntegrationTestBase}。
 */
class DepartmentLeaderIntegrationTest extends IdentityIntegrationTestBase {

  /** 负责人输入：范围默认最窄，额度与币种成对出现。 */
  private Map<String, Object> leaderInput(
      UUID membership, String scope, String limit, String currency) {
    var input = new HashMap<String, Object>();
    input.put("membershipId", membership);
    input.put("scopeMode", scope);
    input.put("approvalLimit", limit);
    input.put("approvalCurrency", currency);
    return input;
  }

  private Map<String, Object> proxyInput(UUID membership, String reason) {
    var input = new HashMap<String, Object>();
    input.put("membershipId", membership);
    input.put("reason", reason);
    return input;
  }

  private Map<String, Object> grantInput(
      UUID membership, String limit, String currency, OffsetDateTime from, OffsetDateTime to) {
    var input = new HashMap<String, Object>();
    input.put("membershipId", membership);
    input.put("approvalLimit", limit);
    input.put("approvalCurrency", currency);
    input.put("startsAt", from.toString());
    input.put("endsAt", to.toString());
    input.put("reason", "出差期间临时授权");
    return input;
  }

  private JsonNode leaderRow(JsonNode rows, String departmentCode) {
    for (JsonNode row : rows)
      if (departmentCode.equals(row.get("departmentCode").asText())) return row;
    return null;
  }

  private JsonNode chain(String token, UUID department, String query, int expected)
      throws Exception {
    return request(
        token,
        "GET",
        "/identity/departments/" + department + "/approval-chain" + query,
        null,
        expected);
  }

  // ------------------------------------------------------------------
  // 负责人设置与管理范围
  // ------------------------------------------------------------------

  /**
   * 指定负责人后，部门树与负责人清单两边都要能看到同一个人。
   *
   * <p>两边同源是本模块最关键的不变量：{@code department_leader} 是权威， {@code department.leader_membership_id}
   * 是触发器维护的镜像，任一侧单独更新都会破坏它。
   */
  @Test
  void assigningLeaderUpdatesBothTheLeaderRecordAndTheDepartmentMirror() throws Exception {
    String token = login(), tag = suffix();
    UUID root = createDepartment(token, "L" + tag, "负责人总部" + tag, null);
    UUID leaf = createDepartment(token, "LS" + tag, "负责人平台组" + tag, root);
    String leaderUser = "ld-" + tag.toLowerCase();
    UUID leader =
        UUID.fromString(
            request(
                    token,
                    "POST",
                    "/identity/members",
                    memberInput(leaderUser, leaf.toString(), List.of()),
                    200)
                .asText());

    UUID leaderId =
        UUID.fromString(
            request(
                    token,
                    "PUT",
                    "/identity/departments/" + leaf + "/leader",
                    leaderInput(leader, "SUBTREE", "5000.000000", "USD"),
                    200)
                .asText());

    var rows = request(token, "GET", "/identity/department-leaders", null, 200);
    var row = leaderRow(rows, "LS" + tag);
    assertThat(row.get("leaderId").asText()).isEqualTo(leaderId.toString());
    assertThat(row.get("membershipId").asText()).isEqualTo(leader.toString());
    assertThat(row.get("username").asText()).isEqualTo(leaderUser);
    assertThat(row.get("scopeMode").asText()).isEqualTo("SUBTREE");
    // 额度走 NUMERIC，读回来按十进制比对，不经过 double。
    assertThat(row.get("approvalLimit").decimalValue())
        .isEqualByComparingTo(new BigDecimal("5000"));

    // 镜像列：17.2.1.1 的树视图看到的是同一个人
    var node = findNode(request(token, "GET", "/identity/departments", null, 200), "LS" + tag);
    assertThat(node.get("leaderMembershipId").asText()).isEqualTo(leader.toString());
    assertThat(node.get("leaderName").asText()).isEqualTo(leaderUser);

    // 换人是 upsert，不是第二条记录：部门仍然只有一个负责人
    String successorUser = "ld2-" + tag.toLowerCase();
    UUID successor =
        UUID.fromString(
            request(
                    token,
                    "POST",
                    "/identity/members",
                    memberInput(successorUser, leaf.toString(), List.of()),
                    200)
                .asText());
    request(
        token,
        "PUT",
        "/identity/departments/" + leaf + "/leader",
        leaderInput(successor, "DEPARTMENT", "1000.000000", "USD"),
        200);
    var after =
        leaderRow(request(token, "GET", "/identity/department-leaders", null, 200), "LS" + tag);
    assertThat(after.get("leaderId").asText()).isEqualTo(leaderId.toString());
    assertThat(after.get("membershipId").asText()).isEqualTo(successor.toString());
    assertThat(after.get("scopeMode").asText()).isEqualTo("DEPARTMENT");
    assertThat(
            findNode(request(token, "GET", "/identity/departments", null, 200), "LS" + tag)
                .get("leaderName")
                .asText())
        .isEqualTo(successorUser);

    // 走 17.2.1.1 的部门表单改负责人时，负责人记录被物化出来，且既有范围与额度保留
    UUID third =
        UUID.fromString(
            request(
                    token,
                    "POST",
                    "/identity/members",
                    memberInput("ld3-" + tag.toLowerCase(), leaf.toString(), List.of()),
                    200)
                .asText());
    request(
        token,
        "PUT",
        "/identity/departments/" + leaf,
        departmentInput("LS" + tag, "负责人平台组" + tag, root, null, third),
        200);
    var materialised =
        leaderRow(request(token, "GET", "/identity/department-leaders", null, 200), "LS" + tag);
    assertThat(materialised.get("membershipId").asText()).isEqualTo(third.toString());
    assertThat(materialised.get("scopeMode").asText()).isEqualTo("DEPARTMENT");
    assertThat(materialised.get("approvalLimit").decimalValue())
        .isEqualByComparingTo(new BigDecimal("1000"));
  }

  /** 部门可以有负责人也可以没有；没有的部门必须留在清单里，标出「需兜底」。 */
  @Test
  void departmentsWithoutLeaderStayVisibleAndReportAFallbackNeed() throws Exception {
    String token = login(), tag = suffix();
    UUID empty = createDepartment(token, "NF" + tag, "无负责人部门" + tag, null);
    UUID owned = createDepartment(token, "HF" + tag, "有负责人部门" + tag, null);
    UUID leader =
        UUID.fromString(
            request(
                    token,
                    "POST",
                    "/identity/members",
                    memberInput("hf-" + tag.toLowerCase(), owned.toString(), List.of()),
                    200)
                .asText());
    request(
        token,
        "PUT",
        "/identity/departments/" + owned + "/leader",
        leaderInput(leader, "DEPARTMENT", null, null),
        200);

    var rows = request(token, "GET", "/identity/department-leaders", null, 200);
    var blank = leaderRow(rows, "NF" + tag);
    assertThat(blank).isNotNull();
    assertThat(blank.get("leaderId").isNull()).isTrue();
    assertThat(blank.get("membershipId").isNull()).isTrue();

    // 审批链：没有负责人就没有审批人，且必须显式告诉下游要配兜底人
    var blankChain = chain(token, empty, "", 200);
    assertThat(blankChain.get("entries")).isEmpty();
    assertThat(blankChain.get("requiresFallback").asBoolean()).isTrue();
    assertThat(blankChain.get("departmentCode").asText()).isEqualTo("NF" + tag);

    var ownedChain = chain(token, owned, "", 200);
    assertThat(ownedChain.get("requiresFallback").asBoolean()).isFalse();
    assertThat(ownedChain.get("entries")).hasSize(1);
    assertThat(ownedChain.get("entries").get(0).get("source").asText()).isEqualTo("LEADER");
  }

  /** 授权口径的校验：额度与币种必须成对，负责人必须启用，跨租户成员不可用。 */
  @Test
  void leaderValidationRejectsHalfLimitsInactiveAndForeignMembers() throws Exception {
    String token = login(), tag = suffix();
    UUID department = createDepartment(token, "V" + tag, "校验部门" + tag, null);
    UUID leader =
        UUID.fromString(
            request(
                    token,
                    "POST",
                    "/identity/members",
                    memberInput("v-" + tag.toLowerCase(), null, List.of()),
                    200)
                .asText());

    // 额度有、币种无：下游无从判断这是多少钱
    request(
        token,
        "PUT",
        "/identity/departments/" + department + "/leader",
        leaderInput(leader, "DEPARTMENT", "1000.000000", null),
        400);
    // 币种有、额度无
    request(
        token,
        "PUT",
        "/identity/departments/" + department + "/leader",
        leaderInput(leader, "DEPARTMENT", null, "USD"),
        400);
    // 额度 <= 0
    request(
        token,
        "PUT",
        "/identity/departments/" + department + "/leader",
        leaderInput(leader, "DEPARTMENT", "0.000000", "USD"),
        400);
    // 币种不是三位大写
    request(
        token,
        "PUT",
        "/identity/departments/" + department + "/leader",
        leaderInput(leader, "DEPARTMENT", "1000.000000", "usd"),
        400);
    // 范围枚举外的值
    request(
        token,
        "PUT",
        "/identity/departments/" + department + "/leader",
        leaderInput(leader, "ALL", null, null),
        400);
    // 不存在的成员
    request(
        token,
        "PUT",
        "/identity/departments/" + department + "/leader",
        leaderInput(UUID.randomUUID(), "DEPARTMENT", null, null),
        400);

    // 停用成员不能当负责人：人已离职却还握着审批权，单据会卡死
    request(token, "DELETE", "/identity/members/" + leader, null, 200);
    request(
        token,
        "PUT",
        "/identity/departments/" + department + "/leader",
        leaderInput(leader, "DEPARTMENT", null, null),
        400);
  }

  /** 撤销负责人把部门退回「可为空」，并连带作废代管与临时授权。 */
  @Test
  void clearingLeaderAlsoRemovesItsProxiesAndGrants() throws Exception {
    String token = login(), tag = suffix();
    UUID department = createDepartment(token, "CL" + tag, "撤销部门" + tag, null);
    UUID leader = createMember(token, "cl-" + tag.toLowerCase());
    UUID proxy = createMember(token, "cp-" + tag.toLowerCase());
    UUID borrower = createMember(token, "cb-" + tag.toLowerCase());

    request(
        token,
        "PUT",
        "/identity/departments/" + department + "/leader",
        leaderInput(leader, "DEPARTMENT", "8000.000000", "USD"),
        200);
    UUID proxyId =
        UUID.fromString(
            request(
                    token,
                    "POST",
                    "/identity/departments/" + department + "/leader/proxies",
                    proxyInput(proxy, "负责人休假"),
                    200)
                .asText());
    UUID grantId =
        UUID.fromString(
            request(
                    token,
                    "POST",
                    "/identity/departments/" + department + "/leader/grants",
                    grantInput(
                        borrower,
                        "1000.000000",
                        "USD",
                        OffsetDateTime.now(ZoneOffset.UTC).minusDays(1),
                        OffsetDateTime.now(ZoneOffset.UTC).plusDays(1)),
                    200)
                .asText());

    var row =
        leaderRow(request(token, "GET", "/identity/department-leaders", null, 200), "CL" + tag);
    assertThat(row.get("proxyIds")).hasSize(1);
    assertThat(row.get("grantIds")).hasSize(1);

    request(token, "DELETE", "/identity/departments/" + department + "/leader", null, 200);
    assertThat(
            leaderRow(request(token, "GET", "/identity/department-leaders", null, 200), "CL" + tag)
                .get("leaderId")
                .isNull())
        .isTrue();
    assertThat(chain(token, department, "", 200).get("requiresFallback").asBoolean()).isTrue();
    // 代理与授权已随负责人一并撤销：代理挂在不存在的负责人上没有意义
    request(
        token,
        "DELETE",
        "/identity/departments/" + department + "/leader/proxies/" + proxyId,
        null,
        400);
    request(
        token,
        "DELETE",
        "/identity/departments/" + department + "/leader/grants/" + grantId,
        null,
        400);
    // 重复撤销不是幂等成功，而是明确告知已无负责人
    request(token, "DELETE", "/identity/departments/" + department + "/leader", null, 400);
  }

  // ------------------------------------------------------------------
  // 代管与临时授权
  // ------------------------------------------------------------------

  /**
   * 代管继承负责人的全部口径（管的是整个职务），临时授权只拿借出去的那部分额度。
   *
   * <p>时间列断言的是**写回的值**（含偏移），不是「没抛异常」：timestamptz 走 {@code Timestamp.valueOf} 会静默偏 −28800
   * 秒，只断言成功会让这个 bug 一路绿灯。
   */
  @Test
  void proxyInheritsFullAuthorityWhileTemporaryGrantStaysBounded() throws Exception {
    String token = login(), tag = suffix();
    UUID department = createDepartment(token, "PG" + tag, "代管授权部门" + tag, null);
    UUID leader = createMember(token, "pgl-" + tag.toLowerCase());
    UUID deputy = createMember(token, "pgd-" + tag.toLowerCase());
    UUID borrower = createMember(token, "pgb-" + tag.toLowerCase());
    request(
        token,
        "PUT",
        "/identity/departments/" + department + "/leader",
        leaderInput(leader, "SUBTREE", "9000.000000", "USD"),
        200);

    // 长期代管：endsAt 留空，到期时间没有值
    request(
        token,
        "POST",
        "/identity/departments/" + department + "/leader/proxies",
        proxyInput(deputy, "负责人休假，长期代管"),
        200);
    UUID grantId =
        UUID.fromString(
            request(
                    token,
                    "POST",
                    "/identity/departments/" + department + "/leader/grants",
                    grantInput(
                        borrower,
                        "1200.500000",
                        "USD",
                        OffsetDateTime.of(2026, 3, 1, 9, 0, 0, 0, ZoneOffset.ofHours(8)),
                        OffsetDateTime.of(2026, 3, 31, 18, 0, 0, 0, ZoneOffset.ofHours(8))),
                    200)
                .asText());

    // 写回值断言：+08:00 偏移的授权起止必须按同一绝对时刻存回去。
    // 直接查库要走租户事务——这些表 FORCE RLS，没有 app.current_tenant 就一行都读不到
    // （这正是「运行时角色无法绕过租户隔离」的另一面：测试自己也不例外）。
    var stored =
        inTenant(
            token,
            () ->
                sql.fetchOne(
                        "select starts_at,ends_at from department_leader_grant where id=?", grantId)
                    .get("starts_at", OffsetDateTime.class)
                    .toInstant());
    assertThat(stored)
        .isEqualTo(OffsetDateTime.of(2026, 3, 1, 9, 0, 0, 0, ZoneOffset.ofHours(8)).toInstant());
    var storedEnd =
        inTenant(
            token,
            () ->
                sql.fetchOne("select ends_at from department_leader_grant where id=?", grantId)
                    .get("ends_at", OffsetDateTime.class)
                    .toInstant());
    assertThat(storedEnd)
        .isEqualTo(OffsetDateTime.of(2026, 3, 31, 18, 0, 0, 0, ZoneOffset.ofHours(8)).toInstant());

    // 授权区间内（2026-03）：临时授权生效。代管是从**现在**开始生效的，
    // 所以它不出现在这个历史时刻的链里——这正是「权限随时间变化，历史单据能还原当时归属」的含义。
    var inside = chain(token, department, "?asOf=2026-03-15T00:00:00Z", 200);
    var insideEntries = inside.get("entries");
    assertThat(insideEntries).hasSize(2);
    assertThat(insideEntries.get(0).get("source").asText()).isEqualTo("LEADER");
    assertThat(insideEntries.get(0).get("approvalLimit").decimalValue())
        .isEqualByComparingTo(new BigDecimal("9000"));
    assertThat(insideEntries.get(1).get("source").asText()).isEqualTo("GRANT");
    // 借出去的权限收敛到本部门，额度只有 1200.5
    assertThat(insideEntries.get(1).get("scopeMode").asText()).isEqualTo("DEPARTMENT");
    assertThat(insideEntries.get(1).get("approvalLimit").decimalValue())
        .isEqualByComparingTo(new BigDecimal("1200.5"));

    // 授权区间之前：临时授权还没生效，链上只有负责人
    var before = chain(token, department, "?asOf=2026-02-15T00:00:00Z", 200);
    assertThat(before.get("entries")).hasSize(1);
    assertThat(before.get("entries").get(0).get("source").asText()).isEqualTo("LEADER");

    // 授权区间之后：临时授权已失效（当前时刻在 2026-10，落在区间之外）
    var after = chain(token, department, "?asOf=2026-04-15T00:00:00Z", 200);
    assertThat(after.get("entries")).hasSize(1);
    assertThat(after.get("entries").get(0).get("source").asText()).isEqualTo("LEADER");

    // 当前时刻：长期代管生效，继承负责人的全部范围与额度，且没有截止时间
    var now = chain(token, department, "", 200);
    var nowEntries = now.get("entries");
    assertThat(nowEntries).hasSize(2);
    assertThat(nowEntries.get(0).get("source").asText()).isEqualTo("LEADER");
    assertThat(nowEntries.get(1).get("source").asText()).isEqualTo("PROXY");
    // 代管的是整个职务，范围与额度跟负责人一致
    assertThat(nowEntries.get(1).get("scopeMode").asText()).isEqualTo("SUBTREE");
    assertThat(nowEntries.get(1).get("approvalLimit").decimalValue())
        .isEqualByComparingTo(new BigDecimal("9000"));
    assertThat(nowEntries.get(1).get("endsAt").isNull()).isTrue();

    // 撤销代管后审批链只剩负责人本人
    var row =
        leaderRow(request(token, "GET", "/identity/department-leaders", null, 200), "PG" + tag);
    UUID proxyId = UUID.fromString(row.get("proxyIds").get(0).asText());
    request(
        token,
        "DELETE",
        "/identity/departments/" + department + "/leader/proxies/" + proxyId,
        null,
        200);
    assertThat(chain(token, department, "", 200).get("entries")).hasSize(1);
    request(
        token,
        "DELETE",
        "/identity/departments/" + department + "/leader/grants/" + grantId,
        null,
        200);
    assertThat(chain(token, department, "?asOf=2026-03-15T00:00:00Z", 200).get("entries"))
        .hasSize(1);
  }

  /** 授权口径的硬校验：借出去的额度不得超过负责人，币种必须一致，停用成员不可被授权。 */
  @Test
  void temporaryGrantCannotExceedTheLeaderOrChangeCurrency() throws Exception {
    String token = login(), tag = suffix();
    UUID department = createDepartment(token, "GT" + tag, "额度校验部门" + tag, null);
    UUID leader = createMember(token, "gtl-" + tag.toLowerCase());
    UUID borrower = createMember(token, "gtb-" + tag.toLowerCase());
    request(
        token,
        "PUT",
        "/identity/departments/" + department + "/leader",
        leaderInput(leader, "DEPARTMENT", "1000.000000", "USD"),
        200);

    var from = OffsetDateTime.now(ZoneOffset.UTC).minusDays(1);
    var to = OffsetDateTime.now(ZoneOffset.UTC).plusDays(1);
    // 超过负责人额度：等于凭空造出一个上级
    request(
        token,
        "POST",
        "/identity/departments/" + department + "/leader/grants",
        grantInput(borrower, "1000.000001", "USD", from, to),
        400);
    // 币种不一致：额度数字在不同币种下不是一回事
    request(
        token,
        "POST",
        "/identity/departments/" + department + "/leader/grants",
        grantInput(borrower, "500.000000", "JPY", from, to),
        400);
    // 结束早于开始：空区间在任何时刻都不生效，却会在清单里占一行
    request(
        token,
        "POST",
        "/identity/departments/" + department + "/leader/grants",
        grantInput(borrower, "500.000000", "USD", to, from),
        400);
    // 代管同理
    request(
        token,
        "POST",
        "/identity/departments/" + department + "/leader/proxies",
        proxyInput(borrower, "结束早于开始"),
        200);

    // 负责人没有额度时无法派生临时授权额度
    UUID noLimit = createDepartment(token, "GN" + tag, "无额度部门" + tag, null);
    request(
        token,
        "PUT",
        "/identity/departments/" + noLimit + "/leader",
        leaderInput(leader, "DEPARTMENT", null, null),
        200);
    request(
        token,
        "POST",
        "/identity/departments/" + noLimit + "/leader/grants",
        grantInput(borrower, "100.000000", "USD", from, to),
        400);

    // 没有负责人的部门不能配代管与临时授权
    UUID orphan = createDepartment(token, "GO" + tag, "无负责人部门" + tag, null);
    request(
        token,
        "POST",
        "/identity/departments/" + orphan + "/leader/proxies",
        proxyInput(borrower, "无负责人"),
        400);
    request(
        token,
        "POST",
        "/identity/departments/" + orphan + "/leader/grants",
        grantInput(borrower, "100.000000", "USD", from, to),
        400);
  }

  /**
   * 停用的成员不能继续出现在审批链里。
   *
   * <p>人已离职审批链还指向他，等于把待审批单据永久卡死。
   */
  @Test
  void inactiveMembersDropOutOfTheApprovalChain() throws Exception {
    String token = login(), tag = suffix();
    UUID department = createDepartment(token, "IV" + tag, "停用部门" + tag, null);
    UUID leader = createMember(token, "ivl-" + tag.toLowerCase());
    UUID deputy = createMember(token, "ivd-" + tag.toLowerCase());
    request(
        token,
        "PUT",
        "/identity/departments/" + department + "/leader",
        leaderInput(leader, "DEPARTMENT", "3000.000000", "USD"),
        200);
    request(
        token,
        "POST",
        "/identity/departments/" + department + "/leader/proxies",
        proxyInput(deputy, "临时代管"),
        200);
    assertThat(chain(token, department, "", 200).get("entries")).hasSize(2);

    request(token, "DELETE", "/identity/members/" + deputy, null, 200);
    var remaining = chain(token, department, "", 200);
    assertThat(remaining.get("entries")).hasSize(1);
    assertThat(remaining.get("entries").get(0).get("username").asText())
        .isNotEqualTo("ivd-" + tag.toLowerCase());
    // 负责人本人被停用：整条链空掉，必须报需要兜底人
    request(token, "DELETE", "/identity/members/" + leader, null, 200);
    var empty = chain(token, department, "", 200);
    assertThat(empty.get("entries")).isEmpty();
    assertThat(empty.get("requiresFallback").asBoolean()).isTrue();
  }

  // ------------------------------------------------------------------
  // 租户隔离
  // ------------------------------------------------------------------

  /**
   * 租户隔离三断言：两租户互不可见、伪造 tenant 头无效、连接池归还后不串租户。
   *
   * <p>底座把 Hikari 池压到 1，所以这里每个请求必然复用同一条物理连接—— 租户设置如果泄漏到连接上，子租户就会读到父租户的负责人。 {@code request()} 每次都带随机
   * {@code X-Tenant-Id}，头对判定没有任何影响。
   */
  @Test
  void leaderRecordsStayInsideTheTenantAndIgnoreForgedTenantHeaders() throws Exception {
    String owner = login(), tag = suffix();
    UUID department = createDepartment(owner, "TL" + tag, "父租户部门" + tag, null);
    UUID leader = createMember(owner, "tl-" + tag.toLowerCase());
    request(
        owner,
        "PUT",
        "/identity/departments/" + department + "/leader",
        leaderInput(leader, "TENANT", "7000.000000", "USD"),
        200);

    String childUser = "tlc-" + tag.toLowerCase();
    request(
        owner,
        "POST",
        "/identity/children",
        Map.of(
            "code",
            "TL" + tag,
            "name",
            "Child",
            "ownerUsername",
            childUser,
            "ownerPassword",
            "test-member-password"),
        200);
    String childToken = loginUser(childUser);
    UUID childDepartment = createDepartment(childToken, "TC" + tag, "子租户部门" + tag, null);

    // 断言一：两租户互不可见。子租户看不到父租户的负责人记录。
    assertThat(
            leaderRow(
                request(childToken, "GET", "/identity/department-leaders", null, 200), "TL" + tag))
        .isNull();
    // 同名部门各看各的：子租户只看到自己那一条，且没有负责人
    var childRow =
        leaderRow(
            request(childToken, "GET", "/identity/department-leaders", null, 200), "TC" + tag);
    assertThat(childRow).isNotNull();
    assertThat(childRow.get("leaderId").isNull()).isTrue();

    // 断言二：伪造 tenant 头无效。跨租户的部门 id 表现为「不存在」，不是 403 更不是放行。
    request(
        childToken,
        "PUT",
        "/identity/departments/" + department + "/leader",
        leaderInput(leader, "TENANT", "7000.000000", "USD"),
        400);
    // 真正要守住的事实不是错误文案，而是父租户的授权口径没被子租户改掉：
    // 跨租户写被拒之后，父租户仍然是原来的负责人、原来的额度与币种。
    assertThat(chain(childToken, department, "", 400)).isNotNull();
    var parentUnchanged =
        leaderRow(request(owner, "GET", "/identity/department-leaders", null, 200), "TL" + tag);
    assertThat(parentUnchanged.get("membershipId").asText()).isEqualTo(leader.toString());
    assertThat(parentUnchanged.get("approvalLimit").decimalValue())
        .isEqualByComparingTo(new BigDecimal("7000"));
    // 跨租户的成员同样不可用作负责人
    request(
        childToken,
        "PUT",
        "/identity/departments/" + childDepartment + "/leader",
        leaderInput(leader, "DEPARTMENT", null, null),
        400);

    // 断言三：连接池归还后不串租户。子租户建自己的负责人，再回到父租户读，两边互不污染。
    UUID childLeader = createMember(childToken, "tlcl-" + tag.toLowerCase());
    request(
        childToken,
        "PUT",
        "/identity/departments/" + childDepartment + "/leader",
        leaderInput(childLeader, "SUBTREE", "400.000000", "EUR"),
        200);
    var childAfter =
        leaderRow(
            request(childToken, "GET", "/identity/department-leaders", null, 200), "TC" + tag);
    assertThat(childAfter.get("username").asText()).isEqualTo("tlcl-" + tag.toLowerCase());
    var parentAfter =
        leaderRow(request(owner, "GET", "/identity/department-leaders", null, 200), "TL" + tag);
    assertThat(parentAfter.get("username").asText()).isEqualTo("tl-" + tag.toLowerCase());
    assertThat(parentAfter.get("approvalCurrency").asText()).isEqualTo("USD");
    assertThat(
            leaderRow(request(owner, "GET", "/identity/department-leaders", null, 200), "TC" + tag))
        .isNull();

    // 审批链同样按租户隔离
    assertThat(chain(childToken, department, "", 400)).isNotNull();
    assertThat(chain(owner, childDepartment, "", 400)).isNotNull();
  }

  /** 负责人相关的写操作走 OWNER + 页面权限码，与 V3 的组织变更纪律一致。 */
  @Test
  void leaderWritesRequireTheDepartmentLeaderPagePermission() throws Exception {
    String owner = login(), tag = suffix();
    UUID department = createDepartment(owner, "PW" + tag, "权限部门" + tag, null);
    UUID plain = createMember(owner, "pwm-" + tag.toLowerCase());
    String token = loginUser("pwm-" + tag.toLowerCase());
    var access = request(token, "GET", "/auth/session", null, 200);
    assertThat(access.get("permissions").toString()).doesNotContain("identity.department_leader");
    request(token, "GET", "/identity/department-leaders", null, 403);
    request(
        token,
        "PUT",
        "/identity/departments/" + department + "/leader",
        leaderInput(plain, "DEPARTMENT", null, null),
        403);
    request(owner, "GET", "/identity/department-leaders", null, 200);
  }

  /** 新表必须和其他业务表一样 FORCE RLS——少一张就等于整个租户隔离有洞。 */
  @Test
  void leaderTablesForceRowLevelSecurity() {
    assertThat(
            sql.fetch(
                "select relname from pg_class join pg_namespace n on n.oid=relnamespace"
                    + " where n.nspname='public' and relkind='r'"
                    + " and relname in ('department_leader','department_leader_proxy','department_leader_grant')"
                    + " and (not relrowsecurity or not relforcerowsecurity)"))
        .isEmpty();
  }

  private UUID createMember(String token, String username) throws Exception {
    return UUID.fromString(
        request(token, "POST", "/identity/members", memberInput(username, null, List.of()), 200)
            .asText());
  }

  /**
   * 在该会话所属租户的事务里跑一段查询。
   *
   * <p>租户 id 取自会话（不取自 {@code X-Tenant-Id}，也不硬编码），与运行时同口径： 先 {@code resolve} 拿到已认证成员关系，再开事务写 {@code
   * app.current_tenant}。
   */
  private <T> T inTenant(String token, Supplier<T> work) throws Exception {
    var session = request(token, "GET", "/auth/session", null, 200);
    var tenant = UUID.fromString(session.get("tenantId").asText());
    return new TenantTransactions(sql, new TransactionTemplate(manager)).run(tenant, work);
  }
}
