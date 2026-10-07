package com.starcon.catalog;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 3.2.1.1 属性定义的纯规则：编码词表、选项集合自洽性、规格轴蕴含式、类目范围形态。
 *
 * <p>与前端 {@code attribute-dictionary-model.ts} 同口径。这一层单独测，是因为它的每个失败分支
 * 对应的症状都是「配置保存成功了但录入时就是不生效」——那种问题在界面上完全看不出根因，
 * 必须在规则层就钉死。
 */
class AttributeDictionaryRulesTest {

  @Test
  void codeIsNormalizedToUpperCase() {
    assertThat(AttributeDictionaryRules.normalizeCode(" color ")).isEqualTo("COLOR");
    assertThat(AttributeDictionaryRules.normalizeCode("Net_Weight")).isEqualTo("NET_WEIGHT");
    assertThat(AttributeDictionaryRules.normalizeCode(null)).isEmpty();
  }

  @Test
  void asciiCodeVocabularyIsAccepted() {
    assertThat(AttributeDictionaryRules.codeProblem("COLOR")).isNull();
    assertThat(AttributeDictionaryRules.codeProblem("SIZE_2")).isNull();
    assertThat(AttributeDictionaryRules.codeProblem("A")).isNull();
    assertThat(AttributeDictionaryRules.codeProblem("A".repeat(64))).isNull();
  }

  @Test
  void emptyOrInvalidCodeIsRejectedWithReason() {
    assertThat(AttributeDictionaryRules.codeProblem("")).contains("不能为空");
    assertThat(AttributeDictionaryRules.codeProblem("   ")).contains("不能为空");
    // 中文码是错的：编码是机器标识，只有选项值才允许中文。
    assertThat(AttributeDictionaryRules.codeProblem("颜色")).contains("英文字母");
    assertThat(AttributeDictionaryRules.codeProblem("A".repeat(65))).contains("长度");
    assertThat(AttributeDictionaryRules.codeProblem("-COLOR")).contains("英文字母");
  }

  @Test
  void vocabularyRejectsValuesOutsideTheDatabaseChecks() {
    assertThat(
            AttributeDictionaryRules.inVocabularyProblem(
                "TEXT", AttributeDictionaryRules.DATA_TYPES, "属性数据类型"))
        .isNull();
    assertThat(
            AttributeDictionaryRules.inVocabularyProblem(
                "BIGNUM", AttributeDictionaryRules.DATA_TYPES, "属性数据类型"))
        .contains("不支持的属性数据类型");
    assertThat(
            AttributeDictionaryRules.inVocabularyProblem(
                "WEIRD", AttributeDictionaryRules.VALUE_MODES, "取值模式"))
        .contains("不支持的取值模式");
    assertThat(
            AttributeDictionaryRules.inVocabularyProblem(
                "TRANSPORT", AttributeDictionaryRules.USAGES, "属性用途"))
        .contains("不支持的属性用途");
    assertThat(
            AttributeDictionaryRules.inVocabularyProblem(null, AttributeDictionaryRules.USAGES, "属性用途"))
        .contains("不支持的属性用途");
  }

  @Test
  void optionAttributesNeedAtLeastOneOption() {
    assertThat(AttributeDictionaryRules.optionsProblem("OPTION", List.of("红色"))).isNull();
    assertThat(AttributeDictionaryRules.optionsProblem("OPTION", List.of())).contains("至少提供一个选项");
  }

  @Test
  void nonOptionAttributesMustNotCarryOptions() {
    // 非枚举属性挂着一堆选项，就是一堆永远匹配不上的死值：必须拒。
    assertThat(AttributeDictionaryRules.optionsProblem("TEXT", List.of("棉"))).contains("只有枚举属性");
    assertThat(AttributeDictionaryRules.optionsProblem("DECIMAL", List.of("1"))).contains("只有枚举属性");
    assertThat(AttributeDictionaryRules.optionsProblem("TEXT", List.of())).isNull();
  }

