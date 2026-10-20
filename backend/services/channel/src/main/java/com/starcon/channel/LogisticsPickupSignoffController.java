package com.starcon.channel;

import static com.starcon.channel.LogisticsPickupSignoffModels.*;

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
 * 10.3.2.2 揽收与签收确认。
 *
 * <p>端点按蓝图模块号标注，方便对照 10-logistics-and-freight.md。
 *
 * <p>🔴 {@code POST /reconcile} 是本模块的<b>主动监控</b>入口：
 * 蓝图加粗句「揽收状态直接影响平台履约指标，需主动监控而非等轨迹同步」，
 * 所以状态推进有一个**显式的巡检端点**，而不是挂在轨迹同步顺带产生。
 * 它<b>不自带调度器</b>：多副本下自带定时器要做主节点选举与分布式锁，
 * 那是平台能力不是领域能力；由外部调度（K8s CronJob / 平台任务执行器）触发一次入口
 * 反而天然单次、可重试、可审计。
 *
 * <p>🔴 下游 9.4.2.1 回传与 15 客服（索评 / 售后窗口）在本服务里只是
 * <b>待通知记录</b>：9.4.2.1 已在 trade 交付但 channel 与它之间没有接线，
 * 15.2.2.1 索评未开发。所以本控制器**没有任何跨服务调用**，
 * 待通知状态到 READY 为止 —— <b>不假装已回传</b>。
 *
 * <p>权限码<b>复用</b> identity 已有的 {@code channel.operation.*}
 * （与 10.1.1.1 承运商、10.1.1.2 渠道配置、10.1.1.3 时效与限制、10.3.1.1 轨迹查询、
 * 10.3.2.1 交运批次同族），不需要改动 identity 服务。
 */
@RestController
@RequestMapping("/channel/logistics-pickup-signoff")
public class LogisticsPickupSignoffController {

  private final AccessService access;
  private final LogisticsPickupSignoffService service;

