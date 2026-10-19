package com.starcon.channel;

import static com.starcon.channel.PublishModels.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.web.bind.annotation.*;

/**
 * 4.3.1.2 批量发布。
 *
 * <p>蓝图：「批量刊登队列：按规则筛选待发布商品，生成任务批次，支持暂停、调整顺序、批量重试。输入：筛选规则与数量上限；发布策略；用户确认。输出：批次任务列表 + 总体进度 + 失败明细（可跳转 4.3.1.3）。关系：上游 = 4.1.1.1、4.2.1.2；下游 = 4.3.1.3。<b>批量任务必须限流</b>：按平台各自的 API 配额排队。」
 *
 * <p>端点按蓝图模块号标注，方便对照 04-listing-and-content.md。端点清单里**每一组**都对应
 * 职责句里的一个词：
 *
 * <ul>
 *   <li>{@code POST /batches} —— 「按规则筛选待发布商品 + 生成任务批次 + 数量上限」。
 *   <li>{@code GET /batches/{id}/tasks} —— 「输出：批次任务列表」（按状态筛，可按执行次序）。
 *   <li>{@code GET /batches/{id}} —— 「总体进度」（五个计数 + 百分比，服务端算好）。
 *   <li>{@code POST /batches/{id}/dispatch} —— 🔴「按平台各自的 API 配额排队」的一轮派发，
 *       逐限流行返回派发/跳过摘要。
 *   <li>{@code POST /batches/{id}/pause|resume|cancel} —— 「暂停」（停止派发新任务）。
 *   <li>{@code POST /batches/{id}/reorder} —— 「调整顺序」（真的改执行次序）。
 *   <li>{@code POST /batches/{id}/retry} —— 「批量重试」（只重试失败项）。
 *   <li>{@code GET|POST /rate-limits} —— 🔴 限流参数**可配置、可查**（数据，不是硬编码常量）。
 *   <li>{@code POST /tasks/{id}/report} —— 在途任务的回执（成功/失败），由 4.3.1.3 / 适配器调用。
 * </ul>
 *
 * <p>权限码<b>复用</b> identity 已有的 {@code channel.operation.*}（与 4.1.1.1 listing 列表、
 * 4.2.1.2 批量编辑同族），identity 服务零改动。读操作用 read，写操作用 create / update。
 */
@RestController
@RequestMapping("/channel/listing-publish")
public class PublishController {

  private final AccessService access;
  private final PublishService service;

