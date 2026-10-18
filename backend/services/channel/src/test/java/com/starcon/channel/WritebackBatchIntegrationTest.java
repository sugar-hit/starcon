package com.starcon.channel;

import static com.starcon.channel.ChannelModels.*;
import static com.starcon.channel.WritebackBatchModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 2.5.2.1 常用回写操作的行为约束。
 *
 * <p>蓝图把这个模块的成败压在两句话上，这里逐条钉住：
 *
 * <ol>
 *   <li>「生成 N 条独立回写任务，逐条记录结果」 —— N 条任务必须真的落进 2.5.1.1 的 platform_operation
 *   <li>「**批量操作不等于事务**：每条独立成败、部分失败要如实回报，不能因一条失败回滚全部」 —— 封禁一条，其余照发
 * </ol>
 *
 * <p>外加 2.5.1.1 强调的幂等键，以及多租户三断言。
 */
@SpringBootTest
@AutoConfigureMockMvc
class WritebackBatchIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  /** 权限集与 identity 迁移一致；本模块只用 operation.*，其余一并注册以对齐既有测试。 */
  static final List<String> allPermissions =
      java.util.stream.Stream.of("registry", "connection", "shop", "sync", "operation")
          .flatMap(p -> java.util.stream.Stream.of("read", "create", "update", "delete")
              .map(op -> "channel." + p + "." + op))
          .toList();

  static AccessService.Access actor(UUID tenant) {
    List<AccessService.Grant> grants =
        List.of(new AccessService.Grant(UUID.randomUUID(), "TEST", "ALL", allPermissions));
    return new AccessService.Access(
        user,
        tenant,
        "Test",
        UUID.randomUUID(),
        null,
        allPermissions,
        grants);
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
      actors.put("Bearer tenant-a", actor(a));
      actors.put("Bearer tenant-b", actor(b));
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
    // 池压到 1：多租户串租户在单连接下最容易暴露。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  @Autowired ChannelService channels;
  @Autowired SyncService sync;
  @Autowired WritebackBatchService writebacks;
  @Autowired AccessService access;
  @Autowired MockMvc http;

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

  UUID shop(AccessService.Access who) {
    return as(
        who,
        "channel.shop.update",
        () ->
            channels.createShop(
                who,
                new ShopInput(
                    "SHP-" + UUID.randomUUID().toString().substring(0, 8),
                    "Writeback shop",
                    "AMAZON",
                    null,
                    null,
                    "SELF_FULFILLED",
                    0L)));
  }

  UUID listing(AccessService.Access who, UUID shopId, String externalRef) {
    return as(
        who,
        "channel.operation.create",
        () ->
            writebacks.upsertTarget(
                who,
                new WritebackTargetInput(
                    shopId,
                    TargetType.LISTING,
                    externalRef,
                    "L-" + externalRef,
                    "SKU-" + externalRef,
                    new BigDecimal("10"),
                    new BigDecimal("19.9900"),
                    "USD",
                    "listing-sync")));
  }

  UUID shipment(AccessService.Access who, UUID shopId, String externalRef) {
    return as(
        who,
        "channel.operation.create",
        () ->
            writebacks.upsertTarget(
                who,
                new WritebackTargetInput(
                    shopId, TargetType.SHIPMENT, externalRef, "", "", null, null, null, "order-sync")));
  }

  WritebackBatchInput stockBatch(UUID shopId, List<UUID> targets, BigDecimal stock) {
    return new WritebackBatchInput(
        BatchKind.UPDATE_STOCK,
        shopId,
        null,
        targets,
        new BatchParameters(stock, null, null, null, null, null, null),
        null,
        "批量改库存");
  }

  // ==================================================================
  // 2.5.2.1 批量改库存：生成 N 条独立回写任务
  // ==================================================================

  @Test
  @DisplayName("2.5.2.1 批量改库存生成 N 条独立回写任务，逐条落进 2.5.1.1 的 platform_operation")
  void batchStockCreatesOneOperationPerTarget() {
    UUID shopId = shop(actorA);
    List<UUID> targets =
        List.of(
            listing(actorA, shopId, "AMZ-ITEM-1"),
            listing(actorA, shopId, "AMZ-ITEM-2"),
            listing(actorA, shopId, "AMZ-ITEM-3"));

    WritebackBatchView batch =
        as(actorA, "channel.operation.create", () -> writebacks.execute(actorA, stockBatch(shopId, targets, new BigDecimal("42.500"))));

    assertThat(batch.items()).hasSize(3);
    assertThat(batch.summary().outcome()).isEqualTo("IN_PROGRESS");
    assertThat(batch.summary().queued()).isEqualTo(3);
    assertThat(batch.summary().rejected()).isZero();

    // 每条都是一条**独立**的平台任务，且各自带业务幂等键（蓝图 2.5.1.1 的硬要求）。
    List<String> keys =
        batch.items().stream().map(WritebackItemView::idempotencyKey).toList();
    assertThat(keys).hasSize(3).doesNotHaveDuplicates().allSatisfy(k -> assertThat(k).startsWith("wbo-"));
    assertThat(batch.items()).allSatisfy(i -> assertThat(i.operationStatus()).isEqualTo("PENDING"));

    // 真落到 2.5.1.1 的任务表里，不是只在自己表里记了一笔。
    var operations = opsOfShop(shopId);
    assertThat(operations).hasSize(3);
    assertThat(operations).allSatisfy(o -> assertThat(o.operation()).isEqualTo("UPDATE_STOCK"));
    // 冻结的载荷按**值**断言：业务关心的是「发给平台的是 42.5 这个数」，不是 JSON 写了几位小数。
    assertThat(operations)
        .allSatisfy(
            o -> assertThat(payloadNumber(o.payload(), "stock")).isEqualByComparingTo("42.500"));
    // 旧值一并冻结：事后追问「这条库存是从多少改成多少」时不靠猜。
    assertThat(operations)
        .allSatisfy(
            o ->
                assertThat(payloadNumber(o.payload(), "previousStock"))
                    .isEqualByComparingTo("10.000"));
  }

  /**
   * 从冻结的 payload 里取数值字段。
   *
   * <p>⚠️ **不能**断言字符串形态（如 {@code contains("42.500")}）：payload 落在 jsonb 列里，
   * PostgreSQL 归一化 numeric 的尾随零（42.500 → 42.5、10.000 → 10.0），读回来就看不到原 scale。
   * 写那种断言等于在测 PostgreSQL 的 jsonb 实现细节，而不是本模块的行为。
   */
  private static BigDecimal payloadNumber(String payload, String key) {
    try {
      var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(payload).get(key);
      if (node == null || node.isNull()) return null;
      return new BigDecimal(node.asText());
    } catch (Exception e) {
      throw new AssertionError("payload 解析失败：" + payload, e);
    }
  }

  @Test
  @DisplayName("2.5.2.1 同一幂等键重复提交复用既有批次，不产生第二批平台动作")
  void repeatedBatchWithSameKeyDoesNotDuplicatePlatformActions() {
    UUID shopId = shop(actorA);
    UUID target = listing(actorA, shopId, "AMZ-ITEM-DUP");
    WritebackBatchInput input = stockBatch(shopId, List.of(target), new BigDecimal("7"));

    WritebackBatchView first = as(actorA, "channel.operation.create", () -> writebacks.execute(actorA, input));
    WritebackBatchView second = as(actorA, "channel.operation.create", () -> writebacks.execute(actorA, input));

    assertThat(second.id()).isEqualTo(first.id());
    assertThat(second.batchNo()).isEqualTo(first.batchNo());
    assertThat(opsOfShop(shopId)).hasSize(1);
  }

  @Test
  @DisplayName("2.5.2.1 不给幂等键时按请求内容去重：同样的请求提交两次只生成一批")
  void derivedIdempotencyKeyDeduplicatesIdenticalRequests() {
    UUID shopId = shop(actorA);
    List<UUID> targets =
        List.of(listing(actorA, shopId, "AMZ-DERIVED-1"), listing(actorA, shopId, "AMZ-DERIVED-2"));
    WritebackBatchInput input = stockBatch(shopId, targets, new BigDecimal("5"));

    WritebackBatchView first = as(actorA, "channel.operation.create", () -> writebacks.execute(actorA, input));
    WritebackBatchView second = as(actorA, "channel.operation.create", () -> writebacks.execute(actorA, input));

    assertThat(second.id()).isEqualTo(first.id());
    assertThat(opsOfShop(shopId)).hasSize(2);
  }

  // ==================================================================
  // 加粗句：批量操作不等于事务
  // ==================================================================

  @Test
  @DisplayName("2.5.2.1 一条被封禁不影响其余：部分失败如实回报为 PARTIAL，其余照常发出去")
  void oneBlockedTargetDoesNotRollBackTheRest() {
    UUID shopId = shop(actorA);
    UUID ok1 = listing(actorA, shopId, "AMZ-PARTIAL-1");
    UUID blocked = listing(actorA, shopId, "AMZ-PARTIAL-2");
    UUID ok2 = listing(actorA, shopId, "AMZ-PARTIAL-3");
    as(
        actorA,
        "channel.operation.update",
        () -> {
          writebacks.setTargetStatus(actorA, blocked, TargetStatus.BLOCKED, "平台侧已拒收该 ASIN");
          return null;
        });

    WritebackBatchView batch =
        as(
            actorA,
            "channel.operation.create",
            () ->
                writebacks.execute(
                    actorA, stockBatch(shopId, List.of(ok1, blocked, ok2), new BigDecimal("3"))));

    assertThat(batch.summary().outcome()).isEqualTo("IN_PROGRESS");
    assertThat(batch.summary().selected()).isEqualTo(3);
    assertThat(batch.summary().rejected()).isEqualTo(1);
    assertThat(batch.summary().queued()).isEqualTo(2);

    WritebackItemView rejected =
        batch.items().stream().filter(i -> "REJECTED".equals(i.itemResult())).findFirst().orElseThrow();
    assertThat(rejected.reason()).contains("封禁");
    // 被拒的那条**没有**生成平台任务：没发出去就不该在「发往平台的写操作」清单里冒充失败。
    assertThat(rejected.operationId()).isNull();

    // 关键：另外两条真的发出去了 —— 一条失败没有回滚全部。
    assertThat(opsOfShop(shopId)).hasSize(2);
  }

  @Test
  @DisplayName("2.5.2.1 平台侧部分成功后批次读作 PARTIAL（逐条真实状态聚合，不是措辞）")
  void batchOutcomeBecomesPartialWhenSomeOperationsFail() {
    UUID shopId = shop(actorA);
    UUID first = listing(actorA, shopId, "AMZ-OUTCOME-1");
    UUID second = listing(actorA, shopId, "AMZ-OUTCOME-2");
    UUID third = listing(actorA, shopId, "AMZ-OUTCOME-3");
    WritebackBatchView batch =
        as(
            actorA,
            "channel.operation.create",
            () ->
                writebacks.execute(
                    actorA, stockBatch(shopId, List.of(first, second, third), new BigDecimal("9"))));

    settle(batch.items().get(0).operationId(), true);
    settle(batch.items().get(1).operationId(), false);
    // 第三条留在 PENDING —— 未定的条目不能算失败。

    WritebackBatchView after =
        as(actorA, "channel.operation.read", () -> writebacks.detail(actorA, batch.id()));
    assertThat(after.summary().outcome()).isEqualTo("IN_PROGRESS");
    assertThat(after.summary().succeeded()).isEqualTo(1);
    assertThat(after.summary().failed()).isEqualTo(1);
    assertThat(after.summary().pending()).isEqualTo(1);

    settle(batch.items().get(2).operationId(), true);
    WritebackBatchView settled =
        as(actorA, "channel.operation.read", () -> writebacks.detail(actorA, batch.id()));
    assertThat(settled.summary().outcome()).isEqualTo("PARTIAL");
    assertThat(settled.summary().succeeded()).isEqualTo(2);
    assertThat(settled.summary().failed()).isEqualTo(1);
  }

  @Test
  @DisplayName("2.5.2.1 列表按 outcome 过滤与详情读出的 outcome 一致（SQL 判定与 Java 判定不跑偏）")
  void outcomeFilterAgreesWithDetailOutcome() {
    UUID shopId = shop(actorA);
    UUID first = listing(actorA, shopId, "AMZ-FILTER-1");
    UUID second = listing(actorA, shopId, "AMZ-FILTER-2");
    WritebackBatchView batch =
        as(
            actorA,
            "channel.operation.create",
            () ->
                writebacks.execute(
                    actorA, stockBatch(shopId, List.of(first, second), new BigDecimal("2"))));
    settle(batch.items().get(0).operationId(), true);
    settle(batch.items().get(1).operationId(), false);

    WritebackBatchPage partial =
        as(
            actorA,
            "channel.operation.read",
            () -> writebacks.batches(a, "UPDATE_STOCK", shopId, "PARTIAL", 0, 50));
    assertThat(partial.items()).extracting(WritebackBatchView::id).contains(batch.id());

    WritebackBatchPage succeeded =
        as(
            actorA,
            "channel.operation.read",
            () -> writebacks.batches(a, "UPDATE_STOCK", shopId, "SUCCEEDED", 0, 50));
    assertThat(succeeded.items()).extracting(WritebackBatchView::id).doesNotContain(batch.id());
  }

  @Test
  @DisplayName("2.5.2.1 结果未知单列一类：算进行中而不是失败，避免诱导重试造成重复扣减")
  void unknownOperationsAreNotCountedAsFailures() {
    UUID shopId = shop(actorA);
    UUID only = listing(actorA, shopId, "AMZ-UNKNOWN-1");
    WritebackBatchView batch =
        as(
            actorA,
            "channel.operation.create",
            () -> writebacks.execute(actorA, stockBatch(shopId, List.of(only), new BigDecimal("1"))));

    as(
        actorA,
        "channel.operation.update",
        () -> {
          sync.markUnknown(actorA, batch.items().get(0).operationId(), "网络超时，未收到平台回执");
          return null;
        });

    WritebackBatchView after =
        as(actorA, "channel.operation.read", () -> writebacks.detail(actorA, batch.id()));
    assertThat(after.summary().unknown()).isEqualTo(1);
    assertThat(after.summary().failed()).isZero();
    assertThat(after.summary().outcome()).isEqualTo("IN_PROGRESS");
  }

  // ==================================================================
  // 四种动作
  // ==================================================================

  @Test
  @DisplayName("2.5.2.1 批量改价：币种与目标不一致的那条被拒，其余照发")
  void priceBatchRejectsCurrencyMismatchPerTarget() {
    UUID shopId = shop(actorA);
    UUID usd = listing(actorA, shopId, "AMZ-PRICE-USD");
    UUID eur =
        as(
            actorA,
            "channel.operation.create",
            () ->
                writebacks.upsertTarget(
                    actorA,
                    new WritebackTargetInput(
                        shopId,
                        TargetType.LISTING,
                        "AMZ-PRICE-EUR",
                        "L-EUR",
                        "SKU-EUR",
                        new BigDecimal("4"),
                        new BigDecimal("9.9000"),
                        "EUR",
                        "listing-sync")));

    WritebackBatchView batch =
        as(
            actorA,
            "channel.operation.create",
            () ->
                writebacks.execute(
                    actorA,
                    new WritebackBatchInput(
                        BatchKind.UPDATE_PRICE,
                        shopId,
                        null,
                        List.of(usd, eur),
                        new BatchParameters(
                            null, new BigDecimal("25.0000"), "USD", null, null, null, null),
                        null,
                        "批量改价")));

    assertThat(batch.summary().rejected()).isEqualTo(1);
    assertThat(batch.summary().queued()).isEqualTo(1);
    assertThat(batch.items().get(1).reason()).contains("EUR");
    assertThat(opsOfShop(shopId)).hasSize(1);
  }

  @Test
  @DisplayName("2.5.2.1 批量下架走 PUBLISH 通道并在 payload 里标 delist（不改 2.5.1.1 的既有 CHECK）")
  void delistBatchIsRecordedAsPublishWithDelistFlag() {
    UUID shopId = shop(actorA);
    List<UUID> targets =
        List.of(listing(actorA, shopId, "AMZ-DELIST-1"), listing(actorA, shopId, "AMZ-DELIST-2"));

    WritebackBatchView batch =
        as(
            actorA,
            "channel.operation.create",
            () ->
                writebacks.execute(
                    actorA,
                    new WritebackBatchInput(
                        BatchKind.DELIST,
                        shopId,
                        null,
                        targets,
                        new BatchParameters(null, null, null, null, null, null, null),
                        null,
                        "清退季节品")));

    assertThat(batch.summary().queued()).isEqualTo(2);
    var operations = opsOfShop(shopId);
    assertThat(operations).hasSize(2);
    assertThat(operations).allSatisfy(o -> assertThat(o.operation()).isEqualTo("PUBLISH"));
    assertThat(operations).allSatisfy(o -> assertThat(o.payload()).contains("\"delist\":true"));
  }

  @Test
  @DisplayName("2.5.2.1 批量回传物流：逐目标运单号覆盖公共值，发货时刻按 timestamptz 写回不偏 8 小时")
  void shipUpdateBatchKeepsPerTargetWaybillsAndExactTimestamp() {
    UUID shopId = shop(actorA);
    UUID first = shipment(actorA, shopId, "AMZ-ORDER-1");
    UUID second = shipment(actorA, shopId, "AMZ-ORDER-2");
    OffsetDateTime shippedAt = OffsetDateTime.of(2026, 3, 4, 5, 6, 7, 0, ZoneOffset.ofHours(8));

    WritebackBatchView batch =
        as(
            actorA,
            "channel.operation.create",
            () ->
                writebacks.execute(
                    actorA,
                    new WritebackBatchInput(
                        BatchKind.SHIP_UPDATE,
                        shopId,
                        null,
                        List.of(first, second),
                        new BatchParameters(
                            null,
                            null,
                            null,
                            "DEFAULT-TRACKING-1",
                            "SF",
                            shippedAt,
                            List.of(
                                new PerTargetParameters(
                                    second.toString(),
                                    null,
                                    null,
                                    "TRACK-2-INDIVIDUAL",
                                    "FEDEX",
                                    null))),
                        null,
                        "批量回传物流")));

    assertThat(batch.summary().queued()).isEqualTo(2);
    assertThat(batch.items())
        .extracting(WritebackItemView::trackingNumber)
        .containsExactly("DEFAULT-TRACKING-1", "TRACK-2-INDIVIDUAL");
    assertThat(batch.items()).allSatisfy(i -> assertThat(i.carrierCode()).isNotBlank());

    // ⚠️ timestamptz 往返断言：写进去的时刻必须读回同一个瞬间。
    // 用 Timestamp.valueOf(LocalDateTime) 会静默偏 −28800s，页面上看不出来、平台侧却对不上。
    for (WritebackItemView item : batch.items()) {
      assertThat(item.shippedAt()).isNotNull();
      assertThat(item.shippedAt()).isEqualTo(shippedAt.toInstant());
      assertThat(item.shippedAt()).isEqualTo(Instant.parse("2026-03-03T21:06:07Z"));
    }
  }

  @Test
  @DisplayName("2.5.2.1 回传物流只接受 SHIPMENT 目标：把商品目标混进来会被逐条拒掉")
  void shipUpdateRejectsListingTargets() {
    UUID shopId = shop(actorA);
    UUID order = shipment(actorA, shopId, "AMZ-ORDER-OK");
    UUID item = listing(actorA, shopId, "AMZ-ITEM-NOT-ORDER");

    WritebackBatchView batch =
        as(
            actorA,
            "channel.operation.create",
            () ->
                writebacks.execute(
                    actorA,
                    new WritebackBatchInput(
                        BatchKind.SHIP_UPDATE,
                        shopId,
                        null,
                        List.of(order, item),
                        new BatchParameters(
                            null, null, null, "TRACK-0001", "SF", null, null),
                        null,
                        "批量回传物流")));

    assertThat(batch.summary().rejected()).isEqualTo(1);
    assertThat(batch.items().get(1).reason()).contains("SHIPMENT");
    assertThat(opsOfShop(shopId)).hasSize(1);
  }

  @Test
  @DisplayName("2.5.2.1 勾选里混进不存在的目标：照样回报一条 REJECTED，不悄悄少一条")
  void missingTargetsAreReportedNotDropped() {
    UUID shopId = shop(actorA);
    UUID real = listing(actorA, shopId, "AMZ-REAL-1");
    UUID ghost = UUID.randomUUID();

    WritebackBatchView batch =
        as(
            actorA,
            "channel.operation.create",
            () -> writebacks.execute(actorA, stockBatch(shopId, List.of(real, ghost), new BigDecimal("6"))));

    assertThat(batch.summary().selected()).isEqualTo(2);
    assertThat(batch.summary().queued()).isEqualTo(1);
    assertThat(batch.summary().rejected()).isEqualTo(1);
    assertThat(batch.items().get(1).reason()).contains("目标不存在");
  }

  // ==================================================================
  // 按店铺与筛选条件批量
  // ==================================================================

  @Test
  @DisplayName("2.5.2.1 按店铺 + 关键词筛选批量执行，命中数与候选池读一致")
  void batchByShopAndKeywordUsesRealPredicate() {
    UUID shopId = shop(actorA);
    listing(actorA, shopId, "AMZ-FILTER-WIDGET");
    listing(actorA, shopId, "AMZ-FILTER-WIDGET-2");
    listing(actorA, shopId, "AMZ-FILTER-GADGET");

    WritebackTargetPage pool =
        as(
            actorA,
            "channel.operation.read",
            () -> writebacks.targets(a, shopId, "LISTING", "AMZ-FILTER-WIDGET", "ACTIVE", 0, 50));
    assertThat(pool.totalElements()).isEqualTo(2);

    WritebackBatchPreview preview =
        as(
            actorA,
            "channel.operation.read",
            () ->
                writebacks.preview(
                    actorA,
                    new WritebackBatchInput(
                        BatchKind.UPDATE_STOCK,
                        shopId,
                        new TargetFilter(shopId, TargetType.LISTING, "AMZ-FILTER-WIDGET", null),
                        null,
                        new BatchParameters(new BigDecimal("11"), null, null, null, null, null, null),
                        null,
                        null)));
    assertThat(preview.matched()).isEqualTo(2);
    assertThat(preview.applicable()).isEqualTo(2);

    WritebackBatchView batch =
        as(
            actorA,
            "channel.operation.create",
            () ->
                writebacks.execute(
                    actorA,
                    new WritebackBatchInput(
                        BatchKind.UPDATE_STOCK,
                        shopId,
                        new TargetFilter(shopId, TargetType.LISTING, "AMZ-FILTER-WIDGET", null),
                        null,
                        new BatchParameters(
                            new BigDecimal("11"), null, null, null, null, null, null),
                        null,
                        "按筛选批量")));
    assertThat(batch.summary().queued()).isEqualTo(2);
  }

  @Test
  @DisplayName("2.5.2.1 筛选没命中时直接报错，而不是静默生成一个空批次")
  void emptySelectionIsRejected() {
    UUID shopId = shop(actorA);
    assertThatThrownBy(
            () ->
                as(
                    actorA,
                    "channel.operation.create",
                    () -> writebacks.execute(actorA, stockBatch(shopId, List.of(), new BigDecimal("1")))))
        .hasMessageContaining("没有命中");
  }

  @Test
  @DisplayName("2.5.2.1 候选池分页是真分页：第二页不与第一页重叠")
  void targetPagingIsReal() {
    UUID shopId = shop(actorA);
    for (int i = 0; i < 5; i++) listing(actorA, shopId, "AMZ-PAGE-" + i);

    WritebackTargetPage firstPage =
        as(actorA, "channel.operation.read", () -> writebacks.targets(a, shopId, "LISTING", "AMZ-PAGE", null, 0, 2));
    WritebackTargetPage secondPage =
        as(actorA, "channel.operation.read", () -> writebacks.targets(a, shopId, "LISTING", "AMZ-PAGE", null, 1, 2));

    assertThat(firstPage.items()).hasSize(2);
    assertThat(secondPage.items()).hasSize(2);
    assertThat(firstPage.totalElements()).isEqualTo(5);
    assertThat(firstPage.totalPages()).isEqualTo(3);
    assertThat(firstPage.summary().total()).isEqualTo(5);
    assertThat(
            firstPage.items().stream().map(WritebackTargetView::id).toList())
        .doesNotContainAnyElementsOf(
            secondPage.items().stream().map(WritebackTargetView::id).toList());
  }

  @Test
  @DisplayName("2.5.2.1 封禁必须写原因：没有原因就拒绝封禁")
  void blockingRequiresAReason() {
    UUID shopId = shop(actorA);
    UUID target = listing(actorA, shopId, "AMZ-BLOCK-REASON");
    assertThatThrownBy(
            () ->
                as(
                    actorA,
                    "channel.operation.update",
                    () -> {
                      writebacks.setTargetStatus(actorA, target, TargetStatus.BLOCKED, "  ");
                      return null;
                    }))
        .hasMessageContaining("必须写明原因");
  }

  // ==================================================================
  // 多租户
  // ==================================================================

  @Test
  @DisplayName("2.5.2.1 断言一：两租户互不可见（目标池与批次都看不到对方）")
  void tenantsCannotSeeEachOthersWritebackState() {
    UUID shopA = shop(actorA);
    UUID shopB = shop(actorB);
    UUID targetA = listing(actorA, shopA, "AMZ-TENANT-A-1");
    UUID targetB = listing(actorB, shopB, "AMZ-TENANT-B-1");

    assertThat(as(actorA, "channel.operation.read", () -> writebacks.targets(a, null, null, null, null, 0, 50)).items())
        .extracting(WritebackTargetView::id)
        .contains(targetA)
        .doesNotContain(targetB);
    assertThat(as(actorB, "channel.operation.read", () -> writebacks.targets(b, null, null, null, null, 0, 50)).items())
        .extracting(WritebackTargetView::id)
        .contains(targetB)
        .doesNotContain(targetA);

    WritebackBatchView batchA =
        as(actorA, "channel.operation.create", () -> writebacks.execute(actorA, stockBatch(shopA, List.of(targetA), new BigDecimal("8"))));

    // B 租户连详情都读不到 A 的批次，更别说逐条结果。
    assertThatThrownBy(
            () -> as(actorB, "channel.operation.read", () -> writebacks.detail(actorB, batchA.id())))
        .hasMessageContaining("不存在");
    assertThat(as(actorB, "channel.operation.read", () -> writebacks.batches(b, null, null, null, 0, 50)).items())
        .extracting(WritebackBatchView::id)
        .doesNotContain(batchA.id());
  }

  @Test
  @DisplayName("2.5.2.1 断言二：伪造 tenant header 无效")
  void forgedTenantHeaderCannotReachAnotherTenantsWritebackData() throws Exception {
    UUID shopA = shop(actorA);
    String refA = "AMZ-FORGED-" + UUID.randomUUID();
    UUID targetA = listing(actorA, shopA, refA);

    // 用 B 的令牌 + A 的 X-Tenant-Id：只认已认证成员关系解析出的租户，A 的目标一条都看不到。
    http.perform(get("/channel/writeback-targets")
            .header("Authorization", "Bearer tenant-b")
            .header("X-Tenant-Id", a.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[?(@.id=='" + targetA.toString() + "')]").isEmpty())
        .andExpect(jsonPath("$.items[?(@.externalRef=='" + refA + "')]").isEmpty());
    http.perform(get("/channel/writeback-batches")
            .header("Authorization", "Bearer tenant-b")
            .header("X-Tenant-Id", a.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items").isEmpty());
    // B 的令牌读 A 的批次详情：RLS 让它压根读不到（404），伪造 header 也没有用。
    http.perform(get("/channel/writeback-batches").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[?(@.id=='" + targetA.toString() + "')]").isEmpty());
    http.perform(get("/channel/writeback-targets")).andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("2.5.2.1 断言三：连接池归还后不串租户（池压到 1，A→B→A 三轮）")
  void pooledConnectionsDoNotBleedTenants() {
    UUID shopA = shop(actorA);
    UUID shopB = shop(actorB);
    String refA = "AMZ-POOL-A-" + UUID.randomUUID();
    String refB = "AMZ-POOL-B-" + UUID.randomUUID();
    UUID targetA = listing(actorA, shopA, refA);
    UUID targetB = listing(actorB, shopB, refB);
    UUID batchA =
        as(actorA, "channel.operation.create", () -> writebacks.execute(actorA, stockBatch(shopA, List.of(targetA), new BigDecimal("4")))).id();
    UUID batchB =
        as(actorB, "channel.operation.create", () -> writebacks.execute(actorB, stockBatch(shopB, List.of(targetB), new BigDecimal("4")))).id();

    // 同一个池上交替跑 A/B/A/B/A —— 单连接下最容易暴露的串租户就在这里。
    for (int i = 0; i < 3; i++) {
      var pageA =
          as(actorA, "channel.operation.read", () -> writebacks.targets(a, null, null, null, null, 0, 200));
      var pageB =
          as(actorB, "channel.operation.read", () -> writebacks.targets(b, null, null, null, null, 0, 200));
      assertThat(pageA.items()).extracting(WritebackTargetView::externalRef).contains(refA).doesNotContain(refB);
      assertThat(pageB.items()).extracting(WritebackTargetView::externalRef).contains(refB).doesNotContain(refA);
      assertThat(pageA.items()).extracting(WritebackTargetView::id).contains(targetA).doesNotContain(targetB);
      assertThat(pageB.items()).extracting(WritebackTargetView::id).contains(targetB).doesNotContain(targetA);

      var batchesA =
          as(actorA, "channel.operation.read", () -> writebacks.batches(a, null, null, null, 0, 100));
      var batchesB =
          as(actorB, "channel.operation.read", () -> writebacks.batches(b, null, null, null, 0, 100));
      assertThat(batchesA.items()).extracting(WritebackBatchView::id).contains(batchA).doesNotContain(batchB);
      assertThat(batchesB.items()).extracting(WritebackBatchView::id).contains(batchB).doesNotContain(batchA);

      // 逐条结果也各自只认自己的租户。
      assertThat(as(actorA, "channel.operation.read", () -> writebacks.detail(actorA, batchA)).items())
          .extracting(WritebackItemView::externalRef)
          .containsExactly(refA);
      assertThat(as(actorB, "channel.operation.read", () -> writebacks.detail(actorB, batchB)).items())
          .extracting(WritebackItemView::externalRef)
          .containsExactly(refB);
    }
  }

  // ==================================================================
  // 端点
  // ==================================================================

  @Test
  @DisplayName("2.5.2.1 端点可真实操作（MockMvc 走完整控制器链路）")
  void endpointsDriveTheFlow() throws Exception {
    http.perform(get("/channel/writeback-targets").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items").isArray());
    http.perform(get("/channel/writeback-batches").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.summary").exists());
    // 既没勾选也没给筛选条件：直接拒绝。全租户范围的批量一次误传就是真实事故。
    http.perform(post("/channel/writeback-batches/preview")
            .header("Authorization", "Bearer tenant-a")
            .contentType("application/json")
            .content("{\"kind\":\"UPDATE_STOCK\",\"parameters\":{\"stock\":1}}"))
        .andExpect(status().isBadRequest());
    // 给了范围之后，0 条命中是合法答案 —— 它正是执行前该看到的。
    http.perform(post("/channel/writeback-batches/preview")
            .header("Authorization", "Bearer tenant-a")
            .contentType("application/json")
            .content("{\"kind\":\"UPDATE_STOCK\",\"parameters\":{\"stock\":1},"
                + "\"filter\":{\"keyword\":\"AMZ-NO-SUCH-TARGET\"}}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.matched").value(0))
        .andExpect(jsonPath("$.applicable").value(0));
    http.perform(get("/channel/writeback-batches/{id}", UUID.randomUUID())
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isNotFound());
    http.perform(post("/channel/writeback-batches")
            .header("Authorization", "Bearer tenant-a")
            .contentType("application/json")
            .content("{\"kind\":\"UPDATE_STOCK\",\"parameters\":{\"stock\":-1}}"))
        .andExpect(status().isBadRequest());
  }

  // ==================================================================
  // helpers
  // ==================================================================

  /**
   * 本店铺的平台回写任务。
   *
   * <p>按店铺收窄而不是按租户总数：同一个测试类的用例共用一个库，按租户数会互相干扰，
   * 断言就成了「取决于哪条用例先跑」而不是「取决于本模块的行为」。
   */
  private List<PlatformOperationView> opsOfShop(UUID shopId) {
    return as(actorA, "channel.operation.read", () -> sync.operations(a, null, 200)).stream()
        .filter(o -> o.shopId().equals(shopId))
        .toList();
  }

  /** 经 2.5.1.1 的既有端点收口单条任务：批量模块自己不发明任务状态机。 */
  private void settle(UUID operationId, boolean succeed) {
    as(
        actorA,
        "channel.operation.update",
        () -> {
          sync.startOperation(actorA, operationId);
          if (succeed) sync.succeedOperation(actorA, operationId, "REQ-" + UUID.randomUUID());
          else sync.failOperation(actorA, operationId, "平台返回 400：库存字段被拒");
          return null;
        });
  }
}
