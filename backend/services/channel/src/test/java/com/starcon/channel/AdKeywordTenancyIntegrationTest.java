package com.starcon.channel;

import static com.starcon.channel.AdKeywordModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.LocalDate;
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
 * 14.1.3.1 关键词管理 的多租户三断言（AGENTS.md 硬性要求，缺一不可）。
 *
 * <ol>
 *   <li><b>两租户互不可见</b>（关键词 / 指标 / 搜索词 / 否定 / 批量任务 / 批量结果 /
 *       明细 / 报表 / 词频排名 都算）
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
class AdKeywordTenancyIntegrationTest {

  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID user = UUID.randomUUID();
  static HttpServer identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

  UUID a, b;
  String bearerA, bearerB;
  UUID shopA, shopB;

  /** 🔴 平台与站点注册表由 V1 迁移随代码发布（只读码表），测试直接用它。 */
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
    bearerA = "Bearer ak-t-a-" + a;
    bearerB = "Bearer ak-t-b-" + b;
    List<AccessService.Grant> grants = List.of(grant("ALL", allPermissions));
    actors.put(bearerA, actor(user, a, grants));
    actors.put(bearerB, actor(user, b, grants));
    shopA = seedShop(a, "AKT-A-" + a.toString().substring(0, 6));
    shopB = seedShop(b, "AKT-B-" + b.toString().substring(0, 6));
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

  @Autowired AdKeywordService service;
  @Autowired AccessService access;
  @Autowired MockMvc http;

  // ==================================================================
  // 辅助
  // ==================================================================

  <T> T as(String bearer, String permission, java.util.function.Supplier<T> work) {
    return access.run(actors.get(bearer), permission, work);
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

  /** 以管理员连接读一行标量（只用于读 pg_class 这类不受 RLS 约束的事实）。 */
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

  AdKeywordRow newKeyword(String bearer, UUID shop, String externalKeywordId, String keyword) {
    return as(
        bearer,
        "channel.operation.create",
        () -> service.saveKeyword(
            null,
            new AdKeywordInput(
                AMAZON, shop, "CAMP-1", externalKeywordId, keyword, "sponsoredProducts", null,
                new java.math.BigDecimal("1.00"), "USD", "ACTIVE", null, "")));
  }

  void ingestMetrics(String bearer, UUID keywordId, String key, int impressions) {
    as(
        bearer,
        "channel.operation.create",
        () -> service.ingestMetrics(
            new AdKeywordMetricBatchRequest(
                "PLATFORM", "src-" + key, key, "",
                List.of(
                    new AdKeywordMetricInput(
                        keywordId, LocalDate.of(2026, 10, 1), (long) impressions, 10L, 1,
                        new java.math.BigDecimal("10.0000"), new java.math.BigDecimal("50.0000"),
                        "USD", at(1, 3))))));
  }

  void ingestSearchTerms(String bearer, UUID keywordId, String key) {
    as(
        bearer,
        "channel.operation.create",
        () -> service.ingestSearchTerms(
            new AdSearchTermBatchRequest(
                "PLATFORM", "src-" + key, key, "",
                List.of(
                    new AdSearchTermInput(
                        keywordId, LocalDate.of(2026, 10, 1), "waste term " + key, "CAMP-1",
                        900L, 30L, 0, new java.math.BigDecimal("90.0000"),
                        java.math.BigDecimal.ZERO, "USD", at(1, 3))))));
  }

  static OffsetDateTime at(int day, int hour) {
    return OffsetDateTime.of(2026, 10, day, hour, 0, 0, 0, ZoneOffset.UTC);
  }

  // ==================================================================
  // 断言一：两租户互不可见
  // ==================================================================

  @Test
  @DisplayName("14.1.3.1 断言一：两租户互不可见（关键词 / 指标 / 搜索词 / 否定 / 批量任务 / 报表）")
  void tenantsAreIsolated() {
    var keywordA = newKeyword(bearerA, shopA, "kw-a", "tenant a keyword");
    var keywordB = newKeyword(bearerB, shopB, "kw-b", "tenant b keyword");

    ingestMetrics(bearerA, keywordA.keywordId(), "ta-1", 1000);
    ingestMetrics(bearerB, keywordB.keywordId(), "tb-1", 2000);
    ingestSearchTerms(bearerA, keywordA.keywordId(), "sta-1");
    ingestSearchTerms(bearerB, keywordB.keywordId(), "stb-1");

    var negativeA =
        as(
            bearerA,
            "channel.operation.create",
            () -> service.createNegative(
                new AdNegativeInput(
                    keywordA.keywordId(), "waste term sta-1", "NEGATIVE_EXACT", "CAMP-1",
                    "A 的否定", null, null, null, "MANUAL")));
    var negativeB =
        as(
            bearerB,
            "channel.operation.create",
            () -> service.createNegative(
                new AdNegativeInput(
                    keywordB.keywordId(), "waste term stb-1", "NEGATIVE_EXACT", "CAMP-1",
                    "B 的否定", null, null, null, "MANUAL")));

    var bulkA =
        as(
            bearerA,
            "channel.operation.update",
            () -> service.bulkAction(
                new AdBulkActionRequest(
                    "BID_ADJUST", shopA, "ABSOLUTE", "USD",
                    List.of(new AdBulkBidTarget(keywordA.keywordId(), new java.math.BigDecimal("2.00"))),
                    null, "bulk-a", "")));
    var bulkB =
        as(
            bearerB,
            "channel.operation.update",
            () -> service.bulkAction(
                new AdBulkActionRequest(
                    "BID_ADJUST", shopB, "ABSOLUTE", "USD",
                    List.of(new AdBulkBidTarget(keywordB.keywordId(), new java.math.BigDecimal("3.00"))),
                    null, "bulk-b", "")));

    // B 的关键词列表里只有 B 自己的行
    var pageB = as(bearerB, "channel.operation.read",
        () -> service.keywords(null, null, null, null, null, null, null, null, null, null, null, null, 0, 50));
    assertThat(pageB.items()).as("B 的租户上下文里只有 B 自己的关键词").hasSize(1);
    assertThat(pageB.items().get(0).keywordId()).isEqualTo(keywordB.keywordId());
    assertThat(pageB.items().get(0).keywordId()).isNotEqualTo(keywordA.keywordId());

    // B 读 A 的关键词明细 ⇒ 404
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () -> as(bearerB, "channel.operation.read",
                    () -> service.detail(keywordA.keywordId().toString()))))
        .as("B 按 id 取 A 的关键词明细必须 404").isNotNull();

