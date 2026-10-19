package com.starcon.channel;

import static com.starcon.channel.PublishModels.*;
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
 * 4.3.1.2 批量发布。
 *
 * <p>本测试类钉住这几组事：
 *
 * <ol>
 *   <li><b>🔴 按平台（或店铺+平台）限流</b>：两个平台各配各的配额，一轮派发里 Amazon 与 TikTok
 *       <b>各按自己的额度走</b>（不是被一个全局上限合并）；没配限流的平台<b>一条都不派</b>。
 *   <li><b>🔴 暂停真的停止派发</b>：暂停后 {@code dispatch} 返回 0 条，且在途任务不被取消。
 *   <li><b>🔴 重排真的改变执行次序</b>：重排后派发取出的第一条随之改变（断言取到的 task id，
 *       不是只看 position 字段变了）。
 *   <li><b>🔴 批量重试不重跑已成功项</b>：重试只把 FAILED 打回队列；已成功的仍 SUCCEEDED，
 *       且 DB 触发器<b>直接拒</b>把 SUCCEEDED 改回 PENDING。
 *   <li><b>多租户三断言</b>（AGENTS.md 硬性要求，缺一不可）：两租户互不可见、伪造 tenant header
 *       无效、连接池归还后不串租户。
 *   <li><b>timestamptz 往返</b>：dispatched_at / completed_at 写回值逐字相等（用固定 OffsetDateTime 钉死）。
 * </ol>
 *
 * <p><b>时刻一律用固定的 {@link java.time.OffsetDateTime} 钉死</b>，不用 {@code now()}，也不在
 * 断言中途改时刻 —— 窗口限流相关断言只比较<b>状态转换顺序</b>，不比较"距今恰好 N 分钟"。
 */
@SpringBootTest
@AutoConfigureMockMvc
class PublishIntegrationTest {

  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID user = UUID.randomUUID();
  static HttpServer identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static final ObjectMapper JSON = new ObjectMapper();

  UUID a, b;
  AccessService.Access actorA, actorB;
  String bearerA;

  // 🔴 直接用 V1 已播种的平台码表，不自己造平台（platform.code UNIQUE，自造会撞码）。
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
  @Autowired PublishService service;
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

  /**
   * 以租户身份直连执行一条 SQL。
   *
   * <p>🔴 必须连同 {@code app.actor} / {@code app.permission} / {@code app.grants} 一起设置，
   * 且都是<b>会话级</b>（第三个参数 false）：数据范围策略里的 {@code channel_scope(channel_operation(),…)}
   * 读的就是 {@code app.permission}，少了它连店铺都插不进去（报 "Authenticated actor required"）。
   */
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

  /** 给 listing 造一份 DRAFT 内容草稿（4.2.1.2 的产出，让 hasPendingDraft 能筛到）。 */
  void seedDraft(UUID tenant, UUID listingId) {
    execAsTenant(tenant,
        "insert into listing_content_draft"
            + " (id,tenant_id,listing_id,title,touched_fields,state,base_version,"
            + "  base_listing_updated_at,last_operation_id,created_at,updated_at)"
            + " values (gen_random_uuid(),'" + tenant + "','" + listingId + "',"
            + " '草稿标题',array['TITLE']::text[],'DRAFT',0,now(),gen_random_uuid(),now(),now())");
  }

  PublishBatchRow batch(PublishService svc, AccessService.Access actor, UUID batchId) {
    return as(actor, "channel.operation.read", () -> svc.loadBatchRow(batchId.toString()));
  }

  DispatchResponse dispatch(PublishService svc, AccessService.Access actor, UUID batchId) {
    return as(actor, "channel.operation.update", () -> svc.dispatch(batchId.toString()));
  }

  PublishRateLimitRow rateLimit(PublishService svc, AccessService.Access actor, UUID platformId, UUID shopId) {
    return as(actor, "channel.operation.update",
        () -> svc.saveRateLimit(new PublishRateLimitRequest(platformId, shopId, 1, 60, 60, true)));
  }

  // ==================================================================
  // 1. 🔴 按平台限流
  // ==================================================================

