package com.starcon.catalog;

import static com.starcon.catalog.CatalogModels.CategoryAttributeCondition;
import static com.starcon.catalog.CatalogModels.CategoryAttributeConditionValue;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 3.3.1.2 类目属性要求的纯规则：要求形态词表、取值范围按数据类型的合法性、 条件必填的求值。
 *
 * <p>与 {@link SpecAxisRules} / {@link CategoryTreeRules} / {@link AttributeDictionaryRules} 同一条纪律：
 * **能在保存前判定的，就不要留到录入表单渲染或商品保存时才炸**。 条件必填引用的属性如果不可枚举，那条规则在录入时永远求不出真值—— 界面上表现为「字段偶尔不校验」，比一个明确的 400
 * 难排查得多。
 *
 * <p>本类不碰数据库，可以在离线单测里穷举，与前端 {@code category-requirements-model.ts} 同口径。
 *
 * <p>类名不叫 {@code CategoryRequirements}：那会被静态导入的 DTO record 撞名 （与 {@link SpecAxisRules} 同一个理由）。
 */
public final class CategoryAttributeRequirementRules {
  private CategoryAttributeRequirementRules() {}

  /** 要求形态词表：必填 / 可选 / 条件必填。 */
  public static final Set<String> REQUIREMENTS =
      Set.of("REQUIRED", "OPTIONAL", "CONDITIONAL_REQUIRED");

  /** 条件算子词表。与 V15 的 {@code category_attribute_rule_operator_vocab} 逐字一致。 */
  public static final Set<String> OPERATORS =
      Set.of("IN", "NOT_IN", "EQ", "NEQ", "GT", "GTE", "LT", "LTE");

  /**
   * 算子按前置属性数据类型的可用集合。
   *
   * <p>为什么不能只有一个全局词表：{@code GT} 对 DECIMAL 有意义（5 大于 3）， 对 OPTION / TEXT 就没有——两个选项值之间没有大小。允许写出这种条件，
   * 求值时只会得到「无法比较」，而字段就会一直停在「非必填」，条件形同虚设。
   *
   * <p>{@code IN}/{@code NOT_IN} 对 DECIMAL 也开放：数值属性写「尺寸在 {1.5, 2.5} 时必填」 是可求值的有限集合，与数据类型不冲突。
   */
  private static final Set<String> ORDERING = Set.of("GT", "GTE", "LT", "LTE");

  /** 排序类算子（需要「可比较的数值」）。校验条件取值能否求值时要用，故公开。 */
  public static boolean isOrderingOperator(String value) {
    return ORDERING.contains(normalizeOperator(value));
  }

  public static String normalizeRequirement(String value) {
    return value == null ? "" : value.trim().toUpperCase(java.util.Locale.ROOT);
  }

  public static String normalizeOperator(String value) {
    return value == null ? "" : value.trim().toUpperCase(java.util.Locale.ROOT);
  }

  /** 要求形态校验。返回 null 表示可用；否则给出可执行的原因。 */
  public static String requirementProblem(String value) {
    String requirement = normalizeRequirement(value);
    if (requirement.isEmpty()) return "要求形态不能为空";
    if (!REQUIREMENTS.contains(requirement))
      return "要求形态只能是 REQUIRED（必填）、OPTIONAL（可选）或 CONDITIONAL_REQUIRED（条件必填）";
    return null;
  }

  public static String operatorProblem(String value) {
    String operator = normalizeOperator(value);
    if (operator.isEmpty()) return "条件算子不能为空";
    if (!OPERATORS.contains(operator))
      return "条件算子只能是 IN / NOT_IN / EQ / NEQ / GT / GTE / LT / LTE";
    return null;
  }

  /**
   * 条件算子与前置属性数据类型的相容性。
   *
   * <p>与 {@link #ORDERING} 同源：排序类算子只对**有大小关系**的数据类型开放。 BOOLEAN 连相等比较都不开放——{@code true/false}
   * 的布尔值是选项而非数值， 写 {@code GT} 表达不出任何可求值的东西（{@code IN}/{@code EQ} 才是它的正确写法）。
   */
  public static String operatorDataTypeProblem(String operator, String dataType) {
    String op = normalizeOperator(operator);
    if (!OPERATORS.contains(op)) return operatorProblem(operator);
    String type = dataType == null ? "" : dataType.trim().toUpperCase(java.util.Locale.ROOT);
    if (ORDERING.contains(op) && Set.of("TEXT", "OPTION", "BOOLEAN").contains(type))
      return "算子 " + op + " 只能用于数值或日期属性：属性「" + type + "」的取值没有大小关系";
    return null;
  }

