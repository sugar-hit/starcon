package com.starcon.catalog;

import static org.assertj.core.api.Assertions.*;

import com.starcon.catalog.ExchangeRateSnapshotModels.ConversionDirection;
import com.starcon.catalog.ExchangeRateSnapshotModels.DocumentType;
import com.starcon.catalog.ExchangeRateSnapshotModels.RoundingModeOption;
import com.starcon.catalog.ExchangeRateSnapshotModels.SnapshotInput;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 5.2.2.2 汇率快照的**纯规则**单测（无 IO、无数据库）。
 *
 * <p>守的是蓝图加粗句「**汇率快照是利润可追溯的前提**：今天汇率变了不能让上个月的利润报表数字跟着变」
 * 在**计算侧**的落点：
 *
 * <ol>
 *   <li><b>方向由三个字段唯一确定</b>：基准币 / 报价币 / 原价币 —— 不猜方向；
 *   <li><b>换算只有一个入口</b>：冻结时算一次、复算时算一次，两次走同一段代码；
 *   <li><b>舍入必须落在数字上</b>：每一种舍入方式的结果都被逐值钉死。
 * </ol>
 */
class ExchangeRateSnapshotRulesTest {

  // ================================================================ 方向

  /** 原价站在基准币那一侧 ⇒ 乘；站在报价币那一侧 ⇒ 除；都不是 ⇒ null（不猜）。 */
  @Test
  void 方向由基准币报价币与原价币唯一确定() {
    assertThat(
            ExchangeRateSnapshotRules.resolveDirection("USD", "CNY", "USD"))
        .isEqualTo(ConversionDirection.BASE_TO_QUOTE);
    assertThat(ExchangeRateSnapshotRules.resolveDirection("USD", "CNY", "CNY"))
        .isEqualTo(ConversionDirection.QUOTE_TO_BASE);
    // 大小写与空白都归一。
    assertThat(ExchangeRateSnapshotRules.resolveDirection(" usd ", "cny", "usd"))
        .isEqualTo(ConversionDirection.BASE_TO_QUOTE);
    // 原价币不在这条汇率的两侧 ⇒ 没有方向。
    assertThat(ExchangeRateSnapshotRules.resolveDirection("USD", "CNY", "EUR")).isNull();
    assertThat(ExchangeRateSnapshotRules.resolveDirection("USD", "CNY", null)).isNull();
  }

  /** 方向不合法时的说明必须把两侧都写出来（否则用户不知道自己该填哪一侧）。 */
  @Test
  void 方向不合法时说明里带两侧币种() {
    var problem = ExchangeRateSnapshotRules.directionProblem("USD", "CNY", "EUR");
    assertThat(problem).contains("EUR").contains("USD").contains("CNY");
  }

  // ================================================================ 换算

  /** 基准币 → 报价币：乘。 */
  @Test
  void 基准币到报价币是乘法() {
    var result =
        ExchangeRateSnapshotRules.convert(
            new BigDecimal("100.00"),
            new BigDecimal("7.1234"),
            ConversionDirection.BASE_TO_QUOTE,
            2,
            RoundingMode.HALF_UP);
    assertThat(result).isEqualByComparingTo("712.34");
  }

  /** 报价币 → 基准币：除（反向汇率由正向推导，不另存一条）。 */
  @Test
  void 报价币到基准币是除法() {
    var result =
        ExchangeRateSnapshotRules.convert(
            new BigDecimal("100.00"),
            new BigDecimal("7.1234"),
            ConversionDirection.QUOTE_TO_BASE,
            2,
            RoundingMode.HALF_UP);
    // 100 ÷ 7.1234 = 14.03824… ⇒ 14.04
    assertThat(result).isEqualByComparingTo("14.04");
  }

  /**
   * 六种舍入方式逐值钉死。
   *
   * <p>用 100 × 7.12345 = 712.345（**恰好**的 5）做样本：只有这样才能把
   * HALF_UP 与 HALF_DOWN 区分开 —— 用 712.345678 时两者都会进位，区分不出来。
   */
  @Test
  void 六种舍入方式逐值钉死() {
    var amount = new BigDecimal("100");
    var rate = new BigDecimal("7.12345");
    assertThat(
            ExchangeRateSnapshotRules.convert(
                amount, rate, ConversionDirection.BASE_TO_QUOTE, 2, RoundingMode.HALF_UP))
        .isEqualByComparingTo("712.35");
    assertThat(
            ExchangeRateSnapshotRules.convert(
                amount, rate, ConversionDirection.BASE_TO_QUOTE, 2, RoundingMode.HALF_DOWN))
        .isEqualByComparingTo("712.34");
    assertThat(
            ExchangeRateSnapshotRules.convert(
                amount, rate, ConversionDirection.BASE_TO_QUOTE, 2, RoundingMode.DOWN))
        .isEqualByComparingTo("712.34");
    assertThat(
            ExchangeRateSnapshotRules.convert(
                amount, rate, ConversionDirection.BASE_TO_QUOTE, 2, RoundingMode.UP))
        .isEqualByComparingTo("712.35");
    assertThat(
            ExchangeRateSnapshotRules.convert(
                amount, rate, ConversionDirection.BASE_TO_QUOTE, 2, RoundingMode.CEILING))
        .isEqualByComparingTo("712.35");
    assertThat(
            ExchangeRateSnapshotRules.convert(
                amount, rate, ConversionDirection.BASE_TO_QUOTE, 2, RoundingMode.FLOOR))
        .isEqualByComparingTo("712.34");
  }

