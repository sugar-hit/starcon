package com.starcon.identity;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.starcon.tenancy.TenantTransactions;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 17.2.2.2 成员开通与入职。
 *
 * <p>本模块的重点不是「能建一个成员」——那件事 17.2.2.1 已经做过了——而是两条它独有的性质：
 *
 * <ol>
 *   <li><b>凭据纪律</b>：初始凭据由服务端生成，只在创建那一次响应里返回明文。 用例必须证明它真的能用（本人登录得上）、之后真的再也拿不到（列表、审计、库里都没有）。
 *   <li><b>租户隔离</b>：入职记录是本租户的业务数据，两租户互不可见、伪造 header 无效、 连接池归还后不串租户三条断言在这里实跑（底座把池压到
 *       1，每个请求必然复用同一条物理连接）。
 * </ol>
 *
 * <p>蓝图点名排除邮件邀请 / 接受令牌 / 自助激活，所以这里**没有**相关用例—— 它们属于后续独立 PRD，将来落地时必须连同令牌摘要、有效期、单次使用、防枚举与撤销规则一起做。
 */
class MemberOnboardingIntegrationTest extends IdentityIntegrationTestBase {

  private TenantTransactions tx() {
    return new TenantTransactions(sql, new TransactionTemplate(manager));
  }

  /** 建一个只有 catalog 权限的自定义角色：非 ALL 范围只允许绑纯 catalog 权限。 */
  private UUID roleId(String token, String tag) throws Exception {
    return UUID.fromString(
        request(
                token,
                "POST",
                "/identity/roles",
                Map.of(
                    "code",
                    "ONB_" + tag,
                    "name",
                    "入职测试角色",
                    "status",
                    "ACTIVE",
                    "permissions",
                    List.of("catalog.spu.read")),
                200)
            .asText());
  }

  /** 建一个带管理权限的角色：验证它不能绑非 ALL 范围。 */
  private UUID adminRoleId(String token, String tag) throws Exception {
    return UUID.fromString(
        request(
                token,
                "POST",
                "/identity/roles",
                Map.of(
                    "code",
                    "ONBA_" + tag,
                    "name",
                    "入职管理角色",
                    "status",
                    "ACTIVE",
                    "permissions",
                    List.of("identity.role.read")),
                200)
            .asText());
  }

  private Map<String, Object> onboardingInput(
      String username, UUID department, List<Map<String, Object>> roles) {
    var input = new java.util.LinkedHashMap<String, Object>();
    input.put("username", username);
    input.put("departmentId", department);
    input.put("roles", roles);
    return input;
  }

  private List<Map<String, Object>> scope(UUID roleId, String mode) {
    return List.of(Map.of("roleId", roleId, "scopeMode", mode));
  }

  /** 用指定凭据登录：入职返回的是随机初始密码，不能走底座那个固定密码的 loginUser。 */
  private String loginWith(String username, String password) throws Exception {
    return request(
            "", "POST", "/auth/login", Map.of("username", username, "password", password), 200)
        .get("token")
        .asText();
  }

  /** 造一个子租户，返回它的所有者账号名。 */
  private String childOwner(String ownerToken, String tag) throws Exception {
    String username = "onb-child-" + tag;
    request(
            ownerToken,
            "POST",
            "/identity/children",
            Map.of(
                "code",
                "ONBC_" + tag,
                "name",
                "入职子租户",
                "ownerUsername",
                username,
                "ownerPassword",
                "test-member-password"),
            200)
        .asText();
    return username;
  }

