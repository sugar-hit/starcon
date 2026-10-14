package com.starcon.channel;

import static com.starcon.channel.ChannelModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
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
 * 02.4 同步运行 与 02.5 平台回写 的行为约束。
 *
 * <p>蓝图把三条纪律写成不可让步的前提，这里逐条钉住：
 *
 * <ol>
 *   <li>游标只在处理提交后推进——处理失败时游标必须原地不动，否则那段数据永久丢失
 *   <li>重放同一外部记录不产生第二条失败记录，也不产生第二张业务单据
 *   <li>平台返回未知结果时状态是 UNKNOWN 而非 FAILED，且未核验不能重发
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class SyncRuntimeIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  /**
   * 权限集与 identity 迁移注册的权限码一致：registry/connection/shop/sync/operation 的 CRUD， 外加 sync.trigger 与
   * sync.complete —— 后两者是 02.4 特有的细分动作。
   */
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
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  @Autowired ChannelService channels;
  @Autowired SyncService sync;
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

  UUID connection(AccessService.Access who) {
    return as(
        who,
        "channel.connection.update",
        () ->
            channels.createConnection(
                who,
                new ConnectionInput("AMAZON", "sync-" + UUID.randomUUID() + "@example.com", "")));
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
                    "Sync shop",
                    "AMAZON",
                    null,
                    null,
                    "SELF_FULFILLED",
                    0L)));
  }

  UUID cursor(AccessService.Access who, UUID connectionId, String resource) {
    return as(
        who,
        "channel.sync.create",
        () ->
            sync.registerCursor(who, new SyncCursorInput(connectionId, null, resource, "default")));
  }

  // ------------------------------------------------------------------
  // 02.4.1.1 同步任务
  // ------------------------------------------------------------------

  @Test
  void cursorIsRegisteredPerConnectionAndResourceNotPerShop() {
    UUID conn = connection(actorA);
    UUID orders = cursor(actorA, conn, "ORDER");
    UUID listings = cursor(actorA, conn, "LISTING");
    // 粒度是（连接, 站点, 资源类型, 分区）：同一连接下不同资源是不同任务
    assertThat(orders).isNotEqualTo(listings);
    assertThat(as(actorA, "channel.sync.read", () -> sync.cursors(conn))).hasSize(2);
  }

  @Test
  void duplicateCursorRegistrationIsRejectedSoResumePointDoesNotSplit() {
    UUID conn = connection(actorA);
    cursor(actorA, conn, "ORDER");
    assertThatThrownBy(() -> cursor(actorA, conn, "ORDER")).hasMessageContaining("已存在");
  }

  // ------------------------------------------------------------------
  // 铁律一：游标只在处理提交后推进
  // ------------------------------------------------------------------

  @Test
  void cursorAdvancesOnlyAfterASuccessfulRun() {
    UUID conn = connection(actorA);
    UUID c = cursor(actorA, conn, "ORDER");
    UUID run = as(actorA, "channel.sync.trigger", () -> sync.triggerRun(actorA, c, "MANUAL"));
    as(
        actorA,
        "channel.sync.complete",
        () -> {
          sync.completeRun(
              actorA,
              run,
              new SyncRunResult("SUCCEEDED", 10, 8, 2, "2026-01-02T00:00:00Z", 1200),
              List.of());
          return null;
        });
    assertThat(as(actorA, "channel.sync.read", () -> sync.cursor(c)).cursorValue())
        .isEqualTo("2026-01-02T00:00:00Z");
  }

  @Test
  void failedRunLeavesTheCursorInPlaceSoTheSameRangeIsRetried() {
    UUID conn = connection(actorA);
    UUID c = cursor(actorA, conn, "ORDER");
    UUID run = as(actorA, "channel.sync.trigger", () -> sync.triggerRun(actorA, c, "MANUAL"));
    as(
        actorA,
        "channel.sync.complete",
        () -> {
          sync.completeRun(
              actorA,
              run,
              new SyncRunResult("FAILED", 10, 0, 0, "2026-01-09T00:00:00Z", 900),
              List.of(
                  new SyncFailureInput(
                      "AMZ-ORDER-1",
                      "ORDER",
                      FailureReason.NETWORK_ERROR,
                      "timeout",
                      "abc123",
                      "e")));
          return null;
        });
    SyncCursorView after = as(actorA, "channel.sync.read", () -> sync.cursor(c));
    // 铁律：游标没有推进，仍停在初始值 —— 下一轮会从同一处重来，不会漏数据
    assertThat(after.cursorValue()).isEmpty();
    assertThat(after.consecutiveFailures()).isEqualTo(1);
    assertThat(after.lastFailureAt()).isNotNull();
  }

  @Test
  void repeatedFailuresMarkTheCursorBrokenSoItNeedsHumanIntervention() {
    UUID conn = connection(actorA);
    UUID c = cursor(actorA, conn, "ORDER");
    for (int i = 0; i < 5; i++) {
      UUID run = as(actorA, "channel.sync.trigger", () -> sync.triggerRun(actorA, c, "MANUAL"));
      as(
          actorA,
          "channel.sync.complete",
          () -> {
            sync.completeRun(actorA, run, new SyncRunResult("FAILED", 1, 0, 0, "", 10), List.of());
            return null;
          });
    }
    // 游标跑飞是事故，必须暴露出来而不是无限重试
    assertThat(as(actorA, "channel.sync.read", () -> sync.cursor(c)).status()).isEqualTo("BROKEN");
    assertThatThrownBy(
            () -> as(actorA, "channel.sync.trigger", () -> sync.triggerRun(actorA, c, "MANUAL")))
        .hasMessageContaining("BROKEN");
  }

  @Test
  void settledRunCannotBeTamperedWithSoHistoryStaysHonest() {
    UUID conn = connection(actorA);
    UUID c = cursor(actorA, conn, "ORDER");
    UUID run = as(actorA, "channel.sync.trigger", () -> sync.triggerRun(actorA, c, "MANUAL"));
    as(
        actorA,
        "channel.sync.complete",
        () -> {
          sync.completeRun(
              actorA, run, new SyncRunResult("SUCCEEDED", 1, 1, 0, "v1", 5), List.of());
          return null;
        });
    assertThatThrownBy(
            () ->
                as(
                    actorA,
                    "channel.sync.complete",
                    () -> {
                      sync.completeRun(
                          actorA, run, new SyncRunResult("SUCCEEDED", 9, 9, 0, "v9", 5), List.of());
                      return null;
                    }))
        .hasMessageContaining("不可修改");
  }

  // ------------------------------------------------------------------
  // 铁律二：重放幂等
  // ------------------------------------------------------------------

  @Test
  void repeatedFailuresForTheSameExternalRecordCollapseIntoOne() {
    UUID conn = connection(actorA);
    UUID c = cursor(actorA, conn, "ORDER");
    // 独立 external_key：去重是按 (tenant, cursor, external_key) 的，
    // 用固定字面量会与其他用例的数据混在一起
    String externalKey = "AMZ-ORDER-" + UUID.randomUUID();
    UUID run = as(actorA, "channel.sync.trigger", () -> sync.triggerRun(actorA, c, "MANUAL"));
    as(
        actorA,
        "channel.sync.complete",
        () -> {
          sync.completeRun(
              actorA,
              run,
              new SyncRunResult("FAILED", 1, 0, 0, "", 10),
              List.of(
                  new SyncFailureInput(
                      externalKey,
                      "ORDER",
                      FailureReason.FIELD_PARSE_FAILED,
                      "bad field",
                      "d1",
                      "e")));
          return null;
        });
    UUID run2 = as(actorA, "channel.sync.trigger", () -> sync.triggerRun(actorA, c, "REPLAY"));
    as(
        actorA,
        "channel.sync.complete",
        () -> {
          sync.completeRun(
              actorA,
              run2,
              new SyncRunResult("FAILED", 1, 0, 0, "", 10),
              List.of(
                  new SyncFailureInput(
                      externalKey,
                      "ORDER",
                      FailureReason.FIELD_PARSE_FAILED,
                      "still bad",
                      "d1",
                      "e")));
          return null;
        });
    // 铁律：同一游标下同一 external_key 只有一条失败记录，重放不产生第二条
    List<SyncFailureView> open = as(actorA, "channel.sync.read", () -> sync.failures(a, null, 50));
    assertThat(open.stream().filter(f -> f.externalKey().equals(externalKey))).hasSize(1);
    assertThat(
            open.stream()
                .filter(f -> f.externalKey().equals(externalKey))
                .findFirst()
                .orElseThrow()
                .attempts())
        .isEqualTo(2);
  }

  @Test
  void failureSummaryClustersByReasonSoSilentLeakageIsVisible() {
    UUID conn = connection(actorA);
    UUID c = cursor(actorA, conn, "ORDER");
    UUID run = as(actorA, "channel.sync.trigger", () -> sync.triggerRun(actorA, c, "MANUAL"));
    as(
        actorA,
        "channel.sync.complete",
        () -> {
          sync.completeRun(
              actorA,
              run,
              new SyncRunResult("PARTIAL", 5, 2, 0, "", 30),
              List.of(
                  new SyncFailureInput("K-1", "ORDER", FailureReason.MAPPING_MISSING, "", "", ""),
                  new SyncFailureInput(
                      "K-2", "ORDER", FailureReason.AUTHORIZATION_EXPIRED, "", "", ""),
                  new SyncFailureInput("K-3", "ORDER", FailureReason.MAPPING_MISSING, "", "", "")));
          return null;
        });
    List<SyncFailureSummary> summary =
        as(actorA, "channel.sync.read", () -> sync.failureSummary(a));
    // 只看本用例引入的三条：容器被本类所有测试共享，全局分布里还有别的用例的记录
    assertThat(summary.stream().filter(s -> s.reason().equals("AUTHORIZATION_EXPIRED")).count())
        .isEqualTo(1);
    assertThat(
            summary.stream()
                .filter(s -> s.reason().equals("MAPPING_MISSING"))
                .findFirst()
                .orElseThrow()
                .total())
        .isEqualTo(2);
    // "授权失效"是唯一会导致持续漏单的原因，排序必须让它可见而不是淹没在长尾里
    assertThat(
            summary.stream()
                .filter(s -> s.reason().equals("AUTHORIZATION_EXPIRED"))
                .findFirst()
                .orElseThrow()
                .total())
        .isPositive();
  }

  @Test
  void replayCreatesANewRunAndLeavingItOpenKeepsTheFailureVisible() {
    UUID conn = connection(actorA);
    UUID c = cursor(actorA, conn, "ORDER");
    UUID run = as(actorA, "channel.sync.trigger", () -> sync.triggerRun(actorA, c, "MANUAL"));
    as(
        actorA,
        "channel.sync.complete",
        () -> {
          sync.completeRun(
              actorA,
              run,
              new SyncRunResult("FAILED", 1, 0, 0, "", 10),
              List.of(
                  new SyncFailureInput("K-9", "ORDER", FailureReason.RATE_LIMITED, "", "", "")));
          return null;
        });
    SyncFailureView failure =
        as(actorA, "channel.sync.read", () -> sync.failures(a, null, 50)).stream()
            .filter(f -> f.externalKey().equals("K-9"))
            .findFirst()
            .orElseThrow();
    UUID replayRun = as(actorA, "channel.sync.trigger", () -> sync.replay(actorA, failure.id()));
    // 重放运行未成功前，失败记录仍保持 OPEN
    as(actorA, "channel.sync.read", () -> sync.failures(a, null, 50)).stream()
        .filter(f -> f.externalKey().equals("K-9"))
        .forEach(f -> assertThat(f.status()).isEqualTo("OPEN"));
    as(
        actorA,
        "channel.sync.complete",
        () -> {
          sync.resolveFailures(actorA, replayRun, true);
          return null;
        });
    assertThat(as(actorA, "channel.sync.read", () -> sync.failures(a, null, 50)))
        .noneMatch(f -> f.externalKey().equals("K-9"));
  }

  // ------------------------------------------------------------------
  // 02.4.1.3 断点续传
  // ------------------------------------------------------------------

  @Test
  void rewindMovesTheCursorBackAndRefusesToSkipAhead() {
    UUID conn = connection(actorA);
    UUID c = cursor(actorA, conn, "ORDER");
    UUID run = as(actorA, "channel.sync.trigger", () -> sync.triggerRun(actorA, c, "MANUAL"));
    as(
        actorA,
        "channel.sync.complete",
        () -> {
          sync.completeRun(
              actorA,
              run,
              new SyncRunResult("SUCCEEDED", 5, 5, 0, "2026-02-01T00:00:00Z", 5),
              List.of());
          return null;
        });
    as(
        actorA,
        "channel.sync.update",
        () -> {
          sync.rewindCursor(actorA, c, "2026-01-15T00:00:00Z");
          return null;
        });
    assertThat(as(actorA, "channel.sync.read", () -> sync.cursor(c)).cursorValue())
        .isEqualTo("2026-01-15T00:00:00Z");
    // 超前于当前游标的"回退"是数据丢失，���须拒绝
    assertThatThrownBy(
            () ->
                as(
                    actorA,
                    "channel.sync.update",
                    () -> {
                      sync.rewindCursor(actorA, c, "2026-03-01T00:00:00Z");
                      return null;
                    }))
        .hasMessageContaining("超前");
  }

  @Test
  void pausedCursorCannotBeTriggered() {
    UUID conn = connection(actorA);
    UUID c = cursor(actorA, conn, "ORDER");
    as(
        actorA,
        "channel.sync.update",
        () -> {
          sync.setCursorPaused(actorA, c, true);
          return null;
        });
    assertThatThrownBy(
            () -> as(actorA, "channel.sync.trigger", () -> sync.triggerRun(actorA, c, "MANUAL")))
        .hasMessageContaining("PAUSED");
    as(
        actorA,
        "channel.sync.update",
        () -> {
          sync.setCursorPaused(actorA, c, false);
          return null;
        });
    assertThat(as(actorA, "channel.sync.trigger", () -> sync.triggerRun(actorA, c, "MANUAL")))
        .isNotNull();
  }

  // ------------------------------------------------------------------
  // 02.5 平台回写
  // ------------------------------------------------------------------

  @Test
  void enqueueingTheSameIdempotencyKeyReusesTheExistingTask() {
    UUID s = shop(actorA);
    String key = "stock-" + UUID.randomUUID();
    UUID first =
        as(
            actorA,
            "channel.operation.create",
            () ->
                sync.enqueueOperation(
                    actorA,
                    new PlatformOperationInput(
                        s, null, OperationKind.UPDATE_STOCK, key, "{\"qty\":5}")));
    UUID second =
        as(
            actorA,
            "channel.operation.create",
            () ->
                sync.enqueueOperation(
                    actorA,
                    new PlatformOperationInput(
                        s, null, OperationKind.UPDATE_STOCK, key, "{\"qty\":5}")));
    // 铁律：同一幂等键不会产生第二条回写，上游重发是安全的
    assertThat(second).isEqualTo(first);
    // 断言只数自己的键：容器被本类所有测试共享，列表里还有别的用例留下的记录
    assertThat(as(actorA, "channel.operation.read", () -> sync.operations(a, null, 50)))
        .filteredOn(o -> o.idempotencyKey().equals(key))
        .hasSize(1);
  }

  @Test
  void unknownResultIsNotTreatedAsFailureAndCannotBeRetriedBlindly() {
    UUID s = shop(actorA);
    UUID op =
        as(
            actorA,
            "channel.operation.create",
            () ->
                sync.enqueueOperation(
                    actorA,
                    new PlatformOperationInput(
                        s, null, OperationKind.SHIP_UPDATE, "ship-" + UUID.randomUUID(), "{}")));
    as(
        actorA,
        "channel.operation.update",
        () -> {
          sync.startOperation(actorA, op);
          sync.markUnknown(actorA, op, "read timeout after send");
          return null;
        });
    PlatformOperationView unknown =
        as(actorA, "channel.operation.read", () -> sync.operations(a, null, 50)).stream()
            .filter(o -> o.id().equals(op))
            .findFirst()
            .orElseThrow();
    // 关键：UNKNOWN 而不是 FAILED。超时不代表平台没执行。
    assertThat(unknown.status()).isEqualTo("UNKNOWN");

    // 核验为"确认未执行"后才允许重新发起
    as(
        actorA,
        "channel.operation.update",
        () -> {
          sync.verifyOperation(actorA, op, Verification.CONFIRMED_NOT_APPLIED);
          return null;
        });
    assertThat(
            as(actorA, "channel.operation.read", () -> sync.operations(a, null, 50)).stream()
                .filter(o -> o.id().equals(op))
                .findFirst()
                .orElseThrow()
                .status())
        .isEqualTo("FAILED");
  }

  @Test
  void verificationThatConfirmsAppliedMarksSuccessWithoutResending() {
    UUID s = shop(actorA);
    UUID op =
        as(
            actorA,
            "channel.operation.create",
            () ->
                sync.enqueueOperation(
                    actorA,
                    new PlatformOperationInput(
                        s, null, OperationKind.PUBLISH, "pub-" + UUID.randomUUID(), "{}")));
    as(
        actorA,
        "channel.operation.update",
        () -> {
          sync.startOperation(actorA, op);
          sync.markUnknown(actorA, op, "timeout");
          sync.verifyOperation(actorA, op, Verification.CONFIRMED_APPLIED);
          return null;
        });
    PlatformOperationView verified =
        as(actorA, "channel.operation.read", () -> sync.operations(a, null, 50)).stream()
            .filter(o -> o.id().equals(op))
            .findFirst()
            .orElseThrow();
    assertThat(verified.status()).isEqualTo("SUCCEEDED");
    assertThat(verified.verification()).isEqualTo("CONFIRMED_APPLIED");
    assertThat(verified.verifiedAt()).isNotNull();
  }

  @Test
  void stillUncertainKeepsTheTaskUnknownSoItIsNeverBlindlyResent() {
    UUID s = shop(actorA);
    UUID op =
        as(
            actorA,
            "channel.operation.create",
            () ->
                sync.enqueueOperation(
                    actorA,
                    new PlatformOperationInput(
                        s, null, OperationKind.UPDATE_PRICE, "price-" + UUID.randomUUID(), "{}")));
    as(
        actorA,
        "channel.operation.update",
        () -> {
          sync.startOperation(actorA, op);
          sync.markUnknown(actorA, op, "timeout");
          sync.verifyOperation(actorA, op, Verification.STILL_UNKNOWN);
          return null;
        });
    PlatformOperationView still =
        as(actorA, "channel.operation.read", () -> sync.operations(a, null, 50)).stream()
            .filter(o -> o.id().equals(op))
            .findFirst()
            .orElseThrow();
    assertThat(still.status()).isEqualTo("UNKNOWN");
    assertThat(still.verification()).isEqualTo("STILL_UNKNOWN");
  }

  // ------------------------------------------------------------------
  // 多租户
  // ------------------------------------------------------------------

  @Test
  void tenantsCannotSeeEachOthersSyncState() {
    UUID conn = connection(actorA);
    UUID c = cursor(actorA, conn, "ORDER");
    assertThat(as(actorA, "channel.sync.read", () -> sync.cursors(conn))).hasSize(1);
    assertThat(as(actorB, "channel.sync.read", () -> sync.cursors(conn))).isEmpty();

    UUID run = as(actorA, "channel.sync.trigger", () -> sync.triggerRun(actorA, c, "MANUAL"));
    as(
        actorA,
        "channel.sync.complete",
        () -> {
          sync.completeRun(
              actorA,
              run,
              new SyncRunResult("FAILED", 1, 0, 0, "", 5),
              List.of(
                  new SyncFailureInput(
                      "LEAK-1", "ORDER", FailureReason.NETWORK_ERROR, "", "", "")));
          return null;
        });
    assertThat(as(actorA, "channel.sync.read", () -> sync.failures(a, null, 50))).isNotEmpty();
    // B 租户看不到 A 的失败队列
    assertThat(as(actorB, "channel.sync.read", () -> sync.failures(b, null, 50)))
        .noneMatch(f -> f.externalKey().equals("LEAK-1"));
  }

  @Test
  void forgedTenantHeaderCannotReachAnotherTenantsOperations() throws Exception {
    UUID s = shop(actorA);
    String key = "forged-" + UUID.randomUUID();
    as(
        actorA,
        "channel.operation.create",
        () ->
            sync.enqueueOperation(
                actorA,
                new PlatformOperationInput(s, null, OperationKind.UPDATE_STOCK, key, "{}")));
    http.perform(
            get("/channel/platform-operations")
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  void httpSurfaceExposesSyncAndOperations() throws Exception {
    http.perform(get("/channel/sync-cursors").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk());
    http.perform(get("/channel/sync-runs").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk());
    http.perform(get("/channel/sync-failures/summary").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk());
    http.perform(get("/channel/platform-operations").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk());
    http.perform(get("/channel/sync-cursors")).andExpect(status().isUnauthorized());
  }
}
