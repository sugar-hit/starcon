package com.starcon.identity;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.starcon.tenancy.TenantTransactions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.Record;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 1.2.2.1 常用页面收藏 —— 集成测试。
 *
 * <p><b>真实 PostgreSQL 16 + 强制 RLS，不使用任何内存替身。</b>
 *
 * <p><b>本测试真正在守的东西</b>：
 *
 * <ol>
 *   <li><b>数据真的落库</b>：收藏写进的是 1.4.1.2 建好的 {@code user_preference} 表，
 *       用本模块自己的 {@code page_key = dashboard.favorites}（断言直查表，不看接口回显就算数）；</li>
 *   <li><b>权限闸真的生效</b>：目标页面没有读权限的收藏<b>不返回</b>（不是返回后再置灰），
 *       而且<b>隐藏不等于删除</b> —— 权限回来它就回来；</li>
 *   <li><b>排序是确定的</b>：拖拽排序与上移/下移走同一个端点，语义是「移到可见清单第 N 位」；</li>
 *   <li><b>校验不是摆设</b>：外站地址、路径穿越、越界系统枚举、未注册的页面码各自被拒。</li>
 * </ol>
 *
 * <p><b>每个用例用自己新建的成员</b>：收藏清单是「一个人一行」的，
 * 用例之间共用 {@code local-admin} 会让清单互相污染（断言「清单里正好两条」就会随机失败）。
 * 新建成员 = 新的一份清单 = 断言是确定的。
 */
class PageFavoriteIntegrationTest extends IdentityIntegrationTestBase {

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

  /** 当前会话对应的成员 id（来自已认证会话，不是请求参数）。 */
  private UUID membershipOf(String token) {
    return identity.resolve("Bearer " + token).membershipId();
  }

  // ================================================================ 测试夹具

  private UUID createRole(String token, String code, List<String> permissions) throws Exception {
    return UUID.fromString(
        request(
                token,
                "POST",
                "/identity/roles",
                Map.of(
                    "code", code,
                    "name", "收藏测试角色 " + code,
                    "status", "ACTIVE",
                    "permissions", permissions),
                200)
            .asText());
  }

  /**
   * 新建一个**干净**的成员并登录，返回它的 token。
   *
   * <p>收藏端点<b>没有</b>功能权限码（与 1.4.1.2 的 {@code user_preference} 同一套论证），
   * 所以这里给的权限只用来构造「有/没有某个页面的读权限」这一种局面。
   */
  private String freshMember(String tag, List<String> permissions) throws Exception {
    return freshMemberWithRole(tag, permissions).token();
  }

  /** 一个新建成员的 token 与它那个角色的 id（改权限时要用角色 id）。 */
  private record Member(String token, UUID roleId) {}

  private Member freshMemberWithRole(String tag, List<String> permissions) throws Exception {
    String owner = login();
    UUID role = createRole(owner, "PF" + tag, permissions);
    request(
        owner,
        "POST",
        "/identity/members",
        memberInput(
            "pf-" + tag.toLowerCase(),
            null,
            List.of(Map.of("roleId", role.toString(), "scopeMode", "ALL"))),
        200);
    return new Member(loginUser("pf-" + tag.toLowerCase()), role);
  }

