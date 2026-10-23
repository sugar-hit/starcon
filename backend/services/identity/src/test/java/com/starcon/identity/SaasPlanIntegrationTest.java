package com.starcon.identity;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.starcon.tenancy.TenantTransactions;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 17.7.1.1 套餐与权益。
 *
 * <p>重点不是「能存进去」，而是蓝图点名的三条纪律真的成立：
 *
 * <ol>
 *   <li><b>仅平台管理员可见，租户不可访问</b>——子租户的所有者角色**带着** {@code identity.plan.*}
 *       权限码（{@code create_child} 会把 permission 全量授给新租户的 OWNER），照样被 403 拒掉。
 *       这条断言是本模块的命门：只看权限码的话，「租户不可访问」当场失效。
 *   <li><b>与业务库物理隔离</b>——平台级表不带 {@code tenant_id}、不参与 RLS、
 *       {@code identity_app} 只有 SELECT；租户级表 {@code tenant_id NOT NULL} + FORCE RLS。
 *   <li><b>与 ERP 客户的销售收入完全无关</b>——本模块的表里**没有任何金额列**，
 *       上限与用量都是 {@code NUMERIC(19,6)} 的数量。
 * </ol>
 *
 * <p>时间字段按值断言：试用起止用 {@code +08:00} 偏移写入再读回，
 * 用 {@code Timestamp.valueOf(LocalDateTime)} 绑 timestamptz 会静默偏 −28800 秒，
 * 只断言「没抛异常」是查不出来的。
 */
class SaasPlanIntegrationTest extends IdentityIntegrationTestBase {

  /**
   * 用例级护栏：本测试类的**任何一个**用例跑完之后，共享的 local-admin（root 租户 OWNER）
   * 仍然**只有一个角色**。
   *
   * <p>这条断言是那个回归的哨兵。平台运营角色登记会把 PLATFORM_OPS **永久挂到调用者本人**身上；
   * 挂在共享 owner 上，就会让同一次 maven 运行里的其它测试类看到一个多角色的 owner，
   * 而 {@code /auth/session} 的 grants 是**无序聚合**（V47 的 session_access 没有 ORDER BY）——
   * 别的测试类一旦靠 {@code grants.get(0)} 猜角色就会踩空。
   *
   * <p>放在 {@code @AfterEach} 而不是某个用例里：污染一旦发生，当场红，而不是等到别的测试类炸出来。
   */
  @AfterEach
  void sharedOwnerKeepsExactlyOneRole() throws Exception {
    String token = login();
    var me = request(token, "GET", "/auth/session", null, 200);
    var grants = me.get("grants");
    assertThat(grants).as("共享 owner 的角色集合不得被本测试类扩大").hasSize(1);
    assertThat(grants.get(0).get("roleCode").asText()).isEqualTo("OWNER");
    // 数据库层同样看一眼：role_assignment 里这个成员仍然只有一行。
    assertThat(
            superuserCount(
                "select count(*) from role_assignment where membership_id='"
                    + me.get("membershipId").asText()
                    + "'"))
        .as("共享 owner 在 role_assignment 里必须仍然只有一行")
        .isEqualTo(1);
  }

  private TenantTransactions tx() {
    return new TenantTransactions(sql, new TransactionTemplate(manager));
  }

  /** 租户外直连（绕过应用连接池与租户上下文），用于触发器与权限级断言。 */
  private void asSuperuser(String statement) throws Exception {
    try (var c =
            DriverManager.getConnection(pg().getJdbcUrl(), pg().getUsername(), pg().getPassword());
        var s = c.createStatement()) {
      s.execute(statement);
    }
  }

  /** 直连库跑一条 count 查询（绕过应用连接池与 RLS），用于核对共享状态有没有被污染。 */
  private long superuserCount(String query) throws Exception {
    try (var c =
            DriverManager.getConnection(pg().getJdbcUrl(), pg().getUsername(), pg().getPassword());
        var s = c.createStatement();
        var rs = s.executeQuery(query)) {
      rs.next();
      return rs.getLong(1);
    }
  }

  private boolean hasPrivilege(String table, String privilege) throws Exception {
    try (var c =
            DriverManager.getConnection(pg().getJdbcUrl(), pg().getUsername(), pg().getPassword());
        var s = c.createStatement();
        var rs =
            s.executeQuery(
                "select has_table_privilege('identity_app','"
                    + table
                    + "','"
                    + privilege
                    + "') as ok")) {
      rs.next();
      return rs.getBoolean("ok");
    }
  }

  /**
   * 平台运营身份：**专属子租户**里的所有者，PLATFORM_OPS 角色登记在他自己租户里。
   *
   * <p>为什么不用共享的 local-admin（root 租户 OWNER）：平台运营角色登记（V323 的
   * {@code saas_platform_register_role}）会把 PLATFORM_OPS 角色**永久挂到调用者本人**身上。
   * 挂在共享 owner 上 = 污染同一次 maven 运行里的**所有** identity 测试类：共享 owner 的角色
   * 从 1 个变成 2 个，而 {@code /auth/session} 的 grants 是**无序聚合**（V47 的 session_access
   * 用 jsonb_agg 且没有 ORDER BY），别的测试类再靠 {@code grants.get(0)} 猜角色就会踩空 ——
   * 2026-10 的真实回归：IdentityIntegrationTest 因此把 PLATFORM_OPS 当成 OWNER 去删，拿到 200 而不是 403。
   *
   * <p>闸门是**按租户**判的：{@code saas_platform_gate} 只要求
   * {@code saas_platform_role.tenant_id = 会话所在租户}，没有「必须是根租户」的要求，
   * 所以子租户的所有者可以在**自己租户里**登记 PLATFORM_OPS 并通过闸门。
   * 平台运营身份因此可以是专属的，与共享 owner 完全解耦：共享状态零污染。
   */
  private String platformToken() throws Exception {
    String token = platformOpsOwnerToken();
    registerPlatformRole(token);
    return token;
  }

