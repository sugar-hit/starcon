package com.starcon.channel;

import static com.starcon.channel.AdDaypartModels.*;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 14.2.1.2 分时策略。
 *
 * <p>本测试类钉住本模块最容易做丢的几件事：
 *
 * <ol>
 *   <li><b>🔴 时区是命门</b>：店铺时区来自**站点参数**（shop_marketplace →
 *       marketplace.default_timezone），不是硬编码时区表，也不是假设所有店铺同一时区。
 *       启用多个站点且时区不同 / 没启用站点 ⇒ <b>定不了时区</b> ⇒ 策略不许激活，
 *       推荐结果<b>单列</b>成「未能按店铺本地时区归属」，**不摊给任何默认时区**。
 *   <li><b>🔴 固定 UTC 偏移会冬夏差一小时</b> ⇒ 时区必须是真实 IANA 标识
 *       （DB 触发器钉死），「当前是几个小时」由 <b>DB 的 now()</b> 按 IANA 时区换算。
 *   <li><b>🔴 不复利</b>：目标值 = <b>基准</b> × 系数（基准快照一次），不是当前值 × 系数。
 *   <li><b>🔴 上限逐条拒绝并留痕</b>：越限的那几条 REJECTED + 写清被哪道闸拦下，
 *       <b>不静默截断</b>。
 *   <li><b>🔴 跨币种不相加</b>：活动 / 关键词币种与策略不一致 ⇒ 逐条拒绝。
 *   <li><b>🔴 上游读不到 = 503</b>：不许返回空集合冒充「上游没有数据」。
 *   <li><b>🔴 时间字段写回值正确</b>（AGENTS.md：断言写回值，不能只断言没抛异常）。
 * </ol>
 *
 * <p><b>时刻一律用固定的 {@link OffsetDateTime} 钉死</b>；「当前是几个小时」用
 * <b>DB 的 now()</b> 现算并与落库值比对（夏令时由 PostgreSQL 的 tzdata 负责）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AdDaypartIntegrationTest {

  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID user = UUID.randomUUID();
  static HttpServer identity;
  static HttpServer reporting;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  // ---- 上游 13.2.1.2 的行为开关（每个用例自己摆）----
  /** 上游店铺时区档案（店铺编码 → 档案）。 */
  static final Map<String, UpstreamShop> upstreamShops = new ConcurrentHashMap<>();
  /** 上游返回的本地小时分布（小时 → 订单笔数）。 */
  static final Map<Integer, BigDecimal> upstreamHours = new ConcurrentHashMap<>();
  /** 上游的店铺占比（小时 → 占比）；null ⇒ 上游不给（不可比）。 */
  static final Map<Integer, BigDecimal> upstreamShares = new ConcurrentHashMap<>();
  /** 上游整体不可用（返回 500）。 */
  static final AtomicInteger upstreamDown = new AtomicInteger(0);
  /** 上游被调用的次数。 */
  static final AtomicInteger upstreamCalls = new AtomicInteger(0);

  /** 上游的店铺档案。 */
  record UpstreamShop(String shopCode, String shopName, String timezone, String status, String reason) {}

  UUID a;
  AccessService.Access actorA;
  String bearerA;

  static final OffsetDateTime DATA_AS_OF = OffsetDateTime.parse("2026-10-01T09:00:00+08:00");
  static final OffsetDateTime STARTED_AT = OffsetDateTime.parse("2026-10-01T08:00:00+08:00");
  static final OffsetDateTime FINISHED_AT = OffsetDateTime.parse("2026-10-01T09:05:00+08:00");

  static final UUID AMAZON = UUID.fromString("11111111-1111-1111-1111-111111111111");
  static final UUID AMAZON_REAL = UUID.fromString("11111111-1111-1111-1111-111111111101");
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
      identity.start();

      // ---- 上游 13.2.1.2（reporting 服务）的替身 ----
      reporting = HttpServer.create(new InetSocketAddress(0), 0);
      reporting.createContext(
          "/reporting/time-pattern/shops",
          e -> {
            upstreamCalls.incrementAndGet();
            if (upstreamDown.get() > 0) {
              e.sendResponseHeaders(500, -1);
              e.close();
              return;
            }
            List<Map<String, Object>> rows = new ArrayList<>();
            for (UpstreamShop shop : upstreamShops.values()) {
              Map<String, Object> row = new LinkedHashMap<>();
              row.put("shopCode", shop.shopCode());
              row.put("shopName", shop.shopName());
              row.put("shopTimezone", shop.timezone());
              row.put("timezoneStatus", shop.status());
              row.put("timezoneReason", shop.reason());
              rows.add(row);
            }
            writeJson(e, rows);
          });
      reporting.createContext(
          "/reporting/time-pattern",
          e -> {
            upstreamCalls.incrementAndGet();
            if (upstreamDown.get() > 0) {
              e.sendResponseHeaders(500, -1);
              e.close();
              return;
            }
            List<Map<String, Object>> hours = new ArrayList<>();
            for (int hour = 0; hour < 24; hour++) {
              Map<String, Object> row = new LinkedHashMap<>();
              row.put("hour", hour);
              row.put("hourLabel", String.format("%02d:00", hour));
              row.put("orderCount", upstreamHours.getOrDefault(hour, BigDecimal.ZERO));
              row.put("unitSold", BigDecimal.ZERO);
              row.put("salesAmounts", List.of());
              row.put("orderShare", upstreamShares.get(hour));
              row.put("p90Peak", false);
              hours.add(row);
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("requestedFrom", "2026-10-01");
            body.put("requestedTo", "2026-10-31");
            body.put("from", "2026-10-01");
            body.put("to", "2026-10-31");
            body.put("alignment", "LOCAL_TIME");
            body.put("alignmentLabel", "店铺本地时间");
            body.put("alignmentNote", "测试");
            body.put("shopCodes", List.of());
            body.put("heatmap", List.of());
            body.put("hourDistribution", hours);
            body.put("weekdayDistribution", List.of());
            body.put("slots", List.of());
            body.put("peakRanking", List.of());
            body.put("rankingMetricCode", "ORDER_COUNT");
            body.put("rankingMetricLabel", "订单笔数");
            body.put("p90OrderCount", BigDecimal.ZERO);
            body.put("shops", List.of());
            body.put("unattributed", null);
            Map<String, Object> coverage = new LinkedHashMap<>();
            coverage.put("projectedOrderCount", BigDecimal.valueOf(upstreamHours.values().stream()
                .mapToDouble(BigDecimal::doubleValue).sum()));
            coverage.put("projectedDays", 30);
            coverage.put("expectedDays", 30);
            coverage.put("note", "测试");
            body.put("coverage", coverage);
            body.put("dataUpdatedAt", "2026-10-31T23:00:00+08:00");
            body.put("dataUpdatedNote", "测试");
            body.put("status", "OK");
            body.put("emptyReason", null);
            body.put("emptyNote", null);
            body.put("warnings", List.of());
            writeJson(e, body);
          });
      reporting.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  /**
   * 🔴 **先停外部服务、再停容器**（本仓既有约定，见 BackfillIntegrationTest 等）。
   *
   * <p>不回收的话，一个测试类就会在机器上留下一个 postgres:16-alpine 容器；
   * 同一个服务堆到上百个容器会把机器压进 swap ⇒ **断言假红**（负载型假红，不是本模块的错）。
   */
  @AfterAll
  static void stopEverything() {
    if (identity != null) identity.stop(0);
    if (reporting != null) reporting.stop(0);
    pg.stop();
  }

  static void writeJson(com.sun.net.httpserver.HttpExchange e, Object body) {
    try {
      byte[] bytes = new ObjectMapper().writeValueAsBytes(body);
      e.getResponseHeaders().add("Content-Type", "application/json");
      e.sendResponseHeaders(200, bytes.length);
      e.getResponseBody().write(bytes);
      e.close();
    } catch (Exception ex) {
      throw new RuntimeException(ex);
    }
  }

  @BeforeEach
  void freshTenants() {
    a = UUID.randomUUID();
    bearerA = "Bearer ada-" + a;
    List<AccessService.Grant> grants = List.of(grant("ALL", allPermissions));
    actors.put(bearerA, actor(user, a, grants));
    actorA = actors.get(bearerA);
    upstreamShops.clear();
    upstreamHours.clear();
    upstreamShares.clear();
    upstreamDown.set(0);
    upstreamCalls.set(0);
  }

  @BeforeEach
  void seedGlobalCatalog() {
    exec("insert into platform (id,code,name,integration,support_status,position)"
        + " values ('" + AMAZON + "','AMAZON','Amazon','OFFICIAL_API','AVAILABLE',1)"
        + " on conflict (id) do nothing");
    exec("insert into platform_enum_mapping (id,platform_id,enum_kind,external_value,internal_value)"
        + " values ('" + UUID.randomUUID() + "','" + AMAZON + "','AD_TYPE','sponsored_products','AUTO_DISCOVERY')"
        + " on conflict (platform_id,enum_kind,external_value) do nothing");
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
    r.add("REPORTING_URL", () -> "http://localhost:" + reporting.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
    r.add("spring.datasource.hikari.maximum-pool-size", () -> "1");
  }

  @Autowired AdDaypartService service;
  @Autowired AdCampaignService campaigns;
  @Autowired AdTypeDiffService adTypes;
  @Autowired AccessService access;
  @Autowired MockMvc http;

  // ==================================================================
  // 辅助
  // ==================================================================

  <T> T as(String permission, java.util.function.Supplier<T> work) {
    return access.run(actorA, permission, work);
  }

  static void exec(String sql) {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(sql);
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

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

  static String execOneAsTenant(UUID tenant, String sql) {
    try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute("select set_config('app.current_tenant','" + tenant + "',false)");
      s.execute("select set_config('app.actor','" + user + "',false)");
      s.execute("select set_config('app.department','',false)");
      s.execute("select set_config('app.permission','channel.operation.create',false)");
      s.execute("select set_config('app.grants','" + ALL_SCOPE_GRANTS + "',false)");
      try (var rs = s.executeQuery(sql)) {
        return rs.next() ? rs.getString(1) : null;
      }
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  static long countAsTenant(UUID tenant, String sql) {
    try (Connection c =
            DriverManager.getConnection(pg.getJdbcUrl(), "channel_app", "test-only");
        var s = c.createStatement()) {
      s.execute("select set_config('app.current_tenant','" + tenant + "',false)");
      s.execute("select set_config('app.actor','" + user + "',false)");
      s.execute("select set_config('app.department','',false)");
      s.execute("select set_config('app.permission','channel.operation.create',false)");
      s.execute("select set_config('app.grants','" + ALL_SCOPE_GRANTS + "',false)");
      try (var rs = s.executeQuery(sql)) {
        return rs.next() ? rs.getLong(1) : 0L;
      }
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  /** 🔴 **DB 的 now()**（DB 钟域）—— 断言用它，不用 JVM 墙钟。 */
  OffsetDateTime dbNow() {
    // 🔴 PG 的 `now()::text` 是 '2026-10-28 03:28:12.42673+08'（**不是** ISO-8601，
    //   直接 OffsetDateTime.parse 会炸）⇒ 用 to_json(now()) 取 ISO 串（带引号，剥掉）。
    var raw = execOneAsTenant(a, "select to_json(now())::text");
    return OffsetDateTime.parse(raw.replace("\"", ""));
  }

  /** DB 的 now() 在给定 IANA 时区下的本地小时（与本模块用的是同一条表达式）。 */
  int dbLocalHour(String timezone) {
    return Integer.parseInt(
        execOneAsTenant(
            a,
            "select extract(hour from (now() at time zone cast('"
                + timezone
                + "' as text)))::int"));
  }

  /** 执行一段**会失败**的 SQL，返回它的 SQLSTATE（测试用来断言 DB 层兜底）。 */
  static String sqlStateOf(Runnable work) {
    try {
      work.run();
      return null;
    } catch (RuntimeException e) {
      for (Throwable t = e; t != null; t = t.getCause()) {
        if (t instanceof SQLException sql) {
          return sql.getSQLState();
        }
      }
      return null;
    }
  }

  /** 播种一个店铺。 */
  String seedShop(String code) {
    execAsTenant(a,
        "insert into shop (tenant_id,id,platform_id,code,name) values ('" + a + "','" + UUID.randomUUID()
            + "','" + AMAZON + "','" + code + "','测试店铺 " + code + "')"
            + " on conflict (tenant_id,code) do nothing");
    return code;
  }

  String shopIdOf(String code) {
    return execOneAsTenant(a, "select id::text from shop where tenant_id = '" + a + "' and code = '" + code + "'");
  }

  /** 站点 id（按站点编码查全局码表）。 */
  String marketplaceId(String code) {
    return execOneAsTenant(
        a,
        "select id::text from marketplace where platform_id = '"
            + AMAZON_REAL
            + "' and code = '"
            + code
            + "'");
  }

  /** 站点 id（按平台 + 站点编码查全局码表）。 */
  String marketplaceIdFor(String platformCode, String siteCode) {
    return execOneAsTenant(
        a,
        "select m.id::text from marketplace m join platform p on p.id = m.platform_id"
            + " where p.code = '"
            + platformCode
            + "' and m.code = '"
            + siteCode
            + "'");
  }

  /** 给店铺启用一个站点。 */
  void enableSite(String shopCode, String marketplaceCode) {
    enableMarketplace(shopCode, marketplaceId(marketplaceCode));
  }

  /** 给店铺启用一个指定的站点（按站点 id）。 */
  void enableMarketplace(String shopCode, String marketplaceId) {
    execAsTenant(a,
        "insert into shop_marketplace (tenant_id,shop_id,marketplace_id,status)"
            + " values ('" + a + "','" + shopIdOf(shopCode) + "','" + marketplaceId + "','ACTIVE')"
            + " on conflict (tenant_id,shop_id,marketplace_id) do nothing");
  }

  /** 导入一个活动（走 14.1.1.1 的真实写入口）。 */
  UUID seedCampaign(String shopCode, String suffix, String currency, BigDecimal dailyBudget) {
    UUID profile =
        as("channel.operation.create",
            () -> adTypes.saveProfile(new AdTypeDiffModels.SaveProfileRequest(
                    null, AMAZON, "sponsored_products", "AUTO_DISCOVERY", "展示名", "CPC", "SALES",
                    true, true, true, true, false, "PLATFORM_DEFAULT", "测试登记"))
                .id());
    as("channel.operation.create",
        () -> campaigns.importCampaigns(new AdCampaignModels.ImportRequest(
            AMAZON,
            shopCode,
            new AdCampaignModels.ImportSyncRun(
                "SUCCESS", DATA_AS_OF, STARTED_AT, FINISHED_AT, "", "batch-" + suffix),
            List.of(
                new AdCampaignModels.ImportCampaign(
                    "EXT-" + suffix,
                    "测试活动 " + suffix,
                    profile,
                    "ENABLED",
                    currency,
                    dailyBudget,
                    null,
                    null,
                    List.of(),
                    "测试登记")))));
    return UUID.fromString(
        execOneAsTenant(
            a,
            "select id::text from ad_campaign where tenant_id = '"
                + a
                + "' and external_campaign_id = 'EXT-"
                + suffix
                + "'"));
  }

  /** 播种一个关键词（带竞价与竞价币种）。 */
  void seedKeyword(
      String shopCode, String externalCampaignId, String text, BigDecimal bid, String bidCurrency) {
    execAsTenant(a,
        "insert into ad_keyword (id,tenant_id,platform_id,shop_id,external_campaign_id,"
            + " external_keyword_id,keyword,internal_ad_type,platform_ad_type,bid,bid_currency,status)"
            + " values ('" + UUID.randomUUID() + "','" + a + "','" + AMAZON + "','"
            + shopIdOf(shopCode) + "','" + externalCampaignId + "','KW-" + text + "','" + text
            + "','AUTO_DISCOVERY','sponsored_products'," + bid + ",'" + bidCurrency + "','ACTIVE')");
  }

  /** 建一条策略的请求体（默认参数；上限与试运行可调）。 */
  AdDaypartStrategyRequest strategyRequest(
      String code,
      String shopCode,
      String currency,
      String singleCapAmount,
      String singleCapPercent) {
    return strategyRequest(code, shopCode, currency, singleCapAmount, singleCapPercent, true);
  }

  AdDaypartStrategyRequest strategyRequest(
      String code,
      String shopCode,
      String currency,
      String singleCapAmount,
      String singleCapPercent,
      boolean dryRun) {
    return new AdDaypartStrategyRequest(
        code, "策略 " + code, shopCode, "ALL_CAMPAIGNS", null, currency,
        new BigDecimal(singleCapAmount), new BigDecimal(singleCapPercent),
        new BigDecimal("5000.0000"), 50, 24,
        null, null, null, null,
        dryRun, Boolean.FALSE, 60,
        null, null, null, null, null, null, null, null, null, "测试");
  }

  /** 建一条策略（24 小时全是 KEEP×1）。 */
  AdDaypartStrategyView newStrategy(String shopCode, String code, String currency) {
    return as(
        "channel.operation.create",
        () -> service.createStrategy(strategyRequest(code, shopCode, currency, "1000.0000", "0.500000")));
  }

  /** 把策略改成「不试运行」并激活（**真跑**之前必须做这一步）。 */
  void activate(AdDaypartStrategyView strategy, boolean dryRun) {
    as(
        "channel.operation.create",
        () ->
            service.updateStrategy(
                strategy.id(),
                strategyRequest(
                    strategy.strategyCode(),
                    strategy.shopCode(),
                    strategy.currency(),
                    strategy.singleAdjustCapAmount().toPlainString(),
                    strategy.singleAdjustCapPercent().toPlainString(),
                    dryRun)));
    as(
        "channel.operation.create",
        () -> service.changeStatus(strategy.id(), new AdDaypartStatusRequest("ACTIVE", "激活")));
  }

  /** 把 24 个小时全部设成同一个动作与系数。 */
  void setAllHours(String strategyId, String action, BigDecimal bid, BigDecimal budget) {
    List<AdDaypartHourInput> hours = new ArrayList<>();
    for (int hour = 0; hour < 24; hour++) {
      hours.add(new AdDaypartHourInput(hour, action, bid, budget, "测试曲线"));
    }
    as("channel.operation.create", () -> service.replaceCurve(strategyId, new AdDaypartCurveRequest(hours)));
  }

  // ==================================================================
  // 一、店铺时区（🔴 本模块的命门）
  // ==================================================================

  @Test
  @DisplayName("14.2.1.2 时区：单站点 ⇒ 定得了（IANA 标识来自站点参数，不是硬编码表）")
  void shopTimezoneResolvedFromSiteParameter() {
    seedShop("AD-SHOP-1");
    enableSite("AD-SHOP-1", "US");

    var tz = as("channel.operation.read", () -> service.shopTimezone("AD-SHOP-1"));
    assertThat(tz.timezoneStatus()).isEqualTo("RESOLVED");
    assertThat(tz.shopTimezone()).isEqualTo("America/Los_Angeles");
    assertThat(tz.activeSiteCount()).isEqualTo(1);
    assertThat(tz.siteTimezones()).containsExactly("America/Los_Angeles");
  }

  @Test
  @DisplayName("14.2.1.2 时区：多站点但时区相同 ⇒ 仍定得了（不因为站点多就放弃）")
  void shopTimezoneResolvedWhenSitesShareTimezone() {
    seedShop("AD-SHOP-2");
    // AMAZON-US 与 SHOPIFY-US 的 default_timezone 都是 America/Los_Angeles
    enableMarketplace("AD-SHOP-2", marketplaceIdFor("AMAZON", "US"));
    enableMarketplace("AD-SHOP-2", marketplaceIdFor("SHOPIFY", "US"));

    var tz = as("channel.operation.read", () -> service.shopTimezone("AD-SHOP-2"));
    assertThat(tz.timezoneStatus()).isEqualTo("RESOLVED");
    assertThat(tz.shopTimezone()).isEqualTo("America/Los_Angeles");
    assertThat(tz.activeSiteCount()).isEqualTo(2);
  }

  @Test
  @DisplayName("14.2.1.2 时区：多站点且时区不同 ⇒ 定不了（不挑一个凑数）")
  void shopTimezoneUnresolvedWhenSitesDiffer() {
    seedShop("AD-SHOP-3");
    enableSite("AD-SHOP-3", "US"); // America/Los_Angeles
    enableSite("AD-SHOP-3", "JP"); // Asia/Tokyo

    var tz = as("channel.operation.read", () -> service.shopTimezone("AD-SHOP-3"));
    assertThat(tz.timezoneStatus()).isEqualTo("UNRESOLVED");
    assertThat(tz.shopTimezone()).isEmpty();
    assertThat(tz.timezoneReason()).contains("不一致").contains("America/Los_Angeles").contains("Asia/Tokyo");
    assertThat(tz.activeSiteCount()).isEqualTo(2);
  }

  @Test
  @DisplayName("14.2.1.2 时区：没启用站点 ⇒ 定不了")
  void shopTimezoneUnresolvedWithoutSites() {
    seedShop("AD-SHOP-4");
    var tz = as("channel.operation.read", () -> service.shopTimezone("AD-SHOP-4"));
    assertThat(tz.timezoneStatus()).isEqualTo("UNRESOLVED");
    assertThat(tz.shopTimezone()).isEmpty();
    assertThat(tz.timezoneReason()).contains("没有启用任何站点");
  }

  @Test
  @DisplayName("14.2.1.2 时区：停用的站点不算（shop_marketplace.status='ACTIVE' 才算）")
  void suspendedSiteDoesNotCount() {
    seedShop("AD-SHOP-5");
    enableSite("AD-SHOP-5", "US");
    execAsTenant(a,
        "update shop_marketplace set status='SUSPENDED' where tenant_id = '" + a + "'");
    var tz = as("channel.operation.read", () -> service.shopTimezone("AD-SHOP-5"));
    assertThat(tz.timezoneStatus()).isEqualTo("UNRESOLVED");
    assertThat(tz.activeSiteCount()).isZero();
  }

  @Test
  @DisplayName("14.2.1.2 时区：定不了时区的店铺 ⇒ 策略**不许激活**（服务层 + DB CHECK 两道）")
  void unresolvedShopCannotActivateStrategy() {
    seedShop("AD-SHOP-6");
    // 没启用站点 ⇒ 定不了时区
    var strategy = newStrategy("AD-SHOP-6", "ADP-UNRESOLVED", "USD");
    assertThat(strategy.timezoneStatus()).isEqualTo("UNRESOLVED");
    assertThat(strategy.shopTimezone()).isEmpty();

    setAllHours(strategy.id(), "BOOST", new BigDecimal("1.500000"), new BigDecimal("1.500000"));

    var thrown =
        catchThrowable(
            () ->
                as(
                    "channel.operation.create",
                    () ->
                        service.changeStatus(
                            strategy.id(), new AdDaypartStatusRequest("ACTIVE", "激活"))));
    assertThat(thrown).isNotNull();
    assertThat(thrown.getMessage()).contains("定不了");

    // 🔴 DB 层也拦住（绕服务层直接写 SQL）：SQLSTATE 23514 = check_violation。
    assertThat(
            sqlStateOf(
                () ->
                    execAsTenant(
                        a,
                        "update ad_daypart_strategy set status='ACTIVE' where tenant_id = '"
                            + a
                            + "' and id = '"
                            + strategy.id()
                            + "'")))
        .isEqualTo("23514");

    // 🔴 DB 层也拦住**假的 IANA 时区**（固定 UTC 偏移 / 编出来的时区名都不许）。
    var fakeTz =
        catchThrowable(
            () ->
                execAsTenant(
                    a,
                    "update ad_daypart_strategy set shop_timezone='UTC+8', timezone_status='RESOLVED'"
                        + " where tenant_id = '"
                        + a
                        + "' and id = '"
                        + strategy.id()
                        + "'"));
    assertThat(fakeTz).isNotNull();
    assertThat(fakeTz.getMessage()).contains("IANA");
    assertThat(
            sqlStateOf(
                () ->
                    execAsTenant(
                        a,
                        "update ad_daypart_strategy set shop_timezone='UTC+8', timezone_status='RESOLVED'"
                            + " where tenant_id = '"
                            + a
                            + "' and id = '"
                            + strategy.id()
                            + "'")))
        .isEqualTo("23514");
  }

  // ==================================================================
  // 二、策略增删改查
  // ==================================================================

  @Test
  @DisplayName("14.2.1.2 策略：新建 ⇒ 24 个小时铺好（缺一个小时的策略建不出来）")
  void createStrategySeedsTwentyFourHours() {
    seedShop("AD-SHOP-10");
    enableSite("AD-SHOP-10", "US");
    var strategy = newStrategy("AD-SHOP-10", "ADP-CURVE", "USD");

    assertThat(strategy.hours()).hasSize(24);
    assertThat(strategy.hours().get(0).localHour()).isZero();
    assertThat(strategy.hours().get(23).localHour()).isEqualTo(23);
    assertThat(strategy.hours()).allMatch(h -> h.action().equals("KEEP"));
    assertThat(strategy.hours()).allMatch(h -> h.bidMultiplier().compareTo(BigDecimal.ONE) == 0);
    assertThat(strategy.hours()).allMatch(h -> h.budgetMultiplier().compareTo(BigDecimal.ONE) == 0);
    assertThat(strategy.shopTimezone()).isEqualTo("America/Los_Angeles");
    assertThat(strategy.status()).isEqualTo("DRAFT");

    // 🔴 DB 层也保证「24 小时齐全」：删掉一个小时 ⇒ 提交时被拒。
    var dbThrown =
        catchThrowable(
            () ->
                execAsTenant(
                    a,
                    "delete from ad_daypart_strategy_hour where tenant_id = '"
                        + a
                        + "' and strategy_id = '"
                        + strategy.id()
                        + "' and local_hour = 7"));
    assertThat(dbThrown).isNotNull();
    assertThat(dbThrown.getMessage()).contains("24");
  }

  @Test
  @DisplayName("14.2.1.2 策略：一个店铺一条策略（跨店铺合并会让小时错位）")
  void oneStrategyPerShop() {
    seedShop("AD-SHOP-11");
    enableSite("AD-SHOP-11", "US");
    newStrategy("AD-SHOP-11", "ADP-ONE", "USD");
    var thrown =
        catchThrowable(
            () ->
                as(
                    "channel.operation.create",
                    () ->
                        service.createStrategy(
                            strategyRequest("ADP-TWO", "AD-SHOP-11", "USD", "1000.0000", "0.500000"))));
    assertThat(thrown).isNotNull();
    assertThat(thrown.getMessage()).contains("已经有一条分时策略");
  }

  @Test
  @DisplayName("14.2.1.2 曲线：必须覆盖 24 个小时；PAUSE 的预算系数必须为 0")
  void curveValidation() {
    seedShop("AD-SHOP-12");
    enableSite("AD-SHOP-12", "US");
    var strategy = newStrategy("AD-SHOP-12", "ADP-VALID", "USD");

    // 缺一个小时
    List<AdDaypartHourInput> missing = new ArrayList<>();
    for (int hour = 0; hour < 23; hour++) {
      missing.add(new AdDaypartHourInput(hour, "KEEP", null, null, ""));
    }
    var missingThrown =
        catchThrowable(
            () ->
                as(
                    "channel.operation.create",
                    () -> service.replaceCurve(strategy.id(), new AdDaypartCurveRequest(missing))));
    assertThat(missingThrown).isNotNull();
    assertThat(missingThrown.getMessage()).contains("24 个小时");

    // PAUSE 但预算系数不为 0 ⇒ 自相矛盾
    List<AdDaypartHourInput> badPause = new ArrayList<>();
    for (int hour = 0; hour < 24; hour++) {
      badPause.add(
          new AdDaypartHourInput(
              hour, hour == 3 ? "PAUSE" : "KEEP", null, hour == 3 ? BigDecimal.ONE : null, ""));
    }
    var pauseThrown =
        catchThrowable(
            () ->
                as(
                    "channel.operation.create",
                    () -> service.replaceCurve(strategy.id(), new AdDaypartCurveRequest(badPause))));
    assertThat(pauseThrown).isNotNull();
    assertThat(pauseThrown.getMessage()).contains("预算系数必须是 0");
  }

  @Test
  @DisplayName("14.2.1.2 策略：曲线 24 小时全是「不动」⇒ 不许激活")
  void cannotActivateAllKeepCurve() {
    seedShop("AD-SHOP-13");
    enableSite("AD-SHOP-13", "US");
    var strategy = newStrategy("AD-SHOP-13", "ADP-NOP", "USD");
    var thrown =
        catchThrowable(
            () ->
                as(
                    "channel.operation.create",
                    () ->
                        service.changeStatus(
                            strategy.id(), new AdDaypartStatusRequest("ACTIVE", "激活"))));
    assertThat(thrown).isNotNull();
    assertThat(thrown.getMessage()).contains("什么都没配");
  }

  @Test
  @DisplayName("14.2.1.2 策略列表：真分页 + 真筛选")
  void strategyListIsRealPaged() {
    seedShop("AD-SHOP-14");
    enableSite("AD-SHOP-14", "US");
    newStrategy("AD-SHOP-14", "ADP-PAGE", "USD");
    seedShop("AD-SHOP-15");
    enableSite("AD-SHOP-15", "US");
    newStrategy("AD-SHOP-15", "ADP-PAGE-2", "USD");

    var page = as("channel.operation.read", () -> service.strategies(null, null, null, 0, 1));
    assertThat(page.total()).isEqualTo(2);
    assertThat(page.items()).hasSize(1);
    assertThat(page.size()).isEqualTo(1);

    var filtered = as("channel.operation.read", () -> service.strategies("AD-SHOP-15", null, null, 0, 50));
    assertThat(filtered.total()).isEqualTo(1);
    assertThat(filtered.items().get(0).shopCode()).isEqualTo("AD-SHOP-15");

    var searched = as("channel.operation.read", () -> service.strategies(null, null, "ADP-PAGE-2", 0, 50));
    assertThat(searched.total()).isEqualTo(1);
    assertThat(searched.items().get(0).strategyCode()).isEqualTo("ADP-PAGE-2");
  }

  // ==================================================================
  // 三、推荐曲线（上游 13.2.1.2）
  // ==================================================================

  @Test
  @DisplayName("14.2.1.2 推荐：按上游的本地小时分布算出高峰 / 低谷 / 停投")
  void recommendationComputesCurveFromUpstream() {
    seedShop("AD-SHOP-20");
    enableSite("AD-SHOP-20", "US");
    var strategy = newStrategy("AD-SHOP-20", "ADP-REC", "USD");
    upstreamShops.put("AD-SHOP-20", new UpstreamShop("AD-SHOP-20", "测试店铺", "America/Los_Angeles", "RESOLVED", ""));

    // 24 个小时里：20:00 占 ~40%（高峰），03:00 占 ~0.05%（最深的低谷 ⇒ 停投），
    // 其余 22 小时平摊 ~60%（每个 ~2.7%，落在「不动」档：高于低谷阈值 2.08%、低于高峰阈值 8.33%）。
    for (int hour = 0; hour < 24; hour++) {
      upstreamHours.put(hour, BigDecimal.valueOf(hour == 20 ? 880 : (hour == 3 ? 1 : 60)));
    }
    for (int hour = 0; hour < 24; hour++) {
      BigDecimal total = upstreamHours.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
      upstreamShares.put(hour, upstreamHours.get(hour).divide(total, 6, java.math.RoundingMode.HALF_UP));
    }

    var batch =
        as("channel.operation.create",
            () ->
                service.generateRecommendation(
                    new AdDaypartRecommendationRequest(strategy.id(), LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31)),
                    bearerA));

    assertThat(batch.upstreamStatus()).isEqualTo("OK");
    assertThat(batch.hours()).hasSize(24);
    assertThat(batch.hours().get(20).suggestedAction()).isEqualTo("BOOST");
    assertThat(batch.hours().get(3).suggestedAction()).isEqualTo("PAUSE");
    assertThat(batch.hours().get(12).suggestedAction()).isEqualTo("KEEP");
    assertThat(batch.hours().get(20).basisNote()).contains("占比");
    // 🔴 判定参数**快照**进批次 ⇒ 可复算
    assertThat(batch.profile()).containsKey("peakShareMultiple");
    assertThat(batch.profile()).containsKey("pauseThreshold");
    assertThat(batch.shopTimezone()).isEqualTo("America/Los_Angeles");

    // 套用 ⇒ 曲线变成推荐值，来源标 RECOMMENDED，判定依据带上
    var applied = as("channel.operation.create", () -> service.applyRecommendation(batch.id(), new AdDaypartApplyRecommendationRequest(List.of())));
    assertThat(applied.hours().get(20).action()).isEqualTo("BOOST");
    assertThat(applied.hours().get(20).source()).isEqualTo("RECOMMENDED");
    assertThat(applied.hours().get(20).budgetMultiplier()).isEqualByComparingTo("1.5");
    assertThat(applied.hours().get(3).action()).isEqualTo("PAUSE");
    assertThat(applied.hours().get(3).budgetMultiplier()).isEqualByComparingTo("0");
  }

  @Test
  @DisplayName("14.2.1.2 推荐：上游读不到 ⇒ 503（不许返回空集合冒充「没有数据」）")
  void recommendationFailsWith503WhenUpstreamDown() throws Exception {
    seedShop("AD-SHOP-21");
    enableSite("AD-SHOP-21", "US");
    var strategy = newStrategy("AD-SHOP-21", "ADP-503", "USD");
    upstreamDown.set(1);

    var thrown =
        catchThrowable(
            () ->
                as(
                    "channel.operation.create",
                    () ->
                        service.generateRecommendation(
                            new AdDaypartRecommendationRequest(strategy.id(), null, null), bearerA)));
    assertThat(thrown).isNotNull();
    assertThat(thrown.getMessage()).contains("读不到");

    // 🔴 端点层也是 503
    http.perform(
            post("/channel/ad-dayparts/recommendations")
                .header("Authorization", bearerA)
                .contentType("application/json")
                .content(
                    new ObjectMapper()
                        .writeValueAsString(
                            new AdDaypartRecommendationRequest(strategy.id(), null, null))))
        .andExpect(status().isServiceUnavailable());
  }

  @Test
  @DisplayName("14.2.1.2 推荐：店铺定不了时区 ⇒ 单列成「未能按店铺本地时区归属」，不摊给任何时区")
  void recommendationRecordsUnresolvedShop() {
    seedShop("AD-SHOP-22");
    enableSite("AD-SHOP-22", "US");
    enableSite("AD-SHOP-22", "JP"); // 时区不一致 ⇒ 本服务这侧定不了
    var strategy = newStrategy("AD-SHOP-22", "ADP-UNRES", "USD");
    upstreamShops.put("AD-SHOP-22", new UpstreamShop("AD-SHOP-22", "测试店铺", "", "UNRESOLVED", "上游也定不了"));

    var batch =
        as("channel.operation.create",
            () ->
                service.generateRecommendation(
                    new AdDaypartRecommendationRequest(strategy.id(), null, null), bearerA));
    assertThat(batch.upstreamStatus()).isEqualTo("SHOP_UNRESOLVED");
    assertThat(batch.hours()).isEmpty();
    assertThat(batch.shopTimezone()).isEmpty();
    assertThat(batch.timezoneReason()).contains("不一致");

    // 🔴 这批**不能**套到策略上（没有曲线可套）。
    var thrown =
        catchThrowable(
            () ->
                as(
                    "channel.operation.create",
                    () -> service.applyRecommendation(batch.id(), new AdDaypartApplyRecommendationRequest(List.of()))));
    assertThat(thrown).isNotNull();
    assertThat(thrown.getMessage()).contains("没有曲线可套");
  }

  @Test
  @DisplayName("14.2.1.2 推荐：两侧时区不一致 ⇒ CONFLICT，不产出曲线")
  void recommendationDetectsTimezoneConflict() {
    seedShop("AD-SHOP-23");
    enableSite("AD-SHOP-23", "US");
    var strategy = newStrategy("AD-SHOP-23", "ADP-CONFLICT", "USD");
    // 上游说的是另一个时区 ⇒ 上游的小时桶在本服务这边会被读成错误的小时
    upstreamShops.put("AD-SHOP-23", new UpstreamShop("AD-SHOP-23", "测试店铺", "Asia/Tokyo", "RESOLVED", ""));

    var batch =
        as("channel.operation.create",
            () ->
                service.generateRecommendation(
                    new AdDaypartRecommendationRequest(strategy.id(), null, null), bearerA));
    assertThat(batch.upstreamStatus()).isEqualTo("CONFLICT");
    assertThat(batch.hours()).isEmpty();
    assertThat(batch.timezoneReason()).contains("两侧时区不一致");
  }

  // ==================================================================
  // 四、定时调整任务
  // ==================================================================

  @Test
  @DisplayName("14.2.1.2 任务：试运行只算不调（预算一个字节都没动）")
  void dryRunDoesNotTouchBudget() {
    seedShop("AD-SHOP-30");
    enableSite("AD-SHOP-30", "US");
    seedCampaign("AD-SHOP-30", "DRY", "USD", new BigDecimal("100.0000"));
    var strategy = newStrategy("AD-SHOP-30", "ADP-DRY", "USD");
    setAllHours(strategy.id(), "BOOST", new BigDecimal("1.500000"), new BigDecimal("1.500000"));

    var result =
        as("channel.operation.create", () -> service.run(new AdDaypartRunRequest(strategy.id(), "DRY_RUN", "MANUAL", "试跑"), bearerA));
    assertThat(result.tasks()).hasSize(1);
    var task = result.tasks().get(0);
    assertThat(task.runMode()).isEqualTo("DRY_RUN");
    assertThat(task.status()).isEqualTo("SKIPPED");
    assertThat(task.lines()).isNotEmpty();
    assertThat(task.lines()).allMatch(l -> l.result().equals("DRY_RUN"));
    assertThat(task.lines().get(0).afterValue()).isEqualByComparingTo("150.0000");
    assertThat(task.lines().get(0).message()).contains("试运行");

    // 🔴 预算真的没动
    assertThat(
            execOneAsTenant(
                a,
                "select daily_budget::text from ad_campaign where tenant_id = '"
                    + a
                    + "' and external_campaign_id = 'EXT-DRY'"))
        .isEqualTo("100.0000");
  }

  @Test
  @DisplayName("14.2.1.2 任务：真跑 ⇒ 走 14.1.1.1 已交付的写路径（预算真的改了 + 有审计 + 标待回写平台）")
  void applyTaskChangesBudgetThroughDeliveredWritePath() {
    seedShop("AD-SHOP-31");
    enableSite("AD-SHOP-31", "US");
    seedCampaign("AD-SHOP-31", "APPLY", "USD", new BigDecimal("100.0000"));
    var strategy = newStrategy("AD-SHOP-31", "ADP-APPLY", "USD");
    setAllHours(strategy.id(), "BOOST", new BigDecimal("1.500000"), new BigDecimal("1.500000"));
    activate(strategy, false);

    var result =
        as("channel.operation.create", () -> service.run(new AdDaypartRunRequest(strategy.id(), "APPLY", "MANUAL", "真跑"), bearerA));
    var task = result.tasks().get(0);
    assertThat(task.status()).isEqualTo("APPLIED");
    assertThat(task.appliedCount()).isEqualTo(1);
    assertThat(task.lines().get(0).result()).isEqualTo("APPLIED");
    assertThat(task.lines().get(0).beforeValue()).isEqualByComparingTo("100.0000");
    assertThat(task.lines().get(0).afterValue()).isEqualByComparingTo("150.0000");

    // 🔴 预算真的改了
    assertThat(
            execOneAsTenant(
                a,
                "select daily_budget::text from ad_campaign where tenant_id = '"
                    + a
                    + "' and external_campaign_id = 'EXT-APPLY'"))
        .isEqualTo("150.0000");
    // 🔴 走的是 14.1.1.1 的写路径 ⇒ 有审计 + 标「待回写平台」
    assertThat(
            countAsTenant(
                a,
                "select count(*) from ad_campaign_operation_log where tenant_id = '"
                    + a
                    + "' and operation = 'SET_BUDGET'"))
        .isEqualTo(1);
    assertThat(
            execOneAsTenant(
                a,
                "select pending_platform_writeback::text from ad_campaign where tenant_id = '"
                    + a
                    + "' and external_campaign_id = 'EXT-APPLY'"))
        .isEqualTo("true");
  }

  @Test
  @DisplayName("14.2.1.2 任务：🔴 目标值相对**基准**算，不是相对当前值（不复利）")
  void taskTargetIsRelativeToBaselineNotCurrent() {
    seedShop("AD-SHOP-32");
    enableSite("AD-SHOP-32", "US");
    seedCampaign("AD-SHOP-32", "BASE", "USD", new BigDecimal("100.0000"));
    var strategy = newStrategy("AD-SHOP-32", "ADP-BASE", "USD");
    setAllHours(strategy.id(), "BOOST", new BigDecimal("1.500000"), new BigDecimal("1.500000"));
    activate(strategy, false);

    var first =
        as("channel.operation.create", () -> service.run(new AdDaypartRunRequest(strategy.id(), "APPLY", "MANUAL", "第一次"), bearerA));
    assertThat(first.tasks().get(0).lines().get(0).afterValue()).isEqualByComparingTo("150.0000");
    assertThat(first.tasks().get(0).lines().get(0).result()).isEqualTo("APPLIED");

    // 模拟「别的时段把它调到了 300」，然后**删掉本小时的任务**让同一小时能再跑一次。
    execAsTenant(a,
        "update ad_campaign set daily_budget = 300.0000 where tenant_id = '" + a + "' and external_campaign_id = 'EXT-BASE'");
    execAsTenant(a,
        "delete from ad_daypart_task where tenant_id = '" + a + "' and strategy_id = '" + strategy.id() + "'");

    var second =
        as("channel.operation.create", () -> service.run(new AdDaypartRunRequest(strategy.id(), "APPLY", "MANUAL", "第二次"), bearerA));

    // 🔴 基准是 100 ⇒ 目标仍然是 100 × 1.5 = 150（**不是** 300 × 1.5 = 450）。
    assertThat(second.tasks().get(0).lines().get(0).afterValue()).isEqualByComparingTo("150.0000");
    assertThat(second.tasks().get(0).lines().get(0).result()).isEqualTo("APPLIED");
    assertThat(
            execOneAsTenant(
                a,
                "select daily_budget::text from ad_campaign where tenant_id = '"
                    + a
                    + "' and external_campaign_id = 'EXT-BASE'"))
        .isEqualTo("150.0000");
  }

  @Test
  @DisplayName("14.2.1.2 任务：🔴 越限的那几条被逐条拒绝并留痕（不静默截断）")
  void guardrailRejectsOversizedAdjustmentAndKeepsTrace() {
    seedShop("AD-SHOP-33");
    enableSite("AD-SHOP-33", "US");
    seedCampaign("AD-SHOP-33", "GUARD", "USD", new BigDecimal("100.0000"));
    var strategy = newStrategy("AD-SHOP-33", "ADP-GUARD", "USD");
    setAllHours(strategy.id(), "BOOST", new BigDecimal("2.000000"), new BigDecimal("2.000000"));
    activate(strategy, false);

    // 单次上限只有 10 ⇒ 100 → 200 的偏离（100）越线
    as("channel.operation.create",
        () -> service.updateStrategy(strategy.id(),
            strategyRequest("ADP-GUARD", "AD-SHOP-33", "USD", "10.0000", "0.500000", false)));
    as("channel.operation.create",
        () -> service.changeStatus(strategy.id(), new AdDaypartStatusRequest("ACTIVE", "激活")));

    var result =
        as("channel.operation.create", () -> service.run(new AdDaypartRunRequest(strategy.id(), "APPLY", "MANUAL", "越线"), bearerA));
    var task = result.tasks().get(0);
    assertThat(task.status()).isEqualTo("FAILED");
    assertThat(task.rejectedCount()).isEqualTo(1);
    var line = task.lines().get(0);
    assertThat(line.result()).isEqualTo("REJECTED");
    assertThat(line.guardrailCode()).isEqualTo("SINGLE_AMOUNT");
    assertThat(line.message()).contains("单次上限");

    // 🔴 越线的调整**一条都没生效**
    assertThat(
            execOneAsTenant(
                a,
                "select daily_budget::text from ad_campaign where tenant_id = '"
                    + a
                    + "' and external_campaign_id = 'EXT-GUARD'"))
        .isEqualTo("100.0000");
  }

  @Test
  @DisplayName("14.2.1.2 任务：🔴 跨币种的活动逐条拒绝（绝不跨币种相加）")
  void crossCurrencyCampaignIsRejected() {
    seedShop("AD-SHOP-34");
    enableSite("AD-SHOP-34", "US");
    seedCampaign("AD-SHOP-34", "CCY", "EUR", new BigDecimal("100.0000"));
    var strategy = newStrategy("AD-SHOP-34", "ADP-CCY", "USD");
    setAllHours(strategy.id(), "BOOST", new BigDecimal("2.000000"), new BigDecimal("2.000000"));
    activate(strategy, false);

    var result =
        as("channel.operation.create", () -> service.run(new AdDaypartRunRequest(strategy.id(), "APPLY", "MANUAL", "跨币种"), bearerA));
    var line = result.tasks().get(0).lines().get(0);
    assertThat(line.result()).isEqualTo("REJECTED");
    assertThat(line.guardrailCode()).isEqualTo("CURRENCY");
    assertThat(line.message()).contains("币种不一致");
    assertThat(
            execOneAsTenant(
                a,
                "select daily_budget::text from ad_campaign where tenant_id = '"
                    + a
                    + "' and external_campaign_id = 'EXT-CCY'"))
        .isEqualTo("100.0000");
  }

  @Test
  @DisplayName("14.2.1.2 任务：🔴 幂等 —— 同一策略 + 同一本地小时 + 同一口径只有一条任务")
  void taskIsIdempotentWithinSameLocalHour() {
    seedShop("AD-SHOP-35");
    enableSite("AD-SHOP-35", "US");
    seedCampaign("AD-SHOP-35", "IDEM", "USD", new BigDecimal("100.0000"));
    var strategy = newStrategy("AD-SHOP-35", "ADP-IDEM", "USD");
    setAllHours(strategy.id(), "BOOST", new BigDecimal("1.500000"), new BigDecimal("1.500000"));
    activate(strategy, false);

    var first = as("channel.operation.create", () -> service.run(new AdDaypartRunRequest(strategy.id(), "APPLY", "MANUAL", "第一次"), bearerA));
    var second = as("channel.operation.create", () -> service.run(new AdDaypartRunRequest(strategy.id(), "APPLY", "MANUAL", "重试"), bearerA));
    assertThat(first.tasks().get(0).id()).isEqualTo(second.tasks().get(0).id());
    assertThat(
            countAsTenant(
                a,
                "select count(*) from ad_daypart_task where tenant_id = '" + a + "' and strategy_id = '"
                    + strategy.id() + "'"))
        .isEqualTo(1);
  }

  @Test
  @DisplayName("14.2.1.2 任务：🔴 「本地第几个小时」由 DB 的 now() 按店铺 IANA 时区换算（夏令时对得上）")
  void localHourComesFromDbNowInShopTimezone() {
    seedShop("AD-SHOP-36");
    enableSite("AD-SHOP-36", "JP"); // Asia/Tokyo
    seedCampaign("AD-SHOP-36", "TZ", "JPY", new BigDecimal("100.0000"));
    var strategy = newStrategy("AD-SHOP-36", "ADP-TZ", "JPY");
    setAllHours(strategy.id(), "BOOST", new BigDecimal("1.500000"), new BigDecimal("1.500000"));
    activate(strategy, false);

    int expected = dbLocalHour("Asia/Tokyo");
    var result = as("channel.operation.create", () -> service.run(new AdDaypartRunRequest(strategy.id(), "APPLY", "MANUAL", "时区"), bearerA));
    var task = result.tasks().get(0);
    assertThat(task.localHour()).isEqualTo(expected);
    assertThat(task.shopTimezone()).isEqualTo("Asia/Tokyo");
    // 🔴 **落库时刻取 PG 的 now()**（DB 钟域）—— 断言写回值，不只断言没抛异常。
    assertThat(task.executedAt()).isNotNull();
    assertThat(task.windowTo()).isAfter(task.windowFrom());
    assertThat(task.windowTo().getHour()).isEqualTo(dbNow().getHour());
  }

  @Test
  @DisplayName("14.2.1.2 任务：🔴 时间字段写回值逐字相等（timestamptz 往返不偏）")
  void timestamptzRoundTripIsExact() {
    seedShop("AD-SHOP-37");
    enableSite("AD-SHOP-37", "US");
    seedCampaign("AD-SHOP-37", "TS", "USD", new BigDecimal("100.0000"));
    var strategy = newStrategy("AD-SHOP-37", "ADP-TS", "USD");
    setAllHours(strategy.id(), "BOOST", new BigDecimal("1.500000"), new BigDecimal("1.500000"));
    activate(strategy, false);

    var result = as("channel.operation.create", () -> service.run(new AdDaypartRunRequest(strategy.id(), "APPLY", "MANUAL", "时刻"), bearerA));
    var task = result.tasks().get(0);

    // 🔴 直接从库里读回来比对（PG 存的是 timestamptz，值必须能原样读回）。
    String written =
        execOneAsTenant(
            a,
            "select to_char(executed_at at time zone cast('UTC' as text), 'YYYY-MM-DD\"T\"HH24:MI:SS')"
                + " from ad_daypart_task where tenant_id = '" + a + "' and id = '" + task.id() + "'");
    String expected =
        task.executedAt()
            .withOffsetSameInstant(java.time.ZoneOffset.UTC)
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));
    assertThat(written).isEqualTo(expected);
  }

  @Test
  @DisplayName("14.2.1.2 任务：🔴 竞价也走 14.1.3.1 已交付的写路径（竞价真的改了）")
  void keywordBidGoesThroughDeliveredWritePath() {
    seedShop("AD-SHOP-38");
    enableSite("AD-SHOP-38", "US");
    seedCampaign("AD-SHOP-38", "KW", "USD", new BigDecimal("100.0000"));
    seedKeyword("AD-SHOP-38", "EXT-KW", "测试词", new BigDecimal("1.0000"), "USD");
    var strategy = newStrategy("AD-SHOP-38", "ADP-KW", "USD");
    setAllHours(strategy.id(), "BOOST", new BigDecimal("1.500000"), new BigDecimal("1.000000"));
    activate(strategy, false);

    var result = as("channel.operation.create", () -> service.run(new AdDaypartRunRequest(strategy.id(), "APPLY", "MANUAL", "竞价"), bearerA));
    var task = result.tasks().get(0);
    var keywordLine =
        task.lines().stream().filter(l -> l.targetType().equals("KEYWORD_BID")).findFirst().orElseThrow();
    assertThat(keywordLine.result()).isEqualTo("APPLIED");
    assertThat(keywordLine.beforeValue()).isEqualByComparingTo("1.0000");
    assertThat(keywordLine.afterValue()).isEqualByComparingTo("1.5000");

    assertThat(
            execOneAsTenant(
                a,
                "select bid::text from ad_keyword where tenant_id = '" + a + "' and keyword = '测试词'"))
        .isEqualTo("1.5000");
  }

  @Test
  @DisplayName("14.2.1.2 任务：🔴 店铺时区定不了 ⇒ 策略自动暂停，本轮跳过（不拿旧时区跑）")
  void runSkipsAndAutoPausesWhenTimezoneBecomesUnresolvable() {
    seedShop("AD-SHOP-39");
    enableSite("AD-SHOP-39", "US");
    seedCampaign("AD-SHOP-39", "AUTO", "USD", new BigDecimal("100.0000"));
    var strategy = newStrategy("AD-SHOP-39", "ADP-AUTO", "USD");
    setAllHours(strategy.id(), "BOOST", new BigDecimal("1.500000"), new BigDecimal("1.500000"));
    activate(strategy, false);

    // 店铺启用了第二个**时区不同**的站点 ⇒ 时区定不了了
    enableSite("AD-SHOP-39", "JP");

    var result = as("channel.operation.create", () -> service.run(new AdDaypartRunRequest(strategy.id(), "APPLY", "MANUAL", "自动暂停"), bearerA));
    assertThat(result.tasks()).isEmpty();
    assertThat(result.skips()).hasSize(1);
    assertThat(result.skips().get(0).reason()).contains("定不了").contains("自动暂停");

    var after = as("channel.operation.read", () -> service.strategy(strategy.id()));
    assertThat(after.status()).isEqualTo("PAUSED");
    assertThat(after.timezoneStatus()).isEqualTo("UNRESOLVED");
    assertThat(after.shopTimezone()).isEmpty();
    // 🔴 一个字节都没动
    assertThat(
            execOneAsTenant(
                a,
                "select daily_budget::text from ad_campaign where tenant_id = '"
                    + a
                    + "' and external_campaign_id = 'EXT-AUTO'"))
        .isEqualTo("100.0000");
  }

  // ==================================================================
  // 五、给下游 14.2.2.2 的读取形状
  // ==================================================================

  @Test
  @DisplayName("14.2.1.2 上限读取形状：供 14.2.2.2 自动规则取用（合并后取更严的）")
  void shopCapsAreExposedForDownstreamRules() {
    seedShop("AD-SHOP-40");
    enableSite("AD-SHOP-40", "US");
    var strategy = newStrategy("AD-SHOP-40", "ADP-CAPS", "USD");
    setAllHours(strategy.id(), "BOOST", new BigDecimal("1.500000"), new BigDecimal("1.500000"));
    activate(strategy, false);

    var caps = as("channel.operation.read", () -> service.shopCaps("AD-SHOP-40"));
    assertThat(caps.shopCode()).isEqualTo("AD-SHOP-40");
    assertThat(caps.shopTimezone()).isEqualTo("America/Los_Angeles");
    assertThat(caps.strategies()).hasSize(1);
    assertThat(caps.effectiveCaps()).isNotNull();
    assertThat(caps.effectiveCaps().singleAdjustCapAmount()).isEqualByComparingTo("1000.0000");
    assertThat(caps.effectiveCaps().totalCapAmount()).isEqualByComparingTo("5000.0000");
    assertThat(caps.note()).contains("取更严");
  }

  @Test
  @DisplayName("14.2.1.2 上限读取形状：没有生效中的策略 ⇒ 明确说「没有生效的分时上限」")
  void shopCapsWithoutActiveStrategy() {
    seedShop("AD-SHOP-41");
    enableSite("AD-SHOP-41", "US");
    newStrategy("AD-SHOP-41", "ADP-DRAFT", "USD");

    var caps = as("channel.operation.read", () -> service.shopCaps("AD-SHOP-41"));
    assertThat(caps.strategies()).hasSize(1);
    assertThat(caps.effectiveCaps()).isNull();
    assertThat(caps.note()).contains("没有生效中");
  }
}
