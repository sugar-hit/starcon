package com.starcon.channel;

import static org.assertj.core.api.Assertions.*;

import com.starcon.channel.InfluencerRules.RatingRule;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 14.5.1.1 达人库 的**纯计算规则**单测（不碰数据库）。
 *
 * <p>本类钉住评分口径最容易做丢的两件事：
 *
 * <ol>
 *   <li><b>「没录」不是 0 分</b>：必需的效果数据缺任何一项 ⇒ 没有评分（null）。
 *       0 分会被读成「这个达人很差」，而真相是「你还没录这个达人的效果数据」。
 *   <li><b>退款率是负向指标</b>：退款率 0 拿满分，退款率触顶拿 0 分；
 *       权重为 0 的那一项**不要求**入库（配了 0 权重却还要求数据，是口径自相矛盾）。
 * </ol>
 */
class InfluencerRulesTest {

  private static final BigDecimal ZERO = new BigDecimal("0");
  private static final BigDecimal ONE = new BigDecimal("1");

  /** 一套标准口径：GMV 0.5 / 互动率 0.3 / 退款率 0.2，上限分别是 10 万 / 0.1 / 0.3。 */
  private static RatingRule rule() {
    return new RatingRule(
        new BigDecimal("0.5"),
        new BigDecimal("0.3"),
        new BigDecimal("0.2"),
        new BigDecimal("100000"),
        new BigDecimal("0.1"),
        new BigDecimal("0.3"));
  }

  @Test
  @DisplayName("评分口径：三项都齐时按权重加总")
  void scoreIsWeightedSum() {
    BigDecimal score =
        InfluencerRules.score(
            rule(), new BigDecimal("50000"), new BigDecimal("0.05"), new BigDecimal("0.03"));
    // 0.5×0.5 + 0.3×0.5 + 0.2×0.9 = 0.25 + 0.15 + 0.18 = 0.58
    assertThat(score).isEqualByComparingTo(new BigDecimal("0.58"));
  }

  @Test
  @DisplayName("🔴 缺必需的效果数据 ⇒ 没有评分（null），不是 0 分")
  void missingEffectDataMeansNoScore() {
    RatingRule r = rule();
    assertThat(InfluencerRules.score(r, null, new BigDecimal("0.05"), new BigDecimal("0.03")))
        .as("缺 GMV ⇒ 没有评分")
        .isNull();
    assertThat(InfluencerRules.score(r, new BigDecimal("50000"), null, new BigDecimal("0.03")))
        .as("缺互动率 ⇒ 没有评分")
        .isNull();
    assertThat(InfluencerRules.score(r, new BigDecimal("50000"), new BigDecimal("0.05"), null))
        .as("缺退款率 ⇒ 没有评分")
        .isNull();
    // 🔴 口径未配置 ⇒ 同样没有评分（不是 0 分）
    assertThat(InfluencerRules.score(null, new BigDecimal("50000"), new BigDecimal("0.05"), new BigDecimal("0.03")))
        .as("评分口径未配置 ⇒ 没有评分")
        .isNull();
  }

  @Test
  @DisplayName("权重为 0 的那一项不要求入库（口径自洽）")
  void zeroWeightMetricIsNotRequired() {
    RatingRule r =
        new RatingRule(
            new BigDecimal("1"),
            ZERO,
            ZERO,
            new BigDecimal("100000"),
            new BigDecimal("0.1"),
            new BigDecimal("0.3"));
    assertThat(InfluencerRules.score(r, new BigDecimal("50000"), null, null))
        .as("互动率与退款率权重都是 0 ⇒ 只要 GMV")
        .isEqualByComparingTo(new BigDecimal("0.5"));
  }

  @Test
  @DisplayName("🔴 退款率是负向指标：退款率 0 拿满分、触顶拿 0 分")
  void refundRateIsNegative() {
    RatingRule r = rule();
    BigDecimal clean =
        InfluencerRules.score(r, new BigDecimal("100000"), new BigDecimal("0.1"), ZERO);
    BigDecimal worst =
        InfluencerRules.score(r, new BigDecimal("100000"), new BigDecimal("0.1"), new BigDecimal("0.3"));
    // 0.5×1 + 0.3×1 + 0.2×1 = 1.00 ； 0.5×1 + 0.3×1 + 0.2×0 = 0.80
    assertThat(clean).isEqualByComparingTo(new BigDecimal("1.00"));
    assertThat(worst).isEqualByComparingTo(new BigDecimal("0.80"));
  }

