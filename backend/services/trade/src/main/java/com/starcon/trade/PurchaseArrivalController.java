package com.starcon.trade;

import static com.starcon.trade.PurchaseArrivalModels.*;

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
 * 6.3.2.2 到货与逾期。
 *
 * <p>蓝图 06-purchase-and-supply.md：「跟踪采购单的到货状态：已发货（在途）、部分到货、全部到货、
 * 逾期未到、取消。逾期自动预警并给出处置建议（催货/改供应商/调库存/延期售卖）。
 * 输入：8 仓储的收货事件；跟单记录；物流在途状态（10）。
 * 输出：到货状态 + 逾期预警 + 处置建议。」
 * 加粗句：「<b>部分到货必须立即更新已收数量并触发应付部分确认</b>，
 * 否则采购与财务三方数据对不上。」
 *
 * <p><b>命名空间</b>：{@code /trade/purchase-arrivals} —— 前端路由在 {@code /purchase/arrivals}，
 * 后端命名空间按服务走（与 6.4.1.1 加工单 {@code /trade/processing} 同一纪律）。
 * api-proxy 白名单里放行的是 {@code purchase-arrivals/...} 这一段，与既有
 * {@code aftersales/...} / {@code fulfillment/...} / {@code processing/...} 互不吃。
 *
 * <p><b>权限码</b>：读 {@code trade.purchase.read}；写 {@code trade.purchase.update}；
 * <b>财务确认应付部分确认</b>用独立的 {@code trade.purchase.finance} ——
 * 「没有财务确认就没有生效」这条规则的落点不只是状态机，还有**权限分离**：
 * 登记到货的人不该顺手把应付确认掉。
 *
 * <p><b>刻意不提供</b>：任何「直接写已收数量」或「直接置为已确认应付」的端点 ——
 * 已收数量由 DB 从到货流水重算，应付确认由 DB 按单价快照 × 到货数量算。
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} / {@link #write} / {@link #finance} 先向 identity
 * 确认已认证成员关系，再在一个事务里绑定 {@code app.current_tenant}，
 * 由 FORCE RLS 在数据库层强制隔离。
 * 本模块的端点大多是<b>按 id 直达</b>的 URL —— 拿别的租户的跟踪单 ID 来读，
 * 得到的是与「不存在」<b>完全相同</b>的错误。
 */
@RestController
@RequestMapping("/trade/purchase-arrivals")
public class PurchaseArrivalController {

  private static final String READ = "purchase.read";
  private static final String WRITE = "purchase.update";
  /** 财务确认应付部分确认用的**独立权限**。 */
  private static final String FINANCE = "purchase.finance";

  private final AccessService access;
  private final PurchaseArrivalService arrivals;

  public PurchaseArrivalController(AccessService access, PurchaseArrivalService arrivals) {
    this.access = access;
    this.arrivals = arrivals;
  }

  /** 统一读入口：先确认身份与权限（租户<b>不</b>来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, Function<AccessService.Access, T> work) {
    AccessService.Access actor = access.require(bearer, "trade." + READ);
    return access.run(actor, "trade." + READ, () -> work.apply(actor));
  }

  /** 写入口：需要 {@code trade.purchase.update}。租户来源与读入口完全一致。 */
  private <T> T write(String bearer, Function<AccessService.Access, T> work) {
    AccessService.Access actor = access.require(bearer, "trade." + WRITE);
    return access.run(actor, "trade." + WRITE, () -> work.apply(actor));
  }

  /** 财务确认入口：需要<b>独立</b>的 {@code trade.purchase.finance}。 */
  private <T> T finance(String bearer, Function<AccessService.Access, T> work) {
    AccessService.Access actor = access.require(bearer, "trade." + FINANCE);
    return access.run(actor, "trade." + FINANCE, () -> work.apply(actor));
  }

  // ================================================================ 能力声明

  /**
   * 能力声明：职责句的五个动词逐块报可用性，上游逐个报 delivered 与归属模块号、
   * 本模块<b>刻意不做</b>的事、状态机口径与逾期的两个口径。
   */
  @GetMapping("/capabilities")
  public PurchaseArrivalCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, actor -> arrivals.capabilities());
  }

  // ================================================================ 逾期看板

  /**
   * 逾期预警看板：逾期未到 + 在途滞留两类预警，各自带处置建议与依据。
   *
   * <p>路径刻意是字面量 {@code overdue-board}（不是 id 段）——
   * 否则它会被 {@code /{id}} 那一支抢走。
   */
  @GetMapping("/overdue-board")
  public PurchaseArrivalAlertBoard overdueBoard(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String alertType,
      @RequestParam(required = false) UUID supplierRefId,
      @RequestParam(required = false) Integer minOverdueDays,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    return call(
        auth,
        actor ->
            arrivals.alertBoard(alertType, supplierRefId, minOverdueDays, page, size));
  }

  // ================================================================ 列表

  /** 到货跟踪列表（真分页 + 多维筛选 + 排序白名单 + 跨全表汇总）。 */
  @GetMapping
  public PurchaseArrivalPage list(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) UUID supplierRefId,
      @RequestParam(required = false) Boolean overdueOnly,
      @RequestParam(required = false) Boolean inTransitOnly,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) java.time.LocalDate promisedBefore,
      @RequestParam(required = false) String sort) {
    return call(
        auth,
        actor ->
            arrivals.list(
                new PurchaseArrivalQuery(
                    page, size, status, supplierRefId, overdueOnly, inTransitOnly, keyword,
                    promisedBefore, sort)));
  }

  /** 单张到货跟踪全景（头 + 到货流水 + 应付部分确认 + 处置建议 + 留痕 + 可执行动作）。 */
  @GetMapping("/{id}")
  public PurchaseArrivalDetail detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, actor -> arrivals.detail(id));
  }

  // ================================================================ 写动作

  /**
   * 建跟踪单（恒为「待发货」）。
   *
   * <p><b>请求体里没有「已收数量」</b>：已收数量永远由 DB 从到货流水重算。
   */
  @PostMapping
  public PurchaseArrivalDetail create(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody PurchaseArrivalCreate request) {
    return write(auth, actor -> arrivals.create(request, actor.userId()));
  }

  /** 标记已发货（在途）。可同时更新承诺到货日（跟单改了交期）。 */
  @PostMapping("/{id}/ship")
  public PurchaseArrivalDetail ship(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody PurchaseArrivalShip request) {
    return write(auth, actor -> arrivals.ship(id, request, actor.userId()));
  }

  /**
   * 登记到货（部分 / 全部）。
   *
   * <p>⚠️ **「部分还是全部」由 DB 按缺口判定**，请求体里没有这个字段；
   * **应付部分确认的金额也由 DB 算**，请求体里同样没有金额字段。
   */
  @PostMapping("/{id}/arrivals")
  public PurchaseArrivalDetail registerArrival(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody PurchaseArrivalArrival request) {
    // ⚠️ 授权头要**原样透传**给 warehouse 端口（跨服务只经授权 API，不透传租户号）。
    return write(
        auth, actor -> arrivals.registerArrival(id, request, actor.userId(), auth));
  }

  /** 取消跟踪单。必须写理由。 */
  @PostMapping("/{id}/cancel")
  public PurchaseArrivalDetail cancel(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody PurchaseArrivalCancel request) {
    return write(auth, actor -> arrivals.cancel(id, request, actor.userId()));
  }

  /** 同步 10.4.1.2 在途状态快照（滞留天数 + ETA）。 */
  @PostMapping("/{id}/sync-in-transit")
  public PurchaseArrivalDetail syncInTransit(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return write(auth, actor -> arrivals.syncInTransit(id, actor.userId(), auth));
  }

  // ================================================================ 处置建议

  /** 处置建议的处理（采纳 / 忽略）。必须写说明。 */
  @PostMapping("/{id}/dispositions/{dispositionId}/resolve")
  public PurchaseArrivalDetail resolveDisposition(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @PathVariable UUID dispositionId,
      @RequestBody PurchaseArrivalDispositionResolve request) {
    return write(
        auth, actor -> arrivals.resolveDisposition(id, dispositionId, request, actor.userId()));
  }

  // ================================================================ 应付部分确认

  /**
   * 财务确认应付部分确认。
   *
   * <p>需要<b>独立</b>的 {@code trade.purchase.finance} 权限。
   */
  @PostMapping("/{id}/payable-confirmations/{confirmationId}/confirm")
  public PurchaseArrivalDetail confirmPayable(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @PathVariable UUID confirmationId,
      @RequestBody PurchaseArrivalPayableConfirm request) {
    return finance(
        auth, actor -> arrivals.confirmPayable(id, confirmationId, request, actor.userId()));
  }

  /** 财务驳回应付部分确认。必须写理由。 */
  @PostMapping("/{id}/payable-confirmations/{confirmationId}/reject")
  public PurchaseArrivalDetail rejectPayable(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @PathVariable UUID confirmationId,
      @RequestBody PurchaseArrivalPayableReject request) {
    return finance(
        auth, actor -> arrivals.rejectPayable(id, confirmationId, request, actor.userId()));
  }
}
