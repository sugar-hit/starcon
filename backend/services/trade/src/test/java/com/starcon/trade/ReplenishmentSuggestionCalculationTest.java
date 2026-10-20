package com.starcon.trade;

import static org.assertj.core.api.Assertions.*;

import com.starcon.trade.ReplenishmentCatalogPort.TierView;
import com.starcon.trade.ReplenishmentSuggestionRules.Rounding;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 6.2.1.2 补货建议的<b>取整规则</b>离线单测。
 *
 * <p>取整是本模块最容易出错的地方（单位一换就差一箱 / 差 12 倍），所以规则被抽成一个
 * 不碰数据库、不碰网络的纯类，这里逐条钉住边界。
 *
 * <p>用例名用中文，和本仓其它 Java 测试一致。
 */
class ReplenishmentSuggestionCalculationTest {

  private static BigDecimal bd(String value) {
    return new BigDecimal(value);
  }

  private static TierView tier(String minQuantity, String unitPrice) {
    return new TierView(bd(minQuantity), bd(unitPrice));
  }

  // ================================================================ 第一步：整箱

  @Test
  void 净需求除以箱规向上取整而不是向下取整() {
    // 250 件 ÷ 12 件/箱 = 20.83 箱 → 向上取整 21 箱（向下取整 20 箱会不够，那是超卖的方向）。
    Rounding r =
        ReplenishmentSuggestionRules.round(
            bd("250"), bd("12"), null, null, null, null, List.of(tier("1", "14.2857")));
    assertThat(r.purchaseUnits()).isEqualByComparingTo("21");
    assertThat(r.suggestedQuantity()).isEqualByComparingTo("252");
    assertThat(r.adjustment()).isEqualByComparingTo("2");
  }

  @Test
  void 净需求刚好整箱时不额外多订() {
    Rounding r =
        ReplenishmentSuggestionRules.round(
            bd("240"), bd("12"), null, null, null, null, List.of(tier("1", "1")));
    assertThat(r.purchaseUnits()).isEqualByComparingTo("20");
    assertThat(r.suggestedQuantity()).isEqualByComparingTo("240");
    assertThat(r.adjustment()).isEqualByComparingTo("0");
  }

  @Test
  void 净需求是极小正数时至少订一个采购单位() {
    // 0.000001 件 → ceil 后是 0，但 0 会被读成「不需要补」⇒ 至少 1 个采购单位。
    Rounding r =
        ReplenishmentSuggestionRules.round(
            bd("0.000001"), bd("12"), null, null, null, null, List.of(tier("1", "1")));
    assertThat(r.purchaseUnits()).isEqualByComparingTo("1");
    assertThat(r.suggestedQuantity()).isEqualByComparingTo("12");
  }

  // ================================================================ 第二步：MOQ

  @Test
  void 低于起订量时抬到起订量() {
    // 净需求 5 件（1 箱）但 MOQ 是 10 箱 → 抬到 10 箱 = 120 件。
    Rounding r =
        ReplenishmentSuggestionRules.round(
            bd("12"), bd("12"), bd("10"), null, null, null, List.of(tier("1", "1")));
    assertThat(r.purchaseUnits()).isEqualByComparingTo("10");
    assertThat(r.suggestedQuantity()).isEqualByComparingTo("120");
    assertThat(r.moqRaised()).isEqualByComparingTo("9");
  }

  @Test
  void 高于起订量时不动() {
    Rounding r =
        ReplenishmentSuggestionRules.round(
            bd("250"), bd("12"), bd("10"), null, null, null, List.of(tier("1", "1")));
    assertThat(r.purchaseUnits()).isEqualByComparingTo("21");
    assertThat(r.moqRaised()).isEqualByComparingTo("0");
  }

  // ================================================================ 第三步：采购起订额

