package com.starcon.warehouse;

import static com.starcon.warehouse.StocktakeVarianceModels.*;

import jakarta.validation.Valid;
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
 * 7.4.2.2 盘点差异处理。
 *
 * <p>蓝图：「处理盘点差异：按差异原因（损耗、错发、偷盗、未入账、时点差）分类，生成调整单走审批， 盘盈盘亏入账并可追责。」输入：「盘点差异明细；差异原因；处理决定（调整/复盘/忽略）。」
 * 输出：「调整单 + 盘点完成报告（含盘盈率、差异率指标）。」以及红线： 「**差异不直接改余额**，必须经调整单与审批。」
 *
 * <p>命名空间与 7.4.2.1 同一套：{@code /warehouse/inventory/...}，权限码 {@code warehouse.inventory.read} /
 * {@code warehouse.inventory.update}。 端点刻意独立成 {@code StocktakeVarianceController} 而不是并进 {@code
 * InventoryController}：那是 7.4.2.1 的文件，两条车道并行时各改各的才不会撞。
 *
 * <p><b>没有「直接把差异写进余额」的端点，而且数据库也不让绕过流程改</b>： 归因只是记录决定；唯一会动余额的是 {@code /adjustments/{id}/post}，
 * 它要求单据已经 APPROVED，而 APPROVED 只能由另一个人批出来（自提自批被拒）。 V62 还有一道入账闸门：未审批的调整单在 DB 层写不进 {@code
 * inventory_entry}。
 */
@RestController
@RequestMapping("/warehouse")
public class StocktakeVarianceController {

  private final AccessService access;
  private final StocktakeVarianceService variance;

  public StocktakeVarianceController(AccessService access, StocktakeVarianceService variance) {
    this.access = access;
    this.variance = variance;
  }

