package com.starcon.warehouse;

import static com.starcon.warehouse.LegalEntityTransferModels.*;

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
 * 7.5.2.1 跨法人调拨。
 *
 * <p>命名空间 {@code /warehouse/inventory/cross-entity-transfers}：与调拨单、总览、
 * 台账同一前缀，浏览器只经 /api/warehouse 代理访问。
 *
 * <p>刻意<b>不</b>复用 {@code /warehouse/inventory/transfers} 下的任何路径：
 * 那条前缀是 7.5.1.1 的普通调拨（货权前后同一个法人、不定价、无内部结算凭据），
 * 两者的状态机与输出都不同，挂在同一前缀下会让「跨法人」看起来只是普通调拨的一个筛选项 ——
 * 而这正是蓝图点名要避免的「当作普通同仓调拨」。
 *
 * <p>权限码沿用 {@code warehouse.}：读用 {@code warehouse.inventory.read}，
 * 写动作挂 {@code warehouse.inventory.update} —— 跨法人调拨动的是真实库存**并改变货权归属**，
 * 比普通调拨更敏感。
 *
 * <p><b>双方法人授权（17 权限）尚未交付</b>：本模块当前只校验「同租户 + 租户级权限码」，
 * 不校验调用者是否被授权代表某个法人。两个方向必须由两个不同自然人确认这一点，
 * 由 DB 触发器 {@code guard_cross_entity_two_parties} 钉死。详见
 * {@link LegalEntityTransferService} 的类注释与详情响应里的 {@code authorizationNote}。
 *
 * <p>租户**不**来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 *
 * <p>动作端点是 **POST**：它们都是「发生一次业务事件」（一次确认 / 一次发出 / 一次实收），
 * 用 PUT 会被 HTTP 语义读成「把单据设置成某个状态」，而实际发生的是库存移动 + 货权转移 + 分录落库。
 */
@RestController
@RequestMapping("/warehouse/inventory/cross-entity-transfers")
public class LegalEntityTransferController {

  private final AccessService access;
  private final LegalEntityTransferService transfers;
  private final InventoryLedgerService ledger;

  public LegalEntityTransferController(
      AccessService access, LegalEntityTransferService transfers, InventoryLedgerService ledger) {
    this.access = access;
    this.transfers = transfers;
    this.ledger = ledger;
  }

