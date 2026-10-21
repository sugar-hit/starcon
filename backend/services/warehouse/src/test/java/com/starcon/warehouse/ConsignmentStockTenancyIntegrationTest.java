package com.starcon.warehouse;

import static com.starcon.warehouse.ConsignmentStockModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.math.BigDecimal;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * 7.7.2.2 平台托管与全托管备货的**租户隔离三断言**。
 *
 * <p>三条断言缺一不可（AGENTS.md「多租户」）：
 *
 * <ol>
 *   <li><b>两租户互不可见</b>：B 看不到 A 的备货单、回执、结算回执、台账与对账结果；
 *   <li><b>伪造 tenant header 无效</b>：带 {@code X-Tenant-Id} 指向 A 的请求，身份是 B 时看到的仍然是 B 的；
 *       并且**用应用账号 warehouse_app 直连数据库**按主键查 A 的行必须查不到 ——
 *       用超级用户查会绕过 RLS，那条断言等于没做。
 *   <li><b>连接池归还后不串租户</b>：连接池压到 1 条，交替读写多轮不串。
 * </ol>
 *
 * <p>五张表逐张确认 ENABLE + FORCE RLS。
 */
class ConsignmentStockTenancyIntegrationTest extends ConsignmentStockTestBase {

  /** 建一张 A 租户的备货单（已发出、有平台回执）。 */
  UUID seedOrderA() {
    var input =
        new PlatformConsignmentOrderInput(
            SHOP_FULL,
            "FBA-WH-1",
            "平台仓一",
            idFor(a, 1),
            "CNY",
            "PER_ITEM",
            null,
            "A 的单",
            List.of(
                new PlatformConsignmentLineInput(
                    idFor(a, 2), new BigDecimal("100"), "PCS", null)));
    var orderId =
        access
            .run(
                actorA,
                "warehouse.inventory.update",
                () -> service.createOrder(input, "Bearer tenant-a", user))
            .order()
            .id();
    access.run(
        actorA,
        "warehouse.inventory.update",
        () -> service.ship(orderId, new PlatformConsignmentNoteInput("发出"), user));
    var lineId =
        access.run(actorA, "warehouse.inventory.read", () -> service.orderDetail(orderId))
            .lines()
            .get(0)
            .id();
    access.run(
        actorA,
        "warehouse.inventory.update",
        () ->
            service.recordReceipt(
                orderId,
                new PlatformConsignmentReceiptInput(
                    lineId,
                    "INBOUND",
                    "A-IN-1",
                    new BigDecimal("60"),
                    atEight(1, 10),
                    "PLATFORM_FEED",
                    null),
                user));
    return orderId;
  }

  // ================================================================ 断言一

