package com.starcon.channel;

import static com.starcon.channel.ListingModels.*;
import static com.starcon.channel.ListingPerformanceModels.*;
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
 * 4.5.1.1 Listing 表现。
 *
 * <p>蓝图：「单个 listing 的表现明细：曝光、点击、转化率、销量、销售额、Session、退货率、评分变化，支持时间范围对比。输入：{@code listing_id}；平台表现数据同步结果；时间范围。输出：表现指标 + 趋势图；下钻到 13 产品表现。关系：上游 = 02 同步的平台流量数据；下游 = 13 产品表现、14 广告（关联投放）。<b>表现数据来自平台而非内部订单</b>，与内部订单口径不同，展示时需标注来源。」
 *
 * <p>本测试类钉住这几组事：
 *
 * <ol>
 *   <li><b>🔴🔴 数据真的落 PostgreSQL</b>：表现快照与同步结果都落表，数值<b>逐字相等</b>
 *       （不是只断言没抛异常）；比率是<b>区间合计的分子 ÷ 区间合计的分母</b>。
 *   <li><b>🔴🔴 分母为 0 时比率是 null</b>：不是 0、也不是 100 —— 报 0 会被读成
 *       「转化率 0%」，那是结论不是缺口。
 *   <li><b>🔴 时间范围对比</b>：本期 vs 上期逐指标给出变化量与变化率；上期默认取
 *       <b>紧邻的等长区间</b>；<b>上期为 0 时变化率是 null</b>（不是 0、不是 +∞）。
 *   <li><b>🔴 评分变化</b>：有评分的快照 ≥ 2 个时才有值；只有 1 个点是 null。
 *   <li><b>🔴 幂等与覆盖是两件事</b>：同一个幂等键再投 ⇒ 整批不落库（replayed=true）；
 *       平台修正同一天用<b>新</b>键 ⇒ 那一行被覆盖、覆盖次数 +1。
 *   <li><b>🔴 跳过要逐条写明原因</b>：统计日落在声明区间外的快照被跳过并写明理由，
 *       <b>不静默丢弃</b>。
 *   <li><b>🔴 趋势没有数据时是空数组</b>，不是一条平线（假曲线会让人以为"表现平稳"）。
 *   <li><b>🔴 数据来源必须能被展示</b>：每个响应都带 {@code dataSource=PLATFORM_REPORTED}
 *       与来源说明；capabilities 里 13 / 14 两段 available=false + ownerModule。
 *   <li><b>🔴 DB 层闸门</b>：快照引用的同步批次必须是**同一个商品**的（触发器）；
 *       同步批次落终态后不可再改（触发器）；跨租户挂 listing 在物理上不可能（复合 FK）。
 *   <li><b>🔴 timestamptz 往返</b>：口径区间写回值<b>逐字相等</b>（+08:00 非 UTC 偏移），
 *       不是只断言没抛异常 —— 偏 8 小时会让整条趋势错位。
 *   <li><b>真分页</b>：{@code total} 是服务端 count 出来的，翻页能翻到不同的数据。
 * </ol>
 *
 * <p><b>时刻与统计日一律钉死</b>，不在断言中途改时刻。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ListingPerformanceIntegrationTest {

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
  @Autowired ListingPerformanceService service;
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

  /** 以管理员连接跑一条会失败的 SQL，返回 SQLState（用于断言 DB 级闸门）。 */
  static String sqlState(String sql) {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(sql);
      return null;
    } catch (SQLException e) {
      return e.getSQLState();
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

  static PerformanceSnapshotInput snap(
      LocalDate date,
      String impressions,
      String clicks,
      String sessions,
      String units,
      String sales,
      String returnsCount,
      String returnAmount,
      String rating,
      String ratingCount) {
    return new PerformanceSnapshotInput(
        date,
        new BigDecimal(impressions),
        new BigDecimal(clicks),
        new BigDecimal(sessions),
        new BigDecimal(units),
        new BigDecimal(sales),
        "USD",
        new BigDecimal(returnsCount),
        new BigDecimal(returnAmount),
        rating == null ? null : new BigDecimal(rating),
        ratingCount == null ? null : new BigDecimal(ratingCount),
        Map.of());
  }

  static IngestPerformanceRequest req(
      UUID listingId, String key, LocalDate from, LocalDate to, List<PerformanceSnapshotInput> snaps) {
    return new IngestPerformanceRequest(
        listingId,
        null,
        null,
        "PLATFORM",
        "API",
        OffsetDateTime.of(from.atStartOfDay(), ZoneOffset.UTC),
        OffsetDateTime.of(to.plusDays(1).atStartOfDay(), ZoneOffset.UTC),
        from,
        to,
        key,
        "",
        Map.of("vendor", "acme"),
        snaps);
  }

  ListingPerformanceIngestResult ingest(
      AccessService.Access who, IngestPerformanceRequest request) {
    return as(who, "channel.operation.create", () -> service.ingest(request, user));
  }

  /** 本期三天的固定数据（曝光 3000 / 点击 150 / Session 600 / 销量 60 / 销售额 3000 / 退货 6）。 */
  static List<PerformanceSnapshotInput> currentPeriodSnaps() {
    return List.of(
        snap(LocalDate.of(2026, 10, 1), "1000", "50", "200", "20", "1000.00", "2", "100.00", "4.5", "10"),
        snap(LocalDate.of(2026, 10, 2), "2000", "100", "400", "40", "2000.00", "4", "200.00", "4.6", "20"),
        snap(LocalDate.of(2026, 10, 3), "0", "0", "0", "0", "0.00", "0", "0.00", null, null));
  }

  /** 上期三天的固定数据（曝光 3000 / 点击 120 / Session 300 / 销量 30 / 销售额 1500 / 退货 3）。 */
  static List<PerformanceSnapshotInput> previousPeriodSnaps() {
    return List.of(
        snap(LocalDate.of(2026, 9, 28), "1000", "40", "100", "10", "500.00", "1", "50.00", null, null),
        snap(LocalDate.of(2026, 9, 29), "1000", "40", "100", "10", "500.00", "1", "50.00", null, null),
        snap(LocalDate.of(2026, 9, 30), "1000", "40", "100", "10", "500.00", "1", "50.00", null, null));
  }

  // ==================================================================
  // 1. 🔴🔴 入库：数据真的落 PostgreSQL
  // ==================================================================

  @Test
  @DisplayName("4.5.1.1 🔴🔴 平台表现数据真的落 PostgreSQL，数值逐字相等")
  void snapshotsLandInPostgres() {
    UUID shop = seedShop(a, "LP-INGEST");
    var l = seedListing(actorA, shop, "LP-A1", "表现商品", "ON_SALE");

    var result = ingest(actorA,
        req(l.id(), "k-ingest-1", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 3),
            currentPeriodSnaps()));

    assertThat(result.replayed()).as("首次投递不是重放").isFalse();
    assertThat(result.status()).isEqualTo("APPLIED");
    assertThat(result.receivedCount()).isEqualTo(3);
    assertThat(result.appliedCount()).isEqualTo(3);
    assertThat(result.skippedCount()).isZero();

    Object stored = scalar(
        "select sum(impressions)::text from listing_performance_snapshot where listing_id='"
            + l.id() + "'");
    assertThat(String.valueOf(stored)).isEqualTo("3000.000000");
    Object sales = scalar(
        "select sum(sales_amount)::text from listing_performance_snapshot where listing_id='"
            + l.id() + "'");
    assertThat(String.valueOf(sales)).isEqualTo("3000.0000");
    Object rows =
        scalar(
            "select count(*)::text from listing_performance_snapshot where listing_id='" + l.id() + "'");
    assertThat(String.valueOf(rows)).isEqualTo("3");
    // 同步结果也真的落库
    Object syncs =
        scalar(
            "select count(*)::text from listing_performance_sync where listing_id='" + l.id() + "'");
    assertThat(String.valueOf(syncs)).isEqualTo("1");
  }

  @Test
  @DisplayName("4.5.1.1 🔴 幂等：同一个幂等键再投 ⇒ 整批不再落库")
  void replayIsIdempotent() {
    UUID shop = seedShop(a, "LP-IDEMP");
    var l = seedListing(actorA, shop, "LP-A2", "幂等商品", "ON_SALE");
    var request =
        req(l.id(), "k-idem-1", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 3),
            currentPeriodSnaps());

    var first = ingest(actorA, request);
    var second = ingest(actorA, request);

    assertThat(first.replayed()).isFalse();
    assertThat(second.replayed()).as("同一个幂等键再投必须是重放").isTrue();
    assertThat(second.syncId()).as("重放返回既有那条同步记录").isEqualTo(first.syncId());
    assertThat(second.status()).isEqualTo("APPLIED");

    Object rows =
        scalar(
            "select count(*)::text from listing_performance_snapshot where listing_id='"
                + l.id() + "'");
    assertThat(String.valueOf(rows)).as("重投不许产生第二份数据").isEqualTo("3");
    Object syncs =
        scalar(
            "select count(*)::text from listing_performance_sync where listing_id='" + l.id() + "'");
    assertThat(String.valueOf(syncs)).as("重投不许产生第二条同步记录").isEqualTo("1");
  }

  @Test
  @DisplayName("4.5.1.1 🔴 平台修正同一天：新的幂等键 ⇒ 那一行被覆盖、覆盖次数 +1")
  void correctedDayOverwritesSnapshot() {
    UUID shop = seedShop(a, "LP-CORRECT");
    var l = seedListing(actorA, shop, "LP-A3", "修正商品", "ON_SALE");

    ingest(actorA,
        req(l.id(), "k-corr-1", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 1),
            List.of(snap(LocalDate.of(2026, 10, 1), "1000", "50", "200", "20", "1000.00", "2", "100.00", "4.5", "10"))));
    ingest(actorA,
        req(l.id(), "k-corr-2", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 1),
            List.of(snap(LocalDate.of(2026, 10, 1), "1500", "80", "300", "30", "1500.00", "3", "150.00", "4.6", "12"))));

    Object rows =
        scalar(
            "select count(*)::text from listing_performance_snapshot where listing_id='"
                + l.id() + "'");
    assertThat(String.valueOf(rows)).as("同一天只能有一行").isEqualTo("1");
    Object revision =
        scalar(
            "select revision::text from listing_performance_snapshot where listing_id='"
                + l.id() + "'");
    assertThat(String.valueOf(revision)).as("覆盖次数必须 +1").isEqualTo("2");
    Object impressions =
        scalar(
            "select impressions::text from listing_performance_snapshot where listing_id='"
                + l.id() + "'");
    assertThat(String.valueOf(impressions)).as("后来者覆盖先来者").isEqualTo("1500.000000");
    // 两条同步记录都留着（同步结果是只追加的证据）
    Object syncs =
        scalar(
            "select count(*)::text from listing_performance_sync where listing_id='" + l.id() + "'");
    assertThat(String.valueOf(syncs)).isEqualTo("2");
  }

  @Test
  @DisplayName("4.5.1.1 🔴 统计日落在声明区间外 ⇒ 跳过并逐条写明原因（不静默丢弃）")
  void outOfRangeSnapshotsAreSkippedWithReason() {
    UUID shop = seedShop(a, "LP-SKIP");
    var l = seedListing(actorA, shop, "LP-A4", "越界商品", "ON_SALE");

    var result = ingest(actorA,
        req(l.id(), "k-skip-1", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 2),
            List.of(
                snap(LocalDate.of(2026, 10, 1), "1000", "50", "200", "20", "1000.00", "2", "100.00", "4.5", "10"),
                snap(LocalDate.of(2026, 9, 30), "999", "49", "199", "19", "900.00", "1", "90.00", null, null))));

    assertThat(result.status()).as("有跳过就是 PARTIAL").isEqualTo("PARTIAL");
    assertThat(result.receivedCount()).isEqualTo(2);
    assertThat(result.appliedCount()).isEqualTo(1);
    assertThat(result.skippedCount()).isEqualTo(1);
    assertThat(result.skipped()).hasSize(1);
    assertThat(result.skipped().get(0)).contains("2026-09-30").contains("之外");
    Object rows =
        scalar(
            "select count(*)::text from listing_performance_snapshot where listing_id='"
                + l.id() + "'");
    assertThat(String.valueOf(rows)).as("越界的那天不许入库").isEqualTo("1");
  }

  @Test
  @DisplayName("4.5.1.1 🔴 负数被拒（整批不落库）")
  void negativeMetricsAreRejected() {
    UUID shop = seedShop(a, "LP-NEG");
    var l = seedListing(actorA, shop, "LP-A5", "负数商品", "ON_SALE");

    var error =
        org.assertj.core.api.Assertions.catchThrowable(
            () ->
                ingest(actorA,
                    req(l.id(), "k-neg-1", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 1),
                        List.of(
                            snap(LocalDate.of(2026, 10, 1), "-1", "50", "200", "20", "1000.00", "2", "100.00", "4.5", "10")))));
    assertThat(error).isNotNull();
    assertThat(error.getMessage()).contains("不能是负数");
    Object rows =
        scalar(
            "select count(*)::text from listing_performance_snapshot where listing_id='"
                + l.id() + "'");
    assertThat(String.valueOf(rows)).as("整批被拒，不许落半批").isEqualTo("0");
  }

  // ==================================================================
  // 2. 🔴🔴 区间聚合与比率口径
  // ==================================================================

  @Test
  @DisplayName("4.5.1.1 🔴🔴 区间聚合：比率是「分子合计 ÷ 分母合计」，分母为 0 时是 null")
  void aggregationUsesSummedNumeratorOverSummedDenominator() {
    UUID shop = seedShop(a, "LP-AGG");
    var l = seedListing(actorA, shop, "LP-A6", "聚合商品", "ON_SALE");
    ingest(actorA,
        req(l.id(), "k-agg-1", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 3),
            currentPeriodSnaps()));

    var detail = as(actorA, "channel.operation.read",
        () -> service.detail(l.id().toString(), "2026-10-01", "2026-10-03", null, null));

    assertThat(detail.hasData()).isTrue();
    var m = detail.current();
    assertThat(m.impressions()).isEqualByComparingTo("3000");
    assertThat(m.clicks()).isEqualByComparingTo("150");
    assertThat(m.sessions()).isEqualByComparingTo("600");
    assertThat(m.unitsOrdered()).isEqualByComparingTo("60");
    assertThat(m.salesAmount()).isEqualByComparingTo("3000.0000");
    assertThat(m.returnsCount()).isEqualByComparingTo("6");
    assertThat(m.returnAmount()).isEqualByComparingTo("300.0000");
    // 150/3000 = 0.05（不是 (50/1000+100/2000)/2 那种日均值平均）
    assertThat(m.ctr()).isEqualByComparingTo("0.050000");
    // 60/600 = 0.1
    assertThat(m.conversionRate()).isEqualByComparingTo("0.100000");
    // 6/60 = 0.1
    assertThat(m.returnRate()).isEqualByComparingTo("0.100000");
    // 评分：期末 4.6、变化 4.6 − 4.5 = 0.1
    assertThat(m.rating()).isEqualByComparingTo("4.6");
    assertThat(m.ratingCount()).isEqualByComparingTo("20");
    assertThat(m.ratingChange()).isEqualByComparingTo("0.1");
    assertThat(m.snapshotCount()).isEqualTo(3);
    assertThat(m.currency()).isEqualTo("USD");
  }

  @Test
  @DisplayName("4.5.1.1 🔴🔴 分母为 0 ⇒ 比率是 null（不是 0、也不是 100）")
  void zeroDenominatorYieldsNullRate() {
    UUID shop = seedShop(a, "LP-ZERO");
    var l = seedListing(actorA, shop, "LP-A7", "零分母商品", "ON_SALE");
    ingest(actorA,
        req(l.id(), "k-zero-1", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 1),
            List.of(
                snap(LocalDate.of(2026, 10, 1), "0", "0", "0", "0", "0.00", "0", "0.00", null, null))));

    var detail = as(actorA, "channel.operation.read",
        () -> service.detail(l.id().toString(), "2026-10-01", "2026-10-01", null, null));

    var m = detail.current();
    assertThat(m.impressions()).isEqualByComparingTo("0");
    assertThat(m.ctr()).as("曝光为 0 ⇒ 点击率是 null（不是 0、不是 100）").isNull();
    assertThat(m.conversionRate()).as("Session 为 0 ⇒ 转化率是 null").isNull();
    assertThat(m.returnRate()).as("销量为 0 ⇒ 退货率是 null").isNull();
    assertThat(m.ratingChange()).as("只有 0 个有评分的快照 ⇒ 评分变化是 null").isNull();
  }

  @Test
  @DisplayName("4.5.1.1 🔴 评分变化：只有一个有评分的快照时是 null（不是 0）")
  void ratingChangeNeedsTwoPoints() {
    UUID shop = seedShop(a, "LP-RATING");
    var l = seedListing(actorA, shop, "LP-A8", "单点评分商品", "ON_SALE");
    ingest(actorA,
        req(l.id(), "k-rating-1", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 2),
            List.of(
                snap(LocalDate.of(2026, 10, 1), "1000", "50", "200", "20", "1000.00", "2", "100.00", "4.5", "10"),
                snap(LocalDate.of(2026, 10, 2), "1000", "50", "200", "20", "1000.00", "2", "100.00", null, null))));

    var detail = as(actorA, "channel.operation.read",
        () -> service.detail(l.id().toString(), "2026-10-01", "2026-10-02", null, null));

    assertThat(detail.current().rating()).isEqualByComparingTo("4.5");
    assertThat(detail.current().ratingChange())
        .as("只有一个有评分的快照 ⇒ 谈不上「变化」")
        .isNull();
  }

  // ==================================================================
  // 3. 🔴 时间范围对比
  // ==================================================================

  @Test
  @DisplayName("4.5.1.1 🔴🔴 本期 vs 上期：默认上期是紧邻的等长区间，逐指标变化率精确")
  void comparisonDefaultsToPrecedingEqualLengthWindow() {
    UUID shop = seedShop(a, "LP-CMP");
    var l = seedListing(actorA, shop, "LP-A9", "对比商品", "ON_SALE");
    ingest(actorA,
        req(l.id(), "k-cmp-cur", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 3),
            currentPeriodSnaps()));
    ingest(actorA,
        req(l.id(), "k-cmp-prev", LocalDate.of(2026, 9, 28), LocalDate.of(2026, 9, 30),
            previousPeriodSnaps()));

    var detail = as(actorA, "channel.operation.read",
        () -> service.detail(l.id().toString(), "2026-10-01", "2026-10-03", null, null));

    // 本期 3 天 ⇒ 上期默认是紧邻的等长区间 09-28 ~ 09-30
    assertThat(detail.comparePeriodFrom()).isEqualTo("2026-09-28");
    assertThat(detail.comparePeriodTo()).isEqualTo("2026-09-30");
    assertThat(detail.previous().impressions()).isEqualByComparingTo("3000");
    assertThat(detail.previous().clicks()).isEqualByComparingTo("120");
    assertThat(detail.previous().unitsOrdered()).isEqualByComparingTo("30");

    var byMetric = new java.util.HashMap<String, ListingPerformanceComparisonEntry>();
    for (var e : detail.comparison().entries()) {
      byMetric.put(e.metric(), e);
    }
    assertThat(byMetric).hasSize(12);

    var clicks = byMetric.get("clicks");
    assertThat(clicks.current()).isEqualByComparingTo("150");
    assertThat(clicks.previous()).isEqualByComparingTo("120");
    assertThat(clicks.change()).isEqualByComparingTo("30");
    assertThat(clicks.changeRate()).as("30 ÷ 120 = 0.25").isEqualByComparingTo("0.250000");
    assertThat(clicks.unit()).isEqualTo("COUNT");

    var ctr = byMetric.get("ctr");
    assertThat(ctr.current()).isEqualByComparingTo("0.050000");
    assertThat(ctr.previous()).isEqualByComparingTo("0.040000");
    assertThat(ctr.changeRate()).as("(0.05 − 0.04) ÷ 0.04 = 0.25").isEqualByComparingTo("0.250000");
    assertThat(ctr.unit()).isEqualTo("RATE");

    var sales = byMetric.get("salesAmount");
    assertThat(sales.current()).isEqualByComparingTo("3000.0000");
    assertThat(sales.previous()).isEqualByComparingTo("1500.0000");
    assertThat(sales.change()).isEqualByComparingTo("1500.0000");
    assertThat(sales.unit()).isEqualTo("AMOUNT");

    var units = byMetric.get("unitsOrdered");
    assertThat(units.changeRate()).as("60 vs 30 ⇒ +100%").isEqualByComparingTo("1.000000");
  }

  @Test
  @DisplayName("4.5.1.1 🔴 上期为 0 ⇒ 变化率是 null（不是 0、不是 +∞）")
  void zeroPreviousYieldsNullChangeRate() {
    UUID shop = seedShop(a, "LP-CMP0");
    var l = seedListing(actorA, shop, "LP-A10", "上期为零商品", "ON_SALE");
    ingest(actorA,
        req(l.id(), "k-cmp0-cur", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 1),
            List.of(
                snap(LocalDate.of(2026, 10, 1), "1000", "50", "200", "20", "1000.00", "2", "100.00", "4.5", "10"))));

    var detail = as(actorA, "channel.operation.read",
        () -> service.detail(l.id().toString(), "2026-10-01", "2026-10-01", null, null));

    var byMetric = new java.util.HashMap<String, ListingPerformanceComparisonEntry>();
    for (var e : detail.comparison().entries()) {
      byMetric.put(e.metric(), e);
    }
    var impressions = byMetric.get("impressions");
    assertThat(impressions.previous()).isEqualByComparingTo("0");
    assertThat(impressions.change()).isEqualByComparingTo("1000");
    assertThat(impressions.changeRate()).as("上期为 0 ⇒ 变化率是 null").isNull();
  }

  @Test
  @DisplayName("4.5.1.1 🔴 显式上期区间生效")
  void explicitCompareWindowIsHonoured() {
    UUID shop = seedShop(a, "LP-CMPX");
    var l = seedListing(actorA, shop, "LP-A11", "显式对比商品", "ON_SALE");
    ingest(actorA,
        req(l.id(), "k-cmpx-cur", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 3),
            currentPeriodSnaps()));
    ingest(actorA,
        req(l.id(), "k-cmpx-prev", LocalDate.of(2026, 9, 28), LocalDate.of(2026, 9, 30),
            previousPeriodSnaps()));

    var detail = as(actorA, "channel.operation.read",
        () -> service.detail(l.id().toString(), "2026-10-01", "2026-10-03", "2026-09-28", "2026-09-30"));

    assertThat(detail.comparePeriodFrom()).isEqualTo("2026-09-28");
    assertThat(detail.comparePeriodTo()).isEqualTo("2026-09-30");
    assertThat(detail.previous().clicks()).isEqualByComparingTo("120");
  }

  // ==================================================================
  // 4. 🔴 趋势 / 空态 / 列表
  // ==================================================================

  @Test
  @DisplayName("4.5.1.1 🔴 趋势：按日归集；没有数据时是空数组（不是假曲线）")
  void trendIsRealAndEmptyWhenNoData() {
    UUID shop = seedShop(a, "LP-TREND");
    var l = seedListing(actorA, shop, "LP-A12", "趋势商品", "ON_SALE");
    ingest(actorA,
        req(l.id(), "k-trend-1", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 3),
            currentPeriodSnaps()));

    var trend = as(actorA, "channel.operation.read",
        () -> service.trend(l.id().toString(), "2026-10-01", "2026-10-03", "DAY"));

    assertThat(trend.hasData()).isTrue();
    assertThat(trend.points()).hasSize(3);
    assertThat(trend.points().get(0).bucket()).isEqualTo("2026-10-01");
    assertThat(trend.points().get(0).impressions()).isEqualByComparingTo("1000");
    assertThat(trend.points().get(0).ctr()).isEqualByComparingTo("0.050000");
    assertThat(trend.points().get(1).impressions()).isEqualByComparingTo("2000");
    assertThat(trend.points().get(2).impressions()).isEqualByComparingTo("0");
    // 零曝光的那天：点击率是 null，不是 0
    assertThat(trend.points().get(2).ctr()).isNull();
    assertThat(trend.dataSource()).isEqualTo("PLATFORM_REPORTED");

    // 另一个没有数据的商品 ⇒ 空数组
    var empty = seedListing(actorA, shop, "LP-A13", "无趋势商品", "ON_SALE");
    var emptyTrend = as(actorA, "channel.operation.read",
        () -> service.trend(empty.id().toString(), "2026-10-01", "2026-10-03", "DAY"));
    assertThat(emptyTrend.points()).as("没有数据时是空数组，不是平线").isEmpty();
    assertThat(emptyTrend.hasData()).isFalse();
  }

  @Test
  @DisplayName("4.5.1.1 🔴 空态要分两种：没同步过 vs 同步过但区间内没数据")
  void emptyStateDistinguishesNeverSyncedFromNoDataInRange() {
    UUID shop = seedShop(a, "LP-EMPTY");
    var never = seedListing(actorA, shop, "LP-A14", "从未同步商品", "ON_SALE");
    var synced = seedListing(actorA, shop, "LP-A15", "已同步商品", "ON_SALE");
    ingest(actorA,
        req(synced.id(), "k-empty-1", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 1),
            List.of(
                snap(LocalDate.of(2026, 10, 1), "1000", "50", "200", "20", "1000.00", "2", "100.00", "4.5", "10"))));

    var neverDetail = as(actorA, "channel.operation.read",
        () -> service.detail(never.id().toString(), "2026-10-01", "2026-10-03", null, null));
    assertThat(neverDetail.hasData()).isFalse();
    assertThat(neverDetail.emptyReason()).contains("还没有平台表现数据的同步结果");
    assertThat(neverDetail.syncCount()).isZero();

    var syncedDetail = as(actorA, "channel.operation.read",
        () -> service.detail(synced.id().toString(), "2026-11-01", "2026-11-03", null, null));
    assertThat(syncedDetail.hasData()).isFalse();
    assertThat(syncedDetail.emptyReason()).contains("同步过").contains("区间");
    assertThat(syncedDetail.syncCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("4.5.1.1 真分页：total 是真的总数，翻页能翻到不同的数据")
  void paginationIsReal() {
    UUID shop = seedShop(a, "LP-PAGE");
    var l = seedListing(actorA, shop, "LP-A16", "分页商品", "ON_SALE");
    for (int day = 1; day <= 5; day++) {
      ingest(actorA,
          req(l.id(), "k-page-" + day, LocalDate.of(2026, 10, day), LocalDate.of(2026, 10, day),
              List.of(
                  snap(LocalDate.of(2026, 10, day), "1000", "50", "200", "20", "1000.00", "2",
                      "100.00", "4.5", "10"))));
    }

    var first = as(actorA, "channel.operation.read",
        () -> service.snapshots(l.id().toString(), null, null, null, null, 0, 2));
    assertThat(first.total()).isEqualTo(5);
    assertThat(first.items()).hasSize(2);
    assertThat(first.syncCount()).as("同步结果条数也是真的").isEqualTo(5);

    var second = as(actorA, "channel.operation.read",
        () -> service.snapshots(l.id().toString(), null, null, null, null, 1, 2));
    assertThat(second.items()).hasSize(2);
    assertThat(first.items().get(0).snapshotId())
        .as("翻到第二页必须翻到不同的数据")
        .isNotEqualTo(second.items().get(0).snapshotId());
    // 列表行也带来源标注字段
    assertThat(first.items().get(0).source()).isEqualTo("PLATFORM");
    assertThat(first.items().get(0).ingestChannel()).isEqualTo("API");
  }

  // ==================================================================
  // 5. 🔴 来源标注与能力声明
  // ==================================================================

  @Test
  @DisplayName("4.5.1.1 🔴 数据来源必须能被展示：每个响应都带平台口径标识与说明")
  void dataSourceIsLabelled() {
    UUID shop = seedShop(a, "LP-SRC");
    var l = seedListing(actorA, shop, "LP-A17", "来源商品", "ON_SALE");
    ingest(actorA,
        req(l.id(), "k-src-1", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 1),
            List.of(
                snap(LocalDate.of(2026, 10, 1), "1000", "50", "200", "20", "1000.00", "2", "100.00", "4.5", "10"))));

    var detail = as(actorA, "channel.operation.read",
        () -> service.detail(l.id().toString(), "2026-10-01", "2026-10-01", null, null));
    assertThat(detail.dataSource()).isEqualTo("PLATFORM_REPORTED");
    assertThat(detail.sourceNote()).contains("平台口径").contains("非内部订单");

    var trend = as(actorA, "channel.operation.read",
        () -> service.trend(l.id().toString(), "2026-10-01", "2026-10-01", "DAY"));
    assertThat(trend.dataSource()).isEqualTo("PLATFORM_REPORTED");
    assertThat(trend.sourceNote()).contains("平台口径");

    var caps = service.capabilities();
    assertThat(caps.dataSourceNote()).contains("平台口径").contains("非内部订单");
  }

  @Test
  @DisplayName("4.5.1.1 🔴 capabilities：13 / 14 未交付 ⇒ available=false + ownerModule + 影响")
  void capabilitiesDeclareUndeliveredDownstreams() {
    var caps = service.capabilities();
    var byDimension = new java.util.HashMap<String, ListingPerformanceCapabilityDimension>();
    for (var d : caps.dimensions()) {
      byDimension.put(d.dimension(), d);
    }

    var drilldown = byDimension.get("product_performance_drilldown");
    assertThat(drilldown).as("13 产品表现必须在能力表里显式声明").isNotNull();
    assertThat(drilldown.available()).isFalse();
    assertThat(drilldown.ownerModule()).contains("13");
    assertThat(drilldown.impact()).isNotBlank();

    var ads = byDimension.get("ad_attribution");
    assertThat(ads).as("14 广告关联投放必须在能力表里显式声明").isNotNull();
    assertThat(ads.available()).isFalse();
    assertThat(ads.ownerModule()).contains("14");
    assertThat(ads.impact()).isNotBlank();

    // 已交付的那几段必须是 true
    assertThat(byDimension.get("platform_performance_ingest").available()).isTrue();
    assertThat(byDimension.get("metric_aggregation").available()).isTrue();
    assertThat(byDimension.get("period_comparison").available()).isTrue();
    assertThat(byDimension.get("trend_series").available()).isTrue();
    assertThat(byDimension.get("data_source_labeling").available()).isTrue();
    assertThat(caps.metricDefinitions()).contains("转化率").contains("退货率").contains("分母为 0");
    assertThat(caps.relationNote()).contains("13").contains("14");
  }

  // ==================================================================
  // 6. 🔴 DB 级闸门
  // ==================================================================

  @Test
  @DisplayName("4.5.1.1 🔴 DB 闸门：快照引用的同步批次必须是同一个商品的")
  void snapshotMustReferenceSameListingSync() {
    UUID shop = seedShop(a, "LP-GUARD");
    var l1 = seedListing(actorA, shop, "LP-A18", "闸门商品一", "ON_SALE");
    var l2 = seedListing(actorA, shop, "LP-A19", "闸门商品二", "ON_SALE");

    var sync1 = ingest(actorA,
        req(l1.id(), "k-guard-1", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 1),
            List.of(
                snap(LocalDate.of(2026, 10, 1), "1000", "50", "200", "20", "1000.00", "2", "100.00", "4.5", "10"))));

    // 拿 l1 的同步批次挂到 l2 上 ⇒ 必须被拒（23514）
    String state =
        sqlState(
            "insert into listing_performance_snapshot (id,tenant_id,listing_id,stat_date,last_sync_id)"
                + " values (gen_random_uuid(),'" + a + "','" + l2.id() + "','2026-10-02','"
                + sync1.syncId() + "')");
    assertThat(state).as("同步批次与商品必须一致").isEqualTo("23514");
  }

  @Test
  @DisplayName("4.5.1.1 🔴 DB 闸门：同步批次落终态后不可再改（只追加的证据）")
  void syncRowIsFinalOnceApplied() {
    UUID shop = seedShop(a, "LP-FINAL");
    var l = seedListing(actorA, shop, "LP-A20", "终态商品", "ON_SALE");
    var sync = ingest(actorA,
        req(l.id(), "k-final-1", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 1),
            List.of(
                snap(LocalDate.of(2026, 10, 1), "1000", "50", "200", "20", "1000.00", "2", "100.00", "4.5", "10"))));

    String state =
        sqlState(
            "update listing_performance_sync set note='偷偷改一下' where id='" + sync.syncId() + "'");
    assertThat(state).as("终态不可再改").isEqualTo("23514");
  }

  @Test
  @DisplayName("4.5.1.1 🔴 DB 闸门：跨租户挂 listing 在物理上不可能（复合 FK）")
  void crossTenantListingReferenceIsImpossible() {
    UUID shopA = seedShop(a, "LP-XA");
    UUID shopB = seedShop(b, "LP-XB");
    var listingA = seedListing(actorA, shopA, "LP-A21", "A 的商品", "ON_SALE");
    var listingB = seedListing(actorB, shopB, "LP-A22", "B 的商品", "ON_SALE");

    var syncA = ingest(actorA,
        req(listingA.id(), "k-xt-1", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 1),
            List.of(
                snap(LocalDate.of(2026, 10, 1), "1000", "50", "200", "20", "1000.00", "2", "100.00", "4.5", "10"))));

    // 用 A 的租户 + A 的同步批次，挂 B 的商品 ⇒ 必须被拒。
    // （先撞上的是「同步批次与商品必须一致」那道触发器 23514；复合 FK 那道是 23503 —— 两道都在，
    //  顺序上触发器先到，所以这里断言「两种约束违例之一」而不是钉死其中一个。）
    String state =
        sqlState(
            "insert into listing_performance_snapshot (id,tenant_id,listing_id,stat_date,last_sync_id)"
                + " values (gen_random_uuid(),'" + a + "','" + listingB.id() + "','2026-10-02','"
                + syncA.syncId() + "')");
    assertThat(state).as("跨租户挂引用必须被拒").isIn("23503", "23514");

    // 🔴 复合外键确实带 tenant_id（这才是「跨租户在物理上不可能」的结构保证）
    String fk =
        String.valueOf(
            scalar(
                "select pg_get_constraintdef(oid) from pg_constraint"
                    + " where conrelid='listing_performance_snapshot'::regclass and contype='f'"
                    + " and pg_get_constraintdef(oid) like '%(tenant_id, listing_id)%'"));
    assertThat(fk).as("快照挂 listing 的外键必须是带 tenant_id 的复合外键")
        .contains("FOREIGN KEY (tenant_id, listing_id)");
  }

  @Test
  @DisplayName("4.5.1.1 🔴 两张新表都是 FORCE RLS")
  void bothTablesForceRls() {
    assertThat(String.valueOf(scalar(
            "select relforcerowsecurity::text from pg_class where relname='listing_performance_snapshot'")))
        .isEqualTo("true");
    assertThat(String.valueOf(scalar(
            "select relforcerowsecurity::text from pg_class where relname='listing_performance_sync'")))
        .isEqualTo("true");
  }

  // ==================================================================
  // 7. 🔴 时间往返 / HTTP 端点
  // ==================================================================

  @Test
  @DisplayName("4.5.1.1 🔴 timestamptz 往返：口径区间写回值逐字相等（+08:00 非 UTC 偏移）")
  void periodRoundTripsExactly() {
    UUID shop = seedShop(a, "LP-TIME");
    var l = seedListing(actorA, shop, "LP-A23", "时间商品", "ON_SALE");

    OffsetDateTime start = OffsetDateTime.of(2026, 3, 14, 15, 9, 26, 0, ZoneOffset.ofHours(8));
    OffsetDateTime end = OffsetDateTime.of(2026, 3, 15, 15, 9, 26, 0, ZoneOffset.ofHours(8));
    var request =
        new IngestPerformanceRequest(
            l.id(), null, null, "PLATFORM", "WEBHOOK", start, end,
            LocalDate.of(2026, 3, 14), LocalDate.of(2026, 3, 15), "k-time-1", "",
            Map.of(),
            List.of(
                snap(LocalDate.of(2026, 3, 14), "1000", "50", "200", "20", "1000.00", "2", "100.00", "4.5", "10")));
    var result = ingest(actorA, request);

    // 落库的值逐字相等（用管理员连接读，按 Asia/Shanghai 呈现）
    Object stored =
        scalar(
            "select to_char(period_start at time zone 'Asia/Shanghai','YYYY-MM-DD HH24:MI:SS')"
                + " from listing_performance_sync where id='" + result.syncId() + "'");
    assertThat(String.valueOf(stored)).isEqualTo("2026-03-14 15:09:26");
    Object storedEnd =
        scalar(
            "select to_char(period_end at time zone 'Asia/Shanghai','YYYY-MM-DD HH24:MI:SS')"
                + " from listing_performance_sync where id='" + result.syncId() + "'");
    assertThat(String.valueOf(storedEnd)).isEqualTo("2026-03-15 15:09:26");
    // 绝对时刻也逐字相等（不是偏 8 小时）
    Object epoch =
        scalar(
            "select extract(epoch from period_start)::bigint::text from listing_performance_sync"
                + " where id='" + result.syncId() + "'");
    assertThat(String.valueOf(epoch)).isEqualTo(String.valueOf(start.toInstant().getEpochSecond()));
  }

  @Test
  @DisplayName("4.5.1.1 🔴 HTTP 端点：能力声明 / 列表 / 明细 / 趋势 / 入库")
  void httpEndpointsWork() throws Exception {
    UUID shop = seedShop(a, "LP-HTTP");
    var l = seedListing(actorA, shop, "LP-A24", "HTTP 商品", "ON_SALE");

    http.perform(get("/channel/listing-performance/capabilities")
            .header("Authorization", bearerA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.dataSourceNote").value(org.hamcrest.Matchers.containsString("平台口径")));

    http.perform(post("/channel/listing-performance")
            .header("Authorization", bearerA)
            .contentType("application/json")
            .content(JSON.writeValueAsString(
                req(l.id(), "k-http-1", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 1),
                    List.of(
                        snap(LocalDate.of(2026, 10, 1), "1000", "50", "200", "20", "1000.00", "2",
                            "100.00", "4.5", "10"))))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.replayed").value(false))
        .andExpect(jsonPath("$.appliedCount").value(1));

    http.perform(get("/channel/listing-performance")
            .header("Authorization", bearerA)
            .param("listingId", l.id().toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.syncCount").value(1))
        .andExpect(jsonPath("$.items[0].source").value("PLATFORM"));

    http.perform(get("/channel/listing-performance/" + l.id())
            .header("Authorization", bearerA)
            .param("from", "2026-10-01")
            .param("to", "2026-10-01"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.dataSource").value("PLATFORM_REPORTED"))
        .andExpect(jsonPath("$.hasData").value(true))
        .andExpect(jsonPath("$.current.impressions").value(1000))
        .andExpect(jsonPath("$.current.ctr").value(0.05));

    http.perform(get("/channel/listing-performance/" + l.id() + "/trend")
            .header("Authorization", bearerA)
            .param("from", "2026-10-01")
            .param("to", "2026-10-01"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.points.length()").value(1))
        .andExpect(jsonPath("$.points[0].bucket").value("2026-10-01"));

    // 不存在的商品 ⇒ 404
    http.perform(get("/channel/listing-performance/" + UUID.randomUUID())
            .header("Authorization", bearerA))
        .andExpect(status().isNotFound());
  }
}
