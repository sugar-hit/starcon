package com.starcon.channel;

import static com.starcon.channel.ListingBundleComponentModels.*;
import static com.starcon.channel.ListingMappingModels.SaveMappingRequest;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
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
 * 4.1.2.3 套装组件映射。
 *
 * <p>本测试类钉住这几组事：
 *
 * <ol>
 *   <li><b>🔴 蓝图加粗句「组件数量必须大于 0」</b>：服务层给出可读原因（422），
 *       <b>绕开服务层直接写 SQL 也写不进 0 或负数</b>（DB 的 CHECK + 触发器兜底）。</li>
 *   <li><b>🔴「缺任一组件时整个套装不可售」</b>：三个组件里只要<b>一个</b>可售量不足，
 *       {@code bundleSellable} 就是 false —— 逐行看另外两个都还有货也没用。
 *       组件数为 0 时同样不可售。</li>
 *   <li><b>🔴 组件 SKU 构成归属校验</b>：不属于所选套装版本的 SKU 写不进来（422），
 *       经 catalog 授权 API 核对（跨服务不建 FK，所以这一层只能靠 API）。</li>
 *   <li><b>🔴 上游不可达 ≠ 全部可售</b>：warehouse 停掉时可售性校验<b>报 503</b>，
 *       绝不返回「能卖」—— 那就是超卖的第一步。</li>
 *   <li><b>「平台变体与不同套装版本对应」</b>：换版生成<b>新绑定</b>并退役旧的；
 *       旧绑定的组件是历史，直接改 SQL 会被触发器拒。</li>
 *   <li><b>DB 层兜底</b>：绕开服务层直接写 SQL 也开不出第二个当前生效绑定。</li>
 *   <li><b>timestamptz 往返不偏 8 小时</b>：断言写回的值逐字相等。</li>
 *   <li><b>多租户三断言</b>（AGENTS.md 硬性要求，缺一不可）。</li>
 * </ol>
 *
 * <p><b>时刻一律用固定的 {@link OffsetDateTime} 字面量钉死</b>，不用 {@code now()}，
 * 也<b>不在中途改时刻</b>（那种写法会让「恰好 N 天」变成「N 天减去当前时刻差」，
 * 于是测试只在早上绿、下午必红）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ListingBundleComponentIntegrationTest {

  static final PostgreSQLContainer<?> pg =
      new PostgreSQLContainer<>("postgres:16-alpine").withUrlParam("sslmode", "disable");
  static final UUID user = UUID.randomUUID();
  static HttpServer identity;
  static HttpServer catalog;
  static HttpServer warehouse;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap();

  /** catalog 桩：某套装版本由哪些组件组成（skuId → quantity）。🔴 全局共享，测试只读不写。 */
  static final Map<UUID, List<StubComponent>> CATALOG_BUNDLES = new ConcurrentHashMap<>();

  /** warehouse 桩：某组件 SKU 在某仓库的可售量。🔴 全局共享。 */
  static final Map<String, BigDecimal> WAREHOUSE_STOCK = new ConcurrentHashMap<>();

  /** 置 true 时 warehouse 桩直接断开连接：用来证明「查不到」不会被读成「可售」。 */
  static volatile boolean warehouseDown = false;

  record StubComponent(UUID skuId, String code, BigDecimal quantity) {}

  /** 🔴 每个用例一对**全新的租户**：共用租户会让前一个用例建的数据进到后一个用例的断言里。 */
  UUID a, b;
  AccessService.Access actorA, actorB;
  String bearerA;

  static final OffsetDateTime V1_FROM = OffsetDateTime.parse("2026-09-01T10:00:00+08:00");
  static final OffsetDateTime V2_FROM = OffsetDateTime.parse("2026-10-01T10:00:00+08:00");
  static final OffsetDateTime SYNCED_AT = OffsetDateTime.parse("2026-08-30T18:30:00+08:00");

  static final UUID AMAZON = UUID.fromString("11111111-1111-1111-1111-111111111101");
  static final UUID WAREHOUSE = UUID.fromString("22222222-2222-2222-2222-222222222201");

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

      // ---- catalog 桩：GET /catalog/versions/{id}/components ----
      catalog = HttpServer.create(new InetSocketAddress(0), 0);
      catalog.createContext(
          "/catalog",
          e -> {
            String path = e.getRequestURI().getPath();
            if (!path.startsWith("/catalog/versions/") || !path.endsWith("/components")) {
              e.sendResponseHeaders(404, -1);
              e.close();
              return;
            }
            String versionId = path.substring("/catalog/versions/".length(), path.length() - "/components".length());
            List<StubComponent> components = CATALOG_BUNDLES.get(UUID.fromString(versionId));
            if (components == null) {
              e.sendResponseHeaders(404, -1);
              e.close();
              return;
            }
            StringBuilder json = new StringBuilder("[");
            for (int i = 0; i < components.size(); i++) {
              StubComponent c = components.get(i);
              if (i > 0) json.append(',');
              json.append("{\"skuId\":\"").append(c.skuId())
                  .append("\",\"skuCode\":\"").append(c.code())
                  .append("\",\"quantity\":").append(c.quantity()).append('}');
            }
            json.append(']');
            byte[] body = json.toString().getBytes(StandardCharsets.UTF_8);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      catalog.start();

      // ---- warehouse 桩：GET /warehouse/inventory/sellable/skus ----
      warehouse = HttpServer.create(new InetSocketAddress(0), 0);
      warehouse.createContext(
          "/warehouse",
          e -> {
            if (warehouseDown) {
              e.close();
              return;
            }
            String query = e.getRequestURI().getRawQuery();
            StringBuilder json = new StringBuilder("{\"rows\":[");
            boolean first = true;
            for (String pair : (query == null ? "" : query).split("&")) {
              if (!pair.startsWith("skuId=")) continue;
              for (String sku : pair.substring("skuId=".length()).split(",")) {
                if (sku.isBlank()) continue;
                BigDecimal qty = WAREHOUSE_STOCK.get(sku);
                if (qty == null) continue; // 这一版组件在该仓库没有库存行
                if (!first) json.append(',');
                first = false;
                json.append("{\"skuId\":\"").append(sku)
                    .append("\",\"sellable\":").append(qty.toPlainString()).append('}');
              }
            }
            json.append("],\"total\":0,\"page\":0,\"size\":0}");
            byte[] body = json.toString().getBytes(StandardCharsets.UTF_8);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      warehouse.start();
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
    warehouseDown = false;
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "channel_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://localhost:" + identity.getAddress().getPort());
    r.add("CATALOG_URL", () -> "http://localhost:" + catalog.getAddress().getPort());
    r.add("WAREHOUSE_URL", () -> "http://localhost:" + warehouse.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
    // 连接池压到 1：多租户串租户在单连接下最容易暴露
    r.add("spring.datasource.hikari.maximum-pool-size", () -> "1");
  }

  @Autowired ListingService listingService;
  @Autowired ListingMappingService mappingService;
  @Autowired ListingBundleComponentService service;
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

  /** 以「已认证的租户操作者」身份执行 SQL：绑定租户 + actor + 权限 + 数据范围。 */
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

  static List<String> execAsTenantRows(UUID tenant, String sql) {
    try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute("select set_config('app.current_tenant','" + tenant + "',false)");
      s.execute("select set_config('app.actor','" + user + "',false)");
      s.execute("select set_config('app.department','',false)");
      s.execute("select set_config('app.permission','channel.operation.create',false)");
      s.execute("select set_config('app.grants','" + ALL_SCOPE_GRANTS + "',false)");
      var rs = s.executeQuery(sql);
      List<String> rows = new ArrayList<>();
      while (rs.next()) rows.add(rs.getString(1));
      return rows;
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

  /** 登记一个平台商品（走 4.1.1.1 的真实写入路径）。 */
  UUID seedListing(
      AccessService.Access who, UUID shopId, String externalItemId, String title) {
    return as(
            who,
            "channel.operation.create",
            () ->
                listingService.save(
                    new ListingModels.SaveListingRequest(
                        null, shopId, null, externalItemId, title, "Electronics", "cat-1",
                        "ON_SALE", null, "", "IN_STOCK", null, null, null, "USD",
                        null, SYNCED_AT,
                        List.of(
                            new ListingModels.SaveVariantRequest(
                                null, "V-1", "UNMAPPED", null, null, "{}")),
                        null)))
        .id();
  }

  UUID variantId(UUID tenant, UUID listingId, String externalVariantId) {
    return UUID.fromString(
        execAsTenantRows(
                tenant,
                "select id from listing_variant where tenant_id='" + tenant + "' and listing_id='"
                    + listingId + "' and external_variant_id='" + externalVariantId + "'")
            .get(0));
  }

  /** 建一条**套装**映射（4.1.2.1 的 ONE_TO_MANY），返回 mappingId。 */
  UUID seedBundleMapping(
      AccessService.Access who, UUID shopId, String externalItemId, UUID bundleSkuId) {
    UUID listingId = seedListing(who, shopId, externalItemId, "套装商品 " + externalItemId);
    UUID variant = variantId(who.tenantId(), listingId, "V-1");
    return as(
            who,
            "channel.operation.create",
            () ->
                mappingService
                    .save(
                        new SaveMappingRequest(
                            null, variant, "ONE_TO_MANY", bundleSkuId,
                            new BigDecimal("1"), "初次建立套装映射", V1_FROM, null))
                    .mapping()
                    .id());
  }

  /** 在 catalog 桩上登记一版套装：returns the version id。 */
  UUID stubBundleVersion(String versionTag, UUID... componentSkus) {
    UUID versionId = UUID.randomUUID();
    List<StubComponent> components = new ArrayList<>();
    for (UUID sku : componentSkus) {
      components.add(new StubComponent(sku, "SKU-" + sku.toString().substring(0, 8),
          BigDecimal.ONE));
    }
    CATALOG_BUNDLES.put(versionId, components);
    assertThat(versionTag).isNotBlank();
    return versionId;
  }

  BundleBindingDetail bind(AccessService.Access who, UUID mappingId, UUID bundleVersionId) {
    return as(
        who,
        "channel.operation.create",
        () ->
            service.saveBinding(
                new SaveBundleBindingRequest(
                    null, mappingId, bundleVersionId, V1_FROM, "选择套装版本", null),
                bearerOf(who)));
  }

  ListingBundleComponentRow addComponent(
      AccessService.Access who, UUID bindingId, UUID skuId, BigDecimal qty) {
    return as(
        who,
        "channel.operation.create",
        () ->
            service.saveComponent(
                new SaveBundleComponentRequest(null, bindingId, skuId, qty, "主机", ""),
                bearerOf(who)));
  }

  String bearerOf(AccessService.Access who) {
    for (var e : actors.entrySet()) {
      if (e.getValue() == who) return e.getKey();
    }
    throw new IllegalStateException("unknown actor");
  }

  ListingBundleSellableCheck check(AccessService.Access who, UUID bindingId) {
    return as(
        who,
        "channel.operation.read",
        () -> service.sellableCheck(bindingId.toString(), WAREHOUSE, bearerOf(who)));
  }

  /** 一个已绑好版、登记好三个组件、库存充足的套装。 */
  record Fixture(UUID mappingId, UUID bundleVersionId, UUID bindingId, List<UUID> skus) {}

  Fixture fullBundle(AccessService.Access who, UUID shopId, String tag) {
    UUID mappingId = seedBundleMapping(who, shopId, "BDL-" + tag, UUID.randomUUID());
    UUID skuA = UUID.randomUUID();
    UUID skuB = UUID.randomUUID();
    UUID skuC = UUID.randomUUID();
    UUID versionId = stubBundleVersion("v1-" + tag, skuA, skuB, skuC);
    UUID bindingId = bind(who, mappingId, versionId).binding().id();
    addComponent(who, bindingId, skuA, new BigDecimal("2"));
    addComponent(who, bindingId, skuB, new BigDecimal("1"));
    addComponent(who, bindingId, skuC, new BigDecimal("1"));
    WAREHOUSE_STOCK.put(skuA.toString(), new BigDecimal("100"));
    WAREHOUSE_STOCK.put(skuB.toString(), new BigDecimal("100"));
    WAREHOUSE_STOCK.put(skuC.toString(), new BigDecimal("100"));
    return new Fixture(mappingId, versionId, bindingId, List.of(skuA, skuB, skuC));
  }

  // ==================================================================
  // 1. 🔴 组件数量必须大于 0（服务层 + DB 双重）
  // ==================================================================

  @Test
  @DisplayName("4.1.2.3 组件数量必须大于 0：服务层给可读原因，DB 层直接写 SQL 也写不进 0")
  void componentQuantityMustBePositive() {
    UUID shopA = seedShop(a, "QTY-AMZ");
    UUID mappingId = seedBundleMapping(actorA, shopA, "QTY-A", UUID.randomUUID());
    UUID skuA = UUID.randomUUID();
    UUID versionId = stubBundleVersion("qty", skuA);
    UUID bindingId = bind(actorA, mappingId, versionId).binding().id();

    // 服务层：0 与负数都给出可读原因（不是「参数错误」这种没有下一步的话）
    assertThatThrownBy(() -> addComponent(actorA, bindingId, skuA, BigDecimal.ZERO))
        .hasMessageContaining("组件数量必须大于 0");
    assertThatThrownBy(() -> addComponent(actorA, bindingId, skuA, new BigDecimal("-1")))
        .hasMessageContaining("组件数量必须大于 0");
    assertThatThrownBy(() -> addComponent(actorA, bindingId, skuA, null))
        .hasMessageContaining("组件数量必须大于 0");

    // 🔴 DB 层兜底：绕开服务层直接写 SQL 同样写不进
    assertThatThrownBy(
            () ->
                execAsTenant(
                    a,
                    "insert into listing_bundle_component"
                        + " (id,tenant_id,binding_id,component_sku_id,quantity) values ('"
                        + UUID.randomUUID()
                        + "','"
                        + a
                        + "','"
                        + bindingId
                        + "','"
                        + UUID.randomUUID()
                        + "',0)"))
        .hasMessageContaining("组件数量必须大于 0");

    // 合法数量写得进去，且是 BigDecimal 往返不丢精度（NUMERIC(19,6)）
    ListingBundleComponentRow ok =
        addComponent(actorA, bindingId, skuA, new BigDecimal("0.500000"));
    assertThat(ok.quantity()).isEqualByComparingTo("0.5");
  }

  // ==================================================================
  // 2. 🔴 缺任一组件时整个套装不可售
  // ==================================================================

  @Test
  @DisplayName("4.1.2.3 缺任一组件时整个套装不可售：三个组件里一个不够，整套就 false")
  void oneShortComponentMakesWholeBundleUnsellable() {
    UUID shopA = seedShop(a, "SELL-AMZ");
    Fixture f = fullBundle(actorA, shopA, "SELL");

    // 三者都充足 ⇒ 可售
    assertThat(check(actorA, f.bindingId()).bundleSellable()).isTrue();
    assertThat(check(actorA, f.bindingId()).componentCount()).isEqualTo(3);
    assertThat(check(actorA, f.bindingId()).unsellableCount()).isZero();

    // 🔴 只让第三个组件不够：逐行看前两个都还有货，整套仍然不可售
    WAREHOUSE_STOCK.put(f.skus().get(2).toString(), BigDecimal.ZERO);
    ListingBundleSellableCheck shortOne = check(actorA, f.bindingId());
    assertThat(shortOne.components()).extracting(ListingBundleComponentSellable::sellable)
        .containsExactly(true, true, false);
    assertThat(shortOne.bundleSellable()).isFalse();
    assertThat(shortOne.unsellableCount()).isEqualTo(1);
    assertThat(shortOne.reason()).contains("缺任一组件");
    assertThat(shortOne.components().get(2).reason()).isNotBlank();

    // 可售量刚好等于每件用量 ⇒ 可售（边界：≥ 而不是 >）
    WAREHOUSE_STOCK.put(f.skus().get(2).toString(), new BigDecimal("1"));
    assertThat(check(actorA, f.bindingId()).bundleSellable()).isTrue();
    // 少一点点就不行
    WAREHOUSE_STOCK.put(f.skus().get(2).toString(), new BigDecimal("0.999999"));
    assertThat(check(actorA, f.bindingId()).bundleSellable()).isFalse();

    // 能成套卖几件 = 可售量 ÷ 每件用量，向下取整
    WAREHOUSE_STOCK.put(f.skus().get(2).toString(), new BigDecimal("7"));
    assertThat(check(actorA, f.bindingId()).components().get(2).setsAvailable())
        .isEqualByComparingTo("7");
    WAREHOUSE_STOCK.put(f.skus().get(0).toString(), new BigDecimal("5")); // 每件需 2
    assertThat(check(actorA, f.bindingId()).components().get(0).setsAvailable())
        .isEqualByComparingTo("2"); // 5 ÷ 2 = 2.5 → 向下取整
  }

  @Test
  @DisplayName("4.1.2.3 一个组件都没登记 ⇒ 整套不可售（缺组件），且不会去空查 warehouse")
  void bundleWithoutComponentsIsUnsellable() {
    UUID shopA = seedShop(a, "EMPTY-AMZ");
    UUID mappingId = seedBundleMapping(actorA, shopA, "EMPTY-A", UUID.randomUUID());
    UUID versionId = stubBundleVersion("empty");
    UUID bindingId = bind(actorA, mappingId, versionId).binding().id();

    ListingBundleSellableCheck check = check(actorA, bindingId);
    assertThat(check.componentCount()).isZero();
    assertThat(check.bundleSellable()).isFalse();
    assertThat(check.reason()).contains("还没有登记任何组件");
    assertThat(check.components()).isEmpty();
  }

  @Test
  @DisplayName("4.1.2.3 warehouse 查不到 ⇒ 组件 SKU 在该仓库没有库存行，可售量按 0 计")
  void missingInventoryRowMeansZeroSellable() {
    UUID shopA = seedShop(a, "NOROW-AMZ");
    UUID mappingId = seedBundleMapping(actorA, shopA, "NOROW-A", UUID.randomUUID());
    UUID skuA = UUID.randomUUID();
    UUID versionId = stubBundleVersion("norow", skuA);
    UUID bindingId = bind(actorA, mappingId, versionId).binding().id();
    addComponent(actorA, bindingId, skuA, new BigDecimal("1"));
    // 故意不给 WAREHOUSE_STOCK 登记 ⇒ 桩不返回这一行

    ListingBundleSellableCheck check = check(actorA, bindingId);
    assertThat(check.components().get(0).sellableQuantity()).isEqualByComparingTo("0");
    assertThat(check.components().get(0).sellable()).isFalse();
    assertThat(check.bundleSellable()).isFalse();
    assertThat(check.components().get(0).reason()).contains("查不到这个组件 SKU 的库存行");
  }

  // ==================================================================
  // 3. 🔴 上游不可达 ≠ 全部可售
  // ==================================================================

  @Test
  @DisplayName("4.1.2.3 warehouse 不可达 ⇒ 报 503，绝不返回「全部可售」")
  void warehouseUnavailableIsNeverReportedAsSellable() {
    UUID shopA = seedShop(a, "DOWN-AMZ");
    Fixture f = fullBundle(actorA, shopA, "DOWN");

    // 先确认健康时确实可售，避免下面的断言在「本来就不可售」时也成立
    assertThat(check(actorA, f.bindingId()).bundleSellable()).isTrue();

    warehouseDown = true;
    try {
      assertThatThrownBy(() -> check(actorA, f.bindingId()))
          .hasMessageContaining("warehouse")
          .hasMessageContaining("不可达");
    } finally {
      warehouseDown = false;
    }

    // 恢复后仍可售：证明前面的 503 不是数据被改坏了
    assertThat(check(actorA, f.bindingId()).bundleSellable()).isTrue();
  }

  // ==================================================================
  // 4. 🔴 组件 SKU 构成归属校验
  // ==================================================================

  @Test
  @DisplayName("4.1.2.3 组件 SKU 必须属于所选套装版本（经 catalog 授权 API 核对）")
  void componentSkuMustBelongToTheBundleVersion() {
    UUID shopA = seedShop(a, "IDENT-AMZ");
    UUID mappingId = seedBundleMapping(actorA, shopA, "IDENT-A", UUID.randomUUID());
    UUID member = UUID.randomUUID();
    UUID stranger = UUID.randomUUID();
    UUID versionId = stubBundleVersion("ident", member);
    UUID bindingId = bind(actorA, mappingId, versionId).binding().id();

    // 不在这一版里的 SKU：写不进来，并把上游的构成如实列出来
    assertThatThrownBy(() -> addComponent(actorA, bindingId, stranger, BigDecimal.ONE))
        .hasMessageContaining("不属于所选套装版本");

    // 在这一版里的：写得进去
    assertThat(addComponent(actorA, bindingId, member, BigDecimal.ONE).componentSkuId())
        .isEqualTo(member);
  }

  @Test
  @DisplayName("4.1.2.3 套装版本在 catalog 不存在 ⇒ 422，不是「空组件」")
  void unknownBundleVersionIsRejected() {
    UUID shopA = seedShop(a, "NOVER-AMZ");
    UUID mappingId = seedBundleMapping(actorA, shopA, "NOVER-A", UUID.randomUUID());
    UUID ghost = UUID.randomUUID(); // 从没在 catalog 桩上登记过

    assertThatThrownBy(() -> bind(actorA, mappingId, ghost))
        .hasMessageContaining("套装版本不存在");
  }

  @Test
  @DisplayName("4.1.2.3 非套装映射（一对一）没有组件可言")
  void nonBundleMappingCannotHaveComponents() {
    UUID shopA = seedShop(a, "ONETOONE-AMZ");
    UUID listingId = seedListing(actorA, shopA, "OTO-A", "一对一商品");
    UUID variant = variantId(a, listingId, "V-1");
    UUID mappingId =
        as(actorA, "channel.operation.create",
            () -> mappingService.save(
                    new SaveMappingRequest(null, variant, "ONE_TO_ONE", UUID.randomUUID(), null,
                        "一对一映射", V1_FROM, null))
                .mapping().id());
    UUID versionId = stubBundleVersion("oto", UUID.randomUUID());

    assertThatThrownBy(() -> bind(actorA, mappingId, versionId))
        .hasMessageContaining("只有一对多（套装）映射才有组件");
  }

  // ==================================================================
  // 5. 平台变体与不同套装版本对应（换版不就地改）
  // ==================================================================

  @Test
  @DisplayName("4.1.2.3 换版生成新绑定：旧的退役、组件成历史，当前生效只有一个")
  void switchingBundleVersionRetiresTheOldBinding() {
    UUID shopA = seedShop(a, "SWITCH-AMZ");
    Fixture f = fullBundle(actorA, shopA, "SWITCH");

    UUID skuA2 = UUID.randomUUID();
    UUID skuB2 = UUID.randomUUID();
    UUID v2 = stubBundleVersion("v2-switch", skuA2, skuB2);

    BundleBindingDetail switched =
        as(actorA, "channel.operation.create",
            () -> service.saveBinding(
                new SaveBundleBindingRequest(
                    null, f.mappingId(), v2, V2_FROM, "内部套装升版，跟着换", V2_FROM),
                bearerOf(actorA)));

    // 新绑定是当前生效，旧绑定已退役
    assertThat(switched.binding().current()).isTrue();
    assertThat(switched.binding().bundleVersionId()).isEqualTo(v2);

    ListingBundleBindingPage page =
        as(actorA, "channel.operation.read", () -> service.listBindings(
            f.mappingId(), null, null, null, null, "CURRENT", null, "EFFECTIVE", 0, 50));
    assertThat(page.total()).isEqualTo(1);
    assertThat(page.items().get(0).id()).isEqualTo(switched.binding().id());

    ListingBundleBindingPage all =
        as(actorA, "channel.operation.read", () -> service.listBindings(
            f.mappingId(), null, null, null, null, null, null, "EFFECTIVE", 0, 50));
    assertThat(all.total()).isEqualTo(2);
    assertThat(all.items()).extracting(ListingBundleBindingRow::current).containsExactly(true, false);

    // 🔴 旧绑定的组件是历史：直接改 SQL 会被触发器拒
    assertThatThrownBy(
            () ->
                execAsTenant(
                    a,
                    "update listing_bundle_component set quantity=999 where binding_id='"
                        + f.bindingId()
                        + "'"))
        .hasMessageContaining("history");
  }

  @Test
  @DisplayName("4.1.2.3 DB 层：同一映射开不出第二个当前生效绑定；已退役绑定补组件也被拒")
  void dbLevelGuardsHold() {
    UUID shopA = seedShop(a, "GUARD-AMZ");
    Fixture f = fullBundle(actorA, shopA, "GUARD");

    // 同一绑定下同一 SKU 不允许两行（放在退役之前：退役后的绑定先被「只读」挡住，
    // 那条路径由下面的直接写 SQL 覆盖）
    assertThatThrownBy(() -> addComponent(actorA, f.bindingId(), f.skus().get(0), BigDecimal.ONE))
        .hasMessageContaining("已经登记过了");

    // 部分唯一索引：绕开服务层直接插第二个 current 绑定
    assertThatThrownBy(
            () ->
                execAsTenant(
                    a,
                    "insert into listing_bundle_binding"
                        + " (id,tenant_id,mapping_id,bundle_version_id,effective_from,change_reason)"
                        + " values ('"
                        + UUID.randomUUID()
                        + "','"
                        + a
                        + "','"
                        + f.mappingId()
                        + "','"
                        + UUID.randomUUID()
                        + "','"
                        + V2_FROM
                        + "','绕过服务层')"))
        .hasMessageContaining("listing_bundle_binding_current");

    // 把旧的退役之后，再直接补组件
    execAsTenant(a,
        "update listing_bundle_binding set effective_to='" + V2_FROM + "' where id='" + f.bindingId() + "'");
    assertThatThrownBy(
            () ->
                execAsTenant(
                    a,
                    "insert into listing_bundle_component"
                        + " (id,tenant_id,binding_id,component_sku_id,quantity) values ('"
                        + UUID.randomUUID()
                        + "','"
                        + a
                        + "','"
                        + f.bindingId()
                        + "','"
                        + UUID.randomUUID()
                        + "',1)"))
        .hasMessageContaining("history");
  }

  // ==================================================================
  // 6. timestamptz 往返
  // ==================================================================

  @Test
  @DisplayName("4.1.2.3 timestamptz 往返不偏 8 小时（逐字相等）")
  void effectiveFromRoundTripsExactly() {
    UUID shopA = seedShop(a, "TIME-AMZ");
    UUID mappingId = seedBundleMapping(actorA, shopA, "TIME-A", UUID.randomUUID());
    UUID versionId = stubBundleVersion("time", UUID.randomUUID());
    BundleBindingDetail bound =
        bind(actorA, mappingId, versionId);

    // 🔴 断言的是**时刻本身**，不是它的字面格式：容器时区不同，写回值的渲染就不同，
    //   断言 "T" 分隔符会让这个测试只在某个时区绿。
    String stored =
        execAsTenantRows(a, "select effective_from from listing_bundle_binding where id='"
                + bound.binding().id() + "'")
            .get(0);
    assertThat(OffsetDateTime.parse(stored.replace(' ', 'T')).toInstant())
        .isEqualTo(V1_FROM.toInstant());

    BundleBindingDetail reread =
        as(actorA, "channel.operation.read", () -> service.detail(bound.binding().id().toString()));
    assertThat(reread.binding().effectiveFrom().toInstant())
        .isEqualTo(V1_FROM.toInstant());
  }

  // ==================================================================
  // 7. 端点
  // ==================================================================

  @Test
  @DisplayName("4.1.2.3 端点可用：列表 / 详情 / 组件 / 可售性校验 / 能力声明")
  void endpointsWork() throws Exception {
    UUID shopA = seedShop(a, "EP-AMZ");
    Fixture f = fullBundle(actorA, shopA, "EP");

    http.perform(get("/channel/bundle-components/capabilities").header("Authorization", bearerA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.dimensions").isArray())
        // 构成冻结快照与按组件预占如实报不可用，不返回空列表冒充
        .andExpect(jsonPath("$.dimensions[6].dimension").value("component_snapshot"))
        .andExpect(jsonPath("$.dimensions[6].available").value(false))
        .andExpect(jsonPath("$.dimensions[7].dimension").value("inventory_reservation"))
        .andExpect(jsonPath("$.dimensions[7].available").value(false))
        .andExpect(jsonPath("$.gapNote").isNotEmpty());

    http.perform(get("/channel/bundle-components/filter-options").header("Authorization", bearerA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.maxPageSize").isNumber());

    http.perform(get("/channel/bundle-components/bindings")
            .header("Authorization", bearerA)
            .param("mappingId", f.mappingId().toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.items[0].componentCount").value(3))
        .andExpect(jsonPath("$.items[0].totalQuantity").value(4.0));

    http.perform(get("/channel/bundle-components/bindings/" + f.bindingId())
            .header("Authorization", bearerA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.components.length()").value(3))
        .andExpect(jsonPath("$.note").isNotEmpty());

    http.perform(get("/channel/bundle-components/components")
            .header("Authorization", bearerA)
            .param("bindingId", f.bindingId().toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(3));

    http.perform(get("/channel/bundle-components/bindings/" + f.bindingId() + "/sellable-check")
            .header("Authorization", bearerA)
            .param("warehouseId", WAREHOUSE.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.bundleSellable").value(true))
        .andExpect(jsonPath("$.componentCount").value(3));

    // 🔴 目标仓库必填：可售量按仓库计算，不指定就没有唯一答案
    http.perform(get("/channel/bundle-components/bindings/" + f.bindingId() + "/sellable-check")
            .header("Authorization", bearerA))
        .andExpect(status().isBadRequest());

    // 删一条组件：响应带删除前的清单，界面能立刻看出还剩什么
    UUID componentId = as(actorA, "channel.operation.read",
        () -> service.listComponents(f.bindingId(), null, null, 0, 50)).items().get(0).id();
    http.perform(delete("/channel/bundle-components/components/" + componentId)
            .header("Authorization", bearerA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.componentsBeforeRemoval.length()").value(3))
        .andExpect(jsonPath("$.note").isNotEmpty());

    assertThat(check(actorA, f.bindingId()).componentCount()).isEqualTo(2);

    // 无会话一律 401
    http.perform(get("/channel/bundle-components/bindings")).andExpect(status().isUnauthorized());
  }

  // ==================================================================
  // 8. 多租户三断言（AGENTS.md 硬性要求，缺一不可）
  // ==================================================================

  @Test
  @DisplayName("4.1.2.3 断言一：两租户互不可见")
  void tenantsAreIsolated() {
    UUID shopA = seedShop(a, "ISO-AMZ-1");
    UUID shopB = seedShop(b, "ISO-AMZ-2");
    Fixture fa = fullBundle(actorA, shopA, "ISO-A");
    Fixture fb = fullBundle(actorB, shopB, "ISO-B");

    var pageA = as(actorA, "channel.operation.read",
        () -> service.listBindings(null, null, null, null, null, "CURRENT", null, "RECENT", 0, 50));
    var pageB = as(actorB, "channel.operation.read",
        () -> service.listBindings(null, null, null, null, null, "CURRENT", null, "RECENT", 0, 50));

    assertThat(pageA.total()).isEqualTo(1);
    assertThat(pageB.total()).isEqualTo(1);
    assertThat(pageA.items()).extracting(ListingBundleBindingRow::id).containsExactly(fa.bindingId());
    assertThat(pageB.items()).extracting(ListingBundleBindingRow::id).containsExactly(fb.bindingId());
    assertThat(pageA.items()).extracting(ListingBundleBindingRow::shopCode).containsOnly("ISO-AMZ-1");

    // 组件列表同样按租户隔离：按 B 的绑定过滤，A 查出 0 条（不是「B 的一条」）
    assertThat(as(actorA, "channel.operation.read",
        () -> service.listComponents(fb.bindingId(), null, null, 0, 50)).total()).isZero();
    assertThat(as(actorB, "channel.operation.read",
        () -> service.listComponents(fb.bindingId(), null, null, 0, 50)).total()).isEqualTo(3);

    // 详情与可售性校验同样隔离
    assertThatThrownBy(() -> as(actorA, "channel.operation.read",
        () -> service.detail(fb.bindingId().toString())))
        .hasMessageContaining("不存在");
    assertThatThrownBy(() -> check(actorA, fb.bindingId()))
        .hasMessageContaining("不存在");
    assertThat(as(actorA, "channel.operation.read",
        () -> service.detail(fa.bindingId().toString())).binding().id())
        .isEqualTo(fa.bindingId());
  }

  @Test
  @DisplayName("4.1.2.3 断言二：伪造 tenant header 无效（租户只来自已认证成员关系）")
  void forgedTenantHeaderIsIgnored() throws Exception {
    UUID shopA = seedShop(a, "FORGE-AMZ-1");
    UUID shopB = seedShop(b, "FORGE-AMZ-2");
    Fixture fa = fullBundle(actorA, shopA, "FORGE-A");
    Fixture fb = fullBundle(actorB, shopB, "FORGE-B");

    // A 的会话 + B 的头 ⇒ 仍然只看得见 A 的数据
    http.perform(get("/channel/bundle-components/bindings")
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.items[0].shopCode").value("FORGE-AMZ-1"));

    // 伪造租户头也不能读到 B 的绑定详情
    http.perform(get("/channel/bundle-components/bindings/" + fb.bindingId())
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isNotFound());

    // 伪造租户头也不能读到 B 的组件
    http.perform(get("/channel/bundle-components/components")
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", b.toString())
            .param("bindingId", fb.bindingId().toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(0));

    // 无会话一律 401
    http.perform(get("/channel/bundle-components/bindings").header("X-Tenant-Id", b.toString()))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("4.1.2.3 断言三：连接池归还后不串租户（池压到 1，A→B→A 三轮）")
  void pooledConnectionsDoNotBleedTenants() {
    UUID shopA = seedShop(a, "POOL-AMZ-1");
    UUID shopB = seedShop(b, "POOL-AMZ-2");
    Fixture fa = fullBundle(actorA, shopA, "POOL-A");
    Fixture fb = fullBundle(actorB, shopB, "POOL-B");

    for (int i = 0; i < 3; i++) {
      var pageA = as(actorA, "channel.operation.read",
          () -> service.listBindings(null, null, null, null, null, "CURRENT", null, "RECENT", 0, 50));
      var pageB = as(actorB, "channel.operation.read",
          () -> service.listBindings(null, null, null, null, null, "CURRENT", null, "RECENT", 0, 50));

      assertThat(pageA.total()).isEqualTo(1);
      assertThat(pageB.total()).isEqualTo(1);
      assertThat(pageA.items()).extracting(ListingBundleBindingRow::id).containsExactly(fa.bindingId());
      assertThat(pageB.items()).extracting(ListingBundleBindingRow::id).containsExactly(fb.bindingId());

      // 组件与可售性校验走同一个连接池
      assertThat(as(actorA, "channel.operation.read",
          () -> service.listComponents(fa.bindingId(), null, null, 0, 50)).total()).isEqualTo(3);
      assertThat(as(actorA, "channel.operation.read",
          () -> service.listComponents(fb.bindingId(), null, null, 0, 50)).total()).isZero();
      assertThat(check(actorA, fa.bindingId()).componentCount()).isEqualTo(3);
      assertThatThrownBy(() -> check(actorA, fb.bindingId()))
          .hasMessageContaining("不存在");
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
    if (catalog != null) catalog.stop(0);
    if (warehouse != null) warehouse.stop(0);
    pg.stop();
  }
}
