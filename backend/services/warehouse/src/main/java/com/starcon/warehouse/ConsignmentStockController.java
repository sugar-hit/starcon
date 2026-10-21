package com.starcon.warehouse;

import static com.starcon.warehouse.ConsignmentStockModels.*;

import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 7.7.2.2 平台托管与全托管备货。
 *
 * <p>蓝图：「处理平台托管（全托管）下的"备货"语义：发给平台的货进入平台仓但**所有权与结算规则随模式变化**，需与自有仓库存分账；支持备货单、平台仓收发对账、按件/按日结算口径标记。」 输入「店铺经营模式（2.3.2.1）；备货单与发运数据；平台仓库存与结算回执。」 输出「托管备货台账（备货量/平台在库/平台已售/结算未结）；与自有库存的对账结果；结算口径标记供 12 使用。」
 *
 * <p>命名空间沿用 {@code /warehouse/inventory/consignment-stock}，权限码 {@code warehouse.inventory.read} / {@code
 * warehouse.inventory.update}。端点独立成 {@code ConsignmentStockController} 而不是并进 {@code InventoryController}：那是
 * 7.4.2.1 的文件，各车道并行时各改各的才不会撞。**前缀刻意不是 {@code consignment-}**：7.7.1.2 寄售模块已占用
 * {@code /warehouse/inventory/consignments}，两个名字挨太近会让人把寄售协议与平台托管备货单当成同一件事。
 *
 * <p><b>本模块没有「把平台在库并进自有库存」的端点，而且数据库也不让这么算</b>：V244 在 {@code inventory_entry} 上挂了
 * 触发器，平台托管的单据只许从自有库存**出库**（负数），不许把平台在库数加进我方账本或占我方预占。
 *
 * <p><b>四个口径在端点层就分得开</b>：{@code /ledger} 一次返回备货量 / 平台在库 / 平台已售 / 结算未结四个独立字段，
 * 并把**自有库存那一侧并排给出**（{@code ownInventoryOnHandQuantity}），界面上没有把它们相加的合计数。
 */
@RestController
@RequestMapping("/warehouse")
public class ConsignmentStockController {

  private final AccessService access;
  private final ConsignmentStockService consignmentStock;

  public ConsignmentStockController(AccessService access, ConsignmentStockService consignmentStock) {
    this.access = access;
    this.consignmentStock = consignmentStock;
  }

