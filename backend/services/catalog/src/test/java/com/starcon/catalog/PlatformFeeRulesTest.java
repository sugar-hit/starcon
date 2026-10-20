package com.starcon.catalog;

import static com.starcon.catalog.PlatformFeeModels.*;
import static org.assertj.core.api.Assertions.*;

import com.starcon.catalog.PlatformFeeRules.RuleView;
import com.starcon.catalog.PlatformFeeRules.TierView;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 5.2.3.1 平台费用：<b>纯算术与校验</b>的单元测试。
 *
 * <p>集成测试只能证明「落库了」，证明不了「算对了」。这里钉住的是：
 *
 * <ul>
 *   <li>四条计价形态的算式（比例 / 每单 / 每件 / 阶梯）；
 *   <li>订阅费分摊 = 周期总额 ÷ 分摊基数；
 *   <li>🔴 <b>算不出来就是算不出来</b>（{@code applicable=false} + {@code amount=null}），
 *       绝不是 0 —— 那是蓝图加粗句在费用项层面的落点；
 *   <li>阶梯选档（下界含、上界开、无上限档）。
 * </ul>
 */
class PlatformFeeRulesTest {

  static RuleView percentage(BigDecimal rate) {
    return new RuleView(
        UUID.randomUUID(), "COMMISSION", ChargeBasis.PERCENTAGE_OF_SALE,
        rate, null, null, null, null, null, null, List.of());
  }

  static RuleView fixedPerOrder(BigDecimal amount, String currency) {
    return new RuleView(
        UUID.randomUUID(), "TRANSACTION", ChargeBasis.FIXED_PER_ORDER,
        null, amount, currency, null, null, null, null, List.of());
  }

  static RuleView fixedPerUnit(BigDecimal amount, String currency) {
    return new RuleView(
        UUID.randomUUID(), "TRANSACTION", ChargeBasis.FIXED_PER_UNIT,
        null, amount, currency, null, null, null, null, List.of());
  }

  static RuleView subscription(BigDecimal total, BigDecimal divisor, AllocationBasis basis) {
    return new RuleView(
        UUID.randomUUID(), "SUBSCRIPTION", ChargeBasis.FIXED_PER_ORDER,
        null, null, null, total, "USD", basis, divisor, List.of());
  }

  static RuleView tiered(List<TierView> tiers) {
    return new RuleView(
        UUID.randomUUID(), "COMMISSION", ChargeBasis.TIERED_PERCENTAGE,
        null, null, null, null, null, null, null, tiers);
  }

  @Test
  void 比例形态是销售额乘费率() {
    var applied =
        PlatformFeeRules.apply(
            percentage(new BigDecimal("0.15")), List.of(),
            new BigDecimal("24.80"), new BigDecimal("1"), "USD");
    assertThat(applied.applicable()).isTrue();
    assertThat(applied.amount()).isEqualByComparingTo("3.72");
    assertThat(applied.currency()).isEqualTo("USD");
    assertThat(applied.basis()).contains("0.15");
  }

  @Test
  void 每单固定额与数量无关() {
    var applied =
        PlatformFeeRules.apply(
            fixedPerOrder(new BigDecimal("0.35"), "USD"), List.of(),
            new BigDecimal("100"), new BigDecimal("7"), "USD");
    assertThat(applied.amount()).isEqualByComparingTo("0.35");
  }

  @Test
  void 每件固定额乘数量() {
    var applied =
        PlatformFeeRules.apply(
            fixedPerUnit(new BigDecimal("0.25"), "USD"), List.of(),
            new BigDecimal("100"), new BigDecimal("4"), "USD");
    assertThat(applied.amount()).isEqualByComparingTo("1.00");
  }

  @Test
  void 订阅费按单分摊是周期总额除以分摊基数() {
    var applied =
        PlatformFeeRules.apply(
            subscription(new BigDecimal("39.99"), new BigDecimal("100"), AllocationBasis.PER_ORDER),
            List.of(), new BigDecimal("20"), new BigDecimal("1"), "USD");
    assertThat(applied.amount()).isEqualByComparingTo("0.3999");
    assertThat(applied.currency()).isEqualTo("USD");
    assertThat(applied.basis()).contains("39.99").contains("100");
  }

  @Test
  void 订阅费按件分摊再乘数量() {
    var applied =
        PlatformFeeRules.apply(
            subscription(new BigDecimal("39.99"), new BigDecimal("100"), AllocationBasis.PER_UNIT),
            List.of(), new BigDecimal("20"), new BigDecimal("3"), "USD");
    assertThat(applied.amount()).isEqualByComparingTo("1.1997");
  }

