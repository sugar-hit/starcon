package com.starcon.finance;

import static com.starcon.finance.FundAnalysisModels.*;

import java.time.LocalDate;
import java.util.function.Function;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 12.7.2.1 资金分析的 HTTP 端点。
 *
 * <p><b>命名空间是 {@code /finance/fund-analysis}</b>：与 12.1.1.1 的 {@code /finance/receivables}
 * （应收单管理）、12.1.1.2 的 {@code /finance/receivable-aging}（应收账龄）**刻意不共用一个前缀** ——
 * 那是别的模块的命名空间（它的端点、它的契约、它的页面都归它所有），本模块另起一个前缀。
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 *
 * <p><b>权限码沿用 12.1.1.2 应收账龄的 {@code finance.receivable.read / .update}</b>：
 * 资金分析是应收 / 应付 / 收付款 / 汇率的**读侧投影**，与账龄同一性质。
 * <b>刻意不新造权限词表</b> —— 权限码在 identity 的 {@code permission(code)} 目录里，
 * 新造一档要改另一个服务的迁移（那是它的写入所有权，且迁移版本号由调度者统一分配）。
 * 账龄已经是这么做的（能看应收单的人就能看账龄），本模块照抄同一套。
 */
@RestController
@RequestMapping("/finance/fund-analysis")
public class FundAnalysisController {

  private final AccessService access;
  private final FundAnalysisService analysis;

  public FundAnalysisController(AccessService access, FundAnalysisService analysis) {
    this.access = access;
    this.analysis = analysis;
  }

  /** 统一入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "finance.receivable." + permission);
    return access.run(actor, "finance.receivable." + permission, () -> work.apply(actor));
  }

  // ================================================================ 能力声明

  /**
   * 能力声明：哪些上游真接通、哪些还没交付、归谁。
   *
   * <p>页面把它固定显示在顶部 —— 12.7.1.1 账户管理 / 12.7.1.2 银行对账**未交付**这件事
   * 必须让人在报表旁边看到，而不是让人以为「账户余额是 0」。
   */
  @GetMapping("/capabilities")
  public FinanceFundAnalysisCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> analysis.capabilities());
  }

  // ================================================================ 筛选项

  /** 筛选项下拉（币种 + 本位币候选，全部来自真实数据）。 */
  @GetMapping("/options")
  public FinanceFundAnalysisOptions options(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> analysis.options());
  }

  // ================================================================ 现金流入流出趋势

  /**
   * 现金流入流出趋势：时间桶 × 币种。
   *
   * <p>⚠️ 三个口径并列（实际流入 / 预期流入 / 计划流出），响应里逐项标注，不混。
   */
  @GetMapping("/cash-flow-trend")
  public FinanceFundFlowTrend cashFlowTrend(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String currency,
      @RequestParam(required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate from,
      @RequestParam(required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate to,
      @RequestParam(required = false) FinanceFundTrendGranularity granularity) {
    return call(
        auth,
        "read",
        actor -> analysis.trend(new FinanceFundFlowTrendQuery(currency, from, to, granularity)));
  }

  // ================================================================ 各币种资金分布

  /** 各币种资金分布（收款 / 应收 / 应付 / 计划 四路真实聚合，按币种分开）。 */
  @GetMapping("/currency-distribution")
  public FinanceFundCurrencyDistribution currencyDistribution(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String currency) {
    return call(
        auth,
        "read",
        actor -> analysis.distribution(new FinanceFundCurrencyDistributionQuery(currency)));
  }

  // ================================================================ 汇率敞口

  /**
   * 汇率敞口测算（实时，不落库）。
   *
   * <p>⚠️ 汇率取不到一律 503 显式失败 —— 绝不返回空集合冒充「没有敞口」。
   */
  @GetMapping("/exposure")
  public FinanceFundExposureReport exposure(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String baseCurrency,
      @RequestParam(required = false) String currency) {
    return call(auth, "read", actor -> analysis.exposure(new FinanceFundExposureQuery(baseCurrency, currency), auth));
  }

  /**
   * 汇率敞口测算并**落成快照**。
   *
   * <p>快照把「这次用的哪天的汇率、汇率是多少、两端金额是多少」一起钉住 ⇒ 事后可复盘。
   */
  @PostMapping("/exposure/measure")
  public FinanceFundExposureMeasureResult measure(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String baseCurrency,
      @RequestParam(required = false) String currency) {
    return call(
        auth,
        "update",
        actor -> analysis.measure(new FinanceFundExposureQuery(baseCurrency, currency), auth, actor));
  }

  /** 敞口快照列表（真分页 + 真排序）。 */
  @GetMapping("/exposure/snapshots")
  public FinanceFundExposureSnapshotPage exposureSnapshots(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(auth, "read", actor -> analysis.exposureSnapshots(page, size));
  }

  // ================================================================ 资金周转天数

  /**
   * 资金周转天数（按币种分开）。
   *
   * <p>⚠️ DSO / DPO 的分子分母同币种、同 UTC 日历日口径；分母为 0 时给 null 而不是 0 天。
   */
  @GetMapping("/turnover-days")
  public FinanceFundTurnoverReport turnoverDays(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String currency,
      @RequestParam(required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate from,
      @RequestParam(required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate to) {
    return call(
        auth,
        "read",
        actor -> analysis.turnover(new FinanceFundTurnoverQuery(currency, from, to)));
  }

  // ================================================================ 付款集中度

  /** 付款集中度：时间维度 + 付款对象维度（口径 = 计划付款）。 */
  @GetMapping("/payment-concentration")
  public FinanceFundPaymentConcentrationReport paymentConcentration(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String currency,
      @RequestParam(required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate from,
      @RequestParam(required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate to,
      @RequestParam(required = false) FinanceFundTrendGranularity granularity) {
    return call(
        auth,
        "read",
        actor -> analysis.concentration(new FinanceFundConcentrationQuery(currency, from, to, granularity)));
  }

  // ================================================================ 流动性预警

  /** 流动性预警：四条规则跑在真实聚合上，阈值来自租户配置或蓝图默认档。 */
  @GetMapping("/liquidity-alerts")
  public FinanceFundLiquidityAlertReport liquidityAlerts(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate from,
      @RequestParam(required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate to) {
    return call(auth, "read", actor -> analysis.alerts(new FinanceFundLiquidityAlertQuery(from, to)));
  }

  // ================================================================ 阈值配置

  /** 生效中的流动性预警阈值（租户配的，或蓝图默认档）。 */
  @GetMapping("/thresholds")
  public FinanceFundLiquidityThresholdConfig thresholds(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> analysis.thresholdConfig());
  }

  /**
   * 配置流动性预警阈值（**整体替换**）。
   *
   * <p>⚠️ 四条规则必须**全部**给出（不想启用某条就把 enabled 置为 false）——
   * 部分配置会让预警集合没有确定含义。传空列表 = 回到蓝图默认档。
   */
  @PutMapping("/thresholds")
  public FinanceFundLiquidityThresholdConfig updateThresholds(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody FundAnalysisModels.FinanceFundLiquidityThresholdConfigInput input) {
    return call(auth, "update", actor -> analysis.updateThresholds(input, actor));
  }
}
