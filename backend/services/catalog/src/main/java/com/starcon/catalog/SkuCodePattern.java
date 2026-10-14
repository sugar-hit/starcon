package com.starcon.catalog;

import java.util.Locale;

/**
 * 3.1.2.3 内部 SKU 编码的**纯规则**：前缀 + 序号（补零）+ 后缀。
 *
 * <p>抽成纯函数而不是写在 service 里，是因为"这条规则展开出来的编码长什么样、是否越界"是 可以脱离数据库独立判定的，而且必须**在规则保存时就判定**——等到批量生成 500 个
 * SKU 才发现 编码超过 64 位要截断，那 500 个编码里已经写进去多少没人说得清。
 *
 * <p>可打印字符集与 {@link CatalogService#code(String)} 保持一致（{@code [A-Z0-9_-]}），
 * 这样手工输入的编码和规则生成的编码在同一个字符集里，按编码检索、排序、导出都不会出现 "一半带空格一半不带"的分裂。
 */
public final class SkuCodePattern {
  /** sku.code 的列宽（V1）：展开后的编码必须放得下。 */
  public static final int MAX_CODE_LENGTH = 64;

  private static final String CODE_CHARS =
      "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-";

  private SkuCodePattern() {}

  /** 规范化规则里可打印的前后缀：去空白、转大写，并拒绝规则字符集之外的字符。 */
  public static String normalizePart(String value, String label) {
    String normalized = value.trim().toUpperCase(Locale.ROOT);
    for (int i = 0; i < normalized.length(); i++) {
      if (CODE_CHARS.indexOf(normalized.charAt(i)) < 0)
        throw new IllegalArgumentException(
            label + "只能使用英文字母、数字、下划线或短横线，当前含有：" + normalized.charAt(i));
    }
    return normalized;
  }

  /**
   * 展开一条编码。
   *
   * <p>序号超过 {@code seqWidth} 位时**不截断**，而是原样变长——截断会让两个不同的序号 映射到同一个编码，直接破坏"编码唯一"这条命门。真正的边界在 {@link
   * #lengthProblem}： 规则保存时就要求展开长度留有余量，序号自然变长到越界会在生成前被拒绝。
   */
  public static String render(String prefix, long sequence, int seqWidth, String suffix) {
    return prefix + String.format(Locale.ROOT, "%0" + seqWidth + "d", sequence) + suffix;
  }

  /**
   * 规则能否落地：展开长度必须留有余量，让序号还能再长几位而不出界。
   *
   * <p>返回 null 表示没问题，否则返回给用户看的中文原因。
   */
  public static String lengthProblem(String prefix, int seqWidth, String suffix) {
    int worst = prefix.length() + seqWidth + suffix.length();
    if (worst > MAX_CODE_LENGTH)
      return "编码展开后最长 " + worst + " 个字符，超过 sku.code 的 " + MAX_CODE_LENGTH + " 位上限；请缩短前后缀或减小序号宽度";
    return null;
  }

  /** 生成前逐条校验展开结果；返回 null 表示这条编码可用。 */
  public static String codeProblem(String code) {
    if (code == null || code.isBlank()) return "展开出的编码为空";
    if (code.length() > MAX_CODE_LENGTH)
      return "展开出的编码有 " + code.length() + " 位，超过 " + MAX_CODE_LENGTH + " 位上限";
    return null;
  }
}