  @Test
  void 阶梯选档是下界含上界开() {
    var tiers =
        List.of(
            new TierView(new BigDecimal("0"), new BigDecimal("100"), new BigDecimal("0.10")),
            new TierView(new BigDecimal("100"), null, new BigDecimal("0.15")));
    // 恰好落在上界 ⇒ 落进下一档。
    var at100 =
        PlatformFeeRules.apply(tiered(tiers), tiers, new BigDecimal("100"), new BigDecimal("1"), "USD");
    assertThat(at100.amount()).isEqualByComparingTo("15.00");
    var below =
        PlatformFeeRules.apply(tiered(tiers), tiers, new BigDecimal("99.99"), new BigDecimal("1"), "USD");
    assertThat(below.amount()).isEqualByComparingTo("9.999");
  }

  @Test
  void 阶梯落不进任何一档时算不出来而不是零() {
    var tiers =
        List.of(new TierView(new BigDecimal("100"), null, new BigDecimal("0.15")));
    var applied =
        PlatformFeeRules.apply(tiered(tiers), tiers, new BigDecimal("50"), new BigDecimal("1"), "USD");
    assertThat(applied.applicable()).isFalse();
    assertThat(applied.amount()).isNull();
    assertThat(applied.note()).contains("不适用");
  }

  @Test
  void 订阅费缺少分摊基数时算不出来而不是零() {
    var broken =
        new RuleView(
            UUID.randomUUID(), "SUBSCRIPTION", ChargeBasis.FIXED_PER_ORDER,
            null, null, null, new BigDecimal("39.99"), "USD", AllocationBasis.PER_ORDER, null, List.of());
    var applied =
        PlatformFeeRules.apply(broken, List.of(), new BigDecimal("20"), new BigDecimal("1"), "USD");
    assertThat(applied.applicable()).isFalse();
    assertThat(applied.amount()).isNull();
  }

  @Test
  void 费率必须在小数口径的零到一之间() {
    var problems =
        PlatformFeeRules.validateRule(
            new FeeRuleInput(
                "AMAZON", "US", null, null, "COMMISSION", "PERCENTAGE_OF_SALE",
                new BigDecimal("15"), null, null, null, null, null, null,
                java.time.OffsetDateTime.now(), null, true, "", null));
    assertThat(problems).anyMatch(p -> p.contains("0 到 1"));
  }

  @Test
  void 订阅费必须填周期总额与分摊基数() {
    var problems =
        PlatformFeeRules.validateRule(
            new FeeRuleInput(
                "AMAZON", "US", null, null, "SUBSCRIPTION", "FIXED_PER_ORDER",
                null, null, null, null, null, null, null,
                java.time.OffsetDateTime.now(), null, true, "", null));
    assertThat(problems).anyMatch(p -> p.contains("周期总额"));
    assertThat(problems).anyMatch(p -> p.contains("分摊基数"));
  }

  @Test
  void 阶梯必须首尾相接() {
    var problems =
        PlatformFeeRules.validateTiers(
            List.of(
                new FeeTierInput(1, new BigDecimal("0"), new BigDecimal("100"), new BigDecimal("0.10")),
                new FeeTierInput(2, new BigDecimal("200"), null, new BigDecimal("0.15"))));
    assertThat(problems).anyMatch(p -> p.contains("首尾相接"));
  }

  @Test
  void 阶梯可以整体替换为相接的一组() {
    var problems =
        PlatformFeeRules.validateTiers(
            List.of(
                new FeeTierInput(1, new BigDecimal("0"), new BigDecimal("100"), new BigDecimal("0.10")),
                new FeeTierInput(2, new BigDecimal("100"), null, new BigDecimal("0.15"))));
    assertThat(problems).isEmpty();
  }

  @Test
  void 特异度按站点内部分类平台类目加权() {
    assertThat(PlatformFeeRules.specificity(null, null, null)).isZero();
    assertThat(PlatformFeeRules.specificity("US", null, null)).isEqualTo(8);
    assertThat(PlatformFeeRules.specificity(null, UUID.randomUUID(), null)).isEqualTo(4);
    assertThat(PlatformFeeRules.specificity(null, null, UUID.randomUUID())).isEqualTo(2);
    assertThat(PlatformFeeRules.specificity("US", UUID.randomUUID(), UUID.randomUUID())).isEqualTo(14);
  }
}
