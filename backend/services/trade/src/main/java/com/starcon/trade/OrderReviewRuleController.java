package com.starcon.trade;

import static com.starcon.trade.OrderReviewRuleModels.*;

import jakarta.validation.Valid;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 9.2.1.2 审单规则。
 *
 * <p>命名空间 {@code /trade/order-review}：与 9.3 的 {@code /trade/fulfillment/*} 并列 ——
 * 「发货前该不该放行」与「放行之后怎么履约」是两件事，权限与日志分得开。
 *
 * <p>权限码用 {@code trade.order_review.read} / {@code trade.order_review.update}：
 * 启用一条规则会直接改变之后**每一张订单**的审单结论（可能让整批货自动放行），
 * 不是读权限能带过去的动作。
 *
 * <p>租户**不**来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 *
 * <p>刻意**不占用** {@code /trade/orders/review}：待审队列是 9.2.1.1（未交付）的主权，
 * 本模块只提供判定与命中链，不推进订单状态。
 */
@RestController
@RequestMapping("/trade/order-review")
public class OrderReviewRuleController {

  private final AccessService access;
  private final OrderReviewRuleService rules;

  public OrderReviewRuleController(AccessService access, OrderReviewRuleService rules) {
    this.access = access;
    this.rules = rules;
  }

  /** 统一入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "trade." + permission);
    return access.run(actor, "trade." + permission, () -> work.apply(actor));
  }

  // ============================================================ 能力声明

  /**
   * 本模块的能力与上下游可用性。
   *
   * <p>存在的理由：SHOP 维度当前只能按**平台**判定（sales_order 无 shop_code）、
   * 买家等级与黑名单由本服务自维护（15 客户 / 16 合规未交付）——
   * 这些必须由接口自己说，而不是让界面从空数组里猜。
   */
  @GetMapping("/capabilities")
  public OrderReviewRuleCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "order_review.read", a -> rules.capabilities());
  }

  // ============================================================ 规则维护

  /** 规则列表（真分页；四组汇总额不受分页影响）。 */
  @GetMapping("/rules")
  public ReviewRulePage ruleList(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String action,
      @RequestParam(required = false, defaultValue = "false") Boolean forcedOnly,
      @RequestParam(required = false) String dimension,
      @RequestParam(required = false, defaultValue = "false") Boolean effectiveOnly,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(auth, "order_review.read",
        a -> rules.pageRules(
            new ReviewRuleQuery(keyword, status, action, forcedOnly, dimension, effectiveOnly, page, size)));
  }

  /** 规则详情：头 + 条件 + 最近一次判定结论。跨租户在 RLS 下查不到 → 404。 */
  @GetMapping("/rules/{ruleId}")
  public ReviewRuleDetail rule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID ruleId) {
    return call(auth, "order_review.read", a -> rules.rule(ruleId));
  }

  /** 新建规则（不带 version）。一律建成草稿 —— 启用是另一个动作，要单独决定。 */
  @PostMapping("/rules")
  public ReviewRuleSaved createRule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody ReviewRuleInput input) {
    return call(auth, "order_review.update", a -> rules.saveRule(input, a.membershipId()));
  }

  /** 更新规则（带 version 乐观锁）。条件整体替换：不在列表里的条件会被移除。 */
  @PutMapping("/rules/{ruleId}")
  public ReviewRuleSaved updateRule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID ruleId,
      @Valid @RequestBody ReviewRuleInput input) {
    return call(auth, "order_review.update", a -> {
      var updated = new ReviewRuleInput(input.ruleCode(), input.ruleName(), input.description(),
          input.priority(), input.action(), input.forcedManual(), input.matchMode(),
          input.effectiveFrom(), input.effectiveTo(), input.capabilityNote(), input.note(),
          input.conditions(), input.version());
      if (!rules.rule(ruleId).rule().ruleCode().equals(updated.ruleCode())) {
        throw new org.springframework.web.server.ResponseStatusException(
            org.springframework.http.HttpStatus.BAD_REQUEST,
            "规则编号不可修改：它在租户内唯一，同时是同优先级时的排序依据 —— "
                + "改编号会让历史命中链的次序不可复现");
      }
      return rules.saveRule(updated, a.membershipId());
    });
  }

  /** 切换规则状态（DRAFT / ACTIVE / DISABLED）。启用要求至少一个条件（DB 层再挡一次）。 */
  @PostMapping("/rules/{ruleId}/status")
  public ReviewRuleSaved changeStatus(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID ruleId,
      @Valid @RequestBody ReviewRuleStatusInput input) {
    return call(auth, "order_review.update", a -> rules.changeStatus(ruleId, input, a.membershipId()));
  }

  // ============================================================ 买家画像

  /** 买家画像列表（真分页）：等级与黑名单的登记入口。 */
  @GetMapping("/buyer-profiles")
  public BuyerProfilePage buyerProfiles(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String buyerLevel,
      @RequestParam(required = false, defaultValue = "false") Boolean blacklistedOnly,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(auth, "order_review.read",
        a -> rules.pageBuyerProfiles(new BuyerProfileQuery(keyword, buyerLevel, blacklistedOnly, page, size)));
  }

  /**
   * 登记 / 更新买家画像（按身份键 upsert）。
   *
   * <p>拉黑必须写理由 —— 没有理由的黑名单，客服无法向买家解释，
   * 而那正是本模块要解决的问题。
   */
  @PutMapping("/buyer-profiles")
  public BuyerProfile saveBuyerProfile(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody BuyerProfileInput input) {
    return call(auth, "order_review.update", a -> rules.saveBuyerProfile(input, a.membershipId()));
  }

  // ============================================================ 判定

  /**
   * 对一张真实订单跑一次审单判定（RECORDED），产出结论与命中链。
   *
   * <p>判定时刻由调用方显式给出：判定是「某时刻对某张单」的事实，
   * 生效期判定必须与这个时刻比较，不能取服务器当前时间。
   */
  @PostMapping("/orders/{orderId}/review")
  public ReviewDetail review(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID orderId,
      @Valid @RequestBody ReviewRunInput input) {
    return call(auth, "order_review.update", a -> rules.review(orderId, input, a.membershipId()));
  }

  /**
   * 试跑（DRY_RUN）：拿真实订单试规则，标成试跑不冒充生产结论。
   *
   * <p>蓝图输入句里的「测试数据」入口。{@code ruleIds} 不给就跑此刻生效中的规则；
   * 给了就只跑点名的那些（哪怕还是草稿），用于「规则还没启用，先验一遍」。
   */
  @PostMapping("/orders/{orderId}/trial")
  public ReviewDetail trial(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID orderId,
      @Valid @RequestBody ReviewRunInput input) {
    return call(auth, "order_review.read", a -> rules.trial(orderId, input, a.membershipId()));
  }

  /** 判定记录列表（真分页）：这是「哪张单被哪条规则拦下了」的查询入口。 */
  @GetMapping("/evaluations")
  public EvaluationPage evaluations(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) UUID orderId,
      @RequestParam(required = false) String decision,
      @RequestParam(required = false) String mode,
      @RequestParam(required = false, defaultValue = "false") Boolean forcedOnly,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(auth, "order_review.read",
        a -> rules.pageEvaluations(
            new EvaluationQuery(keyword, orderId, decision, mode, forcedOnly, page, size)));
  }

  /** 判定详情：结论 + 完整命中链（逐条件到订单字段）+ 订单事实快照。 */
  @GetMapping("/evaluations/{evaluationId}")
  public ReviewDetail evaluation(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID evaluationId) {
    return call(auth, "order_review.read", a -> rules.evaluation(evaluationId));
  }
}
