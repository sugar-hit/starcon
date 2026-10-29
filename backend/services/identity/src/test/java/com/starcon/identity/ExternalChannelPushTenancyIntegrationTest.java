package com.starcon.identity;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.Record;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 1.3.2.2 外部渠道推送 —— **多租户三断言**。
 *
 * <p>{@link ExternalChannelPushIntegrationTest} 里也有一份三断言，但那个类的主职责是
 * 业务行为（凭据不落库、结果如实、偏好过滤、重试与告警）。本类把三条铁律
 * <b>单独拉出来实跑</b>：外部渠道与投递记录是<b>租户级共享</b>的运营配置 ——
 * 隔离一旦破了，A 租户登记的企业微信机器人就会收到 B 租户的告警推送，
 * 而租户之间**互不可见**是最基本的预期。
 *
 * <ol>
 *   <li><b>两租户互不可见</b>；</li>
 *   <li><b>伪造 {@code X-Tenant-Id} 头无效</b>；</li>
 *   <li><b>连接池归还后不串租户</b>。</li>
 * </ol>
 *
 * <p><b>断言落在真实业务表上</b>（{@code external_push_channel} 与
 * {@code external_push_delivery}），并且**按主键直查对方的行、断言读到零行**。
 * 只在 HTTP 层断言「列表里没有对方数据」是不够的 —— 那个结论可能来自过滤条件写对了，
 * 而底下的行照样在库里可见。
 *
 * <p><b>连接池压到 1 条连接</b>：只有复用同一条物理连接，
 * 「归还后不串租户」才是真的被验证过 —— 池大于 1 时每次可能拿到另一条干净连接，
 * 「没串」可能只是碰巧。
 *
 * <p>🔴 <b>容器是本类自己的</b>，并在 {@code @AfterAll} 里 {@code .stop()} ——
 * 不进 {@code IdentityIntegrationTestBase} 的共享 static 容器那个历史包袱。
 */
class ExternalChannelPushTenancyIntegrationTest extends ExternalChannelPushTestSupport {

  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

