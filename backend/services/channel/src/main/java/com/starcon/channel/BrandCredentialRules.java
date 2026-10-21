package com.starcon.channel;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;

/**
 * 16.4.2.2 授权凭证的<b>规则层</b>：词表与纯判定。
 *
 * <p>本类不碰数据库、不碰 HTTP、**不碰文件系统**，可单测（与
 * {@link BrandAuthorizationRules} / {@link ProductCertificationRules} 同一手法）。
 *
 * <p>🔴🔴 本类里有三处**结构**上的不变量：
 *
 * <ol>
 *   <li><b>「真伪」的判据是文件摘要</b>：把受管存储里的文件本体读回来重算 sha256，
 *       与登记时记下的摘要逐字比对。摘要不符 ⇒ 文件在登记之后被改过 ⇒ 判定不通过。
 *       {@link #sha256Hex} 是唯一的摘要算法入口，{@link #isDigest} 是唯一的形态校验。
 *   <li><b>「没核验过」与「核验过但不通过」是两个状态</b>：
 *       {@code UNVERIFIED} ≠ {@code FAILED}。前者是<b>还没查</b>，后者是<b>查了没过</b>。
 *   <li><b>结论与分级必须自洽</b>：{@code passed = (conclusion == 'VERIFIED')}，
 *       {@code severity = passed ? 'PASS' : 'FAIL'} —— 应用层算错也在 DB 层被拒
 *       （迁移里的 CHECK 钉住同一口径）。
 * </ol>
 */
public final class BrandCredentialRules {

  private BrandCredentialRules() {}

  // ---------------------------------------------------------------- 词表

  /** 凭证类型取值。 */
  public static final List<String> CREDENTIAL_TYPES =
      List.of(
          "BRAND_LICENSE",
          "DISTRIBUTOR_AGREEMENT",
          "TRADEMARK_CERTIFICATE",
          "CERTIFICATE_OF_ORIGIN",
          "AUTHORIZATION_LETTER",
          "OTHER");

  /** 核验结论取值（八态）。 */
  public static final List<String> CONCLUSIONS =
      List.of(
          "VERIFIED",
          "DIGEST_MISMATCH",
          "FILE_MISSING",
          "EXPIRED",
          "NOT_YET_EFFECTIVE",
          "SIGNATORY_INCOMPLETE",
          "NO_DOCUMENT",
          "REVOKED");

  /** 核验分级取值。 */
  public static final List<String> SEVERITIES = List.of("PASS", "FAIL");

  /** 凭证「最近核验状态」取值（**现算**，不落库）。 */
  public static final List<String> VERIFICATION_STATES = List.of("UNVERIFIED", "VERIFIED", "FAILED");

  /** 到期状态取值（**现算**，不落库）。 */
  public static final List<String> EXPIRY_STATES =
      List.of("REVOKED", "NOT_YET_EFFECTIVE", "EXPIRED", "EXPIRING", "ACTIVE");

  /** 原因码取值。 */
  public static final List<String> REASON_CODES =
      List.of(
          "REVOKED",
          "NO_DOCUMENT",
          "FILE_MISSING",
          "DIGEST_MISMATCH",
          "NOT_YET_EFFECTIVE",
          "EXPIRED",
          "SIGNATORY_INCOMPLETE",
          "SIGNATORY_UNVERIFIED");

  // ---------------------------------------------------------------- 常量

  /**
   * 当前核验规则的版本。
   *
   * <p>🔴 规则改了就是**新版本 + 新一行核验记录**，旧行原样留着
   * —— 「上次为什么判成那样」永远答得出来。16.2.1.1 合规规则库交付后，
   * 这个版本号改成从库里取。
   */
  public static final long CURRENT_RULE_VERSION = 1L;

  /** 「即将过期」窗口天数（同 16.4.2.1 与 6.1.2.3 的口径）。 */
  public static final int DEFAULT_EXPIRY_WARNING_DAYS = 30;

  /** 单份凭证文件的服务端硬限（20 MB）。 */
  public static final long MAX_DOCUMENT_BYTES = 20L * 1024 * 1024;

  /**
   * 经浏览器代理上传时，原文件可用的字节上限（约 140 KB）。
   *
   * <p>同源代理只转发 JSON 且请求体上限 200,000 字符，base64 之后原文只剩约 3/4，
   * 再留出 JSON 信封的余量 ⇒ 140 KB。直连 multipart 上传不受此限（服务端硬限 20 MB）。
   */
  public static final int BROWSER_PROXY_BASE64_LIMIT_BYTES = 140_000;

