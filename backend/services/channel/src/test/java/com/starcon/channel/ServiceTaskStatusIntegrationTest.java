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
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 1.5.2.1 服务与任务状态的集成测试（<b>真实 PostgreSQL 16</b>）。
 *
 * <p>🔴 这组用例守的是本模块最容易做丢的十三件事：
 *
 * <ol>
 *   <li><b>五个任务来源真的都进列表</b>（sync_run / backfill_task / listing_migration_batch /
 *       publish_task / listing_collect_task）—— 少一个就是「半成品」；</li>
 *   <li><b>停滞是真的判出来的</b>：超过预警线 ⇒ 黄、超过红线 ⇒ 红、没超时 ⇒ 不算停滞；</li>
 *   <li><b>「判不了」是独立结论</b>：在跑但没记开始时刻 ⇒ STALL_UNDETERMINED，不是「没卡住」；</li>
 *   <li><b>没有「执行中」这一档的表永远不会被判停滞</b>（批量搬迁 / 链接采集）；</li>
 *   <li><b>失败分类真的分了类</b>：业务 / 系统 / 两类都有 / 判不了，四种都要能出现；</li>
 *   <li><b>批量搬迁没有耗时列 ⇒ 耗时是 null</b>（不许拿 created_at 冒充执行时长）；</li>
 *   <li><b>失败队列的分类计数真的分开了</b>；</li>
 *   <li><b>停滞提示与失败提示是两个独立列表</b>；</li>
 *   <li><b>真分页 + 真筛选 + 真搜索</b>；</li>
 *   <li><b>声明的列真的存在于数据库</b>（注册表不许写错列名）；</li>
 *   <li><b>本模块只读</b>：写方法一律 405；</li>
 *   <li><b>时间值写回逐字相等</b>（非整点微秒时刻，绑错会静默偏 −28800s）；</li>
 *   <li><b>耗时在服务端算好</b>（发布任务那张表没有耗时列，耗时由服务端从两列相减得出）。</li>
 * </ol>
 *
 * <p>🔴 <b>本类所有时间基准一律取数据库自己的时钟</b>（{@link #dbNow()}）：服务端判定时刻取的是
 * {@code select now()}（PG 的 transaction_timestamp），测试若拿客户端墙钟播种，等于假设两台钟准得
 * 一模一样 —— 差 1 毫秒就足以让「跑了 30 分钟」被截断成「没超时」而判绿。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ServiceTaskStatusIntegrationTest {

  static final PostgreSQLContainer<?> pg =
      new PostgreSQLContainer<>("postgres:16-alpine").withUrlParam("sslmode", "disable");
  static final UUID user = UUID.randomUUID();
  static HttpServer identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static final ObjectMapper JSON = new ObjectMapper();

  static final UUID AMAZON = UUID.fromString("11111111-1111-1111-1111-111111111101");
  static final UUID COLLECT_SOURCE = UUID.fromString("6c1d0001-0000-4000-8000-000000000001");

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

  @Autowired ServiceTaskStatusService service;
  @Autowired AccessService access;
  @Autowired MockMvc http;

  // ==================================================================
  // 辅助
  // ==================================================================

  <T> T as(String permission, Supplier<T> work) {
    return access.run(who, permission, work);
  }

  /** 以超级用户身份落数据（种子用；读断言一律走应用角色 / RLS 上下文）。 */
  void seed(String sql, Object... params) {
    try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        PreparedStatement s = c.prepareStatement(sql)) {
      try (var st = c.createStatement()) {
        st.execute("select set_config('app.current_tenant','" + tenant + "',false)");
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

  /** 🔴 同源 DB 时钟：判定时刻与播种基准都取这一个钟。 */
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

  // ==================================================================
  // 种子
  // ==================================================================

  UUID seedConnection() {
    UUID id = UUID.randomUUID();
    seed(
        "insert into channel_connection (id,tenant_id,platform_id,authorized_account,display_name,status)"
            + " values (?,?,?,?,?,'ACTIVE')",
        id,
        tenant,
        AMAZON,
        "acct-" + id.toString().substring(0, 8),
        "连接 " + id.toString().substring(0, 6));
    return id;
  }

  UUID seedShop(UUID connectionId, String code) {
    UUID id = UUID.randomUUID();
    seed(
        "insert into shop (id,tenant_id,platform_id,connection_id,code,name) values (?,?,?,?,?,?)",
        id,
        tenant,
        AMAZON,
        connectionId,
        code,
        "店铺 " + code);
    return id;
  }

  UUID seedCursor(UUID connectionId) {
    UUID id = UUID.randomUUID();
    seed(
        "insert into sync_cursor (id,tenant_id,connection_id,resource_type) values (?,?,?,'ORDER')",
        id,
        tenant,
        connectionId);
    return id;
  }

  UUID seedRun(
      UUID cursorId, String status, OffsetDateTime startedAt, OffsetDateTime finishedAt, Integer durationMs) {
    UUID id = UUID.randomUUID();
    seed(
        "insert into sync_run (id,tenant_id,cursor_id,trigger_source,status,started_at,finished_at,duration_ms)"
            + " values (?,?,?,'SCHEDULER',?,?::timestamptz,?::timestamptz,?)",
        id,
        tenant,
        cursorId,
        status,
        startedAt,
        finishedAt,
        durationMs);
    return id;
  }

  UUID seedFailure(UUID runId, UUID cursorId, String reason, OffsetDateTime occurredAt) {
    UUID id = UUID.randomUUID();
    seed(
        "insert into sync_failure (id,tenant_id,run_id,cursor_id,external_key,resource_type,reason,"
            + " detail,payload_digest,attempts,status,occurred_at)"
            + " values (?,?,?,?,?, 'ORDER',?,?,'digest',1,'OPEN',?::timestamptz)",
        id,
        tenant,
        runId,
        cursorId,
        "ext-" + id.toString().substring(0, 8),
        reason,
        "集成测试",
        occurredAt);
    return id;
  }

  UUID seedBackfill(
      UUID connectionId, String status, OffsetDateTime startedAt, OffsetDateTime finishedAt, Integer durationMs) {
    UUID id = UUID.randomUUID();
    seed(
        "insert into backfill_task (id,tenant_id,connection_id,resource_type,window_start,window_end,"
            + " trigger_source,idempotency_key,status,started_at,finished_at,duration_ms)"
            + " values (?,?,?,'ORDER',?::timestamptz,?::timestamptz,'MANUAL',?,?,?::timestamptz,?::timestamptz,?)",
        id,
        tenant,
        connectionId,
        dbNow().minusDays(2),
        dbNow().minusDays(1),
        "bf-" + id.toString(),
        status,
        startedAt,
        finishedAt,
        durationMs);
    return id;
  }

  /**
   * 🔴 目标店铺必须**真的存在**：listing_migration_batch 的数据范围策略会数
   * 「在范围内的目标店铺条数」，与 target_shop_ids 的长度比对 ——
   * 塞一个不存在的 UUID 进去，这一行会被 RLS 直接藏起来（测试就会误以为「这一类任务没接上」）。
   */
  UUID seedMigrationBatch(UUID sourceShopId, UUID targetShopId, String state) {
    UUID id = UUID.randomUUID();
    seed(
        "insert into listing_migration_batch (id,tenant_id,source_shop_id,target_shop_ids,idempotency_key,"
            + " state,candidate_count,total_count,migrated_count,skipped_count,failed_count)"
            + " values (?,?,?,array[?]::uuid[],?,?,1,1,1,0,0)",
        id,
        tenant,
        sourceShopId,
        targetShopId,
        "mig-" + id.toString(),
        state);
    return id;
  }

  UUID seedPublishTask(UUID shopId, String state, OffsetDateTime dispatchedAt, OffsetDateTime completedAt) {
    UUID listingId = UUID.randomUUID();
    seed(
        "insert into listing (id,tenant_id,shop_id,external_item_id,title) values (?,?,?,?,?)",
        listingId,
        tenant,
        shopId,
        "ext-" + listingId.toString().substring(0, 8),
        "待发布商品 " + listingId.toString().substring(0, 4));
    UUID batchId = UUID.randomUUID();
    seed(
        "insert into publish_batch (id,tenant_id,listing_ids,limit_count,total_count,pending_count)"
            + " values (?,?,array[?]::uuid[],10,1,1)",
        batchId,
        tenant,
        listingId);
    UUID id = UUID.randomUUID();
    seed(
        "insert into publish_task (id,tenant_id,batch_id,listing_id,platform_id,shop_id,position,state,"
            + " attempts,dispatched_at,completed_at,failure_reason)"
            + " values (?,?,?,?,?,?,0,?,1,?::timestamptz,?::timestamptz,?)",
        id,
        tenant,
        batchId,
        listingId,
        AMAZON,
        shopId,
        state,
        dispatchedAt,
        completedAt,
        "FAILED".equals(state) ? "平台返回 400" : null);
    return id;
  }

  UUID seedCollectTask(String state, int httpStatus) {
    UUID id = UUID.randomUUID();
    seed(
        "insert into listing_collect_task (id,tenant_id,source_id,source_url,normalized_url,final_url,"
            + " host,state,error_message,http_status,attempt_no,started_at,finished_at,captured_at)"
            + " values (?,?,?,?,?,?,?,?,'',?,1,?::timestamptz,?::timestamptz,?::timestamptz)",
        id,
        tenant,
        COLLECT_SOURCE,
        "https://detail.1688.com/offer/" + id.toString() + ".html",
        "https://detail.1688.com/offer/" + id.toString() + ".html",
        "https://detail.1688.com/offer/" + id.toString() + ".html",
        "detail.1688.com",
        state,
        httpStatus,
        dbNow().minusMinutes(5),
        dbNow().minusMinutes(4),
        dbNow().minusMinutes(4));
    return id;
  }

  // ==================================================================
  // 用例
  // ==================================================================

  @Test
  @DisplayName("1.5.2.1 五个任务来源真的都进列表（少一个就是半成品）")
  void everySourceAppearsInTheList() {
    UUID connectionId = seedConnection();
    UUID shopId = seedShop(connectionId, "SHOP-A");
    UUID cursorId = seedCursor(connectionId);
    seedRun(cursorId, "SUCCEEDED", dbNow().minusMinutes(10), dbNow().minusMinutes(9), 60_000);
    seedBackfill(connectionId, "SUCCEEDED", dbNow().minusMinutes(8), dbNow().minusMinutes(7), 60_000);
    seedMigrationBatch(shopId, seedShop(connectionId, "SHOP-A-TARGET"), "EXECUTED");
    seedPublishTask(shopId, "SUCCEEDED", dbNow().minusMinutes(6), dbNow().minusMinutes(5));
    seedCollectTask("SUCCEEDED", 200);

    var page = as("channel.sync.read", () -> service.tasks(null, null, null, null, null, null, 0, 50));
    assertThat(page.items()).extracting(ServiceTaskStatusModels.ServiceTaskStatusTaskCard::sourceKey)
        .containsExactlyInAnyOrder(
            "sync_run", "backfill_task", "listing_migration_batch", "publish_task",
            "listing_collect_task");
    // 🔴 规范化状态要如实反映每张表自己的语义（EXECUTED = 已完成，DISPATCHED = 在途）。
    assertThat(page.items())
        .filteredOn(c -> "listing_migration_batch".equals(c.sourceKey()))
        .singleElement()
        .satisfies(c -> {
          assertThat(c.rawState()).isEqualTo("EXECUTED");
          assertThat(c.state()).isEqualTo("SUCCEEDED");
        });
    // 🔴 原始状态值必须保留 —— 抹掉之后就无法核对「这张表到底怎么记状态的」。
    assertThat(page.items()).extracting(ServiceTaskStatusModels.ServiceTaskStatusTaskCard::rawState)
        .contains("SUCCEEDED");
  }

  @Test
  @DisplayName("1.5.2.1 停滞是真的判出来的：超预警线 ⇒ 黄，超红线 ⇒ 红，没超时 ⇒ 不算停滞")
  void stalledIsDetected() {
    UUID connectionId = seedConnection();
    UUID cursorId = seedCursor(connectionId);
    seedRun(cursorId, "RUNNING", dbNow().minusMinutes(40), null, null);
    seedRun(cursorId, "RUNNING", dbNow().minusMinutes(200), null, null);
    seedRun(cursorId, "RUNNING", dbNow().minusMinutes(5), null, null);

    var page = as("channel.sync.read", () -> service.tasks(null, null, null, null, null, null, 0, 50));
    var cards = page.items();
    assertThat(cards).hasSize(3);

    var warning =
        cards.stream()
            .filter(c -> c.stallMinutes() != null && c.stallMinutes() >= 40 && c.stallMinutes() < 200)
            .findFirst()
            .orElseThrow();
    assertThat(warning.stalled()).isTrue();
    assertThat(warning.stallSeverity()).isEqualTo("YELLOW");
    assertThat(warning.severity()).isEqualTo("YELLOW");
    assertThat(warning.reasons()).extracting(r -> r.code()).contains("STALL_WARNING");

    var critical =
        cards.stream()
            .filter(c -> c.stallMinutes() != null && c.stallMinutes() >= 200)
            .findFirst()
            .orElseThrow();
    assertThat(critical.stalled()).isTrue();
    assertThat(critical.stallSeverity()).isEqualTo("RED");
    assertThat(critical.severity()).isEqualTo("RED");
    assertThat(critical.reasons()).extracting(r -> r.code()).contains("STALL_CRITICAL");

    var healthy =
        cards.stream()
            .filter(c -> c.stallMinutes() != null && c.stallMinutes() < 40)
            .findFirst()
            .orElseThrow();
    assertThat(healthy.stalled()).isFalse();
    assertThat(healthy.severity()).isEqualTo("GREEN");
  }

  @Test
  @DisplayName("1.5.2.1 在跑但没记开始时刻 ⇒ 判不了（不是「没卡住」）")
  void runningWithoutStartedAtIsUndeterminable() {
    UUID connectionId = seedConnection();
    // backfill_task.started_at 可空：状态 RUNNING 但没记开始时刻。
    seedBackfill(connectionId, "RUNNING", null, null, null);

    var page = as("channel.sync.read", () -> service.tasks(null, "RUNNING", null, null, null, null, 0, 50));
    assertThat(page.items()).hasSize(1);
    var card = page.items().get(0);
    assertThat(card.inFlight()).isTrue();
    assertThat(card.stallDeterminable()).isFalse();
    assertThat(card.stalled()).isFalse();
    assertThat(card.stallMinutes()).isNull();
    assertThat(card.severity()).isEqualTo("YELLOW");
    assertThat(card.reasons()).extracting(r -> r.code()).contains("STALL_UNDETERMINED");
  }

  @Test
  @DisplayName("1.5.2.1 没有「执行中」这一档的表永远不会被判停滞（批量搬迁 / 链接采集）")
  void sourcesWithoutInFlightStateAreNeverStalled() {
    UUID connectionId = seedConnection();
    UUID shopId = seedShop(connectionId, "SHOP-B");
    seedMigrationBatch(shopId, seedShop(connectionId, "SHOP-B-TARGET"), "FAILED");
    seedCollectTask("FAILED", 0);

    var page = as("channel.sync.read", () -> service.tasks(null, "FAILED", null, null, null, null, 0, 50));
    assertThat(page.items()).hasSize(2);
    assertThat(page.items()).allMatch(c -> !c.inFlight());
    assertThat(page.items()).allMatch(c -> !c.stalled());
    assertThat(page.items()).allMatch(c -> c.stallMinutes() == null);
    // 🔴 能力声明里必须写清这两张表判不了停滞的理由。
    var capabilities = as("channel.sync.read", () -> service.capabilities());
    assertThat(capabilities.sources())
        .filteredOn(s -> "listing_migration_batch".equals(s.sourceKey()))
        .singleElement()
        .satisfies(s -> {
          assertThat(s.stallSupported()).isFalse();
          assertThat(s.stallNote()).contains("没有「执行中」");
          assertThat(s.durationColumn()).isNull();
        });
    assertThat(capabilities.sources())
        .filteredOn(s -> "listing_collect_task".equals(s.sourceKey()))
        .singleElement()
        .satisfies(s -> assertThat(s.stallSupported()).isFalse());
  }

  @Test
  @DisplayName("1.5.2.1 失败真的分了类：业务 / 系统 / 两类都有")
  void failuresAreClassified() {
    UUID connectionId = seedConnection();
    UUID cursorId = seedCursor(connectionId);

    UUID businessRun = seedRun(cursorId, "FAILED", dbNow().minusMinutes(30), dbNow().minusMinutes(29), 1000);
    seedFailure(businessRun, cursorId, "AUTHORIZATION_EXPIRED", dbNow().minusMinutes(29));

    UUID systemRun = seedRun(cursorId, "FAILED", dbNow().minusMinutes(28), dbNow().minusMinutes(27), 1000);
    seedFailure(systemRun, cursorId, "PLATFORM_ERROR", dbNow().minusMinutes(27));

    UUID mixedRun = seedRun(cursorId, "FAILED", dbNow().minusMinutes(26), dbNow().minusMinutes(25), 1000);
    seedFailure(mixedRun, cursorId, "MAPPING_MISSING", dbNow().minusMinutes(25));
    seedFailure(mixedRun, cursorId, "NETWORK_ERROR", dbNow().minusMinutes(25));

    var page = as("channel.sync.read", () -> service.tasks(null, "FAILED", null, null, null, null, 0, 50));
    assertThat(page.items()).hasSize(3);
    assertThat(page.items())
        .extracting(ServiceTaskStatusModels.ServiceTaskStatusTaskCard::failureClass)
        .containsExactlyInAnyOrder("BUSINESS", "SYSTEM", "MIXED");
    assertThat(page.items())
        .allMatch(c -> c.failed())
        .allMatch(c -> "RED".equals(c.severity()));
    // 🔴 分类的依据必须在数据里说得出来（不是一句文案）。
    var business =
        page.items().stream()
            .filter(c -> "BUSINESS".equals(c.failureClass()))
            .findFirst()
            .orElseThrow();
    assertThat(business.failureClassNote()).contains("业务 / 配置问题");
  }

  @Test
  @DisplayName("1.5.2.1 没有原因词表的来源判不了分类（UNCLASSIFIED），不是「系统故障」")
  void sourceWithoutReasonVocabularyIsUnclassified() {
    UUID connectionId = seedConnection();
    UUID shopId = seedShop(connectionId, "SHOP-C");
    seedPublishTask(shopId, "FAILED", dbNow().minusMinutes(12), dbNow().minusMinutes(11));

    var page = as("channel.sync.read", () -> service.tasks(null, "FAILED", null, null, null, null, 0, 50));
    assertThat(page.items()).hasSize(1);
    var card = page.items().get(0);
    assertThat(card.failureClass()).isEqualTo("UNCLASSIFIED");
    assertThat(card.failureClassNote()).contains("判不了");
    assertThat(card.severity()).isEqualTo("RED");
  }

  @Test
  @DisplayName("1.5.2.1 批量搬迁没有耗时列 ⇒ 耗时与开始时刻都是 null（不拿 created_at 冒充）")
  void migrationBatchHasNoTimingColumns() {
    UUID connectionId = seedConnection();
    UUID shopId = seedShop(connectionId, "SHOP-D");
    seedMigrationBatch(shopId, seedShop(connectionId, "SHOP-D-TARGET"), "PARTIAL");

    var page = as("channel.sync.read", () -> service.tasks(null, "PARTIAL", null, null, null, null, 0, 50));
    assertThat(page.items()).hasSize(1);
    var card = page.items().get(0);
    assertThat(card.durationMs()).isNull();
    assertThat(card.startedAt()).isNull();
    assertThat(card.finishedAt()).isNull();
    assertThat(card.occurredAt()).isNotNull();
    assertThat(card.timingNote()).contains("没有开始 / 结束时刻列");
    assertThat(card.state()).isEqualTo("PARTIAL");
    assertThat(card.severity()).isEqualTo("YELLOW");
  }

  @Test
  @DisplayName("1.5.2.1 耗时在服务端算好（发布任务那张表没有耗时列，由两列相减得出）")
  void durationIsComputedServerSide() {
    UUID connectionId = seedConnection();
    UUID shopId = seedShop(connectionId, "SHOP-E");
    OffsetDateTime dispatched = dbNow().minusMinutes(3).withNano(123_456_000);
    OffsetDateTime completed = dbNow().minusMinutes(2).withNano(123_456_000);
    seedPublishTask(shopId, "SUCCEEDED", dispatched, completed);

    var page = as("channel.sync.read", () -> service.tasks(null, "SUCCEEDED", null, null, null, null, 0, 50));
    assertThat(page.items()).hasSize(1);
    var card = page.items().get(0);
    // 60_000 毫秒 = 60 秒；容差留给数据库的微秒截断。
    assertThat(card.durationMs()).isBetween(59_000L, 61_000L);
  }

  @Test
  @DisplayName("1.5.2.1 时间值写回逐字相等（非整点微秒时刻，绑错会静默偏 −28800s）")
  void timeValuesRoundTripExactly() {
    UUID connectionId = seedConnection();
    UUID cursorId = seedCursor(connectionId);
    // 刻意用非整点、带微秒的时刻：一旦有人用 Timestamp.valueOf(LocalDateTime) 绑定，
    // 会静默偏 −28800 秒 —— 断言必须能看见这个偏移。
    OffsetDateTime started = dbNow().minusHours(2).withNano(123_456_000);
    OffsetDateTime finished = dbNow().minusMinutes(90).withNano(654_321_000);
    UUID runId = seedRun(cursorId, "SUCCEEDED", started, finished, 600_123);

    var page = as("channel.sync.read", () -> service.tasks(null, null, null, null, null, null, 0, 50));
    var card =
        page.items().stream()
            .filter(c -> c.taskId().equals(runId.toString()))
            .findFirst()
            .orElseThrow();
    assertThat(card.startedAt()).isEqualTo(started);
    assertThat(card.finishedAt()).isEqualTo(finished);
    assertThat(card.durationMs()).isEqualTo(600_123L);
  }

  @Test
  @DisplayName("1.5.2.1 总览里失败记录真的按业务 / 系统分开计数")
  void summaryCountsFailureClasses() {
    UUID connectionId = seedConnection();
    UUID cursorId = seedCursor(connectionId);
    UUID run = seedRun(cursorId, "FAILED", dbNow().minusMinutes(30), dbNow().minusMinutes(29), 1000);
    seedFailure(run, cursorId, "AUTHORIZATION_EXPIRED", dbNow().minusMinutes(29));
    seedFailure(run, cursorId, "MAPPING_MISSING", dbNow().minusMinutes(29));
    seedFailure(run, cursorId, "NETWORK_ERROR", dbNow().minusMinutes(29));
    seedFailure(run, cursorId, "RATE_LIMITED", dbNow().minusMinutes(29));

    var summary = as("channel.sync.read", () -> service.summary());
    assertThat(summary.businessFailureTotal()).isEqualTo(2L);
    assertThat(summary.systemFailureTotal()).isEqualTo(2L);
    assertThat(summary.failedRecordTotal()).isEqualTo(4L);
    assertThat(summary.failureByClass())
        .extracting(ServiceTaskStatusModels.ServiceTaskStatusFailureClassCount::failureClass)
        .containsExactly("BUSINESS", "SYSTEM");
    assertThat(summary.failureByClass())
        .filteredOn(c -> "BUSINESS".equals(c.failureClass()))
        .singleElement()
        .satisfies(c -> {
          // 🔴 每一类里**只列属于这一类**的原因 —— 六个值在每类里都列一遍等于没分。
          assertThat(c.byReason())
              .extracting(ServiceTaskStatusModels.ServiceTaskStatusFailureReasonCount::reason)
              .containsExactlyInAnyOrder("AUTHORIZATION_EXPIRED", "MAPPING_MISSING");
          assertThat(c.totalCount()).isEqualTo(2L);
        });
    assertThat(summary.failureByClass())
        .filteredOn(c -> "SYSTEM".equals(c.failureClass()))
        .singleElement()
        .satisfies(c -> {
          assertThat(c.byReason())
              .extracting(ServiceTaskStatusModels.ServiceTaskStatusFailureReasonCount::reason)
              .containsExactlyInAnyOrder("NETWORK_ERROR", "RATE_LIMITED");
          assertThat(c.totalCount()).isEqualTo(2L);
        });
    assertThat(summary.sources()).hasSize(5);
  }

  @Test
  @DisplayName("1.5.2.1 停滞提示与失败提示是两个独立列表")
  void alertsSeparateStalledFromFailed() {
    UUID connectionId = seedConnection();
    UUID cursorId = seedCursor(connectionId);
    seedRun(cursorId, "RUNNING", dbNow().minusMinutes(200), null, null);
    UUID failedRun = seedRun(cursorId, "FAILED", dbNow().minusMinutes(30), dbNow().minusMinutes(29), 1000);
    seedFailure(failedRun, cursorId, "AUTHORIZATION_EXPIRED", dbNow().minusMinutes(29));

    var alerts = as("channel.sync.read", () -> service.alerts());
    assertThat(alerts.stalled()).hasSize(1);
    assertThat(alerts.stalled().get(0).kind()).isEqualTo("STALLED");
    assertThat(alerts.stalled().get(0).severity()).isEqualTo("RED");
    assertThat(alerts.failed()).hasSize(1);
    assertThat(alerts.failed().get(0).kind()).isEqualTo("FAILED");
    assertThat(alerts.failed().get(0).failureClass()).isEqualTo("BUSINESS");
    // 🔴 停滞的那条**不能**出现在失败列表里，反之亦然。
    assertThat(alerts.stalled()).noneMatch(a -> a.taskId().equals(alerts.failed().get(0).taskId()));
    assertThat(alerts.failed()).noneMatch(a -> a.taskId().equals(alerts.stalled().get(0).taskId()));
  }

  @Test
  @DisplayName("1.5.2.1 真分页：页大小生效、总数是真的")
  void paginationIsReal() {
    UUID connectionId = seedConnection();
    UUID cursorId = seedCursor(connectionId);
    for (int i = 0; i < 7; i++) {
      seedRun(cursorId, "SUCCEEDED", dbNow().minusMinutes(60 - i), dbNow().minusMinutes(59 - i), 1000);
    }
    var first = as("channel.sync.read", () -> service.tasks(null, null, null, null, null, null, 0, 2));
    assertThat(first.items()).hasSize(2);
    assertThat(first.total()).isEqualTo(7);
    assertThat(first.page()).isZero();
    var second = as("channel.sync.read", () -> service.tasks(null, null, null, null, null, null, 1, 2));
    assertThat(second.items()).hasSize(2);
    assertThat(second.total()).isEqualTo(7);
    assertThat(second.items().get(0).taskId()).isNotEqualTo(first.items().get(0).taskId());
    var last = as("channel.sync.read", () -> service.tasks(null, null, null, null, null, null, 3, 2));
    assertThat(last.items()).hasSize(1);
  }

  @Test
  @DisplayName("1.5.2.1 真筛选：按来源筛、按状态筛、按关键字搜")
  void filteringIsReal() {
    UUID connectionId = seedConnection();
    UUID shopId = seedShop(connectionId, "SHOP-F");
    UUID cursorId = seedCursor(connectionId);
    seedRun(cursorId, "SUCCEEDED", dbNow().minusMinutes(20), dbNow().minusMinutes(19), 1000);
    seedPublishTask(shopId, "SUCCEEDED", dbNow().minusMinutes(18), dbNow().minusMinutes(17));
    seedCollectTask("SUCCEEDED", 200);

    assertThat(
            as("channel.sync.read", () -> service.tasks("publish_task", null, null, null, null, null, 0, 50))
                .items())
        .extracting(ServiceTaskStatusModels.ServiceTaskStatusTaskCard::sourceKey)
        .containsExactly("publish_task");
    assertThat(
            as("channel.sync.read", () -> service.tasks(null, "SUCCEEDED", null, null, null, null, 0, 50))
                .total())
        .isEqualTo(3);
    assertThat(
            as("channel.sync.read", () -> service.tasks(null, "FAILED", null, null, null, null, 0, 50))
                .total())
        .isZero();
    // 关键字搜索：命中标题里的店铺编码。
    assertThat(
            as("channel.sync.read", () -> service.tasks(null, null, null, "SHOP-F", null, null, 0, 50))
                .items())
        .isNotEmpty();
    // 搜不到的东西不能瞎编一条出来。
    assertThat(
            as("channel.sync.read", () -> service.tasks(null, null, null, "不存在的关键字", null, null, 0, 50))
                .total())
        .isZero();
  }

  @Test
  @DisplayName("1.5.2.1 注册表声明的列真的存在于数据库（不许写错列名）")
  void declaredColumnsExist() {
    for (ServiceTaskStatusSources.TaskSource source : ServiceTaskStatusSources.ALL) {
      assertThat(hasColumn(source.table(), source.stateColumn()))
          .as(source.table() + "." + source.stateColumn() + " 必须存在")
          .isTrue();
      if (source.startColumn() != null) {
        assertThat(hasColumn(source.table(), source.startColumn()))
            .as(source.table() + "." + source.startColumn() + " 必须存在")
            .isTrue();
      }
      if (source.finishColumn() != null) {
        assertThat(hasColumn(source.table(), source.finishColumn()))
            .as(source.table() + "." + source.finishColumn() + " 必须存在")
            .isTrue();
      }
      if (source.durationColumn() != null) {
        assertThat(hasColumn(source.table(), source.durationColumn()))
            .as(source.table() + "." + source.durationColumn() + " 必须存在")
            .isTrue();
      }
      if (source.triggerColumn() != null) {
        assertThat(hasColumn(source.table(), source.triggerColumn()))
            .as(source.table() + "." + source.triggerColumn() + " 必须存在")
            .isTrue();
      }
    }
    // 🔴 反向断言：批量搬迁**真的**没有耗时列 —— 注册表说它没有，它就不能有。
    assertThat(hasColumn("listing_migration_batch", "duration_ms")).isFalse();
    assertThat(hasColumn("listing_migration_batch", "started_at")).isFalse();
    assertThat(hasColumn("publish_task", "duration_ms")).isFalse();
  }

  private static boolean hasColumn(String table, String column) {
    return "1".equals(
        scalar(
            "select count(*) from information_schema.columns"
                + " where table_name = ? and column_name = ?",
            table,
            column));
  }

  @Test
  @DisplayName("1.5.2.1 能力声明：规则表、阈值、逐张表的来源清单、未接通的上游")
  void capabilitiesAreComplete() {
    var capabilities = as("channel.sync.read", () -> service.capabilities());
    assertThat(capabilities.rules()).isNotEmpty();
    assertThat(capabilities.thresholds().stallWarnMinutes()).isEqualTo(30);
    assertThat(capabilities.thresholds().stallCriticalMinutes()).isEqualTo(120);
    assertThat(capabilities.thresholds().failureWindowHours()).isEqualTo(24);
    assertThat(capabilities.sources()).hasSize(5);
    assertThat(capabilities.sources())
        .extracting(ServiceTaskStatusModels.ServiceTaskStatusSourceDoc::sourceKey)
        .containsExactly(
            "sync_run", "backfill_task", "listing_migration_batch", "publish_task",
            "listing_collect_task");
    // 🔴 规则任务整卷未开发 ⇒ 如实报未接通 + 归属模块，**不造一张规则任务表顶替它**。
    assertThat(capabilities.gaps())
        .extracting(ServiceTaskStatusModels.ServiceTaskStatusCapabilityGap::module)
        .contains("18.3", "18.1.3.1", "18.1.3.2");
    assertThat(capabilities.gaps())
        .allMatch(g -> !g.available());
    assertThat(capabilities.stallSemantics()).contains("now()").contains("分钟");
    assertThat(capabilities.failureClassSemantics()).contains("BUSINESS").contains("SYSTEM");
  }

  @Test
  @DisplayName("1.5.2.1 筛选项：来源取自注册表，触发来源取自真实数据")
  void optionsAreReal() {
    UUID connectionId = seedConnection();
    UUID cursorId = seedCursor(connectionId);
    seedRun(cursorId, "SUCCEEDED", dbNow().minusMinutes(20), dbNow().minusMinutes(19), 1000);

    var options = as("channel.sync.read", () -> service.options());
    assertThat(options.sources())
        .containsExactly(
            "sync_run", "backfill_task", "listing_migration_batch", "publish_task",
            "listing_collect_task");
    assertThat(options.triggerSources()).contains("SCHEDULER");
    assertThat(options.severities()).containsExactly("RED", "YELLOW", "GREEN");
    assertThat(options.failureClasses())
        .contains("BUSINESS", "SYSTEM", "MIXED", "UNCLASSIFIED", "UNDETERMINED", "NONE");
  }

  @Test
  @DisplayName("1.5.2.1 单条任务按 id 取得到，取不到就是 404")
  void singleTaskLookup() throws Exception {
    UUID connectionId = seedConnection();
    UUID cursorId = seedCursor(connectionId);
    UUID runId = seedRun(cursorId, "SUCCEEDED", dbNow().minusMinutes(20), dbNow().minusMinutes(19), 1000);

    var card = as("channel.sync.read", () -> service.task(runId.toString()));
    assertThat(card.taskId()).isEqualTo(runId.toString());
    assertThat(card.sourceKey()).isEqualTo("sync_run");

    http.perform(get("/channel/service-task-status/tasks/" + runId).header("Authorization", bearer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sourceKey").value("sync_run"));

    http.perform(
            get("/channel/service-task-status/tasks/" + UUID.randomUUID())
                .header("Authorization", bearer))
        .andExpect(status().isNotFound());
    http.perform(get("/channel/service-task-status/tasks/not-a-uuid").header("Authorization", bearer))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("1.5.2.1 本模块只读：写方法一律 405")
  void moduleIsReadOnly() throws Exception {
    for (String path :
        List.of(
            "/channel/service-task-status/tasks",
            "/channel/service-task-status/summary",
            "/channel/service-task-status/alerts",
            "/channel/service-task-status/capabilities",
            "/channel/service-task-status/options")) {
      http.perform(post(path).header("Authorization", bearer).contentType("application/json").content("{}"))
          .andExpect(status().isMethodNotAllowed());
      http.perform(put(path).header("Authorization", bearer).contentType("application/json").content("{}"))
          .andExpect(status().isMethodNotAllowed());
      http.perform(delete(path).header("Authorization", bearer))
          .andExpect(status().isMethodNotAllowed());
    }
  }

  @Test
  @DisplayName("1.5.2.1 无会话一律 401")
  void requiresAuthentication() throws Exception {
    http.perform(get("/channel/service-task-status/summary")).andExpect(status().isUnauthorized());
    http.perform(get("/channel/service-task-status/tasks")).andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("1.5.2.1 上游读不到 ⇒ 503，不是空集合")
  void upstreamFailureIsServiceUnavailable() {
    UUID connectionId = seedConnection();
    UUID cursorId = seedCursor(connectionId);
    seedRun(cursorId, "SUCCEEDED", dbNow().minusMinutes(20), dbNow().minusMinutes(19), 1000);

    // 正常路径必须真的查到东西（证明下面那个 503 是「读不到」，不是「本来就没数据」）。
    assertThat(
            as("channel.sync.read", () -> service.tasks(null, null, null, null, null, null, 0, 50))
                .total())
        .isEqualTo(1);

    // 🔴 真的把上游打断：把 sync_run 的读权限从应用角色上收回来。
    //    端点必须报 503，**不能**回一个空集合冒充「一切正常」。
    admin("revoke select on sync_run from channel_app");
    try {
      var thrown =
          org.assertj.core.api.Assertions.catchThrowable(
              () -> as("channel.sync.read", () -> service.tasks(null, null, null, null, null, null, 0, 50)));
      assertThat(thrown).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
      assertThat(((org.springframework.web.server.ResponseStatusException) thrown).getStatusCode().value())
          .isEqualTo(503);
    } finally {
      admin("grant select on sync_run to channel_app");
    }
  }

  /** 以超级用户身份执行一条管理语句（用来临时打断 / 恢复上游）。 */
  private static void admin(String statement) {
    try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(statement);
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * 🔴 收尾：停掉本地桩与 Testcontainers 容器。
   *
   * <p>不回收容器的话，Ryuk 只在 JVM 退出时按 session 标签统一回收 —— 整个模块跑完之前容器一直活着
   * （实测 channel 一个服务就能堆到 110+ 个 postgres 容器），容器内存 + Docker VM 把机器压进 swap，
   * JVM 被卡住十几分钟 ⇒ 全量跑时断言假红、SSL 握手失败。
   *
   * <p>顺序：先停外部服务，再停容器。反了会出现「容器已停但还有 in-flight 请求打进来」。
   */
  @AfterAll
  static void stopTestResources() {
    if (identity != null) identity.stop(0);
    pg.stop();
  }
}
