package com.starcon.warehouse;

import static com.starcon.warehouse.InventoryBucketModels.*;
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
 * 7.1.1.2 库存明细：bucket 六维下钻、与 balance 的守恒核对、逐级筛选真实生效、
 * 版本与业务时间口径、行内操作入口契约，以及租户隔离三断言。
 *
 * <p>真实 PostgreSQL 16 + 强制 RLS，不使用任何内存替身。
 *
 * <p>连接池固定为 1 条连接：只有复用同一条连接，才能真正证明归还后不会串租户。
 *
 * <p>夹具写法与 {@link InventoryOverviewIntegrationTest} 完全一致（同样的 Testcontainers、
 * 同样的 fake identity server、同样的按租户派生 UUID、同样的 TRUNCATE 清场），
 * 避免两个测试类各写一套风格。
 */
@SpringBootTest
@AutoConfigureMockMvc
class InventoryBucketIntegrationTest {
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

  // 参照数据 ID 按租户派生：legal_entity_ref / sku_ref / warehouse 的主键是**全局** uuid，
  // 两个租户不能用同一批固定 UUID（与 7.1.1.1 的测试同一条理由）。
  static final UUID leA = idFor(a, 1);
  static final UUID skuA = idFor(a, 2);
  static final UUID whSh = idFor(a, 3);
  static final UUID whSz = idFor(a, 4);
  static final UUID qSellable = idFor(a, 5);
  static final UUID qPending = idFor(a, 6);
  static final UUID qDefective = idFor(a, 7);
  static final UUID locShA = idFor(a, 8);
  static final UUID locShB = idFor(a, 9);
  static final UUID locShQuarantine = idFor(a, 10);
  static final UUID locSzA = idFor(a, 11);
  static final UUID lotA = idFor(a, 12);

