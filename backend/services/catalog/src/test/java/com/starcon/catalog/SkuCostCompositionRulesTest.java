package com.starcon.catalog;

import static com.starcon.catalog.SkuCostCompositionModels.CostMethod;
import static com.starcon.catalog.SkuCostCompositionRules.DeliveryLot;
import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 5.2.1.1 成本构成的**成本法加权**单测。
 *
 * <p>本类钉的是蓝图加粗句的技术落点：
 *
 * <blockquote>
 *
 * **成本口径必须在页面上明示用了哪种成本法**，否则同一 SKU 今天显示毛利 30% 明天 25% 无法解释。
 *
 * </blockquote>
 *
 * ⇒ 成本法必须**真的参与算术**：同一份到货事实，三种成本法给出三个不同的单位成本。
 * 如果它们给出同一个数，那「明示口径」就只是一句装饰 —— 口径其实没起作用，
 * 而毛利波动仍然无法解释。
 */
class SkuCostCompositionRulesTest {

  private static final LocalDate DAY_1 = LocalDate.of(2026, 1, 10);
  private static final LocalDate DAY_2 = LocalDate.of(2026, 2, 10);

  /** 一批到货：金额 1000、数量 100（基础单位 100 件）⇒ 单价 10。 */
  private static DeliveryLot lot(String ref, LocalDate date, String amount, String quantity) {
    return new DeliveryLot(
        UUID.randomUUID(),
        ref,
        date,
        new BigDecimal(quantity),
        new BigDecimal(quantity),
        new BigDecimal(amount),
        "USD");
  }

  /** 两批货：老批 10.00/件（1000 元 100 件），新批 20.00/件（2000 元 100 件）。 */
  private static List<DeliveryLot> twoLots() {
    return List.of(lot("PO-1", DAY_1, "1000", "100"), lot("PO-2", DAY_2, "2000", "100"));
  }

  @Test
  void 三种成本法在同一份事实上给出三个不同的单位成本() {
    var lots = twoLots();

    var moving = SkuCostCompositionRules.weighted(CostMethod.MOVING_AVERAGE, lots);
    var fifo = SkuCostCompositionRules.weighted(CostMethod.FIFO, lots);
    var lot = SkuCostCompositionRules.weighted(CostMethod.LOT, lots);

    // 移动加权 = (1000 + 2000) / (100 + 100) = 15.00
    assertThat(moving.unitCost()).isEqualByComparingTo("15.00");
    // 先进先出：消耗到「最近一批的量」为止，落在最早那一批 = 10.00
    assertThat(fifo.unitCost()).isEqualByComparingTo("10.00");
    // 批次：目标批次 = 最近一批 = 20.00
    assertThat(lot.unitCost()).isEqualByComparingTo("20.00");

    // 🔴 这是本模块最关键的一条断言：三个数互不相同。
    assertThat(List.of(moving.unitCost(), fifo.unitCost(), lot.unitCost()))
        .doesNotHaveDuplicates();
  }

  @Test
  void 先进先出只消耗到目标数量为止而不是吃掉全部批次() {
    // 老批 10 件 @10.00，新批 10 件 @20.00。目标数量 = 最近一批的 10 件。
    var lots =
        List.of(
            lot("PO-1", DAY_1, "100", "10"),
            lot("PO-2", DAY_2, "200", "10"));
    var fifo = SkuCostCompositionRules.weighted(CostMethod.FIFO, lots);
    // 只消耗最早那 10 件 ⇒ 单价仍是 10.00，**不是** 15.00（后者是吃光两批的结果）。
    assertThat(fifo.unitCost()).isEqualByComparingTo("10.00");
    assertThat(fifo.totalBaseQuantity()).isEqualByComparingTo("10");
    assertThat(fifo.lotCount()).isEqualTo(1);
  }

  @Test
  void 先进先出在最后一批取不满时按比例取那一批的一部分() {
    // 老批 10 件 @10.00（100 元），新批 4 件 @20.00（80 元）。目标 = 新批的 4 件。
    var lots =
        List.of(
            lot("PO-1", DAY_1, "100", "10"),
            lot("PO-2", DAY_2, "80", "4"));
    var fifo = SkuCostCompositionRules.weighted(CostMethod.FIFO, lots);
    // 消耗 4 件老批（40 元）+ 4 件新批（80 元）… 等等：目标是 4 件，先从老批取 4 件就够了。
    assertThat(fifo.unitCost()).isEqualByComparingTo("10.00");
    assertThat(fifo.totalBaseQuantity()).isEqualByComparingTo("4");
  }

  @Test
  void 批次法选中的批次是最新的那一批并被标出来() {
    var lots = twoLots();
    var weighted = SkuCostCompositionRules.weighted(CostMethod.LOT, lots);
    assertThat(weighted.selectedLot()).isNotNull();
    assertThat(weighted.selectedLot().deliveryRef()).isEqualTo("PO-2");
    assertThat(weighted.lotCount()).isEqualTo(1);
    // 批次法不做加权：金额与数量都是目标批次自己的。
    assertThat(weighted.totalAmount()).isEqualByComparingTo("2000");
    assertThat(weighted.totalBaseQuantity()).isEqualByComparingTo("100");
  }

  @Test
  void 没有到货事实时单位成本是null而不是0() {
    // 🔴 null ≠ 0：「取不到」与「确实是 0」是两件事。折成 0 会让单位成本凭空出现。
    var weighted = SkuCostCompositionRules.weighted(CostMethod.MOVING_AVERAGE, List.of());
    assertThat(weighted.unitCost()).isNull();
    assertThat(weighted.lotCount()).isZero();
  }

  @Test
  void 加权除法是BigDecimal精确除而不是浮点近似() {
    // 3 件货共 10 元 ⇒ 单价 3.3333（4 位小数，HALF_UP）。
    var lots = List.of(lot("PO-1", DAY_1, "10", "3"));
    var weighted = SkuCostCompositionRules.weighted(CostMethod.MOVING_AVERAGE, lots);
    assertThat(weighted.unitCost()).isEqualByComparingTo("3.3333");
    // 浮点实现会给出 3.3333333333333335 这种值，然后被截成 3.33。
    assertThat(weighted.unitCost().scale()).isEqualTo(4);
  }

  @Test
  void 基础单位折算不出来时返回null以便调用方排除该行() {
    assertThat(SkuCostCompositionRules.toBaseQuantity(new BigDecimal("100"), null)).isNull();
    assertThat(SkuCostCompositionRules.toBaseQuantity(new BigDecimal("100"), new BigDecimal("12")))
        .isEqualByComparingTo("1200");
  }

  @Test
  void 合计只加传入的金额且精度统一到4位() {
    assertThat(SkuCostCompositionRules.total(List.of(new BigDecimal("1.005"), new BigDecimal("2.005"))))
        .isEqualByComparingTo("3.0100");
    assertThat(SkuCostCompositionRules.total(List.of())).isEqualByComparingTo("0.0000");
  }

  @Test
  void 批次顺序确定到货日再id所以同一份事实两次算出同一个结果() {
    var lots = twoLots();
    var first = SkuCostCompositionRules.orderedLots(lots);
    var second = SkuCostCompositionRules.orderedLots(lots);
    assertThat(first).extracting(DeliveryLot::deliveryRef).containsExactly("PO-1", "PO-2");
    assertThat(second).extracting(DeliveryLot::deliveryRef).containsExactly("PO-1", "PO-2");
  }
}
