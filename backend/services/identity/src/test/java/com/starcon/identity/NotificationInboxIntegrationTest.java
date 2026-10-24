package com.starcon.identity;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.starcon.tenancy.TenantTransactions;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.Record;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 1.3.1.1 通知列表 —— 集成测试。
 *
 * <p><b>真实 PostgreSQL 16 + 强制 RLS，不使用任何内存替身。</b>
 *
 * <p><b>本测试真正在守的东西</b>：
 *
 * <ol>
 *   <li><b>数据真的落库</b>：已读状态写进 {@code notification_read_state}、
 *       订阅写进 {@code notification_type_subscription}，都用<b>直查表</b>断言（不看接口回显就算数）；</li>
 *   <li><b>已读是个人状态</b>：同租户的另一个成员读不到我的已读记录，
 *       我读过的也不会自动变成「同事也读过」；</li>
 *   <li><b>批量只对点名的那几条生效</b>：没有「全部标已读」这种后门 ——
 *       没被点名的键必须仍然是未读；</li>
 *   <li><b>订阅是整体替换</b>：取消勾选要真的能取消掉（增量语义会让它永远删不掉）；</li>
 *   <li><b>校验不是摆设</b>：词表外的类型、形状不对的键、超量的批次各自被拒（400）；</li>
 *   <li><b>时间没被时区吃掉</b>：写回库的 {@code read_at} 与接口回的读数是**同一个时刻**，
 *       且落在「刚才」这个窗口里（{@code Timestamp.valueOf(LocalDateTime)} 会静默偏 −28800s）。</li>
 * </ol>
 *
 * <p><b>每个用例用自己新建的成员</b>：已读状态是「一个人一行」的，
 * 用例之间共用 {@code local-admin} 会让状态互相污染。新建成员 = 新的一份状态 = 断言是确定的。
 */
class NotificationInboxIntegrationTest extends IdentityIntegrationTestBase {

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

  // ================================================================ 测试夹具

  /**
   * 新建一个**干净**的成员并登录，返回它的 token。
   *
   * <p>本模块的端点<b>没有</b>功能权限码（与 {@code user_preference} / {@code page_favorite}
   * 同一套论证：边界是数据归属，不是功能开关），所以这里给的权限只是为了让角色存在。
   */
  private String freshMember(String tag) throws Exception {
    String owner = login();
    UUID role =
        UUID.fromString(
            request(
                    owner,
                    "POST",
                    "/identity/roles",
                    Map.of(
                        "code", "NI" + tag,
                        "name", "通知测试角色 " + tag,
                        "status",
                        "ACTIVE",
                        "permissions",
                        List.of()),
                    200)
                .asText());
    request(
        owner,
        "POST",
        "/identity/members",
        memberInput(
            "ni-" + tag.toLowerCase(), null, List.of(Map.of("roleId", role.toString(), "scopeMode", "ALL"))),
        200);
    return loginUser("ni-" + tag.toLowerCase());
  }

  /** 标已读。 */
  private JsonNode markRead(String token, List<String> keys) throws Exception {
    return request(token, "POST", "/identity/notification-inbox/read", Map.of("keys", keys), 200);
  }

  /** 取消已读。 */
  private JsonNode markUnread(String token, List<String> keys) throws Exception {
    return request(token, "POST", "/identity/notification-inbox/unread", Map.of("keys", keys), 200);
  }

  /** 查已读状态。 */
  private JsonNode readState(String token, List<String> keys) throws Exception {
    return request(token, "POST", "/identity/notification-inbox/read-state", Map.of("keys", keys), 200);
  }

  /** 设置订阅类型。 */
  private JsonNode setSubscriptions(String token, List<String> types) throws Exception {
    return request(
        token, "PUT", "/identity/notification-inbox/subscriptions", Map.of("types", types), 200);
  }

  /** 读我的订阅类型。 */
  private JsonNode subscriptions(String token) throws Exception {
    return request(token, "GET", "/identity/notification-inbox/subscriptions", null, 200);
  }

  /** 造一个通知键（类型 + 随机上游主键）。 */
  private static String key(String type) {
    return type + ":" + UUID.randomUUID();
  }

  // ================================================================ 已读状态