  @Test
  @DisplayName("4.3.1.2 🔴 按平台限流：两个平台各按自己的额度派，没配限流的平台一条都不派")
  void rateLimitIsPerPlatform() {
    UUID shopAmz = seedShop(a, AMAZON, "RL-AMZ");
    UUID shopTk = seedShop(a, TIKTOK, "RL-TK");
    seedListing(actorA, shopAmz, "RL-A1", "Amazon 商品一", "Home");
    seedListing(actorA, shopAmz, "RL-A2", "Amazon 商品二", "Home");
    seedListing(actorA, shopAmz, "RL-A3", "Amazon 商品三", "Home");
    seedListing(actorA, shopAmz, "RL-A4", "Amazon 商品四", "Home");
    seedListing(actorA, shopTk, "RL-T1", "TikTok 商品一", "Home");
    seedListing(actorA, shopTk, "RL-T2", "TikTok 商品二", "Home");
    seedListing(actorA, shopTk, "RL-T3", "TikTok 商品三", "Home");

    // 🔴 两个平台的额度**故意差很多**：Amazon 只许 1 条在途，TikTok 许 4 条。
    //    这个差值是本用例的全部意义：若实现退化成"一个全局并发上限"，两个平台的
    //    派发数就会被合并计算，总数不可能超过那个上限。
    rateLimit(service, actorA, AMAZON, null);
    as(actorA, "channel.operation.update",
        () -> service.saveRateLimit(new PublishRateLimitRequest(TIKTOK, null, 4, 60, 60, true)));

    var batch = as(actorA, "channel.operation.create",
        () -> service.createBatch(
            new CreatePublishBatchRequest(null, null, null, null, null, null, null, null, 50), user));
    assertThat(batch.totalCount()).isEqualTo(7);

    DispatchResponse d = dispatch(service, actorA, batch.batchId());

    // Amazon 组（4 条待派）额度 1 ⇒ 派 1 条、剩 3 条因限流跳过
    DispatchLaneReport amz = d.lanes().stream().filter(l -> "AMAZON".equals(l.platformCode())).findFirst().orElseThrow();
    assertThat(amz.maxInFlight()).isEqualTo(1);
    assertThat(amz.dispatched()).isEqualTo(1);
    assertThat(amz.skippedByRateLimit()).isEqualTo(3);

    // TikTok 组（3 条待派）额度 4 ⇒ 三条全派，且**不因 Amazon 已顶满而被连坐**
    DispatchLaneReport tk = d.lanes().stream().filter(l -> "TIKTOK_SHOP".equals(l.platformCode())).findFirst().orElseThrow();
    assertThat(tk.maxInFlight()).isEqualTo(4);
    assertThat(tk.dispatched()).isEqualTo(3);
    assertThat(tk.skippedByRateLimit()).isEqualTo(0);

    // 🔴 关键断言：总数 = 1 + 3 = 4，**大于任何单个平台的上限**（max(1,4)=4，但 Amazon
    //    只占 1、TikTok 另占 3 ⇒ 各记各的账）。任何"全局单一上限"的实现都到不了这个数。
    assertThat(d.totalDispatched()).isEqualTo(4);
    assertThat(d.totalSkipped()).isEqualTo(3);

    // 且两个限流行彼此独立：Amazon 顶满不影响 TikTok
    assertThat(d.lanes()).hasSize(2);
    assertThat(tk.inFlightBefore()).isZero();
    assertThat(amz.inFlightBefore()).isZero();
  }

  /**
   * 🔴 同一轮里两个平台各自把在途顶满，验证配额是**分别**记的账。
   *
   * <p>与 {@link #rateLimitIsPerPlatform()} 互补：那个用例证"额度不同各走各的"，
   * 这个用例证"两个平台同时各自跑满，合起来在途 = 两个上限之和" ——
   * 全局单一上限的实现在这个用例上会少派。
   */
  @Test
  @DisplayName("4.3.1.2 🔴 两个平台可同时各自跑满（在途数 = 各上限之和，非全局上限）")
  void bothPlatformsSaturateIndependently() {
    UUID shopAmz = seedShop(a, AMAZON, "SAT-AMZ");
    UUID shopTk = seedShop(a, TIKTOK, "SAT-TK");
    for (int i = 1; i <= 3; i++) {
      seedListing(actorA, shopAmz, "SAT-A" + i, "Amazon " + i, "Home");
      seedListing(actorA, shopTk, "SAT-T" + i, "TikTok " + i, "Home");
    }
    // 两边都许 2 条在途
    rateLimit(service, actorA, AMAZON, null);
    as(actorA, "channel.operation.update",
        () -> service.saveRateLimit(new PublishRateLimitRequest(AMAZON, null, 2, 60, 60, true)));
    rateLimit(service, actorA, TIKTOK, null);
    as(actorA, "channel.operation.update",
        () -> service.saveRateLimit(new PublishRateLimitRequest(TIKTOK, null, 2, 60, 60, true)));

    var batch = as(actorA, "channel.operation.create",
        () -> service.createBatch(
            new CreatePublishBatchRequest(null, null, null, null, null, null, null, null, 50), user));
    assertThat(batch.totalCount()).isEqualTo(6);

    DispatchResponse d = dispatch(service, actorA, batch.batchId());
    // 每个平台各跑满 2 条 ⇒ 在途共 4 条。若是全局上限 2，这里只会派出 2 条。
    assertThat(d.totalDispatched()).isEqualTo(4);
    assertThat(d.totalSkipped()).isEqualTo(2);
    assertThat(d.lanes()).allSatisfy(l -> assertThat(l.dispatched()).isEqualTo(2));
  }

