package com.starcon.warehouse;

import static com.starcon.warehouse.GoodsReceiptModels.*;
import static com.starcon.warehouse.InventoryLedgerModels.*;

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
 * 8.2.1.1 收货单。
 *
 * <p>命名空间 {@code /warehouse/goods-receipts}：与总览、明细、台账、核对、调拨同一 {@code
 * /warehouse} 前缀，浏览器只经 /api/warehouse 代理访问。
 * 刻意**不**挂在 {@code /inventory/} 下面 —— 8.2 入库作业是 warehouse 的现场作业系统，
 * 而 {@code /inventory} 是 07 库存核算的账本视图；收货单既产生账本、又不只是账本。
 *
 * <p>权限码沿用 {@code warehouse.}：读用 {@code warehouse.inventory.read}，
 * 全部写动作（建单 / 改单 / 实收 / 关单）挂 {@code warehouse.inventory.update} ——
 * 收货是真实入库，权限等级与调拨发货同档。
 *
 * <p>租户**不**来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 客户端伪造的 {@code X-Tenant-Id} 进不来也改不动它。
 *
 * <p>动作端点是 **POST**（{@code /receive}、{@code /close}）：
 * 它们都是「发生一次业务事件」，用 PUT 会被 HTTP 语义读成「把单据设置成某个状态」，
 * 而实际发生的是库存移动 + 分录落库。
 */
@RestController
@RequestMapping("/warehouse/goods-receipts")
public class GoodsReceiptController {

  private final AccessService access;
  private final GoodsReceiptService receipts;
  private final InventoryLedgerService ledger;

  public GoodsReceiptController(
      AccessService access, GoodsReceiptService receipts, InventoryLedgerService ledger) {
    this.access = access;
    this.receipts = receipts;
    this.ledger = ledger;
  }

  /** 统一入口：先向 identity 确认身份与权限（租户**不**来自任何客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "warehouse." + permission);
    return access.run(actor, "warehouse." + permission, () -> work.apply(actor));
  }

  /**
   * 收货能力：四类来源的接入情况、四态状态机的合法转换、七类差异，以及三条口径说明。
   *
   * <p>前端据此渲染来源下拉与动作按钮 —— 未交付的来源**显式报不可用并给出原因**，
   * 而不是给一个点下去没反应的选项。
   */
  @GetMapping("/capabilities")
  public GoodsReceiptCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "inventory.read", a -> receipts.capabilities());
  }

  /** 收货单列表 + 全量汇总（**合计不受分页影响**）+ 状态/来源/仓库下拉。 */
  @GetMapping
  public GoodsReceiptPage page(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID warehouseId,
      @RequestParam(required = false) List<String> status,
      @RequestParam(required = false) List<String> sourceType,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String sort,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "inventory.read",
        a ->
            receipts.page(
                new GoodsReceiptQuery(warehouseId, status, sourceType, keyword, sort, page, size)));
  }

  /**
   * 收货单详情：头 + 明细 + 逐笔实收 + 差异记录 + 当前状态的合法下一步。
   *
   * <p>跨租户在 RLS 下查不到 → 404（403 会泄露「这张单存在」）。
   */
  @GetMapping("/{receiptId}")
  public GoodsReceiptDetail detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID receiptId) {
    return call(auth, "inventory.read", a -> receipts.detail(receiptId));
  }

  /**
   * 本单的库存分录 —— **复用 7.1.2.1 台账**，不另写一套流水查询。
   *
   * <p>归属口径与局限随响应返回：拒收量**不在分录里**（它从没进过库存），
   * 不说明的话「预期 100、实收 60、拒收 10、分录只有 +60」会被读成「30 件的账去哪了」。
   */
  @GetMapping("/{receiptId}/movements")
  public GoodsReceiptMovements movements(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID receiptId,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @RequestParam(required = false) String sort,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(
        auth,
        "inventory.read",
        a -> {
          var head = receipts.detail(receiptId);
          var movements =
              ledger.page(
                  new InventoryLedgerQuery(
                      InventoryController.moment(from),
                      InventoryController.moment(to),
                      null,
                      null,
                      List.of("RECEIPT"),
                      List.of("GOODS_RECEIPT"),
                      null,
                      sort,
                      page,
                      size,
                      a.userId(),
                      InventoryController.selfOnly(a, "warehouse.inventory.read")));
          return new GoodsReceiptMovements(
              head.receipt().id(),
              head.receipt().receiptCode(),
              "ORDER_GRANULAR",
              "分录按本单聚合：只看 kind 为 RECEIPT 且来源单据为本收货单的分录。"
                  + "拒收量不在分录里 —— 它从不写分录、不进库存余额，"
                  + "所以「实收 + 拒收」与分录金额之差正是那部分没入库的货。"
                  + "剩余未到齐的量同样不在分录里（货还没到），要看待办请看本单的还差量列。",
              movements);
        });
  }

  /** 建单（草稿）。用 POST：它真的落库；草稿不动库存。 */
  @PostMapping
  public GoodsReceiptSaved create(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody GoodsReceiptCreateRequest request) {
    return call(
        auth,
        "inventory.update",
        a -> receipts.create(a.tenantId(), request == null ? emptyCreate() : request));
  }

  /** 改单：仅草稿可改，整份替换明细。实收过之后一行都不让改。 */
  @PutMapping("/{receiptId}")
  public GoodsReceiptSaved update(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID receiptId,
      @Valid @RequestBody GoodsReceiptUpdateRequest request) {
    return call(
        auth,
        "inventory.update",
        a -> receipts.update(receiptId, request == null ? emptyUpdate() : request));
  }

  /**
   * 实收：逐笔落事件、转入待检或可售 bucket、写 RECEIPT 入库分录、登记差异。
   *
   * <p>可分次收货（蓝图：「部分收」是一张单上的常态）。实收与库存分录在同一事务内完成。
   */
  @PostMapping("/{receiptId}/receive")
  public GoodsReceiptSaved receive(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID receiptId,
      @Valid @RequestBody GoodsReceiptReceiveRequest request) {
    return call(
        auth,
        "inventory.update",
        a -> receipts.receive(receiptId, request == null ? emptyReceive() : request));
  }

  /** 关单：把剩余未到齐如实记为少收，然后结案。已关闭是终态。 */
  @PostMapping("/{receiptId}/close")
  public GoodsReceiptSaved close(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID receiptId,
      @Valid @RequestBody GoodsReceiptCloseRequest request) {
    return call(
        auth,
        "inventory.update",
        a -> receipts.close(receiptId, request == null ? emptyClose() : request));
  }

  // 空请求体落到这里：{@code @Valid} 对 null 不生效，必须由服务层报出可读错误
  // （例如「缺少 expectedVersion」），而不是 NPE。

  private static GoodsReceiptCreateRequest emptyCreate() {
    return new GoodsReceiptCreateRequest(null, null, null, null, null, null, null, null, List.of());
  }

  private static GoodsReceiptUpdateRequest emptyUpdate() {
    return new GoodsReceiptUpdateRequest(null, null, null, null, null, List.of());
  }

  private static GoodsReceiptReceiveRequest emptyReceive() {
    return new GoodsReceiptReceiveRequest(
        null, null, null, null, null, List.of(), List.of());
  }

  private static GoodsReceiptCloseRequest emptyClose() {
    return new GoodsReceiptCloseRequest(null, null, null);
  }
}