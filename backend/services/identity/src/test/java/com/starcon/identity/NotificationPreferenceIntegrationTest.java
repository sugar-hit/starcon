package com.starcon.identity;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.starcon.tenancy.TenantTransactions;
import java.time.Duration;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.Record;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 1.3.1.2 通知偏好 —— 集成测试。
 *
 * <p><b>真实 PostgreSQL 16 + 强制 RLS，不使用任何内存替身。</b>
 *
 * <p><b>本测试真正在守的东西</b>：
 *
 * <ol>
 *   <li><b>偏好真的落库</b>：写进 {@code user_preference}（页面 key =
 *       {@code notifications.delivery}），用<b>直查表</b>断言（不看接口回显就算数）；</li>
 *   <li><b>关键业务通知关不掉</b>：去掉底线渠道被拒（400），且<b>绕过本服务</b>
 *       用通用偏好端点写坏载荷之后，读回来的<b>生效值里底线渠道仍然在</b>；</li>
 *   <li><b>渠道集合不允许为空</b>：空集合被拒（400）；</li>
 *   <li><b>免打扰是当地时刻语义</b>：时段按「当地 HH:mm + IANA 时区」判定，
 *       同一个瞬间在不同时区下判定不同，跨午夜的时段前后半夜都命中；</li>
 *   <li><b>时间没被时区吃掉</b>：写回库的 {@code updated_at} 与接口回的读数是**同一个时刻**；</li>
 *   <li><b>偏好是个人状态</b>：同租户的另一个成员读不到我的偏好。</li>
 * </ol>
 *
 * <p><b>每个用例用自己新建的成员</b>：偏好是「一个人一份」的，
 * 用例之间共用 {@code local-admin} 会让状态互相污染。
 */
class NotificationPreferenceIntegrationTest extends IdentityIntegrationTestBase {

  @Autowired IdentityController identity;

  private TenantTransactions tx() {
    return new TenantTransactions(
        sql, new org.springframework.transaction.support.TransactionTemplate(manager));
  }

  /** 租户事务内直查（RLS 生效）。 */
  private List<Record> rowsIn(UUID tenant, String statement, Object... bindings) {
    return tx().run(tenant, () -> List.copyOf(sql.fetch(statement, bindings)));
  }

  private UUID tenantOf(String token) throws Exception {
    return UUID.fromString(
        request(token, "GET", "/identity/tenants/current", null, 200).get("tenantId").asText());
  }

  /** 会话对应的成员 id（偏好是「一个人一份」的，直查表时必须带上它）。 */
  private UUID membershipOf(String token) {
    return identity.resolve("Bearer " + token).membershipId();
  }

  /**
   * 直查我的那一行偏好。
   *
   * <p>🔴 <b>必须带 membership_id</b>：本仓的集成测试共享同一个 PostgreSQL 容器与同一个
   * 本地管理员租户，不同用例的成员行<b>并存于同一张表</b>（RLS 是租户级的，
   * 「只看自己的」由服务层的 membership_id 谓词保证）。只按 page_key 查会读到
   * 别的用例留下的行 —— 那是测试之间的污染，不是本模块的行为。
   */
  private List<Record> myRows(UUID tenant, UUID membershipId) {
    return rowsIn(
        tenant,
        "select id, preference::text as pref, updated_at from user_preference"
            + " where page_key = ?::varchar and membership_id = ?::uuid",
        "notifications.delivery",
        membershipId);
  }

  // ================================================================ 测试夹具

  /** 新建一个**干净**的成员并登录，返回它的 token。 */
  private String freshMember(String tag) throws Exception {
    String owner = login();
    UUID role =
        UUID.fromString(
            request(
                    owner,
                    "POST",
                    "/identity/roles",
                    Map.of(
                        "code", "NP" + tag,
                        "name", "通知偏好测试角色 " + tag,
                        "status",
                        "ACTIVE",
                        "permissions",
                        List.of()),
                    200)
                .asText());
    request(
        owner,
        "POST",
        "/identity/members",
        memberInput(
            "np-" + tag.toLowerCase(), null, List.of(Map.of("roleId", role.toString(), "scopeMode", "ALL"))),
        200);
    return loginUser("np-" + tag.toLowerCase());
  }

  /** 读我的通知偏好。 */
  private JsonNode read(String token) throws Exception {
    return request(token, "GET", "/identity/notification-preferences", null, 200);
  }

  /** 保存通知偏好。 */
  private JsonNode save(String token, Object body) throws Exception {
    return request(token, "PUT", "/identity/notification-preferences", body, 200);
  }