  @Test
  @DisplayName("🔴 超过归一化上限按满分算（超级达人不把别人压成 0 分）")
  void metricsAreCapped() {
    RatingRule r = rule();
    BigDecimal score =
        InfluencerRules.score(
            r, new BigDecimal("99999999"), new BigDecimal("0.9"), new BigDecimal("0.9"));
    // GMV 触顶 ⇒ 满分 0.5；互动率触顶 ⇒ 满分 0.3；退款率触顶 ⇒ 该项 0 分（0.2×0）
    assertThat(score).isEqualByComparingTo(new BigDecimal("0.80"));

    // 只看 GMV 超上限那一步：封顶后等于满分的 0.5 权重
    BigDecimal gmvOnly =
        InfluencerRules.score(
            new RatingRule(
                new BigDecimal("1"), ZERO, ZERO, new BigDecimal("100000"), new BigDecimal("0.1"),
                new BigDecimal("0.3")),
            new BigDecimal("99999999"),
            null,
            null);
    assertThat(gmvOnly).isEqualByComparingTo(ONE);
  }

  @Test
  @DisplayName("粉丝层级分段与 DB 触发器同一口径")
  void followerTierBuckets() {
    assertThat(InfluencerRules.followerTier(0)).isEqualTo("NANO");
    assertThat(InfluencerRules.followerTier(9_999L)).isEqualTo("NANO");
    assertThat(InfluencerRules.followerTier(10_000L)).isEqualTo("MICRO");
    assertThat(InfluencerRules.followerTier(99_999L)).isEqualTo("MICRO");
    assertThat(InfluencerRules.followerTier(100_000L)).isEqualTo("MID");
    assertThat(InfluencerRules.followerTier(499_999L)).isEqualTo("MID");
    assertThat(InfluencerRules.followerTier(500_000L)).isEqualTo("MACRO");
    assertThat(InfluencerRules.followerTier(999_999L)).isEqualTo("MACRO");
    assertThat(InfluencerRules.followerTier(1_000_000L)).isEqualTo("MEGA");
    assertThat(InfluencerRules.followerTier(4_999_999L)).isEqualTo("MEGA");
    assertThat(InfluencerRules.followerTier(5_000_000L)).isEqualTo("TOP");
  }

  @Test
  @DisplayName("档位区间是闭区间，且与评分口径分开")
  void tierRangeIsClosed() {
    assertThat(InfluencerRules.tierCovers(new BigDecimal("0.8"), new BigDecimal("1.0"), new BigDecimal("0.8")))
        .isTrue();
    assertThat(InfluencerRules.tierCovers(new BigDecimal("0.8"), new BigDecimal("1.0"), ONE))
        .isTrue();
    assertThat(InfluencerRules.tierCovers(new BigDecimal("0.8"), new BigDecimal("1.0"), new BigDecimal("0.79")))
        .isFalse();
    assertThat(InfluencerRules.tierCovers(new BigDecimal("0.8"), new BigDecimal("1.0"), null))
        .as("没有评分 ⇒ 不落任何档位")
        .isFalse();
  }

  @Test
  @DisplayName("封闭词表与 V288 的 CHECK 一致")
  void vocabulariesMatchDatabase() {
    assertThat(InfluencerRules.FOLLOWER_TIERS)
        .containsExactly("NANO", "MICRO", "MID", "MACRO", "MEGA", "TOP");
    assertThat(InfluencerRules.BLACKLIST_REASON_CODES)
        .containsExactly(
            "FRAUD", "INFRINGEMENT", "POOR_QUALITY", "NON_COMPLIANCE", "BRAND_SAFETY", "DATA_QUALITY");
    assertThat(InfluencerRules.COOPERATION_STATUSES)
        .containsExactly("PLANNED", "ACTIVE", "COMPLETED", "CANCELLED");
    assertThat(InfluencerRules.BLACKLIST_REASON_CODES)
        .as("拉黑原因词表里不许有「其它」桶")
        .doesNotContain("OTHER");
  }
}
