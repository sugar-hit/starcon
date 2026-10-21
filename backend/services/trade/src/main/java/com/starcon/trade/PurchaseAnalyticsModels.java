package com.starcon.trade;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 6.5.2.1 采购分析的响应与请求模型。
 *
 * <p>蓝图 06-purchase-and-supply.md 6.5.2.1 原文：
 *
 * <blockquote>
 *
 * 职责：采购侧报表：采购金额趋势、供应商集中度与贡献、采购价格波动、到货准时率、
 * 采购成本占销售额比、按类目/店铺维度的采购效率。
 * 输入：采购单与收货数据；时间范围与维度筛选。
 * 输出：报表图表 + 可导出明细；下钻到具体采购单。
 * 关系：上游 = 6.3、6.4；下游 = 13 利润与经营分析。
 * </blockquote>
 *
 * <p><b>模型纪律</b>：
 *
 * <ul>
 *   <li><b>口径写在响应里</b>：每一个报表块都带 {@code basis}（口径原文）+
 *       {@code windowStart}/{@code windowEnd}（窗口）+ 币种字段。
 *       读的人不必猜「集中度是 top-N 还是 HHI」「准时率的分母是承诺行还是收货行」。</li>
 *   <li><b>算不出来的不是 0</b>：比率类指标带 {@code available}；
 *       {@code available=false} 时 {@code ownerModule} 与 {@code impact} 必填。</li>
 *   <li><b>金额与数量分列</b>：跨币种绝不按 1:1 相加 —— 报表按币种分列，
 *       折算到本位币的行带汇率快照，未折算的行单列一个桶。</li>
 * </ul>
 */
public final class PurchaseAnalyticsModels {

  private PurchaseAnalyticsModels() {}

  // ================================================================ 能力声明

  /** 能力声明。 */
  @Schema(name = "PurchaseAnalyticsCapabilities")
  public record PurchaseAnalyticsCapabilities(
      List<PurchaseAnalyticsCapabilitySection> sections,
      String moduleNote,
      String basisNote,
      String currencyNote,
      String drillDownNote,
      String exportNote,
      String syncNote) {}

  /** 能力声明的一段。{@code available=false} 时 {@code ownerModule} 与 {@code impact} 必填。 */
  @Schema(name = "PurchaseAnalyticsCapabilitySection")
  public record PurchaseAnalyticsCapabilitySection(
      String key,
      String label,
      boolean available,
      @Schema(nullable = true, description = "不可用时这个能力归属哪个模块号") String ownerModule,
      @Schema(nullable = true, description = "不可用的原因（available=false 时非空）")
          String blockedReason,
      @Schema(nullable = true, description = "不可用对本模块的影响（available=false 时非空）")
          String impact,
      String detail,
      @Schema(nullable = true) String evidence) {}

  // ================================================================ 参照下拉

  /** 筛选下拉项（全部来自**已物化的真实事实**，不是写死的清单）。 */
  @Schema(name = "PurchaseAnalyticsOptions")
  public record PurchaseAnalyticsOptions(
      List<PurchaseAnalyticsOptionItem> suppliers,
      List<PurchaseAnalyticsOptionItem> currencies,
      List<PurchaseAnalyticsOptionItem> categories,
      List<PurchaseAnalyticsOptionItem> shops,
      List<PurchaseAnalyticsOptionItem> sourceTypes,
      List<String> factKinds,
      List<String> onTimeStatuses,
      @Schema(nullable = true) String lastSyncNote,
      @Schema(nullable = true) String note) {}

  /** 一个下拉项。 */
  @Schema(name = "PurchaseAnalyticsOptionItem")
  public record PurchaseAnalyticsOptionItem(String value, String label, long factCount) {}

  // ================================================================ 事实台账

  /** 事实台账的一行（也是**下钻到单据**的最小单位）。 */
  @Schema(name = "PurchaseAnalyticsFactRow")
  public record PurchaseAnalyticsFactRow(
      UUID id,
      String factKey,
      String factKind,
      String sourceType,
      String sourceDocNo,
      @Schema(nullable = true) UUID sourceDocId,
      String sourceRefNo,
      OffsetDateTime occurredAt,
      LocalDate occurredDate,
      @Schema(nullable = true) UUID supplierRefId,
      String supplierCode,
      String supplierName,
      @Schema(nullable = true) UUID skuRefId,
      String skuCode,
      String skuName,
      String categoryCode,
      String categoryName,
      String shopCode,
      String warehouseCode,
      BigDecimal quantity,
      String unitCode,
      @Schema(nullable = true) BigDecimal unitFactor,
      @Schema(nullable = true) BigDecimal baseQuantity,
      @Schema(nullable = true) BigDecimal unitPrice,
      @Schema(nullable = true) BigDecimal amount,
      @Schema(nullable = true) String currency,
      @Schema(nullable = true) String baseCurrency,
      @Schema(nullable = true) BigDecimal fxRate,
      @Schema(nullable = true, description = "本位币金额；汇率未登记时为空（不是 0，也不按 1:1）")
          BigDecimal baseAmount,
      String fxStatus,
      @Schema(nullable = true) UUID fxRateId,
      @Schema(nullable = true) LocalDate fxRateEffectiveDate,
      @Schema(nullable = true) LocalDate promisedArrivalDate,
      String onTimeStatus,
      String note) {}

