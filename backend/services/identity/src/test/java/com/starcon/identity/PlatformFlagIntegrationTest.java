package com.starcon.identity;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.starcon.tenancy.TenantTransactions;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.Record;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 17.7.2.1 平台参数与开关。
 *
 * <p>重点不是「能存进去」，而是蓝图点名的硬要求真的成立：
 *
 * <ol>
 *   <li><b>平台开关与租户参数分层，平台开关不可被租户覆盖</b> —— 平台全局层不带 {@code
 *       tenant_id}、应用角色对它<b>没有 DML 权限</b>；租户例外层只能 DISABLE（{@code effect}
 *       的取值域被 CHECK 钉死），平台禁用的功能租户打不开。
 *   <li><b>灰度放量必须是确定性的</b> —— 同一租户反复请求得到同一个分桶与同一个结果；
 *       Java 侧分桶与数据库函数 {@code platform_flag_rollout_bucket} 逐个租户一致。
 *   <li><b>限流阈值 / 公告 / 灰度比例是平台级参数</b> —— 限流阈值<b>不给默认值</b>：
 *       未配置时判定如实返回「未配置」，不替平台选一个数字。
 *   <li><b>裁决过程可见</b> —— 判定端点把平台全局值、租户例外、最终生效值三层并列给出，
 *       不给一个孤零零的布尔值。
 * </ol>
 *
 * <p>另含多租户三断言（两租户互不可见 / 伪造租户头无效 / 连接池归还后不串租户），
 * 以及平台全局表「租户不可写」的<b>独立断言</b>（直接 SQL 写被数据库挡下）。
 *
 * <p><b>时间字段按值断言</b>：用 {@code Timestamp.valueOf(LocalDateTime)} 绑 timestamptz
 * 会静默偏 −28800 秒，所以公告生效期写回后必须逐字段比对。
 */
class PlatformFlagIntegrationTest extends IdentityIntegrationTestBase {

  /** 平台根租户（LocalBootstrap 建的那一个）之外，另建一个子租户做隔离断言。 */
  private static final String FLAG_MODULE = "MODULE_BULK_PRICE_ADJUST";
  private static final String FLAG_ROLLOUT = "MODULE_AI_ASSISTANT";
  private static final String FLAG_RATE_LIMIT = "RATE_LIMIT_ORDER_API";
  private static final String FLAG_ANNOUNCEMENT = "ANNOUNCEMENT_MAINTENANCE";

  @BeforeEach
  void resetModule() throws Exception {
    try (var c =
            DriverManager.getConnection(pg().getJdbcUrl(), pg().getUsername(), pg().getPassword());
        var s = c.createStatement()) {
      // 变更历史有不可篡改触发器，普通 DELETE 会被它挡住，所以用 TRUNCATE —— 它不触发行级触发器。
      s.execute("truncate platform_flag_exception_history, platform_flag_tenant_exception");
    }
    // 平台全局层是全局的：把出厂配置恢复成种子值，避免上一个用例的改动影响下一个用例。
    try (var c =
            DriverManager.getConnection(pg().getJdbcUrl(), pg().getUsername(), pg().getPassword());
        var s = c.createStatement()) {
      s.execute("truncate platform_flag_history");
      s.execute(
          "update platform_flag_setting set enabled=true,rollout_percent=100.00,"
              + " rollout_salt='starcon-platform-rollout-v1',rate_limit_per_minute=null,"
              + " announcement_title='系统维护通知',"
              + " announcement_body='平台将于近期进行系统维护，维护期间部分功能可能短暂不可用，感谢理解。',"
              + " announcement_from=timestamptz '2026-01-01 00:00:00+00',"
              + " announcement_to=timestamptz '2027-12-31 23:59:59+00',"
              + " note='出厂配置：已发布的维护公告',version=0,updated_at=now()"
              + " where flag_code='"
              + FLAG_ANNOUNCEMENT
              + "'");
      s.execute(
          "update platform_flag_setting set enabled=true,rollout_percent=100.00,"
              + " note='出厂配置：全量开启',version=0,updated_at=now() where flag_code='"
              + FLAG_MODULE
              + "'");
      s.execute(
          "update platform_flag_setting set enabled=true,rollout_percent=10.00,"
              + " note='出厂配置：开启并放量 10%',version=0,updated_at=now() where flag_code='"
              + FLAG_ROLLOUT
              + "'");
      s.execute(
          "update platform_flag_setting set enabled=false,rollout_percent=0.00,"
              + " note='出厂配置：关闭',version=0,updated_at=now() where flag_code='MODULE_ADS_AUTOMATION'");
      s.execute("delete from platform_flag_setting where flag_code='" + FLAG_RATE_LIMIT + "'");
    }
  }

