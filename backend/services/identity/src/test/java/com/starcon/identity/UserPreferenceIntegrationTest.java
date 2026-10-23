package com.starcon.identity;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.starcon.tenancy.TenantTransactions;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.Record;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * 1.4.1.2 列表与表格偏好 —— 「个人视图状态」的集成测试。
 *
 * <p><b>真实 PostgreSQL 16 + 强制 RLS，不使用任何内存替身。</b>
 *
 * <p><b>本测试真正在守的东西</b>：
 *
 * <ol>
 *   <li><b>同 page_key 二次写入是覆盖不是新增</b> —— 列表页每改一次视图就写一次，
 *       如果变成新增行，用户的偏好会越积越多，而且「读到的到底是哪一条」变成随机的。</li>
 *   <li><b>缺键不报错</b> —— 老偏好里没有新加的字段是常态（蓝图的硬约束：
 *       「新增字段不影响旧偏好」），后端必须原样收下，不能因为「少一个键」就 400。</li>
 *   <li><b>没存过就返回 stored=false 而不是 404</b> —— 「还没保存过偏好」是列表页的常态，
 *       不是异常；界面要能直接落到默认值。</li>
 *   <li><b>敏感字段进不来</b> —— 密码 / token / 身份证这类字段不得进 preference JSON，
 *       而且这条规则必须在服务端钉住（前端是可以被绕过的）。</li>
 *   <li><b>时间没被时区悄悄搬走</b> —— {@code timestamptz} 列用 {@code OffsetDateTime} 绑定，
 *       断言写回的值落在「刚刚」附近，而不是偏 −28800s。</li>
 * </ol>
 */
class UserPreferenceIntegrationTest extends IdentityIntegrationTestBase {

  private TenantTransactions tx() {
    return new TenantTransactions(
        sql, new org.springframework.transaction.support.TransactionTemplate(manager));
  }

  private UUID tenantOf(String token) throws Exception {
    return UUID.fromString(
        request(token, "GET", "/identity/tenants/current", null, 200).get("tenantId").asText());
  }

  /** 租户事务内直查（RLS 生效）。 */
  private List<Record> rowsIn(UUID tenant, String statement, Object... bindings) {
    return tx().run(tenant, () -> List.copyOf(sql.fetch(statement, bindings)));
  }

  private JsonNode save(String token, String pageKey, Map<String, Object> preference)
      throws Exception {
    return request(
        token,
        "PUT",
        "/identity/user-preferences/" + pageKey,
        Map.of("preference", preference),
        200);
  }

  private JsonNode read(String token, String pageKey) throws Exception {
    return request(token, "GET", "/identity/user-preferences/" + pageKey, null, 200);
  }

  private JsonNode list(String token) throws Exception {
    return request(token, "GET", "/identity/user-preferences", null, 200);
  }

  private Map<String, Object> payload() {
    var p = new LinkedHashMap<String, Object>();
    p.put("columns", List.of("sku", "name", "price"));
    p.put("sortKey", "createdAt");
    p.put("sortDirection", "DESC");
    p.put("pageSize", 50);
    p.put("filters", Map.of("status", "PENDING"));
    return p;
  }

  // ================================================================ 基本行为

  @Test
  void 保存后能读回同一份偏好() throws Exception {
    String token = login();
    String tag = suffix();
    String pageKey = "orders.pending-shipment-" + tag.toLowerCase();

    JsonNode saved = save(token, pageKey, payload());
    assertThat(saved.get("pageKey").asText()).isEqualTo(pageKey);
    assertThat(saved.get("stored").asBoolean()).isTrue();
    assertThat(saved.get("preference").get("pageSize").asInt()).isEqualTo(50);
    assertThat(saved.get("preference").get("sortDirection").asText()).isEqualTo("DESC");
    assertThat(saved.get("preference").get("columns").toString()).contains("sku", "price");
    assertThat(saved.get("preference").get("filters").get("status").asText()).isEqualTo("PENDING");

    JsonNode loaded = read(token, pageKey);
    assertThat(loaded.get("stored").asBoolean()).isTrue();
    // 逐字段比对，**不**比 JSON 字符串：jsonb 不保留键的插入顺序（按长度再按字节排序），
    // 写回时键序会变 —— 键序不是语义，比字符串会把「jsonb 的正常行为」报成缺陷。
    assertThat(loaded.get("preference").get("pageSize").asInt()).isEqualTo(50);
    assertThat(loaded.get("preference").get("sortKey").asText()).isEqualTo("createdAt");
    assertThat(loaded.get("preference").get("sortDirection").asText()).isEqualTo("DESC");
    assertThat(loaded.get("preference").get("columns").toString()).contains("sku", "price");
    assertThat(loaded.get("preference").get("filters").get("status").asText()).isEqualTo("PENDING");
  }

