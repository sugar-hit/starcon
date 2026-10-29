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
 * 13.5.3.1 物流分析的**集成测试**（真实 PostgreSQL 16 + FORCE RLS，无内存替身）。
 *
 * <p>跑的是本模块的**业务口径**：
 *
 * <ol>
 *   <li>🔴 **单均运费的分母是订单数**（不是运单票数）—— 一单多票时两者不同，
 *       页面上必须能看出这个差别；</li>
 *   <li>🔴 **多币种绝不相加**：每一行带自己的币种；</li>
 *   <li>🔴 **头程分摊后成本读上游的分摊结果**（不重算分摊），
 *       并与上游成本总览的头程分摊位**并排对照**；</li>
 *   <li>🔴 **渠道对比**逐币种，占比分母是同币种合计；</li>
 *   <li>🔴 **时效达成率**读上游判定，分母是「可比数」；分母为 0 ⇒ 不可比（不是 0%）；</li>
 *   <li>🔴 **各国成本对比**逐币种，运费未登记的票不进金额合计；</li>
 *   <li>🔴 **异常件占比**的分子分母**同范围**（窗口内异常件 ÷ 窗口内运单）；</li>
 *   <li>🔴 **类目维度的物流成本占比**（下游 5 定价）：分子 = 头程分摊后成本，
 *       分母 = 13.4.1.2 的销售额投影；</li>
 *   <li>🔴 **时间字段写回的值必须逐字相等**（timestamptz 绑定不能偏 −28800s）；</li>
 *   <li>同一批上游事实重放被幂等跳过。</li>
 * </ol>
 *
 * <p>租户隔离的三条断言在 {@code LogisticsAnalysisTenancyIntegrationTest}（同一批跑）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class LogisticsAnalysisIntegrationTest {

  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  static HttpServer upstream;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  static final List<String> permissions =
      List.of(
          "reporting.logistics-analysis.read",
          "reporting.logistics-analysis.write",
          "reporting.projection.read",
          "reporting.projection.write");

  static final ObjectMapper JSON = JsonMapper.builder().addModule(new JavaTimeModule()).build();

  /** 当前窗口起。 */
  static final LocalDate FROM = LocalDate.of(2026, 10, 1);

  /** 当前窗口止。 */
  static final LocalDate TO = LocalDate.of(2026, 10, 31);

  // ===== 分摊单 =====
  static final UUID ALLOC_1 = UUID.fromString("cccccccc-0000-0000-0000-000000000001");
  static final UUID ALLOC_2 = UUID.fromString("cccccccc-0000-0000-0000-000000000002");

  // ===== SKU =====
  static final UUID SKU_1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
  static final UUID SKU_2 = UUID.fromString("22222222-2222-2222-2222-222222222222");
  static final UUID SKU_3 = UUID.fromString("33333333-3333-3333-3333-333333333333");

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
      upstream.createContext("/channel/logistics-cost-analysis/overview", e -> respond(e, costOverview()));
      upstream.createContext("/channel/logistics-cost-analysis/by-channel", e -> respond(e, costByChannel()));
      upstream.createContext("/channel/logistics-cost-analysis/allocations", e -> respond(e, allocations(e)));
      upstream.createContext("/channel/logistics-timeliness/reports/matrix", e -> respond(e, timelinessMatrix()));
      upstream.createContext("/channel/logistics-tracking/exceptions", e -> respond(e, exceptions()));
      upstream.createContext("/trade/logistics/waybills/list", e -> respond(e, waybills()));
      upstream.createContext("/catalog/skus", e -> respond(e, skus()));
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
    r.add("CHANNEL_URL", () -> "http://localhost:" + upstream.getAddress().getPort());
    r.add("TRADE_URL", () -> "http://localhost:" + upstream.getAddress().getPort());
    r.add("CATALOG_URL", () -> "http://localhost:" + upstream.getAddress().getPort());
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  @Autowired TenantTransactions tenants;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;
  @Autowired ReportingLogisticsAnalysisService logisticsAnalysis;
  @Autowired ReportingLogisticsAnalysisRefreshService refresh;

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
          "truncate reporting_logistics_analysis_cost_fact,"
              + " reporting_logistics_analysis_channel_fact,"
              + " reporting_logistics_analysis_timeliness_fact,"
              + " reporting_logistics_analysis_waybill_fact,"
              + " reporting_logistics_analysis_allocation_fact,"
              + " reporting_logistics_analysis_allocation_line_fact,"
              + " reporting_logistics_analysis_exception_fact,"
              + " reporting_logistics_analysis_sku_category_fact,"
              + " reporting_logistics_analysis_rule,"
              + " reporting_profit_fact cascade");
    }
  }

  // ==========================================================================
  // 夹具：假上游
  // ==========================================================================

  /**
   * channel 10.6.1.1 的成本总览。
   *
   * <p>⚠️ **刻意设计的口径陷阱**：
   *
   * <ul>
   *   <li>USD 的上游「每票运费」= 45 ÷ 4 票 = 11.25；</li>
   *   <li>USD 的**单均运费** = 45 ÷ **3 个订单** = 15（一单多票 ⇒ 两者不同）；</li>
   *   <li>EUR 缺头程分摊 / 关税 / 包材 ⇒ subtotal 只是「已计入分量之和」，缺项**不是 0**。</li>
   * </ul>
   */
  static Map<String, Object> costOverview() {
    var body = new LinkedHashMap<String, Object>();
    body.put("computedAt", "2026-10-31T00:00:00Z");
    body.put("windowFrom", "2026-10-01T00:00:00Z");
    body.put("windowTo", "2026-11-01T00:00:00Z");
    body.put(
        "currencyRows",
        List.of(
            currencyCost(
                "USD", "45", "260", "20", "5", "330",
                List.of("LAST_MILE_FREIGHT", "FIRST_MILE_ALLOCATION", "DUTY_AND_TAX", "PACKAGING"),
                List.of()),
            currencyCost(
                "EUR", "30", null, null, null, "30",
                List.of("LAST_MILE_FREIGHT"),
                List.of("FIRST_MILE_ALLOCATION", "DUTY_AND_TAX", "PACKAGING"))));
    body.put(
        "unitMetrics",
        List.of(
            unitMetric("USD", "45", 4, 1, "7.3", "11.25", "6.1644"),
            unitMetric("EUR", "30", 1, 0, "3", "30", "10")));
    var coverage = new LinkedHashMap<String, Object>();
    coverage.put("complete", false);
    coverage.put(
        "missingComponents",
        List.of("FIRST_MILE_ALLOCATION", "DUTY_AND_TAX", "PACKAGING"));
    coverage.put("note", "缺项不等于 0。");
    body.put("coverage", coverage);
    body.put("scannedWaybills", 6);
    body.put("truncated", false);
    body.put("notes", List.of("尾程按运单签发时刻归集。"));
    return body;
  }

  static Map<String, Object> currencyCost(
      String currency,
      String lastMile,
      String firstMile,
      String duty,
      String packaging,
      String subtotal,
      List<String> included,
      List<String> missing) {
    var row = new LinkedHashMap<String, Object>();
    row.put("currency", currency);
    if (lastMile != null) row.put("lastMileFreight", lastMile);
    if (firstMile != null) row.put("firstMileAllocation", firstMile);
    if (duty != null) row.put("dutyAndTax", duty);
    if (packaging != null) row.put("packaging", packaging);
    row.put("subtotal", subtotal);
    row.put("includedComponents", included);
    row.put("missingComponents", missing);
    return row;
  }

  static Map<String, Object> unitMetric(
      String currency,
      String amount,
      long registered,
      long notRegistered,
      String weight,
      String perOrder,
      String perKg) {
    var row = new LinkedHashMap<String, Object>();
    row.put("currency", currency);
    row.put("totalFreightAmount", amount);
    row.put("registeredWaybills", registered);
    row.put("notRegisteredWaybills", notRegistered);
    row.put("totalWeightKg", weight);
    row.put("perOrderFreight", perOrder);
    row.put("perKgFreight", perKg);
    return row;
  }

  /** channel 10.6.1.1 的渠道对比。 */
  static Map<String, Object> costByChannel() {
    var body = new LinkedHashMap<String, Object>();
    body.put("computedAt", "2026-10-31T00:00:00Z");
    body.put("windowFrom", "2026-10-01T00:00:00Z");
    body.put("windowTo", "2026-11-01T00:00:00Z");
    body.put(
        "rows",
        List.of(
            channelRow("C1", "PLATFORM_INTEGRATED", "平台集成", 3, 3, 0,
                List.of(channelCurrency("USD", "40", 3, "5.5", "13.3333"))),
            channelRow("C2", "THIRD_PARTY", "三方物流", 1, 1, 0,
                List.of(channelCurrency("EUR", "30", 1, "3", "30"))),
            channelRow("C3", "POSTAL", "邮政", 1, 1, 0,
                List.of(channelCurrency("USD", "5", 1, "0.8", "5")))));
    var summary = new LinkedHashMap<String, Object>();
    summary.put("carrierCount", 3);
    summary.put("waybills", 5);
    summary.put("freightRegistered", 5);
    summary.put("freightNotRegistered", 0);
    body.put("summary", summary);
    body.put("scannedWaybills", 6);
    body.put("truncated", false);
    body.put("notes", List.of());
    return body;
  }

  static Map<String, Object> channelRow(
      String carrier,
      String channel,
      String label,
      long waybills,
      long registered,
      long notRegistered,
      List<Map<String, Object>> byCurrency) {
    var row = new LinkedHashMap<String, Object>();
    row.put("carrierCode", carrier);
    row.put("carrierChannel", channel);
    row.put("carrierChannelLabel", label);
    row.put("waybills", waybills);
    row.put("freightRegistered", registered);
    row.put("freightNotRegistered", notRegistered);
    row.put("byCurrency", byCurrency);
    return row;
  }

  static Map<String, Object> channelCurrency(
      String currency, String amount, long waybills, String weight, String perOrder) {
    var row = new LinkedHashMap<String, Object>();
    row.put("currency", currency);
    row.put("amount", amount);
    row.put("waybills", waybills);
    row.put("totalWeightKg", weight);
    row.put("perOrderFreight", perOrder);
    return row;
  }

  /** channel 10.6.1.1 的分摊单（列表 / 详情两种形状）。 */
  static Map<String, Object> allocations(com.sun.net.httpserver.HttpExchange e) {
    var path = e.getRequestURI().getPath();
    if (!path.endsWith("/allocations")) {
      // 详情：/allocations/{id}
      var id = path.substring(path.lastIndexOf('/') + 1);
      var detail = new LinkedHashMap<String, Object>();
      detail.put("allocation", allocationHeader(id));
      var lines = new ArrayList<Map<String, Object>>();
      if (ALLOC_1.toString().equals(id)) {
        lines.add(allocationLine(1, SKU_1, "SKU-1", "100", "PCS", "0.5", "100"));
        lines.add(allocationLine(2, SKU_2, "SKU-2", "100", "PCS", "0.5", "100"));
      } else {
        lines.add(allocationLine(1, SKU_3, "SKU-3", "60", "PCS", "1", "60"));
      }
      detail.put("lines", lines);
      return detail;
    }
    var body = new LinkedHashMap<String, Object>();
    body.put("items", List.of(allocationHeader(ALLOC_1.toString()), allocationHeader(ALLOC_2.toString())));
    body.put("page", 0);
    body.put("size", 200);
    body.put("totalElements", 2);
    body.put("totalPages", 1);
    body.put("computedCount", 1);
    body.put("lockedCount", 1);
    body.put("supersededCount", 0);
    body.put("allocatedByCurrency", List.of());
    return body;
  }

  static Map<String, Object> allocationHeader(String id) {
    var first = ALLOC_1.toString().equals(id);
    var row = new LinkedHashMap<String, Object>();
    row.put("id", id);
    row.put("allocationCode", first ? "ALLOC-001" : "ALLOC-002");
    row.put("ruleId", "dddddddd-0000-0000-0000-000000000001");
    row.put("ruleCode", "HEAD_MILE_BY_QTY");
    row.put("ruleVersion", 1);
    row.put("allocationBasis", "QUANTITY");
    row.put("allocationBasisLabel", "按数量分摊");
    row.put("sourceShipmentId", "eeeeeeee-0000-0000-0000-000000000001");
    row.put("sourceShipmentCode", "SHIP-001");
    row.put("sourceTransportMode", "AIR");
    row.put("sourceCarrierName", "某某国际");
    row.put("destinationCountryCode", first ? "US" : "DE");
    row.put("currency", "USD");
    row.put("sourceTotalAmount", first ? "200" : "60");
    row.put("allocatedTotalAmount", first ? "200" : "60");
    row.put("basisTotal", first ? "200" : "60");
    row.put("lineCount", first ? 2 : 1);
    row.put("status", first ? "LOCKED" : "COMPUTED");
    row.put("statusLabel", first ? "已结账" : "已计算");
    row.put("computedAt", first ? "2026-10-10T00:00:00Z" : "2026-10-12T00:00:00Z");
    row.put("costAsOf", first ? "2026-10-10T00:00:00Z" : "2026-10-12T00:00:00Z");
    if (first) row.put("lockedAt", "2026-10-11T00:00:00Z");
    row.put("note", "");
    return row;
  }

  static Map<String, Object> allocationLine(
      int lineNo,
      UUID skuId,
      String skuCode,
      String basisValue,
      String basisUnit,
      String ratio,
      String amount) {
    var row = new LinkedHashMap<String, Object>();
    row.put("id", "ffffffff-0000-0000-0000-" + String.format("%012d", lineNo));
    row.put("lineNo", lineNo);
    row.put("skuId", skuId.toString());
    row.put("skuCode", skuCode);
    row.put("skuName", skuCode + " 名称");
    row.put("hsCode", "");
    row.put("basisValue", basisValue);
    row.put("basisUnit", basisUnit);
    row.put("ratio", ratio);
    row.put("allocatedAmount", amount);
    return row;
  }

  /** channel 10.6.2.1 的时效矩阵（渠道 × 国家）。 */
  static Map<String, Object> timelinessMatrix() {
    var body = new LinkedHashMap<String, Object>();
    body.put(
        "rows",
        List.of(
            timelinessCell("CH-1", "美国专线", "US", 10, 8, 8, 6, 2, 1, 1, 0, "75", "80", "25", "120"),
            timelinessCell("CH-2", "欧洲专线", "DE", 4, 4, 4, 4, 0, 0, 0, 0, "100", "100", "0", "96")));
    var totals = new LinkedHashMap<String, Object>();
    totals.put("shippedCount", 14);
    totals.put("deliveredCount", 12);
    totals.put("comparableCount", 12);
    totals.put("onTimeCount", 10);
    totals.put("overdueCount", 2);
    totals.put("noPromiseCount", 1);
    totals.put("pendingCount", 1);
    totals.put("returnedCount", 0);
    totals.put("deliveryRate", "85.7143");
    totals.put("onTimeRate", "83.3333");
    totals.put("overdueRate", "16.6667");
    totals.put("avgTransitHours", "112");
    body.put("page", 0);
    body.put("size", 200);
    body.put("totalElements", 2);
    body.put("totalPages", 1);
    body.put("totals", totals);
    body.put("note", "");
    return body;
  }

  static Map<String, Object> timelinessCell(
      String channelCode,
      String channelName,
      String country,
      int shipped,
      int delivered,
      int comparable,
      int onTime,
      int overdue,
      int noPromise,
      int pending,
      int returned,
      String onTimeRate,
      String deliveryRate,
      String overdueRate,
      String avgTransit) {
    var row = new LinkedHashMap<String, Object>();
    row.put("channelId", "aaaaaaaa-0000-0000-0000-" + String.format("%012d", country.hashCode() & 0xffffff));
    row.put("channelCode", channelCode);
    row.put("channelName", channelName);
    row.put("countryCode", country);
    row.put("shippedCount", shipped);
    row.put("deliveredCount", delivered);
    row.put("comparableCount", comparable);
    row.put("onTimeCount", onTime);
    row.put("overdueCount", overdue);
    row.put("noPromiseCount", noPromise);
    row.put("pendingCount", pending);
    row.put("returnedCount", returned);
    row.put("onTimeRate", onTimeRate);
    row.put("deliveryRate", deliveryRate);
    row.put("overdueRate", overdueRate);
    row.put("avgTransitHours", avgTransit);
    row.put("score", "88");
    row.put("scoreGrade", "B");
    row.put("sampleSufficient", true);
    row.put("minSampleThreshold", 5);
    return row;
  }

  /** channel 10.3.1.2 的异常件。 */
  static Map<String, Object> exceptions() {
    var rows = new ArrayList<Map<String, Object>>();
    // 窗口内两件（E1 / E2）；E3 在窗口外，不进分子。
    rows.add(exceptionCase("E1", "2026-10-05T09:30:00Z", "TRACKING_FLAG", "轨迹异常", "HIGH", "高", "OPEN", "未处理", "WB-1", "C1", "SO-1"));
    rows.add(exceptionCase("E2", "2026-10-07T11:00:00Z", "TRACKING_FLAG", "轨迹异常", "LOW", "低", "RESOLVED", "已解决", "WB-3", "C2", "SO-2"));
    rows.add(exceptionCase("E3", "2026-09-01T11:00:00Z", "CARRIER_DELAY", "承运商延误", "MEDIUM", "中", "OPEN", "未处理", "WB-9", "C1", "SO-9"));
    var body = new LinkedHashMap<String, Object>();
    body.put("rows", rows);
    body.put("page", 0);
    body.put("size", 200);
    body.put("total", rows.size());
    body.put("evaluatedAt", "2026-10-31T00:00:00Z");
    body.put("note", "");
    body.put("responsibilityNote", "");
    return body;
  }

  static Map<String, Object> exceptionCase(
      String key,
      String detectedAt,
      String type,
      String typeLabel,
      String severity,
      String severityLabel,
      String status,
      String statusLabel,
      String waybillNo,
      String carrier,
      String salesOrder) {
    var row = new LinkedHashMap<String, Object>();
    row.put("id", fakeUuid(key));
    row.put("caseNo", "EX-" + key);
    row.put("source", "TRACKING_FLAG");
    row.put("sourceLabel", "轨迹异常标记");
    row.put("exceptionType", type);
    row.put("exceptionTypeLabel", typeLabel);
    row.put("severity", severity);
    row.put("severityLabel", severityLabel);
    row.put("status", status);
    row.put("statusLabel", statusLabel);
    row.put("waybillNo", waybillNo);
    row.put("carrierCode", carrier);
    row.put("salesOrderNo", salesOrder);
    row.put("detectedAt", detectedAt);
    row.put("dueAt", detectedAt);
    row.put("overdue", false);
    row.put("note", "");
    return row;
  }

  /**
   * trade 10.2.1.1 的运单清单。
   *
   * <p>⚠️ **刻意设计的三个口径陷阱**：
   *
   * <ul>
   *   <li><b>SO-1 有两票</b>（一单多票）⇒ 订单数 3 ≠ 票数 4 ⇒ 单均运费 15 ≠ 每票运费 11.25；</li>
   *   <li><b>W4 运费未登记</b> ⇒ 有票数、无金额，SO-3 **不进**订单分母；</li>
   *   <li><b>W5 没有目的国</b> ⇒ 单列「未标注目的国」，不摊进任何国家。</li>
   * </ul>
   */
  static Map<String, Object> waybills() {
    var items = new ArrayList<Map<String, Object>>();
    items.add(waybill("W1", "2026-10-02T08:00:00Z", "SO-1", "C1", "PLATFORM_INTEGRATED", "US", "2.5", "20", "USD"));
    items.add(waybill("W2", "2026-10-03T08:00:00Z", "SO-1", "C1", "PLATFORM_INTEGRATED", "US", "1.5", "12", "USD"));
    items.add(waybill("W3", "2026-10-05T08:00:00Z", "SO-2", "C2", "THIRD_PARTY", "DE", "3", "30", "EUR"));
    items.add(waybill("W4", "2026-10-08T08:00:00Z", "SO-3", "C1", "PLATFORM_INTEGRATED", "US", "1", null, "USD"));
    items.add(waybill("W5", "2026-10-09T08:00:00Z", "SO-4", "C3", "POSTAL", "", "0.8", "5", "USD"));
    items.add(waybill("W6", "2026-10-15T08:00:00Z", "SO-5", "C1", "PLATFORM_INTEGRATED", "US", "2", "8", "USD"));
    // 窗口外（上一期）—— 不进本窗口的任何统计。
    items.add(waybill("P1", "2026-09-10T08:00:00Z", "SO-9", "C1", "PLATFORM_INTEGRATED", "US", "5", "100", "USD"));
    var body = new LinkedHashMap<String, Object>();
    body.put("items", items);
    body.put("page", 0);
    body.put("size", 200);
    body.put("totalElements", items.size());
    body.put("totalPages", 1);
    return body;
  }

  static Map<String, Object> waybill(
      String key,
      String createdAt,
      String salesOrder,
      String carrier,
      String channel,
      String country,
      String weight,
      String freight,
      String currency) {
    var row = new LinkedHashMap<String, Object>();
    row.put("id", fakeUuid(key));
    row.put("requestNo", "REQ-" + key);
    row.put("waybillNo", "WB-" + key);
    row.put("salesOrderNo", salesOrder);
    row.put("outboundOrderNo", "OB-" + key);
    row.put("carrierCode", carrier);
    row.put("carrierChannel", channel);
    row.put("carrierChannelLabel", channel);
    row.put("destinationCountry", country);
    row.put("grossWeightKg", weight);
    if (freight != null) {
      row.put("freightAmount", freight);
      row.put("freightCurrency", currency);
      row.put("freightGapCode", "REGISTERED");
    } else {
      // ⚠️ **币种已知、金额没登记**是真实存在的中间态：它有票数、没有金额。
      row.put("freightCurrency", currency);
      row.put("freightGapCode", "NOT_REGISTERED");
    }
    row.put("createdAt", createdAt);
    return row;
  }

  /** catalog 6.1.1.1 的 SKU 列表（**只取类目归属**）。 */
  static Map<String, Object> skus() {
    var items = new ArrayList<Map<String, Object>>();
    items.add(sku(SKU_1, "SKU-1", "家居收纳", "CAT-HOME"));
    items.add(sku(SKU_2, "SKU-2", "厨房用品", "CAT-KITCHEN"));
    items.add(sku(SKU_3, "SKU-3", "收纳箱", "CAT-HOME"));
    var body = new LinkedHashMap<String, Object>();
    body.put("items", items);
    body.put("total", items.size());
    body.put("page", 0);
    body.put("size", 200);
    return body;
  }

  static Map<String, Object> sku(UUID id, String code, String name, String category) {
    var row = new LinkedHashMap<String, Object>();
    row.put("id", id.toString());
    row.put("code", code);
    row.put("name", name);
    row.put("category", category);
    row.put("status", "ACTIVE");
    return row;
  }

  /** 由夹具键派生的稳定 UUID（上游 id 必须是 UUID，否则反序列化会炸）。 */
  static String fakeUuid(String key) {
    return UUID.nameUUIDFromBytes(("logistics-analysis:" + key).getBytes(java.nio.charset.StandardCharsets.UTF_8))
        .toString();
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

  /** 跑一次投影摄取。 */
  void project() {
    refresh.refresh(a, "Bearer tenant-a", FROM, TO, java.time.ZoneId.of("UTC"));
  }

  /** 播种 13.4.1.2 的销售额事实（component='REVENUE'）。 */
  void seedRevenue(String skuCode, String currency, String amount, LocalDate on) {
    tenants.run(
        a,
        () -> {
          sql.execute(
              """
              insert into reporting_profit_fact
                (id,tenant_id,operating_mode,component,shop_code,sku_code,spu_code,msku_code,order_ref,
                 period_start,period_end,currency,amount,quantity,source_service,source_reference,updated_at)
              values (?,?,'SELF_FULFILLED','REVENUE','SHOP-1',?,'','',?,
                      ?,?,?,?,0,'trade','ORDER-' || ?,now())
              """,
              UUID.randomUUID(),
              a,
              skuCode,
              "OB-" + skuCode,
              on,
              on,
              currency,
              new BigDecimal(amount),
              skuCode);
          return null;
        });
  }

  // ==========================================================================
  // ① 单均运费
  // ==========================================================================

  @Test
  void 单均运费_分母是订单数不是票数() {
    project();
    var response = logisticsAnalysis.freightPerOrder(a, FROM, TO);

    assertThat(response.currencies()).containsExactlyInAnyOrder("USD", "EUR");
    var usd =
        response.rows().stream().filter(r -> "USD".equals(r.currency())).findFirst().orElseThrow();

    // USD：已登记运费 20 + 12 + 5 + 8 = 45；票数 4；订单 3（SO-1 两票、SO-4、SO-5）。
    assertThat(usd.freightAmount()).isEqualByComparingTo("45");
    assertThat(usd.registeredWaybillCount()).isEqualTo(4);
    assertThat(usd.notRegisteredWaybillCount()).isEqualTo(1);
    assertThat(usd.orderCount()).as("分母是**订单数**").isEqualTo(3);
    assertThat(usd.perOrder()).as("45 ÷ 3 = 15").isEqualByComparingTo("15");
    assertThat(usd.perWaybill()).as("上游口径：45 ÷ 4 票 = 11.25").isEqualByComparingTo("11.25");
    assertThat(usd.perOrder())
        .as("单均运费与每票运费**不是一回事**（一单多票时两者不同）")
        .isNotEqualByComparingTo(usd.perWaybill());

    // 运费未登记的票：有票数、无金额；它的订单 SO-3 **不进分母**。
    assertThat(usd.orderCountAll()).as("窗口内全部运单涉及的订单数").isEqualTo(4);
    assertThat(usd.note()).contains("未登记");

    var eur =
        response.rows().stream().filter(r -> "EUR".equals(r.currency())).findFirst().orElseThrow();
    assertThat(eur.freightAmount()).isEqualByComparingTo("30");
    assertThat(eur.orderCount()).isEqualTo(1);
    assertThat(eur.perOrder()).isEqualByComparingTo("30");
  }

  @Test
  void 单均运费_没有订单时不可比而不是0() {
    // 窗口内一票都没有 ⇒ 没有订单 ⇒ 单均运费**不可比**（不是 0 元）。
    var response = logisticsAnalysis.freightPerOrder(a, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31));
    assertThat(response.rows()).isEmpty();
    assertThat(response.orderCount()).isZero();
    assertThat(response.warnings()).anyMatch(w -> w.contains("不可比"));
  }

  @Test
  void 单均运费_多币种绝不相加() {
    project();
    var response = logisticsAnalysis.freightPerOrder(a, FROM, TO);
    // USD 45 + EUR 30 = 75 —— **报表里没有这个数**：两行各自带币种。
    assertThat(response.rows()).hasSize(2);
    assertThat(response.rows().stream().map(r -> r.freightAmount().toPlainString()))
        .containsExactlyInAnyOrder("45.0000", "30.0000");
    assertThat(response.rows().stream().map(r -> r.currency()).distinct()).hasSize(2);
  }

  // ==========================================================================
  // ② 成本总览
  // ==========================================================================

  @Test
  void 成本总览_逐币种且缺项不是0() {
    project();
    var response = logisticsAnalysis.overview(a, FROM, TO);

    assertThat(response.currencies()).containsExactlyInAnyOrder("USD", "EUR");
    assertThat(response.snapshotAsOf()).isEqualTo(TO);
    var usd =
        response.rows().stream().filter(r -> "USD".equals(r.currency())).findFirst().orElseThrow();
    assertThat(usd.lastMileFreight()).isEqualByComparingTo("45");
    assertThat(usd.firstMileAllocation()).isEqualByComparingTo("260");
    assertThat(usd.subtotal()).isEqualByComparingTo("330");
    assertThat(usd.missingComponents()).isEmpty();

    var eur =
        response.rows().stream().filter(r -> "EUR".equals(r.currency())).findFirst().orElseThrow();
    // 🔴 缺的三个分量是 **null（算不出来）不是 0**。
    assertThat(eur.firstMileAllocation()).as("缺头程分摊 ⇒ 算不出来").isNull();
    assertThat(eur.dutyAndTax()).as("缺关税 ⇒ 算不出来").isNull();
    assertThat(eur.packaging()).as("缺包材 ⇒ 算不出来").isNull();
    assertThat(eur.subtotal()).isEqualByComparingTo("30");
    assertThat(eur.missingComponents())
        .containsExactly("FIRST_MILE_ALLOCATION", "DUTY_AND_TAX", "PACKAGING");

    assertThat(response.coverageComplete()).isFalse();
    assertThat(response.missingComponents()).contains("PACKAGING");
    assertThat(response.warnings()).anyMatch(w -> w.contains("不是 0") || w.contains("缺"));
  }

  // ==========================================================================
  // ③ 头程分摊后成本
  // ==========================================================================

  @Test
  void 头程分摊_读上游分摊结果且与总览并排对照() {
    project();
    var response = logisticsAnalysis.headMile(a, FROM, TO);

    assertThat(response.rows()).hasSize(1);
    var usd = response.rows().get(0);
    assertThat(usd.currency()).isEqualTo("USD");
    assertThat(usd.allocatedAmount()).as("200 + 60").isEqualByComparingTo("260");
    assertThat(usd.sourceTotalAmount()).isEqualByComparingTo("260");
    assertThat(usd.allocationCount()).isEqualTo(2);
    assertThat(usd.lineCount()).isEqualTo(3);
    // 与上游成本总览的头程分摊位**一致** ⇒ 差为 0。
    assertThat(usd.upstreamOverviewAmount()).isEqualByComparingTo("260");
    assertThat(usd.differenceToOverview()).isEqualByComparingTo("0");

    assertThat(response.allocations()).hasSize(2);
    // 清单按 cost_as_of **倒序**（最新的先列）。
    assertThat(response.allocations().get(0).destinationCountry()).isEqualTo("DE");
    assertThat(response.allocations().get(1).destinationCountry()).isEqualTo("US");
    assertThat(response.allocations().get(1).ruleCode()).isEqualTo("HEAD_MILE_BY_QTY");
    assertThat(response.lockedCount()).isEqualTo(1);
  }

  // ==========================================================================
  // ④ 渠道对比
  // ==========================================================================

  @Test
  void 渠道对比_逐币种且占比分母是同币种合计() {
    project();
    var response = logisticsAnalysis.channels(a, FROM, TO, null, 0, 50);

    assertThat(response.rows()).hasSize(3);
    assertThat(response.total()).isEqualTo(3);
    var c1 =
        response.rows().stream()
            .filter(r -> "C1".equals(r.carrierCode()))
            .findFirst()
            .orElseThrow();
    assertThat(c1.currency()).isEqualTo("USD");
    assertThat(c1.amount()).isEqualByComparingTo("40");
    // USD 合计 45 ⇒ 40 / 45 = 88.8889%。
    assertThat(c1.shareOfCurrency()).isEqualByComparingTo("88.8889");
    var c2 =
        response.rows().stream()
            .filter(r -> "C2".equals(r.carrierCode()))
            .findFirst()
            .orElseThrow();
    // EUR 只有一个渠道 ⇒ 占比 100%。
    assertThat(c2.shareOfCurrency()).isEqualByComparingTo("100.0000");
    assertThat(c2.amount()).isEqualByComparingTo("30");
  }

  // ==========================================================================
  // ⑤ 时效达成率
  // ==========================================================================

  @Test
  void 时效达成率_读上游判定且按国家先汇总再相除() {
    project();
    var response = logisticsAnalysis.timeliness(a, FROM, TO);

    assertThat(response.cells()).hasSize(2);
    assertThat(response.comparableCount()).isEqualTo(12);
    assertThat(response.onTimeCount()).isEqualTo(10);
    assertThat(response.overdueCount()).isEqualTo(2);
    // 10 / 12 = 83.3333（**先汇总再相除**，不是各格比率的平均）。
    assertThat(response.overallOnTimeRate()).isEqualByComparingTo("83.3333");

    var us =
        response.byCountry().stream()
            .filter(r -> "US".equals(r.countryCode()))
            .findFirst()
            .orElseThrow();
    assertThat(us.comparableCount()).isEqualTo(8);
    assertThat(us.onTimeRate()).isEqualByComparingTo("75");
    var de =
        response.byCountry().stream()
            .filter(r -> "DE".equals(r.countryCode()))
            .findFirst()
            .orElseThrow();
    assertThat(de.onTimeRate()).isEqualByComparingTo("100");
  }

  // ==========================================================================
  // ⑥ 各国成本对比
  // ==========================================================================

  @Test
  void 各国成本_逐币种且未登记运费不进合计() {
    project();
    var response = logisticsAnalysis.countryCost(a, FROM, TO);

    assertThat(response.currencies()).containsExactlyInAnyOrder("USD", "EUR");
    var us =
        response.rows().stream()
            .filter(r -> "USD".equals(r.currency()) && "US".equals(r.countryCode()))
            .findFirst()
            .orElseThrow();
    // US = W1(20) + W2(12) + W6(8) = 40；W4 未登记 ⇒ 不进金额。
    assertThat(us.amount()).isEqualByComparingTo("40");
    assertThat(us.waybills()).isEqualTo(4);
    assertThat(us.orderCount()).isEqualTo(2);
    // USD 合计 45 ⇒ 40 / 45 = 88.8889。
    assertThat(us.shareOfCurrency()).isEqualByComparingTo("88.8889");

    var unknown =
        response.rows().stream()
            .filter(r -> "USD".equals(r.currency()) && "UNKNOWN".equals(r.countryCode()))
            .findFirst()
            .orElseThrow();
    assertThat(unknown.amount()).as("没有目的国的票单列，不摊进任何国家").isEqualByComparingTo("5");

    var de =
        response.rows().stream()
            .filter(r -> "EUR".equals(r.currency()) && "DE".equals(r.countryCode()))
            .findFirst()
            .orElseThrow();
    assertThat(de.amount()).isEqualByComparingTo("30");
    assertThat(de.shareOfCurrency()).isEqualByComparingTo("100.0000");
    assertThat(response.missingCountryCount()).isEqualTo(1);
  }

  // ==========================================================================
  // ⑦ 异常件占比
  // ==========================================================================

  @Test
  void 异常件占比_分子分母同范围() {
    project();
    var response = logisticsAnalysis.exceptionShare(a, FROM, TO);

    // 窗口内两件（E1 / E2）；E3 在 9 月，不进分子。
    assertThat(response.exceptionCount()).isEqualTo(2);
    // 分母 = 窗口内运单 6 票（含未登记运费的 W4）。
    assertThat(response.waybillCount()).isEqualTo(6);
    assertThat(response.exceptionShare()).as("2 ÷ 6 = 33.3333").isEqualByComparingTo("33.3333");

    var byType =
        response.byType().stream()
            .filter(r -> "TRACKING_FLAG".equals(r.dimensionKey()))
            .findFirst()
            .orElseThrow();
    assertThat(byType.count()).isEqualTo(2);
    assertThat(byType.shareOfExceptions()).isEqualByComparingTo("100");
    assertThat(response.topCases()).hasSize(2);
  }

  // ==========================================================================
  // ⑧ 类目维度物流成本占比（下游 5 定价）
  // ==========================================================================

  @Test
  void 类目维度物流成本占比_分子分摊后成本分母13_4_1_2销售额() {
    project();
    seedRevenue("SKU-1", "USD", "500", LocalDate.of(2026, 10, 5));
    seedRevenue("SKU-2", "USD", "300", LocalDate.of(2026, 10, 6));
    seedRevenue("SKU-3", "USD", "200", LocalDate.of(2026, 10, 7));

    var response = logisticsAnalysis.categoryCost(a, FROM, TO);

    var home =
        response.rows().stream()
            .filter(r -> "CAT-HOME".equals(r.categoryCode()))
            .findFirst()
            .orElseThrow();
    // 分子：SKU-1 100 + SKU-3 60 = 160；分母：500 + 200 = 700。
    assertThat(home.logisticsCost()).isEqualByComparingTo("160");
    assertThat(home.revenue()).isEqualByComparingTo("700");
    assertThat(home.ratio()).as("160 ÷ 700 = 22.8571").isEqualByComparingTo("22.8571");

    var kitchen =
        response.rows().stream()
            .filter(r -> "CAT-KITCHEN".equals(r.categoryCode()))
            .findFirst()
            .orElseThrow();
    assertThat(kitchen.logisticsCost()).isEqualByComparingTo("100");
    assertThat(kitchen.revenue()).isEqualByComparingTo("300");
    assertThat(kitchen.ratio()).as("100 ÷ 300 = 33.3333").isEqualByComparingTo("33.3333");
    assertThat(kitchen.exceedsTarget()).as("超过默认上限 30%").isTrue();
    assertThat(home.exceedsTarget()).isFalse();

    // ⚠️ 口径边界必须写在页面上：类目维度**只覆盖头程分摊部分**。
    assertThat(response.caliberNote()).contains("头程分摊");
    assertThat(response.warnings()).anyMatch(w -> w.contains("尾程"));
  }

  @Test
  void 类目维度物流成本占比_没有销售额时不可比而不是0() {
    project();
    var response = logisticsAnalysis.categoryCost(a, FROM, TO);
    var home =
        response.rows().stream()
            .filter(r -> "CAT-HOME".equals(r.categoryCode()))
            .findFirst()
            .orElseThrow();
    assertThat(home.revenue()).isNull();
    assertThat(home.ratio()).as("没有分母 ⇒ 不可比（不是 0%）").isNull();
    assertThat(home.note()).contains("不可比");
  }

  // ==========================================================================
  // ⑨ 时间字段与幂等
  // ==========================================================================

  @Test
  void 时间字段_写回的值逐字相等不偏时区() {
    project();
    var drilldown = logisticsAnalysis.drilldown(a, FROM, TO, null, null, null, 0, 50);
    var wb1 =
        drilldown.rows().stream()
            .filter(r -> "WB-W1".equals(r.waybillNo()))
            .findFirst()
            .orElseThrow();
    // 上游是 2026-10-02T08:00:00Z ⇒ 写回必须**逐字相等**（不能偏 −28800s）。
    assertThat(wb1.occurredAt()).isNotNull();
    assertThat(wb1.occurredAt().toInstant()).isEqualTo(java.time.Instant.parse("2026-10-02T08:00:00Z"));
    assertThat(wb1.occurredOn()).isEqualTo(LocalDate.of(2026, 10, 2));
  }

  @Test
  void 幂等_同一批上游事实重放不翻倍() {
    project();
    var before =
        logisticsAnalysis.drilldown(a, FROM, TO, null, null, null, 0, 50).total();
    project();
    project();
    var after = logisticsAnalysis.drilldown(a, FROM, TO, null, null, null, 0, 50).total();
    assertThat(after).as("重放同一批上游事实不会翻倍").isEqualTo(before);
    assertThat(after).isEqualTo(6);
  }

  @Test
  void 下钻_运费未登记的票是横杠不是0元() {
    project();
    var drilldown = logisticsAnalysis.drilldown(a, FROM, TO, null, null, null, 0, 50);
    var w4 =
        drilldown.rows().stream()
            .filter(r -> "WB-W4".equals(r.waybillNo()))
            .findFirst()
            .orElseThrow();
    assertThat(w4.freightAmount()).as("运费没登记 ⇒ 算不出来").isNull();
    assertThat(w4.freightGapCode()).isEqualTo("NOT_REGISTERED");
    assertThat(w4.note()).contains("未登记");
  }

  // ==========================================================================
  // ⑩ HTTP 边界
  // ==========================================================================

  @Test
  void http_端点可读且参数非法时400() throws Exception {
    project();
    http.perform(
            get("/reporting/logistics-analysis/capabilities").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sections.length()").value(7));
    http.perform(
            get("/reporting/logistics-analysis/freight-per-order")
                .header("Authorization", "Bearer tenant-a")
                .param("from", "2026-10-01")
                .param("to", "2026-10-31"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rows.length()").value(2));
    // 起始日期晚于结束日期 ⇒ 400（不是 500）。
    http.perform(
            get("/reporting/logistics-analysis/freight-per-order")
                .header("Authorization", "Bearer tenant-a")
                .param("from", "2026-10-31")
                .param("to", "2026-10-01"))
        .andExpect(status().isBadRequest());
    // 币种不是 ISO alpha-3 ⇒ 400。
    http.perform(
            get("/reporting/logistics-analysis/channels")
                .header("Authorization", "Bearer tenant-a")
                .param("currency", "美元"))
        .andExpect(status().isBadRequest());
  }
}
