package com.starcon.catalog;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 3.1.3.1 规格轴定义的纯规则：轴编码规范化、选项值合法性、矩阵组合计数。
 *
 * <p>与 {@link UnitRules} / {@link BarcodeScheme} 同一条纪律：**能在保存前判定的，就不要留到 生成 SKU
 * 矩阵时才炸**。轴编码非法如果等到矩阵展开才发现，运营已经按错轴建了一半 SKU。
 *
 * <p>本类不碰数据库，因此可以在离线单测里穷举，与前端 {@code spec-axis-model.ts} 同口径。
 *
 * <p>类名不叫 {@code SpecAxis}：那是被静态导入的 DTO record，两者在同一批文件里 会直接撞名（构造器解析到纯规则类上，编译期才炸）。
 */
public final class SpecAxisRules {
  private SpecAxisRules() {}

  /**
   * 选项值长度上限，与 {@code attribute_option.value varchar(100)} 一致。
   *
   * <p>规格选项与属性选项存的是**同一个值**（蓝图硬约束），所以长度上限必须跟着值源走， 不能由规格轴侧另定一个更宽或更窄的数——两个数不一致时会出现"值源能存、规格轴存不下"。
   */
  public static final int MAX_VALUE_LENGTH = 100;

  /**
   * 选项值字符集：中文、拉丁字母、数字、下划线、短横线、空格，首尾去空白后 1~100 位。
   *
   * <p>值是用户可见的规格文案（"深空灰"、"XL"、"500ml"），刻意与 {@link CatalogService#code} 的 [A-Z0-9_-]
   * 词表不同：轴编码是机器标识，选项值是人话。
   */
  private static final String VALUE_PATTERN =
      "[\\p{IsHan}A-Za-z0-9][\\p{IsHan}A-Za-z0-9 _\\-./]{0,99}";

  public static String normalizeCode(String value) {
    return value == null ? "" : value.trim().toUpperCase(java.util.Locale.ROOT);
  }

  /** 返回 null 表示可用；否则给出可执行的原因。离线可判，与后端写入口同口径。 */
  public static String codeProblem(String value) {
    String code = normalizeCode(value);
    if (code.isEmpty()) return "规格轴编码不能为空";
    if (!code.matches("[A-Z0-9][A-Z0-9_-]{0,63}")) return "规格轴编码请使用英文字母、数字、下划线或短横线，长度 1~64 位";
    return null;
  }

  public static String normalizeValue(String value) {
    return value == null ? "" : value.trim();
  }

  /** 选项值校验。返回 null 表示可用。 */
  public static String valueProblem(String value) {
    String normalized = normalizeValue(value);
    if (normalized.isEmpty()) return "规格选项值不能为空";
    if (normalized.length() > MAX_VALUE_LENGTH)
      return "规格选项值最多 " + MAX_VALUE_LENGTH + " 个字符（与属性选项的值源同长度）";
    if (!normalized.matches(VALUE_PATTERN)) return "规格选项值只能使用中文、英文字母、数字、空格、下划线、短横线、点或斜杠";
    return null;
  }

  /**
   * 矩阵笛卡尔积的**行数**（= 该 SPU 用各条轴的可用选项相乘）。
   *
   * <p>用 {@code long} 而不是 {@code int}：四条各 50 个选项的轴已经 6.25M 行， 五条就 3 亿行——{@code int}
   * 会静默溢出成负数，界面上画出「-1 个组合」。 这里不做上限拒绝，只如实返回真实数量并让调用方决定要不要提示规模过大。
   *
   * <p>没有任何可用选项的轴按 0 参与：这条轴还没配选项，矩阵就是空的，不该按 1 假装有值。
   */
  public static long matrixRowCount(Iterable<Set<String>> optionSets) {
    long rows = 1;
    for (Set<String> options : optionSets) {
      long size = options == null ? 0 : options.size();
      if (size == 0) return 0;
      rows = rows * size;
      // 溢出保护：超过 long 上限时钳到上限，界面上按"规模过大"提示而不是显示一个错的数。
      if (rows > 9_000_000_000_000_000_000L) return Long.MAX_VALUE;
    }
    return rows;
  }

  /** 轴上的选项去重并保持录入顺序，用于矩阵计算与展示。 */
  public static Set<String> distinctValues(java.util.Collection<String> values) {
    var result = new LinkedHashSet<String>();
    if (values != null)
      for (String value : values) {
        String normalized = normalizeValue(value);
        if (!normalized.isEmpty()) result.add(normalized);
      }
    return result;
  }
}
