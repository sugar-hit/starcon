package com.starcon.identity;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * 17.5.3.1 审计日志集成测试。
 *
 * <p>跑在真实 PostgreSQL 16（Testcontainers）上，审计记录真的落库、真的查得回来。 重点覆盖四件事：
 *
 * <ol>
 *   <li><b>写侧留痕</b>：15 个动作点都带资源类型、授权上下文与脱敏后的变更前后值。
 *   <li><b>检索与导出</b>：按资源 / 操作人 / 时间过滤，导出的是真实查询结果。
 *   <li><b>多租户三断言</b>：两租户互不可见、伪造 tenant header 无效、连接池归还后不串租户。
 *   <li><b>不可篡改 + 明文不入库</b>：直接 SQL 改/删被数据库挡住；密码等敏感值从未落库。
 * </ol>
 *
 * <p>与 17.5.3.2 操作日志的边界：这里不产生登录流水、错误堆栈或请求 ID 链路。
 */
class AuditIntegrationTest extends IdentityIntegrationTestBase {

  // ------------------------------------------------------------------
  // 写侧：资源类型 / 授权上下文 / 变更前后值
  // ------------------------------------------------------------------

  @Test
  void everyActionRecordsResourceTypeActorAndAuthorizationContext() throws Exception {
    String token = login(), tag = suffix();
    String role =
        request(
                token,
                "POST",
                "/identity/roles",
                Map.of(
                    "code",
                    "AUDITOR_" + tag,
                    "name",
                    "Auditor",
                    "status",
                    "ACTIVE",
                    "permissions",
                    List.of("catalog.spu.read")),
                200)
            .asText();

    var page = audit(token, Map.of("resourceType", "identity.role", "size", "50"));
    var created = findByResource(page, UUID.fromString(role));
    assertThat(created).isNotNull();
    // 操作人可读名在写入时快照：事后成员改名也不影响历史审计自解释。
    assertThat(created.get("actorName").asText()).isEqualTo("local-admin");
    assertThat(created.get("resourceLabel").asText()).isEqualTo("AUDITOR_" + tag);
    assertThat(created.get("action").asText()).isEqualTo("RoleSaved");

    // 变更详情：新建没有 before，after 承载新值，且已被写入侧脱敏。
    var detail = request(token, "GET", "/identity/audit/" + created.get("id").asText(), null, 200);
    assertThat(detail.get("changes").get("before").isNull()).isTrue();
    var after = detail.get("changes").get("after");
    assertThat(after.get("code").asText()).isEqualTo("AUDITOR_" + tag);
    assertThat(after.get("permissions").toString()).contains("catalog.spu.read");
    // 授权上下文：角色编码、数据范围、权限集都要能解释「凭什么放行」。
    assertThat(detail.get("authorization").get("roles").toString()).contains("OWNER");
    assertThat(detail.get("authorization").get("scopeModes").toString()).contains("ALL");
    assertThat(detail.get("authorization").get("owner").asBoolean()).isTrue();
    assertThat(detail.get("owner").asBoolean()).isTrue();

    // 更新动作要同时留下 before 与 after，供逐字段对比。
    request(
        token,
        "PUT",
        "/identity/roles/" + role,
        Map.of(
            "code",
            "AUDITOR_" + tag,
            "name",
            "Auditor renamed",
            "status",
            "INACTIVE",
            "permissions",
            List.of("catalog.spu.read")),
        200);
    var updated =
        findByResource(
            audit(token, Map.of("resourceType", "identity.role", "size", "50")),
            UUID.fromString(role));
    var updateDetail =
        request(token, "GET", "/identity/audit/" + updated.get("id").asText(), null, 200);
    var changes = updateDetail.get("changes");
    assertThat(changes.get("before").get("status").asText()).isEqualTo("ACTIVE");
    assertThat(changes.get("after").get("status").asText()).isEqualTo("INACTIVE");
    assertThat(changes.get("before").get("name").asText()).isEqualTo("Auditor");
    assertThat(changes.get("after").get("name").asText()).isEqualTo("Auditor renamed");

    // 删除动作：before 留痕、after 为空。
    request(token, "DELETE", "/identity/roles/" + role, null, 200);
    var deleted =
        findByAction(
            audit(token, Map.of("resourceType", "identity.role", "size", "50")), "RoleDeleted");
    assertThat(deleted).isNotNull();
    var deleteDetail =
        request(token, "GET", "/identity/audit/" + deleted.get("id").asText(), null, 200);
    assertThat(deleteDetail.get("changes").get("after").isNull()).isTrue();
    assertThat(deleteDetail.get("changes").get("before").get("code").asText())
        .isEqualTo("AUDITOR_" + tag);
  }

