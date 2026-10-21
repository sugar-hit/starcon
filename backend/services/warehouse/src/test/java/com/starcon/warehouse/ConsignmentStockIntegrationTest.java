package com.starcon.warehouse;

import static com.starcon.warehouse.ConsignmentStockModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.math.BigDecimal;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * 7.7.2.2 平台托管与全托管备货：备货单 → 平台仓收发回执 → 托管备货台账 → 平台结算回执。
 *
 * <p>真实 PostgreSQL 16 + 强制 RLS，不使用任何内存替身。
 *
 * <p><b>本测试类的核心是四件事</b>：
 *
 * <ol>
 *   <li><b>备货 ≠ 自有库存</b>：平台在库数**进不了**自有库存账本 —— 绕过服务层直连 SQL 也一样被数据库挡下
 *       （{@link #平台在库数进不了自有库存账本()}）；
 *   <li><b>四个口径分得开</b>：备货量 / 平台在库 / 平台已售 / 结算未结各有各的值，
 *       「备货量 − 平台入库」是**在途未确认**而不是差异（{@link #台账四个口径分列()}）；
 *   <li><b>两种结算口径真的算出不同结果</b>：按件看件数差，按日看哪几天的销还没结
 *       （{@link #按件与按日两种口径的未结量不同()}）；
 *   <li><b>读不到 ≠ 0</b>：channel 不可达报 503、没有这条记录报「无记录」，
 *       平台没给任何回执时未结量是 {@code null} 而不是 0。
 * </ol>
 */
class ConsignmentStockIntegrationTest extends ConsignmentStockTestBase {

  // ================================================================ 夹具

  /** 建一张备货单（默认全托管店铺 + 显式明细）。 */
  UUID createOrder(String basis, UUID skuId, String qty) {
    return createOrder(basis, skuId, qty, null);
  }

  UUID createOrder(String basis, UUID skuId, String qty, UUID forecastId) {
    var input =
        new PlatformConsignmentOrderInput(
            SHOP_FULL,
            "FBA-WH-1",
            "平台仓一",
            idFor(a, 1),
            "CNY",
            basis,
            forecastId,
            "托管备货单",
            List.of(new PlatformConsignmentLineInput(skuId, new BigDecimal(qty), "PCS", null)));
    return access
        .run(
            actorA,
            "warehouse.inventory.update",
            () -> service.createOrder(input, "Bearer tenant-a", user))
        .order()
        .id();
  }

  /** 只给预报单、不给明细行 —— 备货行由 10.4.2.2 预填。 */
  UUID createOrderFromForecast(String basis, UUID forecastId) {
    var input =
        new PlatformConsignmentOrderInput(
            SHOP_FULL,
            "FBA-WH-1",
            "平台仓一",
            idFor(a, 1),
            "CNY",
            basis,
            forecastId,
            "按预报预填",
            List.of());
    return access
        .run(
            actorA,
            "warehouse.inventory.update",
            () -> service.createOrder(input, "Bearer tenant-a", user))
        .order()
        .id();
  }

  UUID lineOf(UUID orderId, UUID skuId) {
    return access
        .run(actorA, "warehouse.inventory.read", () -> service.orderDetail(orderId))
        .lines()
        .stream()
        .filter(l -> l.skuId().equals(skuId))
        .findFirst()
        .orElseThrow()
        .id();
  }

  void ship(UUID orderId) {
    access.run(
        actorA,
        "warehouse.inventory.update",
        () -> service.ship(orderId, new PlatformConsignmentNoteInput("发出"), user));
  }

  PlatformConsignmentReceipt receipt(
      UUID orderId, UUID lineId, String type, String ref, String qty, OffsetDateTime at) {
    return access.run(
        actorA,
        "warehouse.inventory.update",
        () ->
            service.recordReceipt(
                orderId,
                new PlatformConsignmentReceiptInput(
                    lineId, type, ref, new BigDecimal(qty), at, "PLATFORM_FEED", null),
                user));
  }

  PlatformConsignmentSettlementReceipt settlement(
      UUID orderId,
      UUID lineId,
      String basis,
      LocalDate start,
      LocalDate end,
      String qty,
      String amount) {
    return access.run(
        actorA,
        "warehouse.inventory.update",
        () ->
            service.recordSettlementReceipt(
                orderId,
                new PlatformConsignmentSettlementReceiptInput(
                    lineId,
                    basis,
                    start,
                    end,
                    new BigDecimal(qty),
                    amount == null ? null : new BigDecimal(amount),
                    "CNY",
                    "SET-" + UUID.randomUUID(),
                    atEight(6, 9),
                    "PLATFORM_FEED",
                    null),
                user));
  }

  /** 往自有库存放一批货（只读对照用）。 */
  UUID putOnHand(UUID tenant, UUID skuId, String onHand) {
    UUID balanceId = UUID.randomUUID();
    access.run(
        actorFor(tenant),
        "warehouse.inventory.update",
        () -> {
          sql.execute(
              "insert into inventory_balance(id,tenant_id,sku_id,warehouse_id,legal_entity_id,"
                  + "quality_status_id,on_hand,reserved) values(?,?,?,?,?,?,?,0)",
              balanceId,
              tenant,
              skuId,
              idFor(tenant, 5),
              idFor(tenant, 1),
              idFor(tenant, 4),
              new BigDecimal(onHand));
          return null;
        });
    return balanceId;
  }

  // ================================================================ 能力

  @Test
  void 能力声明报出四口径与未接入环节() {
    var cap = access.run(actorA, "warehouse.inventory.read", () -> service.capabilities());
    assertThat(cap.operatingModes()).hasSize(5);
    assertThat(cap.settlementBases()).extracting(PlatformConsignmentOption::value)
        .containsExactly("PER_ITEM", "PER_DAY");
    assertThat(cap.supportedOperatingModes())
        .containsExactly("FULL_CONSIGNMENT", "SEMI_CONSIGNMENT");
    // 未接入的环节必须**显式报出**：空数组会被读成「没有缺口」。
    assertThat(cap.unavailable()).isNotEmpty();
    assertThat(cap.unavailable()).extracting(PlatformConsignmentUnavailableCapability::ownerModule)
        .contains("12.3.1", "13.4.1.1");
    assertThat(cap.measureNote()).contains("备货量").contains("平台在库").contains("结算未结");
    assertThat(cap.separationNote()).contains("不得并入自有库存余额");
    assertThat(cap.settlementNote()).contains("按件").contains("按日");
  }

  // ================================================================ 经营模式（2.3.2.1）

  @Test
  void 自发货店铺不允许建平台托管备货单() {
    var input =
        new PlatformConsignmentOrderInput(
            SHOP_SELF,
            "FBA-WH-1",
            "平台仓一",
            idFor(a, 1),
            "CNY",
            "PER_ITEM",
            null,
            null,
            List.of(
                new PlatformConsignmentLineInput(
                    idFor(a, 2), new BigDecimal("10"), "PCS", null)));
    assertThatThrownBy(
            () ->
                access.run(
                    actorA,
                    "warehouse.inventory.update",
                    () -> service.createOrder(input, "Bearer tenant-a", user)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("400")
        .hasMessageContaining("自发货");
    // 一张单都不该建出来。
    assertThat(
            access.run(actorA, "warehouse.inventory.read", () -> service.orders(null, null, null, null, 0, 20))
                .total())
        .isZero();
  }

  @Test
  void channel读不到时报503而不是猜一个模式() {
    var input =
        new PlatformConsignmentOrderInput(
            SHOP_BROKEN,
            "FBA-WH-1",
            "平台仓一",
            idFor(a, 1),
            "CNY",
            "PER_ITEM",
            null,
            null,
            List.of(
                new PlatformConsignmentLineInput(
                    idFor(a, 2), new BigDecimal("10"), "PCS", null)));
    assertThatThrownBy(
            () ->
                access.run(
                    actorA,
                    "warehouse.inventory.update",
                    () -> service.createOrder(input, "Bearer tenant-a", user)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("503")
        .hasMessageContaining("读不到");
  }

  @Test
  void channel没有这家店时如实报无记录() {
    var mode =
        access.run(
            actorA, "warehouse.inventory.read", () -> service.shopMode(SHOP_MISSING, "Bearer tenant-a"));
    assertThat(mode.known()).isFalse();
    assertThat(mode.operatingMode()).isNull();
    assertThat(mode.note()).contains("无记录");
    // 模式未知 → 不许建单。
    var input =
        new PlatformConsignmentOrderInput(
            SHOP_MISSING,
            "FBA-WH-1",
            "平台仓一",
            idFor(a, 1),
            "CNY",
            "PER_ITEM",
            null,
            null,
            List.of(
                new PlatformConsignmentLineInput(
                    idFor(a, 2), new BigDecimal("10"), "PCS", null)));
    assertThatThrownBy(
            () ->
                access.run(
                    actorA,
                    "warehouse.inventory.update",
                    () -> service.createOrder(input, "Bearer tenant-a", user)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("404");
  }

  @Test
  void 经营模式随建单快照下来但不建影子表() {
    UUID orderId = createOrder("PER_ITEM", idFor(a, 2), "100");
    var order =
        access.run(actorA, "warehouse.inventory.read", () -> service.orderDetail(orderId)).order();
    assertThat(order.operatingMode()).isEqualTo("FULL_CONSIGNMENT");
    assertThat(order.operatingModeLabel()).isEqualTo("全托管");
    // 本服务不复制店铺档案：库里只有快照列，没有 shop 表。
    assertThat(
            sql.fetchOne(
                    "select count(*) from information_schema.tables"
                        + " where table_schema='public' and table_name='shop'")
                .get(0, Long.class))
        .isZero();
  }

  // ================================================================ 备货预报（10.4.2.2）

  @Test
  void 按备货预报预填建单() {
    UUID orderId = createOrderFromForecast("PER_ITEM", FORECAST_KNOWN);
    var detail = access.run(actorA, "warehouse.inventory.read", () -> service.orderDetail(orderId));
    assertThat(detail.order().forecastNo()).isEqualTo("FC-0001");
    assertThat(detail.lines()).hasSize(2);
    // 明细按 id 排序，不按预报里的先后 —— 断言时按 SKU 编码找行，别靠下标。
    assertThat(detail.lines().stream().filter(l -> l.skuCode().equals("SKU-1")).findFirst())
        .isPresent()
        .get()
        .extracting(PlatformConsignmentOrderLine::stockedQuantity)
        .isEqualTo(new BigDecimal("100.000000"));
    assertThat(detail.lines().stream().filter(l -> l.skuCode().equals("SKU-2")).findFirst())
        .isPresent()
        .get()
        .extracting(PlatformConsignmentOrderLine::stockedQuantity)
        .isEqualTo(new BigDecimal("50.000000"));
  }

  @Test
  void 备货预报读不到时报503而不是当成没有备货需求() {
    assertThatThrownBy(
            () ->
                access.run(
                    actorA,
                    "warehouse.inventory.read",
                    () -> service.forecastPreview(FORECAST_BROKEN, "Bearer tenant-a")))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("503")
        .hasMessageContaining("读不到");
  }

  @Test
  void 备货预报没有这张单时如实报无记录() {
    var preview =
        access.run(
            actorA,
            "warehouse.inventory.read",
            () -> service.forecastPreview(FORECAST_MISSING, "Bearer tenant-a"));
    assertThat(preview.known()).isFalse();
    assertThat(preview.lines()).isEmpty();
    assertThat(preview.note()).contains("无记录");
  }

  // ================================================================ 台账四口径

  @Test
  void 台账四个口径分列() {
    UUID orderId = createOrder("PER_ITEM", idFor(a, 2), "100");
    UUID lineId = lineOf(orderId, idFor(a, 2));
    ship(orderId);
    // 平台只确认收到 60 件：剩下 40 件是**在途未确认**，不是差异损失。
    receipt(orderId, lineId, "INBOUND", "R-IN-1", "60", atEight(1, 10));
    receipt(orderId, lineId, "OUTBOUND", "R-OUT-1", "20", atEight(2, 10));

    var ledger = access.run(actorA, "warehouse.inventory.read", () -> service.ledger(orderId));
    var row = ledger.rows().get(0);
    assertThat(row.stockedQuantity()).isEqualByComparingTo("100.000000");
    assertThat(row.platformReceivedQuantity()).isEqualByComparingTo("60.000000");
    assertThat(row.platformSoldQuantity()).isEqualByComparingTo("20.000000");
    assertThat(row.platformOnHandQuantity()).isEqualByComparingTo("40.000000");
    assertThat(row.inTransitQuantity()).isEqualByComparingTo("40.000000");
    assertThat(row.unsettledQuantity()).isEqualByComparingTo("20.000000");
    // 四个口径是四个字段，不是一个「库存量」换着名字用。
    assertThat(ledger.totalStockedQuantity()).isEqualByComparingTo("100.000000");
    assertThat(ledger.totalPlatformOnHandQuantity()).isEqualByComparingTo("40.000000");
    assertThat(ledger.totalPlatformSoldQuantity()).isEqualByComparingTo("20.000000");
    assertThat(ledger.totalUnsettledQuantity()).isEqualByComparingTo("20.000000");
    // 托管货**不是**自有资产。
    assertThat(row.countsAsOwnedAsset()).isFalse();
  }

  @Test
  void 自有库存那一侧并排给出且不含托管量() {
    UUID orderId = createOrder("PER_ITEM", idFor(a, 2), "100");
    UUID lineId = lineOf(orderId, idFor(a, 2));
    putOnHand(a, idFor(a, 2), "35");
    ship(orderId);
    receipt(orderId, lineId, "INBOUND", "R-IN-1", "100", atEight(1, 10));

    var recon =
        access.run(actorA, "warehouse.inventory.read", () -> service.reconciliation(orderId));
    var row = recon.rows().get(0);
    // 自有库存里只有 35 件（我方自己仓里的），平台在库的 100 件**不在**里面。
    assertThat(row.ownInventoryOnHandQuantity()).isEqualByComparingTo("35.000000");
    assertThat(row.platformOnHandQuantity()).isEqualByComparingTo("100.000000");
    assertThat(recon.ownInventoryOnHandQuantity()).isEqualByComparingTo("35.000000");
    assertThat(recon.segregationLeakQuantity()).isEqualByComparingTo("0.000000");
    assertThat(recon.segregationStatus()).isEqualTo("SEPARATED");
  }

  @Test
  void 对账结果有量有口径() {
    UUID orderId = createOrder("PER_ITEM", idFor(a, 2), "100");
    UUID lineId = lineOf(orderId, idFor(a, 2));
    ship(orderId);
    receipt(orderId, lineId, "INBOUND", "R-IN-1", "60", atEight(1, 10));

    var recon =
        access.run(actorA, "warehouse.inventory.read", () -> service.reconciliation(orderId));
    var row = recon.rows().get(0);
    // 差异量 = 平台入库回执累计 − 备货量 = 60 − 100 = −40（有符号）。
    assertThat(row.differenceQuantity()).isEqualByComparingTo("-40.000000");
    assertThat(row.differenceBasis()).isEqualTo("IN_TRANSIT");
    assertThat(row.differenceBasisLabel()).isEqualTo("在途未确认");
    assertThat(row.differenceNote()).contains("在途").contains("不是损失");
  }

  @Test
  void 平台多收时差异口径换一种() {
    UUID orderId = createOrder("PER_ITEM", idFor(a, 2), "100");
    UUID lineId = lineOf(orderId, idFor(a, 2));
    ship(orderId);
    receipt(orderId, lineId, "INBOUND", "R-IN-1", "130", atEight(1, 10));

    var recon =
        access.run(actorA, "warehouse.inventory.read", () -> service.reconciliation(orderId));
    var row = recon.rows().get(0);
    assertThat(row.differenceQuantity()).isEqualByComparingTo("30.000000");
    assertThat(row.differenceBasis()).isEqualTo("OVER_RECEIVED");
  }

  // ================================================================ 结算口径

  @Test
  void 按件与按日两种口径的未结量不同() {
    // 同一批回执（30 件 11-02 售出、20 件 11-05 售出），平台结算了 25 件（区间 11-01~11-03）。
    UUID perItem = createOrder("PER_ITEM", idFor(a, 2), "100");
    UUID lineItem = lineOf(perItem, idFor(a, 2));
    ship(perItem);
    receipt(perItem, lineItem, "INBOUND", "PI-IN", "100", atEight(1, 10));
    receipt(perItem, lineItem, "OUTBOUND", "PI-OUT-1", "30", atEight(2, 10));
    receipt(perItem, lineItem, "OUTBOUND", "PI-OUT-2", "20", atEight(5, 10));
    settlement(
        perItem, lineItem, "PER_ITEM", LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 3), "25", null);

    UUID perDay = createOrder("PER_DAY", idFor(a, 2), "100");
    UUID lineDay = lineOf(perDay, idFor(a, 2));
    ship(perDay);
    receipt(perDay, lineDay, "INBOUND", "PD-IN", "100", atEight(1, 10));
    receipt(perDay, lineDay, "OUTBOUND", "PD-OUT-1", "30", atEight(2, 10));
    receipt(perDay, lineDay, "OUTBOUND", "PD-OUT-2", "20", atEight(5, 10));
    settlement(
        perDay, lineDay, "PER_DAY", LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 3), "25", null);

    // 按件：未结 = 已售 50 − 已结 25 = 25。
    var itemView =
        access.run(actorA, "warehouse.inventory.read", () -> service.settlementBasis(perItem));
    assertThat(itemView.basis()).isEqualTo("PER_ITEM");
    assertThat(itemView.platformSoldQuantity()).isEqualByComparingTo("50.000000");
    assertThat(itemView.settledQuantity()).isEqualByComparingTo("25.000000");
    assertThat(itemView.unsettledQuantity()).isEqualByComparingTo("25.000000");

    // 按日：11-02 的 30 件落在已结算区间内，11-05 的 20 件落在未结算区间内 → 未结 = 20。
    var dayView =
        access.run(actorA, "warehouse.inventory.read", () -> service.settlementBasis(perDay));
    assertThat(dayView.basis()).isEqualTo("PER_DAY");
    assertThat(dayView.platformSoldQuantity()).isEqualByComparingTo("50.000000");
    assertThat(dayView.unsettledQuantity()).isEqualByComparingTo("20.000000");
    assertThat(dayView.settledPeriods()).hasSize(1);
    assertThat(dayView.settledPeriods().get(0).periodStart()).isEqualTo(LocalDate.of(2026, 11, 1));
  }

  @Test
  void 结算回执的口径必须与备货单一致() {
    UUID orderId = createOrder("PER_ITEM", idFor(a, 2), "100");
    UUID lineId = lineOf(orderId, idFor(a, 2));
    ship(orderId);
    receipt(orderId, lineId, "INBOUND", "R-IN-1", "100", atEight(1, 10));
    receipt(orderId, lineId, "OUTBOUND", "R-OUT-1", "10", atEight(2, 10));
    assertThatThrownBy(
            () ->
                settlement(
                    orderId,
                    lineId,
                    "PER_DAY",
                    LocalDate.of(2026, 11, 1),
                    LocalDate.of(2026, 11, 3),
                    "5",
                    null))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("400")
        .hasMessageContaining("口径");
  }

  @Test
  void 平台没给任何回执时未结量是null而不是0() {
    UUID orderId = createOrder("PER_ITEM", idFor(a, 2), "100");
    var view = access.run(actorA, "warehouse.inventory.read", () -> service.settlementBasis(orderId));
    assertThat(view.unsettledKnown()).isFalse();
    assertThat(view.unsettledQuantity()).isNull();
    assertThat(view.settlementState()).isEqualTo("NOT_STARTED");
  }

  @Test
  void 结算口径离开草稿态即冻结() {
    UUID orderId = createOrder("PER_ITEM", idFor(a, 2), "100");
    ship(orderId);
    assertThatThrownBy(
            () ->
                access.run(
                    actorA,
                    "warehouse.inventory.update",
                    () ->
                        sql.execute(
                            "update consignment_stock_order set settlement_basis='PER_DAY'"
                                + " where tenant_id=? and id=?",
                            a,
                            orderId)))
        .hasMessageContaining("结算口径");
  }

  // ================================================================ 分账红线

  @Test
  void 平台在库数进不了自有库存账本() throws Exception {
    UUID orderId = createOrder("PER_ITEM", idFor(a, 2), "100");
    UUID balanceId = putOnHand(a, idFor(a, 2), "10");
    UUID txId = UUID.randomUUID();
    // 绕过服务层、直连数据库：往自有库存账本里塞一笔「平台托管入库」的正数分录。
    try (var c = appConnection(); var s = c.createStatement()) {
      s.execute("select set_config('app.current_tenant', '" + a + "', false)");
      s.execute(
          "insert into inventory_transaction(id,tenant_id,kind,ref_type,ref_id,occurred_at,actor_id)"
              + " values('" + txId + "','" + a + "','SHIPMENT','CONSIGNMENT_STOCK','" + orderId
              + "',now(),'" + user + "')");
      var e =
          assertThatThrownBy(
                  () ->
                      s.execute(
                          "insert into inventory_entry(id,tenant_id,transaction_id,balance_id,"
                              + "on_hand_delta,reserved_delta) values('"
                              + UUID.randomUUID()
                              + "','"
                              + a
                              + "','"
                              + txId
                              + "','"
                              + balanceId
                              + "',50,0)"))
              .hasMessageContaining("不得并入自有库存余额");
      assertThat(e).isNotNull();
    }
    // 分账校验仍然是「已分账」：库里没有任何正数托管入库。
    var recon =
        access.run(actorA, "warehouse.inventory.read", () -> service.reconciliation(orderId));
    assertThat(recon.segregationLeakQuantity()).isEqualByComparingTo("0.000000");
    assertThat(recon.segregationStatus()).isEqualTo("SEPARATED");
  }

  @Test
  void 平台在库从不出库也不许占我方预占() throws Exception {
    UUID orderId = createOrder("PER_ITEM", idFor(a, 2), "100");
    UUID balanceId = putOnHand(a, idFor(a, 2), "10");
    UUID txId = UUID.randomUUID();
    try (var c = appConnection(); var s = c.createStatement()) {
      s.execute("select set_config('app.current_tenant', '" + a + "', false)");
      s.execute(
          "insert into inventory_transaction(id,tenant_id,kind,ref_type,ref_id,occurred_at,actor_id)"
              + " values('" + txId + "','" + a + "','SHIPMENT','CONSIGNMENT_STOCK','" + orderId
              + "',now(),'" + user + "')");
      assertThatThrownBy(
              () ->
                  s.execute(
                      "insert into inventory_entry(id,tenant_id,transaction_id,balance_id,"
                          + "on_hand_delta,reserved_delta) values('"
                          + UUID.randomUUID()
                          + "','"
                          + a
                          + "','"
                          + txId
                          + "','"
                          + balanceId
                          + "',0,7)"))
          .hasMessageContaining("不允许预占自有库存");
    }
  }

  @Test
  void 托管货从我方仓出库是允许的() throws Exception {
    UUID orderId = createOrder("PER_ITEM", idFor(a, 2), "100");
    UUID balanceId = putOnHand(a, idFor(a, 2), "10");
    UUID txId = UUID.randomUUID();
    try (var c = appConnection(); var s = c.createStatement()) {
      s.execute("select set_config('app.current_tenant', '" + a + "', false)");
      s.execute(
          "insert into inventory_transaction(id,tenant_id,kind,ref_type,ref_id,occurred_at,actor_id)"
              + " values('" + txId + "','" + a + "','SHIPMENT','CONSIGNMENT_STOCK','" + orderId
              + "',now(),'" + user + "')");
      // 负数出库（货离开我方仓）必须放行 —— 只挡正数入库。
      s.execute(
          "insert into inventory_entry(id,tenant_id,transaction_id,balance_id,on_hand_delta,reserved_delta)"
              + " values('"
              + UUID.randomUUID()
              + "','"
              + a
              + "','"
              + txId
              + "','"
              + balanceId
              + "',-10,0)");
    }
  }

  // ================================================================ 守恒

  @Test
  void 平台出库不得超过入库() {
    UUID orderId = createOrder("PER_ITEM", idFor(a, 2), "100");
    UUID lineId = lineOf(orderId, idFor(a, 2));
    ship(orderId);
    assertThatThrownBy(
            () -> receipt(orderId, lineId, "OUTBOUND", "R-OUT-1", "10", atEight(2, 10)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("409")
        .hasMessageContaining("不能卖出");
  }

  @Test
  void 结算不得超过平台已售() {
    UUID orderId = createOrder("PER_ITEM", idFor(a, 2), "100");
    UUID lineId = lineOf(orderId, idFor(a, 2));
    ship(orderId);
    receipt(orderId, lineId, "INBOUND", "R-IN-1", "100", atEight(1, 10));
    receipt(orderId, lineId, "OUTBOUND", "R-OUT-1", "10", atEight(2, 10));
    assertThatThrownBy(
            () ->
                settlement(
                    orderId,
                    lineId,
                    "PER_ITEM",
                    LocalDate.of(2026, 11, 1),
                    LocalDate.of(2026, 11, 3),
                    "30",
                    null))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("409")
        .hasMessageContaining("没卖出去的不许结");
  }

  @Test
  void 同一平台回执号不会登记两次() {
    UUID orderId = createOrder("PER_ITEM", idFor(a, 2), "100");
    UUID lineId = lineOf(orderId, idFor(a, 2));
    ship(orderId);
    receipt(orderId, lineId, "INBOUND", "R-IN-1", "60", atEight(1, 10));
    assertThatThrownBy(
            () -> receipt(orderId, lineId, "INBOUND", "R-IN-1", "60", atEight(1, 10)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("409");
    var page =
        access.run(actorA, "warehouse.inventory.read", () -> service.receipts(orderId, null, 0, 20));
    assertThat(page.total()).isEqualTo(1);
  }

  // ================================================================ 状态机

  @Test
  void 确认平台收货必须有平台入库回执() {
    UUID orderId = createOrder("PER_ITEM", idFor(a, 2), "100");
    ship(orderId);
    assertThatThrownBy(
            () ->
                access.run(
                    actorA,
                    "warehouse.inventory.update",
                    () ->
                        service.confirmReceipt(
                            orderId, new PlatformConsignmentNoteInput(null), user)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("409")
        .hasMessageContaining("平台入库回执");
  }

  @Test
  void 未结不为0不许关闭单据() {
    UUID orderId = createOrder("PER_ITEM", idFor(a, 2), "100");
    UUID lineId = lineOf(orderId, idFor(a, 2));
    ship(orderId);
    receipt(orderId, lineId, "INBOUND", "R-IN-1", "100", atEight(1, 10));
    receipt(orderId, lineId, "OUTBOUND", "R-OUT-1", "10", atEight(2, 10));
    access.run(
        actorA,
        "warehouse.inventory.update",
        () -> service.confirmReceipt(orderId, new PlatformConsignmentNoteInput(null), user));
    assertThatThrownBy(
            () ->
                access.run(
                    actorA,
                    "warehouse.inventory.update",
                    () -> service.close(orderId, new PlatformConsignmentNoteInput(null), user)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("409")
        .hasMessageContaining("结算未结");
    // 结清之后就能关。
    settlement(
        orderId,
        lineId,
        "PER_ITEM",
        LocalDate.of(2026, 11, 1),
        LocalDate.of(2026, 11, 3),
        "10",
        "100.0000");
    var closed =
        access.run(
            actorA,
            "warehouse.inventory.update",
            () -> service.close(orderId, new PlatformConsignmentNoteInput("结清"), user));
    assertThat(closed.status()).isEqualTo("CLOSED");
    // 终态不可复活。
    assertThatThrownBy(
            () ->
                access.run(
                    actorA,
                    "warehouse.inventory.update",
                    () -> service.cancel(orderId, new PlatformConsignmentNoteInput(null), user)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("409");
  }

  @Test
  void 草稿态之外不能改备货量() {
    UUID orderId = createOrder("PER_ITEM", idFor(a, 2), "100");
    ship(orderId);
    assertThatThrownBy(
            () ->
                access.run(
                    actorA,
                    "warehouse.inventory.update",
                    () ->
                        service.addLine(
                            orderId,
                            new PlatformConsignmentAddLineInput(
                                idFor(a, 3), new BigDecimal("5"), "PCS", null),
                            user)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("409")
        .hasMessageContaining("草稿");
  }

  // ================================================================ 时间 / 分页

  @Test
  void timestamptz写入读回零漂移() {
    UUID orderId = createOrder("PER_ITEM", idFor(a, 2), "100");
    UUID lineId = lineOf(orderId, idFor(a, 2));
    ship(orderId);
    var at = atEight(1, 15);
    receipt(orderId, lineId, "INBOUND", "R-IN-1", "60", at);
    var page =
        access.run(actorA, "warehouse.inventory.read", () -> service.receipts(orderId, null, 0, 20));
    var read = page.receipts().get(0);
    assertThat(read.occurredAt().toInstant()).isEqualTo(at.toInstant());
    assertThat(read.occurredAt().withOffsetSameInstant(java.time.ZoneOffset.ofHours(8)).getHour())
        .isEqualTo(15);
  }

  @Test
  void 真分页两页不重叠() {
    for (int i = 0; i < 3; i++) {
      createOrder("PER_ITEM", idFor(a, 2), "10");
    }
    var page1 =
        access.run(actorA, "warehouse.inventory.read", () -> service.orders(null, null, null, null, 0, 2));
    var page2 =
        access.run(actorA, "warehouse.inventory.read", () -> service.orders(null, null, null, null, 1, 2));
    assertThat(page1.total()).isEqualTo(3);
    assertThat(page1.orders()).hasSize(2);
    assertThat(page2.orders()).hasSize(1);
    var ids1 = page1.orders().stream().map(PlatformConsignmentOrder::id).toList();
    var ids2 = page2.orders().stream().map(PlatformConsignmentOrder::id).toList();
    assertThat(ids1).doesNotContainAnyElementsOf(ids2);
  }

  @Test
  void 筛选与关键字真的生效() {
    UUID item = createOrder("PER_ITEM", idFor(a, 2), "10");
    UUID day = createOrder("PER_DAY", idFor(a, 3), "10");
    var byBasis =
        access.run(
            actorA, "warehouse.inventory.read", () -> service.orders(null, null, "PER_DAY", null, 0, 20));
    assertThat(byBasis.orders()).extracting(PlatformConsignmentOrder::id).containsExactly(day);
    assertThat(byBasis.orders().stream().map(PlatformConsignmentOrder::id).toList())
        .doesNotContain(item);
    var byKeyword =
        access.run(
            actorA, "warehouse.inventory.read", () -> service.orders(null, null, null, "FBA-WH-1", 0, 20));
    assertThat(byKeyword.total()).isEqualTo(2);
  }

  // ================================================================ 权限与端点

  @Test
  void 只读身份不能写() throws Exception {
    var readOnly = actor(user, a, List.of(grant("ALL", List.of("warehouse.inventory.read"))));
    actors.put("Bearer cs-read-only", readOnly);
    http.perform(
            post("/warehouse/inventory/consignment-stock/orders")
                .header("Authorization", "Bearer cs-read-only")
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void 端点真的把四个口径分开返回() throws Exception {
    UUID orderId = createOrder("PER_ITEM", idFor(a, 2), "100");
    UUID lineId = lineOf(orderId, idFor(a, 2));
    ship(orderId);
    receipt(orderId, lineId, "INBOUND", "R-IN-1", "60", atEight(1, 10));
    receipt(orderId, lineId, "OUTBOUND", "R-OUT-1", "20", atEight(2, 10));

    http.perform(
            get("/warehouse/inventory/consignment-stock/orders/{id}/ledger", orderId)
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rows[0].stockedQuantity").value(100.0))
        .andExpect(jsonPath("$.rows[0].platformReceivedQuantity").value(60.0))
        .andExpect(jsonPath("$.rows[0].platformSoldQuantity").value(20.0))
        .andExpect(jsonPath("$.rows[0].platformOnHandQuantity").value(40.0))
        .andExpect(jsonPath("$.rows[0].unsettledQuantity").value(20.0));

    http.perform(
            get("/warehouse/inventory/consignment-stock/orders/{id}/reconciliation", orderId)
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rows[0].differenceQuantity").value(-40.0))
        .andExpect(jsonPath("$.rows[0].differenceBasis").value("IN_TRANSIT"))
        .andExpect(jsonPath("$.segregationStatus").value("SEPARATED"));

    http.perform(
            get("/warehouse/inventory/consignment-stock/orders/{id}/settlement-basis", orderId)
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.basis").value("PER_ITEM"))
        .andExpect(jsonPath("$.unsettledQuantity").value(20.0));

    http.perform(
            get("/warehouse/inventory/consignment-stock/orders/{id}/audit", orderId)
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(org.hamcrest.Matchers.greaterThan(0)));
  }

  @Test
  void 审计留痕记下每一次动作() {
    UUID orderId = createOrder("PER_ITEM", idFor(a, 2), "100");
    UUID lineId = lineOf(orderId, idFor(a, 2));
    ship(orderId);
    receipt(orderId, lineId, "INBOUND", "R-IN-1", "60", atEight(1, 10));
    var trail =
        access.run(actorA, "warehouse.inventory.read", () -> service.auditTrail(orderId, null));
    assertThat(trail).extracting(PlatformConsignmentAuditEntry::eventType)
        .contains("ORDER_CREATED", "ORDER_SHIPPED", "RECEIPT_RECORDED");
  }
}
