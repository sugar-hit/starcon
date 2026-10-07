package com.starcon.identity;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.starcon.tenancy.TenantTransactions;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 17.3.2.1 角色分配。
 *
 * <p>本模块要证明的不是「能建一条分配」，而是蓝图那条加粗的硬约束真的成立：
 * <b>有角色必有范围</b>。所以最核心的两条用例都直接对数据库下手——
 * 一条证明缺范围的写入会被**库内延迟约束触发器**挡住（不是应用层 if 挡的），
 * 一条证明 scope_mode 不再有默认值（「忘了填」不再等于租户内全量）。
 *
 * <p>其余用例钉住：范围对象确实落库且编码由服务端覆写、生效时间写回不偏时区、
 * 多条分配合并后的权限预览说得清合并方式、未生效的分配不产生任何权限，
 * 以及多租户隔离三断言。
 */
class RoleAssignmentIntegrationTest extends IdentityIntegrationTestBase {

  private TenantTransactions tx() {
    return new TenantTransactions(sql, new TransactionTemplate(manager));
  }

  private String tenantOf(String token) throws Exception {
    return request(token, "GET", "/identity/tenants/current", null, 200).get("tenantId").asText();
  }

  private UUID role(String token, String code, String name, List<String> permissions) throws Exception {
    return UUID.fromString(
        request(
                token,
                "POST",
                "/identity/roles",
                Map.of("code", code, "name", name, "status", "ACTIVE", "permissions", permissions),
                200)
            .asText());
  }

  /** 建一个带部门归属的成员，返回成员 id。初始分配给租户内全部范围，用于让成员能登录。 */
  private UUID member(String token, String username, UUID departmentId, List<Map<String, String>> assignments)
      throws Exception {
    return UUID.fromString(
        request(token, "POST", "/identity/members",
            memberInput(username, departmentId == null ? null : departmentId.toString(), assignments), 200)
            .asText());
  }

  /** 建一个子租户，返回它的所有者账号名。 */
  private String childTenant(String ownerToken, String tag) throws Exception {
    String username = "ra-child-" + tag;
    request(
        ownerToken,
        "POST",
        "/identity/children",
        Map.of(
            "code", "RA_" + tag,
            "name", "角色分配子租户",
            "ownerUsername", username,
            "ownerPassword", "test-member-password"),
        200);
    return username;
  }

  private Map<String, String> all(UUID roleId) {
    return Map.of("roleId", roleId.toString(), "scopeMode", "ALL");
  }

  private Map<String, Object> assignmentBody(
      UUID roleId, String scopeMode, List<Map<String, String>> scopes, OffsetDateTime from) {
    var body = new LinkedHashMap<String, Object>();
    body.put("roleId", roleId.toString());
    body.put("scopeMode", scopeMode);
    body.put("scopes", scopes);
    body.put("effectiveFrom", from.toString());
    return body;
  }

  private Map<String, String> scope(String type, UUID id, String code) {
    var m = new LinkedHashMap<String, String>();
    m.put("scopeType", type);
    m.put("scopeId", id.toString());
    m.put("scopeCode", code);
    return m;
  }

  private List<JsonNode> assignmentsOf(String token, UUID membershipId) throws Exception {
    var all = request(token, "GET", "/identity/role-assignments?membershipId=" + membershipId, null, 200);
    var mine = new ArrayList<JsonNode>();
    for (JsonNode n : all) mine.add(n);
    return mine;
  }

  private JsonNode assignmentOf(String token, UUID membershipId, UUID roleId) throws Exception {
    for (JsonNode n : assignmentsOf(token, membershipId))
      if (n.get("roleId").asText().equals(roleId.toString())) return n;
    return null;
  }

  // ------------------------------------------------------------------
  // 核心断言：有角色必有范围（库内硬约束）
  // ------------------------------------------------------------------

