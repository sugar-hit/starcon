package com.starcon.channel;

import static com.starcon.channel.ListingModels.*;
import static com.starcon.channel.ListingHijackerModels.*;
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
 * 4.5.2.1 跟卖监控 的多租户三断言（AGENTS.md 硬性要求，缺一不可）。
 *
 * <ol>
 *   <li><b>两租户互不可见</b>（监控对象 / 跟卖记录 / 预警 / 对比视图都算）
 *   <li><b>伪造 tenant header 无效</b>（租户只来自已认证成员关系）
 *   <li><b>连接池归还后不串租户</b>（池压到 1，A→B→A 三轮）
 * </ol>
 *
 * <p>🔴 <b>按主键直查那条必须用 {@code channel_app} 连，不能用 Testcontainers 默认的超级用户。</b>
 * 超级用户<b>绕过 RLS</b> —— 用它查「读不到对方数据」会永远得到「读得到」，
 * 那不是隔离被破坏，而是这个断言本身失效了（4.1.1.2 / 4.3.2.1 实测踩过这个坑）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ListingHijackerTenancyIntegrationTest {

  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID user = UUID.randomUUID();
  static HttpServer identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

  UUID a, b;
  AccessService.Access actorA, actorB;
  String bearerA, bearerB;

  // 🔴 平台与站点注册表由 V1 迁移随代码发布（只读码表），测试直接用它，不自己造一份。
  static final UUID AMAZON = UUID.fromString("11111111-1111-1111-1111-111111111101");

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

  @Autowired ListingService listings;
  @Autowired ListingHijackerService service;
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

  UUID seedShop(UUID tenant, String code) {
    UUID id = UUID.randomUUID();
    execAsTenant(tenant,
        "insert into shop (tenant_id,id,platform_id,code,name) values ('" + tenant + "','" + id
            + "','" + AMAZON + "','" + code + "','测试店铺 " + code + "')"
            + " on conflict (tenant_id,code) do nothing");
    return id;
  }

  ListingRow seedListing(
      AccessService.Access who, UUID shopId, String externalItemId, String title, String status) {
    SaveListingRequest request =
        new SaveListingRequest(
            null, shopId, null, externalItemId, title, "Home", "cat-1",
            status, null, "", "IN_STOCK", null, null, null, null, null, null, List.of(), null);
    return as(who, "channel.operation.create", () -> listings.save(request));
  }

  static HijackerRecordInput rec(String sellerId, String shopIdentifier, String price) {
    return new HijackerRecordInput(
        sellerId, shopIdentifier, "店 " + sellerId, new BigDecimal(price), "USD", Map.of());
  }

  static OffsetDateTime at(int day, int hour) {
    return OffsetDateTime.of(2026, 10, day, hour, 30, 0, 0, ZoneOffset.UTC);
  }

  ListingHijackerWatchRow createWatch(AccessService.Access who, UUID listingId) {
    return as(
        who,
        "channel.operation.create",
        () ->
            service.createWatch(
                new SaveHijackerWatchRequest(
                    listingId, null, null, new BigDecimal("25.00"), "USD",
                    new BigDecimal("21.00"), new BigDecimal("0.10"), "ACTIVE", "租户测试")));
  }

  ListingHijackerIngestResult ingest(
      AccessService.Access who, UUID watchId, String key, OffsetDateTime observedAt, String seller) {
    return as(
        who,
        "channel.operation.create",
        () ->
            service.ingest(
                new IngestHijackerRecordsRequest(
                    watchId, observedAt, "PLATFORM", "API", key, "",
                    List.of(rec(seller, "SHOP-" + seller, "20.00")))));
  }

  // ==================================================================
  // 断言一：两租户互不可见
  // ==================================================================

  @Test
  @DisplayName("4.5.2.1 断言一：两租户互不可见（监控对象 / 跟卖记录 / 预警 / 对比视图都不可见）")
  void tenantsAreIsolated() {
    UUID shopA = seedShop(a, "HJA-1");
    UUID shopB = seedShop(b, "HJB-1");
    var listingA = seedListing(actorA, shopA, "HJA-1", "A 的商品", "ON_SALE");
    var listingB = seedListing(actorB, shopB, "HJB-1", "B 的商品", "ON_SALE");

    var watchA = createWatch(actorA, listingA.id());
    var watchB = createWatch(actorB, listingB.id());
    ingest(actorA, watchA.watchId(), "k-ten-a", at(1, 10), "SELLER-A");
    ingest(actorB, watchB.watchId(), "k-ten-b", at(1, 10), "SELLER-B");

    // B 的列表里只有 B 自己的行
    var recordsB =
        as(
            actorB,
            "channel.operation.read",
            () -> service.records(null, null, null, null, null, null, null, 0, 50));
    assertThat(recordsB.items()).as("B 的租户上下文里只有 B 自己的跟卖记录").hasSize(1);
    assertThat(recordsB.items().get(0).listingId()).isEqualTo(listingB.id());
    assertThat(recordsB.items().get(0).listingId()).isNotEqualTo(listingA.id());

    // B 的监控对象列表里只有 B 自己那条
    var watchesB =
        as(actorB, "channel.operation.read", () -> service.watches(null, null, null, null, 0, 50));
    assertThat(watchesB.items()).hasSize(1);
    assertThat(watchesB.items().get(0).watchId()).isEqualTo(watchB.watchId());

    // B 的预警列表里只有 B 自己那条
    var alertsB =
        as(
            actorB,
            "channel.operation.read",
            () -> service.alerts(null, null, null, null, null, null, 0, 50));
    assertThat(alertsB.items()).hasSize(1);
    assertThat(alertsB.items().get(0).watchId()).isEqualTo(watchB.watchId());

    // 🔴 B 按 id 取 A 的监控对象 ⇒ 404（对比视图也是）
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    as(
                        actorB,
                        "channel.operation.read",
                        () ->
                            service.comparison(
                                watchA.watchId().toString(), "2026-10-01", "2026-10-31", 0, 50))))
        .as("B 拿 A 的监控对象看对比视图必须失败")
        .isNotNull();

    // 🔴 按主键直查（用 channel_app 连，超级用户绕过 RLS 会让这个断言永远「通过」）
    assertThat(
            countAsTenant(
                b, "select count(*) from listing_hijacker_watch where listing_id='" + listingA.id() + "'"))
        .as("B 的上下文里直查 A 的监控对象必须读到零行")
        .isZero();
    assertThat(
            countAsTenant(
                b, "select count(*) from listing_hijacker_record where listing_id='" + listingA.id() + "'"))
        .as("B 的上下文里直查 A 的跟卖记录必须读到零行")
        .isZero();
    assertThat(
            countAsTenant(
                b, "select count(*) from listing_hijacker_alert where listing_id='" + listingA.id() + "'"))
        .as("B 的上下文里直查 A 的预警必须读到零行")
        .isZero();
    // 反向同理
    assertThat(
            countAsTenant(
                a, "select count(*) from listing_hijacker_record where listing_id='" + listingB.id() + "'"))
        .isZero();
    assertThat(
            countAsTenant(
                a, "select count(*) from listing_hijacker_alert where listing_id='" + listingB.id() + "'"))
        .isZero();

    // 三张表都是 FORCE RLS
    for (String table :
        List.of("listing_hijacker_watch", "listing_hijacker_record", "listing_hijacker_alert")) {
      assertThat(scalar("select relforcerowsecurity::text from pg_class where relname='" + table + "'"))
          .as(table + " 必须 FORCE RLS")
          .isEqualTo("true");
    }

    // 同一机制在本租户自己的数据上必须看得见行 —— 否则上面那些「零行」可能只是查询一直返回 0
    assertThat(
            countAsTenant(
                a, "select count(*) from listing_hijacker_record where listing_id='" + listingA.id() + "'"))
        .as("A 的上下文必须能直查 A 自己的跟卖记录（证明上面那个 0 是隔离，不是查询失效）")
        .isEqualTo(1);
  }

  @Test
  @DisplayName("4.5.2.1 断言一（续）：B 拿自己的 key 也写不进 A 的租户")
  void ingestStaysInsideTenant() {
    UUID shopA = seedShop(a, "HJA-2");
    UUID shopB = seedShop(b, "HJB-2");
    var listingA = seedListing(actorA, shopA, "HJA-2", "A 的商品", "ON_SALE");
    var listingB = seedListing(actorB, shopB, "HJB-2", "B 的商品", "ON_SALE");
    var watchA = createWatch(actorA, listingA.id());
    var watchB = createWatch(actorB, listingB.id());

    // B 的会话 + A 的监控对象 ⇒ 定位不到（A 的监控对象对 B 不可见）
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () -> ingest(actorB, watchA.watchId(), "k-cross", at(2, 10), "SELLER-CROSS")))
        .as("B 拿 A 的监控对象录跟卖必须失败")
        .isNotNull();

    // B 自己录 ⇒ 只落在 B 的租户里
    ingest(actorB, watchB.watchId(), "k-own-b", at(2, 10), "SELLER-B");
    assertThat(
            countAsTenant(
                b, "select count(*) from listing_hijacker_record where listing_id='" + listingB.id() + "'"))
        .isEqualTo(1);
    assertThat(
            countAsTenant(
                a, "select count(*) from listing_hijacker_record where listing_id='" + listingA.id() + "'"))
        .isZero();
  }

  // ==================================================================
  // 断言二：伪造 tenant header 无效
  // ==================================================================

  @Test
  @DisplayName("4.5.2.1 断言二：伪造 tenant header 无效（租户只来自已认证成员关系）")
  void forgedTenantHeaderIsIgnored() throws Exception {
    UUID shopA = seedShop(a, "HJA-F");
    UUID shopB = seedShop(b, "HJB-F");
    var listingA = seedListing(actorA, shopA, "HJA-F", "A 的商品", "ON_SALE");
    var listingB = seedListing(actorB, shopB, "HJB-F", "B 的商品", "ON_SALE");
    var watchA = createWatch(actorA, listingA.id());
    var watchB = createWatch(actorB, listingB.id());
    ingest(actorB, watchB.watchId(), "k-forge-b", at(3, 10), "SELLER-B");

    // A 的会话 + B 的头 ⇒ 仍然读不到 B 的数据
    http.perform(get("/channel/listing-hijackers/records")
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(0));

    // 🔴 A 自己的监控对象仍然看得见（此刻 A 有 1 条），但 B 的那条**看不见**
    http.perform(get("/channel/listing-hijackers/watches")
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].watchId").value(watchA.watchId().toString()));

    http.perform(get("/channel/listing-hijackers/alerts")
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(0));

    // A 的会话 + B 的头去读 B 的对比视图 ⇒ 404
    http.perform(get("/channel/listing-hijackers/comparison")
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", b.toString())
            .param("watchId", watchB.watchId().toString()))
        .andExpect(status().isNotFound());

    // A 用伪造的 B 头去给 A 自己的监控对象录数据 ⇒ 仍然落在 A 的租户里
    http.perform(post("/channel/listing-hijackers/records")
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", b.toString())
            .contentType("application/json")
            .content(
                JSON.writeValueAsString(
                    new IngestHijackerRecordsRequest(
                        watchA.watchId(), at(3, 11), "PLATFORM", "API", "k-forge-a", "",
                        List.of(rec("SELLER-A", "SHOP-SELLER-A", "20.00"))))))
        .andExpect(status().isOk());

    assertThat(
            countAsTenant(
                a, "select count(*) from listing_hijacker_record where listing_id='" + listingA.id() + "'"))
        .as("伪造的头不能把数据写进别的租户")
        .isEqualTo(1);
    assertThat(
            countAsTenant(
                b, "select count(*) from listing_hijacker_record where listing_id='" + listingA.id() + "'"))
        .as("B 的租户里不能出现 A 的商品")
        .isZero();

    // 无会话一律 401（伪造的头救不了）
    http.perform(get("/channel/listing-hijackers/records")
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isUnauthorized());
  }

  // ==================================================================
  // 断言三：连接池归还后不串租户
  // ==================================================================

  @Test
  @DisplayName("4.5.2.1 断言三：连接池归还后不串租户（池压到 1，A→B→A 三轮）")
  void pooledConnectionsDoNotBleedTenants() {
    UUID shopA = seedShop(a, "HJA-P");
    UUID shopB = seedShop(b, "HJB-P");
    var listingA = seedListing(actorA, shopA, "HJA-P", "A 的商品", "ON_SALE");
    var listingB = seedListing(actorB, shopB, "HJB-P", "B 的商品", "ON_SALE");
    var watchA = createWatch(actorA, listingA.id());
    var watchB = createWatch(actorB, listingB.id());
    ingest(actorA, watchA.watchId(), "k-pool-a", at(4, 10), "SELLER-A");
    ingest(actorB, watchB.watchId(), "k-pool-b", at(4, 10), "SELLER-B");

    for (int i = 0; i < 3; i++) {
      var recordsA =
          as(
              actorA,
              "channel.operation.read",
              () -> service.records(null, null, null, null, null, null, null, 0, 50));
      assertThat(recordsA.items()).hasSize(1);
      assertThat(recordsA.items().get(0).listingId()).isEqualTo(listingA.id());
      assertThat(recordsA.items().get(0).hijackerSellerId()).isEqualTo("SELLER-A");

      var recordsB =
          as(
              actorB,
              "channel.operation.read",
              () -> service.records(null, null, null, null, null, null, null, 0, 50));
      assertThat(recordsB.items()).hasSize(1);
      assertThat(recordsB.items().get(0).listingId()).isEqualTo(listingB.id());
      assertThat(recordsB.items().get(0).hijackerSellerId()).isEqualTo("SELLER-B");

      // 监控对象列表也必须跟着租户走
      var watchesA =
          as(actorA, "channel.operation.read", () -> service.watches(null, null, null, null, 0, 50));
      assertThat(watchesA.items()).hasSize(1);
      assertThat(watchesA.items().get(0).watchId()).isEqualTo(watchA.watchId());

      // 预警也必须跟着租户走
      var alertsA =
          as(
              actorA,
              "channel.operation.read",
              () -> service.alerts(null, null, null, null, null, null, 0, 50));
      assertThat(alertsA.items()).hasSize(1);
      assertThat(alertsA.items().get(0).watchId()).isEqualTo(watchA.watchId());

      // 对比视图也必须跟着租户走
      var comparisonA =
          as(
              actorA,
              "channel.operation.read",
              () ->
                  service.comparison(watchA.watchId().toString(), "2026-10-01", "2026-10-31", 0, 50));
      assertThat(comparisonA.items()).hasSize(1);
      assertThat(comparisonA.items().get(0).hijackerSellerId()).isEqualTo("SELLER-A");
    }
  }
}
