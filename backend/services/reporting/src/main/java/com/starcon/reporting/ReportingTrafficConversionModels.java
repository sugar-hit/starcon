package com.starcon.reporting;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 13.3.2.1 流量转化的**出入参模型**。
 *
 * <p>⚠️ **「算不出来」与「等于 0」必须分开**（与 13.1.1.1 / 13.2.1.1 / 13.2.2.1 同一纪律）：
 * 分母为 0、基线缺失、事实不存在三种情况一律返回 {@code null} / {@code NO_DATA} /
 * {@code NOT_COMPARABLE}，**不用 0 冒充**。
 *
 * <p>⚠️ **口径必须随值一起给**：每个指标值都带 {@code caliber}（PLATFORM / ORDER）与
 * {@code trafficSource}（LISTING / AD / ORDER）——
 * 「平台流量数据与内部订单数据口径不同，展示时必须标注来源与统计范围」是蓝图的硬要求，
 * 不是可选项。
 *
 * <p>⚠️ **名字都带 {@code TrafficConversion} 前缀**：SpringDoc 用**简单类名**当 schema 名，
 * 同名时只能加序号消歧（那个序号取决于**发现顺序**，换个服务先启动就换一个名，
 * 生成出来的契约跟着漂）。本仓已有 {@code RuleNote} / {@code CurrencyRate} /
 * {@code MetricDefinitionView}，本文件一律避开这三个名字。
 */
public final class ReportingTrafficConversionModels {

  private ReportingTrafficConversionModels() {}

  // ==================================================================
  // 指标口径目录
  // ==================================================================

  /**
   * 一个指标的口径定义（口径唯一的展示形态）。
   *
   * @param caliber PLATFORM 平台口径（页面口径）/ ORDER 订单口径
   * @param trafficSource LISTING / AD / ORDER（派生指标为空 —— 它的来源由分子分母决定）
   */
  public record TrafficMetricDefinitionView(
      String metricCode,
      String displayName,
      String unit,
      String aggregation,
      @Schema(nullable = true) String numeratorMetricCode,
      @Schema(nullable = true) String denominatorMetricCode,
      String caliber,
      @Schema(nullable = true) String trafficSource,
      boolean currencyAware,
      String description,
      String ownerModule) {}

  // ==================================================================
  // 漏斗
  // ==================================================================

  /**
   * 漏斗里的一节。
   *
   * @param value 本节的取值（null = 算不出来，**不是 0**）
   * @param stepRate 相对**上一节**的转化率（null = 算不出来）
   * @param overallRate 相对**第一节**的转化率（null = 算不出来）
   * @param availability AVAILABLE / NO_DATA / NOT_COMPARABLE / CALIBER_CONFLICT
   */
  public record TrafficFunnelStage(
      String stageKey,
      String displayName,
      String metricCode,
      String unit,
      String caliber,
      String trafficSource,
      /** 币种（**空串 = 与币种无关**）。 */
      String currency,
      @Schema(nullable = true) BigDecimal value,
      @Schema(nullable = true) BigDecimal stepRate,
      @Schema(nullable = true) BigDecimal overallRate,
      String availability,
      String availabilityNote) {}

  /** 一组漏斗（每一格都带自己的口径）。 */
  public record TrafficFunnelGroup(
      String groupKey,
      String displayName,
      String caliber,
      String trafficSource,
      String sourceNote,
      List<TrafficFunnelStage> stages,
      String note) {}

  // ==================================================================
  // 关键指标
  // ==================================================================

  /** 一个指标在当前窗口的取值（**带口径标签**）。 */
  public record TrafficMetricValue(
      String metricCode,
      String displayName,
      String unit,
      String caliber,
      @Schema(nullable = true) String trafficSource,
      /** 币种（**空串 = 与币种无关**）。 */
      String currency,
      @Schema(nullable = true) BigDecimal value,
      String availability,
      String availabilityNote) {}

  // ==================================================================
  // 趋势
  // ==================================================================

  /** 趋势上的一个桶（日 / 周 / 月）。 */
  public record TrafficTrendPoint(
      String bucketStart,
      String bucketEnd,
      String bucketLabel,
      List<TrafficMetricValue> metrics) {}

  // ==================================================================
  // 异常诊断
  // ==================================================================

