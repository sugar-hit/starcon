package com.starcon.reporting;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 13.5.2.1 采购分析的响应与请求模型。
 *
 * <p>蓝图 13-profit-and-analytics.md 13.5.2.1 原文：
 *
 * <blockquote>
 *
 * 职责：采购分析：采购金额趋势与占比、供应商集中度、价格波动、到货准时率、采购周期、采购成本占销售额比。
 * 输入：采购数据（6）；供应商评级。
 * 输出：采购报表 + 趋势 + 供应商对比。
 * 关系：上游 = 6 采购与供应；下游 = 13.4 利润。**供应商集中度是供应链风险指标**，
 * 单一供应商占比过高会在断供时直接击穿业务。
 *
 * </blockquote>
 *
 * <p><b>模型纪律</b>（与同卷 13.5.1.1 库存分析完全同构）：
 *
 * <ul>
 *   <li><b>口径写在响应里</b>：每个报表块都带 {@code caliberNote}（口径原文）
 *       + {@code from}/{@code to}（窗口）+ 币种字段。读的人不必猜。</li>
 *   <li><b>算不出来的不是 0</b>：比率类字段可空；{@code null} 的语义是
 *       「这一格算不出来」，配套的 {@code note} 写明原因。</li>
 *   <li><b>金额与数量分列</b>：跨币种绝不按 1:1 相加 —— 每一行带自己的币种，
 *       跨币种的「总计」不存在。</li>
 * </ul>
 *
 * <p><b>命名</b>：每个类型都带 {@code ProcurementAnalysis} 前缀 —— 全仓契约被 codegen
 * 拍平进<b>一个扁平文件</b>，同名 key 后者覆盖前者且不报冲突（trade 已有
 * {@code PurchaseAnalytics*} 这一族，所以本模块刻意避开 {@code Purchase} 前缀）。
 */
public final class ReportingProcurementAnalysisModels {
  private ReportingProcurementAnalysisModels() {}

  // ================================================================ 能力声明

  /**
   * 能力声明：六件指标 + 供应商对比，逐条报「接得通 / 接不通」。
   *
   * @param availableMetrics 接得通的指标键
   * @param sections 逐条明细（接不通的必须给出 {@code ownerModule} 与 {@code reason}）
   */
  @Schema(name = "ProcurementAnalysisCapabilities")
  public record ProcurementAnalysisCapabilities(
      List<String> availableMetrics,
      List<ProcurementAnalysisCapabilitySection> sections,
      String upstreamNote,
      String caliberNote,
      String currencyNote,
      String concentrationNote,
      String cycleNote,
      String dataDependencyNote) {}

  /**
   * 能力声明的一段。
   *
   * @param available 接得通与否
   * @param ownerModule 接不通时，这个能力归哪个模块号（**非空**）
   * @param reason 接不通的原因（**available=false 时非空**）
   * @param impact 接不通对本模块的影响（**available=false 时非空**）
   * @param evidence 证据（上游端点 / 迁移号）
   */
  @Schema(name = "ProcurementAnalysisCapabilitySection")
  public record ProcurementAnalysisCapabilitySection(
      String key,
      String label,
      boolean available,
      @Schema(nullable = true) String ownerModule,
      @Schema(nullable = true) String reason,
      @Schema(nullable = true) String impact,
      String caliber,
      @Schema(nullable = true) String evidence) {}

  // ================================================================ 采购金额趋势与占比

  /**
   * 采购金额趋势（**逐币种分列**）。
   *
   * @param points 趋势上的一个点（期间 × 币种）
   * @param totals 逐币种合计（**跨币种无总计**）
   * @param totalAmount 单币种时的合计；**多币种时为 null**（不是 0，也不是跨币种相加）
   */
  @Schema(name = "ProcurementTrendResponse")
  public record ProcurementTrendResponse(
      LocalDate from,
      LocalDate to,
      String granularity,
      String dimension,
      List<String> currencies,
      List<ProcurementTrendPoint> points,
      List<ProcurementTrendTotal> totals,
      @Schema(nullable = true) BigDecimal totalAmount,
      @Schema(nullable = true) String totalCurrency,
      List<ProcurementTrendShareRow> shares,
      @Schema(nullable = true) OffsetDateTime dataUpdatedAt,
      String dataUpdatedNote,
      String caliberNote,
      List<String> warnings) {}

  /** 趋势上的一个点。 */
  @Schema(name = "ProcurementTrendPoint")
  public record ProcurementTrendPoint(
      String period,
      String currency,
      BigDecimal amount,
      long factCount,
      BigDecimal quantity) {}