  /**
   * 缺范围的分配在**数据库层**被拒绝，不是被应用层挡下来的。
   *
   * <p>这条必须绕开 HTTP 直接写表：只有这样才能证明挡它的是 V47 的延迟约束触发器。
   * 如果实现把这条规则只写在 Java 里，那么任何一条绕过服务的写入（运维脚本、
   * 另一个服务、未来某个后台任务）都能造出「有角色、无范围」的记录——而那正是蓝图
   * 说的「实践中极易误配」。
   *
   * <p>用的是 DEPARTMENT 模式（唯一必须带对象范围的一种）；ALL / SELF 本身
   * 就是完整范围声明，不需要对象行。
   */
  @Test
  void assignmentWithoutScopeIsRejectedByTheDatabase() throws Exception {
    String ownerToken = login(), tag = suffix();
    UUID roleId = role(ownerToken, "RA_NS_" + tag, "缺范围测试角色", List.of("catalog.sku.read"));
    UUID memberId = member(ownerToken, "ra-noscope-" + tag, null, List.of());

    var tenantId = UUID.fromString(tenantOf(ownerToken));
    assertThatThrownBy(
            () ->
                tx()
                    .run(
                        tenantId,
                        () ->
                            sql.execute(
                                "insert into role_assignment(tenant_id,membership_id,role_id,scope_mode,assigned_by,effective_from)"
                                    + " values(?,?,?,'DEPARTMENT',?,'2026-01-01T00:00:00Z'::timestamptz)",
                                tenantId,
                                memberId,
                                roleId,
                                tenantId)))
        .hasMessageContaining("Role assignment requires an explicit data scope");

    // 事务整体回滚：库里没有留下半条记录。
    assertThat(
            tx()
                .run(tenantId, () -> sql.fetch("select role_id from role_assignment where membership_id=?", memberId))
                .isEmpty())
        .isTrue();
  }

  /**
   * scope_mode 不再有默认值：「没写范围」不再等于租户内全量。
   *
   * <p>V1 建表时给了 {@code DEFAULT 'ALL'}，于是任何省略范围列的 INSERT 都静默拿到全量权限。
   * 这条断言钉住的是 V47 删掉这个默认值之后的行为：漏填直接失败。
   */
  @Test
  void scopeModeHasNoImplicitTenantWideDefault() throws Exception {
    String ownerToken = login(), tag = suffix();
    UUID roleId = role(ownerToken, "RA_DEF_" + tag, "默认值测试角色", List.of("catalog.sku.read"));
    UUID memberId = member(ownerToken, "ra-default-" + tag, null, List.of());
    var tenantId = UUID.fromString(tenantOf(ownerToken));

    assertThatThrownBy(
            () ->
                tx()
                    .run(
                        tenantId,
                        () ->
                            sql.execute(
                                "insert into role_assignment(tenant_id,membership_id,role_id,assigned_by)"
                                    + " values(?,?,?,?)",
                                tenantId,
                                memberId,
                                roleId,
                                tenantId)))
        .hasMessageContaining("scope_mode");
  }

  /**
   * 「租户内全部」再挂对象范围是自相矛盾的记录，库内同样拒绝。
   *
   * <p>这类行最坏的地方在于下游不知道该信哪一半：既声明全量、又声明只限某几个对象。
   */
  @Test
  void tenantWideScopeCannotCarryScopeObjects() throws Exception {
    String ownerToken = login(), tag = suffix();
    UUID deptId = createDepartment(ownerToken, "RA_CW_" + tag, "全量范围测试部门", null);
    UUID roleId = role(ownerToken, "RA_CW2_" + tag, "全量范围测试角色", List.of("catalog.sku.read"));
    UUID memberId = member(ownerToken, "ra-contradict-" + tag, deptId, List.of());
    var now = OffsetDateTime.now();

    // 应用层先挡（400，好读的原因）；下面再证明库内也挡得住。
    request(
        ownerToken,
        "POST",
        "/identity/members/" + memberId + "/role-assignments",
        assignmentBody(roleId, "ALL", List.of(scope("DEPARTMENT", deptId, "RA_CW_" + tag)), now),
        400);

    var tenantId = UUID.fromString(tenantOf(ownerToken));
    assertThatThrownBy(
            () ->
                tx()
                    .run(
                        tenantId,
                        () -> {
                          sql.execute(
                              "insert into role_assignment(tenant_id,membership_id,role_id,scope_mode,assigned_by,effective_from)"
                                  + " values(?,?,?,'ALL',?,'2026-01-01T00:00:00Z'::timestamptz)",
                              tenantId,
                              memberId,
                              roleId,
                              tenantId);
                          sql.execute(
                              "insert into role_assignment_scope(tenant_id,membership_id,role_id,scope_type,scope_id,scope_code)"
                                  + " values(?,?,?,'DEPARTMENT',?,?)",
                              tenantId,
                              memberId,
                              roleId,
                              deptId,
                              "RA_CW_" + tag);
                          // 返回一个值让 lambda 满足 Supplier：这里只看是否抛异常，不关心行数。
                          return 0;
                        }))
        .hasMessageContaining("cannot carry scope objects");
  }

