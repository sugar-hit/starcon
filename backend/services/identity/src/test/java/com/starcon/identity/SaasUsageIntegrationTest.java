package com.starcon.identity;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.starcon.tenancy.TenantTransactions;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 17.7.1.2 用量统计。
 *
 * <p>重点不是「能存进去」，而是蓝图点名的纪律真的成立：
 *
 * <ol>
 *   <li><b>口径与套餐定义严格一致</b>——流量维度的桶粒度固定为套餐定义的周期（不按更细的
 *       统计周期切分），存量维度的桶粒度跟随统计周期；上限实时读套餐定义。
 *   <li><b>没接通的不假装接通</b>——来源系统没登记的维度，统计事实里<b>没有这一行</b>
 *       （不是一行 0），计费依据里它的结论是 UNDETERMINED。
 *   <li><b>仅平台管理员可见，租户不可访问</b>——普通租户即使带着 identity.subscription.*
 *       权限码也进不来。
 *   <li><b>不落任何金额</b>——统计表里没有金额语义列。
 * </ol>
 *
 * <p>时间字段按值断言：周期边界用 +08:00 偏移的 asOf 归集，读回时断言绝对时刻，
 * 用 {@code Timestamp.valueOf(LocalDateTime)} 绑 timestamptz 会静默偏 −28800 秒，
 * 只断言「没抛异常」是查不出来的。
 */
class SaasUsageIntegrationTest extends IdentityIntegrationTestBase {

  /** 唯一的来源系统标记：用来在跨租户聚合里认出「我这条登记」。 */
  private static final String SOURCE_MARK = "usage_probe";

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