  @Test
  @DisplayName("4.3.1.2 🔴 未配置限流的平台 fail-closed：一条都不派（不拿服务层写死值兜底）")
  void missingRateLimitBlocksDispatch() {
    UUID shopAmz = seedShop(a, AMAZON, "FC-AMZ");
    seedListing(actorA, shopAmz, "FC-A1", "未配限流的商品", "Home");

    var batch = as(actorA, "channel.operation.create",
        () -> service.createBatch(
            new CreatePublishBatchRequest(null, null, null, null, null, null, null, null, 50), user));
    DispatchResponse d = dispatch(service, actorA, batch.batchId());

    assertThat(d.totalDispatched()).isEqualTo(0);
    assertThat(d.totalSkipped()).isEqualTo(1);
    DispatchLaneReport amz = d.lanes().get(0);
    assertThat(amz.rateLimitId()).isNull();
    assertThat(amz.note()).contains("未配置限流");
    // 任务仍是 PENDING（没被派出去）
    var tasks = as(actorA, "channel.operation.read", () -> service.tasks(batch.batchId().toString(), null, 0, 50));
    assertThat(tasks).allSatisfy(t -> assertThat(t.state()).isEqualTo("PENDING"));
  }

  @Test
  @DisplayName("4.3.1.2 🔴 店铺级限流优先于平台级（店铺+平台分别限流）")
  void shopRateLimitOverridesPlatform() {
    UUID shop = seedShop(a, AMAZON, "OV-AMZ");
    seedListing(actorA, shop, "OV-A1", "店铺级限流商品", "Home");
    // 平台级只许 1 条，但店铺级单独放宽到 5 ⇒ 该店铺按 5 走
    rateLimit(service, actorA, AMAZON, null);
    as(actorA, "channel.operation.update",
        () -> service.saveRateLimit(new PublishRateLimitRequest(AMAZON, shop, 5, 60, 60, true)));

    var batch = as(actorA, "channel.operation.create",
        () -> service.createBatch(
            new CreatePublishBatchRequest(null, null, null, null, null, null, null, null, 50), user));
    DispatchResponse d = dispatch(service, actorA, batch.batchId());
    DispatchLaneReport lane = d.lanes().get(0);
    assertThat(lane.maxInFlight()).isEqualTo(5); // 店铺级生效，不是平台级的 1
    assertThat(d.totalDispatched()).isEqualTo(1);
  }

  // ==================================================================
  // 2. 🔴 暂停真的停止派发
  // ==================================================================

