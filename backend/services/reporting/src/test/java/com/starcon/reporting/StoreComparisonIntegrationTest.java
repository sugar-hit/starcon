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
 * 13.1.1.2 多店对比的**集成测试**（真实 PostgreSQL 16 + FORCE RLS，无内存替身）。
 *
 * <p>本文件验的是**业务口径**（多租户三条断言在 {@code StoreComparisonTenancyIntegrationTest}）：
 *
 * <ul>
 *   <li>增长指标（窗口环比）能真算出来 —— 这是上一轮中断留下的半成品的正主：
 *       {@code STORE_GMV_GROWTH} 的 {@code base_metric_code} 必须是 {@code STORE_GMV_PLATFORM}，
 *       {@code STORE_ORDER_GROWTH} 的必须是 {@code STORE_ORDER_COUNT}。base 为空 ⇒
 *       {@code definitions.get(null)} 得到 null ⇒ 增长恒为空；
 *   <li>口径差异额（平台侧 − 还原口径）能算出来 —— 蓝图的「跨平台对比要处理口径差异」；
 *   <li>「算不出来」与「等于 0」分开：上一等长窗口没有事实 ⇒ 不可比，不是 0%；
 *   <li>排名在**币种内**做；平台分组带口径说明；
 *   <li>重复刷新幂等；上游读不到就 503，不返回空集合。
 * </ul>
 *
 * <p>⚠️ **上游是真的 HTTP**：本测试起一个假的上游服务（channel + trade），驱动
 * {@code ReportingStoreComparisonRefreshService} 的**真实**拉取与投影路径 ——
 * 端口写错了路径 / 字段名，在这一层就会暴露（写错字段名 ⇒ 那一类指标恒为 0）。
 *
 * <p>⚠️ **连接池固定为 1 条连接**：只有复用同一条连接，才能真正证明归还后不会串租户。
 */
@SpringBootTest
@AutoConfigureMockMvc
class StoreComparisonIntegrationTest {

  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID tenant = UUID.randomUUID(), user = UUID.randomUUID();
  static final String BEARER = "Bearer tenant-a";

  static HttpServer identity;
  static HttpServer upstream;

  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  static final List<String> permissions =
      List.of("reporting.store-comparison.read", "reporting.projection.write");

  static final ObjectMapper JSON = JsonMapper.builder().addModule(new JavaTimeModule()).build();

  /** 当前窗口。 */
  static final LocalDate DAY1 = LocalDate.of(2026, 10, 1);
  static final LocalDate DAY2 = LocalDate.of(2026, 10, 2);
  /** 上一等长窗口（[DAY1, DAY2] 往前挪两天）。 */
  static final LocalDate PREV1 = LocalDate.of(2026, 9, 29);
  static final LocalDate PREV2 = LocalDate.of(2026, 9, 30);

  static final ZoneId ZONE = ZoneId.of("UTC");

  /** ⚠️ 上游故障开关：打开后假上游一律 500，用来证「读不到 ≠ 没有数据」。 */
  static volatile boolean upstreamDown = false;

  // ==========================================================================
  // 夹具数据
  // ==========================================================================

  /** 店铺清单（channel 的广告报表筛选项）。 */
  static final List<Map<String, Object>> SHOP_OPTIONS =
      List.of(option("shop-a", "亚马逊旗舰店"), option("shop-b", "TikTok 小店"));

  /** 平台清单。 */
  static final List<Map<String, Object>> PLATFORM_OPTIONS =
      List.of(option("AMAZON", "亚马逊"), option("TIKTOK", "TikTok"));

  /** 广告活动（**店铺归属平台的唯一来源**）。 */
  static final List<Map<String, Object>> CAMPAIGNS =
      List.of(
          campaign("shop-a", "亚马逊旗舰店", "AMAZON", "亚马逊"),
          campaign("shop-b", "TikTok 小店", "TIKTOK", "TikTok"));

  /**
   * 平台单。
   *
   * <p>⚠️ **两份成交金额都不同**（平台口径 vs 还原口径）—— 口径差异额就是它们的差。
   */
  static final List<Map<String, Object>> ORDERS =
      List.of(
          // 上一等长窗口：shop-a 两次各 100（合计 200），shop-b 两次各 200（合计 400）
          order("SO-A-0", "shop-a", "AMAZON", PREV1, "100.00", "100.00"),
          order("SO-A-0B", "shop-a", "AMAZON", PREV2, "100.00", "100.00"),
          order("SO-B-0", "shop-b", "TIKTOK", PREV1, "200.00", "200.00"),
          order("SO-B-0B", "shop-b", "TIKTOK", PREV2, "200.00", "200.00"),
          // 当前窗口：shop-a 合计 400（150+250），shop-b 合计 100
          order("SO-A-1", "shop-a", "AMAZON", DAY1, "150.00", "145.00"),
          order("SO-A-2", "shop-a", "AMAZON", DAY2, "250.00", "245.00"),
          order("SO-B-1", "shop-b", "TIKTOK", DAY1, "100.00", "100.00"));