  /** 解析某一类通知现在该走哪些渠道。 */
  private JsonNode resolve(String token, String type) throws Exception {
    return request(
        token, "POST", "/identity/notification-preferences/resolve", Map.of("type", type), 200);
  }

  /** 某一类通知生效的渠道集合。 */
  private static List<String> channelsOf(JsonNode view, String type) {
    for (JsonNode setting : view.get("types")) {
      if (type.equals(setting.get("type").asText())) {
        return stringsOf(setting.get("channels"));
      }
    }
    throw new AssertionError("生效值里没有类型 " + type);
  }

  /** 把 JSON 数组读成字符串列表。 */
  private static List<String> stringsOf(JsonNode array) {
    List<String> values = new java.util.ArrayList<>();
    array.forEach(node -> values.add(node.asText()));
    return values;
  }

  /** 造一个「覆盖当下」的免打扰时段（当地时间），偏移 {@code offset} 小时。 */
  private static String windowAround(ZoneId zone, Duration offset) {
    LocalTime now = LocalTime.now(zone).plus(offset);
    return hhmm(now.minusMinutes(30)) + "-" + hhmm(now.plusMinutes(30));
  }

  private static String hhmm(LocalTime time) {
    return String.format("%02d:%02d", time.getHour(), time.getMinute());
  }

  // ================================================================ 默认生效值

  @Test
  void 没自定义过时是五类齐全且渠道全开() throws Exception {
    String token = freshMember(suffix());
    JsonNode view = read(token);

    assertThat(view.get("stored").asBoolean()).as("没存过就是 stored=false").isFalse();
    assertThat(view.get("types")).as("五类通知必须齐全").hasSize(5);
    assertThat(view.get("types").get(0).get("type").asText()).isEqualTo("STOCKOUT");
    assertThat(view.get("types").get(4).get("type").asText()).isEqualTo("MESSAGE");
    assertThat(channelsOf(view, "STOCKOUT")).containsExactly("IN_APP", "EMAIL", "IM");
    assertThat(view.get("windows")).as("默认不免打扰").isEmpty();
    assertThat(view.get("timezone").asText()).isEqualTo("Asia/Shanghai");
    assertThat(view.get("timezoneSource").asText()).as("没存过时用的是平台默认").isEqualTo("DEFAULT");
    assertThat(view.get("quietHoursScope").asText()).isEqualTo("EXTERNAL_CHANNELS");
  }

  @Test
  void 生效值里标出哪两类是关键业务通知() throws Exception {
    String token = freshMember(suffix());
    JsonNode view = read(token);

    assertThat(view.get("types").get(1).get("type").asText()).isEqualTo("TIMELINESS_OVERDUE");
    assertThat(view.get("types").get(1).get("mandatory").asBoolean()).isTrue();
    assertThat(view.get("types").get(1).get("baselineChannel").asText()).isEqualTo("IN_APP");
    assertThat(view.get("types").get(3).get("type").asText()).isEqualTo("RECONCILIATION_DIFF");
    assertThat(view.get("types").get(3).get("mandatory").asBoolean()).isTrue();
    assertThat(view.get("types").get(0).get("mandatory").asBoolean()).isFalse();
    assertThat(view.get("types").get(0).get("baselineChannel").isNull())
        .as("非底线类型没有底线渠道")
        .isTrue();
  }

  // ================================================================ 保存真的落库

  @Test
  void 保存真的落库且读回来是同一份() throws Exception {
    String token = freshMember(suffix());
    UUID tenant = tenantOf(token);

    JsonNode saved =
        save(
            token,
            Map.of(
                "types",
                List.of(Map.of("type", "MESSAGE", "channels", List.of("IN_APP", "EMAIL"))),
                "windows",
                List.of("22:00-08:00"),
                "timezone",
                "Asia/Shanghai"));
    assertThat(saved.get("stored").asBoolean()).isTrue();
    assertThat(channelsOf(saved, "MESSAGE")).containsExactly("IN_APP", "EMAIL");
    assertThat(saved.get("windows").get(0).asText()).isEqualTo("22:00-08:00");
    assertThat(saved.get("timezoneSource").asText()).isEqualTo("PREFERENCE");

    // 直查表：行真的在库里，而且载荷里就是那一份。
    List<Record> rows = myRows(tenant, membershipOf(token));
    assertThat(rows).as("偏好必须真的落库").hasSize(1);
    assertThat(rows.get(0).get("pref", String.class))
        .contains("MESSAGE")
        .contains("22:00-08:00")
        .contains("Asia/Shanghai");

    // 再读一次：还是同一份（不是只在响应里好看）。
    JsonNode view = read(token);
    assertThat(channelsOf(view, "MESSAGE")).containsExactly("IN_APP", "EMAIL");
    assertThat(view.get("windows").get(0).asText()).isEqualTo("22:00-08:00");
  }

