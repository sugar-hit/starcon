package com.starcon.catalog;

import static com.starcon.catalog.PriceListItemModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
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
 * 5.1.1.2 价目项的**多租户三断言**（AGENTS.md 明文要求，缺一不可）。
 *
 * <p>跑的是这三条：
 *
 * <ol>
 *   <li><b>两租户互不可见</b>：A 看不到 B 的价目项（列表、明细、直查都验）；
 *   <li><b>伪造 tenant header 无效</b>：请求头里的 {@code X-Tenant-Id} 改不了任何事 ——
 *       租户只来自已认证成员关系（identity），伪造头下写入的行仍然落在调用者自己名下；
 *   <li><b>连接池归还后不串租户</b>：连接池压到 1 条连接，A→B→A→B 交替 5 轮，
 *       每一轮读到的都只有当前租户的数据。
 * </ol>
 *
 * <p>另加一条：<b>FORCE RLS 而不只是 ENABLE</b> —— 漏掉 FORCE 之后表 owner（迁移角色）
 * 仍能绕过策略读别人的价目项，而价目项里写着这个租户的**单价、阶梯门槛与生效区间**，
 * 那是一条看不见的越权面（能看见别人打算卖多少钱）。
 *
 * <p><b>真实 PostgreSQL 16 + FORCE RLS，不使用任何内存替身</b>；运行角色
 * {@code catalog_app} 是 {@code nobypassrls}（否则它会绕过策略，三条断言全部变成空断言）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class PriceListItemTenancyIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  static final ObjectMapper JSON =
      new ObjectMapper()
          .findAndRegisterModules()
          .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
  static final Map<String, AccessService.Access> actors =
      new java.util.concurrent.ConcurrentHashMap<>();
  static final AtomicInteger SEQ = new AtomicInteger();

  static AccessService.Access actor(UUID uid, UUID tenant, List<AccessService.Grant> grants) {
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

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "catalog_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://localhost:" + identity.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
    // 单连接：只有复用同一条连接，才能真正证明归还之后不会串租户。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  @AfterAll
  static void stop() {
    identity.stop(0);
    pg.stop();
  }

  @Autowired PriceListItemService items;
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

  /** 每个租户各自建一张价目表 + 一个 SKU（各在自己的租户下）。 */
  record Fixture(UUID priceListId, UUID skuId, String skuCode) {}

  Fixture fixture(AccessService.Access who, UUID tenant) {
    return write(
        who,
        () -> {
          var listId = UUID.randomUUID();
          sql.execute(
              "insert into price_list(id,tenant_id,code,name,currency,valid_from,enabled,created_by)"
                  + " values(?::uuid,?::uuid,?,?,'USD',?::timestamptz,true,?::uuid)",
              listId,
              tenant,
              unique("PL"),
              "价目表",
              OffsetDateTime.now(ZoneOffset.UTC).minusDays(1),
              user);
          var spuId = UUID.randomUUID();
          sql.execute(
              "insert into spu(id,tenant_id,code,name,brand,category,status)"
                  + " values(?::uuid,?::uuid,?,?,?,?,'ON_SALE')",
              spuId,
              tenant,
              unique("SPU"),
              "SPU",
              "ACME",
              "家居");
          var skuId = UUID.randomUUID();
          var code = unique("SKU");
          sql.execute(
              "insert into sku(id,tenant_id,spu_id,code,name,barcode,base_unit,status,variant_signature)"
                  + " values(?::uuid,?::uuid,?::uuid,?,?,?,'件','ACTIVE',?)",
              skuId,
              tenant,
              spuId,
              code,
              "SKU " + code,
              "BAR-" + code,
              code);
          return new Fixture(listId, skuId, code);
        });
  }

  PriceListItemView saveItem(AccessService.Access who, Fixture fixture, String price) {
    return write(
        who,
        () ->
            items.save(
                who,
                fixture.priceListId(),
                null,
                new PriceListItemInput(
                    fixture.skuCode(),
                    new BigDecimal(price),
                    BigDecimal.ZERO,
                    0,
                    OffsetDateTime.now(ZoneOffset.UTC).minusDays(1),
                    null,
                    Boolean.TRUE,
                    "",
                    null)));
  }

  // ================================================================ 断言一：两租户互不可见

  @Test
  void 两租户互不可见() {
    var mine = fixture(actorA, a);
    var theirs = fixture(actorB, b);
    var myItem = saveItem(actorA, mine, "19.99");
    var theirItem = saveItem(actorB, theirs, "88.88");

    // 两边都真的有数据 —— 否则下面的断言就是空的。
    assertThat(tx(actorA, () -> items.detail(mine.priceListId(), myItem.id()))).isNotNull();
    assertThat(tx(actorB, () -> items.detail(theirs.priceListId(), theirItem.id()))).isNotNull();

    // 列表里混不进对方的价目项。
    var myRows =
        tx(
            actorA,
            () ->
                items.list(
                    mine.priceListId(),
                    new PriceListItemService.ItemQuery(null, null, null, null, 0, 200)));
    assertThat(myRows.items()).extracting(PriceListItemView::id).contains(myItem.id());
    assertThat(myRows.items()).extracting(PriceListItemView::id).doesNotContain(theirItem.id());

    var theirRows =
        tx(
            actorB,
            () ->
                items.list(
                    theirs.priceListId(),
                    new PriceListItemService.ItemQuery(null, null, null, null, 0, 200)));
    assertThat(theirRows.items()).extracting(PriceListItemView::id).contains(theirItem.id());
    assertThat(theirRows.items()).extracting(PriceListItemView::id).doesNotContain(myItem.id());

    // 明细读不到对方的价目项（按 id 直查也不行）。
    assertThatThrownBy(
            () -> tx(actorA, () -> items.detail(theirs.priceListId(), theirItem.id())))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> tx(actorB, () -> items.detail(mine.priceListId(), myItem.id())))
        .isInstanceOf(ResponseStatusException.class);

    // 直接查库也读不到对方的那一行（RLS 兜底，不只是服务层过滤）。
    assertThat(
            tx(
                actorA,
                () ->
                    sql.fetchOne("select count(*) from price_list_item where id=?", theirItem.id())
                        .get(0, Long.class)))
        .isZero();
    assertThat(
            tx(
                actorB,
                () ->
                    sql.fetchOne("select count(*) from price_list_item where id=?", myItem.id())
                        .get(0, Long.class)))
        .isZero();

    // 冲突清单也只看得到自己租户的行。
    assertThat(tx(actorA, () -> items.conflicts(mine.priceListId()))).isEmpty();
  }

  /** 写路径也隔离：A 改不掉 B 的价目项。 */
  @Test
  void 写路径也隔离() {
    var mine = fixture(actorA, a);
    var theirs = fixture(actorB, b);
    var theirItem = saveItem(actorB, theirs, "88.88");
    assertThatThrownBy(
            () ->
                write(
                    actorA,
                    () -> {
                      items.delete(theirs.priceListId(), theirItem.id());
                      return null;
                    }))
        .isInstanceOf(ResponseStatusException.class);
    // B 的那一条还在。
    assertThat(tx(actorB, () -> items.detail(theirs.priceListId(), theirItem.id()))).isNotNull();
  }

  // ================================================================ 断言二：伪造 tenant header 无效

  @Test
  void 伪造租户头无效() throws Exception {
    var mine = fixture(actorA, a);
    var theirs = fixture(actorB, b);
    saveItem(actorB, theirs, "88.88");
    var code = mine.skuCode();
    var payload =
        JSON.writeValueAsBytes(
            new PriceListItemInput(
                code,
                new BigDecimal("1.00"),
                BigDecimal.ZERO,
                0,
                OffsetDateTime.parse("2026-10-01T00:00:00Z"),
                null,
                Boolean.TRUE,
                "",
                null));

    // 带伪造的 X-Tenant-Id 头写入：行必须落在**调用者自己**的名下。
    http.perform(
            post("/catalog/price-lists/lists/{id}/items", mine.priceListId())
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b.toString())
                .contentType("application/json")
                .content(payload))
        .andExpect(status().isOk());

    // 伪造头下写出来的行，租户仍然是 A。
    assertThat(
            tx(
                actorA,
                () ->
                    sql.fetchOne(
                            "select count(*) from price_list_item where price_list_id=? and tenant_id=?",
                            mine.priceListId(),
                            a)
                        .get(0, Long.class)))
        .isEqualTo(1L);
    assertThat(
            tx(
                actorB,
                () ->
                    sql.fetchOne(
                            "select count(*) from price_list_item where price_list_id=?",
                            mine.priceListId())
                        .get(0, Long.class)))
        .isZero();

    // 带伪造头读别人的价目项 ⇒ 读不到。
    http.perform(
            get("/catalog/price-lists/lists/{id}/items", theirs.priceListId())
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isNotFound());
  }

  // ================================================================ 断言三：连接池归还后不串租户

  @Test
  void 连接池归还后不串租户() {
    var mine = fixture(actorA, a);
    var theirs = fixture(actorB, b);
    var myItem = saveItem(actorA, mine, "19.99");
    var theirItem = saveItem(actorB, theirs, "88.88");
    // 交替 5 轮：每一次读都必须只看到当前租户的行。
    for (int round = 0; round < 5; round++) {
      var aRows =
          tx(
              actorA,
              () ->
                  items.list(
                      mine.priceListId(),
                      new PriceListItemService.ItemQuery(null, null, null, null, 0, 200)));
      assertThat(aRows.items()).extracting(PriceListItemView::id).contains(myItem.id());
      assertThat(aRows.items()).extracting(PriceListItemView::id).doesNotContain(theirItem.id());

      var bRows =
          tx(
              actorB,
              () ->
                  items.list(
                      theirs.priceListId(),
                      new PriceListItemService.ItemQuery(null, null, null, null, 0, 200)));
      assertThat(bRows.items()).extracting(PriceListItemView::id).contains(theirItem.id());
      assertThat(bRows.items()).extracting(PriceListItemView::id).doesNotContain(myItem.id());
    }
  }

  // ================================================================ 断言四：FORCE RLS

  @Test
  void 价目项强制开启行级安全() {
    for (var table : List.of("price_list_item", "price_list_import_batch", "price_list_import_row")) {
      var row =
          tx(
              actorA,
              () ->
                  sql.fetchOne(
                      "select relforcerowsecurity, relrowsecurity from pg_class where relname=?", table));
      assertThat(row).as("表 %s 必须存在", table).isNotNull();
      assertThat(row.get("relrowsecurity", Boolean.class)).as("%s 必须 ENABLE RLS", table).isTrue();
      assertThat(row.get("relforcerowsecurity", Boolean.class))
          .as("%s 必须 FORCE RLS（漏掉 FORCE 就能绕过策略读别人的价目项）", table)
          .isTrue();
    }
  }
}
