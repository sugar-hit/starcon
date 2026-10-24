package com.starcon.finance;

import static com.starcon.finance.CostCalculationModels.*;

import jakarta.validation.Valid;
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
 * 12.4.1.2 成本计算执行的 HTTP 端点。
 *
 * <p><b>命名空间是 {@code /finance/cost-calculation}</b>：与同服务的 12.4.1.1（{@code /finance/cost-valuation}）、
 * 12.4.2.1（{@code /finance/cost-collections}）刻意不共用一个前缀 —— 首 token 也刻意不同
 * （{@code cost-calculation} ≠ {@code cost-valuation} ≠ {@code cost-collections}），
 * 免得「成本计算执行」被「计价方法配置」的前缀吃掉。
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 *
 * <p>权限码 {@code finance.cost_calculation.read / .create / .update}：
 * 算一次成本不是读权限能带过去的动作。
 */
@RestController
@RequestMapping("/finance/cost-calculation")
public class CostCalculationController {

  private final AccessService access;
  private final CostCalculationService calculations;

  public CostCalculationController(AccessService access, CostCalculationService calculations) {
    this.access = access;
    this.calculations = calculations;
  }

  /** 统一入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(
      String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "finance.cost_calculation." + permission);
    return access.run(actor, "finance.cost_calculation." + permission, () -> work.apply(actor));
  }

  /** 需要把调用方自己的会话转发给上游（identity / catalog / warehouse）时用。 */
  private <T> T callWithBearer(
      String bearer, String permission, TwoArgs<AccessService.Access, String, T> work) {
    var actor = access.require(bearer, "finance.cost_calculation." + permission);
    return access.run(
        actor, "finance.cost_calculation." + permission, () -> work.apply(actor, bearer));
  }

  /** 两个入参的函数（避免在这里引 java.util.function.BiFunction 的类型噪声）。 */
  @FunctionalInterface
  interface TwoArgs<A, B, T> {
    T apply(A first, B second);
  }

  // ================================================================ 能力声明

  /** 能力声明：哪些上游真接通、哪些还没交付、归谁。 */
  @GetMapping("/capabilities")
  public FinanceCostCalculationCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> calculations.capabilities());
  }

  // ================================================================ 选项

  /** 筛选项 + 四个计价方法选项（复用 12.4.1.1 的方法选项与口径说明）。 */
  @GetMapping("/options")
  public FinanceCostCalculationOptions options(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> calculations.options());
  }

  // ================================================================ 执行成本计算

  /**
   * 执行一次成本计算：期间内的销售出库 / 发货 / 平台仓转出 → 成本层 + 成本分录。
   *
   * <p>⚠️ 上游（warehouse 库存流水 / catalog 成本层）读不到一律 <b>503</b> 并写明「读不到」——
   * 绝不返回空结果冒充「这个期间没有出库」。
   */
  @PostMapping("/runs")
  public FinanceCostCalculationRunRow calculate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody FinanceCostCalculationRequest request) {
    return callWithBearer(
        auth, "create", (actor, bearer) -> calculations.calculate(request, actor, bearer));
  }

  /** 批次列表（真分页 + 真筛选）。 */
  @GetMapping("/runs")
  public FinanceCostCalculationRunPage runs(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String scopeType,
      @RequestParam(required = false) UUID scopeRefId,
      @RequestParam(required = false) String valuationMethod,
      @RequestParam(required = false) String status,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(
        auth,
        "read",
        actor -> calculations.runs(scopeType, scopeRefId, valuationMethod, status, page, size));
  }

  /** 批次明细（表头 + 全部成本层 + 全部成本分录）。 */
  @GetMapping("/runs/{id}")
  public FinanceCostCalculationRunDetail runDetail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "read", actor -> calculations.runDetail(id));
  }

  /**
   * 按期间重算：同一个范围、同一个期间，用**当前生效**的计价方法重算一遍。
   *
   * <p>⚠️ 重算 = <b>另起批次</b>：旧批次是当时那笔计算的证据，不被改写；
   * 新批次把旧批次挂进重算链。
   */
  @PostMapping("/runs/{id}/recalculate")
  public FinanceCostCalculationRunRow recalculate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return callWithBearer(
        auth, "update", (actor, bearer) -> calculations.recalculate(id, actor, bearer));
  }

  /**
   * 差异对比：两次批次之间逐 SKU 对比。
   *
   * <p>⚠️ 这是**两次计算批次之间**的对比，<b>不是</b> 12.4.1.3 的成本差异分析。
   */
  @GetMapping("/runs/compare")
  public FinanceCostCalculationVarianceComparison compare(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam UUID fromRunId,
      @RequestParam UUID toRunId) {
    return call(auth, "read", actor -> calculations.variance(fromRunId, toRunId));
  }

  // ================================================================ 结果

  /** 成本层列表（真分页 + 真筛选）。 */
  @GetMapping("/layers")
  public FinanceCostCalculationLayerPage layers(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID runId,
      @RequestParam(required = false) UUID skuId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(auth, "read", actor -> calculations.layers(runId, skuId, page, size));
  }

  /** 成本分录列表（真分页 + 真筛选 + 汇总）。 */
  @GetMapping("/entries")
  public FinanceCostCalculationEntryPage entries(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID runId,
      @RequestParam(required = false) UUID skuId,
      @RequestParam(required = false) String businessType,
      @RequestParam(required = false) String status,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(
        auth,
        "read",
        actor ->
            calculations.entries(runId, skuId, businessType, status, page, size));
  }
}
