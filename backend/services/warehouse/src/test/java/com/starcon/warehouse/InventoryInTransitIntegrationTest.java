package com.starcon.warehouse;

import static com.starcon.warehouse.InventoryInTransitModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
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
 * 7.5.1.2 在途库存：四类在途同一口径、**在途漏算不得发生**（与四个既有模块同一条 sum）、 逾期预警真算、分批到货与短少如实记录、时刻带时区偏移写回、乐观锁冲突，以及租户隔离三断言。
 *
 * <p>真实 PostgreSQL 16 + 强制 RLS，不使用任何内存替身。连接池固定为 1 条连接： 只有复用同一条连接，才能真正证明归还后不会串租户。
 *
 * <p>夹具写法与 {@link InventoryTransferIntegrationTest} 完全一致（同样的 Testcontainers、 同样的 fake identity
 * server、同样的按租户派生 UUID、同样的 TRUNCATE 清场）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class InventoryInTransitIntegrationTest {
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
  static final UUID whSh = idFor(a, 4);
  static final UUID whSz = idFor(a, 5);
  static final UUID qSellable = idFor(a, 6);
  static final UUID locSh1 = idFor(a, 8);
  static final UUID locSz1 = idFor(a, 9);
  static final UUID lotA = idFor(a, 10);

  static UUID idFor(UUID tenant, int ordinal) {
    return UUID.nameUUIDFromBytes(
        ("starcon-warehouse-intransit-test-" + tenant + "-" + ordinal).getBytes());
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
          "Bearer read-only",
          actor(user, a, null, List.of(grant("ALL", List.of("warehouse.inventory.read")))));
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

  @Autowired InventoryInTransitService service;
  @Autowired InventoryTransferService transfers;
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
    // TRUNCATE 不触发行级触发器（append-only 只挡 UPDATE/DELETE），以超级用户连接执行可绕过
    // FORCE RLS —— 只用于测试夹具重建，不给应用角色开这个口子。
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate in_transit_event, transfer_receipt_line, transfer_receipt, transfer_shipment,"
              + " transfer_order_line, transfer_order, inventory_transfer, stock_bucket,"
              + " warehouse_location, inventory_lot, inventory_entry, inventory_transaction,"
              + " inventory_balance, inventory_quality_status, warehouse, sku_ref,"
              + " legal_entity_ref restart identity cascade");
    }
    for (var tenant : List.of(a, b)) {
      access.run(
          actorFor(tenant),
          "warehouse.inventory.read",
          () -> {
            sql.execute(
                "insert into legal_entity_ref(id,tenant_id,code,name) values(?,?,?,?)",
                idFor(tenant, 1),
                tenant,
                "LE-1",
                "主体一");
            sql.execute(
                "insert into sku_ref(id,tenant_id,code,name) values(?,?,?,?)",
                idFor(tenant, 2),
                tenant,
                "SKU-1",
                "商品一");
            sql.execute(
                "insert into warehouse(id,tenant_id,code,name,country_code,timezone)"
                    + " values(?,?,?,?,?,?)",
                idFor(tenant, 4),
                tenant,
                "WH-SH",
                "上海仓",
                "CN",
                "Asia/Shanghai");
            sql.execute(
                "insert into warehouse(id,tenant_id,code,name,country_code,timezone)"
                    + " values(?,?,?,?,?,?)",
                idFor(tenant, 5),
                tenant,
                "WH-SZ",
                "深圳仓",
                "CN",
                "Asia/Shanghai");
            sql.execute(
                "insert into inventory_quality_status(id,tenant_id,code,name,role)"
                    + " values(?,?,?,?,?)",
                idFor(tenant, 6),
                tenant,
                "SELLABLE",
                "可售",
                "SELLABLE");
            return null;
          });
    }
    access.run(
        actorA,
        "warehouse.inventory.read",
        () -> {
          location(idFor(a, 8), whSh, "SH-A-01");
          location(idFor(a, 9), whSz, "SZ-A-01");
          sql.execute(
              "insert into inventory_lot(id,tenant_id,sku_id,lot_code,status)"
                  + " values(?,?,?,?,'ACTIVE')",
              lotA,
              a,
              skuA,
              "LOT-1");
          return null;
        });
  }

  void location(UUID id, UUID warehouse, String code) {
    sql.execute(
        "insert into warehouse_location(id,tenant_id,warehouse_id,code,name,type,pickable)"
            + " values(?,?,?,?,?,'STORAGE',true)",
        id,
        a,
        warehouse,
        code,
        "库位 " + code);
  }

  AccessService.Access actorFor(UUID tenant) {
    return tenant.equals(a) ? actorA : actorB;
  }

  // ================================================================ 助手

  <T> T asA(java.util.function.Supplier<T> work) {
    return access.run(actorA, "warehouse.inventory.read", work);
  }

  <T> T asB(java.util.function.Supplier<T> work) {
    return access.run(actorB, "warehouse.inventory.read", work);
  }

  /** 建一条余额行并在指定库位拆一个 bucket，**同一事务**写入。 */
  void seedBalance(UUID sku, UUID warehouse, UUID location, String onHand) {
    asA(
        () -> {
          var balanceId = UUID.randomUUID();
          sql.execute(
              "insert into inventory_balance(id,tenant_id,sku_id,warehouse_id,legal_entity_id,"
                  + "quality_status_id,on_hand,reserved) values(?,?,?,?,?,?,?,0)",
              balanceId,
              a,
              sku,
              warehouse,
              leA,
              qSellable,
              new BigDecimal(onHand));
          sql.execute(
              "insert into stock_bucket(id,tenant_id,legal_entity_id,sku_id,warehouse_id,"
                  + "location_id,lot_id,quality_status_id,balance_id,on_hand,reserved,"
                  + "last_movement_at) values(?,?,?,?,?,?,null,?,?,?,0,now())",
              UUID.randomUUID(),
              a,
              leA,
              sku,
              warehouse,
              location,
              qSellable,
              balanceId,
              new BigDecimal(onHand));
          return null;
        });
  }

  /**
   * 与 7.1.1.1 / 7.2.2.1 / 7.1.3.1 / 7.5.1.1 **完全相同**的那条在途口径。
   *
   * <p>本模块新增的头程/采购/FBA 在途若能被这条老口径读到，就证明它们真的进了同一张事实表， 而不是另建了一套 —— 「在途漏算导致重复补货」的根因就是有两套账。
   */
  BigDecimal sharedInTransit() {
    return asA(
        () ->
            sql.fetchOne(
                    "select coalesce(sum(quantity),0) from inventory_transfer"
                        + " where status='IN_TRANSIT'")
                .get(0, BigDecimal.class));
  }

  BigDecimal onHand(UUID sku, UUID warehouse) {
    return asA(
        () ->
            sql.fetchOne(
                    "select coalesce(sum(on_hand),0) from inventory_balance"
                        + " where sku_id=? and warehouse_id=?",
                    sku,
                    warehouse)
                .get(0, BigDecimal.class));
  }

  long countTransactions(String kind) {
    return asA(
        () ->
            sql.fetchOne("select count(*) from inventory_transaction where kind=?", kind)
                .get(0, Long.class));
  }

  BigDecimal entryDelta(String kind) {
    return asA(
        () ->
            sql.fetchOne(
                    "select sum(e.on_hand_delta) as d from inventory_entry e"
                        + " join inventory_transaction t on t.id=e.transaction_id"
                        + " where t.kind=?",
                    kind)
                .get("d", BigDecimal.class));
  }

  /**
   * 头程在途登记请求（默认值覆盖大多数用例）。
   *
   * <p>ETA 早于「此刻」时自动把发出时刻推到 ETA 之前：ETA 不能早于发出时刻（那说明录反了）， 但一条 10 天前发出、2 天前该到的批次正是逾期预警要有数据可算的那种货。
   */
  InTransitRegisterRequest firstMile(String qty, OffsetDateTime eta) {
    OffsetDateTime shippedAt =
        eta != null && eta.isBefore(OffsetDateTime.now()) ? eta.minusDays(5) : null;
    return new InTransitRegisterRequest(
        "FIRST_MILE",
        skuA,
        qSellable,
        leA,
        null,
        "供应商东莞工厂",
        whSz,
        "",
        null,
        new BigDecimal(qty),
        shippedAt,
        eta,
        "FIRST_MILE",
        "FM-2026-0001",
        "中远海运",
        "COSU-123");
  }

  InTransitSaved register(InTransitRegisterRequest request) {
    return asA(() -> service.register(a, request));
  }

  InTransitQuery query() {
    return new InTransitQuery(null, null, null, null, null, null, null, null, 0, 50);
  }

  InTransitRow legOf(UUID id) {
    return asA(
        () ->
            service
                .page(new InTransitQuery(null, null, null, null, null, null, null, null, 0, 50))
                .rows()
                .stream()
                .filter(r -> r.id().equals(id))
                .findFirst()
                .orElse(null));
  }

  // ================================================================ 登记

  @Test
  void 登记头程在途_不动在手且进入四个既有模块同一条在途口径() {
    var saved = register(firstMile("500", OffsetDateTime.now().plusDays(20)));

    assertThat(saved.transitKind()).isEqualTo("FIRST_MILE");
    assertThat(saved.status()).isEqualTo("IN_TRANSIT");
    assertThat(saved.outstandingQuantity()).isEqualByComparingTo("500");
    assertThat(saved.declaredQuantity()).isEqualByComparingTo("500");
    assertThat(saved.settledQuantity()).isEqualByComparingTo("0");

    // 货还没进我方账：登记动作**不能**动在手（在途不是库存）
    assertThat(onHand(skuA, whSz)).isEqualByComparingTo("0");
    assertThat(countTransactions("RECEIPT")).isZero();

    // 但它必须立刻出现在既有模块求在途的那条 SQL 里
    assertThat(sharedInTransit()).isEqualByComparingTo("500");

    // 起点不在本租户仓内 → 用可读名快照，不能凭空造一个仓
    var row = legOf(saved.id());
    assertThat(row).isNotNull();
    assertThat(row.fromWarehouseId()).isNull();
    assertThat(row.fromWarehouseName()).isEqualTo("供应商东莞工厂");
    assertThat(row.toWarehouseCode()).isEqualTo("WH-SZ");
    assertThat(row.trackingNo()).isEqualTo("COSU-123");
    assertThat(row.sourceSystem()).isEqualTo("FIRST_MILE");
  }

  @Test
  void 登记FBA发货在途_立刻从发货仓扣减在手() {
    seedBalance(skuA, whSh, locSh1, "100");
    var saved =
        register(
            new InTransitRegisterRequest(
                "FBA_OUTBOUND",
                skuA,
                qSellable,
                leA,
                whSh,
                "",
                null,
                "Amazon FBA ONT8",
                null,
                new BigDecimal("30"),
                null,
                OffsetDateTime.now().plusDays(10),
                "FBA",
                "FBA-SHP-9001",
                "亚马逊物流",
                "AMZ-9001"));

    // 货真的离仓了 → 在途登记时就该把手in扣掉，否则在手虚增、在途也虚增
    assertThat(onHand(skuA, whSh)).isEqualByComparingTo("70");
    assertThat(countTransactions("SHIPMENT")).isEqualTo(1);
    assertThat(entryDelta("SHIPMENT")).isEqualByComparingTo("-30");
    assertThat(sharedInTransit()).isEqualByComparingTo("30");
    assertThat(saved.outstandingQuantity()).isEqualByComparingTo("30");
  }

  @Test
  void 登记_调拨类型被拒_否则在途被记两次() {
    var request =
        new InTransitRegisterRequest(
            "TRANSFER",
            skuA,
            qSellable,
            leA,
            whSh,
            "",
            whSz,
            "",
            null,
            new BigDecimal("1"),
            null,
            null,
            "",
            "",
            "",
            "");
    assertThatThrownBy(() -> register(request))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("7.5.1.1");
    assertThat(sharedInTransit()).isEqualByComparingTo("0");
  }

  @Test
  void 登记_同一来源单据重复投递被拒_不双记在途() {
    register(firstMile("500", OffsetDateTime.now().plusDays(20)));
    assertThatThrownBy(() -> register(firstMile("500", OffsetDateTime.now().plusDays(20))))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    // 关键：不能变成 1000
    assertThat(sharedInTransit()).isEqualByComparingTo("500");
  }

  @Test
  void 登记_各类非法输入都被拒() {
    // 缺来源系统 / 来源单据
    assertThatThrownBy(
            () ->
                register(
                    new InTransitRegisterRequest(
                        "FIRST_MILE",
                        skuA,
                        qSellable,
                        leA,
                        null,
                        "某工厂",
                        whSz,
                        "",
                        null,
                        new BigDecimal("1"),
                        null,
                        null,
                        "",
                        "",
                        "",
                        "")))
        .hasMessageContaining("来源系统必填");
    // 数量非正 / 超过 4 位小数
    assertThatThrownBy(() -> register(firstMile("0", null))).hasMessageContaining("在途数量必须大于 0");
    assertThatThrownBy(() -> register(firstMile("1.00001", null))).hasMessageContaining("最多 4 位小数");
    // 起止站都为空 → DB 的 ck_transfer_endpoint 挡住
    assertThatThrownBy(
            () ->
                register(
                    new InTransitRegisterRequest(
                        "FIRST_MILE",
                        skuA,
                        qSellable,
                        leA,
                        null,
                        "",
                        null,
                        "",
                        null,
                        new BigDecimal("1"),
                        null,
                        null,
                        "P",
                        "R-1",
                        "",
                        "")))
        .isInstanceOf(Exception.class);
    // 非本租户仓的一端没有可读名
    assertThatThrownBy(
            () ->
                register(
                    new InTransitRegisterRequest(
                        "FIRST_MILE",
                        skuA,
                        qSellable,
                        leA,
                        null,
                        "",
                        whSz,
                        "",
                        null,
                        new BigDecimal("1"),
                        null,
                        null,
                        "P",
                        "R-2",
                        "",
                        "")))
        .hasMessageContaining("可读名称");
    // FBA 发货没有我方发货仓 → 无法扣减在手
    assertThatThrownBy(
            () ->
                register(
                    new InTransitRegisterRequest(
                        "FBA_OUTBOUND",
                        skuA,
                        qSellable,
                        leA,
                        null,
                        "某仓",
                        null,
                        "FBA 仓",
                        null,
                        new BigDecimal("1"),
                        null,
                        null,
                        "FBA",
                        "R-3",
                        "",
                        "")))
        .hasMessageContaining("发货仓");
    // 头程/采购没有我方收货仓 → 到货没有入库落点
    assertThatThrownBy(
            () ->
                register(
                    new InTransitRegisterRequest(
                        "FIRST_MILE",
                        skuA,
                        qSellable,
                        leA,
                        null,
                        "某工厂",
                        null,
                        "某平台仓",
                        null,
                        new BigDecimal("1"),
                        null,
                        null,
                        "P",
                        "R-4",
                        "",
                        "")))
        .hasMessageContaining("收货仓");
    // 同仓进出没有在途意义
    assertThatThrownBy(
            () ->
                register(
                    new InTransitRegisterRequest(
                        "FIRST_MILE",
                        skuA,
                        qSellable,
                        leA,
                        whSz,
                        "",
                        whSz,
                        "",
                        null,
                        new BigDecimal("1"),
                        null,
                        null,
                        "P",
                        "R-5",
                        "",
                        "")))
        .hasMessageContaining("同一个仓");
    // 批次不属于该 SKU
    assertThatThrownBy(
            () ->
                register(
                    new InTransitRegisterRequest(
                        "FIRST_MILE",
                        skuA,
                        qSellable,
                        leA,
                        null,
                        "工厂",
                        whSz,
                        "",
                        UUID.randomUUID(),
                        new BigDecimal("1"),
                        null,
                        null,
                        "P",
                        "R-6",
                        "",
                        "")))
        .hasMessageContaining("批次不存在");
    // SKU 未在本租户登记
    assertThatThrownBy(
            () ->
                register(
                    new InTransitRegisterRequest(
                        "FIRST_MILE",
                        idFor(b, 2),
                        qSellable,
                        leA,
                        null,
                        "工厂",
                        whSz,
                        "",
                        null,
                        new BigDecimal("1"),
                        null,
                        null,
                        "P",
                        "R-7",
                        "",
                        "")))
        .hasMessageContaining("SKU 未在本租户登记");
    // ETA 早于发出
    assertThatThrownBy(
            () ->
                register(
                    new InTransitRegisterRequest(
                        "FIRST_MILE",
                        skuA,
                        qSellable,
                        leA,
                        null,
                        "工厂",
                        whSz,
                        "",
                        null,
                        new BigDecimal("1"),
                        OffsetDateTime.now(),
                        OffsetDateTime.now().minusYears(1),
                        "P",
                        "R-8",
                        "",
                        "")))
        .hasMessageContaining("预计到达时间不能早于发出时间");
    // 未知在途类型 / 未知排序键
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.page(
                            new InTransitQuery(
                                "AIR_FREIGHT", null, null, null, null, null, null, null, 0, 50))))
        .hasMessageContaining("未知的在途类型");
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.page(
                            new InTransitQuery(
                                null, null, null, null, null, null, null, "NOPE", 0, 50))))
        .hasMessageContaining("不支持的排序键");
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.page(
                            new InTransitQuery(
                                null,
                                null,
                                null,
                                null,
                                List.of("VERY_LATE"),
                                null,
                                null,
                                null,
                                0,
                                50))))
        .hasMessageContaining("未知的逾期档位");
  }

  @Test
  void 登记_带批次与承运信息_清单读得出来() {
    var saved =
        register(
            new InTransitRegisterRequest(
                "PURCHASE",
                skuA,
                qSellable,
                leA,
                null,
                "1688 供应商",
                whSz,
                "",
                lotA,
                new BigDecimal("120"),
                null,
                OffsetDateTime.now().plusDays(35),
                "PURCHASING",
                "PO-2026-88",
                "顺丰国际",
                "SF-888"));
    var row = legOf(saved.id());
    assertThat(row.lotId()).isEqualTo(lotA);
    assertThat(row.lotCode()).isEqualTo("LOT-1");
    assertThat(row.carrierName()).isEqualTo("顺丰国际");
    assertThat(row.sourceReference()).isEqualTo("PO-2026-88");
    assertThat(row.skuCode()).isEqualTo("SKU-1");
    assertThat(row.qualityStatusCode()).isEqualTo("SELLABLE");
    assertThat(row.legalEntityCode()).isEqualTo("LE-1");
  }

  // ================================================================ 到货

  @Test
  void 到货_分批入库并结案_事件逐笔留痕() {
    var leg = register(firstMile("500", OffsetDateTime.now().plusDays(20)));

    var first =
        asA(
            () ->
                service.arrive(
                    leg.id(),
                    new InTransitArriveRequest(
                        0L, null, locSz1, lotA, "首批到港", new BigDecimal("200"))));
    assertThat(first.status()).isEqualTo("IN_TRANSIT");
    assertThat(first.outstandingQuantity()).isEqualByComparingTo("300");
    assertThat(first.settledQuantity()).isEqualByComparingTo("200");
    assertThat(onHand(skuA, whSz)).isEqualByComparingTo("200");
    assertThat(countTransactions("RECEIPT")).isEqualTo(1);

    var second =
        asA(
            () ->
                service.arrive(
                    leg.id(),
                    new InTransitArriveRequest(
                        1L, null, locSz1, null, "余量到齐", new BigDecimal("300"))));
    assertThat(second.status()).isEqualTo("RECEIVED");
    assertThat(second.outstandingQuantity()).isEqualByComparingTo("300");
    assertThat(second.settledQuantity()).isEqualByComparingTo("500");
    // 结案后原发以已了结量为准（quantity 因 CHECK(quantity&gt;0) 不能归零）
    assertThat(second.declaredQuantity()).isEqualByComparingTo("500");
    assertThat(onHand(skuA, whSz)).isEqualByComparingTo("500");
    assertThat(entryDelta("RECEIPT")).isEqualByComparingTo("500");
    // 全部了结后不再占在途 —— 这正是防重复补货的那一步
    assertThat(sharedInTransit()).isEqualByComparingTo("0");

    var detail = asA(() -> service.detail(leg.id()));
    assertThat(detail.events()).hasSize(2);
    assertThat(detail.events().get(0).quantity()).isEqualByComparingTo("200");
    assertThat(detail.events().get(0).locationCode()).isEqualTo("SZ-A-01");
    assertThat(detail.events().get(0).lotCode()).isEqualTo("LOT-1");
    assertThat(detail.events().get(1).quantity()).isEqualByComparingTo("300");
    assertThat(detail.metricNote()).contains("原发 = 已了结 + 未达");
  }

  @Test
  void 到货_数量超未达被拒_到货后仍在途的量不会被算负() {
    var leg = register(firstMile("500", null));
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.arrive(
                            leg.id(),
                            new InTransitArriveRequest(
                                0L, null, locSz1, null, "", new BigDecimal("501")))))
        .hasMessageContaining("超过该腿未达量");
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.arrive(
                            leg.id(),
                            new InTransitArriveRequest(
                                0L, null, locSz1, null, "", new BigDecimal("0")))))
        .hasMessageContaining("必须大于 0");
    // 入库类到货必须给库位
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.arrive(
                            leg.id(),
                            new InTransitArriveRequest(
                                0L, null, null, null, "", new BigDecimal("1")))))
        .hasMessageContaining("库位");
    // 库位不属于本腿的目的仓 → 货不能收到别的仓去
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.arrive(
                            leg.id(),
                            new InTransitArriveRequest(
                                0L, null, locSh1, null, "", new BigDecimal("1")))))
        .hasMessageContaining("不属于本腿的目的仓");
    // 已了结的腿不能再到货
    asA(
        () ->
            service.arrive(
                leg.id(),
                new InTransitArriveRequest(0L, null, locSz1, null, "", new BigDecimal("500"))));
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.arrive(
                            leg.id(),
                            new InTransitArriveRequest(
                                0L, null, locSz1, null, "", new BigDecimal("1")))))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(409));
  }

  @Test
  void 到货FBA_只了结在途不入库_平台仓不是本地库位() {
    seedBalance(skuA, whSh, locSh1, "100");
    var leg =
        register(
            new InTransitRegisterRequest(
                "FBA_OUTBOUND",
                skuA,
                qSellable,
                leA,
                whSh,
                "",
                null,
                "Amazon FBA ONT8",
                null,
                new BigDecimal("30"),
                null,
                OffsetDateTime.now().plusDays(10),
                "FBA",
                "FBA-SHP-1",
                "亚马逊物流",
                "AMZ-1"));

    // 平台侧没有本地库位，所以这次到货不给库位、也不入库
    var saved =
        asA(
            () ->
                service.arrive(
                    leg.id(),
                    new InTransitArriveRequest(
                        0L, null, null, null, "平台已签收", new BigDecimal("30"))));
    assertThat(saved.status()).isEqualTo("RECEIVED");
    assertThat(countTransactions("RECEIPT")).isZero();
    assertThat(onHand(skuA, whSh)).isEqualByComparingTo("70");
    assertThat(sharedInTransit()).isEqualByComparingTo("0");
  }

  /**
   * 时刻带时区偏移写回 —— 必须断言**写回来的值**，否则静默偏 −28800s 没人发现。
   *
   * <p>用 {@code Timestamp.valueOf(LocalDateTime…)} 绑定的典型症状就是这个：写进去的 瞬间被当成 UTC 解释，读回来差 8 小时，ETA
   * 算出来全错。
   */
  @Test
  void 预计到达与发出时刻_带时区偏移准确写回() {
    OffsetDateTime shippedAt = OffsetDateTime.parse("2026-09-30T08:30:00+08:00");
    OffsetDateTime eta = OffsetDateTime.parse("2026-10-20T17:45:00+08:00");
    var leg =
        register(
            new InTransitRegisterRequest(
                "FIRST_MILE",
                skuA,
                qSellable,
                leA,
                null,
                "工厂",
                whSz,
                "",
                null,
                new BigDecimal("10"),
                shippedAt,
                eta,
                "FIRST_MILE",
                "TZ-1",
                "",
                ""));

    var stored =
        asA(
            () ->
                sql.fetchOne(
                    "select shipped_at,expected_arrive_at from inventory_transfer where id=?",
                    leg.id()));
    OffsetDateTime backShip = stored.get("shipped_at", OffsetDateTime.class);
    OffsetDateTime backEta = stored.get("expected_arrive_at", OffsetDateTime.class);
    // 同一瞬间：不能偏 8 小时
    assertThat(backShip.toInstant()).isEqualTo(shippedAt.toInstant());
    assertThat(backEta.toInstant()).isEqualTo(eta.toInstant());
    // 墙钟时刻也要对（+08:00 偏移被保留，不是换成 UTC 墙钟）
    assertThat(backEta.withOffsetSameInstant(java.time.ZoneOffset.ofHours(8)).getHour())
        .isEqualTo(17);
    assertThat(backEta.getMinute()).isEqualTo(45);

    // 服务层回读同样带着偏移，不是被吞掉
    var saved = asA(() -> service.detail(leg.id())).leg();
    assertThat(saved.expectedArriveAt().toInstant()).isEqualTo(eta.toInstant());

    // 库里按 UTC 记的墙钟时间也对得上
    var utcText =
        asA(
            () ->
                sql.fetchOne(
                        "select to_char(expected_arrive_at at time zone 'UTC',"
                            + "'YYYY-MM-DD HH24:MI:SS') as t from inventory_transfer where id=?",
                        leg.id())
                    .get("t", String.class));
    assertThat(utcText).isEqualTo("2026-10-20 09:45:00");
  }

  // ================================================================ 差异

  @Test
  void 差异登记_原因必填且短少后未达量归零() {
    var leg = register(firstMile("500", OffsetDateTime.now().minusDays(9)));
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.discrepancy(
                            leg.id(),
                            new InTransitDiscrepancyRequest(
                                0L, null, new BigDecimal("30"), "", ""))))
        .hasMessageContaining("差异原因必填");
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.discrepancy(
                            leg.id(),
                            new InTransitDiscrepancyRequest(
                                0L, null, new BigDecimal("30"), "GONE", ""))))
        .hasMessageContaining("未知的差异原因");
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.discrepancy(
                            leg.id(),
                            new InTransitDiscrepancyRequest(
                                0L, null, new BigDecimal("501"), "LOST", ""))))
        .hasMessageContaining("超过该腿未达量");

    var partial =
        asA(
            () ->
                service.discrepancy(
                    leg.id(),
                    new InTransitDiscrepancyRequest(
                        0L, null, new BigDecimal("30"), "CUSTOMS_HELD", "清关扣关 30 件")));
    // 只短少一部分 → 剩下的仍在途，不能因为一次差异登记就把整腿抹掉
    assertThat(partial.status()).isEqualTo("IN_TRANSIT");
    assertThat(partial.settledQuantity()).isEqualByComparingTo("30");
    assertThat(partial.outstandingQuantity()).isEqualByComparingTo("470");
    assertThat(sharedInTransit()).isEqualByComparingTo("470");

    var saved =
        asA(
            () ->
                service.discrepancy(
                    leg.id(),
                    new InTransitDiscrepancyRequest(
                        1L, null, new BigDecimal("470"), "LOST", "余量确认丢失")));
    assertThat(saved.status()).isEqualTo("CANCELLED");
    assertThat(saved.settledQuantity()).isEqualByComparingTo("500");
    assertThat(saved.declaredQuantity()).isEqualByComparingTo("500");
    assertThat(saved.overdueLevel()).isEqualTo("ON_TIME");
    assertThat(sharedInTransit()).isEqualByComparingTo("0");

    var events = asA(() -> service.detail(leg.id())).events();
    assertThat(events).hasSize(2);
    assertThat(events.get(0).kind()).isEqualTo("SHORTAGE");
    assertThat(events.get(0).quantity()).isEqualByComparingTo("30");
    assertThat(events.get(0).locationCode()).isNull();
    // 短少如实留痕，不自动抹平也不悄悄挪回源仓
    assertThat(events.get(0).reason()).isEqualTo("CUSTOMS_HELD");
    assertThat(events.get(1).reason()).isEqualTo("LOST");
  }

  @Test
  void 乐观锁_版本不符一律409() {
    var leg = register(firstMile("500", null));
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.arrive(
                            leg.id(),
                            new InTransitArriveRequest(
                                7L, null, locSz1, null, "", new BigDecimal("1")))))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(409));
    // 缺版本也不放行（不做「读到什么就写什么」）
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.arrive(
                            leg.id(),
                            new InTransitArriveRequest(
                                null, null, locSz1, null, "", new BigDecimal("1")))))
        .hasMessageContaining("缺少 expectedVersion");
  }

  // ================================================================ 预警

  @Test
  void 到达预警_由ETA与此刻真算出来不是写死的标记() {
    var fresh =
        register(
            new InTransitRegisterRequest(
                "FIRST_MILE",
                skuA,
                qSellable,
                leA,
                null,
                "工厂A",
                whSz,
                "",
                null,
                new BigDecimal("10"),
                null,
                OffsetDateTime.now().plusDays(3),
                "P",
                "OK-1",
                "",
                ""));
    var mild =
        register(
            new InTransitRegisterRequest(
                "FIRST_MILE",
                skuA,
                qSellable,
                leA,
                null,
                "工厂B",
                whSz,
                "",
                null,
                new BigDecimal("20"),
                OffsetDateTime.now().minusDays(6),
                OffsetDateTime.now().minusHours(6),
                "P",
                "MILD-1",
                "",
                ""));
    var critical =
        register(
            new InTransitRegisterRequest(
                "FIRST_MILE",
                skuA,
                qSellable,
                leA,
                null,
                "工厂C",
                whSz,
                "",
                null,
                new BigDecimal("30"),
                OffsetDateTime.now().minusDays(14),
                OffsetDateTime.now().minusDays(9),
                "P",
                "CRIT-1",
                "",
                ""));
    // 历史行：有在途但没有登记 ETA —— 算不出逾期，必须如实显示而不是算成准点
    asA(
        () -> {
          sql.execute(
              "insert into inventory_transfer(id,tenant_id,sku_id,legal_entity_id,"
                  + "from_warehouse_id,to_warehouse_id,quality_status_id,quantity,status,shipped_at)"
                  + " values(?,?,?,?,?,?,?,?, 'IN_TRANSIT',now())",
              UUID.randomUUID(),
              a,
              skuA,
              leA,
              whSh,
              whSz,
              qSellable,
              new BigDecimal("40"));
          return null;
        });

    assertThat(legOf(fresh.id()).overdueLevel()).isEqualTo("ON_TIME");
    assertThat(legOf(fresh.id()).overdueHours()).isNull();
    assertThat(legOf(mild.id()).overdueLevel()).isEqualTo("MILD");
    assertThat(legOf(mild.id()).overdueHours()).isGreaterThanOrEqualTo(6);
    assertThat(legOf(critical.id()).overdueLevel()).isEqualTo("CRITICAL");
    assertThat(legOf(critical.id()).overdueHours()).isGreaterThanOrEqualTo(8L * 24);

    var alerts = asA(() -> service.alerts());
    assertThat(alerts.rows())
        .extracting(InTransitRow::id)
        .containsExactlyInAnyOrder(mild.id(), critical.id());
    assertThat(alerts.totalOverdueLegs()).isEqualTo(2);
    assertThat(alerts.overdueQuantity()).isEqualByComparingTo("50");
    assertThat(alerts.generatedAt()).isNotNull();
    assertThat(alerts.levelOptions()).isNotEmpty();

    var page = asA(() -> service.page(query()));
    assertThat(page.summary().overdueLegCount()).isEqualTo(2);
    assertThat(page.summary().noEtaLegCount()).isEqualTo(1);
    assertThat(page.summary().outstandingQuantity()).isEqualByComparingTo("100");

    // 只看逾期 → 未到 ETA 的那条被排除
    var only =
        asA(
            () ->
                service.page(
                    new InTransitQuery(null, null, null, null, null, true, null, null, 0, 50)));
    assertThat(only.rows())
        .extracting(InTransitRow::id)
        .containsExactlyInAnyOrder(mild.id(), critical.id());

    // 按档位筛选
    var criticalOnly =
        asA(
            () ->
                service.page(
                    new InTransitQuery(
                        null, null, null, null, List.of("CRITICAL"), null, null, null, 0, 50)));
    assertThat(criticalOnly.rows()).extracting(InTransitRow::id).containsExactly(critical.id());

    // ETA 一改，档位立刻跟着变（没有可写的「已逾期」标记列）
    asA(
        () -> {
          sql.execute(
              "update inventory_transfer set expected_arrive_at=cast(? as timestamptz) where id=?",
              OffsetDateTime.now().plusDays(2),
              critical.id());
          return null;
        });
    assertThat(legOf(critical.id()).overdueLevel()).isEqualTo("ON_TIME");
    assertThat(asA(() -> service.alerts()).totalOverdueLegs()).isEqualTo(1);
  }

  @Test
  void 逾期档位与阈值_分档边界由同一套规则给出() {
    var now = OffsetDateTime.now();
    assertThat(InTransitOverdueLevel.of(new BigDecimal("1"), null, now))
        .isEqualTo(InTransitOverdueLevel.NO_ETA);
    assertThat(InTransitOverdueLevel.of(new BigDecimal("1"), now.plusDays(1), now))
        .isEqualTo(InTransitOverdueLevel.ON_TIME);
    assertThat(InTransitOverdueLevel.of(new BigDecimal("1"), now.minusHours(1), now))
        .isEqualTo(InTransitOverdueLevel.MILD);
    assertThat(InTransitOverdueLevel.of(new BigDecimal("1"), now.minusDays(2), now))
        .isEqualTo(InTransitOverdueLevel.OVERDUE);
    assertThat(InTransitOverdueLevel.of(new BigDecimal("1"), now.minusDays(5), now))
        .isEqualTo(InTransitOverdueLevel.SEVERE);
    assertThat(InTransitOverdueLevel.of(new BigDecimal("1"), now.minusDays(30), now))
        .isEqualTo(InTransitOverdueLevel.CRITICAL);
    // 已了结的腿不报逾期（货都到了还进待办清单）
    assertThat(InTransitOverdueLevel.of(BigDecimal.ZERO, now.minusDays(30), now))
        .isEqualTo(InTransitOverdueLevel.ON_TIME);
  }

  /** 调拨在途的 ETA 从调拨单派生 —— 7.5.1.1 的既有单据立刻可算逾期， 且**不需要**改它的一行代码。 */
  @Test
  void 调拨在途_清单可见且ETA从调拨单派生() {
    seedBalance(skuA, whSh, locSh1, "100");
    var past = OffsetDateTime.now().minusDays(2);
    var order =
        asA(
            () ->
                transfers.create(
                    a,
                    new InventoryTransferModels.TransferOrderCreateRequest(
                        whSh,
                        whSz,
                        leA,
                        null,
                        past,
                        "补深圳仓",
                        "",
                        List.of(
                            new InventoryTransferModels.TransferOrderLineRequest(
                                skuA, qSellable, new BigDecimal("60"))))));
    asA(
        () ->
            transfers.confirm(
                order.id(), new InventoryTransferModels.TransferOrderActionRequest(0L, null)));
    var lineId = asA(() -> transfers.detail(order.id()).lines().get(0).id());
    asA(
        () ->
            transfers.ship(
                order.id(),
                new InventoryTransferModels.TransferShipRequest(
                    1L,
                    null,
                    "",
                    List.of(
                        new InventoryTransferModels.TransferShipLineRequest(
                            lineId, new BigDecimal("60"))))));

    var page = asA(() -> service.page(query()));
    assertThat(page.total()).isEqualTo(1);
    var row = page.rows().get(0);
    assertThat(row.transitKind()).isEqualTo("TRANSFER");
    assertThat(row.outstandingQuantity()).isEqualByComparingTo("60");
    // ETA 来自 transfer_order.expected_arrive_at（在途行自己那列是空的）
    assertThat(row.expectedArriveAt().toInstant()).isEqualTo(past.toInstant());
    assertThat(row.overdueLevel()).isEqualTo("OVERDUE");
    assertThat(row.overdueHours()).isGreaterThanOrEqualTo(2L * 24);
    assertThat(row.transferOrderId()).isEqualTo(order.id());
    assertThat(row.transferOrderCode()).isEqualTo(order.orderCode());
    assertThat(row.fromWarehouseCode()).isEqualTo("WH-SH");
    assertThat(row.toWarehouseCode()).isEqualTo("WH-SZ");
    // 调拨腿的已了结量恒为 0：分次收货把到达量拆成了独立的已收行
    assertThat(row.settledQuantity()).isEqualByComparingTo("0");
    assertThat(row.attribution()).contains("7.5.1.1");

    // 与四个既有模块同一条口径：调拨在途也在这里
    assertThat(sharedInTransit()).isEqualByComparingTo("60");
  }

  // ================================================================ 补货净额

  @Test
  void 补货净额_按目的仓与SKU聚合_平台仓方向不计入() {
    register(firstMile("500", OffsetDateTime.now().plusDays(20)));
    // 第二个 SKU 的头程，也去深圳仓
    var skuB = idFor(a, 11);
    asA(
        () -> {
          sql.execute(
              "insert into sku_ref(id,tenant_id,code,name) values(?,?,?,?)",
              skuB,
              a,
              "SKU-2",
              "商品二");
          return null;
        });
    asA(
        () ->
            service.register(
                a,
                new InTransitRegisterRequest(
                    "PURCHASE",
                    skuB,
                    qSellable,
                    leA,
                    null,
                    "1688 工厂",
                    whSz,
                    "",
                    null,
                    new BigDecimal("300"),
                    null,
                    OffsetDateTime.now().plusDays(40),
                    "P",
                    "PO-2",
                    "",
                    "")));
    // 发往平台仓的 FBA 在途：不属于任何本租户仓的入库缺口
    seedBalance(skuA, whSh, locSh1, "100");
    asA(
        () ->
            service.register(
                a,
                new InTransitRegisterRequest(
                    "FBA_OUTBOUND",
                    skuA,
                    qSellable,
                    leA,
                    whSh,
                    "",
                    null,
                    "Amazon FBA",
                    null,
                    new BigDecimal("30"),
                    null,
                    OffsetDateTime.now().plusDays(10),
                    "FBA",
                    "FBA-9",
                    "",
                    "")));

    var netting = asA(() -> service.netting(null, null, "QUANTITY", 0, 50));
    assertThat(netting.total()).isEqualTo(2);
    assertThat(netting.totalOutstandingQuantity()).isEqualByComparingTo("800");
    assertThat(netting.rows())
        .extracting(InTransitNettingRow::skuCode)
        .containsExactly("SKU-1", "SKU-2");
    var first = netting.rows().get(0);
    assertThat(first.toWarehouseCode()).isEqualTo("WH-SZ");
    assertThat(first.outstandingQuantity()).isEqualByComparingTo("500");
    assertThat(first.noEtaQuantity()).isEqualByComparingTo("0");
    assertThat(first.legCount()).isEqualTo(1);
    assertThat(first.latestExpectedArriveAt()).isNotNull();
    assertThat(netting.usageNote()).contains("不要再单独查一遍在途");

    // 按仓 / 按 SKU 过滤都真的生效
    assertThat(asA(() -> service.netting(whSz, null, "QUANTITY", 0, 50)).total()).isEqualTo(2);
    assertThat(asA(() -> service.netting(whSh, null, "QUANTITY", 0, 50)).total()).isZero();
    assertThat(asA(() -> service.netting(null, skuB, "QUANTITY", 0, 50)).total()).isEqualTo(1);
  }

  // ================================================================ 清单筛选

  @Test
  void 清单_筛选搜索排序分页与汇总不受分页影响() {
    register(firstMile("500", OffsetDateTime.now().plusDays(20)));
    asA(
        () ->
            service.register(
                a,
                new InTransitRegisterRequest(
                    "PURCHASE",
                    skuA,
                    qSellable,
                    leA,
                    null,
                    "1688 工厂",
                    whSz,
                    "",
                    null,
                    new BigDecimal("300"),
                    OffsetDateTime.now().minusDays(13),
                    OffsetDateTime.now().minusDays(8),
                    "P",
                    "PO-77",
                    "顺丰国际",
                    "SF-77")));
    seedBalance(skuA, whSh, locSh1, "100");
    asA(
        () ->
            service.register(
                a,
                new InTransitRegisterRequest(
                    "FBA_OUTBOUND",
                    skuA,
                    qSellable,
                    leA,
                    whSh,
                    "",
                    null,
                    "Amazon FBA",
                    null,
                    new BigDecimal("30"),
                    null,
                    OffsetDateTime.now().plusDays(5),
                    "FBA",
                    "FBA-5",
                    "",
                    "")));

    var all = asA(() -> service.page(query()));
    assertThat(all.total()).isEqualTo(3);
    assertThat(all.rows()).hasSize(3);
    assertThat(all.summary().outstandingQuantity()).isEqualByComparingTo("830");
    assertThat(all.summary().legCountFirstMile()).isEqualTo(1);
    assertThat(all.summary().outstandingFirstMile()).isEqualByComparingTo("500");
    assertThat(all.summary().legCountPurchase()).isEqualTo(1);
    assertThat(all.summary().outstandingPurchase()).isEqualByComparingTo("300");
    assertThat(all.summary().legCountFbaOutbound()).isEqualTo(1);
    assertThat(all.summary().outstandingFbaOutbound()).isEqualByComparingTo("30");
    assertThat(all.summary().legCount()).isEqualTo(3);
    assertThat(all.summary().outstandingTransfer()).isEqualByComparingTo("0");
    assertThat(all.summary().earliestExpectedArriveAt()).isNotNull();
    // 下拉词表四类全列（含 0 条的调拨在途）
    assertThat(all.kindOptions()).hasSize(4);
    assertThat(all.kindOptions())
        .extracting(InTransitOption::value)
        .containsExactly("TRANSFER", "FIRST_MILE", "PURCHASE", "FBA_OUTBOUND");
    assertThat(all.warehouseOptions()).hasSize(2);

    // 分页：合计不变，行数受限
    var firstPage =
        asA(
            () ->
                service.page(
                    new InTransitQuery(null, null, null, null, null, null, null, null, 0, 2)));
    assertThat(firstPage.rows()).hasSize(2);
    assertThat(firstPage.summary().outstandingQuantity()).isEqualByComparingTo("830");
    assertThat(firstPage.size()).isEqualTo(2);
    var secondPage =
        asA(
            () ->
                service.page(
                    new InTransitQuery(null, null, null, null, null, null, null, null, 1, 2)));
    assertThat(secondPage.rows()).hasSize(1);

    // 类型筛选 / 仓筛选 / SKU 筛选 / 关键词搜索
    assertThat(
            asA(() ->
                    service.page(
                        new InTransitQuery(
                            "FBA_OUTBOUND", null, null, null, null, null, null, null, 0, 50)))
                .total())
        .isEqualTo(1);
    assertThat(
            asA(() ->
                    service.page(
                        new InTransitQuery(null, null, whSz, null, null, null, null, null, 0, 50)))
                .total())
        .isEqualTo(2);
    assertThat(
            asA(() ->
                    service.page(
                        new InTransitQuery(null, whSh, null, null, null, null, null, null, 0, 50)))
                .total())
        .isEqualTo(1);
    assertThat(
            asA(() ->
                    service.page(
                        new InTransitQuery(null, null, null, skuA, null, null, null, null, 0, 50)))
                .total())
        .isEqualTo(3);
    assertThat(
            asA(() ->
                    service.page(
                        new InTransitQuery(
                            null, null, null, null, null, null, "SF-77", null, 0, 50)))
                .total())
        .isEqualTo(1);
    assertThat(
            asA(() ->
                    service.page(
                        new InTransitQuery(
                            null, null, null, null, null, null, "1688 工厂", null, 0, 50)))
                .total())
        .isEqualTo(1);
    assertThat(
            asA(() ->
                    service.page(
                        new InTransitQuery(
                            null, null, null, null, null, null, "不存在的东西", null, 0, 50)))
                .total())
        .isZero();

    // 排序：按未达量 / 按 SKU / 按逾期优先
    assertThat(
            asA(() ->
                    service.page(
                        new InTransitQuery(
                            null, null, null, null, null, null, null, "QUANTITY", 0, 50)))
                .rows()
                .get(0)
                .outstandingQuantity())
        .isEqualByComparingTo("500");
    assertThat(
            asA(() ->
                    service.page(
                        new InTransitQuery(
                            null, null, null, null, null, null, null, "OVERDUE", 0, 50)))
                .rows()
                .get(0)
                .overdueLevel())
        .isEqualTo("CRITICAL");
  }

  @Test
  void 详情_不存在返回404() {
    assertThatThrownBy(() -> asA(() -> service.detail(UUID.randomUUID())))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(404));
  }

  // ================================================================ HTTP 层

  @Test
  void 端点_读写权限与认证() throws Exception {
    // 未认证 401
    http.perform(get("/warehouse/inventory/in-transit")).andExpect(status().isUnauthorized());
    http.perform(get("/warehouse/inventory/in-transit/alerts"))
        .andExpect(status().isUnauthorized());
    http.perform(get("/warehouse/inventory/in-transit/netting"))
        .andExpect(status().isUnauthorized());
    // 只读角色不能登记
    http.perform(
            post("/warehouse/inventory/in-transit")
                .header("Authorization", "Bearer read-only")
                .contentType("application/json")
                .content("{\"transitKind\":\"FIRST_MILE\"}"))
        .andExpect(status().isForbidden());

    register(firstMile("500", OffsetDateTime.now().minusDays(2)));
    http.perform(get("/warehouse/inventory/in-transit").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.summary.outstandingQuantity").value(500.0))
        .andExpect(jsonPath("$.rows[0].transitKind").value("FIRST_MILE"))
        .andExpect(jsonPath("$.rows[0].overdueLevel").value("OVERDUE"))
        .andExpect(jsonPath("$.kindOptions.length()").value(4));
    http.perform(
            get("/warehouse/inventory/in-transit/alerts")
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalOverdueLegs").value(1));
    http.perform(
            get("/warehouse/inventory/in-transit/netting")
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.rows[0].outstandingQuantity").value(500.0));
    // 空请求体也要报可读错误，不能 NPE
    http.perform(
            post("/warehouse/inventory/in-transit")
                .header("Authorization", "Bearer tenant-a")
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isBadRequest());
  }

  // ================================================================ 租户隔离三断言

  @Test
  void 断言一_两租户互不可见() {
    var leg = register(firstMile("500", OffsetDateTime.now().minusDays(2)));

    // B 有自己的在途夹具
    access.run(
        actorB,
        "warehouse.inventory.read",
        () -> {
          sql.execute(
              "insert into inventory_transfer(id,tenant_id,sku_id,legal_entity_id,"
                  + "from_warehouse_id,to_warehouse_id,quality_status_id,quantity,status,shipped_at)"
                  + " values(?,?,?,?,?,?,?,?, 'IN_TRANSIT',now())",
              UUID.randomUUID(),
              b,
              idFor(b, 2),
              idFor(b, 1),
              idFor(b, 4),
              idFor(b, 5),
              idFor(b, 6),
              new BigDecimal("7"));
          return null;
        });

    var pageA = asA(() -> service.page(query()));
    assertThat(pageA.total()).isEqualTo(1);
    assertThat(pageA.rows()).extracting(InTransitRow::id).containsExactly(leg.id());
    assertThat(pageA.summary().outstandingQuantity()).isEqualByComparingTo("500");

    // B 只看得到自己那 7 件，看不到 A 的 500 件
    var pageB = asB(() -> service.page(query()));
    assertThat(pageB.total()).isEqualTo(1);
    assertThat(pageB.rows()).extracting(InTransitRow::id).doesNotContain(leg.id());
    assertThat(pageB.summary().outstandingQuantity()).isEqualByComparingTo("7");

    // 拿 A 的腿 id 给 B → 404（403 会泄露「这批货存在」）
    assertThatThrownBy(() -> asB(() -> service.detail(leg.id())))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(404));
    // 拿 A 的腿 id 给 B 登记到货 → 建不出痕迹
    assertThatThrownBy(
            () ->
                asB(
                    () ->
                        service.arrive(
                            leg.id(),
                            new InTransitArriveRequest(
                                0L, null, idFor(b, 9), null, "", new BigDecimal("1")))))
        .isInstanceOf(ResponseStatusException.class);
    // B 的净额里只有自己那 7 件，拿不到 A 的 500
    var nettingB = asB(() -> service.netting(null, null, "QUANTITY", 0, 50));
    assertThat(nettingB.total()).isEqualTo(1);
    assertThat(nettingB.totalOutstandingQuantity()).isEqualByComparingTo("7");
    // 按 A 的目的仓过滤：B 一行都取不到（那个仓不属于 B）
    assertThat(asB(() -> service.netting(whSz, null, "QUANTITY", 0, 50)).total()).isZero();
    // 同一 SKU / 批次 id 在两个租户各自成立，互不串
    assertThat(asA(() -> service.netting(whSz, skuA, "QUANTITY", 0, 50)).total()).isEqualTo(1);
    assertThat(asB(() -> service.netting(idFor(b, 5), null, "QUANTITY", 0, 50)).total())
        .isEqualTo(1);
  }

  @Test
  void 断言二_伪造租户头无效() throws Exception {
    register(firstMile("500", OffsetDateTime.now().minusDays(2)));

    // 身份是 B 但伪造 X-Tenant-Id 指向 A → 只能看到 B 自己的（这里是空）
    http.perform(
            get("/warehouse/inventory/in-transit")
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(0))
        .andExpect(jsonPath("$.summary.outstandingQuantity").value(0.0));
    // 反向：身份是 A 伪造 B 的头也拿不到 B 的数据
    http.perform(
            get("/warehouse/inventory/in-transit")
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1));
    // 预警与补货净额同样不受伪造头影响
    http.perform(
            get("/warehouse/inventory/in-transit/alerts")
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalOverdueLegs").value(0));
    http.perform(
            get("/warehouse/inventory/in-transit/netting")
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(0));
  }

  @Test
  void 断言三_连接池归还后不串租户() {
    var leg = register(firstMile("500", OffsetDateTime.now().minusDays(2)));
    access.run(
        actorB,
        "warehouse.inventory.read",
        () -> {
          sql.execute(
              "insert into inventory_transfer(id,tenant_id,sku_id,legal_entity_id,"
                  + "from_warehouse_id,to_warehouse_id,quality_status_id,quantity,status,shipped_at)"
                  + " values(?,?,?,?,?,?,?,?, 'IN_TRANSIT',now())",
              UUID.randomUUID(),
              b,
              idFor(b, 2),
              idFor(b, 1),
              idFor(b, 4),
              idFor(b, 5),
              idFor(b, 6),
              new BigDecimal("7"));
          return null;
        });

    // 池大小为 1，同一条连接被反复借还：交替访问两个租户 20 轮，
    // 列表、详情、预警、净额与老口径五条路径都不串租户。
    for (int i = 0; i < 20; i++) {
      var pa = asA(() -> service.page(query()));
      var pb = asB(() -> service.page(query()));
      assertThat(pa.rows()).extracting(InTransitRow::id).containsExactly(leg.id());
      assertThat(pb.rows()).extracting(InTransitRow::id).doesNotContain(leg.id());
      assertThat(pa.summary().outstandingQuantity()).isEqualByComparingTo("500");
      assertThat(pb.summary().outstandingQuantity()).isEqualByComparingTo("7");
      assertThat(asA(() -> sharedInTransitRaw())).isEqualByComparingTo("500");
      assertThat(
              asB(
                  () ->
                      sql.fetchOne(
                              "select coalesce(sum(quantity),0) from inventory_transfer"
                                  + " where status='IN_TRANSIT'")
                          .get(0, BigDecimal.class)))
          .isEqualByComparingTo("7");
      assertThat(asA(() -> service.alerts()).totalOverdueLegs()).isEqualTo(1);
      assertThat(asB(() -> service.alerts()).totalOverdueLegs()).isZero();
      assertThat(asA(() -> service.netting(null, null, "QUANTITY", 0, 50)).total()).isEqualTo(1);
      // B 也有自己那条 7 件在途，净额里当然有 1 行 —— 但它绝不是 A 的那条
      var nettingB = asB(() -> service.netting(null, null, "QUANTITY", 0, 50));
      assertThat(nettingB.total()).isEqualTo(1);
      assertThat(nettingB.totalOutstandingQuantity()).isEqualByComparingTo("7");
      assertThatThrownBy(() -> asB(() -> service.detail(leg.id())))
          .isInstanceOf(ResponseStatusException.class);
      assertThat(asA(() -> service.detail(leg.id())).leg().id()).isEqualTo(leg.id());
    }
  }

  /** 与 {@link #sharedInTransit()} 同一条 SQL，但在调用方自己的租户事务里跑。 */
  BigDecimal sharedInTransitRaw() {
    return sql.fetchOne(
            "select coalesce(sum(quantity),0) from inventory_transfer where status='IN_TRANSIT'")
        .get(0, BigDecimal.class);
  }

  @Test
  void 每张表都强制行级安全() {
    for (var table : List.of("in_transit_event", "inventory_transfer")) {
      var forced =
          sql.fetchOne(
                  "select relforcerowsecurity from pg_class"
                      + " where relname=? and relnamespace='public'::regnamespace",
                  table)
              .get(0, Boolean.class);
      assertThat(forced).as(table + " 必须 FORCE ROW LEVEL SECURITY").isTrue();
      var enabled =
          sql.fetchOne(
                  "select relrowsecurity from pg_class"
                      + " where relname=? and relnamespace='public'::regnamespace",
                  table)
              .get(0, Boolean.class);
      assertThat(enabled).as(table + " 必须 ENABLE ROW LEVEL SECURITY").isTrue();
    }
  }

  /** 在途事实仍然只有一张表：外部腿与调拨腿落在同一张 inventory_transfer 上。 */
  @Test
  void 在途只有一个事实表_外部腿与调拨腿同表() {
    register(firstMile("500", OffsetDateTime.now().plusDays(5)));
    var kinds =
        asA(
            () ->
                sql
                    .fetch(
                        "select distinct transit_kind from inventory_transfer order by transit_kind")
                    .stream()
                    .map(r -> r.get(0, String.class))
                    .toList());
    assertThat(kinds).containsExactly("FIRST_MILE");
    // 没有第二张在途表
    var tables =
        asA(
            () ->
                sql.fetch(
                    "select table_name from information_schema.tables"
                        + " where table_schema='public' and table_name like '%transit%'"
                        + " or (table_schema='public' and table_name='inventory_transfer')"));
    assertThat(tables.stream().map(r -> r.get(0, String.class)).toList())
        .containsExactlyInAnyOrder("inventory_transfer", "in_transit_event");
  }
}
