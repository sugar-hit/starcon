package com.starcon.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/**
 * 18.4.1.2「智能查询」集成测试的**夹具**：一个真的 HTTP 服务器（身份服务 + 13.6.1.1 自定义报表）。
 *
 * <p>🔴 <b>为什么不用 mock 框架</b>：本模块的核心承诺是「**真的去查了受控语义层，
 * 结果表里的数字来自下游 13.6.1.1 的真实响应，明细链接指向真实存在的页面**」。
 * 打桩的端口返回常量数组，等于把「查没查」这件事从测试里删掉了 ——
 * 那样测出来的绿是没有意义的。
 *
 * <p>🔴 <b>上游数据按租户分</b>：夹具按 {@code Authorization} 头（= 调用方自己的会话）
 * 返回**不同租户的不同事实**。这让「A 租户的查询不能查到 B 租户的数据」成为一条
 * <b>可证伪</b>的断言，而不是靠「上游本来就隔离」这种口头保证。
 *
 * <p>⚠️ 夹具只提供**上游**的事实；本模块自己的四张表（语义层词表 / 查询台账 / 明细依据）
 * 全部落在**真实的 Testcontainers PostgreSQL 16** 里，租户隔离由 FORCE RLS 强制。
 *
 * <p>⚠️ 夹具模拟的是 13.6.1.1 的**真实契约**：建草稿会回显请求里的度量与维度，
 * 跑报表会按那份配置产出行 —— 于是「口径真的传下去了」这件事是可断言的。
 */
final class SmartQueryTestStub {

  /**
   * 一个租户在上游那边的样例事实（店铺 + 销售额 + 一个「算不出来」的格子）。
   *
   * @param shopThreeId 那个**算不出来**的店铺 —— 🔴 它必须**属于本租户**：
   *     事实引用（fact_ref）里带着店铺 uuid，租户隔离断言就是拿它比对「A 看得见的事实引用
   *     与 B 的**没有任何一条相同**」⇒ 用一个与租户无关的固定 id 会让两个租户出现
   *     **一模一样**的事实引用，断言就失去意义了。
   */
  record TenantFacts(
      String shopOneId,
      String shopOneName,
      String shopTwoId,
      String shopTwoName,
      String shopThreeId,
      String shopThreeName,
      String currency,
      String topAmount,
      String secondAmount) {}

  private static final ObjectMapper JSON =
      new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

  private final HttpServer server;
  private final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  private final Map<String, TenantFacts> facts = new ConcurrentHashMap<>();
  private final Map<String, Integer> calls = new ConcurrentHashMap<>();
  /** 报表编码 → 建草稿时传上来的配置（证明「口径真的传下去了」）。 */
  private final Map<String, String> reportConfigs = new ConcurrentHashMap<>();

  /** 报表 id → 报表编码（跑报表时按 id 找回那份配置）。 */
  private final Map<String, String> reportCodes = new ConcurrentHashMap<>();

  /** 某个租户的口径表（登记了哪些指标）。 */
  private final Map<String, List<String>> registeredMetrics = new ConcurrentHashMap<>();

  SmartQueryTestStub() throws IOException {
    server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext("/internal/access", this::handleAccess);
    server.createContext("/reporting/custom-report/metrics", this::handleMetrics);
    server.createContext("/reporting/custom-report/dimensions", this::handleDimensions);
    server.createContext("/reporting/custom-report/reports", this::handleReports);
    server.setExecutor(Executors.newFixedThreadPool(4));
  }

  void start() {
    server.start();
  }

  /** 夹具的地址（身份服务与 reporting 服务都指这里 —— 路径互不相同）。 */
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

  /** 登记某个租户的口径表（登记了哪些指标码）。 */
  void registered(String bearer, List<String> metricCodes) {
    registeredMetrics.put(bearer, metricCodes);
  }

  /** 某个上游路径被调过几次（用来证明「真的去查了上游」）。 */
  int calls(String path) {
    return calls.getOrDefault(path, 0);
  }

  /** 重置调用计数。 */
  void resetCalls() {
    calls.clear();
  }