  @Test
  void 标记已读真的落库且读回来的时刻与写进去的一致() throws Exception {
    String token = freshMember(suffix());
    UUID tenant = tenantOf(token);
    String key = key("MESSAGE");

    OffsetDateTime before = OffsetDateTime.now(ZoneOffset.UTC);
    JsonNode result = markRead(token, List.of(key));
    OffsetDateTime after = OffsetDateTime.now(ZoneOffset.UTC);

    assertThat(result.get("affected").asInt()).as("一条键被改动").isEqualTo(1);
    assertThat(result.get("read")).hasSize(1);
    assertThat(result.get("read").get(0).get("key").asText()).isEqualTo(key);

    // 直查表：行真的在库里，而且就一行。
    List<Record> rows =
        rowsIn(
            tenant,
            "select notification_type, notification_ref, read_at from notification_read_state"
                + " where notification_ref = ?::varchar",
            key.substring(key.indexOf(':') + 1));
    assertThat(rows).as("已读状态必须真的落库").hasSize(1);
    assertThat(rows.get(0).get("notification_type", String.class)).isEqualTo("MESSAGE");

    // 🔴 时间字段：写回库的 read_at 与接口回的读数必须是**同一个时刻**，
    //    且落在「刚才」这个窗口里。Timestamp.valueOf(LocalDateTime) 会静默偏 −28800s，
    //    那种偏斜在这里必然被抓出来（偏 8 小时既不等于 before 也不等于 after）。
    OffsetDateTime readAt = OffsetDateTime.parse(result.get("read").get(0).get("readAt").asText());
    assertThat(readAt.toInstant())
        .as("读到的时刻必须落在请求前后的窗口内（不能被时区偏掉 8 小时）")
        .isBetween(before.toInstant().minusSeconds(1), after.toInstant().plusSeconds(1));
    assertThat(rows.get(0).get("read_at", OffsetDateTime.class).toInstant())
        .as("库里的 read_at 与接口读出的必须是同一个时刻")
        .isEqualTo(readAt.toInstant());
  }

  @Test
  void 重复标记已读是覆盖不是新增行() throws Exception {
    String token = freshMember(suffix());
    UUID tenant = tenantOf(token);
    String key = key("STOCKOUT");

    markRead(token, List.of(key));
    JsonNode second = markRead(token, List.of(key));

    assertThat(second.get("affected").asInt()).as("重复标记仍只对应一行").isEqualTo(1);
    assertThat(
            rowsIn(
                tenant,
                "select id from notification_read_state where notification_ref = ?::varchar",
                key.substring(key.indexOf(':') + 1)))
        .as("同一条通知对同一个人只留一行")
        .hasSize(1);
  }

  @Test
  void 批量已读只对点名的那几条生效() throws Exception {
    String token = freshMember(suffix());
    String named = key("SYNC_FAILURE");
    String other = key("SYNC_FAILURE");

    markRead(token, List.of(named));

    JsonNode state = readState(token, List.of(named, other));
    assertThat(state.get("read")).hasSize(1);
    assertThat(state.get("read").get(0).get("key").asText()).isEqualTo(named);
    assertThat(state.get("read").get(0).get("key").asText())
        .as("没被点名的键不得被顺手标已读")
        .isNotEqualTo(other);
  }

  @Test
  void 取消已读是幂等的且只影响点名的那几条() throws Exception {
    String token = freshMember(suffix());
    UUID tenant = tenantOf(token);
    String first = key("RECONCILIATION_DIFF");
    String second = key("RECONCILIATION_DIFF");

    markRead(token, List.of(first, second));
    JsonNode removed = markUnread(token, List.of(first));
    assertThat(removed.get("affected").asInt()).as("取消一条已读").isEqualTo(1);

    JsonNode again = markUnread(token, List.of(first));
    assertThat(again.get("affected").asInt()).as("再取消一次是幂等的（0 行也算成功）").isEqualTo(0);

    assertThat(readState(token, List.of(first, second)).get("read"))
        .as("只剩另一条仍是已读")
        .hasSize(1);
    assertThat(
            rowsIn(
                tenant,
                "select id from notification_read_state where notification_ref = ?::varchar",
                first.substring(first.indexOf(':') + 1)))
        .as("取消已读后那一行真的没了")
        .isEmpty();
  }

  @Test
  void 已读状态是个人状态同租户的同事读不到() throws Exception {
    String tag = suffix();
    String owner = login();
    UUID role =
        UUID.fromString(
            request(
                    owner,
                    "POST",
                    "/identity/roles",
                    Map.of(
                        "code", "NIP" + tag,
                        "name", "通知同事角色 " + tag,
                        "status",
                        "ACTIVE",
                        "permissions",
                        List.of()),
                    200)
                .asText());
    request(
        owner,
        "POST",
        "/identity/members",
        memberInput(
            "nip-" + tag.toLowerCase(), null, List.of(Map.of("roleId", role.toString(), "scopeMode", "ALL"))),
        200);
    String colleague = loginUser("nip-" + tag.toLowerCase());

    String key = key("TIMELINESS_OVERDUE");
    String ref = key.substring(key.indexOf(':') + 1);
    markRead(owner, List.of(key));

    UUID ownerMembership = identity.resolve("Bearer " + owner).membershipId();
    UUID colleagueMembership = identity.resolve("Bearer " + colleague).membershipId();

    // 服务层：同事读不到我读过哪些通知（**按成员**过滤，不是按租户）。
    assertThat(readState(colleague, List.of(key)).get("read"))
        .as("同事读不到我读过哪些通知")
        .isEmpty();

    // 数据层：这一行是**按成员**记的 —— 同事的成员 id 下没有这一行。
    // 🔴 注意：同租户内两个成员的已读行**本来就并存于同一张表**
    //   （RLS 是**租户级**的，「只看自己的」由服务层的 membership_id 谓词保证），
    //   所以这里按 membership_id 断言，而不是按租户数行。
    assertThat(
            rowsIn(
                tenantOf(owner),
                "select id from notification_read_state"
                    + " where notification_ref = ?::varchar and membership_id = ?::uuid",
                ref,
                ownerMembership))
        .as("我的成员 id 下确实有这一行")
        .hasSize(1);
    assertThat(
            rowsIn(
                tenantOf(colleague),
                "select id from notification_read_state"
                    + " where notification_ref = ?::varchar and membership_id = ?::uuid",
                ref,
                colleagueMembership))
        .as("同事的成员 id 下没有这一行（已读是个人状态）")
        .isEmpty();
  }

