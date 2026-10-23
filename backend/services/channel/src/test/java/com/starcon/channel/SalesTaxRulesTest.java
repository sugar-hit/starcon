package com.starcon.channel;

import static com.starcon.channel.SalesTaxModels.SalesTaxRateRuleView;
import static com.starcon.channel.SalesTaxRules.*;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 16.3.2.3 销售税与 Marketplace Facilitator 的**规则单元测试**（纯函数，不碰数据库）。
 *
 * <p>🔴 这组用例守的是本模块最容易做丢的五件事：
 *
 * <ol>
 *   <li><b>应缴与已代缴是两条线</b>：差异 = 应缴 − 已代缴，正数 = 平台少收了；</li>
 *   <li><b>没有生效规则 ⇒ 判不了</b>（不许默认按 0 税率算出一个「不用交税」的假结论）；</li>
 *   <li><b>没登记店铺所在州 ⇒ 判不了</b>（自营销售额根本没归集，「应缴 0」是「没归集」）；</li>
 *   <li><b>未达州级阈值 ⇒ 期间无申报义务</b>（但**不等于免税**）；</li>
 *   <li><b>免征额只减基数</b>：落在免征额内的部分记成 exempt，**不**记成免税。</li>
 * </ol>
 */
class SalesTaxRulesTest {

  /** 造一版规则。 */
  private static SalesTaxRateRuleView rule(
      int version, String from, String to, String baseRate) {
    return new SalesTaxRateRuleView(
        UUID.randomUUID().toString(),
        "US-CA",
        "美国 加利福尼亚州",
        version,
        LocalDate.parse(from),
        to == null ? null : LocalDate.parse(to),
        new BigDecimal(baseRate),
        new BigDecimal("0"),
        new BigDecimal("0"),
        true,
        "QUARTERLY",
        "ACTIVE",
        "",
        null,
        null);
  }

  @Test
  @DisplayName("16.3.2.3 规则：某一刻生效的是哪一版（生效区间左闭右开）")
  void effectiveRulePicksTheVersionInForce() {
    List<SalesTaxRateRuleView> rules =
        List.of(
            rule(1, "2025-01-01", "2026-01-01", "0.0725"),
            rule(2, "2026-01-01", null, "0.0750"));

    assertThat(effectiveRule(rules, LocalDate.parse("2025-06-30")).ruleVersion()).isEqualTo(1);
    // 左闭：生效起始日当天就是新版
    assertThat(effectiveRule(rules, LocalDate.parse("2026-01-01")).ruleVersion()).isEqualTo(2);
    // 右开：终止日当天已经是新版
    assertThat(effectiveRule(rules, LocalDate.parse("2025-12-31")).ruleVersion()).isEqualTo(1);
    // 生效前 ⇒ 没有生效版本（判不了）
    assertThat(effectiveRule(rules, LocalDate.parse("2024-12-31"))).isNull();
  }

  @Test
  @DisplayName("16.3.2.3 对账：没有生效规则 ⇒ 判不了（RULE_MISSING），不许按 0 税率算")
  void missingRuleIsIndeterminate() {
    var outcome =
        compute(
            new ReconciliationInput(
                new BigDecimal("10000"),
                new BigDecimal("700"),
                new BigDecimal("0"),
                new BigDecimal("0"),
                null,
                null,
                2));

    assertThat(outcome.status()).isEqualTo(RECON_RULE_MISSING);
    assertThat(outcome.expectedTax()).isEqualByComparingTo("0");
    // 已代缴的钱仍然如实记着（平台确实收了）
    assertThat(outcome.facilitatorTax()).isEqualByComparingTo("700");
  }

  @Test
  @DisplayName("16.3.2.3 对账：没有登记店铺所在州 ⇒ 判不了（NEXUS_MISSING）")
  void missingNexusIsIndeterminate() {
    var outcome =
        compute(
            new ReconciliationInput(
                new BigDecimal("0"),
                new BigDecimal("700"),
                new BigDecimal("0"),
                new BigDecimal("0"),
                new BigDecimal("0.0725"),
                1,
                0));

    assertThat(outcome.status()).isEqualTo(RECON_NEXUS_MISSING);
    assertThat(outcome.expectedTax()).isEqualByComparingTo("0");
  }

