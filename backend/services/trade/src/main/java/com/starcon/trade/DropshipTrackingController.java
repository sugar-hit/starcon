package com.starcon.trade;

import static com.starcon.trade.DropshipTrackingModels.*;

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
 * 9.6.1.3 代发履约跟踪与异常。
 *
 * <p>命名空间 {@code /trade/fulfillment/dropship-tracking}：与 9.6.1.1 的
 * {@code /trade/fulfillment/dropship}、9.6.1.2 的
 * {@code /trade/fulfillment/dropship-orders} <b>并列</b>而不是挂在其中任何一个下面 ——
 * 配置说「这个 SKU 走不走代发」，代发单说「这一票怎么下的」，
 * 本模块说「这一票现在到哪了、卡在哪、怎么处置」。三件事混在一个命名空间里，
 * 「改一个配置」「下一单」「切一次履约」在日志与权限上就分不开了。
 *
 * <p>权限码沿用 {@code trade.fulfillment.read} / {@code trade.fulfillment.update}：
 * 切履约模式与发起退款是要花钱、要改对外承诺的动作，不是读权限能带过去的。
 *
 * <p>租户**不**来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 */
@RestController
@RequestMapping("/trade/fulfillment/dropship-tracking")
public class DropshipTrackingController {

  private final AccessService access;
  private final DropshipTrackingService tracking;

  public DropshipTrackingController(AccessService access, DropshipTrackingService tracking) {
    this.access = access;
    this.tracking = tracking;
  }

