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
 * 13.2.2.1 店铺与站点的**集成测试**（真实 PostgreSQL 16 + FORCE RLS，无内存替身）。
 *
 * <p>本文件验的是**业务口径**（多租户三条断言在 {@code StoreSiteBreakdownTenancyIntegrationTest}）：
 *
 * <ul>
 *   <li>店铺 / 站点 / 平台**三个维度**都真的拆得出来（平台 → 站点 → 店铺同一棵树）；
 *   <li>**增长贡献度**算得对（谁的增量算在总增量里）—— 蓝图关系句点名的那件事；
 *   <li>**新店 / 老店**按成交事实区分，且新店对增量的贡献算得对；
 *   <li>**渠道占比**（平台维度）算得对；
 *   <li>**站点归属边界**：启用多个站点的店铺，其销量归不了任何站点 ⇒ **单列**，不摊给任何站点；
 *   <li>「算不出来」与「等于 0」分开：基期为 0 ⇒ 增长率**不可比**，不是 0%；
 *   <li>**绝不跨币种相加**：金额按币种分别报，件数 / 单数才跨币种合计；
 *   <li>重复刷新幂等；上游读不到就 503，不返回空集合。
 * </ul>
 *
 * <p>⚠️ **上游是真的 HTTP**：本测试起一个假的上游服务（channel + trade），
 * 驱动 {@code ReportingStoreSiteRefreshService} 的**真实**拉取与投影路径 ——
 * 端口写错了路径 / 字段名，在这一层就会暴露（写错字段名 ⇒ 那一类指标恒为 0）。
 *
 * <p>⚠️ **时间夹具用 DB 时钟**：窗口日期取自 PostgreSQL 的 {@code now()}，
 * **不是** JVM 墙钟 —— 否则夹具与查询分属两个钟域，测试会间歇性失败。
 *
 * <p>⚠️ **连接池固定为 1 条连接**：只有复用同一条连接，才能真正证明归还后不会串租户。
 */
@SpringBootTest
@AutoConfigureMockMvc
class StoreSiteBreakdownIntegrationTest {

  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID tenant = UUID.randomUUID(), user = UUID.randomUUID();
  static final String BEARER = "Bearer tenant-a";

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

  /**
   * 🔴 **夹具的基准日取自 DB 时钟**（PostgreSQL 的 now()），不是 JVM 墙钟。
   *
   * <p>为什么：夹具的订单时刻、刷新用的窗口、查询用的窗口必须**落在同一个钟域**里。
   * 用 JVM 墙钟造数据、再跟 DB 侧算出来的日期比，两端可能分属两个钟域 ⇒ 间歇性失败
   * （本仓已因为同类原因翻过车：identity 审批待办超时、warehouse 货权档漂移判定）。
   */
  static LocalDate today;

  /** 当前窗口（[day1, day2]）。 */
  static LocalDate day1;

  /** 当前窗口末日。 */
  static LocalDate day2;

  static final ZoneId ZONE = ZoneId.of("UTC");

  /** ⚠️ 上游故障开关：打开后假上游一律 500，用来证「读不到 ≠ 没有数据」。 */
  static volatile boolean upstreamDown = false;

  // ==========================================================================
  // 夹具数据
  // ==========================================================================

  /** 一个店铺的事实汇总（金额 / 件数两张表，按「指标码|币种」索引）。 */
  record ShopFacts(Map<String, BigDecimal> values, Map<String, BigDecimal> quantities) {}

  static final UUID SHOP_A = UUID.randomUUID();
  static final UUID SHOP_B = UUID.randomUUID();
  static final UUID SHOP_C = UUID.randomUUID();

  static final UUID SITE_AMAZON_US = UUID.randomUUID();
  static final UUID SITE_AMAZON_DE = UUID.randomUUID();
  static final UUID SITE_TIKTOK_US = UUID.randomUUID();

