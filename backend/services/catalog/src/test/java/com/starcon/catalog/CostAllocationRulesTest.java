package com.starcon.catalog;

import static com.starcon.catalog.CostAllocationModels.BasisRule;
import static com.starcon.catalog.CostAllocationRules.BasisLine;
import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 5.2.1.3 成本分摊的**分摊算术**单测。
 *
 * <p>本类钉的是蓝图加粗句的技术落点：
 *
 * <blockquote>
 *
 * **分摊规则变更会导致历史利润变化**，因此调整必须留历史版本，不能就地覆盖。
 *
 * </blockquote>
 *
 * ⇒ 分摊规则必须**真的参与算术**：同一批货按重量摊与按金额摊给出**不同的**逐行金额。
 * 如果两者给出同一组数，那「换规则会改变历史利润」就只是一句装饰 —— 规则其实没起作用。
 *
 * <p>另一条同样不可让步：<b>逐行分摊额之和必须严格等于货件成本总额</b>。
 * 分摊是拆分不是缩水；差一分钱也是一个对不上的账。
 */
class CostAllocationRulesTest {

  private static final UUID A = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
  private static final UUID B = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
  private static final UUID C = UUID.fromString("00000000-0000-0000-0000-0000000000c3");

  /** 一行：数量 / 毛重（克）/ 体积（立方厘米）/ 货值，基数一律可得。 */
  private static BasisLine line(
      UUID skuId, String quantity, String weightGrams, String volumeCm3, String amountBasis) {
    return new BasisLine(
        skuId,
        new BigDecimal(quantity),
        new BigDecimal(weightGrams),
        new BigDecimal(volumeCm3),
        new BigDecimal(amountBasis),
        true,
        "");
  }

  /** 一行：基数不可得（重量没维护、货值没维护……）。 */
  private static BasisLine unavailable(UUID skuId, String quantity, String note) {
    return new BasisLine(
        skuId,
        new BigDecimal(quantity),
        null,
        null,
        null,
        false,
        note);
  }

