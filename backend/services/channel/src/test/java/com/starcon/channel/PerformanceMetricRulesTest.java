package com.starcon.channel;

import static com.starcon.channel.PerformanceMetricRules.*;
import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 16.1.1.2 绩效指标监控的规则层单测。
 *
 * <p>🔴 这组用例守的是本模块最容易做丢的六件事：
 *
 * <ol>
 *   <li><b>量不到 ≠ 0、量不到 ≠ 通过</b>；</li>
 *   <li><b>阈值方向</b>：越大越危险 / 越小越危险；</li>
 *   <li><b>阈值解析顺序</b>：本模块自定义 &gt; 上游租户覆盖 &gt; 内置默认；</li>
 *   <li><b>量纲不可混</b>：比值 / 小时 / 星级；</li>
 *   <li><b>均值与环比的分母</b>：量不到的不进分母，且分母写清楚；</li>
 *   <li><b>预测不许造假</b>：点数不足 ⇒ 无法预测 + 原因。</li>
 * </ol>
 */
class PerformanceMetricRulesTest {

  static BigDecimal d(String raw) {
    return new BigDecimal(raw);
  }

  static PerformanceMetricRules.TrendPoint point(String start, String end, String value) {
    return new PerformanceMetricRules.TrendPoint(
        start, end, value == null ? null : d(value), "MANUAL", "2026-10-31T00:00:00Z", "");
  }

  // ==================================================================
  // 量不到 ≠ 0，量不到 ≠ 通过
  // ==================================================================

  @Test
  @DisplayName("16.1.1.2 量不到 ⇒ UNKNOWN，不算通过，也不是 0")
  void unknownIsNeitherZeroNorPass() {
    PerformanceMetricRules.AlertThreshold threshold =
        BUILTIN_ALERT_THRESHOLDS.get(METRIC_ODR);

    PerformanceMetricRules.MetricVerdict verdict = evaluate(METRIC_ODR, null, threshold);

    assertThat(verdict.severity()).isEqualTo(SEVERITY_UNKNOWN);
    assertThat(verdict.thresholdKind()).isEqualTo(THRESHOLD_UNKNOWN);
    assertThat(verdict.thresholdValue()).isNull();
    assertThat(verdict.message()).contains("量不到").contains("不算通过");
  }

  @Test
  @DisplayName("16.1.1.2 已知且未命中阈值 ⇒ 不产预警（没有预警就没有凭据）")
  void knownAndHealthyProducesNoAlert() {
    PerformanceMetricRules.MetricVerdict verdict =
        evaluate(METRIC_ODR, d("0.0050"), BUILTIN_ALERT_THRESHOLDS.get(METRIC_ODR));

    assertThat(verdict.severity()).isNull();
    assertThat(verdict.thresholdKind()).isNull();
  }

  // ==================================================================
  // 阈值方向
  // ==================================================================

  @Test
  @DisplayName("16.1.1.2 ABOVE_IS_BAD：越大越危险，严重档优先于警告档")
  void aboveIsBadHitsCriticalFirst() {
    PerformanceMetricRules.AlertThreshold threshold =
        BUILTIN_ALERT_THRESHOLDS.get(METRIC_ODR);

    PerformanceMetricRules.MetricVerdict critical = evaluate(METRIC_ODR, d("0.0300"), threshold);
    assertThat(critical.severity()).isEqualTo(SEVERITY_CRITICAL);
    assertThat(critical.thresholdKind()).isEqualTo(THRESHOLD_CRITICAL);
    assertThat(critical.thresholdValue()).isEqualByComparingTo("0.0280");

    PerformanceMetricRules.MetricVerdict warning = evaluate(METRIC_ODR, d("0.0200"), threshold);
    assertThat(warning.severity()).isEqualTo(SEVERITY_WARNING);
    assertThat(warning.thresholdKind()).isEqualTo(THRESHOLD_WARN);
    assertThat(warning.thresholdValue()).isEqualByComparingTo("0.0100");
  }

