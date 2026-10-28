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
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
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
 * 13.3.1.2 生命周期与分层的**集成测试**（真实 PostgreSQL 16 + FORCE RLS，无内存替身）。
 *
 * <p>本文件验的是**业务口径**（多租户三条断言在 {@code LifecycleSegmentationTenancyIntegrationTest}）：
 *
 * <ul>
 *   <li>六档分层（新品 / 爆款 / 旺款 / 平款 / 滞销款 / 衰退款）真的按规则算出来；</li>
 *   <li>建议动作挂在档位上（备货 / 清仓 / 下架 / 推广）；</li>
 *   <li>ABC 按销量、**按金额分币种**两套口径；</li>
 *   <li>毛利按币种分开，**不跨币种相加**，**算不出来时是 null 不是 0**；</li>
 *   <li>窗口外的事实不参与计算；重复刷新幂等；规则可配置且可审计。</li>
 * </ul>
 *
 * <p>⚠️ **上游是真的 HTTP**：本测试起一个假的上游服务（catalog + channel + trade +
 * warehouse + finance），驱动 13.2.2.2 与 13.3.1.1 的**真实**拉取与投影路径 ——
 * 端口写错了路径 / 字段名，在这一层就会暴露。
 */
@SpringBootTest
@AutoConfigureMockMvc
class LifecycleSegmentationIntegrationTest {

  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID tenant = UUID.randomUUID(), user = UUID.randomUUID();
  static final String BEARER = "Bearer tenant-a";

  static HttpServer identity;
  static HttpServer upstream;

  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  static final List<String> permissions =
      List.of(
          "reporting.lifecycle-segmentation.read",
          // ⚠️ 写权限必须在**同一个 grant** 里同时命中对应的 .read ——
          //   服务端的判据是「能写不能读」的组合不放行，所以这里必须带上 reporting.projection.read。
          "reporting.projection.read",
          "reporting.projection.write");

  static final ObjectMapper JSON = JsonMapper.builder().addModule(new JavaTimeModule()).build();

  /** 🔴 **分层窗口**（10 天）：首销 08-30 的商品距判定基准日 35 天 ⇒ 不是新品。 */
  static final LocalDate FROM = LocalDate.of(2026, 9, 25);
  static final LocalDate TO = LocalDate.of(2026, 10, 4);

  /** 🔴 **对比窗口**（规则里 window_days = 30 ⇒ 窗口前紧挨着的 30 天）。 */
  static final LocalDate BASE_FROM = LocalDate.of(2026, 8, 26);
  static final LocalDate BASE_TO = LocalDate.of(2026, 9, 24);

  static final ZoneId ZONE = ZoneId.of("UTC");

  // ==========================================================================
  // 夹具数据
  // ==========================================================================

  static final UUID SPU_HOT = UUID.randomUUID();
  static final UUID SPU_STRONG = UUID.randomUUID();
  static final UUID SPU_STEADY = UUID.randomUUID();
  static final UUID SPU_SLOW = UUID.randomUUID();
  static final UUID SPU_NEW = UUID.randomUUID();
  static final UUID SPU_DECLINE = UUID.randomUUID();

  static final UUID SKU_HOT = UUID.randomUUID();
  static final UUID SKU_STRONG = UUID.randomUUID();
  static final UUID SKU_STEADY = UUID.randomUUID();
  static final UUID SKU_SLOW = UUID.randomUUID();
  static final UUID SKU_NEW = UUID.randomUUID();
  static final UUID SKU_DECLINE = UUID.randomUUID();

  static final UUID LISTING_1 = UUID.randomUUID();
  static final UUID COST_RUN = UUID.randomUUID();

  /** 商品主数据（catalog）：每个 SKU 一个 SPU。 */
  static final List<Map<String, Object>> SKUS =
      List.of(
          sku(SKU_HOT, "SKU-HOT", "爆款", SPU_HOT, "SPU-HOT"),
          sku(SKU_STRONG, "SKU-STRONG", "旺款", SPU_STRONG, "SPU-STRONG"),
          sku(SKU_STEADY, "SKU-STEADY", "平款", SPU_STEADY, "SPU-STEADY"),
          sku(SKU_SLOW, "SKU-SLOW", "滞销款", SPU_SLOW, "SPU-SLOW"),
          sku(SKU_NEW, "SKU-NEW", "新品", SPU_NEW, "SPU-NEW"),
          sku(SKU_DECLINE, "SKU-DECLINE", "衰退款", SPU_DECLINE, "SPU-DECLINE"));

  /** 平台商品（channel）：只给一个 Listing（SKU 层的事实不需要 Listing）。 */
  static final List<Map<String, Object>> LISTINGS =
      List.of(
          listing(LISTING_1, "shop-a", "AMAZON", "AMAZON-A", List.of(SKU_HOT)),
          listing(UUID.randomUUID(), "shop-a", "AMAZON", "AMAZON-B", List.of(SKU_STRONG)),
          listing(UUID.randomUUID(), "shop-a", "AMAZON", "AMAZON-C", List.of(SKU_STEADY)),
          listing(UUID.randomUUID(), "shop-a", "AMAZON", "AMAZON-D", List.of(SKU_SLOW)),
          listing(UUID.randomUUID(), "shop-a", "AMAZON", "AMAZON-E", List.of(SKU_NEW)),
          listing(UUID.randomUUID(), "shop-a", "AMAZON", "AMAZON-F", List.of(SKU_DECLINE)));

