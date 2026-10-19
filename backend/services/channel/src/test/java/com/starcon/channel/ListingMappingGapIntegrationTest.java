package com.starcon.channel;

import static com.starcon.channel.ListingMappingGapModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
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
 * 2.4.2.3 映射缺失队列。
 *
 * <p>本测试类钉十组事：
 *
 * <ol>
 *   <li><b>队列是真的</b>：平台商品同步进来 ⇒ 变体自动入队；建立映射 ⇒ 缺口离队并回填；
 *       标记忽略 ⇒ 缺口离队。
 *   <li><b>🔴 不按名称猜 SKU</b>：队列行<b>没有任何</b>「建议 SKU / 相似度」字段，
 *       未决策前 {@code internal_sku_id} 恒为 null，队列自己从不给 SKU 填值。
 *   <li><b>🔴 历史订单冻结当时映射版本</b>：缺口回填到的那一版<b>不可改写</b>
 *       （DB 触发器直接拒）；改映射生成新版本后，缺口仍指向**当时那一版**；
 *       按历史时刻解析仍拿到当时的 SKU。
 *   <li><b>🔴 多租户三断言</b>：两租户互不可见 / 伪造 tenant header 无效 / 连接池归还后不串租户。
 *   <li><b>🔴 金额与数量</b>：价格列是 {@code numeric(19,4)}、服务层 {@link BigDecimal}，
 *       金额<b>逐字相等</b>（不靠浮点比较）。
 *   <li><b>🔴 时间字段</b>：{@code timestamptz} 往返<b>断言写回的值逐字相等</b>，
 *       不只断言"没抛异常"。
 *   <li><b>🔴 09 订单来源未接线</b>：接入点是真的（能收事件），但能力声明
 *       {@code orderSourceWired=false}，且**不返回任何订单条数**。
 *   <li><b>🔴 03 建档未接线</b>：「新建 SKU」只登记意图，请求停在 PENDING，
 *       <b>没有创建任何 SKU</b>（{@code created_sku_id} 为 null）。
 *   <li><b>🔴 DB 层兜底</b>：绕开服务层直接写 SQL 也写不进自相矛盾的缺口 /
 *       改写不了已回填的缺口 / 挂不上别的租户的变体。
 *   <li><b>端点往返</b>：401 / 404 / 409 逐字区分。
 * </ol>
 *
 * <p><b>时刻一律用固定的 {@link OffsetDateTime} 钉死</b>，不用 {@code now()}：用墙钟会让
 * 「这次写入落在哪个断言分支上」成为一个 flaky 断言。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ListingMappingGapIntegrationTest {

  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID user = UUID.randomUUID();
  static HttpServer identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule());

  /** 🔴 每个用例一对**全新的租户**：共用租户会让前一个用例建的数据进到后一个用例的断言里。 */
  UUID a, b;
  AccessService.Access actorA, actorB;
  String bearerA, bearerB;

  static final OffsetDateTime EFFECTIVE_FROM = OffsetDateTime.parse("2026-10-05T10:00:00+08:00");
  static final OffsetDateTime SYNCED_AT = OffsetDateTime.parse("2026-10-02T18:30:00+08:00");

  /** V1 已播种的平台目录里的 **AMAZON** 平台 id（平台码全局唯一，别自己插一条）。 */
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

  /** 播种全局码表（平台目录）。刻意不在静态块里播种：Flyway 在上下文刷新时才建表。 */
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
    r.add("INTERNAL_KEY", () -> "test-key");
    // 连接池压到 1：多租户串租户在单连接下最容易暴露。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> "1");
  }

  @Autowired ListingService listingService;
  @Autowired ListingMappingService mappingService;
  @Autowired ListingMappingGapService service;
  @Autowired AccessService access;
  @Autowired MockMvc http;

  // ==================================================================
  // 辅助
  // ==================================================================

  <T> T as(AccessService.Access who, String permission, java.util.function.Supplier<T> work) {
    return access.run(who, permission, work);
  }

  /** 以管理员（超级用户，RLS 不生效）连接执行 SQL —— <b>只用于播种与 DB 层兜底验证</b>。 */
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
      try (var rs = s.executeQuery(sql)) {
        var out = new java.util.ArrayList<String>();
        while (rs.next()) out.add(rs.getString(1));
        return out;
      }
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  /** 播种一个店铺（幂等：店铺码是 UNIQUE(tenant_id, code)）。 */
  UUID seedShop(UUID tenant, String code) {
    UUID id = UUID.randomUUID();
    execAsTenant(tenant,
        "insert into shop (tenant_id,id,platform_id,code,name) values ('" + tenant + "','" + id
            + "','" + AMAZON + "','" + code + "','测试店铺 " + code + "')"
            + " on conflict (tenant_id,code) do nothing");
    return id;
  }

  /** 登记一个平台商品（走 4.1.1.1 的真实写入路径），返回 listing id。 */
  UUID seedListing(
      AccessService.Access who, UUID shopId, String externalItemId, String title, String... variantIds) {
    List<ListingModels.SaveVariantRequest> variants = new java.util.ArrayList<>();
    for (String v : variantIds) {
      variants.add(new ListingModels.SaveVariantRequest(null, v, "UNMAPPED", null, null, "{}"));
    }
    return as(
            who,
            "channel.operation.create",
            () ->
                listingService.save(
                    new ListingModels.SaveListingRequest(
                        null, shopId, null, externalItemId, title, "Electronics", "cat-1",
                        "ON_SALE", null, "", "IN_STOCK", null, null, null, "USD",
                        null, SYNCED_AT, variants, null)))
        .id();
  }

  /** 取变体 id（列表行不返回变体 id，测试直接按外部变体 id 查）。 */
  UUID variantId(UUID tenant, UUID listingId, String externalVariantId) {
    return UUID.fromString(
        execAsTenantRows(
                tenant,
                "select id from listing_variant where tenant_id='" + tenant + "' and listing_id='"
                    + listingId + "' and external_variant_id='" + externalVariantId + "'")
            .get(0));
  }

  /** 队列里某变体的缺口 id。 */
  UUID gapId(UUID tenant, UUID variantId) {
    return UUID.fromString(
        execAsTenantRows(
                tenant,
                "select id from listing_mapping_gap where tenant_id='" + tenant
                    + "' and listing_variant_id='" + variantId + "' and status='PENDING'")
            .get(0));
  }

  MappingGapMapRequest mapRequest(UUID sku) {
    return new MappingGapMapRequest(sku, "ONE_TO_ONE", null, "队列里人工建立映射", null);
  }

  // ==================================================================
  // 1. 队列是真的：同步进 ⇒ 自动入队；映射建 ⇒ 离队并回填；忽略 ⇒ 离队
  // ==================================================================

  @Test
  @DisplayName("2.4.2.3 平台商品同步进来 ⇒ 变体自动入队；清单 / 概览 / 筛选都读真数据")
  void queueIsPopulatedByDatabaseTrigger() {
    UUID shop = seedShop(a, "GAP-AMZ-1");
    UUID listing = seedListing(actorA, shop, "GAP-1", "待映射的商品", "V-1", "V-2");
    UUID v1 = variantId(a, listing, "V-1");
    UUID v2 = variantId(a, listing, "V-2");

    var page = as(actorA, "channel.operation.read", () -> service.list(null, null, null, null, null,
        null, null, null, "RECENT", 0, 50));
    assertThat(page.total()).isEqualTo(2);
    assertThat(page.pendingInPage()).isEqualTo(2);
    assertThat(page.items()).extracting(MappingGapRow::listingVariantId)
        .containsExactlyInAnyOrder(v1, v2);
    // 平台商品 / 变体 / 店铺 三要素都在
    MappingGapRow row = page.items().get(0);
    assertThat(row.externalItemId()).isEqualTo("GAP-1");
    assertThat(row.titleSnapshot()).isEqualTo("待映射的商品");
    assertThat(row.shopCode()).isEqualTo("GAP-AMZ-1");
    assertThat(row.platformCode()).isEqualTo("AMAZON");
    assertThat(row.externalVariantId()).isIn("V-1", "V-2");
    assertThat(row.source()).isEqualTo("LISTING_SYNC");
    assertThat(row.gapReason()).isEqualTo("VARIANT_UNMAPPED");
    assertThat(row.status()).isEqualTo("PENDING");

    // 概览按状态 / 原因 / 店铺聚合
    var summary = as(actorA, "channel.operation.read", service::summary);
    assertThat(summary.pendingTotal()).isEqualTo(2);
    assertThat(summary.byStatus()).anySatisfy(c -> {
      assertThat(c.key()).isEqualTo("PENDING");
      assertThat(c.total()).isEqualTo(2);
    });
    assertThat(summary.byShop()).anySatisfy(c -> {
      assertThat(c.shopCode()).isEqualTo("GAP-AMZ-1");
      assertThat(c.total()).isEqualTo(2);
    });

    // 筛选维度：按店铺 / 关键词 / 状态
    assertThat(as(actorA, "channel.operation.read",
        () -> service.list("GAP-AMZ-1", null, null, null, null, null, null, null, "RECENT", 0, 50))
        .total()).isEqualTo(2);
    assertThat(as(actorA, "channel.operation.read",
        () -> service.list(null, null, "待映射", null, null, null, null, null, "RECENT", 0, 50))
        .total()).isEqualTo(2);
    assertThat(as(actorA, "channel.operation.read",
        () -> service.list(null, null, null, "IGNORED", null, null, null, null, "RECENT", 0, 50))
        .total()).isZero();
    assertThat(as(actorA, "channel.operation.read",
        () -> service.list(null, null, null, null, null, null, null, null, "RECENT", 0, 50))
        .total()).isEqualTo(2);
  }

  @Test
  @DisplayName("2.4.2.3 映射到已有 SKU ⇒ 缺口离队并回填到那一版")
  void mappingToExistingSkuResolvesAndBackfillsTheGap() {
    UUID shop = seedShop(a, "MAP-AMZ-1");
    UUID listing = seedListing(actorA, shop, "MAP-1", "要被映射的商品", "V-1");
    UUID variant = variantId(a, listing, "V-1");
    UUID gap = gapId(a, variant);
    UUID sku = UUID.randomUUID();

    var result = as(actorA, "channel.operation.create",
        () -> service.mapToExistingSku(actorA, gap, mapRequest(sku)));
    assertThat(result.gap().status()).isEqualTo("MAPPED");
    assertThat(result.gap().decision()).isEqualTo("MAPPED");
    assertThat(result.gap().internalSkuId()).isEqualTo(sku);
    assertThat(result.gap().mappingVersionId()).isNotNull();
    assertThat(result.gap().backfilledAt()).isNotNull();

    // 变体上的现状位被 4.1.2.1 的触发器投影成已映射
    assertThat(execAsTenantRows(a,
        "select mapping_status from listing_variant where tenant_id='" + a + "' and id='" + variant
            + "'").get(0)).isEqualTo("MAPPED");

    // 队列里不再有这条待办（离队）
    assertThat(as(actorA, "channel.operation.read", () -> service.list(null, null, null, "PENDING",
        null, null, null, null, "RECENT", 0, 50)).total()).isZero();
    // 但历史行还在（离队不等于删除）
    assertThat(as(actorA, "channel.operation.read", () -> service.list(null, null, null, "MAPPED",
        null, null, null, null, "RECENT", 0, 50)).total()).isEqualTo(1);
  }

  @Test
  @DisplayName("2.4.2.3 标记忽略 ⇒ 缺口离队；原因必填")
  void ignoreRemovesTheGapFromTheQueue() {
    UUID shop = seedShop(a, "IGN-AMZ-1");
    UUID listing = seedListing(actorA, shop, "IGN-1", "不用映射的商品", "V-1");
    UUID variant = variantId(a, listing, "V-1");
    UUID gap = gapId(a, variant);

    // 原因必填：不给就 422
    assertThatThrownBy(() -> as(actorA, "channel.operation.create",
        () -> service.ignore(actorA, gap, new MappingGapIgnoreRequest("  ", ""))))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
        .hasMessageContaining("忽略原因必填");

    var result = as(actorA, "channel.operation.create",
        () -> service.ignore(actorA, gap, new MappingGapIgnoreRequest("平台下架的配件", "已与运营确认")));
    assertThat(result.gap().status()).isEqualTo("IGNORED");
    assertThat(result.gap().decision()).isEqualTo("IGNORED");
    assertThat(result.gap().ignoreReason()).isEqualTo("平台下架的配件");

    assertThat(as(actorA, "channel.operation.read", () -> service.list(null, null, null, "PENDING",
        null, null, null, null, "RECENT", 0, 50)).total()).isZero();
  }

  // ==================================================================
  // 2. 🔴 不按名称猜 SKU
  // ==================================================================

  @Test
  @DisplayName("2.4.2.3 🔴 不按名称猜 SKU：队列只给缺口，未决策前绝不填 SKU，也没有「建议」字段")
  void neverGuessesSku() throws Exception {
    UUID shop = seedShop(a, "NOGUESS-AMZ-1");
    UUID listing = seedListing(actorA, shop, "NOGUESS-1", "无线蓝牙耳机 降噪版", "V-1");
    UUID variant = variantId(a, listing, "V-1");

    var page = as(actorA, "channel.operation.read", () -> service.list(null, null, null, null, null,
        null, null, null, "RECENT", 0, 50));
    MappingGapRow row = page.items().get(0);
    // 未决策 ⇒ 内部 SKU 为空，决策为 NONE
    assertThat(row.internalSkuId()).isNull();
    assertThat(row.decision()).isEqualTo("NONE");
    assertThat(row.mappingId()).isNull();
    assertThat(row.mappingVersionId()).isNull();

    // 🔴 模型层就没有「建议 / 疑似匹配」字段：清单行的 JSON 里不得出现这类键
    String json = JSON.writeValueAsString(page);
    for (String forbidden : List.of("suggest", "candidate", "confidence", "score", "similarity")) {
      assertThat(json.toLowerCase())
          .as("清单行不得出现「疑似匹配」字段：%s", forbidden)
          .doesNotContain(forbidden);
    }

    // 变体本身也仍然没有映射（队列不会顺手替它建映射）
    assertThat(execAsTenantRows(a,
        "select coalesce(internal_sku_id::text,'NULL') from listing_variant where tenant_id='" + a
            + "' and id='" + variant + "'").get(0)).isEqualTo("NULL");
    assertThat(execAsTenantRows(a,
        "select count(*) from listing_mapping where tenant_id='" + a + "' and listing_variant_id='"
            + variant + "'").get(0)).isEqualTo("0");
  }

  // ==================================================================
  // 3. 🔴 历史订单冻结当时映射版本
  // ==================================================================

  @Test
  @DisplayName("2.4.2.3 🔴 历史订单冻结当时映射版本：缺口回填到的那一版不可改写")
  void historyKeepsTheMappingVersionItWasResolvedWith() {
    UUID shop = seedShop(a, "FREEZE-AMZ-1");
    UUID listing = seedListing(actorA, shop, "FREEZE-1", "要改映射的商品", "V-1");
    UUID variant = variantId(a, listing, "V-1");
    UUID gap = gapId(a, variant);
    UUID sku1 = UUID.randomUUID();
    UUID sku2 = UUID.randomUUID();

    // 第一版：生效时间点固定（timestamptz 往返逐字相等）
    var first = as(actorA, "channel.operation.create",
        () -> service.mapToExistingSku(actorA, gap,
            new MappingGapMapRequest(sku1, "ONE_TO_ONE", null, "首次建立映射", EFFECTIVE_FROM)));
    UUID version1 = first.gap().mappingVersionId();
    assertThat(version1).isNotNull();
    assertThat(first.gap().mappedEffectiveFrom())
        .as("🔴 timestamptz 往返必须逐字相等，不能偏 8 小时")
        .isEqualTo(EFFECTIVE_FROM);

    // 改映射 ⇒ 生成新版本（4.1.2.1 的纪律：改映射生成新版本，不原地改）
    as(actorA, "channel.operation.create",
        () -> mappingService.save(
            new ListingMappingModels.SaveMappingRequest(
                first.gap().mappingId(), variant, "ONE_TO_ONE", sku2, null, "改成另一个 SKU", null, null)));

    // 🔴 缺口仍然指向**当时那一版**：回填目标不可被后来的版本顶掉
    var after = as(actorA, "channel.operation.read", () -> service.detail(gap));
    assertThat(after.gap().mappingVersionId()).isEqualTo(version1);
    assertThat(after.gap().internalSkuId()).isEqualTo(sku1);

    // 🔴 DB 层兜底：直接改写已回填缺口的回填目标 ⇒ 被拒
    assertThatThrownBy(() -> execAsTenant(a,
        "update listing_mapping_gap set mapping_version_id=gen_random_uuid()"
            + " where tenant_id='" + a + "' and id='" + gap + "'"))
        .hasMessageContaining("must not be rewritten");

    // 🔴 历史版本行本身也不可改写（V176 的冻结触发器）
    assertThatThrownBy(() -> execAsTenant(a,
        "update listing_mapping_version set internal_sku_id=gen_random_uuid()"
            + " where tenant_id='" + a + "' and id='" + version1 + "'"))
        .hasMessageContaining("must not be rewritten");

    // 🔴 按历史时刻解析，仍拿到**当时**的 SKU（订单冻结当时映射版本的语义）
    var resolved =
        as(actorA, "channel.operation.read",
            () -> mappingService.resolve(
                new ListingMappingModels.MappingResolveRequest(
                    List.of(variant), EFFECTIVE_FROM.plusSeconds(60))));
    assertThat(resolved).hasSize(1);
    assertThat(resolved.get(0).status()).isEqualTo("RESOLVED");
    assertThat(resolved.get(0).internalSkuId()).isEqualTo(sku1);
    assertThat(resolved.get(0).versionNo()).isEqualTo(1);
  }

  // ==================================================================
  // 4. 🔴 金额与时间
  // ==================================================================

  @Test
  @DisplayName("2.4.2.3 🔴 价格是 numeric(19,4) + BigDecimal，金额逐字相等；价格区间筛选是真的")
  void priceIsExactAndFilterable() {
    UUID shop = seedShop(a, "PRICE-AMZ-1");
    UUID listing = seedListing(actorA, shop, "PRICE-1", "有价格的商品", "V-1");
    UUID variant = variantId(a, listing, "V-1");

    // 列类型必须是 numeric（禁浮点）
    assertThat(execAsTenantRows(a,
        "select data_type from information_schema.columns where table_name='listing_mapping_gap'"
            + " and column_name='price'").get(0)).isEqualTo("numeric");
    assertThat(execAsTenantRows(a,
        "select numeric_precision from information_schema.columns"
            + " where table_name='listing_mapping_gap' and column_name='price'").get(0)).isEqualTo("19");
    assertThat(execAsTenantRows(a,
        "select numeric_scale from information_schema.columns"
            + " where table_name='listing_mapping_gap' and column_name='price'").get(0)).isEqualTo("4");

    // 平台商品来源没有价格快照（05 定价未交付）⇒ 价格为 null，不是 0
    var page = as(actorA, "channel.operation.read", () -> service.list(null, null, null, null, null,
        null, null, null, "RECENT", 0, 50));
    assertThat(page.items().get(0).price()).isNull();
    assertThat(page.priceSnapshotInPage()).isZero();

    // 订单来源事件带平台价 ⇒ 快照落库，金额逐字相等
    var accepted = as(actorA, "channel.operation.create",
        () -> service.acceptOrderGap(
            new MappingGapOrderRequest("SO-2026-0001", variant, new BigDecimal("19.99"), "USD", "订单行价格")));
    var detail = as(actorA, "channel.operation.read", () -> service.detail(accepted.gapId()));
    // 🔴 金额逐字相等：numeric(19,4) 的规范文本就是 "19.9900"，一分不差、一位不丢
    assertThat(detail.gap().price()).isEqualByComparingTo(new BigDecimal("19.99"));
    assertThat(detail.gap().price().scale()).isEqualTo(4);
    assertThat(execAsTenantRows(a,
        "select price::text from listing_mapping_gap where tenant_id='" + a + "' and id='"
            + accepted.gapId() + "'").get(0)).isEqualTo("19.9900");
    assertThat(detail.gap().currency()).isEqualTo("USD");
    assertThat(detail.gap().source()).isEqualTo("ORDER");
    assertThat(detail.gap().orderRef()).isEqualTo("SO-2026-0001");
    assertThat(detail.gap().gapReason()).isEqualTo("ORDER_UNMAPPED");

    // 价格区间筛选：真 SQL，只覆盖有价格快照的行
    assertThat(as(actorA, "channel.operation.read", () -> service.list(null, null, null, null, null,
        null, new BigDecimal("10.00"), new BigDecimal("20.00"), "PRICE", 0, 50)).total()).isEqualTo(1);
    assertThat(as(actorA, "channel.operation.read", () -> service.list(null, null, null, null, null,
        null, new BigDecimal("100.00"), null, "PRICE", 0, 50)).total()).isZero();

    // 价格与币种同生共死
    assertThatThrownBy(() -> as(actorA, "channel.operation.create",
        () -> service.acceptOrderGap(
            new MappingGapOrderRequest("SO-2026-0002", variant, new BigDecimal("1.00"), null, ""))))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
        .hasMessageContaining("同生共死");
  }

  // ==================================================================
  // 5. 🔴 09 订单来源未接线 / 03 建档未接线
  // ==================================================================

  @Test
  @DisplayName("2.4.2.3 🔴 09 订单来源：接入点是真的，但能力声明说清 trade 侧还没推事件")
  void orderSourceIsAnIntakePointButNotYetWired() throws Exception {
    UUID shop = seedShop(a, "ORDER-AMZ-1");
    UUID listing = seedListing(actorA, shop, "ORDER-1", "订单里的商品", "V-1");
    UUID variant = variantId(a, listing, "V-1");

    var caps = as(actorA, "channel.operation.read", service::capabilities);
    assertThat(caps.orderSourceWired()).isFalse();
    assertThat(caps.dimensions()).anySatisfy(d -> {
      assertThat(d.dimension()).isEqualTo("order_source");
      assertThat(d.available()).isFalse();
      assertThat(d.ownerModule()).contains("09 订单");
    });
    assertThat(caps.dimensions()).anySatisfy(d -> {
      assertThat(d.dimension()).isEqualTo("order_backfill");
      assertThat(d.available()).isFalse();
    });
    // 价格也如实说未接入
    assertThat(caps.priceAvailable()).isFalse();
    assertThat(caps.priceOwnerModule()).contains("05 定价");

    // 接入点本身可用：事件来了就能进队列
    var accepted = as(actorA, "channel.operation.create",
        () -> service.acceptOrderGap(new MappingGapOrderRequest("SO-9", variant, null, null, "")));
    assertThat(accepted.message()).contains("尚未有生产者");
    var detail = as(actorA, "channel.operation.read", () -> service.detail(accepted.gapId()));
    assertThat(detail.gap().source()).isEqualTo("ORDER");
    // 🔴 不返回任何订单条数（没有「受影响订单数」这种字段）
    String json = JSON.writeValueAsString(detail);
    assertThat(json.toLowerCase()).doesNotContain("affectedordercount");
    assertThat(detail.orderBackfillNote()).contains("尚未接线");
  }

  @Test
  @DisplayName("2.4.2.3 🔴 新建 SKU：只登记意图，没有创建任何 SKU（03 侧未接线）")
  void createSkuOnlyRegistersTheIntent() {
    UUID shop = seedShop(a, "SKU-AMZ-1");
    UUID listing = seedListing(actorA, shop, "SKU-1", "要新建 SKU 的商品", "V-1");
    UUID variant = variantId(a, listing, "V-1");
    UUID gap = gapId(a, variant);

    var result = as(actorA, "channel.operation.create",
        () -> service.createSku(actorA, gap,
            new MappingGapCreateSkuRequest("SKU-NEW-1", "新商品", "6909999999999", "PCS", "运营登记")));
    assertThat(result.request().status()).isEqualTo("PENDING");
    assertThat(result.request().createdSkuId()).isNull();
    assertThat(result.request().dispatchedAt()).isNull();
    assertThat(result.message()).contains("没有创建任何 SKU");

    // 缺口仍留在队列里（等 SKU 建好 + 建立映射后才离队）
    var row = as(actorA, "channel.operation.read", () -> service.detail(gap)).gap();
    assertThat(row.status()).isEqualTo("PENDING");
    assertThat(row.decision()).isEqualTo("CREATE_SKU");
    // 🔴 内部 SKU 仍然为空：登记意图不等于建了档
    assertThat(row.internalSkuId()).isNull();
    // 库里确实没有 SKU 映射
    assertThat(execAsTenantRows(a,
        "select count(*) from listing_mapping where tenant_id='" + a + "' and listing_variant_id='"
            + variant + "'").get(0)).isEqualTo("0");
  }

  // ==================================================================
  // 6. 🔴 多租户三断言
  // ==================================================================

  @Test
  @DisplayName("2.4.2.3 断言一：两租户互不可见")
  void tenantsAreIsolated() {
    UUID shopA = seedShop(a, "ISO-AMZ-A");
    UUID shopB = seedShop(b, "ISO-AMZ-B");
    UUID listingA = seedListing(actorA, shopA, "ISO-A", "A 的商品", "V-1");
    UUID listingB = seedListing(actorB, shopB, "ISO-B", "B 的商品", "V-1");
    UUID variantA = variantId(a, listingA, "V-1");
    UUID variantB = variantId(b, listingB, "V-1");

    var rowsA = as(actorA, "channel.operation.read", () -> service.list(null, null, null, null, null,
        null, null, null, "RECENT", 0, 50));
    var rowsB = as(actorB, "channel.operation.read", () -> service.list(null, null, null, null, null,
        null, null, null, "RECENT", 0, 50));
    assertThat(rowsA.total()).isEqualTo(1);
    assertThat(rowsB.total()).isEqualTo(1);
    assertThat(rowsA.items().get(0).listingVariantId()).isEqualTo(variantA);
    assertThat(rowsB.items().get(0).listingVariantId()).isEqualTo(variantB);
    assertThat(rowsA.items().get(0).shopCode()).isEqualTo("ISO-AMZ-A");
    assertThat(rowsB.items().get(0).shopCode()).isEqualTo("ISO-AMZ-B");

    // 概览与词表也按租户隔离
    assertThat(as(actorA, "channel.operation.read", service::summary).pendingTotal()).isEqualTo(1);
    assertThat(as(actorB, "channel.operation.read", service::summary).pendingTotal()).isEqualTo(1);
    assertThat(as(actorA, "channel.operation.read", service::filterOptions).shops())
        .extracting(MappingGapShopOption::shopCode)
        .containsExactly("ISO-AMZ-A");

    // 跨租户取详情 ⇒ 404（不是 403：跨租户与不存在都表现为"取不到"）
    UUID gapB = gapId(b, variantB);
    assertThatThrownBy(() -> as(actorA, "channel.operation.read", () -> service.detail(gapB)))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
        .hasMessageContaining("不存在");

    // 跨租户决策 ⇒ 404
    assertThatThrownBy(() -> as(actorA, "channel.operation.create",
        () -> service.ignore(actorA, gapB, new MappingGapIgnoreRequest("越权", ""))))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
        .hasMessageContaining("不存在");
  }

  @Test
  @DisplayName("2.4.2.3 断言二：伪造 tenant header 无效（租户只来自已认证成员关系）")
  void forgedTenantHeaderIsIgnored() throws Exception {
    UUID shopA = seedShop(a, "FORGE-AMZ-A");
    UUID shopB = seedShop(b, "FORGE-AMZ-B");
    UUID listingA = seedListing(actorA, shopA, "FORGE-A", "A 的商品", "V-1");
    UUID listingB = seedListing(actorB, shopB, "FORGE-B", "B 的商品", "V-1");
    UUID variantB = variantId(b, listingB, "V-1");
    UUID gapB = gapId(b, variantB);

    // A 的会话 + B 的头 ⇒ 仍然只看得见 A 的数据
    http.perform(get("/channel/mapping-gaps")
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.items[0].shopCode").value("FORGE-AMZ-A"));

    // 伪造租户头也拿不到 B 的缺口详情
    http.perform(get("/channel/mapping-gaps/" + gapB)
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isNotFound());

    // 无会话一律 401
    http.perform(get("/channel/mapping-gaps").header("X-Tenant-Id", b.toString()))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("2.4.2.3 断言三：连接池归还后不串租户（池压到 1，A→B→A 三轮）")
  void pooledConnectionsDoNotBleedTenants() {
    UUID shopA = seedShop(a, "POOL-AMZ-A");
    UUID shopB = seedShop(b, "POOL-AMZ-B");
    UUID listingA = seedListing(actorA, shopA, "POOL-A", "A 的商品", "V-1");
    UUID listingB = seedListing(actorB, shopB, "POOL-B", "B 的商品", "V-1");
    UUID variantA = variantId(a, listingA, "V-1");
    UUID variantB = variantId(b, listingB, "V-1");

    for (int i = 0; i < 3; i++) {
      var rowsA = as(actorA, "channel.operation.read", () -> service.list(null, null, null, null,
          null, null, null, null, "RECENT", 0, 50));
      var rowsB = as(actorB, "channel.operation.read", () -> service.list(null, null, null, null,
          null, null, null, null, "RECENT", 0, 50));
      assertThat(rowsA.total()).isEqualTo(1);
      assertThat(rowsB.total()).isEqualTo(1);
      assertThat(rowsA.items().get(0).listingVariantId()).isEqualTo(variantA);
      assertThat(rowsB.items().get(0).listingVariantId()).isEqualTo(variantB);
      assertThat(as(actorA, "channel.operation.read", service::summary).pendingTotal()).isEqualTo(1);
      assertThat(as(actorB, "channel.operation.read", service::summary).pendingTotal()).isEqualTo(1);
    }
  }

  // ==================================================================
  // 7. 🔴 DB 层兜底
  // ==================================================================

  @Test
  @DisplayName("2.4.2.3 🔴 DB 层兜底：绕开服务层也写不进自相矛盾的缺口 / 挂不上别的租户的变体")
  void databaseGuardsHoldTheInvariants() {
    UUID shop = seedShop(a, "GUARD-AMZ-1");
    UUID listing = seedListing(actorA, shop, "GUARD-1", "守约束的商品", "V-1");
    UUID variant = variantId(a, listing, "V-1");
    UUID gap = gapId(a, variant);

    // 已离队的行必须说清怎么离的：status='MAPPED' 却没回填版本 ⇒ CHECK 拒
    assertThatThrownBy(() -> execAsTenant(a,
        "update listing_mapping_gap set status='MAPPED' where tenant_id='" + a + "' and id='" + gap
            + "'"))
        .hasMessageContaining("violates check constraint");

    // 忽略必须带原因
    assertThatThrownBy(() -> execAsTenant(a,
        "update listing_mapping_gap set status='IGNORED',decision='IGNORED'"
            + " where tenant_id='" + a + "' and id='" + gap + "'"))
        .hasMessageContaining("violates check constraint");

    // 价格与币种同生共死
    assertThatThrownBy(() -> execAsTenant(a,
        "update listing_mapping_gap set price=1.00 where tenant_id='" + a + "' and id='" + gap + "'"))
        .hasMessageContaining("violates check constraint");

    // 跨租户挂载变体：复合 FK 带 tenant_id ⇒ 物理上不可能
    UUID shopB = seedShop(b, "GUARD-AMZ-2");
    UUID listingB = seedListing(actorB, shopB, "GUARD-2", "B 的商品", "V-1");
    UUID variantB = variantId(b, listingB, "V-1");
    assertThatThrownBy(() -> execAsTenant(a,
        "insert into listing_mapping_gap (id,tenant_id,listing_id,listing_variant_id,source,"
            + " gap_reason,status,decision) values (gen_random_uuid(),'" + a + "','" + listingB
            + "','" + variantB + "','LISTING_SYNC','VARIANT_UNMAPPED','PENDING','NONE')"))
        .hasMessageContaining("violates foreign key constraint");
  }

  // ==================================================================
  // 8. 端点往返
  // ==================================================================

  @Test
  @DisplayName("2.4.2.3 端点：401 / 404 / 409 逐字区分；三个操作入口真能跑通")
  void endpointsBehave() throws Exception {
    UUID shop = seedShop(a, "EP-AMZ-1");
    UUID listing = seedListing(actorA, shop, "EP-1", "端点用的商品", "V-1");
    UUID variant = variantId(a, listing, "V-1");
    UUID gap = gapId(a, variant);
    UUID sku = UUID.randomUUID();

    // 401
    http.perform(get("/channel/mapping-gaps")).andExpect(status().isUnauthorized());

    // 404：不存在
    http.perform(get("/channel/mapping-gaps/" + UUID.randomUUID())
            .header("Authorization", bearerA))
        .andExpect(status().isNotFound());

    // 三个操作入口
    http.perform(post("/channel/mapping-gaps/" + gap + "/map")
            .header("Authorization", bearerA)
            .contentType("application/json")
            .content(JSON.writeValueAsString(
                new MappingGapMapRequest(sku, "ONE_TO_ONE", null, "端点建立的映射", null))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.gap.status").value("MAPPED"))
        .andExpect(jsonPath("$.gap.internalSkuId").value(sku.toString()));

    // 409：重复决策
    http.perform(post("/channel/mapping-gaps/" + gap + "/ignore")
            .header("Authorization", bearerA)
            .contentType("application/json")
            .content(JSON.writeValueAsString(new MappingGapIgnoreRequest("再来一次", ""))))
        .andExpect(status().isConflict());

    // 能力声明 / 概览 / 词表
    http.perform(get("/channel/mapping-gaps/capabilities").header("Authorization", bearerA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.orderSourceWired").value(false))
        .andExpect(jsonPath("$.priceAvailable").value(false));
    http.perform(get("/channel/mapping-gaps/summary").header("Authorization", bearerA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.pendingTotal").value(0));
    http.perform(get("/channel/mapping-gaps/filter-options").header("Authorization", bearerA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.statuses").isArray());
  }
}
