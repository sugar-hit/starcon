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
 * 1.5.1.1 同步与 API 健康 的多租户三断言（AGENTS.md 硬性要求，缺一不可）。
 *
 * <ol>
 *   <li><b>两租户互不可见</b>（连接 / 店铺 / 失败队列 / 积压四类事实都不可见）
 *   <li><b>伪造 tenant header 无效</b>（租户只来自已认证成员关系），
 *       <b>并断言行确实落在会话租户名下</b>（不是只断言 A 看不见）
 *   <li><b>连接池归还后不串租户</b>（池压到 1，A→B→A 交替 5 轮）
 * </ol>
 *
 * <p>🔴 <b>按主键直查那条必须用 {@code channel_app} 连，不能用 Testcontainers 默认的超级用户。</b>
 * 超级用户<b>绕过 RLS</b> —— 用它查「读不到对方数据」会永远得到「读得到」，
 * 那不是隔离被破坏，而是这个断言本身失效了。
 */
@SpringBootTest
@AutoConfigureMockMvc
class SyncHealthTenancyIntegrationTest {

  static final PostgreSQLContainer<?> pg =
      new PostgreSQLContainer<>("postgres:16-alpine").withUrlParam("sslmode", "disable");
  static final UUID user = UUID.randomUUID();
  static HttpServer identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static final ObjectMapper JSON = new ObjectMapper();

  static final UUID AMAZON = UUID.fromString("11111111-1111-1111-1111-111111111101");

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