  static UUID idFor(UUID tenant, int ordinal) {
    return UUID.nameUUIDFromBytes(("starcon-warehouse-test-" + tenant + "-" + ordinal).getBytes());
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
      // 只有单页读权限、没有服务级读权限：按 AccessService 的口径必须 403。
      actors.put(
          "Bearer page-reader",
          actor(
              user,
              a,
              null,
              List.of(grant("ALL", List.of("warehouse.inventory.read")))));
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
    // 单连接：只有复用同一条连接才能证明连接池归还后不串租户。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  @Autowired InventoryBucketService service;
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
    // TRUNCATE 不触发行级触发器（bucket 的 no-delete 触发器只挡 DELETE），以超级用户
    // 连接执行可绕过 FORCE RLS —— 只用于测试夹具重建，不给应用角色开这个口子。
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate stock_bucket, inventory_entry, inventory_transaction, inventory_transfer,"
              + " inventory_balance, inventory_policy, inventory_quality_status, inventory_lot,"
              + " warehouse_location, warehouse, sku_ref, legal_entity_ref"
              + " restart identity cascade");
    }
    for (var tenant : List.of(a, b)) {
      access.run(actorFor(tenant), "warehouse.inventory.read", () -> {
        sql.execute(
            "insert into legal_entity_ref(id,tenant_id,code,name) values(?,?,?,?)",
            idFor(tenant, 1), tenant, "LE-1", "主体一");
        sql.execute(
            "insert into sku_ref(id,tenant_id,code,name) values(?,?,?,?)",
            idFor(tenant, 2), tenant, "SKU-1", "商品一");
        sql.execute(
            "insert into warehouse(id,tenant_id,code,name,country_code,timezone) values(?,?,?,?,?,?)",
            idFor(tenant, 3), tenant, "WH-SH", "上海仓", "CN", "Asia/Shanghai");
        sql.execute(
            "insert into warehouse(id,tenant_id,code,name,country_code,timezone) values(?,?,?,?,?,?)",
            idFor(tenant, 4), tenant, "WH-SZ", "深圳仓", "CN", "Asia/Shanghai");
        sql.execute(
            "insert into inventory_quality_status(id,tenant_id,code,name,role) values"
                + "(?,?,?,?,?),(?,?,?,?,?),(?,?,?,?,?)",
            idFor(tenant, 5), tenant, "SELLABLE", "可售", "SELLABLE",
            idFor(tenant, 6), tenant, "PENDING", "待检", "PENDING_INSPECTION",
            idFor(tenant, 7), tenant, "DEFECT", "残次", "DEFECTIVE");
        // 库位：上海仓三个（两个可拣 + 一个待检不可拣），深圳仓一个
        sql.execute(
            "insert into warehouse_location(id,tenant_id,warehouse_id,code,name,type,pickable)"
                + " values(?,?,?,?,?,?,?),"
                + "(?,?,?,?,?,?,?),(?,?,?,?,?,?,?),(?,?,?,?,?,?,?)",
            idFor(tenant, 8), tenant, idFor(tenant, 3), "A-01-01", "A区01排01", "STORAGE", true,
            idFor(tenant, 9), tenant, idFor(tenant, 3), "A-01-02", "A区01排02", "STORAGE", true,
            idFor(tenant, 10), tenant, idFor(tenant, 3), "Q-01", "待检区", "QUARANTINE", false,
            idFor(tenant, 11), tenant, idFor(tenant, 4), "B-02-01", "B区02排01", "STORAGE", true);
        sql.execute(
            "insert into inventory_lot(id,tenant_id,sku_id,lot_code,expires_on,received_on)"
                + " values(?,?,?,?,current_date+365,current_date-10)",
            idFor(tenant, 12), tenant, idFor(tenant, 2), "LOT-2026-01");
        return null;
      });
    }
  }

  AccessService.Access actorFor(UUID tenant) {
    return tenant.equals(a) ? actorA : actorB;
  }

  // ================================================================ 夹具写入

  /** 一条 bucket 规格：库位、批次（null = 无批次）、在手数量。 */
  record B(UUID location, UUID lot, String onHand) {}

  /** 写一行 balance（租户 A），返回它的 id。 */
  UUID balance(UUID warehouse, UUID quality, String onHand, String reserved) {
    var id = UUID.randomUUID();
    access.run(actorA, "warehouse.inventory.read", () -> {
      sql.execute(
          "insert into inventory_balance(id,tenant_id,sku_id,warehouse_id,legal_entity_id,"
              + "quality_status_id,on_hand,reserved) values(?,?,?,?,?,?,?,?)",
          id, a, skuA, warehouse, leA, quality, new BigDecimal(onHand), new BigDecimal(reserved));
      return null;
    });
    return id;
  }

  /**
   * 把一条 balance 按库位/批次拆开：同一事务内改 balance 与全部 bucket。
   *
   * <p>必须同一事务 —— V2 的守恒是**延迟**约束触发器，写入中途 balance 还没改完是允许的，
   * 提交前才对平。分两次写会第二次提交时报不平，这正是设计意图（见
   * {@code bucket不平被DB拒绝} 与 {@code 只改balance不改bucket被DB拒绝}）。
   *
   * <p>balance 的在手被置为各 bucket 之和，所以调用方先建 on_hand=0 的 balance 行。
   */
  void split(UUID balanceId, B... buckets) {
    access.run(actorA, "warehouse.inventory.read", () -> {
      var total = BigDecimal.ZERO;
      for (var b : buckets) total = total.add(new BigDecimal(b.onHand()));
      sql.execute("update inventory_balance set on_hand=? where id=?", total, balanceId);
      int i = 0;
      for (var b : buckets) {
        sql.execute(
            "insert into stock_bucket(id,tenant_id,legal_entity_id,sku_id,warehouse_id,"
                + "location_id,lot_id,quality_status_id,balance_id,on_hand,reserved,"
                + "last_movement_at) select ?,bal.tenant_id,bal.legal_entity_id,bal.sku_id,"
                + "bal.warehouse_id,?,?,bal.quality_status_id,bal.id,?,0,?::timestamptz"
                + " from inventory_balance bal where bal.id=?",
            UUID.randomUUID(),
            b.location(),
            b.lot(),
            new BigDecimal(b.onHand()),
            OffsetDateTime.now().plusSeconds(i++),
            balanceId);
      }
      return null;
    });
  }

  /** 只写 bucket、不动 balance —— 用来验证守恒触发器会拒绝不平的写入。 */
  void bucketOnly(UUID balanceId, UUID location, UUID lot, String onHand) {
    access.run(actorA, "warehouse.inventory.read", () -> {
      sql.execute(
          "insert into stock_bucket(id,tenant_id,legal_entity_id,sku_id,warehouse_id,"
              + "location_id,lot_id,quality_status_id,balance_id,on_hand,reserved,"
              + "last_movement_at) select ?,bal.tenant_id,bal.legal_entity_id,bal.sku_id,"
              + "bal.warehouse_id,?,?,bal.quality_status_id,bal.id,?,0,?::timestamptz"
              + " from inventory_balance bal where bal.id=?",
          UUID.randomUUID(), location, lot, new BigDecimal(onHand), OffsetDateTime.now(), balanceId);
      return null;
    });
  }

  /** 写一条带预占的 bucket（预占只能落在可售质量状态上）。 */
  void bucketWithReserved(UUID balanceId, UUID location, UUID lot, String onHand, String reserved) {
    access.run(actorA, "warehouse.inventory.read", () -> {
      sql.execute(
          "insert into stock_bucket(id,tenant_id,legal_entity_id,sku_id,warehouse_id,"
              + "location_id,lot_id,quality_status_id,balance_id,on_hand,reserved,"
              + "last_movement_at) select ?,bal.tenant_id,bal.legal_entity_id,bal.sku_id,"
              + "bal.warehouse_id,?,?,bal.quality_status_id,bal.id,?,?,?::timestamptz"
              + " from inventory_balance bal where bal.id=?",
          UUID.randomUUID(),
          location,
          lot,
          new BigDecimal(onHand),
          new BigDecimal(reserved),
          OffsetDateTime.now(),
          balanceId);
      sql.execute(
          "update inventory_balance set reserved=? where id=?", new BigDecimal(reserved), balanceId);
      return null;
    });
  }

  InventoryBucketDetail detailOf(AccessService.Access who) {
    return access.run(who, "warehouse.inventory.read", () -> service.detail(query(null)));
  }

  static BucketDetailQuery query(UUID locationId) {
    return new BucketDetailQuery(
        null, null, locationId, null, null, null, null, null, null, null, null);
  }

  // ================================================================ 六维最小事实单位

  @Test
  void 一行就是法人货权乘SKU乘仓库乘库位乘批次乘质量状态() {
    var balance = balance(whSh, qSellable, "0", "0");
    split(balance, new B(locShA, lotA, "40"), new B(locShB, null, "60"));

    var rows = detailOf(actorA).rows();
    assertThat(rows).hasSize(2);
    for (var row : rows) {
      // 六个维度全部落在行上：缺任何一个都答不出"在哪个货位、什么批次、什么质量状态"
      assertThat(row.skuId()).isEqualTo(skuA);
      assertThat(row.warehouseId()).isEqualTo(whSh);
      assertThat(row.legalEntityId()).isEqualTo(leA);
      assertThat(row.qualityStatusId()).isEqualTo(qSellable);
      assertThat(row.qualityRole()).isEqualTo(WarehouseModels.QualityRole.SELLABLE);
      assertThat(row.locationId()).isIn(locShA, locShB);
    }
    // 库位与批次的展示快照也在行上：界面上要能直接看到货位编码与批号
    assertThat(rows).extracting(BucketRow::locationCode).containsExactlyInAnyOrder("A-01-01", "A-01-02");
    assertThat(rows).extracting(BucketRow::lotCode).containsExactlyInAnyOrder("LOT-2026-01", null);
    assertThat(rows).filteredOn(r -> r.lotId() != null).allMatch(r -> r.expiresOn() != null);
    assertThat(rows).noneMatch(BucketRow::expired);
  }

  @Test
  void 无批次行显式标记_而不是留空() {
    var balance = balance(whSh, qSellable, "0", "0");
    split(balance, new B(locShA, null, "50"));

    var detail = detailOf(actorA);
    assertThat(detail.rows()).hasSize(1);
    var row = detail.rows().get(0);
    assertThat(row.lotId()).isNull();
    assertThat(row.lotCode()).isNull();
    assertThat(row.expiresOn()).isNull();
    // 无批次行数单独计数：0 与"没查过批次"含义不同
    assertThat(detail.summary().unbatchedRowCount()).isEqualTo(1);
    assertThat(detail.summary().lotCount()).isZero();
  }

  @Test
  void 同一库位同一批次只能有一行_否则这批货的量永远是多行之和() {
    var balance = balance(whSh, qSellable, "0", "0");
    split(balance, new B(locShA, null, "10"));
    // 同六元组再插一条 → UNIQUE NULLS NOT DISTINCT 必须拒绝（无批次也算"无批次"这一个取值）
    assertThatThrownBy(() -> bucketOnly(balance, locShA, null, "5"))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    // 换成有批次也必须是唯一六元组
    assertThatThrownBy(() -> bucketOnly(balance, locShA, lotA, "5"))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
  }

  // ================================================================ 守恒

  @Test
  void bucket汇总等于balance_而且是DB级强制而不是靠约定() {
    var balance = balance(whSh, qSellable, "70", "30");
    bucketWithReserved(balance, locShA, lotA, "70", "30");

    var d = detailOf(actorA);
    assertThat(d.reconciliation().balanceOnHand()).isEqualByComparingTo("70");
    assertThat(d.reconciliation().bucketOnHand()).isEqualByComparingTo("70");
    assertThat(d.reconciliation().balanceReserved()).isEqualByComparingTo("30");
    assertThat(d.reconciliation().bucketReserved()).isEqualByComparingTo("30");
    assertThat(d.reconciliation().onHandConserved()).isTrue();
    assertThat(d.reconciliation().reservedConserved()).isTrue();
    // 行级可用 = 在手 − 预占：没有第三个可用量来源
    assertThat(d.rows().get(0).available()).isEqualByComparingTo("40");
    assertThat(d.summary().available()).isEqualByComparingTo("40");
  }

  @Test
  void 多个bucket汇总回一条balance() {
    var balance = balance(whSh, qSellable, "0", "0");
    // 同一 balance 的三个库位必须都在同一仓库：bucket 的仓库由复合外键锁定为 balance 的仓库，
    // 跨仓放同一行 bucket 正是这条外键要拦的错。
    split(balance, new B(locShA, lotA, "40"), new B(locShB, lotA, "35"), new B(locShB, null, "25"));
    var d = detailOf(actorA);
    assertThat(d.rows()).hasSize(3);
    assertThat(d.summary().onHand()).isEqualByComparingTo("100");
    assertThat(d.reconciliation().touchedBalanceCount()).isEqualTo(1);
    assertThat(d.reconciliation().bucketCountForTouched()).isEqualTo(3);
    assertThat(d.reconciliation().onHandConserved()).isTrue();
  }

  @Test
  void 只建balance不拆bucket是合法状态_但被单独报出来() {
    // 未拆到库位的 balance 行：明细页看不到它，不能让用户以为"明细漏数据"
    balance(whSh, qSellable, "80", "0");
    var d = detailOf(actorA);
    assertThat(d.rows()).isEmpty();
    assertThat(d.summary().rowCount()).isZero();
    assertThat(d.reconciliation().undecomposedBalanceCount()).isEqualTo(1);
    // 没命中任何 bucket 时核对不该谎报不平
    assertThat(d.reconciliation().touchedBalanceCount()).isZero();
    assertThat(d.reconciliation().onHandConserved()).isTrue();
  }

  @Test
  void 按库位筛选不改变核对数字_只筛一个库位不该报不平() {
    var balance = balance(whSh, qSellable, "0", "0");
    split(balance, new B(locShA, lotA, "40"), new B(locShQuarantine, null, "60"));

    var all = detailOf(actorA);
    var onlyA = access.run(actorA, "warehouse.inventory.read", () -> service.detail(query(locShA)));

    // 只命中 1 行，但核对的两边都是这条 balance 的**全量** 100/100
    assertThat(onlyA.rows()).hasSize(1);
    assertThat(onlyA.reconciliation().balanceOnHand()).isEqualByComparingTo("100");
    assertThat(onlyA.reconciliation().bucketOnHand()).isEqualByComparingTo("100");
    assertThat(onlyA.reconciliation().onHandConserved()).isTrue();
    assertThat(onlyA.reconciliation().bucketCountForTouched()).isEqualTo(2);
    assertThat(all.reconciliation().bucketCountForTouched()).isEqualTo(2);
  }

  @Test
  void bucket不平被DB拒绝_绕过应用层也不行() {
    var balance = balance(whSh, qSellable, "100", "0");
    // balance 100 但只写一条 40 的 bucket → 提交时守恒触发器必须炸
    assertThatThrownBy(() -> bucketOnly(balance, locShA, lotA, "40"))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
  }

  @Test
  void 只改balance不改bucket被DB拒绝() {
    var balance = balance(whSh, qSellable, "0", "0");
    split(balance, new B(locShA, lotA, "100"));
    assertThatThrownBy(
            () -> access.run(actorA, "warehouse.inventory.read", () -> {
              sql.execute("update inventory_balance set on_hand=150 where id=?", balance);
              return null;
            }))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
  }

  // ================================================================ 版本与业务时间

  @Test
  void 版本号与最近变动时间都真实落在行上() {
    var balance = balance(whSh, qSellable, "100", "0");
    var at = OffsetDateTime.now().minusDays(2).truncatedTo(ChronoUnit.SECONDS);
    bucketOnly(balance, locShA, lotA, "100");
    // 把业务时间改回两天前（只在数量不变时允许），验证 ?::timestamptz 写入无时区偏移
    access.run(actorA, "warehouse.inventory.read", () -> {
      sql.execute(
          "update stock_bucket set last_movement_at=?::timestamptz where balance_id=?",
          at.minusDays(1), balance);
      return null;
    });

    var row = detailOf(actorA).rows().get(0);
    // 业务时间原样读回（显式 ?::timestamptz，不发生静默的 28800s 偏移）
    assertThat(row.lastMovementAt()).isEqualTo(at.minusDays(1));
    // 系统时间与业务时间分开存：updated_at 是行写入时刻，不是业务发生时刻
    assertThat(row.updatedAt()).isNotNull();
    assertThat(row.updatedAt()).isAfter(row.lastMovementAt());
    assertThat(row.version()).isZero();
  }

  @Test
  void 数量变动时版本自增且业务时间必须前进() {
    var balance = balance(whSh, qSellable, "0", "0");
    split(balance, new B(locShA, lotA, "100"));
    var bucketId = detailOf(actorA).rows().get(0).bucketId();

    // 只改数量、不给新业务时间 → DB 拒绝（否则"最近变动时间"会永远停在建行时刻）
    assertThatThrownBy(
            () -> access.run(actorA, "warehouse.inventory.read", () -> {
              sql.execute("update stock_bucket set on_hand=90 where id=?", bucketId);
              return null;
            }))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);

    access.run(actorA, "warehouse.inventory.read", () -> {
      sql.execute(
          "update stock_bucket set on_hand=90,last_movement_at=?::timestamptz where id=?",
          OffsetDateTime.now().plusMinutes(1), bucketId);
      sql.execute("update inventory_balance set on_hand=90 where id=?", balance);
      return null;
    });
    // 版本由 DB 自增，调用方无需自记：这是下游并发写入的基础设施
    assertThat(detailOf(actorA).rows().get(0).version()).isEqualTo(1);
    assertThat(detailOf(actorA).rows().get(0).onHand()).isEqualByComparingTo("90");
  }

  // ================================================================ 逐级下钻

  @Test
  void 逐级下钻筛选真实生效() {
    var b1 = balance(whSh, qSellable, "0", "0");
    split(b1, new B(locShA, lotA, "10"));
    var b2 = balance(whSz, qSellable, "0", "0");
    split(b2, new B(locSzA, null, "20"));
    var b3 = balance(whSh, qPending, "0", "0");
    split(b3, new B(locShQuarantine, null, "30"));

    // 按仓库
    assertThat(by(whSh, null, null, null, null).rows()).hasSize(2);
    // 按库位（最细一级）
    assertThat(by(null, locSzA, null, null, null).rows()).hasSize(1);
    // 按批次（只命中有批次的那行）
    assertThat(by(null, null, lotA, null, null).rows()).hasSize(1);
    // 按质量状态
    assertThat(by(null, null, null, qPending, null).rows()).hasSize(1);
    // 按 SKU
    assertThat(by(null, null, null, null, skuA).rows()).hasSize(3);
    // 组合下钻：仓库 + 质量状态
    assertThat(by(whSh, null, null, qSellable, null).rows()).hasSize(1);

    assertThat(by(null, locSzA, null, null, null).rows().get(0).warehouseId()).isEqualTo(whSz);
    assertThat(by(null, null, lotA, null, null).rows().get(0).lotCode()).isEqualTo("LOT-2026-01");
  }

  @Test
  void 只看可拣库位会排除待检库位() {
    var b1 = balance(whSh, qSellable, "0", "0");
    split(b1, new B(locShA, lotA, "10"));
    var b2 = balance(whSz, qSellable, "0", "0");
    split(b2, new B(locSzA, null, "20"));
    var b3 = balance(whSh, qPending, "0", "0");
    split(b3, new B(locShQuarantine, null, "30"));

    var all = detailOf(actorA);
    assertThat(all.rows()).hasSize(3);
    assertThat(all.rows()).anyMatch(r -> !r.pickable());

    var pickable =
        access.run(
            actorA,
            "warehouse.inventory.read",
            () ->
                service.detail(
                    new BucketDetailQuery(
                        null, null, null, null, null, null, null, true, null, null, null)));
    assertThat(pickable.rows()).hasSize(2);
    assertThat(pickable.rows()).allMatch(BucketRow::pickable);
  }

  @Test
  void 关键字命中库位与批次编码() {
    var b1 = balance(whSh, qSellable, "0", "0");
    split(b1, new B(locShA, lotA, "10"));
    var b2 = balance(whSz, qSellable, "0", "0");
    split(b2, new B(locSzA, null, "20"));

    // 批次编码唯一，用它能精确命中；库位编码在两个仓里都可能出现
    assertThat(keyword("LOT-2026-01").rows()).hasSize(1);
    // 关键字命中库位编码：A-01-01 只在上海仓
    assertThat(keyword("A-01-01").rows()).hasSize(1);
    assertThat(keyword("B-02-01").rows()).hasSize(1);
    assertThat(keyword("SKU-1").rows()).hasSize(2);
  }

  @Test
  void 非法排序键被拒绝() {
    assertThatThrownBy(
            () ->
                access.run(
                    actorA,
                    "warehouse.inventory.read",
                    () ->
                        service.detail(
                            new BucketDetailQuery(
                                null, null, null, null, null, null, null, null,
                                "1;drop table", null, null))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void 排序按白名单生效() {
    var b1 = balance(whSh, qSellable, "0", "0");
    split(b1, new B(locShA, lotA, "10"), new B(locShB, null, "90"));
    var byLocation =
        access.run(
            actorA,
            "warehouse.inventory.read",
            () ->
                service.detail(
                    new BucketDetailQuery(
                        null, null, null, null, null, null, null, null, "LOCATION_CODE", null, null)));
    // 按库位编码升序：A-01-01 在 A-01-02 之前（与在手量的 90/10 顺序相反）
    assertThat(byLocation.rows()).extracting(BucketRow::locationCode)
        .containsExactly("A-01-01", "A-01-02");
  }

  @Test
  void 分页不改变合计() {
    var b1 = balance(whSh, qSellable, "0", "0");
    split(b1, new B(locShA, lotA, "10"));
    var b2 = balance(whSh, qPending, "0", "0");
    split(b2, new B(locShQuarantine, null, "20"));
    var b3 = balance(whSz, qSellable, "0", "0");
    split(b3, new B(locSzA, null, "30"));

    var page0 = paged(0, 2);
    var page1 = paged(1, 2);

    assertThat(page0.rows()).hasSize(2);
    assertThat(page1.rows()).hasSize(1);
    // 合计是全量的，不随分页变化
    assertThat(page0.summary().onHand()).isEqualByComparingTo("60");
    assertThat(page1.summary().onHand()).isEqualByComparingTo("60");
    assertThat(page0.summary().rowCount()).isEqualTo(3);
    assertThat(page1.total()).isEqualTo(3);
  }

  @Test
  void 质量角色拆分与总览同口径() {
    var b1 = balance(whSh, qSellable, "0", "0");
    split(b1, new B(locShA, lotA, "100"));
    var b2 = balance(whSh, qPending, "0", "0");
    split(b2, new B(locShQuarantine, null, "20"));
    var b3 = balance(whSh, qDefective, "0", "0");
    split(b3, new B(locShA, null, "5"));

    var s = detailOf(actorA).summary();
    assertThat(s.sellable()).isEqualByComparingTo("100");
    assertThat(s.pendingInspection()).isEqualByComparingTo("20");
    assertThat(s.defective()).isEqualByComparingTo("5");
    assertThat(s.onHand()).isEqualByComparingTo("125");
    // 库位数：locShA 上有可售与残次两行，同一个库位只算一个
    assertThat(s.locationCount()).isEqualTo(2);
    assertThat(s.lotCount()).isEqualTo(1);
  }

  @Test
  void 零库存bucket可识别() {
    // balance 在手必须先有货：预占 10 时在手不能是 0，否则 V1 的 available>=0 CHECK 先炸
    var balance = balance(whSh, qSellable, "10", "0");
    bucketWithReserved(balance, locShA, lotA, "10", "10");
    var row = detailOf(actorA).rows().get(0);
    assertThat(row.available()).isEqualByComparingTo("0");
    assertThat(row.zeroStock()).isTrue();
  }

  // ================================================================ 行内操作入口

  @Test
  void 行内操作入口给出路由契约_未交付时如实标注不可用() {
    var actions = detailOf(actorA).actions();
    assertThat(actions).hasSize(3);
    assertThat(actions).extracting(BucketAction::action)
        .containsExactly("ADJUST", "TRANSFER", "LEDGER");
    // 7.1.2.1 库存流水台账已交付 → 「查看流水」这条入口现在真的点得动，必须翻成 true，
    // 否则页面上会出现一个已经交付却标着"未交付"的入口。7.4.1.1 调整与 7.5.1.1 调拨仍未交付，
    // 继续如实标 false 并说明原因，而不是给一个点下去 404 的按钮。
    assertThat(actions).filteredOn(a -> a.action().equals("LEDGER")).singleElement()
        .satisfies(a -> assertThat(a.available()).isTrue());
    assertThat(actions).filteredOn(a -> a.action().equals("ADJUST") || a.action().equals("TRANSFER"))
        .allMatch(a -> !a.available());
    assertThat(actions).allMatch(a -> a.ownerModule() != null && !a.ownerModule().isBlank());
    assertThat(actions).allMatch(a -> a.note().contains(a.ownerModule()));
    // href 带齐 bucket 定位参数：目标模块落地后入口能直接定位到这一行
    assertThat(actions.get(0).href())
        .contains("/inventory/adjustments")
        .contains("skuId={skuId}")
        .contains("locationId={locationId}")
        .contains("lotId={lotId}");
  }

  // ================================================================ DB 级兜底

  @Test
  void bucket行不允许删除_清空请归零() {
    var balance = balance(whSh, qSellable, "0", "0");
    split(balance, new B(locShA, lotA, "10"));
    var bucketId = detailOf(actorA).rows().get(0).bucketId();
    assertThatThrownBy(
            () -> access.run(actorA, "warehouse.inventory.read", () -> {
              sql.execute("delete from stock_bucket where id=?", bucketId);
              return null;
            }))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
  }

  @Test
  void 非可售bucket不允许预占() {
    var balance = balance(whSh, qPending, "0", "0");
    split(balance, new B(locShQuarantine, null, "10"));
    assertThatThrownBy(
            () -> access.run(actorA, "warehouse.inventory.read", () -> {
              sql.execute(
                  "update stock_bucket set reserved=1,"
                      + "last_movement_at=?::timestamptz where balance_id=?",
                  OffsetDateTime.now().plusMinutes(1), balance);
              return null;
            }))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
  }

  @Test
  void bucket的仓库由balance锁定_且跨服务不得建外键() {
    var balance = balance(whSh, qSellable, "0", "0");
    split(balance, new B(locShA, lotA, "10"));

    // 复合外键存在：bucket 的 (法人,SKU,仓库,质量状态) 必须与 balance 逐列相同，
    // 所以 bucket 不可能声称自己属于另一个仓。约束名由 PG 截断到 63 字符，这里按前缀匹配。
    var compositeFk = access.run(actorA, "warehouse.inventory.read", () -> sql.fetchOne(
        "select count(*) from information_schema.table_constraints"
            + " where constraint_type='FOREIGN KEY' and table_name='stock_bucket'"
            + " and constraint_name like 'stock_bucket_tenant_id_balance_id%'"
            + " and constraint_name like '%sku_id_w_fkey'"));
    assertThat(compositeFk.get(0, Long.class)).isEqualTo(1);

    // 真的写不进去：把 bucket 的仓库改成深圳仓，而 balance 还在上海仓
    assertThatThrownBy(
            () -> access.run(actorA, "warehouse.inventory.read", () -> {
              sql.execute(
                  "update stock_bucket set warehouse_id=? where balance_id=?", whSz, balance);
              return null;
            }))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);

    // 跨服务不得建外键：sku 仍只是逻辑 ID，不 REFERENCES 对方表
    var skuFk = access.run(actorA, "warehouse.inventory.read", () -> sql.fetchOne(
        "select count(*) from information_schema.table_constraints"
            + " where constraint_type='FOREIGN KEY'"
            + " and table_name in ('stock_bucket','inventory_lot')"
            + " and constraint_name not like '%balance_id%'"
            + " and constraint_name not like '%lot_id%'"
            + " and constraint_name not like '%location_id%'"));
    assertThat(skuFk.get(0, Long.class)).isZero();
  }

  @Test
  void 新表同样受RLS保护() {
    var enabled =
        access.run(actorA, "warehouse.inventory.read", () -> sql.fetch(
            "select relname,relrowsecurity,relforcerowsecurity from pg_class"
                + " where relname in ('stock_bucket','warehouse_location','inventory_lot')"
                + " order by relname"));
    assertThat(enabled).hasSize(3);
    assertThat(enabled).allMatch(r -> Boolean.TRUE.equals(r.get("relrowsecurity", Boolean.class)));
    assertThat(enabled).allMatch(r -> Boolean.TRUE.equals(r.get("relforcerowsecurity", Boolean.class)));
  }

  // ================================================================ 端点

  @Test
  void 明细端点返回bucket行与核对结果() throws Exception {
    var balance = balance(whSh, qSellable, "70", "30");
    bucketWithReserved(balance, locShA, lotA, "70", "30");

    http.perform(get("/warehouse/inventory/buckets").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rows.length()").value(1))
        .andExpect(jsonPath("$.rows[0].locationCode").value("A-01-01"))
        .andExpect(jsonPath("$.rows[0].lotCode").value("LOT-2026-01"))
        .andExpect(jsonPath("$.rows[0].available").value(40))
        .andExpect(jsonPath("$.summary.onHand").value(70))
        .andExpect(jsonPath("$.reconciliation.onHandConserved").value(true))
        .andExpect(jsonPath("$.reconciliation.balanceOnHand").value(70))
        .andExpect(jsonPath("$.reconciliation.undecomposedBalanceCount").value(0))
        .andExpect(jsonPath("$.actions.length()").value(3));
  }

  @Test
  void 明细端点支持逐级下钻参数() throws Exception {
    var b1 = balance(whSh, qSellable, "0", "0");
    split(b1, new B(locShA, lotA, "10"));
    var b2 = balance(whSz, qSellable, "0", "0");
    split(b2, new B(locSzA, null, "20"));

    http.perform(get("/warehouse/inventory/buckets")
            .header("Authorization", "Bearer tenant-a")
            .param("warehouseId", whSz.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rows.length()").value(1))
        .andExpect(jsonPath("$.rows[0].warehouseCode").value("WH-SZ"));
    http.perform(get("/warehouse/inventory/buckets")
            .header("Authorization", "Bearer tenant-a")
            .param("locationId", locShA.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rows.length()").value(1))
        .andExpect(jsonPath("$.rows[0].locationCode").value("A-01-01"));
  }

  @Test
  void 库位与批次下拉端点可读() throws Exception {
    http.perform(get("/warehouse/locations").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(4));
    // 按仓库过滤：逐级下钻的第二级
    http.perform(get("/warehouse/locations")
            .header("Authorization", "Bearer tenant-a")
            .param("warehouseId", whSh.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(3));
    // 按库位类型过滤：待检区是运营找问题的常用入口
    http.perform(get("/warehouse/locations")
            .header("Authorization", "Bearer tenant-a")
            .param("type", "quarantine"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].code").value("Q-01"));
    http.perform(get("/warehouse/lots").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].lotCode").value("LOT-2026-01"));
  }

  @Test
  void 未认证一律401() throws Exception {
    http.perform(get("/warehouse/inventory/buckets")).andExpect(status().isUnauthorized());
    // 明细与总览同为 warehouse.inventory.read：权限具备时可读，不额外收窄
    http.perform(get("/warehouse/inventory/buckets").header("Authorization", "Bearer reader"))
        .andExpect(status().isOk());
  }

  // ================================================================ 租户隔离三断言

  @Test
  void 断言一_两租户互不可见() {
    var balance = balance(whSh, qSellable, "0", "0");
    split(balance, new B(locShA, lotA, "100"));

    assertThat(detailOf(actorA).rows()).hasSize(1);
    assertThat(detailOf(actorA).summary().onHand()).isEqualByComparingTo("100");
    // 租户 B 有自己的参照数据但没有 bucket → 看到 0 行
    assertThat(detailOf(actorB).rows()).isEmpty();
    assertThat(detailOf(actorB).summary().onHand()).isEqualByComparingTo("0");
    assertThat(detailOf(actorB).reconciliation().touchedBalanceCount()).isZero();
    // 下拉参照数据同样按租户隔离：B 只看得到自己的 4 条库位
    assertThat(access.run(actorA, "warehouse.inventory.read", () -> service.locations(null, null)))
        .hasSize(4);
    assertThat(access.run(actorB, "warehouse.inventory.read", () -> service.locations(null, null)))
        .hasSize(4);
    assertThat(access.run(actorA, "warehouse.inventory.read", () -> service.lots(null, null)))
        .extracting(LotRef::lotCode)
        .containsExactly("LOT-2026-01");
    assertThat(access.run(actorB, "warehouse.inventory.read", () -> service.lots(null, null)))
        .hasSize(1);
  }

  @Test
  void 断言二_伪造租户头无效() throws Exception {
    var balance = balance(whSh, qSellable, "0", "0");
    split(balance, new B(locShA, lotA, "100"));

    // 浏览器伪造 X-Tenant-Id 指向 A，身份仍是 B → 只能看到 B 的（空）视图
    http.perform(get("/warehouse/inventory/buckets")
            .header("Authorization", "Bearer tenant-b")
            .header("X-Tenant-Id", a.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rows.length()").value(0))
        .andExpect(jsonPath("$.summary.onHand").value(0));
    // 反向：身份是 A 时伪造 B 也拿不到 B 的数据
    http.perform(get("/warehouse/inventory/buckets")
            .header("Authorization", "Bearer tenant-a")
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rows.length()").value(1))
        .andExpect(jsonPath("$.summary.onHand").value(100));
    // 下拉端点同样不受伪造头影响
    http.perform(get("/warehouse/locations")
            .header("Authorization", "Bearer tenant-a")
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(4));
  }

  @Test
  void 断言三_连接池归还后不串租户() {
    var balance = balance(whSh, qSellable, "0", "0");
    split(balance, new B(locShA, lotA, "100"));
    // 池大小为 1，同一条连接被反复借还：交替访问两个租户 20 轮。
    for (int i = 0; i < 20; i++) {
      var fa = detailOf(actorA);
      var fb = detailOf(actorB);
      assertThat(fa.rows()).hasSize(1);
      assertThat(fb.rows()).isEmpty();
      assertThat(fa.summary().onHand()).isEqualByComparingTo("100");
      assertThat(fb.summary().onHand()).isEqualByComparingTo("0");
    }
  }

  // ---------------------------------------------------------------- 工具

  InventoryBucketDetail by(
      UUID warehouse, UUID location, UUID lot, UUID quality, UUID sku) {
    return access.run(
        actorA,
        "warehouse.inventory.read",
        () ->
            service.detail(
                new BucketDetailQuery(
                    null, warehouse, location, sku, lot, quality, null, null, null, null, null)));
  }

  InventoryBucketDetail keyword(String k) {
    return access.run(
        actorA,
        "warehouse.inventory.read",
        () ->
            service.detail(
                new BucketDetailQuery(
                    null, null, null, null, null, null, k, null, null, null, null)));
  }

  InventoryBucketDetail paged(int page, int size) {
    return access.run(
        actorA,
        "warehouse.inventory.read",
        () ->
            service.detail(
                new BucketDetailQuery(
                    null, null, null, null, null, null, null, null, "ON_HAND", page, size)));
  }
}
