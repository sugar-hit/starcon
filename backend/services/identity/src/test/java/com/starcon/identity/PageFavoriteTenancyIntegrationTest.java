package com.starcon.identity;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.starcon.tenancy.TenantTransactions;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.Record;
import org.junit.jupiter.api.Test;

/**
 * 1.2.2.1 常用页面收藏 —— **多租户三断言**。
 *
 * <p>{@link PageFavoriteIntegrationTest} 里也有一份三断言，但那个类的主职责是业务行为
 * （收藏 / 取消 / 排序 / 权限闸 / 校验）；隔离是它的附带覆盖。本类把三条铁律
 * <b>单独拉出来实跑</b>，因为本模块的隔离面直接暴露在
 * 「<b>能不能看见同事收藏了哪些页面 / 能不能替别人收藏</b>」上。
 *
 * <ol>
 *   <li><b>两租户互不可见</b>，且<b>同租户内的两个成员也互不可见</b>
 *       —— 收藏是**个人**的，同事之间不该互相看到对方把哪些页面设成了快捷入口；</li>
 *   <li><b>伪造 {@code X-Tenant-Id} 头无效</b>；</li>
 *   <li><b>连接池归还后不串租户</b>。</li>
 * </ol>
 *
 * <p><b>断言落在真实业务表上</b>（{@code user_preference}），并且<b>按主键直查对方的行、
 * 断言读到零行</b>。只在 HTTP 层断言「列表里没有对方数据」是不够的 ——
 * 那个结论可能来自过滤条件写对了，而底下的行照样在库里可见。
 *
 * <p><b>连接池压到 1 条连接</b>（{@link IdentityIntegrationTestBase} 已设）：只有复用同一条物理连接，
 * 「归还后不串租户」才是真的被验证过 —— 池大于 1 时每次可能拿到另一条干净连接，「没串」可能只是碰巧。
 *
 * <p><b>行数断言按「载荷里含这个路径」收窄</b>：本模块所有用例共用同一个
 * {@code page_key = dashboard.favorites}，同一个租户里不同成员各占一行 ——
 * 不按路径收窄的话，行数会随别的用例留下的行浮动。
 */
class PageFavoriteTenancyIntegrationTest extends IdentityIntegrationTestBase {

  private TenantTransactions tx() {
    return new TenantTransactions(
        sql, new org.springframework.transaction.support.TransactionTemplate(manager));
  }

  private UUID tenantOf(String token) throws Exception {
    return UUID.fromString(
        request(token, "GET", "/identity/tenants/current", null, 200).get("tenantId").asText());
  }

  private List<Record> rowsIn(UUID tenant, String statement, Object... bindings) {
    return tx().run(tenant, () -> List.copyOf(sql.fetch(statement, bindings)));
  }

  /** 某个租户里、载荷里含这个路径的收藏行。 */
  private List<Record> favoriteRows(UUID tenant, String href) {
    return rowsIn(
        tenant,
        "select id from user_preference"
            + " where page_key = 'dashboard.favorites'"
            + " and position(?::varchar in preference::text) > 0",
        href);
  }

  private JsonNode add(String token, String href, String label, String system) throws Exception {
    return request(
        token,
        "POST",
        "/identity/page-favorites",
        Map.of("href", href, "label", label, "system", system, "pageCode", ""),
        200);
  }

  private List<String> hrefs(JsonNode set) {
    return set.get("items").findValuesAsText("href");
  }

  /** 在当前租户里另建一个成员（同租户、不同人）。 */
  private String otherMember(String ownerToken, String tag) throws Exception {
    UUID role =
        UUID.fromString(
            request(
                    ownerToken,
                    "POST",
                    "/identity/roles",
                    Map.of(
                        "code",
                        "FP" + tag,
                        "name",
                        "收藏隔离角色 " + tag,
                        "status",
                        "ACTIVE",
                        "permissions",
                        List.of()),
                    200)
                .asText());
    request(
        ownerToken,
        "POST",
        "/identity/members",
        memberInput(
            "fp-" + tag.toLowerCase(),
            null,
            List.of(Map.of("roleId", role.toString(), "scopeMode", "ALL"))),
        200);
    return loginUser("fp-" + tag.toLowerCase());
  }

