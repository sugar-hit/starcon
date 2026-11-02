package com.starcon.catalog;

import static org.assertj.core.api.Assertions.*;

import com.starcon.catalog.PriceListItemModels.SelectionState;
import com.starcon.catalog.PriceListItemRules.Candidate;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 5.1.1.2 价目项的**纯规则**单测（无 IO、无数据库）。
 *
 * <p>本类钉死四件事：
 *
 * <ol>
 *   <li><b>归一化</b>：SKU 编码 / 单价 / 数量门槛 / 优先级；
 *   <li><b>半开区间</b>：端点相接**不算**重叠（这是冲突判定能成立的前提）；
 *   <li><b>阶梯选档</b>：先选档再选优先级 —— 顺序反了会把阶梯价判成冲突；
 *   <li><b>🔴 同优先级多条有效价目 ⇒ 歧义，绝不随机取</b>（蓝图加粗句）。
 * </ol>
 */
class PriceListItemRulesTest {

  private static final BigDecimal ZERO = new BigDecimal("0");

  // ================================================================ 归一化

  @Test
  void sku编码归一化成大写() {
    assertThat(PriceListItemRules.normalizeSkuCode("  sku-001 ")).isEqualTo("SKU-001");
    assertThatThrownBy(() -> PriceListItemRules.normalizeSkuCode("  "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("必填");
    assertThatThrownBy(() -> PriceListItemRules.normalizeSkuCode("-bad"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("首位不能是分隔符");
  }

  @Test
  void 单价必须非负且最多六位小数() {
    assertThat(PriceListItemRules.normalizePrice(new BigDecimal("19.99"), "单价"))
        .isEqualTo(new BigDecimal("19.990000"));
    assertThatThrownBy(() -> PriceListItemRules.normalizePrice(new BigDecimal("-1"), "单价"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("负数");
    assertThatThrownBy(() -> PriceListItemRules.normalizePrice(new BigDecimal("1.1234567"), "单价"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("小数");
    assertThatThrownBy(() -> PriceListItemRules.normalizePrice(null, "单价"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void 数量门槛空值即基础档() {
    assertThat(PriceListItemRules.normalizeQuantity(null, "数量门槛")).isEqualByComparingTo(ZERO);
    assertThat(PriceListItemRules.normalizeQuantity(new BigDecimal("0.5"), "数量门槛"))
        .isEqualByComparingTo(new BigDecimal("0.5"));
    assertThatThrownBy(() -> PriceListItemRules.normalizeQuantity(new BigDecimal("-1"), "数量门槛"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void 优先级数字越小越优先() {
    assertThat(PriceListItemRules.normalizePriority(null)).isZero();
    assertThat(PriceListItemRules.normalizePriority(3)).isEqualTo(3);
    assertThat(PriceListItemRules.priorityLabel(1)).contains("越小越优先");
  }

  // ================================================================ 半开区间

  @Test
  void 生效区间是半开区间() {
    var from = OffsetDateTime.parse("2026-01-01T00:00:00Z");
    var to = OffsetDateTime.parse("2026-02-01T00:00:00Z");
    // 起点含、终点不含。
    assertThat(PriceListItemRules.effectiveNow(from, to, from)).isTrue();
    assertThat(PriceListItemRules.effectiveNow(from, to, to)).isFalse();
    assertThat(PriceListItemRules.effectiveNow(from, to, from.minusSeconds(1))).isFalse();
    // 终点为空 = 长期有效。
    assertThat(PriceListItemRules.effectiveNow(from, null, from.plusYears(5))).isTrue();
    // 终点必须晚于起点。
    assertThat(PriceListItemRules.validInterval(from, to)).isTrue();
    assertThat(PriceListItemRules.validInterval(from, from)).isFalse();
    assertThat(PriceListItemRules.validInterval(from, null)).isTrue();
  }

  @Test
  void 首尾相接的两段区间不重叠() {
    var a1 = OffsetDateTime.parse("2026-01-01T00:00:00Z");
    var a2 = OffsetDateTime.parse("2026-02-01T00:00:00Z");
    var b1 = OffsetDateTime.parse("2026-02-01T00:00:00Z");
    var b2 = OffsetDateTime.parse("2026-03-01T00:00:00Z");
    assertThat(PriceListItemRules.overlaps(a1, a2, b1, b2)).isFalse();
    // 真正重叠一段。
    assertThat(PriceListItemRules.overlaps(a1, a2, a1.minusDays(1), b1)).isTrue();
    // 长期有效（终点为空）与任何一段都重叠。
    assertThat(PriceListItemRules.overlaps(a1, null, b1, b2)).isTrue();
    var overlap = PriceListItemRules.overlapOf(a1, a2, a1.minusDays(1), b1);
    assertThat(overlap[0]).isEqualTo(a1);
    assertThat(overlap[1]).isEqualTo(b1);
  }

  // ================================================================ 阶梯与优先级

  private static Candidate candidate(long minQuantity, int priority, String price) {
    return new Candidate(
        UUID.randomUUID(), new BigDecimal(minQuantity), priority, new BigDecimal(price));
  }

  @Test
  void 阶梯价先选档再选优先级() {
    var base = candidate(0, 0, "19.99");
    var bulk = candidate(100, 0, "15.99");
    var quantity = new BigDecimal("150");
    var resolution = PriceListItemRules.resolve(List.of(base, bulk), quantity);
    // 数量 150 ⇒ 门槛 ≤ 150 里门槛最大的那一档 = 100 件档。
    assertThat(resolution.appliedMinQuantity()).isEqualByComparingTo(new BigDecimal("100"));
    assertThat(resolution.selected()).isSameAs(bulk);
    assertThat(resolution.ambiguous()).isFalse();
    assertThat(resolution.states())
        .containsExactly(
            SelectionState.NOT_APPLICABLE.name(), SelectionState.SELECTED.name());
  }

  @Test
  void 数量低于最低门槛时没有适用档位() {
    var bulk = candidate(100, 0, "15.99");
    var resolution = PriceListItemRules.resolve(List.of(bulk), new BigDecimal("50"));
    assertThat(resolution.pool()).isEmpty();
    assertThat(resolution.selected()).isNull();
    assertThat(resolution.ambiguous()).isFalse();
    assertThat(resolution.states()).containsExactly(SelectionState.NOT_APPLICABLE.name());
  }

  @Test
  void 不给数量时只认基础档() {
    var base = candidate(0, 0, "19.99");
    var bulk = candidate(100, 0, "15.99");
    var resolution = PriceListItemRules.resolve(List.of(base, bulk), null);
    assertThat(resolution.selected()).isSameAs(base);
    assertThat(resolution.states().get(1)).isEqualTo(SelectionState.NOT_APPLICABLE.name());
  }

  @Test
  void 不同优先级取更优先的那条() {
    var normal = candidate(0, 5, "19.99");
    var preferred = candidate(0, 1, "17.99");
    var resolution = PriceListItemRules.resolve(List.of(normal, preferred), null);
    assertThat(resolution.ambiguous()).isFalse();
    assertThat(resolution.selected()).isSameAs(preferred);
    assertThat(resolution.states())
        .containsExactly(
            SelectionState.LOWER_PRIORITY.name(), SelectionState.SELECTED.name());
  }

  @Test
  void 同优先级多条有效价目必须判为歧义而不是随便挑一条() {
    var first = candidate(0, 0, "19.99");
    var second = candidate(0, 0, "21.99");
    var resolution = PriceListItemRules.resolve(List.of(first, second), null);
    assertThat(resolution.ambiguous()).isTrue();
    // 🔴 歧义时 selected 恒为 null —— 系统不随机取一条。
    assertThat(resolution.selected()).isNull();
    assertThat(resolution.pool()).hasSize(2);
    assertThat(resolution.states())
        .containsExactly(
            SelectionState.AMBIGUOUS.name(), SelectionState.AMBIGUOUS.name());
    assertThat(PriceListItemRules.ambiguityProblem("SKU-1", ZERO, 2))
        .contains("不会随机取一条");
  }

  @Test
  void 歧义说明点名了SKU与档位() {
    var message = PriceListItemRules.ambiguityProblem("SKU-9", new BigDecimal("100"), 3);
    assertThat(message).contains("SKU-9").contains("100").contains("3");
  }

  // ================================================================ 金额换算

  @Test
  void 按百分比换算走BigDecimal() {
    assertThat(PriceListItemRules.applyPercent(new BigDecimal("19.99"), new BigDecimal("10")))
        .isEqualByComparingTo(new BigDecimal("21.989000"));
    assertThat(PriceListItemRules.applyPercent(new BigDecimal("19.99"), new BigDecimal("-10")))
        .isEqualByComparingTo(new BigDecimal("17.991000"));
    // 只有最后一步定标到 6 位。
    assertThat(PriceListItemRules.applyPercent(new BigDecimal("1"), new BigDecimal("7.5")))
        .isEqualByComparingTo(new BigDecimal("1.075000"));
  }

  @Test
  void 按百分比算出负数时拒绝() {
    assertThatThrownBy(
            () -> PriceListItemRules.applyPercent(new BigDecimal("10"), new BigDecimal("-200")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("负数");
  }

  @Test
  void 数量展示文本() {
    assertThat(PriceListItemRules.quantityText(ZERO)).isEqualTo("基础档");
    assertThat(PriceListItemRules.quantityText(new BigDecimal("100.000000"))).isEqualTo("100");
    assertThat(PriceListItemRules.quantityText(null)).isEqualTo("基础档");
  }
}
