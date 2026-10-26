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
 * 13.1.1.2 多店对比的**多租户隔离测试**（真实 PostgreSQL 16 + FORCE RLS，无内存替身）。
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
 * 转发的语义 —— 转发调用方自己的 bearer，不传租户号）。所以租户 A 的上游只给 shop-a，
 * 租户 B 的上游只给 shop-b。这样「B 看不见 A 的事实」才能真正证明「B 只看得见自己的」
 * —— 否则 B 什么都没看见，「没串租户」只是碰巧。
 *
 * <p>⚠️ <b>连接池固定为 1 条连接</b>：只有复用同一条连接，才能真正证明归还后不会串租户 ——
 * 池大于 1 时每次可能拿到不同连接，「没串」可能只是碰巧换了条干净连接。
 */
@SpringBootTest
@AutoConfigureMockMvc
class StoreComparisonTenancyIntegrationTest {

  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  static HttpServer upstream;

  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  static final List<String> permissions =
      List.of(
          "reporting.store-comparison.read",
          // ⚠️ 写权限必须在**同一个 grant** 里同时命中对应的 .read ——
          //   服务端的判据是「能写不能读」的组合不放行，所以这里必须带上
          //   reporting.projection.read，否则刷新端点直接 403。
          "reporting.projection.read",
          "reporting.projection.write");

  static final ObjectMapper JSON = JsonMapper.builder().addModule(new JavaTimeModule()).build();

  static final LocalDate DAY1 = LocalDate.of(2026, 10, 1);
  static final LocalDate DAY2 = LocalDate.of(2026, 10, 2);
  static final LocalDate PREV1 = LocalDate.of(2026, 9, 29);

  static final ZoneId ZONE = ZoneId.of("UTC");

  static final String BEARER_A = "Bearer tenant-a";
  static final String BEARER_B = "Bearer tenant-b";

  /** 一路上游的数据（按 bearer 分租户）。 */
  record UpstreamData(
      List<Map<String, Object>> shops,
      List<Map<String, Object>> platforms,
      List<Map<String, Object>> campaigns,
      List<Map<String, Object>> orders,
      List<Map<String, Object>> ads,
      List<Map<String, Object>> aftersales) {}

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

      // 租户 A：只有 shop-a（亚马逊）
      UPSTREAM.put(
          BEARER_A,
          new UpstreamData(
              List.of(option("shop-a", "亚马逊旗舰店")),
              List.of(option("AMAZON", "亚马逊")),
              List.of(campaign("shop-a", "亚马逊旗舰店", "AMAZON", "亚马逊")),
              List.of(
                  order("SO-A-1", "shop-a", DAY1, "100.00", "100.00"),
                  order("SO-A-0", "shop-a", PREV1, "100.00", "100.00")),
              List.of(ad(DAY1, "shop-a", "10.00", "50.00"), ad(DAY2, "shop-a", "10.00", "50.00")),
              List.of(aftersales("AS-A-1", "SO-A-1", "5.00", DAY1))));
      // 租户 B：只有 shop-b（TikTok）
      UPSTREAM.put(
          BEARER_B,
          new UpstreamData(
              List.of(option("shop-b", "TikTok 小店")),
              List.of(option("TIKTOK", "TikTok")),
              List.of(campaign("shop-b", "TikTok 小店", "TIKTOK", "TikTok")),
              List.of(
                  order("SO-B-1", "shop-b", DAY1, "50.00", "50.00"),
                  order("SO-B-0", "shop-b", PREV1, "50.00", "50.00")),
              List.of(ad(DAY1, "shop-b", "5.00", "25.00"), ad(DAY2, "shop-b", "5.00", "25.00")),
              List.of(aftersales("AS-B-1", "SO-B-1", "2.00", DAY1))));

