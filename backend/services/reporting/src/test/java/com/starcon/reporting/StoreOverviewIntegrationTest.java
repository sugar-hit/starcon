package com.starcon.reporting;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.starcon.tenancy.Ids;
import com.starcon.tenancy.TenantTransactions;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jooq.DSLContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 1.1.2.2 店铺概览的**集成测试**（真实 PostgreSQL 16 + FORCE RLS，无内存替身）。
 *
 * <p>本文件验的是**本模块最容易做错的地方**：
 *
 * <ol>
 *   <li><b>五个维度逐条落地</b>：销售额 / 订单量 / 利润率 / 库存天数 / 广告花费
 *       —— 蓝图点名的五个，一个都不少；</li>
 *   <li><b>「销售额」是三个口径的并列</b>（平台侧成交金额 / 还原口径成交金额 / 口径差异额），
 *       只给一个就是抹掉口径差异；</li>
 *   <li><b>接不通的维度如实标 {@code available=false} + 归属模块，
 *       不给公式也不给分母</b>；</li>
 *   <li><b>「算不出来」与「等于 0」分开</b>：没有广告事实的店铺那一格是 {@code NO_DATA} + null，
 *       **不是 0**；</li>
 *   <li><b>掉队店铺被识别</b>，判定规则与 13.1.1.2 同源；</li>
 *   <li><b>工作台只读</b>：取数前后事实行数不变（卷 01 卷首红线）。</li>
 * </ol>
 *
 * <p>⚠️ **固定时钟**：窗口边界按本租户时区算，「今天是哪一天」取决于时钟 ——
 * 不钉死就只能撞「正好跨过午夜」那个窗口。
 */
@SpringBootTest
@AutoConfigureMockMvc
class StoreOverviewIntegrationTest {

  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID tenant = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  static final String BEARER = "Bearer tenant-a";

  static final List<String> permissions =
      List.of(
          "reporting.overview.read",
          "reporting.projection.read",
          "reporting.projection.write",
          "reporting.store-comparison.read");

  static final ObjectMapper JSON = JsonMapper.builder().addModule(new JavaTimeModule()).build();

  /**
   * 固定时钟。
   *
   * <p>⚠️ 这个时刻是**故意挑的**：UTC 是 2026-10-25，而 Asia/Shanghai 已经是 2026-10-26。
   * 于是「窗口边界按租户时区算」这件事可以被**确定性**地断言 —— 实现里写死 UTC 会直接红。
   */
  static final Instant FIXED_NOW = Instant.parse("2026-10-25T20:00:00Z");

  static final String TENANT_ZONE = "Asia/Shanghai";

  /** 当期窗口。 */
  static final LocalDate DAY1 = LocalDate.of(2026, 10, 1);

  static final LocalDate DAY2 = LocalDate.of(2026, 10, 2);

  /** 上一等长窗口（{@code previousWindow(DAY1, DAY2)}）。 */
  static final LocalDate PREV1 = LocalDate.of(2026, 9, 29);

  static final LocalDate PREV2 = LocalDate.of(2026, 9, 30);

  /** 固定时钟下的「今日」（按租户时区）。 */
  static final LocalDate TODAY = LocalDate.of(2026, 10, 26);

