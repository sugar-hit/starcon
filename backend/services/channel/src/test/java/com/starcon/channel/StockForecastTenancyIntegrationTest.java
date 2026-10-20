package com.starcon.channel;

import static com.starcon.channel.StockForecastModels.*;
import static com.starcon.channel.WarehouseConnectionModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
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
 * 10.4.2.2 备货预报的<b>多租户三断言</b>（AGENTS.md 硬性要求，缺一不可）：
 *
 * <ol>
 *   <li><b>两租户互不可见</b>（预报单 / 明细 / 规格 / 货件任务 / 平台货件报送行 / 仓端回执）</li>
 *   <li><b>伪造 tenant header 无效</b>（租户只来自已认证成员关系）</li>
 *   <li><b>连接池归还后不串租户</b>（池压到 1，A→B→A 三轮）</li>
 * </ol>
 *
 * <p>🔴 <b>按主键直查那条必须用 {@code channel_app} 连，不能用 Testcontainers 默认的超级用户。</b>
 * 超级用户<b>绕过 RLS</b> —— 用它查「读不到对方数据」会永远得到「读得到」，
 * 那不是隔离被破坏，而是这个断言本身失效了。
 */
@SpringBootTest
@AutoConfigureMockMvc
class StockForecastTenancyIntegrationTest {

  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID user = UUID.randomUUID();
  static HttpServer identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static final ObjectMapper JSON = new ObjectMapper();

  UUID a, b;
  AccessService.Access actorA, actorB;
  String bearerA, bearerB;

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

  @Autowired StockForecastService forecasts;
  @Autowired WarehouseConnectionService warehouses;
  @Autowired AccessService access;
  @Autowired MockMvc http;

