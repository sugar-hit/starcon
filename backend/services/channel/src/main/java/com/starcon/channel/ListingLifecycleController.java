package com.starcon.channel;

import static com.starcon.channel.ListingLifecycleModels.*;

import jakarta.validation.Valid;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.web.bind.annotation.*;

/**
 * 4.1.1.3 生命周期与下架。
 *
 * <p>蓝图：「管理 listing 状态：在售 / 草稿 / 平台审核中 / 平台驳回 / 主动下架 / 平台下架 /
 * 被封禁。支持平台侧状态变更被动同步，以及主动下架时选择是否回写平台。输出：状态变更记录
 * （来源：平台 or 人工）+ 可选的平台操作任务。关系：上游 = 02 同步、4.3.2.2；下游 = 02 回写队列（5.1.1）。」
 *
 * <p>端点按蓝图模块号标注，端点清单里**每一组**都对应职责句里的一个词：
 *
 * <ul>
 *   <li>{@code GET /capabilities} —— 逐维度能力声明 + 状态机规则 + 差异判定口径。
 *       五段未交付（平台任务派发 / 重新上架的平台回写 / 轮询调度器 / 批量动作 / 申诉工单）
 *       都 available=false + ownerModule，前端据此显示「这归谁管」，而不是显示一个必然失败的按钮。
 *   <li>{@code GET /filter-options} —— 筛选面板词表（**不硬编码在前端**）。
 *   <li>{@code GET /listings} —— 生命周期列表（真分页 + 七态筛选 + 来源筛选 + 店铺 / 平台 / 关键词）。
 *   <li>{@code GET /diff} —— 🔴「平台下架与内部下架是两个事实」的**差异区**。
 *   <li>{@code GET /listings/{uuid}} —— 单个 listing 的生命周期视图（当前状态 + 时间线 + 动作留痕）。
 *   <li>{@code GET /listings/{uuid}/timeline} —— 🔴「输出：状态变更记录（来源：平台 or 人工）」
 *       （真分页 + 按来源筛；每条事件都带 source，页面**必须**显式标注来源）。
 *   <li>{@code POST /listings/{uuid}/actions} —— 🔴「用户下架/上架操作」：
 *       主动下架 / 重新上架 / 提交平台审核 / 标记被封禁；主动下架可选**是否回写平台**。
 *   <li>{@code POST /listings/{uuid}/platform-status} —— 🔴「平台侧状态变更被动同步」的写入入口。
 *   <li>{@code POST /sync} —— 批量同步：把还没联动过的平台事件应用到内部状态。
 * </ul>
 *
 * <p>🔴 路径前缀刻意是 {@code /channel/listing-lifecycles}：4.1.1.1 把
 * {@code listings/{uuid}/lifecycle} 那一段路径登记成「不放行」，在同一棵路径树上开子资源
 * 要同时改别人已交付的白名单与拒绝清单；而「生命周期状态机」与「列表」「详情」
 * 本来就是**三条独立通道**（一个读列表、一个读全景、一个改状态），状态机与回写互不干涉。
 *
 * <p>权限码<b>复用</b> identity 已有的 {@code channel.operation.*}（与 4.1.1.1 列表、
 * 4.2.1.2 批量编辑、4.3.1.2 批量发布、4.3.2.1 内容回写、4.3.2.2 平台状态回流、
 * 2.5.2.1 常用回写操作同族），identity 服务零改动。
 */
@RestController
@RequestMapping("/channel/listing-lifecycles")
public class ListingLifecycleController {

  private final AccessService access;
  private final ListingLifecycleService service;

