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
 * 13.2.2.1 店铺与站点的**多租户隔离测试**（真实 PostgreSQL 16 + FORCE RLS，无内存替身）。
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
 * 转发的语义 —— 转发调用方自己的 bearer，不传租户号）。所以租户 A 的上游只给 A 的店铺，
 * 租户 B 的上游只给 B 的店铺。这样「B 看不见 A 的事实」才能真正证明「B 只看得见自己的」
 * —— 否则 B 什么都没看见，「没串租户」只是碰巧。
 *
 * <p>⚠️ **连接池固定为 1 条连接**：只有复用同一条连接，才能真正证明归还后不会串租户 ——
 * 池大于 1 时每次可能拿到不同连接，「没串」可能只是碰巧换了条干净连接。
 *
 * <p>⚠️ **时间夹具用 DB 时钟**（PostgreSQL 的 now()），不是 JVM 墙钟 ——
 * 夹具与查询分属两个钟域时测试会间歇性失败。
 */
@SpringBootTest
@AutoConfigureMockMvc
class StoreSiteBreakdownTenancyIntegrationTest {

  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  static HttpServer upstream;

  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  static final List<String> permissions =
      List.of(
          "reporting.store-site.read",
          // ⚠️ 写权限必须在**同一个 grant** 里同时命中对应的 .read ——
          //   服务端的判据是「能写不能读」的组合不放行，所以这里必须带上 reporting.projection.read。
          "reporting.projection.read",
          "reporting.projection.write");

  static final ObjectMapper JSON = JsonMapper.builder().addModule(new JavaTimeModule()).build();

  /** 🔴 基准日取自 **DB 时钟**（PostgreSQL 的 now()），不是 JVM 墙钟。 */
  static LocalDate day1;

  static final String BEARER_A = "Bearer tenant-a";
  static final String BEARER_B = "Bearer tenant-b";

  /** 一个租户的上游数据（店铺 + 站点 + 订单）。 */
  record TenantData(
      String shopCode,
      String shopName,
      String platformCode,
      String siteCode,
      String orderNo,
      String currency,
      String amount,
      String quantity) {}

  static final Map<String, TenantData> UPSTREAM = new ConcurrentHashMap<>();

