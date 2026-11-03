package com.starcon.platform;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.platform.DeliveryRecordModels.*;
import com.starcon.tenancy.TenantTransactions;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.jooq.DSLContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 18.1.2.2「投递记录」的**功能**集成测试，跑在真实 PostgreSQL 16 上。
 *
 * <p><b>真实 PostgreSQL 16 + FORCE RLS，不使用任何内存替身。</b>
 *
 * <p>🔴 <b>收方是一个真的 HTTP 服务器</b>（{@code com.sun.net.httpserver.HttpServer}）：
 * 本模块的核心承诺是「**真的把包发出去，返回码真的记下来**」——
 * 用一个 mock 打桩的发送器，这条承诺就永远验不了。
 * 所以测试里的收方会：
 *
 * <ul>
 *   <li><b>真的</b>收到 POST，把方法、路径、请求头、请求体逐条记下来；
 *   <li>按测试指定的返回码应答（200 / 500 / 404 / 302 …）；
 *   <li>并且由测试**独立复算** HMAC-SHA256 签名 —— 证明平台发出去的那份签名是真的，
 *       而不是一个占位字符串。
 * </ul>
 *
 * <p>覆盖蓝图点名的四件事：
 *
 * <ul>
 *   <li><b>投递明细</b>：事件 ID、订阅方、投递状态、响应码、重试次数；
 *   <li><b>投递日志</b>：每一次尝试一行（响应码、耗时、错误）；
 *   <li><b>手动重投</b>：重发同一份报文字节，事件 ID 不变；
 *   <li><b>死信重放</b>：死信重放后死信状态收口。
 * </ul>
 *
 * <p>租户隔离的三条断言在 {@code DeliveryRecordTenancyIntegrationTest} 里。
 */
