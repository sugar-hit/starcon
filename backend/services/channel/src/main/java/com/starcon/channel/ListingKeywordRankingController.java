package com.starcon.channel;

import static com.starcon.channel.ListingKeywordRankingModels.*;

import jakarta.validation.Valid;
import java.util.function.Function;
import org.springframework.web.bind.annotation.*;

/**
 * 4.5.1.2 关键词排名。
 *
 * <p>蓝图：「监控商品在核心关键词下的自然排名与广告排名，记录历史变化，低于阈值时预警。输入：关键词集合（按商品维护）；排名抓取任务；阈值设置。输出：排名历史表 + 变化预警 + 关键词维度报表。关系：上游 = 抓取任务（02 通道）；下游 = 1 工作台预警、14 广告。<b>排名数据依赖抓取频率与平台限制，必须明确标注抓取时间与可能的不准确窗口</b>。」
 *
 * <p>端点按蓝图模块号标注，端点清单里**每一组**都对应职责句里的一个词：
 *
 * <ul>
 *   <li>{@code GET /capabilities} —— 逐维度能力声明 + 排名字段口径 + 抓取时间与不准确窗口说明。
 *       三段未交付（1 工作台预警推送 / 14 广告关联投放 / 02 真实抓取调度器）都
 *       available=false + ownerModule，前端据此显示「这归谁管」，而不是显示一个必然失败的按钮。
 *   <li>{@code GET /filter-options} —— 筛选面板选项（平台 / 店铺 / 站点，都取本租户真实有的）。
 *   <li>{@code GET /} + {@code POST /} + {@code PUT /{keywordId}} + {@code DELETE /{keywordId}} ——
 *       🔴「输入：关键词集合（按商品维护）」：租户内的增删改查。删除是**软删除** ——
 *       排名历史与预警记录必须留着。
 *   <li>{@code POST /crawls} —— 🔴「输入：排名抓取任务」：接收一次抓取结果，
 *       落一条抓取批次 + N 条排名历史 + 可能产生的预警记录。批次级幂等。
 *   <li>{@code GET /{keywordId}} —— 🔴 单个关键词的排名明细（最新名次 + 阈值 + 抓取时间 + 窗口）。
 *   <li>{@code GET /{keywordId}/history} —— 🔴「输出：排名历史表」：真分页。
 *   <li>{@code GET /{keywordId}/trend} —— 趋势序列（日/周/月）。**没有数据时是空数组，不是假曲线。**
 *   <li>{@code GET /alerts} + {@code PUT /alerts/{alertId}/acknowledge} —— 🔴「输出：变化预警」：
 *       预警是**记录**，本模块**不发通知**（下游 1 工作台预警未交付）。
 *   <li>{@code GET /report} —— 🔴「输出：关键词维度报表」：真 SQL 聚合。
 * </ul>
 *
 * <p>🔴 路径前缀刻意是 {@code /channel/listing-keyword-rankings}：4.1.1.1 把
 * {@code listings/{uuid}/...} 那一段路径登记成「不放行」，在同一棵路径树上开子资源要同时改别人
 * 已交付的白名单与拒绝清单；而且「关键词排名」与「平台表现」「平台状态回流」本来就是
 * **三条独立通道**（一个把排名收回来、一个把表现收回来、一个把状态收回来），
 * 口径、限流与重试互不干涉。
 *
 * <p>权限码<b>复用</b> identity 已有的 {@code channel.operation.*}（与 4.1.1.1 列表、
 * 4.3.2.2 平台状态回流、4.4.2.x 认领与搬迁、4.5.1.1 表现同族），identity 服务零改动。
 */
@RestController
@RequestMapping("/channel/listing-keyword-rankings")
public class ListingKeywordRankingController {

  private final AccessService access;
  private final ListingKeywordRankingService service;

