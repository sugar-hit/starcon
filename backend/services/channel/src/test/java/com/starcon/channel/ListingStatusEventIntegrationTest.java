package com.starcon.channel;

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
 * 4.3.2.2 平台状态回流。
 *
 * <p>蓝图：「接收并展示平台侧的商品状态变化：审核通过/驳回、下架、限流、被抑制（suppressed）、类目审核、价格异常通知，并支持一键处理（如按驳回原因批量修正重发）。输出：状态变更记录 + 需处理队列。<b>回流是平台状态的权威来源</b>，内部状态与之冲突时以平台为准并告警。」
 *
 * <p>本测试类钉住这几组事：
 *
 * <ol>
 *   <li><b>🔴🔴 冲突判定是真实计算出来的</b>：内部 ON_SALE + 平台 DELISTED ⇒ 冲突且
 *       {@code resolution='PLATFORM_WINS'}；内部 UNDER_REVIEW + 平台 APPROVED ⇒ <b>不</b>冲突
 *       （时序差）；平台受限（限流 / 类目审核 / 价格异常）⇒ <b>不</b>冲突（商品仍在售）。
 *   <li><b>🔴🔴 有冲突必有告警</b>：告警行由 **DB 触发器**落 —— 手工插一条冲突事件，
 *       队列表里必然多出一条 CONFLICT_ALERT（服务层没写这段代码）。
 *   <li><b>🔴 以平台为准但不抢写入所有权</b>：冲突之后内部 {@code listing.status} <b>一字未改</b>
 *       （内部生命周期联动归 4.1.1.3）。
 *   <li><b>🔴 一键处理走 2.5.1.1 的既有队列</b>：按驳回原因筛 → 批量发起 → 真的写进
 *       {@code platform_operation}（幂等键可查）；<b>重复点不产生第二个平台任务</b>；
 *       已派发过的逐条写明原因跳过。
 *   <li><b>🔴 队列项与事件自洽</b>：DB 触发器钉住「驳回原因必须与事件逐字相同」
 *       「冲突告警必须真的对应一个冲突事件」。
 *   <li><b>🔴 平台事件 append-only</b>：就地改写被拒（23514）。
 *   <li><b>4.1.1.1 回归</b>：V171 触发器写的 MANUAL 事件**照常落库**（本迁移的 ALTER
 *       一律带 DEFAULT，没打破既有 INSERT 路径）。
 *   <li><b>timestamptz 往返</b>：{@code occurredAt} 写回值<b>逐字相等</b>，不是只断言没抛异常。
 *   <li><b>真分页</b>：{@code total} 是服务端 count 出来的，翻页能翻到不同的数据。
 * </ol>
 *
 * <p><b>时刻一律用固定的 {@link OffsetDateTime} 钉死</b>，不在断言中途改时刻。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ListingStatusEventIntegrationTest {

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
    // 连接池压到 1：多租户串租户在单连接下最容易暴露
    r.add("spring.datasource.hikari.maximum-pool-size", () -> "1");
  }

  @Autowired ListingService listings;
  @Autowired ListingStatusSyncService service;
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

  UUID seedShop(UUID tenant, String code) {
    UUID id = UUID.randomUUID();
    execAsTenant(tenant,
        "insert into shop (tenant_id,id,platform_id,code,name) values ('" + tenant + "','" + id
            + "','" + AMAZON + "','" + code + "','测试店铺 " + code + "')"
            + " on conflict (tenant_id,code) do nothing");
    return id;
  }

  /** 建一条内部状态为 {@code status} 的 listing。 */
  ListingRow seedListing(
      AccessService.Access who, UUID shopId, String externalItemId, String title, String status) {
    SaveListingRequest request =
        new SaveListingRequest(
            null, shopId, null, externalItemId, title, "Home", "cat-1",
            status, null, "", "IN_STOCK", null, null, null, null, null, null, List.of(), null);
    return as(who, "channel.operation.create", () -> listings.save(request));
  }

  ListingStatusEventRow ingest(
      AccessService.Access who, IngestStatusEventRequest request) {
    return as(who, "channel.operation.create", () -> service.ingest(request, user));
  }

  static IngestStatusEventRequest req(
      UUID listingId, String eventType, String platformStatus, String rejectionReason) {
    return new IngestStatusEventRequest(
        listingId, null, null, eventType, platformStatus, rejectionReason, "API", null,
        Map.of("vendor", "acme"), "");
  }

  // ==================================================================
  // 1. 🔴🔴 冲突判定
  // ==================================================================

  @Test
  @DisplayName("4.3.2.2 🔴🔴 内部 ON_SALE + 平台 DELISTED ⇒ 冲突，且以平台为准")
  void internalOnSaleVsPlatformDelistedIsConflict() {
    UUID shop = seedShop(a, "LS-CONFLICT");
    var l = seedListing(actorA, shop, "CF-A1", "冲突商品", "ON_SALE");

    var event = ingest(actorA, req(l.id(), "DELISTED", "INACTIVE", ""));

    assertThat(event.conflictDetected()).as("内部说在售、平台说已下架 ⇒ 必须判冲突").isTrue();
    assertThat(event.resolution()).isEqualTo("PLATFORM_WINS");
    assertThat(event.internalStatus()).as("冲突记录必须写明当时的内部状态").isEqualTo("ON_SALE");
    assertThat(event.platformStatus()).isEqualTo("INACTIVE");
    assertThat(event.conflictNote()).contains("ON_SALE").contains("DELISTED");
    assertThat(event.source()).isEqualTo("PLATFORM");
  }

  @Test
  @DisplayName("4.3.2.2 🔴 内部 UNDER_REVIEW + 平台 APPROVED ⇒ 不是冲突（时序差，不是事实矛盾）")
  void underReviewPlusApprovedIsNotConflict() {
    UUID shop = seedShop(a, "LS-TIMING");
    var l = seedListing(actorA, shop, "CF-A2", "待审商品", "UNDER_REVIEW");

    var event = ingest(actorA, req(l.id(), "APPROVED", "ACTIVE", ""));

    assertThat(event.conflictDetected())
        .as("内部还在等平台裁决 ⇒ 没有明确主张，不算冲突")
        .isFalse();
    assertThat(event.resolution()).isEqualTo("NOT_REQUIRED");
    assertThat(event.internalStatus()).as("不冲突时不留内部状态快照").isEmpty();
  }

  @Test
  @DisplayName("4.3.2.2 🔴 平台受限（限流 / 类目审核 / 价格异常）⇒ 不是冲突，但进需处理队列")
  void restrictedEventsAreNotConflictsButAreQueued() {
    UUID shop = seedShop(a, "LS-RESTRICTED");
    var l = seedListing(actorA, shop, "CF-A3", "受限商品", "ON_SALE");

    var throttled = ingest(actorA, req(l.id(), "THROTTLED", "THROTTLED", ""));
    assertThat(throttled.conflictDetected())
        .as("限流时商品仍在售，与内部「在售」不矛盾")
        .isFalse();

    var queue = as(actorA, "channel.operation.read", () -> service.queue(null, null, null, null, null, 0, 50));
    assertThat(queue.items()).hasSize(1);
    assertThat(queue.items().get(0).kind()).isEqualTo("REVIEW");
    assertThat(queue.items().get(0).eventType()).isEqualTo("THROTTLED");
  }

  @Test
  @DisplayName("4.3.2.2 🔴 内部 DELISTED_BY_US + 平台 APPROVED ⇒ 冲突（平台还在卖，系统以为已下架）")
  void platformSellableVsInternalDelistedIsConflict() {
    UUID shop = seedShop(a, "LS-CONFLICT2");
    var l = seedListing(actorA, shop, "CF-A4", "已下架商品", "DELISTED_BY_US");

    var event = ingest(actorA, req(l.id(), "APPROVED", "ACTIVE", ""));

    assertThat(event.conflictDetected()).isTrue();
    assertThat(event.resolution()).isEqualTo("PLATFORM_WINS");
    assertThat(event.internalStatus()).isEqualTo("DELISTED_BY_US");
  }

  // ==================================================================
  // 2. 🔴🔴 有冲突必有告警（DB 触发器落，服务层没写这段代码）
  // ==================================================================

  @Test
  @DisplayName("4.3.2.2 🔴🔴 有冲突必有告警行 —— 手工插事件，告警行照样出现（DB 触发器）")
  void conflictAlwaysProducesAlertRow() {
    UUID shop = seedShop(a, "LS-ALERT");
    var l = seedListing(actorA, shop, "CF-A5", "告警商品", "ON_SALE");

    // 🔴 直接写库（绕开服务层）—— 服务层要是漏写告警，这里也会出现告警行
    execAsTenant(a,
        "insert into listing_status_event"
            + " (id,tenant_id,listing_id,from_status,to_status,source,note,occurred_at,"
            + "  event_type,platform_status,internal_status,conflict_detected,conflict_note,resolution,"
            + "  rejection_reason,detail,received_at,ingest_channel,ingested_by)"
            + " values (gen_random_uuid(),'" + a + "','" + l.id() + "',null,'INACTIVE','PLATFORM','',now(),"
            + "  'DELISTED','INACTIVE','ON_SALE',true,'手工插的冲突','PLATFORM_WINS','','{}'::jsonb,now(),"
            + "  'WEBHOOK',null)");

    var conflicts = as(actorA, "channel.operation.read", () -> service.conflicts(0, 50));
    assertThat(conflicts.items()).as("DB 触发器必须落一条 CONFLICT_ALERT").hasSize(1);
    assertThat(conflicts.items().get(0).kind()).isEqualTo("CONFLICT_ALERT");
    assertThat(conflicts.items().get(0).note()).contains("以平台为准");
  }

  @Test
  @DisplayName("4.3.2.2 🔴 没有冲突就不产生告警行（不滥报）")
  void noConflictMeansNoAlertRow() {
    UUID shop = seedShop(a, "LS-NOALERT");
    var l = seedListing(actorA, shop, "CF-A6", "正常商品", "ON_SALE");

    ingest(actorA, req(l.id(), "THROTTLED", "THROTTLED", ""));

    var conflicts = as(actorA, "channel.operation.read", () -> service.conflicts(0, 50));
    assertThat(conflicts.items()).isEmpty();
  }

  @Test
  @DisplayName("4.3.2.2 🔴 冲突之后内部 listing.status 一字未改（生命周期联动归 4.1.1.3）")
  void conflictDoesNotRewriteInternalStatus() {
    UUID shop = seedShop(a, "LS-NOREWRITE");
    var l = seedListing(actorA, shop, "CF-A7", "不越权商品", "ON_SALE");

    ingest(actorA, req(l.id(), "DELISTED", "INACTIVE", ""));

    String status = (String) execScalarAsTenant(a, "select status from listing where id='" + l.id() + "'");
    assertThat(status)
        .as("本模块只记录，不改内部生命周期状态 —— 那是 4.1.1.3 的写入所有权")
        .isEqualTo("ON_SALE");
  }

  // ==================================================================
  // 3. 🔴 需处理队列
  // ==================================================================

  @Test
  @DisplayName("4.3.2.2 驳回事件 ⇒ 一条 REPUBLISH 待办，带平台原文的驳回原因")
  void rejectedEventCreatesRepublishQueueItem() {
    UUID shop = seedShop(a, "LS-REJECT");
    var l = seedListing(actorA, shop, "RJ-A1", "被驳回商品", "ON_SALE");

    var event = ingest(actorA, req(l.id(), "REJECTED", "REJECTED", "标题超出长度限制"));

    // 🔴 队列里其实有**两条**：一条 REPUBLISH（可一键修正重发）+ 一条 CONFLICT_ALERT
    //    （内部 ON_SALE 而平台 REJECTED ⇒ 冲突，告警由 DB 触发器落）。
    //    两者是不同的待办，不该塞进一行 —— 所以这里按 kind 分开断言。
    var republish =
        as(actorA, "channel.operation.read", () -> service.queue("REPUBLISH", null, null, null, null, 0, 50));
    assertThat(republish.items()).hasSize(1);
    var item = republish.items().get(0);
    assertThat(item.kind()).isEqualTo("REPUBLISH");
    assertThat(item.state()).isEqualTo("OPEN");
    assertThat(item.rejectionReason()).isEqualTo("标题超出长度限制");
    assertThat(item.eventId()).isEqualTo(event.eventId());

    var conflicts = as(actorA, "channel.operation.read", () -> service.conflicts(0, 50));
    assertThat(conflicts.items()).as("同一个事件的冲突告警也必须存在").hasSize(1);
    assertThat(conflicts.items().get(0).eventId()).isEqualTo(event.eventId());
  }

  @Test
  @DisplayName("4.3.2.2 审核通过不产生待办（没有要处理的事）")
  void approvedEventCreatesNoQueueItem() {
    UUID shop = seedShop(a, "LS-APPROVE");
    var l = seedListing(actorA, shop, "AP-A1", "通过商品", "UNDER_REVIEW");

    ingest(actorA, req(l.id(), "APPROVED", "ACTIVE", ""));

    var queue = as(actorA, "channel.operation.read", () -> service.queue(null, null, null, null, null, 0, 50));
    assertThat(queue.items()).isEmpty();
  }

  @Test
  @DisplayName("4.3.2.2 驳回事件必须带驳回原因 —— 缺了被点名拒绝")
  void rejectedEventWithoutReasonIsRejected() {
    UUID shop = seedShop(a, "LS-NOREASON");
    var l = seedListing(actorA, shop, "RJ-A2", "无原因商品", "ON_SALE");

    var boom =
        catchThrowable(() -> ingest(actorA, req(l.id(), "REJECTED", "REJECTED", "")));
    assertThat(boom).isNotNull();
    assertThat(boom.getMessage()).contains("驳回原因");
  }

  @Test
  @DisplayName("4.3.2.2 未知的事件类型被逐个点名拒绝（不猜一个最像的去执行）")
  void unknownEventTypeIsRejectedByName() {
    UUID shop = seedShop(a, "LS-BADTYPE");
    var l = seedListing(actorA, shop, "BT-A1", "坏类型商品", "ON_SALE");

    var boom = catchThrowable(() -> ingest(actorA, req(l.id(), "REJECTED_ISH", "X", "")));
    assertThat(boom).isNotNull();
    assertThat(boom.getMessage()).contains("REJECTED_ISH");
  }

  // ==================================================================
  // 4. 🔴🔴 一键处理（按驳回原因批量修正重发）
  // ==================================================================

  @Test
  @DisplayName("4.3.2.2 🔴🔴 按驳回原因批量发起修正重发 ⇒ 真的写进 02 队列（2.5.1.1 platform_operation）")
  void republishWritesToPlatformOperation() {
    UUID shop = seedShop(a, "LS-REPUBLISH");
    var l1 = seedListing(actorA, shop, "RP-A1", "驳回商品一", "ON_SALE");
    var l2 = seedListing(actorA, shop, "RP-A2", "驳回商品二", "ON_SALE");
    var l3 = seedListing(actorA, shop, "RP-A3", "另一种驳回", "ON_SALE");
    ingest(actorA, req(l1.id(), "REJECTED", "REJECTED", "标题过长"));
    ingest(actorA, req(l2.id(), "REJECTED", "REJECTED", "标题过长"));
    ingest(actorA, req(l3.id(), "REJECTED", "REJECTED", "类目缺失"));

    var receipt =
        as(actorA, "channel.operation.update",
            () -> service.republish(new RepublishRequest("标题过长", null, null, null, 50, "批量修正"), user));

    assertThat(receipt.matchedCount()).isEqualTo(2);
    assertThat(receipt.dispatchedCount()).isEqualTo(2);
    assertThat(receipt.dispatched()).hasSize(2);
    for (RepublishReceiptItem item : receipt.dispatched()) {
      assertThat(item.operationId()).as("必须真的落到 02 队列（回执凭据）").isNotNull();
      assertThat(item.idempotencyKey()).isEqualTo("LSR-" + item.queueId());
      assertThat(item.rejectionReason()).isEqualTo("标题过长");
    }

    // 🔴 队列里能查到：operation='PUBLISH' + payload.statusRepublish=true
    Long operations =
        (Long) execScalarAsTenant(a,
            "select count(*) from platform_operation where tenant_id='" + a
                + "' and operation='PUBLISH' and payload->>'statusRepublish'='true'");
    assertThat(operations).isEqualTo(2L);

    // 队列项推进到在途，且指向同一个队列任务
    var queue = as(actorA, "channel.operation.read", () -> service.queue("REPUBLISH", null, null, null, null, 0, 50));
    assertThat(queue.items()).hasSize(3);
    assertThat(queue.items())
        .filteredOn(item -> "标题过长".equals(item.rejectionReason()))
        .allMatch(item -> "DISPATCHED".equals(item.state()));
    assertThat(queue.items())
        .filteredOn(item -> "标题过长".equals(item.rejectionReason()))
        .allMatch(item -> item.operationId() != null);
    // 🔴 另一种驳回原因的**没有**被发起（按驳回原因筛得很准）
    assertThat(queue.items())
        .filteredOn(item -> "类目缺失".equals(item.rejectionReason()))
        .allMatch(item -> "OPEN".equals(item.state()) && item.operationId() == null);
  }

  @Test
  @DisplayName("4.3.2.2 🔴🔴 重复点「一键处理」不会产生第二个平台任务（幂等键唯一）")
  void repeatedRepublishDoesNotDuplicatePlatformTask() {
    UUID shop = seedShop(a, "LS-IDEMPOTENT");
    var l = seedListing(actorA, shop, "ID-A1", "幂等商品", "ON_SALE");
    ingest(actorA, req(l.id(), "REJECTED", "REJECTED", "标题过长"));

    var first =
        as(actorA, "channel.operation.update",
            () -> service.republish(new RepublishRequest("标题过长", null, null, null, 50, ""), user));
    assertThat(first.dispatchedCount()).isEqualTo(1);

    var second =
        as(actorA, "channel.operation.update",
            () -> service.republish(new RepublishRequest("标题过长", null, null, null, 50, ""), user));

    // 🔴 第二次不会再发起一个平台任务（已派发 ⇒ 跳过并**逐条写明原因**，不静默丢弃）
    assertThat(second.dispatchedCount()).isZero();
    assertThat(second.skippedCount()).isEqualTo(1);
    assertThat(second.rejected()).hasSize(1);
    assertThat(second.rejected().get(0)).contains("DISPATCHED");
    assertThat(second.matchedCount()).as("命中的总数要算上已处理过的那条").isEqualTo(1);

    Long operations =
        (Long) execScalarAsTenant(a,
            "select count(*) from platform_operation where tenant_id='" + a
                + "' and payload->>'statusRepublish'='true'");
    assertThat(operations).as("平台侧只能看到一个任务").isEqualTo(1L);
  }

  @Test
  @DisplayName("4.3.2.2 限流 / 类目审核 / 价格异常那几条**不**被「一键处理」发起重发")
  void reviewItemsAreNotRepublished() {
    UUID shop = seedShop(a, "LS-NOREPUB");
    var l = seedListing(actorA, shop, "NR-A1", "限流商品", "ON_SALE");
    ingest(actorA, req(l.id(), "THROTTLED", "THROTTLED", ""));

    var receipt =
        as(actorA, "channel.operation.update",
            () -> service.republish(new RepublishRequest(null, null, null, null, 50, ""), user));

    assertThat(receipt.dispatchedCount()).as("盲目重发解决不了限流").isZero();
    Long operations =
        (Long) execScalarAsTenant(a,
            "select count(*) from platform_operation where tenant_id='" + a
                + "' and payload->>'statusRepublish'='true'");
    assertThat(operations).isZero();
  }

  @Test
  @DisplayName("4.3.2.2 人工确认 / 处理完��会推进队列状态并留痕")
  void acknowledgeAndResolveAdvanceQueueState() {
    UUID shop = seedShop(a, "LS-ACK");
    var l = seedListing(actorA, shop, "AK-A1", "确认商品", "ON_SALE");
    ingest(actorA, req(l.id(), "REJECTED", "REJECTED", "标题过长"));

    var queue = as(actorA, "channel.operation.read", () -> service.queue(null, null, null, null, null, 0, 50));
    UUID queueId = queue.items().get(0).queueId();

    var acked = as(actorA, "channel.operation.update", () -> service.acknowledge(queueId.toString(), "看到了", user));
    assertThat(acked.state()).isEqualTo("ACKNOWLEDGED");

    var done = as(actorA, "channel.operation.update", () -> service.resolve(queueId.toString(), "已改好重发", user));
    assertThat(done.state()).isEqualTo("RESOLVED");
    assertThat(done.completedAt()).as("终态必须有时间戳").isNotNull();
  }

  // ==================================================================
  // 5. 🔴 DB 层的自洽与不可篡改
  // ==================================================================

  @Test
  @DisplayName("4.3.2.2 🔴 DB 的 CHECK 也写不进非法事件类型")
  void dbCheckRejectsUnknownEventType() {
    UUID shop = seedShop(a, "LS-DBTYPE");
    var l = seedListing(actorA, shop, "DB-A1", "DB 闸商品", "ON_SALE");

    assertThatThrownBy(() -> execAsTenant(a,
        "insert into listing_status_event"
            + " (id,tenant_id,listing_id,from_status,to_status,source,note,occurred_at,event_type,"
            + "  platform_status,internal_status,conflict_detected,conflict_note,resolution,"
            + "  rejection_reason,detail,received_at,ingest_channel)"
            + " values (gen_random_uuid(),'" + a + "','" + l.id() + "',null,'X','PLATFORM','',now(),"
            + "  'NOT_A_REAL_TYPE','X','',false,'','NOT_REQUIRED','','{}'::jsonb,now(),'API')"))
        .hasMessageContaining("check constraint");
  }

  @Test
  @DisplayName("4.3.2.2 🔴 声称有冲突却没写说明 ⇒ DB 直接拒（23514）")
  void dbRejectsUnexplainedConflict() {
    UUID shop = seedShop(a, "LS-DBCONF");
    var l = seedListing(actorA, shop, "DB-A2", "DB 闸商品二", "ON_SALE");

    assertThatThrownBy(() -> execAsTenant(a,
        "insert into listing_status_event"
            + " (id,tenant_id,listing_id,from_status,to_status,source,note,occurred_at,event_type,"
            + "  platform_status,internal_status,conflict_detected,conflict_note,resolution,"
            + "  rejection_reason,detail,received_at,ingest_channel)"
            + " values (gen_random_uuid(),'" + a + "','" + l.id() + "',null,'X','PLATFORM','',now(),"
            + "  'DELISTED','X','',true,'','PLATFORM_WINS','','{}'::jsonb,now(),'API')"))
        .hasMessageContaining("check constraint");
  }

  @Test
  @DisplayName("4.3.2.2 🔴 平台事件不可就地改写（append-only）")
  void platformEventIsAppendOnly() {
    UUID shop = seedShop(a, "LS-APPEND");
    var l = seedListing(actorA, shop, "AP-A2", "不可改写商品", "ON_SALE");
    var event = ingest(actorA, req(l.id(), "THROTTLED", "THROTTLED", ""));

    assertThatThrownBy(() -> execAsTenant(a,
        "update listing_status_event set conflict_detected=true, conflict_note='偷偷改成冲突', "
            + "resolution='PLATFORM_WINS' where id='" + event.eventId() + "'"))
        .hasMessageContaining("append-only");
  }

  @Test
  @DisplayName("4.3.2.2 🔴 队列项的驳回原因必须与事件逐字相同（触发器钉住）")
  void queueRejectionReasonMustMatchEvent() {
    UUID shop = seedShop(a, "LS-QCONSIST");
    var l = seedListing(actorA, shop, "QC-A1", "队列一致商品", "ON_SALE");
    var event = ingest(actorA, req(l.id(), "REJECTED", "REJECTED", "标题过长"));

    assertThatThrownBy(() -> execAsTenant(a,
        "insert into listing_status_action_queue"
            + " (id,tenant_id,event_id,listing_id,kind,state,event_type,rejection_reason,note,created_at,updated_at)"
            + " values (gen_random_uuid(),'" + a + "','" + event.eventId() + "','" + l.id()
            + "','REPUBLISH','OPEN','REJECTED','编一个别的理由','',now(),now())"))
        .hasMessageContaining("rejection_reason");
  }

  @Test
  @DisplayName("4.3.2.2 🔴 凭空插一条冲突告警被拒（必须真的对应一个冲突事件）")
  void fakeConflictAlertIsRejected() {
    UUID shop = seedShop(a, "LS-FAKEALERT");
    var l = seedListing(actorA, shop, "FA-A1", "假告警商品", "ON_SALE");
    var event = ingest(actorA, req(l.id(), "THROTTLED", "THROTTLED", ""));

    assertThatThrownBy(() -> execAsTenant(a,
        "insert into listing_status_action_queue"
            + " (id,tenant_id,event_id,listing_id,kind,state,event_type,rejection_reason,note,created_at,updated_at)"
            + " values (gen_random_uuid(),'" + a + "','" + event.eventId() + "','" + l.id()
            + "','CONFLICT_ALERT','OPEN','THROTTLED','','假的告警',now(),now())"))
        .hasMessageContaining("conflict");
  }

  @Test
  @DisplayName("4.3.2.2 🔴 本模块的表里无任何金额列（价格异常只是通知，判定归 05 定价）")
  void noMoneyColumns() {
    for (String table : List.of("listing_status_event", "listing_status_action_queue")) {
      Long moneyCols =
          (Long) execScalarAsTenant(a,
              "select count(*) from information_schema.columns where table_name='" + table
                  + "' and data_type in ('numeric','double precision','real','money')");
      assertThat(moneyCols).as(table + " 不该有金额列").isZero();
    }
  }

  @Test
  @DisplayName("4.3.2.2 4.1.1.1 回归：V171 触发器写的 MANUAL 事件照常落库（ALTER 没打破既有路径）")
  void manualStatusEventStillWorks() {
    UUID shop = seedShop(a, "LS-MANUAL");
    var l = seedListing(actorA, shop, "MN-A1", "人工改状态商品", "DRAFT");

    // 改内部状态 ⇒ V171 的 AFTER UPDATE 触发器应落一条 MANUAL 事件
    execAsTenant(a, "update listing set status='ON_SALE' where id='" + l.id() + "'");

    Long manual =
        (Long) execScalarAsTenant(a,
            "select count(*) from listing_status_event where listing_id='" + l.id() + "' and source='MANUAL'");
    assertThat(manual).as("V171 的触发器必须照常工作").isEqualTo(1L);

    // 且新列对它来说是默认值（event_type=STATUS_CHANGE）
    String type = (String) execScalarAsTenant(a,
        "select event_type from listing_status_event where listing_id='" + l.id() + "' and source='MANUAL'");
    assertThat(type).isEqualTo("STATUS_CHANGE");
  }

  // ==================================================================
  // 6. 🔴 时间往返 / 分页 / 能力声明
  // ==================================================================

  @Test
  @DisplayName("4.3.2.2 🔴 timestamptz 往返：occurredAt 写回值逐字相等（不是只断言没抛异常）")
  void occurredAtRoundTripsExactly() {
    UUID shop = seedShop(a, "LS-TIME");
    var l = seedListing(actorA, shop, "TM-A1", "时间商品", "ON_SALE");
    OffsetDateTime occurred = OffsetDateTime.of(2026, 3, 14, 15, 9, 26, 0, ZoneOffset.ofHours(8));

    var event =
        as(actorA, "channel.operation.create",
            () ->
                service.ingest(
                    new IngestStatusEventRequest(
                        l.id(), null, null, "THROTTLED", "THROTTLED", "", "WEBHOOK", occurred,
                        Map.of(), ""),
                    user));

    assertThat(event.occurredAt()).isEqualTo(occurred);
    // 落库的值也逐字相等（用应用角色直查，不靠对象序列化）
    Object stored =
        execScalarAsTenant(a,
            "select to_char(occurred_at at time zone 'Asia/Shanghai','YYYY-MM-DD HH24:MI:SS') "
                + "from listing_status_event where id='" + event.eventId() + "'");
    assertThat(String.valueOf(stored)).isEqualTo("2026-03-14 15:09:26");
  }

  @Test
  @DisplayName("4.3.2.2 真分页：total 是真的总数，翻页能翻到不同的数据")
  void paginationIsReal() {
    UUID shop = seedShop(a, "LS-PAGE");
    for (int i = 0; i < 5; i++) {
      var l = seedListing(actorA, shop, "PG-A" + i, "分页商品" + i, "ON_SALE");
      ingest(actorA, req(l.id(), "THROTTLED", "THROTTLED", ""));
    }

    var first = as(actorA, "channel.operation.read", () -> service.events(null, null, null, null, null, 0, 2));
    assertThat(first.total()).isEqualTo(5L);
    assertThat(first.items()).hasSize(2);

    var second = as(actorA, "channel.operation.read", () -> service.events(null, null, null, null, null, 1, 2));
    assertThat(second.items()).hasSize(2);
    assertThat(second.items().get(0).eventId())
        .as("第二页必须是不同的数据")
        .isNotEqualTo(first.items().get(0).eventId());
  }

  @Test
  @DisplayName("4.3.2.2 筛选：按事件类型 / 店铺 / 冲突与否筛得动")
  void filtersWork() {
    UUID shop = seedShop(a, "LS-FILTER");
    var l1 = seedListing(actorA, shop, "FL-A1", "筛选商品一", "ON_SALE");
    var l2 = seedListing(actorA, shop, "FL-A2", "筛选商品二", "ON_SALE");
    ingest(actorA, req(l1.id(), "THROTTLED", "THROTTLED", ""));
    ingest(actorA, req(l2.id(), "DELISTED", "INACTIVE", ""));

    var byType = as(actorA, "channel.operation.read", () -> service.events(null, "THROTTLED", null, null, null, 0, 50));
    assertThat(byType.items()).hasSize(1);
    assertThat(byType.items().get(0).eventType()).isEqualTo("THROTTLED");

    var byConflict =
        as(actorA, "channel.operation.read", () -> service.events(null, null, null, null, Boolean.TRUE, 0, 50));
    assertThat(byConflict.items()).as("只有 DELISTED 那条是冲突").hasSize(1);
    assertThat(byConflict.items().get(0).eventType()).isEqualTo("DELISTED");

    var byShop = as(actorA, "channel.operation.read", () -> service.events(null, null, "LS-FILTER", null, null, 0, 50));
    assertThat(byShop.items()).hasSize(2);
  }

  @Test
  @DisplayName("4.3.2.2 能力声明如实报出未交付段（available=false + ownerModule）")
  void capabilitiesReportUnavailableDimensions() {
    var caps = as(actorA, "channel.operation.read", () -> service.capabilities());

    assertThat(caps.eventTypes()).contains("APPROVED", "REJECTED", "DELISTED", "THROTTLED",
        "SUPPRESSED", "CATEGORY_REVIEW", "PRICE_ALERT");

    var unavailable = caps.dimensions().stream().filter(d -> !d.available()).toList();
    assertThat(unavailable).isNotEmpty();
    // 🔴 三个未交付段必须指名责任模块（ownerModule 的写法是「模块号 模块名」）
    List<String> owners = unavailable.stream().map(ListingStatusCapabilityDimension::ownerModule).toList();
    assertThat(owners.stream().anyMatch(o -> o.startsWith("4.1.1.3"))).as("4.1.1.3 生命周期联动").isTrue();
    assertThat(owners.stream().anyMatch(o -> o.startsWith("4.3.1.3"))).as("4.3.1.3 发布任务").isTrue();
    assertThat(owners.stream().anyMatch(o -> o.startsWith("16.2.1.2"))).as("16.2.1.2 合规拦截").isTrue();
    // 每条未交付段都要写明影响
    assertThat(unavailable).allMatch(d -> d.impact() != null && !d.impact().isBlank());
  }

  @Test
  @DisplayName("4.3.2.2 端点往返：capabilities / ingest / events / queue / conflicts / republish")
  void endpointsRoundTrip() throws Exception {
    UUID shop = seedShop(a, "LS-HTTP");
    var l = seedListing(actorA, shop, "HT-A1", "端点商品", "ON_SALE");

    http.perform(get("/channel/listing-status-events/capabilities").header("Authorization", bearerA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.eventTypes.length()").value(7));

    http.perform(post("/channel/listing-status-events/events")
            .header("Authorization", bearerA)
            .contentType("application/json")
            .content(JSON.writeValueAsString(req(l.id(), "REJECTED", "REJECTED", "标题过长"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.eventType").value("REJECTED"));

    http.perform(get("/channel/listing-status-events/events").header("Authorization", bearerA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1));

    http.perform(get("/channel/listing-status-events/queue").header("Authorization", bearerA))
        .andExpect(status().isOk())
        // 🔴 驳回事件落在「内部说在售」的商品上 ⇒ 同时产生一条 REPUBLISH 与一条冲突告警
        .andExpect(jsonPath("$.items.length()").value(2))
        .andExpect(jsonPath("$.items[?(@.kind=='REPUBLISH')]").isNotEmpty());

    http.perform(get("/channel/listing-status-events/conflicts").header("Authorization", bearerA))
        .andExpect(status().isOk())
        // 🔴 驳回 + 内部说在售 ⇒ 冲突 ⇒ 告警行必然存在
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].kind").value("CONFLICT_ALERT"));

    http.perform(post("/channel/listing-status-events/actions/republish")
            .header("Authorization", bearerA)
            .contentType("application/json")
            .content(JSON.writeValueAsString(new RepublishRequest("标题过长", null, null, null, 50, ""))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.dispatchedCount").value(1))
        .andExpect(jsonPath("$.dispatched[0].operationId").isNotEmpty());
  }
}
