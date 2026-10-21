package com.starcon.catalog;

import static com.starcon.catalog.LogisticsFreightModels.*;
import static org.assertj.core.api.Assertions.*;

import com.starcon.catalog.LogisticsFreightRules.ChargeInput;
import com.starcon.catalog.LogisticsFreightRules.HeadComponentView;
import com.starcon.catalog.LogisticsFreightRules.RuleView;
import com.starcon.catalog.LogisticsFreightRules.SurchargeView;
import com.starcon.catalog.LogisticsFreightRules.TierView;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 5.2.3.2 物流与头程费用：<b>纯算术与校验</b>的单元测试。
 *
 * <p>集成测试只能证明「落库了」，证明不了「算对了」。这里钉住的是：
 *
 * <ul>
 *   <li>三种计费口径的<b>计费量</b>算式（重量 / 体积 / 件数，含体积重换算）；
 *   <li>阶梯的两种计价形态（首段 + 续段、整档一口价）；
 *   <li>🔴 <b>算不出来就是算不出来</b>（{@code applicable=false} + {@code amount=null}），
 *       绝不是 0 —— 那是「没有规则 ≠ 运费是 0」在运费层面的落点；
 *   <li>三种附加费口径（基础运费比例 / 每单固定额 / 每件固定额）；
 *   <li>头程成本项的四种计价基数，缺数量时**算不出来**而不是 0。
 * </ul>
 */
class LogisticsFreightRulesTest {

  static RuleView rule(String chargeBasis, String currency, List<TierView> tiers) {
    return new RuleView(
        UUID.randomUUID(), chargeBasis, currency, null, null, tiers);
  }

  /** 首段 + 续段：首重 1kg × 12.00，续重每 kg × 5.00。 */
  static TierView firstPlusAdditional(int seq, String min, String max, String firstLimit) {
    return new TierView(
        seq,
        new BigDecimal(min),
        max == null ? null : new BigDecimal(max),
        "FIRST_PLUS_ADDITIONAL",
        null,
        new BigDecimal(firstLimit),
        new BigDecimal("12.00"),
        new BigDecimal("5.00"));
  }

  static ChargeInput charge(
      String weight, String length, String width, String height, String pieces) {
    return new ChargeInput(
        weight == null ? null : new BigDecimal(weight),
        length == null ? null : new BigDecimal(length),
        width == null ? null : new BigDecimal(width),
        height == null ? null : new BigDecimal(height),
        pieces == null ? null : new BigDecimal(pieces),
        null);
  }

  // ================================================================ 计费量

  @Test
  void 按重量计费时计费量就是实重() {
    var result = LogisticsFreightRules.chargeable("BY_WEIGHT", charge("3.5", null, null, null, null));
    assertThat(result.computable()).isTrue();
    assertThat(result.quantity()).isEqualByComparingTo("3.5");
    assertThat(result.unit()).isEqualTo("kg");
  }

  @Test
  void 按体积计费时计费量是立方米() {
    var result =
        LogisticsFreightRules.chargeable("BY_VOLUME", charge("3.5", "100", "50", "40", null));
    assertThat(result.computable()).isTrue();
    // 100 × 50 × 40 cm³ = 200000 cm³ = 0.2 m³
    assertThat(result.quantity()).isEqualByComparingTo("0.200000");
    assertThat(result.unit()).isEqualTo("m³");
  }

  @Test
  void 按件数计费时计费量是件数() {
    var result = LogisticsFreightRules.chargeable("BY_PIECE", charge(null, null, null, null, "12"));
    assertThat(result.computable()).isTrue();
    assertThat(result.quantity()).isEqualByComparingTo("12");
    assertThat(result.unit()).isEqualTo("件");
  }

