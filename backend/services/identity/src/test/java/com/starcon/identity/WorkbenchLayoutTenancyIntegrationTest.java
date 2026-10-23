package com.starcon.identity;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.starcon.tenancy.TenantTransactions;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.Record;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 1.4.1.1 工作台布局 —— **多租户三断言**。
 *
 * <p>工作台布局是**纯个人偏好**：「我这一份工作台怎么摆」既不该被同租户的同事看到，
 * 也不该被别的租户看到，更不该被伪造的租户头改到别处去。所以本模块的隔离面
 * 直接暴露在「**能不能看见别人的工作台布局 / 能不能替别人改布局**」上，三条铁律
 * 必须**单独实跑**，不能只靠 {@link WorkbenchLayoutIntegrationTest} 的附带覆盖。
 *
 * <ol>
 *   <li><b>两租户互不可见</b>，且**同租户内的两个成员也互不可见**；</li>
 *   <li><b>伪造 {@code X-Tenant-Id} 头无效</b>；</li>
 *   <li><b>连接池归还后不串租户</b>。</li>
 * </ol>
 *
 * <p><b>断言落在真实业务表上</b>（{@code user_preference}），并且**按 page_key 直查对方的行、
 * 断言读到零行**。只在 HTTP 层断言「列表里没有对方数据」是不够的 ——
 * 那个结论可能来自过滤条件写对了，而底下的行照样在库里可见。
 *
 * <p><b>连接池压到 1 条连接</b>（{@link IdentityIntegrationTestBase} 已设）：只有复用同一条物理连接，
 * 「归还后不串租户」才是真的被验证过 —— 池大于 1 时每次可能拿到另一条干净连接，「没串」可能只是碰巧。
 *
 * <p>本页用的 page_key 是本模块**真实的** {@code dashboard.layout}（不是随机 key）：
 * 隔离要证明的正是「用户真正会用的那个 key」上的数据不串。
 */
class WorkbenchLayoutTenancyIntegrationTest extends IdentityIntegrationTestBase {

  /** 本模块的页面 key。 */
  private static final String PAGE_KEY = "dashboard.layout";

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

  /** 存一份带**可辨识值**的布局（用来断言「读到的是不是自己那一份」）。 */
  private JsonNode save(String token, String defaultTab, String hiddenCard) throws Exception {
    var payload = new LinkedHashMap<String, Object>();
    payload.put("order", List.of("todo.anomaly-orders", "risk.inventory"));
    payload.put("hidden", List.of(hiddenCard));
    payload.put("defaultTab", defaultTab);
    return request(
        token,
        "PUT",
        "/identity/user-preferences/" + PAGE_KEY,
        Map.of("preference", payload),
        200);
  }