  /** 另建一个子租户。 */
  private String childTenant(String parentToken, String tag) throws Exception {
    request(
        parentToken,
        "POST",
        "/identity/children",
        Map.of(
            "code",
            "FPC" + tag,
            "name",
            "收藏子租户 " + tag,
            "ownerUsername",
            "fpc-" + tag.toLowerCase(),
            "ownerPassword",
            "test-member-password"),
        200);
    return loginUser("fpc-" + tag.toLowerCase());
  }

  // ------------------------------------------------------------------
  // 断言一：两租户互不可见（同租户内不同成员也互不可见）
  // ------------------------------------------------------------------

  @Test
  void 两租户互不可见且同租户内不同成员互不可见() throws Exception {
    String parent = login();
    String tag = suffix();
    String href = "/orders.isolation-" + tag.toLowerCase();
    add(parent, href, "订单中心", "ops");

    String child = childTenant(parent, tag);
    String colleague = otherMember(parent, tag);

    // 子租户读不到父租户的收藏
    assertThat(hrefs(request(child, "GET", "/identity/page-favorites", null, 200)))
        .as("子租户不得读到父租户的收藏")
        .doesNotContain(href);

    // 同租户的**另一个成员**也读不到：收藏是**个人**的
    assertThat(hrefs(request(colleague, "GET", "/identity/page-favorites", null, 200)))
        .as("同租户的同事不得读到我的收藏")
        .doesNotContain(href);

    // 按主键直查：子租户租户事务里读父租户的行 → 零行
    assertThat(favoriteRows(tenantOf(child), href))
        .as("子租户直查父租户的收藏必须读到零行")
        .isEmpty();
    assertThat(favoriteRows(tenantOf(parent), href))
        .as("父租户自己那一行确实在")
        .hasSize(1);

    // 别人**写**同一个 href 也覆盖不了我的：父租户读回来仍是原来那条
    add(child, href, "子租户写的", "ops");
    add(colleague, href, "同事写的", "ops");
    assertThat(
            request(parent, "GET", "/identity/page-favorites", null, 200)
                .get("items")
                .get(0)
                .get("label")
                .asText())
        .as("别人的写不得覆盖我的收藏")
        .isEqualTo("订单中心");

    // 同事写的是**他自己**那一行：同租户、同一 page_key，但 membership_id 不同
    assertThat(favoriteRows(tenantOf(parent), href))
        .as("同租户不同成员各存各的：两行")
        .hasSize(2);
    assertThat(favoriteRows(tenantOf(child), href))
        .as("子租户写的那一行落在子租户自己的库里")
        .hasSize(1);
  }

  // ------------------------------------------------------------------
  // 断言二：伪造 X-Tenant-Id 头无效
  // ------------------------------------------------------------------