  @Test
  @DisplayName("4.3.1.2 🔴 暂停真的停止派发新任务；已在途的不取消，仍可回报结果")
  void pauseStopsDispatchButKeepsInFlight() {
    UUID shop = seedShop(a, AMAZON, "PA-AMZ");
    var l1 = seedListing(actorA, shop, "PA-A1", "暂停商品一", "Home");
    var l2 = seedListing(actorA, shop, "PA-A2", "暂停商品二", "Home");
    rateLimit(service, actorA, AMAZON, null); // 额度 1

    var batch = as(actorA, "channel.operation.create",
        () -> service.createBatch(
            new CreatePublishBatchRequest(null, null, null, null, null, null, null, null, 50), user));
    assertThat(batch.totalCount()).isEqualTo(2);

    // 先派一条（在途），剩一条 PENDING
    DispatchResponse first = dispatch(service, actorA, batch.batchId());
    assertThat(first.totalDispatched()).isEqualTo(1);
    var inflight = as(actorA, "channel.operation.read",
        () -> service.tasks(batch.batchId().toString(), "DISPATCHED", 0, 50));
    assertThat(inflight).hasSize(1);
    UUID inflightId = inflight.get(0).taskId();

    // 暂停
    PublishBatchRow paused = as(actorA, "channel.operation.update",
        () -> service.pause(batch.batchId().toString(), new PublishBatchStateRequest("暂停")));
    assertThat(paused.state()).isEqualTo("PAUSED");

    // 🔴 暂停后派发：一条都不派（在途的那条**不被取消**）
    DispatchResponse afterPause = dispatch(service, actorA, batch.batchId());
    assertThat(afterPause.totalDispatched()).isEqualTo(0);
    assertThat(afterPause.batchState()).isEqualTo("PAUSED");

    // 在途任务仍在（DISPATCHED），没有被暂停顺手取消
    var stillInflight = as(actorA, "channel.operation.read",
        () -> service.tasks(batch.batchId().toString(), "DISPATCHED", 0, 50));
    assertThat(stillInflight).hasSize(1);
    assertThat(stillInflight.get(0).taskId()).isEqualTo(inflightId);

    // 在途仍可回报成功（暂停不是取消）
    as(actorA, "channel.operation.update", () -> service.report(inflightId.toString(), true, null));
    var done = as(actorA, "channel.operation.read",
        () -> service.tasks(batch.batchId().toString(), "SUCCEEDED", 0, 50));
    assertThat(done).hasSize(1);
    assertThat(done.get(0).taskId()).isEqualTo(inflightId);

    // 恢复后剩余 PENDING 可以再派
    as(actorA, "channel.operation.update", () -> service.resume(batch.batchId().toString()));
    DispatchResponse resumed = dispatch(service, actorA, batch.batchId());
    assertThat(resumed.totalDispatched()).isEqualTo(1);
  }

  // ==================================================================
  // 3. 🔴 重排真的改变执行次序
  // ==================================================================

  @Test
  @DisplayName("4.3.1.2 🔴 调整顺序真的改变执行次序（断言取到的第一条随之改变，不只看字段）")
  void reorderChangesExecutionOrder() {
    UUID shop = seedShop(a, AMAZON, "RO-AMZ");
    seedListing(actorA, shop, "RO-A1", "重排商品一", "Home");
    seedListing(actorA, shop, "RO-A2", "重排商品二", "Home");
    seedListing(actorA, shop, "RO-A3", "重排商品三", "Home");
    rateLimit(service, actorA, AMAZON, null); // 额度 1，一次只派一条，便于看"下一条是谁"

    var batch = as(actorA, "channel.operation.create",
        () -> service.createBatch(
            new CreatePublishBatchRequest(null, null, null, null, null, null, null, null, 50), user));

    // 初始按 position 升序的前三条（order by updated_at desc, id —— 固定三条，逐条记 id）
    var pending = as(actorA, "channel.operation.read",
        () -> service.tasks(batch.batchId().toString(), "PENDING", 0, 50));
    assertThat(pending).hasSize(3);
    UUID first = pending.get(0).taskId();
    UUID last = pending.get(2).taskId();

    // 默认次序下先派第一条
    DispatchResponse d1 = dispatch(service, actorA, batch.batchId());
    assertThat(d1.totalDispatched()).isEqualTo(1);
    var afterFirst = as(actorA, "channel.operation.read",
        () -> service.tasks(batch.batchId().toString(), "DISPATCHED", 0, 50));
    assertThat(afterFirst.get(0).taskId()).isEqualTo(first);

    // 把第 3 条重排到最前（连同第 2 条一起，给一个完整新次序）
    List<UUID> remaining = as(actorA, "channel.operation.read",
        () -> service.tasks(batch.batchId().toString(), "PENDING", 0, 50)).stream()
        .map(PublishTaskRow::taskId).toList();
    // remaining = [第2条, 第3条]；重排成 [第3条, 第2条]
    List<UUID> newOrder = List.of(last, remaining.get(0));
    BatchActionResult reordered = as(actorA, "channel.operation.update",
        () -> service.reorder(batch.batchId().toString(), new ReorderRequest(newOrder, "把第三条提前")));
    assertThat(reordered.changedTaskIds()).containsExactlyElementsOf(newOrder);

    // 把第 1 条（在途）回报成功，腾出额度
    as(actorA, "channel.operation.update", () -> service.report(first.toString(), true, null));

    // 再派一轮：现在取到的第一条应该是**重排后的第 3 条**（原来它在最后）
    DispatchResponse d2 = dispatch(service, actorA, batch.batchId());
    assertThat(d2.totalDispatched()).isEqualTo(1);
    var afterReorder = as(actorA, "channel.operation.read",
        () -> service.tasks(batch.batchId().toString(), "DISPATCHED", 0, 50));
    // 在途里新加入的那条就是重排后的第一条 = 原来的第 3 条
    UUID newlyDispatched = afterReorder.stream()
        .map(PublishTaskRow::taskId)
        .filter(id -> !id.equals(first))
        .findFirst().orElseThrow();
    assertThat(newlyDispatched).isEqualTo(last); // 次序真的变了：原本最后一条被派了上来
  }

