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
 * 16.6.1.1 测试用的**本地 trade + crm 服务**。
 *
 * <p>这不是 mock：它是一个真的 HTTP 服务器，跑在真的 socket 上，真的按上游的路由形状回 JSON。
 * 之所以需要它，是因为本模块最要紧的几条不变量全都落在**跨服务那一跳**上：
 *
 * <ul>
 *   <li>「上游读不到 ⇒ 503，绝不返回空集合冒充『这个人没有 PII』」；
 *   <li>「上游正常但查无此人 ⇒ known=false（无记录 ≠ 无 PII）」；
 *   <li>「ORDER 主体上 crm 那个来源给不了电话与地址 ⇒ 标成『上游没给』而不是空值」；
 *   <li>「SELF 成员范围靠 crm 接触记录的 operator_id 判定；订单侧判不了」。
 * </ul>
 *
 * <p>把端口打桩成固定返回值的话，这几条就测不出来。
 */
final class PiiAccessUpstreamStub implements AutoCloseable {
  private static final ObjectMapper JSON = new ObjectMapper();

  private final HttpServer tradeServer;
  private final HttpServer crmServer;
  private final String tradeBase;
  private final String crmBase;

  // ---- trade 数据 ----

  /** 订单（orderId → 一行）。 */
  final Map<String, Map<String, Object>> orders = new ConcurrentHashMap<>();

  /** 订单的收货地址快照（orderId → 一行）。 */
  final Map<String, Map<String, Object>> addresses = new ConcurrentHashMap<>();

  // ---- crm 数据 ----

  /** 买家邮件（buyerRef → 一行）。 */
  final Map<String, Map<String, Object>> buyerEmails = new ConcurrentHashMap<>();

  /** 接触记录（customerRef → 一行，含 operatorId）。 */
  final Map<String, Map<String, Object>> contacts = new ConcurrentHashMap<>();

  // ---- 剧本开关 ----

  volatile boolean tradeForbidden = false;
  volatile boolean crmForbidden = false;
  /** 让 trade 整个服务不可达（模拟「上游挂了」）。 */
  volatile boolean tradeDown = false;
  /** 让 crm 整个服务不可达（模拟「上游挂了」）。 */
  volatile boolean crmDown = false;

  PiiAccessUpstreamStub() throws IOException {
    tradeServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    tradeBase = "http://127.0.0.1:" + tradeServer.getAddress().getPort();
    tradeServer.createContext("/trade/sales-orders/list", this::tradeOrders);
    tradeServer.createContext("/trade/sales-orders/detail", this::tradeOrderDetail);
    tradeServer.setExecutor(null);
    tradeServer.start();

    crmServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    crmBase = "http://127.0.0.1:" + crmServer.getAddress().getPort();
    crmServer.createContext("/crm/buyer-emails", this::crmBuyerEmails);
    crmServer.createContext("/crm/customer-contacts/contacts", this::crmContacts);
    crmServer.setExecutor(null);
    crmServer.start();
  }

  String tradeBase() {
    return tradeBase;
  }

  String crmBase() {
    return crmBase;
  }

  void reset() {
    orders.clear();
    addresses.clear();
    buyerEmails.clear();
    contacts.clear();
    tradeForbidden = false;
    crmForbidden = false;
    tradeDown = false;
    crmDown = false;
  }

  // ================================================================ 剧本

  /** 登记一张订单（带下单时刻）。 */
  void putOrder(String orderId, String orderNo, OffsetDateTime createdAt) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("id", orderId);
    row.put("orderNo", orderNo);
    row.put("orderSource", "MANUAL");
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
      String province,
      String city,
      String district,
      String postcode,
      String addressLine1) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("addressRole", "SHIPPING");
    row.put("recipientName", recipientName);
    row.put("recipientPhone", recipientPhone);
    row.put("recipientEmail", recipientEmail);
    row.put("companyName", "");
    row.put("countryCode", countryCode);
    row.put("province", province);
    row.put("city", city);
    row.put("district", district);
    row.put("postcode", postcode);
    row.put("addressLine1", addressLine1);
    row.put("addressLine2", "");
    addresses.put(orderId, row);
  }

  /** 登记一个买家的联系方式（crm）。 */
  void putBuyerEmail(String buyerRef, String buyerName, String buyerEmail) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("id", UUID.randomUUID().toString());
    row.put("buyerRef", buyerRef);
    row.put("buyerName", buyerName);
    row.put("buyerEmail", buyerEmail);
    row.put("salesOrderNo", "");
    buyerEmails.put(buyerRef, row);
  }

  /** 登记一条接触记录（crm），带经手人 operatorId。 */
  void putContact(String customerRef, String customerName, String operatorId) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("id", UUID.randomUUID().toString());
    row.put("customerRef", customerRef);
    row.put("customerName", customerName);
    row.put("operatorId", operatorId);
    contacts.put(customerRef + "|" + operatorId, row);
  }

  // ================================================================ trade 路由

  private void tradeOrders(HttpExchange e) throws IOException {
    if (tradeDown) {
      e.close();
      return;
    }
    if (tradeForbidden) {
      json(e, 403, Map.of("message", "forbidden"));
      return;
    }
    List<Map<String, Object>> items = new ArrayList<>(orders.values());
    json(e, 200, Map.of("rows", items, "page", 0, "size", 100, "total", items.size()));
  }

  private void tradeOrderDetail(HttpExchange e) throws IOException {
    if (tradeDown) {
      e.close();
      return;
    }
    if (tradeForbidden) {
      json(e, 403, Map.of("message", "forbidden"));
      return;
    }
    String path = e.getRequestURI().getPath();
    String orderId = path.substring("/trade/sales-orders/detail/".length());
    if (orderId.isEmpty() || orderId.contains("/")) {
      json(e, 404, Map.of("message", "no such path"));
      return;
    }
    if (!orders.containsKey(orderId)) {
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

  // ================================================================ crm 路由

  private void crmBuyerEmails(HttpExchange e) throws IOException {
    if (crmDown) {
      e.close();
      return;
    }
    if (crmForbidden) {
      json(e, 403, Map.of("message", "forbidden"));
      return;
    }
    String buyerRef = queryParam(e, "buyerRef");
    List<Map<String, Object>> items = new ArrayList<>();
    for (Map<String, Object> row : buyerEmails.values()) {
      if (buyerRef != null && !buyerRef.isBlank() && !buyerRef.equals(row.get("buyerRef"))) {
        continue;
      }
      items.add(row);
    }
    json(
        e,
        200,
        Map.of(
            "items", items,
            "page", 0,
            "size", 100,
            "totalElements", items.size(),
            "totalPages", 1));
  }

  private void crmContacts(HttpExchange e) throws IOException {
    if (crmDown) {
      e.close();
      return;
    }
    if (crmForbidden) {
      json(e, 403, Map.of("message", "forbidden"));
      return;
    }
    String customerRef = queryParam(e, "customerRef");
    List<Map<String, Object>> items = new ArrayList<>();
    for (Map<String, Object> row : contacts.values()) {
      if (customerRef != null && !customerRef.isBlank() && !customerRef.equals(row.get("customerRef"))) {
        continue;
      }
      items.add(row);
    }
    json(
        e,
        200,
        Map.of(
            "items", items,
            "page", 0,
            "size", 100,
            "totalElements", items.size(),
            "totalPages", 1));
  }

  // ================================================================ 工具

  private static String queryParam(HttpExchange e, String name) {
    String query = e.getRequestURI().getRawQuery();
    if (query == null) {
      return null;
    }
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
    tradeServer.stop(0);
    crmServer.stop(0);
  }
}
