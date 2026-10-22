package com.starcon.channel;

import static com.starcon.channel.AccountHealthRules.*;
import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 16.1.1.1 账号健康总览 <b>规则层</b>的单测（不碰数据库、不碰 HTTP）。
 *
 * <p>🔴 这组用例守的是四件最容易做丢的事：
 *
 * <ol>
 *   <li><b>量不到 ≠ 0</b>：值为 NULL 的指标不进已知数、<b>不算通过</b>；</li>
 *   <li><b>量不到 ≠ 低风险</b>：缺一项 ⇒ 等级 UNKNOWN，<b>不是</b> HEALTHY；</li>
 *   <li><b>方向要对</b>：有效追踪率是「越小越危险」，不能拿「越大越危险」去比；</li>
 *   <li><b>排序真的按风险排</b>，且 UNKNOWN 排在 HEALTHY 前面。</li>
 * </ol>
 */
class AccountHealthRulesTest {

  private static BigDecimal value(String raw) {
    return new BigDecimal(raw);
  }

  private static MetricReading reading(String metricCode, String raw) {
    return new MetricReading(
        metricCode,
        raw == null ? null : value(raw),
        "MANUAL",
        "2026-10-01",
        "2026-10-31",
        "2026-11-01T00:00:00Z",
        "");
  }

  private static Map<String, MetricReading> readings(String... pairs) {
    Map<String, MetricReading> map = new LinkedHashMap<>();
    for (int index = 0; index + 1 < pairs.length; index += 2) {
      map.put(pairs[index], reading(pairs[index], pairs[index + 1]));
    }
    return map;
  }

  private static Map<String, Threshold> builtin() {
    return effectiveThresholds(Map.of());
  }

  // ---------------------------------------------------------------- 阈值

  @Test
  @DisplayName("内置默认阈值：四项齐全，方向各自正确")
  void builtinThresholdsCoverAllMetrics() {
    Map<String, Threshold> thresholds = builtin();
    assertThat(thresholds).hasSize(4);
    assertThat(thresholds.keySet()).containsExactlyInAnyOrderElementsOf(METRIC_CODES);
    assertThat(thresholds.get(METRIC_ODR).direction()).isEqualTo(DIRECTION_ABOVE_IS_BAD);
    assertThat(thresholds.get(METRIC_LATE_SHIPMENT).direction()).isEqualTo(DIRECTION_ABOVE_IS_BAD);
    assertThat(thresholds.get(METRIC_CANCELLATION).direction()).isEqualTo(DIRECTION_ABOVE_IS_BAD);
    assertThat(thresholds.get(METRIC_VALID_TRACKING).direction()).isEqualTo(DIRECTION_BELOW_IS_BAD);
    assertThat(thresholds.values())
        .allSatisfy(
            threshold -> {
              assertThat(threshold.warnValue()).isBetween(BigDecimal.ZERO, BigDecimal.ONE);
              assertThat(threshold.criticalValue()).isBetween(BigDecimal.ZERO, BigDecimal.ONE);
              assertThat(threshold.ruleSource()).isEqualTo(RULE_SOURCE_BUILTIN);
            });
  }

  @Test
  @DisplayName("租户覆盖优先于内置默认；未覆盖的仍用默认")
  void tenantOverrideWins() {
    Map<String, Threshold> overrides =
        Map.of(
            METRIC_ODR,
            new Threshold(
                METRIC_ODR,
                DIRECTION_ABOVE_IS_BAD,
                value("0.0050"),
                value("0.0080"),
                3L,
                RULE_SOURCE_CUSTOM));
    Map<String, Threshold> thresholds = effectiveThresholds(overrides);

    assertThat(thresholds.get(METRIC_ODR).ruleSource()).isEqualTo(RULE_SOURCE_CUSTOM);
    assertThat(thresholds.get(METRIC_ODR).ruleVersion()).isEqualTo(3);
    assertThat(thresholds.get(METRIC_LATE_SHIPMENT).ruleSource()).isEqualTo(RULE_SOURCE_BUILTIN);

    assertThat(ruleSourceOf(thresholds)).isEqualTo(RULE_SOURCE_MIXED);
    assertThat(ruleVersionOf(thresholds)).isEqualTo(3);
    assertThat(ruleSourceOf(builtin())).isEqualTo(RULE_SOURCE_BUILTIN);
  }

  // ---------------------------------------------------------------- 判定

  @Test
  @DisplayName("🔴 一项都量不到 ⇒ UNKNOWN（不是 0 分、不是低风险）")
  void noMetricsAtAllGivesUnknown() {
    var evaluation = evaluate(readings(), builtin());
    assertThat(evaluation.riskLevel()).isEqualTo(LEVEL_UNKNOWN);
    assertThat(evaluation.knownMetricCount()).isZero();
    assertThat(evaluation.breachCount()).isZero();
    assertThat(evaluation.reasons()).hasSize(4);
    assertThat(evaluation.reasons())
        .allSatisfy(reason -> assertThat(reason.thresholdKind()).isEqualTo(THRESHOLD_UNKNOWN));
  }

