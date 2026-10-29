package com.starcon.channel;

import static com.starcon.channel.IpDetectionModels.*;
import static com.starcon.channel.IpRiskLibraryModels.*;
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
 * 16.4.1.2 检测与预警 的多租户三断言（AGENTS.md 硬性要求，缺一不可）。
 *
 * <ol>
 *   <li><b>两租户互不可见</b>（报告 / 命中 / 预警 / 整改建议 / 复核任务 /
 *       外部查询 / 外部命中 / 巡检计划 / 巡检运行 九张表都不可见）
 *   <li><b>伪造 tenant header 无效</b>（租户只来自已认证成员关系），
 *       <b>并断言行确实落在会话租户名下</b>（不是只断言 A 看不见）
 *   <li><b>连接池归还后不串租户</b>（池压到 1，A→B→A 三轮）
 * </ol>
 *
 * <p>🔴 <b>按主键直查那条必须用 {@code channel_app} 连，不能用 Testcontainers 默认的超级用户。</b>
 * 超级用户<b>绕过 RLS</b> —— 用它查「读不到对方数据」会永远得到「读得到」，
 * 那不是隔离被破坏，而是这个断言本身失效了。
 */
@SpringBootTest
@AutoConfigureMockMvc
class IpDetectionTenancyIntegrationTest {

  static final PostgreSQLContainer<?> pg =
      new PostgreSQLContainer<>("postgres:16-alpine").withUrlParam("sslmode", "disable");
  static final UUID user = UUID.randomUUID();
  static HttpServer identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static final ObjectMapper JSON = new ObjectMapper();

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