  /** 建草稿时传上来的配置（度量 / 维度 / 筛选）。 */
  String reportConfig(String reportCode) {
    return reportConfigs.get(reportCode);
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
    respond(exchange, 200, JSON.writeValueAsString(access));
  }

  // ================================================================ 13.6.1.1 的口径目录

  private void handleMetrics(HttpExchange exchange) throws IOException {
    count(exchange);
    var bearer = exchange.getRequestHeaders().getFirst("Authorization");
    var codes = registeredMetrics.get(bearer);
    if (codes == null) {
      // ⚠️ **上游读不到**（不是「上游没有数据」）：返回 503。
      respond(exchange, 503, "{\"message\":\"口径目录读不到\"}");
      return;
    }
    var sb = new StringBuilder("[");
    for (int index = 0; index < codes.size(); index++) {
      if (index > 0) sb.append(",");
      sb.append(metricJson(codes.get(index)));
    }
    respond(exchange, 200, sb.append("]").toString());
  }

  private void handleDimensions(HttpExchange exchange) throws IOException {
    count(exchange);
    respond(
        exchange,
        200,
        """
        [
          {"dimensionCode":"SCOPE_TYPE","label":"公司 / 法人 / 店铺","valueKind":"TEXT","note":""},
          {"dimensionCode":"LEGAL_ENTITY","label":"法人（uuid）","valueKind":"UUID","note":""},
          {"dimensionCode":"SHOP","label":"店铺（uuid）","valueKind":"UUID","note":""},
          {"dimensionCode":"BUCKET_DATE","label":"日历日","valueKind":"DATE","note":""},
          {"dimensionCode":"CURRENCY","label":"币种","valueKind":"TEXT","note":""}
        ]
        """);
  }

  // ================================================================ 13.6.1.1 的报表配置草稿

  private void handleReports(HttpExchange exchange) throws IOException {
    count(exchange);
    var method = exchange.getRequestMethod();
    var path = exchange.getRequestURI().getPath();
    var body = readBody(exchange);

    if ("POST".equals(method) && path.endsWith("/reports")) {
      var config = extract(body);
      var reportCode = field(config, "reportCode");
      reportConfigs.put(reportCode, config);
      var reportId = UUID.nameUUIDFromBytes(reportCode.getBytes(StandardCharsets.UTF_8)).toString();
      reportCodes.put(reportId, reportCode);
      respond(
          exchange,
          200,
          """
          {"reportId":"%s","reportCode":"%s","displayName":"%s","description":"",
           "visibility":"PRIVATE","chartType":"TABLE","windowFrom":null,"windowTo":null,
           "measures":[],"dimensions":[],"filters":[],"ownerUserId":"","createdAt":"","updatedAt":""}
          """
              .formatted(reportId, reportCode, field(config, "displayName")));
      return;
    }
    if ("PUT".equals(method)) {
      var reportId = path.substring(path.lastIndexOf('/') + 1);
      var config = extract(body);
      var reportCode = field(config, "reportCode");
      reportConfigs.put(reportCode, config);
      reportCodes.put(reportId, reportCode);
      respond(
          exchange,
          200,
          """
          {"reportId":"%s","reportCode":"%s","displayName":"%s","description":"",
           "visibility":"PRIVATE","chartType":"TABLE","windowFrom":null,"windowTo":null,
           "measures":[],"dimensions":[],"filters":[],"ownerUserId":"","createdAt":"","updatedAt":""}
          """
              .formatted(reportId, reportCode, field(config, "displayName")));
      return;
    }
    if ("POST".equals(method) && path.endsWith("/run")) {
      // 🔴 路径形状 = 13.6.1.1 的真实契约：/reporting/custom-report/reports/{reportId}/run
      //   ⇒ 取倒数第二个 '/' 与最后一个 '/' 之间的那一段（**整个 reportId**）。
      var lastSlash = path.lastIndexOf('/');
      var reportId = path.substring(path.lastIndexOf('/', lastSlash - 1) + 1, lastSlash);
      var bearer = exchange.getRequestHeaders().getFirst("Authorization");
      var tenant = facts.get(bearer);
      if (tenant == null) {
        respond(exchange, 503, "{\"message\":\"事实读不到\"}");
        return;
      }
      var reportCode = reportCodes.getOrDefault(reportId, "");
      var config = reportConfigs.getOrDefault(reportCode, "{}");
      respond(exchange, 200, runJson(reportId, reportCode, config, tenant));
      return;
    }
    respond(exchange, 404, "{\"message\":\"没有这个端点\"}");
  }