  @Test
  void 预计费用低于采购起订额时按单价抬量() {
    // 21 箱 × 14.2857 = 299.9997 < 300 ⇒ 抬到 22 箱（314.2854 ≥ 300）。
    Rounding r =
        ReplenishmentSuggestionRules.round(
            bd("250"),
            bd("12"),
            bd("10"),
            bd("300"),
            "USD",
            "USD",
            List.of(tier("1", "14.2857")));
    assertThat(r.minOrderApplied()).isTrue();
    assertThat(r.purchaseUnits()).isEqualByComparingTo("22");
    assertThat(r.suggestedQuantity()).isEqualByComparingTo("264");
    assertThat(r.estimatedCost()).isEqualByComparingTo("314.2854");
  }

  @Test
  void 预计费用已达采购起订额时不抬量() {
    Rounding r =
        ReplenishmentSuggestionRules.round(
            bd("250"),
            bd("12"),
            bd("10"),
            bd("200"),
            "USD",
            "USD",
            List.of(tier("1", "14.2857")));
    assertThat(r.minOrderApplied()).isFalse();
    assertThat(r.purchaseUnits()).isEqualByComparingTo("21");
  }

  @Test
  void 起订额币种与报价币种不同则不参与取整并说明原因() {
    // 跨币种不比金额：12 财务才有汇率表，比出来的数是「看起来对、实际错」的。
    Rounding r =
        ReplenishmentSuggestionRules.round(
            bd("250"),
            bd("12"),
            bd("10"),
            bd("300"),
            "USD",
            "CNY",
            List.of(tier("1", "14.2857")));
    assertThat(r.minOrderApplied()).isFalse();
    assertThat(r.minOrderSkipReason()).contains("跨币种");
    assertThat(r.purchaseUnits()).isEqualByComparingTo("21");
  }

  @Test
  void 取不到适用阶梯价时起订额不参与取整并说明原因() {
    Rounding r =
        ReplenishmentSuggestionRules.round(
            bd("5"), bd("12"), null, bd("300"), "USD", "USD", List.of(tier("10", "1")));
    assertThat(r.unitPrice()).isNull();
    assertThat(r.estimatedCost()).isNull();
    assertThat(r.minOrderApplied()).isFalse();
    assertThat(r.minOrderSkipReason()).contains("没有适用档");
  }

  // ================================================================ 阶梯取价

  @Test
  void 适用阶梯取起订量不超过数量的那一档里最大的一档() {
    // 开口阶梯：取 minQuantity <= 数量 里最大的一档。
    List<TierView> tiers = List.of(tier("10", "2.5"), tier("50", "2.2"), tier("100", "2.0"));
    assertThat(ReplenishmentSuggestionRules.applicableTierPrice(tiers, bd("10")))
        .isEqualByComparingTo("2.5");
    assertThat(ReplenishmentSuggestionRules.applicableTierPrice(tiers, bd("49")))
        .isEqualByComparingTo("2.5");
    assertThat(ReplenishmentSuggestionRules.applicableTierPrice(tiers, bd("50")))
        .isEqualByComparingTo("2.2");
    assertThat(ReplenishmentSuggestionRules.applicableTierPrice(tiers, bd("1000")))
        .isEqualByComparingTo("2.0");
  }

  @Test
  void 数量低于最低档时取不到价而不是退回高档价() {
    // 退回高档价会让采购单带出一个供应商没报过的价。
    List<TierView> tiers = List.of(tier("10", "2.5"), tier("100", "2.0"));
    assertThat(ReplenishmentSuggestionRules.applicableTierPrice(tiers, bd("9"))).isNull();
  }

  @Test
  void 阶梯为空时取不到价() {
    assertThat(ReplenishmentSuggestionRules.applicableTierPrice(List.of(), bd("100"))).isNull();
    assertThat(ReplenishmentSuggestionRules.applicableTierPrice(null, bd("100"))).isNull();
  }

  // ================================================================ 金额与数量口径

  @Test
  void 金额与数量各自的刻度与数据库一致() {
    // 金额 numeric(19,4)、数量 numeric(19,6)。
    assertThat(ReplenishmentSuggestionRules.money(bd("1.23456"))).isEqualTo(bd("1.2346"));
    assertThat(ReplenishmentSuggestionRules.money6(bd("1.2345678"))).isEqualTo(bd("1.234568"));
  }
}
