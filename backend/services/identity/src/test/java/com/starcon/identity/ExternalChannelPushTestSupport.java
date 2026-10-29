package com.starcon.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.jooq.Record;

/**
 * 1.3.2.2 外部渠道推送 —— 集成测试的**共用辅助**（**不含任何容器**）。
 *
 * <p>🔴 <b>这里刻意不持有容器</b>：容器由每个具体测试类**自己**持有并在
 * {@code @AfterAll} 里 {@code .stop()}。
 *
 * <p>本仓的 {@code IdentityIntegrationTestBase} 是一个**共享 static 容器**
 * （43 个测试类继承它），那是历史包袱 —— 容器只随 JVM 生命周期回收，
 * 累积到几百个测试类时会把机器压住（2026-10-26 本轮 L2 批次闸实测：
 * 同一 maven session 里跑到中段时机器上同时有 **12** 个 {@code postgres:16-alpine}，
 * swap 用掉 4.2G/5G，测试成片假红）。新写的测试类不再进那个坑：
 * 每个类一个自己的容器，用完就停。
 *
 * <p>所以本类是**纯辅助**：只有请求 / 登录 / 建租户 / 建成员 / 造命中这些夹具方法，
 * 容器与 {@code @DynamicPropertySource} 由子类各写各的。
 */
@SpringBootTest
@AutoConfigureMockMvc
abstract class ExternalChannelPushTestSupport {

  @Autowired MockMvc http;
  @Autowired ObjectMapper json;
  @Autowired org.jooq.DSLContext sql;
  @Autowired org.springframework.transaction.PlatformTransactionManager manager;

  /** 租户事务助手（与生产代码同一条路径）。 */
  com.starcon.tenancy.TenantTransactions tx() {
    return new com.starcon.tenancy.TenantTransactions(
        sql, new org.springframework.transaction.support.TransactionTemplate(manager));
  }

  /**
   * 发一次请求并断言状态码，返回解析后的响应体。
   *
   * <p>每次都带一个随机 {@code X-Tenant-Id}：租户必须来自已认证成员关系，
   * 这个头对判定没有任何影响，测试里保留它正是为了证明这一点。
   */
  JsonNode request(String token, String method, String path, Object body, int expected)
      throws Exception {
    var builder =
        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request(
                org.springframework.http.HttpMethod.valueOf(method), path)
            .header("Authorization", "Bearer " + token)
            .header("X-Tenant-Id", UUID.randomUUID())
            .contentType("application/json");
    if (body != null) builder.content(json.writeValueAsString(body));
    var response = http.perform(builder).andReturn().getResponse();
    String text = response.getContentAsString();
    assertThat(response.getStatus())
        .as("%s %s 期望 %d，实际 %d，响应体：%s", method, path, expected, response.getStatus(), text)
        .isEqualTo(expected);
    return text.isEmpty() ? json.getNodeFactory().nullNode() : json.readTree(text);
  }

