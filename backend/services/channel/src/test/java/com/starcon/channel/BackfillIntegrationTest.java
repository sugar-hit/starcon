package com.starcon.channel;

import static com.starcon.channel.BackfillModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
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
 * 02.6.2.1 增量补拉的行为约束。
 *
 * <p>蓝图 §2.6.2.1 的四条要求逐条钉住：
 *
 * <ol>
 *   <li><b>补拉必须幂等</b> —— 同一条外部记录补拉两次，第二次必须被判为去重而不是新建。
 *       这条用真实 PG 16 跑：唯一索引是唯一裁判。
 *   <li>平台只给「最近 N 条」时分段轮询 + 去重兜底，且任务带「补拉可能延迟发现」标记
 *   <li>四个数字分开记：拉取 / 新增 / 更新 / 去重
 *   <li>幂等键是入参契约：相同 key 重复发起不产生第二个补拉任务
 * </ol>
 *
 * <p>外加多租户三断言（互不可见 / 伪造 tenant header 无效 / 连接池归还后不串租户）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class BackfillIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

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
    // 池大小为 1：连接复用最容易串租户，单连接下这条断言最严格
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  @Autowired ChannelService channels;
  @Autowired SyncService sync;
  @Autowired BackfillService backfills;
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

  UUID connection(AccessService.Access who) {
    return as(
        who,
        "channel.connection.create",
        () ->
            channels.createConnection(
                who,
                new ChannelModels.ConnectionInput(
                    "AMAZON", "bf-" + UUID.randomUUID() + "@example.com", "")));
  }

  /** 构造一个覆盖「最近两小时」的补拉任务。 */
  BackfillTaskInput taskInput(AccessService.Access who, UUID conn, String key) {
    OffsetDateTime end = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(5);
    return new BackfillTaskInput(
        conn,
        null,
        "ORDER",
        end.minusHours(2),
        end,
        BackfillTrigger.MANUAL,
        key,
        null,
        PaginationMode.TIME_INCREMENTAL,
        false,
        100,
        20,
        60);
  }

  UUID createTask(AccessService.Access who, UUID conn, String key) {
    BackfillTaskInput in = taskInput(who, conn, key);
    return as(who, "channel.sync.trigger", () -> backfills.create(who, in));
  }

  BackfillRecordInput rec(String key, String digest, OffsetDateTime at) {
    return new BackfillRecordInput(key, digest, "{\"id\":\"" + key + "\"}", at, null);
  }

  BackfillSubmitResult submit(AccessService.Access who, UUID taskId, List<BackfillRecordInput> rs) {
    return as(
        who, "channel.sync.complete", () -> backfills.submit(who, taskId, new BackfillSubmitInput(rs, false, null, null)));
  }

  int ledgerRows(AccessService.Access who, UUID conn, String externalKey) {
    return as(
        who,
        "channel.sync.read",
        () -> {
          var row =
              sql.fetchOne(
                  "select count(*)::int from backfill_record where tenant_id=? and connection_id=?"
                      + " and resource_type='ORDER' and external_key=?",
                  who.tenantId(),
                  conn,
                  externalKey);
          return row == null ? 0 : row.get(0, Integer.class);
        });
  }

  // ------------------------------------------------------------------
  // 铁律一：补拉必须幂等
  // ------------------------------------------------------------------

  @Test
  void backfillingTheSameExternalRecordTwiceIsDeduplicatedNotRecreated() {
    UUID conn = connection(actorA);
    String key = "AMZ-ORD-" + UUID.randomUUID();
    UUID task1 = createTask(actorA, conn, "idem-1-" + UUID.randomUUID());
    OffsetDateTime at = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(30);

    // 第一次补拉：新增
    BackfillSubmitResult first =
        submit(actorA, task1, List.of(rec(key, "digest-A", at)));
    assertThat(first.created()).isEqualTo(1);
    assertThat(first.deduplicated()).isZero();

    // 第二次补拉同一条记录（同内容）：必须是去重，不是新建
    UUID task2 = createTask(actorA, conn, "idem-2-" + UUID.randomUUID());
    BackfillSubmitResult second =
        submit(actorA, task2, List.of(rec(key, "digest-A", at)));
    assertThat(second.deduplicated())
        .as("同一条外部记录补拉两次，第二次必须被判为去重而不是新增")
        .isEqualTo(1);
    assertThat(second.created()).as("第二次补拉不得产生新增").isZero();

    // 台账里仍然只有一行 —— 幂等不是靠计数，是靠没有第二行业务记录
    assertThat(ledgerRows(actorA, conn, key)).isEqualTo(1);
  }

  @Test
  void refetchingTheSameRecordWithChangedContentIsAnUpdateNotASecondRow() {
    UUID conn = connection(actorA);
    String key = "AMZ-ORD-" + UUID.randomUUID();
    UUID task1 = createTask(actorA, conn, "upd-1-" + UUID.randomUUID());
    OffsetDateTime at = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(30);

    submit(actorA, task1, List.of(rec(key, "digest-A", at)));

    // 平台改了这条记录：指纹变了 → 更新，**仍然不新增行**
    UUID task2 = createTask(actorA, conn, "upd-2-" + UUID.randomUUID());
    BackfillSubmitResult second =
        submit(actorA, task2, List.of(rec(key, "digest-B", at)));
    assertThat(second.updated())
        .as("指纹变化应判为更新，与去重必须分得开")
        .isEqualTo(1);
    assertThat(second.deduplicated()).isZero();
    assertThat(second.created()).isZero();

    assertThat(ledgerRows(actorA, conn, key)).isEqualTo(1);
  }

  @Test
  void theSameRecordBackfilledManyTimesStillKeepsOneLedgerRow() {
    UUID conn = connection(actorA);
    String key = "AMZ-ORD-" + UUID.randomUUID();
    OffsetDateTime at = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(30);

    for (int i = 0; i < 5; i++) {
      UUID task = createTask(actorA, conn, "many-" + i + "-" + UUID.randomUUID());
      BackfillSubmitResult r = submit(actorA, task, List.of(rec(key, "digest-A", at)));
      if (i == 0) assertThat(r.created()).isEqualTo(1);
      else assertThat(r.deduplicated()).isEqualTo(1);
    }

    assertThat(ledgerRows(actorA, conn, key))
        .as("补拉五次仍只有一条台账记录")
        .isEqualTo(1);
  }

  // ------------------------------------------------------------------
  // 铁律二：分段轮询 + 「补拉可能延迟发现」
  // ------------------------------------------------------------------

  @Test
  void aRecentNScanPlatformIsMarkedAsPossiblyDelayedInDiscovery() {
    UUID conn = connection(actorA);
    OffsetDateTime end = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(5);
    BackfillTaskInput in =
        new BackfillTaskInput(
            conn, null, "ORDER", end.minusHours(3), end, BackfillTrigger.MANUAL,
            "scan-" + UUID.randomUUID(), null, PaginationMode.RECENT_N_SCAN,
            // 调用方刻意谎报「不会延迟发现」
            false, 100, 20, 60);

    UUID id = as(actorA, "channel.sync.trigger", () -> backfills.create(actorA, in));
    BackfillTaskView task = as(actorA, "channel.sync.read", () -> backfills.task(actorA, id));

    assertThat(task.paginationMode()).isEqualTo("RECENT_N_SCAN");
    assertThat(task.delayedDiscovery())
        .as("平台只给最近 N 条时必须标明「补拉可能延迟发现」，不能让假完整写进库")
        .isTrue();
  }

  @Test
  void segmentedPollingRecordsEachSegmentAndKeepsDeduplicationAsTheBackstop() {
    UUID conn = connection(actorA);
    OffsetDateTime end = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(5);
    String key = "AMZ-ORD-" + UUID.randomUUID();

    BackfillTaskInput in =
        new BackfillTaskInput(
            conn, null, "ORDER", end.minusHours(3), end, BackfillTrigger.PLATFORM_RECOVERY,
            "seg-" + UUID.randomUUID(), null, PaginationMode.RECENT_N_SCAN, true, 100, 20, 60);
    UUID id = as(actorA, "channel.sync.trigger", () -> backfills.create(actorA, in));

    OffsetDateTime at = end.minusHours(1);
    // 第一段：拿到这条记录 → 新增
    BackfillSubmitResult s1 =
        as(
            actorA,
            "channel.sync.complete",
            () ->
                backfills.submit(
                    actorA,
                    id,
                    new BackfillSubmitInput(
                        List.of(rec(key, "digest-A", at)), false, at.minusMinutes(30), false)));
    assertThat(s1.created()).isEqualTo(1);

    // 第二段：同样的记录又出现（平台推新数据把它顶回「最近 N 条」）→ 靠去重兜底
    BackfillSubmitResult s2 =
        as(
            actorA,
            "channel.sync.complete",
            () ->
                backfills.submit(
                    actorA,
                    id,
                    new BackfillSubmitInput(
                        List.of(rec(key, "digest-A", at)), false, at.minusHours(2), true)));
    assertThat(s2.deduplicated())
        .as("分段轮询里重复出现的记录必须靠去重兜底，不产生新增")
        .isEqualTo(1);
    assertThat(s2.created()).isZero();

    var segments = as(actorA, "channel.sync.read", () -> backfills.segments(actorA, id));
    assertThat(segments).hasSize(2);
    // 水位逐段向窗口起点推进 —— 这正是「往回够」的证据
    assertThat(segments.get(0).get("covered_from", Instant.class))
        .isAfter(segments.get(1).get("covered_from", Instant.class));
    assertThat(segments.get(1).get("reached_window_start", Boolean.class)).isTrue();

    assertThat(ledgerRows(actorA, conn, key)).isEqualTo(1);
  }

  // ------------------------------------------------------------------
  // 铁律三：四个数字分开记
  // ------------------------------------------------------------------

  @Test
  void fetchedCreatedUpdatedAndDeduplicatedAreCountedSeparately() {
    UUID conn = connection(actorA);
    OffsetDateTime end = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(5);
    BackfillTaskInput in =
        new BackfillTaskInput(
            conn, null, "ORDER", end.minusHours(2), end, BackfillTrigger.WEBHOOK_GAP,
            "counts-" + UUID.randomUUID(), null, PaginationMode.TIME_INCREMENTAL, false, 100, 20, 60);
    UUID id = as(actorA, "channel.sync.trigger", () -> backfills.create(actorA, in));

    OffsetDateTime t1 = end.minusMinutes(90), t2 = end.minusMinutes(80), t3 = end.minusMinutes(70);
    String k1 = "AMZ-ORD-" + UUID.randomUUID(), k2 = "AMZ-ORD-" + UUID.randomUUID();

    // 第一批：一条新增、一条失败
    BackfillSubmitResult s1 =
        as(
            actorA,
            "channel.sync.complete",
            () ->
                backfills.submit(
                    actorA,
                    id,
                    new BackfillSubmitInput(
                        List.of(
                            rec(k1, "digest-A", t1),
                            rec(k2, "digest-B", t2),
                            new BackfillRecordInput(
                                "AMZ-BAD-" + UUID.randomUUID(), null, null, t2, "PLATFORM_ERROR")),
                        false,
                        null,
                        null)));
    assertThat(s1.fetched()).isEqualTo(3);
    assertThat(s1.created()).isEqualTo(2);
    assertThat(s1.failed()).isEqualTo(1);

    // 第二批：k1 指纹未变（去重）、k2 指纹变了（更新）
    BackfillSubmitResult s2 =
        as(
            actorA,
            "channel.sync.complete",
            () ->
                backfills.submit(
                    actorA,
                    id,
                    new BackfillSubmitInput(
                        List.of(rec(k1, "digest-A", t1), rec(k2, "digest-NEW", t3)),
                        false,
                        null,
                        null)));
    assertThat(s2.fetched()).isEqualTo(2);
    assertThat(s2.deduplicated()).isEqualTo(1);
    assertThat(s2.updated()).isEqualTo(1);

    // 任务头：四个数字独立可查，且拉取 = 新增+更新+去重+失败
    BackfillTaskView task = as(actorA, "channel.sync.read", () -> backfills.task(actorA, id));
    assertThat(task.fetchedCount()).isEqualTo(5);
    assertThat(task.createdCount()).isEqualTo(2);
    assertThat(task.updatedCount()).isEqualTo(1);
    assertThat(task.deduplicatedCount()).isEqualTo(1);
    assertThat(task.failedCount()).isEqualTo(1);
    assertThat(task.fetchedCount())
        .isEqualTo(
            task.createdCount() + task.updatedCount() + task.deduplicatedCount() + task.failedCount());
  }

  // ------------------------------------------------------------------
  // 铁律四：幂等键是入参契约
  // ------------------------------------------------------------------

  @Test
  void theSameIdempotencyKeyDoesNotCreateASecondBackfillTask() {
    UUID conn = connection(actorA);
    String key = "gap-detect-" + UUID.randomUUID();

    UUID first = createTask(actorA, conn, key);
    UUID second = createTask(actorA, conn, key);
    assertThat(second).as("相同 idempotency_key 重复发起不得产生第二个补拉任务").isEqualTo(first);

    long count =
        as(
            actorA,
            "channel.sync.read",
            () -> {
              var row =
                  sql.fetchOne(
                      "select count(*)::bigint from backfill_task where tenant_id=? and idempotency_key=?",
                      actorA.tenantId(),
                      key);
              return row.get(0, Long.class);
            });
    assertThat(count).isEqualTo(1);
  }

  // ------------------------------------------------------------------
  // 时间窗：边界是本模块的核心事实
  // ------------------------------------------------------------------

  @Test
  void theBackfillWindowIsPersistedWithExactBoundariesNotShiftedByTimezone() {
    UUID conn = connection(actorA);
    // 刻意用一个非整点、带秒的时间，避免"看起来对"的假象
    OffsetDateTime start = OffsetDateTime.of(2026, 3, 14, 15, 9, 26, 535_000_000, ZoneOffset.UTC);
    OffsetDateTime end = start.plusHours(2);
    BackfillTaskInput in =
        new BackfillTaskInput(
            conn, null, "ORDER", start, end, BackfillTrigger.MANUAL,
            "win-" + UUID.randomUUID(), null, PaginationMode.TIME_INCREMENTAL, false, 100, 20, 60);

    UUID id = as(actorA, "channel.sync.trigger", () -> backfills.create(actorA, in));
    BackfillTaskView task = as(actorA, "channel.sync.read", () -> backfills.task(actorA, id));

    // 断言**写回的值**：timestamptz 绑错会静默偏 −28800s，
    // 偏 8 小时的补拉窗口等于漏补或重补整整一段。
    assertThat(task.windowStart()).isEqualTo(start.toInstant());
    assertThat(task.windowEnd()).isEqualTo(end.toInstant());
  }

  @Test
  void anInvertedOrZeroLengthWindowIsRejected() {
    UUID conn = connection(actorA);
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    BackfillTaskInput inverted =
        new BackfillTaskInput(
            conn, null, "ORDER", now.minusHours(1), now.minusHours(2), BackfillTrigger.MANUAL,
            "inv-" + UUID.randomUUID(), null, PaginationMode.TIME_INCREMENTAL, false, 100, 20, 60);
    assertThatThrownBy(() -> as(actorA, "channel.sync.trigger", () -> backfills.create(actorA, inverted)))
        .hasMessageContaining("右端必须晚于左端");

    BackfillTaskInput empty =
        new BackfillTaskInput(
            conn, null, "ORDER", now.minusHours(1), now.minusHours(1), BackfillTrigger.MANUAL,
            "zero-" + UUID.randomUUID(), null, PaginationMode.TIME_INCREMENTAL, false, 100, 20, 60);
    assertThatThrownBy(() -> as(actorA, "channel.sync.trigger", () -> backfills.create(actorA, empty)))
        .hasMessageContaining("右端必须晚于左端");
  }

  // ------------------------------------------------------------------
  // 分页上限与收尾
  // ------------------------------------------------------------------

  @Test
  void aBatchLargerThanTheDeclaredPageSizeIsRejected() {
    UUID conn = connection(actorA);
    OffsetDateTime end = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(5);
    BackfillTaskInput in =
        new BackfillTaskInput(
            conn, null, "ORDER", end.minusHours(2), end, BackfillTrigger.MANUAL,
            "cap-" + UUID.randomUUID(), null, PaginationMode.TIME_INCREMENTAL, false, 2, 20, 60);
    UUID id = as(actorA, "channel.sync.trigger", () -> backfills.create(actorA, in));

    List<BackfillRecordInput> tooMany =
        List.of(
            rec("AMZ-" + UUID.randomUUID(), "d", end.minusMinutes(50)),
            rec("AMZ-" + UUID.randomUUID(), "d", end.minusMinutes(49)),
            rec("AMZ-" + UUID.randomUUID(), "d", end.minusMinutes(48)));

    assertThatThrownBy(
            () ->
                as(
                    actorA,
                    "channel.sync.complete",
                    () -> backfills.submit(actorA, id, new BackfillSubmitInput(tooMany, false, null, null))))
        .hasMessageContaining("超过平台分页上限");
  }

  /** 登记一条 ORDER 游标（2.4.1.3），补拉成功后按窗口末尾推进它。 */
  UUID orderCursor(AccessService.Access who, UUID conn) {
    return as(
        who,
        "channel.sync.create",
        () -> sync.registerCursor(who, new ChannelModels.SyncCursorInput(conn, null, "ORDER", "default")));
  }

  String cursorValue(AccessService.Access who, UUID cursorId) {
    return as(
        who,
        "channel.sync.read",
        () -> sql.fetchOne("select cursor_value from sync_cursor where id=?", cursorId).get(0, String.class));
  }

  @Test
  void aFailedBackfillDoesNotAdvanceTheCursor() {
    UUID conn = connection(actorA);
    UUID cursorId = orderCursor(actorA, conn);
    String before = cursorValue(actorA, cursorId);

    OffsetDateTime end = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(5);
    BackfillTaskInput in =
        new BackfillTaskInput(
            conn, null, "ORDER", end.minusHours(2), end, BackfillTrigger.CURSOR_REWIND,
            "adv-" + UUID.randomUUID(), cursorId, PaginationMode.TIME_INCREMENTAL, false, 100, 20, 60);
    UUID id = as(actorA, "channel.sync.trigger", () -> backfills.create(actorA, in));

    as(
        actorA,
        "channel.sync.complete",
        () -> {
          backfills.complete(actorA, id, new BackfillCompleteInput(BackfillStatus.FAILED, "9999", "平台超时", 1200));
          return null;
        });

    assertThat(cursorValue(actorA, cursorId))
        .as("补拉失败时游标必须保持原位，否则那段数据永久丢失")
        .isEqualTo(before);
  }

  @Test
  void aSuccessfulBackfillAdvancesTheLinkedCursor() {
    UUID conn = connection(actorA);
    UUID cursorId = orderCursor(actorA, conn);

    OffsetDateTime end = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(5);
    BackfillTaskInput in =
        new BackfillTaskInput(
            conn, null, "ORDER", end.minusHours(2), end, BackfillTrigger.CURSOR_REWIND,
            "ok-" + UUID.randomUUID(), cursorId, PaginationMode.TIME_INCREMENTAL, false, 100, 20, 60);
    UUID id = as(actorA, "channel.sync.trigger", () -> backfills.create(actorA, in));

    as(
        actorA,
        "channel.sync.complete",
        () -> {
          backfills.complete(
              actorA, id, new BackfillCompleteInput(BackfillStatus.SUCCEEDED, end.toInstant().toString(), "", 900));
          return null;
        });

    assertThat(cursorValue(actorA, cursorId)).isEqualTo(end.toInstant().toString());
  }

  @Test
  void aSettledTaskCannotBeRewritten() {
    UUID conn = connection(actorA);
    UUID id = createTask(actorA, conn, "settle-" + UUID.randomUUID());
    as(
        actorA,
        "channel.sync.complete",
        () -> {
          backfills.complete(actorA, id, new BackfillCompleteInput(BackfillStatus.SUCCEEDED, "c1", "", 10));
          return null;
        });

    // 已终结的任务不可再改写（可逆性靠补新任务冲销，不靠改历史）
    assertThatThrownBy(
            () ->
                as(
                    actorA,
                    "channel.sync.complete",
                    () -> {
                      backfills.complete(
                          actorA, id, new BackfillCompleteInput(BackfillStatus.FAILED, "c2", "", 20));
                      return null;
                    }))
        .hasMessageContaining("已终结");
  }

  // ------------------------------------------------------------------
  // 端点与脱敏
  // ------------------------------------------------------------------

  @Test
  void theHttpEndpointsExposeTheBackfillTaskAndItsFourNumbers() throws Exception {
    UUID conn = connection(actorA);
    String key = "AMZ-ORD-" + UUID.randomUUID();
    OffsetDateTime end = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(5);
    BackfillTaskInput in =
        new BackfillTaskInput(
            conn, null, "ORDER", end.minusHours(2), end, BackfillTrigger.MANUAL,
            "http-" + UUID.randomUUID(), null, PaginationMode.RECENT_N_SCAN, true, 100, 20, 60);

    String taskJson =
        new com.fasterxml.jackson.databind.ObjectMapper()
            .writeValueAsString(
                Map.of(
                    "connectionId", conn.toString(),
                    "resourceType", "ORDER",
                    "windowStart", end.minusHours(2).toString(),
                    "windowEnd", end.toString(),
                    "triggerSource", "MANUAL",
                    "idempotencyKey", in.idempotencyKey(),
                    "paginationMode", "RECENT_N_SCAN",
                    "delayedDiscovery", true));
    String created =
        http.perform(
                post("/channel/backfills")
                    .header("Authorization", "Bearer tenant-a")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(taskJson))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    UUID id = UUID.fromString(created.replace("\"", ""));

    String submitJson =
        new com.fasterxml.jackson.databind.ObjectMapper()
            .writeValueAsString(
                Map.of(
                    "records",
                    List.of(
                        Map.of(
                            "externalKey", key,
                            "payloadDigest", "digest-A",
                            "payload",
                            "{\"id\":\"" + key + "\",\"access_token\":\"plaintext-token-must-not-persist\"}",
                            "externalOccurredAt", end.minusMinutes(30).toString()))));
    http.perform(
            post("/channel/backfills/" + id + "/records")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(submitJson))
        .andExpect(status().isOk());

    http.perform(get("/channel/backfills/" + id).header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.createdCount").value(1))
        .andExpect(jsonPath("$.fetchedCount").value(1))
        .andExpect(jsonPath("$.delayedDiscovery").value(true));

    // AGENTS.md：明文凭据不入库
    String stored =
        as(
            actorA,
            "channel.sync.read",
            () ->
                sql.fetchOne(
                        "select payload::text from backfill_record where tenant_id=? and external_key=?",
                        actorA.tenantId(),
                        key)
                    .get(0, String.class));
    assertThat(stored).doesNotContain("plaintext-token-must-not-persist");
    assertThat(stored).contains("***");
  }

  @Test
  void anUnauthenticatedRequestIsRejected() throws Exception {
    http.perform(get("/channel/backfills").header("Authorization", "Bearer nobody"))
        .andExpect(status().isUnauthorized());
  }

  // ------------------------------------------------------------------
  // 多租户三断言
  // ------------------------------------------------------------------

  @Test
  void tenantsCannotSeeEachOthersBackfillTasks() {
    UUID connA = connection(actorA);
    String key = "ten-iso-" + UUID.randomUUID();
    UUID taskA = createTask(actorA, connA, key);

    // B 看不到 A 的补拉任务
    var mineB = as(actorB, "channel.sync.read", () -> backfills.tasks(actorB, null, null, 200));
    assertThat(mineB).noneMatch(t -> t.id().equals(taskA));

    // B 直接按 id 取也取不到（RLS 让查询落到 0 行）
    assertThatThrownBy(() -> as(actorB, "channel.sync.read", () -> backfills.task(actorB, taskA)))
        .hasMessageContaining("补拉任务不存在");
  }

  @Test
  void aForgedTenantHeaderCannotReadAnotherTenantsBackfillTasks() throws Exception {
    UUID connA = connection(actorA);
    UUID taskA = createTask(actorA, connA, "forged-" + UUID.randomUUID());
    String externalKey = "AMZ-ORD-" + UUID.randomUUID();
    OffsetDateTime at = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(30);
    submit(actorA, taskA, List.of(rec(externalKey, "d", at)));

    // 带伪造 X-Tenant-Id 访问：租户只来自已认证成员关系，头一律不信
    http.perform(
            get("/channel/backfills/" + taskA)
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a.toString()))
        .andExpect(status().isNotFound());

    // 集合读也必须只返回 B 自己的数据：B 自己也有补拉任务，所以断言的是
    // 「不含 A 的那条」，而不是「列表为空」—— 后者会因为别的用例留下 B 的数据而误判。
    String body =
        http.perform(
                get("/channel/backfills")
                    .header("Authorization", "Bearer tenant-b")
                    .header("X-Tenant-Id", a.toString()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(body).doesNotContain(taskA.toString());
    assertThat(body).doesNotContain(connA.toString());
  }

  @Test
  void theConnectionPoolDoesNotLeakTheTenantAfterBeingReturned() {
    UUID connA = connection(actorA);
    UUID connB = connection(actorB);
    String keyA = "pool-A-" + UUID.randomUUID();
    String keyB = "pool-B-" + UUID.randomUUID();

    UUID taskA = createTask(actorA, connA, keyA);
    submit(actorA, taskA, List.of(rec("AMZ-" + UUID.randomUUID(), "d", OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(20))));

    // 池大小为 1：B 的请求紧跟在 A 之后，必然复用同一条连接。
    // 若 RLS 会话变量没随事务归还，B 会看到 A 的台账
    UUID taskB = createTask(actorB, connB, keyB);
    submit(actorB, taskB, List.of(rec("AMZ-" + UUID.randomUUID(), "d", OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(20))));

    assertThat(as(actorB, "channel.sync.read", () -> backfills.tasks(actorB, null, null, 200)))
        .noneMatch(t -> t.id().equals(taskA));
    assertThat(ledgerRows(actorB, connB, "AMZ-does-not-exist")).isZero();
    assertThat(as(actorA, "channel.sync.read", () -> backfills.records(actorA, taskA, 50))).isNotEmpty();
  }

  @Test
  void oneTenantCannotSubmitIntoAnotherTenantsBackfillTask() {
    UUID connA = connection(actorA);
    UUID taskA = createTask(actorA, connA, "cross-" + UUID.randomUUID());

    // 用 B 的身份向 A 的任务提交：RLS 让更新落到 0 行
    assertThatThrownBy(
            () ->
                as(
                    actorB,
                    "channel.sync.complete",
                    () ->
                        backfills.submit(
                            actorB,
                            taskA,
                            new BackfillSubmitInput(
                                List.of(
                                    rec("AMZ-X-" + UUID.randomUUID(), "d",
                                        OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(10))),
                                false,
                                null,
                                null))))
        .hasMessageContaining("补拉任务不存在");
  }
}
