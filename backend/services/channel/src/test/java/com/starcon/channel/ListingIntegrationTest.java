package com.starcon.channel;

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
 * 4.1.1.1 Listing 列表。
 *
 * <p>本测试类钉八组事：
 *
 * <ol>
 *   <li><b>多租户三断言</b>（AGENTS.md 硬性要求，缺一不可）：两租户互不可见、伪造 tenant header 无效、
 *       连接池归还后不串租户
 *   <li><b>🔴 价格不伪造</b>：capabilities 里 priceAvailable=false + 归属卷 05；列表行<b>没有价格字段</b>，
 *       断言 JSON 里不存在 price / amount / salePrice 任何一个键
 *   <li><b>🔴「未映射」与「缺货」是两个独立状态</b>：能同时筛出「已映射 + 平台缺货」，也能单独筛
 *   <li><b>🔴 改价 / 改库存显式拒绝</b>且拒绝这件事落库留痕，不返回「成功但什么都没做」
 *   <li><b>🔴 状态变更由 DB 触发器派生</b>：服务层改状态就一定能在近 7 日变化里查到
 *   <li><b>映射状态与 internal_sku_id 必须一致</b>：DB 触发器兜底，直接写 SQL 也被拒
 *   <li><b>时间字段写回值正确</b>（AGENTS.md 要求断言写回值，不能只断言没抛异常）
 *   <li><b>真分页真筛选</b>；跨租户与不存在返回逐字相同的 404
 * </ol>
 *
 * <p><b>时刻一律用固定的 {@link OffsetDateTime} 钉死</b>，不用 {@code now()}：用墙钟会让「这次写入
 * 落在哪个断言分支上」成为一个 flaky 断言。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ListingIntegrationTest {

  static final PostgreSQLContainer<?> pg =
      new PostgreSQLContainer<>("postgres:16-alpine").withUrlParam("sslmode", "disable");
  static final UUID user = UUID.randomUUID();
  static HttpServer identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static final ObjectMapper JSON = new ObjectMapper();

  /**
   * 🔴 每个用例一对**全新的租户**。
   *
   * <p>刻意不给整个测试类共用一对租户 UUID：同一个 Testcontainers 容器里所有用例共享一个库，
   * 共用租户就意味着前一个用例建的 listing 会被后一个用例的 total / 列表断言数进去 ——
   * 那不是隔离被破坏，而是**测试之间互相污染**。
   */
  UUID a, b;
  AccessService.Access actorA, actorB;
  String bearerA;

  /** 🔴 钉死的时刻。用墙钟会让「写回值对不对」变成 flaky 断言。 */
  static final OffsetDateTime LAUNCHED_AT = OffsetDateTime.parse("2026-10-01T09:00:00+08:00");
  static final OffsetDateTime SYNCED_AT = OffsetDateTime.parse("2026-10-02T18:30:00+08:00");

  static final UUID AMAZON = UUID.fromString("11111111-1111-1111-1111-111111111111");
  static final UUID TIKTOK = UUID.fromString("22222222-2222-2222-2222-222222222222");
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
    String bearerB = "Bearer t-b-" + b;
    List<AccessService.Grant> grants = List.of(grant("ALL", allPermissions));
    actors.put(bearerA, actor(user, a, grants));
    actors.put(bearerB, actor(user, b, grants));
    actorA = actors.get(bearerA);
    actorB = actors.get(bearerB);
  }

  /** 播种全局码表（平台目录）。刻意不在静态块里播种：Flyway 在上下文刷新时才建表。 */
  @BeforeEach
  void seedGlobalCatalog() {
    exec("insert into platform (id,code,name,integration,support_status,position)"
        + " values ('" + AMAZON + "','AMAZON','Amazon','OFFICIAL_API','AVAILABLE',1)"
        + " on conflict (id) do nothing");
    exec("insert into platform (id,code,name,integration,support_status,position)"
        + " values ('" + TIKTOK + "','TIKTOK','TikTok Shop','OFFICIAL_API','AVAILABLE',2)"
        + " on conflict (id) do nothing");
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

  @Autowired ListingService service;
  @Autowired AccessService access;
  @Autowired MockMvc http;

  // ==================================================================
  // 辅助
  // ==================================================================

  <T> T as(AccessService.Access who, String permission, java.util.function.Supplier<T> work) {
    return access.run(who, permission, work);
  }

  /** 以管理员（超级用户，RLS 不生效）连接执行 SQL，用来验证 DB 层的兜底。 */
  static void exec(String sql) {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(sql);
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * 以「已认证的租户操作者」身份执行 SQL：绑定租户 + actor + 权限 + 数据范围。
   *
   * <p>🔴 店铺表上除了 RLS 还有一条 {@code data_scope} RESTRICTIVE 策略读 {@code app.grants}，
   *   只 {@code set app.current_tenant} 是不够的 —— 漏一项会得到一个「看起来像 RLS 挂了」的假象。
   */
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

  /** 播种一个店铺（幂等：店铺码是 UNIQUE(tenant_id, code)）。 */
  UUID seedShop(UUID tenant, UUID platformId, String code) {
    UUID id = UUID.randomUUID();
    execAsTenant(tenant,
        "insert into shop (tenant_id,id,platform_id,code,name) values ('" + tenant + "','" + id
            + "','" + platformId + "','" + code + "','测试店铺 " + code + "')"
            + " on conflict (tenant_id,code) do nothing");
    return id;
  }

  /** 播种一个站点（全局码表 market 表，按平台内唯一）。 */
  UUID seedMarketplace(UUID platformId, String code) {
    UUID id = UUID.randomUUID();
    exec("insert into marketplace (id,platform_id,code,name,country_code,default_currency)"
        + " values ('" + id + "','" + platformId + "','" + code + "','" + code + " 市场','US','USD')"
        + " on conflict (platform_id,code) do nothing");
    return id;
  }

  SaveListingRequest request(
      UUID shopId, UUID marketplaceId, String externalItemId, String title, String status,
      String stockState, List<SaveVariantRequest> variants) {
    return new SaveListingRequest(
        null, shopId, marketplaceId, externalItemId, title, "Electronics > Audio", "cat-1",
        status, null, "", stockState, null,
        new BigDecimal("4.7000"), new BigDecimal("128"), "USD",
        LAUNCHED_AT, SYNCED_AT, variants, null);
  }

  static SaveVariantRequest mappedVariant(String externalId, UUID skuId) {
    return new SaveVariantRequest(null, externalId, "MAPPED", skuId, null, "{\"size\":\"L\"}");
  }

  static SaveVariantRequest unmappedVariant(String externalId) {
    return new SaveVariantRequest(null, externalId, "UNMAPPED", null, null, "{\"size\":\"M\"}");
  }

  ListingRow save(AccessService.Access who, SaveListingRequest request) {
    return as(who, "channel.operation.create", () -> service.save(request));
  }

  // ==================================================================
  // 1. 价格不伪造（卷 05 整卷未交付）
  // ==================================================================

  @Test
  @DisplayName("4.1.1.1 价格未接入：capabilities 显式 priceAvailable=false + 归属卷 05，列表行没有价格字段")
  void priceIsNotFaked() throws Exception {
    var caps = as(actorA, "channel.operation.read", () -> service.capabilities());

    // 三个未接入维度各自 available=false + ownerModule 非空 + impact 非空
    for (String key : List.of("price", "sales", "internal_stock")) {
      var dim = caps.dimensions().stream().filter(d -> d.dimension().equals(key)).findFirst();
      assertThat(dim).as("维度 %s 必须存在", key).isPresent();
      assertThat(dim.get().available()).as("%s 必须 available=false", key).isFalse();
      assertThat(dim.get().ownerModule()).as("%s 必须写明归属", key).isNotBlank();
      assertThat(dim.get().impact()).as("%s 必须写明不交付的影响", key).isNotBlank();
    }
    assertThat(caps.priceAvailable()).isFalse();
    assertThat(caps.priceOwnerModule()).contains("05");
    assertThat(caps.priceNote()).contains("未接入");

    UUID shop = seedShop(a, AMAZON, "PRICE-AMZ");
    save(actorA, request(shop, null, "PRICE-1", "价格测试商品", "ON_SALE", "IN_STOCK", List.of()));

    // 🔴 列表行的 JSON 里**不能出现任何价格键**：有键就得填值，填 0 会被读成「卖 0 块」
    http.perform(get("/channel/listings").header("Authorization", bearerA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].price").doesNotExist())
        .andExpect(jsonPath("$.items[0].salePrice").doesNotExist())
        .andExpect(jsonPath("$.items[0].amount").doesNotExist())
        .andExpect(jsonPath("$.items[0].cost").doesNotExist())
        .andExpect(jsonPath("$.priceAvailable").doesNotExist());
  }

  // ==================================================================
  // 2. 「未映射」与「缺货」是两个独立状态
  // ==================================================================

  @Test
  @DisplayName("4.1.1.1 未映射与缺货是两个独立状态：可分别筛选，也能同时筛出「已映射 + 平台缺货」")
  void unmappedAndOutOfStockAreIndependent() {
    UUID shop = seedShop(a, AMAZON, "IND-AMZ");
    UUID sku = UUID.randomUUID();

    // 1) 未映射 + 有货
    save(actorA, request(shop, null, "IND-1", "未映射有货", "ON_SALE", "IN_STOCK",
        List.of(unmappedVariant("V-1"))));
    // 2) 已映射 + 有货
    save(actorA, request(shop, null, "IND-2", "已映射有货", "ON_SALE", "IN_STOCK",
        List.of(mappedVariant("V-2", sku))));
    // 3) 已映射 + 平台缺货  ← 最容易漏的那一类
    save(actorA, request(shop, null, "IND-3", "已映射缺货", "ON_SALE", "OUT_OF_STOCK",
        List.of(mappedVariant("V-3", sku))));
    // 4) 未映射 + 平台缺货
    save(actorA, request(shop, null, "IND-4", "未映射缺货", "ON_SALE", "OUT_OF_STOCK",
        List.of(unmappedVariant("V-4"))));

    var unmapped = as(actorA, "channel.operation.read",
        () -> service.list(null, null, null, null, null, "UNMAPPED", null, null, "RECENT", 0, 50));
    assertThat(unmapped.items()).extracting(ListingRow::externalItemId)
        .containsExactlyInAnyOrder("IND-1", "IND-4");

    var mapped = as(actorA, "channel.operation.read",
        () -> service.list(null, null, null, null, null, "MAPPED", null, null, "RECENT", 0, 50));
    assertThat(mapped.items()).extracting(ListingRow::externalItemId)
        .containsExactlyInAnyOrder("IND-2", "IND-3");

    var outOfStock = as(actorA, "channel.operation.read",
        () -> service.list(null, null, null, null, null, null, true, null, "RECENT", 0, 50));
    assertThat(outOfStock.items()).extracting(ListingRow::externalItemId)
        .containsExactlyInAnyOrder("IND-3", "IND-4");

    // 🔴 两条筛选同时生效：只命中「已映射 + 平台缺货」这一类
    var both = as(actorA, "channel.operation.read",
        () -> service.list(null, null, null, null, null, "MAPPED", true, null, "RECENT", 0, 50));
    assertThat(both.items()).extracting(ListingRow::externalItemId).containsExactly("IND-3");
    // 那一行上两个字段各自表达自己的事实
    assertThat(both.items().get(0).mappingStatus()).isEqualTo("MAPPED");
    assertThat(both.items().get(0).platformStockState()).isEqualTo("OUT_OF_STOCK");

    // 🔴 未知取值必须显式报错，而不是静默返回空列表
    assertThat(catchThrowable(() -> as(actorA, "channel.operation.read",
        () -> service.list(null, null, null, null, null, "NOT_A_STATUS", null, null, "RECENT", 0, 50))))
        .hasMessageContaining("未知的映射状态");
    assertThat(catchThrowable(() -> as(actorA, "channel.operation.read",
        () -> service.list(null, null, "NOT_A_STATUS", null, null, null, null, null, "RECENT", 0, 50))))
        .hasMessageContaining("未知的平台商品状态");
  }

  @Test
  @DisplayName("4.1.1.1 部分映射报 PARTIAL：变体映射不齐时既不是 UNMAPPED 也不是 MAPPED")
  void partialMappingIsItsOwnState() {
    UUID shop = seedShop(a, AMAZON, "PART-AMZ");
    UUID sku = UUID.randomUUID();
    save(actorA, request(shop, null, "PART-1", "部分映射", "ON_SALE", "IN_STOCK",
        List.of(mappedVariant("V-A", sku), unmappedVariant("V-B"))));

    var row = as(actorA, "channel.operation.read",
        () -> service.list(null, null, null, null, null, "PARTIAL", null, null, "RECENT", 0, 50))
        .items().get(0);

    assertThat(row.mappingStatus()).isEqualTo("PARTIAL");
    assertThat(row.variantCount()).isEqualTo(2);
    assertThat(row.mappedVariantCount()).isEqualTo(1);
    assertThat(row.internalSkuIds()).containsExactly(sku);
  }

  // ==================================================================
  // 3. 改价 / 改库存显式拒绝且留痕
  // ==================================================================

  @Test
  @DisplayName("4.1.1.1 批量改价 / 改库存显式拒绝（归 05 / 07）并落库留痕，不返回「成功但什么都没做」")
  void repriceAndRestockAreRejectedWithTrace() {
    UUID shop = seedShop(a, AMAZON, "BATCH-AMZ");
    var row = save(actorA, request(shop, null, "BATCH-1", "批量测试", "ON_SALE", "IN_STOCK", List.of()));

    var price = as(actorA, "channel.operation.update",
        () -> service.batch(new ListingBatchRequest(
            "REPRICE", List.of(row.id()), null, new BigDecimal("19.99"), null)));
    assertThat(price.outcome()).isEqualTo("REJECTED");
    assertThat(price.affectedCount()).isZero();
    assertThat(price.ownerModule()).contains("05");
    assertThat(price.reason()).contains("跳 05/07");

    var stock = as(actorA, "channel.operation.update",
        () -> service.batch(new ListingBatchRequest(
            "RESTOCK", List.of(row.id()), null, null, new BigDecimal("50"))));
    assertThat(stock.outcome()).isEqualTo("REJECTED");
    assertThat(stock.ownerModule()).contains("07");

    // 🔴 拒绝这件事本身要能在任务列表里回溯
    var tasks = as(actorA, "channel.operation.read", () -> service.batchTasks(0, 50));
    assertThat(tasks).extracting(ListingBatchResult::outcome)
        .containsExactlyInAnyOrder("REJECTED", "REJECTED");
    assertThat(tasks).extracting(ListingBatchResult::action)
        .containsExactlyInAnyOrder("REPRICE", "RESTOCK");
  }

  @Test
  @DisplayName("4.1.1.1 批量分配负责人与批量下架真实生效，并记入状态历史")
  void assignOwnerAndDelistWork() {
    UUID shop = seedShop(a, AMAZON, "OWN-AMZ");
    var row = save(actorA, request(shop, null, "OWN-1", "负责人测试", "ON_SALE", "IN_STOCK", List.of()));

    UUID newOwner = UUID.randomUUID();
    var assigned = as(actorA, "channel.operation.update",
        () -> service.batch(new ListingBatchRequest(
            "ASSIGN_OWNER", List.of(row.id()), newOwner, null, null)));
    assertThat(assigned.outcome()).isEqualTo("SUCCEEDED");
    assertThat(assigned.affectedCount()).isEqualTo(1);
    assertThat(as(actorA, "channel.operation.read", () -> service.load(row.id().toString())).ownerId())
        .isEqualTo(newOwner);

    var delisted = as(actorA, "channel.operation.update",
        () -> service.batch(new ListingBatchRequest("DELIST", List.of(row.id()), null, null, null)));
    assertThat(delisted.outcome()).isEqualTo("SUCCEEDED");
    assertThat(as(actorA, "channel.operation.read", () -> service.load(row.id().toString())).status())
        .isEqualTo("DELISTED_BY_US");

    // 🔴 状态变了就一定能在近 7 日变化里查到（触发器派生，服务层漏记不了）
    var reloaded = as(actorA, "channel.operation.read", () -> service.load(row.id().toString()));
    assertThat(reloaded.statusChangeCount7d()).isGreaterThanOrEqualTo(1);
    assertThat(reloaded.recentStatusChanges()).anySatisfy(c -> {
      assertThat(c.fromStatus()).isEqualTo("ON_SALE");
      assertThat(c.toStatus()).isEqualTo("DELISTED_BY_US");
      assertThat(c.source()).isEqualTo("MANUAL");
    });

    // 批量下架后的条目应能被状态筛选命中
    assertThat(as(actorA, "channel.operation.read",
        () -> service.list(null, null, "DELISTED_BY_US", null, null, null, null, null, "RECENT", 0, 50))
        .items()).extracting(ListingRow::externalItemId).containsExactly("OWN-1");
  }

  // ==================================================================
  // 4. DB 兜底：映射状态与 internal_sku_id 必须一致
  // ==================================================================

  @Test
  @DisplayName("4.1.1.1 服务层声称 MAPPED 却给不出内部 SKU 时被拒（服务层自查 + DB 触发器兜底）")
  void phantomMappingIsRejected() {
    UUID shop = seedShop(a, AMAZON, "PHAN-AMZ");
    // 服务层自己就该拒绝
    assertThat(catchThrowable(() -> save(actorA,
        request(shop, null, "PHAN-1", "幽灵映射", "ON_SALE", "IN_STOCK",
            List.of(new SaveVariantRequest(null, "V-1", "MAPPED", null, null, "{}"))))))
        .hasMessageContaining("必须给出 internalSkuId");

    // 给了 SKU 却声称 UNMAPPED，同样自相矛盾
    assertThat(catchThrowable(() -> save(actorA,
        request(shop, null, "PHAN-2", "反向幽灵", "ON_SALE", "IN_STOCK",
            List.of(new SaveVariantRequest(null, "V-1", "UNMAPPED", UUID.randomUUID(), null, "{}"))))))
        .hasMessageContaining("不能声称 UNMAPPED");

    // 🔴 绕开服务层直接写 SQL，DB 触发器同样要拒
    UUID listingId = save(actorA, request(shop, null, "PHAN-3", "直接写 SQL", "ON_SALE", "IN_STOCK",
        List.of())).id();
    var raw = catchThrowable(() -> execAsTenant(a,
        "insert into listing_variant (id,tenant_id,listing_id,external_variant_id,mapping_status,"
            + "internal_sku_id) values ('" + UUID.randomUUID() + "','" + a + "','" + listingId
            + "','V-GHOST','MAPPED',null)"));
    assertThat(raw).isNotNull();
    assertThat(raw.getMessage()).contains("carries no internal_sku_id");
  }

  @Test
  @DisplayName("4.1.1.1 平台商品标识不可就地改写（列表与批量操作都按它寻址）")
  void listingIdentityIsImmutable() {
    UUID shop = seedShop(a, AMAZON, "IMM-AMZ");
    var row = save(actorA, request(shop, null, "IMM-1", "标识不可改", "ON_SALE", "IN_STOCK", List.of()));
    var raw = catchThrowable(() -> execAsTenant(a,
        "update listing set external_item_id='IMM-CHANGED' where tenant_id='" + a
            + "' and id='" + row.id() + "'"));
    assertThat(raw).isNotNull();
    assertThat(raw.getMessage()).contains("must not be rewritten in place");
  }

  // ==================================================================
  // 5. 时间字段写回值正确（AGENTS.md 要求断言写回值）
  // ==================================================================

  @Test
  @DisplayName("4.1.1.1 timestamptz 往返不偏 8 小时：断言写回的值逐字相等")
  void timestampRoundTripsWithoutShift() {
    UUID shop = seedShop(a, AMAZON, "TS-AMZ");
    var row = save(actorA, request(shop, null, "TS-1", "时间测试", "ON_SALE", "IN_STOCK", List.of()));

    var reloaded = as(actorA, "channel.operation.read", () -> service.load(row.id().toString()));
    // 🔴 断言写回的值，不只是"没抛异常"。用 Timestamp.valueOf(LocalDateTime) 会静默偏 −28800s。
    assertThat(reloaded.launchedAt()).isEqualTo(LAUNCHED_AT);
    assertThat(reloaded.lastSyncedAt()).isEqualTo(SYNCED_AT);
    assertThat(reloaded.launchedAt().toInstant()).isEqualTo(LAUNCHED_AT.toInstant());

    // 原始库里的值也逐字相等
    var raw =
        as(actorA, "channel.operation.read", () -> service.load(row.id().toString())).launchedAt();
    assertThat(raw.toString()).startsWith("2026-10-01T09:00");
  }

  // ==================================================================
  // 6. 真分页真筛选
  // ==================================================================

  @Test
  @DisplayName("4.1.1.1 分页与七个筛选维度都是真的")
  void paginationAndFiltersAreReal() {
    UUID shopA = seedShop(a, AMAZON, "PAGE-AMZ");
    UUID shopB = seedShop(a, AMAZON, "PAGE-AMZ-2");
    UUID siteA = seedMarketplace(AMAZON, "US");

    for (int i = 0; i < 7; i++) {
      save(actorA, new SaveListingRequest(
          null, shopA, i % 2 == 0 ? siteA : null, "PAGE-" + i, "分页商品 " + i,
          "Electronics", "cat", i % 3 == 0 ? "DRAFT" : "ON_SALE", "APPROVED", "",
          i % 4 == 0 ? "OUT_OF_STOCK" : "IN_STOCK", null,
          new BigDecimal("3.0000").add(new BigDecimal(i)), new BigDecimal("10"), "USD",
          LAUNCHED_AT, SYNCED_AT, List.of(), null));
    }

    // 分页：size=3，第 0 页 3 条、第 1 页 3 条、第 2 页 1 条，total 恒为 7
    var p0 = as(actorA, "channel.operation.read",
        () -> service.list(null, null, null, null, null, null, null, null, "RECENT", 0, 3));
    var p1 = as(actorA, "channel.operation.read",
        () -> service.list(null, null, null, null, null, null, null, null, "RECENT", 1, 3));
    var p2 = as(actorA, "channel.operation.read",
        () -> service.list(null, null, null, null, null, null, null, null, "RECENT", 2, 3));
    assertThat(p0.total()).isEqualTo(7);
    assertThat(p1.total()).isEqualTo(7);
    assertThat(p2.total()).isEqualTo(7);
    assertThat(p0.items()).hasSize(3);
    assertThat(p1.items()).hasSize(3);
    assertThat(p2.items()).hasSize(1);
    // 三页不重叠
    var ids = new java.util.ArrayList<UUID>();
    p0.items().forEach(x -> ids.add(x.id()));
    p1.items().forEach(x -> ids.add(x.id()));
    p2.items().forEach(x -> ids.add(x.id()));
    assertThat(ids).doesNotHaveDuplicates();

    // 筛选：店铺 / 站点 / 状态 / 类目 / 缺货
    assertThat(as(actorA, "channel.operation.read",
        () -> service.list("PAGE-AMZ", null, null, null, null, null, null, null, "RECENT", 0, 50))
        .total()).isEqualTo(7);
    assertThat(as(actorA, "channel.operation.read",
        () -> service.list("PAGE-AMZ-2", null, null, null, null, null, null, null, "RECENT", 0, 50))
        .total()).isZero();
    assertThat(as(actorA, "channel.operation.read",
        () -> service.list(null, "US", null, null, null, null, null, null, "RECENT", 0, 50)).total())
        .isEqualTo(4);
    assertThat(as(actorA, "channel.operation.read",
        () -> service.list(null, null, "DRAFT", null, null, null, null, null, "RECENT", 0, 50)).total())
        .isEqualTo(3);
    assertThat(as(actorA, "channel.operation.read",
        () -> service.list(null, null, null, null, "Electronics", null, null, null, "RECENT", 0, 50))
        .total()).isEqualTo(7);
    assertThat(as(actorA, "channel.operation.read",
        () -> service.list(null, null, null, null, null, null, true, null, "RECENT", 0, 50)).total())
        .isEqualTo(2);
    // 搜索命中标题与平台商品 ID
    assertThat(as(actorA, "channel.operation.read",
        () -> service.list(null, null, null, null, null, null, null, "分页商品 3", "RECENT", 0, 50))
        .total()).isEqualTo(1);
    assertThat(as(actorA, "channel.operation.read",
        () -> service.list(null, null, null, null, null, null, null, "PAGE-4", "RECENT", 0, 50))
        .total()).isEqualTo(1);

    // 排序：评分倒序真的换了顺序
    var byRating = as(actorA, "channel.operation.read",
        () -> service.list(null, null, null, null, null, null, null, null, "RATING_DESC", 0, 50));
    assertThat(byRating.items().get(0).rating())
        .isEqualTo(byRating.items().get(0).rating().max(byRating.items().get(0).rating()));
    assertThat(byRating.items().get(0).externalItemId()).isEqualTo("PAGE-6");
  }

  // ==================================================================
  // 7. 端点往返 + 跨租户 / 不存在同一条 404
  // ==================================================================

  @Test
  @DisplayName("4.1.1.1 端点：401 与真实业务往返；跨租户与不存在返回逐字相同的 404")
  void endpointsWork() throws Exception {
    http.perform(get("/channel/listings")).andExpect(status().isUnauthorized());
    http.perform(get("/channel/listings/capabilities")).andExpect(status().isUnauthorized());

    UUID shop = seedShop(a, AMAZON, "EP-AMZ");
    UUID sku = UUID.randomUUID();
    var row = save(actorA, request(shop, null, "EP-1", "端点测试", "ON_SALE", "OUT_OF_STOCK",
        List.of(mappedVariant("V-1", sku))));

    http.perform(get("/channel/listings/capabilities").header("Authorization", bearerA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.priceAvailable").value(false))
        .andExpect(jsonPath("$.priceNote",
            org.hamcrest.Matchers.containsString("未接入")))
        .andExpect(jsonPath("$.independentStateNote",
            org.hamcrest.Matchers.containsString("两个独立状态")));

    http.perform(get("/channel/listings/filter-options").header("Authorization", bearerA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.statuses", org.hamcrest.Matchers.hasItem("ON_SALE")));

    http.perform(get("/channel/listings").header("Authorization", bearerA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].externalItemId").value("EP-1"))
        .andExpect(jsonPath("$.items[0].mappingStatus").value("MAPPED"))
        .andExpect(jsonPath("$.items[0].platformStockState").value("OUT_OF_STOCK"))
        .andExpect(jsonPath("$.items[0].internalSkuIds[0]").value(sku.toString()))
        // 🔴 平台商品 ID 原文照抄
        .andExpect(jsonPath("$.items[0].externalItemId").value("EP-1"));

    http.perform(get("/channel/listings/" + row.id()).header("Authorization", bearerA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(row.id().toString()));

    http.perform(get("/channel/listings/not-a-uuid").header("Authorization", bearerA))
        .andExpect(status().isNotFound());

    http.perform(get("/channel/listings").header("Authorization", bearerA)
            .param("status", "NOT_A_STATUS"))
        .andExpect(status().isUnprocessableEntity());

    http.perform(get("/channel/listings/batch-tasks").header("Authorization", bearerA))
        .andExpect(status().isOk());

    var crossTenant = catchThrowable(() ->
        as(actorB, "channel.operation.read", () -> service.load(row.id().toString())));
    var missing = catchThrowable(() ->
        as(actorB, "channel.operation.read", () -> service.load(UUID.randomUUID().toString())));
    assertThat(crossTenant).isNotNull();
    assertThat(missing).isNotNull();
    assertThat(crossTenant.getMessage()).isEqualTo(missing.getMessage());
  }

  // ==================================================================
  // 8. 多租户三断言（AGENTS.md 硬性要求，缺一不可）
  // ==================================================================

  @Test
  @DisplayName("4.1.1.1 断言一：两租户互不可见")
  void tenantsAreIsolated() {
    UUID shopA = seedShop(a, AMAZON, "ISO-AMZ-1");
    UUID shopB = seedShop(b, AMAZON, "ISO-AMZ-2");
    var rowA = save(actorA, request(shopA, null, "ISO-A", "A 的商品", "ON_SALE", "IN_STOCK", List.of()));
    var rowB = save(actorB, request(shopB, null, "ISO-B", "B 的商品", "ON_SALE", "IN_STOCK", List.of()));

    assertThat(as(actorA, "channel.operation.read",
        () -> service.list(null, null, null, null, null, null, null, null, "RECENT", 0, 50)).items())
        .extracting(ListingRow::externalItemId).containsExactly("ISO-A");
    assertThat(as(actorB, "channel.operation.read",
        () -> service.list(null, null, null, null, null, null, null, null, "RECENT", 0, 50)).items())
        .extracting(ListingRow::externalItemId).containsExactly("ISO-B");

    // 🔴 店铺也不可见：把 A 的 listing 挂到 B 的店铺上，复合 FK + RLS 双重挡住
    var crossMount = catchThrowable(() -> execAsTenant(b,
        "update listing set shop_id='" + shopB + "' where tenant_id='" + a + "' and id='" + rowA.id() + "'"));
    assertThat(crossMount).isNotNull();

    // B 看不到 A 的批量任务
    assertThat(as(actorB, "channel.operation.read", () -> service.batchTasks(0, 50))).isEmpty();
  }

  @Test
  @DisplayName("4.1.1.1 断言二：伪造 tenant header 无效（租户只来自已认证成员关系）")
  void forgedTenantHeaderIsIgnored() throws Exception {
    UUID shopA = seedShop(a, AMAZON, "FORGE-AMZ-1");
    UUID shopB = seedShop(b, AMAZON, "FORGE-AMZ-2");
    save(actorA, request(shopA, null, "FORGE-A", "A 的商品", "ON_SALE", "IN_STOCK", List.of()));
    var rowB = save(actorB, request(shopB, null, "FORGE-B", "B 的商品", "ON_SALE", "IN_STOCK", List.of()));

    // A 的会话 + B 的头 ⇒ 仍然只看得见 A 的数据
    http.perform(get("/channel/listings")
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[*].externalItemId",
            org.hamcrest.Matchers.contains("FORGE-A")));

    // 伪造租户头也不能让 B 的 listing 被 A 读到
    http.perform(get("/channel/listings/" + rowB.id())
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isNotFound());

    // 无会话一律 401
    http.perform(get("/channel/listings").header("X-Tenant-Id", b.toString()))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("4.1.1.1 断言三：连接池归还后不串租户（池压到 1，A→B→A 三轮）")
  void pooledConnectionsDoNotBleedTenants() {
    UUID shopA = seedShop(a, AMAZON, "POOL-AMZ-1");
    UUID shopB = seedShop(b, AMAZON, "POOL-AMZ-2");
    save(actorA, request(shopA, null, "POOL-A", "A 的商品", "ON_SALE", "IN_STOCK", List.of()));
    save(actorB, request(shopB, null, "POOL-B", "B 的商品", "ON_SALE", "IN_STOCK", List.of()));

    for (int i = 0; i < 3; i++) {
      assertThat(as(actorA, "channel.operation.read",
          () -> service.list(null, null, null, null, null, null, null, null, "RECENT", 0, 50)).items())
          .extracting(ListingRow::externalItemId).containsExactly("POOL-A");
      assertThat(as(actorB, "channel.operation.read",
          () -> service.list(null, null, null, null, null, null, null, null, "RECENT", 0, 50)).items())
          .extracting(ListingRow::externalItemId).containsExactly("POOL-B");
      // 批量任务与筛选选项也走同一个连接池
      assertThat(as(actorA, "channel.operation.read", () -> service.batchTasks(0, 50))).isEmpty();
      assertThat(as(actorB, "channel.operation.read", () -> service.batchTasks(0, 50))).isEmpty();
      assertThat(as(actorA, "channel.operation.read", () -> service.capabilities()).priceAvailable())
          .isFalse();
    }
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
