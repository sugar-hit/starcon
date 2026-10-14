package com.starcon.identity;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.starcon.tenancy.TenantTransactions;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 17.1.1.3 层级授权。
 *
 * <p>本模块的重点不是「能配一条授权」，而是证明**默认是拒绝**：父子关系本身不带来任何访问权
 * （ADR-0005）。所以第一条断言就是本模块的核心——父租户在**没有**任何授权记录时访问子租户必须被拒， 而且要给出可区分的 reason，否则调用方分不清「没配过」和「配了但过期」。
 *
 * <p>其余用例钉住三件事：授权范围真的生效（域 / 只读可写 / 有效期）， 撤销是终态，以及多租户隔离三断言（两租户互不可见、伪造 X-Tenant-Id 无效、
 * 连接池归还后不串租户——底座把池压到 1，每次请求必然复用同一条物理连接）。
 */
class HierarchyAuthorizationIntegrationTest extends IdentityIntegrationTestBase {

  private TenantTransactions tx() {
    return new TenantTransactions(sql, new TransactionTemplate(manager));
  }

  /** 建一个子租户，返回它的所有者账号名。 */
  private String childTenant(String ownerToken, String tag) throws Exception {
    String username = "hz-child-" + tag;
    request(
            ownerToken,
            "POST",
            "/identity/children",
            Map.of(
                "code",
                "HZ_" + tag,
                "name",
                "层级授权子租户",
                "ownerUsername",
                username,
                "ownerPassword",
                "test-member-password"),
            200)
        .asText();
    return username;
  }

  /** 子租户 id：靠登录返回的成员关系反查，不从创建响应里抠（响应只给 id，租户 id 要从会话推）。 */
  private String tenantOf(String token) throws Exception {
    return request(token, "GET", "/identity/tenants/current", null, 200).get("tenantId").asText();
  }