  // ------------------------------------------------------------------
  // 范围与生效时间落库
  // ------------------------------------------------------------------

  /**
   * 范围对象落库、编码由服务端覆写、生效时间写回不偏时区。
   *
   * <p>生效时间必须断言**写回的值**，不能只断言「没抛异常」：jOOQ 对 VALUES 列表里的裸 {@code ?}
   * 按 varchar 推断类型，{@code OffsetDateTime} 会被当字符串塞进去且不报错，
   * 结果是时间偏掉一个时区偏移（典型 −28800s）。这里特意用 {@code +08:00} 的输入，
   * 断言的是**瞬间**（Instant）而不是墙上时间——偏 8 小时的错误写法会被这条直接抓住。
   */
  @Test
  void scopeAndEffectiveTimeAreStoredVerbatim() throws Exception {
    String ownerToken = login(), tag = suffix();
    UUID deptId = createDepartment(ownerToken, "RA_TZ_" + tag, "时区测试部门", null);
    UUID roleId = role(ownerToken, "RA_TZ_" + tag, "时区测试角色", List.of("catalog.sku.read"));
    UUID memberId = member(ownerToken, "ra-tz-" + tag, deptId, List.of());

    // 刻意用 +08:00 偏移构造时间：按字符串解析的错误写法在这里会偏掉整整 8 小时。
    // 断言的是瞬间（Instant）而不是墙上时间，正是为了抓住这类漂移。
    var from = OffsetDateTime.now(ZoneOffset.ofHours(8)).minusDays(1).withNano(0);
    var until = OffsetDateTime.now(ZoneOffset.ofHours(8)).plusDays(30).withNano(0);
    var body = assignmentBody(roleId, "DEPARTMENT", List.of(scope("DEPARTMENT", deptId, "写错的名称")), from);
    body.put("effectiveUntil", until.toString());
    request(ownerToken, "POST", "/identity/members/" + memberId + "/role-assignments", body, 200);

    JsonNode row = assignmentOf(ownerToken, memberId, roleId);
    assertThat(row).isNotNull();
    assertThat(row.get("scopeMode").asText()).isEqualTo("DEPARTMENT");
    assertThat(row.get("effectiveStatus").asText()).isEqualTo("ACTIVE");
    // 瞬间相等 = 没有发生「按字符串解析」的时区漂移。
    assertThat(OffsetDateTime.parse(row.get("effectiveFrom").asText()).toInstant())
        .isEqualTo(from.toInstant());
    assertThat(OffsetDateTime.parse(row.get("effectiveUntil").asText()).toInstant())
        .isEqualTo(until.toInstant());

    // 范围编码由服务端覆写成真实编码：调用方自称的名称不该出现在库里。
    assertThat(row.get("scopes")).hasSize(1);
    assertThat(row.get("scopes").get(0).get("scopeCode").asText()).isEqualTo("RA_TZ_" + tag);
    assertThat(row.get("scopes").get(0).get("scopeId").asText()).isEqualTo(deptId.toString());

    // 库里的值同样按瞬间校验一遍：API 回显和实际落库必须一致，不能只在某一层看起来对。
    var stored =
        tx()
            .run(
                UUID.fromString(tenantOf(ownerToken)),
                () ->
                    sql.fetchOne(
                        "select effective_from,effective_until from role_assignment where membership_id=? and role_id=?",
                        memberId,
                        roleId));
    assertThat(stored.get("effective_from", OffsetDateTime.class).toInstant())
        .isEqualTo(from.toInstant());
    assertThat(stored.get("effective_until", OffsetDateTime.class).toInstant())
        .isEqualTo(until.toInstant());
  }

  /** 生效时间不能倒挂：一条永远不生效的分配是配置错误。 */
  @Test
  void reversedValidityWindowIsRejected() throws Exception {
    String ownerToken = login(), tag = suffix();
    UUID roleId = role(ownerToken, "RA_REV_" + tag, "倒挂测试角色", List.of("catalog.sku.read"));
    UUID memberId = member(ownerToken, "ra-reversed-" + tag, null, List.of());
    var now = OffsetDateTime.now();
    var body = assignmentBody(roleId, "ALL", List.of(), now);
    body.put("effectiveUntil", now.minusDays(1).toString());
    request(ownerToken, "POST", "/identity/members/" + memberId + "/role-assignments", body, 400);
  }