  /** 零位小数（日元口径）：结果必须真的没有小数位。 */
  @Test
  void 零位小数口径下结果没有小数位() {
    var result =
        ExchangeRateSnapshotRules.convert(
            new BigDecimal("1000"),
            new BigDecimal("0.00612"),
            ConversionDirection.BASE_TO_QUOTE,
            0,
            RoundingMode.HALF_UP);
    assertThat(result).isEqualByComparingTo("6");
    assertThat(result.scale()).isZero();
  }

  /** 换算入口对缺参数是安全的（返回 null 而不是抛 NPE）。 */
  @Test
  void 换算入口对缺参数是安全的() {
    assertThat(
            ExchangeRateSnapshotRules.convert(
                null, new BigDecimal("7"), ConversionDirection.BASE_TO_QUOTE, 2, RoundingMode.HALF_UP))
        .isNull();
    assertThat(
            ExchangeRateSnapshotRules.convert(
                new BigDecimal("1"), null, ConversionDirection.BASE_TO_QUOTE, 2, RoundingMode.HALF_UP))
        .isNull();
  }

  /** 换算过程的整句显示：带方向、带算子、带舍入口径，不出现裸数字。 */
  @Test
  void 换算过程显示成整句() {
    var display =
        ExchangeRateSnapshotRules.conversionDisplay(
            new BigDecimal("100.000000"),
            "USD",
            new BigDecimal("7.1234"),
            "CNY",
            ConversionDirection.BASE_TO_QUOTE,
            2,
            RoundingMode.HALF_UP);
    assertThat(display).contains("USD").contains("×").contains("7.1234").contains("712.34").contains("CNY");
    assertThat(display).contains("HALF_UP").contains("2 位小数");

    var divided =
        ExchangeRateSnapshotRules.conversionDisplay(
            new BigDecimal("100.000000"),
            "CNY",
            new BigDecimal("7.1234"),
            "USD",
            ConversionDirection.QUOTE_TO_BASE,
            2,
            RoundingMode.HALF_UP);
    assertThat(divided).contains("÷").contains("14.04").contains("USD");
  }

  /** 金额显示去掉尾零但至少保留一位小数。 */
  @Test
  void 金额显示去掉尾零但至少保留一位小数() {
    assertThat(ExchangeRateSnapshotRules.amountText(new BigDecimal("100.000000"))).isEqualTo("100.0");
    assertThat(ExchangeRateSnapshotRules.amountText(new BigDecimal("712.340000"))).isEqualTo("712.34");
    assertThat(ExchangeRateSnapshotRules.amountText(null)).isEqualTo("—");
  }

  // ================================================================ 写入校验

  private static SnapshotInput input() {
    return new SnapshotInput(
        UUID.randomUUID(),
        null,
        null,
        null,
        "USD",
        new BigDecimal("100"),
        "PRICE_LIST",
        UUID.randomUUID(),
        "PL-1",
        "L1",
        null,
        "");
  }

  /** 既没给 rateId 也没给币种对 ⇒ 必须报错（不许猜一条汇率冻上去）。 */
  @Test
  void 冻结请求必须指明要冻结哪条汇率() {
    var problems =
        ExchangeRateSnapshotRules.validateSnapshot(
            new SnapshotInput(
                null, null, null, null, "USD", new BigDecimal("100"), "PRICE_LIST",
                UUID.randomUUID(), "PL-1", "L1", null, ""),
            null,
            null);
    assertThat(problems).isNotEmpty();
    assertThat(String.join("；", problems)).contains("必须指定要冻结的汇率");
  }