  /**
   * 取值范围约束按数据类型的合法性。
   *
   * <p>与 V11 的 {@code rangeProblem} 同一思路，但本模块多管两类 V11 没有的约束： 文本长度上限（{@code maxLength}）与自由填写（{@code
   * allowFreeText}）。
   *
   * <p>给错数据类型配了形态的规则不是「无害的多余字段」，而是**永远匹配不上的死约束**： 给 OPTION 属性配
   * minValue，界面上看不出问题，录入时也不会有人能填出一个满足它的值。
   *
   * @param dataType 属性定义的数据类型
   * @param minValue 数值下限
   * @param maxValue 数值上限
   * @param maxLength 文本长度上限
   * @param allowFreeText 是否允许自由填写
   * @param allowedOptions 已配置的允许选项数（用于「配了数值区间又配了选项」这类互斥判定）
   */
  public static String rangeProblem(
      String dataType,
      BigDecimal minValue,
      BigDecimal maxValue,
      Integer maxLength,
      boolean allowFreeText,
      int allowedOptionCount) {
    String type = dataType == null ? "" : dataType.trim().toUpperCase(java.util.Locale.ROOT);
    boolean numeric = "DECIMAL".equals(type);
    boolean option = "OPTION".equals(type);
    boolean text = "TEXT".equals(type);

    if (minValue != null && maxValue != null && minValue.compareTo(maxValue) > 0)
      return "取值下限不能大于上限";
    if (minValue != null && !numeric) return "取值上下限只对数值属性（DECIMAL）有意义";
    if (maxValue != null && !numeric) return "取值上下限只对数值属性（DECIMAL）有意义";
    if (allowedOptionCount > 0 && !option) return "允许选项只对枚举属性（OPTION）有意义";
    if (maxLength != null && !text) return "文本长度上限只对文本属性（TEXT）有意义";
    if (maxLength != null && maxLength <= 0) return "文本长度上限必须大于 0";
    // 自由填写与「白名单」是矛盾的：白名单的意义就是「只允许这几个」，一旦允许自由填写，
    // 白名单只剩提示作用。这里不给可读拒绝的话，界面上两个开关都能打开，
    // 而录入时到底哪个说了算只有校验端点知道。
    if (allowFreeText && !option) return "「允许自由填写」只对枚举属性（OPTION）有意义";
    if (allowFreeText && allowedOptionCount > 0)
      return "「允许自由填写」与「限定允许选项」互相矛盾：允许自由填写时，所有选项都可选，不需要再限定子集";
    return null;
  }

  /**
   * 条件取值集合的校验：非空 + 不重复。
   *
   * <p>空集合必须挡住：{@code condition_operator='IN'} 但取值集合为空时， {@code 实际值 ∈ ∅} 恒为假，这条条件必填**永远不成立**，
   * 等价于把字段悄悄降级成可选——配置的人以为自己加了要求，实际什么都没加。
   *
   * @param conditionRequirement 规则的 requirement
   * @param conditionValues 已填写的条件取值
   */
  public static String conditionValuesProblem(
      String conditionRequirement, List<String> conditionValues) {
    boolean conditional = "CONDITIONAL_REQUIRED".equals(normalizeRequirement(conditionRequirement));
    List<String> values = conditionValues == null ? List.of() : conditionValues;
    if (!conditional) {
      if (!values.isEmpty()) return "只有条件必填（CONDITIONAL_REQUIRED）的规则才能配置条件取值";
      return null;
    }
    if (values.isEmpty()) return "条件必填必须给出至少一个条件取值：取值集合为空时条件恒不成立，这条要求等于没配";
    for (String value : values) if (value == null || value.isBlank()) return "条件取值不能为空";
    if (new LinkedHashSet<>(values).size() != values.size()) return "条件取值不可重复";
    return null;
  }

  /** 单个条件取值的规范化：去首尾空白。取值本身允许中文与符号（它是用户可见的文案）。 */
  public static String normalizeConditionValue(String value) {
    return value == null ? "" : value.trim();
  }

  /** 条件取值长度上限，与 {@code category_attribute_condition_value.condition_value varchar(200)} 一致。 */
  public static final int MAX_CONDITION_VALUE_LENGTH = 200;

