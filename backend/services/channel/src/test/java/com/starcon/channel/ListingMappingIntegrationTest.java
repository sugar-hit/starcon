package com.starcon.channel;

import static com.starcon.channel.ListingMappingModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
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
 * 4.1.2.1 映射管理。
 *
 * <p>本测试类钉九组事：
 *
 * <ol>
 *   <li><b>多租户三断言</b>（AGENTS.md 硬性要求，缺一不可）：两租户互不可见、伪造 tenant header 无效、
 *       连接池归还后不串租户
 *   <li><b>🔴 映射未建立时绝不猜 SKU</b>：{@code /resolve} 返回 {@code PENDING_MAPPING} 且
 *       {@code internalSkuId=null}；候选表里躺着一条 1.0 分的标题相似度候选时**也**不解析
 *   <li><b>🔴 改映射生成新版本</b>，不原地改：版本历史逐条留存（生效时间点 / 变更原因）
 *   <li><b>🔴 解除不是删除</b>：变体退回未映射，历史版本原样保留，且**可以重新建立**映射
 *   <li><b>🔴 批量候选只写候选表</b>：确认之前一个字节的映射行都不写；标题相似度恒需人工确认
 *   <li><b>🔴 DB 层兜底</b>：绕开服务层直接写 SQL 也写不进自相矛盾的映射 / 改写不了历史版本
 *   <li><b>时间字段写回值正确</b>（断言写回的值，不只断言没抛异常）
 *   <li><b>未映射清单是真的</b>：建了映射就不在清单里，清单口径与映射状态一致
 *   <li><b>多对一反查</b>：能回答「这个内部 SKU 被哪些平台变体共用」
 * </ol>
 *
 * <p><b>时刻一律用固定的 {@link OffsetDateTime} 钉死</b>，不用 {@code now()}：用墙钟会让
 * 「这次写入落在哪个断言分支上」成为一个 flaky 断言。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ListingMappingIntegrationTest {

  static final PostgreSQLContainer<?> pg =
      new PostgreSQLContainer<>("postgres:16-alpine").withUrlParam("sslmode", "disable");
  static final UUID user = UUID.randomUUID();
  static HttpServer identity;
  static HttpServer catalog;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static final ObjectMapper JSON = new ObjectMapper();

  /** 🔴 每个用例一对**全新的租户**：共用租户会让前一个用例建的数据进到后一个用例的断言里。 */
  UUID a, b;
  AccessService.Access actorA, actorB;
  String bearerA;

  static final OffsetDateTime EFFECTIVE_FROM = OffsetDateTime.parse("2026-10-05T10:00:00+08:00");
  static final OffsetDateTime SYNCED_AT = OffsetDateTime.parse("2026-10-02T18:30:00+08:00");

  static final UUID AMAZON = UUID.fromString("11111111-1111-1111-1111-111111111111");

  /** catalog 侧的 SKU 目录（03 商品拥有，channel 只能经授权 API 读）。 */
  static final UUID CATALOG_SKU_HEADPHONE = UUID.fromString("aaaaaaaa-0000-4000-8000-000000000001");
  static final UUID CATALOG_SKU_CABLE = UUID.fromString("aaaaaaaa-0000-4000-8000-000000000002");
  static final String HEADPHONE_NAME = "无线蓝牙耳机 降噪版";
  static final String HEADPHONE_CODE = "SKU-001";
  static final String HEADPHONE_BARCODE = "6901234567890";

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

      // catalog 桩：真实实现是 03 商品的 GET /catalog/skus（分页 + q / barcode 过滤）。
      // 桩只回 SKU 目录，**不参与租户判定** —— 租户仍由调用方自己的 bearer 在上游解析。
      catalog = HttpServer.create(new InetSocketAddress(0), 0);
      catalog.createContext("/catalog/skus", ListingMappingIntegrationTest::serveSkus);
      catalog.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  /** catalog 桩的 SKU 目录。 */
  private static final List<Map<String, String>> CATALOG_SKUS =
      List.of(
          Map.of(
              "id", CATALOG_SKU_HEADPHONE.toString(),
              "code", HEADPHONE_CODE,
              "name", HEADPHONE_NAME,
              "barcode", HEADPHONE_BARCODE,
              "status", "ACTIVE"),
          Map.of(
              "id", CATALOG_SKU_CABLE.toString(),
              "code", "SKU-002",
              "name", "USB-C 充电线 1.5 米",
              "barcode", "6901234567891",
              "status", "ACTIVE"));

  /** catalog 桩：q 走 code/name 的模糊匹配，barcode 走精确匹配。 */
  private static void serveSkus(com.sun.net.httpserver.HttpExchange e) throws java.io.IOException {
    Map<String, String> query = new java.util.HashMap<>();
    for (String pair : e.getRequestURI().getRawQuery().split("&")) {
      if (pair.isEmpty()) continue;
      int eq = pair.indexOf('=');
      query.put(
          java.net.URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
          java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
    }
    String q = query.get("q");
    String barcode = query.get("barcode");
    List<Map<String, String>> hits = new ArrayList<>();
    for (Map<String, String> sku : CATALOG_SKUS) {
      if (barcode != null) {
        if (barcode.equals(sku.get("barcode"))) hits.add(sku);
      } else if (q == null
          || sku.get("code").toLowerCase().contains(q.toLowerCase())
          || sku.get("name").toLowerCase().contains(q.toLowerCase())) {
        hits.add(sku);
      }
    }
    String json;
    try {
      Map<String, Object> body = new java.util.LinkedHashMap<>();
      body.put("items", hits);
      body.put("total", hits.size());
      body.put("page", 0);
      body.put("size", hits.size());
      json = new ObjectMapper().writeValueAsString(body);
    } catch (Exception failure) {
      throw new IllegalStateException(failure);
    }
    byte[] body = json.getBytes(StandardCharsets.UTF_8);
    e.getResponseHeaders().add("Content-Type", "application/json");
    e.sendResponseHeaders(200, body.length);
    e.getResponseBody().write(body);
    e.close();
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
    r.add("INTERNAL_KEY", () -> "test-key");
    // 连接池压到 1：多租户串租户在单连接下最容易暴露。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> "1");
  }

  @Autowired ListingService listingService;
  @Autowired ListingMappingService service;
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

  /** 播种一个店铺（幂等：店铺码是 UNIQUE(tenant_id, code)）。 */
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

  /** 取变体 id（列表行不返回变体 id，测试直接按外部变体 id 查）。 */
  UUID variantId(UUID tenant, UUID listingId, String externalVariantId) {
    return UUID.fromString(
        execAsTenantRows(
                tenant,
                "select id from listing_variant where tenant_id='" + tenant + "' and listing_id='"
                    + listingId + "' and external_variant_id='" + externalVariantId + "'")
            .get(0));
  }

  /** 变体上被 DB 触发器投影出来的映射现状位。 */
  String variantMappingStatus(UUID tenant, UUID variantId) {
    return execAsTenantRows(
            tenant,
            "select mapping_status from listing_variant where tenant_id='" + tenant
                + "' and id='" + variantId + "'")
        .get(0);
  }

  /** 库里的版本行条数（用来证明「候选阶段一个映射版本都没写」）。 */
  int versionCount(UUID tenant, UUID mappingId) {
    return Integer.parseInt(
        execAsTenantRows(
                tenant,
                "select count(*) from listing_mapping_version where tenant_id='" + tenant
                    + "' and mapping_id='" + mappingId + "'")
            .get(0));
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

  SaveMappingRequest mapping(
      UUID variantId, UUID skuId, String relation, BigDecimal quantity, String reason) {
    return new SaveMappingRequest(
        null, variantId, relation, skuId, quantity, reason, EFFECTIVE_FROM, null);
  }

  // ==================================================================
  // 1. 映射的建立 / 修改 / 解除
  // ==================================================================

  @Test
  @DisplayName("4.1.2.1 建立映射 → 变体现状位被 DB 触发器投影；改映射生成新版本；解除退回未映射")
  void mappingLifecycleWorks() {
    UUID shop = seedShop(a, "LIFE-AMZ");
    UUID listingId = seedListing(actorA, shop, "LIFE-1", "生命周期测试商品", "{}");
    UUID variant = variantId(a, listingId, "V-1");
    UUID sku = UUID.randomUUID();

    // 建立前：变体是未映射的
    assertThat(variantMappingStatus(a, variant)).isEqualTo("UNMAPPED");

    var created =
        as(
            actorA,
            "channel.operation.create",
            () ->
                service.save(
                    mapping(variant, sku, "ONE_TO_ONE", null, "首次建立映射")));

    assertThat(created.mapping().internalSkuId()).isEqualTo(sku);
    assertThat(created.mapping().relationType()).isEqualTo("ONE_TO_ONE");
    assertThat(created.mapping().mappingStatus()).isEqualTo("MAPPED");
    assertThat(created.mapping().currentVersionNo()).isEqualTo(1);
    assertThat(created.versions()).hasSize(1);

    // 🔴 现状位由 DB 触发器投影：服务层不写 listing_variant 也必须跟着变
    assertThat(variantMappingStatus(a, variant)).isEqualTo("MAPPED");

    // 改映射 = 新版本，不是原地改
    UUID sku2 = UUID.randomUUID();
    var updated =
        as(
            actorA,
            "channel.operation.create",
            () ->
                service.save(
                    new SaveMappingRequest(
                        created.mapping().id(), variant, "ONE_TO_ONE", sku2, null,
                        "换到另一个内部 SKU", EFFECTIVE_FROM.plusDays(1), 1)));

    assertThat(updated.mapping().internalSkuId()).isEqualTo(sku2);
    assertThat(updated.mapping().currentVersionNo()).isEqualTo(2);
    assertThat(updated.versions()).hasSize(2);
    // 历史版本仍在，且逐版带着变更原因
    assertThat(updated.versions()).extracting(MappingVersionView::changeReason)
        .containsExactly("换到另一个内部 SKU", "首次建立映射");
    // 旧版本被关闭（半开区间 [from,to)）
    assertThat(updated.versions().get(1).effectiveTo()).isNotNull();
    assertThat(updated.versions().get(0).effectiveTo()).isNull();

    // 解除：变体退回未映射，历史版本一条不少
    var removed =
        as(
            actorA,
            "channel.operation.update",
            () ->
                service.remove(
                    created.mapping().id().toString(),
                    new RemoveMappingRequest("平台下架，解除映射")));
    assertThat(removed.mapping().status()).isEqualTo("REMOVED");
    assertThat(removed.mapping().internalSkuId()).isNull();
    assertThat(variantMappingStatus(a, variant)).isEqualTo("UNMAPPED");
    assertThat(removed.versions()).hasSize(3);
    assertThat(versionCount(a, created.mapping().id())).isEqualTo(3);
  }

  @Test
  @DisplayName("4.1.2.1 解除之后可以重新建立映射（解除不是删除，是这条事实）")
  void mappingCanBeReEstablishedAfterRemoval() {
    UUID shop = seedShop(a, "RE-AMZ");
    UUID listingId = seedListing(actorA, shop, "RE-1", "重新映射测试", "{}");
    UUID variant = variantId(a, listingId, "V-1");
    UUID sku = UUID.randomUUID();

    var created =
        as(
            actorA,
            "channel.operation.create",
            () -> service.save(mapping(variant, sku, "ONE_TO_ONE", null, "首次建立")));
    as(
            actorA,
            "channel.operation.update",
            () ->
                service.remove(
                    created.mapping().id().toString(), new RemoveMappingRequest("先解除")));

    // 🔴 重新建立必须能成：被解除的行还占着 (tenant_id, listing_variant_id)，
    //   「一个变体至多一条生效映射」靠**部分唯一索引**表达，不是表级 UNIQUE。
    var again =
        as(
            actorA,
            "channel.operation.create",
            () -> service.save(mapping(variant, sku, "ONE_TO_ONE", null, "解除后重新建立")));
    assertThat(again.mapping().internalSkuId()).isEqualTo(sku);
    assertThat(again.mapping().status()).isEqualTo("ACTIVE");
    assertThat(again.mapping().currentVersionNo()).isEqualTo(1);
    assertThat(variantMappingStatus(a, variant)).isEqualTo("MAPPED");
  }

  @Test
  @DisplayName("4.1.2.1 套装（一对多）必须带大于 0 的组件数量；非套装不许带")
  void bundleQuantityIsValidated() {
    UUID shop = seedShop(a, "BUNDLE-AMZ");
    UUID listingId = seedListing(actorA, shop, "BUNDLE-1", "套装测试", "{}");
    UUID variant = variantId(a, listingId, "V-1");
    UUID sku = UUID.randomUUID();

    assertThat(catchThrowable(() -> as(actorA, "channel.operation.create",
        () -> service.save(mapping(variant, sku, "ONE_TO_MANY", null, "套装没给数量")))))
        .hasMessageContaining("必须给出大于 0 的 componentQuantity");
    assertThat(catchThrowable(() -> as(actorA, "channel.operation.create",
        () -> service.save(mapping(variant, sku, "ONE_TO_ONE", new BigDecimal("2"), "一对一却带了数量")))))
        .hasMessageContaining("只对一对多");

    var bundle =
        as(
            actorA,
            "channel.operation.create",
            () ->
                service.save(
                    mapping(variant, sku, "ONE_TO_MANY", new BigDecimal("3.000000"), "套装映射")));
    assertThat(bundle.mapping().mappingStatus()).isEqualTo("BUNDLE");
    assertThat(bundle.mapping().componentQuantity()).isEqualByComparingTo("3.000000");
    assertThat(variantMappingStatus(a, variant)).isEqualTo("BUNDLE");
  }

  // ==================================================================
  // 2. 🔴 绝不按名称猜 SKU
  // ==================================================================

  @Test
  @DisplayName("4.1.2.1 没有生效映射时解析为 PENDING_MAPPING 且不给任何 SKU；候选再像也不解析")
  void resolveNeverGuessesSku() {
    UUID shop = seedShop(a, "RES-AMZ");
    UUID listingId = seedListing(actorA, shop, "RES-1", "解析测试商品", "{}");
    UUID variant = variantId(a, listingId, "V-1");
    UUID sku = UUID.randomUUID();

    // 未建立映射 ⇒ 待映射
    var pending =
        as(
            actorA,
            "channel.operation.read",
            () ->
                service.resolve(
                    new MappingResolveRequest(List.of(variant), null)));
    assertThat(pending).hasSize(1);
    assertThat(pending.get(0).status()).isEqualTo("PENDING_MAPPING");
    assertThat(pending.get(0).internalSkuId()).isNull();
    assertThat(pending.get(0).pendingReason()).contains("绝不按名称");

    // 建了映射 ⇒ 解析得到 SKU
    as(actorA, "channel.operation.create",
        () -> service.save(mapping(variant, sku, "ONE_TO_ONE", null, "建立映射")));
    var resolved =
        as(
            actorA,
            "channel.operation.read",
            () -> service.resolve(new MappingResolveRequest(List.of(variant), null)));
    assertThat(resolved.get(0).status()).isEqualTo("RESOLVED");
    assertThat(resolved.get(0).internalSkuId()).isEqualTo(sku);
    assertThat(resolved.get(0).versionNo()).isEqualTo(1);
  }

  @Test
  @DisplayName("4.1.2.1 候选表里躺着高分候选时，解析仍然是 PENDING_MAPPING（候选没确认就不是映射）")
  void unconfirmedCandidateIsNotUsedByResolve() {
    UUID shop = seedShop(a, "GUESS-AMZ");
    UUID listingId = seedListing(actorA, shop, "GUESS-1", HEADPHONE_NAME, "{}");
    UUID variant = variantId(a, listingId, "V-1");

    // 走批量候选：标题相似度，catalog 里有一条同名 SKU
    var batch =
        as(
            actorA,
            "channel.operation.read",
            () ->
                service.batchCandidates(
                    new BatchCandidateRequest("TITLE_SIMILARITY", List.of(listingId), 0.01, 5),
                    bearerA));
    assertThat(batch.candidateCount()).isGreaterThan(0);
    assertThat(batch.requiresConfirmationCount()).isEqualTo(batch.candidateCount());

    // 🔴 候选还没确认 ⇒ 解析必须仍然是待映射，绝不拿候选顶上
    var pending =
        as(
            actorA,
            "channel.operation.read",
            () -> service.resolve(new MappingResolveRequest(List.of(variant), null)));
    assertThat(pending.get(0).status()).isEqualTo("PENDING_MAPPING");
    assertThat(pending.get(0).internalSkuId()).isNull();
  }

  // ==================================================================
  // 3. 批量候选与人工确认
  // ==================================================================

  @Test
  @DisplayName("4.1.2.1 批量候选只写候选表：确认之前没有任何映射行；确认后建映射并记 BATCH_CONFIRMED")
  void batchCandidatesRequireHumanConfirmation() {
    UUID shop = seedShop(a, "BATCH-AMZ");
    UUID listingId = seedListing(actorA, shop, "BATCH-1", HEADPHONE_NAME, "{}");
    UUID variant = variantId(a, listingId, "V-1");

    var batch =
        as(
            actorA,
            "channel.operation.read",
            () ->
                service.batchCandidates(
                    new BatchCandidateRequest("TITLE_SIMILARITY", List.of(listingId), 0.01, 5),
                    bearerA));

    assertThat(batch.rule()).isEqualTo("TITLE_SIMILARITY");
    assertThat(batch.scannedVariantCount()).isEqualTo(1);
    assertThat(batch.scannedSkus()).isGreaterThan(0);
    assertThat(batch.candidateCount()).isEqualTo(1);
    MappingCandidate candidate = batch.candidates().get(0);
    assertThat(candidate.internalSkuId()).isEqualTo(CATALOG_SKU_HEADPHONE);
    assertThat(candidate.requiresConfirmation()).isTrue();
    assertThat(candidate.decision()).isEqualTo("PENDING");
    assertThat(candidate.matchedText()).contains(HEADPHONE_NAME);

    // 🔴 分数是 PostgreSQL pg_trgm similarity() 的真算值：与库里算出来的必须一致
    BigDecimal expected =
        new BigDecimal(
                execAsTenantRows(
                        a,
                        "select similarity(lower('" + HEADPHONE_NAME + "'),lower('"
                            + HEADPHONE_NAME + "'))")
                    .get(0))
            .setScale(6, java.math.RoundingMode.HALF_UP);
    assertThat(candidate.score()).isEqualByComparingTo(expected);

    // 🔴 确认之前：没有映射行，变体仍是未映射
    assertThat(as(actorA, "channel.operation.read",
        () -> service.list(null, null, null, null, null, null, "RECENT", 0, 50)).items())
        .isEmpty();
    assertThat(variantMappingStatus(a, variant)).isEqualTo("UNMAPPED");

    // 人工确认 ⇒ 建立映射 + 生成版本
    var decided =
        as(
            actorA,
            "channel.operation.update",
            () ->
                service.decideCandidate(
                    candidate.id().toString(),
                    new CandidateDecisionRequest("CONFIRMED", "人工核对无误")));
    assertThat(decided.decision()).isEqualTo("CONFIRMED");
    assertThat(decided.mappingId()).isNotNull();
    assertThat(decided.versionNo()).isEqualTo(1);
    assertThat(variantMappingStatus(a, variant)).isEqualTo("MAPPED");

    var versions = as(actorA, "channel.operation.read",
        () -> service.load(decided.mappingId().toString())).versions();
    assertThat(versions).hasSize(1);
    assertThat(versions.get(0).changeSource()).isEqualTo("BATCH_CONFIRMED");
    assertThat(versions.get(0).changeReason()).contains("人工核对无误");

    // 🔴 一个候选只裁决一次：重复裁决 409，而不是静默改写已落库的映射
    assertThat(catchThrowable(() -> as(actorA, "channel.operation.update",
        () -> service.decideCandidate(
            candidate.id().toString(), new CandidateDecisionRequest("REJECTED", "改主意")))))
        .hasMessageContaining("已经裁决过");
  }

  @Test
  @DisplayName("4.1.2.1 SKU 编码匹配与条码匹配都取自 catalog 授权 API，精确命中才算")
  void skuCodeAndBarcodeRulesUseUpstreamCatalog() {
    UUID shop = seedShop(a, "RULE-AMZ");
    UUID listingId =
        seedListing(
            actorA,
            shop,
            "RULE-1",
            "规则测试商品",
            "{\"barcode\":\"" + HEADPHONE_BARCODE + "\"}");
    UUID variant = variantId(a, listingId, "V-1");

    // 变体的外部变体 id 就是内部 SKU 编码原文 ⇒ SKU 编码规则精确命中
    UUID listing2 =
        seedListing(actorA, shop, "RULE-2", "编码规则商品", "{}");
    execAsTenant(
        a,
        "update listing_variant set external_variant_id='" + HEADPHONE_CODE
            + "' where tenant_id='" + a + "' and listing_id='" + listing2 + "'");
    UUID variant2 = variantId(a, listing2, HEADPHONE_CODE);

    var byCode =
        as(
            actorA,
            "channel.operation.read",
            () ->
                service.batchCandidates(
                    new BatchCandidateRequest("SKU_CODE", List.of(listing2), null, 5), bearerA));
    assertThat(byCode.candidateCount()).isEqualTo(1);
    assertThat(byCode.candidates().get(0).internalSkuId()).isEqualTo(CATALOG_SKU_HEADPHONE);
    assertThat(byCode.candidates().get(0).score()).isEqualByComparingTo("1");
    // 🔴 精确匹配（SKU 编码 / 条码）不需要人工确认：它不是「猜」
    assertThat(byCode.candidates().get(0).requiresConfirmation()).isFalse();

    var byBarcode =
        as(
            actorA,
            "channel.operation.read",
            () ->
                service.batchCandidates(
                    new BatchCandidateRequest("BARCODE", List.of(listingId), null, 5), bearerA));
    assertThat(byBarcode.candidateCount()).isEqualTo(1);
    assertThat(byBarcode.candidates().get(0).internalSkuId()).isEqualTo(CATALOG_SKU_HEADPHONE);
    assertThat(byBarcode.candidates().get(0).matchedText()).isEqualTo(HEADPHONE_BARCODE);

    // 变体没被映射 ⇒ 仍然一个映射行都没有
    assertThat(as(actorA, "channel.operation.read",
        () -> service.list(null, null, null, null, null, null, "RECENT", 0, 50)).items())
        .isEmpty();
    assertThat(variantMappingStatus(a, variant)).isEqualTo("UNMAPPED");
  }

  // ==================================================================
  // 4. 未映射清单 / 多对一反查
  // ==================================================================

  @Test
  @DisplayName("4.1.2.1 未映射清单是真的：建了映射就不在清单里；回流目标未交付时如实声明")
  void unmappedListIsReal() {
    UUID shop = seedShop(a, "UNM-AMZ");
    UUID listingId = seedListing(actorA, shop, "UNM-1", "未映射测试", "{}");
    UUID variant = variantId(a, listingId, "V-1");
    UUID sku = UUID.randomUUID();

    var before = as(actorA, "channel.operation.read", () -> service.unmapped(null, null, 0, 50));
    assertThat(before.total()).isEqualTo(1);
    assertThat(before.items().get(0).listingVariantId()).isEqualTo(variant);
    // 🔴 回流目标 2.4.2.3 未交付：端点如实说 backflowDelivered=false，而不是假装已回流
    assertThat(before.backflowDelivered()).isFalse();
    assertThat(before.backflowOwnerModule()).contains("2.4.2.3");

    as(actorA, "channel.operation.create",
        () -> service.save(mapping(variant, sku, "ONE_TO_ONE", null, "建立映射")));

    var after = as(actorA, "channel.operation.read", () -> service.unmapped(null, null, 0, 50));
    assertThat(after.total()).isZero();
  }

  @Test
  @DisplayName("4.1.2.1 多对一反查：能回答「这个内部 SKU 被哪些平台变体共用」")
  void manyToOneReverseLookupWorks() {
    UUID shop = seedShop(a, "M2O-AMZ");
    UUID listingA = seedListing(actorA, shop, "M2O-1", "多对一商品 A", "{}");
    UUID listingB = seedListing(actorA, shop, "M2O-2", "多对一商品 B", "{}");
    UUID variantA = variantId(a, listingA, "V-1");
    UUID variantB = variantId(a, listingB, "V-1");
    UUID sku = UUID.randomUUID();

    as(actorA, "channel.operation.create",
        () -> service.save(mapping(variantA, sku, "MANY_TO_ONE", null, "共用同一个内部 SKU")));
    as(actorA, "channel.operation.create",
        () -> service.save(mapping(variantB, sku, "MANY_TO_ONE", null, "共用同一个内部 SKU")));

    var usage = as(actorA, "channel.operation.read", () -> service.skuUsage(sku.toString()));
    assertThat(usage.internalSkuId()).isEqualTo(sku);
    assertThat(usage.variantCount()).isEqualTo(2);
    assertThat(usage.variants()).extracting(MappedVariantRow::listingVariantId)
        .containsExactlyInAnyOrder(variantA, variantB);

    // 列表行里的「被几个变体共用」也是真的
    var rows = as(actorA, "channel.operation.read",
        () -> service.list(null, null, null, sku.toString(), null, null, "RECENT", 0, 50));
    assertThat(rows.total()).isEqualTo(2);
    assertThat(rows.items()).allSatisfy(r -> assertThat(r.sharedWithVariants()).isEqualTo(1));
  }

  // ==================================================================
  // 5. DB 层兜底
  // ==================================================================

  @Test
  @DisplayName("4.1.2.1 绕开服务层直接写 SQL 也写不进自相矛盾的映射 / 改写不了历史版本")
  void dbLevelGuardsHold() {
    UUID shop = seedShop(a, "GUARD-AMZ");
    UUID listingId = seedListing(actorA, shop, "GUARD-1", "兜底测试", "{}");
    UUID variant = variantId(a, listingId, "V-1");
    UUID sku = UUID.randomUUID();

    var created =
        as(actorA, "channel.operation.create",
            () -> service.save(mapping(variant, sku, "ONE_TO_ONE", null, "建立映射")));

    // 生效中的映射不许没有内部 SKU
    var raw = catchThrowable(() -> execAsTenant(a,
        "insert into listing_mapping (id,tenant_id,listing_id,listing_variant_id,relation_type,"
            + "status,mapping_status,internal_sku_id) values ('" + UUID.randomUUID() + "','" + a
            + "','" + listingId + "','" + variant + "','ONE_TO_ONE','ACTIVE','MAPPED',null)"));
    assertThat(raw).isNotNull();

    // 已关闭的版本行不可改写
    as(actorA, "channel.operation.create",
        () -> service.save(new SaveMappingRequest(
            created.mapping().id(), variant, "ONE_TO_ONE", UUID.randomUUID(), null, "第二版",
            EFFECTIVE_FROM.plusDays(1), 1)));
    var rewrite = catchThrowable(() -> execAsTenant(a,
        "update listing_mapping_version set internal_sku_id='" + UUID.randomUUID()
            + "' where tenant_id='" + a + "' and mapping_id='" + created.mapping().id()
            + "' and version_no=1"));
    assertThat(rewrite).isNotNull();
    assertThat(rewrite.getMessage()).contains("must not be rewritten");

    // 已关闭的版本行连删都不许
    var delete = catchThrowable(() -> execAsTenant(a,
        "delete from listing_mapping_version where tenant_id='" + a + "' and mapping_id='"
            + created.mapping().id() + "' and version_no=1"));
    assertThat(delete).isNotNull();
    assertThat(delete.getMessage()).contains("must not be deleted");
  }

  @Test
  @DisplayName("4.1.2.1 乐观锁：版本号不匹配就 409，不静默覆盖")
  void optimisticLockWorks() {
    UUID shop = seedShop(a, "LOCK-AMZ");
    UUID listingId = seedListing(actorA, shop, "LOCK-1", "乐观锁测试", "{}");
    UUID variant = variantId(a, listingId, "V-1");
    UUID sku = UUID.randomUUID();

    var created =
        as(actorA, "channel.operation.create",
            () -> service.save(mapping(variant, sku, "ONE_TO_ONE", null, "建立映射")));

    assertThat(catchThrowable(() -> as(actorA, "channel.operation.create",
        () -> service.save(new SaveMappingRequest(
            created.mapping().id(), variant, "ONE_TO_ONE", sku, null, "版本号写错",
            EFFECTIVE_FROM.plusDays(1), 7)))))
        .hasMessageContaining("乐观锁冲突");
  }

  // ==================================================================
  // 6. 时间字段写回值
  // ==================================================================

  @Test
  @DisplayName("4.1.2.1 timestamptz 往返不偏 8 小时：生效时间点断言写回的值逐字相等")
  void timestampRoundTripsWithoutShift() {
    UUID shop = seedShop(a, "TS-AMZ");
    UUID listingId = seedListing(actorA, shop, "TS-1", "时间测试", "{}");
    UUID variant = variantId(a, listingId, "V-1");
    UUID sku = UUID.randomUUID();

    var created =
        as(actorA, "channel.operation.create",
            () -> service.save(mapping(variant, sku, "ONE_TO_ONE", null, "时间测试")));

    assertThat(created.versions().get(0).effectiveFrom()).isEqualTo(EFFECTIVE_FROM);
    assertThat(created.versions().get(0).effectiveFrom().toInstant())
        .isEqualTo(EFFECTIVE_FROM.toInstant());

    var reloaded = as(actorA, "channel.operation.read",
        () -> service.load(created.mapping().id().toString()));
    assertThat(reloaded.versions().get(0).effectiveFrom().toString())
        .startsWith("2026-10-05T10:00");
  }

  // ==================================================================
  // 7. 端点往返
  // ==================================================================

  @Test
  @DisplayName("4.1.2.1 端点：401 与真实业务往返；跨租户与不存在返回逐字相同的 404")
  void endpointsWork() throws Exception {
    http.perform(get("/channel/listing-mappings")).andExpect(status().isUnauthorized());
    http.perform(get("/channel/listing-mappings/capabilities")).andExpect(status().isUnauthorized());

    UUID shop = seedShop(a, "EP-AMZ");
    UUID listingId = seedListing(actorA, shop, "EP-1", "端点测试", "{}");
    UUID variant = variantId(a, listingId, "V-1");
    UUID sku = UUID.randomUUID();

    http.perform(get("/channel/listing-mappings/capabilities").header("Authorization", bearerA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.dimensions[*].dimension",
            org.hamcrest.Matchers.hasItem("human_confirmation")))
        .andExpect(jsonPath("$.gapNote", org.hamcrest.Matchers.containsString("未交付段")));

    http.perform(get("/channel/listing-mappings/filter-options").header("Authorization", bearerA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.relationTypes", org.hamcrest.Matchers.hasItem("ONE_TO_MANY")));

    var detail =
        as(actorA, "channel.operation.create",
            () -> service.save(mapping(variant, sku, "ONE_TO_ONE", null, "端点测试")));
    UUID mappingId = detail.mapping().id();

    http.perform(get("/channel/listing-mappings").header("Authorization", bearerA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.items[0].internalSkuId").value(sku.toString()));

    http.perform(get("/channel/listing-mappings/" + mappingId).header("Authorization", bearerA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.mapping.id").value(mappingId.toString()))
        .andExpect(jsonPath("$.versions[0].versionNo").value(1));

    http.perform(get("/channel/listing-mappings/unmapped").header("Authorization", bearerA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.backflowDelivered").value(false));

    http.perform(get("/channel/listing-mappings/by-sku/" + sku).header("Authorization", bearerA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.variantCount").value(1));

    // 跨租户与不存在：逐字相同的 404
    var crossTenant = catchThrowable(() ->
        as(actorB, "channel.operation.read", () -> service.load(mappingId.toString())));
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
  @DisplayName("4.1.2.1 断言一：两租户互不可见")
  void tenantsAreIsolated() {
    UUID shopA = seedShop(a, "ISO-AMZ-1");
    UUID shopB = seedShop(b, "ISO-AMZ-2");
    UUID listingA = seedListing(actorA, shopA, "ISO-A", "A 的商品", "{}");
    UUID listingB = seedListing(actorB, shopB, "ISO-B", "B 的商品", "{}");
    UUID variantA = variantId(a, listingA, "V-1");
    UUID variantB = variantId(b, listingB, "V-1");
    UUID skuA = UUID.randomUUID();
    UUID skuB = UUID.randomUUID();

    as(actorA, "channel.operation.create",
        () -> service.save(mapping(variantA, skuA, "ONE_TO_ONE", null, "A 的映射")));
    as(actorB, "channel.operation.create",
        () -> service.save(mapping(variantB, skuB, "ONE_TO_ONE", null, "B 的映射")));

    // A 只看得见自己的映射
    var rowsA = as(actorA, "channel.operation.read",
        () -> service.list(null, null, null, null, null, null, "RECENT", 0, 50));
    assertThat(rowsA.total()).isEqualTo(1);
    assertThat(rowsA.items().get(0).internalSkuId()).isEqualTo(skuA);

    var rowsB = as(actorB, "channel.operation.read",
        () -> service.list(null, null, null, null, null, null, "RECENT", 0, 50));
    assertThat(rowsB.total()).isEqualTo(1);
    assertThat(rowsB.items().get(0).internalSkuId()).isEqualTo(skuB);

    // 未映射清单也互不可见
    assertThat(as(actorA, "channel.operation.read", () -> service.unmapped(null, null, 0, 50)).total())
        .isZero();
    assertThat(as(actorB, "channel.operation.read", () -> service.unmapped(null, null, 0, 50)).total())
        .isZero();

    // 多对一反查也按租户隔离
    assertThat(as(actorA, "channel.operation.read", () -> service.skuUsage(skuB.toString())).variantCount())
        .isZero();
  }

  @Test
  @DisplayName("4.1.2.1 断言二：伪造 tenant header 无效（租户只来自已认证成员关系）")
  void forgedTenantHeaderIsIgnored() throws Exception {
    UUID shopA = seedShop(a, "FORGE-AMZ-1");
    UUID shopB = seedShop(b, "FORGE-AMZ-2");
    UUID listingA = seedListing(actorA, shopA, "FORGE-A", "A 的商品", "{}");
    UUID listingB = seedListing(actorB, shopB, "FORGE-B", "B 的商品", "{}");
    UUID variantA = variantId(a, listingA, "V-1");
    UUID variantB = variantId(b, listingB, "V-1");

    var mappingB =
        as(actorB, "channel.operation.create",
            () -> service.save(mapping(variantB, UUID.randomUUID(), "ONE_TO_ONE", null, "B 的映射")));

    // A 的会话 + B 的头 ⇒ 仍然只看得见 A 的数据
    http.perform(get("/channel/listing-mappings")
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(0));

    // 伪造租户头也不能让 B 的映射被 A 读到
    http.perform(get("/channel/listing-mappings/" + mappingB.mapping().id())
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isNotFound());

    // 无会话一律 401
    http.perform(get("/channel/listing-mappings").header("X-Tenant-Id", b.toString()))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("4.1.2.1 断言三：连接池归还后不串租户（池压到 1，A→B→A 三轮）")
  void pooledConnectionsDoNotBleedTenants() {
    UUID shopA = seedShop(a, "POOL-AMZ-1");
    UUID shopB = seedShop(b, "POOL-AMZ-2");
    UUID listingA = seedListing(actorA, shopA, "POOL-A", "A 的商品", "{}");
    UUID listingB = seedListing(actorB, shopB, "POOL-B", "B 的商品", "{}");
    UUID variantA = variantId(a, listingA, "V-1");
    UUID variantB = variantId(b, listingB, "V-1");
    UUID skuA = UUID.randomUUID();
    UUID skuB = UUID.randomUUID();

    as(actorA, "channel.operation.create",
        () -> service.save(mapping(variantA, skuA, "ONE_TO_ONE", null, "A 的映射")));
    as(actorB, "channel.operation.create",
        () -> service.save(mapping(variantB, skuB, "ONE_TO_ONE", null, "B 的映射")));

    for (int i = 0; i < 3; i++) {
      var rowsA = as(actorA, "channel.operation.read",
          () -> service.list(null, null, null, null, null, null, "RECENT", 0, 50));
      var rowsB = as(actorB, "channel.operation.read",
          () -> service.list(null, null, null, null, null, null, "RECENT", 0, 50));
      assertThat(rowsA.total()).isEqualTo(1);
      assertThat(rowsA.items().get(0).internalSkuId()).isEqualTo(skuA);
      assertThat(rowsB.total()).isEqualTo(1);
      assertThat(rowsB.items().get(0).internalSkuId()).isEqualTo(skuB);

      // 未映射清单与多对一反查也走同一个连接池
      assertThat(as(actorA, "channel.operation.read", () -> service.unmapped(null, null, 0, 50)).total())
          .isZero();
      assertThat(as(actorB, "channel.operation.read", () -> service.unmapped(null, null, 0, 50)).total())
          .isZero();
      assertThat(as(actorA, "channel.operation.read", () -> service.skuUsage(skuB.toString())).variantCount())
          .isZero();
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
    pg.stop();
  }
}
