package com.starcon.channel;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 16.1.2.1 违规与处罚的<b>受控值域与状态机</b>（纯逻辑，无 IO ⇒ 可直接单测）。
 *
 * <p>蓝图：「记录与管理平台违规与处罚：违规类型、涉及商品/订单、处罚措施（警告/下架/限制销售/扣款/停号）、
 * 处理状态与整改要求。输入：平台违规通知同步；整改记录。输出：违规记录 + 整改跟踪 + 扣款金额（进 12 财务）。
 * 关系：上游 = 2 同步；下游 = 12 财务（罚扣款）、4 刊登（问题商品下架）、16.1.2.2 申诉。
 * <b>违规常涉及多个商品</b>，需批量定位受影响 listing 与库存。」
 *
 * <p>🔴🔴 本类是本模块的<b>唯一值域真相源</b>：DB 层 CHECK 与本类必须**逐字一致** ——
 * DB 钉住「值域可审阅」（应用层 enum 改错了也只是运行时报错，DB 才是硬约束），
 * 本类负责「人话 + 状态机」；应用层改错会被 DB 拒 ⇒ 两边不可能长期漂移。
 *
 * <p>🔴🔴 <b>影响档位是「派生」的，不是存的</b>：一张违规有多条处罚措施，影响取决于
 * <b>措施集合</b>（停号 &gt; 下架 &gt; 限制销售 &gt; 扣款 &gt; 警告）。把它存成一列就会与措施表
 * 漂移（改了一条措施，档位还停在旧值）。本模块<b>不存</b>这一列，查询时现算。
 */
public final class PlatformViolationRules {

  private PlatformViolationRules() {}

  // ==================================================================
  // 违规类型（受控值域）
  // ==================================================================

  /** 🔴 违规类型值域（与 DB 的 CHECK 逐字一致）。 */
  public static final List<String> VIOLATION_TYPES =
      List.of(
          "PRODUCT_IP_INFRINGEMENT",
          "PRODUCT_SAFETY",
          "PRODUCT_MISREPRESENTATION",
          "PROHIBITED_ITEM",
          "RESTRICTED_ITEM",
          "ACCOUNT_HEALTH",
          "FULFILLMENT",
          "CONTENT",
          "CUSTOMER_SERVICE",
          "OTHER");

  /** 违规类型的人话名。 */
  public static final Map<String, String> VIOLATION_TYPE_LABELS =
      Map.ofEntries(
          Map.entry("PRODUCT_IP_INFRINGEMENT", "知识产权侵权"),
          Map.entry("PRODUCT_SAFETY", "产品安全（不合规 / 危险品）"),
          Map.entry("PRODUCT_MISREPRESENTATION", "商品信息不实"),
          Map.entry("PROHIBITED_ITEM", "违禁品"),
          Map.entry("RESTRICTED_ITEM", "禁限售"),
          Map.entry("ACCOUNT_HEALTH", "账号健康类违规"),
          Map.entry("FULFILLMENT", "履约违规"),
          Map.entry("CONTENT", "详情页内容违规"),
          Map.entry("CUSTOMER_SERVICE", "客诉与客服违规"),
          Map.entry("OTHER", "其他"));

  // ==================================================================
  // 处罚措施（受控值域 —— 蓝图点名的五种）
  // ==================================================================

  /** 🔴 处罚措施值域：警告 / 下架 / 限制销售 / 扣款 / 停号（与 DB 的 CHECK 逐字一致）。 */
  public static final List<String> MEASURES =
      List.of("WARNING", "DELIST", "RESTRICT_SALES", "DEDUCTION", "SUSPEND_ACCOUNT");

  /** 处罚措施的人话名。 */
  public static final Map<String, String> MEASURE_LABELS =
      Map.of(
          "WARNING", "警告",
          "DELIST", "下架",
          "RESTRICT_SALES", "限制销售",
          "DEDUCTION", "扣款",
          "SUSPEND_ACCOUNT", "停号");

  /** 🔴 只有「扣款」这一种措施带金额；其余四种**一律不带**。 */
  public static final String MEASURE_DEDUCTION = "DEDUCTION";

  // ==================================================================
  // 处理状态（受控值域 + 状态机）
  // ==================================================================

