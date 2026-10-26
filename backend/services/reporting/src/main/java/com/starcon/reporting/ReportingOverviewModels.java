package com.starcon.reporting;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 13.1.1.1 经营总览的**读模型契约**（蓝图「输出」句：指标卡 + 趋势图 + 异常提示 +
 * 下钻入口 + 数据更新时间戳）。
 *
 * <p>⚠️ **「没有数据」与「数据是 0」是两件事**：本文件里凡是可以「算不出来」的数值
 * 一律用 {@code null} 表示，**不用 0 冒充**。销售额 0 与「这一格没算出来」在报表里
 * 长得一模一样，但含义完全相反。
 */
public final class ReportingOverviewModels {

  private ReportingOverviewModels() {}

  /** 一个币种上的一个取值。 */
  public record CurrencyValue(String currency, @Schema(nullable = true) BigDecimal value) {}

  /**
   * 对比期（环比 / 同比）的取值与变化。
   *
   * @param comparable 该对比**是否可比**。分母缺失或为 0 时 {@code changeRate} 为 null
   *     且 {@code comparable=false} —— 「不可比」必须说出来，不能显示成 0%。
   */
  public record Comparison(
      String label,
      LocalDate from,
      LocalDate to,
      List<CurrencyValue> values,
      @Schema(nullable = true) BigDecimal changeRate,
      @Schema(nullable = true) BigDecimal changeAmount,
      boolean comparable,
      String note) {}

  /**
   * 一张指标卡。
   *
   * @param metricCode 指标码
   * @param displayName 显示名
   * @param unit 单位（CURRENCY / COUNT / QUANTITY / PERCENT）
   * @param aggregation 聚合方式（SUM / LAST / DERIVED_DIFF / DERIVED_RATIO）
   * @param description 口径说明（页面上原样显示）
   * @param ownerModule 口径归属的上游模块
   * @param availability 数据可得性：{@code AVAILABLE} 有事实 / {@code NO_DATA} 该口径下
   *     **没有事实**（≠ 0）/ {@code NOT_REGISTERED} 该指标码未在口径表登记
   * @param values 按币种的取值（**不跨币种相加**）
   * @param baseTotal 折算到本位币的合计（没有本位币或缺汇率时为 null）
   * @param baseCurrency 本位币
   * @param baseRate 折算用的汇率（**必须与 baseRateAsOf 一起显示**）
   * @param baseRateAsOf 折算用的汇率时点
   */
  public record MetricCard(
      String metricCode,
      String displayName,
      String unit,
      String aggregation,
      String description,
      String ownerModule,
      String availability,
      List<CurrencyValue> values,
      @Schema(nullable = true) BigDecimal baseTotal,
      @Schema(nullable = true) String baseCurrency,
      @Schema(nullable = true) BigDecimal baseRate,
      @Schema(nullable = true) OffsetDateTime baseRateAsOf,
      Comparison previousPeriod,
      Comparison yearOverYear,
      String note) {}

  /** 趋势图上的一个点（一天一个币种一个值）。 */
  public record TrendPoint(
      String metricCode, LocalDate date, String currency, @Schema(nullable = true) BigDecimal value) {}

  /**
   * 经营总览的完整响应。
   *
   * @param dataUpdatedAt **数据更新时间**（卷 13 卷首「数据延迟要显示」：页面必须常驻显示）
   * @param dataUpdatedNote 这个时刻**具体指什么**（投影落库时刻 vs 事实行更新时刻）
   * @param timezones 分桶所用时区（**跨时区店铺汇总必须显示所用时区**）
   * @param warnings 异常提示（例如「销售额按币种分别列出，未跨币种相加」）
   */
  public record OverviewResponse(
      String scopeType,
      @Schema(nullable = true) UUID legalEntityId,
      @Schema(nullable = true) UUID shopId,
      LocalDate from,
      LocalDate to,
      List<MetricCard> cards,
      List<TrendPoint> trend,
      @Schema(nullable = true) OffsetDateTime dataUpdatedAt,
      String dataUpdatedNote,
      List<String> timezones,
      List<String> warnings,
      String currencyNote) {}

  /** 下钻构成明细的一行。 */
  public record DrilldownRow(
      String sourceKind,
      String referenceId,
      String referenceLabel,
      String currency,
      @Schema(nullable = true) BigDecimal quantity,
      @Schema(nullable = true) BigDecimal value,
      LocalDate bucketDate) {}

  /** 下钻结果（真分页 + 真计数）。 */
  public record DrilldownPage(
      List<DrilldownRow> items, int page, int size, long total, String metricCode, String metricName) {}

  /** 指标口径目录的一行（**口径唯一的展示形态**）。 */
  public record MetricDefinitionView(
      String metricCode,
      String displayName,
      String unit,
      String aggregation,
      @Schema(nullable = true) String numeratorMetricCode,
      @Schema(nullable = true) String denominatorMetricCode,
      boolean currencyAware,
      String description,
      String ownerModule) {}
}
