package com.starcon.trade;

import static com.starcon.trade.FulfillmentMonitorModels.*;

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
 * 9.3.3.2 履约流程监控。
 *
 * <p>命名空间 {@code /trade/fulfillment/monitor}：与 9.3.3.1 的 {@code /trade/fulfillment/requests}
 * <b>并列而不是嵌套</b>。理由同 V152 控制器那条纪律：同一条履约链上相邻但不同的环节
 * （编排 / 监控）在日志与权限上必须能分开；而且 api-proxy 白名单里两组路径各占一条正则，
 * 并行车道不会互相覆盖。
 *
 * <p>权限码沿用 {@code trade.fulfillment.read} / {@code trade.fulfillment.update}：
 * 查证与处置都直接决定这批货走到哪一步，不是读权限能带过去的动作。
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 */
@RestController
@RequestMapping("/trade/fulfillment/monitor")
public class FulfillmentMonitorController {

  private final AccessService access;
  private final FulfillmentMonitorService monitor;

  public FulfillmentMonitorController(AccessService access, FulfillmentMonitorService monitor) {
    this.access = access;
    this.monitor = monitor;
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
   * <p>存在的理由：<b>两个下游都没接通</b>（1 工作台待办、7.2.1.3 释放预占），
   * 而这恰恰是最容易被误读的地方。所以 {@code reservationReleaseDispatched} 与
   * {@code workbenchTodoPushed} 必须由接口自己报出，而不是让调用方从 404 或空数组里猜。
   */
  @GetMapping("/capabilities")
  public FulfillmentMonitorCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "fulfillment.read", a -> monitor.capabilities());
  }

  // ============================================================ 异常流程清单

  /** 异常流程清单（真分页 + 真 SQL 筛选）。 */
  @GetMapping("/cases")
  public FulfillmentMonitorCasePage cases(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String caseNo,
      @RequestParam(required = false) String requestCode,
      @RequestParam(required = false) String orderRef,
      @RequestParam(required = false) String skuRef,
      @RequestParam(required = false) String warehouseRef,
      @RequestParam(required = false) String stepKind,
      @RequestParam(required = false) String anomalyKind,
      @RequestParam(required = false) String severity,
      @RequestParam(required = false) String status,
      @RequestParam(required = false, defaultValue = "false") Boolean verifiedOnly,
      @RequestParam(required = false, defaultValue = "false") Boolean pendingVerificationOnly,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "fulfillment.read",
        a ->
            monitor.pageCases(
                new FulfillmentMonitorCaseQuery(
                    caseNo, requestCode, orderRef, skuRef, warehouseRef, stepKind, anomalyKind,
                    severity, status, verifiedOnly, pendingVerificationOnly, keyword, page, size)));
  }

  /**
   * 异常流程详情：案例 + 查证记录 + 处置 + 释放登记 + 待办 + 事件流。
   *
   * <p>响应带 {@code retryAllowed} 与 {@code retryBlockedReason}：界面直接显示后一句话，
   * 不让操作员从 409 报文里反推差什么 —— 猜错的代价是点一次重试，也就是重复发货。
   */
  @GetMapping("/cases/{id}")
  public FulfillmentMonitorCaseDetail caseDetail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "fulfillment.read", a -> monitor.caseDetail(id));
  }

  // ============================================================ 扫描 / 检出

  /** 扫描并检出异常（判定用数据库时钟，逐条报出检出结果）。 */
  @PostMapping("/scan")
  public FulfillmentMonitorScanResult scan(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody(required = false) FulfillmentMonitorScanInput input) {
    return call(
        auth,
        "fulfillment.update",
        a ->
            monitor.scan(
                input == null ? new FulfillmentMonitorScanInput(null, null, null, null) : input,
                a.userId()));
  }

  // ============================================================ 查证（加粗句的落点）

  /**
   * 发起查证。
   *
   * <p>只有 {@code conclusion=CONFIRMED} 才把案例推进到 VERIFIED，也只有 VERIFIED
   * 的案例才允许发起处置 —— 这条链在 DB 层强制（V154 第 8.1 / 8.4 节）。
   * 同 {@code idempotencyKey} 重放不会造出第二条「我查过了」。
   */
  @PostMapping("/cases/{id}/verify")
  public FulfillmentMonitorVerifySaved verify(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody FulfillmentMonitorVerifyInput input) {
    return call(auth, "fulfillment.update", a -> monitor.verify(id, input, a.userId()));
  }

  // ============================================================ 处置

  /**
   * 发起处置：重试 / 切换路径 / 人工介入 / 释放预占 / 证伪。
   *
   * <p>重试必须带 {@code verificationId}（一条 CONFIRMED 的查证）与新的超时窗口；
   * 缺任一，DB 触发器直接拒 —— 「超时不是失败，先查证再决定」不是提示语。
   */
  @PostMapping("/cases/{id}/disposition")
  public FulfillmentMonitorDispositionSaved dispose(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody FulfillmentMonitorDispositionInput input) {
    return call(auth, "fulfillment.update", a -> monitor.dispose(id, input, a.userId()));
  }

  /** 推进处置状态（登记下游回执）。状态上的 CAS，重放第二次被挡。 */
  @PostMapping("/cases/{id}/disposition/{dispositionId}/status")
  public FulfillmentMonitorDispositionRow advanceDisposition(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @PathVariable UUID dispositionId,
      @Valid @RequestBody FulfillmentMonitorDispositionStatusInput input) {
    return call(
        auth, "fulfillment.update", a -> monitor.advanceDisposition(id, dispositionId, input));
  }

  /** 结案：要求至少一条 COMPLETED 的处置 —— 只发出（SENT）不算处置完。 */
  @PostMapping("/cases/{id}/close")
  public FulfillmentMonitorCaseSaved close(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody FulfillmentMonitorCloseInput input) {
    return call(auth, "fulfillment.update", a -> monitor.close(id, input, a.userId()));
  }

  /** 登记 7.2.1.3 的释放回执（REQUESTED → RELEASED / REJECTED）。 */
  @PostMapping("/cases/{id}/release/{releaseId}/ack")
  public FulfillmentMonitorReleaseRow ackRelease(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @PathVariable UUID releaseId,
      @Valid @RequestBody FulfillmentMonitorReleaseAckInput input) {
    return call(auth, "fulfillment.update", a -> monitor.ackRelease(id, releaseId, input));
  }

  // ============================================================ 超时阈值配置

  /** 当前生效的四步阈值与解析来源（仓库覆盖 > 租户默认 > 建单快照）。 */
  @GetMapping("/thresholds")
  public FulfillmentMonitorEffectiveThresholds thresholds(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String warehouseRef) {
    return call(auth, "fulfillment.read", a -> monitor.effectiveThresholds(warehouseRef));
  }

  /** 阈值 upsert（幂等：同 (仓, 步) 覆盖而不是插第二条）。 */
  @PutMapping("/thresholds")
  public FulfillmentMonitorThresholdRow putThreshold(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody FulfillmentMonitorThresholdInput input) {
    return call(auth, "fulfillment.update", a -> monitor.putThreshold(input, a.userId()));
  }

  // ============================================================ 人工介入待办

  /** 人工介入待办列表（真分页；下游 1 工作台未交付，本服务内是权威待办）。 */
  @GetMapping("/todos")
  public FulfillmentMonitorTodoPage todos(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID caseId,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String severity,
      @RequestParam(required = false) String actionKind,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "fulfillment.read",
        a ->
            monitor.pageTodos(
                new FulfillmentMonitorTodoQuery(caseId, status, severity, actionKind, page, size)));
  }

  /** 登记人工介入待办项（{@code capturedAt} 与登记时刻分开，是租户可维护的）。 */
  @PostMapping("/cases/{id}/todos")
  public FulfillmentMonitorTodoRow createTodo(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody FulfillmentMonitorTodoInput input) {
    return call(auth, "fulfillment.update", a -> monitor.createTodo(id, input, a.userId()));
  }

  /** 待办认领 / 完成 / 取消。 */
  @PostMapping("/todos/{todoId}/action")
  public FulfillmentMonitorTodoRow actionTodo(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID todoId,
      @Valid @RequestBody FulfillmentMonitorTodoActionInput input) {
    return call(auth, "fulfillment.update", a -> monitor.actionTodo(todoId, input, a.userId()));
  }

  // ============================================================ 事件流

  /** 全部事件流（跨案例的最近事件，供看板「最近发生过什么」用）。 */
  @GetMapping("/events")
  public List<FulfillmentMonitorEventRow> events(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID caseId,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(auth, "fulfillment.read", a -> monitor.recentEvents(caseId, size));
  }
}
