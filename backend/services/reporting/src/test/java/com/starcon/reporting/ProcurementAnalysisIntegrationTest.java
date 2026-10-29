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
 * 13.5.2.1 采购分析的**集成测试**（真实 PostgreSQL 16 + FORCE RLS，无内存替身）。
 *
 * <p>跑的是本模块的**业务口径**：
 *
 * <ol>
 *   <li>🔴 **采购金额逐币种**，**多币种绝不相加**（跨币种的合计不存在）；</li>
 *   <li>🔴 **供应商集中度给的是风险信号，不是一个中性百分比**
 *       （蓝图加粗句：单一供应商占比过高会在断供时直接击穿业务）；</li>
 *   <li>🔴 **价格波动**：当前期间 vs 上一等长期间的**加权均价**；
 *       上一期间没有数量 ⇒ 环比**不可比**（不是 0%）；</li>
 *   <li>🔴 **到货准时率**：分母 = 已到货流水（PENDING 不进分母）；分母为 0 ⇒ 不可比；</li>
 *   <li>🔴 **采购周期** = 到货日 − 下单日（**实际周期**，不是承诺交期）；
 *       没有到货记录 ⇒ 不可比（不是 0 天）；</li>
 *   <li>🔴 **采购成本占销售额比**逐币种算，分母取 13.1.1.1 已投影的 SALES_AMOUNT
 *       （本模块**不重算**销售额）；</li>
 *   <li>**供应商对比**：采购贡献 + 准时率 + 采购周期 + **供应商评级**（未评级不是 0 分）；</li>
 *   <li>**没有金额的流水是「—」，不在任何合计里**（不是 0 元）；</li>
 *   <li>同一批上游事实重放被幂等跳过。</li>
 * </ol>
 *
 * <p>租户隔离的三条断言在 {@code ProcurementAnalysisTenancyIntegrationTest}（同一批跑）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ProcurementAnalysisIntegrationTest {

  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  static HttpServer upstream;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  static final List<String> permissions =
      List.of(
          "reporting.procurement-analysis.read",
          "reporting.procurement-analysis.write",
          "reporting.projection.read",
          "reporting.projection.write");

  static final ObjectMapper JSON = JsonMapper.builder().addModule(new JavaTimeModule()).build();

  /** 当前窗口起。 */
  static final LocalDate FROM = LocalDate.of(2026, 10, 1);

  /** 当前窗口止。 */
  static final LocalDate TO = LocalDate.of(2026, 10, 31);

  // ===== 供应商（catalog 的 party_role id；与 trade 的 supplier_ref_id 同源）=====
  static final UUID SUP_A = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
  static final UUID SUP_B = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000002");
  static final UUID SUP_C = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000003");
  static final UUID SUP_D = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000004");
  static final UUID SUP_E = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000005");
  static final UUID SUP_F = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000006");
  static final UUID SUP_G = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000007");

  // ===== SKU =====
  static final UUID SKU_1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
  static final UUID SKU_2 = UUID.fromString("22222222-2222-2222-2222-222222222222");
  static final UUID SKU_3 = UUID.fromString("33333333-3333-3333-3333-333333333333");
  static final UUID SKU_4 = UUID.fromString("44444444-4444-4444-4444-444444444444");

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

      upstream = HttpServer.create(new InetSocketAddress(0), 0);
      upstream.createContext("/trade/purchase-analytics/facts", e -> respond(e, tradeFacts()));
      upstream.createContext("/trade/supplier-ratings/ratings", e -> respond(e, tradeRatings()));
      upstream.createContext("/catalog/supply/vendors", e -> respond(e, catalogVendors(e)));
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
    r.add("CATALOG_URL", () -> "http://localhost:" + upstream.getAddress().getPort());
    r.add("TRADE_URL", () -> "http://localhost:" + upstream.getAddress().getPort());
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  @Autowired TenantTransactions tenants;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;
  @Autowired ReportingProcurementAnalysisService procurementAnalysis;
  @Autowired ReportingProcurementAnalysisRefreshService refresh;
  @Autowired ReportingProjectionService projection;

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
          "truncate reporting_procurement_analysis_purchase_fact,"
              + " reporting_procurement_analysis_delivery_fact,"
              + " reporting_procurement_analysis_supplier_fact,"
              + " reporting_procurement_analysis_rule,"
              + " reporting_metric_fact cascade");
    }
  }

  // ==========================================================================
  // 夹具：假上游
  // ==========================================================================

  /**
   * trade 6.5.2.1 的采购事实台账。
   *
   * <p>⚠️ **金额口径的刻意设计**：
   *
   * <ul>
   *   <li>USD 桶：甲供应商 3200（74.42%）、丁供应商 1100 ⇒ **单一供应商占比过高**（高风险）；</li>
   *   <li>EUR 桶：乙 500 / 戊 400 / 己 350 / 庚 250 ⇒ 没有单点依赖（33.33% &lt; 40%），
   *       但**前 3 家合计 83.33% ≥ 60%** ⇒ **中风险**；</li>
   *   <li>丙供应商那一条**没有金额 / 币种** ⇒ 金额是「—」，**不在任何合计里**（不是 0 元）；</li>
   *   <li>丁供应商那条 PENDING（已承诺、未到货）—— 计入金额口径，但**不进准时率分母**。</li>
   * </ul>
   */
  static Map<String, Object> tradeFacts() {
    var items = new ArrayList<Map<String, Object>>();
    // 当前窗口（2026-10）
    items.add(fact("F1", "2026-10-01", SUP_A, "SUP-1", "甲供应商", SKU_1, "SKU-1", "家居收纳", "SHOP-1",
        "100", "10", "1000", "USD", "2026-10-01", "ON_TIME"));
    items.add(fact("F2", "2026-10-02", SUP_A, "SUP-1", "甲供应商", SKU_2, "SKU-2", "厨房用品", "SHOP-1",
        "50", "20", "1000", "USD", "2026-10-01", "LATE"));
    items.add(fact("F3", "2026-10-05", SUP_A, "SUP-1", "甲供应商", SKU_1, "SKU-1", "家居收纳", "SHOP-2",
        "100", "12", "1200", "USD", "2026-10-05", "ON_TIME"));
    items.add(fact("F4", "2026-10-03", SUP_B, "SUP-2", "乙供应商", SKU_3, "SKU-3", "家居收纳", "SHOP-1",
        "20", "25", "500", "EUR", "2026-10-03", "ON_TIME"));
    items.add(fact("F5", "2026-10-04", SUP_E, "SUP-5", "戊供应商", SKU_3, "SKU-3", "家居收纳", "SHOP-1",
        "16", "25", "400", "EUR", "2026-10-04", "ON_TIME"));
    items.add(fact("F6", "2026-10-04", SUP_F, "SUP-6", "己供应商", SKU_2, "SKU-2", "厨房用品", "SHOP-2",
        "14", "25", "350", "EUR", "2026-10-04", "ON_TIME"));
    items.add(fact("F7", "2026-10-04", SUP_G, "SUP-7", "庚供应商", SKU_4, "SKU-4", "厨房用品", "SHOP-2",
        "10", "25", "250", "EUR", "2026-10-04", "ON_TIME"));
    items.add(fact("F8", "2026-10-06", SUP_D, "SUP-4", "丁供应商", SKU_2, "SKU-2", "厨房用品", "SHOP-1",
        "25", "20", "500", "USD", "2026-10-06", "ON_TIME"));
    items.add(fact("F9", "2026-10-07", SUP_D, "SUP-4", "丁供应商", SKU_2, "SKU-2", "厨房用品", "SHOP-1",
        "30", "20", "600", "USD", "2026-10-20", "PENDING"));
    // ⚠️ **没有金额 / 币种**的一行：金额是「算不出来」，不是 0。
    items.add(fact("F10", "2026-10-03", SUP_C, "SUP-3", "丙供应商", SKU_4, "SKU-4", "厨房用品", "SHOP-1",
        "10", null, null, null, "2026-10-03", "ON_TIME"));
    // 上一等长期间（2026-08-31 ~ 2026-09-30）—— 环比的对照组。
    items.add(fact("P1", "2026-09-10", SUP_A, "SUP-1", "甲供应商", SKU_1, "SKU-1", "家居收纳", "SHOP-1",
        "100", "8", "800", "USD", "2026-09-10", "ON_TIME"));
    items.add(fact("P2", "2026-09-20", SUP_A, "SUP-1", "甲供应商", SKU_2, "SKU-2", "厨房用品", "SHOP-1",
        "50", "18", "900", "USD", "2026-09-20", "ON_TIME"));
    return page("items", items);
  }

  static Map<String, Object> fact(
      String key,
      String date,
      UUID supplierId,
      String supplierCode,
      String supplierName,
      UUID skuId,
      String skuCode,
      String category,
      String shop,
      String quantity,
      String unitPrice,
      String amount,
      String currency,
      String promised,
      String onTimeStatus) {
    var row = new LinkedHashMap<String, Object>();
    row.put("id", fakeUuid(key));
    row.put("factKey", "ARRIVAL_RECEIPT:" + key);
    row.put("factKind", "COST");
    row.put("sourceType", "ARRIVAL_RECEIPT");
    row.put("sourceDocNo", "PO-" + key);
    row.put("sourceRefNo", "TRK-" + key);
    row.put("occurredAt", date + "T08:00:00Z");
    row.put("occurredDate", date);
    row.put("supplierRefId", supplierId.toString());
    row.put("supplierCode", supplierCode);
    row.put("supplierName", supplierName);
    row.put("skuRefId", skuId.toString());
    row.put("skuCode", skuCode);
    row.put("skuName", skuCode + " 名称");
    row.put("categoryCode", category);
    row.put("categoryName", category);
    row.put("shopCode", shop);
    row.put("warehouseCode", "WH-1");
    row.put("quantity", quantity);
    if (unitPrice != null) row.put("unitPrice", unitPrice);
    if (amount != null) {
      row.put("amount", amount);
      row.put("currency", currency);
    }
    row.put("promisedArrivalDate", promised);
    row.put("onTimeStatus", onTimeStatus);
    return row;
  }

  /**
   * trade 6.1.1.3 的供应商评级。
   *
   * <p>⚠️ **戊供应商刻意给了两期**（6 月与 9 月）⇒ 投影必须只留**最新一期**。
   * ⚠️ **丙供应商没有评级** ⇒ 评级列是「—（未评级）」，**不是 0 分**。
   */
  static Map<String, Object> tradeRatings() {
    var items = new ArrayList<Map<String, Object>>();
    items.add(rating("R1", "2026-09-01", "2026-09-30", SUP_A, "SUP-1", "甲供应商", "82.5", "B"));
    items.add(rating("R2", "2026-09-01", "2026-09-30", SUP_B, "SUP-2", "乙供应商", "91", "A"));
    items.add(rating("R3", "2026-09-01", "2026-09-30", SUP_D, "SUP-4", "丁供应商", "60", "C"));
    // 戊供应商：旧的一期 + 新的一期 ⇒ 只应留新的。
    items.add(rating("R4", "2026-06-01", "2026-06-30", SUP_E, "SUP-5", "戊供应商", "70", "C"));
    items.add(rating("R5", "2026-09-01", "2026-09-30", SUP_E, "SUP-5", "戊供应商", "88", "B"));
    return page("items", items);
  }

  static Map<String, Object> rating(
      String id,
      String from,
      String to,
      UUID supplierId,
      String supplierCode,
      String supplierName,
      String score,
      String grade) {
    var row = new LinkedHashMap<String, Object>();
    row.put("id", fakeUuid(id));
    row.put("periodType", "MONTHLY");
    row.put("periodStart", from);
    row.put("periodEnd", to);
    row.put("supplierRefId", supplierId.toString());
    row.put("supplierCode", supplierCode);
    row.put("supplierName", supplierName);
    row.put("objectiveScore", score);
    row.put("manualAdjustment", "0");
    row.put("finalScore", score);
    row.put("grade", grade);
    row.put("deliveryScore", "80");
    row.put("qualityScore", "90");
    row.put("priceScore", "75");
    row.put("responseScore", "85");
    row.put("serviceScore", "70");
    row.put("computedAt", to + "T00:00:00Z");
    return row;
  }

  /** catalog 6.1.1.1 的供应商列表 + 到货记录（按 roleId 区分两个形状）。 */
  static Map<String, Object> catalogVendors(com.sun.net.httpserver.HttpExchange e) {
    var path = e.getRequestURI().getPath();
    if (path.equals("/catalog/supply/vendors")) {
      var items = new ArrayList<Map<String, Object>>();
      items.add(vendor(SUP_A, "SUP-1", "甲供应商", "STRATEGIC", "ACTIVE", "CN"));
      items.add(vendor(SUP_B, "SUP-2", "乙供应商", "COOPERATION", "ACTIVE", "VN"));
      items.add(vendor(SUP_C, "SUP-3", "丙供应商", "", "", "CN"));
      items.add(vendor(SUP_D, "SUP-4", "丁供应商", "", "SUSPENDED", "CN"));
      items.add(vendor(SUP_E, "SUP-5", "戊供应商", "", "ACTIVE", "CN"));
      items.add(vendor(SUP_F, "SUP-6", "己供应商", "", "ACTIVE", "CN"));
      items.add(vendor(SUP_G, "SUP-7", "庚供应商", "", "ACTIVE", "CN"));
      return page("items", items);
    }
    // 详情：/catalog/supply/vendors/{roleId}
    var roleId = path.substring(path.lastIndexOf('/') + 1);
    var view = new LinkedHashMap<String, Object>();
    view.put("summary", vendor(UUID.fromString(roleId), "SUP", "供应商", "", "ACTIVE", "CN"));
    var deliveries = new ArrayList<Map<String, Object>>();
    switch (roleId) {
      case "aaaaaaaa-0000-0000-0000-000000000001" -> {
        // 甲供应商：两条到货 ⇒ 周期 11 天与 7 天。
        deliveries.add(delivery("D1", "2026-09-20T00:00:00Z", "2026-10-05", "2026-10-01", false));
        deliveries.add(delivery("D2", "2026-09-28T00:00:00Z", "2026-10-06", "2026-10-05", false));
      }
      case "aaaaaaaa-0000-0000-0000-000000000002" ->
          // 乙供应商：一条到货 ⇒ 周期 8 天。
          deliveries.add(delivery("D3", "2026-09-25T00:00:00Z", "2026-10-04", "2026-10-03", false));
      case "aaaaaaaa-0000-0000-0000-000000000003" ->
          // 丙供应商：一条到货 ⇒ 周期 8 天。
          deliveries.add(delivery("D4", "2026-10-01T00:00:00Z", "2026-10-10", "2026-10-09", false));
      case "aaaaaaaa-0000-0000-0000-000000000004" ->
          // 丁供应商：这条到货**已作废** ⇒ 不计入任何指标。
          deliveries.add(delivery("D5", "2026-09-30T00:00:00Z", "2026-10-08", "2026-10-06", true));
      case "aaaaaaaa-0000-0000-0000-000000000005" ->
          // 戊供应商：下单时刻**解析不了** ⇒ 这一行必须被跳过（而不是当成 0 天）。
          deliveries.add(delivery("D6", "not-a-timestamp", "2026-10-10", "2026-10-09", false));
      default -> {
        // 己 / 庚供应商：没有到货记录 ⇒ 采购周期不可比（**不是 0 天**）。
      }
    }
    view.put("deliveries", deliveries);
    return view;
  }

  static Map<String, Object> vendor(
      UUID roleId, String code, String name, String cooperation, String supplyStatus, String country) {
    var row = new LinkedHashMap<String, Object>();
    row.put("roleId", roleId.toString());
    row.put("partyId", roleId.toString());
    row.put("partyCode", code);
    row.put("partyName", name);
    row.put("partyType", "SUPPLIER");
    row.put("countryCode", country);
    row.put("partyStatus", "ACTIVE");
    row.put("cooperationLevel", cooperation);
    row.put("supplyStatus", supplyStatus);
    return row;
  }

  static Map<String, Object> delivery(
      String id, String orderedAt, String dueDate, String receivedDate, boolean isVoid) {
    var row = new LinkedHashMap<String, Object>();
    row.put("id", fakeUuid(id));
    row.put("deliveryRef", "DN-" + id);
    row.put("skuCode", "SKU-1");
    row.put("skuName", "SKU-1 名称");
    row.put("quantity", "100");
    row.put("amount", "1000");
    row.put("currency", "USD");
    row.put("orderedAt", orderedAt);
    row.put("dueDate", dueDate);
    row.put("receivedDate", receivedDate);
    row.put("onTime", !isVoid);
    row.put("daysLate", 0);
    row.put("isVoid", isVoid);
    return row;
  }

  /** 由夹具键派生的稳定 UUID（上游 id 必须是 UUID，否则反序列化会炸）。 */
  static String fakeUuid(String key) {
    return UUID.nameUUIDFromBytes(("procurement-analysis:" + key).getBytes(java.nio.charset.StandardCharsets.UTF_8))
        .toString();
  }

  static Map<String, Object> page(String key, List<Map<String, Object>> items) {
    var body = new LinkedHashMap<String, Object>();
    body.put(key, items);
    body.put("total", items.size());
    body.put("page", 0);
    body.put("size", 200);
    return body;
  }

  static void respond(com.sun.net.httpserver.HttpExchange e, Object body) {
    try {
      byte[] bytes = JSON.writeValueAsBytes(body);
      e.getResponseHeaders().add("Content-Type", "application/json");
      e.sendResponseHeaders(200, bytes.length);
      e.getResponseBody().write(bytes);
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    } finally {
      e.close();
    }
  }

  // ==========================================================================
  // 工具
  // ==========================================================================

  /** 跑一次投影摄取（窗口覆盖当前期 + 上一等长期间）。 */
  void project() {
    refresh.refresh(
        a,
        "Bearer tenant-a",
        LocalDate.of(2026, 8, 31),
        TO,
        java.time.ZoneId.of("UTC"));
  }

  /** 播种销售额事实（13.1.1.1 的 SALES_AMOUNT 投影）。 */
  void seedSales(LocalDate date, String currency, String amount) {
    // ⚠️ `reporting_metric_fact` 有触发器：指标码必须**先在口径表里登记**才能落事实。
    //   这里走真实播种路径（13.1.1.1 的 ensureMetricDefinitions），不手写口径行。
    projection.ensureMetricDefinitions(a);
    tenants.run(
        a,
        () -> {
          sql.execute(
              """
              insert into reporting_metric_fact
                (id,tenant_id,scope_type,legal_entity_id,shop_id,metric_code,bucket_date,currency,
                 value,timezone,source_service,source_reference,updated_at)
              values (?,?,'COMPANY',null,null,'SALES_AMOUNT',?,?,?,'UTC','trade','ORDER-' || ?,now())
              """,
              UUID.randomUUID(),
              a,
              date,
              currency,
              new BigDecimal(amount),
              date.toString());
          return null;
        });
  }

  // ==========================================================================
  // ① 采购金额趋势与占比
  // ==========================================================================

  @Test
  void 趋势_逐币种出点且跨币种无合计() {
    project();
    var trend = procurementAnalysis.trend(a, FROM, TO, "DAY", "ALL", null);

    assertThat(trend.currencies()).containsExactlyInAnyOrder("USD", "EUR");
    // 🔴 **两个币种 ⇒ 合计不存在**（不是 0，也不是跨币种相加）。
    assertThat(trend.totalAmount()).as("跨币种的合计不存在").isNull();
    assertThat(trend.totalCurrency()).isNull();

    var usd = trend.totals().stream().filter(t -> "USD".equals(t.currency())).findFirst().orElseThrow();
    assertThat(usd.amount()).isEqualByComparingTo("4300");
    assertThat(usd.factCount()).isEqualTo(5);
    var eur = trend.totals().stream().filter(t -> "EUR".equals(t.currency())).findFirst().orElseThrow();
    assertThat(eur.amount()).isEqualByComparingTo("1500");
    assertThat(eur.factCount()).isEqualTo(4);

    // 逐日出点：同一天的两个币种是**两行**，不是一行。
    assertThat(trend.points()).hasSize(7);
    var day1004 = trend.points().stream()
        .filter(p -> "2026-10-04".equals(p.period()) && "EUR".equals(p.currency()))
        .findFirst()
        .orElseThrow();
    assertThat(day1004.amount()).as("同一天的 EUR 三条流水合并成一个点").isEqualByComparingTo("1000");
    assertThat(day1004.factCount()).isEqualTo(3);
  }

  @Test
  void 趋势_没有金额的流水不进合计且被单独计数() {
    project();
    var trend = procurementAnalysis.trend(a, FROM, TO, "DAY", "ALL", null);

    // 丙供应商那一条（10 元数量、**没有金额**）不在任何合计里。
    var usd = trend.totals().stream().filter(t -> "USD".equals(t.currency())).findFirst().orElseThrow();
    assertThat(usd.amount()).isEqualByComparingTo("4300");
    assertThat(usd.factCount()).as("没有金额的那条不进合计的行数").isEqualTo(5);
    assertThat(trend.warnings()).anyMatch(w -> w.contains("没有金额 / 币种"));
  }

  @Test
  void 趋势_按维度拆解时占比分母是同币种合计() {
    project();
    var trend = procurementAnalysis.trend(a, FROM, TO, "DAY", "SUPPLIER", null);

    assertThat(trend.shares()).isNotEmpty();
    var usdShares = trend.shares().stream().filter(s -> "USD".equals(s.currency())).toList();
    assertThat(usdShares).hasSize(2);
    var top = usdShares.stream()
        .filter(s -> "甲供应商".equals(s.dimensionLabel()))
        .findFirst()
        .orElseThrow();
    // 3200 / 4300 = 74.4186%
    assertThat(top.amount()).isEqualByComparingTo("3200");
    assertThat(top.share()).isEqualByComparingTo("74.4186");
    var eurShares = trend.shares().stream().filter(s -> "EUR".equals(s.currency())).toList();
    assertThat(eurShares).hasSize(4);
  }

  @Test
  void 趋势_按周与按月分桶() {
    project();
    var week = procurementAnalysis.trend(a, FROM, TO, "WEEK", "ALL", null);
    assertThat(week.points()).isNotEmpty();
    var month = procurementAnalysis.trend(a, FROM, TO, "MONTH", "ALL", null);
    // ⚠️ 趋势点是 **（期间 × 币种）** 一行 ⇒ 同一个月份有两个币种就是**两行**。
    assertThat(month.points()).hasSize(2);
    assertThat(month.points()).allMatch(p -> "2026-10".equals(p.period()));
    assertThat(month.points().stream()
            .filter(p -> "USD".equals(p.currency()))
            .findFirst()
            .orElseThrow()
            .amount())
        .isEqualByComparingTo("4300");
  }

  // ==========================================================================
  // ② 供应商集中度（**风险信号**）
  // ==========================================================================

  @Test
  void 集中度_单一供应商占比过高给出高风险信号() {
    project();
    var concentration = procurementAnalysis.concentration(a, FROM, TO, null);

    assertThat(concentration.buckets()).hasSize(2);
    var usd = concentration.buckets().stream()
        .filter(b -> "USD".equals(b.currency()))
        .findFirst()
        .orElseThrow();
    assertThat(usd.totalAmount()).isEqualByComparingTo("4300");
    assertThat(usd.supplierCount()).isEqualTo(2);
    assertThat(usd.suppliers().get(0).supplierName()).isEqualTo("甲供应商");
    assertThat(usd.suppliers().get(0).share()).isEqualByComparingTo("74.4186");
    assertThat(usd.suppliers().get(0).cumulativeShare()).isEqualByComparingTo("74.4186");
    assertThat(usd.suppliers().get(1).cumulativeShare()).isEqualByComparingTo("100");

    // 🔴 **高风险 + 风险说明写明是哪一家、占比多少、断供会怎样**
    assertThat(usd.currencyRiskLevel()).isEqualTo("HIGH");
    assertThat(usd.currencyRiskNote()).contains("甲供应商").contains("74.4186").contains("断供");
    assertThat(concentration.riskLevel()).isEqualTo("HIGH");
    assertThat(concentration.topSupplierName()).isEqualTo("甲供应商");
    assertThat(concentration.topSupplierShare()).isEqualByComparingTo("74.4186");
    assertThat(concentration.riskNote()).contains("甲供应商");
  }

  @Test
  void 集中度_没有单点依赖但前N家偏集中给出中风险() {
    project();
    var concentration = procurementAnalysis.concentration(a, FROM, TO, null);

    var eur = concentration.buckets().stream()
        .filter(b -> "EUR".equals(b.currency()))
        .findFirst()
        .orElseThrow();
    assertThat(eur.supplierCount()).isEqualTo(4);
    assertThat(eur.top1Share()).as("最大供应商只有 33.33%").isEqualByComparingTo("33.3333");
    assertThat(eur.topNShare()).as("前 3 家合计 83.33%").isEqualByComparingTo("83.3333");
    assertThat(eur.currencyRiskLevel()).isEqualTo("MEDIUM");
    assertThat(eur.currencyRiskNote()).contains("中等风险");
    // HHI 作为参考指数报出（0~10000）
    assertThat(eur.hhi()).isEqualByComparingTo("2644.4444");
    assertThat(eur.hhiScale()).contains("0~10000");
  }

  @Test
  void 集中度_风险线由租户自己的规则决定() {
    project();
    // 收紧风险线 ⇒ 原来的高风险不再触发。
    procurementAnalysis.updateRule(
        a,
        "CONCENTRATION_RISK",
        new ReportingProcurementAnalysisModels.ProcurementAnalysisRuleInput(
            3, new BigDecimal("95"), new BigDecimal("90"), null, null, null, null));
    var concentration = procurementAnalysis.concentration(a, FROM, TO, null);

    var usd = concentration.buckets().stream()
        .filter(b -> "USD".equals(b.currency()))
        .findFirst()
        .orElseThrow();
    assertThat(usd.currencyRiskLevel()).as("单一供应商 74.42% < 90% ⇒ 不再是高风险").isEqualTo("MEDIUM");
    assertThat(concentration.singleSupplierShareThreshold()).isEqualByComparingTo("90");
    assertThat(concentration.topShareThreshold()).isEqualByComparingTo("95");

    var eur = concentration.buckets().stream()
        .filter(b -> "EUR".equals(b.currency()))
        .findFirst()
        .orElseThrow();
    assertThat(eur.currencyRiskLevel()).as("前 3 家 83.33% < 95% ⇒ 低风险").isEqualTo("LOW");
  }

  @Test
  void 集中度_窗口里没有金额时不可比而不是零() {
    var concentration = procurementAnalysis.concentration(a, FROM, TO, null);
    assertThat(concentration.buckets()).isEmpty();
    assertThat(concentration.riskNote()).contains("算不出来");
  }

  // ==========================================================================
  // ③ 价格波动
  // ==========================================================================

  @Test
  void 价格波动_加权均价且上一期间不可比时为null() {
    project();
    var variance = procurementAnalysis.priceVariance(a, FROM, TO, "SKU", null);

    assertThat(variance.previousFrom()).isEqualTo(LocalDate.of(2026, 8, 31));
    assertThat(variance.previousTo()).isEqualTo(LocalDate.of(2026, 9, 30));

    var sku1 = variance.rows().stream()
        .filter(r -> "SKU-1".equals(r.dimensionKey()) && "USD".equals(r.currency()))
        .findFirst()
        .orElseThrow();
    // 当前 2200 / 200 = 11.0000；上一期 800 / 100 = 8.0000 ⇒ +37.5%
    assertThat(sku1.currentAveragePrice()).isEqualByComparingTo("11");
    assertThat(sku1.previousAveragePrice()).isEqualByComparingTo("8");
    assertThat(sku1.changeRate()).isEqualByComparingTo("37.5");
    assertThat(sku1.status()).isEqualTo("COMPARABLE");

    // 🔴 **上一期间没有数量 ⇒ 环比不可比**（不是 0%）。
    var sku3 = variance.rows().stream()
        .filter(r -> "SKU-3".equals(r.dimensionKey()))
        .findFirst()
        .orElseThrow();
    assertThat(sku3.changeRate()).isNull();
    assertThat(sku3.status()).isEqualTo("PREVIOUS_MISSING");
    assertThat(sku3.note()).contains("算不出来");
  }

  @Test
  void 价格波动_跨币种分列不混算() {
    project();
    var variance = procurementAnalysis.priceVariance(a, FROM, TO, "SKU", null);

    // SKU-2 同时出现在 USD 与 EUR 两个桶里 —— 均价**不可跨币种混算**。
    var sku2Rows = variance.rows().stream()
        .filter(r -> "SKU-2".equals(r.dimensionKey()))
        .toList();
    assertThat(sku2Rows).hasSize(2);
    var usd = sku2Rows.stream().filter(r -> "USD".equals(r.currency())).findFirst().orElseThrow();
    var eur = sku2Rows.stream().filter(r -> "EUR".equals(r.currency())).findFirst().orElseThrow();
    // USD：2100 / 105 = 20.0000；上一期 900 / 50 = 18.0000 ⇒ +11.1111%
    assertThat(usd.currentAveragePrice()).isEqualByComparingTo("20");
    assertThat(usd.changeRate()).isEqualByComparingTo("11.1111");
    // EUR：350 / 14 = 25.0000；上一期间没有 ⇒ 不可比
    assertThat(eur.currentAveragePrice()).isEqualByComparingTo("25");
    assertThat(eur.changeRate()).isNull();
  }

  // ==========================================================================
  // ④ 到货准时率
  // ==========================================================================

  @Test
  void 准时率_分母是已到货流水且待到货不进分母() {
    project();
    var onTime = procurementAnalysis.onTime(a, FROM, TO);

    assertThat(onTime.denominatorCount()).as("PENDING 不进分母").isEqualTo(9);
    assertThat(onTime.onTimeCount()).isEqualTo(8);
    assertThat(onTime.lateCount()).isEqualTo(1);
    assertThat(onTime.pendingCount()).isEqualTo(1);
    assertThat(onTime.onTimeRate()).isEqualByComparingTo("88.8889");
    // 默认目标 90% ⇒ 未达标
    assertThat(onTime.meetsTarget()).isFalse();
    assertThat(onTime.targetRate()).isEqualByComparingTo("90");

    // 逾期待到货清单可下钻
    assertThat(onTime.lateRows()).hasSize(1);
    assertThat(onTime.lateRows().get(0).supplierName()).isEqualTo("甲供应商");
    assertThat(onTime.lateRows().get(0).lateDays()).isEqualTo(1);

    // 逐供应商：甲供应商 2/3
    var jia = onTime.suppliers().stream()
        .filter(s -> "甲供应商".equals(s.supplierName()))
        .findFirst()
        .orElseThrow();
    assertThat(jia.denominatorCount()).isEqualTo(3);
    assertThat(jia.onTimeRate()).isEqualByComparingTo("66.6667");
  }

  @Test
  void 准时率_窗口里没有已到货流水时不可比而不是零() {
    var onTime = procurementAnalysis.onTime(a, FROM, TO);
    assertThat(onTime.denominatorCount()).isZero();
    assertThat(onTime.onTimeRate()).as("分母为 0 ⇒ 不可比，不是 0%").isNull();
    assertThat(onTime.meetsTarget()).isNull();
    assertThat(onTime.warnings()).anyMatch(w -> w.contains("不可比"));
  }

  // ==========================================================================
  // ⑤ 采购周期
  // ==========================================================================

  @Test
  void 采购周期_到货日减下单日且作废与解析不了的记录不计入() {
    project();
    var cycle = procurementAnalysis.purchaseCycle(a, FROM, TO);

    assertThat(cycle.sampleCount()).as("丁供应商那条已作废、戊供应商那条解析不了").isEqualTo(4);
    assertThat(cycle.averageCycleDays()).isEqualByComparingTo("8.5");
    assertThat(cycle.medianCycleDays()).isEqualByComparingTo("8");
    assertThat(cycle.p90CycleDays()).isEqualByComparingTo("11");
    assertThat(cycle.meetsTarget()).isTrue();
    assertThat(cycle.targetDays()).isEqualByComparingTo("30");

    var jia = cycle.suppliers().stream()
        .filter(s -> "甲供应商".equals(s.supplierName()))
        .findFirst()
        .orElseThrow();
    assertThat(jia.sampleCount()).isEqualTo(2);
    assertThat(jia.averageCycleDays()).isEqualByComparingTo("9");
    assertThat(jia.latestCycleDays()).isEqualByComparingTo("7");

    // 丁供应商只有一条**已作废**的到货 ⇒ 周期不可比（**不是 0 天**）。
    var ding = cycle.suppliers().stream()
        .filter(s -> "丁供应商".equals(s.supplierName()))
        .findFirst();
    assertThat(ding).as("已作废的到货记录不计入任何指标").isEmpty();
  }

  @Test
  void 采购周期_窗口里没有到货记录时不可比而不是零天() {
    var cycle = procurementAnalysis.purchaseCycle(a, FROM, TO);
    assertThat(cycle.sampleCount()).isZero();
    assertThat(cycle.averageCycleDays()).as("没有到货记录 ⇒ 不可比，不是 0 天").isNull();
    assertThat(cycle.warnings()).anyMatch(w -> w.contains("不可比"));
  }

  @Test
  void 采购周期_下单时刻解析不了的记录被跳过而不是当成零天() {
    project();
    // 戊供应商那条下单时刻是 "not-a-timestamp" ⇒ 整条被跳过（不留一行周期为 0 的记录）。
    var wu = tenants.run(
        a,
        () -> sql.fetchOne(
                "select count(*) from reporting_procurement_analysis_delivery_fact"
                    + " where tenant_id = ? and supplier_id = ?",
                a,
                SUP_E)
            .get(0, Long.class));
    assertThat(wu).as("解析不了的记录不落库").isZero();
  }

  // ==========================================================================
  // ⑥ 采购成本占销售额比
  // ==========================================================================

  @Test
  void 成本占销售额比_逐币种算且分母取已投影的销售额() {
    project();
    seedSales(LocalDate.of(2026, 10, 1), "USD", "10000");
    seedSales(LocalDate.of(2026, 10, 2), "USD", "10000");
    seedSales(LocalDate.of(2026, 10, 5), "USD", "5000");
    seedSales(LocalDate.of(2026, 10, 3), "EUR", "8000");

    var cts = procurementAnalysis.costToSales(a, FROM, TO);
    assertThat(cts.available()).isTrue();

    var usd = cts.rows().stream().filter(r -> "USD".equals(r.currency())).findFirst().orElseThrow();
    // 4300 / 25000 = 17.2%
    assertThat(usd.purchaseAmount()).isEqualByComparingTo("4300");
    assertThat(usd.salesAmount()).isEqualByComparingTo("25000");
    assertThat(usd.ratio()).isEqualByComparingTo("17.2000");
    assertThat(usd.purchaseFactCount()).isEqualTo(5);
    assertThat(usd.salesFactCount()).isEqualTo(3);

    var eur = cts.rows().stream().filter(r -> "EUR".equals(r.currency())).findFirst().orElseThrow();
    assertThat(eur.ratio()).as("1500 / 8000 = 18.75%").isEqualByComparingTo("18.7500");
  }

  @Test
  void 成本占销售额比_销售额投影缺失时不可比而不是零() {
    project();
    var cts = procurementAnalysis.costToSales(a, FROM, TO);
    assertThat(cts.available()).isFalse();
    assertThat(cts.ownerModule()).contains("13.1.1.1");
    assertThat(cts.impact()).isNotBlank();
    var usd = cts.rows().stream().filter(r -> "USD".equals(r.currency())).findFirst().orElseThrow();
    assertThat(usd.ratio()).as("分母不可得 ⇒ 不可比，不是 0%").isNull();
    assertThat(usd.note()).contains("不可比");
  }

  // ==========================================================================
  // ⑦ 供应商对比
  // ==========================================================================

  @Test
  void 供应商对比_采购贡献准时率周期评级四件齐备() {
    project();
    var comparison = procurementAnalysis.suppliers(a, FROM, TO, null, 0, 20);

    assertThat(comparison.total()).isEqualTo(7);
    assertThat(comparison.rows()).hasSize(7);

    var jia = comparison.rows().stream()
        .filter(r -> "甲供应商".equals(r.supplierName()))
        .findFirst()
        .orElseThrow();
    assertThat(jia.purchaseAmount()).isEqualByComparingTo("3200");
    assertThat(jia.purchaseCurrency()).isEqualTo("USD");
    assertThat(jia.purchaseShare()).isEqualByComparingTo("74.4186");
    assertThat(jia.onTimeRate()).isEqualByComparingTo("66.6667");
    assertThat(jia.averageCycleDays()).isEqualByComparingTo("9");
    assertThat(jia.ratingScore()).isEqualByComparingTo("82.5");
    assertThat(jia.ratingGrade()).isEqualTo("B");
    assertThat(jia.cooperationLevel()).isEqualTo("STRATEGIC");
  }

  @Test
  void 供应商对比_未评级不是零分且没有金额不是零元() {
    project();
    var comparison = procurementAnalysis.suppliers(a, FROM, TO, null, 0, 20);

    var bing = comparison.rows().stream()
        .filter(r -> "丙供应商".equals(r.supplierName()))
        .findFirst()
        .orElseThrow();
    assertThat(bing.ratingScore()).as("没有评级 ⇒ null，不是 0 分").isNull();
    assertThat(bing.note()).contains("未评级");
    assertThat(bing.purchaseAmount()).as("没有带金额的流水 ⇒ null，不是 0 元").isNull();
    assertThat(bing.note()).contains("带金额");
  }

  @Test
  void 供应商对比_评级只取最新一期() {
    project();
    var comparison = procurementAnalysis.suppliers(a, FROM, TO, null, 0, 20);
    var wu = comparison.rows().stream()
        .filter(r -> "戊供应商".equals(r.supplierName()))
        .findFirst()
        .orElseThrow();
    assertThat(wu.ratingScore()).as("6 月那一期不该覆盖 9 月那一期").isEqualByComparingTo("88");
    assertThat(wu.ratingGrade()).isEqualTo("B");
    assertThat(wu.ratingPeriodStart()).isEqualTo(LocalDate.of(2026, 9, 1));
  }

  @Test
  void 供应商对比_真分页() {
    project();
    var page1 = procurementAnalysis.suppliers(a, FROM, TO, null, 0, 3);
    var page2 = procurementAnalysis.suppliers(a, FROM, TO, null, 1, 3);
    assertThat(page1.total()).isEqualTo(7);
    assertThat(page1.rows()).hasSize(3);
    assertThat(page2.rows()).hasSize(3);
    assertThat(page1.rows().get(0).supplierName()).isEqualTo("甲供应商");
    // 排序 = 采购金额降序：A 3200、D 1100、B 500 | E 400、F 350、G 250 | C（无金额，垫底）
    assertThat(page2.rows().get(0).supplierName()).isEqualTo("戊供应商");
    assertThat(page2.rows().get(2).supplierName()).isEqualTo("庚供应商");
  }

  @Test
  void 供应商对比_按币种筛选后金额与占比只算该币种() {
    project();
    var usd = procurementAnalysis.suppliers(a, FROM, TO, "USD", 0, 20);
    assertThat(usd.rows()).allMatch(r -> "USD".equals(r.purchaseCurrency()));
    var jia = usd.rows().stream()
        .filter(r -> "甲供应商".equals(r.supplierName()))
        .findFirst()
        .orElseThrow();
    assertThat(jia.purchaseShare()).isEqualByComparingTo("74.4186");
  }

  // ==========================================================================
  // 下钻 + 幂等 + 能力声明
  // ==========================================================================

  @Test
  void 下钻_真分页真计数且带准时判定() {
    project();
    var drilldown = procurementAnalysis.drilldown(a, FROM, TO, null, 0, 50);
    assertThat(drilldown.total()).isEqualTo(10);
    assertThat(drilldown.rows()).hasSize(10);
    var late = drilldown.rows().stream()
        .filter(r -> "LATE".equals(r.onTimeStatus()))
        .findFirst()
        .orElseThrow();
    assertThat(late.supplierName()).isEqualTo("甲供应商");
    assertThat(late.amount()).isEqualByComparingTo("1000");
    assertThat(late.note()).contains("逾期");
  }

  @Test
  void 幂等_同一批上游事实重放不翻倍() {
    project();
    var first = purchaseFactCount();
    project();
    var second = purchaseFactCount();
    assertThat(second).isEqualTo(first);
    assertThat(first).isEqualTo(12);

    // 供应商档案也是幂等的（评级更新覆盖同一行）。
    assertThat(supplierFactCount()).isEqualTo(7);
  }

  @Test
  void 能力声明_逐条报出接得通与接不通() {
    project();
    var capabilities = procurementAnalysis.capabilities(a);
    assertThat(capabilities.sections()).hasSize(8);
    var keys = capabilities.sections().stream()
        .map(ReportingProcurementAnalysisModels.ProcurementAnalysisCapabilitySection::key)
        .toList();
    assertThat(keys).contains(
        "PURCHASE_AMOUNT_TREND",
        "SUPPLIER_CONCENTRATION",
        "PRICE_VARIANCE",
        "ON_TIME_RATE",
        "PURCHASE_CYCLE",
        "COST_TO_SALES",
        "SUPPLIER_COMPARISON",
        "SUPPLIER_RATING");

    // 没有销售额投影 ⇒ 成本占比这一条必须**报出不可得 + 归属模块号 + 原因**。
    var cts = capabilities.sections().stream()
        .filter(s -> "COST_TO_SALES".equals(s.key()))
        .findFirst()
        .orElseThrow();
    assertThat(cts.available()).isFalse();
    assertThat(cts.ownerModule()).isNotBlank();
    assertThat(cts.reason()).isNotBlank();
    assertThat(cts.impact()).isNotBlank();
  }

  @Test
  void 能力声明_有到货记录时采购周期接得通() {
    project();
    var capabilities = procurementAnalysis.capabilities(a);
    var cycle = capabilities.sections().stream()
        .filter(s -> "PURCHASE_CYCLE".equals(s.key()))
        .findFirst()
        .orElseThrow();
    assertThat(cycle.available()).isTrue();
    assertThat(cycle.ownerModule()).isNull();
  }

  // ==========================================================================
  // HTTP 层
  // ==========================================================================

  @Test
  void http_端点返回真实数据且租户不来自请求头() throws Exception {
    project();
    http.perform(
            get("/reporting/procurement-analysis/concentration")
                .param("from", FROM.toString())
                .param("to", TO.toString())
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.riskLevel").value("HIGH"))
        .andExpect(jsonPath("$.buckets.length()").value(2));

    http.perform(
            get("/reporting/procurement-analysis/capabilities")
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sections.length()").value(8));
  }

  @Test
  void http_非法参数被拒() throws Exception {
    http.perform(
            get("/reporting/procurement-analysis/trend")
                .param("granularity", "HOUR")
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isBadRequest());
    http.perform(
            get("/reporting/procurement-analysis/trend")
                .param("currency", "US")
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isBadRequest());
    http.perform(
            get("/reporting/procurement-analysis/rules")
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk());
  }

  @Test
  void http_阈值规则可改且只改自己的表() throws Exception {
    http.perform(
            put("/reporting/procurement-analysis/rules/ON_TIME_TARGET")
                .header("Authorization", "Bearer tenant-a")
                .contentType("application/json")
                .content("{\"onTimeTargetPct\":95,\"minHistoryDays\":7}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.onTimeTargetPct").value(95))
        .andExpect(jsonPath("$.minHistoryDays").value(7));

    var rules = procurementAnalysis.rules(a);
    assertThat(rules).hasSize(3);
    assertThat(rules.stream()
            .filter(r -> "ON_TIME_TARGET".equals(r.ruleType()))
            .findFirst()
            .orElseThrow()
            .onTimeTargetPct())
        .isEqualByComparingTo("95");
  }

  // ==========================================================================
  // 计数工具
  // ==========================================================================

  int purchaseFactCount() {
    return tenants.run(
        a,
        () -> sql.fetch(
                "select id from reporting_procurement_analysis_purchase_fact where tenant_id = ?", a)
            .size());
  }

  int supplierFactCount() {
    return tenants.run(
        a,
        () -> sql.fetch(
                "select id from reporting_procurement_analysis_supplier_fact where tenant_id = ?", a)
            .size());
  }
}
