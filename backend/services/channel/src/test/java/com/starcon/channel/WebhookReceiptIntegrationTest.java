package com.starcon.channel;

import static com.starcon.channel.WebhookModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
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
 * 02.6.1.1 接收记录的行为约束。
 *
 * <p>蓝图 §2.6.1.1 的四条要求逐条钉住：
 *
 * <ol>
 *   <li>先持久化再处理：接收成功即落库返回 200，业务处理异步化
 *   <li>记录签名校验结果、外部事件 ID、来源店铺、接收时间、处理状态
 *   <li>重复 webhook 不产生第二条可处理记录（去重锚点是外部事件 ID）
 *   <li>多租户：两租户互不可见、伪造 tenant header 无效、连接池归还后不串租户
 * </ol>
 *
 * <p>签名用<b>真实密钥对</b>构造：{@link #sign} 与被测代码走同一个 HMAC-SHA256 算法，
 * 但密钥由测试自己持有，因此"算错了"和"密钥不对"是两件可区分的事。
 */
@SpringBootTest
@AutoConfigureMockMvc
class WebhookReceiptIntegrationTest {
  static final PostgreSQLContainer<?> pg =
      new PostgreSQLContainer<>("postgres:16-alpine").withUrlParam("sslmode", "disable");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  /** 与 V170 一致：AES-256 主密钥。生产由 secret manager 注入，测试用固定值。 */
  static final String MASTER_KEY =
      Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes());

  static final List<String> allPermissions =
      java.util.stream.Stream.of("registry", "connection", "shop", "sync", "operation")
          .flatMap(
              p ->
                  java.util.stream.Stream.concat(
                      java.util.stream.Stream.of("read", "create", "update", "delete")
                          .map(op -> "channel." + p + "." + op),
                      p.equals("sync")
                          ? java.util.stream.Stream.of(
                              "channel.sync.trigger", "channel.sync.complete")
                          : java.util.stream.Stream.empty()))
          .distinct()
          .toList();

  static AccessService.Access actor(UUID uid, UUID tenant, List<AccessService.Grant> grants) {
    return new AccessService.Access(
        uid,
        tenant,
        "Test",
        UUID.randomUUID(),
        null,
        grants.stream().flatMap(g -> g.permissions().stream()).distinct().toList(),
        grants);
  }

  static AccessService.Grant grant(String scope, List<String> permissions) {
    return new AccessService.Grant(UUID.randomUUID(), "TEST", scope, permissions);
  }

  static {
    pg.start();
    try {
      try (var c =
              DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
          var s = c.createStatement()) {
        s.execute(
            "do $$ begin if not exists(select 1 from pg_roles where rolname='starcon_app')"
                + " then create role starcon_app nologin; end if; end $$");
        s.execute(
            "do $$ begin if not exists(select 1 from pg_roles where rolname='channel_app')"
                + " then create role channel_app login password 'test-only' nosuperuser nobypassrls; end if; end $$");
        s.execute("alter role channel_app login password 'test-only' nosuperuser nobypassrls");
      }
      identity = HttpServer.create(new InetSocketAddress(0), 0);
      identity.createContext(
          "/internal/access",
          e -> {
            AccessService.Access who = actors.get(e.getRequestHeaders().getFirst("Authorization"));
            if (who == null) {
              e.sendResponseHeaders(401, -1);
              e.close();
              return;
            }
            byte[] body = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(who);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      List<AccessService.Grant> grants = List.of(grant("ALL", allPermissions));
      actors.put("Bearer tenant-a", actor(user, a, grants));
      actors.put("Bearer tenant-b", actor(user, b, grants));
      identity.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "channel_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://localhost:" + identity.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
    r.add("CHANNEL_WEBHOOK_SIGNING_KEY", () -> MASTER_KEY);
    // 池大小为 1：连接复用最容易串租户，单连接下这条断言最严格
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  @Autowired ChannelService channels;
  @Autowired WebhookService webhooks;
  @Autowired AccessService access;
  @Autowired MockMvc http;
  @Autowired DSLContext sql;

  AccessService.Access actorA, actorB;

  @BeforeEach
  void setup() {
    actorA = actors.get("Bearer tenant-a");
    actorB = actors.get("Bearer tenant-b");
  }

  @AfterAll
  static void stop() {
    identity.stop(0);
    pg.stop();
  }

  <T> T as(AccessService.Access who, String permission, java.util.function.Supplier<T> work) {
    return access.run(who, permission, work);
  }

  /** 登记入口后的三件套：路径片段用来打回调，密钥用来签名。 */
  record Endpoint(UUID id, String key, String secret, SignatureEncoding encoding) {}

  Endpoint endpoint(AccessService.Access who, SignatureEncoding encoding) {
    String secret = "wh-secret-" + UUID.randomUUID();
    UUID id =
        as(
            who,
            "channel.sync.create",
            () -> {
              UUID conn =
                  channels.createConnection(
                      who,
                      new ChannelModels.ConnectionInput(
                          "AMAZON", "wh-" + UUID.randomUUID() + "@example.com", ""));
              return webhooks.registerEndpoint(
                  who,
                  new WebhookEndpointInput(
                      conn, null, "X-Starcon-Signature", encoding, secret));
            });
    String key =
        as(
            who,
            "channel.sync.read",
            () ->
                webhooks.endpoints(who.tenantId()).stream()
                    .filter(e -> e.id().equals(id))
                    .findFirst()
                    .orElseThrow()
                    .endpointKey());
    return new Endpoint(id, key, secret, encoding);
  }

  UUID shop(AccessService.Access who) {
    return as(
        who,
        "channel.shop.update",
        () ->
            channels.createShop(
                who,
                new ChannelModels.ShopInput(
                    "WHS-" + UUID.randomUUID().toString().substring(0, 8),
                    "Webhook shop",
                    "AMAZON",
                    null,
                    null,
                    "SELF_FULFILLED",
                    0L)));
  }

  /** 用真实密钥算签名——与被测代码同一算法，但密钥由测试自己持有。 */
  static String sign(String secret, byte[] body, SignatureEncoding encoding) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(), "HmacSHA256"));
      byte[] raw = mac.doFinal(body);
      return encoding == SignatureEncoding.BASE64
          ? Base64.getEncoder().encodeToString(raw)
          : HexFormat.of().formatHex(raw);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  static byte[] payload(String eventId) {
    return (("{\"event_id\":\"" + eventId + "\",\"event_type\":\"ORDER_CREATED\","
            + "\"topic\":\"orders\",\"access_token\":\"plaintext-token-must-not-persist\"}")
        .getBytes());
  }

  WebhookService.IngestResult deliver(Endpoint ep, String eventId) {
    byte[] body = payload(eventId);
    return webhooks.receive(ep.key(), sign(ep.secret(), body, ep.encoding()), body);
  }

  List<WebhookReceiptView> mine(AccessService.Access who) {
    return as(who, "channel.sync.read", () -> webhooks.receipts(who.tenantId(), null, null, 200));
  }

  WebhookReceiptView receiptOf(AccessService.Access who, UUID id) {
    return mine(who).stream().filter(r -> r.id().equals(id)).findFirst().orElseThrow();
  }

  /**
   * 直查库里的原始报文，用来断言脱敏真的发生在存储层而不是只在视图层。
   *
   * <p>必须走 {@link #as} 而不是裸 SQL：webhook_receipt 的 RESTRICTIVE 数据范围策略
   * 依赖 app.permission 与 app.grants，裸查会被策略挡掉并返回空串——
   * 那样的断言等于什么都没测。
   */
  String rawPayload(AccessService.Access who, UUID receiptId) {
    return as(
        who,
        "channel.sync.read",
        () -> {
          var row =
              sql.fetchOne(
                  "select payload::text from webhook_receipt where tenant_id=? and id=?",
                  who.tenantId(),
                  receiptId);
          return row == null ? "" : row.get(0, String.class);
        });
  }

  // ------------------------------------------------------------------
  // 02.6.1.1 记录五要素
  // ------------------------------------------------------------------

  @Test
  void receiptRecordsSignatureEventIdShopReceiveTimeAndStatus() {
    Endpoint ep = endpoint(actorA, SignatureEncoding.HEX);
    String eventId = "EVT-" + UUID.randomUUID();
    WebhookService.IngestResult result = deliver(ep, eventId);

    assertThat(result.status()).isEqualTo("RECEIVED");
    assertThat(result.signatureStatus()).isEqualTo("VERIFIED");

    WebhookReceiptView receipt =
        mine(actorA).stream()
            .filter(r -> r.externalEventId().equals(eventId))
            .findFirst()
            .orElseThrow();
    // 蓝图点名的五要素：验签结论、外部事件 ID、来源店铺、接收时间、处理状态
    assertThat(receipt.signatureStatus()).isEqualTo("VERIFIED");
    assertThat(receipt.externalEventId()).isEqualTo(eventId);
    assertThat(receipt.status()).isEqualTo("RECEIVED");
    assertThat(receipt.eventType()).isEqualTo("ORDER_CREATED");
    assertThat(receipt.payloadDigest()).hasSize(64);

    // 接收时间必须断言**写回的值**：timestamptz 绑错会静默偏 8 小时，
    // 只断言"没抛异常"抓不到这类偏��
    assertThat(receipt.receivedAt()).isNotNull();
    assertThat(receipt.receivedAt())
        .isBetween(
            Instant.now().minus(5, ChronoUnit.MINUTES), Instant.now().plus(1, ChronoUnit.MINUTES));
  }

  @Test
  void receiptStampsTheSourceShopWhenTheCallbackCarriesOne() {
    Endpoint ep = endpoint(actorA, SignatureEncoding.HEX);
    UUID sh = shop(actorA);
    String eventId = "EVT-" + UUID.randomUUID();
    WebhookService.IngestResult result = deliver(ep, eventId);

    as(
        actorA,
        "channel.sync.update",
        () -> {
          webhooks.claim(actorA, result.receiptId(), new ReceiptClaimInput(sh));
          return null;
        });

    WebhookReceiptView receipt = receiptOf(actorA, result.receiptId());
    assertThat(receipt.shopId()).isEqualTo(sh);
    assertThat(receipt.shopName()).isEqualTo("Webhook shop");
  }

  @Test
  void receiptRedactsCredentialFieldsBeforeTheyReachStorage() {
    Endpoint ep = endpoint(actorA, SignatureEncoding.HEX);
    String eventId = "EVT-" + UUID.randomUUID();
    WebhookService.IngestResult result = deliver(ep, eventId);

    String stored = rawPayload(actorA, result.receiptId());
    // AGENTS.md：明文凭据不入库。库里必须是脱敏后的值
    assertThat(stored).doesNotContain("plaintext-token-must-not-persist");
    assertThat(stored).contains("***");
    assertThat(stored).contains(eventId);
  }

  // ------------------------------------------------------------------
  // 验签必须是真的
  // ------------------------------------------------------------------

  @Test
  void aSignatureFromTheWrongKeyIsRejectedAndStillRecorded() {
    Endpoint ep = endpoint(actorA, SignatureEncoding.HEX);
    String eventId = "EVT-" + UUID.randomUUID();
    byte[] body = payload(eventId);
    // 同一算法、不同密钥：必须判失败
    WebhookService.IngestResult result =
        webhooks.receive(
            ep.key(), sign("some-other-secret", body, SignatureEncoding.HEX), body);

    assertThat(result.signatureStatus()).isEqualTo("FAILED");
    assertThat(result.status()).isEqualTo("SIGNATURE_FAILED");
    assertThat(result.processable()).isFalse();
    assertThat(
            mine(actorA).stream()
                .filter(r -> r.externalEventId().equals(eventId))
                .findFirst()
                .orElseThrow()
                .signatureStatus())
        .isEqualTo("FAILED");
  }

  @Test
  void aTamperedBodyIsRejectedBecauseTheSignatureCoversTheBytes() {
    Endpoint ep = endpoint(actorA, SignatureEncoding.HEX);
    byte[] signed = payload("EVT-" + UUID.randomUUID());
    String signature = sign(ep.secret(), signed, SignatureEncoding.HEX);
    // 签名之后换掉报文内容：验签必须失败
    byte[] tampered = payload("EVT-" + UUID.randomUUID());

    WebhookService.IngestResult result = webhooks.receive(ep.key(), signature, tampered);
    assertThat(result.signatureStatus()).isEqualTo("FAILED");
  }

  @Test
  void aMissingSignatureIsRecordedAsMissingRatherThanPassed() {
    Endpoint ep = endpoint(actorA, SignatureEncoding.HEX);
    String eventId = "EVT-" + UUID.randomUUID();
    WebhookService.IngestResult result = webhooks.receive(ep.key(), null, payload(eventId));
    // 没有签名绝不能当作通过：这是"假实现验签"最常见的形态
    assertThat(result.signatureStatus()).isEqualTo("MISSING");
    assertThat(result.status()).isEqualTo("SIGNATURE_FAILED");
  }

  @Test
  void aGarbageSignatureHeaderIsRejectedRatherThanCrashing() {
    Endpoint ep = endpoint(actorA, SignatureEncoding.HEX);
    WebhookService.IngestResult result =
        webhooks.receive(ep.key(), "not-even-hex-@@@", payload("EVT-" + UUID.randomUUID()));
    assertThat(result.signatureStatus()).isEqualTo("FAILED");
  }

  @Test
  void base64AndHexEncodingsAreBothVerifiedForReal() {
    for (SignatureEncoding encoding : List.of(SignatureEncoding.HEX, SignatureEncoding.BASE64)) {
      Endpoint ep = endpoint(actorA, encoding);
      WebhookService.IngestResult result = deliver(ep, "EVT-" + UUID.randomUUID());
      assertThat(result.signatureStatus()).as("encoding %s", encoding).isEqualTo("VERIFIED");
    }
  }

  // ------------------------------------------------------------------
  // 重复推送
  // ------------------------------------------------------------------

  @Test
  void aRepeatedWebhookProducesNoSecondProcessableRecord() {
    Endpoint ep = endpoint(actorA, SignatureEncoding.HEX);
    String eventId = "EVT-" + UUID.randomUUID();

    WebhookService.IngestResult first = deliver(ep, eventId);
    WebhookService.IngestResult again = deliver(ep, eventId);

    assertThat(first.status()).isEqualTo("RECEIVED");
    assertThat(first.processable()).isTrue();
    assertThat(again.status()).isEqualTo("DUPLICATE");
    assertThat(again.processable()).isFalse();

    List<WebhookReceiptView> all =
        mine(actorA).stream().filter(r -> r.externalEventId().equals(eventId)).toList();
    // 两条记录（首次 + 重复），但**只有一条可被认领处理**
    assertThat(all).hasSize(2);
    assertThat(all.stream().filter(r -> "RECEIVED".equals(r.status()))).hasSize(1);
    WebhookReceiptView duplicate =
        all.stream().filter(r -> "DUPLICATE".equals(r.status())).findFirst().orElseThrow();
    // 重复记录指回首次记录：下游 2.6.1.2 才能统计重推次数
    assertThat(duplicate.duplicateOf()).isEqualTo(first.receiptId());
  }

  @Test
  void aDuplicateIsRejectedAtTheDatabaseLevelUnderConcurrentDelivery() throws Exception {
    Endpoint ep = endpoint(actorA, SignatureEncoding.HEX);
    String eventId = "EVT-" + UUID.randomUUID();
    byte[] body = payload(eventId);
    String signature = sign(ep.secret(), body, SignatureEncoding.HEX);

    var pool = java.util.concurrent.Executors.newFixedThreadPool(4);
    try {
      var futures = new ArrayList<java.util.concurrent.Future<WebhookService.IngestResult>>();
      for (int i = 0; i < 4; i++)
        futures.add(pool.submit(() -> webhooks.receive(ep.key(), signature, body)));
      int firstCount = 0;
      for (var f : futures) if (f.get().status().equals("RECEIVED")) firstCount++;
      // 部分唯一索引是唯一裁判：并发投递也只能有一条首次记录
      assertThat(firstCount).isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }
    assertThat(
            mine(actorA).stream()
                .filter(r -> r.externalEventId().equals(eventId))
                .filter(r -> r.duplicateOf() == null))
        .hasSize(1);
  }

  // ------------------------------------------------------------------
  // 先持久化再处理
  // ------------------------------------------------------------------

  @Test
  void aVerifiedFirstDeliveryIsClaimedThenSettled() {
    Endpoint ep = endpoint(actorA, SignatureEncoding.HEX);
    WebhookService.IngestResult result = deliver(ep, "EVT-" + UUID.randomUUID());

    as(
        actorA,
        "channel.sync.update",
        () -> {
          webhooks.claim(actorA, result.receiptId(), new ReceiptClaimInput(null));
          return null;
        });
    WebhookReceiptView claimed = receiptOf(actorA, result.receiptId());
    assertThat(claimed.status()).isEqualTo("PROCESSING");
    assertThat(claimed.processingStartedAt()).isNotNull();

    as(
        actorA,
        "channel.sync.update",
        () -> {
          webhooks.complete(
              actorA, result.receiptId(), new ReceiptCompleteInput(ReceiptStatus.PROCESSED, ""));
          return null;
        });
    WebhookReceiptView settled = receiptOf(actorA, result.receiptId());
    assertThat(settled.status()).isEqualTo("PROCESSED");
    assertThat(settled.processedAt()).isNotNull();
  }

  @Test
  void aSecondClaimOfTheSameReceiptIsRefused() {
    Endpoint ep = endpoint(actorA, SignatureEncoding.HEX);
    WebhookService.IngestResult result = deliver(ep, "EVT-" + UUID.randomUUID());
    as(
        actorA,
        "channel.sync.update",
        () -> {
          webhooks.claim(actorA, result.receiptId(), new ReceiptClaimInput(null));
          return null;
        });
    // 认领是幂等防线：第二个人再来抢必须失败，否则同一个事件会被处理两次
    assertThatThrownBy(
            () ->
                as(
                    actorA,
                    "channel.sync.update",
                    () -> {
                      webhooks.claim(actorA, result.receiptId(), new ReceiptClaimInput(null));
                      return null;
                    }))
        .hasMessageContaining("不可认领");
  }

  @Test
  void signatureFailedReceiptsCanNeverBeClaimed() {
    Endpoint ep = endpoint(actorA, SignatureEncoding.HEX);
    byte[] body = payload("EVT-" + UUID.randomUUID());
    WebhookService.IngestResult result =
        webhooks.receive(
            ep.key(), sign("not-the-secret", body, SignatureEncoding.HEX), body);
    assertThat(result.signatureStatus()).isEqualTo("FAILED");

    assertThatThrownBy(
            () ->
                as(
                    actorA,
                    "channel.sync.update",
                    () -> {
                      webhooks.claim(actorA, result.receiptId(), new ReceiptClaimInput(null));
                      return null;
                    }))
        .hasMessageContaining("不可认领");
  }

  @Test
  void aDuplicateIsNeverClaimable() {
    Endpoint ep = endpoint(actorA, SignatureEncoding.HEX);
    String eventId = "EVT-" + UUID.randomUUID();
    deliver(ep, eventId);
    WebhookService.IngestResult again = deliver(ep, eventId);
    assertThat(again.status()).isEqualTo("DUPLICATE");

    assertThatThrownBy(
            () ->
                as(
                    actorA,
                    "channel.sync.update",
                    () -> {
                      webhooks.claim(actorA, again.receiptId(), new ReceiptClaimInput(null));
                      return null;
                    }))
        .hasMessageContaining("不可认领");
  }

  @Test
  void aSettledReceiptCannotBeRewritten() {
    Endpoint ep = endpoint(actorA, SignatureEncoding.HEX);
    WebhookService.IngestResult result = deliver(ep, "EVT-" + UUID.randomUUID());
    as(
        actorA,
        "channel.sync.update",
        () -> {
          webhooks.claim(actorA, result.receiptId(), new ReceiptClaimInput(null));
          webhooks.complete(
              actorA, result.receiptId(), new ReceiptCompleteInput(ReceiptStatus.PROCESSED, ""));
          return null;
        });
    // 可逆性靠补新记录冲销，不靠改历史
    assertThatThrownBy(
            () ->
                as(
                    actorA,
                    "channel.sync.update",
                    () -> {
                      webhooks.complete(
                          actorA,
                          result.receiptId(),
                          new ReceiptCompleteInput(ReceiptStatus.FAILED, "改一下"));
                      return null;
                    }))
        .hasMessageContaining("不在处理中");
  }

  @Test
  void completionRejectsStatusesOtherThanProcessedOrFailed() {
    Endpoint ep = endpoint(actorA, SignatureEncoding.HEX);
    WebhookService.IngestResult result = deliver(ep, "EVT-" + UUID.randomUUID());
    as(
        actorA,
        "channel.sync.update",
        () -> {
          webhooks.claim(actorA, result.receiptId(), new ReceiptClaimInput(null));
          return null;
        });
    assertThatThrownBy(
            () ->
                as(
                    actorA,
                    "channel.sync.update",
                    () -> {
                      webhooks.complete(
                          actorA,
                          result.receiptId(),
                          new ReceiptCompleteInput(ReceiptStatus.RECEIVED, ""));
                      return null;
                    }))
        .hasMessageContaining("只能是 PROCESSED 或 FAILED");
  }

  @Test
  void summarySurfacesSignatureFailuresSoTheyAreNotBuried() {
    Endpoint ep = endpoint(actorA, SignatureEncoding.HEX);
    byte[] body = payload("EVT-" + UUID.randomUUID());
    webhooks.receive(ep.key(), sign("wrong", body, SignatureEncoding.HEX), body);

    // 验签失败是安全事件，必须能被一眼看见而不是埋在长列表里
    assertThat(
            as(actorA, "channel.sync.read", () -> webhooks.summary(actorA.tenantId())).stream()
                .filter(s -> s.status().equals("SIGNATURE_FAILED")))
        .isNotEmpty();
  }

  @Test
  void receiptsCanBeFilteredByStatus() {
    Endpoint ep = endpoint(actorA, SignatureEncoding.HEX);
    String eventId = "EVT-" + UUID.randomUUID();
    deliver(ep, eventId);
    deliver(ep, eventId);

    List<WebhookReceiptView> duplicates =
        as(
            actorA,
            "channel.sync.read",
            () -> webhooks.receipts(actorA.tenantId(), "DUPLICATE", null, 50));
    assertThat(duplicates.stream().filter(r -> r.externalEventId().equals(eventId))).hasSize(1);
  }

  // ------------------------------------------------------------------
  // 多租户三条断言
  // ------------------------------------------------------------------

  @Test
  void tenantsCannotSeeEachOthersReceipts() {
    Endpoint ep = endpoint(actorA, SignatureEncoding.HEX);
    String eventA = "EVT-A-" + UUID.randomUUID();
    deliver(ep, eventA);

    // 租户 B 的工作台看不到租户 A 的任何一条接收记录
    assertThat(mine(actorB)).noneMatch(r -> r.externalEventId().equals(eventA));
    assertThat(as(actorB, "channel.sync.read", () -> webhooks.endpoints(actorB.tenantId())))
        .noneMatch(e -> e.id().equals(ep.id()));
  }

  @Test
  void aForgedTenantHeaderCannotRedirectACallbackIntoAnotherTenant() throws Exception {
    Endpoint ep = endpoint(actorA, SignatureEncoding.HEX);
    String eventA = "EVT-A-" + UUID.randomUUID();
    byte[] body = payload(eventA);

    // 平台回调端点不接受任何租户头：带伪造 X-Tenant-Id 也只能落到入口自己的租户
    http.perform(
            post("/channel/webhooks/" + ep.key())
                .header("X-Starcon-Signature", sign(ep.secret(), body, SignatureEncoding.HEX))
                .header("X-Tenant-Id", b.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk());

    assertThat(mine(actorB)).noneMatch(r -> r.externalEventId().equals(eventA));
    assertThat(mine(actorA).stream().filter(r -> r.externalEventId().equals(eventA)))
        .isNotEmpty();
  }

  @Test
  void theConnectionPoolDoesNotLeakTheTenantAfterBeingReturned() {
    Endpoint epA = endpoint(actorA, SignatureEncoding.HEX);
    String eventA = "EVT-A-" + UUID.randomUUID();
    deliver(epA, eventA);

    // 池大小为 1：连续两次取连接必然复用同一条。B 的请求紧跟在 A 之后，
    // 若 RLS 会话变量没随事务归还，B 就会看到 A 的数据
    Endpoint epB = endpoint(actorB, SignatureEncoding.HEX);
    String eventB = "EVT-B-" + UUID.randomUUID();
    deliver(epB, eventB);

    assertThat(mine(actorA).stream().filter(r -> r.externalEventId().equals(eventB))).isEmpty();
    assertThat(mine(actorB).stream().filter(r -> r.externalEventId().equals(eventA))).isEmpty();
    assertThat(mine(actorA).stream().filter(r -> r.externalEventId().equals(eventA)))
        .isNotEmpty();
    assertThat(mine(actorB).stream().filter(r -> r.externalEventId().equals(eventB)))
        .isNotEmpty();
  }

  @Test
  void aTenantCannotClaimOrSettleAnotherTenantsReceipt() {
    Endpoint epA = endpoint(actorA, SignatureEncoding.HEX);
    WebhookService.IngestResult result = deliver(epA, "EVT-" + UUID.randomUUID());

    // 用 B 的身份去改 A 的记录：RLS 让更新落到 0 行，对外表现为"不存在"
    assertThatThrownBy(
            () ->
                as(
                    actorB,
                    "channel.sync.update",
                    () -> {
                      webhooks.claim(actorB, result.receiptId(), new ReceiptClaimInput(null));
                      return null;
                    }))
        .hasMessageContaining("不可认领");
    assertThat(receiptOf(actorA, result.receiptId()).status()).isEqualTo("RECEIVED");
  }

  // ------------------------------------------------------------------
  // HTTP 形状
  // ------------------------------------------------------------------

  @Test
  void theHttpEndpointReturns200ForVerifiedAnd401ForBadSignature() throws Exception {
    Endpoint ep = endpoint(actorA, SignatureEncoding.HEX);
    byte[] body = payload("EVT-" + UUID.randomUUID());

    http.perform(
            post("/channel/webhooks/" + ep.key())
                .header("X-Starcon-Signature", sign(ep.secret(), body, SignatureEncoding.HEX))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("RECEIVED"))
        .andExpect(jsonPath("$.processable").value(true));

    byte[] other = payload("EVT-" + UUID.randomUUID());
    http.perform(
            post("/channel/webhooks/" + ep.key())
                .header("X-Starcon-Signature", sign("nope", other, SignatureEncoding.HEX))
                .contentType(MediaType.APPLICATION_JSON)
                .content(other))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.signatureStatus").value("FAILED"));
  }

  @Test
  void anUnknownEndpointKeyIs404() throws Exception {
    http.perform(
            post("/channel/webhooks/wh_does_not_exist_at_all")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isNotFound());
  }

  @Test
  void aDisabledEndpointStopsAcceptingDeliveries() {
    Endpoint ep = endpoint(actorA, SignatureEncoding.HEX);
    as(
        actorA,
        "channel.sync.update",
        () -> {
          webhooks.disableEndpoint(actorA, ep.id());
          return null;
        });
    byte[] body = payload("EVT-" + UUID.randomUUID());
    // 停用是止血动作：平台还在推也不该再受理
    assertThatThrownBy(
            () -> webhooks.receive(ep.key(), sign(ep.secret(), body, SignatureEncoding.HEX), body))
        .hasMessageContaining("回调入口不存在");
  }

  @Test
  void registeringTheSameConnectionTwiceIsRejectedSoOneKeyIsAuthoritative() {
    UUID conn =
        as(
            actorA,
            "channel.connection.update",
            () ->
                channels.createConnection(
                    actorA,
                    new ChannelModels.ConnectionInput(
                        "SHOPIFY", "dup-" + UUID.randomUUID() + "@example.com", "")));
    as(
        actorA,
        "channel.sync.create",
        () ->
            webhooks.registerEndpoint(
                actorA,
                new WebhookEndpointInput(
                    conn, null, "X-Starcon-Signature", SignatureEncoding.HEX, "s1")));
    // 一个连接只允许一个入口：两份密钥会让排查时无从判断哪份生效
    assertThatThrownBy(
            () ->
                as(
                    actorA,
                    "channel.sync.create",
                    () ->
                        webhooks.registerEndpoint(
                            actorA,
                            new WebhookEndpointInput(
                                conn, null, "X-Starcon-Signature", SignatureEncoding.HEX, "s2"))))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
  }

  @Test
  void aRejectedEndpointKeyIsRejectedBeforeTouchingTheDatabase() {
    UUID conn =
        as(
            actorA,
            "channel.connection.update",
            () ->
                channels.createConnection(
                    actorA,
                    new ChannelModels.ConnectionInput(
                        "EBAY", "key-" + UUID.randomUUID() + "@example.com", "")));
    // 路径穿越式片段绝不能进库：回调 URL 是公开的
    assertThatThrownBy(
            () ->
                as(
                    actorA,
                    "channel.sync.create",
                    () ->
                        webhooks.registerEndpoint(
                            actorA,
                            new WebhookEndpointInput(
                                conn,
                                "../../secrets",
                                "X-Starcon-Signature",
                                SignatureEncoding.HEX,
                                "s"))))
        .hasMessageContaining("回调路径片段");
  }
}
