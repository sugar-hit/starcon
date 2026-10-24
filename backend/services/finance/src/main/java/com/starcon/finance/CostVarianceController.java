package com.starcon.finance;

import static com.starcon.finance.CostVarianceModels.*;

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
 * 12.4.1.3 成本差异分析的 HTTP 端点。
 *
 * <p><b>命名空间是 {@code /finance/cost-variance}</b>：与同服务的 12.4.1.2
 * （{@code /finance/cost-calculation}）、12.4.1.1（{@code /finance/cost-valuation}）、
 * 12.4.2.1（{@code /finance/cost-collections}）**刻意不共用前缀** —— 首 token 也刻意不同
 * （{@code cost-variance} ≠ {@code cost-calculation} ≠ {@code cost-valuation} ≠
 * {@code cost-collections}），免得「成本差异分析」被「成本计算执行」的前缀吃掉。
 *
 * <p>🔴 <b>本模块不复用 12.4.1.2 的 {@code cost-calculation/variance}</b>：12.4.1.2 的
 * 「差异对比」是 {@code runs/compare}（两次计算批次之间的逐 SKU 对比），
 * **不是**差异分析报表。两者的形状与口径都不同。
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 *
 * <p>权限码 {@code finance.cost_variance.read / .create}：
 * 跑一次差异分析与登记一版基线都不是读权限能带过去的动作。
 */
@RestController
@RequestMapping("/finance/cost-variance")
public class CostVarianceController {

  private final AccessService access;
  private final CostVarianceService variance;

  public CostVarianceController(AccessService access, CostVarianceService variance) {
    this.access = access;
    this.variance = variance;
  }

  /** 统一入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(
      String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "finance.cost_variance." + permission);
    return access.run(actor, "finance.cost_variance." + permission, () -> work.apply(actor));
  }

  /** 需要把调用方自己的会话转发给上游（identity / catalog / warehouse / trade）时用。 */
  private <T> T callWithBearer(
      String bearer, String permission, TwoArgs<AccessService.Access, String, T> work) {
    var actor = access.require(bearer, "finance.cost_variance." + permission);
    return access.run(
        actor, "finance.cost_variance." + permission, () -> work.apply(actor, bearer));
  }

  /** 两个入参的函数（避免在这里引 java.util.function.BiFunction 的类型噪声）。 */
  @FunctionalInterface
  interface TwoArgs<A, B, T> {
    T apply(A first, B second);
  }

  // ================================================================ 能力声明

