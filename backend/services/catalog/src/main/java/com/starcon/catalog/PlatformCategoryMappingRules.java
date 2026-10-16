package com.starcon.catalog;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 4.2.3.1 平台类目映射的纯规则：编码规范化、词表校验、属性规则的取值约束。
 *
 * <p>与 {@link CategoryTreeRules} / {@link AttributeDictionaryRules} 同一条纪律：
 * **能在保存前判定的，就不要留到刊登提交时才炸**。这一条对本模块尤其要紧——
 * 映射与属性规则是刊登的输入，一个写错的平台码或站点码不会当场报错，
 * 而是要等到平台审核驳回才暴露，那时已经传了货、改了价、占了库存位。
 *
 * <p>本类不碰数据库，可在离线单测里穷举，与前端 {@code platform-mapping-model.ts} 同口径。
 */
public final class PlatformCategoryMappingRules {
  private PlatformCategoryMappingRules() {}

  /** 平台码长度上限，与 {@code platform_category_mapping.platform_code varchar(32)} 一致。 */
  public static final int MAX_PLATFORM_LENGTH = 32;

  /** 站点码长度上限，与 {@code platform_category_mapping.site_code varchar(32)} 一致。 */
  public static final int MAX_SITE_LENGTH = 32;

  /** 平台属性码长度上限，与 {@code platform_category_attribute_mapping.platform_attribute_code varchar(64)} 一致。 */
  public static final int MAX_PLATFORM_ATTRIBUTE_CODE_LENGTH = 64;

  /** 平台属性显示名长度上限，与 {@code platform_attribute_name varchar(200)} 一致。 */
  public static final int MAX_PLATFORM_ATTRIBUTE_NAME_LENGTH = 200;

  /** 单条属性规则的候选值上限：平台枚举实际到几十条，几百条基本是误粘贴。 */
  public static final int MAX_ALLOWED_VALUES = 200;

  /** 单个候选值长度上限，与 DB 侧「候选值长度 100」的校验口径一致。 */
  public static final int MAX_ALLOWED_VALUE_LENGTH = 100;

  /** 要求强度词表。 */
  public static final Set<String> REQUIREMENTS = Set.of("REQUIRED", "RECOMMENDED", "OPTIONAL");

  /** 平台侧数据类型词表。 */
  public static final Set<String> PLATFORM_DATA_TYPES = Set.of("TEXT", "ENUM", "NUMBER", "BOOLEAN", "DATE");

  /** 值变换词表。DIRECT=原样，其余由 4.2.1.1 执行；这里只存算子名，不存实现。 */
  public static final Set<String> TRANSFORMS =
      Set.of("DIRECT", "TRIM", "UPPER", "LOWER", "NUMBER_PARSE", "BOOLEAN_PARSE", "DATE_PARSE");

  /**
   * 平台码规范化：去空白 + 大写。
   *
   * <p>大写是刻意的：平台码要与 channel 服务的 {@code platform.code} 以及 V14 里
   * {@code product_category_tree.platform_code} 对齐，那两处都是大写。
   * 「amazon」与「AMAZON」各存一份的话，覆盖率会按平台算成两个平台，而它们其实是同一个。
   */
  public static String normalizePlatform(String value) {
    return normalizeCode(value);
  }

  /**
   * 站点码规范化：去空白 + 大写。与平台码同一理由（Amazon 的 {@code us} / {@code US} 是同一个站点）。
   */
  public static String normalizeSite(String value) {
    return normalizeCode(value);
  }

  private static String normalizeCode(String value) {
    return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
  }

  /**
   * 平台码校验。
   *
   * <p>字符集与 DB 的 CHECK 完全一致（{@code ^[A-Z0-9][A-Z0-9_-]{0,31}$}），
   * 这里先判是为了能在 400 里说清「哪个字符不合法」，而不是把 DB 的约束名抛给界面。
   */
  public static String platformProblem(String value) {
    String code = normalizePlatform(value);
    if (code.isEmpty()) return "平台编码不能为空（如 AMAZON / SHOPIFY / TIKTOK）";
    if (code.length() > MAX_PLATFORM_LENGTH) return "平台编码最多 " + MAX_PLATFORM_LENGTH + " 个字符";
    if (!code.matches("[A-Z0-9][A-Z0-9_-]{0,31}"))
      return "平台编码请使用英文字母、数字、下划线或短横线，且以字母或数字开头（如 AMAZON）";
    return null;
  }

  /**
   * 站点码校验。
   *
   * <p>与平台码分开给消息：两者混成一条「编码不合法」的话，运营不知道自己填的是哪一栏错了。
   */
  public static String siteProblem(String value) {
    String code = normalizeSite(value);
    if (code.isEmpty()) return "站点编码不能为空（如 US / DE / UK）";
    if (code.length() > MAX_SITE_LENGTH) return "站点编码最多 " + MAX_SITE_LENGTH + " 个字符";
    if (!code.matches("[A-Z0-9][A-Z0-9_-]{0,31}"))
      return "站点编码请使用英文字母、数字、下划线或短横线，且以字母或数字开头（如 US）";
    return null;
  }

  public static String requirementProblem(String value) {
    String key = value == null ? "" : value.trim();
    if (key.isEmpty()) return "要求强度不能为空";
    if (!REQUIREMENTS.contains(key))
      return "要求强度只能是 REQUIRED（平台必填）、RECOMMENDED（平台推荐）或 OPTIONAL（可选）";
    return null;
  }

