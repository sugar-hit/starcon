package com.starcon.channel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 16.4.1.1 侵权库的<b>受控值域</b>（纯逻辑，无 IO ⇒ 可直接单测）。
 *
 * <p>蓝图：「维护知识产权风险库：品牌商标、专利、版权、肖像权，登记风险等级与适用平台。
 * 输入：侵权条目登记；批量导入。输出：侵权库记录。关系：上游 = 3.3.2 品牌；下游 = 16.4.1.2 检测。
 * <b>侵权检测是刊登前拦截而非事后申诉</b>，成本差一个数量级。」
 *
 * <p>🔴🔴 本类是本模块的<b>唯一值域真相源</b>：DB 层 CHECK（迁移 V315）与本类必须<b>逐字一致</b> ——
 * DB 钉住「值域可审阅」（应用层 enum 改错了也只是运行时报错，DB 才是硬约束），
 * 本类负责「人话 + 逐行校验口径」。
 *
 * <p>🔴🔴 本模块最要紧的三条口径：
 *
 * <ul>
 *   <li><b>权利类型是受控枚举</b>（四种：品牌商标 / 专利 / 版权 / 肖像权），
 *       <b>不</b>留自由文本 —— 自由文本的「商标」/「TRADEMARK」/「商标类」会被检测那一侧
 *       当成三种不同的权利。</li>
 *   <li><b>风险等级是受控等级集</b>（高 / 中 / 低），<b>不用 0..100 的裸数字</b> ——
 *       裸数字没有口径，下游拿到的「72 分」没法解释成「高风险」还是「中风险」。</li>
 *   <li><b>适用平台是多对多</b>，不是一个单列 —— 一条条目常常在多个平台上都要拦。</li>
 * </ul>
 */
public final class IpRiskLibraryRules {

  private IpRiskLibraryRules() {}

  // ==================================================================
  // 权利类型（受控值域 —— 蓝图点名的四种）
  // ==================================================================

  /** 🔴 权利类型值域（与 DB 的 CHECK 逐字一致）。 */
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
  // 风险等级（受控等级集）
  // ==================================================================

  /** 🔴 风险等级值域：高 / 中 / 低（与 DB 的 CHECK 逐字一致）。 */
  public static final List<String> RISK_LEVELS = List.of("HIGH", "MEDIUM", "LOW");

  /** 风险等级的人话名。 */
  public static final Map<String, String> RISK_LEVEL_LABELS =
      Map.of("HIGH", "高风险", "MEDIUM", "中风险", "LOW", "低风险");

  /**
   * 🔴 风险等级的**排序权重**（高 → 中 → 低）。
   *
   * <p>排序与「取最严重的一条」都用它，避免各处各写一遍 if。
   */
  public static int riskWeight(String code) {
    return switch (code == null ? "" : code) {
      case "HIGH" -> 3;
      case "MEDIUM" -> 2;
      case "LOW" -> 1;
      default -> 0;
    };
  }

  // ==================================================================
  // 生命周期
  // ==================================================================

  /** 🔴 生命周期：启用 / 停用。 */
  public static final List<String> STATUSES = List.of("ACTIVE", "DISABLED");

  /** 生命周期的人话名。 */
  public static final Map<String, String> STATUS_LABELS = Map.of("ACTIVE", "启用", "DISABLED", "已停用");

  // ==================================================================
  // 匹配特征分类
  // ==================================================================

  /** 🔴 匹配特征分类：关键词 / 短语。 */
  public static final List<String> TERM_KINDS = List.of("KEYWORD", "PHRASE");

  /** 匹配特征分类的人话名。 */
  public static final Map<String, String> TERM_KIND_LABELS = Map.of("KEYWORD", "关键词", "PHRASE", "短语");

  // ==================================================================
  // 来源
  // ==================================================================

  /** 🔴 来源：人工登记 / 批量导入。 */
  public static final List<String> SOURCES = List.of("MANUAL", "IMPORT");

  /** 来源的人话名。 */
  public static final Map<String, String> SOURCE_LABELS = Map.of("MANUAL", "人工登记", "IMPORT", "批量导入");

