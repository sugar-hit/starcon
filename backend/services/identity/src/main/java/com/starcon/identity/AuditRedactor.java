package com.starcon.identity;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 17.5.3.1 变更详情脱敏。
 *
 * <p>蓝图原文：**审计不记录密码与完整客户地址；变更详情必须脱敏**。这里的取舍是脱敏发生在 <b>写入侧</b>，而不是读取时过滤——库里存的就已经是脱敏后的值，这样导出 CSV、直连
 * SQL 排查、其他服务订阅审计事件时都不可能再拿到明文。只在读取时过滤等于把明文留在库里， 换一个读取方（运维、BI、误配置的 SQL 台）就漏一次。
 *
 * <p>两条互补规则：
 *
 * <ul>
 *   <li><b>按键判定</b>：字段名命中敏感词（password / token / 证件号 / 银行卡 …）就整值替换。
 *   <li><b>按值判定</b>：值本身长得像邮箱、手机号、银行卡或身份证就局部打码——因为业务代码 完全可能把收件地址塞进一个叫 {@code remark}
 *       的字段，按键判定挡不住这种情况。
 * </ul>
 *
 * <p>本类无状态、可静态调用，所有写入点共用同一份口径，避免各处各写一套正则导致漏网。
 */
public final class AuditRedactor {
  /** 整值替换时使用的占位符。留痕长度信息但完全不泄露内容。 */
  public static final String MASK = "***";

  private AuditRedactor() {}

  /**
   * 凭证类字段名：命中即<b>整值替换</b>。
   *
   * <p>这些字段不保留任何原文片段——连长度信息都可能泄露（bcrypt 的 {@code $2a$10$} 前缀 直接暴露哈希强度），所以不用「保留头尾」那套。
   *
   * <p>用 {@code code} 点分小写做匹配，所以 {@code passwordHash}、{@code PASSWORD}、 {@code api_key} 都会被归一化后命中。
   */
  private static final String[] CREDENTIAL_KEYS = {
    "password",
    "passwd",
    "pwd",
    "token",
    "secret",
    "apikey",
    "accesskey",
    "privatekey",
    "credential",
    "authorization",
    "cookie",
    "session",
    "signature",
    "salt",
    "hash"
  };

  /**
   * 身份与联系信息类字段名：命中后<b>保留头尾</b>打码。
   *
   * <p>与凭证类的区别：审计要能看出「这个证件号改成了另一个」而不是只知道「某个字段变了」。 保留前 3 后 2（如身份证）足以区分两次不同输入，又不足以还原原文。
   */
  private static final String[] IDENTITY_KEYS = {
    "idcard",
    "idno",
    "identityno",
    "passport",
    "bankcard",
    "cardno",
    "iban",
    "cvv",
    "taxno",
    "phone",
    "mobile",
    "tel",
    "email",
    "address",
    "consignee",
    "receiver"
  };

  /** 中文列名（业务表里很常见），归一化会剥掉非 ASCII，所以单独按原名匹配。 */
  private static final String[] CHINESE_SENSITIVE_KEYS = {
    "用户名", "账号", "手机号", "邮箱", "电话", "地址", "收货地址", "密码", "凭证", "证件号", "身份证", "银行卡", "密钥"
  };

  /** 按值判定的敏感形态。顺序有意义：先判结构更明确的，避免被宽松规则先吃掉。 */
  private static final Pattern EMAIL =
      Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

  /** 中国大陆手机号：1 开头、第二位 3-9、共 11 位。 */
  private static final Pattern MOBILE = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");

  /** 银行卡：13-19 位连续数字，两端都不得再接数字，避免从长串里截一段误判。 */
  private static final Pattern BANK_CARD = Pattern.compile("(?<!\\d)\\d{13,19}(?!\\d)");

  /** 身份证：15 位旧号或 18 位新号（末位可为 X）。 */
  private static final Pattern ID_CARD =
      Pattern.compile("(?<![0-9A-Za-z])\\d{17}[0-9Xx](?![0-9A-Za-z])|(?<!\\d)\\d{15}(?!\\d)");