  @Test
  void allFifteenActionKindsAreAuditedWithResourceTypes() throws Exception {
    String token = login(), tag = suffix();

    // 部门（含移动）：一次建三层，移动与删除都留痕。
    UUID root = createDepartment(token, "AR" + tag, "审计根" + tag, null);
    UUID mid = createDepartment(token, "AM" + tag, "审计中层" + tag, root);
    request(
        token,
        "PUT",
        "/identity/departments/" + mid,
        departmentInput("AM" + tag, "审计中层改名" + tag, null, null, null),
        200);
    request(
        token, "PUT", "/identity/departments/" + mid + "/parent", Map.of("parentId", root), 200);
    // 删除走叶节点：根有子部门、有成员的部门按业务规则拒删（那是 17.2.1.1 的保护，不是审计）。
    UUID disposable = createDepartment(token, "AD" + tag, "待删部门" + tag, null);
    request(token, "DELETE", "/identity/departments/" + disposable, null, 200);

    // 成员（创建 / 更新 / 移除）。
    String memberUser = "audit-m-" + tag.toLowerCase();
    UUID member =
        UUID.fromString(
            request(
                    token,
                    "POST",
                    "/identity/members",
                    memberInput(memberUser, null, List.of()),
                    200)
                .asText());
    request(
        token,
        "PUT",
        "/identity/members/" + member,
        Map.of("status", "INACTIVE", "assignments", List.of()),
        200);
    request(token, "DELETE", "/identity/members/" + member, null, 200);

    // 法人（创建 / 更新 / 删除）。
    UUID entity =
        UUID.fromString(
            request(
                    token,
                    "POST",
                    "/identity/legal-entities",
                    legalEntityInput("AE" + tag, "USD"),
                    200)
                .asText());
    request(
        token,
        "PUT",
        "/identity/legal-entities/" + entity,
        legalEntityInput("AE" + tag, "EUR"),
        200);
    request(token, "DELETE", "/identity/legal-entities/" + entity, null, 200);

    // 子租户（创建 / 更新）。
    String childUser = "audit-c-" + tag.toLowerCase();
    UUID child =
        UUID.fromString(
            request(
                    token,
                    "POST",
                    "/identity/children",
                    Map.of(
                        "code",
                        "AC" + tag,
                        "name",
                        "审计子租户",
                        "ownerUsername",
                        childUser,
                        "ownerPassword",
                        "test-member-password"),
                    200)
                .asText());
    request(
        token,
        "PUT",
        "/identity/children/" + child,
        Map.of("name", "审计子租户改名", "status", "ACTIVE"),
        200);

    // 角色（保存）+ 所有者转移需要第二个活跃成员。
    UUID role =
        UUID.fromString(
            request(
                    token,
                    "POST",
                    "/identity/roles",
                    Map.of(
                        "code",
                        "ARL_" + tag,
                        "name",
                        "Auditor role",
                        "status",
                        "ACTIVE",
                        "permissions",
                        List.of("catalog.spu.read")),
                    200)
                .asText());
    request(token, "DELETE", "/identity/roles/" + role, null, 200);

    var page = audit(token, Map.of("size", "200"));
    for (String action :
        List.of(
            "RoleSaved",
            "RoleDeleted",
            "MemberCreated",
            "MemberUpdated",
            "MemberRemoved",
            "DepartmentCreated",
            "DepartmentUpdated",
            "DepartmentMoved",
            "DepartmentDeleted",
            "ChildCreated",
            "ChildUpdated",
            "LegalEntityCreated",
            "LegalEntityUpdated",
            "LegalEntityDeleted")) {
      assertThat(findByAction(page, action)).as("动作 %s 必须有审计记录", action).isNotNull();
    }

    // 资源类型是检索维度，必须真实存在且与动作匹配。
    assertThat(findByAction(page, "DepartmentMoved").get("resourceType").asText())
        .isEqualTo("identity.department");
    assertThat(findByAction(page, "MemberRemoved").get("resourceType").asText())
        .isEqualTo("identity.member");
    assertThat(findByAction(page, "ChildUpdated").get("resourceType").asText())
        .isEqualTo("tenant.child");
    assertThat(findByAction(page, "LegalEntityDeleted").get("resourceType").asText())
        .isEqualTo("identity.legal_entity");
  }