  static {
    pg.start();
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      // 运行角色必须 NOBYPASSRLS，否则它会绕过策略，隔离断言全部变成空断言。
      s.execute("create role starcon_app nologin");
      s.execute("create role reporting_app login password 'test-only' nosuperuser nobypassrls");
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
    // 连接池固定为 1 条：只有复用同一条连接，才能真正证明归还后不会串租户。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  /** 固定时钟（覆盖生产用的系统时钟）。 */
  @TestConfiguration
  static class FixedClock {
    @Bean
    @Primary
    Clock fixedClock() {
      return Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
    }
  }

  @Autowired TenantTransactions tenants;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;
  @Autowired ReportingStoreComparisonRefreshService refresh;
  @Autowired StoreOverviewService overview;

  @AfterAll
  static void stop() {
    identity.stop(0);
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
  // 夹具
  // ==========================================================================

  /** 店铺档案。 */
  private void profile(String shopCode, String shopName, String platformCode, String platformName) {
    tenants.run(
        tenant,
        () -> {
          sql.execute(
              """
              insert into reporting_store_profile
                (id,tenant_id,shop_code,shop_name,platform_code,platform_name,
                 source_service,source_reference,created_at,updated_at)
              values (?,?,?,?,?,?,'channel',?,now(),now())
              on conflict (tenant_id, shop_code) do update
                set shop_name = excluded.shop_name,
                    platform_code = excluded.platform_code,
                    platform_name = excluded.platform_name,
                    updated_at = now()
              """,
              Ids.next(),
              tenant,
              shopCode,
              shopName,
              platformCode,
              platformName,
              "channel:shops:" + shopCode);
          return null;
        });
  }

  /** 一条店铺维度事实。 */
  private void fact(
      String shopCode,
      String metricCode,
      LocalDate bucketDate,
      String currency,
      String value,
      String sourceReference) {
    tenants.run(
        tenant,
        () -> {
          sql.execute(
              """
              insert into reporting_store_metric_fact
                (id,tenant_id,shop_code,metric_code,bucket_date,currency,value,quantity,
                 timezone,source_service,source_reference,updated_at)
              values (?,?,?,?,?,?,?,0,?,'test',?,now())
              on conflict (tenant_id, shop_code, metric_code, bucket_date, currency,
                           source_service, source_reference)
              do update set value = excluded.value, updated_at = now()
              """,
              Ids.next(),
              tenant,
              shopCode,
              metricCode,
              bucketDate,
              currency,
              new BigDecimal(value),
              TENANT_ZONE,
              sourceReference);
          return null;
        });
  }

  /**
   * 完整夹具：两个店铺、两个平台。
   *
   * <p>shop-a（Amazon）：当期成交金额 400（平台侧）/ 390（还原口径）⇒ 口径差异额 10；
   * 上一等长窗口 200 ⇒ 环比 +100% ⇒ <b>增长店铺</b>；订单 2 笔；广告花费 200。
   *
   * <p>shop-b（TikTok Shop）：当期成交金额 100（两侧口径一致 ⇒ 差异额 <b>真的等于 0</b>）；
   * 上一等长窗口 400 ⇒ 环比 −75% ⇒ <b>掉队店铺</b>；订单 1 笔；**没有广告事实**
   * ⇒ 广告花费那一格必须是「无数据」而不是 0。
   */
  private void seed() {
    // ⚠️ 口径播种必须**在租户事务里**做：口径表是 FORCE RLS 的，未绑定租户时写入被策略拒绝。
    tenants.run(
        tenant,
        () -> {
          refresh.seedDefinitions(tenant);
          return null;
        });
    profile("shop-a", "亚马逊美西店", "AMZ", "Amazon");
    profile("shop-b", "TikTok 美国店", "TT", "TikTok Shop");

    // shop-a：当期
    fact("shop-a", ReportingStoreComparisonCatalog.STORE_GMV_PLATFORM, DAY1, "USD", "150", "a-1");
    fact("shop-a", ReportingStoreComparisonCatalog.STORE_GMV_PLATFORM, DAY2, "USD", "250", "a-2");
    fact("shop-a", ReportingStoreComparisonCatalog.STORE_GMV_RESTORED, DAY1, "USD", "145", "a-1");
    fact("shop-a", ReportingStoreComparisonCatalog.STORE_GMV_RESTORED, DAY2, "USD", "245", "a-2");
    fact("shop-a", ReportingStoreComparisonCatalog.STORE_ORDER_COUNT, DAY1, "USD", "1", "a-1");
    fact("shop-a", ReportingStoreComparisonCatalog.STORE_ORDER_COUNT, DAY2, "USD", "1", "a-2");
    fact("shop-a", ReportingStoreComparisonCatalog.STORE_AD_SPEND, DAY1, "USD", "100", "a-1");
    fact("shop-a", ReportingStoreComparisonCatalog.STORE_AD_SPEND, DAY2, "USD", "100", "a-2");
    // shop-a：上一等长窗口（环比的分母）
    fact("shop-a", ReportingStoreComparisonCatalog.STORE_GMV_PLATFORM, PREV1, "USD", "100", "pa-1");
    fact("shop-a", ReportingStoreComparisonCatalog.STORE_GMV_PLATFORM, PREV2, "USD", "100", "pa-2");

    // shop-b：当期（两侧口径一致 ⇒ 差异额真的等于 0）
    fact("shop-b", ReportingStoreComparisonCatalog.STORE_GMV_PLATFORM, DAY1, "USD", "60", "b-1");
    fact("shop-b", ReportingStoreComparisonCatalog.STORE_GMV_PLATFORM, DAY2, "USD", "40", "b-2");
    fact("shop-b", ReportingStoreComparisonCatalog.STORE_GMV_RESTORED, DAY1, "USD", "60", "b-1");
    fact("shop-b", ReportingStoreComparisonCatalog.STORE_GMV_RESTORED, DAY2, "USD", "40", "b-2");
    fact("shop-b", ReportingStoreComparisonCatalog.STORE_ORDER_COUNT, DAY1, "USD", "1", "b-1");
    // ⚠️ shop-b **没有广告事实** —— 广告花费那一格必须是「无数据」，不是 0。
    // shop-b：上一等长窗口（环比的分母）
    fact("shop-b", ReportingStoreComparisonCatalog.STORE_GMV_PLATFORM, PREV1, "USD", "200", "pb-1");
    fact("shop-b", ReportingStoreComparisonCatalog.STORE_GMV_PLATFORM, PREV2, "USD", "200", "pb-2");
  }

  // ==========================================================================
  // 能力声明
  // ==========================================================================

  @Test
  void 能力声明逐条报出五个维度_两个接不通的维度标available_false且不给分母() throws Exception {
    seed();
    http.perform(get("/reporting/store-overview/capabilities").header("Authorization", BEARER))
        .andExpect(status().isOk())
        // 蓝图点名的五个维度，一个都不少。
        .andExpect(jsonPath("$.dimensions.length()").value(5))
        // 销售额：**三个口径并列**（不是单一口径）
        .andExpect(jsonPath("$.dimensions[?(@.key=='store.sales-amount')].available").value(true))
        .andExpect(jsonPath("$.dimensions[?(@.key=='store.sales-amount')].calibers.length()").value(3))
        .andExpect(jsonPath("$.dimensions[?(@.key=='store.sales-amount')].calibers[?(@.metricCode=='STORE_GMV_PLATFORM')].registered").value(true))
        .andExpect(jsonPath("$.dimensions[?(@.key=='store.sales-amount')].calibers[?(@.metricCode=='STORE_GMV_RESTORED')].registered").value(true))
        .andExpect(jsonPath("$.dimensions[?(@.key=='store.sales-amount')].calibers[?(@.metricCode=='STORE_CALIBER_GAP')].registered").value(true))
        // 订单量
        .andExpect(jsonPath("$.dimensions[?(@.key=='store.order-count')].available").value(true))
        .andExpect(jsonPath("$.dimensions[?(@.key=='store.order-count')].calibers[0].metricCode").value("STORE_ORDER_COUNT"))
        // 利润率：**未接通** ⇒ available=false + 归属模块 + 原因
        .andExpect(jsonPath("$.dimensions[?(@.key=='store.profit-margin')].available").value(false))
        .andExpect(jsonPath("$.dimensions[?(@.key=='store.profit-margin')].ownerModule")
            .value(org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.containsString("13.2"))))
        .andExpect(jsonPath("$.dimensions[?(@.key=='store.profit-margin')].calibers.length()").value(0))
        .andExpect(jsonPath("$.dimensions[?(@.key=='store.profit-margin')].unavailableReason")
            .value(org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.containsString("口径"))))
        // 库存天数：**未接通**
        .andExpect(jsonPath("$.dimensions[?(@.key=='store.inventory-days')].available").value(false))
        .andExpect(jsonPath("$.dimensions[?(@.key=='store.inventory-days')].ownerModule")
            .value(org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.containsString("7.1.1.1"))))
        .andExpect(jsonPath("$.dimensions[?(@.key=='store.inventory-days')].calibers.length()").value(0))
        // 广告花费：接得通（口径 STORE_AD_SPEND 已在 13.1.1.2 的口径表里登记）
        .andExpect(jsonPath("$.dimensions[?(@.key=='store.ad-spend')].available").value(true))
        .andExpect(jsonPath("$.dimensions[?(@.key=='store.ad-spend')].calibers[0].metricCode").value("STORE_AD_SPEND"))
        // 未接通清单逐条列出（不是一句「都接通了」）
        .andExpect(jsonPath("$.notDelivered.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(4)))
        // 判定规则里必须有掉队店铺这一条
        .andExpect(jsonPath("$.rules[?(@.ruleCode=='LAGGARD')].description")
            .value(org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.containsString("−20%"))));

    // 🔴 未接通的维度**不给公式也不给分母**（口径码一个都不给）。
    for (var dimension : overview.capabilities(tenant).dimensions()) {
      if (dimension.available()) continue;
      assertThat(dimension.calibers()).as("未接通 ⇒ 口径码一个都不给").isEmpty();
      assertThat(dimension.unavailableReason()).as("未接通 ⇒ 必须写明原因").isNotBlank();
      assertThat(dimension.ownerModule()).as("未接通 ⇒ 必须写明归谁").isNotBlank();
    }
  }