  /**
   * 新建账号：初始凭据一次性返回、真的能登录、之后再也拿不到。
   *
   * <p>这是本模块最核心的一条：凭据必须**可用**（否则等于没发）， 又必须**只出现一次**（否则它就不是初始凭据而是永久密码）。
   */
  @Test
  void createdAccountReturnsItsCredentialExactlyOnce() throws Exception {
    String token = login(), tag = suffix();
    UUID role = roleId(token, tag);
    UUID department = createDepartment(token, "ONBD" + tag, "入职部门", null);
    String username = "onb-new-" + tag;

    var created =
        request(
            token,
            "POST",
            "/identity/onboardings",
            onboardingInput(username, department, scope(role, "DEPARTMENT")),
            200);

    assertThat(created.get("accountMode").asText()).isEqualTo("CREATED");
    String credential = created.get("initialCredential").asText();
    assertThat(credential).isNotBlank().hasSize(20);

    // 凭据必须真的可用：本人用它换到的会话带着刚给的部门与范围。
    String memberToken = loginWith(username, credential);
    var access = request(memberToken, "GET", "/auth/session", null, 200);
    assertThat(access.get("departmentId").asText()).isEqualTo(department.toString());
    assertThat(access.get("grants").get(0).get("scopeMode").asText()).isEqualTo("DEPARTMENT");
    request(memberToken, "GET", "/identity/onboardings", null, 403);

    // V47「有角色必有范围」：DEPARTMENT 分配必须真的挂上一条对象范围，落在本人所属部门上。
    // 这条断言钉住的是 V50：V47 之前根本不存在这道闸，入职写下的 DEPARTMENT 分配是
    // 「声明了范围却没有任何范围对象」的残缺记录，靠 V47 的延迟约束触发器才终于被挡住。
    UUID memberId = UUID.fromString(access.get("membershipId").asText());
    var scopes =
        tx().run(
                UUID.fromString(accessTenant(token)),
                () ->
                    sql.fetch(
                        "select scope_type,scope_id from role_assignment_scope"
                            + " where membership_id=? and scope_type='DEPARTMENT'",
                        memberId));
    assertThat(scopes).hasSize(1);
    assertThat(scopes.get(0).get("scope_id", UUID.class)).isEqualTo(department);

    // 列表里再取一次：明文已经不存在，只剩一个「发过没有」的布尔位。
    var listed = request(token, "GET", "/identity/onboardings", null, 200);
    var row = findOnboarding(listed, created.get("onboardingId").asText());
    assertThat(row).isNotNull();
    assertThat(row.get("credentialIssued").asBoolean()).isTrue();
    assertThat(row.get("username").asText()).isEqualTo(username);
    assertThat(row.get("departmentName").asText()).isEqualTo("入职部门");
    assertThat(row.get("roleCodes").toString()).contains("ONB_" + tag);
    assertThat(row.get("status").asText()).isEqualTo("PENDING");
    assertThat(row.toString()).doesNotContain(credential);

    // 明文在任何读路径上都不存在：整个列表的 JSON 里搜不到它。
    assertThat(listed.toString()).doesNotContain(credential);

    // 库里只有 bcrypt 哈希，且凭据没有在 member_onboarding 上另存一份。
    // 直读 app_user 必须用迁移账号：运行时角色 identity_app 对 app_user 根本没有 GRANT
    // （这正是「全局用户表不可枚举」的实现方式），所以这里刻意绕开运行时角色，
    // 用一个只有迁移/运维才拿得到的连接来验证存储形态。
    try (var connection =
            java.sql.DriverManager.getConnection(
                pg().getJdbcUrl(), pg().getUsername(), pg().getPassword());
        var statement =
            connection.prepareStatement(
                "select u.password_hash, (select count(*) from member_onboarding o"
                    + " where o.user_id=u.id and o.credential_issued) as issued"
                    + " from app_user u where u.username=?")) {
      statement.setString(1, username);
      try (var rs = statement.executeQuery()) {
        assertThat(rs.next()).isTrue();
        String hash = rs.getString("password_hash");
        assertThat(hash).startsWith("$2").doesNotContain(credential);
        assertThat(rs.getInt("issued")).isEqualTo(1);
      }
    }
  }