  /** 字段名归一化：小写、去分隔符，让 {@code api-key} / {@code APIKey} / {@code api_key} 同义。 */
  private static String normalize(String field) {
    return field.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "");
  }

  private static boolean containsAny(String normalized, String[] words) {
    for (String word : words) if (normalized.contains(word)) return true;
    return false;
  }

  /** 中文列名直接按原名判定：{@code normalize} 会把非 ASCII 全部剥掉，中文必须在这里拦。 */
  private static boolean isChineseSensitive(String field) {
    for (String word : CHINESE_SENSITIVE_KEYS) if (field.contains(word)) return true;
    return false;
  }

  /** 字段名是否敏感（凭证类或身份类，任一命中）。 */
  static boolean isSensitiveKey(String field) {
    return isCredentialKey(field) || isIdentityKey(field);
  }

  static boolean isCredentialKey(String field) {
    return containsAny(normalize(field), CREDENTIAL_KEYS);
  }

  static boolean isIdentityKey(String field) {
    if (isChineseSensitive(field)) return true;
    String key = normalize(field);
    return !key.isEmpty() && containsAny(key, IDENTITY_KEYS);
  }

  /** 局部打码：保留头尾，中间用 MASK 顶替，既能看出「改过」又认不出原文。 */
  private static String keepEnds(String value, int head, int tail) {
    if (value.length() <= head + tail + MASK.length()) return MASK;
    return value.substring(0, head) + MASK + value.substring(value.length() - tail);
  }

  /**
   * 已知形态（身份证 / 银行卡 / 手机 / 邮箱）按各自规则打码；认不出就整值替换，不留原文。
   *
   * <p>用于「字段名已判定敏感」的值：整串本身就是那一种形态。
   */
  private static String maskKnown(String value) {
    var idCard = ID_CARD.matcher(value);
    if (idCard.find()) return keepEnds(idCard.group(), 3, 2);
    var card = BANK_CARD.matcher(value);
    if (card.find()) return keepEnds(card.group(), 0, 4);
    var mobile = MOBILE.matcher(value);
    if (mobile.find()) return keepEnds(mobile.group(), 3, 4);
    var email = EMAIL.matcher(value);
    if (email.find()) return maskEmail(email.group());
    return MASK;
  }

  /**
   * 邮箱打码：本地部分只留首字符，域名完整保留。
   *
   * <p>保留域名是为了让审计仍能回答「这批变更都发往同一个公司邮箱」， 而真正承载个人身份的本地部分只留一个首字符——够区分不同值，不足以还原原文。 这里不能用 {@link
   * #keepEnds}：域名的长度会参与阈值计算，短域名邮箱会被整值抹掉。
   */
  private static String maskEmail(String matched) {
    int at = matched.indexOf('@');
    if (at <= 0) return MASK;
    return matched.charAt(0) + MASK + matched.substring(at);
  }

  /**
   * 对「命名无害」的字段值做形态打码：值里藏了邮箱/手机/卡号/证件号就替换。
   *
   * <p>与 {@link #maskKnown} 的区别是这里在整串里扫描（可能只是其中一个片段敏感）， 而不是要求整串就是那一种形态——{@code remark} 里写「联系我
   * 13800138000」要能挡住。
   */
  static String maskValue(String source) {
    String masked = ID_CARD.matcher(source).replaceAll(m -> keepEnds(m.group(), 3, 2));
    masked = BANK_CARD.matcher(masked).replaceAll(m -> keepEnds(m.group(), 0, 4));
    masked = MOBILE.matcher(masked).replaceAll(m -> keepEnds(m.group(), 3, 4));
    return EMAIL.matcher(masked).replaceAll(m -> maskEmail(m.group()));
  }

  /**
   * 对单个字段值脱敏，返回可直接写入审计 JSON 的对象。
   *
   * <p>非字符串值（布尔、数字、null）原样返回：它们不承载凭证或个人信息， 而审计详情需要保留「从 false 改成 true」这类事实，也需要 amountScale
   * 是数字而不是字符串。
   */
  public static Object redact(String field, Object value) {
    if (value == null) return null;
    if (isCredentialKey(field)) return MASK;
    // 地址/收货地址按蓝图的「不记录完整客户地址」口径整值替换，保留任何片段都可能拼回原文。
    if (isIdentityKey(field)) {
      String text = String.valueOf(value);
      return addressLike(field) ? MASK : maskKnown(text);
    }
    if (value instanceof CharSequence text) {
      String source = text.toString();
      String masked = maskValue(source);
      return masked.equals(source) ? source : masked;
    }
    return value;
  }

  /** 地址类字段整值替换；其余身份字段保留头尾。 */
  private static boolean addressLike(String field) {
    String key = normalize(field);
    return key.contains("address") || field.contains("地址");
  }

  /** 对整个快照脱敏，保持字段顺序以便审计详情逐行对比。null 快照原样返回。 */
  public static Map<String, Object> redactAll(Map<String, Object> values) {
    if (values == null) return null;
    Map<String, Object> safe = new LinkedHashMap<>();
    for (var entry : values.entrySet())
      safe.put(entry.getKey(), redact(entry.getKey(), entry.getValue()));
    return safe;
  }
}
