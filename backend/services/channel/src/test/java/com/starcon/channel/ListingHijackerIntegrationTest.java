package com.starcon.channel;

import static com.starcon.channel.ListingModels.*;
import static com.starcon.channel.ListingHijackerModels.*;
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
import java.time.LocalDate;
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
 * 4.5.2.1 跟卖监控。
 *
 * <p>蓝图：「监控他人/己方其他店铺对本 ASIN 的跟卖行为，记录跟卖者、价格、店铺，发现异常跟卖或恶意压价时告警。输入：定时抓取或平台接口返回的跟卖信息。输出：跟卖记录 + 预警通知 + 对比视图。关系：上游 = 抓取任务；下游 = 16 合规（品牌保护）、1 工作台预警。<b>跟卖数据只能作为参考</b>，处置动作（投诉/举报）在平台侧完成，系统只负责发现与提醒。」
 *
 * <p>本测试类钉住这几组事：
 *
 * <ol>
 *   <li><b>🔴🔴 数据真的落 PostgreSQL</b>：跟卖记录与预警都落表，数值<b>逐字相等</b>
 *       （不是只断言没抛异常）。
 *   <li><b>🔴🔴 分母为 0 时价格差比例是 null</b>：不是 0、也不是 100 —— 报 0 会被读成
 *       「跟卖价和自家价一样」，那是结论不是缺口。
 *   <li><b>🔴 判定规则</b>：恶意压价（≤ 阈值）、异常跟卖（价格差比例 &gt; 阈值）、
 *       己方其他店铺跟卖（**算出来的**，不是调用方声明的）；阈值不给 ⇒ <b>不判定</b>。
 *   <li><b>🔴 幂等与跳过是两件事</b>：同一批次重投 ⇒ 整批不落库（replayed=true）；
 *       同 (监控对象, 跟卖者, 发现时刻) 已有记录 ⇒ 跳过并<b>逐条写明原因</b>。
 *   <li><b>🔴 跟卖者是被监控 listing 自己的店铺 ⇒ 那不是跟卖</b>：跳过并写明原因。
 *   <li><b>🔴 对比视图是服务端聚合</b>：一个跟卖者一行（GROUP BY），价格差比例现算，真分页。
 *   <li><b>🔴 全程标注「仅供参考、不构成处置结论」</b>：每个响应都带 dataSource/sourceNote；
 *       capabilities 里 16 合规 / 1 工作台 / 02 抓取调度器 / 05 定价 四段 available=false。
 *   <li><b>🔴 DB 层闸门</b>：跟卖记录的商品必须与监控对象一致（触发器）；已阅的预警判定结论
 *       不可再改（触发器）；跨租户挂 listing 在物理上不可能（复合 FK）；三张表 FORCE RLS。
 *   <li><b>🔴 timestamptz 往返</b>：发现时间写回值<b>逐字相等</b>（+08:00 非 UTC 偏移）。
 *   <li><b>真分页</b>：{@code total} 是服务端 count 出来的，翻页能翻到不同的数据。
 * </ol>
 *
 * <p><b>时刻一律钉死</b>，不在断言中途改时刻。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ListingHijackerIntegrationTest {

  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID user = UUID.randomUUID();
  static HttpServer identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

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
  @Autowired ListingHijackerService service;
  @Autowired AccessService access;
  @Autowired MockMvc http;

  // ==================================================================
  // 辅助
  // ==================================================================

  <T> T as(AccessService.Access who, String permission, java.util.function.Supplier<T> work) {
    return access.run(who, permission, work);
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

  /** 以管理员连接跑一条 SQL（只用于读全局码表 / 制造 DB 层违规，不受 RLS 约束的事实）。 */
  static Object scalar(String sql) {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement();
        var rs = s.executeQuery(sql)) {
      return rs.next() ? rs.getObject(1) : null;
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  /** 跑一条会违反约束的 SQL，返回 PG 的 SQLSTATE。 */
  static String sqlState(String sql) {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(sql);
      return "00000";
    } catch (SQLException e) {
      return e.getSQLState();
    }
  }

  UUID seedShop(UUID tenant, String code) {
    return seedShop(tenant, code, "");
  }

  /** 建店铺；{@code platformShopId} 是**平台侧**的店铺标识（用来判「是不是我们自己人」）。 */
  UUID seedShop(UUID tenant, String code, String platformShopId) {
    UUID id = UUID.randomUUID();
    execAsTenant(tenant,
        "insert into shop (tenant_id,id,platform_id,code,name,platform_shop_id)"
            + " values ('" + tenant + "','" + id + "','" + AMAZON + "','" + code + "','测试店铺 "
            + code + "','" + platformShopId + "')"
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

  static HijackerRecordInput rec(
      String sellerId, String shopIdentifier, String shopName, String price) {
    return new HijackerRecordInput(
        sellerId, shopIdentifier, shopName, new BigDecimal(price), "USD", Map.of());
  }

  static IngestHijackerRecordsRequest ingestReq(
      UUID watchId, String key, OffsetDateTime observedAt, HijackerRecordInput... records) {
    return new IngestHijackerRecordsRequest(
        watchId, observedAt, "PLATFORM", "API", key, "", List.of(records));
  }

  static OffsetDateTime at(int day, int hour) {
    return OffsetDateTime.of(2026, 10, day, hour, 30, 0, 0, ZoneOffset.UTC);
  }

  ListingHijackerWatchRow createWatch(
      AccessService.Access who, UUID listingId, String reference, String cut, String gap) {
    return as(
        who,
        "channel.operation.create",
        () ->
            service.createWatch(
                new SaveHijackerWatchRequest(
                    listingId, null, null,
                    reference == null ? null : new BigDecimal(reference),
                    "USD",
                    cut == null ? null : new BigDecimal(cut),
                    gap == null ? null : new BigDecimal(gap),
                    "ACTIVE", "测试监控")));
  }

  ListingHijackerIngestResult ingest(
      AccessService.Access who, IngestHijackerRecordsRequest request) {
    return as(who, "channel.operation.create", () -> service.ingest(request));
  }

  // ==================================================================
  // 1. 🔴 监控对象
  // ==================================================================

  @Test
  @DisplayName("4.5.2.1 🔴 监控对象：新建 / 改阈值 / 启停 / 删除，全部真落 PostgreSQL")
  void watchLifecycleWorks() {
    UUID shop = seedShop(a, "HJ-WATCH");
    var l = seedListing(actorA, shop, "HJ-A1", "监控商品", "ON_SALE");

    var watch = createWatch(actorA, l.id(), "25.00", "21.00", "0.10");
    assertThat(watch.watchId()).isNotNull();
    assertThat(watch.listingId()).isEqualTo(l.id());
    assertThat(watch.externalItemId()).isEqualTo("HJ-A1");
    assertThat(watch.platformCode()).isEqualTo("AMAZON");
    assertThat(watch.referencePrice()).isEqualByComparingTo("25.00");
    assertThat(watch.priceCutThreshold()).isEqualByComparingTo("21.00");
    assertThat(watch.priceGapRatioThreshold()).isEqualByComparingTo("0.10");
    assertThat(watch.status()).isEqualTo("ACTIVE");

    // 🔴 同一个 listing 不许配两套阈值
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () -> createWatch(actorA, l.id(), "30.00", null, null)))
        .as("同一个 listing 配两套阈值是配置错误")
        .isNotNull();

    // 🔴 用 (店铺编码 + ASIN) 也能定位（抓取适配器手上最常有的就是这对）——
    //    换一个商品建，免得撞上"一个 listing 一个监控对象"那条约束。
    var l2 = seedListing(actorA, shop, "HJ-A1B", "另一个监控商品", "ON_SALE");
    var byPair =
        as(
            actorA,
            "channel.operation.create",
            () ->
                service.createWatch(
                    new SaveHijackerWatchRequest(
                        null, "HJ-WATCH", "HJ-A1B", new BigDecimal("25.00"), "USD",
                        new BigDecimal("21.00"), new BigDecimal("0.10"), "ACTIVE", "按 ASIN 建")));
    assertThat(byPair.listingId()).isEqualTo(l2.id());
    // 🔴 同一个 listing 已经有监控对象了 ⇒ 409
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    as(
                        actorA,
                        "channel.operation.create",
                        () ->
                            service.createWatch(
                                new SaveHijackerWatchRequest(
                                    null, "HJ-WATCH", "HJ-A1B", null, "USD",
                                    null, null, "ACTIVE", "重复")))))
        .isNotNull();

    // 🔴 PUT = 全量替换：字段不给 = 清空
    var updated =
        as(
            actorA,
            "channel.operation.update",
            () ->
                service.updateWatch(
                    watch.watchId().toString(),
                    new SaveHijackerWatchRequest(
                        l.id(), null, null, new BigDecimal("30.00"), "USD",
                        new BigDecimal("26.00"), null, "PAUSED", "改过了")));
    assertThat(updated.referencePrice()).isEqualByComparingTo("30.00");
    assertThat(updated.priceGapRatioThreshold()).as("不给 = 清空").isNull();
    assertThat(updated.status()).isEqualTo("PAUSED");

    // 🔴 暂停后不接受新的跟卖信息（暂停就是"不收"，含糊地收下会让人以为还在盯）
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    ingest(
                        actorA,
                        ingestReq(
                            watch.watchId(), "k-paused", at(1, 10),
                            rec("SELLER-1", "OTHER-SHOP", "别的店", "20.00")))))
        .as("暂停的监控对象必须拒收")
        .isNotNull();

    // 🔴 恢复 ACTIVE 后又可以收
    as(
        actorA,
        "channel.operation.update",
        () ->
            service.updateWatch(
                watch.watchId().toString(),
                new SaveHijackerWatchRequest(
                    l.id(), null, null, new BigDecimal("25.00"), "USD",
                    new BigDecimal("21.00"), new BigDecimal("0.10"), "ACTIVE", "恢复")));
    var result =
        ingest(
            actorA,
            ingestReq(
                watch.watchId(), "k-after-resume", at(1, 10),
                rec("SELLER-1", "OTHER-SHOP", "别的店", "20.00")));
    assertThat(result.appliedCount()).isEqualTo(1);

    // 🔴 删除：回执写明一并删掉了多少历史
    var deleted = as(actorA, "channel.operation.delete", () -> service.deleteWatch(watch.watchId().toString()));
    assertThat(deleted.deletedRecords()).isEqualTo(1);
    assertThat(deleted.deletedAlerts()).isEqualTo(1);
    assertThat(scalar("select count(*) from listing_hijacker_watch where id='" + watch.watchId() + "'"))
        .as("监控对象真的删掉了")
        .isEqualTo(0L);
  }

  @Test
  @DisplayName("4.5.2.1 🔴 监控对象校验：比例阈值越界 / 参考价没币种 ⇒ 422")
  void watchValidationRejectsBadInput() {
    UUID shop = seedShop(a, "HJ-VALID");
    var l = seedListing(actorA, shop, "HJ-A2", "校验商品", "ON_SALE");

    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    as(
                        actorA,
                        "channel.operation.create",
                        () ->
                            service.createWatch(
                                new SaveHijackerWatchRequest(
                                    l.id(), null, null, new BigDecimal("25.00"), "USD",
                                    null, new BigDecimal("1.5"), "ACTIVE", "越界")))))
        .as("比例阈值必须落在 [0,1)")
        .isNotNull();

    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    as(
                        actorA,
                        "channel.operation.create",
                        () ->
                            service.createWatch(
                                new SaveHijackerWatchRequest(
                                    l.id(), null, null, new BigDecimal("25.00"), "",
                                    null, null, "ACTIVE", "没币种")))))
        .as("给了参考价就必须给币种")
        .isNotNull();

    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    as(
                        actorA,
                        "channel.operation.create",
                        () ->
                            service.createWatch(
                                new SaveHijackerWatchRequest(
                                    l.id(), null, null, null, "USD", null, null, "PENDING", "未知状态")))))
        .as("未知状态必须被拒")
        .isNotNull();
  }

  // ==================================================================
  // 2. 🔴 跟卖记录入库
  // ==================================================================

  @Test
  @DisplayName("4.5.2.1 🔴 跟卖记录真的落 PostgreSQL，数值逐字相等")
  void recordsLandInPostgresWithExactValues() {
    UUID shop = seedShop(a, "HJ-REC");
    var l = seedListing(actorA, shop, "HJ-B1", "跟卖商品", "ON_SALE");
    var watch = createWatch(actorA, l.id(), "25.00", "21.00", "0.10");

    OffsetDateTime observed = at(2, 9);
    var result =
        ingest(
            actorA,
            ingestReq(
                watch.watchId(), "k-rec-1", observed,
                rec("SELLER-9", "SHOP-EXT-9", "外部店九", "20.00")));
    assertThat(result.status()).isEqualTo("APPLIED");
    assertThat(result.receivedCount()).isEqualTo(1);
    assertThat(result.appliedCount()).isEqualTo(1);
    assertThat(result.skippedCount()).isZero();
    assertThat(result.replayed()).isFalse();

    var page =
        as(
            actorA,
            "channel.operation.read",
            () -> service.records(null, null, null, null, null, null, null, 0, 50));
    assertThat(page.items()).hasSize(1);
    var row = page.items().get(0);
    assertThat(row.hijackerSellerId()).isEqualTo("SELLER-9");
    assertThat(row.hijackerShopIdentifier()).isEqualTo("SHOP-EXT-9");
    assertThat(row.hijackerShopName()).isEqualTo("外部店九");
    assertThat(row.hijackerPrice()).isEqualByComparingTo("20.00");
    assertThat(row.currency()).isEqualTo("USD");
    assertThat(row.hijackerKind()).isEqualTo("EXTERNAL");
    assertThat(row.source()).isEqualTo("PLATFORM");
    assertThat(row.ingestChannel()).isEqualTo("API");
    assertThat(row.platformCode()).isEqualTo("AMAZON");
    assertThat(row.observedAt()).isEqualTo(observed);
    assertThat(row.batchIdempotencyKey()).isEqualTo("k-rec-1");
    // 🔴 价格差与价格差比例是**现算**的
    assertThat(row.priceGap()).isEqualByComparingTo("5.00");
    assertThat(row.priceGapRatio()).isEqualByComparingTo("0.2");
  }

  @Test
  @DisplayName("4.5.2.1 🔴 幂等：同一批次重投 ⇒ 整批不落库（replayed=true）")
  void batchReplayIsIdempotent() {
    UUID shop = seedShop(a, "HJ-IDEM");
    var l = seedListing(actorA, shop, "HJ-B2", "幂等商品", "ON_SALE");
    var watch = createWatch(actorA, l.id(), "25.00", null, null);

    var first =
        ingest(
            actorA,
            ingestReq(
                watch.watchId(), "k-idem", at(3, 10),
                rec("SELLER-1", "SHOP-EXT-1", "外部店一", "20.00"),
                rec("SELLER-2", "SHOP-EXT-2", "外部店二", "19.00")));
    assertThat(first.appliedCount()).isEqualTo(2);
    assertThat(first.replayed()).isFalse();

    var second =
        ingest(
            actorA,
            ingestReq(
                watch.watchId(), "k-idem", at(3, 10),
                rec("SELLER-1", "SHOP-EXT-1", "外部店一", "20.00"),
                rec("SELLER-2", "SHOP-EXT-2", "外部店二", "19.00")));
    assertThat(second.replayed()).as("同一批次重投必须整批跳过").isTrue();
    assertThat(second.appliedCount()).isZero();
    assertThat(second.status()).isEqualTo("REPLAYED");

    assertThat(
            scalar(
                "select count(*) from listing_hijacker_record where watch_id='" + watch.watchId() + "'"))
        .as("重投不能多落一条")
        .isEqualTo(2L);
  }

  @Test
  @DisplayName("4.5.2.1 🔴 跳过要逐条写明原因：同 (监控对象, 跟卖者, 时刻) 已有记录")
  void duplicateObservationIsSkippedWithReason() {
    UUID shop = seedShop(a, "HJ-DUP");
    var l = seedListing(actorA, shop, "HJ-B3", "重复商品", "ON_SALE");
    var watch = createWatch(actorA, l.id(), "25.00", null, null);

    ingest(
        actorA,
        ingestReq(
            watch.watchId(), "k-dup-1", at(4, 10),
            rec("SELLER-7", "SHOP-EXT-7", "外部店七", "20.00")));

    // 另一个批次键、同一时刻、同一跟卖者 ⇒ UNIQUE 约束命中 ⇒ 跳过并写明原因
    var second =
        ingest(
            actorA,
            ingestReq(
                watch.watchId(), "k-dup-2", at(4, 10),
                rec("SELLER-7", "SHOP-EXT-7", "外部店七", "18.00"),
                rec("SELLER-8", "SHOP-EXT-8", "外部店八", "17.00")));
    assertThat(second.appliedCount()).isEqualTo(1);
    assertThat(second.skippedCount()).isEqualTo(1);
    assertThat(second.skipped()).hasSize(1);
    assertThat(second.skipped().get(0)).contains("SELLER-7").contains("已有");
    assertThat(second.status()).isEqualTo("PARTIAL");
  }

  @Test
  @DisplayName("4.5.2.1 🔴 跟卖者是被监控 listing 自己的店铺 ⇒ 那不是跟卖，跳过并写明原因")
  void hijackerOnWatchedListingOwnShopIsSkipped() {
    UUID shop = seedShop(a, "HJ-SELF", "SELF-SHOP-1");
    var l = seedListing(actorA, shop, "HJ-B4", "自家商品", "ON_SALE");
    var watch = createWatch(actorA, l.id(), "25.00", null, null);

    var result =
        ingest(
            actorA,
            ingestReq(
                watch.watchId(), "k-self", at(5, 10),
                rec("SELLER-SELF", "SELF-SHOP-1", "自家店铺", "20.00")));
    assertThat(result.appliedCount()).isZero();
    assertThat(result.skippedCount()).isEqualTo(1);
    assertThat(result.skipped().get(0)).contains("自己所在店铺");
    assertThat(
            scalar(
                "select count(*) from listing_hijacker_record where watch_id='" + watch.watchId() + "'"))
        .isEqualTo(0L);
  }

  @Test
  @DisplayName("4.5.2.1 🔴 己方其他店铺跟卖：性质是**算出来的**，并判为异常跟卖")
  void ownShopHijackIsDetectedAndAlerted() {
    UUID ownShop = seedShop(a, "HJ-OWN-A", "OWN-SHOP-A");
    seedShop(a, "HJ-OWN-B", "OWN-SHOP-B");
    var l = seedListing(actorA, ownShop, "HJ-B5", "自家主商品", "ON_SALE");
    var watch = createWatch(actorA, l.id(), "25.00", null, null);

    // 己方另一个店铺来跟卖 —— 价格**并不低**（25.00，自家参考价一样）
    var result =
        ingest(
            actorA,
            ingestReq(
                watch.watchId(), "k-own", at(6, 10),
                rec("SELLER-OWN", "OWN-SHOP-B", "自家另一个店", "25.00")));
    assertThat(result.appliedCount()).isEqualTo(1);
    assertThat(result.alerts()).as("自家店铺跟自家 listing 必须告警").hasSize(1);
    var alert = result.alerts().get(0);
    assertThat(alert.hijackerKind()).isEqualTo("OWN_SHOP");
    assertThat(alert.alertType()).isEqualTo("ANOMALOUS_HIJACK");
    assertThat(alert.severity()).isEqualTo("HIGH");
    assertThat(alert.reason()).contains("己方其他店铺");

    // 🔴 性质是按**平台侧店铺标识**匹配出来的，调用方不能自己声明
    var page =
        as(
            actorA,
            "channel.operation.read",
            () -> service.records(null, null, null, "OWN_SHOP", null, null, null, 0, 50));
    assertThat(page.items()).hasSize(1);
    assertThat(page.items().get(0).hijackerKind()).isEqualTo("OWN_SHOP");
  }

  // ==================================================================
  // 3. 🔴 判定规则
  // ==================================================================

  @Test
  @DisplayName("4.5.2.1 🔴 恶意压价：跟卖价 ≤ 阈值 ⇒ MALICIOUS_PRICE_CUT / HIGH")
  void maliciousPriceCutIsAlerted() {
    UUID shop = seedShop(a, "HJ-CUT");
    var l = seedListing(actorA, shop, "HJ-C1", "压价商品", "ON_SALE");
    var watch = createWatch(actorA, l.id(), "25.00", "21.00", "0.10");

    var result =
        ingest(
            actorA,
            ingestReq(
                watch.watchId(), "k-cut", at(7, 10),
                rec("SELLER-CUT", "SHOP-EXT-C", "压价店", "20.00")));
    assertThat(result.alerts()).hasSize(1);
    var alert = result.alerts().get(0);
    assertThat(alert.alertType()).isEqualTo("MALICIOUS_PRICE_CUT");
    assertThat(alert.severity()).isEqualTo("HIGH");
    assertThat(alert.observedPrice()).isEqualByComparingTo("20.00");
    assertThat(alert.reason()).contains("恶意压价阈值");
    // 🔴 价格差比例是现算的：5 ÷ 25 = 0.2
    assertThat(alert.priceGapRatio()).isEqualByComparingTo("0.2");
  }

  @Test
  @DisplayName("4.5.2.1 🔴 异常跟卖：价格差比例 > 阈值 ⇒ ANOMALOUS_HIJACK / MEDIUM")
  void anomalousHijackByPriceGapIsAlerted() {
    UUID shop = seedShop(a, "HJ-GAP");
    var l = seedListing(actorA, shop, "HJ-C2", "价差商品", "ON_SALE");
    var watch = createWatch(actorA, l.id(), "25.00", null, "0.10");

    var result =
        ingest(
            actorA,
            ingestReq(
                watch.watchId(), "k-gap", at(8, 10),
                rec("SELLER-GAP", "SHOP-EXT-G", "价差店", "20.00")));
    assertThat(result.alerts()).hasSize(1);
    var alert = result.alerts().get(0);
    assertThat(alert.alertType()).isEqualTo("ANOMALOUS_HIJACK");
    assertThat(alert.severity()).isEqualTo("MEDIUM");
    assertThat(alert.reason()).contains("低").contains("20.00%");
    assertThat(alert.priceGapRatio()).isEqualByComparingTo("0.2");
  }

  @Test
  @DisplayName("4.5.2.1 🔴 阈值不给 ⇒ 不判定（不猜一个默认门槛）")
  void withoutThresholdsNoAlertIsRaised() {
    UUID shop = seedShop(a, "HJ-NOTH");
    var l = seedListing(actorA, shop, "HJ-C3", "无阈值商品", "ON_SALE");
    var watch = createWatch(actorA, l.id(), "25.00", null, null);

    var result =
        ingest(
            actorA,
            ingestReq(
                watch.watchId(), "k-noth", at(9, 10),
                rec("SELLER-N", "SHOP-EXT-N", "普通店", "20.00")));
    assertThat(result.appliedCount()).isEqualTo(1);
    assertThat(result.alerts()).as("没配阈值就不该有预警").isEmpty();

    var page =
        as(
            actorA,
            "channel.operation.read",
            () -> service.alerts(null, null, null, null, null, null, 0, 50));
    assertThat(page.total()).isZero();
  }

  @Test
  @DisplayName("4.5.2.1 🔴 分母为 0 时价格差比例是 null（不是 0、也不是 100）")
  void priceGapRatioIsNullWhenReferencePriceIsZero() {
    UUID shop = seedShop(a, "HJ-ZERO");
    var l = seedListing(actorA, shop, "HJ-C4", "零参考价商品", "ON_SALE");
    var watch = createWatch(actorA, l.id(), "0.00", null, null);

    ingest(
        actorA,
        ingestReq(
            watch.watchId(), "k-zero", at(10, 10),
            rec("SELLER-Z", "SHOP-EXT-Z", "零参考价店", "20.00")));

    var page =
        as(
            actorA,
            "channel.operation.read",
            () -> service.records(null, null, null, null, null, null, null, 0, 50));
    var row = page.items().get(0);
    assertThat(row.referencePrice()).isEqualByComparingTo("0.00");
    assertThat(row.priceGapRatio()).as("分母为 0 ⇒ null，不是 0 也不是 100").isNull();
    assertThat(row.priceGap()).isEqualByComparingTo("-20.00");

    // 对比视图里同样是 null
    var comparison =
        as(
            actorA,
            "channel.operation.read",
            () -> service.comparison(watch.watchId().toString(), "2026-10-01", "2026-10-31", 0, 50));
    assertThat(comparison.items()).hasSize(1);
    assertThat(comparison.items().get(0).priceGapRatio()).isNull();
    assertThat(comparison.maxPriceGapRatio()).isNull();
  }

  // ==================================================================
  // 4. 🔴 预警记录
  // ==================================================================

  @Test
  @DisplayName("4.5.2.1 🔴 预警：一条跟卖记录最多一条预警；标记已阅是幂等的")
  void alertsAreListedAndAcknowledged() {
    UUID shop = seedShop(a, "HJ-ALERT");
    var l = seedListing(actorA, shop, "HJ-D1", "预警商品", "ON_SALE");
    var watch = createWatch(actorA, l.id(), "25.00", "21.00", null);

    var result =
        ingest(
            actorA,
            ingestReq(
                watch.watchId(), "k-alert", at(11, 10),
                rec("SELLER-A", "SHOP-EXT-A", "预警店", "20.00")));
    assertThat(result.alerts()).hasSize(1);
    var alertId = result.alerts().get(0).alertId();

    var page =
        as(
            actorA,
            "channel.operation.read",
            () -> service.alerts(null, null, null, null, null, null, 0, 50));
    assertThat(page.total()).isEqualTo(1);
    assertThat(page.openCount()).isEqualTo(1);
    assertThat(page.items().get(0).status()).isEqualTo("OPEN");
    assertThat(page.items().get(0).alertType()).isEqualTo("MALICIOUS_PRICE_CUT");

    // 🔴 标记已阅
    var acked =
        as(
            actorA,
            "channel.operation.update",
            () ->
                service.acknowledgeAlert(
                    alertId.toString(), new AcknowledgeHijackerAlertRequest("已知悉")));
    assertThat(acked.status()).isEqualTo("ACKNOWLEDGED");
    assertThat(acked.acknowledgedAt()).isNotNull();

    // 🔴 再阅一次是幂等的（不报错、不重复写）
    var again =
        as(
            actorA,
            "channel.operation.update",
            () ->
                service.acknowledgeAlert(
                    alertId.toString(), new AcknowledgeHijackerAlertRequest("再点一次")));
    assertThat(again.status()).isEqualTo("ACKNOWLEDGED");
    assertThat(again.acknowledgedAt()).isEqualTo(acked.acknowledgedAt());

    // 🔴 一条跟卖记录最多一条预警（DB 约束）
    assertThat(
            scalar(
                "select count(*) from listing_hijacker_alert where record_id='"
                    + result.alerts().get(0).recordId() + "'"))
        .isEqualTo(1L);
  }

  @Test
  @DisplayName("4.5.2.1 🔴 DB 闸门：已阅的预警判定结论不可再改")
  void acknowledgedAlertIsImmutable() {
    UUID shop = seedShop(a, "HJ-IMM");
    var l = seedListing(actorA, shop, "HJ-D2", "不可改商品", "ON_SALE");
    var watch = createWatch(actorA, l.id(), "25.00", "21.00", null);

    var result =
        ingest(
            actorA,
            ingestReq(
                watch.watchId(), "k-imm", at(12, 10),
                rec("SELLER-I", "SHOP-EXT-I", "不可改店", "20.00")));
    var alertId = result.alerts().get(0).alertId();
    as(
        actorA,
        "channel.operation.update",
        () -> service.acknowledgeAlert(alertId.toString(), new AcknowledgeHijackerAlertRequest("阅")));

    String state =
        sqlState(
            "update listing_hijacker_alert set observed_price=1.00 where id='" + alertId + "'");
    assertThat(state).as("已阅之后判定结论不可再改").isEqualTo("23514");
  }

  // ==================================================================
  // 5. 🔴 对比视图
  // ==================================================================

  @Test
  @DisplayName("4.5.2.1 🔴 对比视图：服务端聚合（一个跟卖者一行）+ 真分页")
  void comparisonViewIsServerAggregatedAndPaged() {
    UUID shop = seedShop(a, "HJ-CMP");
    var l = seedListing(actorA, shop, "HJ-E1", "对比商品", "ON_SALE");
    var watch = createWatch(actorA, l.id(), "25.00", null, "0.10");

    // 卖一：两个时刻的两次观察（12.00 / 10.00）
    ingest(
        actorA,
        ingestReq(
            watch.watchId(), "k-cmp-1", at(13, 8),
            rec("SELLER-X", "SHOP-X", "X 店", "12.00")));
    ingest(
        actorA,
        ingestReq(
            watch.watchId(), "k-cmp-2", at(13, 18),
            rec("SELLER-X", "SHOP-X", "X 店", "10.00"),
            rec("SELLER-Y", "SHOP-Y", "Y 店", "15.00")));

    var comparison =
        as(
            actorA,
            "channel.operation.read",
            () -> service.comparison(watch.watchId().toString(), "2026-10-01", "2026-10-31", 0, 50));
    assertThat(comparison.hasData()).isTrue();
    assertThat(comparison.total()).as("两个跟卖者 ⇒ 两行").isEqualTo(2);
    assertThat(comparison.hijackerCount()).isEqualTo(2);
    assertThat(comparison.observationCount()).isEqualTo(3);
    assertThat(comparison.items()).hasSize(2);

    // 🔴 跟卖最狠的排前面（价格差比例最大的那个）
    var x = comparison.items().get(0);
    assertThat(x.hijackerSellerId()).isEqualTo("SELLER-X");
    assertThat(x.observationCount()).isEqualTo(2);
    assertThat(x.latestPrice()).as("最近一次观察到的价").isEqualByComparingTo("10.00");
    assertThat(x.minPrice()).isEqualByComparingTo("10.00");
    assertThat(x.maxPrice()).isEqualByComparingTo("12.00");
    assertThat(x.avgPrice()).isEqualByComparingTo("11.00");
    assertThat(x.referencePrice()).isEqualByComparingTo("25.00");
    assertThat(x.priceGap()).isEqualByComparingTo("15.00");
    assertThat(x.priceGapRatio()).as("15 ÷ 25 = 0.6").isEqualByComparingTo("0.6");

    var y = comparison.items().get(1);
    assertThat(y.hijackerSellerId()).isEqualTo("SELLER-Y");
    assertThat(y.priceGapRatio()).isEqualByComparingTo("0.4");

    // 🔴 摘要也是服务端聚合出来的
    assertThat(comparison.lowestHijackerPrice()).isEqualByComparingTo("10.00");
    assertThat(comparison.averageHijackerPrice()).as("(12+10+15) ÷ 3 = 12.3333")
        .isEqualByComparingTo("12.3333");
    assertThat(comparison.maxPriceGapRatio()).isEqualByComparingTo("0.6");

    // 🔴 真分页：翻页能翻到不同的数据
    var page2 =
        as(
            actorA,
            "channel.operation.read",
            () -> service.comparison(watch.watchId().toString(), "2026-10-01", "2026-10-31", 1, 1));
    assertThat(page2.items()).hasSize(1);
    assertThat(page2.items().get(0).hijackerSellerId())
        .isNotEqualTo(x.hijackerSellerId());
    assertThat(page2.total()).isEqualTo(2);
  }

  @Test
  @DisplayName("4.5.2.1 🔴 对比视图没有数据时是空态（不是假数据）")
  void comparisonEmptyStateIsHonest() {
    UUID shop = seedShop(a, "HJ-CMP-E");
    var l = seedListing(actorA, shop, "HJ-E2", "空对比商品", "ON_SALE");
    var watch = createWatch(actorA, l.id(), "25.00", null, null);

    var comparison =
        as(
            actorA,
            "channel.operation.read",
            () -> service.comparison(watch.watchId().toString(), "2026-10-01", "2026-10-31", 0, 50));
    assertThat(comparison.hasData()).isFalse();
    assertThat(comparison.items()).isEmpty();
    assertThat(comparison.emptyReason()).isNotBlank();
    assertThat(comparison.emptyReason()).contains("抓取");
  }

  // ==================================================================
  // 6. 🔴 来源标注与能力声明
  // ==================================================================

  @Test
  @DisplayName("4.5.2.1 🔴 全程标注「仅供参考、不构成处置结论」")
  void referenceOnlyLabellingIsPresentEverywhere() {
    UUID shop = seedShop(a, "HJ-LBL");
    var l = seedListing(actorA, shop, "HJ-F1", "标注商品", "ON_SALE");
    var watch = createWatch(actorA, l.id(), "25.00", "21.00", null);
    ingest(
        actorA,
        ingestReq(
            watch.watchId(), "k-lbl", at(14, 10),
            rec("SELLER-L", "SHOP-EXT-L", "标注店", "20.00")));

    var records =
        as(
            actorA,
            "channel.operation.read",
            () -> service.records(null, null, null, null, null, null, null, 0, 50));
    assertThat(records.dataSource()).isEqualTo("PLATFORM_SCRAPED");
    assertThat(records.sourceNote()).contains("仅供参考").contains("不构成处置结论");

    var alerts =
        as(
            actorA,
            "channel.operation.read",
            () -> service.alerts(null, null, null, null, null, null, 0, 50));
    assertThat(alerts.dataSource()).isEqualTo("PLATFORM_SCRAPED");
    assertThat(alerts.sourceNote()).contains("仅供参考");
    assertThat(alerts.items().get(0).dataSource()).isEqualTo("PLATFORM_SCRAPED");

    var comparison =
        as(
            actorA,
            "channel.operation.read",
            () -> service.comparison(watch.watchId().toString(), "2026-10-01", "2026-10-31", 0, 50));
    assertThat(comparison.dataSource()).isEqualTo("PLATFORM_SCRAPED");
    assertThat(comparison.sourceNote()).contains("仅供参考");

    var watches =
        as(actorA, "channel.operation.read", () -> service.watches(null, null, null, null, 0, 50));
    assertThat(watches.dataSource()).isEqualTo("PLATFORM_SCRAPED");
    assertThat(watches.sourceNote()).contains("仅供参考");
  }

  @Test
  @DisplayName("4.5.2.1 🔴 capabilities：16 / 1 / 02 / 05 未交付 ⇒ available=false + ownerModule")
  void capabilitiesDeclareUndeliveredSegments() {
    var caps = service.capabilities();
    var byDimension = new java.util.HashMap<String, ListingHijackerCapabilityDimension>();
    for (var d : caps.dimensions()) {
      byDimension.put(d.dimension(), d);
    }

    var enforcement = byDimension.get("brand_protection_enforcement");
    assertThat(enforcement).as("16 合规品牌保护必须显式声明").isNotNull();
    assertThat(enforcement.available()).isFalse();
    assertThat(enforcement.ownerModule()).contains("16");
    assertThat(enforcement.impact()).isNotBlank();

    var notification = byDimension.get("workbench_alert_notification");
    assertThat(notification).as("1 工作台预警必须显式声明").isNotNull();
    assertThat(notification.available()).isFalse();
    assertThat(notification.ownerModule()).contains("1");
    assertThat(notification.impact()).isNotBlank();

    var scheduler = byDimension.get("real_hijack_crawl_scheduler");
    assertThat(scheduler.available()).isFalse();
    assertThat(scheduler.ownerModule()).contains("02");

    var ownPrice = byDimension.get("authoritative_own_price");
    assertThat(ownPrice.available()).isFalse();
    assertThat(ownPrice.ownerModule()).contains("05");

    // 已交付的那几段必须是 true
    assertThat(byDimension.get("hijack_watch_management").available()).isTrue();
    assertThat(byDimension.get("hijack_record_ingest").available()).isTrue();
    assertThat(byDimension.get("own_shop_detection").available()).isTrue();
    assertThat(byDimension.get("anomaly_detection").available()).isTrue();
    assertThat(byDimension.get("alert_record").available()).isTrue();
    assertThat(byDimension.get("price_comparison_view").available()).isTrue();
    assertThat(byDimension.get("reference_only_labeling").available()).isTrue();

    // 🔴 处置动作的声明必须在能力表里写明
    assertThat(caps.actionsNote()).contains("不提供").contains("平台侧");
    assertThat(caps.ruleDefinitions()).contains("分母为 0").contains("null");
    assertThat(caps.relationNote()).contains("16").contains("1");
  }

  // ==================================================================
  // 7. 🔴 DB 级闸门
  // ==================================================================

  @Test
  @DisplayName("4.5.2.1 🔴 DB 闸门：跟卖记录的商品必须与它的监控对象一致")
  void recordMustBelongToItsWatchListing() {
    UUID shop = seedShop(a, "HJ-GUARD");
    var l1 = seedListing(actorA, shop, "HJ-G1", "闸门商品一", "ON_SALE");
    var l2 = seedListing(actorA, shop, "HJ-G2", "闸门商品二", "ON_SALE");
    var watch = createWatch(actorA, l1.id(), "25.00", null, null);

    String state =
        sqlState(
            "insert into listing_hijacker_record (id,tenant_id,watch_id,listing_id,"
                + "hijacker_seller_id,hijacker_shop_identifier,hijacker_price,observed_at,"
                + "batch_idempotency_key) values (gen_random_uuid(),'" + a + "','"
                + watch.watchId() + "','" + l2.id() + "','S-G','SHOP-G','20.00',"
                + "'2026-10-20 10:00:00+00','k-guard')");
    assertThat(state).as("跟卖记录的商品必须与监控对象一致").isEqualTo("23514");
  }

  @Test
  @DisplayName("4.5.2.1 🔴 DB 闸门：跨租户挂 listing 在物理上不可能（复合 FK）")
  void crossTenantListingReferenceIsImpossible() {
    UUID shopA = seedShop(a, "HJ-XA");
    UUID shopB = seedShop(b, "HJ-XB");
    var listingA = seedListing(actorA, shopA, "HJ-X1", "A 的商品", "ON_SALE");
    var listingB = seedListing(actorB, shopB, "HJ-X2", "B 的商品", "ON_SALE");
    var watchA = createWatch(actorA, listingA.id(), "25.00", null, null);

    // 用 A 的租户 + A 的监控对象，挂 B 的商品 ⇒ 必须被拒
    String state =
        sqlState(
            "insert into listing_hijacker_record (id,tenant_id,watch_id,listing_id,"
                + "hijacker_seller_id,hijacker_shop_identifier,hijacker_price,observed_at,"
                + "batch_idempotency_key) values (gen_random_uuid(),'" + a + "','"
                + watchA.watchId() + "','" + listingB.id() + "','S-X','SHOP-X','20.00',"
                + "'2026-10-20 10:00:00+00','k-xt')");
    assertThat(state).as("跨租户挂引用必须被拒").isIn("23503", "23514");

    // 🔴 复合外键确实带 tenant_id
    String fk =
        String.valueOf(
            scalar(
                "select pg_get_constraintdef(oid) from pg_constraint"
                    + " where conrelid='listing_hijacker_record'::regclass and contype='f'"
                    + " and pg_get_constraintdef(oid) like '%(tenant_id, listing_id)%'"));
    assertThat(fk).contains("FOREIGN KEY (tenant_id, listing_id)");
  }

  @Test
  @DisplayName("4.5.2.1 🔴 三张新表都是 FORCE RLS")
  void allTablesForceRls() {
    for (String table :
        List.of("listing_hijacker_watch", "listing_hijacker_record", "listing_hijacker_alert")) {
      assertThat(String.valueOf(scalar(
              "select relforcerowsecurity::text from pg_class where relname='" + table + "'")))
          .as(table + " 必须 FORCE RLS")
          .isEqualTo("true");
    }
  }

  // ==================================================================
  // 8. 🔴 时间往返 / HTTP 端点 / 真分页
  // ==================================================================

  @Test
  @DisplayName("4.5.2.1 🔴 timestamptz 往返：发现时间写回值逐字相等（+08:00 非 UTC 偏移）")
  void observedAtRoundTripsExactly() {
    UUID shop = seedShop(a, "HJ-TIME");
    var l = seedListing(actorA, shop, "HJ-H1", "时间商品", "ON_SALE");
    var watch = createWatch(actorA, l.id(), "25.00", null, null);

    OffsetDateTime observed = OffsetDateTime.of(2026, 3, 14, 15, 9, 26, 0, ZoneOffset.ofHours(8));
    ingest(
        actorA,
        ingestReq(
            watch.watchId(), "k-time", observed,
            rec("SELLER-T", "SHOP-EXT-T", "时间店", "20.00")));

    Object stored =
        scalar(
            "select to_char(observed_at at time zone 'Asia/Shanghai','YYYY-MM-DD HH24:MI:SS')"
                + " from listing_hijacker_record where watch_id='" + watch.watchId() + "'");
    assertThat(String.valueOf(stored)).isEqualTo("2026-03-14 15:09:26");
    Object epoch =
        scalar(
            "select extract(epoch from observed_at)::bigint::text from listing_hijacker_record"
                + " where watch_id='" + watch.watchId() + "'");
    assertThat(String.valueOf(epoch)).isEqualTo(String.valueOf(observed.toInstant().getEpochSecond()));
  }

  @Test
  @DisplayName("4.5.2.1 🔴 真分页：total 是服务端 count 出来的，翻页翻到不同的数据")
  void paginationIsReal() {
    UUID shop = seedShop(a, "HJ-PAGE");
    var l = seedListing(actorA, shop, "HJ-H2", "分页商品", "ON_SALE");
    var watch = createWatch(actorA, l.id(), "25.00", null, null);

    for (int i = 1; i <= 5; i++) {
      ingest(
          actorA,
          ingestReq(
              watch.watchId(), "k-page-" + i, at(15, i),
              rec("SELLER-P" + i, "SHOP-EXT-P" + i, "分页店" + i, "20.00")));
    }

    var first =
        as(
            actorA,
            "channel.operation.read",
            () -> service.records(null, null, null, null, null, null, null, 0, 2));
    assertThat(first.total()).isEqualTo(5);
    assertThat(first.items()).hasSize(2);

    var second =
        as(
            actorA,
            "channel.operation.read",
            () -> service.records(null, null, null, null, null, null, null, 1, 2));
    assertThat(second.total()).isEqualTo(5);
    assertThat(second.items()).hasSize(2);
    assertThat(second.items().get(0).recordId())
        .as("翻到第二页必须翻到不同的数据")
        .isNotEqualTo(first.items().get(0).recordId());
  }

  @Test
  @DisplayName("4.5.2.1 🔴 HTTP 端点：能力声明 / 监控对象 / 入库 / 记录 / 预警 / 对比")
  void httpEndpointsWork() throws Exception {
    UUID shop = seedShop(a, "HJ-HTTP");
    var l = seedListing(actorA, shop, "HJ-H3", "HTTP 商品", "ON_SALE");

    http.perform(get("/channel/listing-hijackers/capabilities").header("Authorization", bearerA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.dataSource").value("PLATFORM_SCRAPED"))
        .andExpect(jsonPath("$.actionsNote").value(org.hamcrest.Matchers.containsString("平台侧")));

    http.perform(get("/channel/listing-hijackers/filter-options").header("Authorization", bearerA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.platforms.length()").value(org.hamcrest.Matchers.greaterThan(0)));

    String watchBody =
        JSON.writeValueAsString(
            new SaveHijackerWatchRequest(
                l.id(), null, null, new BigDecimal("25.00"), "USD",
                new BigDecimal("21.00"), new BigDecimal("0.10"), "ACTIVE", "HTTP 建"));
    String watchJson =
        http.perform(post("/channel/listing-hijackers/watches")
                .header("Authorization", bearerA)
                .contentType("application/json")
                .content(watchBody))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.referencePrice").value(25.0))
            .andExpect(jsonPath("$.platformCode").value("AMAZON"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String watchId =
        new ObjectMapper().readTree(watchJson).get("watchId").asText();

    http.perform(post("/channel/listing-hijackers/records")
            .header("Authorization", bearerA)
            .contentType("application/json")
            .content(
                JSON.writeValueAsString(
                    ingestReq(
                        UUID.fromString(watchId), "k-http-1", at(16, 10),
                        rec("SELLER-H", "SHOP-EXT-H", "HTTP 店", "20.00")))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.replayed").value(false))
        .andExpect(jsonPath("$.appliedCount").value(1))
        .andExpect(jsonPath("$.alerts.length()").value(1));

    http.perform(get("/channel/listing-hijackers/records").header("Authorization", bearerA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.items[0].hijackerPrice").value(20.0))
        .andExpect(jsonPath("$.items[0].dataSource").value("PLATFORM_SCRAPED"));

    http.perform(get("/channel/listing-hijackers/alerts").header("Authorization", bearerA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.items[0].alertType").value("MALICIOUS_PRICE_CUT"));

    http.perform(get("/channel/listing-hijackers/comparison")
            .header("Authorization", bearerA)
            .param("watchId", watchId)
            .param("from", "2026-10-01")
            .param("to", "2026-10-31"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.hasData").value(true))
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.items[0].hijackerSellerId").value("SELLER-H"));

    // 🔴 未知监控对象 ⇒ 404
    http.perform(get("/channel/listing-hijackers/comparison")
            .header("Authorization", bearerA)
            .param("watchId", UUID.randomUUID().toString()))
        .andExpect(status().isNotFound());

    // 🔴 无会话 ⇒ 401
    http.perform(get("/channel/listing-hijackers/records")).andExpect(status().isUnauthorized());
  }
}