  @Test
  void 同一个页面key二次写入是覆盖不是新增() throws Exception {
    String token = login();
    String tag = suffix();
    String pageKey = "catalog.products-" + tag.toLowerCase();

    save(token, pageKey, payload());
    var second = new LinkedHashMap<String, Object>();
    second.put("pageSize", 20);
    save(token, pageKey, second);

    // 表里**只有一行**
    assertThat(
            rowsIn(
                tenantOf(token),
                "select id from user_preference where page_key = ?",
                pageKey))
        .as("同一个 page_key 只能有一行")
        .hasSize(1);

    // 读回来的是**后写的那份**，不是两份的合并
    JsonNode loaded = read(token, pageKey);
    assertThat(loaded.get("preference").get("pageSize").asInt()).isEqualTo(20);
    assertThat(loaded.get("preference").has("columns")).as("覆盖不是合并").isFalse();
  }

  @Test
  void 载荷缺键时不报错且原样存回() throws Exception {
    String token = login();
    String tag = suffix();
    String pageKey = "inventory.risk-" + tag.toLowerCase();

    // 只给一个键：其余键「缺失」是合法的（老偏好没有新字段）
    JsonNode saved = save(token, pageKey, Map.of("pageSize", 30));
    assertThat(saved.get("stored").asBoolean()).isTrue();
    assertThat(saved.get("preference").get("pageSize").asInt()).isEqualTo(30);
    assertThat(saved.get("preference").has("columns")).isFalse();

    // 服务端**不**给它补默认字段 —— 补默认是前端 hook 的活（它才知道这一页的默认列）
    JsonNode loaded = read(token, pageKey);
    assertThat(loaded.get("preference").toString()).isEqualTo("{\"pageSize\":30}");
  }

  @Test
  void 没保存过的页面key返回stored_false而不是404() throws Exception {
    String token = login();
    String tag = suffix();
    JsonNode loaded = read(token, "never.saved-" + tag.toLowerCase());
    assertThat(loaded.get("stored").asBoolean()).isFalse();
    assertThat(loaded.get("preference").isEmpty()).isTrue();
    assertThat(loaded.get("updatedAt").isNull()).isTrue();
  }

  @Test
  void 重置后这一页回到未保存状态() throws Exception {
    String token = login();
    String tag = suffix();
    String pageKey = "warehouse.outbound-timeliness-" + tag.toLowerCase();

    save(token, pageKey, payload());
    request(token, "DELETE", "/identity/user-preferences/" + pageKey, null, 200);

    assertThat(read(token, pageKey).get("stored").asBoolean()).isFalse();
    assertThat(
            rowsIn(
                tenantOf(token), "select id from user_preference where page_key = ?", pageKey))
        .isEmpty();

    // 重置是**幂等**的：本来就没存过也照样成功
    request(token, "DELETE", "/identity/user-preferences/" + pageKey, null, 200);
  }

  @Test
  void 列出我全部页面的偏好() throws Exception {
    String token = login();
    String tag = suffix();
    String first = "orders.a-" + tag.toLowerCase();
    String second = "orders.b-" + tag.toLowerCase();
    save(token, first, Map.of("pageSize", 10));
    save(token, second, Map.of("pageSize", 20));

    JsonNode entries = list(token).get("entries");
    assertThat(entries.isArray()).isTrue();
    int matched = 0;
    for (JsonNode entry : entries) {
      String key = entry.get("pageKey").asText();
      if (key.equals(first) || key.equals(second)) matched++;
    }
    assertThat(matched).as("两页都要在列表里").isEqualTo(2);
  }

  @Test
  void 全部重置清空我的所有偏好() throws Exception {
    String token = login();
    String tag = suffix();
    save(token, "orders.reset-a-" + tag.toLowerCase(), Map.of("pageSize", 10));
    save(token, "orders.reset-b-" + tag.toLowerCase(), Map.of("pageSize", 20));

    request(token, "DELETE", "/identity/user-preferences", null, 200);

    assertThat(
            rowsIn(tenantOf(token), "select id from user_preference where page_key like ?", "orders.reset-%"))
        .isEmpty();
  }

  // ================================================================ 校验（真校验，不是假校验）

  @Test
  void 敏感字段名被服务端拒绝() throws Exception {
    String token = login();
    String tag = suffix();
    String pageKey = "orders.sensitive-" + tag.toLowerCase();

    for (String key :
        List.of(
            "password",
            "accessToken",
            "access_token",
            "idCard",
            "apiKey",
            "secret",
            "credential",
            "bankCardNo",
            "authorization")) {
      request(
          token,
          "PUT",
          "/identity/user-preferences/" + pageKey,
          Map.of("preference", Map.of("filters", Map.of(key, "whatever"))),
          400);
    }

    // 一个都没写进去
    assertThat(
            rowsIn(
                tenantOf(token), "select id from user_preference where page_key = ?", pageKey))
        .as("被拒的载荷不得留下任何行")
        .isEmpty();
  }