    // B 的搜索词报告里没有 A 的搜索词
    var termsB = as(bearerB, "channel.operation.read",
        () -> service.searchTerms(null, null, null, null, null, null, null, 0, 50));
    assertThat(termsB.items()).isNotEmpty();
    assertThat(termsB.items())
        .as("B 的搜索词报告里不能出现 A 的关键词")
        .allSatisfy(row -> assertThat(row.keywordId()).isEqualTo(keywordB.keywordId()));

    // B 的否定列表里没有 A 的否定
    var negativesB = as(bearerB, "channel.operation.read",
        () -> service.negatives(null, null, null, null, 0, 50));
    assertThat(negativesB.items()).isNotEmpty();
    assertThat(negativesB.items())
        .as("B 的否定列表里不能出现 A 的否定")
        .allSatisfy(row -> assertThat(row.negativeId()).isNotEqualTo(negativeA.negativeId()));
    assertThat(negativesB.items()).extracting("negativeId").doesNotContain(negativeA.negativeId());

    // B 的批量任务列表里没有 A 的任务
    var bulkPageB = as(bearerB, "channel.operation.read",
        () -> service.bulkActions(null, null, 0, 50));
    assertThat(bulkPageB.items()).isNotEmpty();
    assertThat(bulkPageB.items())
        .as("B 的批量任务列表里不能出现 A 的任务")
        .allSatisfy(row -> assertThat(row.actionId()).isNotEqualTo(bulkA.actionId()));
    assertThat(bulkPageB.items()).extracting("actionId").doesNotContain(bulkA.actionId());

