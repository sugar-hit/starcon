package com.starcon.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 16.2.2.2 认证与资质测试用的**本地 catalog 服务**。
 *
 * <p>这不是 mock：它是一个真的 HTTP 服务器，跑在真的 socket 上，真的按 catalog 的路由形状回 JSON。
 * 之所以需要它，是因为本模块的一条核心不变量全在**跨服务那一跳**上 ——
 * 「SKU 与类目主数据在 catalog；读不到的时候必须**显式报读不到**，绝不拿空列表冒充」。
 * 把端口打桩成固定返回值的话，那条不变量就测不出来。
 *
 * <p>可切换的剧本让不同失败原因都能被真实触发：
 *
 * <ul>
 *   <li>{@link #listStatus} = 500 ⇒ SKU 列表读不到 ⇒ 候选端点必须 503（**不是**空列表）；
 *   <li>{@link #detailStatus} = 500 ⇒ SKU 详情读不到 ⇒ 挂 SKU / 登记要求必须明确报错；
 *   <li>{@link #treesStatus} = 500 ⇒ 类目树读不到 ⇒ 同样必须 503。
 * </ul>
 */
final class ProductCertificationCatalogStub implements AutoCloseable {
  private static final ObjectMapper JSON = new ObjectMapper();

  private final HttpServer server;
  private final String base;

  /** SKU id → SKU 行。 */
  final Map<UUID, Map<String, Object>> skus = new ConcurrentHashMap<>();

  /** 类目树 id → 树行。 */
  final Map<UUID, Map<String, Object>> trees = new ConcurrentHashMap<>();

  /** 类目节点 id → 节点行。 */
  final Map<UUID, Map<String, Object>> nodes = new ConcurrentHashMap<>();

  /** SKU 列表端点的 HTTP 状态（0 = 正常；500 = 上游故障）。 */
  volatile int listStatus = 0;

  /** SKU 详情端点的 HTTP 状态（0 = 正常；500 = 上游故障）。 */
  volatile int detailStatus = 0;

  /** 类目树端点的 HTTP 状态（0 = 正常；500 = 上游故障）。 */
  volatile int treesStatus = 0;

  ProductCertificationCatalogStub() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    base = "http://127.0.0.1:" + server.getAddress().getPort();
    server.createContext("/catalog/skus", this::skus);
    server.createContext("/catalog/categories", this::categories);
    server.setExecutor(null);
    server.start();
  }

  String base() {
    return base;
  }

  /** 登记一个 SKU。 */
  void putSku(UUID skuId, String code, String name, String category) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("id", skuId.toString());
    row.put("code", code);
    row.put("name", name);
    row.put("category", category);
    row.put("spuId", UUID.randomUUID().toString());
    row.put("spuName", name + " SPU");
    row.put("status", "ACTIVE");
    skus.put(skuId, row);
  }

  /** 登记一棵内部类目树。 */
  void putTree(UUID treeId, String code, String name) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("id", treeId.toString());
    row.put("code", code);
    row.put("name", name);
    row.put("treeKind", "INTERNAL");
    row.put("platformCode", null);
    row.put("enabled", true);
    trees.put(treeId, row);
  }

  /** 登记一个类目节点（挂在某棵树下）。 */
  void putNode(UUID nodeId, UUID treeId, String code, String name, String path) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("id", nodeId.toString());
    row.put("treeId", treeId.toString());
    row.put("code", code);
    row.put("name", name);
    row.put("path", path);
    row.put("treeKind", "INTERNAL");
    row.put("enabled", true);
    nodes.put(nodeId, row);
  }

  // ---- /catalog/skus 与 /catalog/skus/{id} ----

  private void skus(HttpExchange e) throws IOException {
    String path = e.getRequestURI().getPath();
    if (path.startsWith("/catalog/skus/")) {
      if (detailStatus != 0) {
        json(e, detailStatus, Map.of("message", "upstream failure"));
        return;
      }
      String raw = path.substring("/catalog/skus/".length());
      UUID skuId;
      try {
        skuId = UUID.fromString(raw);
      } catch (IllegalArgumentException ex) {
        json(e, 400, Map.of("message", "bad uuid"));
        return;
      }
      Map<String, Object> row = skus.get(skuId);
      if (row == null) {
        json(e, 404, Map.of("message", "sku not found"));
        return;
      }
      json(e, 200, row);
      return;
    }
    if (!path.equals("/catalog/skus")) {
      json(e, 404, Map.of("message", "no such path"));
      return;
    }
    if (listStatus != 0) {
      json(e, listStatus, Map.of("message", "upstream failure"));
      return;
    }
    String keyword = queryParam(e, "q");
    List<Map<String, Object>> items = new ArrayList<>();
    for (Map<String, Object> row : skus.values()) {
      if (keyword != null
          && !keyword.isBlank()
          && !String.valueOf(row.get("code")).contains(keyword)
          && !String.valueOf(row.get("name")).contains(keyword)) {
        continue;
      }
      items.add(row);
    }
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("items", items);
    payload.put("total", items.size());
    payload.put("page", 0);
    payload.put("size", 20);
    json(e, 200, payload);
  }

  // ---- /catalog/categories/trees 与节点 ----

  private void categories(HttpExchange e) throws IOException {
    String path = e.getRequestURI().getPath();
    if (treesStatus != 0) {
      json(e, treesStatus, Map.of("message", "upstream failure"));
      return;
    }
    if (path.equals("/catalog/categories/trees")) {
      json(e, 200, new ArrayList<>(trees.values()));
      return;
    }
    if (path.startsWith("/catalog/categories/trees/") && path.endsWith("/nodes")) {
      String treeId = path.substring("/catalog/categories/trees/".length(), path.length() - "/nodes".length());
      List<Map<String, Object>> items = new ArrayList<>();
      for (Map<String, Object> row : nodes.values()) {
        if (String.valueOf(row.get("treeId")).equals(treeId)) {
          items.add(row);
        }
      }
      json(e, 200, items);
      return;
    }
    json(e, 404, Map.of("message", "no such path"));
  }

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
    server.stop(0);
  }
}
