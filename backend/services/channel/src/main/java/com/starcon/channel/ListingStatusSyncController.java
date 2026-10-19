package com.starcon.channel;

import static com.starcon.channel.ListingStatusSyncModels.*;

import jakarta.validation.Valid;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.web.bind.annotation.*;

/**
 * 4.3.2.2 平台状态回流。
 *
 * <p>蓝图：「接收并展示平台侧的商品状态变化：审核通过/驳回、下架、限流、被抑制（suppressed）、类目审核、价格异常通知，并支持一键处理（如按驳回原因批量修正重发）。输入：平台事件/webhook；轮询补充。输出：状态变更记录 + 需处理队列。关系：上游 = 02 同步；下游 = 4.1.1.3、4.3.1.3。<b>回流是平台状态的权威来源</b>，内部状态与之冲突时以平台为准并告警。」
 *
 * <p>端点按蓝图模块号标注，端点清单里**每一组**都对应职责句里的一个词：
 *
 * <ul>
 *   <li>{@code GET /capabilities} —— 逐维度能力声明 + 冲突判定口径 + 「以平台为准」的处置口径。
 *       五段未交付（真实 webhook / 轮询调度器 / 4.1.1.3 生命周期联动 / 4.3.1.3 发布任务视图 /
 *       16.2.1.2 合规拦截升级）都 available=false + ownerModule，前端据此显示「这归谁管」，
 *       而不是显示一个必然失败的按钮。
 *   <li>{@code POST /events} —— 🔴「输入：平台事件/webhook；轮询补充」：接收一条平台状态事件，
 *       算冲突、落状态变更记录、生成需处理队列项（冲突告警由 DB 触发器落）。
 *   <li>{@code GET /events} / {@code GET /events/{id}} —— 「输出：状态变更记录」（真分页 + 筛选）。
 *   <li>{@code GET /queue} / {@code GET /queue/{id}} —— 「输出：需处理队列」（真分页 + 筛选）。
 *   <li>{@code GET /conflicts} —— 🔴 冲突与告警区（只取 CONFLICT_ALERT 行）。
 *   <li>{@code POST /actions/republish} —— 🔴「支持一键处理（如按驳回原因批量修正重发）」：
 *       按驳回原因筛出待办并批量发起修正重发，走 2.5.1.1 的 {@code platform_operation} 既有队列，
 *       响应里回 {@code operationIds}（回执）。
 *   <li>{@code POST /queue/{id}/acknowledge} / {@code POST /queue/{id}/resolve} —— 人工确认 / 处理完成。
 *   <li>{@code GET /listings/{listingId}/history} —— 某个商品的状态变更记录（回流视角）。
 * </ul>
 *
 * <p>🔴 路径前缀刻意是 {@code /channel/listing-status-events}：4.1.1.1 把
 * {@code listings/{uuid}/...} 那一段路径登记成「不放行」，在同一棵路径树上开子资源要同时改别人
 * 已交付的白名单与拒绝清单；而「平台状态回流」与「内容回写」本来就是**两条独立通道**
 * （一个把内部变更推出去、一个把平台状态收回来），状态机、限流与重试互不干涉。
 *
 * <p>权限码<b>复用</b> identity 已有的 {@code channel.operation.*}（与 4.1.1.1 列表、
 * 4.2.1.2 批量编辑、4.3.1.2 批量发布、4.3.2.1 内容回写、2.5.2.1 常用回写操作同族），
 * identity 服务零改动。
 */
@RestController
@RequestMapping("/channel/listing-status-events")
public class ListingStatusSyncController {

  private final AccessService access;
  private final ListingStatusSyncService service;

