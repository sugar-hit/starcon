package com.starcon.catalog;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 3.5.2.2 BOM 展开的纯规则层：深度上限、逐层结转的需求量、缺口与余量、子 BOM 候选选择。
 *
 * <p>不碰数据库、时钟与 HTTP：给定同样的输入永远返回同样的结论。
 *
 * <p><b>跨模块口径一致性</b>是本文件最重要的一类断言：
 * 展开的需求量必须与 3.5.2.1 的单层需求量<b>逐位相同</b>。
 * 「同一句公式写两份，迟早有一份被改松」—— 而那处偏差只在跨模块比对里才暴露，
 * 所以这里把它钉成回归断言。
 */
class BomExplosionRulesTest {

  // ================================================================ 深度上限

  @Test
  void 深度上限不传时取默认值且越界被拒() {
    assertThat(BomExplosionRules.resolveMaxDepth(null))
        .isEqualTo(BomExplosionRules.DEFAULT_MAX_DEPTH);
    assertThat(BomExplosionRules.maxDepthProblem(null)).isNull();
    assertThat(BomExplosionRules.resolveMaxDepth(3)).isEqualTo(3);
    assertThat(BomExplosionRules.maxDepthProblem(3)).isNull();
    assertThat(BomExplosionRules.maxDepthProblem(0)).contains("至少是 1");
    assertThat(BomExplosionRules.maxDepthProblem(BomExplosionRules.MAX_DEPTH_LIMIT + 1))
        .contains("展开深度上限最多");
    // 上界本身合法：不能出现「最大值也报错」这种自相矛盾的边界。
    assertThat(BomExplosionRules.maxDepthProblem(BomExplosionRules.MAX_DEPTH_LIMIT)).isNull();
  }

  @Test
  void resolveMaxDepth对越界值做截断而不是抛出() {
    // 服务层先跑 maxDepthProblem 给出可读的拒绝理由；这里保证纯函数自身不会崩。
    assertThat(BomExplosionRules.resolveMaxDepth(0)).isEqualTo(1);
    assertThat(BomExplosionRules.resolveMaxDepth(9999))
        .isEqualTo(BomExplosionRules.MAX_DEPTH_LIMIT);
  }

  // ================================================================ E1 逐层结转

  @Test
  void 需求量与3_5_2_1的单层公式逐位相同() {
    // R7：单位用量 × 产量 × (1 + 损耗率/100)，保留 6 位小数 HALF_UP，不取整。
    var cases = List.of(
        new String[] {"2", "5", "10"}, new String[] {"0.5", "0", "3"},
        new String[] {"1", "50", "3"}, new String[] {"0.125", "12.5", "7"},
        new String[] {"3", "0", "100"}, new String[] {"0.000001", "100", "1"});
    for (String[] c : cases) {
      var unit = new BigDecimal(c[0]);
      var scrap = new BigDecimal(c[1]);
      var driver = new BigDecimal(c[2]);
      assertThat(BomExplosionRules.requiredQuantity(driver, unit, scrap))
          .as("用量=%s 损耗=%s 产量=%s 必须与 3.5.2.1 逐位一致", c[0], c[1], c[2])
          .isEqualTo(BomRules.requiredQuantity(unit, scrap, driver));
    }
  }

  @Test
  void 损耗率是百分数不是比率() {
    // 5 表示 5%：1 × 10 × 1.05 = 10.5。若按比率读成 0.05，会得到 10.4（少算 1%）。
    assertThat(BomExplosionRules.requiredQuantity(new BigDecimal("10"), BigDecimal.ONE,
            new BigDecimal("5")))
        .isEqualByComparingTo("10.500000");
    // 反面：有人存比率（把「5%」写成 0.05）时，同一条公式把它读成 0.05% ——
    // 得到 10.005 而不是 10.5，**少领 1% 的料且不报错**。这就是 R6 必须存百分数的原因。
    assertThat(BomExplosionRules.requiredQuantity(
            new BigDecimal("10"), new BigDecimal("1"), new BigDecimal("0.05")))
        .isEqualByComparingTo("10.005000");
    // 零损耗 = 原值，不引入浮点误差。
    assertThat(BomExplosionRules.requiredQuantity(new BigDecimal("3"), BigDecimal.ZERO, BigDecimal.ZERO))
        .isEqualByComparingTo("0E-6");
  }

  @Test
  void 需求量不取整() {
    // 0.5 米 × 3 个 × 1.10 = 1.65；取整成 1 或 2 都是在下游拿到单据之前替它做了决定。
    assertThat(BomExplosionRules.requiredQuantity(
            new BigDecimal("3"), new BigDecimal("0.5"), new BigDecimal("10")))
        .isEqualByComparingTo("1.650000");
    // 0.333333 × 1 × 1 = 0.333333：不能被悄悄取整成 0。
    assertThat(BomExplosionRules.requiredQuantity(
            new BigDecimal("0.333333"), BigDecimal.ONE, BigDecimal.ZERO))
        .isEqualByComparingTo("0.333333");
  }

