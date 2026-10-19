package com.starcon.channel;

import static com.starcon.channel.ListingEditorModels.*;
import static com.starcon.channel.ListingModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

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

/**
 * 4.2.1.1 刊登编辑器。
 *
 * <p>本测试类钉住这几组事：
 *
 * <ol>
 *   <li><b>🔴 表单字段由平台要求驱动</b>：Amazon / TikTok Shop / Shopify 三个平台的字段集**逐字不同**；
 *       站点级差异（Amazon 日本站标题 100 字 vs 美国站 200 字）也生效。
 *   <li><b>🔴 平台能力矩阵变化时表单自动变化</b>：往 {@code platform.capabilities} 里加一个能力键，
 *       表单里那一栏的 capabilityState 立刻从 UNKNOWN 变 SUPPORTED；改成 false 则该栏被移出可编辑字段。
 *   <li><b>🔴 草稿与线上物理分离</b>：保存草稿后线上 listing 的 title / bullets / images 逐字不变；
 *       而且打开 {@code app.draft_only} 的事务里直接 UPDATE 线上内容列会被 DB 拒（不是「我们没写那条 SQL」）。
 *   <li><b>🔴 内容按平台 + 站点 + 语言分别保存</b>：同商品在不同平台 / 不同语言各存一份，互不污染；
 *       同一 (店铺, 站点, 语言) 只能有一份草稿（DB 唯一约束）。
 *   <li><b>🔴 提交审核只是入队</b>：产出是 QUEUED 的入队记录，幂等；线上一个字节都不动。
 *   <li><b>校验真算</b>：必填缺失 / 长度超限 / 条数超限，逐条指到具体字段。
 *   <li><b>「不知道」是一个状态</b>：要求取不到时是 UNKNOWN + ownerModule，不是「没有约束」。
 *   <li><b>4.2.2.1 刊登模板未交付</b>：能力声明 available=false + ownerModule，不假装有。
 * </ol>
 *
 * <p><b>时刻一律用固定的 {@link java.time.OffsetDateTime} 钉死</b>，不用 {@code now()}。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ListingEditorIntegrationTest {

  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID user = UUID.randomUUID();
  static HttpServer identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static final ObjectMapper JSON = new ObjectMapper();

  UUID tenant;
  AccessService.Access actor;
  String bearer;

  // 🔴 平台与站点注册表由 V1 迁移随代码发布（只读码表），测试直接用它，不自己造一份。
  static final UUID AMAZON = UUID.fromString("11111111-1111-1111-1111-111111111101");
  static final UUID SHOPIFY = UUID.fromString("11111111-1111-1111-1111-111111111102");
  static final UUID TIKTOK = UUID.fromString("11111111-1111-1111-1111-111111111104");
  UUID siteUs, siteJp, siteShopifyUs, siteTikTokUs;

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

  static AccessService.Access actor(UUID uid, UUID tenantId, List<AccessService.Grant> grants) {
    return new AccessService.Access(
        uid, tenantId, "Test", UUID.randomUUID(), null,
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
  void freshTenant() {
    tenant = UUID.randomUUID();
    bearer = "Bearer t-" + tenant;
    actors.put(bearer, actor(user, tenant, List.of(grant("ALL", allPermissions))));
    actor = actors.get(bearer);
    siteUs = marketplaceId(AMAZON, "US");
    siteJp = marketplaceId(AMAZON, "JP");
    siteShopifyUs = marketplaceId(SHOPIFY, "US");
    siteTikTokUs = marketplaceId(TIKTOK, "US");
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
  }

  @Autowired ListingEditorService editor;
  @Autowired ListingService listings;
  @Autowired AccessService access;
  @Autowired MockMvc http;

  // ==================================================================
  // 辅助
  // ==================================================================

  <T> T as(String permission, java.util.function.Supplier<T> work) {
    return access.run(actor, permission, work);
  }

  static void exec(String sql) {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(sql);
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * 以租户身份执行一条 SQL。
   *
   * <p>🔴 必须设置 app.actor：{@code shop} 表上有 {@code stamp_shop_owner} 触发器，
   * 没有已认证演员的 INSERT 会被 DB 拒（这正是"租户与归属只来自已认证成员关系"的物理落点）。
   */
  void execAsTenant(String sql) {
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

  /** 以**会话级** draft_only 标记执行一条 SQL（autocommit 下 is_local=true 会被立刻回滚，标记就看不见了）。 */
  void execAsDraftOnly(String sql) {
    try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute("select set_config('app.current_tenant','" + tenant + "',false)");
      s.execute("select set_config('app.actor','" + user + "',false)");
      s.execute("select set_config('app.department','',false)");
      s.execute("select set_config('app.permission','channel.operation.update',false)");
      s.execute("select set_config('app.grants','" + ALL_SCOPE_GRANTS + "',false)");
      s.execute("select set_config('app.draft_only','true',false)");
      s.execute(sql);
    } catch (SQLException e) {
      throw new RuntimeException(e);
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

  UUID seedShop(String code, UUID platformId, UUID marketplaceId) {
    UUID id = UUID.randomUUID();
    execAsTenant("insert into shop (tenant_id,id,platform_id,code,name) values ('" + tenant + "','" + id
        + "','" + platformId + "','" + code + "','测试店铺 " + code + "')");
    enableSite(id, marketplaceId);
    return id;
  }

  /** 启用店铺的另一个站点（站点维度参与字段要求的就近覆盖）。 */
  void enableSite(UUID shopId, UUID marketplaceId) {
    execAsTenant("insert into shop_marketplace (tenant_id,shop_id,marketplace_id,status) values ('" + tenant
        + "','" + shopId + "','" + marketplaceId + "','ACTIVE')"
        + " on conflict (tenant_id,shop_id,marketplace_id) do update set status='ACTIVE'");
  }

  ListingRow seedListing(UUID shopId, UUID marketplaceId, String externalItemId, String title) {
    return as(
        "channel.operation.create",
        () ->
            listings.save(
                new SaveListingRequest(
                    null, shopId, marketplaceId, externalItemId, title, "Electronics > Audio", "cat-1",
                    "ON_SALE", "APPROVED", "", "IN_STOCK", null, null, null, "USD",
                    null, null,
                    List.of(new SaveVariantRequest(null, "v-1", "MAPPED", UUID.randomUUID(), null, "{}")),
                    null)));
  }

  static EditorFieldValue text(String field, String value) {
    return new EditorFieldValue(field, value, null, null, null);
  }

  static EditorFieldValue items(String field, String... values) {
    return new EditorFieldValue(field, null, List.of(values), null, null);
  }

  static EditorFieldValue pairs(String field, String... kv) {
    List<EditorKeyValue> list = new java.util.ArrayList<>();
    for (int i = 0; i + 1 < kv.length; i += 2) {
      list.add(new EditorKeyValue(kv[i], kv[i + 1]));
    }
    return new EditorFieldValue(field, null, null, null, list);
  }

  // ==================================================================
  // 一、表单字段由平台要求驱动
  // ==================================================================

  @Test
  @DisplayName("4.2.1.1 表单字段由平台要求驱动：三个平台的字段集逐字不同")
  void formFieldsComeFromPlatformRequirements() {
    UUID amazonShop = seedShop("AMZ-" + UUID.randomUUID().toString().substring(0, 6), AMAZON, siteUs);
    UUID shopifyShop =
        seedShop("SHP-" + UUID.randomUUID().toString().substring(0, 6), SHOPIFY, siteShopifyUs);
    UUID tiktokShop = seedShop("TTK-" + UUID.randomUUID().toString().substring(0, 6), TIKTOK, siteTikTokUs);

    var amazon = as("channel.operation.read", () -> editor.formSchema(amazonShop, siteUs, "", "en-US"));
    var shopify =
        as("channel.operation.read", () -> editor.formSchema(shopifyShop, siteShopifyUs, "", "en-US"));
    var tiktok =
        as("channel.operation.read", () -> editor.formSchema(tiktokShop, siteTikTokUs, "", "en-US"));

    List<String> amazonFields = amazon.fields().stream().map(EditorFieldSchema::field).toList();
    List<String> shopifyFields = shopify.fields().stream().map(EditorFieldSchema::field).toList();
    List<String> tiktokFields = tiktok.fields().stream().map(EditorFieldSchema::field).toList();

    assertThat(amazonFields)
        .as("Amazon 的字段集")
        .contains("TITLE", "BULLETS", "DESCRIPTION", "IMAGES", "VIDEO", "A_PLUS");
    assertThat(shopifyFields)
        .as("Shopify 没有要点栏（平台侧就没有这一栏）")
        .doesNotContain("BULLETS");
    assertThat(shopifyFields).contains("TITLE", "DESCRIPTION", "IMAGES");
    assertThat(tiktokFields).contains("TITLE", "BULLETS", "IMAGES");
    assertThat(tiktokFields).as("TikTok Shop 没有 A+ 模块").doesNotContain("A_PLUS");
    assertThat(shopify.unsupportedFields())
        .as("平台明确不支持的栏要单独列出来并带原因，而不是悄悄消失")
        .anyMatch(f -> "A_PLUS".equals(f.field()) && f.unavailableReason() != null);

    // 必填性与上限也来自注册表：Amazon 标题 200 字且必填
    var title = amazon.fields().stream().filter(f -> "TITLE".equals(f.field())).findFirst().orElseThrow();
    assertThat(title.requirement()).isEqualTo("REQUIRED");
    assertThat(title.maxLength()).isEqualTo(200);
    assertThat(title.constraintState()).isEqualTo("KNOWN");
  }

  @Test
  @DisplayName("4.2.1.1 站点级差异生效：Amazon 日本站标题 100 字，美国站 200 字")
  void siteLevelOverrideWins() {
    UUID shop = seedShop("AMZ-" + UUID.randomUUID().toString().substring(0, 6), AMAZON, siteUs);
    enableSite(shop, siteJp);
    var us = as("channel.operation.read", () -> editor.formSchema(shop, siteUs, "", "en-US"));
    var jp = as("channel.operation.read", () -> editor.formSchema(shop, siteJp, "", "ja-JP"));
    assertThat(us.fields().stream().filter(f -> "TITLE".equals(f.field())).findFirst().orElseThrow().maxLength())
        .isEqualTo(200);
    assertThat(jp.fields().stream().filter(f -> "TITLE".equals(f.field())).findFirst().orElseThrow().maxLength())
        .isEqualTo(100);
  }

  @Test
  @DisplayName("4.2.1.1 平台能力矩阵变化时表单自动变化（加键 ⇒ SUPPORTED，改 false ⇒ 移出可编辑字段）")
  void capabilityMatrixDrivesTheForm() {
    UUID shop = seedShop("AMZ-" + UUID.randomUUID().toString().substring(0, 6), AMAZON, siteUs);

    // 能力矩阵没有 video_support ⇒ UNKNOWN（**不是**「不支持」，也不是「没有约束」）
    var before = as("channel.operation.read", () -> editor.formSchema(shop, siteUs, "", "en-US"));
    var videoBefore = before.fields().stream().filter(f -> "VIDEO".equals(f.field())).findFirst().orElseThrow();
    assertThat(videoBefore.capabilityState()).isEqualTo("UNKNOWN");
    assertThat(videoBefore.ownerModule()).isEqualTo("2.1.1.2");

    // 能力矩阵补一个键 ⇒ 表单自动跟着变
    exec("update platform set capabilities = capabilities || '{\"video_support\":true}'::jsonb"
        + " where id = '" + AMAZON + "'");
    var after = as("channel.operation.read", () -> editor.formSchema(shop, siteUs, "", "en-US"));
    var videoAfter = after.fields().stream().filter(f -> "VIDEO".equals(f.field())).findFirst().orElseThrow();
    assertThat(videoAfter.capabilityState()).isEqualTo("SUPPORTED");

    // 改成 false ⇒ 这一栏被移出可编辑字段，并进 unsupportedFields
    exec("update platform set capabilities = capabilities || '{\"video_support\":false}'::jsonb"
        + " where id = '" + AMAZON + "'");
    var off = as("channel.operation.read", () -> editor.formSchema(shop, siteUs, "", "en-US"));
    assertThat(off.fields().stream().map(EditorFieldSchema::field)).doesNotContain("VIDEO");
    assertThat(off.unsupportedFields().stream().map(EditorFieldSchema::field)).contains("VIDEO");

    // 复原，避免影响后续用例
    exec("update platform set capabilities = capabilities - 'video_support' where id = '" + AMAZON + "'");
  }

  // ==================================================================
  // 二、草稿与线上物理分离
  // ==================================================================

  @Test
  @DisplayName("4.2.1.1 保存草稿不写线上 listing（内容列逐字不变）")
  void savingDraftNeverTouchesLiveListing() {
    UUID shop = seedShop("AMZ-" + UUID.randomUUID().toString().substring(0, 6), AMAZON, siteUs);
    ListingRow live = seedListing(shop, siteUs, "LIVE-1", "线上原标题");
    exec("update listing set bullets = array['线上卖点'], images = '[\"https://img/live.jpg\"]'::jsonb"
        + " where id = '" + live.id() + "'");

    String titleBefore = scalar("select title from listing where id = '" + live.id() + "'");
    String bulletsBefore = scalar("select bullets::text from listing where id = '" + live.id() + "'");
    String imagesBefore = scalar("select images::text from listing where id = '" + live.id() + "'");

    var saved =
        as(
            "channel.operation.create",
            () ->
                editor.save(
                    new EditorDraftRequest(
                        shop, siteUs, "en-US", null, live.id(), "Electronics > Audio",
                        List.of(text("TITLE", "草稿标题"), text("DESCRIPTION", "草稿描述"),
                            items("BULLETS", "草稿卖点"),
                            items("IMAGES", "https://img/draft.jpg")),
                        "")));

    assertThat(saved.state()).isEqualTo("DRAFT");
    assertThat(saved.validation().valid()).isTrue();
    assertThat(scalar("select title from listing where id = '" + live.id() + "'"))
        .as("线上标题必须逐字不变").isEqualTo(titleBefore);
    assertThat(scalar("select bullets::text from listing where id = '" + live.id() + "'"))
        .as("线上卖点必须逐字不变").isEqualTo(bulletsBefore);
    assertThat(scalar("select images::text from listing where id = '" + live.id() + "'"))
        .as("线上图片必须逐字不变").isEqualTo(imagesBefore);

    // 草稿里存的是新内容
    assertThat(saved.fieldValues().stream().filter(v -> "TITLE".equals(v.field())).findFirst().orElseThrow().text())
        .isEqualTo("草稿标题");
  }

  @Test
  @DisplayName("4.2.1.1 触发器级防线：app.draft_only 事务里改线上内容列被 DB 拒")
  void liveContentIsRejectedInsideDraftOnlyTransaction() {
    UUID shop = seedShop("AMZ-" + UUID.randomUUID().toString().substring(0, 6), AMAZON, siteUs);
    ListingRow live = seedListing(shop, siteUs, "LIVE-2", "线上原标题");

    // 直接开一个连接、打开 draft_only 标记，尝试改线上内容列 ⇒ DB 拒
    Throwable failure =
        org.assertj.core.api.Assertions.catchThrowable(
            () ->
                execAsDraftOnly(
                    "update listing set bullets = array['偷偷改的'] where id = '" + live.id() + "'"));
    assertThat(failure).as("打开 draft_only 后线上内容列必须写不进去").isNotNull();
    assertThat(scalar("select bullets::text from listing where id = '" + live.id() + "'"))
        .as("线上卖点必须还是空集合").isEqualTo("{}");
  }

  @Test
  @DisplayName("4.2.1.1 草稿里出现该平台没有的字段 ⇒ DB 直接拒")
  void undeclaredFieldIsRejectedByDatabase() {
    UUID shop = seedShop("AMZ-" + UUID.randomUUID().toString().substring(0, 6), AMAZON, siteUs);
    ListingRow live = seedListing(shop, siteUs, "LIVE-3", "线上原标题");

    UUID shopify =
        seedShop("SHP-" + UUID.randomUUID().toString().substring(0, 6), SHOPIFY, siteShopifyUs);
    ListingRow shopifyLive = seedListing(shopify, siteShopifyUs, "LIVE-4", "Shopify 商品");
    Throwable rejected =
        org.assertj.core.api.Assertions.catchThrowable(
            () ->
                as(
                    "channel.operation.create",
                    () ->
                        editor.save(
                            new EditorDraftRequest(
                                shopify, siteShopifyUs, "en-US", null, shopifyLive.id(), "",
                                // Shopify 没有要点栏 —— 这一栏在平台要求里根本没有
                                List.of(text("TITLE", "标题"), items("BULLETS", "不该出现的要点")),
                                ""))));
    assertThat(rejected).as("该平台没有的字段必须被拒（DB 触发器 + 服务层校验双道）").isNotNull();
  }

  // ==================================================================
  // 三、按平台 + 站点 + 语言分别保存
  // ==================================================================

  @Test
  @DisplayName("4.2.1.1 同一商品在不同平台 / 不同语言各存一份，内容互不污染")
  void contentIsIsolatedByPlatformSiteAndLocale() {
    UUID amazonShop = seedShop("AMZ-" + UUID.randomUUID().toString().substring(0, 6), AMAZON, siteUs);
    UUID tiktokShop = seedShop("TTK-" + UUID.randomUUID().toString().substring(0, 6), TIKTOK, siteTikTokUs);
    UUID sku = UUID.randomUUID();

    as(
        "channel.operation.create",
        () ->
            editor.save(
                new EditorDraftRequest(
                    amazonShop, siteUs, "en-US", sku, null, "",
                    List.of(text("TITLE", "Amazon 英文标题")), "")));
    as(
        "channel.operation.create",
        () ->
            editor.save(
                new EditorDraftRequest(
                    amazonShop, siteUs, "de-DE", sku, null, "",
                    List.of(text("TITLE", "Amazon 德文标题")), "")));
    as(
        "channel.operation.create",
        () ->
            editor.save(
                new EditorDraftRequest(
                    tiktokShop, siteTikTokUs, "en-US", sku, null, "",
                    List.of(text("TITLE", "TikTok 英文标题")), "")));

    var drafts = as("channel.operation.read", () -> editor.drafts(null, null, 0, 50));
    assertThat(drafts).hasSize(3);
    assertThat(drafts.stream().map(EditorDraftRow::locale)).containsExactlyInAnyOrder("en-US", "de-DE", "en-US");
    assertThat(drafts.stream().map(EditorDraftRow::platformCode))
        .containsExactlyInAnyOrder("AMAZON", "AMAZON", "TIKTOK_SHOP");
    assertThat(drafts.stream().filter(d -> "AMAZON".equals(d.platformCode()) && "en-US".equals(d.locale()))
            .findFirst().orElseThrow().fieldValues().get(0).text())
        .isEqualTo("Amazon 英文标题");

    // 同一 (店铺, 站点, 语言) 只能有一份草稿 —— DB 唯一约束
    Throwable failure =
        org.assertj.core.api.Assertions.catchThrowable(
            () ->
                exec(
                    "insert into listing_editor_draft (id,tenant_id,shop_id,platform_id,marketplace_id,"
                        + "locale,source_sku_id,field_values) values ('" + UUID.randomUUID() + "','" + tenant
                        + "','" + amazonShop + "','" + AMAZON + "','" + siteUs + "','en-US','" + sku
                        + "','{}'::jsonb)"));
    assertThat(failure).as("同一 (店铺,站点,语言,源商品) 只能有一份草稿").isNotNull();
  }

  @Test
  @DisplayName("4.2.1.1 多语言 Tab 真存多语言版本（改一个语言不影响另一个）")
  void multiLanguageDraftsAreReal() {
    UUID shop = seedShop("AMZ-" + UUID.randomUUID().toString().substring(0, 6), AMAZON, siteUs);
    ListingRow live = seedListing(shop, siteUs, "LIVE-5", "线上原标题");

    as(
        "channel.operation.create",
        () ->
            editor.save(
                new EditorDraftRequest(
                    shop, siteUs, "en-US", null, live.id(), "",
                    List.of(text("TITLE", "English title")), "")));
    as(
        "channel.operation.create",
        () ->
            editor.save(
                new EditorDraftRequest(
                    shop, siteUs, "ja-JP", null, live.id(), "",
                    List.of(text("TITLE", "日本語タイトル")), "")));

    var drafts = as("channel.operation.read", () -> editor.drafts(shop, null, 0, 50));
    assertThat(drafts).hasSize(2);
    var en = drafts.stream().filter(d -> "en-US".equals(d.locale())).findFirst().orElseThrow();
    var jp = drafts.stream().filter(d -> "ja-JP".equals(d.locale())).findFirst().orElseThrow();
    assertThat(en.draftId()).isNotEqualTo(jp.draftId());
    assertThat(en.fieldValues().get(0).text()).isEqualTo("English title");
    assertThat(jp.fieldValues().get(0).text()).isEqualTo("日本語タイトル");

    // 页签里两个语言都在
    var schema = as("channel.operation.read", () -> editor.formSchema(shop, siteUs, "", "en-US"));
    assertThat(schema.locales().stream().map(EditorLocaleTab::locale)).contains("en-US", "ja-JP");
  }

  // ==================================================================
  // 四、校验真算
  // ==================================================================

  @Test
  @DisplayName("4.2.1.1 校验真算：必填缺失 / 超长 / 条数超限，逐条指到具体字段")
  void validationIsReal() {
    UUID shop = seedShop("AMZ-" + UUID.randomUUID().toString().substring(0, 6), AMAZON, siteUs);
    ListingRow live = seedListing(shop, siteUs, "LIVE-6", "线上原标题");

    // ① 必填缺失（标题空）
    var missing =
        as(
            "channel.operation.read",
            () ->
                editor.validate(
                    new EditorValidateRequest(
                        shop, siteUs, "en-US", "", List.of(text("TITLE", "  ")))));
    assertThat(missing.valid()).isFalse();
    assertThat(missing.blockingCount()).isPositive();
    var missingTitle =
        missing.violations().stream()
            .filter(v -> "TITLE".equals(v.field()) && "REQUIRED_MISSING".equals(v.code()))
            .findFirst()
            .orElseThrow();
    assertThat(missingTitle.severity()).isEqualTo("BLOCKING");
    assertThat(missingTitle.label()).isEqualTo("标题");

    // ② 超长（Amazon 标题 200 字）
    String longTitle = "标".repeat(300);
    var tooLong =
        as(
            "channel.operation.read",
            () ->
                editor.validate(
                    new EditorValidateRequest(
                        shop, siteUs, "en-US", "", List.of(text("TITLE", longTitle)))));
    var lengthViolation =
        tooLong.violations().stream()
            .filter(v -> "TITLE".equals(v.field()) && "LENGTH_EXCEEDED".equals(v.code()))
            .findFirst()
            .orElseThrow();
    assertThat(lengthViolation.limit()).isEqualTo(200);
    assertThat(lengthViolation.actual()).isEqualTo(300);

    // ③ 条数超限（Amazon 图片 9 张）
    String[] twelve = new String[12];
    for (int i = 0; i < 12; i++) {
      twelve[i] = "https://img/" + i + ".jpg";
    }
    List<EditorFieldValue> tooManyImages =
        List.of(text("TITLE", "标题"), items("IMAGES", twelve));
    var countViolation =
        as(
                "channel.operation.read",
                () -> editor.validate(new EditorValidateRequest(shop, siteUs, "en-US", "", tooManyImages)))
            .violations().stream()
            .filter(v -> "IMAGES".equals(v.field()) && "ITEM_COUNT_EXCEEDED".equals(v.code()))
            .findFirst()
            .orElseThrow();
    assertThat(countViolation.limit()).isEqualTo(9);
  }

  @Test
  @DisplayName("4.2.1.1「不知道」是一个状态：要求未知时是 UNKNOWN + ownerModule，不是「没有约束」")
  void unknownIsAStateNotAZero() {
    UUID shop = seedShop("AMZ-" + UUID.randomUUID().toString().substring(0, 6), AMAZON, siteUs);
    var schema = as("channel.operation.read", () -> editor.formSchema(shop, siteUs, "", "en-US"));

    // 视频：能力矩阵没有 video_support ⇒ UNKNOWN + ownerModule
    var video = schema.fields().stream().filter(f -> "VIDEO".equals(f.field())).findFirst().orElseThrow();
    assertThat(video.capabilityState()).isEqualTo("UNKNOWN");
    assertThat(video.ownerModule()).isEqualTo("2.1.1.2");
    assertThat(schema.unknownFields().stream().map(EditorFieldSchema::field)).contains("VIDEO");

    // 类目属性：细则在 4.2.3.1（catalog 侧）⇒ DELEGATED + ownerModule
    var attributes =
        schema.fields().stream().filter(f -> "CATEGORY_ATTRIBUTES".equals(f.field())).findFirst().orElseThrow();
    assertThat(attributes.constraintState()).isEqualTo("DELEGATED");
    assertThat(attributes.ownerModule()).isEqualTo("4.2.3.1");
    assertThat(schema.delegatedFields().stream().map(EditorFieldSchema::field)).contains("CATEGORY_ATTRIBUTES");

    // 校验里出现的是「提醒」而不是「没有约束」
    var validation =
        as(
            "channel.operation.read",
            () ->
                editor.validate(
                    new EditorValidateRequest(
                        shop, siteUs, "en-US", "", List.of(text("TITLE", "标题")))));
    assertThat(validation.violations().stream()
            .anyMatch(v -> "CAPABILITY_UNKNOWN".equals(v.code()) && "VIDEO".equals(v.field())))
        .as("能力未知要作为提醒出现").isTrue();
    assertThat(validation.violations().stream()
            .anyMatch(v -> "REQUIREMENTS_DELEGATED".equals(v.code())
                && "CATEGORY_ATTRIBUTES".equals(v.field())))
        .as("细则归别的模块要作为提醒出现").isTrue();
  }

  // ==================================================================
  // 五、提交审核 = 入队
  // ==================================================================

  @Test
  @DisplayName("4.2.1.1 提交审核只是入队（QUEUED），线上不动，且幂等")
  void submitOnlyQueues() {
    UUID shop = seedShop("AMZ-" + UUID.randomUUID().toString().substring(0, 6), AMAZON, siteUs);
    ListingRow live = seedListing(shop, siteUs, "LIVE-7", "线上原标题");
    String titleBefore = scalar("select title from listing where id = '" + live.id() + "'");

    var draft =
        as(
            "channel.operation.create",
            () ->
                editor.save(
                    new EditorDraftRequest(
                        shop, siteUs, "en-US", null, live.id(), "",
                        List.of(text("TITLE", "草稿标题"), text("DESCRIPTION", "草稿描述"),
                            items("IMAGES", "https://img/1.jpg")),
                        "")));

    var submission =
        as(
            "channel.operation.update",
            () -> editor.submit(draft.draftId().toString(), "idem-1"));
    assertThat(submission.state()).as("提交审核只是入队，不是发布").isEqualTo("QUEUED");
    assertThat(submission.draftId()).isEqualTo(draft.draftId());
    assertThat(submission.dispatchNote()).contains("4.3.1.1");
    assertThat(scalar("select title from listing where id = '" + live.id() + "'"))
        .as("入队不改线上").isEqualTo(titleBefore);

    // 幂等：同一个键再提交一次 ⇒ 同一条入队记录
    var again = as("channel.operation.update", () -> editor.submit(draft.draftId().toString(), "idem-1"));
    assertThat(again.submissionId()).isEqualTo(submission.submissionId());
    assertThat(as("channel.operation.read", () -> editor.submissions(0, 50))).hasSize(1);

    // 草稿状态是 SUBMITTED（已入队），但**不是**已发布
    var reloaded = as("channel.operation.read", () -> editor.draft(draft.draftId().toString()));
    assertThat(reloaded.state()).isEqualTo("SUBMITTED");
    assertThat(reloaded.submittedAt()).isNotNull();
  }

  @Test
  @DisplayName("4.2.1.1 有阻断项时不允许入队（违规项指到具体字段）")
  void submitIsBlockedByValidation() {
    UUID shop = seedShop("AMZ-" + UUID.randomUUID().toString().substring(0, 6), AMAZON, siteUs);
    ListingRow live = seedListing(shop, siteUs, "LIVE-8", "线上原标题");
    var draft =
        as(
            "channel.operation.create",
            () ->
                editor.save(
                    new EditorDraftRequest(
                        shop, siteUs, "en-US", null, live.id(), "",
                        List.of(text("TITLE", "标题")), "")));
    // 把描述清掉 ⇒ 类目属性必填缺失 ⇒ 阻断
    as(
        "channel.operation.create",
        () ->
            editor.save(
                new EditorDraftRequest(
                    shop, siteUs, "en-US", null, live.id(), "",
                    List.of(text("TITLE", "标题"), pairs("CATEGORY_ATTRIBUTES")), "")));

    Throwable failure =
        org.assertj.core.api.Assertions.catchThrowable(
            () -> as("channel.operation.update", () -> editor.submit(draft.draftId().toString(), "idem-2")));
    assertThat(failure).as("有阻断项时不允许入队").isNotNull();
    assertThat(failure.getMessage()).contains("描述");
    assertThat(as("channel.operation.read", () -> editor.submissions(0, 50))).isEmpty();
  }

  @Test
  @DisplayName("4.2.1.1 内容改了 ⇒ 之前入队的那份被撤销（它冻结的是旧内容）")
  void editingAfterSubmitCancelsTheQueuedSubmission() {
    UUID shop = seedShop("AMZ-" + UUID.randomUUID().toString().substring(0, 6), AMAZON, siteUs);
    ListingRow live = seedListing(shop, siteUs, "LIVE-9", "线上原标题");
    var draft =
        as(
            "channel.operation.create",
            () ->
                editor.save(
                    new EditorDraftRequest(
                        shop, siteUs, "en-US", null, live.id(), "",
                        List.of(text("TITLE", "第一版标题"), text("DESCRIPTION", "描述"),
                            items("IMAGES", "https://img/1.jpg")), "")));
    as("channel.operation.update", () -> editor.submit(draft.draftId().toString(), "idem-3"));

    as(
        "channel.operation.create",
        () ->
            editor.save(
                new EditorDraftRequest(
                    shop, siteUs, "en-US", null, live.id(), "",
                    List.of(text("TITLE", "第二版标题"), text("DESCRIPTION", "描述"),
                        items("IMAGES", "https://img/1.jpg")), "")));

    var submissions = as("channel.operation.read", () -> editor.submissions(0, 50));
    assertThat(submissions).hasSize(1);
    assertThat(submissions.get(0).state()).as("旧内容的那次入队必须撤销").isEqualTo("CANCELLED");
    var reloaded = as("channel.operation.read", () -> editor.draft(draft.draftId().toString()));
    assertThat(reloaded.state()).as("草稿回到编辑中").isEqualTo("DRAFT");
  }

  // ==================================================================
  // 六、能力声明与端点往返
  // ==================================================================

  @Test
  @DisplayName("4.2.1.1 能力声明如实报出未交付段（4.2.2.1 刊登模板 / 4.3.1.1 派发 / 4.2.3.2 合规预检）")
  void capabilitiesReportHonestGaps() {
    var caps = as("channel.operation.read", () -> editor.capabilities());
    var template = caps.dimensions().stream()
        .filter(d -> "listing_template_apply".equals(d.dimension())).findFirst().orElseThrow();
    assertThat(template.available()).as("4.2.2.1 尚未交付").isFalse();
    assertThat(template.ownerModule()).isEqualTo("4.2.2.1");
    assertThat(template.impact()).isNotBlank();

    var dispatch = caps.dimensions().stream()
        .filter(d -> "publish_dispatch".equals(d.dimension())).findFirst().orElseThrow();
    assertThat(dispatch.available()).isFalse();
    assertThat(dispatch.ownerModule()).isEqualTo("4.3.1.1");

    var compliance = caps.dimensions().stream()
        .filter(d -> "compliance_precheck".equals(d.dimension())).findFirst().orElseThrow();
    assertThat(compliance.available()).isFalse();
    assertThat(compliance.ownerModule()).isEqualTo("4.2.3.2");

    assertThat(caps.draftIsolationNote()).contains("草稿");
    assertThat(caps.submitNote()).contains("入队");
  }

  @Test
  @DisplayName("4.2.1.1 端点往返：schema / 草稿 / 校验 / 入队")
  void httpRoundTrip() throws Exception {
    UUID shop = seedShop("AMZ-" + UUID.randomUUID().toString().substring(0, 6), AMAZON, siteUs);
    ListingRow live = seedListing(shop, siteUs, "LIVE-10", "线上原标题");

    http.perform(get("/channel/listing-editor/capabilities").header("Authorization", bearer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.dimensions.length()").value(org.hamcrest.Matchers.greaterThan(5)));

    http.perform(
            get("/channel/listing-editor/form-schema")
                .header("Authorization", bearer)
                .param("shopId", shop.toString())
                .param("marketplaceId", siteUs.toString())
                .param("locale", "en-US"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.platformCode").value("AMAZON"))
        .andExpect(jsonPath("$.fields[0].field").value("TITLE"))
        .andExpect(jsonPath("$.fields[0].requirement").value("REQUIRED"));

    var saved =
        http.perform(
                post("/channel/listing-editor/drafts")
                    .header("Authorization", bearer)
                    .contentType("application/json")
                    .content(
                        JSON.writeValueAsString(
                            new EditorDraftRequest(
                                shop, siteUs, "en-US", null, live.id(), "",
                                List.of(text("TITLE", "端点标题"), text("DESCRIPTION", "端点描述"),
                                    items("IMAGES", "https://img/1.jpg")), ""))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.state").value("DRAFT"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String draftId = JSON.readTree(saved).get("draftId").asText();

    http.perform(
            post("/channel/listing-editor/validate")
                .header("Authorization", bearer)
                .contentType("application/json")
                .content(
                    JSON.writeValueAsString(
                        new EditorValidateRequest(
                            shop, siteUs, "en-US", "",
                            List.of(text("TITLE", "端点标题"), text("DESCRIPTION", "端点描述"),
                                items("IMAGES", "https://img/1.jpg"))))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.valid").value(true));

    http.perform(
            post("/channel/listing-editor/drafts/" + draftId + "/submit")
                .header("Authorization", bearer)
                .contentType("application/json")
                .content(JSON.writeValueAsString(new EditorSubmitRequest("idem-http"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("QUEUED"));

    // 线上仍然没动
    assertThat(scalar("select title from listing where id = '" + live.id() + "'"))
        .isEqualTo("线上原标题");
  }

  @Test
  @DisplayName("4.2.1.1 目标站点不是该店铺已启用的站点 ⇒ 明确拒绝")
  void disabledSiteIsRejected() {
    UUID shop = seedShop("AMZ-" + UUID.randomUUID().toString().substring(0, 6), AMAZON, siteUs);
    Throwable failure =
        org.assertj.core.api.Assertions.catchThrowable(
            () -> as("channel.operation.read", () -> editor.formSchema(shop, siteJp, "", "ja-JP")));
    assertThat(failure).as("未在该店铺启用的站点不能当目标").isNotNull();
    assertThat(failure.getMessage()).contains("启用");
  }
}
