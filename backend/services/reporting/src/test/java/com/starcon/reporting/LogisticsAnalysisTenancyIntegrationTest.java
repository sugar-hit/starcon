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
 * 13.5.3.1 物流分析的**多租户隔离测试**（真实 PostgreSQL 16 + FORCE RLS，无内存替身）。
 *
 * <p>跑的是 AGENTS.md 明文要求的三条断言（缺一不可）：
 *
 * <ol>
 *   <li><b>两租户互不可见</b>；</li>
 *   <li><b>伪造租户上下文无效</b>（并断言行确实落在**会话租户**名下）；</li>
 *   <li><b>连接池归还后不串租户</b>。</li>
 * </ol>
 *
 * <p>⚠️ <b>连接池固定为 1 条连接</b>：只有复用同一条连接，才能真正证明归还后不会串租户 ——
 * 池大于 1 时每次可能拿到不同连接，「没串」可能只是碰巧换了条干净连接。
 */
@SpringBootTest
@AutoConfigureMockMvc
class LogisticsAnalysisTenancyIntegrationTest {

  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  static final List<String> permissions =
      List.of(
          "reporting.logistics-analysis.read",
          "reporting.logistics-analysis.write",
          "reporting.projection.read",
          "reporting.projection.write");

  static final ObjectMapper JSON = JsonMapper.builder().addModule(new JavaTimeModule()).build();