  // ------------------------------------------------------------------
  // 生效时间必须真的影响权限
  // ------------------------------------------------------------------

  /**
   * 未生效 / 已过期的分配不产生任何权限。
   *
   * <p>这是本模块最容易做成「假字段」的地方：如果生效时间只显示在页面上而不参与鉴权，
   * 管理员会以为下周才生效的分配今天就已经在给权限了。所以这里从成员自己的会话断言：
   * 未来生效的分配不出现在 grants 里，改成过去之后才出现。
   */
  @Test
  void futureAndExpiredAssignmentsGrantNothing() throws Exception {
    String ownerToken = login(), tag = suffix();
    UUID roleId =
        role(ownerToken, "RA_WIN_" + tag, "生效时间测试角色", List.of("identity.assignment.read"));
    UUID memberId = member(ownerToken, "ra-window-" + tag, null, List.of());
    String username = "ra-window-" + tag;

    // 未来生效。
    var future = OffsetDateTime.now().plusDays(2);
    request(
        ownerToken,
        "POST",
        "/identity/members/" + memberId + "/role-assignments",
        assignmentBody(roleId, "ALL", List.of(), future),
        200);
    assertThat(assignmentOf(ownerToken, memberId, roleId).get("effectiveStatus").asText())
        .isEqualTo("PENDING");

    var session = request(loginUser(username), "GET", "/auth/session", null, 200);
    assertThat(session.get("permissions").toString()).doesNotContain("identity.assignment.read");

    // 改成过去：立刻生效，会话里出现。
    var past = OffsetDateTime.now().minusDays(2);
    var body = assignmentBody(roleId, "ALL", List.of(), past);
    request(
        ownerToken,
        "PUT",
        "/identity/members/" + memberId + "/role-assignments/" + roleId,
        body,
        200);
    assertThat(assignmentOf(ownerToken, memberId, roleId).get("effectiveStatus").asText())
        .isEqualTo("ACTIVE");
    session = request(loginUser(username), "GET", "/auth/session", null, 200);
    assertThat(session.get("permissions").toString()).contains("identity.assignment.read");

    // 设为已过期：又消失，且预览里显式说明原因。
    var expired = assignmentBody(roleId, "ALL", List.of(), OffsetDateTime.now().minusDays(10));
    expired.put("effectiveUntil", OffsetDateTime.now().minusDays(1).toString());
    request(
        ownerToken,
        "PUT",
        "/identity/members/" + memberId + "/role-assignments/" + roleId,
        expired,
        200);
    assertThat(assignmentOf(ownerToken, memberId, roleId).get("effectiveStatus").asText())
        .isEqualTo("EXPIRED");
    session = request(loginUser(username), "GET", "/auth/session", null, 200);
    assertThat(session.get("permissions").toString()).doesNotContain("identity.assignment.read");

    JsonNode merge =
        request(ownerToken, "GET", "/identity/members/" + memberId + "/effective-permissions", null, 200);
    assertThat(merge.get("permissions")).isEmpty();
    assertThat(merge.get("inactive").get(0).get("reason").asText()).isEqualTo("EXPIRED");
  }

  /**
   * 撤销是终态：权限消失、记录仍在、原因写明。
   *
   * <p>撤销不是删除。删掉之后「谁在什么时候撤的」就查不到了，而授权变更恰恰是最需要追责的一类操作。
   */
  @Test
  void revokeRemovesPermissionsButKeepsTheRecord() throws Exception {
    String ownerToken = login(), tag = suffix();
    UUID roleId =
        role(ownerToken, "RA_RV_" + tag, "撤销测试角色", List.of("identity.assignment.read"));
    UUID memberId = member(ownerToken, "ra-revoke-" + tag, null, List.of());
    String username = "ra-revoke-" + tag;
    request(
        ownerToken,
        "POST",
        "/identity/members/" + memberId + "/role-assignments",
        assignmentBody(roleId, "ALL", List.of(), OffsetDateTime.now().minusDays(1)),
        200);
    assertThat(request(loginUser(username), "GET", "/auth/session", null, 200).get("permissions").toString())
        .contains("identity.assignment.read");

    request(
        ownerToken,
        "POST",
        "/identity/members/" + memberId + "/role-assignments/" + roleId + "/revoke",
        null,
        200);

    JsonNode row = assignmentOf(ownerToken, memberId, roleId);
    assertThat(row.get("status").asText()).isEqualTo("REVOKED");
    assertThat(row.get("effectiveStatus").asText()).isEqualTo("REVOKED");
    assertThat(row.get("revokedAt").isNull()).isFalse();
    assertThat(row.get("revokedBy").isNull()).isFalse();
    assertThat(request(loginUser(username), "GET", "/auth/session", null, 200).get("permissions").toString())
        .doesNotContain("identity.assignment.read");

    // 撤销是终态，不允许再撤销一次。
    request(
        ownerToken,
        "POST",
        "/identity/members/" + memberId + "/role-assignments/" + roleId + "/revoke",
        null,
        400);
  }