@SpringBootTest
@AutoConfigureMockMvc
class DeliveryRecordIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static HttpServer identity;
  static DeliveryRecordReceiver receiver;

  /** 测试里的签名密钥明文（收方用它独立复算签名）。 */
  static final String PLAIN_SECRET = "test-signing-secret-plaintext";

  static final ObjectMapper json =
      new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

  static {
    pg.start();
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      // 运行角色必须 NOBYPASSRLS，否则它会绕过策略，隔离断言全部变成空断言。
      s.execute("create role starcon_app nologin");
      s.execute("create role platform_app login password 'test-only' nosuperuser nobypassrls");
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
    var permissions =
        List.of(
            "platform.webhook.delivery.read",
            "platform.webhook.delivery.publish",
            "platform.webhook.delivery.replay");
    actors.put(
        "session-a",
        new AccessService.Access(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "Test",
            UUID.randomUUID(),
            null,
            permissions,
            List.of(new AccessService.Grant(UUID.randomUUID(), "TEST", "ALL", permissions))));
    try {
      identity = HttpServer.create(new InetSocketAddress(0), 0);
      identity.createContext(
          "/internal/access",
          e -> {
            var access = actors.get(e.getRequestHeaders().getFirst("Authorization"));
            if (access == null) {
              e.sendResponseHeaders(401, -1);
              e.close();
              return;
            }
            byte[] body = json.writeValueAsBytes(access);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      identity.setExecutor(Executors.newFixedThreadPool(2));
      identity.start();
      receiver = new DeliveryRecordReceiver();
      receiver.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "platform_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://127.0.0.1:" + identity.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
  }

  @Autowired DSLContext sql;
  @Autowired TenantTransactions tenants;
  @Autowired MockMvc http;

  // ================================================================ 夹具

  private UUID tenant() {
    return actors.get("session-a").tenantId();
  }

  /** 直接读库时**必须**在可信租户事务里读 —— 否则 RLS 会把行全部挡掉。 */
  private <T> T inTenant(java.util.function.Supplier<T> work) {
    return tenants.run(tenant(), work);
  }

  @BeforeEach
  void reset() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate table platform_delivery_replay_task, platform_delivery_dead_letter,"
              + " platform_delivery_attempt, platform_delivery_record,"
              + " platform_webhook_audit_event, platform_webhook_policy,"
              + " platform_webhook_signing_secret, platform_webhook_subscription_event,"
              + " platform_webhook_subscription, platform_webhook_event_type, platform_app"
              + " restart identity cascade");
    }
    receiver.reset();
  }

  /** 一套完整夹具：应用 + 事件定义 + 订阅 + 过滤 + 签名密钥（真密文）+ 策略。 */
  private Fixture seed(
      String eventType, String targetUrl, String filterJson, Integer maxAttempts, boolean withSecret) {
    var tenantId = tenant();
    return inTenant(
        () -> {
          var now = OffsetDateTime.now(java.time.ZoneOffset.UTC);
          var appId = UUID.randomUUID();
          sql.execute(
              "insert into platform_app(id,tenant_id,app_code,app_name,app_type,description,status,"
                  + " created_at,updated_at) values(?,?,?,'夹具应用','THIRD_PARTY','','ACTIVE',"
                  + " ?::timestamptz,?::timestamptz)",
              appId,
              tenantId,
              "app-" + appId.toString().substring(0, 8),
              now,
              now);
          sql.execute(
              "insert into platform_webhook_event_type(id,tenant_id,event_type,schema_version,status,"
                  + " payload_schema,source_service,source_artifact,registered_at)"
                  + " values(?,?,?,1,'ACTIVE','{\"type\":\"object\"}'::jsonb,'catalog','a.java:1',"
                  + " ?::timestamptz)",
              UUID.randomUUID(),
              tenantId,
              eventType,
              now);
          var subscriptionId = UUID.randomUUID();
          sql.execute(
              "insert into platform_webhook_subscription(id,tenant_id,app_id,name,target_url,status,"
                  + " description,created_at,updated_at)"
                  + " values(?,?,?,?,?,'ACTIVE','',?::timestamptz,?::timestamptz)",
              subscriptionId,
              tenantId,
              appId,
              "夹具订阅-" + eventType,
              targetUrl,
              now,
              now);
          sql.execute(
              "insert into platform_webhook_subscription_event(id,tenant_id,subscription_id,event_type,"
                  + " schema_version,filter,enabled,created_at)"
                  + " values(?,?,?,?,1,?::jsonb,true,?::timestamptz)",
              UUID.randomUUID(),
              tenantId,
              subscriptionId,
              eventType,
              filterJson,
              now);
          if (withSecret) {
            // 🔴 **真密文**：用与平台同一个 cipher 加密（测试与服务同 INTERNAL_KEY='test-key'），
            //   否则平台解不开，签名算不出来 —— 而那正是「签名是真的」的证明。
            var sealed = new WebhookSigningSecretCipher("test-key").seal(PLAIN_SECRET);
            sql.execute(
                "insert into platform_webhook_signing_secret(id,tenant_id,subscription_id,version,status,"
                    + " secret_ciphertext,secret_hint,created_at)"
                    + " values(?,?,?,1,'ACTIVE',?,?,?::timestamptz)",
                UUID.randomUUID(),
                tenantId,
                subscriptionId,
                sealed,
                PLAIN_SECRET.substring(PLAIN_SECRET.length() - 4),
                now);
          }
          // ⚠️ **订阅级专属**策略（subscription_id 非空）：租户级默认策略有
          //   platform_webhook_policy_tenant_default 部分唯一索引 —— 同一租户插两条默认策略会撞键，
          //   而本测试要给两个订阅各配一套不同的重试参数。
          sql.execute(
              "insert into platform_webhook_policy(id,tenant_id,subscription_id,max_attempts,"
                  + " initial_backoff_seconds,backoff_multiplier,max_backoff_seconds,"
                  + " request_timeout_seconds,dead_letter_retention_days,created_at,updated_at)"
                  + " values(?,?,?,?,1,1.00,60,5,30,?::timestamptz,?::timestamptz)",
              UUID.randomUUID(),
              tenantId,
              subscriptionId,
              maxAttempts == null ? 5 : maxAttempts,
              now,
              now);
          return new Fixture(appId, subscriptionId, eventType);
        });
  }

  private record Fixture(UUID appId, UUID subscriptionId, String eventType) {}

  // ================================================================ 工具

  /** 发布一个事件（走真实端点）。 */
  private DeliveryRecordPublishResult publish(String eventType, String payload, UUID eventId)
      throws Exception {
    // ⚠️ payload 是**一段 JSON 文本**（平台服务既有契约口径：JSON 字段一律 String，
    //   与 18.1.2.1 的 payloadSchema / filter / 签名试算载荷同一形状）。
    var body =
        """
        {"eventId":"%s","eventType":"%s","schemaVersion":1,"payload":%s}
        """
            .formatted(
                eventId == null ? "" : eventId.toString(),
                eventType,
                json.writeValueAsString(payload));
    var mvc =
        http.perform(
                post("/platform/deliveries/publish")
                    .header("Authorization", "session-a")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andReturn();
    var response = mvc.getResponse();
    assertThat(response.getStatus())
        .as(
            "publish 失败：status=%s body=%s error=%s resolved=%s",
            response.getStatus(),
            response.getContentAsString(),
            response.getErrorMessage(),
            mvc.getResolvedException())
        .isEqualTo(200);
    return json.readValue(response.getContentAsString(), DeliveryRecordPublishResult.class);
  }

  /** 独立复算签名（**不**用平台的代码 —— 用 JDK 的 Mac 自己算一遍）。 */
  private static String sign(String secret, String timestamp, String body) {
    try {
      var mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return java.util.HexFormat.of()
          .formatHex(mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  // ==========================================================================
  // 核心：投递要**真的**发出去，响应码要**真的**记下来
  // ==========================================================================

  @Test
  void 发布事件真的把包发到订阅端点并记下响应码() throws Exception {
    seed("SkuProfileSaved", receiver.url("/hook"), "{}", 5, true);

    var result = publish("SkuProfileSaved", "{\"sku\":\"SKU-1\"}", null);

    assertThat(result.matchedCount()).isEqualTo(1);
    assertThat(result.deliveries()).hasSize(1);
    assertThat(result.failures()).isEmpty();

    // 收方**真的**收到了一个 POST。
    var received = receiver.requests();
    assertThat(received).hasSize(1);
    assertThat(received.get(0).method()).isEqualTo("POST");
    assertThat(received.get(0).path()).isEqualTo("/hook");

    // 报文里必须有 event_id（接收方按它幂等去重）。
    var envelope = json.readTree(received.get(0).body());
    assertThat(envelope.get("event_id").asText()).isEqualTo(result.eventId().toString());
    assertThat(envelope.get("event_type").asText()).isEqualTo("SkuProfileSaved");
    assertThat(envelope.get("schema_version").asInt()).isEqualTo(1);
    assertThat(envelope.get("data").get("sku").asText()).isEqualTo("SKU-1");

    // 投递记录：状态 DELIVERED、响应码 200、尝试次数 1。
    var delivery = result.deliveries().get(0);
    assertThat(delivery.status()).isEqualTo("DELIVERED");
    assertThat(delivery.statusCode()).isEqualTo(200);
    assertThat(delivery.attemptCount()).isEqualTo(1);
    assertThat(delivery.subscriptionId()).isNotNull();
    assertThat(delivery.eventId()).isEqualTo(result.eventId());

    // 投递日志：一行，带响应码。
    var attempts =
        inTenant(
            () ->
                sql.fetch(
                        "select attempt_no,status_code,success,attempt_source from platform_delivery_attempt"
                            + " where delivery_id = ? order by attempt_no",
                        delivery.id())
                    .size());
    assertThat(attempts).isEqualTo(1);
  }

  @Test
  void 签名头是真的且可被收方独立复算() throws Exception {
    seed("SkuProfileSaved", receiver.url("/hook"), "{}", 5, true);
    publish("SkuProfileSaved", "{\"sku\":\"SKU-1\"}", null);

    var received = receiver.requests().get(0);
    var header = received.headers().get("x-starcon-signature");
    assertThat(header).as("平台必须发签名头").isNotNull();
    assertThat(header).startsWith("t=");
    assertThat(header).contains(",v1=");

    var timestamp = header.substring(2, header.indexOf(','));
    var presented = header.substring(header.indexOf(",v1=") + 4);

    // 🔴 **收方自己算一遍**：证明平台发出去的那份签名是真的，不是一个占位串。
    assertThat(presented).isEqualTo(sign(PLAIN_SECRET, timestamp, received.body()));

    // 事件 ID 也在头里（对方连报文都不解析就能去重）。
    assertThat(received.headers().get("x-starcon-event-id")).isNotBlank();
  }

  @Test
  void 对方返回5xx时如实记下响应码且不谎称成功() throws Exception {
    receiver.status(500);
    seed("SkuProfileSaved", receiver.url("/hook"), "{}", 5, true);

    var result = publish("SkuProfileSaved", "{\"sku\":\"SKU-1\"}", null);

    var delivery = result.deliveries().get(0);
    assertThat(delivery.statusCode()).isEqualTo(500);
    assertThat(delivery.status()).as("5xx 不是送达").isNotEqualTo("DELIVERED");
    assertThat(delivery.status()).isEqualTo("PENDING");
    assertThat(delivery.lastError()).isNotBlank();
    assertThat(delivery.nextRetryAt()).as("还有重试次数 ⇒ 应当排下一次重试").isNotNull();
    assertThat(result.failures())
        .as("这次没送达就是一次失败，必须如实报出来（重试队列会接着处理）")
        .hasSize(1);
    assertThat(result.failures().get(0).statusCode()).isEqualTo(500);
  }

  @Test
  void 重试次数用尽转入死信() throws Exception {
    receiver.status(500);
    seed("SkuProfileSaved", receiver.url("/hook"), "{}", 1, true);

    var result = publish("SkuProfileSaved", "{\"sku\":\"SKU-1\"}", null);

    var delivery = result.deliveries().get(0);
    assertThat(delivery.status()).isEqualTo("DEAD_LETTER");
    assertThat(delivery.attemptCount()).isEqualTo(1);
    assertThat(delivery.deadLetteredAt()).isNotNull();

    var deadLetters =
        inTenant(() -> sql.fetch("select id,status,reason from platform_delivery_dead_letter").size());
    assertThat(deadLetters).isEqualTo(1);
  }

  @Test
  void 没有生效中的签名密钥时如实判为永久失败而不是假装送达() throws Exception {
    seed("SkuProfileSaved", receiver.url("/hook"), "{}", 5, false);

    var result = publish("SkuProfileSaved", "{\"sku\":\"SKU-1\"}", null);

    var delivery = result.deliveries().get(0);
    assertThat(delivery.status()).isEqualTo("FAILED");
    assertThat(delivery.statusCode()).as("根本没发出去 ⇒ 没有响应码").isEqualTo(0);
    assertThat(delivery.lastError()).contains("签名密钥");
    assertThat(receiver.requests()).as("没有密钥 ⇒ 一个包都不该发出去").isEmpty();
    assertThat(result.failures()).hasSize(1);
  }

  @Test
  void 过滤条件不匹配的订阅不投递() throws Exception {
    seed("SkuProfileSaved", receiver.url("/hook"), "{\"sku\":\"SKU-OTHER\"}", 5, true);

    var result = publish("SkuProfileSaved", "{\"sku\":\"SKU-1\"}", null);

    assertThat(result.matchedCount()).isZero();
    assertThat(result.deliveries()).isEmpty();
    assertThat(result.skipped()).hasSize(1);
    assertThat(result.skipped().get(0).reason()).contains("过滤条件");
    assertThat(receiver.requests()).isEmpty();
  }

  @Test
  void 过滤条件命中时照常投递() throws Exception {
    seed("SkuProfileSaved", receiver.url("/hook"), "{\"sku\":\"SKU-1\"}", 5, true);

    var result = publish("SkuProfileSaved", "{\"sku\":\"SKU-1\"}", null);

    assertThat(result.matchedCount()).isEqualTo(1);
    assertThat(receiver.requests()).hasSize(1);
  }

  @Test
  void 同一个事件重复发布是幂等的不会重复发包() throws Exception {
    seed("SkuProfileSaved", receiver.url("/hook"), "{}", 5, true);
    var eventId = UUID.randomUUID();

    var first = publish("SkuProfileSaved", "{\"sku\":\"SKU-1\"}", eventId);
    var second = publish("SkuProfileSaved", "{\"sku\":\"SKU-1\"}", eventId);

    assertThat(first.eventId()).isEqualTo(eventId);
    assertThat(second.eventId()).isEqualTo(eventId);
    assertThat(second.deliveries()).as("幂等：不再新建投递记录").isEmpty();
    assertThat(second.skipped().get(0).reason()).contains("幂等");
    assertThat(receiver.requests()).as("幂等：不会重复发包").hasSize(1);
    var rows =
        inTenant(() -> sql.fetch("select id from platform_delivery_record").size());
    assertThat(rows).isEqualTo(1);
  }

  // ==========================================================================
  // 手动重投与死信重放
  // ==========================================================================

  @Test
  void 手动重投重发同一份报文字节且事件id不变() throws Exception {
    seed("SkuProfileSaved", receiver.url("/hook"), "{}", 5, true);
    var published = publish("SkuProfileSaved", "{\"sku\":\"SKU-1\"}", null);
    var deliveryId = published.deliveries().get(0).id();

    var response =
        http.perform(
                post("/platform/deliveries/" + deliveryId + "/replay")
                    .header("Authorization", "session-a")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"reason\":\"对方说没收到\"}"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();
    var replay = json.readValue(response.getContentAsString(), DeliveryRecordReplayResult.class);

    assertThat(replay.task().triggerType()).isEqualTo("MANUAL");
    assertThat(replay.task().status()).isEqualTo("SUCCEEDED");
    assertThat(replay.task().resultStatusCode()).isEqualTo(200);
    assertThat(replay.task().reason()).isEqualTo("对方说没收到");

    // 收方收到两次，**报文字节逐字相同**、**事件 ID 相同** ⇒ 对方按 event_id 去重即可。
    var received = receiver.requests();
    assertThat(received).hasSize(2);
    assertThat(received.get(1).body()).isEqualTo(received.get(0).body());
    assertThat(json.readTree(received.get(1).body()).get("event_id").asText())
        .isEqualTo(json.readTree(received.get(0).body()).get("event_id").asText());

    var row = replay.delivery();
    assertThat(row.replayCount()).isEqualTo(1);
    assertThat(row.attemptCount()).isEqualTo(2);
  }

  @Test
  void 死信重放把死信状态收口且真的又发了一次() throws Exception {
    receiver.status(500);
    seed("SkuProfileSaved", receiver.url("/hook"), "{}", 1, true);
    var published = publish("SkuProfileSaved", "{\"sku\":\"SKU-1\"}", null);
    var deliveryId = published.deliveries().get(0).id();
    assertThat(published.deliveries().get(0).status()).isEqualTo("DEAD_LETTER");

    var deadLetterId =
        inTenant(
            () ->
                sql.fetchOne(
                        "select id from platform_delivery_dead_letter where delivery_id = ?", deliveryId)
                    .get(0, UUID.class));

    // 这次对方正常收 ⇒ 重放成功。
    receiver.status(200);
    var response =
        http.perform(
                post("/platform/deliveries/dead-letters/" + deadLetterId + "/replay")
                    .header("Authorization", "session-a")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"reason\":\"修好了\"}"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();
    var replay = json.readValue(response.getContentAsString(), DeliveryRecordReplayResult.class);

    assertThat(replay.task().triggerType()).isEqualTo("DEAD_LETTER");
    assertThat(replay.task().status()).isEqualTo("SUCCEEDED");
    assertThat(replay.delivery().status()).isEqualTo("DELIVERED");
    assertThat(receiver.requests()).hasSize(2);

    var deadLetterStatus =
        inTenant(
            () ->
                sql.fetchOne("select status from platform_delivery_dead_letter where id = ?", deadLetterId)
                    .get(0, String.class));
    assertThat(deadLetterStatus).isEqualTo("REPLAYED");

    // 重复重放被拒（死信已经处理过）。
    http.perform(
            post("/platform/deliveries/dead-letters/" + deadLetterId + "/replay")
                .header("Authorization", "session-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isConflict());
  }

  @Test
  void 重放任务有独立台账可以查() throws Exception {
    seed("SkuProfileSaved", receiver.url("/hook"), "{}", 5, true);
    var published = publish("SkuProfileSaved", "{\"sku\":\"SKU-1\"}", null);
    var deliveryId = published.deliveries().get(0).id();

    http.perform(
            post("/platform/deliveries/" + deliveryId + "/replay")
                .header("Authorization", "session-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isOk());

    var response =
        http.perform(get("/platform/deliveries/replay-tasks").header("Authorization", "session-a"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();
    var page = json.readValue(response.getContentAsString(), DeliveryRecordReplayTaskPage.class);
    assertThat(page.total()).isEqualTo(1);
    assertThat(page.items().get(0).deliveryId()).isEqualTo(deliveryId);
    assertThat(page.items().get(0).triggerType()).isEqualTo("MANUAL");
  }

  // ==========================================================================
  // 到期重试扫描（外部调度触发的入口）
  // ==========================================================================

  @Test
  void 到期重试扫描会真的再投一次() throws Exception {
    receiver.status(500);
    seed("SkuProfileSaved", receiver.url("/hook"), "{}", 5, true);
    var published = publish("SkuProfileSaved", "{\"sku\":\"SKU-1\"}", null);
    assertThat(published.deliveries().get(0).status()).isEqualTo("PENDING");

    // 把 next_retry_at 挪到过去 ⇒ 这次扫描就该捡到它。
    inTenant(
        () -> {
          sql.execute(
              "update platform_delivery_record set next_retry_at = ?::timestamptz - interval '1 minute'",
              OffsetDateTime.now(java.time.ZoneOffset.UTC));
          return null;
        });

    receiver.status(200);
    var response =
        http.perform(
                post("/platform/deliveries/retry-due")
                    .header("Authorization", "session-a")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();
    var sweep = json.readValue(response.getContentAsString(), DeliveryRecordRetrySweep.class);

    assertThat(sweep.scanned()).isEqualTo(1);
    assertThat(sweep.retried()).isEqualTo(1);
    assertThat(sweep.delivered()).isEqualTo(1);
    assertThat(receiver.requests()).hasSize(2);

    var status =
        inTenant(
            () ->
                sql.fetchOne("select status from platform_delivery_record").get(0, String.class));
    assertThat(status).isEqualTo("DELIVERED");
  }

  @Test
  void 没到期的记录不会被扫描捡走() throws Exception {
    receiver.status(500);
    seed("SkuProfileSaved", receiver.url("/hook"), "{}", 5, true);
    publish("SkuProfileSaved", "{\"sku\":\"SKU-1\"}", null);

    var response =
        http.perform(
                post("/platform/deliveries/retry-due")
                    .header("Authorization", "session-a")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();
    var sweep = json.readValue(response.getContentAsString(), DeliveryRecordRetrySweep.class);
    assertThat(sweep.scanned()).isZero();
    assertThat(receiver.requests()).hasSize(1);
  }

  // ==========================================================================
  // 读路径：列表、详情、能力声明、概览
  // ==========================================================================

  @Test
  void 投递记录列表支持真分页与真筛选() throws Exception {
    seed("SkuProfileSaved", receiver.url("/hook"), "{}", 5, true);
    seed("OrderPaid", receiver.url("/hook2"), "{}", 5, true);
    publish("SkuProfileSaved", "{\"sku\":\"SKU-1\"}", null);
    publish("OrderPaid", "{\"order\":\"O-1\"}", null);

    var all =
        json.readValue(
            http.perform(get("/platform/deliveries").header("Authorization", "session-a"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            DeliveryRecordPage.class);
    assertThat(all.total()).isEqualTo(2);

    var filtered =
        json.readValue(
            http.perform(
                    get("/platform/deliveries").param("eventType", "OrderPaid")
                        .header("Authorization", "session-a"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            DeliveryRecordPage.class);
    assertThat(filtered.total()).isEqualTo(1);
    assertThat(filtered.items().get(0).eventType()).isEqualTo("OrderPaid");

    var byStatus =
        json.readValue(
            http.perform(
                    get("/platform/deliveries").param("status", "DELIVERED")
                        .header("Authorization", "session-a"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            DeliveryRecordPage.class);
    assertThat(byStatus.total()).isEqualTo(2);

    var paged =
        json.readValue(
            http.perform(
                    get("/platform/deliveries").param("page", "0").param("size", "1")
                        .header("Authorization", "session-a"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            DeliveryRecordPage.class);
    assertThat(paged.items()).hasSize(1);
    assertThat(paged.total()).isEqualTo(2);

    // 非法状态是 400，不是静默忽略。
    http.perform(
            get("/platform/deliveries").param("status", "NOT_A_STATUS")
                .header("Authorization", "session-a"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void 投递详情带出投递日志与重放任务() throws Exception {
    seed("SkuProfileSaved", receiver.url("/hook"), "{}", 5, true);
    var published = publish("SkuProfileSaved", "{\"sku\":\"SKU-1\"}", null);
    var deliveryId = published.deliveries().get(0).id();

    var detail =
        json.readValue(
            http.perform(get("/platform/deliveries/" + deliveryId).header("Authorization", "session-a"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            DeliveryRecordDetail.class);
    assertThat(detail.delivery().id()).isEqualTo(deliveryId);
    assertThat(detail.attempts()).hasSize(1);
    assertThat(detail.attempts().get(0).statusCode()).isEqualTo(200);
    assertThat(detail.attempts().get(0).attemptSource()).isEqualTo("INITIAL");
    assertThat(detail.attempts().get(0).success()).isTrue();
    assertThat(detail.deadLetter()).isNull();
    assertThat(detail.replayTasks()).isEmpty();

    var attempts =
        json.readValue(
            http.perform(
                    get("/platform/deliveries/" + deliveryId + "/attempts")
                        .header("Authorization", "session-a"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            DeliveryRecordAttemptRow[].class);
    assertThat(attempts).hasSize(1);
  }

  @Test
  void 不存在的投递记录是404() throws Exception {
    http.perform(get("/platform/deliveries/" + UUID.randomUUID()).header("Authorization", "session-a"))
        .andExpect(status().isNotFound());
  }

  @Test
  void 能力声明如实说投递已接通并写明上游缺口() throws Exception {
    var response =
        http.perform(get("/platform/deliveries/capabilities").header("Authorization", "session-a"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();
    var capabilities = json.readValue(response.getContentAsString(), DeliveryRecordCapabilities.class);
    assertThat(capabilities.capabilities()).isNotEmpty();

    var delivery =
        capabilities.capabilities().stream()
            .filter(c -> c.name().contains("事件实际投递"))
            .findFirst()
            .orElseThrow();
    assertThat(delivery.available()).as("投递执行器归本模块 ⇒ 必须已接通").isTrue();
    assertThat(delivery.module()).isEqualTo("18.1.2.2");

    // 上游 outbox relay 仍未接入 —— 必须**如实**说 available=false。
    var relay =
        capabilities.capabilities().stream()
            .filter(c -> c.name().contains("outbox relay"))
            .findFirst()
            .orElseThrow();
    assertThat(relay.available()).isFalse();
    assertThat(relay.impact()).isNotBlank();
  }

  @Test
  void 概览的数是真查库的() throws Exception {
    seed("SkuProfileSaved", receiver.url("/hook"), "{}", 5, true);
    publish("SkuProfileSaved", "{\"sku\":\"SKU-1\"}", null);

    var overview =
        json.readValue(
            http.perform(get("/platform/deliveries/overview").header("Authorization", "session-a"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            DeliveryRecordOverview.class);
    assertThat(overview.deliveryCount()).isEqualTo(1);
    assertThat(overview.deliveredCount()).isEqualTo(1);
    assertThat(overview.pendingCount()).isZero();
    assertThat(overview.deadLetterCount()).isZero();
    assertThat(overview.attemptCount()).isEqualTo(1);
  }

  // ==========================================================================
  // 时间戳：写回的值必须真的落在 DB 时钟的区间里
  // ==========================================================================

  @Test
  void 落库时间戳取的是db时钟且落在调用区间内() throws Exception {
    seed("SkuProfileSaved", receiver.url("/hook"), "{}", 5, true);

    var before = inTenant(() -> sql.fetchOne("select clock_timestamp()").get(0, OffsetDateTime.class));
    var published = publish("SkuProfileSaved", "{\"sku\":\"SKU-1\"}", null);
    var after = inTenant(() -> sql.fetchOne("select clock_timestamp()").get(0, OffsetDateTime.class));

    var delivery = published.deliveries().get(0);
    // 🔴 断言**写回的值**，不是只断言没抛异常。
    assertThat(delivery.deliveredAt()).isNotNull();
    assertThat(delivery.deliveredAt()).isBetween(before, after);
    assertThat(delivery.lastAttemptAt()).isBetween(before, after);
    assertThat(delivery.firstAttemptAt()).isBetween(before, after);
    assertThat(delivery.createdAt()).isBetween(before, after);
  }

  // ==========================================================================
  // 迁移落地检查
  // ==========================================================================

  @Test
  void 本模块的四张表真的建出来了() {
    for (String table :
        List.of(
            "platform_delivery_record",
            "platform_delivery_attempt",
            "platform_delivery_dead_letter",
            "platform_delivery_replay_task")) {
      var row =
          sql.fetchOne(
              "select relrowsecurity, relforcerowsecurity from pg_class where relname = ?", table);
      assertThat(row).as("V413 必须真的建出 " + table).isNotNull();
      assertThat(row.get(0, Boolean.class)).as(table + " 未启用 RLS").isTrue();
      assertThat(row.get(1, Boolean.class)).as(table + " 未 FORCE RLS").isTrue();
      var nullable =
          sql.fetchOne(
                  "select is_nullable from information_schema.columns"
                      + " where table_name = ? and column_name = 'tenant_id'",
                  table)
              .get(0, String.class);
      assertThat(nullable).as(table + " 的 tenant_id 必须是 NOT NULL").isEqualTo("NO");
    }
  }

  @Test
  void 本模块一个浮点列都没有() {
    var floats =
        sql.fetch(
            "select table_name,column_name,data_type from information_schema.columns"
                + " where table_name in ('platform_delivery_record','platform_delivery_attempt',"
                + " 'platform_delivery_dead_letter','platform_delivery_replay_task')"
                + " and data_type in ('double precision','real','float4','float8')");
    assertThat(floats).as("金额与数量一律禁浮点").isEmpty();
  }
}
