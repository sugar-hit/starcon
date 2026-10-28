package com.starcon.reporting;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.starcon.tenancy.TenantTransactions;
import com.sun.net.httpserver.HttpServer;
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
 * 13.3.1.2 生命周期与分层的**多租户隔离测试**（真实 PostgreSQL 16 + FORCE RLS，无内存替身）。
 *
 * <p>跑的是 AGENTS.md 明文要求的三条断言（缺一不可）：
 *
 * <ol>
 *   <li><b>两租户互不可见</b>（分层结果、分层规则、金额行都要挡住）；
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
class LifecycleSegmentationTenancyIntegrationTest {

  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  static HttpServer upstream;

  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  static final List<String> permissions =
      List.of(
          "reporting.lifecycle-segmentation.read",
          // ⚠️ 写权限必须在**同一个 grant** 里同时命中对应的 .read ——
          //   服务端的判据是「能写不能读」的组合不放行，所以这里必须带上
          //   reporting.projection.read，否则刷新端点直接 403。
          "reporting.projection.read",
          "reporting.projection.write");

  static final ObjectMapper JSON = JsonMapper.builder().addModule(new JavaTimeModule()).build();

  static final LocalDate FROM = LocalDate.of(2026, 10, 1);
  static final LocalDate TO = LocalDate.of(2026, 10, 4);
  static final LocalDate BASE_FROM = LocalDate.of(2026, 9, 1);

  static final ZoneId ZONE = ZoneId.of("UTC");

  static final String BEARER_A = "Bearer tenant-a";
  static final String BEARER_B = "Bearer tenant-b";

  /** 一路上游的数据（按 bearer 分租户）。 */
  record UpstreamData(List<Map<String, Object>> skus, List<Map<String, Object>> orders) {}

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

      // 租户 A：SKU-A
      var skuA = UUID.randomUUID();
      var spuA = UUID.randomUUID();
      UPSTREAM.put(
          BEARER_A,
          new UpstreamData(
              List.of(sku(skuA, "SKU-A", spuA, "SPU-A")),
              List.of(order("SO-A-1", LocalDate.of(2026, 10, 1), "USD", "SKU-A", "100", "1000.00"))));

      // 租户 B：SKU-B
      var skuB = UUID.randomUUID();
      var spuB = UUID.randomUUID();
      UPSTREAM.put(
          BEARER_B,
          new UpstreamData(
              List.of(sku(skuB, "SKU-B", spuB, "SPU-B")),
              List.of(order("SO-B-1", LocalDate.of(2026, 10, 1), "EUR", "SKU-B", "5", "100.00"))));

      upstream = HttpServer.create(new InetSocketAddress(0), 0);
      upstream.createContext("/catalog/skus", e -> respond(e, skusBody(e)));
      upstream.createContext("/channel/listings", e -> respond(e, page(List.of(), "items")));
      upstream.createContext("/trade/sales-orders/list", e -> respond(e, ordersBody(e)));
      upstream.createContext("/trade/sales-orders/detail", e -> respond(e, detailBody(e)));
      // 🔴 其余上游一律给**空页**（不是 404）：404 会被端口翻成 503，
      //   「上游读不到」与「上游没有数据」是两件事 —— 这里要的是后者。
      upstream.createContext("/warehouse/inventory/overview", e -> respond(e, page(List.of(), "rows")));
      upstream.createContext(
          "/warehouse/inventory/aging/slow-moving", e -> respond(e, page(List.of(), "rows")));
      upstream.createContext("/finance/cost-calculation/runs", e -> respond(e, page(List.of(), "items")));
      upstream.createContext(
          "/finance/cost-calculation/entries", e -> respond(e, page(List.of(), "items")));
      upstream.createContext("/channel/listing-performance", e -> respond(e, page(List.of(), "items")));
      upstream.createContext(
          "/channel/listing-hijackers/records", e -> respond(e, page(List.of(), "items")));
      upstream.createContext("/channel/ad-reports/rows", e -> respond(e, page(List.of(), "items")));
      upstream.createContext("/channel/ad-campaign-details", e -> respond(e, page(List.of(), "items")));
      upstream.createContext("/trade/aftersales/cases", e -> respond(e, page(List.of(), "rows")));
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
  @Autowired ReportingProductPerformanceRefreshService performanceRefresh;
  @Autowired ReportingLifecycleSegmentationService segmentation;

  @AfterAll
  static void stop() {
    // ⚠️ **先停外部桩、再停容器** —— 顺序反了会让容器泄漏把机器压进 swap。
    identity.stop(0);
    upstream.stop(0);
    pg.stop();
  }

  @BeforeEach
  void reset() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate reporting_lifecycle_segmentation_amount, reporting_lifecycle_segmentation_result,"
              + " reporting_lifecycle_segmentation_rule,"
              + " reporting_product_performance_fact, reporting_product_performance_snapshot,"
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

    var a1 = segmentation.segmentation(a, FROM, TO, "SKU", null, null, 0, 50);
    var b1 = segmentation.segmentation(b, FROM, TO, "SKU", null, null, 0, 50);

    assertThat(keysOf(a1)).containsExactly("SKU-A");
    assertThat(keysOf(b1)).containsExactly("SKU-B");

    // 更强的一格：A 明确按来源行直查 B 的行也拿不到（RLS 挡住）。
    var crossResult =
        tenants.run(
            a,
            () ->
                sql.fetch(
                        "select dimension_key from reporting_lifecycle_segmentation_result "
                            + "where tenant_id = ? and dimension_key = ?",
                        b,
                        "SKU-B")
                    .size());
    assertThat(crossResult).as("分层结果必须被 RLS 挡住").isZero();

