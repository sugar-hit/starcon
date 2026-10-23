package com.starcon.finance;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 12.7.2.1 资金分析的传输模型。
 *
 * <p>蓝图 12-finance.md 原文：
 *
 * <blockquote>
 *
 * 职责：资金分析：现金流入流出趋势、各币种资金分布与汇率敞口、资金周转天数、付款集中度与流动性预警。 输入：资金流水；应收应付账期；汇率数据。 输出：资金报表 + 汇率敞口测算 + 流动性预警。 关系：上游 = 12.7.1、12.1.2.2；下游 = 1 工作台。 <b>汇率敞口是跨境特有风险</b>：大量外币存货 + 本币负债的组合下，汇率波动直接影响利润。
 *
 * </blockquote>
 *
 * <p><b>命名。</b>统一用 {@code FinanceFundAnalysis*} 前缀 —— 与既有 12.1.x / 12.2.x / 12.3.x / 12.5.x 类型名零重叠。
 * 契约被 codegen 合并进一个扁平文件，同名后者覆盖前者且不报冲突（见 tools/codegen/catalog-contracts.py 的跨服务重名检测）。
 *
 * <p><b>本模块的五条口径纪律</b>（每一条都在下面有对应实现）：
 *
 * <ol>
 *   <li><b>币种分开。</b>USD 与 EUR 加起来不是任何一个金额 ⇒ 每一处汇总都按币种分组返回，
 *       没有任何一处跨币种求和。</li>
 *   <li><b>实际 ≠ 计划。</b>12.1.2.2 的付款计划写不出「已付款」，
 *       12.2.2.1 未交付 ⇒ 流出侧只有<b>计划口径</b>与<b>累计已付口径</b>，
 *       两者都不许被当成「实际流出的时间序列」。</li>
 *   <li><b>日历日一律 UTC。</b>周转天数、逾期、账期、预警窗口全部按 UTC 日历日算
 *       （{@code LocalDate.now(ZoneOffset.UTC)} / SQL 侧 {@code (now() at time zone 'UTC')::date}）。
 *       用会话时区的话，换一条连接就换一套分桶。</li>
 *   <li><b>占比是服务端算的。</b>页面不做业务算术。</li>
 *   <li><b>缺口如实标注。</b>12.7.1.1 账户管理 / 12.7.1.2 银行对账未交付 ⇒
 *       账户级余额与银行流水**不可用**，归属模块写在数据上与能力声明里，
 *       绝不用硬编码常量冒充。</li>
 * </ol>
 */
public final class FundAnalysisModels {

  private FundAnalysisModels() {}

  /** 金额小数位（与 12.1.x / 12.2.x 一致）。 */
  public static final int MONEY_SCALE = 4;

  /** 比例小数位。 */
  public static final int RATIO_SCALE = 6;

  /** 天数小数位。 */
  public static final int DAYS_SCALE = 4;

  // ================================================================ 枚举

  /** 趋势 / 集中度的时间分桶粒度（一律按 UTC 日历日切）。 */
  public enum FinanceFundTrendGranularity {
    DAY("按日"),
    WEEK("按周"),
    MONTH("按月");

    private final String label;

