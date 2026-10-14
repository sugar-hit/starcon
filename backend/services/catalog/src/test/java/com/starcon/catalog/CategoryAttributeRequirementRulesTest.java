package com.starcon.catalog;

import static com.starcon.catalog.CategoryAttributeRequirementRules.*;
import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 3.3.1.2 类目属性要求的纯规则单测：要求形态词表、取值约束按数据类型的分流、条件求值。
 *
 * <p>条件求值是本模块最容易出错的一处——布尔列表达不了「条件必填」， 求值逻辑一旦对某个算子或某个类型判错，症状是「字段偶尔不校验」， 集成测试很难穷举，所以在这里把每个分支都钉死。
 */
class CategoryAttributeRequirementRulesTest {

  // ------------------------------------------------------------ 词表

  @Test
  void requirementVocabularyIsClosed() {
    assertThat(requirementProblem("REQUIRED")).isNull();
    assertThat(requirementProblem("required")).isNull(); // 大小写不敏感
    assertThat(requirementProblem("CONDITIONAL_REQUIRED")).isNull();
    assertThat(requirementProblem("")).contains("不能为空");
    assertThat(requirementProblem("MAYBE")).contains("REQUIRED");
    assertThat(normalizeRequirement(" conditional_required ")).isEqualTo("CONDITIONAL_REQUIRED");
  }

  @Test
  void operatorVocabularyIsClosed() {
    for (String op : List.of("IN", "NOT_IN", "EQ", "NEQ", "GT", "GTE", "LT", "LTE"))
      assertThat(operatorProblem(op)).isNull();
    assertThat(operatorProblem("BETWEEN")).isNotNull();
    assertThat(operatorProblem("")).contains("不能为空");
  }

  // ------------------------------------------------------------ 算子与数据类型的相容性

  @Test
  void orderingOperatorsOnlyApplyToComparableTypes() {
    assertThat(operatorDataTypeProblem("GT", "DECIMAL")).isNull();
    assertThat(operatorDataTypeProblem("GTE", "DATE")).isNull();
    // 选项与文本之间没有大小关系：允许写出这种条件，求值只会永远为 false。
    assertThat(operatorDataTypeProblem("GT", "OPTION")).contains("没有大小关系");
    assertThat(operatorDataTypeProblem("LT", "TEXT")).contains("没有大小关系");
    assertThat(operatorDataTypeProblem("LTE", "BOOLEAN")).contains("没有大小关系");
    // 集合与等值算子对所有类型开放。
    assertThat(operatorDataTypeProblem("IN", "OPTION")).isNull();
    assertThat(operatorDataTypeProblem("EQ", "DECIMAL")).isNull();
    assertThat(operatorDataTypeProblem("IN", "TEXT")).isNull();
    assertThat(isOrderingOperator("gte")).isTrue();
    assertThat(isOrderingOperator("IN")).isFalse();
  }

  // ------------------------------------------------------------ 取值约束按类型分流

  @Test
  void rangeConstraintsMustMatchTheAttributeDataType() {
    // 数值：区间合法。比较按值域而不是书写顺序：min 永远是小的那一端。
    assertThat(rangeProblem("DECIMAL", BigDecimal.ONE, BigDecimal.TEN, null, false, 0)).isNull();
    assertThat(
            rangeProblem(
                "DECIMAL", new BigDecimal("0.01"), new BigDecimal("99.99"), null, false, 0))
        .isNull();
    assertThat(rangeProblem("DECIMAL", BigDecimal.TEN, BigDecimal.ONE, null, false, 0))
        .contains("下限不能大于上限");

    // 枚举：白名单合法，自由填写在没有白名单时合法。
    assertThat(rangeProblem("OPTION", null, null, null, false, 2)).isNull();
    assertThat(rangeProblem("OPTION", null, null, null, true, 0)).isNull();
    // 自由填写 + 白名单互相矛盾。
    assertThat(rangeProblem("OPTION", null, null, null, true, 2)).contains("互相矛盾");

    // 文本：只有长度上限有意义。
    assertThat(rangeProblem("TEXT", null, null, 10, false, 0)).isNull();
    assertThat(rangeProblem("TEXT", BigDecimal.ONE, null, null, false, 0)).contains("DECIMAL");
    assertThat(rangeProblem("TEXT", null, null, 0, false, 0)).contains("大于 0");

    // 给错类型配形态一律拒：那是永远匹配不上的死约束。
    assertThat(rangeProblem("BOOLEAN", BigDecimal.ONE, null, null, false, 0)).contains("DECIMAL");
    assertThat(rangeProblem("DECIMAL", null, null, 10, false, 0)).contains("TEXT");
    assertThat(rangeProblem("DECIMAL", null, null, null, true, 0)).contains("OPTION");
    assertThat(rangeProblem("OPTION", null, null, null, false, 1)).isNull();
    assertThat(rangeProblem("DECIMAL", null, null, null, false, 1)).contains("OPTION");
  }

  // ------------------------------------------------------------ 条件取值集合