  private TenantTransactions tx() {
    return new TenantTransactions(sql, new TransactionTemplate(manager));
  }

  // ------------------------------------------------------------------
  // 小工具
  // ------------------------------------------------------------------

  private JsonNode definitions(String token) throws Exception {
    return request(token, "GET", "/identity/platform-flags/definitions", null, 200);
  }

  private JsonNode settings(String token) throws Exception {
    return request(token, "GET", "/identity/platform-flags/settings", null, 200);
  }

  private JsonNode evaluate(String token, String key) throws Exception {
    return request(token, "GET", "/identity/platform-flags/evaluate?key=" + key, null, 200).get(0);
  }

  private JsonNode evaluateFor(String token, String key, UUID tenantId) throws Exception {
    return request(
            token,
            "GET",
            "/identity/platform-flags/evaluate?key=" + key + "&tenantId=" + tenantId,
            null,
            200)
        .get(0);
  }

  private JsonNode findSetting(JsonNode list, String code) {
    for (JsonNode n : list) if (code.equals(n.get("flagCode").asText())) return n;
    return null;
  }

  private Map<String, Object> settingInput(
      Boolean enabled, String rolloutPercent, Integer rateLimit, String title) {
    var input = new LinkedHashMap<String, Object>();
    input.put("enabled", enabled);
    input.put("rolloutPercent", new java.math.BigDecimal(rolloutPercent));
    if (rateLimit != null) input.put("rateLimitPerMinute", rateLimit);
    if (title != null) input.put("announcementTitle", title);
    input.put("note", "集成测试配置");
    return input;
  }

  private Map<String, Object> announcementInput(OffsetDateTime from, OffsetDateTime to) {
    var input = new LinkedHashMap<String, Object>();
    input.put("enabled", true);
    input.put("rolloutPercent", new java.math.BigDecimal("0.00"));
    input.put("announcementTitle", "集成测试公告");
    input.put("announcementBody", "公告正文 · 集成测试");
    input.put("announcementFrom", from.toString());
    input.put("announcementTo", to.toString());
    input.put("note", "集成测试公告");
    return input;
  }

  /** 建子租户并返回子租户所有者的 token。 */
  private String createChild(String parent, String code, String ownerUsername) throws Exception {
    request(
        parent,
        "POST",
        "/identity/children",
        Map.of(
            "code", code,
            "name", "平台开关子租户 " + code,
            "ownerUsername", ownerUsername,
            "ownerPassword", "test-member-password"),
        200);
    return loginUser(ownerUsername);
  }

  private UUID tenantOf(String token) throws Exception {
    return UUID.fromString(
        request(token, "GET", "/identity/tenants/current", null, 200).get("tenantId").asText());
  }

  private List<Record> rowsIn(UUID tenant, String statement, Object... bindings) {
    return tx().run(tenant, () -> List.copyOf(sql.fetch(statement, bindings)));
  }

  // ------------------------------------------------------------------
  // 一、全局配置与出厂默认值（fail-closed）
  // ------------------------------------------------------------------

  @Test
  void 平台全局层不带租户维度且出厂默认一律关闭() throws Exception {
    String token = login();

    // 全局层表**不带 tenant_id**：这是「租户没有一份可以自己改的平台开关」的结构性保证。
    for (var table :
        List.of("platform_flag_definition", "platform_flag_setting", "platform_flag_history")) {
      var column =
          sql.fetchOne(
              "select count(*) as n from information_schema.columns"
                  + " where table_name=? and column_name='tenant_id'",
              table);
      assertThat(column.get("n", Integer.class))
          .as("%s 是平台全局层，不得带 tenant_id", table)
          .isZero();
    }

    // 蓝图职责点名的四类都在码表里。
    var list = definitions(token);
    assertThat(list.toString())
        .contains(FLAG_MODULE)
        .contains(FLAG_ROLLOUT)
        .contains(FLAG_RATE_LIMIT)
        .contains(FLAG_ANNOUNCEMENT);

    // 出厂一律 fail-closed：没显式打开的功能不对任何租户开放。
    var ads = findSetting(settings(token), "MODULE_ADS_AUTOMATION");
    assertThat(ads.get("enabled").asBoolean()).isFalse();
    assertThat(ads.get("rolloutPercent").asDouble()).isEqualTo(0.0);
  }

