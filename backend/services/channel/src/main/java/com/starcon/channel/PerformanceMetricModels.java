package com.starcon.channel;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 16.1.1.2 绩效指标监控的传输模型。
 *
 * <p>蓝图：「监控各平台核心绩效指标的趋势与阈值：订单缺陷率、迟发率、取消率、有效追踪率、
 * 响应时间、账户评级，支持自定义阈值预警与趋势预测（是否会跌破阈值）。
 * 输入：指标时间序列；阈值配置；预测模型参数。
 * 输出：指标趋势图 + 阈值预警 + 跌破预测。
 * 关系：上游 = 16.1.1.1；下游 = 1 工作台预警、9 订单履约（迟发预警前置）。
 * <b>预测跌破比已跌破更有价值</b>——提前几天知道就能提前改。」
 *
 * <p>🔴🔴 本模块对外最要紧的一条事实是<b>六项指标分属两个来源</b>：
 *
 * <ul>
 *   <li>四项指标（ODR / 迟发率 / 取消率 / 有效追踪率）的时间序列来自
 *       <b>16.1.1.1 的 {@code account_health_metric}</b>；</li>
 *   <li>两项指标（响应时间 / 账户评级）来自<b>本模块自有的
 *       {@code account_health_performance_metric}</b>。</li>
 * </ul>
 *
 * <p>这条事实**在 API 与页面上如实表达**（{@link PerformanceMetricDescriptor#sourceModule}
 * 与每条序列的 {@code sourceModule}），<b>不假装</b>六项指标来自同一张表。
 *
 * <p>🔴 <b>「不知道」与「不合格」是两个状态</b>：{@code value=null} 是<b>量不到</b>，
 * {@code severity=UNKNOWN} 是<b>判不了</b>，两者都<b>不许</b>被显示成「健康」或「0」。
 */
public final class PerformanceMetricModels {

  private PerformanceMetricModels() {}

  // ---------------------------------------------------------------- 能力声明

  /**
   * 一个未交付 / 已交付的下游或维度。
   *
   * @param moduleId 模块号（如 1 / 9）
   * @param moduleName 模块名
   * @param ownerModule 归谁（available=false 时必填）
   * @param available 当前是否已交付
   * @param impact 未交付的影响说明（界面原样显示）
   */
  public record PerformanceMetricCapabilityGap(
      String moduleId, String moduleName, String ownerModule, boolean available, String impact) {}

  /**
   * 一项指标的**归属说明**（双来源事实的载体）。
   *
   * @param metricCode 指标编码
   * @param metricLabel 指标的人话名
   * @param unit 量纲（RATIO / HOURS / SCORE）
   * @param unitLabel 量纲的人话
   * @param direction ABOVE_IS_BAD / BELOW_IS_BAD
   * @param directionLabel 方向的人话
   * @param ownerModule 该指标**归谁**（谁拥有它的数据与阈值）
   * @param sourceModule 该指标的时间序列**来自哪张表 / 哪个模块**
   * @param sourceLabel 来源的人话
   * @param available 本模块当前是否真的能给出这条序列
   * @param note 说明（量纲口径 / 是不是平台事实）
   */
  public record PerformanceMetricDescriptor(
      String metricCode,
      String metricLabel,
      String unit,
      String unitLabel,
      String direction,
      String directionLabel,
      String ownerModule,
      String sourceModule,
      String sourceLabel,
      boolean available,
      String note) {}

  /**
   * 能力声明。
   *
   * @param gaps 未交付的下游清单
   * @param metricCatalog 六项指标的归属说明（**双来源事实**）
   * @param metricCodes 六项指标的编码
   * @param metricSources 指标来源词表（PLATFORM_SYNC / MANUAL）
   * @param severities 预警等级词表
   * @param units 量纲词表
   * @param granularitySemantics 区间口径的说明（**趋势不是瞬时点**）
   * @param unknownSemantics 「量不到」的语义（不是 0、不是通过）
   * @param thresholdSemantics 阈值解析口径（用了哪条阈值）
   * @param trendSemantics 趋势计算口径（均值 / 环比的分母）
   * @param forecastSemantics 预测口径（模型 / 点数不足怎么办）
   * @param sourceNote 双来源的整体说明
   * @param upstreamNote 上游说明
   * @param downstreamNote 下游说明
   */
  public record PerformanceMetricCapabilities(
      List<PerformanceMetricCapabilityGap> gaps,
      List<PerformanceMetricDescriptor> metricCatalog,
      List<String> metricCodes,
      List<String> metricSources,
      List<String> severities,
      List<String> units,
      String granularitySemantics,
      String unknownSemantics,
      String thresholdSemantics,
      String trendSemantics,
      String forecastSemantics,
      String sourceNote,
      String upstreamNote,
      String downstreamNote) {}

  // ---------------------------------------------------------------- 店铺范围

  /**
   * 一个店铺在本模块里的覆盖情况。
   *
   * @param shopId 店铺 id
   * @param shopCode 店铺编码
   * @param shopName 店铺名
   * @param platformCode 平台编码
   * @param platformName 平台名
   * @param seriesCount 有指标记录的指标数（**有几条序列**）
   * @param knownPointCount 量到的点数
   * @param unknownPointCount 量不到的点数
   */
  public record PerformanceMetricShop(
      String shopId,
      String shopCode,
      String shopName,
      String platformCode,
      String platformName,
      int seriesCount,
      int knownPointCount,
      int unknownPointCount) {}

  /** 店铺范围清单（**真分页**，数据来自本服务自己的店铺目录）。 */
  public record PerformanceMetricShopPage(
      List<PerformanceMetricShop> items, long total, int page, int size) {}

  // ---------------------------------------------------------------- 趋势

  /**
   * 趋势序列里的一个点。
   *
   * @param periodStart 周期起点
   * @param periodEnd 周期终点
   * @param value **null = 该周期量不到**（不是 0）
   * @param known 是否量到了
   * @param source PLATFORM_SYNC / MANUAL
   * @param sourceLabel 来源的人话
   * @param capturedAt 采集 / 录入时刻
   * @param note 备注
   */
  public record PerformanceMetricPoint(
      String periodStart,
      String periodEnd,
      @Schema(description = "null = 该周期量不到（不是 0）") BigDecimal value,
      boolean known,
      String source,
      String sourceLabel,
      String capturedAt,
      String note) {}

  /**
   * 一条趋势序列（一个店铺 + 一个指标）。
   *
   * @param shopId 店铺 id
   * @param shopCode 店铺编码
   * @param shopName 店铺名
   * @param platformCode 平台编码
   * @param metricCode 指标编码
   * @param metricLabel 指标的人话名
   * @param unit 量纲
   * @param unitLabel 量纲的人话
   * @param sourceModule 时间序列**来自哪个模块**
   * @param sourceLabel 来源的人话
   * @param granularity **PERIOD_RANGE**（区间口径，不是瞬时点）
   * @param points 序列上的点（按周期终点升序）
   * @param knownPointCount 量到了几个点
   * @param unknownPointCount 量不到几个点
   * @param average 均值（**分母只算量到的点**；一个都没量到时为 null）
   * @param averageNote 均值的分母说明
   * @param latestValue 最后一个点的值（量不到时为 null）
   * @param latestPeriodStart 最后一个点的周期起点
   * @param latestPeriodEnd 最后一个点的周期终点
   * @param latestChange 环比变化量（不可知时为 null）
   * @param changeNote 环比的口径说明
   * @param note 序列口径说明
   */
  public record PerformanceMetricSeries(
      String shopId,
      String shopCode,
      String shopName,
      String platformCode,
      String metricCode,
      String metricLabel,
      String unit,
      String unitLabel,
      String sourceModule,
      String sourceLabel,
      String granularity,
      List<PerformanceMetricPoint> points,
      int knownPointCount,
      int unknownPointCount,
      @Schema(description = "分母只算量到的点；一个都没量到时为 null") BigDecimal average,
      String averageNote,
      BigDecimal latestValue,
      String latestPeriodStart,
      String latestPeriodEnd,
      BigDecimal latestChange,
      String changeNote,
      String note) {}

  /** 趋势序列清单（**真分页**，分页单位是「一条序列」）。 */
  public record PerformanceMetricTrendPage(
      List<PerformanceMetricSeries> items, long total, int page, int size, String note) {}

  // ---------------------------------------------------------------- 预警阈值

  /**
   * 一条生效中的预警阈值。
   *
   * @param metricCode 指标编码
   * @param metricLabel 指标的人话名
   * @param direction ABOVE_IS_BAD / BELOW_IS_BAD
   * @param directionLabel 方向的人话
   * @param unit 量纲
   * @param unitLabel 量纲的人话
   * @param warnValue 警告阈值
   * @param criticalValue 严重阈值
   * @param ruleVersion 规则版本
   * @param ruleSource BUILTIN_DEFAULT / TENANT_CUSTOM / UPSTREAM_TENANT_CUSTOM
   * @param ruleSourceLabel 来源的人话
   * @param builtin 是否是模块内置默认（true ⇒ 尚未被租户覆盖）
   * @param editable 是否可改
   * @param effectiveFrom 生效时刻（内置默认为 null）
   * @param note 备注
   */
  public record PerformanceMetricAlertRule(
      String metricCode,
      String metricLabel,
      String direction,
      String directionLabel,
      String unit,
      String unitLabel,
      BigDecimal warnValue,
      BigDecimal criticalValue,
      long ruleVersion,
      String ruleSource,
      String ruleSourceLabel,
      boolean builtin,
      boolean editable,
      String effectiveFrom,
      String note) {}

  /** 预警阈值清单（固定六条，一次返回）。 */
  public record PerformanceMetricAlertRuleList(List<PerformanceMetricAlertRule> items, String note) {}

  /**
   * 预警阈值修改的入参。
   *
   * @param direction ABOVE_IS_BAD / BELOW_IS_BAD
   * @param warnValue 警告阈值（**量纲随指标走**）
   * @param criticalValue 严重阈值
   * @param note 备注
   */
  public record PerformanceMetricAlertRuleInput(
      String direction, BigDecimal warnValue, BigDecimal criticalValue, String note) {}

  // ---------------------------------------------------------------- 预警凭据

  /**
   * 一条阈值预警凭据（**追加式**，带当时用到的阈值快照）。
   *
   * @param alertId 预警凭据 id
   * @param evaluationId 那次评估的 id（同一次评估的多条预警共用）
   * @param shopId 店铺 id
   * @param shopCode 店铺编码
   * @param shopName 店铺名
   * @param metricCode 指标编码
   * @param metricLabel 指标的人话名
   * @param severity CRITICAL / WARNING / UNKNOWN
   * @param severityLabel 等级的人话
   * @param unit 量纲
   * @param unitLabel 量纲的人话
   * @param metricValue 实际值（量不到时为 null）
   * @param metricValueText 实际值的展示串（**量不到显示「未知」，不显示 0**）
   * @param thresholdKind CRITICAL / WARN / UNKNOWN
   * @param thresholdValue 命中的阈值（量不到时为 null）
   * @param thresholdValueText 阈值展示串
   * @param direction ABOVE_IS_BAD / BELOW_IS_BAD
   * @param periodStart 命中的那个周期起点
   * @param periodEnd 命中的那个周期终点
   * @param evaluatedAt 评估时刻
   * @param ruleVersion 规则版本
   * @param ruleSource 阈值来源
   * @param ruleSourceLabel 阈值来源的人话
   * @param thresholds **当时用到的阈值快照**
   * @param message 人话说明
   */
  public record PerformanceMetricAlert(
      String alertId,
      String evaluationId,
      String shopId,
      String shopCode,
      String shopName,
      String metricCode,
      String metricLabel,
      String severity,
      String severityLabel,
      String unit,
      String unitLabel,
      BigDecimal metricValue,
      String metricValueText,
      String thresholdKind,
      BigDecimal thresholdValue,
      String thresholdValueText,
      String direction,
      String periodStart,
      String periodEnd,
      String evaluatedAt,
      long ruleVersion,
      String ruleSource,
      String ruleSourceLabel,
      Map<String, Object> thresholds,
      String message) {}

  /** 预警凭据清单（**真分页**）。 */
  public record PerformanceMetricAlertPage(
      List<PerformanceMetricAlert> items,
      long total,
      int page,
      int size,
      long criticalCount,
      long warningCount,
      long unknownCount,
      String note) {}

  /**
   * 一次预警评估的结果。
   *
   * @param evaluationId 评估 id
   * @param shopId 店铺 id
   * @param shopCode 店铺编码
   * @param shopName 店铺名
   * @param evaluatedAt 评估时刻
   * @param alerts 本次产生的预警凭据（**已知且未命中的指标不落行**）
   * @param evaluatedMetricCount 评估了几项指标
   * @param knownMetricCount 量到了几项
   * @param unknownMetricCount 量不到几项
   * @param note 口径说明
   */
  public record PerformanceMetricAlertEvaluation(
      String evaluationId,
      String shopId,
      String shopCode,
      String shopName,
      String evaluatedAt,
      List<PerformanceMetricAlert> alerts,
      int evaluatedMetricCount,
      int knownMetricCount,
      int unknownMetricCount,
      String note) {}

  // ---------------------------------------------------------------- 预测凭据

  /** 预测里用到的那个周期（写进凭据，可复核）。 */
  public record PerformanceMetricUsedPeriod(
      int index, String periodStart, String periodEnd, BigDecimal value) {}

  /**
   * 一条跌破预测凭据（**追加式**）。
   *
   * @param forecastId 预测凭据 id
   * @param shopId 店铺 id
   * @param shopCode 店铺编码
   * @param shopName 店铺名
   * @param metricCode 指标编码
   * @param metricLabel 指标的人话名
   * @param unit 量纲
   * @param unitLabel 量纲的人话
   * @param method 预测模型（LINEAR_LEAST_SQUARES）
   * @param methodNote **预测口径的人话说明**（可解释）
   * @param result WILL_BREACH / NO_BREACH / ALREADY_BREACHED / CANNOT_PREDICT
   * @param resultLabel 结论的人话
   * @param resultReason 结论的说明（**CANNOT_PREDICT 时说清为什么**）
   * @param breachLevel WARN / CRITICAL（null = 没预测到越线）
   * @param breachLevelLabel 越线档位的人话
   * @param breachInPeriods 从**最后一个量到的周期**起，第几个周期会越线
   * @param predictedValue 那个周期的预测值
   * @param predictedValueText 预测值展示串
   * @param thresholdValue 越的是哪条阈值
   * @param thresholdValueText 阈值展示串
   * @param thresholdKind WARN / CRITICAL
   * @param direction ABOVE_IS_BAD / BELOW_IS_BAD
   * @param directionLabel 方向的人话
   * @param knownPoints 量到的点数
   * @param unknownPoints 量不到的点数
   * @param windowPoints 窗口里一共看了几个周期
   * @param usedPeriods 用到的周期明细
   * @param slope 线性回归斜率
   * @param ruleVersion 规则版本
   * @param ruleSource 阈值来源
   * @param ruleSourceLabel 阈值来源的人话
   * @param thresholds **当时用到的阈值快照**
   * @param forecastedAt 预测时刻
   */
  public record PerformanceMetricForecast(
      String forecastId,
      String shopId,
      String shopCode,
      String shopName,
      String metricCode,
      String metricLabel,
      String unit,
      String unitLabel,
      String method,
      String methodNote,
      String result,
      String resultLabel,
      String resultReason,
      String breachLevel,
      String breachLevelLabel,
      Integer breachInPeriods,
      BigDecimal predictedValue,
      String predictedValueText,
      BigDecimal thresholdValue,
      String thresholdValueText,
      String thresholdKind,
      String direction,
      String directionLabel,
      int knownPoints,
      int unknownPoints,
      int windowPoints,
      List<PerformanceMetricUsedPeriod> usedPeriods,
      BigDecimal slope,
      long ruleVersion,
      String ruleSource,
      String ruleSourceLabel,
      Map<String, Object> thresholds,
      String forecastedAt) {}

  /** 预测凭据清单（**真分页**）。 */
  public record PerformanceMetricForecastPage(
      List<PerformanceMetricForecast> items,
      long total,
      int page,
      int size,
      long willBreachCount,
      long alreadyBreachedCount,
      long noBreachCount,
      long cannotPredictCount,
      String note) {}

  /**
   * 一次预测的结果（店铺 × 全部指标）。
   *
   * @param shopId 店铺 id
   * @param shopCode 店铺编码
   * @param shopName 店铺名
   * @param forecastedAt 预测时刻
   * @param results 每项指标一条结论
   * @param note 口径说明
   */
  public record PerformanceMetricForecastRun(
      String shopId,
      String shopCode,
      String shopName,
      String forecastedAt,
      List<PerformanceMetricForecast> results,
      String note) {}

  /**
   * 预测的入参（**预测模型参数**，蓝图输入句里的第三项）。
   *
   * @param windowPoints 窗口内看最近几个周期（null ⇒ 用服务端默认）
   * @param horizonPeriods 向后预测几个周期（null ⇒ 用服务端默认）
   */
  public record PerformanceMetricForecastInput(Integer windowPoints, Integer horizonPeriods) {}

  // ---------------------------------------------------------------- 指标录入

  /**
   * 手工录入一条**本模块自有**指标的入参（响应时间 / 账户评级）。
   *
   * @param metricCode 指标编码
   * @param metricValue 数值（**量纲随指标走**）；null = 显式登记「该指标量不到」
   * @param periodStart 周期起点
   * @param periodEnd 周期终点
   * @param note 备注
   */
  public record PerformanceMetricMetricInput(
      String metricCode, BigDecimal metricValue, String periodStart, String periodEnd, String note) {}
}
