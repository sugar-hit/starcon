package com.starcon.channel;

import static com.starcon.channel.ListingBulkEditModels.*;
import static com.starcon.channel.ListingModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
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
 * 4.2.1.2 批量编辑。
 *
 * <p>本测试类钉住这几组事：
 *
 * <ol>
 *   <li><b>🔴 核心不变量：批量编辑只改草稿不直接改线上</b>：确认之后 listing.title / category_path
 *       <b>逐字不变</b>；而且这条不变量在 <b>DB 层</b>也成立 —— 打开 app.draft_only 的事务里直接 UPDATE
 *       listing 内容列会被拒（不是「我们没写那条 SQL」，是写不了）。
 *   <li><b>🔴 预览 = 执行</b>：preview 返回的逐条 before/after 与落库后的修改记录逐条相等。
 *   <li><b>六种批量操作都真实现</b>：追加 / 替换 / 正则替换标题、批量换图、统一加卖点、批量改类目。
 *   <li><b>超限与无基线是显式跳过，不是静默截断</b>：SKIPPED_LIMIT / SKIPPED_NO_BASELINE 各有用例。
 *   <li><b>ReDoS 防线</b>：{@code (a+)+$} 这类模式被拒，且拒绝发生在执行之前。
 *   <li><b>touched_fields</b>：只改标题的批量不会顺手把类目也写进草稿。
 *   <li><b>多租户三断言</b>（AGENTS.md 硬性要求，缺一不可）+ <b>跨租户批量选中自动切分</b>。
 * </ol>
 *
 * <p><b>时刻一律用固定的 {@link OffsetDateTime} 钉死</b>，不用 {@code now()}。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ListingBulkEditIntegrationTest {

  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID user = UUID.randomUUID();
  static HttpServer identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static final ObjectMapper JSON = new ObjectMapper();

  UUID a, b;
  AccessService.Access actorA, actorB;
  String bearerA;

  // 🔴 直接用 V1 已播种的平台码表，不自己造平台：platform.code 是 UNIQUE 的，
  //    自造会撞码；而且本模块的平台切分断言依赖真实 code，自造的码反而失真。
  //    （V1：1111...-101=AMAZON、1111...-104=TIKTOK_SHOP）
  static final UUID AMAZON = UUID.fromString("11111111-1111-1111-1111-111111111101");
  static final UUID TIKTOK = UUID.fromString("11111111-1111-1111-1111-111111111104");
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

  @Autowired ListingService listings;
  @Autowired ListingBulkEditService service;
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
    execAsTenantWith(tenant, sql, false);
  }

  /** 🔴 打开 app.draft_only 的事务 —— 用来验证「批量编辑事务里写不了线上」这条不变量是 DB 层强制的。 */
  static void execAsDraftOnly(UUID tenant, String sql) {
    execAsTenantWith(tenant, sql, true);
  }

  static void execAsTenantWith(UUID tenant, String sql, boolean draftOnly) {
    try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute("select set_config('app.current_tenant','" + tenant + "',false)");
      s.execute("select set_config('app.actor','" + user + "',false)");
      s.execute("select set_config('app.department','',false)");
      s.execute("select set_config('app.permission','channel.operation.update',false)");
      s.execute("select set_config('app.grants','" + ALL_SCOPE_GRANTS + "',false)");
      if (draftOnly) {
        // 🔴 必须是**会话级**（第三个参数 false）：autocommit 下每条语句各自一个事务，
        //    is_local=true 的设置在下一条语句执行前就已经被回滚掉了，
        //    那样触发器根本看不到标记，测试会假绿。
        s.execute("select set_config('app.draft_only','true',false)");
      }
      s.execute(sql);
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  UUID seedShop(UUID tenant, UUID platformId, String code) {
    UUID id = UUID.randomUUID();
    execAsTenant(tenant,
        "insert into shop (tenant_id,id,platform_id,code,name) values ('" + tenant + "','" + id
            + "','" + platformId + "','" + code + "','测试店铺 " + code + "')"
            + " on conflict (tenant_id,code) do nothing");
    return id;
  }

  ListingRow seedListing(
      AccessService.Access who, UUID shopId, String externalItemId, String title, String categoryPath) {
    SaveListingRequest request =
        new SaveListingRequest(
            null, shopId, null, externalItemId, title, categoryPath, "cat-1",
            "ON_SALE", null, "", "IN_STOCK", null, null, null, null, null, null, List.of(), null);
    return as(who, "channel.operation.create", () -> listings.save(request));
  }

  static BulkEditParams appendParams(String suffix) {
    return new BulkEditParams(suffix, null, null, null, null, null, null, null, null);
  }

  static BulkEditParams emptyParams() {
    return new BulkEditParams(null, null, null, null, null, null, null, null, null);
  }

  static BulkEditParams replaceParams(String find, String replace) {
    return new BulkEditParams(null, find, replace, null, null, null, null, null, null);
  }

  static BulkEditParams regexParams(String pattern, String replacement) {
    return new BulkEditParams(null, null, replacement, pattern, replacement, null, null, null, null);
  }

  static BulkEditParams imageParams(List<String> images) {
    return new BulkEditParams(null, null, null, null, null, images, null, null, null);
  }

  static BulkEditParams bulletParams(List<String> bullets) {
    return new BulkEditParams(null, null, null, null, null, null, bullets, null, null);
  }

  static BulkEditParams categoryParams(String path, String categoryId) {
    return new BulkEditParams(null, null, null, null, null, null, null, path, categoryId);
  }

  BulkEditPreviewResponse preview(BulkEditRequest request) {
    return as(actorA, "channel.operation.create", () -> service.preview(request));
  }

  ListingBulkEditOperation confirm(UUID operationId) {
    return as(actorA, "channel.operation.update",
        () -> service.confirm(new BulkEditConfirmRequest(operationId, "")));
  }

  /** 直接读线上 listing 的标题 —— 断言「线上没被改」时用它，而不是读草稿。 */
  String liveTitleOf(AccessService.Access who, UUID listingId) {
    return as(who, "channel.operation.read", () -> listings.load(listingId.toString())).title();
  }

  // ==================================================================
  // 1. 🔴 核心不变量：只改草稿，不直接改线上
  // ==================================================================

  @Test
  @DisplayName("4.2.1.2 🔴 批量编辑只改草稿不直接改线上：确认后 listing 线上值逐字不变，草稿才有新值")
  void bulkEditOnlyTouchesDrafts() {
    UUID shop = seedShop(a, AMAZON, "ISO-AMZ");
    var row = seedListing(actorA, shop, "INV-1", "Original Title", "Electronics > Audio");

    var preview = preview(new BulkEditRequest(
        List.of(row.id()), null, "TITLE_APPEND", appendParams(" - 2026新品"), null, null, null, "批量加后缀"));

    // 预览阶段：改前 = 线上原值，改后 = 拟写进草稿的值；线上此刻仍未被动过
    assertThat(preview.changes()).hasSize(1);
    ListingBulkChangeRow change = preview.changes().get(0);
    assertThat(change.field()).isEqualTo("TITLE");
    assertThat(change.beforeValue()).isEqualTo("Original Title");
    assertThat(change.afterValue()).isEqualTo("Original Title - 2026新品");
    assertThat(change.outcome()).isEqualTo("APPLIED");
    assertThat(liveTitleOf(actorA, row.id())).isEqualTo("Original Title");

    // 确认：写草稿
    var applied = confirm(preview.operationId());
    assertThat(applied.state()).isEqualTo("APPLIED");
    assertThat(applied.appliedCount()).isEqualTo(1);

    // 🔴 线上值逐字不变 —— 磁盘上的 listing 表没有被碰过
    ListingRow after = as(actorA, "channel.operation.read", () -> listings.load(row.id().toString()));
    assertThat(after.title()).isEqualTo("Original Title");
    assertThat(after.categoryPath()).isEqualTo("Electronics > Audio");

    // 改后的值真的在草稿里
    ListingContentDraftRow draft = as(actorA, "channel.operation.read", () -> service.draft(row.id().toString()));
    assertThat(draft.title()).isEqualTo("Original Title - 2026新品");
    assertThat(draft.liveTitle()).isEqualTo("Original Title");
    assertThat(draft.touchedFields()).containsExactly("TITLE");
    assertThat(draft.state()).isEqualTo("DRAFT");
  }

  @Test
  @DisplayName("4.2.1.2 🔴 防线在 DB 层：批量编辑事务（app.draft_only）里直接 UPDATE 线上内容列被拒")
  void dbRefusesLiveContentWriteDuringBulkEdit() {
    UUID shop = seedShop(a, AMAZON, "TRIG-AMZ");
    var row = seedListing(actorA, shop, "TRIG-1", "线上原标题", "Cat > Sub");

    // 同样的 UPDATE，在批量编辑事务外（普通更新）是允许的 —— 4.3.1.2 的发布流程就走这条路
    execAsTenant(a, "update listing set title='发布流程改的标题' where tenant_id='" + a
        + "' and id='" + row.id() + "'");
    assertThat(liveTitleOf(actorA, row.id())).isEqualTo("发布流程改的标题");

    // 🔴 但在批量编辑事务里（app.draft_only='true'），同样的 UPDATE 由 DB 直接拒
    assertThat(catchThrowable(() -> execAsDraftOnly(a,
        "update listing set title='批量改的标题' where tenant_id='" + a + "' and id='" + row.id() + "'")))
        .hasMessageContaining("Bulk edit must never write live listing content");

    // 类目列同样受保护
    assertThat(catchThrowable(() -> execAsDraftOnly(a,
        "update listing set category_path='X > Y' where tenant_id='" + a + "' and id='" + row.id() + "'")))
        .isNotNull();

    // 被拒之后线上值没有被部分写入
    assertThat(liveTitleOf(actorA, row.id())).isEqualTo("发布流程改的标题");
  }

  @Test
  @DisplayName("4.2.1.2 🔴 预览 = 执行：preview 的逐条 before/after 与落库的修改记录逐条相等")
  void previewAndExecutionShareTheSameRows() {
    UUID shop = seedShop(a, AMAZON, "PEQ-AMZ");
    var one = seedListing(actorA, shop, "PEQ-1", "Alpha Widget", "Tools > Hammers");
    var two = seedListing(actorA, shop, "PEQ-2", "Beta Widget", "Tools > Drills");

    var preview = preview(new BulkEditRequest(
        List.of(one.id(), two.id()), null, "TITLE_REPLACE", replaceParams("Widget", "Gadget"),
        null, null, null, ""));

    assertThat(preview.changes()).hasSize(2);
    assertThat(preview.changes()).extracting(ListingBulkChangeRow::afterValue)
        .containsExactlyInAnyOrder("Alpha Gadget", "Beta Gadget");

    var applied = confirm(preview.operationId());

    // 🔴 确认后的修改记录与预览**逐条相等**：不存在"预览一套、执行另一套"
    assertThat(applied.changes()).hasSameSizeAs(preview.changes());
    for (ListingBulkChangeRow expected : preview.changes()) {
      assertThat(applied.changes())
          .anySatisfy(actual -> {
            assertThat(actual.listingId()).isEqualTo(expected.listingId());
            assertThat(actual.field()).isEqualTo(expected.field());
            assertThat(actual.beforeValue()).isEqualTo(expected.beforeValue());
            assertThat(actual.afterValue()).isEqualTo(expected.afterValue());
            assertThat(actual.outcome()).isEqualTo(expected.outcome());
          });
    }
  }

  // ==================================================================
  // 2. 六种批量操作都真实现
  // ==================================================================

  @Test
  @DisplayName("4.2.1.2 追加标题 / 替换标题：真实逐条改前改后，线上仍不变")
  void appendAndReplaceTitle() {
    UUID shop = seedShop(a, AMAZON, "OPS-AMZ");
    var one = seedListing(actorA, shop, "OPS-1", "Blue Lamp", "Home > Lighting");
    var two = seedListing(actorA, shop, "OPS-2", "Blue Lamp Pro", "Home > Lighting");

    var appended = confirm(preview(new BulkEditRequest(
        List.of(one.id(), two.id()), null, "TITLE_APPEND", appendParams(" 2026"),
        null, null, null, "")).operationId());
    assertThat(appended.changes()).extracting(ListingBulkChangeRow::afterValue)
        .containsExactlyInAnyOrder("Blue Lamp 2026", "Blue Lamp Pro 2026");

    // 确认后再来一次替换：改前值是**线上原值**，不是上一次的拟改值
    var replaced = confirm(preview(new BulkEditRequest(
        List.of(one.id(), two.id()), null, "TITLE_REPLACE", replaceParams("Lamp", "Lantern"),
        null, null, null, "")).operationId());
    assertThat(replaced.changes()).extracting(ListingBulkChangeRow::beforeValue)
        .containsExactlyInAnyOrder("Blue Lamp", "Blue Lamp Pro");
    assertThat(replaced.changes()).extracting(ListingBulkChangeRow::afterValue)
        .containsExactlyInAnyOrder("Blue Lantern", "Blue Lantern Pro");

    // 两次批量都没碰线上
    assertThat(liveTitleOf(actorA, one.id())).isEqualTo("Blue Lamp");
    assertThat(liveTitleOf(actorA, two.id())).isEqualTo("Blue Lamp Pro");

    // 草稿累积成一份，只写了标题
    ListingContentDraftRow draft = as(actorA, "channel.operation.read", () -> service.draft(one.id().toString()));
    assertThat(draft.title()).isEqualTo("Blue Lantern");
    assertThat(draft.touchedFields()).containsExactly("TITLE");
    assertThat(draft.categoryPath()).as("没拟改的字段不该出现在草稿里").isNull();
  }

  @Test
  @DisplayName("4.2.1.2 正则替换标题：分组引用真生效，且危险模式在执行前被拒")
  void regexReplaceTitle() {
    UUID shop = seedShop(a, AMAZON, "RX-AMZ");
    var one = seedListing(actorA, shop, "RX-1", "Model A200 for Home", "Tools");
    var two = seedListing(actorA, shop, "RX-2", "Model B310 for Office", "Tools");

    var applied = confirm(preview(new BulkEditRequest(
        List.of(one.id(), two.id()), null, "TITLE_REGEX_REPLACE",
        regexParams("Model ([A-Z]\\d+) for (.+)", "$2 Model $1"), null, null, null, "")).operationId());

    assertThat(applied.changes()).extracting(ListingBulkChangeRow::afterValue)
        .containsExactlyInAnyOrder("Home Model A200", "Office Model B310");
    assertThat(liveTitleOf(actorA, one.id())).isEqualTo("Model A200 for Home");

    // 🔴 ReDoS：危险模式在**预览入口**就被拒，一次都不会进入计算（不是跑完才报错）
    assertThat(catchThrowable(() -> preview(new BulkEditRequest(
        List.of(one.id()), null, "TITLE_REGEX_REPLACE",
        regexParams("(a+)+$", "X"), null, null, null, ""))))
        .hasMessageContaining("不安全");
    assertThat(as(actorA, "channel.operation.read", () -> service.checkRegex("(a+)+$")).safe()).isFalse();

    // 相邻无界量词同样被拒
    assertThat(as(actorA, "channel.operation.read", () -> service.checkRegex(".*.*")).safe()).isFalse();
    // 反向引用被拒
    assertThat(as(actorA, "channel.operation.read", () -> service.checkRegex("(a\\1")).safe()).isFalse();
    // 正常的模式照常通过
    assertThat(as(actorA, "channel.operation.read", () -> service.checkRegex("Model (\\w+)")).safe()).isTrue();
    // 只有无界量词、没有嵌套的合法模式不被误杀
    assertThat(as(actorA, "channel.operation.read", () -> service.checkRegex("(a+)(b+)")).safe()).isTrue();
  }

  @Test
  @DisplayName("4.2.1.2 批量换图：整组替换真生效；统一加卖点无基线时显式跳过、有基线时真追加")
  void replaceImagesAndAppendBullets() {
    UUID shop = seedShop(a, AMAZON, "IMG-AMZ");
    var imageRow = seedListing(actorA, shop, "IMG-1", "Image Target", "Home");
    var bulletRow = seedListing(actorA, shop, "IMG-2", "Bullet Target", "Home");

    // 换图：整组替换，改前为空集（线上图片基线未接入，如实显示为空集而不是编一个）
    var images = confirm(preview(new BulkEditRequest(
        List.of(imageRow.id()), null, "REPLACE_IMAGES",
        imageParams(List.of("https://cdn/1.jpg", "https://cdn/2.jpg")), null, null, null, "")).operationId());
    assertThat(images.changes()).extracting(ListingBulkChangeRow::outcome).containsExactly("APPLIED");
    assertThat(images.changes().get(0).beforeValue()).isEqualTo("[]");

    ListingContentDraftRow afterImages =
        as(actorA, "channel.operation.read", () -> service.draft(imageRow.id().toString()));
    assertThat(afterImages.images()).containsExactly("https://cdn/1.jpg", "https://cdn/2.jpg");
    assertThat(afterImages.touchedFields()).contains("IMAGES");

    // 🔴 卖点：此时还没有草稿 ⇒ 没有可比基线 ⇒ 显式跳过，并说明照做会清空原有卖点
    var noBaseline = preview(new BulkEditRequest(
        List.of(imageRow.id()), null, "BULLET_APPEND", bulletParams(List.of("卖点一")), null, null, null, ""));
    assertThat(noBaseline.changes()).extracting(ListingBulkChangeRow::outcome)
        .containsExactly("SKIPPED_NO_BASELINE");
    assertThat(noBaseline.changes().get(0).reason()).contains("清空原有卖点");

    // 确认那批被跳过的操作：草稿里**不会**出现 BULLETS
    confirm(noBaseline.operationId());
    ListingContentDraftRow draft = as(actorA, "channel.operation.read", () -> service.draft(imageRow.id().toString()));
    assertThat(draft.bullets()).as("被跳过的字段不该进草稿").isNull();
    assertThat(draft.touchedFields()).doesNotContain("BULLETS");

    // 🔴 有基线时是真追加（而不是跳过）。基线由 4.2.1.1 刊登编辑器留下 ——
    //    该模块尚未交付，这里直接造一份等价草稿来验证追加路径。
    seedBulletsDraft(a, bulletRow.id(), List.of("原有卖点甲", "原有卖点乙"));
    var appended = confirm(preview(new BulkEditRequest(
        List.of(bulletRow.id()), null, "BULLET_APPEND",
        bulletParams(List.of("新增卖点丙")), null, null, null, "")).operationId());

    assertThat(appended.changes()).extracting(ListingBulkChangeRow::outcome).containsExactly("APPLIED");
    assertThat(appended.changes().get(0).beforeValue()).isEqualTo("[\"原有卖点甲\",\"原有卖点乙\"]");
    assertThat(appended.changes().get(0).afterValue())
        .isEqualTo("[\"原有卖点甲\",\"原有卖点乙\",\"新增卖点丙\"]");

    ListingContentDraftRow bullets =
        as(actorA, "channel.operation.read", () -> service.draft(bulletRow.id().toString()));
    assertThat(bullets.bullets()).containsExactly("原有卖点甲", "原有卖点乙", "新增卖点丙");
    assertThat(bullets.touchedFields()).containsExactly("BULLETS");
  }

  /** 造一份「4.2.1.1 刊登编辑器留下的草稿」：只有卖点这一个字段被拟改。 */
  void seedBulletsDraft(UUID tenant, UUID listingId, List<String> bullets) {
    execAsTenant(tenant,
        "insert into listing_content_draft"
            + " (id,tenant_id,listing_id,bullets,touched_fields,state,base_version,"
            + "  base_listing_updated_at,last_operation_id,created_at,updated_at)"
            + " values (gen_random_uuid(),'" + tenant + "','" + listingId + "',"
            + " array['" + String.join("','", bullets) + "']::text[],array['BULLETS']::text[],"
            + " 'DRAFT',0,now(),gen_random_uuid(),now(),now())");
  }

  @Test
  @DisplayName("4.2.1.2 批量改类目：类目路径与平台类目 id 是同一次决定，两列一起写进草稿")
  void replaceCategory() {
    UUID shop = seedShop(a, AMAZON, "CAT-AMZ");
    var row = seedListing(actorA, shop, "CAT-1", "Category Target", "Electronics > Audio");

    var applied = confirm(preview(new BulkEditRequest(
        List.of(row.id()), null, "REPLACE_CATEGORY",
        categoryParams("Electronics > Audio > Headphones", "cat-headphones"),
        null, null, null, "")).operationId());

    assertThat(applied.changes()).extracting(ListingBulkChangeRow::field)
        .containsExactlyInAnyOrder("CATEGORY_PATH", "PLATFORM_CATEGORY_ID");
    assertThat(applied.changes()).allSatisfy(c -> assertThat(c.outcome()).isEqualTo("APPLIED"));
    assertThat(applied.changes()).extracting(ListingBulkChangeRow::beforeValue)
        .containsExactlyInAnyOrder("Electronics > Audio", "cat-1");

    ListingContentDraftRow draft = as(actorA, "channel.operation.read", () -> service.draft(row.id().toString()));
    assertThat(draft.categoryPath()).isEqualTo("Electronics > Audio > Headphones");
    assertThat(draft.platformCategoryId()).isEqualTo("cat-headphones");
    assertThat(draft.touchedFields()).containsExactlyInAnyOrder("CATEGORY_PATH", "PLATFORM_CATEGORY_ID");

    // 线上类目没动
    ListingRow live = as(actorA, "channel.operation.read", () -> listings.load(row.id().toString()));
    assertThat(live.categoryPath()).isEqualTo("Electronics > Audio");
  }

  // ==================================================================
  // 3. 显式跳过，而不是静默截断
  // ==================================================================

  @Test
  @DisplayName("4.2.1.2 超出平台长度上限：显式 SKIPPED_LIMIT 且 after 仍是完整长串，不静默截断")
  void overLimitIsSkippedNotTruncated() {
    UUID shop = seedShop(a, AMAZON, "LIM-AMZ");
    var row = seedListing(actorA, shop, "LIM-1", "Short", "Home");

    var preview = preview(new BulkEditRequest(
        List.of(row.id()), null, "TITLE_APPEND", appendParams(" VeryLongSuffixThatGoesPastTheLimit"),
        20, null, null, ""));

    ListingBulkChangeRow change = preview.changes().get(0);
    assertThat(change.outcome()).isEqualTo("SKIPPED_LIMIT");
    assertThat(change.changed()).isFalse();
    assertThat(change.reason()).contains("显式跳过而不是截断");
    // 🔴 关键：after 是**完整**的拟改值，不是被偷偷截短的版本 —— 运营能看到"会变成多长"
    assertThat(change.afterValue()).isEqualTo("Short VeryLongSuffixThatGoesPastTheLimit");

    // 确认后这条不进草稿
    confirm(preview.operationId());
    assertThat(catchThrowable(() -> as(actorA, "channel.operation.read", () -> service.draft(row.id().toString()))))
        .isNotNull();
  }

  @Test
  @DisplayName("4.2.1.2 规则跑完与原值相同：报 NO_CHANGE，不制造无意义改动")
  void noChangeIsReportedAsSuch() {
    UUID shop = seedShop(a, AMAZON, "NC-AMZ");
    var row = seedListing(actorA, shop, "NC-1", "No Such Token Here", "Home");

    var preview = preview(new BulkEditRequest(
        List.of(row.id()), null, "TITLE_REPLACE", replaceParams("ZZZ-not-present", "X"), null, null, null, ""));

    assertThat(preview.changes().get(0).outcome()).isEqualTo("NO_CHANGE");
    assertThat(preview.changes().get(0).changed()).isFalse();

    var applied = confirm(preview.operationId());
    assertThat(applied.appliedCount()).isZero();
    assertThat(applied.skippedCount()).isEqualTo(1);
  }

  // ==================================================================
  // 4. 按平台差异化模板
  // ==================================================================

  @Test
  @DisplayName("4.2.1.2 按平台差异化模板：上限按平台生效，模板不套到别的平台上")
  void platformTemplatesDifferentiate() {
    UUID amazonShop = seedShop(a, AMAZON, "PT-AMZ");
    UUID tiktokShop = seedShop(a, TIKTOK, "PT-TK");
    var amazon = seedListing(actorA, amazonShop, "PT-1", "Amazon Item Title", "Home");
    var tiktok = seedListing(actorA, tiktokShop, "PT-2", "TikTok Item Title", "Home");

    var template = as(actorA, "channel.operation.create", () -> service.createTemplate(
        new BulkTemplateRequest(AMAZON, "title-limit-10", "亚马逊 10 字标题上限",
            "TITLE_APPEND", appendParams(" Extra"), 10, 5, 5)));
    assertThat(template.platformCode()).isEqualTo("AMAZON");
    assertThat(template.titleMaxLength()).isEqualTo(10);

    // 一批里混了两个平台：Amazon 的按模板上限判，TikTok 的被显式排除而不是套用 Amazon 的上限
    var preview = preview(new BulkEditRequest(
        List.of(amazon.id(), tiktok.id()), template.id(), null, null, null, null, null, ""));

    assertThat(preview.changes()).extracting(ListingBulkChangeRow::externalItemId)
        .containsExactly("PT-1");
    assertThat(preview.changes().get(0).outcome()).isEqualTo("SKIPPED_LIMIT");

    assertThat(preview.platformSplits()).hasSize(2);
    assertThat(preview.platformSplits())
        .filteredOn(s -> !s.excluded())
        .extracting(BulkEditPlatformSplit::platformCode)
        .containsExactly("AMAZON");
    BulkEditPlatformSplit excluded =
        preview.platformSplits().stream().filter(BulkEditPlatformSplit::excluded).findFirst().orElseThrow();
    assertThat(excluded.platformCode()).isEqualTo("TIKTOK_SHOP");
    assertThat(excluded.reason()).contains("模板限定平台 AMAZON");

    // 模板清单按平台可查
    assertThat(as(actorA, "channel.operation.read", () -> service.templates("AMAZON")))
        .extracting(BulkTemplateRow::code).contains("title-limit-10");
    assertThat(as(actorA, "channel.operation.read", () -> service.templates("TIKTOK_SHOP"))).isEmpty();
  }

  // ==================================================================
  // 5. 草稿生命周期
  // ==================================================================

  @Test
  @DisplayName("4.2.1.2 草稿：可废弃、可重来、线上版本前进会被标 stale、同一操作不可重复确认")
  void draftLifecycle() {
    UUID shop = seedShop(a, AMAZON, "DL-AMZ");
    var row = seedListing(actorA, shop, "DL-1", "Draft Lifecycle", "Home");

    var first = confirm(preview(new BulkEditRequest(
        List.of(row.id()), null, "TITLE_APPEND", appendParams(" A"), null, null, null, "")).operationId());
    // 同一操作确认两次 → 422（确认的一定是同一份预览结果）
    assertThat(catchThrowable(() -> confirm(first.operationId())))
        .hasMessageContaining("不能重复确认");

    // 废弃草稿：不删除，只是标记，历史仍可查
    var discarded = as(actorA, "channel.operation.update", () -> service.discardDraft(row.id().toString()));
    assertThat(discarded.state()).isEqualTo("DISCARDED");
    assertThat(as(actorA, "channel.operation.read", () -> service.drafts(0, 50, "DISCARDED"))).hasSize(1);

    // 🔴 废弃之后可以再来一次批量编辑，新开一份 DRAFT 草稿（不是静默失效）
    var second = confirm(preview(new BulkEditRequest(
        List.of(row.id()), null, "TITLE_APPEND", appendParams(" B"), null, null, null, "")).operationId());
    assertThat(second.appliedCount()).isEqualTo(1);
    ListingContentDraftRow active = as(actorA, "channel.operation.read", () -> service.draft(row.id().toString()));
    assertThat(active.title()).isEqualTo("Draft Lifecycle B");
    assertThat(active.state()).isEqualTo("DRAFT");

    // 线上版本前进 → 草稿被标 stale（发布前必须复核）
    execAsTenant(a, "update listing set title='别处改过的标题', version=version+1 where tenant_id='" + a
        + "' and id='" + row.id() + "'");
    ListingContentDraftRow stale = as(actorA, "channel.operation.read", () -> service.draft(row.id().toString()));
    assertThat(stale.stale()).as("线上版本已前进，草稿必须被标 stale").isTrue();
    assertThat(stale.liveTitle()).isEqualTo("别处改过的标题");
    assertThat(stale.title()).as("草稿里存的仍是批量拟改的值").isEqualTo("Draft Lifecycle B");
  }

  // ==================================================================
  // 6. 多租户三断言 + 跨租户批量选中自动切分
  // ==================================================================

  @Test
  @DisplayName("4.2.1.2 🔴 跨租户批量选中：自动切分，只改本租户那条，不可见的 id 逐个回显")
  void crossTenantSelectionIsSplit() {
    UUID shopA = seedShop(a, AMAZON, "CT-AMZ-A");
    UUID shopB = seedShop(b, AMAZON, "CT-AMZ-B");
    var rowA = seedListing(actorA, shopA, "CT-A", "A 的商品", "Home");
    var rowB = seedListing(actorB, shopB, "CT-B", "B 的商品", "Home");
    UUID missing = UUID.randomUUID();

    var preview = preview(new BulkEditRequest(
        List.of(rowA.id(), rowB.id(), missing), null, "TITLE_APPEND",
        appendParams(" X"), null, null, null, ""));

    // 🔴 自动切分：只对本租户可见的那条做批量
    assertThat(preview.changes()).extracting(ListingBulkChangeRow::externalItemId).containsExactly("CT-A");
    // 不可见的（跨租户的 + 不存在的）逐个回显，而不是静默丢弃。
    // 两者对调用方没有区别 —— 能区分「权限不够」与「不存在」本身就是信息泄露。
    assertThat(preview.outOfScopeIds()).containsExactlyInAnyOrder(rowB.id(), missing);

    confirm(preview.operationId());
    // B 的商品一个字都没被改，也没有草稿
    assertThat(as(actorB, "channel.operation.read", () -> listings.load(rowB.id().toString())).title())
        .isEqualTo("B 的商品");
    assertThat(as(actorB, "channel.operation.read", () -> service.drafts(0, 50, null))).isEmpty();
  }

  @Test
  @DisplayName("4.2.1.2 断言一：两租户互不可见（草稿与修改记录都隔离）")
  void tenantsAreIsolated() {
    UUID shopA = seedShop(a, AMAZON, "ISO1-AMZ-A");
    UUID shopB = seedShop(b, AMAZON, "ISO1-AMZ-B");
    var rowA = seedListing(actorA, shopA, "ISO1-A", "A 的商品", "Home");
    var rowB = seedListing(actorB, shopB, "ISO1-B", "B 的商品", "Home");

    var preview = preview(new BulkEditRequest(
        List.of(rowA.id()), null, "TITLE_APPEND", appendParams(" Y"), null, null, null, ""));
    confirm(preview.operationId());

    // B 看不到 A 的草稿，也读不到 A 的操作
    assertThat(as(actorB, "channel.operation.read", () -> service.drafts(0, 50, null))).isEmpty();
    assertThat(as(actorB, "channel.operation.read", () -> service.operations(0, 50))).isEmpty();
    var cross = catchThrowable(() -> as(actorB, "channel.operation.read",
        () -> service.load(preview.operationId().toString())));
    var missing = catchThrowable(() -> as(actorB, "channel.operation.read",
        () -> service.load(UUID.randomUUID().toString())));
    assertThat(cross).isNotNull();
    assertThat(missing).isNotNull();
    // 跨租户与不存在返回逐字相同的 404
    assertThat(cross.getMessage()).isEqualTo(missing.getMessage());

    // B 的草稿也不受 A 的批量影响
    assertThat(as(actorB, "channel.operation.read", () -> listings.load(rowB.id().toString())).title())
        .isEqualTo("B 的商品");
  }

  @Test
  @DisplayName("4.2.1.2 断言二：伪造 tenant header 无效（租户只来自已认证成员关系）")
  void forgedTenantHeaderIsIgnored() throws Exception {
    UUID shopA = seedShop(a, AMAZON, "FORGE-AMZ-A");
    UUID shopB = seedShop(b, AMAZON, "FORGE-AMZ-B");
    var rowA = seedListing(actorA, shopA, "FORGE-A", "A 的商品", "Home");
    var rowB = seedListing(actorB, shopB, "FORGE-B", "B 的商品", "Home");

    var preview = preview(new BulkEditRequest(
        List.of(rowA.id()), null, "TITLE_APPEND", appendParams(" Z"), null, null, null, ""));
    confirm(preview.operationId());

    // A 的会话 + B 的头 ⇒ 仍然只看得见 A 的数据
    http.perform(get("/channel/listing-bulk-edit/drafts")
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[*].externalItemId",
            org.hamcrest.Matchers.contains("FORGE-A")));

    // 伪造租户头也不能读到 B 的 listing 草稿
    http.perform(get("/channel/listing-bulk-edit/drafts/" + rowB.id())
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isNotFound());

    // 无会话一律 401
    http.perform(get("/channel/listing-bulk-edit/drafts").header("X-Tenant-Id", b.toString()))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("4.2.1.2 断言三：连接池归还后不串租户（池压到 1，A→B→A 三轮）")
  void pooledConnectionsDoNotBleedTenants() {
    UUID shopA = seedShop(a, AMAZON, "POOL-AMZ-A");
    UUID shopB = seedShop(b, AMAZON, "POOL-AMZ-B");
    var rowA = seedListing(actorA, shopA, "POOL-A", "A 的商品", "Home");
    var rowB = seedListing(actorB, shopB, "POOL-B", "B 的商品", "Home");

    // 🔴 set_config('app.draft_only', ..., true) 是事务局部的：连接归还池子后不带标记，
    //    所以这一轮确认之后，B 仍然能正常更新自己的线上 listing
    confirm(preview(new BulkEditRequest(
        List.of(rowA.id()), null, "TITLE_APPEND", appendParams(" P"), null, null, null, "")).operationId());

    for (int i = 0; i < 3; i++) {
      assertThat(as(actorA, "channel.operation.read", () -> service.drafts(0, 50, null)))
          .extracting(ListingContentDraftRow::externalItemId).containsExactly("POOL-A");
      assertThat(as(actorB, "channel.operation.read", () -> service.drafts(0, 50, null))).isEmpty();
      assertThat(as(actorA, "channel.operation.read", () -> service.operations(0, 50)))
          .extracting(ListingBulkEditOperation::appliedCount).containsOnly(1);

      // 标记没有泄漏：B 仍可正常更新线上（发布流程那类操作不被 draft_only 卡住）
      execAsTenant(b, "update listing set title='B 自己改的' where tenant_id='" + b
          + "' and id='" + rowB.id() + "'");
      assertThat(as(actorB, "channel.operation.read", () -> listings.load(rowB.id().toString())).title())
          .isEqualTo("B 自己改的");
    }
  }

  // ==================================================================
  // 7. 端点往返
  // ==================================================================

  @Test
  @DisplayName("4.2.1.2 端点：401 与真实业务往返；能力声明把未接入的基线与发布显式标出来")
  void endpointsWork() throws Exception {
    http.perform(get("/channel/listing-bulk-edit/capabilities")).andExpect(status().isUnauthorized());
    // 载荷合法但没有会话 ⇒ 401（载荷不合法会先被 @Valid 拦成 400，测不到鉴权）
    http.perform(post("/channel/listing-bulk-edit/preview")
            .contentType("application/json")
            .header("X-Tenant-Id", a.toString())
            .content(JSON.writeValueAsString(new BulkEditRequest(
                List.of(UUID.randomUUID()), null, "TITLE_APPEND", appendParams(" X"),
                null, null, null, ""))))
        .andExpect(status().isUnauthorized());

    http.perform(get("/channel/listing-bulk-edit/capabilities").header("Authorization", bearerA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.operationTypes", org.hamcrest.Matchers.hasItem("TITLE_REGEX_REPLACE")))
        .andExpect(jsonPath("$.draftIsolationNote",
            org.hamcrest.Matchers.containsString("绝不")))
        .andExpect(jsonPath("$.onlineBaselineNote",
            org.hamcrest.Matchers.containsString("4.2.1.1")));

    UUID shop = seedShop(a, AMAZON, "EP-AMZ");
    var row = seedListing(actorA, shop, "EP-1", "Endpoint Item", "Home");

    String body = JSON.writeValueAsString(
        new BulkEditRequest(List.of(row.id()), null, "TITLE_APPEND", appendParams(" !"), null, null, null, ""));
    String previewBody = http.perform(post("/channel/listing-bulk-edit/preview")
            .header("Authorization", bearerA)
            .contentType("application/json")
            .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("PREVIEWED"))
        .andExpect(jsonPath("$.changes[0].beforeValue").value("Endpoint Item"))
        .andExpect(jsonPath("$.changes[0].afterValue").value("Endpoint Item !"))
        .andReturn().getResponse().getContentAsString();

    UUID operationId = UUID.fromString(JSON.readTree(previewBody).get("operationId").asText());

    http.perform(post("/channel/listing-bulk-edit/confirm")
            .header("Authorization", bearerA)
            .contentType("application/json")
            .content(JSON.writeValueAsString(new BulkEditConfirmRequest(operationId, "确认"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("APPLIED"));

    http.perform(get("/channel/listing-bulk-edit/drafts").header("Authorization", bearerA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].title").value("Endpoint Item !"))
        .andExpect(jsonPath("$[0].liveTitle").value("Endpoint Item"));

    http.perform(get("/channel/listing-bulk-edit/operations/" + operationId)
            .header("Authorization", bearerA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.changes[0].field").value("TITLE"));

    http.perform(get("/channel/listing-bulk-edit/operations/not-a-uuid")
            .header("Authorization", bearerA))
        .andExpect(status().isNotFound());

    // 危险模式在预览入口就被拒（不是执行到一半才炸）
    // ⚠️ 这里刻意用 `(a*)*` 而不是 `(a+)+$`：query string 里的 `+` 会被解码成空格，
    //    `(a+)+$` 送进服务时已经变成 `(a ) $`，测到的不是我们想测的模式。
    http.perform(post("/channel/listing-bulk-edit/regex-check")
            .param("pattern", "(a*)*")
            .header("Authorization", bearerA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.safe").value(false));

    http.perform(get("/channel/listing-bulk-edit/scope-check")
            .param("listingIds", row.id().toString())
            .header("Authorization", bearerA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  @DisplayName("4.2.1.2 参数缺失与未知操作类型：显式 422，不静默跑出一条什么都没改的批量")
  void badRequestsAreRejected() {
    UUID shop = seedShop(a, AMAZON, "BAD-AMZ");
    var row = seedListing(actorA, shop, "BAD-1", "Bad Request Item", "Home");

    assertThat(catchThrowable(() -> preview(new BulkEditRequest(
        List.of(row.id()), null, "NOT_AN_OPERATION", appendParams(" X"), null, null, null, ""))))
        .hasMessageContaining("未知的批量操作类型");

    assertThat(catchThrowable(() -> preview(new BulkEditRequest(
        List.of(row.id()), null, "TITLE_APPEND", emptyParams(), null, null, null, ""))))
        .hasMessageContaining("必须给出 titleSuffix");

    assertThat(catchThrowable(() -> preview(new BulkEditRequest(
        List.of(row.id()), null, "REPLACE_IMAGES", imageParams(List.of()), null, null, null, ""))))
        .hasMessageContaining("不接受空列表");

    assertThat(catchThrowable(() -> preview(new BulkEditRequest(
        List.of(row.id()), null, "REPLACE_CATEGORY", categoryParams(null, null), null, null, null, ""))))
        .hasMessageContaining("至少给出");

    // 全部不可见 → 整体拒绝，而不是「成功但什么都没改」
    assertThat(catchThrowable(() -> preview(new BulkEditRequest(
        List.of(UUID.randomUUID()), null, "TITLE_APPEND", appendParams(" X"), null, null, null, ""))))
        .hasMessageContaining("没有一条在当前租户的数据范围内");
  }
}
