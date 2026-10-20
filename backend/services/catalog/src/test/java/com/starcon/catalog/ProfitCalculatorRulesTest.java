package com.starcon.catalog;

import static com.starcon.catalog.ProfitCalculatorModels.CalcMode.PRICE;
import static com.starcon.catalog.ProfitCalculatorModels.CalcMode.TARGET_MARGIN;
import static org.assertj.core.api.Assertions.*;

import com.starcon.catalog.ProfitCalculatorModels.CalcMode;
import com.starcon.catalog.ProfitCalculatorRules.CostInput;
import com.starcon.catalog.ProfitCalculatorRules.Input;
import com.starcon.catalog.ProfitCalculatorRules.Output;
import com.starcon.catalog.ProfitCalculatorRules.TierSpec;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.*;

/**
 * 5.3.1.1 利润测算器：利润拆解算术的单元断言。
 *
 * <p>本文件守的是蓝图加粗句的三条技术落点：
 *
 * <ol>
 *   <li><b>「必须显式列出每一项扣减」</b> ⇒ 每一项扣减都要真的参与算术，
 *       少扣一项算出来的毛利率就虚高。
 *   <li><b>「反向测算要走同一套扣减结构」</b> ⇒ 反解出来的售价代回正向公式，
 *       毛利率必须**恰好**等于目标值（不是接近）。
 *   <li><b>「不能只给一个毛利率数字」</b> ⇒ 缺项时毛利率是 <b>null</b>，不是 0、不是估算。
 * </ol>
 */
class ProfitCalculatorRulesTest {

  /** 一份完整的成本：采购 10 + 头程 2 + 包装 1 + 关税 3 + 其他 0 = 16。 */
  private static List<CostInput> fullCosts() {
    return List.of(
        cost("PURCHASE", "10"),
        cost("FIRST_MILE", "2"),
        cost("PACKAGING", "1"),
        cost("DUTY", "3"),
        cost("OTHER_DIRECT", "0"));
  }

  private static CostInput cost(String code, String amount) {
    return new CostInput(code, true, false, new BigDecimal(amount), null);
  }

  private static Input input(String revenue) {
    return new Input(
        new BigDecimal(revenue),
        null,
        BigDecimal.ONE,
        new BigDecimal("0.15"),
        null,
        new BigDecimal("5"),
        null,
        List.of(),
        fullCosts());
  }

  // ================================================================ 正向

  @Test
  void 每一项扣减都参与算术() {
    var output = ProfitCalculatorRules.evaluate(PRICE, input("100"));
    // 100 − 15（平台费 15%）− 5（物流）− 16（已知成本）= 64
    assertThat(output.platformFeeAmount()).isEqualByComparingTo("15.0000");
    assertThat(output.logisticsAmount()).isEqualByComparingTo("5.0000");
    assertThat(output.knownCostTotal()).isEqualByComparingTo("16.0000");
    assertThat(output.totalDeductions()).isEqualByComparingTo("36.0000");
    assertThat(output.grossProfit()).isEqualByComparingTo("64.0000");
    assertThat(output.grossMarginRate()).isEqualByComparingTo("0.640000");
    assertThat(output.complete()).isTrue();
  }

  @Test
  void 少扣一项毛利率就虚高所以缺项必须不给数字() {
    // 把物流抽掉（5.2.3.2 未交付时的真实状态）。
    var withoutLogistics =
        new Input(
            new BigDecimal("100"),
            null,
            BigDecimal.ONE,
            new BigDecimal("0.15"),
            null,
            null, // 物流取不到
            null,
            List.of(),
            fullCosts());
    var output = ProfitCalculatorRules.evaluate(PRICE, withoutLogistics);
    assertThat(output.complete()).isFalse();
    // 🔴 关键断言：不给数字，而不是给一个偏高的数字。
    assertThat(output.grossProfit()).isNull();
    assertThat(output.grossMarginRate()).isNull();
    assertThat(output.breakEvenPrice()).isNull();
    assertThat(output.targetMarginPrice()).isNull();
    assertThat(output.breakEvenVolume()).isNull();
  }

  @Test
  void 部分取到的分项也不算完整() {
    var partial =
        new Input(
            new BigDecimal("100"),
            null,
            BigDecimal.ONE,
            new BigDecimal("0.15"),
            null,
            new BigDecimal("5"),
            null,
            List.of(),
            List.of(
                cost("PURCHASE", "10"),
                new CostInput("FIRST_MILE", true, true, new BigDecimal("2"), "有记录被排除"),
                cost("PACKAGING", "1"),
                cost("DUTY", "3"),
                cost("OTHER_DIRECT", "0")));
    var output = ProfitCalculatorRules.evaluate(PRICE, partial);
    assertThat(output.complete()).isFalse();
    assertThat(output.grossMarginRate()).isNull();
  }