  /** 事实台账分页。 */
  @Schema(name = "PurchaseAnalyticsFactPage")
  public record PurchaseAnalyticsFactPage(
      List<PurchaseAnalyticsFactRow> items, long total, int page, int size, String note) {}

  /** 事实详情：这一行 + 它所属的口径块 + 下钻去处。 */
  @Schema(name = "PurchaseAnalyticsFactDetail")
  public record PurchaseAnalyticsFactDetail(
      PurchaseAnalyticsFactRow fact,
      String sourceModule,
      String sourceDescription,
      @Schema(nullable = true) String drillDownNote,
      String note) {}

  // ================================================================ 同步

  /** 同步请求（把已交付的上游事实物化进本模块台账）。 */
  @Schema(name = "PurchaseAnalyticsSyncRequest")
  public record PurchaseAnalyticsSyncRequest(
      @Schema(nullable = true, description = "ALL 或某个来源；空 = ALL")
          String sourceType,
      @Schema(nullable = true, description = "窗口起（含）。空 = 最近 90 天") LocalDate from,
      @Schema(nullable = true, description = "窗口止（含）。空 = 今天") LocalDate to) {}

  /** 同步结果。 */
  @Schema(name = "PurchaseAnalyticsSyncResult")
  public record PurchaseAnalyticsSyncResult(
      String runNo,
      String sourceType,
      LocalDate windowStart,
      LocalDate windowEnd,
      String status,
      int scannedCount,
      int createdCount,
      int updatedCount,
      int skippedCount,
      int unresolvedSkuCount,
      int unconvertedCount,
      List<String> notes,
      String message) {}

  /** 同步批次留痕的一行。 */
  @Schema(name = "PurchaseAnalyticsSyncRunRow")
  public record PurchaseAnalyticsSyncRunRow(
      UUID id,
      String runNo,
      String sourceType,
      LocalDate windowStart,
      LocalDate windowEnd,
      String status,
      int scannedCount,
      int createdCount,
      int updatedCount,
      int skippedCount,
      int unresolvedSkuCount,
      int unconvertedCount,
      String message,
      OffsetDateTime startedAt,
      @Schema(nullable = true) OffsetDateTime finishedAt) {}

  /** 同步批次留痕分页。 */
  @Schema(name = "PurchaseAnalyticsSyncRunPage")
  public record PurchaseAnalyticsSyncRunPage(
      List<PurchaseAnalyticsSyncRunRow> items, long total, int page, int size, String note) {}

  // ================================================================ 报表

  /** 通用窗口头：每个报表块都带它，窗口口径不靠猜。 */
  @Schema(name = "PurchaseAnalyticsWindow")
  public record PurchaseAnalyticsWindow(LocalDate from, LocalDate to, String granularity) {}

  /** 总览。 */
  @Schema(name = "PurchaseAnalyticsSummary")
  public record PurchaseAnalyticsSummary(
      PurchaseAnalyticsWindow window,
      List<PurchaseAnalyticsCurrencyAmount> purchaseAmounts,
      @Schema(nullable = true, description = "只有单一币种时才有合计；多币种不合并") BigDecimal purchaseAmountTotal,
      String purchaseCurrencyNote,
      int costFactCount,
      int receiptFactCount,
      int planFactCount,
      int supplierCount,
      List<PurchaseAnalyticsCurrencyAmount> claimOffsets,
      BigDecimal unconvertedAmount,
      String unconvertedNote,
      String basis,
      String note) {}

  /** 一个币种下的金额合计（跨币种绝不合并成一个数）。 */
  @Schema(name = "PurchaseAnalyticsCurrencyAmount")
  public record PurchaseAnalyticsCurrencyAmount(
      String currency,
      BigDecimal amount,
      BigDecimal baseAmount,
      @Schema(nullable = true) String baseCurrency,
      long factCount,
      long unconvertedCount) {}

  /** 采购金额趋势（按币种分列）。 */
  @Schema(name = "PurchaseAnalyticsTrend")
  public record PurchaseAnalyticsTrend(
      PurchaseAnalyticsWindow window,
      List<String> currencies,
      List<PurchaseAnalyticsTrendPoint> points,
      String basis,
      String note) {}

  /** 趋势上的一个点。 */
  @Schema(name = "PurchaseAnalyticsTrendPoint")
  public record PurchaseAnalyticsTrendPoint(
      String period, String currency, BigDecimal amount, long factCount, BigDecimal averageAmount) {}

  /** 供应商集中度与贡献（**按币种分段** —— 跨币种不合并）。 */
  @Schema(name = "PurchaseAnalyticsConcentration")
  public record PurchaseAnalyticsConcentration(
      PurchaseAnalyticsWindow window,
      List<PurchaseAnalyticsConcentrationBucket> buckets,
      String basis,
      String note) {}

