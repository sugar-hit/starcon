package com.starcon.warehouse;

import static com.starcon.warehouse.InventoryExpiryModels.*;
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
 * 7.3.1.2 效期与先进先出：FEFO / FIFO / 指定批次三种分配策略、过期排除、临期阈值可配置、 策略开关，以及租户隔离三断言。
 *
 * <p>真实 PostgreSQL 16 + 强制 RLS，不使用任何内存替身。连接池固定为 1 条连接： 只有复用同一条连接，才能真正证明归还后不会串租户。
 *
 * <p>夹具与写入约定与 {@code InventoryLotIntegrationTest} 完全一致（同样的 Testcontainers、 同样的 fake identity
 * server、同样的按租户派生 UUID、同样的 TRUNCATE 清场）。
 *
 * <p><b>最关键的一个用例是 FEFO 与 FIFO 的顺序断言</b>：夹具刻意让「失效日期先后」与 「收货日期先后」完全相反（先到期的反而后入库），于是两种策略排出的批次序列必然不同 ——
 * 这样断言才真的钉住了「两种排序」，而不是两个策略碰巧排出了同一个顺序。
 */
@SpringBootTest
@AutoConfigureMockMvc
class InventoryExpiryIntegrationTest {
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

  static final UUID leA = idFor(a, 1);
  static final UUID skuA = idFor(a, 2);
  static final UUID skuA2 = idFor(a, 3);
  static final UUID whSh = idFor(a, 4);
  static final UUID qSellable = idFor(a, 6);
  static final UUID qHold = idFor(a, 7);
  static final UUID locSh1 = idFor(a, 8);
  static final UUID locSh2 = idFor(a, 9);
  static final UUID locFrozen = idFor(a, 10);

  static final UUID leB = idFor(b, 1);
  static final UUID skuB = idFor(b, 2);
  static final UUID whB = idFor(b, 4);
  static final UUID qSellableB = idFor(b, 6);
  static final UUID locB1 = idFor(b, 8);

