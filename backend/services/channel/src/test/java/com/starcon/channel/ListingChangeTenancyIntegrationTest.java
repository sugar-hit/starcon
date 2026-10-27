package com.starcon.channel;

import static com.starcon.channel.ListingChangeModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
 * 4.5.2.2 上新与变更监控 的多租户三断言（AGENTS.md 硬性要求，缺一不可）。
 *
 * <ol>
 *   <li><b>两租户互不可见</b>（监控对象 / 抓取批次 / 观测快照 / 变更事件 / 市场机会 / 历史对比都算）
 *   <li><b>伪造 tenant header 无效</b>（租户只来自已认证成员关系）
 *   <li><b>连接池归还后不串租户</b>（池压到 1，A→B→A 三轮）
 * </ol>
 *
 * <p>🔴 <b>按主键直查那条必须用 {@code channel_app} 连，不能用 Testcontainers 默认的超级用户。</b>
 * 超级用户<b>绕过 RLS</b> —— 用它查「读不到对方数据」会永远得到「读得到」，
 * 那不是隔离被破坏，而是这个断言本身失效了（4.1.1.2 / 4.3.2.1 实测踩过这个坑）。
 *
 * <p>🔴 本模块**没有**自己的商品表，也不引用 {@code listing} ——
 * 租户边界只体现在五张 {@code listing_change_*} 表上，所以这三条断言逐张表都要覆盖。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ListingChangeTenancyIntegrationTest {

  static final PostgreSQLContainer<?> pg =
      new PostgreSQLContainer<>("postgres:16-alpine").withUrlParam("sslmode", "disable");
  static final UUID user = UUID.randomUUID();
  static HttpServer identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

  UUID a, b;
  AccessService.Access actorA, actorB;
  String bearerA, bearerB;

  static final String AMAZON = "AMAZON";

  static final String ALL_SCOPE_GRANTS =
      "[{\"permissions\":[\"channel.operation.read\",\"channel.operation.create\","
          + "\"channel.operation.update\",\"channel.operation.delete\"],\"scopeMode\":\"ALL\"}]";

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

  static AccessService.Access actor(UUID uid, UUID tenant, List<AccessService.Grant> grants) {
    return new AccessService.Access(
        uid, tenant, "Test", UUID.randomUUID(), null,
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
  void freshTenants() {
    a = UUID.randomUUID();
    b = UUID.randomUUID();
    bearerA = "Bearer t-a-" + a;
    bearerB = "Bearer t-b-" + b;
    List<AccessService.Grant> grants = List.of(grant("ALL", allPermissions));
    actors.put(bearerA, actor(user, a, grants));
    actors.put(bearerB, actor(user, b, grants));
    actorA = actors.get(bearerA);
    actorB = actors.get(bearerB);
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "channel_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://localhost:" + identity.getAddress().getPort());
    r.add("CATALOG_URL", () -> "http://127.0.0.1:1");
    r.add("INTERNAL_KEY", () -> "test-key");
    // 连接池压到 1：多租户串租户在单连接下最容易暴露。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> "1");
  }

  @Autowired ListingChangeService service;
  @Autowired AccessService access;
  @Autowired MockMvc http;

  // ==================================================================
  // 辅助
  // ==================================================================

  <T> T as(AccessService.Access who, String permission, java.util.function.Supplier<T> work) {
    return access.run(who, permission, work);
  }

  static void execAsTenant(UUID tenant, String sql) {
    try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute("select set_config('app.current_tenant','" + tenant + "',false)");
      s.execute("select set_config('app.actor','" + user + "',false)");
      s.execute("select set_config('app.department','',false)");
      s.execute("select set_config('app.permission','channel.operation.create',false)");
      s.execute("select set_config('app.grants','" + ALL_SCOPE_GRANTS + "',false)");
      s.execute(sql);
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * 以<b>应用角色 channel_app</b>（非超级用户）直查一行计数。
   *
   * <p>🔴 必须用 channel_app 连：Testcontainers 默认的 {@code pg.getUsername()} 是超级用户，
   * 而超级用户<b>绕过 RLS</b> —— 用它查「读不到对方数据」会永远得到「读得到」。
   */
  static long countAsTenant(UUID tenant, String sql) {
    try (Connection c =
            DriverManager.getConnection(pg.getJdbcUrl(), "channel_app", "test-only");
        var s = c.createStatement()) {
      s.execute("select set_config('app.current_tenant','" + tenant + "',false)");
      s.execute("select set_config('app.actor','" + user + "',false)");
      s.execute("select set_config('app.department','',false)");
      s.execute("select set_config('app.permission','channel.operation.create',false)");
      s.execute("select set_config('app.grants','" + ALL_SCOPE_GRANTS + "',false)");
      try (var rs = s.executeQuery(sql)) {
        return rs.next() ? rs.getLong(1) : 0L;
      }
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  /** 以管理员连接读一行标量（只用于读全局码表这类不受 RLS 约束的事实）。 */
  static String scalar(String sql) {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement();
        var rs = s.executeQuery(sql)) {
      return rs.next() ? rs.getString(1) : null;
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  static OffsetDateTime at(int day, int hour) {
    return OffsetDateTime.of(2026, 10, day, hour, 30, 0, 0, ZoneOffset.UTC);
  }

  ListingChangeWatchRow createWatch(AccessService.Access who, String asin) {
    return as(
        who,
        "channel.operation.create",
        () ->
            service.createWatch(
                new SaveListingChangeWatchRequest(
                    AMAZON, null, asin, "COMPETITOR", "竞品 " + asin, "BrandX",
                    360, "ACTIVE", null, null, null, null, "租户测试")));
  }

  static ListingChangeObservationInput obs(OffsetDateTime observedAt, String status, String price) {
    return new ListingChangeObservationInput(
        observedAt, status, new BigDecimal(price), "USD", 20, "BSR", null,
        new BigDecimal("100"), Map.of());
  }

  ListingChangeCrawlResult ingest(
      AccessService.Access who, UUID watchId, String key, OffsetDateTime observedAt,
      ListingChangeObservationInput... observations) {
    return as(
        who,
        "channel.operation.create",
        () ->
            service.ingest(
                new IngestListingChangeCrawlRequest(
                    watchId, "PLATFORM", "API", observedAt, 30, key, "", Map.of(),
                    List.of(observations))));
  }

  // ==================================================================
  // 断言一：两租户互不可见
  // ==================================================================

  @Test
  @DisplayName("4.5.2.2 断言一：两租户互不可见（监控对象 / 抓取批次 / 快照 / 事件 / 机会 / 对比都不可见）")
  void tenantsAreIsolated() {
    var watchA = createWatch(actorA, "B0-TENANT-A");
    var watchB = createWatch(actorB, "B0-TENANT-B");
    ingest(actorA, watchA.watchId(), "k-ten-a", at(1, 10), obs(at(1, 10), "LISTED", "10.00"));
    ingest(actorB, watchB.watchId(), "k-ten-b", at(1, 10), obs(at(1, 10), "OUT_OF_STOCK", "12.00"));

    // B 的列表里只有 B 自己的行
    var watchesB =
        as(actorB, "channel.operation.read", () -> service.watches(null, null, null, null, 0, 50));
    assertThat(watchesB.items()).as("B 的租户上下文里只有 B 自己的监控对象").hasSize(1);
    assertThat(watchesB.items().get(0).watchId()).isEqualTo(watchB.watchId());
    assertThat(watchesB.items().get(0).watchId()).isNotEqualTo(watchA.watchId());

    var eventsB =
        as(
            actorB,
            "channel.operation.read",
            () -> service.events(null, null, null, null, null, 0, 50));
    assertThat(eventsB.items()).as("B 看不到 A 的变更事件").hasSize(1);
    assertThat(eventsB.items().get(0).watchId()).isEqualTo(watchB.watchId());

    var opportunitiesB =
        as(
            actorB,
            "channel.operation.read",
            () -> service.opportunities(null, null, null, null, null, null, 0, 50));
    assertThat(opportunitiesB.items()).as("B 看不到 A 的市场机会").hasSize(1);
    assertThat(opportunitiesB.items().get(0).watchId()).isEqualTo(watchB.watchId());

    var crawlsB =
        as(actorB, "channel.operation.read", () -> service.crawls(null, 0, 50));
    assertThat(crawlsB.items()).as("B 看不到 A 的抓取批次").hasSize(1);
    assertThat(crawlsB.items().get(0).watchId()).isEqualTo(watchB.watchId());

    // 🔴 B 按 id 取 A 的监控对象 ⇒ 404（历史对比也是）
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    as(
                        actorB,
                        "channel.operation.read",
                        () ->
                            service.comparison(
                                watchA.watchId().toString(), "2026-10-01", "2026-10-31"))))
        .as("B 拿 A 的监控对象看历史对比必须失败")
        .isNotNull();
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    as(
                        actorB,
                        "channel.operation.read",
                        () -> service.history(watchA.watchId().toString(), null, null, 0, 50))))
        .as("B 拿 A 的监控对象看历史观测必须失败")
        .isNotNull();

    // 🔴 按主键直查（用 channel_app 连，超级用户绕过 RLS 会让这个断言永远「通过」）
    //    监控对象表的主键列是 id，其余四张表的外键列是 watch_id。
    for (String table :
        List.of(
            "listing_change_crawl",
            "listing_change_snapshot",
            "listing_change_event",
            "listing_change_opportunity")) {
      assertThat(
              countAsTenant(
                  b, "select count(*) from " + table + " where watch_id='" + watchA.watchId() + "'"))
          .as("B 的上下文里直查 A 的 " + table + " 必须读到零行")
          .isZero();
      assertThat(
              countAsTenant(
                  a, "select count(*) from " + table + " where watch_id='" + watchB.watchId() + "'"))
          .as("A 的上下文里直查 B 的 " + table + " 必须读到零行")
          .isZero();
    }
    assertThat(
            countAsTenant(b, "select count(*) from listing_change_watch where id='" + watchA.watchId() + "'"))
        .as("B 的上下文里直查 A 的 listing_change_watch 必须读到零行")
        .isZero();
    assertThat(
            countAsTenant(a, "select count(*) from listing_change_watch where id='" + watchB.watchId() + "'"))
        .isZero();

    // 五张表都是 FORCE RLS
    for (String table :
        List.of(
            "listing_change_watch",
            "listing_change_crawl",
            "listing_change_snapshot",
            "listing_change_event",
            "listing_change_opportunity")) {
      assertThat(scalar("select relforcerowsecurity::text from pg_class where relname='" + table + "'"))
          .as(table + " 必须 FORCE RLS")
          .isEqualTo("true");
    }

    // 同一机制在本租户自己的数据上必须看得见行 —— 否则上面那些「零行」可能只是查询一直返回 0
    assertThat(
            countAsTenant(
                a, "select count(*) from listing_change_snapshot where watch_id='" + watchA.watchId() + "'"))
        .as("A 的上下文必须能直查 A 自己的观测快照（证明上面那个 0 是隔离，不是查询失效）")
        .isEqualTo(1);
    assertThat(
            countAsTenant(
                b, "select count(*) from listing_change_snapshot where watch_id='" + watchB.watchId() + "'"))
        .isEqualTo(1);
  }

  @Test
  @DisplayName("4.5.2.2 断言一（续）：B 拿自己的 key 也写不进 A 的租户")
  void ingestStaysInsideTenant() {
    var watchA = createWatch(actorA, "B0-CROSS-A");
    var watchB = createWatch(actorB, "B0-CROSS-B");

    // B 的会话 + A 的监控对象 ⇒ 定位不到（A 的监控对象对 B 不可见）
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () -> ingest(actorB, watchA.watchId(), "k-cross", at(2, 10), obs(at(2, 10), "LISTED", "10.00"))))
        .as("B 拿 A 的监控对象录观测必须失败")
        .isNotNull();

    // B 自己录 ⇒ 只落在 B 的租户里
    ingest(actorB, watchB.watchId(), "k-own-b", at(2, 10), obs(at(2, 10), "LISTED", "11.00"));
    assertThat(
            countAsTenant(
                b, "select count(*) from listing_change_snapshot where watch_id='" + watchB.watchId() + "'"))
        .isEqualTo(1);
    assertThat(
            countAsTenant(
                a, "select count(*) from listing_change_snapshot where watch_id='" + watchA.watchId() + "'"))
        .isZero();
  }

  // ==================================================================
  // 断言二：伪造 tenant header 无效
  // ==================================================================

  @Test
  @DisplayName("4.5.2.2 断言二：伪造 tenant header 无效（租户只来自已认证成员关系）")
  void forgedTenantHeaderIsIgnored() throws Exception {
    var watchA = createWatch(actorA, "B0-FORGE-A");
    var watchB = createWatch(actorB, "B0-FORGE-B");
    ingest(actorB, watchB.watchId(), "k-forge-b", at(3, 10), obs(at(3, 10), "LISTED", "12.00"));

    // A 的会话 + B 的头 ⇒ 仍然读不到 B 的数据
    http.perform(get("/channel/listing-changes/events")
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(0));

    // 🔴 A 自己的监控对象仍然看得见（此刻 A 有 1 条），但 B 的那条**看不见**
    http.perform(get("/channel/listing-changes/watches")
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].watchId").value(watchA.watchId().toString()));

    http.perform(get("/channel/listing-changes/opportunities")
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(0));

    // A 的会话 + B 的头去读 B 的历史对比 ⇒ 404
    http.perform(get("/channel/listing-changes/watches/" + watchB.watchId() + "/comparison")
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isNotFound());

    // A 用伪造的 B 头去给 A 自己的监控对象录数据 ⇒ 仍然落在 A 的租户里
    http.perform(post("/channel/listing-changes/crawls")
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", b.toString())
            .contentType("application/json")
            .content(
                JSON.writeValueAsString(
                    new IngestListingChangeCrawlRequest(
                        watchA.watchId(), "PLATFORM", "API", at(3, 11), 30, "k-forge-a", "",
                        Map.of(), List.of(obs(at(3, 11), "LISTED", "13.00"))))))
        .andExpect(status().isOk());

    assertThat(
            countAsTenant(
                a, "select count(*) from listing_change_snapshot where watch_id='" + watchA.watchId() + "'"))
        .as("伪造的头不能把数据写进别的租户")
        .isEqualTo(1);
    assertThat(
            countAsTenant(
                b, "select count(*) from listing_change_snapshot where watch_id='" + watchA.watchId() + "'"))
        .as("B 的租户里不能出现 A 的监控对象")
        .isZero();

    // 无会话一律 401（伪造的头救不了）
    http.perform(get("/channel/listing-changes/events")
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isUnauthorized());
  }

  // ==================================================================
  // 断言三：连接池归还后不串租户
  // ==================================================================

  @Test
  @DisplayName("4.5.2.2 断言三：连接池归还后不串租户（池压到 1，A→B→A 三轮）")
  void pooledConnectionsDoNotBleedTenants() {
    var watchA = createWatch(actorA, "B0-POOL-A");
    var watchB = createWatch(actorB, "B0-POOL-B");
    ingest(actorA, watchA.watchId(), "k-pool-a", at(4, 10), obs(at(4, 10), "LISTED", "10.00"));
    ingest(actorB, watchB.watchId(), "k-pool-b", at(4, 10), obs(at(4, 10), "LISTED", "11.00"));

    for (int i = 0; i < 3; i++) {
      var eventsA =
          as(
              actorA,
              "channel.operation.read",
              () -> service.events(null, null, null, null, null, 0, 50));
      assertThat(eventsA.items()).hasSize(1);
      assertThat(eventsA.items().get(0).watchId()).isEqualTo(watchA.watchId());

      var eventsB =
          as(
              actorB,
              "channel.operation.read",
              () -> service.events(null, null, null, null, null, 0, 50));
      assertThat(eventsB.items()).hasSize(1);
      assertThat(eventsB.items().get(0).watchId()).isEqualTo(watchB.watchId());

      // 监控对象列表也必须跟着租户走
      var watchesA =
          as(actorA, "channel.operation.read", () -> service.watches(null, null, null, null, 0, 50));
      assertThat(watchesA.items()).hasSize(1);
      assertThat(watchesA.items().get(0).watchId()).isEqualTo(watchA.watchId());

      // 观测快照也必须跟着租户走
      var historyA =
          as(
              actorA,
              "channel.operation.read",
              () -> service.history(watchA.watchId().toString(), null, null, 0, 50));
      assertThat(historyA.items()).hasSize(1);
      assertThat(historyA.items().get(0).watchId()).isEqualTo(watchA.watchId());

      // 历史对比也必须跟着租户走
      var comparisonA =
          as(
              actorA,
              "channel.operation.read",
              () -> service.comparison(watchA.watchId().toString(), "2026-10-01", "2026-10-31"));
      assertThat(comparisonA.sampleCount()).isEqualTo(1);
      assertThat(comparisonA.price().lastPrice()).isEqualByComparingTo("10.00");
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
