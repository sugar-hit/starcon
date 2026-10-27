package com.starcon.channel;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.OffsetDateTime;
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
 * 10.6.2.1 时效分析的<b>多租户三断言</b>（AGENTS.md 硬性要求，缺一不可）：
 *
 * <ol>
 *   <li><b>两租户互不可见</b>（交叉报表 / 月度 / 异常集中 / 评分 / 明细 / 评分台账）</li>
 *   <li><b>伪造 tenant header 无效</b>（租户只来自已认证成员关系）</li>
 *   <li><b>连接池归还后不串租户</b>（池压到 1，A→B→A 三轮）</li>
 * </ol>
 *
 * <p>🔴 <b>按主键直查那条必须用 {@code channel_app} 连，不能用 Testcontainers 默认的超级用户。</b>
 * 超级用户<b>绕过 RLS</b> —— 用它查「读不到对方数据」会永远得到「读得到」，
 * 那不是隔离被破坏，而是这个断言本身失效了。
 *
 * <p>本模块的夹具是**直接落库**的（不走服务层）：本类的断言是「隔离」，
 * 不是「流程」—— 口径与流程由 {@link LogisticsTimelinessIntegrationTest} 负责。
 */
@SpringBootTest
@AutoConfigureMockMvc
class LogisticsTimelinessTenancyIntegrationTest {

  static final PostgreSQLContainer<?> pg =
      new PostgreSQLContainer<>("postgres:16-alpine").withUrlParam("sslmode", "disable");
  static final UUID user = UUID.randomUUID();
  static HttpServer identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static final ObjectMapper JSON = new ObjectMapper();

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

  static final String ALL_SCOPE_GRANTS =
      "[{\"permissions\":[\"channel.operation.read\",\"channel.operation.create\","
          + "\"channel.operation.update\",\"channel.operation.delete\"],\"scopeMode\":\"ALL\"}]";

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

  @Autowired LogisticsTimelinessService service;
  @Autowired AccessService access;
  @Autowired MockMvc http;

  UUID a, b;
  AccessService.Access actorA, actorB;
  String bearerA, bearerB;

