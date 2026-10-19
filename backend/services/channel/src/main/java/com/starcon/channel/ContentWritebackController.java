package com.starcon.channel;

import static com.starcon.channel.ContentWritebackModels.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.web.bind.annotation.*;

/**
 * 4.3.2.1 内容回写。
 *
 * <p>蓝图：「把内部已发布的商品内容变更推送到平台（改标题、改图、改描述、改 A+），支持按变更类型触发或批量触发，并记录回写前后快照。输入：内容变更事件；用户批量回写操作；目标 listing 集合。输出：回写任务（经 02 队列）+ 变更历史。关系：上游 = 3 商品主数据变更、4.2.1.2 批量编辑；下游 = 2.5.1.1。<b>内容回写不触碰价格与库存</b>（那是 05/07 的事），三条回写通道独立失败独立重试。」
 *
 * <p>端点按蓝图模块号标注，端点清单里**每一组**都对应职责句里的一个词：
 *
 * <ul>
 *   <li>{@code GET /capabilities} —— 逐维度能力声明 + 三条回写通道的归属（价格 / 库存
 *       available=false + ownerModule，前端据此显示「改价请去 05 定价」而不是显示一个按钮）。
 *   <li>{@code POST /writebacks} —— 🔴「按变更类型触发 / 批量触发」：目标 listing 集合 + 变更类型，
 *       提交那一刻逐条算出改前 / 改后并落库。
 *   <li>{@code GET /writebacks} / {@code GET /writebacks/{id}} —— 总体进度（六个计数 + 百分比）。
 *   <li>{@code GET /writebacks/{id}/tasks} —— 「输出：回写任务」（含逐条跳过的原因）。
 *   <li>{@code POST /writebacks/{id}/dispatch} —— 🔴 一轮派发：按各平台自己的配额排队，
 *       并把派发出去的写成 02 队列任务（响应里回 {@code queuedOperationIds}）。
 *   <li>{@code GET /tasks/{id}/changes} —— 🔴「记录回写前后快照」：逐字段改前 / 改后 + 两侧来源。
 *   <li>{@code POST /tasks/{id}/report} —— 在途任务的成功 / 失败回执（4.3.2.2 / 平台适配器调用）。
 *   <li>{@code POST /writebacks/{id}/retry} —— 🔴「独立失败独立重试」：只重试失败项。
 *   <li>{@code POST /writebacks/{id}/cancel} —— 人工中止（已在途的不取消）。
 *   <li>{@code GET /listings/{listingId}/content-history} —— 商品维度的变更历史。
 * </ul>
 *
 * <p>🔴 路径前缀刻意是 {@code /channel/listing-content-writeback}：4.1.1.1 把
 * {@code listings/{uuid}/...} 那一段路径登记成「不放行」，在同一棵路径树上开子资源要同时改别人
 * 已交付的白名单与拒绝清单；而「批量发布队列」与「内容回写」本来就是两种资源。
 *
 * <p>权限码<b>复用</b> identity 已有的 {@code channel.operation.*}（与 4.1.1.1 列表、
 * 4.2.1.2 批量编辑、4.3.1.2 批量发布、2.5.2.1 常用回写操作同族），identity 服务零改动。
 */
@RestController
@RequestMapping("/channel/listing-content-writeback")
public class ContentWritebackController {

  private final AccessService access;
  private final ContentWritebackService service;

