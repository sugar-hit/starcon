package com.starcon.reporting;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 1.1.2.1 核心指标卡的**出入参模型**（蓝图 01 卷「输出」句：指标数值 + 趋势箭头 +
 * 同比环比 + 下钻链接 + 口径说明悬浮提示）。
 *
 * <p>⚠️ **「算不出来」与「等于 0」必须分开**（与 13.1.1.1 经营总览、13.1.1.2 多店对比同纪律）：
 * 凡是可以「算不出来」的数值一律用 {@code null} 表示，**不用 0 冒充**。
 * 销售额 0 与「这一格没算出来」在卡片上长得一模一样，含义却相反。
 *
 * <p>⚠️ **三个「判不了」必须分开说**（照抄 1.1.1.1 / 1.1.2.3 的既有约定）：
 *
 * <ol>
 *   <li><b>基期没数据</b> ⇒ 变化率为 {@code null}，卡片显示「—」并带原因，
 *       **不是 0%、不是「持平」、更不是 -100%**；</li>
 *   <li><b>多币种没折算</b> ⇒ 同样不可判定（没有汇率就不可判定，**不是按 1:1 算**）；</li>
 *   <li><b>分母为 0</b> ⇒ 派生比算不出，**不是 0%**。</li>
 * </ol>
 *
 * <p>⚠️ **名字不与既有类型重名**（codegen 的同名闸会中止契约生成）：
 * 13.1.1.1 已有 {@code MetricCard} / {@code Comparison} / {@code CurrencyValue}，
 * 13.1.1.2 已有 {@code StoreMetricValue} / {@code ComparisonCapabilities}。
 * 本文件统一用 {@code MetricCard*} 前缀。
 */
public final class ReportingMetricCardModels {

  private ReportingMetricCardModels() {}

  // ==================================================================
  // 能力声明
  // ==================================================================

  /**
   * 一个指标的能力声明（**逐条**，不是一句「都接通了」）。
   *
   * @param key 卡片标识（稳定字符串，进工作台布局偏好时不许改）
   * @param metricCode 指标码
   * @param displayName 显示名
   * @param unit 单位（CURRENCY / COUNT / QUANTITY / PERCENT）
   * @param currencyAware 是否涉币（涉币指标**不跨币种相加**）
   * @param available 上游是否**真接通**。false ⇒ 该指标没有可引用的口径，
   *     {@code unavailableReason} 写明归谁，{@code numeratorMetricCode} /
   *     {@code denominatorMetricCode} **一律不给**（给了就是编一个公式出来）
   * @param ownerModule 口径归属模块（该指标归哪个系统哪个模块）
   * @param caliber 口径说明（页面上原样显示在 tooltip 里）
   * @param aggregation 聚合方式（SUM / LAST / DERIVED_DIFF / DERIVED_RATIO /
   *     UPSTREAM_SUMMARY —— 最后一个表示「口径在上游，本服务只引用上游算好的合计」）
   * @param numeratorMetricCode 派生型的分子指标码（非派生型为 null）
   * @param denominatorMetricCode 派生型的分母指标码（非派生型为 null）
   * @param sourceTables 数据来源（表名 / 授权只读 API）
   * @param sourceKind 取数来源：REPORTING_PROJECTION / AUTHORIZED_API / NOT_CONNECTED
   * @param drilldownAvailable 是否有可用的下钻入口
   * @param drilldownTargetName 下钻目标名（构成明细 / 已交付的报表页）
   * @param unavailableReason 接不通的原因（available=false 时必填）
   * @param note 补充说明
   */
  public record MetricCardCapability(
      String key,
      String metricCode,
      String displayName,
      String unit,
      boolean currencyAware,
      boolean available,
      String ownerModule,
      String caliber,
      String aggregation,
      @Schema(nullable = true) String numeratorMetricCode,
      @Schema(nullable = true) String denominatorMetricCode,
      String sourceTables,
      String sourceKind,
      boolean drilldownAvailable,
      @Schema(nullable = true) String drilldownTargetName,
      String unavailableReason,
      String note) {}

  /**
   * 能力声明的完整响应。
   *
   * @param metrics 逐个指标的能力声明
   * @param upstreamModules 本服务**真的读过**的上游模块
   * @param notDelivered **确实拿不到**的部分（每条写清原因与影响）
   * @param notes 口径纪律说明
   */
  public record MetricCardsCapabilities(
      boolean upstreamConnected,
      boolean drilldownAvailable,
      boolean dataUpdatedAtVisible,
      List<MetricCardCapability> metrics,
      List<String> upstreamModules,
      List<String> notDelivered,
      List<String> notes) {}

  // ==================================================================
  // 卡片数据
  // ==================================================================

