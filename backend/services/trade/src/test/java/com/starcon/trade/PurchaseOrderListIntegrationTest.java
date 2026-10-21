package com.starcon.trade;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.starcon.trade.PurchaseOrderListModels.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

/**
 * 6.3.1.1 采购单列表的<b>功能集成测试</b>（真实 PostgreSQL 16 + 强制 RLS）。
 *
 * <p>跑的是**本模块自己的三张表**（purchase_order / purchase_order_line / purchase_order_event）
 * 与**上游的真实事实表**（6.2.2.1 的 purchase_request*、6.3.2.2 的 purchase_arrival_tracking）——
 * 不是占位探针表。
 *
 * <p>本测试类盯住三件最容易做错的事：
 *
 * <ol>
 *   <li><b>已收数量 / 比例的口径</b>：来自 6.3.2.2 的到货跟踪；<b>没进跟踪就是 null，不是 0</b>。
 *   <li><b>跨币种不能相加</b>：汇总按币种分组；本位币合计用建单时冻结的汇率快照。
 *   <li><b>金额与行数由 DB 算</b>：调用方传不进来。
 * </ol>
 */
class PurchaseOrderListIntegrationTest extends PurchaseOrderListFixture {

  @Autowired PurchaseOrderListService orders;

  @BeforeEach
  void clean() throws Exception {
    truncateAll();
    catalogDown.set(false);
    lastRateQuery.set(null);
  }

  private PurchaseOrderListQuery query() {
    return new PurchaseOrderListQuery(
        null, null, null, null, null, null, null, null, null, null, null, null, 0, 50,
        userA, departmentA, "ALL");
  }

  private PurchaseOrderListQuery query(String scope, UUID actor) {
    return new PurchaseOrderListQuery(
        null, null, null, null, null, null, null, null, null, null, null, null, 0, 50,
        actor, departmentA, scope);
  }

  private LocalDate today() {
    return LocalDate.now(ZoneOffset.UTC);
  }

  // ==========================================================================
  // 列表 + 分页 + 汇总
  // ==========================================================================

  @Test
  void 列表是真分页_汇总与分页无关() throws Exception {
    seedOrder(tenantA, userA, departmentA, "PO-P1", "USD", "10", "2.5", today().plusDays(5), "SUP-A");
    seedOrder(tenantA, userA, departmentA, "PO-P2", "USD", "20", "2.5", today().plusDays(6), "SUP-A");
    seedOrder(tenantA, userA, departmentA, "PO-P3", "EUR", "30", "1.5", today().plusDays(7), "SUP-B");

    var page = as("Bearer tenant-a", () -> orders.list(query()));
    assertThat(page.total()).isEqualTo(3);
    assertThat(page.rows()).hasSize(3);
    assertThat(page.page()).isZero();
    assertThat(page.size()).isEqualTo(50);

    // 分页：第 2 页只有 1 行，**总数仍然是 3**（总数与分页无关）。
    var p2 =
        as(
            "Bearer tenant-a",
            () ->
                orders.list(
                    new PurchaseOrderListQuery(
                        null, null, null, null, null, null, null, null, null, null, null, null,
                        1, 2, userA, departmentA, "ALL")));
    assertThat(p2.rows()).hasSize(1);
    assertThat(p2.total()).isEqualTo(3);
    assertThat(p2.summary().totalCount()).isEqualTo(3);
  }

  @Test
  void 汇总按币种分组_绝不跨币种相加() throws Exception {
    // USD 100 + EUR 50 —— 把它们加成一个「总金额」是没有意义的。
    seedOrder(tenantA, userA, departmentA, "PO-C1", "USD", "10", "10", today().plusDays(5), "SUP-A");
    seedOrder(tenantA, userA, departmentA, "PO-C2", "USD", "20", "10", today().plusDays(5), "SUP-A");
    seedOrder(tenantA, userA, departmentA, "PO-C3", "EUR", "10", "5", today().plusDays(5), "SUP-B");

    var summary = as("Bearer tenant-a", () -> orders.list(query())).summary();
    assertThat(summary.amountsByCurrency()).hasSize(2);
    assertThat(summary.amountsByCurrency().get(0).currency()).isEqualTo("EUR");
    assertThat(summary.amountsByCurrency().get(0).amount()).isEqualByComparingTo("50");
    assertThat(summary.amountsByCurrency().get(0).orderCount()).isEqualTo(1);
    assertThat(summary.amountsByCurrency().get(1).currency()).isEqualTo("USD");
    // USD 两张：10×10 + 20×10 = 300
    assertThat(summary.amountsByCurrency().get(1).amount()).isEqualByComparingTo("300");
    assertThat(summary.amountsByCurrency().get(1).orderCount()).isEqualTo(2);
    // ⚠️ **不存在一个跨币种的 totalAmount** —— 汇总里只有分组，没有那个「加起来没意义的数」。
    assertThat(summary.totalAmountNote()).contains("按币种分组");
  }