    // 金额行同样被挡住。
    var crossAmount =
        tenants.run(
            a,
            () ->
                sql.fetch(
                        "select currency from reporting_lifecycle_segmentation_amount "
                            + "where tenant_id = ?",
                        b)
                    .size());
    assertThat(crossAmount).as("金额行必须被 RLS 挡住").isZero();

    // 分层规则也是每租户一份。
    assertThat(segmentation.rules(a, "SKU")).hasSize(1);
    assertThat(segmentation.rules(b, "SKU")).hasSize(1);
  }

  // ==========================================================================
  // 断言 2：伪造租户上下文无效
  // ==========================================================================

  @Test
  void 断言2_伪造租户头无效() throws Exception {
    refreshAll(b, BEARER_B);

    // (1) 事务外没有「环境租户」：不存在一个可以被沿用的默认租户。
    assertThat(rawTenantSetting()).isNullOrEmpty();
    assertThat(visibleResultsWithoutTenant()).as("未绑定租户时不应看到任何行").isZero();

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
    assertThat(visibleResultsWithoutTenant()).isZero();

    // (3) HTTP 边界：带**租户 A 的合法会话** + **伪造的 X-Tenant-Id（租户 B）** ⇒
    //     投影仍然落在**会话租户 A** 名下，租户 B 看不见。
    productSalesRefresh.refresh(a, BEARER_A, BASE_FROM, TO, ZONE);
    performanceRefresh.refresh(a, BEARER_A, FROM, TO, BASE_FROM, FROM.minusDays(1), ZONE);
    http.perform(
            post("/reporting/lifecycle-segmentation/refresh")
                .header("Authorization", BEARER_A)
                .header("X-Tenant-Id", b.toString())
                .param("from", FROM.toString())
                .param("to", TO.toString()))
        .andExpect(status().isOk());

    var landedInA =
        tenants.run(
            a,
            () ->
                sql.fetch(
                        "select dimension_key from reporting_lifecycle_segmentation_result "
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
                        "select dimension_key from reporting_lifecycle_segmentation_result "
                            + "where tenant_id = ? and dimension_key = ?",
                        b,
                        "SKU-A")
                    .size());
    assertThat(landedInB).as("被伪造的租户不得看见别人的分层结果").isZero();

    // A 的报表里只有 SKU-A；B 的报表里仍然只有它自己的 SKU-B。
    assertThat(keysOf(segmentation.segmentation(a, FROM, TO, "SKU", null, null, 0, 50)))
        .containsExactly("SKU-A");
    assertThat(keysOf(segmentation.segmentation(b, FROM, TO, "SKU", null, null, 0, 50)))
        .containsExactly("SKU-B");
  }

  // ==========================================================================
  // 断言 3：连接池归还后不串租户
  // ==========================================================================

  @Test
  void 断言3_连接池归还后不串租户() {
    refreshAll(a, BEARER_A);
    refreshAll(b, BEARER_B);
    for (int i = 0; i < 5; i++) {
      assertThat(keysOf(segmentation.segmentation(a, FROM, TO, "SKU", null, null, 0, 50)))
          .as("第 %d 轮租户 A", i)
          .containsExactly("SKU-A");
      assertThat(keysOf(segmentation.segmentation(b, FROM, TO, "SKU", null, null, 0, 50)))
          .as("第 %d 轮租户 B", i)
          .containsExactly("SKU-B");
      assertThat(visibleResultsWithoutTenant()).as("第 %d 轮事务外", i).isZero();
    }
    // 归还后再单独借一次，仍然干净。
    assertThat(keysOf(segmentation.segmentation(a, FROM, TO, "SKU", null, null, 0, 50)))
        .containsExactly("SKU-A");
    assertThat(keysOf(segmentation.segmentation(b, FROM, TO, "SKU", null, null, 0, 50)))
        .containsExactly("SKU-B");
  }

  // ==========================================================================
  // 工具
  // ==========================================================================

  private void refreshAll(UUID tenant, String bearer) {
    productSalesRefresh.refresh(tenant, bearer, BASE_FROM, TO, ZONE);
    performanceRefresh.refresh(tenant, bearer, FROM, TO, BASE_FROM, FROM.minusDays(1), ZONE);
    segmentation.refresh(tenant, FROM, TO, "SKU");
  }

  private List<String> keysOf(
      ReportingLifecycleSegmentationModels.LifecycleSegmentationResponse response) {
    return response.rows().stream().map(r -> r.dimensionKey()).toList();
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

  private int visibleResultsWithoutTenant() {
    return sql.fetch("select id from reporting_lifecycle_segmentation_result").size()
        + sql.fetch("select id from reporting_lifecycle_segmentation_amount").size()
        + sql.fetch("select id from reporting_lifecycle_segmentation_rule").size();
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

  private static Map<String, Object> skusBody(com.sun.net.httpserver.HttpExchange e)
      throws java.io.IOException {
    var data = dataOf(e);
    if (data == null) return null;
    return page(data.skus(), "items");
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
      var skuCode = String.valueOf(row.get("skuCode"));
      matched.add(
          Map.of(
              "skuCode", skuCode,
              "skuName", skuCode,
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

  /** 解析上游时刻（两种格式都要认 —— 只认一种会让夹具静默变成零行）。 */
  private static java.time.Instant parseInstant(String raw) {
    if (raw == null || raw.isBlank()) return null;
    try {
      return java.time.Instant.parse(raw);
    } catch (RuntimeException ignored) {
      // 落到下面按 OffsetDateTime 解析。
    }
    try {
      return OffsetDateTime.parse(raw).toInstant();
    } catch (RuntimeException e) {
      return null;
    }
  }
}
