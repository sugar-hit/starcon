package com.starcon.trade;

import static com.starcon.trade.FulfillmentRequestModels.*;

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
 * 9.3.3.1 履约请求。
 *
 * <p>命名空间 {@code /trade/fulfillment/requests}：与 9.3.2.1 的 {@code /trade/fulfillment/allocation}、
 * 9.3.2.2 的 {@code /trade/fulfillment/routing}、9.3.1.2 的 {@code /trade/fulfillment/consolidation}
 * <b>并列而不是嵌套</b>，也<b>刻意不用裸路径</b> {@code /trade/fulfillment} ——
 * 四者是同一条履约链上相邻但不同的四环（合到什么单元 / 走哪条路径 / 从哪个仓发 / 怎么推进），
 * 混在一个命名空间里会让它们在日志与权限上分不开。api-proxy 白名单里也是新起一组。
 *
 * <p>权限码沿用 {@code trade.fulfillment.read} / {@code trade.fulfillment.update}：
 * 推进流程、登记下游回执、重试都直接决定这批货走到哪一步，不是读权限能带过去的动作。
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 */
@RestController
@RequestMapping("/trade/fulfillment/requests")
public class FulfillmentRequestController {

  private final AccessService access;
  private final FulfillmentRequestService requests;

  public FulfillmentRequestController(AccessService access, FulfillmentRequestService requests) {
    this.access = access;
    this.requests = requests;
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
   * <p>存在的理由：**下游三个全部未接通**，而这恰恰是最容易被误读的地方。
   * 界面若看到「已锁定预占」就以为货锁在 warehouse 那边了，那边其实什么都没锁 ——
   * 9.3.2.1（V116）踩过并写进证据的坑。所以 {@code reservesUpstreamStock} 等三个布尔
   * 必须由接口自己报出，而不是让调用方从 404 或空数组里猜。
   */
  @GetMapping("/capabilities")
  public FulfillmentRequestCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "fulfillment.read", a -> requests.capabilities());
  }

  // ============================================================ 履约请求

  /** 履约请求列表（真分页 + 真 SQL 筛选）。 */
  @GetMapping
  public FulfillmentRequestPage list(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String requestCode,
      @RequestParam(required = false) String orderRef,
      @RequestParam(required = false) String warehouseRef,
      @RequestParam(required = false) String skuRef,
      @RequestParam(required = false) String platformCode,
      @RequestParam(required = false) String splitUnitRef,
      @RequestParam(required = false) String consolidationRef,
      @RequestParam(required = false) String allocationDecisionRef,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String currentStep,
      @RequestParam(required = false, defaultValue = "false") Boolean blockedOnly,
      @RequestParam(required = false, defaultValue = "false") Boolean overdueOnly,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "fulfillment.read",
        a ->
            requests.pageRequests(
                new FulfillmentRequestQuery(
                    requestCode, orderRef, warehouseRef, skuRef, platformCode, splitUnitRef,
                    consolidationRef, allocationDecisionRef, status, currentStep, blockedOnly,
                    overdueOnly, keyword, page, size)));
  }

  /**
   * 建一张履约请求（一次履约单元）。
   *
   * <p>四个上游引用至少填一个。带相同 {@code idempotencyKey} 重放会返回既有那张，
   * 而不是报错 —— 09 卷关键约束「重复事件只产生一张单」。
   */
  @PostMapping
  public FulfillmentRequestSaved create(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody FulfillmentRequestInput input) {
    return call(auth, "fulfillment.update", a -> requests.create(input, a.userId()));
  }

  /** 履约请求详情：头 + 步骤 + 预占 + 出库单 + 作业 + 超时 + 事件。 */
  @GetMapping("/{id}")
  public FulfillmentRequestDetail detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "fulfillment.read", a -> requests.request(id));
  }

  // ============================================================ 推进 / 挂起 / 重试 / 取消

  /** 推进状态。{@code expectedVersion} 必填 —— 版本冲突报 409。 */
  @PostMapping("/{id}/advance")
  public FulfillmentRequestTransitionResult advance(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody FulfillmentRequestStatusInput input) {
    return call(auth, "fulfillment.update", a -> requests.advance(id, input, a.userId()));
  }

  /** 挂起（超时 / 步骤失败 / 回执陈旧），必然落一条 timeout 凭据。 */
  @PostMapping("/{id}/block")
  public FulfillmentRequestTransitionResult block(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody FulfillmentRequestBlockInput input) {
    return call(auth, "fulfillment.update", a -> requests.block(id, input, a.userId()));
  }

  /**
   * 重试 / 断点恢复。
   *
   * <p>「先查证再决定重试还是释放预占」的责任在调用方（人工或 9.3.3.2）；
   * 本端点只保证重试是显式动作、带版本校验、从断点而不是从头开始。
   */
  @PostMapping("/{id}/retry")
  public FulfillmentRequestTransitionResult retry(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody FulfillmentRequestRetryInput input) {
    return call(auth, "fulfillment.update", a -> requests.retry(id, input, a.userId()));
  }

  /** 取消（终态，必须带原因）。 */
  @PostMapping("/{id}/cancel")
  public FulfillmentRequestTransitionResult cancel(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody FulfillmentRequestCancelInput input) {
    return call(auth, "fulfillment.update", a -> requests.cancel(id, input, a.userId()));
  }

  /**
   * 登记跟踪完成（TRACK 步）并把流程收尾。
   *
   * <p>与 {@code /advance} 分开是因为「跟踪完成」必须同时落一条完成凭据：
   * 只推状态不落凭据，会出现「状态说完成了但没有任何完成记录」。
   */
  @PostMapping("/{id}/complete")
  public FulfillmentRequestTransitionResult complete(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody FulfillmentRequestRetryInput input) {
    return call(auth, "fulfillment.update", a -> requests.trackComplete(id, input, a.userId()));
  }

  // ============================================================ 锁定预占（7.2）

  /**
   * 发出锁定预占指令。
   *
   * <p>登记的是<b>指令</b>（状态 REQUESTED），不是「已锁住」。没有 warehouse 的回执
   * 就不算锁定 —— 这是 9.3.2.1（V116）踩过并写进证据的坑。
   */
  @PostMapping("/{id}/reservation")
  public FulfillmentRequestReservationRow requestReservation(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody(required = false) FulfillmentRequestReservationInput input) {
    return call(
        auth,
        "fulfillment.update",
        a -> requests.requestReservation(id, input == null ? new FulfillmentRequestReservationInput(null) : input,
            a.userId()));
  }

  /** 登记 7.2 的预占回执（LOCKED / REJECTED / RELEASED）。 */
  @PostMapping("/{id}/reservation/ack")
  public FulfillmentRequestReservationRow acknowledgeReservation(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody FulfillmentRequestReservationAckInput input) {
    return call(
        auth, "fulfillment.update", a -> requests.acknowledgeReservation(id, input, a.userId()));
  }

  // ============================================================ 出库单（8.3.1）

  /** 发出库单指令（登记，不假装已经派单）。 */
  @PostMapping("/{id}/outbound-order")
  public FulfillmentRequestOutboundRow requestOutboundOrder(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody(required = false) FulfillmentRequestOutboundInput input) {
    return call(
        auth,
        "fulfillment.update",
        a ->
            requests.requestOutboundOrder(
                id, input == null ? new FulfillmentRequestOutboundInput(null, null) : input,
                a.userId()));
  }

  /** 登记 8.3.1 的出库单回执（CONFIRMED / REJECTED / CANCELLED）。 */
  @PostMapping("/{id}/outbound-order/ack")
  public FulfillmentRequestOutboundRow acknowledgeOutboundOrder(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody FulfillmentRequestOutboundAckInput input) {
    return call(
        auth, "fulfillment.update", a -> requests.acknowledgeOutboundOrder(id, input, a.userId()));
  }

  // ============================================================ 编排作业

  /** 批量编排作业（拣货 / 复核 / 打包 / 交接）。幂等键相同则跳过，不重复建单。 */
  @PostMapping("/{id}/work-orders")
  public List<FulfillmentRequestWorkOrderRow> assignWorkOrders(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody FulfillmentRequestWorkOrderInput input) {
    return call(auth, "fulfillment.update", a -> requests.assignWorkOrders(id, input, a.userId()));
  }

  /** 登记单条作业状态（RUNNING / DONE / FAILED / SKIPPED）。 */
  @PostMapping("/{id}/work-orders/{workOrderId}/status")
  public FulfillmentRequestWorkOrderRow updateWorkOrder(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @PathVariable UUID workOrderId,
      @Valid @RequestBody FulfillmentRequestWorkOrderStatusInput input) {
    return call(
        auth, "fulfillment.update", a -> requests.updateWorkOrder(id, workOrderId, input, a.userId()));
  }

  // ============================================================ 断点恢复 / 超时

  /** 可恢复清单：全部挂起的流程，按挂起时间倒序（断点恢复入口）。 */
  @GetMapping("/resumable")
  public FulfillmentRequestResumablePage resumable(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(auth, "fulfillment.read", a -> requests.pageResumable(page, size));
  }

  /** 扫描已超时未推进的流程并挂起（逐条报出挂起与跳过及原因）。 */
  @PostMapping("/scan-timeouts")
  public FulfillmentRequestScanResult scanTimeouts(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody(required = false) FulfillmentRequestScanInput input) {
    return call(
        auth,
        "fulfillment.update",
        a -> requests.scanTimeouts(input == null ? new FulfillmentRequestScanInput(null, null) : input,
            a.userId()));
  }

  /** 超时 / 失败检出记录列表（9.3.3.2 的数据来源）。 */
  @GetMapping("/timeouts")
  public FulfillmentRequestTimeoutPage timeouts(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String stepKind,
      @RequestParam(required = false) String reasonCode,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(auth, "fulfillment.read", a -> requests.pageTimeouts(stepKind, reasonCode, page, size));
  }
}