  @Test
  void 本位币合计用建单时冻结的汇率快照() throws Exception {
    seedOrder(tenantA, userA, departmentA, "PO-F1", "USD", "10", "10", today().plusDays(5), "SUP-A");

    var summary = as("Bearer tenant-a", () -> orders.list(query())).summary();
    assertThat(summary.functionalTotal()).isNotNull();
    assertThat(summary.functionalTotal().functionalCurrency()).isEqualTo("CNY");
    // 100 USD × 7.2 = 720 CNY
    assertThat(summary.functionalTotal().amount()).isEqualByComparingTo("720");
    assertThat(summary.functionalTotal().includedOrderCount()).isEqualTo(1);
    assertThat(summary.functionalTotal().excludedOrderCount()).isZero();
  }

  @Test
  void 没有汇率快照的单不参与本位币合计() throws Exception {
    // 建单时**没有**冻结到汇率（汇率服务当时不可用）⇒ 这张单不参与本位币合计。
    seedOrder(tenantA, userA, departmentA, "PO-F2", "USD", "10", "10", today().plusDays(5), "SUP-A", null);
    // 汇率服务现在也确实是不可达的（说明它当时也没快照）。
    catalogDown.set(true);

    var summary = as("Bearer tenant-a", () -> orders.list(query())).summary();
    // 原币种分组照样给（那是权威口径）；本位币合计不能拿固定汇率凑。
    assertThat(summary.amountsByCurrency()).hasSize(1);
    assertThat(summary.amountsByCurrency().get(0).amount()).isEqualByComparingTo("100");
    assertThat(summary.functionalTotal()).isNull();
  }

  // ==========================================================================
  // 已收数量 / 比例（口径 = 6.3.2.2 到货跟踪）
  // ==========================================================================

  @Test
  void 已收数量来自六点三点二点二_未跟踪时是null不是零() throws Exception {
    seedOrder(tenantA, userA, departmentA, "PO-R1", "USD", "100", "2.5", today().plusDays(5), "SUP-A");
    seedOrder(tenantA, userA, departmentA, "PO-R2", "USD", "100", "2.5", today().plusDays(5), "SUP-A");
    seedArrivalTracking(tenantA, "PO-R1", "100", "40");

    var rows = as("Bearer tenant-a", () -> orders.list(query())).rows();
    var tracked = rows.stream().filter(r -> r.orderNo().equals("PO-R1")).findFirst().orElseThrow();
    var untracked = rows.stream().filter(r -> r.orderNo().equals("PO-R2")).findFirst().orElseThrow();

    // 已跟踪：已收 40 / 可收 100 = 0.4000
    assertThat(tracked.receivedQuantity()).isEqualByComparingTo("40");
    assertThat(tracked.receivableQuantity()).isEqualByComparingTo("100");
    assertThat(tracked.receivedRatio()).isEqualByComparingTo("0.4000");
    assertThat(tracked.receivedQuantitySource()).isEqualTo("ARRIVAL_TRACKING");
    assertThat(tracked.arrivalStatus()).isEqualTo("PARTIALLY_RECEIVED");
    // 08 收货单号只作**来源快照**透出（收货单在 warehouse 服务的独立库里，跨服务只走授权只读端口）。
    assertThat(tracked.warehouseReceiptCode()).isEqualTo("GR-TEST-PO-R1");

    // ⚠️ **没进跟踪的是 null，不是 0** ——
    // 「还没登记到货」与「一件都没到」对采购员的动作含义相反。
    assertThat(untracked.receivedQuantity()).isNull();
    assertThat(untracked.receivedRatio()).isNull();
    assertThat(untracked.receivedQuantitySource()).isEqualTo("NOT_TRACKED");
    assertThat(untracked.arrivalStatus()).isNull();
  }

