package com.starcon.platform;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.platform.AiForecastModels.*;
import com.starcon.tenancy.TenantTransactions;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
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
 * 18.4.2.2「选品与需求预测」的**功能**集成测试，跑在真实 PostgreSQL 16 上。
 *
 * <p><b>真实 PostgreSQL 16 + FORCE RLS，不使用任何内存替身。</b>
 *
 * <p>本测试盯住七件事：
 *
 * <ol>
 *   <li><b>每条预测都有区间与影响因素</b>（蓝图加粗句「预测必须给置信区间和影响因素」）；
 *   <li><b>样本不足就不给预测</b>（不给点估计也不给区间），并写明跳过原因；
 *   <li><b>促销影响真的进得了预测</b>（促销因子 + PROMOTION 因素）；
 *   <li><b>建议只产出、不执行</b>：有 SUGGESTED / ADOPTED / REJECTED，**没有「已执行」**；
 *   <li><b>上游读不到就不生成建议</b>（不用 0 冒充「库存为 0」）；
 *   <li><b>同步撞键不重复计数</b>（同一 SKU 同一天只允许一条）；
 *   <li><b>价格弹性算不出来就不给调价建议</b>，并报出原因。
 * </ol>
 *
 * <p>租户隔离的三条断言在 {@code AiForecastTenancyIntegrationTest} 里（同一批表、同一套夹具）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AiForecastIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static HttpServer identity;
  static HttpServer reporting;
  static HttpServer warehouse;
  static HttpServer trade;

  /** 🔴 **上游开不开**由测试自己控制**：读不到上游的那几格必须表现得像真的读不到。 */
  static volatile boolean reportingUp = true;
  static volatile boolean warehouseUp = true;
  static volatile boolean tradeUp = true;

  static final ObjectMapper json =
      new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

  static {
    pg.start();
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      // 运行角色必须 NOBYPASSRLS，否则它会绕过策略，隔离断言全部变成空断言。
      s.execute("create role starcon_app nologin");
      s.execute("create role platform_app login password 'test-only' nosuperuser nobypassrls");
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
    var permissions =
        List.of(
            "platform.ai_forecast.read",
            "platform.ai_forecast.create",
            "platform.ai_forecast.update",
            "platform.ai_forecast.adopt");
    actors.put(
        "session-a",
        new AccessService.Access(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "Test",
            UUID.randomUUID(),
            null,
            permissions,
            List.of(new AccessService.Grant(UUID.randomUUID(), "TEST", "ALL", permissions))));
    try {
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
      identity.setExecutor(Executors.newFixedThreadPool(2));
      identity.start();

      // ---- 13 分析（reporting）：销量趋势 ----
      reporting = HttpServer.create(new InetSocketAddress(0), 0);
      reporting.createContext(
          "/reporting/sales-trend",
          e -> {
            if (!reportingUp) {
              e.sendResponseHeaders(500, -1);
              e.close();
              return;
            }
            var query = e.getRequestURI().getRawQuery() == null ? "" : e.getRequestURI().getRawQuery();
            var from = query.contains("from=") ? query.substring(query.indexOf("from=") + 5).split("&")[0] : "";
            var to = query.contains("to=") ? query.substring(query.indexOf("to=") + 3).split("&")[0] : "";
            var start = LocalDate.parse(from);
            var end = LocalDate.parse(to);
            var points = new ArrayList<Map<String, Object>>();
            for (var day = start; !day.isAfter(end); day = day.plusDays(1)) {
              points.add(Map.of("bucketStart", day.toString(), "value", 7));
            }
            byte[] body =
                json.writeValueAsBytes(
                    Map.of(
                        "from", start.toString(),
                        "to", end.toString(),
                        "granularity", "DAY",
                        "status", "OK",
                        "series",
                            List.of(
                                Map.of(
                                    "metricCode", "PRODUCT_SALES_QUANTITY",
                                    "dimensionType", "PRODUCT",
                                    "dimensionKey", "SKU-SYNC",
                                    "dimensionLabel", "同步测试款",
                                    "currency", "",
                                    "points", points))));
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      reporting.setExecutor(Executors.newFixedThreadPool(2));
      reporting.start();

      // ---- 7 库存（warehouse）：在手库存 + 在途净额 ----
      warehouse = HttpServer.create(new InetSocketAddress(0), 0);
      warehouse.createContext(
          "/warehouse/inventory/overview",
          e -> {
            if (!warehouseUp) {
              e.sendResponseHeaders(500, -1);
              e.close();
              return;
            }
            byte[] body =
                json.writeValueAsBytes(
                    Map.of(
                        "rows",
                            List.of(
                                Map.of(
                                    "skuId", UUID.randomUUID().toString(),
                                    "skuCode", "SKU-A",
                                    "skuName", "测试款 A",
                                    "onHand", 40,
                                    "reserved", 0,
                                    "available", 40),
                                Map.of(
                                    "skuId", UUID.randomUUID().toString(),
                                    "skuCode", "SKU-B",
                                    "skuName", "测试款 B",
                                    "onHand", 10,
                                    "reserved", 0,
                                    "available", 10)),
                        "total", 2,
                        "page", 0,
                        "size", 200));
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      warehouse.createContext(
          "/warehouse/inventory/in-transit/netting",
          e -> {
            if (!warehouseUp) {
              e.sendResponseHeaders(500, -1);
              e.close();
              return;
            }
            byte[] body =
                json.writeValueAsBytes(
                    Map.of(
                        "total", 1,
                        "totalOutstandingQuantity", 25,
                        "usageNote", "按目的仓 × SKU 聚合的在途量",
                        "rows",
                            List.of(
                                Map.of(
                                    "toWarehouseId", UUID.randomUUID().toString(),
                                    "toWarehouseCode", "WH-1",
                                    "skuId", UUID.randomUUID().toString(),
                                    "skuCode", "SKU-A",
                                    "skuName", "测试款 A",
                                    "outstandingQuantity", 25,
                                    "legCount", 2))));
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      warehouse.setExecutor(Executors.newFixedThreadPool(2));
      warehouse.start();

      // ---- 6 采购（trade）：采购事实 ----
      trade = HttpServer.create(new InetSocketAddress(0), 0);
      trade.createContext(
          "/trade/purchase-analytics/facts",
          e -> {
            if (!tradeUp) {
              e.sendResponseHeaders(500, -1);
              e.close();
              return;
            }
            byte[] body =
                json.writeValueAsBytes(
                    Map.of(
                        "items",
                            List.of(
                                Map.of(
                                    "factKind", "PLAN",
                                    "sourceType", "PURCHASE_ORDER",
                                    "occurredDate", LocalDate.now(ZoneOffset.UTC).toString(),
                                    "skuCode", "SKU-A",
                                    "skuName", "测试款 A",
                                    "quantity", 60),
                                Map.of(
                                    "factKind", "RECEIPT",
                                    "sourceType", "PURCHASE_ORDER",
                                    "occurredDate", LocalDate.now(ZoneOffset.UTC).toString(),
                                    "skuCode", "SKU-A",
                                    "skuName", "测试款 A",
                                    "quantity", 20)),
                        "total", 2,
                        "page", 0,
                        "size", 200,
                        "note", "采购事实"));
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      trade.setExecutor(Executors.newFixedThreadPool(2));
      trade.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "platform_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://127.0.0.1:" + identity.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
    r.add("REPORTING_URL", () -> "http://127.0.0.1:" + reporting.getAddress().getPort());
    r.add("WAREHOUSE_URL", () -> "http://127.0.0.1:" + warehouse.getAddress().getPort());
    r.add("TRADE_URL", () -> "http://127.0.0.1:" + trade.getAddress().getPort());
  }

  @Autowired DSLContext sql;
  @Autowired TenantTransactions tenants;
  @Autowired MockMvc http;

  // ================================================================ 夹具

  @BeforeEach
  void resetThenSeed() throws Exception {
    reportingUp = true;
    warehouseUp = true;
    tradeUp = true;
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate table platform_forecast_factor, platform_forecast_suggestion,"
              + " platform_forecast_result, platform_forecast_run, platform_forecast_demand_history,"
              + " platform_forecast_promotion, platform_app_audit_event, platform_app_quota_usage,"
              + " platform_app_quota, platform_app_ip_allowlist, platform_app_scope,"
              + " platform_app_credential, platform_app restart identity cascade");
      s.execute("alter sequence platform_forecast_run_no_seq restart with 1");
    }
  }

  /** 造 N 天逐日销量（可选价格）。 */
  void seedHistory(String tenantId, String skuCode, String skuName, int days, List<BigDecimal> units, List<BigDecimal> prices) {
    var tenant = UUID.fromString(tenantId);
    var today = LocalDate.now(ZoneOffset.UTC);
    tenants.run(
        tenant,
        () -> {
          for (int i = 0; i < days; i++) {
            var day = today.minusDays(days - 1L - i);
            sql.execute(
                "insert into platform_forecast_demand_history(id,tenant_id,sku_code,sku_name,"
                    + " business_date,units,unit_price,revenue,currency,channel_code,source,source_ref,"
                    + " recorded_at) values(?,?,?,?,?::date,?,?,?,?,'','MANUAL','seed',?::timestamptz)",
                UUID.randomUUID(),
                tenant,
                skuCode,
                skuName,
                day,
                units.get(i),
                prices == null ? null : prices.get(i),
                prices == null ? null : units.get(i).multiply(prices.get(i)),
                prices == null ? null : "USD",
                OffsetDateTime.now(ZoneOffset.UTC));
          }
          return null;
        });
  }

  static BigDecimal b(String value) {
    return new BigDecimal(value);
  }

  static List<BigDecimal> list(String... values) {
    var out = new ArrayList<BigDecimal>();
    for (var value : values) out.add(new BigDecimal(value));
    return out;
  }

  /** 10 天销量 + 两次价格变化（供价格弹性用）。 */
  void seedPriceSeries(String tenantId, String skuCode) {
    seedHistory(
        tenantId,
        skuCode,
        "测试款 " + skuCode,
        10,
        list("10", "10", "10", "14", "14", "14", "18", "18", "19", "20"),
        list("20", "20", "20", "18", "18", "18", "16", "16", "16", "16"));
  }

  /** 10 天销量、单价不变。 */
  void seedPlainSeries(String tenantId) {
    seedHistory(
        tenantId,
        "SKU-A",
        "测试款 A",
        10,
        list("10", "11", "12", "11", "10", "12", "11", "13", "12", "14"),
        null);
  }

  String tenantId() {
    return actors.get("session-a").tenantId().toString();
  }

  // ================================================================ 能力声明

  @Test
  void 能力声明逐条报出算法口径与阈值且不吹成ai() throws Exception {
    var caps =
        json.readValue(
            http.perform(get("/platform/ai/forecast/capabilities").header("Authorization", "session-a"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            AiForecastCapabilities.class);

    // 算法口径：两个算法都要有，且带参数说明。
    assertThat(caps.algorithms()).extracting(AiForecastAlgorithm::code)
        .containsExactlyInAnyOrder("MOVING_AVERAGE", "EXPONENTIAL_SMOOTHING");
    assertThat(caps.algorithms()).allSatisfy(a -> assertThat(a.params()).isNotEmpty());

    // 阈值 + 统计窗口必须写出来（爆品 / 机会品判定不是黑盒）。
    assertThat(caps.thresholds()).extracting(AiForecastThreshold::code)
        .contains("HOT_UNITS", "HOT_GROWTH", "OPPORTUNITY_TREND", "MIN_SAMPLE_DAYS");

    // 上游来源逐条列出。
    assertThat(caps.sources()).extracting(AiForecastSource::code)
        .contains("REPORTING_SALES", "WAREHOUSE_ON_HAND", "WAREHOUSE_IN_TRANSIT", "TRADE_PURCHASE");

    // 🔴 **AI 模型那一维必须如实答「没有」**，并写明归谁。
    var ai =
        caps.capabilities().stream()
            .filter(c -> c.name().contains("AI 大模型"))
            .findFirst()
            .orElse(null);
    assertThat(ai).as("AI 供应商未接入这一维必须出现在能力声明里").isNotNull();
    assertThat(ai.available()).isFalse();
    assertThat(ai.ownerModule()).isEqualTo("18.4");

    // 下游缺口：补货单与调价**不是**本模块写的。
    assertThat(caps.gaps()).extracting(AiForecastGap::moduleId).contains("6.2.1.2", "5.4");
  }

  // ================================================================ 促销日历

  @Test
  void 促销必须给出预期提升率否则预测无法计入促销影响() throws Exception {
    http.perform(
            post("/platform/ai/forecast/promotions")
                .header("Authorization", "session-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"name":"黑五","promotionType":"BIG_SALE","scopeType":"SKU","scopeValue":"SKU-A",
                     "startsAt":"2030-11-28T00:00:00Z","endsAt":"2030-11-30T23:00:00Z",
                     "discountRate":0.2}
                    """))
        .andExpect(status().isBadRequest());

    var row =
        json.readValue(
            http.perform(
                    post("/platform/ai/forecast/promotions")
                        .header("Authorization", "session-a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """
                            {"name":"黑五","promotionType":"BIG_SALE","scopeType":"SKU","scopeValue":"SKU-A",
                             "startsAt":"2030-11-28T00:00:00Z","endsAt":"2030-11-30T23:00:00Z",
                             "discountRate":0.2,"expectedLift":0.3,"source":"MANUAL","sourceRef":"黑五计划"}
                            """))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            AiForecastPromotionRow.class);
    assertThat(row.expectedLift()).isEqualByComparingTo("0.3");
    assertThat(row.source()).isEqualTo("MANUAL");
    assertThat(row.sourceRef()).isEqualTo("黑五计划");
  }

  @Test
  void 促销日历是真筛选的() throws Exception {
    for (var name : List.of("黑五", "会员日")) {
      http.perform(
              post("/platform/ai/forecast/promotions")
                  .header("Authorization", "session-a")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      """
                      {"name":"%s","promotionType":"PROMO","scopeType":"ALL",
                       "startsAt":"2030-11-28T00:00:00Z","endsAt":"2030-11-30T23:00:00Z",
                       "expectedLift":0.2}
                      """
                          .formatted(name)))
          .andExpect(status().isOk());
    }
    var page =
        json.readValue(
            http.perform(
                    get("/platform/ai/forecast/promotions")
                        .header("Authorization", "session-a")
                        .param("keyword", "黑五"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            AiForecastPromotionPage.class);
    assertThat(page.items()).hasSize(1);
    assertThat(page.items().get(0).name()).isEqualTo("黑五");
    assertThat(page.total()).isEqualTo(1);
  }

  // ================================================================ 需求历史

  @Test
  void 需求历史同一sku同一天只允许一条避免重复计数() throws Exception {
    var body =
        """
        {"skuCode":"SKU-A","skuName":"测试款 A","businessDate":"%s","units":10}
        """
            .formatted(LocalDate.now(ZoneOffset.UTC).toString());
    http.perform(
            post("/platform/ai/forecast/history")
                .header("Authorization", "session-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk());
    // 第二条 ⇒ 409（不是静默覆盖，也不是累加）。
    http.perform(
            post("/platform/ai/forecast/history")
                .header("Authorization", "session-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isConflict());
  }

  @Test
  void 请求体不接受租户号伪造字段直接400() throws Exception {
    http.perform(
            post("/platform/ai/forecast/history")
                .header("Authorization", "session-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"skuCode":"SKU-A","businessDate":"2030-01-01","units":10,"tenantId":"%s"}
                    """
                        .formatted(UUID.randomUUID())))
        .andExpect(status().isBadRequest());
  }

  @Test
  void 从上游同步销量写进台账并标明来源() throws Exception {
    var result =
        json.readValue(
            http.perform(
                    post("/platform/ai/forecast/history/sync")
                        .header("Authorization", "session-a")
                        .param("from", LocalDate.now(ZoneOffset.UTC).minusDays(3).toString())
                        .param("to", LocalDate.now(ZoneOffset.UTC).toString()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            AiForecastHistorySyncResult.class);
    assertThat(result.connected()).isTrue();
    assertThat(result.rowsWritten()).isEqualTo(4);
    assertThat(result.source()).isEqualTo("REPORTING");

    var rows =
        tenants.run(
            UUID.fromString(tenantId()),
            () ->
                sql.fetch(
                    "select sku_code, source, units from platform_forecast_demand_history order by business_date"));
    assertThat(rows).hasSize(4);
    assertThat(rows.get(0).get("source", String.class)).isEqualTo("REPORTING");
    assertThat(rows.get(0).get("units", BigDecimal.class)).isEqualByComparingTo("7");
  }

  @Test
  void 上游读不到时同步如实报503且一个数据都不写() throws Exception {
    reportingUp = false;
    http.perform(
            post("/platform/ai/forecast/history/sync")
                .header("Authorization", "session-a")
                .param("from", LocalDate.now(ZoneOffset.UTC).minusDays(3).toString())
                .param("to", LocalDate.now(ZoneOffset.UTC).toString()))
        .andExpect(status().isServiceUnavailable());
    var count =
        tenants.run(
            UUID.fromString(tenantId()),
            () -> sql.fetch("select id from platform_forecast_demand_history").size());
    assertThat(count).as("上游读不到时必须一个数据都不写").isZero();
  }

  // ================================================================ 预测运行

  @Test
  void 预测必须给区间与影响因素() throws Exception {
    seedPlainSeries(tenantId());
    var detail = runForecast();
    assertThat(detail.results()).hasSize(1);
    var result = detail.results().get(0).result();

    // 🔴 点估计 + 区间三件齐全，且区间真的包住点估计。
    assertThat(result.point()).isNotNull();
    assertThat(result.lower()).isNotNull();
    assertThat(result.upper()).isNotNull();
    assertThat(result.lower()).isLessThanOrEqualTo(result.point());
    assertThat(result.upper()).isGreaterThanOrEqualTo(result.point());
    assertThat(result.lower()).isGreaterThanOrEqualTo(BigDecimal.ZERO);

    // 🔴 影响因素逐条带方向、取值与口径说明。
    var factors = detail.results().get(0).factors();
    assertThat(factors).extracting(AiForecastFactorRow::factorCode)
        .contains("BASE_LEVEL", "PROMOTION", "SEASONALITY", "PRICE_CHANGE", "DATA_QUALITY");
    assertThat(factors).allSatisfy(
        f -> {
          assertThat(f.direction()).isIn("UP", "DOWN", "NEUTRAL");
          assertThat(f.explanation()).isNotBlank();
          assertThat(f.factorLabel()).isNotBlank();
        });
  }

  @Test
  void 样本不足就不给预测并写明跳过原因() throws Exception {
    seedHistory(
        tenantId(), "SKU-SPARSE", "样本不足款", 2, list("5", "6"), null);
    var detail = runForecast();
    assertThat(detail.results()).as("样本不足的 SKU 不给预测").isEmpty();
    assertThat(detail.run().skippedSkuCount()).isEqualTo(1);
    assertThat(detail.run().skippedReason()).contains("样本不足");
  }

  @Test
  void 促销影响真的进得了预测() throws Exception {
    seedPlainSeries(tenantId());
    var plain = runForecast().results().get(0).result();

    // 登记一个覆盖预测期的大促。
    var tomorrow = LocalDate.now(ZoneOffset.UTC).plusDays(1);
    http.perform(
            post("/platform/ai/forecast/promotions")
                .header("Authorization", "session-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"name":"预测期大促","promotionType":"BIG_SALE","scopeType":"SKU","scopeValue":"SKU-A",
                     "startsAt":"%sT00:00:00Z","endsAt":"%sT23:00:00Z","expectedLift":0.5}
                    """
                        .formatted(tomorrow, tomorrow.plusDays(3))))
        .andExpect(status().isOk());

    var boosted = runForecast().results().get(0);
    assertThat(boosted.result().point()).isGreaterThan(plain.point());
    assertThat(boosted.result().promoFactor()).isGreaterThan(BigDecimal.ONE);

    var promo =
        boosted.factors().stream()
            .filter(f -> "PROMOTION".equals(f.factorCode()))
            .findFirst()
            .orElseThrow();
    assertThat(promo.direction()).isEqualTo("UP");
    assertThat(promo.impact()).isGreaterThan(BigDecimal.ZERO);
    assertThat(promo.explanation()).contains("预测期");
    assertThat(promo.source()).contains("platform_forecast_promotion");
  }

  // ================================================================ 建议

  @Test
  void 补货建议按上界扣库存与在途且只建议不执行() throws Exception {
    seedPlainSeries(tenantId());
    var detail = runForecast();
    var result = detail.results().get(0).result();

    var suggestions = suggestionPage("REPLENISH");
    assertThat(suggestions.items()).hasSize(1);
    var suggestion = suggestions.items().get(0);
    assertThat(suggestion.status()).isEqualTo("SUGGESTED");
    assertThat(suggestion.currentQty()).isEqualByComparingTo("40");
    assertThat(suggestion.inTransitQty()).isEqualByComparingTo("25");
    // 建议量 = max(0, 上界 − 在手 − 在途)
    var expected =
        result.upper()
            .subtract(new BigDecimal("40"))
            .subtract(new BigDecimal("25"))
            .max(BigDecimal.ZERO);
    assertThat(suggestion.suggestedQty()).isEqualByComparingTo(expected);
    assertThat(suggestion.basis()).contains("不建补货单");
    // 🔴 结构上**没有**「已执行」状态。
    assertThat(suggestion.status()).isNotEqualTo("EXECUTED");
  }

  @Test
  void 上游读不到就不生成补货建议而不是拿0冒充库存为0() throws Exception {
    seedPlainSeries(tenantId());
    warehouseUp = false;
    var detail = runForecast();
    assertThat(detail.run().inventoryConnected()).isFalse();
    assertThat(detail.run().inTransitConnected()).isFalse();
    assertThat(suggestionPage("REPLENISH").items()).isEmpty();
  }

  @Test
  void 采购侧未到货量只作参考不重复扣减() throws Exception {
    seedPlainSeries(tenantId());
    var detail = runForecast();
    var suggestion = suggestionPage("REPLENISH").items().get(0);
    assertThat(suggestion.purchasePendingQty()).isEqualByComparingTo("40");
    assertThat(suggestion.basis()).contains("仅作参考");
  }

  @Test
  void 建议可以被采纳或驳回但不能重复决策() throws Exception {
    seedPlainSeries(tenantId());
    runForecast();
    var id = suggestionPage("REPLENISH").items().get(0).id();

    http.perform(
            post("/platform/ai/forecast/suggestions/" + id + "/adopt")
                .header("Authorization", "session-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"note\":\"人工确认采纳\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ADOPTED"));

    // 第二次决策 ⇒ 409。
    http.perform(
            post("/platform/ai/forecast/suggestions/" + id + "/reject")
                .header("Authorization", "session-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"note\":\"改主意了\"}"))
        .andExpect(status().isConflict());
  }

  // ================================================================ 价格弹性

  @Test
  void 价格弹性算得出来时给调价建议且算不出来时如实说算不出来() throws Exception {
    // 没有价格 ⇒ 弹性算不出来（上游的销量口径不带价格）。
    seedPlainSeries(tenantId());
    var view =
        json.readValue(
            http.perform(get("/platform/ai/forecast/price-elasticity").header("Authorization", "session-a"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            AiForecastPriceElasticityView.class);
    assertThat(view.items()).hasSize(1);
    assertThat(view.items().get(0).available()).isFalse();
    assertThat(view.items().get(0).elasticity()).isNull();
    assertThat(view.items().get(0).reason()).isNotBlank();

    // 有价格且有两次变动 ⇒ 弹性可估（换一个 SKU，避免与上一段的主键唯一约束撞车）。
    seedPriceSeries(tenantId(), "SKU-P");
    var priced =
        json.readValue(
            http.perform(get("/platform/ai/forecast/price-elasticity").header("Authorization", "session-a"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            AiForecastPriceElasticityView.class);
    var elasticity = priced.items().stream().filter(AiForecastElasticityRow::available).findFirst();
    assertThat(elasticity).as("有价格样本时弹性必须能算出来").isPresent();
    assertThat(elasticity.get().elasticity()).isNegative();
    assertThat(elasticity.get().samplePairs()).isGreaterThanOrEqualTo(2);
  }

  @Test
  void 调价建议只在弹性够大时给出且建议里写明不改价() throws Exception {
    seedPriceSeries(tenantId(), "SKU-A");
    runForecast();
    var price = suggestionPage("PRICE");
    assertThat(price.items()).hasSize(1);
    var suggestion = price.items().get(0);
    assertThat(suggestion.elasticity()).isNotNull();
    assertThat(suggestion.priceDeltaRate()).isEqualByComparingTo("-0.1");
    assertThat(suggestion.suggestedPrice()).isEqualByComparingTo("14.4");
    assertThat(suggestion.basis()).contains("不改价");
    assertThat(suggestion.status()).isEqualTo("SUGGESTED");
  }

  // ================================================================ 爆品 / 机会品

  @Test
  void 爆品识别给出阈值与统计窗口且没有基期时不算爆品() throws Exception {
    // 只有当天的销量 ⇒ 上一个等长窗口没有基期 ⇒ 不该判成爆品。
    seedHistory(
        tenantId(),
        "SKU-HOT",
        "爆款",
        1,
        list("500"),
        null);
    var view =
        json.readValue(
            http.perform(get("/platform/ai/forecast/hot-items").header("Authorization", "session-a"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            AiForecastHotItemView.class);
    assertThat(view.unitsThreshold()).isNotNull();
    assertThat(view.growthThreshold()).isNotNull();
    assertThat(view.windowDays()).isGreaterThan(0);
    var hot = view.items().stream().filter(i -> "SKU-HOT".equals(i.skuCode())).findFirst();
    assertThat(hot).isPresent();
    assertThat(hot.get().detected()).as("没有基期时不能算爆品").isFalse();
    assertThat(hot.get().reason()).contains("基期");
  }

  @Test
  void 机会品发现给出趋势比与原因() throws Exception {
    seedHistory(
        tenantId(),
        "SKU-UP",
        "上升款",
        14,
        list("1", "1", "1", "1", "1", "1", "1", "1", "1", "1", "5", "6", "7", "8"),
        null);
    var view =
        json.readValue(
            http.perform(get("/platform/ai/forecast/opportunities").header("Authorization", "session-a"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            AiForecastOpportunityView.class);
    assertThat(view.trendThreshold()).isNotNull();
    var up = view.items().stream().filter(i -> "SKU-UP".equals(i.skuCode())).findFirst();
    assertThat(up).isPresent();
    assertThat(up.get().trendRatio()).isNotNull();
    assertThat(up.get().basis()).contains("近 7 天日均");
  }

  // ================================================================ 分页与筛选

  @Test
  void 预测结果列表是真分页且能按sku筛选() throws Exception {
    seedPlainSeries(tenantId());
    seedHistory(tenantId(), "SKU-B", "测试款 B", 6, list("3", "4", "5", "6", "7", "8"), null);
    runForecast();

    var all =
        json.readValue(
            http.perform(get("/platform/ai/forecast/forecasts").header("Authorization", "session-a"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            AiForecastResultPage.class);
    assertThat(all.total()).isEqualTo(2);
    assertThat(all.items()).hasSize(2);

    var filtered =
        json.readValue(
            http.perform(
                    get("/platform/ai/forecast/forecasts")
                        .header("Authorization", "session-a")
                        .param("skuCode", "SKU-B"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            AiForecastResultPage.class);
    assertThat(filtered.total()).isEqualTo(1);
    assertThat(filtered.items().get(0).skuCode()).isEqualTo("SKU-B");

    var paged =
        json.readValue(
            http.perform(
                    get("/platform/ai/forecast/forecasts")
                        .header("Authorization", "session-a")
                        .param("page", "0")
                        .param("size", "1"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            AiForecastResultPage.class);
    assertThat(paged.items()).hasSize(1);
    assertThat(paged.total()).isEqualTo(2);
    assertThat(paged.totalPages()).isEqualTo(2);
  }

  // ================================================================ 工具

  AiForecastRunDetail runForecast() throws Exception {
    var body =
        """
        {"algorithm":"MOVING_AVERAGE","maWindowDays":3,"windowDays":30,"horizonDays":30,
         "confidenceLevel":0.95}
        """;
    return json.readValue(
        http.perform(
                post("/platform/ai/forecast/runs")
                    .header("Authorization", "session-a")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(),
        AiForecastRunDetail.class);
  }

  AiForecastSuggestionPage suggestionPage(String type) throws Exception {
    return json.readValue(
        http.perform(
                get("/platform/ai/forecast/replenish-suggestions")
                    .header("Authorization", "session-a")
                    .param("suggestionType", type))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(),
        AiForecastSuggestionPage.class);
  }
}
