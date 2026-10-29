package com.starcon.identity;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 1.3.2.2 外部渠道推送 —— **业务行为**集成测试。
 *
 * <p>本类盯的是「这个模块是不是在**说实话**」：
 *
 * <ol>
 *   <li>凭据**不落库**：{@code https://…} 这类地址本体被拒；</li>
 *   <li>投递结果**如实**：外部通道未接通 ⇒ 每一次尝试都真的失败并落成投递记录；</li>
 *   <li>偏好过滤**是真过滤**（1.3.1.2）：收件人没订阅就拒；免打扰时段里就推迟；</li>
 *   <li>重试与告警**真的会跑**：到期扫描真的又试了一次，次数耗尽真的升起告警；</li>
 *   <li>时间**没被时区吃掉**：写回库的与接口读出的是同一个时刻。</li>
 * </ol>
 *
 * <p>🔴 <b>容器是本类自己的</b>，并在 {@code @AfterAll} 里 {@code .stop()} ——
 * 不进 {@code IdentityIntegrationTestBase} 的共享 static 容器那个历史包袱
 * （2026-10-26 本轮 L2 实测：共享容器会在同一个 maven session 里堆到 12 个
 * {@code postgres:16-alpine}，把机器压垮后成片假红）。
 */
class ExternalChannelPushIntegrationTest extends ExternalChannelPushTestSupport {

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
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  @AfterAll
  static void stop() {
    pg.stop();
  }

  // ==========================================================================
  // 一、能力声明：如实报「未接通」
  // ==========================================================================

  @Test
  void 能力声明如实报出三个未接通且不造模板() throws Exception {
    String token = isolatedTenant(suffix());

    JsonNode capabilities =
        request(token, "GET", "/identity/external-channel-push/capabilities", null, 200);

    assertThat(capabilities.get("module").asText()).isEqualTo("1.3.2.2");

    // 四个受控渠道，逐条都有中文名与「未接通」说明。
    assertThat(capabilities.get("channels")).hasSize(4);
    for (JsonNode channel : capabilities.get("channels")) {
      assertThat(channel.get("connected").asBoolean())
          .as("外部通道未接通时不能报 connected")
          .isFalse();
      assertThat(channel.get("unavailableReason").asText())
          .as("未接通必须写清缺什么，不能是空白")
          .isNotBlank();
    }

    // secret manager / 投递通道 / 模板管理：三个「未接通」都要如实报，且写清归谁。
    assertThat(capabilities.get("secretManagerAvailable").asBoolean()).isFalse();
    assertThat(capabilities.get("secretManagerOwnerModule").asText()).contains("18");
    assertThat(capabilities.get("secretManagerNote").asText()).isNotBlank();
    assertThat(capabilities.get("deliveryAvailable").asBoolean()).isFalse();
    assertThat(capabilities.get("deliveryOwnerModule").asText()).contains("18");
    assertThat(capabilities.get("deliveryNote").asText()).isNotBlank();

    // 🔴 模板管理不在本模块：如实标 false + ownerModule，**不造模板表顶替**。
    assertThat(capabilities.get("templateAvailable").asBoolean())
        .as("18.5.1.1 模板管理未交付，不得报成可用")
        .isFalse();
    assertThat(capabilities.get("templateOwnerModule").asText()).contains("18.5.1.1");

    // 边界要写明「凭据不落库」与「绝不返回假成功」。
    List<String> boundaries = new java.util.ArrayList<>();
    capabilities.get("boundaries").forEach(node -> boundaries.add(node.asText()));
    assertThat(boundaries).anyMatch(text -> text.contains("凭据不落库"));
    assertThat(boundaries).anyMatch(text -> text.contains("如实为失败"));
  }

  // ==========================================================================
  // 二、渠道登记：凭据不落库
  // ==========================================================================

  @Test
  void 登记渠道只落secret引用且地址本体被拒() throws Exception {
    String tag = suffix();
    String token = isolatedTenant(tag);
    UUID tenantId = tenantOf(token);

    JsonNode channel = registerChannel(token, "WECHAT_WORK", "运营群机器人 " + tag,
        "secret://projects/starcon/tenants/x/wecom");
    assertThat(channel.get("channelCode").asText()).isEqualTo("WECHAT_WORK");
    assertThat(channel.get("secretRef").asText())
        .as("库里只落引用串")
        .isEqualTo("secret://projects/starcon/tenants/x/wecom");
    assertThat(channel.get("credentialResolvable").asBoolean())
        .as("secret manager 未接通 ⇒ 引用解析不了")
        .isFalse();
    assertThat(channel.get("credentialNote").asText()).isNotBlank();

    // 🔴 地址本体被 400 拒掉（凭据不落库）。
    for (String raw :
        List.of(
            "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=SECRET",
            "http://example.com/hook?token=abc",
            "https://example.com",
            "ftp://example.com/hook")) {
      var response =
          http.perform(
                  org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                          "/identity/external-channel-push/channels")
                      .header("Authorization", "Bearer " + token)
                      .contentType("application/json")
                      .content(
                          json.writeValueAsString(
                              Map.of(
                                  "channelCode", "DINGTALK",
                                  "displayName", "钉钉机器人",
                                  "secretRef", raw))))
              .andReturn()
              .getResponse();
      assertThat(response.getStatus())
          .as("地址本体必须被拒：" + raw)
          .isEqualTo(400);
    }

    // 库里确实没有那几行（被拒的东西一行都不许落）。
    assertThat(
            tx()
                .run(
                    tenantId,
                    () ->
                        sql.fetch(
                            "select id from external_push_channel where display_name = ?::varchar",
                            "钉钉机器人")))
        .as("被拒的渠道一行都不许落库")
        .isEmpty();
  }

  @Test
  void 重复登记同一渠道码被拒且逐字段覆盖与删除都真的生效() throws Exception {
    String tag = suffix();
    String token = isolatedTenant(tag);

    JsonNode channel =
        registerChannel(token, "FEISHU", "飞书群 " + tag, "secret://projects/starcon/tenants/x/feishu");
    String id = channel.get("id").asText();

    // 同租户同渠道码登记第二条 ⇒ 409。
    var conflict =
        http.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                        "/identity/external-channel-push/channels")
                    .header("Authorization", "Bearer " + token)
                    .contentType("application/json")
                    .content(
                        json.writeValueAsString(
                            Map.of(
                                "channelCode", "FEISHU",
                                "displayName", "另一个飞书群",
                                "secretRef", "secret://projects/starcon/tenants/x/feishu2"))))
            .andReturn()
            .getResponse();
    assertThat(conflict.getStatus()).isEqualTo(409);

    // 逐字段覆盖：只改显示名，secret 引用保持不变。
    JsonNode updated =
        request(
            token,
            "PUT",
            "/identity/external-channel-push/channels/" + id,
            Map.of("displayName", "飞书群（改名）", "linkEnabled", false),
            200);
    assertThat(updated.get("displayName").asText()).isEqualTo("飞书群（改名）");
    assertThat(updated.get("secretRef").asText())
        .as("没给的字段保持原值")
        .isEqualTo("secret://projects/starcon/tenants/x/feishu");
    assertThat(updated.get("linkEnabled").asBoolean()).isFalse();
    assertThat(updated.get("cardEnabled").asBoolean()).as("没给的字段保持原值").isTrue();

    // 清单里确实只有一条。
    assertThat(request(token, "GET", "/identity/external-channel-push/channels", null, 200)
            .get("channels"))
        .hasSize(1);

    // 删除。
    request(token, "DELETE", "/identity/external-channel-push/channels/" + id, null, 200);
    assertThat(request(token, "GET", "/identity/external-channel-push/channels", null, 200)
            .get("channels"))
        .isEmpty();
  }

  // ==========================================================================
  // 三、候选清单：只列真命中
  // ==========================================================================

  @Test
  void 候选清单只列真命中而算不出来的不进候选() throws Exception {
    String tag = suffix();
    String token = isolatedTenant(tag);
    UUID tenantId = tenantOf(token);

    UUID hit = insertHit(tenantId, "HIT", "SKU_STOCK_COVER_DAYS_LOW");
    insertHit(tenantId, "UNDECIDABLE", "SKU_STOCK_COVER_DAYS_LOW");

    JsonNode candidates =
        request(token, "GET", "/identity/external-channel-push/candidates", null, 200);
    assertThat(candidates.get("candidates")).hasSize(1);
    JsonNode only = candidates.get("candidates").get(0);
    assertThat(only.get("sourceKind").asText()).isEqualTo("ALERT_HIT");
    assertThat(only.get("sourceRef").asText()).isEqualTo(hit.toString());
    assertThat(only.get("title").asText()).contains("命中");
    assertThat(only.get("body").asText())
        .as("正文必须按命中事实生成（能对上库里的观测值与阈值）")
        .contains("9.5")
        .contains("14");
    assertThat(candidates.get("sourceNote").asText()).contains("NOTIFICATION");
  }

  // ==========================================================================
  // 四、推送：结果如实 + 偏好过滤
  // ==========================================================================

  @Test
  void 推送在通道未接通时如实失败并落成投递记录() throws Exception {
    String tag = suffix();
    String token = isolatedTenant(tag);
    UUID tenantId = tenantOf(token);
    UUID membershipId = membershipOf(token);

    JsonNode channel =
        registerChannel(token, "WECHAT_WORK", "运营群 " + tag, "secret://projects/starcon/x/wecom");
    String channelId = channel.get("id").asText();
    UUID hit = insertHit(tenantId, "HIT", "SKU_STOCK_COVER_DAYS_LOW");

    OffsetDateTime before = OffsetDateTime.now(ZoneOffset.UTC);
    JsonNode result =
        request(
            token,
            "POST",
            "/identity/external-channel-push/deliveries",
            Map.of(
                "sourceKind", "ALERT_HIT",
                "alertHitId", hit.toString(),
                "notificationType", "STOCKOUT",
                "channelIds", List.of(channelId),
                "recipientMembershipId", membershipId.toString(),
                "linkUrl", "/inventory/stockout-alerts"),
            200);
    OffsetDateTime after = OffsetDateTime.now(ZoneOffset.UTC);

    assertThat(result.get("deliveries")).hasSize(1);
    JsonNode delivery = result.get("deliveries").get(0);
    assertThat(delivery.get("status").asText())
        .as("通道未接通时投递结果必须如实为失败")
        .isEqualTo("FAILED");
    assertThat(delivery.get("failureCode").asText()).isNotBlank();
    assertThat(delivery.get("failureMessage").asText()).isNotBlank();
    assertThat(delivery.get("attemptCount").asInt()).isEqualTo(1);
    assertThat(delivery.get("nextAttemptAt").asText())
        .as("还有重试次数 ⇒ 必须排下一次")
        .isNotBlank();
    assertThat(delivery.get("title").asText()).contains("命中");
    assertThat(delivery.get("linkUrl").asText()).isEqualTo("/inventory/stockout-alerts");

    // 🔴 **时间没被时区吃掉**：写回库的与接口读出的是同一个时刻，且落在请求前后的窗口内。
    OffsetDateTime createdAt = OffsetDateTime.parse(delivery.get("createdAt").asText());
    assertThat(createdAt).isBetween(before.minusSeconds(5), after.plusSeconds(5));
    OffsetDateTime nextAttemptAt = OffsetDateTime.parse(delivery.get("nextAttemptAt").asText());
    assertThat(nextAttemptAt).isAfter(before);
    OffsetDateTime lastAttemptAt = OffsetDateTime.parse(delivery.get("lastAttemptAt").asText());
    assertThat(lastAttemptAt).isBetween(before.minusSeconds(5), after.plusSeconds(5));

    // 库里确实有一行，且列与接口一致。
    var rows =
        tx()
            .run(
                tenantId,
                () ->
                    sql.fetch(
                        "select status, attempt_count, next_attempt_at from external_push_delivery"
                            + " where tenant_id = ?::uuid",
                        tenantId));
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).get("status", String.class)).isEqualTo("FAILED");
    assertThat(rows.get(0).get("attempt_count", Integer.class)).isEqualTo(1);
    assertThat(rows.get(0).get("next_attempt_at", OffsetDateTime.class)).isNotNull();
  }

  @Test
  void 收件人没订阅该渠道时推送被拒且不落记录() throws Exception {
    String tag = suffix();
    String token = isolatedTenant(tag);
    UUID tenantId = tenantOf(token);
    UUID membershipId = membershipOf(token);

    JsonNode channel =
        registerChannel(token, "DINGTALK", "钉钉群 " + tag, "secret://projects/starcon/x/ding");
    String channelId = channel.get("id").asText();

    // 收件人只留站内渠道（这一类通知不是关键业务通知，可以只留站内）。
    savePreferences(token, "STOCKOUT", List.of("IN_APP"), null, "UTC");

    var response =
        http.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                        "/identity/external-channel-push/deliveries")
                    .header("Authorization", "Bearer " + token)
                    .contentType("application/json")
                    .content(
                        json.writeValueAsString(
                            Map.of(
                                "sourceKind", "NOTIFICATION",
                                "notificationType", "STOCKOUT",
                                "notificationRef", "SKU-001",
                                "title", "缺货提醒",
                                "channelIds", List.of(channelId),
                                "recipientMembershipId", membershipId.toString()))))
            .andReturn()
            .getResponse();
    assertThat(response.getStatus())
        .as("收件人的偏好不是可以绕过的")
        .isEqualTo(400);

    assertThat(
            tx()
                .run(
                    tenantId,
                    () -> sql.fetch("select id from external_push_delivery where tenant_id = ?::uuid", tenantId)))
        .as("被拒的推送一行都不许落库")
        .isEmpty();
  }

  @Test
  void 落在免打扰时段里时推迟而不是失败() throws Exception {
    String tag = suffix();
    String token = isolatedTenant(tag);
    UUID tenantId = tenantOf(token);
    UUID membershipId = membershipOf(token);

    JsonNode channel =
        registerChannel(token, "EMAIL", "邮件通道 " + tag, "secret://projects/starcon/x/mail");
    String channelId = channel.get("id").asText();

    // 免打扰时段覆盖当下 ±1 小时（**与星期无关**：只看当地墙上时刻）。
    savePreferences(token, "STOCKOUT", null, quietWindowAroundNow(ZoneOffset.UTC), "UTC");

    OffsetDateTime before = OffsetDateTime.now(ZoneOffset.UTC);
    JsonNode result =
        request(
            token,
            "POST",
            "/identity/external-channel-push/deliveries",
            Map.of(
                "sourceKind", "NOTIFICATION",
                "notificationType", "STOCKOUT",
                "notificationRef", "SKU-002",
                "title", "缺货提醒",
                "channelIds", List.of(channelId),
                "recipientMembershipId", membershipId.toString()),
            200);
    OffsetDateTime after = OffsetDateTime.now(ZoneOffset.UTC);

    assertThat(result.get("deferred").asInt()).isEqualTo(1);
    assertThat(result.get("failed").asInt()).as("推迟不是失败").isZero();
    JsonNode delivery = result.get("deliveries").get(0);
    assertThat(delivery.get("status").asText()).isEqualTo("DEFERRED");
    assertThat(delivery.get("attemptCount").asInt()).as("还没投过").isZero();
    assertThat(delivery.get("nextAttemptAt").asText())
        .as("推迟必须排到时段结束的那一刻")
        .isNotBlank();
    OffsetDateTime resumeAt = OffsetDateTime.parse(delivery.get("nextAttemptAt").asText());
    assertThat(resumeAt).isAfter(before);
    assertThat(resumeAt).isBefore(after.plusHours(3));

    assertThat(
            tx()
                .run(
                    tenantId,
                    () ->
                        sql.fetch(
                            "select status from external_push_delivery where tenant_id = ?::uuid",
                            tenantId)))
        .hasSize(1);
  }

  @Test
  void 渠道不支持卡片与跳转时退化而不报错() throws Exception {
    String tag = suffix();
    String token = isolatedTenant(tag);
    UUID membershipId = membershipOf(token);

    JsonNode channel =
        registerChannel(token, "EMAIL", "邮件通道 " + tag, "secret://projects/starcon/x/mail");
    // 关掉卡片与跳转（渠道属性）。
    request(
        token,
        "PUT",
        "/identity/external-channel-push/channels/" + channel.get("id").asText(),
        Map.of("cardEnabled", false, "linkEnabled", false),
        200);

    JsonNode result =
        request(
            token,
            "POST",
            "/identity/external-channel-push/deliveries",
            Map.of(
                "sourceKind", "NOTIFICATION",
                "notificationType", "STOCKOUT",
                "notificationRef", "SKU-003",
                "title", "缺货提醒",
                "body", "正文",
                "linkUrl", "/inventory/stockout-alerts",
                "cardStyle", "CARD",
                "channelIds", List.of(channel.get("id").asText()),
                "recipientMembershipId", membershipId.toString()),
            200);

    JsonNode delivery = result.get("deliveries").get(0);
    assertThat(delivery.get("cardStyle").asText())
        .as("渠道不支持卡片 ⇒ 退化成纯文本，不是报错")
        .isEqualTo("TEXT");
    assertThat(delivery.get("linkUrl").isNull())
        .as("渠道不支持跳转 ⇒ 不带链接")
        .isTrue();
  }

  // ==========================================================================
  // 五、重试与告警
  // ==========================================================================

  @Test
  void 手工重试会累加尝试次数而耗尽后升起告警() throws Exception {
    String tag = suffix();
    String token = isolatedTenant(tag);
    UUID tenantId = tenantOf(token);
    UUID membershipId = membershipOf(token);

    JsonNode channel =
        registerChannel(token, "FEISHU", "飞书群 " + tag, "secret://projects/starcon/x/feishu");
    String channelId = channel.get("id").asText();

    JsonNode result =
        request(
            token,
            "POST",
            "/identity/external-channel-push/deliveries",
            Map.of(
                "sourceKind", "NOTIFICATION",
                "notificationType", "STOCKOUT",
                "notificationRef", "SKU-004",
                "title", "缺货提醒",
                "channelIds", List.of(channelId),
                "recipientMembershipId", membershipId.toString()),
            200);
    String deliveryId = result.get("deliveries").get(0).get("id").asText();

    // 第一次重试：尝试次数 1 → 2。
    JsonNode afterFirst = request(token, "POST", PUSH_DELIVERIES + "/" + deliveryId + "/retry", null, 200);
    assertThat(afterFirst.get("attemptCount").asInt()).isEqualTo(2);
    assertThat(afterFirst.get("status").asText()).isEqualTo("FAILED");
    assertThat(afterFirst.get("alertState").asText()).as("还没耗尽，不该有告警").isEqualTo("NONE");

    // 第二次重试：2 → 3 = 最大尝试次数 ⇒ **耗尽** ⇒ 终态 + 告警升起。
    JsonNode afterSecond = request(token, "POST", PUSH_DELIVERIES + "/" + deliveryId + "/retry", null, 200);
    assertThat(afterSecond.get("attemptCount").asInt()).isEqualTo(3);
    assertThat(afterSecond.get("status").asText()).isEqualTo("FAILED_EXHAUSTED");
    assertThat(afterSecond.get("alertState").asText()).isEqualTo("RAISED");
    assertThat(afterSecond.get("alertedAt").asText()).isNotBlank();
    assertThat(afterSecond.get("alertMessage").asText()).isNotBlank();
    assertThat(afterSecond.get("nextAttemptAt").isNull())
        .as("耗尽了就不该再排下一次")
        .isTrue();

    // 人工重试再给一次机会：3 → 4，预算同步调大（不撞 attempt_count <= max_attempts）。
    JsonNode afterThird = request(token, "POST", PUSH_DELIVERIES + "/" + deliveryId + "/retry", null, 200);
    assertThat(afterThird.get("attemptCount").asInt()).isEqualTo(4);
    assertThat(afterThird.get("maxAttempts").asInt()).isEqualTo(4);
    assertThat(afterThird.get("status").asText())
        .as("通道未接通 ⇒ 这次仍然失败，如实记")
        .isEqualTo("FAILED_EXHAUSTED");

    // 库里仍然是**一行**（重试更新同一行，不是每次插一行）。
    assertThat(
            tx()
                .run(
                    tenantId,
                    () ->
                        sql.fetch(
                            "select attempt_count, status from external_push_delivery"
                                + " where tenant_id = ?::uuid",
                            tenantId)))
        .hasSize(1);

    // 确认告警：只改告警状态，**不改投递结果**。
    JsonNode acknowledged =
        request(token, "POST", PUSH_DELIVERIES + "/" + deliveryId + "/acknowledge-alert", null, 200);
    assertThat(acknowledged.get("alertState").asText()).isEqualTo("ACKNOWLEDGED");
    assertThat(acknowledged.get("status").asText())
        .as("「我知道了」不等于「它投出去了」")
        .isEqualTo("FAILED_EXHAUSTED");
  }

  @Test
  void 到期扫描真的把到期的记录又试了一次() throws Exception {
    String tag = suffix();
    String token = isolatedTenant(tag);
    UUID tenantId = tenantOf(token);
    UUID membershipId = membershipOf(token);

    JsonNode channel =
        registerChannel(token, "WECHAT_WORK", "运营群 " + tag, "secret://projects/starcon/x/wecom");
    String channelId = channel.get("id").asText();

    JsonNode result =
        request(
            token,
            "POST",
            "/identity/external-channel-push/deliveries",
            Map.of(
                "sourceKind", "NOTIFICATION",
                "notificationType", "STOCKOUT",
                "notificationRef", "SKU-005",
                "title", "缺货提醒",
                "channelIds", List.of(channelId),
                "recipientMembershipId", membershipId.toString()),
            200);
    String deliveryId = result.get("deliveries").get(0).get("id").asText();

    // 未到期时扫不到。
    JsonNode nothingDue = request(token, "POST", PUSH_RETRY_DUE, null, 200);
    assertThat(nothingDue.get("scanned").asInt()).isZero();

    // 把下一次尝试挪到过去（模拟「到期了」）。
    tx()
        .run(
            tenantId,
            () ->
                sql.execute(
                    "update external_push_delivery set next_attempt_at = ?::timestamptz"
                        + " where tenant_id = ?::uuid and id = ?::uuid",
                    OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(10),
                    tenantId,
                    UUID.fromString(deliveryId)));

    JsonNode sweep = request(token, "POST", PUSH_RETRY_DUE, null, 200);
    assertThat(sweep.get("scanned").asInt()).isEqualTo(1);
    assertThat(sweep.get("retried").asInt()).isEqualTo(1);
    assertThat(sweep.get("failed").asInt())
        .as("通道未接通 ⇒ 这次仍然失败，如实记")
        .isEqualTo(1);
    assertThat(sweep.get("delivered").asInt())
        .as("绝不报假成功")
        .isZero();

    JsonNode after = request(token, "GET", PUSH_DELIVERIES, null, 200);
    JsonNode delivery = after.get("deliveries").get(0);
    assertThat(delivery.get("attemptCount").asInt()).isEqualTo(2);
    assertThat(delivery.get("status").asText()).isEqualTo("FAILED");
  }

  // ==========================================================================
  // 六、投递记录清单：真分页 + 真筛选
  // ==========================================================================

  @Test
  void 投递记录清单是真分页且筛选真的生效() throws Exception {
    String tag = suffix();
    String token = isolatedTenant(tag);
    UUID membershipId = membershipOf(token);

    JsonNode channel =
        registerChannel(token, "EMAIL", "邮件通道 " + tag, "secret://projects/starcon/x/mail");
    String channelId = channel.get("id").asText();

    for (int i = 0; i < 3; i++) {
      request(
          token,
          "POST",
          PUSH_DELIVERIES,
          Map.of(
              "sourceKind", "NOTIFICATION",
              "notificationType", "STOCKOUT",
              "notificationRef", "SKU-" + tag + "-" + i,
              "title", "缺货提醒 " + i,
              "channelIds", List.of(channelId),
              "recipientMembershipId", membershipId.toString()),
          200);
    }

    JsonNode page = request(token, "GET", PUSH_DELIVERIES + "?limit=2&offset=0", null, 200);
    assertThat(page.get("deliveries")).hasSize(2);
    assertThat(page.get("total").asInt()).isEqualTo(3);
    assertThat(page.get("limit").asInt()).isEqualTo(2);
    assertThat(page.get("offset").asInt()).isZero();

    JsonNode second = request(token, "GET", PUSH_DELIVERIES + "?limit=2&offset=2", null, 200);
    assertThat(second.get("deliveries")).hasSize(1);

    // 筛选：按状态筛。
    JsonNode failed = request(token, "GET", PUSH_DELIVERIES + "?status=FAILED", null, 200);
    assertThat(failed.get("total").asInt()).isEqualTo(3);

    // 筛选：按一个不存在的状态 ⇒ 零行，但 total 也必须是 0（不是「还是 3 条」）。
    JsonNode none = request(token, "GET", PUSH_DELIVERIES + "?status=DELIVERED", null, 200);
    assertThat(none.get("deliveries")).isEmpty();
    assertThat(none.get("total").asInt()).isZero();

    // 筛选：词表外的状态 ⇒ 400（不静默忽略）。
    var bad =
        http.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                        PUSH_DELIVERIES + "?status=NOT_A_STATUS")
                    .header("Authorization", "Bearer " + token))
            .andReturn()
            .getResponse();
    assertThat(bad.getStatus()).isEqualTo(400);
  }

  // ==========================================================================
  // 七、权限与边界
  // ==========================================================================

  @Test
  void 没有权限的成员看不到也改不了() throws Exception {
    String tag = suffix();
    String owner = isolatedTenant(tag);
    String nobody = memberWithout(owner, tag);

    request(nobody, "GET", "/identity/external-channel-push/channels", null, 403);
    request(nobody, "GET", PUSH_DELIVERIES, null, 403);
    request(
        nobody,
        "POST",
        "/identity/external-channel-push/channels",
        Map.of(
            "channelCode", "EMAIL",
            "displayName", "越权渠道",
            "secretRef", "secret://x"),
        403);
  }

  @Test
  void 请求体不接受租户字段且地址本体进不来() throws Exception {
    String tag = suffix();
    String token = isolatedTenant(tag);

    // 租户字段由 Jackson 的 fail-on-unknown-properties 直接 400 拒掉。
    var withTenant =
        http.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                        "/identity/external-channel-push/channels")
                    .header("Authorization", "Bearer " + token)
                    .contentType("application/json")
                    .content(
                        json.writeValueAsString(
                            Map.of(
                                "channelCode", "EMAIL",
                                "displayName", "越权渠道",
                                "secretRef", "secret://x",
                                "tenantId", UUID.randomUUID().toString()))))
            .andReturn()
            .getResponse();
    assertThat(withTenant.getStatus()).isEqualTo(400);

    // 跳转目标只允许站内相对路径。
    String channelId =
        registerChannel(token, "EMAIL", "邮件通道 " + tag, "secret://projects/starcon/x/mail")
            .get("id")
            .asText();
    UUID membershipId = membershipOf(token);
    var externalLink =
        http.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                        PUSH_DELIVERIES)
                    .header("Authorization", "Bearer " + token)
                    .contentType("application/json")
                    .content(
                        json.writeValueAsString(
                            Map.of(
                                "sourceKind", "NOTIFICATION",
                                "notificationType", "STOCKOUT",
                                "notificationRef", "SKU-006",
                                "title", "缺货提醒",
                                "linkUrl", "https://evil.example.com/steal",
                                "channelIds", List.of(channelId),
                                "recipientMembershipId", membershipId.toString()))))
            .andReturn()
            .getResponse();
    assertThat(externalLink.getStatus())
        .as("跳转目标只允许站内相对路径")
        .isEqualTo(400);
  }

  @Test
  void 命中是算不出来时不能推() throws Exception {
    String tag = suffix();
    String token = isolatedTenant(tag);
    UUID tenantId = tenantOf(token);
    UUID membershipId = membershipOf(token);

    JsonNode channel =
        registerChannel(token, "EMAIL", "邮件通道 " + tag, "secret://projects/starcon/x/mail");
    UUID undecidable = insertHit(tenantId, "UNDECIDABLE", "SKU_STOCK_COVER_DAYS_LOW");

    var response =
        http.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(PUSH_DELIVERIES)
                    .header("Authorization", "Bearer " + token)
                    .contentType("application/json")
                    .content(
                        json.writeValueAsString(
                            Map.of(
                                "sourceKind", "ALERT_HIT",
                                "alertHitId", undecidable.toString(),
                                "notificationType", "STOCKOUT",
                                "channelIds", List.of(channel.get("id").asText()),
                                "recipientMembershipId", membershipId.toString()))))
            .andReturn()
            .getResponse();
    assertThat(response.getStatus())
        .as("算不出来的命中不是命中，不许推")
        .isEqualTo(400);
  }

  // ==========================================================================
  // 常量
  // ==========================================================================

  private static final String PUSH_DELIVERIES = "/identity/external-channel-push/deliveries";
  private static final String PUSH_RETRY_DUE = "/identity/external-channel-push/deliveries/retry-due";
}