  // ==========================================================================
  // 逾期标记
  // ==========================================================================

  @Test
  void 逾期标记按承诺日与数据库时钟判定() throws Exception {
    // 承诺日已过 + 尚未全部到货 ⇒ 逾期
    seedOrder(tenantA, userA, departmentA, "PO-O1", "USD", "10", "2.5", today().minusDays(3), "SUP-A");
    // 承诺日已过 + **已全部到货** ⇒ 不逾期
    seedOrder(tenantA, userA, departmentA, "PO-O2", "USD", "10", "2.5", today().minusDays(3), "SUP-A");
    seedArrivalTracking(tenantA, "PO-O2", "10", "10");
    // 承诺日还没到 ⇒ 不逾期
    seedOrder(tenantA, userA, departmentA, "PO-O3", "USD", "10", "2.5", today().plusDays(3), "SUP-A");

    var rows = as("Bearer tenant-a", () -> orders.list(query())).rows();
    var late = rows.stream().filter(r -> r.orderNo().equals("PO-O1")).findFirst().orElseThrow();
    var arrived = rows.stream().filter(r -> r.orderNo().equals("PO-O2")).findFirst().orElseThrow();
    var early = rows.stream().filter(r -> r.orderNo().equals("PO-O3")).findFirst().orElseThrow();

    assertThat(late.overdue()).isTrue();
    assertThat(late.overdueDays()).isEqualTo(3);
    assertThat(arrived.overdue()).isFalse();
    assertThat(arrived.overdueDays()).isNull();
    assertThat(early.overdue()).isFalse();

    // 只看逾期：真的只返回逾期的那张。
    var overdueOnly =
        as(
            "Bearer tenant-a",
            () ->
                orders.list(
                    new PurchaseOrderListQuery(
                        null, null, null, null, null, null, null, null, null, true, null, null,
                        0, 50, userA, departmentA, "ALL")));
    assertThat(overdueOnly.rows()).extracting(PurchaseOrderListRow::orderNo).containsExactly("PO-O1");
    assertThat(overdueOnly.summary().overdueCount()).isEqualTo(1);
  }

  // ==========================================================================
  // 筛选
  // ==========================================================================

  @Test
  void 多维筛选都真的生效() throws Exception {
    UUID supplierA = UUID.randomUUID();
    seedOrder(tenantA, userA, departmentA, "PO-S1", "USD", "10", "2.5", today().plusDays(5), "SUP-A");
    seedOrder(tenantA, userA, departmentA, "PO-S2", "EUR", "20", "1.5", today().plusDays(9), "SUP-B");

    var byStatus =
        as(
            "Bearer tenant-a",
            () ->
                orders.list(
                    new PurchaseOrderListQuery(
                        null, "DRAFT", null, null, null, null, null, null, null, null, null, null,
                        0, 50, userA, departmentA, "ALL")));
    assertThat(byStatus.rows()).hasSize(2);

    var byCurrency =
        as(
            "Bearer tenant-a",
            () ->
                orders.list(
                    new PurchaseOrderListQuery(
                        null, null, null, "EUR", null, null, null, null, null, null, null, null,
                        0, 50, userA, departmentA, "ALL")));
    assertThat(byCurrency.rows()).extracting(PurchaseOrderListRow::orderNo).containsExactly("PO-S2");

    var byKeyword =
        as(
            "Bearer tenant-a",
            () ->
                orders.list(
                    new PurchaseOrderListQuery(
                        null, null, null, null, null, null, null, null, null, null, "SUP-B", null,
                        0, 50, userA, departmentA, "ALL")));
    assertThat(byKeyword.rows()).extracting(PurchaseOrderListRow::orderNo).containsExactly("PO-S2");

    var byWarehouse =
        as(
            "Bearer tenant-a",
            () ->
                orders.list(
                    new PurchaseOrderListQuery(
                        null, null, warehouseId, null, null, null, null, null, null, null, null, null,
                        0, 50, userA, departmentA, "ALL")));
    assertThat(byWarehouse.rows()).hasSize(2);

    var byPromiseRange =
        as(
            "Bearer tenant-a",
            () ->
                orders.list(
                    new PurchaseOrderListQuery(
                        null, null, null, null, null, null, null, today().plusDays(8), today().plusDays(10),
                        null, null, null, 0, 50, userA, departmentA, "ALL")));
    assertThat(byPromiseRange.rows()).extracting(PurchaseOrderListRow::orderNo).containsExactly("PO-S2");
  }