  /** 一个币种下的集中度。 */
  @Schema(name = "PurchaseAnalyticsConcentrationBucket")
  public record PurchaseAnalyticsConcentrationBucket(
      String currency,
      List<PurchaseAnalyticsSupplierRow> suppliers,
      BigDecimal totalAmount,
      BigDecimal topShare,
      int topN,
      BigDecimal hhi,
      String hhiScale,
      long supplierCount,
      BigDecimal unconvertedAmount) {}

  /** 一个供应商的集中度与贡献。 */
  @Schema(name = "PurchaseAnalyticsSupplierRow")
  public record PurchaseAnalyticsSupplierRow(
      UUID supplierRefId,
      String supplierCode,
      String supplierName,
      BigDecimal amount,
      BigDecimal share,
      BigDecimal cumulativeShare,
      long factCount,
      BigDecimal quantity,
      @Schema(nullable = true) BigDecimal onTimeRate,
      String currency) {}

  /** 采购价格波动（环比）。 */
  @Schema(name = "PurchaseAnalyticsPriceVariance")
  public record PurchaseAnalyticsPriceVariance(
      PurchaseAnalyticsWindow window,
      String currency,
      String comparison,
      List<PurchaseAnalyticsPriceVarianceRow> rows,
      @Schema(nullable = true) String currentPeriod,
      @Schema(nullable = true) String previousPeriod,
      @Schema(nullable = true) BigDecimal currentAveragePrice,
      @Schema(nullable = true) BigDecimal previousAveragePrice,
      @Schema(nullable = true) BigDecimal overallChangeRate,
      String basis,
      String note) {}

  /** 一个供应商（或 SKU）的价格波动行。 */
  @Schema(name = "PurchaseAnalyticsPriceVarianceRow")
  public record PurchaseAnalyticsPriceVarianceRow(
      String dimension,
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
      String status) {}

  /** 到货准时率。 */
  @Schema(name = "PurchaseAnalyticsOnTimeRate")
  public record PurchaseAnalyticsOnTimeRate(
      PurchaseAnalyticsWindow window,
      long denominatorCount,
      long onTimeCount,
      long lateCount,
      long pendingCount,
      BigDecimal onTimeRate,
      String denominatorRule,
      String calendarRule,
      List<PurchaseAnalyticsOnTimeRow> lateRows,
      String basis,
      String note) {}

  /** 一条逾期待到货的行（可下钻到具体单据）。 */
  @Schema(name = "PurchaseAnalyticsOnTimeRow")
  public record PurchaseAnalyticsOnTimeRow(
      UUID factId,
      String sourceDocNo,
      String sourceRefNo,
      String supplierName,
      LocalDate promisedArrivalDate,
      OffsetDateTime occurredAt,
      BigDecimal quantity,
      String currency,
      @Schema(nullable = true) BigDecimal amount,
      int delayDays) {}

  /** 采购成本占销售额比。 */
  @Schema(name = "PurchaseAnalyticsCostToSales")
  public record PurchaseAnalyticsCostToSales(
      PurchaseAnalyticsWindow window,
      boolean available,
      @Schema(nullable = true) String ownerModule,
      @Schema(nullable = true) String impact,
      @Schema(nullable = true) BigDecimal ratio,
      @Schema(nullable = true) String ratioCurrency,
      List<PurchaseAnalyticsCurrencyAmount> purchaseAmounts,
      @Schema(nullable = true) List<PurchaseAnalyticsCurrencyAmount> salesAmounts,
      @Schema(nullable = true) String salesNote,
      String basis,
      String note) {}

  /** 按类目 / 店铺维度的采购效率。 */
  @Schema(name = "PurchaseAnalyticsEfficiency")
  public record PurchaseAnalyticsEfficiency(
      PurchaseAnalyticsWindow window,
      String dimension,
      List<PurchaseAnalyticsEfficiencyRow> rows,
      String dimensionNote,
      String basis,
      String note) {}

  /** 效率表的一行。 */
  @Schema(name = "PurchaseAnalyticsEfficiencyRow")
  public record PurchaseAnalyticsEfficiencyRow(
      String dimensionCode,
      String dimensionLabel,
      BigDecimal plannedQuantity,
      @Schema(nullable = true) BigDecimal receivedQuantity,
      @Schema(nullable = true) BigDecimal fulfillmentRate,
      long planLineCount,
      long receiptFactCount,
      @Schema(nullable = true) String unitCode,
      String status,
      String note) {}

  // ================================================================ 报表请求

  /** 报表筛选（所有报表端点共用）。 */
  public record PurchaseAnalyticsQuery(
      LocalDate from,
      LocalDate to,
      String currency,
      String supplierRefId,
      String categoryCode,
      String shopCode,
      String factKind,
      String sourceType,
      String onTimeStatus,
      String keyword,
      String sort,
      int page,
      int size) {}
}