  /**
   * 统一入口：先向 identity 确认身份与权限（租户**不**来自任何客户端头），再在事务里绑定 RLS 租户上下文。
   *
   * <p>写动作走 {@link #write}：它们需要操作人 id —— 双方确认必须能区分是谁签的，
   * 所以这里不像只读端点那样丢掉 actor。
   */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "warehouse." + permission);
    return access.run(actor, "warehouse." + permission, () -> work.apply(actor));
  }

  private <T> T write(String bearer, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "warehouse.inventory.update");
    return access.run(actor, "warehouse.inventory.update", () -> work.apply(actor));
  }

  /** 列表 + 全量汇总（**合计不受分页影响**）+ 状态/法人/仓库下拉。 */
  @GetMapping
  public LegalEntityTransferPage page(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID fromLegalEntityId,
      @RequestParam(required = false) UUID toLegalEntityId,
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
                new LegalEntityTransferQuery(
                    fromLegalEntityId, toLegalEntityId, fromWarehouseId, toWarehouseId,
                    skuId, status, keyword, sort, page, size)));
  }

  /**
   * 详情：头 + 明细（含转移单价）+ 双方确认留痕 + 结算凭据。
   *
   * <p>{@code authorizationNote} / {@code ownershipNote} / {@code pricingNote} 必须随响应返回并
   * 在界面上显示：不说明的话「能确认」会被当成「有权代表该法人」，
   * 「发出即开凭据」会被当成「货已经归转入方了」。
   *
   * <p>跨租户在 RLS 下查不到 → 404（403 会泄露「这张单存在」）。
   */
  @GetMapping("/{transferId}")
  public LegalEntityTransferDetail detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID transferId) {
    return call(auth, "inventory.read", a -> transfers.detail(transferId));
  }

  /** 本单的库存分录 —— 复用 7.1.2.1 台账，不另写一套流水查询。 */
  @GetMapping("/{transferId}/movements")
  public LegalEntityTransferMovements movements(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID transferId,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @RequestParam(required = false) String sort,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(
        auth,
        "inventory.read",
        a -> {
          var head = transfers.detail(transferId);
          var movements =
              ledgerMovements(a, from, to, sort, page, size);
          return new LegalEntityTransferMovements(
              head.transfer().id(),
              head.transfer().transferCode(),
              "ORDER_GRANULAR",
              "分录按本单聚合：只看 kind 为 TRANSFER_OUT / TRANSFER_IN 且来源单据为本跨法人调拨单的分录。"
                  + "发出那笔记在转出法人名下、实收那笔记在转入法人名下 —— 两条分录的余额行属于不同法人，"
                  + "这正是货权转移在台账里的样子。仍在途的数量不在分录里，要看待途请看本单的在途列。",
              movements);
        });
  }

  private com.starcon.warehouse.InventoryLedgerModels.InventoryLedgerPage ledgerMovements(
      AccessService.Access a, String from, String to, String sort, int page, int size) {
    return ledger.page(
        new InventoryLedgerModels.InventoryLedgerQuery(
            InventoryController.moment(from),
            InventoryController.moment(to),
            null,
            null,
            List.of("TRANSFER_OUT", "TRANSFER_IN"),
            List.of("CROSS_ENTITY_TRANSFER"),
            null,
            sort,
            page,
            size,
            a.userId(),
            InventoryController.selfOnly(a, "warehouse.inventory.read")));
  }

  /**
   * 内部结算凭据的取数形状 —— 给 12 财务的交接面。
   *
   * <p>凭据在<b>发出</b>时开具，因此未发运的单这里是 404 而不是空壳：
   * 「还没发运所以还没有凭据」和「凭据存在但金额为 0」必须能分开。
   */
  @GetMapping("/{transferId}/settlement")
  public LegalEntityTransferSettlementHandoff settlement(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID transferId) {
    return call(auth, "inventory.read", a -> transfers.settlement(transferId));
  }

  /** 建单（草稿）。草稿不定价确认、不动库存。 */
  @PostMapping
  public LegalEntityTransferSaved create(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody LegalEntityTransferCreateRequest request) {
    return write(
        auth, a -> transfers.create(a.tenantId(), a.userId(), request == null ? emptyCreate() : request));
  }

  /** 改单：仅草稿可改，整份替换明细与转移定价。发出之后一行都不让改。 */
  @PutMapping("/{transferId}")
  public LegalEntityTransferSaved update(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID transferId,
      @Valid @RequestBody LegalEntityTransferUpdateRequest request) {
    return write(
        auth, a -> transfers.update(transferId, a.userId(), request == null ? emptyUpdate() : request));
  }

  /**
   * 转出方确认（草稿 → 待转入方确认）。
   *
   * <p>与 {@code /target-confirm} 是<b>两个独立端点</b>而不是一个带参数的：
   * 让调用方在请求体里声明「我代表哪一方」，等于让它可以自称另一方。
   */
  @PostMapping("/{transferId}/source-confirm")
  public LegalEntityTransferSaved sourceConfirm(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID transferId,
      @Valid @RequestBody LegalEntityTransferApproveRequest request) {
    return write(
        auth,
        a ->
            transfers.sourceConfirm(
                transferId, a.userId(), request == null ? emptyApprove() : request));
  }

  /** 转入方确认（待转入方确认 → 双方已确认）。必须与转出方确认不是同一个人。 */
  @PostMapping("/{transferId}/target-confirm")
  public LegalEntityTransferSaved targetConfirm(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID transferId,
      @Valid @RequestBody LegalEntityTransferApproveRequest request) {
    return write(
        auth,
        a ->
            transfers.targetConfirm(
                transferId, a.userId(), request == null ? emptyApprove() : request));
  }

  /** 发出：扣转出法人余额、挂在途、开具内部结算凭据。整单发运，不拆批次。 */
  @PostMapping("/{transferId}/ship")
  public LegalEntityTransferSaved ship(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID transferId,
      @Valid @RequestBody LegalEntityTransferShipRequest request) {
    return write(
        auth, a -> transfers.ship(transferId, a.userId(), request == null ? emptyShip() : request));
  }

  /** 实收：转入方入库（余额行落在转入法人名下 = 货权转移）、冲减在途、确认凭据。 */
  @PostMapping("/{transferId}/receive")
  public LegalEntityTransferSaved receive(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID transferId,
      @Valid @RequestBody LegalEntityTransferReceiveRequest request) {
    return write(
        auth, a -> transfers.receive(transferId, a.userId(), request == null ? emptyReceive() : request));
  }

  /** 结案：发出过但收不到的部分如实记为差异，在途归零。结算凭据不因此作废（部分货权确实转移了）。 */
  @PostMapping("/{transferId}/close")
  public LegalEntityTransferSaved close(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID transferId,
      @Valid @RequestBody LegalEntityTransferCloseRequest request) {
    return write(
        auth, a -> transfers.close(transferId, a.userId(), request == null ? emptyClose() : request));
  }

  /** 作废：仅发运之前可用。已发运只能结案，不能一笔抹掉货权与凭据。 */
  @PostMapping("/{transferId}/cancel")
  public LegalEntityTransferSaved cancel(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID transferId,
      @Valid @RequestBody LegalEntityTransferCancelRequest request) {
    return write(
        auth, a -> transfers.cancel(transferId, a.userId(), request == null ? emptyCancel() : request));
  }

  // 空请求体落到这里：{@code @Valid} 对 null 不生效，必须由服务层报出可读错误
  // （例如「缺少 expectedVersion」），而不是 NPE。

  private static LegalEntityTransferCreateRequest emptyCreate() {
    return new LegalEntityTransferCreateRequest(
        null, null, null, null, null, null, null, null, null, null, List.of());
  }

  private static LegalEntityTransferUpdateRequest emptyUpdate() {
    return new LegalEntityTransferUpdateRequest(
        null, null, null, null, null, null, null, List.of());
  }

  private static LegalEntityTransferApproveRequest emptyApprove() {
    return new LegalEntityTransferApproveRequest(null, null);
  }

  private static LegalEntityTransferShipRequest emptyShip() {
    return new LegalEntityTransferShipRequest(null, null, null);
  }

  private static LegalEntityTransferReceiveRequest emptyReceive() {
    return new LegalEntityTransferReceiveRequest(null, null, null, null, null, List.of());
  }

  private static LegalEntityTransferCloseRequest emptyClose() {
    return new LegalEntityTransferCloseRequest(null, null, null);
  }

  private static LegalEntityTransferCancelRequest emptyCancel() {
    return new LegalEntityTransferCancelRequest(null, null);
  }
}