  @Test
  @DisplayName("🔴 缺一项 ⇒ UNKNOWN（不能断言健康），但量到的那几项仍给出依据")
  void partialCoverageGivesUnknownNotHealthy() {
    var evaluation =
        evaluate(
            readings(
                METRIC_ODR, "0.0050",
                METRIC_LATE_SHIPMENT, "0.0100",
                METRIC_CANCELLATION, "0.0100"),
            builtin());

    assertThat(evaluation.riskLevel()).isEqualTo(LEVEL_UNKNOWN);
    assertThat(evaluation.knownMetricCount()).isEqualTo(3);
    assertThat(evaluation.breachCount()).isZero();
    assertThat(evaluation.unknownMetricCodes()).containsExactly(METRIC_VALID_TRACKING);
  }

  @Test
  @DisplayName("四项齐全且都未命中 ⇒ HEALTHY")
  void allGoodGivesHealthy() {
    var evaluation =
        evaluate(
            readings(
                METRIC_ODR, "0.0050",
                METRIC_LATE_SHIPMENT, "0.0100",
                METRIC_CANCELLATION, "0.0100",
                METRIC_VALID_TRACKING, "0.9900"),
            builtin());
    assertThat(evaluation.riskLevel()).isEqualTo(LEVEL_HEALTHY);
    assertThat(evaluation.knownMetricCount()).isEqualTo(4);
    assertThat(evaluation.breachCount()).isZero();
  }

  @Test
  @DisplayName("命中严重阈值 ⇒ CRITICAL，依据里有值、有阈值、有档位")
  void criticalBreachCarriesEvidence() {
    var evaluation =
        evaluate(
            readings(
                METRIC_ODR, "0.0500",
                METRIC_LATE_SHIPMENT, "0.0100",
                METRIC_CANCELLATION, "0.0100",
                METRIC_VALID_TRACKING, "0.9900"),
            builtin());

    assertThat(evaluation.riskLevel()).isEqualTo(LEVEL_CRITICAL);
    assertThat(evaluation.breachCount()).isEqualTo(1);

    var reason =
        evaluation.reasons().stream()
            .filter(r -> r.metricCode().equals(METRIC_ODR))
            .findFirst()
            .orElseThrow();
    assertThat(reason.level()).isEqualTo(LEVEL_CRITICAL);
    assertThat(reason.thresholdKind()).isEqualTo(THRESHOLD_CRITICAL);
    assertThat(reason.metricValue()).isEqualByComparingTo("0.0500");
    assertThat(reason.thresholdValue()).isEqualByComparingTo("0.0280");
    assertThat(reason.message()).contains("5.00%").contains("2.80%");
  }