  public ListingStatusSyncController(AccessService access, ListingStatusSyncService service) {
    this.access = access;
    this.service = service;
  }

  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, permission);
    return access.run(actor, permission, () -> work.apply(actor));
  }

  // ---- 能力声明 ----

  /**
   * 能力声明：真实平台 webhook / 轮询调度器 / 4.1.1.3 生命周期联动 / 4.3.1.3 发布任务视图 /
   * 16.2.1.2 合规拦截升级 五个维度 {@code available=false} + ownerModule。
   *
   * <p>前端据此显示「这归谁管」，<b>不把未交付的步骤画成已完成</b>，也<b>不显示一个必然
   * 404 的按钮</b>。
   */
  @GetMapping("/capabilities")
  public ListingStatusCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.capabilities());
  }

  // ---- 🔴 回流入库 ----

  /**
   * 🔴 接收一条平台状态事件。
   *
   * <p>本端点是**回流入库**的唯一入口：02 平台适配器把平台状态推过来、或轮询器补捞到状态变化，
   * 都走它。真实平台 webhook 的**推送通道**归 02 适配器（能力表里 available=false）——
   * 本模块不另建一条 webhook 通道，两个入口会让同一件事有两个投递路径。
   *
   * <p>响应里带回那条事件（含服务端算好的冲突判定与告警），让调用方立刻看到"这条算不算冲突"。
   */
  @PostMapping("/events")
  public ListingStatusEventRow ingest(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody IngestStatusEventRequest request) {
    return call(auth, "channel.operation.create", a -> service.ingest(request, a.userId()));
  }

  // ---- 状态变更记录 ----

  /**
   * 状态变更记录列表（真分页 + 筛选）。
   *
   * <p>🔴 默认只列 {@code source='PLATFORM'}（本模块记的是平台回流那几条）；
   * {@code source=ALL} 才把内部人工改状态那几条也带出来。
   */
  @GetMapping("/events")
  public ListingStatusEventPage events(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String source,
      @RequestParam(required = false) String eventType,
      @RequestParam(required = false) String shopCode,
      @RequestParam(required = false) String platformCode,
      @RequestParam(required = false) Boolean conflict,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "channel.operation.read",
        a ->
            service.events(source, eventType, shopCode, platformCode, conflict, page, size));
  }

  /** 单条状态变更记录。 */
  @GetMapping("/events/{eventId}")
  public ListingStatusEventRow event(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String eventId) {
    return call(auth, "channel.operation.read", a -> service.event(eventId));
  }

  // ---- 需处理队列 ----

  /**
   * 需处理队列（真分页 + 筛选）。
   *
   * <p>筛选口径：{@code kind}（REPUBLISH / REVIEW / CONFLICT_ALERT）、{@code state}、
   * {@code eventType}、{@code rejectionReason}（🔴 一键处理按它筛）、{@code shopCode}。
   */
  @GetMapping("/queue")
  public ListingStatusQueuePage queue(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String kind,
      @RequestParam(required = false) String state,
      @RequestParam(required = false) String eventType,
      @RequestParam(required = false) String rejectionReason,
      @RequestParam(required = false) String shopCode,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "channel.operation.read",
        a ->
            service.queue(
                kind, state, eventType, rejectionReason, shopCode, page, size));
  }

  /** 单条队列项。 */
  @GetMapping("/queue/{queueId}")
  public ListingStatusQueueRow queueItem(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String queueId) {
    return call(auth, "channel.operation.read", a -> service.queueItem(queueId));
  }

  // ---- 🔴 冲突与告警 ----

  /**
   * 🔴 冲突与告警区：内部状态与平台状态冲突的告警行。
   *
   * <p>每一条都带「内部状态 / 平台状态 / 以平台为准」的逐字说明 —— 页面据此显示
   * 「<b>以平台为准</b>」，**前端不重算冲突判定**。
   */
  @GetMapping("/conflicts")
  public ListingStatusQueuePage conflicts(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(auth, "channel.operation.read", a -> service.conflicts(page, size));
  }

  // ---- 🔴 一键处理 ----

  /**
   * 🔴 一键处理：按驳回原因批量修正重发。
   *
   * <p>动作走 2.5.1.1 的 {@code platform_operation} 既有队列（幂等键唯一），
   * <b>不新建平行任务表</b>；重复点不会产生第二个平台任务；已派发过的逐条写明原因跳过。
   *
   * <p>响应是**回执**：逐条列出真的发起了什么、队列任务 id 是什么、平台侧当前状态是什么。
   */
  @PostMapping("/actions/republish")
  public RepublishReceipt republish(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody(required = false) RepublishRequest request) {
    RepublishRequest body = request == null ? new RepublishRequest(null, null, null, null, null, null) : request;
    return call(auth, "channel.operation.update", a -> service.republish(body, a.userId()));
  }

  // ---- 人工动作 ----

  /** 人工确认：OPEN → ACKNOWLEDGED。 */
  @PostMapping("/queue/{queueId}/acknowledge")
  public ListingStatusQueueRow acknowledge(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String queueId,
      @Valid @RequestBody(required = false) StatusActionRequest request) {
    String note = request == null ? "" : nullToEmpty(request.note());
    return call(auth, "channel.operation.update", a -> service.acknowledge(queueId, note, a.userId()));
  }

  /** 人工处理完成：OPEN / ACKNOWLEDGED / DISPATCHED → RESOLVED。 */
  @PostMapping("/queue/{queueId}/resolve")
  public ListingStatusQueueRow resolve(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String queueId,
      @Valid @RequestBody(required = false) StatusActionRequest request) {
    String note = request == null ? "" : nullToEmpty(request.note());
    return call(auth, "channel.operation.update", a -> service.resolve(queueId, note, a.userId()));
  }

  // ---- 商品维度 ----

  /** 某个商品的状态变更记录（回流视角；默认只列平台来源）。 */
  @GetMapping("/listings/{listingId}/history")
  public ListingStatusEventPage listingHistory(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String listingId,
      @RequestParam(required = false) String source,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(auth, "channel.operation.read", a -> service.listingHistory(listingId, source, page, size));
  }

  private static String nullToEmpty(String value) {
    return value == null ? "" : value;
  }
}