  public PublishController(AccessService access, PublishService service) {
    this.access = access;
    this.service = service;
  }

  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, permission);
    return access.run(actor, permission, () -> work.apply(actor));
  }

  // ---- 能力声明 ----

  /**
   * 能力声明：SPU 状态筛选、实际调平台 API 两个维度 {@code available=false} + ownerModule。
   *
   * <p>前端据此显示「SPU 状态筛选未接入」「平台调用由 4.3.1.3 回报」，<b>不把在途画成已成功</b>。
   */
  @GetMapping("/capabilities")
  public PublishCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.capabilities());
  }

  // ---- 🔴 平台限流配置（可配置、可查的数据）----

  /** 限流配置列表：各平台（或店铺）各自的 API 配额，附当前在途/窗口占用。 */
  @GetMapping("/rate-limits")
  public List<PublishRateLimitRow> rateLimits(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String platformCode) {
    return call(auth, "channel.operation.read", a -> service.rateLimits(platformCode));
  }

  /**
   * 新建 / 更新限流配置（同平台+同店铺幂等 upsert）。
   *
   * <p>这些数字是**真实数据**，服务层没有写死的全局默认值兜底 —— 没配的平台不派发。
   */
  @PostMapping("/rate-limits")
  public PublishRateLimitRow saveRateLimit(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody PublishRateLimitRequest request) {
    return call(auth, "channel.operation.update", a -> service.saveRateLimit(request));
  }

  // ---- 建批次（筛选 + 数量上限 + 确认）----

  /** 生成任务批次（「生成任务批次」）。筛选参数形状照抄 4.1.1.1。 */
  @PostMapping("/batches")
  public PublishBatchRow createBatch(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody CreatePublishBatchRequest request) {
    return call(
        auth, "channel.operation.create", a -> service.createBatch(request, a.userId()));
  }

  /** 批次列表。 */
  @GetMapping("/batches")
  public List<PublishBatchRow> batches(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String state,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(auth, "channel.operation.read", a -> service.batches(state, page, size));
  }

  /** 单个批次（「总体进度」：五个计数 + 百分比）。 */
  @GetMapping("/batches/{batchId}")
  public PublishBatchRow batch(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String batchId) {
    return call(auth, "channel.operation.read", a -> service.loadBatchRow(batchId));
  }

  /** 批次任务列表（「输出：批次任务列表」；失败明细用 {@code state=FAILED} 取）。 */
  @GetMapping("/batches/{batchId}/tasks")
  public List<PublishTaskRow> tasks(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String batchId,
      @RequestParam(required = false) String state,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(auth, "channel.operation.read", a -> service.tasks(batchId, state, page, size));
  }

  // ---- 🔴 派发（按平台限流排队）----

  /**
   * 🔴 一轮派发：按平台（或店铺+平台）各自的配额排队。
   *
   * <p>返回<b>逐限流行</b>的摘要（Amazon 与 TikTok 各走各的账）；批次 PAUSED 时一条都不派。
   */
  @PostMapping("/batches/{batchId}/dispatch")
  public DispatchResponse dispatch(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String batchId) {
    return call(auth, "channel.operation.update", a -> service.dispatch(batchId));
  }

  // ---- 暂停 / 恢复 / 中止 ----

  /**
   * 🔴 暂停批次：停止派发新任务。<b>已在途的任务不取消</b>，仍可被 {@code /report} 回报结果。
   */
  @PostMapping("/batches/{batchId}/pause")
  public PublishBatchRow pause(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String batchId,
      @Valid @RequestBody(required = false) PublishBatchStateRequest request) {
    return call(auth, "channel.operation.update", a -> service.pause(batchId, request));
  }

  /** 恢复批次：回到 RUNNING，可继续派发剩余的 PENDING。 */
  @PostMapping("/batches/{batchId}/resume")
  public PublishBatchRow resume(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String batchId) {
    return call(auth, "channel.operation.update", a -> service.resume(batchId));
  }

  /** 人工中止：不再派发剩余任务；已在途的仍可回报结果。 */
  @PostMapping("/batches/{batchId}/cancel")
  public PublishBatchRow cancel(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String batchId,
      @Valid @RequestBody(required = false) PublishBatchStateRequest request) {
    return call(auth, "channel.operation.update", a -> service.cancel(batchId, request));
  }

  // ---- 🔴 重排 ----

  /** 🔴 调整顺序：按给的新次序重排待派发任务，派发按 position 升序取 —— 次次真的变。 */
  @PostMapping("/batches/{batchId}/reorder")
  public BatchActionResult reorder(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String batchId,
      @Valid @RequestBody ReorderRequest request) {
    return call(auth, "channel.operation.update", a -> service.reorder(batchId, request));
  }

  // ---- 🔴 批量重试 ----

  /** 🔴 批量重试：只重试 FAILED；已成功的绝不会被重跑（DB 触发器钉死）。 */
  @PostMapping("/batches/{batchId}/retry")
  public BatchActionResult retry(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String batchId,
      @Valid @RequestBody(required = false) RetryRequest request) {
    return call(auth, "channel.operation.update", a -> service.retry(batchId, request));
  }

  // ---- 在途任务回执 ----

  /** 在途（DISPATCHED）任务的成功/失败回执。成功则关联内容草稿转 PUBLISHED。 */
  @PostMapping("/tasks/{taskId}/report")
  public PublishTaskRow report(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String taskId,
      @RequestParam boolean success,
      @RequestParam(required = false) String failureReason) {
    return call(auth, "channel.operation.update", a -> service.report(taskId, success, failureReason));
  }
}
