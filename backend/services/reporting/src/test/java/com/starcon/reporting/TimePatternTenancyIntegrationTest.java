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
 * 13.2.1.2 分时与分周模式的**多租户隔离测试**（真实 PostgreSQL 16 + FORCE RLS，无内存替身）。
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
 * 转发的语义 —— 转发调用方自己的 bearer，不传租户号）。所以租户 A 的上游只给 A 的订单，
 * 租户 B 的上游只给 B 的订单。这样「B 看不见 A 的事实」才能真正证明「B 只看得见自己的」
 * —— 否则 B 什么都没看见，「没串租户」只是碰巧。
 *
 * <p>⚠️ <b>连接池固定为 1 条连接</b>：只有复用同一条连接，才能真正证明归还后不会串租户 ——
 * 池大于 1 时每次可能拿到不同连接，「没串」可能只是碰巧换了条干净连接。
 */
@SpringBootTest
@AutoConfigureMockMvc
class TimePatternTenancyIntegrationTest {

  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  static HttpServer upstream;

  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  static final List<String> permissions =
      List.of(
          "reporting.time-pattern.read",
          // ⚠️ 写权限必须在**同一个 grant** 里同时命中对应的 .read ——
          //   服务端的判据是「能写不能读」的组合不放行，所以这里必须带上
          //   reporting.time-pattern.read，否则刷新端点直接 403。
          "reporting.time-pattern.write");

  static final ObjectMapper JSON = JsonMapper.builder().addModule(new JavaTimeModule()).build();

  static final LocalDate DAY1 = LocalDate.of(2026, 10, 1);
  static final LocalDate DAY2 = LocalDate.of(2026, 10, 2);

  static final String BEARER_A = "Bearer tenant-a";
  static final String BEARER_B = "Bearer tenant-b";

  /** 每个租户的上游数据（按 bearer 分）。 */
  static final Map<String, List<Map<String, Object>>> UPSTREAM_ORDERS = new ConcurrentHashMap<>();

  static final Map<String, List<Map<String, Object>>> UPSTREAM_PLATFORM = new ConcurrentHashMap<>();

  // ---------------------------------------------------------------------------
  // 渠道夹具（两个租户共用同一套目录 —— 隔离要考的是 reporting 侧的库）
  // ---------------------------------------------------------------------------

  static final List<Map<String, Object>> PLATFORMS =
      List.of(
          platform("AMAZON", marketplace("US", "America/Los_Angeles")),
          platform("TIKTOK", marketplace("SG", "Asia/Shanghai")));

  static final List<Map<String, Object>> SHOPS =
      List.of(
          shop("shop-a", "北美店", "AMAZON"),
          shop("shop-b", "新加坡店", "TIKTOK"));

  static final Map<String, List<String>> SHOP_SITES =
      Map.of("shop-a", List.of("US"), "shop-b", List.of("SG"));

  private static Object shopEndpoint(com.sun.net.httpserver.HttpExchange e) {
    var path = e.getRequestURI().getPath();
    if (path.endsWith("/shops")) return SHOPS;
    var id = path.substring(path.lastIndexOf('/') + 1);
    var shop =
        SHOPS.stream()
            .filter(s -> id.equals(s.get("id")))
            .findFirst()
            .orElseThrow(() -> new AssertionError("没有这个店铺：" + id));
    var body = new LinkedHashMap<String, Object>(shop);
    var sites = new ArrayList<Map<String, Object>>();
    for (var code : SHOP_SITES.getOrDefault((String) shop.get("code"), List.of())) {
      var site = new LinkedHashMap<String, Object>();
      site.put("marketplaceId", UUID.nameUUIDFromBytes(code.getBytes(StandardCharsets.UTF_8)).toString());
      site.put("marketplaceCode", code);
      site.put("marketplaceName", code);
      site.put("status", "ACTIVE");
      site.put("enabledAt", "2026-01-01T00:00:00Z");
      sites.add(site);
    }
    body.put("marketplaces", sites);
    return body;
  }

  private static Map<String, Object> platform(String code, Map<String, Object>... marketplaces) {
    var body = new LinkedHashMap<String, Object>();
    body.put("id", UUID.nameUUIDFromBytes(code.getBytes(StandardCharsets.UTF_8)).toString());
    body.put("code", code);
    body.put("name", code);
    body.put("marketplaces", List.of(marketplaces));
    return body;
  }