  public ListingLifecycleController(AccessService access, ListingLifecycleService service) {
    this.access = access;
    this.service = service;
  }

  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, permission);
    return access.run(actor, permission, () -> work.apply(actor));
  }

  // ---- 能力声明 ----

  /**
   * 能力声明：逐维度 + 状态机规则 + 差异判定口径。
   *
   * <p>五段未交付能力（平台任务派发 / 重新上架的平台回写 / 轮询调度器 / 批量动作 / 申诉工单）
   * {@code available=false} + ownerModule，前端据此显示「这归谁管」，
   * <b>不把未交付的步骤画成已完成</b>，也<b>不显示一个必然 404 的按钮</b>。
   */
  @GetMapping("/capabilities")
  public ListingLifecycleCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.capabilities());
  }

  /** 筛选面板词表（七态 / 来源 / 动作 / 平台事件类型 / 差异类型）。 */
  @GetMapping("/filter-options")
  public ListingLifecycleFilterOptions filterOptions(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.filterOptions());
  }

  // ---- 列表 ----

  /**
   * 生命周期列表（真分页 + 真筛选）。
   *
   * <p>筛选维度：七态（{@code status}）、**最近一次状态变更的来源**（{@code source}）、
   * 店铺（{@code shopCode}）、平台（{@code platformCode}）、关键词（{@code keyword}）、
   * 只看有差异的（{@code onlyDivergence}）。
   */
  @GetMapping
  public ListingLifecyclePage list(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String source,
      @RequestParam(required = false) String shopCode,
      @RequestParam(required = false) String platformCode,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) Boolean onlyDivergence,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "channel.operation.read",
        a ->
            service.list(
                status, source, shopCode, platformCode, keyword, onlyDivergence, page, size));
  }

  /**
   * 🔴 差异区：平台侧与内部侧说法相反的清单（真分页）。
   *
   * <p>「平台把商品下架了而内部不知情」是蓝图点名的**最常见的漏单原因** ——
   * 它必须是**一眼能看见**的，而不是埋在列表里等人翻。
   */
  @GetMapping("/diff")
  public ListingLifecycleDivergencePage diff(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(auth, "channel.operation.read", a -> service.divergences(page, size));
  }

  // ---- 单条视图 ----

  /**
   * 单个 listing 的生命周期视图：当前状态 + 完整时间线 + 动作留痕 + 下一次合法动作。
   */
  @GetMapping("/{listingId}")
  public ListingLifecycleDetail detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String listingId) {
    return call(auth, "channel.operation.read", a -> service.detail(listingId));
  }

  /**
   * 🔴 状态变更时间线（真分页 + 按来源筛）。
   *
   * <p>倒序（{@code occurred_at desc}）。每条事件都带 {@code source}（MANUAL / PLATFORM）
   * 与 {@code eventType}，页面**必须**显式标注来源 —— 这是本模块的核心价值。
   */
  @GetMapping("/{listingId}/timeline")
  public ListingLifecycleEventPage timeline(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String listingId,
      @RequestParam(required = false) String source,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(auth, "channel.operation.read", a -> service.timeline(listingId, source, page, size));
  }

  // ---- 人工动作 ----

  /**
   * 🔴 人工动作：主动下架 / 重新上架 / 提交平台审核 / 标记被封禁。
   *
   * <p>🔴 非法迁移 ⇒ <b>422 + 可读原因</b>（不是 500）。理由由状态机给出：
   * 「当前状态是什么、合法后继有哪些」。
   *
   * <p>🔴 主动下架选「回写平台」⇒ 真的产生一个平台操作任务（复用 02 回写队列）；
   * 没选 ⇒ <b>只改内部状态</b>，平台侧不知情 —— 这一点在差异区里显式提示。
   */
  @PostMapping("/{listingId}/actions")
  public LifecycleActionReceipt act(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String listingId,
      @Valid @RequestBody(required = false) LifecycleActionRequest request) {
    LifecycleActionRequest body =
        request == null ? new LifecycleActionRequest(null, null, null) : request;
    return call(
        auth,
        "channel.operation.update",
        a -> service.act(listingId, body, a.userId(), a.membershipId()));
  }

  // ---- 平台状态被动同步 ----

  /**
   * 🔴 平台侧状态变更被动同步（单个商品）。
   *
   * <p>给了 {@code platformEventId} ⇒ 以 4.3.2.2 已落库的那条平台事件为准；
   * 不给 ⇒ 用请求体里的字段作为平台事实。
   *
   * <p>限流 / 类目审核 / 价格异常**不改变**内部状态（商品仍在售，平台只是加了限制），
   * 但平台事实照样留痕 —— 「平台说了什么」本身就是事实。
   */
  @PostMapping("/{listingId}/platform-status")
  public LifecycleActionReceipt platformStatus(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String listingId,
      @Valid @RequestBody(required = false) PlatformStatusRequest request) {
    PlatformStatusRequest body =
        request == null
            ? new PlatformStatusRequest(null, null, null, null, null, null, null)
            : request;
    return call(auth, "channel.operation.create", a -> service.applyPlatformStatus(listingId, body, a.userId()));
  }

  /**
   * 🔴 批量同步：把**还没联动过**的平台事件应用到内部状态。
   *
   * <p>「联动过没有」由动作留痕判定 ⇒ 重复跑不会把同一条平台事件应用两遍。
   * 回执逐条写明真的联动了几条、哪些没联动**以及为什么**。
   */
  @PostMapping("/sync")
  public LifecycleSyncReceipt sync(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.update", a -> service.sync(a.userId()));
  }
}
