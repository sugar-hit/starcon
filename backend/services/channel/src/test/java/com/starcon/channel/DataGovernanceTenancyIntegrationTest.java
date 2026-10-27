package com.starcon.channel;

import static com.starcon.channel.DataGovernanceModels.*;
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
import org.jooq.DSLContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 16.6.2.1 数据出境与留存的<b>多租户三断言</b>（AGENTS.md 硬性要求，缺一不可）。
 *
 * <ol>
 *   <li><b>两租户互不可见</b>（数据域 / 传输登记 / 留存策略 / 冲突台账 / 删除请求 /
 *       清理工单 / 清理执行记录都不可见）</li>
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
class DataGovernanceTenancyIntegrationTest {

  static final PostgreSQLContainer<?> pg =
      new PostgreSQLContainer<>("postgres:16-alpine").withUrlParam("sslmode", "disable");
  static final UUID user = UUID.randomUUID();
  static HttpServer identity;
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
    r.add("INTERNAL_KEY", () -> "test-key");
    // 连接池压到 1：多租户串租户在单连接下最容易暴露。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> "1");
  }

  @Autowired DataGovernanceService service;
  @Autowired AccessService access;
  @Autowired MockMvc http;

  // ==================================================================
  // 辅助
  // ==================================================================

  <T> T as(AccessService.Access who, String permission, java.util.function.Supplier<T> work) {
    return access.run(who, permission, work);
  }

  /** 在某个租户上下文里登记数据域 + 留存策略 + 传输登记 + 删除请求 + 清理工单。 */
  void seed(AccessService.Access who, String subjectRef) {
    DataGovernanceDomainInput domainInput =
        new DataGovernanceDomainInput("ORDER", "9 订单", "9 订单（trade 服务）", "PII", true, false, "");
    as(who, "channel.operation.update", () -> service.saveDomain(domainInput));

    RetentionPolicyInput policyInput =
        new RetentionPolicyInput(
            "ORDER",
            "普通留存策略",
            "STANDARD",
            java.math.BigDecimal.valueOf(180),
            "CREATION",
            "测试依据",
            OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(10));
    as(who, "channel.operation.create", () -> service.createPolicy(who, policyInput));

    DataGovernanceTransferInput transferInput =
        new DataGovernanceTransferInput(
            "ORDER",
            "DE",
            "某德国物流商",
            "CONTRACT",
            "SCC 2021/914",
            "TIA-2026-001",
            OffsetDateTime.now(ZoneOffset.UTC).minusDays(30),
            "");
    as(who, "channel.operation.create", () -> service.createTransfer(who, transferInput));

    DataGovernanceDeletionRequestInput deletionInput =
        new DataGovernanceDeletionRequestInput(
            "FORGETTING_RIGHT", "ORDER", subjectRef, "ORDER", "买家要求删除个人数据");
    as(who, "channel.operation.create", () -> service.createDeletionRequest(who, deletionInput));

    RetentionCleanupOrderInput cleanupInput =
        new RetentionCleanupOrderInput("ORDER", subjectRef, "MANUAL", null, null);
    as(who, "channel.operation.create", () -> service.createCleanupOrder(who, cleanupInput));
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
  @DisplayName("16.6.2.1 断言一：两租户互不可见（域 / 传输 / 策略 / 台账 / 删除请求 / 工单 / 执行记录）")
  void tenantsAreIsolated() {
    seed(actorA, "SO-A");
    seed(actorB, "SO-B");

    // B 读不到 A 的任何东西
    assertThat(as(actorB, "channel.operation.read", () -> service.domains(null, 0, 50)).total())
        .as("B 的租户上下文里只有 B 自己的数据域")
        .isEqualTo(1);
    assertThat(as(actorB, "channel.operation.read", () -> service.transfers(null, null, 0, 50)).total())
        .as("B 的租户上下文里读不到 A 的传输登记")
        .isEqualTo(1);
    assertThat(as(actorB, "channel.operation.read", () -> service.policies(null, 0, 50)).total())
        .as("B 的租户上下文里读不到 A 的留存策略")
        .isEqualTo(1);
    assertThat(as(actorB, "channel.operation.read", () -> service.conflicts(null, 0, 50)).total())
        .as("B 的租户上下文里读不到 A 的数据域裁决")
        .isEqualTo(1);
    assertThat(
            as(actorB, "channel.operation.read", () -> service.deletionRequests(null, null, 0, 50)).total())
        .as("B 的租户上下文里读不到 A 的删除请求")
        .isEqualTo(1);
    assertThat(as(actorB, "channel.operation.read", () -> service.cleanupOrders(null, null, 0, 50)).total())
        .as("B 的租户上下文里读不到 A 的清理工单")
        .isEqualTo(1);

    // 🔴 按主键直查（用 channel_app 连，超级用户绕过 RLS 会让这个断言永远「通过」）
    for (String table :
        List.of(
            "data_governance_data_domain",
            "data_governance_transfer",
            "retention_policy",
            "retention_conflict",
            "data_governance_deletion_request",
            "retention_cleanup_work_order",
            "retention_cleanup_record")) {
      assertThat(countAsTenant(b, "select count(*) from " + table + " where tenant_id <> '" + b + "'"))
          .as("B 的上下文里直查 " + table + " 必须读到零行")
          .isZero();
    }

    // 七张表都是 FORCE RLS
    for (String table :
        List.of(
            "data_governance_data_domain",
            "data_governance_transfer",
            "retention_policy",
            "retention_conflict",
            "data_governance_deletion_request",
            "retention_cleanup_work_order",
            "retention_cleanup_record")) {
      assertThat(scalar("select relforcerowsecurity::text from pg_class where relname='" + table + "'"))
          .as(table + " 必须 FORCE RLS")
          .isEqualTo("true");
    }

    // 同一机制在本租户自己的数据上必须看得见行 —— 否则上面那些「零行」可能只是查询一直返回 0
    assertThat(countAsTenant(a, "select count(*) from data_governance_data_domain")).isEqualTo(1);
    assertThat(countAsTenant(b, "select count(*) from data_governance_data_domain")).isEqualTo(1);
    assertThat(countAsTenant(a, "select count(*) from retention_cleanup_work_order")).isEqualTo(1);
    assertThat(countAsTenant(b, "select count(*) from retention_cleanup_work_order")).isEqualTo(1);
  }

  @Test
  @DisplayName("16.6.2.1 断言一（续）：A 的清理执行记录在 B 的上下文里读不到")
  void cleanupRecordsAreIsolated() {
    seed(actorA, "SO-A");
    RetentionCleanupOrderView order =
        as(actorA, "channel.operation.read", () -> service.cleanupOrders(null, null, 0, 50)).items().get(0);
    RetentionCleanupExecuteInput execute =
        new RetentionCleanupExecuteInput("SUCCESS", new java.math.BigDecimal("3"), "已清理 3 条");
    as(
        actorA,
        "channel.operation.update",
        () -> service.executeCleanupOrder(actorA, order.orderId().toString(), execute));

    assertThat(countAsTenant(b, "select count(*) from retention_cleanup_record")).isZero();
    assertThat(countAsTenant(a, "select count(*) from retention_cleanup_record")).isEqualTo(1);
  }

  // ==================================================================
  // 断言二：伪造 tenant header 无效
  // ==================================================================

  @Test
  @DisplayName("16.6.2.1 断言二：伪造 tenant header 无效（租户只来自已认证成员关系）")
  void forgedTenantHeaderIsIgnored() throws Exception {
    seed(actorA, "SO-A");
    seed(actorB, "SO-B");

    // A 的会话 + B 的头 ⇒ 仍然只看得到 A 自己的数据
    http.perform(get("/channel/data-governance/domains")
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.items[0].domainCode").value("ORDER"));

    http.perform(get("/channel/data-governance/deletion-requests")
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.items[0].subjectRef").value("SO-A"));

    // 无会话一律 401（伪造的头救不了）
    http.perform(get("/channel/data-governance/capabilities")
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isUnauthorized());

    // B 的会话 + A 的头 ⇒ 只看得到 B 自己的
    http.perform(get("/channel/data-governance/deletion-requests")
            .header("Authorization", bearerB)
            .header("X-Tenant-Id", a.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.items[0].subjectRef").value("SO-B"));
  }

  // ==================================================================
  // 断言三：连接池归还后不串租户
  // ==================================================================

  @Test
  @DisplayName("16.6.2.1 断言三：连接池归还后不串租户（池压到 1，A→B→A 三轮）")
  void pooledConnectionsDoNotBleedTenants() {
    seed(actorA, "SO-A");
    seed(actorB, "SO-B");

    for (int i = 0; i < 3; i++) {
      assertThat(
              as(actorA, "channel.operation.read", () -> service.deletionRequests(null, null, 0, 50))
                  .items())
          .as("第 " + (i + 1) + " 轮：A 只看到 A 的删除请求")
          .extracting(DataGovernanceDeletionRequestView::subjectRef)
          .containsExactly("SO-A");
      assertThat(
              as(actorB, "channel.operation.read", () -> service.deletionRequests(null, null, 0, 50))
                  .items())
          .as("第 " + (i + 1) + " 轮：B 只看到 B 的删除请求")
          .extracting(DataGovernanceDeletionRequestView::subjectRef)
          .containsExactly("SO-B");
    }

    // 池里那条连接归还后，租户上下文必须被清掉：直查计数时不能带上前一个租户的行
    assertThat(countAsTenant(b, "select count(*) from data_governance_deletion_request"))
        .as("B 的上下文里只能看到 B 自己的删除请求")
        .isEqualTo(1);
    assertThat(countAsTenant(a, "select count(*) from data_governance_deletion_request")).isEqualTo(1);
    assertThat(countAsTenant(b, "select count(*) from retention_policy")).isEqualTo(1);
    assertThat(countAsTenant(a, "select count(*) from retention_policy")).isEqualTo(1);
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