  public ContentWritebackController(AccessService access, ContentWritebackService service) {
    this.access = access;
    this.service = service;
  }

  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, permission);
    return access.run(actor, permission, () -> work.apply(actor));
  }

  // ---- 能力声明 ----

  /**
   * 能力声明：实际调平台 API / 上游商品变更事件 / 描述与 A+ 的内部基线 三个维度
   * {@code available=false} + ownerModule。
   *
   * <p>前端据此显示「平台往返由 4.3.2.2 回报」「改价请走 05 定价」「描述与 A+ 需显式给值」，
   * <b>不把未交付的步骤画成已完成</b>，也<b>不显示一个必然被拒的改价按钮</b>。
   */
  @GetMapping("/capabilities")
  public ContentWritebackCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.capabilities());
  }

  // ---- 建一次回写（按变更类型 / 批量触发）----

  /**
   * 🔴 建一次内容回写。
   *
   * <p>提交那一刻就把每条目标 listing 的**改前**（平台侧当前快照）与**改后**（内部草稿 / 显式
   * 覆盖值）算好并落库，所以界面上看到的和真正推出去的是同一批数据。
   *
   * <p>含 PRICE / STOCK / CATEGORY 的请求会被 <b>422</b> 并逐个点名 —— 内容通道不处理它们
   * （蓝图加粗句），静默忽略会让用户以为价格也推上去了。
   */
  @PostMapping("/writebacks")
  public ContentWritebackRow create(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody CreateContentWritebackRequest request) {
    return call(
        auth, "channel.operation.create", a -> service.create(request, a.userId()));
  }

  /** 回写触发列表。 */
  @GetMapping("/writebacks")
  public List<ContentWritebackRow> writebacks(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String state,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(auth, "channel.operation.read", a -> service.writebacks(state, page, size));
  }

  /** 单次回写触发的总体进度（六个计数 + 百分比，服务端算好）。 */
  @GetMapping("/writebacks/{writebackId}")
  public ContentWritebackRow writeback(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String writebackId) {
    return call(auth, "channel.operation.read", a -> service.loadWritebackRow(writebackId));
  }

  /** 回写任务列表（跳过原因用 {@code state=SKIPPED_NO_CHANGE} 取；失败明细用 {@code state=FAILED}）。 */
  @GetMapping("/writebacks/{writebackId}/tasks")
  public List<ContentWritebackTaskRow> tasks(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String writebackId,
      @RequestParam(required = false) String state,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(auth, "channel.operation.read", a -> service.tasks(writebackId, state, page, size));
  }

  // ---- 🔴 派发（按平台限流排队 + 经 02 队列）----

  /**
   * 🔴 一轮派发：按平台（或店铺+平台）各自的配额排队，派发出去的写成 02 队列任务。
   *
   * <p>响应里的 {@code queuedOperationIds} 可直接拿去 2.5.1.1 的回写任务列表核验 ——
   * 「经队列」是可查事实。触发不是 RUNNING 时一条都不派。
   */
  @PostMapping("/writebacks/{writebackId}/dispatch")
  public ContentWritebackDispatchResponse dispatch(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String writebackId) {
    return call(auth, "channel.operation.update", a -> service.dispatch(writebackId));
  }

  // ---- 🔴 回写前后快照（变更历史）----

  /** 🔴 一条任务的逐字段「改前 vs 改后」，含两侧来源（平台侧快照 / 内部草稿 / 显式覆盖值）。 */
  @GetMapping("/tasks/{taskId}/changes")
  public List<ContentWritebackChangeRow> changes(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String taskId) {
    return call(auth, "channel.operation.read", a -> service.changes(taskId));
  }

  /** 某个 listing 的内容回写变更历史（按时间倒序，一次触发带它全部的字段变更）。 */
  @GetMapping("/listings/{listingId}/content-history")
  public List<ListingContentHistoryEntry> listingHistory(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String listingId) {
    return call(auth, "channel.operation.read", a -> service.listingHistory(listingId));
  }

  // ---- 🔴 重试 / 中止 ----

  /** 🔴 批量重试：只重试 FAILED；已成功的绝不会被重跑（DB 触发器钉死），在途的必须先回报结果。 */
  @PostMapping("/writebacks/{writebackId}/retry")
  public ContentWritebackActionResult retry(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String writebackId,
      @Valid @RequestBody(required = false) RetryContentWritebackRequest request) {
    return call(auth, "channel.operation.update", a -> service.retry(writebackId, request));
  }

  /** 人工中止：不再派发剩余任务；已在途的仍可回报结果（已发出的平台写请求撤不回）。 */
  @PostMapping("/writebacks/{writebackId}/cancel")
  public ContentWritebackRow cancel(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String writebackId,
      @Valid @RequestBody(required = false) ContentWritebackStateRequest request) {
    return call(auth, "channel.operation.update", a -> service.cancel(writebackId, request));
  }

  // ---- 在途任务回执 ----

  /**
   * 在途（DISPATCHED）任务的成功 / 失败回执，由 4.3.2.2 / 平台适配器调用。
   *
   * <p>成功时写一条 {@code listing_publish_record(action='CONTENT_WRITEBACK')}（V175 预留的取值），
   * 失败必须带原因。本端点<b>不伪造平台返回</b>。
   */
  @PostMapping("/tasks/{taskId}/report")
  public ContentWritebackTaskRow report(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String taskId,
      @RequestParam boolean success,
      @RequestParam(required = false) String failureReason) {
    return call(auth, "channel.operation.update", a -> service.report(taskId, success, failureReason));
  }
}