  static {
    pg.start();
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute("create role starcon_app nologin");
      s.execute("create role identity_app login password 'test-only' nosuperuser nobypassrls");
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "identity_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("INTERNAL_KEY", () -> "test-key");
    r.add("LOCAL_BOOTSTRAP_ENABLED", () -> "true");
    r.add("LOCAL_ADMIN_PASSWORD", () -> "test-admin-password");
    // 池压到 1：每次请求必然复用同一条物理连接。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  @AfterAll
  static void stop() {
    pg.stop();
  }

  // ==========================================================================
  // 夹具
  // ==========================================================================

  private static final String CHANNELS = "/identity/external-channel-push/channels";

  private List<Record> rowsIn(UUID tenant, String statement, Object... bindings) {
    return tx().run(tenant, () -> List.copyOf(sql.fetch(statement, bindings)));
  }

  /** 渠道清单里的显示名（按名字核对「看不见对方那条」）。 */
  private static List<String> displayNames(JsonNode channels) {
    List<String> names = new java.util.ArrayList<>();
    channels.forEach(node -> names.add(node.get("displayName").asText()));
    return names;
  }

  /** 在某个租户里登记一个渠道并推一次，返回渠道 id。 */
  private String channelAndPush(String token, String tag, String channelCode) throws Exception {
    UUID membershipId = membershipOf(token);
    String channelId =
        registerChannel(token, channelCode, "渠道 " + tag, "secret://projects/starcon/x/" + tag)
            .get("id")
            .asText();
    request(
        token,
        "POST",
        "/identity/external-channel-push/deliveries",
        Map.of(
            "sourceKind", "NOTIFICATION",
            "notificationType", "STOCKOUT",
            "notificationRef", "SKU-" + tag,
            "title", "缺货提醒 " + tag,
            "channelIds", List.of(channelId),
            "recipientMembershipId", membershipId.toString()),
        200);
    return channelId;
  }

  // ==========================================================================
  // 断言一：两租户互不可见
  // ==========================================================================

  @Test
  void 两租户互不可见且按主键直查也读不到对方的行() throws Exception {
    String tag = suffix();
    String parent = isolatedTenant(tag);

    String parentChannelId = channelAndPush(parent, tag, "WECHAT_WORK");

    String child = childTenant(parent, tag);

    // 子租户的清单是空的。
    assertThat(request(child, "GET", "/identity/external-channel-push/channels", null, 200)
            .get("channels"))
        .as("父租户登记的渠道不得出现在子租户清单里")
        .isEmpty();
    assertThat(
            request(child, "GET", "/identity/external-channel-push/deliveries", null, 200)
                .get("deliveries"))
        .as("父租户的投递记录不得出现在子租户清单里")
        .isEmpty();

    // 按主键直查：子租户租户事务里读父租户的行 ⇒ 零行。
    UUID parentTenant = tenantOf(parent);
    UUID childTenantId = tenantOf(child);
    assertThat(
            rowsIn(
                childTenantId,
                "select id from external_push_channel where id = ?::uuid",
                UUID.fromString(parentChannelId)))
        .as("子租户按主键直查父租户的渠道必须读到零行")
        .isEmpty();
    assertThat(
            rowsIn(
                childTenantId,
                "select id from external_push_delivery where title like ?::varchar",
                "%" + tag + "%"))
        .as("子租户按内容直查父租户的投递记录必须读到零行")
        .isEmpty();

    // 父租户自己那一行确实在。
    assertThat(rowsIn(parentTenant, "select id from external_push_channel"))
        .as("父租户自己那一行确实在")
        .hasSize(1);
    assertThat(rowsIn(parentTenant, "select id from external_push_delivery")).hasSize(1);

    // 子租户自己登记的渠道落在子租户的库里。
    String childChannelId = channelAndPush(child, tag + "C", "EMAIL");
    assertThat(rowsIn(childTenantId, "select id from external_push_channel")).hasSize(1);
    assertThat(
            rowsIn(
                parentTenant,
                "select id from external_push_channel where id = ?::uuid",
                UUID.fromString(childChannelId)))
        .as("子租户写的行不得出现在父租户的库里")
        .isEmpty();
  }

  // ==========================================================================
  // 断言二：伪造 X-Tenant-Id 头无效
  // ==========================================================================

  @Test
  void 伪造租户头无效() throws Exception {
    String tag = suffix();
    String parent = isolatedTenant(tag);
    String child = childTenant(parent, tag);
    UUID childTenantId = tenantOf(child);

    // 伪造一个随机租户 id 与**子租户真实**租户 id：结果都必须不变。
    // 用子租户真实 id 才是真正有杀伤力的那一档 —— 调用方确实知道一个存在的租户 id。
    // 两次用**不同的渠道码**：同一租户同渠道码只能登记一条，重复登记会撞唯一约束（409）。
    List<String> forgedCodes = List.of("FEISHU", "DINGTALK");
    List<String> forgeds = List.of(UUID.randomUUID().toString(), childTenantId.toString());
    for (int i = 0; i < forgeds.size(); i++) {
      String forged = forgeds.get(i);
      var response =
          http.perform(
                  org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                          "/identity/external-channel-push/channels")
                      .header("Authorization", "Bearer " + parent)
                      .header("X-Tenant-Id", forged)
                      .contentType("application/json")
                      .content(
                          json.writeValueAsString(
                              Map.of(
                                  "channelCode", forgedCodes.get(i),
                                  "displayName", "伪造头渠道 " + tag + i,
                                  "secretRef", "secret://projects/starcon/x/forged" + i))))
              .andReturn()
              .getResponse();
      assertThat(response.getStatus())
          .as("伪造 X-Tenant-Id=" + forged + " 不得改变写入所属的租户")
          .isEqualTo(200);
    }

    // 两次写仍然落回**父租户**自己的库。
    assertThat(rowsIn(tenantOf(parent), "select id from external_push_channel")).hasSize(2);
    assertThat(rowsIn(childTenantId, "select id from external_push_channel"))
        .as("伪造租户头不得把行写进子租户的库")
        .isEmpty();
  }

  // ==========================================================================
  // 断言三：连接池归还后不串租户
  // ==========================================================================

