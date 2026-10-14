package com.starcon.warehouse;

import static com.starcon.warehouse.DistributionModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
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
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 7.1.3.1 多仓分布视图：多仓矩阵、自有账本与平台观测的分离、在途归属、周转天数口径、 DB 级「平台仓不得有自有账本」强制，以及租户隔离三断言。
 *
 * <p>真实 PostgreSQL 16 + 强制 RLS，不使用任何内存替身。
 *
 * <p>连接池固定为 1 条连接：只有复用同一条连接，才能真正证明归还后不会串租户。
 *
 * <p>夹具写法与 {@link InventoryBucketIntegrationTest} / {@link InventoryLedgerIntegrationTest} 完全一致（同样的
 * Testcontainers、同样的 fake identity server、同样的按租户派生 UUID、 同样的 TRUNCATE 清场），避免每个测试类各写一套风格。
 */
@SpringBootTest
@AutoConfigureMockMvc
class InventoryDistributionIntegrationTest {
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

  /** SPU 逻辑 ID。warehouse 不建 SPU 档案，只在 sku_ref 上登记引用（V55）。 */
  static final UUID spuA = UUID.randomUUID();

  // 参照数据 ID 按租户派生：主键是**全局** uuid，两个租户不能用同一批固定 UUID。
  static final UUID leA = idFor(a, 1);
  static final UUID skuA = idFor(a, 2);
  static final UUID skuASameSpu = idFor(a, 3); // 同一个 SPU 下的第二个 SKU
  static final UUID skuANoSpu = idFor(a, 4); // 没有登记 spu_id 的存量 SKU
  static final UUID whSh = idFor(a, 5);
  static final UUID whSz = idFor(a, 6);
  static final UUID whFba = idFor(a, 7);
  static final UUID whTpl = idFor(a, 8);
  static final UUID qSellable = idFor(a, 9);
  static final UUID locSh = idFor(a, 10);
  static final UUID locSz = idFor(a, 11);
  static final UUID lotA = idFor(a, 12);
  static final UUID locFba = idFor(a, 13);

  // 租户 B：有自己的参照数据与库存，用于互不可见断言。
  static final UUID leB = idFor(b, 1);
  static final UUID skuB = idFor(b, 2);
  static final UUID whB = idFor(b, 3);
  static final UUID qSellB = idFor(b, 4);

