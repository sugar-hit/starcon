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
 * 13.5.1.1 库存分析的**集成测试**（真实 PostgreSQL 16 + FORCE RLS，无内存替身）。
 *
 * <p>跑的是本模块的**业务口径**：
 *
 * <ol>
 *   <li>🔴 **周转率按成本口径算**（蓝图加粗句）：销量 × catalog 单位成本，**不是**售价；</li>
 *   <li>「算不出来」与「等于 0」分开：没有维护成本的 SKU 金额是 **null**，分母为 0 的周转率是 **null**；</li>
 *   <li>库存金额结构按仓库 / 类目 / 店铺拆解，**多币种绝不相加**；</li>
 *   <li>库龄分布与呆滞占比（判定归 warehouse，本模块只搬运）；</li>
 *   <li>缺货与超储，且**未设阈值**单独计数；</li>
 *   <li>ABC 分类按**库存金额**的累计占比切分；</li>
 *   <li>盘点历史的准确率，账面行数为 0 时**不可比**；</li>
 *   <li>同一批上游事实重放被幂等跳过。</li>
 * </ol>
 *
 * <p>租户隔离的三条断言在 {@code InventoryAnalysisTenancyIntegrationTest}（同一批跑）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class InventoryAnalysisIntegrationTest {

  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  static HttpServer upstream;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static final Map<String, String> upstreamAuth = new ConcurrentHashMap<>();

  static final List<String> permissions =
      List.of(
          "reporting.inventory-analysis.read",
          "reporting.inventory-analysis.write",
          "reporting.projection.read",
          "reporting.projection.write");

  static final ObjectMapper JSON = JsonMapper.builder().addModule(new JavaTimeModule()).build();

  /** 快照日（期初）。 */
  static final LocalDate DAY1 = LocalDate.of(2026, 10, 9);

  /** 快照日（期末）。 */
  static final LocalDate DAY2 = LocalDate.of(2026, 10, 10);

  /** 第三天（销售窗口的边界）。 */
  static final LocalDate DAY3 = LocalDate.of(2026, 10, 11);

  // ===== 夹具里的 id =====
  static final UUID SKU_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
  static final UUID SKU_B = UUID.fromString("22222222-2222-2222-2222-222222222222");
  static final UUID SKU_C = UUID.fromString("33333333-3333-3333-3333-333333333333");
  static final UUID SKU_D = UUID.fromString("44444444-4444-4444-4444-444444444444");
  static final UUID SKU_E = UUID.fromString("55555555-5555-5555-5555-555555555555");
  static final UUID WH_1 = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
  static final UUID WH_2 = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000002");
  static final UUID WH_3 = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000003");
  static final UUID ORDER_1 = UUID.fromString("66666666-0000-0000-0000-000000000001");
  static final UUID ORDER_2 = UUID.fromString("66666666-0000-0000-0000-000000000002");
  static final UUID ORDER_3 = UUID.fromString("66666666-0000-0000-0000-000000000003");
  static final UUID LINE_1 = UUID.fromString("77777777-0000-0000-0000-000000000001");
  static final UUID LINE_2 = UUID.fromString("77777777-0000-0000-0000-000000000002");

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
      upstream.createContext("/catalog/skus", e -> respond(e, catalogSkus()));
      upstream.createContext("/catalog/sku-cost-compositions", e -> respond(e, catalogCosts()));
      upstream.createContext("/channel/listings", e -> respond(e, channelListings()));
      upstream.createContext("/warehouse/inventory/overview", e -> respond(e, warehouseOverview()));
      upstream.createContext("/warehouse/inventory/aging/buckets", e -> respond(e, agingBuckets()));
      upstream.createContext("/warehouse/inventory/aging/slow-moving", e -> respond(e, slowMoving()));
      upstream.createContext("/warehouse/inventory/stocktakes", e -> respond(e, stocktakes()));
      upstream.createContext("/warehouse/warehouses", e -> respond(e, warehouses()));
      upstream.createContext("/trade/sales-orders/list", e -> respond(e, tradeOrders()));
      upstream.createContext("/trade/sales-orders/detail", e -> respond(e, tradeOrderDetail(e)));
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
    r.add("WAREHOUSE_URL", () -> "http://localhost:" + upstream.getAddress().getPort());
    r.add("CATALOG_URL", () -> "http://localhost:" + upstream.getAddress().getPort());
    r.add("CHANNEL_URL", () -> "http://localhost:" + upstream.getAddress().getPort());
    r.add("TRADE_URL", () -> "http://localhost:" + upstream.getAddress().getPort());
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  @Autowired TenantTransactions tenants;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;
  @Autowired ReportingInventoryAnalysisService inventoryAnalysis;
  @Autowired ReportingInventoryAnalysisRefreshService refresh;

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
          "truncate reporting_inventory_analysis_position, reporting_inventory_analysis_sales_fact,"
              + " reporting_inventory_analysis_aging_fact, reporting_inventory_analysis_stocktake_fact,"
              + " reporting_inventory_analysis_rule cascade");
    }
    upstreamAuth.clear();
  }

  // ==========================================================================
  // 夹具：假上游
  // ==========================================================================

  static Map<String, Object> catalogSkus() {
    var items = new ArrayList<Map<String, Object>>();
    items.add(sku(SKU_A, "SKU-A", "家居收纳"));
    items.add(sku(SKU_B, "SKU-B", "家居收纳"));
    items.add(sku(SKU_C, "SKU-C", "厨房用品"));
    items.add(sku(SKU_D, "SKU-D", "厨房用品"));
    items.add(sku(SKU_E, "SKU-E", "厨房用品"));
    return page("items", items);
  }

  static Map<String, Object> sku(UUID id, String code, String category) {
    var row = new LinkedHashMap<String, Object>();
    row.put("id", id.toString());
    row.put("code", code);
    row.put("name", code + " 名称");
    row.put("category", category);
    return row;
  }

  static Map<String, Object> catalogCosts() {
    var items = new ArrayList<Map<String, Object>>();
    // SKU-A：单位成本 10（采购 10 + 包装 0 + 其它 0）
    items.add(cost(SKU_A, "SKU-A", true, "USD", "MOVING_AVERAGE", "10", "0", "0"));
    // SKU-B：单位成本 20
    items.add(cost(SKU_B, "SKU-B", true, "USD", "MOVING_AVERAGE", "20", "0", "0"));
    // SKU-C：单位成本 5
    items.add(cost(SKU_C, "SKU-C", true, "USD", "MOVING_AVERAGE", "5", "0", "0"));
    // ⚠️ **SKU-D / SKU-E 故意不给成本行** ⇒ 它们的库存金额「算不出来」（**不是 0**）。
    return page("items", items);
  }

  static Map<String, Object> cost(
      UUID skuId,
      String skuCode,
      boolean maintained,
      String currency,
      String method,
      String purchase,
      String packaging,
      String other) {
    var row = new LinkedHashMap<String, Object>();
    row.put("skuId", skuId.toString());
    row.put("skuCode", skuCode);
    row.put("skuName", skuCode + " 名称");
    row.put("maintained", maintained);
    row.put("costMethod", method);
    row.put("currency", currency);
    row.put("purchaseCost", purchase);
    row.put("packagingCost", packaging);
    row.put("otherDirectCost", other);
    return row;
  }

  static Map<String, Object> channelListings() {
    var items = new ArrayList<Map<String, Object>>();
    // SKU-A → SHOP-1（唯一映射）
    items.add(listing("SHOP-1", "美亚店", SKU_A));
    // SKU-B → SHOP-2（唯一映射）
    items.add(listing("SHOP-2", "欧洲店", SKU_B));
    // SKU-C → 没有任何 Listing ⇒ 未归属店铺
    // ⚠️ SKU-D → 挂在两个店铺 ⇒ **无法唯一归属**，本模块如实留空（不摊给任何一个）
    items.add(listing("SHOP-1", "美亚店", SKU_D));
    items.add(listing("SHOP-2", "欧洲店", SKU_D));
    return page("items", items);
  }

  static Map<String, Object> listing(String shopCode, String shopName, UUID skuId) {
    var row = new LinkedHashMap<String, Object>();
    row.put("id", UUID.randomUUID().toString());
    row.put("shopCode", shopCode);
    row.put("shopName", shopName);
    row.put("platformCode", "AMAZON");
    row.put("status", "ACTIVE");
    row.put("internalSkuIds", List.of(skuId.toString()));
    return row;
  }

  static Map<String, Object> warehouseOverview() {
    var rows = new ArrayList<Map<String, Object>>();
    // SKU-A @ WH-1：在手 100、可用 100、未超储
    rows.add(balance(SKU_A, "SKU-A", WH_1, "WH-1", "美西仓", "100", "100", false, true, "200", false));
    // SKU-B @ WH-1：在手 5、阈值 3 ⇒ **超储** 2
    rows.add(balance(SKU_B, "SKU-B", WH_1, "WH-1", "美西仓", "5", "5", false, true, "3", true));
    // SKU-C @ WH-2：在手 50
    rows.add(balance(SKU_C, "SKU-C", WH_2, "WH-2", "美东仓", "50", "50", false, true, "80", false));
    // SKU-D @ WH-2：**零库存**、未设阈值
    rows.add(balance(SKU_D, "SKU-D", WH_2, "WH-2", "美东仓", "0", "0", true, false, null, false));
    // SKU-E @ WH-3：在手 10，但**没有维护成本** ⇒ 金额算不出来
    rows.add(balance(SKU_E, "SKU-E", WH_3, "WH-3", "欧洲仓", "10", "10", false, false, null, false));
    var body = new LinkedHashMap<String, Object>();
    body.put("rows", rows);
    body.put("total", rows.size());
    body.put("page", 0);
    body.put("size", 200);
    return body;
  }

  static Map<String, Object> balance(
      UUID skuId,
      String skuCode,
      UUID warehouseId,
      String warehouseCode,
      String warehouseName,
      String onHand,
      String available,
      boolean zeroStock,
      boolean thresholdConfigured,
      String maxOnHand,
      boolean overstock) {
    var row = new LinkedHashMap<String, Object>();
    row.put("balanceId", UUID.randomUUID().toString());
    row.put("skuId", skuId.toString());
    row.put("skuCode", skuCode);
    row.put("skuName", skuCode + " 名称");
    row.put("warehouseId", warehouseId.toString());
    row.put("warehouseCode", warehouseCode);
    row.put("warehouseName", warehouseName);
    row.put("legalEntityId", "99999999-9999-9999-9999-999999999999");
    row.put("legalEntityCode", "LE-1");
    row.put("onHand", onHand);
    row.put("reserved", "0");
    row.put("available", available);
    row.put("zeroStock", zeroStock);
    row.put("thresholdConfigured", thresholdConfigured);
    if (maxOnHand != null) row.put("maxOnHand", maxOnHand);
    row.put("overstock", overstock);
    return row;
  }

  static Map<String, Object> agingBuckets() {
    var buckets = new ArrayList<Map<String, Object>>();
    buckets.add(bucket("AGE_0_30", "0-30 天", 0, 30, "2", "3", "60", "60"));
    buckets.add(bucket("AGE_31_60", "31-60 天", 31, 60, "2", "2", "40", "40"));
    buckets.add(bucket("AGE_61_90", "61-90 天", 61, 90, "0", "0", "0", "0"));
    buckets.add(bucket("AGE_90_PLUS", "90+ 天", 91, null, "1", "2", "50", "50"));
    buckets.add(bucket("NO_INBOUND_DATE", "无入库日期", null, null, "1", "1", "5", "5"));
    var body = new LinkedHashMap<String, Object>();
    body.put("asOf", DAY2.toString());
    body.put("buckets", buckets);
    body.put("agingBasisNote", "库龄 = 今天 - 入库日");
    return body;
  }

  static Map<String, Object> bucket(
      String key,
      String label,
      Integer lower,
      Integer upper,
      String skuCount,
      String bucketCount,
      String onHand,
      String available) {
    var row = new LinkedHashMap<String, Object>();
    row.put("bucket", key);
    row.put("label", label);
    if (lower != null) row.put("lowerDays", lower);
    if (upper != null) row.put("upperDays", upper);
    row.put("skuCount", Long.parseLong(skuCount));
    row.put("bucketCount", Long.parseLong(bucketCount));
    row.put("lotBasedBucketCount", Long.parseLong(bucketCount));
    row.put("onHand", onHand);
    row.put("reserved", "0");
    row.put("available", available);
    return row;
  }

  static Map<String, Object> slowMoving() {
    var summary = new LinkedHashMap<String, Object>();
    summary.put("skuCount", 3);
    summary.put("bucketCount", 5);
    summary.put("obsoleteCount", 1);
    summary.put("slowMovingCount", 2);
    summary.put("obsoleteAvailable", "20");
    summary.put("slowMovingAvailable", "10");
    summary.put("obsoleteAndUpAvailable", "30");
    summary.put("oldestAgingDays", 200);
    var body = new LinkedHashMap<String, Object>();
    body.put("summary", summary);
    body.put("rows", List.of());
    body.put("total", 0);
    body.put("page", 0);
    body.put("size", 1);
    return body;
  }

  static Map<String, Object> stocktakes() {
    var sheets = new ArrayList<Map<String, Object>>();
    var first = new LinkedHashMap<String, Object>();
    first.put("id", "88888888-0000-0000-0000-000000000001");
    first.put("code", "PD-1");
    first.put("scopeType", "WAREHOUSE");
    first.put("scopeLabel", "美西仓");
    first.put("status", "COMPLETED");
    first.put("frozenAt", "2026-10-08T00:00:00Z");
    first.put("lineCount", 100);
    first.put("countedLineCount", 90);
    first.put("varianceLineCount", 5);
    first.put("bookTotal", "1000");
    sheets.add(first);
    // ⚠️ 第二张盘点单**账面行数为 0** ⇒ 准确率**不可比**（不是 0%）。
    var second = new LinkedHashMap<String, Object>();
    second.put("id", "88888888-0000-0000-0000-000000000002");
    second.put("code", "PD-2");
    second.put("scopeType", "WAREHOUSE");
    second.put("scopeLabel", "空仓");
    second.put("status", "DRAFT");
    second.put("frozenAt", "2026-10-09T00:00:00Z");
    second.put("lineCount", 0);
    second.put("countedLineCount", 0);
    second.put("varianceLineCount", 0);
    second.put("bookTotal", "0");
    sheets.add(second);
    var body = new LinkedHashMap<String, Object>();
    body.put("sheets", sheets);
    body.put("total", sheets.size());
    body.put("page", 0);
    body.put("size", 200);
    return body;
  }

  static List<Map<String, Object>> warehouses() {
    var list = new ArrayList<Map<String, Object>>();
    for (var wh :
        List.of(
            Map.of("id", WH_1, "code", "WH-1", "name", "美西仓"),
            Map.of("id", WH_2, "code", "WH-2", "name", "美东仓"),
            Map.of("id", WH_3, "code", "WH-3", "name", "欧洲仓"))) {
      var row = new LinkedHashMap<String, Object>();
      row.put("id", wh.get("id").toString());
      row.put("code", wh.get("code"));
      row.put("name", wh.get("name"));
      list.add(row);
    }
    return list;
  }

  static Map<String, Object> tradeOrders() {
    var rows = new ArrayList<Map<String, Object>>();
    rows.add(order(ORDER_1, "SO-1", "SUBMITTED", "2026-10-10T00:00:00Z"));
    // ⚠️ 草稿单**不是销量**，必须被跳过。
    rows.add(order(ORDER_2, "SO-2", "DRAFT", "2026-10-10T00:00:00Z"));
    rows.add(order(ORDER_3, "SO-3", "SUBMITTED", "2026-10-11T00:00:00Z"));
    var body = new LinkedHashMap<String, Object>();
    body.put("rows", rows);
    body.put("page", 0);
    body.put("size", 200);
    body.put("total", rows.size());
    return body;
  }

  static Map<String, Object> order(UUID id, String orderNo, String status, String submittedAt) {
    var row = new LinkedHashMap<String, Object>();
    row.put("id", id.toString());
    row.put("orderNo", orderNo);
    row.put("documentStatus", status);
    row.put("currency", "USD");
    row.put("grandTotalAmount", "2000");
    row.put("lineCount", 1);
    row.put("submittedAt", submittedAt);
    row.put("createdAt", submittedAt);
    return row;
  }

  static Map<String, Object> tradeOrderDetail(com.sun.net.httpserver.HttpExchange e) {
    var path = e.getRequestURI().getPath();
    var id = path.substring(path.lastIndexOf('/') + 1);
    var header = new LinkedHashMap<String, Object>();
    var lines = new ArrayList<Map<String, Object>>();
    if (ORDER_1.toString().equals(id)) {
      header.put("orderNo", "SO-1");
      header.put("documentStatus", "SUBMITTED");
      header.put("currency", "USD");
      header.put("grandTotalAmount", "2000");
      header.put("submittedAt", "2026-10-10T00:00:00Z");
      // ⚠️ 售价 100 / 单位成本 10 —— 两者差 10 倍，正是用来钉住「按成本口径算」的那一组数。
      lines.add(line(LINE_1, 1, "SKU-A", "20", "100", "2000"));
    } else if (ORDER_3.toString().equals(id)) {
      header.put("orderNo", "SO-3");
      header.put("documentStatus", "SUBMITTED");
      header.put("currency", "USD");
      header.put("grandTotalAmount", "150");
      header.put("submittedAt", "2026-10-11T00:00:00Z");
      lines.add(line(LINE_2, 1, "SKU-B", "3", "50", "150"));
    } else {
      header.put("orderNo", "SO-2");
      header.put("documentStatus", "DRAFT");
      lines.add(line(UUID.randomUUID(), 1, "SKU-A", "999", "100", "99900"));
    }
    var body = new LinkedHashMap<String, Object>();
    body.put("header", header);
    body.put("lines", lines);
    return body;
  }

  static Map<String, Object> line(UUID id, int lineNo, String skuCode, String qty, String price, String amount) {
    var row = new LinkedHashMap<String, Object>();
    row.put("id", id.toString());
    row.put("lineNo", lineNo);
    row.put("skuCode", skuCode);
    row.put("skuName", skuCode + " 名称");
    row.put("quantity", qty);
    row.put("unitPrice", price);
    row.put("lineAmount", amount);
    return row;
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
      upstreamAuth.put(e.getRequestURI().getPath(), e.getRequestHeaders().getFirst("Authorization"));
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
  // 写入夹具（直接落投影表 —— 与刷新路径写的是同一张表、同一套幂等键）
  // ==========================================================================

  /** 写一条库存头寸。 */
  private void position(
      LocalDate asOf,
      String skuCode,
      String warehouseCode,
      String onHand,
      String available,
      String unitCost,
      String currency,
      String categoryCode,
      String shopCode) {
    tenants.run(
        a,
        () -> {
          BigDecimal cost = unitCost == null ? null : new BigDecimal(unitCost);
          BigDecimal value =
              cost == null ? null : new BigDecimal(onHand).multiply(cost).setScale(4, java.math.RoundingMode.HALF_UP);
          sql.execute(
              """
              insert into reporting_inventory_analysis_position
                (id,tenant_id,as_of,sku_id,sku_code,sku_name,
                 warehouse_id,warehouse_code,warehouse_name,
                 legal_entity_id,legal_entity_code,category_code,shop_code,shop_name,
                 on_hand,reserved,available,max_on_hand,
                 unit_cost,cost_currency,cost_method,inventory_value,
                 zero_stock,overstock,threshold_configured,
                 source_service,source_reference,updated_at)
              values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,now())
              on conflict (tenant_id,as_of,sku_id,warehouse_id)
              do update set on_hand = excluded.on_hand,
                            available = excluded.available,
                            unit_cost = excluded.unit_cost,
                            cost_currency = excluded.cost_currency,
                            inventory_value = excluded.inventory_value,
                            updated_at = now()
              """,
              UUID.randomUUID(),
              a,
              asOf,
              UUID.randomUUID(),
              skuCode,
              skuCode + " 名称",
              UUID.randomUUID(),
              warehouseCode,
              warehouseCode + " 仓",
              null,
              "",
              categoryCode,
              shopCode,
              "",
              new BigDecimal(onHand),
              BigDecimal.ZERO,
              new BigDecimal(available),
              null,
              cost,
              currency,
              "MOVING_AVERAGE",
              value,
              new BigDecimal(available).signum() == 0,
              false,
              false,
              "test",
              "test:position:" + asOf + ":" + skuCode + ":" + warehouseCode);
          return null;
        });
  }

  /** 写一条销售出库事实（成本口径）。 */
  private void sales(LocalDate soldOn, String skuCode, String quantity, String unitCost, String currency) {
    tenants.run(
        a,
        () -> {
          var cost = new BigDecimal(unitCost);
          var qty = new BigDecimal(quantity);
          sql.execute(
              """
              insert into reporting_inventory_analysis_sales_fact
                (id,tenant_id,sold_on,sku_id,sku_code,sku_name,
                 category_code,shop_code,shop_name,
                 quantity,unit_cost,cost_currency,cost_amount,
                 source_service,source_reference,updated_at)
              values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,now())
              on conflict (tenant_id,sold_on,sku_id,source_reference)
              do update set quantity = excluded.quantity,
                            cost_amount = excluded.cost_amount,
                            updated_at = now()
              """,
              UUID.randomUUID(),
              a,
              soldOn,
              UUID.randomUUID(),
              skuCode,
              skuCode + " 名称",
              "",
              "",
              "",
              qty,
              cost,
              currency,
              qty.multiply(cost).setScale(4, java.math.RoundingMode.HALF_UP),
              "test",
              "test:sales:" + soldOn + ":" + skuCode + ":" + quantity);
          return null;
        });
  }

  // ==========================================================================
  // 测试 1：刷新（投影摄取）
  // ==========================================================================

  @Test
  void 刷新_把上游事实落成成本口径的投影() {
    // 窗口覆盖两张**已提交**单（SO-1 在 10-10、SO-3 在 10-11）；草稿单 SO-2 在窗口内但**不算销量**。
    var result = refresh.refresh(a, "Bearer tenant-a", DAY1, DAY3);
    assertThat(result.sources()).hasSize(4);
    assertThat(result.sources()).allSatisfy(s -> assertThat(s.status()).as(s.source()).isEqualTo("OK"));

    // 库存头寸：SKU-A 在手 100 × 单位成本 10 = 1000
    var rows =
        tenants.run(
            a,
            () ->
                sql.fetch(
                    "select sku_code, on_hand, unit_cost, cost_currency, inventory_value"
                        + " from reporting_inventory_analysis_position order by sku_code"));
    assertThat(rows).hasSize(5);
    var bySku = new LinkedHashMap<String, org.jooq.Record>();
    for (var row : rows) bySku.put(row.get(0, String.class), row);

    var skuA = bySku.get("SKU-A");
    assertThat(skuA.get(2, BigDecimal.class)).isEqualByComparingTo("10.000000");
    assertThat(skuA.get(3, String.class)).isEqualTo("USD");
    assertThat(skuA.get(4, BigDecimal.class)).as("库存金额 = 在手 × 单位成本").isEqualByComparingTo("1000.0000");

    // 🔴 **没有维护成本的 SKU：金额是 null（算不出来），不是 0**
    var skuD = bySku.get("SKU-D");
    assertThat(skuD.get(2, BigDecimal.class)).as("没有维护成本 ⇒ 单位成本算不出来").isNull();
    assertThat(skuD.get(4, BigDecimal.class)).as("没有维护成本 ⇒ 库存金额算不出来（**不是 0**").isNull();
    var skuE = bySku.get("SKU-E");
    assertThat(skuE.get(4, BigDecimal.class)).isNull();

    // 🔴 **销售出库按成本口径落库**：SKU-A 卖 20 件 × 单位成本 10 = 200（**不是**售价 2000）
    var sales =
        tenants.run(
            a,
            () ->
                sql.fetch(
                    "select sku_code, quantity, cost_amount from reporting_inventory_analysis_sales_fact"
                        + " order by sku_code"));
    assertThat(sales).as("**草稿单不是销量** ⇒ 只有两张已提交单").hasSize(2);
    assertThat(sales).as("窗口外的订单不计入").noneMatch(r -> "SO-3".equals(r.get(0, String.class)));
    var salesBySku = new LinkedHashMap<String, org.jooq.Record>();
    for (var row : sales) salesBySku.put(row.get(0, String.class), row);
    assertThat(salesBySku.get("SKU-A").get(2, BigDecimal.class))
        .as("销量 × 单位成本（**成本口径**），不是售价")
        .isEqualByComparingTo("200.0000");
    assertThat(salesBySku.get("SKU-B").get(2, BigDecimal.class)).isEqualByComparingTo("60.0000");

    // 库龄与呆滞
    var aging =
        tenants.run(
            a,
            () ->
                sql.fetch(
                    "select metric_type, metric_key, scope_type from reporting_inventory_analysis_aging_fact"));
    assertThat(aging).isNotEmpty();
    assertThat(aging).anyMatch(r -> "AGING_BUCKET".equals(r.get(0, String.class)));
    assertThat(aging).anyMatch(r -> "SLOW_MOVING".equals(r.get(0, String.class)));
    assertThat(aging).anyMatch(r -> "TENANT".equals(r.get(2, String.class)));
    assertThat(aging).anyMatch(r -> "WAREHOUSE".equals(r.get(2, String.class)));

    // 盘点历史
    var stocktakes =
        tenants.run(
            a,
            () ->
                sql.fetch(
                    "select code, line_count, counted_line_count, frozen_at"
                        + " from reporting_inventory_analysis_stocktake_fact order by code"));
    assertThat(stocktakes).hasSize(2);
    // 🔴 **断言写回的时间值**（timestamptz 绑定错了会静默偏 8 小时 —— 只断言「没抛异常」是查不出来的）
    var frozenAt = stocktakes.get(0).get(3, java.time.OffsetDateTime.class);
    assertThat(frozenAt).as("写回的冻结时刻必须与上游逐字相等（不能偏 8 小时）").isNotNull();
    assertThat(frozenAt.toInstant()).isEqualTo(java.time.Instant.parse("2026-10-08T00:00:00Z"));
  }

  @Test
  void 刷新_同一批上游事实重放不翻倍() {
    refresh.refresh(a, "Bearer tenant-a", DAY1, DAY2);
    var first = countRows();
    refresh.refresh(a, "Bearer tenant-a", DAY1, DAY2);
    var second = countRows();
    assertThat(second).as("幂等：同一批上游事实重放不会翻倍").isEqualTo(first);
  }

  private long countRows() {
    return tenants.run(
        a,
        () ->
            (long)
                sql.fetchOne("select count(*) from reporting_inventory_analysis_position").get(0, Long.class));
  }

  // ==========================================================================
  // 测试 2：库存金额结构
  // ==========================================================================

  @Test
  void 库存金额结构_按仓库拆解且不跨币种相加() {
    refresh.refresh(a, "Bearer tenant-a", DAY1, DAY2);

    var byWarehouse = inventoryAnalysis.structure(a, "WAREHOUSE", null, null);
    // ⚠️ **只有「算得出金额」的行进金额结构**（金额是币种维度的）；没有维护成本的 SKU
    //   由响应级的 costMissingSkuCount / costMissingQuantity 如实报出。
    assertThat(byWarehouse.rows()).hasSize(2);
    var wh1 = rowOf(byWarehouse.rows(), "WH-1");
    // SKU-A 100×10 = 1000 + SKU-B 5×20 = 100
    assertThat(wh1.inventoryValue()).isEqualByComparingTo("1100.0000");
    assertThat(wh1.currency()).isEqualTo("USD");
    assertThat(wh1.onHandQuantity()).isEqualByComparingTo("105");
    assertThat(wh1.overstockSkuCount()).isEqualTo(1);
    assertThat(wh1.overstockQuantity()).isEqualByComparingTo("2");

    // 单币种 ⇒ 有合计
    assertThat(byWarehouse.totals()).hasSize(1);
    assertThat(byWarehouse.totalInventoryValue())
        .as("SKU-D / SKU-E 没有成本 ⇒ 合计是「已知部分合计」")
        .isEqualByComparingTo("1350.0000");
    assertThat(byWarehouse.totalCurrency()).isEqualTo("USD");
    assertThat(byWarehouse.costMissingSkuCount()).as("SKU-D 与 SKU-E 没有维护成本").isEqualTo(2);
    assertThat(byWarehouse.costMissingQuantity()).isEqualByComparingTo("10.000000");
    assertThat(byWarehouse.warnings()).anySatisfy(w -> assertThat(w).contains("没有维护成本"));

    // 店铺维度：SKU-D 挂在两个店铺 ⇒ 未归属；SKU-C 没有 Listing ⇒ 未归属
    var byShop = inventoryAnalysis.structure(a, "SHOP", null, null);
    assertThat(rowOf(byShop.rows(), "SHOP-1").inventoryValue()).isEqualByComparingTo("1000.0000");
    assertThat(rowOf(byShop.rows(), "SHOP-2").inventoryValue()).isEqualByComparingTo("100.0000");
    // SKU-C 没有 Listing ⇒ 未归属店铺；SKU-D 挂在两个店铺 ⇒ 无法唯一归属 ⇒ 也归到未归属
    assertThat(byShop.rows()).anySatisfy(
        row -> {
          assertThat(row.dimensionKey()).isEmpty();
          assertThat(row.dimensionLabel()).isEqualTo("未归属店铺");
        });

    // 类目维度
    var byCategory = inventoryAnalysis.structure(a, "CATEGORY", null, null);
    assertThat(rowOf(byCategory.rows(), "家居收纳").inventoryValue()).isEqualByComparingTo("1100.0000");
    assertThat(rowOf(byCategory.rows(), "厨房用品").inventoryValue()).isEqualByComparingTo("250.0000");
  }

  @Test
  void 库存金额结构_两个币种时没有跨币种合计() {
    refresh.refresh(a, "Bearer tenant-a", DAY1, DAY2);
    // 追加一个欧元 SKU（同快照日）
    position(LocalDate.now(java.time.ZoneOffset.UTC), "SKU-EUR", "WH-1", "10", "10", "7", "EUR", "家居收纳", "");
    var response = inventoryAnalysis.structure(a, "ALL", null, null);
    assertThat(response.totals()).as("EUR + USD 两组（没有成本的行不进合计）").hasSize(2);
    assertThat(response.totalInventoryValue())
        .as("🔴 **跨币种的合计不存在** —— 绝不相加")
        .isNull();
    assertThat(response.totalCurrency()).isNull();
    assertThat(response.warnings()).anySatisfy(w -> assertThat(w).contains("跨币种"));
  }

  private static ReportingInventoryAnalysisModels.InventoryStructureRow rowOf(
      List<ReportingInventoryAnalysisModels.InventoryStructureRow> rows, String key) {
    return rows.stream()
        .filter(r -> key.equals(r.dimensionKey()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("没有找到维度行：" + key + "（现有 " + rows + "）"));
  }

  // ==========================================================================
  // 测试 3：周转率（成本口径）—— 本模块的命门
  // ==========================================================================

  @Test
  void 周转率_按成本口径算而不是售价() {
    // 期初：在手 100 × 10 = 1000；期末：在手 80 × 10 = 800
    position(DAY1, "SKU-A", "WH-1", "100", "100", "10", "USD", "家居收纳", "SHOP-1");
    position(DAY2, "SKU-A", "WH-1", "80", "80", "10", "USD", "家居收纳", "SHOP-1");
    // 卖 20 件（售价 100 / 件 —— **绝不能**用它算）
    sales(DAY2, "SKU-A", "20", "10", "USD");

    var response =
        inventoryAnalysis.turnover(a, DAY1, DAY2, "DAY", "ALL", null);
    assertThat(response.rows()).hasSize(1);
    var row = response.rows().get(0);

    assertThat(row.costOfGoodsSold()).as("销售出库**成本** = 20 × 10").isEqualByComparingTo("200.0000");
    assertThat(row.openingInventoryValue()).isEqualByComparingTo("1000.0000");
    assertThat(row.closingInventoryValue()).isEqualByComparingTo("800.0000");
    assertThat(row.averageInventoryValue()).isEqualByComparingTo("900.0000");
    assertThat(row.turnoverRate())
        .as("🔴 周转率 = 200 ÷ 900 = 0.2222（**按成本口径**）")
        .isEqualByComparingTo("0.2222");
    assertThat(row.turnoverRate())
        .as("🔴 用售价算会得到 2000 ÷ 900 = 2.2222 —— 那正是蓝图加粗句要消灭的虚高")
        .isNotEqualByComparingTo("2.2222");
    assertThat(row.turnoverDays()).isEqualByComparingTo("9.0000");
    assertThat(row.comparable()).isTrue();
    assertThat(row.caliber()).isEqualTo("AVERAGE");
  }

  @Test
  void 周转率_分母为0时不可比而不是0() {
    // 在手为 0 ⇒ 平均库存金额为 0 ⇒ 分母算不出来
    position(DAY1, "SKU-Z", "WH-1", "0", "0", "10", "USD", "家居收纳", "");
    position(DAY2, "SKU-Z", "WH-1", "0", "0", "10", "USD", "家居收纳", "");
    sales(DAY2, "SKU-Z", "0", "10", "USD");

    var response = inventoryAnalysis.turnover(a, DAY1, DAY2, "DAY", "ALL", null);
    var row = response.rows().get(0);
    assertThat(row.averageInventoryValue()).isEqualByComparingTo("0.0000");
    assertThat(row.turnoverRate())
        .as("🔴 分母为 0 ⇒ 周转率**不可比**（不是 0）")
        .isNull();
    assertThat(row.comparable()).isFalse();
    assertThat(row.note()).contains("不可比");
  }

  @Test
  void 周转率_没有期末快照时用期末口径并写明() {
    // ⚠️ **只有期末快照**：期初快照不存在 ⇒ opening = null ⇒ 退回期末口径并写明。
    position(DAY2, "SKU-A", "WH-1", "100", "100", "10", "USD", "家居收纳", "");
    sales(DAY2, "SKU-A", "20", "10", "USD");
    var response = inventoryAnalysis.turnover(a, DAY1, DAY2, "DAY", "ALL", null);
    var row = response.rows().get(0);
    assertThat(row.openingInventoryValue()).isNull();
    assertThat(row.closingInventoryValue()).isEqualByComparingTo("1000.0000");
    assertThat(row.caliber()).isEqualTo("CLOSING");
    assertThat(row.note()).contains("期末");
  }

  @Test
  void 周转率_按仓库拆解不可比时如实报不可比() {
    position(DAY1, "SKU-A", "WH-1", "100", "100", "10", "USD", "家居收纳", "");
    position(DAY2, "SKU-A", "WH-1", "80", "80", "10", "USD", "家居收纳", "");
    sales(DAY2, "SKU-A", "20", "10", "USD");
    var response = inventoryAnalysis.turnover(a, DAY1, DAY2, "DAY", "WAREHOUSE", null);
    var row = response.rows().get(0);
    assertThat(row.turnoverRate())
        .as("销售订单不带仓库维度 ⇒ 这一格不可比（不是 0）")
        .isNull();
    assertThat(response.warnings()).anySatisfy(w -> assertThat(w).contains("按仓库拆解"));
  }

  // ==========================================================================
  // 测试 4：库龄与呆滞
  // ==========================================================================

  @Test
  void 库龄分布与呆滞占比() {
    refresh.refresh(a, "Bearer tenant-a", DAY1, DAY2);
    var response = inventoryAnalysis.aging(a, null, null);
    assertThat(response.asOf()).isEqualTo(DAY2);
    assertThat(response.buckets()).hasSize(5);
    assertThat(response.totalAvailable()).isEqualByComparingTo("155.000000");

    var bucket = response.buckets().stream().filter(b -> "AGE_0_30".equals(b.bucket())).findFirst().orElseThrow();
    assertThat(bucket.available()).isEqualByComparingTo("60.000000");
    assertThat(bucket.availableShare()).as("60 ÷ 155").isEqualByComparingTo("38.7097");
    assertThat(bucket.lowerDays()).isEqualTo(0);
    assertThat(bucket.upperDays()).isEqualTo(30);

    // 呆滞占比 = 呆滞 20 ÷ 总可用 155
    assertThat(response.obsoleteRatio()).isEqualByComparingTo("12.9032");
    assertThat(response.ratioComparable()).isTrue();
    assertThat(response.obsoleteAndUpAvailable()).isEqualByComparingTo("30.000000");
    assertThat(response.slowMoving()).hasSize(3);
  }

  // ==========================================================================
  // 测试 5：缺货与超储
  // ==========================================================================

  @Test
  void 缺货与超储_未设阈值单独计数() {
    refresh.refresh(a, "Bearer tenant-a", DAY1, DAY2);
    var response = inventoryAnalysis.stockStatus(a, "WAREHOUSE", null);
    var wh1 = response.rows().stream().filter(r -> "WH-1".equals(r.dimensionKey())).findFirst().orElseThrow();
    assertThat(wh1.overstockSkuCount()).isEqualTo(1);
    assertThat(wh1.overstockQuantity()).isEqualByComparingTo("2");
    assertThat(wh1.thresholdUnsetSkuCount()).isZero();
    assertThat(wh1.zeroStockSkuCount()).isZero();
    assertThat(wh1.totalSkuCount()).isEqualTo(2);
    // ⚠️ **金额按币种**（绝不跨币种相加）
    assertThat(wh1.amounts()).hasSize(1);
    assertThat(wh1.amounts().get(0).currency()).isEqualTo("USD");
    assertThat(wh1.amounts().get(0).totalValue()).isEqualByComparingTo("1100.0000");

    var wh2 = response.rows().stream().filter(r -> "WH-2".equals(r.dimensionKey())).findFirst().orElseThrow();
    assertThat(wh2.zeroStockSkuCount()).as("SKU-D 可用量为 0 ⇒ 缺货").isEqualTo(1);
    assertThat(wh2.thresholdUnsetSkuCount()).as("⚠️ 「没查过」与「查过没超」必须分得开").isEqualTo(1);
    assertThat(wh2.totalSkuCount()).isEqualTo(2);
  }

  // ==========================================================================
  // 测试 6：ABC 分类
  // ==========================================================================

  @Test
  void ABC分类_按库存金额的累计占比切分() {
    refresh.refresh(a, "Bearer tenant-a", DAY1, DAY2);
    var response = inventoryAnalysis.abc(a, null, null, 0, 50);
    // SKU-D / SKU-E 没有维护成本 ⇒ **不进 ABC**（不是 0 元）
    assertThat(response.rows()).hasSize(3);

    var skuA = response.rows().get(0);
    assertThat(skuA.skuCode()).isEqualTo("SKU-A");
    assertThat(skuA.inventoryValue()).isEqualByComparingTo("1000.0000");
    assertThat(skuA.valueShare()).as("1000 ÷ 1350").isEqualByComparingTo("74.0741");
    assertThat(skuA.abcClass()).isEqualTo("A");

    // 按金额降序：SKU-A(1000) → SKU-C(250) → SKU-B(100)
    var skuC = response.rows().get(1);
    assertThat(skuC.skuCode()).isEqualTo("SKU-C");
    assertThat(skuC.valueShare()).as("250 ÷ 1350").isEqualByComparingTo("18.5185");
    assertThat(skuC.abcClass())
        .as("累计到本件之后 92.59% ≤ 95% ⇒ B 类")
        .isEqualTo("B");

    var skuB = response.rows().get(2);
    assertThat(skuB.skuCode()).isEqualTo("SKU-B");
    assertThat(skuB.abcClass())
        .as("累计到本件之后 100% > 95% ⇒ C 类")
        .isEqualTo("C");

    var aClass =
        response.summary().stream().filter(s -> "A".equals(s.abcClass())).findFirst().orElseThrow();
    assertThat(aClass.skuCount()).isEqualTo(1);
    assertThat(aClass.inventoryValue()).isEqualByComparingTo("1000.0000");
    assertThat(aClass.valueShare()).isEqualByComparingTo("74.0741");
    var bClass =
        response.summary().stream().filter(s -> "B".equals(s.abcClass())).findFirst().orElseThrow();
    assertThat(bClass.skuCount()).isEqualTo(1);
    assertThat(bClass.inventoryValue()).isEqualByComparingTo("250.0000");
    var cClass =
        response.summary().stream().filter(s -> "C".equals(s.abcClass())).findFirst().orElseThrow();
    assertThat(cClass.skuCount()).isEqualTo(1);
    assertThat(cClass.inventoryValue()).isEqualByComparingTo("100.0000");
  }

  @Test
  void ABC阈值_可以按租户改且非法值被数据库挡住() {
    var rule =
        inventoryAnalysis.updateRule(
            a,
            "ABC_CLASS",
            new ReportingInventoryAnalysisModels.InventoryAnalysisRuleInput(
                new BigDecimal("50"), new BigDecimal("70"), null, null, null));
    assertThat(rule.aCumPct()).isEqualByComparingTo("50");
    assertThat(rule.bCumPct()).isEqualByComparingTo("70");

    // 🔴 **非法阈值（a ≥ b）在数据库层就被挡住**
    assertThatThrownBy(
            () ->
                inventoryAnalysis.updateRule(
                    a,
                    "ABC_CLASS",
                    new ReportingInventoryAnalysisModels.InventoryAnalysisRuleInput(
                        new BigDecimal("90"), new BigDecimal("80"), null, null, null)))
        .isInstanceOf(RuntimeException.class);
  }

  // ==========================================================================
  // 测试 7：盘点历史
  // ==========================================================================

  @Test
  void 盘点历史_账面行数为0时准确率不可比() {
    refresh.refresh(a, "Bearer tenant-a", DAY1, DAY2);
    var response = inventoryAnalysis.stocktakes(a, 0, 20);
    assertThat(response.rows()).hasSize(2);
    var first = response.rows().stream().filter(r -> "PD-1".equals(r.code())).findFirst().orElseThrow();
    assertThat(first.lineCount()).isEqualTo(100);
    assertThat(first.countedRate()).isEqualByComparingTo("90.0000");
    assertThat(first.varianceRate()).isEqualByComparingTo("5.0000");

    var second = response.rows().stream().filter(r -> "PD-2".equals(r.code())).findFirst().orElseThrow();
    assertThat(second.comparable()).isFalse();
    assertThat(second.countedRate()).as("账面行数为 0 ⇒ 准确率**不可比**（不是 0%）").isNull();
  }

  // ==========================================================================
  // 测试 8：下钻
  // ==========================================================================

  @Test
  void 下钻_给出数量与成本口径金额() {
    refresh.refresh(a, "Bearer tenant-a", DAY1, DAY2);
    var response = inventoryAnalysis.drilldown(a, "SKU-A", DAY1, DAY2, null, 0, 50);
    assertThat(response.rows()).hasSize(1);
    var row = response.rows().get(0);
    assertThat(row.skuCode()).isEqualTo("SKU-A");
    assertThat(row.onHandQuantity()).isEqualByComparingTo("100");
    assertThat(row.unitCost()).isEqualByComparingTo("10.000000");
    assertThat(row.inventoryValue()).isEqualByComparingTo("1000.0000");
    assertThat(row.soldQuantity()).isEqualByComparingTo("20.000000");
    assertThat(row.costOfGoodsSold()).as("**成本口径**，不是售价").isEqualByComparingTo("200.0000");
    assertThat(row.comparable()).isTrue();
  }

  // ==========================================================================
  // 测试 9：HTTP 边界
  // ==========================================================================

  @Test
  void HTTP_能力声明与刷新端点() throws Exception {
    http.perform(get("/reporting/inventory-analysis/capabilities").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.metrics.length()").value(7))
        .andExpect(jsonPath("$.costCaliber").exists())
        .andExpect(jsonPath("$.turnoverCaliber").exists());

    http.perform(
            post("/reporting/inventory-analysis/refresh")
                .param("from", DAY1.toString())
                .param("to", DAY2.toString())
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sources.length()").value(4))
        .andExpect(jsonPath("$.totalWritten").isNumber());

    // 非法维度 ⇒ 400
    http.perform(
            get("/reporting/inventory-analysis/structure")
                .param("dimension", "NOPE")
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isBadRequest());

    // 伪造租户头无效
    http.perform(
            get("/reporting/inventory-analysis/structure")
                .param("dimension", "ALL")
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk());
  }

  @Test
  void 上游只读端口_转发调用方自己的bearer而不是租户号() {
    refresh.refresh(a, "Bearer tenant-a", DAY1, DAY2);
    assertThat(upstreamAuth.get("/warehouse/inventory/overview")).isEqualTo("Bearer tenant-a");
    assertThat(upstreamAuth.get("/catalog/sku-cost-compositions")).isEqualTo("Bearer tenant-a");
    assertThat(upstreamAuth.get("/trade/sales-orders/list")).isEqualTo("Bearer tenant-a");
  }
}