  /** 统一入口：先向 identity 确认身份与权限（租户**不**来自任何客户端头）， 再在一个事务里绑定 RLS 租户上下文执行查询（与 7.4.2.1 同一个 call）。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "warehouse." + permission);
    return access.run(actor, "warehouse." + permission, () -> work.apply(actor));
  }

  // ------------------------------------------------------------ 能力

  /**
   * 差异处理能力：五类原因、三种决定、五种筛选，以及三条必须如实讲清楚的说明 （金额口径为什么算不出来、审批为什么不是 17.4、调整单怎么交给 7.4.1.1）。
   *
   * <p>前端据此渲染选项与提示，而不是把规则硬编码在界面上。
   */
  @GetMapping("/inventory/stocktake-variances/capabilities")
  public StocktakeVarianceCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "inventory.read", a -> variance.capabilities());
  }

  // ------------------------------------------------------------ 差异处理

  /**
   * 待处理差异明细（三列 + 当前处置）。
   *
   * <p>差异三列**直接读 7.4.2.1 的盘点明细**，本模块一个数都不自己算 —— 差异是上游的生成列（实盘 − 账面）。这里再算一遍就会出现
   * 「界面上算的差异」与「库里存的差异」两套数。
   *
   * <p>{@code filter} 五个互斥口径：ALL / UNATTRIBUTED（未归因）/ IN_ADJUSTMENT（调整中）/ POSTED（已入账）/
   * RECOUNTED（已复盘）。
   */
  @GetMapping("/inventory/stocktake-variances/sheets/{sheetId}/lines")
  public StocktakeVarianceLinePage lines(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID sheetId,
      @RequestParam(required = false) String filter,
      @RequestParam(required = false) String reason,
      @RequestParam(required = false) String decision,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "inventory.read",
        a -> variance.lines(sheetId, filter, reason, decision, keyword, page, size));
  }

  /**
   * 盘点完成报告（盘盈率、差异率、归因完成度、按原因与按决定的分布）。
   *
   * <p>率按**数量**出并写明分母口径；金额口径如实报为算不出来（库里没有库存成本列）。 分母为 0 的率是 null 而不是 0。
   */
  @GetMapping("/inventory/stocktake-variances/sheets/{sheetId}/report")
  public StocktakeVarianceReport report(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID sheetId) {
    return call(auth, "inventory.read", a -> variance.report(sheetId));
  }

  /** 差异处理审计时间线：谁归因、谁提交、谁审批、谁入账、什么时候（append-only）。 */
  @GetMapping("/inventory/stocktake-variances/sheets/{sheetId}/audit")
  public List<StocktakeVarianceAuditEntry> audit(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID sheetId,
      @RequestParam(required = false) Integer limit) {
    return call(auth, "inventory.read", a -> variance.auditTrail(sheetId, limit));
  }

  /**
   * 归因一条差异：五类原因 × 三种决定 + 说明。
   *
   * <p>权限码是 update（它写归因、重录实盘、置差异状态），但**它本身不改库存余额**： ADJUST 只落一条未结归因等着进调整单；RECOUNT 退回未录；IGNORE 结案留痕。
   * 余额只在 {@code /adjustments/{id}/post} 里动，而那一步要求已审批。
   */
  @PostMapping("/inventory/stocktake-variances/sheets/{sheetId}/lines/{lineId}/attribute")
  public StocktakeVarianceLine attribute(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID sheetId,
      @PathVariable UUID lineId,
      @Valid @RequestBody StocktakeVarianceAttributionInput input) {
    return call(
        auth, "inventory.update", a -> variance.attribute(sheetId, lineId, input, a.userId()));
  }

  // ------------------------------------------------------------ 调整单

  /**
   * 生成调整单（草稿）：把已归因为「调整」且还没进任何单据的差异收进来。
   *
   * <p>{@code lineIds} 显式给行，或 {@code allAttributed=true} 一次收走全部待调整行。 两者都空时显式拒绝 ——
   * 悄悄建一张零行单据会让台账上看起来像「处理过了」。
   *
   * <p>它是**草稿**：此刻一行余额都还没动。
   */
  @PostMapping("/inventory/stocktake-variances/sheets/{sheetId}/adjustments")
  public StocktakeVarianceAdjustmentDetail createAdjustment(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID sheetId,
      @Valid @RequestBody StocktakeVarianceAdjustCreateInput input) {
    return call(
        auth, "inventory.update", a -> variance.createAdjustment(sheetId, input, a.userId()));
  }

  /** 调整单列表（真分页；行数与盘盈/盘亏数量由明细现算，不另存列）。 */
  @GetMapping("/inventory/stocktake-variances/adjustments")
  public StocktakeVarianceAdjustmentPage adjustments(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) UUID sheetId,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(
        auth, "inventory.read", a -> variance.adjustments(status, sheetId, keyword, page, size));
  }

  /** 调整单详情 = 头部 + 全部行 + 这张单上的完整审计时间线。 */
  @GetMapping("/inventory/stocktake-variances/adjustments/{adjustmentId}")
  public StocktakeVarianceAdjustmentDetail adjustment(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID adjustmentId) {
    return call(auth, "inventory.read", a -> variance.detail(adjustmentId));
  }

  /** 提交审批：DRAFT → PENDING_APPROVAL。此刻余额仍未改动。 */
  @PostMapping("/inventory/stocktake-variances/adjustments/{adjustmentId}/submit")
  public StocktakeVarianceAdjustment submit(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID adjustmentId,
      @RequestBody StocktakeVarianceDecisionInput input) {
    return call(auth, "inventory.update", a -> variance.submit(adjustmentId, input, a.userId()));
  }

  /**
   * 批准：PENDING_APPROVAL → APPROVED。
   *
   * <p><b>提交人不能批自己提的单</b>：自提自批的审批与没有审批是同一件事， 而库存调整正是舞弊高发区。
   *
   * <p>批准**仍然不改余额** —— 它只是允许入账，入账是独立的 {@code /post} 一步。
   */
  @PostMapping("/inventory/stocktake-variances/adjustments/{adjustmentId}/approve")
  public StocktakeVarianceAdjustment approve(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID adjustmentId,
      @RequestBody StocktakeVarianceDecisionInput input) {
    return call(auth, "inventory.update", a -> variance.approve(adjustmentId, input, a.userId()));
  }

  /** 驳回：PENDING_APPROVAL → REJECTED，意见必填；差异行释放，可重新归因。 */
  @PostMapping("/inventory/stocktake-variances/adjustments/{adjustmentId}/reject")
  public StocktakeVarianceAdjustment reject(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID adjustmentId,
      @RequestBody StocktakeVarianceDecisionInput input) {
    return call(auth, "inventory.update", a -> variance.reject(adjustmentId, input, a.userId()));
  }

  /** 作废：任何未过账状态 → CANCELLED。已过账的单据不能作废（账已经动了）。 */
  @PostMapping("/inventory/stocktake-variances/adjustments/{adjustmentId}/cancel")
  public StocktakeVarianceAdjustment cancel(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID adjustmentId,
      @RequestBody StocktakeVarianceDecisionInput input) {
    return call(auth, "inventory.update", a -> variance.cancel(adjustmentId, input, a.userId()));
  }

  /**
   * 入账：APPROVED → POSTED。**本模块唯一会改库存余额的端点。**
   *
   * <p>一个事务里写业务单（kind=ADJUSTMENT、ref_type=STOCKTAKE_VARIANCE 定位键）、 逐行写带符号分录、改余额行与库位明细、标记行与归因结案、写审计。
   *
   * <p>未审批的单据在这里被 409 挡住，而 V62 的入账闸门在数据库层再挡一次 —— 蓝图那句「差异不直接改余额，必须经调整单与审批」因此是可执行的，而不是一句承诺。
   */
  @PostMapping("/inventory/stocktake-variances/adjustments/{adjustmentId}/post")
  public StocktakeVariancePostResult post(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID adjustmentId,
      @RequestBody StocktakeVarianceDecisionInput input) {
    return call(auth, "inventory.update", a -> variance.post(adjustmentId, input, a.userId()));
  }
}