  @Test
  void 连接池归还后多轮交替读写不串租户() throws Exception {
    String tag = suffix();
    String parent = isolatedTenant(tag);
    String child = childTenant(parent, tag);
    UUID parentTenant = tenantOf(parent);
    UUID childTenantId = tenantOf(child);

    String parentChannel = channelAndPush(parent, tag + "P", "WECHAT_WORK");
    String childChannel = channelAndPush(child, tag + "C", "DINGTALK");

    // 子租户另有一个「每轮改名」的渠道（同一个渠道码每租户只能登记一条）。
    String roundChannelId =
        registerChannel(
                child,
                "EMAIL",
                "轮次渠道 0 " + tag,
                "secret://projects/starcon/x/round" + tag)
            .get("id")
            .asText();

    // 池大小是 1（见 props），下面每一轮都必然复用同一条物理连接。
    for (int i = 0; i < 5; i++) {
      // 🔴 按**显示名**核对而不是按条数：子租户每轮会多登记一个渠道，
      //   条数本来就会变；「看不见对方那条」才是要钉住的事实。
      assertThat(displayNames(request(parent, "GET", CHANNELS, null, 200).get("channels")))
          .as("第 %d 轮：父租户必须只看到自己的渠道", i)
          .containsExactly("渠道 " + tag + "P");
      assertThat(displayNames(request(child, "GET", CHANNELS, null, 200).get("channels")))
          .as("第 %d 轮：子租户必须看不到父租户的渠道", i)
          .doesNotContain("渠道 " + tag + "P")
          .contains("渠道 " + tag + "C");

      // 按主键直查对方的行 ⇒ 零行。
      assertThat(
              rowsIn(
                  childTenantId,
                  "select id from external_push_channel where id = ?::uuid",
                  UUID.fromString(parentChannel)))
          .as("第 %d 轮：子租户按主键直查父租户的渠道必须读到零行", i)
          .isEmpty();
      assertThat(
              rowsIn(
                  parentTenant,
                  "select id from external_push_channel where id = ?::uuid",
                  UUID.fromString(childChannel)))
          .as("第 %d 轮：父租户按主键直查子租户的渠道必须读到零行", i)
          .isEmpty();

      // 交替写：每轮改一次名字（**同一个渠道码每租户只能有一条**，
      // 所以这里改的是显示名而不是新建一条），证明读到的是最新那份而不是第一份。
      String freshName = "轮次渠道 " + i + " " + tag;
      request(
          child,
          "PUT",
          CHANNELS + "/" + roundChannelId,
          Map.of("displayName", freshName),
          200);
      assertThat(
              rowsIn(
                  childTenantId,
                  "select id from external_push_channel where display_name = ?::varchar",
                  freshName))
          .as("第 %d 轮：子租户的写必须落在子租户自己的库", i)
          .hasSize(1);
      assertThat(
              rowsIn(
                  parentTenant,
                  "select id from external_push_channel where display_name = ?::varchar",
                  freshName))
          .as("第 %d 轮：子租户的写不得落进父租户的库", i)
          .isEmpty();

      // 回到父租户：连接刚被子租户用过，父租户必须仍然只见父租户。
      assertThat(request(parent, "GET", "/identity/external-channel-push/channels", null, 200)
              .get("channels"))
          .as("第 %d 轮：子租户用过连接之后父租户仍不得串", i)
          .hasSize(1);
      assertThat(
              rowsIn(
                  parentTenant,
                  "select id from external_push_delivery where title like ?::varchar",
                  "%" + tag + "P%"))
          .as("第 %d 轮：父租户只看到自己的投递记录", i)
          .hasSize(1);
      assertThat(
              rowsIn(
                  childTenantId,
                  "select id from external_push_delivery where title like ?::varchar",
                  "%" + tag + "P%"))
          .as("第 %d 轮：子租户读不到父租户的投递记录", i)
          .isEmpty();
    }
  }

  @Test
  void 两张表在两租户之间都查不到对方的行且均为强制RLS() throws Exception {
    // 事务外（app.current_tenant 未设置）直查必须为空：租户上下文不是「默认放行」。
    assertThat(sql.fetch("select id from external_push_channel")).isEmpty();
    assertThat(sql.fetch("select id from external_push_delivery")).isEmpty();

    for (String table : List.of("external_push_channel", "external_push_delivery")) {
      Boolean forced =
          sql.fetchOne("select relforcerowsecurity from pg_class where relname = ?::varchar", table)
              .get(0, Boolean.class);
      assertThat(forced).as("%s 必须是 FORCE RLS", table).isTrue();
    }

    // 顺带证明连接确实来自非超级用户、也没有 bypassrls —— 上面两条才有意义。
    assertThat(
            sql.fetchOne("select rolbypassrls, rolsuper from pg_roles where rolname=current_user")
                .get("rolbypassrls", Boolean.class))
        .as("应用连接不得持有 bypassrls，否则 FORCE RLS 形同虚设")
        .isFalse();
  }

  @Test
  void 触发器在缺少租户上下文时拒写() {
    // 事务外直接写：app.current_tenant 没设置 ⇒ 触发器必须拒写。
    assertThatThrownBy(
            () ->
                sql.execute(
                    "insert into external_push_channel(id,tenant_id,channel_code,display_name,"
                        + "secret_ref,created_by) values (?::uuid,?::uuid,'EMAIL','无上下文',"
                        + "'secret://x',?::uuid)",
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID()))
        .as("缺少租户上下文时必须拒写（触发器兜底）")
        .hasMessageContaining("租户上下文");
  }

  @Test
  void 数据库层拒收凭据本体() throws Exception {
    String tag = suffix();
    String token = isolatedTenant(tag);
    UUID tenantId = tenantOf(token);
    UUID membershipId = membershipOf(token);

    // 🔴 **数据层**也拒 https:// 开头（服务层之外还有一道）。
    //    这一条必须在**租户事务里**跑：BEFORE INSERT 触发器先于 CHECK 触发 ——
    //    在事务外跑会先被「缺少租户上下文」拦下，测不到 CHECK 本身。
    assertThatThrownBy(
            () ->
                tx()
                    .run(
                        tenantId,
                        () ->
                            sql.execute(
                                "insert into external_push_channel(id,tenant_id,channel_code,"
                                    + "display_name,secret_ref,created_by) values "
                                    + "(?::uuid,?::uuid,'EMAIL','地址本体',"
                                    + "'https://qyapi.weixin.qq.com/hook?key=SECRET',?::uuid)",
                                UUID.randomUUID(),
                                tenantId,
                                membershipId)))
        .as("地址本体在数据库层也写不进来")
        .hasMessageContaining("external_push_channel_secret_ref_chk");
  }
}
