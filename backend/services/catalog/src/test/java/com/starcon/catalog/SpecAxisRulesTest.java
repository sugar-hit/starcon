package com.starcon.catalog;

import static org.assertj.core.api.Assertions.*;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 3.1.3.1 规格轴纯规则：轴编码、选项值、矩阵行数。与前端 spec-axis-model.ts 同口径。 */
class SpecAxisRulesTest {

  @Test
  void codeIsNormalizedToUpperCase() {
    assertThat(SpecAxisRules.normalizeCode(" color ")).isEqualTo("COLOR");
    assertThat(SpecAxisRules.normalizeCode("Capacity")).isEqualTo("CAPACITY");
    assertThat(SpecAxisRules.normalizeCode(null)).isEmpty();
  }

  @Test
  void asciiCodeVocabularyIsAccepted() {
    assertThat(SpecAxisRules.codeProblem("COLOR")).isNull();
    assertThat(SpecAxisRules.codeProblem("SIZE_2")).isNull();
    assertThat(SpecAxisRules.codeProblem("A")).isNull();
    // 64 位上限。
    assertThat(SpecAxisRules.codeProblem("A".repeat(64))).isNull();
  }

  @Test
  void emptyOrInvalidCodeIsRejectedWithReason() {
    assertThat(SpecAxisRules.codeProblem("")).contains("不能为空");
    assertThat(SpecAxisRules.codeProblem("   ")).contains("不能为空");
    // 中文码在这里是错的：轴码是机器标识，选项值才允许中文。
    assertThat(SpecAxisRules.codeProblem("颜色")).contains("英文字母");
    assertThat(SpecAxisRules.codeProblem("A".repeat(65))).contains("长度");
  }

  @Test
  void optionValueAcceptsRealWorldSpecText() {
    assertThat(SpecAxisRules.valueProblem("深空灰")).isNull();
    assertThat(SpecAxisRules.valueProblem("XL")).isNull();
    assertThat(SpecAxisRules.valueProblem("500ml")).isNull();
    assertThat(SpecAxisRules.valueProblem("2 件装")).isNull();
    assertThat(SpecAxisRules.valueProblem("黑色/哑光")).isNull();
    assertThat(SpecAxisRules.valueProblem("A".repeat(100))).isNull();
  }

  @Test
  void optionValueRejectsEmptyOversizedAndIllegal() {
    assertThat(SpecAxisRules.valueProblem("")).contains("不能为空");
    assertThat(SpecAxisRules.valueProblem("   ")).contains("不能为空");
    // 与 attribute_option.value varchar(100) 同长度：两处存同一个值，长度必须一致。
    assertThat(SpecAxisRules.valueProblem("A".repeat(101))).contains("100");
    assertThat(SpecAxisRules.valueProblem("-前置横线")).contains("只能使用");
    assertThat(SpecAxisRules.valueProblem("红#色")).contains("只能使用");
  }

  @Test
  void matrixRowCountIsCartesianProduct() {
    assertThat(SpecAxisRules.matrixRowCount(List.of(set("红", "蓝"), set("S", "M", "L"))))
        .isEqualTo(6);
    assertThat(SpecAxisRules.matrixRowCount(List.of(set("红", "蓝")))).isEqualTo(2);
    // 没有轴时是一个空组合，不是 0 个 SKU。
    assertThat(SpecAxisRules.matrixRowCount(List.of())).isEqualTo(1);
  }

  @Test
  void anAxisWithoutAvailableOptionsMakesMatrixEmpty() {
    // 任一轴无可用选项 → 矩阵为 0，而不是把该轴按 1 假装有值。
    assertThat(SpecAxisRules.matrixRowCount(List.of(set("红", "蓝"), set()))).isZero();
    assertThat(SpecAxisRules.matrixRowCount(Arrays.asList(set("红"), null))).isZero();
  }

  @Test
  void matrixRowCountDoesNotOverflowIntoNegative() {
    // 四条各 50 个选项 = 6.25M；再多就该接近 long 上限，不能变成负数。
    Set<String> fifty = new LinkedHashSet<>();
    for (int i = 0; i < 50; i++) fifty.add("v" + i);
    assertThat(SpecAxisRules.matrixRowCount(List.of(fifty, fifty, fifty, fifty)))
        .isEqualTo(6_250_000L);
    // 溢出被钳到上限而不是回绕：界面上按「规模过大」提示，而不是显示一个错的行数。
    long huge = SpecAxisRules.matrixRowCount(List.of(fifty, fifty, fifty, fifty, fifty, fifty));
    assertThat(huge).isPositive();
  }

  @Test
  void distinctValuesTrimsDropsBlanksAndKeepsOrder() {
    assertThat(SpecAxisRules.distinctValues(List.of(" 红 ", "蓝", "", "  ", "红")))
        .containsExactly("红", "蓝");
    assertThat(SpecAxisRules.distinctValues(null)).isEmpty();
  }

  private static Set<String> set(String... values) {
    return new LinkedHashSet<>(List.of(values));
  }
}