  @Test
  void 伪造租户头无效() throws Exception {
    String parent = login();
    String tag = suffix();
    String href = "/orders.forged-" + tag.toLowerCase();
    add(parent, href, "订单中心", "ops");

    String child = childTenant(parent, tag);
    String childTenantId = tenantOf(child).toString();

    // 伪造一个随机租户 id 与**子租户真实**租户 id：结果都必须不变。
    // 用子租户真实 id 才是真正有杀伤力的那一档 —— 调用方确实知道一个存在的租户 id。
    for (String forged : List.of(UUID.randomUUID().toString(), childTenantId)) {
      String body =
          http.perform(
                  get("/identity/page-favorites")
                      .header("Authorization", "Bearer " + parent)
                      .header("X-Tenant-Id", forged))
              .andExpect(status().isOk())
              .andReturn()
              .getResponse()
              .getContentAsString();
      assertThat(body)
          .as("伪造 X-Tenant-Id=" + forged + " 不得改变可见范围")
          .contains(href);
    }

    // 伪造头的**写**同样无效：这次写仍然落回**父租户**自己的库
    String forgedHref = "/orders.forged2-" + tag.toLowerCase();
    http.perform(
            post("/identity/page-favorites")
                .header("Authorization", "Bearer " + parent)
                .header("X-Tenant-Id", childTenantId)
                .contentType("application/json")
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "href", forgedHref,
                            "label", "伪造头写的",
                            "system", "ops",
                            "pageCode", ""))))
        .andExpect(status().isOk());
    assertThat(favoriteRows(tenantOf(parent), forgedHref))
        .as("伪造租户头的写仍然落在父租户自己的库")
        .hasSize(1);
    assertThat(favoriteRows(tenantOf(child), forgedHref))
        .as("伪造租户头不得把行写进子租户的库")
        .isEmpty();
  }

  // ------------------------------------------------------------------
  // 断言三：连接池归还后不串租户
  // ------------------------------------------------------------------

  @Test
  void 连接池归还后多轮交替读写不串租户() throws Exception {
    String parent = login();
    String tag = suffix();
    String parentHref = "/orders.pool-parent-" + tag.toLowerCase();
    String childHref = "/orders.pool-child-" + tag.toLowerCase();
    add(parent, parentHref, "父租户的收藏", "ops");

    String child = childTenant(parent, tag);
    add(child, childHref, "子租户的收藏", "ops");

    // 池大小是 1（见基类），下面每一轮都必然复用同一条物理连接。
    for (int i = 0; i < 5; i++) {
      // 父租户读：只看得见父租户自己的
      assertThat(hrefs(request(parent, "GET", "/identity/page-favorites", null, 200)))
          .as("第 %d 轮：父租户读必须只见父租户", i)
          .contains(parentHref)
          .doesNotContain(childHref);

      // 子租户读：只看得见子租户自己的
      assertThat(hrefs(request(child, "GET", "/identity/page-favorites", null, 200)))
          .as("第 %d 轮：子租户读必须只见子租户", i)
          .contains(childHref)
          .doesNotContain(parentHref);

      // 交替**写**：子租户每轮换一个收藏，证明读到的是最新那份而不是第一份
      String roundHref = "/orders.pool-round-" + tag.toLowerCase() + "-" + i;
      add(child, roundHref, "子租户第 " + i + " 条", "ops");
      assertThat(favoriteRows(tenantOf(child), roundHref))
          .as("第 %d 轮：子租户的写必须落在子租户自己的库", i)
          .hasSize(1);
      assertThat(favoriteRows(tenantOf(parent), roundHref))
          .as("第 %d 轮：子租户的写不得改变父租户可见的那一行", i)
          .isEmpty();

      // 回到父租户：连接刚被子租户用过，父租户必须仍然只见父租户
      assertThat(hrefs(request(parent, "GET", "/identity/page-favorites", null, 200)))
          .as("第 %d 轮：子租户用过连接之后父租户仍不得串", i)
          .contains(parentHref)
          .doesNotContain(childHref);
    }
  }

  @Test
  void 收藏表在两租户之间都查不到对方的行且均为强制RLS() throws Exception {
    // 事务外（app.current_tenant 未设置）直查必须为空：租户上下文不是「默认放行」
    assertThat(sql.fetch("select id from user_preference")).isEmpty();

    Boolean forced =
        sql.fetchOne("select relforcerowsecurity from pg_class where relname = 'user_preference'")
            .get(0, Boolean.class);
    assertThat(forced).as("user_preference 必须是 FORCE RLS").isTrue();

    // 顺带证明连接确实来自非超级用户、也没有 bypassrls —— 上面两条才有意义
    assertThat(
            sql.fetchOne("select rolbypassrls, rolsuper from pg_roles where rolname=current_user")
                .get("rolbypassrls", Boolean.class))
        .as("应用连接不得持有 bypassrls，否则 FORCE RLS 形同虚设")
        .isFalse();
  }
}