  /** 店铺维度广告指标（按日；端口逐日拉，假上游按 from/to 过滤）。 */
  static final List<Map<String, Object>> ADS =
      List.of(
          ad(DAY1, "shop-a", "100.00", "500.00"),
          ad(DAY1, "shop-b", "200.00", "200.00"),
          ad(DAY2, "shop-a", "100.00", "500.00"),
          ad(DAY2, "shop-b", "200.00", "200.00"));

  /** 售后退货单（**没有店铺维度**，靠订单号回挂）。 */
  static final List<Map<String, Object>> AFTERSALES =
      List.of(
          aftersales("AS-A-1", "SO-A-1", "30.00", DAY1),
          aftersales("AS-B-1", "SO-B-1", "50.00", DAY1),
          // ⚠️ 挂不上店铺 ⇒ 不得计入任何店铺
          aftersales("AS-X-1", "NO-SUCH-ORDER", "999.00", DAY1));

  static {
    pg.start();
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      // 运行角色必须 NOBYPASSRLS，否则它会绕过策略，多租户断言全部变成空断言。
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
      actors.put(BEARER, actor(tenant));
      identity.start();

      upstream = HttpServer.create(new InetSocketAddress(0), 0);
      upstream.createContext("/channel/ad-reports/options", e -> respond(e, optionsBody()));
      upstream.createContext("/channel/ad-campaigns/campaigns", e -> respond(e, pageBody("items", CAMPAIGNS)));
      upstream.createContext("/channel/ad-reports/rows", e -> respond(e, adRowsBody(e)));
      upstream.createContext("/trade/platform-orders/orders", e -> respond(e, pageBody("rows", ORDERS)));
      upstream.createContext("/trade/aftersales/cases", e -> respond(e, pageBody("rows", AFTERSALES)));
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
    upstreamDown = false;
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate reporting_store_metric_fact, reporting_store_metric_definition,"
              + " reporting_store_profile cascade");
    }
  }

  // ==========================================================================
  // 业务口径
  // ==========================================================================

  @Test
  void 刷新把上游事实投影成店铺维度() {
    var results = refreshAll();
    assertThat(results).as("四路上游都要有结果").hasSize(4);
    assertThat(results).allSatisfy(r -> assertThat(r.status()).isEqualTo("SUCCEEDED"));
    assertThat(factCount()).as("事实行必须落库").isPositive();
    assertThat(profileCount()).as("店铺档案必须落库").isEqualTo(2);
  }

  @Test
  void 增长指标按被比较指标算出环比增长率() {
    refreshAll();
    var response = comparison.comparison(tenant, DAY1, DAY2, null, null);

    // shop-a：当前 400，上一窗口 200 ⇒ +100%
    assertThat(value(response, "shop-a", ReportingStoreComparisonCatalog.STORE_GMV_GROWTH, "USD"))
        .as("成交金额环比增长率必须按 base 指标（平台侧成交金额）算出来")
        .isEqualByComparingTo("100.0000");
    // shop-b：当前 100，上一窗口 400 ⇒ -75%
    assertThat(value(response, "shop-b", ReportingStoreComparisonCatalog.STORE_GMV_GROWTH, "USD"))
        .isEqualByComparingTo("-75.0000");
  }

  @Test
  void 订单量增长也有自己的被比较指标() {
    refreshAll();
    var response = comparison.comparison(tenant, DAY1, DAY2, null, null);
    // shop-a：订单 2 vs 2 ⇒ 0%；shop-b：1 vs 2 ⇒ -50%
    assertThat(value(response, "shop-a", ReportingStoreComparisonCatalog.STORE_ORDER_GROWTH, "USD"))
        .as("订单量环比必须比订单量自己，不是比成交金额")
        .isEqualByComparingTo("0.0000");
    assertThat(value(response, "shop-b", ReportingStoreComparisonCatalog.STORE_ORDER_GROWTH, "USD"))
        .isEqualByComparingTo("-50.0000");
  }

  @Test
  void 增长店铺与掉队店铺被分出来() {
    refreshAll();
    var response = comparison.comparison(tenant, DAY1, DAY2, null, null);

    assertThat(response.growthStores()).extracting(ReportingStoreComparisonModels.StoreFlag::shopCode)
        .contains("shop-a");
    assertThat(response.laggards()).extracting(ReportingStoreComparisonModels.StoreFlag::shopCode)
        .contains("shop-b");
    assertThat(row(response, "shop-a").classification()).isEqualTo("GROWTH");
    assertThat(row(response, "shop-b").classification()).isEqualTo("LAGGARD");
    assertThat(row(response, "shop-a").classificationNote()).contains("+20%");
  }

  @Test
  void 口径差异额反映平台口径与还原口径之差() {
    refreshAll();
    var response = comparison.comparison(tenant, DAY1, DAY2, null, null);
    // shop-a 当前窗口：(150-145) + (250-245) = 10
    assertThat(value(response, "shop-a", ReportingStoreComparisonCatalog.STORE_CALIBER_GAP, "USD"))
        .as("口径差异额 = 平台侧成交金额 − 还原口径成交金额")
        .isEqualByComparingTo("10.0000");
    // shop-b 两边口径一致 ⇒ 差异额为 0（**真的等于 0**，不是算不出来）
    assertThat(value(response, "shop-b", ReportingStoreComparisonCatalog.STORE_CALIBER_GAP, "USD"))
        .isEqualByComparingTo("0.0000");
  }

  @Test
  void 跨平台对比必须标注口径说明() {
    refreshAll();
    var response = comparison.comparison(tenant, DAY1, DAY2, null, null);

    assertThat(response.platformGroups()).as("两个平台 ⇒ 两个分组").hasSize(2);
    assertThat(response.platformGroups())
        .allSatisfy(g -> assertThat(g.caliberNote()).as("每个分组都要有口径说明").isNotBlank());
    assertThat(response.caliberNotes()).as("跨平台口径说明不得为空").isNotEmpty();
    assertThat(String.join("", response.caliberNotes())).contains("口径");
  }

  @Test
  void 排名在币种内做且规模排名按成交金额() {
    refreshAll();
    var response = comparison.comparison(tenant, DAY1, DAY2, null, null);

    assertThat(row(response, "shop-a").scaleRank()).as("400 > 100 ⇒ shop-a 第一").isEqualTo(1);
    assertThat(row(response, "shop-b").scaleRank()).isEqualTo(2);

    var gmvRanking =
        response.rankings().stream()
            .filter(r -> r.metricCode().equals(ReportingStoreComparisonCatalog.STORE_GMV_PLATFORM))
            .findFirst()
            .orElseThrow();
    assertThat(gmvRanking.entries()).hasSize(2);
    assertThat(gmvRanking.entries().get(0).shopCode()).isEqualTo("shop-a");
    assertThat(gmvRanking.entries().get(0).rank()).isEqualTo(1);
  }

  @Test
  void 派生指标按分子分母算_不写死公式() {
    refreshAll();
    var response = comparison.comparison(tenant, DAY1, DAY2, null, null);

    // 客单价 = 平台侧成交金额 ÷ 成交订单量 = 400 ÷ 2
    assertThat(value(response, "shop-a", ReportingStoreComparisonCatalog.STORE_GMV_PER_ORDER, "USD"))
        .isEqualByComparingTo("200.0000");
    // 广告产出比 = 广告销售额 ÷ 广告花费 = 1000 ÷ 200
    assertThat(value(response, "shop-a", ReportingStoreComparisonCatalog.STORE_AD_ROAS, "USD"))
        .isEqualByComparingTo("5.0000");
    // 广告花费占比 = 200 ÷ 1000 = 20%
    assertThat(value(response, "shop-a", ReportingStoreComparisonCatalog.STORE_AD_ACOS, "USD"))
        .isEqualByComparingTo("20.0000");
    // 退货率 = 退货额 ÷ 平台侧成交金额 = 30 ÷ 400 = 7.5%
    assertThat(value(response, "shop-a", ReportingStoreComparisonCatalog.STORE_RETURN_RATE, "USD"))
        .isEqualByComparingTo("7.5000");
  }

  @Test
  void 挂不上店铺的退货单不计入任何店铺() {
    refreshAll();
    var response = comparison.comparison(tenant, DAY1, DAY2, null, null);

    assertThat(value(response, "shop-a", ReportingStoreComparisonCatalog.STORE_RETURN_AMOUNT, "USD"))
        .isEqualByComparingTo("30.0000");
    assertThat(value(response, "shop-b", ReportingStoreComparisonCatalog.STORE_RETURN_AMOUNT, "USD"))
        .isEqualByComparingTo("50.0000");
    assertThat(value(response, "shop-a", ReportingStoreComparisonCatalog.STORE_RETURN_AMOUNT, "USD"))
        .as("挂不上的 999 不得落到任何店铺")
        .isLessThan(new BigDecimal("999.00"));
  }

  @Test
  void 重复刷新幂等_事实不翻倍() {
    refreshAll();
    var after1 = factCount();
    refreshAll();
    assertThat(factCount()).as("重复刷新是覆盖不是翻倍").isEqualTo(after1);
  }

  @Test
  void 空态报空而不是报0() {
    // 没有刷新 ⇒ 没有任何店铺档案
    var response = comparison.comparison(tenant, DAY1, DAY2, null, null);
    assertThat(response.rows()).isEmpty();
    assertThat(response.status()).isEqualTo("NOT_PROJECTED");
    assertThat(response.emptyReason()).isEqualTo("NO_STORES");
    assertThat(response.emptyNote()).isNotBlank();
  }

  @Test
  void 上一窗口没有事实的店铺报不可比而不是0() {
    // 只给 shop-a 投影当前窗口（上一窗口没有事实）
    seedFact("shop-a", ReportingStoreComparisonCatalog.STORE_GMV_PLATFORM, DAY1, "USD", "400.00");
    seedFact("shop-a", ReportingStoreComparisonCatalog.STORE_ORDER_COUNT, DAY1, "USD", "2");
    seedProfile("shop-a", "AMAZON", "亚马逊");

    var response = comparison.comparison(tenant, DAY1, DAY2, null, null);
    assertThat(row(response, "shop-a").classification()).as("算不出环比 ⇒ 不可比").isEqualTo("UNCLASSIFIED");
    assertThat(row(response, "shop-a").growthRank()).as("不可比就没有排名").isNull();
    assertThat(
            value(
                response,
                "shop-a",
                ReportingStoreComparisonCatalog.STORE_GMV_GROWTH,
                "USD"))
        .as("算不出来必须返回 null，不能用 0% 冒充")
        .isNull();
  }

  @Test
  void 上游读不到就失败_不返回空集合() {
    upstreamDown = true;
    assertThatThrownBy(() -> refreshAll())
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
        .satisfies(e -> assertThat(((org.springframework.web.server.ResponseStatusException) e).getStatusCode().value())
            .as("上游读不到必须 503")
            .isEqualTo(503));
  }

  @Test
  void HTTP端点返回对比矩阵() throws Exception {
    refreshAll();
    http.perform(
            get("/reporting/store-comparison")
                .header("Authorization", BEARER)
                .param("from", DAY1.toString())
                .param("to", DAY2.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rows").isArray())
        .andExpect(jsonPath("$.rows.length()").value(2))
        .andExpect(jsonPath("$.rows[0].shopCode").value("shop-a"))
        .andExpect(jsonPath("$.metricDefinitions[?(@.aggregation=='WINDOW_GROWTH')].baseMetricCode")
            .isNotEmpty())
        .andExpect(jsonPath("$.caliberNotes").isNotEmpty());
  }

  @Test
  void 指标口径目录给出被比较指标() {
    var definitions = comparison.metricDefinitions(tenant);
    var growth =
        definitions.stream()
            .filter(d -> d.metricCode().equals(ReportingStoreComparisonCatalog.STORE_GMV_GROWTH))
            .findFirst()
            .orElseThrow();
    assertThat(growth.baseMetricCode())
        .as("增长指标必须声明它比的是哪个指标")
        .isEqualTo(ReportingStoreComparisonCatalog.STORE_GMV_PLATFORM);

    var orderGrowth =
        definitions.stream()
            .filter(d -> d.metricCode().equals(ReportingStoreComparisonCatalog.STORE_ORDER_GROWTH))
            .findFirst()
            .orElseThrow();
    assertThat(orderGrowth.baseMetricCode())
        .isEqualTo(ReportingStoreComparisonCatalog.STORE_ORDER_COUNT);
  }

  // ==========================================================================
  // 工具
  // ==========================================================================

  private List<ReportingStoreComparisonModels.StoreRefreshResult> refreshAll() {
    return refresh.refresh(tenant, BEARER, DAY1, DAY2, ZONE);
  }

  private int factCount() {
    return tenants.run(tenant, () -> sql.fetch("select id from reporting_store_metric_fact").size());
  }

  private int profileCount() {
    return tenants.run(tenant, () -> sql.fetch("select id from reporting_store_profile").size());
  }

  private void seedProfile(String shopCode, String platformCode, String platformName) {
    tenants.run(
        tenant,
        () ->
            sql.execute(
                """
                insert into reporting_store_profile
                  (id,tenant_id,shop_code,shop_name,platform_code,platform_name,
                   source_service,source_reference,created_at,updated_at)
                values (?,?,?,?,?,?,'test','seed:' || ?,now(),now())
                """,
                java.util.UUID.randomUUID(),
                tenant,
                shopCode,
                shopCode,
                platformCode,
                platformName,
                shopCode));
  }

  private void seedFact(
      String shopCode, String metricCode, LocalDate day, String currency, String value) {
    tenants.run(
        tenant,
        () -> {
          // 口径必须先登记（DB 触发器钉死：事实行的指标码必须已登记）。
          // ⚠️ 播种也必须在**租户事务内**做：口径表同样受 RLS 保护，
          // 事务外插不进去（这正是「租户不由客户端决定」在数据层的表现）。
          refresh.seedDefinitions(tenant);
          sql.execute(
              """
              insert into reporting_store_metric_fact
                (id,tenant_id,shop_code,metric_code,bucket_date,currency,value,quantity,
                 timezone,source_service,source_reference,updated_at)
              values (?,?,?,?,?,?,?,0,'UTC','test',?,now())
              """,
              java.util.UUID.randomUUID(),
              tenant,
              shopCode,
              metricCode,
              day,
              currency,
              new BigDecimal(value),
              "seed:" + shopCode + ":" + metricCode + ":" + day);
          return null;
        });
  }

  private static ReportingStoreComparisonModels.StoreComparisonRow row(
      ReportingStoreComparisonModels.ComparisonResponse response, String shopCode) {
    return response.rows().stream()
        .filter(r -> r.shopCode().equals(shopCode))
        .findFirst()
        .orElseThrow(() -> new AssertionError("矩阵里没有店铺 " + shopCode));
  }

  /** 取某个店铺某个指标某个币种的取值（可能为 null = 算不出来）。 */
  private static BigDecimal value(
      ReportingStoreComparisonModels.ComparisonResponse response,
      String shopCode,
      String metricCode,
      String currency) {
    return row(response, shopCode).metrics().stream()
        .filter(m -> m.metricCode().equals(metricCode) && m.currency().equals(currency))
        .findFirst()
        .orElseThrow(() -> new AssertionError("矩阵里没有指标 " + metricCode))
        .value();
  }

  // ---------------------------------------------------------------------------
  // 假上游
  // ---------------------------------------------------------------------------

  private static void respond(com.sun.net.httpserver.HttpExchange e, Object body) {
    try {
      if (upstreamDown) {
        e.sendResponseHeaders(500, -1);
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

  private static Map<String, Object> optionsBody() {
    var body = new LinkedHashMap<String, Object>();
    body.put("shops", SHOP_OPTIONS);
    body.put("platforms", PLATFORM_OPTIONS);
    return body;
  }

  private static Map<String, Object> pageBody(String field, List<Map<String, Object>> rows) {
    var body = new LinkedHashMap<String, Object>();
    body.put(field, rows);
    body.put("page", 0);
    body.put("size", 200);
    body.put("total", rows.size());
    return body;
  }

  /** 店铺维度广告指标：按 from/to 过滤（端口逐日拉，from = to = 某一天）。 */
  private static Map<String, Object> adRowsBody(com.sun.net.httpserver.HttpExchange e) {
    var query = query(e.getRequestURI().getRawQuery());
    var from = query.get("from");
    var to = query.get("to");
    var matched = new ArrayList<Map<String, Object>>();
    for (var row : ADS) {
      var day = String.valueOf(row.get("metricFrom"));
      if (from != null && !from.equals(day)) continue;
      if (to != null && !to.equals(day)) continue;
      matched.add(row);
    }
    return pageBody("items", matched);
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
      String orderNo,
      String shopCode,
      String platformCode,
      LocalDate day,
      String platformAmount,
      String restoredAmount) {
    var m = new LinkedHashMap<String, Object>();
    m.put("id", UUID.nameUUIDFromBytes(orderNo.getBytes(StandardCharsets.UTF_8)).toString());
    m.put("orderNo", orderNo);
    m.put("externalOrderNo", "EXT-" + orderNo);
    m.put("platformCode", platformCode);
    m.put("shopCode", shopCode);
    m.put("currency", "USD");
    m.put("platformGrandTotalAmount", new BigDecimal(platformAmount));
    m.put("restoredGrandTotalAmount", new BigDecimal(restoredAmount));
    m.put("platformPlacedAt", day.atTime(10, 0).atOffset(ZoneOffset.UTC).toString());
    m.put("syncedAt", day.atTime(10, 5).atOffset(ZoneOffset.UTC).toString());
    return m;
  }

  private static Map<String, Object> ad(
      LocalDate day, String shopCode, String spend, String sales) {
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