  /**
   * 所有者转移的审计。
   *
   * <p>转移一旦发生，原所有者立刻失去 OWNER 身份，而后续用例都靠 owner token 建数据， 所以必须在 finally 里转回去——否则测试之间互相污染，失败会表现得莫名其妙。
   */
  @Test
  void ownerTransferIsAuditedWithResourceTypeAndChanges() throws Exception {
    String owner = login(), tag = suffix();
    String ownerMember =
        request(owner, "GET", "/auth/session", null, 200).get("membershipId").asText();
    String successorUser = "audit-succ-" + tag.toLowerCase();
    UUID successor =
        UUID.fromString(
            request(
                    owner,
                    "POST",
                    "/identity/members",
                    memberInput(successorUser, null, List.of()),
                    200)
                .asText());

    try {
      request(owner, "POST", "/identity/owner-transfer", Map.of("membershipId", successor), 200);
      // 原所有者已不是 owner，不能再读审计
      request(owner, "GET", "/identity/audit", null, 403);

      // 新所有者是 owner，能读到这条转移记录
      String newOwner = loginUser(successorUser);
      var row =
          findByAction(
              audit(newOwner, Map.of("action", "OwnerTransferred", "size", "50")),
              "OwnerTransferred");
      assertThat(row).isNotNull();
      assertThat(row.get("resourceType").asText()).isEqualTo("identity.member");
      assertThat(row.get("resourceId").asText()).isEqualTo(successor.toString());
      // 变更后快照要有角色归属：新所有者此刻已是 OWNER:ALL
      var detail = request(newOwner, "GET", "/identity/audit/" + row.get("id").asText(), null, 200);
      assertThat(detail.get("changes").get("after").get("assignments").toString())
          .contains("OWNER");
      assertThat(detail.get("authorization").get("owner").asBoolean()).isTrue();
      assertThat(detail.get("authorization").get("roles").toString()).contains("OWNER");
    } finally {
      // 必须转回去：本地租户是共享的，原所有者一旦失去 OWNER，后续用例全部 403。
      request(
          loginUser(successorUser),
          "POST",
          "/identity/owner-transfer",
          Map.of("membershipId", ownerMember),
          200);
    }
    request(login(), "GET", "/identity/audit?size=1", null, 200);
  }

  // ------------------------------------------------------------------
  // 脱敏：明文从未进过库
  // ------------------------------------------------------------------

