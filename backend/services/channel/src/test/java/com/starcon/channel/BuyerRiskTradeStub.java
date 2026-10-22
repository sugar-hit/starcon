package com.starcon.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 16.5.1.2 测试用的**本地 trade 服务**。
 *
 * <p>这不是 mock：它是一个真的 HTTP 服务器，跑在真的 socket 上，真的按 trade 的路由形状回 JSON。
 * 之所以需要它，是因为本模块最要紧的几条不变量全都落在**跨服务那一跳**上：
 *
 * <ul>
 *   <li>「买家画像读不到 ⇒ 欺诈特征量不到」（不是 0 分）；
 *   <li>「退款数据读不到 ⇒ 退款率量不到」（不是「退款率 0」）；
 *   <li>「回写失败不许阻塞评分」；
 *   <li>「订单的收货地址快照才是买家身份与地址模式的来源」。
 * </ul>
 *
 * <p>把端口打桩成固定返回值的话，这几条就测不出来。
 */
final class BuyerRiskTradeStub implements AutoCloseable {
  private static final ObjectMapper JSON = new ObjectMapper();

  private final HttpServer server;
  private final String base;

  /** 买家画像（buyerKey → 一行）。 */
  final Map<String, Map<String, Object>> profiles = new ConcurrentHashMap<>();

  /** 订单（orderId → 一行）。 */
  final Map<String, Map<String, Object>> orders = new ConcurrentHashMap<>();

  /** 订单的地址快照（orderId → 一行）。 */
  final Map<String, Map<String, Object>> addresses = new ConcurrentHashMap<>();

  /** 发起过退款的订单 id。 */
  final List<String> refundedOrderIds = new ArrayList<>();

  /** 售后单（orderId → reasonCode）。 */
  final Map<String, String> caseReasons = new ConcurrentHashMap<>();

  /** 回写上来的风控信号（signalCode → 请求体）。 */
  final List<Map<String, Object>> receivedSignals = new ArrayList<>();

  // ---- 剧本开关 ----
  volatile boolean profilesForbidden = false;
  volatile boolean ordersForbidden = false;
  volatile boolean refundsForbidden = false;
  volatile boolean casesForbidden = false;
  volatile boolean signalForbidden = false;
  /** 让某一张订单的详情读不到（模拟「这笔订单的收货地址读不到」）。 */
  volatile String brokenOrderId = null;