  // ------------------------------------------------------------------
  // 合并后的有效权限预览
  // ------------------------------------------------------------------

  /**
   * 多条分配并存时的合并：权限并集、范围并集、全量吸收，并且说得清来源。
   *
   * <p>三条规则各钉一条断言：SINGLE（只有一条分配带这个权限）、UNION（两个角色都带，
   * 范围对象去重合并）、ABSORBED_BY_TENANT_SCOPE（其中一条是租户内全部，整体按全部生效）。
   *
   * <p>刻意不测交集：并集才是「同时负责店铺 A 和仓库 B」的正确答案，
   * 取交集会让这个成员什么都不剩。
   */
  @Test
  void mergedPreviewUnionsPermissionsAndScopes() throws Exception {
    String ownerToken = login(), tag = suffix();
    UUID deptId = createDepartment(ownerToken, "RA_MD_" + tag, "合并测试部门", null);
    UUID entityId =
        UUID.fromString(
            request(
                    ownerToken,
                    "POST",
                    "/identity/legal-entities",
                    legalEntityInput("RAE_" + tag, "USD"),
                    200)
                .asText());
    UUID readerId = role(ownerToken, "RA_R1_" + tag, "合并角色一", List.of("catalog.sku.read"));
    UUID writerId =
        role(
            ownerToken,
            "RA_R2_" + tag,
            "合并角色二",
            List.of("catalog.sku.read", "catalog.spu.read"));
    UUID ownerRoleId =
        role(ownerToken, "RA_R3_" + tag, "合并角色三", List.of("catalog.sku.read"));
    UUID memberId = member(ownerToken, "ra-merge-" + tag, deptId, List.of());
    var now = OffsetDateTime.now();

    request(ownerToken, "POST", "/identity/members/" + memberId + "/role-assignments",
        assignmentBody(readerId, "DEPARTMENT", List.of(scope("DEPARTMENT", deptId, "x")), now), 200);
    request(ownerToken, "POST", "/identity/members/" + memberId + "/role-assignments",
        assignmentBody(writerId, "DEPARTMENT",
            List.of(scope("LEGAL_ENTITY", entityId, "x"), scope("WAREHOUSE", UUID.randomUUID(), "US-WH-1")), now), 200);
    // 第三条是租户内全部：按规则它吸收其余来源。
    request(ownerToken, "POST", "/identity/members/" + memberId + "/role-assignments",
        assignmentBody(ownerRoleId, "ALL", List.of(), now), 200);

    JsonNode merge =
        request(ownerToken, "GET", "/identity/members/" + memberId + "/effective-permissions", null, 200);
    assertThat(merge.get("assignmentCount").asInt()).isEqualTo(3);
    assertThat(merge.get("effectiveCount").asInt()).isEqualTo(3);
    assertThat(merge.get("mergeRule").asText()).contains("并集");

    var byCode = new LinkedHashMap<String, JsonNode>();
    for (JsonNode p : merge.get("permissions")) byCode.put(p.get("permissionCode").asText(), p);

    JsonNode sku = byCode.get("catalog.sku.read");
    assertThat(sku).isNotNull();
    // 三个角色都带这条权限 → 按合并规则，整体是租户内全部。
    assertThat(sku.get("mergeRule").asText()).isEqualTo("ABSORBED_BY_TENANT_SCOPE");
    assertThat(sku.get("mergedScopeMode").asText()).isEqualTo("ALL");
    assertThat(sku.get("sourceRoles")).hasSize(3);
    assertThat(sku.get("pageName").asText()).isEqualTo("SKU");

    // 只被第二个角色带的那条权限：单来源，范围对象就是它自己携带的那些。
    JsonNode spu = byCode.get("catalog.spu.read");
    assertThat(spu).isNotNull();
    assertThat(spu.get("mergeRule").asText()).isEqualTo("SINGLE");
    assertThat(spu.get("mergedScopeMode").asText()).isEqualTo("DEPARTMENT");
    assertThat(spu.get("sourceRoles").get(0).asText()).isEqualTo("RA_R2_" + tag);
    assertThat(spu.get("scopeObjects")).hasSize(2);
    // 法人编码由服务端覆写；仓库是跨服务引用，保留调用方给的名称。
    assertThat(spu.get("scopeObjects").toString()).contains("RAE_" + tag).contains("US-WH-1");
  }