  /** 平台与站点目录（channel）。 */
  static final List<Map<String, Object>> PLATFORMS =
      List.of(
          Map.of(
              "id",
              UUID.randomUUID(),
              "code",
              "AMAZON",
              "name",
              "亚马逊",
              "marketplaces",
              List.of(
                  Map.of(
                      "id",
                      SITE_AMAZON_US,
                      "code",
                      "US",
                      "name",
                      "美国站",
                      "countryCode",
                      "US",
                      "defaultCurrency",
                      "USD",
                      "defaultTimezone",
                      "America/New_York"),
                  Map.of(
                      "id",
                      SITE_AMAZON_DE,
                      "code",
                      "DE",
                      "name",
                      "德国站",
                      "countryCode",
                      "DE",
                      "defaultCurrency",
                      "EUR",
                      "defaultTimezone",
                      "Europe/Berlin"))),
          Map.of(
              "id",
              UUID.randomUUID(),
              "code",
              "TIKTOK",
              "name",
              "TikTok Shop",
              "marketplaces",
              List.of(
                  Map.of(
                      "id",
                      SITE_TIKTOK_US,
                      "code",
                      "US",
                      "name",
                      "美国站",
                      "countryCode",
                      "US",
                      "defaultCurrency",
                      "USD",
                      "defaultTimezone",
                      "America/Los_Angeles"))));

  /**
   * 店铺清单（channel）。
   *
   * <p>🔴 三种归属状态各来一个：
   *
   * <ul>
   *   <li>{@code shop-a} —— **只启用了一个站点**（AMAZON/US）⇒ 销量**可唯一归属**到站点；
   *   <li>{@code shop-b} —— **启用了两个站点**（AMAZON/US + AMAZON/DE）⇒ 销量**归不了任何站点**；
   *   <li>{@code shop-c} —— **一个站点都没启用** ⇒ 同上。
   * </ul>
   */
  static final List<Map<String, Object>> SHOPS =
      List.of(
          Map.of(
              "id", SHOP_A, "code", "shop-a", "name", "亚马逊旗舰店", "platformCode", "AMAZON",
              "platformName", "亚马逊"),
          Map.of(
              "id", SHOP_B, "code", "shop-b", "name", "亚马逊德国店", "platformCode", "AMAZON",
              "platformName", "亚马逊"),
          Map.of(
              "id", SHOP_C, "code", "shop-c", "name", "TikTok 小店", "platformCode", "TIKTOK",
              "platformName", "TikTok Shop"));

  /** 店铺启用的站点（channel 的 shop_marketplace）。 */
  static final Map<String, List<Map<String, Object>>> SHOP_SITES =
      Map.of(
          "shop-a",
          List.of(
              Map.of(
                  "marketplaceId", SITE_AMAZON_US,
                  "marketplaceCode", "US",
                  "marketplaceName", "美国站",
                  "status", "ACTIVE",
                  "enabledAt", "2026-01-01T00:00:00Z")),
          "shop-b",
          List.of(
              Map.of(
                  "marketplaceId", SITE_AMAZON_US,
                  "marketplaceCode", "US",
                  "marketplaceName", "美国站",
                  "status", "ACTIVE",
                  "enabledAt", "2026-01-01T00:00:00Z"),
              Map.of(
                  "marketplaceId", SITE_AMAZON_DE,
                  "marketplaceCode", "DE",
                  "marketplaceName", "德国站",
                  "status", "ACTIVE",
                  "enabledAt", "2026-02-01T00:00:00Z")),
          // 🔴 shop-c **没有启用任何站点** ⇒ 销量归不了任何站点。
          "shop-c",
          List.of());

  /** 销售订单（trade）。⚠️ **在静态块里赋值**（夹具日期取自 DB 时钟，声明顺序不能颠倒）。 */
  static List<Map<String, Object>> ORDERS;