  /** 逐币种合计。 */
  @Schema(name = "ProcurementTrendTotal")
  public record ProcurementTrendTotal(String currency, BigDecimal amount, long factCount) {}

  /** 一个拆解维度的占比行（分母 = **同币种**的窗口合计）。 */
  @Schema(name = "ProcurementTrendShareRow")
  public record ProcurementTrendShareRow(
      String dimension,
      String dimensionKey,
      String dimensionLabel,
      String currency,
      BigDecimal amount,
      BigDecimal share,
      long factCount) {}

  // ================================================================ 供应商集中度

  /**
   * 供应商集中度（**逐币种分段** —— 跨币种不合并）。
   *
   * @param riskLevel 风险等级：LOW / MEDIUM / HIGH（**蓝图加粗句的落点**）
   * @param riskNote 人可读的风险说明（写明是哪一家、占比多少、断供会怎样）
   */
  @Schema(name = "ProcurementConcentrationResponse")
  public record ProcurementConcentrationResponse(
      LocalDate from,
      LocalDate to,
      List<ProcurementConcentrationBucket> buckets,
      String riskLevel,
      String riskNote,
      @Schema(nullable = true) String topSupplierName,
      @Schema(nullable = true) BigDecimal topSupplierShare,
      Integer topN,
      BigDecimal topShareThreshold,
      BigDecimal singleSupplierShareThreshold,
      @Schema(nullable = true) OffsetDateTime dataUpdatedAt,
      String dataUpdatedNote,
      String caliberNote,
      List<String> warnings) {}

  /** 一个币种下的集中度。 */
  @Schema(name = "ProcurementConcentrationBucket")
  public record ProcurementConcentrationBucket(
      String currency,
      BigDecimal totalAmount,
      long supplierCount,
      long factCount,
      List<ProcurementConcentrationSupplier> suppliers,
      BigDecimal top1Share,
      BigDecimal topNShare,
      BigDecimal hhi,
      String hhiScale,
      @Schema(nullable = true) String currencyRiskLevel,
      @Schema(nullable = true) String currencyRiskNote) {}

  /** 一个供应商的集中度与贡献。 */
  @Schema(name = "ProcurementConcentrationSupplier")
  public record ProcurementConcentrationSupplier(
      UUID supplierId,
      String supplierCode,
      String supplierName,
      BigDecimal amount,
      BigDecimal share,
      BigDecimal cumulativeShare,
      long factCount,
      BigDecimal quantity,
      @Schema(nullable = true) BigDecimal onTimeRate,
      @Schema(nullable = true) BigDecimal ratingScore,
      @Schema(nullable = true) String ratingGrade,
      @Schema(nullable = true) BigDecimal averageCycleDays) {}

  // ================================================================ 价格波动

  /**
   * 采购价格波动（当前期间 vs 上一等长期间）。
   *
   * @param rows 逐维度（SKU / 供应商）的价格行
   * @param overallChangeRate 整体价格变化率；**任一侧没有可算的均价时为 null**
   */
  @Schema(name = "ProcurementPriceVarianceResponse")
  public record ProcurementPriceVarianceResponse(
      LocalDate from,
      LocalDate to,
      LocalDate previousFrom,
      LocalDate previousTo,
      String dimension,
      List<String> currencies,
      List<ProcurementPriceVarianceRow> rows,
      @Schema(nullable = true) BigDecimal overallChangeRate,
      @Schema(nullable = true) OffsetDateTime dataUpdatedAt,
      String dataUpdatedNote,
      String caliberNote,
      List<String> warnings) {}

  /** 一个 SKU（或供应商）的价格波动行。 */
  @Schema(name = "ProcurementPriceVarianceRow")
  public record ProcurementPriceVarianceRow(
      String dimension,
      String dimensionKey,
      String dimensionLabel,
      String currency,
      @Schema(nullable = true) BigDecimal currentQuantity,
      @Schema(nullable = true) BigDecimal currentAmount,
      @Schema(nullable = true) BigDecimal currentAveragePrice,
      @Schema(nullable = true) BigDecimal previousQuantity,
      @Schema(nullable = true) BigDecimal previousAmount,
      @Schema(nullable = true) BigDecimal previousAveragePrice,
      @Schema(nullable = true) BigDecimal changeRate,
      long factCount,
      String status,
      String note) {}

  // ================================================================ 到货准时率