  /**
   * 销售订单（trade）—— 13.2.2.2 投影它们。
   *
   * <p>🔴 窗口内总销量 = 300 + 20 + 6 + 1 + 3 + 10 = **340** 件 ⇒ 份额分母是 340。
   * 销量份额：HOT 88.24% / STRONG 5.88% / STEADY 1.76% / SLOW 0.29% / NEW 0.88% / DECLINE 2.94%。
   */
  static final List<Map<String, Object>> ORDERS =
      List.of(
          // ---- 对比窗口（历史）：首销都在 08-30（距基准日 35 天 ⇒ 不是新品）----
          order("SO-H1", LocalDate.of(2026, 8, 30), "USD", "SKU-HOT", "100", "1000.00"),
          order("SO-S1", LocalDate.of(2026, 8, 30), "EUR", "SKU-STRONG", "20", "200.00"),
          order("SO-D1", LocalDate.of(2026, 8, 30), "USD", "SKU-STEADY", "4", "40.00"),
          order("SO-L1", LocalDate.of(2026, 8, 30), "USD", "SKU-SLOW", "1", "10.00"),
          order("SO-C1", LocalDate.of(2026, 8, 30), "USD", "SKU-DECLINE", "120", "1200.00"),
          // 新品的首销在 09-20（距基准日 14 天 ≤ 30 ⇒ 新品）
          order("SO-N1", LocalDate.of(2026, 9, 20), "USD", "SKU-NEW", "3", "30.00"),
          // ---- 分层窗口（当期）----
          order("SO-H2", LocalDate.of(2026, 10, 1), "USD", "SKU-HOT", "300", "3000.00"),
          order("SO-S2", LocalDate.of(2026, 10, 1), "EUR", "SKU-STRONG", "20", "200.00"),
          order("SO-D2", LocalDate.of(2026, 10, 1), "USD", "SKU-STEADY", "6", "60.00"),
          order("SO-L2", LocalDate.of(2026, 10, 2), "USD", "SKU-SLOW", "1", "10.00"),
          order("SO-N2", LocalDate.of(2026, 10, 2), "USD", "SKU-NEW", "3", "30.00"),
          order("SO-C2", LocalDate.of(2026, 10, 3), "USD", "SKU-DECLINE", "10", "100.00"));

  /** 成本批次（finance 12.4.1.2）。 */
  static final List<Map<String, Object>> COST_RUNS =
      List.of(
          Map.of(
              "id", COST_RUN.toString(),
              "scopeType", "LEGAL_ENTITY",
              "scopeRefId", UUID.randomUUID().toString(),
              "valuationMethod", "MOVING_AVERAGE",
              "currency", "USD",
              "periodFrom", OffsetDateTime.of(2026, 8, 1, 0, 0, 0, 0, ZoneOffset.UTC).toString(),
              "periodTo", OffsetDateTime.of(2026, 10, 31, 0, 0, 0, 0, ZoneOffset.UTC).toString(),
              "status", "COMPLETED",
              "entryCount", 5));

  /**
   * 成本分录（finance 12.4.1.2）。
   *
   * <p>🔴 **SKU-SLOW 故意没有出库成本** ⇒ 它的毛利额**算不出来**（null，不是 0）。
   */
  static final List<Map<String, Object>> COST_ENTRIES =
      List.of(
          costEntry(COST_RUN, UUID.randomUUID(), SKU_HOT, "SKU-HOT", LocalDate.of(2026, 10, 1), "1500.00", "USD"),
          costEntry(COST_RUN, UUID.randomUUID(), SKU_STRONG, "SKU-STRONG", LocalDate.of(2026, 10, 1), "120.00", "EUR"),
          costEntry(COST_RUN, UUID.randomUUID(), SKU_STEADY, "SKU-STEADY", LocalDate.of(2026, 10, 1), "40.00", "USD"),
          costEntry(COST_RUN, UUID.randomUUID(), SKU_NEW, "SKU-NEW", LocalDate.of(2026, 10, 2), "20.00", "USD"),
          costEntry(COST_RUN, UUID.randomUUID(), SKU_DECLINE, "SKU-DECLINE", LocalDate.of(2026, 10, 3), "70.00", "USD"));

  /** 库存余额（warehouse 7 库存）：只有滞销款有在手库存。 */
  static final List<Map<String, Object>> BALANCES =
      List.of(
          Map.of(
              "balanceId", UUID.randomUUID().toString(),
              "skuId", SKU_SLOW.toString(),
              "skuCode", "SKU-SLOW",
              "skuName", "滞销款",
              "onHand", "50",
              "reserved", "0",
              "available", "50"));

  /** 库龄（warehouse 7 库存库龄）：只有滞销款有库龄。 */
  static final List<Map<String, Object>> AGING =
      List.of(
          map(
              "bucketId", UUID.randomUUID().toString(),
              "skuId", SKU_SLOW.toString(),
              "skuCode", "SKU-SLOW",
              "skuName", "滞销款",
              "agingDays", 90,
              "agingBucket", "61-90",
              "agingBucketLabel", "61-90 天",
              "onHand", "50",
              "reserved", "0",
              "available", "50",
              "slowMovingStatus", "SLOW_MOVING"));

