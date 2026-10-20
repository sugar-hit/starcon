package com.starcon.catalog;

import static com.starcon.catalog.PricingRuleModels.ActionType;
import static com.starcon.catalog.PricingRuleModels.TriggerType;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * 5.3.2.2 价格规则的**纯算术**（{@link PricingRuleRules}）。
 *
 * <p>本类无 IO、无租户上下文，所以可以在没有数据库的情况下把每一个边界钉死 ——
 * 尤其是「取不到数就不判定」这条纪律：它必须是 {@code null}（不判定），
 * 而不是 {@code false}（判定成「没超阈值」）。
 */
class PricingRuleRulesTest {

  private static BigDecimal dec(String value) {
    return new BigDecimal(value);
  }

  // ================================================================ 阈值比较

  @Test
  void 库存低于阈值才命中() {
    assertThat(PricingRuleRules.inventoryBelow(dec("3"), dec("10"))).isTrue();
    assertThat(PricingRuleRules.inventoryBelow(dec("10"), dec("10"))).isFalse();
    assertThat(PricingRuleRules.inventoryBelow(dec("12"), dec("10"))).isFalse();
  }

  @Test
  void 库存取不到就不判定而不是判成没超阈值() {
    assertThat(PricingRuleRules.inventoryBelow(null, dec("10"))).isNull();
    assertThat(PricingRuleRules.inventoryBelow(dec("3"), null)).isNull();
  }

  @Test
  void 成本上涨按两版快照的涨幅判定() {
    // 16 → 21：涨幅 31.25%
    assertThat(PricingRuleRules.costIncreaseRate(dec("21"), dec("16")))
        .isEqualByComparingTo(dec("0.312500"));
    assertThat(PricingRuleRules.costIncreased(dec("21"), dec("16"), dec("0.10"))).isTrue();
    assertThat(PricingRuleRules.costIncreased(dec("21"), dec("16"), dec("0.50"))).isFalse();
  }

  @Test
  void 成本上涨缺上一版或上一版为零时不判定() {
    assertThat(PricingRuleRules.costIncreaseRate(dec("21"), null)).isNull();
    assertThat(PricingRuleRules.costIncreaseRate(dec("21"), dec("0"))).isNull();
    assertThat(PricingRuleRules.costIncreased(null, dec("16"), dec("0.1"))).isNull();
  }

  @Test
  void 市场价降价按相邻两次观测的降幅判定() {
    assertThat(PricingRuleRules.priceDropRate(dec("100"), dec("90"))).isEqualByComparingTo(dec("0.100000"));
    assertThat(PricingRuleRules.priceDropped(dec("100"), dec("90"), dec("0.10"))).isTrue();
    assertThat(PricingRuleRules.priceDropped(dec("100"), dec("90"), dec("0.20"))).isFalse();
    // 涨价不是降价（降幅为负）。
    assertThat(PricingRuleRules.priceDropped(dec("90"), dec("100"), dec("0.10"))).isFalse();
  }

  @Test
  void 只有一次价格观测时不判定降价() {
    assertThat(PricingRuleRules.priceDropRate(null, dec("90"))).isNull();
    assertThat(PricingRuleRules.priceDropped(null, dec("90"), dec("0.10"))).isNull();
  }

  @Test
  void 毛利低于下限才命中且毛利取不到时不判定() {
    assertThat(PricingRuleRules.marginBelowFloor(dec("0.15"), dec("0.20"))).isTrue();
    assertThat(PricingRuleRules.marginBelowFloor(dec("0.25"), dec("0.20"))).isFalse();
    assertThat(PricingRuleRules.marginBelowFloor(null, dec("0.20"))).isNull();
  }

  @Test
  void 库龄超期是严格大于阈值() {
    assertThat(PricingRuleRules.agingOver(120, 90)).isTrue();
    assertThat(PricingRuleRules.agingOver(90, 90)).isFalse();
    assertThat(PricingRuleRules.agingOver(null, 90)).isNull();
  }

  // ================================================================ 建议价

  @Test
  void 降价建议价是当前价乘以一减幅度() {
    assertThat(PricingRuleRules.suggestedPrice(dec("100"), ActionType.LOWER_PRICE, dec("0.05")))
        .isEqualByComparingTo(dec("95.0000"));
  }

  @Test
  void 涨价建议价是当前价乘以一加幅度() {
    assertThat(PricingRuleRules.suggestedPrice(dec("100"), ActionType.RAISE_PRICE, dec("0.05")))
        .isEqualByComparingTo(dec("105.0000"));
  }

  @Test
  void 非价格动作没有建议价() {
    assertThat(PricingRuleRules.suggestedPrice(dec("100"), ActionType.PAUSE_PROMOTION, null)).isNull();
    assertThat(PricingRuleRules.suggestedPrice(dec("100"), ActionType.DELIST, null)).isNull();
  }

