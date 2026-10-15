package com.starcon.channel;

import static com.starcon.channel.FreightCalculatorModels.*;

import jakarta.validation.Valid;
import java.util.function.Function;
import org.springframework.web.bind.annotation.*;

/**
 * 10.1.2.1 运费计算器。
 *
 * <p>端点按蓝图模块号标注，方便对照 10-logistics-and-freight.md。
 *
 * <p>🔴 两个入口刻意分开：
 *
 * <ul>
 *   <li>{@code POST /estimate} —— <b>无副作用</b>试算。人工决策看一眼对照表不该在库里
 *       留下一堆单据。
 *   <li>{@code POST /quotes} —— 试算并落库。给下游 9.3.2.1 分仓与 5.2.3.2 费用测算引用。
 * </ul>
 *
 * <p>🔴 两者返回的 {@code costBasis} 恒为 {@code RULE_ESTIMATE}：**试算用规则、结算用实际**。
 * 真实成本要等承运商账单（trade 10.2.1.2 运单 / 12 财务），试算与实际的差异进费用差异项 ——
 * 把这个数字当实际成本入账，差异会静默吞掉利润。
 *
 * <p>权限码<b>复用</b> identity 已有的 {@code channel.operation.*}（与 10.1.1.2 渠道配置、
 * 10.1.1.1 承运商、10.3.2.1 交运批次同族），identity 服务零改动。
 */
@RestController
@RequestMapping("/channel/freight")
public class FreightCalculatorController {

  private final AccessService access;
  private final FreightCalculatorService service;

  public FreightCalculatorController(AccessService access, FreightCalculatorService service) {
    this.access = access;
    this.service = service;
  }

  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, permission);
    return access.run(actor, permission, () -> work.apply(actor));
  }

  // ---- 能力声明 ----

  /** 能力声明：未交付的段显式 available=false + ownerModule + 影响（比价、实际成本回填）。 */
  @GetMapping("/capabilities")
  public FreightCalculatorCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.capabilities());
  }

  // ---- 试算 ----

  /** 试算（无副作用）：输出渠道 × (运费、时效、可承运性) 对照表。 */
  @PostMapping("/estimate")
  public FreightEstimateResult estimate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody FreightEstimateRequest request) {
    return call(auth, "channel.operation.read", a -> service.estimate(request));
  }

  /** 试算并落库：同一份对照表，额外给出一张可被下游引用的试算单。 */
  @PostMapping("/quotes")
  public FreightEstimateResult saveQuote(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody SaveQuoteRequest request) {
    return call(
        auth,
        "channel.operation.create",
        a -> service.saveQuote(request.request()));
  }

  // ---- 试算单 ----

  /** 试算单列表：真分页 + 真筛选，汇总走独立查询不受 limit/offset 影响。 */
  @GetMapping("/quotes")
  public FreightQuotePage quotes(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String destCountry,
      @RequestParam(required = false) String eligibility,
      @RequestParam(required = false) String quotedFrom,
      @RequestParam(required = false) String quotedTo,
      @RequestParam(required = false, defaultValue = "TIME_DESC") String sort,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "channel.operation.read",
        a -> service.quotes(destCountry, eligibility, quotedFrom, quotedTo, sort, page, size));
  }

  /** 试算单详情：含逐渠道明细与计算轨迹。跨租户与不存在返回逐字相同的 404。 */
  @GetMapping("/quotes/{id}")
  public FreightQuoteDetail quote(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "channel.operation.read", a -> service.quote(id));
  }

  // ---- 规则集（计分档位 / 首重续重 / 分国家）----

  /** 费率卡与分国家规则一览：规则来自数据库，改承运商费率是改这里而不是改代码。 */
  @GetMapping("/rate-cards")
  public RateCardOverview rateCards(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String cardId,
      @RequestParam(required = false) String channelId,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String countryCode) {
    return call(
        auth, "channel.operation.read", a -> service.rateCards(cardId, channelId, status, countryCode));
  }

  /** 登记 / 改一张费率卡（档位整体替换）。 */
  @PostMapping("/rate-cards")
  public RateCardRow saveRateCard(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody SaveRateCardRequest request) {
    return call(auth, "channel.operation.create", a -> service.saveRateCard(request));
  }

  /** 登记 / 改一条分国家规则（偏远标记 + 试算时效区间）。 */
  @PostMapping("/country-rules")
  public ChannelCountryRule saveCountryRule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody SaveCountryRuleRequest request) {
    return call(auth, "channel.operation.create", a -> service.saveCountryRule(request));
  }
}