  @Test
  void 限流阈值不给默认值未配置时判定如实返回未配置() throws Exception {
    String token = login();

    // 出厂没有给 RATE_LIMIT 预置配置行 —— 阈值必须由平台显式选择。
    var rate = findSetting(settings(token), FLAG_RATE_LIMIT);
    assertThat(rate.get("configured").asBoolean())
        .as("限流阈值不给默认值：出厂没有这一行")
        .isFalse();
    assertThat(rate.get("rateLimitPerMinute").isNull()).isTrue();

    var verdict = evaluate(token, FLAG_RATE_LIMIT);
    assertThat(verdict.get("effectiveLayer").get("reason").asText()).isEqualTo("NOT_CONFIGURED");
    assertThat(verdict.get("effectiveLayer").get("rateLimitPerMinute").isNull()).isTrue();

    // 平台显式配置之后才有阈值。
    request(
        token,
        "PUT",
        "/identity/platform-flags/settings/" + FLAG_RATE_LIMIT,
        settingInput(true, "0.00", 120, null),
        200);
    var configured = findSetting(settings(token), FLAG_RATE_LIMIT);
    assertThat(configured.get("configured").asBoolean()).isTrue();
    assertThat(configured.get("rateLimitPerMinute").asInt()).isEqualTo(120);

    var after = evaluate(token, FLAG_RATE_LIMIT);
    assertThat(after.get("effectiveLayer").get("reason").asText()).isEqualTo("PLATFORM_THRESHOLD");
    assertThat(after.get("effectiveLayer").get("rateLimitPerMinute").asInt()).isEqualTo(120);
  }

  @Test
  void 限流阈值缺省时写入被拒而不是替平台选一个数字() throws Exception {
    String token = login();
    var input = settingInput(true, "0.00", null, null);
    request(
        token,
        "PUT",
        "/identity/platform-flags/settings/" + FLAG_RATE_LIMIT,
        input,
        400);
    // 拒绝之后库里仍然没有这一行 —— 没有落库就没有「偷偷给了个默认值」。
    assertThat(
            rowsIn(tenantOf(token), "select flag_code from platform_flag_setting where flag_code=?", FLAG_RATE_LIMIT)
                .size())
        .isZero();
  }

  @Test
  void 公告生效期按值写回且窗外不展示() throws Exception {
    String token = login();
    // 窗口取相对当前时刻的区间：不依赖「今天是几号」，测试任何时候跑结论都一样。
    var now = OffsetDateTime.now(java.time.ZoneOffset.UTC);
    var from = now.minusDays(30);
    var to = now.minusDays(1);

    request(
        token,
        "PUT",
        "/identity/platform-flags/settings/" + FLAG_ANNOUNCEMENT,
        announcementInput(from, to),
        200);

    var row = findSetting(settings(token), FLAG_ANNOUNCEMENT);
    // 🔴 时间字段按值断言：偏 8 小时（-28800s）在这里会直接红。
    assertThat(OffsetDateTime.parse(row.get("announcementFrom").asText()))
        .isEqualTo(from);
    assertThat(OffsetDateTime.parse(row.get("announcementTo").asText())).isEqualTo(to);
    assertThat(row.get("announcementTitle").asText()).isEqualTo("集成测试公告");

    // 生效期外：公告不展示，但平台层与租户例外层照样看得见。
    var verdict = evaluate(token, FLAG_ANNOUNCEMENT);
    assertThat(verdict.get("effectiveLayer").get("reason").asText())
        .isEqualTo("ANNOUNCEMENT_OUT_OF_WINDOW");
    assertThat(verdict.get("effectiveLayer").get("announcementActive").asBoolean()).isFalse();
    assertThat(verdict.get("platformLayer").get("announcementTitle").asText())
        .as("平台层照样看得见公告本身")
        .isEqualTo("集成测试公告");

    // 把窗口改成包含当前时刻：公告生效。
    request(
        token,
        "PUT",
        "/identity/platform-flags/settings/" + FLAG_ANNOUNCEMENT,
        announcementInput(now.minusDays(1), now.plusDays(1)),
        200);
    var active = evaluate(token, FLAG_ANNOUNCEMENT);
    assertThat(active.get("effectiveLayer").get("reason").asText()).isEqualTo("ANNOUNCEMENT_ACTIVE");
    assertThat(active.get("effectiveLayer").get("announcementActive").asBoolean()).isTrue();
  }

  // ------------------------------------------------------------------
  // 二、灰度放量的确定性
  // ------------------------------------------------------------------

