package com.starcon.warehouse;

import static com.starcon.warehouse.InventoryLedgerModels.*;
import static com.starcon.warehouse.InventoryTransferModels.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 7.5.1.1 调拨单。
 *
 * <p>命名空间 {@code /warehouse/inventory/transfers}：与总览、明细、台账、核对同一前缀，
 * 浏览器只经 /api/warehouse 代理访问。刻意**不挂在 {@code /warehouse/lots}** 那类下拉参照形状上 ——
 * 那是只读精简清单，与本模块的单据生命周期不是同一个东西。
 *
 * <p>权限码沿用 {@code warehouse.}：读用 {@code warehouse.inventory.read}，
 * 全部写动作（确认 / 发出 / 收货 / 结案）挂 {@code warehouse.inventory.update} ——
 * 调走的是真实库存，比维护一条阈值危险得多。
 *
 * <p>租户**不**来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 *
 * <p>动作端点是 **POST**（{@code /confirm}、{@code /ship}、{@code /receive}、{@code /close}）：
 * 它们都是「发生一次业务事件」，用 PUT 会被 HTTP 语义读成「把单据设置成某个状态」，
 * 而实际发生的是库存移动 + 分录落库。
 */
@RestController
@RequestMapping("/warehouse/inventory/transfers")
public class InventoryTransferController {

  private final AccessService access;
  private final InventoryTransferService transfers;
  private final InventoryLedgerService ledger;

  public InventoryTransferController(
      AccessService access, InventoryTransferService transfers, InventoryLedgerService ledger) {
    this.access = access;
    this.transfers = transfers;
    this.ledger = ledger;
  }

