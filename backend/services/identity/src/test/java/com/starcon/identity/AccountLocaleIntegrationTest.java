package com.starcon.identity;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.starcon.tenancy.TenantTransactions;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.Record;
import org.junit.jupiter.api.Test;

/**
 * 1.4.2.1 语言与时区 —— 业务行为集成测试（真实 PostgreSQL 16）。
 *
 * <p>覆盖本模块真正承诺的东西：
 *
 * <ol>
 *   <li><b>读 / 写 / 重置</b>三个动作都落到 {@code user_preference} 的一行上（upsert）。</li>
 *   <li><b>语义校验</b>：非法时区与没有语言包的语言被拒（400），且<b>响应体里带得清原因</b>。</li>
 *   <li><b>防御性回落</b>：绕过本服务用通用偏好端点写了畸形值时，读回来回落并说清原因。</li>
 *   <li><b>不改变存储时区</b>：改展示时区不会让写入的时刻漂移 13 小时，
 *       写回的时刻与直查库里的绝对时刻一致（不是 {@code Timestamp.valueOf} 那种静默 −28800s）。</li>
 *   <li><b>能力声明如实</b>：界面语言当前不可用这件事必须报出来，不能报一个假开关。</li>
 * </ol>
 *
 * <p><b>所有行数断言都按 membership_id 收窄</b>：同一个租户里别的成员（本类的其它用例、
 * 别的测试类）也会写 {@code account.locale} 这一页，不收窄就会把别人的行算进来。
 */
class AccountLocaleIntegrationTest extends IdentityIntegrationTestBase {

  private TenantTransactions tx() {
    return new TenantTransactions(
        sql, new org.springframework.transaction.support.TransactionTemplate(manager));
  }

  private UUID tenantOf(String token) throws Exception {
    return UUID.fromString(
        request(token, "GET", "/identity/tenants/current", null, 200).get("tenantId").asText());
  }

  /**
   * 当前会话的 membership_id（租户内的「这个人」）。
   *
   * <p>走公开的 {@code GET /auth/memberships} 而不是直接查表：{@code app_user} 对应用角色
   * 是**不可读**的（账号是凭据面，不是业务面），硬查会撞上「permission denied」。
   */
  private UUID membershipOf(String token) throws Exception {
    UUID tenant = tenantOf(token);
    for (JsonNode membership : request(token, "GET", "/auth/memberships", null, 200)) {
      if (tenant.toString().equals(membership.get("tenantId").asText())) {
        return UUID.fromString(membership.get("id").asText());
      }
    }
    throw new IllegalStateException("会话里找不到当前租户的成员关系");
  }

  /** 我（这个成员）在本租户里、页面 key = account.locale 的行。 */
  private List<Record> myRows(String token) throws Exception {
    UUID tenant = tenantOf(token);
    UUID membership = membershipOf(token);
    return rowsIn(
        tenant,
        "select id, page_key, preference::text as pref, updated_at from user_preference"
            + " where page_key = ? and membership_id = ?::uuid",
        "account.locale",
        membership);
  }

  private List<Record> rowsIn(UUID tenant, String statement, Object... bindings) {
    return tx().run(tenant, () -> List.copyOf(sql.fetch(statement, bindings)));
  }

  private JsonNode read(String token) throws Exception {
    return request(token, "GET", "/identity/user-locale", null, 200);
  }

  /**
   * 登录并**清掉这一页的偏好**。
   *
   * <p>同一个测试类里的用例共用同一个成员（本地所有者），前一个用例存下的偏好会被后一个读到。
   * 所以每个用例都从「没存过」这个起点出发，断言才是确定的。
   */
  private String freshLogin() throws Exception {
    String token = login();
    request(token, "DELETE", "/identity/user-locale", null, 200);
    return token;
  }