  @Test
  void 灰度分桶对同一租户恒定且与数据库函数逐位一致() throws Exception {
    String token = login();
    var tenant = tenantOf(token);

    // 同一租户反复算，桶与结论都必须一样 —— 否则用户会看到开关时开时关。
    var first = evaluate(token, FLAG_ROLLOUT);
    for (int i = 0; i < 5; i++) {
      var again = evaluate(token, FLAG_ROLLOUT);
      assertThat(again.get("effectiveLayer").get("rolloutBucket").asInt())
          .as("第 %s 次请求的分桶必须与第一次相同", i)
          .isEqualTo(first.get("effectiveLayer").get("rolloutBucket").asInt());
      assertThat(again.get("effectiveLayer").get("enabled").asBoolean())
          .isEqualTo(first.get("effectiveLayer").get("enabled").asBoolean());
    }

    // Java 侧算法与数据库函数必须一致：口径只能有一份。
    var salt = "starcon-platform-rollout-v1";
    for (int i = 0; i < 8; i++) {
      var probe = UUID.randomUUID();
      var fromDb =
          sql.fetchOne(
                  "select platform_flag_rollout_bucket(?,?) as bucket", salt, probe)
              .get("bucket", Integer.class);
      assertThat(PlatformFlagController.rolloutBucket(salt, probe))
          .as("租户 %s 的分桶：Java 与数据库必须一致", probe)
          .isEqualTo(fromDb);
      assertThat(fromDb).isBetween(0, 9999);
    }
    assertThat(PlatformFlagController.rolloutBucket(salt, tenant))
        .isEqualTo(first.get("effectiveLayer").get("rolloutBucket").asInt());
  }

  @Test
  void 放量比例为零时谁都不命中放量且百分之百时全部命中() throws Exception {
    String token = login();

    request(
        token,
        "PUT",
        "/identity/platform-flags/settings/" + FLAG_ROLLOUT,
        settingInput(true, "0.00", null, null),
        200);
    var none = evaluate(token, FLAG_ROLLOUT);
    assertThat(none.get("effectiveLayer").get("rolloutBasisPoints").asInt()).isZero();
    assertThat(none.get("effectiveLayer").get("enabled").asBoolean()).isFalse();
    assertThat(none.get("effectiveLayer").get("reason").asText()).isEqualTo("ROLLOUT_EXCLUDED");

    request(
        token,
        "PUT",
        "/identity/platform-flags/settings/" + FLAG_ROLLOUT,
        settingInput(true, "100.00", null, null),
        200);
    var all = evaluate(token, FLAG_ROLLOUT);
    assertThat(all.get("effectiveLayer").get("rolloutBasisPoints").asInt()).isEqualTo(10000);
    assertThat(all.get("effectiveLayer").get("enabled").asBoolean()).isTrue();
    assertThat(all.get("effectiveLayer").get("reason").asText()).isEqualTo("ROLLOUT_INCLUDED");
  }

  // ------------------------------------------------------------------
  // 三、三层裁决：平台值 / 租户例外 / 最终生效值
  // ------------------------------------------------------------------

  @Test
  void 判定端点把三层裁决并列给出而不是只给一个布尔值() throws Exception {
    String token = login();
    var tag = suffix();
    var child = createChild(token, "PFC" + tag, "pfc-" + tag.toLowerCase());
    var childTenant = tenantOf(child);

    // 平台层：开启 + 全量放量。
    request(
        token,
        "PUT",
        "/identity/platform-flags/settings/" + FLAG_MODULE,
        settingInput(true, "100.00", null, null),
        200);

    var before = evaluateFor(token, FLAG_MODULE, childTenant);
    assertThat(before.get("platformLayer").get("enabled").asBoolean()).isTrue();
    assertThat(before.get("tenantLayer").get("present").asBoolean()).isFalse();
    assertThat(before.get("effectiveLayer").get("enabled").asBoolean()).isTrue();

    // 平台运营对子租户加一条「禁用」例外。
    request(
        token,
        "POST",
        "/identity/platform-flags/exceptions",
        Map.of(
            "flagCode", FLAG_MODULE,
            "tenantId", childTenant.toString(),
            "reason", "该租户处于风控观察期，暂不开放批量调价"),
        200);

    var after = evaluateFor(token, FLAG_MODULE, childTenant);
    // 三层都必须看得见：平台开着、租户被单独禁用、最终不可用。
    assertThat(after.get("platformLayer").get("enabled").asBoolean())
        .as("平台全局层仍然是开启的")
        .isTrue();
    assertThat(after.get("tenantLayer").get("present").asBoolean()).isTrue();
    assertThat(after.get("tenantLayer").get("effect").asText()).isEqualTo("DISABLED");
    assertThat(after.get("tenantLayer").get("reason").asText()).contains("风控观察期");
    assertThat(after.get("effectiveLayer").get("enabled").asBoolean()).isFalse();
    assertThat(after.get("effectiveLayer").get("reason").asText()).isEqualTo("TENANT_DISABLED");
  }

