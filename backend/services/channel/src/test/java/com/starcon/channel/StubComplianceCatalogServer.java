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
 * 4.2.3.2 合规预检测试用的**本地 catalog 服务**。
 *
 * <p>这不是 mock：它是一个真的 HTTP 服务器，跑在真的 socket 上，真的按 catalog 的路由形状回 JSON。
 * 之所以需要它，是因为本模块的核心不变量有一半在**跨服务那一跳**上：
 * 「品牌没有授权 ⇒ 阻断项」「平台类目必填没填 ⇒ 阻断项」「图片尺寸不够 ⇒ 阻断项」
 * 「上游读不到 ⇒ 该维度 UNKNOWN 而不是「合规通过」」。
 * 那些不变量只有真的走了 HTTP、真的读到了上游当前值之后才能被打穿。
 *
 * <p>可切换的剧本让不同失败原因都能被真实触发：
 *
 * <ul>
 *   <li>{@link #skuStatus} = 500 ⇒ 源商品读不到 ⇒ 品牌维度必须 UNKNOWN（**不是**「无风险」）；
 *   <li>{@link #brandStatus} = 500 ⇒ 品牌库读不到 ⇒ 同上；
 *   <li>{@link #mappingStatus} = 500 ⇒ 类目映射读不到 ⇒ 类目维度 UNKNOWN；
 *   <li>{@link #mediaStatus} = 500 ⇒ 媒体库读不到 ⇒ 图片维度 UNKNOWN；
 *   <li>{@link #recipesStatus} = 500 ⇒ 图片规格读不到 ⇒ 图片维度 UNKNOWN。
 * </ul>
 */
final class StubComplianceCatalogServer implements AutoCloseable {
  private static final ObjectMapper JSON = new ObjectMapper();

  private final HttpServer server;
  private final String base;

  /** SKU id → SKU 详情。测试改这里就等于「源商品改了」。 */
  final Map<UUID, Map<String, Object>> skus = new ConcurrentHashMap<>();

  /** 关键词 → 品牌行。 */
  final Map<String, List<Map<String, Object>>> brands = new ConcurrentHashMap<>();

  /** 品牌 id → 授权凭证。 */
  final Map<UUID, List<Map<String, Object>>> authorizations = new ConcurrentHashMap<>();

  /** 平台 + 站点 → 类目映射行。 */
  final Map<String, List<Map<String, Object>>> categoryMappings = new ConcurrentHashMap<>();

  /** 内部分类 id → 必填属性清单。 */
  final Map<UUID, List<Map<String, Object>>> requiredAttributes = new ConcurrentHashMap<>();

  /** 存储键 → 素材。 */
  final Map<String, Map<String, Object>> media = new ConcurrentHashMap<>();

  /** 图片处理配方。 */
  final List<Map<String, Object>> recipes = new ArrayList<>();

  /** 素材 id → 处理历史。 */
  final Map<UUID, List<Map<String, Object>>> histories = new ConcurrentHashMap<>();

  volatile int skuStatus = 0;
  volatile int brandStatus = 0;
  volatile int authorizationStatus = 0;
  volatile int mappingStatus = 0;
  volatile int mediaStatus = 0;
  volatile int recipesStatus = 0;

  StubComplianceCatalogServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    base = "http://127.0.0.1:" + server.getAddress().getPort();
    server.createContext("/catalog/skus", this::skus);
    server.createContext("/catalog/brands", this::brands);
    server.createContext("/catalog/category-mappings", this::categoryMappings);
    server.createContext("/catalog/media/processing/recipes", this::recipes);
    server.createContext("/catalog/media/processing/assets", this::history);
    server.createContext("/catalog/media", this::media);
    server.setExecutor(null);
    server.start();
  }

  String base() {
    return base;
  }

  /**
   * 清空剧本。
   *
   * <p>🔴 stub 是 static 的（一个容器一个 server），不同测试共用它。
   * 不清的话上一条测试登记的类目映射会出现在下一条测试的台账里，
   * 症状是「明明登记了 RECOMMENDED，却判出 REQUIRED 的阻断项」——
   * 那是测试之间的脏数据，不是产品行为。
   */
  void reset() {
    skus.clear();
    brands.clear();
    authorizations.clear();
    categoryMappings.clear();
    requiredAttributes.clear();
    media.clear();
    recipes.clear();
    histories.clear();
    skuStatus = 0;
    brandStatus = 0;
    authorizationStatus = 0;
    mappingStatus = 0;
    mediaStatus = 0;
    recipesStatus = 0;
  }

  // ---- 剧本登记 ----

  /** 登记一个带品牌的源商品。 */
  void putSku(UUID skuId, String brand) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("id", skuId.toString());
    payload.put("code", "SKU-" + skuId.toString().substring(0, 8));
    payload.put("name", "测试商品");
    payload.put("status", "ACTIVE");
    payload.put("spuId", UUID.randomUUID().toString());
    payload.put("brand", brand);
    payload.put("category", "Electronics");
    payload.put("media", List.of());
    skus.put(skuId, payload);
  }

  /** 登记一个品牌（可刊登 / 不可刊登）。 */
  void putBrand(UUID brandId, String name, boolean publishable, String blockCode, String blockMessage) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("id", brandId.toString());
    row.put("code", "BR-" + brandId.toString().substring(0, 8));
    row.put("name", name);
    row.put("authorizationStatus", publishable ? "AUTHORIZED" : "PENDING");
    row.put("enabled", true);
    row.put("publishable", publishable);
    row.put("blockCode", blockCode);
    row.put("blockLabel", blockMessage);
    row.put("blockMessage", blockMessage);
    row.put("authorizationCount", 1);
    row.put("validAuthorizationCount", publishable ? 1 : 0);
    row.put("asOf", "2026-10-20T00:00:00Z");
    brands.computeIfAbsent(name, key -> new ArrayList<>()).add(row);
  }

  /** 登记一个品牌授权凭证。 */
  void putAuthorization(UUID brandId, boolean valid, int daysToExpiry) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("id", UUID.randomUUID().toString());
    row.put("brandId", brandId.toString());
    row.put("certificateNo", "CERT-001");
    row.put("grantor", "测试授权方");
    row.put("validFrom", "2026-01-01");
    row.put("validTo", "2027-01-01");
    row.put("enabled", true);
    row.put("validOn", valid);
    row.put("daysToExpiry", daysToExpiry);
    authorizations.computeIfAbsent(brandId, key -> new ArrayList<>()).add(row);
  }

  /** 登记一条平台类目映射。 */
  void putCategoryMapping(
      String platformCode, String siteCode, String internalCategoryId, String platformCategoryPath) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("id", UUID.randomUUID().toString());
    row.put("internalCategoryId", internalCategoryId);
    row.put("internalCategoryCode", "IC-" + internalCategoryId.substring(0, 6));
    row.put("internalCategoryName", "内部分类");
    row.put("platformCode", platformCode);
    row.put("siteCode", siteCode);
    row.put("platformCategoryId", UUID.randomUUID().toString());
    row.put("platformCategoryCode", "PC-1");
    row.put("platformCategoryName", "平台类目");
    row.put("platformCategoryPath", platformCategoryPath);
    row.put("isDefault", true);
    row.put("enabled", true);
    row.put("requiredCount", 1);
    row.put("unmappedRequiredCount", 0);
    categoryMappings
        .computeIfAbsent(platformCode + "|" + siteCode, key -> new ArrayList<>())
        .add(row);
  }

  /** 登记一个平台必填属性。 */
  void putRequiredAttribute(
      UUID internalCategoryId,
      String platformAttributeCode,
      String internalAttributeCode,
      String requirement) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("platformAttributeCode", platformAttributeCode);
    row.put("platformAttributeName", "属性 " + platformAttributeCode);
    row.put("requirement", requirement);
    row.put("platformDataType", "TEXT");
    row.put("multiple", false);
    row.put("allowedValues", List.of());
    row.put("transform", "DIRECT");
    row.put("internalAttributeId", internalAttributeCode == null ? null : UUID.randomUUID().toString());
    row.put("internalAttributeCode", internalAttributeCode);
    row.put("internalAttributeName", internalAttributeCode == null ? null : "内部属性");
    row.put("mapped", internalAttributeCode != null);
    requiredAttributes.computeIfAbsent(internalCategoryId, key -> new ArrayList<>()).add(row);
  }

  /** 登记一个素材。 */
  void putMedia(
      UUID assetId,
      String storageKey,
      String name,
      String widthPx,
      String heightPx,
      String integrityStatus,
      String altText) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("id", assetId.toString());
    row.put("name", name);
    row.put("mediaType", "IMAGE");
    row.put("purpose", "PRIMARY");
    row.put("fileFormat", "png");
    row.put("mimeType", "image/png");
    row.put("widthPx", widthPx);
    row.put("heightPx", heightPx);
    row.put("storageKey", storageKey);
    row.put("originalFilename", name + ".png");
    row.put("altText", altText);
    row.put("integrityStatus", integrityStatus);
    row.put("enabled", true);
    media.put(storageKey, row);
  }

  /** 登记一个图片处理配方。 */
  void putRecipe(
      UUID recipeId,
      String code,
      String targetPlatform,
      boolean whiteBackground,
      String watermarkText,
      String targetWidthPx,
      String targetHeightPx) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("id", recipeId.toString());
    row.put("code", code);
    row.put("name", "配方 " + code);
    row.put("targetPlatform", targetPlatform);
    row.put("targetFormat", "png");
    row.put("removeBackground", whiteBackground);
    row.put("whiteBackground", whiteBackground);
    row.put("watermarkText", watermarkText);
    row.put("watermarkPosition", "BOTTOM_RIGHT");
    row.put("watermarkOpacity", 30);
    row.put("targetWidthPx", targetWidthPx);
    row.put("targetHeightPx", targetHeightPx);
    row.put("cropMode", "FIT");
    row.put("enabled", true);
    row.put("ratios", List.of());
    recipes.add(row);
  }

  /** 登记一条素材处理历史。 */
  void putHistory(UUID assetId, UUID recipeId, String recipeCode, String status) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("id", UUID.randomUUID().toString());
    row.put("recipeId", recipeId.toString());
    row.put("recipeCode", recipeCode);
    row.put("recipeName", "配方 " + recipeCode);
    row.put("targetPlatform", "AMAZON");
    row.put("status", status);
    row.put("assetCount", 1);
    row.put("succeededCount", 1);
    row.put("failedCount", 0);
    row.put("createdAt", "2026-10-19T00:00:00Z");
    histories.computeIfAbsent(assetId, key -> new ArrayList<>()).add(row);
  }

  // ---- 路由 ----

  private void skus(HttpExchange e) throws IOException {
    if (skuStatus != 0) {
      json(e, skuStatus, Map.of("message", "upstream failure"));
      return;
    }
    String path = e.getRequestURI().getPath();
    if (!path.startsWith("/catalog/skus/")) {
      json(e, 404, Map.of("message", "no such path"));
      return;
    }
    UUID skuId = parseUuid(path.substring("/catalog/skus/".length()));
    Map<String, Object> payload = skuId == null ? null : skus.get(skuId);
    if (payload == null) {
      json(e, 404, Map.of("message", "sku not found"));
      return;
    }
    json(e, 200, payload);
  }

  private void brands(HttpExchange e) throws IOException {
    if (brandStatus != 0) {
      json(e, brandStatus, Map.of("message", "upstream failure"));
      return;
    }
    String path = e.getRequestURI().getPath();
    if (path.startsWith("/catalog/brands/") && path.endsWith("/authorizations")) {
      authorizations(e, path);
      return;
    }
    String keyword = queryParam(e, "keyword");
    List<Map<String, Object>> items = new ArrayList<>();
    if (keyword != null && !keyword.isBlank()) {
      for (Map.Entry<String, List<Map<String, Object>>> entry : brands.entrySet()) {
        if (entry.getKey().toLowerCase().contains(keyword.toLowerCase())) {
          items.addAll(entry.getValue());
        }
      }
    } else {
      for (List<Map<String, Object>> rows : brands.values()) {
        items.addAll(rows);
      }
    }
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("items", items);
    payload.put("total", items.size());
    payload.put("page", 0);
    payload.put("size", 20);
    json(e, 200, payload);
  }

  private void authorizations(HttpExchange e, String path) throws IOException {
    if (authorizationStatus != 0) {
      json(e, authorizationStatus, Map.of("message", "upstream failure"));
      return;
    }
    String brandId = path.substring("/catalog/brands/".length(), path.length() - "/authorizations".length());
    UUID id = parseUuid(brandId);
    List<Map<String, Object>> rows = id == null ? null : authorizations.get(id);
    if (rows == null) {
      json(e, 404, Map.of("message", "brand not found"));
      return;
    }
    json(e, 200, rows);
  }

  private void categoryMappings(HttpExchange e) throws IOException {
    if (mappingStatus != 0) {
      json(e, mappingStatus, Map.of("message", "upstream failure"));
      return;
    }
    String path = e.getRequestURI().getPath();
    if (path.endsWith("/resolve")) {
      resolve(e);
      return;
    }
    String platformCode = queryParam(e, "platformCode");
    String siteCode = queryParam(e, "siteCode");
    List<Map<String, Object>> items = new ArrayList<>();
    for (Map.Entry<String, List<Map<String, Object>>> entry : categoryMappings.entrySet()) {
      String[] parts = entry.getKey().split("\\|", 2);
      if (platformCode != null && !platformCode.equals(parts[0])) {
        continue;
      }
      if (siteCode != null && !siteCode.equals(parts.length > 1 ? parts[1] : "")) {
        continue;
      }
      items.addAll(entry.getValue());
    }
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("items", items);
    payload.put("total", items.size());
    payload.put("page", 1);
    payload.put("size", 200);
    json(e, 200, payload);
  }

  private void resolve(HttpExchange e) throws IOException {
    UUID internalCategoryId = parseUuid(queryParam(e, "internalCategoryId"));
    List<Map<String, Object>> rows = internalCategoryId == null ? null : requiredAttributes.get(internalCategoryId);
    if (rows == null) {
      json(e, 404, Map.of("message", "mapping not found"));
      return;
    }
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("mappingId", UUID.randomUUID().toString());
    payload.put("internalCategoryId", internalCategoryId.toString());
    payload.put("platformCode", queryParam(e, "platformCode"));
    payload.put("siteCode", queryParam(e, "siteCode"));
    payload.put("platformCategoryPath", "Electronics > Audio");
    payload.put("isDefault", true);
    payload.put("requiredAttributes", rows);
    payload.put("unmappedRequiredCount", 0);
    json(e, 200, payload);
  }

  private void media(HttpExchange e) throws IOException {
    if (mediaStatus != 0) {
      json(e, mediaStatus, Map.of("message", "upstream failure"));
      return;
    }
    String keyword = queryParam(e, "keyword");
    List<Map<String, Object>> items = new ArrayList<>();
    if (keyword != null && !keyword.isBlank()) {
      for (Map.Entry<String, Map<String, Object>> entry : media.entrySet()) {
        if (entry.getKey().toLowerCase().contains(keyword.toLowerCase())
            || String.valueOf(entry.getValue().get("name")).toLowerCase().contains(keyword.toLowerCase())) {
          items.add(entry.getValue());
        }
      }
    } else {
      items.addAll(media.values());
    }
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("items", items);
    payload.put("total", items.size());
    payload.put("page", 0);
    payload.put("size", 50);
    json(e, 200, payload);
  }

  private void recipes(HttpExchange e) throws IOException {
    if (recipesStatus != 0) {
      json(e, recipesStatus, Map.of("message", "upstream failure"));
      return;
    }
    json(e, 200, recipes);
  }

  private void history(HttpExchange e) throws IOException {
    String path = e.getRequestURI().getPath();
    String tail = path.substring("/catalog/media/processing/assets/".length());
    if (!tail.endsWith("/history")) {
      json(e, 404, Map.of("message", "no such path"));
      return;
    }
    UUID assetId = parseUuid(tail.substring(0, tail.length() - "/history".length()));
    List<Map<String, Object>> rows = assetId == null ? null : histories.get(assetId);
    json(e, 200, rows == null ? List.of() : rows);
  }

  // ---- helpers ----

  private static String queryParam(HttpExchange e, String name) {
    String query = e.getRequestURI().getRawQuery();
    if (query == null) {
      return null;
    }
    for (String pair : query.split("&")) {
      int eq = pair.indexOf('=');
      if (eq > 0 && pair.substring(0, eq).equals(name)) {
        return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
      }
    }
    return null;
  }

  private static UUID parseUuid(String raw) {
    try {
      return UUID.fromString(raw);
    } catch (RuntimeException e) {
      return null;
    }
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
