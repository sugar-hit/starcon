package com.starcon.channel;

import static com.starcon.channel.ChannelScoringEngine.Candidate;
import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 10.1.2.2 渠道比价 的<b>评分引擎</b>单测。
 *
 * <p>{@link ChannelScoringEngine} 是纯函数（不读数据库、无策略常量），所以归一化的边界
 * 可以在毫秒内钉死，不必起容器。这里钉的是集成测试钉不到的那几类：
 *
 * <ul>
 *   <li><b>全部同价 / 全部同时效</b> ⇒ 一律 100 分，不给谁扣 0 分
 *   <li><b>只有一条可比候选</b> ⇒ 三维都是 100，不因为「没有对手」就打成 0
 *   <li><b>不可比返回 null 而不是 0</b>（0 分与「没数据」含义相反）
 *   <li><b>权重归一化</b>与「全零权重被拒」
 *   <li><b>投诉率取倒数</b>：投诉越多得分越低，绝不反向
 * </ul>
 */
class ChannelScoringEngineTest {

  static final ChannelScoringEngine ENGINE = ChannelScoringEngine.INSTANCE;

  static BigDecimal bd(String value) {
    return new BigDecimal(value);
  }

  static Candidate candidate(
      String charge, String currency, Integer transit, String success, String onTime, String complaint) {
    return new Candidate(
        UUID.randomUUID(),
        charge == null ? null : bd(charge),
        currency,
        transit,
        success == null ? null : bd(success),
        onTime == null ? null : bd(onTime),
        complaint == null ? null : bd(complaint),
        true);
  }

  static Candidate excluded() {
    return new Candidate(UUID.randomUUID(), null, "USD", null, null, null, null, false);
  }

  static ChannelScoringEngine.Weights weights() {
    return ENGINE.withReliabilityWeights(ENGINE.normalize(bd("0.4"), bd("0.3"), bd("0.3")),
        bd("0.4"), bd("0.4"), bd("0.2"));
  }

  // ==================================================================
  // 权重
  // ==================================================================

  @Test
  @DisplayName("🔴 权重归一化到 1；三个全 0 被拒（那不叫「都不重要」，那叫没选目标）")
  void weightsAreNormalized() {
    var w = ENGINE.normalize(bd("2"), bd("1"), bd("1"));
    assertThat(w.cost()).isEqualByComparingTo("0.500000");
    assertThat(w.transit()).isEqualByComparingTo("0.250000");
    assertThat(w.reliability()).isEqualByComparingTo("0.250000");

    assertThatThrownBy(() -> ENGINE.normalize(bd("0"), bd("0"), bd("0")))
        .hasMessageContaining("权重");
    assertThatThrownBy(() -> ENGINE.withReliabilityWeights(w, bd("0"), bd("0"), bd("0")))
        .hasMessageContaining("子权重");
  }

  // ==================================================================
  // 成本归一化
  // ==================================================================

  @Test
  @DisplayName("最便宜 100 分、最贵 0 分，中间线性")
  void costNormalizesLinearly() {
    var scope = List.of(candidate("10", "USD", 5, "0.99", "0.98", "0.01"),
        candidate("30", "USD", 5, "0.99", "0.98", "0.01"),
        candidate("20", "USD", 5, "0.99", "0.98", "0.01"));
    var span = ENGINE.costScores(scope);
    assertThat(span.min()).isEqualByComparingTo("10");
    assertThat(span.max()).isEqualByComparingTo("30");
    assertThat(ENGINE.costScore(scope.get(0), span.min(), span.max())).isEqualByComparingTo("100");
    assertThat(ENGINE.costScore(scope.get(1), span.min(), span.max())).isEqualByComparingTo("0");
    assertThat(ENGINE.costScore(scope.get(2), span.min(), span.max())).isEqualByComparingTo("50");
  }

  @Test
  @DisplayName("🔴 全部同价一律 100 分（不给谁扣 0：它们并列最好，没有差距就是没有差距）")
  void equalCostsAllScoreHundred() {
    var scope = List.of(candidate("25", "USD", 5, "0.99", "0.98", "0.01"),
        candidate("25", "USD", 6, "0.99", "0.98", "0.01"));
    var span = ENGINE.costScores(scope);
    assertThat(ENGINE.costScore(scope.get(0), span.min(), span.max())).isEqualByComparingTo("100");
    assertThat(ENGINE.costScore(scope.get(1), span.min(), span.max())).isEqualByComparingTo("100");
  }

  @Test
  @DisplayName("🔴 只有一条可比候选时成本分也是 100（不因为「没有对手」就打成 0）")
  void singleCandidateScoresHundred() {
    var only = candidate("77", "USD", 5, "0.99", "0.98", "0.01");
    var span = ENGINE.costScores(List.of(only));
    assertThat(ENGINE.costScore(only, span.min(), span.max())).isEqualByComparingTo("100");
    assertThat(ENGINE.transitScore(only, ENGINE.transitSpan(List.of(only)))).isEqualByComparingTo("100");
  }