  @Test
  void 平台禁用的功能租户例外打不开() throws Exception {
    String token = login();
    var tag = suffix();
    var child = createChild(token, "PFD" + tag, "pfd-" + tag.toLowerCase());
    var childTenant = tenantOf(child);

    // 平台把功能关掉。
    request(
        token,
        "PUT",
        "/identity/platform-flags/settings/" + FLAG_MODULE,
        settingInput(false, "100.00", null, null),
        200);
    // 平台运营给这个租户加一条「禁用」例外（唯一允许的效果）。
    request(
        token,
        "POST",
        "/identity/platform-flags/exceptions",
        Map.of("flagCode", FLAG_MODULE, "tenantId", childTenant.toString(), "reason", "风控观察"),
        200);

    var verdict = evaluateFor(token, FLAG_MODULE, childTenant);
    // 平台禁用优先：租户例外**不能**把它打开。
    assertThat(verdict.get("platformLayer").get("enabled").asBoolean()).isFalse();
    assertThat(verdict.get("effectiveLayer").get("enabled").asBoolean()).isFalse();
    assertThat(verdict.get("effectiveLayer").get("reason").asText()).isEqualTo("PLATFORM_DISABLED");
  }

  @Test
  void 租户例外只能禁用不能放宽且不能用于限流与公告() throws Exception {
    String token = login();
    var tag = suffix();
    var child = createChild(token, "PFE" + tag, "pfe-" + tag.toLowerCase());
    var childTenant = tenantOf(child);

    // 例外行的 effect 取值域被 CHECK 钉死：数据库层直接拒绝「打开」。
    assertThatThrownBy(
            () ->
                sql.execute(
                    "insert into platform_flag_tenant_exception"
                        + "(id,tenant_id,flag_code,effect,reason,created_at)"
                        + " values(?,?,?,'ENABLED',?,now())",
                    UUID.randomUUID(),
                    childTenant,
                    FLAG_MODULE,
                    "试图打开"))
        .hasStackTraceContaining("platform_flag_tenant_exception");

    // 限流阈值与公告不接受按租户禁用：禁用一个限流阈值等于放宽限制。
    request(
        token,
        "POST",
        "/identity/platform-flags/exceptions",
        Map.of("flagCode", FLAG_RATE_LIMIT, "tenantId", childTenant.toString(), "reason", "想放开限流"),
        400);
    request(
        token,
        "POST",
        "/identity/platform-flags/exceptions",
        Map.of("flagCode", FLAG_ANNOUNCEMENT, "tenantId", childTenant.toString(), "reason", "想屏蔽公告"),
        400);
  }

  @Test
  void 撤销例外后该租户恢复平台口径() throws Exception {
    String token = login();
    var tag = suffix();
    var child = createChild(token, "PFF" + tag, "pff-" + tag.toLowerCase());
    var childTenant = tenantOf(child);

    var id =
        UUID.fromString(
            request(
                    token,
                    "POST",
                    "/identity/platform-flags/exceptions",
                    Map.of(
                        "flagCode", FLAG_MODULE,
                        "tenantId", childTenant.toString(),
                        "reason", "临时禁用"),
                    200)
                .asText());

    assertThat(evaluateFor(token, FLAG_MODULE, childTenant).get("effectiveLayer").get("enabled").asBoolean())
        .isFalse();

    request(
        token,
        "DELETE",
        "/identity/platform-flags/exceptions/" + id + "?tenantId=" + childTenant,
        null,
        200);

    var restored = evaluateFor(token, FLAG_MODULE, childTenant);
    assertThat(restored.get("tenantLayer").get("present").asBoolean()).isFalse();
    assertThat(restored.get("effectiveLayer").get("enabled").asBoolean()).isTrue();

    // 撤销之后例外行确实不在了（列表为空）。
    var remaining = request(token, "GET", "/identity/platform-flags/exceptions", null, 200);
    assertThat(remaining.size()).isZero();
  }

  // ------------------------------------------------------------------
  // 四、🔴 平台全局表「租户不可写」的独立断言
  // ------------------------------------------------------------------

  @Test
  void 平台全局表对应用角色只读租户写不进去() throws Exception {
    // 应用角色（identity_app）对平台全局层**没有任何写入授权** —— 这是 DB 级事实，不是应用层自觉。
    var grant =
        sql.fetchOne(
            "select count(*) as n from information_schema.role_table_grants"
                + " where grantee='identity_app' and table_name='platform_flag_setting'"
                + " and privilege_type in ('INSERT','UPDATE','DELETE','TRUNCATE')");
    assertThat(grant.get("n", Integer.class))
        .as("identity_app 对平台全局层不应有任何写入授权")
        .isZero();

    // 直接用应用连接写：被数据库挡下。
    assertThatThrownBy(
            () ->
                sql.execute(
                    "insert into platform_flag_setting(flag_code,flag_kind,enabled,rollout_percent)"
                        + " values(?,'MODULE',true,100.00)",
                    FLAG_MODULE))
        .hasStackTraceContaining("permission denied");

    assertThatThrownBy(
            () ->
                sql.execute(
                    "update platform_flag_setting set enabled=true where flag_code=?", FLAG_MODULE))
        .hasStackTraceContaining("permission denied");
  }

