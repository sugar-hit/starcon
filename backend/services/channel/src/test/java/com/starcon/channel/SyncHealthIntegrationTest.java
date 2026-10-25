package com.starcon.channel;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 1.5.1.1 同步与 API 健康的集成测试（**真实 PostgreSQL 16**）。
 *
 * <p>🔴 这组用例守的是本模块最容易做丢的九件事：
 *
 * <ol>
 *   <li><b>红黄绿规则逐条真的生效</b>：每个连接状态枚举值各一条断言（不是「大概是这样」）；</li>
 *   <li><b>「读不到」与「没有连接」分得开</b>：没有连接的平台进
 *       {@code platformsWithoutConnection}，**不是**变成一张绿卡片；</li>
 *   <li><b>限流真的按 {@code reason='RATE_LIMITED'} 统计</b>，且限流是黄不是红；</li>
 *   <li><b>积压真的等于 {@code outbox_event.published_at IS NULL} 的行数</b>
 *       （另外三张队列表逐张表分别报，不合成一个来源不明的数）；</li>
 *   <li><b>失败计数同时报近窗口与累计两个口径</b>；</li>
 *   <li><b>两个「最近同步时刻」分开</b>，并说清当前用的是哪一个；</li>
 *   <li><b>时间字段写回值断言</b>（非整点时刻，绑错会静默偏 −28800s）；</li>
 *   <li><b>需处理项只给真实存在的</b>，落点必须是**真实路由**；</li>
 *   <li><b>本模块只读</b>：没有任何写端点（PUT / POST 一律 405）。</li>
 * </ol>
 *
 * <p>🔴 <b>本类所有时间基准一律取数据库自己的时钟</b>（{@link #dbNow()}）：服务端判定时刻取的是
 * {@code select now()}（PG 的 transaction_timestamp），测试若拿客户端墙钟播种，等于假设两台钟准得
 * 一模一样 —— 差 1 毫秒就足以让「偏旧 25 小时」被截断成 24 小时而判绿。
 */
@SpringBootTest
@AutoConfigureMockMvc
class SyncHealthIntegrationTest {

  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID user = UUID.randomUUID();
  static HttpServer identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static final ObjectMapper JSON = new ObjectMapper();

  static final UUID AMAZON = UUID.fromString("11111111-1111-1111-1111-111111111101");
  static final UUID SHOPIFY = UUID.fromString("11111111-1111-1111-1111-111111111102");

  // ================================================================ 时钟错位开关（默认关闭）
  //
  // 只为「两台钟错开也必须绿」这一件事存在。正常 CI 上是 0，不产生任何等待：
  //
  //   dbClockSkewSeconds 把**数据库容器自己的墙上时钟**整体拨动 N 秒（正数 = 拨快）。
  //                  PG 的 now() 取的是容器的系统时钟 ⇒ 拨动它就是真的拨动服务端判定用的那个钟，
  //                  不是模拟。用来证明「客户端墙钟与数据库时钟的相对位置」不再影响结论。
  //                  date -s 需要 CAP_SYS_TIME ⇒ 只有开关打开时才把容器提权。
  static final long DB_CLOCK_SKEW_SECONDS =
      Long.getLong("starcon.channel.sync.dbClockSkewSeconds", 0L);

  static final String ALL_SCOPE_GRANTS =
      "[{\"permissions\":[\"channel.operation.read\",\"channel.operation.create\","
          + "\"channel.operation.update\",\"channel.operation.delete\","
          + "\"channel.sync.read\",\"channel.sync.trigger\"],\"scopeMode\":\"ALL\"}]";

  static final List<String> allPermissions =
      java.util.stream.Stream.of("registry", "connection", "shop", "sync", "operation")
          .flatMap(
              p ->
                  java.util.stream.Stream.concat(
                      java.util.stream.Stream.of("read", "create", "update", "delete")
                          .map(op -> "channel." + p + "." + op),
                      p.equals("sync")
                          ? java.util.stream.Stream.of("channel.sync.trigger", "channel.sync.complete")
                          : java.util.stream.Stream.empty()))
          .distinct()
          .toList();

  static AccessService.Access actor(UUID uid, UUID tenantId, List<AccessService.Grant> grants) {
    return new AccessService.Access(
        uid, tenantId, "Test", UUID.randomUUID(), null,
        grants.stream().flatMap(g -> g.permissions().stream()).distinct().toList(),
        grants);
  }

  static AccessService.Grant grant(String scope, List<String> permissions) {
    return new AccessService.Grant(UUID.randomUUID(), "TEST", scope, permissions);
  }

  static {
    if (DB_CLOCK_SKEW_SECONDS != 0L) {
      // date -s 要 CAP_SYS_TIME —— 只有开了「时钟错位」开关才提权，正常 CI 不受影响。
      pg.withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withPrivileged(true));
    }
    pg.start();
    skewDbClock();
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
            byte[] body = new ObjectMapper().writeValueAsBytes(who);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      identity.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @BeforeEach
  void freshTenant() {
    tenant = UUID.randomUUID();
    bearer = "Bearer t-" + tenant;
    List<AccessService.Grant> grants = List.of(grant("ALL", allPermissions));
    actors.put(bearer, actor(user, tenant, grants));
    who = actors.get(bearer);
  }

  @AfterAll
  static void 还原数据库时钟() {
    restoreDbClock();
  }

  /**
   * 把**数据库容器自己的墙上时钟**整体拨动 {@link #DB_CLOCK_SKEW_SECONDS} 秒（0 = 不拨）。
   *
   * <p>PG 的 {@code now()} 取的是容器的系统时钟，所以拨动它就是真的拨动服务端判定用的那个钟 ——
   * 这不是模拟一个「相对位置」，是真的让客户端墙钟与数据库时钟错开。
   */
  static void skewDbClock() {
    if (DB_CLOCK_SKEW_SECONDS == 0L) {
      return;
    }
    setContainerClock(Instant.now().getEpochSecond() + DB_CLOCK_SKEW_SECONDS);
  }

  /** 把数据库容器的墙上钟拨回真实时间（跑完还原，别影响同一个 JVM 里的其它用例）。 */
  static void restoreDbClock() {
    if (DB_CLOCK_SKEW_SECONDS == 0L) {
      return;
    }
    setContainerClock(Instant.now().getEpochSecond());
  }

  private static void setContainerClock(long epochSeconds) {
    try {
      Container.ExecResult result = pg.execInContainer("date", "-u", "-s", "@" + epochSeconds);
      if (result.getExitCode() != 0) {
        throw new IllegalStateException(
            "拨动数据库容器时钟失败：" + result.getStdout() + " / " + result.getStderr());
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("拨动数据库容器时钟被打断", e);
    } catch (java.io.IOException e) {
      throw new IllegalStateException("拨动数据库容器时钟失败", e);
    }
  }

  UUID tenant;
  AccessService.Access who;
  String bearer;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "channel_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://localhost:" + identity.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
  }

  @Autowired SyncHealthService service;
  @Autowired AccessService access;
  @Autowired MockMvc http;

  // ==================================================================
  // 辅助
  // ==================================================================

  <T> T as(String permission, Supplier<T> work) {
    return access.run(who, permission, work);
  }

  /** 以超级用户身份落数据（种子用；读断言一律走应用角色 / RLS 上下文）。 */
  void seed(UUID tenantId, String sql, Object... params) {
    try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        PreparedStatement s = c.prepareStatement(sql)) {
      try (var st = c.createStatement()) {
        st.execute("select set_config('app.current_tenant','" + tenantId + "',false)");
        st.execute("select set_config('app.actor','" + user + "',false)");
        st.execute("select set_config('app.department','',false)");
        st.execute("select set_config('app.permission','channel.sync.read',false)");
        st.execute("select set_config('app.grants','" + ALL_SCOPE_GRANTS + "',false)");
      }
      for (int i = 0; i < params.length; i++) {
        s.setObject(i + 1, params[i]);
      }
      s.executeUpdate();
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  static String scalar(String statement, Object... params) {
    try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        PreparedStatement s = c.prepareStatement(statement)) {
      for (int i = 0; i < params.length; i++) {
        s.setObject(i + 1, params[i]);
      }
      try (ResultSet rs = s.executeQuery()) {
        return rs.next() ? rs.getString(1) : null;
      }
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  static long countRows(String statement, Object... params) {
    try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        PreparedStatement s = c.prepareStatement(statement)) {
      for (int i = 0; i < params.length; i++) {
        s.setObject(i + 1, params[i]);
      }
      try (ResultSet rs = s.executeQuery()) {
        return rs.next() ? rs.getLong(1) : 0L;
      }
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * 🔴 <b>同源 DB 时钟</b>：服务端判定时刻取的是 {@code select now()}（PG 的 transaction_timestamp）。
   *
   * <p>测试要播种「多久没同步」就必须用**同一个钟**算基准。拿客户端墙钟当基准，等于假设
   * 两台钟准得一模一样：数据库的钟哪怕只慢 1 毫秒，25 小时的偏旧就被截断成 24 小时
   * （{@code 24 > 24} 不成立）⇒ 判成绿。2026-10-25 的全量验证就是这么红的
   * （expected YELLOW but was GREEN）—— 成败取决于两台钟的相对位置，不是被测行为。
   *
   * <p>播种基准与判定时刻都取这一个钟 ⇒ 两钟相对位置怎么变都不影响结论。
   */
  OffsetDateTime dbNow() {
    try (Connection c =
            DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        PreparedStatement s = c.prepareStatement("select now()")) {
      try (ResultSet rs = s.executeQuery()) {
        rs.next();
        return rs.getObject(1, OffsetDateTime.class).withOffsetSameInstant(ZoneOffset.UTC);
      }
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  UUID seedConnection(String status, boolean syncPaused, OffsetDateTime lastSyncAt) {
    UUID id = UUID.randomUUID();
    seed(
        tenant,
        "insert into channel_connection (id,tenant_id,platform_id,authorized_account,display_name,status,sync_paused,last_sync_at)"
            + " values (?,?,?,?,?,?,?,?::timestamptz)",
        id,
        tenant,
        AMAZON,
        "acct-" + id.toString().substring(0, 8),
        "连接 " + id.toString().substring(0, 4),
        status,
        syncPaused,
        lastSyncAt);
    return id;
  }

  /** 在指定平台上建一条连接。 */
  UUID seedConnectionOn(UUID platformId, String status, String account) {
    UUID id = UUID.randomUUID();
    seed(
        tenant,
        "insert into channel_connection (id,tenant_id,platform_id,authorized_account,display_name,status)"
            + " values (?,?,?,?,?,?)",
        id,
        tenant,
        platformId,
        account,
        account + " 连接",
        status);
    return id;
  }

  void seedShop(UUID connectionId, String code) {
    seed(
        tenant,
        "insert into shop (id,tenant_id,platform_id,connection_id,code,name) values (?,?,?,?,?,?)",
        UUID.randomUUID(),
        tenant,
        AMAZON,
        connectionId,
        code,
        "店铺 " + code);
  }

  UUID seedCursor(UUID connectionId, String resourceType) {
    UUID id = UUID.randomUUID();
    seed(
        tenant,
        "insert into sync_cursor (id,tenant_id,connection_id,resource_type) values (?,?,?,?)",
        id,
        tenant,
        connectionId,
        resourceType);
    return id;
  }

  UUID seedRun(
      UUID cursorId,
      String status,
      OffsetDateTime startedAt,
      OffsetDateTime finishedAt,
      Integer durationMs) {
    UUID id = UUID.randomUUID();
    seed(
        tenant,
        "insert into sync_run (id,tenant_id,cursor_id,trigger_source,status,started_at,finished_at,duration_ms)"
            + " values (?,?,?,?,?,?::timestamptz,?::timestamptz,?)",
        id,
        tenant,
        cursorId,
        "SCHEDULER",
        status,
        startedAt,
        finishedAt,
        durationMs);
    return id;
  }

  UUID seedFailure(
      UUID runId, UUID cursorId, String externalKey, String reason, OffsetDateTime occurredAt) {
    UUID id = UUID.randomUUID();
    seed(
        tenant,
        "insert into sync_failure (id,tenant_id,run_id,cursor_id,external_key,resource_type,reason,"
            + " detail,payload_digest,attempts,status,occurred_at)"
            + " values (?,?,?,?,?,?,?,?,?,1,'OPEN',?::timestamptz)",
        id,
        tenant,
        runId,
        cursorId,
        externalKey,
        "ORDER",
        reason,
        "测试失败",
        "",
        occurredAt);
    return id;
  }

  void seedOutbox(boolean published) {
    seed(
        tenant,
        "insert into outbox_event (id,tenant_id,aggregate_id,aggregate_version,event_type,payload,published_at)"
            + " values (?,?,?,1,'ORDER_SYNCED','{}'::jsonb,?::timestamptz)",
        UUID.randomUUID(),
        tenant,
        UUID.randomUUID(),
        published ? dbNow() : null);
  }

  /** 读一张卡片。 */
  SyncHealthModels.SyncHealthConnectionCard card(UUID connectionId) {
    return as(
        "channel.sync.read",
        () -> service.connections(null, null, null, 0, 50).items().stream()
            .filter(c -> c.connectionId().equals(connectionId.toString()))
            .findFirst()
            .orElseThrow());
  }

  // ==================================================================
  // 1. 红黄绿规则逐条
  // ==================================================================

  @Test
  @DisplayName("1.5.1.1 规则：连接状态 EXPIRED ⇒ 红，且带逐条依据")
  void expiredConnectionIsRed() {
    UUID connection = seedConnection("EXPIRED", false, dbNow());
    seedShop(connection, "RED-SHOP");
    var card = card(connection);
    assertThat(card.severity()).isEqualTo("RED");
    assertThat(card.reasons()).extracting(SyncHealthModels.SyncHealthReason::code)
        .contains("CONNECTION_INVALID");
  }

  @Test
  @DisplayName("1.5.1.1 规则：连接状态 REVOKED ⇒ 红")
  void revokedConnectionIsRed() {
    UUID connection = seedConnection("REVOKED", false, dbNow());
    assertThat(card(connection).severity()).isEqualTo("RED");
  }

  @Test
  @DisplayName("1.5.1.1 规则：连接状态 EXPIRING ⇒ 黄")
  void expiringConnectionIsYellow() {
    UUID connection = seedConnection("EXPIRING", false, dbNow());
    assertThat(card(connection).severity()).isEqualTo("YELLOW");
  }

  @Test
  @DisplayName("1.5.1.1 规则：连接状态 PAUSED ⇒ 黄")
  void pausedConnectionIsYellow() {
    UUID connection = seedConnection("PAUSED", false, dbNow());
    assertThat(card(connection).severity()).isEqualTo("YELLOW");
  }

  @Test
  @DisplayName("1.5.1.1 规则：sync_paused=true ⇒ 黄")
  void syncPausedIsYellow() {
    UUID connection = seedConnection("ACTIVE", true, dbNow());
    assertThat(card(connection).severity()).isEqualTo("YELLOW");
    assertThat(card(connection).reasons()).extracting(SyncHealthModels.SyncHealthReason::code)
        .contains("SYNC_PAUSED");
  }

  @Test
  @DisplayName("1.5.1.1 规则：从来没有同步过 ⇒ 黄（不是绿）")
  void neverSyncedIsYellow() {
    UUID connection = seedConnection("ACTIVE", false, null);
    var card = card(connection);
    assertThat(card.severity()).isEqualTo("YELLOW");
    assertThat(card.reasons()).extracting(SyncHealthModels.SyncHealthReason::code)
        .contains("NEVER_SYNCED");
    assertThat(card.effectiveLastSyncAt()).isNull();
    assertThat(card.lastSyncAtSource()).isEqualTo("NONE");
  }

  @Test
  @DisplayName("1.5.1.1 规则：同步停滞超过预警线 ⇒ 黄，超过红线 ⇒ 红")
  void staleSyncEscalates() {
    // 基准取**数据库自己的时钟**（与服务端判定同一个钟），不是客户端墙钟。
    // 两台钟只要差 1 毫秒，25 小时的偏旧就被截断成 24 小时（24 > 24 不成立）⇒ 判绿 ——
    // 2026-10-25 的全量验证就是这么红的（expected YELLOW but was GREEN）：
    // 成败取决于两台钟的相对位置，不是被测行为。
    var base = dbNow();
    // 播种的偏旧时长与阈值**拉开余量**：预警线 24 小时、红线 72 小时，这里分别播种
    // 30 小时与 90 小时 —— 不踩截断边界，两钟再错也还在同一档。
    UUID warning = seedConnection("ACTIVE", false, base.minusHours(30));
    UUID critical = seedConnection("ACTIVE", false, base.minusHours(90));

    var warningCard = card(warning);
    assertThat(warningCard.severity()).isEqualTo("YELLOW");
    assertThat(warningCard.reasons()).extracting(SyncHealthModels.SyncHealthReason::code)
        .contains("SYNC_STALE_WARNING");
    assertThat(warningCard.staleHours())
        .as("偏旧小时数必须真的越过预警线，且还没到红线")
        .isGreaterThan(SyncHealthRules.STALE_SYNC_WARN_HOURS)
        .isLessThanOrEqualTo(SyncHealthRules.STALE_SYNC_CRITICAL_HOURS);

    var criticalCard = card(critical);
    assertThat(criticalCard.severity()).isEqualTo("RED");
    assertThat(criticalCard.reasons()).extracting(SyncHealthModels.SyncHealthReason::code)
        .contains("SYNC_STALE_CRITICAL");
    assertThat(criticalCard.staleHours())
        .as("偏旧小时数必须真的越过红线")
        .isGreaterThan(SyncHealthRules.STALE_SYNC_CRITICAL_HOURS);
  }

  @Test
  @DisplayName("1.5.1.1 规则：最近一次运行 FAILED ⇒ 红，PARTIAL ⇒ 黄")
  void lastRunStatusDecides() {
    OffsetDateTime now = dbNow();
    UUID failedConnection = seedConnection("ACTIVE", false, now.minusHours(1));
    UUID failedCursor = seedCursor(failedConnection, "ORDER");
    seedRun(failedCursor, "FAILED", now.minusHours(2), now.minusHours(1), 60000);

    UUID partialConnection = seedConnection("ACTIVE", false, now.minusHours(1));
    UUID partialCursor = seedCursor(partialConnection, "ORDER");
    seedRun(partialCursor, "PARTIAL", now.minusHours(2), now.minusHours(1), 60000);

    var failedCard = card(failedConnection);
    assertThat(failedCard.severity()).isEqualTo("RED");
    assertThat(failedCard.reasons()).extracting(SyncHealthModels.SyncHealthReason::code)
        .contains("LAST_RUN_FAILED");
    assertThat(failedCard.lastRunStatus()).isEqualTo("FAILED");

    var partialCard = card(partialConnection);
    assertThat(partialCard.severity()).isEqualTo("YELLOW");
    assertThat(partialCard.reasons()).extracting(SyncHealthModels.SyncHealthReason::code)
        .contains("LAST_RUN_INCOMPLETE");
  }

  @Test
  @DisplayName("1.5.1.1 规则：一切正常 ⇒ 绿，且给一条 ALL_CLEAR 依据")
  void healthyConnectionIsGreen() {
    OffsetDateTime now = dbNow();
    UUID connection = seedConnection("ACTIVE", false, now.minusHours(1));
    UUID cursor = seedCursor(connection, "ORDER");
    seedRun(cursor, "SUCCEEDED", now.minusHours(2), now.minusHours(1), 1234);

    var card = card(connection);
    assertThat(card.severity()).isEqualTo("GREEN");
    assertThat(card.reasons()).extracting(SyncHealthModels.SyncHealthReason::code)
        .containsExactly("ALL_CLEAR");
  }

  // ==================================================================
  // 2. 限流真的按 reason='RATE_LIMITED' 统计
  // ==================================================================

  @Test
  @DisplayName("1.5.1.1 限流命中真的按 reason='RATE_LIMITED' 统计，且限流是黄不是红")
  void rateLimitedIsCountedByReason() {
    OffsetDateTime now = dbNow();
    UUID connection = seedConnection("ACTIVE", false, now.minusHours(1));
    UUID cursor = seedCursor(connection, "ORDER");
    UUID run = seedRun(cursor, "SUCCEEDED", now.minusHours(2), now.minusHours(1), 1000);
    seedFailure(run, cursor, "rate-1", "RATE_LIMITED", now.minusHours(3));
    seedFailure(run, cursor, "rate-2", "RATE_LIMITED", now.minusHours(4));
    seedFailure(run, cursor, "other-1", "NETWORK_ERROR", now.minusHours(5));

    var card = card(connection);
    assertThat(card.rateLimitedCountWindow()).as("限流命中数").isEqualTo(2L);
    assertThat(card.rateLimitedCountTotal()).isEqualTo(2L);
    assertThat(card.failureCountWindow()).as("窗口内失败总数（含限流）").isEqualTo(3L);
    assertThat(card.severity()).as("限流是黄不是红").isEqualTo("YELLOW");
    assertThat(card.reasons()).extracting(SyncHealthModels.SyncHealthReason::code)
        .contains("FAILURE_RATE_LIMITED")
        .doesNotContain("FAILURE_AUTHORIZATION_EXPIRED");

    // 逐原因计数里，RATE_LIMITED 单列
    assertThat(card.failureByReason()).anySatisfy(
        entry -> {
          assertThat(entry.reason()).isEqualTo("RATE_LIMITED");
          assertThat(entry.windowCount()).isEqualTo(2L);
          assertThat(entry.totalCount()).isEqualTo(2L);
        });
  }

  @Test
  @DisplayName("1.5.1.1 授权失效失败 ⇒ 红（与限流分开）")
  void authorizationExpiredIsRed() {
    OffsetDateTime now = dbNow();
    UUID connection = seedConnection("ACTIVE", false, now.minusHours(1));
    UUID cursor = seedCursor(connection, "ORDER");
    UUID run = seedRun(cursor, "FAILED", now.minusHours(2), now.minusHours(1), 1000);
    seedFailure(run, cursor, "auth-1", "AUTHORIZATION_EXPIRED", now.minusHours(3));

    var card = card(connection);
    assertThat(card.severity()).isEqualTo("RED");
    assertThat(card.reasons()).extracting(SyncHealthModels.SyncHealthReason::code)
        .contains("FAILURE_AUTHORIZATION_EXPIRED");
  }

  @Test
  @DisplayName("1.5.1.1 失败计数：近窗口与累计两个口径分开报")
  void failureCountHasTwoWindows() {
    OffsetDateTime now = dbNow();
    UUID connection = seedConnection("ACTIVE", false, now.minusHours(1));
    UUID cursor = seedCursor(connection, "ORDER");
    UUID run = seedRun(cursor, "SUCCEEDED", now.minusHours(2), now.minusHours(1), 1000);
    seedFailure(run, cursor, "recent-1", "NETWORK_ERROR", now.minusHours(2));
    seedFailure(run, cursor, "old-1", "NETWORK_ERROR", now.minusHours(40));
    seedFailure(run, cursor, "old-2", "NETWORK_ERROR", now.minusHours(50));

    var card = card(connection);
    assertThat(card.failureCountWindow()).as("近 24 小时").isEqualTo(1L);
    assertThat(card.failureCountTotal()).as("累计").isEqualTo(3L);
  }

  // ==================================================================
  // 3. 积压真的等于 outbox_event.published_at IS NULL 的行数
  // ==================================================================

  @Test
  @DisplayName("1.5.1.1 积压真的等于 outbox_event 未发布的行数，且逐张表分别报")
  void backlogEqualsUnpublishedOutboxRows() {
    seedOutbox(false);
    seedOutbox(false);
    seedOutbox(false);
    seedOutbox(true);

    long expected =
        countRows(
            "select count(*) from outbox_event where tenant_id = ? and published_at is null", tenant);
    assertThat(expected).isEqualTo(3L);

    var summary = as("channel.sync.read", () -> service.summary());
    var outbox =
        summary.backlog().stream()
            .filter(source -> source.table().equals("outbox_event"))
            .findFirst()
            .orElseThrow();
    assertThat(outbox.pendingCount()).isEqualTo(expected);
    assertThat(outbox.pendingCondition()).isEqualTo("published_at IS NULL");

    // 积压来源必须**逐张表**列出，不能只有一个来源不明的总数
    assertThat(summary.backlog())
        .extracting(SyncHealthModels.SyncHealthBacklogSource::table)
        .contains(
            "outbox_event",
            "listing_content_writeback_task",
            "listing_status_action_queue",
            "publish_task");
    assertThat(summary.backlogTotal()).isEqualTo(expected);
  }

  @Test
  @DisplayName("1.5.1.1 已发布的事件不计入积压")
  void publishedOutboxIsNotBacklog() {
    seedOutbox(true);
    var summary = as("channel.sync.read", () -> service.summary());
    var outbox =
        summary.backlog().stream()
            .filter(source -> source.table().equals("outbox_event"))
            .findFirst()
            .orElseThrow();
    assertThat(outbox.pendingCount()).isZero();
  }

  // ==================================================================
  // 4. 「读不到」与「没有连接」分得开
  // ==================================================================

  @Test
  @DisplayName("1.5.1.1 没有连接的平台进 platformsWithoutConnection，不是绿卡片")
  void platformWithoutConnectionIsNotAHealthyCard() {
    // 租户只有一条 Amazon 连接；Shopify 一条都没有
    UUID connection = seedConnection("ACTIVE", false, dbNow());

    var summary = as("channel.sync.read", () -> service.summary());
    assertThat(summary.platformsWithoutConnection())
        .extracting(SyncHealthModels.SyncHealthPlatformGap::platformCode)
        .contains("SHOPIFY");
    var shopify =
        summary.platformsWithoutConnection().stream()
            .filter(gap -> gap.platformCode().equals("SHOPIFY"))
            .findFirst()
            .orElseThrow();
    assertThat(shopify.state()).isEqualTo("NO_CONNECTION");
    assertThat(shopify.message()).contains("还没有建立任何连接");

    // 关键：它**不是**一张健康卡片
    var page = as("channel.sync.read", () -> service.connections(null, null, null, 0, 50));
    assertThat(page.items()).extracting(SyncHealthModels.SyncHealthConnectionCard::platformCode)
        .contains("AMAZON")
        .doesNotContain("SHOPIFY");
    assertThat(page.items()).extracting(SyncHealthModels.SyncHealthConnectionCard::connectionId)
        .containsExactly(connection.toString());
  }

  @Test
  @DisplayName("1.5.1.1 尚未接入的平台标 NOT_INTEGRATED（没接是预期内的，不是缺口）")
  void notIntegratedPlatformIsNotAGap() {
    var summary = as("channel.sync.read", () -> service.summary());
    var wayfair =
        summary.platformsWithoutConnection().stream()
            .filter(gap -> gap.platformCode().equals("WAYFAIR"))
            .findFirst()
            .orElseThrow();
    assertThat(wayfair.state()).isEqualTo("NOT_INTEGRATED");
    assertThat(wayfair.supportStatus()).isEqualTo("PLANNED");
  }

  // ==================================================================
  // 5. 两个「最近同步时刻」分开 + 时间写回值断言
  // ==================================================================

  @Test
  @DisplayName("1.5.1.1 最近同步时刻：连接级与运行级分开报，时间写回值逐字相等（不偏 −28800s）")
  void lastSyncTimesAreDistinctAndRoundTrip() {
    // 刻意用非整点、非零秒、微秒的时刻：绑错会静默偏 −28800s
    OffsetDateTime declared = OffsetDateTime.of(2026, 3, 1, 15, 9, 26, 535_000_000, ZoneOffset.UTC);
    OffsetDateTime finished = OffsetDateTime.of(2026, 3, 1, 16, 8, 27, 123_000_000, ZoneOffset.UTC);

    UUID connection = seedConnection("ACTIVE", false, declared);
    UUID cursor = seedCursor(connection, "ORDER");
    seedRun(cursor, "SUCCEEDED", finished.minusMinutes(3), finished, 3210);

    var card = card(connection);
    assertThat(card.lastSyncAt()).as("连接级声明值").isEqualTo(declared);
    assertThat(card.lastRunFinishedAt()).as("运行级完成时刻").isEqualTo(finished);
    assertThat(card.lastSyncAtSource()).isEqualTo("CONNECTION_DECLARED");
    assertThat(card.lastRunDurationMs()).isEqualTo(3210);

    // 写回值逐字相等（不是「没抛异常」）
    String stored =
        scalar(
            "select to_char(finished_at at time zone 'UTC', 'YYYY-MM-DD HH24:MI:SS.MS')"
                + " from sync_run where tenant_id = ? and cursor_id = ?",
            tenant,
            cursor);
    assertThat(stored).isEqualTo("2026-03-01 16:08:27.123");
  }

  @Test
  @DisplayName("1.5.1.1 连接级声明值为空时，来源标 RUN_FINISHED（不谎称「从来没有同步过」）")
  void runFinishedIsUsedWhenDeclaredIsNull() {
    OffsetDateTime finished = dbNow().minusHours(2);
    UUID connection = seedConnection("ACTIVE", false, null);
    UUID cursor = seedCursor(connection, "ORDER");
    seedRun(cursor, "SUCCEEDED", finished.minusMinutes(1), finished, 10);

    var card = card(connection);
    assertThat(card.lastSyncAt()).isNull();
    assertThat(card.lastSyncAtSource()).isEqualTo("RUN_FINISHED");
    assertThat(card.effectiveLastSyncAt()).isEqualTo(finished);
    assertThat(card.severity()).as("有已完成的运行就不该判「从来没有同步过」").isEqualTo("GREEN");
    assertThat(card.reasons()).extracting(SyncHealthModels.SyncHealthReason::code)
        .doesNotContain("NEVER_SYNCED");
  }

  // ==================================================================
  // 6. 需处理项 + 只读
  // ==================================================================

  @Test
  @DisplayName("1.5.1.1 需处理项只给真实存在的待处理项，落点是真实路由")
  void pendingItemsPointToRealRoutes() {
    OffsetDateTime now = dbNow();
    UUID connection = seedConnection("EXPIRED", false, now.minusHours(1));
    UUID cursor = seedCursor(connection, "ORDER");
    UUID run = seedRun(cursor, "FAILED", now.minusHours(2), now.minusHours(1), 10);
    seedFailure(run, cursor, "f-1", "AUTHORIZATION_EXPIRED", now.minusHours(3));

    var pending = as("channel.sync.read", () -> service.pendingItems());
    assertThat(pending.items()).isNotEmpty();
    for (SyncHealthModels.SyncHealthPendingItem item : pending.items()) {
      assertThat(item.targetRoute())
          .as("落点必须是真实路由（不存在的路由比没有落点更糟）")
          .isIn("/channels", "/system-health");
      assertThat(item.targetModule()).isNotBlank();
      assertThat(item.targetNote()).isNotBlank();
    }
    assertThat(pending.items()).extracting(SyncHealthModels.SyncHealthPendingItem::kind)
        .contains("REAUTHORIZE", "VIEW_SYNC_RUNS", "VIEW_FAILURE_QUEUE");
  }

  @Test
  @DisplayName("1.5.1.1 本模块只读：没有任何写端点")
  void moduleIsReadOnly() throws Exception {
    http.perform(
            post("/channel/sync-health/connections").header("Authorization", bearer)
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isMethodNotAllowed());
    http.perform(
            put("/channel/sync-health/connections").header("Authorization", bearer)
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isMethodNotAllowed());
    http.perform(
            delete("/channel/sync-health/summary").header("Authorization", bearer))
        .andExpect(status().isMethodNotAllowed());
  }

  // ==================================================================
  // 7. 端点形状
  // ==================================================================

  @Test
  @DisplayName("1.5.1.1 总览的连接总数是真实总数，且装载上限被如实标记（不拿前 N 条冒充全量）")
  void summaryReportsRealTotalAndTruncationFlag() {
    for (int i = 0; i < 3; i++) {
      seedConnection("ACTIVE", false, dbNow());
    }
    var summary = as("channel.sync.read", () -> service.summary());
    assertThat(summary.totalConnections()).isEqualTo(3);
    assertThat(summary.cardsTruncated())
        .as("3 条连接远没到装载上限 ⇒ 不该被标成截断")
        .isFalse();

    var pending = as("channel.sync.read", () -> service.pendingItems());
    assertThat(pending.truncated()).isFalse();
  }

  @Test
  @DisplayName("1.5.1.1 能力声明端点公布红黄绿规则表、阈值与积压来源")
  void capabilitiesExposeRuleTable() throws Exception {
    http.perform(get("/channel/sync-health/capabilities").header("Authorization", bearer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rules").isArray())
        .andExpect(jsonPath("$.rules[?(@.code=='CONNECTION_INVALID')].severity").value("RED"))
        .andExpect(jsonPath("$.thresholds.staleSyncWarnHours").value(24))
        .andExpect(jsonPath("$.thresholds.staleSyncCriticalHours").value(72))
        .andExpect(jsonPath("$.thresholds.failureWindowHours").value(24))
        .andExpect(jsonPath("$.backlogSources").isArray())
        .andExpect(jsonPath("$.severityLevels").isArray());
  }

  @Test
  @DisplayName("1.5.1.1 卡片列表是真分页（page / size 真的生效）")
  void connectionListIsReallyPaginated() {
    for (int i = 0; i < 5; i++) {
      seedConnection("ACTIVE", false, dbNow());
    }
    var first = as("channel.sync.read", () -> service.connections(null, null, null, 0, 2));
    assertThat(first.items()).hasSize(2);
    assertThat(first.total()).isEqualTo(5);
    assertThat(first.page()).isZero();

    var second = as("channel.sync.read", () -> service.connections(null, null, null, 1, 2));
    assertThat(second.items()).hasSize(2);
    assertThat(second.total()).isEqualTo(5);
    assertThat(first.items()).extracting(SyncHealthModels.SyncHealthConnectionCard::connectionId)
        .doesNotContainAnyElementsOf(
            second.items().stream()
                .map(SyncHealthModels.SyncHealthConnectionCard::connectionId)
                .toList());
  }

  @Test
  @DisplayName("1.5.1.1 卡片列表是真筛选（平台码 / 状态 / 关键字）")
  void connectionListIsReallyFiltered() {
    UUID amazon = seedConnection("EXPIRED", false, dbNow());
    seedConnectionOn(SHOPIFY, "ACTIVE", "shopify-acct");

    var byPlatform = as("channel.sync.read", () -> service.connections("SHOPIFY", null, null, 0, 50));
    assertThat(byPlatform.items()).hasSize(1);
    assertThat(byPlatform.items().get(0).platformCode()).isEqualTo("SHOPIFY");

    var byStatus = as("channel.sync.read", () -> service.connections(null, "EXPIRED", null, 0, 50));
    assertThat(byStatus.items()).extracting(SyncHealthModels.SyncHealthConnectionCard::connectionId)
        .containsExactly(amazon.toString());

    var byKeyword = as("channel.sync.read", () -> service.connections(null, null, "Shopify", 0, 50));
    assertThat(byKeyword.items()).hasSize(1);
  }

  @Test
  @DisplayName("1.5.1.1 单条卡片与不存在的 id")
  void singleCardAndNotFound() throws Exception {
    UUID connection = seedConnection("ACTIVE", false, dbNow());
    http.perform(get("/channel/sync-health/connections/" + connection).header("Authorization", bearer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.connectionId").value(connection.toString()));
    http.perform(
            get("/channel/sync-health/connections/11111111-2222-3333-4444-555555555555")
                .header("Authorization", bearer))
        .andExpect(status().isNotFound());
    http.perform(get("/channel/sync-health/connections/not-a-uuid").header("Authorization", bearer))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("1.5.1.1 无会话一律 401（租户不来自客户端头）")
  void anonymousIsUnauthorized() throws Exception {
    http.perform(get("/channel/sync-health/summary")).andExpect(status().isUnauthorized());
    http.perform(
            get("/channel/sync-health/summary")
                .header("X-Tenant-Id", UUID.randomUUID().toString()))
        .andExpect(status().isUnauthorized());
  }
}