  private JsonNode save(String token, String locale, String timezone) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    if (locale != null) body.put("locale", locale);
    if (timezone != null) body.put("timezone", timezone);
    return request(token, "PUT", "/identity/user-locale", body, 200);
  }

  // ------------------------------------------------------------------
  // 读 / 写 / 重置
  // ------------------------------------------------------------------

  @Test
  void 没存过时读到默认值而不是404() throws Exception {
    String token = freshLogin();
    JsonNode view = read(token);
    assertThat(view.get("stored").asBoolean()).as("从没存过").isFalse();
    assertThat(view.get("locale").asText()).isEqualTo("zh-CN");
    assertThat(view.get("timezone").asText()).isEqualTo("Asia/Shanghai");
    assertThat(view.get("defaultTimezone").asText()).isEqualTo("Asia/Shanghai");
    assertThat(view.get("updatedAt").isNull()).as("没存过就没有更新时刻").isTrue();
  }

  @Test
  void 保存后读回且库里真的落了一行() throws Exception {
    String token = freshLogin();
    JsonNode saved = save(token, "zh-CN", "America/New_York");
    assertThat(saved.get("stored").asBoolean()).isTrue();
    assertThat(saved.get("timezone").asText()).isEqualTo("America/New_York");
    assertThat(saved.get("updatedAt").asText()).isNotBlank();

    List<Record> rows = myRows(token);
    assertThat(rows).as("偏好必须真的落 PostgreSQL").hasSize(1);
    assertThat(rows.get(0).get("pref", String.class))
        .contains("America/New_York")
        .contains("zh-CN");
  }

  @Test
  void 第二次保存是覆盖而不是新增一行() throws Exception {
    String token = freshLogin();
    save(token, "zh-CN", "Europe/London");
    save(token, "zh-CN", "Asia/Tokyo");
    assertThat(myRows(token)).as("UNIQUE(tenant_id,membership_id,page_key) ⇒ 一页一行").hasSize(1);
    assertThat(read(token).get("timezone").asText()).isEqualTo("Asia/Tokyo");
  }

  @Test
  void 只改时区时语言保持原值() throws Exception {
    String token = freshLogin();
    save(token, "zh-CN", "Asia/Tokyo");
    JsonNode after = save(token, null, "Europe/Berlin");
    assertThat(after.get("timezone").asText()).isEqualTo("Europe/Berlin");
    assertThat(after.get("locale").asText()).isEqualTo("zh-CN");
  }

  @Test
  void 重置删掉那一行并回到默认() throws Exception {
    String token = freshLogin();
    save(token, "zh-CN", "Asia/Tokyo");
    request(token, "DELETE", "/identity/user-locale", null, 200);
    JsonNode view = read(token);
    assertThat(view.get("stored").asBoolean()).as("重置后回到没存过").isFalse();
    assertThat(view.get("timezone").asText()).isEqualTo("Asia/Shanghai");
    assertThat(myRows(token)).as("重置是真删行，不是把默认值写回去").isEmpty();
  }

  @Test
  void 重置是幂等的本来没存过也算成功() throws Exception {
    String token = freshLogin();
    request(token, "DELETE", "/identity/user-locale", null, 200);
    request(token, "DELETE", "/identity/user-locale", null, 200);
  }

  // ------------------------------------------------------------------
  // 语义校验
  // ------------------------------------------------------------------

  @Test
  void 非法时区被拒并说清原因() throws Exception {
    String token = freshLogin();
    for (String bad : List.of("Not/AZone", "UTC+8", "GMT+8:00", "Asia/Shanghai;")) {
      JsonNode error = request(token, "PUT", "/identity/user-locale", Map.of("timezone", bad), 400);
      assertThat(error.get("detail").asText()).as("必须说清为什么被拒：" + bad).contains("时区");
    }
    // 被拒的写入**没有**落库
    assertThat(myRows(token)).isEmpty();
  }

  @Test
  void 没有语言包的语言被拒所以不出现假开关() throws Exception {
    String token = freshLogin();
    JsonNode error = request(token, "PUT", "/identity/user-locale", Map.of("locale", "en-US"), 400);
    assertThat(error.get("detail").asText()).contains("en-US").contains("i18n");
    assertThat(myRows(token)).isEmpty();
  }

  @Test
  void 空时区被拒而不是被静默改成默认() throws Exception {
    String token = freshLogin();
    request(token, "PUT", "/identity/user-locale", Map.of("timezone", ""), 400);
  }

  // ------------------------------------------------------------------
  // 防御性回落
  // ------------------------------------------------------------------

  @Test
  void 绕过本服务写入的畸形值会回落并说清原因() throws Exception {
    String token = freshLogin();
    // 通用偏好端点只守结构安全，不守语义 —— 写进去的是不存在的时区与没有语言包的语言。
    request(
        token,
        "PUT",
        "/identity/user-preferences/account.locale",
        Map.of("preference", Map.of("locale", "en-US", "timezone", "Not/AZone")),
        200);

    JsonNode view = read(token);
    assertThat(view.get("stored").asBoolean()).as("行确实在").isTrue();
    assertThat(view.get("timezone").asText()).as("回落成默认时区").isEqualTo("Asia/Shanghai");
    assertThat(view.get("locale").asText()).as("回落成默认语言").isEqualTo("zh-CN");
    assertThat(view.get("fallbackReason").asText()).contains("Not/AZone").contains("en-US");
  }

  @Test
  void 缺键的老偏好按默认值显示而不是报错() throws Exception {
    String token = freshLogin();
    request(
        token,
        "PUT",
        "/identity/user-preferences/account.locale",
        Map.of("preference", Map.of("pageSize", 20)),
        200);
    JsonNode view = read(token);
    assertThat(view.get("timezone").asText()).isEqualTo("Asia/Shanghai");
    assertThat(view.get("fallbackReason").asText()).contains("展示时区");
  }

  // ------------------------------------------------------------------
  // 不改变存储时区
  // ------------------------------------------------------------------

  @Test
  void 改展示时区不会让写入时刻漂移() throws Exception {
    String token = freshLogin();
    JsonNode before = save(token, "zh-CN", "Asia/Shanghai");
    Instant first = Instant.parse(before.get("updatedAt").asText());

    JsonNode after = save(token, "zh-CN", "America/New_York");
    Instant second = Instant.parse(after.get("updatedAt").asText());

    long driftSeconds = Math.abs(second.getEpochSecond() - first.getEpochSecond());
    assertThat(driftSeconds)
        .as("两个时区相差 13 小时；写入时刻只该差「这一条操作花了多久」")
        .isLessThan(60);
    assertThat(second)
        .as("写入时刻是真实的当下，不是被展示时区搬过的")
        .isCloseTo(Instant.now(), within(5, ChronoUnit.MINUTES));
  }

  @Test
  void 写回的时刻与直查库的绝对时刻一致没有静默偏移() throws Exception {
    String token = freshLogin();
    JsonNode saved = save(token, "zh-CN", "America/New_York");
    Instant viaApi = Instant.parse(saved.get("updatedAt").asText());

    Record row = myRows(token).get(0);
    Instant viaDb = row.get("updated_at", OffsetDateTime.class).toInstant();
    assertThat(viaApi).as("API 与库里必须是同一个绝对时刻").isEqualTo(viaDb);
  }

  @Test
  void 偏好载荷里只有语言与时区没有换算后的时刻() throws Exception {
    String token = freshLogin();
    save(token, "zh-CN", "America/New_York");
    String payload = myRows(token).get(0).get("pref", String.class);
    assertThat(payload).contains("locale").contains("timezone").doesNotContain("offset");
  }

  // ------------------------------------------------------------------
  // 能力声明
  // ------------------------------------------------------------------

  @Test
  void 能力声明逐条如实报出() throws Exception {
    String token = freshLogin();
    JsonNode caps = request(token, "GET", "/identity/user-locale/capabilities", null, 200);
    assertThat(caps.get("module").asText()).isEqualTo("1.4.2.1");

    Map<String, JsonNode> dimensions = new LinkedHashMap<>();
    for (JsonNode dimension : caps.get("dimensions")) {
      dimensions.put(dimension.get("key").asText(), dimension);
    }

    assertThat(dimensions).containsKeys("displayTimezone", "interfaceLocale");
    assertThat(dimensions.get("displayTimezone").get("available").asBoolean())
        .as("展示时区是真能用的")
        .isTrue();
    assertThat(dimensions.get("interfaceLocale").get("available").asBoolean())
        .as("界面语言当前不可用（本仓没有 i18n 运行时与语言包）")
        .isFalse();
    assertThat(dimensions.get("interfaceLocale").get("missing").toString())
        .contains("i18n")
        .contains("语言包");
    assertThat(dimensions.get("interfaceLocale").get("ownerModule").asText())
        .as("缺什么要归谁")
        .isNotBlank();

    assertThat(caps.get("supportedLocales").toString()).contains("zh-CN");
    assertThat(caps.get("plannedLocales").toString()).contains("en-US");
    assertThat(caps.get("boundaries").toString())
        .contains("存储时区")
        .contains("业务日期口径");

    JsonNode convention = caps.get("displayConvention");
    assertThat(convention.get("locale").asText()).isEqualTo("zh-CN");
    assertThat(convention.get("options").get("timeZoneName").asText()).isEqualTo("shortOffset");
    assertThat(convention.get("options").get("hourCycle").asText()).isEqualTo("h23");
  }
}
