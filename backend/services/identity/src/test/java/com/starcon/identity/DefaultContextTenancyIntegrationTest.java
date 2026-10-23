package com.starcon.identity;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.starcon.tenancy.TenantTransactions;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.Record;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 1.4.2.2 默认店铺与仓库 —— <b>多租户三断言</b>。
 *
 * <ol>
 *   <li><b>两租户互不可见</b>，且**同租户内的两个成员也互不可见** —— 默认上下文是
 *       <b>个人</b>的，同事之间不该看到对方默认落在哪个店铺；</li>
 *   <li><b>伪造 {@code X-Tenant-Id} 头无效</b>；</li>
 *   <li><b>连接池归还后不串租户</b>。</li>
 * </ol>
 *
 * <p><b>断言落在真实业务表上</b>（{@code user_preference}），并且**按主键直查对方的行、
 * 断言读到零行**。只在 HTTP 层断言「列表里没有对方数据」是不够的 —— 那个结论可能来自
 * 过滤条件写对了，而底下的行照样在库里可见。
 *
 * <p><b>池压到 1 条连接</b>（{@link IdentityIntegrationTestBase} 已设）：只有复用同一条物理连接，
 * 「归还后不串租户」才是真的被验证过。
 */
class DefaultContextTenancyIntegrationTest extends DefaultContextTestBase {

  @BeforeEach
  void reset() throws Exception {
    clearStubs();
    // 默认上下文是「一页一行」，上一个用例存过的值会留在同一个租户的同一行里；
    // 每个用例开始前先清干净，断言才只反映本用例的行为。
    request(login(), "DELETE", "/identity/default-context", null, 200);
  }

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

  private JsonNode save(String token, String shopId) throws Exception {
    return request(
        token,
        "PUT",
        "/identity/default-context",
        Map.of("shopId", shopId),
        200);
  }

