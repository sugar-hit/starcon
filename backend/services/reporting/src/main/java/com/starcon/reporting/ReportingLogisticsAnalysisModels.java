package com.starcon.reporting;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 13.5.3.1 物流分析的响应与请求模型。
 *
 * <p>蓝图 13-profit-and-analytics.md 13.5.3.1 原文：
 *
 * <blockquote>
 *
 * 职责：物流分析：单均运费、头程分摊后成本、渠道对比、时效达成率、各国成本对比、异常件占比。
 * 输入：物流数据（10）；成本数据。
 * 输出：物流报表 + 渠道对比。
 * 关系：上游 = 10 物流与头程；下游 = 5 定价（运费测算校准）、13.4 利润。
 * **物流成本占比高的类目必须单独定价**，统一毛利率会吃掉利润。
 *
 * </blockquote>
 *
 * <p><b>模型纪律</b>（与同卷 13.5.1.1 / 13.5.2.1 完全同构）：
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
 * <p><b>命名</b>：每个类型都带 {@code LogisticsAnalysis} 前缀 —— 全仓契约被 codegen
 * 拍平进<b>一个扁平文件</b>，同名 key 后者覆盖前者且不报冲突。
 */
public final class ReportingLogisticsAnalysisModels {
  private ReportingLogisticsAnalysisModels() {}

  // ================================================================ 能力声明