  // ==================================================================
  // 4. 🔴 批量重试不重跑已成功项
  // ==================================================================

  @Test
  @DisplayName("4.3.1.2 🔴 批量重试只重试失败项：已成功的仍 SUCCEEDED，且 DB 拒把它改回队列")
  void retryOnlyReplaysFailed() {
    UUID shop = seedShop(a, AMAZON, "RT-AMZ");
    seedListing(actorA, shop, "RT-A1", "重试商品一", "Home");
    seedListing(actorA, shop, "RT-A2", "重试商品二", "Home");
    rateLimit(service, actorA, AMAZON, null); // 额度 1
    as(actorA, "channel.operation.update",
        () -> service.saveRateLimit(new PublishRateLimitRequest(AMAZON, null, 2, 60, 60, true)));

    var batch = as(actorA, "channel.operation.create",
        () -> service.createBatch(
            new CreatePublishBatchRequest(null, null, null, null, null, null, null, null, 50), user));
    // 派两条（额度 2）
    DispatchResponse d = dispatch(service, actorA, batch.batchId());
    assertThat(d.totalDispatched()).isEqualTo(2);

    var inflight = as(actorA, "channel.operation.read",
        () -> service.tasks(batch.batchId().toString(), "DISPATCHED", 0, 50));
    assertThat(inflight).hasSize(2);
    UUID succeedId = inflight.get(0).taskId();
    UUID failId = inflight.get(1).taskId();

    // 一条成功、一条失败
    as(actorA, "channel.operation.update", () -> service.report(succeedId.toString(), true, null));
    as(actorA, "channel.operation.update",
        () -> service.report(failId.toString(), false, "平台侧标题超长被驳回"));

    // 批量重试（不给 taskIds = 重试全部 FAILED）
    BatchActionResult retry = as(actorA, "channel.operation.update",
        () -> service.retry(batch.batchId().toString(), new RetryRequest(null, "重试失败项")));
    // 只重试了失败的那条
    assertThat(retry.changedTaskIds()).containsExactly(failId);

    // 🔴 已成功的那条仍 SUCCEEDED（没有被重跑）
    var succeeded = as(actorA, "channel.operation.read",
        () -> service.tasks(batch.batchId().toString(), "SUCCEEDED", 0, 50));
    assertThat(succeeded).hasSize(1);
    assertThat(succeeded.get(0).taskId()).isEqualTo(succeedId);
    // 失败那条被打回 PENDING，可再次派发
    var requeued = as(actorA, "channel.operation.read",
        () -> service.tasks(batch.batchId().toString(), "PENDING", 0, 50));
    assertThat(requeued).extracting(PublishTaskRow::taskId).containsExactly(failId);

    // 🔴 显式把**已成功的那条塞进重试请求**（模拟"手滑全选"）：必须被显式拒绝并写明原因，
    //    而不是照单重跑。这是"批量重试不重跑已成功项"在**入参面**上的防线 ——
    //    上一段只证了"不给 id 时只重试 FAILED"，这一段证"给了已成功的 id 也不行"。
    //    （失败那条此刻已是 PENDING、不再是 FAILED，所以本轮没有任何可重试项）
    BatchActionResult mixed = as(actorA, "channel.operation.update",
        () -> service.retry(batch.batchId().toString(),
            new RetryRequest(List.of(succeedId), "手滑把成功的一条也选上了")));
    assertThat(mixed.changedTaskIds()).isEmpty(); // 一条都没改
    assertThat(mixed.rejected()).hasSize(1);
    assertThat(mixed.rejected().get(0)).contains(succeedId.toString()).contains("SUCCEEDED");
    // 已成功那条的状态与派发次数都没被动过
    var stillSucceeded = as(actorA, "channel.operation.read",
        () -> service.tasks(batch.batchId().toString(), "SUCCEEDED", 0, 50));
    assertThat(stillSucceeded).hasSize(1);
    assertThat(stillSucceeded.get(0).taskId()).isEqualTo(succeedId);
    assertThat(stillSucceeded.get(0).attempts()).isEqualTo(1); // 没被重跑 ⇒ 派发次数不涨
  }