  /** 能力声明：哪些上游真接通、哪些还没交付、归谁。 */
  @GetMapping("/capabilities")
  public FinanceCostVarianceCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> variance.capabilities());
  }

  // ================================================================ 选项

  /** 筛选项（基线类型 / 维度 / 币种 / 驱动因素 / 可比性 / 严重度）。 */
  @GetMapping("/options")
  public FinanceCostVarianceOptions options(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> variance.options());
  }

  // ================================================================ 基线维护

  /** 基线列表（真分页 + 真筛选）。 */
  @GetMapping("/baselines")
  public FinanceCostVarianceBaselinePage baselines(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String baselineType,
      @RequestParam(required = false) String dimension,
      @RequestParam(required = false) String dimensionRefId,
      @RequestParam(required = false) String currency,
      @RequestParam(required = false) String status,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(
        auth,
        "read",
        actor ->
            variance.baselines(baselineType, dimension, dimensionRefId, currency, status, page, size));
  }

  /**
   * 登记 / 切换一版基线（= **追加新版本**，不是就地改）。
   *
   * <p>🔴 旧版本**不被改写**（触发器钉死）；切换 = 收口旧版本的生效区间 + 插入新版本。
   */
  @PostMapping("/baselines")
  public FinanceCostVarianceBaselineRow upsertBaseline(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody FinanceCostVarianceBaselineRequest request) {
    return call(auth, "create", actor -> variance.upsertBaseline(request, actor));
  }

  /** 版本历史（新到旧）。 */
  @GetMapping("/baselines/versions")
  public java.util.List<FinanceCostVarianceBaselineRow> baselineVersions(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam String baselineType,
      @RequestParam String dimension,
      @RequestParam String dimensionKey,
      @RequestParam(required = false) String currency) {
    return call(
        auth,
        "read",
        actor -> variance.baselineVersions(baselineType, dimension, dimensionKey, currency));
  }

  /** 版本对比（切换前 / 切换后，逐字段给出差异）。 */
  @GetMapping("/baselines/compare")
  public FinanceCostVarianceBaselineComparison compareBaselines(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam String baselineType,
      @RequestParam String dimension,
      @RequestParam String dimensionKey,
      @RequestParam String currency,
      @RequestParam int fromVersion,
      @RequestParam int toVersion) {
    return call(
        auth,
        "read",
        actor ->
            variance.compareBaselines(
                baselineType, dimension, dimensionKey, currency, fromVersion, toVersion));
  }

  // ================================================================ 差异分析

  /**
   * 执行一次差异分析：12.4.1.2 的真实结转金额 → 五类差异 → 报表 + 归因 + 异常提示。
   *
   * <p>⚠️ 上游（采购价 / 头程 / 损耗 / 汇率）读不到**不会**让整张报表失败：
   * 只有受影响的那一类差异被标成「上游不可得」，其余照常出数。
   * 只有<b>基数</b>（12.4.1.2 的已成批次）缺失时才拒绝。
   */
  @PostMapping("/reports")
  public FinanceCostVarianceReportDetail analyze(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody FinanceCostVarianceReportRequest request) {
    return callWithBearer(
        auth, "create", (actor, bearer) -> variance.analyze(request, actor, bearer));
  }

  /** 报表列表（真分页 + 真筛选）。 */
  @GetMapping("/reports")
  public FinanceCostVarianceReportPage reports(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String scopeType,
      @RequestParam(required = false) UUID scopeRefId,
      @RequestParam(required = false) String status,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(
        auth, "read", actor -> variance.reports(scopeType, scopeRefId, status, page, size));
  }

  /** 报表明细（表头 + 按币种分组 + 每组的驱动因素与告警）。 */
  @GetMapping("/reports/{id}")
  public FinanceCostVarianceReportDetail reportDetail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "read", actor -> variance.reportDetail(id));
  }

  // ================================================================ 结果

  /** 明细列表（真分页 + 真筛选）：逐 SKU × 币种 × 驱动因素。 */
  @GetMapping("/items")
  public FinanceCostVarianceItemPage items(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID reportId,
      @RequestParam(required = false) UUID skuId,
      @RequestParam(required = false) String driver,
      @RequestParam(required = false) String currency,
      @RequestParam(required = false) String comparability,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(
        auth,
        "read",
        actor -> variance.items(reportId, skuId, driver, currency, comparability, page, size));
  }

  /**
   * 驱动因素列表（真分页 + 真筛选）：按币种分组 + **主因归因**。
   *
   * <p>每一行带 {@code rank} 与 {@code isPrimary} —— 同一个币种里按 |金额| 排名，
   * 贡献最大的那一类就是该币种的主因。
   */
  @GetMapping("/drivers")
  public FinanceCostVarianceDriverPage drivers(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID reportId,
      @RequestParam(required = false) String currency,
      @RequestParam(required = false) String driver,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(auth, "read", actor -> variance.drivers(reportId, currency, driver, page, size));
  }

  /** 异常提示列表（真分页 + 真筛选）。 */
  @GetMapping("/alerts")
  public FinanceCostVarianceAlertPage alerts(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID reportId,
      @RequestParam(required = false) String code,
      @RequestParam(required = false) String severity,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(auth, "read", actor -> variance.alerts(reportId, code, severity, page, size));
  }
}
