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
 * 13.3.2.1 流量转化的**多租户隔离测试**（真实 PostgreSQL 16 + FORCE RLS，无内存替身）。
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
 * 转发的语义 —— 转发调用方自己的 bearer，不传租户号）。所以租户 A 的上游只给 A 的流量，
 * 租户 B 的上游只给 B 的流量。这样「B 看不见 A 的事实」才能真正证明「B 只看得见自己的」
 * —— 否则 B 什么都没看见，「没串租户」只是碰巧。
 *
 * <p>⚠️ **连接池固定为 1 条连接**：只有复用同一条连接，才能真正证明归还后不会串租户 ——
 * 池大于 1 时每次可能拿到不同连接，「没串」可能只是碰巧换了条干净连接。
 */
@SpringBootTest
@AutoConfigureMockMvc
class TrafficConversionTenancyIntegrationTest {

  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  static HttpServer upstream;

  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  static final List<String> permissions =
      List.of(
          "reporting.traffic-conversion.read",
          // ⚠️ 写权限必须在**同一个 grant** 里同时命中对应的 .read ——
          //   服务端的判据是「能写不能读」的组合不放行，所以这里必须带上 reporting.projection.read。
          "reporting.projection.read",
          "reporting.projection.write");

  static final ObjectMapper JSON = JsonMapper.builder().addModule(new JavaTimeModule()).build();

  /** 🔴 基准日取自 **DB 时钟**（PostgreSQL 的 now()），不是 JVM 墙钟。 */
  static LocalDate day1;

  static final String BEARER_A = "Bearer tenant-a";
  static final String BEARER_B = "Bearer tenant-b";

  /** 一个租户的上游数据。 */
  record TenantData(
      String shopCode, String listingId, String impressions, String clicks, String sessions,
      String units, String orderNo, String amount) {}

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

      // 租户 A：1000 曝光 / 100 点击 / 80 Session / 8 件成交 / 一张 500.00 USD 订单。
      UPSTREAM.put(
          BEARER_A,
          new TenantData(
              "shop-a",
              UUID.nameUUIDFromBytes("listing-a".getBytes(StandardCharsets.UTF_8)).toString(),
              "1000", "100", "80", "8", "SO-A-1", "500.00"));
      // 租户 B：2000 曝光 / 200 点击 / 160 Session / 16 件成交 / 两张 1000.00 USD 订单。
      UPSTREAM.put(
          BEARER_B,
          new TenantData(
              "shop-b",
              UUID.nameUUIDFromBytes("listing-b".getBytes(StandardCharsets.UTF_8)).toString(),
              "2000", "200", "160", "16", "SO-B-1", "1000.00"));

      upstream = HttpServer.create(new InetSocketAddress(0), 0);
      upstream.createContext("/channel/listing-performance", e -> respond(e, listingBody(e)));
      upstream.createContext("/channel/ad-reports/rows", e -> respond(e, adBody(e)));
      upstream.createContext("/trade/sales-orders/list", e -> respond(e, ordersBody(e)));
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
  @Autowired ReportingTrafficConversionRefreshService refresh;
  @Autowired ReportingTrafficConversionService service;

  @AfterAll
  static void stop() {
    // ⚠️ **先停外部服务、再停容器**（顺序反了会让 ryuk 的回收窗口变长）。
    identity.stop(0);
    upstream.stop(0);
    pg.stop();
  }