  @Test
  @DisplayName("16.3.2.3 对账：应缴 = 应税基数 × 税率，差异 = 应缴 − 已代缴")
  void expectedTaxAndVarianceAreTwoSeparateLines() {
    var outcome =
        compute(
            new ReconciliationInput(
                new BigDecimal("10000"),
                new BigDecimal("700"),
                new BigDecimal("0"),
                new BigDecimal("0"),
                new BigDecimal("0.0725"),
                1,
                1));

    // 10000 × 7.25% = 725.000000
    assertThat(outcome.taxableBase()).isEqualByComparingTo("10000");
    assertThat(outcome.expectedTax()).isEqualByComparingTo("725.000000");
    // 已代缴 700 ⇒ 差异 25（正数 = 平台少收了，卖家要补缴）
    assertThat(outcome.facilitatorTax()).isEqualByComparingTo("700");
    assertThat(outcome.variance()).isEqualByComparingTo("25.000000");
    assertThat(outcome.status()).isEqualTo(RECON_VARIANCE);
  }

  @Test
  @DisplayName("16.3.2.3 对账：已代缴等于应缴 ⇒ 已对平")
  void balancedWhenVarianceIsZero() {
    var outcome =
        compute(
            new ReconciliationInput(
                new BigDecimal("10000"),
                new BigDecimal("725"),
                new BigDecimal("0"),
                new BigDecimal("0"),
                new BigDecimal("0.0725"),
                1,
                1));

    assertThat(outcome.variance()).isEqualByComparingTo("0");
    assertThat(outcome.status()).isEqualTo(RECON_BALANCED);
  }

  @Test
  @DisplayName("16.3.2.3 对账：未达州级阈值 ⇒ 期间无申报义务（但平台代收的钱仍如实记着）")
  void belowThresholdMeansNoObligation() {
    var outcome =
        compute(
            new ReconciliationInput(
                new BigDecimal("100"),
                new BigDecimal("7"),
                new BigDecimal("1000"),
                new BigDecimal("0"),
                new BigDecimal("0.0725"),
                1,
                1));

    assertThat(outcome.status()).isEqualTo(RECON_BELOW_THRESHOLD);
    assertThat(outcome.expectedTax()).isEqualByComparingTo("0");
    assertThat(outcome.facilitatorTax()).isEqualByComparingTo("7");
  }

  @Test
  @DisplayName("16.3.2.3 对账：免征额只减基数（不是免税）")
  void exemptionReducesTheBaseOnly() {
    var outcome =
        compute(
            new ReconciliationInput(
                new BigDecimal("1000"),
                new BigDecimal("0"),
                new BigDecimal("0"),
                new BigDecimal("400"),
                new BigDecimal("0.0725"),
                1,
                1));

    // 免征额内 400，应税基数 600 ⇒ 600 × 7.25% = 43.5
    assertThat(outcome.exemptSales()).isEqualByComparingTo("400");
    assertThat(outcome.taxableBase()).isEqualByComparingTo("600");
    assertThat(outcome.expectedTax()).isEqualByComparingTo("43.500000");
  }

  @Test
  @DisplayName("16.3.2.3 对账：既无销售也无代缴 ⇒ 期间无活动")
  void noActivity() {
    var outcome =
        compute(
            new ReconciliationInput(
                new BigDecimal("0"),
                new BigDecimal("0"),
                new BigDecimal("0"),
                new BigDecimal("0"),
                new BigDecimal("0.0725"),
                1,
                1));

    assertThat(outcome.status()).isEqualTo(RECON_NO_ACTIVITY);
    assertThat(outcome.expectedTax()).isEqualByComparingTo("0");
  }

  @Test
  @DisplayName("16.3.2.3 金额：四舍五入是 HALF_UP（会计口径），不是银行家舍入")
  void moneyUsesHalfUp() {
    assertThat(money(new BigDecimal("0.0000005")).toPlainString()).isEqualTo("0.000001");
    assertThat(money(new BigDecimal("2.5")).toPlainString()).isEqualTo("2.500000");
    assertThat(money(new BigDecimal("-2.5")).toPlainString()).isEqualTo("-2.500000");
  }

  @Test
  @DisplayName("16.3.2.3 词表：联系类型 / 申报周期 / 对账状态 / 申报义务都是受控值域")
  void vocabulariesAreClosed() {
    assertThat(NEXUS_TYPES)
        .containsExactly(
            "PHYSICAL_PRESENCE",
            "INVENTORY_STORED",
            "ECONOMIC_NEXUS",
            "MARKETPLACE_FACILITATOR");
    assertThat(FILING_CYCLES).containsExactly("MONTHLY", "QUARTERLY", "SEMI_ANNUAL", "ANNUAL");
    assertThat(RECONCILIATION_STATUSES)
        .containsExactly(
            "BALANCED",
            "VARIANCE",
            "BELOW_THRESHOLD",
            "NO_ACTIVITY",
            "RULE_MISSING",
            "NEXUS_MISSING");
    assertThat(FILING_OBLIGATIONS)
        .containsExactly(
            "FILING_REQUIRED",
            "BELOW_THRESHOLD",
            "NO_ACTIVITY",
            "RULE_MISSING",
            "NOT_REGISTERED");
  }
}