  /** 以本地所有者（OWNER，拥有全部权限码）身份登录，返回 Bearer token。 */
  String login() throws Exception {
    var result =
        http.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                        "/auth/login")
                    .contentType("application/json")
                    .content("{\"username\":\"local-admin\",\"password\":\"test-admin-password\"}"))
            .andExpect(
                org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andReturn();
    return json.readTree(result.getResponse().getContentAsString()).get("token").asText();
  }

  /** 以成员身份登录（成员口令固定）。 */
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

  /** 随机标记，避免不同测试之间的编码撞唯一约束。 */
  String suffix() {
    return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
  }

  /** 当前会话所属租户 id。 */
  UUID tenantOf(String token) throws Exception {
    return UUID.fromString(
        request(token, "GET", "/identity/tenants/current", null, 200).get("tenantId").asText());
  }

  /**
   * 当前会话的成员 id。
   *
   * <p>这条 id 是**收件人**，推送的偏好过滤按它来。
   *
   * <p>🔴 <b>不能直查 {@code auth_session} / {@code membership}</b>：会话是全局认证记录、
   * {@code membership} 带 RLS，应用角色对两者都**没有直接读权限**（这是有意的设计）。
   * 走的是本仓本来就授给应用角色的两个 SECURITY DEFINER 函数：
   * {@code session_access(token_hash)} 给出 user_id 与 tenant_id，
   * {@code memberships_for(user_id)} 给出该用户在那个租户里的 membership id。
   */
  UUID membershipOf(String token) {
    String hash = IdentityController.hash(token);
    Record access =
        sql.fetchOne(
            "select user_id, tenant_id from session_access(?::varchar)", hash);
    return sql.fetchOne(
            "select id from memberships_for(?::uuid) where tenant_id = ?::uuid",
            access.get("user_id", UUID.class),
            access.get("tenant_id", UUID.class))
        .get("id", UUID.class);
  }

  /**
   * 另建一个**独立租户**并返回它的 OWNER token。
   *
   * <p>🔴 <b>每个测试用例一个自己的租户</b>：同一个类里的各个测试方法仍然共用同一个容器、
   * 同一个数据库 —— 如果大家都用 {@code local-admin} 那个租户，那么「A 测试建的渠道」
   * 会出现在「B 测试的清单里」，断言就没法说「本租户还没有渠道」。
   */
  String isolatedTenant(String tag) throws Exception {
    return createTenant(login(), tag, "ecp");
  }

  /** 另建一个子租户（用来验「两租户互不可见」）。 */
  String childTenant(String parentToken, String tag) throws Exception {
    return createTenant(parentToken, tag, "ecc");
  }

  /** 建租户（编码与账号前缀按 prefix 区分，避免同一个编码用两次）。 */
  private String createTenant(String parentToken, String tag, String prefix) throws Exception {
    String username = "ec" + prefix + "-" + tag.toLowerCase();
    request(
        parentToken,
        "POST",
        "/identity/children",
        Map.of(
            "code",
            prefix.toUpperCase() + tag,
            "name",
            "推送租户 " + prefix + tag,
            "ownerUsername",
            username,
            "ownerPassword",
            "test-member-password"),
        200);
    return loginUser(username);
  }

  /**
   * 在当前租户里建一个带指定权限与范围的成员，并返回它的登录 token。
   *
   * @param permissions 该角色持有的权限码
   * @param scopeMode 授权范围（ALL / DEPARTMENT / SELF）
   */
  String memberWith(
      String ownerToken, String tag, List<String> permissions, String scopeMode) throws Exception {
    UUID role = createRole(ownerToken, tag, permissions);
    addMember(ownerToken, "ecm-" + tag.toLowerCase(), role, scopeMode, 200);
    return loginUser("ecm-" + tag.toLowerCase());
  }

  /** 建一个角色，返回它的 id。 */
  UUID createRole(String ownerToken, String tag, List<String> permissions) throws Exception {
    return UUID.fromString(
        request(
                ownerToken,
                "POST",
                "/identity/roles",
                Map.of(
                    "code",
                    "EC" + tag,
                    "name",
                    "推送角色 " + tag,
                    "status",
                    "ACTIVE",
                    "permissions",
                    permissions),
                200)
            .asText());
  }

  /** 在当前租户里加一个成员（可断言期望状态码）。 */
  void addMember(
      String ownerToken, String username, UUID roleId, String scopeMode, int expected)
      throws Exception {
    Map<String, Object> member = new HashMap<>();
    member.put("username", username);
    member.put("password", "test-member-password");
    member.put("status", "ACTIVE");
    member.put("assignments", List.of(Map.of("roleId", roleId.toString(), "scopeMode", scopeMode)));
    request(ownerToken, "POST", "/identity/members", member, expected);
  }

  /** 在当前租户里建一个**没有任何权限**的成员（用来验 403）。 */
  String memberWithout(String ownerToken, String tag) throws Exception {
    return memberWith(ownerToken, tag, List.of(), "ALL");
  }

  // ------------------------------------------------------------------
  // 业务夹具
  // ------------------------------------------------------------------

  /** 登记一个渠道（**secret manager 引用**，不是地址本体）。 */
  JsonNode registerChannel(String token, String channelCode, String displayName, String secretRef)
      throws Exception {
    return request(
        token,
        "POST",
        "/identity/external-channel-push/channels",
        Map.of(
            "channelCode", channelCode,
            "displayName", displayName,
            "secretRef", secretRef),
        200);
  }

  /**
   * 造一条**真命中**（1.3.2.1 的命中记录；写入方是引擎的授权写路径，本模块只读）。
   *
   * <p>⚠️ 直插是**引擎将来会做的事**（今天的引擎未交付）—— 但写入必须走
   * **租户事务**（RLS 兜底），不能走事务外。
   */
  UUID insertHit(UUID tenantId, String resolution, String ruleCode) {
    return tx()
        .run(
            tenantId,
            () -> {
              UUID id = UUID.randomUUID();
              java.time.OffsetDateTime hitAt =
                  java.time.OffsetDateTime.ofInstant(Instant.now(), ZoneOffset.UTC);
              sql.execute(
                  "insert into alert_rule_hit(id,tenant_id,rule_code,object_type,object_ref,"
                      + "metric_code,observed_value,base_value,threshold,comparison,change_basis,"
                      + "time_window,timezone,resolution,reason,hit_at)"
                      + " values (?::uuid,?::uuid,?::varchar,'SKU',?::varchar,'STOCK_COVER_DAYS',"
                      + "?::numeric,null,?::numeric,'LT','NONE','INSTANT','Asia/Shanghai',"
                      + "?::varchar,?::varchar,?::timestamptz)",
                  id,
                  tenantId,
                  ruleCode,
                  "SKU-" + suffix(),
                  "HIT".equals(resolution) ? new java.math.BigDecimal("9.5") : null,
                  new java.math.BigDecimal("14"),
                  resolution,
                  "HIT".equals(resolution) ? null : "上游没数据，算不出来",
                  hitAt);
              return id;
            });
  }

  /** 保存收件人的通知偏好（**渠道集合 + 免打扰时段 + 时区**）。 */
  void savePreferences(
      String token, String type, List<String> channels, List<String> windows, String timezone)
      throws Exception {
    Map<String, Object> input = new HashMap<>();
    if (channels != null) {
      input.put(
          "types",
          List.of(Map.of("type", type, "channels", channels)));
    }
    if (windows != null) input.put("windows", windows);
    if (timezone != null) input.put("timezone", timezone);
    request(token, "PUT", "/identity/notification-preferences", input, 200);
  }

  /** 当前时刻在**某时区**的墙上时间（HH:mm）。 */
  String localNow(ZoneOffset zone, DateTimeFormatter formatter) {
    return formatter.format(Instant.now().atOffset(zone));
  }

  /** 造一条「覆盖当下 ±1 小时」的免打扰时段（**与星期无关**：只看当地时刻）。 */
  List<String> quietWindowAroundNow(ZoneOffset zone) {
    var formatter = DateTimeFormatter.ofPattern("HH:mm");
    var now = Instant.now().atOffset(zone);
    return List.of(formatter.format(now.minusHours(1)) + "-" + formatter.format(now.plusHours(1)));
  }
}
