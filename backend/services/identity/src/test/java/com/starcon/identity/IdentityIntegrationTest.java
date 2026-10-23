package com.starcon.identity;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.DriverManager;
import java.util.*;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.*;

/**
 * identity 服务集成测试（会话、角色成员、部门、法人、子租户）。
 *
 * <p>共享底座（真实 PostgreSQL 16 容器、登录与请求辅助）在 {@link IdentityIntegrationTestBase}； 审计日志的断言在 {@link
 * AuditIntegrationTest}。两者共用同一个容器实例，靠随机编码隔开数据。
 */
class IdentityIntegrationTest extends IdentityIntegrationTestBase {

  @Test
  void sessionMembershipRoleAndLogoutAreReal() throws Exception {
    http.perform(
            post("/auth/login")
                .contentType("application/json")
                .content("{\"username\":\"local-admin\",\"password\":\"wrong\"}"))
        .andExpect(status().isUnauthorized());
    http.perform(
            post("/auth/login")
                .contentType("application/json")
                .content(
                    "{\"username\":\"local-admin\",\"password\":\"test-admin-password\",\"membershipId\":\""
                        + UUID.randomUUID()
                        + "\"}"))
        .andExpect(status().isForbidden());
    String token = login();
    http.perform(get("/auth/session").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.permissions").isArray());
    http.perform(
            get("/internal/access")
                .header("Authorization", "Bearer " + token)
                .header("X-Internal-Key", "wrong"))
        .andExpect(status().isForbidden());
    http.perform(post("/auth/logout").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk());
    http.perform(get("/auth/session").header("Authorization", "Bearer " + token))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void suspendedAncestorInvalidatesExistingSession() throws Exception {
    String token = login();
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())) {
      var sql = DSL.using(c, SQLDialect.POSTGRES);
      UUID parent = UUID.randomUUID();
      sql.execute(
          "insert into tenant(id,code,name,status) values(?,?,?,'SUSPENDED')",
          parent,
          "PARENT",
          "Parent");
      sql.execute("update tenant set parent_tenant_id=? where code='LOCAL'", parent);
      try {
        http.perform(get("/auth/session").header("Authorization", "Bearer " + token))
            .andExpect(status().isUnauthorized());
      } finally {
        sql.execute("update tenant set parent_tenant_id=null where code='LOCAL'");
      }
    }
  }