  @Test
  void 非法状态与非法币种被拒() {
    assertThatThrownBy(
            () ->
                as(
                    "Bearer tenant-a",
                    () ->
                        orders.list(
                            new PurchaseOrderListQuery(
                                null, "NOT_A_STATUS", null, null, null, null, null, null, null, null,
                                null, null, 0, 50, userA, departmentA, "ALL"))))
        .hasStackTraceContaining("采购单状态只能是");
    assertThatThrownBy(
            () ->
                as(
                    "Bearer tenant-a",
                    () ->
                        orders.list(
                            new PurchaseOrderListQuery(
                                null, null, null, "US", null, null, null, null, null, null, null, null,
                                0, 50, userA, departmentA, "ALL"))))
        .hasStackTraceContaining("币种必须是三位大写字母");
  }

  // ==========================================================================
  // 数据范围
  // ==========================================================================

  @Test
  void 数据范围self只看得到本人负责的单() throws Exception {
    seedOrder(tenantA, userA, departmentA, "PO-D1", "USD", "10", "2.5", today().plusDays(5), "SUP-A");
    seedOrder(tenantA, userC, departmentA, "PO-D2", "USD", "10", "2.5", today().plusDays(5), "SUP-A");

    // ALL 范围：两张都看得到。
    assertThat(as("Bearer tenant-a", () -> orders.list(query())).rows()).hasSize(2);
    // SELF 范围（另一个用户 userC）：只看得到自己负责的那张。
    var selfRows =
        as("Bearer self-a", () -> orders.list(query("SELF", userC))).rows();
    assertThat(selfRows).extracting(PurchaseOrderListRow::orderNo).containsExactly("PO-D2");
  }

  // ==========================================================================
  // 批量操作：催单 / 关闭
  // ==========================================================================

  @Test
  void 催单留痕且不改承诺到货日() throws Exception {
    UUID id = seedOrder(tenantA, userA, departmentA, "PO-U1", "USD", "10", "2.5", today().plusDays(5), "SUP-A");

    var result =
        asWrite("Bearer tenant-a", () -> orders.urge(List.of(id), "供应商还没回复", userA, departmentA, "ALL"));
    assertThat(result.requested()).isEqualTo(1);
    assertThat(result.succeeded()).isEqualTo(1);
    assertThat(result.failures()).isEmpty();

    var row = as("Bearer tenant-a", () -> orders.list(query())).rows().get(0);
    assertThat(row.urgeCount()).isEqualTo(1);
    assertThat(row.lastUrgedAt()).isNotNull();

    // 留痕在库里，且承诺到货日没被动过。
    Long events =
        as("Bearer tenant-a", () -> sql.fetchOne("select count(*) from purchase_order_event where event_type='URGED'").get(0, Long.class));
    assertThat(events).isEqualTo(1);
    assertThat(row.promisedArrivalDate()).isEqualTo(today().plusDays(5));
  }

  @Test
  void 关闭必须留理由_关闭后整单冻结() throws Exception {
    UUID id = seedOrder(tenantA, userA, departmentA, "PO-K1", "USD", "10", "2.5", today().plusDays(5), "SUP-A");

    assertThatThrownBy(
            () -> asWrite("Bearer tenant-a", () -> orders.close(List.of(id), "  ", userA, departmentA, "ALL")))
        .hasStackTraceContaining("关闭采购单必须填理由");

    var result =
        asWrite("Bearer tenant-a", () -> orders.close(List.of(id), "供应商停业，改用别家", userA, departmentA, "ALL"));
    assertThat(result.succeeded()).isEqualTo(1);

    var row = as("Bearer tenant-a", () -> orders.list(query())).rows().get(0);
    assertThat(row.status()).isEqualTo("CLOSED");

    // 再关一次：失败并说明原因（而不是整批一个布尔）。
    var again =
        asWrite("Bearer tenant-a", () -> orders.close(List.of(id), "再关一次", userA, departmentA, "ALL"));
    assertThat(again.succeeded()).isZero();
    assertThat(again.failures()).hasSize(1);
    assertThat(again.failures().get(0).reason()).contains("已经关闭");
  }