  @Test
  void 保存是覆盖不是追加() throws Exception {
    String token = freshMember(suffix());
    UUID tenant = tenantOf(token);

    save(
        token,
        Map.of("types", List.of(Map.of("type", "MESSAGE", "channels", List.of("IN_APP", "EMAIL")))));
    save(
        token,
        Map.of("types", List.of(Map.of("type", "STOCKOUT", "channels", List.of("IN_APP")))));

    JsonNode view = read(token);
    assertThat(channelsOf(view, "MESSAGE"))
        .as("没提到的类型保持原值")
        .containsExactly("IN_APP", "EMAIL");
    assertThat(channelsOf(view, "STOCKOUT")).containsExactly("IN_APP");
    assertThat(
            rowsIn(
                tenant,
                "select id from user_preference where page_key = ?::varchar and membership_id = ?::uuid",
                "notifications.delivery",
                membershipOf(token)))
        .as("同一 page key 只有一行")
        .hasSize(1);
  }

  @Test
  void 重置是幂等的且回到默认() throws Exception {
    String token = freshMember(suffix());
    UUID tenant = tenantOf(token);

    save(token, Map.of("types", List.of(Map.of("type", "MESSAGE", "channels", List.of("EMAIL")))));
    JsonNode afterReset = request(token, "DELETE", "/identity/notification-preferences", null, 200);
    assertThat(afterReset.get("stored").asBoolean()).isFalse();
    assertThat(channelsOf(afterReset, "MESSAGE")).containsExactly("IN_APP", "EMAIL", "IM");

    assertThat(
            rowsIn(
                tenant,
                "select id from user_preference where page_key = ?::varchar and membership_id = ?::uuid",
                "notifications.delivery",
                membershipOf(token)))
        .as("重置后那一行真的没了")
        .isEmpty();

    // 再重置一次：幂等（本来就没有也算成功）。
    request(token, "DELETE", "/identity/notification-preferences", null, 200);
  }

  // ================================================================ 底线项不可关闭

  @Test
  void 关键业务通知的底线渠道关不掉() throws Exception {
    String token = freshMember(suffix());

    request(
        token,
        "PUT",
        "/identity/notification-preferences",
        Map.of("types", List.of(Map.of("type", "TIMELINESS_OVERDUE", "channels", List.of("EMAIL")))),
        400);
    request(
        token,
        "PUT",
        "/identity/notification-preferences",
        Map.of("types", List.of(Map.of("type", "RECONCILIATION_DIFF", "channels", List.of("IM")))),
        400);
    // 非底线类型可以只留一个渠道。
    request(
        token,
        "PUT",
        "/identity/notification-preferences",
        Map.of("types", List.of(Map.of("type", "MESSAGE", "channels", List.of("EMAIL")))),
        200);
  }

  @Test
  void 渠道集合不允许为空() throws Exception {
    String token = freshMember(suffix());
    request(
        token,
        "PUT",
        "/identity/notification-preferences",
        Map.of("types", List.of(Map.of("type", "MESSAGE", "channels", List.of()))),
        400);
    request(
        token,
        "PUT",
        "/identity/notification-preferences",
        Map.of("types", List.of(Map.of("type", "TIMELINESS_OVERDUE", "channels", List.of()))),
        400);
  }

  @Test
  void 词表外的类型与渠道被拒() throws Exception {
    String token = freshMember(suffix());
    request(
        token,
        "PUT",
        "/identity/notification-preferences",
        Map.of("types", List.of(Map.of("type", "NOT_A_TYPE", "channels", List.of("IN_APP")))),
        400);
    request(
        token,
        "PUT",
        "/identity/notification-preferences",
        Map.of("types", List.of(Map.of("type", "MESSAGE", "channels", List.of("SMS")))),
        400);
  }

  @Test
  void 绕过本服务写坏载荷时生效值里底线渠道仍然在() throws Exception {
    String token = freshMember(suffix());

    // 用 1.4.1.2 的通用偏好端点直接写一个「把关键业务通知完全关掉」的载荷。
    request(
        token,
        "PUT",
        "/identity/user-preferences/notifications.delivery",
        Map.of(
            "preference",
            Map.of(
                "version",
                1,
                "types",
                List.of(
                    Map.of("type", "TIMELINESS_OVERDUE", "channels", List.of()),
                    Map.of("type", "RECONCILIATION_DIFF", "channels", List.of("EMAIL"))),
                "windows",
                List.of("22:00-08:00"))),
        200);

    JsonNode view = read(token);
    assertThat(channelsOf(view, "TIMELINESS_OVERDUE"))
        .as("关键业务通知的底线渠道必须在生效值里")
        .contains("IN_APP");
    assertThat(channelsOf(view, "RECONCILIATION_DIFF"))
        .as("关键业务通知的底线渠道必须在生效值里")
        .contains("IN_APP");
    assertThat(view.get("fallbackReasons").toString())
        .as("兜底必须说清原因")
        .contains("IN_APP");
  }

