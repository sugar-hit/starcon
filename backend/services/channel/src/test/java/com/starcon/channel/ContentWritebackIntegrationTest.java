package com.starcon.channel;

import static com.starcon.channel.ContentWritebackModels.*;
import static com.starcon.channel.ListingModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
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
 * 4.3.2.1 内容回写。
 *
 * <p>蓝图：「把内部已发布的商品内容变更推送到平台（改标题、改图、改描述、改 A+），支持按变更类型触发或批量触发，并记录回写前后快照。输出：回写任务（经 02 队列）+ 变更历史。<b>内容回写不触碰价格与库存</b>（那是 05/07 的事），三条回写通道独立失败独立重试。」
 *
 * <p>本测试类钉住这几组事：
 *
 * <ol>
 *   <li><b>🔴🔴 内容通道装不下价格与库存</b>：含 PRICE / STOCK / QUANTITY / CATEGORY 的请求被
 *       逐个点名 422（不是静默忽略）；DB 的 CHECK 也写不进这两个词；全库无任何金额列。
 *   <li><b>🔴 三条通道独立失败独立重试</b>：内容重试只打回自己的 FAILED；已成功的打不回去
 *       （触发器 23514）；重试**复用同一个 02 队列任务**（幂等键不变，平台侧不出现第二个任务）；
 *       且内容通道的在途数**不占用**批量发布的额度（各记各的账）。
 *   <li><b>🔴 改前改后两侧来源都是真的</b>：改前来���平台侧当前快照（{@code PLATFORM_SNAPSHOT}），
 *       改后来自内部草稿（{@code INTERNAL_DRAFT}）或显式覆盖值（{@code OVERRIDE}）；
 *       没有平台侧快照时改前记 {@code MISSING} + 空串，**不拿改后值冒充改前值**。
 *   <li><b>🔴 改后与线上逐字相同 ⇒ 不派发</b>（{@code SKIPPED_NO_CHANGE}），而不是报成功。
 *   <li><b>🔴 经 02 队列</b>：派发出去的每条都有 {@code platform_operation} 行，
 *       在 2.5.1.1 的口径里能按 {@code operationId} 查到；跳过的那条一定没有队列行。
 *   <li><b>多租户三断言</b>（AGENTS.md 硬性要求，缺一不可）：两租户互不可见、伪造 tenant header
 *       无效、连接池归还后不串租户。
 *   <li><b>timestamptz 往返</b>：{@code dispatchedAt} / {@code completedAt} 写回值逐字相等。
 * </ol>
 *
 * <p><b>时刻一律用固定的 {@link java.time.OffsetDateTime} 钉死</b>，不在断言中途改时刻。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ContentWritebackIntegrationTest {

  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID user = UUID.randomUUID();
  static HttpServer identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static final ObjectMapper JSON = new ObjectMapper();

  UUID a, b;
  AccessService.Access actorA, actorB;
  String bearerA;

  // 🔴 直接用 V1 已播种的平台码表，不自己造平台（platform.code UNIQUE，自造会撞码）
  static final UUID AMAZON = UUID.fromString("11111111-1111-1111-1111-111111111101");
  static final UUID TIKTOK = UUID.fromString("11111111-1111-1111-1111-111111111104");

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
  @Autowired ContentWritebackService service;
  @Autowired PublishService publish;
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

  static final String ALL_SCOPE_GRANTS =
      "[{\"permissions\":[\"channel.operation.read\",\"channel.operation.create\","
          + "\"channel.operation.update\",\"channel.operation.delete\"],\"scopeMode\":\"ALL\"}]";

  static void execAsTenant(UUID tenant, String sql) {
    try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute("select set_config('app.current_tenant','" + tenant + "',false)");
      s.execute("select set_config('app.actor','" + user + "',false)");
      s.execute("select set_config('app.department','',false)");
      s.execute("select set_config('app.permission','channel.operation.update',false)");
      s.execute("select set_config('app.grants','" + ALL_SCOPE_GRANTS + "',false)");
      s.execute(sql);
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  static Object execScalarAsTenant(UUID tenant, String sql) {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute("select set_config('app.current_tenant','" + tenant + "',false)");
      s.execute("select set_config('app.actor','" + user + "',false)");
      s.execute("select set_config('app.department','',false)");
      s.execute("select set_config('app.permission','channel.operation.update',false)");
      s.execute("select set_config('app.grants','" + ALL_SCOPE_GRANTS + "',false)");
      var rs = s.executeQuery(sql);
      return rs.next() ? rs.getObject(1) : null;
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * 🔴 以<b>应用角色 channel_app</b>（非超级用户）直查一行计数。
   *
   * <p>必须用 channel_app 连：Testcontainers 默认的 {@code pg.getUsername()} 是超级用户，而超级用户
   * <b>绕过 RLS</b> —— 用它查「读不到对方数据」会永远得到「读得到」。那不是隔离被破坏，
   * 而是这个断言本身失效了（同 4.1.1.2 实测踩过）。
   */
  static long countAsTenant(UUID tenant, String sql) {
    try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), "channel_app", "test-only");
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

  /** 平台侧当前内容快照（V175）—— 改前的真实来源。 */
  UUID seedSnapshot(UUID tenant, UUID listingId, String language, String title, String description) {
    UUID id = UUID.randomUUID();
    execAsTenant(tenant,
        "insert into listing_content_snapshot"
            + " (id,tenant_id,listing_id,language,title,bullet_points,description,images,a_plus_modules,"
            + "  category_attributes,compliance_statements,source,is_current,captured_at,created_at,updated_at)"
            + " values ('" + id + "','" + tenant + "','" + listingId + "','" + language + "','"
            + title.replace("'", "''") + "','[\"平台卖点一\"]'::jsonb,'" + description.replace("'", "''")
            + "','[\"https://img/old.jpg\"]'::jsonb,'[]'::jsonb,'{}'::jsonb,'{}'::jsonb,"
            + "'SYNC',true,now(),now(),now())");
    return id;
  }

  /**
   * 内部待发布内容草稿（V177）—— 改后的真实来源。
   *
   * <p>🔴 {@code touched_fields} 与哪些列有值被 CHECK 钉成一一对应，所以传了卖点就必须真写进
   * {@code bullets}（4.2.1.2 的纪律：没碰过的字段保持 NULL，发布时才不会把线上新值覆盖回去）。
   */
  void seedDraft(UUID tenant, UUID listingId, String title, String... bullets) {
    StringBuilder bulletArray = new StringBuilder();
    for (int i = 0; i < bullets.length; i++) {
      if (i > 0) {
        bulletArray.append(',');
      }
      bulletArray.append('\'').append(bullets[i].replace("'", "''")).append('\'');
    }
    execAsTenant(tenant,
        "insert into listing_content_draft"
            + " (id,tenant_id,listing_id,title,bullets,touched_fields,state,base_version,"
            + "  base_listing_updated_at,last_operation_id,created_at,updated_at)"
            + " values (gen_random_uuid(),'" + tenant + "','" + listingId + "','"
            + title.replace("'", "''") + "',ARRAY[" + bulletArray + "]::text[],"
            + "array['TITLE','BULLETS']::text[],'DRAFT',0,now(),gen_random_uuid(),now(),now())");
  }

  ContentWritebackRow create(
      AccessService.Access who, CreateContentWritebackRequest request) {
    return as(who, "channel.operation.create", () -> service.create(request, user));
  }

  ContentWritebackDispatchResponse dispatch(AccessService.Access who, UUID writebackId) {
    return as(who, "channel.operation.update", () -> service.dispatch(writebackId.toString()));
  }

  void rateLimit(AccessService.Access who, UUID platformId, int maxInFlight) {
    as(who, "channel.operation.update",
        () -> publish.saveRateLimit(new PublishModels.PublishRateLimitRequest(
            platformId, null, maxInFlight, 60, 60, true)));
  }

  static CreateContentWritebackRequest req(List<String> types, List<UUID> listingIds) {
    return new CreateContentWritebackRequest(types, listingIds, null, null, null, null, 50, null);
  }

  // ==================================================================
  // 1. 🔴🔴 内容通道装不下价格与库存
  // ==================================================================

  @Test
  @DisplayName("4.3.2.1 🔴🔴 含 PRICE / STOCK 的请求被逐个点名 422（不是静默忽略）")
  void priceAndStockAreRejectedByName() {
    UUID shop = seedShop(a, AMAZON, "CB-PS");
    var l1 = seedListing(actorA, shop, "PS-A1", "价格商品", "Home");
    seedSnapshot(a, l1.id(), "en-US", "旧标题", "旧描述");

    var boom = catchThrowable(() -> create(actorA,
        new CreateContentWritebackRequest(
            List.of("TITLE", "PRICE", "STOCK"), List.of(l1.id()), null, null, null, null, 50, null)));
    assertThat(boom).isNotNull();
    // 🔴 逐个点名：静默忽略会让用户以为价格也推上去了，而它根本没有
    assertThat(boom.getMessage()).contains("PRICE").contains("STOCK");
    assertThat(boom.getMessage()).contains("05 定价").contains("07 库存");
    // 一条回写任务也没建（拒绝发生在建任何任务之前）
    Long tasks = (Long) execScalarAsTenant(a,
        "select count(*) from listing_content_writeback_task where listing_id='" + l1.id() + "'");
    assertThat(tasks).isZero();
  }

  @Test
  @DisplayName("4.3.2.1 🔴🔴 覆盖值里塞 price 也被点名拒绝（同一道闸的两条路径）")
  void priceInOverridesIsRejected() {
    UUID shop = seedShop(a, AMAZON, "CB-OV");
    var l1 = seedListing(actorA, shop, "OV-A1", "覆盖值商品", "Home");
    seedSnapshot(a, l1.id(), "en-US", "旧标题", "旧描述");

    var boom = catchThrowable(() -> create(actorA,
        new CreateContentWritebackRequest(
            List.of("TITLE"), List.of(l1.id()), null, null,
            Map.of("TITLE", "新标题", "PRICE", "99.99"), null, 50, null)));
    assertThat(boom).isNotNull();
    assertThat(boom.getMessage()).contains("PRICE");
  }

  @Test
  @DisplayName("4.3.2.1 🔴🔴 DB 的 CHECK 也写不进价格与库存（不是只靠服务层记得）")
  void dbCheckRejectsPriceAndStock() {
    UUID shop = seedShop(a, AMAZON, "CB-DB");
    var l1 = seedListing(actorA, shop, "DB-A1", "DB 闸商品", "Home");

    // change_types 里塞 PRICE ⇒ CHECK 直接拒
    assertThatThrownBy(() -> execAsTenant(a,
        "insert into listing_content_writeback"
            + " (id,tenant_id,trigger_kind,change_types,listing_ids,overrides,language,state,"
            + "  total_count,pending_count,skipped_count,dispatched_count,succeeded_count,failed_count,"
            + "  actor_id,note,created_at,updated_at)"
            + " values (gen_random_uuid(),'" + a + "','SINGLE',ARRAY['TITLE','PRICE']::text[],"
            + "  ARRAY['" + l1.id() + "']::uuid[],'{}'::jsonb,'','RUNNING',0,0,0,0,0,0,"
            + "  '" + user + "','',now(),now())"))
        .hasMessageContaining("check constraint");

    // 变更行的 field 里塞 STOCK ⇒ 同样被拒
    var wb = create(actorA, req(List.of("TITLE"), List.of(l1.id())));
    seedSnapshot(a, l1.id(), "en-US", "旧", "旧");
    var task = as(actorA, "channel.operation.read", () -> service.tasks(wb.writebackId().toString(), null, 0, 50)).get(0);
    assertThatThrownBy(() -> execAsTenant(a,
        "insert into listing_content_writeback_change"
            + " (id,tenant_id,task_id,listing_id,field,before_value,after_value,changed,outcome,"
            + "  before_source,after_source,reason,created_at)"
            + " values (gen_random_uuid(),'" + a + "','" + task.taskId() + "','" + l1.id()
            + "','STOCK','1','5',true,'APPLIED','PLATFORM_SNAPSHOT','OVERRIDE','',now())"))
        .hasMessageContaining("check constraint");

    // 🔴 且本模块的表里**无任何金额列**（改价归 05 定价）
    for (String table : List.of("listing_content_writeback", "listing_content_writeback_task",
        "listing_content_writeback_change")) {
      Long moneyCols = (Long) execScalarAsTenant(a,
          "select count(*) from information_schema.columns where table_name='" + table
              + "' and data_type in ('numeric','double precision','real','money')");
      assertThat(moneyCols).as("表 %s 不该有任何金额/浮点列", table).isZero();
    }
  }

  @Test
  @DisplayName("4.3.2.1 🔴 三条通道独立：内容回写的在途不占用批量发布的额度（各记各的账）")
  void contentChannelDoesNotConsumePublishQuota() {
    UUID shop = seedShop(a, AMAZON, "CB-CH");
    seedListing(actorA, shop, "CH-A1", "内容商品一", "Home");
    seedListing(actorA, shop, "CH-A2", "内容商品二", "Home");
    // Amazon 的平台级额度只有 1（**两条通道共用同一份真实配额配置**）
    rateLimit(actorA, AMAZON, 1);

    // 先占掉批量发布的在途额度：建一个发布批次并派一条
    var batch = as(actorA, "channel.operation.create",
        () -> publish.createBatch(
            new PublishModels.CreatePublishBatchRequest(
                null, null, null, null, null, null, null, null, 50), user));
    var publishDispatch = as(actorA, "channel.operation.update",
        () -> publish.dispatch(batch.batchId().toString()));
    assertThat(publishDispatch.totalDispatched()).isEqualTo(1);

    // 🔴 内容回写此刻**仍能**派发一条：它数的是自己的任务表，不看批量发布占了几个。
    //    目标显式给这两条（否则前两条没草稿的会一并进来、整批都判 SKIPPED_NO_CHANGE）。
    List<UUID> targets = new java.util.ArrayList<>();
    for (String ext : List.of("CH-A1", "CH-A2")) {
      var row = seedListing(actorA, shop, ext + "-w", "内容 " + ext, "Home");
      seedSnapshot(a, row.id(), "en-US", "旧标题 " + ext, "旧描述");
      seedDraft(a, row.id(), "新标题 " + ext, "新卖点");
      targets.add(row.id());
    }
    var wb = create(actorA,
        new CreateContentWritebackRequest(
            List.of("TITLE"), targets, null, null, null, null, 50, "按批量触发"));
    assertThat(wb.totalCount()).isEqualTo(2);
    var d = dispatch(actorA, wb.writebackId());
    // 内容通道按自己那份「在途 0」算额度 ⇒ 真的派出去了（若共用计数，这里会是 0）
    assertThat(d.totalDispatched()).isEqualTo(1); // Amazon 额度 1 ⇒ 只派一条
    assertThat(d.totalSkippedByRateLimit()).isEqualTo(1);
  }

  // ==================================================================
  // 2. 🔴 改前改后快照：两侧来源都是真的
  // ==================================================================

  @Test
  @DisplayName("4.3.2.1 🔴 改前来��平台侧快照、改后来自内部草稿，两侧来源逐行记下")
  void beforeAndAfterComeFromRealSources() {
    UUID shop = seedShop(a, AMAZON, "CB-SRC");
    var l1 = seedListing(actorA, shop, "SRC-A1", "线上标题", "Home");
    seedSnapshot(a, l1.id(), "en-US", "平台侧旧标题", "平台侧旧描述");
    seedDraft(a, l1.id(), "内部新标题", "内部新卖点一");

    var wb = create(actorA, req(List.of("TITLE", "BULLETS"), List.of(l1.id())));
    var task = as(actorA, "channel.operation.read",
        () -> service.tasks(wb.writebackId().toString(), null, 0, 50)).get(0);
    // 改前基线真的来自平台侧当前快照
    assertThat(task.snapshotId()).isNotNull();
    assertThat(task.draftId()).isNotNull();

    var changes = as(actorA, "channel.operation.read", () -> service.changes(task.taskId().toString()));
    var title = changes.stream().filter(c -> "TITLE".equals(c.field())).findFirst().orElseThrow();
    assertThat(title.beforeValue()).isEqualTo("平台侧旧标题");
    assertThat(title.beforeSource()).isEqualTo("PLATFORM_SNAPSHOT");
    assertThat(title.afterValue()).isEqualTo("内部新标题");
    assertThat(title.afterSource()).isEqualTo("INTERNAL_DRAFT");
    assertThat(title.outcome()).isEqualTo("APPLIED");
    assertThat(title.changed()).isTrue();

    var bullets = changes.stream().filter(c -> "BULLETS".equals(c.field())).findFirst().orElseThrow();
    assertThat(bullets.beforeValue()).isEqualTo("[\"平台卖点一\"]");
    assertThat(bullets.afterValue()).isEqualTo("[\"内部新卖点一\"]");
    // 🔴 数组型内容两侧是**同一形态**（否则内容完全相同也会被判成"有变更"）
    assertThat(bullets.outcome()).isEqualTo("APPLIED");
  }

  @Test
  @DisplayName("4.3.2.1 🔴 没有平台侧快照时改前记 MISSING，不拿改后值冒充改前值")
  void missingBaselineIsHonest() {
    UUID shop = seedShop(a, AMAZON, "CB-NB");
    var l1 = seedListing(actorA, shop, "NB-A1", "无基线商品", "Home");
    seedDraft(a, l1.id(), "内部标题", "内部卖点");

    var wb = create(actorA, req(List.of("TITLE"), List.of(l1.id())));
    var task = as(actorA, "channel.operation.read",
        () -> service.tasks(wb.writebackId().toString(), null, 0, 50)).get(0);
    assertThat(task.snapshotId()).isNull();

    var change = as(actorA, "channel.operation.read", () -> service.changes(task.taskId().toString())).get(0);
    // 🔴 改前是空串 + MISSING；**不是**把改后值复制过去假装那是线上现状
    assertThat(change.beforeValue()).isEmpty();
    assertThat(change.beforeSource()).isEqualTo("MISSING");
    assertThat(change.afterValue()).isEqualTo("内部标题");
    assertThat(change.reason()).contains("改前值不可知");
  }

  @Test
  @DisplayName("4.3.2.1 🔴 内部没有该字段的待推送内容 ⇒ SKIPPED_NO_SOURCE（不编假值）")
  void descriptionWithoutSourceIsSkippedNotFaked() {
    UUID shop = seedShop(a, AMAZON, "CB-DESC");
    var l1 = seedListing(actorA, shop, "DESC-A1", "描述商品", "Home");
    seedSnapshot(a, l1.id(), "en-US", "旧标题", "平台侧旧描述");
    seedDraft(a, l1.id(), "内部新标题", "内部卖点");

    // V177 的草稿**没有** description 列（4.2.1.1 未交付）⇒ 无来源
    var wb = create(actorA, req(List.of("DESCRIPTION"), List.of(l1.id())));
    var task = as(actorA, "channel.operation.read",
        () -> service.tasks(wb.writebackId().toString(), null, 0, 50)).get(0);
    assertThat(task.state()).isEqualTo("SKIPPED_NO_CHANGE");

    var change = as(actorA, "channel.operation.read", () -> service.changes(task.taskId().toString())).get(0);
    assertThat(change.outcome()).isEqualTo("SKIPPED_NO_SOURCE");
    assertThat(change.afterValue()).isEmpty();
    assertThat(change.afterSource()).isEqualTo("MISSING");
    assertThat(change.reason()).contains("4.2.1.1");

    // 给了显式覆盖值就真的能回写（这才是 DESCRIPTION / A+ 的唯一真实来源）
    var wb2 = create(actorA, new CreateContentWritebackRequest(
        List.of("DESCRIPTION"), List.of(l1.id()), null, null,
        Map.of("DESCRIPTION", "内部新描述"), null, 50, null));
    var task2 = as(actorA, "channel.operation.read",
        () -> service.tasks(wb2.writebackId().toString(), null, 0, 50)).get(0);
    assertThat(task2.state()).isEqualTo("PENDING");
    var change2 = as(actorA, "channel.operation.read", () -> service.changes(task2.taskId().toString())).get(0);
    assertThat(change2.beforeValue()).isEqualTo("平台侧旧描述");
    assertThat(change2.afterValue()).isEqualTo("内部新描述");
    assertThat(change2.afterSource()).isEqualTo("OVERRIDE");
  }

  // ==================================================================
  // 3. 🔴 改后与线上相同 ⇒ 不派发（不谎报成功）
  // ==================================================================

  @Test
  @DisplayName("4.3.2.1 🔴 改后与线上逐字相同 ⇒ SKIPPED_NO_CHANGE，且永不派发、绝不报成功")
  void noChangeIsNeverDispatched() {
    UUID shop = seedShop(a, AMAZON, "CB-NC");
    var l1 = seedListing(actorA, shop, "NC-A1", "相同标题", "Home");
    seedSnapshot(a, l1.id(), "en-US", "一模一样的标题", "旧描述");
    seedDraft(a, l1.id(), "一模一样的标题", "内部卖点"); // 标题相同，卖点不同

    // 只回写标题 ⇒ 与线上逐字相同
    var wb = create(actorA, req(List.of("TITLE"), List.of(l1.id())));
    var task = as(actorA, "channel.operation.read",
        () -> service.tasks(wb.writebackId().toString(), null, 0, 50)).get(0);
    assertThat(task.state()).isEqualTo("SKIPPED_NO_CHANGE");
    assertThat(task.failureReason()).contains("逐字相同");
    var change = as(actorA, "channel.operation.read", () -> service.changes(task.taskId().toString())).get(0);
    assertThat(change.outcome()).isEqualTo("NO_CHANGE");
    assertThat(change.changed()).isFalse();

    rateLimit(actorA, AMAZON, 5);
    var d = dispatch(actorA, wb.writebackId());
    // 🔴 一条都不派（对平台的写调用是真实的外部副作用）
    assertThat(d.totalDispatched()).isZero();
    assertThat(d.queuedOperationIds()).isEmpty();
    // 且进度是 100%（SKIPPED 是终态），不是停在 0% 让运营以为还有事没做完
    assertThat(wb.progressPercent()).isEqualTo(100);
    assertThat(wb.skippedCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("4.3.2.1 🔴 跳过的那条一定没有 02 队列行（CHECK 钉死「经队列」的反面）")
  void skippedTaskHasNoQueueRow() {
    UUID shop = seedShop(a, AMAZON, "CB-SK");
    var l1 = seedListing(actorA, shop, "SK-A1", "跳过商品", "Home");
    seedSnapshot(a, l1.id(), "en-US", "相同", "旧");
    seedDraft(a, l1.id(), "相同", "卖点");
    var wb = create(actorA, req(List.of("TITLE"), List.of(l1.id())));
    var task = as(actorA, "channel.operation.read",
        () -> service.tasks(wb.writebackId().toString(), null, 0, 50)).get(0);
    assertThat(task.operationId()).isNull();

    // DB 层：给一条 SKIPPED_NO_CHANGE 的任务硬塞队列行 ⇒ CHECK 直接拒
    assertThatThrownBy(() -> execAsTenant(a,
        "update listing_content_writeback_task set operation_id=gen_random_uuid() where id='"
            + task.taskId() + "'"))
        .hasMessageContaining("check constraint");
  }

  // ==================================================================
  // 4. 🔴 经 02 队列派发 + 限流
  // ==================================================================

  @Test
  @DisplayName("4.3.2.1 🔴 派发真的写进 02 队列（operationId 可查，平台侧不该出现假任务）")
  void dispatchWritesToThe02Queue() {
    UUID shop = seedShop(a, AMAZON, "CB-Q");
    var l1 = seedListing(actorA, shop, "Q-A1", "队列商品", "Home");
    seedSnapshot(a, l1.id(), "en-US", "旧标题", "旧描述");
    seedDraft(a, l1.id(), "新标题", "新卖点");
    rateLimit(actorA, AMAZON, 5);

    var wb = create(actorA, req(List.of("TITLE"), List.of(l1.id())));
    var d = dispatch(actorA, wb.writebackId());
    assertThat(d.totalDispatched()).isEqualTo(1);
    assertThat(d.queuedOperationIds()).hasSize(1);

    UUID operationId = d.queuedOperationIds().get(0);
    var task = as(actorA, "channel.operation.read",
        () -> service.tasks(wb.writebackId().toString(), "DISPATCHED", 0, 50)).get(0);
    assertThat(task.operationId()).isEqualTo(operationId);
    assertThat(task.attempts()).isEqualTo(1);
    // 🔴 2.5.1.1 的口径里能查到这条队列任务，且 payload 明确标出这是内容回写
    assertThat(execScalarAsTenant(a,
        "select count(*) from platform_operation where id='" + operationId
            + "' and shop_id='" + shop + "' and operation='PUBLISH' and payload->>'contentWriteback'='true'"))
        .isEqualTo(1L);

    // 回报成功 ⇒ 写一条 listing_publish_record(action='CONTENT_WRITEBACK')（V175 预留的取值）
    as(actorA, "channel.operation.update", () -> service.report(task.taskId().toString(), true, null));
    assertThat(execScalarAsTenant(a,
        "select count(*) from listing_publish_record where listing_id='" + l1.id()
            + "' and action='CONTENT_WRITEBACK' and outcome='SUCCEEDED'"))
        .isEqualTo(1L);
    // 进度：终态 1/1
    assertThat(wb.state()).isNotEqualTo("COMPLETED"); // 之前那次读的是派发前的快照
    var done = as(actorA, "channel.operation.read", () -> service.loadWritebackRow(wb.writebackId().toString()));
    assertThat(done.state()).isEqualTo("COMPLETED");
    assertThat(done.progressPercent()).isEqualTo(100);
  }

  @Test
  @DisplayName("4.3.2.1 🔴 未配置限流的平台 fail-closed：一条都不派")
  void missingRateLimitBlocksDispatch() {
    UUID shop = seedShop(a, AMAZON, "CB-FC");
    var l1 = seedListing(actorA, shop, "FC-A1", "未配限流的商品", "Home");
    seedSnapshot(a, l1.id(), "en-US", "旧标题", "旧");
    seedDraft(a, l1.id(), "新标题", "新卖点");

    var wb = create(actorA, req(List.of("TITLE"), List.of(l1.id())));
    var d = dispatch(actorA, wb.writebackId());
    assertThat(d.totalDispatched()).isZero();
    assertThat(d.totalSkippedByRateLimit()).isEqualTo(1);
    assertThat(d.lanes().get(0).rateLimitId()).isNull();
    assertThat(d.lanes().get(0).note()).contains("未配置限流");
    var tasks = as(actorA, "channel.operation.read", () -> service.tasks(wb.writebackId().toString(), null, 0, 50));
    assertThat(tasks).allSatisfy(t -> assertThat(t.state()).isEqualTo("PENDING"));
  }

  // ==================================================================
  // 5. 🔴 独立失败独立重试
  // ==================================================================

  @Test
  @DisplayName("4.3.2.1 🔴 重试只打回 FAILED；已成功的打不回去（触发器 23514）")
  void retryOnlyResetsFailedAndNeverSucceeded() {
    UUID shop = seedShop(a, AMAZON, "CB-RT");
    var l1 = seedListing(actorA, shop, "RT-A1", "重试一", "Home");
    var l2 = seedListing(actorA, shop, "RT-A2", "重试二", "Home");
    for (var l : List.of(l1, l2)) {
      seedSnapshot(a, l.id(), "en-US", "旧标题", "旧");
      seedDraft(a, l.id(), "新标题", "新卖点");
    }
    rateLimit(actorA, AMAZON, 5);

    var wb = create(actorA, req(List.of("TITLE"), List.of(l1.id(), l2.id())));
    dispatch(actorA, wb.writebackId());
    var tasks = as(actorA, "channel.operation.read",
        () -> service.tasks(wb.writebackId().toString(), null, 0, 50));
    UUID okId = tasks.get(0).taskId();
    UUID badId = tasks.get(1).taskId();
    as(actorA, "channel.operation.update", () -> service.report(okId.toString(), true, null));
    as(actorA, "channel.operation.update",
        () -> service.report(badId.toString(), false, "平台类目必填属性缺失"));

    var result = as(actorA, "channel.operation.update",
        () -> service.retry(wb.writebackId().toString(), new RetryContentWritebackRequest(null, "重试失败项")));
    assertThat(result.changedTaskIds()).containsExactly(badId);
    assertThat(result.rejected()).isEmpty();

    // 🔴 已成功的那条**仍在 SUCCEEDED**，并被点名拒绝（不给就只打回 FAILED）
    var retrySucceeded = as(actorA, "channel.operation.update",
        () -> service.retry(wb.writebackId().toString(),
            new RetryContentWritebackRequest(List.of(okId, badId), null)));
    // badId 上一轮已经被打回 PENDING ⇒ 这一轮它也不再是 FAILED，两条都被逐条点名拒绝
    assertThat(retrySucceeded.rejected()).hasSize(2);
    assertThat(retrySucceeded.rejected())
        .anySatisfy(r -> assertThat(r).contains(okId.toString()).contains("SUCCEEDED"));
    assertThat(retrySucceeded.changedTaskIds()).isEmpty();

    var after = as(actorA, "channel.operation.read",
        () -> service.tasks(wb.writebackId().toString(), null, 0, 50));
    assertThat(after).filteredOn(t -> t.taskId().equals(okId)).allSatisfy(t ->
        assertThat(t.state()).isEqualTo("SUCCEEDED"));

    // 🔴 DB 层直接改也改不动（不是"服务层没写那条 SQL"，是写不了）
    assertThatThrownBy(() -> execAsTenant(a,
        "update listing_content_writeback_task set state='PENDING' where id='" + okId + "'"))
        .hasMessageContaining("already SUCCEEDED");
  }

  @Test
  @DisplayName("4.3.2.1 🔴 重试复用同一个 02 队列任务（幂等键不变，平台侧不出现第二个任务）")
  void retryReusesTheSameQueueTask() {
    UUID shop = seedShop(a, AMAZON, "CB-ID");
    var l1 = seedListing(actorA, shop, "ID-A1", "幂等商品", "Home");
    seedSnapshot(a, l1.id(), "en-US", "旧标题", "旧");
    seedDraft(a, l1.id(), "新标题", "新卖点");
    rateLimit(actorA, AMAZON, 5);

    var wb = create(actorA, req(List.of("TITLE"), List.of(l1.id())));
    var d1 = dispatch(actorA, wb.writebackId());
    UUID firstOperation = d1.queuedOperationIds().get(0);
    String firstKey = (String) execScalarAsTenant(a,
        "select idempotency_key from platform_operation where id='" + firstOperation + "'");

    var taskId = as(actorA, "channel.operation.read",
        () -> service.tasks(wb.writebackId().toString(), null, 0, 50)).get(0).taskId();
    as(actorA, "channel.operation.update",
        () -> service.report(taskId.toString(), false, "平台限流，请稍后重试"));
    as(actorA, "channel.operation.update",
        () -> service.retry(wb.writebackId().toString(), new RetryContentWritebackRequest(null, null)));
    var d2 = dispatch(actorA, wb.writebackId());

    // 🔴 第二次派发复用同一个队列任务、幂等键不变 ⇒ 平台侧不会看到两次写
    assertThat(d2.queuedOperationIds()).containsExactly(firstOperation);
    assertThat(execScalarAsTenant(a,
        "select idempotency_key from platform_operation where id='" + firstOperation + "'"))
        .isEqualTo(firstKey);
    Long queueRows = (Long) execScalarAsTenant(a,
        "select count(*) from platform_operation where shop_id='" + shop + "' and idempotency_key='" + firstKey + "'");
    assertThat(queueRows).isEqualTo(1L);
    // attempts 累加（"重试了几次"可查）
    assertThat(as(actorA, "channel.operation.read",
        () -> service.tasks(wb.writebackId().toString(), "DISPATCHED", 0, 50)).get(0).attempts())
        .isEqualTo(2);
  }

  @Test
  @DisplayName("4.3.2.1 🔴 内容重试碰不到 02 队列里别的通道（价格 / 库存任务原样不动）")
  void contentRetryTouchesNoOtherChannel() {
    UUID shop = seedShop(a, AMAZON, "CB-OTH");
    var l1 = seedListing(actorA, shop, "OTH-A1", "别的通道", "Home");
    seedSnapshot(a, l1.id(), "en-US", "旧标题", "旧");
    seedDraft(a, l1.id(), "新标题", "新卖点");
    rateLimit(actorA, AMAZON, 5);

    // 队列里先放一条**别的通道**的失败任务（价格回写，05 定价的）
    UUID priceOperation = UUID.randomUUID();
    execAsTenant(a,
        "insert into platform_operation (id,tenant_id,shop_id,marketplace_id,operation,"
            + " idempotency_key,payload,status,platform_request_id,verification,verified_at,attempts,"
            + " last_error,occurred_at,updated_at)"
            + " values ('" + priceOperation + "','" + a + "','" + shop + "',null,'UPDATE_PRICE',"
            + " 'PRICE-OTHER','{}'::jsonb,'FAILED','','NOT_REQUIRED',null,1,'价格通道失败',now(),now())");

    var wb = create(actorA, req(List.of("TITLE"), List.of(l1.id())));
    dispatch(actorA, wb.writebackId());
    var taskId = as(actorA, "channel.operation.read",
        () -> service.tasks(wb.writebackId().toString(), null, 0, 50)).get(0).taskId();
    as(actorA, "channel.operation.update", () -> service.report(taskId.toString(), false, "内容通道失败"));
    as(actorA, "channel.operation.update",
        () -> service.retry(wb.writebackId().toString(), new RetryContentWritebackRequest(null, null)));

    // 🔴 价格通道那条**原样不动**：既没被重试，也没被内容通道改状态
    assertThat(execScalarAsTenant(a,
        "select status from platform_operation where id='" + priceOperation + "'"))
        .isEqualTo("FAILED");
  }

  // ==================================================================
  // 6. 变更历史
  // ==================================================================

  @Test
  @DisplayName("4.3.2.1 🔴 变更历史：任务维度与商品维度两种取法，且不可就地改写")
  void changeHistoryIsReadableAndImmutable() {
    UUID shop = seedShop(a, AMAZON, "CB-H");
    var l1 = seedListing(actorA, shop, "H-A1", "历史商品", "Home");
    seedSnapshot(a, l1.id(), "en-US", "第一版标题", "第一版描述");
    seedDraft(a, l1.id(), "第二版标题", "第二版卖点");
    rateLimit(actorA, AMAZON, 5);

    var wb = create(actorA, req(List.of("TITLE", "BULLETS"), List.of(l1.id())));
    dispatch(actorA, wb.writebackId());
    var task = as(actorA, "channel.operation.read",
        () -> service.tasks(wb.writebackId().toString(), null, 0, 50)).get(0);
    as(actorA, "channel.operation.update", () -> service.report(task.taskId().toString(), true, null));

    // 商品维度：一次触发带它全部的字段变更
    var history = as(actorA, "channel.operation.read", () -> service.listingHistory(l1.id().toString()));
    assertThat(history).hasSize(1);
    assertThat(history.get(0).writebackId()).isEqualTo(wb.writebackId());
    assertThat(history.get(0).taskState()).isEqualTo("SUCCEEDED");
    assertThat(history.get(0).changes()).hasSize(2);
    assertThat(history.get(0).changes())
        .allSatisfy(c -> assertThat(c.beforeValue()).isNotEmpty());

    // 🔴 历史不可就地改写：只能追加新的一次触发来更正
    assertThatThrownBy(() -> execAsTenant(a,
        "update listing_content_writeback_change set after_value='偷偷改掉' where task_id='" + task.taskId() + "'"))
        .hasMessageContaining("append-only");
  }

  // ==================================================================
  // 7. 多租户三断言（AGENTS.md 硬性要求，缺一不可）
  // ==================================================================

  @Test
  @DisplayName("4.3.2.1 断言一：两租户互不可见（回写 / 任务 / 变更历史都隔离）")
  void tenantsAreIsolated() {
    UUID shopA = seedShop(a, AMAZON, "CB-ISO-A");
    UUID shopB = seedShop(b, AMAZON, "CB-ISO-B");
    var rowA = seedListing(actorA, shopA, "ISO-A", "A 的商品", "Home");
    seedListing(actorB, shopB, "ISO-B", "B 的商品", "Home");
    seedSnapshot(a, rowA.id(), "en-US", "旧标题", "旧描述");
    seedDraft(a, rowA.id(), "新标题", "新卖点");

    var wbA = create(actorA, req(List.of("TITLE"), List.of(rowA.id())));

    assertThat(as(actorB, "channel.operation.read", () -> service.writebacks(null, 0, 50))).isEmpty();
    // 跨租户读与读不存在返回**逐字相同**的 404（RLS 先把行藏起来）
    var cross = catchThrowable(() -> as(actorB, "channel.operation.read",
        () -> service.loadWritebackRow(wbA.writebackId().toString())));
    var missing = catchThrowable(() -> as(actorB, "channel.operation.read",
        () -> service.loadWritebackRow(UUID.randomUUID().toString())));
    assertThat(cross).isNotNull();
    assertThat(missing).isNotNull();
    assertThat(cross.getMessage()).isEqualTo(missing.getMessage());

    // 🔴 直接按主键直查也必须是零行（不是"读到一个空对象"）。
    //    这里用 **channel_app** 连：超级用户绕过 RLS，用它查永远得到"读得到" ——
    //    那不是隔离被破坏，而是这个断言本身失效（4.1.1.2 实测踩过）。
    assertThat(countAsTenant(a,
        "select count(*) from listing_content_writeback where id='" + wbA.writebackId() + "'"))
        .isEqualTo(1L);
    assertThat(countAsTenant(b,
        "select count(*) from listing_content_writeback where id='" + wbA.writebackId() + "'"))
        .isZero();
    // 两张新表真的开了 FORCE RLS
    for (String table : List.of("listing_content_writeback", "listing_content_writeback_task",
        "listing_content_writeback_change")) {
      assertThat(execScalarAsTenant(a,
          "select relforcerowsecurity from pg_class where relname='" + table + "'"))
          .isEqualTo(Boolean.TRUE);
    }
  }

  @Test
  @DisplayName("4.3.2.1 断言二：伪造 tenant header 无效（租户只来自已认证成员关系）")
  void forgedTenantHeaderIsIgnored() throws Exception {
    UUID shopA = seedShop(a, AMAZON, "CB-FG-A");
    UUID shopB = seedShop(b, AMAZON, "CB-FG-B");
    var rowA = seedListing(actorA, shopA, "FG-A", "A 的商品", "Home");
    var rowB = seedListing(actorB, shopB, "FG-B", "B 的商品", "Home");
    seedSnapshot(a, rowA.id(), "en-US", "旧标题", "旧");
    seedDraft(a, rowA.id(), "新标题", "新卖点");
    seedSnapshot(b, rowB.id(), "en-US", "B 旧标题", "B 旧描述");
    seedDraft(b, rowB.id(), "B 新标题", "B 新卖点");

    var wbA = create(actorA, req(List.of("TITLE"), List.of(rowA.id())));
    var wbB = create(actorB, req(List.of("TITLE"), List.of(rowB.id())));

    // A 的会话 + B 的头 ⇒ 仍然只看得见 A 的数据
    http.perform(get("/channel/listing-content-writeback/writebacks/" + wbA.writebackId())
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk());
    // 伪造租户头读不到 B 的回写（用 A 的会话读 B 的 id）
    http.perform(get("/channel/listing-content-writeback/writebacks/" + wbB.writebackId())
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isNotFound());

    // 无会话一律 401
    http.perform(get("/channel/listing-content-writeback/writebacks")
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("4.3.2.1 断言三：连接池归还后不串租户（池压到 1，A→B→A 三轮）")
  void pooledConnectionsDoNotBleedTenants() {
    UUID shopA = seedShop(a, AMAZON, "CB-PL-A");
    UUID shopB = seedShop(b, AMAZON, "CB-PL-B");
    var rowA = seedListing(actorA, shopA, "PL-A", "A 的商品", "Home");
    var rowB = seedListing(actorB, shopB, "PL-B", "B 的商品", "Home");
    seedSnapshot(a, rowA.id(), "en-US", "旧标题", "旧");
    seedDraft(a, rowA.id(), "新标题", "新卖点");
    seedSnapshot(b, rowB.id(), "en-US", "B 旧标题", "B 旧");
    seedDraft(b, rowB.id(), "B 新标题", "B 新卖点");

    var wbA1 = create(actorA, req(List.of("TITLE"), List.of(rowA.id())));
    assertThat(wbA1.totalCount()).isEqualTo(1);
    var wbB = create(actorB, req(List.of("TITLE"), List.of(rowB.id())));
    assertThat(wbB.totalCount()).isEqualTo(1);
    assertThat(wbB.writebackId()).isNotEqualTo(wbA1.writebackId());
    var wbA2 = create(actorA, req(List.of("TITLE"), List.of(rowA.id())));
    assertThat(wbA2.totalCount()).isEqualTo(1);

    // A 看不到 B 的那一次，B 看不到 A 的两次
    assertThat(as(actorB, "channel.operation.read", () -> service.writebacks(null, 0, 50)))
        .extracting(ContentWritebackRow::writebackId)
        .containsExactly(wbB.writebackId());
    assertThat(as(actorA, "channel.operation.read", () -> service.writebacks(null, 0, 50)))
        .extracting(ContentWritebackRow::writebackId)
        .containsExactlyInAnyOrder(wbA1.writebackId(), wbA2.writebackId());
  }

  // ==================================================================
  // 8. timestamptz 往返断言（写回值逐字相等）
  // ==================================================================

  @Test
  @DisplayName("4.3.2.1 timestamptz 往返：派发 / 回报的时间写回值逐字相等，且派发后不再变")
  void timestamptzRoundTrips() {
    UUID shop = seedShop(a, AMAZON, "CB-TS");
    var l1 = seedListing(actorA, shop, "TS-A1", "时间商品", "Home");
    seedSnapshot(a, l1.id(), "en-US", "旧标题", "旧");
    seedDraft(a, l1.id(), "新标题", "新卖点");
    rateLimit(actorA, AMAZON, 5);

    var wb = create(actorA, req(List.of("TITLE"), List.of(l1.id())));
    dispatch(actorA, wb.writebackId());
    var inflight = as(actorA, "channel.operation.read",
        () -> service.tasks(wb.writebackId().toString(), "DISPATCHED", 0, 50)).get(0);
    assertThat(inflight.dispatchedAt()).isNotNull();
    assertThat(inflight.completedAt()).isNull();

    as(actorA, "channel.operation.update", () -> service.report(inflight.taskId().toString(), true, null));
    var done = as(actorA, "channel.operation.read",
        () -> service.tasks(wb.writebackId().toString(), "SUCCEEDED", 0, 50)).get(0);
    assertThat(done.completedAt()).isNotNull();
    assertThat(done.dispatchedAt()).isEqualTo(inflight.dispatchedAt());

    // 🔴 写回值本身也要对得上（不是"只断言没抛异常"）：把它原样读回并逐字比较
    assertThat(execScalarAsTenant(a,
        "select completed_at from listing_content_writeback_task where id='" + done.taskId() + "'"))
        .isNotNull();
  }

  // ==================================================================
  // 9. 能力声明（未交付段必须 available=false + ownerModule）
  // ==================================================================

  @Test
  @DisplayName("4.3.2.1 能力声明：价格 / 库存通道不在本模块，能力表里写明归属")
  void capabilitiesDeclareOtherChannels() {
    var caps = as(actorA, "channel.operation.read", () -> service.capabilities());

    // 🔴 变更类型取值域里没有 PRICE / STOCK
    assertThat(caps.changeTypes()).containsExactlyInAnyOrder("TITLE", "BULLETS", "IMAGES", "DESCRIPTION", "A_PLUS");
    assertThat(caps.changeTypes()).doesNotContain("PRICE", "STOCK");
    // 被拒绝的类型逐个点名
    assertThat(caps.rejectedChangeTypes()).contains("PRICE", "STOCK", "QUANTITY");
    // 三条通道各归各家，本模块只处理 CONTENT
    assertThat(caps.channels()).hasSize(3);
    assertThat(caps.channels()).filteredOn(ContentWritebackChannel::handledHere)
        .extracting(ContentWritebackChannel::channel).containsExactly("CONTENT");
    assertThat(caps.channels()).filteredOn(c -> !c.handledHere())
        .extracting(ContentWritebackChannel::ownerModule).containsExactly("05 定价", "07 库存");

    // 未交付的三段逐条 available=false + 写明归属与影响（不假装具备）
    for (String key : List.of("platform_execution", "upstream_change_events",
        "internal_description_baseline", "category_writeback")) {
      var dim = caps.dimensions().stream().filter(d -> key.equals(d.dimension())).findFirst().orElseThrow();
      assertThat(dim.available()).as("%s 必须显式声明未交付", key).isFalse();
      assertThat(dim.ownerModule()).as("%s 必须写明归属模块", key).isNotEmpty();
      assertThat(dim.impact()).as("%s 必须写明不交付的影响", key).isNotEmpty();
    }
  }
}