  /**
   * 三种范围各自在库里留下什么形状：ALL / SELF 不带对象范围，DEPARTMENT 带且只带本人部门。
   *
   * <p>SELF 单独点出来是因为它最容易在后来的重构里被改坏：它属于「非 ALL」， 一条「非 ALL 就必须补一条范围对象」的误解就会给 SELF 挂上对象， 而 V47
   * 明确判定那是自相矛盾的记录（既声明仅本人、又声明限死在某个部门）。 这里是直接读库，不是看接口回显——回显只会告诉你请求被接受了。
   */
  @Test
  void everyScopeModeLeavesACompleteAssignmentRow() throws Exception {
    String token = login(), tag = suffix();
    UUID role = roleId(token, tag);
    UUID department = createDepartment(token, "ONBS" + tag, "范围形态部门", null);
    UUID tenant = UUID.fromString(accessTenant(token));

    var self = "onb-shape-self-" + tag;
    request(
        token,
        "POST",
        "/identity/onboardings",
        onboardingInput(self, null, scope(role, "SELF")),
        200);
    UUID selfMember =
        UUID.fromString(
            findOnboarding(
                    request(token, "GET", "/identity/onboardings", null, 200),
                    onboardingIdOf(self, token))
                .get("membershipId")
                .asText());
    // SELF：行级范围（仅本人数据），没有对象可列，因此一条范围对象都不该有。
    assertThat(
            tx().run(
                    tenant,
                    () ->
                        sql.fetchValue(
                            "select count(*) from role_assignment_scope where membership_id=?",
                            selfMember)))
        .isEqualTo(0L);

    var dept = "onb-shape-dept-" + tag;
    request(
        token,
        "POST",
        "/identity/onboardings",
        onboardingInput(dept, department, scope(role, "DEPARTMENT")),
        200);
    UUID deptMember =
        UUID.fromString(
            findOnboarding(
                    request(token, "GET", "/identity/onboardings", null, 200),
                    onboardingIdOf(dept, token))
                .get("membershipId")
                .asText());
    var deptScopes =
        tx().run(
                tenant,
                () ->
                    sql.fetch(
                        "select scope_type,scope_id from role_assignment_scope where membership_id=?",
                        deptMember));
    assertThat(deptScopes).hasSize(1);
    assertThat(deptScopes.get(0).get("scope_type", String.class)).isEqualTo("DEPARTMENT");
    assertThat(deptScopes.get(0).get("scope_id", UUID.class)).isEqualTo(department);

    // 全库不留残缺分配：两条分配要么是 ALL/SELF（无对象），要么挂了对象，没有中间态。
    // 这条断言直接对应 V47 延迟约束触发器要挡的东西。
    assertThat(
            tx().run(
                    tenant,
                    () ->
                        sql.fetchValue(
                            "select count(*) from role_assignment ra where ra.tenant_id=?::uuid"
                                + " and ra.scope_mode not in ('ALL','SELF') and not exists("
                                + "select 1 from role_assignment_scope s where s.membership_id=ra.membership_id"
                                + " and s.role_id=ra.role_id)",
                            tenant)))
        .isEqualTo(0L);
  }

  /** 用用户名在入职记录列表里定位 id。 */
  private String onboardingIdOf(String username, String token) throws Exception {
    JsonNode rows = request(token, "GET", "/identity/onboardings", null, 200);
    for (JsonNode row : rows)
      if (username.equals(row.get("username").asText())) return row.get("id").asText();
    throw new AssertionError("找不到入职记录：" + username);
  }