  @Test
  @DisplayName("16.1.1.2 BELOW_IS_BAD：越小越危险（有效追踪率 / 账户评级）")
  void belowIsBadIsTheOtherWay() {
    PerformanceMetricRules.AlertThreshold tracking =
        BUILTIN_ALERT_THRESHOLDS.get(METRIC_VALID_TRACKING);
    PerformanceMetricRules.MetricVerdict trackingVerdict =
        evaluate(METRIC_VALID_TRACKING, d("0.9400"), tracking);
    assertThat(trackingVerdict.severity()).isEqualTo(SEVERITY_CRITICAL);
    assertThat(trackingVerdict.thresholdValue()).isEqualByComparingTo("0.9500");

    PerformanceMetricRules.AlertThreshold rating =
        BUILTIN_ALERT_THRESHOLDS.get(METRIC_ACCOUNT_RATING);
    PerformanceMetricRules.MetricVerdict ratingVerdict = evaluate(METRIC_ACCOUNT_RATING, d("2.5"), rating);
    assertThat(ratingVerdict.severity()).isEqualTo(SEVERITY_CRITICAL);
    assertThat(ratingVerdict.thresholdValue()).isEqualByComparingTo("3.000000");

    PerformanceMetricRules.MetricVerdict ratingFine = evaluate(METRIC_ACCOUNT_RATING, d("4.5"), rating);
    assertThat(ratingFine.severity()).isNull();
  }

  // ==================================================================
  // 阈值解析顺序
  // ==================================================================

  @Test
  @DisplayName("16.1.1.2 阈值解析：本模块自定义 > 上游租户覆盖 > 内置默认")
  void thresholdResolutionOrder() {
    PerformanceMetricRules.AlertThreshold upstream =
        new PerformanceMetricRules.AlertThreshold(
            METRIC_ODR, DIRECTION_ABOVE_IS_BAD, UNIT_RATIO, d("0.0200"), d("0.0300"), 5L,
            RULE_SOURCE_UPSTREAM);
    PerformanceMetricRules.AlertThreshold module =
        new PerformanceMetricRules.AlertThreshold(
            METRIC_ODR, DIRECTION_ABOVE_IS_BAD, UNIT_RATIO, d("0.0100"), d("0.0200"), 2L,
            RULE_SOURCE_CUSTOM);

    Map<String, PerformanceMetricRules.AlertThreshold> both =
        effectiveThresholds(Map.of(METRIC_ODR, module), Map.of(METRIC_ODR, upstream));
    assertThat(both.get(METRIC_ODR).warnValue()).isEqualByComparingTo("0.0100");
    assertThat(both.get(METRIC_ODR).ruleSource()).isEqualTo(RULE_SOURCE_CUSTOM);

    Map<String, PerformanceMetricRules.AlertThreshold> upstreamOnly =
        effectiveThresholds(Map.of(), Map.of(METRIC_ODR, upstream));
    assertThat(upstreamOnly.get(METRIC_ODR).warnValue()).isEqualByComparingTo("0.0200");
    assertThat(upstreamOnly.get(METRIC_ODR).ruleSource()).isEqualTo(RULE_SOURCE_UPSTREAM);

    Map<String, PerformanceMetricRules.AlertThreshold> builtinOnly =
        effectiveThresholds(Map.of(), Map.of());
    assertThat(builtinOnly.get(METRIC_ODR).ruleSource()).isEqualTo(RULE_SOURCE_BUILTIN);
    // 🔴 本模块自有的两个码**没有**上游阈值来源，只能是默认或本模块自定义。
    assertThat(builtinOnly.get(METRIC_RESPONSE_TIME).ruleSource()).isEqualTo(RULE_SOURCE_BUILTIN);
    assertThat(builtinOnly.get(METRIC_ACCOUNT_RATING).ruleSource()).isEqualTo(RULE_SOURCE_BUILTIN);
  }

  @Test
  @DisplayName("16.1.1.2 阈值来源的人话必须说清用的是哪条阈值")
  void ruleSourceLabels() {
    assertThat(ruleSourceLabel(RULE_SOURCE_BUILTIN)).contains("内置默认");
    assertThat(ruleSourceLabel(RULE_SOURCE_CUSTOM)).contains("本模块");
    assertThat(ruleSourceLabel(RULE_SOURCE_UPSTREAM)).contains("16.1.1.1");
  }

  // ==================================================================
  // 量纲
  // ==================================================================