  /** 🔴 违规处理状态值域。 */
  public static final List<String> STATUSES =
      List.of("PENDING", "IN_PROGRESS", "RECTIFIED", "APPEALING", "CLOSED");

  /** 处理状态的人话名。 */
  public static final Map<String, String> STATUS_LABELS =
      Map.of(
          "PENDING", "未处理",
          "IN_PROGRESS", "处理中",
          "RECTIFIED", "已整改",
          "APPEALING", "申诉中",
          "CLOSED", "已关闭");

  /**
   * 🔴 违规处理状态的<b>合法转换</b>（未处理 → 处理中 → 已整改 / 申诉中 / 已关闭）。
   *
   * <p>与 DB 触发器 {@code guard_platform_violation_status_transition} 逐字一致。
   * 服务层先拒（422，带人话），DB 再拒（23514，兜底）—— 两道防线。
   */
  public static final Map<String, List<String>> STATUS_TRANSITIONS =
      Map.of(
          "PENDING", List.of("IN_PROGRESS"),
          "IN_PROGRESS", List.of("RECTIFIED", "APPEALING", "CLOSED"),
          "RECTIFIED", List.of("IN_PROGRESS", "APPEALING", "CLOSED"),
          "APPEALING", List.of("IN_PROGRESS", "RECTIFIED", "CLOSED"),
          "CLOSED", List.of());

  // ==================================================================
  // 整改要求状态（受控值域 + 状态机）
  // ==================================================================

  /** 🔴 整改状态值域。 */
  public static final List<String> REMEDIATION_STATUSES =
      List.of("OPEN", "IN_PROGRESS", "SUBMITTED", "VERIFIED", "REJECTED");

  /** 整改状态的人话名。 */
  public static final Map<String, String> REMEDIATION_STATUS_LABELS =
      Map.of(
          "OPEN", "待整改",
          "IN_PROGRESS", "整改中",
          "SUBMITTED", "已提交（待验收）",
          "VERIFIED", "已验收",
          "REJECTED", "已驳回（需重新整改）");

  /** 🔴 整改状态的合法转换（与 DB 触发器逐字一致）。 */
  public static final Map<String, List<String>> REMEDIATION_STATUS_TRANSITIONS =
      Map.of(
          "OPEN", List.of("IN_PROGRESS", "SUBMITTED"),
          "IN_PROGRESS", List.of("SUBMITTED"),
          "SUBMITTED", List.of("VERIFIED", "REJECTED"),
          "REJECTED", List.of("IN_PROGRESS"),
          "VERIFIED", List.of());

  // ==================================================================
  // 涉及主体类型
  // ==================================================================

  /** 🔴 违规涉及的主体类型：商品（SKU）/ 平台商品（LISTING）/ 订单（ORDER）。 */
  public static final List<String> SUBJECT_TYPES = List.of("SKU", "LISTING", "ORDER");

  /** 主体类型的人话名。 */
  public static final Map<String, String> SUBJECT_TYPE_LABELS =
      Map.of("SKU", "商品（SKU）", "LISTING", "平台商品（Listing）", "ORDER", "订单");

  // ==================================================================
  // 来源
  // ==================================================================

  /** 🔴 来源：平台同步来的 / 人工登记的。 */
  public static final List<String> SOURCES = List.of("PLATFORM_SYNC", "MANUAL");

  /** 来源的人话名。 */
  public static final Map<String, String> SOURCE_LABELS =
      Map.of("PLATFORM_SYNC", "平台同步", "MANUAL", "人工登记");

  // ==================================================================
  // 影响档位（**派生**，不落库）
  // ==================================================================

  /** 🔴 影响档位：按**处罚措施集合**派生的严重度（不落库，避免与措施表漂移）。 */
  public static final List<String> IMPACT_LEVELS = List.of("CRITICAL", "HIGH", "MEDIUM", "LOW");

  /** 影响档位的人话名。 */
  public static final Map<String, String> IMPACT_LABELS =
      Map.of(
          "CRITICAL", "致命（停号级）",
          "HIGH", "严重（下架级）",
          "MEDIUM", "中等（限制销售 / 扣款级）",
          "LOW", "轻微（警告级）");

