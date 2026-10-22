package com.starcon.channel;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 16.4.1.2 检测与预警的<b>受控值域</b>与<b>比对口径</b>（纯逻辑，无 IO ⇒ 可直接单测）。
 *
 * <p>蓝图：「对商品内容做侵权检测：标题/描述/图片/品牌字段比对侵权库，输出风险等级与命中项；
 * 接入外部商标/专利查询；已上架商品的定期巡检。输入：商品内容与图片；侵权库；外部查询结果。
 * 输出：检测报告 + 预警 + 整改建议。关系：上游 = 16.4.1.1、4.2.1.1；下游 = 16.2.1.2 拦截、16.4.2 授权核验。
 * <b>图片侵权检测难度高于文本</b>，误判与漏判都要留人工复核环节。」
 *
 * <p>🔴🔴 本类是本模块的<b>唯一值域真相源</b>：DB 层 CHECK（迁移 V317）与本类必须<b>逐字一致</b>。
 *
 * <p>🔴🔴 本类钉死四条结构性口径：
 *
 * <ol>
 *   <li><b>风险等级沿用上游口径</b>（{@code HIGH} / {@code MEDIUM} / {@code LOW}），
 *       另加 {@code NONE}（一条命中都没有）。
 *       <b>不用 0..100 的裸数字</b> —— 裸数字没法解释成「高风险还是中风险」，
 *       也和上游 16.4.1.1 的 {@code ip_risk_entry.risk_level} 对不上。</li>
 *   <li><b>图片命中一律进人工复核</b>：{@link #needsManualReview} 对 {@code IMAGE}
 *       恒返回 {@code true}，<b>没有开关</b>。蓝图原话是「图片侵权检测难度高于文本，
 *       误判与漏判都要留人工复核环节」—— 图片比对本来就是**标识文本比对**（不是图像指纹），
 *       把它当结论直接定性就是误判。</li>
 *   <li><b>结论三态</b>：{@code CLEAR}（无命中）/ {@code REVIEW_REQUIRED}（有命中但**全部**
 *       还要人工复核）/ {@code ACTION_REQUIRED}（存在<b>可判定</b>的命中）。
 *       「判不了」与「判了没问题」是<b>两件事</b>，不能都写成 CLEAR。</li>
 *   <li><b>本模块不拦截</b>：{@link #recommendedAction} 只是<b>建议</b>，
 *       真正的拦截归下游 16.2.1.2，授权核验归 16.4.2。</li>
 * </ol>
 */
public final class IpDetectionRules {

  private IpDetectionRules() {}

  // ==================================================================
  // 风险等级（沿用上游 16.4.1.1 的口径）
  // ==================================================================

  /** 🔴 风险等级值域：沿用上游 HIGH / MEDIUM / LOW，**加** NONE（无命中）。 */
  public static final List<String> RISK_LEVELS = List.of("HIGH", "MEDIUM", "LOW", "NONE");

  /** 风险等级的人话名（NONE 单独说明，它是「没有命中」而不是「低风险」）。 */
  public static final Map<String, String> RISK_LEVEL_LABELS =
      Map.of(
          "HIGH", "高风险",
          "MEDIUM", "中风险",
          "LOW", "低风险",
          "NONE", "无命中");

  /**
   * 🔴 风险等级的<b>排序权重</b>（高 → 中 → 低）。
   *
   * <p>{@code NONE} 的权重是 0 —— 它比 LOW 还轻，因为它是「没命中」而不是「低风险命中」。
   */
  public static int riskWeight(String code) {
    return switch (code == null ? "" : code) {
      case "HIGH" -> 3;
      case "MEDIUM" -> 2;
      case "LOW" -> 1;
      default -> 0;
    };
  }

  /** 取两个风险等级里更严重的一个。 */
  public static String maxRisk(String left, String right) {
    return riskWeight(right) > riskWeight(left) ? right : left;
  }

  // ==================================================================
  // 结论 / 建议动作
  // ==================================================================

  /** 🔴 结论三态（DB 层 CHECK 钉死）。 */
  public static final List<String> CONCLUSIONS = List.of("CLEAR", "REVIEW_REQUIRED", "ACTION_REQUIRED");

  /** 结论的人话名。 */
  public static final Map<String, String> CONCLUSION_LABELS =
      Map.of(
          "CLEAR", "无命中",
          "REVIEW_REQUIRED", "待人工复核",
          "ACTION_REQUIRED", "需整改");

  /** 🔴 建议的下游动作（**只是建议**，拦截归 16.2.1.2，授权核验归 16.4.2）。 */
  public static final List<String> RECOMMENDED_ACTIONS =
      List.of("NONE", "NOTIFY", "REMEDIATE", "INTERCEPT", "VERIFY_AUTHORIZATION");

  /** 建议动作的人话名。 */
  public static final Map<String, String> RECOMMENDED_ACTION_LABELS =
      Map.of(
          "NONE", "无需动作",
          "NOTIFY", "通知运营",
          "REMEDIATE", "整改后重检",
          "INTERCEPT", "建议下游拦截",
          "VERIFY_AUTHORIZATION", "建议核验品牌授权");

  // ==================================================================
  // 检测对象 / 命中字段
  // ==================================================================

  /** 🔴 检测对象类型。 */
  public static final List<String> SUBJECT_TYPES = List.of("LISTING", "DRAFT", "MANUAL");

  /** 检测对象类型的人话名。 */
  public static final Map<String, String> SUBJECT_TYPE_LABELS =
      Map.of(
          "LISTING", "已上架商品",
          "DRAFT", "刊登前草稿",
          "MANUAL", "手工提交内容");

  /** 🔴 命中字段（受控值域）。 */
  public static final List<String> FIELDS =
      List.of("TITLE", "DESCRIPTION", "BULLET", "IMAGE", "BRAND");

  /** 命中字段的人话名。 */
  public static final Map<String, String> FIELD_LABELS =
      Map.of(
          "TITLE", "标题",
          "DESCRIPTION", "描述",
          "BULLET", "卖点",
          "IMAGE", "图片",
          "BRAND", "品牌");

  /** 🔴 命中特征分类（受控值域）。 */
  public static final List<String> FEATURE_KINDS =
      List.of("TERM", "BRAND_NAME", "PATTERN", "EXTERNAL_TERM");

  /** 命中特征分类的人话名。 */
  public static final Map<String, String> FEATURE_KIND_LABELS =
      Map.of(
          "TERM", "侵权词 / 短语",
          "BRAND_NAME", "品牌名",
          "PATTERN", "标识符特征",
          "EXTERNAL_TERM", "外部查询关键词");

  // ==================================================================
  // 预警 / 整改建议 / 复核
  // ==================================================================

  /** 预警等级值域（沿用 HIGH / MEDIUM / LOW）。 */
  public static final List<String> ALERT_LEVELS = List.of("HIGH", "MEDIUM", "LOW");

  /** 预警等级的人话名。 */
  public static final Map<String, String> ALERT_LEVEL_LABELS =
      Map.of("HIGH", "高风险", "MEDIUM", "中风险", "LOW", "低风险");

  /** 🔴 预警类型（受控值域）。 */
  public static final List<String> ALERT_TYPES =
      List.of("INFRINGEMENT_HIT", "PENDING_REVIEW", "EXTERNAL_MATCH", "INSPECTION_FOUND");

  /** 预警类型的人话名。 */
  public static final Map<String, String> ALERT_TYPE_LABELS =
      Map.of(
          "INFRINGEMENT_HIT", "检出侵权命中",
          "PENDING_REVIEW", "有待复核的命中",
          "EXTERNAL_MATCH", "命中外部查询结果",
          "INSPECTION_FOUND", "巡检发现命中");

  /** 预警状态值域。 */
  public static final List<String> ALERT_STATUSES = List.of("OPEN", "ACKNOWLEDGED", "RESOLVED");

  /** 预警状态的人话名。 */
  public static final Map<String, String> ALERT_STATUS_LABELS =
      Map.of("OPEN", "待处理", "ACKNOWLEDGED", "已确认", "RESOLVED", "已处置");

  /** 🔴 整改动作类型（受控值域）。 */
  public static final List<String> REMEDIATION_ACTIONS =
      List.of(
          "REMOVE_TERM",
          "REWRITE_TITLE",
          "REPLACE_IMAGE",
          "VERIFY_BRAND_AUTH",
          "APPLY_EXEMPTION",
          "CONTACT_RIGHTS_HOLDER");

  /** 整改动作类型的人话名。 */
  public static final Map<String, String> REMEDIATION_ACTION_LABELS =
      Map.of(
          "REMOVE_TERM", "删除侵权词 / 短语",
          "REWRITE_TITLE", "改写标题",
          "REPLACE_IMAGE", "替换图片",
          "VERIFY_BRAND_AUTH", "核验品牌授权",
          "APPLY_EXEMPTION", "申请豁免",
          "CONTACT_RIGHTS_HOLDER", "联系权利人协商");

  /** 整改建议状态值域。 */
  public static final List<String> REMEDIATION_STATUSES =
      List.of("OPEN", "IN_PROGRESS", "DONE", "DISMISSED");

  /** 整改建议状态的人话名。 */
  public static final Map<String, String> REMEDIATION_STATUS_LABELS =
      Map.of(
          "OPEN", "待处理",
          "IN_PROGRESS", "处理中",
          "DONE", "已办完",
          "DISMISSED", "已放弃");

  /** 🔴 复核原因（受控值域）。 */
  public static final List<String> REVIEW_REASONS = List.of("IMAGE_MATCH", "TEXT_MISMATCH", "APPEAL");

  /** 复核原因的人话名。 */
  public static final Map<String, String> REVIEW_REASON_LABELS =
      Map.of(
          "IMAGE_MATCH", "图片命中复核",
          "TEXT_MISMATCH", "文本误判申诉",
          "APPEAL", "权利方申诉");

  /** 复核状态值域。 */
  public static final List<String> REVIEW_STATUSES = List.of("PENDING", "CONFIRMED", "DISMISSED");

  /** 复核状态的人话名。 */
  public static final Map<String, String> REVIEW_STATUS_LABELS =
      Map.of(
          "PENDING", "待复核",
          "CONFIRMED", "确认侵权",
          "DISMISSED", "判定误报");

  // ==================================================================
  // 外部查询 / 巡检
  // ==================================================================

  /** 外部查询状态值域。 */
  public static final List<String> QUERY_STATUSES =
      List.of("REGISTERED", "RETURNED", "NO_RESULT", "FAILED");

  /** 外部查询状态的人话名。 */
  public static final Map<String, String> QUERY_STATUS_LABELS =
      Map.of(
          "REGISTERED", "已登记",
          "RETURNED", "已查回结果",
          "NO_RESULT", "查无结果",
          "FAILED", "查询失败");

  /** 巡检范围值域。 */
  public static final List<String> PLAN_SCOPES = List.of("ALL", "SHOP");

  /** 巡检范围的人话名。 */
  public static final Map<String, String> PLAN_SCOPE_LABELS = Map.of("ALL", "全部店铺", "SHOP", "指定店铺");

  /**
   * 🔴 巡检运行状态值域 —— <b>只有终态</b>。
   *
   * <p>巡检运行是 **append-only** 的回执：跑完才落一行（跑的过程中不落行）⇒
   * 不需要「运行中」这一态，也因此这张表**只给 SELECT, INSERT**。
   */
  public static final List<String> RUN_STATUSES = List.of("COMPLETED", "PARTIAL", "FAILED");

  /** 巡检运行状态的人话名。 */
  public static final Map<String, String> RUN_STATUS_LABELS =
      Map.of(
          "COMPLETED", "已完成",
          "PARTIAL", "部分完成",
          "FAILED", "失败");

  /** 权利类型（沿用上游 16.4.1.1 的四种口径）。 */
  public static final List<String> RIGHT_TYPES =
      List.of("TRADEMARK", "PATENT", "COPYRIGHT", "IMAGE_RIGHT");

  /** 权利类型的人话名。 */
  public static final Map<String, String> RIGHT_TYPE_LABELS =
      Map.of(
          "TRADEMARK", "品牌商标",
          "PATENT", "专利",
          "COPYRIGHT", "版权",
          "IMAGE_RIGHT", "肖像权");

  // ==================================================================
  // 判定
  // ==================================================================

  public static boolean knownRiskLevel(String code) {
    return code != null && RISK_LEVELS.contains(code);
  }

  public static boolean knownConclusion(String code) {
    return code != null && CONCLUSIONS.contains(code);
  }

  public static boolean knownField(String code) {
    return code != null && FIELDS.contains(code);
  }

  public static boolean knownSubjectType(String code) {
    return code != null && SUBJECT_TYPES.contains(code);
  }

  public static boolean knownAlertStatus(String code) {
    return code != null && ALERT_STATUSES.contains(code);
  }

  public static boolean knownReviewStatus(String code) {
    return code != null && REVIEW_STATUSES.contains(code);
  }

  public static boolean knownRemediationStatus(String code) {
    return code != null && REMEDIATION_STATUSES.contains(code);
  }

  public static boolean knownRemediationAction(String code) {
    return code != null && REMEDIATION_ACTIONS.contains(code);
  }

  public static boolean knownQueryStatus(String code) {
    return code != null && QUERY_STATUSES.contains(code);
  }

  // ==================================================================
  // 人话
  // ==================================================================

  public static String riskLevelLabel(String code) {
    return RISK_LEVEL_LABELS.getOrDefault(code, code);
  }

  public static String conclusionLabel(String code) {
    return CONCLUSION_LABELS.getOrDefault(code, code);
  }

  public static String recommendedActionLabel(String code) {
    return RECOMMENDED_ACTION_LABELS.getOrDefault(code, code);
  }

  public static String subjectTypeLabel(String code) {
    return SUBJECT_TYPE_LABELS.getOrDefault(code, code);
  }

  public static String fieldLabel(String code) {
    return FIELD_LABELS.getOrDefault(code, code);
  }

  public static String featureKindLabel(String code) {
    return FEATURE_KIND_LABELS.getOrDefault(code, code);
  }

  public static String alertLevelLabel(String code) {
    return ALERT_LEVEL_LABELS.getOrDefault(code, code);
  }

  public static String alertTypeLabel(String code) {
    return ALERT_TYPE_LABELS.getOrDefault(code, code);
  }

  public static String alertStatusLabel(String code) {
    return ALERT_STATUS_LABELS.getOrDefault(code, code);
  }

  public static String remediationActionLabel(String code) {
    return REMEDIATION_ACTION_LABELS.getOrDefault(code, code);
  }

  public static String remediationStatusLabel(String code) {
    return REMEDIATION_STATUS_LABELS.getOrDefault(code, code);
  }

  public static String reviewReasonLabel(String code) {
    return REVIEW_REASON_LABELS.getOrDefault(code, code);
  }

  public static String reviewStatusLabel(String code) {
    return REVIEW_STATUS_LABELS.getOrDefault(code, code);
  }

  public static String queryStatusLabel(String code) {
    return QUERY_STATUS_LABELS.getOrDefault(code, code);
  }

  public static String planScopeLabel(String code) {
    return PLAN_SCOPE_LABELS.getOrDefault(code, code);
  }

  public static String runStatusLabel(String code) {
    return RUN_STATUS_LABELS.getOrDefault(code, code);
  }

  public static String rightTypeLabel(String code) {
    return RIGHT_TYPE_LABELS.getOrDefault(code, code);
  }

  // ==================================================================
  // 🔴 比对口径（纯逻辑，可直接单测）
  // ==================================================================

  /**
   * 一条<b>可被比对的特征</b>（来自本地侵权库或外部查询结果）。
   *
   * @param sourceType 来源：{@code ENTRY}（16.4.1.1 条目）/ {@code EXTERNAL}（外部查询命中）
   * @param sourceId 来源 id（条目 id / 外部命中 id）
   * @param code 编码快照（条目编码 / 外部查询编码）
   * @param rightType 权利类型
   * @param riskLevel 风险等级
   * @param featureKind 特征分类（TERM / BRAND_NAME / PATTERN / EXTERNAL_TERM）
   * @param matchMode 比对方式：{@code KEYWORD}（词边界）/ {@code PHRASE}（包含）/ {@code CONTAINS}（包含）
   * @param text 特征原文
   */
  public record Feature(
      String sourceType,
      String sourceId,
      String code,
      String rightType,
      String riskLevel,
      String featureKind,
      String matchMode,
      String text) {}

  /**
   * 一段<b>被比对的内容</b>。
   *
   * @param field 字段（TITLE / DESCRIPTION / BULLET / IMAGE / BRAND）
   * @param text 该字段的原文
   */
  public record FieldText(String field, String text) {}

  /**
   * 一条<b>命中</b>。
   *
   * @param feature 命中的特征
   * @param field 命中在哪个字段
   * @param matchedText 命中处原文片段（人可看 ⇒ 可复核）
   */
  public record Match(Feature feature, String field, String matchedText) {}

  /**
   * 🔴 图片命中<b>一律</b>要人工复核。
   *
   * <p>蓝图：「<b>图片侵权检测难度高于文本</b>，误判与漏判都要留人工复核环节。」
   *
   * <p>本模块的图片比对本质上是<b>标识文本比对</b>（文件名 / alt 文本 / URL 里的品牌名），
   * <b>不是</b>图像指纹比对 —— 没有图像引擎就没有真正的以图搜图。
   * 把它当结论直接定性就是误判，所以它<b>永远</b>进复核队列。
   */
  public static boolean needsManualReview(String field) {
    return "IMAGE".equals(field);
  }

  /**
   * 🔴 特征在哪些字段上参与比对。
   *
   * <ul>
   *   <li>{@code BRAND_NAME}（条目的品牌名）⇒ <b>只</b>比对品牌字段 ——
   *       品牌名对品牌，「标题里出现了品牌名」那是关键词的事（{@code TERM}），不是品牌名的事。</li>
   *   <li>{@code TERM} / {@code PATTERN} / {@code EXTERNAL_TERM} ⇒ 文本四个字段都参与。</li>
   *   <li>字段是 {@code IMAGE} ⇒ <b>所有</b>特征都参与（图片的标识文本里常带品牌名），
   *       但比对方式是包含（{@code CONTAINS}），且命中<b>一律</b>要人工复核。</li>
   * </ul>
   */
  private static boolean appliesTo(String featureKind, String field) {
    if ("IMAGE".equals(field)) {
      return true;
    }
    if ("BRAND_NAME".equals(featureKind)) {
      return "BRAND".equals(field);
    }
    return true;
  }

  /**
   * 🔴 在某个字段上，该特征用哪种比对方式。
   *
   * <p>图片字段一律用包含：文件名「nike-air-max.jpg」里的品牌名没有词边界可言。
   */
  private static String modeFor(String feature, String mode, String field) {
    if ("IMAGE".equals(field)) {
      return "CONTAINS";
    }
    return mode == null || mode.isBlank() ? "PHRASE" : mode;
  }

  /**
   * 🔴 主比对入口：拿一组特征去比一组内容，返回全部命中。
   *
   * <p>口径：
   *
   * <ul>
   *   <li>{@code KEYWORD} —— <b>词边界</b>匹配（大小写不敏感）：
   *       「air」不该命中「hair」。特征首尾若不是字母数字，则那一侧不查边界
   *       （否则 {@code C++} 这种特征永远匹配不上）。</li>
   *   <li>{@code PHRASE} / {@code CONTAINS} —— 包含匹配（大小写不敏感）。</li>
   *   <li>同一个特征在同一个字段上<b>只留一条</b>命中（命中三次没有额外信息量）。</li>
   * </ul>
   *
   * @param features 待比对特征
   * @param fields 被比对内容
   * @return 命中清单（顺序稳定：特征顺序 × 字段顺序）
   */
  public static List<Match> matchFeatures(List<Feature> features, List<FieldText> fields) {
    List<Match> matches = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    for (Feature feature : features) {
      if (feature == null || feature.text() == null || feature.text().isBlank()) {
        continue;
      }
      for (FieldText field : fields) {
        if (field == null || field.text() == null || field.text().isBlank()) {
          continue;
        }
        if (!appliesTo(feature.featureKind(), field.field())) {
          continue;
        }
        String mode = modeFor(feature.featureKind(), feature.matchMode(), field.field());
        int at = indexOf(field.text(), feature.text(), mode);
        if (at < 0) {
          continue;
        }
        String key = feature.sourceId() + "|" + feature.featureKind() + "|" + feature.text() + "|" + field.field();
        if (!seen.add(key)) {
          continue;
        }
        matches.add(new Match(feature, field.field(), snippet(field.text(), at, feature.text().length())));
      }
    }
    return matches;
  }

  /**
   * 🔴 在 {@code haystack} 里找 {@code needle}（大小写不敏感）。
   *
   * @return 命中位置（-1 ⇒ 没命中）
   */
  public static int indexOf(String haystack, String needle, String mode) {
    if (haystack == null || needle == null) {
      return -1;
    }
    String h = haystack.toLowerCase(Locale.ROOT);
    String n = needle.toLowerCase(Locale.ROOT);
    if (n.isEmpty()) {
      return -1;
    }
    boolean boundary = "KEYWORD".equals(mode);
    int from = 0;
    while (true) {
      int at = h.indexOf(n, from);
      if (at < 0) {
        return -1;
      }
      if (!boundary || boundaryOk(h, n, at)) {
        return at;
      }
      from = at + 1;
    }
  }

  /** 词边界检查：特征首尾若是字母数字，相邻字符就不能也是字母数字。 */
  private static boolean boundaryOk(String haystack, String needle, int at) {
    boolean firstWordChar = Character.isLetterOrDigit(needle.charAt(0));
    boolean lastWordChar = Character.isLetterOrDigit(needle.charAt(needle.length() - 1));
    if (firstWordChar && at > 0 && Character.isLetterOrDigit(haystack.charAt(at - 1))) {
      return false;
    }
    int end = at + needle.length();
    if (lastWordChar && end < haystack.length() && Character.isLetterOrDigit(haystack.charAt(end))) {
      return false;
    }
    return true;
  }

  /**
   * 命中处的原文片段（前后各留一点上下文，让人看得懂命中在哪）。
   *
   * @param haystack 原文
   * @param at 命中位置
   * @param length 命中长度
   */
  public static String snippet(String haystack, int at, int length) {
    if (haystack == null || haystack.isEmpty() || at < 0) {
      return "";
    }
    int context = 30;
    int start = Math.max(0, at - context);
    int end = Math.min(haystack.length(), at + length + context);
    String out = haystack.substring(start, end).replaceAll("\\s+", " ").trim();
    if (start > 0) {
      out = "…" + out;
    }
    if (end < haystack.length()) {
      out = out + "…";
    }
    return out.length() > 1000 ? out.substring(0, 1000) : out;
  }

  // ==================================================================
  // 🔴 结论口径
  // ==================================================================

  /**
   * 一条命中的<b>定性</b>摘要（纯逻辑，可直接单测）。
   *
   * @param riskLevel 该命中的风险等级
   * @param field 命中在哪个字段
   * @param pending 是否还在等人工复核
   */
  public record HitSummary(String riskLevel, String field, boolean pending) {}

  /**
   * 🔴 由命中清单算出结论。
   *
   * <ul>
   *   <li>一条命中都没有 ⇒ {@code CLEAR}（风险等级 {@code NONE}）。</li>
   *   <li>有命中，但**全部**还在等人工复核 ⇒ {@code REVIEW_REQUIRED}
   *       （典型：图片命中 —— <b>判不了</b>不等于「没问题」）。</li>
   *   <li>存在<b>可判定</b>的命中 ⇒ {@code ACTION_REQUIRED}。</li>
   * </ul>
   */
  public static String conclusionOf(List<HitSummary> hits) {
    if (hits == null || hits.isEmpty()) {
      return "CLEAR";
    }
    for (HitSummary hit : hits) {
      if (hit != null && !hit.pending()) {
        return "ACTION_REQUIRED";
      }
    }
    return "REVIEW_REQUIRED";
  }

  /**
   * 🔴 由命中清单算出建议的下游动作（**只是建议**）。
   *
   * <ul>
   *   <li>无命中 ⇒ {@code NONE}。</li>
   *   <li>命中了品牌字段 ⇒ {@code VERIFY_AUTHORIZATION}（下游 16.4.2 授权核验）。</li>
   *   <li>存在可判定的高风险命中 ⇒ {@code INTERCEPT}（**建议**下游 16.2.1.2 拦截）。</li>
   *   <li>其余 ⇒ {@code REMEDIATE}（整改后重检）；全在等复核 ⇒ {@code NOTIFY}。</li>
   * </ul>
   */
  public static String recommendedActionOf(List<HitSummary> hits) {
    if (hits == null || hits.isEmpty()) {
      return "NONE";
    }
    for (HitSummary hit : hits) {
      if (hit != null && "BRAND".equals(hit.field())) {
        return "VERIFY_AUTHORIZATION";
      }
    }
    boolean anyDecidable = false;
    for (HitSummary hit : hits) {
      if (hit == null || hit.pending()) {
        continue;
      }
      anyDecidable = true;
      if ("HIGH".equals(hit.riskLevel())) {
        return "INTERCEPT";
      }
    }
    return anyDecidable ? "REMEDIATE" : "NOTIFY";
  }

  // ==================================================================
  // 🔴 整改建议（由命中推出）
  // ==================================================================

  /**
   * 🔴 由一条命中推出整改动作类型。
   *
   * <ul>
   *   <li>图片命中 ⇒ {@code REPLACE_IMAGE}（换图，不是删词）。</li>
   *   <li>品牌命中 ⇒ {@code VERIFY_BRAND_AUTH}（去核验授权，不是删词）。</li>
   *   <li>标题命中 ⇒ {@code REWRITE_TITLE}；其余文本命中 ⇒ {@code REMOVE_TERM}。</li>
   *   <li>外部查询命中 ⇒ {@code CONTACT_RIGHTS_HOLDER}（那是外部权利，不是我们的词库）。</li>
   * </ul>
   */
  public static String remediationActionOf(String field, String featureKind) {
    if ("IMAGE".equals(field)) {
      return "REPLACE_IMAGE";
    }
    if ("BRAND".equals(field)) {
      return "VERIFY_BRAND_AUTH";
    }
    if ("EXTERNAL_TERM".equals(featureKind)) {
      return "CONTACT_RIGHTS_HOLDER";
    }
    if ("TITLE".equals(field)) {
      return "REWRITE_TITLE";
    }
    return "REMOVE_TERM";
  }

  /**
   * 🔴 由一条命中推出整改建议的**人话正文**。
   *
   * @param field 命中字段
   * @param featureKind 命中特征分类
   * @param featureText 命中特征原文
   * @param entryCode 命中条目的编码快照
   */
  public static String remediationSuggestionOf(
      String field, String featureKind, String featureText, String entryCode) {
    String where = fieldLabel(field);
    String what = featureText == null ? "" : featureText.trim();
    String who = entryCode == null || entryCode.isBlank() ? "" : "（条目 " + entryCode + "）";
    return switch (remediationActionOf(field, featureKind)) {
      case "REPLACE_IMAGE" ->
          "图片里出现了疑似侵权标识「" + what + "」" + who
              + "。🔴 图片命中**必须先过人工复核**（图片比文本更容易误判），"
              + "复核确认后请替换该图片；未确认前不要直接下架。";
      case "VERIFY_BRAND_AUTH" ->
          "品牌字段命中「" + what + "」" + who
              + "。请走 16.4.2 品牌授权核验：拿到授权就补登记，拿不到就换品牌或下架。";
      case "REWRITE_TITLE" ->
          "标题里出现了疑似侵权词「" + what + "」" + who
              + "。建议改写标题后再刊登（保留卖点，替换掉侵权标识）。";
      case "CONTACT_RIGHTS_HOLDER" ->
          "内容命中了外部商标 / 专利查询返回的「" + what + "」" + who
              + "。建议联系权利人确认授权范围；确认没有授权再改内容。";
      default ->
          where + "里出现了疑似侵权词「" + what + "」" + who + "。建议删除或替换该表述。";
    };
  }

  /**
   * 🔴 由一条命中推出预警类型。
   *
   * @param field 命中字段
   * @param featureKind 命中特征分类
   * @param pending 是否还在等人工复核
   * @param fromInspection 是否由巡检产生
   */
  public static String alertTypeOf(
      String field, String featureKind, boolean pending, boolean fromInspection) {
    if (pending) {
      return "PENDING_REVIEW";
    }
    if ("EXTERNAL_TERM".equals(featureKind)) {
      return "EXTERNAL_MATCH";
    }
    if (fromInspection) {
      return "INSPECTION_FOUND";
    }
    return "INFRINGEMENT_HIT";
  }

  /**
   * 🔴 图片的<b>标识文本</b>：文件名 / alt 文本 / URL 里能拿来比对的部分。
   *
   * <p>图片本身是二进制，比不了；能比的是它的**标识文本**。
   * 这个口径必须写在页面上 —— 否则界面会让人以为系统在做真正的以图搜图。
   *
   * @param url 图片地址
   * @param altText 替代文本（可空）
   * @param originalFilename 原始文件名（可空）
   */
  public static String imageDescriptor(String url, String altText, String originalFilename) {
    StringBuilder out = new StringBuilder();
    appendPart(out, originalFilename);
    appendPart(out, altText);
    appendPart(out, url);
    return out.toString().trim();
  }

  private static void appendPart(StringBuilder out, String part) {
    if (part == null || part.isBlank()) {
      return;
    }
    if (!out.isEmpty()) {
      out.append(' ');
    }
    out.append(part.trim());
  }

  /** 去空白（null ⇒ 空串）。 */
  public static String text(String value) {
    return value == null ? "" : value.trim();
  }
}