  // ==================================================================
  // 判定
  // ==================================================================

  /** 权利类型是否合法。 */
  public static boolean knownRightType(String code) {
    return code != null && RIGHT_TYPES.contains(code);
  }

  /** 风险等级是否合法。 */
  public static boolean knownRiskLevel(String code) {
    return code != null && RISK_LEVELS.contains(code);
  }

  /** 生命周期是否合法。 */
  public static boolean knownStatus(String code) {
    return code != null && STATUSES.contains(code);
  }

  /** 匹配特征分类是否合法。 */
  public static boolean knownTermKind(String code) {
    return code != null && TERM_KINDS.contains(code);
  }

  // ==================================================================
  // 人话
  // ==================================================================

  public static String rightTypeLabel(String code) {
    return RIGHT_TYPE_LABELS.getOrDefault(code, code);
  }

  public static String riskLevelLabel(String code) {
    return RISK_LEVEL_LABELS.getOrDefault(code, code);
  }

  public static String statusLabel(String code) {
    return STATUS_LABELS.getOrDefault(code, code);
  }

  public static String termKindLabel(String code) {
    return TERM_KIND_LABELS.getOrDefault(code, code);
  }

  public static String sourceLabel(String code) {
    return SOURCE_LABELS.getOrDefault(code, code);
  }

  // ==================================================================
  // 逐行校验（批量导入与手工登记共用同一套口径）
  // ==================================================================

  /**
   * 一行的校验问题：**哪个字段** + **为什么**。
   *
   * @param field 出问题的字段名（entryCode / rightType / riskLevel / title / platforms）
   * @param message 人话原因
   */
  public record RowProblem(String field, String message) {}

  /**
   * 🔴 校验一行侵权条目。
   *
   * <p>批量导入必须给出**逐行**的「哪个字段、为什么」，而不是整份文件一个「失败」。
   * 这一段是<b>纯逻辑</b>（平台码表由调用方注入）⇒ 可以直接单测。
   *
   * @param entryCode 条目编码（幂等锚点）
   * @param rightType 权利类型
   * @param riskLevel 风险等级
   * @param title 条目标题
   * @param platformCodes 适用平台（多值）
   * @param knownPlatformCodes 平台目录里真实存在的编码
   * @return 问题清单（**空 ⇒ 这一行可以入库**）
   */
  public static List<RowProblem> validateEntryFields(
      String entryCode,
      String rightType,
      String riskLevel,
      String title,
      List<String> platformCodes,
      Set<String> knownPlatformCodes) {
    List<RowProblem> problems = new ArrayList<>();
    if (text(entryCode).isEmpty()) {
      problems.add(new RowProblem("entryCode", "条目编码不能为空（它是批量导入的幂等锚点）"));
    }
    if (!knownRightType(rightType)) {
      problems.add(
          new RowProblem(
              "rightType",
              "未知的权利类型："
                  + (text(rightType).isEmpty() ? "(空)" : rightType)
                  + "（只能是 TRADEMARK / PATENT / COPYRIGHT / IMAGE_RIGHT）"));
    }
    if (!knownRiskLevel(riskLevel)) {
      problems.add(
          new RowProblem(
              "riskLevel",
              "未知的风险等级："
                  + (text(riskLevel).isEmpty() ? "(空)" : riskLevel)
                  + "（只能是 HIGH / MEDIUM / LOW）"));
    }
    if (text(title).isEmpty()) {
      problems.add(new RowProblem("title", "条目标题不能为空（它是这条权利的人话名）"));
    }
    if (platformCodes != null) {
      for (String code : platformCodes) {
        String value = text(code).toUpperCase();
        if (value.isEmpty()) {
          problems.add(new RowProblem("platforms", "适用平台不能是空值"));
          break;
        }
        if (knownPlatformCodes != null && !knownPlatformCodes.contains(value)) {
          problems.add(
              new RowProblem("platforms", "未知的适用平台：" + value + "（平台目录里没有这个平台）"));
          break;
        }
      }
    }
    return problems;
  }

  /** 去空白（null ⇒ 空串）。 */
  public static String text(String value) {
    return value == null ? "" : value.trim();
  }
}
