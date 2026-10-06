package com.starcon.identity;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.DriverManager;
import java.util.*;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

@SpringBootTest
@AutoConfigureMockMvc
class IdentityIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

  static {
    pg.start();
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute("create role starcon_app nologin");
      s.execute("create role identity_app login password 'test-only' nosuperuser nobypassrls");
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "identity_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("INTERNAL_KEY", () -> "test-key");
    r.add("LOCAL_BOOTSTRAP_ENABLED", () -> "true");
    r.add("LOCAL_ADMIN_PASSWORD", () -> "test-admin-password");
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  @Autowired MockMvc http;
  @Autowired ObjectMapper json;
  @Autowired org.jooq.DSLContext sql;
  @Autowired org.springframework.transaction.PlatformTransactionManager manager;

  JsonNode request(String token, String method, String path, Object body, int expected)
      throws Exception {
    var builder =
        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request(
                org.springframework.http.HttpMethod.valueOf(method), path)
            .header("Authorization", "Bearer " + token)
            .header("X-Tenant-Id", UUID.randomUUID())
            .contentType("application/json");
    if (body != null) builder.content(json.writeValueAsString(body));
    var response = http.perform(builder).andExpect(status().is(expected)).andReturn().getResponse();
    return response.getContentAsString().isEmpty()
        ? json.getNodeFactory().nullNode()
        : json.readTree(response.getContentAsString());
  }

  String loginUser(String username) throws Exception {
    return request(
            "",
            "POST",
            "/auth/login",
            Map.of("username", username, "password", "test-member-password"),
            200)
        .get("token")
        .asText();
  }

  String suffix() {
    return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
  }

  Map<String, Object> memberInput(String username, String dept, List<?> assignments) {
    var input = new HashMap<String, Object>();
    input.put("username", username);
    input.put("password", "test-member-password");
    input.put("status", "ACTIVE");
    input.put("departmentId", dept);
    input.put("assignments", assignments);
    return input;
  }

  String login() throws Exception {
    var result =
        http.perform(
                post("/auth/login")
                    .contentType("application/json")
                    .content("{\"username\":\"local-admin\",\"password\":\"test-admin-password\"}"))
            .andExpect(status().isOk())
            .andReturn();
    return json.readTree(result.getResponse().getContentAsString()).get("token").asText();
  }

  @AfterAll
  static void stop() {
    pg.stop();
  }

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
        request(token, "POST", "/identity/departments", Map.of("name", "Dept " + tag), 200)
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
        token, "PUT", "/identity/departments/" + department, Map.of("name", "Renamed " + tag), 200);
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
    String ownerRole = me.get("grants").get(0).get("roleId").asText(),
        ownerMember = me.get("membershipId").asText();
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
                "select relname from pg_class join pg_namespace n on n.oid=relnamespace where n.nspname='public' and relkind='r' and relname in ('membership','role','role_permission','role_assignment','department','tenant_closure','identity_audit','identity_outbox','legal_entity','legal_entity_reference') and (not relrowsecurity or not relforcerowsecurity)"))
        .isEmpty();
    var runtime =
        sql.fetchOne("select rolsuper,rolbypassrls from pg_roles where rolname=current_user");
    assertThat(runtime.get("rolsuper", Boolean.class)).isFalse();
    assertThat(runtime.get("rolbypassrls", Boolean.class)).isFalse();
  }

  // ------------------------------------------------------------------
  // 17.1.2 法人档案
  // ------------------------------------------------------------------

  Map<String, Object> legalEntityInput(String code, String currency) {
    var input = new HashMap<String, Object>();
    input.put("code", code);
    input.put("legalName", "测试法人 " + code);
    input.put("registrationCountry", "US");
    input.put("registrationNo", "REG-" + code);
    input.put("taxNo", "TAX-" + code);
    input.put("baseCurrency", currency);
    input.put("address", "1 Test Street");
    input.put("amountScale", 2);
    input.put("quantityScale", 3);
    input.put("rounding", "HALF_UP");
    input.put("status", "ACTIVE");
    return input;
  }

  @Test
  void legalEntityCrudCarriesBaseCurrencyAndRounding() throws Exception {
    String token = login();
    String code = "LE" + suffix();
    var created = request(token, "POST", "/identity/legal-entities", legalEntityInput(code, "USD"), 200);
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
    var created = request(token, "POST", "/identity/legal-entities", legalEntityInput(code, "USD"), 200);
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
    var created = request(token, "POST", "/identity/legal-entities", legalEntityInput(code, "USD"), 200);
    request(
        token,
        "POST",
        "/identity/legal-entities/references",
        Map.of("legalEntityId", UUID.randomUUID(), "source", "channel_shop", "referenceId", UUID.randomUUID()),
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
                memberInput(
                    username, null, List.of(Map.of("roleId", role, "scopeMode", "ALL"))),
                200)
            .asText();
    try {
      String finance = loginUser(username);
      request(finance, "GET", "/identity/legal-entities", null, 200);
      request(finance, "POST", "/identity/legal-entities", legalEntityInput("LE" + tag, "USD"), 403);
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
              "code", "FINANCE_" + tag,
              "name", "Finance",
              "status", "INACTIVE",
              "permissions", List.of("identity.legal_entity.read")),
          200);
      request(finance, "GET", "/identity/legal-entities", null, 403);
    } finally {
      request(owner, "PUT", "/identity/members/" + member, Map.of("status", "INACTIVE", "assignments", List.of()), 200);
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
}