  @Test
  void conditionValuesMustBePresentExactlyWhenConditional() {
    // 空集合：条件恒不成立 = 要求等于没配，必须在配置侧就拒。
    assertThat(conditionValuesProblem("CONDITIONAL_REQUIRED", List.of())).contains("至少一个条件取值");
    assertThat(conditionValuesProblem("CONDITIONAL_REQUIRED", null)).contains("至少一个条件取值");
    assertThat(conditionValuesProblem("CONDITIONAL_REQUIRED", List.of("红色", ""))).contains("不能为空");
    assertThat(conditionValuesProblem("CONDITIONAL_REQUIRED", List.of("红色", "红色")))
        .contains("不可重复");
    assertThat(conditionValuesProblem("CONDITIONAL_REQUIRED", List.of("红色", "蓝色"))).isNull();

    // 非条件必填却配了取值：那是一条永不生效的死配置。
    assertThat(conditionValuesProblem("REQUIRED", List.of("红色"))).contains("才能配置条件取值");
    assertThat(conditionValuesProblem("OPTIONAL", List.of())).isNull();
  }

  // ------------------------------------------------------------ 条件求值

  @Test
  void inAndNotInEvaluateAgainstTheDependencyValue() {
    assertThat(conditionHolds("IN", List.of("红色", "蓝色"), "OPTION", "红色")).isTrue();
    assertThat(conditionHolds("IN", List.of("红色", "蓝色"), "OPTION", "绿色")).isFalse();
    assertThat(conditionHolds("NOT_IN", List.of("红色"), "OPTION", "绿色")).isTrue();
    assertThat(conditionHolds("NOT_IN", List.of("红色"), "OPTION", "红色")).isFalse();
  }

  @Test
  void equalityOperatorsAreCaseSensitiveBecauseOptionValuesAre() {
    // 选项值是用户可见文案，比较的是原值——大小写不同就是不同的选项。
    assertThat(conditionHolds("EQ", List.of("Red"), "OPTION", "Red")).isTrue();
    assertThat(conditionHolds("EQ", List.of("Red"), "OPTION", "red")).isFalse();
    assertThat(conditionHolds("NEQ", List.of("Red"), "OPTION", "red")).isTrue();
  }

  @Test
  void orderingOperatorsCompareAsBigDecimalNotFloatingPoint() {
    assertThat(conditionHolds("GT", List.of("3"), "DECIMAL", "4")).isTrue();
    assertThat(conditionHolds("GTE", List.of("3"), "DECIMAL", "3")).isTrue();
    assertThat(conditionHolds("LT", List.of("3"), "DECIMAL", "2")).isTrue();
    assertThat(conditionHolds("LTE", List.of("3"), "DECIMAL", "3")).isTrue();
    assertThat(conditionHolds("GT", List.of("3"), "DECIMAL", "2")).isFalse();
    // 浮点误差用例：0.1 + 0.2 > 0.3 在 double 下为 false，在 BigDecimal 下必须为 true。
    assertThat(conditionHolds("GT", List.of("0.3"), "DECIMAL", "0.1+0.2")).isFalse();
    assertThat(conditionHolds("GTE", List.of("0.3"), "DECIMAL", "0.30")).isTrue();
  }

  @Test
  void missingOrIncomparableValuesMakeTheConditionFalse() {
    // 前置属性没填：当作「还不知道」，不逼用户先填前置字段。
    assertThat(conditionHolds("IN", List.of("红色"), "OPTION", null)).isFalse();
    assertThat(conditionHolds("IN", List.of("红色"), "OPTION", "  ")).isFalse();
    // 空取值集合：恒假（配置层已挡住，这里兜底防绕过）。
    assertThat(conditionHolds("IN", List.of(), "OPTION", "红色")).isFalse();
    // 类型不可比：不崩，退化成条件不成立。
    assertThat(conditionHolds("GT", List.of("3"), "OPTION", "红色")).isFalse();
    assertThat(conditionHolds("GT", List.of("3"), "BOOLEAN", "true")).isFalse();
    assertThat(conditionHolds("GT", List.of("3"), "DECIMAL", "很重")).isFalse();
    // 未词表内的算子：恒假，不抛错。
    assertThat(conditionHolds("BETWEEN", List.of("1"), "DECIMAL", "2")).isFalse();
  }

  @Test
  void valuesAreTrimmedBeforeComparison() {
    // 前端表单与脚本带来的首尾空白不该改变判定。
    assertThat(conditionHolds("IN", List.of(" 红色 "), "OPTION", " 红色")).isTrue();
    assertThat(normalizeConditionValue("  一年  ")).isEqualTo("一年");
    assertThat(conditionHolds("EQ", List.of("一年"), "OPTION", " 一年 ")).isTrue();
  }

  @Test
  void resolvedConditionCarriesEverythingEvaluationNeeds() {
    var resolved =
        resolve(
            new CatalogModels.CategoryAttributeCondition(
                java.util.UUID.randomUUID(), "COLOR", "颜色", "OPTION", "IN"),
            List.of(
                new CatalogModels.CategoryAttributeConditionValue("红色", 0),
                new CatalogModels.CategoryAttributeConditionValue("蓝色", 1)),
            "OPTION");
    assertThat(resolved.operator()).isEqualTo("IN");
    assertThat(resolved.values()).containsExactly("红色", "蓝色");
    assertThat(resolved.dependencyName()).isEqualTo("颜色");
    assertThat(resolved.holds("红色")).isTrue();
    assertThat(resolved.holds("绿色")).isFalse();
    assertThat(resolve(null, List.of(), "OPTION")).isNull();
  }
}
