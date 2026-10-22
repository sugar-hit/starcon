package com.starcon.channel;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 16.2.1.2 拦截执行的<b>受控值域</b>与<b>纯判定逻辑</b>（无 IO ⇒ 可直接单测）。
 *
 * <p>蓝图：「在关键业务节点执行合规校验并阻断：刊登发布前、发货前、结算提现前；
 * 展示拦截原因与解除方式（提交资质、申请豁免）。输入：合规规则集；业务动作触发的校验请求（商品/订单/店铺）。
 * 输出：校验结论（通过/警告/阻断）+ 原因 + 解除入口；豁免申请与审批。
 * 关系：上游 = 16.2.1.1；下游 = 4.3.1.1 发布、8.3.4.1 发货、12 提现。
 * <b>豁免必须有审批与理由</b>，否则阻断会被随意绕过。」
 *
 * <p>🔴🔴 本类是本模块的<b>唯一值域真相源</b>：DB 层 CHECK（迁移 V313）与本类必须<b>逐字一致</b> ——
 * DB 钉住「值域可审阅」，本类负责「人话 + 怎么判 + 怎么解除」。
 *
 * <p>🔴🔴 <b>结论是三态 + 一个「判不了」</b>：蓝图只点了「通过 / 警告 / 阻断」，
 * 但「判不了」必须与「通过」分开 —— 规则读不到 / 适用版本查不到 / 规则集为空时
 * <b>不能返回 PASS</b>（那等于放行），必须返回 {@code UNDECIDABLE} 并给出原因。
 * 本类的 {@link #aggregate} 就是这条口径的唯一实现。
 */
public final class ComplianceInterceptRules {

  private ComplianceInterceptRules() {}

  // ==================================================================
  // 拦截点（受控值域 —— 蓝图点名的三个关键业务节点）
  // ==================================================================

  /** 🔴 拦截点值域：刊登发布前 / 发货前 / 结算提现前（与 DB 的 CHECK 逐字一致）。 */
  public static final List<String> SCENES = List.of("LISTING_PUBLISH", "SHIPMENT", "WITHDRAWAL");

  /** 拦截点的人话名。 */
  public static final Map<String, String> SCENE_LABELS =
      Map.of(
          "LISTING_PUBLISH", "刊登发布前",
          "SHIPMENT", "发货前",
          "WITHDRAWAL", "结算提现前");

  // ==================================================================
  // 校验对象（受控值域）
  // ==================================================================

  /** 🔴 校验对象值域：商品 / 订单 / 店铺（与 DB 的 CHECK 逐字一致）。 */
  public static final List<String> SUBJECT_TYPES = List.of("PRODUCT", "ORDER", "SHOP");

  /** 校验对象的人话名。 */
  public static final Map<String, String> SUBJECT_TYPE_LABELS =
      Map.of("PRODUCT", "商品", "ORDER", "订单", "SHOP", "店铺");

  // ==================================================================
  // 结论（三态 + 一个「判不了」）
  // ==================================================================

  /** 🔴 结论值域（与 DB 的 CHECK 逐字一致）。 */
  public static final List<String> CONCLUSIONS =
      List.of("PASS", "WARN", "BLOCK", "UNDECIDABLE");

  /** 结论的人话名。 */
  public static final Map<String, String> CONCLUSION_LABELS =
      Map.of(
          "PASS", "通过",
          "WARN", "警告",
          "BLOCK", "阻断",
          "UNDECIDABLE", "判不了");

  /**
   * 🔴 「判不了」的原因码 —— 这些原因码表示<b>没能判出来</b>，而不是判出来「不通过」。
   *
   * <p>它们一律把结论拉到 {@code UNDECIDABLE}，**永远不能**落到 {@code PASS}。
   */
  public static final List<String> UNDECIDABLE_REASON_CODES =
      List.of(
          "RULE_SET_EMPTY",
          "RULE_VERSION_UNRESOLVED",
          "RULE_TERMS_MISSING",
          "REQUIREMENT_UNRESOLVED",
          "RULE_SCOPE_UNRESOLVED",
          "RULE_READ_FAILED");

  // ==================================================================
  // 原因码（具名 —— 与 DB 的 CHECK 逐字一致）
  // ==================================================================

  /** 🔴 原因码值域（与 DB 的 CHECK 逐字一致）。 */
  public static final List<String> REASON_CODES =
      List.of(
          "WORD_TERM_HIT",
          "REQUIREMENT_NOT_SATISFIED",
          "RULE_SET_EMPTY",
          "RULE_VERSION_UNRESOLVED",
          "RULE_TERMS_MISSING",
          "REQUIREMENT_UNRESOLVED",
          "RULE_SCOPE_UNRESOLVED",
          "RULE_READ_FAILED");

  /** 原因码的人话名。 */
  public static final Map<String, String> REASON_CODE_LABELS =
      Map.of(
          "WORD_TERM_HIT", "命中词条",
          "REQUIREMENT_NOT_SATISFIED", "要求未满足",
          "RULE_SET_EMPTY", "规则集为空",
          "RULE_VERSION_UNRESOLVED", "适用版本查不到",
          "RULE_TERMS_MISSING", "规则词库为空",
          "REQUIREMENT_UNRESOLVED", "要求状态缺失",
          "RULE_SCOPE_UNRESOLVED", "适用范围无法确定",
          "RULE_READ_FAILED", "规则读不到");

  // ==================================================================
  // 豁免状态
  // ==================================================================

  /** 🔴 豁免状态值域：PENDING → APPROVED / REJECTED（终态不许回退）。 */
  public static final List<String> EXEMPTION_STATUSES =
      List.of("PENDING", "APPROVED", "REJECTED");

  /** 豁免状态的人话名。 */
  public static final Map<String, String> EXEMPTION_STATUS_LABELS =
      Map.of("PENDING", "待审批", "APPROVED", "已批准", "REJECTED", "已驳回");

  // ==================================================================
  // 判定
  // ==================================================================

  /** 拦截点是否合法。 */
  public static boolean knownScene(String code) {
    return code != null && SCENES.contains(code);
  }

  /** 校验对象是否合法。 */
  public static boolean knownSubjectType(String code) {
    return code != null && SUBJECT_TYPES.contains(code);
  }

  /** 结论是否合法。 */
  public static boolean knownConclusion(String code) {
    return code != null && CONCLUSIONS.contains(code);
  }

  /** 豁免状态是否合法。 */
  public static boolean knownExemptionStatus(String code) {
    return code != null && EXEMPTION_STATUSES.contains(code);
  }

  /** 原因码是否合法。 */
  public static boolean knownReasonCode(String code) {
    return code != null && REASON_CODES.contains(code);
  }

  /** 🔴 这个原因码是不是「判不了」类（不是判出来不通过，而是没判出来）。 */
  public static boolean undecidableReason(String code) {
    return code != null && UNDECIDABLE_REASON_CODES.contains(code);
  }

  /** 🔴 结论是不是「没判出来」。 */
  public static boolean undecidableConclusion(String conclusion) {
    return "UNDECIDABLE".equals(conclusion);
  }

  // ==================================================================
  // 人话
  // ==================================================================

  public static String sceneLabel(String code) {
    return SCENE_LABELS.getOrDefault(code, code);
  }

  public static String subjectTypeLabel(String code) {
    return SUBJECT_TYPE_LABELS.getOrDefault(code, code);
  }

  public static String conclusionLabel(String code) {
    return CONCLUSION_LABELS.getOrDefault(code, code);
  }

  public static String reasonCodeLabel(String code) {
    return REASON_CODE_LABELS.getOrDefault(code, code);
  }

  public static String exemptionStatusLabel(String code) {
    return EXEMPTION_STATUS_LABELS.getOrDefault(code, code);
  }

  // ==================================================================
  // 🔴 词条命中（与 4.2.3.2 同一口径：大小写不敏感 + 拉丁词按词边界匹配）
  // ==================================================================

  /**
   * 🔴 词条是否命中。
   *
   * <p><b>拉丁词按词边界匹配</b>：{@code best} 不命中 {@code bestseller}（那是另一个词，
   * 拿它当违规是把判定做糊了）；<b>含非 ASCII 字母的词按包含匹配</b>（中文没有词边界，
   * 「违禁词」出现在「这是违禁词商品」里就该命中）。
   *
   * @param term 规则词条（原文照抄，不折叠大小写）
   * @param text 被校验的文本
   * @return 是否命中
   */
  public static boolean matchesTerm(String term, String text) {
    if (term == null || term.isBlank() || text == null || text.isEmpty()) {
      return false;
    }
    String needle = term.trim();
    String haystack = text.toLowerCase(Locale.ROOT);
    String lowerNeedle = needle.toLowerCase(Locale.ROOT);
    if (hasNonAsciiLetter(needle)) {
      return haystack.contains(lowerNeedle);
    }
    if (!lowerNeedle.matches("[\\p{L}\\p{N} ]+")) {
      // 含符号的词条（例如 "c++"）退回包含匹配 —— 词边界对它们没有意义。
      return haystack.contains(lowerNeedle);
    }
    return Pattern.compile(
            "(?<![\\p{L}\\p{N}])" + Pattern.quote(needle) + "(?![\\p{L}\\p{N}])",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)
        .matcher(text)
        .find();
  }

  /** 词条里是否含非 ASCII 字母（含中日韩等）。 */
  private static boolean hasNonAsciiLetter(String value) {
    for (int i = 0; i < value.length(); i++) {
      if (Character.isLetter(value.charAt(i)) && value.charAt(i) > 127) {
        return true;
      }
    }
    return false;
  }

  // ==================================================================
  // 🔴 解除方式（蓝图的「展示拦截原因与**解除方式**」）
  // ==================================================================

  /**
   * 🔴 按规则类型给出**解除方式**（人能读的一句话）。
   *
   * <p>蓝图：「展示拦截原因与解除方式（<b>提交资质</b>、申请豁免）」——
   * 只说「被拦了」不说「怎么解开」，运营就只能来问人。
   */
  public static String remedyOf(String ruleType) {
    if (ruleType == null) {
      return "按规则要求整改后重新校验";
    }
    return switch (ruleType) {
      case "PROHIBITED_WORD" -> "改掉命中的违禁词后重新校验";
      case "SUPERLATIVE_WORD" -> "改掉命中的极限词（换成可验证的表述）后重新校验";
      case "INFRINGEMENT_WORD" -> "移除侵权词或改用自有品牌表述后重新校验";
      case "CERTIFICATION_REQUIRED" -> "提交资质（上传对应认证并保证在有效期内）后重新校验";
      case "RESTRICTED_SALE" -> "该商品在此平台禁售：请下架或改投允许销售的平台";
      case "LABEL_REQUIREMENT" -> "按规则要求补齐标签后重新校验";
      case "MATERIAL_REQUIREMENT" -> "修正材质描述或提交材质证明后重新校验";
      default -> "按规则要求整改后重新校验";
    };
  }

  /** 🔴 统一的「申请豁免」解除方式（豁免必须走审批 + 理由，不是自己点一下就行）。 */
  public static final String EXEMPTION_REMEDY =
      "申请豁免：填理由提交后需**他人审批**（申请人不能审批自己），"
          + "批准后按有效期放行 —— 有效期过了就重新失效";

  // ==================================================================
  // 🔴 结论聚合（三态 + 一个「判不了」的唯一实现）
  // ==================================================================

  /**
   * 🔴 由原因聚合出结论。
   *
   * <p>口径（顺序不可换）：
   *
   * <ol>
   *   <li>有一条**未豁免**的阻断级命中 ⇒ {@code BLOCK}（被阻断）。</li>
   *   <li>否则有一条**未豁免**的警告级命中 ⇒ {@code WARN}（放行但要提醒）。</li>
   *   <li>否则有一条「判不了」类原因 ⇒ {@code UNDECIDABLE}
   *       （<b>不是</b> PASS —— 判不出来不等于没毛病）。</li>
   *   <li>否则 ⇒ {@code PASS}。</li>
   * </ol>
   *
   * <p><b>因豁免放行的原因不参与聚合</b> —— 它已经被另一条已批准的豁免单放行了，
   * 再算一次等于豁免没用。
   *
   * @param blocking 未豁免的阻断级命中数
   * @param warning 未豁免的警告级命中数
   * @param undecidable 「判不了」类原因数
   * @return 结论
   */
  public static String aggregate(int blocking, int warning, int undecidable) {
    if (blocking > 0) {
      return "BLOCK";
    }
    if (warning > 0) {
      return "WARN";
    }
    if (undecidable > 0) {
      return "UNDECIDABLE";
    }
    return "PASS";
  }

  /**
   * 🔴 结论对应的「能不能放行」。
   *
   * <p>判不了（{@code UNDECIDABLE}）**不能放行** —— 它与「通过」分开正是为了这件事。
   */
  public static boolean releaseGranted(String conclusion) {
    return "PASS".equals(conclusion) || "WARN".equals(conclusion);
  }

  /** 🔴 结论对应的「是不是被阻断」。 */
  public static boolean blocked(String conclusion) {
    return "BLOCK".equals(conclusion);
  }
}