  @Test
  void 配了体积重换算系数时计费重取实重与体积重的较大者() {
    var input =
        new ChargeInput(
            new BigDecimal("2.0"),
            new BigDecimal("100"),
            new BigDecimal("50"),
            new BigDecimal("40"),
            null,
            new BigDecimal("5000"));
    // 体积重 = 100 × 50 × 40 ÷ 5000 = 40 kg > 实重 2kg ⇒ 计费重 40kg
    var result = LogisticsFreightRules.chargeable("BY_WEIGHT", input);
    assertThat(result.computable()).isTrue();
    assertThat(result.quantity()).isEqualByComparingTo("40.000000");
    assertThat(result.basis()).contains("体积重").contains("取两者较大者");
  }

  @Test
  void 缺实重时计费量算不出来而不是零() {
    var result = LogisticsFreightRules.chargeable("BY_WEIGHT", charge(null, null, null, null, null));
    assertThat(result.computable()).isFalse();
    assertThat(result.quantity()).isNull();
    assertThat(result.reason()).contains("实重");
  }

  @Test
  void 缺长宽高时按体积算不出来而不是零() {
    var result = LogisticsFreightRules.chargeable("BY_VOLUME", charge("3.5", null, null, null, null));
    assertThat(result.computable()).isFalse();
    assertThat(result.quantity()).isNull();
  }

  // ================================================================ 阶梯算式

  @Test
  void 首段加续段把首段和续段分开算() {
    var rule =
        rule(
            "BY_WEIGHT",
            "USD",
            List.of(firstPlusAdditional(1, "0", "30", "1")));
    var base = LogisticsFreightRules.baseFreight(rule, new BigDecimal("3.5"));
    assertThat(base.applicable()).isTrue();
    // 首段 1kg × 12.00 = 12.00；续段 2.5kg × 5.00 = 12.50 ⇒ 24.50
    assertThat(base.amount()).isEqualByComparingTo("24.5000");
    assertThat(base.basis()).contains("首段").contains("续段");
  }

  @Test
  void 计费量落在首段以内时只算首段() {
    var rule = rule("BY_WEIGHT", "USD", List.of(firstPlusAdditional(1, "0", "30", "1")));
    var base = LogisticsFreightRules.baseFreight(rule, new BigDecimal("0.8"));
    assertThat(base.amount()).isEqualByComparingTo("9.6000");
    assertThat(base.basis()).contains("续段 0");
  }

  @Test
  void 整档一口价不看具体多重() {
    var rule =
        rule(
            "BY_WEIGHT",
            "USD",
            List.of(
                new TierView(
                    1, new BigDecimal("0"), new BigDecimal("1"), "FLAT_PER_TIER",
                    new BigDecimal("15.00"), null, null, null)));
    var base = LogisticsFreightRules.baseFreight(rule, new BigDecimal("0.5"));
    assertThat(base.applicable()).isTrue();
    assertThat(base.amount()).isEqualByComparingTo("15.0000");
  }

  @Test
  void 计费量落不进任何一档时基础运费算不出来而不是零() {
    var rule = rule("BY_WEIGHT", "USD", List.of(firstPlusAdditional(1, "5", "30", "6")));
    var base = LogisticsFreightRules.baseFreight(rule, new BigDecimal("2"));
    assertThat(base.applicable()).isFalse();
    assertThat(base.amount()).isNull();
    assertThat(base.note()).contains("不适用");
  }

  @Test
  void 低于最低收费时按最低收费计() {
    var view =
        new RuleView(
            UUID.randomUUID(),
            "BY_WEIGHT",
            "USD",
            null,
            new BigDecimal("20.00"),
            List.of(firstPlusAdditional(1, "0", "30", "1")));
    var base = LogisticsFreightRules.baseFreight(view, new BigDecimal("1"));
    // 首段 1kg × 12 = 12.00 < 最低收费 20.00 ⇒ 按 20.00 计
    assertThat(base.amount()).isEqualByComparingTo("20.0000");
    assertThat(base.note()).contains("最低收费");
  }