  @Test
  void 取不到当前价或幅度非法时不产出建议价() {
    assertThat(PricingRuleRules.suggestedPrice(null, ActionType.LOWER_PRICE, dec("0.05"))).isNull();
    assertThat(PricingRuleRules.suggestedPrice(dec("100"), ActionType.LOWER_PRICE, null)).isNull();
    // 幅度 ≥ 1 ⇒ 价格会变成非正数。
    assertThat(PricingRuleRules.suggestedPrice(dec("100"), ActionType.LOWER_PRICE, dec("1"))).isNull();
    assertThat(PricingRuleRules.suggestedPrice(dec("100"), ActionType.LOWER_PRICE, dec("1.5"))).isNull();
  }

  @Test
  void 变动幅度带符号且降价取绝对值做风险判断() {
    var drop = PricingRuleRules.priceChangeRate(dec("100"), dec("95"));
    assertThat(drop).isEqualByComparingTo(dec("-0.050000"));
    assertThat(PricingRuleRules.dropRate(drop)).isEqualByComparingTo(dec("0.050000"));

    var raise = PricingRuleRules.priceChangeRate(dec("100"), dec("105"));
    assertThat(raise).isEqualByComparingTo(dec("0.050000"));
    // 涨价不是「降了多少」⇒ 风险闸只看降的那一侧。
    assertThat(PricingRuleRules.dropRate(raise)).isEqualByComparingTo(dec("0.000000"));
  }

  // ================================================================ 风险闸

  @Test
  void 降价幅度超过阈值必须人工确认() {
    assertThat(PricingRuleRules.requiresConfirmation(ActionType.LOWER_PRICE, dec("-0.05"), dec("0.02"), true))
        .isTrue();
    assertThat(PricingRuleRules.requiresConfirmation(ActionType.LOWER_PRICE, dec("-0.05"), dec("0.10"), true))
        .isFalse();
  }

  @Test
  void 成本不完整时即便降价幅度很小也不自动放行() {
    assertThat(PricingRuleRules.requiresConfirmation(ActionType.LOWER_PRICE, dec("-0.01"), dec("0.50"), false))
        .isTrue();
    assertThat(PricingRuleRules.requiresConfirmation(ActionType.RAISE_PRICE, dec("0.01"), dec("0.50"), false))
        .isTrue();
  }

  @Test
  void 非价格动作一律要人工确认() {
    assertThat(PricingRuleRules.requiresConfirmation(ActionType.PAUSE_PROMOTION, null, dec("1"), true)).isTrue();
    assertThat(PricingRuleRules.requiresConfirmation(ActionType.DELIST, null, dec("1"), true)).isTrue();
  }

  @Test
  void 自动放行上限为零时任何降价都要人工确认() {
    assertThat(PricingRuleRules.requiresConfirmation(ActionType.LOWER_PRICE, dec("-0.01"), dec("0"), true))
        .isTrue();
    assertThat(PricingRuleRules.requiresConfirmation(ActionType.LOWER_PRICE, dec("-0.01"), null, true))
        .isTrue();
  }

  // ================================================================ 生效时段

  @Test
  void 生效时段是左闭右开() {
    var from = OffsetDateTime.of(2026, 3, 1, 10, 0, 0, 0, ZoneOffset.UTC);
    var to = OffsetDateTime.of(2026, 4, 1, 10, 0, 0, 0, ZoneOffset.UTC);
    assertThat(PricingRuleRules.effectiveNow(true, from, to, from)).isTrue();
    assertThat(PricingRuleRules.effectiveNow(true, from, to, to)).isFalse();
    assertThat(PricingRuleRules.effectiveNow(true, from, null, to)).isTrue();
    assertThat(PricingRuleRules.effectiveNow(false, from, null, from)).isFalse();
  }

  // ================================================================ 精度

  @Test
  void 金额四位费率六位且四舍五入() {
    assertThat(PricingRuleRules.money(dec("1.00005")).scale()).isEqualTo(4);
    assertThat(PricingRuleRules.money(dec("1.00005"))).isEqualByComparingTo(dec("1.0001"));
    assertThat(PricingRuleRules.rate(dec("0.0000005"))).isEqualByComparingTo(dec("0.000001"));
    assertThat(PricingRuleRules.percentText(dec("0.25"))).isEqualTo("25%");
    assertThat(PricingRuleRules.percentText(null)).isEqualTo("—");
  }

  @Test
  void 词表与蓝图逐字对应() {
    assertThat(TriggerType.values())
        .extracting(Enum::name)
        .containsExactly(
            "INVENTORY_BELOW_THRESHOLD",
            "COMPETITOR_PRICE_DROP",
            "COST_INCREASE",
            "MARGIN_BELOW_FLOOR",
            "AGING_OVER_THRESHOLD");
    assertThat(ActionType.values())
        .extracting(Enum::name)
        .containsExactly("LOWER_PRICE", "RAISE_PRICE", "PAUSE_PROMOTION", "DELIST");
  }
}
