package com.starcon.warehouse;

import static com.starcon.warehouse.InventoryTransferModels.*;
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
 * 7.5.1.1 调拨单：完整状态机、**在途不丢账**、分次收货、差异如实记录、
 * 乐观锁冲突，以及租户隔离三断言。
 *
 * <p>真实 PostgreSQL 16 + 强制 RLS，不使用任何内存替身。连接池固定为 1 条连接：
 * 只有复用同一条连接，才能真正证明归还后不会串租户。
 *
 * <p>夹具写法与 {@link InventoryLotIntegrationTest} 完全一致（同样的 Testcontainers、
 * 同样的 fake identity server、同样的按租户派生 UUID、同样的 TRUNCATE 清场）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class InventoryTransferIntegrationTest {
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

  static UUID idFor(UUID tenant, int ordinal) {
    return UUID.nameUUIDFromBytes(("starcon-warehouse-transfer-test-" + tenant + "-" + ordinal).getBytes());
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

  @Autowired InventoryTransferService service;
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
          "truncate transfer_receipt_line, transfer_receipt, transfer_shipment,"
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
                idFor(tenant, 1), tenant, "LE-1", "主体一");
            sql.execute(
                "insert into sku_ref(id,tenant_id,code,name) values(?,?,?,?)",
                idFor(tenant, 2), tenant, "SKU-1", "商品一");
            sql.execute(
                "insert into warehouse(id,tenant_id,code,name,country_code,timezone)"
                    + " values(?,?,?,?,?,?)",
                idFor(tenant, 4), tenant, "WH-SH", "上海仓", "CN", "Asia/Shanghai");
            sql.execute(
                "insert into warehouse(id,tenant_id,code,name,country_code,timezone)"
                    + " values(?,?,?,?,?,?)",
                idFor(tenant, 5), tenant, "WH-SZ", "深圳仓", "CN", "Asia/Shanghai");
            sql.execute(
                "insert into inventory_quality_status(id,tenant_id,code,name,role)"
                    + " values(?,?,?,?,?)",
                idFor(tenant, 6), tenant, "SELLABLE", "可售", "SELLABLE");
            return null;
          });
    }
    access.run(
        actorA,
        "warehouse.inventory.read",
        () -> {
          location(idFor(a, 8), whSh, "SH-A-01");
          location(idFor(a, 9), whSz, "SZ-A-01");
          return null;
        });
  }

  void location(UUID id, UUID warehouse, String code) {
    sql.execute(
        "insert into warehouse_location(id,tenant_id,warehouse_id,code,name,type,pickable)"
            + " values(?,?,?,?,?,'STORAGE',true)",
        id, a, warehouse, code, "库位 " + code);
  }

  AccessService.Access actorFor(UUID tenant) {
    return tenant.equals(a) ? actorA : actorB;
  }

  // ================================================================ 夹具与查询助手

  /**
   * 建一条余额行并在指定库位拆一个 bucket，**同一事务**写入。
   *
   * <p>必须同一事务：V2 的守恒是延迟约束触发器，提交前才对平。
   */
  void seedBalance(UUID sku, UUID warehouse, UUID location, String onHand) {
    access.run(
        actorA,
        "warehouse.inventory.read",
        () -> {
          var balanceId = UUID.randomUUID();
          sql.execute(
              "insert into inventory_balance(id,tenant_id,sku_id,warehouse_id,legal_entity_id,"
                  + "quality_status_id,on_hand,reserved) values(?,?,?,?,?,?,?,0)",
              balanceId, a, sku, warehouse, leA, qSellable, new BigDecimal(onHand));
          sql.execute(
              "insert into stock_bucket(id,tenant_id,legal_entity_id,sku_id,warehouse_id,"
                  + "location_id,lot_id,quality_status_id,balance_id,on_hand,reserved,"
                  + "last_movement_at) values(?,?,?,?,?,?,null,?,?,?,0,now())",
              UUID.randomUUID(), a, leA, sku, warehouse, location, qSellable, balanceId,
              new BigDecimal(onHand));
          return null;
        });
  }

  /**
   * 与 7.1.1.1 总览 / 7.1.2.2 核对 / 7.2.2.1 可售量 / 7.1.3.1 多仓分布**完全相同**的那条在途口径。
   *
   * <p>包在 {@code asA} 里：FORCE RLS 下没有绑定 {@code app.current_tenant} 的查询会一行都看不到，
   * 断言会变成「一切都等于 0」而看起来通过。
   */
  BigDecimal sharedInTransit() {
    return asA(() -> rawInTransit());
  }

  /** 同一条在途口径，但**不**绑定租户上下文：调用方自己决定跑在哪个租户的事务里。 */
  BigDecimal rawInTransit() {
    return sql.fetchOne(
            "select coalesce(sum(quantity),0) from inventory_transfer where status='IN_TRANSIT'")
        .get(0, BigDecimal.class);
  }

  BigDecimal onHand(UUID sku, UUID warehouse) {
    return asA(
        () ->
            sql.fetchOne(
                    "select coalesce(sum(on_hand),0) from inventory_balance"
                        + " where sku_id=? and warehouse_id=?",
                    sku, warehouse)
                .get(0, BigDecimal.class));
  }

  List<org.jooq.Record> legsOf(UUID lineId) {
    return asA(
        () -> sql.fetch("select status,quantity,received_at from inventory_transfer where line_id=?",
            lineId));
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

  TransferOrderSaved createOrder(String quantity) {
    return asA(
        () ->
            service.create(
                a,
                new TransferOrderCreateRequest(
                    whSh,
                    whSz,
                    leA,
                    null,
                    null,
                    "补深圳仓",
                    "",
                    List.of(
                        new TransferOrderLineRequest(skuA, qSellable, new BigDecimal(quantity))))));
  }

  UUID lineIdOf(UUID orderId) {
    return asA(() -> service.detail(orderId)).lines().get(0).id();
  }

  // ================================================================ 完整链路

  @Test
  void 完整链路_草稿到已收且在途全程不丢账() {
    seedBalance(skuA, whSh, locSh1, "100");

    var draft = createOrder("60");
    assertThat(draft.status()).isEqualTo("DRAFT");
    assertThat(draft.version()).isEqualTo(0);
    // 建单不动库存：草稿只是意图
    assertThat(onHand(skuA, whSh)).isEqualByComparingTo("100");
    assertThat(onHand(skuA, whSz)).isEqualByComparingTo("0");

    var confirmed = asA(() -> service.confirm(draft.id(), new TransferOrderActionRequest(0L, null)));
    assertThat(confirmed.status()).isEqualTo("PENDING_SHIPMENT");

    var line = lineIdOf(draft.id());
    var shipped =
        asA(
            () ->
                service.ship(
                    draft.id(),
                    new TransferShipRequest(
                        1L,
                        null,
                        "",
                        List.of(new TransferShipLineRequest(line, new BigDecimal("60"))))));
    assertThat(shipped.status()).isEqualTo("IN_TRANSIT");
    assertThat(shipped.inTransitQuantity()).isEqualByComparingTo("60");

    // **调拨不是瞬时**：发出只减源仓，目的仓纹丝不动
    assertThat(onHand(skuA, whSh)).isEqualByComparingTo("40");
    assertThat(onHand(skuA, whSz)).isEqualByComparingTo("0");
    // 与四个既有模块同一条口径的在途：60 既没消失也没被算进任何一边的在手
    assertThat(sharedInTransit()).isEqualByComparingTo("60");

    var received =
        asA(
            () ->
                service.receive(
                    draft.id(),
                    new TransferReceiveRequest(
                        2L,
                        null,
                        locSz1,
                        null,
                        "",
                        List.of(new TransferReceiveLineRequest(line, new BigDecimal("60"))))));
    assertThat(received.status()).isEqualTo("RECEIVED");
    assertThat(onHand(skuA, whSz)).isEqualByComparingTo("60");
    assertThat(onHand(skuA, whSh)).isEqualByComparingTo("40");
    assertThat(sharedInTransit()).isEqualByComparingTo("0");
    assertThat(received.inTransitQuantity()).isEqualByComparingTo("0");

    // 守恒式：已发出 = 已实收 + 已确认差异 + 仍在途
    assertThat(received.shippedQuantity())
        .isEqualByComparingTo(received.receivedQuantity().add(received.discrepancyQuantity())
            .add(received.inTransitQuantity()));

    var detail = asA(() -> service.detail(draft.id()));
    assertThat(detail.shipments()).hasSize(1);
    assertThat(detail.receipts()).hasSize(1);
    assertThat(detail.receipts().get(0).kind()).isEqualTo("RECEIVED");
    assertThat(detail.receipts().get(0).lines().get(0).quantity()).isEqualByComparingTo("60");
  }

  @Test
  void 分次收货_逐笔转入且在途逐次递减() {
    seedBalance(skuA, whSh, locSh1, "100");
    var draft = createOrder("60");
    asA(() -> service.confirm(draft.id(), new TransferOrderActionRequest(0L, null)));
    var line = lineIdOf(draft.id());
    asA(
        () ->
            service.ship(
                draft.id(),
                new TransferShipRequest(
                    1L, null, "",
                    List.of(new TransferShipLineRequest(line, new BigDecimal("60"))))));

    var first =
        asA(
            () ->
                service.receive(
                    draft.id(),
                    new TransferReceiveRequest(
                        2L, null, locSz1, null, "第一批",
                        List.of(new TransferReceiveLineRequest(line, new BigDecimal("25"))))));
    assertThat(first.status()).isEqualTo("PARTIALLY_RECEIVED");
    assertThat(onHand(skuA, whSz)).isEqualByComparingTo("25");
    assertThat(first.inTransitQuantity()).isEqualByComparingTo("35");
    // 关键：既有模块的求和在途口径不能因为「分次」而虚高
    assertThat(sharedInTransit()).isEqualByComparingTo("35");

    var second =
        asA(
            () ->
                service.receive(
                    draft.id(),
                    new TransferReceiveRequest(
                        3L, null, locSz1, null, "第二批",
                        List.of(new TransferReceiveLineRequest(line, new BigDecimal("20"))))));
    assertThat(second.inTransitQuantity()).isEqualByComparingTo("15");
    assertThat(sharedInTransit()).isEqualByComparingTo("15");

    var third =
        asA(
            () ->
                service.receive(
                    draft.id(),
                    new TransferReceiveRequest(
                        4L, null, locSz1, null, "第三批",
                        List.of(new TransferReceiveLineRequest(line, new BigDecimal("15"))))));
    assertThat(third.status()).isEqualTo("RECEIVED");
    assertThat(sharedInTransit()).isEqualByComparingTo("0");
    assertThat(onHand(skuA, whSz)).isEqualByComparingTo("60");

    // 每一笔收货都留痕，能按时间读出这趟货分几批到的
    var detail = asA(() -> service.detail(draft.id()));
    assertThat(detail.receipts()).hasSize(3);
    assertThat(detail.receipts()).hasSize(3);
    assertThat(detail.receipts().get(0).lines().get(0).quantity()).isEqualByComparingTo("25");
    assertThat(detail.receipts().get(1).lines().get(0).quantity()).isEqualByComparingTo("20");
    assertThat(detail.receipts().get(2).lines().get(0).quantity()).isEqualByComparingTo("15");

    // 分次收货把 60 拆成三段已收（25/20/15），全部转 RECEIVED；
    // 三段数量之和仍等于发出量 —— 拆行只是形状变化，账没变
    var legs = legsOf(line);
    assertThat(legs).hasSize(3);
    assertThat(legs).allSatisfy(l -> assertThat(l.get("status", String.class)).isEqualTo("RECEIVED"));
    assertThat(legs).allSatisfy(
        l -> assertThat(l.get("received_at", OffsetDateTime.class)).isNotNull());
    assertThat(legs).allSatisfy(
        l -> assertThat(l.get("quantity", BigDecimal.class)).isPositive());
    assertThat(
            legs.stream()
                .map(l -> l.get("quantity", BigDecimal.class))
                .reduce(BigDecimal.ZERO, BigDecimal::add))
        .isEqualByComparingTo("60");
    // 一行都不留在途
    assertThat(
            legs.stream()
                .filter(l -> l.get("status", String.class).equals("IN_TRANSIT"))
                .count())
        .isZero();
  }

  @Test
  void 部分收货把在途拆成已收段与未到段且求和仍精确() {
    seedBalance(skuA, whSh, locSh1, "100");
    var draft = createOrder("60");
    asA(() -> service.confirm(draft.id(), new TransferOrderActionRequest(0L, null)));
    var line = lineIdOf(draft.id());
    asA(
        () ->
            service.ship(
                draft.id(),
                new TransferShipRequest(
                    1L, null, "",
                    List.of(new TransferShipLineRequest(line, new BigDecimal("60"))))));
    asA(
        () ->
            service.receive(
                draft.id(),
                new TransferReceiveRequest(
                    2L, null, locSz1, null, "先到一批",
                    List.of(new TransferReceiveLineRequest(line, new BigDecimal("25"))))));

    var legs = legsOf(line);
    assertThat(legs).hasSize(2);
    // 未到段仍在途、已收段转 RECEIVED，两条都不为 0（V1 的 CHECK）
    assertThat(legs).allSatisfy(l -> assertThat(l.get("quantity", BigDecimal.class)).isPositive());
    var transit =
        asA(
            () ->
                sql.fetchOne(
                        "select coalesce(sum(quantity),0) from inventory_transfer"
                            + " where line_id=? and status='IN_TRANSIT'",
                        line)
                    .get(0, BigDecimal.class));
    assertThat(transit).isEqualByComparingTo("35");
    // 拆行之后，发出的那一趟仍然是 60 —— 发出量从 append-only 分录派生，不受拆行影响
    var detail = asA(() -> service.detail(draft.id()));
    assertThat(detail.shipments().get(0).lines().get(0).quantity())
        .isEqualByComparingTo("60");
  }

  @Test
  void 收超过在途数量直接拒绝() {
    seedBalance(skuA, whSh, locSh1, "100");
    var draft = createOrder("60");
    asA(() -> service.confirm(draft.id(), new TransferOrderActionRequest(0L, null)));
    var line = lineIdOf(draft.id());
    asA(
        () ->
            service.ship(
                draft.id(),
                new TransferShipRequest(
                    1L, null, "",
                    List.of(new TransferShipLineRequest(line, new BigDecimal("60"))))));

    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.receive(
                            draft.id(),
                            new TransferReceiveRequest(
                                2L, null, locSz1, null, "",
                                List.of(
                                    new TransferReceiveLineRequest(line, new BigDecimal("61")))))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("超过该行仍在途");
  }

  // ================================================================ 差异

  @Test
  void 差异如实记录_短少不抹平也不进任何一边的在手() {
    seedBalance(skuA, whSh, locSh1, "100");
    var draft = createOrder("30");
    asA(() -> service.confirm(draft.id(), new TransferOrderActionRequest(0L, null)));
    var line = lineIdOf(draft.id());
    asA(
        () ->
            service.ship(
                draft.id(),
                new TransferShipRequest(
                    1L, null, "",
                    List.of(new TransferShipLineRequest(line, new BigDecimal("30"))))));
    asA(
        () ->
            service.receive(
                draft.id(),
                new TransferReceiveRequest(
                    2L, null, locSz1, null, "",
                    List.of(new TransferReceiveLineRequest(line, new BigDecimal("20"))))));

    // 差异原因必填：没给就不许结案，否则短少的货会被无声抹掉
    assertThatThrownBy(() -> asA(() -> service.close(draft.id(), new TransferCloseRequest(3L, null, null))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("必须给出差异原因");

    var closed = asA(() -> service.close(draft.id(), new TransferCloseRequest(3L, "LOST", "途中丢失")));
    assertThat(closed.status()).isEqualTo("DISCREPANCY");
    assertThat(closed.discrepancyQuantity()).isEqualByComparingTo("10");
    // 短少的 10 件既没被挪回源仓、也没凭空进目的仓 —— 如实挂成差异
    assertThat(onHand(skuA, whSh)).isEqualByComparingTo("70");
    assertThat(onHand(skuA, whSz)).isEqualByComparingTo("20");
    // 在途归零：这批货已经不在路上了，不许永远挂在途清单上
    assertThat(closed.inTransitQuantity()).isEqualByComparingTo("0");
    assertThat(sharedInTransit()).isEqualByComparingTo("0");

    var detail = asA(() -> service.detail(draft.id()));
    var shortage =
        detail.receipts().stream().filter(r -> r.kind().equals("SHORTAGE")).findFirst().orElseThrow();
    assertThat(shortage.lines().get(0).quantity()).isEqualByComparingTo("10");
    assertThat(shortage.reason()).isEqualTo("LOST");
    // 守恒式仍然成立：30 = 20 + 10 + 0
    assertThat(detail.order().shippedQuantity())
        .isEqualByComparingTo(
            detail.order().receivedQuantity()
                .add(detail.order().discrepancyQuantity())
                .add(detail.order().inTransitQuantity()));
  }

  // ================================================================ 乐观锁

  @Test
  void 乐观锁_版本不符拒绝且不静默覆盖() {
    seedBalance(skuA, whSh, locSh1, "100");
    var draft = createOrder("60");
    asA(() -> service.confirm(draft.id(), new TransferOrderActionRequest(0L, null)));
    var line = lineIdOf(draft.id());

    // 用错版本发货 → 409
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.ship(
                            draft.id(),
                            new TransferShipRequest(
                                0L, null, "",
                                List.of(
                                    new TransferShipLineRequest(line, new BigDecimal("60")))))))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            e ->
                assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(409));

    // 缺 expectedVersion 也要拒绝（不能当成「不校验」）
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.ship(
                            draft.id(),
                            new TransferShipRequest(
                                null, null, "",
                                List.of(
                                    new TransferShipLineRequest(line, new BigDecimal("60")))))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("expectedVersion");

    // 失败的请求没有留下任何库存改动
    assertThat(onHand(skuA, whSh)).isEqualByComparingTo("100");
    assertThat(sharedInTransit()).isEqualByComparingTo("0");

    // 正确的版本才走得通
    var shipped =
        asA(
            () ->
                service.ship(
                    draft.id(),
                    new TransferShipRequest(
                        1L, null, "",
                        List.of(new TransferShipLineRequest(line, new BigDecimal("60"))))));
    assertThat(shipped.version()).isEqualTo(2);
    // 再用同一个旧版本发货 → 409
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.ship(
                            draft.id(),
                            new TransferShipRequest(
                                1L, null, "",
                                List.of(
                                    new TransferShipLineRequest(line, new BigDecimal("1")))))))
        .isInstanceOf(ResponseStatusException.class);
  }

  @Test
  void 可用量不足拒绝发货且不产生在途() {
    seedBalance(skuA, whSh, locSh1, "30");
    var draft = createOrder("60");
    asA(() -> service.confirm(draft.id(), new TransferOrderActionRequest(0L, null)));
    var line = lineIdOf(draft.id());
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.ship(
                            draft.id(),
                            new TransferShipRequest(
                                1L, null, "",
                                List.of(
                                    new TransferShipLineRequest(line, new BigDecimal("60")))))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("可用量不足");
    assertThat(onHand(skuA, whSh)).isEqualByComparingTo("30");
    assertThat(sharedInTransit()).isEqualByComparingTo("0");
  }

  // ================================================================ 状态机

  @Test
  void 状态机_拒绝非法流转() {
    seedBalance(skuA, whSh, locSh1, "100");
    var draft = createOrder("60");
    // 草稿不能直接发货（必须先确认成待发）
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.ship(
                            draft.id(),
                            new TransferShipRequest(
                                0L, null, "",
                                List.of(
                                    new TransferShipLineRequest(
                                        lineIdOf(draft.id()), new BigDecimal("60")))))))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            e ->
                assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(409));
    // 草稿不能收货
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.receive(
                            draft.id(),
                            new TransferReceiveRequest(
                                0L, null, locSz1, null, "",
                                List.of(
                                    new TransferReceiveLineRequest(
                                        lineIdOf(draft.id()), new BigDecimal("10")))))))
        .isInstanceOf(ResponseStatusException.class);
    // 草稿可以改单
    var edited =
        asA(
            () ->
                service.update(
                    draft.id(),
                    new TransferOrderUpdateRequest(
                        0L, null, null, "改成补货", "",
                        List.of(
                            new TransferOrderLineRequest(skuA, qSellable, new BigDecimal("80"))))));
    assertThat(edited.requestedQuantity()).isEqualByComparingTo("80");
  }

  @Test
  void 状态机_已收之后不能再收() {
    seedBalance(skuA, whSh, locSh1, "100");
    var draft = createOrder("20");
    asA(() -> service.confirm(draft.id(), new TransferOrderActionRequest(0L, null)));
    var line = lineIdOf(draft.id());
    asA(
        () ->
            service.ship(
                draft.id(),
                new TransferShipRequest(
                    1L, null, "",
                    List.of(new TransferShipLineRequest(line, new BigDecimal("20"))))));
    asA(
        () ->
            service.receive(
                draft.id(),
                new TransferReceiveRequest(
                    2L, null, locSz1, null, "",
                    List.of(new TransferReceiveLineRequest(line, new BigDecimal("20"))))));
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.receive(
                            draft.id(),
                            new TransferReceiveRequest(
                                3L, null, locSz1, null, "",
                                List.of(
                                    new TransferReceiveLineRequest(line, new BigDecimal("1")))))))
        .isInstanceOf(ResponseStatusException.class);
  }

  // ================================================================ 分录与跨模块对齐

  @Test
  void 分录符号正确_出库为负入库为正() {
    seedBalance(skuA, whSh, locSh1, "100");
    var draft = createOrder("30");
    asA(() -> service.confirm(draft.id(), new TransferOrderActionRequest(0L, null)));
    var line = lineIdOf(draft.id());
    asA(
        () ->
            service.ship(
                draft.id(),
                new TransferShipRequest(
                    1L, null, "",
                    List.of(new TransferShipLineRequest(line, new BigDecimal("30"))))));
    asA(
        () ->
            service.receive(
                draft.id(),
                new TransferReceiveRequest(
                    2L, null, locSz1, null, "",
                    List.of(new TransferReceiveLineRequest(line, new BigDecimal("30"))))));

    assertThat(entryDelta("TRANSFER_OUT")).isEqualByComparingTo("-30");
    assertThat(entryDelta("TRANSFER_IN")).isEqualByComparingTo("30");
    // 分录逐条可追到调拨单（ref_type/ref_id 是幂等锚点）
    assertThat(countTransactions("TRANSFER_OUT")).isEqualTo(1);
    assertThat(countTransactions("TRANSFER_IN")).isEqualTo(1);
  }

  @Test
  void 与多仓分布对齐_调拨出库不是销售出库() {
    seedBalance(skuA, whSh, locSh1, "100");
    var draft = createOrder("30");
    asA(() -> service.confirm(draft.id(), new TransferOrderActionRequest(0L, null)));
    var line = lineIdOf(draft.id());
    asA(
        () ->
            service.ship(
                draft.id(),
                new TransferShipRequest(
                    1L, null, "",
                    List.of(new TransferShipLineRequest(line, new BigDecimal("30"))))));

    // 7.1.3.1 多仓分布刻意只按 kind='SHIPMENT' 算周转天数的出库速度，
    // 把 TRANSFER_OUT 排除掉（调拨决策要用那一列算依据，不能被自己的出库弄坏）。
    // 本模块因此绝不能把调拨记成 SHIPMENT。
    assertThat(countTransactions("SHIPMENT")).isEqualTo(0);
    assertThat(countTransactions("TRANSFER_OUT")).isEqualTo(1);
  }

  // ================================================================ 时间口径

  @Test
  void 预计时间带时区偏移往返零漂移() {
    seedBalance(skuA, whSh, locSh1, "100");
    var shipAt = OffsetDateTime.parse("2026-10-20T09:30:00+08:00");
    var arriveAt = OffsetDateTime.parse("2026-10-22T18:00:00+08:00");
    var order =
        asA(
            () ->
                service.create(
                    a,
                    new TransferOrderCreateRequest(
                        whSh, whSz, leA, shipAt, arriveAt, "补货", "",
                        List.of(
                            new TransferOrderLineRequest(skuA, qSellable, new BigDecimal("10"))))));
    var readBack =
        asA(
                () ->
                    sql.fetchOne(
                            "select expected_ship_at,expected_arrive_at from transfer_order"
                                + " where id=?",
                            order.id())
                        .get("expected_ship_at", OffsetDateTime.class))
            .toInstant();
    assertThat(readBack).isEqualTo(shipAt.toInstant());
    // 走 Timestamp.valueOf(LocalDateTime) 会静默偏 −28800s，这里断言偏移本身
    var offset =
        asA(
            () ->
                sql.fetchOne(
                        "select expected_ship_at from transfer_order where id=?", order.id())
                    .get("expected_ship_at", OffsetDateTime.class)
                    .getOffset()
                    .getTotalSeconds());
    assertThat(offset).isEqualTo(8 * 3600);
    var row = asA(() -> service.detail(order.id())).order();
    assertThat(row.expectedShipAt().toInstant()).isEqualTo(shipAt.toInstant());
    assertThat(row.expectedArriveAt().toInstant()).isEqualTo(arriveAt.toInstant());

    // 预计到达早于预计发出直接拒
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.create(
                            a,
                            new TransferOrderCreateRequest(
                                whSh, whSz, leA, arriveAt, shipAt, "", "",
                                List.of(
                                    new TransferOrderLineRequest(
                                        skuA, qSellable, new BigDecimal("10")))))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不能早于");
  }

  // ================================================================ 列表与校验

  @Test
  void 列表_筛选排序分页与汇总不受分页影响() {
    seedBalance(skuA, whSh, locSh1, "1000");
    var first = createOrder("60");
    asA(() -> service.confirm(first.id(), new TransferOrderActionRequest(0L, null)));
    createOrder("80");

    var page1 = asA(() -> service.page(new TransferOrderQuery(null, null, null, null, null, null, 0, 1)));
    assertThat(page1.total()).isEqualTo(2);
    assertThat(page1.rows()).hasSize(1);
    // 合计是全量的：分页只切行，不切数
    assertThat(page1.summary().orderCount()).isEqualTo(2);
    assertThat(page1.summary().requestedQuantity()).isEqualByComparingTo("140");
    assertThat(page1.summary().draftCount()).isEqualTo(1);
    assertThat(page1.summary().pendingShipmentCount()).isEqualTo(1);
    // 六态下拉全给，哪怕某个态现在 0 单
    assertThat(page1.statusOptions()).hasSize(6);
    assertThat(page1.warehouseOptions()).hasSize(2);

    var byStatus =
        asA(() -> service.page(new TransferOrderQuery(null, null, null, List.of("DRAFT"), null, null, 0, 50)));
    assertThat(byStatus.total()).isEqualTo(1);
    // DRAFT 那一行是第二张单（第一张已被确认成待发），不是被确认过的那张
    assertThat(byStatus.rows().get(0).orderCode()).isNotEqualTo(first.orderCode());
    assertThat(byStatus.summary().draftCount()).isEqualTo(1);
    var byPending =
        asA(() -> service.page(new TransferOrderQuery(null, null, null, List.of("PENDING_SHIPMENT"), null, null, 0, 50)));
    assertThat(byPending.rows().get(0).orderCode()).isEqualTo(first.orderCode());

    var byWarehouse =
        asA(() -> service.page(new TransferOrderQuery(whSz, null, null, null, null, null, 0, 50)));
    assertThat(byWarehouse.total()).isZero();
    var bySku =
        asA(() -> service.page(new TransferOrderQuery(null, null, skuA, null, null, null, 0, 50)));
    assertThat(bySku.total()).isEqualTo(2);
    var byKeyword =
        asA(() -> service.page(new TransferOrderQuery(null, null, null, null, "SKU-1", null, 0, 50)));
    assertThat(byKeyword.total()).isEqualTo(2);

    // 非法排序键与非法状态当场 400，不静默忽略
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.page(new TransferOrderQuery(null, null, null, null, null, "DROP", 0, 50))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.page(
                            new TransferOrderQuery(null, null, null, List.of("NOPE"), null, null, 0, 50))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void 建单校验_同仓跨租户空明细都拒绝() {
    seedBalance(skuA, whSh, locSh1, "100");
    assertThatThrownBy(
            () -> asA(() -> service.create(a, new TransferOrderCreateRequest(whSh, whSh, leA, null, null, "", "", List.of(new TransferOrderLineRequest(skuA, qSellable, BigDecimal.ONE))))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不能是同一个仓");
    // 目标仓塞另一个租户的仓 → 建不出来
    assertThatThrownBy(
            () -> asA(() -> service.create(a, new TransferOrderCreateRequest(whSh, idFor(b, 5), leA, null, null, "", "", List.of(new TransferOrderLineRequest(skuA, qSellable, BigDecimal.ONE))))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不属于本租户");
    // 空明细
    assertThatThrownBy(
            () -> asA(() -> service.create(a, new TransferOrderCreateRequest(whSh, whSz, leA, null, null, "", "", List.of()))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("至少要有一条明细");
    // 数量为 0
    assertThatThrownBy(
            () -> asA(() -> service.create(a, new TransferOrderCreateRequest(whSh, whSz, leA, null, null, "", "", List.of(new TransferOrderLineRequest(skuA, qSellable, BigDecimal.ZERO))))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("必须大于 0");
    // 收货库位必须属于目的仓
    var draft = createOrder("10");
    asA(() -> service.confirm(draft.id(), new TransferOrderActionRequest(0L, null)));
    var line = lineIdOf(draft.id());
    asA(() -> service.ship(draft.id(), new TransferShipRequest(1L, null, "", List.of(new TransferShipLineRequest(line, new BigDecimal("10"))))));
    assertThatThrownBy(
            () -> asA(() -> service.receive(draft.id(), new TransferReceiveRequest(2L, null, locSh1, null, "", List.of(new TransferReceiveLineRequest(line, new BigDecimal("10")))))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不属于目标仓");
  }

  // ================================================================ 租户隔离三断言

  @Test
  void 断言一_两租户互不可见() {
    seedBalance(skuA, whSh, locSh1, "100");
    var orderA = createOrder("60");
    asA(() -> service.confirm(orderA.id(), new TransferOrderActionRequest(0L, null)));
    asA(
        () ->
            service.ship(
                orderA.id(),
                new TransferShipRequest(
                    1L, null, "",
                    List.of(
                        new TransferShipLineRequest(
                            lineIdOf(orderA.id()), new BigDecimal("60"))))));

    // B 有自己的 SKU 与在途夹具，但对 B 不可见 A 的单
    access.run(
        actorB,
        "warehouse.inventory.read",
        () -> {
          sql.execute(
              "insert into inventory_transfer(id,tenant_id,sku_id,legal_entity_id,"
                  + "from_warehouse_id,to_warehouse_id,quality_status_id,quantity,status,shipped_at)"
                  + " values(?,?,?,?,?,?,?,?, 'IN_TRANSIT',now())",
              UUID.randomUUID(), b, idFor(b, 2), idFor(b, 1), idFor(b, 4), idFor(b, 5),
              idFor(b, 6), new BigDecimal("7"));
          return null;
        });

    var pageA = asA(() -> service.page(new TransferOrderQuery(null, null, null, null, null, null, 0, 50)));
    assertThat(pageA.total()).isEqualTo(1);
    assertThat(pageA.summary().inTransitQuantity()).isEqualByComparingTo("60");

    var pageB = asB(() -> service.page(new TransferOrderQuery(null, null, null, null, null, null, 0, 50)));
    assertThat(pageB.total()).isZero();
    assertThat(pageB.summary().orderCount()).isZero();

    // 拿 A 的单 id 给 B → 404（403 会泄露「这张单存在」）
    assertThatThrownBy(() -> asB(() -> service.detail(orderA.id())))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            e ->
                assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(404));
    // 拿 A 的明细行 id 给 B 收货 → 建不出痕迹
    assertThatThrownBy(
            () ->
                asB(
                    () ->
                        service.receive(
                            orderA.id(),
                            new TransferReceiveRequest(
                                0L, null, locSz1, null, "",
                                List.of(
                                    new TransferReceiveLineRequest(
                                        lineIdOf(orderA.id()), new BigDecimal("1")))))))
        .isInstanceOf(ResponseStatusException.class);
  }

  @Test
  void 断言二_伪造租户头无效() throws Exception {
    seedBalance(skuA, whSh, locSh1, "100");
    var orderA = createOrder("60");

    // 身份是 B 但伪造 X-Tenant-Id 指向 A → 只能看到 B 自己的（这里是空）
    http.perform(get("/warehouse/inventory/transfers")
            .header("Authorization", "Bearer tenant-b")
            .header("X-Tenant-Id", a.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(0))
        .andExpect(jsonPath("$.summary.orderCount").value(0));
    // 反向：身份是 A 伪造 B 的头也拿不到 B 的数据
    http.perform(get("/warehouse/inventory/transfers")
            .header("Authorization", "Bearer tenant-a")
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.rows[0].orderCode").value(orderA.orderCode()));
    // 详情端点同样不受伪造头影响
    http.perform(get("/warehouse/inventory/transfers/" + orderA.id())
            .header("Authorization", "Bearer tenant-b")
            .header("X-Tenant-Id", a.toString()))
        .andExpect(status().isNotFound());
    // 未认证 401
    http.perform(get("/warehouse/inventory/transfers")).andExpect(status().isUnauthorized());
    // 写端点要 update 权限：只有 read 的角色不能建单
    http.perform(post("/warehouse/inventory/transfers")
            .header("Authorization", "Bearer read-only")
            .contentType("application/json")
            .content("{\"fromWarehouseId\":\"" + whSh + "\"}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void 断言三_连接池归还后不串租户() {
    seedBalance(skuA, whSh, locSh1, "100");
    var orderA = createOrder("60");
    asA(() -> service.confirm(orderA.id(), new TransferOrderActionRequest(0L, null)));
    asA(
        () ->
            service.ship(
                orderA.id(),
                new TransferShipRequest(
                    1L, null, "",
                    List.of(
                        new TransferShipLineRequest(
                            lineIdOf(orderA.id()), new BigDecimal("60"))))));
    access.run(
        actorB,
        "warehouse.inventory.read",
        () -> {
          sql.execute(
              "insert into inventory_transfer(id,tenant_id,sku_id,legal_entity_id,"
                  + "from_warehouse_id,to_warehouse_id,quality_status_id,quantity,status,shipped_at)"
                  + " values(?,?,?,?,?,?,?,?, 'IN_TRANSIT',now())",
              UUID.randomUUID(), b, idFor(b, 2), idFor(b, 1), idFor(b, 4), idFor(b, 5),
              idFor(b, 6), new BigDecimal("7"));
          return null;
        });

    // 池大小为 1，同一条连接被反复借还：交替访问两个租户 20 轮，
    // 每一轮都校验列表、详情与在途口径三条路径都不串租户。
    // A 有 60 件在途（来自它自己的调拨单），B 有 7 件在途（裸夹具，没有调拨单）——
    // 两个数既不能互相污染，也不能因为求和口径不同就各说各话。
    for (int i = 0; i < 20; i++) {
      var pa = asA(() -> service.page(new TransferOrderQuery(null, null, null, null, null, null, 0, 50)));
      var pb = asB(() -> service.page(new TransferOrderQuery(null, null, null, null, null, null, 0, 50)));
      assertThat(pa.rows()).extracting(TransferOrderRow::orderCode).containsExactly(orderA.orderCode());
      assertThat(pb.rows()).isEmpty();
      assertThat(pa.summary().inTransitQuantity()).isEqualByComparingTo("60");
      assertThat(pb.summary().inTransitQuantity()).isEqualByComparingTo("0");
      assertThat(asA(() -> rawInTransit())).isEqualByComparingTo("60");
      assertThat(asB(() -> rawInTransit())).isEqualByComparingTo("7");
      assertThatThrownBy(() -> asB(() -> service.detail(orderA.id())))
          .isInstanceOf(ResponseStatusException.class);
      assertThat(asA(() -> service.detail(orderA.id())).order().orderCode())
          .isEqualTo(orderA.orderCode());
    }
  }

  @Test
  void 每张表都强制行级安全() {
    for (var table :
        List.of(
            "transfer_order",
            "transfer_order_line",
            "transfer_shipment",
            "transfer_receipt",
            "transfer_receipt_line")) {
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

  // ================================================================ 端点层

  @Test
  void 端点_完整流转与响应形状() throws Exception {
    seedBalance(skuA, whSh, locSh1, "100");
    var created =
        http.perform(post("/warehouse/inventory/transfers")
                .header("Authorization", "Bearer tenant-a")
                .contentType("application/json")
                .content(
                    "{\"fromWarehouseId\":\"" + whSh + "\",\"toWarehouseId\":\"" + whSz + "\","
                        + "\"legalEntityId\":\"" + leA + "\",\"reason\":\"补深圳仓\","
                        + "\"expectedShipAt\":\"2026-10-20T09:30:00+08:00\","
                        + "\"lines\":[{\"skuId\":\"" + skuA + "\",\"qualityStatusId\":\""
                        + qSellable + "\",\"quantity\":60}]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("DRAFT"))
            .andExpect(jsonPath("$.version").value(0))
            .andExpect(jsonPath("$.requestedQuantity").value(60))
            .andReturn()
            .getResponse()
            .getContentAsString();
    var orderId = com.jayway.jsonpath.JsonPath.read(created, "$.id");

    var lineId =
        com.jayway.jsonpath.JsonPath.read(
            http.perform(get("/warehouse/inventory/transfers/" + orderId)
                    .header("Authorization", "Bearer tenant-a"))
                .andExpect(status().isOk())
                // 守恒口径说明必须随响应返回，界面上要显示
                .andExpect(jsonPath("$.conservationNote").exists())
                .andExpect(jsonPath("$.lines[0].skuCode").value("SKU-1"))
                .andReturn()
                .getResponse()
                .getContentAsString(),
            "$.lines[0].id");

    http.perform(post("/warehouse/inventory/transfers/" + orderId + "/confirm")
            .header("Authorization", "Bearer tenant-a")
            .contentType("application/json")
            .content("{\"expectedVersion\":0}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("PENDING_SHIPMENT"));

    // 版本错 → 409
    http.perform(post("/warehouse/inventory/transfers/" + orderId + "/ship")
            .header("Authorization", "Bearer tenant-a")
            .contentType("application/json")
            .content(
                "{\"expectedVersion\":0,\"lines\":[{\"lineId\":\"" + lineId + "\",\"quantity\":60}]}"))
        .andExpect(status().isConflict());

    http.perform(post("/warehouse/inventory/transfers/" + orderId + "/ship")
            .header("Authorization", "Bearer tenant-a")
            .contentType("application/json")
            .content(
                "{\"expectedVersion\":1,\"lines\":[{\"lineId\":\"" + lineId + "\",\"quantity\":60}]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("IN_TRANSIT"))
        .andExpect(jsonPath("$.inTransitQuantity").value(60));

    http.perform(post("/warehouse/inventory/transfers/" + orderId + "/receive")
            .header("Authorization", "Bearer tenant-a")
            .contentType("application/json")
            .content(
                "{\"expectedVersion\":2,\"locationId\":\"" + locSz1 + "\","
                    + "\"lines\":[{\"lineId\":\"" + lineId + "\",\"quantity\":60}]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("RECEIVED"))
        .andExpect(jsonPath("$.inTransitQuantity").value(0));

    // 分录下钻复用台账，带归属口径
    http.perform(get("/warehouse/inventory/transfers/" + orderId + "/movements")
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.attribution").value("ORDER_GRANULAR"))
        .andExpect(jsonPath("$.movements.total").value(2));

    // 非法排序 400
    http.perform(get("/warehouse/inventory/transfers")
            .header("Authorization", "Bearer tenant-a")
            .param("sort", "DROP_TABLE"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void 端点_未知字段返回400() throws Exception {
    http.perform(post("/warehouse/inventory/transfers")
            .header("Authorization", "Bearer tenant-a")
            .contentType("application/json")
            .content("{\"fromWarehouseId\":\"" + whSh + "\",\"nope\":true}"))
        .andExpect(status().isBadRequest());
  }

  // ================================================================ 工具

  <T> T asA(java.util.function.Supplier<T> work) {
    return access.run(actorA, "warehouse.inventory.read", work);
  }

  <T> T asB(java.util.function.Supplier<T> work) {
    return access.run(actorB, "warehouse.inventory.read", work);
  }
}