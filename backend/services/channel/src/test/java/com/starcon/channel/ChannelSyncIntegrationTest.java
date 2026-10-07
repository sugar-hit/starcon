package com.starcon.channel;

import static com.starcon.channel.ChannelSyncModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.jooq.DSLContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 02.4 同步运行 integration coverage.
 *
 * <p>The centre of gravity is 2.4.1.3's "游标只在处理提交后推进": every test here tries to move the
 * watermark without a committed run and asserts it did not move. The three tenancy guarantees from
 * AGENTS.md are exercised against the new tables, not merely inherited from the shop tables.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ChannelSyncIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  static final List<String> allPermissions =
      java.util.stream.Stream.of(
              java.util.stream.Stream.of("registry", "connection", "shop")
                  .flatMap(
                      p ->
                          java.util.stream.Stream.of("read", "create", "update", "delete")
                              .map(op -> "channel." + p + "." + op)),
              // 暂停同步会改变平台侧行为，重放会改动数据，两者都必须能与"只读日志"分开授权
              java.util.stream.Stream.of("channel.sync.read", "channel.sync.run", "channel.sync.replay"))
          .flatMap(s -> s)
          .distinct()
          .toList();
  static final List<String> readOnlySync =
      List.of("channel.sync.read", "channel.connection.read", "channel.registry.read");

  static AccessService.Access actor(UUID uid, UUID tenant, List<String> permissions) {
    AccessService.Grant g = new AccessService.Grant(UUID.randomUUID(), "TEST", "ALL", permissions);
    return new AccessService.Access(
        uid, tenant, "Test", UUID.randomUUID(), null, permissions.stream().distinct().toList(), List.of(g));
  }

  static {
    pg.start();
    try {
      try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
          var s = c.createStatement()) {
        s.execute("do $$ begin if not exists(select 1 from pg_roles where rolname='starcon_app')"
            + " then create role starcon_app nologin; end if; end $$");
        s.execute("do $$ begin if not exists(select 1 from pg_roles where rolname='channel_app')"
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
      actors.put("Bearer tenant-a", actor(user, a, allPermissions));
      actors.put("Bearer tenant-b", actor(user, b, allPermissions));
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
    // 单连接让"连接归还池后不串租户"这条断言有意义：下一个借用者拿到的就是同一条物理会话。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  @Autowired ChannelService channels;
  @Autowired ChannelSyncService sync;
  @Autowired DSLContext sql;
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

  <T> T as(AccessService.Access who, String permission, Supplier<T> work) {
    return access.run(who, permission, work);
  }

  <T> T read(Supplier<T> work) {
    return as(actorA, "channel.sync.read", work);
  }

  <T> T run(Supplier<T> work) {
    return as(actorA, "channel.sync.run", work);
  }

  /** 一个可同步的（连接, 站点）组合：必须真的在店铺上启用了该站点。 */
  record Fixture(UUID connectionId, UUID marketplaceId) {}

  Fixture fixture(AccessService.Access who) {
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    UUID conn =
        as(who, "channel.connection.create",
            () -> channels.createConnection(who, new ChannelModels.ConnectionInput("AMAZON", "seller-" + suffix + "@example.com", "")));
    UUID shopId =
        as(who, "channel.shop.create",
            () -> channels.createShop(who, new ChannelModels.ShopInput(
                "SHP-" + suffix, "Shop " + suffix, "AMAZON", conn, UUID.randomUUID(), "SELF_FULFILLED", 0L)));
    String mkCode =
        as(who, "channel.registry.read",
            () -> channels.platforms().stream().filter(p -> p.code().equals("AMAZON")).findFirst().orElseThrow()
                .marketplaces().get(0).code());
    as(who, "channel.shop.update",
        () -> {
          channels.enableMarketplace(who, shopId, new ChannelModels.ShopMarketplaceInput(mkCode, "ACTIVE"));
          return null;
        });
    UUID mkId =
        as(who, "channel.registry.read",
            () -> channels.platforms().stream().filter(p -> p.code().equals("AMAZON")).findFirst().orElseThrow()
                .marketplaces().get(0).id());
    return new Fixture(conn, mkId);
  }

  Instant watermark(AccessService.Access who, Fixture f) {
    SyncCursorView view =
        as(who, "channel.sync.read",
            () -> sync.cursors("").stream()
                .filter(c -> c.task().connectionId().equals(f.connectionId()))
                .findFirst()
                .orElse(null));
    return view == null ? null : view.watermark().value();
  }

  /** 按外部记录键取未关闭失败：failures() 的 reason 参数筛的是原因分类，不是外部单号。 */
  List<SyncFailureView> failuresWithKey(AccessService.Access who, String key) {
    return as(who, "channel.sync.read", () -> sync.failures("", true)).stream()
        .filter(v -> v.externalRecordKey().equals(key))
        .toList();
  }

  SyncRunInput start(Fixture f, ResourceType type) {
    return new SyncRunInput(f.connectionId(), f.marketplaceId(), type, "", "MANUAL", null, null);
  }

  // ------------------------------------------------------------------
  // 2.4.1.3 游标只在处理提交后推进
  // ------------------------------------------------------------------

  @Test
  void failedRunLeavesTheWatermarkUntouchedSoTheNextRunResumesFromTheSamePoint() {
    Fixture f = fixture(actorA);
    UUID first = run(() -> sync.beginRun(actorA, start(f, ResourceType.ORDER)));
    assertThat(watermark(actorA, f)).isEqualTo(Instant.EPOCH);

    run(() -> {
      sync.failRun(actorA, first, "平台返回 503");
      return null;
    });

    // 失败的运行不得推进水位：这是断点续传成立的前提
    assertThat(watermark(actorA, f)).isEqualTo(Instant.EPOCH);
    SyncCursorView cursor =
        read(() -> sync.cursors("ORDER").stream()
            .filter(c -> c.task().connectionId().equals(f.connectionId())).findFirst().orElseThrow());
    assertThat(cursor.consecutiveFailures()).isEqualTo(1);
    SyncRunView failed =
        read(() -> sync.runs(cursor.id(), 10).stream().filter(r -> r.id().equals(first)).findFirst().orElseThrow());
    assertThat(failed.status()).isEqualTo("FAILED");
    assertThat(failed.cursorAfter()).isNull();

    // 下一次运行必须从同一水位起拉，而不是从失败点之后
    UUID second = run(() -> sync.beginRun(actorA, start(f, ResourceType.ORDER)));
    SyncRunView resumed =
        read(() -> sync.runs(cursor.id(), 10).stream().filter(r -> r.id().equals(second)).findFirst().orElseThrow());
    assertThat(resumed.cursorBefore().value()).isEqualTo(Instant.EPOCH);
    assertThat(resumed.triggerKind()).isEqualTo("MANUAL");

    Instant committed = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    run(() -> sync.commitRun(actorA, second, new SyncRunCommit(
        "SUCCEEDED", 12, 10, 2, 0, 0, 0, "", committed, "page-3")));
    assertThat(watermark(actorA, f)).isEqualTo(committed);
    SyncCursorView after =
        read(() -> sync.cursors("ORDER").stream()
            .filter(c -> c.task().connectionId().equals(f.connectionId())).findFirst().orElseThrow());
    assertThat(after.consecutiveFailures()).isZero();
    assertThat(after.watermark().externalMarker()).isEqualTo("page-3");
    assertThat(after.lastRunStatus()).isEqualTo("SUCCEEDED");
  }

  @Test
  void aFailedCommitLeavesNeitherTheRunTerminalNorTheWatermarkBehind() {
    Fixture f = fixture(actorA);
    Instant committed = Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
    UUID first = run(() -> sync.beginRun(actorA, start(f, ResourceType.LISTING)));
    run(() -> sync.commitRun(actorA, first,
        new SyncRunCommit("SUCCEEDED", 8, 8, 0, 0, 0, 0, "", committed, "page-2")));
    assertThat(watermark(actorA, f)).isEqualTo(committed);

    UUID second = run(() -> sync.beginRun(actorA, start(f, ResourceType.LISTING)));
    // 提交的游标早于本次起始水位：这是调用方丢失运行上下文的真实 bug，必须显式拒绝而不是静默接受
    Instant stale = committed.minus(30, ChronoUnit.MINUTES);
    assertThatThrownBy(
            () -> run(() -> sync.commitRun(actorA, second, new SyncRunCommit("SUCCEEDED", 1, 1, 0, 0, 0, 0, "", stale, ""))))
        .hasMessageContaining("早于");
    // 水位没动：仍在第一次运行提交的位置
    assertThat(watermark(actorA, f)).isEqualTo(committed);
    // 运行仍是 RUNNING：回滚后既没落终态也没推进水位，下次仍可在同一运行上重试
    assertThat(read(() -> sync.runs(null, 50)).stream()
            .filter(r -> r.id().equals(second)).findFirst().orElseThrow().status())
        .isEqualTo("RUNNING");

    // 同一次运行可以用合法水位重试成功
    run(() -> sync.commitRun(actorA, second,
        new SyncRunCommit("SUCCEEDED", 3, 3, 0, 0, 0, 0, "", committed.plus(10, ChronoUnit.MINUTES), "page-3")));
    assertThat(watermark(actorA, f)).isEqualTo(committed.plus(10, ChronoUnit.MINUTES));
  }

  @Test
  void aFailedRunCannotClaimItAdvancedTheCursor() {
    Fixture f = fixture(actorA);
    UUID runId = run(() -> sync.beginRun(actorA, start(f, ResourceType.INVENTORY)));
    Instant ahead = Instant.now().plus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
    assertThatThrownBy(
            () -> run(() -> sync.commitRun(actorA, runId, new SyncRunCommit("FAILED", 5, 0, 0, 5, 0, 5, "boom", ahead, "page-9"))))
        .hasMessageContaining("不得推进");
    assertThat(watermark(actorA, f)).isEqualTo(Instant.EPOCH);
  }

  @Test
  void partialRunAdvancesOnlyToTheCommittedPointAndQueuesTheRest() {
    Fixture f = fixture(actorA);
    Instant committed = Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
    UUID runId = run(() -> sync.beginRun(actorA, start(f, ResourceType.ORDER)));
    run(() -> {
      sync.commitRun(actorA, runId,
          new SyncRunCommit("PARTIAL", 20, 15, 0, 5, 2, 5, "5 条字段解析失败", committed, ""));
      sync.recordFailure(actorA, new SyncFailureRecordInput(
          runId, FailureReason.FIELD_PARSE_FAILED, "AMZ-ORDER-001", "s3://redacted/1", "缺少 shipping_date"));
      return null;
    });
    assertThat(watermark(actorA, f)).isEqualTo(committed);

    SyncCursorView cursor =
        read(() -> sync.cursors("ORDER").stream()
            .filter(c -> c.task().connectionId().equals(f.connectionId())).findFirst().orElseThrow());
    assertThat(cursor.backlogCount()).isEqualTo(1);
    SyncRunView partial =
        read(() -> sync.runs(cursor.id(), 10).stream().filter(r -> r.id().equals(runId)).findFirst().orElseThrow());
    assertThat(partial.fetchedCount()).isEqualTo(20);
    assertThat(partial.writtenCount()).isEqualTo(15);
    assertThat(partial.skippedCount()).isEqualTo(5);
    assertThat(partial.errorCount()).isEqualTo(5);
    assertThat(partial.cursorAfter().value()).isEqualTo(committed);

    // 续传从已提交水位继续，而不是从头再来，也���是跳过失败的那 5 条
    UUID next = run(() -> sync.beginRun(actorA, start(f, ResourceType.ORDER)));
    SyncRunView resumedRun =
        read(() -> sync.runs(cursor.id(), 10).stream().filter(r -> r.id().equals(next)).findFirst().orElseThrow());
    assertThat(resumedRun.cursorBefore().value()).isEqualTo(committed);
  }

  @Test
  void onlyOneRunMayBeInFlightPerTaskSoTwoRunnersCannotSkipEachOthersWindows() {
    Fixture f = fixture(actorA);
    run(() -> sync.beginRun(actorA, start(f, ResourceType.SETTLEMENT)));
    assertThatThrownBy(() -> run(() -> sync.beginRun(actorA, start(f, ResourceType.SETTLEMENT))))
        .hasMessageContaining("进行中");
  }

  @Test
  void syncingASiteTheConnectionNeverEnabledIsRejected() {
    Fixture f = fixture(actorA);
    UUID otherSite =
        as(actorA, "channel.registry.read",
            () -> channels.platforms().stream().filter(p -> p.code().equals("AMAZON")).findFirst().orElseThrow()
                .marketplaces().stream().filter(m -> !m.id().equals(f.marketplaceId())).findFirst().orElseThrow().id());
    // 连接与站点各自合法，拼在一起却没有启用店铺——必须拒绝，否则会把 A 站订单写进 B 站
    assertThatThrownBy(
            () -> run(() -> sync.beginRun(actorA, new SyncRunInput(
                f.connectionId(), otherSite, ResourceType.ORDER, "", "MANUAL", null, null))))
        .hasMessageContaining("站点");
  }

  // ------------------------------------------------------------------
  // 2.4.1.3 游标回退
  // ------------------------------------------------------------------

  @Test
  void rewindingTheWatermarkRequiresTheExplicitEndpoint() {
    Fixture f = fixture(actorA);
    Instant committed = Instant.now().minus(3, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
    UUID runId = run(() -> sync.beginRun(actorA, start(f, ResourceType.ORDER)));
    run(() -> sync.commitRun(actorA, runId, new SyncRunCommit("SUCCEEDED", 30, 30, 0, 0, 0, 0, "", committed, "")));
    assertThat(watermark(actorA, f)).isEqualTo(committed);

    SyncCursorView cursor =
        read(() -> sync.cursors("ORDER").stream()
            .filter(c -> c.task().connectionId().equals(f.connectionId())).findFirst().orElseThrow());
    // 裸 SQL 拨回水位必须被触发器挡住：改事实来源只能走可审计的端点
    assertThatThrownBy(
            () -> run(() -> {
              sql.execute("update sync_cursor set cursor_value=?::timestamptz where id=?",
                  java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
                      .withZone(java.time.ZoneOffset.UTC)
                      .format(committed.minus(2, ChronoUnit.HOURS)),
                  cursor.id());
              return null;
            }))
        .hasMessageContaining("rewind");

    // 显式回退端点放行
    Instant target = committed.minus(2, ChronoUnit.HOURS);
    run(() -> {
      sync.rewindCursor(actorA, cursor.id(), new SyncCursorRewindInput(target, "page-1"));
      return null;
    });
    assertThat(watermark(actorA, f)).isEqualTo(target);
    SyncCursorView rewound =
        read(() -> sync.cursors("ORDER").stream()
            .filter(c -> c.task().connectionId().equals(f.connectionId())).findFirst().orElseThrow());
    assertThat(rewound.watermark().externalMarker()).isEqualTo("page-1");

    // 回退后补拉：从回退后的水位起拉，并被标记为重放窗口
    UUID replay = run(() -> sync.beginRun(actorA, new SyncRunInput(
        f.connectionId(), f.marketplaceId(), ResourceType.ORDER, "", "ROLLBACK_REPLAY", target, "page-1")));
    SyncRunView replayRun =
        read(() -> sync.runs(cursor.id(), 10).stream().filter(r -> r.id().equals(replay)).findFirst().orElseThrow());
    assertThat(replayRun.cursorBefore().value()).isEqualTo(target);
    assertThat(replayRun.triggerKind()).isEqualTo("ROLLBACK_REPLAY");
  }

  @Test
  void rewindCannotPushTheWatermarkForward() {
    Fixture f = fixture(actorA);
    run(() -> sync.beginRun(actorA, start(f, ResourceType.ORDER)));
    SyncCursorView cursor =
        read(() -> sync.cursors("ORDER").stream()
            .filter(c -> c.task().connectionId().equals(f.connectionId())).findFirst().orElseThrow());
    assertThatThrownBy(
            () -> run(() -> {
              sync.rewindCursor(actorA, cursor.id(),
                  new SyncCursorRewindInput(Instant.now().plus(1, ChronoUnit.DAYS), ""));
              return null;
            }))
        .hasMessageContaining("只能向过去");
  }

  @Test
  void aReplayWindowRunMayNotClaimOwnershipOfTheWatermark() {
    Fixture f = fixture(actorA);
    Instant committed = Instant.now().minus(3, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
    UUID first = run(() -> sync.beginRun(actorA, start(f, ResourceType.ORDER)));
    run(() -> sync.commitRun(actorA, first, new SyncRunCommit("SUCCEEDED", 10, 10, 0, 0, 0, 0, "", committed, "")));

    // 从水位之前的历史窗口补拉：被判定为重放窗口，游标起点仍是已提交水位
    UUID replay = run(() -> sync.beginRun(actorA, new SyncRunInput(
        f.connectionId(), f.marketplaceId(), ResourceType.ORDER, "", null,
        committed.minus(2, ChronoUnit.HOURS), "")));
    SyncRunView replayRun =
        read(() -> sync.runs(null, 50)).stream().filter(r -> r.id().equals(replay)).findFirst().orElseThrow();
    assertThat(replayRun.triggerKind()).isEqualTo("ROLLBACK_REPLAY");
    assertThat(replayRun.cursorBefore().value()).isEqualTo(committed);

    // 重放窗口不得声称自己推进了水位
    Instant ahead = committed.plus(1, ChronoUnit.HOURS);
    assertThatThrownBy(
            () -> run(() -> sync.commitRun(actorA, replay, new SyncRunCommit("SUCCEEDED", 5, 5, 0, 0, 0, 0, "", ahead, ""))))
        .hasMessageContaining("重放窗口");
    assertThat(watermark(actorA, f)).isEqualTo(committed);

    // 允许按原水位重放：补数成功，失败项仍走失败队列，不动水位
    run(() -> sync.commitRun(actorA, replay, new SyncRunCommit("SUCCEEDED", 5, 5, 0, 0, 0, 0, "", committed, "")));
    assertThat(watermark(actorA, f)).isEqualTo(committed);
  }

  // ------------------------------------------------------------------
  // 2.4.2 异常处理
  // ------------------------------------------------------------------

  @Test
  void reProcessingTheSameExternalRecordNeverCreatesASecondFailure() {
    Fixture f = fixture(actorA);
    UUID runId = run(() -> sync.beginRun(actorA, start(f, ResourceType.ORDER)));
    run(() -> {
      sync.failRun(actorA, runId, "平台限流");
      return null;
    });
    String key = "AMZ-ORDER-" + UUID.randomUUID();
    UUID first = run(() -> sync.recordFailure(actorA,
        new SyncFailureRecordInput(runId, FailureReason.RATE_LIMITED, key, "s3://redacted/a", "429")));
    UUID second = run(() -> sync.recordFailure(actorA,
        new SyncFailureRecordInput(runId, FailureReason.RATE_LIMITED, key, "s3://redacted/a", "429 again")));

    // 重试/webhook 重投撞车时只累加尝试次数，不堆第二张待办
    assertThat(second).isEqualTo(first);
    List<SyncFailureView> open = read(() -> sync.failures("", true));
    assertThat(open.stream().filter(v -> v.externalRecordKey().equals(key))).hasSize(1);
    assertThat(open.stream().filter(v -> v.externalRecordKey().equals(key)).findFirst().orElseThrow().attemptCount())
        .isEqualTo(2);
  }

  @Test
  void replayIsIdempotentAndKeepsCountingAttempts() {
    Fixture f = fixture(actorA);
    UUID runId = run(() -> sync.beginRun(actorA, start(f, ResourceType.ORDER)));
    String key = "AMZ-ORDER-" + UUID.randomUUID();
    UUID failureId = run(() -> sync.recordFailure(actorA,
        new SyncFailureRecordInput(runId, FailureReason.NETWORK_ERROR, key, "", "timeout")));

    SyncFailureView again = run(() -> sync.replayFailure(actorA, failureId,
        new SyncFailureReplayInput("STILL_FAILING", "仍然超时", null)));
    assertThat(again.status()).isEqualTo("OPEN");
    assertThat(again.attemptCount()).isEqualTo(2);
    assertThat(again.lastError()).isEqualTo("仍然超时");

    SyncFailureView resolved = run(() -> sync.replayFailure(actorA, failureId,
        new SyncFailureReplayInput("SUCCESS", null, null)));
    assertThat(resolved.status()).isEqualTo("RESOLVED");
    assertThat(resolved.resolvedAt()).isNotNull();
    assertThat(read(() -> sync.failures("", true)).stream().filter(v -> v.id().equals(failureId))).isEmpty();
    // 已关闭的记录不能重复重放，否则第二次点重放会凭空复活一条已解决的待办
    assertThatThrownBy(() -> run(() -> sync.replayFailure(actorA, failureId,
        new SyncFailureReplayInput("STILL_FAILING", "x", null))))
        .hasMessageContaining("已关闭");
  }

  @Test
  void authFailuresEscalateToTheWorkbenchBecauseTheyCauseSilentOrderLoss() {
    Fixture f = fixture(actorA);
    UUID runId = run(() -> sync.beginRun(actorA, start(f, ResourceType.ORDER)));
    UUID failureId = run(() -> sync.recordFailure(actorA, new SyncFailureRecordInput(
        runId, FailureReason.AUTH_EXPIRED, "AMZ-ORDER-" + UUID.randomUUID(), "", "token expired")));

    SyncFailureView view =
        read(() -> sync.failures("AUTH_EXPIRED", true).stream()
            .filter(v -> v.id().equals(failureId)).findFirst().orElseThrow());
    assertThat(view.escalated()).isTrue();

    FailureClusterView cluster =
        read(() -> sync.failureClusters()).stream()
            .filter(c -> c.reason().equals("AUTH_EXPIRED")).findFirst().orElseThrow();
    assertThat(cluster.escalated()).isGreaterThanOrEqualTo(1);

    // 数据库层兜底：授权失效不得以"未升级"落库
    assertThatThrownBy(() -> run(() -> {
      sql.execute(
          "insert into sync_failure(id,tenant_id,run_id,cursor_id,connection_id,"
              + " resource_type,failure_reason,external_record_key,escalated)"
              + " select ?,?,?,id,connection_id,resource_type,'AUTH_EXPIRED',?,false"
              + " from sync_run where tenant_id=? and id=?",
          UUID.randomUUID(), a, runId, "BYPASS-" + UUID.randomUUID(), a, runId);
      return null;
    })).hasMessageContaining("escalat");
  }

  @Test
  void failureReasonsClusterSoTheQueueCanBeTriaged() {
    Fixture f = fixture(actorA);
    UUID runId = run(() -> sync.beginRun(actorA, start(f, ResourceType.ORDER)));
    run(() -> {
      sync.recordFailure(actorA, new SyncFailureRecordInput(runId, FailureReason.MAPPING_MISSING, "M-" + UUID.randomUUID(), "", "无内部 SKU"));
      sync.recordFailure(actorA, new SyncFailureRecordInput(runId, FailureReason.FIELD_PARSE_FAILED, "P-" + UUID.randomUUID(), "", "日期无法解析"));
      return null;
    });
    List<FailureClusterView> clusters = read(() -> sync.failureClusters());
    assertThat(clusters.stream().map(FailureClusterView::reason)).contains("MAPPING_MISSING", "FIELD_PARSE_FAILED");
    assertThat(clusters.stream()
            .filter(c -> c.reason().equals("MAPPING_MISSING")).findFirst().orElseThrow().escalated())
        .isZero();
  }

  // ------------------------------------------------------------------
  // 多租户：AGENTS.md 要求的三条断言
  // ------------------------------------------------------------------

  @Test
  void tenantsCannotSeeEachOthersCursorsRunsOrFailures() {
    Fixture fa = fixture(actorA);
    String key = "TENANT-ISOLATION-" + UUID.randomUUID();
    UUID runId = run(() -> sync.beginRun(actorA, start(fa, ResourceType.ORDER)));
    run(() -> {
      sync.recordFailure(actorA, new SyncFailureRecordInput(runId, FailureReason.MAPPING_MISSING, key, "", "无映射"));
      return null;
    });
    assertThat(failuresWithKey(actorA, key)).hasSize(1);

    assertThat(failuresWithKey(actorB, key)).isEmpty();
    assertThat(as(actorB, "channel.sync.read", () -> sync.failures("MAPPING_MISSING", true)))
        .isEmpty();
    assertThat(as(actorB, "channel.sync.read", () -> sync.cursors("")).stream()
            .filter(c -> c.task().connectionId().equals(fa.connectionId())))
        .isEmpty();
    // 租户 B 连对方的游标都开不出来：站点校验查不到自己的启用店铺
    assertThatThrownBy(() -> as(actorB, "channel.sync.run", () -> sync.beginRun(actorB, start(fa, ResourceType.ORDER))))
        .hasMessageContaining("站点");
  }

  @Test
  void forgedTenantHeaderCannotReachAnotherTenantsSyncData() throws Exception {
    Fixture fa = fixture(actorA);
    UUID runId = run(() -> sync.beginRun(actorA, start(fa, ResourceType.ORDER)));
    String key = "FORGED-" + UUID.randomUUID();
    run(() -> {
      sync.recordFailure(actorA, new SyncFailureRecordInput(runId, FailureReason.NETWORK_ERROR, key, "", "boom"));
      return null;
    });
    // 用 A 的令牌 + 伪造的租户头：租户仍由成员关系解析，伪造头不影响可见性
    http.perform(get("/channel/sync/failures").param("reason", "NETWORK_ERROR")
            .header("Authorization", "Bearer tenant-a")
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1));
    // 换成 B 的令牌，即使带上 A 的租户头也拿不到 A 的数据
    http.perform(get("/channel/sync/failures").param("reason", "NETWORK_ERROR")
            .header("Authorization", "Bearer tenant-b")
            .header("X-Tenant-Id", a.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  void connectionReturnedToPoolDoesNotLeakThePreviousTenant() {
    Fixture fa = fixture(actorA);
    UUID runId = run(() -> sync.beginRun(actorA, start(fa, ResourceType.ORDER)));
    String key = "POOL-" + UUID.randomUUID();
    run(() -> {
      sync.recordFailure(actorA, new SyncFailureRecordInput(runId, FailureReason.NETWORK_ERROR, key, "", "boom"));
      return null;
    });
    // 借出 A 的连接读一次，再借出 B 的连接读一次：池大小为 1，是同一条物理会话
    assertThat(failuresWithKey(actorA, key)).hasSize(1);
    assertThat(failuresWithKey(actorB, key)).isEmpty();
    // 再借一次 A：必须仍然只看到 A 自己的数据，说明 app.current_tenant 被重新绑定
    assertThat(failuresWithKey(actorA, key)).hasSize(1);
    assertThat(read(() -> sync.cursors("ORDER")).stream()
            .filter(c -> c.task().connectionId().equals(fa.connectionId())))
        .hasSize(1);
  }

  @Test
  void unauthenticatedSyncAccessIsRejected() throws Exception {
    http.perform(get("/channel/sync/tasks")).andExpect(status().isUnauthorized());
    http.perform(get("/channel/sync/failures/clusters")).andExpect(status().isUnauthorized());
  }

  @Test
  void readOnlyRoleCanInspectSyncStateButCannotAdvanceACursor() throws Exception {
    Fixture f = fixture(actorA);
    actors.put("Bearer sync-readonly", actor(user, a, readOnlySync));
    // 有 channel.sync.read：任务列表与失败队列可读
    http.perform(get("/channel/sync/tasks").header("Authorization", "Bearer sync-readonly"))
        .andExpect(status().isOk());
    http.perform(get("/channel/sync/failures").header("Authorization", "Bearer sync-readonly"))
        .andExpect(status().isOk());
    // 缺 channel.sync.run：触发运行必须 403，而不是靠 RLS 在写库时才炸
    http.perform(post("/channel/sync/runs")
            .header("Authorization", "Bearer sync-readonly")
            .contentType("application/json")
            .content(
                """
                {"connectionId":"%s","marketplaceId":"%s","resourceType":"ORDER","partitionKey":"","triggerKind":"MANUAL"}"""
                    .formatted(f.connectionId(), f.marketplaceId())))
        .andExpect(status().isForbidden());
    // 被拒的触发没有留下任何游标
    assertThat(read(() -> sync.cursors("ORDER")).stream()
            .filter(c -> c.task().connectionId().equals(f.connectionId())))
        .isEmpty();
  }
}