  // ================================================================ 响应体

  private String metricJson(String code) {
    var name = metricName(code);
    return """
        {"metricCode":"%s","displayName":"%s","unit":"%s","aggregation":"%s",
         "numeratorMetricCode":null,"denominatorMetricCode":null,"currencyAware":%s,
         "description":"上游登记的口径说明","ownerModule":"9.1.1.1"}
        """
        .formatted(code, name, unitOf(code), aggregationOf(code), currencyAware(code));
  }

  private static String metricName(String code) {
    return switch (code) {
      case "SALES_AMOUNT" -> "销售额";
      case "ORDER_COUNT" -> "订单量";
      case "UNIT_SOLD" -> "销量";
      case "COGS_AMOUNT" -> "成本";
      case "GROSS_PROFIT" -> "毛利额";
      case "GROSS_MARGIN" -> "毛利率";
      case "AD_SPEND" -> "广告花费";
      case "RETURNED_AMOUNT" -> "退货额";
      case "RETURN_RATE" -> "退货率";
      case "NET_PROFIT" -> "净利";
      case "INVENTORY_VALUE" -> "库存总额";
      case "RECEIVABLE_BALANCE" -> "应收余额";
      default -> code;
    };
  }

  private static String unitOf(String code) {
    return switch (code) {
      case "ORDER_COUNT" -> "COUNT";
      case "UNIT_SOLD" -> "QUANTITY";
      case "GROSS_MARGIN", "RETURN_RATE" -> "PERCENT";
      default -> "CURRENCY";
    };
  }

  private static String aggregationOf(String code) {
    return switch (code) {
      case "GROSS_PROFIT" -> "DERIVED_DIFF";
      case "GROSS_MARGIN", "RETURN_RATE" -> "DERIVED_RATIO";
      case "INVENTORY_VALUE", "RECEIVABLE_BALANCE" -> "LAST";
      default -> "SUM";
    };
  }

  private static boolean currencyAware(String code) {
    return !java.util.Set.of("ORDER_COUNT", "UNIT_SOLD").contains(code);
  }

  /** 跑报表的响应：**按建草稿时传下来的度量与维度**产出行。 */
  private String runJson(String reportId, String reportCode, String config, TenantFacts tenant) {
    var measure = extractArray(config, "measureMetricCodes");
    var metricCode = measure.isEmpty() ? "SALES_AMOUNT" : measure.get(0);
    var dimensions = extractArray(config, "dimensionCodes");
    var byShop = dimensions.contains("SHOP");

    // ⚠️ 行与行之间必须有逗号 —— 否则拼出来的不是合法 JSON，
    //   上游响应解析就会失败（而不是「查不到数据」），那会把夹具的 bug 伪装成模块的 bug。
    var rowJsonList = new java.util.ArrayList<String>();
    if (byShop) {
      // ⚠️ **故意乱序返回**（第二店 → 算不出来的那行 → 第一店）——
      //   排序必须是**真的排了**，而不是恰好按上游顺序显示。
      rowJsonList.add(
          rowJson(tenant.shopTwoId(), metricCode, tenant.currency(), tenant.secondAmount(), "AVAILABLE", ""));
      // 🔴 有一行**算不出来**（value = null，不是 0）—— 「压根算不出来」与「算出来是零」是两件事。
      //   这一行仍然属于**本租户**（店铺 uuid 是租户自己的）⇒ 事实引用带着租户标识。
      rowJsonList.add(
          rowJson(tenant.shopThreeId(), metricCode, tenant.currency(), null, "NOT_COMPARABLE", "分母为 0，比率算不出来"));
      rowJsonList.add(
          rowJson(tenant.shopOneId(), metricCode, tenant.currency(), tenant.topAmount(), "AVAILABLE", ""));
    } else {
      rowJsonList.add(rowJson("", metricCode, tenant.currency(), tenant.topAmount(), "AVAILABLE", ""));
    }
    var rows = String.join(",", rowJsonList);

    return """
        {"reportId":"%s","reportCode":"%s","displayName":"智能查询草稿","description":"",
         "chartType":"TABLE","from":"2026-10-26","to":"2026-11-01","dimensions":[],
         "measures":[%s],
         "rows":[%s],
         "total":%d,"page":0,"size":200,
         "caliberNotes":["口径全部取自 reporting_metric_definition（13.1.1.1 经营总览的口径唯一表）"],
         "dataUpdatedAt":"2026-11-01T10:00:00Z","dataUpdatedNote":"最近一次投影落库时刻",
         "status":"AVAILABLE","statusNote":"","emptyReason":null,"emptyNote":""}
        """
        .formatted(reportId, reportCode, metricJson(metricCode), rows, byShop ? 3 : 1);
  }

