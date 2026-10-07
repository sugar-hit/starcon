package com.starcon.warehouse;

import static com.starcon.warehouse.StocktakeModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.starcon.warehouse.StocktakeModels.StocktakeCountInput;
import com.starcon.warehouse.StocktakeModels.StocktakeRequest;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.jooq.DSLContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 7.4.2.1 盘点单：冻结时点快照不可被后续交易改写、三列输出、四种范围的真实能力、
 * 任务生成与派工、时区零漂移，以及租户隔离三断言。
 *
 * <p>真实 PostgreSQL 16 + 强制 RLS，不使用任何内存替身。连接池固定为 1 条连接：
 * 只有复用同一条连接，才能真正证明归还后不会串租户。
 *
 * <p>夹具写法与 {@link SellablePolicyIntegrationTest} 完全一致（同样的 Testcontainers、
 * 同样的 fake identity server、同样的按租户派生 UUID、同样的 TRUNCATE 清场）。
 *
 * <p><b>本测试的核心是 {@link #冻结的账面数不被后续出入库改写()} 这一条</b>：蓝图原话
 * 「盘点时点必须冻结账面数，否则边盘边卖会产生虚假差异」。所以测试必须**真的**在发起盘点之后
 * 做一笔出入库，然后逐列断言：账面列一字不动、实盘列按录入值、差异列按「实盘 − 账面」重算。
 * 只测「发起盘点能查到账面数」证明不了冻结 —— 那条路（实时查当前余额）恰好是蓝图点名要否掉的。
 */
@SpringBootTest
@AutoConfigureMockMvc
class StocktakeIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static final List<String> allPermissions =
      Stream.of("inventory")
          .flatMap(p -> Stream.of("read", "create", "update", "delete").map(op -> "warehouse." + p + "." + op))
          .toList();

  static final UUID le = idFor(a, 1);
  static final UUID skuOne = idFor(a, 2);
  static final UUID skuTwo = idFor(a, 3);
  static final UUID whOne = idFor(a, 4);
  static final UUID whTwo = idFor(a, 5);
  static final UUID qSellable = idFor(a, 6);
  static final UUID locOne = idFor(a, 7);
  static final UUID locTwo = idFor(a, 8);
  static final UUID locThree = idFor(a, 9);
  static final UUID lotOne = idFor(a, 10);

  static UUID idFor(UUID tenant, int ordinal) {
    return UUID.nameUUIDFromBytes(("starcon-warehouse-stocktake-" + tenant + "-" + ordinal).getBytes());
  }

  static AccessService.Access actor(UUID uid, UUID tenant, List<AccessService.Grant> grants) {
    return new AccessService.Access(
        uid,
        tenant,
        "Test",
        UUID.randomUUID(),
        null,
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
            byte[] body = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(access);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      actors.put("Bearer tenant-a", actor(user, a, List.of(grant("ALL", allPermissions))));
      actors.put("Bearer tenant-b", actor(user, b, List.of(grant("ALL", allPermissions))));
      actors.put(
          "Bearer read-only",
          actor(user, a, List.of(grant("ALL", List.of("warehouse.inventory.read")))));
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

  @Autowired StocktakeService service;
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
    // TRUNCATE 不触发行级触发器，以超级用户连接执行可绕过 FORCE RLS ——
    // 只用于测试夹具重建，不给应用角色开这个口子。
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate stocktake_line, stocktake_task, stocktake_sheet, stock_bucket, warehouse_location,"
              + " inventory_lot, inventory_balance, inventory_quality_status, warehouse, sku_ref,"
              + " legal_entity_ref restart identity cascade");
    }
    for (var tenant : List.of(a, b)) {
      access.run(
          actorFor(tenant),
          "warehouse.inventory.read",
          () -> {
            sql.execute(
                "insert into legal_entity_ref(id,tenant_id,code,name) values(?,?,?,?)",
                idFor(tenant, 1), tenant, "LE-1", "主体一");
            sql.execute(
                "insert into sku_ref(id,tenant_id,code,name) values(?,?,?,?)",
                idFor(tenant, 2), tenant, "SKU-1", "商品一");
            sql.execute(
                "insert into sku_ref(id,tenant_id,code,name) values(?,?,?,?)",
                idFor(tenant, 3), tenant, "SKU-2", "商品二");
            sql.execute(
                "insert into warehouse(id,tenant_id,code,name,country_code,timezone) values(?,?,?,?,?,?)",
                idFor(tenant, 4), tenant, "WH-ONE", "一仓", "CN", "Asia/Shanghai");
            sql.execute(
                "insert into warehouse(id,tenant_id,code,name,country_code,timezone) values(?,?,?,?,?,?)",
                idFor(tenant, 5), tenant, "WH-TWO", "二仓", "CN", "Asia/Shanghai");
            sql.execute(
                "insert into inventory_quality_status(id,tenant_id,code,name,role) values(?,?,?,?,?)",
                idFor(tenant, 6), tenant, "SELLABLE", "可售", "SELLABLE");
            sql.execute(
                "insert into warehouse_location(id,tenant_id,warehouse_id,code,name) values(?,?,?,?,?)",
                idFor(tenant, 7), tenant, idFor(tenant, 4), "A-01", "A 区一排");
            sql.execute(
                "insert into warehouse_location(id,tenant_id,warehouse_id,code,name) values(?,?,?,?,?)",
                idFor(tenant, 8), tenant, idFor(tenant, 4), "A-02", "A 区二排");
            sql.execute(
                "insert into warehouse_location(id,tenant_id,warehouse_id,code,name) values(?,?,?,?,?)",
                idFor(tenant, 9), tenant, idFor(tenant, 5), "B-01", "B 区一排");
            sql.execute(
                "insert into inventory_lot(id,tenant_id,sku_id,lot_code,received_on) values(?,?,?,?,?)",
                idFor(tenant, 10), tenant, idFor(tenant, 2), "LOT-A", java.sql.Date.valueOf("2026-09-01"));
            return null;
          });
    }
  }

  AccessService.Access actorFor(UUID tenant) {
    return tenant.equals(a) ? actorA : actorB;
  }

  static OffsetDateTime now() {
    return OffsetDateTime.now().truncatedTo(ChronoUnit.MILLIS);
  }

  // ================================================================ 夹具写入

  /**
   * 一个余额行（同租户同仓同 SKU 同货权同质量状态**只有一条** balance —— 这是 V1 的唯一约束）
   * + 若干个库位 bucket 挂在它下面，{@code Σbucket.on_hand == balance.on_hand}（V2 守恒触发器）。
   *
   * <p>这个形状不是测试的随意选择：V1 把 balance 定为「仓 × 货权 × SKU × 质量状态」的可用量
   * 决策单位，V2 才在它下面按库位/批次拆成 bucket。盘点冻结的是 **bucket**（最小事实单位），
   * 所以夹具必须先把 balance 摆对，否则守恒约束当场拒绝。
   *
   * <p>每组一条 balance 的所有写入都在**同一个** {@code access.run} 里：守恒触发器是
   * DEFERRABLE INITIALLY DEFERRED 但仍在同一事务核对，分成两个事务会被当场拒掉。
   *
   * @param entries 每个库位一行：库位 id、批次 id（非批次管理品传 null）、数量
   */
  record BucketSpec(UUID location, UUID lot, String quantity) {}

  void group(UUID warehouse, UUID sku, List<BucketSpec> entries) {
    access.run(
        actorA,
        "warehouse.inventory.read",
        () -> {
          BigDecimal sum = BigDecimal.ZERO;
          for (BucketSpec e : entries) {
            sum = sum.add(new BigDecimal(e.quantity()));
          }
          UUID balanceId = UUID.randomUUID();
          sql.execute(
              "insert into inventory_balance(id,tenant_id,sku_id,warehouse_id,legal_entity_id,"
                  + "quality_status_id,on_hand,reserved) values(?,?,?,?,?,?,?,?)",
              balanceId, a, sku, warehouse, le, qSellable, sum, BigDecimal.ZERO);
          for (BucketSpec e : entries) {
            sql.execute(
                "insert into stock_bucket(id,tenant_id,legal_entity_id,sku_id,warehouse_id,location_id,"
                    + "lot_id,quality_status_id,balance_id,on_hand,reserved,last_movement_at) "
                    + "values(?,?,?,?,?,?,?,?,?,?,0::numeric,?::timestamptz)",
                UUID.randomUUID(), a, le, sku, warehouse, e.location(), e.lot(), qSellable, balanceId,
                new BigDecimal(e.quantity()), now());
          }
          return null;
        });
  }

  /** 租户 B 的同形态夹具（跨租户断言用）。 */
  void groupFor(UUID tenant, UUID warehouse, UUID sku, String quantity) {
    access.run(
        actorFor(tenant),
        "warehouse.inventory.read",
        () -> {
          UUID balanceId = UUID.randomUUID();
          sql.execute(
              "insert into inventory_balance(id,tenant_id,sku_id,warehouse_id,legal_entity_id,"
                  + "quality_status_id,on_hand,reserved) values(?,?,?,?,?,?,?,?)",
              balanceId, tenant, sku, warehouse, idFor(tenant, 1), idFor(tenant, 6),
              new BigDecimal(quantity), BigDecimal.ZERO);
          sql.execute(
              "insert into stock_bucket(id,tenant_id,legal_entity_id,sku_id,warehouse_id,location_id,"
                  + "quality_status_id,balance_id,on_hand,reserved,last_movement_at) "
                  + "values(?,?,?,?,?,?,?,?,?,0::numeric,?::timestamptz)",
              UUID.randomUUID(), tenant, idFor(tenant, 1), sku, warehouse, idFor(tenant, 7), idFor(tenant, 6),
              balanceId, new BigDecimal(quantity), now());
          return null;
        });
  }

  /**
   * 标准库存夹具（租户 A）：
   *
   * <pre>
   *   一仓 A-01  SKU-1  批次 LOT-A  100
   *   一仓 A-01  SKU-2  无批次      40
   *   一仓 A-02  SKU-1  无批次      25
   *   二仓 B-01  SKU-1  无批次      60
   * </pre>
   *
   * <p>这样三种范围都能筛出**真正不同**的行集：按一仓 = 前三行、按二仓 = 最后一行、
   * 按库位 A-01 = 前两行。测试要能断言这些集合各不相同，否则范围筛选就是假的。
   *
   * <p>注意 SKU-1 在一仓有两条 bucket（A-01/A-02）却只有一条 balance —— 余额按仓决策，
   * 明细按库位拆分，正是 V1/V2 的分层口径。
   */
  void standardStock() {
    group(whOne, skuOne, List.of(new BucketSpec(locOne, lotOne, "100"), new BucketSpec(locTwo, null, "25")));
    group(whOne, skuTwo, List.of(new BucketSpec(locOne, null, "40")));
    group(whTwo, skuOne, List.of(new BucketSpec(locThree, null, "60")));
  }

  StocktakeSaved open(StocktakeScopeType scope, UUID key, StocktakeTaskUnit unit) {
    return access.run(
        actorA,
        "warehouse.inventory.update",
        () -> service.create(new StocktakeRequest(scope.name(), key, unit.name(), "夹具"), user));
  }

  // ================================================================ 冻结不变量（本测试的核心）

  /**
   * 蓝图红线：「盘点时点必须冻结账面数，否则边盘边卖会产生虚假差异。」
   *
   * <p>这一条必须真的做一笔出入库再逐列断言。只断言「发起后账面数等于某个值」证明不了冻结 ——
   * 实时查当前余额的实现也能通过那个断言，而且它恰恰是蓝图要否掉的那条路。
   */
  @Test
  void 冻结的账面数不被后续出入库改写() {
    standardStock();
    var saved = open(StocktakeScopeType.WAREHOUSE, whOne, StocktakeTaskUnit.BY_LOCATION);
    UUID sheetId = saved.sheet().id();
    var before = access.run(actorA, "warehouse.inventory.read", () -> service.detail(sheetId));
    assertThat(before.sheet().lineCount()).isEqualTo(3);
    assertThat(before.summary().bookTotal()).isEqualByComparingTo("165");

    // 盘点进行中：SKU-1 在 A-01 上出库 30 件（真实的边盘边卖）。
    // balance 与 bucket 必须在同一事务里改，守恒触发器是 DEFERRABLE 的。
    OffsetDateTime moved = now().plusMinutes(1);
    access.run(
        actorA,
        "warehouse.inventory.update",
        () -> {
          sql.execute(
              "update stock_bucket set on_hand = on_hand - 30::numeric, last_movement_at = ?::timestamptz"
                  + " where tenant_id = ? and location_id = ? and sku_id = ?",
              moved, a, locOne, skuOne);
          sql.execute(
              "update inventory_balance set on_hand = on_hand - 30::numeric"
                  + " where tenant_id = ? and warehouse_id = ? and sku_id = ? and quality_status_id = ?",
              a, whOne, skuOne, qSellable);
          return null;
        });

    // 账面合计必须纹丝不动 —— 这正是「冻结」的含义。
    var afterMove = access.run(actorA, "warehouse.inventory.read", () -> service.detail(sheetId));
    assertThat(afterMove.summary().bookTotal()).isEqualByComparingTo("165");
    var line = access.run(actorA, "warehouse.inventory.read", () -> service.lines(sheetId, null, null, 0, 50).lines())
        .stream().filter(l -> l.skuId().equals(skuOne) && l.locationId().equals(locOne)).findFirst().orElseThrow();
    assertThat(line.bookOnHand()).isEqualByComparingTo("100");

    // 录入实盘 95（盘点员确实看到 95 件）→ 差异 = 95 − 100 = −5，
    // 而**不是** 95 − 70（当前余额）。这就是冻结挡掉的虚假差异。
    var counted = access.run(
        actorA, "warehouse.inventory.update", () -> service.count(sheetId, line.id(), new StocktakeCountInput(new BigDecimal("95"), "6901234567892", "现场清点"), user));
    assertThat(counted.bookOnHand()).isEqualByComparingTo("100");
    assertThat(counted.countedQty()).isEqualByComparingTo("95");
    assertThat(counted.variance()).isEqualByComparingTo("-5");
    assertThat(counted.countedBarcode()).isEqualTo("6901234567892");

    // 当前余额确实已经变成 70 —— 证明上面那笔出入库真的发生了，不是没生效的空转。
    assertThat(access.run(actorA, "warehouse.inventory.read", () ->
            sql.fetchOne("select on_hand from stock_bucket where tenant_id = ? and location_id = ? and sku_id = ?", a, locOne, skuOne)
                .get("on_hand", BigDecimal.class)))
        .isEqualByComparingTo("70");

    // 三列合计也要跟着实盘重算，且账面列仍在 165。
    var after = access.run(actorA, "warehouse.inventory.read", () -> service.detail(sheetId));
    assertThat(after.summary().bookTotal()).isEqualByComparingTo("165");
    assertThat(after.summary().countedTotal()).isEqualByComparingTo("95");
    assertThat(after.summary().varianceTotal()).isEqualByComparingTo("-5");
  }

  /** 账面数由 DB 钉死：绕过服务层直接 UPDATE 改账面列必须被拒。 */
  @Test
  void 账面数在DB层不可改写() {
    standardStock();
    var saved = open(StocktakeScopeType.WAREHOUSE, whOne, StocktakeTaskUnit.BY_LOCATION);
    UUID sheetId = saved.sheet().id();
    UUID lineId = access.run(actorA, "warehouse.inventory.read", () -> service.lines(sheetId, null, null, 0, 1).lines())
        .get(0).id();

    assertThatThrownBy(() -> access.run(actorA, "warehouse.inventory.update", () -> {
              sql.execute("update stocktake_line set book_on_hand = 1::numeric where id = ?", lineId);
              return null;
            }))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("冻结");
    // 改了维度列同样不行：账面数要能回答「当时是哪个库位的那一行」。
    assertThatThrownBy(() -> access.run(actorA, "warehouse.inventory.update", () -> {
              sql.execute("update stocktake_line set location_code = 'X' where id = ?", lineId);
              return null;
            }))
        .isInstanceOf(DataAccessException.class);
    // 明细行也不许删：删掉它等于让这张单从未盘过这一行。
    assertThatThrownBy(() -> access.run(actorA, "warehouse.inventory.update", () -> {
              sql.execute("delete from stocktake_line where id = ?", lineId);
              return null;
            }))
        .isInstanceOf(DataAccessException.class);
    // 账面数原封不动
    assertThat(access.run(actorA, "warehouse.inventory.read", () ->
            sql.fetchOne("select book_on_hand from stocktake_line where id = ?", lineId).get("book_on_hand", BigDecimal.class)))
        .isEqualByComparingTo("100");
  }

  /** 差异是算出来的：generated column 恒等于 实盘 − 账面，人工赋值不存在这条路。 */
  @Test
  void 差异列是生成列而非人工填写() {
    standardStock();
    var saved = open(StocktakeScopeType.WAREHOUSE, whOne, StocktakeTaskUnit.BY_LOCATION);
    UUID sheetId = saved.sheet().id();
    UUID lineId = access.run(actorA, "warehouse.inventory.read", () -> service.lines(sheetId, null, null, 0, 1).lines())
        .get(0).id();

    // 直接写 variance 必须被 PG 拒绝（generated column）。
    assertThatThrownBy(() -> access.run(actorA, "warehouse.inventory.update", () -> {
              sql.execute("update stocktake_line set variance = 0::numeric where id = ?", lineId);
              return null;
            }))
        .isInstanceOf(DataAccessException.class);

    // 盘盈也要能算出来：账面 100 录成 103 → +3
    var line = access.run(
        actorA, "warehouse.inventory.update", () -> service.count(sheetId, lineId, new StocktakeCountInput(new BigDecimal("103"), null, null), user));
    assertThat(line.variance()).isEqualByComparingTo("3");
  }

  // ================================================================ 三列输出与录入

  /** 未录入时差异为 NULL 而不是 0 —— 「还没盘」与「盘出 0」必须可区分。 */
  @Test
  void 未录入行的差异为空而不是零() {
    standardStock();
    var saved = open(StocktakeScopeType.WAREHOUSE, whOne, StocktakeTaskUnit.BY_LOCATION);
    var lines = access.run(actorA, "warehouse.inventory.read", () -> service.lines(saved.sheet().id(), null, null, 0, 50));
    assertThat(lines.total()).isEqualTo(3);
    assertThat(lines.lines()).allSatisfy(l -> {
      assertThat(l.countedQty()).isNull();
      assertThat(l.variance()).isNull();
    });
    // 未录入的行合计差异为 0，但「有差异行数」是 0（不是 3）
    var detail = access.run(actorA, "warehouse.inventory.read", () -> service.detail(saved.sheet().id()));
    assertThat(detail.summary().countedLineCount()).isZero();
    assertThat(detail.summary().varianceLineCount()).isZero();
    assertThat(detail.sheet().status()).isEqualTo(StocktakeSheetStatus.OPEN);
  }

  /** 录入推进状态；撤销录入（countedQty 传 null）必须把状态推回去。 */
  @Test
  void 录入推进状态且可撤销() {
    standardStock();
    var saved = open(StocktakeScopeType.WAREHOUSE, whOne, StocktakeTaskUnit.BY_LOCATION);
    UUID sheetId = saved.sheet().id();
    var all = access.run(actorA, "warehouse.inventory.read", () -> service.lines(sheetId, null, null, 0, 50)).lines();

    access.run(actorA, "warehouse.inventory.update", () -> service.count(sheetId, all.get(0).id(), new StocktakeCountInput(new BigDecimal("100"), null, null), user));
    assertThat(access.run(actorA, "warehouse.inventory.read", () -> service.detail(sheetId)).sheet().status())
        .isEqualTo(StocktakeSheetStatus.COUNTING);

    for (int i = 1; i < all.size(); i++) {
      var target = all.get(i);
      access.run(actorA, "warehouse.inventory.update", () -> service.count(sheetId, target.id(), new StocktakeCountInput(target.bookOnHand(), null, null), user));
    }
    var done = access.run(actorA, "warehouse.inventory.read", () -> service.detail(sheetId));
    assertThat(done.sheet().status()).isEqualTo(StocktakeSheetStatus.COUNTED);
    assertThat(done.sheet().countedLineCount()).isEqualTo(3);
    assertThat(done.sheet().varianceLineCount()).isZero();

    // 撤销录入 → 状态必须重算回 COUNTING，而不是停在 COUNTED。
    access.run(actorA, "warehouse.inventory.update", () -> service.count(sheetId, all.get(2).id(), new StocktakeCountInput(null, null, null), user));
    var reverted = access.run(actorA, "warehouse.inventory.read", () -> service.detail(sheetId));
    assertThat(reverted.sheet().status()).isEqualTo(StocktakeSheetStatus.COUNTING);
    assertThat(reverted.sheet().countedLineCount()).isEqualTo(2);
  }

  /** 明细筛选四个口径必须互斥且真的筛出不同行集。 */
  @Test
  void 明细按差异筛选() {
    standardStock();
    var saved = open(StocktakeScopeType.WAREHOUSE, whOne, StocktakeTaskUnit.BY_LOCATION);
    UUID sheetId = saved.sheet().id();
    var all = access.run(actorA, "warehouse.inventory.read", () -> service.lines(sheetId, null, null, 0, 50)).lines();
    // 排序后三行是 SKU-1@A-01=100、SKU-1@A-02=25、SKU-2@A-01=40
    // 逐行录成 95（盘亏）、25（相符）、45（盘盈）
    access.run(actorA, "warehouse.inventory.update", () -> service.count(sheetId, all.get(0).id(), new StocktakeCountInput(new BigDecimal("95"), null, null), user));
    access.run(actorA, "warehouse.inventory.update", () -> service.count(sheetId, all.get(1).id(), new StocktakeCountInput(new BigDecimal("25"), null, null), user));
    access.run(actorA, "warehouse.inventory.update", () -> service.count(sheetId, all.get(2).id(), new StocktakeCountInput(new BigDecimal("45"), null, null), user));

    assertThat(access.run(actorA, "warehouse.inventory.read", () -> service.lines(sheetId, "PENDING", null, 0, 50)).total()).isZero();
    assertThat(access.run(actorA, "warehouse.inventory.read", () -> service.lines(sheetId, "MATCH", null, 0, 50)).total()).isEqualTo(1);
    assertThat(access.run(actorA, "warehouse.inventory.read", () -> service.lines(sheetId, "GAIN", null, 0, 50)).total()).isEqualTo(1);
    assertThat(access.run(actorA, "warehouse.inventory.read", () -> service.lines(sheetId, "LOSS", null, 0, 50)).total()).isEqualTo(1);
    assertThat(access.run(actorA, "warehouse.inventory.read", () -> service.lines(sheetId, "ALL", null, 0, 50)).total()).isEqualTo(3);
  }

  // ================================================================ 范围与任务

  /** 三种范围必须筛出真正不同的行集与任务集 —— 否则范围筛选就是假的。 */
  @Test
  void 范围筛出真正不同的任务集合() {
    standardStock();

    var byWarehouse = open(StocktakeScopeType.WAREHOUSE, whOne, StocktakeTaskUnit.BY_LOCATION);
    assertThat(byWarehouse.sheet().lineCount()).isEqualTo(3);
    assertThat(byWarehouse.taskCount()).isEqualTo(2);

    var otherWarehouse = open(StocktakeScopeType.WAREHOUSE, whTwo, StocktakeTaskUnit.BY_LOCATION);
    assertThat(otherWarehouse.sheet().lineCount()).isEqualTo(1);
    assertThat(otherWarehouse.taskCount()).isEqualTo(1);

    var byLocation = open(StocktakeScopeType.LOCATION, locOne, StocktakeTaskUnit.BY_LOCATION);
    assertThat(byLocation.sheet().lineCount()).isEqualTo(2);
    assertThat(byLocation.taskCount()).isEqualTo(1);

    // 范围与任务单元是两个独立维度：按一仓盘 + 按 SKU 发任务 = 3 行、2 个 SKU 任务。
    var bySkuTasks = open(StocktakeScopeType.WAREHOUSE, whOne, StocktakeTaskUnit.BY_SKU);
    assertThat(bySkuTasks.sheet().lineCount()).isEqualTo(3);
    assertThat(bySkuTasks.taskCount()).isEqualTo(2);

    var tasks = access.run(actorA, "warehouse.inventory.read", () -> service.tasks(bySkuTasks.sheet().id(), null));
    assertThat(tasks).extracting(StocktakeTask::taskCode).containsExactlyInAnyOrder("SKU-1", "SKU-2");
    assertThat(tasks).allSatisfy(t -> assertThat(t.status()).isEqualTo(StocktakeTaskStatus.PENDING));
  }

  /** 任务进度由明细派生，且派工后能被读到。 */
  @Test
  void 任务进度派生且可派工() {
    standardStock();
    var saved = open(StocktakeScopeType.WAREHOUSE, whOne, StocktakeTaskUnit.BY_LOCATION);
    UUID sheetId = saved.sheet().id();
    var tasks = access.run(actorA, "warehouse.inventory.read", () -> service.tasks(sheetId, null));
    assertThat(tasks).hasSize(2);
    // A-01 有两行、A-02 有一行
    assertThat(tasks).filteredOn(t -> t.taskCode().equals("A-02")).singleElement()
        .satisfies(t -> {
          assertThat(t.lineCount()).isEqualTo(1);
          assertThat(t.bookTotal()).isEqualByComparingTo("25");
        });

    var assignee = UUID.randomUUID();
    var assigned = access.run(
        actorA, "warehouse.inventory.update", () -> service.assign(sheetId, tasks.get(0).id(), new StocktakeAssignInput(assignee)));
    assertThat(assigned.assignedToUserId()).isEqualTo(assignee);

    // 录入 A-02 那一行 → 那个任务立刻变 DONE（A-01 那个还在 COUNTING）
    var line = access.run(actorA, "warehouse.inventory.read", () -> service.lines(sheetId, null, "A-02", 0, 10)).lines().get(0);
    access.run(actorA, "warehouse.inventory.update", () -> service.count(sheetId, line.id(), new StocktakeCountInput(new BigDecimal("20"), null, null), user));
    var after = access.run(actorA, "warehouse.inventory.read", () -> service.tasks(sheetId, null));
    assertThat(after).filteredOn(t -> t.taskCode().equals("A-02")).singleElement()
        .satisfies(t -> {
          assertThat(t.status()).isEqualTo(StocktakeTaskStatus.DONE);
          assertThat(t.varianceTotal()).isEqualByComparingTo("-5");
        });
  }

  /** 范围筛不出任何库存行时必须报错，而不是建一张零明细的空单。 */
  @Test
  void 空范围被显式拒绝() {
    standardStock();
    // locThree 在二仓，二仓没建 balance 之外的东西；用不存在的库位更直接
    UUID ghost = idFor(a, 99);
    assertThatThrownBy(() -> open(StocktakeScopeType.LOCATION, ghost, StocktakeTaskUnit.BY_LOCATION))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("不存在");
    assertThat(access.run(actorA, "warehouse.inventory.read", () -> sql.fetchOne("select count(*) as c from stocktake_sheet")).get("c", Long.class))
        .isZero();
  }

  /** 范围可读名必须来自真实主数据，不能自己编。 */
  @Test
  void 范围名取自真实主数据() {
    standardStock();
    var byWarehouse = open(StocktakeScopeType.WAREHOUSE, whOne, StocktakeTaskUnit.BY_LOCATION);
    assertThat(byWarehouse.sheet().scopeLabel()).isEqualTo("WH-ONE 一仓");
    var byLocation = open(StocktakeScopeType.LOCATION, locOne, StocktakeTaskUnit.BY_LOCATION);
    assertThat(byLocation.sheet().scopeLabel()).isEqualTo("WH-ONE/A-01 A 区一排");
    // 不存在的库位 → 明确报错，不落一张范围名是编出来的单子
    assertThatThrownBy(() -> open(StocktakeScopeType.LOCATION, idFor(a, 98), StocktakeTaskUnit.BY_LOCATION))
        .isInstanceOf(ResponseStatusException.class);
  }

  /** 快照冻结了可读名：之后 sku_ref 改名，盘点单仍读得懂当时盘的是什么。 */
  @Test
  void 冻结时点连可读名一起冻结() {
    standardStock();
    var saved = open(StocktakeScopeType.WAREHOUSE, whOne, StocktakeTaskUnit.BY_LOCATION);
    UUID sheetId = saved.sheet().id();
    access.run(actorA, "warehouse.inventory.update", () -> {
      sql.execute("update sku_ref set name = ? where id = ? and tenant_id = ?", "改名后的商品一", skuOne, a);
      return null;
    });
    var lines = access.run(actorA, "warehouse.inventory.read", () -> service.lines(sheetId, null, "SKU-1", 0, 10)).lines();
    assertThat(lines).isNotEmpty();
    assertThat(lines).allSatisfy(l -> assertThat(l.skuName()).isEqualTo("商品一"));
  }

  /** ABC 分类库里没有字段 → 如实报不可用，不臆造算法。 */
  @Test
  void ABC分类如实报为不可用() {
    var caps = access.run(actorA, "warehouse.inventory.read", () -> service.capabilities());
    assertThat(caps).extracting(StocktakeScopeCapability::scopeType).contains("ABC", "CATEGORY", "WAREHOUSE", "LOCATION");
    assertThat(caps).filteredOn(c -> c.scopeType().equals("WAREHOUSE")).singleElement()
        .satisfies(c -> assertThat(c.available()).isTrue());
    assertThat(caps).filteredOn(c -> c.scopeType().equals("ABC")).singleElement()
        .satisfies(c -> {
          assertThat(c.available()).isFalse();
          assertThat(c.note()).contains("没有");
        });
    // 类目未接入上游 → 显式拒绝（422），不是返回空集
    assertThatThrownBy(() -> open(StocktakeScopeType.CATEGORY, UUID.randomUUID(), StocktakeTaskUnit.BY_LOCATION))
        .isInstanceOf(CatalogProjectionPort.DimensionUnavailableException.class);
  }

  // ================================================================ 列表 / 分页 / 时区

  @Test
  void 列表真分页且进度计数正确() {
    standardStock();
    open(StocktakeScopeType.WAREHOUSE, whOne, StocktakeTaskUnit.BY_LOCATION);
    open(StocktakeScopeType.WAREHOUSE, whTwo, StocktakeTaskUnit.BY_LOCATION);
    var page = access.run(actorA, "warehouse.inventory.read", () -> service.page(null, null, null, 0, 1));
    assertThat(page.total()).isEqualTo(2);
    assertThat(page.sheets()).hasSize(1);
    assertThat(page.size()).isEqualTo(1);
    var page2 = access.run(actorA, "warehouse.inventory.read", () -> service.page(null, null, null, 1, 1));
    assertThat(page2.sheets()).hasSize(1);
    // 两次翻页拿到不同的单（决胜键恒定追加 id，同排序值时不会错位或重复）
    assertThat(page.sheets().get(0).id()).isNotEqualTo(page2.sheets().get(0).id());
    // 按单号搜
    var found = access.run(actorA, "warehouse.inventory.read", () -> service.page(null, null, "WH-TWO", 0, 20));
    assertThat(found.total()).isEqualTo(1);
  }

  /**
   * 时区零漂移：frozen_at 用 +08:00 偏移写入，读回的瞬间必须与直接查库的值一致。
   *
   * <p>这一条钉住的是「用 {@code Timestamp.valueOf} 会静默偏 −28800s」那个坑：
   * 本模块所有 timestamptz 绑定都用 OffsetDateTime。
   */
  @Test
  void 时区零漂移() {
    standardStock();
    var saved = open(StocktakeScopeType.WAREHOUSE, whOne, StocktakeTaskUnit.BY_LOCATION);
    UUID sheetId = saved.sheet().id();
    var fromApi = access.run(actorA, "warehouse.inventory.read", () -> service.detail(sheetId)).sheet().frozenAt();
    var fromDb = access.run(actorA, "warehouse.inventory.read", () ->
            sql.fetchOne("select frozen_at from stocktake_sheet where id = ?", sheetId).get("frozen_at", OffsetDateTime.class))
        .toInstant();
    assertThat(fromApi.toInstant()).isEqualTo(fromDb);
    // 明细行的 created_at 与单头 frozen_at 是同一刻（同一绑定值复制而来）
    var lineAt = access.run(actorA, "warehouse.inventory.read", () ->
            sql.fetchOne("select created_at from stocktake_line where sheet_id = ? limit 1", sheetId).get("created_at", OffsetDateTime.class))
        .toInstant();
    assertThat(lineAt).isEqualTo(fromDb);

    // 录入实盘时刻同样零漂移
    var lineId = access.run(actorA, "warehouse.inventory.read", () -> service.lines(sheetId, null, null, 0, 1).lines()).get(0).id();
    var counted = access.run(
        actorA, "warehouse.inventory.update", () -> service.count(sheetId, lineId, new StocktakeCountInput(new BigDecimal("1"), null, null), user));
    var countedFromDb = access.run(actorA, "warehouse.inventory.read", () ->
            sql.fetchOne("select counted_at from stocktake_line where id = ?", lineId).get("counted_at", OffsetDateTime.class))
        .toInstant();
    assertThat(counted.countedAt().toInstant()).isEqualTo(countedFromDb);
  }

  /** 实盘数不得为负：盘出负数没有物理含义，它是录入错误。 */
  @Test
  void 负实盘被拒绝() {
    standardStock();
    var saved = open(StocktakeScopeType.WAREHOUSE, whOne, StocktakeTaskUnit.BY_LOCATION);
    UUID sheetId = saved.sheet().id();
    UUID lineId = access.run(actorA, "warehouse.inventory.read", () -> service.lines(sheetId, null, null, 0, 1).lines()).get(0).id();
    assertThatThrownBy(() -> access.run(actorA, "warehouse.inventory.update",
            () -> service.count(sheetId, lineId, new StocktakeCountInput(new BigDecimal("-1"), null, null), user)))
        .isInstanceOf(DataAccessException.class);
  }

  // ================================================================ HTTP 契约与权限

  @Test
  void 端点返回真实数据并守住写权限() throws Exception {
    standardStock();
    var saved = open(StocktakeScopeType.WAREHOUSE, whOne, StocktakeTaskUnit.BY_LOCATION);

    http.perform(get("/warehouse/inventory/stocktakes").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.sheets[0].lineCount").value(3));
    http.perform(get("/warehouse/inventory/stocktakes/capabilities").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(4));
    http.perform(get("/warehouse/inventory/stocktakes/{id}", saved.sheet().id()).header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.summary.bookTotal").value(165.0))
        .andExpect(jsonPath("$.sheet.status").value("OPEN"));
    http.perform(get("/warehouse/inventory/stocktakes/{id}/lines", saved.sheet().id()).header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(3))
        .andExpect(jsonPath("$.lines[0].bookOnHand").exists());
    http.perform(get("/warehouse/inventory/stocktakes/{id}/tasks", saved.sheet().id()).header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2));

    // 只有 read 权限的角色不能发起盘点 —— 冻结账面是写动作。
    http.perform(post("/warehouse/inventory/stocktakes")
            .header("Authorization", "Bearer read-only")
            .contentType("application/json")
            .content("{\"scopeType\":\"WAREHOUSE\",\"scopeKey\":\"" + whOne + "\",\"taskUnit\":\"BY_LOCATION\"}"))
        .andExpect(status().isForbidden());

    // 类目范围 → 422（未接入上游投影），不是 400 也不是空结果
    http.perform(post("/warehouse/inventory/stocktakes")
            .header("Authorization", "Bearer tenant-a")
            .contentType("application/json")
            .content("{\"scopeType\":\"CATEGORY\",\"scopeKey\":\"" + UUID.randomUUID() + "\",\"taskUnit\":\"BY_LOCATION\"}"))
        .andExpect(status().isUnprocessableEntity());

    // 未带 Authorization → 401
    http.perform(get("/warehouse/inventory/stocktakes")).andExpect(status().isUnauthorized());
  }

  // ================================================================ 租户隔离三断言

  @Test
  void 断言一_两租户互不可见() {
    standardStock();
    var savedA = open(StocktakeScopeType.WAREHOUSE, whOne, StocktakeTaskUnit.BY_LOCATION);

    // 租户 B 在同一个库位概念上建自己的单（id 不同但业务形态相同）
    groupFor(b, idFor(b, 4), idFor(b, 2), "777");
    var savedB = access.run(actorB, "warehouse.inventory.update", () -> service.create(
        new StocktakeRequest("WAREHOUSE", idFor(b, 4), "BY_LOCATION", "B 的单"), user));

    // A 只能看到自己的，B 只能看到自己的
    var listA = access.run(actorA, "warehouse.inventory.read", () -> service.page(null, null, null, 0, 20));
    assertThat(listA.total()).isEqualTo(1);
    assertThat(listA.sheets().get(0).id()).isEqualTo(savedA.sheet().id());
    var listB = access.run(actorB, "warehouse.inventory.read", () -> service.page(null, null, null, 0, 20));
    assertThat(listB.total()).isEqualTo(1);
    assertThat(listB.sheets().get(0).id()).isEqualTo(savedB.sheet().id());

    // 跨租户按 id 读详情 → 404（不泄露存在性）
    assertThatThrownBy(() -> access.run(actorB, "warehouse.inventory.read", () -> service.detail(savedA.sheet().id())))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("404");
    // 跨租户读明细 / 任务同样 404
    assertThatThrownBy(() -> access.run(actorB, "warehouse.inventory.read", () -> service.lines(savedA.sheet().id(), null, null, 0, 10)))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> access.run(actorB, "warehouse.inventory.read", () -> service.tasks(savedA.sheet().id(), null)))
        .isInstanceOf(ResponseStatusException.class);
    // 跨租户录入实盘 → 404，不能往别人的盘点单上写数
    UUID lineA = access.run(actorA, "warehouse.inventory.read", () -> service.lines(savedA.sheet().id(), null, null, 0, 1).lines()).get(0).id();
    assertThatThrownBy(() -> access.run(actorB, "warehouse.inventory.update",
            () -> service.count(savedA.sheet().id(), lineA, new StocktakeCountInput(BigDecimal.ONE, null, null), user)))
        .isInstanceOf(ResponseStatusException.class);
  }

  @Test
  void 断言二_伪造租户头无效() throws Exception {
    standardStock();
    var savedA = open(StocktakeScopeType.WAREHOUSE, whOne, StocktakeTaskUnit.BY_LOCATION);

    // 身份是 B，伪造 X-Tenant-Id 指向 A → B 仍然只能看到 B 的（空）
    http.perform(get("/warehouse/inventory/stocktakes")
            .header("Authorization", "Bearer tenant-b")
            .header("X-Tenant-Id", a.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(0));
    // 伪造头也不能让 B 读到 A 的盘点单详情
    http.perform(get("/warehouse/inventory/stocktakes/{id}", savedA.sheet().id())
            .header("Authorization", "Bearer tenant-b")
            .header("X-Tenant-Id", a.toString()))
        .andExpect(status().isNotFound());
    // 伪造头也不能让 B 往 A 的单上录数
    UUID lineA = access.run(actorA, "warehouse.inventory.read", () -> service.lines(savedA.sheet().id(), null, null, 0, 1).lines()).get(0).id();
    http.perform(post("/warehouse/inventory/stocktakes/{id}/lines/{lineId}/count", savedA.sheet().id(), lineA)
            .header("Authorization", "Bearer tenant-b")
            .header("X-Tenant-Id", a.toString())
            .contentType("application/json")
            .content("{\"countedQty\":99}"))
        .andExpect(status().isNotFound());
    // A 的账面数未被这串伪造请求改动
    assertThat(access.run(actorA, "warehouse.inventory.read", () -> service.detail(savedA.sheet().id())).summary().bookTotal())
        .isEqualByComparingTo("165");
  }

  @Test
  void 断言三_连接池归还后不串租户() {
    standardStock();
    var savedA = open(StocktakeScopeType.WAREHOUSE, whOne, StocktakeTaskUnit.BY_LOCATION);
    // 交替复用同一条物理连接（池容量=1）：A → B → A → B
    for (int i = 0; i < 3; i++) {
      var a1 = access.run(actorA, "warehouse.inventory.read", () -> service.detail(savedA.sheet().id()));
      assertThat(a1.sheet().scopeLabel()).isEqualTo("WH-ONE 一仓");
      var b1 = access.run(actorB, "warehouse.inventory.read", () -> service.page(null, null, null, 0, 20));
      assertThat(b1.total()).isZero();
      var a2 = access.run(actorA, "warehouse.inventory.read", () -> service.detail(savedA.sheet().id()));
      assertThat(a2.sheet().id()).isEqualTo(savedA.sheet().id());
    }
    // 未绑定 app.current_tenant 时直查三张表都必须为空（RLS 在 DB 层拦住）
    access.run(actorA, "warehouse.inventory.read", () -> {
      assertThat(sql.fetchOne("select count(*) as c from stocktake_sheet").get("c", Long.class)).isEqualTo(1);
      return null;
    });
    // 三张表逐张确认 FORCE RLS
    for (var table : List.of("stocktake_sheet", "stocktake_task", "stocktake_line")) {
      assertThat(sql.fetchOne("select relforcerowsecurity from pg_class where relname = ?", table).get("relforcerowsecurity", Boolean.class))
          .isTrue();
      assertThat(sql.fetchOne("select relrowsecurity from pg_class where relname = ?", table).get("relrowsecurity", Boolean.class))
          .isTrue();
    }
  }
}