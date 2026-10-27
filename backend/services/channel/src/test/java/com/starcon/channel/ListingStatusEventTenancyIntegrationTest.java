package com.starcon.channel;

import static com.starcon.channel.ListingModels.*;
import static com.starcon.channel.ListingStatusSyncModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
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
 * 4.3.2.2 平台状态回流 的多租户三断言（AGENTS.md 硬性要求，缺一不可）。
 *
 * <ol>
 *   <li><b>两租户互不可见</b>（状态变更记录 / 需处理队列 / 冲突告警 / 02 队列任务都算）
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
class ListingStatusEventTenancyIntegrationTest {

  static final PostgreSQLContainer<?> pg =
      new PostgreSQLContainer<>("postgres:16-alpine").withUrlParam("sslmode", "disable");
  static final UUID user = UUID.randomUUID();
  static HttpServer identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static final ObjectMapper JSON = new ObjectMapper();

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
  @Autowired ListingStatusSyncService service;
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

  /** 录一条平台事件（制造状态变更记录 + 需处理队列项）。 */
  ListingStatusEventRow ingest(AccessService.Access who, UUID listingId, String eventType, String reason) {
    return as(who, "channel.operation.create",
        () ->
            service.ingest(
                new IngestStatusEventRequest(
                    listingId, null, null, eventType, "ACTIVE", reason, "API", null, Map.of(), ""),
                user));
  }

  // ==================================================================
  // 断言一：两租户互不可见
  // ==================================================================