  public static String platformDataTypeProblem(String value) {
    String key = value == null ? "" : value.trim();
    if (key.isEmpty()) return "平台数据类型不能为空";
    if (!PLATFORM_DATA_TYPES.contains(key))
      return "平台数据类型只能是 TEXT / ENUM / NUMBER / BOOLEAN / DATE";
    return null;
  }

  public static String transformProblem(String value) {
    if (value == null || value.isBlank()) return null; // 未给即 DIRECT
    String key = normalizeTransform(value);
    if (!TRANSFORMS.contains(key)) return "值变换只能是 " + String.join(" / ", TRANSFORMS.stream().sorted().toList());
    return null;
  }

  /**
   * 规范化后的值变换算子名。空值归一到 DIRECT，避免到处判空。
   *
   * <p>归一到大写（与 {@link #normalizePlatformAttributeCode} 刻意不同）：算子名是本服务自己的词表，
   * DB 的 CHECK 也只认大写，留着小写会让服务层判过而 DB 拒收。平台属性码不能这么干，
   * 因为它要原样进平台请求体。
   */
  public static String normalizeTransform(String value) {
    if (value == null || value.isBlank()) return "DIRECT";
    return value.trim().toUpperCase(Locale.ROOT);
  }

  /**
   * 平台属性码规范化。
   *
   * <p>与平台码不同，这里**保留原始大小写**：平台属性码要原样进请求体
   * （Amazon 的 {@code bullet_point_1} 与 {@code BULLET_POINT_1} 是两个字段），
   * 顺手大写会直接让刊登被驳回。字符集允许大小写与点号，与 DB 的 CHECK 一致。
   */
  public static String normalizePlatformAttributeCode(String value) {
    return value == null ? "" : value.trim();
  }

  public static String platformAttributeCodeProblem(String value) {
    String code = normalizePlatformAttributeCode(value);
    if (code.isEmpty()) return "平台属性编码不能为空（如 material / bullet_point_1）";
    if (code.length() > MAX_PLATFORM_ATTRIBUTE_CODE_LENGTH)
      return "平台属性编码最多 " + MAX_PLATFORM_ATTRIBUTE_CODE_LENGTH + " 个字符";
    if (!code.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}"))
      return "平台属性编码请使用英文字母、数字、下划线、点号或短横线，且以字母或数字开头";
    return null;
  }

  public static String normalizePlatformAttributeName(String value) {
    return value == null ? "" : value.trim();
  }

  public static String platformAttributeNameProblem(String value) {
    String name = normalizePlatformAttributeName(value);
    if (name.isEmpty()) return "平台属性显示名不能为空（如「材质」）";
    if (name.length() > MAX_PLATFORM_ATTRIBUTE_NAME_LENGTH)
      return "平台属性显示名最多 " + MAX_PLATFORM_ATTRIBUTE_NAME_LENGTH + " 个字符";
    return null;
  }

  /**
   * 候选值规范化：去首尾空白 + 去重（保持出现顺序）。
   *
   * <p>去重而不是报错：平台后台导出的枚举里重复值很常见，而重复候选会让下拉里出现两项同名，
   * 运营选了其中一项、刊登时平台按值匹配到另一项——症状是「明明选对了却总被驳回」。
   * 空白项则直接丢弃（DB 的 CHECK 会拒空串，规则层先滤掉更早一步）。
   */
  public static List<String> normalizeAllowedValues(List<String> values) {
    if (values == null || values.isEmpty()) return List.of();
    Set<String> seen = new LinkedHashSet<>();
    for (String value : values) {
      String normalized = value == null ? "" : value.trim();
      if (!normalized.isEmpty()) seen.add(normalized);
    }
    return List.copyOf(seen);
  }

  /**
   * 单条属性规则的完整校验。
   *
   * <p>逐条给可读原因而不是抛一个笼统的「规则不合法」：这条规则一次要填七项，
   * 一次只告诉一项的话，运营得反复提交七轮。
   */
  public static String attributeProblem(
      String code,
      String name,
      String requirement,
      String dataType,
      List<String> allowedValues,
      String transform,
      Integer position) {
    String problem = platformAttributeCodeProblem(code);
    if (problem != null) return problem;
    problem = platformAttributeNameProblem(name);
    if (problem != null) return problem;
    problem = requirementProblem(requirement);
    if (problem != null) return problem;
    problem = platformDataTypeProblem(dataType);
    if (problem != null) return problem;
    problem = transformProblem(transform);
    if (problem != null) return problem;
    List<String> values = normalizeAllowedValues(allowedValues);
    if (values.size() > MAX_ALLOWED_VALUES) return "候选值最多 " + MAX_ALLOWED_VALUES + " 条";
    for (String value : values) {
      if (value.length() > MAX_ALLOWED_VALUE_LENGTH)
        return "单个候选值最多 " + MAX_ALLOWED_VALUE_LENGTH + " 个字符：「" + value + "」超长";
    }
    // 与 V145 的 CHECK 同口径，但这里先给原因：枚举没有候选值时，
    // 刊登侧既渲染不出下拉也校验不了值，只能原样发过去等平台驳回。
    if ("ENUM".equals(dataType == null ? "" : dataType.trim()) && values.isEmpty())
      return "平台数据类型是 ENUM 时必须提供候选值，否则刊登时无法渲染下拉、也无法校验取值";
    if (position != null && position < 0) return "排序位不能为负数";
    return null;
  }
}