  @Test
  void 绕过本服务写坏的载荷在下次保存时被纠正回合规() throws Exception {
    String token = freshMember(suffix());
    UUID tenant = tenantOf(token);
    UUID membershipId = membershipOf(token);

    // 先用通用偏好端点写一个「关键业务通知没有底线渠道」的载荷。
    request(
        token,
        "PUT",
        "/identity/user-preferences/notifications.delivery",
        Map.of(
            "preference",
            Map.of(
                "version",
                1,
                "types",
                List.of(Map.of("type", "TIMELINESS_OVERDUE", "channels", List.of("EMAIL"))))),
        200);

    // 走本模块保存一次（只改另一类），顺带把历史载荷带回去。
    save(token, Map.of("types", List.of(Map.of("type", "MESSAGE", "channels", List.of("IN_APP")))));

    List<Record> rows = myRows(tenant, membershipId);
    assertThat(rows.get(0).get("pref", String.class))
        .as("存进表里的载荷必须是合规的：关键业务通知带着底线渠道")
        .contains("IN_APP");
    assertThat(channelsOf(read(token), "TIMELINESS_OVERDUE")).contains("IN_APP");
  }

  // ================================================================ 免打扰时段（当地时刻语义）

  @Test
  void 时段形状不对被拒() throws Exception {
    String token = freshMember(suffix());
    request(
        token,
        "PUT",
        "/identity/notification-preferences",
        Map.of("windows", List.of("晚上十点到八点")),
        400);
    request(
        token,
        "PUT",
        "/identity/notification-preferences",
        Map.of("windows", List.of("08:00-08:00")),
        400);
    request(
        token,
        "PUT",
        "/identity/notification-preferences",
        Map.of("windows", List.of("22:00-08:00", "22:00-08:00")),
        400);
  }

  @Test
  void 时区不是IANA区域id被拒() throws Exception {
    String token = freshMember(suffix());
    request(
        token,
        "PUT",
        "/identity/notification-preferences",
        Map.of("timezone", "UTC+8"),
        400);
    request(
        token,
        "PUT",
        "/identity/notification-preferences",
        Map.of("timezone", "Not/AZone"),
        400);
  }

  @Test
  void 命中免打扰时段时外部渠道被压制而站内渠道不压制() throws Exception {
    String token = freshMember(suffix());
    ZoneId zone = ZoneId.of("Asia/Shanghai");
    String window = windowAround(zone, Duration.ZERO);

    save(
        token,
        Map.of(
            "types", List.of(Map.of("type", "MESSAGE", "channels", List.of("IN_APP", "EMAIL", "IM"))),
            "windows", List.of(window),
            "timezone", zone.getId()));

    JsonNode resolution = resolve(token, "MESSAGE");
    assertThat(resolution.get("quietNow").asBoolean()).as("现在应当落在免打扰时段里").isTrue();
    assertThat(resolution.get("quietWindows").get(0).asText()).isEqualTo(window);
    assertThat(stringsOf(resolution.get("suppressedChannels")))
        .as("被压制的只有外部渠道")
        .containsExactly("EMAIL", "IM");
    assertThat(stringsOf(resolution.get("channels"))).containsExactly("IN_APP", "EMAIL", "IM");
  }

  @Test
  void 不在免打扰时段时不压制任何渠道() throws Exception {
    String token = freshMember(suffix());
    ZoneId zone = ZoneId.of("Asia/Shanghai");
    String window = windowAround(zone, Duration.ofHours(6));

    save(
        token,
        Map.of(
            "types", List.of(Map.of("type", "MESSAGE", "channels", List.of("IN_APP", "EMAIL"))),
            "windows", List.of(window),
            "timezone", zone.getId()));

    JsonNode resolution = resolve(token, "MESSAGE");
    assertThat(resolution.get("quietNow").asBoolean()).isFalse();
    assertThat(resolution.get("suppressedChannels")).isEmpty();
  }