  // ==========================================================================
  // 五个维度
  // ==========================================================================

  @Test
  void 销售额并列给出两份成交金额口径与口径差异额() {
    seed();
    var response = overview.overview(tenant, "CUSTOM", DAY1, DAY2, null);

    var sales = dimension(row(response, "shop-a"), "store.sales-amount");
    assertThat(sales.available()).as("销售额维度接通").isTrue();
    assertThat(sales.values()).as("三个口径码并列").hasSize(3);
    assertThat(value(sales, ReportingStoreComparisonCatalog.STORE_GMV_PLATFORM, "USD"))
        .as("平台侧成交金额 = 150 + 250")
        .isEqualByComparingTo("400.0000");
    assertThat(value(sales, ReportingStoreComparisonCatalog.STORE_GMV_RESTORED, "USD"))
        .as("还原口径成交金额 = 145 + 245")
        .isEqualByComparingTo("390.0000");
    assertThat(value(sales, ReportingStoreComparisonCatalog.STORE_CALIBER_GAP, "USD"))
        .as("口径差异额 = 平台侧 − 还原口径")
        .isEqualByComparingTo("10.0000");
  }

  @Test
  void 两个口径一致时口径差异额真的等于零而不是无数据() {
    seed();
    var response = overview.overview(tenant, "CUSTOM", DAY1, DAY2, null);

    var sales = dimension(row(response, "shop-b"), "store.sales-amount");
    assertThat(value(sales, ReportingStoreComparisonCatalog.STORE_CALIBER_GAP, "USD"))
        .as("两侧口径一致 ⇒ 差异额真的等于 0")
        .isEqualByComparingTo("0.0000");
    assertThat(metric(sales, ReportingStoreComparisonCatalog.STORE_CALIBER_GAP, "USD").availability())
        .isEqualTo("AVAILABLE");
  }