  @Test
  void 子租户即便拿到了写权限也改不了平台全局层() throws Exception {
    String token = login();
    var tag = suffix();
    var child = createChild(token, "PFG" + tag, "pfg-" + tag.toLowerCase());

    // create_child 会把 permission 表里的全部权限码授给子租户的 OWNER —— 子租户**确实**有写权限码。
    // 但平台全局层只允许根租户写，所以子租户改不动。
    var childTenant = tenantOf(child);
    assertThat(
            rowsIn(
                    childTenant,
                    "select permission_code from role_permission where tenant_id=? and permission_code=?",
                    childTenant,
                    "identity.platform_flag.update")
                .size())
        .as("子租户的 OWNER 确实拿到了写权限码（create_child 的既有行为）")
        .isPositive();

    request(
        child,
        "PUT",
        "/identity/platform-flags/settings/" + FLAG_MODULE,
        settingInput(false, "0.00", null, null),
        403);

    // 平台全局层的值没有被子租户改掉。
    assertThat(findSetting(settings(token), FLAG_MODULE).get("enabled").asBoolean()).isTrue();
  }

  @Test
  void 没有写权限的角色改不了平台全局层() throws Exception {
    String token = login();
    var tag = suffix();
    // 建一个只有读权限的角色，并用它登录。
    var role =
        UUID.fromString(
            request(
                    token,
                    "POST",
                    "/identity/roles",
                    Map.of(
                        "code", "PFR" + tag,
                        "name", "只读平台参数 " + tag,
                        "status", "ACTIVE",
                        "permissions", List.of("identity.platform_flag.read")),
                    200)
                .asText());
    request(
        token,
        "POST",
        "/identity/members",
        memberInput(
            "pfr-" + tag.toLowerCase(),
            null,
            List.of(Map.of("roleId", role.toString(), "scopeMode", "ALL"))),
        200);
    var reader = loginUser("pfr-" + tag.toLowerCase());

    request(
        reader,
        "PUT",
        "/identity/platform-flags/settings/" + FLAG_MODULE,
        settingInput(false, "0.00", null, null),
        403);
    // 读是允许的。
    assertThat(settings(reader).toString()).contains(FLAG_MODULE);
  }

  // ------------------------------------------------------------------
  // 五、多租户三断言
  // ------------------------------------------------------------------

  @Test
  void 两租户的例外互不可见且按主键直查对方行读到零行() throws Exception {
    String parent = login();
    var tag = suffix();
    var child = createChild(parent, "PFH" + tag, "pfh-" + tag.toLowerCase());
    var parentTenant = tenantOf(parent);
    var childTenant = tenantOf(child);

    request(
        parent,
        "POST",
        "/identity/platform-flags/exceptions",
        Map.of("flagCode", FLAG_MODULE, "tenantId", parentTenant.toString(), "reason", "父租户自身禁用"),
        200);
    request(
        parent,
        "POST",
        "/identity/platform-flags/exceptions",
        Map.of("flagCode", FLAG_MODULE, "tenantId", childTenant.toString(), "reason", "子租户禁用"),
        200);

    // 列表层：各自的例外只有自己的那一条。
    assertThat(request(parent, "GET", "/identity/platform-flags/exceptions", null, 200).toString())
        .contains("父租户自身禁用")
        .doesNotContain("子租户禁用");
    assertThat(request(child, "GET", "/identity/platform-flags/exceptions", null, 200).toString())
        .contains("子租户禁用")
        .doesNotContain("父租户自身禁用");

    // 按主键直查对方的行：必须读到零行（列表过滤对了不代表底下的行不可见）。
    // 直查必须在**被查租户的事务上下文**里做 —— RLS 是 FORCE 的，事务外查不到任何行。
    var parentException =
        rowsIn(parentTenant, "select id from platform_flag_tenant_exception where tenant_id=?", parentTenant)
            .get(0)
            .get("id", UUID.class);
    assertThat(
            rowsIn(
                    childTenant,
                    "select id from platform_flag_tenant_exception where id=?",
                    parentException))
        .as("子租户按主键直查父租户的例外必须读到零行")
        .isEmpty();
    assertThat(
            rowsIn(
                    parentTenant,
                    "select id from platform_flag_tenant_exception where tenant_id=? and flag_code=?",
                    childTenant,
                    FLAG_MODULE))
        .as("父租户读不到子租户的例外行")
        .isEmpty();

    // 跨租户撤销一律 404（403 会泄露「这条记录存在」）。
    request(
        child,
        "DELETE",
        "/identity/platform-flags/exceptions/" + parentException + "?tenantId=" + childTenant,
        null,
        404);
  }