  @BeforeEach
  void reset() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate reporting_traffic_conversion_metric_definition,"
              + " reporting_traffic_conversion_fact,"
              + " reporting_traffic_conversion_drilldown_fact cascade");
    }
  }

  // ==========================================================================
  // 断言 1：两租户互不可见
  // ==========================================================================

  @Test
  void 断言1_两租户互不可见() {
    refresh.refresh(a, BEARER_A, day1, day1);
    refresh.refresh(b, BEARER_B, day1, day1);

    var reportA = service.report(a, day1, day1, "DAY");
    var reportB = service.report(b, day1, day1, "DAY");

    assertThat(impressionsOf(reportA)).as("租户 A 只能看到自己的平台曝光").isEqualByComparingTo("1000");
    assertThat(impressionsOf(reportB)).as("租户 B 只能看到自己的平台曝光").isEqualByComparingTo("2000");
    assertThat(orderCountOf(reportA)).as("租户 A 只能看到自己的订单").isEqualByComparingTo("1");
    assertThat(orderCountOf(reportB)).as("租户 B 只能看到自己的订单").isEqualByComparingTo("1");

    // 更强的一格：A 明确按来源行直查 B 的行也拿不到（RLS 挡住）。
    var cross =
        tenants.run(
            a,
            () ->
                sql.fetch(
                        "select metric_code from reporting_traffic_conversion_fact "
                            + "where tenant_id = ? and shop_code = ?",
                        b,
                        "shop-b")
                    .size());
    assertThat(cross).as("按店铺直查也必须被 RLS 挡住").isZero();

    // 构成明细同样隔离。
    var crossDrill =
        tenants.run(
            a,
            () ->
                sql.fetch(
                        "select reference_id from reporting_traffic_conversion_drilldown_fact "
                            + "where tenant_id = ? and reference_id = ?",
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
            post("/reporting/traffic-conversion/refresh")
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
                        "select shop_code from reporting_traffic_conversion_fact "
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
                        "select shop_code from reporting_traffic_conversion_fact "
                            + "where tenant_id = ? and shop_code = ?",
                        b,
                        "shop-a")
                    .size());
    assertThat(landedInB).as("被伪造的租户不得看见别人的流量事实").isZero();

    // A 的报表里只有 shop-a 的量；B 的报表里仍然只有它自己的量。
    assertThat(impressionsOf(service.report(a, day1, day1, "DAY"))).isEqualByComparingTo("1000");
    assertThat(impressionsOf(service.report(b, day1, day1, "DAY"))).isEqualByComparingTo("2000");
  }

  @Test
  void 断言2_伪造租户头不影响口径目录() throws Exception {
    http.perform(get("/reporting/traffic-conversion/metrics").header("Authorization", BEARER_A))
        .andExpect(status().isOk());
    var defsA = service.metricDefinitions(a);
    var defsB = service.metricDefinitions(b);
    assertThat(defsA).isNotEmpty();
    assertThat(defsB).isNotEmpty();
    // 两个租户各自播种自己的标准口径，互不影响（种子相同但行各属各的租户）。
    var codesA =
        tenants.run(
            a,
            () ->
                sql.fetch("select metric_code from reporting_traffic_conversion_metric_definition")
                    .size());
    var codesB =
        tenants.run(
            b,
            () ->
                sql.fetch("select metric_code from reporting_traffic_conversion_metric_definition")
                    .size());
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
      assertThat(impressionsOf(service.report(a, day1, day1, "DAY")))
          .as("第 %d 轮租户 A", i).isEqualByComparingTo("1000");
      assertThat(impressionsOf(service.report(b, day1, day1, "DAY")))
          .as("第 %d 轮租户 B", i).isEqualByComparingTo("2000");
      assertThat(visibleFactsWithoutTenant()).as("第 %d 轮事务外", i).isZero();
    }
    // 归还后再单独借一次，仍然干净。
    assertThat(impressionsOf(service.report(a, day1, day1, "DAY"))).isEqualByComparingTo("1000");
    assertThat(impressionsOf(service.report(b, day1, day1, "DAY"))).isEqualByComparingTo("2000");
  }

  // ==========================================================================
  // 工具
  // ==========================================================================

  private BigDecimal impressionsOf(
      ReportingTrafficConversionModels.TrafficConversionResponse report) {
    return report.keyMetrics().stream()
        .filter(m -> ReportingTrafficConversionCatalog.IMPRESSIONS.equals(m.metricCode()))
        .findFirst()
        .orElseThrow()
        .value();
  }

  private BigDecimal orderCountOf(
      ReportingTrafficConversionModels.TrafficConversionResponse report) {
    return report.keyMetrics().stream()
        .filter(m -> ReportingTrafficConversionCatalog.ORDER_COUNT.equals(m.metricCode()))
        .findFirst()
        .orElseThrow()
        .value();
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
    return sql.fetch("select id from reporting_traffic_conversion_fact").size()
        + sql.fetch("select id from reporting_traffic_conversion_drilldown_fact").size();
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

  private static void respond(com.sun.net.httpserver.HttpExchange e, Object body)
      throws java.io.IOException {
    if (body == null) return;
    byte[] bytes = JSON.writeValueAsBytes(body);
    e.getResponseHeaders().add("Content-Type", "application/json");
    e.sendResponseHeaders(200, bytes.length);
    e.getResponseBody().write(bytes);
    e.close();
  }

  /** 平台表现快照。 */
  private static Object listingBody(com.sun.net.httpserver.HttpExchange e)
      throws java.io.IOException {
    var data = dataOf(e);
    if (data == null) return null;
    var row = new LinkedHashMap<String, Object>();
    row.put("listingId", data.listingId());
    row.put("externalItemId", "EXT-" + data.shopCode());
    row.put("title", "商品 " + data.shopCode());
    row.put("shopCode", data.shopCode());
    row.put("platformCode", "AMAZON");
    row.put("statDate", day1.toString());
    row.put("impressions", new BigDecimal(data.impressions()));
    row.put("clicks", new BigDecimal(data.clicks()));
    row.put("sessions", new BigDecimal(data.sessions()));
    row.put("unitsOrdered", new BigDecimal(data.units()));
    row.put("salesAmount", new BigDecimal(data.units()).multiply(new BigDecimal("100")));
    row.put("currency", "USD");
    var page = new LinkedHashMap<String, Object>();
    page.put("items", List.of(row));
    page.put("page", 0);
    page.put("size", 200);
    page.put("total", 1);
    page.put("syncCount", 1);
    return page;
  }

  /** 广告报表（**本租户没有广告数据** ⇒ 广告漏斗为空，跨口径诊断能看出差异）。 */
  private static Object adBody(com.sun.net.httpserver.HttpExchange e) throws java.io.IOException {
    var data = dataOf(e);
    if (data == null) return null;
    var page = new LinkedHashMap<String, Object>();
    page.put("items", List.of());
    page.put("page", 0);
    page.put("size", 200);
    page.put("total", 0);
    page.put("granularity", "DAY");
    page.put("dimension", "CAMPAIGN");
    page.put("emptyReason", "NO_SYNCED_DATA");
    page.put("emptyNote", "没有广告数据");
    return page;
  }

  /** 内部销售订单。 */
  private static Object ordersBody(com.sun.net.httpserver.HttpExchange e)
      throws java.io.IOException {
    var data = dataOf(e);
    if (data == null) return null;
    var at = OffsetDateTime.of(
        day1.atTime(10, 0), java.time.ZoneOffset.UTC);
    var row = new LinkedHashMap<String, Object>();
    row.put("id", UUID.nameUUIDFromBytes(data.orderNo().getBytes(StandardCharsets.UTF_8)));
    row.put("orderNo", data.orderNo());
    row.put("orderSource", "MANUAL");
    row.put("currency", "USD");
    row.put("grandTotalAmount", new BigDecimal(data.amount()));
    row.put("lineCount", 1);
    row.put("submittedAt", at.toString());
    row.put("createdAt", at.toString());
    var page = new LinkedHashMap<String, Object>();
    page.put("rows", List.of(row));
    page.put("page", 0);
    page.put("size", 200);
    page.put("total", 1);
    return page;
  }

  /** 查询串解析（保留给将来加筛选时用）。 */
  static Map<String, String> query(String rawQuery) {
    var out = new LinkedHashMap<String, String>();
    if (rawQuery == null || rawQuery.isBlank()) return out;
    for (var pair : rawQuery.split("&")) {
      var index = pair.indexOf('=');
      if (index < 0) continue;
      out.put(
          URLDecoder.decode(pair.substring(0, index), StandardCharsets.UTF_8),
          URLDecoder.decode(pair.substring(index + 1), StandardCharsets.UTF_8));
    }
    return out;
  }

  /** 供上面用：把字符串解析成瞬间。 */
  static Instant parseInstant(String value) {
    return value == null ? null : Instant.parse(value.trim());
  }
}
