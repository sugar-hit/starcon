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
 * 13.4.1.1 多模式利润报表的**集成测试**（真实 PostgreSQL 16 + FORCE RLS，无内存替身）。
 *
 * <p>跑的是本模块的**业务口径**：
 *
 * <ol>
 *   <li><b>不同经营模式的利润含义完全不同</b> ⇒ 模式分行、**不合并**（没有「全部模式合计」）；
 *   <li><b>毛利 = 收入 − 各成本费用</b>，用 BigDecimal 显式定标（**没有浮点**）；
 *   <li><b>「算不出来」≠「等于 0」</b>：缺任何一项成本 ⇒ 毛利是 {@code null} + 原因，**不是 0**；
 *   <li><b>两种收入口径不相加</b>（订单总额口径 vs 订单行金额口径）；
 *   <li><b>多币种绝不相加</b>；
 *   <li><b>实际侧允许负数</b>（退款 / 冲回是真实发生的费用变化）；
 *   <li><b>数据更新时间</b>：两张表都空时是 {@code null}（不是 {@code '-infinity'} 假值）。
 * </ol>
 *
 * <p>租户隔离的三条断言在 {@code ProfitReportTenancyIntegrationTest}（同一批跑）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ProfitReportIntegrationTest {

  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  static final List<String> permissions =
      List.of(
          "reporting.profit-report.read",
          "reporting.projection.read",
          "reporting.projection.write");

  static final ObjectMapper JSON = JsonMapper.builder().addModule(new JavaTimeModule()).build();

  static final LocalDate DAY1 = LocalDate.of(2026, 10, 1);
  static final LocalDate DAY2 = LocalDate.of(2026, 10, 2);

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
    // ⚠️ 连接池固定为 1 条连接：只有复用同一条连接，才能真正证明归还后不会串租户。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  @Autowired TenantTransactions tenants;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;
  @Autowired ReportingProfitReportService report;

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
          "truncate reporting_profit_fact, reporting_profit_shop_profile,"
              + " reporting_metric_fact, reporting_drilldown_fact, reporting_projection_event,"
              + " reporting_projection_cursor, reporting_currency_rate_snapshot,"
              + " reporting_metric_definition,"
              + " reporting_expense_estimate_fact, reporting_expense_actual_fact cascade");
    }
  }

  // ==========================================================================
  // 夹具
  // ==========================================================================

  /** 一条利润事实（写库前的手工 SQL：与投影器写进去的形状逐字一致）。 */
  private void fact(
      UUID tenant,
      String mode,
      String component,
      String shop,
      String sku,
      String orderRef,
      LocalDate period,
      String currency,
      String amount) {
    tenants.run(
        tenant,
        () -> {
          sql.execute(
              """
              insert into reporting_profit_fact
                (id,tenant_id,operating_mode,component,shop_code,sku_code,spu_code,msku_code,order_ref,
                 period_start,period_end,currency,amount,quantity,source_service,source_reference,updated_at)
              values (?,?,?,?,?,?,'','',?,?,?,?,?,0,'test','test:'||?,now())
              """,
              UUID.randomUUID(),
              tenant,
              mode,
              component,
              shop,
              sku,
              orderRef,
              period,
              period,
              currency,
              new BigDecimal(amount),
              UUID.randomUUID().toString());
          return null;
        });
  }

  /** 一个店铺档案（模式标记）。 */
  private void shopProfile(UUID tenant, String shopCode, String mode) {
    tenants.run(
        tenant,
        () -> {
          sql.execute(
              """
              insert into reporting_profit_shop_profile
                (id,tenant_id,shop_code,shop_name,platform_code,operating_mode,
                 source_service,source_reference,updated_at)
              values (?,?,?,?,'AMAZON',?,'channel',?,now())
              """,
              UUID.randomUUID(),
              tenant,
              shopCode,
              shopCode,
              mode,
              "channel:shop:" + shopCode);
          return null;
        });
  }

  // ==========================================================================
  // 断言 1：模式分行，绝不合并
  // ==========================================================================

  @Test
  void 断言1_不同经营模式的利润分行不合并() {
    shopProfile(a, "SHOP-SELF", "SELF_FULFILLED");
    shopProfile(a, "SHOP-FULL", "FULL_CONSIGNMENT");

    // 自发货：收入 100，成本 40 ⇒ 毛利 60。
    fact(a, "SELF_FULFILLED", "REVENUE", "SHOP-SELF", "", "ORDER-1", DAY1, "USD", "100");
    fact(a, "SELF_FULFILLED", "COGS", "SHOP-SELF", "", "", DAY1, "USD", "40");
    // 全托管：收入 200，成本 150 ⇒ 毛利 50。
    fact(a, "FULL_CONSIGNMENT", "REVENUE", "SHOP-FULL", "", "ORDER-2", DAY1, "USD", "200");
    fact(a, "FULL_CONSIGNMENT", "COGS", "SHOP-FULL", "", "", DAY1, "USD", "150");

    var response = report.report(a, DAY1, DAY1, "DAY", "MODE", null, null, null, null);

    assertThat(response.rows()).as("两个模式 ⇒ 两行").hasSize(2);
    var self =
        response.rows().stream()
            .filter(r -> r.operatingMode().equals("SELF_FULFILLED"))
            .findFirst()
            .orElseThrow();
    var full =
        response.rows().stream()
            .filter(r -> r.operatingMode().equals("FULL_CONSIGNMENT"))
            .findFirst()
            .orElseThrow();
    assertThat(self.grossProfit()).isEqualByComparingTo("60");
    assertThat(full.grossProfit()).isEqualByComparingTo("50");
    // 🔴 **没有「全部模式合计」**：两个模式的收入加起来（300）**不允许**出现在任何一行。
    assertThat(response.rows())
        .as("模式之间绝不合并")
        .allSatisfy(r -> assertThat(r.grossProfit()).isNotEqualByComparingTo("110"));
  }

  // ==========================================================================
  // 断言 2：毛利 = 收入 − 各成本费用
  // ==========================================================================

  @Test
  void 断言2_毛利等于收入减各成本费用() {
    fact(a, "SELF_FULFILLED", "REVENUE", "SHOP-SELF", "", "ORDER-1", DAY1, "USD", "1000");
    fact(a, "SELF_FULFILLED", "COGS", "SHOP-SELF", "", "", DAY1, "USD", "400");
    fact(a, "SELF_FULFILLED", "PLATFORM_FEE", "SHOP-SELF", "", "", DAY1, "USD", "150");
    fact(a, "SELF_FULFILLED", "LOGISTICS_FEE", "SHOP-SELF", "", "", DAY1, "USD", "100");
    fact(a, "SELF_FULFILLED", "AD_FEE", "SHOP-SELF", "", "", DAY1, "USD", "50");

    var row = report.report(a, DAY1, DAY1, "DAY", "MODE", null, null, null, null).rows().get(0);

    assertThat(row.grossProfitComparable()).isTrue();
    assertThat(row.totalCost()).isEqualByComparingTo("700");
    assertThat(row.grossProfit()).isEqualByComparingTo("300");
    assertThat(row.grossMargin()).isEqualByComparingTo("30");
    // 每一项成本都在行里（服务端定标，前端只做展示格式化）。
    assertThat(row.components()).hasSize(7);
  }

  // ==========================================================================
  // 断言 3：缺项 ⇒ 毛利是 null（不是 0）
  // ==========================================================================

  @Test
  void 断言3_缺成本项时毛利算不出来而不是零() {
    // 本模式有收入与商品成本，但**没有平台费**；另一个模式有平台费
    // ⇒ 平台费在这个租户的这个维度上**是适用的**（不是「压根没有这类成本」），
    //   于是本模式缺它 ⇒ 毛利算不出来（**不是 0**）。
    fact(a, "SELF_FULFILLED", "REVENUE", "SHOP-SELF", "", "ORDER-1", DAY1, "USD", "1000");
    fact(a, "SELF_FULFILLED", "COGS", "SHOP-SELF", "", "", DAY1, "USD", "400");
    fact(a, "FULL_CONSIGNMENT", "PLATFORM_FEE", "SHOP-FULL", "", "", DAY1, "USD", "30");

    var response = report.report(a, DAY1, DAY1, "DAY", "MODE", null, null, null, null);
    var row =
        response.rows().stream()
            .filter(r -> r.operatingMode().equals("SELF_FULFILLED"))
            .findFirst()
            .orElseThrow();

    assertThat(row.grossProfitComparable()).as("缺成本项 ⇒ 不可比").isFalse();
    assertThat(row.grossProfit()).as("毛利算不出来 ⇒ null，不是 0").isNull();
    assertThat(row.grossMargin()).isNull();
    assertThat(row.totalCost()).isNull();
    assertThat(row.missingComponents()).contains("平台费");
    // 缺项那一格是 null（**不是 0.00**）。
    var platformFee =
        row.components().stream()
            .filter(c -> c.component().equals("PLATFORM_FEE"))
            .findFirst()
            .orElseThrow();
    assertThat(platformFee.amount()).isNull();
    assertThat(platformFee.available()).isFalse();
    assertThat(platformFee.reason()).contains("不是 0");
  }

  // ==========================================================================
  // 断言 4：两种收入口径不相加
  // ==========================================================================

  @Test
  void 断言4_订单总额口径与订单行金额口径不相加() {
    // 同一张订单：订单总额口径 120（无 SKU 归属）+ 订单行金额口径 100（带 SKU 归属）。
    fact(a, "SELF_FULFILLED", "REVENUE", "SHOP-SELF", "", "ORDER-1", DAY1, "USD", "120");
    fact(a, "SELF_FULFILLED", "REVENUE", "SHOP-SELF", "SKU-1", "ORDER-1", DAY1, "USD", "100");

    var modeRow = report.report(a, DAY1, DAY1, "DAY", "MODE", null, null, null, null).rows().get(0);
    var revenueOfMode = modeRow.components().stream()
        .filter(c -> c.component().equals("REVENUE"))
        .findFirst()
        .orElseThrow();
    assertThat(revenueOfMode.amount()).as("经营模式维度只取订单总额口径").isEqualByComparingTo("120");

    var skuRow = report.report(a, DAY1, DAY1, "DAY", "SKU", null, null, null, null).rows().get(0);
    var revenueOfSku = skuRow.components().stream()
        .filter(c -> c.component().equals("REVENUE"))
        .findFirst()
        .orElseThrow();
    assertThat(revenueOfSku.amount()).as("SKU 维度只取订单行金额口径").isEqualByComparingTo("100");
  }

  // ==========================================================================
  // 断言 5：多币种绝不相加
  // ==========================================================================

  @Test
  void 断言5_多币种绝不相加() {
    fact(a, "SELF_FULFILLED", "REVENUE", "SHOP-SELF", "", "ORDER-1", DAY1, "USD", "100");
    fact(a, "SELF_FULFILLED", "REVENUE", "SHOP-SELF", "", "ORDER-2", DAY1, "EUR", "100");

    var response = report.report(a, DAY1, DAY1, "DAY", "MODE", null, null, null, null);

    assertThat(response.rows()).as("两个币种 ⇒ 两行").hasSize(2);
    assertThat(response.rows()).extracting(r -> r.currency()).containsExactlyInAnyOrder("USD", "EUR");
    // 🔴 **没有任何一行是 200**（跨币种相加不存在）。
    assertThat(response.rows())
        .allSatisfy(r -> assertThat(r.components().stream()
            .filter(c -> c.component().equals("REVENUE"))
            .findFirst()
            .orElseThrow()
            .amount())
            .isEqualByComparingTo("100"));
  }

  // ==========================================================================
  // 断言 6：实际侧允许负数（退款 / 冲回）
  // ==========================================================================

  @Test
  void 断言6_实际侧允许负数不被截成零() {
    fact(a, "SELF_FULFILLED", "REVENUE", "SHOP-SELF", "", "ORDER-1", DAY1, "USD", "1000");
    fact(a, "SELF_FULFILLED", "COGS", "SHOP-SELF", "", "", DAY1, "USD", "400");
    fact(a, "SELF_FULFILLED", "PLATFORM_FEE", "SHOP-SELF", "", "", DAY1, "USD", "-50");

    var row = report.report(a, DAY1, DAY1, "DAY", "MODE", null, null, null, null).rows().get(0);

    assertThat(row.totalCost()).as("冲回是真实发生的费用变化，负数原样计入").isEqualByComparingTo("350");
    assertThat(row.grossProfit()).isEqualByComparingTo("650");
  }

  // ==========================================================================
  // 断言 7：数据更新时间（两张表都空 ⇒ null）
  // ==========================================================================

  @Test
  void 断言7_数据更新时间没投影时是空而不是哨兵值() {
    var empty = report.report(a, DAY1, DAY1, "DAY", "MODE", null, null, null, null);
    assertThat(empty.rows()).isEmpty();
    assertThat(empty.dataUpdatedAt()).as("尚未投影任何利润事实 ⇒ null").isNull();

    fact(a, "SELF_FULFILLED", "REVENUE", "SHOP-SELF", "", "ORDER-1", DAY1, "USD", "1000");

    var withFact = report.report(a, DAY1, DAY1, "DAY", "MODE", null, null, null, null);
    assertThat(withFact.dataUpdatedAt()).as("有事实 ⇒ 有更新时间").isNotNull();
    // 🔴 **断言写回的值**：库里的 updated_at 与响应里的 dataUpdatedAt 必须是同一个时刻。
    var stored =
        tenants.run(
            a,
            () ->
                sql.fetchOne("select max(updated_at) from reporting_profit_fact where tenant_id = ?", a));
    assertThat(stored.get(0, OffsetDateTime.class)).isEqualTo(withFact.dataUpdatedAt());
  }

  // ==========================================================================
  // 断言 8：下钻（真分页 + 真计数）
  // ==========================================================================

  @Test
  void 断言8_下钻是真分页真计数() {
    for (int i = 0; i < 7; i++) {
      fact(a, "SELF_FULFILLED", "COGS", "SHOP-SELF", "", "ORDER-" + i, DAY1, "USD", "10");
    }

    var page = report.drilldown(a, DAY1, DAY1, "SHOP", "SHOP-SELF", "SELF_FULFILLED", 0, 3);

    assertThat(page.total()).as("真计数").isEqualTo(7);
    assertThat(page.items()).hasSize(3);
    assertThat(page.page()).isZero();
    assertThat(page.size()).isEqualTo(3);

    var second = report.drilldown(a, DAY1, DAY1, "SHOP", "SHOP-SELF", "SELF_FULFILLED", 1, 3);
    assertThat(second.items()).hasSize(3);
    // 翻页不重复。
    assertThat(second.items().get(0).sourceReference())
        .isNotEqualTo(page.items().get(0).sourceReference());
  }

  // ==========================================================================
  // 断言 9：HTTP 端点
  // ==========================================================================

  @Test
  void 断言9_利润报表端点可用() throws Exception {
    fact(a, "SELF_FULFILLED", "REVENUE", "SHOP-SELF", "", "ORDER-1", DAY1, "USD", "1000");
    fact(a, "SELF_FULFILLED", "COGS", "SHOP-SELF", "", "", DAY1, "USD", "400");

    http.perform(
            get("/reporting/profit-report/report")
                .header("Authorization", "Bearer tenant-a")
                .param("from", DAY1.toString())
                .param("to", DAY1.toString())
                .param("dimension", "MODE"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rows[0].operatingMode").value("SELF_FULFILLED"))
        .andExpect(jsonPath("$.rows[0].grossProfit").value(600.0));

    http.perform(get("/reporting/profit-report/capabilities").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.upstreamConnected").value(true))
        .andExpect(jsonPath("$.notDelivered").isArray());

    http.perform(get("/reporting/profit-report/modes").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isArray());

    http.perform(
            get("/reporting/profit-report/drilldown")
                .header("Authorization", "Bearer tenant-a")
                .param("from", DAY1.toString())
                .param("to", DAY1.toString())
                .param("dimension", "MODE"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").isNumber());
  }

  @Test
  void 断言10_非法参数给一句能展示的话() throws Exception {
    http.perform(
            get("/reporting/profit-report/report")
                .header("Authorization", "Bearer tenant-a")
                .param("dimension", "NOT-A-DIMENSION"))
        .andExpect(status().isBadRequest());

    http.perform(
            get("/reporting/profit-report/report")
                .header("Authorization", "Bearer tenant-a")
                .param("from", DAY2.toString())
                .param("to", DAY1.toString()))
        .andExpect(status().isBadRequest());
  }
}