  /** 审计里不能出现凭据，也不能出现任何长得像凭据的字段。 */
  @Test
  void auditRecordsOnboardingWithoutAnyCredential() throws Exception {
    String token = login(), tag = suffix();
    UUID role = roleId(token, tag);
    String username = "onb-audit-" + tag;
    var created =
        request(
            token,
            "POST",
            "/identity/onboardings",
            onboardingInput(username, null, scope(role, "ALL")),
            200);
    String credential = created.get("initialCredential").asText();
    UUID onboardingId = UUID.fromString(created.get("onboardingId").asText());

    // ALL 范围：V47 判定它自带完整范围，因此**不该**有任何范围对象行。
    // 这条同时钉住 V50 补上的 assigned_by——该列自 V47 起 NOT NULL，
    // 而入职写入路径一度没有它，导致连全量范围的开通都撞约束（V47 之前它还不存在，所以看不出来）。
    var allAssignment =
        tx().run(
                UUID.fromString(accessTenant(token)),
                () ->
                    sql.fetch(
                        "select assigned_by,(select count(*) from role_assignment_scope s"
                            + " where s.membership_id=ra.membership_id and s.role_id=ra.role_id) as objects"
                            + " from role_assignment ra where membership_id=?",
                        UUID.fromString(created.get("membershipId").asText())));
    assertThat(allAssignment).hasSize(1);
    // assigned_by 是操作人本人（放人进来的是他，不是系统），不能是空。
    assertThat(allAssignment.get(0).get("assigned_by", UUID.class)).isNotNull();
    assertThat(allAssignment.get(0).get("objects", Integer.class)).isZero();

    var audit =
        tx().run(
                UUID.fromString(accessTenant(token)),
                () ->
                    sql.fetch(
                        "select action,resource_type,changes::text as changes from identity_audit"
                            + " where resource_id=?",
                        onboardingId));
    assertThat(audit).isNotEmpty();
    assertThat(audit.get(0).get("action", String.class)).isEqualTo("MemberOnboarded");
    assertThat(audit.get(0).get("resource_type", String.class)).isEqualTo("identity.onboarding");
    var changes = audit.get(0).get("changes", String.class);
    assertThat(changes).contains("accountMode").contains("CREATED").contains("ONB_" + tag);
    // 蓝图：不在页面或日志返回完整初始凭据。
    assertThat(changes)
        .doesNotContain(credential)
        .doesNotContain("password")
        .doesNotContain("$2a$");
  }

  /**
   * 关联已有全局账号：不返回凭据，也不改掉这个人原有的密码。
   *
   * <p>LINKED 的前提是服务端根本不知道那个人的密码，所以响应里必须是 null 而不是编一个—— 返回一个假的比不返回更糟：管理员会当真并转交一个登不上去的密码。
   */
  @Test
  void linkingAnExistingGlobalAccountReturnsNoCredential() throws Exception {
    String ownerToken = login(), tag = suffix();
    UUID role = roleId(ownerToken, tag);
    String username = "onb-shared-" + tag;

    var first =
        request(
            ownerToken,
            "POST",
            "/identity/onboardings",
            onboardingInput(username, null, scope(role, "ALL")),
            200);
    assertThat(first.get("accountMode").asText()).isEqualTo("CREATED");
    String originalCredential = first.get("initialCredential").asText();

    // 第二个租户用同一个账号名开通：账号已存在 → 只接成员关系。
    String childToken = loginUser(childOwner(ownerToken, tag));
    UUID childRole = roleId(childToken, tag + "B");
    var linked =
        request(
            childToken,
            "POST",
            "/identity/onboardings",
            onboardingInput(username, null, scope(childRole, "ALL")),
            200);

    assertThat(linked.get("accountMode").asText()).isEqualTo("LINKED");
    assertThat(linked.get("initialCredential").isNull()).isTrue();
    assertThat(linked.get("membershipId").asText())
        .isNotEqualTo(first.get("membershipId").asText());

    // 关联没有改动原密码：旧凭据仍然有效，且租户成员关系确实多了一项。
    // 这次登录 token 为 null 而不是发一个令牌：这个人现在属于两个租户，必须自己选一个进入
    // （见 IdentityController.login）。这正好是「成员关系是两个租户各自的」的证据。
    var reLogin =
        request(
            "",
            "POST",
            "/auth/login",
            Map.of("username", username, "password", originalCredential),
            200);
    assertThat(reLogin.get("token").isNull()).isTrue();
    assertThat(reLogin.get("memberships")).hasSize(2);
  }