  @Test
  void 逐行分摊额之和严格等于货件成本总额() {
    // 100 元三等分：33.3333 × 3 = 99.9999，差的那 0.0001 必须被补回去。
    var outcome =
        CostAllocationRules.allocate(
            new BigDecimal("100"),
            List.of(line(A, "1", "1000", "1000", "100"), line(B, "1", "1000", "1000", "100"),
                line(C, "1", "1000", "1000", "100")),
            BasisRule.QUANTITY);

    var sum =
        outcome.lines().stream()
            .map(CostAllocationRules.AllocatedLine::allocatedAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    assertThat(sum).isEqualByComparingTo("100.0000");
    // 补的 0.0001 落在小数部分最大的那一行上；并列时按 skuId 升序 ⇒ 结果可复现。
    assertThat(outcome.lines().get(0).allocatedAmount()).isEqualByComparingTo("33.3334");
    assertThat(outcome.lines().get(1).allocatedAmount()).isEqualByComparingTo("33.3333");
    assertThat(outcome.lines().get(2).allocatedAmount()).isEqualByComparingTo("33.3333");
  }

  @Test
  void 同样一批货换规则会给出不同的逐行金额() {
    // 一样的两行货：数量 1:10，重量 1000:3000，货值 10:100。
    var lines =
        List.of(line(A, "1", "1000", "1000", "10"), line(B, "10", "3000", "3000", "100"));

    var byQuantity =
        CostAllocationRules.allocate(new BigDecimal("110"), lines, BasisRule.QUANTITY);
    var byWeight = CostAllocationRules.allocate(new BigDecimal("110"), lines, BasisRule.WEIGHT);
    var byAmount = CostAllocationRules.allocate(new BigDecimal("110"), lines, BasisRule.AMOUNT);

    // 数量口径：1 : 10 ⇒ 10 : 100
    assertThat(byQuantity.lines().get(0).allocatedAmount()).isEqualByComparingTo("10.0000");
    assertThat(byQuantity.lines().get(1).allocatedAmount()).isEqualByComparingTo("100.0000");
    // 重量口径：1000 : 3000 ⇒ 27.5 : 82.5
    assertThat(byWeight.lines().get(0).allocatedAmount()).isEqualByComparingTo("27.5000");
    assertThat(byWeight.lines().get(1).allocatedAmount()).isEqualByComparingTo("82.5000");
    // 货值口径：10 : 100 ⇒ 10 : 100（这一批的货值比例与数量比例相同）
    assertThat(byAmount.lines().get(0).allocatedAmount()).isEqualByComparingTo("10.0000");
    assertThat(byAmount.lines().get(1).allocatedAmount()).isEqualByComparingTo("100.0000");
  }

  @Test
  void 体积口径按体积占比分摊() {
    var lines = List.of(line(A, "1", "1000", "1000", "10"), line(B, "1", "1000", "9000", "10"));
    var outcome = CostAllocationRules.allocate(new BigDecimal("1000"), lines, BasisRule.VOLUME);
    assertThat(outcome.lines().get(0).allocatedAmount()).isEqualByComparingTo("100.0000");
    assertThat(outcome.lines().get(1).allocatedAmount()).isEqualByComparingTo("900.0000");
    assertThat(outcome.basisTotal()).isEqualByComparingTo("10000");
  }

  @Test
  void 基数取不到的行不参与分母且明确标为不可得() {
    // B 行没有重量 ⇒ 按重量摊时它不参与分母，全部金额归 A。
    var lines =
        List.of(line(A, "1", "1000", "1000", "10"), unavailable(B, "1", "没申报毛重"));
    var outcome = CostAllocationRules.allocate(new BigDecimal("100"), lines, BasisRule.WEIGHT);

    assertThat(outcome.eligibleCount()).isEqualTo(1);
    assertThat(outcome.lines().get(0).allocatedAmount()).isEqualByComparingTo("100.0000");
    var b = outcome.lines().get(1);
    assertThat(b.basisAvailable()).isFalse();
    // 🔴 基数不可得不是「摊到 0」：基数本身是 null，不是 0。
    assertThat(b.basisValue()).isNull();
    assertThat(b.allocatedAmount()).isEqualByComparingTo("0.0000");
    assertThat(b.basisNote()).contains("没申报毛重");
  }

  @Test
  void 所有行都取不到基数时拒绝分摊而不是摊成全零() {
    var lines = List.of(unavailable(A, "1", "没申报毛重"), unavailable(B, "1", "没申报毛重"));
    assertThatThrownBy(() -> CostAllocationRules.allocate(new BigDecimal("100"), lines, BasisRule.WEIGHT))
        .isInstanceOf(CostAllocationRules.BasisUnavailableException.class)
        .hasMessageContaining("没有可用的分摊基数");
  }

  @Test
  void 相同输入两次计算结果完全一致() {
    // 规则变更会让历史利润变化 ⇒ 结果必须可复现，否则「差异对比」全是噪声。
    var lines =
        List.of(line(A, "1", "1000", "1000", "10"), line(B, "1", "7000", "1000", "30"),
            line(C, "1", "2000", "1000", "20"));
    var first = CostAllocationRules.allocate(new BigDecimal("100"), lines, BasisRule.WEIGHT);
    var second = CostAllocationRules.allocate(new BigDecimal("100"), lines, BasisRule.WEIGHT);
    for (int i = 0; i < first.lines().size(); i++) {
      assertThat(second.lines().get(i).allocatedAmount())
          .isEqualByComparingTo(first.lines().get(i).allocatedAmount());
    }
  }

  @Test
  void 人工微调是相对计算分摊额的增减且最终额等于两者之和() {
    assertThat(CostAllocationRules.finalAmount(new BigDecimal("10.0000"), new BigDecimal("2.5")))
        .isEqualByComparingTo("12.5000");
    assertThat(CostAllocationRules.finalAmount(new BigDecimal("10.0000"), new BigDecimal("-2.5")))
        .isEqualByComparingTo("7.5000");
    assertThat(CostAllocationRules.finalAmount(new BigDecimal("10.0000"), null))
        .isEqualByComparingTo("10.0000");
  }

  @Test
  void 差异对比能看出新增移除与变化() {
    var before =
        List.of(
            new CostAllocationModels.PricingCostAllocationLineRow(A, "A", "A", BigDecimal.ONE, null, null, null,
                null, true, "", new BigDecimal("10.0000"), BigDecimal.ZERO, "",
                new BigDecimal("10.0000")),
            new CostAllocationModels.PricingCostAllocationLineRow(B, "B", "B", BigDecimal.ONE, null, null, null,
                null, true, "", new BigDecimal("20.0000"), BigDecimal.ZERO, "",
                new BigDecimal("20.0000")));
    var after =
        List.of(
            new CostAllocationModels.PricingCostAllocationLineRow(A, "A", "A", BigDecimal.ONE, null, null, null,
                null, true, "", new BigDecimal("12.0000"), BigDecimal.ZERO, "",
                new BigDecimal("12.0000")),
            new CostAllocationModels.PricingCostAllocationLineRow(C, "C", "C", BigDecimal.ONE, null, null, null,
                null, true, "", new BigDecimal("8.0000"), BigDecimal.ZERO, "",
                new BigDecimal("8.0000")));

    var diff = CostAllocationRules.diff(before, after);
    assertThat(diff).hasSize(3);
    assertThat(diff).anySatisfy(
        row -> {
          assertThat(row.skuId()).isEqualTo(A);
          assertThat(row.change()).isEqualTo("CHANGED");
          assertThat(row.delta()).isEqualByComparingTo("2.0000");
        });
    assertThat(diff).anySatisfy(
        row -> {
          assertThat(row.skuId()).isEqualTo(B);
          assertThat(row.change()).isEqualTo("REMOVED");
          assertThat(row.delta()).isNull();
        });
    assertThat(diff).anySatisfy(
        row -> {
          assertThat(row.skuId()).isEqualTo(C);
          assertThat(row.change()).isEqualTo("ADDED");
        });
  }
}
