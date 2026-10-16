package com.starcon.channel;

import static com.starcon.channel.RateComparisonModels.*;

import jakarta.validation.Valid;
import java.util.function.Function;
import org.springframework.web.bind.annotation.*;

/**
 * 10.1.2.2 渠道比价。
 *
 * <p>蓝图：「在满足时效与限制的渠道中按成本/时效/成功率多目标排序，支持按店铺预设优先级自动选择。
 * 输入：比价条件；渠道评分（历史成功率、时效达成率、投诉率）。输出：排序后的推荐渠道 + 评分明细。」
 *
 * <p>端点按蓝图模块号标注，方便对照 10-logistics-and-freight.md。
 *
 * <p>🔴 两个比价入口刻意分开（照抄 10.1.2.1 的 {@code /estimate} 与 {@code /quotes}）：
 *
 * <ul>
 *   <li>{@code POST /compare} —— <b>无副作用</b>试算。人工决策看一眼排序不该在库里
 *       留下一堆单据。
 *   <li>{@code POST /comparisons} —— 比价并落库。给下游 9.3.2.1 分仓与 18 自动化规则引用。
 * </ul>
 *
 * <p>🔴 两者返回的 {@code costBasis} 恒为 {@code RULE_ESTIMATE}：比价的成本来自上游
 * 10.1.2.1 的<b>试算</b>，不是承运商账单。试算与实际的差异要进费用差异项 ——
 * 把这个数字当实收成本入账，差异会静默吞掉利润。
 *
 * <p>权限码<b>复用</b> identity 已有的 {@code channel.operation.*}（与 10.1.1.2 渠道配置、
 * 10.1.2.1 运费计算器同族），identity 服务零改动。
 */
@RestController
@RequestMapping("/channel/rate-comparison")
public class RateComparisonController {

  private final AccessService access;
  private final RateComparisonService service;

  public RateComparisonController(AccessService access, RateComparisonService service) {
    this.access = access;
    this.service = service;
  }

  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, permission);
    return access.run(actor, permission, () -> work.apply(actor));
  }

  // ---- 能力声明 ----

  /**
   * 能力声明：未交付的段显式 available=false + ownerModule + 影响。
   *
   * <p>其中 {@code rating_producer}（渠道评分的自动生产者，10.6.2.1 时效分析）刻意报
   * available=false —— 当前只能人工登记评分，而不是让它在界面上看起来像自动的。
   */
  @GetMapping("/capabilities")
  public RateComparisonCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.capabilities());
  }

  // ---- 比价 ----

  /** 比价（无副作用）：输出排序后的推荐渠道 + 评分明细。 */
  @PostMapping("/compare")
  public CompareResult compare(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody CompareRequest request) {
    return call(auth, "channel.operation.read", a -> service.compare(request));
  }

  /** 比价并落库：同一份排序与评分明细，额外给出一张可被下游引用的比价单。 */
  @PostMapping("/comparisons")
  public CompareResult saveComparison(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody SaveComparisonRequest request) {
    return call(
        auth,
        "channel.operation.create",
        a -> service.saveComparison(request.request()));
  }

  // ---- 比价单 ----

  /** 比价单列表：真分页 + 真筛选，汇总走独立查询不受 limit/offset 影响。 */
  @GetMapping("/comparisons")
  public ComparisonPage comparisons(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String destCountry,
      @RequestParam(required = false) String shopCode,
      @RequestParam(required = false) String selectionBasis,
      @RequestParam(required = false) String comparedFrom,
      @RequestParam(required = false) String comparedTo,
      @RequestParam(required = false, defaultValue = "TIME_DESC") String sort,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "channel.operation.read",
        a ->
            service.comparisons(
                destCountry, shopCode, selectionBasis, comparedFrom, comparedTo, sort, page, size));
  }

  /** 比价单详情：含逐渠道评分明细与算式。跨租户与不存在返回逐字相同的 404。 */
  @GetMapping("/comparisons/{id}")
  public ComparisonDetail comparison(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "channel.operation.read", a -> service.comparison(id));
  }

  // ---- 渠道评分（比价条件之一）----

  /** 渠道评分一览 + 没有任何评分的渠道清单（那部分在比价时会被排除）。 */
  @GetMapping("/ratings")
  public RatingOverview ratings(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String channelId,
      @RequestParam(required = false) String countryCode,
      @RequestParam(required = false) String status,
      @RequestParam(required = false, defaultValue = "CHANNEL") String sort) {
    return call(
        auth, "channel.operation.read", a -> service.ratings(channelId, countryCode, status, sort));
  }

  /**
   * 登记 / 改一条渠道评分。
   *
   * <p>🔔 生效中的评分不允许就地改计数或统计窗口（DB 层拒）：已发出的比价单里存着
   * 当时那组比率，就地改会让那些单据再也复算不出来。要改请把旧行置为 {@code SUPERSEDED}
   * 再登记一条新的。
   */
  @PostMapping("/ratings")
  public ChannelRatingRow saveRating(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody SaveRatingRequest request) {
    return call(auth, "channel.operation.create", a -> service.saveRating(request));
  }

  // ---- 店铺预设优先级 ----

  /** 店铺预设优先级一览：按序号升序，生效中的序号必须连续覆盖 1..N。 */
  @GetMapping("/shop-priorities")
  public ShopPriorityOverview shopPriorities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String shopCode,
      @RequestParam(required = false) String status) {
    return call(auth, "channel.operation.read", a -> service.shopPriorities(shopCode, status));
  }

  /** 登记 / 改一条店铺预设优先级（自动选择的顺序预设）。 */
  @PostMapping("/shop-priorities")
  public ShopPriorityRow saveShopPriority(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody SaveShopPriorityRequest request) {
    return call(auth, "channel.operation.create", a -> service.saveShopPriority(request));
  }
}
