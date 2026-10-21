package com.starcon.channel;

import static com.starcon.channel.ListingPublishTaskModels.*;

import java.util.List;
import java.util.function.Function;
import org.springframework.web.bind.annotation.*;

/**
 * 4.3.1.3 发布任务 —— 单次发布任务的进度视图。
 *
 * <p>蓝图：「单次发布任务的进度视图：各步骤状态（内容校验 → 平台创建 → 变体创建 → 媒体上传 → 价格库存设置 → 提交审核）、失败步骤与原因、可重试的步骤。输入：任务 ID；平台返回结果。输出：步骤时间线 + 错误明细 + 重试操作。关系：上游 = 4.3.1.1/4.3.1.2；下游 = 2.5.1.1。<b>平台提交审核是异步的</b>，任务在「审核中」结束，实际结果由 4.3.2.2 回流。」
 *
 * <p>🔴 端点清单里每一组都对应职责句里的一个词：
 *
 * <ul>
 *   <li>{@code GET /capabilities} —— 能力声明：阶段 → 步骤键的映射（含**没有独立步骤行**的阶段）、
 *       未交付段 available=false + ownerModule。
 *   <li>{@code GET /tasks} —— 「单次发布任务」的列表（真分页 + 任务来源 / 阶段 / 平台 / 店铺筛选）。
 *   <li>{@code GET /tasks/{uuid}} —— 任务详情 = 步骤时间线 + 错误明细 + 重试尝试。
 *   <li>{@code GET /tasks/{uuid}/steps} —— 「步骤时间线」（单独一条，便于只刷新时间线）。
 *   <li>{@code POST /tasks/{uuid}/retry} —— 「重试操作」的任务级粒度（派发就失败时的唯一出路）。
 *   <li>{@code POST /tasks/{uuid}/steps/{stepKey}/retry} —— 「重试操作」的步骤级粒度。
 * </ul>
 *
 * <p>🔴 <b>本前缀与兄弟模块不重叠</b>：4.3.1.1 一键刊登是 {@code one-click-publish}，
 * 4.3.1.2 批量发布是 {@code listing-publish}，本模块是 {@code listing-publish-tasks} ——
 * 三条通道各有各的状态机（单 SKU 多目标各自成败 / 批次队列按配额排队 / 单次任务进度视图），
 * 绝不揉进同一个端点或同一张表。
 *
 * <p>权限码<b>复用</b> identity 已有的 {@code channel.operation.*}（与 4.1.1.1 listing 列表、
 * 4.3.1.1 一键刊登、4.3.1.2 批量发布同族），identity 服务零改动。读操作用 read，
 * 重试用 update。
 */
@RestController
@RequestMapping("/channel/listing-publish-tasks")
public class ListingPublishTaskController {

  private final AccessService access;
  private final ListingPublishTaskService service;

  public ListingPublishTaskController(AccessService access, ListingPublishTaskService service) {
    this.access = access;
    this.service = service;
  }

  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, permission);
    return access.run(actor, permission, () -> work.apply(actor));
  }

  // ---- 能力声明 ----

  /**
   * 能力声明：蓝图阶段 → 步骤键的映射 + 未交付段。
   *
   * <p>前端据此显示「平台创建 / 变体创建 这两段没有独立步骤行（归 4.3.1.1）」，
   * <b>不凭空造两个步骤</b>。
   */
  @GetMapping("/capabilities")
  public ListingPublishTaskCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.capabilities());
  }

  // ---- 读 ----

  /**
   * 发布任务列表（真分页 + 真筛选）。
   *
   * <p>🔴 两条上游（4.3.1.1 的发布任务行、4.3.1.2 的队列任务）聚成一张列表，
   * 但每行都带 {@code taskKind} 与**原始** {@code state} —— 状态不揉。
   */
  @GetMapping("/tasks")
  public ListingPublishTaskPage tasks(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String kind,
      @RequestParam(required = false) String stage,
      @RequestParam(required = false) String platformId,
      @RequestParam(required = false) String shopId,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(
        auth, "channel.operation.read", a -> service.tasks(kind, stage, platformId, shopId, page, size));
  }

  /** 任务详情 = 步骤时间线 + 错误明细 + 重试尝试。 */
  @GetMapping("/tasks/{taskId}")
  public ListingPublishTaskDetail task(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String taskId) {
    return call(auth, "channel.operation.read", a -> service.task(taskId));
  }

  /** 步骤时间线（单独一条，便于界面只刷新时间线）。 */
  @GetMapping("/tasks/{taskId}/steps")
  public List<ListingPublishTaskStepRow> steps(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String taskId) {
    return call(auth, "channel.operation.read", a -> service.steps(taskId));
  }

  // ---- 重试 ----

  /**
   * 🔴 任务级重试：整条任务重来。
   *
   * <p>派发就失败的时候时间线上**没有任何一步是 FAILED**（步骤还停在 PENDING），
   * 步骤级重试无从谈起 —— 这时唯一有意义的就是重试整条任务。
   *
   * <p>实际动作**委托**给 4.3.1.1 的既有重试路径（目标级状态机的唯一写入所有者）；
   * 本模块只记一笔尝试留痕。**已驳回的任务不能重试**（内容没改，重发只会再被驳回一次）。
   */
  @PostMapping("/tasks/{taskId}/retry")
  public ListingPublishTaskRetryResult retryTask(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String taskId) {
    return call(auth, "channel.operation.update", a -> service.retryTask(taskId));
  }

  /**
   * 🔴 步骤级重试：重试时间线上的某一步。
   *
   * <p>只有 MEDIA / SUBMIT / RESULT 可重试（它们对应同一次平台发布请求，重试就是
   * 把**同一份冻结内容**重新提交一次）；内容快照已冻结的步骤与价格 / 库存快照已冻结的步骤
   * 会被拒，并写清原因。
   */
  @PostMapping("/tasks/{taskId}/steps/{stepKey}/retry")
  public ListingPublishTaskRetryResult retryStep(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String taskId,
      @PathVariable String stepKey) {
    return call(auth, "channel.operation.update", a -> service.retryStep(taskId, stepKey));
  }
}
