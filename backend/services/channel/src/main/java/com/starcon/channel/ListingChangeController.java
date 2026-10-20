package com.starcon.channel;

import static com.starcon.channel.ListingChangeModels.*;

import jakarta.validation.Valid;
import java.util.function.Function;
import org.springframework.web.bind.annotation.*;

/**
 * 4.5.2.2 上新与变更监控。
 *
 * <p>蓝图：「监控目标 ASIN/竞品的上下架、价格、排名、评论数变化，识别市场机会（如竞品断货涨价）。输入：监控对象列表；抓取频率。输出：变更事件流 + 历史对比。关系：上游 = 抓取任务；下游 = 13 分析、1 工作台。<b>监控对象与自有商品分离</b>，竞品数据不进自有商品模型，只做参考指标。」
 *
 * <p>端点按蓝图模块号标注，端点清单里**每一组**都对应职责句里的一个词：
 *
 * <ul>
 *   <li>{@code GET /capabilities} —— 逐维度能力声明 + 判定规则口径 + 参考数据标注。
 *       三段未交付（02 抓取调度器 / 13 分析 / 1 工作台通知）都 available=false + ownerModule + impact。
 *   <li>{@code GET /filter-options} —— 筛选项词表（平台 / 站点 / 枚举取值域）。
 *   <li>{@code GET|POST|PUT|DELETE /watches} —— 🔴「输入：监控对象列表；抓取频率」：
 *       监控对象是真分页列表，可新建 / 改抓取频率与阈值 / 启停 / 删除。
 *   <li>{@code POST /crawls} —— 🔴「上游 = 抓取任务」：接收一次抓取任务的结果，
 *       落观测快照 → 识别四类变化 → 落变更事件 → 判定市场机会。批次级幂等。
 *   <li>{@code GET /crawls} —— 抓取批次列表（真分页）。
 *   <li>{@code GET /events} —— 🔴「输出：变更事件流」：四类变化的真分页 + 真筛选。
 *   <li>{@code GET /opportunities} + {@code PUT /opportunities/{id}/acknowledge} ——
 *       🔴「识别市场机会（如竞品断货涨价）」：市场机会**记录** + 标记已阅。
 *   <li>{@code GET /watches/{watchId}/history} —— 观测快照时间线（历史对比的事实源）。
 *   <li>{@code GET /watches/{watchId}/comparison} —— 🔴「输出：历史对比」：
 *       价格 / 排名 / 评论数 / 上下架四维对比（服务端 SQL 聚合 + 现算比率）。
 * </ul>
 *
 * <p>🔴 **本类没有、也不该有抢占 / 调价 / 铺货端点。** 蓝图加粗句「竞品数据不进自有商品模型，
 * <b>只做参考指标</b>」是**契约级**的硬要求：放行一个「一键抢占竞品价格」的形状，
 * 会让界面以为系统能替运营完成竞品应对 —— 而它做不到，点了只会得到一个说不清的失败。
 *
 * <p>🔴 路径前缀刻意是 {@code /channel/listing-changes}（**独立前缀**）：4.1.1.1 把
 * {@code listings/{uuid}/...} 那一段路径登记成「不放行」，在同一棵路径树上开子资源要同时改别人
 * 已交付的白名单与拒绝清单；而「竞品变更监控」是**对外部目标的观测**，
 * 口径、限流与权限都与「listing 本身」不同。
 *
 * <p>权限码<b>复用</b> identity 已有的 {@code channel.operation.*}（与 4.1.1.1 列表、
 * 4.3.2.2 平台状态回流、4.4.2.x 认领与搬迁、4.5.x 监控系列同族），identity 服务零改动。
 */
@RestController
@RequestMapping("/channel/listing-changes")
public class ListingChangeController {

  private final AccessService access;
  private final ListingChangeService service;