  static UUID idFor(UUID tenant, int ordinal) {
    return UUID.nameUUIDFromBytes(
        ("starcon-warehouse-expiry-test-" + tenant + "-" + ordinal).getBytes());
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
        s.execute("create role warehouse_app login password 'test-only' nosuperuser nobypassrls");
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

  @Autowired InventoryExpiryService service;
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
          "truncate inventory_expiry_policy, stock_bucket, warehouse_location, inventory_lot,"
              + " inventory_entry, inventory_transaction, inventory_balance,"
              + " inventory_quality_status, warehouse, sku_ref, legal_entity_ref"
              + " restart identity cascade");
      try (var rs = s.executeQuery("select current_date as d")) {
        rs.next();
        dbToday = rs.getObject(1, LocalDate.class);
      }
    }
    access.run(
        actorA,
        "warehouse.inventory.read",
        () -> {
          sql.execute(
              "insert into legal_entity_ref(id,tenant_id,code,name) values(?,?,?,?)",
              leA,
              a,
              "LE-1",
              "主体一");
          sql.execute(
              "insert into sku_ref(id,tenant_id,code,name) values(?,?,?,?)",
              skuA,
              a,
              "SKU-1",
              "效期商品");
          sql.execute(
              "insert into warehouse(id,tenant_id,code,name,country_code,timezone) values(?,?,?,?,?,?)",
              whSh,
              a,
              "WH-SH",
              "上海仓",
              "CN",
              "Asia/Shanghai");
          sql.execute(
              "insert into inventory_quality_status(id,tenant_id,code,name,role) values(?,?,?,?,?)",
              qSellable,
              a,
              "SELLABLE",
              "可售",
              "SELLABLE");
          sql.execute(
              "insert into inventory_quality_status(id,tenant_id,code,name,role) values(?,?,?,?,?)",
              qHold,
              a,
              "PENDING_INSPECTION",
              "待检",
              "PENDING_INSPECTION");
          sql.execute(
              "insert into warehouse_location(id,tenant_id,warehouse_id,code,name,type,pickable)"
                  + " values(?,?,?,?,?,?,?)",
              locSh1,
              a,
              whSh,
              "SH-01",
              "上海一区",
              "STORAGE",
              true);
          sql.execute(
              "insert into warehouse_location(id,tenant_id,warehouse_id,code,name,type,pickable)"
                  + " values(?,?,?,?,?,?,?)",
              locSh2,
              a,
              whSh,
              "SH-02",
              "上海二区",
              "STORAGE",
              true);
          sql.execute(
              "insert into warehouse_location(id,tenant_id,warehouse_id,code,name,type,pickable)"
                  + " values(?,?,?,?,?,?,?)",
              locFrozen,
              a,
              whSh,
              "SH-FROZEN",
              "冻结区",
              "FROZEN",
              false);
          return null;
        });
    access.run(
        actorB,
        "warehouse.inventory.read",
        () -> {
          sql.execute(
              "insert into legal_entity_ref(id,tenant_id,code,name) values(?,?,?,?)",
              leB,
              b,
              "LE-1",
              "主体一");
          sql.execute(
              "insert into sku_ref(id,tenant_id,code,name) values(?,?,?,?)",
              skuB,
              b,
              "SKU-B",
              "B 租户商品");
          sql.execute(
              "insert into warehouse(id,tenant_id,code,name,country_code,timezone) values(?,?,?,?,?,?)",
              whB,
              b,
              "WH-BJ",
              "北京仓",
              "CN",
              "Asia/Shanghai");
          sql.execute(
              "insert into inventory_quality_status(id,tenant_id,code,name,role) values(?,?,?,?,?)",
              qSellableB,
              b,
              "SELLABLE",
              "可售",
              "SELLABLE");
          sql.execute(
              "insert into warehouse_location(id,tenant_id,warehouse_id,code,name,type,pickable)"
                  + " values(?,?,?,?,?,?,?)",
              locB1,
              b,
              whB,
              "BJ-01",
              "北京一区",
              "STORAGE",
              true);
          return null;
        });
  }

  // ================================================================ 夹具写入

  /** 一条 bucket 的夹具描述。质量状态按 balance 取，同一 balance 下的 bucket 必须共享。 */
  record B(UUID lotId, UUID location, String onHand, String reserved) {}

  /**
   * 建余额行并按库位/批次拆成若干 bucket，**同一事务**写入。
   *
   * <p>必须同一事务：V2 的守恒（{@code guard_bucket_conservation}）是延迟约束触发器， 提交前才把 balance 与它的 bucket
   * 汇总对平，分两次写第二次提交就会报不平。
   *
   * <p>同一仓 × SKU × 货权法人 × 质量状态只有**一条** balance（唯一键里没有批次）， 所以同仓同质量状态下的多个批次必须共用这条 balance。
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
    access.run(
        actor,
        "warehouse.inventory.read",
        () -> {
          var onHand = BigDecimal.ZERO;
          var reserved = BigDecimal.ZERO;
          for (var x : buckets) {
            onHand = onHand.add(new BigDecimal(x.onHand()));
            reserved = reserved.add(new BigDecimal(x.reserved()));
          }
          sql.execute(
              "insert into inventory_balance(id,tenant_id,sku_id,warehouse_id,legal_entity_id,"
                  + "quality_status_id,on_hand,reserved) values(?,?,?,?,?,?,?,?)",
              balanceId,
              tenant,
              sku,
              warehouse,
              legalEntity,
              quality,
              onHand,
              reserved);
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

  /** 建批次。expiresOn 传 null 表示非效期品；收货日期单独给（FIFO 的排序依据）。 */
  UUID lot(String code, LocalDate expiresOn, LocalDate receivedOn) {
    var id = UUID.randomUUID();
    access.run(
        actorA,
        "warehouse.inventory.read",
        () -> {
          sql.execute(
              "insert into inventory_lot(id,tenant_id,sku_id,lot_code,expires_on,received_on,"
                  + "origin,source_system,source_ref_id,source_ref_code)"
                  + " values(?,?,?,?,?::date,?::date,'MANUAL','PURCHASE',?,'PO-EXPIRY')",
              id,
              a,
              skuA,
              code,
              expiresOn,
              receivedOn,
              UUID.randomUUID());
          return null;
        });
    return id;
  }

  /** 建租户 B 的批次（只用于隔离断言）。 */
  UUID lotB(String code) {
    var id = UUID.randomUUID();
    access.run(
        actorB,
        "warehouse.inventory.read",
        () -> {
          sql.execute(
              "insert into inventory_lot(id,tenant_id,sku_id,lot_code,expires_on,received_on,origin)"
                  + " values(?,?,?,?,current_date+3,current_date-1,'MANUAL')",
              id,
              b,
              skuB,
              code);
          return null;
        });
    return id;
  }

  /**
   * 建一个**同租户但归属另一个 SKU** 的批次。
   *
   * <p>专供「指定批次跨 SKU」那条 DB 触发器用例：外键 {@code (tenant_id, specified_lot_id)}
   * 只保证批次在本租户存在，这个批次在本租户，所以外键会放行 —— 必须由 {@code guard_expiry_policy_lot_sku} 挡住。用别的租户的批次测不出这一条，
   * 因为它会先被外键拦下，根本走不到触发器。
   */
  UUID lotOfOtherSku(String code) {
    var id = UUID.randomUUID();
    access.run(
        actorA,
        "warehouse.inventory.read",
        () -> {
          if (sql.fetchOne("select s.id from sku_ref s where s.id=?", skuA2) == null) {
            sql.execute(
                "insert into sku_ref(id,tenant_id,code,name) values(?,?,?,?)",
                skuA2,
                a,
                "SKU-2",
                "另一个商品");
          }
          sql.execute(
              "insert into inventory_lot(id,tenant_id,sku_id,lot_code,expires_on,received_on,origin)"
                  + " values(?,?,?,?,current_date+30,current_date-2,'MANUAL')",
              id,
              a,
              skuA2,
              code);
          return null;
        });
    return id;
  }

  <T> T asA(java.util.function.Supplier<T> work) {
    return access.run(actorA, "warehouse.inventory.read", work::get);
  }

  <T> T asB(java.util.function.Supplier<T> work) {
    return access.run(actorB, "warehouse.inventory.read", work::get);
  }

  /**
   * 铺好「失效顺序与入库顺序完全相反」的一组批次。
   *
   * <p>这是整个测试类最重要的夹具：先到期的反而后入库，于是 FEFO 排出来是 A→C→B，FIFO 排出来是 B→C→A，两种策略的顺序必然不同。
   * 如果两者碰巧同序，就断言不出「两种排序」的区别。
   *
   * <p>另有两个反例批次：已过期的 lotOld、以及没有失效日期的 lotNoExp。
   */
  record Fixture(UUID lotA, UUID lotB, UUID lotC, UUID lotOld, UUID lotNoExp) {}

  Fixture expiryFixture() {
    var lotA = lot("LOT-A", today(3), today(-5)); // 最早到期，但较晚入库
    var lotB = lot("LOT-B", today(20), today(-30)); // 最晚到期，却最早入库
    var lotC = lot("LOT-C", today(10), today(-15)); // 居中
    var lotOld = lot("LOT-OLD", today(-5), today(-40)); // 已过期
    var lotNoExp = lot("LOT-NOEXP", null, today(-1)); // 非效期品

    // 可售质量状态只有**一条** balance（唯一键 tenant+sku+warehouse+legal_entity+quality 里没有批次），
    // 所以同仓同质量状态下的所有批次（含不可拣库位上的）必须共用它，拆成两次插入会撞唯一约束。
    // 合计 = 50+50+50+20+30（可拣）+ 40（冻结区）= 240
    balance(
        actorA,
        a,
        skuA,
        whSh,
        leA,
        qSellable,
        List.of(
            new B(lotA, locSh1, "50", "0"),
            new B(lotB, locSh1, "50", "0"),
            new B(lotC, locSh2, "50", "0"),
            new B(lotOld, locSh2, "20", "0"),
            new B(lotNoExp, locSh2, "30", "0"),
            new B(lotB, locFrozen, "40", "0")));
    // 待检质量状态另起一条 balance：验证「非可售不参与出库」
    balance(actorA, a, skuA, whSh, leA, qHold, List.of(new B(lotC, locSh2, "15", "0")));
    return new Fixture(lotA, lotB, lotC, lotOld, lotNoExp);
  }

  /**
   * 数据库当天。
   *
   * <p>刻意从 PG 读而不是用 {@code LocalDate.now()}：服务层的过期判定用的是 {@code current_date}（数据库当天），而容器时区通常是
   * UTC。JVM 在东八区， 16:00~24:00 之间两边的「今天」会差一天 —— 那会让 `daysToExpiry` 的断言变成 「看跑测试的时刻而定」。基准必须与被测逻辑同源。
   */
  static LocalDate dbToday;

  private static LocalDate today(int days) {
    return dbToday.plusDays(days);
  }

  FefoAllocationRequest req(String quantity) {
    return new FefoAllocationRequest(skuA, whSh, new BigDecimal(quantity), null, null);
  }

  FefoAllocationRequest req(String quantity, String strategy) {
    return new FefoAllocationRequest(skuA, whSh, new BigDecimal(quantity), strategy, null);
  }

  /** 配一条策略（走真实服务，upsert 语义）。 */
  ExpiryPolicySaved policy(String strategy, boolean enabled, Integer leadDays, UUID lotId) {
    return asA(
        () ->
            service.upsertPolicy(
                a, new ExpiryPolicyUpsertRequest(skuA, strategy, enabled, leadDays, lotId, null)));
  }

  // ================================================================ FEFO

  @Test
  void FEFO按失效日期升序推荐_先到期的先出() {
    var f = expiryFixture();
    policy("FEFO", true, 30, null);

    var plan = asA(() -> service.recommend(req("100")));

    assertThat(plan.strategy()).isEqualTo("FEFO");
    assertThat(plan.policyEnabled()).isTrue();
    assertThat(plan.strategySource()).isEqualTo("POLICY");
    // 失效顺序 A(+3) → C(+10) → B(+20)：先到期的先出，前两批正好凑满 100。
    assertThat(plan.lines())
        .extracting(FefoAllocationLine::lotId)
        .containsExactly(f.lotA(), f.lotC());
    assertThat(plan.lines())
        .extracting(FefoAllocationLine::quantity)
        .allMatch(q -> q.compareTo(new BigDecimal("50")) == 0);
    assertThat(plan.allocatedQuantity()).isEqualByComparingTo("100");
    assertThat(plan.shortfallQuantity()).isEqualByComparingTo("0");
    assertThat(plan.earliestExpiry()).isEqualTo(today(3));
    // 排序依据必须是失效日期本身，而不是任何时间戳。
    assertThat(plan.lines().get(0).sortBasis()).isEqualTo("失效日期");
    assertThat(plan.lines().get(0).sortBasisValue()).isEqualTo(today(3).toString());

    // 过期 / 非效期 / 不可拣 / 非可售四类都要被排除并如实报出量。
    var in = plan.ineligible();
    assertThat(in.expiredAvailable()).isEqualByComparingTo("20"); // LOT-OLD
    assertThat(in.noExpiryAvailable()).isEqualByComparingTo("30"); // LOT-NOEXP 不参与 FEFO
    assertThat(in.nonPickableAvailable()).isEqualByComparingTo("40"); // 冻结区
    assertThat(in.nonSellableAvailable()).isEqualByComparingTo("15"); // 待检
    assertThat(in.noLotAvailable()).isEqualByComparingTo("0");
  }

  @Test
  void FEFO分配到批次的量是批次精确的_等于各库位取用量之和() {
    var f = expiryFixture();
    policy("FEFO", true, 30, null);

    // 取 80：LOT-A 只有 50，所以必须跨到 LOT-C —— 这证明分配是按 bucket 走的真实取用，不是按批次估算。
    var plan = asA(() -> service.recommend(req("80")));

    assertThat(plan.lines()).hasSize(2);
    assertThat(plan.lines().get(0).quantity()).isEqualByComparingTo("50");
    assertThat(plan.lines().get(1).quantity()).isEqualByComparingTo("30");
    for (var line : plan.lines()) {
      var bucketSum = BigDecimal.ZERO;
      for (var bucket : line.buckets()) bucketSum = bucketSum.add(bucket.quantity());
      assertThat(bucketSum).isEqualByComparingTo(line.quantity());
    }
    assertThat(plan.lines().get(1).lotId()).isEqualTo(f.lotC());
  }

  // ================================================================ FIFO

  @Test
  void FIFO按入库日期升序推荐_与FEFO顺序完全不同() {
    var f = expiryFixture();
    policy("FIFO", true, 30, null);

    var plan = asA(() -> service.recommend(req("100")));

    assertThat(plan.strategy()).isEqualTo("FIFO");
    // 入库顺序 B(-30) → C(-15) → A(-5)：与 FEFO 的 A → C → B 完全相反。
    assertThat(plan.lines())
        .extracting(FefoAllocationLine::lotId)
        .containsExactly(f.lotB(), f.lotC());
    assertThat(plan.lines().get(0).lotId()).isNotEqualTo(f.lotA());
    assertThat(plan.lines().get(0).sortBasis()).isEqualTo("入库时间");
    assertThat(plan.lines().get(0).sortBasisValue()).isEqualTo(today(-30).toString());
    assertThat(plan.allocatedQuantity()).isEqualByComparingTo("100");
  }

  @Test
  void FIFO可取没有失效日期的批次_过期批次在任何策略下都不出库() {
    var f = expiryFixture();
    policy("FIFO", true, 30, null);

    // 可分配的总量是 50+50+50+30 = 180（LOT-NOEXP 可取，LOT-OLD 已过期不可取）。
    var plan = asA(() -> service.recommend(req("250")));

    assertThat(plan.allocatedQuantity()).isEqualByComparingTo("180");
    assertThat(plan.shortfallQuantity()).isEqualByComparingTo("70");
    assertThat(plan.lines())
        .extracting(FefoAllocationLine::lotId)
        .containsExactly(f.lotB(), f.lotC(), f.lotA(), f.lotNoExp());
    assertThat(plan.lines()).extracting(FefoAllocationLine::lotId).doesNotContain(f.lotOld());
    // FIFO 下非效期批次是可用的，因此这一项必须是 0（否则说明 FEFO 的排除逻辑串到了 FIFO）。
    assertThat(plan.ineligible().noExpiryAvailable()).isEqualByComparingTo("0");
    assertThat(plan.ineligible().expiredAvailable()).isEqualByComparingTo("20");
  }

  @Test
  void 缺少收货日期时FIFO退回bucket建行时间排序() {
    // 收货日期为空的批次：FIFO 的排序依据必须来自 stock_bucket.created_at，
    // 也就是「货是什么时候进到这个库位的」—— 不能直接把这些批次排除掉。
    var x = lot("LOT-NORECEIPT", today(50), null);
    var y = lot("LOT-NORECEIPT2", today(60), null);
    balance(
        actorA,
        a,
        skuA,
        whSh,
        leA,
        qSellable,
        List.of(new B(x, locSh1, "10", "0"), new B(y, locSh2, "10", "0")));
    policy("FIFO", true, 30, null);

    var plan = asA(() -> service.recommend(req("15")));

    assertThat(plan.allocatedQuantity()).isEqualByComparingTo("15");
    assertThat(plan.lines()).hasSize(2);
    assertThat(plan.lines().get(0).lotId()).isEqualTo(x);
    assertThat(plan.lines().get(0).receivedOn()).isNull();
    assertThat(plan.lines().get(0).sortBasis()).isEqualTo("入库时间");
    assertThat(plan.lines().get(0).sortBasisValue()).isNotBlank();
  }

  // ================================================================ 指定批次

  @Test
  void 指定批次策略只从该批次取_不足时如实报出缺口() {
    var f = expiryFixture();
    policy("SPECIFIED_LOT", true, 30, f.lotC());

    var plan = asA(() -> service.recommend(req("100")));

    assertThat(plan.strategy()).isEqualTo("SPECIFIED_LOT");
    assertThat(plan.lines()).extracting(FefoAllocationLine::lotId).containsExactly(f.lotC());
    assertThat(plan.allocatedQuantity()).isEqualByComparingTo("50");
    assertThat(plan.shortfallQuantity()).isEqualByComparingTo("50");
    assertThat(plan.notes()).anyMatch(n -> n.contains("缺口"));
  }

  @Test
  void 请求显式指定策略时覆盖SKU配置并标明来源() {
    expiryFixture();
    policy("FEFO", true, 30, null);

    var plan = asA(() -> service.recommend(req("10", "FIFO")));

    assertThat(plan.strategy()).isEqualTo("FIFO");
    assertThat(plan.strategySource()).isEqualTo("REQUEST");
  }

  // ================================================================ 策略开关

  @Test
  void 策略未启用时退回按库位顺序取_并如实报出开关关闭() {
    expiryFixture();
    policy("FEFO", false, 30, null);

    var plan = asA(() -> service.recommend(req("10")));

    assertThat(plan.policyEnabled()).isFalse();
    assertThat(plan.strategy()).isEqualTo("NONE");
    assertThat(plan.strategyLabel()).contains("未启用策略");
    assertThat(plan.allocatedQuantity()).isEqualByComparingTo("10");
    assertThat(plan.notes()).anyMatch(n -> n.contains("未启用"));
    assertThat(plan.notes()).anyMatch(n -> n.contains("不做效期排序"));
  }

  @Test
  void 没配过策略的SKU返回默认值并标明configured为false() {
    expiryFixture();

    var p = asA(() -> service.policy(skuA));

    assertThat(p.configured()).isFalse();
    assertThat(p.enabled()).isFalse();
    assertThat(p.strategy()).isEqualTo("FEFO");
    assertThat(p.leadDays()).isEqualTo(30);
    assertThat(p.source()).isEqualTo("DEFAULT");
    assertThat(p.nearestExpiry()).isNotNull();
  }

  @Test
  void 策略的临期数按各自的提前天数算_7天与90天结果不同() {
    var f = expiryFixture();

    // 提前 7 天：LOT-A（还有 3 天）与 LOT-C（还有 10 天）里只有 A 进临期。
    policy("FEFO", true, 7, null);
    var seven =
        asA(
            () ->
                service.alerts(
                    new ExpiryAlertQuery(skuA, null, null, null, null, null, null, 0, 50)));
    assertThat(seven.rows())
        .extracting(ExpiryAlertRow::lotId)
        .containsExactly(f.lotOld(), f.lotA());
    assertThat(seven.rows())
        .filteredOn(r -> r.lotId().equals(f.lotA()))
        .singleElement()
        .satisfies(
            r -> {
              assertThat(r.expiryStatus()).isEqualTo("EXPIRING");
              assertThat(r.leadDays()).isEqualTo(7);
              assertThat(r.leadDaysSource()).isEqualTo("POLICY");
            });

    // 提前 90 天：LOT-C 也进临期，且已过期的 LOT-OLD 仍单列在过期档。
    policy("FEFO", true, 90, null);
    var ninety =
        asA(
            () ->
                service.alerts(
                    new ExpiryAlertQuery(skuA, null, null, null, null, null, null, 0, 50)));
    assertThat(ninety.rows())
        .extracting(ExpiryAlertRow::lotId)
        .containsExactly(f.lotOld(), f.lotA(), f.lotC(), f.lotB());
    assertThat(ninety.rows())
        .filteredOn(r -> r.lotId().equals(f.lotC()))
        .singleElement()
        .satisfies(r -> assertThat(r.expiryStatus()).isEqualTo("EXPIRING"));
  }

  // ================================================================ 效期预警

  @Test
  void 过期与临期分列_剩余天数读回真实的正负值() {
    var f = expiryFixture();
    policy("FEFO", true, 30, null);

    var page =
        asA(
            () ->
                service.alerts(
                    new ExpiryAlertQuery(null, null, null, null, null, null, null, 0, 50)));

    // 默认只看「需要处理」的两档：过期 + 临期。30 天窗口内 A(+3)、C(+10)、B(+20) 三批都算临期。
    // LOT-NOEXP（非效期品）不该挤在预警清单里。
    assertThat(page.rows())
        .extracting(ExpiryAlertRow::lotId)
        .containsExactly(f.lotOld(), f.lotA(), f.lotC(), f.lotB());

    var old = rowOf(page, f.lotOld());
    assertThat(old.expiryStatus()).isEqualTo("EXPIRED");
    assertThat(old.expiryStatusLabel()).isEqualTo("已过期");
    // 关键断言：daysToExpiry 必须读回**负数**。纯文本 SQL 里 jOOQ 拿不到列类型，
    // 取值方式不当会让这里变成 null，也就是「过期批次显示成没有效期」。
    assertThat(old.daysToExpiry()).isEqualTo(-5);
    assertThat(old.expiresOn()).isEqualTo(today(-5));

    var a3 = rowOf(page, f.lotA());
    assertThat(a3.expiryStatus()).isEqualTo("EXPIRING");
    assertThat(a3.daysToExpiry()).isEqualTo(3);

    var c10 = rowOf(page, f.lotC());
    assertThat(c10.daysToExpiry()).isEqualTo(10);

    // 汇总：过期与临期的数量与在手量分开报，处置路径不同不能合成一个数。
    // LOT-C 的在手是 65（可售 50 + 待检 15）、LOT-B 是 90（可售 50 + 冻结区 40）：
    // 预警清单按**批次在手**统计，待检与不可拣的货也占着这批货的位置，不能只算可售那部分。
    assertThat(page.summary().expiredLotCount()).isEqualTo(1);
    assertThat(page.summary().expiringLotCount()).isEqualTo(3);
    assertThat(page.summary().expiredOnHand()).isEqualByComparingTo("20");
    assertThat(page.summary().expiringOnHand()).isEqualByComparingTo("205");
    assertThat(page.summary().earliestExpiry()).isEqualTo(today(-5));
    // 60 天窗口下没有正常批次，因此这一项确实是 0（不是被档位筛选收窄掉的）。
    assertThat(page.summary().healthyLotCount()).isEqualTo(0);
    assertThat(page.summary().noExpiryLotCount()).isEqualTo(1);
  }

  @Test
  void 效期档位筛选可以显式要回正常与非效期品() {
    var f = expiryFixture();
    policy("FEFO", true, 1, null); // 1 天窗口：只有 LOT-A(+3) 变正常

    var page =
        asA(
            () ->
                service.alerts(
                    new ExpiryAlertQuery(
                        null,
                        null,
                        List.of("HEALTHY", "NO_EXPIRY"),
                        null,
                        null,
                        null,
                        null,
                        0,
                        50)));

    // 1 天窗口：LOT-A(+3)、LOT-C(+10)、LOT-B(+20) 都超出窗口 → 正常档；LOT-NOEXP → 非效期品档。
    // 默认按剩余天数升序，所以是 A → C → B，无失效日期的排最后。
    assertThat(page.rows())
        .extracting(ExpiryAlertRow::lotId)
        .containsExactly(f.lotA(), f.lotC(), f.lotB(), f.lotNoExp());
    var noExp = rowOf(page, f.lotNoExp());
    assertThat(noExp.expiryStatus()).isEqualTo("NO_EXPIRY");
    assertThat(noExp.expiresOn()).isNull();
    assertThat(noExp.daysToExpiry()).isNull();
    // 汇总覆盖筛选范围内的全档位分布，不因只看两档就退化成「正常数恒为 0」。
    assertThat(page.summary().healthyLotCount()).isEqualTo(3);
    assertThat(page.summary().noExpiryLotCount()).isEqualTo(1);
    assertThat(page.summary().expiredLotCount()).isEqualTo(1);
    assertThat(page.summary().expiredOnHand()).isEqualByComparingTo("20");
  }

  @Test
  void 零库存批次不进预警清单_显式要求时才出现() {
    var f = expiryFixture();
    policy("FEFO", true, 30, null);
    // 刚建、还没收货的批次：没有 bucket，在手为 0。
    var empty = lot("LOT-EMPTY", today(2), today(0));

    var stocked =
        asA(
            () ->
                service.alerts(
                    new ExpiryAlertQuery(null, null, null, null, null, null, null, 0, 50)));
    assertThat(stocked.rows()).extracting(ExpiryAlertRow::lotId).doesNotContain(empty);

    var all =
        asA(
            () ->
                service.alerts(
                    new ExpiryAlertQuery(null, null, null, null, null, false, null, 0, 50)));
    assertThat(all.rows()).extracting(ExpiryAlertRow::lotId).contains(empty);
  }

  @Test
  void 提前天数临时覆盖不改各SKU策略_并如实报出来源() {
    var f = expiryFixture();
    policy("FEFO", true, 1, null); // 策略窗口只有 1 天

    // 不覆盖时 LOT-A(+3) 是正常档（默认只看两档，所以不在列表里）
    var without =
        asA(
            () ->
                service.alerts(
                    new ExpiryAlertQuery(null, null, null, null, null, null, null, 0, 50)));
    assertThat(without.rows()).extracting(ExpiryAlertRow::lotId).doesNotContain(f.lotA());
    assertThat(without.leadDaysOverride()).isNull();
    assertThat(without.leadDaysNote()).contains("逐 SKU");

    // 临时覆盖成 30 天窗口 → LOT-A 进临期，但策略本身没有被改
    var overridden =
        asA(
            () ->
                service.alerts(
                    new ExpiryAlertQuery(null, null, null, 30, null, null, null, 0, 50)));
    assertThat(overridden.rows()).extracting(ExpiryAlertRow::lotId).contains(f.lotA());
    assertThat(overridden.leadDaysOverride()).isEqualTo(30);
    assertThat(overridden.leadDaysNote()).contains("临时覆盖");
    var stillOne = asA(() -> service.policy(skuA));
    assertThat(stillOne.leadDays()).isEqualTo(1);
  }

  private static ExpiryAlertRow rowOf(ExpiryAlertPage page, UUID lotId) {
    return page.rows().stream().filter(r -> r.lotId().equals(lotId)).findFirst().orElseThrow();
  }

  // ================================================================ 策略校验与不可删除

  @Test
  void 指定批次不属于该SKU被拒绝_服务层与DB触发器各兜一道() {
    expiryFixture();
    var otherSkuLot = lotOfOtherSku("LOT-OTHER-SKU");

    // 服务层前置校验：给出可读错误。
    assertThatThrownBy(() -> policy("SPECIFIED_LOT", true, 30, otherSkuLot))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不属于该 SKU");

    // 绕过服务层直接写库：外键（同租户的批次，外键放行）挡不住，
    // 必须由 DB 触发器 guard_expiry_policy_lot_sku 拦下。
    assertThatThrownBy(
            () ->
                asA(
                    () -> {
                      sql.execute(
                          "insert into inventory_expiry_policy(id,tenant_id,sku_id,strategy,"
                              + "enabled,lead_days,specified_lot_id) values(?,?,?,?,?,?,?)",
                          UUID.randomUUID(),
                          a,
                          skuA,
                          "SPECIFIED_LOT",
                          true,
                          30,
                          otherSkuLot);
                      return null;
                    }))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    // 触发器拒绝后确实没有留下行
    assertThat(
            asA(() -> service.policies(new ExpiryPolicyQuery(null, null, null, null, null, 0, 50)))
                .total())
        .isEqualTo(0);
  }

  @Test
  void 指定批次策略必须给批次_超范围与未知策略当场400() {
    expiryFixture();
    assertThatThrownBy(() -> policy("SPECIFIED_LOT", true, 30, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("必须给出 specifiedLotId");
    assertThatThrownBy(() -> policy("FEFO", true, 99999, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("0 ~ 3650");
    assertThatThrownBy(() -> policy("LIFO", true, 30, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("未知的出库分配策略");
  }

  @Test
  void 策略行不可删除_停用走enabled为false() {
    expiryFixture();
    policy("FEFO", true, 30, null);

    assertThatThrownBy(
            () ->
                asA(
                    () -> {
                      sql.execute("delete from inventory_expiry_policy where tenant_id=?", a);
                      return null;
                    }))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);

    var disabled = policy("FEFO", false, 30, null);
    assertThat(disabled.enabled()).isFalse();
    var p = asA(() -> service.policy(skuA));
    assertThat(p.configured()).isTrue();
    assertThat(p.enabled()).isFalse();
  }

  @Test
  void 同一SKU重复提交策略是改不是冲突() {
    expiryFixture();
    policy("FEFO", true, 7, null);
    var second = policy("FIFO", true, 90, null);

    assertThat(second.strategy()).isEqualTo("FIFO");
    assertThat(second.leadDays()).isEqualTo(90);
    var page =
        asA(() -> service.policies(new ExpiryPolicyQuery(null, null, null, null, null, 0, 50)));
    assertThat(page.total()).isEqualTo(1);
    assertThat(page.summary().fefoCount()).isEqualTo(0);
    assertThat(page.summary().fifoCount()).isEqualTo(1);
  }

  // ================================================================ 输入校验

  @Test
  void 分配请求的非法输入当场400而不是静默处理() {
    expiryFixture();
    assertThatThrownBy(() -> asA(() -> service.recommend(req("0"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("必须大于 0");
    assertThatThrownBy(() -> asA(() -> service.recommend(req("-5"))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.recommend(new FefoAllocationRequest(skuA, whSh, null, null, null))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("出库数量不能为空");
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.alerts(
                            new ExpiryAlertQuery(
                                null, null, List.of("SOON"), null, null, null, null, 0, 50))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("未知的效期档位");
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.alerts(
                            new ExpiryAlertQuery(null, null, null, -1, null, null, null, 0, 50))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("0 ~ 3650");
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.policies(
                            new ExpiryPolicyQuery(null, null, null, null, "DROP_TABLE", 0, 50))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不支持的排序键");
  }

  // ================================================================ 租户隔离三断言

  @Test
  void 断言一_两租户互不可见() {
    var f = expiryFixture();
    policy("FEFO", true, 30, null);
    var lotB = lotB("LOT-B-TENANT");
    balance(actorB, b, skuB, whB, leB, qSellableB, List.of(new B(lotB, locB1, "80", "0")));

    // A 只看得到自己的批次与分配
    var pageA =
        asA(
            () ->
                service.alerts(
                    new ExpiryAlertQuery(null, null, null, null, null, null, null, 0, 50)));
    assertThat(pageA.rows())
        .extracting(ExpiryAlertRow::lotId)
        .containsExactly(f.lotOld(), f.lotA(), f.lotC(), f.lotB());

    // B 只看得到自己的
    var pageB =
        asB(
            () ->
                service.alerts(
                    new ExpiryAlertQuery(null, null, null, null, null, null, null, 0, 50)));
    assertThat(pageB.rows()).extracting(ExpiryAlertRow::lotId).containsExactly(lotB);

    // A 的 SKU 在 B 那里查不到策略 → 404，不泄露存在性
    assertThatThrownBy(() -> asB(() -> service.policy(skuA)))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(404));
    // B 的批次列表也不含 A 的
    assertThat(asB(() -> service.lots(skuB)))
        .extracting(ExpiryLotOption::lotId)
        .containsExactly(lotB);
    // A 配的策略在 B 的策略列表里一条都没有
    assertThat(
            asB(() -> service.policies(new ExpiryPolicyQuery(null, null, null, null, null, 0, 50)))
                .total())
        .isEqualTo(0);
  }

  @Test
  void 断言二_伪造租户头无效() throws Exception {
    expiryFixture();
    policy("FEFO", true, 30, null);
    var lotB = lotB("LOT-B-TENANT");
    balance(actorB, b, skuB, whB, leB, qSellableB, List.of(new B(lotB, locB1, "80", "0")));

    // 身份是 B 但伪造 X-Tenant-Id 指向 A → 只能看到 B 自己的预警
    http.perform(
            get("/warehouse/inventory/expiry/alerts")
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.rows[0].lotCode").value("LOT-B-TENANT"));

    // 反向：身份是 A 伪造 B 的头也拿不到 B 的数据
    http.perform(
            get("/warehouse/inventory/expiry/alerts")
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rows[0].lotCode").value("LOT-OLD"));

    // 分配推荐同样不受伪造头影响
    http.perform(
            post("/warehouse/inventory/expiry/allocation")
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a.toString())
                .contentType("application/json")
                .content(
                    "{\"skuId\":\""
                        + skuA
                        + "\",\"warehouseId\":\""
                        + whSh
                        + "\",\"quantity\":10}"))
        .andExpect(status().isNotFound());

    // 策略写入同样不接受伪造头（只能写到自己的租户）
    http.perform(
            post("/warehouse/inventory/expiry/policy")
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a.toString())
                .contentType("application/json")
                .content(
                    "{\"skuId\":\""
                        + skuB
                        + "\",\"strategy\":\"FEFO\",\"enabled\":true,\"leadDays\":15}"))
        .andExpect(status().isOk());
    assertThat(asB(() -> service.policy(skuB)).leadDays()).isEqualTo(15);
    assertThat(asA(() -> service.policy(skuA)).leadDays()).isEqualTo(30);

    // 未认证 401
    http.perform(get("/warehouse/inventory/expiry/alerts")).andExpect(status().isUnauthorized());
    // 写策略要 update 权限：只有 read 的角色不能改防错发的配置
    http.perform(
            post("/warehouse/inventory/expiry/policy")
                .header("Authorization", "Bearer self-only")
                .contentType("application/json")
                .content("{\"skuId\":\"" + skuA + "\",\"strategy\":\"FEFO\"}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void 断言三_连接池归还后不串租户() {
    var f = expiryFixture();
    policy("FEFO", true, 30, null);
    var lotB = lotB("LOT-B-TENANT");
    balance(actorB, b, skuB, whB, leB, qSellableB, List.of(new B(lotB, locB1, "80", "0")));

    // 池大小为 1，同一条连接被反复借还：交替访问两个租户 20 轮，
    // 每一轮都要同时校验预警清单、分配推荐、策略三条路径都不串租户。
    for (int i = 0; i < 20; i++) {
      var pageA =
          asA(
              () ->
                  service.alerts(
                      new ExpiryAlertQuery(null, null, null, null, null, null, null, 0, 50)));
      var pageB =
          asB(
              () ->
                  service.alerts(
                      new ExpiryAlertQuery(null, null, null, null, null, null, null, 0, 50)));
      assertThat(pageA.rows())
          .extracting(ExpiryAlertRow::lotId)
          .containsExactly(f.lotOld(), f.lotA(), f.lotC(), f.lotB());
      assertThat(pageB.rows()).extracting(ExpiryAlertRow::lotId).containsExactly(lotB);

      var planA = asA(() -> service.recommend(req("10")));
      assertThat(planA.skuCode()).isEqualTo("SKU-1");
      assertThat(planA.strategy()).isEqualTo("FEFO");
      assertThat(planA.allocatedQuantity()).isEqualByComparingTo("10");

      var planB =
          asB(
              () ->
                  service.recommend(
                      new FefoAllocationRequest(skuB, whB, new BigDecimal("10"), null, null)));
      assertThat(planB.skuCode()).isEqualTo("SKU-B");
      assertThat(planB.allocatedQuantity()).isEqualByComparingTo("10");

      assertThat(asA(() -> service.policy(skuA)).leadDays()).isEqualTo(30);
      assertThatThrownBy(() -> asB(() -> service.policy(skuA)))
          .isInstanceOf(ResponseStatusException.class);
    }
  }

  // ================================================================ 端点层

  @Test
  void 端点_策略配置后分配推荐与预警清单可读() throws Exception {
    expiryFixture();

    http.perform(
            post("/warehouse/inventory/expiry/policy")
                .header("Authorization", "Bearer tenant-a")
                .contentType("application/json")
                .content(
                    "{\"skuId\":\""
                        + skuA
                        + "\",\"strategy\":\"FEFO\",\"enabled\":true,\"leadDays\":30}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.strategy").value("FEFO"))
        .andExpect(jsonPath("$.enabled").value(true))
        .andExpect(jsonPath("$.leadDays").value(30));

    http.perform(
            post("/warehouse/inventory/expiry/allocation")
                .header("Authorization", "Bearer tenant-a")
                .contentType("application/json")
                .content(
                    "{\"skuId\":\""
                        + skuA
                        + "\",\"warehouseId\":\""
                        + whSh
                        + "\",\"quantity\":60}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.strategy").value("FEFO"))
        .andExpect(jsonPath("$.policyEnabled").value(true))
        .andExpect(jsonPath("$.allocatedQuantity").value(60))
        .andExpect(jsonPath("$.lines[0].quantity").value(50))
        .andExpect(jsonPath("$.lines[1].quantity").value(10))
        .andExpect(jsonPath("$.lines[0].buckets[0].locationCode").value("SH-01"))
        // 缺口与不可用量分项都要随响应给出
        .andExpect(jsonPath("$.ineligible.expiredAvailable").value(20));

    http.perform(
            get("/warehouse/inventory/expiry/alerts").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.summary.expiredLotCount").value(1))
        .andExpect(jsonPath("$.summary.expiringLotCount").value(3))
        .andExpect(jsonPath("$.statusOptions[0].value").exists())
        .andExpect(jsonPath("$.leadDaysNote").exists());

    http.perform(
            get("/warehouse/inventory/expiry/policies").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.rows[0].skuCode").value("SKU-1"))
        .andExpect(jsonPath("$.rows[0].expiringLotCount").value(3));

    http.perform(
            get("/warehouse/inventory/expiry/policy/" + skuA)
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.configured").value(true))
        .andExpect(jsonPath("$.nearestExpiry").exists());

    http.perform(
            get("/warehouse/inventory/expiry/warehouses")
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].code").value("WH-SH"));

    http.perform(
            get("/warehouse/inventory/expiry/lots/" + skuA)
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(5));
  }

  @Test
  void 端点_非法参数返回400且无权限返回401() throws Exception {
    http.perform(
            get("/warehouse/inventory/expiry/alerts")
                .header("Authorization", "Bearer tenant-a")
                .param("expiryStatus", "SOON"))
        .andExpect(status().isBadRequest());
    http.perform(
            get("/warehouse/inventory/expiry/alerts")
                .header("Authorization", "Bearer tenant-a")
                .param("leadDays", "-1"))
        .andExpect(status().isBadRequest());
    http.perform(
            get("/warehouse/inventory/expiry/alerts")
                .header("Authorization", "Bearer tenant-a")
                .param("sort", "DROP_TABLE"))
        .andExpect(status().isBadRequest());
    http.perform(
            post("/warehouse/inventory/expiry/allocation")
                .header("Authorization", "Bearer tenant-a")
                .contentType("application/json")
                .content(
                    "{\"skuId\":\"" + skuA + "\",\"warehouseId\":\"" + whSh + "\",\"quantity\":0}"))
        .andExpect(status().isBadRequest());
    http.perform(
            post("/warehouse/inventory/expiry/allocation")
                .header("Authorization", "Bearer tenant-a")
                .contentType("application/json")
                .content("{\"quantity\":10}"))
        .andExpect(status().isBadRequest());
    // 越权读：没有任何 warehouse.inventory.* 权限
    http.perform(get("/warehouse/inventory/expiry/alerts").header("Authorization", "Bearer nope"))
        .andExpect(status().isUnauthorized());
    // 刻意没有 DELETE 端点：停用走 enabled=false
    http.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(
                    "/warehouse/inventory/expiry/policy/" + skuA)
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isMethodNotAllowed());
  }
}
