package com.starcon.reporting;

import static org.assertj.core.api.Assertions.*;
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
 * 13.4.1.2 SKU 利润的**多租户隔离测试**（真实 PostgreSQL 16 + FORCE RLS，无内存替身）。
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
 * 但它有**写入口** —— {@code POST /reporting/sku-profit/refresh} 把上游退款事实投影成读模型。
 * 所以「伪造 {@code X-Tenant-Id} 头无效」可以真的证：带合法会话 + 伪造头 ⇒
 * 退款事实仍然落在**会话租户**名下，另一个租户看不见。
 *
 * <p>⚠️ <b>连接池固定为 1 条连接</b>：只有复用同一条连接，才能真正证明归还后不会串租户 ——
 * 池大于 1 时每次可能拿到不同连接，「没串」可能只是碰巧换了条干净连接。
 */
@SpringBootTest
@AutoConfigureMockMvc
class SkuProfitTenancyIntegrationTest {

  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  static HttpServer trade;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  static final List<String> permissions =
      List.of(
          "reporting.sku-profit.read",
          "reporting.profit-report.read",
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

      // 上游 trade 替身：一张**已成功**的退款申请单 + 它的售后单（带着 SKU）。
      // ⚠️ 它**不区分租户** —— 租户是由 reporting 侧从会话解析出来的，
      //   所以「伪造头会不会写到别的租户名下」恰恰由这个替身证明。
      trade = HttpServer.create(new InetSocketAddress(0), 0);
      var caseId = UUID.randomUUID();
      trade.createContext(
          "/trade/refund-applications",
          e -> {
            byte[] body =
                ("{\"rows\":[{\"head\":{\"id\":\""
                        + UUID.randomUUID()
                        + "\",\"applicationNo\":\"APP-1\",\"caseId\":\""
                        + caseId
                        + "\",\"caseNo\":\"CASE-1\",\"orderNo\":\"ORDER-A\","
                        + "\"applicationStatus\":\"SUCCEEDED\",\"refundAmount\":100.0000,"
                        + "\"currency\":\"USD\",\"refundReason\":\"RETURN_REFUND\","
                        + "\"outcomeRecordedAt\":\"2026-10-01T00:00:00Z\","
                        + "\"createdAt\":\"2026-10-01T00:00:00Z\","
                        + "\"updatedAt\":\"2026-10-01T00:00:00Z\"}}],"
                        + "\"page\":0,\"size\":200,\"total\":1}")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      trade.createContext(
          "/trade/aftersales/cases",
          e -> {
            byte[] body =
                ("{\"header\":{\"id\":\""
                        + caseId
                        + "\",\"caseNo\":\"CASE-1\",\"caseType\":\"RETURN_REFUND\","
                        + "\"status\":\"RESOLVED\",\"requestedAmount\":100.0000,\"currency\":\"USD\","
                        + "\"receivedAt\":\"2026-10-01T00:00:00Z\"},"
                        + "\"lines\":[{\"id\":\""
                        + UUID.randomUUID()
                        + "\",\"orderLineId\":\""
                        + UUID.randomUUID()
                        + "\",\"lineNo\":1,\"skuCode\":\"SKU-1\",\"quantity\":1.000000,"
                        + "\"requestedAmount\":100.0000,\"currency\":\"USD\"}]}")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      trade.createContext(
          "/trade/platform-orders/orders",
          e -> {
            byte[] body =
                "{\"rows\":[],\"page\":0,\"size\":200,\"total\":0}"
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      trade.start();
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
    r.add("TRADE_URL", () -> "http://localhost:" + trade.getAddress().getPort());
    // ⚠️ 连接池固定为 1 条连接：只有复用同一条连接，才能真正证明归还后不会串租户。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  @Autowired TenantTransactions tenants;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;
  @Autowired ReportingSkuProfitService profit;

  @AfterAll
  static void stop() {
    identity.stop(0);
    trade.stop(0);
    pg.stop();
  }

  @BeforeEach
  void reset() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate reporting_sku_profit_refund_fact, reporting_profit_fact,"
              + " reporting_profit_shop_profile, reporting_metric_fact, reporting_drilldown_fact,"
              + " reporting_projection_event, reporting_projection_cursor,"
              + " reporting_currency_rate_snapshot, reporting_metric_definition,"
              + " reporting_expense_estimate_fact, reporting_expense_actual_fact cascade");
    }
  }

  // ==========================================================================
  // 夹具
  // ==========================================================================

  /** 一条退款损失事实（手工 SQL：与投影器写进去的形状逐字一致）。 */
  private void refund(
      UUID tenant, String sku, String orderRef, String caseRef, String amount) {
    tenants.run(
        tenant,
        () -> {
          sql.execute(
              """
              insert into reporting_sku_profit_refund_fact
                (id,tenant_id,sku_code,operating_mode,order_ref,case_ref,application_ref,
                 period_start,period_end,currency,amount,quantity,source_service,source_reference,updated_at)
              values (?,?,?,'UNKNOWN',?,?,'APP-1',?,?,'USD',?,0,'trade',?,now())
              """,
              UUID.randomUUID(),
              tenant,
              sku,
              orderRef,
              caseRef,
              DAY1,
              DAY1,
              new BigDecimal(amount),
              "refund:" + caseRef);
          return null;
        });
  }

  /** 租户 A 自己的一条退款事实。 */
  private void seedTenantA() {
    refund(a, "SKU-A", "ORDER-A", "CASE-A", "100");
  }

  /**
   * 租户 B 自己的一条退款事实。
   *
   * <p>⚠️ **B 必须有自己的数据**：否则「B 看不见 A 的事实」只能靠「B 什么都没有」证明，
   * 那证明不了「B 只看得见自己的」（B 可能只是压根没数据）。
   */
  private void seedTenantB() {
    refund(b, "SKU-B", "ORDER-B", "CASE-B", "70");
  }

  // ==========================================================================
  // 断言 1：两租户互不可见
  // ==========================================================================

  @Test
  void 断言1_两租户互不可见() {
    seedTenantA();
    seedTenantB();

    var rowsA = profit.report(a, DAY1, DAY1, null, null).rows();
    var rowsB = profit.report(b, DAY1, DAY1, null, null).rows();

    assertThat(rowsA).as("租户 A 只能看到自己的退款事实").hasSize(1);
    assertThat(rowsA.get(0).skuCode()).isEqualTo("SKU-A");
    assertThat(rowsB).hasSize(1);
    assertThat(rowsB.get(0).skuCode()).isEqualTo("SKU-B");

    // 更强的一格：A 明确按来源行直查 B 的行也拿不到（RLS 挡住）。
    var cross =
        tenants.run(
            a,
            () ->
                sql.fetch(
                        "select source_reference from reporting_sku_profit_refund_fact "
                            + "where tenant_id = ? and source_reference = ?",
                        b,
                        "refund:CASE-B")
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
    assertThat(visibleRefundsWithoutTenant()).as("未绑定租户时不应看到任何行").isZero();

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
    assertThat(visibleRefundsWithoutTenant()).isZero();

    // (3) HTTP 边界：带**租户 A 的合法会话** + **伪造的 X-Tenant-Id（租户 B）** ⇒
    //     退款事实仍然落在会话租户 A 名下，租户 B 看不见。
    var beforeA = refundCount(a);
    var beforeB = refundCount(b);
    http.perform(
            post("/reporting/sku-profit/refresh")
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b.toString())
                .param("from", DAY1.toString())
                .param("to", DAY1.toString()))
        .andExpect(status().isOk());

    // 行确实落在**会话租户 A** 名下（不是 B）。
    var landedInA = refundCount(a);
    var landedInB = refundCount(b);
    assertThat(landedInA).as("伪造头不得让退款事实落到别的租户名下").isGreaterThan(beforeA);
    assertThat(landedInB).as("被伪造的租户不得多出别人的退款事实").isEqualTo(beforeB);

    // A 的报表里仍然只有自己的行。
    var rowsA = profit.report(a, DAY1, DAY1, null, null).rows();
    assertThat(rowsA).isNotEmpty();
    assertThat(rowsA).allSatisfy(r -> assertThat(r.skuCode()).isNotEqualTo("SKU-B"));
    // B 的报表里仍然只有 B 自己的行。
    var rowsB = profit.report(b, DAY1, DAY1, null, null).rows();
    assertThat(rowsB).allSatisfy(r -> assertThat(r.skuCode()).isEqualTo("SKU-B"));
  }

  // ==========================================================================
  // 断言 3：连接池归还后不串租户
  // ==========================================================================

  @Test
  void 断言3_连接池归还后不串租户() {
    seedTenantA();
    seedTenantB();
    for (int i = 0; i < 5; i++) {
      assertThat(refundOf(a)).as("第 %d 轮租户 A", i).isEqualByComparingTo("100");
      assertThat(refundOf(b)).as("第 %d 轮租户 B", i).isEqualByComparingTo("70");
      assertThat(visibleRefundsWithoutTenant()).as("第 %d 轮事务外", i).isZero();
    }
    // 归还后再单独借一次，仍然干净。
    assertThat(refundOf(a)).isEqualByComparingTo("100");
    assertThat(refundOf(b)).isEqualByComparingTo("70");
  }

  // ==========================================================================
  // 工具
  // ==========================================================================

  /** 某租户在窗口内看到的退款损失合计。 */
  private BigDecimal refundOf(UUID tenant) {
    var rows = profit.report(tenant, DAY1, DAY1, null, null).rows();
    if (rows.isEmpty()) return null;
    return rows.get(0).components().stream()
        .filter(c -> c.component().equals("REFUND_LOSS"))
        .findFirst()
        .orElseThrow()
        .amount();
  }

  /** 某租户的退款事实条数。 */
  private long refundCount(UUID tenant) {
    return tenants.run(
        tenant,
        () ->
            sql.fetch("select id from reporting_sku_profit_refund_fact where tenant_id = ?", tenant)
                .size());
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

  private int visibleRefundsWithoutTenant() {
    return sql.fetch("select id from reporting_sku_profit_refund_fact").size();
  }
}