  @Test
  void 订单量与广告花费按店铺给出_没有广告事实的店铺是无数据而不是零() {
    seed();
    var response = overview.overview(tenant, "CUSTOM", DAY1, DAY2, null);

    var ordersA = dimension(row(response, "shop-a"), "store.order-count");
    assertThat(value(ordersA, ReportingStoreComparisonCatalog.STORE_ORDER_COUNT, "USD"))
        .isEqualByComparingTo("2.0000");

    var adsA = dimension(row(response, "shop-a"), "store.ad-spend");
    assertThat(value(adsA, ReportingStoreComparisonCatalog.STORE_AD_SPEND, "USD"))
        .as("广告花费 = 100 + 100")
        .isEqualByComparingTo("200.0000");

    // ⚠️ shop-b 没有广告事实 ⇒ **无数据**，不是 0。
    var adsB = dimension(row(response, "shop-b"), "store.ad-spend");
    assertThat(adsB.status()).isEqualTo("NO_DATA");
    var adValue = metric(adsB, ReportingStoreComparisonCatalog.STORE_AD_SPEND, "USD");
    assertThat(adValue.value()).as("没有事实 ≠ 0").isNull();
    assertThat(adValue.availability()).isEqualTo("NO_DATA");
    assertThat(adValue.availabilityNote()).as("没有值必须写明原因").isNotBlank();
  }