  @Test
  void 选档是下界闭上界开且最后一档无上限() {
    var tiers =
        List.of(
            firstPlusAdditional(1, "0", "1", "0.5"),
            firstPlusAdditional(2, "1", null, "2"));
    // 1.0 落在第二档（第一档上界含 1，不含）
    assertThat(LogisticsFreightRules.selectTier(tiers, new BigDecimal("1")).seq()).isEqualTo(2);
    // 0.5 落在第一档
    assertThat(LogisticsFreightRules.selectTier(tiers, new BigDecimal("0.5")).seq()).isEqualTo(1);
    // 100 落在最后一档（无上限）
    assertThat(LogisticsFreightRules.selectTier(tiers, new BigDecimal("100")).seq()).isEqualTo(2);
    // 低于最低档下界 ⇒ 没有档位
    assertThat(LogisticsFreightRules.selectTier(tiers, new BigDecimal("-1"))).isNull();
  }

  // ================================================================ 附加费

  @Test
  void 燃油附加按基础运费的比例算() {
    var surcharge =
        new SurchargeView(
            UUID.randomUUID(),
            "FUEL",
            "PERCENT_OF_BASE_FREIGHT",
            new BigDecimal("0.15"),
            null,
            "USD");
    var applied =
        LogisticsFreightRules.applySurcharge(surcharge, new BigDecimal("24.5000"), new BigDecimal("1"));
    assertThat(applied.applicable()).isTrue();
    assertThat(applied.amount()).isEqualByComparingTo("3.6750");
    assertThat(applied.basis()).contains("0.15");
  }

  @Test
  void 旺季附加每单一个固定额() {
    var surcharge =
        new SurchargeView(
            UUID.randomUUID(), "PEAK_SEASON", "FIXED_PER_ORDER", null, new BigDecimal("2.50"), "USD");
    var applied =
        LogisticsFreightRules.applySurcharge(surcharge, new BigDecimal("24.5000"), new BigDecimal("3"));
    assertThat(applied.amount()).isEqualByComparingTo("2.5000");
  }

  @Test
  void 偏远附加每件一个固定额() {
    var surcharge =
        new SurchargeView(
            UUID.randomUUID(), "REMOTE_AREA", "FIXED_PER_UNIT", null, new BigDecimal("1.20"), "USD");
    var applied =
        LogisticsFreightRules.applySurcharge(surcharge, new BigDecimal("24.5000"), new BigDecimal("3"));
    assertThat(applied.amount()).isEqualByComparingTo("3.6000");
    assertThat(applied.basis()).contains("件数");
  }

  @Test
  void 基础运费算不出来时比例附加费也算不出来() {
    var surcharge =
        new SurchargeView(
            UUID.randomUUID(),
            "FUEL",
            "PERCENT_OF_BASE_FREIGHT",
            new BigDecimal("0.15"),
            null,
            "USD");
    var applied = LogisticsFreightRules.applySurcharge(surcharge, null, new BigDecimal("1"));
    assertThat(applied.applicable()).isFalse();
    assertThat(applied.amount()).isNull();
  }

  // ================================================================ 头程成本项

  @Test
  void 头程成本项按各自的计价基数算() {
    var perShipment =
        new HeadComponentView(1, "LINE_HAUL", "海运费", "PER_SHIPMENT", new BigDecimal("1200.00"), "CNY");
    var perCbm =
        new HeadComponentView(2, "CUSTOMS", "报关费", "PER_CBM", new BigDecimal("80.00"), "CNY");
    var container =
        new HeadComponentView(3, "TRUCKING", "拖车费", "PER_CONTAINER", new BigDecimal("900.00"), "CNY");
    var perKg =
        new HeadComponentView(4, "INSURANCE", "保险费", "PER_KG", new BigDecimal("0.50"), "CNY");
    assertThat(
            LogisticsFreightRules.applyHeadComponent(
                    perShipment, new BigDecimal("1"), new BigDecimal("12"), new BigDecimal("2"), new BigDecimal("300"))
                .amount())
        .isEqualByComparingTo("1200.0000");
    assertThat(
            LogisticsFreightRules.applyHeadComponent(
                    perCbm, new BigDecimal("1"), new BigDecimal("12"), new BigDecimal("2"), new BigDecimal("300"))
                .amount())
        .isEqualByComparingTo("960.0000");
    assertThat(
            LogisticsFreightRules.applyHeadComponent(
                    container, new BigDecimal("1"), new BigDecimal("12"), new BigDecimal("2"), new BigDecimal("300"))
                .amount())
        .isEqualByComparingTo("1800.0000");
    assertThat(
            LogisticsFreightRules.applyHeadComponent(
                    perKg, new BigDecimal("1"), new BigDecimal("12"), new BigDecimal("2"), new BigDecimal("300"))
                .amount())
        .isEqualByComparingTo("150.0000");
  }