  /** 币种码、本币对本币、金额精度、单据类型与单据 id 都要挡住。 */
  @Test
  void 冻结请求的字段校验逐条挡住() {
    var badCurrency =
        ExchangeRateSnapshotRules.validateSnapshot(
            new SnapshotInput(
                UUID.randomUUID(), null, null, null, "US", new BigDecimal("100"), "PRICE_LIST",
                UUID.randomUUID(), "", "", null, ""),
            null,
            null);
    assertThat(String.join("；", badCurrency)).contains("原价币种");

    var samePair =
        ExchangeRateSnapshotRules.validateSnapshot(
            new SnapshotInput(
                null, "USD", "USD", null, "USD", new BigDecimal("100"), "PRICE_LIST",
                UUID.randomUUID(), "", "", null, ""),
            null,
            null);
    assertThat(String.join("；", samePair)).contains("本币对本币");

    var tooPrecise =
        ExchangeRateSnapshotRules.validateSnapshot(
            new SnapshotInput(
                UUID.randomUUID(), null, null, null, "USD", new BigDecimal("100.1234567"),
                "PRICE_LIST", UUID.randomUUID(), "", "", null, ""),
            null,
            null);
    assertThat(String.join("；", tooPrecise)).contains("最多保留 6 位小数");

    var badDocumentType =
        ExchangeRateSnapshotRules.validateSnapshot(
            new SnapshotInput(
                UUID.randomUUID(), null, null, null, "USD", new BigDecimal("100"), "NOT_A_DOC",
                UUID.randomUUID(), "", "", null, ""),
            null,
            null);
    assertThat(String.join("；", badDocumentType)).contains("单据类型不合法");

    var noDocument =
        ExchangeRateSnapshotRules.validateSnapshot(
            new SnapshotInput(
                UUID.randomUUID(), null, null, null, "USD", new BigDecimal("100"), "PRICE_LIST",
                null, "", "", null, ""),
            null,
            null);
    assertThat(String.join("；", noDocument)).contains("单据 id 不能为空");
  }

  /** 原价币必须落在这条汇率的两侧之一（方向错时报出来）。 */
  @Test
  void 原价币不在汇率两侧时报方向错() {
    var problems =
        ExchangeRateSnapshotRules.validateSnapshot(input(), "USD", "CNY");
    // 原价币是 USD（在两侧之内）⇒ 这一项不该报。
    assertThat(problems).isEmpty();

    var wrong =
        ExchangeRateSnapshotRules.validateSnapshot(
            new SnapshotInput(
                UUID.randomUUID(), null, null, null, "EUR", new BigDecimal("100"), "PRICE_LIST",
                UUID.randomUUID(), "", "", null, ""),
            "USD",
            "CNY");
    assertThat(String.join("；", wrong)).contains("必须是这条汇率的基准币");
  }

  /** 舍入规则的校验：代码、模式、小数位。 */
  @Test
  void 舍入规则校验逐条挡住() {
    assertThat(
            String.join(
                "；",
                ExchangeRateSnapshotRules.validateRoundingRule(
                    "a b", "名称", "CNY", "HALF_UP", 2)))
        .contains("规则代码");
    assertThat(
            String.join(
                "；",
                ExchangeRateSnapshotRules.validateRoundingRule(
                    "CNY", "", "CNY", "HALF_UP", 2)))
        .contains("规则名称");
    assertThat(
            String.join(
                "；",
                ExchangeRateSnapshotRules.validateRoundingRule(
                    "CNY", "名称", "人民币", "HALF_UP", 2)))
        .contains("适用币种");
    assertThat(
            String.join(
                "；",
                ExchangeRateSnapshotRules.validateRoundingRule(
                    "CNY", "名称", "CNY", "ROUND_HALF_EVEN", 2)))
        .contains("舍入方式");
    assertThat(
            String.join(
                "；",
                ExchangeRateSnapshotRules.validateRoundingRule(
                    "CNY", "名称", "CNY", "HALF_UP", 9)))
        .contains("小数位数");
    assertThat(
            ExchangeRateSnapshotRules.validateRoundingRule("CNY", "名称", "CNY", "HALF_UP", 2))
        .isEmpty();
    // 通用规则：适用币种留空是合法的。
    assertThat(
            ExchangeRateSnapshotRules.validateRoundingRule("DEFAULT", "通用", null, "HALF_UP", 2))
        .isEmpty();
  }

  // ================================================================ 词表

  /** 单据类型与舍入方式都有中文名与依据说明。 */
  @Test
  void 词表都有中文名与依据说明() {
    assertThat(DocumentType.parse("price_list")).isEqualTo(DocumentType.PRICE_LIST);
    assertThat(DocumentType.parse("NOT_A_DOC")).isNull();
    assertThat(DocumentType.PRICE_LIST.label()).isNotBlank();
    assertThat(DocumentType.PRICE_LIST.basis()).isNotBlank();
    assertThat(RoundingModeOption.parse("half_up")).isEqualTo(RoundingModeOption.HALF_UP);
    assertThat(RoundingModeOption.parse("ROUND_HALF_EVEN")).isNull();
    assertThat(RoundingModeOption.HALF_UP.mode()).isEqualTo(RoundingMode.HALF_UP);
    for (var option : RoundingModeOption.values()) {
      assertThat(option.label()).as("舍入方式要有中文名").isNotBlank();
      assertThat(option.basis()).as("舍入方式要有依据说明").isNotBlank();
    }
  }
}
