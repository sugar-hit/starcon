package com.starcon.channel;

import static com.starcon.channel.AdBulkOperationModels.*;

import jakarta.validation.Valid;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.web.bind.annotation.*;

/**
 * 14.1.1.3 批量操作。
 *
 * <p>蓝图：「跨活动批量调整：预算增减、竞价调整、状态切换、结束时间设置，支持按筛选结果全选并预览变更。
 * 输入：选中活动集合；调整参数；预览确认。输出：批量调整任务（异步）+ 每条执行结果。
 * 关系：上游 = 14.1.1.1；下游 = 平台广告 API（经 2 队列）。
 * <b>批量调价要有单次上限与总消耗上限保护</b>，误操作一次可能烧掉整月预算。」
 *
 * <p>🔴 入口按职责句逐个对应：
 *
 * <ul>
 *   <li>{@code GET /selection} —— 「<b>按筛选结果全选</b>」：**服务端**按筛选条件解析出 id 集合
 *       （分页 + 游标 + 硬上限）。🔴 前端**不**能把 id 全拉回来再循环 —— 那等于全表扫。
 *   <li>{@code POST /preview} —— 「<b>预览变更</b>」：**不落库**的预演，
 *       给出逐条「会变成什么样」+ 总预算增量 + 护栏裁决（超上限 ⇒ 整批拒绝）。
 *   <li>{@code POST /tasks} —— 「<b>批量调整任务（异步）</b>」：受理即返回任务，
 *       逐条结果事后查。
 *   <li>{@code GET /tasks} / {@code GET /tasks/{uuid}} —— 「<b>每条执行结果</b>」。
 *   <li>{@code GET /capabilities} / {@code GET /options} —— 能力声明与护栏上限
 *       （界面上要能写明「本次最多 N 个」）。
 * </ul>
 *
 * <p>权限码<b>复用</b> identity 已有的 {@code channel.operation.*}（与 14.1.1.1 / 14.1.1.2 同族），
 * identity 服务零改动。
 */
@RestController
@RequestMapping("/channel/ad-bulk-operations")
public class AdBulkOperationController {

  private final AccessService access;
  private final AdBulkOperationService service;
  private final AdBulkOperationExecutor executor;

  public AdBulkOperationController(
      AccessService access, AdBulkOperationService service, AdBulkOperationExecutor executor) {
    this.access = access;
    this.service = service;
    this.executor = executor;
  }

  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, permission);
    return access.run(actor, permission, () -> work.apply(actor));
  }

  // ---- 能力声明与选项 ----

  /** 能力声明：未交付的段（平台回写、消耗预估、广告组管理）显式 available=false。 */
  @GetMapping("/capabilities")
  public AdBulkCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.capabilities());
  }

  /** 筛选项与护栏上限（下拉项 + 「本次最多批量操作多少条」这类界面上要写明的数）。 */
  @GetMapping("/options")
  public AdBulkOptions options(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.options());
  }

  // ---- 按筛选结果全选 ----

  /**
   * 按筛选条件解析出目标 id 集合的一页。
   *
   * <p>🔴 这是「按筛选结果全选」的**唯一**入口形态：解析在服务端（SQL 里）完成，
   * 前端只拿到一页 id 与一个计数。命中数超过单次上限时 {@code truncated=true}，
   * 此时提交批量任务会被**整批拒绝** —— 界面据此提示「请先缩小筛选范围」。
   */
  @GetMapping("/selection")
  public AdBulkSelection selection(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String platformId,
      @RequestParam(required = false) String shopCode,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String internalAdType,
      @RequestParam(required = false) String platformAdType,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "CAMPAIGN") String targetType,
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "channel.operation.read",
        a ->
            service.selection(
                blankUuid(platformId),
                shopCode,
                status,
                internalAdType,
                platformAdType,
                keyword,
                targetType,
                cursor,
                size));
  }

  // ---- 预览 ----

  /**
   * 预览变更（**不落库**）。
   *
   * <p>🔴 预览算出来的就是提交会执行的 —— 两个端点吃同一份载荷、
   * 共用同一套预演逻辑（{@code AdBulkOperationRules}），不会出现「预览的是一个意思、
   * 提交的是另一个意思」。
   */
  @PostMapping("/preview")
  public AdBulkPreview preview(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody AdBulkOperationRequest request) {
    return call(auth, "channel.operation.read", a -> service.preview(request));
  }

  // ---- 批量任务 ----

  /**
   * 受理一次批量调整：落一张任务 + 一批任务项，**异步**执行。
   *
   * <p>🔴 三段式提交（顺序不能换）：
   *
   * <ol>
   *   <li>创建任务（事务 1）—— 提交后工作线程才看得见这张任务；
   *   <li>领取任务（事务 2）—— 把 PENDING 推进成 RUNNING，由 DB 裁决谁领到了；
   *   <li>丢进线程池 —— 逐条执行在请求之外发生。
   * </ol>
   *
   * <p>🔴 护栏越限时任务**整批被拒**（status=REJECTED，一条都没执行），
   * 但任务行仍然写库 —— 「有人差点烧掉整月预算」这件事本身就要留痕。
   */
  @PostMapping("/tasks")
  public AdBulkTaskSubmission tasks(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody AdBulkOperationRequest request) {
    var actor = access.require(auth, "channel.operation.update");
    // 事务 1：创建任务并提交
    var created = access.run(actor, "channel.operation.update", () -> service.createTask(request));
    // 事务 2：领取（REJECTED 的任务领不到，也不需要执行）
    executor.accept(actor, "channel.operation.update", created.id());
    // 事务 3：读回真实状态（执行器可能已经跑完了）
    var detail = access.run(actor, "channel.operation.read", () -> service.task(created.id().toString(), 0, 200));
    return new AdBulkTaskSubmission(
        detail.task(),
        !"REJECTED".equals(detail.task().status()),
        "🔴 本次改动只落**本地**并标记「待回写平台」：平台回写队列尚未交付，"
            + "界面上要显式写「本地已改、还没回写平台」。"
            + "逐条结果用 GET /channel/ad-bulk-operations/tasks/{uuid} 查。");
  }

  /** 批量任务列表（状态可查）。 */
  @GetMapping("/tasks")
  public AdBulkTaskPage tasks(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(auth, "channel.operation.read", a -> service.tasks(page, size));
  }

  /** 单个批量任务 + 逐条执行结果。 */
  @GetMapping("/tasks/{id}")
  public AdBulkTaskDetail task(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "200") int size) {
    return call(auth, "channel.operation.read", a -> service.task(id, page, size));
  }

  /** 空串与 null 同义，避免把 {@code ?platformId=} 当成「筛选空字符串」。 */
  private static UUID blankUuid(String value) {
    if (value == null || value.isBlank()) return null;
    try {
      return UUID.fromString(value.trim());
    } catch (IllegalArgumentException e) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.BAD_REQUEST, "平台 id 不是合法 UUID");
    }
  }
}