  /**
   * 能力声明：六件指标逐条报「接得通 / 接不通」。
   *
   * @param availableMetrics 接得通的指标键
   * @param sections 逐条明细（接不通的必须给出 {@code ownerModule} 与 {@code reason}）
   */
  @Schema(name = "LogisticsAnalysisCapabilities")
  public record LogisticsAnalysisCapabilities(
      List<String> availableMetrics,
      List<LogisticsAnalysisCapabilitySection> sections,
      String upstreamNote,
      String caliberNote,
      String currencyNote,
      String orderScopeNote,
      String allocationNote,
      String categoryNote,
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
  @Schema(name = "LogisticsAnalysisCapabilitySection")
  public record LogisticsAnalysisCapabilitySection(
      String key,
      String label,
      boolean available,
      @Schema(nullable = true) String ownerModule,
      @Schema(nullable = true) String reason,
      @Schema(nullable = true) String impact,
      String caliber,
      @Schema(nullable = true) String evidence) {}

  // ================================================================ 成本总览

  /**
   * 物流成本总览（**逐币种**）。
   *
   * @param rows 逐币种的成本行
   * @param coverageComplete 上游的五个分量是否都取到了数
   * @param missingComponents 没取到数的分量（**不等于 0**）
   */
  @Schema(name = "LogisticsCostOverviewResponse")
  public record LogisticsCostOverviewResponse(
      LocalDate from,
      LocalDate to,
      @Schema(nullable = true) LocalDate snapshotAsOf,
      List<LogisticsCostCurrencyRow> rows,
      List<String> currencies,
      boolean coverageComplete,
      List<String> missingComponents,
      long scannedWaybills,
      boolean truncated,
      @Schema(nullable = true) OffsetDateTime dataUpdatedAt,
      String dataUpdatedNote,
      String caliberNote,
      List<String> warnings) {}

  /** 一个币种下的成本构成。 */
  @Schema(name = "LogisticsCostCurrencyRow")
  public record LogisticsCostCurrencyRow(
      String currency,
      @Schema(nullable = true) BigDecimal lastMileFreight,
      @Schema(nullable = true) BigDecimal firstMileAllocation,
      @Schema(nullable = true) BigDecimal dutyAndTax,
      @Schema(nullable = true) BigDecimal packaging,
      @Schema(nullable = true) BigDecimal subtotal,
      @Schema(nullable = true) BigDecimal lastMileShare,
      @Schema(nullable = true) BigDecimal firstMileShare,
      @Schema(nullable = true) BigDecimal dutyAndTaxShare,
      @Schema(nullable = true) BigDecimal packagingShare,
      long registeredWaybills,
      long notRegisteredWaybills,
      @Schema(nullable = true) BigDecimal totalWeightKg,
      @Schema(nullable = true) BigDecimal upstreamPerWaybillFreight,
      @Schema(nullable = true) BigDecimal upstreamPerKgFreight,
      List<String> includedComponents,
      List<String> missingComponents,
      String note) {}

  // ================================================================ 单均运费

  /**
   * 单均运费（**逐币种**）。
   *
   * @param orderCount 分母：**产生过运单的订单数**（与分子同范围）
   * @param perOrder 单均运费；分母为 0 ⇒ null（**不可比，不是 0**）
   * @param upstreamPerWaybillFreight 上游的「每票运费」（**分母是票不是单**，并排对照）
   */
  @Schema(name = "LogisticsFreightPerOrderResponse")
  public record LogisticsFreightPerOrderResponse(
      LocalDate from,
      LocalDate to,
      List<LogisticsFreightPerOrderRow> rows,
      List<String> currencies,
      long orderCount,
      long orderCountAll,
      long waybillCount,
      long registeredWaybillCount,
      long notRegisteredWaybillCount,
      String orderScope,
      @Schema(nullable = true) BigDecimal targetPerOrder,
      @Schema(nullable = true) String targetCurrency,
      Boolean meetsTarget,
      @Schema(nullable = true) OffsetDateTime dataUpdatedAt,
      String dataUpdatedNote,
      String caliberNote,
      List<String> warnings) {}

  /** 一个币种下的单均运费。 */
  @Schema(name = "LogisticsFreightPerOrderRow")
  public record LogisticsFreightPerOrderRow(
      String currency,
      @Schema(nullable = true) BigDecimal freightAmount,
      long orderCount,
      long orderCountAll,
      long waybillCount,
      long registeredWaybillCount,
      long notRegisteredWaybillCount,
      @Schema(nullable = true) BigDecimal perOrder,
      @Schema(nullable = true) BigDecimal perWaybill,
      @Schema(nullable = true) BigDecimal perKg,
      @Schema(nullable = true) BigDecimal totalWeightKg,
      String note) {}

  // ================================================================ 头程分摊后成本

  /**
   * 头程分摊后成本（**逐币种** + 分摊单清单）。
   *
   * @param rows 逐币种的分摊后成本
   */
  @Schema(name = "LogisticsHeadMileResponse")
  public record LogisticsHeadMileResponse(
      LocalDate from,
      LocalDate to,
      List<LogisticsHeadMileCurrencyRow> rows,
      List<LogisticsAllocationRow> allocations,
      long allocationCount,
      long lockedCount,
      long supersededCount,
      @Schema(nullable = true) OffsetDateTime dataUpdatedAt,
      String dataUpdatedNote,
      String caliberNote,
      List<String> warnings) {}

  /** 一个币种下的头程分摊后成本。 */
  @Schema(name = "LogisticsHeadMileCurrencyRow")
  public record LogisticsHeadMileCurrencyRow(
      String currency,
      @Schema(nullable = true) BigDecimal allocatedAmount,
      @Schema(nullable = true) BigDecimal sourceTotalAmount,
      long allocationCount,
      long lineCount,
      @Schema(nullable = true) BigDecimal upstreamOverviewAmount,
      @Schema(nullable = true) BigDecimal differenceToOverview,
      String note) {}

  /** 一张头程分摊单。 */
  @Schema(name = "LogisticsAllocationRow")
  public record LogisticsAllocationRow(
      UUID allocationId,
      String allocationCode,
      String ruleCode,
      int ruleVersion,
      String allocationBasis,
      String allocationBasisLabel,
      String sourceShipmentCode,
      String sourceCarrierName,
      String destinationCountry,
      String currency,
      BigDecimal sourceTotalAmount,
      BigDecimal allocatedTotalAmount,
      int lineCount,
      String status,
      String statusLabel,
      @Schema(nullable = true) OffsetDateTime computedAt,
      @Schema(nullable = true) OffsetDateTime costAsOf,
      @Schema(nullable = true) OffsetDateTime lockedAt,
      String note) {}

  // ================================================================ 渠道对比

  /**
   * 渠道对比（真分页 + 真计数）。
   *
   * @param rows 每个（承运商 × 渠道 × 币种）一行
   */
  @Schema(name = "LogisticsChannelComparisonResponse")
  public record LogisticsChannelComparisonResponse(
      LocalDate from,
      LocalDate to,
      @Schema(nullable = true) LocalDate snapshotAsOf,
      List<LogisticsChannelComparisonRow> rows,
      long total,
      int page,
      int size,
      long carrierCount,
      long waybills,
      long freightRegistered,
      long freightNotRegistered,
      @Schema(nullable = true) OffsetDateTime dataUpdatedAt,
      String dataUpdatedNote,
      String caliberNote,
      List<String> warnings) {}

  /** 渠道对比的一行。 */
  @Schema(name = "LogisticsChannelComparisonRow")
  public record LogisticsChannelComparisonRow(
      String carrierCode,
      String carrierChannel,
      String carrierChannelLabel,
      String currency,
      BigDecimal amount,
      long waybills,
      @Schema(nullable = true) BigDecimal totalWeightKg,
      @Schema(nullable = true) BigDecimal upstreamPerWaybillFreight,
      @Schema(nullable = true) BigDecimal shareOfCurrency,
      String note) {}

  // ================================================================ 时效达成率

  /**
   * 时效达成率（**判定归 channel 10.6.2.1**，本模块只搬运）。
   *
   * @param onTimeRate 达成率；分母（可比数）为 0 ⇒ null（**不可比，不是 0%**）
   */
  @Schema(name = "LogisticsTimelinessResponse")
  public record LogisticsTimelinessResponse(
      LocalDate from,
      LocalDate to,
      @Schema(nullable = true) LocalDate snapshotAsOf,
      List<LogisticsTimelinessCell> cells,
      @Schema(nullable = true) BigDecimal overallOnTimeRate,
      long comparableCount,
      long onTimeCount,
      long overdueCount,
      long shippedCount,
      long deliveredCount,
      long noPromiseCount,
      long pendingCount,
      long returnedCount,
      @Schema(nullable = true) BigDecimal overallDeliveryRate,
      @Schema(nullable = true) BigDecimal overallOverdueRate,
      @Schema(nullable = true) BigDecimal avgTransitHours,
      @Schema(nullable = true) BigDecimal targetRate,
      Boolean meetsTarget,
      List<LogisticsTimelinessCountryRow> byCountry,
      @Schema(nullable = true) OffsetDateTime dataUpdatedAt,
      String dataUpdatedNote,
      String caliberNote,
      List<String> warnings) {}

  /** 一格 = 一个渠道 × 一个国家。 */
  @Schema(name = "LogisticsTimelinessCell")
  public record LogisticsTimelinessCell(
      @Schema(nullable = true) UUID channelId,
      String channelCode,
      String channelName,
      String countryCode,
      long shippedCount,
      long deliveredCount,
      long comparableCount,
      long onTimeCount,
      long overdueCount,
      long noPromiseCount,
      long pendingCount,
      long returnedCount,
      @Schema(nullable = true) BigDecimal onTimeRate,
      @Schema(nullable = true) BigDecimal deliveryRate,
      @Schema(nullable = true) BigDecimal overdueRate,
      @Schema(nullable = true) BigDecimal avgTransitHours,
      @Schema(nullable = true) BigDecimal avgDeviationHours,
      @Schema(nullable = true) BigDecimal score,
      @Schema(nullable = true) String scoreGrade,
      boolean sampleSufficient,
      String note) {}

  /** 按国家的汇总（一国一行，**达成率按可比数汇总后再相除**，不是各格比率的平均）。 */
  @Schema(name = "LogisticsTimelinessCountryRow")
  public record LogisticsTimelinessCountryRow(
      String countryCode,
      long shippedCount,
      long comparableCount,
      long onTimeCount,
      long overdueCount,
      @Schema(nullable = true) BigDecimal onTimeRate,
      @Schema(nullable = true) BigDecimal overdueRate,
      @Schema(nullable = true) BigDecimal avgTransitHours) {}

  // ================================================================ 各国成本对比

  /**
   * 各国成本对比（**逐币种**）。
   *
   * @param rows 逐（币种 × 国家）一行
   */
  @Schema(name = "LogisticsCountryCostResponse")
  public record LogisticsCountryCostResponse(
      LocalDate from,
      LocalDate to,
      List<LogisticsCountryCostRow> rows,
      List<String> currencies,
      long waybillCount,
      long orderCount,
      long countryCount,
      long missingCountryCount,
      @Schema(nullable = true) OffsetDateTime dataUpdatedAt,
      String dataUpdatedNote,
      String caliberNote,
      List<String> warnings) {}

  /** 一个（币种 × 国家）的成本行。 */
  @Schema(name = "LogisticsCountryCostRow")
  public record LogisticsCountryCostRow(
      String currency,
      String countryCode,
      @Schema(nullable = true) BigDecimal amount,
      long waybills,
      long orderCount,
      @Schema(nullable = true) BigDecimal perWaybill,
      @Schema(nullable = true) BigDecimal perOrder,
      @Schema(nullable = true) BigDecimal shareOfCurrency,
      @Schema(nullable = true) BigDecimal totalWeightKg,
      @Schema(nullable = true) BigDecimal perKg,
      String note) {}

  // ================================================================ 异常件占比

  /**
   * 异常件占比（**分子分母同范围**：窗口内异常件 ÷ 窗口内运单）。
   *
   * @param exceptionShare 异常件占比；分母为 0 ⇒ null（**不可比，不是 0%**）
   */
  @Schema(name = "LogisticsExceptionShareResponse")
  public record LogisticsExceptionShareResponse(
      LocalDate from,
      LocalDate to,
      long exceptionCount,
      long waybillCount,
      @Schema(nullable = true) BigDecimal exceptionShare,
      @Schema(nullable = true) BigDecimal targetShare,
      Boolean meetsTarget,
      List<LogisticsExceptionGroupRow> byType,
      List<LogisticsExceptionGroupRow> bySeverity,
      List<LogisticsExceptionGroupRow> byStatus,
      List<LogisticsExceptionRow> topCases,
      @Schema(nullable = true) OffsetDateTime dataUpdatedAt,
      String dataUpdatedNote,
      String caliberNote,
      List<String> warnings) {}

  /** 一个分组（按类型 / 严重度 / 状态）。 */
  @Schema(name = "LogisticsExceptionGroupRow")
  public record LogisticsExceptionGroupRow(
      String dimension,
      String dimensionKey,
      String dimensionLabel,
      long count,
      @Schema(nullable = true) BigDecimal shareOfExceptions,
      String note) {}

  /** 一个异常件（下钻用）。 */
  @Schema(name = "LogisticsExceptionRow")
  public record LogisticsExceptionRow(
      UUID caseId,
      String caseNo,
      String exceptionType,
      String exceptionTypeLabel,
      String severity,
      String severityLabel,
      String status,
      String statusLabel,
      String waybillNo,
      String carrierCode,
      String salesOrderNo,
      LocalDate detectedOn,
      OffsetDateTime detectedAt,
      boolean overdue,
      String note) {}

  // ================================================================ 类目维度物流成本占比

  /**
   * 类目维度的物流成本占销售额比（**下游 5 定价的输入**）。
   *
   * @param rows 逐（币种 × 类目）一行
   */
  @Schema(name = "LogisticsCategoryCostResponse")
  public record LogisticsCategoryCostResponse(
      LocalDate from,
      LocalDate to,
      List<LogisticsCategoryCostRow> rows,
      List<String> currencies,
      long categoryCount,
      long skuCount,
      long unattributedLineCount,
      @Schema(nullable = true) BigDecimal unattributedAmount,
      @Schema(nullable = true) Long revenueMissingSkuCount,
      @Schema(nullable = true) BigDecimal targetRatio,
      @Schema(nullable = true) OffsetDateTime dataUpdatedAt,
      String dataUpdatedNote,
      String caliberNote,
      List<String> warnings) {}

  /** 一个（币种 × 类目）的物流成本占比行。 */
  @Schema(name = "LogisticsCategoryCostRow")
  public record LogisticsCategoryCostRow(
      String currency,
      String categoryCode,
      @Schema(nullable = true) BigDecimal logisticsCost,
      @Schema(nullable = true) BigDecimal revenue,
      @Schema(nullable = true) BigDecimal ratio,
      long skuCount,
      long lineCount,
      @Schema(nullable = true) Long skuCountWithCost,
      @Schema(nullable = true) Long skuCountWithRevenue,
      Boolean exceedsTarget,
      String note) {}

  // ================================================================ 下钻

  /**
   * 运单级下钻（真分页 + 真计数）。
   *
   * @param rows 一行 = 一张运单
   */
  @Schema(name = "LogisticsDrilldownResponse")
  public record LogisticsDrilldownResponse(
      LocalDate from,
      LocalDate to,
      List<LogisticsDrilldownRow> rows,
      long total,
      int page,
      int size,
      @Schema(nullable = true) OffsetDateTime dataUpdatedAt,
      String dataUpdatedNote,
      String caliberNote,
      List<String> warnings) {}

  /** 运单下钻的一行。 */
  @Schema(name = "LogisticsDrilldownRow")
  public record LogisticsDrilldownRow(
      UUID waybillId,
      String waybillNo,
      String salesOrderNo,
      LocalDate occurredOn,
      OffsetDateTime occurredAt,
      String carrierCode,
      String carrierChannel,
      String destinationCountry,
      @Schema(nullable = true) BigDecimal grossWeightKg,
      @Schema(nullable = true) BigDecimal freightAmount,
      @Schema(nullable = true) String freightCurrency,
      String freightGapCode,
      String note) {}

  // ================================================================ 阈值规则

  /** 一条阈值规则。 */
  @Schema(name = "LogisticsAnalysisRule")
  public record LogisticsAnalysisRule(
      String ruleType,
      @Schema(nullable = true) BigDecimal onTimeTargetPct,
      @Schema(nullable = true) BigDecimal freightPerOrderTarget,
      @Schema(nullable = true) String freightPerOrderCurrency,
      @Schema(nullable = true) BigDecimal exceptionShareTargetPct,
      @Schema(nullable = true) BigDecimal costToSalesTargetPct,
      int minHistoryDays,
      boolean enabled,
      OffsetDateTime updatedAt) {}

  /** 改阈值的入参（只改写进来的那几个字段）。 */
  @Schema(name = "LogisticsAnalysisRuleInput")
  public record LogisticsAnalysisRuleInput(
      @Schema(nullable = true) BigDecimal onTimeTargetPct,
      @Schema(nullable = true) BigDecimal freightPerOrderTarget,
      @Schema(nullable = true) String freightPerOrderCurrency,
      @Schema(nullable = true) BigDecimal exceptionShareTargetPct,
      @Schema(nullable = true) BigDecimal costToSalesTargetPct,
      @Schema(nullable = true) Integer minHistoryDays,
      @Schema(nullable = true) Boolean enabled) {}

  // ================================================================ 投影摄取

  /** 刷新结果。 */
  @Schema(name = "LogisticsAnalysisRefreshResult")
  public record LogisticsAnalysisRefreshResult(
      List<LogisticsAnalysisRefreshSummary> summaries,
      int totalWritten,
      boolean truncated,
      String note) {}

  /** 一路上游的摄取小结。 */
  @Schema(name = "LogisticsAnalysisRefreshSummary")
  public record LogisticsAnalysisRefreshSummary(
      String source,
      String label,
      int factCount,
      int written,
      String status,
      String note) {}
}