  @Test
  void 伪造租户头无效且写不进别人的数据() throws Exception {
    String parent = login();
    var tag = suffix();
    var child = createChild(parent, "PFI" + tag, "pfi-" + tag.toLowerCase());
    var childTenant = tenantOf(child);
    var parentTenant = tenantOf(parent);

    request(
        parent,
        "POST",
        "/identity/platform-flags/exceptions",
        Map.of("flagCode", FLAG_MODULE, "tenantId", parentTenant.toString(), "reason", "父租户自身禁用"),
        200);

    // 父租户伪造随机租户 id 与子租户**真实**租户 id：可见范围都必须不变。
    for (var forged : List.of(UUID.randomUUID().toString(), childTenant.toString())) {
      var view =
          http.perform(
                  org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                          "/identity/platform-flags/exceptions")
                      .header("Authorization", "Bearer " + parent)
                      .header("X-Tenant-Id", forged))
              .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                  .isOk())
              .andReturn()
              .getResponse()
              .getContentAsString();
      assertThat(view)
          .as("伪造 X-Tenant-Id=" + forged + " 不得改变可见范围")
          .contains("父租户自身禁用")
          .doesNotContain("子租户禁用");
    }

    // 伪造头也写不进别人的库：父租户给自己建例外，却伪造子租户的头 ——
    // 写必须仍然落在**父租户自己的库**里，子租户那边一个字节都没变。
    var forgedWrite =
        http.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                        "/identity/platform-flags/exceptions")
                    .header("Authorization", "Bearer " + parent)
                    .header("X-Tenant-Id", childTenant.toString())
                    .contentType("application/json")
                    .content(
                        json.writeValueAsString(
                            Map.of(
                                "flagCode", FLAG_ROLLOUT,
                                "tenantId", parentTenant.toString(),
                                "reason", "伪造租户头写入"))))
            .andReturn()
            .getResponse();
    assertThat(forgedWrite.getStatus())
        .as("父租户本来就有权给自己建例外，伪造头既不该让他多看到东西，也不该剥夺他已有的权限")
        .isEqualTo(200);
    assertThat(
            rowsIn(
                    parentTenant,
                    "select id from platform_flag_tenant_exception where reason=?",
                    "伪造租户头写入"))
        .as("伪造租户头的写仍然落在父租户自己的库")
        .hasSize(1);
    assertThat(
            rowsIn(
                    childTenant,
                    "select id from platform_flag_tenant_exception where reason=?",
                    "伪造租户头写入"))
        .as("伪造租户头的写不得把行写进子租户的库")
        .isEmpty();
  }

  @Test
  void 连接池归还后多轮交替读写不串租户() throws Exception {
    String parent = login();
    var tag = suffix();
    var child = createChild(parent, "PFJ" + tag, "pfj-" + tag.toLowerCase());
    var parentTenant = tenantOf(parent);
    var childTenant = tenantOf(child);

    // 池大小是 1（见基类），下面每一轮都必然复用同一条物理连接。
    for (int i = 0; i < 4; i++) {
      // 同一租户对同一开关至多一条例外，所以每轮用不同的开关（或先撤销上一轮）。
      // 这里用「建 → 断言 → 撤销」跑完整一轮，撤销路径也顺带被覆盖。
      var parentId =
          UUID.fromString(
              request(
                      parent,
                      "POST",
                      "/identity/platform-flags/exceptions",
                      Map.of(
                          "flagCode", FLAG_MODULE,
                          "tenantId", parentTenant.toString(),
                          "reason", "父租户第 " + i + " 轮"),
                      200)
                  .asText());
      var childId =
          UUID.fromString(
              request(
                      parent,
                      "POST",
                      "/identity/platform-flags/exceptions",
                      Map.of(
                          "flagCode", FLAG_MODULE,
                          "tenantId", childTenant.toString(),
                          "reason", "子租户第 " + i + " 轮"),
                      200)
                  .asText());

      // 父租户读：只见父租户。
      var p = request(parent, "GET", "/identity/platform-flags/exceptions", null, 200).toString();
      assertThat(p)
          .as("第 %s 轮：父租户读必须只见父租户", i)
          .contains("父租户第 " + i + " 轮")
          .doesNotContain("子租户第 " + i + " 轮");

      // 子租户读：只见子租户（连接刚被父租户用过）。
      var c = request(child, "GET", "/identity/platform-flags/exceptions", null, 200).toString();
      assertThat(c)
          .as("第 %s 轮：子租户读必须只见子租户", i)
          .contains("子租户第 " + i + " 轮")
          .doesNotContain("父租户第 " + i + " 轮");

      // 按主键直查：两边的行都还在自己租户里。
      assertThat(
              rowsIn(
                      parentTenant,
                      "select id from platform_flag_tenant_exception where reason=?",
                      "父租户第 " + i + " 轮"))
          .hasSize(1);
      assertThat(
              rowsIn(
                      childTenant,
                      "select id from platform_flag_tenant_exception where reason=?",
                      "子租户第 " + i + " 轮"))
          .hasSize(1);

      // 撤销本轮的两条例外：连接再一次被两个租户交替用过。
      request(
          parent,
          "DELETE",
          "/identity/platform-flags/exceptions/" + parentId + "?tenantId=" + parentTenant,
          null,
          200);
      request(
          parent,
          "DELETE",
          "/identity/platform-flags/exceptions/" + childId + "?tenantId=" + childTenant,
          null,
          200);

      // 撤销之后两边都读不到对方的东西。
      assertThat(
              request(parent, "GET", "/identity/platform-flags/exceptions", null, 200).toString())
          .doesNotContain("子租户第 " + i + " 轮");
      assertThat(
              request(child, "GET", "/identity/platform-flags/exceptions", null, 200).toString())
          .doesNotContain("父租户第 " + i + " 轮");
    }
  }

  @Test
  void 无租户上下文时例外表查不到任何行且均为强制RLS() throws Exception {
    // 事务外（app.current_tenant 未设置）直查必须为空：租户上下文不是「默认放行」。
    assertThat(sql.fetch("select id from platform_flag_tenant_exception")).isEmpty();
    assertThat(sql.fetch("select id from platform_flag_exception_history")).isEmpty();

    for (var table : List.of("platform_flag_tenant_exception", "platform_flag_exception_history")) {
      assertThat(
              sql.fetchOne("select relforcerowsecurity as forced from pg_class where relname=?", table)
                  .get("forced", Boolean.class))
          .as("%s 必须 FORCE RLS", table)
          .isTrue();
    }

    // 平台全局层**不参与**租户隔离：它不带 tenant_id，也没有 RLS 策略。
    assertThat(
            sql.fetchOne(
                    "select relrowsecurity as enabled from pg_class where relname=?",
                    "platform_flag_setting")
                .get("enabled", Boolean.class))
        .as("平台全局层是全平台共读的一份，不启用租户 RLS")
        .isFalse();

    // 顺带证明连接确实来自非超级用户、也没有 bypassrls。
    assertThat(
            sql.fetchOne("select rolbypassrls, rolsuper from pg_roles where rolname=current_user")
                .get("rolbypassrls", Boolean.class))
        .isFalse();
  }

  // ------------------------------------------------------------------
  // 六、变更留痕
  // ------------------------------------------------------------------

  @Test
  void 平台全局层变更留痕且不可修改() throws Exception {
    String token = login();
    request(
        token,
        "PUT",
        "/identity/platform-flags/settings/" + FLAG_MODULE,
        settingInput(true, "50.00", null, null),
        200);

    var history =
        request(token, "GET", "/identity/platform-flags/history?flagCode=" + FLAG_MODULE, null, 200);
    assertThat(history.size()).isPositive();
    var row = history.get(0);
    assertThat(row.get("action").asText()).isIn("CREATED", "UPDATED");
    assertThat(row.get("afterRolloutPercent").asDouble()).isEqualTo(50.0);

    // 历史不可改：数据库层触发器挡下 UPDATE / DELETE。
    assertThatThrownBy(() -> sql.execute("update platform_flag_history set note='篡改'"))
        .hasStackTraceContaining("platform_flag_history");
    assertThatThrownBy(() -> sql.execute("delete from platform_flag_history"))
        .hasStackTraceContaining("platform_flag_history");
  }

  @Test
  void 能力声明如实标出未交付的下游() throws Exception {
    String token = login();
    var capabilities =
        request(token, "GET", "/identity/platform-flags/capabilities", null, 200);
    var text = capabilities.toString();
    assertThat(text)
        .contains("PLATFORM_CONFIG")
        .contains("ROLLOUT")
        .contains("TENANT_DISABLE")
        .contains("RATE_LIMIT")
        .contains("ANNOUNCEMENT")
        .contains("AVAILABILITY_EVALUATION");

    // 下游门控未接入：如实标 available=false 并写明归属模块。
    assertThat(text).contains("DOWNSTREAM_GATING");
    assertThat(text).contains("17.7.1.1");
    assertThat(text).contains("17.7.2.2");
  }
}