  public ListingChangeController(AccessService access, ListingChangeService service) {
    this.access = access;
    this.service = service;
  }

  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, permission);
    return access.run(actor, permission, () -> work.apply(actor));
  }

  // ---- 能力声明 ----

  /**
   * 能力声明：02 抓取调度器 / 13 分析 / 1 工作台通知 三个维度
   * {@code available=false} + ownerModule + impact。
   *
   * <p>响应里同时给出<b>判定规则口径</b>与<b>参考数据标注</b> —— 前端据此显示
   * 「竞品数据仅作参考指标」，<b>不把未交付的步骤画成已完成</b>，
   * 也<b>不显示一个必然 404 的按钮</b>。
   */
  @GetMapping("/capabilities")
  public ListingChangeCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.capabilities());
  }

  /** 筛选项词表（平台 / 站点 / 枚举取值域）。 */
  @GetMapping("/filter-options")
  public ListingChangeFilterOptions filterOptions(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.filterOptions());
  }

  // ---- 🔴 监控对象 ----

  /**
   * 监控对象列表（真分页 + 筛选）。
   *
   * <p>🔴 筛选口径：{@code status}、{@code platformCode}、{@code targetKind}
   * （COMPETITOR / OWN_ASIN）、{@code keyword}（ASIN / 标题 / 品牌模糊匹配）。
   */
  @GetMapping("/watches")
  public ListingChangeWatchPage watches(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String platformCode,
      @RequestParam(required = false) String targetKind,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "channel.operation.read",
        a -> service.watches(status, platformCode, targetKind, keyword, page, size));
  }

  /**
   * 新建监控对象。
   *
   * <p>🔴 目标身份只有「平台编码 + 平台商品 ID（ASIN）」—— <b>没有 listingId</b>：
   * 蓝图加粗句「监控对象与自有商品分离」在入参这一层就已经生效。
   * 同一个平台的同一个 ASIN 已有监控对象 ⇒ 409（不允许配两套抓取频率）。
   */
  @PostMapping("/watches")
  public ListingChangeWatchRow createWatch(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody SaveListingChangeWatchRequest request) {
    return call(auth, "channel.operation.create", a -> service.createWatch(request));
  }

  /**
   * 更新监控对象（<b>PUT = 全量替换</b>：字段不给 = 清空 / 复位）。
   *
   * <p>🔴 目标身份（平台 + ASIN）<b>不可改</b>（改了会让历史事件挂到别的竞品上）——
   * 给了不同的 externalItemId 直接 422。
   */
  @PutMapping("/watches/{watchId}")
  public ListingChangeWatchRow updateWatch(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String watchId,
      @Valid @RequestBody SaveListingChangeWatchRequest request) {
    return call(auth, "channel.operation.update", a -> service.updateWatch(watchId, request));
  }

  /**
   * 删除监控对象（连同它的抓取批次、观测快照、变更事件与市场机会）。
   *
   * <p>🔴 回执写明一并删掉了多少条 —— 删掉的是事实，调用方必须知道。
   */
  @DeleteMapping("/watches/{watchId}")
  public ListingChangeWatchDeleteResult deleteWatch(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String watchId) {
    return call(auth, "channel.operation.delete", a -> service.deleteWatch(watchId));
  }

  // ---- 🔴 回流入库 ----

  /**
   * 🔴 接收一次抓取任务的结果。
   *
   * <p>本端点是**竞品观测进来的唯一入口**：02 平台适配器把抓到的竞品状态推过来、
   * 或轮询器补捞到，都走它。真实抓取调度器归 02（能力表里 available=false）——
   * 本模块不另建一条抓取通道，两个入口会让同一件事有两个投递路径。
   *
   * <p>响应里带回那次的回执（真的落了几条、跳过几条、是不是重复投递、产生了哪些变化与机会），
   * 让调用方立刻看到"这批数据到底进没进去"。
   */
  @PostMapping("/crawls")
  public ListingChangeCrawlResult ingest(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody IngestListingChangeCrawlRequest request) {
    return call(auth, "channel.operation.create", a -> service.ingest(request));
  }

  /** 抓取批次列表（真分页 + 筛选）。 */
  @GetMapping("/crawls")
  public ListingChangeCrawlPage crawls(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String watchId,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(auth, "channel.operation.read", a -> service.crawls(watchId, page, size));
  }

  // ---- 🔴 变更事件流 ----

  /**
   * 变更事件流（真分页 + 真筛选）。
   *
   * <p>🔴 筛选口径：{@code watchId}、{@code changeType}
   * （LISTING_STATUS / PRICE / RANK / REVIEW_COUNT）、{@code direction}
   * （INCREASED / DECREASED / CHANGED）、{@code from} / {@code to}
   * （判定时刻范围，{@code yyyy-MM-dd}，按 UTC 自然日）。
   */
  @GetMapping("/events")
  public ListingChangeEventPage events(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String watchId,
      @RequestParam(required = false) String changeType,
      @RequestParam(required = false) String direction,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "channel.operation.read",
        a -> service.events(watchId, changeType, direction, from, to, page, size));
  }

  // ---- 🔴 市场机会 ----

  /**
   * 市场机会列表（真分页 + 筛选）。
   *
   * <p>🔴 筛选口径：{@code watchId}、{@code opportunityType}、{@code severity}、{@code status}、
   * {@code from} / {@code to}（判定时刻范围，{@code yyyy-MM-dd}，按 UTC 自然日）。
   */
  @GetMapping("/opportunities")
  public ListingChangeOpportunityPage opportunities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String watchId,
      @RequestParam(required = false) String opportunityType,
      @RequestParam(required = false) String severity,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "channel.operation.read",
        a ->
            service.opportunities(
                watchId, opportunityType, severity, status, from, to, page, size));
  }

  /**
   * 标记市场机会为「已阅」。
   *
   * <p>🔴 这是**内部记录状态**，**不是任何外部动作** —— 本模块不提供抢占 / 调价 / 铺货入口，
   * 机会需要人工判断后再在平台侧或自有定价流程里执行。已经阅过的再阅一次是幂等的。
   */
  @PutMapping("/opportunities/{opportunityId}/acknowledge")
  public ListingChangeOpportunityRow acknowledgeOpportunity(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String opportunityId,
      @Valid @RequestBody(required = false) AcknowledgeListingChangeOpportunityRequest request) {
    return call(
        auth,
        "channel.operation.update",
        a -> service.acknowledgeOpportunity(opportunityId, request));
  }

  // ---- 🔴 历史对比 ----

  /**
   * 某个监控对象的观测历史（真分页 + 时间范围）。
   *
   * <p>🔴 这是历史对比的**事实源**（观测快照）；聚合口径在 {@code /comparison}。
   */
  @GetMapping("/watches/{watchId}/history")
  public ListingChangeSnapshotPage history(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String watchId,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(auth, "channel.operation.read", a -> service.history(watchId, from, to, page, size));
  }

  /**
   * 🔴 历史对比：价格 / 排名 / 评论数 / 上下架四维对比（服务端 SQL 聚合 + 现算比率）。
   *
   * <p>🔴 {@code from} / {@code to} 是观测时刻范围（{@code yyyy-MM-dd}，按 UTC 自然日；
   * 不给时默认近 30 天）。
   */
  @GetMapping("/watches/{watchId}/comparison")
  public ListingChangeComparison comparison(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String watchId,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to) {
    return call(auth, "channel.operation.read", a -> service.comparison(watchId, from, to));
  }
}