  private UUID grant(
      String token,
      String childTenantId,
      List<String> domains,
      String level,
      OffsetDateTime from,
      OffsetDateTime until) {
    var body = new java.util.LinkedHashMap<String, Object>();
    body.put("childTenantId", childTenantId);
    body.put("domains", domains);
    body.put("accessLevel", level);
    body.put("validFrom", from.toString());
    if (until != null) body.put("validUntil", until.toString());
    try {
      return UUID.fromString(
          request(token, "POST", "/identity/hierarchy-authorizations", body, 200).asText());
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * 只取某个子租户对应的授权行。
   *
   * <p>必须按子租户过滤而不是断言整张列表为空或长度为 1：底座的容器跨测试类共享， 本地所有者所属的租户会累积其它用例建过的授权。对全局计数做断言等于让这条用例
   * 依赖执行顺序，后面接任何一个用例都会把它跑红。
   */
  private List<JsonNode> grantsFor(String token, String childTenantId) {
    try {
      var all = request(token, "GET", "/identity/hierarchy-authorizations", null, 200);
      var mine = new java.util.ArrayList<JsonNode>();
      for (JsonNode n : all) if (childTenantId.equals(n.get("childTenantId").asText())) mine.add(n);
      return mine;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private JsonNode check(String token, String childTenantId, String domain, boolean write) {
    var body = new java.util.LinkedHashMap<String, Object>();
    if (childTenantId != null) body.put("childTenantId", childTenantId);
    body.put("domain", domain);
    body.put("requireWrite", write);
    try {
      return request(token, "POST", "/identity/hierarchy-authorizations/check", body, 200);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private JsonNode grantRow(String token, UUID id) {
    try {
      for (JsonNode n : request(token, "GET", "/identity/hierarchy-authorizations", null, 200))
        if (id.toString().equals(n.get("id").asText())) return n;
      return null;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  // ------------------------------------------------------------------
  // 核心断言：默认拒绝
  // ------------------------------------------------------------------

  /**
   * 本模块的核心断言。
   *
   * <p>父租户所有者**没有任何授权记录**时访问子租户必须被拒。这条最容易漏做，因为在 「父能看见子」的直觉下，实现者很容易顺手让层级关系隐含赋权——而那正是 ADR-0005
   * 明令禁止的（`V3` 的闭包注释也写着「listing never expands business access」）。
   *
   * <p>同时钉住 reason：必须是 {@code NO_GRANT}（没配过）而不是笼统的 false。 两种拒绝在处置上完全不同，前者找管理员配置，后者等过期或续期。
   */
  @Test
  void parentCannotAccessChildWithoutGrant() throws Exception {
    String ownerToken = login(), tag = suffix();
    String childOwner = childTenant(ownerToken, tag);
    String childToken = loginUser(childOwner);
    String childTenantId = tenantOf(childToken);

    JsonNode denied = check(ownerToken, childTenantId, "catalog.spu", false);
    assertThat(denied.get("allowed").asBoolean()).isFalse();
    assertThat(denied.get("reason").asText()).isEqualTo("NO_GRANT");
    assertThat(denied.get("grantId").isNull()).isTrue();

    // 层级关系本身不给任何东西：这个子租户名下没有任何已登记的授权开口。
    assertThat(grantsFor(ownerToken, childTenantId)).isEmpty();
  }

  /**
   * 授权建立后判定放行，且父子两侧对同一事实的认知一致。
   *
   * <p>子租户所有者仍然只能看见**自己**配置的授权：授权是父租户单方面开的口， 子租户不应在父租户的上下文里插一脚（否则它能看见并删掉集团给它的授权）。
   */
  @Test
  void grantOpensAccessOnlyForGrantedDomain() throws Exception {
    String ownerToken = login(), tag = suffix();
    String childOwner = childTenant(ownerToken, tag);
    String childToken = loginUser(childOwner);
    String childTenantId = tenantOf(childToken);
    var now = OffsetDateTime.now();

    UUID id =
        grant(
            ownerToken,
            childTenantId,
            List.of("catalog.spu", "catalog.sku"),
            "READ",
            now.minusHours(1),
            now.plusHours(1));

    JsonNode allowed = check(ownerToken, childTenantId, "catalog.spu", false);
    assertThat(allowed.get("allowed").asBoolean()).isTrue();
    assertThat(allowed.get("reason").asText()).isEqualTo("GRANTED");
    assertThat(allowed.get("grantId").asText()).isEqualTo(id.toString());
    assertThat(allowed.get("accessLevel").asText()).isEqualTo("READ");

    // 授权范围之外的域仍然拒绝——授权是按数据域开的，不是「进了这个子租户就全给」。
    JsonNode other = check(ownerToken, childTenantId, "trade.order", false);
    assertThat(other.get("allowed").asBoolean()).isFalse();
    assertThat(other.get("reason").asText()).isEqualTo("DOMAIN_NOT_GRANTED");

    // 子租户侧看不到父租户配置的授权行。
    assertThat(grantsFor(childToken, childTenantId)).isEmpty();
  }

  // ------------------------------------------------------------------
  // 只读 / 可写
  // ------------------------------------------------------------------

  /**
   * 只读授权不允许写：WRITE 要求必须被显式满足。
   *
   * <p>降级方向是单向的：READ 授权永远不能推出 WRITE。写权限比读权限危险得多， 让它隐式成立等于把「只读观察子公司」变成「可以改子公司单据」。
   */
  @Test
  void readGrantDoesNotSatisfyWrite() throws Exception {
    String ownerToken = login(), tag = suffix();
    String childToken = loginUser(childTenant(ownerToken, tag));
    String childTenantId = tenantOf(childToken);
    var now = OffsetDateTime.now();

    UUID grantId =
        grant(ownerToken, childTenantId, List.of("catalog.spu"), "READ", now.minusHours(1), null);

    assertThat(check(ownerToken, childTenantId, "catalog.spu", false).get("allowed").asBoolean())
        .isTrue();
    JsonNode write = check(ownerToken, childTenantId, "catalog.spu", true);
    assertThat(write.get("allowed").asBoolean()).isFalse();
    assertThat(write.get("reason").asText()).isEqualTo("WRITE_NOT_GRANTED");

    // 升到 WRITE 后写放行，读仍然放行（读是写的子集）。
    // 用 grant() 返回的 id，而不是列表第一行：列表里还躺着其它用例建的授权。
    request(
        ownerToken,
        "PUT",
        "/identity/hierarchy-authorizations/" + grantId,
        Map.of(
            "domains", List.of("catalog.spu"),
            "accessLevel", "WRITE",
            "validFrom", now.minusHours(1).toString()),
        200);
    assertThat(check(ownerToken, childTenantId, "catalog.spu", true).get("allowed").asBoolean())
        .isTrue();
  }

  // ------------------------------------------------------------------
  // 有效期：写回值与过期判定
  // ------------------------------------------------------------------

  /**
   * 有效期写回的是**同一个瞬间**，不是差 8 小时的另一个瞬间。
   *
   * <p>这是本模块时间语义的重点。用 {@code Timestamp.valueOf(LocalDateTime)} 绑 timestamptz 会 静默偏
   * −28800s：接口看起来正常，列表里却是错的到期时间。所以这里同时断言 API 回读与 直接查库，两处都必须等于输入的 {@link
   * OffsetDateTime#toInstant()}。
   */
  @Test
  void validityWindowRoundTripsWithoutDrift() throws Exception {
    String ownerToken = login(), tag = suffix();
    String childToken = loginUser(childTenant(ownerToken, tag));
    String childTenantId = tenantOf(childToken);

    var from = OffsetDateTime.now().minusDays(1).withSecond(0).truncatedTo(ChronoUnit.SECONDS);
    var until = OffsetDateTime.now().plusDays(30).withSecond(0).truncatedTo(ChronoUnit.SECONDS);

    UUID id = grant(ownerToken, childTenantId, List.of("catalog.spu"), "READ", from, until);

    JsonNode row = grantRow(ownerToken, id);
    assertThat(OffsetDateTime.parse(row.get("validFrom").asText()).toInstant())
        .isEqualTo(from.toInstant());
    assertThat(OffsetDateTime.parse(row.get("validUntil").asText()).toInstant())
        .isEqualTo(until.toInstant());

    // 再直接查一次库：不能只信序列化层，列本身就必须是输入的那个瞬间。
    var stored =
        tx().run(
                UUID.fromString(tenantOf(ownerToken)),
                () ->
                    sql.fetchOne(
                        "select valid_from,valid_until from tenant_hierarchy_grant where id=?",
                        id));
    assertThat(stored.get("valid_from", OffsetDateTime.class).toInstant())
        .isEqualTo(from.toInstant());
    assertThat(stored.get("valid_until", OffsetDateTime.class).toInstant())
        .isEqualTo(until.toInstant());
  }

  /**
   * 过期授权不再放行，且 reason 明确是 EXPIRED。
   *
   * <p>status 仍是 ACTIVE（没人撤销它），只有 effectiveStatus 变成 EXPIRED。 这正是列表里两个状态并存的原因：只看 status 会以为权限还在。
   */
  @Test
  void expiredGrantIsDeniedWithExpiredReason() throws Exception {
    String ownerToken = login(), tag = suffix();
    String childToken = loginUser(childTenant(ownerToken, tag));
    String childTenantId = tenantOf(childToken);
    var now = OffsetDateTime.now();

    UUID id =
        grant(
            ownerToken,
            childTenantId,
            List.of("catalog.spu"),
            "READ",
            now.minusDays(2),
            now.minusDays(1));

    JsonNode row = grantRow(ownerToken, id);
    assertThat(row.get("status").asText()).isEqualTo("ACTIVE");
    assertThat(row.get("effectiveStatus").asText()).isEqualTo("EXPIRED");

    JsonNode denied = check(ownerToken, childTenantId, "catalog.spu", false);
    assertThat(denied.get("allowed").asBoolean()).isFalse();
    assertThat(denied.get("reason").asText()).isEqualTo("EXPIRED");
    assertThat(denied.get("grantId").asText()).isEqualTo(id.toString());
  }

  /** 尚未生效的授权同样不放行（PENDING），理由与过期区分开。 */
  @Test
  void notYetValidGrantIsDenied() throws Exception {
    String ownerToken = login(), tag = suffix();
    String childToken = loginUser(childTenant(ownerToken, tag));
    String childTenantId = tenantOf(childToken);
    var now = OffsetDateTime.now();

    UUID id =
        grant(
            ownerToken,
            childTenantId,
            List.of("catalog.spu"),
            "READ",
            now.plusDays(1),
            now.plusDays(2));

    assertThat(grantRow(ownerToken, id).get("effectiveStatus").asText()).isEqualTo("PENDING");
    assertThat(check(ownerToken, childTenantId, "catalog.spu", false).get("reason").asText())
        .isEqualTo("NOT_YET_VALID");
  }

  // ------------------------------------------------------------------
  // 撤销
  // ------------------------------------------------------------------

  /**
   * 撤销是终态：立刻断掉访问，且不能再改回来。
   *
   * <p>撤销后仍可「改」必须是拒绝的——否则撤权就不是撤权，只是暂时收起来。
   */
  @Test
  void revokeIsTerminalAndClosesAccess() throws Exception {
    String ownerToken = login(), tag = suffix();
    String childToken = loginUser(childTenant(ownerToken, tag));
    String childTenantId = tenantOf(childToken);
    var now = OffsetDateTime.now();
    UUID id =
        grant(ownerToken, childTenantId, List.of("catalog.spu"), "WRITE", now.minusHours(1), null);

    request(ownerToken, "POST", "/identity/hierarchy-authorizations/" + id + "/revoke", null, 200);

    JsonNode denied = check(ownerToken, childTenantId, "catalog.spu", true);
    assertThat(denied.get("allowed").asBoolean()).isFalse();
    assertThat(denied.get("reason").asText()).isEqualTo("REVOKED");

    JsonNode row = grantRow(ownerToken, id);
    assertThat(row.get("status").asText()).isEqualTo("REVOKED");
    assertThat(row.get("effectiveStatus").asText()).isEqualTo("REVOKED");
    assertThat(row.get("revokedAt").isNull()).isFalse();

    // 终态：不能再改回 ACTIVE，重复撤销也必须失败。
    var edit =
        Map.<String, Object>of(
            "domains", List.of("catalog.spu"),
            "accessLevel", "WRITE",
            "validFrom", now.minusHours(1).toString());
    request(ownerToken, "PUT", "/identity/hierarchy-authorizations/" + id, edit, 403);
    request(ownerToken, "POST", "/identity/hierarchy-authorizations/" + id + "/revoke", null, 403);
  }

  // ------------------------------------------------------------------
  // 服务端真校验：谁能配、能配给谁
  // ------------------------------------------------------------------

  /**
   * 只能授权给真后代，不能授权给自己。
   *
   * <p>「能管子租户」不等于「能对任意租户开口子」——没有后代关系的租户一律拒绝， 这里用「授权给本租户自己」验证最省事：自己必然不是 depth>0 的后代。
   */
  @Test
  void grantTargetMustBeRealDescendant() throws Exception {
    String ownerToken = login();
    request(
        ownerToken,
        "POST",
        "/identity/hierarchy-authorizations",
        Map.of(
            "childTenantId", tenantOf(ownerToken),
            "domains", List.of("catalog.spu"),
            "accessLevel", "READ",
            "validFrom", OffsetDateTime.now().toString()),
        403);
  }

  /** 有效期倒挂与格式非法的数据域都要被服务端拒掉，不靠前端拦。 */
  @Test
  void invalidScopeIsRejected() throws Exception {
    String ownerToken = login(), tag = suffix();
    String childToken = loginUser(childTenant(ownerToken, tag));
    String childTenantId = tenantOf(childToken);
    var now = OffsetDateTime.now();

    // 有效期倒挂
    request(
        ownerToken,
        "POST",
        "/identity/hierarchy-authorizations",
        Map.of(
            "childTenantId", childTenantId,
            "domains", List.of("catalog.spu"),
            "accessLevel", "READ",
            "validFrom", now.toString(),
            "validUntil", now.minusDays(1).toString()),
        400);
    // 域格式非法
    request(
        ownerToken,
        "POST",
        "/identity/hierarchy-authorizations",
        Map.of(
            "childTenantId",
            childTenantId,
            "domains",
            List.of("Not A Domain"),
            "accessLevel",
            "READ",
            "validFrom",
            now.toString()),
        400);
    // 域列表为空：授权必须说清楚开放哪些数据域，不接受「全开」的隐式写法
    request(
        ownerToken,
        "POST",
        "/identity/hierarchy-authorizations",
        Map.of(
            "childTenantId",
            childTenantId,
            "domains",
            List.of(),
            "accessLevel",
            "READ",
            "validFrom",
            now.toString()),
        400);
    // 级别非法（枚举外的值连 Bean Validation 都过不去）
    request(
        ownerToken,
        "POST",
        "/identity/hierarchy-authorizations",
        Map.of(
            "childTenantId",
            childTenantId,
            "domains",
            List.of("catalog.spu"),
            "accessLevel",
            "ADMIN",
            "validFrom",
            now.toString()),
        400);
  }

  /**
   * 配置授权是所有者专属；没有层级角色的普通成员也拿不到跨租户访问。
   *
   * <p>后半句同样重要：**配了授权 ≠ 租户里每个人都能用**。谁有资格用这处开口， 仍由该租户自己的角色分配决定（ADR-0005：操作与数据范围必须在同一角色分配中满足）。
   */
  @Test
  void onlyOwnerConfiguresAndOnlyHierarchyRolesConsume() throws Exception {
    String ownerToken = login(), tag = suffix();
    String childToken = loginUser(childTenant(ownerToken, tag));
    String childTenantId = tenantOf(childToken);
    var now = OffsetDateTime.now();
    grant(
        ownerToken,
        childTenantId,
        List.of("catalog.spu"),
        "READ",
        now.minusHours(1),
        now.plusHours(1));

    // 一个不带任何层级权限的自定义角色。
    UUID roleId =
        UUID.fromString(
            request(
                    ownerToken,
                    "POST",
                    "/identity/roles",
                    Map.of(
                        "code",
                        "HZR_" + tag,
                        "name",
                        "层级授权测试角色",
                        "status",
                        "ACTIVE",
                        "permissions",
                        List.of("catalog.sku.read")),
                    200)
                .asText());
    request(
        ownerToken,
        "POST",
        "/identity/members",
        memberInput(
            "hz-member-" + tag,
            null,
            List.of(Map.of("roleId", roleId.toString(), "scopeMode", "ALL"))),
        200);

    String memberToken = loginUser("hz-member-" + tag);

    // 看不到授权配置：连读路径都要 tenant.hierarchy.read，所以是 403 而不是空列表。
    // 这与既有管理端点同口径（AdministrationController 对每个页面权限码都这样闸）。
    request(memberToken, "GET", "/identity/hierarchy-authorizations", null, 403);
    request(
        memberToken,
        "POST",
        "/identity/hierarchy-authorizations",
        Map.of(
            "childTenantId",
            childTenantId,
            "domains",
            List.of("catalog.spu"),
            "accessLevel",
            "READ",
            "validFrom",
            now.toString()),
        403);

    // 关键：授权已经配好、父子关系也是真的，但这个角色没有层级权限，判定照样拒绝。
    JsonNode denied = check(memberToken, childTenantId, "catalog.spu", false);
    assertThat(denied.get("allowed").asBoolean()).isFalse();
    assertThat(denied.get("reason").asText()).isEqualTo("ROLE_NOT_AUTHORIZED");
  }

  // ------------------------------------------------------------------
  // 多租户隔离三断言
  // ------------------------------------------------------------------

  /**
   * 两租户互不可见 + 伪造 tenant header 无效 + 连接池归还后不串租户。
   *
   * <p>三条压在一个用例里，因为它们本质是同一件事的三面：授权表按 tenant_id 隔离， 租户来自已认证成员关系而不是请求头。底座把连接池压到 1，父 → 子 → 父 三次请求
   * 必然复用同一条物理连接，父的授权行如果泄漏到连接上，子那一次就会看到它。
   *
   * <p>{@link IdentityIntegrationTestBase#request} 每次都带随机 {@code X-Tenant-Id}，
   * 所以这一串断言同时证明了伪造头不产生任何影响。
   */
  @Test
  void tenantsStayIsolatedAcrossPooledConnections() throws Exception {
    String ownerToken = login(), tag = suffix();
    String childOwner = childTenant(ownerToken, tag);
    String childToken = loginUser(childOwner);
    String childTenantId = tenantOf(childToken);
    var now = OffsetDateTime.now();
    UUID id =
        grant(
            ownerToken,
            childTenantId,
            List.of("catalog.spu"),
            "READ",
            now.minusHours(1),
            now.plusHours(1));

    // 父：看得到自己给这个子租户配的那一条。
    assertThat(grantsFor(ownerToken, childTenantId)).hasSize(1);
    assertThat(grantsFor(ownerToken, childTenantId).get(0).get("id").asText())
        .isEqualTo(id.toString());

    // 子：一条都看不到（同一物理连接，上一步刚写过父租户上下文）。
    assertThat(grantsFor(childToken, childTenantId)).isEmpty();

    // 再回父：仍然只有自己那一条，没有把子租户的上下文带回来。
    assertThat(grantsFor(ownerToken, childTenantId)).hasSize(1);
    assertThat(grantsFor(ownerToken, childTenantId).get(0).get("childTenantId").asText())
        .isEqualTo(childTenantId);

    // 运行时角色绕不过 RLS：不带租户上下文直接查表，最多只能看到 app.current_tenant 那一个租户的行，
    // 换个租户上下文就换成另一个集合，两边不会同时可见。
    var asParent =
        tx()
            .run(
                UUID.fromString(tenantOf(ownerToken)),
                () -> sql.fetch("select id from tenant_hierarchy_grant"))
            .stream()
            .map(r -> r.get("id", UUID.class))
            .toList();
    var asChild =
        tx()
            .run(
                UUID.fromString(childTenantId),
                () -> sql.fetch("select id from tenant_hierarchy_grant"))
            .stream()
            .map(r -> r.get("id", UUID.class))
            .toList();
    assertThat(asParent).contains(id);
    assertThat(asChild).doesNotContain(id).isEmpty();
  }

  // ------------------------------------------------------------------
  // 审计
  // ------------------------------------------------------------------

  /** 每次配置变更都要留审计与 outbox：事后要能回答「谁在什么时候把哪个域开放给了哪个子租户」。 */
  @Test
  void changesAreAuditedAndEmitted() throws Exception {
    String ownerToken = login(), tag = suffix();
    String childToken = loginUser(childTenant(ownerToken, tag));
    String childTenantId = tenantOf(childToken);
    var now = OffsetDateTime.now();

    UUID id =
        grant(
            ownerToken,
            childTenantId,
            List.of("catalog.spu"),
            "READ",
            now.minusHours(1),
            now.plusHours(1));
    request(ownerToken, "POST", "/identity/hierarchy-authorizations/" + id + "/revoke", null, 200);

    var tenantId = UUID.fromString(tenantOf(ownerToken));
    var actions =
        tx()
            .run(
                tenantId,
                () ->
                    sql.fetch(
                        "select action from identity_audit where resource_type='tenant.hierarchy' order by occurred_at"))
            .stream()
            .map(r -> r.get("action", String.class))
            .toList();
    assertThat(actions).contains("HierarchyGrantCreated", "HierarchyGrantRevoked");

    // outbox 不在这里断言：identity_app 对 identity_outbox 只有 INSERT 权限（V3 的既有设计，
    // 事件表不开放给运行时角色读）。审计与 outbox 在同一事务里写入，
    // 下面断言到审计行即证明那次 outbox 插入也执行了——否则事务会整体回滚。
    //
    // 审计快照里要能还原「授权给了哪个子租户、哪些域、到什么时候」——只有一句
    // 「改过授权」等于没法追责。
    var changes =
        tx().run(
                tenantId,
                () ->
                    sql.fetchOne(
                        "select changes from identity_audit where resource_type='tenant.hierarchy'"
                            + " and action='HierarchyGrantRevoked' and resource_id=?",
                        id))
            .get("changes", String.class);
    assertThat(changes).contains(childTenantId).contains("catalog.spu").contains("validUntil");
  }
}