  static UUID idFor(UUID tenant, int ordinal) {
    return UUID.nameUUIDFromBytes(("starcon-warehouse-dist-" + tenant + "-" + ordinal).getBytes());
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
      // 只有只读权限：矩阵是纯读视图，read 权限就够；观测录入必须被拒。
      actors.put(
          "Bearer reader",
          actor(
              user,
              a,
              null,
              List.of(grant("ALL", List.of("warehouse.read", "warehouse.inventory.read")))));
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
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  @Autowired InventoryDistributionService service;
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
    // TRUNCATE 不触发行级触发器，以超级用户连接执行可绕过 FORCE RLS —— 只用于夹具重建。
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate platform_stock_observation, stock_bucket, inventory_entry, inventory_transaction,"
              + " inventory_transfer, inventory_balance, inventory_policy, inventory_quality_status,"
              + " inventory_lot, warehouse_location, warehouse, sku_ref, legal_entity_ref"
              + " restart identity cascade");
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
          // spu_id 是 V55 新增的引用登记列：前两个 SKU 属于同一 SPU，第三个故意不登记。
          sql.execute(
              "insert into sku_ref(id,tenant_id,code,name,spu_id) values(?,?,?,?,?),(?,?,?,?,?),(?,?,?,?,?)",
              skuA,
              a,
              "SKU-1",
              "商品一",
              spuA,
              skuASameSpu,
              a,
              "SKU-2",
              "商品二",
              spuA,
              skuANoSpu,
              a,
              "SKU-9",
              "未登记SPU",
              null);
          sql.execute(
              "insert into warehouse(id,tenant_id,code,name,country_code,timezone,kind)"
                  + " values(?,?,?,?,?,?,?),(?,?,?,?,?,?,?),(?,?,?,?,?,?,?),(?,?,?,?,?,?,?)",
              whSh,
              a,
              "WH-SH",
              "上海仓",
              "CN",
              "Asia/Shanghai",
              "LOCAL",
              whSz,
              a,
              "WH-SZ",
              "深圳仓",
              "CN",
              "Asia/Shanghai",
              "LOCAL",
              whFba,
              a,
              "FBA-US",
              "美国FBA",
              "US",
              "America/Los_Angeles",
              "PLATFORM_FBA",
              whTpl,
              a,
              "TPL-DE",
              "德国三方仓",
              "DE",
              "Europe/Berlin",
              "THIRD_PARTY");
          sql.execute(
              "insert into inventory_quality_status(id,tenant_id,code,name,role) values(?,?,?,?,?)",
              qSellable,
              a,
              "SELLABLE",
              "可售",
              "SELLABLE");
          sql.execute(
              "insert into warehouse_location(id,tenant_id,warehouse_id,code,name,type,pickable)"
                  + " values(?,?,?,?,?,?,?),(?,?,?,?,?,?,?),(?,?,?,?,?,?,?)",
              locSh,
              a,
              whSh,
              "A-01",
              "A区01",
              "STORAGE",
              true,
              locSz,
              a,
              whSz,
              "B-01",
              "B区01",
              "STORAGE",
              true,
              // FBA 仓也建一个库位：用来证明 bucket 侧的「平台仓不得有账本」触发器真的生效
              locFba,
              a,
              whFba,
              "FBA-A1",
              "FBA货位",
              "STORAGE",
              true);
          sql.execute(
              "insert into inventory_lot(id,tenant_id,sku_id,lot_code,expires_on,received_on)"
                  + " values(?,?,?,?,current_date+365,current_date-10)",
              lotA,
              a,
              skuA,
              "LOT-2026-01");
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
              "LE-B",
              "主体B");
          sql.execute(
              "insert into sku_ref(id,tenant_id,code,name) values(?,?,?,?)",
              skuB,
              b,
              "SKU-B",
              "商品B");
          sql.execute(
              "insert into warehouse(id,tenant_id,code,name,country_code,timezone,kind)"
                  + " values(?,?,?,?,?,?,?)",
              whB,
              b,
              "WH-B",
              "B仓",
              "CN",
              "Asia/Shanghai",
              "LOCAL");
          sql.execute(
              "insert into inventory_quality_status(id,tenant_id,code,name,role) values(?,?,?,?,?)",
              qSellB,
              b,
              "SELLABLE",
              "可售",
              "SELLABLE");
          return null;
        });
  }

  AccessService.Access actorFor(UUID tenant) {
    return tenant.equals(a) ? actorA : actorB;
  }

  // ================================================================ 夹具写入

  /** 租户 A 的一条余额行。 */
  UUID balance(UUID tenant, UUID warehouse, UUID sku, String onHand, String reserved) {
    var id = UUID.randomUUID();
    var actor = actorFor(tenant);
    access.run(
        actor,
        "warehouse.inventory.read",
        () -> {
          sql.execute(
              "insert into inventory_balance(id,tenant_id,sku_id,warehouse_id,legal_entity_id,"
                  + "quality_status_id,on_hand,reserved) values(?,?,?,?,?,?,?,?)",
              id,
              tenant,
              sku,
              warehouse,
              tenant.equals(a) ? leA : leB,
              tenant.equals(a) ? qSellable : qSellB,
              new BigDecimal(onHand),
              new BigDecimal(reserved));
          return null;
        });
    return id;
  }

  /** 给一条余额行挂一个 bucket（用于验证分解度统计与"尚未拆库位"的对比）。 */
  void addBucket(UUID tenant, UUID balanceId, UUID warehouse, UUID location, String onHand) {
    addBucket(tenant, balanceId, warehouse, location, onHand, "0");
  }

  /**
   * 给一条余额行挂一个 bucket。reserved 必须与余额一致：V2 的守恒触发器要求 Σ bucket 的在手/预占都等于 balance，只对齐在手会被 DB 以「不平」拒绝。
   */
  void addBucket(
      UUID tenant, UUID balanceId, UUID warehouse, UUID location, String onHand, String reserved) {
    access.run(
        actorFor(tenant),
        "warehouse.inventory.read",
        () -> {
          sql.execute(
              "insert into stock_bucket(id,tenant_id,legal_entity_id,sku_id,warehouse_id,"
                  + "location_id,lot_id,quality_status_id,balance_id,on_hand,reserved,"
                  + "last_movement_at) select ?,bal.tenant_id,bal.legal_entity_id,bal.sku_id,"
                  + "bal.warehouse_id,?,?,bal.quality_status_id,bal.id,?,?::numeric(19,4),"
                  + "?::timestamptz from inventory_balance bal where bal.id=?",
              UUID.randomUUID(),
              location,
              tenant.equals(a) ? lotA : null,
              new BigDecimal(onHand),
              new BigDecimal(reserved),
              OffsetDateTime.now(),
              balanceId);
          return null;
        });
  }

  /**
   * 写一笔库存业务（业务单 + 分录）。
   *
   * <p>occurredAt 用 {@code ?::timestamptz} 绑定，且 cast **不在绑定序列末位** （后面还有 2 个绑定）：落在末位会让 jOOQ
   * 整体绑定错位一格。
   */
  UUID txn(
      UUID tenant, String kind, OffsetDateTime occurredAt, UUID balanceId, String onHandDelta) {
    var txId = UUID.randomUUID();
    var refId = UUID.randomUUID();
    access.run(
        actorFor(tenant),
        "warehouse.inventory.read",
        () -> {
          sql.execute(
              "insert into inventory_transaction(id,tenant_id,kind,ref_type,ref_id,occurred_at,"
                  + "actor_id,notes) values(?,?,?,?,?,?::timestamptz,?,?)",
              txId,
              tenant,
              kind,
              "ORDER",
              refId,
              occurredAt,
              user,
              "夹具");
          sql.execute(
              "insert into inventory_entry(id,tenant_id,transaction_id,balance_id,on_hand_delta,"
                  + "reserved_delta) values(?,?,?,?,?,0)",
              UUID.randomUUID(),
              tenant,
              txId,
              balanceId,
              new BigDecimal(onHandDelta));
          return null;
        });
    return txId;
  }

  /** 在途：从 fromWh 发往 toWh，尚未收货。 */
  void transfer(UUID tenant, UUID from, UUID to, UUID sku, String quantity) {
    access.run(
        actorFor(tenant),
        "warehouse.inventory.read",
        () -> {
          sql.execute(
              "insert into inventory_transfer(id,tenant_id,sku_id,legal_entity_id,"
                  + "from_warehouse_id,to_warehouse_id,quality_status_id,quantity,status,"
                  + "shipped_at) values(?,?,?,?,?,?,?,?,'IN_TRANSIT',?::timestamptz)",
              UUID.randomUUID(),
              tenant,
              sku,
              tenant.equals(a) ? leA : leB,
              from,
              to,
              tenant.equals(a) ? qSellable : qSellB,
              new BigDecimal(quantity),
              OffsetDateTime.now().minusDays(2));
          return null;
        });
  }

  /** 直接写一条平台观测（夹具用，绕开服务的入参校验，但走完全相同的 DB 约束）。 */
  void observation(
      UUID tenant,
      UUID warehouse,
      UUID sku,
      String source,
      OffsetDateTime collectedAt,
      String onHand,
      String inbound) {
    access.run(
        actorFor(tenant),
        "warehouse.inventory.read",
        () -> {
          sql.execute(
              "insert into platform_stock_observation(id,tenant_id,warehouse_id,sku_id,"
                  + "legal_entity_id,source,collected_at,observed_on_hand,observed_reserved,"
                  + "observed_available,observed_inbound) values(?,?,?,?,?,?,?::timestamptz,?,?,?,?)",
              UUID.randomUUID(),
              tenant,
              warehouse,
              sku,
              tenant.equals(a) ? leA : leB,
              source,
              collectedAt,
              new BigDecimal(onHand),
              new BigDecimal("0"),
              new BigDecimal(onHand),
              new BigDecimal(inbound));
          return null;
        });
  }

  MultiWarehouseDistribution matrixOf(UUID tenant, UUID skuId) {
    return access.run(
        actorFor(tenant),
        "warehouse.inventory.read",
        () -> service.distribution(new DistributionQuery(skuId, null, null, 30)));
  }

  DistributionRow row(UUID tenant, UUID skuId, String warehouseCode) {
    return matrixOf(tenant, skuId).rows().stream()
        .filter(r -> r.warehouseCode().equals(warehouseCode))
        .findFirst()
        .orElseThrow(() -> new AssertionError("矩阵里没有仓库 " + warehouseCode));
  }

  // ================================================================ 矩阵本体

  @Test
  void 一行一仓_自有量来自账本且各列口径正确() {
    balance(a, whSh, skuA, "100", "30");
    balance(a, whSz, skuA, "40", "0");

    var m = matrixOf(a, skuA);
    assertThat(m.rows())
        .extracting(DistributionRow::warehouseCode)
        .containsExactlyInAnyOrder("WH-SH", "WH-SZ");
    var sh = row(a, skuA, "WH-SH");
    assertThat(sh.onHand()).isEqualByComparingTo("100");
    assertThat(sh.reserved()).isEqualByComparingTo("30");
    assertThat(sh.available()).isEqualByComparingTo("70");
    // 自有仓的平台列必须为空，而不是 0 —— 0 会被读成「平台报的是 0」
    assertThat(sh.platformOnHand()).isNull();
    assertThat(sh.source()).isNull();
    assertThat(sh.collectedAt()).isNull();
    assertThat(sh.warehouseKind()).isEqualTo("LOCAL");
  }

  @Test
  void 零库存仓仍然出现_因为缺货判断是本模块用途() {
    balance(a, whSh, skuA, "10", "0");
    // 深圳仓一条都没有：它既没有余额也没有在途，也没有平台观测 → 不该出现
    var m = matrixOf(a, skuA);
    assertThat(m.rows()).hasSize(1);

    // 全被预占的仓 available=0：这是**缺货**，必须出现且被零库存计数命中
    var bal = balance(a, whSz, skuA, "5", "5");
    addBucket(a, bal, whSz, locSz, "5", "5");
    var m2 = matrixOf(a, skuA);
    assertThat(m2.rows()).hasSize(2);
    assertThat(row(a, skuA, "WH-SZ").available()).isEqualByComparingTo("0");
    assertThat(m2.totals().ownZeroWarehouseCount()).isEqualTo(1);
  }

  @Test
  void 尚未拆到库位的余额仍计入矩阵并标出分解度() {
    var bal = balance(a, whSh, skuA, "60", "0");
    // 故意不建 bucket：7.1.1.2 允许「已拆库位前」的 balance 行
    var sh = row(a, skuA, "WH-SH");
    assertThat(sh.onHand()).isEqualByComparingTo("60");
    assertThat(sh.bucketCount()).isZero();
    assertThat(sh.locationCount()).isZero();

    addBucket(a, bal, whSh, locSh, "60");
    var sh2 = row(a, skuA, "WH-SH");
    assertThat(sh2.bucketCount()).isEqualTo(1);
    assertThat(sh2.locationCount()).isEqualTo(1);
    assertThat(sh2.lotCount()).isEqualTo(1);
    assertThat(sh2.lastMovementAt()).isNotNull();
  }

  // ================================================================ 在途

  @Test
  void 在途按收货仓归属且不与在手相加() {
    balance(a, whSh, skuA, "100", "0");
    balance(a, whSz, skuA, "0", "0");
    // 上海 → 深圳 30 件在途：货权已离开上海、尚未入深圳，所以不在上海的在手里，
    // 也不在深圳的在手里 —— 它是深圳这一行**额外**的一列
    transfer(a, whSh, whSz, skuA, "30");

    var m = matrixOf(a, skuA);
    assertThat(row(a, skuA, "WH-SH").inTransit()).isEqualByComparingTo("0");
    var sz = row(a, skuA, "WH-SZ");
    assertThat(sz.onHand()).isEqualByComparingTo("0");
    assertThat(sz.inTransit()).isEqualByComparingTo("30");
    // 在途不计入可用：未到货不能发货
    assertThat(sz.available()).isEqualByComparingTo("0");
    assertThat(m.totals().ownInTransit()).isEqualByComparingTo("30");
    assertThat(m.totals().ownOnHand()).isEqualByComparingTo("100");
  }

  @Test
  void 已收货的调拨不再算在途() {
    balance(a, whSz, skuA, "0", "0");
    access.run(
        actorA,
        "warehouse.inventory.read",
        () -> {
          sql.execute(
              "insert into inventory_transfer(id,tenant_id,sku_id,legal_entity_id,"
                  + "from_warehouse_id,to_warehouse_id,quality_status_id,quantity,status,"
                  + "shipped_at,received_at) values(?,?,?,?,?,?,?,?,'RECEIVED',?::timestamptz,"
                  + "?::timestamptz)",
              UUID.randomUUID(),
              a,
              skuA,
              leA,
              whSh,
              whSz,
              qSellable,
              new BigDecimal("30"),
              OffsetDateTime.now().minusDays(5),
              OffsetDateTime.now().minusDays(1));
          return null;
        });
    // 已收货 → 既不是在途、也没进在手（没有 TRANSFER_IN 分录），所以这一行根本不出现
    var m = matrixOf(a, skuA);
    assertThat(m.rows()).isEmpty();
    assertThat(m.totals().ownInTransit()).isEqualByComparingTo("0");
  }

  // ================================================================ 平台观测

  @Test
  void 平台仓行来自观测值且自带来源与采集时间() {
    var at = OffsetDateTime.now().minusHours(3).truncatedTo(ChronoUnit.SECONDS);
    observation(a, whFba, skuA, "AMAZON_FBA", at, "80", "25");

    var m = matrixOf(a, skuA);
    var fba = row(a, skuA, "FBA-US");
    assertThat(fba.warehouseKind()).isEqualTo("PLATFORM_FBA");
    assertThat(fba.platformOnHand()).isEqualByComparingTo("80");
    assertThat(fba.platformInbound()).isEqualByComparingTo("25");
    // 平台仓的自有账本列必须为空
    assertThat(fba.onHand()).isNull();
    assertThat(fba.available()).isNull();
    // 蓝图点名要求：标注来源与采集时间
    assertThat(fba.source()).isEqualTo("AMAZON_FBA");
    assertThat(fba.collectedAt()).isNotNull();
    assertThat(fba.observationAgeSeconds()).isPositive();
  }

  @Test
  void 自有账本与平台观测永不相加_合计也分两组() {
    balance(a, whSh, skuA, "100", "0");
    var at = OffsetDateTime.now().minusHours(2);
    observation(a, whFba, skuA, "AMAZON_FBA", at, "80", "25");

    var t = matrixOf(a, skuA).totals();
    assertThat(t.ownOnHand()).isEqualByComparingTo("100");
    assertThat(t.ownWarehouseCount()).isEqualTo(1);
    assertThat(t.platformOnHand()).isEqualByComparingTo("80");
    assertThat(t.platformInbound()).isEqualByComparingTo("25");
    assertThat(t.platformWarehouseCount()).isEqualTo(1);
    // 200 这个数在系统里**不存在**：账本 100 与观测 80 没有被加在一起
    assertThat(t.ownOnHand().add(t.platformOnHand())).isEqualByComparingTo("180");
    assertThat(t.earliestCollectedAt()).isNotNull();
  }

  @Test
  void 同一仓多个观测来源分行不混加() {
    var now = OffsetDateTime.now();
    observation(a, whFba, skuA, "AMAZON_FBA", now.minusHours(1), "80", "0");
    observation(a, whFba, skuA, "THIRD_PARTY_API", now.minusHours(2), "30", "0");

    var fbaRows =
        matrixOf(a, skuA).rows().stream().filter(r -> r.warehouseCode().equals("FBA-US")).toList();
    assertThat(fbaRows).hasSize(2);
    assertThat(fbaRows)
        .extracting(DistributionRow::source)
        .containsExactlyInAnyOrder("AMAZON_FBA", "THIRD_PARTY_API");
    // 两个来源的分歧被暴露成两行，而不是被求和成一个 110
    assertThat(fbaRows)
        .extracting(r -> r.platformOnHand().toPlainString())
        .containsExactlyInAnyOrder("80.0000", "30.0000");
  }

  @Test
  void 平台仓取最新一条观测_历史保留() {
    var now = OffsetDateTime.now();
    observation(a, whFba, skuA, "AMAZON_FBA", now.minusDays(5), "200", "0");
    observation(a, whFba, skuA, "AMAZON_FBA", now.minusDays(1), "150", "0");

    var fba = row(a, skuA, "FBA-US");
    assertThat(fba.platformOnHand()).isEqualByComparingTo("150");
    // 历史仍在表里可查（供 7.2.2.2 平台断货风险回溯）
    var stored =
        access.run(
            actorA,
            "warehouse.inventory.read",
            () ->
                sql.fetch("select observed_on_hand from platform_stock_observation").stream()
                    .map(r -> r.get("observed_on_hand", BigDecimal.class))
                    .toList());
    assertThat(stored).hasSize(2);
  }

  @Test
  void 平台仓不得有自有账本余额被DB拒绝() {
    assertThatThrownBy(() -> balance(a, whFba, skuA, "10", "0")).hasMessageContaining("平台仓");
    // bucket 侧同样锁住：直接插一行属于 FBA 仓的 bucket（不走 balance 推导）同样被拒
    assertThatThrownBy(
            () ->
                access.run(
                    actorA,
                    "warehouse.inventory.read",
                    () -> {
                      sql.execute(
                          "insert into stock_bucket(id,tenant_id,legal_entity_id,sku_id,"
                              + "warehouse_id,location_id,lot_id,quality_status_id,balance_id,"
                              + "on_hand,reserved,last_movement_at)"
                              + " values(?,?,?,?,?,?,?,?,?,1,0,?::timestamptz)",
                          UUID.randomUUID(),
                          a,
                          leA,
                          skuA,
                          whFba,
                          locFba,
                          lotA,
                          qSellable,
                          UUID.randomUUID(),
                          OffsetDateTime.now());
                      return null;
                    }))
        .hasMessageContaining("平台仓");
  }

  @Test
  void 已有账本余额的仓不能改成平台仓() {
    balance(a, whSh, skuA, "10", "0");
    assertThatThrownBy(
            () ->
                access.run(
                    actorA,
                    "warehouse.inventory.read",
                    () -> {
                      sql.execute("update warehouse set kind='PLATFORM_FBA' where id=?", whSh);
                      return null;
                    }))
        .hasMessageContaining("不能改为平台仓");
  }

  @Test
  void 非平台仓写观测被DB拒绝() {
    var at = OffsetDateTime.now();
    assertThatThrownBy(() -> observation(a, whSh, skuA, "AMAZON_FBA", at, "10", "0"))
        .hasMessageContaining("不是平台仓");
  }

  @Test
  void 观测是appendOnly_改删都被拒绝() {
    observation(a, whFba, skuA, "AMAZON_FBA", OffsetDateTime.now(), "80", "0");
    // 双重锁定：warehouse_app 只有 SELECT/INSERT（V55 的 GRANT/REVOKE），所以 UPDATE/DELETE
    // 在权限层就被挡住，根本走不到 append-only 触发器。两道防线都在，任一道单独存在也够。
    // 注意断言的是 **rootCause**：Spring 把 SQLSTATE 42501 翻译成 BadSqlGrammarException
    // 并丢掉驱动原文，只看顶层 message 只会看到「bad SQL grammar」。
    assertThatThrownBy(
            () ->
                access.run(
                    actorA,
                    "warehouse.inventory.read",
                    () -> {
                      sql.execute("update platform_stock_observation set observed_on_hand=1");
                      return null;
                    }))
        .rootCause()
        .hasMessageContaining("permission denied");
    assertThatThrownBy(
            () ->
                access.run(
                    actorA,
                    "warehouse.inventory.read",
                    () -> {
                      sql.execute("delete from platform_stock_observation");
                      return null;
                    }))
        .rootCause()
        .hasMessageContaining("permission denied");
    // 即使用超级用户连接绕过权限层，append-only 触发器仍然挡得住
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      assertThatThrownBy(
              () -> s.execute("update platform_stock_observation set observed_on_hand=1"))
          .hasMessageContaining("append-only");
      assertThatThrownBy(() -> s.execute("delete from platform_stock_observation"))
          .hasMessageContaining("append-only");
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }

  // ================================================================ 观测录入端点

  @Test
  void 观测录入真实落库且回显带时区的采集时间() {
    var collectedAt = OffsetDateTime.parse("2026-10-01T00:00:00+08:00");
    var saved =
        access.run(
            actorA,
            "warehouse.inventory.update",
            () ->
                service.recordObservation(
                    new PlatformObservationInput(
                        whFba,
                        skuA,
                        leA,
                        "amazon_fba",
                        collectedAt,
                        new BigDecimal("80"),
                        new BigDecimal("5"),
                        new BigDecimal("75"),
                        new BigDecimal("25"),
                        "FBA-REF-1")));

    assertThat(saved.source()).isEqualTo("AMAZON_FBA");
    assertThat(saved.warehouseKind()).isEqualTo("PLATFORM_FBA");
    // 写回的值必须与传入的带偏移时刻一致：+08:00 存进 timestamptz 再读出来不能变成 UTC 字面量
    assertThat(saved.collectedAt()).isEqualTo(collectedAt);
    var fba = row(a, skuA, "FBA-US");
    assertThat(fba.collectedAt()).isEqualTo(collectedAt);
    assertThat(fba.platformOnHand()).isEqualByComparingTo("80");
    assertThat(fba.platformAvailable()).isEqualByComparingTo("75");
  }

  @Test
  void 观测缺来源或缺采集时间被拒() {
    var now = OffsetDateTime.now();
    assertThatThrownBy(
            () ->
                access.run(
                    actorA,
                    "warehouse.inventory.update",
                    () ->
                        service.recordObservation(
                            new PlatformObservationInput(
                                whFba,
                                skuA,
                                leA,
                                "  ",
                                now,
                                BigDecimal.ONE,
                                BigDecimal.ZERO,
                                BigDecimal.ONE,
                                BigDecimal.ZERO,
                                null))))
        .hasMessageContaining("source");
    assertThatThrownBy(
            () ->
                access.run(
                    actorA,
                    "warehouse.inventory.update",
                    () ->
                        service.recordObservation(
                            new PlatformObservationInput(
                                whFba,
                                skuA,
                                leA,
                                "AMAZON_FBA",
                                null,
                                BigDecimal.ONE,
                                BigDecimal.ZERO,
                                BigDecimal.ONE,
                                BigDecimal.ZERO,
                                null))))
        .hasMessageContaining("collectedAt");
    // 负数量在服务层就给出可读报错，而不是只抛一句 constraint 名字
    assertThatThrownBy(
            () ->
                access.run(
                    actorA,
                    "warehouse.inventory.update",
                    () ->
                        service.recordObservation(
                            new PlatformObservationInput(
                                whFba,
                                skuA,
                                leA,
                                "AMAZON_FBA",
                                now,
                                new BigDecimal("-1"),
                                BigDecimal.ZERO,
                                BigDecimal.ONE,
                                BigDecimal.ZERO,
                                null))))
        .hasMessageContaining("不能为负");
  }

  @Test
  void 观测录入需要写权限_只读身份被拒() throws Exception {
    var body =
        """
        {"warehouseId":"%s","skuId":"%s","legalEntityId":"%s","source":"AMAZON_FBA",
         "collectedAt":"2026-10-01T00:00:00+08:00","observedOnHand":1,
         "observedReserved":0,"observedAvailable":1,"observedInbound":0}
        """
            .formatted(whFba, skuA, leA);
    // 只读身份：矩阵能看，但不能写观测
    http.perform(
            get("/warehouse/inventory/distribution?skuId=" + skuA)
                .header("Authorization", "Bearer reader"))
        .andExpect(status().isOk());
    http.perform(
            post("/warehouse/inventory/platform-observations")
                .header("Authorization", "Bearer reader")
                .contentType("application/json")
                .content(body))
        .andExpect(status().isForbidden());
  }

  // ================================================================ 周转天数

  @Test
  void 自有仓周转天数等于在手除以日均出库() {
    var bal = balance(a, whSh, skuA, "60", "0");
    // 近 30 天发货出库 30 件 → 日均 1 件 → 60 / 1 = 60 天
    txn(a, "SHIPMENT", OffsetDateTime.now().minusDays(5), bal, "-30");

    var sh = row(a, skuA, "WH-SH");
    assertThat(sh.turnoverComputable()).isTrue();
    assertThat(sh.turnoverDays()).isEqualByComparingTo("60.0");
    assertThat(sh.turnoverBasis()).isEqualTo("OWN_OUTBOUND");
  }

  @Test
  void 调拨出库不计入出库速度() {
    var bal = balance(a, whSh, skuA, "60", "0");
    // 只有调拨出库、没有发货：调拨不是消耗，不能让这个仓看起来卖得快
    txn(a, "TRANSFER_OUT", OffsetDateTime.now().minusDays(5), bal, "-30");

    var sh = row(a, skuA, "WH-SH");
    assertThat(sh.turnoverComputable()).isFalse();
    assertThat(sh.turnoverDays()).isNull();
    assertThat(sh.turnoverNote()).contains("没有发货");
  }

  @Test
  void 没有出库记录时周转天数为空而不是零() {
    balance(a, whSh, skuA, "60", "0");
    var sh = row(a, skuA, "WH-SH");
    assertThat(sh.turnoverComputable()).isFalse();
    assertThat(sh.turnoverDays()).isNull();
    assertThat(sh.turnoverBasis()).isEqualTo("NONE");
    // 说明必须讲清为什么空，否则用户会以为没算
    assertThat(sh.turnoverNote()).contains("无法计算");
  }

  @Test
  void 回看窗口之外的出库不计入() {
    var bal = balance(a, whSh, skuA, "60", "0");
    // 45 天前的发货，落在默认 30 天窗口之外
    txn(a, "SHIPMENT", OffsetDateTime.now().minusDays(45), bal, "-30");

    assertThat(row(a, skuA, "WH-SH").turnoverComputable()).isFalse();
    // 窗口放宽到 90 天后就能算出来
    var wider =
        access.run(
            actorA,
            "warehouse.inventory.read",
            () -> service.distribution(new DistributionQuery(skuA, null, null, 90)));
    var sh =
        wider.rows().stream()
            .filter(r -> r.warehouseCode().equals("WH-SH"))
            .findFirst()
            .orElseThrow();
    assertThat(sh.turnoverComputable()).isTrue();
    assertThat(sh.turnoverDays()).isEqualByComparingTo("180.0");
    assertThat(wider.window().lookbackDays()).isEqualTo(90);
    assertThat(wider.window().outboundKinds()).containsExactly("SHIPMENT");
  }

  @Test
  void 平台仓周转天数走观测差分且口径单独标注() {
    var now = OffsetDateTime.now();
    // 两条观测相隔 8 天，在手从 200 掉到 100：日均减 100/8=12.5，最新在手 100 → 8 天
    observation(a, whFba, skuA, "AMAZON_FBA", now.minusDays(10), "200", "0");
    observation(a, whFba, skuA, "AMAZON_FBA", now.minusDays(2), "100", "0");

    var fba = row(a, skuA, "FBA-US");
    assertThat(fba.turnoverComputable()).isTrue();
    assertThat(fba.turnoverBasis()).isEqualTo("PLATFORM_OBSERVATION_DIFF");
    assertThat(fba.turnoverDays()).isEqualByComparingTo("8");
    // 口径与自有仓不同，必须在说明里讲清不可直接比较
    assertThat(fba.turnoverNote()).contains("不可直接比较");
  }

  @Test
  void 平台仓观测样本不足时周转天数留空() {
    observation(a, whFba, skuA, "AMAZON_FBA", OffsetDateTime.now().minusDays(1), "80", "0");
    var fba = row(a, skuA, "FBA-US");
    assertThat(fba.turnoverComputable()).isFalse();
    assertThat(fba.turnoverDays()).isNull();
    assertThat(fba.turnoverNote()).contains("少于 2 条");
  }

  @Test
  void 平台仓观测无净减少时周转天数留空() {
    var now = OffsetDateTime.now();
    observation(a, whFba, skuA, "AMAZON_FBA", now.minusDays(10), "80", "0");
    observation(a, whFba, skuA, "AMAZON_FBA", now.minusDays(2), "120", "0");
    var fba = row(a, skuA, "FBA-US");
    assertThat(fba.turnoverComputable()).isFalse();
    assertThat(fba.turnoverNote()).contains("没有净减少");
  }

  @Test
  void 回看天数越界被拒() {
    assertThatThrownBy(
            () ->
                access.run(
                    actorA,
                    "warehouse.inventory.read",
                    () -> service.distribution(new DistributionQuery(skuA, null, null, 0))))
        .hasMessageContaining("lookbackDays");
    assertThatThrownBy(
            () ->
                access.run(
                    actorA,
                    "warehouse.inventory.read",
                    () -> service.distribution(new DistributionQuery(skuA, null, null, 365))))
        .hasMessageContaining("lookbackDays");
  }

  // ================================================================ SKU / SPU 输入

  @Test
  void 按SKU过滤只看这一个SKU() {
    balance(a, whSh, skuA, "100", "0");
    balance(a, whSh, skuASameSpu, "7", "0");

    var m = matrixOf(a, skuA);
    assertThat(m.rows()).hasSize(1);
    assertThat(m.rows().get(0).onHand()).isEqualByComparingTo("100");
    assertThat(m.subject().skuCode()).isEqualTo("SKU-1");
    assertThat(m.subject().spuId()).isEqualTo(spuA);
    assertThat(m.skuScope().matchedSkuCount()).isEqualTo(1);
  }

  @Test
  void 按SPU过滤聚合同SPU下所有SKU() {
    balance(a, whSh, skuA, "100", "0");
    balance(a, whSh, skuASameSpu, "20", "0");
    observation(a, whFba, skuA, "AMAZON_FBA", OffsetDateTime.now().minusHours(1), "80", "0");
    observation(a, whFba, skuASameSpu, "AMAZON_FBA", OffsetDateTime.now().minusHours(1), "20", "0");

    var m =
        access.run(
            actorA,
            "warehouse.inventory.read",
            () -> service.distribution(new DistributionQuery(null, spuA, null, 30)));
    assertThat(m.subject().spuId()).isEqualTo(spuA);
    assertThat(m.skuScope().matchedSkuCount()).isEqualTo(2);
    var sh =
        m.rows().stream().filter(r -> r.warehouseCode().equals("WH-SH")).findFirst().orElseThrow();
    assertThat(sh.onHand()).isEqualByComparingTo("120");
    var fba =
        m.rows().stream().filter(r -> r.warehouseCode().equals("FBA-US")).findFirst().orElseThrow();
    // 同来源的两个 SKU 相加是同质的；采集时间取最旧那条
    assertThat(fba.platformOnHand()).isEqualByComparingTo("100");
  }

  @Test
  void 未登记SPU的SKU被如实报出覆盖缺口() {
    balance(a, whSh, skuANoSpu, "50", "0");
    balance(a, whSh, skuA, "10", "0");

    var m = matrixOf(a, skuA);
    // 按 SKU 查时 matchedSkuCount 就是 1；但缺口数必须报出来，不能假装 SPU 维度是完整的
    assertThat(m.skuScope().matchedSkuCount()).isEqualTo(1);
    assertThat(m.skuScope().unregisteredSpuCount()).isEqualTo(1);
    // 未登记 SPU 的 SKU 不会被 SPU 口径捞到 —— 这正是缺口数的含义
    var bySpu =
        access.run(
            actorA,
            "warehouse.inventory.read",
            () -> service.distribution(new DistributionQuery(null, spuA, null, 30)));
    // 该 SPU 下登记了 2 个 SKU，但只有 skuA 有余额
    assertThat(bySpu.skuScope().matchedSkuCount()).isEqualTo(2);
    assertThat(bySpu.rows().get(0).onHand()).isEqualByComparingTo("10");
  }

  @Test
  void SKU与SPU互斥且必居其一() {
    assertThatThrownBy(
            () ->
                access.run(
                    actorA,
                    "warehouse.inventory.read",
                    () -> service.distribution(new DistributionQuery(null, null, null, null))))
        .hasMessageContaining("skuId 或 spuId");
    assertThatThrownBy(
            () ->
                access.run(
                    actorA,
                    "warehouse.inventory.read",
                    () -> service.distribution(new DistributionQuery(skuA, spuA, null, null))))
        .hasMessageContaining("只能给一个");
  }

  @Test
  void 端点参数校验返回400() throws Exception {
    http.perform(
            get("/warehouse/inventory/distribution").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isBadRequest());
    http.perform(
            get("/warehouse/inventory/distribution?skuId=" + skuA + "&spuId=" + spuA)
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isBadRequest());
    // 未认证
    http.perform(get("/warehouse/inventory/distribution?skuId=" + skuA))
        .andExpect(status().isUnauthorized());
  }

  // ================================================================ 矩阵形态

  @Test
  void 排序把自有仓排在平台仓之前() {
    balance(a, whSh, skuA, "10", "0");
    balance(a, whSz, skuA, "5", "0");
    observation(a, whFba, skuA, "AMAZON_FBA", OffsetDateTime.now(), "9999", "0");

    var rows = matrixOf(a, skuA).rows();
    assertThat(rows).hasSize(3);
    // 平台观测再大也不能排到自有账本前面：两种量不可比
    assertThat(rows.get(0).warehouseKind()).isEqualTo("LOCAL");
    assertThat(rows.get(1).warehouseKind()).isEqualTo("LOCAL");
    assertThat(rows.get(2).warehouseKind()).isEqualTo("PLATFORM_FBA");
    // 自有组内按可用倒序
    assertThat(rows.get(0).available()).isEqualByComparingTo("10");
    assertThat(rows.get(1).available()).isEqualByComparingTo("5");
  }

  @Test
  void 三方仓与海外仓都作为自有账本行出现() {
    balance(a, whTpl, skuA, "12", "2");
    var tpl = row(a, skuA, "TPL-DE");
    assertThat(tpl.warehouseKind()).isEqualTo("THIRD_PARTY");
    // 三方仓是自有账本（我们请他们代管），所以走账本列而不是观测列
    assertThat(tpl.onHand()).isEqualByComparingTo("12");
    assertThat(tpl.platformOnHand()).isNull();
  }

  @Test
  void 按法人过滤只影响该法人的货() {
    balance(a, whSh, skuA, "100", "0");
    // 另一个法人（复用 leA 之外的一个 id）在同一仓同 SKU 有货
    var leOther = UUID.randomUUID();
    access.run(
        actorA,
        "warehouse.inventory.read",
        () -> {
          sql.execute(
              "insert into legal_entity_ref(id,tenant_id,code,name) values(?,?,?,?)",
              leOther,
              a,
              "LE-2",
              "主体二");
          sql.execute(
              "insert into inventory_balance(id,tenant_id,sku_id,warehouse_id,legal_entity_id,"
                  + "quality_status_id,on_hand,reserved) values(?,?,?,?,?,?,?,0)",
              UUID.randomUUID(),
              a,
              skuA,
              whSh,
              leOther,
              qSellable,
              new BigDecimal("500"));
          return null;
        });

    var all = matrixOf(a, skuA);
    assertThat(all.rows().get(0).onHand()).isEqualByComparingTo("600");

    var scoped =
        access.run(
            actorA,
            "warehouse.inventory.read",
            () -> service.distribution(new DistributionQuery(skuA, null, leA, 30)));
    assertThat(scoped.rows().get(0).onHand()).isEqualByComparingTo("100");
    assertThat(scoped.totals().ownOnHand()).isEqualByComparingTo("100");
  }

  @Test
  void 行内入口如实标注下游可用性() {
    var m = matrixOf(a, skuA);
    var byCode = new java.util.HashMap<String, DistributionAction>();
    m.actions().forEach(x -> byCode.put(x.action(), x));
    // 调拨 7.5.1.1 尚未交付 → 不可用，但路由契约仍给出
    assertThat(byCode.get("TRANSFER").available()).isFalse();
    assertThat(byCode.get("TRANSFER").ownerModule()).isEqualTo("7.5.1.1");
    assertThat(byCode.get("TRANSFER").href()).contains("/inventory/transfer");
    // 明细与流水已交付 → 可用。把已交付的标成未交付就是在页面上说假状态
    assertThat(byCode.get("BUCKET").available()).isTrue();
    assertThat(byCode.get("LEDGER").available()).isTrue();
    // 入口带上定位维度，不是"从哪开始"的空路由
    assertThat(byCode.get("BUCKET").href()).contains("skuId={skuId}");
  }

  @Test
  void 矩阵经HTTP端点可读且形状完整() throws Exception {
    balance(a, whSh, skuA, "100", "30");
    observation(a, whFba, skuA, "AMAZON_FBA", OffsetDateTime.now().minusHours(1), "80", "25");

    http.perform(
            get("/warehouse/inventory/distribution?skuId=" + skuA + "&lookbackDays=30")
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rows.length()").value(2))
        .andExpect(jsonPath("$.subject.skuCode").value("SKU-1"))
        .andExpect(jsonPath("$.totals.ownOnHand").value(100))
        .andExpect(jsonPath("$.totals.platformOnHand").value(80))
        .andExpect(jsonPath("$.window.lookbackDays").value(30))
        .andExpect(jsonPath("$.window.outboundKinds[0]").value("SHIPMENT"))
        .andExpect(jsonPath("$.skuScope.matchedSkuCount").value(1))
        // 平台行带来源与采集时间，自有行的平台列为 null
        .andExpect(
            jsonPath("$.rows[?(@.warehouseKind=='PLATFORM_FBA')].source")
                .value(org.hamcrest.Matchers.hasItem("AMAZON_FBA")))
        .andExpect(
            jsonPath("$.rows[?(@.warehouseKind=='LOCAL')].platformOnHand")
                .value(org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.nullValue())));
  }

  // ================================================================ 租户隔离三断言

  @Test
  void 断言一_两租户互不可见() {
    balance(a, whSh, skuA, "100", "0");
    observation(a, whFba, skuA, "AMAZON_FBA", OffsetDateTime.now(), "80", "0");
    balance(b, whB, skuB, "7", "0");

    var ma = matrixOf(a, skuA);
    assertThat(ma.rows()).hasSize(2);
    assertThat(ma.totals().ownOnHand()).isEqualByComparingTo("100");

    // B 用 A 的 skuId 查：RLS 下查不到任何东西，矩阵为空而不是 404
    var mb = matrixOf(b, skuA);
    assertThat(mb.rows()).isEmpty();
    assertThat(mb.totals().ownOnHand()).isEqualByComparingTo("0");
    assertThat(mb.totals().platformOnHand()).isEqualByComparingTo("0");
    assertThat(mb.subject().skuCode()).isNull();

    // B 查自己的 SKU，只看得到自己的一个仓
    var mbOwn = matrixOf(b, skuB);
    assertThat(mbOwn.rows()).hasSize(1);
    assertThat(mbOwn.rows().get(0).warehouseCode()).isEqualTo("WH-B");
    assertThat(mbOwn.totals().ownOnHand()).isEqualByComparingTo("7");
  }

  @Test
  void 断言二_伪造租户头无效() throws Exception {
    balance(a, whSh, skuA, "100", "0");
    observation(a, whFba, skuA, "AMAZON_FBA", OffsetDateTime.now(), "80", "0");

    // 身份是 B、伪造头指向 A → 只能看到 B 的空视图
    http.perform(
            get("/warehouse/inventory/distribution?skuId=" + skuA)
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rows.length()").value(0))
        .andExpect(jsonPath("$.totals.ownOnHand").value(0))
        .andExpect(jsonPath("$.totals.platformOnHand").value(0));
    // 反向：身份是 A、伪造头指向 B 也拿不到 A 之外的东西
    http.perform(
            get("/warehouse/inventory/distribution?skuId=" + skuA)
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rows.length()").value(2));
    // 观测写入同样不受伪造头影响
    var body =
        """
        {"warehouseId":"%s","skuId":"%s","legalEntityId":"%s","source":"AMAZON_FBA",
         "collectedAt":"2026-10-01T00:00:00+08:00","observedOnHand":1,
         "observedReserved":0,"observedAvailable":1,"observedInbound":0}
        """
            .formatted(whFba, skuA, leA);
    http.perform(
            post("/warehouse/inventory/platform-observations")
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b.toString())
                .contentType("application/json")
                .content(body))
        .andExpect(status().isOk());
    // 写进的是 A 的租户（whFba 属于 A），B 的表里仍然一条都没有
    assertThat(matrixOf(b, skuA).rows()).isEmpty();
  }

  @Test
  void 断言三_连接池归还后不串租户() {
    balance(a, whSh, skuA, "100", "0");
    observation(a, whFba, skuA, "AMAZON_FBA", OffsetDateTime.now(), "80", "0");
    balance(b, whB, skuB, "7", "0");

    // 池大小为 1，同一条连接被反复借还：交替访问两个租户 20 轮。
    for (int i = 0; i < 20; i++) {
      var ma = matrixOf(a, skuA);
      var mb = matrixOf(b, skuA);
      var mbOwn = matrixOf(b, skuB);
      assertThat(ma.rows()).hasSize(2);
      assertThat(mb.rows()).isEmpty();
      assertThat(ma.totals().ownOnHand()).isEqualByComparingTo("100");
      assertThat(ma.totals().platformOnHand()).isEqualByComparingTo("80");
      assertThat(mb.totals().ownOnHand()).isEqualByComparingTo("0");
      assertThat(mbOwn.totals().ownOnHand()).isEqualByComparingTo("7");
    }
  }
}