  @Test
  @DisplayName("🔴 不可比的候选不进归一化区间（它不能撑大区间，也不能被算成最便宜）")
  void excludedCandidatesDoNotStretchTheSpan() {
    var cheap = candidate("10", "USD", 5, "0.99", "0.98", "0.01");
    var dear = candidate("30", "USD", 5, "0.99", "0.98", "0.01");
    var span = ENGINE.costScores(List.of(cheap, dear, excluded()));
    assertThat(span.min()).isEqualByComparingTo("10");
    assertThat(span.max()).isEqualByComparingTo("30");
    assertThat(ENGINE.costScore(excluded(), span.min(), span.max())).isNull();
  }

  @Test
  @DisplayName("🔴 算不出钱的候选返回 null 而不是 0（0 分会被读成「比过了，最差」）")
  void unpricedIsNullNotZero() {
    var unpriced = candidate(null, "USD", 5, "0.99", "0.98", "0.01");
    var scope = List.of(candidate("30", "USD", 5, "0.99", "0.98", "0.01"), unpriced);
    var span = ENGINE.costScores(scope);
    assertThat(ENGINE.costScore(unpriced, span.min(), span.max())).isNull();
    assertThat(unpriced.totalCharge()).isNull();
  }

  // ==================================================================
  // 时效归一化
  // ==================================================================

  @Test
  @DisplayName("时效：最快 100 分、最慢 0 分；没登记时效返回 null（不编天数）")
  void transitNormalizesAndMissingIsNull() {
    var fast = candidate("30", "USD", 3, "0.99", "0.98", "0.01");
    var slow = candidate("10", "USD", 20, "0.99", "0.98", "0.01");
    var unknown = candidate("10", "USD", null, "0.99", "0.98", "0.01");
    var span = ENGINE.transitSpan(List.of(fast, slow, unknown));
    assertThat(span.min()).isEqualTo(3);
    assertThat(span.max()).isEqualTo(20);
    assertThat(ENGINE.transitScore(fast, span)).isEqualByComparingTo("100");
    assertThat(ENGINE.transitScore(slow, span)).isEqualByComparingTo("0");
    assertThat(ENGINE.transitScore(unknown, span)).isNull();
    assertThat(unknown.transitBasisDays()).isNull();
  }

  @Test
  @DisplayName("时效全部相同时一律 100 分")
  void equalTransitAllScoreHundred() {
    var a = candidate("30", "USD", 7, "0.99", "0.98", "0.01");
    var b = candidate("10", "USD", 7, "0.99", "0.98", "0.01");
    var span = ENGINE.transitSpan(List.of(a, b));
    assertThat(ENGINE.transitScore(a, span)).isEqualByComparingTo("100");
    assertThat(ENGINE.transitScore(b, span)).isEqualByComparingTo("100");
  }

  // ==================================================================
  // 可靠性分
  // ==================================================================

  @Test
  @DisplayName("🔴 投诉率取倒数参与：投诉翻倍，可靠性分必须下降（不是反向）")
  void complaintRateCountsInversely() {
    var w = weights();
    var clean = candidate("30", "USD", 5, "0.9900", "0.9800", "0.0100");
    var lossy = candidate("30", "USD", 5, "0.9900", "0.9800", "0.0800");
    assertThat(ENGINE.reliabilityScore(lossy, w))
        .as("投诉率 8% 的渠道可靠性分必须低于投诉率 1% 的渠道")
        .isLessThan(ENGINE.reliabilityScore(clean, w));
  }

  @Test
  @DisplayName("🔴 缺任一维返回 null（不拿缺的当 0：0 会被读成「投诉率 100%」）")
  void missingDimensionIsNullNotZero() {
    var w = weights();
    var partial = candidate("30", "USD", 5, "0.9900", null, "0.0100");
    assertThat(ENGINE.reliabilityScore(partial, w)).isNull();
    assertThat(ENGINE.reliabilityScore(candidate("30", "USD", 5, null, "0.98", "0.01"), w)).isNull();
    assertThat(ENGINE.reliabilityScore(candidate("30", "USD", 5, "0.99", "0.98", null), w)).isNull();
  }

  @Test
  @DisplayName("可靠性分 = 成功率×权重 + 时效达成率×权重 + 无投诉×权重")
  void reliabilityFormulaIsExplicit() {
    var w = weights();
    // 99%×0.4 + 98%×0.4 + 99%×0.2 = 39.6 + 39.2 + 19.8 = 98.6
    assertThat(ENGINE.reliabilityScore(candidate("30", "USD", 5, "0.9900", "0.9800", "0.0100"), w))
        .isEqualByComparingTo("98.6000");
  }

