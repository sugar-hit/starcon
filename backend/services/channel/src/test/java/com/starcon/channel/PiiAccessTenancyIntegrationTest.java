package com.starcon.channel;

import static com.starcon.channel.PiiAccessModels.*;
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
 * 16.6.1.1 PII 管理 的多租户三断言（AGENTS.md 硬性要求，缺一不可）。
 *
 * <ol>
 *   <li><b>两租户互不可见</b>（授权配置 / 导出申请 / 导出清单 / 导出凭据 / 访问审计都不可见）
 *   <li><b>伪造 tenant header 无效</b>（租户只来自已认证成员关系）
 *   <li><b>连接池归还后不串租户</b>（池压到 1，A→B→A 三轮）
 * </ol>
 *
 * <p>🔴 <b>按主键直查那条必须用 {@code channel_app} 连，不能用 Testcontainers 默认的超级用户。</b>
 * 超级用户<b>绕过 RLS</b> —— 用它查「读不到对方数据」会永远得到「读得到」，
 * 那不是隔离被破坏，而是这个断言本身失效了。
 */
@SpringBootTest
@AutoConfigureMockMvc
class PiiAccessTenancyIntegrationTest {

  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID user = UUID.randomUUID();
  static HttpServer identity;
  static PiiAccessUpstreamStub upstream;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static final ObjectMapper JSON = new ObjectMapper();

  UUID a, b;
  AccessService.Access actorA, actorB;
  String bearerA, bearerB;

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

  static AccessService.Access actor(UUID uid, UUID tenantId, List<AccessService.Grant> grants) {
    return new AccessService.Access(
        uid, tenantId, "Test", UUID.randomUUID(), null,
        grants.stream().flatMap(g -> g.permissions().stream()).distinct().toList(),
        grants);
  }