  /**
   * 一条异常诊断。
   *
   * @param severity HIGH / MEDIUM
   * @param observed 观测值（当前桶 / 当前窗口）
   * @param baseline 基线值（上一个桶 / 对照口径）
   * @param changeRate 变化率（负数 = 下降；null = 算不出来）
   * @param threshold 触发阈值
   */
  public record TrafficDiagnosis(
      String ruleCode,
      String severity,
      String metricCode,
      String scope,
      @Schema(nullable = true) String bucketStart,
      @Schema(nullable = true) String bucketEnd,
      @Schema(nullable = true) BigDecimal observed,
      @Schema(nullable = true) BigDecimal baseline,
      @Schema(nullable = true) BigDecimal changeRate,
      @Schema(nullable = true) BigDecimal threshold,
      String message,
      String suggestion) {}

  // ==================================================================
  // 下钻
  // ==================================================================

  /** 构成明细的一行（哪个 listing / 哪个广告活动 / 哪张内部订单）。 */
  public record TrafficDrilldownRow(
      String trafficSource,
      String sourceKind,
      String referenceId,
      String referenceLabel,
      String shopCode,
      String platformCode,
      String orderNo,
      @Schema(nullable = true) OffsetDateTime occurredAt,
      String statDate,
      String currency,
      BigDecimal quantity,
      BigDecimal value) {}

  /** 下钻的完整响应。 */
  public record TrafficDrilldownResponse(
      LocalDate from,
      LocalDate to,
      String trafficSource,
      @Schema(nullable = true) String metricCode,
      long totalRows,
      int page,
      int size,
      List<TrafficDrilldownRow> rows,
      @Schema(nullable = true) OffsetDateTime dataUpdatedAt,
      String dataUpdatedNote,
      String status,
      String statusNote,
      @Schema(nullable = true) String emptyReason,
      String emptyNote,
      List<String> warnings) {}

  // ==================================================================
  // 响应
  // ==================================================================

  /**
   * 流量转化的完整响应。
   *
   * @param requestedFrom / requestedTo 调用方请求的原始范围
   * @param granularity DAY / WEEK / MONTH（**桶的对齐规则是口径的一部分**）
   * @param dataUpdatedAt 数据更新时间（**空表时为 null**，不是假时间）
   */
  public record TrafficConversionResponse(
      LocalDate requestedFrom,
      LocalDate requestedTo,
      String granularity,
      String granularityLabel,
      List<TrafficFunnelGroup> funnel,
      List<TrafficMetricValue> keyMetrics,
      List<TrafficTrendPoint> trend,
      List<TrafficDiagnosis> diagnoses,
      List<TrafficMetricDefinitionView> metricDefinitions,
      @Schema(nullable = true) OffsetDateTime dataUpdatedAt,
      String dataUpdatedNote,
      String status,
      String statusNote,
      @Schema(nullable = true) String emptyReason,
      String emptyNote,
      List<String> warnings,
      List<String> caliberNotes) {}

  // ==================================================================
  // 能力声明
  // ==================================================================

  /**
   * 能力声明。
   *
   * @param notDelivered **确实拿不到**的部分（每条都写清原因与影响）
   * @param rules 判定规则（口径说明、诊断阈值、诊断规则 …）
   */
  public record TrafficConversionCapabilities(
      boolean upstreamConnected,
      boolean funnelAvailable,
      boolean trendAvailable,
      boolean diagnosisAvailable,
      boolean drilldownAvailable,
      boolean dataUpdatedAtVisible,
      List<String> upstreamModules,
      List<String> notDelivered,
      List<TrafficRuleNote> rules,
      List<String> metrics,
      List<String> granularities) {}

  /** 一条判定规则。 */
  public record TrafficRuleNote(String ruleCode, String description) {}

  // ==================================================================
  // 刷新
  // ==================================================================

  /** 一路刷新结果。 */
  public record TrafficRefreshResult(
      String source,
      /** 拉到的上游行数。 */
      int fetched,
      /** 落库的事实 + 明细行数。 */
      int projected,
      /** 结论：SUCCEEDED / FAILED / TRUNCATED。 */
      String status,
      /** 说明（失败原因 / 截断原因）。 */
      String note) {}
}