  private String rowJson(
      String dimensionValue,
      String metricCode,
      String currency,
      String amount,
      String availability,
      String availabilityNote) {
    var dimensionCell =
        dimensionValue.isEmpty()
            ? ""
            : """
                {"dimensionCode":"SHOP","label":"店铺（uuid）","value":"%s"}
                """.formatted(dimensionValue);
    var value = amount == null ? "null" : amount;
    return """
        {"dimensions":[%s],
         "measures":[{"metricCode":"%s","displayName":"%s","unit":"%s","currencyAware":%s,
           "values":[{"currency":"%s","value":%s,"availability":"%s","availabilityNote":"%s"}],
           "availability":"%s","availabilityNote":"%s"}]}
        """
        .formatted(
            dimensionCell,
            metricCode,
            metricName(metricCode),
            unitOf(metricCode),
            currencyAware(metricCode),
            currency,
            value,
            availability,
            availabilityNote,
            availability,
            availabilityNote);
  }

  // ================================================================ 工具

  private static String readBody(HttpExchange exchange) throws IOException {
    var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    return body == null ? "" : body;
  }

  /** 从建草稿的请求体里抠出字段（夹具只要证明「口径传下去了」）。 */
  private static String field(String config, String name) {
    var marker = "\"" + name + "\"";
    var at = config.indexOf(marker);
    if (at < 0) return "";
    var colon = config.indexOf(':', at + marker.length());
    if (colon < 0) return "";
    var rest = config.substring(colon + 1).trim();
    if (!rest.startsWith("\"")) return "";
    var end = rest.indexOf('"', 1);
    return end < 0 ? "" : rest.substring(1, end);
  }

  /** 从建草稿的请求体里抠出一个字符串数组。 */
  private static List<String> extractArray(String config, String name) {
    var out = new java.util.ArrayList<String>();
    var marker = "\"" + name + "\"";
    var at = config.indexOf(marker);
    if (at < 0) return out;
    var open = config.indexOf('[', at);
    if (open < 0) return out;
    var close = config.indexOf(']', open);
    if (close < 0) return out;
    var inner = config.substring(open + 1, close);
    var matcher = java.util.regex.Pattern.compile("\"([^\"]*)\"").matcher(inner);
    while (matcher.find()) {
      out.add(matcher.group(1));
    }
    return out;
  }

  private static String extract(String body) {
    return body == null ? "" : body;
  }

  private void count(HttpExchange exchange) {
    calls.merge(exchange.getRequestURI().getPath(), 1, Integer::sum);
  }

  private static void respond(HttpExchange exchange, int status, String body) throws IOException {
    var bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }

  /** 夹具用到的权限码。 */
  static List<String> permissions() {
    return List.of("platform.smart_query.read", "platform.smart_query.create", "platform.smart_query.update");
  }

  /** 夹具默认登记的标准指标（与 13.1.1.1 的标准口径一致）。 */
  static List<String> standardMetrics() {
    return List.of(
        "SALES_AMOUNT",
        "ORDER_COUNT",
        "UNIT_SOLD",
        "COGS_AMOUNT",
        "GROSS_PROFIT",
        "GROSS_MARGIN",
        "AD_SPEND",
        "RETURNED_AMOUNT",
        "RETURN_RATE",
        "NET_PROFIT",
        "INVENTORY_VALUE",
        "RECEIVABLE_BALANCE");
  }
}
