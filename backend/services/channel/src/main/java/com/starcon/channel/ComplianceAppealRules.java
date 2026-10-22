package com.starcon.channel;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 16.1.2.2 申诉管理的<b>受控值域与状态机</b>（纯逻辑，无 IO ⇒ 可直接单测）。
 *
 * <p>蓝图：「管理平台申诉：申诉类型、举证材料收集、申诉文本、提交跟踪、结果记录与申诉次数统计。
 * 输入：违规记录；申诉材料；提交动作。输出：申诉单 + 提交记录 + 结果（通过/驳回）。
 * 关系：上游 = 16.1.2.1；下游 = 平台申诉接口。<b>申诉配额有限</b>，需记录已用次数并优先申诉高影响违规。」
 *
 * <p>🔴🔴 本类是本模块的<b>唯一值域真相源</b>：DB 层 CHECK 与本类必须**逐字一致** ——
 * DB 钉住「值域可审阅」，本类负责「人话 + 状态机」；应用层改错会被 DB 拒 ⇒ 两边不可能长期漂移。
 *
 * <p>🔴 <b>影响档位不在本类里重写一遍</b>：它是上游 16.1.2.1 的派生概念，
 * 本模块直接复用 {@link PlatformViolationRules#impactLabel(String)}，
 * 避免「同一个档位两套人话、两套排序」。
 */
public final class ComplianceAppealRules {

  private ComplianceAppealRules() {}

  // ==================================================================
  // 申诉类型（受控值域）
  // ==================================================================

  /** 🔴 申诉类型值域（与 DB 的 CHECK 逐字一致）。 */
  public static final List<String> APPEAL_TYPES =
      List.of(
          "POLICY_MISINTERPRETATION",
          "EVIDENCE_PROVIDED",
          "PROCESS_ERROR",
          "DUPLICATE_PENALTY",
          "RESTORATION_REQUEST",
          "OTHER");

  /** 申诉类型的人话名。 */
  public static final Map<String, String> APPEAL_TYPE_LABELS =
      Map.of(
          "POLICY_MISINTERPRETATION", "政策误判",
          "EVIDENCE_PROVIDED", "补充证据",
          "PROCESS_ERROR", "处理流程有误",
          "DUPLICATE_PENALTY", "重复处罚",
          "RESTORATION_REQUEST", "恢复措施申请",
          "OTHER", "其他");

  // ==================================================================
  // 申诉状态（受控值域 + 状态机）
  // ==================================================================

  /** 🔴 申诉状态值域。 */
  public static final List<String> STATUSES =
      List.of("DRAFT", "SUBMITTED", "APPROVED", "REJECTED", "WITHDRAWN");

  /** 申诉状态的人话名。 */
  public static final Map<String, String> STATUS_LABELS =
      Map.of(
          "DRAFT", "草稿（未提交）",
          "SUBMITTED", "已提交（等平台裁定）",
          "APPROVED", "申诉通过",
          "REJECTED", "申诉驳回",
          "WITHDRAWN", "已撤回");

  /**
   * 🔴 申诉状态的<b>合法转换</b>。
   *
   * <p>与 DB 触发器 {@code guard_platform_appeal_status_transition} 逐字一致。
   * 服务层先拒（422，带人话），DB 再拒（23514，兜底）—— 两道防线。
   *
   * <p>🔴 「驳回 → 草稿」这条**往回走**的边是刻意留的：它让「提交记录」与
   * 「申诉次数统计」不是摆设 —— 驳回后补充举证材料再提一次，是**第 2 次提交**、
   * 消耗**第 2 次配额**。没有这条边，「申诉配额有限」就只是一句口号。
   */
  public static final Map<String, List<String>> STATUS_TRANSITIONS =
      Map.of(
          "DRAFT", List.of("SUBMITTED", "WITHDRAWN"),
          "SUBMITTED", List.of("APPROVED", "REJECTED"),
          "REJECTED", List.of("DRAFT"),
          "APPROVED", List.of(),
          "WITHDRAWN", List.of());

  // ==================================================================
  // 举证材料类型（受控值域）
  // ==================================================================

  /** 🔴 举证材料类型值域（与 DB 的 CHECK 逐字一致）。 */
  public static final List<String> EVIDENCE_TYPES =
      List.of("DOCUMENT", "IMAGE", "VIDEO", "LINK", "NOTE", "INVOICE", "CERTIFICATE");

  /** 举证材料类型的人话名。 */
  public static final Map<String, String> EVIDENCE_TYPE_LABELS =
      Map.of(
          "DOCUMENT", "文档",
          "IMAGE", "图片",
          "VIDEO", "视频",
          "LINK", "链接",
          "NOTE", "说明",
          "INVOICE", "发票 / 采购凭证",
          "CERTIFICATE", "资质证书");

  // ==================================================================
  // 申诉结果（受控值域）
  // ==================================================================

  /** 🔴 申诉结果值域：通过 / 驳回（与 DB 的 CHECK 逐字一致）。 */
  public static final List<String> OUTCOMES = List.of("APPROVED", "REJECTED");

  /** 申诉结果的人话名。 */
  public static final Map<String, String> OUTCOME_LABELS =
      Map.of("APPROVED", "通过", "REJECTED", "驳回");

  // ==================================================================
  // 状态机
  // ==================================================================

  /** 🔴 申诉状态能否这么转（服务层先拒，DB 再兜底）。 */
  public static boolean canTransition(String from, String to) {
    if (from == null || to == null) {
      return false;
    }
    return STATUS_TRANSITIONS.getOrDefault(from, List.of()).contains(to);
  }

  /** 当前状态能去的所有状态（界面上给「下一步」用）。 */
  public static List<String> nextStatuses(String from) {
    return STATUS_TRANSITIONS.getOrDefault(from, List.of());
  }

  /** 🔴 当前状态能不能改申诉文本（只有草稿态能改）。 */
  public static boolean editableText(String status) {
    return "DRAFT".equals(status);
  }

  // ==================================================================
  // 人话
  // ==================================================================

  public static String appealTypeLabel(String code) {
    return APPEAL_TYPE_LABELS.getOrDefault(code, code);
  }

  public static String statusLabel(String code) {
    return STATUS_LABELS.getOrDefault(code, code);
  }

  public static String evidenceTypeLabel(String code) {
    return EVIDENCE_TYPE_LABELS.getOrDefault(code, code);
  }

  public static String outcomeLabel(String code) {
    return OUTCOME_LABELS.getOrDefault(code, code);
  }

  /**
   * 🔴 影响档位的人话 —— <b>直接复用上游 16.1.2.1 的规则</b>。
   *
   * <p>同一个档位不允许有两套人话：上游把「尚无处罚措施」单独做成一个档位，
   * 本模块照抄它，避免页面上出现「无措施」与「尚无处罚措施」两种说法。
   */
  public static String impactLabel(String level) {
    return PlatformViolationRules.impactLabel(level);
  }

  // ==================================================================
  // 值域校验
  // ==================================================================

  /** 值域是否合法（服务层在写库前先拒，给出比 DB 错误更好懂的信息）。 */
  public static boolean knownAppealType(String code) {
    return code != null && APPEAL_TYPES.contains(code);
  }

  public static boolean knownStatus(String code) {
    return code != null && STATUSES.contains(code);
  }

  public static boolean knownEvidenceType(String code) {
    return code != null && EVIDENCE_TYPES.contains(code);
  }

  public static boolean knownOutcome(String code) {
    return code != null && OUTCOMES.contains(code);
  }

  /** 值域集合（去重后的受控值域子集）。 */
  public static Set<String> appealTypeSet(Collection<String> types) {
    return types == null ? Set.of() : Set.copyOf(types);
  }
}