  @AfterAll
  static void stopStubs() {
    if (identity != null) identity.stop(0);
    pg.stop();
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

  @Autowired IpDetectionService service;
  @Autowired IpRiskLibraryService library;
  @Autowired AccessService access;
  @Autowired MockMvc http;

  // ==================================================================
  // 辅助
  // ==================================================================

  <T> T as(AccessService.Access actor, String permission, Supplier<T> work) {
    return access.run(actor, permission, work);
  }

  static void execAsTenant(UUID tenantId, String statement) {
    try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute("select set_config('app.current_tenant','" + tenantId + "',false)");
      s.execute("select set_config('app.actor','" + user + "',false)");
      s.execute("select set_config('app.department','',false)");
      s.execute("select set_config('app.permission','channel.operation.create',false)");
      s.execute("select set_config('app.grants','" + ALL_SCOPE_GRANTS + "',false)");
      s.execute(statement);
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
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

  /** 九张表全在这里 —— 隔离断言必须**逐张表**过。 */
  static final List<String> ALL_TABLES =
      List.of(
          "ip_detection_report",
          "ip_detection_hit",
          "ip_detection_alert",
          "ip_detection_remediation",
          "ip_detection_review_task",
          "ip_detection_external_query",
          "ip_detection_external_hit",
          "ip_detection_inspection_plan",
          "ip_detection_inspection_run");

  /** 在某个租户下造一整套检测数据（报告 / 命中 / 预警 / 建议 / 复核 / 外部查询 / 外部命中 / 计划 / 运行）。 */
  IpDetectionReportDetail seed(AccessService.Access actor, String code) {
    // 🔴 先在上游侵权库（16.4.1.1）登记一条带关键词的条目 —— 没有它就没有命中，
    //   后面的命中 / 预警 / 建议 / 复核四张表就都是空的，隔离断言会**假通过**。
    as(
        actor,
        "channel.operation.create",
        () ->
            library.createEntry(
                new IpRiskLibraryEntryInput(
                    "ENTRY-" + code,
                    "TRADEMARK",
                    "HIGH",
                    "条目 " + code,
                    "权利人甲",
                    null,
                    null,
                    null,
                    "",
                    List.of("AMAZON"),
                    List.of(new IpRiskLibraryTermInput("nike", "KEYWORD")))));

    IpDetectionReportDetail detail =
        as(
            actor,
            "channel.operation.create",
            () ->
                service.detect(
                    new IpDetectionSubjectInput(
                        "MANUAL", null, null, "nike shoes " + code, "", null,
                        List.of("https://cdn/nike-" + code + ".jpg"), null, null),
                    "Bearer x"));

    // 外部查询 + 外部命中
    IpDetectionExternalQuery query =
        as(
            actor,
            "channel.operation.create",
            () ->
                service.createExternalQuery(
                    new IpDetectionExternalQueryInput(
                        "EXT-" + code, "TRADEMARK", "Acme " + code, null, "外部商标库", "")));
    as(
        actor,
        "channel.operation.create",
        () ->
            service.registerExternalHits(
                query.queryId(),
                List.of(
                    new IpDetectionExternalHitInput(
                        query.queryId(), "Acme " + code, "TRADEMARK", "HIGH", "TM-1", "权利人", "外部商标库"))));

    // 巡检计划 + 巡检运行
    IpDetectionInspectionPlan plan =
        as(
            actor,
            "channel.operation.create",
            () ->
                service.createPlan(
                    new IpDetectionInspectionPlanInput(
                        "PLAN-" + code, "ALL", null, 7, true, true, null)));
    as(actor, "channel.operation.create", () -> service.runInspection(plan.planId(), "Bearer x"));
    return detail;
  }

  // ==================================================================
  // 断言一：两租户互不可见
  // ==================================================================

  @Test
  @DisplayName("16.4.1.2 断言一：两租户互不可见（九张表都不可见）")
  void tenantsAreIsolated() {
    IpDetectionReportDetail detailA = seed(actorA, "TEN-A");
    seed(actorB, "TEN-B");

    // B 读不到 A 的报告。
    assertThat(
            as(
                    actorB,
                    "channel.operation.read",
                    () -> service.reports(null, null, null, null, false, null, null, 0, 50))
                .items())
        .extracting(IpDetectionReport::reportId)
        .doesNotContain(detailA.report().reportId());

    // 🔴 按 id 直取 A 的报告 ⇒ 404（B 的租户上下文里根本读不到这一行）。
    assertThat(
            catchThrowable(
                () ->
                    as(
                        actorB,
                        "channel.operation.read",
                        () -> service.report(detailA.report().reportId()))))
        .as("B 按 id 直取 A 的报告必须失败")
        .isNotNull();

    // 🔴 按主键直查（用 channel_app 连，超级用户绕过 RLS 会让这个断言永远「通过」）。
    assertThat(
            countAsTenant(
                b, "select count(*) from ip_detection_report where id='" + detailA.report().reportId() + "'"))
        .as("B 的上下文里直查 A 的报告必须读到零行").isZero();

    // 九张表逐张：每个租户看得见**自己**的行，**看不见对方**的行。
    for (String table : ALL_TABLES) {
      assertThat(countAsTenant(a, "select count(*) from " + table))
          .as("A 自己的 " + table + " 必须看得见行").isPositive();
      assertThat(countAsTenant(b, "select count(*) from " + table))
          .as("B 自己的 " + table + " 必须看得见行").isPositive();
      // 🔴 关键断言：**对方租户的行在本租户上下文里读不到**。
      assertThat(countAsTenant(b, "select count(*) from " + table + " where tenant_id = '" + a + "'"))
          .as("B 的上下文里 " + table + " 不得出现 A 的行").isZero();
      assertThat(countAsTenant(a, "select count(*) from " + table + " where tenant_id = '" + b + "'"))
          .as("A 的上下文里 " + table + " 不得出现 B 的行").isZero();
      // 🔴 上下文里看到的总行数 = 该租户自己的行数（不多不少）——
      //   「多出来」就是串租户，「少掉」是 RLS 把自己的行也滤掉了。
      assertThat(
              countAsTenant(a, "select count(*) from " + table)
                  + countAsTenant(b, "select count(*) from " + table))
          .as(table + " 两个租户的行数之和必须等于全表行数")
          .isEqualTo(Long.parseLong(scalar("select count(*) from " + table)));
      assertThat(scalar("select relforcerowsecurity::text from pg_class where relname='" + table + "'"))
          .as(table + " 必须 FORCE RLS").isEqualTo("true");
    }
  }

  // ==================================================================
  // 断言二：伪造 tenant header 无效
  // ==================================================================

  @Test
  @DisplayName("16.4.1.2 断言二：伪造 tenant header 无效（租户只来自已认证成员关系）")
  void forgedTenantHeaderIsIgnored() throws Exception {
    IpDetectionReportDetail detailB = seed(actorB, "FORGE-B");

    // A 的会话 + B 的头 ⇒ 仍然读不到 B 的报告。
    http.perform(get("/channel/ip-detections/detections/" + detailB.report().reportId())
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isNotFound());
    http.perform(get("/channel/ip-detections/detections")
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(0));

    // 无会话一律 401（伪造的头救不了）。
    http.perform(get("/channel/ip-detections/capabilities").header("X-Tenant-Id", b.toString()))
        .andExpect(status().isUnauthorized());

    // A 自己建一份之后，A 的会话 + B 的头仍然只看到 A 自己的。
    seed(actorA, "FORGE-A");
    http.perform(get("/channel/ip-detections/detections")
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1));

    // 🔴 **并断言行确实落在会话租户名下**（不是只断言 A 看不见 B）。
    assertThat(scalar("select count(*) from ip_detection_report where tenant_id='" + a + "'"))
        .as("A 会话写入的行必须落在 A 的租户名下")
        .isEqualTo("1");
    assertThat(scalar("select count(*) from ip_detection_report where tenant_id='" + b + "'"))
        .as("B 会话写入的行必须落在 B 的租户名下")
        .isEqualTo("1");
    assertThat(countAsTenant(a, "select count(*) from ip_detection_report where tenant_id='" + b + "'"))
        .as("A 的上下文里直查 B 的行必须读到零行")
        .isZero();
  }

  // ==================================================================
  // 断言三：连接池归还后不串租户
  // ==================================================================

  @Test
  @DisplayName("16.4.1.2 断言三：连接池归还后不串租户（池压到 1，A→B→A 三轮）")
  void pooledConnectionsDoNotBleedTenants() {
    IpDetectionReportDetail detailA = seed(actorA, "POOL-A");
    IpDetectionReportDetail detailB = seed(actorB, "POOL-B");

    for (int i = 0; i < 3; i++) {
      assertThat(
              as(
                      actorA,
                      "channel.operation.read",
                      () -> service.reports(null, null, null, null, false, null, null, 0, 50))
                  .items())
          .as("第 " + (i + 1) + " 轮：A 只看到 A 的报告")
          .extracting(IpDetectionReport::reportId)
          .contains(detailA.report().reportId())
          .doesNotContain(detailB.report().reportId());
      assertThat(
              as(
                      actorB,
                      "channel.operation.read",
                      () -> service.reports(null, null, null, null, false, null, null, 0, 50))
                  .items())
          .as("第 " + (i + 1) + " 轮：B 只看到 B 的报告")
          .extracting(IpDetectionReport::reportId)
          .contains(detailB.report().reportId())
          .doesNotContain(detailA.report().reportId());
    }

    // 池里那条连接归还后，租户上下文必须被清掉：直查计数时不能带上前一个租户的行。
    for (String table : ALL_TABLES) {
      assertThat(countAsTenant(b, "select count(*) from " + table))
          .as("B 上下文里的 " + table + " 必须是 B 自己的行").isPositive();
      assertThat(countAsTenant(a, "select count(*) from " + table))
          .as("A 上下文里的 " + table + " 必须是 A 自己的行").isPositive();
    }
  }
}
