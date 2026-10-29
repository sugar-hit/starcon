package com.starcon.channel;

import static com.starcon.channel.ListingClaimModels.*;
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
import org.jooq.DSLContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 4.4.2.1 商品认领。
 *
 * <p>蓝图：「把已在 A 店铺的 listing 认领到 B 店铺（跨店搬家），复用原商品内容与映射，生成 B 店铺的新 listing 草稿。输入：源
 * listing；目标店铺与站点；保留/重写的内容字段选择。输出：目标店铺的 listing 草稿 + 待发布任务。关系：上游 = 4.1.1.2；下游 =
 * 4.3.1.1。<b>新店铺 listing 是全新平台资源</b>，需重新过平台审核；<b>源 listing 不受影响</b>。」
 *
 * <p>本测试类钉住的事：
 *
 * <ol>
 *   <li><b>🔴 源 listing 不受影响</b>：认领前后源商品的 status / review_status / version / updated_at
 *       与状态事件条数**逐字不变**；认领记录里「认领时」与「现在」两组快照也相等。
 *   <li><b>🔴 目标是草稿态、尚未过平台审核</b>：status=DRAFT、review_status=NOT_SUBMITTED、
 *       平台可售状态=UNKNOWN、评分 / 币种 / 上架时间全为 NULL；<b>没有任何发布记录被写入</b>。
 *   <li><b>🔴 待发布任务复用 V183</b>：publish_batch / publish_task 里真的有一行 PENDING 任务，
 *       且指向目标 listing；本模块**不建**平行任务表。
 *   <li><b>🔴 复用内容与映射</b>：目标 listing 有内容快照（V175）、变体与映射（V171 / V176），
 *       内部 SKU 逻辑 id 与源一致。
 *   <li><b>🔴 站点绑定字段不可跨站点保留</b>：平台类目 ID 跨站点沿用被 422 拒绝。
 *   <li><b>🔴 跨平台 / 同店铺 / 未启用站点</b>三种越界目标被逐个点名拒绝。
 *   <li><b>timestamptz 往返</b>：映射版本的生效时刻与认领记录的创建时刻**逐字相等**。
 *   <li><b>能力表</b>：售价 / 内部可售量 / 平台审核 / 内部内容草稿 / 跨平台认领 available=false + 归属。
 * </ol>
 *
 * <p>多租户三断言在 {@code ListingClaimTenancyIntegrationTest} 里（连接池压到 1，直查用
 * {@code channel_app} 而不是超级用户）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ListingClaimIntegrationTest {

  static final PostgreSQLContainer<?> pg =
      new PostgreSQLContainer<>("postgres:16-alpine").withUrlParam("sslmode", "disable");
  static final UUID user = UUID.randomUUID();
  static HttpServer identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static final ObjectMapper JSON = new ObjectMapper();

  UUID a, b;
  AccessService.Access actorA, actorB;
  String bearerA;

  // 🔴 平台与站点注册表由 V1 迁移**随代码发布**（只读码表）：测试直接用它，不自己造一份 ——
  //    自己造会撞上 platform.code / marketplace(platform_id,code) 的唯一约束。
  static final UUID AMAZON = UUID.fromString("11111111-1111-1111-1111-111111111101");
  static final UUID SHOPIFY = UUID.fromString("11111111-1111-1111-1111-111111111102");
  UUID SITE_US;
  UUID SITE_UK;
  UUID SITE_DE;

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
    // 平台与站点注册表由 V1 迁移随代码发布，这里只把要用的站点 id 读出来。
    SITE_US = marketplaceId(AMAZON, "US");
    SITE_UK = marketplaceId(AMAZON, "UK");
    SITE_DE = marketplaceId(SHOPIFY, "DE");
    assertThat(SITE_US).as("V1 迁移应当发布了 AMAZON 的美国站").isNotNull();
    assertThat(SITE_UK).isNotNull();
    assertThat(SITE_DE).isNotNull();
  }

  /** 从 V1 迁移发布的站点注册表里读一个站点 id。 */
  static UUID marketplaceId(UUID platformId, String code) {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement();
        var rs = s.executeQuery(
            "select id from marketplace where platform_id='" + platformId + "' and code='" + code + "'")) {
      return rs.next() ? UUID.fromString(rs.getString(1)) : null;
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
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

  @Autowired ListingClaimService service;
  @Autowired ListingService listService;
  @Autowired ListingDetailService detailService;
  @Autowired AccessService access;
  @Autowired MockMvc http;
  @Autowired DSLContext raw;

  // ==================================================================
  // 辅助
  // ==================================================================

  <T> T as(AccessService.Access who, String permission, java.util.function.Supplier<T> work) {
    return access.run(who, permission, work);
  }

  /** 在租户上下文里发起一次认领（租户由已认证成员关系决定，不来自任何头）。 */
  ListingClaimRow claim(AccessService.Access who, ClaimListingRequest request) {
    return as(who, "channel.operation.create", () -> service.claim(request, user));
  }

  /** 解析 jsonb 文本（PG 的 jsonb 会把 `/` 转义成 `\/`，直接 contains 会误判）。 */
  List<String> jsonArray(String raw) {
    try {
      var node = JSON.readTree(raw);
      List<String> out = new java.util.ArrayList<>();
      node.forEach(n -> out.add(n.asText()));
      return out;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  /** 🔴 在租户上下文里查一行（RLS 生效，能证明"当前租户真的看得到"）。 */
  List<org.jooq.Record> queryRows(
      AccessService.Access who, String sql, Object... binds) {
    return as(who, "channel.operation.read", () -> raw.fetch(sql, binds));
  }

  /** 🔴 在租户上下文里查一个标量。 */
  <T> T queryValue(AccessService.Access who, Class<T> type, String sql, Object... binds) {
    return as(who, "channel.operation.read", () -> {
      var row = raw.fetchOne(sql, binds);
      return row == null ? null : type.cast(row.get(0, type));
    });
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

  /** 以管理员连接读一行计数（事实表本身受 RLS 约束，这里读的是"行在不在"，不是"读不读得到"）。 */
  static long countRaw(String sql) {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement();
        var rs = s.executeQuery(sql)) {
      return rs.next() ? rs.getLong(1) : 0L;
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  UUID seedShop(UUID tenant, String code, UUID platformId) {
    UUID id = UUID.randomUUID();
    execAsTenant(tenant,
        "insert into shop (tenant_id,id,platform_id,code,name) values ('" + tenant + "','" + id
            + "','" + platformId + "','" + code + "','测试店铺 " + code + "')"
            + " on conflict (tenant_id,code) do nothing");
    return id;
  }

  void enableSite(UUID tenant, UUID shopId, UUID marketplaceId) {
    execAsTenant(tenant,
        "insert into shop_marketplace (tenant_id,shop_id,marketplace_id,status)"
            + " values ('" + tenant + "','" + shopId + "','" + marketplaceId + "','ACTIVE')"
            + " on conflict (tenant_id,shop_id,marketplace_id) do update set status='ACTIVE'");
  }

  ListingRow save(AccessService.Access who, SaveListingRequest request) {
    return as(who, "channel.operation.create", () -> listService.save(request));
  }

  SaveListingRequest listingRequest(
      UUID shopId, UUID marketplaceId, String externalItemId, String title, String status,
      String reviewStatus, List<SaveVariantRequest> variants) {
    return new SaveListingRequest(
        null, shopId, marketplaceId, externalItemId, title, "Electronics > Audio", "cat-1",
        status, reviewStatus, "", "IN_STOCK", new BigDecimal("5"),
        new BigDecimal("4.7000"), new BigDecimal("128"), "USD",
        null, null, variants, null);
  }

  /**
   * 源 listing + 它的当前内容快照（认领的输入前提）。
   *
   * <p>三个变体，覆盖三条不同的映射来源：
   *
   * <ul>
   *   <li><b>v-1</b> —— 有真正的 {@code listing_mapping} 行（V176 的写入所有者那条路）
   *   <li><b>v-2</b> —— 未映射
   *   <li><b>v-3</b> —— 只有变体上的投影位（4.1.1.1 登记路径那条路）
   * </ul>
   */
  ListingRow seedSource(
      AccessService.Access who, UUID shopId, UUID marketplaceId, String externalItemId, String title) {
    UUID sku = UUID.randomUUID();
    UUID skuOnly = UUID.randomUUID();
    var row = save(who,
        listingRequest(shopId, marketplaceId, externalItemId, title, "ON_SALE", "APPROVED",
            List.of(
                new SaveVariantRequest(null, "v-1", "MAPPED", sku, null, "{\"color\":\"Black\"}"),
                new SaveVariantRequest(null, "v-2", "UNMAPPED", null, null, "{\"color\":\"White\"}"),
                new SaveVariantRequest(null, "v-3", "MAPPED", skuOnly, null, "{\"color\":\"Blue\"}"))));
    as(who, "channel.operation.create",
        () -> detailService.saveSnapshot(row.id().toString(),
            new ListingDetailModels.SaveContentSnapshotRequest(
                "en-US", title, "[\"卖点一\",\"卖点二\"]", "平台描述原文",
                "[\"https://img/1.jpg\"]", "https://video/1.mp4",
                "[{\"module\":\"标准 A+\"}]", "{\"color\":\"Black\"}", "{\"battery\":\"锂离子\"}",
                "SYNC", OffsetDateTime.parse("2026-10-10T08:00:00+08:00"), Boolean.TRUE)));
    seedMapping(who, row.id(), "v-1", sku);
    return row;
  }

  /** 给源 listing 的某个变体建一条真正的 {@code listing_mapping} + 版本 1（4.1.2.1 的形状）。 */
  void seedMapping(AccessService.Access who, UUID listingId, String externalVariantId, UUID skuId) {
    as(who, "channel.operation.create",
        () -> {
          UUID variantId =
              raw.fetchOne(
                      "select id from listing_variant where listing_id=? and external_variant_id=?",
                      listingId,
                      externalVariantId)
                  .get(0, UUID.class);
          UUID mappingId = UUID.randomUUID();
          raw.execute(
              "insert into listing_mapping (id,tenant_id,listing_id,listing_variant_id,relation_type,"
                  + "status,mapping_status,internal_sku_id,component_quantity,current_version_id,"
                  + "version,created_at,updated_at) values (?,?,?,?,'ONE_TO_ONE','ACTIVE','MAPPED',?,"
                  + "null::numeric,null::uuid,0,now(),now())",
              mappingId,
              currentTenantOf(who),
              listingId,
              variantId,
              skuId);
          UUID versionId = UUID.randomUUID();
          raw.execute(
              "insert into listing_mapping_version (id,tenant_id,mapping_id,version_no,relation_type,"
                  + "mapping_status,internal_sku_id,component_quantity,effective_from,effective_to,"
                  + "change_reason,change_source,created_at) values (?,?,?,1,'ONE_TO_ONE','MAPPED',?,"
                  + "null::numeric,now(),null::timestamptz,'人工建立','MANUAL',now())",
              versionId,
              currentTenantOf(who),
              mappingId,
              skuId);
          raw.execute(
              "update listing_mapping set current_version_id=? where id=?", versionId, mappingId);
          return null;
        });
  }

  UUID currentTenantOf(AccessService.Access who) {
    return who.tenantId();
  }

  List<ListingClaimFieldDecision> decisions(List<ListingClaimFieldDecision> list) {
    return list;
  }

  ClaimListingRequest claimRequest(
      UUID sourceListingId, UUID targetShopId, UUID targetMarketplaceId,
      List<ListingClaimFieldDecision> fieldDecisions) {
    return new ClaimListingRequest(
        sourceListingId, targetShopId, targetMarketplaceId, null, fieldDecisions, "跨店搬家");
  }

  ListingClaimFieldDecision keep(String field) {
    return new ListingClaimFieldDecision(field, "KEEP", null);
  }

  ListingClaimFieldDecision rewrite(String field, String value) {
    return new ListingClaimFieldDecision(field, "REWRITE", value);
  }

  ListingClaimFieldDecision skip(String field) {
    return new ListingClaimFieldDecision(field, "SKIP", null);
  }

  /** 一次"同站点跨店"的标准认领：标题 / 卖点 / 图片 / 描述保留，平台类目 ID 跳过。 */
  List<ListingClaimFieldDecision> standardDecisions() {
    return List.of(
        keep("TITLE"),
        keep("BULLETS"),
        keep("IMAGES"),
        keep("DESCRIPTION"),
        keep("A_PLUS"),
        keep("VIDEO_URL"),
        keep("CATEGORY_PATH"),
        skip("PLATFORM_CATEGORY_ID"),
        skip("CATEGORY_ATTRIBUTES"),
        skip("COMPLIANCE_STATEMENTS"));
  }

  // ==================================================================
  // 1. 认领产出：目标草稿 + 待发布任务
  // ==================================================================

  @Test
  @DisplayName("4.4.2.1 认领产出目标店铺的 listing 草稿 + 待发布任务（复用 V183 队列）")
  void claimProducesTargetDraftAndPendingPublishTask() {
    UUID shopA = seedShop(a, "CLAIM-A", AMAZON);
    UUID shopB = seedShop(a, "CLAIM-B", AMAZON);
    enableSite(a, shopA, SITE_US);
    enableSite(a, shopB, SITE_US);
    var source = seedSource(actorA, shopA, SITE_US, "SRC-1", "源商品标题");

    var result = as(actorA, "channel.operation.create",
        () -> service.claim(
            claimRequest(source.id(), shopB, SITE_US, standardDecisions()), user));

    // ---- 目标草稿
    assertThat(result.targetListingId()).isNotNull().isNotEqualTo(source.id());
    assertThat(result.targetShopCode()).isEqualTo("CLAIM-B");
    assertThat(result.targetMarketplaceCode()).isEqualTo("US");
    assertThat(result.targetTitle()).isEqualTo("源商品标题");
    // 🔴 目标恒为草稿态：尚未过平台审核
    assertThat(result.targetStatus()).isEqualTo("DRAFT");
    assertThat(result.targetReviewStatus()).isEqualTo("NOT_SUBMITTED");
    assertThat(result.targetPlatformStockState()).isEqualTo("UNKNOWN");
    // 平台商品 ID 还没有：草稿未发布，平台不分配
    assertThat(result.targetExternalItemId()).startsWith("CLAIM-PENDING:");
    assertThat(result.state()).isEqualTo("DRAFT");

    // ---- 目标 listing 行：评分 / 币种 / 上架时间全为 NULL（源商品的平台事实不搬过去）
    var target =
        as(actorA, "channel.operation.read", () -> listService.load(result.targetListingId().toString()));
    assertThat(target.rating()).as("新商品在平台上还没有评分").isNull();
    assertThat(target.ratingCount()).isNull();
    assertThat(target.currency()).as("源店铺的币种在目标店铺不成立").isNull();
    assertThat(target.launchedAt()).isNull();
    assertThat(target.status()).isEqualTo("DRAFT");
    assertThat(target.reviewStatus()).isEqualTo("NOT_SUBMITTED");
    assertThat(target.platformStockQty()).isNull();

    // ---- 复用内容：目标有内容快照（V175 既有表），内容与源一致
    var snapshotRows =
        queryRows(actorA,
            "select title, bullet_points, description, images, video_url, source, language"
                + " from listing_content_snapshot where listing_id=? and is_current",
            result.targetListingId());
    assertThat(snapshotRows).as("目标草稿必须有一份内容快照").hasSize(1);
    var snap = snapshotRows.get(0);
    assertThat(snap.get("title", String.class)).isEqualTo("源商品标题");
    assertThat(jsonArray(snap.get("bullet_points", String.class))).contains("卖点一");
    assertThat(snap.get("description", String.class)).isEqualTo("平台描述原文");
    assertThat(jsonArray(snap.get("images", String.class))).contains("https://img/1.jpg");
    assertThat(snap.get("video_url", String.class)).isEqualTo("https://video/1.mp4");
    assertThat(snap.get("source", String.class)).as("人工登记的内容快照").isEqualTo("MANUAL");
    assertThat(snap.get("language", String.class)).isEqualTo("en-US");

    // ---- 复用映射：变体与内部 SKU 逻辑 id 都搬过去了
    assertThat(result.variantCount()).isEqualTo(3);
    assertThat(result.mappedVariantCount()).isEqualTo(2);
    var variants =
        queryRows(actorA,
            "select external_variant_id, mapping_status, internal_sku_id from listing_variant"
                + " where listing_id=? order by external_variant_id",
            result.targetListingId());
    assertThat(variants).hasSize(3);
    assertThat(variants.get(0).get("external_variant_id", String.class)).isEqualTo("v-1");
    assertThat(variants.get(0).get("mapping_status", String.class)).isEqualTo("MAPPED");
    assertThat(variants.get(0).get("internal_sku_id", UUID.class)).isNotNull();
    assertThat(variants.get(1).get("external_variant_id", String.class)).isEqualTo("v-2");
    assertThat(variants.get(1).get("mapping_status", String.class)).isEqualTo("UNMAPPED");
    assertThat(variants.get(1).get("internal_sku_id", UUID.class)).isNull();
    // v-3 只有变体上的投影位：认领时也必须把映射搬过去（不能悄悄丢）
    assertThat(variants.get(2).get("external_variant_id", String.class)).isEqualTo("v-3");
    assertThat(variants.get(2).get("mapping_status", String.class)).isEqualTo("MAPPED");
    assertThat(variants.get(2).get("internal_sku_id", UUID.class)).isNotNull();
    // 🔴 映射也在目标侧建了行（V176 的写入所有者），并写了版本 1
    assertThat(
            queryRows(actorA,
                    "select mapping_status, internal_sku_id from listing_mapping where listing_id=?"
                        + " and status='ACTIVE'",
                    result.targetListingId())
                .size())
        .isEqualTo(2);
    assertThat(
            queryRows(actorA,
                    "select version_no, effective_to from listing_mapping_version where mapping_id in"
                        + " (select id from listing_mapping where listing_id=?)",
                    result.targetListingId()))
        .hasSize(2);

    // ---- 待发布任务：真的落在 V183 的队列里（PENDING，未派发）
    assertThat(result.publishBatchId()).isNotNull();
    assertThat(result.publishTaskId()).isNotNull();
    assertThat(countRaw("select count(*) from publish_task where id='" + result.publishTaskId() + "'"))
        .isEqualTo(1);
    assertThat(countRaw("select count(*) from publish_batch where id='" + result.publishBatchId() + "'"))
        .isEqualTo(1);
    assertThat(scalar("select state from publish_task where id='" + result.publishTaskId() + "'"))
        .isEqualTo("PENDING");
    assertThat(scalar("select listing_id::text from publish_task where id='" + result.publishTaskId() + "'"))
        .isEqualTo(result.targetListingId().toString());
    assertThat(scalar("select shop_id::text from publish_task where id='" + result.publishTaskId() + "'"))
        .isEqualTo(shopB.toString());
    // 🔴 本模块**不建**平行发布任务表：认领用到的全部是 V183 那两张
    assertThat(countRaw("select count(*) from pg_tables where tablename like 'listing_claim%publish%'"))
        .isZero();

    // 🔴 草稿 ≠ 已发布：一条发布记录都不该有
    assertThat(countRaw("select count(*) from listing_publish_record where listing_id='"
            + result.targetListingId() + "'"))
        .as("草稿尚未发布，不得有发布记录").isZero();
    assertThat(countRaw("select count(*) from listing_publish_record where listing_id='"
            + source.id() + "'"))
        .as("源商品也没有发布记录").isZero();
  }

  // ==================================================================
  // 2. 🔴 边界一：源 listing 不受影响
  // ==================================================================

  @Test
  @DisplayName("4.4.2.1 🔴 源 listing 不受影响：认领前后 status / review / version / updated_at 逐字不变")
  void sourceListingIsUntouched() {
    UUID shopA = seedShop(a, "UNTouched-A", AMAZON);
    UUID shopB = seedShop(a, "UNTouched-B", AMAZON);
    enableSite(a, shopA, SITE_US);
    enableSite(a, shopB, SITE_US);
    var source = seedSource(actorA, shopA, SITE_US, "SRC-UNT", "不动我");

    String beforeStatus = scalar("select status from listing where id='" + source.id() + "'");
    String beforeReview = scalar("select review_status from listing where id='" + source.id() + "'");
    String beforeVersion = scalar("select version from listing where id='" + source.id() + "'");
    String beforeUpdated = scalar("select updated_at from listing where id='" + source.id() + "'");
    String beforeTitle = scalar("select title from listing where id='" + source.id() + "'");
    String beforeExternal = scalar("select external_item_id from listing where id='" + source.id() + "'");
    long beforeEvents =
        countRaw("select count(*) from listing_status_event where listing_id='" + source.id() + "'");
    long beforeVariants =
        countRaw("select count(*) from listing_variant where listing_id='" + source.id() + "'");

    var result = as(actorA, "channel.operation.create",
        () -> service.claim(
            claimRequest(source.id(), shopB, SITE_US, standardDecisions()), user));

    // 🔴 逐字比对：源商品的一切都没变
    assertThat(scalar("select status from listing where id='" + source.id() + "'"))
        .as("源商品状态不得被认领改动").isEqualTo(beforeStatus);
    assertThat(scalar("select review_status from listing where id='" + source.id() + "'"))
        .isEqualTo(beforeReview);
    assertThat(scalar("select version from listing where id='" + source.id() + "'"))
        .as("源商品版本号不得前进（没有发生任何写入）").isEqualTo(beforeVersion);
    assertThat(scalar("select updated_at from listing where id='" + source.id() + "'"))
        .as("源商品的 updated_at 不得被触碰").isEqualTo(beforeUpdated);
    assertThat(scalar("select title from listing where id='" + source.id() + "'"))
        .isEqualTo(beforeTitle);
    assertThat(scalar("select external_item_id from listing where id='" + source.id() + "'"))
        .isEqualTo(beforeExternal);
    assertThat(countRaw("select count(*) from listing_status_event where listing_id='" + source.id() + "'"))
        .as("认领不得在源商品上留下状态事件").isEqualTo(beforeEvents);
    assertThat(countRaw("select count(*) from listing_variant where listing_id='" + source.id() + "'"))
        .as("认领不得往源商品上加变体").isEqualTo(beforeVariants);

    // 🔴 认领记录里「认领时」与「现在」两组快照也必须相等（这是界面上能给运营看的证据）
    assertThat(result.sourceStatus()).isEqualTo(result.sourceCurrentStatus());
    assertThat(result.sourceReviewStatus()).isEqualTo(result.sourceCurrentReviewStatus());
    assertThat(result.sourceVersion()).isEqualTo(result.sourceCurrentVersion());
    assertThat(result.sourceStatus()).isEqualTo(beforeStatus);
    assertThat(result.sourceVersion()).isEqualTo(Long.parseLong(beforeVersion));

    // 🔴 认领记录本身不可就地改写源 / 目标
    var rewritten =
        org.assertj.core.api.Assertions.catchThrowable(
            () ->
                as(actorA, "channel.operation.update",
                    () ->
                        raw.execute(
                            "update listing_claim set source_listing_id=? where tenant_id=? and id=?",
                            result.targetListingId(), a, result.claimId())));
    assertThat(rewritten).as("改写认领的源 / 目标必须被 DB 触发器拒绝").isNotNull();
  }

  // ==================================================================
  // 3. 🔴 边界二：目标是草稿态
  // ==================================================================

  @Test
  @DisplayName("4.4.2.1 🔴 目标恒为草稿态（尚未过平台审核），认领状态随目标离开草稿态而派生")
  void targetStaysDraftUntilItLeavesDraft() {
    UUID shopA = seedShop(a, "DRAFT-A", AMAZON);
    UUID shopB = seedShop(a, "DRAFT-B", AMAZON);
    enableSite(a, shopA, SITE_US);
    enableSite(a, shopB, SITE_US);
    var source = seedSource(actorA, shopA, SITE_US, "SRC-DRAFT", "草稿目标");

    var result = as(actorA, "channel.operation.create",
        () -> service.claim(
            claimRequest(source.id(), shopB, SITE_US, standardDecisions()), user));

    // 认领刚完成时：草稿态
    var fresh = as(actorA, "channel.operation.read", () -> service.detail(result.claimId().toString()));
    assertThat(fresh.state()).isEqualTo("DRAFT");
    assertThat(fresh.targetStatus()).isEqualTo("DRAFT");
    assertThat(fresh.targetReviewStatus()).isEqualTo("NOT_SUBMITTED");

    // 目标离开草稿态（= 发布流程提交平台）后，认领状态由 DB 触发器派生
    as(actorA, "channel.operation.update",
        () ->
            raw.execute(
                "update listing set status='UNDER_REVIEW' where tenant_id=? and id=?",
                a,
                result.targetListingId()));

    var after = as(actorA, "channel.operation.read", () -> service.detail(result.claimId().toString()));
    assertThat(after.state())
        .as("目标离开草稿态后认领状态由 DB 触发器派生为 PUBLISHED").isEqualTo("PUBLISHED");
    assertThat(after.targetStatus()).isEqualTo("UNDER_REVIEW");
    // 🔴 PUBLISHED 只表示"不再是草稿"，不代表平台已批准
    assertThat(after.targetReviewStatus()).isEqualTo("NOT_SUBMITTED");
  }

  // ==================================================================
  // 4. 越界目标被逐个点名拒绝
  // ==================================================================

  @Test
  @DisplayName("4.4.2.1 认领到同一个店铺 / 跨平台 / 站点未启用 —— 三种越界目标被 422 逐个点名")
  void invalidTargetsAreRejected() {
    UUID shopA = seedShop(a, "BAD-A", AMAZON);
    UUID shopB = seedShop(a, "BAD-B", AMAZON);
    UUID shopC = seedShop(a, "BAD-C", SHOPIFY);
    enableSite(a, shopA, SITE_US);
    enableSite(a, shopB, SITE_US);
    enableSite(a, shopC, SITE_DE);
    var source = seedSource(actorA, shopA, SITE_US, "SRC-BAD", "越界测试");

    // ---- 同一个店铺
    var sameShop = org.assertj.core.api.Assertions.catchThrowable(
        () -> as(actorA, "channel.operation.create",
            () -> service.claim(
                claimRequest(source.id(), shopA, SITE_US, standardDecisions()), user)));
    assertThat(sameShop).isNotNull();
    assertThat(((org.springframework.web.server.ResponseStatusException) sameShop).getReason())
        .contains("另一个店铺");

    // ---- 跨平台
    var crossPlatform = org.assertj.core.api.Assertions.catchThrowable(
        () -> as(actorA, "channel.operation.create",
            () -> service.claim(
                claimRequest(source.id(), shopC, SITE_DE, standardDecisions()), user)));
    assertThat(crossPlatform).isNotNull();
    assertThat(((org.springframework.web.server.ResponseStatusException) crossPlatform).getReason())
        .contains("跨平台认领不在本模块范围");

    // ---- 目标店铺没有启用该站点
    var noSite = org.assertj.core.api.Assertions.catchThrowable(
        () -> as(actorA, "channel.operation.create",
            () -> service.claim(
                claimRequest(source.id(), shopB, SITE_UK, standardDecisions()), user)));
    assertThat(noSite).isNotNull();
    assertThat(((org.springframework.web.server.ResponseStatusException) noSite).getReason())
        .contains("未启用该站点");

    // 三次失败都没有留下任何目标 listing（半成品比失败更糟）
    assertThat(countRaw("select count(*) from listing where tenant_id='" + a + "' and shop_id='"
            + shopB + "'"))
        .isZero();
    assertThat(countRaw("select count(*) from listing_claim where tenant_id='" + a + "'")).isZero();
  }

  // ==================================================================
  // 5. 🔴 站点绑定字段不可跨站点沿用
  // ==================================================================

  @Test
  @DisplayName("4.4.2.1 🔴 平台类目 ID 与站点绑定：跨站点沿用被 422 拒绝（不能写假事实）")
  void siteBoundFieldCannotBeKeptAcrossSites() {
    UUID shopA = seedShop(a, "SITE-A", AMAZON);
    UUID shopB = seedShop(a, "SITE-B", AMAZON);
    enableSite(a, shopA, SITE_US);
    enableSite(a, shopB, SITE_UK);
    var source = seedSource(actorA, shopA, SITE_US, "SRC-SITE", "站点测试");

    // 源 listing 的 platform_category_id = 'cat-1'（见 listingRequest）
    assertThat(scalar("select platform_category_id from listing where id='" + source.id() + "'"))
        .isEqualTo("cat-1");

    var keep = org.assertj.core.api.Assertions.catchThrowable(
        () -> as(actorA, "channel.operation.create",
            () -> service.claim(
                claimRequest(source.id(), shopB, SITE_UK,
                    List.of(keep("TITLE"), keep("PLATFORM_CATEGORY_ID"))),
                user)));
    assertThat(keep).isNotNull();
    assertThat(((org.springframework.web.server.ResponseStatusException) keep).getReason())
        .contains("与**站点**绑定");

    // 同站点保留 ⇒ 允许
    UUID shopB2 = seedShop(a, "SITE-B2", AMAZON);
    enableSite(a, shopB2, SITE_US);
    var ok = as(actorA, "channel.operation.create",
        () -> service.claim(
            claimRequest(source.id(), shopB2, SITE_US,
                List.of(keep("TITLE"), keep("PLATFORM_CATEGORY_ID"))),
            user));
    var row = as(actorA, "channel.operation.read", () -> listService.load(ok.targetListingId().toString()));
    assertThat(row.platformCategoryId()).isEqualTo("cat-1");
  }

  // ==================================================================
  // 6. 字段决策：重写 / 跳过 / 非法输入
  // ==================================================================

  @Test
  @DisplayName("4.4.2.1 保留 / 重写 / 跳过三种决策都落到目标草稿，并逐条留痕")
  void fieldDecisionsAreAppliedAndRecorded() {
    UUID shopA = seedShop(a, "FIELD-A", AMAZON);
    UUID shopB = seedShop(a, "FIELD-B", AMAZON);
    enableSite(a, shopA, SITE_US);
    enableSite(a, shopB, SITE_US);
    var source = seedSource(actorA, shopA, SITE_US, "SRC-FIELD", "源标题");

    var result = as(actorA, "channel.operation.create",
        () -> service.claim(
            claimRequest(source.id(), shopB, SITE_US,
                List.of(
                    rewrite("TITLE", "目标店铺专用标题"),
                    keep("BULLETS"),
                    rewrite("IMAGES", "[\"https://img/new.jpg\"]"),
                    skip("DESCRIPTION"),
                    keep("A_PLUS"))),
            user));

    // 目标 listing 的标题用的是重写值
    var target = as(actorA, "channel.operation.read",
        () -> listService.load(result.targetListingId().toString()));
    assertThat(target.title()).isEqualTo("目标店铺专用标题");

    // 目标内容快照：标题 / 图片用重写值，卖点保留，描述跳过 ⇒ 留空
    var snap =
        queryRows(actorA,
            "select title, bullet_points, images, description from listing_content_snapshot"
                + " where listing_id=? and is_current",
            result.targetListingId())
            .get(0);
    assertThat(snap.get("title", String.class)).isEqualTo("目标店铺专用标题");
    assertThat(jsonArray(snap.get("bullet_points", String.class))).contains("卖点一");
    assertThat(jsonArray(snap.get("images", String.class))).contains("https://img/new.jpg");
    assertThat(snap.get("description", String.class)).as("跳过的字段在目标上留空").isEmpty();

    // 逐字段决策都留了痕
    var fields = result.fields();
    assertThat(fields).hasSize(5);
    var titleRow = fields.stream().filter(f -> f.field().equals("TITLE")).findFirst().orElseThrow();
    assertThat(titleRow.decision()).isEqualTo("REWRITE");
    assertThat(titleRow.sourceValue()).isEqualTo("源标题");
    assertThat(titleRow.targetValue()).isEqualTo("目标店铺专用标题");
    assertThat(titleRow.sourceOrigin()).isEqualTo("PLATFORM_SNAPSHOT");
    var skipRow = fields.stream().filter(f -> f.field().equals("DESCRIPTION")).findFirst().orElseThrow();
    assertThat(skipRow.decision()).isEqualTo("SKIP");
    assertThat(skipRow.targetValue()).as("跳过 ⇒ 没有目标值").isNull();
    assertThat(skipRow.sourceValue()).isEqualTo("平台描述原文");
  }

  @Test
  @DisplayName("4.4.2.1 非法字段 / 空重写值 / 重复决策 / 缺标题决策 —— 逐条 422")
  void invalidFieldDecisionsAreRejected() {
    UUID shopA = seedShop(a, "INV-A", AMAZON);
    UUID shopB = seedShop(a, "INV-B", AMAZON);
    enableSite(a, shopA, SITE_US);
    enableSite(a, shopB, SITE_US);
    var source = seedSource(actorA, shopA, SITE_US, "SRC-INV", "非法输入");

    assertThat(reason(() -> claim(actorA,
            claimRequest(source.id(), shopB, SITE_US, List.of(rewrite("PRICE", "9.99"))))))
        .contains("未知的字段");
    assertThat(reason(() -> claim(actorA,
            claimRequest(source.id(), shopB, SITE_US, List.of(rewrite("TITLE", "  "))))))
        .contains("必须给出非空的值");
    assertThat(reason(() -> claim(actorA,
            claimRequest(source.id(), shopB, SITE_US,
                List.of(keep("TITLE"), keep("TITLE"))))))
        .contains("字段重复决策");
    assertThat(reason(() -> claim(actorA,
            claimRequest(source.id(), shopB, SITE_US, List.of(keep("BULLETS"))))))
        .contains("必须给出标题");
    // JSON 字段的重写值必须是合法 JSON
    assertThat(reason(() -> claim(actorA,
            claimRequest(source.id(), shopB, SITE_US,
                List.of(keep("TITLE"), rewrite("IMAGES", "不是 JSON"))))))
        .contains("必须是合法的 JSON");
    // 数组字段不接受对象
    assertThat(reason(() -> claim(actorA,
            claimRequest(source.id(), shopB, SITE_US,
                List.of(keep("TITLE"), rewrite("IMAGES", "{\"a\":1}"))))))
        .contains("必须是 JSON 数组");
  }

  @Test
  @DisplayName("4.4.2.1 源 listing 没有内容快照 ⇒ 422（没有「原商品内容」可复用，不建空壳草稿）")
  void sourceWithoutContentSnapshotIsRejected() {
    UUID shopA = seedShop(a, "NOSNAP-A", AMAZON);
    UUID shopB = seedShop(a, "NOSNAP-B", AMAZON);
    enableSite(a, shopA, SITE_US);
    enableSite(a, shopB, SITE_US);
    var source = save(actorA,
        listingRequest(shopA, SITE_US, "SRC-NOSNAP", "没有快照", "ON_SALE", "APPROVED", List.of()));

    assertThat(reason(() -> claim(actorA,
            claimRequest(source.id(), shopB, SITE_US, standardDecisions()))))
        .contains("尚未登记平台内容快照");
    assertThat(countRaw("select count(*) from listing where tenant_id='" + a + "' and shop_id='"
            + shopB + "'"))
        .isZero();
  }

  // ==================================================================
  // 7. timestamptz 往返
  // ==================================================================

  @Test
  @DisplayName("4.4.2.1 🔴 timestamptz 往返：映射版本的生效时刻与认领记录的创建时刻逐字相等")
  void timestamptzRoundTripIsExact() {
    UUID shopA = seedShop(a, "TS-A", AMAZON);
    UUID shopB = seedShop(a, "TS-B", AMAZON);
    enableSite(a, shopA, SITE_US);
    enableSite(a, shopB, SITE_US);
    var source = seedSource(actorA, shopA, SITE_US, "SRC-TS", "时刻");

    var result = as(actorA, "channel.operation.create",
        () -> service.claim(
            claimRequest(source.id(), shopB, SITE_US, standardDecisions()), user));

    OffsetDateTime createdAt = result.createdAt();
    assertThat(createdAt).as("认领记录必须有创建时刻").isNotNull();

    // 🔴 逐字相等：瞬时与偏移都一致。
    //    （OffsetDateTime 用 Timestamp.valueOf(LocalDateTime) 绑会静默偏 −28800s；
    //      这里用 ?::timestamptz 绑定 PG 的事务时刻，往返后必须一模一样）
    var versions =
        queryRows(actorA,
            "select effective_from from listing_mapping_version where mapping_id in"
                + " (select id from listing_mapping where listing_id=?)",
            result.targetListingId());
    assertThat(versions).as("复用的映射必须各写一个版本").hasSize(2);
    for (var version : versions) {
      OffsetDateTime written = version.get(0, OffsetDateTime.class);
      assertThat(written).as("映射版本必须有生效时刻").isNotNull();
      assertThat(written.toInstant()).isEqualTo(createdAt.toInstant());
      assertThat(written.getOffset()).isEqualTo(createdAt.getOffset());
      assertThat(written).isEqualTo(createdAt);
    }

    // 认领记录的创建时刻本身也必须是可往返的 timestamptz
    OffsetDateTime reread =
        queryValue(actorA, OffsetDateTime.class,
            "select created_at from listing_claim where id=?", result.claimId());
    assertThat(reread).isNotNull();
    assertThat(reread.toInstant()).isEqualTo(createdAt.toInstant());
  }

  // ==================================================================
  // 8. 能力表与读取面
  // ==================================================================

  @Test
  @DisplayName("4.4.2.1 能力表：未交付维度 available=false + 归属 + 影响（不伪造已交付）")
  void capabilitiesDeclareUnavailableDimensions() {
    var caps = as(actorA, "channel.operation.read", () -> service.capabilities());
    for (String key : List.of("price", "internal_stock", "platform_approval", "internal_content_draft",
        "cross_platform_claim")) {
      var dim = caps.dimensions().stream().filter(d -> d.dimension().equals(key)).findFirst();
      assertThat(dim).as("维度 %s 必须存在", key).isPresent();
      assertThat(dim.get().available()).as("%s 必须 available=false", key).isFalse();
      assertThat(dim.get().ownerModule()).as("%s 必须写明归属", key).isNotBlank();
      assertThat(dim.get().impact()).as("%s 必须写明影响", key).isNotBlank();
    }
    assertThat(caps.priceAvailable()).isFalse();
    assertThat(caps.priceOwnerModule()).contains("05");
    assertThat(caps.draftOnly()).isTrue();
    assertThat(caps.sourceUntouched()).isTrue();
    assertThat(caps.draftNote()).contains("重新过平台审核");
    assertThat(caps.sourceUntouchedNote()).contains("不修改也不下架");
    assertThat(caps.fields()).contains("TITLE", "PLATFORM_CATEGORY_ID", "CATEGORY_ATTRIBUTES");
    assertThat(caps.decisions()).containsExactly("KEEP", "REWRITE", "SKIP");
  }

  @Test
  @DisplayName("4.4.2.1 认领历史可读、可筛、可按商品反查")
  void claimsAreListableAndFilterable() {
    UUID shopA = seedShop(a, "LIST-A", AMAZON);
    UUID shopB = seedShop(a, "LIST-B", AMAZON);
    enableSite(a, shopA, SITE_US);
    enableSite(a, shopB, SITE_US);
    var source = seedSource(actorA, shopA, SITE_US, "SRC-LIST", "历史");
    var result = as(actorA, "channel.operation.create",
        () -> service.claim(
            claimRequest(source.id(), shopB, SITE_US, standardDecisions()), user));

    var all = as(actorA, "channel.operation.read", () -> service.list(null, null, 0, 50));
    assertThat(all).hasSize(1);
    assertThat(all.get(0).claimId()).isEqualTo(result.claimId());

    assertThat(as(actorA, "channel.operation.read", () -> service.list("DRAFT", null, 0, 50)))
        .hasSize(1);
    assertThat(as(actorA, "channel.operation.read", () -> service.list("PUBLISHED", null, 0, 50)))
        .isEmpty();
    assertThat(as(actorA, "channel.operation.read", () -> service.list(null, "LIST-B", 0, 50)))
        .hasSize(1);
    assertThat(as(actorA, "channel.operation.read", () -> service.list(null, "LIST-A", 0, 50)))
        .as("按源店铺筛不应命中（认领是搬到目标店铺的）").isEmpty();

    // 按商品反查：作为源 / 作为目标都算
    assertThat(as(actorA, "channel.operation.read",
        () -> service.forListing(source.id().toString()))).hasSize(1);
    assertThat(as(actorA, "channel.operation.read",
        () -> service.forListing(result.targetListingId().toString()))).hasSize(1);
  }

  @Test
  @DisplayName("4.4.2.1 HTTP 面：认领走 POST，能力声明与历史走 GET")
  void httpSurfaceWorks() throws Exception {
    UUID shopA = seedShop(a, "HTTP-A", AMAZON);
    UUID shopB = seedShop(a, "HTTP-B", AMAZON);
    enableSite(a, shopA, SITE_US);
    enableSite(a, shopB, SITE_US);
    var source = seedSource(actorA, shopA, SITE_US, "SRC-HTTP", "HTTP 认领");

    String body = JSON.writeValueAsString(
        new ClaimListingRequest(
            source.id(), shopB, SITE_US, null, standardDecisions(), "HTTP 备注"));

    var response = http.perform(post("/channel/listing-claims")
            .header("Authorization", bearerA)
            .contentType("application/json")
            .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.targetStatus").value("DRAFT"))
        .andExpect(jsonPath("$.targetReviewStatus").value("NOT_SUBMITTED"))
        .andExpect(jsonPath("$.sourceStatus").value("ON_SALE"))
        .andExpect(jsonPath("$.sourceCurrentStatus").value("ON_SALE"))
        .andExpect(jsonPath("$.publishTaskId").isNotEmpty())
        .andReturn();
    assertThat(response.getResponse().getContentAsString()).contains("CLAIM-PENDING:");

    http.perform(get("/channel/listing-claims/capabilities").header("Authorization", bearerA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.priceAvailable").value(false))
        .andExpect(jsonPath("$.draftOnly").value(true));

    http.perform(get("/channel/listing-claims").header("Authorization", bearerA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1));

    // 不存在的认领 ⇒ 404（与跨租户逐字相同）
    http.perform(get("/channel/listing-claims/" + UUID.randomUUID())
            .header("Authorization", bearerA))
        .andExpect(status().isNotFound());
    // 畸形 id 也走同一条 404
    http.perform(get("/channel/listing-claims/not-a-uuid").header("Authorization", bearerA))
        .andExpect(status().isNotFound());
    // 无会话 ⇒ 401
    http.perform(get("/channel/listing-claims/capabilities")).andExpect(status().isUnauthorized());
  }

  private String reason(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable) {
    var thrown = org.assertj.core.api.Assertions.catchThrowable(callable);
    assertThat(thrown).as("应当抛异常").isNotNull();
    assertThat(thrown).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    return ((org.springframework.web.server.ResponseStatusException) thrown).getReason();
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
