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

/**
 * 1.4.2.1 语言与时区 —— <b>多租户三断言</b>。
 *
 * <p>本模块存的是「我这个人选的展示时区」。它离业务数据远，但离「个人」近得吓人：
 * 时区偏好一旦串了，A 看到的「今天」变成 B 的「今天」，而 A 完全不知道发生了什么。
 * 所以三条铁律必须**单独实跑**，而不是当作业务测试的附带覆盖：
 *
 * <ol>
 *   <li><b>两租户互不可见</b>，且<b>同租户内的两个成员也互不可见</b>（偏好是个人的）；</li>
 *   <li><b>伪造 {@code X-Tenant-Id} 头无效</b>；</li>
 *   <li><b>连接池归还后不串租户</b>。</li>
 * </ol>
 *
 * <p><b>断言落在真实业务表上</b>（{@code user_preference}），并按 page_key + membership_id
 * 直查对方的行、断言读到零行 —— 只在 HTTP 层断言「读不到」是不够的：那个结论可能来自
 * 过滤条件写对了，而底下的行照样在库里可见。
 *
 * <p><b>连接池压到 1 条连接</b>（{@link IdentityIntegrationTestBase} 已设）：只有复用同一条物理连接，
 * 「归还后不串租户」才是真的被验证过。
 */
class AccountLocaleTenancyIntegrationTest extends IdentityIntegrationTestBase {

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
   * <p>走公开的 {@code GET /auth/memberships}：{@code app_user} 对应用角色不可读，硬查会撞权限。
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

  /** 在某个租户的事务里按 membership 查行（用来验证「读不到别人的行」）。 */
  private List<Record> rowsIn(UUID tenant, UUID membership) {
    return tx()
        .run(
            tenant,
            () ->
                List.copyOf(
                    sql.fetch(
                        "select id from user_preference"
                            + " where page_key = ? and membership_id = ?::uuid",
                        "account.locale",
                        membership)));
  }

  /** 某个会话自己那一页的行。 */
  private List<Record> rowsOf(String token) throws Exception {
    return rowsIn(tenantOf(token), membershipOf(token));
  }

  private JsonNode save(String token, String timezone) throws Exception {
    return request(
        token, "PUT", "/identity/user-locale", Map.of("locale", "zh-CN", "timezone", timezone), 200);
  }

  private JsonNode read(String token) throws Exception {
    return request(token, "GET", "/identity/user-locale", null, 200);
  }

