package com.starcon.identity;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.starcon.tenancy.TenantTransactions;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 17.1.1.1 租户列表。
 *
 * <p>本模块是全系统唯一允许的跨租户列表查询路径，所以这里的重点不是「功能能用」， 而是证明这个列表**只能是自己的成员关系租户**：没有成员关系的租户一条都不该出现， 伪造 {@code
 * X-Tenant-Id} 也不能把范围撑大，管理员同样看不到自己管不到的平台租户。
 */
class TenantListIntegrationTest extends IdentityIntegrationTestBase {

  private TenantTransactions tx() {
    return new TenantTransactions(sql, new TransactionTemplate(manager));
  }

  /** 造一个子租户，返回它的所有者账号名。 */
  private String childTenant(String ownerToken, String tag, String code) throws Exception {
    String username = "tl-child-" + tag;
    request(
            ownerToken,
            "POST",
            "/identity/children",
            Map.of(
                "code",
                code,
                "name",
                "租户列表子租户",
                "ownerUsername",
                username,
                "ownerPassword",
                "test-member-password"),
            200)
        .asText();
    return username;
  }

  /**
   * 建一个普通角色并返回它的 id。
   *
   * <p>不能用 OWNER：既有规则禁止把 OWNER 直接授予成员（必须走转移所有者操作）， 所以普通成员一律配一个自定义角色。
   */
  private UUID roleId(String token, String tag) throws Exception {
    return UUID.fromString(
        request(
                token,
                "POST",
                "/identity/roles",
                Map.of(
                    "code",
                    "TLR_" + tag,
                    "name",
                    "租户列表测试角色",
                    "status",
                    "ACTIVE",
                    "permissions",
                    List.of("catalog.spu.read")),
                200)
            .asText());
  }

  /** 在列表里按 code 找一行，找不到返回 null。 */
  private JsonNode find(JsonNode tenants, String code) {
    for (JsonNode node : tenants) if (code.equals(node.get("code").asText())) return node;
    return null;
  }

  private String currentTenantId(String token) throws Exception {
    return request(token, "GET", "/identity/tenants/current", null, 200).get("tenantId").asText();
  }

  /** 多租户账号登录时必须指定身份，否则后端只回身份清单不发令牌。 */
  private String loginAs(String username, String membershipId) throws Exception {
    return request(
            "",
            "POST",
            "/auth/login",
            Map.of(
                "username",
                username,
                "password",
                "test-member-password",
                "membershipId",
                membershipId),
            200)
        .get("token")
        .asText();
  }

  private String membershipFor(String username, String tenantId) throws Exception {
    var memberships =
        request(
                "",
                "POST",
                "/auth/login",
                Map.of("username", username, "password", "test-member-password"),
                200)
            .get("memberships");
    for (JsonNode m : memberships)
      if (tenantId.equals(m.get("tenantId").asText())) return m.get("id").asText();
    return null;
  }

  /**
   * 列表只返回有成员关系的租户，并且角色来自真实分配。
   *
   * <p>注意本地所有者只属于一个租户，但它在自己租户下能创建子租户——子租户对它是**没有 成员关系**的，所以它必须看不到子租户。这条最容易做漏：列表一旦不小心从 tenant 表起查，
   * 子租户就会混进来，而「父租户看得见子租户」恰好是用户最自然的预期。
   */
  @Test
  void switchableTenantsOnlyContainMembershipTenants() throws Exception {
    String ownerToken = login(), tag = suffix(), code = "TLC_" + tag;
    String childOwner = childTenant(ownerToken, tag, code);

    var listed = request(ownerToken, "GET", "/identity/tenants", null, 200);
    assertThat(listed).hasSize(1);
    JsonNode mine = listed.get(0);
    assertThat(mine.get("code").asText()).isEqualTo("LOCAL");
    assertThat(mine.get("status").asText()).isEqualTo("ACTIVE");
    assertThat(mine.get("current").asBoolean()).isTrue();
    // 角色必须来自 role_assignment 的真实分配，而不是租户表上任何可推导的东西。
    assertThat(mine.get("roles").toString()).contains("OWNER");

    // 子租户归 childOwner 所有，对 ownerToken 没有成员关系：一条都不该出现。
    assertThat(find(listed, code)).isNull();
    assertThat(listed.toString()).doesNotContain(code);

    // 但子租户所有者自己看得到自己那一个租户，列表确实不是恒空。
    var child = request(loginUser(childOwner), "GET", "/identity/tenants", null, 200);
    assertThat(child).hasSize(1);
    assertThat(child.get(0).get("code").asText()).isEqualTo(code);
    assertThat(child.get(0).get("current").asBoolean()).isTrue();
    assertThat(child.get(0).get("roles").toString()).contains("OWNER");
  }

