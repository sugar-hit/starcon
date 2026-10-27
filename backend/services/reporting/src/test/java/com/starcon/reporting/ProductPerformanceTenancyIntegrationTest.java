package com.starcon.reporting;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.starcon.tenancy.TenantTransactions;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jooq.DSLContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 13.3.1.1 产品表现分析的**多租户隔离测试**（真实 PostgreSQL 16 + FORCE RLS，无内存替身）。
 *
 * <p>跑的是 AGENTS.md 明文要求的三条断言（缺一不可）：
 *
 * <ol>
 *   <li><b>两租户互不可见</b>；
 *   <li><b>伪造租户上下文无效</b>（并断言行确实落在**会话租户**名下）；
 *   <li><b>连接池归还后不串租户</b>。
 * </ol>
 *
 * <p>⚠️ **假上游是按 bearer 分租户的**：上游按**已认证成员关系**解析租户（这正是本服务
 * 转发的语义 —— 转发调用方自己的 bearer，不传租户号）。所以租户 A 的上游只给 A 的 SKU，
 * 租户 B 的上游只给 B 的 SKU。这样「B 看不见 A 的事实」才能真正证明「B 只看得见自己的」。
 *
 * <p>⚠️ <b>连接池固定为 1 条连接</b>：只有复用同一条连接，才能真正证明归还后不会串租户。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ProductPerformanceTenancyIntegrationTest {

  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  static HttpServer upstream;

  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  static final List<String> permissions =
      List.of(
          "reporting.product-performance.read",
          // ⚠️ 写权限必须在**同一个 grant** 里同时命中对应的 .read ——
          //   服务端的判据是「能写不能读」的组合不放行，所以这里必须带上
          //   reporting.projection.read，否则刷新端点直接 403。
          "reporting.projection.read",
          "reporting.projection.write");

  static final ObjectMapper JSON = JsonMapper.builder().addModule(new JavaTimeModule()).build();

  static final LocalDate DAY1 = LocalDate.of(2026, 10, 1);
  static final LocalDate DAY2 = LocalDate.of(2026, 10, 2);
  static final LocalDate BASE1 = LocalDate.of(2026, 9, 29);
  static final LocalDate BASE2 = LocalDate.of(2026, 9, 30);

  static final ZoneId ZONE = ZoneId.of("UTC");

  static final String BEARER_A = "Bearer tenant-a";
  static final String BEARER_B = "Bearer tenant-b";

  /** 一路上游的数据（按 bearer 分租户）。 */
  record UpstreamData(
      List<Map<String, Object>> skus,
      List<Map<String, Object>> listings,
      List<Map<String, Object>> orders,
      List<Map<String, Object>> performance,
      List<Map<String, Object>> balances) {}

  static final Map<String, UpstreamData> UPSTREAM = new ConcurrentHashMap<>();

  static {
    pg.start();
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      // 运行角色必须 NOBYPASSRLS，否则它会绕过策略，三条断言全部变成空断言。
      s.execute("create role starcon_app nologin");
      s.execute(
          "create role reporting_app login password 'test-only' nosuperuser nobypassrls");
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
            byte[] body = JSON.writeValueAsBytes(access);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      actors.put(BEARER_A, actor(a));
      actors.put(BEARER_B, actor(b));
      identity.start();

      // 租户 A：SKU-A / SPU-A / Listing-A
      var skuA = UUID.randomUUID();
      var spuA = UUID.randomUUID();
      var listingA = UUID.randomUUID();
      UPSTREAM.put(
          BEARER_A,
          new UpstreamData(
              List.of(sku(skuA, "SKU-A", spuA, "SPU-A")),
              List.of(listing(listingA, "shop-a", "AMAZON", List.of(skuA))),
              List.of(order("SO-A-1", DAY1, "USD", "SKU-A", "2", "20.00")),
              List.of(performance(listingA, DAY1, "1000", "50", "40", "2", "40.00", "USD")),
              List.of(balance(skuA, "SKU-A", "10", "1", "9"))));

      // 租户 B：SKU-B / SPU-B / Listing-B
      var skuB = UUID.randomUUID();
      var spuB = UUID.randomUUID();
      var listingB = UUID.randomUUID();
      UPSTREAM.put(
          BEARER_B,
          new UpstreamData(
              List.of(sku(skuB, "SKU-B", spuB, "SPU-B")),
              List.of(listing(listingB, "shop-b", "TIKTOK", List.of(skuB))),
              List.of(order("SO-B-1", DAY1, "USD", "SKU-B", "5", "10.00")),
              List.of(performance(listingB, DAY1, "200", "10", "8", "5", "50.00", "USD")),
              List.of(balance(skuB, "SKU-B", "50", "5", "45"))));

      upstream = HttpServer.create(new InetSocketAddress(0), 0);
      upstream.createContext("/catalog/skus", e -> respond(e, skusBody(e)));
      upstream.createContext("/channel/listings", e -> respond(e, listingsBody(e)));
      upstream.createContext("/trade/sales-orders/list", e -> respond(e, ordersBody(e)));
      upstream.createContext("/trade/sales-orders/detail", e -> respond(e, detailBody(e)));
      upstream.createContext("/channel/listing-performance", e -> respond(e, performanceBody(e)));
      upstream.createContext("/warehouse/inventory/overview", e -> respond(e, balancesBody(e)));
      // 🔴 其余上游一律给**空页**（不是 404）：404 会被端口翻成 503，
      //   「上游读不到」与「上游没有数据」是两件事 —— 这里要的是后者。
      upstream.createContext("/trade/aftersales/cases", e -> respond(e, page(List.of(), "rows")));
      upstream.createContext("/finance/cost-calculation/runs", e -> respond(e, page(List.of(), "items")));
      upstream.createContext(
          "/finance/cost-calculation/entries", e -> respond(e, page(List.of(), "items")));
      upstream.createContext(
          "/warehouse/inventory/aging/slow-moving", e -> respond(e, page(List.of(), "rows")));
      upstream.createContext("/channel/ad-reports/rows", e -> respond(e, page(List.of(), "items")));
      upstream.createContext("/channel/ad-campaign-details", e -> respond(e, page(List.of(), "items")));
      upstream.createContext(
          "/channel/listing-hijackers/records", e -> respond(e, page(List.of(), "items")));
      upstream.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  static AccessService.Access actor(UUID tenant) {
    var grant = new AccessService.Grant(UUID.randomUUID(), "TEST", "ALL", permissions);
    return new AccessService.Access(
        user, tenant, "Test", UUID.randomUUID(), null, permissions, List.of(grant));
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "reporting_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://localhost:" + identity.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
    r.add("CATALOG_URL", () -> "http://localhost:" + upstream.getAddress().getPort());
    r.add("CHANNEL_URL", () -> "http://localhost:" + upstream.getAddress().getPort());
    r.add("TRADE_URL", () -> "http://localhost:" + upstream.getAddress().getPort());
    r.add("WAREHOUSE_URL", () -> "http://localhost:" + upstream.getAddress().getPort());
    r.add("FINANCE_URL", () -> "http://localhost:" + upstream.getAddress().getPort());
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  @Autowired TenantTransactions tenants;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;
  @Autowired ReportingProductSalesRefreshService productSalesRefresh;
  @Autowired ReportingProductPerformanceRefreshService refresh;
  @Autowired ReportingProductPerformanceService performance;

  @AfterAll
  static void stop() {
    identity.stop(0);
    upstream.stop(0);
    pg.stop();
  }

  @BeforeEach
  void reset() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate reporting_product_performance_fact, reporting_product_performance_snapshot,"
              + " reporting_product_performance_metric_definition, reporting_product_metric_fact,"
              + " reporting_product_drilldown_fact, reporting_product_metric_definition,"
              + " reporting_product_listing_sku, reporting_product_dimension cascade");
    }
  }

  // ==========================================================================
  // 断言 1：两租户互不可见
  // ==========================================================================

  @Test
  void 断言1_两租户互不可见() {
    refreshAll(a, BEARER_A);
    refreshAll(b, BEARER_B);

    var a1 = performance.analyze(a, DAY1, DAY2, BASE1, BASE2, "SKU", "SKU-A", null);
    var b1 = performance.analyze(b, DAY1, DAY2, BASE1, BASE2, "SKU", "SKU-B", null);

    assertThat(a1.dimensionKey()).isEqualTo("SKU-A");
    assertThat(b1.dimensionKey()).isEqualTo("SKU-B");
    // ⚠️ skuIds 是**上游的 uuid**（不是业务编码）—— 编码在 dimensionKey 上。
    assertThat(a1.skuIds()).hasSize(1);
    assertThat(b1.skuIds()).hasSize(1);
    assertThat(a1.skuIds()).isNotEqualTo(b1.skuIds());

    // 更强的一格：A 明确按来源行直查 B 的行也拿不到（RLS 挡住）。
    // 🔴 查的是**快照**表：SKU 维度的库存事实落在那里（B 的那一行确实存在）。
    var crossSnapshot =
        tenants.run(
            a,
            () ->
                sql.fetch(
                        "select dimension_key from reporting_product_performance_snapshot "
                            + "where tenant_id = ? and dimension_key = ?",
                        b,
                        "SKU-B")
                    .size());
    assertThat(crossSnapshot).as("快照也必须被 RLS 挡住").isZero();

    // A 的报表里看不到 B 的商品（查 B 的 key 直接落到「找不到」）。
    var missing = performance.analyze(a, DAY1, DAY2, BASE1, BASE2, "SKU", "SKU-B", null);
    assertThat(missing.status()).isEqualTo("NOT_PROJECTED");
    assertThat(missing.emptyReason()).isEqualTo("NO_DIMENSION");
  }

  // ==========================================================================
  // 断言 2：伪造租户上下文无效
  // ==========================================================================

  @Test
  void 断言2_伪造租户头无效() throws Exception {
    refreshAll(b, BEARER_B);

    // (1) 事务外没有「环境租户」：不存在一个可以被沿用的默认租户。
    assertThat(rawTenantSetting()).isNullOrEmpty();
    assertThat(visibleFactsWithoutTenant()).as("未绑定租户时不应看到任何行").isZero();

    // (2) 事务内绑定的值精确等于可信参数，提交后立刻失效。
    var bound =
        tenants.run(
            a,
            () -> {
              assertThat(effectiveTenant()).isEqualTo(a);
              return rawTenantSetting();
            });
    assertThat(bound).isEqualTo(a.toString());
    assertThat(effectiveTenant()).as("提交后租户上下文必须已清空").isNull();
    assertThat(visibleFactsWithoutTenant()).isZero();

    // (3) HTTP 边界：带**租户 A 的合法会话** + **伪造的 X-Tenant-Id（租户 B）** ⇒
    //     投影仍然落在**会话租户 A** 名下，租户 B 看不见。
    //     ⚠️ 先刷 13.2.2.2 的商品档案（SKU / SPU / Listing 三层）—— 本模块**只读**它，
    //     没有档案时商品维度的上游事实归不到任何商品（**不猜一个 SKU**）。
    productSalesRefresh.refresh(a, BEARER_A, BASE1, DAY2, ZONE);
    http.perform(
            post("/reporting/product-performance/refresh")
                .header("Authorization", BEARER_A)
                .header("X-Tenant-Id", b.toString())
                .param("from", DAY1.toString())
                .param("to", DAY2.toString()))
        .andExpect(status().isOk());

    var landedInA =
        tenants.run(
            a,
            () ->
                sql.fetch(
                        "select dimension_key from reporting_product_performance_snapshot "
                            + "where tenant_id = ? and dimension_key = ?",
                        a,
                        "SKU-A")
                    .size());
    assertThat(landedInA).as("伪造头不得让投影落到别的租户名下").isPositive();

    var landedInB =
        tenants.run(
            b,
            () ->
                sql.fetch(
                        "select dimension_key from reporting_product_performance_snapshot "
                            + "where tenant_id = ? and dimension_key = ?",
                        b,
                        "SKU-A")
                    .size());
    assertThat(landedInB).as("被伪造的租户不得看见别人的商品事实").isZero();

    // A 的报表里只有 SKU-A；B 的报表里仍然只有它自己的 SKU-B。
    assertThat(dimensionKeysOf(a)).contains("SKU-A", "SPU-A");
    assertThat(dimensionKeysOf(a)).doesNotContain("SKU-B", "SPU-B");
    assertThat(dimensionKeysOf(b)).contains("SKU-B", "SPU-B");
    assertThat(dimensionKeysOf(b)).doesNotContain("SKU-A", "SPU-A");
  }

  // ==========================================================================
  // 断言 3：连接池归还后不串租户
  // ==========================================================================

  @Test
  void 断言3_连接池归还后不串租户() {
    refreshAll(a, BEARER_A);
    refreshAll(b, BEARER_B);
    for (int i = 0; i < 5; i++) {
      assertThat(dimensionKeysOf(a)).as("第 %d 轮租户 A", i).contains("SKU-A", "SPU-A");
      assertThat(dimensionKeysOf(a)).as("第 %d 轮租户 A 不得看到 B", i).doesNotContain("SKU-B");
      assertThat(dimensionKeysOf(b)).as("第 %d 轮租户 B", i).contains("SKU-B", "SPU-B");
      assertThat(dimensionKeysOf(b)).as("第 %d 轮租户 B 不得看到 A", i).doesNotContain("SKU-A");
      assertThat(visibleFactsWithoutTenant()).as("第 %d 轮事务外", i).isZero();
    }
    // 归还后再单独借一次，仍然干净。
    assertThat(dimensionKeysOf(a)).contains("SKU-A");
    assertThat(dimensionKeysOf(b)).contains("SKU-B");
  }

  // ==========================================================================
  // 工具
  // ==========================================================================

  private void refreshAll(UUID tenant, String bearer) {
    productSalesRefresh.refresh(tenant, bearer, BASE1, DAY2, ZONE);
    refresh.refresh(tenant, bearer, DAY1, DAY2, BASE1, BASE2, ZONE);
  }

  private List<String> dimensionKeysOf(UUID tenant) {
    return tenants.run(
        tenant,
        () ->
            sql.fetch("select dimension_key from reporting_product_dimension order by dimension_key")
                .stream()
                .map(r -> r.get(0, String.class))
                .toList());
  }

  private String rawTenantSetting() {
    return sql.fetch("select current_setting('app.current_tenant', true)").stream()
        .findFirst()
        .map(r -> r.get(0, String.class))
        .orElse(null);
  }

  private UUID effectiveTenant() {
    var row = sql.fetchOne("select nullif(current_setting('app.current_tenant', true),'')::uuid");
    return row == null ? null : row.get(0, UUID.class);
  }

  private int visibleFactsWithoutTenant() {
    return sql.fetch("select id from reporting_product_performance_fact").size()
        + sql.fetch("select id from reporting_product_performance_snapshot").size()
        + sql.fetch("select id from reporting_product_metric_fact").size();
  }

  // ---------------------------------------------------------------------------
  // 假上游（按 bearer 分租户）
  // ---------------------------------------------------------------------------

  private static UpstreamData dataOf(com.sun.net.httpserver.HttpExchange e) throws java.io.IOException {
    var bearer = e.getRequestHeaders().getFirst("Authorization");
    var data = UPSTREAM.get(bearer);
    if (data == null) {
      e.sendResponseHeaders(401, -1);
      e.close();
      return null;
    }
    return data;
  }

  private static void respond(com.sun.net.httpserver.HttpExchange e, Object body) {
    try {
      if (body == null) {
        e.sendResponseHeaders(404, -1);
        e.close();
        return;
      }
      byte[] bytes = JSON.writeValueAsBytes(body);
      e.getResponseHeaders().add("Content-Type", "application/json");
      e.sendResponseHeaders(200, bytes.length);
      e.getResponseBody().write(bytes);
      e.close();
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }

  /** 造一个保持顺序的 Map（{@code Map.of} 最多 10 对，夹具常常超）。 */
  private static Map<String, Object> map(Object... pairs) {
    var out = new LinkedHashMap<String, Object>();
    for (int i = 0; i + 1 < pairs.length; i += 2) {
      out.put(String.valueOf(pairs[i]), pairs[i + 1]);
    }
    return out;
  }

  private static Map<String, Object> page(List<Map<String, Object>> rows, String field) {
    var body = new LinkedHashMap<String, Object>();
    body.put(field, rows);
    body.put("page", 0);
    body.put("size", 200);
    body.put("total", rows.size());
    return body;
  }

  private static Map<String, Object> skusBody(com.sun.net.httpserver.HttpExchange e)
      throws java.io.IOException {
    var data = dataOf(e);
    if (data == null) return null;
    return page(data.skus(), "items");
  }

  private static Map<String, Object> listingsBody(com.sun.net.httpserver.HttpExchange e)
      throws java.io.IOException {
    var data = dataOf(e);
    if (data == null) return null;
    return page(data.listings(), "items");
  }

  private static Map<String, Object> performanceBody(com.sun.net.httpserver.HttpExchange e)
      throws java.io.IOException {
    var data = dataOf(e);
    if (data == null) return null;
    return page(data.performance(), "items");
  }

  private static Map<String, Object> balancesBody(com.sun.net.httpserver.HttpExchange e)
      throws java.io.IOException {
    var data = dataOf(e);
    if (data == null) return null;
    return page(data.balances(), "rows");
  }

  private static Map<String, Object> ordersBody(com.sun.net.httpserver.HttpExchange e)
      throws java.io.IOException {
    var data = dataOf(e);
    if (data == null) return null;
    var query = query(e.getRequestURI().getRawQuery());
    var from = parseInstant(query.get("orderedFrom"));
    var to = parseInstant(query.get("orderedTo"));
    var matched = new ArrayList<Map<String, Object>>();
    for (var row : data.orders()) {
      var at = parseInstant(String.valueOf(row.get("submittedAt")));
      if (at == null) continue;
      if (from != null && at.isBefore(from)) continue;
      if (to != null && !at.isBefore(to)) continue;
      matched.add(row);
    }
    return page(matched, "rows");
  }

  private static Map<String, Object> detailBody(com.sun.net.httpserver.HttpExchange e)
      throws java.io.IOException {
    var data = dataOf(e);
    if (data == null) return null;
    var path = e.getRequestURI().getPath();
    var id = path.substring(path.lastIndexOf('/') + 1);
    var matched = new ArrayList<Map<String, Object>>();
    for (var row : data.orders()) {
      if (!row.get("id").equals(id)) continue;
      matched.add(
          Map.of(
              "skuCode", row.get("skuCode"),
              "skuName", row.get("skuCode"),
              "quantity", row.get("quantity"),
              "unitPrice", row.get("grandTotalAmount"),
              "lineAmount", row.get("grandTotalAmount")));
    }
    var body = new LinkedHashMap<String, Object>();
    body.put("header", Map.of("orderNo", "SO", "currency", "USD"));
    body.put("lines", matched);
    return body;
  }

  private static Map<String, String> query(String raw) {
    var out = new LinkedHashMap<String, String>();
    if (raw == null || raw.isBlank()) return out;
    for (var pair : raw.split("&")) {
      var index = pair.indexOf('=');
      if (index < 0) continue;
      out.put(
          URLDecoder.decode(pair.substring(0, index), StandardCharsets.UTF_8),
          URLDecoder.decode(pair.substring(index + 1), StandardCharsets.UTF_8));
    }
    return out;
  }

  private static java.time.Instant parseInstant(String raw) {
    if (raw == null || raw.isBlank()) return null;
    try {
      return java.time.Instant.parse(raw);
    } catch (RuntimeException ignored) {
      // 落到下面按 OffsetDateTime 解析（秒为 0 时 toString() 会省略秒）。
    }
    try {
      return OffsetDateTime.parse(raw).toInstant();
    } catch (RuntimeException e) {
      return null;
    }
  }

  // ---------------------------------------------------------------------------
  // 夹具
  // ---------------------------------------------------------------------------

  private static Map<String, Object> sku(UUID id, String code, UUID spuId, String spuCode) {
    return Map.of(
        "id", id.toString(),
        "code", code,
        "name", code,
        "spuId", spuId.toString(),
        "spuCode", spuCode,
        "spuName", spuCode,
        "variantSignature", code,
        "status", "ACTIVE");
  }

  private static Map<String, Object> listing(
      UUID id, String shopCode, String platformCode, List<UUID> skuIds) {
    return Map.of(
        "id", id.toString(),
        "shopCode", shopCode,
        "shopName", shopCode,
        "platformCode", platformCode,
        "marketplaceCode", platformCode,
        "externalItemId", id.toString(),
        "title", id.toString(),
        "status", "ACTIVE",
        "mappingStatus", "MAPPED",
        "internalSkuIds", skuIds.stream().map(UUID::toString).toList());
  }

  private static Map<String, Object> order(
      String orderNo, LocalDate day, String currency, String skuCode, String quantity, String amount) {
    return Map.of(
        "id", UUID.randomUUID().toString(),
        "orderNo", orderNo,
        "skuCode", skuCode,
        "orderSource", "MANUAL",
        "currency", currency,
        "grandTotalAmount", amount,
        "quantity", quantity,
        "lineCount", 1,
        "submittedAt",
            OffsetDateTime.of(day, java.time.LocalTime.of(10, 0), ZoneOffset.UTC).toString(),
        "createdAt",
            OffsetDateTime.of(day, java.time.LocalTime.of(10, 0), ZoneOffset.UTC).toString());
  }

  private static Map<String, Object> performance(
      UUID listingId,
      LocalDate day,
      String impressions,
      String clicks,
      String sessions,
      String units,
      String sales,
      String currency) {
    return map(
        "listingId", listingId.toString(),
        "title", "listing",
        "shopCode", "shop",
        "platformCode", "AMAZON",
        "statDate", day.toString(),
        "impressions", impressions,
        "clicks", clicks,
        "sessions", sessions,
        "unitsOrdered", units,
        "salesAmount", sales,
        "currency", currency);
  }

  private static Map<String, Object> balance(
      UUID skuId, String skuCode, String onHand, String reserved, String available) {
    return Map.of(
        "balanceId", UUID.randomUUID().toString(),
        "skuId", skuId.toString(),
        "skuCode", skuCode,
        "skuName", skuCode,
        "onHand", onHand,
        "reserved", reserved,
        "available", available);
  }
}
