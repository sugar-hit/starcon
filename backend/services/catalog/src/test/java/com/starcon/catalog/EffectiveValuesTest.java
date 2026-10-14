package com.starcon.catalog;

import static org.assertj.core.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 属性有效值解析规则的单元测试。
 *
 * <p>这套规则同时决定 3.1.2.2 详情页的属性区与既有商品档案页的 SKU 矩阵， 一旦理解漂移，运营会看到"这个 SKU 明明填了值却显示成公共值"这种无法自查的错误， 所以四种取值模式
 * × 三种 SKU 侧状态（赋值 / 清空 / 没有这一行）全部穷举。
 */
class EffectiveValuesTest {

  private static EffectiveValues.Raw set(String... values) {
    return new EffectiveValues.Raw("SET", List.of(values));
  }

  private static EffectiveValues.Raw clear() {
    return new EffectiveValues.Raw("CLEAR", List.of());
  }

  /** 「没有这一行」和「有一行但没值」是两回事，前者才允许继承公共值。 */
  private static EffectiveValues.Raw absent() {
    return new EffectiveValues.Raw(null, List.of());
  }

  @Test
  void inheritsSpuCommonValueWhenSkuHasNoRow() {
    var r = EffectiveValues.resolve(EffectiveValues.COMMON_DEFAULT, set("棉"), absent(), false);
    assertThat(r.source()).isEqualTo(EffectiveValues.Source.SPU_COMMON);
    assertThat(r.overrideState()).isEqualTo("INHERIT");
    assertThat(r.effectiveValues()).containsExactly("棉");
    assertThat(r.rawValues()).isEmpty();
    assertThat(r.inherited()).isTrue();
    assertThat(r.effectiveText()).isEqualTo("棉");
  }

  @Test
  void skuAssignmentOverridesCommonValue() {
    var r = EffectiveValues.resolve(EffectiveValues.COMMON_DEFAULT, set("棉"), set("涤纶"), false);
    assertThat(r.source()).isEqualTo(EffectiveValues.Source.SKU_OVERRIDE);
    assertThat(r.overrideState()).isEqualTo("SET");
    assertThat(r.effectiveValues()).containsExactly("涤纶");
    // 原始存的仍是 SKU 侧的值，有效值也是它——覆写是同一份数据。
    assertThat(r.rawValues()).containsExactly("涤纶");
    assertThat(r.inherited()).isFalse();
  }

  @Test
  void skuClearDoesNotFallBackToCommonValue() {
    // 这是本规则最容易写错的一格：CLEAR 的有效值是空，**不是**公共值。
    // 写成 COALESCE(sku, common) 就会让「SKU 清空了公共属性」悄悄变回公共值，
    // 操作人以为清掉了，详情页却还显示着公共值。
    var r = EffectiveValues.resolve(EffectiveValues.COMMON_DEFAULT, set("一年"), clear(), false);
    assertThat(r.source()).isEqualTo(EffectiveValues.Source.SKU_CLEARED);
    assertThat(r.overrideState()).isEqualTo("CLEAR");
    assertThat(r.effectiveValues()).isEmpty();
    assertThat(r.inherited()).isFalse();
  }

  @Test
  void skuClearWinsEvenWhenCommonValueIsAbsent() {
    var r = EffectiveValues.resolve(EffectiveValues.COMMON_DEFAULT, absent(), clear(), false);
    assertThat(r.source()).isEqualTo(EffectiveValues.Source.SKU_CLEARED);
    assertThat(r.effectiveValues()).isEmpty();
  }

  @Test
  void skuOnlyNeverInheritsCommonValue() {
    // SKU_ONLY 属性没有 SKU 赋值行就是"未赋值"，不能退回任何公共值。
    var r = EffectiveValues.resolve(EffectiveValues.SKU_ONLY, set("黑色"), absent(), true);
    assertThat(r.source()).isEqualTo(EffectiveValues.Source.UNSET);
    assertThat(r.overrideState()).isEqualTo("INHERIT");
    assertThat(r.effectiveValues()).isEmpty();
    // 必填属性没值必须被标出来，否则详情页的补全清单会漏掉它。
    assertThat(r.missingRequired()).isTrue();
  }