  @Test
  void runtimeCannotEnumerateIdentityTables() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), "identity_app", "test-only")) {
      assertThatThrownBy(() -> c.createStatement().executeQuery("select * from app_user"))
          .isInstanceOf(java.sql.SQLException.class);
    }
  }

  @Test
  void rolesMembersDepartmentsAndRevocationWorkThroughAuthorizedApis() throws Exception {
    String token = login(), tag = suffix();
    String department =
        request(
                token,
                "POST",
                "/identity/departments",
                Map.of("code", "DEPT" + tag, "name", "Dept " + tag),
                200)
            .asText();
    String role =
        request(
                token,
                "POST",
                "/identity/roles",
                Map.of(
                    "code",
                    "EDITOR_" + tag,
                    "name",
                    "Editor",
                    "status",
                    "ACTIVE",
                    "permissions",
                    List.of("catalog.spu.read", "catalog.spu.update")),
                200)
            .asText();
    request(
        token,
        "POST",
        "/identity/roles",
        Map.of(
            "code",
            "BAD_" + tag,
            "name",
            "Bad",
            "status",
            "ACTIVE",
            "permissions",
            List.of("catalog.spu.update")),
        400);
    request(
        token,
        "POST",
        "/identity/roles",
        Map.of(
            "code",
            "UNKNOWN_" + tag,
            "name",
            "Bad",
            "status",
            "ACTIVE",
            "permissions",
            List.of("catalog.unknown.read")),
        400);
    String username = "member-" + tag;
    String member =
        request(
                token,
                "POST",
                "/identity/members",
                memberInput(
                    username,
                    department,
                    List.of(Map.of("roleId", role, "scopeMode", "DEPARTMENT"))),
                200)
            .asText();
    String memberToken = loginUser(username);
    var access = request(memberToken, "GET", "/auth/session", null, 200);
    assertThat(access.get("departmentId").asText()).isEqualTo(department);
    assertThat(access.get("grants").get(0).get("scopeMode").asText()).isEqualTo("DEPARTMENT");
    request(memberToken, "GET", "/identity/members", null, 403);
    request(
        memberToken,
        "PUT",
        "/identity/roles/" + role,
        Map.of(
            "code",
            "EDITOR_" + tag,
            "name",
            "Escalation",
            "status",
            "ACTIVE",
            "permissions",
            List.of("identity.role.read", "identity.role.update")),
        403);
    request(
        token,
        "PUT",
        "/identity/roles/" + role,
        Map.of(
            "code",
            "EDITOR_" + tag,
            "name",
            "Editor renamed",
            "status",
            "INACTIVE",
            "permissions",
            List.of("catalog.spu.read", "catalog.spu.update")),
        200);
    assertThat(request(memberToken, "GET", "/auth/session", null, 200).get("permissions").isEmpty())
        .isTrue();
    request(
        token,
        "PUT",
        "/identity/roles/" + role,
        Map.of(
            "code",
            "EDITOR_" + tag,
            "name",
            "Editor renamed",
            "status",
            "ACTIVE",
            "permissions",
            List.of("catalog.spu.read")),
        200);
    request(
        token,
        "PUT",
        "/identity/members/" + member,
        Map.of(
            "status",
            "ACTIVE",
            "departmentId",
            department,
            "assignments",
            List.of(Map.of("roleId", role, "scopeMode", "SELF"))),
        200);
    assertThat(
            request(memberToken, "GET", "/auth/session", null, 200)
                .get("grants")
                .get(0)
                .get("scopeMode")
                .asText())
        .isEqualTo("SELF");
    request(
        token,
        "PUT",
        "/identity/departments/" + department,
        Map.of("code", "DEPT" + tag, "name", "Renamed " + tag),
        200);
    request(token, "DELETE", "/identity/departments/" + department, null, 409);
    request(token, "DELETE", "/identity/roles/" + role, null, 200);
    assertThat(request(memberToken, "GET", "/auth/session", null, 200).get("grants").isEmpty())
        .isTrue();
    request(token, "DELETE", "/identity/members/" + member, null, 200);
    request(memberToken, "GET", "/auth/session", null, 401);
    // Removing the member releases its department, so no manual unlink is needed before deleting
    // it.
    request(token, "DELETE", "/identity/departments/" + department, null, 200);
  }

  @Test
  void childrenAreIndependentAndClosureAndDepthAndSuspensionAreEnforced() throws Exception {
    String parentToken = login(), tag = suffix(), username = "child-owner-" + tag;
    String child =
        request(
                parentToken,
                "POST",
                "/identity/children",
                Map.of(
                    "code",
                    "CHILD_" + tag,
                    "name",
                    "Child",
                    "ownerUsername",
                    username,
                    "ownerPassword",
                    "test-member-password"),
                200)
            .asText();
    String childToken = loginUser(username);
    var parent = request(parentToken, "GET", "/auth/session", null, 200);
    var childAccess = request(childToken, "GET", "/auth/session", null, 200);
    assertThat(childAccess.get("tenantId").asText()).isEqualTo(child);
    String childMember = childAccess.get("membershipId").asText();
    request(parentToken, "POST", "/auth/switch", Map.of("membershipId", childMember), 403);
    request(parentToken, "DELETE", "/identity/members/" + childMember, null, 404);
    assertThat(request(parentToken, "GET", "/auth/memberships", null, 200).toString())
        .doesNotContain(child);
    var tx =
        new com.starcon.tenancy.TenantTransactions(
            sql, new org.springframework.transaction.support.TransactionTemplate(manager));
    UUID parentId = UUID.fromString(parent.get("tenantId").asText()),
        childId = UUID.fromString(child);
    assertThat(
            tx.run(
                parentId, () -> sql.fetch("select id from membership").getValues("id", UUID.class)))
        .doesNotContain(UUID.fromString(childMember));
    assertThat(
            tx.run(
                childId, () -> sql.fetch("select id from membership").getValues("id", UUID.class)))
        .containsExactly(UUID.fromString(childMember));
    assertThat(sql.fetch("select * from membership")).isEmpty();
    assertThat(
            tx.run(
                parentId,
                () ->
                    sql.fetchOne("select depth from tenant_closure where descendant_id=?", childId)
                        .get("depth", Integer.class)))
        .isEqualTo(1);
    // Create three levels and verify the ancestor closure without granting ancestor membership.
    String grand =
        request(
                childToken,
                "POST",
                "/identity/children",
                Map.of(
                    "code",
                    "GRAND_" + tag,
                    "name",
                    "Grandchild",
                    "ownerUsername",
                    "grand-" + tag,
                    "ownerPassword",
                    "test-member-password"),
                200)
            .asText();
    assertThat(
            tx.run(
                parentId,
                () ->
                    sql.fetchOne(
                            "select depth from tenant_closure where descendant_id=?",
                            UUID.fromString(grand))
                        .get("depth", Integer.class)))
        .isEqualTo(2);
    String grandToken = loginUser("grand-" + tag);
    request(
        parentToken,
        "PUT",
        "/identity/children/" + child,
        Map.of("name", "Child renamed", "status", "SUSPENDED"),
        200);
    request(childToken, "GET", "/auth/session", null, 401);
    request(grandToken, "GET", "/auth/session", null, 401);
    request(
        parentToken,
        "PUT",
        "/identity/children/" + child,
        Map.of("name", "Child renamed", "status", "ACTIVE"),
        200);
    request(childToken, "GET", "/auth/session", null, 200);
    String current = grandToken;
    for (int depth = 3; depth <= 5; depth++) {
      String login = "depth-" + depth + "-" + tag;
      request(
          current,
          "POST",
          "/identity/children",
          Map.of(
              "code",
              "DEPTH_" + depth + "_" + tag,
              "name",
              "Deep",
              "ownerUsername",
              login,
              "ownerPassword",
              "test-member-password"),
          200);
      current = loginUser(login);
    }
    request(
        current,
        "POST",
        "/identity/children",
        Map.of(
            "code",
            "TOO_DEEP_" + tag,
            "name",
            "Too deep",
            "ownerUsername",
            "deep-failed-" + tag,
            "ownerPassword",
            "test-member-password"),
        409);
    assertThat(sql.fetch("select * from tenant_closure")).isEmpty();
    assertThatThrownBy(
            () ->
                tx.run(
                    childId,
                    () -> {
                      sql.fetch("select * from role");
                      throw new IllegalStateException("rollback");
                    }))
        .isInstanceOf(IllegalStateException.class);
    assertThat(sql.fetch("select * from role")).isEmpty();
  }

  @Test
  void ownerIsProtectedAndTransferIsExplicitAndCannotBeUsedToEscalate() throws Exception {
    String token = login(), tag = suffix();
    var me = request(token, "GET", "/auth/session", null, 200);
    // grants 是**无序聚合**（V47 的 session_access 用 jsonb_agg 且没有 ORDER BY），
    // grants.get(0) 取到哪个角色取决于查询计划，不取决于数据语义。
    // 这个成员一旦挂了第二个角色，第一个就可能是别的角色 —— 按 roleCode 显式挑 OWNER。
    String ownerRole = null;
    for (JsonNode grant : me.get("grants"))
      if ("OWNER".equals(grant.get("roleCode").asText()))
        ownerRole = grant.get("roleId").asText();
    assertThat(ownerRole).as("会话里必须带 OWNER 角色").isNotNull();
    String ownerMember = me.get("membershipId").asText();
    request(token, "DELETE", "/identity/roles/" + ownerRole, null, 403);
    request(token, "DELETE", "/identity/members/" + ownerMember, null, 403);
    String username = "successor-" + tag;
    String successor =
        request(token, "POST", "/identity/members", memberInput(username, null, List.of()), 200)
            .asText();
    var invalid = new HashMap<String, Object>();
    invalid.put("status", "ACTIVE");
    invalid.put("departmentId", null);
    invalid.put("assignments", List.of(Map.of("roleId", ownerRole, "scopeMode", "ALL")));
    request(token, "PUT", "/identity/members/" + successor, invalid, 403);
    String next = loginUser(username);
    request(next, "POST", "/identity/owner-transfer", Map.of("membershipId", successor), 403);
    request(token, "POST", "/identity/owner-transfer", Map.of("membershipId", successor), 200);
    request(token, "GET", "/identity/roles", null, 403);
    try {
      request(next, "DELETE", "/identity/members/" + successor, null, 403);
    } finally {
      request(next, "POST", "/identity/owner-transfer", Map.of("membershipId", ownerMember), 200);
    }
    request(token, "GET", "/identity/roles", null, 200);
  }

  @Test
  void allTenantTablesForceRlsAndGlobalUsersRemainInaccessible() {
    assertThat(
            sql.fetch(
                "select relname from pg_class join pg_namespace n on n.oid=relnamespace where n.nspname='public' and relkind='r' and relname in ('membership','role','role_permission','role_assignment','department','tenant_closure','identity_audit','identity_outbox','legal_entity','legal_entity_reference','tenant_hierarchy_grant') and (not relrowsecurity or not relforcerowsecurity)"))
        .isEmpty();
    var runtime =
        sql.fetchOne("select rolsuper,rolbypassrls from pg_roles where rolname=current_user");
    assertThat(runtime.get("rolsuper", Boolean.class)).isFalse();
    assertThat(runtime.get("rolbypassrls", Boolean.class)).isFalse();
  }

  // ------------------------------------------------------------------
  // 17.1.2 法人档案
  // ------------------------------------------------------------------

  @Test
  void legalEntityCrudCarriesBaseCurrencyAndRounding() throws Exception {
    String token = login();
    String code = "LE" + suffix();
    var created =
        request(token, "POST", "/identity/legal-entities", legalEntityInput(code, "USD"), 200);
    UUID id = UUID.fromString(created.asText());
    var listed = request(token, "GET", "/identity/legal-entities", null, 200);
    assertThat(listed.toString()).contains(code);
    // 本位币与舍入规则属于法人，决定 12 财务总账的记账基准
    assertThat(listed.findValuesAsText("baseCurrency")).contains("USD");
    assertThat(listed.findValuesAsText("rounding")).contains("HALF_UP");

    var edited = new HashMap<String, Object>(legalEntityInput(code, "EUR"));
    edited.put("amountScale", 4);
    request(token, "PUT", "/identity/legal-entities/" + id, edited, 200);
    var after = request(token, "GET", "/identity/legal-entities", null, 200);
    assertThat(after.findValuesAsText("baseCurrency")).contains("EUR");

    request(token, "DELETE", "/identity/legal-entities/" + id, null, 200);
    assertThat(request(token, "GET", "/identity/legal-entities", null, 200).toString())
        .doesNotContain(code);
  }

  @Test
  void legalEntityIsProtectedWhileBusinessObjectsReferenceIt() throws Exception {
    String token = login();
    String code = "LE" + suffix();
    var created =
        request(token, "POST", "/identity/legal-entities", legalEntityInput(code, "USD"), 200);
    UUID id = UUID.fromString(created.asText());
    UUID shop = UUID.randomUUID();
    request(
        token,
        "POST",
        "/identity/legal-entities/references",
        Map.of("legalEntityId", id, "source", "channel_shop", "referenceId", shop),
        200);
    // 仍被引用时拒绝删除：货权与记账主体不能凭空消失，停用才是可逆的做法
    request(token, "DELETE", "/identity/legal-entities/" + id, null, 409);
    var suspended = new HashMap<String, Object>(legalEntityInput(code, "USD"));
    suspended.put("status", "SUSPENDED");
    request(token, "PUT", "/identity/legal-entities/" + id, suspended, 200);
    request(token, "DELETE", "/identity/legal-entities/" + id, null, 409);
  }

  @Test
  void legalEntityReferenceMustPointAtAnEntityOfTheSameTenant() throws Exception {
    String token = login();
    String code = "LE" + suffix();
    var created =
        request(token, "POST", "/identity/legal-entities", legalEntityInput(code, "USD"), 200);
    request(
        token,
        "POST",
        "/identity/legal-entities/references",
        Map.of(
            "legalEntityId",
            UUID.randomUUID(),
            "source",
            "channel_shop",
            "referenceId",
            UUID.randomUUID()),
        400);
    assertThat(created.asText()).isNotBlank();
  }

  @Test
  void legalEntityIsReadableButOnlyOwnerMayMutateIt() throws Exception {
    String owner = login(), tag = suffix();
    // 只给读权限的财务角色：法人档案是记账基准，可以看，但改不动
    String role =
        request(
                owner,
                "POST",
                "/identity/roles",
                Map.of(
                    "code",
                    "FINANCE_" + tag,
                    "name",
                    "Finance",
                    "status",
                    "ACTIVE",
                    "permissions",
                    List.of("identity.legal_entity.read")),
                200)
            .asText();
    String username = "le-member-" + tag.toLowerCase();
    String member =
        request(
                owner,
                "POST",
                "/identity/members",
                memberInput(username, null, List.of(Map.of("roleId", role, "scopeMode", "ALL"))),
                200)
            .asText();
    try {
      String finance = loginUser(username);
      request(finance, "GET", "/identity/legal-entities", null, 200);
      request(
          finance, "POST", "/identity/legal-entities", legalEntityInput("LE" + tag, "USD"), 403);
      request(
          finance,
          "PUT",
          "/identity/legal-entities/" + UUID.randomUUID(),
          legalEntityInput("LE" + tag, "EUR"),
          403);
      request(finance, "DELETE", "/identity/legal-entities/" + UUID.randomUUID(), null, 403);
      // 登记引用同样是组织变更动作
      request(
          finance,
          "POST",
          "/identity/legal-entities/references",
          Map.of(
              "legalEntityId", UUID.randomUUID(),
              "source", "channel_shop",
              "referenceId", UUID.randomUUID()),
          403);
      // 变更权随角色状态一起撤销：停用角色后读写都归零
      request(
          owner,
          "PUT",
          "/identity/roles/" + role,
          Map.of(
              "code",
              "FINANCE_" + tag,
              "name",
              "Finance",
              "status",
              "INACTIVE",
              "permissions",
              List.of("identity.legal_entity.read")),
          200);
      request(finance, "GET", "/identity/legal-entities", null, 403);
    } finally {
      request(
          owner,
          "PUT",
          "/identity/members/" + member,
          Map.of("status", "INACTIVE", "assignments", List.of()),
          200);
      request(owner, "DELETE", "/identity/roles/" + role, null, 200);
    }
  }

  @Test
  void legalEntityCodeIsUniqueWithinTenant() throws Exception {
    String token = login();
    String code = "LE" + suffix();
    request(token, "POST", "/identity/legal-entities", legalEntityInput(code, "USD"), 200);
    request(token, "POST", "/identity/legal-entities", legalEntityInput(code, "EUR"), 409);
  }

  // ------------------------------------------------------------------
  // 17.2.1.1 部门树
  // ------------------------------------------------------------------

  /** 部门输入 + 可选引用（所属法人 / 负责人），避免每个断言都手搓 HashMap。 */
  /** 树里按 code 找节点，找不到返回 null，方便断言里直接取 children。 */
  @Test
  void departmentTreeIsHierarchicalAndCarriesLegalEntityLeaderAndMemberCount() throws Exception {
    String token = login(), tag = suffix();
    UUID entity =
        UUID.fromString(
            request(
                    token,
                    "POST",
                    "/identity/legal-entities",
                    legalEntityInput("LE" + tag, "USD"),
                    200)
                .asText());
    UUID root = createDepartment(token, "ROOT" + tag, "总部" + tag, null);
    UUID mid = createDepartment(token, "MID" + tag, "研发部" + tag, root);
    UUID leaf = createDepartment(token, "LEAF" + tag, "平台组" + tag, mid);

    // 负责人只是一个指向 membership 的字段引用：代管/临时授权属于 17.2.1.2，本模块不做。
    String leaderUser = "dept-leader-" + tag.toLowerCase();
    String leader =
        request(token, "POST", "/identity/members", memberInput(leaderUser, null, List.of()), 200)
            .asText();
    request(
        token,
        "PUT",
        "/identity/departments/" + mid,
        departmentInput("MID" + tag, "研发部" + tag, null, entity, UUID.fromString(leader)),
        200);

    // 成员分布：一个挂靠研发部，两个挂靠平台组
    request(
            token,
            "POST",
            "/identity/members",
            memberInput("dept-m" + tag.toLowerCase(), mid.toString(), List.of()),
            200)
        .asText();
    request(
            token,
            "POST",
            "/identity/members",
            memberInput("dept-p1-" + tag.toLowerCase(), leaf.toString(), List.of()),
            200)
        .asText();
    request(
            token,
            "POST",
            "/identity/members",
            memberInput("dept-p2-" + tag.toLowerCase(), leaf.toString(), List.of()),
            200)
        .asText();

    var tree = request(token, "GET", "/identity/departments", null, 200);
    var rootNode = findNode(tree, "ROOT" + tag);
    assertThat(rootNode).isNotNull();
    assertThat(rootNode.get("parentId").isNull()).isTrue();
    assertThat(rootNode.get("id").asText()).isEqualTo(root.toString());
    // 父部门只统计直接挂靠的人，不把子部门的人重复累计
    assertThat(rootNode.get("memberCount").asInt()).isZero();
    assertThat(rootNode.get("children")).hasSize(1);

    var midNode = findNode(tree, "MID" + tag);
    assertThat(midNode.get("parentId").asText()).isEqualTo(root.toString());
    assertThat(midNode.get("memberCount").asInt()).isEqualTo(1);
    assertThat(midNode.get("legalEntityId").asText()).isEqualTo(entity.toString());
    assertThat(midNode.get("legalEntityName").asText()).isEqualTo("测试法人 LE" + tag);
    assertThat(midNode.get("leaderMembershipId").asText()).isEqualTo(leader);
    assertThat(midNode.get("leaderName").asText()).isEqualTo(leaderUser);

    var leafNode = findNode(tree, "LEAF" + tag);
    assertThat(leafNode.get("parentId").asText()).isEqualTo(mid.toString());
    assertThat(leafNode.get("memberCount").asInt()).isEqualTo(2);
    assertThat(leafNode.get("children")).isEmpty();
    // 负责人可为空：没有负责人的部门需要在审批流里指定兜底人
    assertThat(leafNode.get("leaderMembershipId").isNull()).isTrue();
  }

  @Test
  void departmentMoveRelocatesSubtreeAndRejectsCycles() throws Exception {
    String token = login(), tag = suffix();
    UUID a = createDepartment(token, "A" + tag, "A部门" + tag, null);
    UUID b = createDepartment(token, "B" + tag, "B部门" + tag, a);
    UUID c = createDepartment(token, "C" + tag, "C部门" + tag, b);

    // 拖拽：C 移到 A 之下
    request(token, "PUT", "/identity/departments/" + c + "/parent", Map.of("parentId", a), 200);
    var tree = request(token, "GET", "/identity/departments", null, 200);
    assertThat(findNode(tree, "C" + tag).get("parentId").asText()).isEqualTo(a.toString());
    assertThat(findNode(tree, "B" + tag).get("children")).isEmpty();

    // 防环：把 A 挂到自己的子孙 C 之下必须被拒绝，且数据不变
    request(token, "PUT", "/identity/departments/" + a + "/parent", Map.of("parentId", c), 400);
    request(token, "PUT", "/identity/departments/" + a + "/parent", Map.of("parentId", b), 400);
    request(token, "PUT", "/identity/departments/" + a + "/parent", Map.of("parentId", a), 400);
    var after = request(token, "GET", "/identity/departments", null, 200);
    assertThat(findNode(after, "A" + tag).get("parentId").isNull()).isTrue();

    // 提升为根部门。Map.of 不接受 null 值，显式 parentId=null 才是「提升为根」的真实请求体。
    var promote = new HashMap<String, Object>();
    promote.put("parentId", null);
    request(token, "PUT", "/identity/departments/" + b + "/parent", promote, 200);
    var roots = request(token, "GET", "/identity/departments", null, 200);
    assertThat(findNode(roots, "B" + tag).get("parentId").isNull()).isTrue();
  }

  @Test
  void departmentHierarchyStaysInsideTheTenant() throws Exception {
    String owner = login(), tag = suffix();

    // 建一个子租户，用子租户所有者的身份建一个部门
    String childUser = "dept-child-" + tag.toLowerCase();
    request(
            owner,
            "POST",
            "/identity/children",
            Map.of(
                "code",
                "DC" + tag,
                "name",
                "Child",
                "ownerUsername",
                childUser,
                "ownerPassword",
                "test-member-password"),
            200)
        .asText();
    String childToken = loginUser(childUser);
    UUID foreign = createDepartment(childToken, "F" + tag, "外部部门" + tag, null);

    // 父租户看不到子租户的部门
    assertThat(findNode(request(owner, "GET", "/identity/departments", null, 200), "F" + tag))
        .isNull();
    // 伪造 parentId 指向别的租户：RLS 让它不可见，应用层给出可读业务错误。
    // request() 每次都带随机 X-Tenant-Id，这个头不影响判定。
    request(
        owner, "POST", "/identity/departments", departmentInput("X" + tag, "跨租户挂靠", foreign), 400);
    // 同一个编码在另一个租户可以用
    request(
        childToken,
        "POST",
        "/identity/departments",
        departmentInput("X" + tag, "另一租户同编码", null),
        200);
  }

  @Test
  void departmentDeleteRefusesWhileChildrenOrMembersRemain() throws Exception {
    String token = login(), tag = suffix();
    UUID root = createDepartment(token, "R" + tag, "待删总部" + tag, null);
    UUID child = createDepartment(token, "S" + tag, "待删子部门" + tag, root);

    request(token, "DELETE", "/identity/departments/" + root, null, 409);
    assertThat(findNode(request(token, "GET", "/identity/departments", null, 200), "R" + tag))
        .isNotNull();

    // 成员仍挂靠时也不能删
    request(
            token,
            "POST",
            "/identity/members",
            memberInput("dept-del-" + tag.toLowerCase(), child.toString(), List.of()),
            200)
        .asText();
    request(token, "DELETE", "/identity/departments/" + child, null, 409);

    // 清空子部门与成员后才可删除
    request(token, "DELETE", "/identity/departments/" + child, null, 409);
    var members = request(token, "GET", "/identity/members", null, 200);
    for (JsonNode m : members) {
      if (child.toString().equals(m.get("departmentId").asText())) {
        request(token, "DELETE", "/identity/members/" + m.get("id").asText(), null, 200);
      }
    }
    request(token, "DELETE", "/identity/departments/" + child, null, 200);
    request(token, "DELETE", "/identity/departments/" + root, null, 200);
    assertThat(findNode(request(token, "GET", "/identity/departments", null, 200), "R" + tag))
        .isNull();
  }

  @Test
  void departmentCodeIsUniqueWithinTenantAndCrossTenantReferencesAreRejected() throws Exception {
    String token = login(), tag = suffix();
    createDepartment(token, "DUP" + tag, "编码部门" + tag, null);
    request(
        token, "POST", "/identity/departments", departmentInput("DUP" + tag, "重名编码", null), 409);
    // 所属法人 / 负责人 必须是本租户的行，跨租户引用给出可读业务错误而不是 500
    request(
        token,
        "POST",
        "/identity/departments",
        departmentInput("LE" + tag, "假法人", null, UUID.randomUUID(), null),
        400);
    request(
        token,
        "POST",
        "/identity/departments",
        departmentInput("LD" + tag, "假负责人", null, null, UUID.randomUUID()),
        400);
  }
}
