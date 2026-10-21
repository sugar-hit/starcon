package com.starcon.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 4.2.2.1 刊登模板测试用的**本地 catalog 服务**。
 *
 * <p>这不是 mock：它是一个真的 HTTP 服务器，跑在真的 socket 上，真的按 catalog 的路由形状回 JSON。
 * 之所以需要它，是因为本模块的核心不变量全在**跨服务那一跳**上 ——
 * 「模板存规则不存快照，商品改价或换图后套用结果跟着变」。
 * 那个不变量只有在真的走了 HTTP、真的读到了上游当前值之后才能被打穿；
 * 把端口打桩成固定返回值的话，这条不变量就测不出来。
 *
 * <p>可切换的剧本让不同失败原因都能被真实触发：
 *
 * <ul>
 *   <li>{@link #skuStatus} = 500 ⇒ 上游不可用 ⇒ 字段必须是 {@code UNKNOWN_SOURCE}（**不是**空值）；
 *   <li>{@link #skuAttributes} 改一处 ⇒ 源商品「改了」⇒ 重新套用必须产出新值；
 *   <li>{@link #mediaStatus} = 500 ⇒ 媒体库不可用 ⇒ 同上；
 *   <li>{@link #mediaStorageKeys} 改一处 ⇒ 素材「换了图」⇒ 重新套用必须产出新图。
 * </ul>
 */
final class StubCatalogServer implements AutoCloseable {
  private static final ObjectMapper JSON = new ObjectMapper();

  private final HttpServer server;
  private final String base;

  /** SKU id → 属性码 → 生效值。测试改这里就等于「商品改了」。 */
  final Map<UUID, Map<String, List<String>>> skuAttributes = new ConcurrentHashMap<>();

  /** SKU 详情端点的 HTTP 状态（0 = 正常；500 = 上游故障）。 */
  volatile int skuStatus = 0;

  /** 素材 id → 存储键。测试改这里就等于「素材换了图」。 */
  final Map<UUID, String> mediaStorageKeys = new ConcurrentHashMap<>();

  /** 素材 id → 素材名。 */
  final Map<UUID, String> mediaNames = new ConcurrentHashMap<>();

  /** 素材 id → 是否启用。 */
  final Map<UUID, Boolean> mediaEnabled = new ConcurrentHashMap<>();

  /** 媒体库列表端点的 HTTP 状态（0 = 正常；500 = 上游故障）。 */
  volatile int mediaStatus = 0;

  /** 描述模板编码 → 模板 id。 */
  final Map<String, UUID> descriptionTemplates = new ConcurrentHashMap<>();

  /** 渲染结果的正文。 */
  volatile String renderHtml = "<section>rendered</section>";

  /** 渲染结果报出的缺失变量条数。 */
  volatile int renderIssueCount = 0;

  /** 描述模板端点的 HTTP 状态（0 = 正常；500 = 上游故障）。 */
  volatile int descriptionStatus = 0;

  StubCatalogServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    base = "http://127.0.0.1:" + server.getAddress().getPort();
    server.createContext("/catalog/skus", this::skus);
    server.createContext("/catalog/media", this::media);
    server.createContext("/catalog/description-templates/templates", this::descriptionTemplates);
    server.createContext("/catalog/description-templates/renders", this::renders);
    server.setExecutor(null);
    server.start();
  }

  String base() {
    return base;
  }

  /** 登记一个 SKU 的属性。 */
  void putSku(UUID skuId, String code, String... values) {
    skuAttributes
        .computeIfAbsent(skuId, key -> new ConcurrentHashMap<>())
        .put(code, List.of(values));
  }

  /** 登记一个素材。 */
  void putMedia(UUID assetId, String name, String storageKey) {
    mediaStorageKeys.put(assetId, storageKey);
    mediaNames.put(assetId, name);
    mediaEnabled.put(assetId, true);
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
    Map<String, List<String>> attributes = skuAttributes.get(skuId);
    if (attributes == null) {
      json(e, 404, Map.of("message", "sku not found"));
      return;
    }
    List<Map<String, Object>> attributeList = new ArrayList<>();
    for (Map.Entry<String, List<String>> entry : attributes.entrySet()) {
      Map<String, Object> attribute = new LinkedHashMap<>();
      attribute.put("code", entry.getKey());
      attribute.put("name", entry.getKey());
      attribute.put("dataType", "TEXT");
      attribute.put("multiple", entry.getValue().size() > 1);
      attribute.put("options", List.of());
      attribute.put("rawValues", entry.getValue());
      attribute.put("effectiveValues", entry.getValue());
      attributeList.add(attribute);
    }
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("id", skuId.toString());
    payload.put("code", "SKU-" + skuId.toString().substring(0, 8));
    payload.put("name", "测试商品 " + skuId.toString().substring(0, 8));
    payload.put("status", "ACTIVE");
    payload.put("attributes", attributeList);
    json(e, 200, payload);
  }

  // ---- /catalog/media ----

  private void media(HttpExchange e) throws IOException {
    if (mediaStatus != 0) {
      json(e, mediaStatus, Map.of("message", "upstream failure"));
      return;
    }
    String path = e.getRequestURI().getPath();
    if (path.startsWith("/catalog/media/")) {
      UUID assetId;
      try {
        assetId = UUID.fromString(path.substring("/catalog/media/".length()));
      } catch (IllegalArgumentException ex) {
        json(e, 400, Map.of("message", "bad uuid"));
        return;
      }
      String key = mediaStorageKeys.get(assetId);
      if (key == null) {
        json(e, 404, Map.of("message", "asset not found"));
        return;
      }
      json(e, 200, mediaPayload(assetId, key));
      return;
    }
    // 列表：按 tagCode 过滤
    String tag = null;
    String query = e.getRequestURI().getRawQuery();
    if (query != null) {
      for (String pair : query.split("&")) {
        if (pair.startsWith("tagCode=")) {
          tag = pair.substring("tagCode=".length());
        }
      }
    }
    List<Map<String, Object>> items = new ArrayList<>();
    for (Map.Entry<UUID, String> entry : mediaStorageKeys.entrySet()) {
      if (tag != null && !tag.equals(mediaNames.get(entry.getKey()))) {
        continue;
      }
      items.add(mediaPayload(entry.getKey(), entry.getValue()));
    }
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("items", items);
    payload.put("total", items.size());
    payload.put("page", 0);
    payload.put("size", 50);
    json(e, 200, payload);
  }

  private Map<String, Object> mediaPayload(UUID assetId, String storageKey) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("id", assetId.toString());
    payload.put("name", mediaNames.getOrDefault(assetId, "素材"));
    payload.put("mediaType", "IMAGE");
    payload.put("purpose", "GALLERY");
    payload.put("storageKey", storageKey);
    payload.put("enabled", mediaEnabled.getOrDefault(assetId, true));
    payload.put("currentVersion", 1);
    return payload;
  }

  // ---- /catalog/description-templates ----

  private void descriptionTemplates(HttpExchange e) throws IOException {
    if (descriptionStatus != 0) {
      json(e, descriptionStatus, Map.of("message", "upstream failure"));
      return;
    }
    List<Map<String, Object>> items = new ArrayList<>();
    for (Map.Entry<String, UUID> entry : descriptionTemplates.entrySet()) {
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("id", entry.getValue().toString());
      payload.put("code", entry.getKey());
      payload.put("name", "文案模板 " + entry.getKey());
      payload.put("enabled", true);
      items.add(payload);
    }
    json(e, 200, items);
  }

  private void renders(HttpExchange e) throws IOException {
    if (descriptionStatus != 0) {
      json(e, descriptionStatus, Map.of("message", "upstream failure"));
      return;
    }
    String body = new String(e.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    UUID templateId = null;
    try {
      var node = JSON.readTree(body);
      if (node.has("templateId")) {
        templateId = UUID.fromString(node.get("templateId").asText());
      }
    } catch (Exception ignored) {
      // 请求体结构不对时按「渲染不出东西」处理
    }
    UUID matched = null;
    for (Map.Entry<String, UUID> entry : descriptionTemplates.entrySet()) {
      if (entry.getValue().equals(templateId)) {
        matched = entry.getValue();
      }
    }
    if (matched == null) {
      json(e, 404, Map.of("message", "template not found"));
      return;
    }
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("renderId", UUID.randomUUID().toString());
    payload.put("templateId", matched.toString());
    payload.put("templateCode", templateCodeOf(matched));
    payload.put("status", renderIssueCount > 0 ? "INCOMPLETE" : "COMPLETE");
    payload.put("html", renderHtml);
    payload.put("issueCount", renderIssueCount);
    json(e, 200, payload);
  }

  private String templateCodeOf(UUID templateId) {
    for (Map.Entry<String, UUID> entry : descriptionTemplates.entrySet()) {
      if (entry.getValue().equals(templateId)) {
        return entry.getKey();
      }
    }
    return "";
  }

  // ---- helpers ----

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
