package com.starcon.channel;

import static com.starcon.channel.WebhookDedupModels.*;
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
 * 02.6.1.2 去重视图的行为约束。
 *
 * <p>蓝图 §2.6.1.2 的四行逐条钉住：
 *
 * <ol>
 *   <li>以 event_id 为准识别重复推送（库里的真实列名是 {@code external_event_id}）
 *   <li>展示同一业务事件被平台重推的次数
 *   <li>展示处理结果，并**引用首次处理结果**
 *   <li>**重复事件不产生第二条业务记录** —— 本模块存在的唯一理由
 *   <li>多租户：两租户互不可见、伪造 tenant header 无效、连接池归还后不串租户
 * </ol>
 *
 * <p>签名用**真实密钥对**构造：{@link #sign} 与被测代码走同一个 HMAC-SHA256 算法，
 * 但密钥由测试自己持有，因此"算错了"和"密钥不对"是两件可区分的事。
 * 去重的触发完全依赖它——只有验签通过的投递才会去抢首次接收权。
 */
@SpringBootTest
@AutoConfigureMockMvc
class WebhookDedupIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
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
  @Autowired WebhookDedupService dedup;
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

  /**
   * 一个本测试类专属的干净租户。
   *
   * <p>Testcontainers 的库在整个类里是**共享**的：租户 A 的数据会被同类的其它用例不断追加。
   * 凡是断言聚合口径（总事件数、重复投递总条数、重推最多的事件）的用例都必须用干净租户，
   * 否则它的期望值取决于 JUnit 的用例执行顺序——那不是测试，那是定时炸弹。
   */
  AccessService.Access freshTenant() {
    AccessService.Access who = actor(user, UUID.randomUUID(), List.of(grant("ALL", allPermissions)));
    actors.put("Bearer fresh-" + who.tenantId(), who);
    return who;
  }

  /** 登记入口后的三件套：路径片段用来打回调，密钥用来签名。 */
  record Endpoint(UUID id, UUID connectionId, String key, String secret) {}

  Endpoint endpoint(AccessService.Access who) {
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
                      conn, null, "X-Starcon-Signature", SignatureEncoding.HEX, secret));
            });
    var registered =
        as(
            who,
            "channel.sync.read",
            () ->
                webhooks.endpoints(who.tenantId()).stream()
                    .filter(e -> e.id().equals(id))
                    .findFirst()
                    .orElseThrow());
    return new Endpoint(id, registered.connectionId(), registered.endpointKey(), secret);
  }

  static String sign(String secret, byte[] body) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(), "HmacSHA256"));
      return HexFormat.of().formatHex(mac.doFinal(body));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  static byte[] payload(String eventId) {
    return (("{\"event_id\":\"" + eventId + "\",\"event_type\":\"ORDER_CREATED\","
            + "\"topic\":\"orders\"}")
        .getBytes());
  }

  /** 一次合法投递（验签通过）。去重只有在验签通过时才会发生，所以签名必须对。 */
  WebhookService.IngestResult deliver(Endpoint ep, String eventId) {
    byte[] body = payload(eventId);
    return webhooks.receive(ep.key(), sign(ep.secret(), body), body);
  }

  List<WebhookDedupEventView> view(
      AccessService.Access who, String eventId, UUID connectionId, String firstStatus,
      boolean duplicatedOnly) {
    return as(
        who,
        "channel.sync.read",
        () -> dedup.events(who, eventId, connectionId, firstStatus, duplicatedOnly, 200));
  }

  List<WebhookDedupEventView> view(AccessService.Access who) {
    return view(who, null, null, null, false);
  }

  WebhookDedupEventView groupOf(AccessService.Access who, Endpoint ep, String eventId) {
    return view(who, null, null, null, false).stream()
        .filter(
            e ->
                e.externalEventId().equals(eventId)
                    && e.endpointId().equals(ep.id()))
        .findFirst()
        .orElseThrow();
  }

  /**
   * 直查库里某个业务事件处于"可处理链路"上的接收记录数。
   *
   * <p>必须走 {@link #as} 而不是裸 SQL：webhook_receipt 的 RESTRICTIVE 数据范围策略
   * 依赖 app.permission 与 app.grants，裸查会被策略挡掉并返回 0——
   * 那样的断言等于什么都没测。
   */
  int settledCount(AccessService.Access who, Endpoint ep, String eventId, String status) {
    return as(
        who,
        "channel.sync.read",
        () ->
            (Integer)
                sql.fetchValue(
                    "select count(*)::int from webhook_receipt where tenant_id=? and endpoint_id=?"
                        + " and external_event_id=? and status=?",
                    who.tenantId(),
                    ep.id(),
                    eventId,
                    status));
  }

  /** 该业务事件下 {@code duplicate_of IS NULL} 的记录数——首次接收权的物理保证。 */
  int firstDeliveryCount(AccessService.Access who, Endpoint ep, String eventId) {
    return as(
        who,
        "channel.sync.read",
        () ->
            (Integer)
                sql.fetchValue(
                    "select count(*)::int from webhook_receipt where tenant_id=? and endpoint_id=?"
                        + " and external_event_id=? and duplicate_of is null",
                    who.tenantId(),
                    ep.id(),
                    eventId));
  }

  // ==================================================================
  // 蓝图铁律：重复事件不产生第二条业务记录
  // ==================================================================

  @Test
  void pushingTheSameEventTwiceLeavesExactlyOneBusinessRecord() {
    Endpoint ep = endpoint(actorA);
    String eventId = "EVT-" + UUID.randomUUID();

    WebhookService.IngestResult first = deliver(ep, eventId);
    WebhookService.IngestResult again = deliver(ep, eventId);
    assertThat(first.status()).isEqualTo("RECEIVED");
    assertThat(first.processable()).isTrue();
    assertThat(again.status()).isEqualTo("DUPLICATE");
    assertThat(again.processable()).isFalse();

    // 下游按**业务事件**（而不是按接收记录）消费。重复的投递连同它的 id 都拿不到。
    WebhookDedupClaimResult claimed =
        as(
            actorA,
            "channel.sync.update",
            () -> dedup.claim(actorA, first.receiptId(), new WebhookDedupClaimInput(null)));
    // 认领结果里的 receiptId 恒等于首次那一条：重复行没有任何出口
    assertThat(claimed.receiptId()).isEqualTo(first.receiptId());
    assertThat(claimed.status()).isEqualTo("PROCESSING");

    as(
        actorA,
        "channel.sync.update",
        () -> {
          webhooks.complete(
              actorA, claimed.receiptId(), new ReceiptCompleteInput(ReceiptStatus.PROCESSED, ""));
          return null;
        });

    // 唯一判据：推了两次，业务侧只产生了一条已处理的记录。
    assertThat(settledCount(actorA, ep, eventId, "PROCESSED")).isEqualTo(1);
    // 整条可处理链路上也只有它自己：重复行永远停在 DUPLICATE
    assertThat(firstDeliveryCount(actorA, ep, eventId)).isEqualTo(1);
  }

  @Test
  void aDownstreamRetryCannotClaimTheSameEventTwice() {
    Endpoint ep = endpoint(actorA);
    String eventId = "EVT-" + UUID.randomUUID();
    WebhookService.IngestResult first = deliver(ep, eventId);
    deliver(ep, eventId);

    as(
        actorA,
        "channel.sync.update",
        () -> dedup.claim(actorA, first.receiptId(), new WebhookDedupClaimInput(null)));
    // 第二次认领必须被拒：如果它能过，"只处理一次"就退化成一条靠自觉的约定
    assertThatThrownBy(
            () ->
                as(
                    actorA,
                    "channel.sync.update",
                    () -> dedup.claim(actorA, first.receiptId(), new WebhookDedupClaimInput(null))))
        .hasMessageContaining("不会产生第二条业务记录");
    assertThat(settledCount(actorA, ep, eventId, "PROCESSED")).isEqualTo(0);
    assertThat(settledCount(actorA, ep, eventId, "PROCESSING")).isEqualTo(1);
  }

  @Test
  void aDuplicateReceiptCanNeverBeClaimedAsTheEvent() {
    Endpoint ep = endpoint(actorA);
    String eventId = "EVT-" + UUID.randomUUID();
    deliver(ep, eventId);
    WebhookService.IngestResult again = deliver(ep, eventId);

    // 拿重复行的 id 来认领：它不是任何一组的首次记录，必须 404 而不是悄悄放行
    assertThatThrownBy(
            () ->
                as(
                    actorA,
                    "channel.sync.update",
                    () -> dedup.claim(actorA, again.receiptId(), new WebhookDedupClaimInput(null))))
        .hasMessageContaining("该外部事件没有接收记录");
  }

  @Test
  void anUnverifiedFirstDeliveryIsNotClaimable() {
    Endpoint ep = endpoint(actorA);
    String eventId = "EVT-" + UUID.randomUUID();
    byte[] body = payload(eventId);
    WebhookService.IngestResult bad = webhooks.receive(ep.key(), sign("wrong", body), body);
    assertThat(bad.status()).isEqualTo("SIGNATURE_FAILED");

    WebhookDedupEventView group = groupOf(actorA, ep, eventId);
    // 去重视图必须如实反映"这组事件没有可信来源"，而不是把它算成一个待处理事件
    assertThat(group.firstStatus()).isEqualTo("SIGNATURE_FAILED");
    assertThat(group.signatureStatus()).isNotEqualTo("VERIFIED");
    assertThat(group.processable()).isFalse();
    assertThatThrownBy(
            () ->
                as(
                    actorA,
                    "channel.sync.update",
                    () -> dedup.claim(actorA, group.firstReceiptId(), new WebhookDedupClaimInput(null))))
        .hasMessageContaining("不会产生第二条业务记录");
  }

  // ==================================================================
  // 蓝图职责：展示重推次数
  // ==================================================================

  @Test
  void theViewCountsHowManyTimesThePlatformRedeliveredTheEvent() {
    Endpoint ep = endpoint(actorA);
    String eventId = "EVT-" + UUID.randomUUID();

    deliver(ep, eventId);
    deliver(ep, eventId);
    deliver(ep, eventId);

    WebhookDedupEventView group = groupOf(actorA, ep, eventId);
    // 三次投递 → 归并成一行，重推次数是 2 而不是 3：首次不是重推
    assertThat(group.deliveryCount()).isEqualTo(3);
    assertThat(group.duplicateCount()).isEqualTo(2);
    assertThat(group.lastDeliveryAttempt()).isEqualTo(3);
    // 去重视图一行 = 一个业务事件；三次投递不能显示成三行
    assertThat(view(actorA).stream().filter(e -> e.externalEventId().equals(eventId))).hasSize(1);
  }

  @Test
  void aSingleDeliveryEventReportsZeroRedeliveries() {
    AccessService.Access solo = freshTenant();
    Endpoint ep = endpoint(solo);
    deliver(ep, "EVT-" + UUID.randomUUID());
    deliver(ep, "EVT-" + UUID.randomUUID());

    WebhookDedupEventView group =
        as(solo, "channel.sync.read", () -> dedup.events(solo, null, null, null, false, 200)).get(0);
    // 没被重推的事件必须报 0 而不是 1：把首次算成重推会让"重推次数"整体虚高
    assertThat(group.deliveryCount()).isEqualTo(1);
    assertThat(group.duplicateCount()).isZero();
    assertThat(group.lastDeliveryAttempt()).isEqualTo(1);
    // 而且它们不该出现在"只看重推过的事件"里
    assertThat(view(solo, null, null, null, true)).isEmpty();
    WebhookDedupSummary summary = as(solo, "channel.sync.read", () -> dedup.summary(solo));
    assertThat(summary.duplicatedEvents()).isZero();
    assertThat(summary.duplicateDeliveries()).isZero();
    assertThat(summary.singleDeliveryEvents()).isEqualTo(2);
    assertThat(summary.oldestDuplicateAt()).isNull();
  }

  @Test
  void aSingleDeliveryEventIsExcludedFromTheRedeliveryOnlyFilter() {
    AccessService.Access solo = freshTenant();
    Endpoint ep = endpoint(solo);
    String repeated = "EVT-REPEATED-" + UUID.randomUUID();
    deliver(ep, repeated);
    deliver(ep, repeated);
    deliver(ep, "EVT-ONCE-" + UUID.randomUUID());

    assertThat(view(solo, null, null, null, true))
        .extracting(WebhookDedupEventView::externalEventId)
        .containsExactly(repeated);
  }

  @Test
  void theSameEventIdOnTwoEndpointsIsTwoBusinessEvents() {
    Endpoint ep1 = endpoint(actorA);
    Endpoint ep2 = endpoint(actorA);
    String eventId = "EVT-" + UUID.randomUUID();

    deliver(ep1, eventId);
    deliver(ep2, eventId);

    // 去重锚点是 (endpoint, event)，与 webhook_receipt_first_delivery 部分唯一索引同口径。
    // 只按 event_id 归并会把两个入口各自独立的事件错并成一条——那才是真的重复业务记录。
    assertThat(groupOf(actorA, ep1, eventId).deliveryCount()).isEqualTo(1);
    assertThat(groupOf(actorA, ep2, eventId).deliveryCount()).isEqualTo(1);
    assertThat(view(actorA).stream().filter(e -> e.externalEventId().equals(eventId))).hasSize(2);
  }

  // ==================================================================
  // 蓝图输出：去重命中记录 + 首次处理结果引用
  // ==================================================================

  @Test
  void everyDuplicateHitReferencesTheFirstDeliveryAndItsResult() {
    Endpoint ep = endpoint(actorA);
    String eventId = "EVT-" + UUID.randomUUID();

    WebhookService.IngestResult first = deliver(ep, eventId);
    deliver(ep, eventId);
    deliver(ep, eventId);
    as(
        actorA,
        "channel.sync.update",
        () -> {
          webhooks.claim(actorA, first.receiptId(), new ReceiptClaimInput(null));
          webhooks.complete(
              actorA, first.receiptId(), new ReceiptCompleteInput(ReceiptStatus.PROCESSED, ""));
          return null;
        });

    WebhookDedupDetail detail =
        as(actorA, "channel.sync.read", () -> dedup.detail(actorA, first.receiptId()));

    // 两次重推 = 两条命中记录，每条都指回首推，并带着首推的处理结果
    assertThat(detail.hits()).hasSize(2);
    for (WebhookDedupHitView hit : detail.hits()) {
      assertThat(hit.firstReceiptId()).isEqualTo(first.receiptId());
      assertThat(hit.status()).isEqualTo("DUPLICATE");
      // 不给一个光秃秃的 id：处理结论跟着一起走，否则"重推了但到底处理了没"无从回答
      assertThat(hit.firstStatus()).isEqualTo("PROCESSED");
      assertThat(hit.firstProcessedAt()).isNotNull();
      assertThat(hit.firstLastError()).isEmpty();
    }
    assertThat(detail.hits()).extracting(WebhookDedupHitView::deliveryAttempt)
        .containsExactly(2, 3);

    // 归并行本身也引用首次处理结果
    assertThat(detail.event().firstStatus()).isEqualTo("PROCESSED");
    assertThat(detail.event().processable()).isFalse();
    assertThat(detail.event().firstProcessedAt()).isNotNull();

    // 全部投递时间线：首次一条 + 重复两条
    assertThat(detail.deliveries()).hasSize(3);
    assertThat(detail.deliveries().get(0).duplicateOf()).isNull();
    assertThat(detail.deliveries().get(0).status()).isEqualTo("PROCESSED");
    assertThat(detail.deliveries().get(1).duplicateOf()).isEqualTo(first.receiptId());
    assertThat(detail.deliveries().get(2).duplicateOf()).isEqualTo(first.receiptId());
  }

  @Test
  void aFailedFirstProcessingResultIsVisibleOnEveryHit() {
    Endpoint ep = endpoint(actorA);
    String eventId = "EVT-" + UUID.randomUUID();
    WebhookService.IngestResult first = deliver(ep, eventId);
    deliver(ep, eventId);
    as(
        actorA,
        "channel.sync.update",
        () -> {
          webhooks.claim(actorA, first.receiptId(), new ReceiptClaimInput(null));
          webhooks.complete(
              actorA,
              first.receiptId(),
              new ReceiptCompleteInput(ReceiptStatus.FAILED, "平台订单接口返回 500"));
          return null;
        });

    WebhookDedupDetail detail =
        as(actorA, "channel.sync.read", () -> dedup.detail(actorA, first.receiptId()));
    // 处理失败必须能被一眼看见——报"成功"比漏报更糟
    assertThat(detail.event().firstStatus()).isEqualTo("FAILED");
    assertThat(detail.hits()).allSatisfy(
        hit -> {
          assertThat(hit.firstStatus()).isEqualTo("FAILED");
          assertThat(hit.firstLastError()).contains("500");
        });
  }

  // ==================================================================
  // 时间字段：断言写回的值（timestamptz 绑错会静默偏 8 小时）
  // ==================================================================

  @Test
  void receiveAndProcessingTimesRoundTripWithoutSilentShift() {
    Endpoint ep = endpoint(actorA);
    String eventId = "EVT-" + UUID.randomUUID();
    WebhookService.IngestResult first = deliver(ep, eventId);
    deliver(ep, eventId);

    WebhookDedupDetail detail =
        as(actorA, "channel.sync.read", () -> dedup.detail(actorA, first.receiptId()));
    Instant before = Instant.now().minus(5, ChronoUnit.MINUTES);
    Instant after = Instant.now().plus(1, ChronoUnit.MINUTES);

    // 读回来的必须是库里的真实时刻。绑定方式错了偏 −28800s，这条断言抓得到。
    assertThat(detail.event().firstReceivedAt()).isBetween(before, after);
    assertThat(detail.event().lastReceivedAt()).isAfterOrEqualTo(detail.event().firstReceivedAt());
    assertThat(detail.hits()).allSatisfy(hit -> assertThat(hit.receivedAt()).isBetween(before, after));

    WebhookDedupClaimResult claimed =
        as(
            actorA,
            "channel.sync.update",
            () -> dedup.claim(actorA, first.receiptId(), new WebhookDedupClaimInput(null)));
    assertThat(claimed.processingStartedAt()).isBetween(before, after);
    WebhookDedupEventView afterClaim = groupOf(actorA, ep, eventId);
    assertThat(afterClaim.firstProcessingStartedAt()).isBetween(before, after);
    assertThat(afterClaim.firstStatus()).isEqualTo("PROCESSING");
    // 处理中也可以被重新读回来（视图不做任何缓存，恒读当下真实状态）
    assertThat(
            as(actorA, "channel.sync.read", () -> dedup.detail(actorA, first.receiptId()))
                .event()
                .firstProcessingStartedAt())
        .isBetween(before, after);
  }

  // ==================================================================
  // 筛选与总览
  // ==================================================================

  @Test
  void theViewCanBeFilteredByEventIdConnectionAndFirstStatus() {
    AccessService.Access solo = freshTenant();
    Endpoint ep = endpoint(solo);
    Endpoint other = endpoint(solo);
    String wanted = "EVT-WANTED-" + UUID.randomUUID();
    String untouched = "EVT-UNTOUCHED-" + UUID.randomUUID();

    deliver(ep, wanted);
    deliver(ep, wanted);
    deliver(ep, untouched);
    deliver(other, "EVT-OTHER-CONN-" + UUID.randomUUID());

    assertThat(view(solo, wanted, null, null, false))
        .extracting(WebhookDedupEventView::externalEventId)
        .containsExactly(wanted);
    assertThat(view(solo, null, ep.connectionId(), null, false))
        .allSatisfy(e -> assertThat(e.connectionId()).isEqualTo(ep.connectionId()));
    assertThat(view(solo, null, null, "RECEIVED", false))
        .isNotEmpty()
        .allSatisfy(e -> assertThat(e.firstStatus()).isEqualTo("RECEIVED"));
    // 只看重推过的事件： untouched 没被重推，不该出现
    assertThat(view(solo, null, null, null, true))
        .allSatisfy(e -> assertThat(e.duplicateCount()).isGreaterThan(0))
        .extracting(WebhookDedupEventView::externalEventId)
        .containsExactly(wanted);
  }

  @Test
  void theSummarySeparatesRedeliveryCountFromRedeliveredEventCount() {
    // 干净租户：聚合口径的期望值不能取决于同租户里别的用例留下了多少数据
    AccessService.Access solo = freshTenant();
    Endpoint ep = endpoint(solo);
    String thrice = "EVT-THRICE-" + UUID.randomUUID();
    deliver(ep, thrice);
    deliver(ep, thrice);
    deliver(ep, thrice); // → 2 次重推

    String twice = "EVT-TWICE-" + UUID.randomUUID();
    deliver(ep, twice);
    deliver(ep, twice); // → 1 次重推

    WebhookDedupSummary summary = as(solo, "channel.sync.read", () -> dedup.summary(solo));

    // 一个事件被推 3 次 + 另一个被推 2 次：重复投递共 3 条，但"发生过重推的事件"只有 2 个。
    // 合成一个数字之后就再也分不开"平台在死磕一条事件"和"很多事件各重推了一次"。
    assertThat(summary.totalEvents()).isEqualTo(2);
    assertThat(summary.duplicateDeliveries()).isEqualTo(3);
    assertThat(summary.duplicatedEvents()).isEqualTo(2);
    assertThat(summary.singleDeliveryEvents()).isZero();
    assertThat(summary.pendingFirstEvents()).isEqualTo(2);
    assertThat(summary.worstExternalEventId()).isEqualTo(thrice);
    assertThat(summary.worstDuplicateCount()).isEqualTo(2);
    assertThat(summary.oldestDuplicateAt()).isNotNull();
  }

  @Test
  void anEmptyTenantGetsAnEmptyViewRatherThanAnError() {
    // 一个从没接过推送的租户：去重视图必须是空列表 + 零值总览，不是报错
    AccessService.Access empty = freshTenant();
    assertThat(view(empty)).isEmpty();
    WebhookDedupSummary summary = as(empty, "channel.sync.read", () -> dedup.summary(empty));
    assertThat(summary.totalEvents()).isZero();
    assertThat(summary.duplicateDeliveries()).isZero();
    assertThat(summary.duplicatedEvents()).isZero();
    assertThat(summary.singleDeliveryEvents()).isZero();
    assertThat(summary.pendingFirstEvents()).isZero();
    assertThat(summary.worstExternalEventId()).isEmpty();
    assertThat(summary.worstDuplicateCount()).isZero();
    assertThat(summary.oldestDuplicateAt()).isNull();
  }

  @Test
  void anUnknownFirstReceiptIsNotFound() {
    assertThatThrownBy(
            () ->
                as(
                    actorA,
                    "channel.sync.read",
                    () -> dedup.detail(actorA, UUID.randomUUID())))
        .hasMessageContaining("该外部事件没有接收记录");
  }

  // ==================================================================
  // 多租户三条断言
  // ==================================================================

  @Test
  void tenantsCannotSeeEachOthersDedupView() {
    Endpoint ep = endpoint(actorA);
    String eventA = "EVT-A-" + UUID.randomUUID();
    deliver(ep, eventA);
    deliver(ep, eventA);

    // 租户 B 的工作台看不到租户 A 的任何一个业务事件（不论 B 自己有多少数据）
    assertThat(view(actorB).stream().filter(e -> e.externalEventId().equals(eventA))).isEmpty();
    assertThat(view(actorB).stream().filter(e -> e.firstReceiptId() != null))
        .allMatch(e -> !e.externalEventId().equals(eventA));
    assertThat(as(actorB, "channel.sync.read", () -> dedup.summary(actorB)).duplicateDeliveries())
        .isZero();
  }

  @Test
  void aForgedTenantHeaderCannotRedirectDedupReads() throws Exception {
    Endpoint ep = endpoint(actorA);
    String eventA = "EVT-A-" + UUID.randomUUID();
    deliver(ep, eventA);
    deliver(ep, eventA);

    // 带伪造的 X-Tenant-Id 去读：租户只来自已认证成员关系，这个头必须完全不起作用
    String body =
        http.perform(
                get("/channel/webhook-dedup")
                    .header("Authorization", "Bearer tenant-a")
                    .header("X-Tenant-Id", b.toString()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(body).contains(eventA);
    // 伪造成 B 之后仍然只能看到 A 的数据，B 自己也确实一条都没有
    assertThat(view(actorB).stream().filter(e -> e.externalEventId().equals(eventA))).isEmpty();
    assertThat(as(actorB, "channel.sync.read", () -> dedup.summary(actorB)).duplicateDeliveries())
        .isZero();
  }

  @Test
  void aTenantCannotClaimAnotherTenantsEvent() {
    Endpoint epA = endpoint(actorA);
    String eventA = "EVT-A-" + UUID.randomUUID();
    WebhookService.IngestResult first = deliver(epA, eventA);

    // 用 B 的身份去认领 A 的业务事件：RLS 让更新落到 0 行，对外表现为"不存在"
    assertThatThrownBy(
            () ->
                as(
                    actorB,
                    "channel.sync.update",
                    () -> dedup.claim(actorB, first.receiptId(), new WebhookDedupClaimInput(null))))
        .hasMessageContaining("该外部事件没有接收记录");
    assertThat(groupOf(actorA, epA, eventA).firstStatus()).isEqualTo("RECEIVED");
  }

  @Test
  void theConnectionPoolDoesNotLeakTheTenantAfterBeingReturned() {
    Endpoint epA = endpoint(actorA);
    String eventA = "EVT-A-" + UUID.randomUUID();
    deliver(epA, eventA);
    deliver(epA, eventA);

    // 池大小为 1：连续两次取连接必然复用同一条。B 的请求紧跟在 A 之后，
    // 若 RLS 会话变量没随事务归还，B 就会看到 A 的数据
    Endpoint epB = endpoint(actorB);
    String eventB = "EVT-B-" + UUID.randomUUID();
    deliver(epB, eventB);
    deliver(epB, eventB);

    assertThat(view(actorA).stream().filter(e -> e.externalEventId().equals(eventA))).hasSize(1);
    assertThat(view(actorA).stream().filter(e -> e.externalEventId().equals(eventB))).isEmpty();
    assertThat(view(actorB).stream().filter(e -> e.externalEventId().equals(eventB))).hasSize(1);
    assertThat(view(actorB).stream().filter(e -> e.externalEventId().equals(eventA))).isEmpty();
  }

  // ==================================================================
  // HTTP 形状
  // ==================================================================

  @Test
  void theHttpEndpointsExposeTheWholeDedupContract() throws Exception {
    Endpoint ep = endpoint(actorA);
    String eventId = "EVT-" + UUID.randomUUID();
    WebhookService.IngestResult first = deliver(ep, eventId);
    deliver(ep, eventId);

    http.perform(get("/channel/webhook-dedup").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].externalEventId").value(eventId))
        .andExpect(jsonPath("$[0].deliveryCount").value(2))
        .andExpect(jsonPath("$[0].duplicateCount").value(1))
        .andExpect(jsonPath("$[0].firstStatus").value("RECEIVED"))
        .andExpect(jsonPath("$[0].firstReceiptId").value(first.receiptId().toString()));

    http.perform(get("/channel/webhook-dedup/summary").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.duplicateDeliveries").isNumber());

    http.perform(
            get("/channel/webhook-dedup/" + first.receiptId())
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.event.externalEventId").value(eventId))
        .andExpect(jsonPath("$.hits.length()").value(1))
        .andExpect(jsonPath("$.deliveries.length()").value(2));

    http.perform(
            post("/channel/webhook-dedup/" + first.receiptId() + "/claim")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("PROCESSING"))
        // 认领之后同样的请求必须被拒，否则就是两次处理
        .andExpect(jsonPath("$.receiptId").value(first.receiptId().toString()));

    http.perform(
            post("/channel/webhook-dedup/" + first.receiptId() + "/claim")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isConflict());
  }

  @Test
  void dedupReadsRequireASession() throws Exception {
    // 伪造 X-Tenant-Id 也不行：没有已认证成员关系就没有租户可言
    http.perform(get("/channel/webhook-dedup").header("X-Tenant-Id", b.toString()))
        .andExpect(status().isUnauthorized());
    http.perform(
            get("/channel/webhook-dedup/summary")
                .header("X-Tenant-Id", b.toString())
                .header("X-Internal-Key", "forged"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void aMalformedIdIsRejectedRatherThanTreatedAsAnEvent() throws Exception {
    http.perform(
            get("/channel/webhook-dedup/not-a-uuid").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isBadRequest());
  }
}
