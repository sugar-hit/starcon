package com.starcon.channel;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 16.2.2.2 认证与资质的<b>规则层</b>：词表与纯判定。
 *
 * <p>本类不碰数据库、不碰 HTTP，**可单测**（与 {@link CompliancePrecheckRules} 同一手法）。
 *
 * <p>🔴🔴 本类里**没有认证类型清单**：CE / FCC / RoHS / PSE / FDA / CCC 等内置类型是
 * **数据库里的数据**（迁移 V247 的 {@code certification_type} 种子行），
 * 租户可新增 / 改名 / 停用（{@code tenant_certification_type}）。
 * 把它们写成 Java 常量数组的那一刻，「这个租户用什么认证类型」就再也答不了了。
 *
 * <p>🔴 <b>到期状态是算出来的，不是存出来的</b>（{@link #expiryState}）：表里没有
 * {@code expiry_state} 列 —— 存一列就要一个每日任务去维护它，而维护出来的那天与读出口算出的
 * 那天差一天时，「这张证到底过期没有」会答错，而这个答案直接决定能不能刊登。
 * 判据用<b>数据库时钟</b>（{@code now()}），不是应用服务器时钟，也不是浏览器时钟。
 */
public final class ProductCertificationRules {

  private ProductCertificationRules() {}

  /** 认证状态取值。 */
  public static final List<String> STATUSES = List.of("ACTIVE", "REVOKED");

  /**
   * 到期状态取值（<b>现算</b>，不落库）。
   *
   * <p>判定顺序即优先级：撤销 &gt; 尚未生效 &gt; 已过期 &gt; 即将过期 &gt; 有效。
   */
  public static final List<String> EXPIRY_STATES =
      List.of("REVOKED", "FUTURE", "EXPIRED", "EXPIRING", "VALID");

  /** 扫描件类别取值。 */
  public static final List<String> DOCUMENT_KINDS = List.of("ORIGINAL", "RENEWAL", "ATTACHMENT");

  /** 认证适用范围取值。 */
  public static final List<String> SCOPES = List.of("GLOBAL", "MARKETPLACE");

  /** 默认「即将过期」窗口天数（与 6.1.2.3 供应商资质同口径）。 */
  public static final int DEFAULT_WARNING_DAYS = 30;

  /** 单份扫描件的服务端硬限（10 MB）。 */
  public static final long MAX_DOCUMENT_BYTES = 10L * 1024 * 1024;

  /**
   * 经浏览器代理上传时，原文件可用的字节上限（约 140 KB）。
   *
   * <p>同源代理只转发 JSON 且请求体上限 200,000 字符，base64 之后原文只剩约 3/4，
   * 再留出 JSON 信封的余量 ⇒ 140 KB。直连 multipart 上传不受此限（服务端硬限 10 MB）。
   */
  public static final int BROWSER_PROXY_BASE64_LIMIT_BYTES = 140_000;

  /** 认证类型编码的字面量形态（大写字母、数字、下划线、短横线）。 */
  private static final Pattern TYPE_CODE = Pattern.compile("^[A-Z0-9][A-Z0-9_-]{0,31}$");

  /** 认证档案编码的字面量形态。 */
  private static final Pattern CERT_CODE = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$");

  /**
   * 到期状态。
   *
   * @param status 认证状态（REVOKED 优先于一切）
   * @param validFrom 生效时刻
   * @param validUntil 到期时刻
   * @param now 判定时刻（来自数据库时钟）
   * @param warningDays 即将过期窗口天数
   */
  public static String classifyExpiry(
      String status,
      OffsetDateTime validFrom,
      OffsetDateTime validUntil,
      OffsetDateTime now,
      int warningDays) {
    if ("REVOKED".equals(status)) {
      return "REVOKED";
    }
    if (validFrom != null && now.isBefore(validFrom)) {
      return "FUTURE";
    }
    if (validUntil == null) {
      return "VALID";
    }
    if (!now.isBefore(validUntil)) {
      return "EXPIRED";
    }
    if (now.plusDays(warningDays).isAfter(validUntil) || now.plusDays(warningDays).equals(validUntil)) {
      return "EXPIRING";
    }
    return "VALID";
  }

  /**
   * 剩余天数。
   *
   * <p>🔴 负数 = 已过期几天。<b>向下取整</b>（floorDiv）而不是截断：
   * 截断会让「还剩 12 小时」显示成「还剩 0 天」，而这 0 天读起来就是「今天到期」，
   * 运营当天不会去补 —— 而「还剩 12 小时」本该是最该提醒的那一档。
   */
  public static int daysRemaining(OffsetDateTime validUntil, OffsetDateTime now) {
    if (validUntil == null) {
      return 0;
    }
    long seconds = Duration.between(now, validUntil).getSeconds();
    return (int) Math.floorDiv(seconds, 86_400L);
  }

  /** 认证类型编码规范化：去空白 + 转大写。 */
  public static String normalizeTypeCode(String raw) {
    return raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
  }

  /** 认证类型编码字面量校验。 */
  public static boolean validTypeCode(String code) {
    return code != null && TYPE_CODE.matcher(code).matches();
  }

  /** 认证档案编码字面量校验。 */
  public static boolean validCertificationCode(String code) {
    return code != null && CERT_CODE.matcher(code).matches();
  }

  /** 适用范围规范化：去空白 + 转大写。 */
  public static String normalizeScope(String raw) {
    return raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
  }

  /** 到期状态的展示顺序（界面按这个顺序渲染，不按字母序）。 */
  public static List<String> expiryStateOrder() {
    return List.of("EXPIRED", "EXPIRING", "FUTURE", "REVOKED", "VALID");
  }
}