  @Test
  void 断言一_两租户互不可见() {
    UUID orderA = seedOrderA();

    // B 看不到 A 的任何东西：连详情都是 404（比返回空集更强 —— 不泄露存在性）。
    assertThatThrownBy(
            () ->
                access.run(
                    actorB, "warehouse.inventory.read", () -> service.orderDetail(orderA)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("404");
    assertThatThrownBy(
            () ->
                access.run(actorB, "warehouse.inventory.read", () -> service.ledger(orderA)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("404");
    assertThatThrownBy(
            () ->
                access.run(actorB, "warehouse.inventory.read", () -> service.reconciliation(orderA)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("404");
    assertThatThrownBy(
            () ->
                access.run(
                    actorB, "warehouse.inventory.read", () -> service.settlementBasis(orderA)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("404");
    assertThatThrownBy(
            () ->
                access.run(
                    actorB, "warehouse.inventory.read", () -> service.receipts(orderA, null, 0, 20)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("404");
    assertThatThrownBy(
            () ->
                access.run(
                    actorB, "warehouse.inventory.read", () -> service.auditTrail(orderA, null)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("404");
    assertThat(
            access
                .run(actorB, "warehouse.inventory.read", () -> service.orders(null, null, null, null, 0, 20))
                .total())
        .isZero();

    // B 不能往 A 的单据上写平台回执。
    var aLineId =
        access.run(actorA, "warehouse.inventory.read", () -> service.orderDetail(orderA))
            .lines()
            .get(0)
            .id();
    assertThatThrownBy(
            () ->
                access.run(
                    actorB,
                    "warehouse.inventory.update",
                    () ->
                        service.recordReceipt(
                            orderA,
                            new PlatformConsignmentReceiptInput(
                                aLineId,
                                "INBOUND",
                                "B-IN-1",
                                new BigDecimal("999"),
                                atEight(2, 10),
                                "MANUAL",
                                null),
                            user)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("404");

    // B 自己的单与 A 的互不可见。
    var bInput =
        new PlatformConsignmentOrderInput(
            SHOP_FULL,
            "FBA-WH-9",
            "B 的平台仓",
            idFor(b, 1),
            "CNY",
            "PER_ITEM",
            null,
            null,
            List.of(
                new PlatformConsignmentLineInput(
                    idFor(b, 2), new BigDecimal("10"), "PCS", null)));
    var bOrderId =
        access
            .run(
                actorB,
                "warehouse.inventory.update",
                () -> service.createOrder(bInput, "Bearer tenant-b", user))
            .order()
            .id();
    assertThat(
            access
                .run(
                    actorB,
                    "warehouse.inventory.read",
                    () -> service.orders(null, null, null, null, 0, 20))
                .orders()
                .stream()
                .map(PlatformConsignmentOrder::id))
        .containsExactly(bOrderId);
  }

  // ================================================================ 断言二

  @Test
  void 断言二_伪造租户头无效() throws Exception {
    UUID orderA = seedOrderA();

    // 身份是 B，伪造 X-Tenant-Id 指向 A：B 看到的仍然是 B 的（空）。
    http.perform(
            get("/warehouse/inventory/consignment-stock/orders")
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(0));
    http.perform(
            get("/warehouse/inventory/consignment-stock/orders/{id}", orderA)
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a.toString()))
        .andExpect(status().isNotFound());
    http.perform(
            get("/warehouse/inventory/consignment-stock/orders/{id}/ledger", orderA)
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a.toString()))
        .andExpect(status().isNotFound());
    // 伪造头也不能让 B 给 A 的单登记平台回执（那等于往 A 的账里写数）。
    http.perform(
            post("/warehouse/inventory/consignment-stock/orders/{id}/receipts", orderA)
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a.toString())
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isNotFound());
    // A 的数据未被这串伪造请求改动。
    assertThat(
            access
                .run(actorA, "warehouse.inventory.read", () -> service.receipts(orderA, null, 0, 20))
                .total())
        .isEqualTo(1);

    // 🔴 用**应用账号** warehouse_app 直连数据库按主键查 A 的行必须查不到 ——
    //    用超级用户查会绕过 FORCE RLS，那条断言等于没做。
    try (var c = appConnection(); var s = c.createStatement()) {
      s.execute("select set_config('app.current_tenant', '" + b + "', false)");
      try (var rs =
          s.executeQuery(
              "select count(*) from consignment_stock_order where id = '" + orderA + "'")) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getLong(1)).isZero();
      }
      // 换成 A 的租户就能查到 —— 证明上一条不是因为「行不存在」。
      s.execute("select set_config('app.current_tenant', '" + a + "', false)");
      try (var rs =
          s.executeQuery(
              "select count(*) from consignment_stock_order where id = '" + orderA + "'")) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getLong(1)).isEqualTo(1);
      }
    }
  }

  // ================================================================ 断言三

  @Test
  void 断言三_连接池归还后不串租户() {
    UUID orderA = seedOrderA();
    var bInput =
        new PlatformConsignmentOrderInput(
            SHOP_FULL,
            "FBA-WH-9",
            "B 的平台仓",
            idFor(b, 1),
            "CNY",
            "PER_ITEM",
            null,
            null,
            List.of(
                new PlatformConsignmentLineInput(
                    idFor(b, 2), new BigDecimal("10"), "PCS", null)));
    access.run(
        actorB,
        "warehouse.inventory.update",
        () -> service.createOrder(bInput, "Bearer tenant-b", user));

    // 交替复用同一条物理连接（池容量=1）：A → B → A → B
    for (int i = 0; i < 3; i++) {
      var a1 = access.run(actorA, "warehouse.inventory.read", () -> service.orderDetail(orderA));
      assertThat(a1.order().id()).isEqualTo(orderA);
      assertThat(a1.ledger().rows().get(0).platformReceivedQuantity())
          .isEqualByComparingTo("60.000000");

      var b1 =
          access.run(
              actorB, "warehouse.inventory.read", () -> service.orders(null, null, null, null, 0, 20));
      assertThat(b1.total()).isEqualTo(1);

      var a2 =
          access.run(
              actorA, "warehouse.inventory.read", () -> service.orders(null, null, null, null, 0, 20));
      assertThat(a2.total()).isEqualTo(1);
      assertThat(a2.orders().get(0).id()).isEqualTo(orderA);

      var b2 =
          access.run(
              actorB, "warehouse.inventory.read", () -> service.orders(null, null, null, null, 0, 20));
      assertThat(b2.total()).isEqualTo(1);
    }

    // 五张表逐张确认 FORCE RLS
    for (var table :
        List.of(
            "consignment_stock_order",
            "consignment_stock_order_line",
            "consignment_stock_receipt",
            "consignment_stock_settlement_receipt",
            "consignment_stock_audit")) {
      assertThat(
              sql.fetchOne("select relforcerowsecurity from pg_class where relname = ?", table)
                  .get("relforcerowsecurity", Boolean.class))
          .isTrue();
      assertThat(
              sql.fetchOne("select relrowsecurity from pg_class where relname = ?", table)
                  .get("relrowsecurity", Boolean.class))
          .isTrue();
    }
  }
}
