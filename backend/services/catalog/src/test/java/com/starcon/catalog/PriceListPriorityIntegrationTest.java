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
 * 5.1.2.1 优先级与冲突检测：真实 PostgreSQL 16 + 强制 RLS 下的优先级规则、
 * 四个维度的冲突检测、冲突即拒绝、以及唯一解析规则。
 *
 * <p><b>真实 PostgreSQL 16 + 强制 RLS</b>：优先级规则真的落库，冲突真的由数据库谓词判。
 * 连接池固定为 1 条连接：只有复用同一条连接，才能真正证明归还后不会串租户。
 *
 * <p><b>跨服务依赖的处理</b>：法人来自 identity、店铺与站点来自 channel（另外两个库）。
 * 生产路径是真实的 {@code RestClient} HTTP 调用，本测试用两个 {@link HttpServer}
 * 起确定性替身来满足它们 —— 替身只存在于测试代码里，所以它证明的是
 * <b>生产代码真的走 HTTP 且透传了租户上下文</b>。
 *
 * <p><b>🔴 每个用例一个自己的店铺</b>：同一个测试类共用一个数据库，各用例建的价目表
 * 会一起留在库里。如果它们都挂在同一个店铺下，前一个用例留下的价目表就会把后一个用例
 * 撞成冲突（那不是被测行为，是测试之间的脏数据）。所以店铺目录是**动态**的，
 * 每个用例登记一个只属于它的店铺。
 *
 * <p><b>本文件最重要的九个断言</b>：
 *
 * <ol>
 *   <li>{@link #优先级规则真的落库()}：规则写进去读得出来，且 GLOBAL 与上下文规则都在。
 *   <li>{@link #缺少全局规则时保存被拒()}：没有 GLOBAL 的价目表会「在某些上下文里没有优先级」
 *       ⇒ 服务层直接拒（400），不是收下再说。
 *   <li>{@link #同优先级并列时保存被拒并列出冲突明细()}：409 + **结构化明细**
 *       （哪两张表、哪个店铺、哪个站点、区间重叠在哪、两边各是什么优先级）。
 *   <li>{@link #不同优先级不阻断但仍要报出来()}：可解析的冲突**也要报**（不阻断 ≠ 不报）。
 *   <li>{@link #生效区间首尾相接不算冲突()}：半开区间 [起点, 终点) ⇒ 不重叠。
 *   <li>{@link #写适用范围撞上并列时整笔保存回滚()}：守门员拦的是**写适用范围**那条路。
 *   <li>{@link #解析唯一命中并给出依据()}：返回一条确定的价目表 + 命中了哪条优先级规则。
 *   <li>{@link #同优先级并列时解析不可解析()}：409 + 并列候选，**不随机取一条**。
 *   <li>{@link #同sku维度未覆盖如实声明()}：「同 SKU」这一维度**如实说没覆盖**，
 *       而不是静默返回「无冲突」。
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class PriceListPriorityIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();

  /** 法人 id 固定下来：店铺要通过它挂到法人上（冲突检测的「同法人」维度要用）。 */
  static final UUID LEGAL_ENTITY_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

  static HttpServer identity;
  static HttpServer channel;

  static final ObjectMapper JSON =
      new ObjectMapper()
          .findAndRegisterModules()
          .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
  static final Map<String, AccessService.Access> actors =
      new java.util.concurrent.ConcurrentHashMap<>();
  static final AtomicInteger SEQ = new AtomicInteger();

  /** channel 替身服务的店铺目录：**动态** —— 每个用例登记一个只属于它的店铺。 */
  static final List<Map<String, Object>> SHOPS = new CopyOnWriteArrayList<>();

  static final AtomicInteger SHOP_SEQ = new AtomicInteger();

  static final String LEGAL_ENTITY_CODE = "US-HQ";
  static final String PLATFORM_CODE = "AMAZON";

  /** 一个用例专属的店铺（店铺编码 + 它名下的站点码）。 */
  record ShopFixture(String code, String siteCode) {}

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

  static AccessService.Grant grant(String scope, List<String> permissions) {
    return new AccessService.Grant(UUID.randomUUID(), "TEST", scope, permissions);
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
      actors.put("Bearer tenant-a", actor(user, a, List.of(grant("ALL", allPermissions()))));
      actors.put("Bearer tenant-b", actor(user, b, List.of(grant("ALL", allPermissions()))));
      identity.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  /** channel 替身：平台目录（2.1.1.1）与店铺列表（2.3.1.1）。店铺目录是动态的。 */
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
            var platform = new LinkedHashMap<String, Object>();
            platform.put("id", UUID.randomUUID().toString());
            platform.put("code", PLATFORM_CODE);
            platform.put("name", "Amazon");
            platform.put("marketplaces", List.of(market("US", "美国站")));
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

  /** 一个站点（平台目录里的 marketplace）。 */
  static LinkedHashMap<String, Object> market(String code, String name) {
    var marketplace = new LinkedHashMap<String, Object>();
    marketplace.put("id", UUID.randomUUID().toString());
    marketplace.put("code", code);
    marketplace.put("name", name);
    marketplace.put("countryCode", "US");
    marketplace.put("defaultCurrency", "USD");
    marketplace.put("currencyScale", 2);
    marketplace.put("vatRequired", false);
    return marketplace;
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

  /** 建一张价目表（默认覆盖整个店铺，区间长期有效）。 */
  private PriceListDetail saveList(String code, String shopCode) {
    var from = OffsetDateTime.now(ZoneOffset.UTC).minusDays(1);
    return saveList(code, shopCode, from, null);
  }

  private PriceListDetail saveList(
      String code, String shopCode, OffsetDateTime from, OffsetDateTime to) {
    var input =
        new PriceListInput(
            code,
            "价目表 " + code,
            "夹具价目表",
            "USD",
            from,
            to,
            Boolean.TRUE,
            "夹具",
            List.of(new ScopeInput(ScopeType.SHOP, shopCode, PLATFORM_CODE)),
            null);
    return write(actorA, () -> priceLists.save(actorA, null, input, bearer(actorA)));
  }

  /** 一条优先级规则。 */
  private PriorityRuleInput rule(PriorityScopeType type, String ref, int priority) {
    return new PriorityRuleInput(type, ref, priority, "");
  }

  private PriorityRuleInput global(int priority) {
    return rule(PriorityScopeType.GLOBAL, "*", priority);
  }

  /** 保存优先级规则（走服务层 ⇒ 会真的跑冲突检测）。 */
  private PriorityRulesSaved saveRules(UUID listId, List<PriorityRuleInput> rules) {
    return write(
        actorA,
        () ->
            priorities.saveRules(
                actorA, listId, new PriorityRulesInput(rules), bearer(actorA)));
  }

  /** 该价目表的冲突报告。 */
  private ConflictReport conflicts(UUID listId) {
    return tx(actorA, () -> priorities.conflicts(listId, bearer(actorA)));
  }

  // ================================================================ 规则读写

  @Test
  void 优先级规则真的落库() {
    var shop = newShop();
    var list = saveList(unique("PL"), shop.code());
    var saved =
        saveRules(
            list.summary().id(),
            List.of(global(10), rule(PriorityScopeType.SHOP, shop.code(), 5)));

    assertThat(saved.rules()).hasSize(2);
    var global = saved.rules().get(0);
    assertThat(global.scopeType()).isEqualTo(PriorityScopeType.GLOBAL);
    assertThat(global.scopeRef()).isEqualTo("*");
    assertThat(global.priority()).isEqualTo(10);
    assertThat(global.priceListCode()).isEqualTo(list.summary().code());

    // 直接查库：规则真的在表里。
    var rows =
        tx(
            actorA,
            () ->
                sql.fetch(
                    "select scope_type, scope_ref, priority from price_list_priority"
                        + " where price_list_id=? order by scope_type",
                    list.summary().id()));
    assertThat(rows).hasSize(2);
    assertThat(rows.get(0).get("scope_type", String.class)).isEqualTo("GLOBAL");
    assertThat(rows.get(1).get("scope_ref", String.class)).isEqualTo(shop.code());
    assertThat(rows.get(1).get("priority", Integer.class)).isEqualTo(5);

    // 整体替换：再存一次只剩一条。
    var replaced = saveRules(list.summary().id(), List.of(global(3)));
    assertThat(replaced.rules()).hasSize(1);
    assertThat(replaced.rules().get(0).priority()).isEqualTo(3);
  }

  @Test
  void 缺少全局规则时保存被拒() {
    var shop = newShop();
    var list = saveList(unique("PL"), shop.code());
    assertThatThrownBy(
            () ->
                saveRules(
                    list.summary().id(), List.of(rule(PriorityScopeType.SHOP, shop.code(), 5))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("GLOBAL");
    // 一条都没留下。
    assertThat(
            tx(
                actorA,
                () ->
                    sql.fetchOne(
                            "select count(*) from price_list_priority where price_list_id=?",
                            list.summary().id())
                        .get(0, Long.class)))
        .isZero();
  }

  @Test
  void 同一上下文重复登记被拒() {
    var shop = newShop();
    var list = saveList(unique("PL"), shop.code());
    assertThatThrownBy(
            () ->
                saveRules(
                    list.summary().id(),
                    List.of(
                        global(1),
                        rule(PriorityScopeType.SHOP, shop.code(), 5),
                        rule(PriorityScopeType.SHOP, shop.code(), 6))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("重复");
  }

  @Test
  void 规则引用不存在的价目表被数据库挡住() {
    // 触发器兜底：绕过服务层直接写库 ⇒ 报 23514（不是「写进去了但没人管」）。
    assertThatThrownBy(
            () ->
                tx(
                    actorA,
                    () -> {
                      sql.execute(
                          "insert into price_list_priority(id,tenant_id,price_list_id,scope_type,"
                              + "scope_ref,priority,note,created_by)"
                              + " values(?::uuid,?::uuid,?::uuid,'GLOBAL','*',1,'',?::uuid)",
                          UUID.randomUUID(),
                          a,
                          UUID.randomUUID(),
                          user);
                      return null;
                    }))
        .isInstanceOf(org.springframework.dao.DataAccessException.class)
        .hasMessageContaining("must reference an existing price list");
  }

  // ================================================================ 冲突检测

  @Test
  void 同优先级并列时保存被拒并列出冲突明细() {
    var shop = newShop();
    var first = saveList(unique("PL-A"), shop.code());
    var second = saveList(unique("PL-B"), shop.code());
    saveRules(first.summary().id(), List.of(global(10)));

    var thrown = catchThrowable(() -> saveRules(second.summary().id(), List.of(global(10))));
    assertThat(thrown).isInstanceOf(PriceListPriorityConflictException.class);
    var report = ((PriceListPriorityConflictException) thrown).report();
    assertThat(report.blockingCount()).isPositive();

    var detail = report.conflicts().get(0);
    // 哪两张表。
    assertThat(detail.leftPriceListCode() + detail.rightPriceListCode())
        .contains(first.summary().code())
        .contains(second.summary().code());
    // 哪个店铺、哪个站点。
    assertThat(detail.shopCode()).isEqualTo(shop.code());
    assertThat(detail.siteCode()).isEqualTo(shop.siteCode());
    // 区间重叠在哪。
    assertThat(detail.overlapFrom()).isNotNull();
    // 两边各是什么优先级。
    assertThat(detail.leftPriority()).isEqualTo(10);
    assertThat(detail.rightPriority()).isEqualTo(10);
    // 四个维度逐条独立可解释。
    assertThat(detail.dimensions())
        .contains(
            ConflictDimension.SHOP,
            ConflictDimension.SITE,
            ConflictDimension.TIME,
            ConflictDimension.PRIORITY);
    assertThat(detail.blocking()).isTrue();

    // 🔴 冲突即拒绝 ⇒ 什么都没保存：第二条的规则一条都没留下。
    assertThat(
            tx(
                actorA,
                () ->
                    sql.fetchOne(
                            "select count(*) from price_list_priority where price_list_id=?",
                            second.summary().id())
                        .get(0, Long.class)))
        .isZero();
  }

  @Test
  void 不同优先级不阻断但仍要报出来() {
    var shop = newShop();
    var first = saveList(unique("PL-A"), shop.code());
    var second = saveList(unique("PL-B"), shop.code());
    saveRules(first.summary().id(), List.of(global(10)));

    var saved = saveRules(second.summary().id(), List.of(global(20)));
    assertThat(saved.rules()).hasSize(1);
    assertThat(saved.report().blockingCount()).isZero();

    // 可解析的冲突**也要报**（不阻断 ≠ 不报）。
    var report = conflicts(first.summary().id());
    assertThat(report.conflictCount()).isPositive();
    assertThat(report.blockingCount()).isZero();
    var detail = report.conflicts().get(0);
    assertThat(detail.reason()).contains("优先级不同");
    // 配对的左右顺序由主键大小决定 ⇒ 断言「两边分别是 10 与 20」而不是钉死左右。
    assertThat(java.util.Set.of(detail.leftPriority(), detail.rightPriority()))
        .containsExactlyInAnyOrder(10, 20);
  }

  @Test
  void 生效区间首尾相接不算冲突() {
    var shop = newShop();
    // 半开区间 [起点, 终点)：A 到 11 月 1 日止、B 从 11 月 1 日起 ⇒ 两者不重叠。
    var boundary = OffsetDateTime.parse("2026-11-01T00:00:00Z");
    var first = saveList(unique("PL-A"), shop.code(), boundary.minusMonths(1), boundary);
    var second = saveList(unique("PL-B"), shop.code(), boundary, null);
    saveRules(first.summary().id(), List.of(global(10)));
    var saved = saveRules(second.summary().id(), List.of(global(10)));

    assertThat(saved.report().blockingCount()).isZero();
    assertThat(saved.report().conflictCount()).isZero();
  }

  @Test
  void 生效区间重叠时给出精确的重叠区间() {
    var shop = newShop();
    var from = OffsetDateTime.parse("2026-10-01T09:00:00+08:00");
    var to = OffsetDateTime.parse("2026-12-01T09:00:00+08:00");
    var first = saveList(unique("PL-A"), shop.code(), from, to);
    var second = saveList(unique("PL-B"), shop.code(), from.minusDays(1), to.plusDays(1));
    saveRules(first.summary().id(), List.of(global(10)));

    var thrown = catchThrowable(() -> saveRules(second.summary().id(), List.of(global(10))));
    assertThat(thrown).isInstanceOf(PriceListPriorityConflictException.class);
    var detail = ((PriceListPriorityConflictException) thrown).report().conflicts().get(0);
    // 重叠区间 = 两张表区间的交集 ⇒ 逐字相等（不被静默偏成别的瞬间）。
    assertThat(detail.overlapFrom().toInstant()).isEqualTo(from.toInstant());
    assertThat(detail.overlapTo().toInstant()).isEqualTo(to.toInstant());
    // timestamptz 只保时刻、不保偏移量：重叠区间的偏移量取决于读取时的会话/JVM
    // 时区（本地 +08:00、CI 的 UTC runner 上是 Z），不属于可断言的性质。
    // 要证的是「重叠区间没被静默偏掉」——换算回 +08:00 后墙钟必须一致。
    assertThat(detail.overlapFrom().withOffsetSameInstant(ZoneOffset.ofHours(8)).toLocalDateTime())
        .isEqualTo(from.toLocalDateTime());
  }

  @Test
  void 写适用范围撞上并列时整笔保存回滚() {
    var first = newShop();
    var second = newShop();
    // 两张表各管一个店铺，优先级相同 ⇒ 现在不撞。
    var firstList = saveList(unique("PL-A"), first.code());
    var secondList = saveList(unique("PL-B"), second.code());
    saveRules(firstList.summary().id(), List.of(global(10)));
    saveRules(secondList.summary().id(), List.of(global(10)));

    // 把第二张表的适用范围改成第一个店铺 ⇒ 撞上 ⇒ 拒绝保存。
    var input =
        new PriceListInput(
            secondList.summary().code(),
            secondList.summary().name(),
            "",
            "USD",
            OffsetDateTime.now(ZoneOffset.UTC).minusDays(1),
            null,
            Boolean.TRUE,
            "",
            List.of(new ScopeInput(ScopeType.SHOP, first.code(), PLATFORM_CODE)),
            secondList.version());
    assertThatThrownBy(
            () ->
                write(
                    actorA,
                    () -> priceLists.save(actorA, secondList.summary().id(), input, bearer(actorA))))
        .isInstanceOf(PriceListPriorityConflictException.class);

    // 🔴 整笔回滚：适用范围没被改掉（还在原来的店铺上）。
    var scopes =
        tx(
            actorA,
            () ->
                sql.fetch(
                    "select scope_ref from price_list_scope where price_list_id=?",
                    secondList.summary().id()));
    assertThat(scopes).hasSize(1);
    assertThat(scopes.get(0).get("scope_ref", String.class)).isEqualTo(second.code());
  }

  @Test
  void 未配置优先级的价目表不参与解析但仍会被报出来() {
    var shop = newShop();
    var ranked = saveList(unique("PL-R"), shop.code());
    saveList(unique("PL-U"), shop.code());
    saveRules(ranked.summary().id(), List.of(global(10)));

    var report = conflicts(ranked.summary().id());
    assertThat(report.conflictCount()).isPositive();
    assertThat(report.blockingCount()).isZero();
    assertThat(report.conflicts().get(0).reason()).contains("未配置优先级");
    // 报告里要能看出是哪一侧没排位（另一侧排了 10）。左右顺序由主键大小决定。
    var left = report.conflicts().get(0).leftPriority();
    var right = report.conflicts().get(0).rightPriority();
    assertThat(left == null || right == null).as("未配置优先级的一侧必须是空").isTrue();
    assertThat(left == null ? right : left).isEqualTo(10);
  }

  // ================================================================ 解析

  @Test
  void 解析唯一命中并给出依据() {
    var shop = newShop();
    var winner = saveList(unique("PL-WIN"), shop.code());
    var loser = saveList(unique("PL-LOSE"), shop.code());
    saveRules(winner.summary().id(), List.of(global(10)));
    saveRules(loser.summary().id(), List.of(global(20)));

    var resolution =
        tx(
            actorA,
            () ->
                priorities.resolve(
                    new ResolveRequest(shop.code(), shop.siteCode(), "SKU-1", null), bearer(actorA)));

    assertThat(resolution.resolved()).isTrue();
    assertThat(resolution.priceList().id()).isEqualTo(winner.summary().id());
    assertThat(resolution.priceList().currency()).isEqualTo("USD");
    // 解析依据：命中了哪条优先级规则。
    assertThat(resolution.basis()).isNotNull();
    assertThat(resolution.basis().priority()).isEqualTo(10);
    assertThat(resolution.basis().ruleScopeType()).isEqualTo(PriorityScopeType.GLOBAL);
    assertThat(resolution.basis().explanation()).contains("优先级最高");
    // 候选都在（可解释：为什么是它）。
    assertThat(resolution.candidates()).hasSize(2);
    assertThat(resolution.candidates().stream().filter(ResolutionCandidate::selected)).hasSize(1);
  }

  @Test
  void 上下文里更具体的规则优先于全局规则() {
    var shop = newShop();
    var list = saveList(unique("PL"), shop.code());
    saveRules(
        list.summary().id(), List.of(global(10), rule(PriorityScopeType.SHOP, shop.code(), 1)));

    var resolution =
        tx(
            actorA,
            () ->
                priorities.resolve(
                    new ResolveRequest(shop.code(), shop.siteCode(), null, null), bearer(actorA)));
    assertThat(resolution.resolved()).isTrue();
    assertThat(resolution.basis().priority()).isEqualTo(1);
    assertThat(resolution.basis().ruleScopeType()).isEqualTo(PriorityScopeType.SHOP);
    assertThat(resolution.basis().ruleScopeRef()).isEqualTo(shop.code());
  }

  @Test
  void 同优先级并列时解析不可解析() {
    var shop = newShop();
    var first = saveList(unique("PL-A"), shop.code());
    var second = saveList(unique("PL-B"), shop.code());
    // 直接写库绕过保存时的检测（正常路径会被 409 挡下），制造并列现场。
    write(
        actorA,
        () -> {
          sql.execute(
              "insert into price_list_priority(id,tenant_id,price_list_id,scope_type,scope_ref,"
                  + "priority,note,created_by) values(?::uuid,?::uuid,?::uuid,'GLOBAL','*',7,'',?::uuid)",
              UUID.randomUUID(),
              a,
              first.summary().id(),
              user);
          sql.execute(
              "insert into price_list_priority(id,tenant_id,price_list_id,scope_type,scope_ref,"
                  + "priority,note,created_by) values(?::uuid,?::uuid,?::uuid,'GLOBAL','*',7,'',?::uuid)",
              UUID.randomUUID(),
              a,
              second.summary().id(),
              user);
          return null;
        });

    assertThatThrownBy(
            () ->
                tx(
                    actorA,
                    () ->
                        priorities.resolve(
                            new ResolveRequest(shop.code(), shop.siteCode(), null, null),
                            bearer(actorA))))
        .isInstanceOf(PriceListPriorityAmbiguityException.class)
        .hasMessageContaining("不可解析");
  }

  @Test
  void 停用的价目表不参与解析() {
    var shop = newShop();
    var list = saveList(unique("PL"), shop.code());
    saveRules(list.summary().id(), List.of(global(10)));
    // 停用这张表。
    tx(
        actorA,
        () -> {
          sql.execute("update price_list set enabled=false where id=?", list.summary().id());
          return null;
        });

    var resolution =
        tx(
            actorA,
            () ->
                priorities.resolve(
                    new ResolveRequest(shop.code(), shop.siteCode(), null, null), bearer(actorA)));
    assertThat(resolution.resolved()).isFalse();
    assertThat(resolution.excluded()).anyMatch(e -> e.reason() == ExclusionReason.DISABLED);
  }

  @Test
  void 判定时刻由请求指定且逐字回显() {
    var shop = newShop();
    var list = saveList(unique("PL"), shop.code());
    saveRules(list.summary().id(), List.of(global(10)));
    var at = OffsetDateTime.parse("2026-10-01T09:00:00+08:00");

    var resolution =
        tx(
            actorA,
            () ->
                priorities.resolve(
                    new ResolveRequest(shop.code(), shop.siteCode(), null, at), bearer(actorA)));
    assertThat(resolution.at()).isEqualTo(at.toString());
    assertThat(resolution.shopCode()).isEqualTo(shop.code());
    assertThat(resolution.siteCode()).isEqualTo(shop.siteCode());
  }

  // ================================================================ 维度覆盖声明

  @Test
  void 同sku维度未覆盖如实声明() {
    var shop = newShop();
    var list = saveList(unique("PL"), shop.code());
    saveRules(list.summary().id(), List.of(global(10)));

    var report = conflicts(list.summary().id());
    var sku =
        report.dimensionCoverage().stream()
            .filter(c -> c.dimension() == ConflictDimension.SKU)
            .findFirst()
            .orElseThrow();
    assertThat(sku.covered()).isFalse();
    assertThat(sku.detail()).contains("5.1.1.2");
    assertThat(report.skuDimensionNote()).contains("price_list_item");

    // 解析结果里也要说清楚（不是只在冲突报告里说）。
    var resolution =
        tx(
            actorA,
            () ->
                priorities.resolve(
                    new ResolveRequest(shop.code(), shop.siteCode(), "SKU-9", null), bearer(actorA)));
    assertThat(resolution.skuCoverage().covered()).isFalse();
    assertThat(resolution.skuCoverage().sku()).isEqualTo("SKU-9");
    assertThat(resolution.skuCoverage().reason()).contains("5.1.1.2");
  }

  @Test
  void 能力声明列出六个维度与优先级语义() {
    var caps = tx(actorA, () -> priorities.capabilities());
    assertThat(caps.dimensionCoverage()).hasSize(ConflictDimension.values().length);
    assertThat(caps.prioritySemantics()).contains("数字越小");
    assertThat(caps.tieNote()).contains("不可唯一解析");
    assertThat(caps.skuNote()).contains("未覆盖");
  }

  // ================================================================ HTTP 形状

  @Test
  void 端点形状与状态码() throws Exception {
    var shop = newShop();
    var list = saveList(unique("PL"), shop.code());

    // 能力声明。
    http.perform(
            get("/catalog/price-lists/priority/capabilities")
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk());

    // 规则读写。
    var payload =
        JSON.writeValueAsBytes(
            new PriorityRulesInput(
                List.of(new PriorityRuleInput(PriorityScopeType.GLOBAL, "*", 4, "默认"))));
    http.perform(
            post("/catalog/price-lists/lists/" + list.summary().id() + "/priority")
                .header("Authorization", "Bearer tenant-a")
                .contentType("application/json")
                .content(payload))
        .andExpect(status().isOk());

    http.perform(
            get("/catalog/price-lists/lists/" + list.summary().id() + "/priority")
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk());

    // 冲突明细。
    http.perform(
            get("/catalog/price-lists/lists/" + list.summary().id() + "/conflicts")
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk());
    http.perform(get("/catalog/price-lists/conflicts").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk());

    // 解析。
    var resolvePayload =
        JSON.writeValueAsBytes(
            new ResolveRequest(shop.code(), shop.siteCode(), "SKU-1", null));
    http.perform(
            post("/catalog/price-lists/resolve")
                .header("Authorization", "Bearer tenant-a")
                .contentType("application/json")
                .content(resolvePayload))
        .andExpect(status().isOk());

    // 不存在的价目表 ⇒ 404。
    http.perform(
            get("/catalog/price-lists/lists/" + UUID.randomUUID() + "/conflicts")
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isNotFound());
  }
}