  /**
   * 到货准时率（**判定归 trade 6.3.2.2**，本模块只搬运）。
   *
   * @param denominatorCount 分母：窗口内**已到货**的流水数（PENDING 未到货不进分母）
   * @param onTimeRate 准时率；分母为 0 ⇒ null（不可比，不是 0%）
   */
  @Schema(name = "ProcurementOnTimeResponse")
  public record ProcurementOnTimeResponse(
      LocalDate from,
      LocalDate to,
      long denominatorCount,
      long onTimeCount,
      long lateCount,
      long pendingCount,
      @Schema(nullable = true) BigDecimal onTimeRate,
      @Schema(nullable = true) BigDecimal targetRate,
      Boolean meetsTarget,
      List<ProcurementOnTimeSupplierRow> suppliers,
      List<ProcurementOnTimeLateRow> lateRows,
      @Schema(nullable = true) OffsetDateTime dataUpdatedAt,
      String dataUpdatedNote,
      String caliberNote,
      List<String> warnings) {}

  /** 逐供应商的准时率。 */
  @Schema(name = "ProcurementOnTimeSupplierRow")
  public record ProcurementOnTimeSupplierRow(
      UUID supplierId,
      String supplierCode,
      String supplierName,
      long denominatorCount,
      long onTimeCount,
      long lateCount,
      @Schema(nullable = true) BigDecimal onTimeRate,
      @Schema(nullable = true) BigDecimal ratingScore,
      @Schema(nullable = true) String ratingGrade) {}

  /** 一条逾期待到货的流水（可下钻到具体单据）。 */
  @Schema(name = "ProcurementOnTimeLateRow")
  public record ProcurementOnTimeLateRow(
      UUID factId,
      String sourceDocNo,
      String sourceRefNo,
      String supplierName,
      String skuCode,
      LocalDate promisedArrivalDate,
      OffsetDateTime occurredAt,
      BigDecimal quantity,
      @Schema(nullable = true) BigDecimal amount,
      @Schema(nullable = true) String currency,
      Integer lateDays) {}

  // ================================================================ 采购周期

  /**
   * 采购周期（到货日 − 下单日）。
   *
   * @param averageCycleDays 平均周期（天）；没有到货记录 ⇒ null（**不是 0 天**）
   * @param medianCycleDays 中位周期（天）
   * @param p90CycleDays P90 周期（天）
   */
  @Schema(name = "ProcurementCycleResponse")
  public record ProcurementCycleResponse(
      LocalDate from,
      LocalDate to,
      @Schema(nullable = true) BigDecimal averageCycleDays,
      @Schema(nullable = true) BigDecimal medianCycleDays,
      @Schema(nullable = true) BigDecimal p90CycleDays,
      long sampleCount,
      @Schema(nullable = true) BigDecimal targetDays,
      Boolean meetsTarget,
      List<ProcurementCycleSupplierRow> suppliers,
      @Schema(nullable = true) OffsetDateTime dataUpdatedAt,
      String dataUpdatedNote,
      String caliberNote,
      List<String> warnings) {}

  /** 逐供应商的采购周期。 */
  @Schema(name = "ProcurementCycleSupplierRow")
  public record ProcurementCycleSupplierRow(
      UUID supplierId,
      String supplierCode,
      String supplierName,
      long sampleCount,
      @Schema(nullable = true) BigDecimal averageCycleDays,
      @Schema(nullable = true) BigDecimal medianCycleDays,
      @Schema(nullable = true) BigDecimal p90CycleDays,
      @Schema(nullable = true) BigDecimal latestCycleDays,
      @Schema(nullable = true) LocalDate latestReceivedDate) {}

  // ================================================================ 采购成本占销售额比

  /**
   * 采购成本占销售额比（**逐币种**）。
   *
   * @param available 分母（销售额）是否可得；false ⇒ {@code ownerModule} / {@code impact} 必填
   * @param rows 逐币种的比率行
   */
  @Schema(name = "ProcurementCostToSalesResponse")
  public record ProcurementCostToSalesResponse(
      LocalDate from,
      LocalDate to,
      boolean available,
      @Schema(nullable = true) String ownerModule,
      @Schema(nullable = true) String impact,
      List<ProcurementCostToSalesRow> rows,
      @Schema(nullable = true) OffsetDateTime dataUpdatedAt,
      String dataUpdatedNote,
      String caliberNote,
      List<String> warnings) {}

  /** 一个币种下的成本占销售额比。 */
  @Schema(name = "ProcurementCostToSalesRow")
  public record ProcurementCostToSalesRow(
      String currency,
      BigDecimal purchaseAmount,
      @Schema(nullable = true) BigDecimal salesAmount,
      @Schema(nullable = true) BigDecimal ratio,
      long purchaseFactCount,
      long salesFactCount,
      String note) {}

  // ================================================================ 供应商对比

