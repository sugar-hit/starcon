package com.starcon.catalog;

import static org.assertj.core.api.Assertions.*;

import com.starcon.catalog.LandedCostRules;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 5.2.1.2 到岸成本：分摊守恒与「null ≠ 0」的纯计算单测。
 *
 * <p><b>本文件最要紧的一条</b>：{@link LandedCostRules#allocateByQuantity} 必须让
 * 「各行分摊金额之和 == 批次级来源总额」**逐字成立**。
 * 比来源大就是多算成本，比来源小就是漏算成本 ——
 * 而漏算正是本模块最危险的失败模式（到岸成本被低估 ⇒ 定价偏低 ⇒ 亏本发货）。
 */
class LandedCostRulesTest {

  private static BigDecimal n(String value) {
    return new BigDecimal(value);
  }

  @Test
  @DisplayName("按数量比例分摊后，各行之和严格等于来源总额")
  void 分摊逐字守恒() {
    var shares = LandedCostRules.allocateByQuantity(n("100.0000"), List.of(n("1"), n("1"), n("1")));
    assertThat(shares).hasSize(3);
    var sum = shares.stream().map(x -> x.add(BigDecimal.ZERO)).reduce(BigDecimal.ZERO, BigDecimal::add);
    assertThat(sum).isEqualByComparingTo("100.0000");
  }

  @Test
  @DisplayName("三等分时余数补给其中一行，总额仍然逐字相等")
  void 三等分的余数处理() {
    var shares = LandedCostRules.allocateByQuantity(n("100"), List.of(n("1"), n("1"), n("1")));
    // 100/3 = 33.3333…：先各取 33.3333，余下 0.0001 补给其中一行。
    assertThat(shares).containsExactlyInAnyOrder(n("33.3334"), n("33.3333"), n("33.3333"));
    var sum = shares.stream().map(x -> x.add(BigDecimal.ZERO)).reduce(BigDecimal.ZERO, BigDecimal::add);
    assertThat(sum).isEqualByComparingTo("100");
  }

  @Test
  @DisplayName("比例明显不同时按比例分摊")
  void 按比例分摊() {
    var shares = LandedCostRules.allocateByQuantity(n("100"), List.of(n("3"), n("1")));
    assertThat(shares.get(0)).isEqualByComparingTo("75.0000");
    assertThat(shares.get(1)).isEqualByComparingTo("25.0000");
  }

  @Test
  @DisplayName("来源总额为 null 时每一行都是 null（**不是 0**）")
  void 来源没有数时给空而不是给零() {
    var shares = LandedCostRules.allocateByQuantity(null, List.of(n("1"), n("2")));
    assertThat(shares).containsExactly(null, null);
  }

  @Test
  @DisplayName("数量之和为 0 时拒绝分摊（不是摊成 0 行）")
  void 数量和为零时拒绝() {
    assertThatThrownBy(() -> LandedCostRules.allocateByQuantity(n("100"), List.of(n("0"), n("0"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("无法按比例分摊");
  }

  @Test
  @DisplayName("单位金额：除不尽时按 4 位 HALF_UP，不产生浮点误差")
  void 单位金额精度() {
    assertThat(LandedCostRules.unitAmount(n("100"), n("3"))).isEqualByComparingTo("33.3333");
    assertThat(LandedCostRules.unitAmount(n("100"), n("0"))).isNull();
    assertThat(LandedCostRules.unitAmount(null, n("3"))).isNull();
  }

  @Test
  @DisplayName("合计：全为 null 时是 null（算不出来），不是 0")
  void 合计区分空与零() {
    // 🔴 List.of 不许 null，这里必须用 Arrays.asList。
    assertThat(LandedCostRules.total(java.util.Arrays.asList(null, null))).isNull();
    assertThat(LandedCostRules.total(List.of())).isNull();
    assertThat(LandedCostRules.total(List.of(n("1.0000"), n("2.0000")))).isEqualByComparingTo("3.0000");
    // 「真的是 0」与「没有数」是两件事：0 要原样返回。
    assertThat(LandedCostRules.total(List.of(n("0.0000")))).isEqualByComparingTo("0.0000");
  }
}