  @Test
  @DisplayName("4.3.2.2 断言一：两租户互不可见（状态变更记录 / 需处理队列 / 02 队列任务都不可见）")
  void tenantsAreIsolated() {
    UUID shopA = seedShop(a, "TEN-A-1");
    UUID shopB = seedShop(b, "TEN-B-1");
    var listingA = seedListing(actorA, shopA, "TEN-A-1", "A 的商品", "ON_SALE");
    var listingB = seedListing(actorB, shopB, "TEN-B-1", "B 的商品", "ON_SALE");

    var eventA = ingest(actorA, listingA.id(), "REJECTED", "A 的驳回原因");
    var eventB = ingest(actorB, listingB.id(), "REJECTED", "B 的驳回原因");

    // B 读不到 A 的事件（B 只能看到**自己**的那条）
    var pageB = as(actorB, "channel.operation.read", () -> service.events(null, null, null, null, null, 0, 50));
    assertThat(pageB.items()).as("B 的租户上下文里只有 B 自己的事件").hasSize(1);
    assertThat(pageB.items().get(0).eventId()).isEqualTo(eventB.eventId());
    assertThat(pageB.items().get(0).eventId()).isNotEqualTo(eventA.eventId());
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () -> as(actorB, "channel.operation.read", () -> service.event(eventA.eventId().toString()))))
        .as("B 按 id 取 A 的事件必须 404").isNotNull();

    // B 读不到 A 的需处理队列
    var queueB = as(actorB, "channel.operation.read", () -> service.queue(null, null, null, null, null, 0, 50));
    assertThat(queueB.items())
        .as("B 的租户上下文里只有 B 自己的待办")
        .allMatch(item -> item.eventId().equals(eventB.eventId()));
    assertThat(queueB.items()).noneMatch(item -> item.eventId().equals(eventA.eventId()));

    // B 读不到 A 的冲突告警
    var conflictsB = as(actorB, "channel.operation.read", () -> service.conflicts(0, 50));
    assertThat(conflictsB.items())
        .as("B 的租户上下文里读不到 A 的冲突告警")
        .noneMatch(item -> item.eventId().equals(eventA.eventId()));

    // 🔴 按主键直查（用 channel_app 连，超级用户绕过 RLS 会让这个断言永远「通过」）
    assertThat(countAsTenant(b,
            "select count(*) from listing_status_event where id='" + eventA.eventId() + "'"))
        .as("B 的上下文里直查 A 的事件必须读到零行").isZero();
    assertThat(countAsTenant(b,
            "select count(*) from listing_status_action_queue where event_id='" + eventA.eventId() + "'"))
        .as("B 的上下文里直查 A 的队列项必须读到零行").isZero();
    // 反向同理
    assertThat(countAsTenant(a,
            "select count(*) from listing_status_event where id='" + eventB.eventId() + "'"))
        .isZero();

    // 两张表都是 FORCE RLS
    assertThat(scalar("select relforcerowsecurity::text from pg_class where relname='listing_status_action_queue'"))
        .as("listing_status_action_queue 必须 FORCE RLS").isEqualTo("true");
    assertThat(scalar("select relforcerowsecurity::text from pg_class where relname='listing_status_event'"))
        .as("listing_status_event 必须 FORCE RLS").isEqualTo("true");

    // 同一机制在本租户自己的数据上必须看得见行 —— 否则上面那些「零行」可能只是查询一直返回 0
    assertThat(countAsTenant(a,
            "select count(*) from listing_status_event where id='" + eventA.eventId() + "'"))
        .as("A 的上下文必须能直查 A 自己的事件（证明上面那个 0 是隔离，不是查询失效）")
        .isEqualTo(1);
  }

  @Test
  @DisplayName("4.3.2.2 断言一（续）：一键处理不会把 A 的队列任务写进 B 的租户")
  void republishStaysInsideTenant() {
    UUID shopA = seedShop(a, "TEN-A-2");
    UUID shopB = seedShop(b, "TEN-B-2");
    var listingA = seedListing(actorA, shopA, "TEN-A-2", "A 的驳回商品", "ON_SALE");
    ingest(actorA, listingA.id(), "REJECTED", "标题过长");

    // B 试着对 A 的待办发起一键处理 ⇒ 筛不到任何东西（RLS 先把行藏起来）
    var receipt =
        as(actorB, "channel.operation.update",
            () -> service.republish(new RepublishRequest("标题过长", null, null, null, 50, ""), user));
    assertThat(receipt.matchedCount()).as("B 看不到 A 的待办").isZero();
    assertThat(receipt.dispatchedCount()).isZero();

    // A 自己发起 ⇒ 只在 A 的租户里留下队列任务
    var receiptA =
        as(actorA, "channel.operation.update",
            () -> service.republish(new RepublishRequest("标题过长", null, null, null, 50, ""), user));
    assertThat(receiptA.dispatchedCount()).isEqualTo(1);

    assertThat(countAsTenant(b,
            "select count(*) from platform_operation where payload->>'statusRepublish'='true'"))
        .as("B 的上下文里读不到 A 的队列任务").isZero();
    assertThat(countAsTenant(a,
            "select count(*) from platform_operation where payload->>'statusRepublish'='true'"))
        .isEqualTo(1);
  }

  // ==================================================================
  // 断言二：伪造 tenant header 无效
  // ==================================================================

  @Test
  @DisplayName("4.3.2.2 断言二：伪造 tenant header 无效（租户只来自已认证成员关系）")
  void forgedTenantHeaderIsIgnored() throws Exception {
    UUID shopA = seedShop(a, "FORGE-A");
    UUID shopB = seedShop(b, "FORGE-B");
    var listingA = seedListing(actorA, shopA, "FORGE-A", "A 的商品", "ON_SALE");
    var listingB = seedListing(actorB, shopB, "FORGE-B", "B 的商品", "ON_SALE");
    var eventB = ingest(actorB, listingB.id(), "REJECTED", "B 的驳回原因");

    // A 的会话 + B 的头 ⇒ 仍然读不到 B 的事件
    http.perform(get("/channel/listing-status-events/events/" + eventB.eventId())
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isNotFound());
    http.perform(get("/channel/listing-status-events/events")
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(0));

    // A 的会话 + A 的头 ⇒ 只能看到 A 自己的
    http.perform(get("/channel/listing-status-events/queue")
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", a.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(0));

    // A 用伪造的 B 头去给 A 自己的商品录一条事件 ⇒ 仍然落在 A 的租户里
    http.perform(post("/channel/listing-status-events/events")
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", b.toString())
            .contentType("application/json")
            .content(JSON.writeValueAsString(
                new IngestStatusEventRequest(
                    listingA.id(), null, null, "THROTTLED", "THROTTLED", "", "API", null, Map.of(), ""))))
        .andExpect(status().isOk());

    assertThat(countAsTenant(a,
            "select count(*) from listing_status_event where listing_id='" + listingA.id() + "'"))
        .as("伪造的头不能把数据写进别的租户").isEqualTo(1);
    assertThat(countAsTenant(b,
            "select count(*) from listing_status_event where listing_id='" + listingA.id() + "'"))
        .as("B 的租户里不能出现 A 的商品").isZero();

    // 无会话一律 401（伪造的头救不了）
    http.perform(get("/channel/listing-status-events/capabilities")
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isUnauthorized());
  }

  // ==================================================================
  // 断言三：连接池归还后不串租户
  // ==================================================================

  @Test
  @DisplayName("4.3.2.2 断言三：连接池归还后不串租户（池压到 1，A→B→A 三轮）")
  void pooledConnectionsDoNotBleedTenants() {
    UUID shopA = seedShop(a, "POOL-A");
    UUID shopB = seedShop(b, "POOL-B");
    var listingA = seedListing(actorA, shopA, "POOL-A", "A 的商品", "ON_SALE");
    var listingB = seedListing(actorB, shopB, "POOL-B", "B 的商品", "ON_SALE");
    var eventA = ingest(actorA, listingA.id(), "REJECTED", "A 的驳回原因");
    var eventB = ingest(actorB, listingB.id(), "REJECTED", "B 的驳回原因");

    for (int i = 0; i < 3; i++) {
      var pageA = as(actorA, "channel.operation.read", () -> service.events(null, null, null, null, null, 0, 50));
      assertThat(pageA.items()).hasSize(1);
      assertThat(pageA.items().get(0).eventId()).isEqualTo(eventA.eventId());
      assertThat(pageA.items().get(0).rejectionReason()).isEqualTo("A 的驳回原因");

      var pageB = as(actorB, "channel.operation.read", () -> service.events(null, null, null, null, null, 0, 50));
      assertThat(pageB.items()).hasSize(1);
      assertThat(pageB.items().get(0).eventId()).isEqualTo(eventB.eventId());
      assertThat(pageB.items().get(0).rejectionReason()).isEqualTo("B 的驳回原因");

      // 队列也必须跟着租户走：串了就会读到别人的待办
      var queueA = as(actorA, "channel.operation.read", () -> service.queue("REPUBLISH", null, null, null, null, 0, 50));
      assertThat(queueA.items()).hasSize(1);
      assertThat(queueA.items().get(0).rejectionReason()).isEqualTo("A 的驳回原因");
      var queueB = as(actorB, "channel.operation.read", () -> service.queue("REPUBLISH", null, null, null, null, 0, 50));
      assertThat(queueB.items()).hasSize(1);
      assertThat(queueB.items().get(0).rejectionReason()).isEqualTo("B 的驳回原因");
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