  @Test
  @DisplayName("16.1.1.2 量纲不可混：比值 / 小时 / 星级")
  void unitsAreNotInterchangeable() {
    assertThat(METRIC_UNITS.get(METRIC_ODR)).isEqualTo(UNIT_RATIO);
    assertThat(METRIC_UNITS.get(METRIC_VALID_TRACKING)).isEqualTo(UNIT_RATIO);
    assertThat(METRIC_UNITS.get(METRIC_RESPONSE_TIME)).isEqualTo(UNIT_HOURS);
    assertThat(METRIC_UNITS.get(METRIC_ACCOUNT_RATING)).isEqualTo(UNIT_SCORE);

    assertThat(formatValue(d("0.0280"), UNIT_RATIO)).isEqualTo("2.80%");
    assertThat(formatValue(d("24"), UNIT_HOURS)).isEqualTo("24.00 小时");
    assertThat(formatValue(d("4"), UNIT_SCORE)).isEqualTo("4.00 分");
    assertThat(formatValue(null, UNIT_RATIO)).isEqualTo("未知");
  }

  // ==================================================================
  // 均值 / 环比的分母
  // ==================================================================

  @Test
  @DisplayName("16.1.1.2 均值只用量到的点，且分母写清楚")
  void averageIgnoresUnknownButStatesDenominator() {
    List<PerformanceMetricRules.TrendPoint> points =
        List.of(
            point("2026-07-01", "2026-07-31", "0.0100"),
            point("2026-08-01", "2026-08-31", null),
            point("2026-09-01", "2026-09-30", "0.0300"));

    assertThat(averageOf(points)).isEqualByComparingTo("0.0200");
    assertThat(averageNote(points)).contains("2 个周期").contains("1 个量不到");

    List<PerformanceMetricRules.TrendPoint> allUnknown =
        List.of(point("2026-08-01", "2026-08-31", null));
    assertThat(averageOf(allUnknown)).as("一个都没量到 ⇒ 算不出均值（不是 0）").isNull();
    assertThat(averageNote(allUnknown)).contains("分母为 0");
  }

  @Test
  @DisplayName("16.1.1.2 环比：上一个周期量不到 ⇒ 不可知（不是「没变化」）")
  void changeIsUnknownWhenPreviousPeriodIsUnmeasurable() {
    List<PerformanceMetricRules.TrendPoint> withGap =
        List.of(
            point("2026-08-01", "2026-08-31", null),
            point("2026-09-01", "2026-09-30", "0.0300"));
    assertThat(changeOf(withGap)).isNull();
    assertThat(changeNote(withGap)).contains("不可知");

    List<PerformanceMetricRules.TrendPoint> solid =
        List.of(
            point("2026-08-01", "2026-08-31", "0.0100"),
            point("2026-09-01", "2026-09-30", "0.0300"));
    assertThat(changeOf(solid)).isEqualByComparingTo("0.0200");
    assertThat(changeNote(solid)).contains("上一个周期").contains("区间口径");
  }

  // ==================================================================
  // 预测
  // ==================================================================

  @Test
  @DisplayName("16.1.1.2 点数不足 ⇒ 无法预测 + 原因（绝不给假预测）")
  void forecastCannotPredictWhenNotEnoughKnownPoints() {
    PerformanceMetricRules.AlertThreshold threshold =
        BUILTIN_ALERT_THRESHOLDS.get(METRIC_ODR);
    List<PerformanceMetricRules.TrendPoint> two =
        List.of(
            point("2026-08-01", "2026-08-31", "0.0100"),
            point("2026-09-01", "2026-09-30", "0.0200"));

    PerformanceMetricRules.Forecast forecast = forecast(two, threshold, 6);

    assertThat(forecast.result()).isEqualTo(RESULT_CANNOT_PREDICT);
    assertThat(forecast.reason()).contains("已知点数不足");
    assertThat(forecast.knownPoints()).isEqualTo(2);
    assertThat(forecast.predictedValue()).isNull();
    assertThat(forecast.breachInPeriods()).isNull();
  }