  /**
   * 条件求值：给定前置属性的**当前值**，这条条件是否成立。
   *
   * <p>本方法是「条件必填真正参与校验」的落点，被表单 schema 生成与保存前校验共用—— 两处必须走同一个判定，否则会出现「表单标了必填、校验却放行」的分裂。
   *
   * <p>三条口径，都是为了让「条件不成立」和「条件无法判断」可区分：
   *
   * <ul>
   *   <li><b>前置属性没有值</b> → 条件不成立（{@code false}）。不是抛错也不是当成真：
   *       录入时前置字段还没填就是「还不知道」，此时把依赖字段标成必填会逼用户先填前置字段， 而用户在录 T恤时不一定关心保修期。把未知当假，行为可预期。
   *   <li><b>空取值集合</b> → 恒为 {@code false}（配置层已挡住，这里兜底防绕过）。
   *   <li><b>类型不可比</b>（如对 OPTION 用 GT）→ 恒为 {@code false}（同上，配置层是主防线）。
   * </ul>
   *
   * @param operator 比较算子，已规范化
   * @param expectedValues 条件取值
   * @param dependencyType 前置属性的数据类型
   * @param actual 前置属性的当前值（null = 尚未填写）
   */
  public static boolean conditionHolds(
      String operator, List<String> expectedValues, String dependencyType, String actual) {
    List<String> expected = expectedValues == null ? List.of() : expectedValues;
    if (expected.isEmpty()) return false;
    String actualValue = normalizeConditionValue(actual);
    if (actualValue.isEmpty()) return false;
    String op = normalizeOperator(operator);
    String type =
        dependencyType == null ? "" : dependencyType.trim().toUpperCase(java.util.Locale.ROOT);

    // 条件取值同样去首尾空白：写入侧已经规范化过，但求值侧不该假设调用方都走了那条路——
    // 两侧口径不一致时，一次手误的空格就能让条件永远不成立，而界面上看不出任何异常。
    List<String> expectedTrimmed =
        expected.stream().map(CategoryAttributeRequirementRules::normalizeConditionValue).toList();

    if ("IN".equals(op)) return expectedTrimmed.contains(actualValue);
    if ("NOT_IN".equals(op)) return !expectedTrimmed.contains(actualValue);

    // 等价类算子（EQ/NEQ）对所有类型开放：值本身就是字符串，比较字符串总是有意义的。
    if ("EQ".equals(op)) return expectedTrimmed.contains(actualValue);
    if ("NEQ".equals(op)) return !expectedTrimmed.contains(actualValue);

    // 排序类算子只对可解析为 BigDecimal 的类型求值。DECIMAL 直接比，
    // DATE 与 TEXT 先试数值解析（运营会用 2024 这种年份写法），
    // 解析不出来就当不可比——条件不成立，而不是崩掉整个校验。
    if (!ORDERING.contains(op)) return false;
    if ("BOOLEAN".equals(type) || "OPTION".equals(type)) return false;
    BigDecimal left = parseNumeric(actualValue);
    BigDecimal right = parseNumeric(expectedTrimmed.get(0));
    if (left == null || right == null) return false;
    return switch (op) {
      case "GT" -> left.compareTo(right) > 0;
      case "GTE" -> left.compareTo(right) >= 0;
      case "LT" -> left.compareTo(right) < 0;
      case "LTE" -> left.compareTo(right) <= 0;
      default -> false;
    };
  }

  /**
   * 宽松的数值解析：能解析成 BigDecimal 就返回，解析不出来返回 {@code null}。
   *
   * <p>用 {@link BigDecimal} 而不是 {@code double}：条件里比的是数值属性与年份， 浮点误差会让 {@code 0.1+0.2 > 0.3}
   * 这类判定在两台机器上给出不同结果 （AGENTS.md 明令禁止浮点记账，这里是同一条纪律在条件求值上的延伸）。
   */
  private static BigDecimal parseNumeric(String value) {
    if (value == null || value.isBlank()) return null;
    try {
      return new BigDecimal(value.trim());
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /**
   * 把一条条件组装成可求值的形状。
   *
   * <p>与 {@link CategoryAttributeCondition} 分开：本方法只关心**求值需要的四个事实** （算子 / 取值 / 前置属性类型 /
   * 前置属性名），不关心展示用的属性 id。
   */
  public static ResolvedCondition resolve(
      CategoryAttributeCondition condition,
      List<CategoryAttributeConditionValue> values,
      String dependencyType) {
    if (condition == null) return null;
    return new ResolvedCondition(
        normalizeOperator(condition.operator()),
        values == null
            ? List.of()
            : values.stream().map(CategoryAttributeConditionValue::value).toList(),
        dependencyType,
        condition.attributeName());
  }

  /** 求值就绪的条件：算子 + 取值 + 前置属性类型。 */
  public record ResolvedCondition(
      String operator, List<String> values, String dependencyType, String dependencyName) {

    /** 用当前前置属性值求值。语义见 {@link CategoryAttributeRequirementRules#conditionHolds}。 */
    public boolean holds(String actual) {
      return conditionHolds(operator, values, dependencyType, actual);
    }
  }
}