  /** 🔴 措施 → 档位的映射（档位取措施集合里的最高档）。 */
  private static final Map<String, String> MEASURE_IMPACT =
      Map.of(
          "SUSPEND_ACCOUNT", "CRITICAL",
          "DELIST", "HIGH",
          "RESTRICT_SALES", "MEDIUM",
          "DEDUCTION", "MEDIUM",
          "WARNING", "LOW");

  /**
   * 🔴 由处罚措施集合派生影响档位。
   *
   * <p>没有措施 ⇒ 记「无措施」（LOW 之上但仍然最低）。**这不是「没问题」** ——
   * 一条没有任何处罚措施的违规仍然需要处理，页面上不能把它显示成「无风险」。
   */
  public static String impactLevel(Collection<String> measures) {
    if (measures == null || measures.isEmpty()) {
      return "NONE";
    }
    String level = "NONE";
    for (String measure : measures) {
      String candidate = MEASURE_IMPACT.get(measure);
      if (candidate == null) {
        continue;
      }
      if (rank(candidate) < rank(level)) {
        level = candidate;
      }
    }
    return level;
  }

  /** 档位的排序权重（越小越严重）。 */
  private static int rank(String level) {
    switch (level) {
      case "CRITICAL":
        return 0;
      case "HIGH":
        return 1;
      case "MEDIUM":
        return 2;
      case "LOW":
        return 3;
      default:
        return 4;
    }
  }

  /** 影响档位的人话名（含「无措施」）。 */
  public static String impactLabel(String level) {
    if ("NONE".equals(level)) {
      return "尚无处罚措施";
    }
    return IMPACT_LABELS.getOrDefault(level, level);
  }

  // ==================================================================
  // 状态机
  // ==================================================================

  /** 🔴 违规处理状态能否这么转（服务层先拒，DB 再兜底）。 */
  public static boolean canTransition(String from, String to) {
    if (from == null || to == null) {
      return false;
    }
    return STATUS_TRANSITIONS.getOrDefault(from, List.of()).contains(to);
  }

  /** 🔴 整改状态能否这么转。 */
  public static boolean canTransitionRemediation(String from, String to) {
    if (from == null || to == null) {
      return false;
    }
    return REMEDIATION_STATUS_TRANSITIONS.getOrDefault(from, List.of()).contains(to);
  }

  /** 当前状态能去的所有状态（界面上给「下一步」用）。 */
  public static List<String> nextStatuses(String from) {
    return STATUS_TRANSITIONS.getOrDefault(from, List.of());
  }

  /** 当前整改状态能去的所有状态。 */
  public static List<String> nextRemediationStatuses(String from) {
    return REMEDIATION_STATUS_TRANSITIONS.getOrDefault(from, List.of());
  }

  // ==================================================================
  // 人话
  // ==================================================================

  public static String violationTypeLabel(String code) {
    return VIOLATION_TYPE_LABELS.getOrDefault(code, code);
  }

  public static String measureLabel(String code) {
    return MEASURE_LABELS.getOrDefault(code, code);
  }

  public static String statusLabel(String code) {
    return STATUS_LABELS.getOrDefault(code, code);
  }

  public static String remediationStatusLabel(String code) {
    return REMEDIATION_STATUS_LABELS.getOrDefault(code, code);
  }

  public static String subjectTypeLabel(String code) {
    return SUBJECT_TYPE_LABELS.getOrDefault(code, code);
  }

  public static String sourceLabel(String code) {
    return SOURCE_LABELS.getOrDefault(code, code);
  }

  /** 值域是否合法（服务层在写库前先拒，给出比 DB 错误更好懂的信息）。 */
  public static boolean knownViolationType(String code) {
    return code != null && VIOLATION_TYPES.contains(code);
  }

  public static boolean knownMeasure(String code) {
    return code != null && MEASURES.contains(code);
  }

  public static boolean knownStatus(String code) {
    return code != null && STATUSES.contains(code);
  }

  public static boolean knownRemediationStatus(String code) {
    return code != null && REMEDIATION_STATUSES.contains(code);
  }

  public static boolean knownSubjectType(String code) {
    return code != null && SUBJECT_TYPES.contains(code);
  }

  /** 措施集合（去重后的受控值域子集）。 */
  public static Set<String> measureSet(Collection<String> measures) {
    return measures == null ? Set.of() : Set.copyOf(measures);
  }
}
