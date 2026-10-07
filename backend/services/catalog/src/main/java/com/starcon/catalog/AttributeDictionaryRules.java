package com.starcon.catalog;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 3.2.1.1 属性定义的词表与校验规则。
 *
 * <p>单独成类是为了能在不起容器、不连库的情况下把边界条件钉死（见
 * {@code AttributeDictionaryRulesTest}）：编码规范化、选项集合的一致性、以及
 * 「类目规则的数据类型前提」这三类判断一旦写错，症状都是很久之后才在界面上冒出来的
 * 「配置了但不生效」。
 *
 * <p>与前端 {@code attribute-dictionary-model.ts} 同口径：两边都从各自的位置调用同一组
 * 词表常量，前端那份是为了在提交前就提示，不是第二套规则。
 */
final class AttributeDictionaryRules {
  private AttributeDictionaryRules() {}

  /** 属性数据类型。与 V1 的 {@code attribute_definition.data_type} CHECK 完全一致。 */
  static final Set<String> DATA_TYPES =
      Set.of("TEXT", "DECIMAL", "BOOLEAN", "DATE", "OPTION");

  /** 取值模式。与 V1 的 {@code value_mode} CHECK 完全一致。 */
  static final Set<String> VALUE_MODES = Set.of("COMMON_LOCKED", "COMMON_DEFAULT", "SKU_ONLY");

  /**
   * 属性用途：描述 / 规格 / 物流 / 合规。
   *
   * <p>V11 加的 {@code attribute_definition_variant_usage} 约束要求
   * {@code variant_axis=true} 必须落在 SPECIFICATION 上，{@link #usageProblem} 在应用层提前拦一道。
   */
  static final Set<String> USAGES = Set.of("DESCRIPTION", "SPECIFICATION", "LOGISTICS", "COMPLIANCE");

  /**
   * 编码规范化：ASCII 大写字母数字与下划线短横线，1~64 位。
   *
   * <p>与 {@link CatalogService#code} 同一个词表（V9 的规格轴也走它），保证字典编码、
   * 规格轴编码、SPU 级属性编码三者可对齐。
   */
  static String normalizeCode(String value) {
    return value == null ? "" : value.trim().toUpperCase(java.util.Locale.ROOT);
  }

  /** 编码问题；null 表示通过。 */
  static String codeProblem(String value) {
    String normalized = normalizeCode(value);
    if (normalized.isEmpty()) return "属性编码不能为空";
    if (!normalized.matches("[A-Z0-9][A-Z0-9_-]{0,63}"))
      return "属性编码请使用英文字母、数字、下划线或短横线，长度 1~64 位";
    return null;
  }

  /** 词表问题；null 表示通过。value 为空或不在词表内都算问题。 */
  static String inVocabularyProblem(String value, Set<String> vocabulary, String label) {
    if (value == null || !vocabulary.contains(value))
      return "不支持的" + label + "：" + (value == null ? "空" : value);
    return null;
  }

  /**
   * 选项集合是否与数据类型自洽。
   *
   * <p>两条方向都要查：只有枚举属性能有选项（否则是一堆永远匹配不上的死值），
   * 反过来枚举属性必须至少有一个选项（否则录入表单渲染不出可选值）。
   *
   * @return null 表示通过，否则是可读原因
   */
  static String optionsProblem(String dataType, List<String> options) {
    boolean optionType = "OPTION".equals(dataType);
    if (optionType && options.isEmpty()) return "枚举属性请至少提供一个选项";
    if (!optionType && !options.isEmpty()) return "只有枚举属性（OPTION）可以配置选项集合";
    if (new LinkedHashSet<>(options).size() != options.size()) return "选项不可重复";
    for (String option : options) {
      String trimmed = option == null ? "" : option.trim();
      if (trimmed.isEmpty()) return "选项值不能为空";
      if (trimmed.length() > 100) return "选项值最多 100 个字符";
    }
    return null;
  }

  /**
   * 规格轴的蕴含式。
   *
   * <p>V1 的 CHECK {@code NOT variant_axis OR (OPTION + SKU_ONLY + 非多值 + 必填)}
   * 在这里是应用层的同口径表达：DB 会拦，但界面需要一句能读懂的原因。
   */
  static String variantAxisProblem(
      boolean variantAxis,
      String dataType,
      String valueMode,
      boolean multiple,
      boolean required,
      String usage) {
    if (!variantAxis) {
      return null;
    }
    if (!"OPTION".equals(dataType)
        || !"SKU_ONLY".equals(valueMode)
        || multiple
        || !required)
      return "规格轴必须登记为「按 SKU 填写的必填单选枚举属性」";
    if (!"SPECIFICATION".equals(usage))
      return "规格轴属性的用途必须是「规格」：3.1.3.1 的规格轴输入就是用途=规格的属性定义";
    return null;
  }

  /** 类目规则允许的可选范围形态；null 表示通过。 */
  static String rangeProblem(String dataType, BigDecimal min, BigDecimal max) {
    boolean numeric = "DECIMAL".equals(dataType);
    if (min == null && max == null) return null;
    if (!numeric)
      return "取值范围只对数值属性（DECIMAL）有意义；枚举属性请改用可选值范围";
    if (min != null && max != null && min.compareTo(max) > 0)
      return "取值范围的下限不能大于上限";
    return null;
  }

  /**
   * 属性名的常用后缀提示位——不做校验，只给界面一个默认值建议。
   *
   * <p>刻意不自动改写名称：属性名是运营语言，替用户猜字比留空更糟。
   */
  static String suggestGroupName(String dataType) {
    return switch (dataType) {
      case "SPECIFICATION" -> "规格参数";
      case "LOGISTICS" -> "物流参数";
      case "COMPLIANCE" -> "合规信息";
      default -> "基本参数";
    };
  }
}