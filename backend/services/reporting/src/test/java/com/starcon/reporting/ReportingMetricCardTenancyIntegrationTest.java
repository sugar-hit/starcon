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
 * 1.1.2.1 核心指标卡的**多租户隔离**测试（AGENTS.md 明文要求的三条断言，缺一不可）。
 *
 * <ol>
 *   <li>两租户互不可见；</li>
 *   <li>伪造租户上下文无效；</li>
 *   <li>连接池归还后不串租户。</li>
 * </ol>
 *
 * <p><b>连接池固定为 1 条连接</b>：只有复用同一条连接，才能真正证明归还后不会串租户 ——
 * 池大于 1 时每次可能拿到不同连接，「没串」可能只是碰巧换了条干净连接。
 *
 * <p>⚠️ 回款那一格走的是 12 财务的**授权只读 API**，假上游按**调用方自己的 bearer**返回不同数字 ——
 * 于是「上游按已认证成员关系判租户」这一层也被钉住。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReportingMetricCardTenancyIntegrationTest {

  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  static HttpServer finance;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  static final String BEARER_A = "Bearer tenant-a";
  static final String BEARER_B = "Bearer tenant-b";

  static final List<String> permissions =
      List.of("reporting.overview.read", "reporting.projection.read", "reporting.projection.write");

  static final ObjectMapper JSON = JsonMapper.builder().addModule(new JavaTimeModule()).build();

  static final LocalDate DAY1 = LocalDate.of(2026, 10, 1);
  static final LocalDate DAY2 = LocalDate.of(2026, 10, 2);

  static {
    pg.start();
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      // 运行角色必须 NOBYPASSRLS，否则它会绕过策略，三条断言全部变成空断言。
      s.execute("create role starcon_app nologin");
      s.execute("create role reporting_app login password 'test-only' nosuperuser nobypassrls");
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
      actors.put(BEARER_A, actor(a));
      actors.put(BEARER_B, actor(b));
      identity.start();

      finance = HttpServer.create(new InetSocketAddress(0), 0);
      finance.createContext(
          "/finance/receipts",
          e -> {
            var bearer = e.getRequestHeaders().getFirst("Authorization");
            var amount =
                bearer != null && bearer.contains("tenant-b")
                    ? new BigDecimal("900.00")
                    : new BigDecimal("300.00");
            byte[] body =
                JSON.writeValueAsBytes(
                    Map.of(
                        "items",
                        List.of(),
                        "page",
                        0,
                        "size",
                        200,
                        "total",
                        0,
                        "summaryByCurrency",
                        List.of(
                            Map.of(
                                "currency",
                                "USD",
                                "amount",
                                amount,
                                "settledAmount",
                                BigDecimal.ZERO,
                                "unsettledAmount",
                                amount,
                                "count",
                                1,
                                "advanceCount",
                                0,
                                "advanceAmount",
                                BigDecimal.ZERO))));
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      finance.start();
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
    r.add("FINANCE_URL", () -> "http://localhost:" + finance.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  @Autowired TenantTransactions tenants;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;
  @Autowired ReportingProjectionService projection;
  @Autowired ReportingMetricCardService cards;

  @AfterAll
  static void stop() {
    identity.stop(0);
    finance.stop(0);
    pg.stop();
  }

  @BeforeEach
  void reset() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate reporting_metric_fact, reporting_drilldown_fact, reporting_projection_event,"
              + " reporting_projection_cursor, reporting_currency_rate_snapshot,"
              + " reporting_metric_definition cascade");
    }
  }

  // ==========================================================================
  // 夹具
  // ==========================================================================

  private static ReportingProjectionModels.ProjectionFact companyFact(
      String metricCode, LocalDate date, String currency, String value, String reference) {
    return new ReportingProjectionModels.ProjectionFact(
        "COMPANY", null, null, metricCode, date, currency, new BigDecimal(value), BigDecimal.ZERO, reference);
  }

  private static ReportingProjectionModels.ProjectionEvent event(
      String sourceService, String sourceEventId, LocalDate date, String value, String reference) {
    return new ReportingProjectionModels.ProjectionEvent(
        sourceService,
        sourceEventId,
        ReportingProjectionModels.ReportingEventTypes.SALES_FACT,
        null,
        0,
        date.atStartOfDay(ZoneOffset.UTC).toOffsetDateTime(),
        "UTC",
        List.of(
            companyFact(ReportingMetricCatalog.SALES_AMOUNT, date, "USD", value, reference),
            companyFact(ReportingMetricCatalog.ORDER_COUNT, date, "USD", "1", reference)),
        List.of());
  }

  private void seedA() {
    projection.applyBatch(
        a,
        List.of(event("trade", "trade:orders:SO-A-1", DAY1, "1500.00", "SO-A-1")));
  }

  private void seedB() {
    projection.applyBatch(
        b,
        List.of(event("trade", "trade:orders:SO-B-1", DAY1, "77.00", "SO-B-1")));
  }

  // ==========================================================================
  // 断言 1：两租户互不可见
  // ==========================================================================

  @Test
  void 断言1_两租户互不可见() {
    seedA();
    seedB();

    assertThat(sales(a, BEARER_A)).as("租户 A 只能看到自己的销售额").isEqualByComparingTo("1500");
    assertThat(sales(b, BEARER_B)).as("租户 B 只能看到自己的销售额").isEqualByComparingTo("77");

    // 回款那一格也一样：上游按**调用方自己的身份**返回本租户的数字。
    assertThat(received(a, BEARER_A)).as("租户 A 的回款").isEqualByComparingTo("300");
    assertThat(received(b, BEARER_B)).as("租户 B 的回款").isEqualByComparingTo("900");

    // 更强的一格：A 明确按指标直查 B 的行也拿不到。
    var cross =
        tenants.run(
            a,
            () ->
                sql.fetch(
                        "select source_reference from reporting_metric_fact "
                            + "where tenant_id = ? and metric_code = ?",
                        b,
                        ReportingMetricCatalog.SALES_AMOUNT)
                    .size());
    assertThat(cross).as("按主键直查也必须被 RLS 挡住").isZero();
  }

  // ==========================================================================
  // 断言 2：伪造租户上下文无效
  // ==========================================================================

  @Test
  void 断言2_伪造租户头无效() throws Exception {
    seedA();
    seedB();

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
    //     卡片仍然只给 A 的数字，B 的数字一滴都看不到。
    http.perform(
            get("/reporting/metric-cards")
                .param("window", "CUSTOM")
                .param("from", DAY1.toString())
                .param("to", DAY1.toString())
                .header("Authorization", BEARER_A)
                .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.cards.length()").value(7));

    assertThat(sales(a, BEARER_A)).as("伪造头不得让卡片显示别人的数字").isEqualByComparingTo("1500");
    assertThat(sales(b, BEARER_B)).as("被伪造的租户不得看见别人的事实").isEqualByComparingTo("77");
  }

  // ==========================================================================
  // 断言 3：连接池归还后不串租户
  // ==========================================================================

  @Test
  void 断言3_连接池归还后不串租户() {
    seedA();
    seedB();
    for (int i = 0; i < 5; i++) {
      assertThat(sales(a, BEARER_A)).as("第 %d 轮租户 A", i).isEqualByComparingTo("1500");
      assertThat(sales(b, BEARER_B)).as("第 %d 轮租户 B", i).isEqualByComparingTo("77");
      assertThat(received(a, BEARER_A)).as("第 %d 轮租户 A 的回款", i).isEqualByComparingTo("300");
      assertThat(received(b, BEARER_B)).as("第 %d 轮租户 B 的回款", i).isEqualByComparingTo("900");
      assertThat(visibleFactsWithoutTenant()).as("第 %d 轮事务外", i).isZero();
    }
    // 归还后再单独借一次，仍然干净。
    assertThat(sales(a, BEARER_A)).isEqualByComparingTo("1500");
    assertThat(sales(b, BEARER_B)).isEqualByComparingTo("77");
  }

  // ==========================================================================
  // 工具
  // ==========================================================================

  private BigDecimal sales(UUID tenantId, String bearer) {
    return cardValue(tenantId, bearer, "SALES_AMOUNT");
  }

  private BigDecimal received(UUID tenantId, String bearer) {
    return cardValue(tenantId, bearer, "CASH_RECEIVED");
  }

  private BigDecimal cardValue(UUID tenantId, String bearer, String metricCode) {
    var response = cards.cards(tenantId, bearer, "CUSTOM", DAY1, DAY1, null);
    return response.cards().stream()
        .filter(c -> c.metricCode().equals(metricCode))
        .findFirst()
        .orElseThrow(() -> new AssertionError("响应里没有这张卡：" + metricCode))
        .headline()
        .value();
  }

  /** 当前事务里生效的租户（空串 = 未绑定 ⇒ null）。 */
  private UUID effectiveTenant() {
    var row = sql.fetchOne("select nullif(current_setting('app.current_tenant', true),'')::uuid");
    return row == null ? null : row.get(0, UUID.class);
  }

  /** 原始设置值（事务外应当为空）。 */
  private String rawTenantSetting() {
    return sql.fetch("select current_setting('app.current_tenant', true)").stream()
        .findFirst()
        .map(r -> r.get(0, String.class))
        .orElse(null);
  }

  /** 不带 tenant_id 谓词时能看到几行（事务外必须是 0）。 */
  private int visibleFactsWithoutTenant() {
    return sql.fetch("select id from reporting_metric_fact").size();
  }
}