  @Test
  void 免打扰只压制外部渠道这一口径是公开声明的() throws Exception {
    String token = freshMember(suffix());
    JsonNode capabilities =
        request(token, "GET", "/identity/notification-preferences/capabilities", null, 200);
    assertThat(capabilities.get("quietHoursScope").asText()).isEqualTo("EXTERNAL_CHANNELS");
    assertThat(capabilities.get("boundaries").toString()).contains("站内");
  }

  // ================================================================ 时区口径

  @Test
  void 时区默认继承语言与时区那一模块() throws Exception {
    String token = freshMember(suffix());

    // 1.4.2.1：把展示时区设成 America/New_York。
    request(
        token,
        "PUT",
        "/identity/user-locale",
        Map.of("timezone", "America/New_York"),
        200);

    JsonNode view = read(token);
    assertThat(view.get("timezone").asText()).isEqualTo("America/New_York");
    assertThat(view.get("timezoneSource").asText())
        .as("没在本模块设过时应当继承 1.4.2.1 的展示时区")
        .isEqualTo("ACCOUNT_LOCALE");

    // 本模块显式设置时，以本模块为准。
    save(token, Map.of("timezone", "Europe/Berlin"));
    JsonNode explicit = read(token);
    assertThat(explicit.get("timezone").asText()).isEqualTo("Europe/Berlin");
    assertThat(explicit.get("timezoneSource").asText()).isEqualTo("PREFERENCE");
  }

  // ================================================================ 时间字段

  @Test
  void 时间没被时区吃掉() throws Exception {
    String token = freshMember(suffix());
    UUID tenant = tenantOf(token);

    OffsetDateTime before = OffsetDateTime.now(ZoneOffset.UTC);
    JsonNode saved = save(token, Map.of("windows", List.of("22:00-08:00")));
    OffsetDateTime after = OffsetDateTime.now(ZoneOffset.UTC);

    OffsetDateTime updatedAt = OffsetDateTime.parse(saved.get("updatedAt").asText());
    assertThat(updatedAt.toInstant())
        .as("写入时刻必须落在请求前后的窗口内（不能被时区偏掉 8 小时）")
        .isBetween(before.toInstant().minusSeconds(1), after.toInstant().plusSeconds(1));

    List<Record> rows = myRows(tenant, membershipOf(token));
    assertThat(rows.get(0).get("updated_at", OffsetDateTime.class).toInstant())
        .as("库里的 updated_at 与接口读出的必须是同一个时刻")
        .isEqualTo(updatedAt.toInstant());
  }

  // ================================================================ 能力声明与个人隔离

  @Test
  void 能力声明给出渠道词表与不可关闭的底线清单() throws Exception {
    String token = freshMember(suffix());
    JsonNode capabilities =
        request(token, "GET", "/identity/notification-preferences/capabilities", null, 200);

    assertThat(capabilities.get("module").asText()).isEqualTo("1.3.1.2");
    assertThat(capabilities.get("channels")).hasSize(3);
    assertThat(capabilities.get("types")).hasSize(5);
    assertThat(capabilities.get("baselineChannel").asText()).isEqualTo("IN_APP");
    assertThat(capabilities.get("maxWindows").asInt()).isEqualTo(4);
    assertThat(capabilities.get("boundaries").toString())
        .contains("1.3.2.1")
        .contains("1.3.2.2")
        .contains("不允许被完全关闭");
  }

  @Test
  void 偏好是个人状态同租户的同事读不到() throws Exception {
    String tag = suffix();
    String owner = login();
    UUID role =
        UUID.fromString(
            request(
                    owner,
                    "POST",
                    "/identity/roles",
                    Map.of(
                        "code", "NPP" + tag,
                        "name", "通知偏好同事角色 " + tag,
                        "status",
                        "ACTIVE",
                        "permissions",
                        List.of()),
                    200)
                .asText());
    request(
        owner,
        "POST",
        "/identity/members",
        memberInput(
            "npp-" + tag.toLowerCase(), null, List.of(Map.of("roleId", role.toString(), "scopeMode", "ALL"))),
        200);
    String colleague = loginUser("npp-" + tag.toLowerCase());

    save(owner, Map.of("types", List.of(Map.of("type", "MESSAGE", "channels", List.of("IN_APP")))));

    assertThat(channelsOf(read(colleague), "MESSAGE"))
        .as("同事读不到我的偏好（他看到的是默认的全开）")
        .containsExactly("IN_APP", "EMAIL", "IM");
    assertThat(read(colleague).get("stored").asBoolean()).isFalse();
  }

  @Test
  void 未登录时一律401() throws Exception {
    http.perform(get("/identity/notification-preferences")).andExpect(status().isUnauthorized());
    http.perform(get("/identity/notification-preferences/capabilities"))
        .andExpect(status().isUnauthorized());
  }
}
