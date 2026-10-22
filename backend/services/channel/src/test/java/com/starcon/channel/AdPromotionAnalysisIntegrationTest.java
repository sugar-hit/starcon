package com.starcon.channel;

import static com.starcon.channel.AdPromotionAnalysisModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 14.4.2.1 促销效果分析。
 *
 * <p>本测试类钉住本模块最容易做丢的几件事：
 *
 * <ol>
 *   <li><b>多租户三断言</b>（AGENTS.md 硬性要求，缺一不可）：两租户互不可见、伪造 tenant header 无效、
 *       连接池归还后不串租户
 *   <li><b>🔴 判定看毛利不看销售额</b>：销售额涨了但毛利率跌了 ⇒ 判定必须是 DECREMENTAL
 *       且 {@code marginAlert} 为真 —— 这是蓝图加粗句「促销期间毛利可能大幅下滑，
 *       评估必须看毛利而不是销售额」的直接落点
 *   <li><b>🔴 不可比不是 0</b>：没登记成本 ⇒ 毛利为 <b>null</b>；BOGO 类型 ⇒ 补贴为 <b>null</b>；
 *       两者都**不是 0**
 *   <li><b>🔴 ROI 分子分母各自落库</b>：从库里读回 {@code roi_numerator} / {@code roi_denominator}，
 *       自己除一遍必须等于 {@code roi_value}（含负 ROI）
 *   <li><b>🔴 基期口径</b>：基期 = 促销首日之前**等长且紧邻**的一段，从库里读回日期断言
 *   <li><b>🔴 活动维度是真聚合</b>：Σ口径，不是各商品比率的平均；一个商品不可比 ⇒ 活动该指标为 null
 *   <li><b>🔴 同一天多来源不相加</b>：PLATFORM 与 API 两条来源只取最近收到的一条
 *   <li><b>真分页 / 真筛选 / 真排序</b>（默认按毛利排序）；导出留痕 + CSV
 *   <li><b>跳过不许静默消失</b>：没起止时间的促销记进运行留痕的 skipped
 * </ol>
 *
 * <p><b>时刻一律用固定的 {@link OffsetDateTime} 钉死</b>，不用 {@code now()}：用墙钟会让
 * 「这次写入落在哪个断言分支上」成为一个 flaky 断言。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AdPromotionAnalysisIntegrationTest {

  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID user = UUID.randomUUID();
  static HttpServer identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  /** 🔴 每个用例一对**全新的租户**（共用租户会让前一个用例的数据被后一个用例数进去）。 */
  UUID a, b;
  AccessService.Access actorA, actorB;
  String bearerA;

  // ==================================================================
  // 钉死的常量：促销窗口、基期窗口、金额
  // ==================================================================

  /** 促销期：2026-10-10 ~ 2026-10-12（3 天）。 */
  static final OffsetDateTime PROMO_START = OffsetDateTime.parse("2026-10-10T00:00:00Z");
  static final OffsetDateTime PROMO_END = OffsetDateTime.parse("2026-10-12T23:59:59Z");

  /** 基期：2026-10-07 ~ 2026-10-09（等长 3 天，紧邻促销首日之前）。 */
  static final String BASELINE_FROM = "2026-10-07";
  static final String BASELINE_TO = "2026-10-09";

  static final String CURRENCY = "USD";

  static final UUID AMAZON = UUID.fromString("11111111-1111-1111-1111-111111111111");
  static final UUID MARKETPLACE_UTC =
      UUID.fromString("22222222-2222-2222-2222-222222222222");
  static final String ALL_SCOPE_GRANTS =
      "[{\"permissions\":[\"channel.operation.read\",\"channel.operation.create\","
          + "\"channel.operation.update\",\"channel.operation.delete\"],\"scopeMode\":\"ALL\"}]";

  static final List<String> allPermissions =
      java.util.stream.Stream.of("registry", "connection", "shop", "sync", "operation")
          .flatMap(
              p ->
                  java.util.stream.Stream.concat(
                      java.util.stream.Stream.of("read", "create", "update", "delete")
                          .map(op -> "channel." + p + "." + op),
                      p.equals("sync")
                          ? java.util.stream.Stream.of("channel.sync.trigger", "channel.sync.complete")
                          : java.util.stream.Stream.empty()))
          .distinct()
          .toList();

  static AccessService.Access actor(UUID uid, UUID tenant, List<AccessService.Grant> grants) {
    return new AccessService.Access(
        uid, tenant, "Test", UUID.randomUUID(), null,
        grants.stream().flatMap(g -> g.permissions().stream()).distinct().toList(),
        grants);
  }

  static AccessService.Grant grant(String scope, List<String> permissions) {
    return new AccessService.Grant(UUID.randomUUID(), "TEST", scope, permissions);
  }

  static {
    pg.start();
    try {
      try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
          var s = c.createStatement()) {
        s.execute("do $$ begin if not exists(select 1 from pg_roles where rolname='starcon_app')"
            + " then create role starcon_app nologin; end if; end $$");
        s.execute("do $$ begin if not exists(select 1 from pg_roles where rolname='channel_app')"
            + " then create role channel_app login password 'test-only' nosuperuser nobypassrls; end if; end $$");
        s.execute("alter role channel_app login password 'test-only' nosuperuser nobypassrls");
      }
      identity = HttpServer.create(new InetSocketAddress(0), 0);
      identity.createContext(
          "/internal/access",
          e -> {
            AccessService.Access who = actors.get(e.getRequestHeaders().getFirst("Authorization"));
            if (who == null) {
              e.sendResponseHeaders(401, -1);
              e.close();
              return;
            }
            byte[] body = new ObjectMapper().writeValueAsBytes(who);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      List<AccessService.Grant> grants = List.of(grant("ALL", allPermissions));
      identity.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @BeforeEach
  void freshTenants() {
    a = UUID.randomUUID();
    b = UUID.randomUUID();
    bearerA = "Bearer t-a-" + a;
    String bearerB = "Bearer t-b-" + b;
    List<AccessService.Grant> grants = List.of(grant("ALL", allPermissions));
    actors.put(bearerA, actor(user, a, grants));
    actors.put(bearerB, actor(user, b, grants));
    actorA = actors.get(bearerA);
    actorB = actors.get(bearerB);
  }

  /** 播种全局码表（平台目录 + 站点）。刻意**不在静态块里**播种（那时表还没建）。 */
  @BeforeEach
  void seedGlobalCatalog() {
    exec("insert into platform (id,code,name,integration,support_status,position)"
        + " values ('" + AMAZON + "','AMAZON','Amazon','OFFICIAL_API','AVAILABLE',1)"
        + " on conflict (id) do nothing");
    exec("insert into marketplace (id,platform_id,code,name,country_code,default_currency,"
        + " default_timezone,default_locale,currency_scale,position)"
        + " values ('" + MARKETPLACE_UTC + "','" + AMAZON + "','ANALYSIS-UTC','分析测试站点','US','"
        + CURRENCY + "','UTC','en-US',2,99)"
        + " on conflict (id) do nothing");
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "channel_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://localhost:" + identity.getAddress().getPort());
    r.add("CATALOG_URL", () -> "http://127.0.0.1:1");
    r.add("INTERNAL_KEY", () -> "test-key");
    // 连接池压到 1：多租户串租户在单连接下最容易暴露。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> "1");
  }

  @Autowired AdPromotionAnalysisService service;
  @Autowired AccessService access;
  @Autowired MockMvc http;

  // ==================================================================
  // 辅助
  // ==================================================================

  <T> T as(AccessService.Access who, String permission, java.util.function.Supplier<T> work) {
    return access.run(who, permission, work);
  }

  /** 以管理员（超级用户，RLS 不生效）连接执行 SQL。 */
  static void exec(String sql) {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(sql);
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  /** 以「已认证的租户操作者」身份执行 SQL：绑定租户 + actor + 权限 + 数据范围。 */
  static void execAsTenant(UUID tenant, String sql) {
    try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute("select set_config('app.current_tenant','" + tenant + "',false)");
      s.execute("select set_config('app.actor','" + user + "',false)");
      s.execute("select set_config('app.department','',false)");
      s.execute("select set_config('app.permission','channel.operation.create',false)");
      s.execute("select set_config('app.grants','" + ALL_SCOPE_GRANTS + "',false)");
      s.execute(sql);
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  UUID seedShop(UUID tenant, String code) {
    UUID id = UUID.randomUUID();
    execAsTenant(tenant,
        "insert into shop (tenant_id,id,platform_id,code,name) values ('" + tenant + "','" + id
            + "','" + AMAZON + "','" + code + "','测试店铺 " + code + "')"
            + " on conflict (tenant_id,code) do nothing");
    return id;
  }

  UUID seedListing(UUID tenant, UUID shopId, String externalItemId, String title) {
    UUID id = UUID.randomUUID();
    execAsTenant(tenant,
        "insert into listing (tenant_id,id,shop_id,marketplace_id,external_item_id,title)"
            + " values ('" + tenant + "','" + id + "','" + shopId + "','" + MARKETPLACE_UTC
            + "','" + externalItemId + "','" + title + "')");
    return id;
  }

  /** 播种一次平台表现同步批次（快照必须挂在批次上，DB 触发器钉死）。 */
  UUID seedSync(UUID tenant, UUID listingId, String key) {
    UUID id = UUID.randomUUID();
    execAsTenant(tenant,
        "insert into listing_performance_sync (tenant_id,id,listing_id,source,ingest_channel,"
            + " period_start,period_end,stat_from,stat_to,received_count,status,idempotency_key)"
            + " values ('" + tenant + "','" + id + "','" + listingId + "','PLATFORM','POLL',"
            + " '2026-10-01T00:00:00Z','2026-10-31T00:00:00Z','2026-10-01','2026-10-31',1,"
            + " 'RECEIVED','" + key + "')");
    return id;
  }

  /** 播种一天的平台表现快照。 */
  void seedSnapshot(
      UUID tenant,
      UUID listingId,
      UUID syncId,
      String statDate,
      String units,
      String sales,
      String source,
      String receivedAt) {
    execAsTenant(tenant,
        "insert into listing_performance_snapshot (tenant_id,id,listing_id,stat_date,units_ordered,"
            + " sales_amount,currency,source,ingest_channel,last_sync_id,received_at)"
            + " values ('" + tenant + "','" + UUID.randomUUID() + "','" + listingId + "','" + statDate
            + "'," + units + "," + sales + ",'" + CURRENCY + "','" + source + "','POLL','" + syncId
            + "','" + receivedAt + "')");
  }

  /** 播种促销 + 适用商品。 */
  record Seeded(UUID promotionId, UUID listingId) {}

  Seeded seedPromotion(
      UUID tenant,
      String shopCode,
      String code,
      String type,
      String rate,
      String amount,
      UUID listingId,
      OffsetDateTime startAt,
      OffsetDateTime endAt) {
    UUID promotionId = UUID.randomUUID();
    execAsTenant(tenant,
        "insert into ad_promotion (tenant_id,id,platform_id,shop_code,promotion_code,promotion_name,"
            + " promotion_type,discount_rate,discount_amount,currency,start_at,end_at,status)"
            + " values ('" + tenant + "','" + promotionId + "','" + AMAZON + "','" + shopCode
            + "','" + code + "','促销 " + code + "','" + type + "',"
            + (rate == null ? "null" : rate) + ","
            + (amount == null ? "null" : amount) + ",'" + CURRENCY + "',"
            + (startAt == null ? "null" : "'" + startAt + "'") + ","
            + (endAt == null ? "null" : "'" + endAt + "'") + ",'ACTIVE')");
    if (listingId != null) {
      execAsTenant(tenant,
          "insert into ad_promotion_product (tenant_id,id,promotion_id,listing_id,base_price,currency)"
              + " values ('" + tenant + "','" + UUID.randomUUID() + "','" + promotionId + "','"
              + listingId + "',20.0000,'" + CURRENCY + "')");
    }
    return new Seeded(promotionId, listingId);
  }

  /** 登记成本与售价快照（走真写入口）。 */
  AdPromotionAnalysisCostSnapshotRow saveCost(
      AccessService.Access who, UUID promotionId, UUID listingId, String unitCost, String basePrice) {
    return as(
        who,
        "channel.operation.update",
        () ->
            service.saveCostSnapshot(
                new SaveCostSnapshotRequest(
                    promotionId,
                    listingId,
                    new BigDecimal(unitCost),
                    basePrice == null ? null : new BigDecimal(basePrice),
                    CURRENCY,
                    "catalog:landed_cost#" + unitCost,
                    OffsetDateTime.parse("2026-10-01T00:00:00Z"),
                    "测试登记")));
  }

  AdPromotionAnalysisPage report(AccessService.Access who, AdPromotionAnalysisQuery q) {
    return as(who, "channel.operation.read", () -> service.report(q));
  }

  AdPromotionAnalysisRunRow run(AccessService.Access who, UUID promotionId) {
    return as(
        who,
        "channel.operation.update",
        () -> service.runAnalysis(new RunAnalysisRequest(promotionId, "测试运行")));
  }

  AdPromotionAnalysisQuery query(String dimension, String sort) {
    return new AdPromotionAnalysisQuery(
        dimension, sort, null, null, null, null, null, null, null, null, null, 0, 50);
  }

  /** 从库里读回效果结果行（**绕过服务层**，确保断言的是真正落库的值）。 */
  record RawEffect(
      OffsetDateTime periodFrom,
      OffsetDateTime periodTo,
      int periodDays,
      int coveredDays,
      BigDecimal promoUnits,
      BigDecimal promoSales,
      BigDecimal promoCost,
      BigDecimal promoGrossProfit,
      BigDecimal promoGrossMarginRate,
      BigDecimal incrementUnits,
      BigDecimal incrementSales,
      BigDecimal incrementGrossProfit,
      BigDecimal subsidyUnit,
      BigDecimal subsidyCost,
      BigDecimal roiNumerator,
      BigDecimal roiDenominator,
      BigDecimal roiValue,
      String verdict,
      boolean marginAlert,
      OffsetDateTime dataAsOf) {}

  RawEffect rawEffect(UUID tenant, UUID promotionId, UUID listingId) {
    try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var ps =
            c.prepareStatement(
                "select e.period_from_at, e.period_to_at, e.period_days, e.period_covered_days,"
                    + " e.promo_units, e.promo_sales, e.promo_cost, e.promo_gross_profit,"
                    + " e.promo_gross_margin_rate, e.increment_units, e.increment_sales,"
                    + " e.increment_gross_profit, e.subsidy_unit, e.subsidy_cost, e.roi_numerator,"
                    + " e.roi_denominator, e.roi_value, e.increment_verdict, e.margin_alert, e.data_as_of"
                    + " from ad_promotion_effect e"
                    + " where e.tenant_id = ? and e.promotion_id = ? and e.listing_id = ?")) {
      ps.setObject(1, tenant);
      ps.setObject(2, promotionId);
      ps.setObject(3, listingId);
      try (var rs = ps.executeQuery()) {
        if (rs.next()) {
          return new RawEffect(
              rs.getObject("period_from_at", OffsetDateTime.class),
              rs.getObject("period_to_at", OffsetDateTime.class),
              rs.getInt("period_days"),
              rs.getInt("period_covered_days"),
              rs.getBigDecimal("promo_units"),
              rs.getBigDecimal("promo_sales"),
              rs.getBigDecimal("promo_cost"),
              rs.getBigDecimal("promo_gross_profit"),
              rs.getBigDecimal("promo_gross_margin_rate"),
              rs.getBigDecimal("increment_units"),
              rs.getBigDecimal("increment_sales"),
              rs.getBigDecimal("increment_gross_profit"),
              rs.getBigDecimal("subsidy_unit"),
              rs.getBigDecimal("subsidy_cost"),
              rs.getBigDecimal("roi_numerator"),
              rs.getBigDecimal("roi_denominator"),
              rs.getBigDecimal("roi_value"),
              rs.getString("increment_verdict"),
              rs.getBoolean("margin_alert"),
              rs.getObject("data_as_of", OffsetDateTime.class));
        }
      }
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
    throw new IllegalStateException("效果结果不存在：" + promotionId + " / " + listingId);
  }

  /** 从库里读回基期快照的窗口（自然日 → 时刻）。 */
  record RawBaseline(
      OffsetDateTime windowFrom,
      OffsetDateTime windowTo,
      int windowDays,
      BigDecimal baselineUnits,
      BigDecimal baselineSales,
      BigDecimal baselineCost,
      BigDecimal baselineGrossProfit,
      int coveredDays,
      BigDecimal dailyUnits,
      BigDecimal dailySales) {}

  RawBaseline rawBaseline(UUID tenant, UUID promotionId, UUID listingId) {
    try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var ps =
            c.prepareStatement(
                "select window_from_at, window_to_at, window_days, baseline_units, baseline_sales,"
                    + " baseline_cost, baseline_gross_profit, baseline_covered_days,"
                    + " baseline_daily_units, baseline_daily_sales"
                    + " from ad_promotion_effect_baseline"
                    + " where tenant_id = ? and promotion_id = ? and listing_id = ?")) {
      ps.setObject(1, tenant);
      ps.setObject(2, promotionId);
      ps.setObject(3, listingId);
      try (var rs = ps.executeQuery()) {
        if (rs.next()) {
          return new RawBaseline(
              rs.getObject("window_from_at", OffsetDateTime.class),
              rs.getObject("window_to_at", OffsetDateTime.class),
              rs.getInt("window_days"),
              rs.getBigDecimal("baseline_units"),
              rs.getBigDecimal("baseline_sales"),
              rs.getBigDecimal("baseline_cost"),
              rs.getBigDecimal("baseline_gross_profit"),
              rs.getInt("baseline_covered_days"),
              rs.getBigDecimal("baseline_daily_units"),
              rs.getBigDecimal("baseline_daily_sales"));
        }
      }
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
    throw new IllegalStateException("基期快照不存在：" + promotionId + " / " + listingId);
  }

  /**
   * 主场景：一个促销挂两个商品。
   *
   * <ul>
   *   <li>L1：促销期销售额涨了，但**毛利率跌到负数** ⇒ 判定必须 DECREMENTAL + marginAlert
   *   <li>L2：促销期毛利明显上升 ⇒ 判定 INCREMENTAL
   * </ul>
   */
  record Scenario(UUID shopId, UUID listingDown, UUID listingUp, UUID promotionId) {}

  Scenario seedMainScenario(AccessService.Access who, UUID tenant, String suffix) {
    UUID shopId = seedShop(tenant, "PA-" + suffix + "-AMZ");
    UUID listingDown = seedListing(tenant, shopId, "EXT-" + suffix + "-DOWN", "销量涨毛利跌的商品");
    UUID listingUp = seedListing(tenant, shopId, "EXT-" + suffix + "-UP", "毛利上升的商品");

    UUID syncDown = seedSync(tenant, listingDown, "sync-" + suffix + "-down");
    for (String d : List.of("2026-10-07", "2026-10-08", "2026-10-09")) {
      seedSnapshot(tenant, listingDown, syncDown, d, "10", "100.0000", "PLATFORM", "2026-10-10T02:00:00Z");
    }
    for (String d : List.of("2026-10-10", "2026-10-11", "2026-10-12")) {
      seedSnapshot(tenant, listingDown, syncDown, d, "20", "150.0000", "PLATFORM", "2026-10-13T02:00:00Z");
    }

    UUID syncUp = seedSync(tenant, listingUp, "sync-" + suffix + "-up");
    for (String d : List.of("2026-10-07", "2026-10-08", "2026-10-09")) {
      seedSnapshot(tenant, listingUp, syncUp, d, "10", "100.0000", "PLATFORM", "2026-10-10T02:00:00Z");
    }
    for (String d : List.of("2026-10-10", "2026-10-11", "2026-10-12")) {
      seedSnapshot(tenant, listingUp, syncUp, d, "20", "300.0000", "PLATFORM", "2026-10-13T02:00:00Z");
    }

    Seeded seeded =
        seedPromotion(
            tenant,
            "PA-" + suffix + "-AMZ",
            "P-" + suffix,
            "PERCENT_OFF",
            "0.200000",
            null,
            listingDown,
            PROMO_START,
            PROMO_END);
    // 第二个商品挂到同一个促销上（多对多）。
    execAsTenant(tenant,
        "insert into ad_promotion_product (tenant_id,id,promotion_id,listing_id,base_price,currency)"
            + " values ('" + tenant + "','" + UUID.randomUUID() + "','" + seeded.promotionId() + "','"
            + listingUp + "',20.0000,'" + CURRENCY + "')");

    saveCost(who, seeded.promotionId(), listingDown, "8.0000", "20.0000");
    saveCost(who, seeded.promotionId(), listingUp, "8.0000", "20.0000");
    return new Scenario(shopId, listingDown, listingUp, seeded.promotionId());
  }

  // ==================================================================
  // 1. 能力声明
  // ==================================================================

  @Test
  @DisplayName("14.4.2.1 能力声明：三个未交付维度必须显式 false + ownerModule")
  void capabilitiesDeclareUnavailableDimensions() {
    var caps = as(actorA, "channel.operation.read", () -> service.capabilities());
    assertThat(caps.dimensions()).isNotEmpty();
    assertThat(caps.dimensions())
        .filteredOn(d -> d.dimension().equals("platform_attribution"))
        .singleElement()
        .satisfies(
            d -> {
              assertThat(d.available()).isFalse();
              assertThat(d.ownerModule()).contains("平台促销 API");
              assertThat(d.impact()).contains("不是");
            });
    assertThat(caps.dimensions())
        .filteredOn(d -> d.dimension().equals("profit_report"))
        .singleElement()
        .satisfies(
            d -> {
              assertThat(d.available()).isFalse();
              assertThat(d.ownerModule()).contains("13.4");
            });
    assertThat(caps.dimensions())
        .filteredOn(d -> d.dimension().equals("fx"))
        .singleElement()
        .satisfies(
            d -> {
              assertThat(d.available()).isFalse();
              assertThat(d.ownerModule()).contains("12 财务");
            });
    // 🔴 已交付的维度不能被写成不可用
    assertThat(caps.dimensions())
        .filteredOn(d -> List.of("sales", "margin", "increment", "subsidy", "roi").contains(d.dimension()))
        .allMatch(d -> d.available());
    // 🔴 判定口径必须写明「看毛利」
    assertThat(caps.incrementNote()).contains("毛利").contains("不看销售额");
  }

  // ==================================================================
  // 2. 🔴 判定看毛利：销售额涨了但毛利下滑
  // ==================================================================

  @Test
  @DisplayName("14.4.2.1 🔴 促销期毛利下滑：销售额涨了但判定必须是 DECREMENTAL + 放量不赚")
  void decliningMarginIsReportedAsDecremental() {
    Scenario s = seedMainScenario(actorA, a, "DOWN");
    run(actorA, s.promotionId());

    // 🔴 销售额确实涨了：促销期 450 vs 基期 300
    AdPromotionAnalysisPage products =
        report(
            actorA,
            new AdPromotionAnalysisQuery(
                "PRODUCT", null, s.promotionId(), null, null, null, null, null, null, null, null, 0, 50));
    AdPromotionAnalysisRow down =
        products.items().stream()
            .filter(r -> s.listingDown().equals(r.listingId()))
            .findFirst()
            .orElseThrow();
    assertThat(down.promoSales()).isEqualByComparingTo("450.0000");
    assertThat(down.baselineSales()).isEqualByComparingTo("300.0000");
    assertThat(down.incrementSales()).isEqualByComparingTo("150.0000");

    // 🔴 但毛利是负的（单位成本 8 × 60 件 = 480 > 销售额 450）
    assertThat(down.promoCost()).isEqualByComparingTo("480.0000");
    assertThat(down.promoGrossProfit()).isEqualByComparingTo("-30.0000");
    assertThat(down.baselineGrossProfit()).isEqualByComparingTo("60.0000");
    assertThat(down.incrementGrossProfit()).isEqualByComparingTo("-90.0000");

    // 🔴 判据是毛利 ⇒ 下滑，不是「销售额涨了所以是增量」
    assertThat(down.incrementVerdict()).isEqualTo("DECREMENTAL");
    assertThat(down.marginAlert()).isTrue();

    // 🔴 另一半商品毛利上升 ⇒ 判定是增量
    AdPromotionAnalysisRow up =
        products.items().stream()
            .filter(r -> s.listingUp().equals(r.listingId()))
            .findFirst()
            .orElseThrow();
    // 🔴 促销期 3 天 × 300.00 = 900.00 销售额；成本 8 × 60 = 480 ⇒ 毛利 420.0000
    assertThat(up.promoSales()).isEqualByComparingTo("900.0000");
    assertThat(up.promoGrossProfit()).isEqualByComparingTo("420.0000");
    assertThat(up.incrementGrossProfit()).isEqualByComparingTo("360.0000");
    assertThat(up.incrementVerdict()).isEqualTo("INCREMENTAL");
    assertThat(up.marginAlert()).isFalse();

    // 🔴 从库里读回，确认落库的值就是这些（不能只断言接口返回）
    RawEffect raw = rawEffect(a, s.promotionId(), s.listingDown());
    assertThat(raw.promoGrossProfit()).isEqualByComparingTo("-30.0000");
    assertThat(raw.incrementGrossProfit()).isEqualByComparingTo("-90.0000");
    assertThat(raw.verdict()).isEqualTo("DECREMENTAL");
    assertThat(raw.marginAlert()).isTrue();
  }

  // ==================================================================
  // 3. 🔴 ROI：分子分母各自落库，自己除一遍要对得上
  // ==================================================================

  @Test
  @DisplayName("14.4.2.1 🔴 ROI 分子分母各自落库（负 ROI 也要落得下）")
  void roiNumeratorAndDenominatorArePersisted() {
    Scenario s = seedMainScenario(actorA, a, "ROI");
    run(actorA, s.promotionId());

    RawEffect raw = rawEffect(a, s.promotionId(), s.listingDown());
    // 🔴 分子 = 增量毛利，分母 = 补贴成本（单件让利 4.00 × 60 件 = 240）
    assertThat(raw.subsidyUnit()).isEqualByComparingTo("4.0000");
    assertThat(raw.subsidyCost()).isEqualByComparingTo("240.0000");
    assertThat(raw.roiNumerator()).isEqualByComparingTo("-90.0000");
    assertThat(raw.roiDenominator()).isEqualByComparingTo("240.0000");
    // 🔴 负 ROI 是「促销把毛利做没了」最直接的读数，不许被拦成 null
    assertThat(raw.roiValue()).isEqualByComparingTo("-0.375000");
    assertThat(raw.roiValue())
        .isEqualByComparingTo(
            raw.roiNumerator().divide(raw.roiDenominator(), 6, java.math.RoundingMode.HALF_UP));
  }

  // ==================================================================
  // 4. 🔴 不可比不是 0
  // ==================================================================

  @Test
  @DisplayName("14.4.2.1 🔴 没登记成本 ⇒ 毛利是空值（不可比），不是 0")
  void missingCostMeansNullGrossProfitNotZero() {
    UUID shopId = seedShop(a, "NOCOST-AMZ");
    UUID listingId = seedListing(a, shopId, "EXT-NOCOST", "没有成本快照的商品");
    UUID syncId = seedSync(a, listingId, "sync-nocost");
    for (String d : List.of("2026-10-07", "2026-10-08", "2026-10-09", "2026-10-10", "2026-10-11",
        "2026-10-12")) {
      seedSnapshot(a, listingId, syncId, d, "10", "100.0000", "PLATFORM", "2026-10-13T02:00:00Z");
    }
    Seeded seeded =
        seedPromotion(a, "NOCOST-AMZ", "P-NOCOST", "PERCENT_OFF", "0.200000", null, listingId,
            PROMO_START, PROMO_END);
    // 🔴 故意**不**登记成本快照
    run(actorA, seeded.promotionId());

    AdPromotionAnalysisPage page =
        report(
            actorA,
            new AdPromotionAnalysisQuery(
                "PRODUCT", null, seeded.promotionId(), null, null, null, null, null, null, null, null,
                0, 50));
    AdPromotionAnalysisRow row = page.items().get(0);
    assertThat(row.promoSales()).isEqualByComparingTo("300.0000");
    // 🔴 毛利是 null，不是 0
    assertThat(row.promoGrossProfit()).isNull();
    assertThat(row.promoGrossMarginRate()).isNull();
    assertThat(row.incrementGrossProfit()).isNull();
    // 🔴 没有成本 ⇒ 补贴也不可比（百分比促销没有基准价）
    assertThat(row.subsidyUnit()).isNull();
    assertThat(row.subsidyCost()).isNull();
    assertThat(row.roiValue()).isNull();
    // 🔴 数据不足以判定
    assertThat(row.incrementVerdict()).isEqualTo("INSUFFICIENT_DATA");
    assertThat(row.grossProfitProductCount()).isZero();
    assertThat(row.comparableProductCount()).isZero();
  }

  @Test
  @DisplayName("14.4.2.1 🔴 BOGO 没有可折算价格系数 ⇒ 补贴是空值（不可比），不是 0")
  void nonPriceBearingPromotionHasNullSubsidy() {
    UUID shopId = seedShop(a, "BOGO-AMZ");
    UUID listingId = seedListing(a, shopId, "EXT-BOGO", "买赠商品");
    UUID syncId = seedSync(a, listingId, "sync-bogo");
    for (String d : List.of("2026-10-07", "2026-10-08", "2026-10-09", "2026-10-10", "2026-10-11",
        "2026-10-12")) {
      seedSnapshot(a, listingId, syncId, d, "10", "100.0000", "PLATFORM", "2026-10-13T02:00:00Z");
    }
    Seeded seeded =
        seedPromotion(a, "BOGO-AMZ", "P-BOGO", "BOGO", null, null, listingId, PROMO_START, PROMO_END);
    // 🔴 有成本有基准价，但促销类型没有可折算的价格系数
    saveCost(actorA, seeded.promotionId(), listingId, "8.0000", "20.0000");
    run(actorA, seeded.promotionId());

    AdPromotionAnalysisRow row =
        report(
                actorA,
                new AdPromotionAnalysisQuery(
                    "PRODUCT", null, seeded.promotionId(), null, null, null, null, null, null, null,
                    null, 0, 50))
            .items()
            .get(0);
    // 🔴 毛利可比（成本有），补贴不可比（类型不可折算）
    assertThat(row.promoGrossProfit()).isEqualByComparingTo("60.0000");
    assertThat(row.subsidyUnit()).isNull();
    assertThat(row.subsidyCost()).isNull();
    assertThat(row.roiDenominator()).isNull();
    assertThat(row.roiValue()).isNull();
    assertThat(row.comparableProductCount()).isZero();
  }

  // ==================================================================
  // 5. 🔴 基期口径：等长且紧邻
  // ==================================================================

  @Test
  @DisplayName("14.4.2.1 🔴 基期 = 促销首日之前等长且紧邻的一段")
  void baselineWindowIsAdjacentAndEqualLength() {
    Scenario s = seedMainScenario(actorA, a, "BASE");
    run(actorA, s.promotionId());

    RawBaseline base = rawBaseline(a, s.promotionId(), s.listingDown());
    // 🔴 基期 = 2026-10-07 ~ 2026-10-09（促销首日 10-10 往前等长 3 天）
    assertThat(base.windowFrom())
        .isEqualTo(OffsetDateTime.parse("2026-10-07T00:00:00Z"));
    assertThat(base.windowTo()).isEqualTo(OffsetDateTime.parse("2026-10-10T00:00:00Z"));
    assertThat(base.windowDays()).isEqualTo(3);
    assertThat(base.baselineUnits()).isEqualByComparingTo("30.000000");
    assertThat(base.baselineSales()).isEqualByComparingTo("300.0000");
    assertThat(base.baselineCost()).isEqualByComparingTo("240.0000");
    assertThat(base.baselineGrossProfit()).isEqualByComparingTo("60.0000");
    assertThat(base.coveredDays()).isEqualTo(3);
    // 🔴 日均值供按天口径复核
    assertThat(base.dailyUnits()).isEqualByComparingTo("10.000000");
    assertThat(base.dailySales()).isEqualByComparingTo("100.0000");

    // 🔴 促销期窗口 = 2026-10-10 ~ 2026-10-13（开区间）
    RawEffect effect = rawEffect(a, s.promotionId(), s.listingDown());
    assertThat(effect.periodFrom()).isEqualTo(OffsetDateTime.parse("2026-10-10T00:00:00Z"));
    assertThat(effect.periodTo()).isEqualTo(OffsetDateTime.parse("2026-10-13T00:00:00Z"));
    assertThat(effect.periodDays()).isEqualTo(3);
    assertThat(effect.coveredDays()).isEqualTo(3);
    assertThat(effect.dataAsOf()).isNotNull();
  }

  // ==================================================================
  // 6. 🔴 同一天多来源不相加
  // ==================================================================

  @Test
  @DisplayName("14.4.2.1 🔴 同一天的 PLATFORM 与 API 两条来源只取最近收到的一条，不相加")
  void sameDaySourcesAreNotSummed() {
    UUID shopId = seedShop(a, "TWOSRC-AMZ");
    UUID listingId = seedListing(a, shopId, "EXT-TWOSRC", "双来源商品");
    UUID syncId = seedSync(a, listingId, "sync-twosrc");
    for (String d : List.of("2026-10-07", "2026-10-08", "2026-10-09")) {
      seedSnapshot(a, listingId, syncId, d, "10", "100.0000", "PLATFORM", "2026-10-10T02:00:00Z");
    }
    for (String d : List.of("2026-10-10", "2026-10-11", "2026-10-12")) {
      seedSnapshot(a, listingId, syncId, d, "20", "150.0000", "PLATFORM", "2026-10-13T02:00:00Z");
      // 🔴 同一天再来一条 API 来源（更晚收到）—— 相加会把 20 件算成 40 件
      seedSnapshot(a, listingId, syncId, d, "20", "150.0000", "API", "2026-10-13T03:00:00Z");
    }
    Seeded seeded =
        seedPromotion(a, "TWOSRC-AMZ", "P-TWOSRC", "PERCENT_OFF", "0.200000", null, listingId,
            PROMO_START, PROMO_END);
    saveCost(actorA, seeded.promotionId(), listingId, "8.0000", "20.0000");
    run(actorA, seeded.promotionId());

    RawEffect effect = rawEffect(a, seeded.promotionId(), listingId);
    // 🔴 60 件（3 天 × 20），不是 120 件
    assertThat(effect.promoUnits()).isEqualByComparingTo("60.000000");
    assertThat(effect.promoSales()).isEqualByComparingTo("450.0000");
    assertThat(effect.coveredDays()).isEqualTo(3);
  }

  // ==================================================================
  // 7. 🔴 活动维度是真聚合
  // ==================================================================

  @Test
  @DisplayName("14.4.2.1 🔴 活动维度是真聚合（Σ口径，不是各商品比率的平均）")
  void promotionDimensionIsRealAggregation() {
    Scenario s = seedMainScenario(actorA, a, "AGG");
    run(actorA, s.promotionId());

    AdPromotionAnalysisRow agg =
        report(
                actorA,
                new AdPromotionAnalysisQuery(
                    "PROMOTION", null, s.promotionId(), null, null, null, null, null, null, null,
                    null, 0, 50))
            .items()
            .get(0);
    // 🔴 Σ口径：销售额 450 + 900 = 1350；毛利 −30 + 420 = 390
    assertThat(agg.promoSales()).isEqualByComparingTo("1350.0000");
    assertThat(agg.promoCost()).isEqualByComparingTo("960.0000");
    assertThat(agg.promoGrossProfit()).isEqualByComparingTo("390.0000");
    assertThat(agg.baselineSales()).isEqualByComparingTo("600.0000");
    assertThat(agg.baselineGrossProfit()).isEqualByComparingTo("120.0000");
    assertThat(agg.incrementGrossProfit()).isEqualByComparingTo("270.0000");
    // 🔴 毛利率按「先汇总再相除」= 390/1350 ≈ 0.288889，
    //    不是两个商品毛利率 (−0.066667 + 0.733333) / 2 = 0.333333
    assertThat(agg.promoGrossMarginRate()).isEqualByComparingTo("0.288889");
    assertThat(agg.baselineGrossMarginRate()).isEqualByComparingTo("0.200000");
    assertThat(agg.productCount()).isEqualTo(2);
    assertThat(agg.grossProfitProductCount()).isEqualTo(2);
    assertThat(agg.comparableProductCount()).isEqualTo(2);
    // 🔴 活动维度判定按聚合后的增量毛利
    assertThat(agg.incrementVerdict()).isEqualTo("INCREMENTAL");
  }

  @Test
  @DisplayName("14.4.2.1 🔴 活动里只要有一个商品不可比，该活动的对应指标就是空值（不是部分和）")
  void promotionAggregateIsNullWhenOneProductIncomparable() {
    UUID shopId = seedShop(a, "MIX-AMZ");
    UUID listingOk = seedListing(a, shopId, "EXT-MIX-OK", "有成本的商品");
    UUID listingNoCost = seedListing(a, shopId, "EXT-MIX-NO", "没成本的商品");
    for (UUID listing : List.of(listingOk, listingNoCost)) {
      UUID syncId = seedSync(a, listing, "sync-mix-" + listing);
      for (String d : List.of("2026-10-07", "2026-10-08", "2026-10-09", "2026-10-10", "2026-10-11",
          "2026-10-12")) {
        seedSnapshot(a, listing, syncId, d, "10", "100.0000", "PLATFORM", "2026-10-13T02:00:00Z");
      }
    }
    Seeded seeded =
        seedPromotion(a, "MIX-AMZ", "P-MIX", "PERCENT_OFF", "0.200000", null, listingOk, PROMO_START,
            PROMO_END);
    execAsTenant(a,
        "insert into ad_promotion_product (tenant_id,id,promotion_id,listing_id,base_price,currency)"
            + " values ('" + a + "','" + UUID.randomUUID() + "','" + seeded.promotionId() + "','"
            + listingNoCost + "',20.0000,'" + CURRENCY + "')");
    saveCost(actorA, seeded.promotionId(), listingOk, "8.0000", "20.0000");
    run(actorA, seeded.promotionId());

    AdPromotionAnalysisRow agg =
        report(
                actorA,
                new AdPromotionAnalysisQuery(
                    "PROMOTION", null, seeded.promotionId(), null, null, null, null, null, null, null,
                    null, 0, 50))
            .items()
            .get(0);
    assertThat(agg.productCount()).isEqualTo(2);
    assertThat(agg.grossProfitProductCount()).isEqualTo(1);
    // 🔴 毛利不可比 ⇒ 活动级毛利是空值，不是部分和
    assertThat(agg.promoGrossProfit()).isNull();
    assertThat(agg.incrementGrossProfit()).isNull();
    assertThat(agg.incrementVerdict()).isEqualTo("INSUFFICIENT_DATA");
  }

  // ==================================================================
  // 8. 真分页 / 真筛选 / 真排序
  // ==================================================================

  @Test
  @DisplayName("14.4.2.1 真分页 / 真筛选 / 真排序（默认按毛利排序）")
  void reportHasRealPagingFilteringAndSorting() {
    Scenario s = seedMainScenario(actorA, a, "PAGE");
    run(actorA, s.promotionId());

    // 真分页
    AdPromotionAnalysisPage p0 =
        report(
            actorA,
            new AdPromotionAnalysisQuery(
                "PRODUCT", null, s.promotionId(), null, null, null, null, null, null, null, null, 0, 1));
    AdPromotionAnalysisPage p1 =
        report(
            actorA,
            new AdPromotionAnalysisQuery(
                "PRODUCT", null, s.promotionId(), null, null, null, null, null, null, null, null, 1, 1));
    assertThat(p0.total()).isEqualTo(2);
    assertThat(p0.items()).hasSize(1);
    assertThat(p1.items()).hasSize(1);
    assertThat(p0.items().get(0).listingId()).isNotEqualTo(p1.items().get(0).listingId());

    // 🔴 默认排序 = 按毛利（高的在前）：420 > −30
    assertThat(p0.items().get(0).promoGrossProfit()).isEqualByComparingTo("420.0000");

    // 真筛选：只看放量不赚
    AdPromotionAnalysisPage alerts =
        report(
            actorA,
            new AdPromotionAnalysisQuery(
                "PRODUCT", null, s.promotionId(), null, null, null, null, true, null, null, null, 0,
                50));
    assertThat(alerts.total()).isEqualTo(1);
    assertThat(alerts.items().get(0).listingId()).isEqualTo(s.listingDown());

    // 真筛选：按判定
    AdPromotionAnalysisPage decrementals =
        report(
            actorA,
            new AdPromotionAnalysisQuery(
                "PRODUCT", null, s.promotionId(), null, null, null, "DECREMENTAL", null, null, null,
                null, 0, 50));
    assertThat(decrementals.total()).isEqualTo(1);

    // 真筛选：关键字
    AdPromotionAnalysisPage byKeyword =
        report(
            actorA,
            new AdPromotionAnalysisQuery(
                "PRODUCT", null, s.promotionId(), null, null, null, null, null, "毛利上升", null, null,
                0, 50));
    assertThat(byKeyword.total()).isEqualTo(1);
    assertThat(byKeyword.items().get(0).listingTitle()).contains("毛利上升");

    // 真筛选：币种
    AdPromotionAnalysisPage byCurrency =
        report(
            actorA,
            new AdPromotionAnalysisQuery(
                "PRODUCT", null, s.promotionId(), null, null, CURRENCY, null, null, null, null, null, 0,
                50));
    assertThat(byCurrency.total()).isEqualTo(2);
  }

  // ==================================================================
  // 9. 导出留痕 + CSV
  // ==================================================================

  @Test
  @DisplayName("14.4.2.1 导出留痕 + CSV：留痕记筛选条件，CSV 口径写进表头")
  void exportIsTracedAndCsvCarriesMetricDefinition() {
    Scenario s = seedMainScenario(actorA, a, "EXPORT");
    run(actorA, s.promotionId());

    AdPromotionAnalysisExportRow export =
        as(
            actorA,
            "channel.operation.read",
            () ->
                service.recordExport(
                    new AdPromotionAnalysisQuery(
                        "PRODUCT", null, s.promotionId(), null, null, null, null, null, null, null,
                        null, 0, 50)));
    assertThat(export.rowCount()).isEqualTo(2);
    assertThat(export.requestedBy()).isEqualTo(user);
    assertThat(export.metricDefinitionNote()).contains("毛利");

    AdPromotionAnalysisExportPage history =
        as(actorA, "channel.operation.read", () -> service.exports(0, 50));
    assertThat(history.total()).isEqualTo(1);
    assertThat(history.items().get(0).id()).isEqualTo(export.id());

    StringBuilder csv = new StringBuilder();
    service.streamCsv(
        actorA,
        service.queryOf(export),
        chunk -> {
          csv.append(chunk);
        });
    String text = csv.toString();
    assertThat(text).contains("促销期毛利");
    assertThat(text).contains("ROI分子(增量毛利)");
    assertThat(text).contains("增量判定(判据=增量毛利)");
    assertThat(text.lines().count()).isEqualTo(3); // 表头 + 2 行
  }

  // ==================================================================
  // 10. 运行留痕：跳过不许静默消失
  // ==================================================================

  @Test
  @DisplayName("14.4.2.1 运行留痕：跳过的促销记进 skipped，状态是 PARTIAL")
  void skippedPromotionsAreRecorded() {
    UUID shopId = seedShop(a, "SKIP-AMZ");
    // 🔴 没有起止时间 ⇒ 跳过的促销不许静默消失
    Seeded skipped =
        seedPromotion(a, "SKIP-AMZ", "P-NOWINDOW", "PERCENT_OFF", "0.200000", null, null, null, null);
    UUID listingId = seedListing(a, shopId, "EXT-SKIP", "跳过场景的商品");
    execAsTenant(a,
        "insert into ad_promotion_product (tenant_id,id,promotion_id,listing_id,base_price,currency)"
            + " values ('" + a + "','" + UUID.randomUUID() + "','" + skipped.promotionId() + "','"
            + listingId + "',20.0000,'" + CURRENCY + "')");

    AdPromotionAnalysisRunRow run = run(actorA, skipped.promotionId());
    assertThat(run.status()).isEqualTo("PARTIAL");
    assertThat(run.skippedCount()).isEqualTo(1);
    assertThat(run.analyzedCount()).isZero();
    assertThat(run.skipped()).contains("P-NOWINDOW").contains("起止时间");

    // 🔴 运行历史也读得到
    List<AdPromotionAnalysisRunRow> runs =
        as(actorA, "channel.operation.read", () -> service.runs(0, 50));
    assertThat(runs).isNotEmpty();
    assertThat(runs.get(0).id()).isEqualTo(run.id());
  }

  // ==================================================================
  // 11. 多租户三断言（AGENTS.md 硬性要求，缺一不可）
  // ==================================================================

  @Test
  @DisplayName("14.4.2.1 断言一：两租户互不可见")
  void tenantsAreIsolated() {
    Scenario sa = seedMainScenario(actorA, a, "ISOA");
    Scenario sb = seedMainScenario(actorB, b, "ISOB");
    run(actorA, sa.promotionId());
    run(actorB, sb.promotionId());

    AdPromotionAnalysisPage pageA =
        report(actorA, query("PROMOTION", null));
    AdPromotionAnalysisPage pageB =
        report(actorB, query("PROMOTION", null));
    assertThat(pageA.total()).isEqualTo(1);
    assertThat(pageB.total()).isEqualTo(1);
    assertThat(pageA.items().get(0).promotionCode()).isEqualTo("P-ISOA");
    assertThat(pageB.items().get(0).promotionCode()).isEqualTo("P-ISOB");

    // 🔴 A 的会话看不到 B 的促销（跨租户与不存在是同一个答案）
    AdPromotionAnalysisPage cross =
        report(
            actorA,
            new AdPromotionAnalysisQuery(
                "PROMOTION", null, sb.promotionId(), null, null, null, null, null, null, null, null, 0,
                50));
    assertThat(cross.total()).isZero();

    // 🔴 成本快照也互不可见
    assertThat(
            as(actorA, "channel.operation.read", () -> service.costSnapshots(null, null, 0, 50))
                .total())
        .isEqualTo(2);
    assertThat(
            as(actorB, "channel.operation.read", () -> service.costSnapshots(null, null, 0, 50))
                .total())
        .isEqualTo(2);
    assertThat(
            as(
                    actorA,
                    "channel.operation.read",
                    () -> service.costSnapshots(sb.promotionId(), null, 0, 50))
                .total())
        .isZero();
  }

  @Test
  @DisplayName("14.4.2.1 断言二：伪造 tenant header 无效（租户只来自已认证成员关系）")
  void forgedTenantHeaderIsIgnored() throws Exception {
    Scenario sa = seedMainScenario(actorA, a, "FORGEA");
    Scenario sb = seedMainScenario(actorB, b, "FORGEB");
    run(actorA, sa.promotionId());
    run(actorB, sb.promotionId());

    // A 的会话 + B 的头 ⇒ 仍然只看得见 A 的促销
    http.perform(get("/channel/ad-promotion-analysis/report")
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()", org.hamcrest.Matchers.is(1)))
        .andExpect(jsonPath("$.items[0].promotionCode", org.hamcrest.Matchers.is("P-FORGEA")));

    // 无会话一律 401
    http.perform(get("/channel/ad-promotion-analysis/report")
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("14.4.2.1 断言三：连接池归还后不串租户（池压到 1，A→B→A 三轮）")
  void pooledConnectionsDoNotBleedTenants() {
    Scenario sa = seedMainScenario(actorA, a, "POOLA");
    Scenario sb = seedMainScenario(actorB, b, "POOLB");
    run(actorA, sa.promotionId());
    run(actorB, sb.promotionId());

    for (int i = 0; i < 3; i++) {
      AdPromotionAnalysisPage pageA = report(actorA, query("PROMOTION", null));
      AdPromotionAnalysisPage pageB = report(actorB, query("PROMOTION", null));
      assertThat(pageA.total()).isEqualTo(1);
      assertThat(pageB.total()).isEqualTo(1);
      assertThat(pageA.items().get(0).promotionCode()).isEqualTo("P-POOLA");
      assertThat(pageB.items().get(0).promotionCode()).isEqualTo("P-POOLB");
    }
  }

  // ==================================================================
  // 12. 端点形状
  // ==================================================================

  @Test
  @DisplayName("14.4.2.1 端点可用：能力声明 / 下拉项 / 报表（销售额与毛利同时在场）")
  void endpointsAreReachable() throws Exception {
    http.perform(get("/channel/ad-promotion-analysis/capabilities")
            .header("Authorization", bearerA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.dimensions.length()", org.hamcrest.Matchers.greaterThan(0)))
        .andExpect(jsonPath("$.reportDimensions.length()", org.hamcrest.Matchers.is(2)))
        .andExpect(jsonPath("$.incrementNote", org.hamcrest.Matchers.containsString("毛利")));

    http.perform(get("/channel/ad-promotion-analysis/options").header("Authorization", bearerA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.dimensions.length()", org.hamcrest.Matchers.is(2)))
        .andExpect(jsonPath("$.sorts.length()", org.hamcrest.Matchers.greaterThan(0)));

    http.perform(get("/channel/ad-promotion-analysis/report").header("Authorization", bearerA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()", org.hamcrest.Matchers.is(0)))
        // 🔴 口径说明必须随报表返回
        .andExpect(jsonPath("$.metricDefinitionNote",
            org.hamcrest.Matchers.containsString("先汇总再相除")));
  }

  @Test
  @DisplayName("14.4.2.1 报表行同时带销售额与毛利（界面上必须两个都看得见）")
  void reportRowCarriesBothSalesAndGrossProfit() {
    Scenario s = seedMainScenario(actorA, a, "BOTH");
    run(actorA, s.promotionId());

    AdPromotionAnalysisRow row =
        report(
                actorA,
                new AdPromotionAnalysisQuery(
                    "PRODUCT", null, s.promotionId(), null, null, null, null, null, null, null, null,
                    0, 50))
            .items()
            .get(0);
    assertThat(row.promoSales()).isNotNull();
    assertThat(row.promoGrossProfit()).isNotNull();
    assertThat(row.baselineSales()).isNotNull();
    assertThat(row.baselineGrossProfit()).isNotNull();
    assertThat(row.metricDefinitionNote()).contains("毛利").contains("销售额");
  }

  // ==================================================================
  // 13. DB 层兜底：跨租户挂载物理上不可能
  // ==================================================================

  @Test
  @DisplayName("14.4.2.1 DB 层兜底：效果结果不能挂到别的租户的促销上")
  void crossTenantAttachIsRejectedByDatabase() {
    Scenario sa = seedMainScenario(actorA, a, "DBA");
    Scenario sb = seedMainScenario(actorB, b, "DBB");
    assertThatThrownBy(
            () ->
                execAsTenant(
                    a,
                    "insert into ad_promotion_effect (tenant_id,id,promotion_id,listing_id,currency,"
                        + " period_from_at,period_to_at,period_days,baseline_id,increment_verdict,"
                        + " data_as_of) values ('"
                        + a
                        + "','"
                        + UUID.randomUUID()
                        + "','"
                        + sb.promotionId()
                        + "','"
                        + sb.listingUp()
                        + "','USD','2026-10-10T00:00:00Z','2026-10-13T00:00:00Z',3,'"
                        + UUID.randomUUID()
                        + "','FLAT','2026-10-13T00:00:00Z')"))
        .hasMessageContaining("foreign key");
  }

  @Test
  @DisplayName("14.4.2.1 基期窗口必须晚于起点（DB CHECK 兜底）")
  void baselineWindowCheckIsEnforcedByDatabase() {
    UUID shopId = seedShop(a, "CHK-AMZ");
    UUID listingId = seedListing(a, shopId, "EXT-CHK", "窗口校验商品");
    Seeded seeded =
        seedPromotion(a, "CHK-AMZ", "P-CHK", "PERCENT_OFF", "0.200000", null, listingId, PROMO_START,
            PROMO_END);
    assertThatThrownBy(
            () ->
                execAsTenant(
                    a,
                    "insert into ad_promotion_effect_baseline (tenant_id,id,promotion_id,listing_id,"
                        + " currency,window_from_at,window_to_at,window_days,data_as_of)"
                        + " values ('"
                        + a
                        + "','"
                        + UUID.randomUUID()
                        + "','"
                        + seeded.promotionId()
                        + "','"
                        + listingId
                        + "','USD','2026-10-10T00:00:00Z','2026-10-07T00:00:00Z',3,"
                        + "'2026-10-13T00:00:00Z')"))
        .hasMessageContaining("ad_promotion_effect_baseline_window_order");
  }

  @Test
  @DisplayName("14.4.2.1 成本快照只能挂在促销的适用商品上（服务层真校验）")
  void costSnapshotRequiresProductInPromotion() {
    Scenario sa = seedMainScenario(actorA, a, "COSTCHK");
    UUID otherListing = seedListing(a, sa.shopId(), "EXT-NOT-IN-PROMO", "不在促销里的商品");
    assertThatThrownBy(() -> saveCost(actorA, sa.promotionId(), otherListing, "8.0000", "20.0000"))
        .hasMessageContaining("适用范围");
  }

  @Test
  @DisplayName("14.4.2.1 非法维度与排序直接拒，不静默兜底")
  void invalidQueryValuesAreRejected() {
    assertThatThrownBy(() -> report(actorA, query("NOPE", null)))
        .hasMessageContaining("维度");
    assertThatThrownBy(() -> report(actorA, query("PROMOTION", "NOPE")))
        .hasMessageContaining("排序");
  }

  @Test
  @DisplayName("14.4.2.1 日期筛选按促销期与区间在时刻轴上重叠判定")
  void dateFilterNarrowsByPeriodOverlap() {
    Scenario s = seedMainScenario(actorA, a, "DATE");
    run(actorA, s.promotionId());

    AdPromotionAnalysisPage hit =
        report(
            actorA,
            new AdPromotionAnalysisQuery(
                "PROMOTION", null, null, null, null, null, null, null, null,
                LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-31"), 0, 50));
    assertThat(hit.total()).isEqualTo(1);

    AdPromotionAnalysisPage miss =
        report(
            actorA,
            new AdPromotionAnalysisQuery(
                "PROMOTION", null, null, null, null, null, null, null, null,
                LocalDate.parse("2026-11-01"), LocalDate.parse("2026-11-30"), 0, 50));
    assertThat(miss.total()).isZero();
  }
}