  @Test
  void 未接通的维度不给出任何取值且写明归谁() {
    seed();
    var response = overview.overview(tenant, "CUSTOM", DAY1, DAY2, null);

    for (var key : List.of("store.profit-margin", "store.inventory-days")) {
      var dimension = dimension(row(response, "shop-a"), key);
      assertThat(dimension.available()).as("%s 未接通", key).isFalse();
      assertThat(dimension.status()).isEqualTo("NOT_CONNECTED");
      assertThat(dimension.values()).as("%s 不得给出任何取值", key).isEmpty();
      assertThat(dimension.ownerModule()).as("%s 必须写明归谁", key).isNotBlank();
      assertThat(dimension.statusNote()).as("%s 必须写明原因", key).isNotBlank();
    }
  }

  // ==========================================================================
  // 掉队店铺 / 空态 / 只读
  // ==========================================================================

  @Test
  void 掉队店铺被识别出来_判定规则与13一致() {
    seed();
    var response = overview.overview(tenant, "CUSTOM", DAY1, DAY2, null);

    assertThat(response.laggards()).extracting(StoreOverviewModels.StoreOverviewLaggard::shopCode)
        .contains("shop-b");
    assertThat(row(response, "shop-b").classification()).isEqualTo("LAGGARD");
    assertThat(row(response, "shop-a").classification()).isEqualTo("GROWTH");
    assertThat(row(response, "shop-b").classificationNote()).isNotBlank();
  }

  @Test
  void 空态区分没有店铺档案与没有投影事实() {
    // 什么都没有 ⇒ 没有店铺档案
    var empty = overview.overview(tenant, "CUSTOM", DAY1, DAY2, null);
    assertThat(empty.rows()).isEmpty();
    assertThat(empty.emptyReason()).isEqualTo("NO_STORES");
    assertThat(empty.status()).isEqualTo("NOT_PROJECTED");
    assertThat(empty.emptyNote()).isNotBlank();

    // 有店铺档案但这个窗口没有事实 ⇒ 未投影
    profile("shop-c", "空店", "AMZ", "Amazon");
    var notProjected = overview.overview(tenant, "CUSTOM", DAY1, DAY2, null);
    assertThat(notProjected.rows()).hasSize(1);
    assertThat(notProjected.emptyReason()).isEqualTo("NOT_PROJECTED");
    assertThat(notProjected.rows().get(0).hasFactsInWindow()).isFalse();
    // 没有事实的店铺，每个维度都是「无数据」而不是 0。
    assertThat(dimension(notProjected.rows().get(0), "store.sales-amount").status())
        .isEqualTo("NO_DATA");
  }