  /** 一个币种上的一个取值（**不跨币种相加**）。 */
  public record MetricCardAmount(String currency, @Schema(nullable = true) BigDecimal value) {}

  /**
   * 一次对比（环比 / 同比）。
   *
   * @param label 对比口径说明（如「环比（上一等长周期）」）
   * @param from 对比期起
   * @param to 对比期止
   * @param changeRate 变化率（**百分比**）。不可判定时为 {@code null}
   * @param changeAmount 变化额。不可判定时为 {@code null}
   * @param comparable 该对比**是否可比**。false ⇒ changeRate 必为 null
   * @param reason 不可判定的原因（comparable=false 时必填）
   */
  public record MetricCardComparison(
      String label,
      LocalDate from,
      LocalDate to,
      @Schema(nullable = true) BigDecimal changeRate,
      @Schema(nullable = true) BigDecimal changeAmount,
      boolean comparable,
      String reason) {}

  /**
   * 下钻入口。
   *
   * @param available 有没有下钻入口（没有时前端**不显示**入口，而不是显示一个死链）
   * @param detailPath 构成明细的接口路径（复用 13.1.1.1 的下钻端点，带 metric + 期间定位参数）
   * @param reportPath 已交付的报表页路径（带 metric + 期间定位参数）
   * @param reportName 报表页名称
   * @param reason 没有下钻入口的原因（available=false 时必填）
   */
  public record MetricCardDrilldown(
      boolean available,
      @Schema(nullable = true) String detailPath,
      @Schema(nullable = true) String reportPath,
      @Schema(nullable = true) String reportName,
      String reason) {}

  /**
   * 一张指标卡。
   *
   * @param status 数据状态：
   *     {@code AVAILABLE} 有数 / {@code NO_DATA} 该窗口该口径下**没有事实**（≠ 0）/
   *     {@code NOT_CONNECTED} 该指标口径尚未接通 / {@code UPSTREAM_UNAVAILABLE}
   *     上游授权 API 本次取不到（无权限或服务不可达）
   * @param values 按币种的取值（**不跨币种相加**）
   * @param headline 卡面主值。**多币种且未指定本位币时为 null**（不是 0，
   *     也不是随便挑一个币种）—— 卡片改显示「按币种分列」
   * @param previousPeriod 环比（与上一等长周期比）
   * @param yearOverYear 同比（与去年同期同长度窗口比）
   * @param trend 趋势箭头：UP / DOWN / FLAT / UNKNOWN（**由后端算出的变化率决定**，
   *     前端不许自己拿两个数做减法）
   * @param trendBasis 箭头依据（环比 / 同比 / 无）
   * @param caliber 口径说明（tooltip 文案）
   * @param ownerModule 口径归属
   * @param drilldown 下钻入口
   * @param note 补充说明（为什么没有数、为什么不可判定）
   */
  public record MetricCardEntry(
      String key,
      String metricCode,
      String displayName,
      String unit,
      boolean currencyAware,
      String status,
      List<MetricCardAmount> values,
      @Schema(nullable = true) MetricCardAmount headline,
      MetricCardComparison previousPeriod,
      MetricCardComparison yearOverYear,
      String trend,
      String trendBasis,
      String caliber,
      String ownerModule,
      MetricCardDrilldown drilldown,
      String note) {}

  /**
   * 一次取数的完整响应。
   *
   * @param windowPreset 时间口径选择：TODAY / YESTERDAY / THIS_MONTH / CUSTOM
   * @param from 窗口起
   * @param to 窗口止
   * @param asOfDate 「今日」在这个租户时区里的日期（窗口按它推导，不是按 UTC）
   * @param timezone 窗口边界所用的时区
   * @param timezoneSource 时区来源：PROJECTION（取自本租户投影事实的分桶时区）/
   *     FALLBACK_UTC（本租户还没有投影数据，暂按 UTC）
   * @param timezones 本窗口涉及的分桶时区（跨时区汇总必须显示所用时区）
   * @param baseCurrency 本位币（给了且折算成功时才有值）
   * @param cards 卡片
   * @param dataUpdatedAt 数据更新时间（卷 13 卷首「数据延迟要显示」）
   * @param dataUpdatedNote 这个时刻具体指什么
   * @param warnings 异常提示
   * @param currencyNote 币种口径说明
   */
  public record MetricCardsResponse(
      String windowPreset,
      LocalDate from,
      LocalDate to,
      LocalDate asOfDate,
      String timezone,
      String timezoneSource,
      List<String> timezones,
      @Schema(nullable = true) String baseCurrency,
      List<MetricCardEntry> cards,
      @Schema(nullable = true) OffsetDateTime dataUpdatedAt,
      String dataUpdatedNote,
      List<String> warnings,
      String currencyNote) {}
}