  @Test
  void sensitiveValuesNeverReachTheDatabase() throws Exception {
    String token = login(), tag = suffix();
    String username = "audit-secret-" + tag.toLowerCase();
    String password = "Aud1t-P@ssw0rd-" + tag;
    // 建一个法人，收件地址与证件号一起进去，验证写入侧就脱敏。
    var input = new java.util.HashMap<String, Object>(legalEntityInput("AS" + tag, "USD"));
    input.put("address", "上海市浦东新区世纪大道100号2栋3层 张三");
    input.put("taxNo", "91310115MA1K3XYZ4B");

    request(
        token, "POST", "/identity/members", memberInput(username, null, List.of(), password), 200);
    request(token, "POST", "/identity/legal-entities", input, 200);

    // 直接查库（绕开 API）：明文必须一个都不在。
    try (var c =
        DriverManager.getConnection(pg().getJdbcUrl(), pg().getUsername(), pg().getPassword())) {
      var raw = new StringBuilder();
      try (var st = c.createStatement();
          var rs =
              st.executeQuery(
                  "select coalesce(changes::text,'')||' '||coalesce(resource_label,'') from identity_audit")) {
        while (rs.next()) raw.append(rs.getString(1)).append('\n');
      }
      var text = raw.toString();
      assertThat(text).doesNotContain(password);
      assertThat(text).doesNotContain("上海市浦东新区世纪大道100号2栋3层");
      assertThat(text).doesNotContain("91310115MA1K3XYZ4B");
      // 脱敏确实生效了（不是查询侧过滤出来的空值）
      assertThat(text).contains(AuditRedactor.MASK);
    }

    // API 侧同样拿不到原文
    var page = audit(token, Map.of("size", "200"));
    var legalEntity = findByAction(page, "LegalEntityCreated");
    assertThat(legalEntity).isNotNull();
    var detail =
        request(token, "GET", "/identity/audit/" + legalEntity.get("id").asText(), null, 200);
    assertThat(detail.get("changes").get("after").get("address").asText())
        .isEqualTo(AuditRedactor.MASK);
  }

  // ------------------------------------------------------------------
  // 检索与导出
  // ------------------------------------------------------------------

  @Test
  void searchByResourceActorAndTimeReturnsRealRows() throws Exception {
    String token = login(), tag = suffix();
    UUID department = createDepartment(token, "SR" + tag, "检索部门" + tag, null);
    UUID other = createDepartment(token, "SO" + tag, "别的部门" + tag, null);

    var byResource =
        audit(
            token,
            Map.of("resourceType", "identity.department", "resourceId", department.toString()));
    assertThat(byResource.get("items")).isNotEmpty();
    assertThat(byResource.get("items").get(0).get("resourceId").asText())
        .isEqualTo(department.toString());

    // 按操作人：local-admin 的审计里有刚建的部门，另一个部门也属于同一操作人——
    // 所以「操作人」这个维度本身必须真的生效（换成不存在的人应当为空）。
    var byActor = audit(token, Map.of("actorId", localAdminUserId(token).toString()));
    assertThat(byActor.get("total").asLong()).isPositive();
    assertThat(audit(token, Map.of("actorId", UUID.randomUUID().toString())).get("total").asLong())
        .isZero();

    // 按时间窗：把窗口设在这次操作之前，应当查不到任何东西。
    Instant before = Instant.now().minus(1, ChronoUnit.HOURS);
    var windowed =
        audit(
            token,
            Map.of(
                "from",
                before.toString(),
                "to",
                Instant.now().minus(30, ChronoUnit.MINUTES).toString()));
    assertThat(windowed.get("total").asLong()).isZero();
    var recent = audit(token, Map.of("from", before.toString()));
    assertThat(recent.get("total").asLong()).isPositive();

    // 分页：真实 total + 真实切片
    var paged = audit(token, Map.of("size", "2"));
    assertThat(paged.get("items")).hasSize(2);
    assertThat(paged.get("total").asLong()).isGreaterThanOrEqualTo(2);
    var second = audit(token, Map.of("size", "2", "page", "1"));
    assertThat(second.get("items").get(0).get("id").asText())
        .isNotEqualTo(paged.get("items").get(0).get("id").asText());

    // 非法分页参数必须被拒，而不是被静默修正
    auditExpect(token, Map.of("size", "0"), 400);
    auditExpect(token, Map.of("size", "5000"), 400);
    auditExpect(token, Map.of("page", "-1"), 400);
    auditExpect(
        token,
        Map.of(
            "from",
            Instant.now().toString(),
            "to",
            Instant.now().minus(1, ChronoUnit.HOURS).toString()),
        400);
    assertThat(other).isNotNull();
  }