  private JsonNode read(String token) throws Exception {
    return request(token, "GET", "/identity/default-context", null, 200);
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
                        "DCT" + tag,
                        "name",
                        "默认上下文隔离角色 " + tag,
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
            "dct-" + tag.toLowerCase(),
            null,
            List.of(Map.of("roleId", role.toString(), "scopeMode", "ALL"))),
        200);
    return loginUser("dct-" + tag.toLowerCase());
  }

  /** 另建一个子租户。 */
  private String childTenant(String parentToken, String tag) throws Exception {
    request(
        parentToken,
        "POST",
        "/identity/children",
        Map.of(
            "code",
            "DCC" + tag,
            "name",
            "默认上下文子租户 " + tag,
            "ownerUsername",
            "dcc-" + tag.toLowerCase(),
            "ownerPassword",
            "test-member-password"),
        200);
    return loginUser("dcc-" + tag.toLowerCase());
  }

  // ------------------------------------------------------------------
  // 断言一：两租户互不可见（同租户内不同成员也互不可见）
  // ------------------------------------------------------------------

  @Test
  void 两租户互不可见且同租户内不同成员互不可见() throws Exception {
    String parent = login();
    String tag = suffix();
    UUID parentShop = UUID.randomUUID();
    stubShops(parent, shopListJson(shopJson(parentShop, "DP-" + tag, "父租户店", "ACTIVE")));
    stubWarehouses(parent, warehouseListJson());

    save(parent, parentShop.toString());

    String child = childTenant(parent, tag);
    String colleague = otherMember(parent, tag);
    UUID childShop = UUID.randomUUID();
    UUID colleagueShop = UUID.randomUUID();
    stubShops(child, shopListJson(shopJson(childShop, "DC-" + tag, "子租户店", "ACTIVE")));
    stubWarehouses(child, warehouseListJson());
    stubShops(colleague, shopListJson(shopJson(colleagueShop, "DO-" + tag, "同事的店", "ACTIVE")));
    stubWarehouses(colleague, warehouseListJson());

    // 子租户读不到父租户的那一份
    var childView = read(child);
    assertThat(childView.get("stored").asBoolean()).as("子租户不得读到父租户的默认上下文").isFalse();
    assertThat(childView.get("shop").isNull()).isTrue();

    // 同租户的**另一个成员**也读不到：默认上下文是**个人**的
    assertThat(read(colleague).get("stored").asBoolean())
        .as("同租户的同事不得读到我的默认上下文")
        .isFalse();

    // 按主键直查：子租户租户事务里读父租户的行 → 零行
    assertThat(
            rowsIn(
                tenantOf(child),
                "select id from user_preference where page_key = ?",
                DefaultContextRules.PAGE_KEY))
        .as("子租户按页面 key 直查父租户的默认上下文必须读到零行")
        .isEmpty();
    assertThat(
            rowsIn(
                tenantOf(parent),
                "select id from user_preference where page_key = ?",
                DefaultContextRules.PAGE_KEY))
        .as("父租户自己那一行确实在")
        .hasSize(1);

    // 别人**写**同一个页面 key 也覆盖不了我的
    save(child, childShop.toString());
    save(colleague, colleagueShop.toString());
    assertThat(read(parent).get("shop").get("id").asText())
        .as("别人的写不得覆盖我的默认上下文")
        .isEqualTo(parentShop.toString());
    assertThat(
            rowsIn(
                tenantOf(parent),
                "select id from user_preference where page_key = ?",
                DefaultContextRules.PAGE_KEY))
        .as("同租户不同成员各存各的：两行")
        .hasSize(2);
    assertThat(
            rowsIn(
                tenantOf(child),
                "select id from user_preference where page_key = ?",
                DefaultContextRules.PAGE_KEY))
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
    UUID parentShop = UUID.randomUUID();
    stubShops(parent, shopListJson(shopJson(parentShop, "DF-" + tag, "父租户店", "ACTIVE")));
    stubWarehouses(parent, warehouseListJson());
    save(parent, parentShop.toString());

    String child = childTenant(parent, tag);
    stubShops(child, shopListJson());
    stubWarehouses(child, warehouseListJson());
    String childTenantId = tenantOf(child).toString();

    // 伪造一个随机租户 id 与**子租户真实**租户 id：结果都必须不变。
    for (String forged : List.of(UUID.randomUUID().toString(), childTenantId)) {
      String body =
          http.perform(
                  get("/identity/default-context")
                      .header("Authorization", "Bearer " + parent)
                      .header("X-Tenant-Id", forged))
              .andExpect(status().isOk())
              .andReturn()
              .getResponse()
              .getContentAsString();
      assertThat(body)
          .as("伪造 X-Tenant-Id=" + forged + " 不得改变可见范围")
          .contains("\"stored\":true")
          .contains(parentShop.toString());
    }

    // 伪造头的**写**同样无效：这次写仍然落回**父租户**自己的库
    // （写的店铺必须在**父租户**自己的可见清单里 —— 否则会被 400 挡掉，测的就不是伪造头了）
    UUID anotherParentShop = UUID.randomUUID();
    stubShops(
        parent,
        shopListJson(
            shopJson(parentShop, "DF-" + tag, "父租户店", "ACTIVE"),
            shopJson(anotherParentShop, "DF2-" + tag, "父租户另一个店", "ACTIVE")));
    http.perform(
            put("/identity/default-context")
                .header("Authorization", "Bearer " + parent)
                .header("X-Tenant-Id", childTenantId)
                .contentType("application/json")
                .content(
                    json.writeValueAsString(
                        Map.of("shopId", anotherParentShop.toString()))))
        .andExpect(status().isOk());
    assertThat(
            rowsIn(
                tenantOf(parent),
                "select id from user_preference where page_key = ?",
                DefaultContextRules.PAGE_KEY))
        .as("伪造租户头的写仍然落在父租户自己的库")
        .hasSize(1);
    assertThat(
            rowsIn(
                tenantOf(child),
                "select id from user_preference where page_key = ?",
                DefaultContextRules.PAGE_KEY))
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
    UUID parentShop = UUID.randomUUID();
    stubShops(parent, shopListJson(shopJson(parentShop, "DPool-" + tag, "父租户店", "ACTIVE")));
    stubWarehouses(parent, warehouseListJson());
    save(parent, parentShop.toString());

    String child = childTenant(parent, tag);
    UUID childShop = UUID.randomUUID();
    stubShops(child, shopListJson(shopJson(childShop, "C-" + tag, "子租户店", "ACTIVE")));
    stubWarehouses(child, warehouseListJson());
    save(child, childShop.toString());

    // 池大小是 1（见基类），下面每一轮都必然复用同一条物理连接。
    //
    // 断言用「行数不变」而不是「父租户只有一行」：同一个租户里可能还有别的成员
    // （本类的另一个用例会在父租户里造一个同事并给他存一份），那是**别人的**行，
    // 与本用例要证明的「子租户的写不落到父租户」无关。
    long parentRowsBefore =
        rowsIn(
                tenantOf(parent),
                "select id from user_preference where page_key = ?",
                DefaultContextRules.PAGE_KEY)
            .size();
    long childRowsBefore =
        rowsIn(
                tenantOf(child),
                "select id from user_preference where page_key = ?",
                DefaultContextRules.PAGE_KEY)
            .size();
    String expectedChildShop = childShop.toString();
    for (int i = 0; i < 5; i++) {
      assertThat(read(parent).get("shop").get("id").asText())
          .as("第 %d 轮：父租户读必须只见父租户", i)
          .isEqualTo(parentShop.toString());
      assertThat(read(child).get("shop").get("id").asText())
          .as("第 %d 轮：子租户读必须只见子租户", i)
          .isEqualTo(expectedChildShop);

      // 交替**写**：每轮换一个值，证明读到的是最新那份而不是第一份
      UUID next = UUID.randomUUID();
      stubShops(child, shopListJson(shopJson(next, "C-" + tag + "-" + i, "子租户店", "ACTIVE")));
      save(child, next.toString());
      expectedChildShop = next.toString();
      assertThat(
              rowsIn(
                  tenantOf(child),
                  "select id from user_preference where page_key = ?",
                  DefaultContextRules.PAGE_KEY))
          .as("第 %d 轮：子租户的写必须落在子租户自己的库", i)
          .hasSize(1);
      assertThat(
              rowsIn(
                      tenantOf(parent),
                      "select id from user_preference where page_key = ?",
                      DefaultContextRules.PAGE_KEY)
                  .size())
          .as("第 %d 轮：子租户的写不得在父租户里多出一行", i)
          .isEqualTo(parentRowsBefore);
      assertThat(
              rowsIn(
                      tenantOf(child),
                      "select id from user_preference where page_key = ?",
                      DefaultContextRules.PAGE_KEY)
                  .size())
          .as("第 %d 轮：子租户自己的那一行还在", i)
          .isEqualTo(childRowsBefore);
      assertThat(
              rowsIn(
                      tenantOf(parent),
                      "select preference::text as pref from user_preference where page_key = ?",
                      DefaultContextRules.PAGE_KEY)
                  .stream()
                  .anyMatch(row -> row.get("pref", String.class).contains(parentShop.toString())))
          .as("第 %d 轮：父租户自己那一行的内容没有被子租户改掉", i)
          .isTrue();

      // 回到父租户：连接刚被子租户用过，父租户必须仍然只见父租户
      assertThat(read(parent).get("shop").get("id").asText())
          .as("第 %d 轮：子租户用过连接之后父租户仍不得串", i)
          .isEqualTo(parentShop.toString());
    }
  }

  @Test
  void 默认上下文表在两租户之间都查不到对方的行且均为强制RLS() throws Exception {
    // 事务外（app.current_tenant 未设置）直查必须为空：租户上下文不是「默认放行」
    assertThat(sql.fetch("select id from user_preference")).isEmpty();

    Boolean forced =
        sql.fetchOne("select relforcerowsecurity from pg_class where relname = 'user_preference'")
            .get(0, Boolean.class);
    assertThat(forced).as("user_preference 必须是 FORCE RLS").isTrue();

    assertThat(
            sql.fetchOne("select rolbypassrls, rolsuper from pg_roles where rolname=current_user")
                .get("rolbypassrls", Boolean.class))
        .as("应用连接不得持有 bypassrls，否则 FORCE RLS 形同虚设")
        .isFalse();
  }
}
