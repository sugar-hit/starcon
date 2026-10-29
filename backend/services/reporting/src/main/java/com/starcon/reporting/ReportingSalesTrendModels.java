package com.starcon.reporting;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 13.2.1.1 销量趋势的**出入参模型**。
 *
 * <p>⚠️ **「算不出来」与「等于 0」必须分开**（与 13.1.1.1 / 13.1.1.2 / 13.4.2.2 同一纪律）：
 * 基期缺失、基期为 0、事实不存在三种情况一律返回 {@code null} / {@code NO_DATA}，
 * **不用 0 冒充**。销售额 0 与「这一格没算出来」在页面上长得一样，含义却相反。
 *
 * <p>⚠️ **不跨币种相加**：每个币种各自成一条序列 / 一行，**绝不**把不同币种加在一起。
 * 给了本位币且有生效汇率时，额外给出折算值，并把**汇率与时点**一起返回。
 *
 * <p>⚠️ **名字都带 {@code SalesTrend} 前缀**：SpringDoc 用**简单类名**当 schema 名，
 * 同名时只能加序号消歧（那个序号取决于**发现顺序**，换个服务先启动就换一个名，
 * 生成出来的契约跟着漂）。本仓已有 {@code RuleNote} / {@code CurrencyRate} /
 * {@code MetricDefinitionView}，本文件一律避开这三个名字。
 */
public final class ReportingSalesTrendModels {

  private ReportingSalesTrendModels() {}

  // ==================================================================
  // 指标口径目录
  // ==================================================================

  /**
   * 一个指标的口径定义（口径唯一的展示形态）。
   *
   * <p>⚠️ **{@code dimensionType} 是口径的一部分**：公司口径的成交金额与商品口径的行金额
   * 不是同一个数，所以维度写在口径里，页面必须把它显示出来。
   */
  public record SalesTrendMetricDefinitionView(
      String metricCode,
      String displayName,
      String unit,
      String aggregation,
      @Schema(nullable = true) String baseMetricCode,
      String dimensionType,
      boolean currencyAware,
      String description,
      String ownerModule) {}

  // ==================================================================
  // 趋势
  // ==================================================================

  /**
   * 趋势图上的一个点（一个桶 × 一个币种 × 一个指标）。
   *
   * @param bucketStart 桶起始日（按所用时区）
   * @param bucketEnd 桶结束日（含）
   * @param bucketHour 小时（仅 HOUR 粒度有值，其余为 null）
   * @param periodOverPeriodValue 上一基准值（null = 没有基期事实，**不是 0**）
   * @param periodOverPeriodRate 环比增长率（null = 算不出，**不是 0%**）
   * @param anomaly 该桶是否被判成异常（偏离基线超过阈值）
   * @param events 落在该桶里的异常事件（**销量异常必须能关联事件**）
   */
  public record SalesTrendPoint(
      LocalDate bucketStart,
      LocalDate bucketEnd,
      @Schema(nullable = true) Integer bucketHour,
      String bucketLabel,
      String currency,
      @Schema(nullable = true) BigDecimal value,
      @Schema(nullable = true) BigDecimal baseValue,
      @Schema(nullable = true) String baseCurrency,
      @Schema(nullable = true) BigDecimal periodOverPeriodValue,
      @Schema(nullable = true) BigDecimal periodOverPeriodRate,
      @Schema(nullable = true) LocalDate periodOverPeriodBucketStart,
      @Schema(nullable = true) BigDecimal yearOverYearValue,
      @Schema(nullable = true) BigDecimal yearOverYearRate,
      @Schema(nullable = true) LocalDate yearOverYearBucketStart,
      boolean anomaly,
      String anomalyNote,
      List<SalesTrendEvent> events) {}

  /** 一条序列 = 一个指标 × 一个币种（× 一个维度键）。 */
  public record SalesTrendSeries(
      String metricCode,
      String displayName,
      String unit,
      String dimensionType,
      String dimensionKey,
      String dimensionLabel,
      String currency,
      boolean currencyAware,
      List<SalesTrendPoint> points) {}

  /** 一条异常（某个桶偏离基线超过阈值）。 */
  public record SalesTrendAnomaly(
      LocalDate bucketStart,
      LocalDate bucketEnd,
      @Schema(nullable = true) Integer bucketHour,
      String metricCode,
      String currency,
      @Schema(nullable = true) BigDecimal value,
      @Schema(nullable = true) BigDecimal baseline,
      @Schema(nullable = true) BigDecimal deviationRate,
      /** SPIKE（异常偏高）/ DIP（异常偏低）。 */
      String direction,
      String note,
      /** 🔴 与该异常关联上的事件（没有事件时为空数组 —— 如实说明「查不到原因」）。 */
      List<SalesTrendEvent> events) {}

  // ==================================================================
  // 多维对比
  // ==================================================================

