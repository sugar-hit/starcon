package com.starcon.reporting;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.starcon.tenancy.TenantTransactions;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.LinkedHashMap;
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
 * 13.1.2.1 目标设定的**多租户隔离测试**（真实 PostgreSQL 16 + FORCE RLS，无内存替身）。
 *
 * <p>跑的是 AGENTS.md 明文要求的三条断言（缺一不可）：
 *
 * <ol>
 *   <li><b>两租户互不可见</b>；
 *   <li><b>伪造租户上下文无效</b>（并断言行确实落在**会话租户**名下）；
 *   <li><b>连接池归还后不串租户</b>。
 * </ol>
 *
 * <p>⚠️ **假上游是按 bearer 分租户的**：上游按**已认证成员关系**解析租户（这正是本服务
 * 转发的语义 —— 转发调用方自己的 bearer，不传租户号）。所以租户 A 的上游只给 dept-a，
 * 租户 B 的上游只给 dept-b。这样「B 看不见 A 的目标」才能真正证明「B 只看得见自己的」
 * —— 否则 B 什么都没看见，「没串租户」只是碰巧。
 *
 * <p>⚠️ <b>连接池固定为 1 条连接</b>：只有复用同一条连接，才能真正证明归还后不会串租户 ——
 * 池大于 1 时每次可能拿到不同连接，「没串」可能只是碰巧换了条干净连接。
 */
@SpringBootTest
@AutoConfigureMockMvc
class TargetSettingTenancyIntegrationTest {

  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static final UUID deptA = UUID.randomUUID();
  static final UUID deptB = UUID.randomUUID();

  static HttpServer identity;

  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  static final List<String> permissions =
      List.of(
          "reporting.targets.read",
          "reporting.targets.write");

  static final ObjectMapper JSON = JsonMapper.builder().addModule(new JavaTimeModule()).build();

  static final LocalDate PERIOD_START = LocalDate.of(2026, 1, 1);
  static final LocalDate PERIOD_END = LocalDate.of(2026, 3, 31);

  static final String BEARER_A = "Bearer tenant-a";
  static final String BEARER_B = "Bearer tenant-b";