  /**
   * 伪造 tenant header 不能扩大可见范围。
   *
   * <p>{@code request()} 本来就每次都带一个随机 {@code X-Tenant-Id}，这里再显式指定成 「一个真实存在但与我无关的租户」，确认连真实 id
   * 也换不来任何东西。
   */
  @Test
  void forgedTenantHeaderCannotWidenTheList() throws Exception {
    String ownerToken = login(), tag = suffix(), code = "TLF_" + tag;
    String foreign = currentTenantId(loginUser(childTenant(ownerToken, tag, code)));

    var forged =
        http.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                        "/identity/tenants")
                    .header("Authorization", "Bearer " + ownerToken)
                    .header("X-Tenant-Id", foreign))
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(forged).doesNotContain(foreign).doesNotContain(code);
    assertThat(json.readTree(forged)).hasSize(1);
  }

  /** 没有任何租户 id 形式的入口：不接受「按 tenant_id 任意查询」。 */
  @Test
  void thereIsNoEndpointToReadAnArbitraryTenant() throws Exception {
    String ownerToken = login(), tag = suffix(), code = "TLR_" + tag;
    String foreign = currentTenantId(loginUser(childTenant(ownerToken, tag, code)));

    for (String path :
        List.of("/identity/tenants/" + foreign, "/identity/tenants/current/" + foreign)) {
      var response =
          http.perform(
                  org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path)
                      .header("Authorization", "Bearer " + ownerToken)
                      .header("X-Tenant-Id", foreign))
              .andReturn()
              .getResponse();
      assertThat(response.getStatus()).as("按任意 tenant id 读取必须不成立：%s", path).isIn(404, 405);
    }
  }

  /**
   * 失去全部成员关系的用户拿不到任何租户。
   *
   * <p>这是「仅返回有成员关系的租户」的下界：列表不能退化成「空数组 + 200」， 那会被界面渲染成「你还没有租户」。成员被删除后现存令牌直接失效。
   */
  @Test
  void userWithoutMembershipSeesNoTenant() throws Exception {
    String ownerToken = login(), tag = suffix(), username = "tl-orphan-" + tag;
    request(
        ownerToken,
        "POST",
        "/identity/members",
        memberInput(
            username, null, List.of(Map.of("roleId", roleId(ownerToken, tag), "scopeMode", "ALL"))),
        200);
    String orphanToken = loginUser(username);
    assertThat(request(orphanToken, "GET", "/identity/tenants", null, 200)).hasSize(1);

    String membershipId =
        request(orphanToken, "GET", "/auth/session", null, 200).get("membershipId").asText();
    request(ownerToken, "DELETE", "/identity/members/" + membershipId, null, 200);

    // 令牌还在库里，但已没有任何成员关系：不是空列表，而是整个会话失效。
    // 401 而不是 200+[] —— 空数组会被界面渲染成「你还没有租户」，那是骗人的。
    request(orphanToken, "GET", "/identity/tenants", null, 401);
    request(orphanToken, "GET", "/identity/tenants/current", null, 401);
    assertThat(request(ownerToken, "GET", "/identity/tenants", null, 200).toString())
        .doesNotContain(username);
  }

  /**
   * 最后登录是真实写入的 timestamptz，且能原样读回。
   *
   * <p>这里断言的是**值**而不是「没抛异常」：用 {@code Timestamp.valueOf(LocalDateTime)} 绑 timestamptz 会在
   * Asia/Shanghai 下静默偏 −28800 秒，落在下界断言之外。
   */
  @Test
  void lastLoginIsARealTimestampWrittenAndReadBack() throws Exception {
    String ownerToken = login(), tag = suffix(), code = "TLT_" + tag;
    Instant before = Instant.now();
    String token = loginUser(childTenant(ownerToken, tag, code));

    var listed = request(token, "GET", "/identity/tenants", null, 200);
    assertThat(listed).hasSize(1);
    assertThat(listed.get(0).get("lastLoginAt").isNull()).isFalse();
    OffsetDateTime parsed = OffsetDateTime.parse(listed.get(0).get("lastLoginAt").asText());
    assertThat(parsed.toInstant())
        .as("最后登录必须落在登录动作附近（漂移 8 小时说明 timestamptz 绑定错了）")
        .isAfterOrEqualTo(before.minusSeconds(60))
        .isBefore(Instant.now().plusSeconds(60));

    UUID tenantId = UUID.fromString(listed.get(0).get("tenantId").asText());
    UUID membershipId = UUID.fromString(listed.get(0).get("membershipId").asText());
    OffsetDateTime stored =
        tx().run(
                tenantId,
                () ->
                    sql.fetchOne("select last_login_at from membership where id=?", membershipId)
                        .get("last_login_at", OffsetDateTime.class));
    assertThat(stored).isNotNull();
    assertThat(stored.toInstant()).isAfterOrEqualTo(before.minusSeconds(60));
    assertThat(stored.toInstant()).isEqualTo(parsed.toInstant());
  }

  /**
   * 切换租户也要算一次「进入」。
   *
   * <p>同一个账号在两个租户都有成员身份：切到 B 之后 B 的最后登录被刷新，而**没有切换的** A 的时间戳必须原样不动——否则「最后登录」就答不出「我上次进的是哪个租户」。
   */
  @Test
  void switchingTenantStampsOnlyTheNewlyEnteredTenant() throws Exception {
    String ownerToken = login(), tag = suffix(), code = "TLW_" + tag;
    String localTenant = currentTenantId(ownerToken);
    String portable = childTenant(ownerToken, tag, code);

    // 本地租户里再给同一个账号一个成员身份，于是它有两个可切换租户。
    // 账号已存在（作为子租户所有者），密码必须传 null：既有规则不允许借新建顺带重置密码。
    request(
        ownerToken,
        "POST",
        "/identity/members",
        memberInput(
            portable,
            null,
            List.of(Map.of("roleId", roleId(ownerToken, tag), "scopeMode", "ALL")),
            null),
        200);
    String localMembership = membershipFor(portable, localTenant);
    assertThat(localMembership).isNotNull();
    String token = loginAs(portable, localMembership);

    var before = request(token, "GET", "/identity/tenants", null, 200);
    assertThat(before).hasSize(2);
    String localStamp = find(before, "LOCAL").get("lastLoginAt").asText();
    String childStamp = find(before, code).get("lastLoginAt").asText();
    String childMembership = membershipFor(portable, find(before, code).get("tenantId").asText());

    Instant switchedAt = Instant.now();
    request(token, "POST", "/auth/switch", Map.of("membershipId", childMembership), 200);

    var after = request(token, "GET", "/identity/tenants", null, 200);
    assertThat(find(after, code).get("current").asBoolean()).isTrue();
    assertThat(find(after, "LOCAL").get("current").asBoolean()).isFalse();
    assertThat(find(after, code).get("lastLoginAt").asText()).isNotEqualTo(childStamp);
    assertThat(OffsetDateTime.parse(find(after, code).get("lastLoginAt").asText()).toInstant())
        .isAfterOrEqualTo(switchedAt.minusSeconds(60));
    // 没切过去的那个租户时间戳不动。
    assertThat(find(after, "LOCAL").get("lastLoginAt").asText()).isEqualTo(localStamp);
    // 当前上下文也跟着换。
    assertThat(currentTenantId(token)).isEqualTo(find(after, code).get("tenantId").asText());
  }

  /** 租户管理员可维护本租户基本信息；非管理员不能。 */
  @Test
  void onlyTenantOwnerManagesTheTenantProfile() throws Exception {
    String ownerToken = login(), tag = suffix(), code = "TLO_" + tag;
    childTenant(ownerToken, tag, code);
    String member = "tl-member-" + tag;
    request(
        ownerToken,
        "POST",
        "/identity/members",
        memberInput(
            member, null, List.of(Map.of("roleId", roleId(ownerToken, tag), "scopeMode", "ALL"))),
        200);
    String memberToken = loginUser(member);

    // 普通成员读得到上下文，但 managed=false，拿不到修改权。
    var readOnly = request(memberToken, "GET", "/identity/tenants/current", null, 200);
    assertThat(readOnly.get("managed").asBoolean()).isFalse();
    assertThat(readOnly.get("code").asText()).isEqualTo("LOCAL");
    assertThat(readOnly.get("memberCount").asInt()).isGreaterThanOrEqualTo(2);
    assertThat(readOnly.get("childCount").asInt()).isGreaterThanOrEqualTo(1);
    assertThat(readOnly.get("parentTenantId").isNull()).isTrue();

    request(
        memberToken,
        "PUT",
        "/identity/tenants/current",
        Map.of("name", "越权改名", "status", "ACTIVE"),
        403);

    request(
        ownerToken,
        "PUT",
        "/identity/tenants/current",
        Map.of("name", "本地开发租户 改名", "status", "ACTIVE"),
        200);
    var after = request(ownerToken, "GET", "/identity/tenants/current", null, 200);
    assertThat(after.get("name").asText()).isEqualTo("本地开发租户 改名");
    assertThat(after.get("managed").asBoolean()).isTrue();
    assertThat(after.get("code").asText()).as("code 不可改").isEqualTo("LOCAL");
    assertThat(
            request(ownerToken, "GET", "/identity/tenants", null, 200).get(0).get("name").asText())
        .isEqualTo("本地开发租户 改名");

    // 改名进审计：事后要能答出当时叫什么。列表只给摘要，变更前后值在详情里。
    var entries =
        request(ownerToken, "GET", "/identity/audit?resourceType=tenant.profile", null, 200);
    JsonNode entry = entries.get("items").get(0);
    assertThat(entry.get("action").asText()).isEqualTo("TenantProfileUpdated");
    assertThat(entry.get("resourceLabel").asText()).isEqualTo("LOCAL");
    var detail =
        request(ownerToken, "GET", "/identity/audit/" + entry.get("id").asText(), null, 200);
    assertThat(detail.toString()).contains("本地开发租户 改名").contains("本地开发租户");

    // 校验是真实的，不是装饰。
    request(
        ownerToken,
        "PUT",
        "/identity/tenants/current",
        Map.of("name", "  ", "status", "ACTIVE"),
        400);
    request(
        ownerToken,
        "PUT",
        "/identity/tenants/current",
        Map.of("name", "ok", "status", "GONE"),
        400);
  }

  /** 停用后该租户不再出现在可切换集合里（对它自己而言等于退出）。 */
  @Test
  void suspendedTenantDropsOutOfTheSwitchableList() throws Exception {
    String ownerToken = login(), tag = suffix(), code = "TLS_" + tag;
    String siblingToken = loginUser(childTenant(ownerToken, tag, code));

    assertThat(request(siblingToken, "GET", "/identity/tenants", null, 200)).hasSize(1);
    request(
        siblingToken,
        "PUT",
        "/identity/tenants/current",
        Map.of("name", "已停用租户", "status", "SUSPENDED"),
        200);
    // 停用后该租户内所有现存会话失效，不再是可切换上下文（会话先失效，所以是 401）。
    request(siblingToken, "GET", "/identity/tenants", null, 401);
    // 父租户不受影响：父子之间不自动赋权，停用一个也不会连累另一个。
    var parent = request(ownerToken, "GET", "/identity/tenants", null, 200);
    assertThat(parent).hasSize(1);
    assertThat(find(parent, code)).as("父租户本来就看不到子租户").isNull();
  }

  /**
   * 租户隔离三断言（两租户互不可见 / 伪造头无效 / 连接归还后不串租户）。
   *
   * <p>连接池在基类里被压到 1，所以每个请求必然复用同一条物理连接——租户设置如果泄漏到连接上， 第二个租户就会读到第一个租户的数据。
   */
  @Test
  void tenantIsolationHoldsAcrossConnectionReuse() throws Exception {
    String ownerToken = login(), tag = suffix(), code = "TLI_" + tag;
    String childToken = loginUser(childTenant(ownerToken, tag, code));
    UUID parentId = UUID.fromString(currentTenantId(ownerToken));
    UUID childId = UUID.fromString(currentTenantId(childToken));
    assertThat(parentId).isNotEqualTo(childId);

    // 交替请求，两个上下文互不串味。
    for (int i = 0; i < 3; i++) {
      assertThat(currentTenantId(ownerToken)).isEqualTo(parentId.toString());
      assertThat(currentTenantId(childToken)).isEqualTo(childId.toString());
    }

    // 直连表：RLS 下每个租户只看得到自己的 membership 行，池只有一条连接。
    var parentRows =
        tx().run(
                parentId, () -> sql.fetch("select id from membership").getValues("id", UUID.class));
    var childRows =
        tx().run(childId, () -> sql.fetch("select id from membership").getValues("id", UUID.class));
    assertThat(parentRows).isNotEmpty().doesNotContainAnyElementsOf(childRows);
    assertThat(childRows).isNotEmpty();
    // 没有 app.current_tenant 时一条都读不到：租户判定不在连接上残留。
    assertThat(sql.fetch("select id from membership")).isEmpty();
  }

  /** 本租户的表仍然是 FORCE RLS，运行时角色不能绕过。 */
  @Test
  void runtimeRoleCannotBypassTenantIsolation() {
    var runtime =
        sql.fetchOne("select rolsuper,rolbypassrls from pg_roles where rolname=current_user");
    assertThat(runtime.get("rolsuper", Boolean.class)).isFalse();
    assertThat(runtime.get("rolbypassrls", Boolean.class)).isFalse();
    assertThat(
            sql.fetch(
                "select relname from pg_class join pg_namespace n on n.oid=relnamespace"
                    + " where n.nspname='public' and relname='membership'"
                    + " and (not relrowsecurity or not relforcerowsecurity)"))
        .isEmpty();
  }
}