  // ================================================================ 按类型订阅

  @Test
  void 没自定义过时默认订阅全部类型() throws Exception {
    String token = freshMember(suffix());
    JsonNode types = subscriptions(token);

    assertThat(types.get("types")).hasSize(5);
    assertThat(types.get("types").get(0).asText()).isEqualTo("STOCKOUT");
    assertThat(
            types.get("types").get(0).asText())
        .as("默认集合按词表顺序返回，不按字母序")
        .isEqualTo("STOCKOUT");
  }

  @Test
  void 设置订阅是整体替换且真的落库() throws Exception {
    String token = freshMember(suffix());
    UUID tenant = tenantOf(token);

    setSubscriptions(token, List.of("MESSAGE", "STOCKOUT"));
    JsonNode types = subscriptions(token);
    assertThat(types.get("types")).hasSize(2);
    assertThat(types.get("types").get(0).asText()).as("按词表顺序").isEqualTo("STOCKOUT");
    assertThat(types.get("types").get(1).asText()).isEqualTo("MESSAGE");

    assertThat(
            rowsIn(tenant, "select notification_type from notification_type_subscription"))
        .as("订阅真的落库")
        .hasSize(2);

    // 再设一次：整体替换，不是叠加。
    setSubscriptions(token, List.of("SYNC_FAILURE"));
    assertThat(subscriptions(token).get("types")).hasSize(1);
    assertThat(rowsIn(tenant, "select notification_type from notification_type_subscription"))
        .as("整体替换：旧的两行应当没了")
        .hasSize(1);
  }

  @Test
  void 订阅词表外的类型被拒() throws Exception {
    String token = freshMember(suffix());
    request(
        token,
        "PUT",
        "/identity/notification-inbox/subscriptions",
        Map.of("types", List.of("NOT_A_TYPE")),
        400);
    request(
        token,
        "PUT",
        "/identity/notification-inbox/subscriptions",
        Map.of("types", List.of("MESSAGE", "everything")),
        400);
  }

  @Test
  void 一个类型都不订阅被拒() throws Exception {
    String token = freshMember(suffix());
    request(
        token,
        "PUT",
        "/identity/notification-inbox/subscriptions",
        Map.of("types", List.of()),
        400);
  }

  // ================================================================ 校验

  @Test
  void 形状不对的通知键被拒() throws Exception {
    String token = freshMember(suffix());
    // 缺冒号
    request(token, "POST", "/identity/notification-inbox/read", Map.of("keys", List.of("MESSAGE")), 400);
    // 类型不在词表里
    request(
        token,
        "POST",
        "/identity/notification-inbox/read",
        Map.of("keys", List.of("NOT_A_TYPE:" + UUID.randomUUID())),
        400);
    // 主键段带路径分隔符
    request(
        token,
        "POST",
        "/identity/notification-inbox/read",
        Map.of("keys", List.of("MESSAGE:abc/def")),
        400);
    // 主键段为空
    request(token, "POST", "/identity/notification-inbox/read", Map.of("keys", List.of("MESSAGE:")), 400);
  }

  @Test
  void 超量的批次被拒() throws Exception {
    String token = freshMember(suffix());
    List<String> keys = new java.util.ArrayList<>();
    for (int i = 0; i <= 500; i++) keys.add(key("MESSAGE"));
    request(token, "POST", "/identity/notification-inbox/read", Map.of("keys", keys), 400);
  }

  @Test
  void 能力声明给出受控词表与未交付的相邻模块() throws Exception {
    String token = freshMember(suffix());
    JsonNode capabilities = request(token, "GET", "/identity/notification-inbox/capabilities", null, 200);

    assertThat(capabilities.get("types")).hasSize(5);
    assertThat(capabilities.get("maxKeysPerRequest").asInt()).isEqualTo(500);
    assertThat(capabilities.get("boundaries").toString())
        .contains("1.3.1.2")
        .contains("1.3.2.1")
        .contains("1.3.2.2");
  }
}
