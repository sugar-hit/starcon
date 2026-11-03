package com.starcon.catalog;

import static com.starcon.catalog.PriceListModels.*;
import static com.starcon.catalog.PriceListPriorityModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
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
 * 5.1.2.1 优先级规则的**多租户三断言**（AGENTS.md 明文要求，缺一不可）。
 *
 * <p>跑的是这三条：
 *
 * <ol>
 *   <li><b>两租户互不可见</b>：A 看不到 B 的优先级规则；<b>冲突检测也不会把 B 的价目表算进来</b>
 *       （两个租户各有一张同店铺同优先级的表，在各自租户里都**不是**冲突）。
 *   <li><b>伪造 tenant header 无效</b>：请求头里的 {@code X-Tenant-Id} 改不了任何事 ——
 *       租户只来自已认证成员关系（identity），伪造头下写入的行仍然落在调用者自己名下。
 *   <li><b>连接池归还后不串租户</b>：连接池压到 1 条连接，A→B→A→B 交替 5 轮，
 *       每一轮读到的都只有当前租户的数据。
 * </ol>
 *
 * <p>另加一条：<b>FORCE RLS 而不只是 ENABLE</b> —— 漏掉 FORCE 之后表 owner（迁移角色）
 * 仍能绕过策略读别人的优先级规则，而优先级规则写着一个租户「哪张价目表压过哪张价目表」——
 * 那是一条看不见的越权面。
 *
 * <p><b>真实 PostgreSQL 16 + FORCE RLS，不使用任何内存替身</b>；运行角色
 * {@code catalog_app} 是 {@code nobypassrls}（否则它会绕过策略，三条断言全部变成空断言）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class PriceListPriorityTenancyIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static final UUID LEGAL_ENTITY_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
  static HttpServer identity;
  static HttpServer channel;

  static final ObjectMapper JSON =
      new ObjectMapper()
          .findAndRegisterModules()
          .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
  static final Map<String, AccessService.Access> actors =
      new java.util.concurrent.ConcurrentHashMap<>();
  static final AtomicInteger SEQ = new AtomicInteger();

  static final String LEGAL_ENTITY_CODE = "US-HQ";
  static final String PLATFORM_CODE = "AMAZON";

  /** channel 替身服务的店铺目录：**动态** —— 每个用例登记一个只属于它的店铺。 */
  static final List<Map<String, Object>> SHOPS = new CopyOnWriteArrayList<>();

  static final AtomicInteger SHOP_SEQ = new AtomicInteger();

  /** 一个用例专属的店铺（店铺编码 + 它名下的站点码）。 */
  record ShopFixture(String code, String siteCode) {}

  /** 登记一个只属于当前用例的店铺。 */
  static ShopFixture newShop() {
    var n = SHOP_SEQ.incrementAndGet();
    var code = "AMZ-T" + n;
    var site = "SITE-T" + n;
    SHOPS.add(shop(code, "测试店铺 " + code, site));
    return new ShopFixture(code, site);
  }

  /** 一个店铺（带它名下的站点与所属法人）。 */
  static LinkedHashMap<String, Object> shop(String code, String name, String siteCode) {
    var marketplace = new LinkedHashMap<String, Object>();
    marketplace.put("marketplaceCode", siteCode);
    marketplace.put("status", "ACTIVE");
    var shop = new LinkedHashMap<String, Object>();
    shop.put("id", UUID.randomUUID().toString());
    shop.put("platformCode", PLATFORM_CODE);
    shop.put("legalEntityId", LEGAL_ENTITY_ID.toString());
    shop.put("code", code);
    shop.put("name", name);
    shop.put("status", "ACTIVE");
    shop.put("marketplaces", List.of(marketplace));
    return shop;
  }

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
            var entity = new LinkedHashMap<String, Object>();
            entity.put("id", LEGAL_ENTITY_ID.toString());
            entity.put("code", LEGAL_ENTITY_CODE);
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
            body = List.copyOf(SHOPS);
          } else {
            var marketplace = new LinkedHashMap<String, Object>();
            marketplace.put("id", UUID.randomUUID().toString());
            marketplace.put("code", "US");
            marketplace.put("name", "美国站");
            marketplace.put("countryCode", "US");
            marketplace.put("defaultCurrency", "USD");
            marketplace.put("currencyScale", 2);
            marketplace.put("vatRequired", false);
            var platform = new LinkedHashMap<String, Object>();
            platform.put("id", UUID.randomUUID().toString());
            platform.put("code", PLATFORM_CODE);
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
  @Autowired PriceListPriorityService priorities;
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

  private PriceListDetail save(AccessService.Access who, String code, String shopCode) {
    var input =
        new PriceListInput(
            code,
            "价目表 " + code,
            "夹具",
            "USD",
            OffsetDateTime.now(ZoneOffset.UTC).minusDays(1),
            null,
            Boolean.TRUE,
            "",
            List.of(new ScopeInput(ScopeType.SHOP, shopCode, PLATFORM_CODE)),
            null);
    return write(who, () -> priceLists.save(who, null, input, bearer(who)));
  }

  private PriorityRulesSaved saveRules(AccessService.Access who, UUID listId, int priority) {
    return write(
        who,
        () ->
            priorities.saveRules(
                who,
                listId,
                new PriorityRulesInput(
                    List.of(new PriorityRuleInput(PriorityScopeType.GLOBAL, "*", priority, ""))),
                bearer(who)));
  }

  // ================================================================ 断言一：两租户互不可见

  @Test
  void 两租户互不可见() {
    var shop = newShop();
    var mine = save(actorA, unique("PL-A"), shop.code());
    var theirs = save(actorB, unique("PL-B"), shop.code());
    saveRules(actorA, mine.summary().id(), 10);
    saveRules(actorB, theirs.summary().id(), 10);

    // 规则列表里混不进对方的规则。
    var myRules = tx(actorA, () -> priorities.allRules());
    assertThat(myRules.stream().map(PriorityRuleView::priceListId)).contains(mine.summary().id());
    assertThat(myRules.stream().map(PriorityRuleView::priceListId)).doesNotContain(theirs.summary().id());

    // 读对方的规则 ⇒ 读不到。
    assertThatThrownBy(() -> tx(actorA, () -> priorities.rules(theirs.summary().id())))
        .isInstanceOf(ResponseStatusException.class);

    // 🔴 冲突检测也必须是租户内的：A 与 B 各有一张同店铺、同优先级的表，
    //    在各自租户里都**不是**冲突（跨租户撞车不算撞车）。
    var myConflicts = tx(actorA, () -> priorities.conflicts(mine.summary().id(), bearer(actorA)));
    assertThat(myConflicts.conflictCount()).isZero();
    assertThat(myConflicts.blockingCount()).isZero();
    var theirConflicts = tx(actorB, () -> priorities.conflicts(theirs.summary().id(), bearer(actorB)));
    assertThat(theirConflicts.conflictCount()).isZero();

    // 直接查库也读不到对方的那一行（RLS 兜底，不只是服务层过滤）。
    assertThat(
            tx(
                actorA,
                () ->
                    sql.fetchOne(
                            "select count(*) from price_list_priority where price_list_id=?",
                            theirs.summary().id())
                        .get(0, Long.class)))
        .isZero();
  }

  /** 写路径也隔离：A 改不了 B 的优先级规则。 */
  @Test
  void 写路径也隔离() {
    var shop = newShop();
    var theirs = save(actorB, unique("PL-B"), shop.code());
    saveRules(actorB, theirs.summary().id(), 10);
    assertThatThrownBy(() -> saveRules(actorA, theirs.summary().id(), 20))
        .isInstanceOf(ResponseStatusException.class);
    // B 的那条规则没被动过。
    var rules = tx(actorB, () -> priorities.rules(theirs.summary().id()));
    assertThat(rules).hasSize(1);
    assertThat(rules.get(0).priority()).isEqualTo(10);
  }

  // ================================================================ 断言二：伪造 tenant header 无效

  @Test
  void 伪造租户头无效() throws Exception {
    var shop = newShop();
    var theirs = save(actorB, unique("PL-B"), shop.code());
    saveRules(actorB, theirs.summary().id(), 10);

    var listId = UUID.randomUUID();
    var payload =
        JSON.writeValueAsBytes(
            new PriorityRulesInput(
                List.of(new PriorityRuleInput(PriorityScopeType.GLOBAL, "*", 3, "伪造头写入"))));

    // 带伪造的 X-Tenant-Id 头写入：行必须落在**调用者自己**的名下。
    http.perform(
            post("/catalog/price-lists/lists/" + listId + "/priority")
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b.toString())
                .contentType("application/json")
                .content(payload))
        .andExpect(status().isNotFound());

    // A 自己的那张表上，伪造头写出来的规则仍落在 A 名下（B 读不到）。
    var mine = save(actorA, unique("PL-A"), shop.code());
    http.perform(
            post("/catalog/price-lists/lists/" + mine.summary().id() + "/priority")
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b.toString())
                .contentType("application/json")
                .content(payload))
        .andExpect(status().isOk());
    assertThat(
            tx(
                actorA,
                () ->
                    sql.fetchOne(
                            "select count(*) from price_list_priority where price_list_id=? and tenant_id=?",
                            mine.summary().id(),
                            a)
                        .get(0, Long.class)))
        .isEqualTo(1L);
    assertThat(
            tx(
                actorB,
                () ->
                    sql.fetchOne(
                            "select count(*) from price_list_priority where price_list_id=?",
                            mine.summary().id())
                        .get(0, Long.class)))
        .isZero();
  }

  // ================================================================ 断言三：连接池归还后不串租户

  @Test
  void 连接池归还后不串租户() {
    var shop = newShop();
    var mine = save(actorA, unique("PL-A"), shop.code());
    var theirs = save(actorB, unique("PL-B"), shop.code());
    saveRules(actorA, mine.summary().id(), 10);
    saveRules(actorB, theirs.summary().id(), 10);
    // 交替 5 轮：每一次读都必须只看到当前租户的行。
    for (int round = 0; round < 5; round++) {
      var myRules = tx(actorA, () -> priorities.allRules());
      assertThat(myRules.stream().map(PriorityRuleView::priceListId)).contains(mine.summary().id());
      assertThat(myRules.stream().map(PriorityRuleView::priceListId)).doesNotContain(theirs.summary().id());

      var theirRules = tx(actorB, () -> priorities.allRules());
      assertThat(theirRules.stream().map(PriorityRuleView::priceListId)).contains(theirs.summary().id());
      assertThat(theirRules.stream().map(PriorityRuleView::priceListId)).doesNotContain(mine.summary().id());
    }
  }

  // ================================================================ 断言四：FORCE RLS

  @Test
  void 优先级规则强制开启行级安全() {
    var row =
        tx(
            actorA,
            () ->
                sql.fetchOne(
                    "select relforcerowsecurity, relrowsecurity from pg_class where relname=?",
                    "price_list_priority"));
    assertThat(row).isNotNull();
    assertThat(row.get("relrowsecurity", Boolean.class)).as("必须 ENABLE RLS").isTrue();
    assertThat(row.get("relforcerowsecurity", Boolean.class))
        .as("必须 FORCE RLS（漏掉 FORCE 就能绕过策略读别人的优先级规则）")
        .isTrue();
  }
}