  UUID a, b;
  AccessService.Access actorA, actorB;
  String bearerA, bearerB;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "channel_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://localhost:" + identity.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
    // 连接池压到 1：多租户串租户在单连接下最容易暴露。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> "1");
  }

  @Autowired SyncHealthService service;
  @Autowired AccessService access;
  @Autowired MockMvc http;

  // ==================================================================
  // 辅助
  // ==================================================================

  <T> T as(AccessService.Access actor, String permission, Supplier<T> work) {
    return access.run(actor, permission, work);
  }

  /** 以超级用户身份落种子数据（读断言一律走应用角色 / RLS 上下文）。 */
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

  /** 🔴 以<b>应用角色 channel_app</b>（非超级用户）直查一行计数。 */
  static long countAsTenant(UUID tenantId, String statement, Object... params) {
    try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), "channel_app", "test-only");
        PreparedStatement s = c.prepareStatement(statement)) {
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
      try (ResultSet rs = s.executeQuery()) {
        return rs.next() ? rs.getLong(1) : 0L;
      }
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

  /** 一条连接 + 一条店铺 + 一条游标 + 一次运行 + 一条失败 + 一条未发布事件。 */
  UUID seedFullChain(UUID tenantId, String account, String reason) {
    UUID connectionId = UUID.randomUUID();
    seed(
        tenantId,
        "insert into channel_connection (id,tenant_id,platform_id,authorized_account,display_name,status,last_sync_at)"
            + " values (?,?,?,?,?,'ACTIVE',?::timestamptz)",
        connectionId,
        tenantId,
        AMAZON,
        account,
        account + " 连接",
        OffsetDateTime.now(ZoneOffset.UTC).minusHours(1));
    seed(
        tenantId,
        "insert into shop (id,tenant_id,platform_id,connection_id,code,name) values (?,?,?,?,?,?)",
        UUID.randomUUID(),
        tenantId,
        AMAZON,
        connectionId,
        "SHOP-" + account,
        "店铺 " + account);
    UUID cursorId = UUID.randomUUID();
    seed(
        tenantId,
        "insert into sync_cursor (id,tenant_id,connection_id,resource_type) values (?,?,?,'ORDER')",
        cursorId,
        tenantId,
        connectionId);
    UUID runId = UUID.randomUUID();
    seed(
        tenantId,
        "insert into sync_run (id,tenant_id,cursor_id,trigger_source,status,started_at,finished_at,duration_ms)"
            + " values (?,?,?,'SCHEDULER','SUCCEEDED',?::timestamptz,?::timestamptz,100)",
        runId,
        tenantId,
        cursorId,
        OffsetDateTime.now(ZoneOffset.UTC).minusHours(2),
        OffsetDateTime.now(ZoneOffset.UTC).minusHours(1));
    seed(
        tenantId,
        "insert into sync_failure (id,tenant_id,run_id,cursor_id,external_key,resource_type,reason,"
            + " detail,payload_digest,attempts,status,occurred_at)"
            + " values (?,?,?,?,?, 'ORDER',?,?,'',1,'OPEN',?::timestamptz)",
        UUID.randomUUID(),
        tenantId,
        runId,
        cursorId,
        "ext-" + account,
        reason,
        "多租户测试",
        OffsetDateTime.now(ZoneOffset.UTC).minusHours(1));
    seed(
        tenantId,
        "insert into outbox_event (id,tenant_id,aggregate_id,aggregate_version,event_type,payload)"
            + " values (?,?,?,1,'ORDER_SYNCED','{}'::jsonb)",
        UUID.randomUUID(),
        tenantId,
        connectionId);
    return connectionId;
  }

  // ==================================================================
  // 断言一：两租户互不可见
  // ==================================================================

  @Test
  @DisplayName("1.5.1.1 断言一：两租户互不可见（连接 / 店铺 / 失败队列 / 积压四类事实）")
  void tenantsAreIsolated() {
    UUID connectionA = seedFullChain(a, "acct-a", "RATE_LIMITED");
    UUID connectionB = seedFullChain(b, "acct-b", "RATE_LIMITED");

    // B 读不到 A 的任何东西
    var pageB = as(actorB, "channel.sync.read", () -> service.connections(null, null, null, 0, 50));
    assertThat(pageB.items()).extracting(SyncHealthModels.SyncHealthConnectionCard::connectionId)
        .containsExactly(connectionB.toString());

    // 按 id 直取 A 的连接 ⇒ 404
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () -> as(actorB, "channel.sync.read", () -> service.connection(connectionA.toString()))))
        .as("B 按 id 直取 A 的卡片必须失败").isNotNull();

    // 总览里的红黄绿分布也只算自己的
    var summaryB = as(actorB, "channel.sync.read", () -> service.summary());
    assertThat(summaryB.totalConnections()).isEqualTo(1);
    assertThat(summaryB.failureCountTotal()).isEqualTo(1L);

    // 🔴 按主键直查（用 channel_app 连，超级用户绕过 RLS 会让这个断言永远「通过」）
    assertThat(countAsTenant(b, "select count(*) from channel_connection where id = ?", connectionA))
        .as("B 的上下文里直查 A 的连接必须读到零行").isZero();
    assertThat(countAsTenant(b, "select count(*) from channel_connection"))
        .as("B 的上下文里只能看到 B 自己的连接").isEqualTo(1);
    assertThat(countAsTenant(b, "select count(*) from shop where connection_id = ?", connectionA))
        .as("B 的上下文里直查 A 的店铺必须读到零行").isZero();
    assertThat(countAsTenant(b, "select count(*) from sync_failure"))
        .as("B 的上下文里只能看到 B 自己的失败记录").isEqualTo(1);
    assertThat(countAsTenant(b, "select count(*) from outbox_event where published_at is null"))
        .as("B 的上下文里只能看到 B 自己的积压").isEqualTo(1);

    // 同一机制在本租户自己的数据上必须看得见行 —— 否则上面那些「零行」可能只是查询一直返回 0
    assertThat(countAsTenant(a, "select count(*) from channel_connection where id = ?", connectionA))
        .as("A 的上下文必须能直查 A 自己的连接（证明上面那个 0 是隔离，不是查询失效）")
        .isEqualTo(1);
    assertThat(countAsTenant(a, "select count(*) from sync_failure")).isEqualTo(1);
    assertThat(countAsTenant(a, "select count(*) from outbox_event where published_at is null"))
        .isEqualTo(1);

    // 本模块读到的每一张事实表都必须 FORCE RLS
    for (String table :
        List.of(
            "channel_connection",
            "shop",
            "sync_cursor",
            "sync_run",
            "sync_failure",
            "outbox_event",
            "listing_content_writeback_task",
            "listing_status_action_queue",
            "publish_task")) {
      assertThat(scalar("select relforcerowsecurity::text from pg_class where relname = ?", table))
          .as(table + " 必须 FORCE RLS").isEqualTo("true");
    }
  }

  // ==================================================================
  // 断言二：伪造 tenant header 无效
  // ==================================================================

  @Test
  @DisplayName("1.5.1.1 断言二：伪造 tenant header 无效（租户只来自已认证成员关系）")
  void forgedTenantHeaderIsIgnored() throws Exception {
    UUID connectionA = seedFullChain(a, "forge-a", "AUTHORIZATION_EXPIRED");
    UUID connectionB = seedFullChain(b, "forge-b", "AUTHORIZATION_EXPIRED");

    // A 的会话 + B 的头 ⇒ 仍然只看到 A 的
    http.perform(get("/channel/sync-health/connections").header("Authorization", bearerA)
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.items[0].connectionId").value(connectionA.toString()))
        .andExpect(jsonPath("$.items[?(@.connectionId=='" + connectionB + "')]").isEmpty());

    // A 的会话 + B 的头 ⇒ 按 id 直取 B 的连接仍是 404
    http.perform(get("/channel/sync-health/connections/" + connectionB).header("Authorization", bearerA)
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isNotFound());

    // 无会话一律 401（伪造的头救不了）
    http.perform(get("/channel/sync-health/summary").header("X-Tenant-Id", b.toString()))
        .andExpect(status().isUnauthorized());

    // 🔴 **并断言行确实落在会话租户名下**（不是只断言 A 看不见 B）
    assertThat(scalar("select tenant_id from channel_connection where id = ?", connectionA))
        .as("A 会话写入的行必须落在 A 的租户名下")
        .isEqualTo(a.toString());
    assertThat(scalar("select tenant_id from channel_connection where id = ?", connectionB))
        .as("B 会话写入的行必须落在 B 的租户名下")
        .isEqualTo(b.toString());
    assertThat(scalar("select count(*) from sync_failure where tenant_id = ?", a)).isEqualTo("1");
    assertThat(scalar("select count(*) from sync_failure where tenant_id = ?", b)).isEqualTo("1");
    assertThat(scalar("select count(*) from outbox_event where tenant_id = ?", a)).isEqualTo("1");
    assertThat(scalar("select count(*) from outbox_event where tenant_id = ?", b)).isEqualTo("1");
  }

  // ==================================================================
  // 断言三：连接池归还后不串租户
  // ==================================================================

  @Test
  @DisplayName("1.5.1.1 断言三：连接池归还后不串租户（池压到 1，A→B→A 交替 5 轮）")
  void pooledConnectionsDoNotBleedTenants() {
    UUID connectionA = seedFullChain(a, "pool-a", "RATE_LIMITED");
    UUID connectionB = seedFullChain(b, "pool-b", "RATE_LIMITED");

    for (int i = 0; i < 5; i++) {
      assertThat(
              as(actorA, "channel.sync.read", () -> service.connections(null, null, null, 0, 50))
                  .items())
          .as("第 " + (i + 1) + " 轮：A 只看到 A 的连接")
          .extracting(SyncHealthModels.SyncHealthConnectionCard::connectionId)
          .containsExactly(connectionA.toString());
      assertThat(
              as(actorB, "channel.sync.read", () -> service.connections(null, null, null, 0, 50))
                  .items())
          .as("第 " + (i + 1) + " 轮：B 只看到 B 的连接")
          .extracting(SyncHealthModels.SyncHealthConnectionCard::connectionId)
          .containsExactly(connectionB.toString());

      var summaryA = as(actorA, "channel.sync.read", () -> service.summary());
      assertThat(summaryA.totalConnections()).as("第 " + (i + 1) + " 轮：A 的总览只算 A 的连接").isEqualTo(1);
      assertThat(summaryA.failureCountTotal()).as("第 " + (i + 1) + " 轮：A 的失败计数只算 A 的").isEqualTo(1L);

      var summaryB = as(actorB, "channel.sync.read", () -> service.summary());
      assertThat(summaryB.totalConnections()).as("第 " + (i + 1) + " 轮：B 的总览只算 B 的连接").isEqualTo(1);
      assertThat(summaryB.failureCountTotal()).as("第 " + (i + 1) + " 轮：B 的失败计数只算 B 的").isEqualTo(1L);
    }

    // 池里那条连接归还后，租户上下文必须被清掉：直查计数时不能带上前一个租户的行
    assertThat(countAsTenant(b, "select count(*) from channel_connection")).isEqualTo(1);
    assertThat(countAsTenant(b, "select count(*) from sync_failure")).isEqualTo(1);
    assertThat(countAsTenant(a, "select count(*) from channel_connection")).isEqualTo(1);
    assertThat(countAsTenant(a, "select count(*) from sync_failure")).isEqualTo(1);
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