  static final LocalDate DAY = LocalDate.of(2026, 10, 10);

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
  @Autowired ReportingLogisticsAnalysisService logisticsAnalysis;

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
          "truncate reporting_logistics_analysis_cost_fact,"
              + " reporting_logistics_analysis_channel_fact,"
              + " reporting_logistics_analysis_timeliness_fact,"
              + " reporting_logistics_analysis_waybill_fact,"
              + " reporting_logistics_analysis_allocation_fact,"
              + " reporting_logistics_analysis_allocation_line_fact,"
              + " reporting_logistics_analysis_exception_fact,"
              + " reporting_logistics_analysis_sku_category_fact,"
              + " reporting_logistics_analysis_rule cascade");
    }
  }

  // ==========================================================================
  // 夹具
  // ==========================================================================

  /** 写一条运单（落在**指定租户**名下）。 */
  private void waybillFact(UUID tenant, String waybillNo, String orderNo, String country, String amount) {
    insertAs(tenant, tenant, waybillNo, orderNo, country, amount);
  }

  /**
   * 写一条运单，但**行上的 tenant_id 可以与绑定的租户不同**。
   *
   * <p>⚠️ 用来证 RLS 的 {@code WITH CHECK}：绑定 A 时往 B 名下行写 ⇒ 必须被数据库挡住。
   */
  private void insertAs(
      UUID boundTenant,
      UUID rowTenant,
      String waybillNo,
      String orderNo,
      String country,
      String amount) {
    tenants.run(
        boundTenant,
        () -> {
          sql.execute(
              """
              insert into reporting_logistics_analysis_waybill_fact
                (id,tenant_id,occurred_on,occurred_at,
                 waybill_id,waybill_no,sales_order_no,
                 carrier_code,carrier_channel,destination_country,
                 gross_weight_kg,freight_amount,freight_currency,freight_gap_code,
                 source_service,source_reference,updated_at)
              values (?,?,?,?::timestamptz,
                      ?,?,?,
                      'C1','PLATFORM_INTEGRATED',?,
                      1,?,?,'REGISTERED',
                      'test',?,now())
              """,
              UUID.randomUUID(),
              rowTenant,
              DAY,
              DAY.atStartOfDay(java.time.ZoneOffset.UTC).toInstant(),
              UUID.nameUUIDFromBytes(("waybill:" + waybillNo).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
              waybillNo,
              orderNo,
              country,
              new BigDecimal(amount),
              "USD",
              "test:" + rowTenant);
          return null;
        });
  }

  /** 某租户看到的运单（走完整的读模型）。 */
  private List<ReportingLogisticsAnalysisModels.LogisticsDrilldownRow> rows(UUID tenant) {
    return logisticsAnalysis.drilldown(tenant, DAY.minusDays(30), DAY.plusDays(30), null, null, null, 0, 50)
        .rows();
  }

  // ==========================================================================
  // 断言 1：两租户互不可见
  // ==========================================================================

  @Test
  void 断言1_两租户互不可见() {
    waybillFact(a, "WB-A1", "SO-A", "US", "100");
    waybillFact(b, "WB-B1", "SO-B", "DE", "50");

    var rowsA = rows(a);
    var rowsB = rows(b);
    assertThat(rowsA).as("租户 A 只能看到自己的运单").hasSize(1);
    assertThat(rowsA.get(0).waybillNo()).isEqualTo("WB-A1");
    assertThat(rowsA.get(0).freightAmount()).isEqualByComparingTo("100");
    assertThat(rowsB).hasSize(1);
    assertThat(rowsB.get(0).waybillNo()).isEqualTo("WB-B1");
    assertThat(rowsB.get(0).freightAmount()).as("B 只看得到自己的").isEqualByComparingTo("50");

    // 更强的一格：A 明确按运单号直查 B 的行也拿不到（RLS 挡住）。
    var cross =
        tenants.run(
            a,
            () ->
                sql.fetch(
                        "select waybill_no from reporting_logistics_analysis_waybill_fact "
                            + "where tenant_id = ? and waybill_no = ?",
                        b,
                        "WB-B1")
                    .size());
    assertThat(cross).as("按业务键直查也必须被 RLS 挡住").isZero();

    // 汇总口径同样隔离：A 的单均运费只算 A 的运单。
    var perOrderA = logisticsAnalysis.freightPerOrder(a, DAY.minusDays(30), DAY.plusDays(30));
    assertThat(perOrderA.orderCount()).isEqualTo(1);
    assertThat(perOrderA.rows().get(0).freightAmount()).isEqualByComparingTo("100");
  }

  // ==========================================================================
  // 断言 2：伪造租户上下文无效
  // ==========================================================================

  @Test
  void 断言2_伪造租户头无效() throws Exception {
    waybillFact(a, "WB-A1", "SO-A", "US", "100");
    waybillFact(b, "WB-B1", "SO-B", "DE", "50");

    // (1) 事务外没有「环境租户」：不存在一个可以被沿用的默认租户。
    assertThat(rawTenantSetting()).isNullOrEmpty();
    assertThat(visibleWaybillsWithoutTenant()).as("未绑定租户时不应看到任何行").isZero();

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
    assertThat(visibleWaybillsWithoutTenant()).isZero();

    // (3) HTTP 边界：带**租户 A 的合法会话** + **伪造的 X-Tenant-Id（租户 B）**
    //     ⇒ 返回的仍然是**会话租户 A** 的数据，B 的 50 一格都看不到。
    http.perform(
            get("/reporting/logistics-analysis/drilldown")
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rows.length()").value(1))
        .andExpect(jsonPath("$.rows[0].waybillNo").value("WB-A1"))
        .andExpect(jsonPath("$.rows[0].freightAmount").value(org.hamcrest.Matchers.closeTo(100.0, 0.001)));

    // 反向也成立：会话是 B、伪造头是 A ⇒ 看到的是 B 的 50，不是 A 的 100。
    http.perform(
            get("/reporting/logistics-analysis/drilldown")
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rows.length()").value(1))
        .andExpect(jsonPath("$.rows[0].waybillNo").value("WB-B1"))
        .andExpect(jsonPath("$.rows[0].freightAmount").value(org.hamcrest.Matchers.closeTo(50.0, 0.001)));

    // (4) 写侧也必须被 RLS 的 WITH CHECK 挡住：绑定 A 时插入 tenant_id = B 的行必须失败。
    assertThatThrownBy(() -> insertAs(a, b, "WB-FAKE", "SO-FAKE", "US", "1"))
        .as("绑定 A 时不许往 B 名下写行")
        .isInstanceOf(RuntimeException.class);
    var landedInB =
        tenants.run(
            b,
            () ->
                sql.fetch(
                        "select waybill_no from reporting_logistics_analysis_waybill_fact "
                            + "where tenant_id = ? and waybill_no = ?",
                        b,
                        "WB-FAKE")
                    .size());
    assertThat(landedInB).as("被伪造的租户不得看见别人的运单").isZero();
  }

  // ==========================================================================
  // 断言 3：连接池归还后不串租户
  // ==========================================================================

  @Test
  void 断言3_连接池归还后不串租户() {
    waybillFact(a, "WB-A1", "SO-A", "US", "100");
    for (int i = 0; i < 5; i++) {
      assertThat(amountOf(a)).as("第 %d 轮租户 A", i).isEqualByComparingTo("100");
      assertThat(amountOf(b)).as("第 %d 轮租户 B（无事实）", i).isNull();
      assertThat(visibleWaybillsWithoutTenant()).as("第 %d 轮事务外", i).isZero();
    }
    // 归还后再单独借一次，仍然干净。
    assertThat(amountOf(a)).isEqualByComparingTo("100");
    assertThat(amountOf(b)).isNull();
  }

  // ==========================================================================
  // 工具
  // ==========================================================================

  /** 某租户看到的运费合计（没有事实 ⇒ null，不是 0）。 */
  private BigDecimal amountOf(UUID tenant) {
    var rows = rows(tenant);
    if (rows.isEmpty()) return null;
    return rows.get(0).freightAmount();
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

  private int visibleWaybillsWithoutTenant() {
    return sql.fetch("select id from reporting_logistics_analysis_waybill_fact").size();
  }
}
