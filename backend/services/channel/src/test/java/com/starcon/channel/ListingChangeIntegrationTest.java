package com.starcon.channel;

import static com.starcon.channel.ListingChangeModels.*;
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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
 * 4.5.2.2 上新与变更监控。
 *
 * <p>蓝图：「监控目标 ASIN/竞品的上下架、价格、排名、评论数变化，识别市场机会（如竞品断货涨价）。输入：监控对象列表；抓取频率。输出：变更事件流 + 历史对比。关系：上游 = 抓取任务；下游 = 13 分析、1 工作台。<b>监控对象与自有商品分离</b>，竞品数据不进自有商品模型，只做参考指标。」
 *
 * <p>本测试类钉住这几组事：
 *
 * <ol>
 *   <li><b>🔴🔴 竞品数据不进自有商品模型</b>：本模块**一张自有商品表都不碰** ——
 *       监控对象表没有 listing_id 列，竞品的 ASIN 不会出现在 {@code listing} 表里。
 *   <li><b>🔴🔴 四类变化都要能被识别出来并落成事件</b>：上下架 / 价格 / 排名 / 评论数
 *       —— 不是只存「当前值」。
 *   <li><b>🔴🔴 平台没报 ≠ 变成 0</b>：这次没报价格 ⇒ 不产生价格事件，
 *       而且比较基准回退到<b>上一个已知值</b>，不会吃掉下一次真正的价格变化。
 *   <li><b>🔴🔴 变化量现算，分母为 0 时是 null</b>：不是 0、也不是 100。
 *   <li><b>🔴 首次观测</b>：上下架留一条事件（旧状态 UNKNOWN → 新状态，这就是「上新」），
 *       价格 / 排名 / 评论数**不产生事件**（没有基准就算不出变化）。
 *   <li><b>🔴 机会判定</b>：断货 / 涨价超阈值 / 排名变差；阈值不给 ⇒ <b>不判定</b>；
 *       涨价比例的分母为 0 ⇒ <b>不判定</b>。
 *   <li><b>🔴 幂等与跳过是两件事</b>：同一批次重投 ⇒ 整批不落库（replayed=true）；
 *       同刻重复 ⇒ 覆盖并递增 revision；批内时刻重复 ⇒ 跳过并<b>逐条写明原因</b>。
 *   <li><b>🔴 历史对比是服务端 SQL 聚合</b>：价格 / 排名 / 评论数 / 上下架四维，
 *       比率现算，**不落表**。
 *   <li><b>🔴 全程标注「参考数据」</b>：每个响应都带 dataSource/sourceNote；
 *       capabilities 里 02 抓取调度器 / 13 分析 / 1 工作台 三段 available=false。
 *   <li><b>🔴 DB 层闸门</b>：事件引用的快照必须属于同一监控对象（触发器）；
 *       已阅的机会判定结论不可再改（触发器）；五张表 FORCE RLS。
 *   <li><b>🔴 timestamptz 往返</b>：观测时间写回值<b>逐字相等</b>（+08:00 非 UTC 偏移）。
 *   <li><b>真分页</b>：{@code total} 是服务端 count 出来的，翻页能翻到不同的数据。
 * </ol>
 *
 * <p><b>时刻一律钉死</b>，不在断言中途改时刻。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ListingChangeIntegrationTest {

  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID user = UUID.randomUUID();
  static HttpServer identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

  UUID a, b;
  AccessService.Access actorA, actorB;
  String bearerA;

  // 🔴 直接用 V1 已播种的平台码表，不自己造平台（platform.code UNIQUE，自造会撞码）
  static final String AMAZON = "AMAZON";

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
    // 连接池压到 1：多租户串租户在单连接下最容易暴露
    r.add("spring.datasource.hikari.maximum-pool-size", () -> "1");
  }

  @Autowired ListingChangeService service;
  @Autowired AccessService access;
  @Autowired MockMvc http;

  // ==================================================================
  // 辅助
  // ==================================================================

  <T> T as(AccessService.Access who, String permission, java.util.function.Supplier<T> work) {
    return access.run(who, permission, work);
  }

  static final String ALL_SCOPE_GRANTS =
      "[{\"permissions\":[\"channel.operation.read\",\"channel.operation.create\","
          + "\"channel.operation.update\",\"channel.operation.delete\"],\"scopeMode\":\"ALL\"}]";

  static void execAsTenant(UUID tenant, String sql) {
    try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute("select set_config('app.current_tenant','" + tenant + "',false)");
      s.execute("select set_config('app.actor','" + user + "',false)");
      s.execute("select set_config('app.department','',false)");
      s.execute("select set_config('app.permission','channel.operation.update',false)");
      s.execute("select set_config('app.grants','" + ALL_SCOPE_GRANTS + "',false)");
      s.execute(sql);
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  /** 以管理员连接跑一条 SQL（只用于读全局码表 / 制造 DB 层违规，不受 RLS 约束的事实）。 */
  static Object scalar(String sql) {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement();
        var rs = s.executeQuery(sql)) {
      return rs.next() ? rs.getObject(1) : null;
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  /** 跑一条会违反约束的 SQL，返回 PG 的 SQLSTATE。 */
  static String sqlState(String sql) {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(sql);
      return "00000";
    } catch (SQLException e) {
      return e.getSQLState();
    }
  }

  static OffsetDateTime at(int day, int hour) {
    return OffsetDateTime.of(2026, 10, day, hour, 30, 0, 0, ZoneOffset.UTC);
  }

  ListingChangeWatchRow createWatch(AccessService.Access who, String asin) {
    return createWatch(who, asin, "COMPETITOR", null, null);
  }

  ListingChangeWatchRow createWatch(
      AccessService.Access who,
      String asin,
      String targetKind,
      BigDecimal priceIncreaseRatioThreshold,
      Integer rankWorsenThreshold) {
    return as(
        who,
        "channel.operation.create",
        () ->
            service.createWatch(
                new SaveListingChangeWatchRequest(
                    AMAZON, null, asin, targetKind, "竞品 " + asin, "BrandX",
                    360, "ACTIVE", null, null, priceIncreaseRatioThreshold, rankWorsenThreshold,
                    "测试用")));
  }

  static ListingChangeObservationInput obs(
      OffsetDateTime observedAt,
      String status,
      String price,
      String rank,
      String review) {
    return new ListingChangeObservationInput(
        observedAt,
        status,
        price == null ? null : new BigDecimal(price),
        "USD",
        rank == null ? null : Integer.valueOf(rank),
        "BSR",
        null,
        review == null ? null : new BigDecimal(review),
        Map.of());
  }

  ListingChangeCrawlResult ingest(
      AccessService.Access who, UUID watchId, String key, OffsetDateTime observedAt,
      ListingChangeObservationInput... observations) {
    return as(
        who,
        "channel.operation.create",
        () ->
            service.ingest(
                new IngestListingChangeCrawlRequest(
                    watchId, "PLATFORM", "API", observedAt, 30, key, "抓取", Map.of(),
                    List.of(observations))));
  }

  // ==================================================================
  // 1. 监控对象
  // ==================================================================

  @Test
  @DisplayName("4.5.2.2 监控对象可建可改可删，回执写明一并删掉多少条事实")
  void watchCrud() {
    var watch = createWatch(actorA, "B0-ASIN-1");
    assertThat(watch.externalItemId()).isEqualTo("B0-ASIN-1");
    assertThat(watch.platformCode()).isEqualTo(AMAZON);
    assertThat(watch.targetKind()).isEqualTo("COMPETITOR");
    assertThat(watch.crawlIntervalMinutes()).isEqualTo(360);
    assertThat(watch.status()).isEqualTo("ACTIVE");
    // 🔴 每个响应都带「参考数据」标识
    var watchPage =
        as(actorA, "channel.operation.read", () -> service.watches(null, null, null, null, 0, 50));
    assertThat(watchPage.dataSource()).isEqualTo("PLATFORM_SCRAPED");
    assertThat(watchPage.sourceNote()).contains("仅供参考");

    // 改抓取频率与阈值
    var updated =
        as(
            actorA,
            "channel.operation.update",
            () ->
                service.updateWatch(
                    watch.watchId().toString(),
                    new SaveListingChangeWatchRequest(
                        AMAZON, null, "B0-ASIN-1", "COMPETITOR", "竞品改名了", "BrandX",
                        60, "PAUSED", Boolean.FALSE, Boolean.TRUE,
                        new BigDecimal("0.15"), 5, "改了")));
    assertThat(updated.crawlIntervalMinutes()).isEqualTo(60);
    assertThat(updated.status()).isEqualTo("PAUSED");
    assertThat(updated.outOfStockOpportunity()).isFalse();
    assertThat(updated.listedOpportunity()).isTrue();
    assertThat(updated.priceIncreaseRatioThreshold()).isEqualByComparingTo("0.15");
    assertThat(updated.rankWorsenThreshold()).isEqualTo(5);
    assertThat(updated.title()).isEqualTo("竞品改名了");

    // 🔴 目标身份不可改（改了会让历史事件挂到别的竞品上）
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    as(
                        actorA,
                        "channel.operation.update",
                        () ->
                            service.updateWatch(
                                watch.watchId().toString(),
                                new SaveListingChangeWatchRequest(
                                    AMAZON, null, "B0-ASIN-OTHER", "COMPETITOR", "", "",
                                    360, "ACTIVE", null, null, null, null, "")))))
        .as("不能改监控对象指向的 ASIN")
        .isNotNull();

    // 删掉
    // 🔴 刚才把它暂停了，暂停期间不接收 —— 先恢复再录
    as(
        actorA,
        "channel.operation.update",
        () ->
            service.updateWatch(
                watch.watchId().toString(),
                new SaveListingChangeWatchRequest(
                    AMAZON, null, "B0-ASIN-1", "COMPETITOR", "竞品改名了", "BrandX",
                    60, "ACTIVE", Boolean.FALSE, Boolean.TRUE,
                    new BigDecimal("0.15"), 5, "改了")));
    ingest(actorA, watch.watchId(), "k-del-1", at(1, 10), obs(at(1, 10), "LISTED", "10.00", "12", "50"));
    var deleted =
        as(actorA, "channel.operation.delete", () -> service.deleteWatch(watch.watchId().toString()));
    assertThat(deleted.snapshotCount()).isEqualTo(1);
    assertThat(deleted.eventCount()).isEqualTo(1);
    assertThat(deleted.crawlCount()).isEqualTo(1);

    assertThat(
            as(actorA, "channel.operation.read", () -> service.watches(null, null, null, null, 0, 50))
                .items())
        .isEmpty();
  }

  @Test
  @DisplayName("4.5.2.2 同一个平台的同一个 ASIN 不许配两套监控对象（409）")
  void duplicateAsinIsRejected() {
    createWatch(actorA, "B0-ASIN-DUP");
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () -> createWatch(actorA, "B0-ASIN-DUP")))
        .as("同一个竞品配两套抓取频率是配置错误")
        .isNotNull();
  }

  // ==================================================================
  // 2. 🔴 竞品数据不进自有商品模型
  // ==================================================================

  @Test
  @DisplayName("4.5.2.2 🔴 竞品数据不进自有商品模型：监控对象表没有 listing_id，竞品 ASIN 不进 listing 表")
  void competitorDataNeverTouchesOwnProductModel() {
    var watch = createWatch(actorA, "B0-COMPETITOR-ASIN");
    ingest(actorA, watch.watchId(), "k-iso-1", at(1, 10), obs(at(1, 10), "LISTED", "19.99", "7", "120"));
    ingest(actorA, watch.watchId(), "k-iso-2", at(2, 10), obs(at(2, 10), "LISTED", "24.99", "9", "135"));

    // 🔴 本模块的五张表**没有任何自有商品列**
    for (String table :
        List.of(
            "listing_change_watch",
            "listing_change_crawl",
            "listing_change_snapshot",
            "listing_change_event",
            "listing_change_opportunity")) {
      Long ownColumns =
          (Long)
              scalar(
                  "select count(*) from information_schema.columns where table_name='"
                      + table
                      + "' and column_name in ('listing_id','sku_id','internal_sku_id','shop_id')");
      assertThat(ownColumns).as(table + " 不得有自有商品列").isZero();
    }

    // 🔴 竞品的 ASIN 不出现在自有商品表里（本模块一次也没碰过 listing）
    Long listingRows =
        (Long) scalar("select count(*) from listing where external_item_id='B0-COMPETITOR-ASIN'");
    assertThat(listingRows).as("竞品数据不得写进自有商品表").isZero();
  }

  // ==================================================================
  // 3. 🔴 四类变化都要能被识别出来并落成事件
  // ==================================================================

  @Test
  @DisplayName("4.5.2.2 🔴 四类变化都被识别并落成事件（上下架 / 价格 / 排名 / 评论数）")
  void allFourChangeTypesAreDetected() {
    var watch = createWatch(actorA, "B0-FOUR");
    ingest(actorA, watch.watchId(), "k-4-1", at(1, 10),
        obs(at(1, 10), "LISTED", "10.00", "20", "100"));
    // 第二次：四维全变
    ingest(actorA, watch.watchId(), "k-4-2", at(2, 10),
        obs(at(2, 10), "OUT_OF_STOCK", "12.50", "35", "104"));

    var events =
        as(
            actorA,
            "channel.operation.read",
            () -> service.events(watch.watchId().toString(), null, null, null, null, 0, 50));
    // 1（首次观测的上下架）+ 4（四维各一次变化）= 5
    assertThat(events.items()).hasSize(5);

    var byType = new java.util.HashMap<String, ListingChangeEventRow>();
    for (ListingChangeEventRow e : events.items()) {
      // 事件按判定时刻倒序 ⇒ 第一个就是最新的一次变化
      byType.putIfAbsent(e.changeType(), e);
    }
    assertThat(byType.keySet())
        .containsExactlyInAnyOrder("LISTING_STATUS", "PRICE", "RANK", "REVIEW_COUNT");

    // 上下架：LISTED → OUT_OF_STOCK
    var status = byType.get("LISTING_STATUS");
    assertThat(status.previousState()).isEqualTo("LISTED");
    assertThat(status.currentState()).isEqualTo("OUT_OF_STOCK");
    assertThat(status.direction()).isEqualTo("CHANGED");

    // 价格：10.00 → 12.50，变化量与比例**现算**
    var price = byType.get("PRICE");
    assertThat(price.previousValue()).isEqualByComparingTo("10.00");
    assertThat(price.currentValue()).isEqualByComparingTo("12.50");
    assertThat(price.direction()).isEqualTo("INCREASED");
    assertThat(price.priceChangeAmount()).isEqualByComparingTo("2.50");
    assertThat(price.priceChangeRatio()).isEqualByComparingTo("0.25");

    // 排名：名次数字变大 = 竞品表现变差
    var rank = byType.get("RANK");
    assertThat(rank.previousValue()).isEqualByComparingTo("20");
    assertThat(rank.currentValue()).isEqualByComparingTo("35");
    assertThat(rank.direction()).isEqualTo("INCREASED");
    assertThat(rank.rankChange()).isEqualTo(15);

    // 评论数
    var review = byType.get("REVIEW_COUNT");
    assertThat(review.previousValue()).isEqualByComparingTo("100");
    assertThat(review.currentValue()).isEqualByComparingTo("104");
    assertThat(review.direction()).isEqualTo("INCREASED");
    assertThat(review.reviewCountChange()).isEqualByComparingTo("4");

    // 每个响应都带「参考数据」标识
    assertThat(events.dataSource()).isEqualTo("PLATFORM_SCRAPED");
    assertThat(events.sourceNote()).contains("仅供参考");
  }

  @Test
  @DisplayName("4.5.2.2 🔴 首次观测：上下架留一条事件（上新），价格 / 排名 / 评论数不产生事件")
  void firstObservationOnlyProducesStatusEvent() {
    var watch = createWatch(actorA, "B0-FIRST");
    var result =
        ingest(actorA, watch.watchId(), "k-first-1", at(1, 10),
            obs(at(1, 10), "LISTED", "10.00", "20", "100"));
    assertThat(result.appliedCount()).isEqualTo(1);
    assertThat(result.eventCount()).isEqualTo(1);

    var events =
        as(
            actorA,
            "channel.operation.read",
            () -> service.events(watch.watchId().toString(), null, null, null, null, 0, 50));
    assertThat(events.items()).hasSize(1);
    var only = events.items().get(0);
    assertThat(only.changeType()).isEqualTo("LISTING_STATUS");
    assertThat(only.previousState()).isEqualTo("UNKNOWN");
    assertThat(only.currentState()).isEqualTo("LISTED");
    assertThat(only.previousSnapshotId()).as("首次观测没有比较基准").isNull();
    assertThat(only.reason()).contains("首次观测");
  }

  // ==================================================================
  // 4. 🔴 平台没报 ≠ 变成 0
  // ==================================================================

  @Test
  @DisplayName("4.5.2.2 🔴 平台没报价格不产生价格事件，且比较基准回退到上一个已知值")
  void missingObservationFallsBackToLastKnownValue() {
    var watch = createWatch(actorA, "B0-GAP");
    ingest(actorA, watch.watchId(), "k-gap-1", at(1, 10), obs(at(1, 10), "LISTED", "10.00", "20", "100"));
    // 第二次：平台**没报价格**（null），其它维度照报
    ingest(actorA, watch.watchId(), "k-gap-2", at(2, 10), obs(at(2, 10), "LISTED", null, "22", "101"));
    // 第三次：平台又报了价格 —— 与「上一个已知值」10.00 比，而不是与 null 比
    ingest(actorA, watch.watchId(), "k-gap-3", at(3, 10), obs(at(3, 10), "LISTED", "12.00", "24", "102"));

    var events =
        as(
            actorA,
            "channel.operation.read",
            () -> service.events(watch.watchId().toString(), "PRICE", null, null, null, 0, 50));
    assertThat(events.items()).as("只有一次真正的价格变化").hasSize(1);
    var price = events.items().get(0);
    assertThat(price.previousValue()).isEqualByComparingTo("10.00");
    assertThat(price.currentValue()).isEqualByComparingTo("12.00");
    assertThat(price.priceChangeRatio()).isEqualByComparingTo("0.2");
  }

  @Test
  @DisplayName("4.5.2.2 🔴 上次价格为 0 时价格变化比例是 null（不是 0、也不是 100）")
  void zeroDenominatorRatioIsNull() {
    var watch = createWatch(actorA, "B0-ZERO");
    ingest(actorA, watch.watchId(), "k-zero-1", at(1, 10), obs(at(1, 10), "LISTED", "0.00", "20", "100"));
    ingest(actorA, watch.watchId(), "k-zero-2", at(2, 10), obs(at(2, 10), "LISTED", "8.00", "21", "101"));

    var events =
        as(
            actorA,
            "channel.operation.read",
            () -> service.events(watch.watchId().toString(), "PRICE", null, null, null, 0, 50));
    assertThat(events.items()).hasSize(1);
    var price = events.items().get(0);
    assertThat(price.priceChangeAmount()).isEqualByComparingTo("8.00");
    assertThat(price.priceChangeRatio())
        .as("分母为 0 时是 null —— 报 0 会被读成「价格没变」，那是结论不是缺口")
        .isNull();
  }

  // ==================================================================
  // 5. 🔴 幂等与跳过
  // ==================================================================

  @Test
  @DisplayName("4.5.2.2 🔴 同一批次重投 ⇒ 整批不落库（replayed=true），事件不会算两遍")
  void replayedBatchIsNotAppliedTwice() {
    var watch = createWatch(actorA, "B0-REPLAY");
    var first =
        ingest(actorA, watch.watchId(), "k-replay-1", at(1, 10), obs(at(1, 10), "LISTED", "10.00", "20", "100"));
    assertThat(first.replayed()).isFalse();
    assertThat(first.appliedCount()).isEqualTo(1);

    var second =
        ingest(actorA, watch.watchId(), "k-replay-1", at(1, 10), obs(at(1, 10), "LISTED", "10.00", "20", "100"));
    assertThat(second.replayed()).as("同一个幂等键再次投递 ⇒ 整批不落库").isTrue();
    // 🔴 appliedCount 报的是**原批次**落库的条数（本次未落库）—— 看 replayed 字段
    assertThat(second.appliedCount()).isEqualTo(1);
    assertThat(second.skipped()).as("必须写明为什么没落").isNotEmpty();

    var events =
        as(
            actorA,
            "channel.operation.read",
            () -> service.events(watch.watchId().toString(), null, null, null, null, 0, 50));
    assertThat(events.items()).as("事件不会算两遍").hasSize(1);
  }

  @Test
  @DisplayName("4.5.2.2 🔴 同刻重复 ⇒ 覆盖并递增 revision，事件跟着更新")
  void sameTimestampIsOverwrittenWithRevisionBump() {
    var watch = createWatch(actorA, "B0-OVERWRITE");
    ingest(actorA, watch.watchId(), "k-ow-1", at(1, 10), obs(at(1, 10), "LISTED", "10.00", "20", "100"));
    // 抓取方修正同一次抓取的结果：新的批次 key、同一个观测时刻
    ingest(actorA, watch.watchId(), "k-ow-2", at(1, 10), obs(at(1, 10), "LISTED", "11.00", "20", "100"));

    var history =
        as(
            actorA,
            "channel.operation.read",
            () -> service.history(watch.watchId().toString(), null, null, 0, 50));
    assertThat(history.items()).as("同一次抓取只有一行").hasSize(1);
    assertThat(history.items().get(0).revision()).isEqualTo(2);
    assertThat(history.items().get(0).price()).isEqualByComparingTo("11.00");
  }

  @Test
  @DisplayName("4.5.2.2 🔴 批内观测时刻重复 ⇒ 跳过并逐条写明原因（不静默丢弃）")
  void duplicateObservedAtInBatchIsSkippedWithReason() {
    var watch = createWatch(actorA, "B0-DUPBATCH");
    var result =
        ingest(
            actorA,
            watch.watchId(),
            "k-dupb-1",
            at(1, 10),
            obs(at(1, 10), "LISTED", "10.00", "20", "100"),
            obs(at(1, 10), "LISTED", "99.00", "20", "100"));
    assertThat(result.receivedCount()).isEqualTo(2);
    assertThat(result.appliedCount()).isEqualTo(1);
    assertThat(result.skippedCount()).isEqualTo(1);
    assertThat(result.skipped()).as("必须逐条写明原因").hasSize(1);
    assertThat(result.skipped().get(0)).contains("重复");
  }

  @Test
  @DisplayName("4.5.2.2 🔴 数值越界的观测被跳过并写明原因，不静默丢弃")
  void invalidValueIsSkippedWithReason() {
    var watch = createWatch(actorA, "B0-BAD");
    var result =
        as(
            actorA,
            "channel.operation.create",
            () ->
                service.ingest(
                    new IngestListingChangeCrawlRequest(
                        watch.watchId(), "PLATFORM", "API", at(1, 10), 30, "k-bad-1", "", Map.of(),
                        List.of(
                            new ListingChangeObservationInput(
                                at(1, 10), "LISTED", new BigDecimal("-1.00"), "USD", 5, "BSR", null,
                                new BigDecimal("10"), Map.of())))));
    assertThat(result.appliedCount()).isZero();
    assertThat(result.skipped()).hasSize(1);
    assertThat(result.skipped().get(0)).contains("价格不能是负数");
  }

  // ==================================================================
  // 6. 🔴 市场机会
  // ==================================================================

  @Test
  @DisplayName("4.5.2.2 🔴 竞品断货 ⇒ 市场机会（断货开关默认开）")
  void outOfStockRaisesOpportunity() {
    var watch = createWatch(actorA, "B0-OOS");
    ingest(actorA, watch.watchId(), "k-oos-1", at(1, 10), obs(at(1, 10), "LISTED", "10.00", "20", "100"));
    ingest(actorA, watch.watchId(), "k-oos-2", at(2, 10), obs(at(2, 10), "OUT_OF_STOCK", "10.00", "20", "100"));

    var opportunities =
        as(
            actorA,
            "channel.operation.read",
            () -> service.opportunities(watch.watchId().toString(), null, null, null, null, null, 0, 50));
    assertThat(opportunities.items()).hasSize(1);
    var opp = opportunities.items().get(0);
    assertThat(opp.opportunityType()).isEqualTo("COMPETITOR_OUT_OF_STOCK");
    assertThat(opp.status()).isEqualTo("OPEN");
    assertThat(opp.reason()).contains("断货");
    assertThat(opportunities.openCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("4.5.2.2 🔴 竞品涨价超过配置阈值 ⇒ 市场机会；阈值不给 ⇒ 不判定")
  void priceIncreaseOpportunityHonoursThreshold() {
    var withThreshold =
        createWatch(actorA, "B0-PRICE-UP", "COMPETITOR", new BigDecimal("0.10"), null);
    ingest(actorA, withThreshold.watchId(), "k-pu-1", at(1, 10), obs(at(1, 10), "LISTED", "10.00", "20", "100"));
    ingest(actorA, withThreshold.watchId(), "k-pu-2", at(2, 10), obs(at(2, 10), "LISTED", "12.00", "20", "100"));

    var opportunities =
        as(
            actorA,
            "channel.operation.read",
            () ->
                service.opportunities(
                    withThreshold.watchId().toString(), null, null, null, null, null, 0, 50));
    assertThat(opportunities.items()).as("涨 20% > 阈值 10% ⇒ 机会").hasSize(1);
    assertThat(opportunities.items().get(0).opportunityType()).isEqualTo("COMPETITOR_PRICE_INCREASE");
    assertThat(opportunities.items().get(0).reason()).contains("20.00%");

    // 🔴 阈值不给 ⇒ 该维度**不判定**（不猜一个默认门槛）
    var withoutThreshold = createWatch(actorA, "B0-PRICE-NONE", "COMPETITOR", null, null);
    ingest(actorA, withoutThreshold.watchId(), "k-pn-1", at(1, 10), obs(at(1, 10), "LISTED", "10.00", "20", "100"));
    ingest(actorA, withoutThreshold.watchId(), "k-pn-2", at(2, 10), obs(at(2, 10), "LISTED", "12.00", "20", "100"));
    var none =
        as(
            actorA,
            "channel.operation.read",
            () ->
                service.opportunities(
                    withoutThreshold.watchId().toString(), null, null, null, null, null, 0, 50));
    assertThat(none.items()).as("没配阈值 ⇒ 不判定这一条").isEmpty();
  }

  @Test
  @DisplayName("4.5.2.2 🔴 上次价格为 0 时不判定涨价机会（分母为 0 ⇒ 比例是 null）")
  void zeroDenominatorDoesNotRaisePriceOpportunity() {
    var watch = createWatch(actorA, "B0-ZERO-OPP", "COMPETITOR", new BigDecimal("0.10"), null);
    ingest(actorA, watch.watchId(), "k-zo-1", at(1, 10), obs(at(1, 10), "LISTED", "0.00", "20", "100"));
    ingest(actorA, watch.watchId(), "k-zo-2", at(2, 10), obs(at(2, 10), "LISTED", "8.00", "20", "100"));

    var opportunities =
        as(
            actorA,
            "channel.operation.read",
            () -> service.opportunities(watch.watchId().toString(), null, null, null, null, null, 0, 50));
    assertThat(opportunities.items()).as("分母为 0 ⇒ 比例是 null ⇒ 不判定").isEmpty();
  }

  @Test
  @DisplayName("4.5.2.2 🔴 竞品排名变差超过配置位数 ⇒ 市场机会")
  void rankWorsenedOpportunity() {
    var watch = createWatch(actorA, "B0-RANK", "COMPETITOR", null, 5);
    ingest(actorA, watch.watchId(), "k-rk-1", at(1, 10), obs(at(1, 10), "LISTED", "10.00", "10", "100"));
    ingest(actorA, watch.watchId(), "k-rk-2", at(2, 10), obs(at(2, 10), "LISTED", "10.00", "20", "100"));
    // 名次 10 → 20，变差 10 位 ≥ 阈值 5 位
    var opportunities =
        as(
            actorA,
            "channel.operation.read",
            () -> service.opportunities(watch.watchId().toString(), null, null, null, null, null, 0, 50));
    assertThat(opportunities.items()).hasSize(1);
    assertThat(opportunities.items().get(0).opportunityType()).isEqualTo("COMPETITOR_RANK_WORSENED");
  }

  @Test
  @DisplayName("4.5.2.2 🔴 机会可标记已阅；已阅是内部状态，不等于已采取动作")
  void opportunityCanBeAcknowledged() {
    var watch = createWatch(actorA, "B0-ACK");
    ingest(actorA, watch.watchId(), "k-ack-1", at(1, 10), obs(at(1, 10), "LISTED", "10.00", "20", "100"));
    ingest(actorA, watch.watchId(), "k-ack-2", at(2, 10), obs(at(2, 10), "OUT_OF_STOCK", "10.00", "20", "100"));

    var opportunities =
        as(
            actorA,
            "channel.operation.read",
            () -> service.opportunities(watch.watchId().toString(), null, null, null, null, null, 0, 50));
    var opp = opportunities.items().get(0);
    var acked =
        as(
            actorA,
            "channel.operation.update",
            () -> service.acknowledgeOpportunity(opp.opportunityId().toString(), null));
    assertThat(acked.status()).isEqualTo("ACKNOWLEDGED");
    assertThat(acked.acknowledgedAt()).isNotNull();

    var after =
        as(
            actorA,
            "channel.operation.read",
            () -> service.opportunities(watch.watchId().toString(), null, null, null, null, null, 0, 50));
    assertThat(after.openCount()).isZero();
  }

  // ==================================================================
  // 7. 🔴 历史对比
  // ==================================================================

  @Test
  @DisplayName("4.5.2.2 🔴 历史对比是服务端 SQL 聚合：四维齐全、比率现算、不落表")
  void historyComparisonIsServerSideAggregation() {
    var watch = createWatch(actorA, "B0-CMP");
    ingest(actorA, watch.watchId(), "k-cmp-1", at(1, 10), obs(at(1, 10), "LISTED", "10.00", "20", "100"));
    ingest(actorA, watch.watchId(), "k-cmp-2", at(2, 10), obs(at(2, 10), "LISTED", "12.00", "30", "110"));
    ingest(actorA, watch.watchId(), "k-cmp-3", at(3, 10), obs(at(3, 10), "OUT_OF_STOCK", "15.00", "40", "120"));

    var comparison =
        as(
            actorA,
            "channel.operation.read",
            () -> service.comparison(watch.watchId().toString(), "2026-10-01", "2026-10-31"));

    assertThat(comparison.hasData()).isTrue();
    assertThat(comparison.sampleCount()).isEqualTo(3);
    // 事件数 = 首次观测 1 条（上下架）+ 第 2 次 3 条（价格/排名/评论数）+ 第 3 次 4 条（+上下架）
    assertThat(comparison.eventCount()).isEqualTo(8);

    // 价格
    assertThat(comparison.price().firstPrice()).isEqualByComparingTo("10.00");
    assertThat(comparison.price().lastPrice()).isEqualByComparingTo("15.00");
    assertThat(comparison.price().minPrice()).isEqualByComparingTo("10.00");
    assertThat(comparison.price().maxPrice()).isEqualByComparingTo("15.00");
    assertThat(comparison.price().changeAmount()).isEqualByComparingTo("5.00");
    assertThat(comparison.price().changeRatio()).isEqualByComparingTo("0.5");
    assertThat(comparison.price().currency()).isEqualTo("USD");

    // 排名（名次数字越小越好）
    assertThat(comparison.rank().firstRank()).isEqualTo(20);
    assertThat(comparison.rank().lastRank()).isEqualTo(40);
    assertThat(comparison.rank().bestRank()).isEqualTo(20);
    assertThat(comparison.rank().worstRank()).isEqualTo(40);
    assertThat(comparison.rank().changeAmount()).isEqualTo(20);

    // 评论数
    assertThat(comparison.reviewCount().firstCount()).isEqualByComparingTo("100");
    assertThat(comparison.reviewCount().lastCount()).isEqualByComparingTo("120");
    assertThat(comparison.reviewCount().changeAmount()).isEqualByComparingTo("20");

    // 上下架
    assertThat(comparison.listingStatus().currentStatus()).isEqualTo("OUT_OF_STOCK");
    assertThat(comparison.listingStatus().listedCount()).isEqualTo(2);
    assertThat(comparison.listingStatus().outOfStockCount()).isEqualTo(1);
    assertThat(comparison.listingStatus().transitionCount()).isEqualTo(2);

    // 🔴 对比视图**不落表**
    Long comparisonTables =
        (Long)
            scalar(
                "select count(*) from information_schema.tables where table_name like 'listing_change%compar%'");
    assertThat(comparisonTables).as("对比视图不落表").isZero();
  }

  @Test
  @DisplayName("4.5.2.2 🔴 没有观测数据时历史对比给出空态原因，不是假数据")
  void comparisonEmptyState() {
    var watch = createWatch(actorA, "B0-EMPTY");
    var comparison =
        as(
            actorA,
            "channel.operation.read",
            () -> service.comparison(watch.watchId().toString(), "2026-10-01", "2026-10-31"));
    assertThat(comparison.hasData()).isFalse();
    assertThat(comparison.emptyReason()).isNotEmpty();
    assertThat(comparison.sampleCount()).isZero();
    assertThat(comparison.price().firstPrice()).isNull();
    assertThat(comparison.price().changeRatio()).isNull();
  }

  // ==================================================================
  // 8. 能力声明与参考数据标注
  // ==================================================================

  @Test
  @DisplayName("4.5.2.2 能力声明：02 抓取调度器 / 13 分析 / 1 工作台 三段未交付，显式声明")
  void capabilitiesDeclareUndeliveredSegments() {
    var caps = as(actorA, "channel.operation.read", () -> service.capabilities());
    var byDimension = new java.util.HashMap<String, ListingChangeCapabilityDimension>();
    for (ListingChangeCapabilityDimension d : caps.dimensions()) {
      byDimension.put(d.dimension(), d);
    }
    assertThat(byDimension.get("real_crawl_scheduler").available())
        .as("真实抓取调度器归 02，本模块只提供入库端点")
        .isFalse();
    assertThat(byDimension.get("real_crawl_scheduler").ownerModule()).contains("02");
    assertThat(byDimension.get("analytics_consumption").available())
        .as("13 分析整卷未交付")
        .isFalse();
    assertThat(byDimension.get("analytics_consumption").ownerModule()).contains("13");
    assertThat(byDimension.get("workbench_opportunity_delivery").available())
        .as("1 工作台整卷未交付")
        .isFalse();
    assertThat(byDimension.get("workbench_opportunity_delivery").ownerModule()).contains("01");

    // 🔴 参考数据标注
    assertThat(caps.dataSource()).isEqualTo("PLATFORM_SCRAPED");
    assertThat(caps.sourceNote()).contains("仅供参考");
    assertThat(caps.actionsNote()).contains("不提供抢占");

    // 🔴 本模块**没有**「抢占 / 调价」能力维度
    assertThat(byDimension.keySet()).doesNotContain("price_undercut_action");
  }

  // ==================================================================
  // 9. 🔴 DB 层闸门
  // ==================================================================

  @Test
  @DisplayName("4.5.2.2 🔴 DB 层闸门：事件引用的快照必须属于同一监控对象")
  void eventMustReferenceSnapshotOfSameWatch() {
    var watchA = createWatch(actorA, "B0-GATE-A");
    var watchB = createWatch(actorA, "B0-GATE-B");
    ingest(actorA, watchA.watchId(), "k-gate-1", at(1, 10), obs(at(1, 10), "LISTED", "10.00", "20", "100"));
    ingest(actorA, watchB.watchId(), "k-gate-2", at(1, 11), obs(at(1, 11), "LISTED", "11.00", "21", "101"));

    UUID snapshotA = snapshotIdOf(watchA.watchId());
    UUID watchBId = watchB.watchId();
    String state =
        sqlState(
            "insert into listing_change_event (id,tenant_id,watch_id,snapshot_id,crawl_id,change_type,"
                + "direction,detected_at,reason) values (gen_random_uuid(),'"
                + a
                + "','"
                + watchBId
                + "','"
                + snapshotA
                + "',(select id from listing_change_crawl where tenant_id='"
                + a
                + "' limit 1),'PRICE','INCREASED',now(),'人为制造的不一致')");
    assertThat(state).as("事件挂在 A 的快照上却属于 B 的监控对象 ⇒ DB 直接拒").isEqualTo("23514");
  }

  @Test
  @DisplayName("4.5.2.2 🔴 DB 层闸门：已阅的市场机会判定结论不可再改")
  void acknowledgedOpportunityConclusionIsImmutable() {
    var watch = createWatch(actorA, "B0-FINAL");
    ingest(actorA, watch.watchId(), "k-fin-1", at(1, 10), obs(at(1, 10), "LISTED", "10.00", "20", "100"));
    ingest(actorA, watch.watchId(), "k-fin-2", at(2, 10), obs(at(2, 10), "OUT_OF_STOCK", "10.00", "20", "100"));
    var opportunities =
        as(
            actorA,
            "channel.operation.read",
            () -> service.opportunities(watch.watchId().toString(), null, null, null, null, null, 0, 50));
    var opp = opportunities.items().get(0);
    as(
        actorA,
        "channel.operation.update",
        () -> service.acknowledgeOpportunity(opp.opportunityId().toString(), null));

    String state =
        sqlState(
            "update listing_change_opportunity set severity='LOW' where tenant_id='"
                + a
                + "' and id='"
                + opp.opportunityId()
                + "'");
    assertThat(state).as("已阅的判定结论不可再改（改它等于改历史）").isEqualTo("23514");
  }

  @Test
  @DisplayName("4.5.2.2 🔴 五张表都 FORCE RLS")
  void allTablesForceRowLevelSecurity() {
    for (String table :
        List.of(
            "listing_change_watch",
            "listing_change_crawl",
            "listing_change_snapshot",
            "listing_change_event",
            "listing_change_opportunity")) {
      assertThat(scalar("select relforcerowsecurity::text from pg_class where relname='" + table + "'"))
          .as(table + " 必须 FORCE RLS")
          .isEqualTo("true");
    }
  }

  // ==================================================================
  // 10. 🔴 timestamptz 往返
  // ==================================================================

  @Test
  @DisplayName("4.5.2.2 🔴 timestamptz 往返逐字相等（+08:00 偏移不会被静默偏 −28800s）")
  void timestamptzRoundTripIsExact() {
    var watch = createWatch(actorA, "B0-TZ");
    OffsetDateTime observed = OffsetDateTime.of(2026, 10, 8, 15, 45, 30, 0, ZoneOffset.ofHours(8));
    ingest(actorA, watch.watchId(), "k-tz-1", observed, obs(observed, "LISTED", "10.00", "20", "100"));

    var history =
        as(
            actorA,
            "channel.operation.read",
            () -> service.history(watch.watchId().toString(), null, null, 0, 50));
    assertThat(history.items()).hasSize(1);
    OffsetDateTime stored = history.items().get(0).observedAt();
    assertThat(stored.toInstant()).isEqualTo(observed.toInstant());
    assertThat(stored.getOffset()).isEqualTo(ZoneOffset.ofHours(8));

    // 直接查库，字面相等
    Object raw =
        scalar(
            "select observed_at from listing_change_snapshot where tenant_id='"
                + a
                + "' and watch_id='"
                + watch.watchId()
                + "'");
    assertThat(raw).isInstanceOf(java.sql.Timestamp.class);
    assertThat(((java.sql.Timestamp) raw).toInstant()).isEqualTo(observed.toInstant());
  }

  // ==================================================================
  // 11. 真分页
  // ==================================================================

  @Test
  @DisplayName("4.5.2.2 真分页：total 是服务端 count 出来的，翻页能翻到不同的数据")
  void paginationIsReal() {
    var watch = createWatch(actorA, "B0-PAGE");
    for (int day = 1; day <= 5; day++) {
      // 价格每天变一次 ⇒ 每天一条价格事件（+ 首次观测那条上下架事件）
      ingest(actorA, watch.watchId(), "k-page-" + day, at(day, 10),
          obs(at(day, 10), "LISTED", String.valueOf(10 + day) + ".00", "20", "100"));
    }
    var page0 =
        as(
            actorA,
            "channel.operation.read",
            () -> service.events(watch.watchId().toString(), null, null, null, null, 0, 2));
    var page1 =
        as(
            actorA,
            "channel.operation.read",
            () -> service.events(watch.watchId().toString(), null, null, null, null, 1, 2));
    assertThat(page0.total()).isEqualTo(5);
    assertThat(page0.items()).hasSize(2);
    assertThat(page1.items()).hasSize(2);
    assertThat(page0.items().get(0).eventId()).isNotEqualTo(page1.items().get(0).eventId());
  }

  // ==================================================================
  // 12. HTTP 面
  // ==================================================================

  @Test
  @DisplayName("4.5.2.2 HTTP 面：无会话 401 / 不存在 404 / 非法参数 422")
  void httpSurface() throws Exception {
    var watch = createWatch(actorA, "B0-HTTP");

    // 无会话
    http.perform(get("/channel/listing-changes/watches")).andExpect(status().isUnauthorized());

    // 不存在
    http.perform(get("/channel/listing-changes/watches")
            .header("Authorization", bearerA)
            .param("watchId", UUID.randomUUID().toString()))
        .andExpect(status().isOk());
    http.perform(get("/channel/listing-changes/watches/" + UUID.randomUUID() + "/comparison")
            .header("Authorization", bearerA))
        .andExpect(status().isNotFound());

    // 非法筛选参数
    http.perform(get("/channel/listing-changes/watches")
            .header("Authorization", bearerA)
            .param("status", "NOT_A_STATUS"))
        .andExpect(status().isBadRequest());
    http.perform(get("/channel/listing-changes/events")
            .header("Authorization", bearerA)
            .param("changeType", "NOT_A_TYPE"))
        .andExpect(status().isBadRequest());

    // 平台不存在
    http.perform(post("/channel/listing-changes/watches")
            .header("Authorization", bearerA)
            .contentType("application/json")
            .content(
                JSON.writeValueAsString(
                    new SaveListingChangeWatchRequest(
                        "NOT_A_PLATFORM", null, "B0-X", "COMPETITOR", "", "",
                        360, "ACTIVE", null, null, null, null, ""))))
        .andExpect(status().isUnprocessableEntity());
  }

  @Test
  @DisplayName("4.5.2.2 🔴 暂停的监控对象不接收抓取结果（暂停只停接收与判定，历史不删）")
  void pausedWatchRejectsIngest() {
    var watch = createWatch(actorA, "B0-PAUSED");
    ingest(actorA, watch.watchId(), "k-pa-1", at(1, 10), obs(at(1, 10), "LISTED", "10.00", "20", "100"));
    as(
        actorA,
        "channel.operation.update",
        () ->
            service.updateWatch(
                watch.watchId().toString(),
                new SaveListingChangeWatchRequest(
                    AMAZON, null, "B0-PAUSED", "COMPETITOR", "", "", 360, "PAUSED",
                    null, null, null, null, "")));

    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    ingest(
                        actorA, watch.watchId(), "k-pa-2", at(2, 10),
                        obs(at(2, 10), "LISTED", "11.00", "21", "101"))))
        .as("暂停期间不接收也不判定")
        .isNotNull();

    // 历史事实不删
    var history =
        as(
            actorA,
            "channel.operation.read",
            () -> service.history(watch.watchId().toString(), null, null, 0, 50));
    assertThat(history.items()).as("暂停不删历史").hasSize(1);
  }

  // ==================================================================
  // 辅助
  // ==================================================================

  UUID snapshotIdOf(UUID watchId) {
    Object id =
        scalar(
            "select id from listing_change_snapshot where tenant_id='"
                + a
                + "' and watch_id='"
                + watchId
                + "' order by observed_at desc limit 1");
    return (UUID) id;
  }
}