  private static Map<String, Object> marketplace(String code, String defaultTimezone) {
    var body = new LinkedHashMap<String, Object>();
    body.put("id", UUID.nameUUIDFromBytes(code.getBytes(StandardCharsets.UTF_8)).toString());
    body.put("code", code);
    body.put("name", code);
    body.put("countryCode", "US");
    body.put("defaultCurrency", "USD");
    body.put("defaultTimezone", defaultTimezone);
    return body;
  }

  private static Map<String, Object> shop(String code, String name, String platformCode) {
    var body = new LinkedHashMap<String, Object>();
    body.put("id", UUID.nameUUIDFromBytes(code.getBytes(StandardCharsets.UTF_8)).toString());
    body.put("code", code);
    body.put("name", name);
    body.put("platformCode", platformCode);
    body.put("platformName", platformCode);
    return body;
  }

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

      UPSTREAM_ORDERS.put(BEARER_A, List.of(order("SO-A-1", "2026-10-02T02:00:00Z", "100.00", "USD")));
      UPSTREAM_ORDERS.put(BEARER_B, List.of(order("SO-B-1", "2026-10-02T02:00:00Z", "900.00", "USD")));
      UPSTREAM_PLATFORM.put(BEARER_A, List.of(platformOrder("SO-A-1", "shop-a")));
      UPSTREAM_PLATFORM.put(BEARER_B, List.of(platformOrder("SO-B-1", "shop-b")));

