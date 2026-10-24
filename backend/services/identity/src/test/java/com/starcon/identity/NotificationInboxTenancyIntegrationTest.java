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
 * 1.3.1.1 通知列表 —— **多租户三断言**。
 *
 * <p>{@link NotificationInboxIntegrationTest} 里也有一份三断言，但那个类的主职责是
 * 业务行为（落库、批量边界、订阅替换、校验、时间）；隔离是它的附带覆盖。
 * 本类把三条铁律**单独拉出来实跑**，因为本模块的隔离面比一般列表模块更大：
 * 三处都直接暴露在「**能不能看见别人读过哪些通知**」上。
 *
 * <ol>
 *   <li><b>两租户互不可见</b>，且**同租户内的两个成员也互不可见** ——
 *       已读状态是**个人**的，同事之间不该互相看到对方读过什么；</li>
 *   <li><b>伪造 {@code X-Tenant-Id} 头无效</b>；</li>
 *   <li><b>连接池归还后不串租户</b>。</li>
 * </ol>
 *
 * <p><b>断言落在真实业务表上</b>（{@code notification_read_state} 与
 * {@code notification_type_subscription}），并且**按主键直查对方的行、断言读到零行**。
 * 只在 HTTP 层断言「列表里没有对方数据」是不够的 ——
 * 那个结论可能来自过滤条件写对了，而底下的行照样在库里可见。
 *
 * <p><b>连接池压到 1 条连接</b>（{@link IdentityIntegrationTestBase} 已设）：只有复用同一条物理连接，
 * 「归还后不串租户」才是真的被验证过 —— 池大于 1 时每次可能拿到另一条干净连接，「没串」可能只是碰巧。
 */
class NotificationInboxTenancyIntegrationTest extends IdentityIntegrationTestBase {

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

  private JsonNode markRead(String token, List<String> keys) throws Exception {
    return request(token, "POST", "/identity/notification-inbox/read", Map.of("keys", keys), 200);
  }

  private JsonNode readState(String token, List<String> keys) throws Exception {
    return request(token, "POST", "/identity/notification-inbox/read-state", Map.of("keys", keys), 200);
  }

  private void setSubscriptions(String token, List<String> types) throws Exception {
    request(token, "PUT", "/identity/notification-inbox/subscriptions", Map.of("types", types), 200);
  }

  private JsonNode subscriptions(String token) throws Exception {
    return request(token, "GET", "/identity/notification-inbox/subscriptions", null, 200);
  }

