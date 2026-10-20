package com.starcon.catalog;

import static org.assertj.core.api.Assertions.*;

import com.starcon.catalog.ExchangeRateModels.EntryMode;
import com.starcon.catalog.ExchangeRateModels.RateInput;
import com.starcon.catalog.ExchangeRateModels.SourceKind;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * 5.2.2.1 汇率查询的纯规则断言。
 *
 * <p>这些函数守着蓝图加粗句「**汇率方向必须显式**」的技术落点：
 * 显示口径必须带方向、反向汇率必须由正向推导、录入必须同时给基准币种与报价币种。
 */
class ExchangeRateRulesTest {

  private static BigDecimal rate(String value) {
    return new BigDecimal(value);
  }

  // ================================================================ 方向显示

  @Test
  void 方向显示必须带基准币种与报价币种而不是裸数字() {
    assertThat(ExchangeRateRules.display("USD", "CNY", rate("7.1234")))
        .isEqualTo("1 USD = 7.1234 CNY");
    // 方向相反时显示句必须完全不同 —— 裸数字 0.1404 读不出方向。
    assertThat(ExchangeRateRules.display("CNY", "USD", rate("0.1404")))
        .isEqualTo("1 CNY = 0.1404 USD");
    assertThat(ExchangeRateRules.display("USD", "CNY", rate("7.1234")))
        .isNotEqualTo(ExchangeRateRules.display("CNY", "USD", rate("7.1234")));
  }

  @Test
  void 显示里的尾零要去掉但至少留一位小数() {
    assertThat(ExchangeRateRules.display("USD", "CNY", rate("7.00000000")))
        .isEqualTo("1 USD = 7.0 CNY");
    assertThat(ExchangeRateRules.display("USD", "CNY", rate("7.12345678")))
        .isEqualTo("1 USD = 7.12345678 CNY");
    // 缺字段时不能编一个方向出来。
    assertThat(ExchangeRateRules.display(null, "CNY", rate("7"))).isEqualTo("—");
    assertThat(ExchangeRateRules.display("USD", "CNY", null)).isEqualTo("—");
  }

  // ================================================================ 反向汇率

  @Test
  void 反向汇率由正向推导且带标注() {
    var inverse = ExchangeRateRules.inverseRate(rate("7.1234"));
    assertThat(inverse).isNotNull();
    assertThat(inverse).isEqualByComparingTo("0.14038240");
    // 8 位小数精度：DB 层是 numeric(19,8)，推导值必须同精度。
    assertThat(inverse.scale()).isEqualTo(ExchangeRateRules.RATE_SCALE);
    var text = ExchangeRateRules.inverseDisplay("USD", "CNY", rate("7.1234"));
    assertThat(text).contains("1 CNY = 0.1403824 USD");
    // 标注必须写明是从哪条正向推出来的。
    assertThat(text).contains("推导");
    assertThat(text).contains("1 USD = 7.1234 CNY");
  }

  @Test
  void 反向推导与正向相乘回到1() {
    var forward = rate("6.7250");
    var inverse = ExchangeRateRules.inverseRate(forward);
    var product = forward.multiply(inverse);
    assertThat(product.subtract(BigDecimal.ONE).abs())
        .isLessThan(new BigDecimal("0.0000001"));
  }

  @Test
  void 零汇率不能推导() {
    assertThat(ExchangeRateRules.inverseRate(BigDecimal.ZERO)).isNull();
    assertThat(ExchangeRateRules.inverseRate(null)).isNull();
    assertThat(ExchangeRateRules.inverseDisplay("USD", "CNY", BigDecimal.ZERO)).isEqualTo("—");
  }

  // ================================================================ 币种

  @Test
  void 币种码归一化与校验() {
    assertThat(ExchangeRateRules.normalizeCurrency(" usd ")).isEqualTo("USD");
    assertThat(ExchangeRateRules.validCurrency("USD")).isTrue();
    assertThat(ExchangeRateRules.validCurrency("usd")).isTrue();
    assertThat(ExchangeRateRules.validCurrency("US")).isFalse();
    assertThat(ExchangeRateRules.validCurrency("USDD")).isFalse();
    assertThat(ExchangeRateRules.currencyProblem("US")).contains("三位大写币种代码");
    assertThat(ExchangeRateRules.currencyProblem(null)).contains("不能为空");
    assertThat(ExchangeRateRules.currencyProblem("USD")).isNull();
  }

  // ================================================================ 录入校验