  private boolean hasPrivilege(String table, String privilege) throws Exception {    try (var c =
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
    String opsUser = "usage-platform-ops-" + tag.toLowerCase();
    request(
        root,
        "POST",
        "/identity/children",
        Map.of(
            "code",
            "UOP" + tag,
            "name",
            "用量统计平台运营租户",
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
   * <p>登记会把 PLATFORM_OPS 角色挂到**调用者本人**身上，所以只能对**专属身份**做，
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

  /**
   * 用例级护栏：本测试类的**任何一个**用例跑完之后，共享的 local-admin（root 租户 OWNER）
   * 仍然**只有一个角色**。
   *
   * <p>这条断言是那个回归的哨兵。平台运营角色登记会把 PLATFORM_OPS **永久挂到调用者本人**身上；
   * 挂到共享 owner 上，全套 identity 测试一起跑时就会踩到别的测试类靠
   * {@code grants.get(0)} 猜角色的假设（grants 是无序聚合，顺序不保证）。
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

  /** 一个**普通租户**的所有者：带着 identity.subscription.* 权限码，但不是平台运营方。 */
  private String ordinaryTenantToken(String tag) throws Exception {
    String owner = login();
    String childUser = "usage-ordinary-" + tag.toLowerCase();
    request(
        owner,
        "POST",
        "/identity/children",
        Map.of(
            "code",
            "US" + tag,
            "name",
            "用量统计普通租户",
            "ownerUsername",
            childUser,
            "ownerPassword",
            "test-member-password"),
        200);
    return loginUser(childUser);
  }

  /** 造一个真实存在的目标租户。 */
  private void createTenant(String tag, UUID tenantId) throws Exception {
    asSuperuser(
        "insert into tenant(id,code,name) values('"
            + tenantId
            + "','USE_T"
            + tag
            + "','用量统计目标租户 "
            + tag
            + "')");
    asSuperuser(
        "insert into tenant_closure(tenant_id,descendant_id,depth) values('"
            + tenantId
            + "','"
            + tenantId
            + "',0)");
  }

  /** 开通订阅（走 17.7.1.1 的端点，本模块不另造一套）。 */
  private UUID subscribe(String token, UUID tenantId, String planCode) throws Exception {
    return UUID.fromString(
        request(
                token,
                "POST",
                "/identity/saas-plans/subscriptions",
                Map.of(
                    "tenantId", tenantId.toString(),
                    "planCode", planCode,
                    "status", "ACTIVE",
                    "startedAt",
                        OffsetDateTime.now(ZoneOffset.UTC).minusDays(3).withNano(0).toString(),
                    "expiresAt",
                        OffsetDateTime.now(ZoneOffset.UTC).plusYears(1).withNano(0).toString(),
                    "note", "用量统计集成测试"),
                200)
            .asText());
  }

  /** 登记用量（走 17.7.1.1 的端点：来源系统经它登记，本模块只读）。 */
  private void registerUsage(
      String token, UUID tenantId, UUID subscriptionId, String entitlementCode, int value)
      throws Exception {
    request(
        token,
        "POST",
        "/identity/saas-plans/usage",
        Map.of(
            "tenantId", tenantId.toString(),
            "subscriptionId", subscriptionId.toString(),
            "entitlementCode", entitlementCode,
            "usageValue", value,
            "available", true,
            "measuredAt", OffsetDateTime.now(ZoneOffset.UTC).withNano(0).toString(),
            "sourceSystem", SOURCE_MARK,
            "note", "用量统计集成测试"),
        200);
  }

  /** 归集。 */
  private JsonNode collect(String token, UUID tenantId, String periodType, OffsetDateTime asOf)
      throws Exception {
    var body = new LinkedHashMap<String, Object>();
    if (tenantId != null) body.put("tenantId", tenantId.toString());
    body.put("periodType", periodType);
    if (asOf != null) body.put("asOf", asOf.toString());
    return request(token, "POST", "/identity/saas-usage/collections", body, 200);
  }

  private JsonNode findRow(JsonNode rows, String entitlementCode) {
    for (JsonNode n : rows)
      if (entitlementCode.equals(n.get("entitlementCode").asText())) return n;
    return null;
  }

  private JsonNode findBilling(JsonNode rows, String entitlementCode) {
    for (JsonNode n : rows)
      if (entitlementCode.equals(n.get("entitlementCode").asText())) return n;
    return null;
  }

  /** 按**维度码**定位一行（覆盖与容量两视图按维度给，不按权益码）。 */
  private JsonNode findKind(JsonNode rows, String entitlementKind) {
    for (JsonNode n : rows)
      if (entitlementKind.equals(n.get("entitlementKind").asText())) return n;
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
    var session = request(ordinary, "GET", "/auth/session", null, 200);
    var codes = new ArrayList<String>();
    session.get("permissions").forEach(p -> codes.add(p.asText()));
    assertThat(codes)
        .as("普通租户所有者带着平台运营权限码，这条断言才成立")
        .contains("identity.subscription.read", "identity.subscription.update");

    // 但平台运营域一律拒绝：读、写、归集全部 403。
    request(ordinary, "GET", "/identity/saas-usage/capabilities", null, 403);
    request(ordinary, "GET", "/identity/saas-usage/options", null, 403);
    request(ordinary, "GET", "/identity/saas-usage/report", null, 403);
    request(ordinary, "GET", "/identity/saas-usage/trend", null, 403);
    request(ordinary, "GET", "/identity/saas-usage/billing-basis", null, 403);
    request(ordinary, "GET", "/identity/saas-usage/capacity", null, 403);
    request(ordinary, "GET", "/identity/saas-usage/coverage", null, 403);
    request(ordinary, "GET", "/identity/saas-usage/collections", null, 403);
    request(
        ordinary,
        "POST",
        "/identity/saas-usage/collections",
        Map.of("periodType", "MONTH"),
        403);

    // 未登录一律 401。
    http.perform(get("/identity/saas-usage/report")).andExpect(status().isUnauthorized());
  }

  // ------------------------------------------------------------------
  // 纪律二：口径与套餐定义严格一致
  // ------------------------------------------------------------------

  @Test
  void collectionMaterializesRegisteredUsageIntoCaliberBuckets() throws Exception {
    String tag = suffix();
    String platform = platformToken();
    UUID tenantId = UUID.randomUUID();
    createTenant(tag, tenantId);
    UUID subscriptionId = subscribe(platform, tenantId, "STARTER");

    // STARTER 的口径：SHOP/SKU/MEMBER 是存量（TOTAL），ORDER/API/AI 是流量（MONTH）。
    registerUsage(platform, tenantId, subscriptionId, "SKU", 800);
    registerUsage(platform, tenantId, subscriptionId, "ORDER", 2500);

    // 用 +08:00 偏移的 asOf 归集：周期边界按 UTC 切分，偏移不该把日期带偏。
    OffsetDateTime asOf = OffsetDateTime.of(2026, 3, 15, 18, 0, 0, 0, ZoneOffset.ofHours(8));
    var run = collect(platform, tenantId, "MONTH", asOf);
    assertThat(run.get("tenantCount").asInt()).isEqualTo(1);
    assertThat(run.get("measuredDimensionCount").asInt()).isEqualTo(2);
    assertThat(run.get("unmeasuredDimensionCount").asInt()).isEqualTo(4);

    var tenants = run.get("tenants");
    var result = tenants.get(0);
    assertThat(result.get("dimensionCount").asInt()).isEqualTo(6);
    assertThat(result.get("measuredCount").asInt()).isEqualTo(2);
    assertThat(result.get("unmeasuredCount").asInt()).isEqualTo(4);
    assertThat(result.get("status").asText()).isEqualTo("PARTIAL");
    // sourceSystems 是一个数组（本次归集实际读到哪些来源系统）。
    var sourceSystems = result.get("sourceSystems");
    assertThat(sourceSystems.isArray()).isTrue();
    assertThat(sourceSystems.toString()).contains(SOURCE_MARK);

    var rows =
        request(
            platform,
            "GET",
            "/identity/saas-usage/report?tenantId=" + tenantId + "&periodType=MONTH",
            null,
            200);
    assertThat(rows).hasSize(2);

    // 存量维度（SKU，套餐口径 TOTAL）：桶粒度跟随统计周期 MONTH。
    var sku = findRow(rows, "SKU");
    assertThat(sku).isNotNull();
    assertThat(sku.get("entitlementKind").asText()).isEqualTo("SKU_COUNT");
    assertThat(sku.get("periodType").asText()).isEqualTo("MONTH");
    assertThat(sku.get("quantity").asInt()).isEqualTo(800);
    assertThat(sku.get("sourceSystem").asText()).isEqualTo(SOURCE_MARK);
    assertThat(sku.get("entitlementLimit").asInt()).isEqualTo(1000);
    assertThat(sku.get("utilization").asDouble()).isEqualTo(0.8);
    assertThat(sku.get("overLimit").asBoolean()).isFalse();

    // 流量维度（ORDER，套餐口径 MONTH）：桶粒度固定为 MONTH。
    var order = findRow(rows, "ORDER");
    assertThat(order).isNotNull();
    assertThat(order.get("entitlementKind").asText()).isEqualTo("ORDER_VOLUME");
    assertThat(order.get("periodType").asText()).isEqualTo("MONTH");
    assertThat(order.get("quantity").asInt()).isEqualTo(2500);

    // 周期边界按 UTC 切分并**按值**断言（asOf 是 +08:00 偏移，读回必须是同一批绝对时刻）。
    assertThat(OffsetDateTime.parse(sku.get("periodStart").asText()).toInstant())
        .isEqualTo(java.time.Instant.parse("2026-03-01T00:00:00Z"));
    assertThat(OffsetDateTime.parse(sku.get("periodEnd").asText()).toInstant())
        .isEqualTo(java.time.Instant.parse("2026-04-01T00:00:00Z"));
  }

  @Test
  void flowDimensionKeepsItsCaliberPeriodWhenCollectedWithAFinerCycle() throws Exception {
    String tag = suffix();
    String platform = platformToken();
    UUID tenantId = UUID.randomUUID();
    createTenant(tag, tenantId);
    UUID subscriptionId = subscribe(platform, tenantId, "STARTER");
    registerUsage(platform, tenantId, subscriptionId, "ORDER", 120);
    registerUsage(platform, tenantId, subscriptionId, "SHOP", 3);

    // 用日粒度归集：来源系统给的是月值，就不能被切进日桶（切了就是计费争议）。
    var asOf = OffsetDateTime.now(ZoneOffset.UTC);
    collect(platform, tenantId, "DAY", asOf);

    var rows =
        request(
            platform,
            "GET",
            "/identity/saas-usage/report?tenantId=" + tenantId,
            null,
            200);
    var order = findRow(rows, "ORDER");
    assertThat(order.get("periodType").asText()).isEqualTo("MONTH");
    // 存量维度反过来：它的值是快照，桶粒度跟随统计周期，于是存量维度也有日趋势。
    var shop = findRow(rows, "SHOP");
    assertThat(shop.get("periodType").asText()).isEqualTo("DAY");
  }

  @Test
  void unmeasuredDimensionsLeaveNoRowAndStayUndetermined() throws Exception {
    String tag = suffix();
    String platform = platformToken();
    UUID tenantId = UUID.randomUUID();
    createTenant(tag, tenantId);
    UUID subscriptionId = subscribe(platform, tenantId, "STARTER");
    registerUsage(platform, tenantId, subscriptionId, "SKU", 42);

    collect(platform, tenantId, "MONTH", OffsetDateTime.now(ZoneOffset.UTC));

    // 统计事实里只有量到的那一行——没量到的维度**不是一行 0**。
    var rows =
        request(
            platform,
            "GET",
            "/identity/saas-usage/report?tenantId=" + tenantId,
            null,
            200);
    assertThat(rows).hasSize(1);
    assertThat(findRow(rows, "SKU")).isNotNull();

    // 计费依据里六个维度都在（套餐 STARTER 有六项权益），没量到的结论是 UNDETERMINED。
    var billing =
        request(
            platform,
            "GET",
            "/identity/saas-usage/billing-basis?tenantId=" + tenantId,
            null,
            200);
    assertThat(billing).hasSize(6);
    var shop = findBilling(billing, "SHOP");
    assertThat(shop.get("state").asText()).isEqualTo("UNDETERMINED");
    assertThat(shop.get("quantity").isNull()).as("没量到的维度用量必须为空，不能是 0").isTrue();
    assertThat(shop.get("stateNote").asText()).isNotBlank();
    var sku = findBilling(billing, "SKU");
    assertThat(sku.get("state").asText()).isEqualTo("WITHIN_LIMIT");
    assertThat(sku.get("quantity").asInt()).isEqualTo(42);
  }

  @Test
  void collectionIsIdempotentWithinTheSamePeriod() throws Exception {
    String tag = suffix();
    String platform = platformToken();
    UUID tenantId = UUID.randomUUID();
    createTenant(tag, tenantId);
    UUID subscriptionId = subscribe(platform, tenantId, "STARTER");
    registerUsage(platform, tenantId, subscriptionId, "ORDER", 777);

    var asOf = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
    collect(platform, tenantId, "MONTH", asOf);
    collect(platform, tenantId, "MONTH", asOf);
    collect(platform, tenantId, "MONTH", asOf);

    var rows =
        request(
            platform,
            "GET",
            "/identity/saas-usage/report?tenantId=" + tenantId,
            null,
            200);
    assertThat(rows).as("重复归集是覆盖，不是累加").hasSize(1);
    assertThat(findRow(rows, "ORDER").get("quantity").asInt()).isEqualTo(777);
  }

  @Test
  void overLimitShowsUpInTheBillingBasisWithoutAnyAmount() throws Exception {
    String tag = suffix();
    String platform = platformToken();
    UUID tenantId = UUID.randomUUID();
    createTenant(tag, tenantId);
    UUID subscriptionId = subscribe(platform, tenantId, "STARTER");
    // STARTER 的 SKU 上限 1000，超限策略 BLOCK；ORDER 上限 3000，策略 DEGRADE。
    registerUsage(platform, tenantId, subscriptionId, "SKU", 1500);
    registerUsage(platform, tenantId, subscriptionId, "ORDER", 3500);

    collect(platform, tenantId, "MONTH", OffsetDateTime.now(ZoneOffset.UTC));

    var billing =
        request(
            platform,
            "GET",
            "/identity/saas-usage/billing-basis?tenantId=" + tenantId,
            null,
            200);
    var sku = findBilling(billing, "SKU");
    assertThat(sku.get("state").asText()).isEqualTo("OVER_LIMIT");
    assertThat(sku.get("overLimit").asBoolean()).isTrue();
    assertThat(sku.get("overLimitAction").asText()).isEqualTo("BLOCK");
    assertThat(sku.get("entitlementLimit").asInt()).isEqualTo(1000);
    assertThat(sku.get("quantity").asInt()).isEqualTo(1500);
    // 计费依据里没有任何金额字段。
    assertThat(sku.has("amount")).isFalse();
    assertThat(sku.has("price")).isFalse();
    assertThat(sku.has("invoice")).isFalse();
    assertThat(sku.has("totalAmount")).isFalse();
  }

  @Test
  void trendAggregatesTheSameDimensionIntoOneSeries() throws Exception {
    String tag = suffix();
    String platform = platformToken();
    UUID tenantId = UUID.randomUUID();
    createTenant(tag, tenantId);
    UUID subscriptionId = subscribe(platform, tenantId, "STARTER");
    registerUsage(platform, tenantId, subscriptionId, "ORDER", 100);

    // 归集两次，落在两个不同的月份里 ⇒ 趋势上两个点。
    collect(platform, tenantId, "MONTH", OffsetDateTime.parse("2026-01-20T00:00:00Z"));
    collect(platform, tenantId, "MONTH", OffsetDateTime.parse("2026-02-20T00:00:00Z"));

    var trend =
        request(
            platform,
            "GET",
            "/identity/saas-usage/trend?tenantId=" + tenantId + "&kind=ORDER_VOLUME",
            null,
            200);
    assertThat(trend).hasSize(2);
    assertThat(trend.get(0).get("periodStart").asText()).startsWith("2026-01-01");
    assertThat(trend.get(1).get("periodStart").asText()).startsWith("2026-02-01");
    assertThat(trend.get(0).get("quantity").asInt()).isEqualTo(100);
    assertThat(trend.get(0).get("sourceSystems").asText()).contains(SOURCE_MARK);
  }

  @Test
  void capacityAndCoverageReportWhatIsActuallyWired() throws Exception {
    String tag = suffix();
    String platform = platformToken();
    UUID tenantId = UUID.randomUUID();
    createTenant(tag, tenantId);
    UUID subscriptionId = subscribe(platform, tenantId, "STARTER");
    registerUsage(platform, tenantId, subscriptionId, "SKU", 500);

    collect(platform, tenantId, "MONTH", OffsetDateTime.now(ZoneOffset.UTC));

    var coverage = request(platform, "GET", "/identity/saas-usage/coverage", null, 200);
    assertThat(coverage).hasSize(6);
    var sku = findKind(coverage, "SKU_COUNT");
    assertThat(sku.get("wiredSourceSystems").asText()).contains(SOURCE_MARK);
    assertThat(sku.get("registeredTenantCount").asLong()).isGreaterThanOrEqualTo(1);
    assertThat(sku.get("measuredTenantCount").asLong()).isGreaterThanOrEqualTo(1);
    assertThat(sku.get("state").asText()).isIn("WIRED", "PARTIAL");
    // 没有任何来源系统登记过的维度：如实报「未接通」，不是「用量 0」。
    var ai = findKind(coverage, "AI_USAGE");
    assertThat(ai.get("registeredTenantCount").asLong()).isEqualTo(0);
    assertThat(ai.get("state").asText()).isEqualTo("NOT_WIRED");
    assertThat(ai.get("stateNote").asText()).isNotBlank();

    var capacity = request(platform, "GET", "/identity/saas-usage/capacity", null, 200);
    assertThat(capacity).hasSize(6);
    var capacitySku = findKind(capacity, "SKU_COUNT");
    assertThat(capacitySku.get("usedTotal").asDouble()).isGreaterThanOrEqualTo(500.0);
    assertThat(capacitySku.get("licensedTotal").asDouble()).isGreaterThan(0.0);
    assertThat(capacitySku.get("headroom").asDouble()).isNotNaN();
    assertThat(capacitySku.get("subscribedTenantCount").asLong()).isGreaterThanOrEqualTo(1);
  }

  // ------------------------------------------------------------------
  // 纪律三：多租户隔离（三条断言实跑）
  // ------------------------------------------------------------------

  @Test
  void tenantIsolationHoldsOnThreeAssertions() throws Exception {
    String tag = suffix();
    String platform = platformToken();
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();
    createTenant(tag + "A", tenantA);
    createTenant(tag + "B", tenantB);
    UUID subA = subscribe(platform, tenantA, "STARTER");
    UUID subB = subscribe(platform, tenantB, "STARTER");
    registerUsage(platform, tenantA, subA, "ORDER", 111);
    registerUsage(platform, tenantB, subB, "ORDER", 222);

    collect(platform, tenantA, "MONTH", OffsetDateTime.now(ZoneOffset.UTC));
    collect(platform, tenantB, "MONTH", OffsetDateTime.now(ZoneOffset.UTC));

    // ---- 断言一：两租户互不可见（RLS 层）----
    var aRows =
        tx()
            .run(
                tenantA,
                () -> sql.fetch("select id,tenant_id from saas_usage_statistic").stream().toList());
    assertThat(aRows).isNotEmpty();
    assertThat(aRows)
        .allSatisfy(r -> assertThat(r.get("tenant_id", UUID.class)).isEqualTo(tenantA));
    var bRows =
        tx()
            .run(
                tenantB,
                () -> sql.fetch("select id,tenant_id from saas_usage_statistic").stream().toList());
    assertThat(bRows).isNotEmpty();
    assertThat(bRows)
        .allSatisfy(r -> assertThat(r.get("tenant_id", UUID.class)).isEqualTo(tenantB));
    // 无租户上下文时读到零行（不放行「全部租户」）。
    assertThat(sql.fetch("select id from saas_usage_statistic")).isEmpty();
    assertThat(sql.fetch("select id from saas_usage_collection")).isEmpty();

    // API 层：平台运营看得到两个租户，量互不相干。
    var report = request(platform, "GET", "/identity/saas-usage/report", null, 200);
    var forA =
        request(
            platform,
            "GET",
            "/identity/saas-usage/report?tenantId=" + tenantA,
            null,
            200);
    var forB =
        request(
            platform,
            "GET",
            "/identity/saas-usage/report?tenantId=" + tenantB,
            null,
            200);
    assertThat(forA.toString()).contains("111").doesNotContain("222");
    assertThat(forB.toString()).contains("222").doesNotContain("111");
    assertThat(report.toString()).contains(tenantA.toString()).contains(tenantB.toString());

    // ---- 断言二：伪造 tenant header 无效 ----
    // 平台运营带着伪造的 X-Tenant-Id 归集，落点仍由请求体的目标租户决定。
    var forged =
        http.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                        "/identity/saas-usage/collections")
                    .header("Authorization", "Bearer " + platform)
                    .header("X-Tenant-Id", tenantB.toString())
                    .header("Content-Type", "application/json")
                    .content(
                        "{\"tenantId\":\""
                            + tenantA
                            + "\",\"periodType\":\"MONTH\"}"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(forged).contains(tenantA.toString()).doesNotContain(tenantB.toString());
    // 伪造头之后，A 的上下文里仍然只有 A 的行。
    var afterForged =
        tx()
            .run(
                tenantA,
                () -> sql.fetch("select tenant_id from saas_usage_statistic").stream().toList());
    assertThat(afterForged)
        .allSatisfy(r -> assertThat(r.get("tenant_id", UUID.class)).isEqualTo(tenantA));

    // ---- 断言三：连接池归还后不串租户（池大小压到 1）----
    for (int i = 0; i < 3; i++) {
      var inA =
          tx().run(tenantA, () -> sql.fetch("select tenant_id from saas_usage_statistic"));
      assertThat(inA).as("第 %s 轮 A 的上下文只看到 A 的统计事实", i).isNotEmpty();
      assertThat(inA)
          .allSatisfy(r -> assertThat(r.get("tenant_id", UUID.class)).isEqualTo(tenantA));
      var inB =
          tx().run(tenantB, () -> sql.fetch("select tenant_id from saas_usage_statistic"));
      assertThat(inB).as("第 %s 轮 B 的上下文只看到 B 的统计事实", i).isNotEmpty();
      assertThat(inB)
          .allSatisfy(r -> assertThat(r.get("tenant_id", UUID.class)).isEqualTo(tenantB));
    }
  }

  // ------------------------------------------------------------------
  // 纪律四：不落任何金额 / 事实表不可物理删除 / 留痕追加不可改
  // ------------------------------------------------------------------

  @Test
  void statisticTablesCarryNoMoneyColumnsAndNoDeleteGrant() throws Exception {
    assertThat(hasPrivilege("saas_usage_statistic", "SELECT")).isTrue();
    assertThat(hasPrivilege("saas_usage_statistic", "INSERT")).isTrue();
    assertThat(hasPrivilege("saas_usage_statistic", "UPDATE")).isTrue();
    assertThat(hasPrivilege("saas_usage_statistic", "DELETE")).isFalse();
    assertThat(hasPrivilege("saas_usage_collection", "SELECT")).isTrue();
    assertThat(hasPrivilege("saas_usage_collection", "INSERT")).isTrue();
    assertThat(hasPrivilege("saas_usage_collection", "UPDATE")).isFalse();
    assertThat(hasPrivilege("saas_usage_collection", "DELETE")).isFalse();

    // 没有金额语义列：与 ERP 客户的销售收入完全无关。
    try (var c =
            DriverManager.getConnection(pg().getJdbcUrl(), pg().getUsername(), pg().getPassword());
        var s = c.createStatement();
        var rs =
            s.executeQuery(
                "select table_name,column_name from information_schema.columns"
                    + " where table_name in ('saas_usage_statistic','saas_usage_collection')"
                    + " and (column_name ~* '(amount|price|invoice|receivable|payable|payment|settle|revenue|balance|fee|currency|tax)'"
                    + " or data_type in ('money'))")) {
      var found = new ArrayList<String>();
      while (rs.next()) found.add(rs.getString(1) + "." + rs.getString(2));
      assertThat(found).as("用量统计表里不得出现金额语义列").isEmpty();
    }

    // 两张表都是租户级：带 tenant_id NOT NULL 且 FORCE RLS。
    try (var c =
            DriverManager.getConnection(pg().getJdbcUrl(), pg().getUsername(), pg().getPassword());
        var s = c.createStatement();
        var rs =
            s.executeQuery(
                "select c.relname, c.relforcerowsecurity,"
                    + " (select count(*) from information_schema.columns col"
                    + "  where col.table_name=c.relname and col.column_name='tenant_id'"
                    + "    and col.is_nullable='NO') as tenant_not_null"
                    + " from pg_class c join pg_namespace n on n.oid=c.relnamespace"
                    + " where n.nspname='public' and c.relname in"
                    + " ('saas_usage_statistic','saas_usage_collection')")) {
      var rows = new ArrayList<String>();
      while (rs.next()) {
        assertThat(rs.getBoolean("relforcerowsecurity"))
            .as(rs.getString(1) + " 必须 FORCE ROW LEVEL SECURITY")
            .isTrue();
        assertThat(rs.getInt("tenant_not_null")).as(rs.getString(1) + " 必须 tenant_id NOT NULL").isEqualTo(1);
        rows.add(rs.getString(1));
      }
      assertThat(rows).hasSize(2);
    }
  }

  @Test
  void collectionLogIsAppendOnly() throws Exception {
    String tag = suffix();
    String platform = platformToken();
    UUID tenantId = UUID.randomUUID();
    createTenant(tag, tenantId);
    UUID subscriptionId = subscribe(platform, tenantId, "STARTER");
    registerUsage(platform, tenantId, subscriptionId, "SKU", 5);
    var run = collect(platform, tenantId, "MONTH", OffsetDateTime.now(ZoneOffset.UTC));
    UUID collectionId =
        UUID.fromString(run.get("tenants").get(0).get("collectionId").asText());

    // 改留痕 → 触发器挡住（即使拿的是超级用户连接）。
    assertThatThrownBy(() -> asSuperuser("update saas_usage_collection set note='x' where id='" + collectionId + "'"))
        .hasMessageContaining("用量归集批次不可修改或删除");
    // 删留痕 → 同样挡住。
    assertThatThrownBy(() -> asSuperuser("delete from saas_usage_collection where id='" + collectionId + "'"))
        .hasMessageContaining("用量归集批次不可修改或删除");
  }

  @Test
  void capabilitiesReportTheGapsHonestly() throws Exception {
    String platform = platformToken();
    var capabilities = request(platform, "GET", "/identity/saas-usage/capabilities", null, 200);

    // 维度码与周期码只能用 17.7.1.1 已定义的那一套。
    var kinds = new ArrayList<String>();
    capabilities.get("entitlementKinds").forEach(n -> kinds.add(n.asText()));
    assertThat(kinds)
        .containsExactlyInAnyOrder(
            "SHOP_COUNT", "SKU_COUNT", "ORDER_VOLUME", "USER_COUNT", "API_CALL", "AI_USAGE");
    var periods = new ArrayList<String>();
    capabilities.get("periods").forEach(n -> periods.add(n.asText()));
    assertThat(periods).containsExactlyInAnyOrder("DAY", "MONTH", "YEAR", "TOTAL");

    var byName = new LinkedHashMap<String, JsonNode>();
    for (JsonNode n : capabilities.get("dimensions")) byName.put(n.get("dimension").asText(), n);

    // 已交付的部分：available=true。
    assertThat(byName.get("周期归集与统计事实").get("available").asBoolean()).isTrue();
    assertThat(byName.get("用量报表与趋势").get("available").asBoolean()).isTrue();
    assertThat(byName.get("计费依据（数量口径）").get("available").asBoolean()).isTrue();
    assertThat(byName.get("容量规划").get("available").asBoolean()).isTrue();

    // 没交付的部分：available=false 且必须写明归属模块与影响面。
    for (String name :
        List.of("来源系统埋点接入", "存储维度计量", "计费与收入确认")) {
      var dimension = byName.get(name);
      assertThat(dimension).as(name + " 必须出现在能力声明里").isNotNull();
      assertThat(dimension.get("available").asBoolean()).as(name + " 必须标 available=false").isFalse();
      assertThat(dimension.get("ownerModule").asText()).as(name + " 必须写明归属模块").isNotBlank();
      assertThat(dimension.get("impact").asText()).as(name + " 必须写明影响面").isNotBlank();
    }
    // 存储维度的缺口指向套餐定义（本模块不另起 STORAGE 维度码）。
    assertThat(byName.get("存储维度计量").get("ownerModule").asText()).isEqualTo("17.7.1.1");
    // 不落任何金额。
    assertThat(capabilities.get("moneyNote").asText()).contains("不落任何金额");
    assertThat(capabilities.get("boundaryNote").asText()).contains("租户不可访问");
  }

  @Test
  void optionsExposeTheSameVocabularyAsTheBackend() throws Exception {
    String platform = platformToken();
    var options = request(platform, "GET", "/identity/saas-usage/options", null, 200);
    var kinds = new ArrayList<String>();
    options.get("entitlementKinds").forEach(n -> kinds.add(n.asText()));
    assertThat(kinds)
        .containsExactlyInAnyOrder(
            "SHOP_COUNT", "SKU_COUNT", "ORDER_VOLUME", "USER_COUNT", "API_CALL", "AI_USAGE");
    var periods = new ArrayList<String>();
    options.get("periods").forEach(n -> periods.add(n.asText()));
    assertThat(periods).containsExactlyInAnyOrder("DAY", "MONTH", "YEAR", "TOTAL");
    var states = new ArrayList<String>();
    options.get("billingStates").forEach(n -> states.add(n.asText()));
    assertThat(states).contains("UNDETERMINED");
  }

  @Test
  void invalidCollectionRequestIsRejected() throws Exception {
    String platform = platformToken();
    // 统计周期必须是四个既定周期码之一。
    request(
        platform,
        "POST",
        "/identity/saas-usage/collections",
        Map.of("periodType", "WEEK"),
        400);
    request(
        platform,
        "POST",
        "/identity/saas-usage/collections",
        Map.of("periodType", "MONTH", "tenantId", UUID.randomUUID().toString()),
        400);
  }
}
