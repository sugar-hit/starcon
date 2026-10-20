package com.starcon.trade;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jooq.DSLContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 6.2.1.2 补货建议（trade 侧）。
 *
 * <p><b>本测试是 V238 迁移那些 DB 级不变量的独立复验</b>：不采信任何外部结论，逐条在真实
 * PostgreSQL 16 上跑一遍，并且走 HTTP 端点而不是直连数据库，以便同时验证「服务层 + DB 层」
 * 两层都没有把闸门漏掉。
 *
 * <p><b>warehouse 与 catalog 都起本地 stub 服务</b>（而不是在测试里 new 一个假对象）：
 * 可用量、在途量、报价、箱规都必须来自上游的**授权只读接口** —— 本模块跨服务一律走端口，
 * 禁跨库 SQL / FK / 视图。stub 只负责按上游契约形状返回数，数字本身是按各模块自己的算式
 * 真算出来的，不是写死的数组。
 *
 * <p><b>日均销量走 6.2.1.1 的真实登记端点</b>写入（{@code /trade/stockout-risk/velocities}）：
 * 本模块读的是 6.2.1.1 的事实表，走真实写入路径才证明得了「复用上游输入」这件事。
 *
 * <p>真实 PostgreSQL 16 + 强制 RLS，不使用任何内存替身。连接池固定为 1 条连接：
 * 只有复用同一条连接，才能真正证明归还后不会串租户。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReplenishmentSuggestionIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity, warehouse, catalog;
  static final ObjectMapper json =
      new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  /** 评估基准日：写死是为了让「哪天到货」这个断言不随跑测试的日期漂移。 */
  static final LocalDate AS_OF = LocalDate.of(2026, 1, 15);

  /** 兜底覆盖天数。 */
  static final int COVERAGE = 30;

  static final List<String> allPermissions =
      List.of(
          "trade.replenishment.read",
          "trade.replenishment.update",
          "trade.stockout.read",
          "trade.stockout.update");

  // ---------------------------------------------------------------- 上游 stub 数据

  /** 租户 → 该租户 warehouse 的可用量行。 */
  static final Map<UUID, List<Map<String, Object>>> stockRows = new ConcurrentHashMap<>();

  /** 租户 → 该租户 warehouse 的在途腿。 */
  static final Map<UUID, List<Map<String, Object>>> transitRows = new ConcurrentHashMap<>();

  /** 租户 → SKU id → 报价候选。 */
  static final Map<UUID, Map<UUID, List<Map<String, Object>>>> quoteRows = new ConcurrentHashMap<>();

  /** 租户 → SKU id → SKU 详情（分类 + 单位换算 = 箱规）。 */
  static final Map<UUID, Map<UUID, Map<String, Object>>> skuRows = new ConcurrentHashMap<>();

  /** 置 true 时 catalog stub 返回 500（模拟上游故障）。 */
  static final AtomicBoolean catalogDown = new AtomicBoolean(false);

  static UUID skuFor(UUID tenant, int ordinal) {
    return UUID.nameUUIDFromBytes(
        ("starcon-replenishment-test-sku-" + tenant + "-" + ordinal).getBytes());
  }

  static AccessService.Access actor(
      UUID uid, UUID tenant, UUID dept, List<AccessService.Grant> grants) {
    return new AccessService.Access(
        uid,
        tenant,
        "Test",
        UUID.randomUUID(),
        dept,
        grants.stream().flatMap(g -> g.permissions().stream()).distinct().toList(),
        grants);
  }

  static AccessService.Grant grant(String scope, List<String> permissions) {
    return new AccessService.Grant(UUID.randomUUID(), "TEST", scope, permissions);
  }

  static void write(HttpServer server, com.sun.net.httpserver.HttpExchange e, int code, Object body)
      throws java.io.IOException {
    byte[] bytes = json.writeValueAsBytes(body);
    e.getResponseHeaders().add("Content-Type", "application/json");
    e.sendResponseHeaders(code, bytes.length);
    e.getResponseBody().write(bytes);
    e.close();
  }

  /** 从 Authorization 头解析出租户（stub 只按已认证身份给数，绝不认 X-Tenant-Id）。 */
  static UUID tenantOf(String bearer) {
    var access = actors.get(bearer);
    return access == null ? null : access.tenantId();
  }

  static Map<String, String> queryOf(com.sun.net.httpserver.HttpExchange e) {
    Map<String, String> params = new LinkedHashMap<>();
    String raw = e.getRequestURI().getRawQuery();
    if (raw == null || raw.isBlank()) {
      return params;
    }
    for (String pair : raw.split("&")) {
      int eq = pair.indexOf('=');
      String key = eq < 0 ? pair : pair.substring(0, eq);
      String value = eq < 0 ? "" : pair.substring(eq + 1);
      params.put(
          URLDecoder.decode(key, StandardCharsets.UTF_8),
          URLDecoder.decode(value, StandardCharsets.UTF_8));
    }
    return params;
  }

  /** 按 page / size 对一份列表分页（stub 的真分页，不是假分页）。 */
  static <T> Map<String, Object> paged(List<T> rows, Map<String, String> params) {
    int page = Integer.parseInt(params.getOrDefault("page", "0"));
    int size = Integer.parseInt(params.getOrDefault("size", "50"));
    int from = Math.min(page * size, rows.size());
    int to = Math.min(from + size, rows.size());
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("rows", rows.subList(from, to));
    body.put("total", rows.size());
    body.put("page", page);
    body.put("size", size);
    return body;
  }

  static {
    pg.start();
    try {
      try (var c =
              DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
          var s = c.createStatement()) {
        s.execute("create role starcon_app nologin");
        s.execute("create role trade_app login password 'test-only' nosuperuser nobypassrls");
      }
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
            byte[] body = json.writeValueAsBytes(access);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      actors.put("Bearer tenant-a", actor(user, a, null, List.of(grant("ALL", allPermissions))));
      actors.put("Bearer tenant-b", actor(user, b, null, List.of(grant("ALL", allPermissions))));
      actors.put(
          "Bearer read-only",
          actor(user, a, null, List.of(grant("ALL", List.of("trade.replenishment.read")))));
      identity.start();

      // ---- warehouse stub：7.1.1.3 可用量 + 7.5.1.2 在途腿 ----
      warehouse = HttpServer.create(new InetSocketAddress(0), 0);
      warehouse.createContext(
          "/warehouse/inventory/sellable/skus",
          e -> {
            UUID tenant = tenantOf(e.getRequestHeaders().getFirst("Authorization"));
            if (tenant == null) {
              write(warehouse, e, 401, Map.of("message", "未认证"));
              return;
            }
            write(warehouse, e, 200, paged(stockRows.getOrDefault(tenant, List.of()), queryOf(e)));
          });
      warehouse.createContext(
          "/warehouse/inventory/in-transit",
          e -> {
            UUID tenant = tenantOf(e.getRequestHeaders().getFirst("Authorization"));
            if (tenant == null) {
              write(warehouse, e, 401, Map.of("message", "未认证"));
              return;
            }
            var params = queryOf(e);
            List<Map<String, Object>> rows =
                new ArrayList<>(transitRows.getOrDefault(tenant, List.of()));
            String kinds = params.get("transitKind");
            if (kinds != null && !kinds.isBlank()) {
              var allowed = List.of(kinds.split(","));
              rows.removeIf(r -> !allowed.contains(String.valueOf(r.get("transitKind"))));
            }
            write(warehouse, e, 200, paged(rows, params));
          });
      warehouse.start();

      // ---- catalog stub：3.6.2.1 有效报价 + 2.x SKU 详情（分类 / 单位换算 = 箱规）----
      catalog = HttpServer.create(new InetSocketAddress(0), 0);
      catalog.createContext(
          "/catalog/supplier-quotes/effective",
          e -> {
            if (catalogDown.get()) {
              write(catalog, e, 500, Map.of("message", "商品主数据服务内部错误"));
              return;
            }
            UUID tenant = tenantOf(e.getRequestHeaders().getFirst("Authorization"));
            if (tenant == null) {
              write(catalog, e, 401, Map.of("message", "未认证"));
              return;
            }
            UUID skuId = UUID.fromString(queryOf(e).get("skuId"));
            List<Map<String, Object>> candidates =
                quoteRows.getOrDefault(tenant, Map.of()).getOrDefault(skuId, List.of());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("candidates", candidates);
            body.put("ambiguous", false);
            body.put("ambiguityProblem", null);
            body.put("note", "");
            write(catalog, e, 200, body);
          });
      catalog.createContext(
          "/catalog/skus/",
          e -> {
            if (catalogDown.get()) {
              write(catalog, e, 500, Map.of("message", "商品主数据服务内部错误"));
              return;
            }
            UUID tenant = tenantOf(e.getRequestHeaders().getFirst("Authorization"));
            if (tenant == null) {
              write(catalog, e, 401, Map.of("message", "未认证"));
              return;
            }
            String path = e.getRequestURI().getPath();
            UUID skuId = UUID.fromString(path.substring(path.lastIndexOf('/') + 1));
            Map<String, Object> sku = skuRows.getOrDefault(tenant, Map.of()).get(skuId);
            if (sku == null) {
              write(catalog, e, 404, Map.of("message", "商品不存在"));
              return;
            }
            write(catalog, e, 200, sku);
          });
      catalog.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "trade_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://localhost:" + identity.getAddress().getPort());
    r.add("WAREHOUSE_URL", () -> "http://localhost:" + warehouse.getAddress().getPort());
    r.add("CATALOG_URL", () -> "http://localhost:" + catalog.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
    // 单连接：只有复用同一条连接才能证明连接池归还后不串租户。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  @AfterAll
  static void stop() {
    identity.stop(0);
    warehouse.stop(0);
    catalog.stop(0);
    pg.stop();
  }

  @Autowired ReplenishmentSuggestionService service;
  @Autowired AccessService access;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  // ================================================================ 数据准备

  /** 一行 warehouse 可用量。 */
  static Map<String, Object> stock(UUID skuId, String code, String sellable) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("skuId", skuId.toString());
    row.put("skuCode", code);
    row.put("skuName", code + " 名称");
    row.put("onHand", new BigDecimal(sellable));
    row.put("reserved", BigDecimal.ZERO);
    row.put("available", new BigDecimal(sellable));
    row.put("sellableBeforeBuffer", new BigDecimal(sellable));
    row.put("safetyBuffer", BigDecimal.ZERO);
    row.put("sellable", new BigDecimal(sellable));
    return row;
  }

  /** 一条在途腿。{@code eta} 为 null 表示没有预计到达日。 */
  static Map<String, Object> leg(
      UUID skuId, String code, String kind, String quantity, String eta, String reference) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("id", UUID.randomUUID().toString());
    row.put("transitKind", kind);
    row.put("skuId", skuId.toString());
    row.put("skuCode", code);
    row.put("outstandingQuantity", new BigDecimal(quantity));
    row.put("expectedArriveAt", eta == null ? null : eta);
    row.put("sourceSystem", "warehouse");
    row.put("sourceReference", reference);
    return row;
  }

  /** 一条有效报价候选。 */
  static Map<String, Object> quote(
      String supplierName,
      String purchaseUnit,
      String currency,
      String moq,
      int leadTimeDays,
      int priority,
      String unitPrice) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("id", UUID.randomUUID().toString());
    row.put("supplierRoleId", UUID.randomUUID().toString());
    row.put("supplierId", UUID.randomUUID().toString());
    row.put("supplierName", supplierName);
    row.put("purchaseUnitCode", purchaseUnit);
    row.put("currency", currency);
    row.put("moqQuantity", new BigDecimal(moq));
    row.put("leadTimeDays", leadTimeDays);
    row.put("priority", priority);
    Map<String, Object> tier = new LinkedHashMap<>();
    tier.put("minQuantity", BigDecimal.ONE);
    tier.put("unitPrice", new BigDecimal(unitPrice));
    row.put("tiers", List.of(tier));
    return row;
  }

  /** SKU 详情（分类 + 单位换算 = 箱规）。 */
  static Map<String, Object> skuDetail(UUID skuId, String code, String baseUnit, Object... conversions) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("id", skuId.toString());
    row.put("code", code);
    row.put("name", code + " 名称");
    row.put("category", "CAT-01");
    row.put("baseUnit", baseUnit);
    List<Map<String, Object>> list = new ArrayList<>();
    for (int i = 0; i + 2 < conversions.length; i += 3) {
      Map<String, Object> conversion = new LinkedHashMap<>();
      conversion.put("unitCode", conversions[i]);
      conversion.put("role", conversions[i + 1]);
      conversion.put("quantityPerBase", new BigDecimal(conversions[i + 2].toString()));
      conversion.put("current", true);
      list.add(conversion);
    }
    row.put("unitConversions", list);
    return row;
  }

  @BeforeEach
  void seed() throws Exception {
    catalogDown.set(false);
    stockRows.clear();
    transitRows.clear();
    quoteRows.clear();
    skuRows.clear();
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate replenishment_suggestion_decision, replenishment_suggestion_step,"
              + " replenishment_suggestion, replenishment_suggestion_batch,"
              + " replenishment_coverage_policy, replenishment_order_policy,"
              + " stockout_sales_velocity cascade");
    }
    for (UUID tenant : List.of(a, b)) {
      String p = shortCode(tenant);
      UUID needBox = skuFor(tenant, 1); // 要补货：净需求 250
      UUID enough = skuFor(tenant, 2); // 库存已覆盖
      UUID noVelocity = skuFor(tenant, 3); // 没登记销售速度
      UUID noQuote = skuFor(tenant, 4); // 没有有效报价
      UUID noBoxSpec = skuFor(tenant, 5); // 箱规量不到
      UUID withTransit = skuFor(tenant, 6); // 在途扣掉一部分

      stockRows.put(
          tenant,
          List.of(
              stock(needBox, "SKU-" + p + "-01", "50"),
              stock(enough, "SKU-" + p + "-02", "500"),
              stock(noVelocity, "SKU-" + p + "-03", "50"),
              stock(noQuote, "SKU-" + p + "-04", "50"),
              stock(noBoxSpec, "SKU-" + p + "-05", "50"),
              stock(withTransit, "SKU-" + p + "-06", "50")));
      transitRows.put(
          tenant,
          List.of(
              // 覆盖窗口内到达（2026-01-15 + 30 天 = 2026-02-14）⇒ 计入缺口扣减
              leg(withTransit, "SKU-" + p + "-06", "PURCHASE", "200", "2026-02-01T00:00:00Z", "PO-001"),
              // 覆盖窗口之后才到 ⇒ 不计入
              leg(needBox, "SKU-" + p + "-01", "FIRST_MILE", "999", "2026-05-01T00:00:00Z", "FM-001"),
              // 没有 ETA ⇒ 不计入
              leg(needBox, "SKU-" + p + "-01", "TRANSFER", "500", null, "TR-001")));

      Map<UUID, List<Map<String, Object>>> quotes = new LinkedHashMap<>();
      quotes.put(
          needBox,
          List.of(quote("宏远科技", "CTN", "USD", "10", 15, 1, "14.2857")));
      quotes.put(enough, List.of(quote("宏远科技", "CTN", "USD", "10", 15, 1, "14.2857")));
      quotes.put(noVelocity, List.of(quote("宏远科技", "CTN", "USD", "10", 15, 1, "14.2857")));
      quotes.put(noBoxSpec, List.of(quote("星尘科技", "BOX", "USD", "10", 15, 1, "14.2857")));
      quotes.put(withTransit, List.of(quote("宏远科技", "CTN", "USD", "10", 15, 1, "14.2857")));
      quoteRows.put(tenant, quotes);

      Map<UUID, Map<String, Object>> skus = new LinkedHashMap<>();
      skus.put(needBox, skuDetail(needBox, "SKU-" + p + "-01", "PCS", "CTN", "PURCHASE", "12"));
      skus.put(enough, skuDetail(enough, "SKU-" + p + "-02", "PCS", "CTN", "PURCHASE", "12"));
      skus.put(noVelocity, skuDetail(noVelocity, "SKU-" + p + "-03", "PCS", "CTN", "PURCHASE", "12"));
      skus.put(noQuote, skuDetail(noQuote, "SKU-" + p + "-04", "PCS", "CTN", "PURCHASE", "12"));
      // 采购单位 BOX **没有登记换算比例** ⇒ 箱规量不到。
      skus.put(noBoxSpec, skuDetail(noBoxSpec, "SKU-" + p + "-05", "PCS"));
      skus.put(withTransit, skuDetail(withTransit, "SKU-" + p + "-06", "PCS", "CTN", "PURCHASE", "12"));
      skuRows.put(tenant, skus);
    }
  }

  static String shortCode(UUID tenant) {
    return tenant.equals(a) ? "A" : "B";
  }

  // ================================================================ 工具

  /** 登记一条销售速度（走 6.2.1.1 的真实端点，验证真实写入路径）。 */
  void registerVelocity(
      String bearer, UUID skuId, String code, String daily, LocalDate effectiveFrom) throws Exception {
    http.perform(
            post("/trade/stockout-risk/velocities")
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "skuId", skuId.toString(),
                            "skuCode", code,
                            "skuName", code + " 名称",
                            "dailySalesQuantity", new BigDecimal(daily),
                            "windowStart", "2025-12-01",
                            "windowEnd", "2025-12-31",
                            "effectiveFrom", effectiveFrom.toString(),
                            "sourceKind", "MANUAL",
                            "sourceRef", "手工登记",
                            "note", ""))))
        .andExpect(status().isOk());
  }

  /** 发起一次计算（走 HTTP）。 */
  String compute(String bearer) throws Exception {
    var result =
        http.perform(
                post("/trade/replenishment-suggestions/batches")
                    .header("Authorization", bearer)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(
                            Map.of("asOfDate", AS_OF.toString(), "defaultCoverageDays", COVERAGE))))
            .andReturn();
    var response = result.getResponse();
    if (response.getStatus() != 200) {
      var ex = result.getResolvedException();
      throw new AssertionError(
          "计算失败："
              + response.getStatus()
              + " "
              + (ex == null ? response.getContentAsString() : ex.toString()),
          ex);
    }
    return response.getContentAsString();
  }

  /** 建议清单（走 HTTP）。 */
  String suggestions(String bearer) throws Exception {
    return http.perform(get("/trade/replenishment-suggestions/suggestions").header("Authorization", bearer))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  @SuppressWarnings("unchecked")
  List<Map<String, Object>> rowsOf(String payload, String key) throws Exception {
    Map<String, Object> parsed = json.readValue(payload, Map.class);
    return (List<Map<String, Object>>) parsed.get(key);
  }

  Map<String, Object> rowByCode(List<Map<String, Object>> rows, String code) {
    return rows.stream()
        .filter(r -> code.equals(r.get("skuCode")))
        .findFirst()
        .orElseThrow(() -> new AssertionError("清单里没有 SKU " + code + "：" + rows));
  }

  /** 异常链最底层的 SQL 异常（DB 触发器的拒绝理由就在那里）。 */
  static java.sql.SQLException rootSqlException(Throwable t) {
    var cur = t;
    while (cur.getCause() != null && cur.getCause() != cur) {
      cur = cur.getCause();
    }
    return cur instanceof java.sql.SQLException sql ? sql : null;
  }

  /** 异常链上的全部消息拼起来（DB 触发器的拒绝理由可能在中间某一层）。 */
  static String allMessages(Throwable t) {
    StringBuilder sb = new StringBuilder();
    for (var cur = t; cur != null; cur = cur.getCause()) {
      if (cur.getMessage() != null) {
        sb.append(cur.getMessage()).append('\n');
      }
    }
    return sb.toString();
  }

  /**
   * 按供应商登记一条采购起订额 / 生产周期配置。
   *
   * <p>刻意做成<b>按供应商</b>而不是租户默认：不同供应商的报价用不同的采购单位与币种，
   * 租户级的起订额会被套到不该套的 SKU 上。
   */
  void seedOrderPolicy(
      String bearer,
      UUID tenant,
      UUID skuId,
      String minOrderAmount,
      String currency,
      Integer productionDays)
      throws Exception {
    var candidate = quoteRows.get(tenant).get(skuId).get(0);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("supplierId", String.valueOf(candidate.get("supplierId")));
    body.put("supplierRoleId", String.valueOf(candidate.get("supplierRoleId")));
    body.put("minOrderAmount", new BigDecimal(minOrderAmount));
    body.put("currency", currency);
    if (productionDays != null) {
      body.put("productionLeadTimeDays", productionDays);
    }
    body.put("effectiveFrom", "2026-01-01");
    body.put("note", "测试用采购门槛");
    http.perform(
            post("/trade/replenishment-suggestions/order-policies")
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)))
        .andExpect(status().isOk());
  }

  /** 登记 A 租户的主要销售速度（每天 10 件）。 */
  void seedVelocities(String bearer) throws Exception {
    registerVelocity(bearer, skuFor(a, 1), "SKU-A-01", "10", LocalDate.of(2026, 1, 1));
    registerVelocity(bearer, skuFor(a, 2), "SKU-A-02", "10", LocalDate.of(2026, 1, 1));
    registerVelocity(bearer, skuFor(a, 4), "SKU-A-04", "10", LocalDate.of(2026, 1, 1));
    registerVelocity(bearer, skuFor(a, 5), "SKU-A-05", "10", LocalDate.of(2026, 1, 1));
    registerVelocity(bearer, skuFor(a, 6), "SKU-A-06", "10", LocalDate.of(2026, 1, 1));
  }

  // ================================================================ 主干：建议量与取整

  @Test
  void 建议量按目标库存减可用再按箱规与起订量取整() throws Exception {
    seedVelocities("Bearer tenant-a");
    seedOrderPolicy("Bearer tenant-a", a, skuFor(a, 1), "300", "USD", null);
    compute("Bearer tenant-a");

    var rows = rowsOf(suggestions("Bearer tenant-a"), "rows");
    var need = rowByCode(rows, "SKU-A-01");

    // 目标库存 = 10 件/天 × 30 天 = 300；可用 50；覆盖窗口内在途 0 ⇒ 净需求 250。
    assertThat(need.get("dailySalesQuantity")).isEqualTo(10.0);
    assertThat(need.get("targetStockQuantity")).isEqualTo(300.0);
    assertThat(need.get("availableQuantity")).isEqualTo(50.0);
    assertThat(need.get("rawRequiredQuantity")).isEqualTo(250.0);
    // 覆盖窗口之后才到的 999 与没有 ETA 的 500 都不计入，但**在途合计照实给**。
    assertThat(need.get("inTransitQuantity")).isEqualTo(1499.0);
    assertThat(need.get("inTransitArrivingQuantity")).isEqualTo(0.0);

    // 250 ÷ 12 = 20.83 箱 → 21 箱；MOQ 10 箱已满足；采购起订额 300 USD ⇒ 抬到 22 箱。
    assertThat(need.get("boxSpecQuantity")).isEqualTo(12.0);
    assertThat(need.get("moqQuantity")).isEqualTo(10.0);
    assertThat(need.get("suggestedPurchaseQuantity")).isEqualTo(22.0);
    assertThat(need.get("suggestedQuantity")).isEqualTo(264.0);
    assertThat(need.get("roundingAdjustmentQuantity")).isEqualTo(14.0);
    assertThat(String.valueOf(need.get("roundingNote"))).contains("向上取整").contains("采购起订额");

    // 预计费用 = 22 箱 × 14.2857 = 314.2854 USD。
    assertThat(need.get("estimatedCost")).isEqualTo(314.2854);
    assertThat(need.get("currency")).isEqualTo("USD");
    assertThat(need.get("unitPrice")).isEqualTo(14.2857);

    // 建议供应商 + 预计到货日（评估日 + 交期 15 天 + 生产周期 0 天）。
    assertThat(need.get("supplierName")).isEqualTo("宏远科技");
    assertThat(need.get("leadTimeDays")).isEqualTo(15);
    assertThat(need.get("expectedArrivalDate")).isEqualTo("2026-01-30");
    assertThat(String.valueOf(need.get("arrivalBasis"))).contains("头程时效未交付");
    assertThat(String.valueOf(need.get("supplierSelectionBasis"))).contains("宏远科技");

    assertThat(need.get("advice")).isEqualTo("REPLENISH");
    assertThat(need.get("status")).isEqualTo("SUGGESTED");
  }

  @Test
  void 库存已覆盖目标时给无需补货而不是零建议() throws Exception {
    seedVelocities("Bearer tenant-a");
    compute("Bearer tenant-a");
    var rows = rowsOf(suggestions("Bearer tenant-a"), "rows");
    var enough = rowByCode(rows, "SKU-A-02");
    assertThat(enough.get("advice")).isEqualTo("HOLD");
    assertThat(enough.get("rawRequiredQuantity")).isEqualTo(0.0);
    assertThat(enough.get("suggestedQuantity")).isNull();
  }

  @Test
  void 没有销售速度登记时给无数据而不是零() throws Exception {
    seedVelocities("Bearer tenant-a");
    compute("Bearer tenant-a");
    var rows = rowsOf(suggestions("Bearer tenant-a"), "rows");
    var noVelocity = rowByCode(rows, "SKU-A-03");
    assertThat(noVelocity.get("advice")).isEqualTo("NO_SALES_DATA");
    assertThat(noVelocity.get("dailySalesQuantity")).isNull();
    assertThat(noVelocity.get("targetStockQuantity")).isNull();
    assertThat(noVelocity.get("rawRequiredQuantity")).isNull();
    assertThat(noVelocity.get("suggestedQuantity")).isNull();
  }

  @Test
  void 没有有效报价时给不出建议量但净需求照实给() throws Exception {
    seedVelocities("Bearer tenant-a");
    compute("Bearer tenant-a");
    var rows = rowsOf(suggestions("Bearer tenant-a"), "rows");
    var noQuote = rowByCode(rows, "SKU-A-04");
    assertThat(noQuote.get("advice")).isEqualTo("NO_QUOTE");
    assertThat(noQuote.get("rawRequiredQuantity")).isEqualTo(250.0);
    assertThat(noQuote.get("suggestedQuantity")).isNull();
    assertThat(noQuote.get("supplierName")).isEqualTo("");
  }

  @Test
  void 箱规量不到时给不出建议量而不是按一件算() throws Exception {
    seedVelocities("Bearer tenant-a");
    compute("Bearer tenant-a");
    var rows = rowsOf(suggestions("Bearer tenant-a"), "rows");
    var noBox = rowByCode(rows, "SKU-A-05");
    assertThat(noBox.get("advice")).isEqualTo("NO_BOX_SPEC");
    assertThat(noBox.get("boxSpecQuantity")).isNull();
    assertThat(noBox.get("suggestedQuantity")).isNull();
    assertThat(noBox.get("rawRequiredQuantity")).isEqualTo(250.0);
  }

  @Test
  void 覆盖窗口内能到的在途会从净需求里扣掉() throws Exception {
    seedVelocities("Bearer tenant-a");
    compute("Bearer tenant-a");
    var rows = rowsOf(suggestions("Bearer tenant-a"), "rows");
    var withTransit = rowByCode(rows, "SKU-A-06");
    // 目标 300 − 可用 50 − 覆盖窗口内在途 200 = 50 ⇒ 50 ÷ 12 = 4.17 箱 → 5 箱，
    // 但报价的 MOQ 是 10 箱 ⇒ 抬到 10 箱 = 120 件（起订量是真实门槛，不是摆设）。
    assertThat(withTransit.get("inTransitArrivingQuantity")).isEqualTo(200.0);
    assertThat(withTransit.get("rawRequiredQuantity")).isEqualTo(50.0);
    assertThat(withTransit.get("suggestedPurchaseQuantity")).isEqualTo(10.0);
    assertThat(withTransit.get("suggestedQuantity")).isEqualTo(120.0);
    assertThat(String.valueOf(withTransit.get("roundingNote"))).contains("起订量");
  }

  // ================================================================ 计算过程可见

  @Test
  void 每条建议都能展开看到逐项计算过程() throws Exception {
    seedVelocities("Bearer tenant-a");
    compute("Bearer tenant-a");
    var rows = rowsOf(suggestions("Bearer tenant-a"), "rows");
    var need = rowByCode(rows, "SKU-A-01");
    String id = String.valueOf(need.get("id"));

    var result =
        http.perform(
                get("/trade/replenishment-suggestions/suggestions/" + id)
                    .header("Authorization", "Bearer tenant-a"))
            .andExpect(status().isOk())
            .andReturn();
    var detail = json.readTree(result.getResponse().getContentAsString());
    var steps = detail.get("steps");
    assertThat(steps.size()).isGreaterThanOrEqualTo(10);
    List<String> kinds = new ArrayList<>();
    for (var step : steps) {
      kinds.add(step.get("stepKind").asText());
      // 每一步都要有依据原文与来源 —— 「这个数是怎么来的」是蓝图明确要求的。
      assertThat(step.get("basis").asText()).isNotBlank();
      assertThat(step.get("sourceRef").asText()).isNotBlank();
    }
    assertThat(kinds)
        .contains(
            "DAILY_SALES",
            "COVERAGE_DAYS",
            "TARGET_STOCK",
            "AVAILABLE",
            "IN_TRANSIT_TOTAL",
            "IN_TRANSIT_ARRIVING",
            "RAW_REQUIRED",
            "BOX_SPEC",
            "MOQ",
            "MIN_ORDER_AMOUNT",
            "ROUNDED_QUANTITY",
            "UNIT_PRICE",
            "ESTIMATED_COST",
            "SUPPLIER_LEAD_TIME",
            "ARRIVAL_DATE");
  }

  // ================================================================ 覆盖率与增长率

  @Test
  void 按sku配置的覆盖天数优先于计算时的兜底值() throws Exception {
    seedVelocities("Bearer tenant-a");
    http.perform(
            post("/trade/replenishment-suggestions/coverage-policies")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "scope", "SKU",
                            "skuId", skuFor(a, 1).toString(),
                            "coverageDays", 60,
                            "effectiveFrom", "2026-01-01"))))
        .andExpect(status().isOk());
    compute("Bearer tenant-a");
    var rows = rowsOf(suggestions("Bearer tenant-a"), "rows");
    var need = rowByCode(rows, "SKU-A-01");
    assertThat(need.get("coverageDays")).isEqualTo(60);
    assertThat(need.get("coverageSource")).isEqualTo("SKU");
    // 目标库存 = 10 × 60 = 600 − 50 = 550 ⇒ 550 ÷ 12 = 45.83 → 46 箱（MOQ 10 已满足）→ 552 件。
    assertThat(need.get("targetStockQuantity")).isEqualTo(600.0);
    assertThat(need.get("rawRequiredQuantity")).isEqualTo(550.0);
    assertThat(need.get("suggestedPurchaseQuantity")).isEqualTo(46.0);
    assertThat(need.get("suggestedQuantity")).isEqualTo(552.0);
  }

  @Test
  void 新的覆盖天数配置会收口旧配置而不是覆盖() throws Exception {
    http.perform(
            post("/trade/replenishment-suggestions/coverage-policies")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "scope", "DEFAULT",
                            "coverageDays", 30,
                            "effectiveFrom", "2026-01-01"))))
        .andExpect(status().isOk());
    http.perform(
            post("/trade/replenishment-suggestions/coverage-policies")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "scope", "DEFAULT",
                            "coverageDays", 45,
                            "effectiveFrom", "2026-02-01"))))
        .andExpect(status().isOk());

    var page =
        http.perform(
                get("/trade/replenishment-suggestions/coverage-policies")
                    .header("Authorization", "Bearer tenant-a"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var rows = rowsOf(page, "rows");
    assertThat(rows).hasSize(2);
    var closed = rows.stream().filter(r -> Integer.valueOf(30).equals(r.get("coverageDays"))).findFirst().orElseThrow();
    assertThat(closed.get("effectiveTo")).isEqualTo("2026-02-01");
    assertThat(closed.get("effective")).isEqualTo(false);
  }

  @Test
  void 同一作用范围不能有两条未收口的覆盖天数配置() throws Exception {
    http.perform(
            post("/trade/replenishment-suggestions/coverage-policies")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "scope", "DEFAULT",
                            "coverageDays", 30,
                            "effectiveFrom", "2026-01-01"))))
        .andExpect(status().isOk());
    // 生效起点不晚于已有配置 ⇒ 409（服务层挡住，因为同一时间点会有两个覆盖天数）。
    http.perform(
            post("/trade/replenishment-suggestions/coverage-policies")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "scope", "DEFAULT",
                            "coverageDays", 40,
                            "effectiveFrom", "2026-01-01"))))
        .andExpect(status().isBadRequest());
  }

  @Test
  void 近期增长率由当前版本与上一个已收口版本相比得出() throws Exception {
    // 先登记一版 8 件/天（2026-01-01 起），再登记一版 10 件/天（2026-02-01 起）——
    // 评估日 2026-01-15 落在**第一版**上，所以此时的增长率还答不出来。
    registerVelocity("Bearer tenant-a", skuFor(a, 1), "SKU-A-01", "8", LocalDate.of(2026, 1, 1));
    registerVelocity("Bearer tenant-a", skuFor(a, 1), "SKU-A-01", "10", LocalDate.of(2026, 2, 1));
    for (int ordinal : new int[] {2, 4, 5, 6}) {
      registerVelocity(
          "Bearer tenant-a",
          skuFor(a, ordinal),
          "SKU-A-0" + ordinal,
          "10",
          LocalDate.of(2026, 1, 1));
    }
    compute("Bearer tenant-a");
    var rows = rowsOf(suggestions("Bearer tenant-a"), "rows");
    var need = rowByCode(rows, "SKU-A-01");
    assertThat(need.get("dailySalesQuantity")).isEqualTo(8.0);
    assertThat(need.get("previousDailySalesQuantity")).isNull();
    assertThat(need.get("growthRate")).isNull();

    // 评估日改到 2026-03-01（第二版生效，第一版已收口）⇒ 增长率 = (10 − 8) ÷ 8 = 0.25。
    var result =
        http.perform(
                post("/trade/replenishment-suggestions/batches")
                    .header("Authorization", "Bearer tenant-a")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(
                            Map.of("asOfDate", "2026-03-01", "defaultCoverageDays", COVERAGE))))
            .andExpect(status().isOk())
            .andReturn();
    var batch = json.readTree(result.getResponse().getContentAsString()).get("batchId").asText();
    var page =
        http.perform(
                get("/trade/replenishment-suggestions/suggestions?batchId=" + batch)
                    .header("Authorization", "Bearer tenant-a"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var later = rowByCode(rowsOf(page, "rows"), "SKU-A-01");
    assertThat(later.get("dailySalesQuantity")).isEqualTo(10.0);
    assertThat(later.get("previousDailySalesQuantity")).isEqualTo(8.0);
    assertThat(later.get("growthRate")).isEqualTo(0.25);
  }

  // ================================================================ 采购起订额与生产周期

  @Test
  void 采购起订额按供应商配置参与取整且生产周期进入预计到货日() throws Exception {
    seedVelocities("Bearer tenant-a");
    seedOrderPolicy("Bearer tenant-a", a, skuFor(a, 1), "300", "USD", 7);
    compute("Bearer tenant-a");
    var rows = rowsOf(suggestions("Bearer tenant-a"), "rows");
    var need = rowByCode(rows, "SKU-A-01");
    assertThat(need.get("minOrderAmount")).isEqualTo(300.0);
    assertThat(need.get("suggestedPurchaseQuantity")).isEqualTo(22.0);
    assertThat(need.get("productionLeadTimeDays")).isEqualTo(7);
    // 评估日 2026-01-15 + 交期 15 天 + 生产周期 7 天 = 2026-02-06。
    assertThat(need.get("expectedArrivalDate")).isEqualTo("2026-02-06");
  }

  @Test
  void 起订额币种与报价币种不同则不参与取整并写明原因() throws Exception {
    seedVelocities("Bearer tenant-a");
    seedOrderPolicy("Bearer tenant-a", a, skuFor(a, 1), "300", "CNY", null);
    compute("Bearer tenant-a");
    var rows = rowsOf(suggestions("Bearer tenant-a"), "rows");
    var need = rowByCode(rows, "SKU-A-01");
    // 21 箱 × 14.2857 = 299.9997 < 300 —— 但币种不同，不做起订额取整 ⇒ 仍是 21 箱。
    assertThat(need.get("suggestedPurchaseQuantity")).isEqualTo(21.0);
    assertThat(String.valueOf(need.get("roundingNote"))).contains("跨币种");
  }

  // ================================================================ 处置（接受 / 调整 / 忽略）

  @Test
  void 接受建议会落库处置人处置时间与处置说明() throws Exception {
    seedVelocities("Bearer tenant-a");
    seedOrderPolicy("Bearer tenant-a", a, skuFor(a, 1), "300", "USD", null);
    compute("Bearer tenant-a");
    var rows = rowsOf(suggestions("Bearer tenant-a"), "rows");
    String id = String.valueOf(rowByCode(rows, "SKU-A-01").get("id"));

    var result =
        http.perform(
                post("/trade/replenishment-suggestions/suggestions/" + id + "/decision")
                    .header("Authorization", "Bearer tenant-a")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(
                            Map.of("action", "ACCEPT", "note", "按建议下单，走季度框架协议"))))
            .andExpect(status().isOk())
            .andReturn();
    var decided = json.readTree(result.getResponse().getContentAsString());
    assertThat(decided.get("status").asText()).isEqualTo("ACCEPTED");
    assertThat(decided.get("decidedQuantity").asDouble()).isEqualTo(264.0);
    assertThat(decided.get("decisionNote").asText()).isEqualTo("按建议下单，走季度框架协议");
    assertThat(decided.get("decisionBy").asText()).isEqualTo(user.toString());
    assertThat(decided.get("decisionAt").asText()).isNotBlank();

    // 处置留痕真的落库。
    var detail =
        http.perform(
                get("/trade/replenishment-suggestions/suggestions/" + id)
                    .header("Authorization", "Bearer tenant-a"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var decisions = rowsOf(detail, "decisions");
    assertThat(decisions).hasSize(1);
    assertThat(decisions.get(0).get("action")).isEqualTo("ACCEPT");
    assertThat(decisions.get(0).get("note")).isEqualTo("按建议下单，走季度框架协议");
    assertThat(decisions.get(0).get("decidedBy")).isEqualTo(user.toString());
  }

  @Test
  void 调整建议时给出的数量会被记住() throws Exception {
    seedVelocities("Bearer tenant-a");
    compute("Bearer tenant-a");
    var rows = rowsOf(suggestions("Bearer tenant-a"), "rows");
    String id = String.valueOf(rowByCode(rows, "SKU-A-01").get("id"));

    http.perform(
            post("/trade/replenishment-suggestions/suggestions/" + id + "/decision")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of("action", "ADJUST", "quantity", new BigDecimal("300"), "note", "大促备货，加到 300 件"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ADJUSTED"))
        .andExpect(jsonPath("$.decidedQuantity").value(300.0));
  }

  @Test
  void 忽略建议时不带数量但要写明理由() throws Exception {
    seedVelocities("Bearer tenant-a");
    compute("Bearer tenant-a");
    var rows = rowsOf(suggestions("Bearer tenant-a"), "rows");
    String id = String.valueOf(rowByCode(rows, "SKU-A-01").get("id"));

    http.perform(
            post("/trade/replenishment-suggestions/suggestions/" + id + "/decision")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of("action", "IGNORE", "note", "这个 SKU 下季度要清货，不再补货"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("IGNORED"))
        .andExpect(jsonPath("$.decidedQuantity").doesNotExist());
  }

  @Test
  void 处置说明不能为空() throws Exception {
    seedVelocities("Bearer tenant-a");
    compute("Bearer tenant-a");
    var rows = rowsOf(suggestions("Bearer tenant-a"), "rows");
    String id = String.valueOf(rowByCode(rows, "SKU-A-01").get("id"));
    http.perform(
            post("/trade/replenishment-suggestions/suggestions/" + id + "/decision")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("action", "ACCEPT", "note", ""))))
        .andExpect(status().isBadRequest());
  }

  @Test
  void 调整时不给数量会被拒() throws Exception {
    seedVelocities("Bearer tenant-a");
    compute("Bearer tenant-a");
    var rows = rowsOf(suggestions("Bearer tenant-a"), "rows");
    String id = String.valueOf(rowByCode(rows, "SKU-A-01").get("id"));
    http.perform(
            post("/trade/replenishment-suggestions/suggestions/" + id + "/decision")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("action", "ADJUST", "note", "改一下"))))
        .andExpect(status().isBadRequest());
  }

  @Test
  void 答不出来的建议不能被处置() throws Exception {
    seedVelocities("Bearer tenant-a");
    compute("Bearer tenant-a");
    var rows = rowsOf(suggestions("Bearer tenant-a"), "rows");
    String id = String.valueOf(rowByCode(rows, "SKU-A-03").get("id"));
    // 给「缺日均销量」的行点忽略，会把一个数据缺口伪装成一次业务决定 ⇒ 409。
    http.perform(
            post("/trade/replenishment-suggestions/suggestions/" + id + "/decision")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("action", "IGNORE", "note", "不管它"))))
        .andExpect(status().isConflict());
  }

  // ================================================================ DB 级不变量

  @Test
  void 建议的计算结论不可改写() throws Exception {
    seedVelocities("Bearer tenant-a");
    compute("Bearer tenant-a");
    var rows = rowsOf(suggestions("Bearer tenant-a"), "rows");
    String id = String.valueOf(rowByCode(rows, "SKU-A-01").get("id"));

    // 用表 owner 直连 SQL 改结论 ⇒ 触发器必须挡住（23514）。
    assertThatThrownBy(
            () -> {
              try (var c =
                      DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
                  var s = c.createStatement()) {
                s.execute(
                    "update replenishment_suggestion set suggested_quantity = 1 where id = '"
                        + id
                        + "'::uuid");
              }
            })
        .as("改建议量等于追溯改写「当时算出的是多少」")
        .satisfies(
            e -> {
              assertThat(allMessages(e)).contains("计算结论不可改写");
              var sqlError = rootSqlException(e);
              assertThat((Object) sqlError)
                  .as("必须是 DB 层拒绝（23514），不是服务层挡的")
                  .isNotNull();
              assertThat(sqlError.getSQLState()).isEqualTo("23514");
            });
  }

  @Test
  void 处置留痕只追加不可改写() throws Exception {
    seedVelocities("Bearer tenant-a");
    seedOrderPolicy("Bearer tenant-a", a, skuFor(a, 1), "300", "USD", null);
    compute("Bearer tenant-a");
    var rows = rowsOf(suggestions("Bearer tenant-a"), "rows");
    String id = String.valueOf(rowByCode(rows, "SKU-A-01").get("id"));
    http.perform(
            post("/trade/replenishment-suggestions/suggestions/" + id + "/decision")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("action", "ACCEPT", "note", "接受"))))
        .andExpect(status().isOk());

    assertThatThrownBy(
            () -> {
              try (var c =
                      DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
                  var s = c.createStatement()) {
                s.execute("update replenishment_suggestion_decision set note = '改掉'");
              }
            })
        .as("处置留痕必须 append-only")
        .satisfies(
            e -> {
              assertThat(allMessages(e)).contains("不可改写");
              var sqlError = rootSqlException(e);
              assertThat((Object) sqlError).isNotNull();
              assertThat(sqlError.getSQLState()).isEqualTo("23514");
            });
  }

  @Test
  void 覆盖天数的口径不可改只能收口() throws Exception {
    http.perform(
            post("/trade/replenishment-suggestions/coverage-policies")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "scope", "DEFAULT",
                            "coverageDays", 30,
                            "effectiveFrom", "2026-01-01"))))
        .andExpect(status().isOk());
    assertThatThrownBy(
            () -> {
              try (var c =
                      DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
                  var s = c.createStatement()) {
                s.execute("update replenishment_coverage_policy set coverage_days = 45");
              }
            })
        .as("覆盖天数的口径不可改 —— 改了就查不回来「当时按哪个数算的」")
        .satisfies(
            e -> {
              assertThat(allMessages(e)).contains("口径不可改");
              var sqlError = rootSqlException(e);
              assertThat((Object) sqlError).isNotNull();
              assertThat(sqlError.getSQLState()).isEqualTo("23514");
            });
  }

  @Test
  void 处置时间在库里写回的是同一刻() throws Exception {
    seedVelocities("Bearer tenant-a");
    seedOrderPolicy("Bearer tenant-a", a, skuFor(a, 1), "300", "USD", null);
    compute("Bearer tenant-a");
    var rows = rowsOf(suggestions("Bearer tenant-a"), "rows");
    String id = String.valueOf(rowByCode(rows, "SKU-A-01").get("id"));
    http.perform(
            post("/trade/replenishment-suggestions/suggestions/" + id + "/decision")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("action", "ACCEPT", "note", "接受"))))
        .andExpect(status().isOk());

    // timestamptz 往返：用 UTC 的读出口比对，差一秒都算偏了（-28800s 那种偏法必须被抓住）。
    // ⚠️ 读库必须在**绑定了租户**的事务里做 —— 事务外没有 app.current_tenant，
    // RLS 会让这一行直接看不见（那不是「数据没了」，是「没绑租户」）。
    var stored =
        access.run(
            actors.get("Bearer tenant-a"),
            "trade.replenishment.read",
            () ->
                sql.fetchOne(
                        "select decision_at from replenishment_suggestion where id = ?::uuid",
                        UUID.fromString(id))
                    .get(0, java.time.OffsetDateTime.class));
    assertThat(stored).isNotNull();
    assertThat(stored.toInstant())
        .isCloseTo(
            java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).toInstant(),
            org.assertj.core.api.Assertions.within(5, java.time.temporal.ChronoUnit.MINUTES));
    // 库里那一列用 timestamptz（带时区），不是 timestamp（不带时区 ⇒ 读出来会偏 8 小时）。
    var columnType =
        sql.fetchOne(
                "select data_type from information_schema.columns"
                    + " where table_name = 'replenishment_suggestion' and column_name = 'decision_at'")
            .get(0, String.class);
    assertThat(columnType).isEqualTo("timestamp with time zone");
  }

  // ================================================================ 清单 / 分页 / 筛选

  @Test
  void 清单支持真分页与按建议类别筛选() throws Exception {
    seedVelocities("Bearer tenant-a");
    compute("Bearer tenant-a");
    var page =
        http.perform(
                get("/trade/replenishment-suggestions/suggestions?onlyActionable=true&size=2")
                    .header("Authorization", "Bearer tenant-a"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var parsed = json.readTree(page);
    assertThat(parsed.get("total").asInt()).isEqualTo(2); // SKU-A-01 与 SKU-A-06
    assertThat(parsed.get("rows").size()).isEqualTo(2);
    for (var row : parsed.get("rows")) {
      assertThat(row.get("advice").asText()).isEqualTo("REPLENISH");
    }

    var hold =
        http.perform(
                get("/trade/replenishment-suggestions/suggestions?advice=HOLD")
                    .header("Authorization", "Bearer tenant-a"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(json.readTree(hold).get("total").asInt()).isEqualTo(1);
    assertThat(json.readTree(hold).get("rows").get(0).get("skuCode").asText()).isEqualTo("SKU-A-02");
  }

  @Test
  void 批次列表能读到历史批次() throws Exception {
    seedVelocities("Bearer tenant-a");
    compute("Bearer tenant-a");
    compute("Bearer tenant-a");
    var page =
        http.perform(get("/trade/replenishment-suggestions/batches").header("Authorization", "Bearer tenant-a"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(rowsOf(page, "rows")).hasSize(2);
  }

  // ================================================================ 上游故障与权限

  @Test
  void 商品主数据不可达时显式503而不是给一份零建议的清单() throws Exception {
    seedVelocities("Bearer tenant-a");
    catalogDown.set(true);
    var result =
        http.perform(
                post("/trade/replenishment-suggestions/batches")
                    .header("Authorization", "Bearer tenant-a")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(
                            Map.of("asOfDate", AS_OF.toString(), "defaultCoverageDays", COVERAGE))))
            .andReturn();
    // 显式 503，而不是给一份「零建议」的清单（那会被读成「上游说不用补」）。
    assertThat(result.getResponse().getStatus()).isEqualTo(503);
  }

  @Test
  void 只读身份不能发起计算() throws Exception {
    http.perform(
            post("/trade/replenishment-suggestions/batches")
                .header("Authorization", "Bearer read-only")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of("asOfDate", AS_OF.toString(), "defaultCoverageDays", COVERAGE))))
        .andExpect(status().isForbidden());
  }

  @Test
  void 能力声明把上游的真实状态与缺口说清楚() throws Exception {
    http.perform(get("/trade/replenishment-suggestions/capabilities").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.supplierRatingAvailable").value(false))
        .andExpect(jsonPath("$.firstMileLeadTimeAvailable").value(false))
        .andExpect(jsonPath("$.purchaseRequestAvailable").value(false))
        .andExpect(jsonPath("$.salesVelocityAvailable").value(true))
        .andExpect(jsonPath("$.quoteAvailable").value(true))
        .andExpect(jsonPath("$.boxSpecAvailable").value(true))
        .andExpect(jsonPath("$.arrivalFormula").value(org.hamcrest.Matchers.containsString("头程时效未交付")));
  }
}
