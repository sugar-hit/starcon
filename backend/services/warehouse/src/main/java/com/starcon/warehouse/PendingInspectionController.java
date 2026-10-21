package com.starcon.warehouse;

import static com.starcon.warehouse.PendingInspectionModels.*;

import jakarta.validation.Valid;
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
 * 8.2.2.2 待检处理入口（待检区库存 → 质检 → 质量状态分流）。
 *
 * <p>命名空间 {@code /warehouse/pending-inspections}。<b>刻意不是 {@code goods-receipts} 的子路径</b>：
 * 待检处理是一张**独立的作业单**，它有质检、有结论、有处置 —— 挂在收货单底下会让
 * 「这张收货单还没检完」与「这张收货单还没收完」在契约上分不清，
 * 而现场看板正是按「还有多少货压在待检区」排班的。
 *
 * <p>八个形状，刻意逐个列出、不做通配：
 *
 * <ul>
 *   <li>{@code capabilities} 能力与上下游声明（含「待检品不可售」与四条硬规则）
 *   <li>{@code GET /stock} <b>待检库存清单</b>（真分页 / 真筛选 / 汇总）
 *   <li>{@code GET /options} 建单与处置的下拉（仓库 / 质量状态 / 库位 / 质检模板）
 *   <li>{@code POST /} 建待检处理单（<b>不动库存</b>）
 *   <li>{@code GET /} 列表（真分页 / 真筛选 / 排序白名单 / 汇总）
 *   <li>{@code GET /{id}} 详情（单头 + 来源库存 + 处置明细 + 事件台账 + <b>库存分录</b> + 可售量前后对照）
 *   <li>{@code POST /{id}/request-inspection} <b>发起质检任务</b>（6.4.2.1，trade 服务）
 *   <li>{@code POST /{id}/record-result} 录入质检结果（<b>回读</b> trade 的三量，不让调用方再敲一遍）
 *   <li>{@code POST /{id}/dispose} <b>处置决定</b>（本模块唯一动库存的动作）
 *   <li>{@code POST /{id}/cancel} 取消（已发起质检的会同时作废 trade 侧的质检单）
 * </ul>
 *
 * <p>刻意<b>没有</b>任何 PUT / DELETE：处置之后要改去向，用「取消重开」而不是原地改 ——
 * 因为一旦处置，货已经在新库位上（或已经离仓），改单据不改事实就是账实不符
 * （V252 的守卫从数据库层挡住）。
 *
 * <p>权限码用 {@code warehouse.pending-inspection.read} / {@code warehouse.pending-inspection.update}：
 * 处置会真的把货从一个质量状态挪到另一个质量状态，不是读权限能带过去的。
 */
@RestController
@RequestMapping("/warehouse/pending-inspections")
public class PendingInspectionController {

  private final AccessService access;
  private final PendingInspectionService pending;

  public PendingInspectionController(AccessService access, PendingInspectionService pending) {
    this.access = access;
    this.pending = pending;
  }

  /** 统一入口：先确认身份与权限（租户<b>不</b>来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "warehouse." + permission);
    return access.run(actor, "warehouse." + permission, () -> work.apply(actor));
  }

  /**
   * 能力声明：跨服务边界、「待检品不可售」这条硬约束与上下游可用性。
   *
   * <p>存在的理由与既有 warehouse 模块一致：把「发起质检走哪条通道」「不合格品能不能转可售」
   * 「6.4.2.2 不良品处理交付后谁接管」显式报出来，而不是让调用方从 400 或空数组里猜。
   */
  @GetMapping("/capabilities")
  public PendingInspectionCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "pending-inspection.read", a -> pending.capabilities());
  }

  /** 待检库存清单（真分页 + 真筛选 + 汇总）。 */
  @GetMapping("/stock")
  public PendingInspectionStockPage stock(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID warehouseId,
      @RequestParam(required = false) UUID locationId,
      @RequestParam(required = false) UUID skuId,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "ON_HAND") String sort,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "pending-inspection.read",
        a -> pending.stock(warehouseId, locationId, skuId, keyword, sort, page, size));
  }

  /** 建单与处置需要的下拉（仓库 / 质量状态 / 库位 / 质检模板）。 */
  @GetMapping("/options")
  public PendingInspectionOptions options(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID warehouseId) {
    return call(auth, "pending-inspection.read", a -> pending.options(warehouseId));
  }

  /** 建待检处理单（<b>不动库存</b>）。 */
  @PostMapping
  public PendingInspectionTaskRow create(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody PendingInspectionTaskCreateInput input) {
    return call(auth, "pending-inspection.update", a -> pending.create(input, a.userId()));
  }

  /** 待检处理单列表（真分页 + 真筛选 + 排序白名单 + 汇总）。 */
  @GetMapping
  public PendingInspectionTaskPage page(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) UUID warehouseId,
      @RequestParam(required = false) UUID skuId,
      @RequestParam(required = false) UUID receiptId,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "CREATED_AT") String sort,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "pending-inspection.read",
        a ->
            pending.page(status, warehouseId, skuId, receiptId, keyword, sort, page, size));
  }

  /** 待检处理单详情（含库存分录与可售量前后对照）。 */
  @GetMapping("/{id}")
  public PendingInspectionTaskDetail detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "pending-inspection.read", a -> pending.detail(id));
  }

  /**
   * 发起质检任务（6.4.2.1 质检单，在 trade 服务）。
   *
   * <p>本服务只给 trade 三样它真正知道的事实：哪个 SKU、来自哪张收货单的哪一行、本次检多少。
   * 抽样规则与检验项由 trade 的模板决定 —— 本服务不替它猜一个默认模板。
   */
  @PostMapping("/{id}/request-inspection")
  public PendingInspectionTaskRow requestInspection(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody PendingInspectionRequestInspectionInput input) {
    return call(
        auth, "pending-inspection.update", a -> pending.requestInspection(id, input, a.userId(), auth));
  }

  /**
   * 录入质检结果。
   *
   * <p><b>入参刻意没有数量字段</b>：质检结论的唯一来源是 trade 的质检单，
   * 本服务在录入时回读那张单的三量。让操作员在这里再敲一遍数字，
   * 等于让同一个结论在两个服务各有一份 —— 迟早对不上。
   */
  @PostMapping("/{id}/record-result")
  public PendingInspectionTaskRow recordResult(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody(required = false) PendingInspectionRecordResultInput input) {
    return call(
        auth,
        "pending-inspection.update",
        a ->
            pending.recordResult(
                id, input == null ? new PendingInspectionRecordResultInput(null) : input,
                a.userId(),
                auth));
  }

  /**
   * 处置决定 —— <b>本模块唯一动库存的动作</b>。
   *
   * <p>各去向数量之和必须等于任务批量（一批待检货必须被全部处置掉），
   * 转可售的量不得超过质检结论里的可售量（不合格品不能转可售）。
   */
  @PostMapping("/{id}/dispose")
  public PendingInspectionTaskRow dispose(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody PendingInspectionDisposeInput input) {
    return call(auth, "pending-inspection.update", a -> pending.dispose(id, input, a.userId()));
  }

  /** 取消（已发起质检的会同时作废 trade 侧的质检单）。 */
  @PostMapping("/{id}/cancel")
  public PendingInspectionTaskRow cancel(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody(required = false) PendingInspectionCancelInput input) {
    return call(
        auth,
        "pending-inspection.update",
        a ->
            pending.cancel(
                id, input == null ? new PendingInspectionCancelInput(null) : input, a.userId(), auth));
  }
}
