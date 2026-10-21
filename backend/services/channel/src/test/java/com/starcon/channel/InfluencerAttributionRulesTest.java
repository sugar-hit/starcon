package com.starcon.channel;

import static com.starcon.channel.InfluencerAttributionRules.*;
import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 14.5.2.1 带货归因 的纯计算规则单测。
 *
 * <p>本类钉住本模块最容易做丢的几件事：
 *
 * <ol>
 *   <li><b>「没录」不是 0</b>：佣金没录 ⇒ 佣金金额 null；佣金为 0 ⇒ ROI null。
 *   <li><b>三个口径互不覆盖</b>：归因 GMV 由**基数 × 权重**算出来，
 *       合作全额归因与活动池分摊给出不同的结果。
 *   <li><b>退款口径</b>：GROSS 与 NET 两种口径下
 *       「毛额 = 归因 GMV + 退款额」这个恒等式都成立。
 *   <li><b>佣金不按归因权重缩放</b>：成本不会因为口径变了就变。
 *   <li><b>分摊基准不可用时返回 null</b>，不悄悄退回「全额归因」。
 *   <li><b>归因窗口</b>：超窗不再新增归因，但合作进行中永远在窗口内。
 * </ol>
 */
class InfluencerAttributionRulesTest {

  private static final BigDecimal ONE = new BigDecimal("1");
  private static final BigDecimal ZERO = new BigDecimal("0");

  // ==================================================================
  // 归因权重
  // ==================================================================

  @Test
  @DisplayName("合作全额归因：权重恒为 1（与分摊基准无关）")
  void cooperationFullAlwaysWeightsOne() {
    assertThat(
            InfluencerAttributionRules.weight(
                "COOPERATION_FULL", "FOLLOWER_SHARE", 1000L, 5000L, null, null))
        .isEqualByComparingTo("1.000000");
  }

  @Test
  @DisplayName("活动池分摊 · 按粉丝数占比：权重 = 本人粉丝 / 池子粉丝合计")
  void poolSplitByFollowerShare() {
    assertThat(
            InfluencerAttributionRules.weight(
                "CAMPAIGN_POOL_SPLIT", "FOLLOWER_SHARE", 30_000L, 100_000L, null, null))
        .isEqualByComparingTo("0.300000");
  }

  @Test
  @DisplayName("活动池分摊 · 按合作 GMV 占比：权重 = 本人 GMV / 池子 GMV 合计")
  void poolSplitByGmvShare() {
    assertThat(
            InfluencerAttributionRules.weight(
                "CAMPAIGN_POOL_SPLIT",
                "COOPERATION_GMV_SHARE",
                0L,
                0L,
                new BigDecimal("2500"),
                new BigDecimal("10000")))
        .isEqualByComparingTo("0.250000");
  }

  @Test
  @DisplayName("分摊基准不可用 ⇒ null（不悄悄退回全额归因）")
  void unusableSplitBasisReturnsNull() {
    // 粉丝数合计为 0
    assertThat(
            InfluencerAttributionRules.weight(
                "CAMPAIGN_POOL_SPLIT", "FOLLOWER_SHARE", 0L, 0L, null, null))
        .isNull();
    // 池子 GMV 为 0
    assertThat(
            InfluencerAttributionRules.weight(
                "CAMPAIGN_POOL_SPLIT",
                "COOPERATION_GMV_SHARE",
                0L,
                0L,
                new BigDecimal("100"),
                ZERO))
        .isNull();
    // 本人 GMV 没录
    assertThat(
            InfluencerAttributionRules.weight(
                "CAMPAIGN_POOL_SPLIT",
                "COOPERATION_GMV_SHARE",
                0L,
                0L,
                null,
                new BigDecimal("100")))
        .isNull();
  }

  // ==================================================================
  // 归因结果
  // ==================================================================