  @Test
  void 已关闭的采购单不许再改行() throws Exception {
    UUID id = seedOrder(tenantA, userA, departmentA, "PO-K2", "USD", "10", "2.5", today().plusDays(5), "SUP-A");
    asWrite("Bearer tenant-a", () -> orders.close(List.of(id), "停业", userA, departmentA, "ALL"));

    assertThatThrownBy(
            () ->
                as(
                    "Bearer tenant-a",
                    () ->
                        sql.execute(
                            "insert into purchase_order_line(id,tenant_id,order_id,line_no,sku_id,sku_code,"
                                + " sku_name,quantity,unit_code,unit_price,price_source,created_at,updated_at)"
                                + " values (?::uuid,?::uuid,?::uuid,9,?::uuid,'SKU-X','X',?::numeric,'',"
                                + " ?::numeric,'MANUAL',now(),now())",
                            UUID.randomUUID(),
                            tenantA,
                            id,
                            UUID.randomUUID(),
                            new BigDecimal("1"),
                            new BigDecimal("1"))))
        .hasStackTraceContaining("不允许再改它的行");
  }

  // ==========================================================================
  // 转单：6.2.2.1 的转单事实 → 采购单
  // ==========================================================================

  @Test
  void 由转单事实生成采购单() throws Exception {
    UUID supplier = UUID.randomUUID();
    var seeded = seedConversion(tenantA, "CV1", "USD", "2.5", today().plusDays(7), supplier);

    var result =
        asWrite(
            "Bearer tenant-a",
            () -> orders.createFromConversions(List.of(seeded.conversionId()), userA, departmentA, "Bearer tenant-a"));

    assertThat(result.orderId()).isNotNull();
    assertThat(result.orderNo()).startsWith("PO-");
    assertThat(result.lineCount()).isEqualTo(1);
    assertThat(result.totalAmount()).isEqualByComparingTo("250");
    assertThat(result.currency()).isEqualTo("USD");
    assertThat(result.functionalCurrency()).isEqualTo("CNY");
    assertThat(result.fxSnapshotStatus()).isEqualTo("CAPTURED");
    assertThat(result.rejected()).isEmpty();

    // 真的进了列表：金额与行数由 DB 从行表算出来。
    var row =
        as("Bearer tenant-a", () -> orders.list(query())).rows().stream()
            .filter(r -> r.orderNo().equals(result.orderNo()))
            .findFirst()
            .orElseThrow();
    assertThat(row.totalAmount()).isEqualByComparingTo("250");
    assertThat(row.lineCount()).isEqualTo(1);
    assertThat(row.skuCount()).isEqualTo(1);
    assertThat(row.status()).isEqualTo("DRAFT");
    assertThat(row.promisedArrivalDate()).isEqualTo(today().plusDays(7));

    // 汇率是**建单那一刻**问 catalog 的，且问的是「当天或之前」的那一条。
    assertThat(lastRateQuery.get()).contains("baseCurrency=USD").contains("quoteCurrency=CNY");
  }

  @Test
  void 没有预估单价的转单被拒_不静默丢弃() throws Exception {
    UUID supplier = UUID.randomUUID();
    var seeded = seedConversion(tenantA, "CV2", "USD", null, today().plusDays(7), supplier);

    var result =
        asWrite(
            "Bearer tenant-a",
            () -> orders.createFromConversions(List.of(seeded.conversionId()), userA, departmentA, "Bearer tenant-a"));

    assertThat(result.orderId()).isNull();
    assertThat(result.rejected()).hasSize(1);
    assertThat(result.rejected().get(0).reasonCode()).isEqualTo("UNIT_PRICE_MISSING");
  }