  /** 完成入职是终态：记录操作人与时刻，并且不能翻回去。 */
  @Test
  void completingOnboardingIsTerminalAndRecorded() throws Exception {
    String token = login(), tag = suffix();
    UUID role = roleId(token, tag);
    var created =
        request(
            token,
            "POST",
            "/identity/onboardings",
            onboardingInput("onb-done-" + tag, null, scope(role, "ALL")),
            200);
    String id = created.get("onboardingId").asText();

    request(token, "POST", "/identity/onboardings/" + id + "/complete", null, 200);
    var row = findOnboarding(request(token, "GET", "/identity/onboardings", null, 200), id);
    assertThat(row.get("status").asText()).isEqualTo("COMPLETED");
    assertThat(row.get("completedAt").asText()).isNotBlank();
    assertThat(row.get("completedBy").asText()).isNotBlank();

    // 终态：再完成一次拿不到记录（记录还在，但已不是待办）。
    request(token, "POST", "/identity/onboardings/" + id + "/complete", null, 409);
    // 未知 id 是 404，不泄露「存在但无权」。
    request(token, "POST", "/identity/onboardings/" + UUID.randomUUID() + "/complete", null, 404);
  }

  /**
   * 开通时的服务端校验。
   *
   * <p>每一条都是「看起来开通成功、实际没生效」的坑：OWNER 只能走所有者转移， 停用角色不能给，本部门范围必须先有部门，管理权限只能绑全租户范围。
   */
  @Test
  void onboardingRejectsInvalidRolesDepartmentsAndDuplicates() throws Exception {
    String token = login(), tag = suffix();
    UUID role = roleId(token, tag);
    UUID adminRole = adminRoleId(token, tag);
    var me = request(token, "GET", "/auth/session", null, 200);
    UUID ownerRole = UUID.fromString(me.get("grants").get(0).get("roleId").asText());

    request(
        token,
        "PUT",
        "/identity/roles/" + adminRole,
        Map.of(
            "code",
            "ONBA_" + tag,
            "name",
            "入职管理角色",
            "status",
            "INACTIVE",
            "permissions",
            List.of("identity.role.read")),
        200);

    // 不能借开通直接发 OWNER：那会让「谁能成为所有者」这件事绕开转移流程。
    // 与 17.2.2.1 的 assignments() 同口径——是权限问题，不是字段格式问题，所以是 403。
    request(
        token,
        "POST",
        "/identity/onboardings",
        onboardingInput("onb-owner-" + tag, null, scope(ownerRole, "ALL")),
        403);
    // 不能分配停用角色。
    request(
        token,
        "POST",
        "/identity/onboardings",
        onboardingInput("onb-inactive-" + tag, null, scope(adminRole, "ALL")),
        400);
    // 本部门范围必须先有部门。
    request(
        token,
        "POST",
        "/identity/onboardings",
        onboardingInput("onb-nodep-" + tag, null, scope(role, "DEPARTMENT")),
        400);
    // 管理权限不能绑非全租户范围。
    UUID activeAdmin = adminRoleId(token, tag + "A");
    request(
        token,
        "POST",
        "/identity/onboardings",
        onboardingInput("onb-scope-" + tag, null, scope(activeAdmin, "SELF")),
        400);
    // 同一个角色不能重复给。
    request(
        token,
        "POST",
        "/identity/onboardings",
        onboardingInput(
            "onb-dup-" + tag, null, List.of(scope(role, "ALL").get(0), scope(role, "ALL").get(0))),
        400);
    // 角色必须属于本租户：跨租户角色在当前租户的事务里根本查不到，与「不存在」同答案。
    request(
        token,
        "POST",
        "/identity/onboardings",
        onboardingInput("onb-frole-" + tag, null, scope(UUID.randomUUID(), "ALL")),
        404);

    // 部门与角色都必须属于本租户：拿 A 租户的部门去 B 租户开通要被拒。
    // 这一条在两个租户之间做，否则测的只是「随机 uuid 不存在」，不是「跨租户引用」。
    UUID aDepartment = createDepartment(token, "ONBX" + tag, "跨租户部门", null);
    String bToken = loginUser(childOwner(token, tag));
    UUID bRole = roleId(bToken, tag + "B");
    request(
        bToken,
        "POST",
        "/identity/onboardings",
        onboardingInput("onb-fdept-" + tag, aDepartment, scope(bRole, "ALL")),
        400);

    // 已经在这个租户里有成员关系的人不能重复开通。
    request(
        token,
        "POST",
        "/identity/onboardings",
        onboardingInput("onb-twice-" + tag, null, scope(role, "ALL")),
        200);
    request(
        token,
        "POST",
        "/identity/onboardings",
        onboardingInput("onb-twice-" + tag, null, scope(role, "ALL")),
        409);
  }