  /** 对比行里的一个指标取值。 */
  public record SalesTrendComparisonMetric(
      String metricCode,
      String displayName,
      String unit,
      @Schema(nullable = true) BigDecimal value,
      @Schema(nullable = true) BigDecimal baseValue,
      @Schema(nullable = true) BigDecimal periodOverPeriodValue,
      @Schema(nullable = true) BigDecimal periodOverPeriodRate,
      @Schema(nullable = true) BigDecimal yearOverYearValue,
      @Schema(nullable = true) BigDecimal yearOverYearRate,
      /** AVAILABLE / NO_DATA / NOT_COMPARABLE。 */
      String availability,
      String availabilityNote) {}

  /** 对比行 = 一个维度键（店铺 / 商品 / 公司）× 一个币种。 */
  public record SalesTrendComparisonRow(
      String dimensionType,
      String dimensionKey,
      String dimensionLabel,
      String currency,
      List<SalesTrendComparisonMetric> metrics) {}

  // ==================================================================
  // 异常事件
  // ==================================================================

  /** 一个异常事件（大促 / 秒杀 / 缺货 / 断货）。 */
  public record SalesTrendEvent(
      String eventCode,
      String eventType,
      String eventTypeLabel,
      String title,
      String detail,
      LocalDate eventDate,
      LocalDate endDate,
      String shopCode,
      String skuCode,
      String severity) {}

  // ==================================================================
  // 响应
  // ==================================================================

  /**
   * 销量趋势的完整响应。
   *
   * @param requestedFrom / requestedTo 调用方请求的原始范围
   * @param from / to **对齐到粒度后**的实际范围（周 / 月粒度会把边界对齐到整周 / 整月）
   * @param granularity DAY / WEEK / MONTH / HOUR
   * @param timezone 本次汇总所用的时区（**跨时区汇总必须显示所用时区**）
   * @param dataUpdatedAt 数据更新时间（**空表时为 null**，不是假时间）
   */
  public record SalesTrendResponse(
      LocalDate requestedFrom,
      LocalDate requestedTo,
      LocalDate from,
      LocalDate to,
      String granularity,
      String timezone,
      List<SalesTrendSeries> series,
      List<SalesTrendAnomaly> anomalies,
      List<SalesTrendEvent> events,
      List<SalesTrendMetricDefinitionView> metricDefinitions,
      @Schema(nullable = true) OffsetDateTime dataUpdatedAt,
      String dataUpdatedNote,
      String status,
      String statusNote,
      @Schema(nullable = true) String emptyReason,
      String emptyNote,
      List<String> warnings,
      List<String> caliberNotes,
      @Schema(nullable = true) String baseCurrency,
      List<SalesTrendCurrencyRate> baseRates) {}

  /** 多维对比的完整响应。 */
  public record SalesTrendComparisonResponse(
      LocalDate requestedFrom,
      LocalDate requestedTo,
      LocalDate from,
      LocalDate to,
      String granularity,
      String timezone,
      String dimensionType,
      List<SalesTrendComparisonRow> rows,
      List<SalesTrendMetricDefinitionView> metricDefinitions,
      @Schema(nullable = true) OffsetDateTime dataUpdatedAt,
      String dataUpdatedNote,
      String status,
      String statusNote,
      @Schema(nullable = true) String emptyReason,
      String emptyNote,
      List<String> warnings,
      List<String> caliberNotes,
      @Schema(nullable = true) String baseCurrency,
      List<SalesTrendCurrencyRate> baseRates) {}

  /** 异常事件清单的响应。 */
  public record SalesTrendEventResponse(
      LocalDate from,
      LocalDate to,
      List<SalesTrendEvent> events,
      @Schema(nullable = true) OffsetDateTime dataUpdatedAt,
      String dataUpdatedNote,
      String status,
      String statusNote,
      @Schema(nullable = true) String emptyReason,
      String emptyNote,
      List<String> warnings) {}

  /** 一个币种对本位币的汇率与时点。 */
  public record SalesTrendCurrencyRate(
      String currency, String baseCurrency, BigDecimal rate, @Schema(nullable = true) OffsetDateTime asOf) {}

  // ==================================================================
  // 能力声明
  // ==================================================================

  /**
   * 能力声明。
   *
   * @param upstreamConnected 上游是否真接通（本模块的三个上游都已交付）
   * @param notDelivered **确实拿不到**的部分（每条都写清原因与影响）
   * @param rules 判定规则（异常阈值、同比 / 环比基准、维度口径差异）
   */
  public record SalesTrendCapabilities(
      boolean upstreamConnected,
      boolean trendAvailable,
      boolean comparisonAvailable,
      boolean eventsAvailable,
      boolean dataUpdatedAtVisible,
      List<String> upstreamModules,
      List<String> notDelivered,
      List<SalesTrendRuleNote> rules,
      List<String> granularities,
      List<String> dimensions) {}

  /** 一条判定规则。 */
  public record SalesTrendRuleNote(String ruleCode, String description) {}

  // ==================================================================
  // 刷新
  // ==================================================================

  /** 一路刷新结果。 */
  public record SalesTrendRefreshResult(
      String source,
      /** 拉到的上游行数。 */
      int fetched,
      /** 落库的事实行数。 */
      int projected,
      /** 结论：SUCCEEDED / FAILED / TRUNCATED。 */
      String status,
      /** 说明（失败原因 / 截断原因）。 */
      String note) {}
}