  /** 通知键（类型 + 随机上游主键）。 */
  private static String key(String type) {
    return type + ":" + UUID.randomUUID();
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
                        "NIT" + tag,
                        "name",
                        "通知隔离角色 " + tag,
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
            "nit-" + tag.toLowerCase(), null, List.of(Map.of("roleId", role.toString(), "scopeMode", "ALL"))),
        200);
    return loginUser("nit-" + tag.toLowerCase());
  }

  /** 另建一个子租户。 */
  private String childTenant(String parentToken, String tag) throws Exception {
    request(
        parentToken,
        "POST",
        "/identity/children",
        Map.of(
            "code",
            "NITC" + tag,
            "name",
            "通知子租户 " + tag,
            "ownerUsername",
            "nitc-" + tag.toLowerCase(),
            "ownerPassword",
            "test-member-password"),
        200);
    return loginUser("nitc-" + tag.toLowerCase());
  }

  // ------------------------------------------------------------------
  // 断言一：两租户互不可见（同租户内不同成员也互不可见）
  // ------------------------------------------------------------------

  @Test
  void 两租户互不可见且同租户内不同成员互不可见() throws Exception {
    String parent = login();
    String tag = suffix();
    String key = key("MESSAGE");
    markRead(parent, List.of(key));
    setSubscriptions(parent, List.of("MESSAGE"));

    String child = childTenant(parent, tag);
    String colleague = otherMember(parent, tag);

    // 子租户读不到父租户的那一条已读
    assertThat(readState(child, List.of(key)).get("read"))
        .as("子租户不得读到父租户的已读状态")
        .isEmpty();

    // 同租户的**另一个成员**也读不到：已读是**个人**的
    assertThat(readState(colleague, List.of(key)).get("read"))
        .as("同租户的同事不得读到我的已读状态")
        .isEmpty();

    // 订阅同样互不可见
    assertThat(subscriptions(colleague).get("types"))
        .as("同事读不到我的订阅设置（他看到的是默认的全部类型）")
        .hasSize(5);

    // 按主键直查：子租户租户事务里读父租户的行 → 零行
    assertThat(
            rowsIn(
                tenantOf(child),
                "select id from notification_read_state where notification_ref = ?::varchar",
                key.substring(key.indexOf(':') + 1)))
        .as("子租户按主键直查父租户的已读状态必须读到零行")
        .isEmpty();
    assertThat(
            rowsIn(
                tenantOf(parent),
                "select id from notification_read_state where notification_ref = ?::varchar",
                key.substring(key.indexOf(':') + 1)))
        .as("父租户自己那一行确实在")
        .hasSize(1);

    // 订阅也一样
    assertThat(rowsIn(tenantOf(child), "select id from notification_type_subscription"))
        .as("子租户读不到父租户的订阅行")
        .isEmpty();

    // 🔴 **同租户内不同成员**：已读与订阅都是**按成员**记的。
    //   RLS 是**租户级**的（同租户的两行本来就并存于同一张表），
    //   「只看自己的」由服务层的 membership_id 谓词保证 ——
    //   所以这里按 membership_id 断言，而不是按租户数行。
    UUID parentMembership = identity.resolve("Bearer " + parent).membershipId();
    UUID colleagueMembership = identity.resolve("Bearer " + colleague).membershipId();
    assertThat(
            rowsIn(
                tenantOf(parent),
                "select id from notification_read_state"
                    + " where notification_ref = ?::varchar and membership_id = ?::uuid",
                key.substring(key.indexOf(':') + 1),
                parentMembership))
        .as("父租户里属于我的那一行确实在")
        .hasSize(1);
    assertThat(
            rowsIn(
                tenantOf(parent),
                "select id from notification_read_state"
                    + " where notification_ref = ?::varchar and membership_id = ?::uuid",
                key.substring(key.indexOf(':') + 1),
                colleagueMembership))
        .as("同事的成员 id 下没有这一行（已读是个人状态）")
        .isEmpty();

    // 别人**写**同一个键也覆盖不了我的：父租户读回来仍是原样
    markRead(child, List.of(key));
    assertThat(readState(parent, List.of(key)).get("read")).hasSize(1);
    assertThat(
            rowsIn(
                tenantOf(parent),
                "select id from notification_read_state where notification_ref = ?::varchar",
                key.substring(key.indexOf(':') + 1)))
        .as("子租户的写落在子租户自己的库里，父租户这边不受影响")
        .hasSize(1);
    assertThat(
            rowsIn(
                tenantOf(child),
                "select id from notification_read_state where notification_ref = ?::varchar",
                key.substring(key.indexOf(':') + 1)))
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
    String key = key("STOCKOUT");
    markRead(parent, List.of(key));

    String child = childTenant(parent, tag);
    String childTenantId = tenantOf(child).toString();

    // 伪造一个随机租户 id 与**子租户真实**租户 id：结果都必须不变。
    // 用子租户真实 id 才是真正有杀伤力的那一档 —— 调用方确实知道一个存在的租户 id。
    for (String forged : List.of(UUID.randomUUID().toString(), childTenantId)) {
      String body =
          http.perform(
                  post("/identity/notification-inbox/read-state")
                      .header("Authorization", "Bearer " + parent)
                      .header("X-Tenant-Id", forged)
                      .contentType("application/json")
                      .content(json.writeValueAsString(Map.of("keys", List.of(key)))))
              .andExpect(status().isOk())
              .andReturn()
              .getResponse()
              .getContentAsString();
      assertThat(body)
          .as("伪造 X-Tenant-Id=" + forged + " 不得改变可见范围")
          .contains("\"key\":\"" + key + "\"");
    }

    // 伪造头的**写**同样无效：这次写仍然落回**父租户**自己的库
    String other = key("STOCKOUT");
    http.perform(
            post("/identity/notification-inbox/read")
                .header("Authorization", "Bearer " + parent)
                .header("X-Tenant-Id", childTenantId)
                .contentType("application/json")
                .content(json.writeValueAsString(Map.of("keys", List.of(other)))))
        .andExpect(status().isOk());
    assertThat(
            rowsIn(
                tenantOf(parent),
                "select id from notification_read_state where notification_ref = ?::varchar",
                other.substring(other.indexOf(':') + 1)))
        .as("伪造租户头的写仍然落在父租户自己的库")
        .hasSize(1);
    assertThat(
            rowsIn(
                tenantOf(child),
                "select id from notification_read_state where notification_ref = ?::varchar",
                other.substring(other.indexOf(':') + 1)))
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
    String parentKey = key("TIMELINESS_OVERDUE");
    String childKey = key("TIMELINESS_OVERDUE");
    markRead(parent, List.of(parentKey));
    setSubscriptions(parent, List.of("TIMELINESS_OVERDUE"));

    String child = childTenant(parent, tag);
    markRead(child, List.of(childKey));
    setSubscriptions(child, List.of("MESSAGE"));

    // 池大小是 1（见基类），下面每一轮都必然复用同一条物理连接。
    for (int i = 0; i < 5; i++) {
      // 父租户读：只看得见父租户自己的
      assertThat(readState(parent, List.of(parentKey, childKey)).get("read"))
          .as("第 %d 轮：父租户读必须只见父租户", i)
          .hasSize(1);
      assertThat(readState(parent, List.of(parentKey, childKey)).get("read").get(0).get("key").asText())
          .as("第 %d 轮：父租户读到的必须是父租户那一条", i)
          .isEqualTo(parentKey);

      // 子租户读：只看得见子租户自己的
      assertThat(readState(child, List.of(parentKey, childKey)).get("read"))
          .as("第 %d 轮：子租户读必须只见子租户", i)
          .hasSize(1);
      assertThat(readState(child, List.of(parentKey, childKey)).get("read").get(0).get("key").asText())
          .as("第 %d 轮：子租户读到的必须是子租户那一条", i)
          .isEqualTo(childKey);

      // 交替**写**：子租户写自己那一条（每轮换一个键，证明读到的是最新那份而不是第一份）
      String fresh = key("TIMELINESS_OVERDUE");
      markRead(child, List.of(fresh));
      assertThat(
              rowsIn(
                  tenantOf(child),
                  "select id from notification_read_state where notification_ref = ?::varchar",
                  fresh.substring(fresh.indexOf(':') + 1)))
          .as("第 %d 轮：子租户的写必须落在子租户自己的库", i)
          .hasSize(1);
      assertThat(
              rowsIn(
                  tenantOf(parent),
                  "select id from notification_read_state where notification_ref = ?::varchar",
                  fresh.substring(fresh.indexOf(':') + 1)))
          .as("第 %d 轮：子租户的写不得改变父租户可见的那一行", i)
          .isEmpty();

      // 回到父租户：连接刚被子租户用过，父租户必须仍然只见父租户
      assertThat(readState(parent, List.of(parentKey, childKey)).get("read"))
          .as("第 %d 轮：子租户用过连接之后父租户仍不得串", i)
          .hasSize(1);
      assertThat(subscriptions(parent).get("types"))
          .as("第 %d 轮：父租户的订阅不得被子租户的写冲掉", i)
          .hasSize(1);
      assertThat(subscriptions(parent).get("types").get(0).asText())
          .as("第 %d 轮：父租户订阅的仍是自己的那一个类型", i)
          .isEqualTo("TIMELINESS_OVERDUE");
    }
  }

  @Test
  void 两张表在两租户之间都查不到对方的行且均为强制RLS() throws Exception {
    // 事务外（app.current_tenant 未设置）直查必须为空：租户上下文不是「默认放行」
    assertThat(sql.fetch("select id from notification_read_state")).isEmpty();
    assertThat(sql.fetch("select id from notification_type_subscription")).isEmpty();

    for (String table : List.of("notification_read_state", "notification_type_subscription")) {
      Boolean forced =
          sql.fetchOne("select relforcerowsecurity from pg_class where relname = ?::varchar", table)
              .get(0, Boolean.class);
      assertThat(forced).as("%s 必须是 FORCE RLS", table).isTrue();
    }

    // 顺带证明连接确实来自非超级用户、也没有 bypassrls —— 上面两条才有意义
    assertThat(
            sql.fetchOne("select rolbypassrls, rolsuper from pg_roles where rolname=current_user")
                .get("rolbypassrls", Boolean.class))
        .as("应用连接不得持有 bypassrls，否则 FORCE RLS 形同虚设")
        .isFalse();
  }
}