  /**
   * 范围对象去重：同一个对象被两个角色各带一次，预览里只出现一次。
   *
   * <p>预览回答的是「范围并集里有哪些对象」，不是「被几个角色提到」。
   */
  @Test
  void duplicatedScopeObjectsAreMergedOnce() throws Exception {
    String ownerToken = login(), tag = suffix();
    UUID deptId = createDepartment(ownerToken, "RA_DU_" + tag, "去重测试部门", null);
    UUID a = role(ownerToken, "RA_D1_" + tag, "去重角色一", List.of("catalog.sku.read"));
    UUID b = role(ownerToken, "RA_D2_" + tag, "去重角色二", List.of("catalog.sku.read"));
    UUID memberId = member(ownerToken, "ra-dedupe-" + tag, deptId, List.of());
    var now = OffsetDateTime.now();

    request(ownerToken, "POST", "/identity/members/" + memberId + "/role-assignments",
        assignmentBody(a, "DEPARTMENT", List.of(scope("DEPARTMENT", deptId, "x")), now), 200);
    request(ownerToken, "POST", "/identity/members/" + memberId + "/role-assignments",
        assignmentBody(b, "DEPARTMENT", List.of(scope("DEPARTMENT", deptId, "y")), now), 200);

    JsonNode merge =
        request(ownerToken, "GET", "/identity/members/" + memberId + "/effective-permissions", null, 200);
    JsonNode sku = merge.get("permissions").get(0);
    assertThat(sku.get("permissionCode").asText()).isEqualTo("catalog.sku.read");
    assertThat(sku.get("mergeRule").asText()).isEqualTo("UNION");
    assertThat(sku.get("sourceRoles")).hasSize(2);
    assertThat(sku.get("scopeObjects")).hasSize(1);
    assertThat(sku.get("scopeObjects").get(0).get("scopeCode").asText()).isEqualTo("RA_DU_" + tag);
  }

  /** 范围对象必须属于本租户：伪造一个别处法人 id 会被库内触发器挡下。 */
  @Test
  void scopeObjectFromAnotherTenantIsRejected() throws Exception {
    String ownerToken = login(), tag = suffix();
    UUID roleId = role(ownerToken, "RA_XT_" + tag, "跨租户范围角色", List.of("catalog.sku.read"));
    UUID memberId = member(ownerToken, "ra-cross-" + tag, null, List.of());
    var now = OffsetDateTime.now();

    UUID otherEntity =
        UUID.fromString(
            request(loginUser(childTenant(ownerToken, tag)), "POST", "/identity/legal-entities",
                legalEntityInput("RX_" + tag, "USD"), 200).asText());

    request(ownerToken, "POST", "/identity/members/" + memberId + "/role-assignments",
        assignmentBody(roleId, "DEPARTMENT", List.of(scope("LEGAL_ENTITY", otherEntity, "别人的法人")), now), 400);
  }

  // ------------------------------------------------------------------
  // 权限边界
  // ------------------------------------------------------------------

