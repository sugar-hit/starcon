package com.starcon.channel;

import static com.starcon.channel.ListingKeywordRankingModels.*;
import static com.starcon.channel.ListingModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
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
 * 4.5.1.2 关键词排名 的多租户三断言（AGENTS.md 硬性要求，缺一不可）。
 *
 * <ol>
 *   <li><b>两租户互不可见</b>（关键词集合 / 抓取任务 / 排名历史 / 预警 / 明细 / 趋势 / 报表都算）
 *   <li><b>伪造 tenant header 无效</b>（租户只来自已认证成员关系）
 *   <li><b>连接池归还后不串租户</b>（池压到 1，A→B→A 三轮）
 * </ol>
 *
 * <p>🔴 <b>按主键直查那条必须用 {@code channel_app} 连，不能用 Testcontainers 默认的超级用户。</b>
 * 超级用户<b>绕过 RLS</b> —— 用它查「读不到对方数据」会永远得到「读得到」，
 * 那不是隔离被破坏，而是这个断言本身失效了（4.1.1.2 / 4.3.2.1 实测踩过这个坑）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ListingKeywordRankingTenancyIntegrationTest {

  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID user = UUID.randomUUID();
  static HttpServer identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

  UUID a, b;
  AccessService.Access actorA, actorB;
  String bearerA, bearerB;

  // 🔴 平台与站点注册表由 V1 迁移随代码发布（只读码表），测试直接用它，不自己造一份。
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
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @BeforeEach
  void freshTenants() {
    a = UUID.randomUUID();
    b = UUID.randomUUID();
    bearerA = "Bearer t-a-" + a;
    bearerB = "Bearer t-b-" + b;
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
    // 连接池压到 1：多租户串租户在单连接下最容易暴露。
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

  /**
   * 以<b>应用角色 channel_app</b>（非超级用户）直查一行计数。
   *
   * <p>🔴 必须用 channel_app 连：Testcontainers 默认的 {@code pg.getUsername()} 是超级用户，
   * 而超级用户<b>绕过 RLS</b> —— 用它查「读不到对方数据」会永远得到「读得到」。
   */
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

  /** 以管理员连接读一行标量（只用于读全局码表这类不受 RLS 约束的事实）。 */
  static String scalar(String sql) {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement();
        var rs = s.executeQuery(sql)) {
      return rs.next() ? rs.getString(1) : null;
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

  ListingRow seedListing(
      AccessService.Access who, UUID shopId, String externalItemId, String title) {
    SaveListingRequest request =
        new SaveListingRequest(
            null, shopId, null, externalItemId, title, "Home", "cat-1",
            "ON_SALE", null, "", "IN_STOCK", null, null, null, null, null, null, List.of(), null);
    return as(who, "channel.operation.create", () -> listings.save(request));
  }

  ListingKeywordRankingKeywordRow newKeyword(
      AccessService.Access who, UUID listingId, String word) {
    return as(
        who,
        "channel.operation.create",
        () -> service.saveKeyword(null,
            new ListingKeywordRankingKeywordInput(listingId, word, 10, 5, 3, "")));
  }

  ListingKeywordRankingCrawlResult crawl(
      AccessService.Access who, ListingKeywordRankingCrawlRequest request) {
    return as(who, "channel.operation.create", () -> service.crawl(request));
  }

  static ListingKeywordRankingRankInput rank(UUID keywordId, Integer natural, Integer ad) {
    return new ListingKeywordRankingRankInput(keywordId, natural, ad, Map.of());
  }

  static ListingKeywordRankingCrawlRequest crawlReq(
      UUID listingId, String key, OffsetDateTime crawledAt, List<ListingKeywordRankingRankInput> ranks) {
    return new ListingKeywordRankingCrawlRequest(
        listingId, "PLATFORM", "API", crawledAt, 30, key, "", Map.of(), ranks);
  }

  static OffsetDateTime at(int day, int hour) {
    return OffsetDateTime.of(2026, 10, day, hour, 0, 0, 0, ZoneOffset.UTC);
  }

  // ==================================================================
  // 断言一：两租户互不可见
  // ==================================================================

  @Test
  @DisplayName("4.5.1.2 断言一：两租户互不可见（关键词 / 抓取 / 排名历史 / 预警 / 明细 / 趋势 / 报表）")
  void tenantsAreIsolated() {
    UUID shopA = seedShop(a, "LKR-T-A");
    UUID shopB = seedShop(b, "LKR-T-B");
    var listingA = seedListing(actorA, shopA, "LKR-T-A-1", "A 的商品");
    var listingB = seedListing(actorB, shopB, "LKR-T-B-1", "B 的商品");

    var keywordA = newKeyword(actorA, listingA.id(), "tenant a keyword");
    var keywordB = newKeyword(actorB, listingB.id(), "tenant b keyword");

    crawl(actorA, crawlReq(listingA.id(), "k-ten-a", at(1, 3), List.of(rank(keywordA.keywordId(), 12, 4))));
    crawl(actorB, crawlReq(listingB.id(), "k-ten-b", at(1, 3), List.of(rank(keywordB.keywordId(), 20, 6))));

    // B 的关键词列表里只有 B 自己的行
    var pageB = as(actorB, "channel.operation.read",
        () -> service.keywords(null, null, null, null, null, null, 0, 50));
    assertThat(pageB.items()).as("B 的租户上下文里只有 B 自己的关键词").hasSize(1);
    assertThat(pageB.items().get(0).keywordId()).isEqualTo(keywordB.keywordId());
    assertThat(pageB.items().get(0).keywordId()).isNotEqualTo(keywordA.keywordId());

    // B 读 A 的关键词明细 ⇒ 404
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () -> as(actorB, "channel.operation.read",
                    () -> service.detail(keywordA.keywordId().toString()))))
        .as("B 按 id 取 A 的关键词明细必须 404").isNotNull();

    // B 读 A 的关键词趋势 ⇒ 404
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () -> as(actorB, "channel.operation.read",
                    () -> service.trend(keywordA.keywordId().toString(), null, null, "DAY"))))
        .as("B 按 id 取 A 的关键词趋势必须 404").isNotNull();

    // B 的预警列表里没有 A 的预警
    //    🔴 注意：B **自己**也有预警（B 的关键词自然 20 > 阈值 10、广告 6 > 阈值 5），
    //    所以断言不是「列表为空」，而是「列表里没有 A 的关键词」——
    //    写成 isEmpty() 会把「B 自己的预警」也当成隔离失败。
    var alertsB = as(actorB, "channel.operation.read",
        () -> service.alerts(null, null, null, null, 0, 50));
    assertThat(alertsB.items())
        .as("B 的预警列表里不能出现 A 的关键词")
        .isNotEmpty()
        .allSatisfy(row -> assertThat(row.keywordId()).isEqualTo(keywordB.keywordId()));
    assertThat(alertsB.items()).extracting("keywordId").doesNotContain(keywordA.keywordId());

    // B 的报表里没有 A 的关键词
    //    🔴 注意：B **自己**也有报表行（B 的关键词也有抓取数据），
    //    所以断言不是「报表为空」，而是「报表里没有 A 的关键词」。
    var reportB = as(actorB, "channel.operation.read", () -> service.report(null, null, null, null, null));
    assertThat(reportB.rows())
        .as("B 的报表里不能出现 A 的关键词")
        .isNotEmpty()
        .allSatisfy(row -> assertThat(row.keyword()).isEqualTo("tenant b keyword"));

    // 🔴 按主键直查（用 channel_app 连，超级用户绕过 RLS 会让这个断言永远「通过」）
    assertThat(countAsTenant(b,
            "select count(*) from listing_keyword where id='" + keywordA.keywordId() + "'"))
        .as("B 的上下文里直查 A 的关键词必须读到零行").isZero();
    assertThat(countAsTenant(b,
            "select count(*) from listing_keyword_rank_snapshot where keyword_id='"
                + keywordA.keywordId() + "'"))
        .as("B 的上下文里直查 A 的排名历史必须读到零行").isZero();
    assertThat(countAsTenant(b,
            "select count(*) from listing_keyword_rank_alert where keyword_id='"
                + keywordA.keywordId() + "'"))
        .as("B 的上下文里直查 A 的预警必须读到零行").isZero();
    assertThat(countAsTenant(b,
            "select count(*) from listing_keyword_crawl where listing_id='" + listingA.id() + "'"))
        .as("B 的上下文里直查 A 的抓取任务必须读到零行").isZero();
    // 反向同理
    assertThat(countAsTenant(a,
            "select count(*) from listing_keyword where id='" + keywordB.keywordId() + "'"))
        .isZero();
    assertThat(countAsTenant(a,
            "select count(*) from listing_keyword_rank_snapshot where keyword_id='"
                + keywordB.keywordId() + "'"))
        .isZero();
    assertThat(countAsTenant(a,
            "select count(*) from listing_keyword_crawl where listing_id='" + listingB.id() + "'"))
        .isZero();

    // 四张表都是 FORCE RLS
    for (String table :
        List.of(
            "listing_keyword",
            "listing_keyword_crawl",
            "listing_keyword_rank_snapshot",
            "listing_keyword_rank_alert")) {
      assertThat(scalar("select relforcerowsecurity::text from pg_class where relname='" + table + "'"))
          .as(table + " 必须 FORCE RLS").isEqualTo("true");
    }

    // 同一机制在本租户自己的数据上必须看得见行 —— 否则上面那些「零行」可能只是查询一直返回 0
    assertThat(countAsTenant(a,
            "select count(*) from listing_keyword where id='" + keywordA.keywordId() + "'"))
        .as("A 的上下文必须能直查 A 自己的关键词（证明上面那个 0 是隔离，不是查询失效）")
        .isEqualTo(1);
    assertThat(countAsTenant(a,
            "select count(*) from listing_keyword_rank_snapshot where keyword_id='"
                + keywordA.keywordId() + "'"))
        .isEqualTo(1);
  }

  @Test
  @DisplayName("4.5.1.2 断言一（续）：B 拿自己的 key 也写不进 A 的租户")
  void crawlStaysInsideTenant() {
    UUID shopA = seedShop(a, "LKR-T-A2");
    UUID shopB = seedShop(b, "LKR-T-B2");
    var listingA = seedListing(actorA, shopA, "LKR-T-A2-1", "A 的商品");
    var listingB = seedListing(actorB, shopB, "LKR-T-B2-1", "B 的商品");

    var keywordA = newKeyword(actorA, listingA.id(), "cross tenant keyword");
    var keywordB = newKeyword(actorB, listingB.id(), "own b keyword");

    // B 的会话 + A 的关键词 id ⇒ 定位不到（A 的关键词对 B 不可见）⇒ 整批拒绝
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () -> crawl(actorB,
                    crawlReq(listingB.id(), "k-cross", at(1, 3),
                        List.of(rank(keywordA.keywordId(), 10, 2))))))
        .as("B 拿 A 的关键词 id 抓排名必须失败").isNotNull();

    // B 自己抓 ⇒ 只落在 B 的租户里
    crawl(actorB, crawlReq(listingB.id(), "k-own-b", at(1, 3), List.of(rank(keywordB.keywordId(), 10, 2))));
    assertThat(countAsTenant(b,
            "select count(*) from listing_keyword_rank_snapshot where keyword_id='"
                + keywordB.keywordId() + "'"))
        .isEqualTo(1);
    assertThat(countAsTenant(a,
            "select count(*) from listing_keyword_rank_snapshot where keyword_id='"
                + keywordB.keywordId() + "'"))
        .isZero();
  }

  // ==================================================================
  // 断言二：伪造 tenant header 无效
  // ==================================================================

  @Test
  @DisplayName("4.5.1.2 断言二：伪造 tenant header 无效（租户只来自已认证成员关系）")
  void forgedTenantHeaderIsIgnored() throws Exception {
    UUID shopA = seedShop(a, "LKR-FORGE-A");
    UUID shopB = seedShop(b, "LKR-FORGE-B");
    var listingA = seedListing(actorA, shopA, "LKR-FORGE-A", "A 的商品");
    var listingB = seedListing(actorB, shopB, "LKR-FORGE-B", "B 的商品");
    var keywordB = newKeyword(actorB, listingB.id(), "forged b keyword");
    crawl(actorB, crawlReq(listingB.id(), "k-forge-b", at(1, 3), List.of(rank(keywordB.keywordId(), 20, 6))));

    // A 的会话 + B 的头 ⇒ 仍然读不到 B 的关键词
    http.perform(get("/channel/listing-keyword-rankings/" + keywordB.keywordId())
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isNotFound());
    http.perform(get("/channel/listing-keyword-rankings")
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(0));

    // A 的会话 + A 的头 ⇒ 只能看到 A 自己的（此刻 A 还没数据）
    http.perform(get("/channel/listing-keyword-rankings")
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", a.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(0));

    // A 用伪造的 B 头去给 A 自己的商品建关键词 ⇒ 仍然落在 A 的租户里
    http.perform(post("/channel/listing-keyword-rankings")
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", b.toString())
            .contentType("application/json")
            .content(JSON.writeValueAsString(
                new ListingKeywordRankingKeywordInput(listingA.id(), "forged header keyword",
                    10, 5, 3, ""))))
        .andExpect(status().isOk());

    assertThat(countAsTenant(a,
            "select count(*) from listing_keyword where listing_id='" + listingA.id() + "'"))
        .as("伪造的头不能把数据写进别的租户").isEqualTo(1);
    assertThat(countAsTenant(b,
            "select count(*) from listing_keyword where listing_id='" + listingA.id() + "'"))
        .as("B 的租户里不能出现 A 的商品").isZero();

    // 无会话一律 401（伪造的头救不了）
    http.perform(get("/channel/listing-keyword-rankings/capabilities")
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isUnauthorized());
  }

  // ==================================================================
  // 断言三：连接池归还后不串租户
  // ==================================================================

  @Test
  @DisplayName("4.5.1.2 断言三：连接池归还后不串租户（池压到 1，A→B→A 三轮）")
  void pooledConnectionsDoNotBleedTenants() {
    UUID shopA = seedShop(a, "LKR-POOL-A");
    UUID shopB = seedShop(b, "LKR-POOL-B");
    var listingA = seedListing(actorA, shopA, "LKR-POOL-A", "A 的商品");
    var listingB = seedListing(actorB, shopB, "LKR-POOL-B", "B 的商品");
    var keywordA = newKeyword(actorA, listingA.id(), "pool a keyword");
    var keywordB = newKeyword(actorB, listingB.id(), "pool b keyword");
    crawl(actorA, crawlReq(listingA.id(), "k-pool-a", at(1, 3), List.of(rank(keywordA.keywordId(), 11, 4))));
    crawl(actorB, crawlReq(listingB.id(), "k-pool-b", at(1, 3), List.of(rank(keywordB.keywordId(), 22, 8))));

    for (int i = 0; i < 3; i++) {
      var pageA = as(actorA, "channel.operation.read",
          () -> service.keywords(null, null, null, null, null, null, 0, 50));
      assertThat(pageA.items()).hasSize(1);
      assertThat(pageA.items().get(0).keywordId()).isEqualTo(keywordA.keywordId());
      assertThat(pageA.items().get(0).latestNaturalRank()).isEqualTo(11);

      var pageB = as(actorB, "channel.operation.read",
          () -> service.keywords(null, null, null, null, null, null, 0, 50));
      assertThat(pageB.items()).hasSize(1);
      assertThat(pageB.items().get(0).keywordId()).isEqualTo(keywordB.keywordId());
      assertThat(pageB.items().get(0).latestNaturalRank()).isEqualTo(22);

      // 🔴 另一条查询形状也要干净（明细）
      var detailA = as(actorA, "channel.operation.read",
          () -> service.detail(keywordA.keywordId().toString()));
      assertThat(detailA.latestNaturalRank()).isEqualTo(11);
      var detailB = as(actorB, "channel.operation.read",
          () -> service.detail(keywordB.keywordId().toString()));
      assertThat(detailB.latestNaturalRank()).isEqualTo(22);

      // 🔴 第三条查询形状：报表
      var reportA = as(actorA, "channel.operation.read", () -> service.report(null, null, null, null, null));
      assertThat(reportA.rows()).hasSize(1);
      assertThat(reportA.rows().get(0).keyword()).isEqualTo("pool a keyword");
    }
  }
}
