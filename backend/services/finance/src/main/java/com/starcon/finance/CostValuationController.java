package com.starcon.finance;

import static com.starcon.finance.CostValuationModels.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 12.4.1.1 计价方法配置的 HTTP 端点。
 *
 * <p><b>命名空间是 {@code /finance/cost-valuation}</b>：与同服务的 12.4.2.1（{@code /finance/cost-collections}）、
 * 12.3.1.3（{@code /finance/deferred-settlement}）刻意不共用一个前缀 —— 首 token 也刻意不同
 * （{@code cost-valuation} ≠ {@code cost-collections} ≠ {@code deferred-settlement}），
 * 免得「计价方法配置」被「费用归集」的前缀吃掉。
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 *
 * <p>权限码 {@code finance.cost_valuation.read / .create / .update}：
 * 切换计价方法不是读权限能带过去的动作。
 */
@RestController
@RequestMapping("/finance/cost-valuation")
public class CostValuationController {

  private final AccessService access;
  private final CostValuationService costValuations;

  public CostValuationController(AccessService access, CostValuationService costValuations) {
    this.access = access;
    this.costValuations = costValuations;
  }

  /** 统一入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(
      String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "finance.cost_valuation." + permission);
    return access.run(actor, "finance.cost_valuation." + permission, () -> work.apply(actor));
  }

  /** 需要把调用方自己的会话转发给上游（identity / catalog）时用。 */
  private <T> T callWithBearer(
      String bearer, String permission, TwoArgs<AccessService.Access, String, T> work) {
    var actor = access.require(bearer, "finance.cost_valuation." + permission);
    return access.run(
        actor, "finance.cost_valuation." + permission, () -> work.apply(actor, bearer));
  }

  /** 两个入参的函数（避免在这里引 java.util.function.BiFunction 的类型噪声）。 */
  @FunctionalInterface
  interface TwoArgs<A, B, T> {
    T apply(A first, B second);
  }

  // ================================================================ 能力声明

  /** 能力声明：哪些上游真接通、哪些还没交付、归谁。 */
  @GetMapping("/capabilities")
  public FinanceCostValuationCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> costValuations.capabilities());
  }

  // ================================================================ 选项

  /** 筛选项 + 四个计价方法选项（系统提供选项，不替用户决定政策）。 */
  @GetMapping("/options")
  public FinanceCostValuationOptions options(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> costValuations.options());
  }

  /** 可用计价范围（法人 + 品类）：实时读 identity / catalog，读不到就如实说读不到。 */
  @GetMapping("/scopes")
  public FinanceCostValuationScopeOptions scopes(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return callWithBearer(auth, "read", (actor, bearer) -> costValuations.scopeOptions(bearer));
  }

  // ================================================================ 方法配置

  /** 计价方法配置列表（真分页 + 真筛选）。 */
  @GetMapping("/methods")
  public FinanceCostValuationMethodPage methods(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String scopeType,
      @RequestParam(required = false) UUID scopeRefId,
      @RequestParam(required = false) String valuationMethod,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String currency,
      @RequestParam(required = false) String keyword,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(
        auth,
        "read",
        actor ->
            costValuations.methods(
                scopeType,
                scopeRefId,
                valuationMethod,
                status,
                currency,
                keyword,
                page,
                size));
  }

  /**
   * 登记 / 切换计价方法（按范围 upsert：同范围已有生效版本 ⇒ 追加新版本）。
   *
   * <p>⚠️ 切换方法 = <b>追加版本</b>，旧版本被收口并留作版本对比；
   * 新版本生效前，历史成本仍按旧方法解释 —— 历史成本不会被追溯改写。
   */
  @PostMapping("/methods")
  public FinanceCostValuationMethodRow switchMethod(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody FinanceCostValuationMethodInput input) {
    return call(auth, "update", actor -> costValuations.switchMethod(input, actor));
  }

  /** 版本历史（新到旧）。 */
  @GetMapping("/methods/versions")
  public List<FinanceCostValuationMethodRow> versions(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam String scopeType,
      @RequestParam UUID scopeRefId) {
    return call(auth, "read", actor -> costValuations.versions(scopeType, scopeRefId));
  }

  /**
   * 版本对比（蓝图「切换方法需重算并留版本对比」的**配置面**）。
   *
   * <p>⚠️ 对比的是配置本身，<b>不是</b>重算结果（重算属 12.4.1.2）。
   */
  @GetMapping("/methods/compare")
  public FinanceCostValuationVersionComparison compare(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam String scopeType,
      @RequestParam UUID scopeRefId,
      @RequestParam int fromVersion,
      @RequestParam int toVersion) {
    return call(
        auth,
        "read",
        actor -> costValuations.compare(scopeType, scopeRefId, fromVersion, toVersion));
  }

  // ================================================================ 指定成本

  /** 指定单价列表（真分页 + 真筛选）。 */
  @GetMapping("/specified-costs")
  public FinanceCostValuationSpecifiedCostPage specifiedCosts(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID skuId,
      @RequestParam(required = false) String currency,
      @RequestParam(required = false) String status,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(
        auth,
        "read",
        actor -> costValuations.specifiedCosts(skuId, currency, status, page, size));
  }

  /** 维护指定单价（同 SKU + 币种已有生效值 ⇒ 追加新版本）。 */
  @PostMapping("/specified-costs")
  public FinanceCostValuationSpecifiedCostRow upsertSpecifiedCost(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody FinanceCostValuationSpecifiedCostInput input) {
    return call(auth, "update", actor -> costValuations.upsertSpecifiedCost(input, actor));
  }

  // ================================================================ 试算

  /**
   * 发起一次试算：四种计价方法在同一批真实成本层上的结果并排给出。
   *
   * <p>⚠️ 上游（catalog 成本层）读不到一律 <b>503</b> 并写明「读不到」——
   * 绝不返回空结果冒充「这个 SKU 没有成本层」。
   */
  @PostMapping("/trials")
  public FinanceCostValuationTrialRow trial(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody FinanceCostValuationTrialRequest request) {
    return callWithBearer(
        auth, "create", (actor, bearer) -> costValuations.trial(request, actor, bearer));
  }

  /** 试算列表（真分页 + 真筛选）。 */
  @GetMapping("/trials")
  public FinanceCostValuationTrialPage trials(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID skuId,
      @RequestParam(required = false) String scopeType,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(auth, "read", actor -> costValuations.trials(skuId, scopeType, page, size));
  }

  /** 试算明细（表头 + 四个方法的结果）。 */
  @GetMapping("/trials/{id}")
  public FinanceCostValuationTrialRow trialDetail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "read", actor -> costValuations.trialDetail(id));
  }

}
