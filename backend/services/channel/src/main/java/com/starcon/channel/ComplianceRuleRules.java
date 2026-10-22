package com.starcon.channel;

import java.util.List;
import java.util.Map;

/**
 * 16.2.1.1 规则库的<b>受控值域</b>（纯逻辑，无 IO ⇒ 可直接单测）。
 *
 * <p>蓝图：「管理合规规则库：规则类型（违禁词、极限词、侵权词、认证要求、禁限售、标签要求、材质要求）、
 * 严重度（阻断/警告/提示）、适用平台与站点、生效时间与版本。输入：规则定义与词库上传；版本更新；适用范围配置。
 * 输出：规则集 + 生效版本。关系：上游 = 平台政策同步 + 人工维护；下游 = 16.2.1.2 执行、4.2.3.2 预检。
 * <b>规则版本化是关键</b>：政策变化时历史商品的判定结果要可解释，不能因为规则库更新就说过去的商品当时违规。」
 *
 * <p>🔴🔴 本类是本模块的<b>唯一值域真相源</b>：DB 层 CHECK（迁移 V311）与本类必须<b>逐字一致</b> ——
 * DB 钉住「值域可审阅」（应用层 enum 改错了也只是运行时报错，DB 才是硬约束），
 * 本类负责「人话 + 哪些类型带词条」；应用层改错会被 DB 拒 ⇒ 两边不可能长期漂移。
 *
 * <p>🔴🔴 <b>哪些属性落在规则、哪些落在版本</b>（本模块最要紧的一条口径）：
 *
 * <ul>
 *   <li><b>规则身份</b>（{@code rule_code} / {@code rule_type} / {@code status}）落在
 *       {@code compliance_rule}：一条规则**永远是同一类规则**，改类型 = 另一条规则。</li>
 *   <li><b>判定内容</b>（{@code severity} / 词条 / 适用范围 / 生效时间窗）落在
 *       {@code compliance_rule_version}：政策变了就<b>新增一个版本</b>，
 *       旧版本原样留着 ⇒ 「当时生效的是哪一版、那一版有多严重、按哪些词判」永远答得出来。</li>
 * </ul>
 */
public final class ComplianceRuleRules {

  private ComplianceRuleRules() {}

  // ==================================================================
  // 规则类型（受控值域 —— 蓝图点名的七种）
  // ==================================================================

  /** 🔴 规则类型值域（与 DB 的 CHECK 逐字一致）。 */
  public static final List<String> RULE_TYPES =
      List.of(
          "PROHIBITED_WORD",
          "SUPERLATIVE_WORD",
          "INFRINGEMENT_WORD",
          "CERTIFICATION_REQUIRED",
          "RESTRICTED_SALE",
          "LABEL_REQUIREMENT",
          "MATERIAL_REQUIREMENT");

  /** 规则类型的人话名。 */
  public static final Map<String, String> RULE_TYPE_LABELS =
      Map.of(
          "PROHIBITED_WORD", "违禁词",
          "SUPERLATIVE_WORD", "极限词",
          "INFRINGEMENT_WORD", "侵权词",
          "CERTIFICATION_REQUIRED", "认证要求",
          "RESTRICTED_SALE", "禁限售",
          "LABEL_REQUIREMENT", "标签要求",
          "MATERIAL_REQUIREMENT", "材质要求");

  /**
   * 🔴 <b>带词条集合</b>的规则类型：违禁词 / 极限词 / 侵权词。
   *
   * <p>蓝图：「词库是多词条：违禁词 / 极限词 / 侵权词三类规则带词条集合（一条规则多个词条）。
   * 词条属于某个规则<b>版本</b>，不能脱离版本存在。」
   *
   * <p>另外四类（认证要求 / 禁限售 / 标签要求 / 材质要求）**不带词条** ——
   * 它们约束的是「有没有这份认证 / 这个品类能不能卖 / 标签上要写什么 / 材质是什么」，
   * 用一个词表表达不了。DB 层由触发器 {@code guard_compliance_rule_term_ref} 钉死。
   */
  public static final List<String> WORD_RULE_TYPES =
      List.of("PROHIBITED_WORD", "SUPERLATIVE_WORD", "INFRINGEMENT_WORD");

  // ==================================================================
  // 严重度（受控值域 —— 蓝图点名的三种）
  // ==================================================================

  /** 🔴 严重度值域：阻断 / 警告 / 提示（与 DB 的 CHECK 逐字一致）。 */
  public static final List<String> SEVERITIES = List.of("BLOCKING", "WARNING", "INFO");

  /** 严重度的人话名。 */
  public static final Map<String, String> SEVERITY_LABELS =
      Map.of("BLOCKING", "阻断", "WARNING", "警告", "INFO", "提示");

  // ==================================================================
  // 规则生命周期
  // ==================================================================

  /** 🔴 规则生命周期：在用 / 已停用。 */
  public static final List<String> STATUSES = List.of("ACTIVE", "RETIRED");

  /** 规则生命周期的人话名。 */
  public static final Map<String, String> STATUS_LABELS = Map.of("ACTIVE", "在用", "RETIRED", "已停用");

  // ==================================================================
  // 词条分类
  // ==================================================================

  /** 🔴 词条分类：通用 / 品牌 / 品类 / 拼写变体。 */
  public static final List<String> TERM_KINDS = List.of("GENERAL", "BRAND", "CATEGORY", "SPELLING");

  /** 词条分类的人话名。 */
  public static final Map<String, String> TERM_KIND_LABELS =
      Map.of(
          "GENERAL", "通用词",
          "BRAND", "品牌词",
          "CATEGORY", "品类词",
          "SPELLING", "拼写变体");

  // ==================================================================
  // 判定
  // ==================================================================

  /** 规则类型是否合法。 */
  public static boolean knownRuleType(String code) {
    return code != null && RULE_TYPES.contains(code);
  }

  /** 严重度是否合法。 */
  public static boolean knownSeverity(String code) {
    return code != null && SEVERITIES.contains(code);
  }

  /** 规则生命周期是否合法。 */
  public static boolean knownStatus(String code) {
    return code != null && STATUSES.contains(code);
  }

  /** 词条分类是否合法。 */
  public static boolean knownTermKind(String code) {
    return code != null && TERM_KINDS.contains(code);
  }

  /** 🔴 该规则类型是否带词条集合。 */
  public static boolean wordRuleType(String code) {
    return code != null && WORD_RULE_TYPES.contains(code);
  }

  // ==================================================================
  // 人话
  // ==================================================================

  public static String ruleTypeLabel(String code) {
    return RULE_TYPE_LABELS.getOrDefault(code, code);
  }

  public static String severityLabel(String code) {
    return SEVERITY_LABELS.getOrDefault(code, code);
  }

  public static String statusLabel(String code) {
    return STATUS_LABELS.getOrDefault(code, code);
  }

  public static String termKindLabel(String code) {
    return TERM_KIND_LABELS.getOrDefault(code, code);
  }
}
