package com.starcon.catalog;

import static com.starcon.catalog.PricingRuleModels.*;

import jakarta.validation.Valid;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.web.bind.annotation.DeleteMapping;
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
 * 5.3.2.2 价格规则。
 *
 * <p>命名空间 {@code /catalog/pricing-rules}。页面路由 {@code /pricing/rules}。
 *
 * <p>权限沿用商品主数据域：读 {@code catalog.spu.read}，写 {@code catalog.spu.update}。
 * 不新造权限词表 —— 新词表要改 identity 服务的迁移，那是另一个服务的写入所有权。
 *
 * <p><b>租户不来自任何请求头</b>：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 客户端伪造的 {@code X-Tenant-Id} 进不来也改不动它。
 *
 * <p><b>🔴 先建议，再执行</b>：{@code POST /evaluate} 产出的是**命中记录 + 建议调价**，
 * 不是生效价 —— 改价是 5.4.1.1 调价单的写入所有权（蓝图关系句：
 * 上游 = 7 库存、5.3.2.1，下游 = 5.4.1.1）。
 */
@RestController
@RequestMapping("/catalog/pricing-rules")
public class PricingRuleController {

  private final AccessService access;
  private final PricingRuleService rules;

  public PricingRuleController(AccessService access, PricingRuleService rules) {
    this.access = access;
    this.rules = rules;
  }

  /** 统一入口：先向 identity 确认身份与权限（租户**不**来自任何客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "catalog." + permission);
    return access.run(actor, "catalog." + permission, () -> work.apply(actor));
  }

  /**
   * 能力声明：触发条件 / 动作 / 作用范围 / 状态四张词表 + 口径说明。
   *
   * <p>🔴 页面固定显示它 —— 蓝图那句「自动调价必须先"建议"再"执行"」必须写在脸上。
   */
  @GetMapping("/capabilities")
  public PricingRuleCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "spu.read", a -> rules.capabilities());
  }

  /** 作用范围目录：内部分类 + 品牌。 */
  @GetMapping("/directory")
  public PricingRuleDirectory directory(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "spu.read", a -> rules.directory());
  }

  /** 规则台账：真分页 + 关键词 / 触发条件 / 动作 / 作用范围筛选。 */
  @GetMapping("/rules")
  public PricingRulePage rules(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String triggerType,
      @RequestParam(required = false) String actionType,
      @RequestParam(required = false) Boolean enabled,
      @RequestParam(required = false) String scopeType,
      @RequestParam(required = false) String scopeRef,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    return call(
        auth,
        "spu.read",
        a ->
            rules.list(
                new PricingRuleService.RuleQuery(
                    keyword,
                    triggerType == null ? null : TriggerType.parse(triggerType),
                    actionType == null ? null : ActionType.parse(actionType),
                    enabled,
                    scopeType == null ? null : ScopeType.parse(scopeType),
                    scopeRef,
                    page == null ? 0 : page,
                    size == null ? 20 : size)));
  }

  /** 规则详情 = 规则本身 + 作用范围。 */
  @GetMapping("/rules/{ruleId}")
  public PricingRuleDetail rule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID ruleId) {
    return call(auth, "spu.read", a -> rules.detail(ruleId));
  }

  /** 新建规则。 */
  @PostMapping("/rules")
  public PricingRuleDetail create(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody PricingRuleInput input) {
    return call(auth, "spu.update", a -> rules.save(a, null, input));
  }

  /** 修改规则（乐观锁：版本对不上时 409）。作用范围整体替换。 */
  @PutMapping("/rules/{ruleId}")
  public PricingRuleDetail update(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID ruleId,
      @Valid @RequestBody PricingRuleInput input) {
    return call(auth, "spu.update", a -> rules.save(a, ruleId, input));
  }

  /** 删除规则（连同它的作用范围）。 */
  @DeleteMapping("/rules/{ruleId}")
  public void delete(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID ruleId) {
    call(
        auth,
        "spu.update",
        a -> {
          rules.delete(ruleId);
          return null;
        });
  }

  /**
   * 跑一次规则评估：产出**命中记录**与**建议调价列表**（蓝图输出句）。
   *
   * <p>🔴 不改价：产出的是建议调价。高风险建议（降价超阈值 / 成本不完整 / 非价格动作）
   * 会停在「待人工确认」，由 {@code POST /suggestions/{id}/decide} 推进。
   */
  @PostMapping("/evaluate")
  public EvaluationResult evaluate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody EvaluateRequest request) {
    return call(auth, "spu.update", a -> rules.evaluate(a, request, auth));
  }

  /** 命中记录（真分页 + 规则 / SKU / 是否命中筛选）。 */
  @GetMapping("/hits")
  public HitPage hits(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID ruleId,
      @RequestParam(required = false) UUID skuId,
      @RequestParam(required = false) Boolean matched,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    return call(
        auth,
        "spu.read",
        a ->
            rules.listHits(
                new PricingRuleService.HitQuery(
                    ruleId, skuId, matched, page == null ? 0 : page, size == null ? 20 : size)));
  }

  /** 建议调价列表（真分页 + 规则 / SKU / 状态 / 是否需人工确认筛选）。 */
  @GetMapping("/suggestions")
  public SuggestionPage suggestions(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID ruleId,
      @RequestParam(required = false) UUID skuId,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) Boolean requiresConfirmation,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    return call(
        auth,
        "spu.read",
        a ->
            rules.listSuggestions(
                new PricingRuleService.SuggestionQuery(
                    ruleId,
                    skuId,
                    status == null ? null : SuggestionStatus.parse(status),
                    requiresConfirmation,
                    page == null ? 0 : page,
                    size == null ? 20 : size)));
  }

  /**
   * 人工确认 / 驳回一条建议调价。
   *
   * <p>🔴 只有「待人工确认」的建议可以改判；已放行 / 已确认 / 已驳回的不能改 ——
   * 建议列表是只追加的事实，改判历史等于把留痕洗掉。
   */
  @PostMapping("/suggestions/{suggestionId}/decide")
  public SuggestionView decide(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID suggestionId,
      @Valid @RequestBody DecideRequest request) {
    return call(auth, "spu.update", a -> rules.decide(a, suggestionId, request));
  }
}