  /** 开通入职是所有者专属：普通成员既不能建也不能完成。 */
  @Test
  void onboardingRequiresTheTenantOwner() throws Exception {
    String token = login(), tag = suffix();
    UUID role = roleId(token, tag);
    var created =
        request(
            token,
            "POST",
            "/identity/onboardings",
            onboardingInput("onb-plain-" + tag, null, scope(role, "ALL")),
            200);
    String credential = created.get("initialCredential").asText();
    String memberToken = loginWith("onb-plain-" + tag, credential);

    request(memberToken, "GET", "/identity/onboardings", null, 403);
    request(
        memberToken,
        "POST",
        "/identity/onboardings",
        onboardingInput("onb-escalate-" + tag, null, scope(role, "ALL")),
        403);
    request(
        memberToken,
        "POST",
        "/identity/onboardings/" + created.get("onboardingId").asText() + "/complete",
        null,
        403);
  }

  /**
   * 租户隔离三断言：两租户互不可见 / 伪造 tenant header 无效 / 连接池归还后不串租户。
   *
   * <p>底座把 Hikari 池压到 1，所以这里 A→B→A→B 的交替请求必然复用同一条物理连接： 租户设置如果泄漏在连接上，第二个租户就会看到第一个租户的入职记录。
   */
  @Test
  void onboardingRecordsAreIsolatedBetweenTenants() throws Exception {
    String aToken = login(), tag = suffix();
    UUID aRole = roleId(aToken, tag);
    String aUsername = "onb-iso-a-" + tag;
    var aCreated =
        request(
            aToken,
            "POST",
            "/identity/onboardings",
            onboardingInput(aUsername, null, scope(aRole, "ALL")),
            200);
    UUID aId = UUID.fromString(aCreated.get("onboardingId").asText());
    UUID aTenant = UUID.fromString(accessTenant(aToken));

    String bUsername = childOwner(aToken, tag);
    String bToken = loginUser(bUsername);
    UUID bRole = roleId(bToken, tag);
    UUID bTenant = UUID.fromString(accessTenant(bToken));
    var bCreated =
        request(
            bToken,
            "POST",
            "/identity/onboardings",
            onboardingInput("onb-iso-b-" + tag, null, scope(bRole, "ALL")),
            200);
    UUID bId = UUID.fromString(bCreated.get("onboardingId").asText());

    // 断言一：两租户互不可见。
    assertThat(
            findOnboarding(
                request(aToken, "GET", "/identity/onboardings", null, 200), bId.toString()))
        .isNull();
    assertThat(
            findOnboarding(
                request(bToken, "GET", "/identity/onboardings", null, 200), aId.toString()))
        .isNull();
    assertThat(request(bToken, "GET", "/identity/onboardings", null, 200).toString())
        .doesNotContain(aUsername);

    // 断言二：伪造 tenant header 无效——指向真实存在的 A 租户也换不来 A 的记录。
    var forged =
        http.perform(
                MockMvcRequestBuilders.get("/identity/onboardings")
                    .header("Authorization", "Bearer " + bToken)
                    .header("X-Tenant-Id", aTenant.toString()))
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(forged).doesNotContain(aId.toString()).doesNotContain(aUsername);
    assertThat(json.readTree(forged)).hasSize(1);

    // 断言三：连接池归还后不串租户——A→B→A 交替，直查也只能看到自己租户的行。
    // 注意不能断言「A 里正好一行」：底座容器由整个测试类共享，A 租户里还有其它用例建的入职记录，
    // 这里要证明的是「A 看不到 B 的那一条」，不是「A 里只有一条」。
    assertThat(
            tx().run(aTenant, () -> sql.fetch("select id from member_onboarding"))
                .getValues("id", UUID.class))
        .contains(aId)
        .doesNotContain(bId);
    assertThat(request(bToken, "GET", "/identity/onboardings", null, 200)).hasSize(1);
    assertThat(
            tx().run(
                    aTenant,
                    () ->
                        sql.fetch("select id from member_onboarding").getValues("id", UUID.class)))
        .contains(aId)
        .doesNotContain(bId);
    assertThat(request(aToken, "GET", "/identity/onboardings", null, 200).toString())
        .doesNotContain(bId.toString());
    assertThat(
            tx().run(
                    bTenant,
                    () ->
                        sql.fetch("select id from member_onboarding").getValues("id", UUID.class)))
        .containsExactly(bId);

    // 拿别人的入职 id 去完成，拿不到 404 而不是把它改掉。
    request(bToken, "POST", "/identity/onboardings/" + aId + "/complete", null, 404);
    assertThat(
            findOnboarding(
                    request(aToken, "GET", "/identity/onboardings", null, 200), aId.toString())
                .get("status")
                .asText())
        .isEqualTo("PENDING");

    // 没有 app.current_tenant 时整张表读不到任何行。
    assertThat(sql.fetch("select id from member_onboarding")).isEmpty();
  }

