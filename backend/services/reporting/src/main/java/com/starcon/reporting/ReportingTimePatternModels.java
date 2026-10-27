package com.starcon.reporting;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 13.2.1.2 分时与分周模式的**响应 / 请求模型**。
 *
 * <p>⚠️ <b>金额与数量一律 {@link BigDecimal}</b>：金额 {@code numeric(24,4)}、
 * 数量 {@code numeric(19,6)}，前端只做展示格式化，不重算。
 *
 * <p>⚠️ <b>「算不出来」与「等于 0」必须分开</b>：分母为 0、事实缺失、基期缺失，
 * 一律返回 {@code null}（页面上显示「—」/「不可比」），<b>不用 0 冒充</b>。
 */
public final class ReportingTimePatternModels {

  private ReportingTimePatternModels() {}

  // ==========================================================================
  // 能力声明
  // ==========================================================================

  /** 能力声明（页面上原样显示：哪些上游真接通、哪些确实拿不到、判定怎么算）。 */
  public record TimePatternCapabilities(
      boolean timezoneAware,
      boolean slotConfigurable,
      boolean heatmap,
      boolean peakRanking,
      List<String> upstreamModules,
      List<String> notDelivered,
      List<TimePatternRuleNote> rules,
      List<String> alignments,
      String defaultAlignment,
      String summaryCaliberNote) {}

  /** 一条判定规则说明。 */
  public record TimePatternRuleNote(String code, String note) {}

  // ==========================================================================
  // 指标口径目录
  // ==========================================================================

  /**
   * 一个口径定义的展示形态。
   *
   * @param referencedFrom 该口径的**唯一定义处**（表名）—— 页面据此显示「引用，不重定义」
   */
  public record TimePatternMetricDefinitionView(
      String metricCode,
      String displayName,
      String unit,
      String aggregation,
      String baseMetricCode,
      boolean currencyAware,
      String description,
      String ownerModule,
      String referencedFrom) {}

  // ==========================================================================
  // 店铺时区档案
  // ==========================================================================

  /** 一个店铺的时区档案。 */
  public record TimePatternShopView(
      String shopCode,
      String shopName,
      String shopTimezone,
      String timezoneStatus,
      String timezoneReason) {}

  // ==========================================================================
  // 分布
  // ==========================================================================

  /** 一个金额取值（**按币种分列，绝不相加**）。 */
  public record TimePatternAmount(String currency, BigDecimal value) {}

  /**
   * 热力图的一个格子（星期几 × 本地小时）。
   *
   * @param orderCount 该格子的订单笔数（与币种无关）
   * @param unitSold 该格子的销量件数（与币种无关）
   * @param salesAmounts 该格子的成交金额（**按币种分列**）
   * @param orderShare 该格子的订单占比（分母为 0 时为 null）
   * @param p90Peak 该小时是否达到 P90 高峰阈值
   */
  public record TimePatternCell(
      int weekday,
      String weekdayLabel,
      int hour,
      String hourLabel,
      BigDecimal orderCount,
      BigDecimal unitSold,
      List<TimePatternAmount> salesAmounts,
      BigDecimal orderShare,
      boolean p90Peak) {}

  /** 小时分布的一格（0..23）。 */
  public record TimePatternHourBucket(
      int hour,
      String hourLabel,
      BigDecimal orderCount,
      BigDecimal unitSold,
      List<TimePatternAmount> salesAmounts,
      BigDecimal orderShare,
      boolean p90Peak) {}

  /** 星期分布的一格（1..7，ISO 星期几）。 */
  public record TimePatternWeekdayBucket(
      int weekday,
      String weekdayLabel,
      BigDecimal orderCount,
      BigDecimal unitSold,
      List<TimePatternAmount> salesAmounts,
      BigDecimal orderShare) {}

  // ==========================================================================
  // 时段
  // ==========================================================================