  @Test
  void 看起来像凭据的值被服务端拒绝() throws Exception {
    String token = login();
    String tag = suffix();
    String pageKey = "orders.credential-" + tag.toLowerCase();

    request(
        token,
        "PUT",
        "/identity/user-preferences/" + pageKey,
        Map.of(
            "preference",
            Map.of("filters", Map.of("note", "Bearer abcdefghijklmnopqrstuvwxyz"))),
        400);
    request(
        token,
        "PUT",
        "/identity/user-preferences/" + pageKey,
        Map.of(
            "preference",
            Map.of(
                "filters",
                Map.of(
                    "note",
                    "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.abcdefghij"))),
        400);
    request(
        token,
        "PUT",
        "/identity/user-preferences/" + pageKey,
        Map.of("preference", Map.of("filters", Map.of("note", "password=hunter2"))),
        400);

    assertThat(
            rowsIn(
                tenantOf(token), "select id from user_preference where page_key = ?", pageKey))
        .isEmpty();
  }

  @Test
  void 非法页面key被拒() throws Exception {
    String token = login();
    // 只取**不会与路径分隔符混淆**的非法形状：
    // 含 "/" 的那个会被容器当成多一段路径，落到 404 而不是本页的 400 校验。
    for (String pageKey : List.of("Orders.Pending", "orders pending", "-orders", "orders pending!")) {
      request(
          token,
          "GET",
          "/identity/user-preferences/"
              + java.net.URLEncoder.encode(pageKey, java.nio.charset.StandardCharsets.UTF_8),
          null,
          400);
    }
  }

  @Test
  void 单个值太长被拒() throws Exception {
    String token = login();
    String tag = suffix();
    var filters = new LinkedHashMap<String, Object>();
    filters.put("note", "x".repeat(4096));
    request(
        token,
        "PUT",
        "/identity/user-preferences/oversized-" + tag.toLowerCase(),
        Map.of("preference", Map.of("filters", filters)),
        400);
  }

  @Test
  void 载荷总体积超限被拒() throws Exception {
    String token = login();
    String tag = suffix();
    // 单个值都在长度上限内，但合起来超过 16 KB —— 这是「整体太大」而不是「某个值太长」。
    var big = new java.util.ArrayList<String>();
    for (int i = 0; i < 64; i++) big.add("y".repeat(400));
    request(
        token,
        "PUT",
        "/identity/user-preferences/heavy-" + tag.toLowerCase(),
        Map.of("preference", Map.of("filters", Map.of("note", big))),
        400);
  }

  @Test
  void 载荷不是对象时被拒() throws Exception {
    String token = login();
    String tag = suffix();
    request(
        token,
        "PUT",
        "/identity/user-preferences/array-" + tag.toLowerCase(),
        Map.of("preference", List.of("a", "b")),
        400);
  }

  @Test
  void 嵌套太深被拒() throws Exception {
    String token = login();
    String tag = suffix();
    var deep = new LinkedHashMap<String, Object>();
    deep.put("l1", Map.of("l2", Map.of("l3", Map.of("l4", Map.of("l5", "x")))));
    request(
        token,
        "PUT",
        "/identity/user-preferences/deep-" + tag.toLowerCase(),
        Map.of("preference", deep),
        400);
  }

  // ================================================================ 时间与 RLS

  @Test
  void 时间字段按东八区不偏移地写回() throws Exception {
    String token = login();
    String tag = suffix();
    String pageKey = "orders.clock-" + tag.toLowerCase();
    OffsetDateTime before = OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(5);

    JsonNode saved = save(token, pageKey, payload());
    OffsetDateTime updatedAt = OffsetDateTime.parse(saved.get("updatedAt").asText());

    // 用 OffsetDateTime 绑定 timestamptz：不该出现 −28800s 的静默偏移
    assertThat(updatedAt)
        .as("updatedAt 必须落在「刚刚」附近（不是偏了 8 小时）")
        .isAfter(before)
        .isBefore(OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(30));

    // 库里那一行的值与返回值一致（断言写回的值，不是「没抛异常」）
    List<Record> rows =
        rowsIn(
            tenantOf(token),
            "select updated_at from user_preference where page_key = ?",
            pageKey);
    assertThat(rows).hasSize(1);
    OffsetDateTime stored = rows.getFirst().get("updated_at", OffsetDateTime.class);
    assertThat(stored.toInstant()).isEqualTo(updatedAt.toInstant());
  }

  @Test
  void 偏好表是强制RLS() throws Exception {
    Boolean forced =
        sql.fetchOne("select relforcerowsecurity from pg_class where relname = 'user_preference'")
            .get(0, Boolean.class);
    assertThat(forced).as("user_preference 必须是 FORCE RLS").isTrue();
  }

  @Test
  void 无租户上下文时表里读不到任何行() {
    assertThat(sql.fetch("select id from user_preference")).isEmpty();
  }

  @Test
  void 未登录一律401() throws Exception {
    http.perform(get("/identity/user-preferences")).andExpect(status().isUnauthorized());
    http.perform(get("/identity/user-preferences/orders.pending-shipment"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void 写接口接受application_json() throws Exception {
    String token = login();
    String tag = suffix();
    String body =
        json.writeValueAsString(Map.of("preference", Map.of("pageSize", 15)));
    http.perform(
            put("/identity/user-preferences/json-" + tag.toLowerCase())
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk());
  }
}