  static {
    pg.start();
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      // 运行角色必须 NOBYPASSRLS，否则它会绕过策略，多租户断言全部变成空断言。
      s.execute("create role starcon_app nologin");
      s.execute(
          "create role reporting_app login password 'test-only' nosuperuser nobypassrls");

      // 🔴 **基准日取自 DB 时钟**（不是 JVM 墙钟）。
      var rs =
          s.executeQuery("select (now() at time zone 'UTC')::date");
      rs.next();
      today = rs.getObject(1, LocalDate.class);
      day1 = today.minusDays(1);
      day2 = today;

      // 🔴 订单夹具在**拿到 DB 时钟之后**才构造 —— 声明顺序不能颠倒。
      ORDERS =
          List.of(
              // 基期：shop-a 100.00 USD / 2 件
              order(
                  "SO-BASE-A",
                  day1.minusDays(2),
                  "shop-a",
                  "USD",
                  "100.00",
                  List.of(line("SKU-1", "2"))),
              // 基期：shop-b 50.00 USD / 1 件
              order(
                  "SO-BASE-B",
                  day1.minusDays(1),
                  "shop-b",
                  "USD",
                  "50.00",
                  List.of(line("SKU-2", "1"))),
              // 当前窗口：shop-a 300.00 USD / 5 件
              order("SO-A1", day1, "shop-a", "USD", "300.00", List.of(line("SKU-1", "5"))),
              // 当前窗口：shop-b 100.00 USD / 3 件
              order("SO-B1", day2, "shop-b", "USD", "100.00", List.of(line("SKU-2", "3"))),
              // 当前窗口：shop-c 200.00 EUR / 4 件（**新店** + **另一个币种**）
              order("SO-C1", day2, "shop-c", "EUR", "200.00", List.of(line("SKU-3", "4"))));

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
      upstream.createContext("/channel/platforms", e -> respond(e, PLATFORMS));
      upstream.createContext("/channel/shops", e -> respond(e, shopsBody(e)));
      upstream.createContext("/trade/sales-orders/list", e -> respond(e, ordersBody(e)));
      upstream.createContext("/trade/sales-orders/detail", e -> respond(e, detailBody(e)));
      upstream.createContext("/trade/platform-orders/orders", e -> respond(e, platformOrdersBody()));
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
    upstreamDown = false;
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate reporting_store_site_metric_fact, reporting_store_site_drilldown_fact,"
              + " reporting_store_site_metric_definition, reporting_store_site_profile cascade");
    }
  }

  // ==========================================================================
  // 业务口径
  // ==========================================================================

  @Test
  void 刷新把上游事实投影成店铺维度() {
    var results = refreshAll();
    assertThat(results).as("档案 + 两个窗口都要有结果").hasSize(3);
    assertThat(results).allMatch(r -> !"FAILED".equals(r.status()));

    // 档案：三个店铺的归属状态分别是 UNIQUE / AMBIGUOUS / NONE。
    var attribution =
        tenants.run(
            tenant,
            () -> {
              var out = new LinkedHashMap<String, String>();
              for (var row :
                  sql.fetch(
                      "select shop_code, attribution_status from reporting_store_site_profile"
                          + " order by shop_code")) {
                out.put(row.get(0, String.class), row.get(1, String.class));
              }
              return out;
            });
    assertThat(attribution).containsEntry("shop-a", "UNIQUE").containsEntry("shop-b", "AMBIGUOUS")
        .containsEntry("shop-c", "NONE");

    // 事实：shop-a 的两笔订单（基期 + 当前窗口）都投影进来了。
    //
    // ⚠️ **按数值比，不按字符串比**：PostgreSQL 的 `sum(numeric(24,4))` 结果刻度跟着输入走
    //   （1.0000 + 1.0000 = 2.0000），把刻度写进断言字符串就是给自己埋一颗雷 ——
    //   换个 sum 表达式或换个列类型，字符串断言会以「看起来像数据错了」的方式炸掉。
    //   `isEqualByComparingTo` 忽略刻度，比的是**值**，这才是这条断言真正想说的。
    var facts =
        tenants.run(
            tenant,
            () -> {
              var values = new LinkedHashMap<String, BigDecimal>();
              var quantities = new LinkedHashMap<String, BigDecimal>();
              for (var row :
                  sql.fetch(
                      "select metric_code, currency, sum(value) v, sum(quantity) q"
                          + " from reporting_store_site_metric_fact where tenant_id = ? and shop_code = ?"
                          + " group by metric_code, currency order by metric_code",
                      tenant,
                      "shop-a")) {
                var key = row.get(0, String.class) + "|" + row.get(1, String.class);
                values.put(key, row.get(2, BigDecimal.class));
                quantities.put(key, row.get(3, BigDecimal.class));
              }
              return new ShopFacts(values, quantities);
            });
    assertThat(facts.values.get("STORE_SITE_ORDER_COUNT|USD")).isEqualByComparingTo("2");
    assertThat(facts.values.get("STORE_SITE_SALES_AMOUNT|USD")).isEqualByComparingTo("400");
    assertThat(facts.quantities.get("STORE_SITE_SALES_QUANTITY|USD")).isEqualByComparingTo("7");
    // 金额型指标的件数恒为 0，数量型指标的金额恒为 0（两列不混用）。
    assertThat(facts.quantities.get("STORE_SITE_SALES_AMOUNT|USD")).isEqualByComparingTo("0");
    assertThat(facts.values.get("STORE_SITE_SALES_QUANTITY|USD")).isEqualByComparingTo("0");
  }

  @Test
  void 店铺维度拆解给出金额销量占比与增长贡献度() {
    refreshAll();
    var report = service.report(tenant, day1, day2, "SHOP", 0, 50);

    assertThat(report.dimension()).isEqualTo("SHOP");
    assertThat(report.totalRows()).isEqualTo(3);

    var shopA = rowOf(report, "shop-a");
    // 金额：**当前窗口** 300.00 USD；基期 100.00 USD（按币种分别报）。
    assertThat(amountOf(shopA, "STORE_SITE_SALES_AMOUNT", "USD")).isEqualByComparingTo("300.00");
    assertThat(baseOf(shopA, "STORE_SITE_SALES_AMOUNT", "USD")).isEqualByComparingTo("100.00");
    // 销量：跨币种合计（币种为空串 = 与币种无关）。
    assertThat(quantityOf(shopA, "STORE_SITE_SALES_QUANTITY")).isEqualByComparingTo("5");
    // 订单数：当前窗口 1 单。
    assertThat(countOf(shopA, "STORE_SITE_ORDER_COUNT")).isEqualByComparingTo("1");

    // 占比：当前窗口 USD 合计 = shop-a 300 + shop-b 100 = 400 ⇒ shop-a 75%、shop-b 25%。
    assertThat(shareOf(shopA, "STORE_SITE_AMOUNT_SHARE", "USD")).isEqualByComparingTo("75.0000");
    assertThat(shareOf(rowOf(report, "shop-b"), "STORE_SITE_AMOUNT_SHARE", "USD"))
        .isEqualByComparingTo("25.0000");
    // 销量占比：合计 5 + 3 + 4 = 12 ⇒ shop-a 41.6667%。
    assertThat(shareOf(shopA, "STORE_SITE_QUANTITY_SHARE", ""))
        .isEqualByComparingTo("41.6667");

    // 🔴 **增长贡献度**（蓝图点名的那件事）：
    //   USD 增量 = (300+100) − (100+50) = 250；shop-a 增量 200 ⇒ 80.0000%；shop-b 增量 50 ⇒ 20.0000%。
    //   （注意它与**占比**不是同一个数：占比看当前窗口，贡献度看增量。）
    assertThat(contributionOf(shopA, "STORE_SITE_AMOUNT_GROWTH_CONTRIBUTION", "USD"))
        .isEqualByComparingTo("80.0000");
    assertThat(contributionOf(rowOf(report, "shop-b"), "STORE_SITE_AMOUNT_GROWTH_CONTRIBUTION", "USD"))
        .isEqualByComparingTo("20.0000");
    //   EUR 只有 shop-c 有 ⇒ 它的增量就是全部增量 ⇒ 100%。
    assertThat(contributionOf(rowOf(report, "shop-c"), "STORE_SITE_AMOUNT_GROWTH_CONTRIBUTION", "EUR"))
        .isEqualByComparingTo("100.0000");
    //   销量增量 = (5+3+4) − (2+1) = 9；shop-a 增量 3 ⇒ 33.3333%。
    assertThat(contributionOf(shopA, "STORE_SITE_QUANTITY_GROWTH_CONTRIBUTION", ""))
        .isEqualByComparingTo("33.3333");

    // 环比增长率：shop-a (300−100)/100 = 200%。
    assertThat(growthOf(shopA, "STORE_SITE_AMOUNT_GROWTH", "USD")).isEqualByComparingTo("200.0000");
  }

  @Test
  void 站点维度只归能唯一归属的店铺其余单列() {
    refreshAll();
    var report = service.report(tenant, day1, day2, "SITE", 0, 50);

    // 🔴 **shop-a 只启用了一个站点** ⇒ 唯一归属到 AMAZON:US。
    assertThat(report.rows()).hasSize(1);
    var site = report.rows().get(0);
    assertThat(site.dimensionKey()).isEqualTo("AMAZON:US");
    assertThat(site.siteCode()).isEqualTo("US");
    assertThat(site.platformCode()).isEqualTo("AMAZON");
    assertThat(amountOf(site, "STORE_SITE_SALES_AMOUNT", "USD")).isEqualByComparingTo("300.00");
    assertThat(baseOf(site, "STORE_SITE_SALES_AMOUNT", "USD")).isEqualByComparingTo("100.00");

    // 🔴 **shop-b（两个站点）与 shop-c（没站点）归不了任何站点** ⇒ 单列，不摊给任何站点。
    assertThat(report.unattributed().shopCount()).isEqualTo(2);
    assertThat(report.unattributed().shopCodes()).containsExactlyInAnyOrder("shop-b", "shop-c");
    // 未归属部分的金额：shop-b 100.00 USD + shop-c 200.00 EUR。
    assertThat(
            amountOf(
                report.unattributed().metrics(), "STORE_SITE_SALES_AMOUNT", "USD"))
        .isEqualByComparingTo("100.00");
    assertThat(
            amountOf(
                report.unattributed().metrics(), "STORE_SITE_SALES_AMOUNT", "EUR"))
        .isEqualByComparingTo("200.00");
    // 站点维度的占比分母**只含可唯一归属的部分** ⇒ shop-a 占 100%。
    assertThat(shareOf(site, "STORE_SITE_AMOUNT_SHARE", "USD")).isEqualByComparingTo("100.0000");
    // 报告里必须有一条明确的警告。
    assertThat(report.warnings()).anyMatch(w -> w.contains("无法唯一归属到站点"));
  }

  @Test
  void 平台维度就是渠道占比() {
    refreshAll();
    var report = service.report(tenant, day1, day2, "PLATFORM", 0, 50);

    assertThat(report.rows()).hasSize(2);
    var amazon = rowOf(report, "AMAZON");
    var tiktok = rowOf(report, "TIKTOK");
    // AMAZON = shop-a（300）+ shop-b（100）= 当前窗口 400.00 USD / 8 件。
    assertThat(amountOf(amazon, "STORE_SITE_SALES_AMOUNT", "USD")).isEqualByComparingTo("400.00");
    assertThat(quantityOf(amazon, "STORE_SITE_SALES_QUANTITY")).isEqualByComparingTo("8");
    // TIKTOK = shop-c。
    assertThat(amountOf(tiktok, "STORE_SITE_SALES_AMOUNT", "EUR")).isEqualByComparingTo("200.00");

    // 渠道占比：**始终**按平台维度给，与当前选中的维度无关。
    assertThat(report.channelShares()).hasSize(2);
    var amazonShare =
        report.channelShares().stream()
            .filter(s -> "AMAZON".equals(s.platformCode()))
            .findFirst()
            .orElseThrow();
    assertThat(shareOf(amazonShare.metrics(), "STORE_SITE_AMOUNT_SHARE", "USD"))
        .isEqualByComparingTo("100.0000");
  }

  @Test
  void 新店老店按成交事实区分并算出新店对增量的贡献() {
    refreshAll();
    var report = service.report(tenant, day1, day2, "SHOP", 0, 50);

    // shop-c 基期没有成交 ⇒ 新店；shop-a / shop-b 基期有成交 ⇒ 老店。
    assertThat(report.newVsExisting().newShopCount()).isEqualTo(1);
    assertThat(report.newVsExisting().existingShopCount()).isEqualTo(2);
    assertThat(rowOf(report, "shop-c").storeAge()).isEqualTo("NEW");
    assertThat(rowOf(report, "shop-a").storeAge()).isEqualTo("EXISTING");

    // 新店的成交金额 = shop-c 的 200.00 EUR。
    assertThat(
            amountOf(
                report.newVsExisting().newStoreMetrics(), "STORE_SITE_SALES_AMOUNT", "EUR"))
        .isEqualByComparingTo("200.00");
    // 老店的成交金额 = shop-a 300 + shop-b 100 = 400.00 USD。
    assertThat(
            amountOf(
                report.newVsExisting().existingStoreMetrics(), "STORE_SITE_SALES_AMOUNT", "USD"))
        .isEqualByComparingTo("400.00");

    // 🔴 **新店对增量的贡献**：EUR 增量全部来自新店 ⇒ 100%。
    var contribution =
        report.newVsExisting().newStoreContribution().stream()
            .filter(m -> "EUR".equals(m.currency()))
            .findFirst()
            .orElseThrow();
    assertThat(contribution.value()).isEqualByComparingTo("100.0000");
  }

  @Test
  void 基期为零时增长率不可比而不是零() {
    refreshAll();
    var report = service.report(tenant, day1, day2, "SHOP", 0, 50);

    // shop-c 基期**没有任何 EUR 事实** ⇒ 环比增长率算不出来（不是 0%）。
    var growth =
        metricOf(rowOf(report, "shop-c"), "STORE_SITE_AMOUNT_GROWTH", "EUR");
    assertThat(growth.value()).as("基期缺失 ⇒ 增长率算不出来").isNull();
    assertThat(growth.availability()).isEqualTo("NO_DATA");
    assertThat(growth.availabilityNote()).contains("算不出来");
  }

  @Test
  void 重复刷新幂等不会把金额加两遍() {
    refreshAll();
    var first = service.report(tenant, day1, day2, "SHOP", 0, 50);
    refreshAll();
    var second = service.report(tenant, day1, day2, "SHOP", 0, 50);

    assertThat(amountOf(rowOf(second, "shop-a"), "STORE_SITE_SALES_AMOUNT", "USD"))
        .isEqualByComparingTo(
            amountOf(rowOf(first, "shop-a"), "STORE_SITE_SALES_AMOUNT", "USD"));
    assertThat(quantityOf(rowOf(second, "shop-a"), "STORE_SITE_SALES_QUANTITY"))
        .isEqualByComparingTo(
            quantityOf(rowOf(first, "shop-a"), "STORE_SITE_SALES_QUANTITY"));
  }

  @Test
  void 上游读不到就失败不返回空集合() {
    upstreamDown = true;
    assertThatThrownBy(() -> refreshAll())
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
        .hasMessageContaining("读不到");
  }

  @Test
  void 端点返回真实报表() throws Exception {
    refreshAll();
    // ⚠️ **必须把窗口钉死**（from / to）：端点不传参时默认「最近 30 天」，
    //   那个窗口会把**基期**也圈进来 ⇒ 基期不再是「没有任何成交事实」⇒
    //   三家店全被判成新店（newShopCount = 3 而不是 1）。
    //   基期是**上一等长窗口**，只有把当前窗口钉成 [day1, day2]，
    //   基期才落到 [day1-2, day1-1] —— 夹具里 shop-a / shop-b 的成交事实正好在那里。
    http.perform(
            get("/reporting/store-site")
                .header("Authorization", BEARER)
                .param("from", day1.toString())
                .param("to", day2.toString())
                .param("dimension", "SHOP"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.dimension").value("SHOP"))
        .andExpect(jsonPath("$.requestedFrom").value(day1.toString()))
        .andExpect(jsonPath("$.requestedTo").value(day2.toString()))
        .andExpect(jsonPath("$.rows.length()").value(3))
        .andExpect(jsonPath("$.channelShares.length()").value(2))
        .andExpect(jsonPath("$.newVsExisting.newShopCount").value(1))
        .andExpect(jsonPath("$.newVsExisting.existingShopCount").value(2));
    http.perform(get("/reporting/store-site/metrics").header("Authorization", BEARER))
        .andExpect(status().isOk());
    http.perform(get("/reporting/store-site/capabilities").header("Authorization", BEARER))
        .andExpect(status().isOk());
  }

  @Test
  void 构成明细可以下钻到订单行() {
    refreshAll();
    var drill =
        service.drilldown(tenant, day1, day2, "SHOP", "shop-a", null, 0, 50);
    assertThat(drill.rows()).isNotEmpty();
    // 订单级明细（金额）与行级明细（销量）都在。
    assertThat(drill.rows()).anyMatch(r -> "ORDER".equals(r.sourceKind()));
    assertThat(drill.rows()).anyMatch(r -> "ORDER_LINE".equals(r.sourceKind()));
    assertThat(drill.rows()).anyMatch(r -> "SO-A1".equals(r.orderNo()));
    // 订单级金额 = 300.00。
    var orderRow =
        drill.rows().stream()
            .filter(r -> "ORDER".equals(r.sourceKind()) && "SO-A1".equals(r.orderNo()))
            .findFirst()
            .orElseThrow();
    assertThat(orderRow.value()).isEqualByComparingTo("300.00");
  }

  @Test
  void 非法维度直接拒绝不静默回落() throws Exception {
    http.perform(
            get("/reporting/store-site")
                .header("Authorization", BEARER)
                .param("dimension", "BRAND"))
        .andExpect(status().isBadRequest());
  }

  // ==========================================================================
  // 工具
  // ==========================================================================

  private List<ReportingStoreSiteModels.StoreSiteRefreshResult> refreshAll() {
    return refresh.refresh(tenant, BEARER, day1, day2);
  }

  private static ReportingStoreSiteModels.StoreSiteRow rowOf(
      ReportingStoreSiteModels.StoreSiteResponse report, String key) {
    return report.rows().stream()
        .filter(r -> key.equals(r.dimensionKey()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("报表里没有维度键 " + key));
  }

  private static ReportingStoreSiteModels.StoreSiteMetricValue metricOf(
      ReportingStoreSiteModels.StoreSiteRow row, String metricCode, String currency) {
    return row.metrics().stream()
        .filter(m -> metricCode.equals(m.metricCode()) && currency.equals(m.currency()))
        .findFirst()
        .orElseThrow(
            () -> new AssertionError("维度 " + row.dimensionKey() + " 没有指标 " + metricCode + "（" + currency + "）"));
  }

  private static ReportingStoreSiteModels.StoreSiteMetricValue metricOf(
      List<ReportingStoreSiteModels.StoreSiteMetricValue> metrics, String metricCode, String currency) {
    return metrics.stream()
        .filter(m -> metricCode.equals(m.metricCode()) && currency.equals(m.currency()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("没有指标 " + metricCode + "（" + currency + "）"));
  }

  private static BigDecimal amountOf(
      ReportingStoreSiteModels.StoreSiteRow row, String metricCode, String currency) {
    return metricOf(row, metricCode, currency).value();
  }

  private static BigDecimal amountOf(
      List<ReportingStoreSiteModels.StoreSiteMetricValue> metrics, String metricCode, String currency) {
    return metricOf(metrics, metricCode, currency).value();
  }

  private static BigDecimal baseOf(
      ReportingStoreSiteModels.StoreSiteRow row, String metricCode, String currency) {
    return metricOf(row, metricCode, currency).baseValue();
  }

  private static BigDecimal quantityOf(
      ReportingStoreSiteModels.StoreSiteRow row, String metricCode) {
    return metricOf(row, metricCode, "").value();
  }

  private static BigDecimal countOf(
      ReportingStoreSiteModels.StoreSiteRow row, String metricCode) {
    return metricOf(row, metricCode, "").value();
  }

  private static BigDecimal shareOf(
      ReportingStoreSiteModels.StoreSiteRow row, String metricCode, String currency) {
    return metricOf(row, metricCode, currency).value();
  }

  private static BigDecimal shareOf(
      List<ReportingStoreSiteModels.StoreSiteMetricValue> metrics, String metricCode, String currency) {
    return metricOf(metrics, metricCode, currency).value();
  }

  private static BigDecimal contributionOf(
      ReportingStoreSiteModels.StoreSiteRow row, String metricCode, String currency) {
    return metricOf(row, metricCode, currency).value();
  }

  private static BigDecimal growthOf(
      ReportingStoreSiteModels.StoreSiteRow row, String metricCode, String currency) {
    return metricOf(row, metricCode, currency).value();
  }

  // ---------------------------------------------------------------------------
  // 夹具构造
  // ---------------------------------------------------------------------------

  private static Map<String, Object> order(
      String orderNo,
      LocalDate day,
      String shopCode,
      String currency,
      String amount,
      List<Map<String, Object>> lines) {
    var at = day.atTime(10, 0).atOffset(ZoneOffset.UTC);
    return Map.of(
        "id",
        UUID.nameUUIDFromBytes(orderNo.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
        "orderNo",
        orderNo,
        "orderSource",
        "PLATFORM",
        "currency",
        currency,
        "grandTotalAmount",
        new BigDecimal(amount),
        "lineCount",
        lines.size(),
        "submittedAt",
        at.toString(),
        "createdAt",
        at.toString(),
        "__shopCode",
        shopCode,
        "__lines",
        lines);
  }

  private static Map<String, Object> line(String skuCode, String quantity) {
    return Map.of(
        "skuCode", skuCode,
        "skuName", skuCode + " 商品",
        "quantity", new BigDecimal(quantity),
        "unitPrice", new BigDecimal("10.00"),
        "lineAmount", new BigDecimal("10.00").multiply(new BigDecimal(quantity)));
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

  /** 店铺列表 / 店铺详情（同一个 context，按路径区分）。 */
  private static Object shopsBody(com.sun.net.httpserver.HttpExchange e) throws java.io.IOException {
    var path = e.getRequestURI().getPath();
    if (path.equals("/channel/shops")) return SHOPS;
    // /channel/shops/{id}
    var id = path.substring("/channel/shops/".length());
    for (var shop : SHOPS) {
      if (String.valueOf(shop.get("id")).equals(id)) {
        var code = String.valueOf(shop.get("code"));
        var sites = SHOP_SITES.getOrDefault(code, List.of());
        var detail = new LinkedHashMap<String, Object>(shop);
        detail.put("marketplaces", sites);
        return detail;
      }
    }
    return null;
  }

  /** 销售订单列表（**按上游的 orderedFrom / orderedTo 过滤**）。 */
  private static Object ordersBody(com.sun.net.httpserver.HttpExchange e)
      throws java.io.IOException {
    var query = query(e.getRequestURI().getRawQuery());
    var from = parseInstant(query.get("orderedFrom"));
    var to = parseInstant(query.get("orderedTo"));
    var matched = new ArrayList<Map<String, Object>>();
    for (var row : ORDERS) {
      var at = parseInstant(String.valueOf(row.get("submittedAt")));
      if (at == null) continue;
      if (from != null && at.isBefore(from)) continue;
      if (to != null && !at.isBefore(to)) continue;
      var copy = new LinkedHashMap<String, Object>(row);
      copy.remove("__shopCode");
      copy.remove("__lines");
      matched.add(copy);
    }
    return Map.of("rows", matched, "page", 0, "size", 200, "total", matched.size());
  }

  /** 订单明细。 */
  private static Object detailBody(com.sun.net.httpserver.HttpExchange e)
      throws java.io.IOException {
    var path = e.getRequestURI().getPath();
    var id = path.substring("/trade/sales-orders/detail/".length());
    for (var row : ORDERS) {
      if (String.valueOf(row.get("id")).equals(id)) {
        return Map.of(
            "header",
            Map.of(
                "orderNo",
                row.get("orderNo"),
                "currency",
                row.get("currency"),
                "grandTotalAmount",
                row.get("grandTotalAmount"),
                "submittedAt",
                row.get("submittedAt")),
            "lines",
            row.get("__lines"));
      }
    }
    return null;
  }

  /** 平台单（**销售订单 → 店铺归属**的唯一来源）。 */
  private static Object platformOrdersBody() {
    var rows = new ArrayList<Map<String, Object>>();
    for (var row : ORDERS) {
      rows.add(
          Map.of(
              "id",
              UUID.nameUUIDFromBytes(
                  ("platform:" + row.get("orderNo")).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
              "salesOrderId",
              row.get("id"),
              "orderNo",
              row.get("orderNo"),
              "platformCode",
              "shop-c".equals(String.valueOf(row.get("__shopCode"))) ? "TIKTOK" : "AMAZON",
              "shopCode",
              row.get("__shopCode"),
              "currency",
              row.get("currency"),
              "platformPlacedAt",
              row.get("submittedAt"),
              "syncedAt",
              row.get("submittedAt")));
    }
    return Map.of("rows", rows, "page", 0, "size", 200, "total", rows.size());
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

  /**
   * 把夹具里的时刻串解析成瞬间。
   *
   * <p>🔴 **两种形状都要收**：上游给的是 {@code OffsetDateTime} 的 ISO 串，而
   * {@code OffsetDateTime.toString()} 在「秒为 0」时**省略秒字段**
   * （{@code 2026-10-26T10:00Z}，不是 {@code 2026-10-26T10:00:00Z}）——
   * 这正是 Jackson 序列化上游 {@code submittedAt} 时会产出的形状。
   * {@link Instant#parse} 要求秒字段 ⇒ 直接用它会抛
   * {@link java.time.format.DateTimeParseException}。
   *
   * <p>⚠️ **这个异常曾经伪装成「上游不可达」**：异常发生在 HTTP handler 里 ⇒
   * JDK HttpServer 直接**掐断连接**（不回任何响应）⇒ 客户端只看到「响应被截断」
   * （{@code ResourceAccessException}）⇒ 端口把它翻成 503「上游不可达」——
   * 真实原因（夹具字符串形状）藏在网络层下面，看不见。
   */
  private static Instant parseInstant(String raw) {
    if (raw == null || raw.isBlank()) return null;
    try {
      return Instant.parse(raw);
    } catch (java.time.format.DateTimeParseException e) {
      return java.time.OffsetDateTime.parse(raw).toInstant();
    }
  }
}
