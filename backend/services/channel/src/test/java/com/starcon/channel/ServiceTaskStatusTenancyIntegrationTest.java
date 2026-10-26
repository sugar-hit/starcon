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
 * 1.5.2.1 服务与任务状态 的多租户三断言（AGENTS.md 硬性要求，缺一不可）。
 *
 * <ol>
 *   <li><b>两租户互不可见</b>（五个来源的任务都不可见）
 *   <li><b>伪造 tenant header 无效</b>（租户只来自已认证成员关系），
 *       <b>并断言行确实落在会话租户名下</b>（不是只断言 A 看不见）
 *   <li><b>连接池归还后不串租户</b>（池压到 1，A→B→A 交替 5 轮）
 * </ol>
 *
 * <p>🔴 <b>按主键直查那条必须用 {@code channel_app} 连，不能用 Testcontainers 默认的超级用户。</b>
 * 超级用户<b>绕过 RLS</b> —— 用它查「读不到对方数据」会永远得到「读得到」，
 * 那不是隔离被破坏，而是这个断言本身失效了。
 *
 * <p>🔴 本模块读的六张表（{@code sync_run} / {@code sync_failure} / {@code backfill_task} /
 * {@code listing_migration_batch} / {@code publish_task} / {@code listing_collect_task}）
 * 都必须 <b>FORCE RLS</b> —— owner 身份一连就会绕过隔离。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ServiceTaskStatusTenancyIntegrationTest {

  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
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

  @Autowired ServiceTaskStatusService service;
  @Autowired AccessService access;
  @Autowired MockMvc http;

  // ==================================================================
  // 辅助
  // ==================================================================

  <T> T as(AccessService.Access actor, Supplier<T> work) {
    return access.run(actor, "channel.sync.read", work);
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

  /** 一个租户的一条完整任务链：连接 + 两个店铺 + 游标 + 一次运行 + 一条失败 + 五类任务各一条。 */
  UUID seedFullChain(UUID tenantId, String tag) {
    UUID connectionId = UUID.randomUUID();
    seed(
        tenantId,
        "insert into channel_connection (id,tenant_id,platform_id,authorized_account,display_name,status)"
            + " values (?,?,?,?,?,'ACTIVE')",
        connectionId,
        tenantId,
        AMAZON,
        "acct-" + tag,
        "连接 " + tag);
    UUID sourceShop = UUID.randomUUID();
    UUID targetShop = UUID.randomUUID();
    for (UUID shopId : List.of(sourceShop, targetShop)) {
      seed(
          tenantId,
          "insert into shop (id,tenant_id,platform_id,connection_id,code,name) values (?,?,?,?,?,?)",
          shopId,
          tenantId,
          AMAZON,
          connectionId,
          "SHOP-" + tag + "-" + shopId.toString().substring(0, 4),
          "店铺 " + tag);
    }
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
            + " values (?,?,?,'SCHEDULER','FAILED',?::timestamptz,?::timestamptz,1000)",
        runId,
        tenantId,
        cursorId,
        dbNow().minusMinutes(30),
        dbNow().minusMinutes(29));
    seed(
        tenantId,
        "insert into sync_failure (id,tenant_id,run_id,cursor_id,external_key,resource_type,reason,"
            + " detail,payload_digest,attempts,status,occurred_at)"
            + " values (?,?,?,?,?, 'ORDER','AUTHORIZATION_EXPIRED',?,'digest',1,'OPEN',?::timestamptz)",
        UUID.randomUUID(),
        tenantId,
        runId,
        cursorId,
        "ext-" + tag,
        "多租户测试",
        dbNow().minusMinutes(29));

    // 增量补拉
    seed(
        tenantId,
        "insert into backfill_task (id,tenant_id,connection_id,resource_type,window_start,window_end,"
            + " trigger_source,idempotency_key,status,started_at,finished_at,duration_ms)"
            + " values (?,?,?,'ORDER',?::timestamptz,?::timestamptz,'MANUAL',?,'FAILED',"
            + "?::timestamptz,?::timestamptz,1000)",
        UUID.randomUUID(),
        tenantId,
        connectionId,
        dbNow().minusDays(2),
        dbNow().minusDays(1),
        "bf-" + tag,
        dbNow().minusMinutes(25),
        dbNow().minusMinutes(24));

    // 批量搬迁（目标店铺必须真的存在，否则会被数据范围策略藏起来）
    seed(
        tenantId,
        "insert into listing_migration_batch (id,tenant_id,source_shop_id,target_shop_ids,idempotency_key,"
            + " state,candidate_count,total_count,migrated_count,skipped_count,failed_count)"
            + " values (?,?,?,array[?]::uuid[],?,'FAILED',1,1,0,0,1)",
        UUID.randomUUID(),
        tenantId,
        sourceShop,
        targetShop,
        "mig-" + tag);

    // 发布任务（需要 listing + publish_batch）
    UUID listingId = UUID.randomUUID();
    seed(
        tenantId,
        "insert into listing (id,tenant_id,shop_id,external_item_id,title) values (?,?,?,?,?)",
        listingId,
        tenantId,
        sourceShop,
        "ext-" + tag + "-" + listingId.toString().substring(0, 4),
        "待发布 " + tag);
    UUID batchId = UUID.randomUUID();
    seed(
        tenantId,
        "insert into publish_batch (id,tenant_id,listing_ids,limit_count,total_count,pending_count)"
            + " values (?,?,array[?]::uuid[],10,1,1)",
        batchId,
        tenantId,
        listingId);
    seed(
        tenantId,
        "insert into publish_task (id,tenant_id,batch_id,listing_id,platform_id,shop_id,position,state,"
            + " attempts,dispatched_at,completed_at,failure_reason)"
            + " values (?,?,?,?,?,?,0,'FAILED',1,?::timestamptz,?::timestamptz,'平台返回 400')",
        UUID.randomUUID(),
        tenantId,
        batchId,
        listingId,
        AMAZON,
        sourceShop,
        dbNow().minusMinutes(20),
        dbNow().minusMinutes(19));

    // 链接采集
    UUID collectId = UUID.randomUUID();
    seed(
        tenantId,
        "insert into listing_collect_task (id,tenant_id,source_id,source_url,normalized_url,final_url,"
            + " host,state,error_message,http_status,attempt_no,started_at,finished_at,captured_at)"
            + " values (?,?,?,?,?,?,?,'FAILED','',0,1,?::timestamptz,?::timestamptz,?::timestamptz)",
        collectId,
        tenantId,
        COLLECT_SOURCE,
        "https://detail.1688.com/offer/" + collectId + ".html",
        "https://detail.1688.com/offer/" + collectId + ".html",
        "https://detail.1688.com/offer/" + collectId + ".html",
        "detail.1688.com",
        dbNow().minusMinutes(15),
        dbNow().minusMinutes(14),
        dbNow().minusMinutes(14));

    return runId;
  }

  // ==================================================================
  // 断言一：两租户互不可见
  // ==================================================================

  @Test
  @DisplayName("1.5.2.1 断言一：两租户互不可见（五个来源的任务 + 失败队列）")
  void tenantsAreIsolated() {
    UUID runA = seedFullChain(a, "acct-a");
    UUID runB = seedFullChain(b, "acct-b");

    var pageB = as(actorB, () -> service.tasks(null, null, null, null, null, null, 0, 50));
    assertThat(pageB.items())
        .extracting(ServiceTaskStatusModels.ServiceTaskStatusTaskCard::taskId)
        .contains(runB.toString())
        .doesNotContain(runA.toString());
    assertThat(pageB.total()).isEqualTo(5L);

    // 按 id 直取 A 的任务 ⇒ 404
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () -> as(actorB, () -> service.task(runA.toString()))))
        .as("B 按 id 直取 A 的任务必须失败").isNotNull();

    // 总览也只算自己的
    var summaryB = as(actorB, () -> service.summary());
    assertThat(summaryB.totalTasks()).isEqualTo(5L);
    // 五类任务里除「同步运行」外都是失败的 ⇒ 五个来源各一条失败任务。
    assertThat(summaryB.failedCount()).isEqualTo(5L);
    assertThat(summaryB.businessFailureTotal()).isEqualTo(1L);

    // 🔴 按主键直查（用 channel_app 连，超级用户绕过 RLS 会让这个断言永远「通过」）
    assertThat(countAsTenant(b, "select count(*) from sync_run where id = ?", runA))
        .as("B 的上下文里直查 A 的运行必须读到零行").isZero();
    assertThat(countAsTenant(b, "select count(*) from sync_run"))
        .as("B 的上下文里只能看到 B 自己的运行").isEqualTo(1);
    assertThat(countAsTenant(b, "select count(*) from sync_failure"))
        .as("B 的上下文里只能看到 B 自己的失败记录").isEqualTo(1);
    assertThat(countAsTenant(b, "select count(*) from backfill_task")).isEqualTo(1);
    assertThat(countAsTenant(b, "select count(*) from listing_migration_batch")).isEqualTo(1);
    assertThat(countAsTenant(b, "select count(*) from publish_task")).isEqualTo(1);
    assertThat(countAsTenant(b, "select count(*) from listing_collect_task")).isEqualTo(1);

    // 同一机制在本租户自己的数据上必须看得见行 —— 否则上面那些「零行」可能只是查询一直返回 0
    assertThat(countAsTenant(a, "select count(*) from sync_run where id = ?", runA))
        .as("A 的上下文必须能直查 A 自己的运行（证明上面那个 0 是隔离，不是查询失效）")
        .isEqualTo(1);
    assertThat(countAsTenant(a, "select count(*) from sync_failure")).isEqualTo(1);
    assertThat(countAsTenant(a, "select count(*) from backfill_task")).isEqualTo(1);
    assertThat(countAsTenant(a, "select count(*) from listing_migration_batch")).isEqualTo(1);
    assertThat(countAsTenant(a, "select count(*) from publish_task")).isEqualTo(1);
    assertThat(countAsTenant(a, "select count(*) from listing_collect_task")).isEqualTo(1);

    // 🔴 本模块读的每一张事实表都必须 FORCE RLS
    for (String table :
        List.of(
            "sync_run",
            "sync_failure",
            "backfill_task",
            "listing_migration_batch",
            "publish_task",
            "listing_collect_task")) {
      assertThat(scalar("select relforcerowsecurity::text from pg_class where relname = ?", table))
          .as(table + " 必须 FORCE RLS").isEqualTo("true");
    }
  }

  // ==================================================================
  // 断言二：伪造 tenant header 无效
  // ==================================================================

  @Test
  @DisplayName("1.5.2.1 断言二：伪造 tenant header 无效（租户只来自已认证成员关系）")
  void forgedTenantHeaderIsIgnored() throws Exception {
    UUID runA = seedFullChain(a, "forge-a");
    UUID runB = seedFullChain(b, "forge-b");

    // A 的会话 + B 的头 ⇒ 仍然只看到 A 的
    http.perform(get("/channel/service-task-status/tasks").header("Authorization", bearerA)
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(5))
        .andExpect(jsonPath("$.items[?(@.taskId=='" + runB + "')]").isEmpty());

    // A 的会话 + B 的头 ⇒ 按 id 直取 B 的任务仍是 404
    http.perform(get("/channel/service-task-status/tasks/" + runB).header("Authorization", bearerA)
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isNotFound());

    // 无会话一律 401（伪造的头救不了）
    http.perform(get("/channel/service-task-status/summary").header("X-Tenant-Id", b.toString()))
        .andExpect(status().isUnauthorized());

    // 🔴 **并断言行确实落在会话租户名下**（不是只断言 A 看不见 B）
    assertThat(scalar("select tenant_id from sync_run where id = ?", runA))
        .as("A 会话写入的行必须落在 A 的租户名下")
        .isEqualTo(a.toString());
    assertThat(scalar("select tenant_id from sync_run where id = ?", runB))
        .as("B 会话写入的行必须落在 B 的租户名下")
        .isEqualTo(b.toString());
    assertThat(scalar("select count(*) from sync_failure where tenant_id = ?", a)).isEqualTo("1");
    assertThat(scalar("select count(*) from sync_failure where tenant_id = ?", b)).isEqualTo("1");
    assertThat(scalar("select count(*) from backfill_task where tenant_id = ?", a)).isEqualTo("1");
    assertThat(scalar("select count(*) from backfill_task where tenant_id = ?", b)).isEqualTo("1");
    assertThat(scalar("select count(*) from publish_task where tenant_id = ?", a)).isEqualTo("1");
    assertThat(scalar("select count(*) from publish_task where tenant_id = ?", b)).isEqualTo("1");
    assertThat(scalar("select count(*) from listing_collect_task where tenant_id = ?", a))
        .isEqualTo("1");
    assertThat(scalar("select count(*) from listing_collect_task where tenant_id = ?", b))
        .isEqualTo("1");
  }

  // ==================================================================
  // 断言三：连接池归还后不串租户
  // ==================================================================

  @Test
  @DisplayName("1.5.2.1 断言三：连接池归还后不串租户（池压到 1，A→B→A 交替 5 轮）")
  void pooledConnectionsDoNotBleedTenants() {
    UUID runA = seedFullChain(a, "pool-a");
    UUID runB = seedFullChain(b, "pool-b");

    for (int i = 0; i < 5; i++) {
      assertThat(
              as(actorA, () -> service.tasks(null, null, null, null, null, null, 0, 50)).items())
          .as("第 " + (i + 1) + " 轮：A 只看到 A 的任务")
          .extracting(ServiceTaskStatusModels.ServiceTaskStatusTaskCard::taskId)
          .contains(runA.toString())
          .doesNotContain(runB.toString());
      assertThat(
              as(actorB, () -> service.tasks(null, null, null, null, null, null, 0, 50)).items())
          .as("第 " + (i + 1) + " 轮：B 只看到 B 的任务")
          .extracting(ServiceTaskStatusModels.ServiceTaskStatusTaskCard::taskId)
          .contains(runB.toString())
          .doesNotContain(runA.toString());

      var summaryA = as(actorA, () -> service.summary());
      assertThat(summaryA.totalTasks()).as("第 " + (i + 1) + " 轮：A 的总览只算 A 的任务").isEqualTo(5L);
      var summaryB = as(actorB, () -> service.summary());
      assertThat(summaryB.totalTasks()).as("第 " + (i + 1) + " 轮：B 的总览只算 B 的任务").isEqualTo(5L);

      // 🔴 提示清单也不能串：A 的失败提示里**不能出现** B 的任何一条任务。
      var idsB =
          as(actorB, () -> service.tasks(null, null, null, null, null, null, 0, 50))
              .items()
              .stream()
              .map(ServiceTaskStatusModels.ServiceTaskStatusTaskCard::taskId)
              .toList();
      var alertsA = as(actorA, () -> service.alerts());
      assertThat(alertsA.failed())
          .as("第 " + (i + 1) + " 轮：A 的失败提示里不能有 B 的任务")
          .isNotEmpty()
          .allMatch(alert -> !idsB.contains(alert.taskId()));
    }

    // 池里那条连接归还后，租户上下文必须被清掉：直查计数时不能带上前一个租户的行
    assertThat(countAsTenant(b, "select count(*) from sync_run")).isEqualTo(1);
    assertThat(countAsTenant(b, "select count(*) from sync_failure")).isEqualTo(1);
    assertThat(countAsTenant(a, "select count(*) from sync_run")).isEqualTo(1);
    assertThat(countAsTenant(a, "select count(*) from sync_failure")).isEqualTo(1);
  }
}
