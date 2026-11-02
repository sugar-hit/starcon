package com.starcon.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/**
 * 18.4.1.1「对话助手」集成测试的**夹具**：一个真的 HTTP 服务器（身份服务 + 四个上游只读服务）。
 *
 * <p>🔴 <b>为什么不用 mock 框架</b>：本模块的核心承诺是「**真的去查了上游，答案里的数字来自
 * 上游响应，明细链接指向真实存在的页面**」。打桩的端口返回常量数组，等于把「查没查」
 * 这件事从测试里删掉了 —— 那样测出来的绿是没有意义的。
 *
 * <p>🔴 <b>上游数据按租户分</b>：夹具按 {@code Authorization} 头（= 调用方自己的会话）返回
 * **不同租户的不同事实**（A 租户的店是 SHOP-A，B 租户的店是 SHOP-B）。
 * 这让「A 租户的问句不能查到 B 租户的数据」成为一条**可证伪**的断言，
 * 而不是靠「上游本来就隔离」这种口头保证。
 *
 * <p>⚠️ 夹具只提供**上游**的事实；本模块自己的表（意图目录 / 会话 / 问答 / 依据）
 * 全部落在**真实的 Testcontainers PostgreSQL 16** 里，租户隔离由 FORCE RLS 强制。
 */
final class AssistantTestStub {

  /** 一个租户在上游那边的样例事实。 */
  record TenantFacts(
      String shopCode,
      String shopName,
      String skuCode,
      String skuName,
      String customerRef,
      String customerName,
      String salesOrderNo) {

    /** 另一个（更差一些的）店铺，用来验证「第二名」追问。 */
    static TenantFacts withSecondShop(TenantFacts base) {
      return new TenantFacts(
          base.shopCode(),
          base.shopName(),
          base.skuCode(),
          base.skuName(),
          base.customerRef(),
          base.customerName(),
          base.salesOrderNo());
    }
  }

  private static final ObjectMapper JSON =
      new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

  private final HttpServer server;
  private final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  private final Map<String, TenantFacts> facts = new ConcurrentHashMap<>();
  private final Map<String, Integer> calls = new ConcurrentHashMap<>();

  AssistantTestStub() throws IOException {
    server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext("/internal/access", this::handleAccess);
    server.createContext("/reporting/profit-report/report", e -> respond(e, profitReport()));
    server.createContext("/reporting/inventory-analysis/drilldown", e -> respond(e, inventoryDrilldown()));
    server.createContext("/reporting/sales-trend", e -> respond(e, salesTrend()));
    server.createContext("/reporting/lifecycle-segmentation", e -> respond(e, lifecycle()));
    server.createContext("/finance/fund-analysis/cash-flow-trend", e -> respond(e, fundFlow()));
    server.createContext("/trade/fulfillment/pending-shipment/units", e -> respond(e, pendingUnits()));
    server.createContext("/crm/customer-lifecycle/alerts", e -> respond(e, churnAlerts()));
    server.setExecutor(Executors.newFixedThreadPool(4));
  }

  void start() {
    server.start();
  }

  /** 夹具的地址（四个上游服务都指这里 —— 路径互不相同）。 */
  String url() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  /** 登记一个已认证会话（bearer → 租户 + 权限）。 */
  void actor(String bearer, AccessService.Access access) {
    actors.put(bearer, access);
  }

  /** 登记一个租户在上游的样例事实。 */
  void facts(String bearer, TenantFacts facts) {
    this.facts.put(bearer, facts);
  }

  /** 某个上游路径被调过几次（用来证明「真的去查了上游」）。 */
  int calls(String path) {
    return calls.getOrDefault(path, 0);
  }

  /** 重置调用计数。 */
  void resetCalls() {
    calls.clear();
  }

  // ================================================================ 身份服务

