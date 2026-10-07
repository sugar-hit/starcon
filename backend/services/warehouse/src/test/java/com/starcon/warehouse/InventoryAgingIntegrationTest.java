package com.starcon.warehouse;

import static com.starcon.warehouse.InventoryAgingModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 7.6.1.1 库龄与呆滞：库龄分桶边界、呆滞判定、处置建议优先级、阈值策略，以及租户隔离三断言。
 *
 * <p>真实 PostgreSQL 16 + 强制 RLS，不使用任何内存替身。连接池固定为 1 条连接：
 * 只有复用同一条连接，才能真正证明归还后不会串租户。
 *
 * <p>夹具与写入约定与 {@code InventoryExpiryIntegrationTest} 一致（同样的 Testcontainers、
 * 同样的 fake identity server、同样的按租户派生 UUID、同样的 TRUNCATE 清场）。
 *
 * <p><b>夹具刻意在每个分桶边界两侧各放一行</b>（0/30/31/60/61/90/91 天），
 * 因为「90+ 到底含不含 90」是本模块唯一一处会让人误解的口径：
 * 若让 90 同时落进两个桶，各桶之和就会大于真实库存。用例
 * {@link #库龄分桶边界_90天归61_90桶而非90加桶()} 把这条钉死。
 *
 * <p>基准「今天」从数据库读 {@code current_date} 而不是 {@link LocalDate#now()} ——
 * 应用 JVM 与 PG 容器时区可能不同，用 JVM 的会让边界断言变成「看跑测试的时刻而定」。
 */
@SpringBootTest
@AutoConfigureMockMvc
class InventoryAgingIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  static final java.util.Map<String, AccessService.Access> actors =
      new java.util.concurrent.ConcurrentHashMap<>();
  static final List<String> allPermissions =
      java.util.stream.Stream.of("inventory")
          .flatMap(
              p ->
                  java.util.stream.Stream.of("read", "create", "update", "delete")
                      .map(op -> "warehouse." + p + "." + op))
          .toList();

  // 租户 A：SKU-1 承载 7 个边界批次，SKU-2 承载无批次（建档日代理口径）的一行。
  static final UUID leA = idFor(a, 1);
  static final UUID skuA = idFor(a, 2);
  static final UUID skuA2 = idFor(a, 3);
  static final UUID whSh = idFor(a, 4);
  static final UUID qSellable = idFor(a, 6);
  static final UUID qDefective = idFor(a, 7);
  static final UUID locSh1 = idFor(a, 8);
  static final UUID locSh2 = idFor(a, 9);

  // 租户 B
  static final UUID leB = idFor(b, 1);
  static final UUID skuB = idFor(b, 2);
  static final UUID whB = idFor(b, 4);
  static final UUID qSellableB = idFor(b, 6);
  static final UUID locB1 = idFor(b, 8);

  static UUID idFor(UUID tenant, int ordinal) {
    return UUID.nameUUIDFromBytes(
        ("starcon-warehouse-aging-test-" + tenant + "-" + ordinal).getBytes());
  }

  static AccessService.Access actor(
      UUID uid, UUID tenant, UUID dept, List<AccessService.Grant> grants) {
    return new AccessService.Access(
        uid,
        tenant,
        "Test",
        UUID.randomUUID(),
        dept,
        grants.stream().flatMap(g -> g.permissions().stream()).distinct().toList(),
        grants);
  }

  static AccessService.Grant grant(String scope, List<String> permissions) {
    return new AccessService.Grant(UUID.randomUUID(), "TEST", scope, permissions);
  }

  static {
    pg.start();
    try {
      try (var c =
              DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
          var s = c.createStatement()) {
        s.execute("create role starcon_app nologin");
        s.execute(
            "create role warehouse_app login password 'test-only' nosuperuser nobypassrls");
      }
      identity = HttpServer.create(new InetSocketAddress(0), 0);
      identity.createContext(
          "/internal/access",
          e -> {
            var access = actors.get(e.getRequestHeaders().getFirst("Authorization"));
            if (access == null) {
              e.sendResponseHeaders(401, -1);
              e.close();
              return;
            }
            byte[] body =
                new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(access);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      actors.put("Bearer tenant-a", actor(user, a, null, List.of(grant("ALL", allPermissions))));
      actors.put("Bearer tenant-b", actor(user, b, null, List.of(grant("ALL", allPermissions))));
      actors.put(
          "Bearer self-only",
          actor(user, a, null, List.of(grant("SELF", List.of("warehouse.inventory.read")))));
      identity.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "warehouse_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://localhost:" + identity.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
    // 单连接：只有复用同一条连接才能证明连接池归还后不串租户。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  @Autowired InventoryAgingService service;
  @Autowired AccessService access;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  @AfterAll
  static void stop() {
    identity.stop(0);
    pg.stop();
  }

  AccessService.Access actorA;
  AccessService.Access actorB;

  @BeforeEach
  void seed() throws Exception {
    actorA = actors.get("Bearer tenant-a");
    actorB = actors.get("Bearer tenant-b");
    // TRUNCATE 不触发行级触发器，以超级用户连接执行可绕过 FORCE RLS —— 只用于测试夹具重建。
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate inventory_aging_policy, inventory_expiry_policy, stock_bucket,"
              + " warehouse_location, inventory_lot, inventory_entry, inventory_transaction,"
              + " inventory_balance, inventory_quality_status, warehouse, sku_ref,"
              + " legal_entity_ref restart identity cascade");
      try (var rs = s.executeQuery("select current_date as d")) {
        rs.next();
        dbToday = rs.getObject(1, LocalDate.class);
      }
    }
    access.run(actorA, "warehouse.inventory.read", () -> {
      sql.execute(
          "insert into legal_entity_ref(id,tenant_id,code,name) values(?,?,?,?)",
          leA, a, "LE-1", "主体一");
      sql.execute(
          "insert into sku_ref(id,tenant_id,code,name) values(?,?,?,?)", skuA, a, "SKU-1", "库龄商品");
      sql.execute(
          "insert into sku_ref(id,tenant_id,code,name) values(?,?,?,?)",
          skuA2, a, "SKU-2", "非批次商品");
      sql.execute(
          "insert into warehouse(id,tenant_id,code,name,country_code,timezone) values(?,?,?,?,?,?)",
          whSh, a, "WH-SH", "上海仓", "CN", "Asia/Shanghai");
      sql.execute(
          "insert into inventory_quality_status(id,tenant_id,code,name,role) values(?,?,?,?,?)",
          qSellable, a, "SELLABLE", "可售", "SELLABLE");
      sql.execute(
          "insert into inventory_quality_status(id,tenant_id,code,name,role) values(?,?,?,?,?)",
          qDefective, a, "DEFECTIVE", "残次", "DEFECTIVE");
      sql.execute(
          "insert into warehouse_location(id,tenant_id,warehouse_id,code,name,type,pickable)"
              + " values(?,?,?,?,?,?,?)",
          locSh1, a, whSh, "SH-01", "上海一区", "STORAGE", true);
      sql.execute(
          "insert into warehouse_location(id,tenant_id,warehouse_id,code,name,type,pickable)"
              + " values(?,?,?,?,?,?,?)",
          locSh2, a, whSh, "SH-02", "上海二区", "STORAGE", true);
      return null;
    });
    access.run(actorB, "warehouse.inventory.read", () -> {
      sql.execute(
          "insert into legal_entity_ref(id,tenant_id,code,name) values(?,?,?,?)",
          leB, b, "LE-1", "主体一");
      sql.execute(
          "insert into sku_ref(id,tenant_id,code,name) values(?,?,?,?)", skuB, b, "SKU-B", "B 租户商品");
      sql.execute(
          "insert into warehouse(id,tenant_id,code,name,country_code,timezone) values(?,?,?,?,?,?)",
          whB, b, "WH-BJ", "北京仓", "CN", "Asia/Shanghai");
      sql.execute(
          "insert into inventory_quality_status(id,tenant_id,code,name,role) values(?,?,?,?,?)",
          qSellableB, b, "SELLABLE", "可售", "SELLABLE");
      sql.execute(
          "insert into warehouse_location(id,tenant_id,warehouse_id,code,name,type,pickable)"
              + " values(?,?,?,?,?,?,?)",
          locB1, b, whB, "BJ-01", "北京一区", "STORAGE", true);
      return null;
    });
  }

  // ================================================================ 夹具

  static LocalDate dbToday;

  private static LocalDate daysAgo(int days) {
    return dbToday.minusDays(days);
  }

  record B(UUID lotId, UUID location, String onHand, String reserved) {}

  /**
   * 建余额行并按库位/批次拆成若干 bucket，**同一事务**写入。
   *
   * <p>必须同一事务：V2 的守恒是延迟约束触发器，提交前才把 balance 与 bucket 汇总对平。
   */
  UUID balance(
      AccessService.Access actor,
      UUID tenant,
      UUID sku,
      UUID warehouse,
      UUID legalEntity,
      UUID quality,
      List<B> buckets) {
    var balanceId = UUID.randomUUID();
    access.run(actor, "warehouse.inventory.read", () -> {
      var onHand = BigDecimal.ZERO;
      var reserved = BigDecimal.ZERO;
      for (var x : buckets) {
        onHand = onHand.add(new BigDecimal(x.onHand()));
        reserved = reserved.add(new BigDecimal(x.reserved()));
      }
      sql.execute(
          "insert into inventory_balance(id,tenant_id,sku_id,warehouse_id,legal_entity_id,"
              + "quality_status_id,on_hand,reserved) values(?,?,?,?,?,?,?,?)",
          balanceId, tenant, sku, warehouse, legalEntity, quality, onHand, reserved);
      for (var x : buckets) {
        sql.execute(
            "insert into stock_bucket(id,tenant_id,legal_entity_id,sku_id,warehouse_id,"
                + "location_id,lot_id,quality_status_id,balance_id,on_hand,reserved)"
                + " values(?,?,?,?,?,?,?,?,?,?,?)",
            UUID.randomUUID(),
            tenant,
            legalEntity,
            sku,
            warehouse,
            x.location(),
            x.lotId(),
            quality,
            balanceId,
            new BigDecimal(x.onHand()),
            new BigDecimal(x.reserved()));
      }
      return null;
    });
    return balanceId;
  }

  /** 建批次。{@code receivedDaysAgo} 就是**库龄天数**，直接控制边界。 */
  UUID lot(String code, int receivedDaysAgo, LocalDate expiresOn, boolean withSupplier) {
    var id = UUID.randomUUID();
    access.run(actorA, "warehouse.inventory.read", () -> {
      sql.execute(
          "insert into inventory_lot(id,tenant_id,sku_id,lot_code,expires_on,received_on,"
              + "origin,source_system,source_ref_id,source_ref_code,supplier_ref_id,supplier_name)"
              + " values(?,?,?,?,?::date,?::date,'MANUAL','PURCHASE',?,'PO-AGING',?,?)",
          id,
          a,
          skuA,
          code,
          expiresOn,
          daysAgo(receivedDaysAgo),
          UUID.randomUUID(),
          withSupplier ? UUID.randomUUID() : null,
          withSupplier ? "供应商甲" : "");
      return null;
    });
    return id;
  }

  /**
   * 标准夹具：SKU-1 上 7 个批次分别躺在 0 / 30 / 31 / 60 / 61 / 90 / 91 天前，各 10 件。
   *
   * <p>刻意覆盖每一个分桶边界的两侧，这样四个桶的边界都由真实数据钉住。
   */
  record Fixture(UUID l0, UUID l30, UUID l31, UUID l60, UUID l61, UUID l90, UUID l91) {}

  Fixture agingFixture() {
    var l0 = lot("L0", 0, null, false);
    var l30 = lot("L30", 30, null, false);
    var l31 = lot("L31", 31, null, false);
    var l60 = lot("L60", 60, null, false);
    var l61 = lot("L61", 61, null, false);
    var l90 = lot("L90", 90, null, false);
    var l91 = lot("L91", 91, null, false);
    balance(
        actorA,
        a,
        skuA,
        whSh,
        leA,
        qSellable,
        List.of(
            new B(l0, locSh1, "10", "0"),
            new B(l30, locSh1, "10", "0"),
            new B(l31, locSh1, "10", "0"),
            new B(l60, locSh1, "10", "0"),
            new B(l61, locSh1, "10", "0"),
            new B(l90, locSh1, "10", "0"),
            new B(l91, locSh2, "10", "0")));
    return new Fixture(l0, l30, l31, l60, l61, l90, l91);
  }

  /** 非批次管理品（无 lot_id）：库龄只能退回库位建档日。 */
  UUID lotlessBucket(String onHand) {
    balance(
        actorA, a, skuA2, whSh, leA, qSellable,
        List.of(new B(null, locSh1, onHand, "0")));
    return skuA2;
  }

  <T> T asA(java.util.function.Supplier<T> work) {
    return access.run(actorA, "warehouse.inventory.read", work::get);
  }

  <T> T asB(java.util.function.Supplier<T> work) {
    return access.run(actorB, "warehouse.inventory.read", work::get);
  }

  AgingBucketReport report() {
    return asA(
        () -> service.bucketReport(new AgingBucketQuery(null, null, null, null, null)));
  }

  SlowMovingPage list() {
    return asA(
        () ->
            service.slowMoving(
                new SlowMovingQuery(
                    null, null, null, null, null, null, null, 0, 50)));
  }

  static AgingBucketRow bucket(AgingBucketReport r, String name) {
    return r.buckets().stream().filter(x -> x.bucket().equals(name)).findFirst().orElseThrow();
  }

  // ================================================================ 库龄分桶

  @Test
  void 库龄分桶边界_90天归61_90桶而非90加桶() {
    agingFixture();
    var r = report();

    // 边界：0 与 30 都在 0-30（含 30 当天）；31 起进第二桶。
    assertThat(bucket(r, "AGE_0_30").bucketCount()).isEqualTo(2L);
    assertThat(bucket(r, "AGE_0_30").available()).isEqualByComparingTo("20");
    assertThat(bucket(r, "AGE_31_60").bucketCount()).isEqualTo(2L);
    assertThat(bucket(r, "AGE_31_60").available()).isEqualByComparingTo("20");
    assertThat(bucket(r, "AGE_61_90").bucketCount()).isEqualTo(2L);
    assertThat(bucket(r, "AGE_61_90").available()).isEqualByComparingTo("20");
    assertThat(bucket(r, "AGE_90_PLUS").bucketCount()).isEqualTo(1L);
    assertThat(bucket(r, "AGE_90_PLUS").available()).isEqualByComparingTo("10");
  }

  @Test
  void 库龄分桶_各桶之和恒等于总量() {
    agingFixture();
    var r = report();
    var sum = BigDecimal.ZERO;
    for (var row : r.buckets()) sum = sum.add(row.available());
    assertThat(sum).isEqualByComparingTo(r.totals().available());
    assertThat(r.totals().available()).isEqualByComparingTo("70");
    assertThat(r.totals().bucketCount()).isEqualTo(7L);
  }

  @Test
  void 库龄分桶_90加桶下界是91不是90() {
    // 把这条单独钉死：若哪天有人把 90+ 的下界改成 90，90 天那批会同时落进两个桶。
    assertThat(AgingBucket.of(89)).isEqualTo(AgingBucket.AGE_61_90);
    assertThat(AgingBucket.of(90)).isEqualTo(AgingBucket.AGE_61_90);
    assertThat(AgingBucket.of(91)).isEqualTo(AgingBucket.AGE_90_PLUS);
    assertThat(AgingBucket.of(30)).isEqualTo(AgingBucket.AGE_0_30);
    assertThat(AgingBucket.of(31)).isEqualTo(AgingBucket.AGE_31_60);
    assertThat(AgingBucket.of(60)).isEqualTo(AgingBucket.AGE_31_60);
    assertThat(AgingBucket.of(61)).isEqualTo(AgingBucket.AGE_61_90);
    assertThat(AgingBucket.of(0)).isEqualTo(AgingBucket.AGE_0_30);
  }

  @Test
  void 库龄天数真按收货日算出且能读回数值() {
    // 这条断言是为了钉住「jOOQ 在纯文本 SQL 里拿不到 integer 列类型」这个坑：
    // 取值方式不对时每一行的库龄都会静默变成 null，而报表看起来仍然「有四行」。
    agingFixture();
    var page = list();
    assertThat(page.rows()).isNotEmpty();
    assertThat(page.rows()).allSatisfy(r -> assertThat(r.agingDays()).isNotNull());
    var l91 =
        page.rows().stream().filter(r -> "L91".equals(r.lotCode())).findFirst().orElseThrow();
    assertThat(l91.agingDays()).isEqualTo(91);
    assertThat(l91.inboundDate()).isEqualTo(daysAgo(91));
  }

  @Test
  void 库龄口径_有批次取收货日_无批次退回建档日() {
    var f = agingFixture();
    lotlessBucket("15");

    var r = report();
    // 无批次那一行算不进「有批次」的计数里，但仍在总量里 —— 两边必须能对上。
    assertThat(r.totals().bucketCount()).isEqualTo(8L);
    assertThat(r.totals().available()).isEqualByComparingTo("85");
    assertThat(bucket(r, "AGE_0_30").bucketCount()).isEqualTo(3L);

    var page = list();
    var lotBased =
        page.rows().stream().filter(x -> f.l91.equals(x.lotId())).findFirst().orElseThrow();
    assertThat(lotBased.agingBasis()).isEqualTo("LOT_RECEIVED_ON");
    assertThat(lotBased.receivedOn()).isEqualTo(daysAgo(91));

    // 无批次行：按 SKU-2 过滤才看得到
    var lotlessPage =
        asA(
            () ->
                service.slowMoving(
                    new SlowMovingQuery(
                        skuA2, null, null, List.of("NORMAL"), null, null, null, 0, 50)));
    assertThat(lotlessPage.rows()).hasSize(1);
    assertThat(lotlessPage.rows().getFirst().agingBasis()).isEqualTo("BUCKET_CREATED_AT");
    assertThat(lotlessPage.rows().getFirst().receivedOn()).isNull();
  }

  @Test
  void 库龄口径筛选_能只看待批次收货日或只看待代理值() {
    agingFixture();
    lotlessBucket("15");

    var onlyReal =
        asA(
            () ->
                service.bucketReport(
                    new AgingBucketQuery(null, null, null, "LOT_RECEIVED_ON", null)));
    assertThat(onlyReal.totals().bucketCount()).isEqualTo(7L);
    assertThat(onlyReal.totals().available()).isEqualByComparingTo("70");

    var onlyProxy =
        asA(
            () ->
                service.bucketReport(
                    new AgingBucketQuery(null, null, null, "BUCKET_CREATED_AT", null)));
    assertThat(onlyProxy.totals().bucketCount()).isEqualTo(1L);
    assertThat(onlyProxy.totals().available()).isEqualByComparingTo("15");

    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.bucketReport(
                            new AgingBucketQuery(null, null, null, "CREATED_AT", null))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不支持的库龄口径");
  }

  @Test
  void 库龄不用最近变动时间_动过的货仍按收货日计龄() {
    // 库位上的货今天又动过一次：若用 last_movement_at，它的库龄会变成 0 天。
    var l = lot("L-MOVED", 120, null, false);
    balance(actorA, a, skuA, whSh, leA, qSellable, List.of(new B(l, locSh1, "5", "0")));
    access.run(actorA, "warehouse.inventory.read", () -> {
      sql.execute(
          "update stock_bucket set last_movement_at=now(),version=version+1"
              + " where lot_id=?",
          l);
      return null;
    });

    var page = list();
    var row = page.rows().stream().filter(r -> "L-MOVED".equals(r.lotCode())).findFirst().orElseThrow();
    assertThat(row.agingDays()).isEqualTo(120);
    assertThat(row.slowMovingStatus()).isEqualTo("OBSOLETE");
  }

  @Test
  void 占用资金无成本列时如实报不可用而不是报零() {
    agingFixture();
    var tie = report().capitalTie();
    assertThat(tie.available()).isFalse();
    assertThat(tie.occupiedAmount()).isNull();
    assertThat(tie.reason()).contains("无成本列");
    assertThat(tie.blockedBy()).contains("finance");
  }

  // ================================================================ 呆滞判定与处置

  @Test
  void 呆滞判定_默认阈值60滞销90呆滞且逐档互斥() {
    agingFixture();
    var page = list();

    assertThat(page.rows()).extracting(SlowMovingRow::lotCode, SlowMovingRow::agingDays)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("L91", 91),
            org.assertj.core.groups.Tuple.tuple("L90", 90),
            org.assertj.core.groups.Tuple.tuple("L61", 61),
            org.assertj.core.groups.Tuple.tuple("L60", 60));

    assertThat(page.rows()).extracting(SlowMovingRow::slowMovingStatus)
        .containsExactly("OBSOLETE", "OBSOLETE", "SLOW_MOVING", "SLOW_MOVING");

    // 未配策略时阈值来源必须标成 DEFAULT，而不是让用户以为有人配过
    assertThat(page.rows()).extracting(SlowMovingRow::slowMovingDaysSource)
        .containsOnly("DEFAULT");
  }

  @Test
  void 呆滞判定_未达滞销线的不进清单() {
    agingFixture();
    var page = list();
    assertThat(page.rows()).extracting(SlowMovingRow::lotCode)
        .doesNotContain("L0", "L30", "L31");
    // 汇总只统计命中两档的行
    assertThat(page.summary().bucketCount()).isEqualTo(4L);
    assertThat(page.summary().obsoleteCount()).isEqualTo(2L);
    assertThat(page.summary().slowMovingCount()).isEqualTo(2L);
    assertThat(page.summary().obsoleteAvailable()).isEqualByComparingTo("20");
    assertThat(page.summary().oldestAgingDays()).isEqualTo(91);
    assertThat(page.summary().oldestInboundDate()).isEqualTo(daysAgo(91));
  }

  @Test
  void 处置建议_过期与残次优先于库龄() {
    var expired = lot("L-EXPIRED", 5, daysAgo(1), false);
    var expiring = lot("L-EXPIRING", 10, dbToday.plusDays(10), false);
    balance(
        actorA, a, skuA, whSh, leA, qSellable,
        List.of(new B(expired, locSh1, "3", "0"), new B(expiring, locSh1, "4", "0")));

    var page = list();
    var expiredRow =
        page.rows().stream().filter(r -> "L-EXPIRED".equals(r.lotCode())).findFirst().orElseThrow();
    var expiringRow =
        page.rows().stream().filter(r -> "L-EXPIRING".equals(r.lotCode())).findFirst().orElseThrow();

    // 过期货即使只躺了 5 天，也必须建议报废而不是促销
    assertThat(expiredRow.expiryStatus()).isEqualTo("EXPIRED");
    assertThat(expiredRow.slowMovingStatus()).isEqualTo("NORMAL");
    assertThat(expiredRow.disposition()).isEqualTo("SCRAP");

    // 临期口径与 7.3.1.2 一致：剩余 <= 30 天即临期
    assertThat(expiringRow.expiryStatus()).isEqualTo("EXPIRING");
    assertThat(expiringRow.daysToExpiry()).isEqualTo(10);
    assertThat(expiringRow.disposition()).isEqualTo("MARKDOWN");
    assertThat(page.summary().obsoleteAndUpAvailable()).isEqualByComparingTo("7");
  }

  @Test
  void 处置建议_残次品有供应商才建议退仓() {
    var withSupplier = lot("L-DEF-SUP", 100, null, true);
    var noSupplier = lot("L-DEF-NOSUP", 100, null, false);
    balance(
        actorA, a, skuA, whSh, leA, qDefective,
        List.of(new B(withSupplier, locSh1, "2", "0"), new B(noSupplier, locSh1, "6", "0")));

    var page = list();
    var sup =
        page.rows().stream().filter(r -> "L-DEF-SUP".equals(r.lotCode())).findFirst().orElseThrow();
    var nosup =
        page.rows().stream().filter(r -> "L-DEF-NOSUP".equals(r.lotCode())).findFirst().orElseThrow();
    assertThat(sup.qualityRole()).isEqualTo("DEFECTIVE");
    assertThat(sup.disposition()).isEqualTo("RETURN_TO_VENDOR");
    assertThat(nosup.disposition()).isEqualTo("SCRAP");
  }

  @Test
  void 呆滞与滞销分别对应清仓与促销() {
    agingFixture();
    var page = list();
    assertThat(page.rows()).extracting(SlowMovingRow::lotCode, SlowMovingRow::disposition)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("L91", "CLEARANCE"),
            org.assertj.core.groups.Tuple.tuple("L90", "CLEARANCE"),
            org.assertj.core.groups.Tuple.tuple("L61", "MARKDOWN"),
            org.assertj.core.groups.Tuple.tuple("L60", "MARKDOWN"));
    // 处置建议只是依据：本模块不触发任何动作
    assertThat(page.rows()).extracting(SlowMovingRow::automatable).containsOnly(false);
    assertThat(page.salesVelocityNote()).contains("尚未交付");
    assertThat(page.agingBasisNote()).contains("最近变动时间");
    assertThat(page.rows().getFirst().basis()).contains("未纳入销售速度因子");
  }

  @Test
  void 呆滞清单_筛选排序分页都是真的() {
    agingFixture();
    lotlessBucket("15");

    var obsoleteOnly =
        asA(
            () ->
                service.slowMoving(
                    new SlowMovingQuery(
                        null, null, null, List.of("OBSOLETE"), null, null, null, 0, 50)));
    assertThat(obsoleteOnly.rows()).extracting(SlowMovingRow::lotCode)
        .containsExactly("L91", "L90");
    assertThat(obsoleteOnly.total()).isEqualTo(2L);
    assertThat(obsoleteOnly.statusOptions()).hasSize(3);

    var clearanceOnly =
        asA(
            () ->
                service.slowMoving(
                    new SlowMovingQuery(
                        null, null, null, null, "CLEARANCE", null, null, 0, 50)));
    assertThat(clearanceOnly.rows()).extracting(SlowMovingRow::lotCode)
        .containsExactly("L91", "L90");
    assertThat(clearanceOnly.dispositionOptions()).isNotEmpty();

    var page1 =
        asA(
            () ->
                service.slowMoving(
                    new SlowMovingQuery(null, null, null, null, null, null, null, 0, 2)));
    var page2 =
        asA(
            () ->
                service.slowMoving(
                    new SlowMovingQuery(null, null, null, null, null, null, null, 1, 2)));
    assertThat(page1.total()).isEqualTo(4L);
    assertThat(page1.rows()).hasSize(2);
    assertThat(page2.rows()).hasSize(2);
    assertThat(page1.rows()).extracting(SlowMovingRow::lotCode)
        .doesNotContainAnyElementsOf(
            page2.rows().stream().map(SlowMovingRow::lotCode).toList());

    var byAvailable =
        asA(
            () ->
                service.slowMoving(
                    new SlowMovingQuery(null, null, null, null, null, null, "AVAILABLE", 0, 50)));
    assertThat(byAvailable.rows()).extracting(SlowMovingRow::lotCode)
        .containsExactly("L91", "L90", "L61", "L60");

    var keyword =
        asA(
            () ->
                service.slowMoving(
                    new SlowMovingQuery(null, null, null, null, null, "L91", null, 0, 50)));
    assertThat(keyword.rows()).extracting(SlowMovingRow::lotCode).containsExactly("L91");

    var byWarehouse =
        asA(
            () ->
                service.slowMoving(
                    new SlowMovingQuery(null, whSh, null, null, null, null, null, 0, 50)));
    assertThat(byWarehouse.total()).isEqualTo(4L);
    assertThat(asA(() -> service.slowMoving(
            new SlowMovingQuery(null, UUID.randomUUID(), null, null, null, null, null, 0, 50)))
        .total()).isZero();
  }

  @Test
  void 非法筛选值与排序键都被拒() {
    agingFixture();
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.slowMoving(
                            new SlowMovingQuery(
                                null, null, null, List.of("GONE"), null, null, null, 0, 50))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不支持的呆滞档位");
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.slowMoving(
                            new SlowMovingQuery(
                                null, null, null, null, "BURN", null, null, 0, 50))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不支持的处置建议");
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.slowMoving(
                            new SlowMovingQuery(
                                null, null, null, null, null, null, "NOPE", 0, 50))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不支持的排序键");
  }

  // ================================================================ 阈值策略

  @Test
  void 阈值策略_未配时用默认值并标成未配置() {
    var p = asA(() -> service.policy(skuA));
    assertThat(p.configured()).isFalse();
    assertThat(p.source()).isEqualTo("DEFAULT");
    assertThat(p.slowMovingDays()).isEqualTo(60);
    assertThat(p.obsoleteDays()).isEqualTo(90);
    assertThat(p.bucketCount()).isZero();
  }

  @Test
  void 阈值策略_配置后立刻改变呆滞判定并标明来源() {
    agingFixture();
    // 把滞销线提到 10 天：原来只有 60/61/90/91 命中的四行会变成七行全部命中。
    var saved =
        asA(
            () ->
                service.upsertPolicy(
                    a, new AgingPolicyUpsertRequest(skuA, 10, 20, true, "快消品")));
    assertThat(saved.configured()).isTrue();
    assertThat(saved.source()).isEqualTo("POLICY");
    assertThat(saved.slowMovingDays()).isEqualTo(10);
    assertThat(saved.obsoleteDays()).isEqualTo(20);

    // L0（今天入库，库龄 0 天）仍然进不了清单 —— 滞销线配到 10 天也不会让 0 天算滞销，
    // 所以是 6 行而不是 7 行。这条同时钉住「阈值下界不被绕过」。
    var page = list();
    assertThat(page.rows()).hasSize(6);
    assertThat(page.rows()).extracting(SlowMovingRow::lotCode).doesNotContain("L0");
    // 呆滞线 20 天高于 L30/L31 的库龄，所以这 6 行**全部**是 OBSOLETE：
    // 档位判定先看呆滞线，「滞销」这一档只在库龄没过呆滞线时才可能命中。
    assertThat(page.rows()).extracting(SlowMovingRow::slowMovingStatus)
        .containsOnly("OBSOLETE");
    assertThat(page.rows()).extracting(SlowMovingRow::slowMovingDaysSource)
        .containsOnly("POLICY");

    // 重复配置走 upsert，不撞唯一键
    // 换成 30/40：L31/L30 落在滞销线与呆滞线之间，这一档才第一次被真正命中 ——
    // 前面 10/20 的配置会把六行全判成 OBSOLETE，测不到「滞销」这一档。
    var again =
        asA(() -> service.upsertPolicy(a, new AgingPolicyUpsertRequest(skuA, 30, 40, true, null)));
    assertThat(again.slowMovingDays()).isEqualTo(30);
    assertThat(list().rows()).extracting(SlowMovingRow::lotCode, SlowMovingRow::slowMovingStatus)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("L91", "OBSOLETE"),
            org.assertj.core.groups.Tuple.tuple("L90", "OBSOLETE"),
            org.assertj.core.groups.Tuple.tuple("L61", "OBSOLETE"),
            org.assertj.core.groups.Tuple.tuple("L60", "OBSOLETE"),
            org.assertj.core.groups.Tuple.tuple("L31", "SLOW_MOVING"),
            org.assertj.core.groups.Tuple.tuple("L30", "SLOW_MOVING"));
    assertThat(asA(() -> service.policies(new AgingPolicyQuery(null, null, null, null, 0, 50))).total())
        .isEqualTo(1L);

    // 关掉后退回默认值，而不是当成 0 天
    asA(() -> service.upsertPolicy(a, new AgingPolicyUpsertRequest(skuA, 30, 40, false, null)));
    assertThat(list().rows()).extracting(SlowMovingRow::slowMovingDaysSource)
        .containsOnly("DEFAULT");
    assertThat(list().rows()).hasSize(4);
  }

  @Test
  void 阈值策略_呆滞线不得早于滞销线() {
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.upsertPolicy(
                            a, new AgingPolicyUpsertRequest(skuA, 90, 60, true, null))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("呆滞线不能早于滞销线");
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.upsertPolicy(
                            a, new AgingPolicyUpsertRequest(skuA, -1, 60, true, null))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("滞销线必须在");
    assertThatThrownBy(
            () -> asA(() -> service.upsertPolicy(a, new AgingPolicyUpsertRequest(null, 1, 2, true, null))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("必须给出 skuId");
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.upsertPolicy(
                            a,
                            new AgingPolicyUpsertRequest(UUID.randomUUID(), 1, 2, true, null))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("SKU 不存在");
  }

  // ================================================================ 端点层

  @Test
  void 端点_分桶与清单可读且响应含资金占用说明() throws Exception {
    agingFixture();

    http.perform(get("/warehouse/inventory/aging/buckets")
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.asOf").exists())
        .andExpect(jsonPath("$.buckets.length()").value(4))
        .andExpect(jsonPath("$.buckets[0].bucket").value("AGE_0_30"))
        .andExpect(jsonPath("$.buckets[3].bucket").value("AGE_90_PLUS"))
        .andExpect(jsonPath("$.totals.available").value(70.0))
        .andExpect(jsonPath("$.capitalTie.available").value(false))
        .andExpect(jsonPath("$.capitalTie.occupiedAmount").doesNotExist());

    http.perform(get("/warehouse/inventory/aging/slow-moving")
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(4))
        .andExpect(jsonPath("$.rows[0].lotCode").value("L91"))
        .andExpect(jsonPath("$.rows[0].agingDays").value(91))
        .andExpect(jsonPath("$.rows[0].agingBasis").value("LOT_RECEIVED_ON"))
        .andExpect(jsonPath("$.rows[0].disposition").value("CLEARANCE"))
        .andExpect(jsonPath("$.rows[0].automatable").value(false))
        .andExpect(jsonPath("$.rows[0].basis").isNotEmpty());

    http.perform(get("/warehouse/inventory/aging/policy/{sku}", "placeholder")
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isBadRequest());

    http.perform(get("/warehouse/inventory/aging/policy/{sku}", skuA)
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.configured").value(false))
        .andExpect(jsonPath("$.source").value("DEFAULT"))
        .andExpect(jsonPath("$.slowMovingDays").value(60));
  }

  @Test
  void 端点_策略写入需要update权限() throws Exception {
    http.perform(post("/warehouse/inventory/aging/policy")
            .header("Authorization", "Bearer self-only")
            .contentType("application/json")
            .content("{\"skuId\":\"" + skuA + "\",\"slowMovingDays\":10,\"obsoleteDays\":20}"))
        .andExpect(status().isForbidden());

    http.perform(post("/warehouse/inventory/aging/policy")
            .header("Authorization", "Bearer tenant-a")
            .contentType("application/json")
            .content("{\"skuId\":\"" + skuA + "\",\"slowMovingDays\":10,\"obsoleteDays\":20}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.configured").value(true));

    http.perform(get("/warehouse/inventory/aging/buckets")).andExpect(status().isUnauthorized());
  }

  // ================================================================ 租户隔离三断言

  @Test
  void 断言一_两租户互不可见() {
    agingFixture();
    asA(() -> service.upsertPolicy(a, new AgingPolicyUpsertRequest(skuA, 10, 20, true, null)));

    // B 租户放一行躺在 120 天前的货
    var bLot = UUID.randomUUID();
    access.run(actorB, "warehouse.inventory.read", () -> {
      sql.execute(
          "insert into inventory_lot(id,tenant_id,sku_id,lot_code,expires_on,received_on,"
              + "origin,source_system) values(?,?,?,?,null,?::date,'MANUAL','PURCHASE')",
          bLot, b, skuB, "LOT-B-TENANT", dbToday.minusDays(120));
      return null;
    });
    balance(actorB, b, skuB, whB, leB, qSellableB, List.of(new B(bLot, locB1, "80", "0")));

    // A 只看得到自己那几行（阈值配到 10 天后 6 行命中，L0 库龄 0 天不算）
    assertThat(list().total()).isEqualTo(6L);
    assertThat(list().rows()).extracting(SlowMovingRow::lotCode)
        .doesNotContain("LOT-B-TENANT");

    // B 只看得到自己那一行
    var pageB = asB(() -> service.slowMoving(
        new SlowMovingQuery(null, null, null, null, null, null, null, 0, 50)));
    assertThat(pageB.total()).isEqualTo(1L);
    assertThat(pageB.rows()).extracting(SlowMovingRow::lotCode).containsExactly("LOT-B-TENANT");

    // 分桶报表同样隔离
    var reportA = report();
    assertThat(reportA.totals().available()).isEqualByComparingTo("70");
    var reportB = asB(() -> service.bucketReport(
        new AgingBucketQuery(null, null, null, null, null)));
    assertThat(reportB.totals().available()).isEqualByComparingTo("80");

    // A 的 SKU 在 B 那里查不到策略 → 404，不泄露存在性
    assertThatThrownBy(() -> asB(() -> service.policy(skuA)))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(404));

    // A 配的策略在 B 的策略列表里一条都没有
    assertThat(asB(() -> service.policies(new AgingPolicyQuery(null, null, null, null, 0, 50))).total())
        .isZero();
  }

  @Test
  void 断言二_伪造租户头无效() throws Exception {
    agingFixture();
    var bLot = UUID.randomUUID();
    access.run(actorB, "warehouse.inventory.read", () -> {
      sql.execute(
          "insert into inventory_lot(id,tenant_id,sku_id,lot_code,expires_on,received_on,"
              + "origin,source_system) values(?,?,?,?,null,?::date,'MANUAL','PURCHASE')",
          bLot, b, skuB, "LOT-B-TENANT", dbToday.minusDays(120));
      return null;
    });
    balance(actorB, b, skuB, whB, leB, qSellableB, List.of(new B(bLot, locB1, "80", "0")));

    // 身份是 B 但伪造 X-Tenant-Id 指向 A → 只能看到 B 自己的货
    http.perform(get("/warehouse/inventory/aging/slow-moving")
            .header("Authorization", "Bearer tenant-b")
            .header("X-Tenant-Id", a.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.rows[0].lotCode").value("LOT-B-TENANT"));

    // 反向：身份是 A 伪造 B 的头也拿不到 B 的数据
    http.perform(get("/warehouse/inventory/aging/slow-moving")
            .header("Authorization", "Bearer tenant-a")
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(4));

    // 分桶报表同样不受伪造头影响
    http.perform(get("/warehouse/inventory/aging/buckets")
            .header("Authorization", "Bearer tenant-b")
            .header("X-Tenant-Id", a.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totals.available").value(80.0));

    // 策略写入不接受伪造头：只能写到身份自己的租户
    http.perform(post("/warehouse/inventory/aging/policy")
            .header("Authorization", "Bearer tenant-b")
            .header("X-Tenant-Id", a.toString())
            .contentType("application/json")
            .content("{\"skuId\":\"" + skuB + "\",\"slowMovingDays\":15,\"obsoleteDays\":45}"))
        .andExpect(status().isOk());
    assertThat(asB(() -> service.policy(skuB)).slowMovingDays()).isEqualTo(15);
    assertThat(asA(() -> service.policy(skuA)).source()).isEqualTo("DEFAULT");

    // 未认证 401；只有 read 权限不能写策略
    http.perform(get("/warehouse/inventory/aging/buckets")).andExpect(status().isUnauthorized());
    http.perform(post("/warehouse/inventory/aging/policy")
            .header("Authorization", "Bearer self-only")
            .contentType("application/json")
            .content("{\"skuId\":\"" + skuA + "\",\"slowMovingDays\":10}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void 断言三_连接池归还后不串租户() {
    agingFixture();
    asA(() -> service.upsertPolicy(a, new AgingPolicyUpsertRequest(skuA, 10, 20, true, null)));
    var bLot = UUID.randomUUID();
    access.run(actorB, "warehouse.inventory.read", () -> {
      sql.execute(
          "insert into inventory_lot(id,tenant_id,sku_id,lot_code,expires_on,received_on,"
              + "origin,source_system) values(?,?,?,?,null,?::date,'MANUAL','PURCHASE')",
          bLot, b, skuB, "LOT-B-TENANT", dbToday.minusDays(120));
      return null;
    });
    balance(actorB, b, skuB, whB, leB, qSellableB, List.of(new B(bLot, locB1, "80", "0")));

    // 池大小为 1，同一条连接被反复借还：交替访问两个租户 20 轮，
    // 每一轮都要同时校验分桶报表、呆滞清单、策略三条路径都不串租户。
    for (int i = 0; i < 20; i++) {
      assertThat(report().totals().available()).isEqualByComparingTo("70");
      assertThat(asB(() -> service.bucketReport(
              new AgingBucketQuery(null, null, null, null, null)))
          .totals().available()).isEqualByComparingTo("80");

      assertThat(list().total()).isEqualTo(6L);
      assertThat(asB(() -> service.slowMoving(
              new SlowMovingQuery(null, null, null, null, null, null, null, 0, 50)))
          .total()).isEqualTo(1L);

      assertThat(asA(() -> service.policy(skuA)).slowMovingDays()).isEqualTo(10);
      assertThatThrownBy(() -> asB(() -> service.policy(skuA)))
          .isInstanceOf(ResponseStatusException.class);
    }
  }
}