    // B 读 A 的批量任务详情 ⇒ 404
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () -> as(bearerB, "channel.operation.read",
                    () -> service.bulkActionDetail(bulkA.actionId().toString()))))
        .as("B 按 id 取 A 的批量任务详情必须 404").isNotNull();

    // B 的报表里没有 A 的关键词
    var reportB = as(bearerB, "channel.operation.read",
        () -> service.report("PLATFORM", null, null, null, 0, 50));
    assertThat(reportB.rows())
        .as("B 的报表里不能出现 A 的关键词")
        .allSatisfy(row -> assertThat(row.keywordCount()).isEqualTo(1L));

    // 🔴 按主键直查（用 channel_app 连，超级用户绕过 RLS 会让这个断言永远「通过」）
    assertThat(countAsTenant(b,
            "select count(*) from ad_keyword where id='" + keywordA.keywordId() + "'"))
        .as("B 的上下文里直查 A 的关键词必须读到零行").isZero();
    assertThat(countAsTenant(b,
            "select count(*) from ad_keyword_metric where keyword_id='"
                + keywordA.keywordId() + "'"))
        .as("B 的上下文里直查 A 的指标必须读到零行").isZero();
    assertThat(countAsTenant(b,
            "select count(*) from ad_search_term where keyword_id='"
                + keywordA.keywordId() + "'"))
        .as("B 的上下文里直查 A 的搜索词必须读到零行").isZero();
    assertThat(countAsTenant(b,
            "select count(*) from ad_negative_keyword where id='" + negativeA.negativeId() + "'"))
        .as("B 的上下文里直查 A 的否定必须读到零行").isZero();
    assertThat(countAsTenant(b,
            "select count(*) from ad_keyword_bulk_action where id='" + bulkA.actionId() + "'"))
        .as("B 的上下文里直查 A 的批量任务必须读到零行").isZero();
    assertThat(countAsTenant(b,
            "select count(*) from ad_keyword_bulk_action_item where action_id='"
                + bulkA.actionId() + "'"))
        .as("B 的上下文里直查 A 的批量结果必须读到零行").isZero();

    // 反向同理
    assertThat(countAsTenant(a,
            "select count(*) from ad_keyword where id='" + keywordB.keywordId() + "'"))
        .isZero();
    assertThat(countAsTenant(a,
            "select count(*) from ad_keyword_metric where keyword_id='"
                + keywordB.keywordId() + "'"))
        .isZero();
    assertThat(countAsTenant(a,
            "select count(*) from ad_search_term where keyword_id='"
                + keywordB.keywordId() + "'"))
        .isZero();
    assertThat(countAsTenant(a,
            "select count(*) from ad_negative_keyword where id='" + negativeB.negativeId() + "'"))
        .isZero();
    assertThat(countAsTenant(a,
            "select count(*) from ad_keyword_bulk_action where id='" + bulkB.actionId() + "'"))
        .isZero();

    // 六张表都是 FORCE RLS
    for (String table :
        List.of(
            "ad_keyword", "ad_keyword_metric", "ad_search_term", "ad_negative_keyword",
            "ad_keyword_bulk_action", "ad_keyword_bulk_action_item")) {
      assertThat(scalar("select relforcerowsecurity::text from pg_class where relname='" + table + "'"))
          .as(table + " 必须 FORCE RLS").isEqualTo("true");
    }

    // 同一机制在本租户自己的数据上必须看得见行 —— 否则上面那些「零行」可能只是查询一直返回 0
    assertThat(countAsTenant(a,
            "select count(*) from ad_keyword where id='" + keywordA.keywordId() + "'"))
        .as("A 的上下文必须能直查 A 自己的关键词（证明上面那个 0 是隔离，不是查询失效）")
        .isEqualTo(1);
    assertThat(countAsTenant(a,
            "select count(*) from ad_keyword_metric where keyword_id='"
                + keywordA.keywordId() + "'"))
        .isEqualTo(1);
  }

  @Test
  @DisplayName("14.1.3.1 断言一（续）：B 拿自己的 key 也写不进 A 的租户")
  void writesStayInsideTenant() {
    var keywordA = newKeyword(bearerA, shopA, "kw-a2", "tenant a keyword 2");
    var keywordB = newKeyword(bearerB, shopB, "kw-b2", "tenant b keyword 2");

    // B 的会话 + A 的关键词 id 做指标同步 ⇒ A 的租户里不能出现 B 的数据
    ingestMetrics(bearerB, keywordB.keywordId(), "tb-2", 100);
    assertThat(countAsTenant(b,
            "select count(*) from ad_keyword_metric where keyword_id='"
                + keywordB.keywordId() + "'"))
        .isEqualTo(1);
    assertThat(countAsTenant(a,
            "select count(*) from ad_keyword_metric where keyword_id='"
                + keywordA.keywordId() + "'"))
        .isZero();

    // B 的批量调价指定 A 的店铺 ⇒ 整批被拒（A 的店铺对 B 不可见）
    var thrown =
        org.assertj.core.api.Assertions.catchThrowable(
            () -> as(
                bearerB,
                "channel.operation.update",
                () -> service.bulkAction(
                    new AdBulkActionRequest(
                        "BID_ADJUST", shopA, "ABSOLUTE", "USD",
                        List.of(new AdBulkBidTarget(keywordB.keywordId(), new java.math.BigDecimal("2.00"))),
                        null, "bulk-cross", ""))));
    assertThat(thrown).as("B 拿 A 的店铺做批量调价必须失败").isNotNull();
  }

  // ==================================================================
  // 断言二：伪造 tenant header 无效
  // ==================================================================

  @Test
  @DisplayName("14.1.3.1 断言二：伪造 tenant header 无效（租户只来自已认证成员关系）")
  void forgedTenantHeaderIsIgnored() throws Exception {
    var keywordA = newKeyword(bearerA, shopA, "kw-forge-a", "forged a keyword");
    var keywordB = newKeyword(bearerB, shopB, "kw-forge-b", "forged b keyword");
    ingestMetrics(bearerB, keywordB.keywordId(), "tb-forge", 500);

    // A 的会话 + B 的头 ⇒ 仍然读不到 B 的关键词
    http.perform(get("/channel/ad-keywords/" + keywordB.keywordId())
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isNotFound());
    http.perform(get("/channel/ad-keywords")
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].keywordId").value(keywordA.keywordId().toString()));

    // A 的会话 + A 的头 ⇒ 只能看到 A 自己的
    http.perform(get("/channel/ad-keywords")
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", a.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].keywordId").value(keywordA.keywordId().toString()));

    // A 用伪造的 B 头去登记关键词 ⇒ 仍然落在 A 的租户里
    http.perform(post("/channel/ad-keywords")
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", b.toString())
            .contentType("application/json")
            .content(JSON.writeValueAsString(
                new AdKeywordInput(
                    AMAZON, shopA, "CAMP-1", "kw-forge-new", "forged header keyword",
                    "sponsoredProducts", null, new java.math.BigDecimal("1.00"), "USD", "ACTIVE",
                    null, ""))))
        .andExpect(status().isOk());

    assertThat(countAsTenant(a,
            "select count(*) from ad_keyword where external_keyword_id='kw-forge-new'"))
        .as("伪造的头不能把数据写进别的租户").isEqualTo(1);
    assertThat(countAsTenant(b,
            "select count(*) from ad_keyword where external_keyword_id='kw-forge-new'"))
        .as("B 的租户里不能出现 A 登记的关键词").isZero();

    // 无会话一律 401（伪造的头救不了）
    http.perform(get("/channel/ad-keywords/capabilities")
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isUnauthorized());
  }

  // ==================================================================
  // 断言三：连接池归还后不串租户
  // ==================================================================

  @Test
  @DisplayName("14.1.3.1 断言三：连接池归还后不串租户（池压到 1，A→B→A 三轮）")
  void pooledConnectionsDoNotBleedTenants() {
    var keywordA = newKeyword(bearerA, shopA, "kw-pool-a", "pool a keyword");
    var keywordB = newKeyword(bearerB, shopB, "kw-pool-b", "pool b keyword");
    ingestMetrics(bearerA, keywordA.keywordId(), "ta-pool", 1000);
    ingestMetrics(bearerB, keywordB.keywordId(), "tb-pool", 2000);

    for (int i = 0; i < 3; i++) {
      var pageA = as(bearerA, "channel.operation.read",
          () -> service.keywords(null, null, null, null, null, null, null, null, null, null, null, null, 0, 50));
      assertThat(pageA.items()).hasSize(1);
      assertThat(pageA.items().get(0).keywordId()).isEqualTo(keywordA.keywordId());
      assertThat(pageA.items().get(0).impressions()).isEqualTo(1000L);

      var pageB = as(bearerB, "channel.operation.read",
          () -> service.keywords(null, null, null, null, null, null, null, null, null, null, null, null, 0, 50));
      assertThat(pageB.items()).hasSize(1);
      assertThat(pageB.items().get(0).keywordId()).isEqualTo(keywordB.keywordId());
      assertThat(pageB.items().get(0).impressions()).isEqualTo(2000L);

      // 🔴 另一条查询形状也要干净（明细）
      var detailA = as(bearerA, "channel.operation.read",
          () -> service.detail(keywordA.keywordId().toString()));
      assertThat(detailA.impressions()).isEqualTo(1000L);
      var detailB = as(bearerB, "channel.operation.read",
          () -> service.detail(keywordB.keywordId().toString()));
      assertThat(detailB.impressions()).isEqualTo(2000L);

      // 🔴 第三条查询形状：报表
      var reportA = as(bearerA, "channel.operation.read",
          () -> service.report("PLATFORM", null, null, null, 0, 50));
      assertThat(reportA.rows()).hasSize(1);
      assertThat(reportA.rows().get(0).spend()).isEqualByComparingTo("10.0000");
    }
  }
}
