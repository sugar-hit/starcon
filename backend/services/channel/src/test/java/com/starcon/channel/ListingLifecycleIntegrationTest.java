package com.starcon.channel;

import static com.starcon.channel.ListingLifecycleModels.*;
import static com.starcon.channel.ListingModels.*;
import static com.starcon.channel.ListingStatusSyncModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
 * 4.1.1.3 生命周期与下架 的集成测试（Testcontainers · 真实 PostgreSQL 16）。
 *
 * <p>🔴 逐条钉住的���变量：
 *
 * <ol>
 *   <li><b>🔴🔴 事件记录不交给服务层写</b>：一次人工动作 ⇒ 事件表里**恰好一条**事件
 *       （source='MANUAL'），且是 DB 触发器落的。服务层多插一条就是 2 条。
 *   <li><b>🔴🔴 平台联动不会被记成「人工下架」</b>：平台状态被动同步 ⇒ 事件 source='PLATFORM'
 *       且带平台事件类型与平台权威状态原文。这是本模块最要紧的一列 ——
 *       记错了，「平台把商品下架了」就会变成「我们主动下架了」，漏单原因就此被掩盖。
 *   <li><b>🔴 主动下架回写平台真的产生了平台任务</b>：writeback_batch(kind='DELIST') +
 *       批次条目 + platform_operation，三件都在，且 id 与回执一致。
 *   <li><b>🔴 不回写平台就不产生平台任务</b>，且差异区显式提示
 *       （INTERNAL_DELISTED_NOT_WRITEBACK）。
 *   <li><b>🔴 非法迁移显式拒绝并给出可读原因</b>（422，不是 500），且**一个字节都没写**。
 *   <li><b>🔴 4.1.1.1 回归</b>：没有上下文时（4.1.1.1 的批量下架），事件仍落
 *       source='MANUAL' / event_type='STATUS_CHANGE' / note='' ——
 *       V201 换掉触发器函数**没有**打破既有 INSERT 路径。
 *   <li><b>🔴 平台事件不可就地改写</b>（V199 append-only 回归）。
 *   <li><b>timestamptz 往返</b>：平台事件的 {@code occurredAt} 写回值<b>逐字相等</b>。
 *   <li><b>真分页</b>：{@code total} 是服务端 count 出来的。
 *   <li><b>SQL 与 Java 的差异判定不跑偏</b>：逐行比对两侧结论。
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class ListingLifecycleIntegrationTest {

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

  @Autowired ListingService listings;
  @Autowired ListingStatusSyncService statusSync;
  @Autowired ListingLifecycleService service;
  @Autowired AccessService access;
  @Autowired MockMvc http;

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

  UUID seedShop(UUID tenant, String code) {
    UUID id = UUID.randomUUID();
    execAsTenant(tenant,
        "insert into shop (tenant_id,id,platform_id,code,name) values ('" + tenant + "','" + id
            + "','" + AMAZON + "','" + code + "','测试店铺 " + code + "')"
            + " on conflict (tenant_id,code) do nothing");
    return id;
  }

  ListingRow seedListing(
      AccessService.Access who, UUID shopId, String externalItemId, String title, String status) {
    SaveListingRequest request =
        new SaveListingRequest(
            null, shopId, null, externalItemId, title, "Home", "cat-1",
            status, null, "", "IN_STOCK", null, null, null, null, null, null, List.of(), null);
    return as(who, "channel.operation.create", () -> listings.save(request));
  }

  LifecycleActionReceipt act(
      AccessService.Access who, UUID listingId, String action, Boolean writeback, String reason) {
    return as(
        who,
        "channel.operation.update",
        () ->
            service.act(
                listingId.toString(),
                new LifecycleActionRequest(action, writeback, reason),
                user,
                who.membershipId()));
  }

  /** 录一条平台状态事件（4.3.2.2 的入口）。 */
  ListingStatusEventRow ingest(
      AccessService.Access who, UUID listingId, String eventType, String platformStatus,
      String rejectionReason) {
    return as(
        who,
        "channel.operation.create",
        () ->
            statusSync.ingest(
                new IngestStatusEventRequest(
                    listingId, null, null, eventType, platformStatus, rejectionReason,
                    "API", null, Map.of(), ""),
                user));
  }

  long eventCount(UUID tenant, UUID listingId) {
    Object v =
        execScalarAsTenant(
            tenant, "select count(*) from listing_status_event where listing_id='" + listingId + "'");
    return v == null ? 0 : ((Number) v).longValue();
  }

  // ==================================================================
  // 断言一：事件记录不交给服务层写
  // ==================================================================

  @Test
  @DisplayName("4.1.1.3 🔴 一次人工动作 ⇒ 事件表里恰好一条事件（source=MANUAL，DB 触发器落的）")
  void manualActionWritesExactlyOneEvent() {
    UUID shop = seedShop(a, "LC-EVT-1");
    var listing = seedListing(actorA, shop, "LC-EVT-1", "事件商品", "ON_SALE");

    var receipt = act(actorA, listing.id(), "DELIST", false, "手动下架测试");

    // 🔴 服务层若多插一条，这里就是 2 —— 重复记会让「谁下的架」永远说不清
    assertThat(eventCount(a, listing.id())).as("一次动作只落一条事件").isEqualTo(1);

    var timeline = as(actorA, "channel.operation.read", () -> service.timeline(listing.id().toString(), null, 0, 50));
    assertThat(timeline.items()).hasSize(1);
    var event = timeline.items().get(0);
    assertThat(event.source()).as("人工动作的事件来源必须是 MANUAL").isEqualTo("MANUAL");
    assertThat(event.fromStatus()).isEqualTo("ON_SALE");
    assertThat(event.toStatus()).isEqualTo("DELISTED_BY_US");
    assertThat(event.eventType()).isEqualTo("STATUS_CHANGE");
    assertThat(event.platformStatus()).isEmpty();
    assertThat(event.note()).as("动因要进事件备注").isEqualTo("手动下架测试");

    // 回执里的事件 id 指向那一条
    assertThat(receipt.statusEventId()).isEqualTo(event.eventId());
    assertThat(receipt.toStatus()).isEqualTo("DELISTED_BY_US");
    assertThat(receipt.writebackRequested()).isFalse();
    assertThat(receipt.writebackBatchId()).isNull();

    // 动作留痕也落库了
    var detail = as(actorA, "channel.operation.read", () -> service.detail(listing.id().toString()));
    assertThat(detail.actions()).hasSize(1);
    assertThat(detail.actions().get(0).action()).isEqualTo("DELIST");
    assertThat(detail.actions().get(0).fromStatus()).isEqualTo("ON_SALE");
    assertThat(detail.actions().get(0).toStatus()).isEqualTo("DELISTED_BY_US");
    assertThat(detail.actions().get(0).source()).isEqualTo("MANUAL");
    assertThat(detail.actions().get(0).writebackRequested()).isFalse();
  }

  @Test
  @DisplayName("4.1.1.3 🔴 4.1.1.1 回归：没有上下文时事件仍落 source=MANUAL / STATUS_CHANGE / note=''")
  void existingManualPathIsUnchanged() {
    UUID shop = seedShop(a, "LC-REG-1");
    var listing = seedListing(actorA, shop, "LC-REG-1", "回归商品", "ON_SALE");

    // 走 4.1.1.1 的批量下架（它不设置任何上下文）
    var batch =
        as(
            actorA,
            "channel.operation.update",
            () ->
                listings.batch(
                    new ListingBatchRequest("DELIST", List.of(listing.id()), null, null, null)));

    assertThat(batch.outcome()).isEqualTo("SUCCEEDED");
    assertThat(eventCount(a, listing.id())).isEqualTo(1);

    var timeline = as(actorA, "channel.operation.read", () -> service.timeline(listing.id().toString(), null, 0, 50));
    var event = timeline.items().get(0);
    assertThat(event.source()).isEqualTo("MANUAL");
    assertThat(event.eventType()).as("人工改状态的事件类型仍是 STATUS_CHANGE").isEqualTo("STATUS_CHANGE");
    assertThat(event.note()).as("4.1.1.1 的路径下备注仍是空串（既有行为逐字不变）").isEmpty();
    assertThat(event.platformStatus()).isEmpty();
    assertThat(event.internalStatus()).isEmpty();
    assertThat(event.conflictDetected()).isFalse();
    assertThat(event.resolution()).isEqualTo("NOT_REQUIRED");
  }

  // ==================================================================
  // 断言二：主动下架回写平台
  // ==================================================================

  @Test
  @DisplayName("4.1.1.3 🔴 主动下架 + 回写平台 ⇒ 真的产生 writeback_batch(DELIST) + 条目 + 平台任务")
  void delistWithWritebackCreatesRealPlatformTask() {
    UUID shop = seedShop(a, "LC-WB-1");
    var listing = seedListing(actorA, shop, "LC-WB-1", "回写商品", "ON_SALE");

    var receipt = act(actorA, listing.id(), "DELIST", true, "平台同步下架");

    assertThat(receipt.writebackRequested()).isTrue();
    assertThat(receipt.writebackBatchId()).isNotNull();
    assertThat(receipt.writebackBatchNo()).startsWith("WB-");
    assertThat(receipt.operationId()).isNotNull();

    // 🔴 三件套都在库里（不是一句「已下发」）
    Object batchKind =
        execScalarAsTenant(
            a, "select kind from writeback_batch where id='" + receipt.writebackBatchId() + "'");
    assertThat(String.valueOf(batchKind)).as("复用 02 回写队列，kind 必须是 DELIST").isEqualTo("DELIST");

    Object item =
        execScalarAsTenant(
            a,
            "select item_result from writeback_batch_item where id='"
                + receipt.writebackBatchId()
                + "' or batch_id='"
                + receipt.writebackBatchId()
                + "'");
    assertThat(String.valueOf(item)).isEqualTo("QUEUED");

    Object operation =
        execScalarAsTenant(
            a,
            "select operation from platform_operation where id='" + receipt.operationId() + "'");
    assertThat(String.valueOf(operation)).as("DELIST 在 02 队列里映射为 PUBLISH").isEqualTo("PUBLISH");

    Object payload =
        execScalarAsTenant(
            a, "select payload->>'delist' from platform_operation where id='" + receipt.operationId() + "'");
    assertThat(String.valueOf(payload)).isEqualTo("true");

    // 目标也登记了（可回写目标簿）
    Object target =
        execScalarAsTenant(
            a,
            "select count(*) from writeback_target where external_ref='LC-WB-1' and target_type='LISTING'");
    assertThat(((Number) target).longValue()).isEqualTo(1);

    // 动作留痕关联到批次
    var detail = as(actorA, "channel.operation.read", () -> service.detail(listing.id().toString()));
    assertThat(detail.actions()).hasSize(1);
    assertThat(detail.actions().get(0).writebackRequested()).isTrue();
    assertThat(detail.actions().get(0).writebackBatchId()).isEqualTo(receipt.writebackBatchId());
    assertThat(detail.actions().get(0).operationId()).isEqualTo(receipt.operationId());
  }

  @Test
  @DisplayName("4.1.1.3 🔴 不回写平台 ⇒ 不产生任何平台任务，且差异区显式提示")
  void delistWithoutWritebackCreatesNoPlatformTask() {
    UUID shop = seedShop(a, "LC-NOWB-1");
    var listing = seedListing(actorA, shop, "LC-NOWB-1", "不回写商品", "ON_SALE");

    var receipt = act(actorA, listing.id(), "DELIST", false, "只改内部状态");

    assertThat(receipt.writebackRequested()).isFalse();
    assertThat(receipt.writebackBatchId()).isNull();
    assertThat(receipt.operationId()).isNull();

    Object batches =
        execScalarAsTenant(
            a, "select count(*) from writeback_batch where reason='只改内部状态'");
    assertThat(((Number) batches).longValue()).isZero();

    // 🔴 差异区必须提示「内部下了但没告诉平台」—— 这是漏单的另一种形态
    var diff = as(actorA, "channel.operation.read", () -> service.divergences(0, 50));
    assertThat(diff.items())
        .as("内部已下架且未回写平台 ⇒ 差异区要能看见")
        .anyMatch(row -> row.listingId().equals(listing.id())
            && "INTERNAL_DELISTED_NOT_WRITEBACK".equals(row.divergenceKind()));
  }

  @Test
  @DisplayName("4.1.1.3 🔴 目标已封禁 ⇒ 回写平台被显式拒绝，且不会留下半截状态")
  void blockedTargetCannotBeWrittenBack() {
    UUID shop = seedShop(a, "LC-BLK-1");
    var listing = seedListing(actorA, shop, "LC-BLK-1", "封禁目标商品", "ON_SALE");

    // 先把目标登记成 BLOCKED（模拟 2.5.2.1 封禁）
    execAsTenant(
        a,
        "insert into writeback_target(id,tenant_id,shop_id,target_type,external_ref,listing_ref,"
            + "sku_code,status,blocked_reason) values (gen_random_uuid(),'" + a + "','" + shop
            + "','LISTING','LC-BLK-1','" + listing.id() + "','','BLOCKED','店铺停售')");

    var thrown =
        catchThrowable(
            () -> act(actorA, listing.id(), "DELIST", true, "要回写但目标被封禁"));

    assertThat(thrown).isNotNull();
    assertThat(thrown.getMessage()).contains("封禁");
    // 🔴 整个动作回滚：内部状态没变，事件没落
    Object status =
        execScalarAsTenant(a, "select status from listing where id='" + listing.id() + "'");
    assertThat(String.valueOf(status)).as("回写失败时内部状态不该被改").isEqualTo("ON_SALE");
    assertThat(eventCount(a, listing.id())).isZero();
  }

  // ==================================================================
  // 断言三：非法迁移显式拒绝
  // ==================================================================

  @Test
  @DisplayName("4.1.1.3 🔴 非法迁移 ⇒ 422 + 可读原因，且一个字节都没写")
  void illegalTransitionIsRejectedWithReadableReason() {
    UUID shop = seedShop(a, "LC-ILL-1");
    var listing = seedListing(actorA, shop, "LC-ILL-1", "在售商品", "ON_SALE");

    var thrown =
        catchThrowable(() -> act(actorA, listing.id(), "REPUBLISH", false, "想重新上架"));

    assertThat(thrown).isNotNull();
    assertThat(thrown.getMessage()).as("拒绝理由要说清为什么").contains("已经在售");
    assertThat(thrown.getMessage()).contains("ON_SALE");

    Object status =
        execScalarAsTenant(a, "select status from listing where id='" + listing.id() + "'");
    assertThat(String.valueOf(status)).isEqualTo("ON_SALE");
    assertThat(eventCount(a, listing.id())).as("被拒的动作不许留下事件").isZero();
  }

  @Test
  @DisplayName("4.1.1.3 非法迁移在 HTTP 层是 422（不是 500）")
  void illegalTransitionIs422OverHttp() throws Exception {
    UUID shop = seedShop(a, "LC-ILL-2");
    var listing = seedListing(actorA, shop, "LC-ILL-2", "在售商品", "ON_SALE");

    http.perform(post("/channel/listing-lifecycles/" + listing.id() + "/actions")
            .header("Authorization", bearerA)
            .contentType("application/json")
            .content(JSON.writeValueAsString(
                new LifecycleActionRequest("REPUBLISH", false, "非法"))))
        .andExpect(status().isUnprocessableEntity());
  }

  // ==================================================================
  // 断言四：平台状态被动同步
  // ==================================================================

  @Test
  @DisplayName("4.1.1.3 🔴 平台联动 ⇒ 内部状态跟随平台，事件 source=PLATFORM（不是 MANUAL）")
  void platformSyncMovesStatusAndKeepsSourcePlatform() {
    UUID shop = seedShop(a, "LC-SYNC-1");
    var listing = seedListing(actorA, shop, "LC-SYNC-1", "平台联动商品", "ON_SALE");

    // 平台说「已下架」
    var platformEvent = ingest(actorA, listing.id(), "DELISTED", "INACTIVE", "");

    // 内部状态还挂在「在售」⇒ 差异区必须报出来
    var diffBefore = as(actorA, "channel.operation.read", () -> service.divergences(0, 50));
    assertThat(diffBefore.items())
        .as("平台已下架、内部还在售 ⇒ 漏单风险必须在差异区里")
        .anyMatch(row -> row.listingId().equals(listing.id())
            && "PLATFORM_DELISTED_INTERNAL_ON_SALE".equals(row.divergenceKind())
            && "HIGH".equals(row.severity()));

    // 被动同步：内部状态跟随平台
    var receipt =
        as(
            actorA,
            "channel.operation.create",
            () ->
                service.applyPlatformStatus(
                    listing.id().toString(),
                    new PlatformStatusRequest(platformEvent.eventId(), null, null, null, null, null, ""),
                    user));

    assertThat(receipt.fromStatus()).isEqualTo("ON_SALE");
    assertThat(receipt.toStatus()).isEqualTo("DELISTED_BY_PLATFORM");
    assertThat(receipt.source()).isEqualTo("PLATFORM");

    Object status =
        execScalarAsTenant(a, "select status from listing where id='" + listing.id() + "'");
    assertThat(String.valueOf(status)).isEqualTo("DELISTED_BY_PLATFORM");

    // 🔴 关键断言：联动的状态变更事件来源必须是 PLATFORM，且带平台事件类型与平台状态原文
    var timeline = as(actorA, "channel.operation.read", () -> service.timeline(listing.id().toString(), null, 0, 50));
    var linkage =
        timeline.items().stream()
            .filter(e -> "DELISTED_BY_PLATFORM".equals(e.toStatus()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("联动事件没落库"));
    assertThat(linkage.source()).as("平台联动不许被记成 MANUAL").isEqualTo("PLATFORM");
    assertThat(linkage.eventType()).isEqualTo("DELISTED");
    assertThat(linkage.platformStatus()).isEqualTo("INACTIVE");
    assertThat(linkage.fromStatus()).isEqualTo("ON_SALE");

    // 同步后差异消失（两边一致了）
    var diffAfter = as(actorA, "channel.operation.read", () -> service.divergences(0, 50));
    assertThat(diffAfter.items())
        .noneMatch(row -> row.listingId().equals(listing.id()));
  }

  @Test
  @DisplayName("4.1.1.3 🔴 平台受限事件（限流）不改变内部状态，但平台事实照样留痕")
  void throttledEventDoesNotChangeStatusButIsStillRecorded() {
    UUID shop = seedShop(a, "LC-THR-1");
    var listing = seedListing(actorA, shop, "LC-THR-1", "限流商品", "ON_SALE");

    var platformEvent = ingest(actorA, listing.id(), "THROTTLED", "THROTTLED", "");

    var receipt =
        as(
            actorA,
            "channel.operation.create",
            () ->
                service.applyPlatformStatus(
                    listing.id().toString(),
                    new PlatformStatusRequest(platformEvent.eventId(), null, null, null, null, null, ""),
                    user));

    assertThat(receipt.toStatus()).as("限流不改内部状态").isEqualTo("ON_SALE");

    Object status =
        execScalarAsTenant(a, "select status from listing where id='" + listing.id() + "'");
    assertThat(String.valueOf(status)).isEqualTo("ON_SALE");

    // 🔴 但平台事实必须留痕（动作行里）
    var detail = as(actorA, "channel.operation.read", () -> service.detail(listing.id().toString()));
    assertThat(detail.actions()).hasSize(1);
    assertThat(detail.actions().get(0).action()).isEqualTo("APPLY_PLATFORM_STATUS");
    assertThat(detail.actions().get(0).source()).isEqualTo("PLATFORM");
    assertThat(detail.actions().get(0).platformEventType()).isEqualTo("THROTTLED");
    assertThat(detail.actions().get(0).platformStatus()).isEqualTo("THROTTLED");
  }

  @Test
  @DisplayName("4.1.1.3 🔴 同一条平台事件不会被联动两次")
  void platformEventIsLinkedOnlyOnce() {
    UUID shop = seedShop(a, "LC-ONCE-1");
    var listing = seedListing(actorA, shop, "LC-ONCE-1", "只联动一次", "ON_SALE");
    var platformEvent = ingest(actorA, listing.id(), "DELISTED", "INACTIVE", "");

    var request =
        new PlatformStatusRequest(platformEvent.eventId(), null, null, null, null, null, "");
    as(actorA, "channel.operation.create", () -> service.applyPlatformStatus(listing.id().toString(), request, user));

    var thrown =
        catchThrowable(
            () ->
                as(
                    actorA,
                    "channel.operation.create",
                    () -> service.applyPlatformStatus(listing.id().toString(), request, user)));
    assertThat(thrown).as("同一条平台事件重复联动必须被拒").isNotNull();
  }

  @Test
  @DisplayName("4.1.1.3 🔴 批量同步：把还没联动过的平台事件应用掉，重复跑不再应用")
  void batchSyncAppliesPendingEventsOnce() {
    UUID shop = seedShop(a, "LC-BATCH-1");
    var listing = seedListing(actorA, shop, "LC-BATCH-1", "批量同步商品", "ON_SALE");
    ingest(actorA, listing.id(), "DELISTED", "INACTIVE", "");

    var first = as(actorA, "channel.operation.update", () -> service.sync(user));
    assertThat(first.appliedCount()).as("第一次同步应用一条").isEqualTo(1);
    assertThat(first.applied()).hasSize(1);

    var second = as(actorA, "channel.operation.update", () -> service.sync(user));
    assertThat(second.appliedCount()).as("同一条平台事件不会被应用两次").isZero();
  }

  @Test
  @DisplayName("4.1.1.3 🔴 平台事件不可就地改写（V199 append-only 回归）")
  void platformEventRemainsAppendOnly() {
    UUID shop = seedShop(a, "LC-APP-1");
    var listing = seedListing(actorA, shop, "LC-APP-1", "不可改写商品", "ON_SALE");
    var platformEvent = ingest(actorA, listing.id(), "DELISTED", "INACTIVE", "");

    var thrown =
        catchThrowable(
            () ->
                execAsTenant(
                    a,
                    "update listing_status_event set note='偷偷改一下' where id='"
                        + platformEvent.eventId()
                        + "'"));
    assertThat(thrown).as("平台事件就地改写必须被拒").isNotNull();
  }

  // ==================================================================
  // 断言五：时间 / 分页 / 差异判定
  // ==================================================================

  @Test
  @DisplayName("4.1.1.3 🔴 timestamptz 往返：平台事件 occurredAt 写回值逐字相等")
  void occurredAtRoundTripsExactly() {
    UUID shop = seedShop(a, "LC-TIME-1");
    var listing = seedListing(actorA, shop, "LC-TIME-1", "时间商品", "ON_SALE");
    OffsetDateTime occurred = OffsetDateTime.of(2026, 3, 14, 15, 9, 26, 0, ZoneOffset.ofHours(8));

    var platformEvent =
        as(
            actorA,
            "channel.operation.create",
            () ->
                statusSync.ingest(
                    new IngestStatusEventRequest(
                        listing.id(), null, null, "DELISTED", "INACTIVE", "",
                        "API", occurred, Map.of(), ""),
                    user));

    assertThat(platformEvent.occurredAt().toInstant()).isEqualTo(occurred.toInstant());
    Object stored =
        execScalarAsTenant(
            a,
            "select to_char(occurred_at at time zone 'Asia/Shanghai','YYYY-MM-DD HH24:MI:SS') "
                + "from listing_status_event where id='" + platformEvent.eventId() + "'");
    assertThat(String.valueOf(stored)).isEqualTo("2026-03-14 15:09:26");
  }

  @Test
  @DisplayName("4.1.1.3 真分页：total 是服务端 count 出来的，翻页能翻到不同数据")
  void paginationIsReal() {
    UUID shop = seedShop(a, "LC-PAGE-1");
    for (int i = 0; i < 3; i++) {
      seedListing(actorA, shop, "LC-PAGE-" + i, "分页商品 " + i, "ON_SALE");
    }

    var page0 = as(actorA, "channel.operation.read", () -> service.list(null, null, null, null, null, null, 0, 2));
    assertThat(page0.items()).hasSize(2);
    assertThat(page0.total()).isGreaterThanOrEqualTo(3);

    var page1 = as(actorA, "channel.operation.read", () -> service.list(null, null, null, null, null, null, 1, 2));
    assertThat(page1.items()).isNotEmpty();
    assertThat(page0.items().get(0).listingId())
        .as("第 2 页不能与第 1 页重复")
        .isNotEqualTo(page1.items().get(0).listingId());
  }

  @Test
  @DisplayName("4.1.1.3 🔴 SQL 与 Java 的差异判定不许跑偏（两边同一套口径）")
  void sqlAndJavaDivergenceAgree() {
    UUID shop = seedShop(a, "LC-DIV-1");
    var onSale = seedListing(actorA, shop, "LC-DIV-1", "在售待下架", "ON_SALE");
    var delisted = seedListing(actorA, shop, "LC-DIV-2", "已下架未回写", "ON_SALE");
    var platformOnSale = seedListing(actorA, shop, "LC-DIV-3", "平台在售内部下架", "ON_SALE");

    ingest(actorA, onSale.id(), "DELISTED", "INACTIVE", "");
    ingest(actorA, platformOnSale.id(), "APPROVED", "ACTIVE", "");
    act(actorA, delisted.id(), "DELIST", false, "只改内部状态");

    var rows = as(actorA, "channel.operation.read", () -> service.list(null, null, null, null, null, null, 0, 50)).items();

    for (var row : rows) {
      // 差异区的结论必须与 Java 侧口径一致（不写回平台的行 writebackRequested=false）
      String javaSide =
          ListingLifecycleService.divergenceOf(
              row.status(),
              row.platformEventType(),
              "DELISTED_BY_US".equals(row.status()) ? Boolean.FALSE : null);
      assertThat(row.divergenceKind())
          .as("商品 %s 的差异判定：SQL 与 Java 必须一致", row.externalItemId())
          .isEqualTo(javaSide);
      assertThat(javaSide).isNotNull();
    }
  }

  @Test
  @DisplayName("4.1.1.3 筛选：七态与来源都能真的筛")
  void filtersWork() {
    UUID shop = seedShop(a, "LC-FIL-1");
    var listing = seedListing(actorA, shop, "LC-FIL-1", "筛选商品", "ON_SALE");
    act(actorA, listing.id(), "DELIST", false, "筛选用");

    var byStatus = as(actorA, "channel.operation.read", () -> service.list("DELISTED_BY_US", null, null, null, null, null, 0, 50));
    assertThat(byStatus.items()).anyMatch(r -> r.listingId().equals(listing.id()));

    var byOtherStatus = as(actorA, "channel.operation.read", () -> service.list("ON_SALE", null, null, null, null, null, 0, 50));
    assertThat(byOtherStatus.items()).noneMatch(r -> r.listingId().equals(listing.id()));

    var bySource = as(actorA, "channel.operation.read", () -> service.list(null, "MANUAL", null, null, null, null, 0, 50));
    assertThat(bySource.items()).anyMatch(r -> r.listingId().equals(listing.id())
        && "MANUAL".equals(r.lastChangeSource()));

    var byOtherSource = as(actorA, "channel.operation.read", () -> service.list(null, "PLATFORM", null, null, null, null, 0, 50));
    assertThat(byOtherSource.items()).noneMatch(r -> r.listingId().equals(listing.id()));
  }

  @Test
  @DisplayName("4.1.1.3 未知状态 / 来源被显式拒绝（422），不是静默返回空")
  void unknownFiltersAreRejected() {
    var thrown = catchThrowable(() -> as(actorA, "channel.operation.read",
        () -> service.list("SOMETHING", null, null, null, null, null, 0, 50)));
    assertThat(thrown).isNotNull();
    assertThat(thrown.getMessage()).contains("未知的生命周期状态");

    var thrown2 = catchThrowable(() -> as(actorA, "channel.operation.read",
        () -> service.list(null, "SOMEBODY", null, null, null, null, 0, 50)));
    assertThat(thrown2).isNotNull();
    assertThat(thrown2.getMessage()).contains("未知的事件来源");
  }

  // ==================================================================
  // 断言六：端点
  // ==================================================================

  @Test
  @DisplayName("4.1.1.3 列表行带服务端算好的合法动作（页面据此渲染按钮，不自己猜）")
  void listRowCarriesAllowedActions() {
    UUID shop = seedShop(a, "LC-ACT-1");
    var onSale = seedListing(actorA, shop, "LC-ACT-1", "在售商品", "ON_SALE");
    var draft = seedListing(actorA, shop, "LC-ACT-2", "草稿商品", "DRAFT");

    var rows = as(actorA, "channel.operation.read", () -> service.list(null, null, null, null, null, null, 0, 50)).items();

    var onSaleRow = rows.stream().filter(r -> r.listingId().equals(onSale.id())).findFirst().orElseThrow();
    assertThat(onSaleRow.allowedActions())
        .as("在售可以下架 / 提交审核 / 标记封禁，但不能「重新上架」")
        .contains("DELIST", "SUBMIT_REVIEW", "MARK_BLOCKED")
        .doesNotContain("REPUBLISH");

    var draftRow = rows.stream().filter(r -> r.listingId().equals(draft.id())).findFirst().orElseThrow();
    assertThat(draftRow.allowedActions())
        .as("草稿不能「重新上架」以外的下架类动作？—— 草稿可以下架、提交审核、上架、标记封禁")
        .contains("DELIST", "SUBMIT_REVIEW", "REPUBLISH", "MARK_BLOCKED");
  }

  @Test
  @DisplayName("4.1.1.3 端点：能力声明 / 筛选词表 / 列表 / 差异 / 详情 / 时间线都真的可读")
  void endpointsAreReal() throws Exception {
    UUID shop = seedShop(a, "LC-EP-1");
    var listing = seedListing(actorA, shop, "LC-EP-1", "端点商品", "ON_SALE");
    act(actorA, listing.id(), "DELIST", false, "端点测试");

    http.perform(get("/channel/listing-lifecycles/capabilities").header("Authorization", bearerA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.lifecycleStatuses.length()").value(7))
        .andExpect(jsonPath("$.dimensions.length()").value(org.hamcrest.Matchers.greaterThan(0)))
        .andExpect(jsonPath("$.stateMachineRule").isNotEmpty());

    http.perform(get("/channel/listing-lifecycles/filter-options").header("Authorization", bearerA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.lifecycleStatuses.length()").value(7))
        .andExpect(jsonPath("$.actionSources.length()").value(2));

    http.perform(get("/channel/listing-lifecycles").header("Authorization", bearerA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items").isArray())
        .andExpect(jsonPath("$.total").isNumber());

    http.perform(get("/channel/listing-lifecycles/diff").header("Authorization", bearerA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items").isArray());

    http.perform(get("/channel/listing-lifecycles/" + listing.id()).header("Authorization", bearerA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.listing.listingId").value(listing.id().toString()))
        .andExpect(jsonPath("$.timeline[0].source").value("MANUAL"))
        .andExpect(jsonPath("$.actions[0].action").value("DELIST"));

    http.perform(get("/channel/listing-lifecycles/" + listing.id() + "/timeline")
            .header("Authorization", bearerA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].source").value("MANUAL"))
        .andExpect(jsonPath("$.total").value(1));

    // 不存在的商品 ⇒ 404
    http.perform(get("/channel/listing-lifecycles/" + UUID.randomUUID()).header("Authorization", bearerA))
        .andExpect(status().isNotFound());
  }
}
