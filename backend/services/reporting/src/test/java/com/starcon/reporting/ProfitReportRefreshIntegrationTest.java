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
 * 13.4.1.1 多模式利润报表的**投影链路测试**（真实 PostgreSQL 16 + 上游替身，无内存替身）。
 *
 * <p>这个测试盯的是**「从上游事实到利润报表」的整条管道**：
 *
 * <ol>
 *   <li><b>经营模式真的来自上游</b>（channel 2.3.2.1 的 {@code shop.operating_mode}），
 *       本模块**不硬编码模式当真相**；</li>
 *   <li><b>订单收入真的来自 trade</b>：订单总额口径与订单行金额口径**分别落库**；</li>
 *   <li><b>费用真的来自 12.3.1.2 结算明细</b>（符号翻正：上游是结算贡献，费用是减项）；</li>
 *   <li><b>成本真的来自 12.4.2.2 分摊结果</b>，而且**平台费 / 物流费 / 广告费不重复计入**
 *       （同一笔钱记两次就是重复计成本）；</li>
 *   <li><b>投影是幂等的</b>：刷新两次，金额不翻倍。</li>
 * </ol>
 *
 * <p>上游替身只回**上游真实形状**的 JSON（字段名逐字照抄各服务的 model），
 * 本模块的端口只声明真正用到的字段 ⇒ 字段名写错会静默变成 null，测试因此能抓住它。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ProfitReportRefreshIntegrationTest {

  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), user = UUID.randomUUID();
  static final UUID orderId = UUID.randomUUID();
  static final UUID platformOrderId = UUID.randomUUID();
  static final UUID settlementId = UUID.randomUUID();
  static final UUID transactionId = UUID.randomUUID();
  static final UUID poolId = UUID.randomUUID();
  static final UUID batchId = UUID.randomUUID();
  static final UUID resultId = UUID.randomUUID();
  static final UUID skuId = UUID.randomUUID();

  static HttpServer identity;
  static HttpServer upstream;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  static final List<String> permissions =
      List.of(
          "reporting.profit-report.read",
          "reporting.projection.read",
          "reporting.projection.write");

  static final ObjectMapper JSON = JsonMapper.builder().addModule(new JavaTimeModule()).build();

  static final LocalDate DAY1 = LocalDate.of(2026, 10, 1);

  static {
    pg.start();
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
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
      identity.start();
      upstream = HttpServer.create(new InetSocketAddress(0), 0);
      upstream.createContext("/", ProfitReportRefreshIntegrationTest::route);
      upstream.start();
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
    var url = "http://localhost:" + upstream.getAddress().getPort();
    r.add("CHANNEL_URL", () -> url);
    r.add("TRADE_URL", () -> url);
    r.add("FINANCE_URL", () -> url);
    r.add("CATALOG_URL", () -> url);
  }

  @Autowired TenantTransactions tenants;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;
  @Autowired ReportingProfitReportService report;

  @AfterAll
  static void stop() {
    identity.stop(0);
    upstream.stop(0);
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
  // 上游替身（字段名逐字照抄各服务的 model）
  // ==========================================================================

  /** 取查询串里的一个参数（上游端口按参数过滤，替身必须照做）。 */
  static String queryParam(String query, String name) {
    if (query == null) return null;
    for (var pair : query.split("&")) {
      var idx = pair.indexOf('=');
      if (idx <= 0) continue;
      if (pair.substring(0, idx).equals(name)) return pair.substring(idx + 1);
    }
    return null;
  }

  static void route(com.sun.net.httpserver.HttpExchange e) throws java.io.IOException {
    var path = e.getRequestURI().getPath();
    String body;
    if (path.equals("/trade/sales-orders/detail/" + orderId)) {
      // ---- trade 9 销售订单明细（订单行金额）----
      body =
          """
          {"header":{"orderNo":"ORDER-1","currency":"USD","grandTotalAmount":120.0000,
            "submittedAt":"2026-10-01T08:00:00Z"},
           "lines":[{"skuCode":"SKU-1","skuName":"商品","quantity":2,
            "unitPrice":50.0000,"lineAmount":100.0000}]}
          """;
    } else if (path.equals("/finance/transactions")) {
      // ---- finance 12.3.1.2 结算明细（**按 category 过滤**：三类费用各查一次）----
      var category = queryParam(e.getRequestURI().getQuery(), "category");
      body =
          "COMMISSION".equals(category)
              ? """
                {"items":[{"id":"%s","settlementId":"%s","settlementNo":"ST-1","shopCode":"SHOP-SELF",
                  "shopName":"自发货店铺","platformCode":"AMAZON","periodStart":"2026-10-01",
                  "periodEnd":"2026-10-01","lineNo":1,"transactionType":"COMMISSION",
                  "transactionTypeLabel":"佣金","category":"COMMISSION","categoryLabel":"佣金",
                  "amount":-12.0000,"rawAmount":-12.0000,"currency":"USD","quantity":null,
                  "occurredOn":"2026-10-01","externalReference":"EXT-1","orderReference":"ORDER-1",
                  "orderLineReference":"ORDER-1#1","idempotencyKey":"K-1","source":"PLATFORM",
                  "sourceLabel":"平台","description":"佣金"}],
                 "page":0,"size":200,"total":1}
                """
                  .formatted(transactionId, settlementId)
              : """
                {"items":[],"page":0,"size":200,"total":0}
                """;
    } else {
      body =
          switch (path) {
            // ---- channel 2.3.1.1 店铺列表（含 2.3.2.1 经营模式）----
            case "/channel/shops" ->
                """
                [{"id":"%s","code":"SHOP-SELF","name":"自发货店铺","platformCode":"AMAZON",
                  "platformName":"亚马逊","operatingMode":"SELF_FULFILLED"}]
                """
                    .formatted(UUID.randomUUID());
            // ---- channel 4.1.1.1 平台商品（MSKU 归属）----
            case "/channel/listings" ->
                """
                {"items":[{"id":"%s","shopCode":"SHOP-SELF","shopName":"自发货店铺",
                  "platformCode":"AMAZON","marketplaceCode":"US","externalItemId":"MSKU-001",
                  "title":"商品","status":"ACTIVE","mappingStatus":"MAPPED","internalSkuIds":["%s"]}],
                 "total":1,"page":0,"size":200}
                """
                    .formatted(UUID.randomUUID(), skuId);
            // ---- trade 9 销售订单列表 ----
            case "/trade/sales-orders/list" ->
                """
                {"rows":[{"id":"%s","orderNo":"ORDER-1","orderSource":"MANUAL","currency":"USD",
                  "grandTotalAmount":120.0000,"lineCount":1,
                  "submittedAt":"2026-10-01T08:00:00Z","createdAt":"2026-10-01T08:00:00Z"}],
                 "page":0,"size":200,"total":1}
                """
                    .formatted(orderId);
            // ---- trade 9.1.2.1 平台单（订单 → 店铺的归属）----
            case "/trade/platform-orders/orders" ->
                """
                {"rows":[{"id":"%s","salesOrderId":"%s","orderNo":"ORDER-1","platformCode":"AMAZON",
                  "shopCode":"SHOP-SELF","currency":"USD","platformPlacedAt":"2026-10-01T07:00:00Z",
                  "syncedAt":"2026-10-01T07:05:00Z"}],
                 "page":0,"size":200,"total":1}
                """
                    .formatted(platformOrderId, orderId);
            // ---- finance 12.4.2.1 归集规则（费用类别 ⇒ 成本项）----
            case "/finance/cost-collections/rules" ->
                """
                [{"id":"%s","ruleCode":"RULE-1","poolKey":"POOL-HEAD","feeSource":"HEAD_MILE_SHIPMENT",
                  "feeCategory":"HEAD_MILE_FEE","targetDimension":"MONTH","status":"ACTIVE"},
                 {"id":"%s","ruleCode":"RULE-2","poolKey":"POOL-PLATFORM","feeSource":"PLATFORM_SETTLEMENT",
                  "feeCategory":"PLATFORM_FEE","targetDimension":"MONTH","status":"ACTIVE"}]
                """
                    .formatted(UUID.randomUUID(), UUID.randomUUID());
            // ---- finance 12.4.2.2 费用池 ----
            case "/finance/cost-allocations/pools" ->
                """
                {"items":[{"poolId":"%s","poolKey":"POOL-HEAD","poolName":"头程费池","dimension":"MONTH",
                  "periodStart":"2026-10-01","periodEnd":"2026-10-31","currency":"USD",
                  "totalAmount":30.0000,"allocationAvailable":true}],
                 "page":0,"size":200,"total":1}
                """
                    .formatted(poolId);
            // ---- finance 12.4.2.2 分摊批次 ----
            case "/finance/cost-allocations/batches" ->
                """
                {"items":[{"id":"%s","poolId":"%s","poolKey":"POOL-HEAD","poolName":"头程费池",
                  "currency":"USD","allocatedTotal":30.0000,"status":"ALLOCATED",
                  "allocatedAt":"2026-10-02T00:00:00Z"}],
                 "page":0,"size":200,"total":1}
                """
                    .formatted(batchId, poolId);
            // ---- finance 12.4.2.2 分摊结果 ----
            case "/finance/cost-allocations/results" ->
                """
                {"items":[{"id":"%s","batchId":"%s","targetDimension":"SHOP","targetCode":"SHOP-SELF",
                  "targetName":"自发货店铺","basisAvailable":true,"allocatedAmount":30.0000,
                  "basisNote":""}],
                 "page":0,"size":200,"total":1}
                """
                    .formatted(resultId, batchId);
            // ---- catalog 3.1.2.1 SKU 列表（SKU → SPU）----
            case "/catalog/skus" ->
                """
                {"items":[{"id":"%s","code":"SKU-1","name":"商品","spuId":"%s","spuCode":"SPU-1",
                  "spuName":"商品族","variantSignature":"黑色","status":"ACTIVE"}],
                 "total":1,"page":0,"size":200}
                """
                    .formatted(skuId, UUID.randomUUID());
            default -> "{\"detail\":\"not found\"}";
          };
    }
    var bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    e.getResponseHeaders().add("Content-Type", "application/json");
    e.sendResponseHeaders(200, bytes.length);
    e.getResponseBody().write(bytes);
    e.close();
  }

  // ==========================================================================
  // 断言
  // ==========================================================================

  @Test
  void 断言1_刷新把上游事实投影成利润事实() throws Exception {
    http.perform(
            post("/reporting/profit-report/refresh")
                .header("Authorization", "Bearer tenant-a")
                .param("from", DAY1.toString())
                .param("to", DAY1.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].source").exists());

    // ---- 经营模式来自上游（channel 2.3.2.1）----
    var profiles =
        tenants.run(
            a,
            () ->
                sql.fetch(
                    "select shop_code, operating_mode from reporting_profit_shop_profile where tenant_id = ?",
                    a));
    assertThat(profiles).hasSize(1);
    assertThat(profiles.get(0).get(0, String.class)).isEqualTo("SHOP-SELF");
    assertThat(profiles.get(0).get(1, String.class)).isEqualTo("SELF_FULFILLED");

    // ---- 事实行：订单总额口径 + 订单行金额口径 + 结算费用 + 分摊成本 ----
    var facts =
        tenants.run(
            a,
            () ->
                sql.fetch(
                    "select operating_mode, component, shop_code, sku_code, spu_code, msku_code,"
                        + " order_ref, amount, currency from reporting_profit_fact where tenant_id = ?"
                        + " order by component, sku_code",
                    a));
    assertThat(facts).as("四路上游各自落一行").hasSize(4);

    // 订单总额口径（无 SKU 归属）
    var revenueOrder = factOf(facts, "REVENUE", "");
    assertThat(revenueOrder.get(2, String.class)).isEqualTo("SHOP-SELF");
    assertThat(revenueOrder.get(6, String.class)).isEqualTo("ORDER-1");
    assertThat(revenueOrder.get(7, BigDecimal.class)).isEqualByComparingTo("120");
    assertThat(revenueOrder.get(0, String.class)).as("经营模式来自上游").isEqualTo("SELF_FULFILLED");

    // 订单行金额口径（带 SKU / SPU / MSKU 归属）
    var revenueLine = factOf(facts, "REVENUE", "SKU-1");
    assertThat(revenueLine.get(3, String.class)).isEqualTo("SKU-1");
    assertThat(revenueLine.get(4, String.class)).isEqualTo("SPU-1");
    assertThat(revenueLine.get(5, String.class)).isEqualTo("MSKU-001");
    assertThat(revenueLine.get(7, BigDecimal.class)).isEqualByComparingTo("100");

    // 结算明细费用（符号翻正：上游是结算贡献 −12，费用是 +12）
    var fee = factOf(facts, "PLATFORM_FEE", "");
    assertThat(fee.get(2, String.class)).isEqualTo("SHOP-SELF");
    assertThat(fee.get(7, BigDecimal.class)).isEqualByComparingTo("12");

    // 12.4.2.2 分摊结果（头程费，落在店铺上 ⇒ 可归属经营模式）
    var headFreight = factOf(facts, "HEAD_FREIGHT", "");
    assertThat(headFreight.get(2, String.class)).isEqualTo("SHOP-SELF");
    assertThat(headFreight.get(0, String.class)).isEqualTo("SELF_FULFILLED");
    assertThat(headFreight.get(7, BigDecimal.class)).isEqualByComparingTo("30");
  }

  /** 从事实行里挑出「某个成本项 + 某个 SKU」那一行（不靠行序，行序会随排序变）。 */
  private static org.jooq.Record factOf(List<org.jooq.Record> facts, String component, String sku) {
    return facts.stream()
        .filter(r -> r.get(1, String.class).equals(component) && r.get(3, String.class).equals(sku))
        .findFirst()
        .orElseThrow(() -> new AssertionError("没有 " + component + " / SKU=" + sku + " 这一行"));
  }

  @Test
  void 断言2_平台费不重复计入() throws Exception {
    http.perform(
            post("/reporting/profit-report/refresh")
                .header("Authorization", "Bearer tenant-a")
                .param("from", DAY1.toString())
                .param("to", DAY1.toString()))
        .andExpect(status().isOk());

    // 归集规则里有 POOL-PLATFORM（平台费类别），但**没有对应的分摊批次**
    // ⇒ 平台费只有结算明细那一个来源（12.0000），绝不会是 12 + 12。
    var platformFee =
        tenants.run(
            a,
            () ->
                sql.fetchOne(
                    "select sum(amount) from reporting_profit_fact "
                        + "where tenant_id = ? and component = 'PLATFORM_FEE'",
                    a));
    assertThat(platformFee.get(0, BigDecimal.class)).isEqualByComparingTo("12");
  }

  @Test
  void 断言3_刷新是幂等的金额不翻倍() throws Exception {
    for (int i = 0; i < 2; i++) {
      http.perform(
              post("/reporting/profit-report/refresh")
                  .header("Authorization", "Bearer tenant-a")
                  .param("from", DAY1.toString())
                  .param("to", DAY1.toString()))
          .andExpect(status().isOk());
    }

    var count =
        tenants.run(
            a, () -> sql.fetch("select id from reporting_profit_fact where tenant_id = ?", a).size());
    assertThat(count).as("刷新两次，事实条数不变（幂等键覆盖）").isEqualTo(4);

    var revenue =
        tenants.run(
            a,
            () ->
                sql.fetchOne(
                    "select sum(amount) from reporting_profit_fact "
                        + "where tenant_id = ? and component = 'REVENUE' and sku_code = ''",
                    a));
    assertThat(revenue.get(0, BigDecimal.class)).as("订单总额口径不翻倍").isEqualByComparingTo("120");
  }

  @Test
  void 断言4_刷新后报表能算出毛利() throws Exception {
    http.perform(
            post("/reporting/profit-report/refresh")
                .header("Authorization", "Bearer tenant-a")
                .param("from", DAY1.toString())
                .param("to", DAY1.toString()))
        .andExpect(status().isOk());

    var rows = report.report(a, DAY1, DAY1, "DAY", "MODE", null, null, null, null).rows();

    assertThat(rows).hasSize(1);
    var row = rows.get(0);
    assertThat(row.operatingMode()).isEqualTo("SELF_FULFILLED");
    // 收入 120（订单总额口径）− 成本（平台费 12 + 头程 30）= 78。
    assertThat(row.totalCost()).isEqualByComparingTo("42");
    assertThat(row.grossProfit()).isEqualByComparingTo("78");
    assertThat(row.grossProfitComparable()).isTrue();
  }
}
