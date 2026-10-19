package com.starcon.channel;

import static com.starcon.channel.ListingClaimModels.*;
import static com.starcon.channel.ListingMigrationModels.*;
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
 * 4.4.2.2 批量搬迁。
 *
 * <p>蓝图：「批量把一批 listing 从一个店铺搬到多个店铺，支持按规则筛选（类目、销量区间、价格区间），并对重复铺货风险给出提示。输入：
 * 筛选规则；目标店铺集合；重复度控制参数。输出：批量搬迁任务 + 结果明细；重复度风险提示。关系：上游 = 4.4.2.1；下游 = 4.3.1.2。
 * <b>搬迁后必须做标题差异化</b>，否则平台可能判定重复铺货并抑制 listing。」
 *
 * <p>本测试类钉住的事：
 *
 * <ol>
 *   <li><b>🔴 筛选是真筛选</b>：类目 / 价格区间 / 销量区间三个维度各自真过滤；价格命中的是
 *       {@code writeback_target.current_price}，销量维度**来源未接入**（命中数按未知值策略如实报，
 *       <b>绝不读成 0</b>）；排序与分页真的改变结果集。
 *   <li><b>🔴 标题差异化是流程的一部分</b>：产出的目标草稿标题与原标题不同；逐条留痕（原值 / 新值 / 规则 /
 *       谁改的）；DB 层的 CHECK 与触发器**直接拒掉**"没做差异化"的搬迁结果。
 *   <li><b>🔴 重复度分值是算出来的</b>：不同的相似度给出不同的分值与等级；目标店铺没有既有 listing 时
 *       等级是 UNKNOWN、<b>分值为 null（不是 0）</b>。
 *   <li><b>🔴 幂等</b>：同一个幂等键重复触发只产生一个批次；已搬迁的那一对搬第二次被跳过。
 *   <li><b>🔴 上游 4.4.2.1</b>：已被认领过去的商品不再重复搬迁。
 *   <li><b>🔴 源 listing 不受影响</b>；<b>🔴 待发布任务复用 V183</b>。
 *   <li><b>timestamptz 往返</b>：映射版本的生效时刻与搬迁结果的创建时刻逐字相等。
 * </ol>
 *
 * <p>多租户三断言在 {@code ListingMigrationTenancyIntegrationTest} 里。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ListingMigrationIntegrationTest {

  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID user = UUID.randomUUID();
  static HttpServer identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static final ObjectMapper JSON = new ObjectMapper();

  UUID a, b;
  AccessService.Access actorA, actorB;
  String bearerA;

  static final UUID AMAZON = UUID.fromString("11111111-1111-1111-1111-111111111101");
  static final UUID SHOPIFY = UUID.fromString("11111111-1111-1111-1111-111111111102");
  UUID SITE_US;
  UUID SITE_UK;

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
    SITE_US = marketplaceId(AMAZON, "US");
    SITE_UK = marketplaceId(AMAZON, "UK");
    assertThat(SITE_US).as("V1 迁移应当发布了 AMAZON 的美国站").isNotNull();
    assertThat(SITE_UK).isNotNull();
  }

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
    r.add("spring.datasource.hikari.maximum-pool-size", () -> "1");
  }

  @Autowired ListingMigrationService service;
  @Autowired ListingClaimService claimService;
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

  static long countRaw(String sql) {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement();
        var rs = s.executeQuery(sql)) {
      return rs.next() ? rs.getLong(1) : 0L;
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  <T> T queryValue(AccessService.Access who, Class<T> type, String sql, Object... binds) {
    return as(who, "channel.operation.read", () -> {
      var row = raw.fetchOne(sql, binds);
      return row == null ? null : type.cast(row.get(0, type));
    });
  }

  UUID seedShop(UUID tenant, String code, UUID platformId) {
    UUID id = UUID.randomUUID();
    execAsTenant(tenant,
        "insert into shop (tenant_id,id,platform_id,code,name) values ('" + tenant + "','" + id
            + "','" + platformId + "','" + code + "','测试店铺 " + code + "')"
            + " on conflict (tenant_id,code) do nothing");
    execAsTenant(tenant,
        "insert into shop_marketplace (tenant_id,shop_id,marketplace_id,status)"
            + " values ('" + tenant + "','" + id + "','" + SITE_US + "','ACTIVE')"
            + " on conflict (tenant_id,shop_id,marketplace_id) do update set status='ACTIVE'");
    return id;
  }

  /** 源 listing + 当前内容快照（搬迁的输入前提）。 */
  ListingRow seedSource(
      AccessService.Access who,
      UUID shopId,
      String externalItemId,
      String title,
      String categoryPath,
      String platformCategoryId,
      String bullets,
      String description,
      String attributes) {
    UUID sku = UUID.randomUUID();
    var row =
        as(
            who,
            "channel.operation.create",
            () ->
                listService.save(
                    new SaveListingRequest(
                        null, shopId, SITE_US, externalItemId, title, categoryPath, platformCategoryId,
                        "ON_SALE", "APPROVED", "", "IN_STOCK", new BigDecimal("5"),
                        new BigDecimal("4.7000"), new BigDecimal("128"), "USD",
                        null, null,
                        List.of(new SaveVariantRequest(null, "v-1", "MAPPED", sku, null, "{}")),
                        null)));
    as(who, "channel.operation.create",
        () -> detailService.saveSnapshot(row.id().toString(),
            new ListingDetailModels.SaveContentSnapshotRequest(
                "en-US", title, bullets, description, "[]", "", "[]", attributes, "{}",
                "SYNC", null, Boolean.TRUE)));
    return row;
  }

  /** 登记平台侧在售价快照（价格维度的**唯一**真实来源，V169 writeback_target）。 */
  void registerPrice(UUID tenant, UUID shopId, String externalItemId, String price) {
    execAsTenant(tenant,
        "insert into writeback_target (id,tenant_id,shop_id,target_type,external_ref,listing_ref,sku_code,"
            + " current_stock,current_price,currency,status,blocked_reason,source_ref,version,created_at,updated_at)"
            + " values ('" + UUID.randomUUID() + "','" + tenant + "','" + shopId + "','LISTING','"
            + externalItemId + "','','',null," + price + ",'USD','ACTIVE','','',0,now(),now())");
  }

  MigrationFilterRequest filter(
      UUID shopId, List<String> categoryPaths, BigDecimal priceMin, BigDecimal priceMax,
      BigDecimal salesMin, BigDecimal salesMax, String policy, String sortBy) {
    return new MigrationFilterRequest(
        shopId, categoryPaths, null, priceMin, priceMax, salesMin, salesMax, policy, sortBy, "ASC", 0, 50);
  }

  /** 预览（必须在租户上下文里跑：租户由已认证成员关系决定，不来自任何头）。 */
  ListingMigrationPreview preview(
      AccessService.Access who, MigrationFilterRequest filter, List<MigrationTarget> targets) {
    return as(who, "channel.operation.read", () -> service.preview(filter, targets));
  }

  MigrationExecuteRequest executeRequest(
      MigrationFilterRequest filter, List<MigrationTarget> targets, MigrationDuplicatePolicy policy,
      String idempotencyKey) {
    return new MigrationExecuteRequest(filter, targets, policy, idempotencyKey, "测试搬迁");
  }

  // ==================================================================
  // 一、筛选：真分页 + 真筛选 + 真排序
  // ==================================================================

  @Test
  @DisplayName("4.4.2.2 类目与价格区间是真筛选：命中数随条件变化，未登记价格算未知不算 0")
  void categoryAndPriceFiltersAreReal() {
    UUID shop = seedShop(a, "FILTER-1", AMAZON);
    UUID target = seedShop(a, "FILTER-1-T", AMAZON);
    seedSource(actorA, shop, "F-A", "耳机 A", "Electronics > Audio", "cat-a", "[\"卖点\"]", "描述", "{}");
    seedSource(actorA, shop, "F-B", "耳机 B", "Electronics > Home", "cat-b", "[\"卖点\"]", "描述", "{}");
    seedSource(actorA, shop, "F-C", "耳机 C", "Electronics > Audio", "cat-a", "[\"卖点\"]", "描述", "{}");
    // A 有登记价 10.00；C 没有登记价 ⇒ 价格未知
    registerPrice(a, shop, "F-A", "10.00");

    // 只按类目
    var byCategory = preview(actorA, 
        filter(shop, List.of("Electronics > Audio"), null, null, null, null, "EXCLUDE", "TITLE"),
        List.of(new MigrationTarget(target, SITE_US)));
    assertThat(byCategory.total()).as("类目筛选命中 2 条").isEqualTo(2);
    assertThat(byCategory.explanation().dimensions())
        .filteredOn(d -> "CATEGORY".equals(d.dimension()))
        .singleElement()
        .satisfies(d -> assertThat(d.matchedCount()).isEqualTo(2));

    // 类目 + 价格区间（EXCLUDE：未知的 C 被排除）
    var priced = preview(actorA, 
        filter(shop, List.of("Electronics > Audio"), new BigDecimal("5"), new BigDecimal("20"),
            null, null, "EXCLUDE", "TITLE"),
        List.of(new MigrationTarget(target, SITE_US)));
    assertThat(priced.total()).as("价格区间命中 1 条（只有 A 有登记价且落在区间内）").isEqualTo(1);
    assertThat(priced.items()).singleElement()
        .satisfies(c -> assertThat(c.sourceTitle()).isEqualTo("耳机 A"));

    // 未知值策略 INCLUDE：未知的 C 也留下
    var included = preview(actorA, 
        filter(shop, List.of("Electronics > Audio"), new BigDecimal("5"), new BigDecimal("20"),
            null, null, "INCLUDE", "TITLE"),
        List.of(new MigrationTarget(target, SITE_US)));
    assertThat(included.total()).as("INCLUDE 策略下未知的 C 也命中").isEqualTo(2);

    // 筛选说明里价格维度必须说清楚"未知不是 0"
    var priceReport = priced.explanation().dimensions().stream()
        .filter(d -> "PRICE".equals(d.dimension())).findFirst().orElseThrow();
    assertThat(priceReport.unknownCount())
        .as("基线是源店铺的全部 3 条 listing，其中 2 条没有登记价格（未知，不是 0）")
        .isEqualTo(2);
    assertThat(priceReport.note()).contains("未知");
  }

  @Test
  @DisplayName("4.4.2.2 销量维度来源未接入：命中 0 条并如实说明是未知而不是 0")
  void salesDimensionIsNotFabricated() {
    UUID shop = seedShop(a, "SALES-1", AMAZON);
    UUID target = seedShop(a, "SALES-1-T", AMAZON);
    seedSource(actorA, shop, "S-1", "商品一", "Electronics > Audio", "cat-a", "[]", "描述", "{}");
    seedSource(actorA, shop, "S-2", "商品二", "Electronics > Audio", "cat-a", "[]", "描述", "{}");

    var excluded = preview(actorA, 
        filter(shop, List.of("Electronics > Audio"), null, null, new BigDecimal("10"),
            new BigDecimal("100"), "EXCLUDE", "TITLE"),
        List.of(new MigrationTarget(target, SITE_US)));
    assertThat(excluded.total()).as("销量来源未接入 ⇒ 命中 0 条（全部销量未知）").isZero();
    var salesReport = excluded.explanation().dimensions().stream()
        .filter(d -> "SALES".equals(d.dimension())).findFirst().orElseThrow();
    assertThat(salesReport.sourceAvailable()).as("销量维度必须显式声明未接入").isFalse();
    assertThat(salesReport.ownerModule()).contains("4.5.1.1");
    assertThat(salesReport.unknownCount()).as("全部候选的销量都是未知").isEqualTo(2);
    assertThat(salesReport.matchedCount()).isZero();
    assertThat(salesReport.note()).contains("不是 0");

    // INCLUDE 策略：未知的都留下 ⇒ 命中数回到候选数
    var included = preview(actorA, 
        filter(shop, List.of("Electronics > Audio"), null, null, new BigDecimal("10"),
            new BigDecimal("100"), "INCLUDE", "TITLE"),
        List.of(new MigrationTarget(target, SITE_US)));
    assertThat(included.total()).as("INCLUDE 策略下未知的候选都留下").isEqualTo(2);
  }

  @Test
  @DisplayName("4.4.2.2 排序与分页真的改变结果集")
  void sortingAndPagingAreReal() {
    UUID shop = seedShop(a, "SORT-1", AMAZON);
    UUID target = seedShop(a, "SORT-1-T", AMAZON);
    seedSource(actorA, shop, "SO-C", "Charlie 商品", "Electronics > Audio", "cat-a", "[]", "描述", "{}");
    seedSource(actorA, shop, "SO-A", "Alpha 商品", "Electronics > Audio", "cat-a", "[]", "描述", "{}");
    seedSource(actorA, shop, "SO-B", "Bravo 商品", "Electronics > Audio", "cat-a", "[]", "描述", "{}");

    var sorted = preview(actorA, 
        filter(shop, List.of("Electronics > Audio"), null, null, null, null, "EXCLUDE", "TITLE"),
        List.of(new MigrationTarget(target, SITE_US)));
    assertThat(sorted.items()).extracting(c -> c.sourceTitle())
        .containsExactly("Alpha 商品", "Bravo 商品", "Charlie 商品");

    // 分页：size=2 只给前两条
    var paged = preview(actorA, 
        new MigrationFilterRequest(
            shop, List.of("Electronics > Audio"), null, null, null, null, null, "EXCLUDE", "TITLE",
            "ASC", 0, 2),
        List.of(new MigrationTarget(target, SITE_US)));
    assertThat(paged.total()).isEqualTo(3);
    assertThat(paged.items()).hasSize(2);
    assertThat(paged.items().get(0).sourceTitle()).isEqualTo("Alpha 商品");

    // 第二页
    var page2 = preview(actorA, 
        new MigrationFilterRequest(
            shop, List.of("Electronics > Audio"), null, null, null, null, null, "EXCLUDE", "TITLE",
            "ASC", 1, 2),
        List.of(new MigrationTarget(target, SITE_US)));
    assertThat(page2.items()).hasSize(1);
    assertThat(page2.items().get(0).sourceTitle()).isEqualTo("Charlie 商品");
  }

  // ==================================================================
  // 二、标题差异化：流程的一部分 + 逐条留痕
  // ==================================================================

  @Test
  @DisplayName("4.4.2.2 搬迁结果带差异化后的标题，且逐条留痕（原值 / 新值 / 规则 / 谁改的）")
  void migratedItemsCarryDifferentiatedTitlesWithTrace() {
    UUID shop = seedShop(a, "DIFF-1", AMAZON);
    UUID target = seedShop(a, "DIFF-1-T", AMAZON);
    var source = seedSource(actorA, shop, "D-1", "无线耳机 Pro", "Electronics > Audio", "cat-a",
        "[\"轻便\"]", "原始描述", "{\"color\":\"Black\"}");

    var batch = as(actorA, "channel.operation.create",
        () -> service.execute(
            executeRequest(
                filter(shop, List.of("Electronics > Audio"), null, null, null, null, "EXCLUDE", "TITLE"),
                List.of(new MigrationTarget(target, SITE_US)),
                new MigrationDuplicatePolicy("NONE", "PROCEED", 50, null),
                "diff-1"),
            user));
    assertThat(batch.migratedCount()).isEqualTo(1);

    var items = as(actorA, "channel.operation.read", () -> service.items(batch.batchId().toString(), null, 0, 50));
    assertThat(items).hasSize(1);
    var item = items.get(0);
    assertThat(item.state()).isEqualTo("MIGRATED");
    assertThat(item.originalTitle()).isEqualTo("无线耳机 Pro");
    assertThat(item.differentiatedTitle()).as("标题必须被差异化").isNotEqualTo("无线耳机 Pro");
    assertThat(item.differentiatedTitle()).contains("DIFF-1-T");
    assertThat(item.titleDifferentiated()).isTrue();

    // 目标草稿真的落库了，标题就是差异化后的标题
    String targetTitle = queryValue(actorA, String.class,
        "select title from listing where tenant_id=? and id=?", a, item.targetListingId());
    assertThat(targetTitle).isEqualTo(item.differentiatedTitle());

    // 逐条留痕：原值 → 差异化后的值 + 规则 + 谁改的
    assertThat(item.diffs()).isNotEmpty();
    var titleDiff = item.diffs().stream().filter(d -> "TITLE".equals(d.field())).findFirst().orElseThrow();
    assertThat(titleDiff.sourceValue()).isEqualTo("无线耳机 Pro");
    assertThat(titleDiff.targetValue()).isEqualTo(item.differentiatedTitle());
    assertThat(titleDiff.ruleId()).isNotBlank();
    assertThat(titleDiff.actorId()).isEqualTo(user);
    assertThat(item.diffs().stream().map(ListingMigrationContentDiffRow::field))
        .contains("TITLE", "BULLETS", "DESCRIPTION");
  }

  @Test
  @DisplayName("4.4.2.2 DB 层拒绝没做差异化的搬迁结果（只改大小写也不算差异化）")
  void databaseRejectsUndifferentiatedMigration() {
    UUID shop = seedShop(a, "DIFF-2", AMAZON);
    UUID target = seedShop(a, "DIFF-2-T", AMAZON);
    var source = seedSource(actorA, shop, "D-2", "Same Title", "Electronics > Audio", "cat-a",
        "[]", "描述", "{}");
    var batch = as(actorA, "channel.operation.create",
        () -> service.execute(
            executeRequest(
                filter(shop, List.of("Electronics > Audio"), null, null, null, null, "EXCLUDE", "TITLE"),
                List.of(new MigrationTarget(target, SITE_US)),
                new MigrationDuplicatePolicy("NONE", "PROCEED", 50, null),
                "diff-2"),
            user));
    var item = as(actorA, "channel.operation.read",
        () -> service.items(batch.batchId().toString(), null, 0, 50)).get(0);

    // 逐字相同 ⇒ 被 CHECK / 触发器拒
    assertThatThrownBy(
            () -> as(actorA, "channel.operation.create",
                () -> raw.execute(
                    """
                    insert into listing_migration_item
                      (id,tenant_id,batch_id,source_listing_id,source_shop_id,target_shop_id,
                       target_marketplace_id,target_listing_id,source_title,target_title,state,
                       duplicate_level,created_at,updated_at)
                    values (?,?,?,?,?,?,?,?,?,?,'MIGRATED','LOW',now(),now())
                    """,
                    UUID.randomUUID(), a, batch.batchId(), source.id(), shop, target, SITE_US,
                    item.targetListingId(), "Same Title", "Same Title")))
        .as("逐字相同的标题必须被 DB 拒")
        .hasMessageContaining("differentiated");

    // 只改大小写 ⇒ 归一化后相同，也必须被拒
    assertThatThrownBy(
            () -> as(actorA, "channel.operation.create",
                () -> raw.execute(
                    """
                    insert into listing_migration_item
                      (id,tenant_id,batch_id,source_listing_id,source_shop_id,target_shop_id,
                       target_marketplace_id,target_listing_id,source_title,target_title,state,
                       duplicate_level,created_at,updated_at)
                    values (?,?,?,?,?,?,?,?,?,?,'MIGRATED','LOW',now(),now())
                    """,
                    UUID.randomUUID(), a, batch.batchId(), source.id(), shop, target, SITE_US,
                    item.targetListingId(), "Same Title", "same title")))
        .as("只改大小写不算差异化，必须被 DB 拒")
        .hasMessageContaining("differentiated");
  }

  // ==================================================================
  // 三、重复度：分值是算出来的
  // ==================================================================

  @Test
  @DisplayName("4.4.2.2 重复度分值是算出来的：不同的相似度给出不同的分值与等级")
  void duplicateScoreIsComputedNotConstant() {
    UUID shop = seedShop(a, "RISK-1", AMAZON);
    UUID target = seedShop(a, "RISK-1-T", AMAZON);
    // 目标店铺里已有一条同名商品 ⇒ 高度重复
    seedSource(actorA, target, "R-SAME", "Wireless Headphones Pro", "Electronics > Audio", "cat-a",
        "[]", "描述", "{\"color\":\"Black\"}");
    // 源商品：与它几乎一样
    var same = seedSource(actorA, shop, "R-1", "Wireless Headphones Pro", "Electronics > Audio", "cat-a",
        "[]", "描述", "{\"color\":\"Black\"}");
    // 源商品：与它毫不相干
    var other = seedSource(actorA, shop, "R-2", "厨房定时器 家用烘焙不锈钢", "Home > Kitchen > Timers",
        "cat-z", "[]", "描述", "{\"material\":\"steel\"}");

    var batch = as(actorA, "channel.operation.create",
        () -> service.execute(
            executeRequest(
                filter(shop, null, null, null, null, null, "EXCLUDE", "TITLE"),
                List.of(new MigrationTarget(target, SITE_US)),
                new MigrationDuplicatePolicy("NONE", "PROCEED", 50, null),
                "risk-1"),
            user));

    var risks = as(actorA, "channel.operation.read", () -> service.duplicateRisks(null, target, 0, 50));
    assertThat(risks).hasSize(2);
    var sameRisk = risks.stream().filter(r -> same.id().equals(r.sourceListingId())).findFirst().orElseThrow();
    var otherRisk = risks.stream().filter(r -> other.id().equals(r.sourceListingId())).findFirst().orElseThrow();

    assertThat(sameRisk.score()).as("同名商品的分值必须算出来").isNotNull();
    assertThat(sameRisk.score()).isGreaterThan(otherRisk.score());
    assertThat(sameRisk.level()).as("几乎同名 ⇒ 风险等级必须是高的").isIn("HIGH", "CRITICAL");
    assertThat(sameRisk.comparableTitle()).isEqualTo("Wireless Headphones Pro");
    assertThat(sameRisk.titleSimilarity()).isNotNull();
    assertThat(sameRisk.basis().explanation()).contains("加权");
    assertThat(sameRisk.basis().titleWeight()).isEqualTo(0.60);
    assertThat(sameRisk.basis().comparableCount()).isEqualTo(1);

    assertThat(otherRisk.level()).as("毫不相干的商品 ⇒ 风险低").isIn("LOW", "MEDIUM");
    assertThat(otherRisk.score()).isLessThan(sameRisk.score());
  }

  @Test
  @DisplayName("4.4.2.2 目标店铺没有既有 listing 时风险是 UNKNOWN（分值为 null，不是 0）")
  void duplicateRiskIsUnknownWhenThereIsNothingToCompare() {
    UUID shop = seedShop(a, "RISK-2", AMAZON);
    UUID target = seedShop(a, "RISK-2-T", AMAZON);
    seedSource(actorA, shop, "U-1", "独占商品", "Electronics > Audio", "cat-a", "[]", "描述", "{}");

    var batch = as(actorA, "channel.operation.create",
        () -> service.execute(
            executeRequest(
                filter(shop, null, null, null, null, null, "EXCLUDE", "TITLE"),
                List.of(new MigrationTarget(target, SITE_US)),
                new MigrationDuplicatePolicy("NONE", "PROCEED", 50, null),
                "risk-2"),
            user));
    assertThat(batch.migratedCount()).isEqualTo(1);

    var risks = as(actorA, "channel.operation.read", () -> service.duplicateRisks(null, target, 0, 50));
    assertThat(risks).hasSize(1);
    assertThat(risks.get(0).level()).isEqualTo("UNKNOWN");
    assertThat(risks.get(0).score()).as("未知不是 0：分值必须是 null").isNull();
    assertThat(risks.get(0).comparableCount()).isZero();
    assertThat(risks.get(0).basis().explanation()).contains("不是 0 分");
  }

  @Test
  @DisplayName("4.4.2.2 重复度控制参数：达到上限等级的搬迁项被跳过并写明原因")
  void duplicatePolicySkipsRiskyItems() {
    UUID shop = seedShop(a, "RISK-3", AMAZON);
    UUID target = seedShop(a, "RISK-3-T", AMAZON);
    seedSource(actorA, target, "P-SAME", "Wireless Headphones Pro", "Electronics > Audio", "cat-a",
        "[]", "描述", "{}");
    seedSource(actorA, shop, "P-1", "Wireless Headphones Pro", "Electronics > Audio", "cat-a",
        "[]", "描述", "{}");

    var batch = as(actorA, "channel.operation.create",
        () -> service.execute(
            executeRequest(
                filter(shop, null, null, null, null, null, "EXCLUDE", "TITLE"),
                List.of(new MigrationTarget(target, SITE_US)),
                new MigrationDuplicatePolicy("LOW", "PROCEED", 50, null),
                "risk-3"),
            user));
    assertThat(batch.migratedCount()).as("高风险项被跳过").isZero();
    assertThat(batch.skippedCount()).isEqualTo(1);
    var item = as(actorA, "channel.operation.read",
        () -> service.items(batch.batchId().toString(), null, 0, 50)).get(0);
    assertThat(item.state()).isEqualTo("SKIPPED");
    assertThat(item.skipReason()).contains("DUPLICATE_RISK");
    assertThat(item.duplicateLevel()).isIn("HIGH", "CRITICAL");
    assertThat(item.targetListingId()).as("跳过的项不产生目标草稿").isNull();
  }

  // ==================================================================
  // 四、幂等与「已搬迁」
  // ==================================================================

  @Test
  @DisplayName("4.4.2.2 幂等：同一个幂等键重复触发只产生一个批次、一份搬迁结果")
  void sameIdempotencyKeyProducesOneBatch() {
    UUID shop = seedShop(a, "IDEM-1", AMAZON);
    UUID target = seedShop(a, "IDEM-1-T", AMAZON);
    seedSource(actorA, shop, "I-1", "幂等商品", "Electronics > Audio", "cat-a", "[]", "描述", "{}");

    var request = executeRequest(
        filter(shop, List.of("Electronics > Audio"), null, null, null, null, "EXCLUDE", "TITLE"),
        List.of(new MigrationTarget(target, SITE_US)),
        new MigrationDuplicatePolicy("NONE", "PROCEED", 50, null),
        "idem-1");
    var first = as(actorA, "channel.operation.create", () -> service.execute(request, user));
    var second = as(actorA, "channel.operation.create", () -> service.execute(request, user));

    assertThat(second.batchId()).as("同一个幂等键 ⇒ 同一个批次").isEqualTo(first.batchId());
    assertThat(second.migratedCount()).isEqualTo(1);
    assertThat(countRaw("select count(*) from listing_migration_batch where tenant_id='" + a + "'"))
        .as("批次只有一条").isEqualTo(1);
    assertThat(countRaw("select count(*) from listing_migration_item where tenant_id='" + a + "'"))
        .as("搬迁结果只有一条").isEqualTo(1);
    assertThat(countRaw(
            "select count(*) from listing where tenant_id='" + a + "' and shop_id='" + target + "'"))
        .as("目标店铺只多了一个草稿").isEqualTo(1);
  }

  @Test
  @DisplayName("4.4.2.2 不给幂等键时同一个请求也幂等（按请求内容派生）")
  void derivedIdempotencyKeyIsStable() {
    UUID shop = seedShop(a, "IDEM-2", AMAZON);
    UUID target = seedShop(a, "IDEM-2-T", AMAZON);
    seedSource(actorA, shop, "I-2", "派生幂等商品", "Electronics > Audio", "cat-a", "[]", "描述", "{}");

    var request = executeRequest(
        filter(shop, List.of("Electronics > Audio"), null, null, null, null, "EXCLUDE", "TITLE"),
        List.of(new MigrationTarget(target, SITE_US)),
        new MigrationDuplicatePolicy("NONE", "PROCEED", 50, null),
        null);
    var first = as(actorA, "channel.operation.create", () -> service.execute(request, user));
    var second = as(actorA, "channel.operation.create", () -> service.execute(request, user));
    assertThat(second.batchId()).isEqualTo(first.batchId());
    assertThat(first.idempotencyKey()).startsWith("derived:");
  }

  @Test
  @DisplayName("4.4.2.2 已搬迁的那一对搬第二次被跳过（DB 层也搬不了第二次）")
  void alreadyMigratedPairIsSkipped() {
    UUID shop = seedShop(a, "IDEM-3", AMAZON);
    UUID target = seedShop(a, "IDEM-3-T", AMAZON);
    seedSource(actorA, shop, "I-3", "搬迁过一次", "Electronics > Audio", "cat-a", "[]", "描述", "{}");

    as(actorA, "channel.operation.create",
        () -> service.execute(
            executeRequest(
                filter(shop, List.of("Electronics > Audio"), null, null, null, null, "EXCLUDE", "TITLE"),
                List.of(new MigrationTarget(target, SITE_US)),
                new MigrationDuplicatePolicy("NONE", "PROCEED", 50, null),
                "idem-3-a"),
            user));
    // 换一个幂等键再搬一次 ⇒ 这一对已经是"已搬迁"
    var second = as(actorA, "channel.operation.create",
        () -> service.execute(
            executeRequest(
                filter(shop, List.of("Electronics > Audio"), null, null, null, null, "EXCLUDE", "TITLE"),
                List.of(new MigrationTarget(target, SITE_US)),
                new MigrationDuplicatePolicy("NONE", "PROCEED", 50, null),
                "idem-3-b"),
            user));
    assertThat(second.migratedCount()).as("已搬迁的不再搬").isZero();
    assertThat(second.skippedCount()).isEqualTo(1);
    var item = as(actorA, "channel.operation.read",
        () -> service.items(second.batchId().toString(), null, 0, 50)).get(0);
    assertThat(item.skipReason()).contains("ALREADY_MIGRATED");
    assertThat(countRaw(
            "select count(*) from listing where tenant_id='" + a + "' and shop_id='" + target + "'"))
        .as("目标店铺仍然只有一个草稿").isEqualTo(1);
  }

  @Test
  @DisplayName("4.4.2.2 上游 4.4.2.1：已被认领过去的商品不再重复搬迁")
  void claimedListingsAreNotMigratedAgain() {
    UUID shop = seedShop(a, "CLAIM-1", AMAZON);
    UUID target = seedShop(a, "CLAIM-1-T", AMAZON);
    var source = seedSource(actorA, shop, "C-1", "已认领商品", "Electronics > Audio", "cat-a",
        "[\"卖点\"]", "描述", "{}");

    // 🔴 4.4.2.1 把它认领到**同一个目标店铺** ⇒ 批量搬迁再搬一次就是重复铺货
    as(actorA, "channel.operation.create",
        () -> claimService.claim(
            new ClaimListingRequest(
                source.id(), target, SITE_US, null,
                List.of(
                    new ListingClaimFieldDecision("TITLE", "KEEP", null),
                    new ListingClaimFieldDecision("BULLETS", "KEEP", null)),
                ""),
            user));

    var batch = as(actorA, "channel.operation.create",
        () -> service.execute(
            executeRequest(
                filter(shop, List.of("Electronics > Audio"), null, null, null, null, "EXCLUDE", "TITLE"),
                List.of(new MigrationTarget(target, SITE_US)),
                new MigrationDuplicatePolicy("NONE", "PROCEED", 50, null),
                "claim-1"),
            user));
    assertThat(batch.migratedCount()).isZero();
    assertThat(batch.skippedCount()).isEqualTo(1);
    var item = as(actorA, "channel.operation.read",
        () -> service.items(batch.batchId().toString(), null, 0, 50)).get(0);
    assertThat(item.skipReason()).contains("ALREADY_CLAIMED");
  }

  // ==================================================================
  // 五、源不受影响 + 待发布任务复用 V183
  // ==================================================================

  @Test
  @DisplayName("4.4.2.2 源 listing 不受影响，目标恒为草稿态，待发布任务复用 V183")
  void sourceUntouchedTargetIsDraftAndPublishTaskIsReused() {
    UUID shop = seedShop(a, "DRAFT-1", AMAZON);
    UUID target = seedShop(a, "DRAFT-1-T", AMAZON);
    var source = seedSource(actorA, shop, "DR-1", "草稿态商品", "Electronics > Audio", "cat-a",
        "[\"卖点\"]", "描述", "{}");

    String statusBefore = queryValue(actorA, String.class, "select status from listing where id=?", source.id());
    String reviewBefore = queryValue(actorA, String.class, "select review_status from listing where id=?", source.id());
    long versionBefore = queryValue(actorA, Long.class, "select version from listing where id=?", source.id());
    long eventsBefore = countRaw("select count(*) from listing_status_event where listing_id='" + source.id() + "'");

    var batch = as(actorA, "channel.operation.create",
        () -> service.execute(
            executeRequest(
                filter(shop, List.of("Electronics > Audio"), null, null, null, null, "EXCLUDE", "TITLE"),
                List.of(new MigrationTarget(target, SITE_US)),
                new MigrationDuplicatePolicy("NONE", "PROCEED", 50, null),
                "draft-1"),
            user));
    var item = as(actorA, "channel.operation.read",
        () -> service.items(batch.batchId().toString(), null, 0, 50)).get(0);

    // 源逐字未变
    assertThat(queryValue(actorA, String.class, "select status from listing where id=?", source.id()))
        .isEqualTo(statusBefore);
    assertThat(queryValue(actorA, String.class, "select review_status from listing where id=?", source.id()))
        .isEqualTo(reviewBefore);
    assertThat(queryValue(actorA, Long.class, "select version from listing where id=?", source.id()))
        .isEqualTo(versionBefore);
    assertThat(countRaw("select count(*) from listing_status_event where listing_id='" + source.id() + "'"))
        .isEqualTo(eventsBefore);

    // 目标恒为草稿态、平台事实全为 NULL
    assertThat(queryValue(actorA, String.class, "select status from listing where id=?", item.targetListingId()))
        .isEqualTo("DRAFT");
    assertThat(queryValue(actorA, String.class, "select review_status from listing where id=?", item.targetListingId()))
        .isEqualTo("NOT_SUBMITTED");
    assertThat(queryValue(actorA, String.class, "select platform_stock_state from listing where id=?", item.targetListingId()))
        .isEqualTo("UNKNOWN");
    assertThat(queryValue(actorA, BigDecimal.class, "select rating from listing where id=?", item.targetListingId()))
        .isNull();
    assertThat(queryValue(actorA, String.class, "select currency from listing where id=?", item.targetListingId()))
        .isNull();
    assertThat(item.targetExternalItemId()).startsWith("MIGRATION-PENDING:");

    // 待发布任务真的落在 V183
    assertThat(batch.publishBatchId()).isNotNull();
    assertThat(item.publishTaskId()).isNotNull();
    assertThat(queryValue(actorA, String.class, "select state from publish_task where id=?", item.publishTaskId()))
        .isEqualTo("PENDING");
    assertThat(queryValue(actorA, UUID.class, "select listing_id from publish_task where id=?", item.publishTaskId()))
        .isEqualTo(item.targetListingId());
    assertThat(queryValue(actorA, UUID.class, "select shop_id from publish_task where id=?", item.publishTaskId()))
        .isEqualTo(target);

    // 本模块不建平行发布任务表
    assertThat(countRaw(
            "select count(*) from information_schema.tables where table_name='listing_migration_publish_task'"))
        .isZero();
  }

  @Test
  @DisplayName("4.4.2.2 timestamptz 往返：映射版本的生效时刻与搬迁结果的创建时刻逐字相等")
  void timestamptzRoundTrip() {
    UUID shop = seedShop(a, "TS-1", AMAZON);
    UUID target = seedShop(a, "TS-1-T", AMAZON);
    seedSource(actorA, shop, "TS-1", "时间戳商品", "Electronics > Audio", "cat-a", "[]", "描述", "{}");

    var batch = as(actorA, "channel.operation.create",
        () -> service.execute(
            executeRequest(
                filter(shop, List.of("Electronics > Audio"), null, null, null, null, "EXCLUDE", "TITLE"),
                List.of(new MigrationTarget(target, SITE_US)),
                new MigrationDuplicatePolicy("NONE", "PROCEED", 50, null),
                "ts-1"),
            user));
    var item = as(actorA, "channel.operation.read",
        () -> service.items(batch.batchId().toString(), null, 0, 50)).get(0);

    OffsetDateTime createdAt = queryValue(actorA, OffsetDateTime.class,
        "select created_at from listing_migration_item where id=?", item.itemId());
    OffsetDateTime effectiveFrom = queryValue(actorA, OffsetDateTime.class,
        """
        select v.effective_from from listing_mapping_version v
        join listing_mapping m on m.tenant_id=v.tenant_id and m.id=v.mapping_id
        where m.listing_id=? order by v.version_no desc limit 1
        """,
        item.targetListingId());
    assertThat(effectiveFrom).as("映射版本的生效时刻与搬迁结果创建时刻必须是同一个瞬时")
        .isEqualTo(createdAt);
  }

  // ==================================================================
  // 六、重复铺货风险 与 已搬迁 分开查
  // ==================================================================

  @Test
  @DisplayName("4.4.2.2 「重复铺货风险」与「已搬迁」是两种事实，分开查")
  void duplicateRisksAndMigratedAreSeparateQueries() {
    UUID shop = seedShop(a, "SEP-1", AMAZON);
    UUID target = seedShop(a, "SEP-1-T", AMAZON);
    seedSource(actorA, target, "SEP-SAME", "完全一样的标题", "Electronics > Audio", "cat-a",
        "[]", "描述", "{}");
    seedSource(actorA, shop, "SEP-1", "完全一样的标题", "Electronics > Audio", "cat-a",
        "[]", "描述", "{}");
    seedSource(actorA, shop, "SEP-2", "毫不相干的商品", "Home > Kitchen", "cat-z",
        "[]", "描述", "{}");

    as(actorA, "channel.operation.create",
        () -> service.execute(
            executeRequest(
                filter(shop, null, null, null, null, null, "EXCLUDE", "TITLE"),
                List.of(new MigrationTarget(target, SITE_US)),
                new MigrationDuplicatePolicy("NONE", "PROCEED", 50, null),
                "sep-1"),
            user));

    var risks = as(actorA, "channel.operation.read", () -> service.duplicateRisks(null, target, 0, 50));
    var migrated = as(actorA, "channel.operation.read", () -> service.migrated(null, target, 0, 50));

    assertThat(risks).as("风险评估覆盖所有候选（跳过的也留评估）").hasSize(2);
    assertThat(migrated).as("已搬迁只统计真的搬过去的").hasSize(2);
    assertThat(risks).extracting(ListingMigrationDuplicateRow::level)
        .as("同名商品的风险等级必须比毫不相干的商品高")
        .anyMatch(l -> "HIGH".equals(l) || "CRITICAL".equals(l))
        .anyMatch(l -> "LOW".equals(l) || "MEDIUM".equals(l));
    // 按等级筛风险
    String highLevel =
        risks.stream()
            .map(ListingMigrationDuplicateRow::level)
            .filter(l -> "HIGH".equals(l) || "CRITICAL".equals(l))
            .findFirst()
            .orElseThrow();
    var filtered = as(actorA, "channel.operation.read",
        () -> service.duplicateRisks(highLevel, target, 0, 50));
    assertThat(filtered).isNotEmpty();
    assertThat(filtered).allSatisfy(r -> assertThat(r.level()).isEqualTo(highLevel));
  }

  // ==================================================================
  // 七、能力声明 + HTTP 面
  // ==================================================================

  @Test
  @DisplayName("4.4.2.2 能力声明：销量维度未接入 + 差异化与幂等保证写明")
  void capabilitiesAreHonest() {
    var caps = as(actorA, "channel.operation.read", () -> service.capabilities());
    assertThat(caps.titleDifferentiationEnforced()).isTrue();
    assertThat(caps.titleDifferentiationNote()).contains("不同");
    assertThat(caps.riskScoreComputed()).isTrue();
    assertThat(caps.idempotencyNote()).contains("幂等");
    assertThat(caps.titleRules()).contains("STORE_MARKER");
    var sales = caps.dimensions().stream()
        .filter(d -> "sales_filter".equals(d.dimension())).findFirst().orElseThrow();
    assertThat(sales.available()).isFalse();
    assertThat(sales.ownerModule()).contains("4.5.1.1");
    var price = caps.dimensions().stream()
        .filter(d -> "price_filter".equals(d.dimension())).findFirst().orElseThrow();
    assertThat(price.available()).isTrue();
  }

  @Test
  @DisplayName("4.4.2.2 HTTP 面：预览 / 执行 / 列表 / 明细 / 风险 / 已搬迁 / 401 / 404 / 422")
  void httpSurface() throws Exception {
    UUID shop = seedShop(a, "HTTP-1", AMAZON);
    UUID target = seedShop(a, "HTTP-1-T", AMAZON);
    seedSource(actorA, shop, "H-1", "接口商品", "Electronics > Audio", "cat-a", "[]", "描述", "{}");

    var filter = filter(shop, List.of("Electronics > Audio"), null, null, null, null, "EXCLUDE", "TITLE");
    var targets = List.of(new MigrationTarget(target, SITE_US));

    // 预览
    http.perform(post("/channel/listing-migrations/preview")
            .header("Authorization", bearerA)
            .contentType("application/json")
            .content(JSON.writeValueAsString(new MigrationPreviewRequest(filter, targets))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.explanation.baseCount").value(1));

    // 执行
    String body =
        http.perform(post("/channel/listing-migrations")
                .header("Authorization", bearerA)
                .contentType("application/json")
                .content(JSON.writeValueAsString(executeRequest(
                    filter, targets, new MigrationDuplicatePolicy("NONE", "PROCEED", 50, null), "http-1"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.migratedCount").value(1))
            .andReturn().getResponse().getContentAsString();
    String batchId = JSON.readTree(body).get("batchId").asText();

    // 列表 / 详情 / 明细
    http.perform(get("/channel/listing-migrations").header("Authorization", bearerA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1));
    http.perform(get("/channel/listing-migrations/" + batchId).header("Authorization", bearerA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.batchId").value(batchId));
    http.perform(get("/channel/listing-migrations/" + batchId + "/items").header("Authorization", bearerA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].diffs.length()").value(org.hamcrest.Matchers.greaterThan(0)));

    // 风险 / 已搬迁
    http.perform(get("/channel/listing-migrations/duplicate-risks").header("Authorization", bearerA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1));
    http.perform(get("/channel/listing-migrations/migrated").header("Authorization", bearerA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1));

    // 能力与筛选选项
    http.perform(get("/channel/listing-migrations/capabilities").header("Authorization", bearerA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.titleDifferentiationEnforced").value(true));
    http.perform(get("/channel/listing-migrations/filter-options")
            .header("Authorization", bearerA)
            .param("sourceShopId", shop.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.categories.length()").value(1));

    // 无会话 ⇒ 401
    http.perform(get("/channel/listing-migrations/capabilities")).andExpect(status().isUnauthorized());
    // 畸形 id ⇒ 404
    http.perform(get("/channel/listing-migrations/not-a-uuid").header("Authorization", bearerA))
        .andExpect(status().isNotFound());
    // 目标店铺不存在 ⇒ 422
    http.perform(post("/channel/listing-migrations")
            .header("Authorization", bearerA)
            .contentType("application/json")
            .content(JSON.writeValueAsString(executeRequest(
                filter, List.of(new MigrationTarget(UUID.randomUUID(), SITE_US)),
                new MigrationDuplicatePolicy("NONE", "PROCEED", 50, null), "http-bad"))))
        .andExpect(status().isUnprocessableEntity());
  }

  @Test
  @DisplayName("4.4.2.2 越界目标逐个点名：同店铺 / 跨平台 / 未启用站点")
  void invalidTargetsAreRejected() {
    UUID shop = seedShop(a, "BAD-1", AMAZON);
    seedSource(actorA, shop, "B-1", "越界商品", "Electronics > Audio", "cat-a", "[]", "描述", "{}");
    UUID other = seedShop(a, "BAD-1-OTHER", SHOPIFY);

    // 搬回源店铺
    assertThatThrownBy(
            () -> as(actorA, "channel.operation.create",
                () -> service.execute(
                    executeRequest(
                        filter(shop, null, null, null, null, null, "EXCLUDE", "TITLE"),
                        List.of(new MigrationTarget(shop, SITE_US)),
                        null, "bad-same-shop"),
                    user)))
        .hasMessageContaining("源店铺不同");
    // 跨平台
    assertThatThrownBy(
            () -> as(actorA, "channel.operation.create",
                () -> service.execute(
                    executeRequest(
                        filter(shop, null, null, null, null, null, "EXCLUDE", "TITLE"),
                        List.of(new MigrationTarget(other, SITE_US)),
                        null, "bad-cross-platform"),
                    user)))
        .hasMessageContaining("跨平台");
    // 未启用站点
    UUID noSite = seedShop(a, "BAD-1-NOSITE", AMAZON);
    assertThatThrownBy(
            () -> as(actorA, "channel.operation.create",
                () -> service.execute(
                    executeRequest(
                        filter(shop, null, null, null, null, null, "EXCLUDE", "TITLE"),
                        List.of(new MigrationTarget(noSite, SITE_UK)),
                        null, "bad-site"),
                    user)))
        .hasMessageContaining("站点");
  }

  @Test
  @DisplayName("4.4.2.2 筛选参数非法时 422（区间倒置 / 未知策略 / 未知排序维度）")
  void invalidFiltersAreRejected() {
    UUID shop = seedShop(a, "INV-1", AMAZON);
    UUID target = seedShop(a, "INV-1-T", AMAZON);
    var targets = List.of(new MigrationTarget(target, SITE_US));

    assertThatThrownBy(() -> preview(actorA, 
            filter(shop, null, new BigDecimal("100"), new BigDecimal("10"), null, null, "EXCLUDE", "TITLE"),
            targets))
        .hasMessageContaining("下界");
    assertThatThrownBy(() -> preview(actorA, 
            filter(shop, null, null, null, null, null, "NOPE", "TITLE"), targets))
        .hasMessageContaining("未知值策略");
    assertThatThrownBy(() -> preview(actorA, 
            filter(shop, null, null, null, null, null, "EXCLUDE", "NOPE"), targets))
        .hasMessageContaining("排序维度");
  }
}
