package com.starcon.catalog;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 3.3.3.1 计量单位（字典视角）的纯规则：单位组编码与名称、量纲词表、组内换算比例校验， 以及蓝图点名的**换算查询拒绝原因码**。
 *
 * <p>与 {@link BrandRules} / {@link UnitRules} / {@link CategoryTreeRules} 同一条纪律：
 * **能在保存前判定的，就不要留到换算时才炸**。量纲不匹配（件 → 千克）如果等到 07 库存 记一笔账才发现，那笔账已经按错比例记完了。
 *
 * <p>拒绝原因码（{@link #DENY_CODES}）是本模块的对外契约之一：换算查询不只回答「换不换得动」，
 * 还要说清**为什么换不动**——界面据此提示「这两个单位不在同一个单位组」还是「量纲不同」， 下游 06 采购 / 07 库存 / 08 拣货也要按码走不同的处理（换一个同组单位 vs
 * 补一条换算版本）。 只返回一句中文的话，调用方只能去匹配字符串。
 *
 * <p>本类不碰数据库，可以在离线单测里穷举，与前端 {@code unit-group-model.ts} 同口径。
 *
 * <p>类名不叫 {@code UnitGroup}：那是被静态导入的 DTO record（{@link CatalogModels}），
 * 两者在同一批文件里会直接撞名，构造器解析到纯规则类上，编译期才炸。 与 {@link UnitRules} 同一个理由。
 */
public final class UnitGroupRules {
  private UnitGroupRules() {}

  /** 单位组编码长度上限，与 {@code unit_group.code varchar(32)} 一致。 */
  public static final int MAX_CODE_LENGTH = 32;

  /** 单位组名称长度上限，与 {@code unit_group.name varchar(100)} 一致。 */
  public static final int MAX_NAME_LENGTH = 100;

  /** 备注长度上限。备注是给运营看的自由文本，不参与任何判定。 */
  public static final int MAX_NOTE_LENGTH = 500;

  /** 单个单位组最多可容纳的成员数：超过这个数说明这是"所有单位"而不是一个换算组。 */
  public static final int MAX_MEMBERS = 50;

  /** 量纲词表。与 V8 的 {@code unit_of_measure.dimension} CHECK 完全一致，不另造词表。 */
  public static final Map<String, String> DIMENSIONS =
      Map.of(
          "WEIGHT", "重量",
          "LENGTH", "长度",
          "VOLUME", "体积",
          "COUNT", "数量",
          "PACKAGE", "包装");

  /** 换算查询的拒绝原因码。返回体里给机器读的是它，给运营看的是 {@link #denyLabel}。 */
  public static final String DENY_UNIT_UNREGISTERED = "UNIT_UNREGISTERED";

  public static final String DENY_UNIT_DISABLED = "UNIT_DISABLED";
  public static final String DENY_GROUP_DISABLED = "GROUP_DISABLED";
  public static final String DENY_DIMENSION_MISMATCH = "DIMENSION_MISMATCH";
  public static final String DENY_NOT_SAME_GROUP = "NOT_SAME_GROUP";
  public static final String DENY_NO_EFFECTIVE_VERSION = "NO_EFFECTIVE_VERSION";
  public static final String DENY_SELF_CONVERSION = "SELF_CONVERSION";

  /** 原因码 → 中文标签。前端 {@code unit-group-model.ts} 的 {@code DENY_LABELS} 逐字同口径。 */
  public static final Map<String, String> DENY_LABELS =
      Map.of(
          DENY_UNIT_UNREGISTERED, "单位未登记",
          DENY_UNIT_DISABLED, "单位已停用",
          DENY_GROUP_DISABLED, "单位组已停用",
          DENY_DIMENSION_MISMATCH, "量纲不匹配",
          DENY_NOT_SAME_GROUP, "不在同一个单位组",
          DENY_NO_EFFECTIVE_VERSION, "没有生效的换算版本",
          DENY_SELF_CONVERSION, "无需换算");

  /** 全部原因码，用于离线校验（前端也用它做提示分支的穷举检查）。 */
  public static final Set<String> DENY_CODES = DENY_LABELS.keySet();

  /**
   * 单位组编码字符集：中文、拉丁字母、数字、下划线、短横线，1~32 位。
   *
   * <p>与 {@link UnitRules#codeProblem} 同一口径（允许中文）：运营就是这么叫的， 「重量组」「长度组」是中文业务词。套 ASCII 词表会逼着所有人用拼音。
   */
  private static final String CODE_PATTERN = "[\\p{IsHan}A-Za-z0-9][\\p{IsHan}A-Za-z0-9_-]{0,31}";

  public static String normalizeCode(String value) {
    return value == null ? "" : value.trim();
  }

  public static String normalizeNote(String value) {
    return value == null ? "" : value.trim();
  }

  public static String normalizeDimension(String value) {
    return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
  }

  public static String normalizeUnitCode(String value) {
    return value == null ? "" : value.trim();
  }

  /** 单位组编码校验。返回 null 表示可用；否则给出可执行的原因。 */
  public static String codeProblem(String value) {
    String code = normalizeCode(value);
    if (code.isEmpty()) return "单位组编码不能为空";
    if (code.length() > MAX_CODE_LENGTH) return "单位组编码最多 " + MAX_CODE_LENGTH + " 个字符";
    if (!code.matches(CODE_PATTERN)) return "单位组编码只能使用中文、英文字母、数字、下划线或短横线，长度 1~32 位";
    return null;
  }

  /** 单位组名称校验。返回 null 表示可用。 */
  public static String nameProblem(String value) {
    String name = value == null ? "" : value.trim();
    if (name.isEmpty()) return "单位组名称不能为空";
    if (name.length() > MAX_NAME_LENGTH) return "单位组名称最多 " + MAX_NAME_LENGTH + " 个字符";
    return null;
  }

  public static String dimensionLabel(String dimension) {
    return DIMENSIONS.getOrDefault(dimension, dimension);
  }

  public static String denyLabel(String code) {
    return DENY_LABELS.getOrDefault(code, code);
  }

  /**
   * 组内换算比例的校验。返回 null 表示可用。
   *
   * <p>与 {@link UnitRules#ratioProblem} 同一套约束（>0、6 位小数、整数部分 ≤13 位）， 两条路径的比例列都是 {@code
   * numeric(19,6)}，口径必须一致，否则同一份数据 在 SKU 侧能存、到组侧被判非法。
   */
  public static String ratioProblem(BigDecimal quantityPerBase) {
    if (quantityPerBase == null) return "换算比例不能为空";
    if (quantityPerBase.signum() <= 0) return "换算比例必须大于 0";
    if (quantityPerBase.scale() > 6) return "换算比例最多 6 位小数";
    if (quantityPerBase.precision() - quantityPerBase.scale() > 13) return "换算比例的整数部分最多 13 位";
    return null;
  }

  /**
   * 生效区间校验。返回 null 表示可用。
   *
   * <p>区间不重叠由 V17 的 {@code validate_unit_group_conversion_window} 触发器在 DB 层兜底，
   * 这里判的是触发器不判的那一半：截止日早于起始日。
   */
  public static String windowProblem(LocalDate from, LocalDate to) {
    if (from == null) return "生效起始日不能为空";
    if (to == null) return null; // 开放区间：当前仍生效
    if (to.isBefore(from)) return "生效截止日不能早于生效起始日";
    return null;
  }

  /**
   * 组内换算：把「from 单位」的数量换成「to 单位」的数量。
   *
   * <p>算式是 {@code quantity × fromPerBase ÷ toPerBase}，其中两者都是 「1 个该单位等于多少个组基础单位」：1 千克 = 1000 克、1 吨 =
   * 1000000 克时， 2 吨换成克 = 2 × 1000000 ÷ 1000 = 2000。先乘后除（而不是先求比值再乘） 是为了让 6 位小数的中间精度损失最少。
   *
   * <p>用 HALF_UP 而不是截断：截断会让账面比实盘少一点点，而差额不会留在任何地方。
   */
  public static BigDecimal convert(
      BigDecimal quantity, BigDecimal fromPerBase, BigDecimal toPerBase) {
    return quantity
        .multiply(fromPerBase)
        .divide(toPerBase, 6, RoundingMode.HALF_UP)
        .setScale(6, RoundingMode.HALF_UP);
  }

  /** 按单位精度展示数量：字典的 scale 决定显示几位小数，空值不画成 0。 */
  public static String display(BigDecimal quantity, int scale) {
    if (quantity == null) return "—";
    return quantity.setScale(Math.max(0, Math.min(6, scale)), RoundingMode.HALF_UP).toPlainString();
  }

  /**
   * 某一天命中哪一版换算。查不到就是查不到——调用方据此报 {@link #DENY_NO_EFFECTIVE_VERSION}，**不要**回退到"最近的一版"。
   *
   * <p>与 {@link UnitRules#effectiveOn} 同一语义，两条换算路径不允许出现不同答案。
   */
  public static boolean effectiveOn(LocalDate from, LocalDate to, LocalDate at) {
    return !from.isAfter(at) && (to == null || !to.isBefore(at));
  }

  /**
   * 量纲是否允许放进同一个组。
   *
   * <p>与 {@link UnitRules} 的量纲词表同源，所以两边对同一个量纲的判定必然一致： 单位能在 SKU 侧换算，就一定能进同一个组，反之亦然。
   */
  public static boolean sameDimension(String left, String right) {
    return left != null && right != null && left.equals(right);
  }

  /** 量纲不一致时的解释文案。调用方要显示它而不是只显示「不允许」。 */
  public static String dimensionMismatchMessage(
      String fromCode, String fromDimension, String toCode, String toDimension) {
    return "量纲不匹配：「"
        + fromCode
        + "」属于"
        + dimensionLabel(fromDimension)
        + "，「"
        + toCode
        + "」属于"
        + dimensionLabel(toDimension)
        + "，不同量纲的单位之间不允许换算";
  }
}
