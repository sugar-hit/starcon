package com.starcon.catalog;

import static com.starcon.catalog.PriceListModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.jooq.DSLContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 5.1.1.1 价目表的**多租户三断言**（AGENTS.md 明文要求，缺一不可）。
 *
 * <p>跑的是这三条：
 *
 * <ol>
 *   <li><b>两租户互不可见</b>：A 看不到 B 的价目表与适用范围（列表、明细、直查都验）；
 *   <li><b>伪造 tenant header 无效</b>：请求头里的 {@code X-Tenant-Id} 改不了任何事 ——
 *       租户只来自已认证成员关系（identity），伪造头下写入的行仍然落在调用者自己名下；
 *   <li><b>连接池归还后不串租户</b>：连接池压到 1 条连接，A→B→A→B 交替 5 轮，
 *       每一轮读到的都只有当前租户的数据。
 * </ol>
 *
 * <p>另加一条：<b>FORCE RLS 而不只是 ENABLE</b> —— 漏掉 FORCE 之后表 owner（迁移角色）
 * 仍能绕过策略读别人的价目表，而价目表里写着这个租户的币种、适用法人/店铺/站点
 * 与定价区间，那是一条看不见的越权面。
 *
 * <p><b>真实 PostgreSQL 16 + FORCE RLS，不使用任何内存替身</b>；运行角色
 * {@code catalog_app} 是 {@code nobypassrls}（否则它会绕过策略，三条断言全部变成空断言）。
 *
 * <p>为什么价目表必须隔离：它写着这个租户的**定价币种、适用法人 / 店铺 / 站点与生效区间**。
 * A 读到 B 的价目表 ⇒ A 的定价口径与覆盖范围直接串味，而 B 的生效区间会让 A 在
 * 同一时刻按两套价格出单。
 */