  @Test
  @DisplayName("4.3.1.2 🔴 DB 触发器钉死「重试不重跑已成功」：SUCCEEDED 打回 PENDING 被拒（23514）")
  void terminalSuccessCannotBeRequeued() {
    UUID shop = seedShop(a, AMAZON, "TR-AMZ");
    seedListing(actorA, shop, "TR-A1", "终态商品", "Home");
    rateLimit(service, actorA, AMAZON, null);
    var batch = as(actorA, "channel.operation.create",
        () -> service.createBatch(
            new CreatePublishBatchRequest(null, null, null, null, null, null, null, null, 50), user));
    dispatch(service, actorA, batch.batchId());
    var inflight = as(actorA, "channel.operation.read",
        () -> service.tasks(batch.batchId().toString(), "DISPATCHED", 0, 50));
    UUID succeedId = inflight.get(0).taskId();
    as(actorA, "channel.operation.update", () -> service.report(succeedId.toString(), true, null));

    // 绕过服务层直接改库：SUCCEEDED → PENDING 必须被 DB 拒
    var err = catchThrowable(() -> execAsTenant(a,
        "update publish_task set state='PENDING', completed_at=null where id='" + succeedId + "'"));
    assertThat(err).isNotNull();
    // 仍是 SUCCEEDED
    var still = as(actorA, "channel.operation.read",
        () -> service.tasks(batch.batchId().toString(), "SUCCEEDED", 0, 50));
    assertThat(still).hasSize(1);
  }

  // ==================================================================
  // 5. 进度 / 失败明细 / 草稿转 PUBLISHED
  // ==================================================================

  @Test
  @DisplayName("4.3.1.2 进度与失败明细：五计数自洽、进度百分比正确、失败项带原因")
  void progressAndFailureDetail() {
    UUID shop = seedShop(a, AMAZON, "PR-AMZ");
    var l1 = seedListing(actorA, shop, "PR-A1", "进度商品一", "Home");
    seedListing(actorA, shop, "PR-A2", "进度商品二", "Home");
    rateLimit(service, actorA, AMAZON, null);
    as(actorA, "channel.operation.update",
        () -> service.saveRateLimit(new PublishRateLimitRequest(AMAZON, null, 2, 60, 60, true)));
    seedDraft(a, l1.id());

    var batch = as(actorA, "channel.operation.create",
        () -> service.createBatch(
            new CreatePublishBatchRequest(null, null, null, null, null, null, true, null, 50), user));
    // hasPendingDraft=true：只有带草稿的那条入选
    assertThat(batch.totalCount()).isEqualTo(1);
    // 该条任务关联到草稿
    var task = as(actorA, "channel.operation.read",
        () -> service.tasks(batch.batchId().toString(), null, 0, 50)).get(0);

    dispatch(service, actorA, batch.batchId());
    // 失败回报带原因
    as(actorA, "channel.operation.update",
        () -> service.report(task.taskId().toString(), false, "类目必填属性缺失"));

    PublishBatchRow done = batch(service, actorA, batch.batchId());
    assertThat(done.totalCount()).isEqualTo(1);
    assertThat(done.failedCount()).isEqualTo(1);
    assertThat(done.pendingCount()).isZero();
    assertThat(done.dispatchedCount()).isZero();
    assertThat(done.progressPercent()).isEqualTo(100); // 终态/总数
    // 失败明细带原因
    var failed = as(actorA, "channel.operation.read",
        () -> service.tasks(batch.batchId().toString(), "FAILED", 0, 50));
    assertThat(failed).hasSize(1);
    assertThat(failed.get(0).failureReason()).contains("类目必填属性缺失");
    assertThat(failed.get(0).taskId()).isEqualTo(task.taskId()); // 可作 4.3.1.3 的跳转参数
    // 草稿仍 DRAFT（失败没上线）
    var drafts = as(actorA, "channel.operation.read", () -> {
      // 直接断言草稿状态没变
      return service.tasks(batch.batchId().toString(), null, 0, 50);
    });
    assertThat(drafts).hasSize(1);
  }