  static final OffsetDateTime SHIPPED = OffsetDateTime.parse("2026-09-02T08:00:00Z");
  static final OffsetDateTime PROMISED = OffsetDateTime.parse("2026-09-05T00:00:00Z");
  static final OffsetDateTime DELIVERED = OffsetDateTime.parse("2026-09-07T12:00:00Z");
  static final String FROM = "2026-09-01";
  static final String TO = "2026-09-30";

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
    truncateAll();
  }

  @AfterAll
  static void stop() {
    identity.stop(0);
    pg.stop();
  }

  void truncateAll() {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      // CASCADE：logistics_tracking_event 等表以外键引用 logistics_tracking_subject。
      s.execute("truncate logistics_timeliness_score, logistics_tracking_subject,"
          + " logistics_channel_config, carrier_logistics_profile cascade");
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * 直接落一整套「渠道 + 国家 + 时效事实」。
   *
   * <p>每个租户落在<b>不同的国家</b>上（A ⇒ US，B ⇒ DE）—— 这样「读到了对方的行」
   * 不会被「同一个国家两边都有」掩盖掉。
   */
  void seed(UUID tenant, String channelCode, String country, int count) {
    UUID carrierId = UUID.randomUUID();
    execAsTenant(
        tenant,
        "insert into carrier_logistics_profile"
            + " (id,tenant_id,role_id,party_id,party_code,party_name)"
            + " values ('" + carrierId + "','" + tenant + "','" + UUID.randomUUID() + "','"
            + UUID.randomUUID() + "','SF-" + country + "','隔离夹具承运商')");
    UUID channelId = UUID.randomUUID();
    execAsTenant(
        tenant,
        "insert into logistics_channel_config"
            + " (id,tenant_id,carrier_profile_id,channel_code,channel_name,status,billing_mode,"
            + "  billing_currency,base_rate,readiness)"
            + " values ('" + channelId + "','" + tenant + "','" + carrierId + "','" + channelCode
            + "','隔离夹具渠道','ACTIVE','BY_WEIGHT','USD',10,'NO_SCOPE')");
    // 补上国家维度后可用性才算 READY（可用性由 DB 触发器算，不接受手工指定）
    execAsTenant(
        tenant,
        "insert into logistics_channel_scope"
            + " (tenant_id,channel_id,scope_kind,scope_value)"
            + " values ('" + tenant + "','" + channelId + "','COUNTRY','" + country + "')");
    execAsTenant(
        tenant,
        "update logistics_channel_config set readiness = 'READY' where id = '" + channelId + "'");
    for (int i = 0; i < count; i++) {
      UUID id = UUID.randomUUID();
      UUID source = UUID.randomUUID();
      execAsTenant(
          tenant,
          "insert into logistics_tracking_subject"
              + " (id,tenant_id,source_shipment_record_id,source_record_no,sales_order_no,"
              + "  outbound_order_no,warehouse_ref,carrier_code,master_waybill_no,"
              + "  destination_country,channel_id,gross_weight_kg,service_level,parcel_count,"
              + "  shipped_at,promised_transit_days_min,promised_transit_days_max,"
              + "  promised_eta_earliest_at,promised_eta_at,matched_promise_count,"
              + "  latest_node,latest_event_code,latest_description,latest_occurred_at,"
              + "  latest_received_at,event_count,exception_flag,exception_count,status,"
              + "  created_at,updated_at)"
              + " values ('" + id + "','" + tenant + "','" + source + "','REC','SO','OB','WH','SF','WB-"
              + source.toString().substring(0, 8) + "','" + country + "','" + channelId
              + "',1.5,'STANDARD',1,'" + SHIPPED + "'::timestamptz,3,3,'"
              + PROMISED.minusDays(2) + "'::timestamptz,'" + PROMISED + "'::timestamptz,1,"
              + "'DELIVERED','DELIVERED','已签收','" + DELIVERED + "'::timestamptz,'" + DELIVERED
              + "'::timestamptz,1,false,0,'DELIVERED',now(),now())");
    }
  }

  static long countAsTenant(UUID tenant, String sql) {
    try (Connection c =
            DriverManager.getConnection(pg.getJdbcUrl(), "channel_app", "test-only");
        var s = c.createStatement()) {
      s.execute("select set_config('app.current_tenant','" + tenant + "',false)");
      s.execute("select set_config('app.actor','" + user + "',false)");
      s.execute("select set_config('app.department','',false)");
      s.execute("select set_config('app.permission','channel.operation.read',false)");
      s.execute("select set_config('app.grants','" + ALL_SCOPE_GRANTS + "',false)");
      try (var rs = s.executeQuery(sql)) {
        return rs.next() ? rs.getLong(1) : 0L;
      }
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  static String scalar(String sql) {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement();
        var rs = s.executeQuery(sql)) {
      return rs.next() ? rs.getString(1) : null;
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  static void execAsTenant(UUID tenant, String sql) {
    try (Connection c =
            DriverManager.getConnection(pg.getJdbcUrl(), "channel_app", "test-only");
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

  <T> T as(AccessService.Access who, String permission, java.util.function.Supplier<T> work) {
    return access.run(who, permission, work);
  }

  // ==================================================================
  // 断言一：两租户互不可见
  // ==================================================================

  @Test
  @DisplayName("10.6.2.1 断言一：两租户互不可见（交叉报表 / 月度 / 异常 / 评分 / 明细 / 台账）")
  void tenantsAreIsolated() {
    seed(a, "CH-A", "US", 12);
    seed(b, "CH-B", "DE", 12);

    // 交叉报表：A 只看到自己的 US 格，B 只看到自己的 DE 格
    var matrixA = as(actorA, "channel.operation.read", () -> service.matrix(FROM, TO, null, null, null, 0, 50));
    var matrixB = as(actorB, "channel.operation.read", () -> service.matrix(FROM, TO, null, null, null, 0, 50));
    assertThat(matrixA.rows()).hasSize(1);
    assertThat(matrixA.rows().get(0).countryCode()).isEqualTo("US");
    assertThat(matrixA.rows().get(0).channelCode()).isEqualTo("CH-A");
    assertThat(matrixB.rows()).hasSize(1);
    assertThat(matrixB.rows().get(0).countryCode()).isEqualTo("DE");
    assertThat(matrixB.rows().get(0).channelCode()).isEqualTo("CH-B");

    // 月度分桶互不可见
    assertThat(as(actorA, "channel.operation.read", () -> service.monthly(FROM, TO, null, null)).rows())
        .hasSize(1);
    assertThat(as(actorB, "channel.operation.read", () -> service.monthly(FROM, TO, null, null)).rows())
        .hasSize(1);

    // 异常集中分析互不可见
    var anomalyA = as(actorA, "channel.operation.read", () -> service.anomalies(FROM, TO));
    var anomalyB = as(actorB, "channel.operation.read", () -> service.anomalies(FROM, TO));
    assertThat(anomalyA.cells()).hasSize(1);
    assertThat(anomalyA.cells().get(0).countryCode()).isEqualTo("US");
    assertThat(anomalyB.cells()).hasSize(1);
    assertThat(anomalyB.cells().get(0).countryCode()).isEqualTo("DE");

    // 评分互不可见
    assertThat(as(actorA, "channel.operation.read", () -> service.scores(FROM, TO, null, null, 0, 50)).rows())
        .hasSize(1);
    assertThat(as(actorB, "channel.operation.read", () -> service.scores(FROM, TO, null, null, 0, 50)).rows())
        .hasSize(1);

    // 逐票明细互不可见
    assertThat(as(actorA, "channel.operation.read", () -> service.subjects(FROM, TO, null, null, null, 0, 50)).rows())
        .hasSize(12);
    assertThat(as(actorB, "channel.operation.read", () -> service.subjects(FROM, TO, null, null, null, 0, 50)).rows())
        .hasSize(12);
    assertThat(as(actorA, "channel.operation.read", () -> service.subjects(FROM, TO, null, "DE", null, 0, 50)).rows())
        .as("A 读不到 B 的 DE 那一格")
        .isEmpty();
    assertThat(as(actorB, "channel.operation.read", () -> service.subjects(FROM, TO, null, "US", null, 0, 50)).rows())
        .as("B 读不到 A 的 US 那一格")
        .isEmpty();

    // 落库的评分台账也必须互不可见
    as(actorA, "channel.operation.create", () -> service.snapshotScores(FROM, TO, null));
    as(actorB, "channel.operation.create", () -> service.snapshotScores(FROM, TO, null));
    assertThat(as(actorA, "channel.operation.read", () -> service.scoreSnapshots(FROM, TO, null, null, 0, 50)).rows())
        .hasSize(1);
    assertThat(as(actorB, "channel.operation.read", () -> service.scoreSnapshots(FROM, TO, null, null, 0, 50)).rows())
        .hasSize(1);

    // 🔴 按主键直查（用 channel_app 连，超级用户绕过 RLS 会让这个断言永远「通过」）
    // 双方各落了一份快照，所以各自上下文里**只该看见自己那一份**。
    assertThat(countAsTenant(a, "select count(*) from logistics_timeliness_score"))
        .as("A 的上下文里只有 A 自己那一份台账").isEqualTo(1);
    assertThat(countAsTenant(b, "select count(*) from logistics_timeliness_score"))
        .as("B 的上下文里只有 B 自己那一份台账").isEqualTo(1);
    // 显式点名对方租户的行 ⇒ 必须零行（RLS 把它们挡在结果之外）
    assertThat(
            countAsTenant(
                b, "select count(*) from logistics_timeliness_score where tenant_id = '" + a + "'"))
        .as("B 的上下文里读不到 A 的评分台账").isZero();
    assertThat(
            countAsTenant(
                a, "select count(*) from logistics_timeliness_score where tenant_id = '" + b + "'"))
        .as("A 的上下文里读不到 B 的评分台账").isZero();

    // 同一机制在本租户自己的数据上必须看得见行 —— 否则上面那些「零行」可能只是查询一直返回 0
    assertThat(countAsTenant(a, "select count(*) from logistics_tracking_subject"))
        .as("A 的上下文必须能读到自己那 12 票")
        .isEqualTo(12);
    assertThat(countAsTenant(b, "select count(*) from logistics_tracking_subject"))
        .isEqualTo(12);
    assertThat(countAsTenant(a, "select count(*) from logistics_tracking_subject where destination_country = 'DE'"))
        .as("A 的上下文里读不到 B 的 DE 那一批")
        .isZero();

    // 两张表都是 FORCE RLS
    for (String table : new String[] {"logistics_timeliness_score", "logistics_tracking_subject"}) {
      assertThat(scalar("select relforcerowsecurity::text from pg_class where relname='" + table + "'"))
          .as(table + " 必须 FORCE RLS").isEqualTo("true");
    }
  }

  // ==================================================================
  // 断言二：伪造 tenant header 无效
  // ==================================================================

  @Test
  @DisplayName("10.6.2.1 断言二：伪造 tenant header 无效（租户只来自已认证成员关系）")
  void forgedTenantHeaderIsIgnored() throws Exception {
    seed(a, "CH-A", "US", 12);
    seed(b, "CH-B", "DE", 12);

    // A 的会话 + B 的头 ⇒ 仍然只看到 A 的 US
    http.perform(
            get("/channel/logistics-timeliness/reports/matrix")
                .header("Authorization", bearerA)
                .header("X-Tenant-Id", b.toString())
                .param("from", FROM)
                .param("to", TO))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rows.length()").value(1))
        .andExpect(jsonPath("$.rows[0].countryCode").value("US"));

    // A 的会话 + A 的头 ⇒ 一样只有 A 的 US
    http.perform(
            get("/channel/logistics-timeliness/reports/matrix")
                .header("Authorization", bearerA)
                .header("X-Tenant-Id", a.toString())
                .param("from", FROM)
                .param("to", TO))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rows.length()").value(1))
        .andExpect(jsonPath("$.rows[0].countryCode").value("US"));

    // 概览也必须按会话里的租户算
    http.perform(
            get("/channel/logistics-timeliness/overview")
                .header("Authorization", bearerA)
                .header("X-Tenant-Id", b.toString())
                .param("from", FROM)
                .param("to", TO))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totals.shippedCount").value(12));
  }

  // ==================================================================
  // 断言三：连接池归还后不串租户
  // ==================================================================

  @Test
  @DisplayName("10.6.2.1 断言三：连接池归还后不串租户（池压到 1，A→B→A 三轮）")
  void connectionPoolDoesNotLeakTenant() {
    seed(a, "CH-A", "US", 12);
    seed(b, "CH-B", "DE", 12);

    for (int round = 0; round < 3; round++) {
      var matrixA =
          as(actorA, "channel.operation.read", () -> service.matrix(FROM, TO, null, null, null, 0, 50));
      assertThat(matrixA.rows()).as("第 " + round + " 轮 A").hasSize(1);
      assertThat(matrixA.rows().get(0).countryCode()).as("第 " + round + " 轮 A").isEqualTo("US");

      var matrixB =
          as(actorB, "channel.operation.read", () -> service.matrix(FROM, TO, null, null, null, 0, 50));
      assertThat(matrixB.rows()).as("第 " + round + " 轮 B").hasSize(1);
      assertThat(matrixB.rows().get(0).countryCode()).as("第 " + round + " 轮 B").isEqualTo("DE");

      // 落库也要按轮次隔离：重算一次不会串到对方的窗口
      as(actorA, "channel.operation.create", () -> service.snapshotScores(FROM, TO, null));
      assertThat(countAsTenant(a, "select count(*) from logistics_timeliness_score"))
          .as("第 " + round + " 轮落库").isEqualTo(1);
      assertThat(countAsTenant(b, "select count(*) from logistics_timeliness_score"))
          .as("第 " + round + " 轮落库").isZero();
    }
  }
}