@SpringBootTest
@AutoConfigureMockMvc
class PriceListTenancyIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  static HttpServer channel;
  /**
   * 测试用的 JSON 序列化器。
   *
   * <p>🔴 必须关掉 {@code WRITE_DATES_AS_TIMESTAMPS}：默认的 ObjectMapper 会把
   * {@link java.time.OffsetDateTime} 写成**数字**（1790812800.000000000），
   * 而服务端（Spring Boot 默认 write-dates-as-timestamps=false）只认 ISO 字符串 ——
   * 那样 POST 会被 400 掉，而原因藏在序列化器里，看不出是日期形状的问题。
   */
  static final ObjectMapper JSON =
      new ObjectMapper()
          .findAndRegisterModules()
          .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
  static final Map<String, AccessService.Access> actors =
      new java.util.concurrent.ConcurrentHashMap<>();
  static final AtomicInteger SEQ = new AtomicInteger();

  static AccessService.Access actor(
      UUID uid, UUID tenant, List<AccessService.Grant> grants) {
    return new AccessService.Access(
        uid,
        tenant,
        "Test",
        UUID.randomUUID(),
        null,
        grants.stream().flatMap(g -> g.permissions().stream()).distinct().toList(),
        grants);
  }

  static List<String> allPermissions() {
    return java.util.stream.Stream.of("spu", "sku", "attribute", "research", "compliance", "assets")
        .flatMap(
            p ->
                java.util.stream.Stream.of("read", "create", "update", "delete")
                    .map(op -> "catalog." + p + "." + op))
        .toList();
  }

  static {
    pg.start();
    try (var conn =
            DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = conn.createStatement()) {
      s.execute("create role starcon_app nologin");
      s.execute("create role catalog_app login password 'test-only' nosuperuser nobypassrls");
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
      identity.createContext(
          "/identity",
          e -> {
            var entity = new java.util.LinkedHashMap<String, Object>();
            entity.put("id", UUID.randomUUID().toString());
            entity.put("code", "US-HQ");
            entity.put("legalName", "美国总部");
            entity.put("baseCurrency", "USD");
            entity.put("status", "ACTIVE");
            byte[] body = JSON.writeValueAsBytes(List.of(entity));
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      startChannelStub();
      actors.put(
          "Bearer tenant-a",
          actor(user, a, List.of(new AccessService.Grant(UUID.randomUUID(), "TEST", "ALL", allPermissions()))));
      actors.put(
          "Bearer tenant-b",
          actor(user, b, List.of(new AccessService.Grant(UUID.randomUUID(), "TEST", "ALL", allPermissions()))));
      identity.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  static void startChannelStub() throws Exception {
    channel = HttpServer.create(new InetSocketAddress(0), 0);
    channel.createContext(
        "/channel",
        e -> {
          var path = e.getRequestURI().getPath();
          Object body;
          if (path.equals("/channel/shops")) {
            var shop = new java.util.LinkedHashMap<String, Object>();
            shop.put("id", UUID.randomUUID().toString());
            shop.put("platformCode", "AMAZON");
            shop.put("legalEntityId", null);
            shop.put("code", "AMZ-US");
            shop.put("name", "亚马逊美国自发货店");
            shop.put("status", "ACTIVE");
            shop.put("marketplaces", List.of());
            body = List.of(shop);
          } else {
            var marketplace = new java.util.LinkedHashMap<String, Object>();
            marketplace.put("id", UUID.randomUUID().toString());
            marketplace.put("code", "US");
            marketplace.put("name", "美国站");
            marketplace.put("countryCode", "US");
            marketplace.put("defaultCurrency", "USD");
            marketplace.put("currencyScale", 2);
            marketplace.put("vatRequired", false);
            var platform = new java.util.LinkedHashMap<String, Object>();
            platform.put("id", UUID.randomUUID().toString());
            platform.put("code", "AMAZON");
            platform.put("name", "Amazon");
            platform.put("marketplaces", List.of(marketplace));
            body = List.of(platform);
          }
          byte[] out = JSON.writeValueAsBytes(body);
          e.getResponseHeaders().add("Content-Type", "application/json");
          e.sendResponseHeaders(200, out.length);
          e.getResponseBody().write(out);
          e.close();
        });
    channel.start();
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "catalog_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://localhost:" + identity.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
    r.add("CHANNEL_URL", () -> "http://localhost:" + channel.getAddress().getPort());
    // 单连接：只有复用同一条连接，才能真正证明归还之后不会串租户。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  @AfterAll
  static void stop() {
    channel.stop(0);
    identity.stop(0);
    pg.stop();
  }

  @Autowired PriceListService priceLists;
  @Autowired AccessService access;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  AccessService.Access actorA;
  AccessService.Access actorB;

  @BeforeEach
  void setUp() {
    actorA = actors.get("Bearer tenant-a");
    actorB = actors.get("Bearer tenant-b");
  }

  // ================================================================ 夹具

  <T> T tx(AccessService.Access who, java.util.function.Supplier<T> work) {
    return access.run(who, "catalog.spu.read", work);
  }

  <T> T write(AccessService.Access who, java.util.function.Supplier<T> work) {
    return access.run(who, "catalog.spu.update", work);
  }

  static String unique(String prefix) {
    return prefix + "-" + SEQ.incrementAndGet();
  }

  static String bearer(AccessService.Access who) {
    return "Bearer " + (who.tenantId().equals(a) ? "tenant-a" : "tenant-b");
  }

  private PriceListInput input(String code) {
    return new PriceListInput(
        code,
        "价目表 " + code,
        "夹具",
        "USD",
        OffsetDateTime.now(ZoneOffset.UTC).minusDays(1),
        null,
        Boolean.TRUE,
        "",
        List.of(),
        null);
  }

  private PriceListDetail save(AccessService.Access who, String code) {
    var in = input(code);
    return write(who, () -> priceLists.save(who, null, in, bearer(who)));
  }

  // ================================================================ 断言一：两租户互不可见

  @Test
  void 两租户互不可见() {
    var mine = save(actorA, unique("PL-A"));
    var theirs = save(actorB, unique("PL-B"));

    // 两边都真的有数据 —— 否则下面的断言就是空的。
    assertThat(tx(actorA, () -> priceLists.detail(mine.summary().id()))).isNotNull();
    assertThat(tx(actorB, () -> priceLists.detail(theirs.summary().id()))).isNotNull();

    // 列表里混不进对方的价目表。
    var myRows = tx(actorA, () -> priceLists.list(new PriceListService.PriceListQuery(null, null, null, null, null, null, 0, 200)));
    assertThat(myRows.items().stream().map(PriceListSummary::id)).contains(mine.summary().id());
    assertThat(myRows.items().stream().map(PriceListSummary::id)).doesNotContain(theirs.summary().id());

    // 明细读不到对方的价目表。
    assertThatThrownBy(() -> tx(actorA, () -> priceLists.detail(theirs.summary().id())))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> tx(actorB, () -> priceLists.detail(mine.summary().id())))
        .isInstanceOf(ResponseStatusException.class);

    // 直接查库也读不到对方的那一行（RLS 兜底，不只是服务层过滤）。
    assertThat(
            tx(
                actorA,
                () ->
                    sql.fetchOne(
                            "select count(*) from price_list where id=?", theirs.summary().id())
                        .get(0, Long.class)))
        .isZero();
    assertThat(
            tx(
                actorB,
                () ->
                    sql.fetchOne(
                            "select count(*) from price_list where id=?", mine.summary().id())
                        .get(0, Long.class)))
        .isZero();

    // 覆盖范围摘要也只看得到自己租户的价目表。
    var myCoverage = tx(actorA, () -> priceLists.coverage());
    assertThat(myCoverage.lists().stream().map(CoverageEntry::id)).contains(mine.summary().id());
    assertThat(myCoverage.lists().stream().map(CoverageEntry::id)).doesNotContain(theirs.summary().id());
  }

  /** 写路径也隔离：A 删不掉 B 的价目表。 */
  @Test
  void 写路径也隔离() {
    var theirs = save(actorB, unique("PL-B"));
    assertThatThrownBy(
            () ->
                write(
                    actorA,
                    () -> {
                      priceLists.delete(theirs.summary().id());
                      return null;
                    }))
        .isInstanceOf(ResponseStatusException.class);
    // B 的那一张还在。
    assertThat(tx(actorB, () -> priceLists.detail(theirs.summary().id()))).isNotNull();
  }

  // ================================================================ 断言二：伪造 tenant header 无效

  @Test
  void 伪造租户头无效() throws Exception {
    var theirs = save(actorB, unique("PL-B"));
    var code = unique("PL-FORGED");
    var payload =
        JSON.writeValueAsBytes(
            new PriceListInput(
                code,
                "伪造头写入的价目表",
                "",
                "USD",
                OffsetDateTime.parse("2026-10-01T00:00:00Z"),
                null,
                Boolean.TRUE,
                "",
                List.of(),
                null));

    // 带伪造的 X-Tenant-Id 头写入：行必须落在**调用者自己**的名下（B 的租户下读不到）。
    http.perform(
            post("/catalog/price-lists/lists")
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b.toString())
                .contentType("application/json")
                .content(payload))
        .andExpect(status().isOk());

    // 伪造头下写出来的行，租户仍然是 A（identity 解析出来的那个）。
    assertThat(
            tx(
                actorA,
                () ->
                    sql.fetchOne(
                            "select count(*) from price_list where code=? and tenant_id=?",
                            code,
                            a)
                        .get(0, Long.class)))
        .isEqualTo(1L);
    assertThat(
            tx(
                actorB,
                () ->
                    sql.fetchOne(
                            "select count(*) from price_list where code=?", code)
                        .get(0, Long.class)))
        .isZero();

    // 带伪造头读别人的价目表 ⇒ 读不到。
    http.perform(
            get("/catalog/price-lists/lists/" + theirs.summary().id())
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isNotFound());
  }

  // ================================================================ 断言三：连接池归还后不串租户

  @Test
  void 连接池归还后不串租户() {
    var mine = save(actorA, unique("PL-A"));
    var theirs = save(actorB, unique("PL-B"));
    // 交替 5 轮：每一次读都必须只看到当前租户的行。
    for (int round = 0; round < 5; round++) {
      var aRows = tx(actorA, () -> priceLists.list(new PriceListService.PriceListQuery(null, null, null, null, null, null, 0, 200)));
      assertThat(aRows.items().stream().map(PriceListSummary::id)).contains(mine.summary().id());
      assertThat(aRows.items().stream().map(PriceListSummary::id)).doesNotContain(theirs.summary().id());

      var bRows = tx(actorB, () -> priceLists.list(new PriceListService.PriceListQuery(null, null, null, null, null, null, 0, 200)));
      assertThat(bRows.items().stream().map(PriceListSummary::id)).contains(theirs.summary().id());
      assertThat(bRows.items().stream().map(PriceListSummary::id)).doesNotContain(mine.summary().id());
    }
  }

  // ================================================================ 断言四：FORCE RLS

  @Test
  void 价目表强制开启行级安全() {
    for (var table : List.of("price_list", "price_list_scope")) {
      var row =
          tx(
              actorA,
              () ->
                  sql.fetchOne(
                      "select relforcerowsecurity, relrowsecurity from pg_class where relname=?",
                      table));
      assertThat(row).as("表 %s 必须存在", table).isNotNull();
      assertThat(row.get("relrowsecurity", Boolean.class)).as("%s 必须 ENABLE RLS", table).isTrue();
      assertThat(row.get("relforcerowsecurity", Boolean.class))
          .as("%s 必须 FORCE RLS（漏掉 FORCE 就能绕过策略读别人的价目表）", table)
          .isTrue();
    }
  }
}