  @Test
  void occurredAtRoundTripsAsUtcWithoutTimezoneDrift() throws Exception {
    String token = login(), tag = suffix();
    createDepartment(token, "TZ" + tag, "时区部门" + tag, null);

    var page = audit(token, Map.of("resourceType", "identity.department", "size", "50"));
    var row = page.get("items").get(0);
    var occurredAt = Instant.parse(row.get("occurredAt").asText());
    // 写入用的是 LocalDateTime.ofInstant(now, UTC)。若误用 LocalDateTime.now()，
    // 在 Asia/Shanghai 下会静默偏 -28800 秒（8 小时前），断言窗口必然不满足。
    assertThat(occurredAt)
        .isBetween(
            Instant.now().minus(5, ChronoUnit.MINUTES), Instant.now().plus(5, ChronoUnit.MINUTES));

    // 库里的原值也必须一致，而不是只有 API 视图正确
    try (var c =
        DriverManager.getConnection(pg().getJdbcUrl(), pg().getUsername(), pg().getPassword())) {
      try (var st = c.prepareStatement("select occurred_at from identity_audit where id = ?")) {
        st.setObject(1, UUID.fromString(row.get("id").asText()));
        try (var rs = st.executeQuery()) {
          assertThat(rs.next()).isTrue();
          assertThat(rs.getObject(1, java.time.OffsetDateTime.class).toInstant())
              .isEqualTo(occurredAt);
        }
      }
    }
  }

  @Test
  void exportReturnsRealCsvMatchingTheQuery() throws Exception {
    String token = login(), tag = suffix();
    UUID department = createDepartment(token, "EX" + tag, "导出部门" + tag, null);

    String csv =
        exportCsv(
            token,
            Map.of("resourceType", "identity.department", "resourceId", department.toString()));
    // 表头是中文的，导出的是真实查询结果而不是占位内容
    assertThat(csv).contains("发生时间", "操作人", "动作", "资源类型", "授权上下文", "变更前", "变更后");
    assertThat(csv).contains("导出部门" + tag);
    assertThat(csv).contains("local-admin");
    assertThat(csv).contains("identity.department");
    // CSV 按 RFC 4180 转义：字段值带引号
    assertThat(csv).contains("\"");
    // 导出的行数与同条件检索的 total 对得上
    long matched =
        audit(
                token,
                Map.of("resourceType", "identity.department", "resourceId", department.toString()))
            .get("total")
            .asLong();
    assertThat(csv.lines().skip(1).filter(line -> !line.isBlank()).count()).isEqualTo(matched);

    // 导出必须受认证保护
    http.perform(get("/identity/audit/export")).andExpect(status().isUnauthorized());
  }

  // ------------------------------------------------------------------
  // 不可篡改
  // ------------------------------------------------------------------

  @Test
  void auditRowsCannotBeUpdatedOrDeletedByTheApplicationRole() throws Exception {
    String token = login(), tag = suffix();
    createDepartment(token, "IM" + tag, "不可篡改部门" + tag, null);
    var row =
        audit(token, Map.of("resourceType", "identity.department", "size", "50"))
            .get("items")
            .get(0);
    UUID id = UUID.fromString(row.get("id").asText());

    // 用 identity_app 直连：权限层已经不给 UPDATE/DELETE
    try (var c = DriverManager.getConnection(pg().getJdbcUrl(), "identity_app", "test-only")) {
      assertThatThrownBy(
              () ->
                  c.createStatement()
                      .executeUpdate(
                          "update identity_audit set action='Tampered' where id='" + id + "'"))
          .isInstanceOf(java.sql.SQLException.class);
      assertThatThrownBy(
              () ->
                  c.createStatement()
                      .executeUpdate("delete from identity_audit where id='" + id + "'"))
          .isInstanceOf(java.sql.SQLException.class);
    }

    // 用超级用户直连：权限被绕过，但触发器仍然挡住——这才是真正的不可篡改
    try (var c =
        DriverManager.getConnection(pg().getJdbcUrl(), pg().getUsername(), pg().getPassword())) {
      assertThatThrownBy(
              () ->
                  c.createStatement()
                      .executeUpdate(
                          "update identity_audit set action='Tampered' where id='" + id + "'"))
          .hasMessageContaining("不可修改或删除");
      assertThatThrownBy(
              () ->
                  c.createStatement()
                      .executeUpdate("delete from identity_audit where id='" + id + "'"))
          .hasMessageContaining("不可修改或删除");
    }

    // 记录仍在，且内容未被篡改
    var still = request(token, "GET", "/identity/audit/" + id, null, 200);
    assertThat(still.get("action").asText()).isNotEqualTo("Tampered");
  }