  @Test
  void 跨供应商不合单() throws Exception {
    UUID supplierA = UUID.randomUUID();
    UUID supplierB = UUID.randomUUID();
    var first = seedConversion(tenantA, "CV3", "USD", "2.5", today().plusDays(7), supplierA);
    var second = seedConversion(tenantA, "CV4", "USD", "3.0", today().plusDays(7), supplierB);

    var result =
        asWrite(
            "Bearer tenant-a",
            () ->
                orders.createFromConversions(
                    List.of(first.conversionId(), second.conversionId()),
                    userA,
                    departmentA,
                    "Bearer tenant-a"));

    assertThat(result.lineCount()).isEqualTo(1);
    assertThat(result.rejected()).hasSize(1);
    assertThat(result.rejected().get(0).reasonCode()).isEqualTo("SUPPLIER_MISMATCH");
  }

  @Test
  void 同一条转单事实不能转两次() throws Exception {
    UUID supplier = UUID.randomUUID();
    var seeded = seedConversion(tenantA, "CV5", "USD", "2.5", today().plusDays(7), supplier);

    asWrite(
        "Bearer tenant-a",
        () -> orders.createFromConversions(List.of(seeded.conversionId()), userA, departmentA, "Bearer tenant-a"));
    var second =
        asWrite(
            "Bearer tenant-a",
            () ->
                orders.createFromConversions(
                    List.of(seeded.conversionId()), userA, departmentA, "Bearer tenant-a"));

    // 第二次转不出来（转单事实已被消费）⇒ 如实报「找不到」，不生成第二张单。
    assertThat(second.orderId()).isNull();
    assertThat(second.rejected()).hasSize(1);
    assertThat(second.rejected().get(0).reasonCode()).isEqualTo("CONVERSION_ALREADY_USED");
  }

  // ==========================================================================
  // 导出
  // ==========================================================================

  @Test
  void 导出与列表同一套筛选条件() throws Exception {
    seedOrder(tenantA, userA, departmentA, "PO-E1", "USD", "10", "2.5", today().plusDays(5), "SUP-A");
    seedOrder(tenantA, userA, departmentA, "PO-E2", "EUR", "20", "1.5", today().plusDays(5), "SUP-B");

    var export = as("Bearer tenant-a", () -> orders.exportCsv(query()));
    assertThat(export.rowCount()).isEqualTo(2);
    assertThat(export.truncated()).isFalse();
    assertThat(export.csv()).contains("采购单号,状态,供应商编码");
    assertThat(export.csv()).contains("PO-E1").contains("PO-E2");

    // 同样的筛选条件 ⇒ 导出的行数与列表一致。
    var byCurrency =
        as(
            "Bearer tenant-a",
            () ->
                orders.exportCsv(
                    new PurchaseOrderListQuery(
                        null, null, null, "EUR", null, null, null, null, null, null, null, null,
                        0, 10000, userA, departmentA, "ALL")));
    assertThat(byCurrency.rowCount()).isEqualTo(1);
    assertThat(byCurrency.csv()).contains("PO-E2").doesNotContain("PO-E1");
  }

  // ==========================================================================
  // HTTP 层
  // ==========================================================================

  @Test
  void http层列表与导出可用() throws Exception {
    seedOrder(tenantA, userA, departmentA, "PO-H1", "USD", "10", "2.5", today().plusDays(5), "SUP-A");

    http.perform(get("/trade/purchase-orders").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.rows[0].orderNo").value("PO-H1"))
        .andExpect(jsonPath("$.dataScope").value("ALL"));

    http.perform(get("/trade/purchase-orders/capabilities").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.bulkOperations.length()").value(3));

    http.perform(get("/trade/purchase-orders/filters").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.currencies[0]").value("USD"));

    var export =
        http.perform(get("/trade/purchase-orders/export").header("Authorization", "Bearer tenant-a"))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Export-Truncated", "false"))
            .andReturn().getResponse().getContentAsString();
    assertThat(export).contains("PO-H1");

    // 没带令牌 ⇒ 401（租户与权限都不来自客户端头）。
    http.perform(get("/trade/purchase-orders")).andExpect(status().isUnauthorized());
  }
}