    FinanceFundTrendGranularity(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 流动性预警规则码（与 {@code finance_liquidity_threshold.code} 同一词表）。 */
  public enum FinanceFundLiquidityRule {
    /** 单期计划付款额占窗口总额的比例上限。 */
    PAYMENT_CONCENTRATION("付款集中度"),
    /** 计划流出 ÷ 流入 的上限（大于 1 即为净流出缺口）。 */
    NET_OUTFLOW_GAP("净流出缺口"),
    /** 逾期未核销应收 ÷ 未核销应收 的上限。 */
    RECEIVABLE_OVERDUE("应收逾期"),
    /** 现金可支撑天数（按日均计划流出计）的下限。 */
    CASH_BUFFER_DAYS("现金覆盖天数");

    private final String label;

    FinanceFundLiquidityRule(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 敞口方向。 */
  public enum FinanceFundExposureDirection {
    /** 多外币资产（汇率不利变动 ⇒ 本币价值下降）。 */
    LONG_ASSET("多外币资产"),
    /** 多外币负债（汇率不利变动 ⇒ 本币成本上升）。 */
    SHORT_ASSET("多外币负债"),
    /** 无净敞口。 */
    FLAT("无净敞口");

    private final String label;

    FinanceFundExposureDirection(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 预警严重度。 */
  public enum FinanceFundAlertSeverity {
    INFO("提示"),
    WARN("警告"),
    CRITICAL("严重");

    private final String label;

    FinanceFundAlertSeverity(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  // ================================================================ 能力声明

  /** 能力条目：每一维说清「有没有、依据是什么、缺了会怎样」。 */
  public record FinanceFundAnalysisCapability(
      String module,
      String name,
      boolean available,
      String basis,
      String ownerModule,
      String detail) {}

  /** 下游缺口：谁没交付、归谁。 */
  public record FinanceFundAnalysisDownstreamGap(
      String module,
      String name,
      String ownerService,
      boolean available,
      String group,
      String detail) {}

  /** 12.7.2.1 的能力声明。 */
  public record FinanceFundAnalysisCapabilities(
      List<FinanceFundAnalysisCapability> capabilities,
      List<FinanceFundAnalysisDownstreamGap> downstreamGaps) {}

  // ================================================================ 筛选项

  /**
   * 筛选项下拉：全部来自**真实数据**（distinct 查询），不是写死的词表。
   *
   * @param baseCurrencyCandidates 本位币候选：取自**本租户单据上已记的汇率快照本位币**
   *     （{@code finance_receivable.base_currency} / {@code finance_payable.base_currency}），
   *     真实数据，不是常量。
   */
  public record FinanceFundAnalysisOptions(
      List<String> currencies,
      List<String> baseCurrencyCandidates,
      LocalDate asOfDate,
      String defaultRange,
      String note) {}

  // ================================================================ 现金流入流出趋势

  /**
   * 一个时间桶 × 一个币种。
   *
   * @param actualInflowAmount <b>实际</b>现金流入（已到账收款，按到账时刻分桶）。
   * @param expectedInflowAmount <b>预期</b>现金流入（未核销应收，按到期日分桶）—— 不是已到账的钱。
   * @param plannedOutflowAmount <b>计划</b>现金流出（付款计划，按计划付款日分桶）—— 不是已付的钱。
   * @param netExpectedAmount <b>同口径</b>的预测净额 = 预期流入 − 计划流出。
   *     刻意<b>不</b>给「实际净额」：实际流出当前没有日期维度（见 {@code actualOutflowBasis}），
   *     硬凑一个「实际流入 − 0」叫净额，那是把缺失当零。
   */
  public record FinanceFundFlowBucket(
      LocalDate periodStart,
      String currency,
      BigDecimal actualInflowAmount,
      BigDecimal expectedInflowAmount,
      BigDecimal plannedOutflowAmount,
      BigDecimal netExpectedAmount,
      long actualInflowCount,
      long expectedInflowCount,
      long plannedOutflowCount) {}

  /** 一个币种在整段区间上的合计。 */
  public record FinanceFundFlowCurrencyTotal(
      String currency,
      BigDecimal actualInflowAmount,
      BigDecimal expectedInflowAmount,
      BigDecimal plannedOutflowAmount,
      BigDecimal netExpectedAmount) {}

  /**
   * 现金流入流出趋势报表。
   *
   * @param actualOutflowBasis 实际流出为什么没有时间序列 —— 写在响应里，别让人以为漏算了。
   * @param actualOutflowOwnerModule 实际流出的归属模块。
   */
  public record FinanceFundFlowTrend(
      LocalDate fromDate,
      LocalDate toDate,
      String granularity,
      LocalDate asOfDate,
      String actualOutflowBasis,
      String actualOutflowOwnerModule,
      List<FinanceFundFlowBucket> buckets,
      List<FinanceFundFlowCurrencyTotal> totals) {}

  // ================================================================ 各币种资金分布

  /**
   * 一个币种的资金分布。
   *
   * <p>⚠️ <b>这七个金额是七种不同口径的度量，不是资产负债表</b>：
   * 「累计已到账」是历史累计，「未核销应收 / 未付应付」是当前余额，
   * 「计划流出」是未付应付的**排期**（与未付应付**重叠**，不许相加）。
   */
  public record FinanceFundCurrencyPosition(
      String currency,
      BigDecimal receivedCashAmount,
      BigDecimal unsettledReceivedAmount,
      BigDecimal advanceAmount,
      BigDecimal receivableOpenAmount,
      BigDecimal payableUnpaidAmount,
      BigDecimal paidOutAmount,
      BigDecimal plannedOutflowAmount,
      BigDecimal netSettlementAmount,
      long receivableCount,
      long payableCount) {}

  /** 各币种资金分布报表。 */
  public record FinanceFundCurrencyDistribution(
      LocalDate asOfDate,
      String cashBasisNote,
      boolean accountLevelAvailable,
      String accountOwnerModule,
      List<FinanceFundCurrencyPosition> positions,
      List<String> notes) {}

  // ================================================================ 汇率敞口

  /**
   * 一个币种的汇率敞口。
   *
   * @param exposureShare 该币种敞口占**全部绝对敞口之和**的比例（服务端算好）——
   *     跨境风险集中度：这一格越接近 1，风险越集中在一个币种上。
   */
  public record FinanceFundExposureRow(
      String currency,
      String direction,
      String directionLabel,
      BigDecimal receivableOpenAmount,
      BigDecimal payableOpenAmount,
      BigDecimal netExposureAmount,
      String baseCurrency,
      BigDecimal exchangeRate,
      LocalDate rateEffectiveDate,
      BigDecimal baseExposureAmount,
      BigDecimal sensitivityPer1Pct,
      BigDecimal sensitivityPer5Pct,
      @Schema(nullable = true) BigDecimal exposureShare,
      String rateDisplay) {}

  /** 汇率敞口测算报表。 */
  public record FinanceFundExposureReport(
      LocalDate asOfDate,
      String baseCurrency,
      String baseCurrencyBasis,
      String rateSource,
      String rateSourceModule,
      String inventoryNote,
      String inventoryOwnerModule,
      BigDecimal totalBaseExposureAmount,
      List<FinanceFundExposureRow> rows) {}

  // ================================================================ 敞口快照

  /** 一条敞口快照。 */
  public record FinanceFundExposureSnapshotRow(
      UUID id,
      String snapshotNo,
      LocalDate asOfDate,
      String baseCurrency,
      String currency,
      String direction,
      BigDecimal receivableOpenAmount,
      BigDecimal payableOpenAmount,
      BigDecimal netExposureAmount,
      BigDecimal exchangeRate,
      LocalDate rateEffectiveDate,
      BigDecimal baseExposureAmount,
      BigDecimal sensitivityPer1Pct,
      BigDecimal sensitivityPer5Pct,
      OffsetDateTime createdAt) {}

  /** 敞口快照页（真分页）。 */
  public record FinanceFundExposureSnapshotPage(
      List<FinanceFundExposureSnapshotRow> items, int page, int size, long total) {}

  /**
   * 一次敞口测算的结果：报表 + 落成的快照。
   *
   * @param snapshots 本次落库的快照行（**不含本位币那一行** —— 本位币没有汇率敞口，
   *     快照表的 CHECK 钉死 base_currency ≠ currency）。
   */
  public record FinanceFundExposureMeasureResult(
      FinanceFundExposureReport report, List<FinanceFundExposureSnapshotRow> snapshots) {}

  // ================================================================ 资金周转天数

  /**
   * 一个币种的周转天数。
   *
   * @param dsoDays 应收周转天数 = 期末未核销应收 ÷ 期间实际现金流入 × 期间天数。
   * @param dpoDays 应付周转天数 = 期末未付应付 ÷ 期间应付发生额 × 期间天数。
   * @param fundTurnoverDays 资金周转天数 = DSO − DPO（**不含存货段**）。
   * @param dioDays 存货周转天数 —— 本服务没有存货数据 ⇒ 恒为 {@code null}，**不用 0 冒充**。
   */
  public record FinanceFundTurnoverRow(
      String currency,
      int periodDays,
      BigDecimal receivableOpenAmount,
      BigDecimal actualInflowInPeriod,
      BigDecimal payableUnpaidAmount,
      BigDecimal payableOccurrenceInPeriod,
      @Schema(nullable = true) BigDecimal dsoDays,
      @Schema(nullable = true) BigDecimal dpoDays,
      @Schema(nullable = true) BigDecimal fundTurnoverDays,
      @Schema(nullable = true) BigDecimal dioDays,
      boolean inventoryBasisAvailable,
      String inventoryOwnerModule,
      String payableOccurrenceBasis) {}

  /** 资金周转天数报表。 */
  public record FinanceFundTurnoverReport(
      LocalDate fromDate,
      LocalDate toDate,
      int periodDays,
      String actualOutflowBasis,
      String actualOutflowOwnerModule,
      List<FinanceFundTurnoverRow> rows) {}

  // ================================================================ 付款集中度

  /** 一个时间桶的付款额与占比。 */
  public record FinanceFundConcentrationBucket(
      LocalDate periodStart, BigDecimal amount, @Schema(nullable = true) BigDecimal share) {}

  /** 一个付款对象的计划付款额与占比。 */
  public record FinanceFundCounterpartyShare(
      String counterpartyName, BigDecimal amount, @Schema(nullable = true) BigDecimal share) {}

  /** 一个币种的付款集中度。 */
  public record FinanceFundPaymentConcentration(
      String currency,
      BigDecimal totalPlannedAmount,
      long planCount,
      List<FinanceFundConcentrationBucket> buckets,
      @Schema(nullable = true) BigDecimal topBucketShare,
      @Schema(nullable = true) LocalDate topBucketPeriod,
      List<FinanceFundCounterpartyShare> topCounterparties,
      @Schema(nullable = true) BigDecimal topCounterpartyShare) {}

  /** 付款集中度报表。 */
  public record FinanceFundPaymentConcentrationReport(
      LocalDate fromDate,
      LocalDate toDate,
      String granularity,
      String basis,
      List<FinanceFundPaymentConcentration> rows) {}

  // ================================================================ 流动性预警

  /** 一条阈值配置。 */
  public record FinanceFundLiquidityThreshold(
      String code,
      String label,
      BigDecimal thresholdValue,
      boolean enabled,
      String note) {}

  /**
   * 生效中的阈值配置。
   *
   * @param source {@code TENANT_CONFIG}（租户自己配的）或 {@code BLUEPRINT_DEFAULT}（蓝图默认档）。
   */
  public record FinanceFundLiquidityThresholdConfig(
      String source, boolean configured, List<FinanceFundLiquidityThreshold> thresholds, String detail) {}

  /** 一条阈值配置（输入）。 */
  public record FinanceFundLiquidityThresholdInput(
      String code,
      String label,
      BigDecimal thresholdValue,
      boolean enabled,
      String note) {}

  /**
   * 阈值配置输入（**整体替换**）。
   *
   * <p>⚠️ 整体替换而不是逐条增删改：规则的**组合**才是口径（哪条开着、阈值多少），
   * 逐条写会出现「只配了一半」的中间态，那种状态下预警集合没有确定含义。
   */
  public record FinanceFundLiquidityThresholdConfigInput(
      List<FinanceFundLiquidityThresholdInput> thresholds) {}

  /** 一条流动性预警。 */
  public record FinanceFundLiquidityAlert(
      String rule,
      String ruleLabel,
      String severity,
      String currency,
      String title,
      String detail,
      String basis,
      BigDecimal observedValue,
      BigDecimal thresholdValue,
      LocalDate windowStart,
      LocalDate windowEnd) {}

  /** 流动性预警报表。 */
  public record FinanceFundLiquidityAlertReport(
      LocalDate fromDate,
      LocalDate toDate,
      String thresholdSource,
      int alertCount,
      List<FinanceFundLiquidityAlert> alerts,
      List<FinanceFundLiquidityThreshold> thresholds,
      String actualOutflowBasis,
      String actualOutflowOwnerModule) {}

  // ================================================================ 查询参数

  /**
   * 趋势查询条件。
   *
   * <p>可选属性显式带 {@code | null}：仓库启用了 {@code exactOptionalPropertyTypes}，
   * 少了它的话「省略」与「显式传 null」在类型上不是一回事。
   */
  public record FinanceFundFlowTrendQuery(
      @Schema(nullable = true) String currency,
      @Schema(nullable = true) LocalDate fromDate,
      @Schema(nullable = true) LocalDate toDate,
      @Schema(nullable = true) FinanceFundTrendGranularity granularity) {}

  /** 币种分布查询条件。 */
  public record FinanceFundCurrencyDistributionQuery(@Schema(nullable = true) String currency) {}

  /** 敞口查询条件。 */
  public record FinanceFundExposureQuery(
      @Schema(nullable = true) String baseCurrency, @Schema(nullable = true) String currency) {}

  /** 周转天数查询条件。 */
  public record FinanceFundTurnoverQuery(
      @Schema(nullable = true) String currency,
      @Schema(nullable = true) LocalDate fromDate,
      @Schema(nullable = true) LocalDate toDate) {}

  /** 付款集中度查询条件。 */
  public record FinanceFundConcentrationQuery(
      @Schema(nullable = true) String currency,
      @Schema(nullable = true) LocalDate fromDate,
      @Schema(nullable = true) LocalDate toDate,
      @Schema(nullable = true) FinanceFundTrendGranularity granularity) {}

  /** 流动性预警查询条件。 */
  public record FinanceFundLiquidityAlertQuery(
      @Schema(nullable = true) LocalDate fromDate, @Schema(nullable = true) LocalDate toDate) {}
}