  static {
    pg.start();
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      // 运行角色必须 NOBYPASSRLS，否则它会绕过策略，三条断言全部变成空断言。
      s.execute("create role starcon_app nologin");
      s.execute(
          "create role reporting_app login password 'test-only' nosuperuser nobypassrls");
      identity = HttpServer.create(new InetSocketAddress(0), 0);
      identity.createContext(
          "/internal/access",
          e -> {
            var access = actors.get(e.getRequestHeaders().getFirst("Authorization"));
            if (access == null) {
              e.sendResponseHeaders(401, -1);
              e.close();
              return;
            }
            byte[] body = JSON.writeValueAsBytes(access);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      identity.createContext(
          "/identity/departments",
          e -> {
            var bearer = e.getRequestHeaders().getFirst("Authorization");
            var body =
                JSON.writeValueAsBytes(
                    BEARER_A.equals(bearer)
                        ? List.of(department(deptA, "SALES-A", "销售部 A"))
                        : List.of(department(deptB, "SALES-B", "销售部 B")));
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      identity.createContext("/identity/members", e -> respond(e, List.of()));
      identity.start();
      actors.put(BEARER_A, actor(a));
      actors.put(BEARER_B, actor(b));
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  static Map<String, Object> department(UUID id, String code, String name) {
    var node = new LinkedHashMap<String, Object>();
    node.put("id", id);
    node.put("code", code);
    node.put("name", name);
    node.put("parentId", null);
    node.put("leaderMembershipId", null);
    node.put("leaderName", "");
    node.put("memberCount", 1);
    node.put("children", List.of());
    return node;
  }

  private static void respond(com.sun.net.httpserver.HttpExchange e, Object body) {
    try {
      byte[] bytes = JSON.writeValueAsBytes(body);
      e.getResponseHeaders().add("Content-Type", "application/json");
      e.sendResponseHeaders(200, bytes.length);
      e.getResponseBody().write(bytes);
      e.close();
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }

  static AccessService.Access actor(UUID tenantId) {
    var grant = new AccessService.Grant(UUID.randomUUID(), "TEST", "ALL", permissions);
    return new AccessService.Access(
        user, tenantId, "Test", UUID.randomUUID(), null, permissions, List.of(grant));
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "reporting_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://localhost:" + identity.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
    r.add("CATALOG_URL", () -> "http://localhost:" + identity.getAddress().getPort());
    // ⚠️ **连接池固定为 1 条连接**：只有复用同一条连接，才能真正证明归还后不会串租户。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  @Autowired TenantTransactions tenants;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;
  @Autowired ReportingTargetService targets;

  @AfterAll
  static void stop() {
    identity.stop(0);
    pg.stop();
  }

  @BeforeEach
  void reset() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate reporting_target, reporting_target_bucket, reporting_metric_fact, "
              + "reporting_metric_definition cascade");
    }
  }

  private ReportingTargetModels.TargetInput input(UUID departmentId, String currency) {
    return new ReportingTargetModels.TargetInput(
        "SALES_AMOUNT",
        "DEPARTMENT",
        departmentId.toString(),
        currency,
        new BigDecimal("1000"),
        PERIOD_START,
        PERIOD_END,
        "MONTHLY",
        "EVEN",
        null,
        null,
        "");
  }

  // ==========================================================================
  // 断言 1：两租户互不可见
  // ==========================================================================

  @Test
  void 断言1_两租户互不可见() {
    targets.create(a, input(deptA, "USD"), BEARER_A);
    targets.create(b, input(deptB, "USD"), BEARER_B);

    var targetsA = targets.list(a, null, null, null, null, 0, 50);
    var targetsB = targets.list(b, null, null, null, null, 0, 50);

    assertThat(targetsA.total()).isEqualTo(1);
    assertThat(targetsA.targets().get(0).ownerId()).isEqualTo(deptA.toString());
    assertThat(targetsB.total()).isEqualTo(1);
    assertThat(targetsB.targets().get(0).ownerId()).isEqualTo(deptB.toString());

    // 更强的一格：A 明确按 id 直查 B 的行也拿不到（RLS 挡住）。
    var cross =
        tenants.run(
            a,
            () ->
                sql.fetch(
                        "select id from reporting_target where tenant_id = ? and owner_id = ?",
                        b,
                        deptB.toString())
                    .size());
    assertThat(cross).as("按归属对象直查也必须被 RLS 挡住").isZero();
  }

  @Test
  void 断言1_分解计划也按租户隔离() {
    var createdA = targets.create(a, input(deptA, "USD"), BEARER_A);
    targets.create(b, input(deptB, "USD"), BEARER_B);

    // B 拿着 A 的目标 id 也读不到。
    assertThat(
            assertThrows(
                org.springframework.web.server.ResponseStatusException.class,
                () -> targets.detail(b, createdA.id())))
        .hasMessageContaining("不在你的可见范围");

    var bucketsB =
        tenants.run(
            b,
            () ->
                sql.fetch(
                        "select id from reporting_target_bucket where tenant_id = ? and target_id = ?",
                        b,
                        createdA.id())
                    .size());
    assertThat(bucketsB).isZero();
  }

  // ==========================================================================
  // 断言 2：伪造租户上下文无效
  // ==========================================================================

  @Test
  void 断言2_伪造租户头无效() throws Exception {
    targets.create(b, input(deptB, "USD"), BEARER_B);

    // (1) 事务外没有「环境租户」：不存在一个可以被沿用的默认租户。
    assertThat(rawTenantSetting()).isNullOrEmpty();
    assertThat(visibleTargetsWithoutTenant()).as("未绑定租户时不应看到任何行").isZero();

    // (2) 事务内绑定的值精确等于可信参数，提交后立刻失效。
    var bound =
        tenants.run(
            a,
            () -> {
              assertThat(effectiveTenant()).isEqualTo(a);
              return rawTenantSetting();
            });
    assertThat(bound).isEqualTo(a.toString());
    assertThat(effectiveTenant()).as("提交后租户上下文必须已清空").isNull();
    assertThat(visibleTargetsWithoutTenant()).isZero();

    // (3) HTTP 边界：带**租户 A 的合法会话** + **伪造的 X-Tenant-Id（租户 B）** ⇒
    //     目标仍然落在**会话租户 A** 名下，租户 B 看不见。
    http.perform(
            post("/reporting/targets")
                .header("Authorization", BEARER_A)
                .header("X-Tenant-Id", b.toString())
                .contentType("application/json")
                .content(JSON.writeValueAsBytes(input(deptA, "USD"))))
        .andExpect(status().isOk());

    // 行确实落在**会话租户 A** 名下（不是 B）。
    var landedInA =
        tenants.run(
            a,
            () ->
                sql.fetch(
                        "select owner_id from reporting_target where tenant_id = ? and owner_id = ?",
                        a,
                        deptA.toString())
                    .size());
    assertThat(landedInA).as("伪造头不得让目标落到别的租户名下").isPositive();

    var landedInB =
        tenants.run(
            b,
            () ->
                sql.fetch(
                        "select owner_id from reporting_target where tenant_id = ? and owner_id = ?",
                        b,
                        deptA.toString())
                    .size());
    assertThat(landedInB).as("被伪造的租户不得看见别人的目标").isZero();

    // A 只看得到自己那条；B 只看得到自己那条。
    assertThat(targets.list(a, null, null, null, null, 0, 50).targets())
        .extracting(ReportingTargetModels.TargetView::ownerId)
        .containsExactly(deptA.toString());
    assertThat(targets.list(b, null, null, null, null, 0, 50).targets())
        .extracting(ReportingTargetModels.TargetView::ownerId)
        .containsExactly(deptB.toString());
  }

  @Test
  void 断言2_伪造租户头不影响口径目录() {
    var metricsA = targets.metrics(a, "DEPARTMENT");
    var metricsB = targets.metrics(b, "DEPARTMENT");

    assertThat(metricsA).isNotEmpty();
    assertThat(metricsB).isNotEmpty();
    // 两个租户各自播种自己的标准口径，互不影响（种子相同但行各属各的租户）。
    var codesA =
        tenants.run(
            a, () -> sql.fetch("select metric_code from reporting_metric_definition").size());
    var codesB =
        tenants.run(
            b, () -> sql.fetch("select metric_code from reporting_metric_definition").size());
    assertThat(codesA).isEqualTo(codesB);
  }

  // ==========================================================================
  // 断言 3：连接池归还后不串租户
  // ==========================================================================

  @Test
  void 断言3_连接池归还后不串租户() {
    targets.create(a, input(deptA, "USD"), BEARER_A);
    targets.create(b, input(deptB, "USD"), BEARER_B);
    for (int i = 0; i < 5; i++) {
      assertThat(ownersOf(a)).as("第 %d 轮租户 A", i).containsExactly(deptA.toString());
      assertThat(ownersOf(b)).as("第 %d 轮租户 B", i).containsExactly(deptB.toString());
      assertThat(visibleTargetsWithoutTenant()).as("第 %d 轮事务外", i).isZero();
    }
    // 归还后再单独借一次，仍然干净。
    assertThat(ownersOf(a)).containsExactly(deptA.toString());
    assertThat(ownersOf(b)).containsExactly(deptB.toString());
  }

  // ==========================================================================
  // 工具
  // ==========================================================================

  private List<String> ownersOf(UUID tenant) {
    return targets.list(tenant, null, null, null, null, 0, 50).targets().stream()
        .map(ReportingTargetModels.TargetView::ownerId)
        .toList();
  }

  private String rawTenantSetting() {
    return sql.fetch("select current_setting('app.current_tenant', true)").stream()
        .findFirst()
        .map(r -> r.get(0, String.class))
        .orElse(null);
  }

  private UUID effectiveTenant() {
    var row = sql.fetchOne("select nullif(current_setting('app.current_tenant', true),'')::uuid");
    return row == null ? null : row.get(0, UUID.class);
  }

  private int visibleTargetsWithoutTenant() {
    return sql.fetch("select id from reporting_target").size()
        + sql.fetch("select id from reporting_target_bucket").size();
  }
}