  static {
    pg.start();
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      // 运行角色必须 NOBYPASSRLS，否则它会绕过策略，多租户断言全部变成空断言。
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
      actors.put(BEARER, actor(tenant));
      identity.start();

      upstream = HttpServer.create(new InetSocketAddress(0), 0);
      upstream.createContext("/catalog/skus", e -> respond(e, page(SKUS, "items")));
      upstream.createContext("/channel/listings", e -> respond(e, page(LISTINGS, "items")));
      upstream.createContext("/trade/sales-orders/list", e -> respond(e, ordersBody(e)));
      upstream.createContext("/trade/sales-orders/detail", e -> respond(e, detailBody(e)));
      upstream.createContext("/warehouse/inventory/overview", e -> respond(e, page(BALANCES, "rows")));
      upstream.createContext("/warehouse/inventory/aging/slow-moving", e -> respond(e, page(AGING, "rows")));
      upstream.createContext("/finance/cost-calculation/runs", e -> respond(e, page(COST_RUNS, "items")));
      upstream.createContext(
          "/finance/cost-calculation/entries", e -> respond(e, costEntriesBody(e)));
      // 🔴 **其余上游一律给空页（不是 404）**：404 会被端口翻成 503，
      //   「上游读不到」与「上游没有数据」是两件事 —— 这里要的是后者。
      //   本模块只用得到 13.2.2.2 的销量事实与 13.3.1.1 的出库成本 / 库存 / 库龄，
      //   流量 / 跟卖 / 广告 / 售后这些上游给空页即可（少给一个就 503）。
      upstream.createContext("/channel/listing-performance", e -> respond(e, page(List.of(), "items")));
      upstream.createContext(
          "/channel/listing-hijackers/records", e -> respond(e, page(List.of(), "items")));
      upstream.createContext("/channel/ad-reports/rows", e -> respond(e, page(List.of(), "items")));
      upstream.createContext("/channel/ad-campaign-details", e -> respond(e, page(List.of(), "items")));
      upstream.createContext("/trade/aftersales/cases", e -> respond(e, page(List.of(), "rows")));
      upstream.start();
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
    r.add("CATALOG_URL", () -> "http://localhost:" + upstream.getAddress().getPort());
    r.add("CHANNEL_URL", () -> "http://localhost:" + upstream.getAddress().getPort());
    r.add("TRADE_URL", () -> "http://localhost:" + upstream.getAddress().getPort());
    r.add("WAREHOUSE_URL", () -> "http://localhost:" + upstream.getAddress().getPort());
    r.add("FINANCE_URL", () -> "http://localhost:" + upstream.getAddress().getPort());
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  @Autowired TenantTransactions tenants;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;
  @Autowired ReportingProductSalesRefreshService productSalesRefresh;
  @Autowired ReportingProductPerformanceRefreshService performanceRefresh;
  @Autowired ReportingLifecycleSegmentationService segmentation;

  @AfterAll
  static void stop() {
    // ⚠️ **先停外部桩、再停容器** —— 顺序反了会让容器泄漏把机器压进 swap。
    identity.stop(0);
    upstream.stop(0);
    pg.stop();
  }

  @BeforeEach
  void reset() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate reporting_lifecycle_segmentation_amount, reporting_lifecycle_segmentation_result,"
              + " reporting_lifecycle_segmentation_rule,"
              + " reporting_product_performance_fact, reporting_product_performance_snapshot,"
              + " reporting_product_performance_metric_definition, reporting_product_metric_fact,"
              + " reporting_product_drilldown_fact, reporting_product_metric_definition,"
              + " reporting_product_listing_sku, reporting_product_dimension cascade");
    }
  }

  // ==========================================================================
  // 业务口径
  // ==========================================================================

  @Test
  void 刷新把六档分层与建议动作都落库() {
    var results = refreshAll();
    assertThat(results).hasSize(1);
    assertThat(results.get(0).status()).isEqualTo("SUCCEEDED");
    assertThat(results.get(0).projected()).isEqualTo(6);

    var response = report();
    assertThat(response.status()).isEqualTo("OK");
    assertThat(response.rows()).hasSize(6);
    assertThat(response.summary()).hasSize(6);
    // 六档全都有商品。
    assertThat(response.summary())
        .filteredOn(s -> s.productCount() > 0)
        .hasSize(6);
  }

  @Test
  void 档位按生命周期与表现判定() {
    refreshAll();
    var response = report();

    assertThat(segmentOf(response, "SKU-HOT")).isEqualTo("HOT");
    assertThat(segmentOf(response, "SKU-STRONG")).isEqualTo("STRONG");
    assertThat(segmentOf(response, "SKU-STEADY")).isEqualTo("STEADY");
    assertThat(segmentOf(response, "SKU-SLOW")).isEqualTo("SLOW");
    assertThat(segmentOf(response, "SKU-NEW")).isEqualTo("NEW");
    assertThat(segmentOf(response, "SKU-DECLINE")).isEqualTo("DECLINING");
  }

  @Test
  void 建议动作挂在档位上() {
    refreshAll();
    var response = report();

    assertThat(actionOf(response, "SKU-HOT")).isEqualTo("RESTOCK");
    assertThat(actionOf(response, "SKU-STRONG")).isEqualTo("RESTOCK");
    assertThat(actionOf(response, "SKU-STEADY")).isEqualTo("PROMOTE");
    assertThat(actionOf(response, "SKU-SLOW")).isEqualTo("CLEARANCE");
    assertThat(actionOf(response, "SKU-NEW")).isEqualTo("PROMOTE");
    assertThat(actionOf(response, "SKU-DECLINE")).isEqualTo("DELIST");

    // 建议动作必须写清依据。
    assertThat(rowOf(response, "SKU-HOT").actionNote()).contains("备货");
  }

  @Test
  void ABC按销量分档且并列同档() {
    refreshAll();
    var response = report();

    assertThat(abcQuantityOf(response, "SKU-HOT")).isEqualTo("A");
    assertThat(abcQuantityOf(response, "SKU-STRONG")).isEqualTo("B");
    assertThat(abcQuantityOf(response, "SKU-DECLINE")).isEqualTo("B");
    assertThat(abcQuantityOf(response, "SKU-STEADY")).isEqualTo("C");
    assertThat(abcQuantityOf(response, "SKU-NEW")).isEqualTo("C");
    assertThat(abcQuantityOf(response, "SKU-SLOW")).isEqualTo("C");
    assertThat(rowOf(response, "SKU-HOT").abcTotalByQuantity()).isEqualTo(6);
  }

  @Test
  void ABC按金额分币种排名且不跨币种() {
    refreshAll();
    var response = report();

    // 🔴 **SKU-STRONG 是 EUR**：它的金额榜只有它自己 ⇒ EUR 里是 A。
    assertThat(abcAmountOf(response, "SKU-STRONG", "EUR")).isEqualTo("A");
    // USD 榜里没有 SKU-STRONG（它的成交额是 EUR）。
    assertThat(abcAmountOf(response, "SKU-STRONG", "USD")).isNull();
    assertThat(abcAmountOf(response, "SKU-HOT", "USD")).isEqualTo("A");
    assertThat(abcAmountOf(response, "SKU-DECLINE", "USD")).isEqualTo("B");
    assertThat(abcAmountOf(response, "SKU-STEADY", "USD")).isEqualTo("C");
    // EUR 榜里只有 1 个商品。
    assertThat(amountOf(response, "SKU-STRONG", "EUR").abcTotalByAmount()).isEqualTo(1);
  }

  @Test
  void 毛利按币种分开且不跨币种相加() {
    refreshAll();
    var response = report();

    var hot = amountOf(response, "SKU-HOT", "USD");
    assertThat(hot.salesAmount()).isEqualByComparingTo("3000.00");
    assertThat(hot.grossProfitAmount()).isEqualByComparingTo("1500.00");
    assertThat(hot.grossProfitAvailability()).isEqualTo("AVAILABLE");

    // 🔴 SKU-STRONG 是 EUR：毛利 200.00 − 120.00 = 80.00 EUR，**没有 USD 那一行**。
    var strong = amountOf(response, "SKU-STRONG", "EUR");
    assertThat(strong.salesAmount()).isEqualByComparingTo("200.00");
    assertThat(strong.grossProfitAmount()).isEqualByComparingTo("80.00");
    assertThat(amountsOf(response, "SKU-STRONG")).hasSize(1);

    // 分档汇总里的 USD 销售额**不含** EUR 的那一档。
    var hotSummary = summaryOf(response, "HOT");
    assertThat(hotSummary.salesQuantity()).isEqualByComparingTo("300");
    assertThat(amountOfSummary(hotSummary, "USD").salesAmount()).isEqualByComparingTo("3000.00");
  }

  @Test
  void 毛利算不出来时是null不是0() {
    refreshAll();
    var response = report();

    // 🔴 SKU-SLOW 没有出库成本 ⇒ 毛利额**算不出来**（null，**不是 0**）。
    var slow = amountOf(response, "SKU-SLOW", "USD");
    assertThat(slow.grossProfitAmount()).isNull();
    assertThat(slow.grossProfitAvailability()).isEqualTo("NO_DATA");
    assertThat(slow.grossProfitNote()).contains("13.3.1.1");
    // 销售额仍在（毛利不可算 ≠ 销售额也没有）。
    assertThat(slow.salesAmount()).isEqualByComparingTo("10.00");
  }

  @Test
  void 分档汇总给出各层建议与毛利可得性() {
    refreshAll();
    var response = report();

    var slow = summaryOf(response, "SLOW");
    assertThat(slow.productCount()).isEqualTo(1);
    assertThat(slow.recommendedAction()).isEqualTo("CLEARANCE");
    assertThat(slow.actionLabel()).isEqualTo("清仓");
    // 🔴 这一档的毛利**全部算不出来** ⇒ 合计是 null 且写明原因（不是 0）。
    var slowUsd = amountOfSummary(slow, "USD");
    assertThat(slowUsd.grossProfitAmount()).isNull();
    assertThat(slowUsd.grossProfitAvailability()).isEqualTo("NO_DATA");
  }

  @Test
  void 窗口外的事实不参与计算() {
    refreshAll();
    var response = report();

    // 窗口内销量只算 10-01 ~ 10-04 的订单；对比窗口与更早的历史不进份额。
    assertThat(rowOf(response, "SKU-HOT").salesQuantity()).isEqualByComparingTo("300");
    // 🔴 **比例是百分数**（服务端已 ×100）：300 / 340 = 88.2353%。
    assertThat(rowOf(response, "SKU-HOT").salesShare()).isEqualByComparingTo("88.2353");
    // 对比窗口的销量另记一栏（供衰退判定用），不混进当期。
    assertThat(rowOf(response, "SKU-DECLINE").priorQuantity()).isEqualByComparingTo("120");
    // 对比窗日均 120 ÷ 30 = 4.00，当期日均 10 ÷ 10 = 1.00 ⇒ 下滑 75.0000%。
    assertThat(rowOf(response, "SKU-DECLINE").declineRatio()).isEqualByComparingTo("75.0000");
  }

  @Test
  void 衰退判定算不出来时写明原因() {
    refreshAll();
    var response = report();

    // 🔴 SKU-SLOW 在对比窗口里有 1 件销量 ⇒ 衰退可算（比值 < 阈值 ⇒ 不是衰退款）。
    var slow = rowOf(response, "SKU-SLOW");
    assertThat(slow.declineRatio()).isNotNull();
    assertThat(slow.segmentNote()).contains("滞销");

    // 🔴 没有任何历史（把 SKU-NEW 的历史删掉另测）—— 这里换个口径验证「不是 0」。
    assertThat(rowOf(response, "SKU-HOT").declineRatio()).isEqualByComparingTo("0.0000");
  }

  @Test
  void 重复刷新幂等() {
    refreshAll();
    var first = report();
    refreshAll();
    var second = report();

    assertThat(second.rows()).hasSize(first.rows().size());
    assertThat(second.total()).isEqualTo(first.total());
    assertThat(resultRowCount()).isEqualTo(6);
    assertThat(amountRowCount()).isEqualTo(6);
  }

  @Test
  void SPU层由SKU上卷() {
    refreshAll();
    // 🔴 SPU 层要**另外刷一次**（规则与结果都是按维度层分开落的）。
    segmentation.refresh(tenant, FROM, TO, "SPU");
    var response =
        segmentation.segmentation(tenant, FROM, TO, "SPU", null, null, 0, 50);

    assertThat(response.rows()).hasSize(6);
    var spu = rowOf(response, "SPU-HOT");
    assertThat(spu.lifecycleSegment()).isEqualTo("HOT");
    assertThat(spu.salesQuantity()).isEqualByComparingTo("300");
    // 🔴 SPU 层也有自己的规则（不是 SKU 那条）。
    assertThat(response.ruleCode()).isEqualTo("LIFECYCLE_DEFAULT_SPU");
  }

  @Test
  void 规则可配置且可审计() {
    refreshAll();
    var before = ruleViews();

    var updated =
        segmentation.updateRule(
            tenant,
            "SKU",
            new ReportingLifecycleSegmentationModels.LifecycleSegmentationRuleInput(
                "自定义分层规则", null, 7, null, null, null, null, null, null, null, null, null,
                null, null, "把新品窗口收窄到 7 天"),
            user.toString());

    assertThat(updated.version()).isEqualTo(2);
    assertThat(updated.ruleName()).isEqualTo("自定义分层规则");
    assertThat(updated.newMaxDaysSinceFirstSale()).isEqualTo(7);
    assertThat(updated.enabled()).isTrue();

    // 🔴 **旧版本停用并写上生效结束日**（可审计）。
    var after = ruleViews();
    assertThat(after).hasSize(2);
    var old = after.stream().filter(v -> v.version() == 1).findFirst().orElseThrow();
    assertThat(old.enabled()).isFalse();
    assertThat(old.effectiveTo()).isNotNull();
    assertThat(old.createdBy()).isEqualTo("system-default");

    // 🔴 **历史分层结果仍指向旧版本**（改规则不改历史结论）。
    assertThat(resultRuleVersion()).isEqualTo(1);
    // 重跑之后才按新版本算。
    segmentation.refresh(tenant, FROM, TO, "SKU");
    assertThat(resultRuleVersion()).isEqualTo(2);
  }

  @Test
  void 非法阈值被拒绝() {
    refreshAll();

    // 🔴 ABC 的 A 累计比必须小于 B —— 非法组合当场拒绝，不写半条规则进库。
    assertThatThrownBy(
            () ->
                segmentation.updateRule(
                    tenant,
                    "SKU",
                    new ReportingLifecycleSegmentationModels.LifecycleSegmentationRuleInput(
                        null, null, null, null, null, null, null, null, null, null,
                        new BigDecimal("96.0000"), new BigDecimal("80.0000"), null, null, null),
                    user.toString()))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
        .hasMessageContaining("累计比");
    assertThat(ruleViews()).hasSize(1);
  }

  @Test
  void 上游口径未投影时报不可算而不是0() throws Exception {
    // 🔴 **先把上游口径表清空**（模拟 13.2.2.2 / 13.3.1.1 还没播种）。
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate reporting_product_performance_metric_definition,"
              + " reporting_product_metric_definition cascade");
    }

    var results = segmentation.refresh(tenant, FROM, TO, "SKU");
    assertThat(results).hasSize(1);
    assertThat(results.get(0).status()).isEqualTo("FAILED");
    assertThat(results.get(0).note()).contains("口径未投影");
    assertThat(results.get(0).projected()).isZero();
    assertThat(resultRowCount()).isZero();
  }

  @Test
  void 空窗口返回空态而不是报错() {
    var response =
        segmentation.segmentation(
            tenant, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 2), "SKU", null, null, 0, 50);

    assertThat(response.status()).isEqualTo("EMPTY");
    assertThat(response.emptyReason()).isEqualTo("NO_SEGMENTATION_IN_WINDOW");
    assertThat(response.emptyNote()).contains("refresh");
    assertThat(response.rows()).isEmpty();
    assertThat(response.dataUpdatedAt()).isNull();
  }

  @Test
  void 分页是真的() {
    refreshAll();
    var page1 = segmentation.segmentation(tenant, FROM, TO, "SKU", null, null, 0, 2);
    var page2 = segmentation.segmentation(tenant, FROM, TO, "SKU", null, null, 1, 2);

    assertThat(page1.rows()).hasSize(2);
    assertThat(page2.rows()).hasSize(2);
    assertThat(page1.total()).isEqualTo(6);
    assertThat(page1.size()).isEqualTo(2);
    // 两页的商品不重叠。
    assertThat(keysOf(page1)).doesNotContainAnyElementsOf(keysOf(page2));
  }

  @Test
  void 档位与ABC筛选真的生效() {
    refreshAll();
    var hotOnly = segmentation.segmentation(tenant, FROM, TO, "SKU", "HOT", null, 0, 50);
    assertThat(hotOnly.rows()).hasSize(1);
    assertThat(hotOnly.rows().get(0).dimensionKey()).isEqualTo("SKU-HOT");
    assertThat(hotOnly.total()).isEqualTo(1);

    var aOnly = segmentation.segmentation(tenant, FROM, TO, "SKU", null, "A", 0, 50);
    assertThat(aOnly.rows()).hasSize(1);
    assertThat(aOnly.rows().get(0).abcByQuantity()).isEqualTo("A");
  }

  @Test
  void 数据更新时间常驻() {
    refreshAll();
    var response = report();
    assertThat(response.dataUpdatedAt()).isNotNull();
    assertThat(response.dataUpdatedNote()).contains("投影");
  }

  @Test
  void timestamptz往返逐字相等() {
    refreshAll();

    // 🔴 **用 ?::timestamptz + OffsetDateTime 绑定一个带 +08:00 偏移的夹具**，
    //   再原样读回来 —— **瞬间必须一模一样**。
    //   ⚠️ 有人用 `Timestamp.valueOf(LocalDateTime)` 就会**静默偏 −28800s**：
    //   JVM 本地时区被当成 UTC，页面上的时刻整整差 8 小时，而且**不抛任何异常**。
    var fixture = OffsetDateTime.of(2026, 10, 4, 18, 0, 0, 0, ZoneOffset.ofHours(8));
    tenants.run(
        tenant,
        () -> {
          sql.execute(
              "update reporting_lifecycle_segmentation_result set source_observed_at = ?::timestamptz"
                  + " where tenant_id = ? and dimension_key = ?",
              fixture,
              tenant,
              "SKU-HOT");
          return null;
        });

    var response = report();
    var row = rowOf(response, "SKU-HOT");
    assertThat(row.sourceObservedAt()).as("timestamptz 往返必须逐字相等").isNotNull();
    assertThat(row.sourceObservedAt().toInstant())
        .as("🔴 偏 −28800s 就是 Timestamp.valueOf(LocalDateTime) 的锅")
        .isEqualTo(fixture.toInstant());
    // 🔴 偏移也原样保留（PG 默认会话时区是 UTC ⇒ 读回来是 +00:00 的同一瞬间）。
    assertThat(row.sourceObservedAt().toInstant().toString())
        .isEqualTo(fixture.toInstant().toString());
  }

  @Test
  void 端点权限与形状() throws Exception {
    refreshAll();

    http.perform(get("/reporting/lifecycle-segmentation/capabilities").header("Authorization", BEARER))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.notDelivered").isArray())
        .andExpect(jsonPath("$.rules").isArray());

    http.perform(
            get("/reporting/lifecycle-segmentation")
                .header("Authorization", BEARER)
                .param("from", FROM.toString())
                .param("to", TO.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rows").isArray())
        .andExpect(jsonPath("$.summary").isArray());

    http.perform(get("/reporting/lifecycle-segmentation/rules").header("Authorization", BEARER))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].ruleCode").exists());

    // 🔴 **非法维度层 ⇒ 400**（不静默回落成 SKU）。
    http.perform(
            get("/reporting/lifecycle-segmentation")
                .header("Authorization", BEARER)
                .param("dimensionType", "LISTING"))
        .andExpect(status().isBadRequest());

    // 🔴 **没有 Authorization ⇒ 401**。
    http.perform(get("/reporting/lifecycle-segmentation")).andExpect(status().isUnauthorized());
  }

  // ==========================================================================
  // 工具
  // ==========================================================================

  private List<ReportingLifecycleSegmentationModels.LifecycleSegmentationRefreshResult> refreshAll() {
    // 13.2.2.2 投影：商品维度档案 + 销量 / 销售额日事实（**含对比窗口**）。
    productSalesRefresh.refresh(tenant, BEARER, BASE_FROM, TO, ZONE);
    // 13.3.1.1 投影：出库成本事实 + 库存 / 库龄快照 + 口径播种。
    performanceRefresh.refresh(tenant, BEARER, FROM, TO, BASE_FROM, BASE_TO, ZONE);
    return segmentation.refresh(tenant, FROM, TO, "SKU");
  }

  private ReportingLifecycleSegmentationModels.LifecycleSegmentationResponse report() {
    return segmentation.segmentation(tenant, FROM, TO, "SKU", null, null, 0, 50);
  }

  private ReportingLifecycleSegmentationModels.LifecycleSegmentationRow rowOf(
      ReportingLifecycleSegmentationModels.LifecycleSegmentationResponse response, String key) {
    return response.rows().stream()
        .filter(r -> r.dimensionKey().equals(key))
        .findFirst()
        .orElseThrow(() -> new AssertionError("分层结果里没有 " + key));
  }

  private String segmentOf(
      ReportingLifecycleSegmentationModels.LifecycleSegmentationResponse response, String key) {
    return rowOf(response, key).lifecycleSegment();
  }

  private String actionOf(
      ReportingLifecycleSegmentationModels.LifecycleSegmentationResponse response, String key) {
    return rowOf(response, key).recommendedAction();
  }

  private String abcQuantityOf(
      ReportingLifecycleSegmentationModels.LifecycleSegmentationResponse response, String key) {
    return rowOf(response, key).abcByQuantity();
  }

  private String abcAmountOf(
      ReportingLifecycleSegmentationModels.LifecycleSegmentationResponse response,
      String key,
      String currency) {
    var amount = amountOf(response, key, currency);
    return amount == null ? null : amount.abcByAmount();
  }

  private ReportingLifecycleSegmentationModels.LifecycleSegmentationAmountRow amountOf(
      ReportingLifecycleSegmentationModels.LifecycleSegmentationResponse response,
      String key,
      String currency) {
    return amountsOf(response, key).stream()
        .filter(a -> a.currency().equals(currency))
        .findFirst()
        .orElse(null);
  }

  private List<ReportingLifecycleSegmentationModels.LifecycleSegmentationAmountRow> amountsOf(
      ReportingLifecycleSegmentationModels.LifecycleSegmentationResponse response, String key) {
    return rowOf(response, key).amounts();
  }

  private ReportingLifecycleSegmentationModels.LifecycleSegmentSummary summaryOf(
      ReportingLifecycleSegmentationModels.LifecycleSegmentationResponse response, String segment) {
    return response.summary().stream()
        .filter(s -> s.segment().equals(segment))
        .findFirst()
        .orElseThrow(() -> new AssertionError("分档汇总里没有 " + segment));
  }

  private ReportingLifecycleSegmentationModels.LifecycleSegmentationAmountRow amountOfSummary(
      ReportingLifecycleSegmentationModels.LifecycleSegmentSummary summary, String currency) {
    return summary.amounts().stream()
        .filter(a -> a.currency().equals(currency))
        .findFirst()
        .orElse(null);
  }

  private List<String> keysOf(
      ReportingLifecycleSegmentationModels.LifecycleSegmentationResponse response) {
    return response.rows().stream().map(r -> r.dimensionKey()).toList();
  }

  private List<ReportingLifecycleSegmentationModels.LifecycleSegmentationRuleView> ruleViews() {
    return segmentation.rules(tenant, "SKU");
  }

  private long resultRowCount() {
    return tenants.run(tenant, () -> sql.fetch("select id from reporting_lifecycle_segmentation_result").size());
  }

  private long amountRowCount() {
    return tenants.run(
        tenant, () -> sql.fetch("select id from reporting_lifecycle_segmentation_amount").size());
  }

  private int resultRuleVersion() {
    return tenants.run(
        tenant,
        () ->
            sql.fetchOne(
                    "select distinct rule_version from reporting_lifecycle_segmentation_result")
                .get(0, Integer.class));
  }

  // ---------------------------------------------------------------------------
  // 夹具
  // ---------------------------------------------------------------------------

  private static Map<String, Object> sku(
      UUID id, String code, String name, UUID spuId, String spuCode) {
    return Map.of(
        "id", id.toString(),
        "code", code,
        "name", name,
        "spuId", spuId.toString(),
        "spuCode", spuCode,
        "spuName", spuCode,
        "variantSignature", code,
        "status", "ACTIVE");
  }

  private static Map<String, Object> listing(
      UUID id, String shopCode, String platformCode, String externalItemId, List<UUID> skuIds) {
    return Map.of(
        "id", id.toString(),
        "shopCode", shopCode,
        "shopName", shopCode,
        "platformCode", platformCode,
        "marketplaceCode", platformCode,
        "externalItemId", externalItemId,
        "title", externalItemId,
        "status", "ACTIVE",
        "mappingStatus", "MAPPED",
        "internalSkuIds", skuIds.stream().map(UUID::toString).toList());
  }

  private static Map<String, Object> order(
      String orderNo, LocalDate day, String currency, String skuCode, String quantity, String amount) {
    return Map.of(
        "id", UUID.randomUUID().toString(),
        "orderNo", orderNo,
        "skuCode", skuCode,
        "orderSource", "MANUAL",
        "currency", currency,
        "grandTotalAmount", amount,
        "quantity", quantity,
        "lineCount", 1,
        "submittedAt",
            OffsetDateTime.of(day, java.time.LocalTime.of(10, 0), ZoneOffset.UTC).toString(),
        "createdAt",
            OffsetDateTime.of(day, java.time.LocalTime.of(10, 0), ZoneOffset.UTC).toString());
  }

  private static Map<String, Object> costEntry(
      UUID runId, UUID flowId, UUID skuId, String skuCode, LocalDate day, String amount, String currency) {
    return map(
        "id", UUID.randomUUID().toString(),
        "runId", runId.toString(),
        "flowTransactionId", flowId.toString(),
        "occurredAt",
            OffsetDateTime.of(day, java.time.LocalTime.of(8, 0), ZoneOffset.UTC).toString(),
        "businessType", "SALES_OUTBOUND",
        "skuId", skuId.toString(),
        "skuCode", skuCode,
        "issueQuantity", "1",
        "costAmount", amount,
        "currency", currency,
        "status", "CALCULATED");
  }

  // ---------------------------------------------------------------------------
  // 假上游
  // ---------------------------------------------------------------------------

  private static void respond(com.sun.net.httpserver.HttpExchange e, Object body) {
    try {
      if (body == null) {
        e.sendResponseHeaders(404, -1);
        e.close();
        return;
      }
      byte[] bytes = JSON.writeValueAsBytes(body);
      e.getResponseHeaders().add("Content-Type", "application/json");
      e.sendResponseHeaders(200, bytes.length);
      e.getResponseBody().write(bytes);
      e.close();
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }

  /** 造一个保持顺序的 Map（{@code Map.of} 最多 10 对，夹具常常超）。 */
  private static Map<String, Object> map(Object... pairs) {
    var out = new LinkedHashMap<String, Object>();
    for (int i = 0; i + 1 < pairs.length; i += 2) {
      out.put(String.valueOf(pairs[i]), pairs[i + 1]);
    }
    return out;
  }

  /** 一页数据（列表字段名由 {@code field} 决定）。 */
  private static Map<String, Object> page(List<Map<String, Object>> rows, String field) {
    var body = new LinkedHashMap<String, Object>();
    body.put(field, rows);
    body.put("page", 0);
    body.put("size", 200);
    body.put("total", rows.size());
    return body;
  }

  private static Map<String, Object> ordersBody(com.sun.net.httpserver.HttpExchange e)
      throws java.io.IOException {
    var query = query(e.getRequestURI().getRawQuery());
    var from = parseInstant(query.get("orderedFrom"));
    var to = parseInstant(query.get("orderedTo"));
    var matched = new ArrayList<Map<String, Object>>();
    for (var row : ORDERS) {
      var at = parseInstant(String.valueOf(row.get("submittedAt")));
      if (at == null) continue;
      if (from != null && at.isBefore(from)) continue;
      if (to != null && !at.isBefore(to)) continue;
      matched.add(row);
    }
    return page(matched, "rows");
  }

  private static Map<String, Object> detailBody(com.sun.net.httpserver.HttpExchange e)
      throws java.io.IOException {
    var path = e.getRequestURI().getPath();
    var id = path.substring(path.lastIndexOf('/') + 1);
    var matched = new ArrayList<Map<String, Object>>();
    for (var row : ORDERS) {
      if (!row.get("id").equals(id)) continue;
      var skuCode = String.valueOf(row.get("skuCode"));
      matched.add(
          Map.of(
              "skuCode", skuCode,
              "skuName", skuCode,
              "quantity", row.get("quantity"),
              "unitPrice", row.get("grandTotalAmount"),
              "lineAmount", row.get("grandTotalAmount")));
    }
    var body = new LinkedHashMap<String, Object>();
    body.put("header", Map.of("orderNo", "SO", "currency", "USD"));
    body.put("lines", matched);
    return body;
  }

  private static Map<String, Object> costEntriesBody(com.sun.net.httpserver.HttpExchange e)
      throws java.io.IOException {
    var query = query(e.getRequestURI().getRawQuery());
    var businessType = query.get("businessType");
    var matched = new ArrayList<Map<String, Object>>();
    for (var row : COST_ENTRIES) {
      if (businessType != null && !businessType.equals(row.get("businessType"))) continue;
      matched.add(row);
    }
    return page(matched, "items");
  }

  private static Map<String, String> query(String raw) {
    var out = new LinkedHashMap<String, String>();
    if (raw == null || raw.isBlank()) return out;
    for (var pair : raw.split("&")) {
      var index = pair.indexOf('=');
      if (index < 0) continue;
      out.put(
          URLDecoder.decode(pair.substring(0, index), StandardCharsets.UTF_8),
          URLDecoder.decode(pair.substring(index + 1), StandardCharsets.UTF_8));
    }
    return out;
  }

  /** 解析上游时刻（两种格式都要认 —— 只认一种会让夹具静默变成零行）。 */
  private static java.time.Instant parseInstant(String raw) {
    if (raw == null || raw.isBlank()) return null;
    try {
      return java.time.Instant.parse(raw);
    } catch (RuntimeException ignored) {
      // 落到下面按 OffsetDateTime 解析。
    }
    try {
      return OffsetDateTime.parse(raw).toInstant();
    } catch (RuntimeException e) {
      return null;
    }
  }
}