  @Test
  @DisplayName("归因毛额 = 基数 × 权重；基数没录 ⇒ null（不是 0）")
  void grossGmvIsBaseTimesWeight() {
    assertThat(
            InfluencerAttributionRules.grossGmv(new BigDecimal("1000"), new BigDecimal("0.25")))
        .isEqualByComparingTo("250.0000");
    assertThat(InfluencerAttributionRules.grossGmv(null, new BigDecimal("0.25"))).isNull();
    assertThat(InfluencerAttributionRules.grossGmv(new BigDecimal("1000"), null)).isNull();
  }

  @Test
  @DisplayName("退款口径 GROSS / NET：归因毛额 = 基数 × 权重 在两种口径下都成立")
  void refundTreatmentKeepsGrossIdentity() {
    BigDecimal gross = new BigDecimal("1000");
    BigDecimal refund = InfluencerAttributionRules.refundAmount(gross, new BigDecimal("0.10"));

    assertThat(refund).isEqualByComparingTo("100.0000");

    BigDecimal grossGmv =
        InfluencerAttributionRules.attributedGmv(gross, refund, "GROSS");
    BigDecimal netGmv =
        InfluencerAttributionRules.attributedGmv(gross, refund, "NET");

    // GROSS：归因 GMV 就是毛额
    assertThat(grossGmv).isEqualByComparingTo("1000.0000");
    // NET：归因 GMV = 毛额 − 退款额
    assertThat(netGmv).isEqualByComparingTo("900.0000");
    assertThat(netGmv.add(refund)).isEqualByComparingTo(gross);
    // 🔴 毛额本身 = 基数 × 权重（两种退款口径下都一样）
    assertThat(
            InfluencerAttributionRules.grossGmv(gross, new BigDecimal("1.000000")))
        .isEqualByComparingTo(gross);
  }

  @Test
  @DisplayName("净额口径下退款额超过毛额 ⇒ 归因 GMV 归零（不为负）")
  void netGmvNeverGoesNegative() {
    BigDecimal gross = new BigDecimal("100");
    BigDecimal refund = new BigDecimal("150");
    assertThat(InfluencerAttributionRules.attributedGmv(gross, refund, "NET"))
        .isEqualByComparingTo("0.0000");
  }

  @Test
  @DisplayName("有效订单量 = 归因订单量 × (1 − 退款率)；退款率没录 ⇒ 原值")
  void effectiveOrderCountNetsRefunds() {
    assertThat(
            InfluencerAttributionRules.effectiveOrderCount(
                new BigDecimal("100"), new BigDecimal("0.20")))
        .isEqualByComparingTo("80.000000");
    assertThat(InfluencerAttributionRules.effectiveOrderCount(new BigDecimal("100"), null))
        .isEqualByComparingTo("100.000000");
  }

  @Test
  @DisplayName("有效粉丝转化率：粉丝数为 0 ⇒ null（不是 0，也不是无穷大）")
  void followerConversionNeedsFollowerCount() {
    assertThat(
            InfluencerAttributionRules.followerConversionRate(new BigDecimal("50"), 1000L))
        .isEqualByComparingTo("0.050000");
    assertThat(InfluencerAttributionRules.followerConversionRate(new BigDecimal("50"), 0L)).isNull();
    assertThat(InfluencerAttributionRules.followerConversionRate(null, 1000L)).isNull();
  }

  // ==================================================================
  // 佣金成本与投产比
  // ==================================================================

  @Test
  @DisplayName("佣金口径 · 用合作记录已录的佣金：金额就是那条佣金（不按归因权重缩放）")
  void commissionFromCooperationRecord() {
    assertThat(
            InfluencerAttributionRules.commissionAmount(
                "COOPERATION_COMMISSION",
                new BigDecimal("300"),
                new BigDecimal("5000"),
                null))
        .isEqualByComparingTo("300.0000");
    assertThat(
            InfluencerAttributionRules.commissionBasisAmount(
                "COOPERATION_COMMISSION", new BigDecimal("300"), new BigDecimal("5000")))
        .isEqualByComparingTo("300.0000");
  }

