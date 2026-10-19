package com.starcon.channel;

import static com.starcon.channel.ListingMappingModels.SaveMappingRequest;
import static com.starcon.channel.ListingMappingVersionModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
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
 * 4.1.2.2 映射版本。
 *
 * <p>本测试类钉住这几组事：
 *
 * <ol>
 *   <li><b>🔴 核心不变量「改映射不影响已产生的订单」的可查侧</b>：改两次映射之后，
 *       前两版的 <b>internalSkuId / effectiveFrom / effectiveTo / changeReason / 商品范围快照
 *       逐字不变</b>，并且 DB 层直接 UPDATE 已关闭的版本行会被拒。
 *       本模块不写版本行（那是 4.1.2.1），但<b>回溯得到的那一份必须是真的</b>，
 *       否则这一条不变量在查询面就漏了。</li>
 *   <li><b>🔴 受影响订单范围如实报不可用</b>：{@code available=false}、
 *       {@code items} 恒为 <b>null</b>（不是空数组）、归属模块指向 09 订单 / trade。</li>
 *   <li><b>🔴 商品范围是冻结快照</b>：版本落库后把 listing 标题改掉，
 *       快照仍是旧标题、当前值是新标题、{@code driftedFromPlatform=true}。</li>
 *   <li><b>DB 层兜底</b>：绕开服务层直接写 SQL 也改不动商品范围快照。</li>
 *   <li><b>版本列表是真分页 + 真筛选</b>：生效区间是<b>重叠</b>语义。</li>
 *   <li><b>timestamptz 往返不偏 8 小时</b>：断言写回的值逐字相等。</li>
 *   <li><b>多租户三断言</b>（AGENTS.md 硬性要求，缺一不可）。</li>
 * </ol>
 *
 * <p><b>时刻一律用固定的 {@link OffsetDateTime} 字面量钉死</b>，不用 {@code now()}，
 * 也<b>不在中途改时刻</b>（{@code withHour(...)} 那种写法会把「恰好 N 天」变成
 * 「N 天减去当前时刻差」，于是测试只在早上绿、下午必红）。
 * 版本区间相关的断言优先断言<b>版本集合与顺序</b>，而不是「两个时间戳相差恰好 N 天」。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ListingMappingVersionIntegrationTest {

  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID user = UUID.randomUUID();
  static HttpServer identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static final ObjectMapper JSON = new ObjectMapper();

  /** 🔴 每个用例一对**全新的租户**：共用租户会让前一个用例建的数据进到后一个用例的断言里。 */
  UUID a, b;
  AccessService.Access actorA, actorB;
  String bearerA;

  // 🔴 三个时刻全部是**独立字面量**，彼此之间不做任何 with*/plus* 运算后再去比时长。
  //    比时长只在「同一个基准 + 同源偏移」时才安全，这里干脆不比。
  static final OffsetDateTime V1_FROM = OffsetDateTime.parse("2026-09-01T10:00:00+08:00");
  static final OffsetDateTime V2_FROM = OffsetDateTime.parse("2026-09-08T10:00:00+08:00");
  static final OffsetDateTime V3_FROM = OffsetDateTime.parse("2026-09-15T10:00:00+08:00");
  static final OffsetDateTime SYNCED_AT = OffsetDateTime.parse("2026-08-30T18:30:00+08:00");

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
      identity = HttpServer.create(new java.net.InetSocketAddress(0), 0);
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

  @Autowired ListingService listingService;
  @Autowired ListingMappingService mappingService;
  @Autowired ListingMappingVersionService service;
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
      AccessService.Access who, UUID shopId, String externalItemId, String title, String attributes) {
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
                                null, "V-1", "UNMAPPED", null, null, attributes)),
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

  SaveMappingRequest mapping(UUID variantId, UUID skuId, String reason, OffsetDateTime from) {
    return new SaveMappingRequest(
        null, variantId, "ONE_TO_ONE", skuId, null, reason, from, null);
  }

  /** 在既有映射上改一版（改会生成新版本，不原地改 —— 上游 4.1.2.1 的行为）。 */
  SaveMappingRequest revise(
      UUID mappingId, UUID variantId, UUID skuId, String reason, OffsetDateTime from) {
    // 🔴 上游 save 对「改」这一条同样要求 listingVariantId（@NotNull）：改的是映射，
    //    但它仍要按变体检索可见范围，所以这里必须把它带上，不能图省事传 null。
    return new SaveMappingRequest(
        mappingId, variantId, "ONE_TO_ONE", skuId, null, reason, from, null);
  }

  VersionPage read(AccessService.Access who) {
    return as(who, "channel.operation.read",
        () -> service.list(null, null, null, null, null, null, null, null, null, null, null,
            "VERSION", 0, 50));
  }

  MappingVersionDetail readVersion(AccessService.Access who, String id) {
    return as(who, "channel.operation.read", () -> service.detail(id));
  }

  /** 建三版映射：A→SKU1（V1）、A→SKU2（V2）、A→SKU3（V3，当前生效）。 */
  record ThreeVersions(UUID mappingId, UUID variantId, UUID listingId, List<UUID> skus) {}

  ThreeVersions buildThreeVersions(AccessService.Access who, UUID shopId, String code, String title) {
    UUID tenant = who.tenantId();
    UUID listingId = seedListing(who, shopId, code, title, "{}");
    UUID variant = variantId(tenant, listingId, "V-1");
    UUID sku1 = UUID.randomUUID();
    UUID sku2 = UUID.randomUUID();
    UUID sku3 = UUID.randomUUID();

    UUID mappingId =
        as(who, "channel.operation.create",
            () -> mappingService.save(mapping(variant, sku1, "初次建立", V1_FROM)).mapping().id());
    as(who, "channel.operation.create",
        () -> mappingService.save(revise(mappingId, variant, sku2, "供应商换货", V2_FROM)));
    as(who, "channel.operation.create",
        () -> mappingService.save(revise(mappingId, variant, sku3, "平台活动换 SKU", V3_FROM)));

    return new ThreeVersions(mappingId, variant, listingId, List.of(sku1, sku2, sku3));
  }

  // ==================================================================
  // 1. 版本列表是真的
  // ==================================================================

  @Test
  @DisplayName("4.1.2.2 版本列表是真的：逐版返回版本号 / 生效时间点 / 变更原因，且是真分页")
  void versionListIsReal() {
    UUID shopA = seedShop(a, "VERLIST-AMZ");
    ThreeVersions built = buildThreeVersions(actorA, shopA, "VERLIST-A", "版本列表商品");

    var page = read(actorA);

    // 🔴 断言「版本集合与顺序」，而不是「相邻两个时间戳相差恰好 7 天」
    assertThat(page.total()).isEqualTo(3);
    assertThat(page.items()).extracting(VersionRow::versionNo).containsExactly(1, 2, 3);
    assertThat(page.items()).extracting(VersionRow::internalSkuId)
        .containsExactlyElementsOf(built.skus());
    assertThat(page.items()).extracting(VersionRow::changeReason)
        .containsExactly("初次建立", "供应商换货", "平台活动换 SKU");

    // 当前生效只有最新一版；旧两版的 effectiveTo 必须已被关上（VERSION 排序是 1→N 升序，
    // 所以「当前生效」落在最后一条，不是第一条）
    assertThat(page.items()).filteredOn(VersionRow::current).hasSize(1);
    assertThat(page.items().get(0).effectiveTo()).isNotNull();
    assertThat(page.items().get(1).effectiveTo()).isNotNull();
    assertThat(page.items().get(2).effectiveTo()).isNull();
    assertThat(page.currentInPage()).isEqualTo(1);

    // 🔴 timestamptz 往返：断言**写回的值逐字相等**（不只断言没抛异常）
    assertThat(page.items()).extracting(VersionRow::effectiveFrom)
        .containsExactly(V1_FROM, V2_FROM, V3_FROM);

    // 真分页：size=2 时 total 仍然是全集 3，items 只有 2 条
    var firstPage = as(actorA, "channel.operation.read",
        () -> service.list(null, null, null, null, null, null, null, null, null, null, null,
            "VERSION", 0, 2));
    assertThat(firstPage.total()).isEqualTo(3);
    assertThat(firstPage.items()).hasSize(2);
    assertThat(firstPage.items()).extracting(VersionRow::versionNo).containsExactly(1, 2);

    var secondPage = as(actorA, "channel.operation.read",
        () -> service.list(null, null, null, null, null, null, null, null, null, null, null,
            "VERSION", 1, 2));
    assertThat(secondPage.items()).extracting(VersionRow::versionNo).containsExactly(3);

    // 筛选维度：按 mappingId / SKU / 生效状态 / 关键词
    assertThat(as(actorA, "channel.operation.read",
        () -> service.list(built.mappingId(), null, null, null, null, null, null, null, null, null,
            null, "VERSION", 0, 50)).total()).isEqualTo(3);
    assertThat(as(actorA, "channel.operation.read",
        () -> service.list(null, null, null, built.skus().get(0), null, null, null, null, null, null,
            null, "VERSION", 0, 50)).total()).isEqualTo(1);
    assertThat(as(actorA, "channel.operation.read",
        () -> service.list(null, null, null, null, null, null, null, "CURRENT", null, null, null,
            "VERSION", 0, 50)).total()).isEqualTo(1);
    assertThat(as(actorA, "channel.operation.read",
        () -> service.list(null, null, null, null, null, null, null, "CLOSED", null, null, null,
            "VERSION", 0, 50)).total()).isEqualTo(2);
    assertThat(as(actorA, "channel.operation.read",
        () -> service.list(null, null, null, null, null, null, null, null, null, null, "供应商",
            "VERSION", 0, 50)).total()).isEqualTo(1);
  }

  /**
   * 🔴 核心不变量：改映射不影响既有版本。
   *
   * <p>先取一版的第一版全字段快照，改两版之后<b>逐字比对</b>第一版：
   * SKU、变更原因、商品范围快照、生效时间点都必须一字不差。
   * 这条断言就是蓝图加粗句「改映射不影响已产生的订单，只对新订单生效」在查询面上的样子 ——
   * 订单行冻结的是版本 id，而版本 id 指向的行必须永远读得到同一份事实。
   */
  @Test
  @DisplayName("4.1.2.2 🔴 改映射不影响既有版本：第一版的 SKU / 变更原因 / 生效区间 / 商品范围逐字不变")
  void changingMappingDoesNotTouchExistingVersions() {
    UUID shopA = seedShop(a, "FROZEN-AMZ");
    ThreeVersions built = buildThreeVersions(actorA, shopA, "FROZEN-A", "冻结商品");

    VersionPage before = read(actorA);
    VersionRow v1Before = before.items().get(0);
    VersionProductScope scopeBefore =
        as(actorA, "channel.operation.read", () -> service.productScope(v1Before.id().toString()));

    // 改一版：换一个 SKU、一个新的变更原因、一个新的生效时间点
    UUID sku4 = UUID.randomUUID();
    as(actorA, "channel.operation.create",
        () -> mappingService.save(
            revise(built.mappingId(), built.variantId(), sku4, "第四次调整",
                OffsetDateTime.parse("2026-09-22T10:00:00+08:00"))));

    var after = read(actorA);
    assertThat(after.total()).isEqualTo(4);

    VersionRow v1After =
        after.items().stream().filter(v -> v.versionNo() == 1).findFirst().orElseThrow();

    // 🔴 逐字相等：旧版本一个字节都没被后来的变更改到
    assertThat(v1After.id()).isEqualTo(v1Before.id());
    assertThat(v1After.internalSkuId()).isEqualTo(built.skus().get(0));
    assertThat(v1After.internalSkuId()).isNotEqualTo(sku4);
    assertThat(v1After.changeReason()).isEqualTo("初次建立");
    assertThat(v1After.effectiveFrom()).isEqualTo(V1_FROM);
    assertThat(v1After.effectiveTo()).isEqualTo(v1Before.effectiveTo());
    assertThat(v1After.relationType()).isEqualTo("ONE_TO_ONE");

    // 商品范围快照同样不受后续变更影响
    VersionProductScope scopeAfter =
        as(actorA, "channel.operation.read", () -> service.productScope(v1Before.id().toString()));
    assertThat(scopeAfter.externalItemId()).isEqualTo(scopeBefore.externalItemId());
    assertThat(scopeAfter.titleSnapshot()).isEqualTo(scopeBefore.titleSnapshot());
    assertThat(scopeAfter.shopCode()).isEqualTo(scopeBefore.shopCode());
    assertThat(scopeAfter.listingVariantId()).isEqualTo(scopeBefore.listingVariantId());

    // 每一版仍然都能单独读出来 —— 订单冻结的是版本 id，id 读不到就等于追溯断了
    for (VersionRow v : after.items()) {
      MappingVersionDetail detail =
          as(actorA, "channel.operation.read", () -> service.detail(v.id().toString()));
      assertThat(detail.version().id()).isEqualTo(v.id());
      assertThat(detail.productScope()).isNotNull();
    }
  }

  @Test
  @DisplayName("4.1.2.2 DB 层兜底：绕开服务层直接写 SQL 也改不动已关闭的版本 / 商品范围快照")
  void dbLevelGuardsHold() {
    UUID shopA = seedShop(a, "GUARD-AMZ");
    ThreeVersions built = buildThreeVersions(actorA, shopA, "GUARD-A", "守护商品");
    var page = read(actorA);
    UUID v1 = page.items().get(0).id();

    // 已关闭的版本：V176 的 frozen 触发器拒
    assertThatThrownBy(() ->
            execAsTenant(a,
                "update listing_mapping_version set change_reason='偷偷改历史' where tenant_id='"
                    + a + "' and id='" + v1 + "'"))
        .hasMessageContaining("is history and must not be rewritten");

    // 已关闭的版本：连删都不许
    assertThatThrownBy(() ->
            execAsTenant(a,
                "delete from listing_mapping_version where tenant_id='" + a + "' and id='" + v1 + "'"))
        .hasMessageContaining("must not be deleted");

    // 🔴 V182 的范围快照：任何 UPDATE 都被拒（含超级用户连接）
    assertThatThrownBy(() ->
            exec("update listing_mapping_version_scope set title_snapshot='伪造的商品名'"
                + " where tenant_id='" + a + "' and version_id='" + v1 + "'"))
        .hasMessageContaining("must not be rewritten in place");

    // 改完之后，第一版逐字不变
    assertThat(read(actorA).items().get(0).changeReason()).isEqualTo("初次建立");
    assertThat(as(actorA, "channel.operation.read", () -> service.productScope(v1.toString()))
            .titleSnapshot())
        .isEqualTo("守护商品");
  }

  // ==================================================================
  // 2. 商品范围是冻结快照
  // ==================================================================

  @Test
  @DisplayName("4.1.2.2 商品范围是冻结快照：listing 后来被重新同步，历史版本仍读得到当时的标题")
  void productScopeIsFrozenAndDetectsDrift() {
    UUID shopA = seedShop(a, "DRIFT-AMZ");
    ThreeVersions built = buildThreeVersions(actorA, shopA, "DRIFT-A", "改版前的标题");

    VersionRow v1 = read(actorA).items().get(0);

    // 改版前：快照 == 平台当前值，没有漂移
    VersionProductScope before =
        as(actorA, "channel.operation.read", () -> service.productScope(v1.id().toString()));
    assertThat(before.titleSnapshot()).isEqualTo("改版前的标题");
    assertThat(before.currentTitle()).isEqualTo("改版前的标题");
    assertThat(before.driftedFromPlatform()).isFalse();

    // 平台侧后来重新同步，把标题改掉了
    execAsTenant(a,
        "update listing set title='改版后的标题', updated_at=now() where tenant_id='" + a
            + "' and id='" + built.listingId() + "'");

    // 🔴 历史版本读到的仍是**当时**的标题，平台当前值另说一栏
    VersionProductScope after =
        as(actorA, "channel.operation.read", () -> service.productScope(v1.id().toString()));
    assertThat(after.titleSnapshot()).isEqualTo("改版前的标题");
    assertThat(after.currentTitle()).isEqualTo("改版后的标题");
    assertThat(after.driftedFromPlatform()).isTrue();
    assertThat(after.externalItemId()).isEqualTo(before.externalItemId());
  }

  @Test
  @DisplayName("4.1.2.2 版本详情给出与上一版的真比对（改了什么）")
  void changedFieldsAreRealDiff() {
    UUID shopA = seedShop(a, "DIFF-AMZ");
    ThreeVersions built = buildThreeVersions(actorA, shopA, "DIFF-A", "差异商品");

    var page = read(actorA);

    // 首版没有上一版 ⇒ changedFields 为空，而不是列出全部字段
    var first = readVersion(actorA, page.items().get(0).id().toString());
    assertThat(first.previousVersionNo()).isNull();
    assertThat(first.nextVersionNo()).isEqualTo(2);
    assertThat(first.changedFields()).isEmpty();

    // 第二版换了 SKU ⇒ 真比对能看出来
    var second = readVersion(actorA, page.items().get(1).id().toString());
    assertThat(second.previousVersionNo()).isEqualTo(1);
    assertThat(second.nextVersionNo()).isEqualTo(3);
    assertThat(second.changedFields()).contains("INTERNAL_SKU_ID");

    // 第三版是当前生效版
    var third = readVersion(actorA, page.items().get(2).id().toString());
    assertThat(third.previousVersionNo()).isEqualTo(2);
    assertThat(third.nextVersionNo()).isNull();
    assertThat(third.version().current()).isTrue();
  }

  @Test
  @DisplayName("4.1.2.2 生效区间筛选是重叠语义：窗口内曾经生效过就该被列出来")
  void effectiveWindowFilterIsOverlapSemantics() {
    UUID shopA = seedShop(a, "WINDOW-AMZ");
    buildThreeVersions(actorA, shopA, "WINDOW-A", "窗口商品");

    // 🔴 窗口跨在 V2 / V3 的交界上：V2 的**起点（09-08）落在窗口之外**、终点（09-15）落在窗口之内，
    //    V3 的起点（09-15）落在窗口之内。这正是「重叠」与「起点落在窗口内」两种语义的分水岭：
    //    按后者实现会漏掉 V2，而运营问「9 月中旬那几天用的是哪一版」正需要它。
    var overlapping = as(actorA, "channel.operation.read",
        () -> service.list(null, null, null, null, null, null, null, null,
            OffsetDateTime.parse("2026-09-14T00:00:00+08:00"),
            OffsetDateTime.parse("2026-09-16T00:00:00+08:00"), null, "VERSION", 0, 50));
    assertThat(overlapping.items()).extracting(VersionRow::versionNo).containsExactly(2, 3);

    // 窗口整体落在 V2 生效期间：只有 V2
    var secondWindow = as(actorA, "channel.operation.read",
        () -> service.list(null, null, null, null, null, null, null, null,
            OffsetDateTime.parse("2026-09-10T00:00:00+08:00"),
            OffsetDateTime.parse("2026-09-11T00:00:00+08:00"), null, "VERSION", 0, 50));
    assertThat(secondWindow.items()).extracting(VersionRow::versionNo).containsExactly(2);

    // 窗口落在 V1 生效期间：只有 V1 曾经生效
    var firstWindow = as(actorA, "channel.operation.read",
        () -> service.list(null, null, null, null, null, null, null, null,
            OffsetDateTime.parse("2026-09-02T00:00:00+08:00"),
            OffsetDateTime.parse("2026-09-03T00:00:00+08:00"), null, "VERSION", 0, 50));
    assertThat(firstWindow.items()).extracting(VersionRow::versionNo).containsExactly(1);

    // 窗口在全部版本之前 ⇒ 空是真结果
    var beforeAll = as(actorA, "channel.operation.read",
        () -> service.list(null, null, null, null, null, null, null, null,
            OffsetDateTime.parse("2020-01-01T00:00:00+08:00"),
            OffsetDateTime.parse("2020-01-02T00:00:00+08:00"), null, "VERSION", 0, 50));
    assertThat(beforeAll.total()).isZero();
    assertThat(beforeAll.items()).isEmpty();
  }

  // ==================================================================
  // 3. 受影响订单范围 —— 如实报不可用
  // ==================================================================

  @Test
  @DisplayName("4.1.2.2 🔴 受影响订单范围 available=false 且 items 为 null（不是空数组）+ 归属模块")
  void affectedOrdersAreHonestlyUnavailable() {
    UUID shopA = seedShop(a, "AFFECT-AMZ");
    ThreeVersions built = buildThreeVersions(actorA, shopA, "AFFECT-A", "受影响商品");
    UUID v2 = read(actorA).items().get(1).id();

    var affected = as(actorA, "channel.operation.read", () -> service.affectedOrders(v2.toString()));

    assertThat(affected.available()).isFalse();
    // 🔴 关键：**响应里没有 items 字段** —— 空数组会被读成「这一版确实没有影响任何订单」，
    //    所以未交付的维度干脆不配列表（与 UnmappedPage.backflowDelivered 同一手法）
    assertThat(affected.ownerModule()).contains("09 订单");
    assertThat(affected.ownerModule()).contains("trade");
    assertThat(affected.note()).contains("order_line_component");
    assertThat(affected.prerequisite()).isNotBlank();
    // 版本自身的字段照常给得出来（本服务的事实）
    assertThat(affected.versionNo()).isEqualTo(2);

    // 详情里嵌的那一份也是同一个形状
    var detail = readVersion(actorA, v2.toString());
    assertThat(detail.affectedOrders().available()).isFalse();
    assertThat(detail.affectedOrders().prerequisite()).isNotBlank();

    // 能力声明里同样如实标了不可用
    var caps = as(actorA, "channel.operation.read", () -> service.capabilities());
    var affectedDimension =
        caps.dimensions().stream().filter(d -> d.dimension().equals("affected_orders")).findFirst()
            .orElseThrow();
    assertThat(affectedDimension.available()).isFalse();
    assertThat(affectedDimension.ownerModule()).contains("09 订单");
    // 已交付的维度不能被误标成不可用
    assertThat(caps.dimensions().stream()
            .filter(d -> List.of("version_list", "version_detail", "product_scope").contains(d.dimension()))
            .allMatch(d -> d.available()))
        .isTrue();
  }

  // ==================================================================
  // 4. 端点
  // ==================================================================

  /** 404 body 里除 {@code instance} 之外的字段（instance 按路径变化，不参与比对）。 */
  List<String> errorFields(String body) throws Exception {
    var json = JSON.readTree(body);
    return List.of(
        String.valueOf(json.get("status")), json.get("title").asText(), json.get("detail").asText());
  }

  @Test
  @DisplayName("4.1.2.2 端点：401 与真实业务往返；跨租户与不存在返回逐字相同的 404")
  void endpointsWork() throws Exception {
    UUID shopA = seedShop(a, "EP-AMZ");
    UUID shopB = seedShop(b, "EP-AMZ-B");
    ThreeVersions built = buildThreeVersions(actorA, shopA, "EP-A", "端点商品");
    UUID v1 = read(actorA).items().get(0).id();
    UUID bVersion = seedVersionOfTenantB(actorB, shopB);

    http.perform(get("/channel/mapping-versions").header("Authorization", bearerA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(3));

    http.perform(get("/channel/mapping-versions/capabilities").header("Authorization", bearerA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.dimensions").isArray());

    http.perform(get("/channel/mapping-versions/filter-options").header("Authorization", bearerA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.maxPageSize").isNumber());

    http.perform(get("/channel/mapping-versions/" + v1).header("Authorization", bearerA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version.id").value(v1.toString()))
        .andExpect(jsonPath("$.productScope.titleSnapshot").isNotEmpty())
        .andExpect(jsonPath("$.affectedOrders.available").value(false))
        .andExpect(jsonPath("$.affectedOrders.items").doesNotExist());

    http.perform(get("/channel/mapping-versions/" + v1 + "/product-scope")
            .header("Authorization", bearerA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.versionId").value(v1.toString()));

    http.perform(get("/channel/mapping-versions/" + v1 + "/affected-orders")
            .header("Authorization", bearerA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.available").value(false))
        .andExpect(jsonPath("$.items").doesNotExist());

    // 无会话 401
    http.perform(get("/channel/mapping-versions")).andExpect(status().isUnauthorized());

    // 跨租户与不存在必须**逐字相同**的 404：不泄露「别的租户有这么一条」。
    // ⚠️ 比的是 body 里的 title / status / detail 三项，**不是整段 body** ——
    //    RFC 9457 的 `instance` 字段就是请求路径本身，它按路径变化是正确行为，
    //    拿整段 body 去比会让这条断言在换一条 URL 时假红。
    var crossTenant = http.perform(get("/channel/mapping-versions/" + bVersion)
            .header("Authorization", bearerA))
        .andExpect(status().isNotFound())
        .andReturn()
        .getResponse();
    var missing = http.perform(get("/channel/mapping-versions/" + UUID.randomUUID())
            .header("Authorization", bearerA))
        .andExpect(status().isNotFound())
        .andReturn()
        .getResponse();

    assertThat(crossTenant.getStatus()).isEqualTo(missing.getStatus());
    assertThat(errorFields(crossTenant.getContentAsString()))
        .isEqualTo(errorFields(missing.getContentAsString()));

    // 非法 uuid 与不存在也是同一条路径
    http.perform(get("/channel/mapping-versions/not-a-uuid").header("Authorization", bearerA))
        .andExpect(status().isNotFound());
  }

  UUID seedVersionOfTenantB(AccessService.Access who, UUID shopId) {
    UUID listingId = seedListing(who, shopId, "EP-B", "B 租户商品", "{}");
    UUID variant = variantId(who.tenantId(), listingId, "V-1");
    as(who, "channel.operation.create",
        () -> mappingService.save(mapping(variant, UUID.randomUUID(), "B 的映射", V1_FROM)));
    return read(who).items().get(0).id();
  }

  // ==================================================================
  // 5. 多租户三断言（AGENTS.md 硬性要求，缺一不可）
  // ==================================================================

  @Test
  @DisplayName("4.1.2.2 断言一：两租户互不可见")
  void tenantsAreIsolated() {
    UUID shopA = seedShop(a, "ISO-AMZ-1");
    UUID shopB = seedShop(b, "ISO-AMZ-2");
    ThreeVersions aVersions = buildThreeVersions(actorA, shopA, "ISO-A", "A 的商品");
    ThreeVersions bVersions = buildThreeVersions(actorB, shopB, "ISO-B", "B 的商品");

    var pageA = read(actorA);
    var pageB = read(actorB);

    assertThat(pageA.total()).isEqualTo(3);
    assertThat(pageB.total()).isEqualTo(3);
    // A 看得见的版本全是 A 的 SKU，B 的一个都混不进来
    assertThat(pageA.items()).extracting(VersionRow::internalSkuId)
        .containsExactlyElementsOf(aVersions.skus());
    assertThat(pageB.items()).extracting(VersionRow::internalSkuId)
        .containsExactlyElementsOf(bVersions.skus());
    assertThat(pageA.items()).extracting(VersionRow::shopCode).containsOnly("ISO-AMZ-1");
    assertThat(pageB.items()).extracting(VersionRow::shopCode).containsOnly("ISO-AMZ-2");

    // 按 B 的 mappingId / SKU 过滤，A 查出来必须是 0 条（不是"B 的一条"）
    assertThat(as(actorA, "channel.operation.read",
        () -> service.list(bVersions.mappingId(), null, null, null, null, null, null, null, null,
            null, null, "VERSION", 0, 50)).total()).isZero();
    assertThat(as(actorA, "channel.operation.read",
        () -> service.list(null, null, null, bVersions.skus().get(0), null, null, null, null, null,
            null, null, "VERSION", 0, 50)).total()).isZero();

    // 商品范围 / 受影响订单范围同样按租户隔离
    UUID aVersion = pageA.items().get(0).id();
    assertThatThrownBy(() ->
            as(actorA, "channel.operation.read",
                () -> service.productScope(pageB.items().get(0).id().toString())))
        .hasMessageContaining("不存在");
    assertThatThrownBy(() ->
            as(actorA, "channel.operation.read",
                () -> service.affectedOrders(pageB.items().get(0).id().toString())))
        .hasMessageContaining("不存在");
    assertThat(as(actorA, "channel.operation.read", () -> service.productScope(aVersion.toString()))
            .versionId())
        .isEqualTo(aVersion);
  }

  @Test
  @DisplayName("4.1.2.2 断言二：伪造 tenant header 无效（租户只来自已认证成员关系）")
  void forgedTenantHeaderIsIgnored() throws Exception {
    UUID shopA = seedShop(a, "FORGE-AMZ-1");
    UUID shopB = seedShop(b, "FORGE-AMZ-2");
    buildThreeVersions(actorA, shopA, "FORGE-A", "A 的商品");
    UUID bVersion = seedVersionOfTenantB(actorB, shopB);

    // A 的会话 + B 的头 ⇒ 仍然只看得见 A 的数据
    http.perform(get("/channel/mapping-versions")
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(3))
        .andExpect(jsonPath("$.items[0].shopCode").value("FORGE-AMZ-1"));

    // 伪造租户头也不能让 B 的版本被 A 读到
    http.perform(get("/channel/mapping-versions/" + bVersion + "/product-scope")
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isNotFound());

    // 无会话一律 401
    http.perform(get("/channel/mapping-versions").header("X-Tenant-Id", b.toString()))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("4.1.2.2 断言三：连接池归还后不串租户（池压到 1，A→B→A 三轮）")
  void pooledConnectionsDoNotBleedTenants() {
    UUID shopA = seedShop(a, "POOL-AMZ-1");
    UUID shopB = seedShop(b, "POOL-AMZ-2");
    ThreeVersions aVersions = buildThreeVersions(actorA, shopA, "POOL-A", "A 的商品");
    ThreeVersions bVersions = buildThreeVersions(actorB, shopB, "POOL-B", "B 的商品");

    for (int i = 0; i < 3; i++) {
      var pageA = read(actorA);
      var pageB = read(actorB);

      assertThat(pageA.total()).isEqualTo(3);
      assertThat(pageB.total()).isEqualTo(3);
      assertThat(pageA.items()).extracting(VersionRow::internalSkuId)
          .containsExactlyElementsOf(aVersions.skus());
      assertThat(pageB.items()).extracting(VersionRow::internalSkuId)
          .containsExactlyElementsOf(bVersions.skus());

      // 商品范围与受影响订单范围也走同一个连接池
      UUID aVersion = pageA.items().get(0).id();
      assertThat(as(actorA, "channel.operation.read", () -> service.productScope(aVersion.toString()))
              .shopCode())
          .isEqualTo("POOL-AMZ-1");
      assertThatThrownBy(() ->
              as(actorA, "channel.operation.read",
                  () -> service.productScope(pageB.items().get(0).id().toString())))
          .hasMessageContaining("不存在");
    }
  }
}