  private void handleAccess(HttpExchange exchange) throws IOException {
    var bearer = exchange.getRequestHeaders().getFirst("Authorization");
    var access = bearer == null ? null : actors.get(bearer);
    if (access == null) {
      exchange.sendResponseHeaders(401, -1);
      exchange.close();
      return;
    }
    var body = JSON.writeValueAsBytes(access);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, body.length);
    exchange.getResponseBody().write(body);
    exchange.close();
  }

  // ================================================================ 上游只读端点

  private void respond(HttpExchange exchange, String body) throws IOException {
    var path = exchange.getRequestURI().getPath();
    calls.merge(path, 1, Integer::sum);
    var bearer = exchange.getRequestHeaders().getFirst("Authorization");
    var tenant = facts.get(bearer);
    if (tenant == null) {
      // ⚠️ **上游读不到**（不是「上游没有数据」）：返回 503，
      //   让平台侧如实翻成「读不到」，而不是返回空集合冒充「没有数据」。
      var error = "{\"message\":\"上游事实读不到\"}".getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(503, error.length);
      exchange.getResponseBody().write(error);
      exchange.close();
      return;
    }
    var bytes = body.replace("{{SHOP}}", tenant.shopCode())
        .replace("{{SHOP_NAME}}", tenant.shopName())
        .replace("{{SKU}}", tenant.skuCode())
        .replace("{{SKU_NAME}}", tenant.skuName())
        .replace("{{CUSTOMER}}", tenant.customerRef())
        .replace("{{CUSTOMER_NAME}}", tenant.customerName())
        .replace("{{ORDER}}", tenant.salesOrderNo())
        .getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }

  /** 多模式利润报表（店铺维度）—— 三行，第一行毛利最低。 */
  private String profitReport() {
    return """
        {
          "from": "2026-10-26", "to": "2026-11-01", "granularity": "WEEK", "dimension": "SHOP",
          "rows": [
            {"dimension": "SHOP", "dimensionCode": "{{SHOP}}", "dimensionLabel": "{{SHOP_NAME}} 店",
             "operatingMode": "SELF", "operatingModeLabel": "自营",
             "periodStart": "2026-10-26", "periodEnd": "2026-11-01", "currency": "USD",
             "totalCost": 120.0000, "grossProfit": -12.3000, "grossMargin": -0.1230,
             "grossProfitComparable": true, "missingComponents": [], "factCount": 4, "note": ""},
            {"dimension": "SHOP", "dimensionCode": "{{SHOP}}-2", "dimensionLabel": "{{SHOP_NAME}} 第二店",
             "operatingMode": "SELF", "operatingModeLabel": "自营",
             "periodStart": "2026-10-26", "periodEnd": "2026-11-01", "currency": "USD",
             "totalCost": 80.0000, "grossProfit": -3.5000, "grossMargin": -0.0350,
             "grossProfitComparable": true, "missingComponents": [], "factCount": 2, "note": ""},
            {"dimension": "SHOP", "dimensionCode": "{{SHOP}}-3", "dimensionLabel": "{{SHOP_NAME}} 第三店",
             "operatingMode": "SELF", "operatingModeLabel": "自营",
             "periodStart": "2026-10-26", "periodEnd": "2026-11-01", "currency": "USD",
             "totalCost": 60.0000, "grossProfit": 9.9000, "grossMargin": 0.0990,
             "grossProfitComparable": true, "missingComponents": ["平台费"], "factCount": 3, "note": ""}
          ],
          "dataUpdatedAt": "2026-11-01T10:00:00Z", "dataUpdatedNote": "最近一次投影落库时刻",
          "caliberNote": "毛利额 = 收入 − 各成本项之和；缺成本项的行不可比", "warnings": []
        }
        """;
  }

  /** 库存下钻（SKU 级）。 */
  private String inventoryDrilldown() {
    return """
        {
          "asOf": "2026-11-01", "from": "2026-10-26", "to": "2026-11-01",
          "rows": [
            {"skuCode": "{{SKU}}", "skuName": "{{SKU_NAME}}", "currency": "USD", "categoryCode": "CAT-1",
             "warehouseCode": "WH-1", "warehouseName": "主仓", "shopCode": "{{SHOP}}", "shopName": "{{SHOP_NAME}}",
             "onHandQuantity": 10.000000, "reservedQuantity": 1.000000, "availableQuantity": 9.000000,
             "unitCost": 3.500000, "inventoryValue": 31.500000, "soldQuantity": 20.000000,
             "costOfGoodsSold": 70.000000, "zeroStock": false, "overstock": false,
             "thresholdConfigured": true, "abcClass": "A", "abcClassLabel": "A 类",
             "comparable": true, "note": ""}
          ],
          "total": 1, "page": 0, "size": 200,
          "dataUpdatedAt": "2026-11-01T10:00:00Z", "dataUpdatedNote": "投影落库时刻",
          "caliberNote": "库存金额按成本口径", "warnings": []
        }
        """;
  }

  /** 商品维度销量趋势。 */
  private String salesTrend() {
    return """
        {
          "from": "2026-10-26", "to": "2026-11-01", "granularity": "DAY", "timezone": "UTC",
          "series": [
            {"metricCode": "PRODUCT_SALES_QUANTITY", "displayName": "商品销量", "unit": "QUANTITY",
             "dimensionType": "PRODUCT", "dimensionKey": "{{SKU}}", "dimensionLabel": "{{SKU_NAME}}",
             "currency": "", "currencyAware": false,
             "points": [
               {"bucketStart": "2026-10-28", "bucketEnd": "2026-10-28", "bucketLabel": "10-28",
                "currency": "", "value": 12.000000},
               {"bucketStart": "2026-10-30", "bucketEnd": "2026-10-30", "bucketLabel": "10-30",
                "currency": "", "value": 8.000000}
             ]}
          ],
          "warnings": [], "status": "PROJECTED", "emptyReason": null, "emptyNote": ""
        }
        """;
  }

  /** 生命周期与分层（商品维度）。 */
  private String lifecycle() {
    return """
        {
          "from": "2026-10-26", "to": "2026-11-01", "windowMatched": true, "dimensionType": "SKU",
          "ruleCode": "DEFAULT", "ruleVersion": 1,
          "rows": [
            {"dimensionType": "SKU", "dimensionId": "11111111-1111-1111-1111-111111111111",
             "dimensionKey": "{{SKU}}", "dimensionName": "{{SKU_NAME}}", "bucketDate": "2026-11-01",
             "salesQuantity": 10.000000, "recentQuantity": 4.000000, "priorQuantity": 20.000000,
             "declineRatio": 0.8000, "salesShare": 0.1000, "lifecycleSegment": "DECLINE",
             "segmentAvailability": "AVAILABLE", "segmentNote": "", "recommendedAction": "清仓",
             "actionNote": "", "onHandQuantity": 5.000000, "agingDays": 30,
             "sourceObservedAt": "2026-11-01T10:00:00Z", "amounts": []}
          ],
          "page": 0, "size": 200, "total": 1, "archivedProductCount": 0, "notSegmentedProductCount": 0,
          "coverageNote": "", "caliberNotes": ["下行比 = 1 − 近期销量 ÷ 前期销量"], "rules": [],
          "dataUpdatedAt": "2026-11-01T10:00:00Z", "dataUpdatedNote": "投影落库时刻",
          "status": "PROJECTED", "statusNote": "", "emptyReason": null, "emptyNote": ""
        }
        """;
  }

  /** 现金流入流出趋势。 */
  private String fundFlow() {
    return """
        {
          "fromDate": "2026-10-26", "toDate": "2026-11-01", "granularity": "MONTH",
          "asOfDate": "2026-11-01",
          "actualOutflowBasis": "上游只提供了实际流入的时间序列，实际流出没有序列",
          "actualOutflowOwnerModule": "12.2.2.1",
          "buckets": [
            {"periodStart": "2026-10-26", "currency": "USD",
             "actualInflowAmount": 1000.0000, "expectedInflowAmount": 200.0000,
             "plannedOutflowAmount": 800.0000, "netExpectedAmount": 200.0000,
             "actualInflowCount": 5, "expectedInflowCount": 2, "plannedOutflowCount": 3}
          ],
          "totals": [
            {"currency": "USD", "actualInflowAmount": 1000.0000, "expectedInflowAmount": 200.0000,
             "plannedOutflowAmount": 800.0000, "netExpectedAmount": 200.0000}
          ]
        }
        """;
  }

  /** 待发货单元清单。 */
  private String pendingUnits() {
    return """
        {
          "items": [
            {"id": "22222222-2222-2222-2222-222222222222",
             "sourcePackingOrderId": "33333333-3333-3333-3333-333333333333",
             "sourceOutboundOrderId": "44444444-4444-4444-4444-444444444444",
             "warehouseRef": "WH-1", "warehouseId": "55555555-5555-5555-5555-555555555555",
             "outboundOrderNo": "OUT-{{SHOP}}", "salesOrderId": "66666666-6666-6666-6666-666666666666",
             "salesOrderNo": "{{ORDER}}", "shopRef": "{{SHOP}}", "buyerName": "买家",
             "destinationCountry": "US", "carrierChannel": "CARRIER", "carrierCode": "DHL",
             "slaBucket": "OVERDUE", "slaSource": "RULE", "parcelCount": 2,
             "totalWeightKg": 1.500000, "status": "PENDING", "waybillNo": "", "labelPrinted": false,
             "packedAt": "2026-10-30T08:00:00Z", "slaDeadline": "2026-10-31T08:00:00Z",
             "handedOverAt": null, "syncedAt": "2026-10-30T09:00:00Z", "nextActions": "交接承运商"}
          ],
          "page": 0, "size": 50, "totalElements": 1, "totalPages": 1
        }
        """;
  }

  /** 流失预警清单。 */
  private String churnAlerts() {
    return """
        {
          "rows": [
            {"id": "77777777-7777-7777-7777-777777777777", "customerRef": "{{CUSTOMER}}",
             "sourceShop": "{{SHOP}}", "customerName": "{{CUSTOMER_NAME}}", "lastOrderAt": "2026-08-01",
             "daysSinceLastOrder": 90, "expectedRepurchaseDays": 30, "toleranceDays": 7,
             "overdueDays": 53, "thresholdSource": "GLOBAL", "thresholdSourceLabel": "全局兜底",
             "categoryCode": "CAT-1", "customerBaselineDays": 30, "customerBaselineSamples": 5,
             "riskScore": 88.50, "riskLevel": "HIGH", "riskLevelLabel": "高风险", "riskFactors": [],
             "recallSuggestion": "发短信召回", "suggestionOwner": "15.5.2",
             "suggestionOwnerLabel": "客户成功", "status": "OPEN", "statusLabel": "待处理",
             "statusNote": "", "detectedAt": "2026-11-01T10:00:00Z"}
          ],
          "page": 0, "size": 50, "total": 1, "totalPages": 1, "note": "风险分是规则分，不是模型置信度"
        }
        """;
  }

  /** 夹具用到的权限码。 */
  static List<String> permissions() {
    return List.of("platform.assistant.read", "platform.assistant.create");
  }
}