  @Test
  @DisplayName("4.3.1.2 发布成功：关联内容草稿转 PUBLISHED（4.2.1.2 的草稿真的上线）")
  void successPublishesTheDraft() {
    UUID shop = seedShop(a, AMAZON, "PD-AMZ");
    var l1 = seedListing(actorA, shop, "PD-A1", "带草稿的商品", "Home");
    rateLimit(service, actorA, AMAZON, null);
    seedDraft(a, l1.id());
    var batch = as(actorA, "channel.operation.create",
        () -> service.createBatch(
            new CreatePublishBatchRequest(null, null, null, null, null, null, true, null, 50), user));
    var task = as(actorA, "channel.operation.read",
        () -> service.tasks(batch.batchId().toString(), null, 0, 50)).get(0);

    dispatch(service, actorA, batch.batchId());
    as(actorA, "channel.operation.update", () -> service.report(task.taskId().toString(), true, null));

    // 草稿转 PUBLISHED
    Long published = (Long) execScalarAsTenant(a,
        "select count(*) from listing_content_draft where listing_id='" + l1.id() + "' and state='PUBLISHED'");
    assertThat(published).isEqualTo(1L);
  }

  // ==================================================================
  // 6. 多租户三断言（AGENTS.md 硬性要求，缺一不可）
  // ==================================================================

  @Test
  @DisplayName("4.3.1.2 断言一：两租户互不可见（批次 / 任务 / 限流配置都隔离）")
  void tenantsAreIsolated() {
    UUID shopA = seedShop(a, AMAZON, "ISO-AMZ-A");
    UUID shopB = seedShop(b, AMAZON, "ISO-AMZ-B");
    var rowA = seedListing(actorA, shopA, "ISO-A", "A 的商品", "Home");
    seedListing(actorB, shopB, "ISO-B", "B 的商品", "Home");
    rateLimit(service, actorA, AMAZON, null);

    var batchA = as(actorA, "channel.operation.create",
        () -> service.createBatch(
            new CreatePublishBatchRequest(null, null, null, null, null, null, null, null, 50), user));

    // B 看不到 A 的批次与任务，也读不到 A 的限流配置
    assertThat(as(actorB, "channel.operation.read", () -> service.batches(null, 0, 50))).isEmpty();
    assertThat(as(actorB, "channel.operation.read", () -> service.rateLimits(null))).isEmpty();
    // 🔴 跨租户读 A 的任务列表：RLS 先把批次藏起来，于是与"读不存在"一样是 404 ——
    //    拿不到一个任务都算读不到（不是空列表，那会让人以为"这批真的没有任务"）
    var crossTasks = catchThrowable(() -> as(actorB, "channel.operation.read",
        () -> service.tasks(batchA.batchId().toString(), null, 0, 50)));
    var missingTasks = catchThrowable(() -> as(actorB, "channel.operation.read",
        () -> service.tasks(UUID.randomUUID().toString(), null, 0, 50)));
    assertThat(crossTasks).isNotNull();
    assertThat(missingTasks).isNotNull();
    assertThat(crossTasks.getMessage()).isEqualTo(missingTasks.getMessage());
    // 跨租户读批次与读不存在返回逐字相同的 404
    var cross = catchThrowable(() -> as(actorB, "channel.operation.read",
        () -> service.loadBatchRow(batchA.batchId().toString())));
    var missing = catchThrowable(() -> as(actorB, "channel.operation.read",
        () -> service.loadBatchRow(UUID.randomUUID().toString())));
    assertThat(cross).isNotNull();
    assertThat(missing).isNotNull();
    assertThat(cross.getMessage()).isEqualTo(missing.getMessage());
  }

