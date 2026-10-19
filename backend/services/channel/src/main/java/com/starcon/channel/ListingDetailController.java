package com.starcon.channel;

import static com.starcon.channel.ListingDetailModels.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.function.Function;
import org.springframework.web.bind.annotation.*;

/**
 * 4.1.1.2 Listing 详情。
 *
 * <p>蓝图：「单个平台商品的全景：平台原始信息（ASIN/item_id、标题、状态、评分评论数）、内部映射、内容对照（平台当前内容 vs
 * 内部草稿内容）、价格库存差异、发布历史、平台操作记录。输入：{@code listing_id}；聚合平台快照与内部数据；用户权限。输出：左右对照视图 +
 * 编辑入口 + 差异高亮 + 回写按钮。关系：上游 = 4.1.1.1；下游 = 4.2.1.1 编辑、4.3.2.1 回写。<b>差异对照是本模块的核心价值</b>：让运营一眼看出「平台上卖的价格/库存和我系统里设的不一样」。」
 *
 * <p>🔴 <b>路径前缀刻意不是 {@code /channel/listings}</b>，而是 {@code /channel/listing-details}。
 *
 * <p>理由三条：
 *
 * <ol>
 *   <li><b>不侵入已交付的 4.1.1.1 契约</b>：4.1.1.1 交付时把 {@code /channel/listings/{id}/diff} 显式登记为
 *       「不放行」（那是本模块的形状）、把 {@code /channel/listings/{id}} 登记为「列表行点进去的最小载荷」。
 *       在同一条路径树上开子资源会让两条车道同时改同一份白名单与同一份契约注解，合并必冲突。
 *   <li><b>「列表」与「详情全景」是两种资源</b>：列表行是筛选结果的一行，详情是聚合了四份上游事实的全景视图，
 *       两者连字段集合都不一样。
 *   <li><b>白名单是多车道共用的累积文件</b>：本车道新增一条独立的 {@code services[]} 元素（插入锚点固定在
 *       最后一条 {@code channel/} 元素之后、{@code trade/} 之前），既有分支一字未动。
 * </ol>
 *
 * <p>🔴 端点按职责句逐项对应：
 *
 * <ul>
 *   <li>{@code GET /{id}} —— 「输出：左右对照视图 + 差异高亮」，一次给全（全景 + 映射 + 内容对照 + 差异 + 发布历史 + 平台操作记录）。
 *   <li>{@code GET /{id}/diff} —— 「差异对照是本模块的核心价值」单独成一路，便于界面分块高亮与单独刷新。
 *   <li>{@code GET /{id}/content} —— 「内容对照（平台当前内容 vs 内部草稿内容）」。
 *   <li>{@code GET /{id}/publish-history} / {@code GET /{id}/platform-operations} —— 「发布历史」「平台操作记录」，真分页。
 *   <li>{@code POST /{id}/content-snapshots} —— 平台当前内容快照的写入入口（02 同步 / 4.4.1 采集）。
 *   <li>{@code POST /{id}/publish-records} —— 发布动作结果留痕（4.3.1.1 / 4.3.2.1 调用；本模块不发起发布）。
 *   <li>{@code GET /capabilities} —— 四个未接入维度的明账（售价 / 内部可售量 / 内容草稿 / 销量）。
 * </ul>
 *
 * <p>权限码<b>复用</b> identity 已有的 {@code channel.operation.*}（与 4.1.1.1 同族），identity 服务零改动。
 */
@RestController
@RequestMapping("/channel/listing-details")
public class ListingDetailController {

  private final AccessService access;
  private final ListingDetailService service;

  public ListingDetailController(AccessService access, ListingDetailService service) {
    this.access = access;
    this.service = service;
  }

  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, permission);
    return access.run(actor, permission, () -> work.apply(actor));
  }

  // ---- 能力声明 ----

  /**
   * 能力声明：售价 / 内部可售量 / 内容草稿 / 销量四个维度 available=false + ownerModule + impact。
   *
   * <p>前端据此显示「未接入」，<b>不回 0、不显示假草稿</b>。编辑入口与回写按钮同样在这里声明为不可用并写明归属。
   */
  @GetMapping("/capabilities")
  public ListingDetailCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.capabilities());
  }

  // ---- 全景 ----

  /**
   * 单个平台商品的全景。
   *
   * <p>职责句里的七块一次给全：平台原始信息、内部映射、内容对照、价格库存差异、发布历史、平台操作记录，
   * 外加状态变更轨迹与回写登记簿状态（后两块是「全景」要成立所必需的上下文）。
   */
  @GetMapping("/{id}")
  public ListingDetail detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "channel.operation.read", a -> service.detail(id));
  }

  // ---- 差异对照（本模块的核心价值）----

  /** 差异对照：14 行差异表 + 三类计数。详见 {@link ListingDiffReport} 的 status 取值域说明。 */
  @GetMapping("/{id}/diff")
  public ListingDiffReport diff(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "channel.operation.read", a -> service.diff(id));
  }

  // ---- 内容对照 ----

  /** 内容对照：平台当前内容（有值）vs 内部草稿内容（未接入，4.2.1.1）。 */
  @GetMapping("/{id}/content")
  public ListingContentComparison content(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "channel.operation.read", a -> service.content(id));
  }

  // ---- 发布历史 / 平台操作记录 ----

  /** 发布历史（新 → 旧，真分页）。追加写、不可改写 —— 它是「当时发了什么」的凭据。 */
  @GetMapping("/{id}/publish-history")
  public List<ListingPublishRecordView> publishHistory(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(auth, "channel.operation.read", a -> service.publishHistory(id, page, size));
  }

  /**
   * 平台操作记录（新 → 旧，真分页）。
   *
   * <p>🔴 只含<b>能归属到本商品</b>的操作（shop_id + payload 的 targetType / externalRef 命中）。
   * 直接单条派发的操作不带这两个键、无法归属到商品，属店铺级事实，本模块不猜也不硬塞进来 ——
   * capabilities 的 platformOperationNote 里写明了这一点。
   */
  @GetMapping("/{id}/platform-operations")
  public List<ListingPlatformOperationView> platformOperations(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(auth, "channel.operation.read", a -> service.platformOperations(id, page, size));
  }

  // ---- 写入：平台内容快照（02 同步 / 4.4.1 采集入口）----

  /**
   * 登记一份平台当前内容快照。
   *
   * <p>🔴 请求体<b>只有平台侧字段</b>：内部草稿归 4.2.1.1，本模块不预建它的表，也不在快照里留草稿位。
   * 登记为「当前」时，同一 listing 同一语言的上一份由 DB 触发器自动降级。
   */
  @PostMapping("/{id}/content-snapshots")
  public SaveContentSnapshotResult saveSnapshot(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @Valid @RequestBody SaveContentSnapshotRequest request) {
    return call(auth, "channel.operation.create", a -> service.saveSnapshot(id, request));
  }

  // ---- 写入：发布动作结果留痕 ----

  /**
   * 登记一次发布 / 内容回写的结果。
   *
   * <p>🔴 本模块**不发起**发布（那是 4.3.1.1 发布流程与 4.3.2.1 内容回写的活），只留痕「发了什么、平台回了什么、
   * 为什么失败」。{@code FAILED} / {@code REJECTED} 必须写明原因。
   */
  @PostMapping("/{id}/publish-records")
  public SavePublishRecordResult savePublishRecord(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @Valid @RequestBody SavePublishRecordRequest request) {
    return call(auth, "channel.operation.create", a -> service.savePublishRecord(id, request));
  }
}