  public ListingKeywordRankingController(AccessService access, ListingKeywordRankingService service) {
    this.access = access;
    this.service = service;
  }

  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, permission);
    return access.run(actor, permission, () -> work.apply(actor));
  }

  // ---- 能力声明 ----

  /**
   * 能力声明：1 工作台预警推送 / 14 广告关联投放 / 02 真实抓取调度器 三个维度
   * {@code available=false} + ownerModule。
   *
   * <p>响应里同时给出<b>排名字段口径</b>与<b>抓取时间 / 不准确窗口说明</b> ——
   * 前端据此显示「这个名次是什么时候的、可能有多不准」，
   * <b>不把未交付的步骤画成已完成</b>，也<b>不显示一个必然 404 的按钮</b>。
   */
  @GetMapping("/capabilities")
  public ListingKeywordRankingCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.capabilities());
  }

  /** 筛选面板选项：平台 / 店铺 / 站点。 */
  @GetMapping("/filter-options")
  public ListingKeywordRankingFilterOptions filterOptions(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.filterOptions());
  }

  // ---- 🔴 关键词集合（增删改查） ----

  /**
   * 关键词列表（真分页 + 筛选）。
   *
   * <p>🔴 筛选口径：{@code listingId}、{@code shopCode}、{@code platformCode}、
   * {@code siteCode}、{@code keyword}（模糊搜索）、{@code status}（ACTIVE / INACTIVE / ALL）。
   */
  @GetMapping
  public ListingKeywordRankingKeywordPage keywords(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String listingId,
      @RequestParam(required = false) String shopCode,
      @RequestParam(required = false) String platformCode,
      @RequestParam(required = false) String siteCode,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String status,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "channel.operation.read",
        a -> service.keywords(listingId, shopCode, platformCode, siteCode, keyword, status, page, size));
  }

  /** 新建一个关键词（挂到商品上，带阈值设置）。 */
  @PostMapping
  public ListingKeywordRankingKeywordRow createKeyword(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody ListingKeywordRankingKeywordInput input) {
    return call(auth, "channel.operation.create", a -> service.saveKeyword(null, input));
  }

  /** 改一个关键词（关键词原文与三个阈值）。 */
  @PutMapping("/{keywordId}")
  public ListingKeywordRankingKeywordRow updateKeyword(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String keywordId,
      @Valid @RequestBody ListingKeywordRankingKeywordInput input) {
    return call(
        auth, "channel.operation.update", a -> service.saveKeyword(parseId(keywordId), input));
  }

  /**
   * 🔴 停用一个关键词（**软删除**）。
   *
   * <p>排名历史与预警记录**必须留着** —— 关键词停了不等于当初抓到的名次不算数。
   */
  @DeleteMapping("/{keywordId}")
  public ListingKeywordRankingKeywordRow disableKeyword(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String keywordId) {
    return call(auth, "channel.operation.delete", a -> service.disableKeyword(keywordId));
  }

  // ---- 🔴 排名抓取任务（蓝图输入项） ----

  /**
   * 🔴 接收一次排名抓取任务。
   *
   * <p>本端点是**排名数据进来的唯一入口**：抓取方把平台上的关键词名次推过来，
   * 都走它。真实的抓取调度器归 02（能力表里 available=false）——
   * 本模块不另建一条抓取通道，两个入口会让同一件事有两个投递路径。
   *
   * <p>🔴 {@code crawledAt}（抓取时间）与 {@code accuracyWindowMinutes}（可能的不准确窗口）
   * **必填** —— 蓝图加粗句「必须明确标注抓取时间与可能的不准确窗口」在这里落地。
   */
  @PostMapping("/crawls")
  public ListingKeywordRankingCrawlResult crawl(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody ListingKeywordRankingCrawlRequest request) {
    return call(auth, "channel.operation.create", a -> service.crawl(request));
  }

  // ---- 🔴 排名明细与历史 ----

  /**
   * 🔴 单个关键词的排名明细。
   *
   * <p>🔴 响应带 {@code latestCrawledAt}（抓取时间）与
   * {@code latestAccuracyWindowMinutes}（可能的不准确窗口）——
   * 没有它们，界面上那个名次就看不出是什么时候的。
   */
  @GetMapping("/{keywordId}")
  public ListingKeywordRankingDetail detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String keywordId) {
    return call(auth, "channel.operation.read", a -> service.detail(keywordId));
  }

  /**
   * 🔴 排名历史（真分页 + 时间范围筛选）。
   *
   * <p>🔴 {@code from} / {@code to} 是 {@code yyyy-MM-dd} 的**UTC 日**区间。
   */
  @GetMapping("/{keywordId}/history")
  public ListingKeywordRankingHistoryPage history(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String keywordId,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(auth, "channel.operation.read", a -> service.history(keywordId, from, to, page, size));
  }

  /**
   * 趋势序列（按日 / 周 / 月归集，UTC）。
   *
   * <p>🔴 {@code granularity} 只认 DAY / WEEK / MONTH（不给 = DAY）。
   * 没有数据时 {@code points} 是<b>空数组</b> —— 假曲线会让人以为"这段时间排名没变"，
   * 而真相是"没抓到数据"。
   */
  @GetMapping("/{keywordId}/trend")
  public ListingKeywordRankingTrend trend(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String keywordId,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @RequestParam(required = false) String granularity) {
    return call(
        auth, "channel.operation.read", a -> service.trend(keywordId, from, to, granularity));
  }

  // ---- 🔴 变化预警 ----

  /**
   * 变化预警列表（真分页 + 筛选）。
   *
   * <p>🔴 预警是**记录**，本模块**不发通知**（下游 1 工作台预警未交付）。
   */
  @GetMapping("/alerts")
  public ListingKeywordRankingAlertPage alerts(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String keywordId,
      @RequestParam(required = false) String listingId,
      @RequestParam(required = false) String alertType,
      @RequestParam(required = false) String acknowledged,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "channel.operation.read",
        a -> service.alerts(keywordId, listingId, alertType, acknowledged, page, size));
  }

  /**
   * 🔴 人工确认一条预警。
   *
   * <p>预警**只是记录**（不发通知），但「有没有人看过」这个事实仍然要留痕。
   */
  @PutMapping("/alerts/{alertId}/acknowledge")
  public ListingKeywordRankingAlertRow acknowledge(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String alertId) {
    return call(auth, "channel.operation.update", a -> service.acknowledge(alertId, a.userId()));
  }

  // ---- 🔴 关键词维度报表 ----

  /**
   * 🔴 关键词维度报表（**真 SQL 聚合**）。
   *
   * <p>🔴 按（平台, 站点, 关键词）聚合 —— 站点码只在平台内唯一，平台才是父级。
   * 🔴 平均名次在没有样本时是 null（不是 0）。
   */
  @GetMapping("/report")
  public ListingKeywordRankingReport report(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String platformCode,
      @RequestParam(required = false) String siteCode,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to) {
    return call(
        auth, "channel.operation.read", a -> service.report(platformCode, siteCode, keyword, from, to));
  }

  private static java.util.UUID parseId(String raw) {
    try {
      return java.util.UUID.fromString(raw == null ? "" : raw.trim());
    } catch (RuntimeException e) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.BAD_REQUEST, "id 格式不正确");
    }
  }
}
