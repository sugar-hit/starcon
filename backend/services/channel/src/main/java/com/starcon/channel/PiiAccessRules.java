package com.starcon.channel;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 16.6.1.1 PII 管理的<b>纯计算</b>（不含 IO、不含 Spring）。
 *
 * <p>🔴 这个类只做两件事：**脱敏**与**授权判定**。把它们单独拎出来（而不是塞进 Service 里），
 * 是因为这两件事是本模块的命门 ——
 *
 * <ol>
 *   <li><b>脱敏必须真的脱敏</b>：掩码后的值**不得等于明文**，也**不得是明文的可逆编码**。
 *       用 {@code *} 逐字掩码就天然满足后者：一个可逆的编码（base64 / AES）是可以被还原的，
 *       那不叫脱敏，叫换了个写法的明文。</li>
 *   <li><b>授权判定必须能单独测</b>：角色 / 字段 / 成员范围三者的组合有十几种，
 *       混在数据库访问里就没人能一眼看出它对不对。</li>
 * </ol>
 *
 * <p>🔴 <b>「查无此人」与「这个人没有 PII」是两件事</b>：
 * {@link #decide} 之外的所有判定都靠 {@code known} / {@code present} 两个布尔量区分，
 * 折成空串是这类系统最致命的一种撒谎。
 */
public final class PiiAccessRules {

  private PiiAccessRules() {}

  // ================================================================ 字段口径

  /** 姓名。 */
  public static final String FIELD_NAME = "NAME";

  /** 电话。 */
  public static final String FIELD_PHONE = "PHONE";

  /** 邮箱。 */
  public static final String FIELD_EMAIL = "EMAIL";

  /** 地址。 */
  public static final String FIELD_ADDRESS = "ADDRESS";

  /** 审计里的「整条主体脱敏视图」。 */
  public static final String AUDIT_FIELD_ALL = "ALL";

  /** 全部字段类别（受控值域，与 DB 的 CHECK 一致）。 */
  public static final List<String> FIELDS = List.of(FIELD_NAME, FIELD_PHONE, FIELD_EMAIL, FIELD_ADDRESS);

  /** 成员范围：全部。 */
  public static final String SCOPE_ALL = "ALL";

  /** 成员范围：部门。 */
  public static final String SCOPE_DEPARTMENT = "DEPARTMENT";

  /** 成员范围：本人经手。 */
  public static final String SCOPE_SELF = "SELF";

  /** 字段类别的人话标签。 */
  public static String fieldLabel(String fieldCode) {
    return switch (fieldCode) {
      case FIELD_NAME -> "姓名";
      case FIELD_PHONE -> "电话";
      case FIELD_EMAIL -> "邮箱";
      case FIELD_ADDRESS -> "地址";
      default -> fieldCode;
    };
  }

  /** 成员范围的人话标签。 */
  public static String scopeLabel(String scopeMode) {
    return switch (scopeMode) {
      case SCOPE_ALL -> "全部成员";
      case SCOPE_DEPARTMENT -> "限部门成员";
      case SCOPE_SELF -> "限本人经手";
      default -> scopeMode;
    };
  }

  // ================================================================ 脱敏

  /**
   * 姓名脱敏：**保留姓氏**，其余掩码。
   *
   * <p>🔴 两种姓名形态分开处理，因为「姓氏在哪」在两种语言里位置不同：
   *
   * <ul>
   *   <li><b>西式（含空白）</b>：末段是姓 ⇒ 保留姓氏全文，名的部分只留首字母。
   *       {@code Alice Smith} ⇒ {@code A**** Smith}。</li>
   *   <li><b>中文式（无空白）</b>：首字符就是姓 ⇒ 保留首字符。
   *       {@code 王小明} ⇒ {@code 王**}。</li>
   * </ul>
   *
   * <p>掩码用 {@code *} 逐字替换 ⇒ 掩码值**不是明文的可逆编码**。
   */
  public static String maskName(String plain) {
    String v = nz(plain).trim();
    if (v.isEmpty()) {
      return "";
    }
    int sp = v.lastIndexOf(' ');
    if (sp > 0 && sp < v.length() - 1) {
      String given = v.substring(0, sp);
      String family = v.substring(sp + 1);
      return given.charAt(0) + "*".repeat(Math.max(1, given.length() - 1)) + " " + family;
    }
    return v.charAt(0) + "*".repeat(Math.max(1, v.length() - 1));
  }

  /**
   * 电话脱敏：**保留末四位**，其余掩码。
   *
   * <p>🔴 只按**数字**计数掩码长度：分隔符（{@code + - 空格}）本身不携带信息，
   * 留着它会让掩码值看着像真电话。
   */
  public static String maskPhone(String plain) {
    String v = nz(plain).trim();
    if (v.isEmpty()) {
      return "";
    }
    StringBuilder digits = new StringBuilder();
    for (int i = 0; i < v.length(); i++) {
      if (Character.isDigit(v.charAt(i))) {
        digits.append(v.charAt(i));
      }
    }
    if (digits.length() == 0) {
      return "";
    }
    if (digits.length() <= 4) {
      return "*".repeat(digits.length());
    }
    return "*".repeat(digits.length() - 4) + digits.substring(digits.length() - 4);
  }

  /**
   * 邮箱脱敏：**本地部分掩码**，域名保留。
   *
   * <p>{@code alice@example.com} ⇒ {@code a***@example.com}。
   *
   * <p>🔴 本地部分**只有一个字符**时整段掩码（{@code a@example.com} ⇒ {@code *@example.com}）：
   * 否则「保留首字符」会把原文原样吐出来 —— 脱敏值等于明文是最难被发现的一种失败。
   */
  public static String maskEmail(String plain) {
    String v = nz(plain).trim();
    if (v.isEmpty()) {
      return "";
    }
    int at = v.indexOf('@');
    if (at <= 0) {
      // 没有 @ ⇒ 不是一个可解析的邮箱：**不猜**，整段掩码。
      return "*".repeat(v.length());
    }
    String local = v.substring(0, at);
    String domain = v.substring(at);
    if (local.length() <= 1) {
      return "*" + domain;
    }
    return local.charAt(0) + "*".repeat(local.length() - 1) + domain;
  }

  /**
   * 地址脱敏：**保留到区级**（国家 / 省 / 市 / 区），街道与邮编掩码。
   *
   * <p>街道逐字掩码（空格与标点保留），邮编逐字掩码 ⇒ 掩码值不含街道原文。
   */
  public static String maskAddress(
      String country,
      String province,
      String city,
      String district,
      String postcode,
      String line1,
      String line2) {
    List<String> parts = new ArrayList<>();
    for (String raw : List.of(country, province, city, district)) {
      String t = nz(raw).trim();
      if (!t.isEmpty() && !parts.contains(t)) {
        parts.add(t);
      }
    }
    String street = (nz(line1).trim() + " " + nz(line2).trim()).trim();
    if (!street.isEmpty()) {
      parts.add(maskChars(street));
    }
    String zip = nz(postcode).trim();
    if (!zip.isEmpty()) {
      parts.add(maskChars(zip));
    }
    return String.join(" / ", parts);
  }

  /** 逐字掩码：字母数字换 {@code *}，空格与标点保留（保留形状，不泄露原文）。 */
  public static String maskChars(String plain) {
    String v = nz(plain);
    StringBuilder sb = new StringBuilder(v.length());
    for (int i = 0; i < v.length(); i++) {
      char c = v.charAt(i);
      sb.append(Character.isLetterOrDigit(c) ? '*' : c);
    }
    return sb.toString();
  }

  /**
   * 主体标签（列表与审计里显示的那一栏）。
   *
   * <p>🔴 **用脱敏值当标签**：审计表与导出清单里都不该出现人读得懂的姓名 ——
   * 那会让审计表变成第二个 PII 副本。
   */
  public static String subjectLabel(String subjectRef, String plainName) {
    String masked = maskName(plainName);
    if (!masked.isEmpty()) {
      return masked;
    }
    String ref = nz(subjectRef);
    return ref.length() <= 40 ? ref : ref.substring(0, 40) + "…";
  }

  // ================================================================ 授权判定

  /** 一条生效中的授权（已按当前时刻过滤过有效期的那些）。 */
  public record GrantFact(
      UUID grantId, String roleCode, String fieldCode, String scopeMode, boolean reasonRequired) {}

  /**
   * 授权判定结果。
   *
   * @param allowed 是否放行
   * @param reason 人话理由（**被拒时必须说清为什么** —— 「没权限」三个字无法追责）
   */
  public record Decision(
      boolean allowed, UUID grantId, String roleCode, String scopeMode, String reason) {}

  /**
   * 判定「这个成员能不能看这个主体的这个字段的完整值」。
   *
   * <p>🔴 <b>判定顺序是有意义的</b>：先看**有没有**授权（角色 × 字段），
   * 再看**范围**（ALL / DEPARTMENT / SELF）。范围判不过时**不给任何部分值** ——
   * 半授权（名字给、电话不给）会让调用方误以为「拿到了就等于拿到了」。
   *
   * <p>🔴 <b>多条授权命中时取最宽松的一条</b>（ALL &gt; DEPARTMENT &gt; SELF），
   * 并在返回值里带上**具体依据哪一条**（{@code grantId} / {@code roleCode}）——
   * 界面要能回答「凭什么能看到」。
   *
   * @param grants 该租户当前对该字段生效的授权行
   * @param actorRoleCodes 该成员命中的角色 code 集合（一个成员可能有多个角色）
   * @param fieldCode 要看的字段
   * @param actorHasDepartment 该成员是否属于某个部门（DEPARTMENT 范围要用）
   * @param subjectHandledByActor 主体是否由本人经手；<b>null = 判不了</b>（SELF 范围要用）
   */
  public static Decision decide(
      List<GrantFact> grants,
      Set<String> actorRoleCodes,
      String fieldCode,
      boolean actorHasDepartment,
      Boolean subjectHandledByActor) {

    List<GrantFact> candidates = new ArrayList<>();
    for (GrantFact grant : grants) {
      if (!fieldCode.equals(grant.fieldCode())) {
        continue;
      }
      if (actorRoleCodes == null || !actorRoleCodes.contains(grant.roleCode())) {
        continue;
      }
      candidates.add(grant);
    }

    if (candidates.isEmpty()) {
      return new Decision(
          false, null, "", "", "没有授权配置允许你的角色查看" + fieldLabel(fieldCode) + "的完整值");
    }

    Decision best = null;
    String narrowestReason = "";
    int bestRank = -1;
    for (GrantFact grant : candidates) {
      String reason = scopeReason(grant, actorHasDepartment, subjectHandledByActor);
      if (reason == null) {
        int rank = scopeRank(grant.scopeMode());
        if (rank > bestRank) {
          bestRank = rank;
          best =
              new Decision(
                  true,
                  grant.grantId(),
                  grant.roleCode(),
                  grant.scopeMode(),
                  "依据角色「" + grant.roleCode() + "」的授权（范围：" + scopeLabel(grant.scopeMode()) + "）");
        }
      } else if (narrowestReason.isEmpty()) {
        narrowestReason = reason;
      }
    }

    if (best != null) {
      return best;
    }
    return new Decision(false, null, "", "", narrowestReason);
  }

  /**
   * 范围判定的理由。
   *
   * @return null = 放行；非 null = 不放行的人话理由
   */
  private static String scopeReason(
      GrantFact grant, boolean actorHasDepartment, Boolean subjectHandledByActor) {
    switch (grant.scopeMode()) {
      case SCOPE_ALL:
        return null;
      case SCOPE_DEPARTMENT:
        if (actorHasDepartment) {
          return null;
        }
        return "该授权限部门成员使用，当前成员不属于任何部门";
      case SCOPE_SELF:
        if (subjectHandledByActor == null) {
          return "该授权限本人经手的主体，但这一个主体的经手人**判不了**（上游没有经手人字段）";
        }
        return subjectHandledByActor ? null : "该授权限本人经手的主体，这一个主体不是本人经手";
      default:
        return "授权配置的成员范围不可识别：" + grant.scopeMode();
    }
  }

  /** 范围宽松度排名（ALL &gt; DEPARTMENT &gt; SELF）。 */
  private static int scopeRank(String scopeMode) {
    return switch (scopeMode) {
      case SCOPE_ALL -> 3;
      case SCOPE_DEPARTMENT -> 2;
      case SCOPE_SELF -> 1;
      default -> 0;
    };
  }

  // ================================================================ 摘要

  /**
   * 主体清单的 md5 摘要（导出凭据用）。
   *
   * <p>🔴 **只存摘要，不存 PII 也不存主体清单**：事后能证明「导出了哪一批」，
   * 而本服务**不复制一份 PII 出来** —— 复制一份就是新增一个泄露面。
   */
  public static String digest(List<String> refs) {
    Set<String> unique = new LinkedHashSet<>(refs);
    List<String> sorted = new ArrayList<>(unique);
    sorted.sort(String::compareTo);
    StringBuilder sb = new StringBuilder();
    for (String ref : sorted) {
      sb.append(nz(ref)).append('');
    }
    return md5Hex(sb.toString());
  }

  /** md5 的小写十六进制（32 位）。 */
  public static String md5Hex(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("MD5");
      byte[] bytes = digest.digest(nz(value).getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder(32);
      for (byte b : bytes) {
        sb.append(Character.forDigit((b >> 4) & 0xF, 16));
        sb.append(Character.forDigit(b & 0xF, 16));
      }
      return sb.toString();
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException("MD5 不可用（导出摘要依赖它）", e);
    }
  }

  /** null ⇒ 空串。 */
  public static String nz(String value) {
    return value == null ? "" : value;
  }
}
