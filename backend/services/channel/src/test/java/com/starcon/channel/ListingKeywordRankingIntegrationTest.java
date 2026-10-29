package com.starcon.channel;

import static com.starcon.channel.ListingKeywordRankingModels.*;
import static com.starcon.channel.ListingModels.*;
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
 * 4.5.1.2 关键词排名。
 *
 * <p>蓝图：「监控商品在核心关键词下的自然排名与广告排名，记录历史变化，低于阈值时预警。输入：关键词集合（按商品维护）；排名抓取任务；阈值设置。输出：排名历史表 + 变化预警 + 关键词维度报表。关系：上游 = 抓取任务（02 通道）；下游 = 1 工作台预警、14 广告。<b>排名数据依赖抓取频率与平台限制，必须明确标注抓取时间与可能的不准确窗口</b>。」
 *
 * <p>本测试类钉住这几组事：
 *
 * <ol>
 *   <li><b>🔴🔴 数据真的落 PostgreSQL</b>：关键词集合、排名历史、变化预警都落表，
 *       名次<b>逐字相等</b>（不是只断言没抛异常）。
 *   <li><b>🔴🔴 抓取时间与不准确窗口写回值逐字相等</b>（+08:00 非 UTC 偏移，
 *       epoch 也相等）—— 偏 8 小时会让整条趋势错位。
 *   <li><b>🔴🔴 排名没有就是 null</b>，不是 0 —— 写 0 会被读成「排第一」或「完全没排名」。
 *   <li><b>🔴 变化量现算</b>：本次名次 − 上次名次；没有上次时是 null（不是 0）。
 *   <li><b>🔴 幂等与覆盖是两件事</b>：同一个幂等键再投 ⇒ 整批不落库（replayed=true）；
 *       新的键 + 同一抓取时刻 ⇒ 那一行被覆盖、覆盖次数 +1。
 *   <li><b>🔴 预警是记录</b>：低于阈值 / 退步超阈值各产生一条；<b>没配阈值就不产生</b>；
 *       预警**不发通知**，但确认会留痕。
 *   <li><b>🔴 关键词停用是软删除</b>：排名历史与预警记录必须留着。
 *   <li><b>🔴 报表是真 SQL 聚合</b>：按（平台, 站点, 关键词）聚合；<b>没有样本时平均是 null</b>。
 *   <li><b>🔴 趋势没有数据时是空数组</b>，不是一条平线。
 *   <li><b>🔴 DB 层闸门</b>：快照引用的抓取批次必须同商品（触发器）；抓取批次落终态后不可再改
 *       （触发器）；跨租户挂 listing 在物理上不可能（复合 FK）。
 *   <li><b>真分页</b>：{@code total} 是服务端 count 出来的，翻页能翻到不同的数据。
 * </ol>
 *
 * <p><b>时刻一律钉死</b>，不在断言中途改时刻。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ListingKeywordRankingIntegrationTest {

  static final PostgreSQLContainer<?> pg =
      new PostgreSQLContainer<>("postgres:16-alpine").withUrlParam("sslmode", "disable");
  static final UUID user = UUID.randomUUID();
  static HttpServer identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

  UUID a, b;
  AccessService.Access actorA, actorB;
  String bearerA;

  // 🔴 直接用 V1 已播种的平台码表，不自己造平台（platform.code UNIQUE，自造会撞码）
  static final UUID AMAZON = UUID.fromString("11111111-1111-1111-1111-111111111101");

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

  @Autowired ListingService listings;
  @Autowired ListingKeywordRankingService service;
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

  /** 以管理员连接跑一条会失败的 SQL，返回 SQLState（用于断言 DB 级闸门）。 */
  static String sqlState(String sql) {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(sql);
      return null;
    } catch (SQLException e) {
      return e.getSQLState();
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

  /** 🔴 站点码只在平台内唯一 —— 取 AMAZON 的 US 站（md5 派生，测试不去硬编码）。 */
  UUID amazonUsMarketplace() {
    Object id =
        scalar("select id from marketplace where platform_id='" + AMAZON + "' and code='US'");
    return (UUID) id;
  }

  ListingRow seedListing(
      AccessService.Access who, UUID shopId, UUID marketplaceId, String externalItemId, String title) {
    SaveListingRequest request =
        new SaveListingRequest(
            null, shopId, marketplaceId, externalItemId, title, "Home", "cat-1",
            "ON_SALE", null, "", "IN_STOCK", null, null, null, null, null, null, List.of(), null);
    return as(who, "channel.operation.create", () -> listings.save(request));
  }

  ListingKeywordRankingKeywordRow newKeyword(
      AccessService.Access who, UUID listingId, String word, Integer natural, Integer ad, Integer drop) {
    return as(
        who,
        "channel.operation.create",
        () -> service.saveKeyword(null, new ListingKeywordRankingKeywordInput(
            listingId, word, natural, ad, drop, "")));
  }

  ListingKeywordRankingCrawlResult crawl(
      AccessService.Access who, ListingKeywordRankingCrawlRequest request) {
    return as(who, "channel.operation.create", () -> service.crawl(request));
  }

  static ListingKeywordRankingRankInput rank(UUID keywordId, Integer natural, Integer ad) {
    return new ListingKeywordRankingRankInput(keywordId, natural, ad, Map.of());
  }

  static ListingKeywordRankingCrawlRequest crawlReq(
      UUID listingId, String key, OffsetDateTime crawledAt, int window,
      List<ListingKeywordRankingRankInput> ranks) {
    return new ListingKeywordRankingCrawlRequest(
        listingId, "PLATFORM", "API", crawledAt, window, key, "", Map.of("vendor", "acme"), ranks);
  }

  static OffsetDateTime at(int day, int hour) {
    return OffsetDateTime.of(2026, 10, day, hour, 0, 0, 0, ZoneOffset.UTC);
  }

  // ==================================================================
  // 1. 🔴🔴 关键词集合：落 PostgreSQL + 平台站点由商品推导
  // ==================================================================

  @Test
  @DisplayName("4.5.1.2 🔴🔴 关键词集合真的落 PostgreSQL，平台与站点由商品推导")
  void keywordSetLandsInPostgres() {
    UUID shop = seedShop(a, "LKR-KW-1");
    var listing = seedListing(actorA, shop, amazonUsMarketplace(), "LKR-A1", "关键词商品");

    var row = newKeyword(actorA, listing.id(), "wireless headphones", 10, 5, 3);

    assertThat(row.keyword()).isEqualTo("wireless headphones");
    assertThat(row.listingId()).isEqualTo(listing.id());
    // 🔴 平台与站点**由商品推导**，不是调用方给的
    assertThat(row.platformCode()).isEqualTo("AMAZON");
    assertThat(row.siteCode()).isEqualTo("US");
    assertThat(row.naturalRankThreshold()).isEqualTo(10);
    assertThat(row.adRankThreshold()).isEqualTo(5);
    assertThat(row.rankDropThreshold()).isEqualTo(3);
    assertThat(row.active()).isTrue();

    Object stored =
        scalar(
            "select keyword||'|'||platform_code||'|'||site_code||'|'||natural_rank_threshold"
                + " from listing_keyword where id='" + row.keywordId() + "'");
    assertThat(String.valueOf(stored)).isEqualTo("wireless headphones|AMAZON|US|10");
  }

  @Test
  @DisplayName("4.5.1.2 平台与站点由商品推导：商品没有站点时站点码是空串（不是猜一个）")
  void siteCodeIsEmptyWhenListingHasNoMarketplace() {
    UUID shop = seedShop(a, "LKR-KW-NOSITE");
    var listing = seedListing(actorA, shop, null, "LKR-A2", "无站点商品");

    var row = newKeyword(actorA, listing.id(), "no site keyword", null, null, null);

    assertThat(row.platformCode()).isEqualTo("AMAZON");
    assertThat(row.siteCode()).isEmpty();
    // 🔴 没配阈值 = 该维度不预警
    assertThat(row.naturalRankThreshold()).isNull();
    assertThat(row.adRankThreshold()).isNull();
    assertThat(row.rankDropThreshold()).isNull();
  }

  @Test
  @DisplayName("4.5.1.2 同一商品下同一关键词只能有一条（重复新建 409，不新建第二条）")
  void duplicateKeywordConflicts() {
    UUID shop = seedShop(a, "LKR-DUP");
    var listing = seedListing(actorA, shop, amazonUsMarketplace(), "LKR-A3", "重复关键词商品");
    newKeyword(actorA, listing.id(), "dup keyword", 10, null, null);

    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () -> newKeyword(actorA, listing.id(), "dup keyword", 20, null, null)))
        .as("重复关键词必须被拒")
        .isNotNull();

    Object rows =
        scalar("select count(*)::text from listing_keyword where listing_id='" + listing.id() + "'");
    assertThat(String.valueOf(rows)).isEqualTo("1");
  }

  @Test
  @DisplayName("4.5.1.2 🔴 改阈值走更新；停用是软删除（排名历史与预警记录留着）")
  void updateAndSoftDelete() {
    UUID shop = seedShop(a, "LKR-UPD");
    var listing = seedListing(actorA, shop, amazonUsMarketplace(), "LKR-A4", "改阈值商品");
    var created = newKeyword(actorA, listing.id(), "update keyword", 10, 5, 3);

    var updated =
        as(
            actorA,
            "channel.operation.update",
            () ->
                service.saveKeyword(
                    created.keywordId(),
                    new ListingKeywordRankingKeywordInput(
                        listing.id(), "update keyword", 25, 15, 8, "改过的备注")));
    assertThat(updated.naturalRankThreshold()).isEqualTo(25);
    assertThat(updated.adRankThreshold()).isEqualTo(15);
    assertThat(updated.rankDropThreshold()).isEqualTo(8);
    assertThat(updated.note()).isEqualTo("改过的备注");

    // 抓两次，产生历史与预警
    crawl(actorA, crawlReq(listing.id(), "k-upd-1", at(1, 3), 30, List.of(rank(created.keywordId(), 40, 2))));
    crawl(actorA, crawlReq(listing.id(), "k-upd-2", at(2, 3), 30, List.of(rank(created.keywordId(), 45, 3))));

    var disabled = as(actorA, "channel.operation.delete", () -> service.disableKeyword(created.keywordId().toString()));
    assertThat(disabled.active()).as("停用是软删除").isFalse();

    // 🔴 历史与预警必须留着
    Object snapshots =
        scalar(
            "select count(*)::text from listing_keyword_rank_snapshot where keyword_id='"
                + created.keywordId() + "'");
    assertThat(String.valueOf(snapshots)).as("停用不能抹掉排名历史").isEqualTo("2");
    Object alerts =
        scalar(
            "select count(*)::text from listing_keyword_rank_alert where keyword_id='"
                + created.keywordId() + "'");
    // 🔴 2 条：阈值已被改成 25/15/8 ⇒ 抓 1 次（40 > 25）1 条 + 抓 2 次（45 > 25）1 条；
    //    退步 5 位 ≤ 退步阈值 8 ⇒ **不**产生退步预警。
    assertThat(String.valueOf(alerts)).as("停用不能抹掉预警记录").isEqualTo("2");
  }

  // ==================================================================
  // 2. 🔴🔴 排名抓取结果入库
  // ==================================================================

  @Test
  @DisplayName("4.5.1.2 🔴🔴 排名抓取结果真的落 PostgreSQL，自然排名与广告排名逐字相等")
  void ranksLandInPostgres() {
    UUID shop = seedShop(a, "LKR-INGEST");
    var listing = seedListing(actorA, shop, amazonUsMarketplace(), "LKR-A5", "排名商品");
    var keyword = newKeyword(actorA, listing.id(), "ingest keyword", 10, 5, 3);

    var result =
        crawl(actorA, crawlReq(listing.id(), "k-ingest-1", at(1, 3), 45, List.of(rank(keyword.keywordId(), 7, 2))));

    assertThat(result.replayed()).as("首次投递不是重放").isFalse();
    assertThat(result.status()).isEqualTo("APPLIED");
    assertThat(result.receivedCount()).isEqualTo(1);
    assertThat(result.appliedCount()).isEqualTo(1);
    assertThat(result.skippedCount()).isZero();
    assertThat(result.accuracyWindowMinutes()).isEqualTo(45);

    Object natural =
        scalar(
            "select natural_rank::text from listing_keyword_rank_snapshot where keyword_id='"
                + keyword.keywordId() + "'");
    assertThat(String.valueOf(natural)).isEqualTo("7");
    Object ad =
        scalar(
            "select ad_rank::text from listing_keyword_rank_snapshot where keyword_id='"
                + keyword.keywordId() + "'");
    assertThat(String.valueOf(ad)).isEqualTo("2");
  }

  @Test
  @DisplayName("4.5.1.2 🔴🔴 抓取时间写回值逐字相等（+08:00 非 UTC 偏移，epoch 也相等）")
  void crawledAtRoundTripsExactly() {
    UUID shop = seedShop(a, "LKR-TZ");
    var listing = seedListing(actorA, shop, amazonUsMarketplace(), "LKR-A6", "时区商品");
    var keyword = newKeyword(actorA, listing.id(), "tz keyword", null, null, null);

    // 🔴 +08:00 偏移：Timestamp.valueOf(LocalDateTime) 那条路会静默偏 −28800s
    OffsetDateTime crawledAt =
        OffsetDateTime.of(2026, 10, 1, 9, 30, 0, 0, ZoneOffset.ofHours(8));
    crawl(actorA, crawlReq(listing.id(), "k-tz-1", crawledAt, 30, List.of(rank(keyword.keywordId(), 9, 3))));

    Object local =
        scalar(
            "select to_char(crawled_at at time zone 'Asia/Shanghai','YYYY-MM-DD HH24:MI:SS')"
                + " from listing_keyword_rank_snapshot where keyword_id='"
                + keyword.keywordId() + "'");
    assertThat(String.valueOf(local)).isEqualTo("2026-10-01 09:30:00");

    Object epoch =
        scalar(
            "select extract(epoch from crawled_at)::text from listing_keyword_rank_snapshot"
                + " where keyword_id='" + keyword.keywordId() + "'");
    assertThat(new BigDecimal(String.valueOf(epoch)).longValue())
        .as("epoch 必须逐字相等（偏 8 小时会让整条趋势错位）")
        .isEqualTo(crawledAt.toInstant().getEpochSecond());
  }

  @Test
  @DisplayName("4.5.1.2 🔴 不准确窗口写回值逐字相等（不是只断言没抛异常）")
  void accuracyWindowIsPersisted() {
    UUID shop = seedShop(a, "LKR-WIN");
    var listing = seedListing(actorA, shop, amazonUsMarketplace(), "LKR-A7", "窗口商品");
    var keyword = newKeyword(actorA, listing.id(), "window keyword", null, null, null);

    crawl(actorA, crawlReq(listing.id(), "k-win-1", at(1, 3), 180, List.of(rank(keyword.keywordId(), 11, 4))));

    Object window =
        scalar(
            "select accuracy_window_minutes::text from listing_keyword_rank_snapshot where keyword_id='"
                + keyword.keywordId() + "'");
    assertThat(String.valueOf(window)).isEqualTo("180");

    // 🔴 明细里也必须带抓取时间与窗口（契约级，不是只落在快照表上）
    var detail = as(actorA, "channel.operation.read", () -> service.detail(keyword.keywordId().toString()));
    assertThat(detail.latestAccuracyWindowMinutes()).isEqualTo(180);
    assertThat(detail.latestCrawledAt()).isNotNull();
    assertThat(detail.accuracyNote()).contains("抓取时间").contains("不准确窗口");
  }

  @Test
  @DisplayName("4.5.1.2 🔴 幂等：同一个键再投 ⇒ 整批不再落库（连预警都不重复产生）")
  void idempotentCrawlIsNotAppliedTwice() {
    UUID shop = seedShop(a, "LKR-IDEM");
    var listing = seedListing(actorA, shop, amazonUsMarketplace(), "LKR-A8", "幂等商品");
    var keyword = newKeyword(actorA, listing.id(), "idem keyword", 10, 5, 3);

    var first = crawl(actorA, crawlReq(listing.id(), "k-idem-1", at(1, 3), 30, List.of(rank(keyword.keywordId(), 40, 2))));
    assertThat(first.replayed()).isFalse();
    assertThat(first.alertCount()).as("40 > 阈值 10 ⇒ 产生一条低于阈值预警").isEqualTo(1);

    var second = crawl(actorA, crawlReq(listing.id(), "k-idem-1", at(1, 3), 30, List.of(rank(keyword.keywordId(), 40, 2))));
    assertThat(second.replayed()).as("同一个键再投是重放").isTrue();

    Object rows =
        scalar(
            "select count(*)::text from listing_keyword_rank_snapshot where keyword_id='"
                + keyword.keywordId() + "'");
    assertThat(String.valueOf(rows)).isEqualTo("1");
    Object alerts =
        scalar(
            "select count(*)::text from listing_keyword_rank_alert where keyword_id='"
                + keyword.keywordId() + "'");
    assertThat(String.valueOf(alerts)).as("重投不能刷出重复预警").isEqualTo("1");
  }

  @Test
  @DisplayName("4.5.1.2 🔴 同刻覆盖：新的键 + 同一抓取时刻 ⇒ 覆盖那一行，覆盖次数 +1")
  void sameMomentIsOverwritten() {
    UUID shop = seedShop(a, "LKR-OVR");
    var listing = seedListing(actorA, shop, amazonUsMarketplace(), "LKR-A9", "覆盖商品");
    var keyword = newKeyword(actorA, listing.id(), "overwrite keyword", null, null, null);

    crawl(actorA, crawlReq(listing.id(), "k-ovr-1", at(1, 3), 30, List.of(rank(keyword.keywordId(), 10, 5))));
    crawl(actorA, crawlReq(listing.id(), "k-ovr-2", at(1, 3), 30, List.of(rank(keyword.keywordId(), 12, 6))));

    Object rows =
        scalar(
            "select count(*)::text from listing_keyword_rank_snapshot where keyword_id='"
                + keyword.keywordId() + "'");
    assertThat(String.valueOf(rows)).as("同一时刻只有一行").isEqualTo("1");
    Object revision =
        scalar(
            "select revision::text from listing_keyword_rank_snapshot where keyword_id='"
                + keyword.keywordId() + "'");
    assertThat(String.valueOf(revision)).isEqualTo("2");
    Object natural =
        scalar(
            "select natural_rank::text from listing_keyword_rank_snapshot where keyword_id='"
                + keyword.keywordId() + "'");
    assertThat(String.valueOf(natural)).isEqualTo("12");
  }

  @Test
  @DisplayName("4.5.1.2 🔴🔴 平台没报名次时写 null（不是 0、不是很大的数）")
  void missingRankIsNullNotZero() {
    UUID shop = seedShop(a, "LKR-NULL");
    var listing = seedListing(actorA, shop, amazonUsMarketplace(), "LKR-A10", "空名次商品");
    var keyword = newKeyword(actorA, listing.id(), "null rank keyword", 10, 5, 3);

    crawl(actorA, crawlReq(listing.id(), "k-null-1", at(1, 3), 30, List.of(rank(keyword.keywordId(), null, 4))));

    Object natural =
        scalar(
            "select coalesce(natural_rank::text,'<NULL>') from listing_keyword_rank_snapshot"
                + " where keyword_id='" + keyword.keywordId() + "'");
    assertThat(String.valueOf(natural))
        .as("平台没报自然排名 = null，不是 0")
        .isEqualTo("<NULL>");

    var detail = as(actorA, "channel.operation.read", () -> service.detail(keyword.keywordId().toString()));
    assertThat(detail.latestNaturalRank()).as("null 不是 0").isNull();
    assertThat(detail.latestAdRank()).isEqualTo(4);
  }

  @Test
  @DisplayName("4.5.1.2 🔴 变化量现算：本次 − 上次；没有上次时是 null（不是 0）")
  void rankChangeIsComputed() {
    UUID shop = seedShop(a, "LKR-CHG");
    var listing = seedListing(actorA, shop, amazonUsMarketplace(), "LKR-A11", "变化商品");
    var keyword = newKeyword(actorA, listing.id(), "change keyword", null, null, null);

    crawl(actorA, crawlReq(listing.id(), "k-chg-1", at(1, 3), 30, List.of(rank(keyword.keywordId(), 10, 5))));
    crawl(actorA, crawlReq(listing.id(), "k-chg-2", at(2, 3), 30, List.of(rank(keyword.keywordId(), 15, 3))));

    var page = as(actorA, "channel.operation.read", () -> service.keywords(null, null, null, null, null, null, 0, 50));
    var row = page.items().stream().filter(r -> r.keywordId().equals(keyword.keywordId())).findFirst().orElseThrow();
    assertThat(row.latestNaturalRank()).isEqualTo(15);
    // 🔴 名次数字变大 = 变差，变化量是 +5
    assertThat(row.naturalRankChange()).isEqualTo(5);
    assertThat(row.adRankChange()).isEqualTo(-2);

    // 没有上一次时变化量是 null（不是 0）
    var single = newKeyword(actorA, listing.id(), "single keyword", null, null, null);
    crawl(actorA, crawlReq(listing.id(), "k-chg-3", at(3, 3), 30, List.of(rank(single.keywordId(), 8, 2))));
    var page2 = as(actorA, "channel.operation.read", () -> service.keywords(null, null, null, null, null, null, 0, 50));
    var singleRow =
        page2.items().stream().filter(r -> r.keywordId().equals(single.keywordId())).findFirst().orElseThrow();
    assertThat(singleRow.naturalRankChange()).as("没有可比的上次 ⇒ null，不是 0").isNull();
  }

  // ==================================================================
  // 3. 🔴 变化预警
  // ==================================================================

  @Test
  @DisplayName("4.5.1.2 🔴 低于阈值产生预警记录（预警是记录，不发通知）")
  void alertsRecordedWhenBelowThreshold() {
    UUID shop = seedShop(a, "LKR-ALT");
    var listing = seedListing(actorA, shop, amazonUsMarketplace(), "LKR-A12", "预警商品");
    var keyword = newKeyword(actorA, listing.id(), "alert keyword", 10, 5, null);

    // 自然 25 > 阈值 10 ⇒ 预警；广告 3 ≤ 阈值 5 ⇒ 不预警
    var result = crawl(actorA, crawlReq(listing.id(), "k-alt-1", at(1, 3), 30, List.of(rank(keyword.keywordId(), 25, 3))));
    assertThat(result.alertCount()).isEqualTo(1);

    var alerts = as(actorA, "channel.operation.read", () -> service.alerts(null, null, null, null, 0, 50));
    assertThat(alerts.items()).hasSize(1);
    var alert = alerts.items().get(0);
    assertThat(alert.alertType()).isEqualTo("NATURAL_BELOW_THRESHOLD");
    assertThat(alert.rankValue()).isEqualTo(25);
    assertThat(alert.thresholdValue()).isEqualTo(10);
    assertThat(alert.acknowledgedAt()).as("未确认时为 null").isNull();
    assertThat(alerts.openCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("4.5.1.2 🔴 没配阈值就不产生预警（「没配阈值」与「阈值是 0」是两件事）")
  void noAlertWithoutThreshold() {
    UUID shop = seedShop(a, "LKR-NOTH");
    var listing = seedListing(actorA, shop, amazonUsMarketplace(), "LKR-A13", "无阈值商品");
    var keyword = newKeyword(actorA, listing.id(), "no threshold keyword", null, null, null);

    var result = crawl(actorA, crawlReq(listing.id(), "k-noth-1", at(1, 3), 30, List.of(rank(keyword.keywordId(), 999, 888))));
    assertThat(result.alertCount()).as("没配阈值 ⇒ 不预警").isZero();

    var alerts = as(actorA, "channel.operation.read", () -> service.alerts(null, null, null, null, 0, 50));
    assertThat(alerts.items()).isEmpty();
  }

  @Test
  @DisplayName("4.5.1.2 🔴 排名退步超阈值产生变化预警（名次变大 = 变差）")
  void rankDroppedAlert() {
    UUID shop = seedShop(a, "LKR-DROP");
    var listing = seedListing(actorA, shop, amazonUsMarketplace(), "LKR-A14", "退步商品");
    var keyword = newKeyword(actorA, listing.id(), "drop keyword", null, null, 5);

    crawl(actorA, crawlReq(listing.id(), "k-drop-1", at(1, 3), 30, List.of(rank(keyword.keywordId(), 10, 2))));
    // 10 → 22，退步 12 位 > 阈值 5 ⇒ 预警；广告 2 → 3 退步 1 位 ≤ 5 ⇒ 不预警
    var result = crawl(actorA, crawlReq(listing.id(), "k-drop-2", at(2, 3), 30, List.of(rank(keyword.keywordId(), 22, 3))));
    assertThat(result.alertCount()).isEqualTo(1);

    var alerts = as(actorA, "channel.operation.read", () -> service.alerts(null, null, "NATURAL_RANK_DROPPED", null, 0, 50));
    assertThat(alerts.items()).hasSize(1);
    var alert = alerts.items().get(0);
    assertThat(alert.rankValue()).isEqualTo(22);
    assertThat(alert.previousRank()).isEqualTo(10);
    assertThat(alert.thresholdValue()).isEqualTo(5);
  }

  @Test
  @DisplayName("4.5.1.2 🔴 预警确认留痕（「有没有人看过」这个事实也要留下）")
  void alertAcknowledgementIsTracked() {
    UUID shop = seedShop(a, "LKR-ACK");
    var listing = seedListing(actorA, shop, amazonUsMarketplace(), "LKR-A15", "确认商品");
    var keyword = newKeyword(actorA, listing.id(), "ack keyword", 10, null, null);
    crawl(actorA, crawlReq(listing.id(), "k-ack-1", at(1, 3), 30, List.of(rank(keyword.keywordId(), 50, null))));

    var alerts = as(actorA, "channel.operation.read", () -> service.alerts(null, null, null, null, 0, 50));
    UUID alertId = alerts.items().get(0).alertId();

    var acknowledged = as(actorA, "channel.operation.update", () -> service.acknowledge(alertId.toString(), user));
    assertThat(acknowledged.acknowledgedAt()).isNotNull();
    assertThat(acknowledged.acknowledgedBy()).isEqualTo(user);

    var after = as(actorA, "channel.operation.read", () -> service.alerts(null, null, null, "OPEN", 0, 50));
    assertThat(after.items()).as("确认后未确认列表为空").isEmpty();
    assertThat(after.openCount()).isZero();
  }

  // ==================================================================
  // 4. 🔴 排名历史与趋势
  // ==================================================================

  @Test
  @DisplayName("4.5.1.2 🔴 排名历史是真分页（total 是服务端 count 出来的）")
  void historyIsPaginated() {
    UUID shop = seedShop(a, "LKR-HIS");
    var listing = seedListing(actorA, shop, amazonUsMarketplace(), "LKR-A16", "历史商品");
    var keyword = newKeyword(actorA, listing.id(), "history keyword", null, null, null);
    for (int day = 1; day <= 5; day++) {
      crawl(
          actorA,
          crawlReq(listing.id(), "k-his-" + day, at(day, 3), 30,
              List.of(rank(keyword.keywordId(), 10 + day, 5))));
    }

    var page1 = as(actorA, "channel.operation.read", () -> service.history(keyword.keywordId().toString(), null, null, 0, 2));
    assertThat(page1.total()).isEqualTo(5);
    assertThat(page1.items()).hasSize(2);
    // 按抓取时间倒序
    assertThat(page1.items().get(0).crawledAt()).isAfter(page1.items().get(1).crawledAt());

    var page3 = as(actorA, "channel.operation.read", () -> service.history(keyword.keywordId().toString(), null, null, 2, 2));
    assertThat(page3.items()).hasSize(1);
    assertThat(page3.total()).isEqualTo(5);

    // 每行都带抓取时间与不准确窗口
    assertThat(page1.items().get(0).crawledAt()).isNotNull();
    assertThat(page1.items().get(0).accuracyWindowMinutes()).isEqualTo(30);
    assertThat(page1.items().get(0).accuracyNote()).contains("抓取时间");
    // 🔴 变化量逐行现算
    assertThat(page1.items().get(0).naturalRankChange()).isEqualTo(1);
    assertThat(page1.items().get(1).naturalRankChange()).isEqualTo(1);
  }

  @Test
  @DisplayName("4.5.1.2 🔴 趋势没有数据时是空数组（不是一条平线）")
  void trendIsEmptyWithoutData() {
    UUID shop = seedShop(a, "LKR-TRD");
    var listing = seedListing(actorA, shop, amazonUsMarketplace(), "LKR-A17", "趋势商品");
    var keyword = newKeyword(actorA, listing.id(), "trend keyword", null, null, null);

    var trend =
        as(actorA, "channel.operation.read",
            () -> service.trend(keyword.keywordId().toString(), "2026-10-01", "2026-10-31", "DAY"));
    assertThat(trend.points()).as("没有数据时是空数组，不是假曲线").isEmpty();
    assertThat(trend.hasData()).isFalse();
    assertThat(trend.accuracyNote()).contains("抓取时间");
  }

  @Test
  @DisplayName("4.5.1.2 🔴 趋势按 UTC 日归集（最好/最差名次 = 区间内最小/最大）")
  void trendGroupsByDay() {
    UUID shop = seedShop(a, "LKR-TRD2");
    var listing = seedListing(actorA, shop, amazonUsMarketplace(), "LKR-A18", "趋势商品2");
    var keyword = newKeyword(actorA, listing.id(), "trend keyword 2", null, null, null);

    crawl(actorA, crawlReq(listing.id(), "k-trd2-1", at(1, 3), 30, List.of(rank(keyword.keywordId(), 10, 2))));
    crawl(actorA, crawlReq(listing.id(), "k-trd2-2", at(1, 9), 30, List.of(rank(keyword.keywordId(), 4, 6))));
    crawl(actorA, crawlReq(listing.id(), "k-trd2-3", at(2, 3), 30, List.of(rank(keyword.keywordId(), 30, 1))));

    var trend =
        as(actorA, "channel.operation.read",
            () -> service.trend(keyword.keywordId().toString(), "2026-10-01", "2026-10-31", "DAY"));
    assertThat(trend.points()).hasSize(2);
    assertThat(trend.points().get(0).bucket()).isEqualTo("2026-10-01");
    assertThat(trend.points().get(0).sampleCount()).isEqualTo(2);
    // 🔴 名次数字越小越好
    assertThat(trend.points().get(0).bestNaturalRank()).isEqualTo(4);
    assertThat(trend.points().get(0).worstNaturalRank()).isEqualTo(10);
    assertThat(trend.points().get(0).lastNaturalRank()).isEqualTo(4);
    assertThat(trend.points().get(1).lastNaturalRank()).isEqualTo(30);
  }

  // ==================================================================
  // 5. 🔴 关键词维度报表
  // ==================================================================

  @Test
  @DisplayName("4.5.1.2 🔴🔴 报表是真 SQL 聚合（按平台/站点/关键词聚合平均、最好、最差）")
  void reportAggregatesByKeyword() {
    UUID shop = seedShop(a, "LKR-RPT");
    var listing = seedListing(actorA, shop, amazonUsMarketplace(), "LKR-A19", "报表商品");
    var keyword = newKeyword(actorA, listing.id(), "report keyword", 10, null, null);

    crawl(actorA, crawlReq(listing.id(), "k-rpt-1", at(1, 3), 30, List.of(rank(keyword.keywordId(), 10, null))));
    crawl(actorA, crawlReq(listing.id(), "k-rpt-2", at(2, 3), 30, List.of(rank(keyword.keywordId(), 30, null))));
    crawl(actorA, crawlReq(listing.id(), "k-rpt-3", at(3, 3), 30, List.of(rank(keyword.keywordId(), 20, null))));

    var report = as(actorA, "channel.operation.read", () -> service.report(null, null, null, null, null));
    var row =
        report.rows().stream().filter(r -> r.keyword().equals("report keyword")).findFirst().orElseThrow();

    assertThat(row.platformCode()).isEqualTo("AMAZON");
    assertThat(row.siteCode()).isEqualTo("US");
    assertThat(row.listingCount()).isEqualTo(1);
    assertThat(row.sampleCount()).isEqualTo(3);
    // 🔴 平均 = (10+30+20)/3 = 20.00
    assertThat(row.averageNaturalRank()).isEqualByComparingTo("20.00");
    assertThat(row.bestNaturalRank()).isEqualTo(10);
    assertThat(row.worstNaturalRank()).isEqualTo(30);
    // 🔴 30 与 20 都 > 阈值 10 ⇒ 低于阈值两次；10 **不算**（等于阈值仍在阈值内）
    assertThat(row.belowThresholdCount()).isEqualTo(2);
    assertThat(row.openAlertCount()).isEqualTo(2);
    assertThat(row.lastCrawledAt()).isNotNull();
    assertThat(row.accuracyWindowMinutes()).isEqualTo(30);
    assertThat(report.accuracyNote()).contains("抓取时间");
  }

  @Test
  @DisplayName("4.5.1.2 🔴 报表在没有样本时是空数组（不是一行 0）")
  void reportIsEmptyWithoutSamples() {
    UUID shop = seedShop(a, "LKR-RPT0");
    var listing = seedListing(actorA, shop, amazonUsMarketplace(), "LKR-A20", "空报表商品");
    newKeyword(actorA, listing.id(), "empty report keyword", 10, null, null);

    var report = as(actorA, "channel.operation.read", () -> service.report(null, null, null, null, null));
    assertThat(report.rows()).as("没有样本就没有报表行").isEmpty();
    assertThat(report.keywordCount()).isZero();
  }

  // ==================================================================
  // 6. 🔴 能力声明
  // ==================================================================

  @Test
  @DisplayName("4.5.1.2 🔴 能力表：未交付段 available=false + ownerModule + impact")
  void capabilitiesDeclareUndelivered() {
    var caps = as(actorA, "channel.operation.read", () -> service.capabilities());

    var undelivered =
        caps.dimensions().stream().filter(d -> !d.available()).map(d -> d.dimension()).toList();
    assertThat(undelivered)
        .as("1 工作台预警 / 14 广告 / 02 抓取调度器都必须显式声明未交付")
        .contains("workbench_alert_delivery", "ad_attribution", "real_crawl_scheduler");

    for (var d : caps.dimensions()) {
      if (!d.available()) {
        assertThat(d.ownerModule()).as(d.dimension() + " 必须写明归属模块").isNotBlank();
        assertThat(d.impact()).as(d.dimension() + " 必须写明不交付的影响").isNotBlank();
      }
    }
    assertThat(caps.accuracyNote()).contains("抓取时间").contains("不准确窗口");
    assertThat(caps.rankDefinitions()).contains("null");
  }

  // ==================================================================
  // 7. 🔴 DB 层闸门
  // ==================================================================

  @Test
  @DisplayName("4.5.1.2 🔴 DB 层闸门：快照引用的抓取批次必须同商品；抓取终态不可再改")
  void dbLevelGuards() {
    UUID shopA = seedShop(a, "LKR-GUARD-A");
    UUID shopB = seedShop(b, "LKR-GUARD-B");
    var listingA = seedListing(actorA, shopA, amazonUsMarketplace(), "LKR-A21", "A 的商品");
    var listingB = seedListing(actorB, shopB, amazonUsMarketplace(), "LKR-B21", "B 的商品");
    var keywordA = newKeyword(actorA, listingA.id(), "guard keyword", null, null, null);

    var crawlA = crawl(actorA, crawlReq(listingA.id(), "k-guard-1", at(1, 3), 30,
        List.of(rank(keywordA.keywordId(), 10, null))));

    // 🔴 快照挂到**别的商品**的抓取批次上 ⇒ 触发器拒绝
    String crossListing =
        "insert into listing_keyword_rank_snapshot"
            + " (id,tenant_id,keyword_id,listing_id,natural_rank,ad_rank,crawled_at,"
            + "  accuracy_window_minutes,source,ingest_channel,last_crawl_id,revision,detail,"
            + "  received_at,created_at,updated_at)"
            + " values (gen_random_uuid(),'" + a + "','" + keywordA.keywordId() + "','"
            + listingB.id() + "',5,null,'2026-10-01 03:00:00+00',30,'PLATFORM','API','"
            + crawlA.crawlId() + "',1,'{}'::jsonb,now(),now(),now())";
    assertThat(sqlState(crossListing))
        .as("快照引用的抓取批次必须是同一个商品的")
        .isEqualTo("23514");

    // 🔴 抓取批次落终态后不可再改
    String rewriteFinal =
        "update listing_keyword_crawl set status='RECEIVED' where id='" + crawlA.crawlId() + "'";
    assertThat(sqlState(rewriteFinal)).as("终态不可再改").isEqualTo("23514");

    // 🔴 跨租户挂 listing 在物理上不可能（复合 FK 带 tenant_id）。
    //    这条打在 `listing_keyword` 上而不是快照上：快照的 BEFORE 触发器会**先**拦下来
    //    （触发器早于 FK 检查），打在那儿断言到的会是 23514 而不是 FK 的 23503。
    String crossTenant =
        "insert into listing_keyword"
            + " (id,tenant_id,listing_id,keyword,platform_code,site_code,"
            + "  natural_rank_threshold,ad_rank_threshold,rank_drop_threshold,active,note,"
            + "  created_at,updated_at)"
            + " values (gen_random_uuid(),'" + a + "','" + listingB.id() + "','cross tenant keyword',"
            + "'AMAZON','US',null,null,null,true,'','2026-10-01 00:00:00+00','2026-10-01 00:00:00+00')";
    assertThat(sqlState(crossTenant)).as("跨租户挂引用被复合 FK 拒绝").isEqualTo("23503");
  }

  @Test
  @DisplayName("4.5.1.2 🔴 四张表都 FORCE RLS（只 ENABLE 的话 owner 能绕过策略）")
  void allTablesForceRls() {
    for (String table :
        List.of(
            "listing_keyword",
            "listing_keyword_crawl",
            "listing_keyword_rank_snapshot",
            "listing_keyword_rank_alert")) {
      assertThat(
              String.valueOf(
                  scalar("select relforcerowsecurity::text from pg_class where relname='" + table + "'")))
          .as(table + " 必须 FORCE RLS")
          .isEqualTo("true");
    }
  }

  // ==================================================================
  // 8. HTTP 面
  // ==================================================================

  @Test
  @DisplayName("4.5.1.2 HTTP 面：401 / 404 / 422")
  void httpSurface() throws Exception {
    UUID shop = seedShop(a, "LKR-HTTP");
    var listing = seedListing(actorA, shop, amazonUsMarketplace(), "LKR-A22", "HTTP 商品");
    var keyword = newKeyword(actorA, listing.id(), "http keyword", 10, null, null);

    // 无会话 ⇒ 401
    http.perform(get("/channel/listing-keyword-rankings/capabilities"))
        .andExpect(status().isUnauthorized());

    // 不存在的关键词 ⇒ 404
    http.perform(get("/channel/listing-keyword-rankings/" + UUID.randomUUID())
            .header("Authorization", bearerA))
        .andExpect(status().isNotFound());

    // 非法筛选参数 ⇒ 400
    http.perform(get("/channel/listing-keyword-rankings?status=WHAT")
            .header("Authorization", bearerA))
        .andExpect(status().isBadRequest());

    // 关键字为空 ⇒ 400（Bean Validation 的 @NotBlank 先拦下来了）
    http.perform(post("/channel/listing-keyword-rankings")
            .header("Authorization", bearerA)
            .contentType("application/json")
            .content(JSON.writeValueAsString(
                new ListingKeywordRankingKeywordInput(listing.id(), "  ", null, null, null, ""))))
        .andExpect(status().isBadRequest());

    // 挂到不存在的商品上 ⇒ 422（服务层语义：关键词必须挂在真实存在的商品上）
    http.perform(post("/channel/listing-keyword-rankings")
            .header("Authorization", bearerA)
            .contentType("application/json")
            .content(JSON.writeValueAsString(
                new ListingKeywordRankingKeywordInput(UUID.randomUUID(), "orphan keyword",
                    10, 5, 3, ""))))
        .andExpect(status().isUnprocessableEntity());

    // 🔴 抓取任务挂到不存在的商品上 ⇒ 422（服务层语义）
    http.perform(post("/channel/listing-keyword-rankings/crawls")
            .header("Authorization", bearerA)
            .contentType("application/json")
            .content(JSON.writeValueAsString(
                new ListingKeywordRankingCrawlRequest(
                    UUID.randomUUID(), "PLATFORM", "API", OffsetDateTime.now(ZoneOffset.UTC), 30,
                    "k-http-2", "", Map.of(), List.of(rank(keyword.keywordId(), 5, null))))))
        .andExpect(status().isUnprocessableEntity());

    // 🔴 把**别的商品**的关键词挂到这次抓取上 ⇒ 422（调用方的契约错误，整批拒绝）
    UUID otherShop = seedShop(a, "LKR-HTTP-2");
    var otherListing = seedListing(actorA, otherShop, amazonUsMarketplace(), "LKR-A23", "另一个商品");
    var otherKeyword = newKeyword(actorA, otherListing.id(), "other keyword", null, null, null);
    http.perform(post("/channel/listing-keyword-rankings/crawls")
            .header("Authorization", bearerA)
            .contentType("application/json")
            .content(JSON.writeValueAsString(
                new ListingKeywordRankingCrawlRequest(
                    listing.id(), "PLATFORM", "API", OffsetDateTime.now(ZoneOffset.UTC), 30,
                    "k-http-3", "", Map.of(), List.of(rank(otherKeyword.keywordId(), 5, null))))))
        .andExpect(status().isUnprocessableEntity());

    // 🔴 抓取任务缺抓取时间 ⇒ 400（Bean Validation 的 @NotNull 先拦下来了；
    //    服务层还有一道 422 兜底，两层都拒）
    http.perform(post("/channel/listing-keyword-rankings/crawls")
            .header("Authorization", bearerA)
            .contentType("application/json")
            .content(JSON.writeValueAsString(
                new ListingKeywordRankingCrawlRequest(
                    listing.id(), "PLATFORM", "API", null, 30, "k-http-1", "", Map.of(),
                    List.of(rank(keyword.keywordId(), 5, null))))))
        .andExpect(status().isBadRequest());

    // 正常路径 ⇒ 200（本租户 2 个关键词：本条 + 上面为「关键词归属」那条建的）
    http.perform(get("/channel/listing-keyword-rankings")
            .header("Authorization", bearerA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(2));
    http.perform(get("/channel/listing-keyword-rankings/filter-options")
            .header("Authorization", bearerA))
        .andExpect(status().isOk());
    http.perform(get("/channel/listing-keyword-rankings/report")
            .header("Authorization", bearerA))
        .andExpect(status().isOk());
  }

  @Test
  @DisplayName("4.5.1.2 筛选词表只给本租户真实有的平台/店铺/站点")
  void filterOptionsAreTenantScoped() {
    UUID shopA = seedShop(a, "LKR-FO-A");
    seedShop(b, "LKR-FO-B");
    seedListing(actorA, shopA, amazonUsMarketplace(), "LKR-A23", "筛选商品");

    var options = as(actorA, "channel.operation.read", () -> service.filterOptions());
    assertThat(options.shops()).extracting("code").contains("LKR-FO-A");
    assertThat(options.shops()).extracting("code").doesNotContain("LKR-FO-B");
    assertThat(options.platforms()).extracting("code").contains("AMAZON");
    assertThat(options.sites()).extracting("code").contains("US");
  }

  /**
   * 🔴 收尾：停掉本地桩与 Testcontainers 容器。
   *
   * <p>不回收容器的话，Ryuk 只在 JVM 退出时按 session 标签统一回收 —— 整个模块跑完之前容器一直活着
   * （实测 channel 一个服务就能堆到 110+ 个 postgres 容器），容器内存 + Docker VM 把机器压进 swap，
   * JVM 被卡住十几分钟 ⇒ 全量跑时断言假红、SSL 握手失败。
   *
   * <p>顺序：先停外部服务，再停容器。反了会出现「容器已停但还有 in-flight 请求打进来」。
   */
  @AfterAll
  static void stopTestResources() {
    if (identity != null) identity.stop(0);
    pg.stop();
  }
}
