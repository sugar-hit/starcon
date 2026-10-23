package com.starcon.identity;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.starcon.tenancy.TenantTransactions;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.Record;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 1.4.1.1 工作台布局 —— 布局载荷在**真实 PostgreSQL 16** 上的集成测试。
 *
 * <p><b>本模块不新建任何表、也不新增任何端点</b>：工作台布局是**纯个人偏好**，
 * 落在 1.4.1.2 建的那张 {@code user_preference} 表上，用本模块自己的 page_key
 * {@code dashboard.layout}。所以这里验证的是「**复用既有端点 + 复用既有表**」这条路
 * 真的成立，而不是新写一套存储。
 *
 * <p><b>真正在守的东西</b>：
 *
 * <ol>
 *   <li><b>卡片顺序靠数组顺序表达，而 jsonb 不重排数组元素</b>（它只重排对象的键）。
 *       这是整个模块的地基：顺序一旦被重排，用户的布局就全乱了。</li>
 *   <li><b>同 page_key 二次写入是覆盖不是新增</b> —— 用户每拖一次就写一次，
 *       变成新增行的话偏好会越积越多，「读到的是哪一条」变成随机的。</li>
 *   <li><b>缺键不报错</b> —— 老布局里没有新加的字段是常态（蓝图的硬约束：
 *       「新增字段不影响旧偏好」），后端必须原样收下。</li>
 *   <li><b>没存过就返回 stored=false 而不是 404</b> —— 「还没设过布局」是常态。</li>
 *   <li><b>重置真的删行</b> —— 「恢复默认布局」是删掉那一行，不是「提示用户刷新」。</li>
 *   <li><b>敏感键进不来</b> —— 前端可以被绕过，这条规则必须在服务端钉住。</li>
 *   <li><b>时间没被时区悄悄搬走</b> —— {@code timestamptz} 列用 {@code OffsetDateTime} 绑定。</li>
 * </ol>
 *
 * <p>隔离（两租户互不可见 / 伪造租户头无效 / 连接池归还后不串租户）在
 * {@link WorkbenchLayoutTenancyIntegrationTest} 里单独实跑。
 */
class WorkbenchLayoutIntegrationTest extends IdentityIntegrationTestBase {

  /** 本模块的页面 key —— 与前端 {@code WORKBENCH_LAYOUT_PAGE_KEY} 一致。 */
  private static final String PAGE_KEY = "dashboard.layout";

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

  private JsonNode save(String token, Map<String, Object> preference) throws Exception {
    return request(
        token, "PUT", "/identity/user-preferences/" + PAGE_KEY, Map.of("preference", preference), 200);
  }

  private JsonNode read(String token) throws Exception {
    return request(token, "GET", "/identity/user-preferences/" + PAGE_KEY, null, 200);
  }

  /** 一份完整的布局载荷。 */
  private Map<String, Object> layout() {
    var payload = new LinkedHashMap<String, Object>();
    payload.put("order", List.of("todo.anomaly-orders", "risk.inventory", "metric.fulfillment-health"));
    payload.put("hidden", List.of("nav.shortcuts"));
    payload.put("defaultTab", "risk");
    return payload;
  }

  /**
   * 每个用例开始前把这一页清干净。
   *
   * <p>本模块的 page_key 是**固定的**（不像列表偏好那样每个用例拼一个随机 key）——
   * 固定 key 才是「用户真的会用这个 key」的真实形状，所以隔离靠清场而不是靠换 key。
   *
   * <p>清的是**本租户内这一页的所有行**（含同租户其它成员留下的）：偏好表的 RLS 只管到
   * 租户级，「只看我自己的」是服务层的 membership_id 谓词，所以清场要走租户事务里的
   * 直删，而不是只删「我」那一条。
   */
  @BeforeEach
  void 清掉上一条用例留下的布局() throws Exception {
    UUID tenant = tenantOf(login());
    tx()
        .run(
            tenant,
            () -> sql.execute("delete from user_preference where page_key = ?", PAGE_KEY));
  }

  // ================================================================ 基本行为

  @Test
  void 保存后能读回同一份布局() throws Exception {
    String token = login();

    JsonNode saved = save(token, layout());
    assertThat(saved.get("pageKey").asText()).isEqualTo(PAGE_KEY);
    assertThat(saved.get("stored").asBoolean()).isTrue();
    assertThat(saved.get("preference").get("defaultTab").asText()).isEqualTo("risk");
    assertThat(saved.get("preference").get("hidden").toString()).contains("nav.shortcuts");

    JsonNode loaded = read(token);
    assertThat(loaded.get("stored").asBoolean()).isTrue();
    assertThat(loaded.get("preference").get("defaultTab").asText()).isEqualTo("risk");
    assertThat(loaded.get("preference").get("hidden").toString()).contains("nav.shortcuts");
  }

  @Test
  void 卡片顺序按数组顺序原样往返() throws Exception {
    String token = login();
    List<String> order = List.of("risk.inventory", "nav.shortcuts", "todo.anomaly-orders");

    var payload = new LinkedHashMap<String, Object>();
    payload.put("order", order);
    payload.put("hidden", List.of());
    payload.put("defaultTab", "overview");
    save(token, payload);

    // 🔴 这条是整个模块的地基：卡片顺序**只**靠数组顺序表达。
    // jsonb 会重排对象的键，但**不重排数组元素** —— 顺序必须原样回来。
    List<String> roundTrip = new ArrayList<>();
    read(token).get("preference").get("order").forEach(node -> roundTrip.add(node.asText()));
    assertThat(roundTrip)
        .as("卡片顺序必须原样往返（jsonb 不得重排数组元素）")
        .containsExactlyElementsOf(order);
  }