  public LogisticsPickupSignoffController(AccessService access, LogisticsPickupSignoffService service) {
    this.access = access;
    this.service = service;
  }

  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, permission);
    return access.run(actor, permission, () -> work.apply(actor));
  }

  // ---- 能力声明 ----

  @GetMapping("/capabilities")
  public LogisticsPickupSignoffCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.capabilities());
  }

  /** 概览：<b>全量口径</b>，不受列表筛选与分页影响。 */
  @GetMapping("/overview")
  public PickupSignoffSummary overview(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.overview());
  }

  // ---- 确认单 ----

  /** 确认单清单：真分页 + 真筛选，汇总走独立查询不受 limit/offset 影响。 */
  @GetMapping("/cases")
  public PickupSignoffCasePage cases(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String carrierCode,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String pickupState,
      @RequestParam(required = false) String deliveryState,
      @RequestParam(required = false) String batchNo,
      @RequestParam(required = false) String gapCode,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "false") boolean overdueOnly,
      @RequestParam(required = false, defaultValue = "false") boolean exceptionOnly,
      @RequestParam(required = false, defaultValue = "HANDOVER_AT_DESC") String sort,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "channel.operation.read",
        a ->
            service.cases(
                carrierCode,
                status,
                pickupState,
                deliveryState,
                batchNo,
                gapCode,
                keyword,
                overdueOnly,
                exceptionOnly,
                sort,
                page,
                size));
  }

  /** 单件确认单详情：单 + 时间线 + 提醒 + 下游待通知 + 边界说明。 */
  @GetMapping("/cases/{id}")
  public PickupSignoffCaseDetail caseDetail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "channel.operation.read", a -> service.caseDetail(UUID.fromString(id)));
  }

  /**
   * 从 10.3.2.1 交运批次同步确认单（幂等投影）。
   *
   * <p>只有**已登记交接**的批次才建单：批次还是 DRAFT 时责任还没交出去，
   * 此时判「超时未揽收」是无中生有。
   */
  @PostMapping("/cases/sync")
  public PickupSignoffSyncResult sync(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody PickupSignoffSyncInput input) {
    return call(auth, "channel.operation.create", a -> service.sync(input));
  }

  /**
   * 巡检：推进揽收/签收状态、判定超时、生成提醒与下游待通知。
   *
   * <p>这是蓝图加粗句「需主动监控而非等轨迹同步」的入口。
   */
  @PostMapping("/reconcile")
  public PickupSignoffReconcileResult reconcile(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody PickupSignoffReconcileInput input) {
    return call(auth, "channel.operation.update", a -> service.reconcile(input));
  }

  /** 人工确认揽收 / 签收（承运商通道未接通时的凭据登记）。 */
  @PostMapping("/cases/{id}/attest")
  public PickupSignoffCaseDetail attest(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @Valid @RequestBody PickupSignoffAttestInput input) {
    return call(
        auth, "channel.operation.update", a -> service.attest(UUID.fromString(id), input, a.membershipId()));
  }

  // ---- 提醒任务 ----

  /** 提醒任务列表：真分页 + 真筛选。 */
  @GetMapping("/reminders")
  public PickupSignoffReminderPage reminders(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String carrierCode,
      @RequestParam(required = false) String reminderKind,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String batchNo,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "channel.operation.read",
        a -> service.reminders(carrierCode, reminderKind, status, batchNo, page, size));
  }

  /** 认领提醒（PENDING → ACKNOWLEDGED）。 */
  @PostMapping("/reminders/{id}/acknowledge")
  public PickupSignoffReminderRow acknowledgeReminder(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @Valid @RequestBody PickupSignoffReminderActionInput input) {
    return call(
        auth,
        "channel.operation.update",
        a -> service.acknowledgeReminder(UUID.fromString(id), input, a.membershipId()));
  }

  /** 处置完成（→ RESOLVED）。 */
  @PostMapping("/reminders/{id}/resolve")
  public PickupSignoffReminderRow resolveReminder(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @Valid @RequestBody PickupSignoffReminderActionInput input) {
    return call(
        auth,
        "channel.operation.update",
        a -> service.resolveReminder(UUID.fromString(id), input, a.membershipId()));
  }

  // ---- 下游待通知 ----

  /** 下游待通知列表：真分页 + 真筛选。 */
  @GetMapping("/notifications")
  public PickupSignoffNotificationPage notifications(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String carrierCode,
      @RequestParam(required = false) String notificationKind,
      @RequestParam(required = false) String downstreamStatus,
      @RequestParam(required = false) String batchNo,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "channel.operation.read",
        a ->
            service.notifications(
                carrierCode, notificationKind, downstreamStatus, batchNo, page, size));
  }

  /**
   * 登记下游消费结果。
   *
   * <p>本模块**不跨服务调用**下游，所以这里登记的是「下游取走了没有」，
   * 不是「本模块回传出去了」。
   */
  @PostMapping("/notifications/{id}/consume")
  public PickupSignoffNotificationRow consumeNotification(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @Valid @RequestBody PickupSignoffNotificationConsumeInput input) {
    return call(
        auth,
        "channel.operation.update",
        a -> service.consumeNotification(UUID.fromString(id), input, a.membershipId()));
  }

  // ---- 确认事件流 ----

  /** 确认事件流：真分页 + 真筛选。 */
  @GetMapping("/events")
  public PickupSignoffEventPage events(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String eventKind,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String batchNo,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(auth, "channel.operation.read", a -> service.events(eventKind, status, batchNo, page, size));
  }

  // ---- 时限规则 ----

  /** 时限规则列表：真分页 + 真筛选。 */
  @GetMapping("/sla-rules")
  public PickupSignoffSlaRulePage slaRules(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String carrierCode,
      @RequestParam(required = false) String status,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(auth, "channel.operation.read", a -> service.slaRules(carrierCode, status, page, size));
  }

  /**
   * 登记 / 改时限规则。
   *
   * <p>带 channelId 是渠道级规则（优先于承运商级），不带是承运商级。
   * 规则改动**不影响已存在的确认单** —— 确认单上的时限是建单时的快照。
   */
  @PostMapping("/sla-rules")
  public PickupSignoffSlaRuleRow registerSlaRule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody PickupSignoffSlaRuleInput input) {
    return call(auth, "channel.operation.create", a -> service.registerSlaRule(input));
  }
}
