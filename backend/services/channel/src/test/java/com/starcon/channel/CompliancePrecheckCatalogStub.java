package com.starcon.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 16.2.2.1 合规预检测试用的**本地 catalog 服务**。
 *
 * <p>这不是 mock：它是一个真的 HTTP 服务器，跑在真的 socket 上，真的按 catalog 的路由形状回 JSON。
 * 之所以需要它，是因为本模块的一条核心不变量全在**跨服务那一跳**上 ——
 * 「品牌有没有授权，catalog 说了算；读不到的时候是『量不到』而不是『合规』」。
 * 把端口打桩成固定返回值的话，那条不变量就测不出来。
 *
 * <p>可切换的剧本让不同失败原因都能被真实触发：
 *
 * <ul>
 *   <li>{@link #skuStatus} = 500 ⇒ 源商品读不到 ⇒ 品牌这一项必须量不到（**不是**合规、不是 0 分）；
 *   <li>{@link #brandPublishable} = false ⇒ 品牌不可刊登 ⇒ 阻断项；
 *   <li>{@link #brandLookupFails} = true ⇒ 品牌字典里查不到 ⇒ 同样量不到；
 *   <li>{@link #skuBrand} 改一处 ⇒ 换了品牌 ⇒ 授权判定跟着变。
 * </ul>
 */
final class CompliancePrecheckCatalogStub implements AutoCloseable {
  private static final ObjectMapper JSON = new ObjectMapper();

  private final HttpServer server;
  private final String base;

  /** SKU id → 品牌名。 */
  final Map<UUID, String> skuBrands = new ConcurrentHashMap<>();

  /** SKU 详情端点的 HTTP 状态（0 = 正常；500 = 上游故障）。 */
  volatile int skuStatus = 0;

  /** 品牌名 → 品牌 id / 编码。 */
  final Map<String, UUID> brandIds = new ConcurrentHashMap<>();

  /** 品牌字典查询是否失败（模拟「品牌字典里没有这个品牌」或上游挂了）。 */
  volatile boolean brandLookupFails = false;

  /** 品牌是否允许刊登。 */
  volatile boolean brandPublishable = true;

  /** 阻止刊登的原因码。 */
  volatile String brandBlockCode = "BRAND_NOT_AUTHORIZED";

  /** 阻止刊登的原因。 */
  volatile String brandBlockMessage = "该品牌当前没有有效授权";

  /** 有效授权条数。 */
  volatile int validAuthorizationCount = 1;

  CompliancePrecheckCatalogStub() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    base = "http://127.0.0.1:" + server.getAddress().getPort();
    server.createContext("/catalog/skus", this::skus);
    server.createContext("/catalog/brands", this::brands);
    server.setExecutor(null);
    server.start();
  }

  String base() {
    return base;
  }

  /** 登记一个源商品：它挂在哪个品牌下。 */
  void putSku(UUID skuId, String brandName) {
    skuBrands.put(skuId, brandName);
  }

  /** 登记一个品牌（品牌字典里能查到它）。 */
  void putBrand(String name) {
    brandIds.put(name, UUID.randomUUID());
  }

  // ---- /catalog/skus/{id} ----

  private void skus(HttpExchange e) throws IOException {
    String path = e.getRequestURI().getPath();
    if (!path.startsWith("/catalog/skus/")) {
      json(e, 404, Map.of("message", "no such path"));
      return;
    }
    if (skuStatus != 0) {
      json(e, skuStatus, Map.of("message", "upstream failure"));
      return;
    }
    UUID skuId;
    try {
      skuId = UUID.fromString(path.substring("/catalog/skus/".length()));
    } catch (IllegalArgumentException ex) {
      json(e, 400, Map.of("message", "bad uuid"));
      return;
    }
    String brand = skuBrands.get(skuId);
    if (brand == null) {
      json(e, 404, Map.of("message", "sku not found"));
      return;
    }
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("id", skuId.toString());
    payload.put("code", "SKU-" + skuId.toString().substring(0, 8));
    payload.put("name", "测试商品");
    payload.put("status", "ACTIVE");
    payload.put("brand", brand);
    payload.put("specs", List.of(Map.of("axis", "包装尺寸", "value", "10x20x30")));
    payload.put(
        "attributes",
        List.of(
            Map.of(
                "code", "MATERIAL",
                "name", "材质",
                "effectiveValues", List.of("铝合金"))));
    json(e, 200, payload);
  }

  // ---- /catalog/brands ----

  private void brands(HttpExchange e) throws IOException {
    String path = e.getRequestURI().getPath();
    if (path.startsWith("/catalog/brands/") && path.endsWith("/authorizations")) {
      String brandId = path.substring("/catalog/brands/".length(), path.length() - "/authorizations".length());
      List<Map<String, Object>> items = new ArrayList<>();
      if (validAuthorizationCount > 0) {
        items.add(
            Map.of(
                "id", UUID.randomUUID().toString(),
                "certificateNo", "CERT-001",
                "grantor", "品牌方",
                "validFrom", "2024-01-01",
                "validTo", null,
                "enabled", true,
                "validOn", true));
      }
      json(e, 200, items);
      return;
    }
    if (path.startsWith("/catalog/brands/") && path.endsWith("/listing-eligibility")) {
      String brandId = path.substring("/catalog/brands/".length(), path.length() - "/listing-eligibility".length());
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("brandId", brandId);
      payload.put("brandName", "品牌");
      payload.put("authorizationStatus", brandPublishable ? "AUTHORIZED" : "MISSING");
      payload.put("enabled", true);
      payload.put("publishable", brandPublishable);
      payload.put("blockCode", brandPublishable ? null : brandBlockCode);
      payload.put("blockLabel", brandPublishable ? null : "未获授权");
      payload.put("blockMessage", brandPublishable ? null : brandBlockMessage);
      payload.put("authorizationCount", validAuthorizationCount);
      payload.put("validAuthorizationCount", validAuthorizationCount);
      payload.put("asOf", "2026-01-01");
      json(e, 200, payload);
      return;
    }
    if (!path.equals("/catalog/brands")) {
      json(e, 404, Map.of("message", "no such path"));
      return;
    }
    if (brandLookupFails) {
      json(e, 500, Map.of("message", "upstream failure"));
      return;
    }
    String keyword = queryParam(e, "keyword");
    List<Map<String, Object>> items = new ArrayList<>();
    for (Map.Entry<String, UUID> entry : brandIds.entrySet()) {
      if (keyword != null && !keyword.isBlank() && !keyword.equalsIgnoreCase(entry.getKey())) {
        continue;
      }
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("id", entry.getValue().toString());
      row.put("code", "BRD-" + entry.getKey());
      row.put("name", entry.getKey());
      row.put("enabled", true);
      row.put("publishable", brandPublishable);
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
        // 🔴 必须解码：RestClient 的 queryParam 会把中文品牌名 URL 编码，
        //    不解码就拿不到原始名字，关键词过滤会静默地一条都不命中。
        try {
          return java.net.URLDecoder.decode(
              pair.substring(name.length() + 1), java.nio.charset.StandardCharsets.UTF_8);
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