  @Test
  void commonLockedAlwaysUsesTheCommonValue() {
    var inherited =
        EffectiveValues.resolve(EffectiveValues.COMMON_LOCKED, set("棉"), absent(), false);
    assertThat(inherited.source()).isEqualTo(EffectiveValues.Source.SPU_COMMON);
    assertThat(inherited.effectiveValues()).containsExactly("棉");
    // COMMON_LOCKED 下 V1 的 validate_value_owner 触发器已拒绝 SKU 赋值行；
    // 万一读到这样的脏数据，仍以公共值为准，不能把公共固定属性显示成 SKU 覆写。
    var dirty = EffectiveValues.resolve(EffectiveValues.COMMON_LOCKED, set("棉"), set("涤纶"), false);
    assertThat(dirty.source()).isEqualTo(EffectiveValues.Source.SPU_COMMON);
    assertThat(dirty.effectiveValues()).containsExactly("棉");
    // 公共固定属性没有公共值时就是未赋值，且必填时要被标出来。
    var noCommon = EffectiveValues.resolve(EffectiveValues.COMMON_LOCKED, absent(), absent(), true);
    assertThat(noCommon.source()).isEqualTo(EffectiveValues.Source.UNSET);
    assertThat(noCommon.missingRequired()).isTrue();
  }

  @Test
  void unsetWhenNeitherSideHasAValue() {
    var r = EffectiveValues.resolve(EffectiveValues.COMMON_DEFAULT, absent(), absent(), false);
    assertThat(r.source()).isEqualTo(EffectiveValues.Source.UNSET);
    assertThat(r.effectiveValues()).isEmpty();
    assertThat(r.effectiveText()).isEmpty();
    assertThat(r.missingRequired()).isFalse();
  }

  @Test
  void requiredMissingFlagsOnlyWhenEffectiveValueIsEmpty() {
    assertThat(
            EffectiveValues.resolve(EffectiveValues.COMMON_DEFAULT, set("棉"), absent(), true)
                .missingRequired())
        .isFalse();
    assertThat(
            EffectiveValues.resolve(EffectiveValues.COMMON_DEFAULT, set("棉"), clear(), true)
                .missingRequired())
        .isTrue();
    assertThat(
            EffectiveValues.resolve(EffectiveValues.SKU_ONLY, absent(), set("黑色"), true)
                .missingRequired())
        .isFalse();
  }

  @Test
  void multiValuedOverrideKeepsOrdinalOrder() {
    var r =
        EffectiveValues.resolve(
            EffectiveValues.COMMON_DEFAULT, absent(), set("A", "B", "C"), false);
    assertThat(r.effectiveValues()).containsExactly("A", "B", "C");
    assertThat(r.effectiveText()).isEqualTo("A / B / C");
  }

  @Test
  void valueModeLabelsAreExplicitAndRejectUnknownModes() {
    assertThat(EffectiveValues.modeLabel(EffectiveValues.COMMON_LOCKED)).isEqualTo("公共固定");
    assertThat(EffectiveValues.modeLabel(EffectiveValues.COMMON_DEFAULT)).isEqualTo("公共默认 · 可覆写");
    assertThat(EffectiveValues.modeLabel(EffectiveValues.SKU_ONLY)).isEqualTo("SKU 独立");
    // 大小写与未知值都要有确定行为，不能静默显示成空白标签。
    assertThat(EffectiveValues.modeLabel("sku_only")).isEqualTo("SKU 独立");
    assertThatThrownBy(() -> EffectiveValues.modeLabel("WHATEVER"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("未知的属性取值模式");
  }

  @Test
  void unknownSkuStateIsRejectedRatherThanSilentlyTreatedAsEmpty() {
    assertThatThrownBy(
            () ->
                EffectiveValues.resolve(
                    EffectiveValues.COMMON_DEFAULT,
                    absent(),
                    new EffectiveValues.Raw("WHATEVER", List.of()),
                    false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("未知的属性赋值状态");
  }
}