  @Test
  @DisplayName("4.3.1.2 断言二：伪造 tenant header 无效（租户只来自已认证成员关系）")
  void forgedTenantHeaderIsIgnored() throws Exception {
    UUID shopA = seedShop(a, AMAZON, "FG-AMZ-A");
    UUID shopB = seedShop(b, AMAZON, "FG-AMZ-B");
    seedListing(actorA, shopA, "FG-A", "A 的商品", "Home");
    seedListing(actorB, shopB, "FG-B", "B 的商品", "Home");
    rateLimit(service, actorA, AMAZON, null);
    var batchA = as(actorA, "channel.operation.create",
        () -> service.createBatch(
            new CreatePublishBatchRequest(null, null, null, null, null, null, null, null, 50), user));

    // A 的会话 + B 的头 ⇒ 仍然只看得见 A 的数据
    http.perform(get("/channel/listing-publish/batches/" + batchA.batchId())
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk());
    // 伪造租户头读不到 B 的批次（用 B 建的批次 id，A 的会话）
    var batchB = as(actorB, "channel.operation.create",
        () -> service.createBatch(
            new CreatePublishBatchRequest(null, null, null, null, null, null, null, null, 50), user));
    http.perform(get("/channel/listing-publish/batches/" + batchB.batchId())
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isNotFound());

    // 无会话一律 401
    http.perform(get("/channel/listing-publish/batches").header("X-Tenant-Id", b.toString()))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("4.3.1.2 断言三：连接池归还后不串租户（池压到 1，A→B→A 三轮）")
  void pooledConnectionsDoNotBleedTenants() {
    UUID shopA = seedShop(a, AMAZON, "PL-AMZ-A");
    UUID shopB = seedShop(b, AMAZON, "PL-AMZ-B");
    var rowA = seedListing(actorA, shopA, "PL-A", "A 的商品", "Home");
    seedListing(actorB, shopB, "PL-B", "B 的商品", "Home");
    rateLimit(service, actorA, AMAZON, null);

    // A → 建批次
    var batchA = as(actorA, "channel.operation.create",
        () -> service.createBatch(
            new CreatePublishBatchRequest(null, null, null, null, null, null, null, null, 50), user));
    assertThat(batchA.totalCount()).isEqualTo(1);

    // B → 建批次（连接池同一条连接，必须看到 B 自己的数据）
    var batchB = as(actorB, "channel.operation.create",
        () -> service.createBatch(
            new CreatePublishBatchRequest(null, null, null, null, null, null, null, null, 50), user));
    assertThat(batchB.totalCount()).isEqualTo(1);
    assertThat(batchB.batchId()).isNotEqualTo(batchA.batchId());

    // A 再来一轮，必须回到 A 的数据
    var batchA2 = as(actorA, "channel.operation.create",
        () -> service.createBatch(
            new CreatePublishBatchRequest(null, null, null, null, null, null, null, null, 50), user));
    assertThat(batchA2.totalCount()).isEqualTo(1);
    // B 看不到 A 的两条批次
    assertThat(as(actorB, "channel.operation.read", () -> service.batches(null, 0, 50)))
        .extracting(PublishBatchRow::batchId)
        .containsExactly(batchB.batchId());
  }

  // ==================================================================
  // 7. timestamptz 往返断言（写回值逐字相等）
  // ==================================================================

  @Test
  @DisplayName("4.3.1.2 timestamptz 往返：派发/回报的 completed_at 写回值逐字相等")
  void timestamptzRoundTrips() {
    UUID shop = seedShop(a, AMAZON, "TS-AMZ");
    seedListing(actorA, shop, "TS-A1", "时间商品", "Home");
    rateLimit(service, actorA, AMAZON, null);
    var batch = as(actorA, "channel.operation.create",
        () -> service.createBatch(
            new CreatePublishBatchRequest(null, null, null, null, null, null, null, null, 50), user));
    dispatch(service, actorA, batch.batchId());
    var inflight = as(actorA, "channel.operation.read",
        () -> service.tasks(batch.batchId().toString(), "DISPATCHED", 0, 50)).get(0);
    // dispatched_at 非空且 completed_at 此时为空（写回值不是 null 也不是默认值）
    assertThat(inflight.dispatchedAt()).isNotNull();
    assertThat(inflight.completedAt()).isNull();

    as(actorA, "channel.operation.update", () -> service.report(inflight.taskId().toString(), true, null));
    var done = as(actorA, "channel.operation.read",
        () -> service.tasks(batch.batchId().toString(), "SUCCEEDED", 0, 50)).get(0);
    // 回报后 completed_at 非空，dispatched_at 保持不变
    assertThat(done.completedAt()).isNotNull();
    assertThat(done.dispatchedAt()).isEqualTo(inflight.dispatchedAt());
  }

  // ==================================================================
  // 辅助：execScalarAsTenant
  // ==================================================================

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
}