  /** 登录并清掉这一页的偏好：同一测试类里的用例共用同一个成员，起点必须一致。 */
  private String freshLogin() throws Exception {
    String token = login();
    request(token, "DELETE", "/identity/user-locale", null, 200);
    return token;
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
                        "AL" + tag,
                        "name",
                        "时区隔离角色 " + tag,
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
            "al-" + tag.toLowerCase(),
            null,
            List.of(Map.of("roleId", role.toString(), "scopeMode", "ALL"))),
        200);
    return loginUser("al-" + tag.toLowerCase());
  }

  /** 另建一个子租户。 */
  private String childTenant(String parentToken, String tag) throws Exception {
    request(
        parentToken,
        "POST",
        "/identity/children",
        Map.of(
            "code",
            "ALC" + tag,
            "name",
            "时量子租户 " + tag,
            "ownerUsername",
            "alc-" + tag.toLowerCase(),
            "ownerPassword",
            "test-member-password"),
        200);
    return loginUser("alc-" + tag.toLowerCase());
  }

  // ------------------------------------------------------------------
  // 断言一：两租户互不可见（同租户内不同成员互不可见）
  // ------------------------------------------------------------------

  @Test
  void 两租户互不可见且同租户内不同成员互不可见() throws Exception {
    String parent = freshLogin();
    String tag = suffix();
    save(parent, "America/New_York");

    String child = childTenant(parent, tag);
    String colleague = otherMember(parent, tag);
    assertThat(read(child).get("stored").asBoolean()).as("子租户读不到父租户的偏好").isFalse();
    assertThat(read(child).get("timezone").asText())
        .as("子租户拿到的是平台默认")
        .isEqualTo("Asia/Shanghai");
    assertThat(read(colleague).get("stored").asBoolean())
        .as("同租户的同事不得读到我的偏好")
        .isFalse();

    // 按主键直查：子租户租户事务里读父租户的行 → 零行
    UUID parentMembership = membershipOf(parent);
    assertThat(rowsIn(tenantOf(child), parentMembership))
        .as("子租户直查父租户的行必须零行")
        .isEmpty();
    assertThat(rowsOf(parent)).as("父租户自己那一行确实在").hasSize(1);

    // 别人**写**同一个 page key 覆盖不了我的
    save(child, "Asia/Tokyo");
    save(colleague, "Europe/Berlin");
    assertThat(read(parent).get("timezone").asText())
        .as("别人的写不得覆盖我的偏好")
        .isEqualTo("America/New_York");
    assertThat(rowsOf(parent)).as("父租户自己那一行没被动").hasSize(1);
    assertThat(rowsOf(colleague)).as("同事写的是他自己那一行").hasSize(1);
    assertThat(rowsOf(child)).as("子租户写的那一行落在子租户自己的库里").hasSize(1);
  }

  // ------------------------------------------------------------------
  // 断言二：伪造 X-Tenant-Id 头无效
  // ------------------------------------------------------------------

  @Test
  void 伪造租户头无效() throws Exception {
    String parent = freshLogin();
    String tag = suffix();
    save(parent, "America/New_York");

    String child = childTenant(parent, tag);
    String childTenantId = tenantOf(child).toString();

    // 伪造一个随机租户 id 与**子租户真实**租户 id：结果都必须不变。
    for (String forged : List.of(UUID.randomUUID().toString(), childTenantId)) {
      String body =
          http.perform(
                  get("/identity/user-locale")
                      .header("Authorization", "Bearer " + parent)
                      .header("X-Tenant-Id", forged))
              .andExpect(status().isOk())
              .andReturn()
              .getResponse()
              .getContentAsString();
      assertThat(body)
          .as("伪造 X-Tenant-Id=" + forged + " 不得改变可见范围")
          .contains("America/New_York");
    }

    // 伪造头的**写**同样无效：这次写仍然落回**父租户**自己的库
    http.perform(
            put("/identity/user-locale")
                .header("Authorization", "Bearer " + parent)
                .header("X-Tenant-Id", childTenantId)
                .contentType("application/json")
                .content(json.writeValueAsString(Map.of("locale", "zh-CN", "timezone", "Asia/Tokyo"))))
        .andExpect(status().isOk());
    assertThat(rowsOf(parent))
        .as("伪造租户头的写仍然落在父租户自己的库")
        .hasSize(1);
    assertThat(rowsOf(child))
        .as("伪造租户头不得把行写进子租户的库")
        .isEmpty();
  }

  // ------------------------------------------------------------------
  // 断言三：连接池归还后不串租户
  // ------------------------------------------------------------------

  @Test
  void 连接池归还后多轮交替读写不串租户() throws Exception {
    String parent = freshLogin();
    String tag = suffix();
    save(parent, "America/New_York");

    String child = childTenant(parent, tag);
    save(child, "Asia/Tokyo");

    // 池大小是 1（见基类），下面每一轮都必然复用同一条物理连接。
    String expectedChild = "Asia/Tokyo";
    for (int i = 0; i < 5; i++) {
      assertThat(read(parent).get("timezone").asText())
          .as("第 %d 轮：父租户读必须只见父租户", i)
          .isEqualTo("America/New_York");
      assertThat(read(child).get("timezone").asText())
          .as("第 %d 轮：子租户读必须只见子租户", i)
          .isEqualTo(expectedChild);

      // 交替**写**：子租户每轮换一个值，证明读到的是最新那份而不是第一份
      expectedChild = expectedChild.equals("Asia/Tokyo") ? "Europe/Berlin" : "Asia/Tokyo";
      save(child, expectedChild);
      assertThat(rowsOf(child))
          .as("第 %d 轮：子租户的写必须落在子租户自己的库", i)
          .hasSize(1);
      assertThat(rowsOf(parent))
          .as("第 %d 轮：子租户的写不得改变父租户可见的那一行", i)
          .hasSize(1);

      // 回到父租户：连接刚被子租户用过，父租户必须仍然只见父租户
      assertThat(read(parent).get("timezone").asText())
          .as("第 %d 轮：子租户用过连接之后父租户仍不得串", i)
          .isEqualTo("America/New_York");
    }
  }

  @Test
  void 偏好表在两租户之间都查不到对方的行且均为强制RLS() throws Exception {
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