  @Test
  @DisplayName("佣金口径 · 按归因 GMV × 比例计提")
  void commissionFromRateOnAttributedGmv() {
    assertThat(
            InfluencerAttributionRules.commissionAmount(
                "RATE_ON_ATTRIBUTED_GMV", null, new BigDecimal("5000"), new BigDecimal("0.10")))
        .isEqualByComparingTo("500.0000");
    assertThat(
            InfluencerAttributionRules.commissionBasisAmount(
                "RATE_ON_ATTRIBUTED_GMV", null, new BigDecimal("5000")))
        .isEqualByComparingTo("5000.0000");
  }

  @Test
  @DisplayName("佣金没录 ⇒ 佣金金额 null（不是 0），ROI 与净额也跟着 null")
  void missingCommissionIsNullNotZero() {
    assertThat(
            InfluencerAttributionRules.commissionAmount(
                "COOPERATION_COMMISSION", null, new BigDecimal("5000"), null))
        .isNull();
    assertThat(InfluencerAttributionRules.roi(new BigDecimal("5000"), null)).isNull();
    assertThat(InfluencerAttributionRules.netAfterCommission(new BigDecimal("5000"), null)).isNull();
  }

  @Test
  @DisplayName("ROI = 归因 GMV ÷ 佣金成本；佣金为 0 ⇒ null（不是无穷大）")
  void roiNeedsPositiveCommission() {
    assertThat(InfluencerAttributionRules.roi(new BigDecimal("5000"), new BigDecimal("250")))
        .isEqualByComparingTo("20.000000");
    assertThat(InfluencerAttributionRules.roi(new BigDecimal("5000"), ZERO)).isNull();
  }

  @Test
  @DisplayName("扣佣金后净额：只扣佣金")
  void netAfterCommissionOnlySubtractsCommission() {
    assertThat(
            InfluencerAttributionRules.netAfterCommission(
                new BigDecimal("5000"), new BigDecimal("500")))
        .isEqualByComparingTo("4500.0000");
  }

  // ==================================================================
  // 归因窗口
  // ==================================================================

  @Test
  @DisplayName("归因窗口：从合作结束时刻起算；超窗不再新增归因")
  void attributionWindow() {
    OffsetDateTime now = OffsetDateTime.parse("2026-10-20T00:00:00+08:00");
    OffsetDateTime ended = OffsetDateTime.parse("2026-10-01T00:00:00+08:00");

    assertThat(InfluencerAttributionRules.withinWindow(ended, 30, now)).isTrue();
    assertThat(InfluencerAttributionRules.withinWindow(ended, 10, now)).isFalse();
    // 合作进行中（没有结束时刻）⇒ 永远在窗口内
    assertThat(InfluencerAttributionRules.withinWindow(null, 0, now)).isTrue();
  }

  // ==================================================================
  // 封闭词表
  // ==================================================================

  @Test
  @DisplayName("封闭词表与 V290 的 CHECK 一致")
  void closedVocabularies() {
    assertThat(InfluencerAttributionRules.ATTRIBUTION_MODELS)
        .containsExactly("COOPERATION_FULL", "CAMPAIGN_POOL_SPLIT");
    assertThat(InfluencerAttributionRules.POOL_WEIGHT_BASES)
        .containsExactly("FOLLOWER_SHARE", "COOPERATION_GMV_SHARE");
    assertThat(InfluencerAttributionRules.REFUND_TREATMENTS)
        .containsExactly("GROSS", "NET");
    assertThat(InfluencerAttributionRules.COMMISSION_BASES)
        .containsExactly("COOPERATION_COMMISSION", "RATE_ON_ATTRIBUTED_GMV");
    assertThat(InfluencerAttributionRules.ACCRUAL_STATUSES)
        .containsExactly("PENDING", "ACCRUED", "REVERSED");
  }
}
