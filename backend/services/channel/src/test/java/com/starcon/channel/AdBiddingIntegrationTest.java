package com.starcon.channel;

import static com.starcon.channel.AdBiddingModels.*;
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
import java.time.ZoneOffset;
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
 * 14.2.1.1 竞价管理。
 *
 * <p>本测试类钉住本模块最容易做丢的几件事：
 *
 * <ol>
 *   <li><b>🔴 动态竞价策略是三档枚举</b>（只增只降 / 只降低 / 只提高），不是自由文本；
 *       激活后进<b>待下发队列</b>。
 *   <li><b>🔴 改竞价走 14.1.3.1 已交付的写路径</b>：真的改了 {@code ad_keyword.bid}、
 *       真的留了 {@code ad_keyword_bulk_action} 审计、护栏上限**继承**不另设。
 *   <li><b>🔴 两道不同的闸</b>：竞价范围上限（闸①）与单次调整额度（闸②）分别拦下，
 *       **逐条拒绝并留痕**（写明被哪道闸拦下），<b>绝不静默截断</b>。
 *   <li><b>🔴 ACOS / ROAS 是 GENERATED 列</b>：分母为 0 ⇒ <b>NULL（不可比）而不是 0</b>。
 *   <li><b>🔴 目标 ACOS 反推竞价</b>：倍率 = 目标 ACOS ÷ 当前 ACOS，逐条落到关键词自己的价上。
 *   <li><b>🔴 利润打通</b>：目标 ACOS 高于<b>盈亏平衡 ACOS</b>的建议被否决；
 *       利润侧读不到 ⇒ <b>503</b>。
 *   <li><b>🔴 待下发队列</b>：唯一能改的动作是「撤销」；<b>刻意不提供</b>「标记已下发」。
 *   <li><b>🔴 幂等</b>：同一幂等键重投不会重复调价（调价是花钱的动作）。
 *   <li><b>🔴 时间字段写回值正确</b>（AGENTS.md：断言写回值，不能只断言没抛异常）。
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class AdBiddingIntegrationTest {

  static final PostgreSQLContainer<?> pg =
      new PostgreSQLContainer<>("postgres:16-alpine").withUrlParam("sslmode", "disable");
  static final UUID user = UUID.randomUUID();
  static HttpServer identity;
  static HttpServer reporting;
  static HttpServer catalog;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  // ---- 上游 13.4.1.2（reporting）与 03（catalog）的行为开关（每个用例自己摆）----
  /** reporting 不可用（返回 500）。 */
  static final AtomicInteger profitDown = new AtomicInteger(0);
  /** catalog 不可用（返回 500）。 */
  static final AtomicInteger catalogDown = new AtomicInteger(0);
  /** 利润侧：SKU 编码 → 该 SKU 的利润（null = 上游说没有这个 SKU 的事实）。 */
  static final Map<String, BigDecimal[]> profitBySku = new ConcurrentHashMap<>();
  /** catalog：内部 SKU id → SKU 编码。 */
  static final Map<UUID, String> skuCodeById = new ConcurrentHashMap<>();

  UUID a;
  AccessService.Access actorA;
  String bearerA;

  static final UUID AMAZON = UUID.fromString("11111111-1111-1111-1111-111111111101");

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

      // ---- 上游 13.4.1.2（reporting 服务）的替身：SKU 利润 ----
      reporting = HttpServer.create(new InetSocketAddress(0), 0);
      reporting.createContext(
          "/reporting/sku-profit/report",
          e -> {
            if (profitDown.get() > 0) {
              e.sendResponseHeaders(500, -1);
              e.close();
              return;
            }
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Map.Entry<String, BigDecimal[]> entry : profitBySku.entrySet()) {
              String skuCode = entry.getKey();
              BigDecimal[] v = entry.getValue();
              Map<String, Object> row = new LinkedHashMap<>();
              row.put("skuCode", skuCode);
              row.put("currency", "USD");
              // 🔴 用 LinkedHashMap 而不是 Map.of：Map.of 不接受 null 值
              //    （AD_FEE 的 quantity 就是 null ⇒ Map.of 会当场 NPE）。
              Map<String, Object> revenue = new LinkedHashMap<>();
              revenue.put("component", "REVENUE");
              revenue.put("label", "单品收入");
              revenue.put("amount", v[0]);
              revenue.put("quantity", BigDecimal.ONE);
              revenue.put("status", "AVAILABLE");
              revenue.put("available", true);
              revenue.put("reason", "");
              Map<String, Object> adFee = new LinkedHashMap<>();
              adFee.put("component", "AD_FEE");
              adFee.put("label", "广告费");
              adFee.put("amount", v[2]);
              adFee.put("quantity", null);
              adFee.put("status", "AVAILABLE");
              adFee.put("available", true);
              adFee.put("reason", "");
              row.put("components", List.of(revenue, adFee));
              row.put("totalCost", v[1].add(v[2]));
              row.put("netProfit", v[0].subtract(v[1]).subtract(v[2]));
              row.put("netMargin", BigDecimal.ZERO);
              row.put("netProfitComparable", true);
              row.put("missingComponents", List.of());
              row.put("modeBreakdown", List.of());
              row.put("factCount", 1);
              row.put("note", "测试");
              rows.add(row);
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("from", "2026-10-01");
            body.put("to", "2026-10-31");
            body.put("dimension", "SKU");
            body.put("rows", rows);
            body.put("skuCount", rows.size());
            body.put("comparableCount", rows.size());
            body.put("notComparableCount", 0);
            body.put("dataUpdatedAt", "2026-10-31T23:00:00+08:00");
            body.put("dataUpdatedNote", "测试");
            body.put("caliberNote", "测试");
            body.put("warnings", List.of());
            writeJson(e, body);
          });
      reporting.start();

      // ---- 上游 03（catalog 服务）的替身：内部 SKU → SKU 编码 ----
      catalog = HttpServer.create(new InetSocketAddress(0), 0);
      catalog.createContext(
          "/catalog/sku-code/skus/",
          e -> {
            if (catalogDown.get() > 0) {
              e.sendResponseHeaders(500, -1);
              e.close();
              return;
            }
            String raw = e.getRequestURI().getPath();
            String id = raw.substring(raw.lastIndexOf('/') + 1);
            String skuCode = skuCodeById.get(UUID.fromString(id));
            if (skuCode == null) {
              e.sendResponseHeaders(404, -1);
              e.close();
              return;
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("skuId", id);
            body.put("skuCode", skuCode);
            body.put("skuName", "测试 SKU " + skuCode);
            body.put("spuCode", "SPU-" + skuCode);
            writeJson(e, body);
          });
      catalog.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  /**
   * 🔴 **先停外部服务、再停容器**（本仓既有约定）。
   *
   * <p>不回收的话，一个测试类就会在机器上留下一个 postgres:16-alpine 容器；
   * 同一个服务堆到上百个容器会把机器压进 swap ⇒ **断言假红**。
   */
  @AfterAll
  static void stopEverything() {
    if (identity != null) identity.stop(0);
    if (reporting != null) reporting.stop(0);
    if (catalog != null) catalog.stop(0);
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
    profitDown.set(0);
    catalogDown.set(0);
    profitBySku.clear();
    skuCodeById.clear();
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "channel_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://localhost:" + identity.getAddress().getPort());
    r.add("CATALOG_URL", () -> "http://localhost:" + catalog.getAddress().getPort());
    r.add("REPORTING_URL", () -> "http://localhost:" + reporting.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
    r.add("spring.datasource.hikari.maximum-pool-size", () -> "1");
  }

  @Autowired AdBiddingService service;
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

  /** 播种一个商品（listing）并挂一个内部 SKU（listing → SKU 一对一）。 */
  UUID seedListingWithSku(String shopCode, String externalItemId, UUID internalSkuId) {
    UUID listingId = UUID.randomUUID();
    execAsTenant(a,
        "insert into listing (id,tenant_id,shop_id,external_item_id,title,status)"
            + " values ('" + listingId + "','" + a + "','" + shopIdOf(shopCode) + "','" + externalItemId
            + "','测试商品 " + externalItemId + "','ON_SALE')");
    execAsTenant(a,
        "insert into listing_variant (id,tenant_id,listing_id,external_variant_id,mapping_status,internal_sku_id)"
            + " values ('" + UUID.randomUUID() + "','" + a + "','" + listingId + "','VAR-1','MAPPED','"
            + internalSkuId + "')");
    return listingId;
  }

  /** 播种一条 listing_keyword（关键词 → 商品 的挂载点）。 */
  UUID seedListingKeyword(UUID listingId, String keywordText) {
    UUID id = UUID.randomUUID();
    execAsTenant(a,
        "insert into listing_keyword (id,tenant_id,listing_id,keyword,platform_code,site_code)"
            + " values ('" + id + "','" + a + "','" + listingId + "','" + keywordText + "','AMAZON','US')");
    return id;
  }

  /** 播种一个关键词（可挂到 listing_keyword 上）。 */
  UUID seedKeyword(
      String shopCode,
      String externalCampaignId,
      String text,
      BigDecimal bid,
      String bidCurrency,
      String metricCurrency,
      UUID listingKeywordId) {
    UUID id = UUID.randomUUID();
    execAsTenant(a,
        "insert into ad_keyword (id,tenant_id,platform_id,shop_id,external_campaign_id,"
            + " external_keyword_id,keyword,internal_ad_type,platform_ad_type,bid,bid_currency,"
            + " metric_currency,status,listing_keyword_id)"
            + " values ('" + id + "','" + a + "','" + AMAZON + "','" + shopIdOf(shopCode) + "','"
            + externalCampaignId + "','KW-" + text + "','" + text + "','AUTO_DISCOVERY','sponsored_products',"
            + bid + ",'" + bidCurrency + "','" + metricCurrency + "','ACTIVE',"
            + (listingKeywordId == null ? "null" : "'" + listingKeywordId + "'") + ")");
    return id;
  }

  /** 播种一条关键词指标（花费 / 销售额，**平台自报口径**）。 */
  void seedMetric(UUID keywordId, LocalDate day, String spend, String sales, String currency) {
    execAsTenant(a,
        "insert into ad_keyword_metric (id,tenant_id,keyword_id,bucket_date,impressions,clicks,"
            + " conversions,spend,sales,currency,synced_at,source,batch_key)"
            + " values ('" + UUID.randomUUID() + "','" + a + "','" + keywordId + "','" + day
            + "',100,10,1," + spend + "," + sales + ",'" + currency
            + "','2026-10-31T23:00:00+08:00','PLATFORM','batch-1')");
  }

  /** 播种一条生效的归因规则。 */
  UUID seedAttributionRule() {
    UUID id = UUID.randomUUID();
    execAsTenant(a,
        "insert into ad_attribution_rule (id,tenant_id,rule_code,attribution_model,"
            + " attribution_window_days,status,version)"
            + " values ('" + id + "','" + a + "','LAST-CLICK','LAST_CLICK',30,'ACTIVE',0)");
    return id;
  }

  /** 播种一条归因事实（**归因销售额**）。 */
  void seedAttributionFact(
      UUID ruleId,
      long ruleVersion,
      UUID campaignId,
      String shopCode,
      UUID internalSkuId,
      String attributedSales,
      String currency,
      String orderAt) {
    UUID orderId = UUID.randomUUID();
    execAsTenant(a,
        "insert into ad_attribution_order (id,tenant_id,campaign_id,shop_code,platform_code,"
            + " external_order_no,order_at,internal_sku_id,currency,attributed_spend,attributed_sales,"
            + " attributed_orders,attributed_units,data_as_of)"
            + " values ('" + orderId + "','" + a + "','" + campaignId + "','" + shopCode
            + "','AMAZON','ORD-" + orderId + "','" + orderAt + "','" + internalSkuId + "','" + currency
            + "',0," + attributedSales + ",1,1,now())");
    execAsTenant(a,
        "insert into ad_attribution_fact (id,tenant_id,rule_id,rule_code,rule_version,"
            + " attribution_model,attribution_window_days,order_id,campaign_id,shop_code,platform_code,"
            + " external_order_no,internal_sku_id,currency,order_at,chosen_click_id,chosen_click_at,"
            + " attribution_status,attributed_spend,attributed_sales,attributed_orders,attributed_units,"
            + " eligible_click_count)"
            + " select '" + UUID.randomUUID() + "','" + a + "','" + ruleId + "','LAST-CLICK',"
            + ruleVersion + ",'LAST_CLICK',30,'" + orderId + "',o.campaign_id,o.shop_code,"
            + "o.platform_code,o.external_order_no,o.internal_sku_id,o.currency,o.order_at,'CLICK-1','"
            + orderAt + "','ATTRIBUTED',0," + attributedSales + ",1,1,1"
            + " from ad_attribution_order o where o.id='" + orderId + "'");
  }

  /** 播种一个活动（归因事实要挂活动）。 */
  UUID seedCampaign(String shopCode, String externalCampaignId) {
    // 🔴 归一口径的来源必须能在 platform_enum_mapping 里查到（V146 的触发器会验）
    exec("insert into platform_enum_mapping (id,platform_id,enum_kind,external_value,internal_value)"
        + " values ('" + UUID.randomUUID() + "','" + AMAZON + "','AD_TYPE','sponsored_products',"
        + "'AUTO_DISCOVERY') on conflict (platform_id,enum_kind,external_value) do nothing");
    UUID profileId = UUID.randomUUID();
    String existing =
        execOneAsTenant(
            a,
            "select id::text from ad_type_profile where tenant_id='" + a + "'"
                + " and platform_id='" + AMAZON + "' and platform_ad_type='sponsored_products'");
    if (existing != null) {
      profileId = UUID.fromString(existing);
    } else {
      execAsTenant(a,
          "insert into ad_type_profile (id,tenant_id,platform_id,platform_ad_type,internal_ad_type,"
              + " display_name,auction_model,objective,supports_targeting,supports_bid_schedule,"
              + " supports_negative_kw,supports_creative,supports_ascii_ad,mapping_source,enum_kind,"
              + " created_at,updated_at)"
              + " select '" + profileId + "','" + a + "',p.id,'sponsored_products','AUTO_DISCOVERY',"
              + "'测试登记','CPC','SALES',true,true,true,true,false,'PLATFORM_DEFAULT','AD_TYPE',"
              + "now(),now() from platform p where p.code='AMAZON'");
    }
    UUID campaignId = UUID.randomUUID();
    execAsTenant(a,
        "insert into ad_campaign (id,tenant_id,platform_id,shop_code,ad_type_profile_id,"
            + " platform_ad_type,internal_ad_type,ad_type_mapping_source,external_campaign_id,"
            + " campaign_name,status,currency,daily_budget,data_as_of,last_synced_at,created_at,updated_at)"
            + " select '" + campaignId + "','" + a + "',p.id,'" + shopCode + "','" + profileId
            + "','sponsored_products','AUTO_DISCOVERY','PLATFORM_DEFAULT','" + externalCampaignId
            + "','测试活动','ENABLED','USD',100,'2026-10-31T23:00:00+08:00','2026-10-31T23:00:00+08:00',"
            + "now(),now() from platform p where p.code='AMAZON'");
    return campaignId;
  }

  /** 建一条策略的请求体。 */
  AdBiddingPolicyRequest policyRequest(
      String code, String shopCode, String currency, String targetAcos, String targetRoas) {
    return new AdBiddingPolicyRequest(
        code, "策略 " + code, AMAZON.toString(), shopCode, "DOWN_ONLY", currency, "SHOP", null,
        null, null, new BigDecimal("0.30"), new BigDecimal("500.0000"),
        targetAcos == null ? null : new BigDecimal(targetAcos),
        targetRoas == null ? null : new BigDecimal(targetRoas),
        Boolean.FALSE, "测试");
  }

  /** 建一条策略（**不试运行**）。 */
  AdBiddingPolicyView newPolicy(String code, String shopCode, String targetAcos) {
    return as(
        "channel.operation.create",
        () -> service.createPolicy(policyRequest(code, shopCode, "USD", targetAcos, null)));
  }

  // ==================================================================
  // 一、动态竞价策略（三档枚举）
  // ==================================================================

  @Test
  @DisplayName("14.2.1.1：动态竞价策略是三档枚举，非法档位被拒（不是自由文本）")
  void dynamicStrategyIsAThreeValueEnum() {
    seedShop("SHOP-A");
    var thrown =
        catchThrowable(
            () -> as(
                "channel.operation.create",
                () ->
                    service.createPolicy(
                        new AdBiddingPolicyRequest(
                            "P-1", "策略 1", AMAZON.toString(), "SHOP-A", "随便写点什么", "USD",
                            "SHOP", null, null, null, new BigDecimal("0.30"), new BigDecimal("500.0000"),
                            new BigDecimal("0.25"), null, Boolean.FALSE, ""))));
    assertThat(thrown).as("动态竞价策略只允许三档").isNotNull();
    assertThat(thrown.getMessage()).contains("UP_AND_DOWN").contains("DOWN_ONLY").contains("UP_ONLY");
  }

  @Test
  @DisplayName("14.2.1.1：激活策略会进**待下发队列**（平台广告 API 未交付 ⇒ 界面写「待下发」）")
  void activationEnqueuesPendingDispatch() {
    seedShop("SHOP-A");
    var policy = newPolicy("P-1", "SHOP-A", "0.25");
    assertThat(policy.status()).isEqualTo("DRAFT");

    as(
        "channel.operation.create",
        () -> service.changeStatus(policy.id(), new AdBiddingStatusRequest("ACTIVE", "激活")));

    var page = as("channel.operation.read", () -> service.dispatches("PENDING", null, 0, 50));
    assertThat(page.items()).as("激活后必须有一条待下发").hasSize(1);
    var dispatch = page.items().get(0);
    assertThat(dispatch.actionType()).isEqualTo("SET_BIDDING_STRATEGY");
    assertThat(dispatch.dispatchStatus()).isEqualTo("PENDING");
    assertThat(dispatch.actionPayload()).containsKey("dynamicBiddingStrategy");
    assertThat(dispatch.actionPayload().get("dynamicBiddingStrategy")).isEqualTo("DOWN_ONLY");
  }

  @Test
  @DisplayName("14.2.1.1：目标 ACOS 与目标 ROAS **二选一**，同时给两个被拒")
  void targetsAreMutuallyExclusive() {
    seedShop("SHOP-A");
    var thrown =
        catchThrowable(
            () -> as(
                "channel.operation.create",
                () ->
                    service.createPolicy(
                        new AdBiddingPolicyRequest(
                            "P-2", "策略 2", AMAZON.toString(), "SHOP-A", "DOWN_ONLY", "USD",
                            "SHOP", null, null, null, new BigDecimal("0.30"), new BigDecimal("500.0000"),
                            new BigDecimal("0.25"), new BigDecimal("4.000000"), Boolean.FALSE, ""))));
    assertThat(thrown).as("ACOS 与 ROAS 互为倒数，同时给两个必须被拒").isNotNull();
    assertThat(thrown.getMessage()).contains("二选一");

    // 只给 ROAS ⇒ 服务端换算出 ACOS = 1 / ROAS
    var policy =
        as(
            "channel.operation.create",
            () ->
                service.createPolicy(
                    new AdBiddingPolicyRequest(
                        "P-3", "策略 3", AMAZON.toString(), "SHOP-A", "DOWN_ONLY", "USD",
                        "SHOP", null, null, null, new BigDecimal("0.30"), new BigDecimal("500.0000"),
                        null, new BigDecimal("4.000000"), Boolean.FALSE, "")));
    assertThat(policy.targetRoas()).isEqualByComparingTo("4.000000");
    assertThat(policy.targetAcos()).isEqualByComparingTo("0.250000");
  }

  @Test
  @DisplayName("14.2.1.1：DB 层钉死「目标二选一」——绕过服务层直接写 SQL 也写不进两个目标")
  void dbRejectsTwoTargets() {
    seedShop("SHOP-A");
    var policy = newPolicy("P-4", "SHOP-A", "0.25");
    var state =
        sqlStateOf(
            () ->
                execAsTenant(
                    a,
                    "update ad_bidding_policy set target_roas=4 where tenant_id='" + a + "' and id='"
                        + policy.id() + "'"));
    assertThat(state).as("DB 层 CHECK（num_nonnulls=1）必须拒绝两个目标").isEqualTo("23514");
  }

  // ==================================================================
  // 二、两道闸 + 走 14.1.3.1 的写路径
  // ==================================================================

  @Test
  @DisplayName("14.2.1.1：改竞价**真的走 14.1.3.1 的写路径**（竞价真的改了 + 有审计 + 护栏继承）")
  void adjustmentGoesThroughTheDeliveredWritePath() {
    seedShop("SHOP-A");
    var keywordId = seedKeyword("SHOP-A", "CAMP-1", "wireless mouse", new BigDecimal("2.0000"),
        "USD", "USD", null);
    var policy = newPolicy("P-10", "SHOP-A", "0.25");

    var result =
        as(
            "channel.operation.create",
            () ->
                service.adjust(
                    new AdBiddingAdjustmentRequest(
                        policy.id(), null, "USD", Boolean.FALSE,
                        List.of(new AdBiddingAdjustTarget(keywordId.toString(), new BigDecimal("2.5000"))),
                        "bulk-1", "调整"),
                    bearerA));

    assertThat(result.status()).isEqualTo("APPLIED");
    assertThat(result.appliedCount()).isEqualTo(1);
    // 🔴 本地竞价真的改了
    assertThat(bidOf(keywordId)).isEqualByComparingTo("2.5000");
    // 🔴 14.1.3.1 的批量任务留痕了（bulk_action_id 指过去）
    assertThat(result.bulkActionId()).isNotBlank();
    assertThat(
            countAsTenant(
                a, "select count(*) from ad_keyword_bulk_action where id='" + result.bulkActionId() + "'"))
        .as("必须落在 14.1.3.1 已交付的写路径上").isEqualTo(1);
    // 🔴 护栏快照带 14.1.3.1 的三条上限（继承，不另设）
    assertThat(result.guardrail()).containsKeys(
        "upstreamMaxBidDeltaRatio", "upstreamMaxBidAbsolute", "upstreamMaxTotalBidIncrease",
        "effectiveAdjustRatio", "rangeMinBid", "rangeMaxBid");
    assertThat(result.guardrail().get("upstreamMaxBidAbsolute").toString())
        .contains("1000");
    // 🔴 真调之后进待下发队列（**待下发**，不是「已生效」）
    var dispatches = as("channel.operation.read", () -> service.dispatches(null, null, 0, 50));
    assertThat(dispatches.items()).hasSize(1);
    assertThat(dispatches.items().get(0).dispatchStatus()).isEqualTo("PENDING");
    assertThat(dispatches.items().get(0).actionType()).isEqualTo("SET_BID");
  }

  @Test
  @DisplayName("14.2.1.1：闸① 竞价范围上限 —— 越限**逐条拒绝并留痕**，绝不静默截断")
  void bidRangeCapRejectsPerItemWithoutTruncating() {
    seedShop("SHOP-A");
    var keywordId = seedKeyword("SHOP-A", "CAMP-1", "wireless mouse", new BigDecimal("2.0000"),
        "USD", "USD", null);
    var policy =
        as(
            "channel.operation.create",
            () ->
                service.createPolicy(
                    new AdBiddingPolicyRequest(
                        "P-11", "策略 11", AMAZON.toString(), "SHOP-A", "DOWN_ONLY", "USD",
                        "SHOP", null, new BigDecimal("1.0000"), new BigDecimal("3.0000"),
                        new BigDecimal("0.30"), new BigDecimal("500.0000"),
                        new BigDecimal("0.25"), null, Boolean.FALSE, "")));

    var result =
        as(
            "channel.operation.create",
            () ->
                service.adjust(
                    new AdBiddingAdjustmentRequest(
                        policy.id(), null, "USD", Boolean.FALSE,
                        List.of(new AdBiddingAdjustTarget(keywordId.toString(), new BigDecimal("9.0000"))),
                        "bulk-range", "越界"),
                    bearerA));

    assertThat(result.status()).isEqualTo("REJECTED");
    assertThat(result.failedCount()).isEqualTo(1);
    var item = result.items().get(0);
    assertThat(item.status()).isEqualTo("REJECTED");
    assertThat(item.guardrailCode()).as("必须写明被哪道闸拦下").isEqualTo("RANGE_MAX");
    assertThat(item.reason()).contains("竞价范围上限");
    // 🔴 绝不静默截断：竞价一个字节都没动
    assertThat(bidOf(keywordId)).isEqualByComparingTo("2.0000");
  }

  @Test
  @DisplayName("14.2.1.1：闸② 单次调整额度 —— ±50% 越限被拒（绝对值模式下这条护栏也必须生效）")
  void singleAdjustCapRejectsPerItem() {
    seedShop("SHOP-A");
    var keywordId = seedKeyword("SHOP-A", "CAMP-1", "wireless mouse", new BigDecimal("2.0000"),
        "USD", "USD", null);
    var policy = newPolicy("P-12", "SHOP-A", "0.25");

    var result =
        as(
            "channel.operation.create",
            () ->
                service.adjust(
                    new AdBiddingAdjustmentRequest(
                        policy.id(), null, "USD", Boolean.FALSE,
                        List.of(new AdBiddingAdjustTarget(keywordId.toString(), new BigDecimal("9.0000"))),
                        "bulk-amount", "越额度"),
                    bearerA));

    assertThat(result.failedCount()).isEqualTo(1);
    var item = result.items().get(0);
    assertThat(item.status()).isEqualTo("REJECTED");
    assertThat(item.guardrailCode()).as("被闸②拦下").isEqualTo("ADJUST_RATIO");
    assertThat(bidOf(keywordId)).isEqualByComparingTo("2.0000");
  }

  @Test
  @DisplayName("14.2.1.1：试运行只算不调 —— 逐条明细在，竞价一个字节都没动")
  void dryRunDoesNotTouchTheBid() {
    seedShop("SHOP-A");
    var keywordId = seedKeyword("SHOP-A", "CAMP-1", "wireless mouse", new BigDecimal("2.0000"),
        "USD", "USD", null);
    var policy = newPolicy("P-13", "SHOP-A", "0.25");

    var result =
        as(
            "channel.operation.create",
            () ->
                service.adjust(
                    new AdBiddingAdjustmentRequest(
                        policy.id(), null, "USD", Boolean.TRUE,
                        List.of(new AdBiddingAdjustTarget(keywordId.toString(), new BigDecimal("2.5000"))),
                        "bulk-dry", "试运行"),
                    bearerA));

    assertThat(result.runMode()).isEqualTo("DRY_RUN");
    assertThat(result.items()).hasSize(1);
    assertThat(result.items().get(0).afterBid()).isEqualByComparingTo("2.5000");
    assertThat(bidOf(keywordId)).as("试运行不许真的改").isEqualByComparingTo("2.0000");
    // 🔴 试运行不进待下发队列（没有真的改，就没有什么要下发）
    var dispatches = as("channel.operation.read", () -> service.dispatches(null, null, 0, 50));
    assertThat(dispatches.items()).isEmpty();
  }

  @Test
  @DisplayName("14.2.1.1：幂等 —— 同一幂等键重投不会重复调价")
  void adjustmentIsIdempotent() {
    seedShop("SHOP-A");
    var keywordId = seedKeyword("SHOP-A", "CAMP-1", "wireless mouse", new BigDecimal("2.0000"),
        "USD", "USD", null);
    var policy = newPolicy("P-14", "SHOP-A", "0.25");
    var request =
        new AdBiddingAdjustmentRequest(
            policy.id(), null, "USD", Boolean.FALSE,
            List.of(new AdBiddingAdjustTarget(keywordId.toString(), new BigDecimal("2.5000"))),
            "bulk-idem", "幂等");

    var first = as("channel.operation.create", () -> service.adjust(request, bearerA));
    var second = as("channel.operation.create", () -> service.adjust(request, bearerA));

    assertThat(second.id()).isEqualTo(first.id());
    assertThat(bidOf(keywordId)).isEqualByComparingTo("2.5000");
    assertThat(
            countAsTenant(
                a, "select count(*) from ad_bidding_adjustment where tenant_id='" + a + "'"))
        .as("重投不会多出第二条任务").isEqualTo(1);
  }

  // ==================================================================
  // 三、目标 ACOS 反推竞价建议
  // ==================================================================

  @Test
  @DisplayName("14.2.1.1：目标 ACOS 反推 —— 倍率 = 目标 ACOS ÷ 当前 ACOS，逐条落到关键词自己的价上")
  void targetAcosProducesSuggestedBid() {
    seedShop("SHOP-A");
    UUID skuId = UUID.randomUUID();
    skuCodeById.put(skuId, "SKU-001");
    // 利润：单品收入 1000、成本 600、广告费 100 ⇒ 净利 300 ⇒
    // 盈亏平衡 ACOS =（净利 + 广告费）÷ 收入 = (300 + 100) / 1000 = 0.4
    profitBySku.put("SKU-001", new BigDecimal[] {new BigDecimal("1000"), new BigDecimal("600"), new BigDecimal("100")});
    var listingId = seedListingWithSku("SHOP-A", "ASIN-1", skuId);
    var listingKeywordId = seedListingKeyword(listingId, "wireless mouse");
    var keywordId = seedKeyword("SHOP-A", "CAMP-1", "wireless mouse", new BigDecimal("2.0000"),
        "USD", "USD", listingKeywordId);
    seedMetric(keywordId, LocalDate.of(2026, 10, 10), "100.0000", "900.0000", "USD");
    seedAttributionRule();
    UUID campaignId = seedCampaign("SHOP-A", "CAMP-1");
    seedAttributionFact(
        UUID.fromString(
            execOneAsTenant(a, "select id::text from ad_attribution_rule where tenant_id='" + a + "'")),
        0L, campaignId, "SHOP-A", skuId, "1000.0000", "USD", "2026-10-10T10:00:00+08:00");

    var policy = newPolicy("P-20", "SHOP-A", "0.25");
    var suggestion =
        as(
            "channel.operation.create",
            () ->
                service.generateSuggestion(
                    new AdBiddingSuggestionRequest(
                        policy.id(), LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), "sg-1", "测试"),
                    bearerA));

    assertThat(suggestion.skuCount()).isEqualTo(1);
    var sku = suggestion.skus().get(0);
    assertThat(sku.skuCode()).isEqualTo("SKU-001");
    assertThat(sku.spend()).isEqualByComparingTo("100.0000");
    assertThat(sku.attributedSales()).isEqualByComparingTo("1000.0000");
    // 🔴 ACOS = 花费 ÷ 归因销售额 = 0.1
    assertThat(sku.currentAcos()).isEqualByComparingTo("0.100000");
    // 🔴 ROAS = 归因销售额 ÷ 花费 = 10
    assertThat(sku.currentRoas()).isEqualByComparingTo("10.000000");
    // 🔴 盈亏平衡 ACOS =（净利 + 广告费）÷ 收入 = 0.4
    assertThat(sku.breakEvenAcos()).isEqualByComparingTo("0.400000");
    assertThat(sku.profitStatus()).isEqualTo("AVAILABLE");
    // 🔴 倍率 = 目标 ACOS ÷ 当前 ACOS = 0.25 / 0.1 = 2.5
    assertThat(sku.bidRatio()).isEqualByComparingTo("2.500000");
    // 🔴 建议竞价 = 当前竞价 × 倍率 = 2 × 2.5 = 5
    var item = sku.items().get(0);
    assertThat(item.currentBid()).isEqualByComparingTo("2.0000");
    assertThat(item.suggestedBid()).isEqualByComparingTo("5.0000");
    assertThat(item.bidDelta()).isEqualByComparingTo("3.0000");
  }

  @Test
  @DisplayName("14.2.1.1：🔴 归因销售额为 0 ⇒ ACOS **不可比（NULL）而不是 0**，建议给不出来")
  void zeroAttributedSalesMakesAcosNotComparable() {
    seedShop("SHOP-A");
    UUID skuId = UUID.randomUUID();
    skuCodeById.put(skuId, "SKU-002");
    profitBySku.put("SKU-002", new BigDecimal[] {new BigDecimal("1000"), new BigDecimal("600"), new BigDecimal("100")});
    var listingId = seedListingWithSku("SHOP-A", "ASIN-2", skuId);
    var listingKeywordId = seedListingKeyword(listingId, "wireless keyboard");
    var keywordId = seedKeyword("SHOP-A", "CAMP-1", "wireless keyboard", new BigDecimal("2.0000"),
        "USD", "USD", listingKeywordId);
    seedMetric(keywordId, LocalDate.of(2026, 10, 10), "100.0000", "0.0000", "USD");
    seedAttributionRule();
    seedCampaign("SHOP-A", "CAMP-1");

    var policy = newPolicy("P-21", "SHOP-A", "0.25");
    var suggestion =
        as(
            "channel.operation.create",
            () ->
                service.generateSuggestion(
                    new AdBiddingSuggestionRequest(
                        policy.id(), LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), "sg-2", "测试"),
                    bearerA));

    var sku = suggestion.skus().get(0);
    assertThat(sku.currentAcos()).as("ACOS 的分母是归因销售额 ⇒ 为 0 ⇒ 不可比（null），不是 0").isNull();
    // 🔴 ROAS 的分母是**花费**（这里是 100，不为 0）⇒ ROAS = 0 / 100 = 0
    //    是「可比但真的是零」（花了钱、归因销售额真的是 0），**不是**不可比。
    //    两个口径的「0」含义不同，分母各归各的。
    assertThat(sku.currentRoas()).isEqualByComparingTo("0.000000");
    assertThat(sku.bidRatio()).isNull();
    assertThat(sku.status()).isEqualTo("REJECTED");
    assertThat(sku.guardrailCode()).isEqualTo("NO_BASIS");
    assertThat(sku.items().get(0).suggestedBid()).isNull();
  }

  @Test
  @DisplayName("14.2.1.1：🔴 花费为 0 ⇒ ROAS **不可比（NULL）**，而 ACOS 是 0（真的是零）")
  void zeroSpendMakesRoasNotComparable() {
    seedShop("SHOP-A");
    UUID skuId = UUID.randomUUID();
    skuCodeById.put(skuId, "SKU-005");
    profitBySku.put("SKU-005", new BigDecimal[] {new BigDecimal("1000"), new BigDecimal("600"), new BigDecimal("100")});
    var listingId = seedListingWithSku("SHOP-A", "ASIN-5", skuId);
    var listingKeywordId = seedListingKeyword(listingId, "ergonomic mouse");
    var keywordId = seedKeyword("SHOP-A", "CAMP-1", "ergonomic mouse", new BigDecimal("2.0000"),
        "USD", "USD", listingKeywordId);
    seedMetric(keywordId, LocalDate.of(2026, 10, 10), "0.0000", "100.0000", "USD");
    seedAttributionRule();
    seedCampaign("SHOP-A", "CAMP-1");
    seedAttributionFact(
        UUID.fromString(
            execOneAsTenant(a, "select id::text from ad_attribution_rule where tenant_id='" + a + "'")),
        0L, seedCampaign("SHOP-A", "CAMP-2"), "SHOP-A", skuId, "100.0000", "USD",
        "2026-10-10T10:00:00+08:00");

    var policy = newPolicy("P-25", "SHOP-A", "0.25");
    var suggestion =
        as(
            "channel.operation.create",
            () ->
                service.generateSuggestion(
                    new AdBiddingSuggestionRequest(
                        policy.id(), LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), "sg-5", "测试"),
                    bearerA));

    var sku = suggestion.skus().get(0);
    assertThat(sku.currentRoas()).as("ROAS 的分母是花费 ⇒ 为 0 ⇒ 不可比（null）").isNull();
    assertThat(sku.currentAcos()).as("ACOS 的分母不为 0 ⇒ 真的是零").isEqualByComparingTo("0.000000");
  }

  @Test
  @DisplayName("14.2.1.1：🔴 目标 ACOS 高于盈亏平衡 ACOS ⇒ 建议被否决并写明原因")
  void targetAboveBreakEvenIsRejected() {
    seedShop("SHOP-A");
    UUID skuId = UUID.randomUUID();
    skuCodeById.put(skuId, "SKU-003");
    // 收入 1000、成本 900、广告费 0 ⇒ 净利 100 ⇒ 盈亏平衡 ACOS = 0.1
    profitBySku.put("SKU-003", new BigDecimal[] {new BigDecimal("1000"), new BigDecimal("900"), BigDecimal.ZERO});
    var listingId = seedListingWithSku("SHOP-A", "ASIN-3", skuId);
    var listingKeywordId = seedListingKeyword(listingId, "gaming mouse");
    var keywordId = seedKeyword("SHOP-A", "CAMP-1", "gaming mouse", new BigDecimal("2.0000"),
        "USD", "USD", listingKeywordId);
    seedMetric(keywordId, LocalDate.of(2026, 10, 10), "100.0000", "900.0000", "USD");
    seedAttributionRule();
    UUID campaignId = seedCampaign("SHOP-A", "CAMP-1");
    seedAttributionFact(
        UUID.fromString(
            execOneAsTenant(a, "select id::text from ad_attribution_rule where tenant_id='" + a + "'")),
        0L, campaignId, "SHOP-A", skuId, "1000.0000", "USD", "2026-10-10T10:00:00+08:00");

    // 目标 ACOS 0.30 > 盈亏平衡 0.10 ⇒ 否决
    var policy = newPolicy("P-22", "SHOP-A", "0.30");
    var suggestion =
        as(
            "channel.operation.create",
            () ->
                service.generateSuggestion(
                    new AdBiddingSuggestionRequest(
                        policy.id(), LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), "sg-3", "测试"),
                    bearerA));

    var sku = suggestion.skus().get(0);
    assertThat(sku.breakEvenAcos()).isEqualByComparingTo("0.100000");
    assertThat(sku.status()).isEqualTo("REJECTED");
    assertThat(sku.guardrailCode()).isEqualTo("ABOVE_BREAK_EVEN");
    assertThat(sku.reason()).contains("盈亏平衡");
    assertThat(sku.items().get(0).status()).as("逐条也要标否决并写清原因").isEqualTo("REJECTED");
  }

  @Test
  @DisplayName("14.2.1.1：🔴 利润侧读不到 ⇒ 503（不给一批没校验过盈亏的建议）")
  void profitUpstreamDownYields503() {
    seedShop("SHOP-A");
    UUID skuId = UUID.randomUUID();
    skuCodeById.put(skuId, "SKU-004");
    var listingId = seedListingWithSku("SHOP-A", "ASIN-4", skuId);
    var listingKeywordId = seedListingKeyword(listingId, "optical mouse");
    var keywordId = seedKeyword("SHOP-A", "CAMP-1", "optical mouse", new BigDecimal("2.0000"),
        "USD", "USD", listingKeywordId);
    seedMetric(keywordId, LocalDate.of(2026, 10, 10), "100.0000", "900.0000", "USD");
    seedAttributionRule();
    seedCampaign("SHOP-A", "CAMP-1");

    var policy = newPolicy("P-23", "SHOP-A", "0.25");
    profitDown.set(1);

    var thrown =
        catchThrowable(
            () ->
                as(
                    "channel.operation.create",
                    () ->
                        service.generateSuggestion(
                            new AdBiddingSuggestionRequest(
                                policy.id(), LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31),
                                "sg-4", "测试"),
                            bearerA)));
    assertThat(thrown).as("利润侧读不到必须 503").isNotNull();
    assertThat(thrown.getMessage()).contains("SKU 利润");

    // 🔴 读不到时**没有**落一个批次（不能留下一批「看起来很专业」的建议）
    assertThat(
            countAsTenant(a, "select count(*) from ad_bidding_suggestion where tenant_id='" + a + "'"))
        .isZero();
  }

  @Test
  @DisplayName("14.2.1.1：建议批次幂等 —— 同一次生成重投返回既有批次")
  void suggestionIsIdempotent() {
    seedShop("SHOP-A");
    var policy = newPolicy("P-24", "SHOP-A", "0.25");
    var request =
        new AdBiddingSuggestionRequest(
            policy.id(), LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), "sg-idem", "测试");
    var first = as("channel.operation.create", () -> service.generateSuggestion(request, bearerA));
    var second = as("channel.operation.create", () -> service.generateSuggestion(request, bearerA));
    assertThat(second.id()).isEqualTo(first.id());
    assertThat(
            countAsTenant(a, "select count(*) from ad_bidding_suggestion where tenant_id='" + a + "'"))
        .isEqualTo(1);
  }

  // ==================================================================
  // 四、待下发队列
  // ==================================================================

  @Test
  @DisplayName("14.2.1.1：撤销是**唯一**能改队列的动作；撤销必须写清原因")
  void cancelIsTheOnlyQueueAction() {
    seedShop("SHOP-A");
    var keywordId = seedKeyword("SHOP-A", "CAMP-1", "wireless mouse", new BigDecimal("2.0000"),
        "USD", "USD", null);
    var policy = newPolicy("P-30", "SHOP-A", "0.25");
    as(
        "channel.operation.create",
        () ->
            service.adjust(
                new AdBiddingAdjustmentRequest(
                    policy.id(), null, "USD", Boolean.FALSE,
                    List.of(new AdBiddingAdjustTarget(keywordId.toString(), new BigDecimal("2.5000"))),
                    "bulk-cancel", "调整"),
                bearerA));

    var page = as("channel.operation.read", () -> service.dispatches("PENDING", null, 0, 50));
    assertThat(page.items()).hasSize(1);
    var dispatchId = page.items().get(0).id();

    // 撤销必须写清原因
    var noReason =
        catchThrowable(
            () ->
                as(
                    "channel.operation.create",
                    () -> service.cancelDispatch(dispatchId, new AdBiddingDispatchCancelRequest("  "))));
    assertThat(noReason).as("撤销必须写清原因").isNotNull();

    var cancelled =
        as(
            "channel.operation.create",
            () ->
                service.cancelDispatch(
                    dispatchId, new AdBiddingDispatchCancelRequest("投错了活动，撤回")));
    assertThat(cancelled.dispatchStatus()).isEqualTo("CANCELLED");
    assertThat(cancelled.cancelledAt()).isNotNull();
    assertThat(cancelled.cancelReason()).isEqualTo("投错了活动，撤回");

    // 已撤销的不能再撤销
    var again =
        catchThrowable(
            () ->
                as(
                    "channel.operation.create",
                    () ->
                        service.cancelDispatch(
                            dispatchId, new AdBiddingDispatchCancelRequest("再撤一次"))));
    assertThat(again).isNotNull();
  }

  @Test
  @DisplayName("14.2.1.1：🔴 撤销时刻是 timestamptz，落库写回值必须正确（不是只断言没抛异常）")
  void cancelTimestampRoundTrips() {
    seedShop("SHOP-A");
    var keywordId = seedKeyword("SHOP-A", "CAMP-1", "wireless mouse", new BigDecimal("2.0000"),
        "USD", "USD", null);
    var policy = newPolicy("P-31", "SHOP-A", "0.25");
    as(
        "channel.operation.create",
        () ->
            service.adjust(
                new AdBiddingAdjustmentRequest(
                    policy.id(), null, "USD", Boolean.FALSE,
                    List.of(new AdBiddingAdjustTarget(keywordId.toString(), new BigDecimal("2.5000"))),
                    "bulk-ts", "调整"),
                bearerA));
    var page = as("channel.operation.read", () -> service.dispatches("PENDING", null, 0, 50));
    var dispatchId = page.items().get(0).id();
    var cancelled =
        as(
            "channel.operation.create",
            () ->
                service.cancelDispatch(
                    dispatchId, new AdBiddingDispatchCancelRequest("撤回")));

    String raw =
        execOneAsTenant(
            a,
            "select to_json(cancelled_at)::text from ad_bidding_dispatch where tenant_id='" + a
                + "' and id='" + dispatchId + "'");
    assertThat(raw).isNotNull();
    var stored = OffsetDateTime.parse(raw.replace("\"", ""));
    assertThat(stored.toInstant()).isEqualTo(cancelled.cancelledAt().toInstant());
  }

  // ==================================================================
  // 五、读端点与边界
  // ==================================================================

  @Test
  @DisplayName("14.2.1.1：策略清单真分页真筛选；跨租户 / 不存在返回逐字相同的 404")
  void policyListPaginatesAndFilters() {
    seedShop("SHOP-A");
    seedShop("SHOP-A2");
    seedShop("SHOP-B");
    newPolicy("P-A1", "SHOP-A", "0.25");
    newPolicy("P-A2", "SHOP-A2", "0.30");
    newPolicy("P-B1", "SHOP-B", "0.25");

    var all = as("channel.operation.read", () -> service.policies(null, null, null, 0, 50));
    assertThat(all.total()).isEqualTo(3);
    var pageA = as("channel.operation.read", () -> service.policies("SHOP-A", null, null, 0, 50));
    assertThat(pageA.total()).isEqualTo(1);
    var page1 = as("channel.operation.read", () -> service.policies(null, null, null, 0, 1));
    assertThat(page1.items()).hasSize(1);
    assertThat(page1.total()).as("总数是筛选后的全集，不是当前页").isEqualTo(3);

    var filtered = as("channel.operation.read", () -> service.policies(null, "DRAFT", "P-A1", 0, 50));
    assertThat(filtered.items()).hasSize(1);
    assertThat(filtered.items().get(0).policyCode()).isEqualTo("P-A1");

    var missing =
        catchThrowable(() -> as("channel.operation.read", () -> service.policy(UUID.randomUUID().toString())));
    assertThat(missing).isNotNull();
    assertThat(missing.getMessage()).contains("不存在");
  }

  @Test
  @DisplayName("14.2.1.1：HTTP 端点可用（能力声明 + 下拉项 + 清单）")
  void httpEndpointsWork() throws Exception {
    seedShop("SHOP-A");
    newPolicy("P-40", "SHOP-A", "0.25");

    http.perform(get("/channel/ad-bidding/capabilities").header("Authorization", bearerA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.dynamicStrategies[0]").value("UP_AND_DOWN"))
        .andExpect(jsonPath("$.dynamicStrategies[1]").value("DOWN_ONLY"))
        .andExpect(jsonPath("$.dynamicStrategies[2]").value("UP_ONLY"));

    http.perform(get("/channel/ad-bidding/options").header("Authorization", bearerA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.upstreamGuardrails.maxBidAbsolute").value(1000.0));

    http.perform(get("/channel/ad-bidding/policies").header("Authorization", bearerA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1));

    http.perform(get("/channel/ad-bidding/dispatch").header("Authorization", bearerA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(0));

    // 伪造的租户头不得影响读到的租户
    http.perform(
            get("/channel/ad-bidding/policies")
                .header("Authorization", bearerA)
                .header("X-Tenant-Id", UUID.randomUUID().toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1));
  }

  @Test
  @DisplayName("14.2.1.1：能力声明里「平台回写」与「标记已下发」必须显式说做不了")
  void capabilitiesDeclareWhatIsNotDelivered() {
    var caps = as("channel.operation.read", () -> service.capabilities());
    var byDimension = new LinkedHashMap<String, AdBiddingCapabilityDimension>();
    for (var d : caps.dimensions()) {
      byDimension.put(d.dimension(), d);
    }
    assertThat(byDimension.get("platform_writeback").available()).isFalse();
    assertThat(byDimension.get("platform_writeback").ownerModule()).isNotBlank();
    assertThat(byDimension.get("mark_as_dispatched").available()).isFalse();
    assertThat(byDimension.get("dynamic_bidding_strategy").available()).isTrue();
    assertThat(byDimension.get("profit_breakeven").available()).isTrue();
  }


  @Test
  @DisplayName("14.2.1.1：同一个作用范围只允许一条策略（店铺级 ⇒ 一个店铺一条）")
  void onePolicyPerScope() {
    seedShop("SHOP-A");
    newPolicy("P-50", "SHOP-A", "0.25");
    var thrown =
        catchThrowable(
            () ->
                as(
                    "channel.operation.create",
                    () -> service.createPolicy(policyRequest("P-51", "SHOP-A", "USD", "0.30", null))));
    assertThat(thrown).as("同一个店铺再建一条店铺级策略必须被拒").isNotNull();
  }

  // ==================================================================
  // 工具
  // ==================================================================

  BigDecimal bidOf(UUID keywordId) {
    String raw =
        execOneAsTenant(
            a, "select bid::text from ad_keyword where tenant_id='" + a + "' and id='" + keywordId + "'");
    return raw == null ? null : new BigDecimal(raw);
  }

  static OffsetDateTime at(int day, int hour) {
    return OffsetDateTime.of(2026, 10, day, hour, 0, 0, 0, ZoneOffset.UTC);
  }
}