  @Test
  @DisplayName("命中警告阈值 ⇒ WARNING")
  void warningBreachGivesWarning() {
    var evaluation =
        evaluate(
            readings(
                METRIC_ODR, "0.0150",
                METRIC_LATE_SHIPMENT, "0.0100",
                METRIC_CANCELLATION, "0.0100",
                METRIC_VALID_TRACKING, "0.9900"),
            builtin());
    assertThat(evaluation.riskLevel()).isEqualTo(LEVEL_WARNING);
    assertThat(evaluation.breachCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("🔴 有效追踪率方向相反：95% ⇒ CRITICAL（不是「越大越危险」）")
  void validTrackingUsesBelowIsBad() {
    var evaluation =
        evaluate(
            readings(
                METRIC_ODR, "0.0050",
                METRIC_LATE_SHIPMENT, "0.0100",
                METRIC_CANCELLATION, "0.0100",
                METRIC_VALID_TRACKING, "0.9500"),
            builtin());
    assertThat(evaluation.riskLevel()).isEqualTo(LEVEL_CRITICAL);

    var evaluation2 =
        evaluate(
            readings(
                METRIC_ODR, "0.0050",
                METRIC_LATE_SHIPMENT, "0.0100",
                METRIC_CANCELLATION, "0.0100",
                METRIC_VALID_TRACKING, "0.9700"),
            builtin());
    assertThat(evaluation2.riskLevel())
        .as("97% 高于警告阈值 98%? 不 —— 97% < 98% ⇒ 命中警告")
        .isEqualTo(LEVEL_WARNING);
  }

  @Test
  @DisplayName("量到但没有阈值 ⇒ 判不了（不是合格）")
  void metricWithoutThresholdIsUnknown() {
    Map<String, Threshold> thresholds = new LinkedHashMap<>(builtin());
    thresholds.remove(METRIC_ODR);
    var evaluation =
        evaluate(
            readings(
                METRIC_ODR, "0.0050",
                METRIC_LATE_SHIPMENT, "0.0100",
                METRIC_CANCELLATION, "0.0100",
                METRIC_VALID_TRACKING, "0.9900"),
            thresholds);
    assertThat(evaluation.riskLevel()).isEqualTo(LEVEL_UNKNOWN);
    assertThat(evaluation.knownMetricCount()).isEqualTo(3);
    assertThat(evaluation.unknownMetricCodes()).containsExactly(METRIC_ODR);
  }

  // ---------------------------------------------------------------- 排序

  @Test
  @DisplayName("🔴 排序：CRITICAL → WARNING → UNKNOWN → HEALTHY")
  void rankingOrdersByRisk() {
    var critical =
        new RankedShop(
            "3",
            "C",
            "严重店",
            "AMAZON",
            evaluate(
                readings(
                    METRIC_ODR, "0.0500",
                    METRIC_LATE_SHIPMENT, "0.0100",
                    METRIC_CANCELLATION, "0.0100",
                    METRIC_VALID_TRACKING, "0.9900"),
                builtin()));
    var warning =
        new RankedShop(
            "2",
            "W",
            "预警店",
            "AMAZON",
            evaluate(
                readings(
                    METRIC_ODR, "0.0150",
                    METRIC_LATE_SHIPMENT, "0.0100",
                    METRIC_CANCELLATION, "0.0100",
                    METRIC_VALID_TRACKING, "0.9900"),
                builtin()));
    var unknown =
        new RankedShop(
            "4",
            "U",
            "数据不全店",
            "AMAZON",
            evaluate(readings(METRIC_ODR, "0.0050"), builtin()));
    var healthy =
        new RankedShop(
            "1",
            "H",
            "健康店",
            "AMAZON",
            evaluate(
                readings(
                    METRIC_ODR, "0.0050",
                    METRIC_LATE_SHIPMENT, "0.0100",
                    METRIC_CANCELLATION, "0.0100",
                    METRIC_VALID_TRACKING, "0.9900"),
                builtin()));

    List<RankedShop> sorted = new java.util.ArrayList<>(List.of(healthy, unknown, warning, critical));
    sorted.sort(ranking());

    assertThat(sorted).extracting(RankedShop::shopCode).containsExactly("C", "W", "U", "H");
    assertThat(levelRank(LEVEL_CRITICAL)).isLessThan(levelRank(LEVEL_WARNING));
    assertThat(levelRank(LEVEL_WARNING)).isLessThan(levelRank(LEVEL_UNKNOWN));
    assertThat(levelRank(LEVEL_UNKNOWN)).isLessThan(levelRank(LEVEL_HEALTHY));
  }

  @Test
  @DisplayName("同级并列时：命中多的先、越线狠的先、编码稳定兜底")
  void rankingTieBreaksDeterministically() {
    var first =
        new RankedShop(
            "2",
            "B",
            "B 店",
            "AMAZON",
            evaluate(
                readings(
                    METRIC_ODR, "0.0900",
                    METRIC_LATE_SHIPMENT, "0.2000",
                    METRIC_CANCELLATION, "0.0100",
                    METRIC_VALID_TRACKING, "0.9900"),
                builtin()));
    var second =
        new RankedShop(
            "1",
            "A",
            "A 店",
            "AMAZON",
            evaluate(
                readings(
                    METRIC_ODR, "0.0300",
                    METRIC_LATE_SHIPMENT, "0.0100",
                    METRIC_CANCELLATION, "0.0100",
                    METRIC_VALID_TRACKING, "0.9900"),
                builtin()));

    List<RankedShop> sorted = new java.util.ArrayList<>(List.of(second, first));
    sorted.sort(ranking());
    assertThat(sorted).extracting(RankedShop::shopCode).containsExactly("B", "A");
  }

  // ---------------------------------------------------------------- 展示口径

  @Test
  @DisplayName("比值 → 百分数（0.0280 ⇒ 2.80%）")
  void percentFormatsRatio() {
    assertThat(percent(value("0.0280"))).isEqualTo("2.80%");
    assertThat(percent(value("0.9900"))).isEqualTo("99.00%");
    assertThat(percent(null)).isEqualTo("未知");
  }

  @Test
  @DisplayName("等级与来源的人话")
  void labelsAreHumanReadable() {
    assertThat(levelLabel(LEVEL_CRITICAL)).contains("严重");
    assertThat(levelLabel(LEVEL_WARNING)).isEqualTo("预警");
    assertThat(levelLabel(LEVEL_HEALTHY)).isEqualTo("健康");
    assertThat(levelLabel(LEVEL_UNKNOWN)).contains("判不了");
    assertThat(sourceLabel(SOURCE_MANUAL)).isEqualTo("人工录入");
    assertThat(sourceLabel(SOURCE_PLATFORM_SYNC)).isEqualTo("平台同步");
  }
}