  /** 统一入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "trade." + permission);
    return access.run(actor, "trade." + permission, () -> work.apply(actor));
  }

  // ============================================================ 能力声明

  /**
   * 本模块的能力与上下游可用性。
   *
   * <p>两处 available=false 且点名 ownerModule：<b>平台回传</b>（2.5.1.1 / channel）
   * 与 <b>售后退款</b>（11）。它们在本模块里的真实形态是**任务**，不是已完成的动作 ——
   * 报成 true 会让人以为回传与退款已经通了。
   */
  @GetMapping("/capabilities")
  public DropshipTrackingCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "fulfillment.read", a -> tracking.capabilities());
  }

  // ============================================================ 检出策略

  /** 检出策略（阈值来自租户级单例；未登记时返回迁移内置默认值并如实标出）。 */
  @GetMapping("/policy")
  public DropshipTrackingPolicyRow policy(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "fulfillment.read", a -> tracking.policy());
  }

  /** 维护检出策略（upsert，单例）。阈值变更只影响**此后**检出的异常。 */
  @PostMapping("/policy")
  public DropshipTrackingPolicyRow savePolicy(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody DropshipTrackingPolicyInput input) {
    return call(auth, "fulfillment.update", a -> tracking.savePolicy(input, a.membershipId()));
  }

  // ============================================================ 跟踪

  /** 跟踪列表（真分页）。{@code exceptionOnly=true} 只回有未结案异常的那些。 */
  @GetMapping("/trackings")
  public DropshipTrackingPage trackings(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String orderNo,
      @RequestParam(required = false) String salesOrderNo,
      @RequestParam(required = false) String supplierCode,
      @RequestParam(required = false) String activeLeg,
      @RequestParam(required = false) String supplierLegStatus,
      @RequestParam(required = false) String ownLegStatus,
      @RequestParam(required = false) String exceptionOnly,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "fulfillment.read",
        a ->
            tracking.pageTrackings(
                new DropshipTrackingQuery(
                    orderNo, salesOrderNo, supplierCode, activeLeg, supplierLegStatus,
                    ownLegStatus, exceptionOnly, keyword, page, size)));
  }

  /** 从一张已提交给供应商的代发单建跟踪（承诺发货时限在此刻定下，是延迟判定的基准）。 */
  @PostMapping("/trackings")
  public DropshipTrackingSaved createTracking(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody DropshipTrackingInput input) {
    return call(auth, "fulfillment.update", a -> tracking.createTracking(input, a.membershipId()));
  }

  /** 跟踪详情：跟踪头（两段物流） + 异常 + 处置 + 任务 + 事件流。跨租户 → 404。 */
  @GetMapping("/trackings/{trackingId}")
  public DropshipTrackingDetail tracking(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID trackingId) {
    return call(auth, "fulfillment.read", a -> tracking.trackingDetail(trackingId));
  }

  /**
   * 录入/更新供应商段（发货、轨迹、妥投）。
   *
   * <p>给了运单号且供应商段已发货时自动排一条 <b>PLATFORM_CALLBACK 任务</b>交给
   * 2.5.1.1 —— 本模块**不代为回传**，callback_status 停在 PENDING。
   */
  @PostMapping("/trackings/{trackingId}/supplier-leg")
  public DropshipTrackingSaved supplierLeg(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID trackingId,
      @Valid @RequestBody DropshipSupplierLegInput input) {
    return call(
        auth,
        "fulfillment.update",
        a -> tracking.recordSupplierLeg(trackingId, input, a.membershipId()));
  }

  /** 录入/更新我方段（交运、轨迹、妥投）。切换履约模式后这一段才开始推进。 */
  @PostMapping("/trackings/{trackingId}/own-leg")
  public DropshipTrackingSaved ownLeg(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID trackingId,
      @Valid @RequestBody DropshipOwnLegInput input) {
    return call(
        auth, "fulfillment.update", a -> tracking.recordOwnLeg(trackingId, input, a.membershipId()));
  }

  // ============================================================ 异常

  /**
   * 跑一轮异常检出（三条判据，超时未发货 / 轨迹长时间无更新 / 报发货后无运单）。
   *
   * <p>刻意返回 200 并如实报出「扫了多少张、检出几条」：检出为空是一个**业务结论**
   * （此刻确实没有异常），不是请求失败。
   */
  @PostMapping("/exceptions/evaluate")
  public DropshipTrackingEvaluateResult evaluate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "fulfillment.update", a -> tracking.evaluateTrackingExceptions());
  }

  /** 异常清单工作台（真分页，按严重度排序）。 */
  @GetMapping("/exceptions")
  public DropshipTrackingExceptionPage exceptions(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID orderId,
      @RequestParam(required = false) String exceptionKind,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String severity,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "fulfillment.read",
        a ->
            tracking.pageExceptions(
                new DropshipTrackingExceptionQuery(
                    orderId, exceptionKind, status, severity, keyword, page, size)));
  }

  /**
   * 处置一条异常：催供应商 / 改自营发货 / 发起退款。
   *
   * <p>「改自营发货」会真的把生效段切到我方段（供应商段落 ABANDONED 保留凭据），
   * 「发起退款」只落待办任务交给 11 售后 —— 本模块不代为退款。
   */
  @PostMapping("/exceptions/{exceptionId}/dispose")
  public DropshipDispositionSaved dispose(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID exceptionId,
      @Valid @RequestBody DropshipDisposeInput input) {
    return call(
        auth,
        "fulfillment.update",
        a -> tracking.dispose(exceptionId, input, a.membershipId()));
  }

  /** 判为误报（必须写明理由；解封后重新逾期会被当作一条新事实重新检出）。 */
  @PostMapping("/exceptions/{exceptionId}/dismiss")
  public DropshipTrackingExceptionRow dismiss(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID exceptionId,
      @Valid @RequestBody DropshipDismissInput input) {
    return call(
        auth, "fulfillment.update", a -> tracking.dismissException(exceptionId, input, a.membershipId()));
  }

  // ============================================================ 处置状态

  /** 处置状态流转（PENDING → SENT → ACKNOWLEDGED → COMPLETED / PENDING → CANCELLED）。 */
  @PostMapping("/dispositions/{dispositionId}/status")
  public DropshipDispositionRow dispositionStatus(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID dispositionId,
      @Valid @RequestBody DropshipDispositionStatusInput input) {
    return call(
        auth,
        "fulfillment.update",
        a -> tracking.changeDispositionStatus(dispositionId, input, a.membershipId()));
  }

  // ============================================================ 任务队列

  /** 待回传 / 待处置任务工作台：2.5.1.1（回传）与 11 售后（退款）从这里接手。 */
  @GetMapping("/tasks")
  public DropshipTaskPage tasks(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String taskKind,
      @RequestParam(required = false) String taskStatus,
      @RequestParam(required = false) String targetSystem,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "fulfillment.read",
        a ->
            tracking.pageTasks(
                new DropshipTaskQuery(
                    taskKind, taskStatus, targetSystem, keyword, page, size)));
  }

  /** 下游领取任务（OPEN → CLAIMED）。 */
  @PostMapping("/tasks/{taskId}/claim")
  public DropshipTaskSaved claimTask(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID taskId) {
    return call(auth, "fulfillment.update", a -> tracking.claimTask(taskId, a.membershipId()));
  }

  /**
   * 下游回执（CLAIMED → ACKNOWLEDGED 或 FAILED）。
   *
   * <p>只有走到这里，供应商运单号才可能被标成「平台已受理」——
   * 「已回传」必须有一个下游真的接手过它，否则只能是待办。
   */
  @PostMapping("/tasks/{taskId}/complete")
  public DropshipTaskSaved completeTask(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID taskId,
      @Valid @RequestBody DropshipTaskResultInput input) {
    return call(
        auth, "fulfillment.update", a -> tracking.completeTask(taskId, input, a.membershipId()));
  }

  // ============================================================ 事件流

  /** 事件流（只读；append-only，没有任何写形状）。 */
  @GetMapping("/events")
  public DropshipTrackingEventPage events(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID trackingId,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "fulfillment.read",
        a -> tracking.pageEvents(new DropshipTrackingEventQuery(trackingId, page, size)));
  }
}