  @Test
  void duplicateOrBlankOptionsAreRejected() {
    assertThat(AttributeDictionaryRules.optionsProblem("OPTION", List.of("红色", "红色")))
        .contains("不可重复");
    assertThat(AttributeDictionaryRules.optionsProblem("OPTION", List.of("红色", " ")))
        .contains("不能为空");
    assertThat(AttributeDictionaryRules.optionsProblem("OPTION", List.of("A".repeat(101))))
        .contains("最多 100 个字符");
  }

  @Test
  void variantAxisMustBeRequiredSingleValueOptionAttribute() {
    // 蓝图与 V1 的 CHECK 同口径：规格轴 = 按 SKU 填写的必填单选枚举属性。
    assertThat(AttributeDictionaryRules.variantAxisProblem(true, "OPTION", "SKU_ONLY", false, true, "SPECIFICATION"))
        .isNull();
    assertThat(AttributeDictionaryRules.variantAxisProblem(true, "TEXT", "SKU_ONLY", false, true, "SPECIFICATION"))
        .contains("必填单选枚举属性");
    assertThat(AttributeDictionaryRules.variantAxisProblem(true, "OPTION", "COMMON_DEFAULT", false, true, "SPECIFICATION"))
        .contains("必填单选枚举属性");
    assertThat(AttributeDictionaryRules.variantAxisProblem(true, "OPTION", "SKU_ONLY", true, true, "SPECIFICATION"))
        .contains("必填单选枚举属性");
    assertThat(AttributeDictionaryRules.variantAxisProblem(true, "OPTION", "SKU_ONLY", false, false, "SPECIFICATION"))
        .contains("必填单选枚举属性");
  }

  @Test
  void variantAxisMustBeRegisteredAsSpecificationUsage() {
    // V11 的 attribute_definition_variant_usage 在应用层的同口径表达：
    // 3.1.3.1 的「输入：属性定义（用途=规格）」要在字典侧真的成立。
    assertThat(AttributeDictionaryRules.variantAxisProblem(true, "OPTION", "SKU_ONLY", false, true, "DESCRIPTION"))
        .contains("用途必须是「规格」");
    // 非规格轴属性不做用途限制。
    assertThat(AttributeDictionaryRules.variantAxisProblem(false, "OPTION", "SKU_ONLY", false, true, "DESCRIPTION"))
        .isNull();
  }

  @Test
  void numericRangeIsOnlyMeaningfulForDecimalAttributes() {
    assertThat(AttributeDictionaryRules.rangeProblem("DECIMAL", null, null)).isNull();
    assertThat(AttributeDictionaryRules.rangeProblem("DECIMAL", new BigDecimal("0.5"), new BigDecimal("9.5")))
        .isNull();
    assertThat(AttributeDictionaryRules.rangeProblem("TEXT", new BigDecimal("1"), null))
        .contains("只对数值属性");
    assertThat(AttributeDictionaryRules.rangeProblem("OPTION", null, new BigDecimal("1")))
        .contains("只对数值属性");
  }

  @Test
  void invertedNumericRangeIsRejected() {
    assertThat(AttributeDictionaryRules.rangeProblem("DECIMAL", new BigDecimal("9"), new BigDecimal("1")))
        .contains("下限不能大于上限");
    // 相等是一个合法区间（只能是这一个值），不能误杀。
    assertThat(AttributeDictionaryRules.rangeProblem("DECIMAL", new BigDecimal("5"), new BigDecimal("5")))
        .isNull();
  }

  @Test
  void groupNameSuggestionFollowsUsage() {
    assertThat(AttributeDictionaryRules.suggestGroupName("SPECIFICATION")).isEqualTo("规格参数");
    assertThat(AttributeDictionaryRules.suggestGroupName("LOGISTICS")).isEqualTo("物流参数");
    assertThat(AttributeDictionaryRules.suggestGroupName("COMPLIANCE")).isEqualTo("合规信息");
    assertThat(AttributeDictionaryRules.suggestGroupName("DESCRIPTION")).isEqualTo("基本参数");
  }
}