  /** 统一入口：先向 identity 确认身份与权限（租户**不**来自任何客户端头）， 再在一个事务里绑定 RLS 租户上下文执行查询。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "warehouse." + permission);
    return access.run(actor, "warehouse." + permission, () -> work.apply(actor));
  }

  // ------------------------------------------------------------ 能力

  /**
   * 平台托管备货能力：四个口径的定义、结算口径（按件 / 按日）、经营模式、差异口径，
   * 以及 **未接入的上游/下游环节清单**（12 结算、13 利润、平台仓自动采集、9 订单推送）。
   *
   * <p>上游未交付是事实，但必须**显式报出**：靠 404 或空数组让人猜哪一环没通， 迟早会有人把「没数据」当成「没问题」。
   */
  @GetMapping("/inventory/consignment-stock/capabilities")
  public PlatformConsignmentCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "inventory.read", a -> consignmentStock.capabilities());
  }

  /** 店铺经营模式读出口（2.3.2.1，只读）。上游读不到 → 503；没有这家店 → known=false。 */
  @GetMapping("/inventory/consignment-stock/shops/{shopId}")
  public PlatformConsignmentShopMode shopMode(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID shopId) {
    return call(auth, "inventory.read", a -> consignmentStock.shopMode(shopId, auth));
  }

  /** 备货预报预填预览（10.4.2.2，只读）。 */
  @GetMapping("/inventory/consignment-stock/forecast-preview")
  public PlatformConsignmentForecastPreview forecastPreview(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam UUID forecastId) {
    return call(auth, "inventory.read", a -> consignmentStock.forecastPreview(forecastId, auth));
  }

  // ------------------------------------------------------------ 备货单

  /**
   * 建平台托管备货单。
   *
   * <p>建单前会先经 channel 的授权只读接口核对店铺经营模式（2.3.2.1）：自发货模式直接被拒；
   * channel 读不到 → 503（不猜、不用 0 冒充）。
   */
  @PostMapping("/inventory/consignment-stock/orders")
  public PlatformConsignmentOrderDetail create(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody PlatformConsignmentOrderInput input) {
    return call(auth, "inventory.update", a -> consignmentStock.createOrder(input, auth, a.userId()));
  }

  /** 备货单列表（真分页；合计全部由明细与平台回执聚合得出，不另存列）。 */
  @GetMapping("/inventory/consignment-stock/orders")
  public PlatformConsignmentOrderPage orders(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) UUID shopId,
      @RequestParam(required = false) String settlementBasis,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(
        auth,
        "inventory.read",
        a ->
            consignmentStock.orders(status, shopId, settlementBasis, keyword, page, size));
  }

  /** 备货单详情 = 头部 + 明细 + 托管备货台账 + 与自有库存的对账结果 + 审计时间线。 */
  @GetMapping("/inventory/consignment-stock/orders/{orderId}")
  public PlatformConsignmentOrderDetail detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID orderId) {
    return call(auth, "inventory.read", a -> consignmentStock.orderDetail(orderId));
  }

  /** 给草稿备货单补一个 SKU。已发出的备货单不允许改备货量。 */
  @PostMapping("/inventory/consignment-stock/orders/{orderId}/lines")
  public PlatformConsignmentOrderDetail addLine(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID orderId,
      @RequestBody PlatformConsignmentAddLineInput input) {
    return call(
        auth, "inventory.update", a -> consignmentStock.addLine(orderId, input, a.userId()));
  }

  /** 发货：DRAFT → SHIPPED。备货量在此刻成为台账里的「备货量」口径。 */
  @PostMapping("/inventory/consignment-stock/orders/{orderId}/ship")
  public PlatformConsignmentOrder ship(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID orderId,
      @RequestBody(required = false) PlatformConsignmentNoteInput input) {
    return call(auth, "inventory.update", a -> consignmentStock.ship(orderId, input, a.userId()));
  }

  /** 平台确认收货：SHIPPED → RECEIVED。必须先有平台入库回执 —— 我方发货确认不了平台收没收到。 */
  @PostMapping("/inventory/consignment-stock/orders/{orderId}/confirm-receipt")
  public PlatformConsignmentOrder confirmReceipt(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID orderId,
      @RequestBody(required = false) PlatformConsignmentNoteInput input) {
    return call(
        auth, "inventory.update", a -> consignmentStock.confirmReceipt(orderId, input, a.userId()));
  }

  /** 关闭备货单：RECEIVED → CLOSED。结算未结不为 0 时拒绝 —— 结清之前关单，未结就没有单据可追。 */
  @PostMapping("/inventory/consignment-stock/orders/{orderId}/close")
  public PlatformConsignmentOrder close(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID orderId,
      @RequestBody(required = false) PlatformConsignmentNoteInput input) {
    return call(auth, "inventory.update", a -> consignmentStock.close(orderId, input, a.userId()));
  }

  /** 作废备货单：→ CANCELLED。终态不可复活。 */
  @PostMapping("/inventory/consignment-stock/orders/{orderId}/cancel")
  public PlatformConsignmentOrder cancel(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID orderId,
      @RequestBody(required = false) PlatformConsignmentNoteInput input) {
    return call(auth, "inventory.update", a -> consignmentStock.cancel(orderId, input, a.userId()));
  }

  // ------------------------------------------------------------ 平台仓回执

  /** 登记平台仓收发回执 —— 平台在库与平台已售两个口径的**唯一**来源。 */
  @PostMapping("/inventory/consignment-stock/orders/{orderId}/receipts")
  public PlatformConsignmentReceipt recordReceipt(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID orderId,
      @RequestBody PlatformConsignmentReceiptInput input) {
    return call(
        auth, "inventory.update", a -> consignmentStock.recordReceipt(orderId, input, a.userId()));
  }

  /** 平台仓回执列表（真分页；每条都带来源与发生时刻）。 */
  @GetMapping("/inventory/consignment-stock/orders/{orderId}/receipts")
  public PlatformConsignmentReceiptPage receipts(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID orderId,
      @RequestParam(required = false) String receiptType,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(
        auth, "inventory.read", a -> consignmentStock.receipts(orderId, receiptType, page, size));
  }

  // ------------------------------------------------------------ 平台结算回执

  /** 登记平台结算回执（按件 / 按日，口径随回执走但必须与备货单一致）。 */
  @PostMapping("/inventory/consignment-stock/orders/{orderId}/settlement-receipts")
  public PlatformConsignmentSettlementReceipt recordSettlementReceipt(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID orderId,
      @RequestBody PlatformConsignmentSettlementReceiptInput input) {
    return call(
        auth,
        "inventory.update",
        a -> consignmentStock.recordSettlementReceipt(orderId, input, a.userId()));
  }

  /** 平台结算回执列表（真分页）。 */
  @GetMapping("/inventory/consignment-stock/orders/{orderId}/settlement-receipts")
  public PlatformConsignmentSettlementReceiptPage settlementReceipts(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID orderId,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(
        auth, "inventory.read", a -> consignmentStock.settlementReceipts(orderId, page, size));
  }

  // ------------------------------------------------------------ 台账 / 对账 / 口径

  /**
   * 托管备货台账：备货量 / 平台在库 / 平台已售 / 结算未结，**四个口径分列**，
   * 外加自有库存那一侧（并排给出，不合并）。
   */
  @GetMapping("/inventory/consignment-stock/orders/{orderId}/ledger")
  public PlatformConsignmentLedger ledger(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID orderId) {
    return call(auth, "inventory.read", a -> consignmentStock.ledger(orderId));
  }

  /** 与自有库存的对账结果：差异有量、有口径；分账校验给出「平台在库有没有被并进自有库存余额」的结论。 */
  @GetMapping("/inventory/consignment-stock/orders/{orderId}/reconciliation")
  public PlatformConsignmentReconciliation reconciliation(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID orderId) {
    return call(auth, "inventory.read", a -> consignmentStock.reconciliation(orderId));
  }

  /** 结算口径标记（供 12.3.1 结算使用）+ 未结量。平台没给任何回执时未结量是 null 而不是 0。 */
  @GetMapping("/inventory/consignment-stock/orders/{orderId}/settlement-basis")
  public PlatformConsignmentSettlementBasisView settlementBasis(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID orderId) {
    return call(auth, "inventory.read", a -> consignmentStock.settlementBasis(orderId));
  }

  // ------------------------------------------------------------ 审计

  /** 审计时间线：谁建了单、谁发的货、谁登记了平台回执与结算回执（append-only）。 */
  @GetMapping("/inventory/consignment-stock/orders/{orderId}/audit")
  public List<PlatformConsignmentAuditEntry> audit(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID orderId,
      @RequestParam(required = false) Integer limit) {
    return call(auth, "inventory.read", a -> consignmentStock.auditTrail(orderId, limit));
  }
}
