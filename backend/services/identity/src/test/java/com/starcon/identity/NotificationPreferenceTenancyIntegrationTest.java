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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 1.3.1.2 通知偏好 —— **多租户三断言**。
 *
 * <p>{@link NotificationPreferenceIntegrationTest} 里也有一份三断言，但那个类的主职责是
 * 业务行为（落库、底线项不可关闭、时段语义、时区口径、时间）；隔离是它的附带覆盖。
 * 本类把三条铁律**单独拉出来实跑**，因为本模块的隔离面直接暴露在
 * 「**能不能看见别人的通知偏好 / 能不能替别人改渠道**」上。
 *
 * <ol>
 *   <li><b>两租户互不可见</b>，且**同租户内的两个成员也互不可见** ——
 *       通知偏好是**个人**的，同事之间不该互相看到对方的渠道设置；</li>
 *   <li><b>伪造 {@code X-Tenant-Id} 头无效</b>；</li>
 *   <li><b>连接池归还后不串租户</b>。</li>
 * </ol>
 *
 * <p><b>断言落在真实业务表上</b>（{@code user_preference}，页面 key = {@code notifications.delivery}），
 * 并且**按主键直查对方的行、断言读到零行**。只在 HTTP 层断言「列表里没有对方数据」是不够的 ——
 * 那个结论可能来自过滤条件写对了，而底下的行照样在库里可见。
 *
 * <p>🔴 <b>本模块复用 {@code user_preference} 表</b>，所以隔离靠的是**既有**的
 * {@code tenant_id + membership_id + FORCE RLS} 三件套（V336 建的），
 * 本迁移没有改动那张表 —— 这正是「复用既有表」要兑现的承诺：
 * <b>隔离不因为复用而放松一寸</b>。
 *
 * <p><b>连接池压到 1 条连接</b>（{@link IdentityIntegrationTestBase} 已设）：只有复用同一条物理连接，
 * 「归还后不串租户」才是真的被验证过 —— 池大于 1 时每次可能拿到另一条干净连接、「没串」可能只是碰巧。
 */
class NotificationPreferenceTenancyIntegrationTest extends IdentityIntegrationTestBase {

  @Autowired IdentityController identity;

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

  /**
   * 直查<b>某个成员</b>的那一行偏好。
   *
   * <p>🔴 <b>必须带 membership_id</b>：集成测试共享同一个 PostgreSQL 容器与同一个
   * 本地管理员租户，不同用例的成员行<b>并存于同一张表</b>（RLS 是租户级的，
   * 「只看自己的」由服务层的 membership_id 谓词保证）。只按 page_key 查会读到
   * 别的用例留下的行 —— 那是测试之间的污染，不是本模块的行为。
   */
  private List<Record> myRows(UUID tenant, UUID membershipId) {
    return rowsIn(
        tenant,
        "select id from user_preference where page_key = ?::varchar and membership_id = ?::uuid",
        "notifications.delivery",
        membershipId);
  }

  private JsonNode save(String token, Object body) throws Exception {
    return request(token, "PUT", "/identity/notification-preferences", body, 200);
  }

  private JsonNode read(String token) throws Exception {
    return request(token, "GET", "/identity/notification-preferences", null, 200);
  }