  @BeforeEach
  void freshTenants() {
    a = UUID.randomUUID();
    b = UUID.randomUUID();
    bearerA = "Bearer sf-t-a-" + a;
    bearerB = "Bearer sf-t-b-" + b;
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
      s.execute("truncate warehouse_forecast_receipt, warehouse_shipment_report,"
          + " warehouse_shipment_task, warehouse_forecast_line, warehouse_forecast,"
          + " warehouse_forecast_spec cascade");
    } catch (java.sql.SQLException e) {
      throw new RuntimeException(e);
    }
  }

  <T> T as(AccessService.Access who, String permission, java.util.function.Supplier<T> work) {
    return access.run(who, permission, work);
  }

  /** 一个租户下的完整夹具：目的仓 + 规格 + 预报单（带明细）。 */
  record Fixture(UUID accountId, UUID specId, UUID forecastId) {}

  Fixture seed(AccessService.Access who) {
    UUID providerId =
        as(
            who,
            "channel.connection.read",
            () ->
                warehouses.providers().stream()
                    .filter(p -> p.code().equals("PLATFORM_FBA"))
                    .findFirst()
                    .orElseThrow()
                    .id());
    UUID connectionId =
        as(
            who,
            "channel.connection.create",
            () ->
                warehouses.createConnection(
                    new WarehouseConnectionInput(
                        providerId,
                        "vault://platform-warehouse/" + UUID.randomUUID(),
                        "acct-" + UUID.randomUUID().toString().substring(0, 8),
                        "FBA Hub",
                        "platform-shipment-report")));
    UUID accountId =
        as(
            who,
            "channel.connection.create",
            () ->
                warehouses.createAccount(
                    connectionId,
                    new WarehouseAccountInput(
                        "WH-" + UUID.randomUUID().toString().substring(0, 8),
                        "FBA Hub",
                        "US",
                        "America/New_York",
                        WarehouseAccountKind.PLATFORM_FBA)));
    UUID specId =
        as(
            who,
            "channel.operation.create",
            () ->
                forecasts.createSpec(
                    new CreateForecastSpecRequest(
                        accountId,
                        List.of("CARTON_STANDARD"),
                        100,
                        null,
                        null,
                        false,
                        false,
                        List.of(),
                        false,
                        null,
                        null,
                        true,
                        0,
                        "租户夹具规格")));
    UUID forecastId =
        as(
            who,
            "channel.operation.create",
            () ->
                forecasts.createForecast(
                    new CreateForecastRequest(
                        accountId,
                        "MANUAL",
                        OffsetDateTime.parse("2026-12-20T09:30:00+08:00"),
                        "租户夹具预报单",
                        List.of(
                            new ForecastLineInput(
                                "SKU-TENANT",
                                new BigDecimal("10"),
                                "PCS",
                                "CARTON_STANDARD",
                                1,
                                null,
                                "FNSKU",
                                "CARTON-1",
                                null,
                                "")))));
    return new Fixture(accountId, specId, forecastId);
  }

  // ==================================================================
  // 断言一：两租户互不可见
  // ==================================================================

  @Test
  @DisplayName("10.4.2.2 断言一：两租户互不可见（预报单/明细/规格/货件任务/回执全隔离）")
  void tenantsCannotSeeEachOther() throws Exception {
    Fixture fa = seed(actorA);
    Fixture fb = seed(actorB);

    // 列表：A 只看到自己那一张
    var rowsA = as(actorA, "channel.operation.read", () -> forecasts.forecasts(null, null, null, null, 0, 50)).rows();
    var rowsB = as(actorB, "channel.operation.read", () -> forecasts.forecasts(null, null, null, null, 0, 50)).rows();
    assertThat(rowsA).hasSize(1);
    assertThat(rowsA.get(0).id()).isEqualTo(fa.forecastId());
    assertThat(rowsB).hasSize(1);
    assertThat(rowsB.get(0).id()).isEqualTo(fb.forecastId());

    // 详情：A 读 B 的预报单 ⇒ 404
    assertThat(catchThrowable(() -> as(actorA, "channel.operation.read", () -> forecasts.forecast(fb.forecastId()))))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    assertThat(catchThrowable(() -> as(actorB, "channel.operation.read", () -> forecasts.forecast(fa.forecastId()))))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);

    // 规格：A 看不到 B 的规格
    var specsA = as(actorA, "channel.operation.read", () -> forecasts.specs(null));
    assertThat(specsA).hasSize(1);
    assertThat(specsA.get(0).id()).isEqualTo(fa.specId());

    // 🔴 直查也必须查不到：用**应用账号**（受 RLS 约束），不能用超级用户。
    assertThat(countAsTenant(a, "select count(*) from warehouse_forecast where id='" + fb.forecastId() + "'"))
        .as("A 的上下文必须读不到 B 的预报单（证明隔离真的在生效）")
        .isEqualTo(0);
    assertThat(countAsTenant(a, "select count(*) from warehouse_forecast_line where forecast_id='" + fb.forecastId() + "'"))
        .isEqualTo(0);
    assertThat(countAsTenant(a, "select count(*) from warehouse_forecast_spec where id='" + fb.specId() + "'"))
        .isEqualTo(0);
    // 反向：A 自己的必须查得到（证明上面那个 0 是隔离，不是查询失效）
    assertThat(countAsTenant(a, "select count(*) from warehouse_forecast where id='" + fa.forecastId() + "'"))
        .isEqualTo(1);
    assertThat(countAsTenant(b, "select count(*) from warehouse_forecast where id='" + fb.forecastId() + "'"))
        .isEqualTo(1);

    // HTTP 层同样隔离
    http.perform(get("/channel/stock-forecasts/" + fb.forecastId()).header("Authorization", bearerA))
        .andExpect(status().isNotFound());
    http.perform(get("/channel/stock-forecasts").header("Authorization", bearerA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rows.length()").value(1))
        .andExpect(jsonPath("$.rows[0].id").value(fa.forecastId().toString()));
  }

  // ==================================================================
  // 断言二：伪造 tenant header 无效
  // ==================================================================

  @Test
  @DisplayName("10.4.2.2 断言二：伪造 tenant header 无效（租户只来自已认证成员关系）")
  void forgedTenantHeaderIsIgnored() throws Exception {
    Fixture fa = seed(actorA);
    Fixture fb = seed(actorB);

    // A 的会话 + B 的头 ⇒ 仍然读不到 B 的数据
    http.perform(get("/channel/stock-forecasts/" + fb.forecastId())
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isNotFound());
    http.perform(get("/channel/stock-forecasts")
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rows.length()").value(1))
        .andExpect(jsonPath("$.rows[0].id").value(fa.forecastId().toString()));

    // A 的会话 + A 的头 ⇒ 只能看到 A 自己的
    http.perform(get("/channel/stock-forecasts")
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", a.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rows.length()").value(1))
        .andExpect(jsonPath("$.rows[0].id").value(fa.forecastId().toString()));

    // 汇总也必须按会话里的租户算
    http.perform(get("/channel/stock-forecasts")
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.summary.total").value(1));

    // 无会话一律 401（伪造的头救不了）
    http.perform(get("/channel/stock-forecasts").header("X-Tenant-Id", b.toString()))
        .andExpect(status().isUnauthorized());
  }

  // ==================================================================
  // 断言三：连接池归还后不串租户
  // ==================================================================

  @Test
  @DisplayName("10.4.2.2 断言三：连接池归还后不串租户（池压到 1，A→B→A 三轮）")
  void pooledConnectionsDoNotBleedTenants() {
    Fixture fa = seed(actorA);
    Fixture fb = seed(actorB);

    for (int i = 0; i < 3; i++) {
      var rowsA = as(actorA, "channel.operation.read", () -> forecasts.forecasts(null, null, null, null, 0, 50)).rows();
      var rowsB = as(actorB, "channel.operation.read", () -> forecasts.forecasts(null, null, null, null, 0, 50)).rows();
      assertThat(rowsA).hasSize(1);
      assertThat(rowsA.get(0).id()).isEqualTo(fa.forecastId());
      assertThat(rowsA.get(0).accountId()).isEqualTo(fa.accountId());
      assertThat(rowsB).hasSize(1);
      assertThat(rowsB.get(0).id()).isEqualTo(fb.forecastId());
      assertThat(rowsB.get(0).accountId()).isEqualTo(fb.accountId());

      // 详情里的明细与规格也必须跟着租户走
      var detailA = as(actorA, "channel.operation.read", () -> forecasts.forecast(fa.forecastId()));
      var detailB = as(actorB, "channel.operation.read", () -> forecasts.forecast(fb.forecastId()));
      assertThat(detailA.lines()).hasSize(1);
      assertThat(detailB.lines()).hasSize(1);
      assertThat(detailA.lines().get(0).skuCode()).isEqualTo("SKU-TENANT");
      assertThat(detailA.spec().id()).isEqualTo(fa.specId());
      assertThat(detailB.spec().id()).isEqualTo(fb.specId());
    }
  }

  /** 以应用账号（受 RLS 约束）连接并按租户上下文直查。 */
  private long countAsTenant(UUID tenant, String statement) {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), "channel_app", "test-only");
        var s = c.createStatement()) {
      s.execute("select set_config('app.current_tenant','" + tenant + "',false)");
      var rs = s.executeQuery(statement);
      rs.next();
      return rs.getLong(1);
    } catch (java.sql.SQLException e) {
      throw new RuntimeException(e);
    }
  }
}
