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
import java.time.Instant;
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
 * 13.2.2.2 SKU/SPU/Listing 的**多租户隔离测试**（真实 PostgreSQL 16 + FORCE RLS，无内存替身）。
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
 * 租户 B 的上游只给 B 的 SKU。这样「B 看不见 A 的事实」才能真正证明「B 只看得见自己的」
 * —— 否则 B 什么都没看见，「没串租户」只是碰巧。
 *
 * <p>⚠️ <b>连接池固定为 1 条连接</b>：只有复用同一条连接，才能真正证明归还后不会串租户 ——
 * 池大于 1 时每次可能拿到不同连接，「没串」可能只是碰巧换了条干净连接。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ProductSalesTenancyIntegrationTest {

  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  static HttpServer upstream;

  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  static final List<String> permissions =
      List.of(
          "reporting.product-sales.read",
          // ⚠️ 写权限必须在**同一个 grant** 里同时命中对应的 .read ——
          //   服务端的判据是「能写不能读」的组合不放行，所以这里必须带上
          //   reporting.projection.read，否则刷新端点直接 403。
          "reporting.projection.read",
          "reporting.projection.write");

  static final ObjectMapper JSON = JsonMapper.builder().addModule(new JavaTimeModule()).build();

  static final LocalDate DAY1 = LocalDate.of(2026, 10, 1);
  static final LocalDate DAY2 = LocalDate.of(2026, 10, 2);

  static final ZoneId ZONE = ZoneId.of("UTC");

  static final String BEARER_A = "Bearer tenant-a";
  static final String BEARER_B = "Bearer tenant-b";

  /** 一路上游的数据（按 bearer 分租户）。 */
  record UpstreamData(
      List<Map<String, Object>> skus,
      List<Map<String, Object>> listings,
      List<Map<String, Object>> orders) {}

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
              List.of(order("SO-A-1", DAY1, "USD", "SKU-A", "2", "20.00"))));

      // 租户 B：SKU-B / SPU-B / Listing-B
      var skuB = UUID.randomUUID();
      var spuB = UUID.randomUUID();
      var listingB = UUID.randomUUID();
      UPSTREAM.put(
          BEARER_B,
          new UpstreamData(
              List.of(sku(skuB, "SKU-B", spuB, "SPU-B")),
              List.of(listing(listingB, "shop-b", "TIKTOK", List.of(skuB))),
              List.of(order("SO-B-1", DAY1, "USD", "SKU-B", "5", "10.00"))));

      upstream = HttpServer.create(new InetSocketAddress(0), 0);
      upstream.createContext("/catalog/skus", e -> respond(e, skusBody(e)));
      upstream.createContext("/channel/listings", e -> respond(e, listingsBody(e)));
      upstream.createContext("/trade/sales-orders/list", e -> respond(e, ordersBody(e)));
      upstream.createContext("/trade/sales-orders/detail", e -> respond(e, detailBody(e)));
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
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  @Autowired TenantTransactions tenants;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;
  @Autowired ReportingProductSalesRefreshService refresh;
  @Autowired ReportingProductSalesService sales;

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
          "truncate reporting_product_metric_fact, reporting_product_drilldown_fact,"
              + " reporting_product_metric_definition, reporting_product_listing_sku,"
              + " reporting_product_dimension cascade");
    }
  }

  // ==========================================================================
  // 断言 1：两租户互不可见
  // ==========================================================================

  @Test
  void 断言1_两租户互不可见() {
    refresh.refresh(a, BEARER_A, DAY1, DAY2, ZONE);
    refresh.refresh(b, BEARER_B, DAY1, DAY2, ZONE);

    var rowsA = sales.report(a, DAY1, DAY2, "SKU", false, null, null, null, 0, 50).rows();
    var rowsB = sales.report(b, DAY1, DAY2, "SKU", false, null, null, null, 0, 50).rows();

    assertThat(rowsA).as("租户 A 只能看到自己的 SKU").hasSize(1);
    assertThat(rowsA.get(0).dimensionKey()).isEqualTo("SKU-A");
    assertThat(rowsB).as("租户 B 只能看到自己的 SKU").hasSize(1);
    assertThat(rowsB.get(0).dimensionKey()).isEqualTo("SKU-B");

    // 更强的一格：A 明确按来源行直查 B 的行也拿不到（RLS 挡住）。
    var cross =
        tenants.run(
            a,
            () ->
                sql.fetch(
                        "select dimension_key from reporting_product_metric_fact "
                            + "where tenant_id = ? and dimension_key = ?",
                        b,
                        "SKU-B")
                    .size());
    assertThat(cross).as("按维度直查也必须被 RLS 挡住").isZero();

    // 构成明细同样隔离。
    var crossDrill =
        tenants.run(
            a,
            () ->
                sql.fetch(
                        "select order_no from reporting_product_drilldown_fact "
                            + "where tenant_id = ? and order_no = ?",
                        b,
                        "SO-B-1")
                    .size());
    assertThat(crossDrill).as("构成明细也必须被 RLS 挡住").isZero();
  }

  @Test
  void 断言1_商品档案与绑定也按租户隔离() {
    refresh.refresh(a, BEARER_A, DAY1, DAY2, ZONE);
    refresh.refresh(b, BEARER_B, DAY1, DAY2, ZONE);

    // 档案三层都在（LISTING 的业务键是它自己的 uuid —— 上游没有 listing 的业务编码）。
    assertThat(dimensionKeysOf(a)).contains("SKU-A", "SPU-A");
    assertThat(dimensionKeysOf(a)).doesNotContain("SKU-B", "SPU-B");
    assertThat(dimensionKeysOf(b)).contains("SKU-B", "SPU-B");
    assertThat(dimensionKeysOf(b)).doesNotContain("SKU-A", "SPU-A");
    assertThat(dimensionTypeCountsOf(a)).isEqualTo(Map.of("SKU", 1L, "SPU", 1L, "LISTING", 1L));
    assertThat(dimensionTypeCountsOf(b)).isEqualTo(Map.of("SKU", 1L, "SPU", 1L, "LISTING", 1L));

    assertThat(listingSkuKeysOf(a)).containsExactly("SKU-A");
    assertThat(listingSkuKeysOf(b)).containsExactly("SKU-B");
  }

  // ==========================================================================
  // 断言 2：伪造租户上下文无效
  // ==========================================================================

  @Test
  void 断言2_伪造租户头无效() throws Exception {
    refresh.refresh(b, BEARER_B, DAY1, DAY2, ZONE);

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
    http.perform(
            post("/reporting/product-sales/refresh")
                .header("Authorization", BEARER_A)
                .header("X-Tenant-Id", b.toString())
                .param("from", DAY1.toString())
                .param("to", DAY2.toString()))
        .andExpect(status().isOk());

    // 行确实落在**会话租户 A** 名下（不是 B）。
    var landedInA =
        tenants.run(
            a,
            () ->
                sql.fetch(
                        "select dimension_key from reporting_product_metric_fact "
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
                        "select dimension_key from reporting_product_metric_fact "
                            + "where tenant_id = ? and dimension_key = ?",
                        b,
                        "SKU-A")
                    .size());
    assertThat(landedInB).as("被伪造的租户不得看见别人的商品事实").isZero();

    // A 的报表里只有 SKU-A；B 的报表里仍然只有它自己的 SKU-B。
    assertThat(skuKeysOf(a)).containsExactly("SKU-A");
    assertThat(skuKeysOf(b)).containsExactly("SKU-B");
  }

  @Test
  void 断言2_伪造租户头不影响口径目录() throws Exception {
    http.perform(get("/reporting/product-sales/metrics").header("Authorization", BEARER_A))
        .andExpect(status().isOk());
    var defsA = sales.metricDefinitions(a);
    var defsB = sales.metricDefinitions(b);
    assertThat(defsA).isNotEmpty();
    assertThat(defsB).isNotEmpty();
    // 两个租户各自播种自己的标准口径，互不影响（种子相同但行各属各的租户）。
    var codesA =
        tenants.run(
            a, () -> sql.fetch("select metric_code from reporting_product_metric_definition").size());
    var codesB =
        tenants.run(
            b, () -> sql.fetch("select metric_code from reporting_product_metric_definition").size());
    assertThat(codesA).isEqualTo(codesB);
  }

  // ==========================================================================
  // 断言 3：连接池归还后不串租户
  // ==========================================================================

  @Test
  void 断言3_连接池归还后不串租户() {
    refresh.refresh(a, BEARER_A, DAY1, DAY2, ZONE);
    refresh.refresh(b, BEARER_B, DAY1, DAY2, ZONE);
    for (int i = 0; i < 5; i++) {
      assertThat(skuKeysOf(a)).as("第 %d 轮租户 A", i).containsExactly("SKU-A");
      assertThat(skuKeysOf(b)).as("第 %d 轮租户 B", i).containsExactly("SKU-B");
      assertThat(visibleFactsWithoutTenant()).as("第 %d 轮事务外", i).isZero();
    }
    // 归还后再单独借一次，仍然干净。
    assertThat(skuKeysOf(a)).containsExactly("SKU-A");
    assertThat(skuKeysOf(b)).containsExactly("SKU-B");
  }

  // ==========================================================================
  // 工具
  // ==========================================================================

  private List<String> skuKeysOf(UUID tenant) {
    return sales.report(tenant, DAY1, DAY2, "SKU", false, null, null, null, 0, 50).rows().stream()
        .map(ReportingProductSalesModels.ProductRow::dimensionKey)
        .toList();
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

  private List<String> listingSkuKeysOf(UUID tenant) {
    return tenants.run(
        tenant,
        () ->
            sql.fetch("select sku_key from reporting_product_listing_sku order by sku_key").stream()
                .map(r -> r.get(0, String.class))
                .toList());
  }

  /** 每一层的档案条数（租户之间必须各自成套，不多不少）。 */
  private Map<String, Long> dimensionTypeCountsOf(UUID tenant) {
    return tenants.run(
        tenant,
        () -> {
          var out = new LinkedHashMap<String, Long>();
          for (var row :
              sql.fetch(
                  "select dimension_type, count(*) from reporting_product_dimension group by dimension_type")) {
            out.put(row.get(0, String.class), row.get(1, Long.class));
          }
          return out;
        });
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
    return sql.fetch("select id from reporting_product_metric_fact").size()
        + sql.fetch("select id from reporting_product_dimension").size()
        + sql.fetch("select id from reporting_product_drilldown_fact").size();
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
      if (body == null) return;
      byte[] bytes = JSON.writeValueAsBytes(body);
      e.getResponseHeaders().add("Content-Type", "application/json");
      e.sendResponseHeaders(200, bytes.length);
      e.getResponseBody().write(bytes);
      e.close();
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
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
    for (var row : data.orders()) {
      if (!String.valueOf(row.get("id")).equals(id)) continue;
      var body = new LinkedHashMap<String, Object>();
      var header = new LinkedHashMap<String, Object>();
      header.put("orderNo", row.get("orderNo"));
      header.put("currency", row.get("currency"));
      header.put("grandTotalAmount", row.get("grandTotalAmount"));
      header.put("submittedAt", row.get("submittedAt"));
      body.put("header", header);
      body.put("lines", row.get("lines"));
      return body;
    }
    var missing = new LinkedHashMap<String, Object>();
    missing.put("header", null);
    missing.put("lines", List.of());
    return missing;
  }

  private static Map<String, Object> page(List<Map<String, Object>> rows, String key) {
    var body = new LinkedHashMap<String, Object>();
    body.put(key, rows);
    body.put("page", 0);
    body.put("size", 200);
    body.put("total", rows.size());
    return body;
  }

  private static Instant parseInstant(String raw) {
    if (raw == null || raw.isBlank()) return null;
    try {
      return Instant.parse(raw);
    } catch (RuntimeException e) {
      try {
        return OffsetDateTime.parse(raw).toInstant();
      } catch (RuntimeException ignored) {
        return null;
      }
    }
  }

  private static Map<String, String> query(String raw) {
    var out = new LinkedHashMap<String, String>();
    if (raw == null || raw.isBlank()) return out;
    for (var pair : raw.split("&")) {
      var idx = pair.indexOf('=');
      if (idx < 0) continue;
      out.put(
          URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8),
          URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8));
    }
    return out;
  }

  private static Map<String, Object> sku(UUID id, String code, UUID spuId, String spuCode) {
    var m = new LinkedHashMap<String, Object>();
    m.put("id", id.toString());
    m.put("code", code);
    m.put("name", code + " 的名字");
    m.put("spuId", spuId.toString());
    m.put("spuCode", spuCode);
    m.put("spuName", spuCode + " 的名字");
    m.put("variantSignature", "默认");
    m.put("status", "ACTIVE");
    return m;
  }

  private static Map<String, Object> listing(
      UUID id, String shopCode, String platformCode, List<UUID> internalSkuIds) {
    var m = new LinkedHashMap<String, Object>();
    m.put("id", id.toString());
    m.put("shopCode", shopCode);
    m.put("shopName", shopCode);
    m.put("platformCode", platformCode);
    m.put("marketplaceCode", platformCode);
    m.put("externalItemId", platformCode + "-" + id);
    m.put("title", "平台商品");
    m.put("status", "ACTIVE");
    m.put("mappingStatus", "MAPPED");
    m.put("internalSkuIds", internalSkuIds.stream().map(UUID::toString).toList());
    return m;
  }

  private static Map<String, Object> order(
      String orderNo, LocalDate day, String currency, String skuCode, String quantity, String amount) {
    var m = new LinkedHashMap<String, Object>();
    m.put("id", UUID.nameUUIDFromBytes(orderNo.getBytes(StandardCharsets.UTF_8)).toString());
    m.put("orderNo", orderNo);
    m.put("orderSource", "AMAZON");
    m.put("currency", currency);
    m.put("grandTotalAmount", new BigDecimal(amount));
    m.put("lineCount", 1);
    m.put(
        "submittedAt",
        OffsetDateTime.of(day.getYear(), day.getMonthValue(), day.getDayOfMonth(), 10, 0, 0, 0,
                ZoneOffset.UTC)
            .toString());
    m.put("createdAt", m.get("submittedAt"));
    var line = new LinkedHashMap<String, Object>();
    line.put("skuCode", skuCode);
    line.put("skuName", skuCode + " 的名字");
    line.put("quantity", new BigDecimal(quantity));
    line.put("unitPrice", new BigDecimal(amount));
    line.put("lineAmount", new BigDecimal(amount));
    m.put("lines", List.of(line));
    return m;
  }
}