  /** 一次批量核验最多处理几个凭证。 */
  public static final int MAX_BATCH_VERIFY = 50;

  // ---------------------------------------------------------------- 到期状态

  /**
   * 到期状态。
   *
   * <p>判定顺序即优先级：撤销 &gt; 尚未生效 &gt; 已过期 &gt; 即将过期 &gt; 有效。
   *
   * @param status 凭证状态（REVOKED 优先于一切）
   * @param validFrom 有效期起点
   * @param validUntil 有效期终点
   * @param now 判定时刻（来自数据库时钟）
   * @param warningDays 即将过期窗口天数
   */
  public static String classifyExpiry(
      String status, OffsetDateTime validFrom, OffsetDateTime validUntil, OffsetDateTime now, int warningDays) {
    if ("REVOKED".equals(status)) {
      return "REVOKED";
    }
    if (validFrom != null && now.isBefore(validFrom)) {
      return "NOT_YET_EFFECTIVE";
    }
    if (validUntil == null) {
      return "ACTIVE";
    }
    if (!now.isBefore(validUntil)) {
      return "EXPIRED";
    }
    if (now.plusDays(Math.max(0, warningDays)).isAfter(validUntil)
        || now.plusDays(Math.max(0, warningDays)).equals(validUntil)) {
      return "EXPIRING";
    }
    return "ACTIVE";
  }

  /**
   * 剩余天数。
   *
   * <p>🔴 负数 = 已过期几天。<b>向下取整</b>（floorDiv）而不是截断：
   * 截断会让「还剩 12 小时」显示成「还剩 0 天」，而这 0 天读起来就是「今天到期」。
   */
  public static int daysRemaining(OffsetDateTime validUntil, OffsetDateTime now) {
    if (validUntil == null) {
      return 0;
    }
    long seconds = Duration.between(now, validUntil).getSeconds();
    return (int) Math.floorDiv(seconds, 86_400L);
  }

  // ---------------------------------------------------------------- 摘要