  // ================================================================ 反向（同一套结构）

  @Test
  void 反向倒推的售价代回正向公式后毛利率恰好等于目标值() {
    var target = new BigDecimal("0.25");
    var reverse =
        new Input(
            null,
            target,
            BigDecimal.ONE,
            new BigDecimal("0.15"),
            null,
            new BigDecimal("5"),
            null,
            List.of(),
            fullCosts());
    var output = ProfitCalculatorRules.evaluate(TARGET_MARGIN, reverse);
    assertThat(output.complete()).isTrue();
    // 售价 = (0 + 5 + 16) ÷ (1 − 0.15 − 0.25) = 21 ÷ 0.6 = 35
    assertThat(output.revenue()).isEqualByComparingTo("35.0000");
    assertThat(output.grossMarginRate()).isEqualByComparingTo("0.250000");
    assertThat(output.grossProfit()).isEqualByComparingTo("8.7500");
  }

  @Test
  void 反向与正向是同一个方程所以保本价等于毛利为零的售价() {
    var output = ProfitCalculatorRules.evaluate(PRICE, input("100"));
    // 保本价 = (0 + 5 + 16) ÷ (1 − 0.15) = 21 ÷ 0.85 = 24.7059
    assertThat(output.breakEvenPrice()).isEqualByComparingTo("24.7059");
    // 把保本价代回正向公式：毛利恰好为 0。
    var atBreakEven =
        new Input(
            output.breakEvenPrice(),
            null,
            BigDecimal.ONE,
            new BigDecimal("0.15"),
            null,
            new BigDecimal("5"),
            null,
            List.of(),
            fullCosts());
    var check = ProfitCalculatorRules.evaluate(PRICE, atBreakEven);
    assertThat(check.grossProfit()).isEqualByComparingTo("0.0000");
  }

  @Test
  void 目标毛利率加平台费率不小于百分之百时方程无解() {
    var impossible =
        new Input(
            null,
            new BigDecimal("0.90"),
            BigDecimal.ONE,
            new BigDecimal("0.15"),
            null,
            new BigDecimal("5"),
            null,
            List.of(),
            fullCosts());
    var output = ProfitCalculatorRules.evaluate(TARGET_MARGIN, impossible);
    assertThat(output.revenue()).isNull();
    assertThat(output.complete()).isFalse();
    assertThat(output.incompleteReason()).contains("无解");
  }

  // ================================================================ 物流阶梯

  @Test
  void 阶梯按数量加权平均成每单位运费() {
    var tiers =
        List.of(
            new TierSpec(new BigDecimal("100"), new BigDecimal("4.00")),
            new TierSpec(null, new BigDecimal("3.00")));
    // 150 单：前 100 单 4.00（400），后 50 单 3.00（150）⇒ 每单位 550/150 = 3.6667
    var perUnit = ProfitCalculatorRules.logisticsPerUnit(tiers, new BigDecimal("150"), null);
    assertThat(perUnit).isEqualByComparingTo("3.6667");
    var bands = ProfitCalculatorRules.bands(tiers, new BigDecimal("150"), null);
    assertThat(bands).hasSize(2);
    assertThat(bands.get(0).units()).isEqualByComparingTo("100");
    assertThat(bands.get(0).bandTotal()).isEqualByComparingTo("400.0000");
    assertThat(bands.get(1).units()).isEqualByComparingTo("50");
    assertThat(bands.get(1).bandTotal()).isEqualByComparingTo("150.0000");
  }

  @Test
  void 阶梯没有覆盖到测算数量时不给一个平均运费() {
    var tiers = List.of(new TierSpec(new BigDecimal("100"), new BigDecimal("4.00")));
    assertThat(ProfitCalculatorRules.tierCoverageComplete(tiers, new BigDecimal("150"))).isFalse();
    assertThat(ProfitCalculatorRules.logisticsPerUnit(tiers, new BigDecimal("150"), null)).isNull();
    // 覆盖得到时才有值。
    assertThat(ProfitCalculatorRules.logisticsPerUnit(tiers, new BigDecimal("50"), null))
        .isEqualByComparingTo("4.0000");
  }

  // ================================================================ 盈亏平衡销量