  /** 入职表与 app_user 一样是 FORCE RLS，运行时角色不能绕过。 */
  @Test
  void memberOnboardingForcesRlsAndKeepsNoCredentialColumn() {
    assertThat(
            sql.fetch(
                "select relname from pg_class join pg_namespace n on n.oid=relnamespace where"
                    + " n.nspname='public' and relname='member_onboarding'"
                    + " and (not relrowsecurity or not relforcerowsecurity)"))
        .isEmpty();
    var runtime =
        sql.fetchOne("select rolsuper,rolbypassrls from pg_roles where rolname=current_user");
    assertThat(runtime.get("rolsuper", Boolean.class)).isFalse();
    assertThat(runtime.get("rolbypassrls", Boolean.class)).isFalse();

    // 表结构里不存在任何能存凭据的列：只有 credential_issued 这个布尔位。
    var columns =
        sql.fetch(
                "select column_name from information_schema.columns where table_name='member_onboarding'")
            .getValues("column_name", String.class);
    assertThat(columns).contains("credential_issued", "credential_issued_at");
    assertThat(columns)
        .noneMatch(c -> c.contains("password") || c.contains("token") || c.contains("secret"));
    // 蓝图点名排除邮件邀请：没有邀请态列，将来补齐时必须连有效期与单次使用一起加。
    assertThat(columns).noneMatch(c -> c.contains("invitation") || c.contains("invite"));
  }

  private String accessTenant(String token) throws Exception {
    return request(token, "GET", "/auth/session", null, 200).get("tenantId").asText();
  }

  private JsonNode findOnboarding(JsonNode rows, String id) {
    for (JsonNode row : rows) if (id.equals(row.get("id").asText())) return row;
    return null;
  }
}
