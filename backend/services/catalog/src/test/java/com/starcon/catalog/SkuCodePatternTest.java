package com.starcon.catalog;

import static org.assertj.core.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 3.1.2.3 内部编码展开规则的纯函数单测。不碰数据库——"展开成什么样、是否越界" 必须在规则保存时就能判定，不能等批量生成 500 个 SKU 之后才发现。 */
class SkuCodePatternTest {

  @Test
  void rendersPrefixPaddedSequenceAndSuffix() {
    assertThat(SkuCodePattern.render("SKU-", 1, 6, "")).isEqualTo("SKU-000001");
    assertThat(SkuCodePattern.render("AB", 42, 4, "-X")).isEqualTo("AB0042-X");
    // 宽度 1 = 不补零，序号原样。
    assertThat(SkuCodePattern.render("", 9, 1, "")).isEqualTo("9");
    // 序号超过声明宽度时**变长而不是截断**：截断会让两个序号映射到同一个编码。
    assertThat(SkuCodePattern.render("S-", 1234567, 4, "")).isEqualTo("S-1234567");
  }

  @Test
  void sequenceRenderIsMonotonicSoOneRuleNeverRepeatsACode() {
    String previous = null;
    for (long i = 1; i <= 500; i++) {
      String code = SkuCodePattern.render("SKU-", i, 6, "");
      if (previous != null) assertThat(code).isNotEqualTo(previous);
      previous = code;
    }
    assertThat(previous).isEqualTo("SKU-000500");
  }

  @Test
  void rejectsRuleWhoseExpandedCodeCannotFitTheColumn() {
    assertThat(SkuCodePattern.lengthProblem("SKU-", 6, "")).isNull();
    // 32 + 12 + 32 = 76 > 64，必须在保存规则时就拒绝。
    String problem = SkuCodePattern.lengthProblem("A".repeat(32), 12, "B".repeat(32));
    assertThat(problem).isNotNull().contains("76").contains("64");
    // 刚好 64 放得下。
    assertThat(SkuCodePattern.lengthProblem("A".repeat(32), 12, "B".repeat(20))).isNull();
  }

  @Test
  void rejectsAnExpandedCodeThatOverflowedAtGenerationTime() {
    assertThat(SkuCodePattern.codeProblem("SKU-000001")).isNull();
    assertThat(SkuCodePattern.codeProblem("")).isNotNull();
    assertThat(SkuCodePattern.codeProblem(null)).isNotNull();
    String over = "X".repeat(65);
    assertThat(SkuCodePattern.codeProblem(over)).isNotNull().contains("65");
    // 恰好 64 位仍然放得下。
    assertThat(SkuCodePattern.codeProblem("X".repeat(64))).isNull();
  }

  @Test
  void normalizesPrefixAndSuffixToTheSameCharsetAsManualCodes() {
    assertThat(SkuCodePattern.normalizePart("  sku- ", "编码前缀")).isEqualTo("SKU-");
    assertThat(SkuCodePattern.normalizePart("", "编码前缀")).isEmpty();
    assertThat(SkuCodePattern.normalizePart("A_B-9", "编码后缀")).isEqualTo("A_B-9");
    // 空格、中文、标点都不在 [A-Z0-9_-] 里，必须在保存规则时报错。
    for (String bad : List.of("SKU 1", "SKU-1/2", "商品编码", "SKU#1", "SKU,1")) {
      assertThatThrownBy(() -> SkuCodePattern.normalizePart(bad, "编码前缀"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("编码前缀");
    }
  }
}