  /**
   * 配置分配是所有者专属；没有角色分配的成员拿不到本模块的读写路径。
   *
   * <p>与 V42 层级授权、既有成员管理同一道闸：授权与组织变更必须由租户所有者执行。
   */
  @Test
  void onlyOwnerConfiguresAssignments() throws Exception {
    String ownerToken = login(), tag = suffix();
    UUID deptId = createDepartment(ownerToken, "RA_OW_" + tag, "权限测试部门", null);
    UUID readerId =
        role(ownerToken, "RA_OW1_" + tag, "只读分配角色", List.of("identity.assignment.read"));
    UUID memberId = member(ownerToken, "ra-owner-" + tag, deptId, List.of(all(readerId)));
    String memberToken = loginUser("ra-owner-" + tag);

    // 有读权限码：看得见分配列表（自己那条），但写路径被所有者这道闸挡住。
    assertThat(assignmentsOf(memberToken, memberId)).hasSize(1);
    assertThat(assignmentsOf(memberToken, memberId).get(0).get("roleCode").asText())
        .isEqualTo("RA_OW1_" + tag);
    // 写路径是所有者专属，与既有管理端点同口径。
    request(memberToken, "POST", "/identity/members/" + memberId + "/role-assignments",
        assignmentBody(readerId, "ALL", List.of(), OffsetDateTime.now()), 403);

    // 完全没有该页面权限码的成员，连列表都拿不到（403 而不是空列表）。
    UUID plainId = role(ownerToken, "RA_OW2_" + tag, "无关角色", List.of("catalog.sku.read"));
    UUID plainMember = member(ownerToken, "ra-plain-" + tag, deptId, List.of(all(plainId)));
    String plainToken = loginUser("ra-plain-" + tag);
    request(plainToken, "GET", "/identity/role-assignments?membershipId=" + plainMember, null, 403);
    request(plainToken, "GET", "/identity/role-assignment-scopes", null, 403);
  }

  /** 范围候选只给本服务持有的法人与部门；店铺与仓库不属于本库，不会拿假数据凑数。 */
  @Test
  void scopeOptionsCoverOnlyTenantOwnedTypes() throws Exception {
    String ownerToken = login(), tag = suffix();
    UUID deptId = createDepartment(ownerToken, "RA_OP_" + tag, "候选部门", null);
    request(ownerToken, "POST", "/identity/legal-entities", legalEntityInput("ROE_" + tag, "USD"), 200);

    JsonNode options = request(ownerToken, "GET", "/identity/role-assignment-scopes", null, 200);
    var types = new ArrayList<String>();
    for (JsonNode o : options) types.add(o.get("scopeType").asText());
    assertThat(types).contains("LEGAL_ENTITY", "DEPARTMENT");
    assertThat(types).doesNotContain("SHOP", "WAREHOUSE");
    assertThat(options.toString()).contains("ROE_" + tag).contains("RA_OP_" + tag).contains(deptId.toString());
  }

  // ------------------------------------------------------------------
  // 多租户隔离三断言
  // ------------------------------------------------------------------

  /**
   * 两租户互不可见 + 伪造 tenant header 无效 + 连接池归还后不串租户。
   *
   * <p>三条压在一个用例里，因为它们是同一件事的三面：分配表按 tenant_id 隔离，
   * 租户来自已认证成员关系而不是请求头。底座把连接池压到 1，父 → 子 → 父 三次请求
   * 必然复用同一条物理连接，父的分配行如果泄漏到连接上，子那一次就会看到它。
   *
   * <p>{@link IdentityIntegrationTestBase#request} 每次都带随机 {@code X-Tenant-Id}，
   * 所以这一串断言同时证明了伪造头不产生任何影响。
   */
  @Test
  void tenantsStayIsolatedAcrossPooledConnections() throws Exception {
    String ownerToken = login(), tag = suffix();
    UUID deptId = createDepartment(ownerToken, "RA_TN_" + tag, "隔离测试部门", null);
    UUID roleId = role(ownerToken, "RA_TN1_" + tag, "隔离测试角色", List.of("catalog.sku.read"));
    UUID memberId = member(ownerToken, "ra-isolation-" + tag, deptId, List.of());
    request(ownerToken, "POST", "/identity/members/" + memberId + "/role-assignments",
        assignmentBody(roleId, "DEPARTMENT", List.of(scope("DEPARTMENT", deptId, "x")),
            OffsetDateTime.now()), 200);

    String childToken = loginUser(childTenant(ownerToken, tag));
    UUID childDept = createDepartment(childToken, "RA_TC_" + tag, "子租户部门", null);
    UUID childRole = role(childToken, "RA_TC1_" + tag, "子租户角色", List.of("catalog.sku.read"));
    UUID childMember = member(childToken, "ra-child-member-" + tag, childDept, List.of());
    request(childToken, "POST", "/identity/members/" + childMember + "/role-assignments",
        assignmentBody(childRole, "DEPARTMENT", List.of(scope("DEPARTMENT", childDept, "x")),
            OffsetDateTime.now()), 200);

    // 父看得到自己那一条。
    assertThat(assignmentsOf(ownerToken, memberId)).hasSize(1);
    // 子看得到自己那一条（同一物理连接，上一步刚写过父租户上下文）。
    assertThat(assignmentsOf(childToken, childMember)).hasSize(1);
    // 互相看不到对方：父读不到子租户成员的分配，子读不到父租户成员的分配。
    assertThat(assignmentsOf(ownerToken, childMember)).isEmpty();
    assertThat(assignmentsOf(childToken, memberId)).isEmpty();
    // 再回父：仍然只有自己那一条，没有把子租户的上下文带回来。
    assertThat(assignmentsOf(ownerToken, memberId)).hasSize(1);

    // 运行时角色绕不过 RLS：不带租户上下文直查最多只能看到当前租户的行。
    var asParent =
        tx().run(UUID.fromString(tenantOf(ownerToken)),
            () -> sql.fetch("select membership_id from role_assignment"))
            .stream().map(r -> r.get("membership_id", UUID.class)).toList();
    var asChild =
        tx().run(UUID.fromString(tenantOf(childToken)),
            () -> sql.fetch("select membership_id from role_assignment"))
            .stream().map(r -> r.get("membership_id", UUID.class)).toList();
    assertThat(asParent).contains(memberId).doesNotContain(childMember);
    assertThat(asChild).contains(childMember).doesNotContain(memberId);

    // 范围表同样按租户隔离。
    var scopesAsParent =
        tx().run(UUID.fromString(tenantOf(ownerToken)),
            () -> sql.fetch("select membership_id from role_assignment_scope"))
            .stream().map(r -> r.get("membership_id", UUID.class)).toList();
    assertThat(scopesAsParent).contains(memberId).doesNotContain(childMember);
  }