  // ------------------------------------------------------------------
  // 多租户三断言
  // ------------------------------------------------------------------

  @Test
  void auditIsTenantIsolatedAndHeaderForgeryIsIgnored() throws Exception {
    String owner = login(), tag = suffix();
    UUID parentDepartment = createDepartment(owner, "TA" + tag, "父租户部门" + tag, null);

    // 建子租户，用子租户所有者身份产生自己的审计
    String childUser = "audit-child-" + tag.toLowerCase();
    request(
        owner,
        "POST",
        "/identity/children",
        Map.of(
            "code",
            "TAC" + tag,
            "name",
            "审计子租户",
            "ownerUsername",
            childUser,
            "ownerPassword",
            "test-member-password"),
        200);
    String childToken = loginUser(childUser);
    UUID childDepartment = createDepartment(childToken, "TC" + tag, "子租户部门" + tag, null);

    // 断言一：两租户互不可见
    var parentView = audit(owner, Map.of("resourceType", "identity.department", "size", "200"));
    assertThat(parentView.toString()).doesNotContain("子租户部门" + tag);
    assertThat(findByResource(parentView, childDepartment)).isNull();
    var childView = audit(childToken, Map.of("resourceType", "identity.department", "size", "200"));
    assertThat(findByResource(childView, childDepartment)).isNotNull();
    assertThat(childView.toString()).doesNotContain("父租户部门" + tag);
    // 详情端点同样隔离：拿子租户的审计 ID 去父租户查，必须 404（而不是 403 泄露存在性）
    var childRow = findByResource(childView, childDepartment);
    request(owner, "GET", "/identity/audit/" + childRow.get("id").asText(), null, 404);

    // 断言二：伪造 tenant header 无效。request() 每次都带随机 X-Tenant-Id。
    String forged = UUID.randomUUID().toString();
    var forgedView =
        http.perform(
                get("/identity/audit?resourceType=identity.department")
                    .header("Authorization", "Bearer " + owner)
                    .header("X-Tenant-Id", childDepartment.toString()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    // 伪造 header 指向子租户的资源，父租户依然只能看到自己的审计
    assertThat(forgedView).doesNotContain("子租户部门" + tag);
    // 伪造 header 指向不存在的租户也不改变结果
    http.perform(
            get("/identity/audit?size=5")
                .header("Authorization", "Bearer " + owner)
                .header("X-Tenant-Id", forged))
        .andExpect(status().isOk());
    // 未登录查不到任何审计
    http.perform(get("/identity/audit")).andExpect(status().isUnauthorized());

    // 断言三：连接池归还后不串租户。池大小被压到 1，交替租户请求复用同一物理连接。
    for (int i = 0; i < 3; i++) {
      var parent = audit(owner, Map.of("resourceType", "identity.department", "size", "200"));
      assertThat(parent.toString()).as("第 %s 轮父租户不得看到子租户审计", i).doesNotContain("子租户部门" + tag);
      var child = audit(childToken, Map.of("resourceType", "identity.department", "size", "200"));
      assertThat(child.toString()).as("第 %s 轮子租户不得看到父租户审计", i).doesNotContain("父租户部门" + tag);
    }
    // 事务外（无租户上下文）直查应当为空
    assertThat(sql.fetch("select id from identity_audit")).isEmpty();
    assertThat(parentDepartment).isNotNull();
  }

  @Test
  void auditReadRequiresTheAuditPermission() throws Exception {
    String owner = login(), tag = suffix();
    // 一个没有审计权限的角色：能进系统，但查不到审计
    String role =
        request(
                owner,
                "POST",
                "/identity/roles",
                Map.of(
                    "code",
                    "NOAUDIT_" + tag,
                    "name",
                    "No audit",
                    "status",
                    "ACTIVE",
                    "permissions",
                    List.of("catalog.spu.read")),
                200)
            .asText();
    String username = "audit-noread-" + tag.toLowerCase();
    request(
        owner,
        "POST",
        "/identity/members",
        memberInput(username, null, List.of(Map.of("roleId", role, "scopeMode", "ALL"))),
        200);
    String memberToken = loginUser(username);
    request(memberToken, "GET", "/identity/audit", null, 403);
    request(memberToken, "GET", "/identity/audit/export", null, 403);

    // 授予审计读权限后即可读取（审计只读，没有 create/update/delete）
    request(
        owner,
        "PUT",
        "/identity/roles/" + role,
        Map.of(
            "code",
            "NOAUDIT_" + tag,
            "name",
            "No audit",
            "status",
            "ACTIVE",
            "permissions",
            List.of("catalog.spu.read", "identity.audit.read")),
        200);
    request(memberToken, "GET", "/identity/audit?size=5", null, 200);
    assertThat(
            request(memberToken, "GET", "/auth/session", null, 200).get("permissions").toString())
        .contains("identity.audit.read");
  }

  // ------------------------------------------------------------------
  // helpers
  // ------------------------------------------------------------------

  /** 检索：条件作为 query string，值都是字符串。 */
  JsonNode audit(String token, Map<String, String> filters) throws Exception {
    var query = new StringBuilder();
    filters.forEach(
        (key, value) -> {
          if (!value.isEmpty())
            query.append(query.isEmpty() ? "?" : "&").append(key).append('=').append(enc(value));
        });
    return request(token, "GET", "/identity/audit" + query, null, 200);
  }

  JsonNode auditExpect(String token, Map<String, String> filters, int status) throws Exception {
    var query = new StringBuilder();
    filters.forEach(
        (key, value) -> {
          if (!value.isEmpty())
            query.append(query.isEmpty() ? "?" : "&").append(key).append('=').append(enc(value));
        });
    return request(token, "GET", "/identity/audit" + query, null, status);
  }

  /** 导出走 text/csv，不能用 JSON 解析的 request()。 */
  String exportCsv(String token, Map<String, String> filters) throws Exception {
    var query = new StringBuilder();
    filters.forEach(
        (key, value) -> {
          if (!value.isEmpty())
            query.append(query.isEmpty() ? "?" : "&").append(key).append('=').append(enc(value));
        });
    var response =
        http.perform(
                get("/identity/audit/export" + query)
                    .header("Authorization", "Bearer " + token)
                    .header("X-Tenant-Id", UUID.randomUUID())
                    .accept(MediaType.ALL))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();
    return response.getContentAsString();
  }

  /**
   * 查询参数值原样拼接，不做 URL 编码。
   *
   * <p>{@link org.springframework.test.web.servlet.request.MockMvcRequestBuilders#get(String,
   * Object...)} 不会解码 query 里的 percent-encoding，所以这里若用 {@code URLEncoder}，MockMvc 会把 {@code %3A}
   * 原样送到控制器，{@code Instant.parse} 直接失败。真实 HTTP 客户端会解码， 所以测试端保持原样才是与生产一致的做法。
   */
  private static String enc(String value) {
    return value;
  }

  JsonNode findByAction(JsonNode page, String action) {
    for (JsonNode item : page.get("items"))
      if (action.equals(item.get("action").asText())) return item;
    return null;
  }

  JsonNode findByResource(JsonNode page, UUID resourceId) {
    for (JsonNode item : page.get("items"))
      if (resourceId.toString().equals(item.get("resourceId").asText())) return item;
    return null;
  }

  UUID localAdminUserId(String token) throws Exception {
    return UUID.fromString(
        request(token, "GET", "/auth/session", null, 200).get("userId").asText());
  }

  UUID currentMembershipId(String token) throws Exception {
    return UUID.fromString(
        request(token, "GET", "/auth/session", null, 200).get("membershipId").asText());
  }
}