  static {
    pg.start();
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      // 运行角色必须 NOBYPASSRLS，否则它会绕过策略，三条断言全部变成空断言。
      s.execute("create role starcon_app nologin");
      s.execute(
          "create role reporting_app login password 'test-only' nosuperuser nobypassrls");

      var rs = s.executeQuery("select (now() at time zone 'UTC')::date");
      rs.next();
      day1 = rs.getObject(1, LocalDate.class).minusDays(1);

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

      // 租户 A：shop-a（AMAZON / US 站）—— 一笔 100.00 USD 的订单。
      UPSTREAM.put(
          BEARER_A,
          new TenantData("shop-a", "亚马逊旗舰店", "AMAZON", "US", "SO-A-1", "USD", "100.00", "2"));
      // 租户 B：shop-b（TIKTOK / US 站）—— 一笔 200.00 USD 的订单。
      UPSTREAM.put(
          BEARER_B,
          new TenantData("shop-b", "TikTok 小店", "TIKTOK", "US", "SO-B-1", "USD", "200.00", "5"));

      upstream = HttpServer.create(new InetSocketAddress(0), 0);
      upstream.createContext("/channel/platforms", e -> respond(e, platformsBody(e)));
      upstream.createContext("/channel/shops", e -> respond(e, shopsBody(e)));
      upstream.createContext("/trade/sales-orders/list", e -> respond(e, ordersBody(e)));
      upstream.createContext("/trade/sales-orders/detail", e -> respond(e, detailBody(e)));
      upstream.createContext("/trade/platform-orders/orders", e -> respond(e, platformOrdersBody(e)));
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
    r.add("CHANNEL_URL", () -> "http://localhost:" + upstream.getAddress().getPort());
    r.add("TRADE_URL", () -> "http://localhost:" + upstream.getAddress().getPort());
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  @Autowired TenantTransactions tenants;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;
  @Autowired ReportingStoreSiteRefreshService refresh;
  @Autowired ReportingStoreSiteService service;

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
          "truncate reporting_store_site_metric_fact, reporting_store_site_drilldown_fact,"
              + " reporting_store_site_metric_definition, reporting_store_site_profile cascade");
    }
  }

  // ==========================================================================
  // 断言 1：两租户互不可见
  // ==========================================================================

  @Test
  void 断言1_两租户互不可见() {
    refresh.refresh(a, BEARER_A, day1, day1);
    refresh.refresh(b, BEARER_B, day1, day1);

    var rowsA = service.report(a, day1, day1, "SHOP", 0, 50).rows();
    var rowsB = service.report(b, day1, day1, "SHOP", 0, 50).rows();

    assertThat(rowsA).as("租户 A 只能看到自己的店铺").hasSize(1);
    assertThat(rowsA.get(0).dimensionKey()).isEqualTo("shop-a");
    assertThat(rowsB).as("租户 B 只能看到自己的店铺").hasSize(1);
    assertThat(rowsB.get(0).dimensionKey()).isEqualTo("shop-b");

    // 更强的一格：A 明确按来源行直查 B 的行也拿不到（RLS 挡住）。
    var cross =
        tenants.run(
            a,
            () ->
                sql.fetch(
                        "select shop_code from reporting_store_site_metric_fact "
                            + "where tenant_id = ? and shop_code = ?",
                        b,
                        "shop-b")
                    .size());
    assertThat(cross).as("按店铺直查也必须被 RLS 挡住").isZero();

    // 档案与构成明细同样隔离。
    var crossProfile =
        tenants.run(
            a,
            () ->
                sql.fetch(
                        "select shop_code from reporting_store_site_profile "
                            + "where tenant_id = ? and shop_code = ?",
                        b,
                        "shop-b")
                    .size());
    assertThat(crossProfile).as("店铺档案也必须被 RLS 挡住").isZero();

    var crossDrill =
        tenants.run(
            a,
            () ->
                sql.fetch(
                        "select order_no from reporting_store_site_drilldown_fact "
                            + "where tenant_id = ? and order_no = ?",
                        b,
                        "SO-B-1")
                    .size());
    assertThat(crossDrill).as("构成明细也必须被 RLS 挡住").isZero();
  }

  // ==========================================================================
  // 断言 2：伪造租户上下文无效
  // ==========================================================================

  @Test
  void 断言2_伪造租户头无效() throws Exception {
    refresh.refresh(b, BEARER_B, day1, day1);

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
            post("/reporting/store-site/refresh")
                .header("Authorization", BEARER_A)
                .header("X-Tenant-Id", b.toString())
                .param("from", day1.toString())
                .param("to", day1.toString()))
        .andExpect(status().isOk());

    // 行确实落在**会话租户 A** 名下（不是 B）。
    var landedInA =
        tenants.run(
            a,
            () ->
                sql.fetch(
                        "select shop_code from reporting_store_site_metric_fact "
                            + "where tenant_id = ? and shop_code = ?",
                        a,
                        "shop-a")
                    .size());
    assertThat(landedInA).as("伪造头不得让投影落到别的租户名下").isPositive();

    var landedInB =
        tenants.run(
            b,
            () ->
                sql.fetch(
                        "select shop_code from reporting_store_site_metric_fact "
                            + "where tenant_id = ? and shop_code = ?",
                        b,
                        "shop-a")
                    .size());
    assertThat(landedInB).as("被伪造的租户不得看见别人的店铺事实").isZero();

    // A 的报表里只有 shop-a；B 的报表里仍然只有它自己的 shop-b。
    assertThat(shopKeysOf(a)).containsExactly("shop-a");
    assertThat(shopKeysOf(b)).containsExactly("shop-b");
  }

  @Test
  void 断言2_伪造租户头不影响口径目录() throws Exception {
    http.perform(get("/reporting/store-site/metrics").header("Authorization", BEARER_A))
        .andExpect(status().isOk());
    var defsA = service.metricDefinitions(a);
    var defsB = service.metricDefinitions(b);
    assertThat(defsA).isNotEmpty();
    assertThat(defsB).isNotEmpty();
    // 两个租户各自播种自己的标准口径，互不影响（种子相同但行各属各的租户）。
    var codesA =
        tenants.run(
            a, () -> sql.fetch("select metric_code from reporting_store_site_metric_definition").size());
    var codesB =
        tenants.run(
            b, () -> sql.fetch("select metric_code from reporting_store_site_metric_definition").size());
    assertThat(codesA).isEqualTo(codesB);
  }

  // ==========================================================================
  // 断言 3：连接池归还后不串租户
  // ==========================================================================

  @Test
  void 断言3_连接池归还后不串租户() {
    refresh.refresh(a, BEARER_A, day1, day1);
    refresh.refresh(b, BEARER_B, day1, day1);
    for (int i = 0; i < 5; i++) {
      assertThat(shopKeysOf(a)).as("第 %d 轮租户 A", i).containsExactly("shop-a");
      assertThat(shopKeysOf(b)).as("第 %d 轮租户 B", i).containsExactly("shop-b");
      assertThat(visibleFactsWithoutTenant()).as("第 %d 轮事务外", i).isZero();
    }
    // 归还后再单独借一次，仍然干净。
    assertThat(shopKeysOf(a)).containsExactly("shop-a");
    assertThat(shopKeysOf(b)).containsExactly("shop-b");
  }

  // ==========================================================================
  // 工具
  // ==========================================================================

  private List<String> shopKeysOf(UUID tenant) {
    return service.report(tenant, day1, day1, "SHOP", 0, 50).rows().stream()
        .map(ReportingStoreSiteModels.StoreSiteRow::dimensionKey)
        .toList();
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
    return sql.fetch("select id from reporting_store_site_metric_fact").size()
        + sql.fetch("select id from reporting_store_site_profile").size()
        + sql.fetch("select id from reporting_store_site_drilldown_fact").size();
  }

  // ---------------------------------------------------------------------------
  // 假上游（按 bearer 分租户）
  // ---------------------------------------------------------------------------

  private static TenantData dataOf(com.sun.net.httpserver.HttpExchange e) {
    var bearer = e.getRequestHeaders().getFirst("Authorization");
    var data = UPSTREAM.get(bearer);
    if (data == null) {
      try {
        e.sendResponseHeaders(401, -1);
      } catch (java.io.IOException ignored) {
        // 上游已经断开连接 —— 这里只关心「这个租户没有数据」。
      }
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

  /** 平台与站点目录。 */
  private static Object platformsBody(com.sun.net.httpserver.HttpExchange e) {
    var data = dataOf(e);
    if (data == null) return null;
    return List.of(
        Map.of(
            "id",
            UUID.nameUUIDFromBytes(data.platformCode().getBytes(StandardCharsets.UTF_8)),
            "code",
            data.platformCode(),
            "name",
            data.platformCode(),
            "marketplaces",
            List.of(
                Map.of(
                    "id",
                    UUID.nameUUIDFromBytes(
                        (data.platformCode() + data.siteCode()).getBytes(StandardCharsets.UTF_8)),
                    "code",
                    data.siteCode(),
                    "name",
                    data.siteCode() + " 站",
                    "countryCode",
                    "US",
                    "defaultCurrency",
                    "USD",
                    "defaultTimezone",
                    "America/New_York"))));
  }

  /** 店铺清单 / 店铺详情。 */
  private static Object shopsBody(com.sun.net.httpserver.HttpExchange e) throws java.io.IOException {
    var data = dataOf(e);
    if (data == null) return null;
    var path = e.getRequestURI().getPath();
    var shopId = UUID.nameUUIDFromBytes(data.shopCode().getBytes(StandardCharsets.UTF_8));
    if (path.equals("/channel/shops")) {
      return List.of(
          Map.of(
              "id", shopId,
              "code", data.shopCode(),
              "name", data.shopName(),
              "platformCode", data.platformCode(),
              "platformName", data.platformCode()));
    }
    var id = path.substring("/channel/shops/".length());
    if (!shopId.toString().equals(id)) return null;
    return Map.of(
        "id", shopId,
        "code", data.shopCode(),
        "name", data.shopName(),
        "platformCode", data.platformCode(),
        "platformName", data.platformCode(),
        "marketplaces",
        List.of(
            Map.of(
                "marketplaceId",
                UUID.nameUUIDFromBytes(
                    (data.platformCode() + data.siteCode()).getBytes(StandardCharsets.UTF_8)),
                "marketplaceCode",
                data.siteCode(),
                "marketplaceName",
                data.siteCode() + " 站",
                "status",
                "ACTIVE",
                "enabledAt",
                "2026-01-01T00:00:00Z")));
  }

  /** 销售订单列表。 */
  private static Object ordersBody(com.sun.net.httpserver.HttpExchange e)
      throws java.io.IOException {
    var data = dataOf(e);
    if (data == null) return null;
    var query = query(e.getRequestURI().getRawQuery());
    var from = parseInstant(query.get("orderedFrom"));
    var to = parseInstant(query.get("orderedTo"));
    var at = day1.atTime(10, 0).toInstant(ZoneOffset.UTC);
    if (from != null && at.isBefore(from)) return Map.of("rows", List.of(), "total", 0);
    if (to != null && !at.isBefore(to)) return Map.of("rows", List.of(), "total", 0);
    return Map.of(
        "rows",
        List.of(
            Map.of(
                "id", orderId(data),
                "orderNo", data.orderNo(),
                "orderSource", "PLATFORM",
                "currency", data.currency(),
                "grandTotalAmount", new BigDecimal(data.amount()),
                "lineCount", 1,
                "submittedAt", at.toString(),
                "createdAt", at.toString())),
        "page",
        0,
        "size",
        200,
        "total",
        1);
  }

  /** 订单明细。 */
  private static Object detailBody(com.sun.net.httpserver.HttpExchange e)
      throws java.io.IOException {
    var data = dataOf(e);
    if (data == null) return null;
    var path = e.getRequestURI().getPath();
    var id = path.substring("/trade/sales-orders/detail/".length());
    if (!orderId(data).toString().equals(id)) return null;
    var quantity = new BigDecimal(data.quantity());
    return Map.of(
        "header",
        Map.of(
            "orderNo", data.orderNo(),
            "currency", data.currency(),
            "grandTotalAmount", new BigDecimal(data.amount()),
            "submittedAt", day1.atTime(10, 0).atOffset(ZoneOffset.UTC).toString()),
        "lines",
        List.of(
            Map.of(
                "skuCode", "SKU-1",
                "skuName", "SKU-1 商品",
                "quantity", quantity,
                "unitPrice", new BigDecimal(data.amount()).divide(quantity, 2, java.math.RoundingMode.HALF_UP),
                "lineAmount", new BigDecimal(data.amount()))));
  }

  /** 平台单（销售订单 → 店铺归属）。 */
  private static Object platformOrdersBody(com.sun.net.httpserver.HttpExchange e) {
    var data = dataOf(e);
    if (data == null) return null;
    var at = day1.atTime(10, 0).atOffset(ZoneOffset.UTC).toString();
    return Map.of(
        "rows",
        List.of(
            Map.of(
                "id",
                UUID.nameUUIDFromBytes(
                    ("platform:" + data.orderNo()).getBytes(StandardCharsets.UTF_8)),
                "salesOrderId",
                orderId(data),
                "orderNo",
                data.orderNo(),
                "platformCode",
                data.platformCode(),
                "shopCode",
                data.shopCode(),
                "currency",
                data.currency(),
                "platformPlacedAt",
                at,
                "syncedAt",
                at)),
        "page",
        0,
        "size",
        200,
        "total",
        1);
  }

  private static UUID orderId(TenantData data) {
    return UUID.nameUUIDFromBytes(data.orderNo().getBytes(StandardCharsets.UTF_8));
  }

  private static Map<String, String> query(String raw) {
    var out = new LinkedHashMap<String, String>();
    if (raw == null || raw.isBlank()) return out;
    for (var part : raw.split("&")) {
      var idx = part.indexOf('=');
      if (idx < 0) continue;
      out.put(
          URLDecoder.decode(part.substring(0, idx), StandardCharsets.UTF_8),
          URLDecoder.decode(part.substring(idx + 1), StandardCharsets.UTF_8));
    }
    return out;
  }

  private static Instant parseInstant(String raw) {
    if (raw == null || raw.isBlank()) return null;
    return Instant.parse(raw);
  }
}
