package com.starcon.channel;

import static com.starcon.channel.TrackingQueryModels.*;

import jakarta.validation.Valid;
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
 * 10.3.1.1 轨迹查询。
 *
 * <p>端点按蓝图模块号标注，方便对照 10-logistics-and-freight.md。
 *
 * <p>🔴 {@code POST /events} 是承运商推送的<b>唯一真实写入口</b>：
 * 事件真落 PostgreSQL 16，按承运商事件 ID 在 DB 层去重，
 * <b>同一节点重复推送不产生重复记录</b>。
 *
 * <p>🔴 {@code POST /pull-tasks} 是「按承运商批量拉取」的入口，
 * <b>由外部调度触发</b>（K8s CronJob / 平台任务执行器）——
 * 本服务<b>不自带调度器</b>：多副本下自带定时器要做主节点选举与分布式锁，
 * 那是平台能力不是领域能力，而外部触发一次入口天然单次、可重试、可审计。
 * 当前<b>没有已接通的 vendor 实现</b>，那一轮会如实落 {@code NO_CHANNEL}
 * 并写明理由，<b>不返回空轨迹冒充「承运商说没轨迹」</b>。
 *
 * <p>🔴 本控制器<b>刻意没有异常件处置入口</b>（联系承运商 / 理赔 / 补发 / 改址）：
 * 那是 10.3.1.2 的职责，本模块只<b>标记</b>异常节点。
 *
 * <p>权限码<b>复用</b> identity 已有的 {@code channel.operation.*}
 * （与 10.1.1.1 承运商、10.1.1.2 渠道配置、10.1.1.3 时效与限制、10.2.2.2 面单模板、
 * 10.3.2.1 交运批次同族），不需要改动 identity 服务。
 */
@RestController
@RequestMapping("/channel/logistics-tracking")
public class TrackingQueryController {

  private final AccessService access;
  private final TrackingQueryService tracking;

  public TrackingQueryController(AccessService access, TrackingQueryService tracking) {
    this.access = access;
    this.tracking = tracking;
  }

  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, permission);
    return access.run(actor, permission, () -> work.apply(actor));
  }

  // ---- 能力声明 ----

  @GetMapping("/capabilities")
  public TrackingCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> tracking.capabilities());
  }

  /** 概览：<b>全量口径</b>，不受列表筛选与分页影响。 */
  @GetMapping("/overview")
  public TrackingSubjectSummary overview(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> tracking.overview());
  }

  // ---- 轨迹清单与时间线 ----

  /** 轨迹清单：真分页 + 真筛选，汇总走独立查询不受 limit/offset 影响。 */
  @GetMapping("/subjects")
  public TrackingSubjectPage subjects(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String carrierCode,
      @RequestParam(required = false) String destinationCountry,
      @RequestParam(required = false) String nodeType,
      @RequestParam(required = false) String etaStatus,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "false") boolean exceptionOnly,
      @RequestParam(required = false, defaultValue = "LATEST_DESC") String sort,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "channel.operation.read",
        a ->
            tracking.subjects(
                carrierCode,
                destinationCountry,
                nodeType,
                etaStatus,
                status,
                keyword,
                exceptionOnly,
                sort,
                page,
                size));
  }

  /** 单票轨迹时间线 + 时效对比 + 边界说明。 */
  @GetMapping("/subjects/{id}")
  public TrackingTimelineDetail subject(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "channel.operation.read", a -> tracking.subject(id));
  }

  /**
   * 从上游 9.4.1.2 同步运单集合（幂等投影）。
   *
   * <p>上游不可达时返回 503（理由写「读不到」），<b>不是</b>空清单。
   */
  @PostMapping("/subjects/sync")
  public TrackingSyncResult sync(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody TrackingSyncInput input) {
    return call(auth, "channel.operation.create", a -> tracking.sync(input, auth));
  }

  // ---- 轨迹事件 ----

  /** 事件流（跨票）：真分页 + 真筛选。 */
  @GetMapping("/events")
  public TrackingEventPage events(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String carrierCode,
      @RequestParam(required = false) String nodeType,
      @RequestParam(required = false) String subjectId,
      @RequestParam(required = false, defaultValue = "false") boolean exceptionOnly,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "channel.operation.read",
        a -> tracking.events(carrierCode, nodeType, subjectId, exceptionOnly, page, size));
  }

  /**
   * 承运商推送轨迹事件（批量）。
   *
   * <p>去重键 = 承运商事件 ID，落点是 <b>DB 唯一约束</b>：
   * 同一节点重复推送不产生重复记录；被拒的条目逐条如实列出。
   */
  @PostMapping("/events")
  public TrackingEventPushResult push(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody TrackingEventPushInput input) {
    return call(auth, "channel.operation.create", a -> tracking.push(input, a.membershipId()));
  }

  // ---- 按承运商批量拉取（轮询任务） ----

  /**
   * 建一轮「按承运商批量拉取」并执行。
   *
   * <p>没有已接通的 vendor 实现时，这一轮如实落 {@code NO_CHANNEL} 并写明理由。
   */
  @PostMapping("/pull-tasks")
  public TrackingPullTaskDetail createPullTask(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody TrackingPullTaskInput input) {
    return call(auth, "channel.operation.create", a -> tracking.createPullTask(input, a.membershipId()));
  }

  /** 拉取任务列表：真分页 + 真筛选。 */
  @GetMapping("/pull-tasks")
  public TrackingPullTaskPage pullTasks(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String carrierCode,
      @RequestParam(required = false) String status,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(auth, "channel.operation.read", a -> tracking.pullTasks(carrierCode, status, page, size));
  }

  /** 拉取任务详情（含逐票结果）。 */
  @GetMapping("/pull-tasks/{id}")
  public TrackingPullTaskDetail pullTask(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "channel.operation.read", a -> tracking.pullTask(id));
  }
}