  // ==================================================================
  // 综合分
  // ==================================================================

  @Test
  @DisplayName("🔴 综合分 = 三维加权；任一维不可比则综合分为 null（不把缺失的维度摊到别处）")
  void compositeNeedsAllThreeDimensions() {
    var w = weights();
    var full = ENGINE.composite(bd("100"), bd("50"), bd("80"), w);
    // 0.4×100 + 0.3×50 + 0.3×80 = 40 + 15 + 24 = 79
    assertThat(full).isEqualByComparingTo("79.0000");
    assertThat(ENGINE.composite(bd("100"), null, bd("80"), w)).isNull();
    assertThat(ENGINE.composite(null, bd("50"), bd("80"), w)).isNull();
  }

  // ==================================================================
  // 三个闸
  // ==================================================================

  @Test
  @DisplayName("🔴 投诉率红线：超线拦下，贴线放行（比较是严格大于）")
  void riskGateUsesStrictlyGreater() {
    var at5 = candidate("30", "USD", 5, "0.99", "0.98", "0.0500");
    var over = candidate("30", "USD", 5, "0.99", "0.98", "0.0501");
    assertThat(ENGINE.riskGate(at5, bd("5"))).isNull();
    assertThat(ENGINE.riskGate(over, bd("5"))).contains("投诉率").contains("红线");
  }

  @Test
  @DisplayName("🔴 最小样本量：低于门槛拦下，恰好等于门槛放行")
  void sampleGateUsesStrictlyLess() {
    var few = candidate("30", "USD", 5, "1.0000", "1.0000", "0.0000");
    assertThat(ENGINE.sampleGate(29, 30)).contains("样本量").contains("29");
    assertThat(ENGINE.sampleGate(30, 30)).isNull();
    assertThat(ENGINE.sampleGate(null, 30)).contains("没有渠道评分");
    // 3 单全到的成功率确实是 100% —— 数字漂亮，但这正是样本闸要拦的情形
    assertThat(few.successRate()).isEqualByComparingTo("1.0000");
  }

  @Test
  @DisplayName("🔴 评分新鲜度：按「比价时刻 − 观测时刻」的天数判定，超过上限拦下")
  void stalenessGateCountsDays() {
    var comparedAt = OffsetDateTime.parse("2026-10-15T09:00:00+08:00");
    var fresh = OffsetDateTime.parse("2026-09-15T09:00:00+08:00");   // 30 天
    var stale = OffsetDateTime.parse("2026-04-01T09:00:00+08:00");   // 197 天
    assertThat(ENGINE.stalenessGate(fresh, comparedAt, 90)).isNull();
    assertThat(ENGINE.stalenessGate(stale, comparedAt, 90)).contains("天").contains("90");
  }

  // ==================================================================
  // 算式明细
  // ==================================================================

  @Test
  @DisplayName("🔴 算式明细逐条给出，不是一团；不可比的维度也说清「为什么不可比」")
  void breakdownIsPerDimensionAndExplainsMissing() {
    var w = weights();
    var good = candidate("30", "USD", 5, "0.9900", "0.9800", "0.0100");
    var noTransit = candidate("30", "USD", null, "0.9900", "0.9800", "0.0100");
    var span = ENGINE.transitSpan(List.of(good, noTransit));
    var costSpan = ENGINE.costScores(List.of(good, noTransit));

    var text = ENGINE.breakdown(good, w,
        ENGINE.costScore(good, costSpan.min(), costSpan.max()),
        ENGINE.transitScore(good, span),
        ENGINE.reliabilityScore(good, w),
        ENGINE.composite(bd("100"), ENGINE.transitScore(good, span), ENGINE.reliabilityScore(good, w), w),
        "投诉率超红线", null, null);
    assertThat(text).anyMatch(s -> s.startsWith("成本分"));
    assertThat(text).anyMatch(s -> s.startsWith("时效分"));
    assertThat(text).anyMatch(s -> s.startsWith("可靠性分"));
    assertThat(text).anyMatch(s -> s.startsWith("综合分"));
    assertThat(text).anyMatch(s -> s.startsWith("风险闸") && s.contains("超红线"));

    var missing = ENGINE.breakdown(noTransit, w,
        ENGINE.costScore(noTransit, costSpan.min(), costSpan.max()),
        ENGINE.transitScore(noTransit, span),
        ENGINE.reliabilityScore(noTransit, w),
        ENGINE.composite(bd("100"), ENGINE.transitScore(noTransit, span),
            ENGINE.reliabilityScore(noTransit, w), w),
        null, null, null);
    assertThat(missing).anyMatch(s -> s.contains("时效分：不可比") && s.contains("不编天数"));
    // 缺一维则综合分不可算，明细里不该出现一条凭空算出来的综合分
    assertThat(missing).noneMatch(s -> s.startsWith("综合分"));
  }
}