  /** 收藏一个页面。 */
  private JsonNode add(String token, String href, String label, String system, String pageCode)
      throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("href", href);
    body.put("label", label);
    body.put("system", system);
    body.put("pageCode", pageCode);
    return request(token, "POST", "/identity/page-favorites", body, 200);
  }

  /** 收藏并断言状态码（用于「应当被拒」的场合）。 */
  private void addExpecting(
      String token, String href, String label, String system, String pageCode, int expected)
      throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("href", href);
    body.put("label", label);
    body.put("system", system);
    body.put("pageCode", pageCode);
    request(token, "POST", "/identity/page-favorites", body, expected);
  }

  /** 取消收藏（目标路径走查询参数）。 */
  private JsonNode remove(String token, String href) throws Exception {
    var response =
        http.perform(
                delete("/identity/page-favorites")
                    .header("Authorization", "Bearer " + token)
                    .header("X-Tenant-Id", UUID.randomUUID())
                    .queryParam("href", href))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();
    return json.readTree(response.getContentAsString());
  }

  /** 排序。 */
  private JsonNode move(String token, String href, int toIndex) throws Exception {
    return request(
        token,
        "PUT",
        "/identity/page-favorites/order",
        Map.of("href", href, "toIndex", toIndex),
        200);
  }

  private List<String> hrefs(JsonNode set) {
    List<String> out = new ArrayList<>();
    for (JsonNode item : set.get("items")) out.add(item.get("href").asText());
    return out;
  }

  /** 某个租户里、载荷里含这个路径的收藏行。 */
  private List<Record> favoriteRows(UUID tenant, String href) {
    return rowsIn(
        tenant,
        "select id, membership_id, preference::text as pref from user_preference"
            + " where page_key = 'dashboard.favorites' and position(?::varchar in preference::text) > 0",
        href);
  }

  // ================================================================ 列表与落库

  @Test
  void 没收藏过时列表为空而不是报错() throws Exception {
    String tag = suffix();
    String member = freshMember(tag, List.of());
    JsonNode set = request(member, "GET", "/identity/page-favorites", null, 200);
    assertThat(hrefs(set)).isEmpty();
    assertThat(set.get("hiddenByPermission").asInt()).isZero();
    assertThat(set.get("total").asInt()).isZero();
  }

  @Test
  void 收藏后能读回且真的落在user_preference表里() throws Exception {
    String tag = suffix();
    // 这个成员持有 trade.orders.read ⇒ 他收藏的订单页不会被权限闸挡住。
    String member = freshMember(tag, List.of("trade.orders.read"));
    String href = "/orders-" + tag.toLowerCase();

    JsonNode set = add(member, href, "订单中心", "ops", "trade.orders");
    assertThat(hrefs(set)).containsExactly(href);
    assertThat(set.get("items").get(0).get("label").asText()).isEqualTo("订单中心");
    assertThat(set.get("items").get(0).get("system").asText()).isEqualTo("ops");
    assertThat(set.get("items").get(0).get("pageCode").asText()).isEqualTo("trade.orders");
    assertThat(set.get("items").get(0).get("order").asInt()).isZero();
    assertThat(set.get("total").asInt()).isEqualTo(1);

    // 直查表：真的落库了，用的是本模块自己的 page_key
    List<Record> rows = favoriteRows(tenantOf(member), href);
    assertThat(rows).as("收藏必须落在 user_preference 表的 dashboard.favorites 这一行").hasSize(1);
    assertThat(rows.get(0).get("pref", String.class)).contains("\"items\"").contains(href);

    // 记的是**成员**（membership），不是跨租户的全局账号（app_user）
    assertThat(rows.get(0).get("membership_id", UUID.class))
        .as("收藏的归属是租户内的成员 id")
        .isEqualTo(membershipOf(member));
  }

  @Test
  void 同一路径重复收藏是覆盖且不打乱已有顺序() throws Exception {
    String tag = suffix();
    String member = freshMember(tag, List.of());
    String first = "/first-" + tag.toLowerCase();
    String second = "/second-" + tag.toLowerCase();

    add(member, first, "第一", "ops", "");
    add(member, second, "第二", "ops", "");
    JsonNode set = add(member, first, "第一改名", "logistics", "");

    assertThat(hrefs(set)).as("重复收藏不新增行").containsExactly(first, second);
    assertThat(set.get("items").get(0).get("label").asText()).isEqualTo("第一改名");
    assertThat(set.get("items").get(0).get("system").asText()).isEqualTo("logistics");
    assertThat(set.get("total").asInt()).isEqualTo(2);
  }

  @Test
  void 取消收藏是幂等的() throws Exception {
    String tag = suffix();
    String member = freshMember(tag, List.of());
    String href = "/gone-" + tag.toLowerCase();
    add(member, href, "待取消", "ops", "");

    JsonNode set = remove(member, href);
    assertThat(hrefs(set)).doesNotContain(href);

    // 再取消一次仍然是成功（界面上重复点 X 不该报错）
    JsonNode again = remove(member, href);
    assertThat(hrefs(again)).doesNotContain(href);
  }

  // ================================================================ 排序

  @Test
  void 排序把收藏移到指定位置() throws Exception {
    String tag = suffix();
    String member = freshMember(tag, List.of());
    String a = "/a-" + tag.toLowerCase();
    String b = "/b-" + tag.toLowerCase();
    String c = "/c-" + tag.toLowerCase();
    add(member, a, "A", "ops", "");
    add(member, b, "B", "ops", "");
    add(member, c, "C", "ops", "");

    JsonNode up = move(member, c, 0);
    assertThat(hrefs(up)).containsExactly(c, a, b);

    JsonNode down = move(member, c, 2);
    assertThat(hrefs(down)).containsExactly(a, b, c);

    JsonNode middle = move(member, a, 1);
    assertThat(hrefs(middle)).containsExactly(b, a, c);
  }

  @Test
  void 排序越界被夹到两端() throws Exception {
    String tag = suffix();
    String member = freshMember(tag, List.of());
    String a = "/a-" + tag.toLowerCase();
    String b = "/b-" + tag.toLowerCase();
    add(member, a, "A", "ops", "");
    add(member, b, "B", "ops", "");

    assertThat(hrefs(move(member, a, 99))).containsExactly(b, a);
    assertThat(hrefs(move(member, b, -3))).containsExactly(b, a);
  }

  @Test
  void 排序不存在的收藏被拒() throws Exception {
    String tag = suffix();
    String member = freshMember(tag, List.of());
    request(
        member,
        "PUT",
        "/identity/page-favorites/order",
        Map.of("href", "/nowhere", "toIndex", 0),
        400);
  }

  // ================================================================ 权限闸

  @Test
  void 无页面读权限的收藏自动隐藏而且数据没丢() throws Exception {
    String tag = suffix();
    // 这个成员**自己**收藏了一条指向 trade.orders 的收藏，但他没有 trade.orders.read。
    String limited = freshMember(tag, List.of());
    String href = "/hidden-" + tag.toLowerCase();
    add(limited, href, "订单中心", "ops", "trade.orders");

    JsonNode set = request(limited, "GET", "/identity/page-favorites", null, 200);
    assertThat(hrefs(set)).as("没有页面读权限的收藏不得返回").doesNotContain(href);
    assertThat(set.get("hiddenByPermission").asInt()).isEqualTo(1);
    assertThat(set.get("total").asInt()).as("总数含被隐藏的").isEqualTo(1);

    // 隐藏不等于删除：那一行还在库里（权限恢复后就会回来）。
    assertThat(favoriteRows(tenantOf(limited), href))
        .as("被隐藏的收藏仍然留在存储里")
        .hasSize(1);
  }

  @Test
  void 权限恢复后收藏自动回来() throws Exception {
    String tag = suffix();
    String owner = login();
    Member member = freshMemberWithRole(tag, List.of("trade.orders.read"));
    String href = "/back-" + tag.toLowerCase();
    add(member.token(), href, "订单中心", "ops", "trade.orders");

    // 这个成员**有** trade.orders.read → 可见
    assertThat(hrefs(request(member.token(), "GET", "/identity/page-favorites", null, 200)))
        .contains(href);

    // 撤掉权限 → 立刻隐藏，不需要任何同步任务
    request(
        owner,
        "PUT",
        "/identity/roles/" + member.roleId(),
        Map.of(
            "code", "PF" + tag,
            "name", "收藏测试角色 PF" + tag,
            "status", "ACTIVE",
            "permissions", List.of()),
        200);
    JsonNode hidden = request(member.token(), "GET", "/identity/page-favorites", null, 200);
    assertThat(hrefs(hidden)).doesNotContain(href);
    assertThat(hidden.get("hiddenByPermission").asInt()).isEqualTo(1);

    // 再给回来 → 立刻回来（数据一直没被删）
    request(
        owner,
        "PUT",
        "/identity/roles/" + member.roleId(),
        Map.of(
            "code", "PF" + tag,
            "name", "收藏测试角色 PF" + tag,
            "status", "ACTIVE",
            "permissions", List.of("trade.orders.read")),
        200);
    assertThat(hrefs(request(member.token(), "GET", "/identity/page-favorites", null, 200)))
        .contains(href);
  }

  @Test
  void 没有页面级读权限的收藏不受权限闸约束() throws Exception {
    String tag = suffix();
    String member = freshMember(tag, List.of());
    String href = "/ungated-" + tag.toLowerCase();
    add(member, href, "利润中心", "ops", "");

    // 一个什么权限都没有的成员：这一页没有 read 权限 ⇒ 没有闸 ⇒ 可见
    JsonNode set = request(member, "GET", "/identity/page-favorites", null, 200);
    assertThat(hrefs(set)).contains(href);
    assertThat(set.get("hiddenByPermission").asInt()).isZero();
  }

  @Test
  void 能清掉因权限不足被隐藏的收藏() throws Exception {
    String tag = suffix();
    // 这个成员**自己**收藏了一条指向 trade.orders 的收藏，但他没有 trade.orders.read
    // ⇒ 这条收藏当前被权限闸挡住。
    String limited = freshMember(tag, List.of());
    String href = "/ghost-" + tag.toLowerCase();
    add(limited, href, "订单中心", "ops", "trade.orders");
    assertThat(
            request(limited, "GET", "/identity/page-favorites", null, 200)
                .get("hiddenByPermission")
                .asInt())
        .isEqualTo(1);

    JsonNode after = request(limited, "DELETE", "/identity/page-favorites/hidden", null, 200);
    assertThat(hrefs(after)).doesNotContain(href);
    assertThat(after.get("hiddenByPermission").asInt()).isZero();
    assertThat(after.get("total").asInt()).isZero();

    // 行确实被删掉了（清的是「我自己」的清单）
    assertThat(favoriteRows(tenantOf(limited), href)).isEmpty();
  }

  // ================================================================ 校验

  @Test
  void 外站地址与路径穿越被拒() throws Exception {
    String tag = suffix();
    String member = freshMember(tag, List.of());
    addExpecting(member, "https://evil.example/orders", "钓鱼", "ops", "", 400);
    addExpecting(member, "/orders/../../etc", "穿越", "ops", "", 400);
    addExpecting(member, "//evil.example", "协议相对", "ops", "", 400);
  }

  @Test
  void 未注册的页面码被拒() throws Exception {
    String tag = suffix();
    String member = freshMember(tag, List.of());
    addExpecting(member, "/whatever", "未注册页面", "ops", "not.registered", 400);
  }

  @Test
  void 系统枚举外的归属被拒() throws Exception {
    String tag = suffix();
    String member = freshMember(tag, List.of());
    addExpecting(member, "/whatever", "奇怪归属", "not-a-system", "", 400);
  }

  @Test
  void 标题为空被拒() throws Exception {
    String tag = suffix();
    String member = freshMember(tag, List.of());
    addExpecting(member, "/whatever", "   ", "ops", "", 400);
  }

  @Test
  void 收藏条数有上限() throws Exception {
    String tag = suffix();
    String member = freshMember(tag, List.of());
    for (int i = 0; i < 64; i++) {
      add(member, "/bulk-" + tag.toLowerCase() + "-" + i, "批量 " + i, "ops", "");
    }
    // 第 65 条被拒
    addExpecting(member, "/bulk-" + tag.toLowerCase() + "-overflow", "超量", "ops", "", 400);
  }

  // ================================================================ 老载荷兼容

  @Test
  void 旧载荷缺字段不影响读取() throws Exception {
    String tag = suffix();
    String member = freshMember(tag, List.of());
    String href = "/legacy-" + tag.toLowerCase();
    UUID membershipId = membershipOf(member);
    UUID tenant = tenantOf(member);

    // 直接写一行**旧形状**的载荷：没有 pageCode、没有 order。
    // 「新增字段不影响旧偏好」这条硬约束在 JSONB 上天然成立 —— 读的时候缺键套默认值。
    tx()
        .run(
            tenant,
            () -> {
              sql.execute(
                  "insert into user_preference"
                      + "(id,tenant_id,membership_id,page_key,preference,created_at,updated_at)"
                      + " values (?::uuid,?::uuid,?::uuid,?::varchar,?::jsonb,now(),now())",
                  UUID.randomUUID(),
                  tenant,
                  membershipId,
                  "dashboard.favorites",
                  "{\"items\":[{\"href\":\""
                      + href
                      + "\",\"label\":\"旧收藏\",\"system\":\"ops\"}]}");
              return null;
            });

    JsonNode set = request(member, "GET", "/identity/page-favorites", null, 200);
    assertThat(hrefs(set)).contains(href);
    assertThat(set.get("items").get(0).get("pageCode").asText()).as("缺失的键按空处理").isEmpty();
    assertThat(set.get("items").get(0).get("order").asInt()).isZero();
  }
}