  /**
   * 供应商对比（真分页 + 真计数）。
   *
   * @param rows 每个供应商一行（采购贡献 + 准时率 + 采购周期 + 评级）
   */
  @Schema(name = "ProcurementSupplierComparisonResponse")
  public record ProcurementSupplierComparisonResponse(
      LocalDate from,
      LocalDate to,
      List<ProcurementSupplierRow> rows,
      long total,
      int page,
      int size,
      @Schema(nullable = true) OffsetDateTime dataUpdatedAt,
      String dataUpdatedNote,
      String caliberNote,
      List<String> warnings) {}

  /** 供应商对比的一行。 */
  @Schema(name = "ProcurementSupplierRow")
  public record ProcurementSupplierRow(
      UUID supplierId,
      String supplierCode,
      String supplierName,
      String partyStatus,
      String cooperationLevel,
      String supplyStatus,
      @Schema(nullable = true) String countryCode,
      @Schema(nullable = true) BigDecimal purchaseAmount,
      @Schema(nullable = true) String purchaseCurrency,
      @Schema(nullable = true) BigDecimal purchaseShare,
      long purchaseFactCount,
      @Schema(nullable = true) BigDecimal onTimeRate,
      @Schema(nullable = true) BigDecimal averageCycleDays,
      @Schema(nullable = true) BigDecimal ratingScore,
      @Schema(nullable = true) String ratingGrade,
      @Schema(nullable = true) LocalDate ratingPeriodStart,
      @Schema(nullable = true) LocalDate ratingPeriodEnd,
      String note) {}

  // ================================================================ 下钻

  /**
   * 下钻到到货流水（真分页 + 真计数）。
   *
   * @param rows 一行 = 一次到货流水
   */
  @Schema(name = "ProcurementDrilldownResponse")
  public record ProcurementDrilldownResponse(
      LocalDate from,
      LocalDate to,
      List<ProcurementDrilldownRow> rows,
      long total,
      int page,
      int size,
      @Schema(nullable = true) OffsetDateTime dataUpdatedAt,
      String dataUpdatedNote,
      String caliberNote,
      List<String> warnings) {}

  /** 下钻的一行（一次到货流水）。 */
  @Schema(name = "ProcurementDrilldownRow")
  public record ProcurementDrilldownRow(
      UUID factId,
      String sourceDocNo,
      String sourceRefNo,
      LocalDate occurredOn,
      OffsetDateTime occurredAt,
      @Schema(nullable = true) UUID supplierId,
      String supplierCode,
      String supplierName,
      String skuCode,
      String skuName,
      String categoryCode,
      String shopCode,
      BigDecimal quantity,
      @Schema(nullable = true) BigDecimal unitPrice,
      @Schema(nullable = true) BigDecimal amount,
      @Schema(nullable = true) String currency,
      @Schema(nullable = true) LocalDate promisedArrivalDate,
      String onTimeStatus,
      String note) {}

  // ================================================================ 阈值规则

  /** 一条阈值规则。 */
  @Schema(name = "ProcurementAnalysisRule")
  public record ProcurementAnalysisRule(
      String ruleType,
      @Schema(nullable = true) Integer concentrationTopN,
      @Schema(nullable = true) BigDecimal concentrationTopSharePct,
      @Schema(nullable = true) BigDecimal singleSupplierSharePct,
      @Schema(nullable = true) BigDecimal onTimeTargetPct,
      @Schema(nullable = true) BigDecimal purchaseCycleTargetDays,
      int minHistoryDays,
      boolean enabled,
      OffsetDateTime updatedAt) {}

  /** 改阈值的入参（只改写进来的那几个字段）。 */
  @Schema(name = "ProcurementAnalysisRuleInput")
  public record ProcurementAnalysisRuleInput(
      @Schema(nullable = true) Integer concentrationTopN,
      @Schema(nullable = true) BigDecimal concentrationTopSharePct,
      @Schema(nullable = true) BigDecimal singleSupplierSharePct,
      @Schema(nullable = true) BigDecimal onTimeTargetPct,
      @Schema(nullable = true) BigDecimal purchaseCycleTargetDays,
      @Schema(nullable = true) Integer minHistoryDays,
      @Schema(nullable = true) Boolean enabled) {}

  // ================================================================ 投影摄取

  /** 刷新结果。 */
  @Schema(name = "ProcurementAnalysisRefreshResult")
  public record ProcurementAnalysisRefreshResult(
      List<ProcurementAnalysisRefreshSummary> summaries,
      int totalWritten,
      boolean truncated,
      String note) {}

  /** 一路上游的摄取小结。 */
  @Schema(name = "ProcurementAnalysisRefreshSummary")
  public record ProcurementAnalysisRefreshSummary(
      String source,
      String label,
      int factCount,
      int written,
      String status,
      String note) {}
}