  /** 统一入口：先向 identity 确认身份与权限（租户**不**来自任何客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "warehouse." + permission);
    return access.run(actor, "warehouse." + permission, () -> work.apply(actor));
  }

  /**
   * 调拨单列表 + 全量汇总（**合计不受分页影响**）+ 状态与仓库下拉。
   *
   * <p>行上的五个数量列对应守恒式 {@code 已发出 = 已实收 + 已确认差异 + 仍在途}，
   * 未发出量（请求 − 已发出）不是差异：货从没离开源仓。
   */
  @GetMapping
  public TransferOrderPage page(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID fromWarehouseId,
      @RequestParam(required = false) UUID toWarehouseId,
      @RequestParam(required = false) UUID skuId,
      @RequestParam(required = false) List<String> status,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String sort,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "inventory.read",
        a ->
            transfers.page(
                new TransferOrderQuery(
                    fromWarehouseId, toWarehouseId, skuId, status, keyword, sort, page, size)));
  }

  /**
   * 调拨单详情：头 + 明细 + 每次发出 + 每次收货/差异登记。
   *
   * <p>跨租户在 RLS 下查不到 → 404（403 会泄露「这张单存在」）。
   */
  @GetMapping("/{orderId}")
  public TransferOrderDetail detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID orderId) {
    return call(auth, "inventory.read", a -> transfers.detail(orderId));
  }

  /**
   * 本单的库存分录 —— **复用 7.1.2.1 台账**，不另写一套流水查询。
   *
   * <p>归属口径与局限随响应返回，让调用方不必猜这个数怎么来的。
   * 先确认单据在本租户可见：不存在直接 404，否则一张不存在的单会返回「空流水」，
   * 与「存在但没动过」长得一样。
   */
  @GetMapping("/{orderId}/movements")
  public TransferMovements movements(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID orderId,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @RequestParam(required = false) String sort,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(
        auth,
        "inventory.read",
        a -> {
          var head = transfers.detail(orderId);
          var movements =
              ledger.page(
                  new InventoryLedgerQuery(
                      InventoryController.moment(from),
                      InventoryController.moment(to),
                      null,
                      null,
                      List.of("TRANSFER_OUT", "TRANSFER_IN"),
                      List.of("TRANSFER_ORDER"),
                      null,
                      sort,
                      page,
                      size,
                      a.userId(),
                      InventoryController.selfOnly(a, "warehouse.inventory.read")));
          return new TransferMovements(
              head.order().id(),
              head.order().orderCode(),
              "ORDER_GRANULAR",
              "分录按本单聚合：只看 kind 为 TRANSFER_OUT / TRANSFER_IN 且来源单据为本调拨单的分录。"
                  + "仍在途的数量不在分录里（货权已在发出时离开发货仓），要看待途请看本单的在途列。",
              movements);
        });
  }

  /** 建单（草稿）。用 POST：它真的落库；草稿不动库存，货权在发出那一刻才转移。 */
  @PostMapping
  public TransferOrderSaved create(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody TransferOrderCreateRequest request) {
    return call(
        auth,
        "inventory.update",
        a -> transfers.create(a.tenantId(), request == null ? emptyCreate() : request));
  }

  /** 改单：仅草稿可改，整份替换明细与时间窗。发出之后一行都不让改。 */
  @PutMapping("/{orderId}")
  public TransferOrderSaved update(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID orderId,
      @Valid @RequestBody TransferOrderUpdateRequest request) {
    return call(
        auth,
        "inventory.update",
        a -> transfers.update(orderId, request == null ? emptyUpdate() : request));
  }

  /** 确认（草稿 → 待发）。这是唯一一次「确认」，之后明细锁定。 */
  @PostMapping("/{orderId}/confirm")
  public TransferOrderSaved confirm(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID orderId,
      @Valid @RequestBody TransferOrderActionRequest request) {
    return call(
        auth,
        "inventory.update",
        a -> transfers.confirm(orderId, request == null ? emptyAction() : request));
  }

  /**
   * 发出：减源仓、写 TRANSFER_OUT 分录、挂上在途。**不动目的仓** —— 货还没到。
   *
   * <p>可分批发运：每次发一部分行或每行的一部分量。
   */
  @PostMapping("/{orderId}/ship")
  public TransferOrderSaved ship(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID orderId,
      @Valid @RequestBody TransferShipRequest request) {
    return call(
        auth,
        "inventory.update",
        a -> transfers.ship(orderId, request == null ? emptyShip() : request));
  }

  /** 实收：转入目的仓、写 TRANSFER_IN 分录、逐次冲减在途。可分次收货。 */
  @PostMapping("/{orderId}/receive")
  public TransferOrderSaved receive(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID orderId,
      @Valid @RequestBody TransferReceiveRequest request) {
    return call(
        auth,
        "inventory.update",
        a -> transfers.receive(orderId, request == null ? emptyReceive() : request));
  }

  /** 结案：把已发出但收不到的部分如实记为差异，在途归零。差异原因必填。 */
  @PostMapping("/{orderId}/close")
  public TransferOrderSaved close(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID orderId,
      @Valid @RequestBody TransferCloseRequest request) {
    return call(
        auth,
        "inventory.update",
        a -> transfers.close(orderId, request == null ? emptyClose() : request));
  }

  // 空请求体落到这里：{@code @Valid} 对 null 不生效，必须由服务层报出可读错误
  // （例如「缺少 expectedVersion」），而不是 NPE。

  private static TransferOrderCreateRequest emptyCreate() {
    return new TransferOrderCreateRequest(null, null, null, null, null, null, null, List.of());
  }

  private static TransferOrderUpdateRequest emptyUpdate() {
    return new TransferOrderUpdateRequest(null, null, null, null, null, List.of());
  }

  private static TransferOrderActionRequest emptyAction() {
    return new TransferOrderActionRequest(null, null);
  }

  private static TransferShipRequest emptyShip() {
    return new TransferShipRequest(null, null, null, List.of());
  }

  private static TransferReceiveRequest emptyReceive() {
    return new TransferReceiveRequest(null, null, null, null, null, List.of());
  }

  private static TransferCloseRequest emptyClose() {
    return new TransferCloseRequest(null, null, null);
  }
}