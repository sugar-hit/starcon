package com.starcon.reporting;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.starcon.tenancy.TenantTransactions;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.LocalDate;
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
 * 13.4.2.2 费用差异的**多租户隔离测试**（真实 PostgreSQL 16 + FORCE RLS，无内存替身）。
 *
 * <p>跑的是 AGENTS.md 明文要求的三条断言（缺一不可）：
 *
 * <ol>
 *   <li><b>两租户互不可见</b>；
 *   <li><b>伪造租户上下文无效</b>（并断言行确实落在**会话租户**名下）；
 *   <li><b>连接池归还后不串租户</b>。
 * </ol>
 *
 * <p>断言 2 在 HTTP 边界上是完整的：reporting 虽然是**投影服务**（不产生业务事实），
 * 但它有**写入口** —— {@code POST /reporting/projection/events} 把上游事件投影成读模型。
 * 所以「伪造 {@code X-Tenant-Id} 头无效」可以真的证：带合法会话 + 伪造头 ⇒
 * 费用事实仍然落在**会话租户**名下，另一个租户看不见。
 *
 * <p>⚠️ <b>连接池固定为 1 条连接</b>：只有复用同一条连接，才能真正证明归还后不会串租户 ——
 * 池大于 1 时每次可能拿到不同连接，「没串」可能只是碰巧换了条干净连接。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ExpenseVarianceTenancyIntegrationTest {

  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  static final List<String> permissions =
      List.of(
          "reporting.expense-variance.read",
          "reporting.projection.read",
          "reporting.projection.write");

  static final ObjectMapper JSON = JsonMapper.builder().addModule(new JavaTimeModule()).build();

  static final LocalDate DAY1 = LocalDate.of(2026, 10, 1);

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
      actors.put("Bearer tenant-a", actor(a));
      actors.put("Bearer tenant-b", actor(b));
      identity.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  static AccessService.Access actor(UUID tenant) {
    var grant = new AccessService.Grant(UUID.randomUUID(), "TEST", "ALL", permissions);
    return new AccessService.Access(
        user, tenant, "Test", UUID.randomUUID(), null, permissions, List.of(grant));
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
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  @Autowired TenantTransactions tenants;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;
  @Autowired ReportingProjectionService projection;
  @Autowired ReportingExpenseService expenses;

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
          "truncate reporting_metric_fact, reporting_drilldown_fact, reporting_projection_event,"
              + " reporting_projection_cursor, reporting_currency_rate_snapshot,"
              + " reporting_metric_definition,"
              + " reporting_expense_estimate_fact, reporting_expense_actual_fact cascade");
    }
  }

  // ==========================================================================
  // 夹具
  // ==========================================================================

  /** 一条费用事实（预计或实际）。 */
  private static ReportingProjectionModels.ProjectionExpenseFact expenseFact(
      String kind, String amount, String reference) {
    return new ReportingProjectionModels.ProjectionExpenseFact(
        kind,
        "PLATFORM_FEE",
        "COMMISSION",
        "AMAZON",
        "",
        "",
        "",
        "",
        "",
        "",
        DAY1,
        DAY1,
        "USD",
        new java.math.BigDecimal(amount),
        new java.math.BigDecimal("1000.0000"),
        null,
        reference);
  }

  /** 一个费用事件。 */
  private static ReportingProjectionModels.ProjectionEvent event(
      String key, String estimated, String actual) {
    return new ReportingProjectionModels.ProjectionEvent(
        "catalog",
        "catalog:expense:" + key,
        ReportingProjectionModels.ReportingEventTypes.EXPENSE_FACT,
        null,
        0,
        DAY1.atStartOfDay(ZoneOffset.UTC).toOffsetDateTime(),
        "UTC",
        List.of(),
        List.of(),
        List.of(expenseFact("ESTIMATE", estimated, key), expenseFact("ACTUAL", actual, key)));
  }

  private void seedTenantA() {
    projection.applyBatch(a, List.of(event("A-1", "100.0000", "85.0000")));
  }

  /**
   * 租户 B 自己的一条费用事实。
   *
   * <p>⚠️ **B 必须有自己的数据**：否则「B 看不见 A 的事实」只能靠「B 什么都没有」证明，
   * 那证明不了「B 只看得见自己的」（B 可能只是压根没数据）。给 B 一条自己的事实后，
   * 「B 的差异表里只有自己那一条、金额不变」才是真正的隔离断言。
   */
  private void seedTenantB() {
    projection.applyBatch(b, List.of(event("B-1", "50.0000", "50.0000")));
  }

  // ==========================================================================
  // 断言 1：两租户互不可见
  // ==========================================================================

  @Test
  void 断言1_两租户互不可见() {
    seedTenantA();
    projection.applyBatch(b, List.of(event("B-1", "50.0000", "50.0000")));

    var rowsA = expenses.variance(a, DAY1, DAY1, "DAY", null, null, null, null).rows();
    var rowsB = expenses.variance(b, DAY1, DAY1, "DAY", null, null, null, null).rows();
    assertThat(rowsA).as("租户 A 只能看到自己的费用差异").hasSize(1);
    assertThat(rowsA.get(0).estimatedAmount()).isEqualByComparingTo("100.0000");
    assertThat(rowsB).hasSize(1);
    assertThat(rowsB.get(0).estimatedAmount()).isEqualByComparingTo("50.0000");

    // 更强的一格：A 明确按来源行直查 B 的行也拿不到（RLS 挡住）。
    var cross =
        tenants.run(
            a,
            () ->
                sql.fetch(
                        "select source_reference from reporting_expense_actual_fact "
                            + "where tenant_id = ? and source_reference = ?",
                        b,
                        "B-1")
                    .size());
    assertThat(cross).as("按主键直查也必须被 RLS 挡住").isZero();
  }

  // ==========================================================================
  // 断言 2：伪造租户上下文无效
  // ==========================================================================

  @Test
  void 断言2_伪造租户头无效() throws Exception {
    seedTenantA();
    seedTenantB();

    // (1) 事务外没有「环境租户」：不存在一个可以被沿用的默认租户。
    assertThat(rawTenantSetting()).isNullOrEmpty();
    assertThat(visibleFactsWithoutTenant()).as("未绑定租户时不应看到任何行").isZero();

    // (2) 事务内绑定的值精确等于可信参数，提交后立刻失效。
    var bound =
        tenants.run(
            b,
            () -> {
              assertThat(effectiveTenant()).isEqualTo(b);
              return rawTenantSetting();
            });
    assertThat(bound).isEqualTo(b.toString());
    assertThat(effectiveTenant()).as("提交后租户上下文必须已清空").isNull();
    assertThat(visibleFactsWithoutTenant()).isZero();

    // (3) HTTP 边界：带**租户 A 的合法会话** + **伪造的 X-Tenant-Id（租户 B）** ⇒
    //     费用事实仍然落在会话租户 A 名下，租户 B 看不见。
    var body =
        JSON.writeValueAsBytes(
            List.of(event("FORGED", "999.0000", "999.0000")));
    http.perform(
            post("/reporting/projection/events")
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b.toString())
                .contentType("application/json")
                .content(body))
        .andExpect(status().isOk());

    // 行确实落在**会话租户 A** 名下（不是 B）。
    var landedInA =
        tenants.run(
            a,
            () ->
                sql.fetch(
                        "select source_reference from reporting_expense_actual_fact "
                            + "where tenant_id = ? and source_reference = ?",
                        a,
                        "FORGED")
                    .size());
    assertThat(landedInA).as("伪造头不得让费用事实落到别的租户名下").isEqualTo(1);

    var landedInB =
        tenants.run(
            b,
            () ->
                sql.fetch(
                        "select source_reference from reporting_expense_actual_fact "
                            + "where tenant_id = ? and source_reference = ?",
                        b,
                        "FORGED")
                    .size());
    assertThat(landedInB).as("被伪造的租户不得看见别人的费用事实").isZero();

    // A 的差异表里能看见这笔（预计 100 + 999）。
    var rowsA = expenses.variance(a, DAY1, DAY1, "DAY", null, null, null, null).rows();
    assertThat(rowsA).hasSize(1);
    assertThat(rowsA.get(0).estimatedAmount()).isEqualByComparingTo("1099.0000");
    // 🔴 B 只看得到**自己那一条**：伪造的 999 没有落进 B（否则这里是 1049 或多出一行）。
    var rowsB = expenses.variance(b, DAY1, DAY1, "DAY", null, null, null, null).rows();
    assertThat(rowsB).as("B 只看得到自己的费用事实").hasSize(1);
    assertThat(rowsB.get(0).estimatedAmount()).as("B 只看得到自己的").isEqualByComparingTo("50.0000");
  }

  // ==========================================================================
  // 断言 3：连接池归还后不串租户
  // ==========================================================================

  @Test
  void 断言3_连接池归还后不串租户() {
    seedTenantA();
    for (int i = 0; i < 5; i++) {
      assertThat(estimatedOf(a)).as("第 %d 轮租户 A", i).isEqualByComparingTo("100.0000");
      assertThat(estimatedOf(b)).as("第 %d 轮租户 B（无事实）", i).isNull();
      assertThat(visibleFactsWithoutTenant()).as("第 %d 轮事务外", i).isZero();
    }
    // 归还后再单独借一次，仍然干净。
    assertThat(estimatedOf(a)).isEqualByComparingTo("100.0000");
    assertThat(estimatedOf(b)).isNull();
  }

  // ==========================================================================
  // 工具
  // ==========================================================================

  /** 某租户在窗口内看到的预计费用合计（没有事实 ⇒ null，不是 0）。 */
  private java.math.BigDecimal estimatedOf(UUID tenant) {
    var rows = expenses.variance(tenant, DAY1, DAY1, "DAY", null, null, null, null).rows();
    if (rows.isEmpty()) return null;
    return rows.get(0).estimatedAmount();
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

  private int visibleFactsWithoutTenant() {
    return sql.fetch("select id from reporting_expense_actual_fact").size()
        + sql.fetch("select id from reporting_expense_estimate_fact").size();
  }
}