  // ================================================================ E2 缺口

  @Test
  void 缺口等于需求量减可用减在途且下限截零() {
    assertThat(BomExplosionRules.gap(new BigDecimal("20"), new BigDecimal("4"), new BigDecimal("1")))
        .isEqualByComparingTo("15.000000");
    // 在途可抵扣：货权已归本租户（已经买了只是没到），抵扣它是为了不重复下单。
    assertThat(BomExplosionRules.gap(new BigDecimal("20"), new BigDecimal("4"), new BigDecimal("16")))
        .isEqualByComparingTo("0.000000");
    // 余量不为负：负数表达「多出来多少」，对采购决策无用。
    assertThat(BomExplosionRules.gap(new BigDecimal("20"), new BigDecimal("30"), new BigDecimal("5")))
        .isEqualByComparingTo("0.000000");
    assertThat(BomExplosionRules.gap(BigDecimal.ZERO, new BigDecimal("30"), BigDecimal.ZERO))
        .isEqualByComparingTo("0.000000");
  }

  @Test
  void 余量只在库存超过需求时非零() {
    assertThat(BomExplosionRules.surplus(new BigDecimal("20"), new BigDecimal("30"), new BigDecimal("5")))
        .isEqualByComparingTo("15.000000");
    assertThat(BomExplosionRules.surplus(new BigDecimal("20"), new BigDecimal("4"), new BigDecimal("1")))
        .isEqualByComparingTo("0.000000");
    // 恰好覆盖时余量为 0（不多不少）。
    assertThat(BomExplosionRules.surplus(new BigDecimal("20"), new BigDecimal("10"), new BigDecimal("10")))
        .isEqualByComparingTo("0.000000");
  }

  @Test
  void 覆盖判定用可用加在途() {
    assertThat(BomExplosionRules.covered(new BigDecimal("10"), new BigDecimal("6"), new BigDecimal("4"))).isTrue();
    assertThat(BomExplosionRules.covered(new BigDecimal("10"), new BigDecimal("6"), new BigDecimal("3"))).isFalse();
    assertThat(BomExplosionRules.covered(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO)).isTrue();
  }

  @Test
  void 只有缺口为正的行才是采购对象() {
    assertThat(BomExplosionRules.needsProcurement(new BigDecimal("0.000001"))).isTrue();
    assertThat(BomExplosionRules.needsProcurement(BigDecimal.ZERO)).isFalse();
    assertThat(BomExplosionRules.needsProcurement(null)).isFalse();
  }

  // ================================================================ E3 子 BOM 候选

  @Test
  void 子bom候选按编码序确定性地取第一个() {
    var c1 = new BomExplosionRules.BomCandidate(UUID.randomUUID(), "BOM-AAA");
    var c2 = new BomExplosionRules.BomCandidate(UUID.randomUUID(), "BOM-BBB");
    // 传入顺序与选中顺序无关：确定性必须来自排序键，不是来自调用方给的顺序。
    var picked = BomExplosionRules.pickSubBom(List.of(c2, c1)).orElseThrow();
    assertThat(picked.getKey().code()).isEqualTo("BOM-AAA");
    assertThat(picked.getValue()).isTrue();
    // 只有一个候选：不算歧义。
    var single = BomExplosionRules.pickSubBom(List.of(c2)).orElseThrow();
    assertThat(single.getValue()).isFalse();
    // 没有候选：这个 SKU 不是任何 BOM 的成品，按叶子件处理。
    assertThat(BomExplosionRules.pickSubBom(List.of())).isEmpty();
    assertThat(BomExplosionRules.pickSubBom(null)).isEmpty();
  }

  @Test
  void 编码相同时用id兜底保证顺序唯一() {
    var one = new BomExplosionRules.BomCandidate(
        UUID.fromString("00000000-0000-0000-0000-000000000001"), "BOM-X");
    var two = new BomExplosionRules.BomCandidate(
        UUID.fromString("00000000-0000-0000-0000-000000000002"), "BOM-X");
    // 同一 SKU 被两个同编码 BOM 声明时，仍然必须选出**同一个**（而不是取决于输入顺序）。
    assertThat(BomExplosionRules.pickSubBom(List.of(two, one)).orElseThrow().getKey().id())
        .isEqualTo(one.id());
    assertThat(BomExplosionRules.pickSubBom(List.of(one, two)).orElseThrow().getKey().id())
        .isEqualTo(one.id());
  }
}
