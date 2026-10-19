package com.starcon.channel;

import static com.starcon.channel.ListingPerformanceModels.*;

import jakarta.validation.Valid;
import java.util.function.Function;
import org.springframework.web.bind.annotation.*;

/**
 * 4.5.1.1 Listing 表现。
 *
 * <p>蓝图：「单个 listing 的表现明细：曝光、点击、转化率、销量、销售额、Session、退货率、评分变化，支持时间范围对比。输入：{@code listing_id}；平台表现数据同步结果；时间范围。输出：表现指标 + 趋势图；下钻到 13 产品表现。关系：上游 = 02 同步的平台流量数据；下游 = 13 产品表现、14 广告（关联投放）。<b>表现数据来自平台而非内部订单</b>，与内部订单口径不同，展示时需标注来源。」
 *
 * <p>端点按蓝图模块号标注，端点清单里**每一组**都对应职责句里的一个词：
 *
 * <ul>
 *   <li>{@code GET /capabilities} —— 逐维度能力声明 + 指标口径 + 数据来源标注。
 *       四段未交付（13 产品表现 / 14 广告关联投放 / 02 真实流量抓取调度器 /
 *       09 内部订单口径对账）都 available=false + ownerModule，
 *       前端据此显示「这归谁管」，而不是显示一个必然失败的按钮。
 *   <li>{@code POST /} —— 🔴「输入：平台表现数据同步结果」：接收一次平台表现数据同步，
 *       落一条同步结果 + N 条按平台统计日的表现快照。批次级幂等。
 *   <li>{@code GET /} —— 🔴「输出：表现指标」：表现快照列表（真分页 + 商品/店铺/平台/时间范围筛选）。
 *   <li>{@code GET /{listingId}} —— 🔴「单个 listing 的表现明细……支持时间范围对比」：
 *       本期指标 + 上期指标 + 逐指标变化率。上期不给时默认取紧邻的等长区间。
 *   <li>{@code GET /{listingId}/trend} —— 🔴「输出：表现指标 + 趋势图」：
 *       按日 / 周 / 月归集的趋势序列。**没有数据时是空数组，不是假曲线。**
 * </ul>
 *
 * <p>🔴 路径前缀刻意是 {@code /channel/listing-performance}：4.1.1.1 把
 * {@code listings/{uuid}/...} 那一段路径登记成「不放行」，在同一棵路径树上开子资源要同时改别人
 * 已交付的白名单与拒绝清单；而「表现」与「状态回流」本来就是**两条独立通道**
 * （一个把状态收回来、一个把表现收回来），口径、限流与重试互不干涉。
 *
 * <p>权限码<b>复用</b> identity 已有的 {@code channel.operation.*}（与 4.1.1.1 列表、
 * 4.3.2.2 平台状态回流、4.4.2.x 认领与搬迁同族），identity 服务零改动。
 */
@RestController
@RequestMapping("/channel/listing-performance")
public class ListingPerformanceController {

  private final AccessService access;
  private final ListingPerformanceService service;

  public ListingPerformanceController(AccessService access, ListingPerformanceService service) {
    this.access = access;
    this.service = service;
  }

  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, permission);
    return access.run(actor, permission, () -> work.apply(actor));
  }

  // ---- 能力声明 ----

  /**
   * 能力声明：13 产品表现 / 14 广告关联投放 / 02 真实流量抓取调度器 /
   * 09 内部订单口径对账 四个维度 {@code available=false} + ownerModule。
   *
   * <p>响应里同时给出<b>指标口径</b>与<b>数据来源标注</b> —— 前端据此显示
   * 「平台口径」，<b>不把未交付的步骤画成已完成</b>，也<b>不显示一个必然 404 的按钮</b>。
   */
  @GetMapping("/capabilities")
  public ListingPerformanceCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.capabilities());
  }

  // ---- 🔴 回流入库 ----

  /**
   * 🔴 接收一次平台表现数据同步结果。
   *
   * <p>本端点是**平台表现数据进来的唯一入口**：02 平台适配器把平台的表现报表推过来、
   * 或轮询器补捞到报表，都走它。真实抓取调度器归 02（能力表里 available=false）——
   * 本模块不另建一条抓取通道，两个入口会让同一件事有两个投递路径。
   *
   * <p>响应里带回那次的回执（真的落了几条、跳过几条、是不是重复投递），
   * 让调用方立刻看到"这批数据到底进没进去"。
   */
  @PostMapping
  public ListingPerformanceIngestResult ingest(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody IngestPerformanceRequest request) {
    return call(auth, "channel.operation.create", a -> service.ingest(request, a.userId()));
  }

  // ---- 表现指标 ----

  /**
   * 表现快照列表（真分页 + 筛选）。
   *
   * <p>🔴 筛选口径：{@code listingId}、{@code shopCode}、{@code platformCode}、
   * {@code from} / {@code to}（平台统计日范围，{@code yyyy-MM-dd}）。
   */
  @GetMapping
  public ListingPerformanceSnapshotPage snapshots(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String listingId,
      @RequestParam(required = false) String shopCode,
      @RequestParam(required = false) String platformCode,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "channel.operation.read",
        a -> service.snapshots(listingId, shopCode, platformCode, from, to, page, size));
  }

  /**
   * 🔴 单个 listing 的表现明细 + 时间范围对比。
   *
   * <p>🔴 {@code from} / {@code to} 是本期（平台统计日范围）；{@code compareFrom} /
   * {@code compareTo} 是上期，<b>不给时默认取紧邻的等长区间</b>。
   */
  @GetMapping("/{listingId}")
  public ListingPerformanceDetail detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String listingId,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @RequestParam(required = false) String compareFrom,
      @RequestParam(required = false) String compareTo) {
    return call(
        auth, "channel.operation.read", a -> service.detail(listingId, from, to, compareFrom, compareTo));
  }

  /**
   * 🔴 趋势序列（按日 / 周 / 月归集）。
   *
   * <p>🔴 {@code granularity} 只认 DAY / WEEK / MONTH（不给 = DAY）。
   * 没有数据时 {@code points} 是<b>空数组</b> —— 假曲线会让人以为"这段时间表现平稳"，
   * 而真相是"平台没报数据"。
   */
  @GetMapping("/{listingId}/trend")
  public ListingPerformanceTrend trend(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String listingId,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @RequestParam(required = false) String granularity) {
    return call(
        auth, "channel.operation.read", a -> service.trend(listingId, from, to, granularity));
  }
}