  @Test
  void 工作台只读_取数不写任何事实() {
    seed();
    var before = factCount();
    overview.overview(tenant, "CUSTOM", DAY1, DAY2, null);
    overview.capabilities(tenant);
    assertThat(factCount()).as("卷 01 红线：工作台只读 + 跳转").isEqualTo(before);
  }

  @Test
  void 窗口边界按租户时区算_不写死UTC() {
    seed();
    var response = overview.overview(tenant, "TODAY", null, null, null);
    // 固定时钟 2026-10-25T20:00Z ⇒ UTC 是 10-25，Asia/Shanghai 已经是 10-26。
    assertThat(response.asOfDate()).as("按租户时区算的今天").isEqualTo(TODAY);
    assertThat(response.timezone()).isEqualTo(TENANT_ZONE);
    assertThat(response.timezoneSource()).isEqualTo("PROJECTION");
    assertThat(response.from()).isEqualTo(TODAY);
    assertThat(response.to()).isEqualTo(TODAY);
  }

  @Test
  void 租户不来自任何请求头() throws Exception {
    seed();
    // 没带 Authorization ⇒ 401（不是把头里的租户号当权威）。
    http.perform(get("/reporting/store-overview")).andExpect(status().isUnauthorized());
    // 带伪造的 X-Tenant-Id ⇒ 仍然是会话租户的数据。
    http.perform(
            get("/reporting/store-overview")
                .param("window", "CUSTOM")
                .param("from", DAY1.toString())
                .param("to", DAY2.toString())
                .header("Authorization", BEARER)
                .header("X-Tenant-Id", UUID.randomUUID().toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rows.length()").value(2))
        .andExpect(jsonPath("$.rows[0].shopCode").value("shop-a"));
  }

  @Test
  void 可见店铺集合按窗口判定有没有事实() {
    seed();
    var shops = overview.visibleStores(tenant, DAY1, DAY2, null);
    assertThat(shops).hasSize(2);
    assertThat(shops).allSatisfy(shop -> assertThat(shop.hasFactsInWindow()).isTrue());
    // 另一个窗口没有事实。
    var other = overview.visibleStores(tenant, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 2), null);
    assertThat(other).hasSize(2);
    assertThat(other).allSatisfy(shop -> assertThat(shop.hasFactsInWindow()).isFalse());
    assertThat(other.get(0).lastFactAt()).as("没有事实 ⇒ 时刻留空").isNull();
  }

  // ==========================================================================
  // 工具
  // ==========================================================================

  private StoreOverviewModels.StoreOverviewRow row(
      StoreOverviewModels.StoreOverviewResponse response, String shopCode) {
    return response.rows().stream()
        .filter(item -> item.shopCode().equals(shopCode))
        .findFirst()
        .orElseThrow(() -> new AssertionError("响应里没有这个店铺：" + shopCode));
  }

  private StoreOverviewModels.StoreOverviewDimension dimension(
      StoreOverviewModels.StoreOverviewRow row, String key) {
    return row.dimensions().stream()
        .filter(item -> item.key().equals(key))
        .findFirst()
        .orElseThrow(() -> new AssertionError("响应里没有这个维度：" + key));
  }

  private StoreOverviewModels.StoreOverviewMetricValue metric(
      StoreOverviewModels.StoreOverviewDimension dimension, String metricCode, String currency) {
    return dimension.values().stream()
        .filter(
            item -> item.metricCode().equals(metricCode) && item.currency().equals(currency))
        .findFirst()
        .orElseThrow(
            () -> new AssertionError("维度里没有这个口径取值：" + metricCode + "/" + currency));
  }

  private BigDecimal value(
      StoreOverviewModels.StoreOverviewDimension dimension, String metricCode, String currency) {
    return metric(dimension, metricCode, currency).value();
  }

  private long factCount() {
    return tenants.run(
        tenant,
        () -> {
          var row =
              sql.fetchOne(
                  "select count(*) from reporting_store_metric_fact where tenant_id = ?", tenant);
          return row == null ? 0L : row.get(0, Long.class);
        });
  }
}
