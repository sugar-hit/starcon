package com.starcon.reporting;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.starcon.tenancy.Ids;
import com.starcon.tenancy.TenantTransactions;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.LocalDate;
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
 * 13.1.2.2 达成追踪的**多租户隔离测试**（真实 PostgreSQL 16 + FORCE RLS，无内存替身）。
 *
 * <p>跑的是 AGENTS.md 明文要求的三条断言（缺一不可）：
 *
 * <ol>
 *   <li><b>两租户互不可见</b>；</li>
 *   <li><b>伪造租户上下文无效</b>（并断言行确实落在**会话租户**名下）；</li>
 *   <li><b>连接池归还后不串租户</b>。
 * </ol>
 *
 * <p>⚠️ <b>连接池固定为 1 条连接</b>：只有复用同一条连接，才能真正证明归还后不会串租户 ——
 * 池大于 1 时每次可能拿到不同连接，「没串」可能只是碰巧换了条干净连接。
 */
@SpringBootTest
@AutoConfigureMockMvc
class TargetAttainmentTenancyIntegrationTest {

  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();

  static HttpServer identity;

  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  static final List<String> permissions =
      List.of(
          "reporting.target-attainment.read", "reporting.target-attainment.write");

  static final ObjectMapper JSON = JsonMapper.builder().addModule(new JavaTimeModule()).build();

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
      identity.start();
      actors.put(BEARER_A, actor(a));
      actors.put(BEARER_B, actor(b));
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
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
  @Autowired ReportingTargetAttainmentService attainment;

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
          "truncate reporting_target_attainment, reporting_target, reporting_target_bucket, "
              + "reporting_store_metric_fact, reporting_store_metric_definition, "
              + "reporting_metric_fact, reporting_metric_definition cascade");
    }
  }

  // ==========================================================================
  // 夹具
  // ==========================================================================

  /** 播种一套店铺目标（**每个租户播自己的**）。 */
  private UUID seedTarget(UUID tenantId, String shopCode, LocalDate start, LocalDate end) {
    var id = Ids.next();
    tenants.run(
        tenantId,
        () -> {
          sql.execute(
              "insert into reporting_store_metric_definition (id,tenant_id,metric_code,display_name,"
                  + "unit,aggregation,currency_aware,description,owner_module) "
                  + "values (?,?,?,?,?,?,?,?,?) on conflict (tenant_id, metric_code) do nothing",
              Ids.next(),
              tenantId,
              "STORE_GMV_PLATFORM",
              "平台侧成交金额",
              "CURRENCY",
              "SUM",
              true,
              "各平台自己的 GMV 口径。",
              "13.1.1.2");
          sql.execute(
              "insert into reporting_target (id,tenant_id,metric_code,scope_type,owner_id,owner_code,"
                  + "owner_name,currency,target_value,baseline_status,period_start,period_end,"
                  + "granularity,decomposition_method,status) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
              id,
              tenantId,
              "STORE_GMV_PLATFORM",
              "SHOP",
              shopCode,
              shopCode,
              shopCode,
              "USD",
              new BigDecimal("1000"),
              "NO_DATA",
              start,
              end,
              "MONTHLY",
              "EVEN",
              "ACTIVE");
          sql.execute(
              "insert into reporting_target_bucket (id,tenant_id,target_id,bucket_start,bucket_end,"
                  + "granularity,currency,target_value,weight) values (?,?,?,?,?,?,?,?,?)",
              Ids.next(),
              tenantId,
              id,
              start,
              end,
              "MONTHLY",
              "USD",
              new BigDecimal("1000"),
              new BigDecimal("1"));
          return null;
        });
    return id;
  }

  /** 上一个月（用数据库时钟算，避开 JVM 墙钟）。 */
  private LocalDate[] lastMonth(UUID tenantId) {
    return tenants.run(
        tenantId,
        () -> {
          var today =
              sql.fetchOne("select (now() at time zone 'UTC')::date").get(0, LocalDate.class);
          var start = today.withDayOfMonth(1).minusMonths(1);
          return new LocalDate[] {start, start.withDayOfMonth(start.lengthOfMonth())};
        });
  }

  // ==========================================================================
  // 断言 1：两租户互不可见
  // ==========================================================================

  @Test
  void 断言1_两租户互不可见() {
    var periodA = lastMonth(a);
    var periodB = lastMonth(b);
    seedTarget(a, "SHOP-A", periodA[0], periodA[1]);
    seedTarget(b, "SHOP-B", periodB[0], periodB[1]);

    var boardA = attainment.board(a, null, null, null, null, 0, 50);
    var boardB = attainment.board(b, null, null, null, null, 0, 50);

    assertThat(boardA.total()).isEqualTo(1);
    assertThat(boardA.groups().get(0).rows().get(0).ownerId()).isEqualTo("SHOP-A");
    assertThat(boardB.total()).isEqualTo(1);
    assertThat(boardB.groups().get(0).rows().get(0).ownerId()).isEqualTo("SHOP-B");

    // 更强的一格：A 明确按 id 直查 B 的行也拿不到（RLS 挡住）。
    var cross =
        tenants.run(
            a,
            () ->
                sql.fetch(
                        "select id from reporting_target where tenant_id = ? and owner_id = ?", b, "SHOP-B")
                    .size());
    assertThat(cross).as("按归属对象直查也必须被 RLS 挡住").isZero();
  }

  @Test
  void 断言1_快照也按租户隔离() {
    var periodA = lastMonth(a);
    var periodB = lastMonth(b);
    seedTarget(a, "SHOP-A", periodA[0], periodA[1]);
    seedTarget(b, "SHOP-B", periodB[0], periodB[1]);

    attainment.evaluate(a, null, null, null, null);
    attainment.evaluate(b, null, null, null, null);

    var snapshotsA = attainment.snapshots(a, null, null, null, 0, 50);
    var snapshotsB = attainment.snapshots(b, null, null, null, 0, 50);
    assertThat(snapshotsA.total()).isEqualTo(1);
    assertThat(snapshotsA.snapshots().get(0).ownerId()).isEqualTo("SHOP-A");
    assertThat(snapshotsB.total()).isEqualTo(1);
    assertThat(snapshotsB.snapshots().get(0).ownerId()).isEqualTo("SHOP-B");

    // B 直接查 A 的快照行也拿不到。
    var cross =
        tenants.run(
            b,
            () ->
                sql.fetch(
                        "select id from reporting_target_attainment where tenant_id = ?", a).size());
    assertThat(cross).isZero();
  }

  // ==========================================================================
  // 断言 2：伪造租户上下文无效
  // ==========================================================================

  @Test
  void 断言2_伪造租户头无效() throws Exception {
    var periodA = lastMonth(a);
    seedTarget(a, "SHOP-A", periodA[0], periodA[1]);

    // (1) 事务外没有「环境租户」：不存在一个可以被沿用的默认租户。
    assertThat(rawTenantSetting()).isNullOrEmpty();
    assertThat(visibleSnapshotsWithoutTenant()).as("未绑定租户时不应看到任何行").isZero();

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
    assertThat(visibleSnapshotsWithoutTenant()).isZero();

    // (3) HTTP 边界：带**租户 A 的合法会话** + **伪造的 X-Tenant-Id（租户 B）** ⇒
    //     快照仍然落在**会话租户 A** 名下，租户 B 看不见。
    http.perform(
            post("/reporting/target-attainment/evaluate").header("Authorization", BEARER_A).header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk());

    var landedInA =
        tenants.run(
            a,
            () -> sql.fetch("select id from reporting_target_attainment where tenant_id = ?", a).size());
    assertThat(landedInA).as("伪造头不得让快照落到别的租户名下").isPositive();

    var landedInB =
        tenants.run(
            b,
            () -> sql.fetch("select id from reporting_target_attainment where tenant_id = ?", b).size());
    assertThat(landedInB).as("被伪造的租户不得看见别人的快照").isZero();

    // A 只看得到自己那条；B 只看得到自己那条。
    assertThat(attainment.snapshots(a, null, null, null, 0, 50).snapshots())
        .extracting(ReportingTargetAttainmentModels.AttainmentSnapshot::ownerId)
        .containsExactly("SHOP-A");
    assertThat(attainment.snapshots(b, null, null, null, 0, 50).total()).isZero();
  }

  // ==========================================================================
  // 断言 3：连接池归还后不串租户
  // ==========================================================================

  @Test
  void 断言3_连接池归还后不串租户() {
    var periodA = lastMonth(a);
    var periodB = lastMonth(b);
    seedTarget(a, "SHOP-A", periodA[0], periodA[1]);
    seedTarget(b, "SHOP-B", periodB[0], periodB[1]);
    for (int i = 0; i < 5; i++) {
      assertThat(ownersOf(a)).as("第 %d 轮租户 A", i).containsExactly("SHOP-A");
      assertThat(ownersOf(b)).as("第 %d 轮租户 B", i).containsExactly("SHOP-B");
      assertThat(visibleSnapshotsWithoutTenant()).as("第 %d 轮事务外", i).isZero();
    }
    // 归还后再单独借一次，仍然干净。
    assertThat(ownersOf(a)).containsExactly("SHOP-A");
    assertThat(ownersOf(b)).containsExactly("SHOP-B");
  }

  // ==========================================================================
  // 工具
  // ==========================================================================

  private List<String> ownersOf(UUID tenant) {
    var board = attainment.board(tenant, null, null, null, null, 0, 50);
    return board.groups().stream()
        .flatMap(group -> group.rows().stream())
        .map(ReportingTargetAttainmentModels.AttainmentRow::ownerId)
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

  private int visibleSnapshotsWithoutTenant() {
    return sql.fetch("select id from reporting_target_attainment").size()
        + sql.fetch("select id from reporting_target").size()
        + sql.fetch("select id from reporting_target_bucket").size();
  }
}
