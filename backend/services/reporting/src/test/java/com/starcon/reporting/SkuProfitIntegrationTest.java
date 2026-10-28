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
 * 13.4.1.2 SKU 利润的**集成测试**（真实 PostgreSQL 16 + FORCE RLS，无内存替身）。
 *
 * <p>跑的是本模块的**业务口径**：
 *
 * <ol>
 *   <li><b>净利 = 单品收入 − 各项成本</b>，用 BigDecimal 显式定标（**没有浮点**）；
 *   <li><b>「算不出来」≠「等于 0」</b>：成本拿不到 ⇒ 净利是 {@code null} + 原因，**不是 0**；
 *   <li><b>「不可归属」与「缺这一项」分开说</b>（上游分摊到店铺 / 订单 vs 别的 SKU 有）；
 *   <li><b>退款是逐笔枚举的事实</b>：这个 SKU 不在其中 ⇒ 退款损失**真的是 0**；
 *   <li><b>多币种绝不相加</b>；
 *   <li><b>瀑布图逐步</b>，哪一步拿不到净利就是「—（不可比）」；
 *   <li><b>下钻是真分页 + 真计数</b>。
 * </ol>
 *
 * <p>租户隔离的三条断言在 {@code SkuProfitTenancyIntegrationTest}（同一批跑）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class SkuProfitIntegrationTest {

  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  static final List<String> permissions =
      List.of(
          "reporting.sku-profit.read",
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
  @Autowired ReportingSkuProfitService profit;

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

  /** 一条利润事实（手工 SQL：与投影器写进去的形状逐字一致）。 */
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

  /** 一条退款损失事实（手工 SQL：与投影器写进去的形状逐字一致）。 */
  private void refund(
      UUID tenant,
      String mode,
      String sku,
      String orderRef,
      String caseRef,
      LocalDate period,
      String currency,
      String amount) {
    tenants.run(
        tenant,
        () -> {
          sql.execute(
              """
              insert into reporting_sku_profit_refund_fact
                (id,tenant_id,sku_code,operating_mode,order_ref,case_ref,application_ref,
                 period_start,period_end,currency,amount,quantity,source_service,source_reference,updated_at)
              values (?,?,?,?,?,?,'APP-1',?,?,?,?,0,'trade','refund:'||?,now())
              """,
              UUID.randomUUID(),
              tenant,
              sku,
              mode,
              orderRef,
              caseRef,
              period,
              period,
              currency,
              new BigDecimal(amount),
              UUID.randomUUID().toString());
          return null;
        });
  }

  // ==========================================================================
  // 断言 1：净利 = 单品收入 − 各项成本
  // ==========================================================================

  @Test
  void 断言1_净利等于单品收入减各项成本() {
    fact(a, "SELF_FULFILLED", "REVENUE", "SHOP-1", "SKU-1", "ORDER-1", DAY1, "USD", "1000");
    fact(a, "SELF_FULFILLED", "COGS", "SHOP-1", "SKU-1", "", DAY1, "USD", "400");
    fact(a, "SELF_FULFILLED", "HEAD_FREIGHT", "SHOP-1", "SKU-1", "", DAY1, "USD", "100");
    fact(a, "SELF_FULFILLED", "PLATFORM_FEE", "SHOP-1", "SKU-1", "", DAY1, "USD", "150");
    fact(a, "SELF_FULFILLED", "LOGISTICS_FEE", "SHOP-1", "SKU-1", "", DAY1, "USD", "80");
    fact(a, "SELF_FULFILLED", "AD_FEE", "SHOP-1", "SKU-1", "", DAY1, "USD", "50");
    refund(a, "SELF_FULFILLED", "SKU-1", "ORDER-1", "CASE-1", DAY1, "USD", "20");

    var row = profit.report(a, DAY1, DAY1, "SKU-1", null).rows().get(0);

    assertThat(row.netProfitComparable()).as("成本项齐全 ⇒ 可比").isTrue();
    assertThat(row.totalCost()).isEqualByComparingTo("800");
    assertThat(row.netProfit()).isEqualByComparingTo("200");
    assertThat(row.netMargin()).isEqualByComparingTo("20");
    // 每一项都在行里（服务端定标，前端只做展示格式化）。
    assertThat(row.components()).hasSize(7);
  }

  // ==========================================================================
  // 断言 2：成本不可归属到 SKU ⇒ 净利算不出来（不是 0）
  // ==========================================================================

  @Test
  void 断言2_成本不可归属到SKU时净利算不出来而不是零() {
    // 收入在 SKU 上；平台费只分摊到了**订单**（sku_code = ''）⇒ SKU 维度拿不到。
    fact(a, "SELF_FULFILLED", "REVENUE", "SHOP-1", "SKU-1", "ORDER-1", DAY1, "USD", "1000");
    fact(a, "SELF_FULFILLED", "PLATFORM_FEE", "SHOP-1", "", "ORDER-1", DAY1, "USD", "150");

    var row = profit.report(a, DAY1, DAY1, "SKU-1", null).rows().get(0);

    assertThat(row.netProfitComparable()).as("平台费不可归属 ⇒ 不可比").isFalse();
    assertThat(row.netProfit()).as("净利算不出来 ⇒ null，不是 0").isNull();
    assertThat(row.netMargin()).isNull();
    assertThat(row.totalCost()).isNull();
    assertThat(row.missingComponents()).contains("平台费");

    var platformFee =
        row.components().stream()
            .filter(c -> c.component().equals("PLATFORM_FEE"))
            .findFirst()
            .orElseThrow();
    assertThat(platformFee.amount()).isNull();
    assertThat(platformFee.status()).isEqualTo("NOT_ATTRIBUTABLE");
    assertThat(platformFee.reason()).contains("不可归属");
    assertThat(platformFee.reason()).contains("不是 0");
  }

  // ==========================================================================
  // 断言 3：缺成本项（别的 SKU 有）⇒ 算不出来
  // ==========================================================================

  @Test
  void 断言3_缺成本项时净利算不出来而不是零() {
    // SKU-1 有收入与采购成本；SKU-2 有广告费 ⇒ 广告费在**这个租户的 SKU 维度上适用**
    //   （不是「压根没有这类成本」）⇒ SKU-1 缺它 ⇒ 净利算不出来（**不是 0**）。
    fact(a, "SELF_FULFILLED", "REVENUE", "SHOP-1", "SKU-1", "ORDER-1", DAY1, "USD", "1000");
    fact(a, "SELF_FULFILLED", "COGS", "SHOP-1", "SKU-1", "", DAY1, "USD", "400");
    fact(a, "SELF_FULFILLED", "AD_FEE", "SHOP-1", "SKU-2", "", DAY1, "USD", "30");

    var row = profit.report(a, DAY1, DAY1, "SKU-1", null).rows().get(0);

    assertThat(row.netProfitComparable()).as("缺成本项 ⇒ 不可比").isFalse();
    assertThat(row.netProfit()).as("净利算不出来 ⇒ null，不是 0").isNull();
    assertThat(row.missingComponents()).contains("广告费");
    var adFee =
        row.components().stream()
            .filter(c -> c.component().equals("AD_FEE"))
            .findFirst()
            .orElseThrow();
    assertThat(adFee.status()).isEqualTo("MISSING");
    assertThat(adFee.reason()).contains("不是 0");
  }

  // ==========================================================================
  // 断言 4：多币种绝不相加
  // ==========================================================================

  @Test
  void 断言4_多币种绝不相加() {
    fact(a, "SELF_FULFILLED", "REVENUE", "SHOP-1", "SKU-1", "ORDER-1", DAY1, "USD", "100");
    fact(a, "SELF_FULFILLED", "REVENUE", "SHOP-1", "SKU-1", "ORDER-2", DAY1, "EUR", "100");

    var response = profit.report(a, DAY1, DAY1, null, null);

    assertThat(response.rows()).as("两个币种 ⇒ 两行").hasSize(2);
    assertThat(response.rows()).extracting(r -> r.currency()).containsExactlyInAnyOrder("USD", "EUR");
    // 🔴 **没有任何一行是 200**（跨币种相加不存在）。
    assertThat(response.rows())
        .allSatisfy(
            r ->
                assertThat(
                        r.components().stream()
                            .filter(c -> c.component().equals("REVENUE"))
                            .findFirst()
                            .orElseThrow()
                            .amount())
                    .isEqualByComparingTo("100"));
  }

  // ==========================================================================
  // 断言 5：退款损失（逐笔枚举 ⇒ 缺就是 0，不是「算不出来」）
  // ==========================================================================

  @Test
  void 断言5_退款损失按SKU计入且没有退款时是零而不是空() {
    fact(a, "SELF_FULFILLED", "REVENUE", "SHOP-1", "SKU-1", "ORDER-1", DAY1, "USD", "1000");
    fact(a, "SELF_FULFILLED", "COGS", "SHOP-1", "SKU-1", "", DAY1, "USD", "400");
    refund(a, "SELF_FULFILLED", "SKU-2", "ORDER-9", "CASE-9", DAY1, "USD", "70");

    var response = profit.report(a, DAY1, DAY1, null, null);
    var sku1 =
        response.rows().stream()
            .filter(r -> r.skuCode().equals("SKU-1"))
            .findFirst()
            .orElseThrow();
    var sku2 =
        response.rows().stream()
            .filter(r -> r.skuCode().equals("SKU-2"))
            .findFirst()
            .orElseThrow();

    // SKU-2 有退款 ⇒ 计入成本。
    var sku2Refund =
        sku2.components().stream()
            .filter(c -> c.component().equals("REFUND_LOSS"))
            .findFirst()
            .orElseThrow();
    assertThat(sku2Refund.amount()).isEqualByComparingTo("70");
    assertThat(sku2Refund.available()).isTrue();

    // SKU-1 没有退款 ⇒ 退款损失**真的是 0**（上游逐笔枚举过 ⇒ 不在其中就是没有）。
    var sku1Refund =
        sku1.components().stream()
            .filter(c -> c.component().equals("REFUND_LOSS"))
            .findFirst()
            .orElseThrow();
    assertThat(sku1Refund.amount()).as("逐笔枚举的事实：不在其中 ⇒ 真的是 0").isEqualByComparingTo("0");
    assertThat(sku1Refund.available()).isTrue();
    assertThat(sku1Refund.reason()).contains("逐笔枚举");
  }

  // ==========================================================================
  // 断言 6：瀑布图
  // ==========================================================================

  @Test
  void 断言6_瀑布图按收入到成本到净利逐步且不可比时净利为空() {
    fact(a, "SELF_FULFILLED", "REVENUE", "SHOP-1", "SKU-1", "ORDER-1", DAY1, "USD", "1000");
    fact(a, "SELF_FULFILLED", "COGS", "SHOP-1", "SKU-1", "", DAY1, "USD", "400");
    // 平台费只分摊到**订单** ⇒ SKU 维度不可归属 ⇒ 净利不可比。
    fact(a, "SELF_FULFILLED", "PLATFORM_FEE", "SHOP-1", "", "ORDER-1", DAY1, "USD", "150");

    var waterfall = profit.waterfall(a, DAY1, DAY1, "SKU-1", "USD");

    assertThat(waterfall.steps()).hasSize(8);
    assertThat(waterfall.steps().get(0).component()).isEqualTo("REVENUE");
    assertThat(waterfall.steps().get(0).sign()).isEqualTo("+");
    assertThat(waterfall.steps().get(1).component()).isEqualTo("COGS");
    assertThat(waterfall.steps().get(1).sign()).isEqualTo("-");
    assertThat(waterfall.steps().get(7).component()).isEqualTo("NET_PROFIT");
    // 成本项拿不到 ⇒ 净利不可比 ⇒ 最后一步是「—（不可比）」，**不是 0**。
    assertThat(waterfall.netProfitComparable()).isFalse();
    assertThat(waterfall.netProfit()).isNull();
    assertThat(waterfall.steps().get(7).amount()).isNull();
    // 平台费那一步也是「—」，并带着「不可归属」的原因。
    var platformFee =
        waterfall.steps().stream()
            .filter(s -> s.component().equals("PLATFORM_FEE"))
            .findFirst()
            .orElseThrow();
    assertThat(platformFee.available()).isFalse();
    assertThat(platformFee.reason()).contains("不可归属");
  }

  // ==========================================================================
  // 断言 7：订单明细钻取（真分页 + 真计数）
  // ==========================================================================

  @Test
  void 断言7_订单明细钻取是真分页真计数() {
    for (int i = 0; i < 4; i++) {
      fact(a, "SELF_FULFILLED", "COGS", "SHOP-1", "SKU-1", "ORDER-" + i, DAY1, "USD", "10");
    }
    refund(a, "SELF_FULFILLED", "SKU-1", "ORDER-9", "CASE-9", DAY1, "USD", "30");

    var page = profit.orders(a, DAY1, DAY1, "SKU-1", "USD", 0, 3);

    assertThat(page.total()).as("真计数（含退款行）").isEqualTo(5);
    assertThat(page.items()).hasSize(3);
    assertThat(page.page()).isZero();
    assertThat(page.size()).isEqualTo(3);

    var second = profit.orders(a, DAY1, DAY1, "SKU-1", "USD", 1, 3);
    assertThat(second.items()).hasSize(2);
    // 翻页不重复。
    assertThat(second.items().get(0).sourceReference())
        .isNotEqualTo(page.items().get(0).sourceReference());
    // 退款行也在里面（它是一笔真实的损失事实）。
    assertThat(second.items().stream().map(r -> r.component())).contains("REFUND_LOSS");
  }

  // ==========================================================================
  // 断言 8：数据更新时间（没投影时是 null）
  // ==========================================================================

  @Test
  void 断言8_数据更新时间没投影时是空而不是哨兵值() {
    var empty = profit.report(a, DAY1, DAY1, null, null);
    assertThat(empty.rows()).isEmpty();
    assertThat(empty.dataUpdatedAt()).as("尚未投影任何事实 ⇒ null").isNull();

    fact(a, "SELF_FULFILLED", "REVENUE", "SHOP-1", "SKU-1", "ORDER-1", DAY1, "USD", "1000");

    var withFact = profit.report(a, DAY1, DAY1, null, null);
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
  // 断言 9：HTTP 端点
  // ==========================================================================

  @Test
  void 断言9_端点可用且非法参数给一句能展示的话() throws Exception {
    fact(a, "SELF_FULFILLED", "REVENUE", "SHOP-1", "SKU-1", "ORDER-1", DAY1, "USD", "1000");
    fact(a, "SELF_FULFILLED", "COGS", "SHOP-1", "SKU-1", "", DAY1, "USD", "400");

    http.perform(
            get("/reporting/sku-profit/report")
                .header("Authorization", "Bearer tenant-a")
                .param("from", DAY1.toString())
                .param("to", DAY1.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rows[0].skuCode").value("SKU-1"))
        .andExpect(jsonPath("$.rows[0].netProfit").value(600.0));

    http.perform(get("/reporting/sku-profit/capabilities").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.upstreamConnected").value(true))
        .andExpect(jsonPath("$.notDelivered").isArray());

    http.perform(
            get("/reporting/sku-profit/waterfall")
                .header("Authorization", "Bearer tenant-a")
                .param("skuCode", "SKU-1")
                .param("from", DAY1.toString())
                .param("to", DAY1.toString())
                .param("currency", "USD"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.steps").isArray());

    http.perform(
            get("/reporting/sku-profit/orders")
                .header("Authorization", "Bearer tenant-a")
                .param("skuCode", "SKU-1")
                .param("from", DAY1.toString())
                .param("to", DAY1.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").isNumber());

    // 非法参数给一句能直接展示的话，而不是抛枚举异常。
    http.perform(
            get("/reporting/sku-profit/report")
                .header("Authorization", "Bearer tenant-a")
                .param("currency", "美元"))
        .andExpect(status().isBadRequest());

    http.perform(
            get("/reporting/sku-profit/orders")
                .header("Authorization", "Bearer tenant-a")
                .param("skuCode", " "))
        .andExpect(status().isBadRequest());

    // 未认证 ⇒ 不给数据。
    http.perform(get("/reporting/sku-profit/report")).andExpect(status().isUnauthorized());
  }
}