  @Test
  void 盈亏平衡销量是固定成本除以单位贡献毛利() {
    // 售价 100，平台费率 0，物流 0，已知成本 16 ⇒ 单位贡献 84；固定成本 100 ⇒ 100/84 ⇒ 2 件
    var volume =
        ProfitCalculatorRules.breakEvenVolume(
            new BigDecimal("100"),
            BigDecimal.ZERO,
            null,
            new BigDecimal("16"),
            List.of(),
            new BigDecimal("5"),
            new BigDecimal("100"));
    assertThat(volume).isEqualByComparingTo("2");
  }

  @Test
  void 阶梯下盈亏平衡销量按分段精确求解而不是简单相除() {
    // 第 1~100 单每单位运费 4，之后每单位 2。
    // 售价 100，平台费率 0，已知成本 16 ⇒ 第 1 段单位贡献 80，第 2 段 82。
    // 固定成本 100 ⇒ 第 1 段需 ceil(100/80) = 2 件 ⇒ 盈亏平衡销量 2。
    var volume =
        ProfitCalculatorRules.breakEvenVolume(
            new BigDecimal("100"),
            BigDecimal.ZERO,
            null,
            new BigDecimal("16"),
            List.of(
                new TierSpec(new BigDecimal("100"), new BigDecimal("4")),
                new TierSpec(null, new BigDecimal("2"))),
            new BigDecimal("5"),
            new BigDecimal("100"));
    assertThat(volume).isEqualByComparingTo("2");
  }

  @Test
  void 单位贡献不为正时没有盈亏平衡点() {
    var volume =
        ProfitCalculatorRules.breakEvenVolume(
            new BigDecimal("10"),
            BigDecimal.ZERO,
            null,
            new BigDecimal("16"), // 成本 16 > 售价 10 ⇒ 每卖一件亏 6
            List.of(),
            new BigDecimal("5"),
            new BigDecimal("100"));
    assertThat(volume).isNull();
  }

  // ================================================================ 精度

  @Test
  void 金额四位小数费率六位小数全部四舍五入() {
    assertThat(ProfitCalculatorRules.money(new BigDecimal("1.00005"))).isEqualByComparingTo("1.0001");
    assertThat(ProfitCalculatorRules.money(new BigDecimal("1.00004"))).isEqualByComparingTo("1.0000");
    assertThat(ProfitCalculatorRules.rate(new BigDecimal("0.3333335"))).isEqualByComparingTo("0.333334");
    // 🔴 毛利率是精确值：不能是 double（0.1 + 0.2 != 0.3 的那类误差会直接改错价）。
    assertThat(ProfitCalculatorRules.rate(new BigDecimal("1").divide(new BigDecimal("3"), 20, java.math.RoundingMode.HALF_UP)))
        .isEqualByComparingTo("0.333333");
  }

  @Test
  void 目标毛利率模式在没有售价时也会算出收入() {
    var reverse =
        new Input(
            null,
            new BigDecimal("0.20"),
            new BigDecimal("10"),
            new BigDecimal("0.10"),
            new BigDecimal("1"),
            new BigDecimal("4"),
            null,
            List.of(),
            fullCosts());
    var output = ProfitCalculatorRules.evaluate(TARGET_MARGIN, reverse);
    assertThat(output.revenue()).isNotNull();
    // 售价 = (1 + 4 + 16) ÷ (1 − 0.10 − 0.20) = 21 ÷ 0.7 = 30
    assertThat(output.revenue()).isEqualByComparingTo("30.0000");
    assertThat(output.grossMarginRate()).isEqualByComparingTo("0.200000");
  }

  @Test
  void 平台费同时含费率与固定费时保本价要把固定费算进分子() {
    var withFixed =
        new Input(
            new BigDecimal("100"),
            null,
            BigDecimal.ONE,
            new BigDecimal("0.10"),
            new BigDecimal("2"),
            new BigDecimal("5"),
            null,
            List.of(),
            fullCosts());
    var output = ProfitCalculatorRules.evaluate(PRICE, withFixed);
    // 保本价 = (2 + 5 + 16) ÷ (1 − 0.10) = 23 ÷ 0.9 = 25.5556
    assertThat(output.breakEvenPrice()).isEqualByComparingTo("25.5556");
    // 平台费 = 100 × 0.10 + 2 = 12
    assertThat(output.platformFeeAmount()).isEqualByComparingTo("12.0000");
  }

  @Test
  void 模式枚举与两种测算方向一一对应() {
    assertThat(CalcMode.PRICE.label()).isNotBlank();
    assertThat(CalcMode.TARGET_MARGIN.label()).isNotBlank();
    assertThat(CalcMode.parse("price")).isEqualTo(CalcMode.PRICE);
    assertThat(CalcMode.parse("nope")).isNull();
  }
}