  @Test
  void 头程成本项缺对应数量时算不出来而不是零() {
    var perCbm =
        new HeadComponentView(1, "CUSTOMS", "报关费", "PER_CBM", new BigDecimal("80.00"), "CNY");
    var applied =
        LogisticsFreightRules.applyHeadComponent(
            perCbm, new BigDecimal("1"), null, new BigDecimal("2"), new BigDecimal("300"));
    assertThat(applied.applicable()).isFalse();
    assertThat(applied.amount()).isNull();
    assertThat(applied.note()).contains("m³");
  }

  // ================================================================ 入参校验

  @Test
  void 阶梯必须首尾相接() {
    var problems =
        LogisticsFreightRules.validateTiers(
            List.of(
                new FreightTierInput(
                    1, new BigDecimal("0"), new BigDecimal("1"), "FLAT_PER_TIER",
                    new BigDecimal("10"), null, null, null),
                new FreightTierInput(
                    2, new BigDecimal("2"), null, "FLAT_PER_TIER",
                    new BigDecimal("12"), null, null, null)));
    assertThat(problems).anyMatch(p -> p.contains("首尾相接"));
  }

  @Test
  void 体积重换算系数只对按重量有意义() {
    var problems =
        LogisticsFreightRules.validateRule(
            new FreightRuleInput(
                "YUNDA", "", null, null, "EXPRESS", "空运", "BY_PIECE", "CNY",
                new BigDecimal("5000"), null,
                java.time.OffsetDateTime.now(), null, "",
                List.of(
                    new FreightTierInput(
                        1, new BigDecimal("0"), null, "FLAT_PER_TIER",
                        new BigDecimal("10"), null, null, null))));
    assertThat(problems).anyMatch(p -> p.contains("体积重换算系数只对"));
  }

  @Test
  void 附加费的形态与参数必须互斥() {
    var problems =
        LogisticsFreightRules.validateSurcharge(
            new FreightSurchargeInput(
                "YUNDA", "", null, null, "EXPRESS", "", "FUEL",
                "PERCENT_OF_BASE_FREIGHT", null, new BigDecimal("3"), "CNY",
                java.time.OffsetDateTime.now(), null, ""));
    assertThat(problems).anyMatch(p -> p.contains("不填固定额"));
  }

  @Test
  void 附加费的费率必须在零到一之间() {
    var problems =
        LogisticsFreightRules.validateSurcharge(
            new FreightSurchargeInput(
                "YUNDA", "", null, null, "EXPRESS", "", "FUEL",
                "PERCENT_OF_BASE_FREIGHT", new BigDecimal("15"), null, "CNY",
                java.time.OffsetDateTime.now(), null, ""));
    assertThat(problems).anyMatch(p -> p.contains("0 到 1"));
  }

  @Test
  void 没有阶梯的规则通不过校验() {
    var problems =
        LogisticsFreightRules.validateRule(
            new FreightRuleInput(
                "YUNDA", "", null, null, "EXPRESS", "空运", "BY_WEIGHT", "CNY",
                null, null, java.time.OffsetDateTime.now(), null, "",
                List.<FreightTierInput>of()));
    assertThat(problems).anyMatch(p -> p.contains("至少要有一档阶梯"));
  }

  @Test
  void 头程费用结构至少要有一个成本项() {
    var problems =
        LogisticsFreightRules.validateHeadStructure(
            new HeadFreightStructureInput(
                "SEA", "", "CN-SZ", "", "US-LAX", "", "CNY",
                java.time.OffsetDateTime.now(), null, "", List.of()));
    assertThat(problems).anyMatch(p -> p.contains("至少要有一个成本项"));
  }
}