  /** 跨租户的成员 id 直接报「不存在」：RLS 把行藏起来之后，接口只能这样回答。 */
  @Test
  void memberOfAnotherTenantIsNotVisible() throws Exception {
    String ownerToken = login(), tag = suffix();
    String childToken = loginUser(childTenant(ownerToken, tag));
    UUID childMember = member(childToken, "ra-hidden-" + tag, null, List.of());
    request(ownerToken, "GET", "/identity/role-assignments?membershipId=" + childMember, null, 200);
    request(ownerToken, "GET", "/identity/members/" + childMember + "/effective-permissions", null, 404);
    request(ownerToken, "POST", "/identity/members/" + childMember + "/role-assignments",
        assignmentBody(UUID.randomUUID(), "ALL", List.of(), OffsetDateTime.now()), 404);
  }

  // ------------------------------------------------------------------
  // 审计
  // ------------------------------------------------------------------

  /**
   * 每次变更都要留审计：范围、生效时间、撤销痕迹必须能还原。
   *
   * <p>只有一句「改过分配」等于没法追责——事后没人能回答「当时到底把权限放到了哪些范围、放了多久」。
   */
  @Test
  void changesAreAudited() throws Exception {
    String ownerToken = login(), tag = suffix();
    UUID deptId = createDepartment(ownerToken, "RA_AU_" + tag, "审计测试部门", null);
    UUID roleId = role(ownerToken, "RA_AU1_" + tag, "审计测试角色", List.of("catalog.sku.read"));
    UUID memberId = member(ownerToken, "ra-audit-" + tag, deptId, List.of());
    var now = OffsetDateTime.now();

    request(ownerToken, "POST", "/identity/members/" + memberId + "/role-assignments",
        assignmentBody(roleId, "DEPARTMENT", List.of(scope("DEPARTMENT", deptId, "x")), now), 200);
    request(ownerToken, "POST", "/identity/members/" + memberId + "/role-assignments/" + roleId + "/revoke",
        null, 200);

    var changes =
        tx().run(UUID.fromString(tenantOf(ownerToken)), () ->
                sql.fetch(
                    "select action,changes from identity_audit where resource_type='identity.assignment'"
                        + " and resource_id=? order by occurred_at",
                    memberId))
            .stream()
            .map(r -> r.get("action", String.class) + " " + r.get("changes", String.class))
            .toList();
    assertThat(changes).hasSize(2);
    assertThat(changes.get(0)).startsWith("RoleAssignmentCreated");
    assertThat(changes.get(1)).startsWith("RoleAssignmentRevoked");
    // 范围与生效时间都要进快照。
    assertThat(changes.get(0)).contains("DEPARTMENT:" + deptId).contains("effectiveFrom");
    assertThat(changes.get(1)).contains("REVOKED").contains(deptId.toString());
  }
}
