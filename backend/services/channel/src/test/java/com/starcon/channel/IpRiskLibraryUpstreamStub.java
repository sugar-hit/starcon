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
 * 16.4.1.1 测试用的**本地上游服务**（03 品牌 catalog）。
 *
 * <p>这不是 mock：它是一个真的 HTTP 服务器，跑在真的 socket 上，真的按上游的路由形状回 JSON。
 * 之所以需要它，是因为本模块的一条核心不变量全在<b>跨服务那一跳</b>上 ——
 * 「上游读不到时，正确答案是『读不到』而不是『上游一个品牌都没有』」。
 * 把端口打桩成固定返回值的话，那条不变量就测不出来。
 *
 * <p>可切换的剧本让不同失败原因都能被真实触发：
 *
 * <ul>
 *   <li>{@link #catalogStatus} ≠ 0 ⇒ 上游故障 ⇒ 候选列表必须显示「读不到」；
 *   <li>{@link #brandIds} 清空 ⇒ 品牌字典里查不到（此时是**真的没有**，不是读不到）。
 * </ul>
 */
final class IpRiskLibraryUpstreamStub implements AutoCloseable {
  private static final ObjectMapper JSON = new ObjectMapper();

  private final HttpServer server;
  private final String base;

  /** 品牌名 → 品牌 id。 */
  final Map<String, UUID> brandIds = new ConcurrentHashMap<>();

  /** catalog 端点的 HTTP 状态（0 = 正常；其它 = 上游故障）。 */
  volatile int catalogStatus = 0;

  IpRiskLibraryUpstreamStub() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    base = "http://127.0.0.1:" + server.getAddress().getPort();
    server.createContext("/catalog/brands", this::brands);
    server.setExecutor(null);
    server.start();
  }

  String base() {
    return base;
  }

  /** 登记一个品牌。 */
  void putBrand(String name) {
    brandIds.put(name, UUID.randomUUID());
  }

  // ---- /catalog/brands ----

  private void brands(HttpExchange e) throws IOException {
    if (catalogStatus != 0) {
      json(e, catalogStatus, Map.of("message", "upstream failure"));
      return;
    }
    String keyword = queryParam(e, "keyword");
    List<Map<String, Object>> items = new ArrayList<>();
    for (Map.Entry<String, UUID> entry : brandIds.entrySet()) {
      if (keyword != null
          && !keyword.isBlank()
          && !entry.getKey().toLowerCase().contains(keyword.toLowerCase())) {
        continue;
      }
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("id", entry.getValue().toString());
      row.put("code", "BRD-" + entry.getKey());
      row.put("name", entry.getKey());
      row.put("authorizationStatus", "OWNED");
      row.put("enabled", true);
      items.add(row);
    }
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("items", items);
    payload.put("total", items.size());
    payload.put("page", 0);
    payload.put("size", 20);
    json(e, 200, payload);
  }

  private static String queryParam(HttpExchange e, String name) {
    String query = e.getRequestURI().getRawQuery();
    if (query == null) {
      return null;
    }
    for (String pair : query.split("&")) {
      if (pair.startsWith(name + "=")) {
        // 🔴 必须解码：RestClient 的 queryParam 会把中文名 URL 编码，
        //    不解码就拿不到原始名字，关键词过滤会静默地一条都不命中。
        try {
          return URLDecoder.decode(pair.substring(name.length() + 1), StandardCharsets.UTF_8);
        } catch (RuntimeException ex) {
          return pair.substring(name.length() + 1);
        }
      }
    }
    return null;
  }

  private static void json(HttpExchange e, int status, Object payload) throws IOException {
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
