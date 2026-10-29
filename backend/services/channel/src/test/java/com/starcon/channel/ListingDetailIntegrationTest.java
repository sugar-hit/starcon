package com.starcon.channel;

import static com.starcon.channel.ListingDetailModels.*;
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
 * 4.1.1.2 Listing 详情。
 *
 * <p>本测试类钉八组事：
 *
 * <ol>
 *   <li><b>多租户三断言</b>（AGENTS.md 硬性要求，缺一不可）：两租户互不可见、伪造 tenant header 无效、连接池归还后不串租户
 *   <li><b>🔴 四个跨服务缺口不伪造</b>：售价 / 内部可售量 / 内容草稿 / 销量全部 {@code available=false} + ownerModule + impact；
 *       详情 JSON 里不存在 salePrice / price / amount / cost 任何一个键
 *   <li><b>🔴「缺一侧」≠「相同」</b>：只有平台侧有值时状态是 PLATFORM_ONLY / UNAVAILABLE，<b>绝不是 SAME</b>
 *   <li><b>🔴 差异是真的算出来的</b>：平台回报可售量 vs 回写登记簿快照、平台状态 vs 平台可售状态、平台变体 vs 内部映射
 *   <li><b>内容对照右列恒未接入</b>：有快照时左列有值，右列 internalKnown=false + internalValue=null
 *   <li><b>快照追加写 +「当前」唯一</b>：登记第二份同语言快照，第一份由 DB 触发器自动降级
 *   <li><b>发布历史不可改写</b> + FAILED 必须写原因 + 引用别人的快照被拒
 *   <li><b>平台操作记录按 payload 精确归属</b>：别的商品的、带不出来源的都不许混进来；timestamptz <b>断言写回的值</b>
 * </ol>
 *
 * <p><b>时刻一律用固定的 {@link OffsetDateTime} 钉死</b>，不用 {@code now()}：用墙钟会让「这次写入落在哪个断言分支上」
 * 成为一个 flaky 断言。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ListingDetailIntegrationTest {

  static final PostgreSQLContainer<?> pg =
      new PostgreSQLContainer<>("postgres:16-alpine").withUrlParam("sslmode", "disable");
  static final UUID user = UUID.randomUUID();
  static HttpServer identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static final ObjectMapper JSON = new ObjectMapper();

  /** 🔴 每个用例一对全新的租户，避免用例之间互相污染。 */
  UUID a, b;
  AccessService.Access actorA, actorB;
  String bearerA;

  /** 🔴 钉死的时刻。 */
  static final OffsetDateTime CAPTURED_AT = OffsetDateTime.parse("2026-10-03T10:15:00+08:00");
  static final OffsetDateTime PUBLISHED_AT = OffsetDateTime.parse("2026-10-04T21:45:30+08:00");
  static final OffsetDateTime LAUNCHED_AT = OffsetDateTime.parse("2026-10-01T09:00:00+08:00");

  static final UUID AMAZON = UUID.fromString("11111111-1111-1111-1111-111111111111");
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

  @BeforeEach
  void seedGlobalCatalog() {
    exec("insert into platform (id,code,name,integration,support_status,position)"
        + " values ('" + AMAZON + "','AMAZON','Amazon','OFFICIAL_API','AVAILABLE',1)"
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

  @Autowired ListingDetailService service;
  @Autowired ListingService listService;
  @Autowired AccessService access;
  @Autowired MockMvc http;

  // ==================================================================
  // 辅助
  // ==================================================================

  <T> T as(AccessService.Access who, String permission, java.util.function.Supplier<T> work) {
    return access.run(who, permission, work);
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

  /**
   * 以<b>应用角色 channel_app</b>（非超级用户）直查一行计数。
   *
   * <p>🔴 必须用 channel_app 连：Testcontainers 默认的 {@code pg.getUsername()} 是超级用户，
   * 而超级用户<b>绕过 RLS</b> —— 用它查「读不到对方数据」会永远得到「读得到」，
   * 那不是隔离被破坏，而是这个断言本身失效了。
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

  SaveListingRequest listingRequest(
      UUID shopId, String externalItemId, String title, String status, String stockState,
      BigDecimal platformQty, List<SaveVariantRequest> variants) {
    return new SaveListingRequest(
        null, shopId, null, externalItemId, title, "Electronics > Audio", "cat-1",
        status, null, "", stockState, platformQty,
        new BigDecimal("4.7000"), new BigDecimal("128"), "USD",
        LAUNCHED_AT, LAUNCHED_AT, variants, null);
  }

  ListingRow save(AccessService.Access who, SaveListingRequest request) {
    return as(who, "channel.operation.create", () -> listService.save(request));
  }

  ListingDetail detail(AccessService.Access who, UUID id) {
    return as(who, "channel.operation.read", () -> service.detail(id.toString()));
  }

  ListingDiffRow rowOf(ListingDiffReport report, String dimension) {
    return report.rows().stream()
        .filter(r -> r.dimension().equals(dimension))
        .findFirst()
        .orElseThrow(() -> new AssertionError("差异表里没有维度 " + dimension));
  }

  /** 登记一条可回写目标（2.5.2.1 的写入路径，本模块只读不写，这里直接播种）。 */
  void seedRegistry(UUID tenant, UUID shopId, String externalRef, String price, String currency,
      String stock) {
    execAsTenant(tenant,
        "insert into writeback_target (tenant_id,id,shop_id,target_type,external_ref,"
            + "current_stock,current_price,currency,source_ref)"
            + " values ('" + tenant + "','" + UUID.randomUUID() + "','" + shopId + "','LISTING','"
            + externalRef + "'," + stock + "," + (price == null ? "null" : price) + ","
            + (currency == null ? "null" : "'" + currency + "'") + ",'TEST')");
  }

  /** 播种一条平台写操作（V2 platform_operation）。externalRef / targetType 为空即「店铺级操作」，payload 不带这两个键。 */
  void seedOperation(UUID tenant, UUID shopId, String externalRef, String targetType,
      String operation, String status) {
    String payload =
        externalRef == null
            ? "{\"note\":\"店铺级单条派发\"}"
            : "{\"targetType\":\"" + targetType + "\",\"externalRef\":\"" + externalRef + "\"}";
    execAsTenant(tenant,
        "insert into platform_operation (tenant_id,id,shop_id,operation,idempotency_key,payload,"
            + "status,platform_request_id,verification,attempts)"
            + " values ('" + tenant + "','" + UUID.randomUUID() + "','" + shopId + "','" + operation
            + "','" + UUID.randomUUID() + "','" + payload + "',"
            + "'" + status + "','req-" + operation + "','CONFIRMED_APPLIED',1)");
  }

  SaveContentSnapshotRequest snapshot(String title, String bullets) {
    return new SaveContentSnapshotRequest(
        "en-US", title, bullets, "平台描述原文", "[\"https://img/1.jpg\"]", "",
        "[{\"module\":\"标准 A+\"}]", "{\"color\":\"Black\"}", "{\"battery\":\"锂离子\"}",
        "SYNC", CAPTURED_AT, Boolean.TRUE);
  }

  // ==================================================================
  // 1. 四个跨服务缺口不伪造
  // ==================================================================

  @Test
  @DisplayName("4.1.1.2 售价 / 内部可售量 / 内容草稿 / 销量全部 available=false + 归属 + 影响，详情 JSON 无价格键")
  void crossServiceGapsAreDeclaredNotFaked() throws Exception {
    var caps = as(actorA, "channel.operation.read", () -> service.capabilities());

    for (String key : List.of("price", "internal_stock", "internal_content_draft", "sales")) {
      var dim = caps.dimensions().stream().filter(d -> d.dimension().equals(key)).findFirst();
      assertThat(dim).as("维度 %s 必须存在", key).isPresent();
      assertThat(dim.get().available()).as("%s 必须 available=false", key).isFalse();
      assertThat(dim.get().ownerModule()).as("%s 必须写明归属", key).isNotBlank();
      assertThat(dim.get().impact()).as("%s 必须写明不交付的影响", key).isNotBlank();
    }
    assertThat(caps.priceAvailable()).isFalse();
    assertThat(caps.priceOwnerModule()).contains("05");
    assertThat(caps.internalStockAvailable()).isFalse();
    assertThat(caps.internalStockOwnerModule()).contains("07");
    assertThat(caps.contentDraftAvailable()).isFalse();
    assertThat(caps.contentDraftOwnerModule()).contains("4.2.1.1");
    assertThat(caps.salesAvailable()).isFalse();
    assertThat(caps.salesOwnerModule()).contains("09");
    // 四条界面措辞都要有，前端原样展示
    assertThat(caps.priceNote()).isNotBlank();
    assertThat(caps.internalStockNote()).isNotBlank();
    assertThat(caps.contentDraftNote()).isNotBlank();
    assertThat(caps.salesNote()).isNotBlank();
    // 编辑入口与回写按钮：不可用但必须写明归属（按钮禁用 + 说明，而不是点了没反应）
    assertThat(caps.editSupported()).isFalse();
    assertThat(caps.editOwnerModule()).contains("4.2.1.1");
    assertThat(caps.editReason()).isNotBlank();
    assertThat(caps.writebackSupported()).isFalse();
    assertThat(caps.writebackOwnerModule()).contains("4.3.2.1");
    assertThat(caps.writebackReason()).isNotBlank();

    UUID shop = seedShop(a, "GAP-AMZ");
    var row = save(actorA, listingRequest(shop, "GAP-1", "缺口测试", "ON_SALE", "IN_STOCK",
        new BigDecimal("5"), List.of()));

    // 🔴 详情响应里不能出现任何「售价」键：有键就得填值，填 0 会被读成「卖 0 块」
    http.perform(get("/channel/listing-details/" + row.id()).header("Authorization", bearerA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.profile.price").doesNotExist())
        .andExpect(jsonPath("$.profile.salePrice").doesNotExist())
        .andExpect(jsonPath("$.profile.amount").doesNotExist())
        .andExpect(jsonPath("$.profile.cost").doesNotExist())
        .andExpect(jsonPath("$.profile.internalStock").doesNotExist())
        .andExpect(jsonPath("$.profile.sales").doesNotExist());

    // 差异表里这三行是 UNAVAILABLE，**绝不是 SAME**
    var report = as(actorA, "channel.operation.read", () -> service.diff(row.id().toString()));
    for (String dim : List.of("price", "internal_stock", "platform_stock_state")) {
      var r = rowOf(report, dim);
      assertThat(r.available()).as("%s 不可比对", dim).isFalse();
      assertThat(r.status()).as("%s 必须是 UNAVAILABLE 而不是 SAME", dim).isEqualTo("UNAVAILABLE");
      assertThat(r.ownerModule()).as("%s 必须写明归属", dim).isNotBlank();
      assertThat(r.impact()).as("%s 必须写明影响", dim).isNotBlank();
      assertThat(r.internalKnown()).as("%s 右列不得有值", dim).isFalse();
      assertThat(r.internalValue()).as("%s 右列不得有值", dim).isNull();
    }
    assertThat(report.unavailableCount()).isGreaterThanOrEqualTo(11);
  }

  // ==================================================================
  // 2. 「缺一侧」≠「相同」
  // ==================================================================

  @Test
  @DisplayName("4.1.1.2 只有平台侧有值时状态是 PLATFORM_ONLY，不是 SAME")
  void missingSideIsNotSame() {
    UUID shop = seedShop(a, "ONLY-AMZ");
    var row = save(actorA, listingRequest(shop, "ONLY-1", "只有平台侧", "ON_SALE", "IN_STOCK",
        new BigDecimal("3"), List.of()));

    // 未登记为可回写目标 ⇒ 登记簿那一侧没有值
    var r = rowOf(as(actorA, "channel.operation.read", () -> service.diff(row.id().toString())),
        "platform_stock_vs_registry");
    assertThat(r.platformKnown()).isTrue();
    assertThat(r.platformValue()).isEqualTo("3");
    assertThat(r.internalKnown()).isFalse();
    assertThat(r.status()).isEqualTo("PLATFORM_ONLY");
    assertThat(r.status()).isNotEqualTo("SAME");

    // 也没登记过内容快照 ⇒ 内容对照整列都没有值，但仍然**不可比对**（而不是「一致」）
    var content = as(actorA, "channel.operation.read", () -> service.content(row.id().toString()));
    assertThat(content.snapshot()).isNull();
    assertThat(content.internalDraftAvailable()).isFalse();
    assertThat(content.fields()).allSatisfy(f -> {
      assertThat(f.internalKnown()).as("%s 右列恒未接入", f.field()).isFalse();
      assertThat(f.internalValue()).as("%s 右列恒无值", f.field()).isNull();
      assertThat(f.ownerModule()).contains("4.2.1.1");
    });
    // 标题在没快照时回退到 listing.title —— 那是平台侧事实，落在左列是对的
    assertThat(content.fields().get(0).platformValue()).isEqualTo("只有平台侧");
  }

  // ==================================================================
  // 3. 差异是真的算出来的
  // ==================================================================

  @Test
  @DisplayName("4.1.1.2 平台回报可售量 vs 回写登记簿快照：不等即 DIFFERENT（这是本批次唯一能算的库存落差）")
  void platformStockVsRegistryIsComputed() {
    UUID shop = seedShop(a, "REG-AMZ");
    var row = save(actorA, listingRequest(shop, "REG-1", "登记落差", "ON_SALE", "IN_STOCK",
        new BigDecimal("3"), List.of()));
    seedRegistry(a, shop, "REG-1", "19.9900", "USD", "50.000");

    var diff = rowOf(as(actorA, "channel.operation.read", () -> service.diff(row.id().toString())),
        "platform_stock_vs_registry");
    assertThat(diff.platformValue()).isEqualTo("3");
    assertThat(diff.internalValue()).as("尾随零归一后仍是无损的 50").isEqualTo("50");
    assertThat(diff.available()).isTrue();
    assertThat(diff.status()).isEqualTo("DIFFERENT");
    assertThat(diff.note()).contains("不等于");

    // 把登记簿改成与平台回报一致 ⇒ SAME（证明是真比较，不是写死的结论）
    execAsTenant(a, "update writeback_target set current_stock=3.000"
        + " where tenant_id='" + a + "' and shop_id='" + shop + "' and external_ref='REG-1'");
    var same = rowOf(as(actorA, "channel.operation.read", () -> service.diff(row.id().toString())),
        "platform_stock_vs_registry");
    assertThat(same.status()).isEqualTo("SAME");

    // 差异计数真的把这条算进去了
    var report = as(actorA, "channel.operation.read", () -> service.diff(row.id().toString()));
    assertThat(report.differentCount()).isZero();
    assertThat(report.sameCount()).isGreaterThanOrEqualTo(1);
  }

  @Test
  @DisplayName("4.1.1.2 平台在售状态 vs 平台可售状态：在售却缺货 = DIFFERENT（漏单最常见的来源）")
  void saleableConsistencyIsComputed() {
    UUID shop = seedShop(a, "CON-AMZ");
    var onSaleOutOfStock = save(actorA, listingRequest(shop, "CON-1", "在售却缺货", "ON_SALE",
        "OUT_OF_STOCK", null, List.of()));
    var inconsistent = rowOf(
        as(actorA, "channel.operation.read", () -> service.diff(onSaleOutOfStock.id().toString())),
        "saleable_consistency");
    assertThat(inconsistent.status()).isEqualTo("DIFFERENT");
    assertThat(inconsistent.platformValue()).isEqualTo("ON_SALE");
    assertThat(inconsistent.internalValue()).isEqualTo("OUT_OF_STOCK");

    var ok = save(actorA, listingRequest(shop, "CON-2", "在售且有货", "ON_SALE", "IN_STOCK",
        new BigDecimal("7"), List.of()));
    var consistent = rowOf(
        as(actorA, "channel.operation.read", () -> service.diff(ok.id().toString())),
        "saleable_consistency");
    assertThat(consistent.status()).isEqualTo("SAME");

    // 平台没回报可售状态 ⇒ UNKNOWN，不是「有货」
    var unknown = save(actorA, listingRequest(shop, "CON-3", "平台未回报", "ON_SALE", "UNKNOWN",
        null, List.of()));
    assertThat(rowOf(as(actorA, "channel.operation.read", () -> service.diff(unknown.id().toString())),
        "saleable_consistency").status()).isEqualTo("UNKNOWN");
  }

  @Test
  @DisplayName("4.1.1.2 平台变体 vs 内部映射：部分映射 = DIFFERENT，未登记变体 = UNKNOWN")
  void variantMappingDiffIsComputed() {
    UUID shop = seedShop(a, "MAP-AMZ");
    UUID sku = UUID.randomUUID();
    var partial = save(actorA, listingRequest(shop, "MAP-1", "部分映射", "ON_SALE", "IN_STOCK",
        new BigDecimal("2"), List.of(
            new SaveVariantRequest(null, "V-1", "MAPPED", sku, null, "{\"size\":\"L\"}"),
            new SaveVariantRequest(null, "V-2", "UNMAPPED", null, null, "{\"size\":\"M\"}"))));
    var diff = rowOf(as(actorA, "channel.operation.read", () -> service.diff(partial.id().toString())),
        "variant_mapping");
    assertThat(diff.status()).isEqualTo("DIFFERENT");
    assertThat(diff.platformValue()).contains("V-1").contains("V-2");
    assertThat(diff.internalValue()).contains("V-1→" + sku);

    var mapped = save(actorA, listingRequest(shop, "MAP-2", "全部映射", "ON_SALE", "IN_STOCK",
        new BigDecimal("2"), List.of(
            new SaveVariantRequest(null, "V-1", "MAPPED", sku, null, "{}"),
            new SaveVariantRequest(null, "V-2", "MAPPED", UUID.randomUUID(), null, "{}"))));
    assertThat(rowOf(as(actorA, "channel.operation.read", () -> service.diff(mapped.id().toString())),
        "variant_mapping").status()).isEqualTo("SAME");

    // 一个变体都没有 ⇒ UNKNOWN（这不是「全部已映射」）
    var none = save(actorA, listingRequest(shop, "MAP-3", "没有变体", "ON_SALE", "IN_STOCK",
        new BigDecimal("2"), List.of()));
    assertThat(rowOf(as(actorA, "channel.operation.read", () -> service.diff(none.id().toString())),
        "variant_mapping").status()).isEqualTo("UNKNOWN");

    // 全景里的映射聚合与 4.1.1.1 的口径一致
    assertThat(detail(actorA, partial.id()).mappingStatus()).isEqualTo("PARTIAL");
    assertThat(detail(actorA, mapped.id()).mappingStatus()).isEqualTo("MAPPED");
    assertThat(detail(actorA, none.id()).mappingStatus()).isEqualTo("UNMAPPED");
  }

  // ==================================================================
  // 4. 内容对照右列恒未接入 + 快照追加写
  // ==================================================================

  @Test
  @DisplayName("4.1.1.2 内容对照：左列有平台内容，右列恒未接入；快照追加写且「当前」只有一份")
  void contentComparisonRightColumnIsNeverFaked() {
    UUID shop = seedShop(a, "CNT-AMZ");
    var row = save(actorA, listingRequest(shop, "CNT-1", "平台标题", "ON_SALE", "IN_STOCK",
        new BigDecimal("4"), List.of()));

    var written = as(actorA, "channel.operation.create",
        () -> service.saveSnapshot(row.id().toString(), snapshot("平台侧新标题", "[\"轻便\",\"耐用\"]")));
    assertThat(written.current()).isTrue();
    assertThat(written.historyCount()).isEqualTo(1);

    var content = as(actorA, "channel.operation.read", () -> service.content(row.id().toString()));
    assertThat(content.snapshot()).isNotNull();
    assertThat(content.snapshot().language()).isEqualTo("en-US");
    assertThat(content.snapshot().source()).isEqualTo("SYNC");
    assertThat(content.internalDraftAvailable()).isFalse();
    for (ListingContentField f : content.fields()) {
      assertThat(f.internalKnown()).as("%s 右列恒未接入", f.field()).isFalse();
      assertThat(f.internalValue()).as("%s 右列恒无值", f.field()).isNull();
      assertThat(f.platformLabel()).isEqualTo("平台当前内容");
      assertThat(f.internalLabel()).isEqualTo("内部草稿内容");
    }
    assertThat(content.fields().get(0).platformValue()).isEqualTo("平台侧新标题");
    assertThat(content.fields().get(1).platformValue()).contains("轻便").contains("耐用");
    assertThat(content.fields().get(3).platformValue()).contains("https://img/1.jpg");
    assertThat(content.fields().get(5).platformValue()).contains("标准 A+");

    // 登记第二份同语言快照 ⇒ 第一份被 DB 触发器自动降级，历史条数累积
    var second = as(actorA, "channel.operation.create",
        () -> service.saveSnapshot(row.id().toString(),
            new SaveContentSnapshotRequest("en-US", "第二版标题", "[]", "第二版描述", "[]", "",
                "[]", "{}", "{}", "MANUAL", CAPTURED_AT, Boolean.TRUE)));
    assertThat(second.historyCount()).isEqualTo(2);

    var after = as(actorA, "channel.operation.read", () -> service.content(row.id().toString()));
    assertThat(after.fields().get(0).platformValue()).isEqualTo("第二版标题");
    assertThat(after.snapshot().historyCount()).isEqualTo(2);

    // 🔴 绕过服务层硬塞一条同语言 current ⇒ DB 触发器仍保证「当前只有一份」，
    //    旧的那份被自动降级（唯一部分索引只是第二道兜底，不是主要防线）
    execAsTenant(a,
        "insert into listing_content_snapshot (id,tenant_id,listing_id,language,title,is_current,source)"
            + " values ('" + UUID.randomUUID() + "','" + a + "','" + row.id() + "','en-US','硬塞',true,'MANUAL')");
    var afterManual = as(actorA, "channel.operation.read", () -> service.content(row.id().toString()));
    assertThat(afterManual.fields().get(0).platformValue()).as("最新一份成为当前").isEqualTo("硬塞");
    assertThat(afterManual.snapshot().historyCount()).as("追加写不覆盖历史").isEqualTo(3);
  }

  @Test
  @DisplayName("4.1.1.2 内容快照的 JSON 结构错配在服务层被拒（DB 的 CHECK 仍兜底）")
  void malformedContentJsonIsRejected() {
    UUID shop = seedShop(a, "JSON-AMZ");
    var row = save(actorA, listingRequest(shop, "JSON-1", "结构错配", "ON_SALE", "IN_STOCK",
        new BigDecimal("1"), List.of()));

    // 要点必须是数组，给对象 → 422
    var err = catchThrowable(() -> as(actorA, "channel.operation.create",
        () -> service.saveSnapshot(row.id().toString(),
            new SaveContentSnapshotRequest("en-US", "t", "{\"a\":1}", "d", "[]", "", "[]", "{}",
                "{}", "SYNC", CAPTURED_AT, Boolean.TRUE))));
    assertThat(err).isNotNull();
    assertThat(err.getMessage()).contains("数组");

    // 类目属性必须是对象，给数组 → 422
    var err2 = catchThrowable(() -> as(actorA, "channel.operation.create",
        () -> service.saveSnapshot(row.id().toString(),
            new SaveContentSnapshotRequest("en-US", "t", "[]", "d", "[]", "", "[]", "[]", "{}",
                "SYNC", CAPTURED_AT, Boolean.TRUE))));
    assertThat(err2).isNotNull();
    assertThat(err2.getMessage()).contains("对象");

    // 不是合法 JSON → 422
    var err3 = catchThrowable(() -> as(actorA, "channel.operation.create",
        () -> service.saveSnapshot(row.id().toString(),
            new SaveContentSnapshotRequest("en-US", "t", "[坏", "d", "[]", "", "[]", "{}", "{}",
                "SYNC", CAPTURED_AT, Boolean.TRUE))));
    assertThat(err3).isNotNull();
    assertThat(err3.getMessage()).contains("合法");
  }

  // ==================================================================
  // 5. 发布历史
  // ==================================================================

  @Test
  @DisplayName("4.1.1.2 发布历史追加写、引用自己的快照、timestamptz 写回值零漂移")
  void publishHistoryIsAppendOnlyAndTimeAccurate() {
    UUID shop = seedShop(a, "PUB-AMZ");
    var row = save(actorA, listingRequest(shop, "PUB-1", "发布历史", "ON_SALE", "IN_STOCK",
        new BigDecimal("9"), List.of()));
    var snap = as(actorA, "channel.operation.create",
        () -> service.saveSnapshot(row.id().toString(), snapshot("要发布的内容", "[]")));

    var ok = as(actorA, "channel.operation.create",
        () -> service.savePublishRecord(row.id().toString(),
            new SavePublishRecordRequest(snap.id(), "PUBLISH", "SUCCEEDED", "req-1", "",
                PUBLISHED_AT)));
    // 🔴 AGENTS.md：断言写回的值，不只是「没抛异常」
    assertThat(ok.occurredAt().toInstant()).isEqualTo(PUBLISHED_AT.toInstant());
    assertThat(ok.occurredAtEcho()).isEqualTo(PUBLISHED_AT.toString());
    assertThat(ok.snapshotId()).isEqualTo(snap.id());

    as(actorA, "channel.operation.create",
        () -> service.savePublishRecord(row.id().toString(),
            new SavePublishRecordRequest(snap.id(), "CONTENT_WRITEBACK", "FAILED", "req-2",
                "平台拒收：类目属性缺失", PUBLISHED_AT.plusHours(1))));

    var history = as(actorA, "channel.operation.read",
        () -> service.publishHistory(row.id().toString(), 0, 50));
    assertThat(history).hasSize(2);
    assertThat(history.get(0).outcome()).isEqualTo("FAILED");
    assertThat(history.get(0).reason()).contains("类目属性缺失");
    assertThat(history.get(1).outcome()).isEqualTo("SUCCEEDED");
    assertThat(as(actorA, "channel.operation.read",
        () -> service.publishHistoryTotal(row.id().toString()))).isEqualTo(2);

    // 🔴 引用别人的快照被拒（服务层拒 + DB 触发器再兜一道）
    var otherShop = seedShop(a, "PUB-AMZ-2");
    var otherRow = save(actorA, listingRequest(otherShop, "PUB-2", "别的商品", "DRAFT", "UNKNOWN",
        null, List.of()));
    var otherSnap = as(actorA, "channel.operation.create",
        () -> service.saveSnapshot(otherRow.id().toString(), snapshot("别的内容", "[]")));
    var err = catchThrowable(() -> as(actorA, "channel.operation.create",
        () -> service.savePublishRecord(row.id().toString(),
            new SavePublishRecordRequest(otherSnap.id(), "PUBLISH", "SUCCEEDED", "r", "",
                PUBLISHED_AT))));
    assertThat(err).isNotNull();
    assertThat(err.getMessage()).contains("不属于本 listing");

    // 🔴 发布记录不可就地改写：绕过服务层直接 UPDATE 也会被 V175 触发器拒
    var rewrite = catchThrowable(() -> execAsTenant(a,
        "update listing_publish_record set reason='改成成功' where tenant_id='" + a
            + "' and id='" + ok.id() + "'"));
    assertThat(rewrite).as("发布历史不可改写").isNotNull();

    // FAILED / REJECTED 必须写原因
    var noReason = catchThrowable(() -> as(actorA, "channel.operation.create",
        () -> service.savePublishRecord(row.id().toString(),
            new SavePublishRecordRequest(null, "PUBLISH", "FAILED", "r", "", PUBLISHED_AT))));
    assertThat(noReason).isNotNull();
    assertThat(noReason.getMessage()).contains("必须写明原因");
  }

  // ==================================================================
  // 6. 平台操作记录按 payload 精确归属
  // ==================================================================

  @Test
  @DisplayName("4.1.1.2 平台操作记录只含能归属到本商品的：别的商品与不带来源的都不许混进来")
  void platformOperationsAreAttributedPrecisely() {
    UUID shop = seedShop(a, "OP-AMZ");
    var row = save(actorA, listingRequest(shop, "OP-1", "操作记录", "ON_SALE", "IN_STOCK",
        new BigDecimal("3"), List.of()));
    save(actorA, listingRequest(shop, "OP-2", "别的商品", "ON_SALE", "IN_STOCK",
        new BigDecimal("3"), List.of()));

    seedOperation(a, shop, "OP-1", "LISTING", "UPDATE_STOCK", "SUCCEEDED");
    seedOperation(a, shop, "OP-2", "LISTING", "UPDATE_STOCK", "SUCCEEDED");
    // 直接单条派发：不带 targetType / externalRef ⇒ 店铺级事实，无法归属到商品
    seedOperation(a, shop, null, null, "CANCEL_ORDER", "FAILED");

    var ops = as(actorA, "channel.operation.read",
        () -> service.platformOperations(row.id().toString(), 0, 50));
    assertThat(ops).hasSize(1);
    assertThat(ops.get(0).operation()).isEqualTo("UPDATE_STOCK");
    assertThat(ops.get(0).payload()).contains("OP-1");
    // 🔴 店铺级的那条（不带 targetType / externalRef）不属于任何一个商品，不许混进来
    assertThat(ops).noneMatch(o -> "CANCEL_ORDER".equals(o.operation()));
    assertThat(as(actorA, "channel.operation.read",
        () -> service.platformOperationsTotal(row.id().toString()))).isEqualTo(1);

    // capability 里写明了这个完整性边界
    assertThat(as(actorA, "channel.operation.read", () -> service.capabilities()).platformOperationNote())
        .contains("无法归属到具体商品");
  }

  // ==================================================================
  // 7. 跨租户与不存在：逐字相同的 404
  // ==================================================================

  @Test
  @DisplayName("4.1.1.2 跨租户与不存在返回逐字相同的 404")
  void crossTenantAndMissingAreIndistinguishable() {
    UUID shopA = seedShop(a, "NF-AMZ-1");
    UUID shopB = seedShop(b, "NF-AMZ-2");
    var rowB = save(actorB, listingRequest(shopB, "NF-B", "B 的商品", "ON_SALE", "IN_STOCK",
        new BigDecimal("1"), List.of()));
    save(actorA, listingRequest(shopA, "NF-A", "A 的商品", "ON_SALE", "IN_STOCK",
        new BigDecimal("1"), List.of()));

    var cross = catchThrowable(() -> detail(actorA, rowB.id()));
    var missing = catchThrowable(() -> detail(actorA, UUID.randomUUID()));
    assertThat(cross).isNotNull();
    assertThat(missing).isNotNull();
    assertThat(cross.getMessage()).isEqualTo(missing.getMessage());

    // 各子资源同样不可见
    assertThat(catchThrowable(() -> as(actorA, "channel.operation.read",
        () -> service.diff(rowB.id().toString())))).isNotNull();
    assertThat(catchThrowable(() -> as(actorA, "channel.operation.read",
        () -> service.content(rowB.id().toString())))).isNotNull();
    assertThat(catchThrowable(() -> as(actorA, "channel.operation.read",
        () -> service.publishHistory(rowB.id().toString(), 0, 50)))).isNotNull();
    assertThat(catchThrowable(() -> as(actorA, "channel.operation.read",
        () -> service.platformOperations(rowB.id().toString(), 0, 50)))).isNotNull();
    // 写入路径同样挡住：不能往 B 的 listing 上挂快照
    assertThat(catchThrowable(() -> as(actorA, "channel.operation.create",
        () -> service.saveSnapshot(rowB.id().toString(), snapshot("x", "[]"))))).isNotNull();

    // 畸形 id 与不存在同样 404（不泄露「id 格式错了」这种额外信息）
    var malformed = catchThrowable(() -> as(actorA, "channel.operation.read",
        () -> service.detail("not-a-uuid")));
    assertThat(malformed).isNotNull();
    assertThat(malformed.getMessage()).isEqualTo(missing.getMessage());
  }

  // ==================================================================
  // 8. 多租户三断言（AGENTS.md 硬性要求，缺一不可）
  // ==================================================================

  @Test
  @DisplayName("4.1.1.2 断言一：两租户互不可见")
  void tenantsAreIsolated() {
    UUID shopA = seedShop(a, "ISO-AMZ-1");
    UUID shopB = seedShop(b, "ISO-AMZ-2");
    var rowA = save(actorA, listingRequest(shopA, "ISO-A", "A 的商品", "ON_SALE", "IN_STOCK",
        new BigDecimal("2"), List.of()));
    var rowB = save(actorB, listingRequest(shopB, "ISO-B", "B 的商品", "ON_SALE", "IN_STOCK",
        new BigDecimal("2"), List.of()));
    as(actorA, "channel.operation.create",
        () -> service.saveSnapshot(rowA.id().toString(), snapshot("A 的平台内容", "[]")));
    as(actorB, "channel.operation.create",
        () -> service.saveSnapshot(rowB.id().toString(), snapshot("B 的平台内容", "[]")));

    assertThat(detail(actorA, rowA.id()).profile().externalItemId()).isEqualTo("ISO-A");
    assertThat(detail(actorB, rowB.id()).profile().externalItemId()).isEqualTo("ISO-B");
    assertThat(detail(actorA, rowA.id()).contentComparison().get(0).platformValue())
        .isEqualTo("A 的平台内容");
    assertThat(detail(actorB, rowB.id()).contentComparison().get(0).platformValue())
        .isEqualTo("B 的平台内容");

    // 🔴 按主键直查对方的数据读到零行（只在 HTTP 层断言不够：那只证明过滤条件对，
    //    不证明底下的行在当前租户上下文里真的不可见）
    assertThat(countAsTenant(a,
        "select count(*) from listing_content_snapshot where listing_id='" + rowB.id() + "'"))
        .as("A 的上下文里直查 B 的内容快照必须读到零行").isZero();
    assertThat(countAsTenant(a,
        "select count(*) from listing_publish_record where listing_id='" + rowB.id() + "'"))
        .as("A 的上下文里直查 B 的发布历史必须读到零行").isZero();
    // 反向同理
    assertThat(countAsTenant(b,
        "select count(*) from listing_content_snapshot where listing_id='" + rowA.id() + "'"))
        .isZero();
    // 两张新表都是 FORCE RLS（表级 relforcerowsecurity 为真，owner 之外的连接也拦得住）
    assertThat(scalar("select relforcerowsecurity::text from pg_class where relname='listing_content_snapshot'"))
        .as("listing_content_snapshot 必须 FORCE RLS").isEqualTo("true");
    assertThat(scalar("select relforcerowsecurity::text from pg_class where relname='listing_publish_record'"))
        .as("listing_publish_record 必须 FORCE RLS").isEqualTo("true");
    // 同一机制在本租户自己的数据上必须看得见行 —— 否则上面那个「零行」可能只是查询一直返回 0
    assertThat(countAsTenant(a,
        "select count(*) from listing_content_snapshot where listing_id='" + rowA.id() + "'"))
        .as("A 的上下文必须能直查 A 自己的快照（证明上面那个 0 是隔离，不是查询失效）")
        .isEqualTo(1);
  }

  @Test
  @DisplayName("4.1.1.2 断言二：伪造 tenant header 无效（租户只来自已认证成员关系）")
  void forgedTenantHeaderIsIgnored() throws Exception {
    UUID shopA = seedShop(a, "FORGE-AMZ-1");
    UUID shopB = seedShop(b, "FORGE-AMZ-2");
    save(actorA, listingRequest(shopA, "FORGE-A", "A 的商品", "ON_SALE", "IN_STOCK",
        new BigDecimal("1"), List.of()));
    var rowB = save(actorB, listingRequest(shopB, "FORGE-B", "B 的商品", "ON_SALE", "IN_STOCK",
        new BigDecimal("1"), List.of()));

    // A 的会话 + B 的头 ⇒ 仍然读不到 B 的商品
    http.perform(get("/channel/listing-details/" + rowB.id())
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isNotFound());

    // 无会话一律 401
    http.perform(get("/channel/listing-details/capabilities")
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("4.1.1.2 断言三：连接池归还后不串租户（池压到 1，A→B→A 三轮）")
  void pooledConnectionsDoNotBleedTenants() {
    UUID shopA = seedShop(a, "POOL-AMZ-1");
    UUID shopB = seedShop(b, "POOL-AMZ-2");
    var rowA = save(actorA, listingRequest(shopA, "POOL-A", "A 的商品", "ON_SALE", "IN_STOCK",
        new BigDecimal("2"), List.of()));
    var rowB = save(actorB, listingRequest(shopB, "POOL-B", "B 的商品", "ON_SALE", "IN_STOCK",
        new BigDecimal("2"), List.of()));
    seedRegistry(a, shopA, "POOL-A", "10.0000", "USD", "9.000");
    seedRegistry(b, shopB, "POOL-B", "20.0000", "USD", "8.000");

    for (int i = 0; i < 3; i++) {
      assertThat(detail(actorA, rowA.id()).profile().externalItemId()).isEqualTo("POOL-A");
      assertThat(detail(actorB, rowB.id()).profile().externalItemId()).isEqualTo("POOL-B");
      // 回写登记簿必须跟着租户走：串了就会读到别人的价格
      assertThat(detail(actorA, rowA.id()).writebackRegistry().currentPrice())
          .isEqualByComparingTo("10.0000");
      assertThat(detail(actorB, rowB.id()).writebackRegistry().currentPrice())
          .isEqualByComparingTo("20.0000");
      assertThat(rowOf(as(actorA, "channel.operation.read", () -> service.diff(rowA.id().toString())),
          "price").platformValue()).contains("10 USD");
      assertThat(rowOf(as(actorB, "channel.operation.read", () -> service.diff(rowB.id().toString())),
          "price").platformValue()).contains("20 USD");
      assertThat(as(actorA, "channel.operation.read",
          () -> service.publishHistory(rowA.id().toString(), 0, 50))).isEmpty();
      assertThat(as(actorB, "channel.operation.read",
          () -> service.publishHistory(rowB.id().toString(), 0, 50))).isEmpty();
    }
  }

  // ==================================================================
  // 9. 回写登记簿只读 + 未登记不得显示 0
  // ==================================================================

  @Test
  @DisplayName("4.1.1.2 回写登记簿未登记时不得回 0（那会被读成「平台上卖 0 块」）")
  void unregisteredRegistryIsNotZero() {
    UUID shop = seedShop(a, "ZERO-AMZ");
    var row = save(actorA, listingRequest(shop, "ZERO-1", "未登记", "ON_SALE", "IN_STOCK",
        new BigDecimal("4"), List.of()));

    var registry = detail(actorA, row.id()).writebackRegistry();
    assertThat(registry.registered()).isFalse();
    assertThat(registry.currentPrice()).isNull();
    assertThat(registry.currentStock()).isNull();
    assertThat(registry.limitation()).contains("未登记不是 0");
    assertThat(registry.source()).contains("writeback_target");

    // 登记后如实显示，并带来源与局限说明（它是登记簿快照，不是售价结论）
    seedRegistry(a, shop, "ZERO-1", "25.5000", "USD", "4.000");
    var after = detail(actorA, row.id()).writebackRegistry();
    assertThat(after.registered()).isTrue();
    assertThat(after.currentPrice()).isEqualByComparingTo("25.5000");
    assertThat(after.currency()).isEqualTo("USD");
    assertThat(after.limitation()).contains("不是定价系统的售价结论");
    assertThat(after.limitation()).contains("不是内部可售量");

    // 差异表的售价行把登记簿快照放在**平台侧**那一列，且仍然 UNAVAILABLE（05 未交付）
    var price = rowOf(as(actorA, "channel.operation.read", () -> service.diff(row.id().toString())),
        "price");
    assertThat(price.platformValue()).as("尾随零归一后仍是无损的 25.5").isEqualTo("25.5 USD");
    assertThat(price.platformLabel()).contains("回写登记簿");
    assertThat(price.available()).isFalse();
    assertThat(price.status()).isEqualTo("UNAVAILABLE");
    assertThat(price.ownerModule()).contains("05");
  }

  // ==================================================================
  // 10. 全景一次给全
  // ==================================================================

  @Test
  @DisplayName("4.1.1.2 全景一次给全七块：平台原始信息 / 内部映射 / 内容对照 / 差异 / 发布历史 / 平台操作记录 / 状态轨迹")
  void detailCarriesEveryBlueprintSection() {
    UUID shop = seedShop(a, "FULL-AMZ");
    UUID sku = UUID.randomUUID();
    var row = save(actorA, listingRequest(shop, "FULL-1", "全景商品", "ON_SALE", "IN_STOCK",
        new BigDecimal("6"), List.of(
            new SaveVariantRequest(null, "V-1", "MAPPED", sku, null, "{\"size\":\"L\"}"))));
    var snap = as(actorA, "channel.operation.create",
        () -> service.saveSnapshot(row.id().toString(), snapshot("平台全景内容", "[\"要点一\"]")));
    as(actorA, "channel.operation.create",
        () -> service.savePublishRecord(row.id().toString(),
            new SavePublishRecordRequest(snap.id(), "PUBLISH", "SUCCEEDED", "req-x", "",
                PUBLISHED_AT)));
    seedOperation(a, shop, "FULL-1", "LISTING", "UPDATE_STOCK", "SUCCEEDED");
    // 改状态 ⇒ V171 触发器自动落一条 listing_status_event
    as(actorA, "channel.operation.update", () -> listService.batch(
        new ListingBatchRequest("DELIST", List.of(row.id()), null, null, null)));

    var detail = detail(actorA, row.id());

    // 平台原始信息
    assertThat(detail.profile().externalItemId()).isEqualTo("FULL-1");
    assertThat(detail.profile().title()).isEqualTo("全景商品");
    assertThat(detail.profile().shopCode()).isEqualTo("FULL-AMZ");
    // 平台编码从全局码表解析（码表是 V1 的既有种子，本测试不假设它种了什么）
    assertThat(detail.profile().platformCode())
        .as("平台编码必须从全局码表解析出来，而不是猜一个")
        .isEqualTo(scalar("select code from platform where id='" + AMAZON + "'"));
    assertThat(detail.profile().rating()).isEqualByComparingTo("4.7000");
    assertThat(detail.profile().ratingCount()).isEqualByComparingTo("128");
    assertThat(detail.profile().status()).isEqualTo("DELISTED_BY_US");
    assertThat(detail.profile().platformStockState()).isEqualTo("IN_STOCK");

    // 内部映射
    assertThat(detail.variants()).hasSize(1);
    assertThat(detail.variants().get(0).internalSkuId()).isEqualTo(sku);
    assertThat(detail.variants().get(0).attributes()).contains("L");

    // 内容对照 + 差异
    assertThat(detail.contentComparison()).hasSize(8);
    assertThat(detail.diff().rows()).isNotEmpty();
    assertThat(detail.diff().note()).contains("UNAVAILABLE");

    // 发布历史 / 平台操作记录 / 状态轨迹
    assertThat(detail.publishHistory()).hasSize(1);
    assertThat(detail.platformOperations()).hasSize(1);
    assertThat(detail.statusChanges()).isNotEmpty();

    // 未接入维度清单如实带出
    assertThat(detail.unavailableDimensions())
        .contains("price", "internal_stock", "internal_content_draft", "sales");
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