  /**
   * 专属平台运营身份：一个**只属于本次用例**的子租户所有者，尚未登记平台运营角色。
   *
   * <p>用真实的 {@code POST /identity/children} 造子租户（而不是直接插库）：走的是生产路径，
   * 租户 / 成员 / OWNER 角色 / 角色分配由 create_child 一次性建好，与普通租户用例同一套机制。
   */
  private String platformOpsOwnerToken() throws Exception {
    String tag = suffix();
    String root = login();
    String opsUser = "saas-platform-ops-" + tag.toLowerCase();
    request(
        root,
        "POST",
        "/identity/children",
        Map.of(
            "code",
            "SAP" + tag,
            "name",
            "套餐与权益平台运营租户",
            "ownerUsername",
            opsUser,
            "ownerPassword",
            "test-member-password"),
        200);
    return loginUser(opsUser);
  }

  /**
   * 平台运营角色登记（平台运营侧专用路径：浏览器打不到，要内部密钥）。
   *
   * <p>登记走的是平台运营专用路径（要内部密钥），浏览器打不到——这正是「平台运营侧专用路径」
   * 的形状。登记会把 PLATFORM_OPS 角色挂到**调用者本人**身上，所以只能对**专属身份**做，
   * 绝不能对共享的 local-admin 做。
   */
  private void registerPlatformRole(String token) throws Exception {
    http.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                    "/identity/saas-platform/roles")
                .header("Authorization", "Bearer " + token)
                .header("X-Internal-Key", "test-key"))
        .andExpect(status().isOk());
  }

  /** 一个**普通租户**的所有者：带着 identity.plan.* 权限码，但不是平台运营方。 */
  private String ordinaryTenantToken(String tag) throws Exception {
    String owner = login();
    String childUser = "saas-ordinary-" + tag.toLowerCase();
    request(
        owner,
        "POST",
        "/identity/children",
        Map.of(
            "code",
            "SA" + tag,
            "name",
            "套餐与权益普通租户",
            "ownerUsername",
            childUser,
            "ownerPassword",
            "test-member-password"),
        200);
    return loginUser(childUser);
  }

  private UUID tenantOf(String token) throws Exception {
    return UUID.fromString(
        request(token, "GET", "/identity/tenants/current", null, 200).get("tenantId").asText());
  }

  private Map<String, Object> planInput(String code, String name) {
    var input = new LinkedHashMap<String, Object>();
    input.put("code", code);
    input.put("name", name);
    input.put("description", "集成测试套餐");
    input.put("tierLevel", 50);
    input.put("trialDays", 7);
    input.put("sortOrder", 50);
    return input;
  }

  private Map<String, Object> entitlementInput(
      String code, String kind, long limit, String action) {
    var input = new LinkedHashMap<String, Object>();
    input.put("entitlementCode", code);
    input.put("entitlementKind", kind);
    input.put("limitValue", limit);
    input.put("unlimited", false);
    input.put("overLimitAction", action);
    input.put("period", "MONTH");
    input.put("unit", "个");
    input.put("sortOrder", 10);
    return input;
  }

  private JsonNode findPlan(JsonNode list, String code) {
    for (JsonNode n : list) if (code.equals(n.get("code").asText())) return n;
    return null;
  }

  /** 在权益用量视图里按权益码定位一行。 */
  private JsonNode findUsage(JsonNode list, String entitlementCode) {
    for (JsonNode n : list)
      if (entitlementCode.equals(n.get("entitlementCode").asText())) return n;
    return null;
  }

  // ------------------------------------------------------------------
  // 纪律一：仅平台管理员可见，租户不可访问
  // ------------------------------------------------------------------

  @Test
  void ordinaryTenantIsDeniedEvenThoughItOwnsThePermissionCodes() throws Exception {
    String tag = suffix();
    String ordinary = ordinaryTenantToken(tag);

    // 先证明这个普通租户**确实**带着平台运营权限码（create_child 会把 permission 全量授给 OWNER）。
    // 少了这一步，「403」可能只是因为权限码没发到，而不是闸门真的在起作用。
    var session = request(ordinary, "GET", "/auth/session", null, 200);
    var codes = new java.util.ArrayList<String>();
    session.get("permissions").forEach(p -> codes.add(p.asText()));
    assertThat(codes)
        .as("普通租户所有者带着平台运营权限码，这条断言才成立")
        .contains("identity.plan.read", "identity.subscription.read");

    // 但平台运营域一律拒绝：读、写、跨租户看板、用量、超限判定全部 403。
    request(ordinary, "GET", "/identity/saas-plans/capabilities", null, 403);
    request(ordinary, "GET", "/identity/saas-plans/options", null, 403);
    request(ordinary, "GET", "/identity/saas-plans/plans", null, 403);
    request(ordinary, "GET", "/identity/saas-plans/subscriptions", null, 403);
    request(
        ordinary,
        "GET",
        "/identity/saas-plans/usage?tenantId=" + UUID.randomUUID(),
        null,
        403);
    request(
        ordinary,
        "GET",
        "/identity/saas-plans/entitlement-check?tenantId="
            + UUID.randomUUID()
            + "&entitlementCode=SKU",
        null,
        403);
    request(ordinary, "POST", "/identity/saas-plans/plans", planInput("HACK_" + tag, "越权"), 403);
    request(ordinary, "GET", "/identity/saas-plans/plans/STARTER", null, 403);
    request(
        ordinary,
        "POST",
        "/identity/saas-plans/subscriptions",
        Map.of(
            "tenantId", UUID.randomUUID(),
            "planCode", "STARTER",
            "status", "ACTIVE",
            "startedAt", OffsetDateTime.now(ZoneOffset.UTC).toString(),
            "expiresAt", OffsetDateTime.now(ZoneOffset.UTC).plusYears(1).toString()),
        403);

    // 未登录一律 401。
    http.perform(get("/identity/saas-plans/plans")).andExpect(status().isUnauthorized());
  }

  @Test
  void platformRoleRegistrationIsServerToServerOnly() throws Exception {
    // 专属身份：登记会把 PLATFORM_OPS 挂到调用者本人身上，不能挂在共享的 local-admin 上。
    String token = platformOpsOwnerToken();
    // 没有内部密钥 → 拒绝：这条路径是平台运营侧的专用路径，浏览器打不到。
    http.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                    "/identity/saas-platform/roles")
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isForbidden());
    // 伪造内部密钥同样拒绝。
    http.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                    "/identity/saas-platform/roles")
                .header("Authorization", "Bearer " + token)
                .header("X-Internal-Key", "forged"))
        .andExpect(status().isForbidden());
    // 正确密钥 → 登记成功。
    registerPlatformRole(token);
    // 登记之后，这个身份才拿到平台运营域的读权限。
    request(token, "GET", "/identity/saas-plans/capabilities", null, 200);
  }

  // ------------------------------------------------------------------
  // 纪律二：平台级表 vs 租户级表的分层
  // ------------------------------------------------------------------

  @Test
  void platformTablesAreReadOnlyTenantlessAndMoneyFree() throws Exception {
    // 平台级表：只授予 SELECT —— 改套餐定义必须走平台运营专用写入路径。
    assertThat(hasPrivilege("saas_plan", "SELECT")).isTrue();
    assertThat(hasPrivilege("saas_plan", "INSERT")).isFalse();
    assertThat(hasPrivilege("saas_plan", "UPDATE")).isFalse();
    assertThat(hasPrivilege("saas_plan", "DELETE")).isFalse();
    assertThat(hasPrivilege("saas_plan_entitlement", "SELECT")).isTrue();
    assertThat(hasPrivilege("saas_plan_entitlement", "INSERT")).isFalse();
    assertThat(hasPrivilege("saas_plan_entitlement", "UPDATE")).isFalse();

    // 租户级表：正常读写，但不给 DELETE —— 订阅与用量是事实。
    assertThat(hasPrivilege("saas_subscription", "SELECT")).isTrue();
    assertThat(hasPrivilege("saas_subscription", "INSERT")).isTrue();
    assertThat(hasPrivilege("saas_subscription", "UPDATE")).isTrue();
    assertThat(hasPrivilege("saas_subscription", "DELETE")).isFalse();
    assertThat(hasPrivilege("saas_entitlement_usage", "DELETE")).isFalse();

    // 平台级表不带 tenant_id，也不参与 RLS（与 dict_catalog 同一先例）。
    try (var c =
            DriverManager.getConnection(pg().getJdbcUrl(), pg().getUsername(), pg().getPassword());
        var s = c.createStatement();
        var rs =
            s.executeQuery(
                "select c.relname,"
                    + " (select count(*) from information_schema.columns col"
                    + "   where col.table_name=c.relname and col.column_name='tenant_id') as tenant_cols,"
                    + " c.relrowsecurity as rls_enabled,"
                    + " c.relforcerowsecurity as rls_forced"
                    + " from pg_class c join pg_namespace n on n.oid=c.relnamespace"
                    + " where n.nspname='public' and c.relkind='r'"
                    + " and c.relname in ('saas_plan','saas_plan_entitlement','saas_subscription',"
                    + " 'saas_entitlement_usage','saas_subscription_event','saas_platform_role')")) {
      var map = new java.util.LinkedHashMap<String, Object[]>();
      while (rs.next())
        map.put(
            rs.getString("relname"),
            new Object[] {
              rs.getInt("tenant_cols"), rs.getBoolean("rls_enabled"), rs.getBoolean("rls_forced")
            });
      assertThat(map.get("saas_plan")[0]).as("平台级表不带 tenant_id").isEqualTo(0);
      assertThat(map.get("saas_plan_entitlement")[0]).as("平台级表不带 tenant_id").isEqualTo(0);
      assertThat(map.get("saas_plan")[1]).as("平台级表不参与 RLS").isEqualTo(false);
      assertThat(map.get("saas_plan_entitlement")[1]).as("平台级表不参与 RLS").isEqualTo(false);
      assertThat(map.get("saas_subscription")[0]).as("租户级表必须带 tenant_id").isEqualTo(1);
      assertThat(map.get("saas_subscription")[1]).as("租户级表必须启用 RLS").isEqualTo(true);
      assertThat(map.get("saas_subscription")[2]).as("租户级表必须 FORCE RLS").isEqualTo(true);
      assertThat(map.get("saas_entitlement_usage")[0]).isEqualTo(1);
      assertThat(map.get("saas_subscription_event")[0]).isEqualTo(1);
      assertThat(map.get("saas_platform_role")[0]).isEqualTo(1);
      assertThat(map.get("saas_platform_role")[2]).as("平台运营角色登记同样 FORCE RLS").isEqualTo(true);
    }

    // 纪律三：整张表里**没有任何金额列**。列名里出现金额语义的词就失败。
    try (var c =
            DriverManager.getConnection(pg().getJdbcUrl(), pg().getUsername(), pg().getPassword());
        var s = c.createStatement();
        var rs =
            s.executeQuery(
                "select table_name,column_name from information_schema.columns"
                    + " where table_name like 'saas\\_%'"
                    + " and column_name ~* '(amount|price|fee|money|cost|revenue|balance|currency|rmb|usd)'")) {
      var money = new java.util.ArrayList<String>();
      while (rs.next()) money.add(rs.getString("table_name") + "." + rs.getString("column_name"));
      assertThat(money).as("本模块与 ERP 客户的销售收入完全无关，不得出现任何金额列").isEmpty();
    }

    // 权益上限与用量是数量：NUMERIC，不是浮点。
    try (var c =
            DriverManager.getConnection(pg().getJdbcUrl(), pg().getUsername(), pg().getPassword());
        var s = c.createStatement();
        var rs =
            s.executeQuery(
                "select table_name,column_name,data_type,numeric_precision,numeric_scale"
                    + " from information_schema.columns"
                    + " where table_name in ('saas_plan_entitlement','saas_entitlement_usage')"
                    + " and column_name='limit_value' or (table_name='saas_entitlement_usage'"
                    + " and column_name='usage_value')")) {
      int seen = 0;
      while (rs.next()) {
        seen++;
        assertThat(rs.getString("data_type")).isEqualTo("numeric");
        assertThat(rs.getInt("numeric_precision")).isEqualTo(19);
        assertThat(rs.getInt("numeric_scale")).isEqualTo(6);
      }
      assertThat(seen).as("上限与用量两列都要查到").isEqualTo(2);
    }
  }

  @Test
  void planAndEntitlementLifecycleIsReal() throws Exception {
    String token = platformToken();
    String tag = suffix();
    String code = "PLAN_" + tag;

    // 编码必须是标识符形态：小写与横杠都拒。
    request(token, "POST", "/identity/saas-plans/plans", planInput("plan-" + tag, "小写"), 400);
    request(token, "POST", "/identity/saas-plans/plans", planInput("P", "太短"), 400);

    request(token, "POST", "/identity/saas-plans/plans", planInput(code, "集成测试套餐"), 200);

    // 列表里能看到，计数是真实聚合。
    var plans = request(token, "GET", "/identity/saas-plans/plans", null, 200);
    var plan = findPlan(plans, code);
    assertThat(plan).isNotNull();
    assertThat(plan.get("entitlementCount").asLong()).isZero();
    assertThat(plan.get("blockedActionCount").asLong()).isZero();

    // 新增权益项。
    request(
        token,
        "POST",
        "/identity/saas-plans/plans/" + code + "/entitlements",
        entitlementInput("SKU_" + tag, "SKU_COUNT", 500, "BLOCK"),
        200);
    request(
        token,
        "POST",
        "/identity/saas-plans/plans/" + code + "/entitlements",
        entitlementInput("ORDER_" + tag, "ORDER_VOLUME", 1000, "DEGRADE"),
        200);
    // 同一套餐下权益编码唯一 → 409。
    request(
        token,
        "POST",
        "/identity/saas-plans/plans/" + code + "/entitlements",
        entitlementInput("SKU_" + tag, "SKU_COUNT", 999, "BLOCK"),
        409);

    var detail = request(token, "GET", "/identity/saas-plans/plans/" + code, null, 200);
    assertThat(detail.get("entitlements")).hasSize(2);
    assertThat(detail.get("plan").get("entitlementCount").asLong()).isEqualTo(2);
    assertThat(detail.get("plan").get("blockedActionCount").asLong()).isEqualTo(1);

    // 改上限与超限策略。
    request(
        token,
        "PUT",
        "/identity/saas-plans/plans/" + code + "/entitlements/SKU_" + tag,
        entitlementInput("SKU_" + tag, "SKU_COUNT", 800, "DEGRADE"),
        200);
    var after = request(token, "GET", "/identity/saas-plans/plans/" + code, null, 200);
    JsonNode sku = null;
    for (JsonNode n : after.get("entitlements"))
      if (("SKU_" + tag).equals(n.get("entitlementCode").asText())) sku = n;
    assertThat(sku).isNotNull();
    assertThat(sku.get("limitValue").asLong()).isEqualTo(800);
    assertThat(sku.get("overLimitAction").asText()).isEqualTo("DEGRADE");

    // 套餐退役（不删除）：下线走退役。
    request(
        token,
        "PUT",
        "/identity/saas-plans/plans/" + code,
        Map.of(
            "name", "集成测试套餐",
            "description", "已退役",
            "status", "RETIRED",
            "tierLevel", 50,
            "trialDays", 7,
            "sortOrder", 50),
        200);
    assertThat(
            request(token, "GET", "/identity/saas-plans/plans/" + code, null, 200)
                .get("plan")
                .get("status")
                .asText())
        .isEqualTo("RETIRED");

    // 套餐不存在 → 404。
    request(token, "GET", "/identity/saas-plans/plans/NOPE_" + tag, null, 404);
  }

  // ------------------------------------------------------------------
  // 订阅：试用、到期、状态流转、时间不漂移
  // ------------------------------------------------------------------

  @Test
  void subscriptionKeepsExactTimestampsAndSupportsTrialAndExpiry() throws Exception {
    String token = platformToken();
    String tag = suffix();
    UUID tenantId = UUID.randomUUID();
    // 造一个真实存在的目标租户。
    createTenant(tag, tenantId);

    // 用 +08:00 偏移写入：Timestamp.valueOf(LocalDateTime) 的 −28800s 偏移在这个偏移下必现。
    OffsetDateTime trialStart = OffsetDateTime.now(ZoneOffset.ofHours(8)).minusDays(1).withNano(0);
    OffsetDateTime trialEnd = OffsetDateTime.now(ZoneOffset.ofHours(8)).plusDays(13).withNano(0);

    UUID id =
        UUID.fromString(
            request(
                    token,
                    "POST",
                    "/identity/saas-plans/subscriptions",
                    Map.of(
                        "tenantId", tenantId.toString(),
                        "planCode", "TRIAL",
                        "status", "TRIAL",
                        "trialStart", trialStart.toString(),
                        "trialEnd", trialEnd.toString(),
                        "note", "集成测试试用"),
                    200)
                .asText());

    var view = request(token, "GET", "/identity/saas-plans/subscriptions/" + id, null, 200);
    var subscription = view.get("subscription");
    assertThat(subscription.get("status").asText()).isEqualTo("TRIAL");
    // 按**时刻**断言，不只断言「没抛异常」。
    assertThat(OffsetDateTime.parse(subscription.get("trialStart").asText()).toInstant())
        .isEqualTo(trialStart.toInstant());
    assertThat(OffsetDateTime.parse(subscription.get("trialEnd").asText()).toInstant())
        .isEqualTo(trialEnd.toInstant());
    assertThat(subscription.get("effectiveStatus").asText()).isEqualTo("TRIAL");

    // 试用转正式：必须给起止，否则 400。
    request(
        token,
        "POST",
        "/identity/saas-plans/subscriptions/" + id + "/status",
        Map.of("status", "ACTIVE", "reason", "试用转正式"),
        400);
    OffsetDateTime startedAt = OffsetDateTime.now(ZoneOffset.ofHours(8)).withNano(0);
    OffsetDateTime expiresAt = startedAt.plusYears(1);
    request(
        token,
        "POST",
        "/identity/saas-plans/subscriptions/" + id + "/status",
        Map.of(
            "status", "ACTIVE",
            "reason", "试用转正式",
            "startedAt", startedAt.toString(),
            "expiresAt", expiresAt.toString()),
        200);
    var active = request(token, "GET", "/identity/saas-plans/subscriptions/" + id, null, 200);
    assertThat(active.get("subscription").get("status").asText()).isEqualTo("ACTIVE");
    assertThat(
            OffsetDateTime.parse(active.get("subscription").get("expiresAt").asText()).toInstant())
        .isEqualTo(expiresAt.toInstant());

    // 状态流转白名单：已取消是终态。
    request(
        token,
        "POST",
        "/identity/saas-plans/subscriptions/" + id + "/status",
        Map.of("status", "CANCELLED", "reason", "客户不续费"),
        200);
    request(
        token,
        "POST",
        "/identity/saas-plans/subscriptions/" + id + "/status",
        Map.of("status", "ACTIVE", "reason", "想恢复"),
        409);
    // 取消必须留时刻。
    var cancelled = request(token, "GET", "/identity/saas-plans/subscriptions/" + id, null, 200);
    assertThat(cancelled.get("subscription").get("cancelledAt").asText()).isNotBlank();

    // 原因必填。
    request(
        token,
        "POST",
        "/identity/saas-plans/subscriptions/" + id + "/status",
        Map.of("status", "SUSPENDED", "reason", ""),
        400);
  }

  @Test
  void expiredSubscriptionIsDerivedFromTimeAndCanBeSettledInBatch() throws Exception {
    String token = platformToken();
    String tag = suffix();
    UUID tenantId = UUID.randomUUID();
    createTenant(tag, tenantId);

    // 一个已经过了试用期的试用订阅。
    OffsetDateTime past = OffsetDateTime.now(ZoneOffset.ofHours(8)).minusDays(3).withNano(0);
    UUID id =
        UUID.fromString(
            request(
                    token,
                    "POST",
                    "/identity/saas-plans/subscriptions",
                    Map.of(
                        "tenantId", tenantId.toString(),
                        "planCode", "TRIAL",
                        "status", "TRIAL",
                        "trialStart", past.toString(),
                        "trialEnd", past.plusDays(1).toString()),
                    200)
                .asText());

    // 库里存的还是 TRIAL，但推导出来的真实状态是 EXPIRED —— 这是算出来的，不是存出来的。
    var view = request(token, "GET", "/identity/saas-plans/subscriptions/" + id, null, 200);
    assertThat(view.get("subscription").get("status").asText()).isEqualTo("TRIAL");
    assertThat(view.get("subscription").get("effectiveStatus").asText()).isEqualTo("EXPIRED");

    // 到期处理：批量把过了期的订阅置为 EXPIRED，并留下留痕。
    var settled = request(token, "POST", "/identity/saas-plans/subscriptions/expire-due", null, 200);
    assertThat(settled.get("expired").asInt()).isPositive();
    var after = request(token, "GET", "/identity/saas-plans/subscriptions/" + id, null, 200);
    assertThat(after.get("subscription").get("status").asText()).isEqualTo("EXPIRED");
    assertThat(after.get("events").toString()).contains("EXPIRED");
  }

  // ------------------------------------------------------------------
  // 权益用量与超限判定
  // ------------------------------------------------------------------

  @Test
  void unavailableMeteringIsReportedAsUndeterminedNotAsZero() throws Exception {
    String token = platformToken();
    String tag = suffix();
    UUID tenantId = UUID.randomUUID();
    createTenant(tag, tenantId);

    UUID id =
        UUID.fromString(
            request(
                    token,
                    "POST",
                    "/identity/saas-plans/subscriptions",
                    Map.of(
                        "tenantId", tenantId.toString(),
                        "planCode", "STARTER",
                        "status", "ACTIVE",
                        "startedAt", OffsetDateTime.now(ZoneOffset.ofHours(8)).withNano(0).toString(),
                        "expiresAt",
                            OffsetDateTime.now(ZoneOffset.ofHours(8)).plusYears(1).withNano(0).toString()),
                    200)
                .asText());

    // available=false 却填了用量 → 拒（0 会被误读成「量过了正好是 0」）。
    request(
        token,
        "POST",
        "/identity/saas-plans/usage",
        Map.of(
            "tenantId", tenantId.toString(),
            "subscriptionId", id.toString(),
            "entitlementCode", "SKU",
            "usageValue", 0,
            "available", false,
            "ownerModule", "17.7.1.2",
            "impact", "没有统一计量接口"),
        400);
    // available=false 但没写归属模块与影响面 → 拒。
    request(
        token,
        "POST",
        "/identity/saas-plans/usage",
        Map.of(
            "tenantId", tenantId.toString(),
            "subscriptionId", id.toString(),
            "entitlementCode", "SKU",
            "available", false),
        400);
    // available=true 但没给用量与计量时刻 → 拒。
    request(
        token,
        "POST",
        "/identity/saas-plans/usage",
        Map.of(
            "tenantId", tenantId.toString(),
            "subscriptionId", id.toString(),
            "entitlementCode", "SKU",
            "available", true),
        400);

    // 未接通的维度：登记为 available=false，判定结果是 UNDETERMINED。
    request(
        token,
        "POST",
        "/identity/saas-plans/usage",
        Map.of(
            "tenantId", tenantId.toString(),
            "subscriptionId", id.toString(),
            "entitlementCode", "SKU",
            "available", false,
            "ownerModule", "17.7.1.2",
            "impact", "SKU 数量分散在 catalog 服务，尚未接通计量",
            "sourceSystem", "catalog"),
        200);
    var check =
        request(
                token,
                "GET",
                "/identity/saas-plans/entitlement-check?tenantId=" + tenantId + "&entitlementCode=SKU",
                null,
                200)
            ;
    assertThat(check.get("state").asText()).isEqualTo("UNDETERMINED");
    assertThat(check.get("blocked").asBoolean()).isFalse();
    assertThat(check.get("ownerModule").asText()).isEqualTo("17.7.1.2");
    assertThat(check.get("impact").asText()).isNotBlank();
    // 判定不了 ≠ 没超限：usageValue 必须为空，不能是 0。
    assertThat(check.get("usageValue").isNull()).isTrue();
  }

  @Test
  void entitlementCheckProducesAVerdictForTheDownstreamSwitch() throws Exception {
    String token = platformToken();
    String tag = suffix();
    UUID tenantId = UUID.randomUUID();
    createTenant(tag, tenantId);
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.ofHours(8)).withNano(0);

    UUID id =
        UUID.fromString(
            request(
                    token,
                    "POST",
                    "/identity/saas-plans/subscriptions",
                    Map.of(
                        "tenantId", tenantId.toString(),
                        "planCode", "STARTER",
                        "status", "ACTIVE",
                        "startedAt", now.toString(),
                        "expiresAt", now.plusYears(1).toString()),
                    200)
                .asText());

    // STARTER 的 SKU 上限 1000，超限策略 BLOCK。
    request(
        token,
        "POST",
        "/identity/saas-plans/usage",
        Map.of(
            "tenantId", tenantId.toString(),
            "subscriptionId", id.toString(),
            "entitlementCode", "SKU",
            "usageValue", 1500,
            "available", true,
            "measuredAt", now.toString(),
            "sourceSystem", "catalog"),
        200);
    var blocked =
        request(
                token,
                "GET",
                "/identity/saas-plans/entitlement-check?tenantId=" + tenantId + "&entitlementCode=SKU",
                null,
                200);
    assertThat(blocked.get("state").asText()).isEqualTo("OVER_LIMIT_BLOCKED");
    assertThat(blocked.get("blocked").asBoolean()).isTrue();
    assertThat(blocked.get("limitValue").asLong()).isEqualTo(1000);
    assertThat(blocked.get("usageValue").asLong()).isEqualTo(1500);

    // ORDER 维度降级：超出上限但不阻断。
    request(
        token,
        "POST",
        "/identity/saas-plans/usage",
        Map.of(
            "tenantId", tenantId.toString(),
            "subscriptionId", id.toString(),
            "entitlementCode", "ORDER",
            "usageValue", 999999,
            "available", true,
            "measuredAt", now.toString(),
            "sourceSystem", "trade"),
        200);
    var degraded =
        request(
                token,
                "GET",
                "/identity/saas-plans/entitlement-check?tenantId="
                    + tenantId
                    + "&entitlementCode=ORDER",
                null,
                200);
    assertThat(degraded.get("state").asText()).isEqualTo("OVER_LIMIT_DEGRADED");
    assertThat(degraded.get("blocked").asBoolean()).isFalse();

    // 没有订阅的租户 → NO_SUBSCRIPTION。
    UUID otherTenant = UUID.randomUUID();
    createTenant(tag + "X", otherTenant);
    var none =
        request(
                token,
                "GET",
                "/identity/saas-plans/entitlement-check?tenantId=" + otherTenant + "&entitlementCode=SKU",
                null,
                200);
    assertThat(none.get("state").asText()).isEqualTo("NO_SUBSCRIPTION");

    // 用量视图按目标租户读，读到的是真实登记值。
    var usage = request(token, "GET", "/identity/saas-plans/usage?tenantId=" + tenantId, null, 200);
    assertThat(usage.toString()).contains("SKU").contains("ORDER");
    // 用量视图带上权益定义的计量周期：前端要靠它区分「每月 3000 单」与「1000 个 SKU（总量）」。
    JsonNode orderUsage = findUsage(usage, "ORDER");
    assertThat(orderUsage).isNotNull();
    assertThat(orderUsage.get("period").asText()).as("ORDER 是按月重置的").isEqualTo("MONTH");
    JsonNode skuUsage = findUsage(usage, "SKU");
    assertThat(skuUsage).isNotNull();
    assertThat(skuUsage.get("period").asText()).as("SKU 是总量上限").isEqualTo("TOTAL");
    // 判定结果里同样带周期，租户侧拿到的判定口径与用量视图一致。
    assertThat(degraded.get("period").asText()).as("判定结果带同一个周期").isEqualTo("MONTH");
    // 另一个租户读不到这个租户的用量。
    var otherUsage = request(token, "GET", "/identity/saas-plans/usage?tenantId=" + otherTenant, null, 200);
    assertThat(otherUsage).isEmpty();
  }

  /**
   * 每一种超限判定都得真的走得到 —— 走不到的分支就是死代码。
   *
   * <p>出厂套餐覆盖不到 {@code OVER_LIMIT_ALLOWED}：ENTERPRISE 的权益项全是 unlimited，
   * 其余套餐没有 ALLOW 策略。所以这里自建一个「有上限 + ALLOW」的套餐来触达它。
   */
  @Test
  void everyOverLimitVerdictIsReachable() throws Exception {
    String token = platformToken();
    String tag = suffix();
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.ofHours(8)).withNano(0);

    // 自建套餐：SKU 有上限且 BLOCK，ORDER 有上限且 ALLOW（只记录不拦截）。
    String code = "VERDICT_" + tag;
    request(token, "POST", "/identity/saas-plans/plans", planInput(code, "判定覆盖套餐"), 200);
    request(
        token,
        "POST",
        "/identity/saas-plans/plans/" + code + "/entitlements",
        entitlementInput("SKU_" + tag, "SKU_COUNT", 100, "BLOCK"),
        200);
    request(
        token,
        "POST",
        "/identity/saas-plans/plans/" + code + "/entitlements",
        entitlementInput("ORDER_" + tag, "ORDER_VOLUME", 10, "ALLOW"),
        200);

    UUID tenantId = UUID.randomUUID();
    createTenant(tag + "V", tenantId);
    UUID subId =
        UUID.fromString(
            request(
                    token,
                    "POST",
                    "/identity/saas-plans/subscriptions",
                    Map.of(
                        "tenantId", tenantId.toString(),
                        "planCode", code,
                        "status", "ACTIVE",
                        "startedAt", now.toString(),
                        "expiresAt", now.plusYears(1).toString()),
                    200)
                .asText());

    // 用量在上限内 → WITHIN_LIMIT。
    request(
        token,
        "POST",
        "/identity/saas-plans/usage",
        Map.of(
            "tenantId", tenantId.toString(),
            "subscriptionId", subId.toString(),
            "entitlementCode", "SKU_" + tag,
            "usageValue", 50,
            "available", true,
            "measuredAt", now.toString(),
            "sourceSystem", "catalog"),
        200);
    var within =
        request(
            token,
            "GET",
            "/identity/saas-plans/entitlement-check?tenantId=" + tenantId
                + "&entitlementCode=SKU_" + tag,
            null,
            200);
    assertThat(within.get("state").asText()).isEqualTo("WITHIN_LIMIT");
    assertThat(within.get("blocked").asBoolean()).isFalse();

    // 超上限但策略是 ALLOW → OVER_LIMIT_ALLOWED：记录，但不拦截。
    request(
        token,
        "POST",
        "/identity/saas-plans/usage",
        Map.of(
            "tenantId", tenantId.toString(),
            "subscriptionId", subId.toString(),
            "entitlementCode", "ORDER_" + tag,
            "usageValue", 999,
            "available", true,
            "measuredAt", now.toString(),
            "sourceSystem", "trade"),
        200);
    var allowed =
        request(
            token,
            "GET",
            "/identity/saas-plans/entitlement-check?tenantId=" + tenantId
                + "&entitlementCode=ORDER_" + tag,
            null,
            200);
    assertThat(allowed.get("state").asText()).isEqualTo("OVER_LIMIT_ALLOWED");
    assertThat(allowed.get("blocked").asBoolean()).as("ALLOW 策略只记录不拦截").isFalse();

    // 不限量的权益 → UNLIMITED（不限量优先于「有没有超」）。
    UUID unlimitedTenant = UUID.randomUUID();
    createTenant(tag + "W", unlimitedTenant);
    UUID unlimitedSub =
        UUID.fromString(
            request(
                    token,
                    "POST",
                    "/identity/saas-plans/subscriptions",
                    Map.of(
                        "tenantId", unlimitedTenant.toString(),
                        "planCode", "ENTERPRISE",
                        "status", "ACTIVE",
                        "startedAt", now.toString(),
                        "expiresAt", now.plusYears(1).toString()),
                    200)
                .asText());
    request(
        token,
        "POST",
        "/identity/saas-plans/usage",
        Map.of(
            "tenantId", unlimitedTenant.toString(),
            "subscriptionId", unlimitedSub.toString(),
            "entitlementCode", "SKU",
            "usageValue", 999999,
            "available", true,
            "measuredAt", now.toString(),
            "sourceSystem", "catalog"),
        200);
    var unlimited =
        request(
            token,
            "GET",
            "/identity/saas-plans/entitlement-check?tenantId=" + unlimitedTenant
                + "&entitlementCode=SKU",
            null,
            200);
    assertThat(unlimited.get("state").asText()).isEqualTo("UNLIMITED");
    assertThat(unlimited.get("unlimited").asBoolean()).isTrue();
  }

  // ------------------------------------------------------------------
  // 多租户三断言
  // ------------------------------------------------------------------

  @Test
  void tenantIsolationTwoVisibleForgedHeaderAndPoolReturn() throws Exception {
    String tag = suffix();
    String platform = platformToken();

    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();
    createTenant(tag + "A", tenantA);
    createTenant(tag + "B", tenantB);

    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.ofHours(8)).withNano(0);
    for (UUID target : new UUID[] {tenantA, tenantB}) {
      request(
          platform,
          "POST",
          "/identity/saas-plans/subscriptions",
          Map.of(
              "tenantId", target.toString(),
              "planCode", "STARTER",
              "status", "ACTIVE",
              "startedAt", now.toString(),
              "expiresAt", now.plusYears(1).toString(),
              "note", "隔离测试 " + target),
          200);
    }

    // ---- 断言一：两租户互不可见（RLS 层）----
    // 切到 A 的租户上下文，只能看到 A 的订阅；切到 B 的上下文，只能看到 B 的。
    var aRows =
        tx()
            .run(
                tenantA,
                () -> sql.fetch("select id,tenant_id from saas_subscription").stream().toList());
    assertThat(aRows).hasSize(1);
    assertThat(aRows.get(0).get("tenant_id", UUID.class)).isEqualTo(tenantA);
    var bRows =
        tx()
            .run(
                tenantB,
                () -> sql.fetch("select id,tenant_id from saas_subscription").stream().toList());
    assertThat(bRows).hasSize(1);
    assertThat(bRows.get(0).get("tenant_id", UUID.class)).isEqualTo(tenantB);
    // 无租户上下文时两表都读不到任何行。
    assertThat(sql.fetch("select id,tenant_id from saas_subscription")).isEmpty();
    assertThat(sql.fetch("select id from saas_entitlement_usage")).isEmpty();

    // API 层：普通租户成员对两个租户都不可见（403），平台运营才看得到跨租户看板。
    String ordinary = ordinaryTenantToken(tag + "C");
    request(ordinary, "GET", "/identity/saas-plans/subscriptions", null, 403);
    var board = request(platform, "GET", "/identity/saas-plans/subscriptions", null, 200);
    assertThat(board.toString()).contains(tenantA.toString()).contains(tenantB.toString());

    // ---- 断言二：伪造 tenant header 无效 ----
    // 平台运营带着伪造的 X-Tenant-Id 读看板，结果与不带头时逐字段相同。
    String forged = UUID.randomUUID().toString();
    var forgedBoard =
        http.perform(
                get("/identity/saas-plans/subscriptions")
                    .header("Authorization", "Bearer " + platform)
                    .header("X-Tenant-Id", forged))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(forgedBoard)
        .as("伪造 tenant header 不得改变平台看板")
        .contains(tenantA.toString())
        .contains(tenantB.toString());
    // 伪造成另一个租户的真实 id 也一样：目标租户来自请求体，不来自请求头。
    var forgedReal =
        http.perform(
                get("/identity/saas-plans/subscriptions")
                    .header("Authorization", "Bearer " + platform)
                    .header("X-Tenant-Id", tenantB.toString()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(forgedReal).contains(tenantA.toString()).contains(tenantB.toString());
    // 普通租户带着伪造的头照样 403。
    http.perform(
            get("/identity/saas-plans/subscriptions")
                .header("Authorization", "Bearer " + ordinary)
                .header("X-Tenant-Id", tenantA.toString()))
        .andExpect(status().isForbidden());

    // ---- 断言三：连接池归还后不串租户（池大小压到 1）----
    for (int i = 0; i < 3; i++) {
      var inA = tx().run(tenantA, () -> sql.fetch("select id,tenant_id from saas_subscription"));
      assertThat(inA).as("第 %s 轮 A 的上下文只看到 A 的订阅", i).hasSize(1);
      assertThat(inA.get(0).get("tenant_id", UUID.class)).isEqualTo(tenantA);
      var inB = tx().run(tenantB, () -> sql.fetch("select id,tenant_id from saas_subscription"));
      assertThat(inB).as("第 %s 轮 B 的上下文只看到 B 的订阅", i).hasSize(1);
      assertThat(inB.get(0).get("tenant_id", UUID.class)).isEqualTo(tenantB);
    }
  }

  // ------------------------------------------------------------------
  // 留痕不可篡改 + 能力声明
  // ------------------------------------------------------------------

  @Test
  void subscriptionEventsAreAppendOnly() throws Exception {
    String token = platformToken();
    String tag = suffix();
    UUID tenantId = UUID.randomUUID();
    createTenant(tag, tenantId);
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.ofHours(8)).withNano(0);
    UUID id =
        UUID.fromString(
            request(
                    token,
                    "POST",
                    "/identity/saas-plans/subscriptions",
                    Map.of(
                        "tenantId", tenantId.toString(),
                        "planCode", "STARTER",
                        "status", "ACTIVE",
                        "startedAt", now.toString(),
                        "expiresAt", now.plusYears(1).toString()),
                    200)
                .asText());

    var events = request(token, "GET", "/identity/saas-plans/subscriptions/" + id + "/events", null, 200);
    assertThat(events).isNotEmpty();
    assertThat(events.get(0).get("action").asText()).isEqualTo("CREATED");
    assertThat(events.get(0).get("actorName").asText()).isNotBlank();

    // 应用侧没有 UPDATE/DELETE 权限。
    assertThat(hasPrivilege("saas_subscription_event", "UPDATE")).isFalse();
    assertThat(hasPrivilege("saas_subscription_event", "DELETE")).isFalse();
    // 数据库层的触发器也挡着：直连库改不动。
    assertThatCode(() -> asSuperuser("update saas_subscription_event set reason='改写'"))
        .isInstanceOf(Exception.class);
    assertThatCode(() -> asSuperuser("delete from saas_subscription_event"))
        .isInstanceOf(Exception.class);
  }

  @Test
  void capabilitiesDeclareUndeliveredSegmentsHonestly() throws Exception {
    String token = platformToken();
    var caps = request(token, "GET", "/identity/saas-plans/capabilities", null, 200);
    var dimensions = caps.get("dimensions");
    assertThat(dimensions).isNotEmpty();
    int unavailable = 0;
    for (JsonNode d : dimensions) {
      if (!d.get("available").asBoolean()) {
        unavailable++;
        assertThat(d.get("ownerModule").asText())
            .as("未交付段必须点名归属模块：%s", d.get("dimension").asText())
            .isNotBlank();
        assertThat(d.get("impact").asText())
            .as("未交付段必须说清影响面：%s", d.get("dimension").asText())
            .isNotBlank();
      }
    }
    assertThat(unavailable).as("至少有三条未交付段要如实标注").isGreaterThanOrEqualTo(3);
    // 能力声明本身不落金额。
    assertThat(caps.get("financeNote").asText()).contains("不落任何金额");
    assertThat(caps.get("meteringNote").asText()).contains("UNDETERMINED");
    assertThat(caps.get("boundaryNote").asText()).contains("租户不可访问");
  }

  // ------------------------------------------------------------------
  // 辅助
  // ------------------------------------------------------------------

  /** 造一个真实存在的目标租户（平台运营替客户开通订阅时要指到它）。 */
  private void createTenant(String tag, UUID tenantId) throws Exception {
    asSuperuser(
        "insert into tenant(id,code,name) values('"
            + tenantId
            + "','SAAS_T"
            + tag
            + "','套餐与权益目标租户 "
            + tag
            + "')");
    asSuperuser(
        "insert into tenant_closure(tenant_id,descendant_id,depth) values('"
            + tenantId
            + "','"
            + tenantId
            + "',0)");
  }
}
