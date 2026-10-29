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
 * 13.2.2.2 SKU/SPU/Listing 的**集成测试**（真实 PostgreSQL 16 + FORCE RLS，无内存替身）。
 *
 * <p>本文件验的是**业务口径**（多租户三条断言在 {@code ProductSalesTenancyIntegrationTest}）：
 *
 * <ul>
 *   <li>SKU / SPU / Listing **三层**都真的投影出来了；
 *   <li>SPU 层把名下**多个 SKU、多个平台**合并（蓝图的「SPU 口径是跨平台经营的基本单位」）；
 *   <li>变体销量占比按**所属 SPU 的销量**算出来（跨层口径：分子 SKU 层 / 分母 SPU 层）；
 *   <li>Listing 层只归**可唯一归属**的销量：同一 SKU 挂在多个 Listing 上时**不摊给任何 Listing**；
 *   <li>「算不出来」与「等于 0」分开：SPU 销量为 0 ⇒ 占比不可比，不是 0%；
 *   <li>订单数**按订单去重**（一单两行只算一单）；
 *   <li>重复刷新幂等；上游读不到就 503，不返回空集合。
 * </ul>
 *
 * <p>⚠️ **上游是真的 HTTP**：本测试起一个假的上游服务（catalog + channel + trade），
 * 驱动 {@code ReportingProductSalesRefreshService} 的**真实**拉取与投影路径 ——
 * 端口写错了路径 / 字段名，在这一层就会暴露（写错字段名 ⇒ 那一类指标恒为 0）。
 *
 * <p>⚠️ **连接池固定为 1 条连接**：只有复用同一条连接，才能真正证明归还后不会串租户。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ProductSalesIntegrationTest {

  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID tenant = UUID.randomUUID(), user = UUID.randomUUID();
  static final String BEARER = "Bearer tenant-a";

  static HttpServer identity;
  static HttpServer upstream;

  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  static final List<String> permissions =
      List.of(
          "reporting.product-sales.read",
          // ⚠️ 写权限必须在**同一个 grant** 里同时命中对应的 .read ——
          //   服务端的判据是「能写不能读」的组合不放行，所以这里必须带上 reporting.projection.read。
          "reporting.projection.read",
          "reporting.projection.write");

  static final ObjectMapper JSON = JsonMapper.builder().addModule(new JavaTimeModule()).build();

  /** 当前窗口。 */
  static final LocalDate DAY1 = LocalDate.of(2026, 10, 1);
  static final LocalDate DAY2 = LocalDate.of(2026, 10, 2);

  static final ZoneId ZONE = ZoneId.of("UTC");

  /** ⚠️ 上游故障开关：打开后假上游一律 500，用来证「读不到 ≠ 没有数据」。 */
  static volatile boolean upstreamDown = false;

  // ==========================================================================
  // 夹具数据
  // ==========================================================================

  static final UUID SPU_CUP = UUID.randomUUID();
  static final UUID SPU_MUG = UUID.randomUUID();
  static final UUID SKU_RED_S = UUID.randomUUID();
  static final UUID SKU_RED_L = UUID.randomUUID();
  static final UUID SKU_BLUE_S = UUID.randomUUID();
  static final UUID SKU_ORPHAN = UUID.randomUUID();
  static final UUID LISTING_AMAZON = UUID.randomUUID();
  static final UUID LISTING_TIKTOK = UUID.randomUUID();

  /**
   * SKU 档案（catalog）。
   *
   * <p>🔴 {@code SKU_ORPHAN} **没有 SPU** —— 商品主数据里它还没归入任何 SPU。
   * 它**不进 SPU 汇总**（不猜一个 SPU），但在 SKU 层照样有自己的档案。
   */
  static final List<Map<String, Object>> SKUS =
      List.of(
          sku(SKU_RED_S, "SKU-RED-S", "红杯 S", SPU_CUP, "SPU-CUP", "马克杯套装", "红色/S", "ACTIVE"),
          sku(SKU_RED_L, "SKU-RED-L", "红杯 L", SPU_CUP, "SPU-CUP", "马克杯套装", "红色/L", "ACTIVE"),
          sku(SKU_BLUE_S, "SKU-BLUE-S", "蓝杯 S", SPU_MUG, "SPU-MUG", "陶瓷马克杯", "蓝色/S", "ACTIVE"),
          sku(SKU_ORPHAN, "SKU-ORPHAN", "未归 SPU 的商品", null, null, null, "", "DRAFT"));

  /**
   * 平台商品（channel）。
   *
   * <p>🔴 **SKU-RED-S 同时挂在两个 Listing 上**（亚马逊 + TikTok）⇒ 它的销量
   * **无法唯一归属**到某个 Listing。SKU-RED-L 只挂亚马逊、SKU-BLUE-S 只挂 TikTok ⇒ 可唯一归属。
   */
  static final List<Map<String, Object>> LISTINGS =
      List.of(
          listing(LISTING_AMAZON, "shop-a", "亚马逊旗舰店", "AMAZON", "AMAZON-CUP-RED", "红杯（亚马逊）",
              "MAPPED", List.of(SKU_RED_S, SKU_RED_L)),
          listing(LISTING_TIKTOK, "shop-b", "TikTok 小店", "TIKTOK", "TT-CUP-RED", "红杯（TikTok）",
              "MAPPED", List.of(SKU_RED_S, SKU_BLUE_S)));

  /** 销售订单（trade）。 */
  static final List<Map<String, Object>> ORDERS =
      List.of(
          // SO-1：红杯 S ×2 + 红杯 L ×1（USD）
          order("SO-1", DAY1, "USD", OffsetDateTime.of(2026, 10, 1, 10, 0, 0, 0, ZoneOffset.UTC),
              List.of(line("SKU-RED-S", "红杯 S", "2", "25.00", "50.00"),
                  line("SKU-RED-L", "红杯 L", "1", "30.00", "30.00"))),
          // SO-2：蓝杯 S ×3（USD）
          order("SO-2", DAY2, "USD", OffsetDateTime.of(2026, 10, 2, 10, 0, 0, 0, ZoneOffset.UTC),
              List.of(line("SKU-BLUE-S", "蓝杯 S", "3", "10.00", "30.00"))),
          // 🔴 SO-3：**东八区**的下单时刻（+08:00）⇒ UTC 下落在 10-01，东八区下落在 10-02。
          //   这条订单同时是「跨币种」与「时区往返」的夹具。
          order("SO-3", DAY2, "EUR", OffsetDateTime.of(2026, 10, 2, 1, 0, 0, 0, ZoneOffset.ofHours(8)),
              List.of(line("SKU-RED-L", "红杯 L", "2", "28.00", "56.00"))),
          // 🔴 SO-4：**同一张订单里同一款 SKU 出现两行** ⇒ 订单数只算一单，销量算两行。
          order("SO-4", DAY1, "USD", OffsetDateTime.of(2026, 10, 1, 12, 0, 0, 0, ZoneOffset.UTC),
              List.of(line("SKU-BLUE-S", "蓝杯 S", "1", "10.00", "10.00"),
                  line("SKU-BLUE-S", "蓝杯 S", "1", "12.00", "12.00"))));

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
      upstream.createContext("/catalog/skus", e -> respond(e, pageBody("items", SKUS)));
      upstream.createContext("/channel/listings", e -> respond(e, pageBody("items", LISTINGS)));
      upstream.createContext("/trade/sales-orders/list", e -> respond(e, ordersBody(e)));
      upstream.createContext("/trade/sales-orders/detail", e -> respond(e, detailBody(e)));
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
    upstreamDown = false;
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate reporting_product_metric_fact, reporting_product_drilldown_fact,"
              + " reporting_product_metric_definition, reporting_product_listing_sku,"
              + " reporting_product_dimension cascade");
    }
  }

  // ==========================================================================
  // 业务口径
  // ==========================================================================

  @Test
  void 刷新把上游事实投影成三层维度() {
    var results = refreshAll();
    assertThat(results).as("两路上游都要有结果").hasSize(2);
    assertThat(results).allSatisfy(r -> assertThat(r.status()).isEqualTo("SUCCEEDED"));
    assertThat(factCount()).as("事实行必须落库").isPositive();
    assertThat(dimensionCount("SKU")).as("SKU 档案必须落库").isEqualTo(4);
    assertThat(dimensionCount("SPU")).as("SPU 档案必须落库").isEqualTo(2);
    assertThat(dimensionCount("LISTING")).as("Listing 档案必须落库").isEqualTo(2);
  }

  @Test
  void SKU层的销量与金额算出来() {
    refreshAll();
    var response = sales.report(tenant, DAY1, DAY2, "SKU", false, null, null, null, 0, 50);

    var redS = row(response, "SKU-RED-S");
    assertThat(value(redS, ReportingProductSalesCatalog.PRODUCT_SALES_QUANTITY))
        .as("SKU-RED-S 销量 = 2 件").isEqualByComparingTo("2");
    assertThat(value(redS, ReportingProductSalesCatalog.PRODUCT_SALES_AMOUNT))
        .as("SKU-RED-S 成交金额 = 50.00").isEqualByComparingTo("50.00");
    assertThat(value(redS, ReportingProductSalesCatalog.PRODUCT_ORDER_COUNT))
        .as("SKU-RED-S 只出现在一张单里").isEqualByComparingTo("1");
  }

  @Test
  void SPU层把名下多个SKU与多个平台合并() {
    refreshAll();
    var response = sales.report(tenant, DAY1, DAY2, "SPU", false, null, null, null, 0, 50);

    // 🔴 蓝图：「SPU 口径是跨平台经营的基本单位」⇒ SPU-CUP = 红杯 S + 红杯 L（两个 SKU，
    //   其中红杯 S 同时在亚马逊与 TikTok 上架 ⇒ 这一层是**跨平台合并**）。
    var cup = row(response, "SPU-CUP");
    assertThat(value(cup, ReportingProductSalesCatalog.PRODUCT_SALES_QUANTITY))
        .as("SPU-CUP 销量 = 2 + 3 = 5 件（两个 SKU 合并）").isEqualByComparingTo("5");
    assertThat(amountOf(cup, "USD"))
        .as("SPU-CUP 的 USD 成交金额 = 50 + 30 = 80.00").isEqualByComparingTo("80.00");
    assertThat(amountOf(cup, "EUR"))
        .as("SPU-CUP 的 EUR 成交金额 = 56.00（**不与 USD 相加**）").isEqualByComparingTo("56.00");
    assertThat(cup.currencies()).containsExactly("EUR", "USD");
  }

  @Test
  void 合并SPU开关等价于按SPU层看() {
    refreshAll();
    var merged = sales.report(tenant, DAY1, DAY2, "SKU", true, null, null, null, 0, 50);
    assertThat(merged.level()).isEqualTo("SPU");
    assertThat(merged.mergeSpu()).isTrue();
    assertThat(merged.rows()).extracting(r -> r.dimensionKey()).containsExactly("SPU-CUP", "SPU-MUG");
  }

  @Test
  void 变体销量占比按所属SPU的销量算出来() {
    refreshAll();
    var response = sales.variants(tenant, DAY1, DAY2, "SPU-CUP");

    assertThat(response.variants()).hasSize(2);
    var redS = variant(response, "SKU-RED-S");
    // 红杯 S 2 件 / SPU-CUP 共 5 件 = 40%
    assertThat(redS.share()).as("变体销量占比 = 该变体销量 ÷ 所属 SPU 销量").isEqualByComparingTo("40.0000");
    var redL = variant(response, "SKU-RED-L");
    // 红杯 L 3 件（USD 1 件 + EUR 2 件）/ 5 件 = 60%
    assertThat(redL.share()).isEqualByComparingTo("60.0000");
    assertThat(response.totalQuantity()).as("SPU 总销量 = 5 件").isEqualByComparingTo("5");
  }

  @Test
  void 变体占比在SPU层不出现() {
    refreshAll();
    var response = sales.report(tenant, DAY1, DAY2, "SPU", false, null, null, null, 0, 50);
    // 🔴 变体销量占比是**跨层**口径（分子 SKU 层 / 分母 SPU 层）——
    //   在 SPU 层它没有意义（SPU 销量 ÷ SPU 销量 = 100%），所以**不出现**这个指标，
    //   而不是报一个「算不出来」的假指标。
    assertThat(row(response, "SPU-CUP").metrics())
        .extracting(m -> m.metricCode())
        .doesNotContain(ReportingProductSalesCatalog.PRODUCT_VARIANT_SHARE);
  }

  @Test
  void Listing层只归可唯一归属的销量() {
    refreshAll();
    var response = sales.report(tenant, DAY1, DAY2, "LISTING", false, null, null, null, 0, 50);

    // L-1（亚马逊）只挂了红杯 L（红杯 S 同时挂在 L-2 上 ⇒ 归不了）⇒ 销量 = 红杯 L 的 3 件。
    var amazon = row(response, LISTING_AMAZON.toString());
    assertThat(value(amazon, ReportingProductSalesCatalog.PRODUCT_SALES_QUANTITY))
        .as("L-1 只归红杯 L 的销量（3 件）").isEqualByComparingTo("3");
    assertThat(amazon.attributionStatus()).isEqualTo("SHARED_SKU");
    assertThat(amazon.attributionNote()).contains("无法唯一归属");

    // L-2（TikTok）挂了红杯 S（共享）+ 蓝杯 S（唯一）⇒ 只归蓝杯 S 的 5 件。
    var tiktok = row(response, LISTING_TIKTOK.toString());
    assertThat(value(tiktok, ReportingProductSalesCatalog.PRODUCT_SALES_QUANTITY))
        .as("L-2 只归蓝杯 S 的销量（5 件）").isEqualByComparingTo("5");
  }

  @Test
  void 归不了的销量单列不摊给任何Listing() {
    refreshAll();
    var response = sales.report(tenant, DAY1, DAY2, "LISTING", false, null, null, null, 0, 50);

    // 🔴 SKU-RED-S 挂在两个 Listing 上 ⇒ 它的 2 件 / 50.00 USD **不摊给任何 Listing**。
    var total = response.rows().stream()
        .map(r -> value(r, ReportingProductSalesCatalog.PRODUCT_SALES_QUANTITY))
        .filter(java.util.Objects::nonNull)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
    assertThat(total)
        .as("两个 Listing 加起来只有 3 + 5 = 8 件（红杯 S 的 2 件不摊给任何 Listing）")
        .isEqualByComparingTo("8");

    assertThat(response.unattributed().quantity()).as("无法归属的件数 = 2").isEqualByComparingTo("2");
    assertThat(response.unattributed().skuCount()).isEqualTo(1);
    assertThat(response.unattributed().amounts()).hasSize(1);
    assertThat(response.unattributed().amounts().get(0).currency()).isEqualTo("USD");
    assertThat(response.unattributed().amounts().get(0).amount())
        .as("无法归属的金额 = 50.00 USD").isEqualByComparingTo("50.00");
  }

  @Test
  void 派生指标按分子分母算_不写死公式() {
    refreshAll();
    var response = sales.report(tenant, DAY1, DAY2, "SKU", false, null, null, null, 0, 50);

    // 件均价 = 成交金额 ÷ 销量 = 50.00 ÷ 2 = 25.00
    assertThat(value(row(response, "SKU-RED-S"), ReportingProductSalesCatalog.PRODUCT_AVG_UNIT_PRICE))
        .isEqualByComparingTo("25.0000");
    // 客单价 = 成交金额 ÷ 成交订单数 = 50.00 ÷ 1 = 50.00
    assertThat(value(row(response, "SKU-RED-S"), ReportingProductSalesCatalog.PRODUCT_ORDER_AVG_AMOUNT))
        .isEqualByComparingTo("50.0000");
  }

  @Test
  void 订单数按订单去重() {
    refreshAll();
    var response = sales.report(tenant, DAY1, DAY2, "SKU", false, null, null, null, 0, 50);

    // 🔴 SO-4 里蓝杯 S 出现**两行** ⇒ 销量 2 件，但订单数只算 **1 单**。
    var blue = row(response, "SKU-BLUE-S");
    assertThat(value(blue, ReportingProductSalesCatalog.PRODUCT_SALES_QUANTITY))
        .as("蓝杯 S 销量 = 3 + 1 + 1 = 5 件").isEqualByComparingTo("5");
    assertThat(value(blue, ReportingProductSalesCatalog.PRODUCT_ORDER_COUNT))
        .as("蓝杯 S 出现在 SO-2 与 SO-4 两张单里 ⇒ 2 单（不是 3 单）").isEqualByComparingTo("2");
  }

  @Test
  void 跨币种不相加_件数与单数给跨币种合计() {
    refreshAll();
    var response = sales.report(tenant, DAY1, DAY2, "SKU", false, null, null, null, 0, 50);

    var redL = row(response, "SKU-RED-L");
    // 金额按币种分别列：USD 30.00 / EUR 56.00
    assertThat(amountOf(redL, "USD")).isEqualByComparingTo("30.00");
    assertThat(amountOf(redL, "EUR")).isEqualByComparingTo("56.00");
    // 件数与货币无关 ⇒ 给一个跨币种合计（30.00 的 1 件 + 56.00 的 2 件 = 3 件）
    assertThat(value(redL, ReportingProductSalesCatalog.PRODUCT_SALES_QUANTITY))
        .as("红杯 L 的跨币种总销量 = 3 件").isEqualByComparingTo("3");
  }

  @Test
  void 钻取能钻到订单号与SKU() {
    refreshAll();
    var response =
        sales.drilldown(tenant, DAY1, DAY2, "SKU", "SKU-BLUE-S", null, 0, 50);

    // SO-2 一行 + SO-4 两行 = 3 条构成明细（🔴 一单两行**不被合并**）。
    assertThat(response.total()).isEqualTo(3);
    assertThat(response.rows()).hasSize(3);
    assertThat(response.rows()).extracting(r -> r.orderNo()).containsExactlyInAnyOrder("SO-2", "SO-4", "SO-4");
    assertThat(response.rows()).allSatisfy(r -> assertThat(r.skuCode()).isEqualTo("SKU-BLUE-S"));
    assertThat(response.skuCodes()).containsExactly("SKU-BLUE-S");
  }

  @Test
  void 下单时刻按瞬间往返_不偏八小时() {
    refreshAll();
    var response = sales.drilldown(tenant, DAY1, DAY2, "SKU", "SKU-RED-L", null, 0, 50);

    var so3 = response.rows().stream().filter(r -> r.orderNo().equals("SO-3")).findFirst().orElseThrow();
    // 🔴 上游给的是 2026-10-02T01:00+08:00。写进去再读回来必须是**同一个瞬间** ——
    //   用 Timestamp.valueOf(LocalDateTime) 会静默偏 −28800s（这里就会读成 2026-10-02T01:00Z）。
    assertThat(so3.occurredAt())
        .as("下单时刻必须按瞬间往返，不能偏 8 小时")
        .isNotNull();
    assertThat(so3.occurredAt().toInstant())
        .isEqualTo(Instant.parse("2026-10-01T17:00:00Z"));
  }

  @Test
  void 分桶日按所用时区_并记下那个时区() throws Exception {
    // 🔴 SO-3 的下单时刻是 2026-10-02T01:00+08:00 = 2026-10-01T17:00Z。
    //   按 UTC 分桶落在 10-01；按东八区分桶落在 10-02。
    refresh.refresh(tenant, BEARER, DAY1, DAY2, ZoneId.of("Asia/Shanghai"));
    var shanghai = bucketOf("SKU-RED-L", "EUR");
    assertThat(shanghai).as("东八区下 SO-3 落在 10-02").isEqualTo(DAY2);
    assertThat(timezoneOf("SKU-RED-L"))
        .as("事实行必须记下「算这个分桶日时用的那个时区」").isEqualTo("Asia/Shanghai");

    reset();
    refresh.refresh(tenant, BEARER, DAY1, DAY2, ZoneId.of("UTC"));
    var utc = bucketOf("SKU-RED-L", "EUR");
    assertThat(utc).as("UTC 下 SO-3 落在 10-01").isEqualTo(DAY1);
    assertThat(timezoneOf("SKU-RED-L")).isEqualTo("UTC");
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
    var response = sales.report(tenant, DAY1, DAY2, "SKU", false, null, null, null, 0, 50);
    assertThat(response.rows()).isEmpty();
    assertThat(response.status()).isEqualTo("NOT_PROJECTED");
    assertThat(response.emptyReason()).isEqualTo("NO_FACTS");
    assertThat(response.emptyNote()).isNotBlank();
    assertThat(response.dataUpdatedAt())
        .as("空表时数据更新时间必须是 null（不是一个长得像时间的假值）").isNull();
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
  void 指标口径目录给出分子分母层与币种口径() {
    var definitions = sales.metricDefinitions(tenant);
    var share =
        definitions.stream()
            .filter(d -> d.metricCode().equals(ReportingProductSalesCatalog.PRODUCT_VARIANT_SHARE))
            .findFirst()
            .orElseThrow();
    assertThat(share.numeratorDimensionType()).as("变体占比的分子在 SKU 层").isEqualTo("SKU");
    assertThat(share.denominatorDimensionType()).as("变体占比的分母在 SPU 层").isEqualTo("SPU");
    assertThat(share.currencyScope()).as("件数口径允许跨币种合计").isEqualTo("ANY");

    var amount =
        definitions.stream()
            .filter(d -> d.metricCode().equals(ReportingProductSalesCatalog.PRODUCT_SALES_AMOUNT))
            .findFirst()
            .orElseThrow();
    assertThat(amount.currencyScope()).as("金额口径绝不跨币种").isEqualTo("SAME");
  }

  @Test
  void HTTP端点返回三层报表与汇总() throws Exception {
    refreshAll();

    http.perform(
            get("/reporting/product-sales")
                .header("Authorization", BEARER)
                .param("from", DAY1.toString())
                .param("to", DAY2.toString())
                .param("level", "SKU"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rows").isArray())
        .andExpect(jsonPath("$.rows.length()").value(3))
        .andExpect(jsonPath("$.rows[0].dimensionType").value("SKU"))
        .andExpect(jsonPath("$.unattributed.quantity").exists())
        .andExpect(jsonPath("$.dataUpdatedAt").exists());

    http.perform(
            get("/reporting/product-sales/spu-summary")
                .header("Authorization", BEARER)
                .param("from", DAY1.toString())
                .param("to", DAY2.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rows.length()").value(2))
        .andExpect(jsonPath("$.rows[0].variants").isArray());

    http.perform(
            get("/reporting/product-sales/variants")
                .header("Authorization", BEARER)
                .param("from", DAY1.toString())
                .param("to", DAY2.toString())
                .param("spuKey", "SPU-CUP"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.variants.length()").value(2))
        .andExpect(jsonPath("$.totalQuantity").exists());

    http.perform(
            get("/reporting/product-sales/drilldown")
                .header("Authorization", BEARER)
                .param("from", DAY1.toString())
                .param("to", DAY2.toString())
                .param("level", "SKU")
                .param("dimensionKey", "SKU-BLUE-S"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(3))
        .andExpect(jsonPath("$.rows.length()").value(3));

    http.perform(get("/reporting/product-sales/capabilities").header("Authorization", BEARER))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.upstreamModules").isNotEmpty())
        .andExpect(jsonPath("$.notDelivered").isNotEmpty());

    http.perform(get("/reporting/product-sales/metrics").header("Authorization", BEARER))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.metricCode=='PRODUCT_VARIANT_SHARE')].numeratorDimensionType")
            .value("SKU"));
  }

  @Test
  void 非法维度层报400而不是静默回落() throws Exception {
    http.perform(
            get("/reporting/product-sales")
                .header("Authorization", BEARER)
                .param("level", "PLATFORM"))
        .andExpect(status().isBadRequest());
  }

  // ==========================================================================
  // 工具
  // ==========================================================================

  private List<ReportingProductSalesModels.ProductRefreshResult> refreshAll() {
    return refresh.refresh(tenant, BEARER, DAY1, DAY2, ZONE);
  }

  private int factCount() {
    return tenants.run(tenant, () -> sql.fetch("select id from reporting_product_metric_fact").size());
  }

  private int dimensionCount(String dimensionType) {
    return tenants.run(
        tenant,
        () ->
            sql.fetch(
                    "select id from reporting_product_dimension where dimension_type = ?", dimensionType)
                .size());
  }

  private LocalDate bucketOf(String dimensionKey, String currency) {
    return tenants.run(
        tenant,
        () -> {
          var row =
              sql.fetchOne(
                  "select bucket_date from reporting_product_metric_fact"
                      + " where dimension_key = ? and currency = ?"
                      + " and metric_code = ? limit 1",
                  dimensionKey,
                  currency,
                  ReportingProductSalesCatalog.PRODUCT_SALES_AMOUNT);
          return row == null ? null : row.get(0, LocalDate.class);
        });
  }

  private String timezoneOf(String dimensionKey) {
    return tenants.run(
        tenant,
        () -> {
          var row =
              sql.fetchOne(
                  "select timezone from reporting_product_metric_fact where dimension_key = ? limit 1",
                  dimensionKey);
          return row == null ? null : row.get(0, String.class);
        });
  }

  private static ReportingProductSalesModels.ProductRow row(
      ReportingProductSalesModels.ProductSalesResponse response, String dimensionKey) {
    return response.rows().stream()
        .filter(r -> r.dimensionKey().equals(dimensionKey))
        .findFirst()
        .orElseThrow(() -> new AssertionError("报表里没有维度 " + dimensionKey));
  }

  private static ReportingProductSalesModels.VariantRow variant(
      ReportingProductSalesModels.VariantDistributionResponse response, String skuKey) {
    return response.variants().stream()
        .filter(v -> v.skuKey().equals(skuKey))
        .findFirst()
        .orElseThrow(() -> new AssertionError("变体分布里没有 SKU " + skuKey));
  }

  /** 取某个指标在这行里的取值（可能为 null = 算不出来）。 */
  private static BigDecimal value(
      ReportingProductSalesModels.ProductRow row, String metricCode) {
    for (var metric : row.metrics()) {
      if (metric.metricCode().equals(metricCode)) return metric.value();
    }
    throw new AssertionError("这一行里没有指标 " + metricCode);
  }

  /** 取某个币种的成交金额（null = 这个币种没有事实）。 */
  private static BigDecimal amountOf(
      ReportingProductSalesModels.ProductRow row, String currency) {
    for (var metric : row.metrics()) {
      if (!metric.metricCode().equals(ReportingProductSalesCatalog.PRODUCT_SALES_AMOUNT)) continue;
      if (currency.equals(metric.currency())) return metric.value();
    }
    return null;
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

  private static Map<String, Object> pageBody(String field, List<Map<String, Object>> rows) {
    var body = new LinkedHashMap<String, Object>();
    body.put(field, rows);
    body.put("page", 0);
    body.put("size", 200);
    body.put("total", rows.size());
    return body;
  }

  /** 销售订单列表：按 orderedFrom / orderedTo 过滤（端口发的是 ISO 时刻）。 */
  private static Map<String, Object> ordersBody(com.sun.net.httpserver.HttpExchange e) {
    var query = query(e.getRequestURI().getRawQuery());
    var from = parseInstant(query.get("orderedFrom"));
    var to = parseInstant(query.get("orderedTo"));
    var matched = new ArrayList<Map<String, Object>>();
    for (var row : ORDERS) {
      var at = parseInstant(String.valueOf(row.get("submittedAt")));
      if (at == null) continue;
      if (from != null && at.isBefore(from)) continue;
      if (to != null && !at.isBefore(to)) continue;
      matched.add(row);
    }
    return pageBody("rows", matched);
  }

  /**
   * 解析 ISO 时刻。
   *
   * <p>⚠️ **必须同时认得「Z」形式与「+08:00」偏移形式** —— {@code Instant.parse} 只认前者，
   * 夹具里的下单时刻带东八区偏移，用 Instant.parse 会抛异常 ⇒ 假上游的连接被直接掐断，
   * 客户端看到的是「上游不可达」而不是「这一单不在窗口里」。
   */
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

  /** 订单明细：按路径里的 uuid 找订单。 */
  private static Map<String, Object> detailBody(com.sun.net.httpserver.HttpExchange e) {
    var path = e.getRequestURI().getPath();
    var id = path.substring(path.lastIndexOf('/') + 1);
    for (var row : ORDERS) {
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

  private static Map<String, Object> sku(
      UUID id,
      String code,
      String name,
      UUID spuId,
      String spuCode,
      String spuName,
      String variantSignature,
      String status) {
    var m = new LinkedHashMap<String, Object>();
    m.put("id", id.toString());
    m.put("code", code);
    m.put("name", name);
    m.put("spuId", spuId == null ? null : spuId.toString());
    m.put("spuCode", spuCode);
    m.put("spuName", spuName);
    m.put("variantSignature", variantSignature);
    m.put("status", status);
    return m;
  }

  private static Map<String, Object> listing(
      UUID id,
      String shopCode,
      String shopName,
      String platformCode,
      String externalItemId,
      String title,
      String mappingStatus,
      List<UUID> internalSkuIds) {
    var m = new LinkedHashMap<String, Object>();
    m.put("id", id.toString());
    m.put("shopCode", shopCode);
    m.put("shopName", shopName);
    m.put("platformCode", platformCode);
    m.put("marketplaceCode", platformCode);
    m.put("externalItemId", externalItemId);
    m.put("title", title);
    m.put("status", "ACTIVE");
    m.put("mappingStatus", mappingStatus);
    m.put("internalSkuIds", internalSkuIds.stream().map(UUID::toString).toList());
    return m;
  }

  private static Map<String, Object> order(
      String orderNo,
      LocalDate day,
      String currency,
      OffsetDateTime submittedAt,
      List<Map<String, Object>> lines) {
    var m = new LinkedHashMap<String, Object>();
    m.put("id", UUID.nameUUIDFromBytes(orderNo.getBytes(StandardCharsets.UTF_8)).toString());
    m.put("orderNo", orderNo);
    m.put("orderSource", "AMAZON");
    m.put("currency", currency);
    m.put("grandTotalAmount", new BigDecimal("100.00"));
    m.put("lineCount", lines.size());
    m.put("submittedAt", submittedAt.toString());
    m.put("createdAt", submittedAt.toString());
    m.put("lines", lines);
    return m;
  }

  private static Map<String, Object> line(
      String skuCode, String skuName, String quantity, String unitPrice, String lineAmount) {
    var m = new LinkedHashMap<String, Object>();
    m.put("skuCode", skuCode);
    m.put("skuName", skuName);
    m.put("quantity", new BigDecimal(quantity));
    m.put("unitPrice", new BigDecimal(unitPrice));
    m.put("lineAmount", new BigDecimal(lineAmount));
    return m;
  }
}