      upstream = HttpServer.create(new InetSocketAddress(0), 0);
      upstream.createContext("/trade/sales-orders/list", e -> respond(e, salesOrderPage(e)));
      upstream.createContext("/trade/sales-orders/detail", e -> respond(e, salesOrderDetail(e)));
      upstream.createContext("/trade/platform-orders/orders", e -> respond(e, platformOrderPage(e)));
      upstream.createContext("/channel/platforms", e -> respond(e, PLATFORMS));
      upstream.createContext("/channel/shops", e -> respond(e, shopEndpoint(e)));
      upstream.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  static AccessService.Access actor(UUID tenantId) {
    var grant = new AccessService.Grant(UUID.randomUUID(), "TEST", "ALL", permissions);
    return new AccessService.Access(
        user, tenantId, "Test", UUID.randomUUID(), null, permissions, List.of(grant));
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
    r.add("CHANNEL_URL", () -> "http://localhost:" + upstream.getAddress().getPort());
    r.add("TRADE_URL", () -> "http://localhost:" + upstream.getAddress().getPort());
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  @Autowired TenantTransactions tenants;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;
  @Autowired ReportingTimePatternRefreshService refresh;
  @Autowired ReportingTimePatternService pattern;

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
          "truncate reporting_time_pattern_fact, reporting_time_pattern_shop_profile,"
              + " reporting_time_pattern_slot, reporting_time_pattern_metric_definition,"
              + " reporting_metric_definition cascade");
    }
  }

  // ==========================================================================
  // 断言 1：两租户互不可见
  // ==========================================================================

  @Test
  void 断言1_两租户互不可见() {
    refreshAll(a, BEARER_A);
    refreshAll(b, BEARER_B);

    assertThat(amount(a)).as("租户 A 只能看到自己的订单数").isEqualByComparingTo("1");
    assertThat(amount(b)).as("租户 B 只能看到自己的订单数").isEqualByComparingTo("1");
    assertThat(salesAmount(a)).as("租户 A 只能看到自己的成交金额").isEqualByComparingTo("100");
    assertThat(salesAmount(b)).as("租户 B 只能看到自己的成交金额").isEqualByComparingTo("900");

    // 更强的一格：A 明确按来源行直查 B 的行也拿不到（RLS 挡住）。
    var cross =
        tenants.run(
            a,
            () ->
                sql.fetch(
                        "select source_reference from reporting_time_pattern_fact where tenant_id = ?",
                        b)
                    .size());
    assertThat(cross).as("按来源直查也必须被 RLS 挡住").isZero();
  }

  @Test
  void 断言1_店铺时区档案也按租户隔离() {
    refreshAll(a, BEARER_A);
    refreshAll(b, BEARER_B);

    var shopsA = pattern.shops(a);
    var shopsB = pattern.shops(b);
    assertThat(shopsA).as("A 看得到自己的店铺档案").isNotEmpty();
    assertThat(shopsB).as("B 看得到自己的店铺档案").isNotEmpty();

    var cross =
        tenants.run(
            a,
            () ->
                sql.fetch(
                        "select shop_code from reporting_time_pattern_shop_profile where tenant_id = ?",
                        b)
                    .size());
    assertThat(cross).as("按租户直查也必须被 RLS 挡住").isZero();
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
    http.perform(
            post("/reporting/time-pattern/refresh")
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
                        "select source_reference from reporting_time_pattern_fact where tenant_id = ?",
                        a)
                    .size());
    assertThat(landedInA).as("伪造头不得让投影落到别的租户名下").isPositive();

    var landedInB =
        tenants.run(
            b,
            () ->
                sql.fetch(
                        "select source_reference from reporting_time_pattern_fact where tenant_id = ?",
                        a)
                    .size());
    assertThat(landedInB).as("被伪造的租户不得看见别人的分时事实").isZero();

    // A 的报表里只有 A 的金额；B 的报表里仍然只有它自己的。
    assertThat(salesAmount(a)).isEqualByComparingTo("100");
    assertThat(salesAmount(b)).isEqualByComparingTo("900");
  }

  @Test
  void 断言2_伪造租户头不影响时段配置() throws Exception {
    http.perform(get("/reporting/time-pattern/slots").header("Authorization", BEARER_A))
        .andExpect(status().isOk());
    var slotsA = pattern.slotConfigs(a);
    var slotsB = pattern.slotConfigs(b);
    assertThat(slotsA).isNotEmpty();
    assertThat(slotsB).isNotEmpty();

    var cross =
        tenants.run(
            a,
            () ->
                sql.fetch(
                        "select slot_code from reporting_time_pattern_slot where tenant_id = ?", b)
                    .size());
    assertThat(cross).as("按租户直查也必须被 RLS 挡住").isZero();
  }

  // ==========================================================================
  // 断言 3：连接池归还后不串租户
  // ==========================================================================

  @Test
  void 断言3_连接池归还后不串租户() {
    refreshAll(a, BEARER_A);
    refreshAll(b, BEARER_B);
    for (int i = 0; i < 5; i++) {
      assertThat(salesAmount(a)).as("第 %d 轮租户 A", i).isEqualByComparingTo("100");
      assertThat(salesAmount(b)).as("第 %d 轮租户 B", i).isEqualByComparingTo("900");
      assertThat(visibleFactsWithoutTenant()).as("第 %d 轮事务外", i).isZero();
    }
    // 归还后再单独借一次，仍然干净。
    assertThat(salesAmount(a)).isEqualByComparingTo("100");
    assertThat(salesAmount(b)).isEqualByComparingTo("900");
  }

  // ==========================================================================
  // 工具
  // ==========================================================================

  private void refreshAll(UUID tenantId, String bearer) {
    refresh.refresh(tenantId, bearer, DAY1, DAY2);
  }

  /** 某租户的订单笔数（从对外的读路径取，**只走读路径**）。 */
  private BigDecimal amount(UUID tenantId) {
    var response =
        pattern.pattern(
            tenantId,
            DAY1,
            DAY2,
            ReportingTimePatternCatalog.Alignment.LOCAL_TIME,
            List.of(),
            null);
    var total = BigDecimal.ZERO;
    for (var cell : response.heatmap()) total = total.add(cell.orderCount());
    return total;
  }

  /** 某租户的成交金额合计（**只加同一币种**）。 */
  private BigDecimal salesAmount(UUID tenantId) {
    var response =
        pattern.pattern(
            tenantId,
            DAY1,
            DAY2,
            ReportingTimePatternCatalog.Alignment.LOCAL_TIME,
            List.of(),
            null);
    var total = BigDecimal.ZERO;
    for (var cell : response.heatmap()) {
      for (var amount : cell.salesAmounts()) {
        if (!amount.currency().equals("USD")) continue;
        total = total.add(amount.value());
      }
    }
    return total;
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
    return sql.fetch("select id from reporting_time_pattern_fact").size()
        + sql.fetch("select id from reporting_time_pattern_shop_profile").size()
        + sql.fetch("select id from reporting_time_pattern_slot").size();
  }

  // ---------------------------------------------------------------------------
  // 假上游（按 bearer 分租户）
  // ---------------------------------------------------------------------------

  private static void respond(com.sun.net.httpserver.HttpExchange e, Object body) {
    try {
      byte[] bytes = JSON.writeValueAsBytes(body);
      e.getResponseHeaders().add("Content-Type", "application/json");
      e.sendResponseHeaders(200, bytes.length);
      e.getResponseBody().write(bytes);
      e.close();
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }

  private static Map<String, Object> pageBody(String field, List<Map<String, Object>> rows) {
    var body = new LinkedHashMap<String, Object>();
    body.put(field, rows);
    body.put("page", 0);
    body.put("size", 200);
    body.put("total", rows.size());
    return body;
  }

  private static Map<String, Object> salesOrderPage(com.sun.net.httpserver.HttpExchange e) {
    var bearer = e.getRequestHeaders().getFirst("Authorization");
    var orders = UPSTREAM_ORDERS.getOrDefault(bearer, List.of());
    var query = query(e);
    var from = query.get("orderedFrom") == null ? null : java.time.Instant.parse(query.get("orderedFrom"));
    var to = query.get("orderedTo") == null ? null : java.time.Instant.parse(query.get("orderedTo"));
    var rows = new ArrayList<Map<String, Object>>();
    for (var order : orders) {
      var submittedAt = java.time.Instant.parse((String) order.get("submittedAt"));
      if (from != null && submittedAt.isBefore(from)) continue;
      if (to != null && !submittedAt.isBefore(to)) continue;
      rows.add(order);
    }
    return pageBody("rows", rows);
  }

  private static Map<String, Object> salesOrderDetail(com.sun.net.httpserver.HttpExchange e) {
    var bearer = e.getRequestHeaders().getFirst("Authorization");
    var orders = UPSTREAM_ORDERS.getOrDefault(bearer, List.of());
    var path = e.getRequestURI().getPath();
    var id = path.substring(path.lastIndexOf('/') + 1);
    var order =
        orders.stream()
            .filter(o -> id.equals(o.get("id")))
            .findFirst()
            .orElseThrow(() -> new AssertionError("没有这张订单：" + id));
    var body = new LinkedHashMap<String, Object>();
    var header = new LinkedHashMap<String, Object>();
    header.put("orderNo", order.get("orderNo"));
    header.put("currency", order.get("currency"));
    header.put("grandTotalAmount", order.get("grandTotalAmount"));
    header.put("submittedAt", order.get("submittedAt"));
    body.put("header", header);
    var line = new LinkedHashMap<String, Object>();
    line.put("skuCode", "SKU-A");
    line.put("skuName", "SKU-A");
    line.put("quantity", 1);
    line.put("unitPrice", "10.00");
    line.put("lineAmount", "10.00");
    body.put("lines", List.of(line));
    return body;
  }

  private static Map<String, Object> platformOrderPage(com.sun.net.httpserver.HttpExchange e) {
    var bearer = e.getRequestHeaders().getFirst("Authorization");
    return pageBody("rows", UPSTREAM_PLATFORM.getOrDefault(bearer, List.of()));
  }

  private static Map<String, String> query(com.sun.net.httpserver.HttpExchange e) {
    var out = new LinkedHashMap<String, String>();
    var raw = e.getRequestURI().getRawQuery();
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

  private static Map<String, Object> order(
      String orderNo, String submittedAt, String amount, String currency) {
    var body = new LinkedHashMap<String, Object>();
    body.put("id", UUID.nameUUIDFromBytes(orderNo.getBytes(StandardCharsets.UTF_8)).toString());
    body.put("orderNo", orderNo);
    body.put("orderSource", "PLATFORM");
    body.put("currency", currency);
    body.put("grandTotalAmount", amount);
    body.put("lineCount", 1);
    body.put("submittedAt", submittedAt);
    body.put("createdAt", submittedAt);
    body.put("quantity", 1);
    return body;
  }

  private static Map<String, Object> platformOrder(String orderNo, String shopCode) {
    var body = new LinkedHashMap<String, Object>();
    body.put("id", UUID.nameUUIDFromBytes(("po:" + orderNo).getBytes(StandardCharsets.UTF_8)).toString());
    body.put(
        "salesOrderId",
        UUID.nameUUIDFromBytes(orderNo.getBytes(StandardCharsets.UTF_8)).toString());
    body.put("orderNo", orderNo);
    body.put("platformCode", "AMAZON");
    body.put("shopCode", shopCode);
    body.put("currency", "USD");
    body.put("platformPlacedAt", "2026-10-02T02:00:00Z");
    body.put("syncedAt", "2026-10-02T02:05:00Z");
    return body;
  }

}