  static AccessService.Grant grant(String scope, List<String> permissions) {
    return new AccessService.Grant(UUID.randomUUID(), "SUPPORT", scope, permissions);
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
      upstream = new PiiAccessUpstreamStub();
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
    upstream.reset();
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "channel_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://localhost:" + identity.getAddress().getPort());
    r.add("TRADE_URL", upstream::tradeBase);
    r.add("CRM_URL", upstream::crmBase);
    r.add("INTERNAL_KEY", () -> "test-key");
    // 连接池压到 1：多租户串租户在单连接下最容易暴露。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> "1");
  }

  @Autowired PiiAccessService service;
  @Autowired AccessService access;
  @Autowired MockMvc http;

  // ==================================================================
  // 辅助
  // ==================================================================

  <T> T as(AccessService.Access who, String permission, java.util.function.Supplier<T> work) {
    return access.run(who, permission, work);
  }

  void seedOrder(String orderId, String name) {
    upstream.putOrder(orderId, "SO-" + orderId, OffsetDateTime.now(ZoneOffset.UTC).minusDays(1));
    upstream.putShippingAddress(
        orderId, name, "+1-555-0100", name.replace(" ", ".") + "@example.com", "US", "New York",
        "New York", "Manhattan", "10001", "742 Evergreen Terrace");
  }

  /** 以<b>应用角色 channel_app</b>（非超级用户）直查一行计数 —— 超级用户绕过 RLS 会让断言永远「通过」。 */
  static long countAsTenant(UUID tenantId, String statement) {
    try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), "channel_app", "test-only");
        var s = c.createStatement()) {
      s.execute("select set_config('app.current_tenant','" + tenantId + "',false)");
      s.execute("select set_config('app.actor','" + user + "',false)");
      s.execute("select set_config('app.department','',false)");
      s.execute("select set_config('app.permission','channel.operation.create',false)");
      s.execute("select set_config('app.grants','" + ALL_SCOPE_GRANTS + "',false)");
      try (var rs = s.executeQuery(statement)) {
        return rs.next() ? rs.getLong(1) : 0L;
      }
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  static String scalar(String statement) {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement();
        var rs = s.executeQuery(statement)) {
      return rs.next() ? rs.getString(1) : null;
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  // ==================================================================
  // 断言一：两租户互不可见
  // ==================================================================

  @Test
  @DisplayName("16.6.1.1 断言一：两租户互不可见（授权 / 导出申请 / 清单 / 凭据 / 审计都不可见）")
  void tenantsAreIsolated() {
    seedOrder("o-a", "Alice Smith");
    seedOrder("o-b", "Bob Jones");

    PiiAccessExportRequestView requestA =
        as(
            actorA,
            "channel.operation.create",
            () ->
                service.createExportRequest(
                    actorA,
                    new PiiAccessExportRequestInput(
                        "ORDER", "ADDRESS", 1, "A 租户的排查", List.of("o-a"))));
    as(
        actorB,
        "channel.operation.create",
        () ->
            service.createExportRequest(
                actorB,
                new PiiAccessExportRequestInput(
                    "ORDER", "ADDRESS", 1, "B 租户的排查", List.of("o-b"))));

    // B 读不到 A 的导出申请
    assertThat(
            as(actorB, "channel.operation.read", () -> service.exportRequests(null, null, 0, 50))
                .items())
        .as("B 的租户上下文里读不到 A 的导出申请")
        .extracting(PiiAccessExportRequestView::requestId)
        .doesNotContain(requestA.requestId());

    // B 按 id 直取 A 的导出申请必须失败
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    as(
                        actorB,
                        "channel.operation.read",
                        () -> service.exportRequest(requestA.requestId().toString()))))
        .as("B 按 id 直取 A 的导出申请必须失败")
        .isNotNull();

    // 🔴 按主键直查（用 channel_app 连，超级用户绕过 RLS 会让这个断言永远「通过」）
    assertThat(
            countAsTenant(
                b, "select count(*) from pii_access_export_request where id='" + requestA.requestId() + "'"))
        .as("B 的上下文里直查 A 的导出申请必须读到零行")
        .isZero();
    assertThat(
            countAsTenant(
                b, "select count(*) from pii_access_export_item where export_request_id='"
                + requestA.requestId() + "'"))
        .as("B 的上下文里直查 A 的导出清单必须读到零行")
        .isZero();

    // 六张表都是 FORCE RLS
    for (String table :
        List.of(
            "pii_access_policy",
            "pii_access_grant",
            "pii_access_view_audit",
            "pii_access_export_request",
            "pii_access_export_item",
            "pii_access_export_batch")) {
      assertThat(scalar("select relforcerowsecurity::text from pg_class where relname='" + table + "'"))
          .as(table + " 必须 FORCE RLS")
          .isEqualTo("true");
    }

    // 同一机制在本租户自己的数据上必须看得见行 —— 否则上面那些「零行」可能只是查询一直返回 0
    assertThat(
            countAsTenant(
                a, "select count(*) from pii_access_export_request where id='" + requestA.requestId() + "'"))
        .as("A 的上下文必须能直查 A 自己的导出申请（证明上面那个 0 是隔离，不是查询失效）")
        .isEqualTo(1);
    assertThat(countAsTenant(a, "select count(*) from pii_access_export_item")).isEqualTo(1);
    assertThat(countAsTenant(b, "select count(*) from pii_access_export_request")).isEqualTo(1);
  }

  @Test
  @DisplayName("16.6.1.1 断言一（续）：A 的访问审计在 B 的上下文里读不到")
  void auditIsIsolated() {
    seedOrder("o-audit-a", "Alice Smith");
    as(actorA, "channel.operation.read", () -> service.subject(actorA, bearerA, "ORDER", "o-audit-a", false, null, null));

    assertThat(countAsTenant(b, "select count(*) from pii_access_view_audit")).isZero();
    assertThat(countAsTenant(a, "select count(*) from pii_access_view_audit")).isEqualTo(1);
  }

  // ==================================================================
  // 断言二：伪造 tenant header 无效
  // ==================================================================

  @Test
  @DisplayName("16.6.1.1 断言二：伪造 tenant header 无效（租户只来自已认证成员关系）")
  void forgedTenantHeaderIsIgnored() throws Exception {
    seedOrder("o-hdr", "Alice Smith");
    PiiAccessExportRequestView requestB =
        as(
            actorB,
            "channel.operation.create",
            () ->
                service.createExportRequest(
                    actorB,
                    new PiiAccessExportRequestInput(
                        "ORDER", "ADDRESS", 1, "B 租户的排查", List.of("o-hdr"))));

    // A 的会话 + B 的头 ⇒ 仍然读不到 B 的导出申请
    http.perform(get("/channel/pii-access/exports/" + requestB.requestId())
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isNotFound());
    http.perform(get("/channel/pii-access/exports")
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(0))
        .andExpect(jsonPath("$.total").value(0));

    // 无会话一律 401（伪造的头救不了）
    http.perform(get("/channel/pii-access/capabilities")
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isUnauthorized());

    // A 自己产生一条之后，A 的会话 + B 的头仍然只看到 A 自己的
    as(
        actorA,
        "channel.operation.create",
        () ->
            service.createExportRequest(
                actorA,
                new PiiAccessExportRequestInput(
                    "ORDER", "ADDRESS", 1, "A 租户的排查", List.of("o-hdr"))));
    http.perform(get("/channel/pii-access/exports")
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1));
  }

  // ==================================================================
  // 断言三：连接池归还后不串租户
  // ==================================================================

  @Test
  @DisplayName("16.6.1.1 断言三：连接池归还后不串租户（池压到 1，A→B→A 三轮）")
  void pooledConnectionsDoNotBleedTenants() {
    seedOrder("o-pool", "Alice Smith");
    PiiAccessExportRequestView requestA =
        as(
            actorA,
            "channel.operation.create",
            () ->
                service.createExportRequest(
                    actorA,
                    new PiiAccessExportRequestInput(
                        "ORDER", "ADDRESS", 1, "A 租户的排查", List.of("o-pool"))));
    PiiAccessExportRequestView requestB =
        as(
            actorB,
            "channel.operation.create",
            () ->
                service.createExportRequest(
                    actorB,
                    new PiiAccessExportRequestInput(
                        "ORDER", "ADDRESS", 1, "B 租户的排查", List.of("o-pool"))));

    for (int i = 0; i < 3; i++) {
      assertThat(
              as(actorA, "channel.operation.read", () -> service.exportRequests(null, null, 0, 50))
                  .items())
          .as("第 " + (i + 1) + " 轮：A 只看到 A 的导出申请")
          .extracting(PiiAccessExportRequestView::requestId)
          .containsExactly(requestA.requestId());
      assertThat(
              as(actorB, "channel.operation.read", () -> service.exportRequests(null, null, 0, 50))
                  .items())
          .as("第 " + (i + 1) + " 轮：B 只看到 B 的导出申请")
          .extracting(PiiAccessExportRequestView::requestId)
          .containsExactly(requestB.requestId());
    }

    // 池里那条连接归还后，租户上下文必须被清掉：直查计数时不能带上前一个租户的行
    assertThat(countAsTenant(b, "select count(*) from pii_access_export_request"))
        .as("B 的上下文里只能看到 B 自己的导出申请")
        .isEqualTo(1);
    assertThat(countAsTenant(a, "select count(*) from pii_access_export_request")).isEqualTo(1);
  }
}