  private List<String> channelsOf(JsonNode view, String type) {
    for (JsonNode setting : view.get("types")) {
      if (type.equals(setting.get("type").asText())) {
        List<String> channels = new java.util.ArrayList<>();
        setting.get("channels").forEach(node -> channels.add(node.asText()));
        return channels;
      }
    }
    throw new AssertionError("生效值里没有类型 " + type);
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
                        "NPT" + tag,
                        "name",
                        "通知偏好隔离角色 " + tag,
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
            "npt-" + tag.toLowerCase(), null, List.of(Map.of("roleId", role.toString(), "scopeMode", "ALL"))),
        200);
    return loginUser("npt-" + tag.toLowerCase());
  }

  /** 另建一个子租户。 */
  private String childTenant(String parentToken, String tag) throws Exception {
    request(
        parentToken,
        "POST",
        "/identity/children",
        Map.of(
            "code",
            "NPTC" + tag,
            "name",
            "通知偏好子租户 " + tag,
            "ownerUsername",
            "nptc-" + tag.toLowerCase(),
            "ownerPassword",
            "test-member-password"),
        200);
    return loginUser("nptc-" + tag.toLowerCase());
  }

  // ------------------------------------------------------------------
  // 断言一：两租户互不可见（同租户内不同成员也互不可见）
  // ------------------------------------------------------------------

  @Test
  void 两租户互不可见且同租户内不同成员互不可见() throws Exception {
    String parent = login();
    String tag = suffix();
    save(parent, Map.of("types", List.of(Map.of("type", "MESSAGE", "channels", List.of("IN_APP")))));

    String child = childTenant(parent, tag);
    String colleague = otherMember(parent, tag);

    // 子租户读不到父租户的那份偏好（他看到的是默认的全开）。
    assertThat(channelsOf(read(child), "MESSAGE"))
        .as("子租户不得读到父租户的通知偏好")
        .containsExactly("IN_APP", "EMAIL", "IM");
    assertThat(read(child).get("stored").asBoolean()).isFalse();

    // 同租户的**另一个成员**也读不到：通知偏好是**个人**的。
    assertThat(channelsOf(read(colleague), "MESSAGE"))
        .as("同租户的同事不得读到我的通知偏好")
        .containsExactly("IN_APP", "EMAIL", "IM");
    assertThat(read(colleague).get("stored").asBoolean()).isFalse();

    // 按 page key 直查：子租户租户事务里读父租户的行 → 零行。
    UUID parentMembershipId = identity.resolve("Bearer " + parent).membershipId();
    UUID childMembershipId = identity.resolve("Bearer " + child).membershipId();
    assertThat(myRows(tenantOf(child), childMembershipId))
        .as("子租户直查父租户的通知偏好必须读到零行")
        .isEmpty();
    assertThat(myRows(tenantOf(parent), parentMembershipId))
        .as("父租户自己那一行确实在")
        .hasSize(1);

    // 🔴 **同租户内不同成员**：偏好是**按成员**记的。
    //   RLS 是**租户级**的（同租户的两行本来就并存于同一张表），
    //   「只看自己的」由服务层的 membership_id 谓词保证 ——
    //   所以这里按 membership_id 断言，而不是按租户数行。
    UUID parentMembership = identity.resolve("Bearer " + parent).membershipId();
    UUID colleagueMembership = identity.resolve("Bearer " + colleague).membershipId();
    assertThat(myRows(tenantOf(parent), parentMembership))
        .as("父租户里属于我的那一行确实在")
        .hasSize(1);
    assertThat(myRows(tenantOf(parent), colleagueMembership))
        .as("同事的成员 id 下没有这一行（通知偏好是个人状态）")
        .isEmpty();

    // 别人**写**同一个 page key 也覆盖不了我的：父租户读回来仍是原样。
    save(child, Map.of("types", List.of(Map.of("type", "MESSAGE", "channels", List.of("EMAIL")))));
    assertThat(channelsOf(read(parent), "MESSAGE"))
        .as("子租户的写不得改掉父租户那一行")
        .containsExactly("IN_APP");
    assertThat(myRows(tenantOf(child), childMembershipId))
        .as("子租户写的那一行落在子租户自己的库里")
        .hasSize(1);
    assertThat(myRows(tenantOf(parent), parentMembershipId))
        .as("父租户自己那一行不受影响")
        .hasSize(1);
  }

  // ------------------------------------------------------------------
  // 断言二：伪造 X-Tenant-Id 头无效
  // ------------------------------------------------------------------

  @Test
  void 伪造租户头无效() throws Exception {
    String parent = login();
    String tag = suffix();
    save(parent, Map.of("types", List.of(Map.of("type", "MESSAGE", "channels", List.of("IN_APP")))));

    String child = childTenant(parent, tag);
    String childTenantId = tenantOf(child).toString();

    // 伪造一个随机租户 id 与**子租户真实**租户 id：结果都必须不变。
    // 用子租户真实 id 才是真正有杀伤力的那一档 —— 调用方确实知道一个存在的租户 id。
    for (String forged : List.of(UUID.randomUUID().toString(), childTenantId)) {
      String body =
          http.perform(
                  get("/identity/notification-preferences")
                      .header("Authorization", "Bearer " + parent)
                      .header("X-Tenant-Id", forged))
              .andExpect(status().isOk())
              .andReturn()
              .getResponse()
              .getContentAsString();
      assertThat(body)
          .as("伪造 X-Tenant-Id=" + forged + " 不得改变可见范围")
          .contains("\"channels\":[\"IN_APP\"]");
    }

    // 伪造头的**写**同样无效：这次写仍然落回**父租户**自己的库。
    http.perform(
            put("/identity/notification-preferences")
                .header("Authorization", "Bearer " + parent)
                .header("X-Tenant-Id", childTenantId)
                .contentType("application/json")
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "types",
                            List.of(Map.of("type", "STOCKOUT", "channels", List.of("EMAIL")))))))
        .andExpect(status().isOk());
    assertThat(myRows(tenantOf(parent), identity.resolve("Bearer " + parent).membershipId()))
        .as("伪造租户头的写仍然落在父租户自己的库")
        .hasSize(1);
    assertThat(myRows(tenantOf(child), identity.resolve("Bearer " + child).membershipId()))
        .as("伪造租户头不得把行写进子租户的库")
        .isEmpty();
    assertThat(channelsOf(read(parent), "STOCKOUT"))
        .as("父租户自己那一行仍然是伪造头那次写的内容")
        .containsExactly("EMAIL");
  }

  // ------------------------------------------------------------------
  // 断言三：连接池归还后不串租户
  // ------------------------------------------------------------------

  @Test
  void 连接池归还后多轮交替读写不串租户() throws Exception {
    String parent = login();
    String tag = suffix();
    save(parent, Map.of("types", List.of(Map.of("type", "MESSAGE", "channels", List.of("IN_APP")))));

    String child = childTenant(parent, tag);
    save(child, Map.of("types", List.of(Map.of("type", "MESSAGE", "channels", List.of("EMAIL")))));
    UUID parentMembershipId = identity.resolve("Bearer " + parent).membershipId();
    UUID childMembershipId = identity.resolve("Bearer " + child).membershipId();

    // 池大小是 1（见基类），下面每一轮都必然复用同一条物理连接。
    String expectedChild = "EMAIL";
    for (int i = 0; i < 5; i++) {
      assertThat(channelsOf(read(parent), "MESSAGE"))
          .as("第 %d 轮：父租户读必须只见父租户", i)
          .containsExactly("IN_APP");
      assertThat(channelsOf(read(child), "MESSAGE"))
          .as("第 %d 轮：子租户读必须只见子租户", i)
          .containsExactly(expectedChild);

      // 交替**写**：每轮换一个渠道，证明读到的是最新那份而不是第一份。
      String channel = i % 2 == 0 ? "IM" : "EMAIL";
      save(child, Map.of("types", List.of(Map.of("type", "MESSAGE", "channels", List.of(channel)))));
      expectedChild = channel;
      assertThat(channelsOf(read(child), "MESSAGE"))
          .as("第 %d 轮：子租户读到的必须是最新那一份", i)
          .containsExactly(channel);
      assertThat(channelsOf(read(parent), "MESSAGE"))
          .as("第 %d 轮：子租户用过连接之后父租户仍不得串", i)
          .containsExactly("IN_APP");

      assertThat(myRows(tenantOf(child), childMembershipId))
          .as("第 %d 轮：子租户那一行落在子租户自己的库", i)
          .hasSize(1);
      assertThat(myRows(tenantOf(parent), parentMembershipId))
          .as("第 %d 轮：父租户那一行不受影响", i)
          .hasSize(1);
    }
  }

  @Test
  void 偏好表是强制RLS且运行角色绕不过() throws Exception {
    // 事务外（app.current_tenant 未设置）直查必须为空：租户上下文不是「默认放行」。
    assertThat(sql.fetch("select id from user_preference")).isEmpty();

    Boolean forced =
        sql.fetchOne("select relforcerowsecurity from pg_class where relname = 'user_preference'")
            .get(0, Boolean.class);
    assertThat(forced).as("user_preference 必须是 FORCE RLS").isTrue();

    // 顺带证明连接确实来自非超级用户、也没有 bypassrls —— 上面那条才有意义。
    assertThat(
            sql.fetchOne("select rolbypassrls, rolsuper from pg_roles where rolname=current_user")
                .get("rolbypassrls", Boolean.class))
        .as("应用连接不得持有 bypassrls，否则 FORCE RLS 形同虚设")
        .isFalse();
  }
}
