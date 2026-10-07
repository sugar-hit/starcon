package com.starcon.catalog;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 3.1.2.3 外部条码（sku_identifier）的 scheme 取值与校验。
 *
 * <p>抽成纯函数的理由同 {@link SkuCodeRule}：条码校验是纯判定，必须能在不碰数据库的情况下
 * 逐条跑单元测试。条码一旦写错，代价出现在**拣货现场**（08 扫不出来、扫错货），
 * 而校验发生在导入那一刻——所以校验必须便宜、可穷举、可单测。
 *
 * <p>GTIN 家族（EAN-8 / EAN-13 / UPC-A / UPC-E / GTIN-14）统一走 mod-10 校验位：
 * 从右往左，数字位权重 3,1,3,1…，算出的校验位与末位比对。这与 GS1 的算法一致，
 * 也是 10 物流面单打印与平台校验会做的同一件事——本模块提前在导入时挡住。
 *
 * <p>CUSTOM 允许任意非空字符串：自定义 scheme（供应商货号、内部码、平台自有码）不具备
 * 固定长度与校验位，硬套 GTIN 规则会把合法条码误拒。它只保证非空与长度上限。
 *
 * <p>**seller_sku 不在这里**：它不是外部商品条码，而是平台侧属性，映射由 04 刊登维护。
 */
public final class BarcodeScheme {
  public static final String EAN8 = "EAN8";
  public static final String EAN13 = "EAN13";
  public static final String UPCA = "UPCA";
  public static final String UPCE = "UPCE";
  public static final String GTIN14 = "GTIN14";
  public static final String CUSTOM = "CUSTOM";

  /** sku_identifier.value 的列宽（V7）。 */
  public static final int MAX_VALUE_LENGTH = 128;

  /** 全租户支持的 scheme 取值集合。DB 层不限制取值，合法性由本类判定。 */
  public static final List<String> SCHEMES =
      List.of(EAN8, EAN13, UPCA, UPCE, GTIN14, CUSTOM);

  private static final Set<String> GTIN_SCHEMES = Set.of(EAN8, EAN13, UPCA, UPCE, GTIN14);

  /** scheme → 固定数字位数；GTIN 家族的位数由 scheme 决定。 */
  private static final java.util.Map<String, Integer> GTIN_LENGTHS =
      java.util.Map.of(EAN8, 8, EAN13, 13, UPCA, 12, UPCE, 8, GTIN14, 14);

  private BarcodeScheme() {}

  public static String normalize(String scheme) {
    return scheme == null ? "" : scheme.trim().toUpperCase(Locale.ROOT);
  }

  public static String label(String scheme) {
    return switch (scheme) {
      case EAN8 -> "EAN-8";
      case EAN13 -> "EAN-13";
      case UPCA -> "UPC-A";
      case UPCE -> "UPC-E";
      case GTIN14 -> "GTIN-14";
      case CUSTOM -> "自定义";
      default -> scheme;
    };
  }

  /**
   * 校验一条 (scheme, value)；返回 null 表示可用，否则返回给用户看的中文原因。
   *
   * <p>校验失败在批量导入里进**拒绝清单**（逐行带原因），在单条扫码录入里是 400。
   */
  public static String problem(String rawScheme, String rawValue) {
    String scheme = normalize(rawScheme);
    if (scheme.isEmpty()) return "条码 scheme 不能为空";
    if (!SCHEMES.contains(scheme)) return "不支持的条码 scheme：" + rawScheme + "（支持 " + String.join(" / ", SCHEMES) + "）";
    String value = rawValue == null ? "" : rawValue.trim();
    if (value.isEmpty()) return "条码值不能为空";
    if (value.length() > MAX_VALUE_LENGTH)
      return "条码值有 " + value.length() + " 位，超过 " + MAX_VALUE_LENGTH + " 位上限";
    if (!GTIN_SCHEMES.contains(scheme)) return null;

    Integer expected = GTIN_LENGTHS.get(scheme);
    if (!value.chars().allMatch(Character::isDigit))
      return BarcodeScheme.label(scheme) + " 只能由数字组成";
    if (value.length() != expected)
      return BarcodeScheme.label(scheme) + " 必须是 " + expected + " 位，当前 " + value.length() + " 位";
    if (!validCheckDigit(value))
      return BarcodeScheme.label(scheme) + " 校验位不正确（末位应为 " + expectedCheckDigit(value.substring(0, value.length() - 1)) + "）";
    return null;
  }

  /**
   * GTIN mod-10 校验位：从右往左，数据位权重 3,1,3,1… 求和后补到 10 的倍数。
   *
   * <p>已按 {@link #problem} 校验过位数与纯数字，外部可直接调用。
   */
  public static boolean validCheckDigit(String digits) {
    // 入参是含校验位的完整条码：先截掉末位再按数据位算，否则权重会整体错位一格。
    // 两侧都必须是 char：char - '0' 是 int，拿 char 去和它比会变成拿 ASCII 码比数字值，
    // 于是「4」永远不等于 4，所有条码都会被误判为校验位错。
    return expectedCheckDigit(digits.substring(0, digits.length() - 1))
        == digits.charAt(digits.length() - 1);
  }

  /**
   * GTIN mod-10 校验位：按 GS1 规则，**数据位最右边那一位权重为 3**，向左 3,1,3,1…交替，
   * 求和后补到 10 的倍数即得校验位。
   *
   * <p>入参是**不含校验位的数据位**。若把含校验位的整串传进来，权重会整体错位一格，
   * 算出的"应填校验位"与实际不符——所以 {@link #validCheckDigit} 先截掉末位再调用本方法。
   */
  public static char expectedCheckDigit(String dataDigits) {
    int sum = 0;
    for (int i = dataDigits.length() - 1, weight = 3; i >= 0; i--, weight = weight == 3 ? 1 : 3)
      sum += (dataDigits.charAt(i) - '0') * weight;
    return (char) ('0' + (10 - sum % 10) % 10);
  }
}