  @Test
  void 布局真的落在库里且只有一行() throws Exception {
    String token = login();
    UUID tenant = tenantOf(token);
    save(token, layout());

    List<Record> rows =
        rowsIn(
            tenant,
            "select tenant_id, membership_id, page_key, preference::text as pref,"
                + " created_at, updated_at from user_preference where page_key = ?",
            PAGE_KEY);
    assertThat(rows).as("布局必须真的落 PostgreSQL").hasSize(1);
    assertThat(rows.get(0).get("page_key", String.class)).isEqualTo(PAGE_KEY);
    assertThat(rows.get(0).get("tenant_id", UUID.class)).isEqualTo(tenant);
    // 落库的是 JSONB 文本，断言的是**值**而不是字符串形态（jsonb 的键序不保证）。
    assertThat(rows.get(0).get("pref", String.class)).contains("\"defaultTab\"").contains("\"risk\"");
  }

  @Test
  void 同一页二次写入是覆盖不是新增() throws Exception {
    String token = login();
    UUID tenant = tenantOf(token);

    save(token, layout());
    var second = new LinkedHashMap<String, Object>();
    second.put("order", List.of("nav.global-search"));
    second.put("hidden", List.of());
    second.put("defaultTab", "overview");
    save(token, second);

    assertThat(rowsIn(tenant, "select id from user_preference where page_key = ?", PAGE_KEY))
        .as("同 page_key 二次写入必须覆盖，不新增行")
        .hasSize(1);
    assertThat(read(token).get("preference").get("defaultTab").asText()).isEqualTo("overview");
  }

  @Test
  void 没存过时返回stored为false而不是404() throws Exception {
    String token = login();
    JsonNode view = read(token);
    assertThat(view.get("stored").asBoolean()).isFalse();
    assertThat(view.get("pageKey").asText()).isEqualTo(PAGE_KEY);
    // 载荷是空对象，不是 null —— 前端要能直接对缺失键套默认值。
    assertThat(view.get("preference").isObject()).isTrue();
    assertThat(view.get("preference").size()).isZero();
  }

  @Test
  void 缺键不报错原样收下() throws Exception {
    String token = login();
    // 只给 order，缺 hidden 与 defaultTab —— 老布局就是这个形状。
    JsonNode saved = save(token, Map.of("order", List.of("risk.inventory")));
    assertThat(saved.get("stored").asBoolean()).isTrue();

    JsonNode loaded = read(token);
    assertThat(loaded.get("preference").has("order")).isTrue();
    assertThat(loaded.get("preference").has("hidden")).isFalse();
    assertThat(loaded.get("preference").has("defaultTab")).isFalse();
  }

  @Test
  void 重置真的删掉那一行() throws Exception {
    String token = login();
    UUID tenant = tenantOf(token);
    save(token, layout());
    assertThat(rowsIn(tenant, "select id from user_preference where page_key = ?", PAGE_KEY))
        .hasSize(1);

    request(token, "DELETE", "/identity/user-preferences/" + PAGE_KEY, null, 200);

    assertThat(rowsIn(tenant, "select id from user_preference where page_key = ?", PAGE_KEY))
        .as("重置 = 删掉那一行（真的重置，不是提示用户刷新）")
        .isEmpty();
    assertThat(read(token).get("stored").asBoolean()).isFalse();
  }

  @Test
  void 重置是幂等的本来没存过也算成功() throws Exception {
    String token = login();
    request(token, "DELETE", "/identity/user-preferences/" + PAGE_KEY, null, 200);
    request(token, "DELETE", "/identity/user-preferences/" + PAGE_KEY, null, 200);
    assertThat(read(token).get("stored").asBoolean()).isFalse();
  }

  // ================================================================ 服务端把关

  @Test
  void 敏感键被服务端拒掉() throws Exception {
    String token = login();
    var payload = new LinkedHashMap<String, Object>();
    payload.put("order", List.of("risk.inventory"));
    payload.put("accessToken", "whatever");
    http.perform(
            put("/identity/user-preferences/" + PAGE_KEY)
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content(json.writeValueAsString(Map.of("preference", payload))))
        .andExpect(status().isBadRequest());
  }

  @Test
  void 载荷必须是对象不是数组() throws Exception {
    String token = login();
    http.perform(
            put("/identity/user-preferences/" + PAGE_KEY)
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content(json.writeValueAsString(Map.of("preference", List.of("risk.inventory")))))
        .andExpect(status().isBadRequest());
  }

  @Test
  void 时间字段零漂移() throws Exception {
    String token = login();
    UUID tenant = tenantOf(token);
    OffsetDateTime before = OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(5);

    JsonNode saved = save(token, layout());

    // 用 OffsetDateTime 绑定 timestamptz：不该出现 −28800s 的静默偏移。
    OffsetDateTime updatedAt = OffsetDateTime.parse(saved.get("updatedAt").asText());
    assertThat(updatedAt)
        .as("updatedAt 必须落在「刚刚」附近（不是偏了 8 小时）")
        .isAfter(before)
        .isBefore(OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(30));

    // 库里那一行与接口回读的是**同一个时刻**（比的是 instant，不是本地时区形态）。
    Record row =
        rowsIn(tenant, "select created_at, updated_at from user_preference where page_key = ?", PAGE_KEY)
            .get(0);
    assertThat(row.get("updated_at", OffsetDateTime.class).toInstant())
        .isEqualTo(updatedAt.toInstant());
    assertThat(row.get("created_at", OffsetDateTime.class).toInstant())
        .isAfter(before.toInstant());
  }
}