  @Test
  @DisplayName("16.1.1.2 全是量不到 ⇒ 无法预测 + 原因")
  void forecastCannotPredictWhenAllUnknown() {
    PerformanceMetricRules.AlertThreshold threshold =
        BUILTIN_ALERT_THRESHOLDS.get(METRIC_ODR);
    List<PerformanceMetricRules.TrendPoint> allNull =
        List.of(
            point("2026-07-01", "2026-07-31", null),
            point("2026-08-01", "2026-08-31", null),
            point("2026-09-01", "2026-09-30", null));

    PerformanceMetricRules.Forecast forecast = forecast(allNull, threshold, 6);

    assertThat(forecast.result()).isEqualTo(RESULT_CANNOT_PREDICT);
    assertThat(forecast.reason()).contains("一个量到的周期都没有");
    assertThat(forecast.knownPoints()).isZero();
    assertThat(forecast.unknownPoints()).isEqualTo(3);
  }

  @Test
  @DisplayName("16.1.1.2 上涨趋势 ⇒ 会跌破：说清第几个周期")
  void forecastPredictsBreachOnRisingSeries() {
    PerformanceMetricRules.AlertThreshold threshold =
        new PerformanceMetricRules.AlertThreshold(
            METRIC_ODR, DIRECTION_ABOVE_IS_BAD, UNIT_RATIO, d("0.0300"), d("0.0400"), 1L,
            RULE_SOURCE_CUSTOM);
    List<PerformanceMetricRules.TrendPoint> rising =
        List.of(
            point("2026-07-01", "2026-07-31", "0.0100"),
            point("2026-08-01", "2026-08-31", "0.0150"),
            point("2026-09-01", "2026-09-30", "0.0200"));

    PerformanceMetricRules.Forecast forecast = forecast(rising, threshold, 6);

    assertThat(forecast.result()).isEqualTo(RESULT_WILL_BREACH);
    assertThat(forecast.breachLevel()).isEqualTo(THRESHOLD_WARN);
    assertThat(forecast.breachInPeriods()).isEqualTo(2);
    assertThat(forecast.predictedValue()).isEqualByComparingTo("0.030000");
    assertThat(forecast.reason()).contains("第 2 个周期").contains("最小二乘线性外推");
    assertThat(forecast.knownPoints()).isEqualTo(3);
  }

  @Test
  @DisplayName("16.1.1.2 评级下滑 ⇒ 会跌破（BELOW_IS_BAD 方向）")
  void forecastPredictsBreachOnFallingRating() {
    PerformanceMetricRules.AlertThreshold threshold =
        BUILTIN_ALERT_THRESHOLDS.get(METRIC_ACCOUNT_RATING);
    List<PerformanceMetricRules.TrendPoint> falling =
        List.of(
            point("2026-07-01", "2026-07-31", "4.8"),
            point("2026-08-01", "2026-08-31", "4.6"),
            point("2026-09-01", "2026-09-30", "4.4"));

    PerformanceMetricRules.Forecast forecast = forecast(falling, threshold, 6);

    assertThat(forecast.result()).isEqualTo(RESULT_WILL_BREACH);
    assertThat(forecast.breachLevel()).isEqualTo(THRESHOLD_WARN);
    assertThat(forecast.breachInPeriods()).isEqualTo(2);
    assertThat(forecast.predictedValue()).isEqualByComparingTo("4.000000");
  }

  @Test
  @DisplayName("16.1.1.2 趋势远离阈值 ⇒ 按当前趋势不会跌破")
  void forecastSaysNoBreachWhenTrendMovesAway() {
    PerformanceMetricRules.AlertThreshold threshold =
        new PerformanceMetricRules.AlertThreshold(
            METRIC_ODR, DIRECTION_ABOVE_IS_BAD, UNIT_RATIO, d("0.0250"), d("0.0300"), 1L,
            RULE_SOURCE_CUSTOM);
    List<PerformanceMetricRules.TrendPoint> falling =
        List.of(
            point("2026-07-01", "2026-07-31", "0.0300"),
            point("2026-08-01", "2026-08-31", "0.0250"),
            point("2026-09-01", "2026-09-30", "0.0200"));

    PerformanceMetricRules.Forecast forecast = forecast(falling, threshold, 6);

    assertThat(forecast.result()).isEqualTo(RESULT_NO_BREACH);
    assertThat(forecast.reason()).contains("不会");
    assertThat(forecast.breachInPeriods()).isNull();
    assertThat(forecast.slope()).isNotNull();
  }