      upstream = HttpServer.create(new InetSocketAddress(0), 0);
      upstream.createContext("/channel/ad-reports/options", e -> respond(e, optionsBody(e)));
      upstream.createContext("/channel/ad-campaigns/campaigns", e -> respond(e, pageBody(e, "campaigns")));
      upstream.createContext("/channel/ad-reports/rows", e -> respond(e, adRowsBody(e)));
      upstream.createContext("/trade/platform-orders/orders", e -> respond(e, pageBody(e, "orders")));
      upstream.createContext("/trade/aftersales/cases", e -> respond(e, pageBody(e, "aftersales")));
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
  @Autowired ReportingStoreComparisonRefreshService refresh;
  @Autowired ReportingStoreComparisonService comparison;

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
          "truncate reporting_store_metric_fact, reporting_store_metric_definition,"
              + " reporting_store_profile cascade");
    }
  }

  // ==========================================================================
  // 断言 1：两租户互不可见
  // ==========================================================================

  @Test
  void 断言1_两租户互不可见() {
    refresh.refresh(a, BEARER_A, DAY1, DAY2, ZONE);
    refresh.refresh(b, BEARER_B, DAY1, DAY2, ZONE);

    var rowsA = comparison.comparison(a, DAY1, DAY2, null, null).rows();
    var rowsB = comparison.comparison(b, DAY1, DAY2, null, null).rows();

    assertThat(rowsA).as("租户 A 只能看到自己的店铺").hasSize(1);
    assertThat(rowsA.get(0).shopCode()).isEqualTo("shop-a");
    assertThat(rowsB).as("租户 B 只能看到自己的店铺").hasSize(1);
    assertThat(rowsB.get(0).shopCode()).isEqualTo("shop-b");

    // 更强的一格：A 明确按来源行直查 B 的行也拿不到（RLS 挡住）。
    var cross =
        tenants.run(
            a,
            () ->
                sql.fetch(
                        "select source_reference from reporting_store_metric_fact "
                            + "where tenant_id = ? and shop_code = ?",
                        b,
                        "shop-b")
                    .size());
    assertThat(cross).as("按店铺直查也必须被 RLS 挡住").isZero();
  }

  @Test
  void 断言1_店铺清单也按租户隔离() {
    refresh.refresh(a, BEARER_A, DAY1, DAY2, ZONE);
    refresh.refresh(b, BEARER_B, DAY1, DAY2, ZONE);

    assertThat(comparison.stores(a).stores()).extracting(ReportingStoreComparisonModels.StoreSummary::shopCode)
        .containsExactly("shop-a");
    assertThat(comparison.stores(b).stores()).extracting(ReportingStoreComparisonModels.StoreSummary::shopCode)
        .containsExactly("shop-b");
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
            post("/reporting/store-comparison/refresh")
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
                        "select shop_code from reporting_store_metric_fact "
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
                        "select shop_code from reporting_store_metric_fact "
                            + "where tenant_id = ? and shop_code = ?",
                        b,
                        "shop-a")
                    .size());
    assertThat(landedInB).as("被伪造的租户不得看见别人的店铺事实").isZero();

    // A 的矩阵里只有 shop-a；B 的矩阵里仍然只有它自己的 shop-b。
    var rowsA = comparison.comparison(a, DAY1, DAY2, null, null).rows();
    var rowsB = comparison.comparison(b, DAY1, DAY2, null, null).rows();
    assertThat(rowsA).as("A 只看得到自己的店铺").hasSize(1);
    assertThat(rowsA.get(0).shopCode()).isEqualTo("shop-a");
    assertThat(rowsB).as("B 只看得到自己的店铺").hasSize(1);
    assertThat(rowsB.get(0).shopCode()).isEqualTo("shop-b");
  }

  @Test
  void 断言2_伪造租户头不影响口径目录() throws Exception {
    // 口径目录也是租户级的：伪造头不得让 A 看到 B 的口径。
    http.perform(get("/reporting/store-comparison/metrics").header("Authorization", BEARER_A))
        .andExpect(status().isOk());
    var defsA = comparison.metricDefinitions(a);
    var defsB = comparison.metricDefinitions(b);
    assertThat(defsA).isNotEmpty();
    assertThat(defsB).isNotEmpty();
    // 两个租户各自播种自己的标准口径，互不影响（种子相同但行各属各的租户）。
    var codesA =
        tenants.run(
            a, () -> sql.fetch("select metric_code from reporting_store_metric_definition").size());
    var codesB =
        tenants.run(
            b, () -> sql.fetch("select metric_code from reporting_store_metric_definition").size());
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
      assertThat(shopsOf(a)).as("第 %d 轮租户 A", i).containsExactly("shop-a");
      assertThat(shopsOf(b)).as("第 %d 轮租户 B", i).containsExactly("shop-b");
      assertThat(visibleFactsWithoutTenant()).as("第 %d 轮事务外", i).isZero();
    }
    // 归还后再单独借一次，仍然干净。
    assertThat(shopsOf(a)).containsExactly("shop-a");
    assertThat(shopsOf(b)).containsExactly("shop-b");
  }

  // ==========================================================================
  // 工具
  // ==========================================================================

  private List<String> shopsOf(UUID tenant) {
    return comparison.comparison(tenant, DAY1, DAY2, null, null).rows().stream()
        .map(ReportingStoreComparisonModels.StoreComparisonRow::shopCode)
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
    return sql.fetch("select id from reporting_store_metric_fact").size()
        + sql.fetch("select id from reporting_store_profile").size();
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

  private static Map<String, Object> optionsBody(com.sun.net.httpserver.HttpExchange e)
      throws java.io.IOException {
    var data = dataOf(e);
    if (data == null) return null;
    var body = new LinkedHashMap<String, Object>();
    body.put("shops", data.shops());
    body.put("platforms", data.platforms());
    return body;
  }

  private static Map<String, Object> pageBody(com.sun.net.httpserver.HttpExchange e, String field)
      throws java.io.IOException {
    var data = dataOf(e);
    if (data == null) return null;
    // ⚠️ 广告活动用 items，平台单与售后单用 rows —— 两个 trade 端点的**列表字段名相同**，
    // 所以这里必须按**端点**分开取，不能只按字段名分。
    List<Map<String, Object>> rows =
        switch (field) {
          case "campaigns" -> data.campaigns();
          case "orders" -> data.orders();
          default -> data.aftersales();
        };
    return page(rows, "items".equals(field) ? "items" : "rows");
  }

  private static Map<String, Object> page(List<Map<String, Object>> rows, String key) {
    var body = new LinkedHashMap<String, Object>();
    body.put(key, rows);
    body.put("page", 0);
    body.put("size", 200);
    body.put("total", rows.size());
    return body;
  }

  /** 店铺维度广告指标：按 from/to 过滤（端口逐日拉）。 */
  private static Map<String, Object> adRowsBody(com.sun.net.httpserver.HttpExchange e)
      throws java.io.IOException {
    var data = dataOf(e);
    if (data == null) return null;
    var query = query(e.getRequestURI().getRawQuery());
    var from = query.get("from");
    var to = query.get("to");
    var matched = new ArrayList<Map<String, Object>>();
    for (var row : data.ads()) {
      var day = String.valueOf(row.get("metricFrom"));
      if (from != null && !from.equals(day)) continue;
      if (to != null && !to.equals(day)) continue;
      matched.add(row);
    }
    var body = new LinkedHashMap<String, Object>();
    body.put("items", matched);
    body.put("page", 0);
    body.put("size", 200);
    body.put("total", matched.size());
    return body;
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

  private static Map<String, Object> option(String value, String label) {
    var m = new LinkedHashMap<String, Object>();
    m.put("value", value);
    m.put("label", label);
    return m;
  }

  private static Map<String, Object> campaign(
      String shopCode, String shopName, String platformCode, String platformName) {
    var m = new LinkedHashMap<String, Object>();
    m.put("shopCode", shopCode);
    m.put("shopName", shopName);
    m.put("platformCode", platformCode);
    m.put("platformName", platformName);
    m.put("currency", "USD");
    m.put("spend", new BigDecimal("0.00"));
    m.put("sales", new BigDecimal("0.00"));
    return m;
  }

  private static Map<String, Object> order(
      String orderNo, String shopCode, LocalDate day, String platformAmount, String restoredAmount) {
    var m = new LinkedHashMap<String, Object>();
    m.put("id", UUID.nameUUIDFromBytes(orderNo.getBytes(StandardCharsets.UTF_8)).toString());
    m.put("orderNo", orderNo);
    m.put("externalOrderNo", "EXT-" + orderNo);
    m.put("platformCode", "AMAZON");
    m.put("shopCode", shopCode);
    m.put("currency", "USD");
    m.put("platformGrandTotalAmount", new BigDecimal(platformAmount));
    m.put("restoredGrandTotalAmount", new BigDecimal(restoredAmount));
    m.put("platformPlacedAt", day.atTime(10, 0).atOffset(ZoneOffset.UTC).toString());
    m.put("syncedAt", day.atTime(10, 5).atOffset(ZoneOffset.UTC).toString());
    return m;
  }

  private static Map<String, Object> ad(LocalDate day, String shopCode, String spend, String sales) {
    var m = new LinkedHashMap<String, Object>();
    m.put("dimensionKey", shopCode);
    m.put("dimensionLabel", shopCode);
    m.put("currency", "USD");
    m.put("impressions", new BigDecimal("1000"));
    m.put("clicks", new BigDecimal("50"));
    m.put("orders", new BigDecimal("5"));
    m.put("spend", new BigDecimal(spend));
    m.put("sales", new BigDecimal(sales));
    m.put("metricFrom", day.toString());
    m.put("metricTo", day.toString());
    return m;
  }

  private static Map<String, Object> aftersales(
      String caseNo, String orderNo, String amount, LocalDate day) {
    var m = new LinkedHashMap<String, Object>();
    m.put("id", UUID.nameUUIDFromBytes(caseNo.getBytes(StandardCharsets.UTF_8)).toString());
    m.put("caseNo", caseNo);
    m.put("orderNo", orderNo);
    m.put("caseType", "RETURN");
    m.put("status", "COMPLETED");
    m.put("requestedAmount", new BigDecimal(amount));
    m.put("currency", "USD");
    m.put("requestedQuantity", new BigDecimal("1"));
    m.put("receivedAt", day.atTime(12, 0).atOffset(ZoneOffset.UTC).toString());
    return m;
  }
}
