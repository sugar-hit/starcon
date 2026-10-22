package com.starcon.channel;

import static com.starcon.channel.BuyerRiskScoreModels.*;

import jakarta.validation.Valid;
import java.util.function.Function;
import org.springframework.web.bind.annotation.*;

/**
 * 16.5.1.2 风险评分。
 *
 * <p>蓝图：「计算买家风险分：历史退款率、纠纷率、欺诈特征、地址模式、下单行为、关联账号，
 * 输出评分与建议动作。输入：买家行为数据；风险特征规则；权重配置。输出：风险评分 + 特征解释。
 * 关系：上游 = 15 客户、11 售后、9 订单；下游 = 16.5.1.1、9.2.2.2。
 * <b>评分要能解释</b>（为什么这个买家 80 分），黑盒分数客服无法向平台申诉。」
 *
 * <p>🔴 端点清单（命名空间 {@code /channel/buyer-risk-scores}，与既有的
 * {@code /channel/compliance-precheck}、{@code /channel/tax-registration} 等**并列而不重叠**）：
 *
 * <ul>
 *   <li>{@code GET /capabilities} —— 能力声明：逐维 available + ownerModule + 上游读面可用性。
 *   <li>{@code GET /rule} —— 评分规则（权重 + 阈值 + 缺信号口径）。<b>没登记过返回 configured=false</b>，
 *       而不是 404 —— 404 会让界面把「没登记」误读成「读失败」。返回体里**不预填默认权重**。
 *   <li>{@code PUT /rule} —— 登记 / 修改规则（upsert，一租户一条）。
 *   <li>{@code GET /buyers} —— 买家名册（trade 的租户自维护画像）+ 各自的最近一次评分。
 *   <li>{@code POST /evaluate} —— 🔴 <b>跑一次评分</b>（真去上游读、真算、真落库）。
 *   <li>{@code GET /scores} —— 评分历史（真分页 + 真筛选 + 汇总计数）。
 *   <li>{@code GET /scores/{id}} —— 单次评分详情（头 + <b>逐特征贡献明细</b> + 回写凭据）。
 * </ul>
 *
 * <p>🔴 <b>刻意没有的形状</b>：没有「黑名单」端点（16.5.1.1 未交付）、
 * 没有「关联账号」端点（16.5.2.2 未交付）、没有「改订单」端点（9.2 的事）、
 * 没有「删除评分」端点（评分是判定凭据，append-only）——
 * <b>放行一个不存在的入口比 404 更糟</b>（界面会以为功能已交付）。
 *
 * <p>权限码<b>复用</b> identity 已有的 {@code channel.operation.*}（读 / 录 / 改），identity 零改动。
 * 租户<b>不</b>来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 FORCE RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 */
@RestController
@RequestMapping("/channel/buyer-risk-scores")
public class BuyerRiskScoreController {

  private final AccessService access;
  private final BuyerRiskScoreService service;

  public BuyerRiskScoreController(AccessService access, BuyerRiskScoreService service) {
    this.access = access;
    this.service = service;
  }

  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, permission);
    return access.run(actor, permission, () -> work.apply(actor));
  }

  // ---- 能力声明 ----

  /** 能力声明：逐维 available + ownerModule + 上游读面可用性 + 四条口径说明。 */
  @GetMapping("/capabilities")
  public BuyerRiskCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.capabilities());
  }

  // ---- 规则 ----

  /** 评分规则。没登记过返回 configured=false（**不预填任何默认权重**）。 */
  @GetMapping("/rule")
  public BuyerRiskRuleView rule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.rule());
  }

  /** 登记 / 修改规则（upsert）。权重合计必须恰好 1.0000，否则 422。 */
  @PutMapping("/rule")
  public BuyerRiskRuleView saveRule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody BuyerRiskRuleInput input) {
    return call(auth, "channel.operation.update", a -> service.saveRule(input));
  }

  // ---- 买家名册 ----

  /** 买家名册（trade 的租户自维护画像）+ 各自的最近一次评分。 */
  @GetMapping("/buyers")
  public BuyerRiskBuyerPage buyers(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String keyword,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(auth, "channel.operation.read", a -> service.buyers(auth, keyword, page, size));
  }

  // ---- 评分 ----

  /**
   * 🔴 跑一次评分：真去 trade 读订单 / 退款 / 售后 / 买家画像，真算，真落库。
   *
   * <p>{@code buyerKey} 留空 ⇒ 评「画像买家 ∪ 订单里发现的买家」；
   * 填了 ⇒ 只评这一个买家。
   */
  @PostMapping("/evaluate")
  public BuyerRiskEvaluateResult evaluate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody(required = false) BuyerRiskEvaluateInput input) {
    return call(auth, "channel.operation.create", a -> service.evaluate(auth, input));
  }

  /** 评分历史（真分页 + 真筛选 + 汇总计数）。 */
  @GetMapping("/scores")
  public BuyerRiskScorePage scores(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String buyerKey,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String action,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(auth, "channel.operation.read", a -> service.scores(buyerKey, status, action, page, size));
  }

  /** 单次评分详情：头 + 逐特征贡献明细 + 回写凭据。 */
  @GetMapping("/scores/{id}")
  public BuyerRiskScoreView score(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "channel.operation.read", a -> service.score(id));
  }
}