  /**
   * 一条时段配置（租户可配置的数据）。
   *
   * @param qualified 该时段**是否达标**（配置的层级被数据核对通过）
   * @param reason 判定理由（页面上原样显示 —— 用户要看得懂「为什么这个时段算高峰」）
   */
  public record TimePatternSlotView(
      String slotCode,
      String displayName,
      int startHour,
      int endHour,
      String level,
      String levelLabel,
      String qualifyBasis,
      String qualifyComparison,
      BigDecimal qualifyThreshold,
      boolean qualified,
      String reason,
      BigDecimal orderCount,
      BigDecimal unitSold,
      List<TimePatternAmount> salesAmounts,
      BigDecimal orderShare) {}

  /** 高峰时段排序的一行。 */
  public record TimePatternPeakRank(
      int rank,
      String slotCode,
      String displayName,
      String level,
      String levelLabel,
      String metricCode,
      String metricLabel,
      BigDecimal metricValue,
      String currency,
      BigDecimal orderShare,
      boolean qualified,
      String reason) {}

  // ==========================================================================
  // 未定时的归属
  // ==========================================================================

  /**
   * **定不了店铺时区**的那部分订单（单列，不摊给任何时段）。
   *
   * @param reason 为什么定不了（页面上原样显示）
   */
  public record TimePatternUnattributed(
      BigDecimal orderCount,
      BigDecimal unitSold,
      List<TimePatternAmount> salesAmounts,
      String reason,
      List<String> shopCodes) {}

  // ==========================================================================
  // 覆盖度
  // ==========================================================================

  /**
   * 投影覆盖度（**投影不满时，格子里的 0 有多少是真没单、有多少是没投影，说不清**）。
   *
   * @param projectedDays 实际投影到的本地日期数
   * @param expectedDays 窗口里应有的本地日期数
   */
  public record TimePatternCoverage(
      BigDecimal projectedOrderCount, int projectedDays, int expectedDays, String note) {}

  // ==========================================================================
  // 主响应
  // ==========================================================================

  /** 分时与分周模式的主响应。 */
  public record TimePatternResponse(
      LocalDate requestedFrom,
      LocalDate requestedTo,
      LocalDate from,
      LocalDate to,
      String alignment,
      String alignmentLabel,
      String alignmentNote,
      List<String> shopCodes,
      List<TimePatternCell> heatmap,
      List<TimePatternHourBucket> hourDistribution,
      List<TimePatternWeekdayBucket> weekdayDistribution,
      List<TimePatternSlotView> slots,
      List<TimePatternPeakRank> peakRanking,
      String rankingMetricCode,
      String rankingMetricLabel,
      BigDecimal p90OrderCount,
      List<TimePatternShopView> shops,
      TimePatternUnattributed unattributed,
      TimePatternCoverage coverage,
      List<TimePatternMetricDefinitionView> metricDefinitions,
      OffsetDateTime dataUpdatedAt,
      String dataUpdatedNote,
      String status,
      String emptyReason,
      String emptyNote,
      List<String> warnings) {}

  // ==========================================================================
  // 时段配置（增删改查）
  // ==========================================================================

  /** 一条时段配置的展示形态（配置页用）。 */
  public record TimePatternSlotConfigView(
      String id,
      String slotCode,
      String displayName,
      int startHour,
      int endHour,
      String level,
      String levelLabel,
      String qualifyBasis,
      String qualifyComparison,
      BigDecimal qualifyThreshold,
      int sortOrder,
      boolean enabled,
      String description,
      String boundaryNote) {}

  /** 新建 / 改一条时段配置的请求体。 */
  public record TimePatternSlotRequest(
      String slotCode,
      String displayName,
      Integer startHour,
      Integer endHour,
      String level,
      String qualifyBasis,
      String qualifyComparison,
      BigDecimal qualifyThreshold,
      Integer sortOrder,
      Boolean enabled,
      String description) {}

  /** 整体替换时段配置的请求体（一次成型，避免逐条改时中间态留空洞）。 */
  public record TimePatternSlotReplaceRequest(List<TimePatternSlotRequest> slots) {}

  // ==========================================================================
  // 刷新
  // ==========================================================================

  /** 一次刷新的结果。 */
  public record TimePatternRefreshResult(
      String source, int fetched, int projected, String status, String note) {}
}