  private static RateInput input(String base, String quote, String value, LocalDate date, String source) {
    return new RateInput(
        base, quote, value == null ? null : rate(value), date, source, "", "");
  }

  @Test
  void 录入必须同时给出基准币种与报价币种() {
    var problems =
        ExchangeRateRules.validateManual(
            input("US", "CNY", "7.1", LocalDate.now(), "MANUAL"), SourceKind.MANUAL, true);
    assertThat(problems).anyMatch(p -> p.contains("基准币种"));
  }

  @Test
  void 本币对本币不需要汇率() {
    var problems =
        ExchangeRateRules.validateManual(
            input("USD", "USD", "1", LocalDate.now(), "MANUAL"), SourceKind.MANUAL, true);
    assertThat(problems).anyMatch(p -> p.contains("本币对本币"));
  }

  @Test
  void 汇率必须大于0且不能超过8位小数() {
    assertThat(
            ExchangeRateRules.validateManual(
                    input("USD", "CNY", "0", LocalDate.now(), "MANUAL"), SourceKind.MANUAL, true))
        .anyMatch(p -> p.contains("大于 0"));
    assertThat(
            ExchangeRateRules.validateManual(
                    input("USD", "CNY", "7.123456789", LocalDate.now(), "MANUAL"),
                    SourceKind.MANUAL,
                    true))
        .anyMatch(p -> p.contains("8 位小数"));
  }

  @Test
  void 来源未登记与已停用都挡住录入() {
    assertThat(
            ExchangeRateRules.validateManual(
                input("USD", "CNY", "7.1", LocalDate.now(), "BANK_OF_CHINA"), null, null))
        .anyMatch(p -> p.contains("未登记"));
    assertThat(
            ExchangeRateRules.validateManual(
                input("USD", "CNY", "7.1", LocalDate.now(), "BANK_OF_CHINA"),
                SourceKind.MANUAL,
                false))
        .anyMatch(p -> p.contains("已停用"));
    // 手工来源 + 启用 ⇒ 没有问题。
    assertThat(
            ExchangeRateRules.validateManual(
                input("USD", "CNY", "7.1", LocalDate.now(), "MANUAL"), SourceKind.MANUAL, true))
        .isEmpty();
  }

  @Test
  void 录入方式必须与来源类型一致() {
    assertThat(ExchangeRateRules.entryModeMatches(SourceKind.MANUAL, EntryMode.MANUAL)).isTrue();
    assertThat(ExchangeRateRules.entryModeMatches(SourceKind.EXTERNAL, EntryMode.EXTERNAL)).isTrue();
    // 手工来源标成外部拉取 = 编造来源。
    assertThat(ExchangeRateRules.entryModeMatches(SourceKind.MANUAL, EntryMode.EXTERNAL)).isFalse();
    assertThat(ExchangeRateRules.entryModeProblem(SourceKind.MANUAL, EntryMode.EXTERNAL))
        .contains("录入方式与来源类型不符");
    assertThat(ExchangeRateRules.entryModeProblem(SourceKind.MANUAL, EntryMode.MANUAL)).isNull();
  }

  // ================================================================ 词表文案

  @Test
  void 来源类型与录入方式都有中文名() {
    assertThat(ExchangeRateRules.sourceKindLabel("MANUAL")).isEqualTo("手工录入");
    assertThat(ExchangeRateRules.sourceKindLabel("EXTERNAL")).isEqualTo("外部拉取");
    assertThat(ExchangeRateRules.sourceKindLabel(null)).isEqualTo("未登记");
    assertThat(ExchangeRateRules.entryModeLabel("EXTERNAL")).isEqualTo("外部拉取");
    assertThat(ExchangeRateRules.entryModeLabel("WHAT")).isEqualTo("WHAT");
  }

  @Test
  void 能力声明文案必须讲清方向与唯一性() {
    assertThat(ExchangeRateRules.DIRECTION_RULE).contains("基准币种").contains("报价币种");
    assertThat(ExchangeRateRules.UNIQUENESS_RULE).contains("只能有一条");
    assertThat(ExchangeRateRules.INVERSE_RULE).contains("推导").contains("矛盾");
    // 下游未交付必须如实写明。
    assertThat(ExchangeRateRules.DOWNSTREAM_NOTE).contains("5.2.2.2").contains("尚未交付");
    assertThat(ExchangeRateRules.OWNERSHIP_NOTE).contains("catalog");
  }
}