  private JsonNode read(String token) throws Exception {
    return request(token, "GET", "/identity/user-preferences/" + PAGE_KEY, null, 200);
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
                        "WL" + tag,
                        "name",
                        "布局隔离角色 " + tag,
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
            "wl-" + tag.toLowerCase(),
            null,
            List.of(Map.of("roleId", role.toString(), "scopeMode", "ALL"))),
        200);
    return loginUser("wl-" + tag.toLowerCase());
  }

  /** 另建一个子租户。 */
  private String childTenant(String parentToken, String tag) throws Exception {
    request(
        parentToken,
        "POST",
        "/identity/children",
        Map.of(
            "code",
            "WLC" + tag,
            "name",
            "布局子租户 " + tag,
            "ownerUsername",
            "wlc-" + tag.toLowerCase(),
            "ownerPassword",
            "test-member-password"),
        200);
    return loginUser("wlc-" + tag.toLowerCase());
  }

  /**
   * 每个用例开始前把这一页清干净。
   *
   * <p>本模块的 page_key 是**固定的**（不是随机 key），所以隔离靠清场不靠换 key。
   * 清的是**本租户内这一页的所有行**（含同租户其它成员留下的）——
   * 偏好表的 RLS 只管到租户级，「只看我自己的」是服务层的 membership_id 谓词。
   */
  @BeforeEach
  void 清掉上一条用例留下的布局() throws Exception {
    UUID tenant = tenantOf(login());
    tx()
        .run(
            tenant,
            () -> sql.execute("delete from user_preference where page_key = ?", PAGE_KEY));
  }

  // ------------------------------------------------------------------
  // 断言一：两租户互不可见（同租户内不同成员也互不可见）
  // ------------------------------------------------------------------

  @Test
  void 两租户互不可见且同租户内不同成员互不可见() throws Exception {
    String parent = login();
    String tag = suffix();
    save(parent, "risk", "nav.shortcuts");

    String child = childTenant(parent, tag);
    String colleague = otherMember(parent, tag);

    // 子租户读不到父租户的这一页
    assertThat(read(child).get("stored").asBoolean())
        .as("子租户不得读到父租户的工作台布局")
        .isFalse();

    // 同租户的**另一个成员**也读不到：布局是**个人**的
    assertThat(read(colleague).get("stored").asBoolean())
        .as("同租户的同事不得读到我的工作台布局")
        .isFalse();

    // 按主键直查：子租户租户事务里读父租户的行 → 零行
    assertThat(rowsIn(tenantOf(child), "select id from user_preference where page_key = ?", PAGE_KEY))
        .as("子租户按 page_key 直查父租户的布局必须读到零行")
        .isEmpty();
    assertThat(rowsIn(tenantOf(parent), "select id from user_preference where page_key = ?", PAGE_KEY))
        .as("父租户自己那一行确实在")
        .hasSize(1);

    // 别人**写**同一个 page_key 也覆盖不了我的：父租户读回来仍是原值
    save(child, "overview", "nav.global-search");
    save(colleague, "todo", "metric.fulfillment-health");
    assertThat(read(parent).get("preference").get("defaultTab").asText())
        .as("别人的写不得覆盖我的工作台布局")
        .isEqualTo("risk");
    // 同事写的是**他自己**那一行：同租户、同一 page_key，但 membership_id 不同
    assertThat(rowsIn(tenantOf(parent), "select id from user_preference where page_key = ?", PAGE_KEY))
        .as("同租户不同成员各存各的：两行")
        .hasSize(2);
    assertThat(rowsIn(tenantOf(child), "select id from user_preference where page_key = ?", PAGE_KEY))
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
    save(parent, "risk", "nav.shortcuts");

    String child = childTenant(parent, tag);
    String childTenantId = tenantOf(child).toString();

    // 伪造一个随机租户 id 与**子租户真实**租户 id：结果都必须不变。
    // 用子租户真实 id 才是真正有杀伤力的那一档 —— 调用方确实知道一个存在的租户 id。
    for (String forged : List.of(UUID.randomUUID().toString(), childTenantId)) {
      String body =
          http.perform(
                  get("/identity/user-preferences/" + PAGE_KEY)
                      .header("Authorization", "Bearer " + parent)
                      .header("X-Tenant-Id", forged))
              .andExpect(status().isOk())
              .andReturn()
              .getResponse()
              .getContentAsString();
      assertThat(body)
          .as("伪造 X-Tenant-Id=" + forged + " 不得改变可见范围")
          .contains("\"stored\":true")
          .contains("\"defaultTab\":\"risk\"");
    }

    // 伪造头的**写**同样无效：这次写仍然落回**父租户**自己的库
    http.perform(
            put("/identity/user-preferences/" + PAGE_KEY)
                .header("Authorization", "Bearer " + parent)
                .header("X-Tenant-Id", childTenantId)
                .contentType("application/json")
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "preference",
                            Map.of(
                                "order", List.of("risk.inventory"),
                                "hidden", List.of(),
                                "defaultTab", "overview")))))
        .andExpect(status().isOk());
    assertThat(
            rowsIn(
                tenantOf(parent),
                "select preference::text as pref from user_preference where page_key = ?",
                PAGE_KEY))
        .as("伪造租户头的写仍然落在父租户自己的库")
        .hasSize(1);
    assertThat(rowsIn(tenantOf(child), "select id from user_preference where page_key = ?", PAGE_KEY))
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
    save(parent, "risk", "nav.shortcuts");

    String child = childTenant(parent, tag);
    // 子租户当前那一份的值（每轮结束时会被换成另一个值，用来证明读到的是**最新**那份）。
    String childTab = "overview";
    save(child, childTab, "nav.global-search");

    // 池大小是 1（见基类），下面每一轮都必然复用同一条物理连接。
    for (int i = 0; i < 5; i++) {
      // 父租户读：只看得见父租户自己的
      String parentRead =
          http.perform(
                  get("/identity/user-preferences/" + PAGE_KEY)
                      .header("Authorization", "Bearer " + parent))
              .andExpect(status().isOk())
              .andReturn()
              .getResponse()
              .getContentAsString();
      assertThat(parentRead)
          .as("第 %d 轮：父租户读必须只见父租户", i)
          .contains("\"defaultTab\":\"risk\"")
          .doesNotContain("\"defaultTab\":\"" + childTab + "\"");

      // 子租户读：只看得见子租户自己的
      String childRead =
          http.perform(
                  get("/identity/user-preferences/" + PAGE_KEY)
                      .header("Authorization", "Bearer " + child))
              .andExpect(status().isOk())
              .andReturn()
              .getResponse()
              .getContentAsString();
      assertThat(childRead)
          .as("第 %d 轮：子租户读必须只见子租户", i)
          .contains("\"defaultTab\":\"" + childTab + "\"")
          .doesNotContain("\"defaultTab\":\"risk\"");

      // 交替**写**：子租户写自己那一份（每轮换一个值，证明读到的是最新那份而不是第一份）
      childTab = childTab.equals("todo") ? "overview" : "todo";
      save(child, childTab, "nav.global-search");
      assertThat(
              rowsIn(
                  tenantOf(child),
                  "select preference::text as pref from user_preference where page_key = ?",
                  PAGE_KEY))
          .as("第 %d 轮：子租户的写必须落在子租户自己的库", i)
          .hasSize(1);
      assertThat(
              rowsIn(
                  tenantOf(parent),
                  "select preference::text as pref from user_preference where page_key = ?",
                  PAGE_KEY))
          .as("第 %d 轮：子租户的写不得改变父租户可见的那一行", i)
          .hasSize(1);

      // 回到父租户：连接刚被子租户用过，父租户必须仍然只见父租户
      String parentAgain =
          http.perform(
                  get("/identity/user-preferences/" + PAGE_KEY)
                      .header("Authorization", "Bearer " + parent))
              .andExpect(status().isOk())
              .andReturn()
              .getResponse()
              .getContentAsString();
      assertThat(parentAgain)
          .as("第 %d 轮：子租户用过连接之后父租户仍不得串", i)
          .contains("\"defaultTab\":\"risk\"")
          .doesNotContain("\"defaultTab\":\"" + childTab + "\"");
    }
  }

  @Test
  void 布局表在两租户之间都查不到对方的行且均为强制RLS() throws Exception {
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