  @Test
  @DisplayName("16.1.1.2 已经越线 ⇒ 报「已经越线（现状）」，不冒充预测")
  void forecastReportsAlreadyBreached() {
    PerformanceMetricRules.AlertThreshold threshold =
        BUILTIN_ALERT_THRESHOLDS.get(METRIC_ODR);
    List<PerformanceMetricRules.TrendPoint> breaching =
        List.of(
            point("2026-07-01", "2026-07-31", "0.0050"),
            point("2026-08-01", "2026-08-31", "0.0100"),
            point("2026-09-01", "2026-09-30", "0.0200"));

    PerformanceMetricRules.Forecast forecast = forecast(breaching, threshold, 6);

    assertThat(forecast.result()).isEqualTo(RESULT_ALREADY_BREACHED);
    assertThat(forecast.breachLevel()).isEqualTo(THRESHOLD_WARN);
    assertThat(forecast.reason()).contains("现状");
    assertThat(forecast.breachInPeriods()).isNull();
  }

  @Test
  @DisplayName("16.1.1.2 预测保留周期序号：量不到的周期占位但不进回归")
  void forecastKeepsGapsInTheXAxis() {
    PerformanceMetricRules.AlertThreshold threshold =
        new PerformanceMetricRules.AlertThreshold(
            METRIC_ODR, DIRECTION_ABOVE_IS_BAD, UNIT_RATIO, d("0.1000"), d("0.2000"), 1L,
            RULE_SOURCE_CUSTOM);
    List<PerformanceMetricRules.TrendPoint> withGap =
        List.of(
            point("2026-06-01", "2026-06-30", "0.0100"),
            point("2026-07-01", "2026-07-31", null),
            point("2026-08-01", "2026-08-31", "0.0200"),
            point("2026-09-01", "2026-09-30", "0.0300"));

    PerformanceMetricRules.Forecast forecast = forecast(withGap, threshold, 6);

    assertThat(forecast.knownPoints()).isEqualTo(3);
    assertThat(forecast.unknownPoints()).isEqualTo(1);
    // 🔴 如果把空缺周期「压缩掉」，斜率会是 0.010000；保留序号则是 0.006429。
    assertThat(forecast.slope()).isEqualByComparingTo("0.006429");
    assertThat(forecast.usedPeriods())
        .extracting(PerformanceMetricRules.UsedPeriod::index)
        .containsExactly(0, 2, 3);
  }

  @Test
  @DisplayName("16.1.1.2 预测口径说明必须写明模型与窗口")
  void methodNoteStatesTheModel() {
    String note = methodNote(6, 3, 6);
    assertThat(note).contains("最小二乘线性回归").contains("最后一个量到的周期").contains("不是 0");
  }

  @Test
  @DisplayName("16.1.1.2 预测结论的人话")
  void resultLabels() {
    assertThat(resultLabel(RESULT_WILL_BREACH)).contains("会跌破");
    assertThat(resultLabel(RESULT_NO_BREACH)).contains("不会跌破");
    assertThat(resultLabel(RESULT_ALREADY_BREACHED)).contains("已经");
    assertThat(resultLabel(RESULT_CANNOT_PREDICT)).contains("无法预测");
  }

  // ==================================================================
  // 双来源的事实（词表层面）
  // ==================================================================

  @Test
  @DisplayName("16.1.1.2 双来源：四个码归上游、两个码归本模块，互补不重叠")
  void twoSourcesAreComplementary() {
    assertThat(UPSTREAM_METRIC_CODES).containsExactlyInAnyOrder(
        METRIC_ODR, METRIC_LATE_SHIPMENT, METRIC_CANCELLATION, METRIC_VALID_TRACKING);
    assertThat(OWN_METRIC_CODES).containsExactlyInAnyOrder(
        METRIC_RESPONSE_TIME, METRIC_ACCOUNT_RATING);
    assertThat(METRIC_CODES).hasSize(6);
    // 🔴 两个集合**不重叠**：同一指标不会有第二个真相来源。
    assertThat(UPSTREAM_METRIC_CODES).doesNotContainAnyElementsOf(OWN_METRIC_CODES);
    assertThat(METRIC_CODES).containsAll(UPSTREAM_METRIC_CODES).containsAll(OWN_METRIC_CODES);
  }
}
