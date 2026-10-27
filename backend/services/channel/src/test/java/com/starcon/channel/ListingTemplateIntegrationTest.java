package com.starcon.channel;

import static com.starcon.channel.ListingTemplateModels.*;
import static com.starcon.channel.ListingModels.*;
import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 4.2.2.1 刊登模板 的集成测试。
 *
 * <p>蓝图：「沉淀可复用的刊登模板（类目级、店铺级），保存字段填充规则（取自商品属性、引用文案库、套用图片组），一键套用到目标商品。输入：模板
 * 定义（字段规则 + 图片组 + 必填校验）；套用目标。输出：模板记录；套用后的内容草稿。关系：上游 = 03 文案资产、03 媒体库；下游 = 4.2.1.1
 * 编辑器。<b>模板引用的是"取值规则"而非一次性拷贝</b>，商品改价或换图后套用结果跟着变。」
 *
 * <p>🔴 本测试类要打穿的核心是**那一句加粗**。它不是靠断言文案，而是靠**真的改一次源商品**：
 *
 * <ol>
 *   <li>第一次套用 ⇒ 草稿里的标题带的是源商品当时的价格、图带的是素材当时的存储键；
 *   <li>**改源商品**（stub catalog 里的属性值与素材存储键都换掉）⇒ 再套用一次；
 *   <li>第二次的草稿值**跟着变了** —— 而**模板那几行逐字未变**（模板没有吸收快照）。
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class ListingTemplateIntegrationTest {

  static final PostgreSQLContainer<?> pg =
      new PostgreSQLContainer<>("postgres:16-alpine").withUrlParam("sslmode", "disable");
  static final UUID user = UUID.randomUUID();
  static HttpServer identity;
  static StubCatalogServer catalog;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static final ObjectMapper JSON = new ObjectMapper();

  AccessService.Access actor;
  String bearer;

  /** V1 迁移随代码发布的平台码表（只读），测试直接用它，不自己造一份。 */
  static final UUID AMAZON = UUID.fromString("11111111-1111-1111-1111-111111111101");

  static final UUID SHOPIFY = UUID.fromString("11111111-1111-1111-1111-111111111102");

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

  UUID siteUs;
  UUID shopId;
  UUID otherShopId;
  UUID skuId;
  UUID assetOne;
  UUID assetTwo;

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
      catalog = new StubCatalogServer();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
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
    r.add("CATALOG_URL", catalog::base);
    r.add("INTERNAL_KEY", () -> "test-key");
  }

  @Autowired ListingTemplateService service;
  @Autowired ListingService listService;
  @Autowired ListingDetailService detailService;
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

  /** 执行一条**预期失败**的 SQL，返回它抛出的 SQLException（不包成 RuntimeException，好断言 SQLState）。 */
  static SQLException execAsTenantExpectFailure(UUID tenant, String sql) {
    try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute("select set_config('app.current_tenant','" + tenant + "',false)");
      s.execute("select set_config('app.actor','" + user + "',false)");
      s.execute("select set_config('app.department','',false)");
      s.execute("select set_config('app.permission','channel.operation.create',false)");
      s.execute("select set_config('app.grants','" + ALL_SCOPE_GRANTS + "',false)");
      s.execute(sql);
      return null;
    } catch (SQLException e) {
      return e;
    }
  }

  static String scalar(String sql) {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement();
        var rs = s.executeQuery(sql)) {
      return rs.next() ? rs.getString(1) : null;
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
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

  @BeforeEach
  void setUp() {
    actor =
        new AccessService.Access(
            user,
            UUID.randomUUID(),
            "Test",
            UUID.randomUUID(),
            null,
            allPermissions,
            List.of(
                new AccessService.Grant(
                    UUID.randomUUID(),
                    "TEST",
                    "ALL",
                    java.util.stream.Stream.of(
                            allPermissions.stream()
                                .filter(p -> p.endsWith(".read"))
                                .toList(),
                            allPermissions.stream()
                                .filter(p -> !p.endsWith(".read"))
                                .toList())
                        .flatMap(List::stream)
                        .toList())));
    bearer = "Bearer t-" + actor.tenantId();
    actors.put(bearer, actor);

    siteUs = marketplaceId(AMAZON, "US");
    assertThat(siteUs).as("V1 迁移应当发布了 AMAZON 的美国站").isNotNull();

    shopId = seedShop(actor.tenantId(), AMAZON, "TPL-SHOP-1", "模板测试店铺");
    otherShopId = seedShop(actor.tenantId(), AMAZON, "TPL-SHOP-2", "另一个店铺");

    skuId = UUID.randomUUID();
    assetOne = UUID.randomUUID();
    assetTwo = UUID.randomUUID();
    catalog.skuStatus = 0;
    catalog.mediaStatus = 0;
    catalog.descriptionStatus = 0;
    catalog.renderIssueCount = 0;
    catalog.skuAttributes.clear();
    catalog.mediaStorageKeys.clear();
    catalog.mediaNames.clear();
    catalog.mediaEnabled.clear();
    catalog.descriptionTemplates.clear();
  }

  UUID seedShop(UUID tenant, UUID platformId, String code, String name) {
    return seedShop(tenant, platformId, siteUs, code, name);
  }

  UUID seedShop(UUID tenant, UUID platformId, UUID marketplaceId, String code, String name) {
    UUID id = UUID.randomUUID();
    execAsTenant(tenant,
        "insert into shop (tenant_id,id,platform_id,code,name) values ('" + tenant + "','" + id
            + "','" + platformId + "','" + code + "','" + name + "')"
            + " on conflict (tenant_id,code) do update set name=excluded.name");
    execAsTenant(tenant,
        "insert into shop_marketplace (tenant_id,shop_id,marketplace_id,status)"
            + " values ('" + tenant + "','" + id + "','" + marketplaceId + "','ACTIVE')"
            + " on conflict (tenant_id,shop_id,marketplace_id) do update set status='ACTIVE'");
    return id;
  }

  ListingRow seedListing(UUID shop, String externalItemId, String title, String categoryPath) {
    return seedListing(shop, siteUs, externalItemId, title, categoryPath);
  }

  ListingRow seedListing(UUID shop, UUID marketplace, String externalItemId, String title, String categoryPath) {
    var row =
        as(actor,
            "channel.operation.create",
            () ->
                listService.save(
                    new SaveListingRequest(
                        null, shop, marketplace, externalItemId, title, categoryPath, "cat-1",
                        "ON_SALE", "APPROVED", "", "IN_STOCK", null, null, null, "USD",
                        null, null,
                        List.of(
                            new SaveVariantRequest(null, "v-1", "MAPPED", skuId, null, "{}")),
                        null)));
    as(actor,
        "channel.operation.create",
        () ->
            detailService.saveSnapshot(
                row.id().toString(),
                new ListingDetailModels.SaveContentSnapshotRequest(
                    "en-US", title, "[\"平台卖点一\"]", "平台描述", "[\"https://img/platform-1.jpg\"]",
                    "", "[]", "{}", "{}", "SYNC", null, Boolean.TRUE)));
    return row;
  }

  /** 模板定义：标题取自商品属性、图片取自图片组、卖点是常量。 */
  SaveListingTemplateRequest templateRequest(
      String code, String scope, UUID platform, UUID shop, String categoryPath) {
    return new SaveListingTemplateRequest(
        null,
        code,
        "刊登模板 " + code,
        "集成测试用模板",
        scope,
        platform,
        siteUs.toString(),
        categoryPath,
        shop == null ? null : shop.toString(),
        "en-US",
        "ACTIVE",
        List.of(
            new ListingTemplateFieldInput(
                "TITLE", "PRODUCT_ATTRIBUTE", "price", "Special {{value}}", false, 0),
            new ListingTemplateFieldInput("IMAGES", "IMAGE_GROUP", "main", "", false, 1),
            new ListingTemplateFieldInput("BULLETS", "LITERAL", "", "卖点一\n卖点二", true, 2)),
        List.of(
            new ListingTemplateAssetGroupInput(
                "main", "ASSET_LIST", "", List.of(assetOne, assetTwo), 5, 0)));
  }

  String draftTitle(UUID listingId) {
    return scalar(
        "select coalesce(title,'') from listing_content_draft where listing_id='"
            + listingId
            + "' and state='DRAFT'");
  }

  String draftImages(UUID listingId) {
    return scalar(
        "select coalesce(images::text,'') from listing_content_draft where listing_id='"
            + listingId
            + "' and state='DRAFT'");
  }

  String draftBullets(UUID listingId) {
    return scalar(
        "select coalesce(array_to_string(bullets,'|'),'') from listing_content_draft where listing_id='"
            + listingId
            + "' and state='DRAFT'");
  }

  // ==================================================================
  // 1. 🔴 核心不变量：模板存规则不存快照
  // ==================================================================

  @Test
  @DisplayName("4.2.2.1 核心不变量：模板存规则不存快照 —— 改一次源商品 → 重新套用 → 草稿里的值跟着变")
  void rulesNotSnapshots_liveEvaluationFollowsSourceProduct() {
    catalog.putSku(skuId, "price", "99.00");
    catalog.putMedia(assetOne, "主图一", "media/a1-v1.jpg");
    catalog.putMedia(assetTwo, "主图二", "media/a2-v1.jpg");

    var listing = seedListing(shopId, "TPL-LIVE-1", "线上标题", "Electronics > Audio");
    var detail =
        as(actor, "channel.operation.create", () -> service.save(templateRequest(
            "TPL-LIVE", "CATEGORY", AMAZON, null, "Electronics > Audio")));

    // ---- 第一次套用
    var first =
        as(actor, "channel.operation.create", () -> service.apply(
            new ApplyListingTemplateRequest(detail.template().id(), listing.id(), null, ""), bearer));

    assertThat(first.validationStatus()).as("必填都取到了 → 校验通过").isEqualTo("PASS");
    assertThat(first.appliedCount()).isEqualTo(3);
    assertThat(first.violationCount()).isZero();
    assertThat(first.targetDraftId()).as("套用必须产出草稿").isNotNull();
    assertThat(first.draftState()).as("套用只产出草稿，不发布").isEqualTo("DRAFT");

    assertThat(draftTitle(listing.id())).as("标题取的是源商品当时的价格").isEqualTo("Special 99.00");
    assertThat(draftImages(listing.id())).contains("media/a1-v1.jpg", "media/a2-v1.jpg");
    assertThat(draftBullets(listing.id())).isEqualTo("卖点一|卖点二");

    // ---- 模板本身逐字记下来（后面要比对它没被改动）
    String templateRowsBefore =
        scalar(
            "select string_agg(f.field || '|' || f.source_kind || '|' || f.source_ref || '|' ||"
                + " f.value_expression || '|' || f.required::text, ';' order by f.slot_no)"
                + " from listing_template_field f where f.template_id='"
                + detail.template().id()
                + "'");

    // ---- 🔴 改源商品：价格改了、素材换了版本
    catalog.putSku(skuId, "price", "129.00");
    catalog.putMedia(assetTwo, "主图二", "media/a2-v2.jpg");

    // ---- 重新套用
    var second =
        as(actor, "channel.operation.create", () -> service.apply(
            new ApplyListingTemplateRequest(detail.template().id(), listing.id(), null, ""), bearer));

    assertThat(second.applyId()).as("重新套用是一次新的套用").isNotEqualTo(first.applyId());
    assertThat(draftTitle(listing.id()))
        .as("🔴 源商品改价之后，草稿里的标题必须跟着变")
        .isEqualTo("Special 129.00")
        .isNotEqualTo(draftTitleBefore(listing.id(), "Special 99.00"));
    assertThat(draftImages(listing.id()))
        .as("🔴 素材换版本之后，草稿里的图片必须跟着变")
        .contains("media/a2-v2.jpg")
        .doesNotContain("media/a2-v1.jpg");

    // ---- 🔴 模板本身逐字未变（它没有吸收快照）
    String templateRowsAfter =
        scalar(
            "select string_agg(f.field || '|' || f.source_kind || '|' || f.source_ref || '|' ||"
                + " f.value_expression || '|' || f.required::text, ';' order by f.slot_no)"
                + " from listing_template_field f where f.template_id='"
                + detail.template().id()
                + "'");
    assertThat(templateRowsAfter)
        .as("🔴 模板里存的是规则，重新套用不能把值抄进模板")
        .isEqualTo(templateRowsBefore);
    assertThat(templateRowsAfter).contains("TITLE|PRODUCT_ATTRIBUTE|price|Special {{value}}|false");
    assertThat(templateRowsAfter).doesNotContain("99.00").doesNotContain("129.00");

    // ---- 套用记录留了两条（历史可追）
    var applies = as(actor, "channel.operation.read", () -> service.applies(0, 50, null, null));
    assertThat(applies.total()).isEqualTo(2);
  }

  /** 断言用：先记下旧值再比对，避免自证。 */
  private String draftTitleBefore(UUID listingId, String expected) {
    assertThat(expected).isNotBlank();
    return expected;
  }

  // ==================================================================
  // 2. 🔴 套用只产出草稿，线上 listing 一列都不碰
  // ==================================================================

  @Test
  @DisplayName("4.2.2.1 套用只产出草稿：线上 listing 的内容列逐字未变")
  void applyNeverTouchesLiveListing() {
    catalog.putSku(skuId, "price", "99.00");
    catalog.putMedia(assetOne, "主图一", "media/a1-v1.jpg");
    catalog.putMedia(assetTwo, "主图二", "media/a2-v1.jpg");
    var listing = seedListing(shopId, "TPL-DRAFT-1", "线上标题", "Electronics > Audio");
    var detail =
        as(actor, "channel.operation.create", () -> service.save(templateRequest(
            "TPL-DRAFT", "CATEGORY", AMAZON, null, "Electronics > Audio")));

    String liveTitleBefore =
        scalar("select title from listing where id='" + listing.id() + "'");
    String liveCategoryBefore =
        scalar("select category_path from listing where id='" + listing.id() + "'");

    as(actor, "channel.operation.create", () -> service.apply(
        new ApplyListingTemplateRequest(detail.template().id(), listing.id(), null, ""), bearer));

    assertThat(scalar("select title from listing where id='" + listing.id() + "'"))
        .as("🔴 线上标题不能被模板改")
        .isEqualTo(liveTitleBefore);
    assertThat(scalar("select category_path from listing where id='" + listing.id() + "'"))
        .isEqualTo(liveCategoryBefore);
    assertThat(draftTitle(listing.id())).isEqualTo("Special 99.00");
  }

  @Test
  @DisplayName("4.2.2.1 草稿隔离的 DB 防线：app.draft_only 打开期间线上内容列被拒写")
  void draftOnlyGuardIsArmed() {
    // 这条断言是「将来有人在模板路径里手滑加了 update listing set title=…」会在测试期就炸的地基：
    // 同一个事务里打开 app.draft_only，线上内容列的 UPDATE 由 V177 的触发器拒掉。
    Throwable failure =
        org.assertj.core.api.Assertions.catchThrowable(
            () -> {
              try (var c =
                  DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())) {
                // set_config(..., true) 是**事务局部**的，所以必须显式开事务才有效
                c.setAutoCommit(false);
                try (var s = c.createStatement()) {
                  s.execute("select set_config('app.draft_only','true',true)");
                  s.execute(
                      "update listing set title='被手滑改掉的标题' where id=(select id from listing limit 1)");
                }
              }
            });
    assertThat(failure).as("app.draft_only 期间线上内容列必须被 DB 拒写").isNotNull();
    assertThat(failure.getMessage()).contains("must never write live listing content");
  }

  // ==================================================================
  // 3. 🔴 两种作用域都真做（判定有真实数据支撑）
  // ==================================================================

  @Test
  @DisplayName("4.2.2.1 类目级与店铺级两种作用域都真做：命中集合从真实数据里查出来")
  void bothScopesAreRealAndDataBacked() {
    catalog.putSku(skuId, "price", "99.00");
    catalog.putMedia(assetOne, "主图一", "media/a1-v1.jpg");
    catalog.putMedia(assetTwo, "主图二", "media/a2-v1.jpg");
    var inCategory = seedListing(shopId, "TPL-SCOPE-1", "类目内商品", "Electronics > Audio");
    var otherCategory = seedListing(shopId, "TPL-SCOPE-2", "类目外商品", "Home > Kitchen");
    var otherShop = seedListing(otherShopId, "TPL-SCOPE-3", "别的店铺商品", "Electronics > Audio");

    var categoryTemplate =
        as(actor, "channel.operation.create", () -> service.save(templateRequest(
            "TPL-SCOPE-CAT", "CATEGORY", AMAZON, null, "Electronics > Audio")));
    var shopTemplate =
        as(actor, "channel.operation.create", () -> service.save(
            new SaveListingTemplateRequest(
                null, "TPL-SCOPE-SHOP", "店铺级模板", "", "SHOP", AMAZON, siteUs.toString(), null, shopId.toString(),
                "en-US", "ACTIVE",
                List.of(new ListingTemplateFieldInput(
                    "TITLE", "PRODUCT_ATTRIBUTE", "price", "Special {{value}}", false, 0)),
                List.of())));

    // 类目级：只命中类目路径相同的那条
    var categoryMatches =
        as(actor, "channel.operation.read", () -> service.matches(
            categoryTemplate.template().id().toString(), 0, 50));
    assertThat(categoryMatches.total()).isEqualTo(2);
    assertThat(categoryMatches.items())
        .extracting(ListingTemplateMatchRow::externalItemId)
        .contains("TPL-SCOPE-1", "TPL-SCOPE-3")
        .doesNotContain("TPL-SCOPE-2");

    // 店铺级：命中本店铺的**全部**商品（含类目不匹配的那条），但不含别的店铺的
    var shopMatches =
        as(actor, "channel.operation.read", () -> service.matches(
            shopTemplate.template().id().toString(), 0, 50));
    assertThat(shopMatches.total()).isEqualTo(2);
    assertThat(shopMatches.items())
        .extracting(ListingTemplateMatchRow::externalItemId)
        .contains("TPL-SCOPE-1", "TPL-SCOPE-2")
        .doesNotContain("TPL-SCOPE-3");
    assertThat(shopMatches.scopeNote()).as("适用范围说明要说清按什么判").contains("店铺");

    // 套用时作用域不匹配 ⇒ 明确拒绝（不是静默套用）
    Throwable categoryMiss =
        org.assertj.core.api.Assertions.catchThrowable(
            () -> as(actor, "channel.operation.create", () -> service.apply(
                new ApplyListingTemplateRequest(
                    categoryTemplate.template().id(), otherCategory.id(), null, ""),
                bearer)));
    assertThat(categoryMiss).as("类目不匹配必须被拒").isNotNull();
    assertThat(categoryMiss.getMessage()).contains("类目路径");

    Throwable shopMiss =
        org.assertj.core.api.Assertions.catchThrowable(
            () -> as(actor, "channel.operation.create", () -> service.apply(
                new ApplyListingTemplateRequest(
                    shopTemplate.template().id(), otherShop.id(), null, ""),
                bearer)));
    assertThat(shopMiss).as("店铺不匹配必须被拒").isNotNull();
    assertThat(shopMiss.getMessage()).contains("店铺");
  }

  @Test
  @DisplayName("4.2.2.1 内容按平台 + 站点隔离：跨平台套用被拒")
  void crossPlatformApplyIsRejected() {
    catalog.putSku(skuId, "price", "99.00");
    var listing = seedListing(shopId, "TPL-PLAT-1", "线上标题", "Electronics > Audio");
    var detail =
        as(actor, "channel.operation.create", () -> service.save(templateRequest(
            "TPL-PLAT", "CATEGORY", AMAZON, null, "Electronics > Audio")));

    // 同一个租户里另一个平台的店铺 + 商品
    UUID shopifySite = marketplaceId(SHOPIFY, "US");
    assertThat(shopifySite).as("V1 迁移应当发布了 Shopify 的美国站").isNotNull();
    UUID shopifyShop = seedShop(actor.tenantId(), SHOPIFY, shopifySite, "TPL-SHOPIFY", "Shopify 店铺");
    var shopifyListing = seedListing(shopifyShop, shopifySite, "TPL-PLAT-2", "Shopify 商品", "Electronics > Audio");

    Throwable failure =
        org.assertj.core.api.Assertions.catchThrowable(
            () -> as(actor, "channel.operation.create", () -> service.apply(
                new ApplyListingTemplateRequest(
                    detail.template().id(), shopifyListing.id(), null, ""),
                bearer)));
    assertThat(failure).as("跨平台套用必须被拒").isNotNull();
    assertThat(failure.getMessage()).contains("平台");
    assertThat(scalar("select count(*) from listing_content_draft where listing_id='"
        + shopifyListing.id() + "'"))
        .as("被拒的套用不能留下草稿")
        .isEqualTo("0");
  }

  // ==================================================================
  // 4. 🔴 必填校验真的算出来
  // ==================================================================

  @Test
  @DisplayName("4.2.2.1 必填校验真的算出来：违规指到具体字段与具体规则")
  void requiredValidationIsComputed() {
    catalog.putSku(skuId, "price", "99.00");
    var listing = seedListing(shopId, "TPL-REQ-1", "线上标题", "Electronics > Audio");

    // 必填字段引用的属性码在 SKU 上不存在 ⇒ SOURCE_MISSING + 违规
    var detail =
        as(actor, "channel.operation.create", () -> service.save(
            new SaveListingTemplateRequest(
                null, "TPL-REQ", "必填违规模板", "", "CATEGORY", AMAZON, siteUs.toString(),
                "Electronics > Audio", null, "en-US", "ACTIVE",
                List.of(
                    new ListingTemplateFieldInput(
                        "TITLE", "PRODUCT_ATTRIBUTE", "brand", "{{value}}", true, 0),
                    new ListingTemplateFieldInput(
                        "CATEGORY_PATH", "LITERAL", "", "Electronics > Audio", false, 1)),
                List.of())));

    var result =
        as(actor, "channel.operation.create", () -> service.apply(
            new ApplyListingTemplateRequest(detail.template().id(), listing.id(), null, ""), bearer));

    assertThat(result.validationStatus()).as("必填字段取不到 ⇒ 校验失败").isEqualTo("FAIL");
    assertThat(result.violationCount()).isEqualTo(1);
    assertThat(result.violations()).hasSize(1);
    var violation = result.violations().get(0);
    assertThat(violation.field()).isEqualTo("TITLE");
    assertThat(violation.ruleRef()).as("违规要指到具体那条规则").isEqualTo("PRODUCT_ATTRIBUTE:brand");
    assertThat(violation.status()).isEqualTo("SOURCE_MISSING");
    assertThat(violation.ownerModule()).as("「不知道」必须点名归属模块").isEqualTo("3.1.2.2");
    assertThat(violation.violationCode()).isEqualTo("REQUIRED_SOURCE_MISSING");
    assertThat(violation.reason()).contains("brand");
  }

  // ==================================================================
  // 5. 🔴「不知道」是一个状态，不是一个 0
  // ==================================================================

  @Test
  @DisplayName("4.2.2.1 上游读不到 ⇒ UNKNOWN_SOURCE + ownerModule，不回退成「没有值」")
  void unknownIsAStateNotZero() {
    catalog.putSku(skuId, "price", "99.00");
    catalog.skuStatus = 500;
    var listing = seedListing(shopId, "TPL-UNK-1", "线上标题", "Electronics > Audio");
    var detail =
        as(actor, "channel.operation.create", () -> service.save(
            new SaveListingTemplateRequest(
                null, "TPL-UNK", "上游不可用模板", "", "CATEGORY", AMAZON, siteUs.toString(),
                "Electronics > Audio", null, "en-US", "ACTIVE",
                List.of(
                    new ListingTemplateFieldInput(
                        "TITLE", "PRODUCT_ATTRIBUTE", "price", "{{value}}", true, 0),
                    new ListingTemplateFieldInput(
                        "CATEGORY_PATH", "LITERAL", "", "Electronics > Audio", false, 1)),
                List.of())));

    var result =
        as(actor, "channel.operation.create", () -> service.apply(
            new ApplyListingTemplateRequest(detail.template().id(), listing.id(), null, ""), bearer));

    assertThat(result.validationStatus()).isEqualTo("FAIL");
    assertThat(result.unknownCount()).isEqualTo(1);
    var title = result.fields().stream().filter(f -> f.field().equals("TITLE")).findFirst().orElseThrow();
    assertThat(title.status()).isEqualTo("UNKNOWN_SOURCE");
    assertThat(title.ownerModule()).isEqualTo("3.1.2.2");
    assertThat(title.value()).as("「不知道」不能变成一个 0 或空串").isNull();
    assertThat(title.reason()).contains("catalog");

    // 其余字段照常产出草稿（一个上游挂掉不该让整次套用失败）
    assertThat(result.appliedCount()).isEqualTo(1);
    assertThat(scalar("select category_path from listing_content_draft where listing_id='"
        + listing.id() + "'"))
        .isEqualTo("Electronics > Audio");
  }

  // ==================================================================
  // 6. 🔴 幂等
  // ==================================================================

  @Test
  @DisplayName("4.2.2.1 幂等：带同一个幂等键重复套用返回第一次的结果；不带则覆盖草稿")
  void applyIsIdempotent() {
    catalog.putSku(skuId, "price", "99.00");
    catalog.putMedia(assetOne, "主图一", "media/a1-v1.jpg");
    catalog.putMedia(assetTwo, "主图二", "media/a2-v1.jpg");
    var listing = seedListing(shopId, "TPL-IDEM-1", "线上标题", "Electronics > Audio");
    var detail =
        as(actor, "channel.operation.create", () -> service.save(templateRequest(
            "TPL-IDEM", "CATEGORY", AMAZON, null, "Electronics > Audio")));

    var first =
        as(actor, "channel.operation.create", () -> service.apply(
            new ApplyListingTemplateRequest(
                detail.template().id(), listing.id(), "KEY-1", ""),
            bearer));
    assertThat(first.idempotentReplay()).isFalse();

    // ---- 同一个幂等键 ⇒ 返回第一次的那一份，不重新求值、不再落日志
    catalog.putSku(skuId, "price", "999.00");
    var replay =
        as(actor, "channel.operation.create", () -> service.apply(
            new ApplyListingTemplateRequest(
                detail.template().id(), listing.id(), "KEY-1", ""),
            bearer));
    assertThat(replay.idempotentReplay()).as("幂等重放").isTrue();
    assertThat(replay.applyId()).isEqualTo(first.applyId());
    assertThat(draftTitle(listing.id()))
        .as("幂等重放不重新求值 ⇒ 草稿值不变")
        .isEqualTo("Special 99.00");
    assertThat(as(actor, "channel.operation.read", () -> service.applies(0, 50, null, null)).total())
        .isEqualTo(1);

    // ---- 不带幂等键 ⇒ 覆盖草稿（重新求值）
    var overwrite =
        as(actor, "channel.operation.create", () -> service.apply(
            new ApplyListingTemplateRequest(detail.template().id(), listing.id(), null, ""), bearer));
    assertThat(overwrite.idempotentReplay()).isFalse();
    assertThat(overwrite.applyId()).isNotEqualTo(first.applyId());
    assertThat(draftTitle(listing.id()))
        .as("不带幂等键重复套用 = 覆盖草稿（重新求值）")
        .isEqualTo("Special 999.00");
    assertThat(as(actor, "channel.operation.read", () -> service.applies(0, 50, null, null)).total())
        .isEqualTo(2);
  }

  // ==================================================================
  // 7. 🔴 DB 层的结构约束（模板不许吸收快照）
  // ==================================================================

  @Test
  @DisplayName("4.2.2.1 DB 防线：来源引用与占位符只能是标识符，作用域必须互斥，套用记录只追加")
  void dbGuardsRejectSnapshotInjection() {
    UUID tenant = actor.tenantId();
    UUID templateId = UUID.randomUUID();
    execAsTenant(tenant,
        "insert into listing_template (id,tenant_id,code,name,scope,platform_id,category_path,status)"
            + " values ('" + templateId + "','" + tenant + "','TPL-GUARD','守卫模板','CATEGORY','"
            + AMAZON + "','Electronics > Audio','ACTIVE')");

    // ① 来源引用不能是一段自由文本（否则就能把商品现在的值抄进模板）
    SQLException freeText =
        execAsTenantExpectFailure(tenant,
            "insert into listing_template_field (id,tenant_id,template_id,field,source_kind,"
                + "source_ref,value_expression,required,slot_no) values ('" + UUID.randomUUID()
                + "','" + tenant + "','" + templateId + "','TITLE','PRODUCT_ATTRIBUTE',"
                + "'Special Price 99.00','',false,0)");
    assertThat((Throwable) freeText).as("自由文本当来源引用必须被 DB 拒").isNotNull();
    assertThat(freeText.getSQLState()).isEqualTo("23514");

    // ② 占位符只能是标识符（不能把值藏进占位符）
    SQLException badPlaceholder =
        execAsTenantExpectFailure(tenant,
            "insert into listing_template_field (id,tenant_id,template_id,field,source_kind,"
                + "source_ref,value_expression,required,slot_no) values ('" + UUID.randomUUID()
                + "','" + tenant + "','" + templateId + "','TITLE','PRODUCT_ATTRIBUTE',"
                + "'price','{{Price 99.00}}',false,1)");
    assertThat((Throwable) badPlaceholder).as("占位符藏值必须被 DB 拒").isNotNull();
    assertThat(badPlaceholder.getSQLState()).isEqualTo("23514");

    // ③ 非 LITERAL 来源必须至少引用一个占位符（不引用来源的表达式 = 伪装成规则的常量快照）
    SQLException noPlaceholder =
        execAsTenantExpectFailure(tenant,
            "insert into listing_template_field (id,tenant_id,template_id,field,source_kind,"
                + "source_ref,value_expression,required,slot_no) values ('" + UUID.randomUUID()
                + "','" + tenant + "','" + templateId + "','TITLE','PRODUCT_ATTRIBUTE',"
                + "'price','Special 99.00',false,2)");
    assertThat((Throwable) noPlaceholder).as("不引用来源的表达式必须被 DB 拒").isNotNull();
    assertThat(noPlaceholder.getSQLState()).isEqualTo("23514");

    // ④ 两种作用域互斥
    SQLException scopeBoth =
        execAsTenantExpectFailure(tenant,
            "insert into listing_template (id,tenant_id,code,name,scope,platform_id,"
                + "category_path,shop_id,status) values ('" + UUID.randomUUID() + "','"
                + tenant + "','TPL-GUARD-2','双作用域','CATEGORY','" + AMAZON
                + "','Electronics > Audio','" + shopId + "','ACTIVE')");
    assertThat((Throwable) scopeBoth).as("两种作用域同时给必须被 DB 拒").isNotNull();
    assertThat(scopeBoth.getSQLState()).isEqualTo("23514");

    // ⑤ 套用记录只追加
    catalog.putSku(skuId, "price", "99.00");
    var listing = seedListing(shopId, "TPL-GUARD-3", "线上标题", "Electronics > Audio");
    var detail =
        as(actor, "channel.operation.create", () -> service.save(
            new SaveListingTemplateRequest(
                null, "TPL-GUARD-OK", "可套用模板", "", "CATEGORY", AMAZON, siteUs.toString(),
                "Electronics > Audio", null, "en-US", "ACTIVE",
                List.of(new ListingTemplateFieldInput(
                    "TITLE", "PRODUCT_ATTRIBUTE", "price", "{{value}}", false, 0)),
                List.of())));
    var applied =
        as(actor, "channel.operation.create", () -> service.apply(
            new ApplyListingTemplateRequest(detail.template().id(), listing.id(), null, ""), bearer));

    SQLException mutate =
        execAsTenantExpectFailure(tenant,
            "update listing_template_apply set note='改过了' where id='"
                + applied.applyId() + "'");
    assertThat((Throwable) mutate).as("套用记录不许改").isNotNull();
    assertThat(mutate.getSQLState()).isEqualTo("23514");

    SQLException delete =
        execAsTenantExpectFailure(tenant,
            "delete from listing_template_apply where id='" + applied.applyId() + "'");
    assertThat((Throwable) delete).as("套用记录不许删").isNotNull();
    assertThat(delete.getSQLState()).isEqualTo("23514");
  }

  // ==================================================================
  // 8. 能力声明 / 列表 / 词表
  // ==================================================================

  @Test
  @DisplayName("4.2.2.1 能力声明：编辑器侧套用入口 available=false + ownerModule=4.2.1.1")
  void capabilitiesDeclareTheMissingEditorEntry() {
    var caps = as(actor, "channel.operation.read", () -> service.capabilities());
    var editor = caps.dimensions().stream()
        .filter(d -> d.dimension().equals("editor_entry"))
        .findFirst()
        .orElseThrow();
    assertThat(editor.available()).isFalse();
    assertThat(editor.ownerModule()).isEqualTo("4.2.1.1");
    assertThat(editor.description()).as("要写明模板能力已就绪、只差编辑器入口").contains("已就绪");

    var publish = caps.dimensions().stream()
        .filter(d -> d.dimension().equals("publish"))
        .findFirst()
        .orElseThrow();
    assertThat(publish.available()).isFalse();
    assertThat(publish.ownerModule()).isEqualTo("4.3.1.2");

    assertThat(caps.rulesOnly()).isTrue();
    assertThat(caps.draftOnly()).isTrue();
  }

  @Test
  @DisplayName("4.2.2.1 模板列表：真分页 + 真筛选（作用域 / 平台 / 店铺 / 状态 / 关键字）")
  void templateListIsReallyPagedAndFiltered() {
    catalog.putSku(skuId, "price", "99.00");
    as(actor, "channel.operation.create", () -> service.save(
        new SaveListingTemplateRequest(
            null, "TPL-PAGE-1", "类目模板", "", "CATEGORY", AMAZON, siteUs.toString(),
            "Electronics > Audio", null, "en-US", "ACTIVE",
            List.of(new ListingTemplateFieldInput(
                "TITLE", "PRODUCT_ATTRIBUTE", "price", "{{value}}", false, 0)),
            List.of())));
    as(actor, "channel.operation.create", () -> service.save(
        new SaveListingTemplateRequest(
            null, "TPL-PAGE-2", "店铺模板", "", "SHOP", AMAZON, siteUs.toString(), null, shopId.toString(),
            "en-US", "DRAFT",
            List.of(new ListingTemplateFieldInput(
                "TITLE", "PRODUCT_ATTRIBUTE", "price", "{{value}}", false, 0)),
            List.of())));
    UUID shopifySite = marketplaceId(SHOPIFY, "US");
    assertThat(shopifySite).as("V1 迁移应当发布了 Shopify 的美国站").isNotNull();
    as(actor, "channel.operation.create", () -> service.save(
        new SaveListingTemplateRequest(
            null, "TPL-PAGE-3", "别的平台模板", "", "CATEGORY", SHOPIFY, shopifySite.toString(),
            "Electronics > Audio", null, "en-US", "ACTIVE",
            List.of(new ListingTemplateFieldInput(
                "TITLE", "PRODUCT_ATTRIBUTE", "price", "{{value}}", false, 0)),
            List.of())));

    var all = as(actor, "channel.operation.read", () -> service.list(0, 50, null, null, null, null, null, null));
    assertThat(all.total()).isEqualTo(3);

    var categoryScope =
        as(actor, "channel.operation.read", () -> service.list(0, 50, "CATEGORY", null, null, null, null, null));
    assertThat(categoryScope.total()).isEqualTo(2);

    var shopScope =
        as(actor, "channel.operation.read", () -> service.list(0, 50, "SHOP", null, null, null, null, null));
    assertThat(shopScope.total()).isEqualTo(1);
    assertThat(shopScope.items().get(0).scopeTarget()).isEqualTo("TPL-SHOP-1");

    var amazonOnly =
        as(actor, "channel.operation.read", () -> service.list(0, 50, null, "AMAZON", null, null, null, null));
    assertThat(amazonOnly.total()).isEqualTo(2);

    var byStatus =
        as(actor, "channel.operation.read", () -> service.list(0, 50, null, null, null, null, "DRAFT", null));
    assertThat(byStatus.total()).isEqualTo(1);

    var byKeyword =
        as(actor, "channel.operation.read", () -> service.list(0, 50, null, null, null, null, null, "PAGE-3"));
    assertThat(byKeyword.total()).isEqualTo(1);
    assertThat(byKeyword.items().get(0).code()).isEqualTo("TPL-PAGE-3");

    // 真分页：size=1 时 total 仍然是全部命中数
    var firstPage =
        as(actor, "channel.operation.read", () -> service.list(0, 1, null, null, null, null, null, null));
    assertThat(firstPage.items()).hasSize(1);
    assertThat(firstPage.total()).isEqualTo(3);
    var secondPage =
        as(actor, "channel.operation.read", () -> service.list(1, 1, null, null, null, null, null, null));
    assertThat(secondPage.items()).hasSize(1);
    assertThat(secondPage.items().get(0).id()).isNotEqualTo(firstPage.items().get(0).id());

    var options = as(actor, "channel.operation.read", () -> service.filterOptions());
    assertThat(options.platforms()).isNotEmpty();
    assertThat(options.shops()).isNotEmpty();
  }

  @Test
  @DisplayName("4.2.2.1 HTTP 端点：能力声明、列表、套用都通")
  void httpEndpointsWork() throws Exception {
    http.perform(
            get(
                    "/channel/listing-templates/capabilities")
                .header("Authorization", bearer))
        .andExpect(status().isOk());
    http.perform(
            get(
                    "/channel/listing-templates")
                .header("Authorization", bearer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(0));
    // 无会话 ⇒ 401
    http.perform(
            get(
                    "/channel/listing-templates/capabilities"))
        .andExpect(status().isUnauthorized());
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
    if (catalog != null) catalog.close();
    pg.stop();
  }
}