  BuyerRiskTradeStub() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    base = "http://127.0.0.1:" + server.getAddress().getPort();
    server.createContext("/trade/order-review/buyer-profiles", this::buyerProfiles);
    server.createContext("/trade/sales-orders/list", this::salesOrders);
    server.createContext("/trade/sales-orders/detail", this::salesOrderDetail);
    server.createContext("/trade/refunds", this::refunds);
    server.createContext("/trade/aftersales/cases", this::aftersalesCases);
    server.createContext("/trade/orders/risk-validation/signals", this::riskSignals);
    server.setExecutor(null);
    server.start();
  }

  String base() {
    return base;
  }

  void reset() {
    profiles.clear();
    orders.clear();
    addresses.clear();
    refundedOrderIds.clear();
    caseReasons.clear();
    receivedSignals.clear();
    profilesForbidden = false;
    ordersForbidden = false;
    refundsForbidden = false;
    casesForbidden = false;
    signalForbidden = false;
    brokenOrderId = null;
  }

  // ================================================================ 剧本

  /** 登记一个租户自维护的买家画像。 */
  void putProfile(
      String buyerKey,
      String name,
      String phone,
      String email,
      String level,
      boolean blacklisted,
      String blacklistReason) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("buyerKey", buyerKey);
    row.put("buyerName", name);
    row.put("buyerPhone", phone);
    row.put("buyerEmail", email);
    row.put("buyerLevel", level);
    row.put("blacklisted", blacklisted);
    row.put("blacklistReason", blacklistReason);
    profiles.put(buyerKey, row);
  }

  /** 登记一张订单（带下单时刻）。 */
  void putOrder(String orderId, String orderNo, String orderSource, OffsetDateTime createdAt) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("id", orderId);
    row.put("orderNo", orderNo);
    row.put("orderSource", orderSource);
    row.put("createdAt", createdAt.toString());
    orders.put(orderId, row);
  }

  /** 登记一张订单的收货地址快照。 */
  void putShippingAddress(
      String orderId,
      String recipientName,
      String recipientPhone,
      String recipientEmail,
      String countryCode,
      String postcode,
      String city,
      String district,
      String addressLine1) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("addressRole", "SHIPPING");
    row.put("recipientName", recipientName);
    row.put("recipientPhone", recipientPhone);
    row.put("recipientEmail", recipientEmail);
    row.put("countryCode", countryCode);
    row.put("postcode", postcode);
    row.put("city", city);
    row.put("district", district);
    row.put("addressLine1", addressLine1);
    addresses.put(orderId, row);
  }

  void putRefund(String orderId) {
    refundedOrderIds.add(orderId);
  }

  void putCase(String orderId, String reasonCode) {
    caseReasons.put(orderId, reasonCode);
  }

  // ================================================================ 路由

  private void buyerProfiles(HttpExchange e) throws IOException {
    if (profilesForbidden) {
      json(e, 403, Map.of("message", "forbidden"));
      return;
    }
    List<Map<String, Object>> items = new ArrayList<>(profiles.values());
    json(
        e,
        200,
        Map.of(
            "items", items,
            "total", items.size(),
            "page", 0,
            "size", 200,
            "blacklistedCount", 0));
  }

  private void salesOrders(HttpExchange e) throws IOException {
    if (ordersForbidden) {
      json(e, 403, Map.of("message", "forbidden"));
      return;
    }
    String from = queryParam(e, "orderedFrom");
    OffsetDateTime since = null;
    if (from != null && !from.isBlank()) {
      try {
        since = OffsetDateTime.parse(from);
      } catch (RuntimeException ex) {
        // 🔴 解析不了就回 400，**不要让处理器抛出去** —— 处理器崩掉的话客户端拿到的是
        //    连接中断，测试里会被误读成「trade 服务不可达」，掩盖真正的参数问题。
        json(e, 400, Map.of("message", "bad orderedFrom: " + from));
        return;
      }
    }
    List<Map<String, Object>> items = new ArrayList<>();
    for (Map<String, Object> row : orders.values()) {
      if (since != null) {
        OffsetDateTime created = OffsetDateTime.parse(String.valueOf(row.get("createdAt")));
        if (created.isBefore(since)) continue;
      }
      items.add(row);
    }
    json(e, 200, Map.of("rows", items, "page", 0, "size", 200, "total", items.size()));
  }

  private void salesOrderDetail(HttpExchange e) throws IOException {
    String path = e.getRequestURI().getPath();
    String orderId = path.substring("/trade/sales-orders/detail/".length());
    if (orderId.contains("/")) {
      json(e, 404, Map.of("message", "no such path"));
      return;
    }
    if (orderId.equals(brokenOrderId)) {
      json(e, 404, Map.of("message", "order not found"));
      return;
    }
    Map<String, Object> address = addresses.get(orderId);
    if (address == null) {
      json(e, 200, Map.of("addresses", List.of()));
      return;
    }
    json(e, 200, Map.of("addresses", List.of(address)));
  }

  private void refunds(HttpExchange e) throws IOException {
    if (refundsForbidden) {
      json(e, 403, Map.of("message", "forbidden"));
      return;
    }
    List<Map<String, Object>> rows = new ArrayList<>();
    for (String orderId : refundedOrderIds) {
      rows.add(Map.of("id", UUID.randomUUID().toString(), "orderId", orderId, "orderNo", ""));
    }
    json(e, 200, Map.of("rows", rows, "page", 0, "size", 200, "total", rows.size()));
  }

  private void aftersalesCases(HttpExchange e) throws IOException {
    if (casesForbidden) {
      json(e, 403, Map.of("message", "forbidden"));
      return;
    }
    List<Map<String, Object>> rows = new ArrayList<>();
    for (Map.Entry<String, String> entry : caseReasons.entrySet()) {
      rows.add(
          Map.of(
              "id", UUID.randomUUID().toString(),
              "orderId", entry.getKey(),
              "reasonCode", entry.getValue(),
              "caseNo", ""));
    }
    json(e, 200, Map.of("rows", rows, "page", 0, "size", 200, "total", rows.size()));
  }

  private void riskSignals(HttpExchange e) throws IOException {
    if (signalForbidden) {
      json(e, 403, Map.of("message", "forbidden"));
      return;
    }
    byte[] raw = e.getRequestBody().readAllBytes();
    @SuppressWarnings("unchecked")
    Map<String, Object> body = JSON.readValue(raw, Map.class);
    receivedSignals.add(body);
    json(e, 200, Map.of("id", UUID.randomUUID().toString()));
  }

  // ================================================================ 工具

  private static String queryParam(HttpExchange e, String name) {
    String query = e.getRequestURI().getRawQuery();
    if (query == null) return null;
    for (String pair : query.split("&")) {
      if (pair.startsWith(name + "=")) {
        try {
          return URLDecoder.decode(pair.substring(name.length() + 1), StandardCharsets.UTF_8);
        } catch (RuntimeException ex) {
          return pair.substring(name.length() + 1);
        }
      }
    }
    return null;
  }

  private void json(HttpExchange e, int status, Object payload) throws IOException {
    byte[] bytes = JSON.writeValueAsBytes(payload);
    e.getResponseHeaders().add("Content-Type", "application/json");
    e.sendResponseHeaders(status, bytes.length);
    e.getResponseBody().write(bytes);
    e.close();
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