  /**
   * 文件摘要（小写十六进制 sha256）。
   *
   * <p>🔴 这是「真伪核验」的<b>唯一</b>摘要入口 —— 登记时算一次，核验时再算一次，
   * 两次必须逐字相等。用别的算法或别的编码方式，两边就会对不上，
   * 而「对不上」在界面上读起来是「文件是假的」—— 那是误伤。
   */
  public static String sha256Hex(byte[] content) {
    if (content == null) {
      throw new IllegalArgumentException("文件内容为空，无法计算摘要");
    }
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
      StringBuilder out = new StringBuilder(digest.length * 2);
      for (byte b : digest) {
        out.append(Character.forDigit((b >> 4) & 0xf, 16));
        out.append(Character.forDigit(b & 0xf, 16));
      }
      return out.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("运行环境缺少 SHA-256 实现", e);
    }
  }

  /** 摘要形态校验（小写十六进制 64 位）—— 与 DB 层的 CHECK 同一口径。 */
  public static boolean isDigest(String value) {
    if (value == null || value.length() != 64) {
      return false;
    }
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
      if (!hex) {
        return false;
      }
    }
    return true;
  }

  // ---------------------------------------------------------------- 结论

  /**
   * 结论的严重度优先级（**越大越严重**）。
   *
   * <p>一次核验可能同时发现好几条问题（文件不在 + 已过期 + 没签署方）。
   * {@code conclusion} 取**最严重**的那一条，其余作为 {@code reasons} 一并给出 ——
   * 只报最严重的会让运营以为「修好这一条就没事了」，而事实是还有别的问题。
   */
  public static int conclusionPriority(String conclusion) {
    return switch (conclusion == null ? "" : conclusion) {
      case "REVOKED" -> 70;
      case "NO_DOCUMENT" -> 60;
      case "FILE_MISSING" -> 50;
      case "DIGEST_MISMATCH" -> 40;
      case "NOT_YET_EFFECTIVE" -> 30;
      case "EXPIRED" -> 20;
      case "SIGNATORY_INCOMPLETE" -> 10;
      case "VERIFIED" -> 0;
      default -> -1;
    };
  }

  /** 一组结论里最严重的那个（全 VERIFIED 时返回 VERIFIED）。 */
  public static String mostSevere(List<String> conclusions) {
    String worst = "VERIFIED";
    int worstPriority = 0;
    for (String candidate : conclusions) {
      int priority = conclusionPriority(candidate);
      if (priority > worstPriority) {
        worstPriority = priority;
        worst = candidate;
      }
    }
    return worst;
  }

  /** 通过 ⟺ 结论是 VERIFIED（与 DB 层的 CHECK 同一口径）。 */
  public static boolean passed(String conclusion) {
    return "VERIFIED".equals(conclusion);
  }

  /** 分级：PASS / FAIL（与 DB 层的 CHECK 同一口径）。 */
  public static String severity(String conclusion) {
    return passed(conclusion) ? "PASS" : "FAIL";
  }

  /** 凭证「最近核验状态」：VERIFIED / FAILED（没有核验记录时是 UNVERIFIED，由调用方处理）。 */
  public static String verificationState(boolean passed) {
    return passed ? "VERIFIED" : "FAILED";
  }

  // ---------------------------------------------------------------- 原因

  /** 原因码的人话标题。 */
  public static String reasonLabel(String code) {
    return switch (code == null ? "" : code) {
      case "REVOKED" -> "凭证已被撤销";
      case "NO_DOCUMENT" -> "凭证还没有文件";
      case "FILE_MISSING" -> "文件本体不在受管存储里";
      case "DIGEST_MISMATCH" -> "文件摘要对不上";
      case "NOT_YET_EFFECTIVE" -> "凭证尚未生效";
      case "EXPIRED" -> "凭证已过期";
      case "SIGNATORY_INCOMPLETE" -> "没有登记签署方";
      case "SIGNATORY_UNVERIFIED" -> "签署方身份尚未核验";
      default -> "需要人工复核";
    };
  }

  /**
   * 原因码的分级：FAIL（不通过的原因）/ WARN（通过了但仍要提醒）。
   *
   * <p>🔴 {@code SIGNATORY_UNVERIFIED} 是 **WARN** 而不是 FAIL：
   * 「签署人姓名职务都登记了，但还没有人工核过身份」是**常态**，
   * 把它算成不通过会让「一键核验」这个功能每天都红。
   * 但**一条签署方都没登记**（{@code SIGNATORY_INCOMPLETE}）是真的不完整 ⇒ FAIL。
   */
  public static String reasonSeverity(String code) {
    return "SIGNATORY_UNVERIFIED".equals(code) ? "WARN" : "FAIL";
  }

  // ---------------------------------------------------------------- 规范化

  /** 凭证类型规范化：去空白 + 转大写。 */
  public static String normalizeCredentialType(String raw) {
    return raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
  }

  /**
   * 存储键：租户 / 凭证 / 文档 id + 净化后的扩展名。
   *
   * <p>🔴 **不接受调用方原样透传的文件名**：文件名会带路径分隔符、{@code ..}、
   * 甚至中文与空格。键是唯一由服务端生成的东西，租户段在键里 ——
   * 一个租户算不出另一个租户的键。
   */
  public static String storageKey(
      java.util.UUID tenant, java.util.UUID credentialId, java.util.UUID documentId, String fileName) {
    String extension = "";
    if (fileName != null) {
      int dot = fileName.lastIndexOf('.');
      if (dot >= 0 && dot < fileName.length() - 1) {
        String candidate = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (candidate.matches("[a-z0-9]{1,10}")) {
          extension = "." + candidate;
        }
      }
    }
    return "credentials/" + tenant + "/" + credentialId + "/" + documentId + extension;
  }

  /**
   * 净化后的展示文件名（去掉路径部分）。
   *
   * <p>🔴 文件名会原样显示在界面上，也原样写进 DB：带 {@code ../} 的文件名
   * 既是信息泄露也是显示事故。
   */
  public static String sanitizeFileName(String raw) {
    String value = raw == null ? "" : raw.trim();
    if (value.isEmpty()) {
      return "credential-file";
    }
    int slash = Math.max(value.lastIndexOf('/'), value.lastIndexOf('\\'));
    if (slash >= 0 && slash < value.length() - 1) {
      value = value.substring(slash + 1);
    }
    value = value.replaceAll("[\\p{Cntrl}]", "");
    if (value.length() > 255) {
      value = value.substring(value.length() - 255);
    }
    return value.isEmpty() ? "credential-file" : value;
  }
}
