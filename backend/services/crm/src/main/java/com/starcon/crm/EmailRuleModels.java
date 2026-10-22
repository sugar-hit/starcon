package com.starcon.crm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * 15.1.2.2 邮件规则的传输模型。
 *
 * <p>蓝图 15 卷 15.1.2.2 原文：
 *
 * <blockquote>
 *
 * 职责：配置邮件自动处理规则：按发件人/关键词/工单标签自动分配、自动回复、静默规则（重要沟通不可静默）。 输入：规则配置；15.1.1.3 分类结果。 输出：规则命中记录；自动分配结果。 关系：上游 = 15.1.1.3；下游 = 15.4.1.1 工单分配。 <b>关键沟通静默会造成平台违规</b>，规则需强制留痕。
 *
 * </blockquote>
 *
 * <p><b>建模纪律</b>：
 *
 * <ul>
 *   <li><b>声明的动作 vs 实际生效的动作</b>：命中记录同时给
 *       {@code action}（规则声明的）与 {@code effectiveAction}（实际生效的）。
 *       重要沟通命中静默规则时，实际生效的动作只能是
 *       {@code ESCALATE_MANUAL}（不静默 + 转人工）—— 这条降级<b>在服务端强制</b>，
 *       不靠前端约定。</li>
 *   <li><b>命中凭据冻结当时的规则</b>：{@code ruleVersion} + {@code ruleSnapshot}
 *       + {@code matchBasis}（命中条件明细）—— 改规则<b>不追溯</b>改变历史命中记录。</li>
 *   <li><b>命中记录只追加</b>：DB 触发器拒绝改删（它是「当时发生了什么」的证据）。</li>
 *   <li><b>未交付的下游不编</b>：自动分配只产出<b>分配结果</b>（分给谁、依据哪条规则），
 *       15.4.1.1 工单未交付 ⇒ <b>不创建工单、不写工单 id</b>；
 *       15.5.1 自动回复未交付 ⇒ 只留痕意图，{@code deliveryStatus} 恒为
 *       {@code PENDING_DOWNSTREAM}。</li>
 * </ul>
 */
public final class EmailRuleModels {

  private EmailRuleModels() {}

  // ================================================================ 词表

  /** 命中动作。与 V305 的 CHECK 同词表。 */
  public static final List<String> ACTIONS = List.of("AUTO_ASSIGN", "AUTO_REPLY", "SILENCE");

  /** 实际生效的动作（在 {@link #ACTIONS} 之外多了「不静默 + 转人工」）。 */
  public static final List<String> EFFECTIVE_ACTIONS =
      List.of("AUTO_ASSIGN", "AUTO_REPLY", "SILENCE", "ESCALATE_MANUAL");

  /** 自动分配的目标类型：组 / 人。 */
  public static final List<String> ASSIGN_TARGET_TYPES = List.of("TEAM", "AGENT");

  /** 同一维度内多个值的命中方式。 */
  public static final List<String> MATCH_MODES = List.of("ANY", "ALL");

  /**
   * 🔴 重要沟通的**分类类别**（上游 15.1.1.3 的标签）。
   *
   * <p>平台合规 = 静默它等于违规；投诉 = 静默它等于把纠纷吞掉。
   */
  public static final List<String> IMPORTANT_LABELS = List.of("PLATFORM_COMPLIANCE", "COMPLAINT");

  // ================================================================ 输入

  /**
   * 一条邮件规则（新建 / 修改共用）。
   *
   * <p>⚠️ 三个条件维度（发件人 / 关键词 / 工单标签）**至少要有一个非空**：
   * 全空的规则永远不会命中，却会占掉一个顺序位 —— 那是一条
   * 「看起来配了、其实没生效」的规则。
   */
  public record CrmEmailRuleInput(
      @NotBlank @Size(max = 64)
          @Schema(description = "规则编码（同租户内唯一，建后不可改）")
          String ruleCode,
      @NotBlank @Size(max = 200) @Schema(description = "规则名称") String ruleName,
      @NotNull @PositiveOrZero
          @Schema(description = "显式顺序：数字小的先命中；并列时按 rule_code")
          Integer priorityOrder,
      @Schema(nullable = true, description = "是否启用（停用而不删除：历史命中依据要查得到）")
          Boolean enabled,
      @NotBlank @Pattern(regexp = "AUTO_ASSIGN|AUTO_REPLY|SILENCE")
          @Schema(description = "命中动作：自动分配 / 自动回复 / 静默")
          String action,
      @Schema(nullable = true, description = "发件人邮箱（精确匹配，忽略大小写）")
          List<@NotBlank @Size(max = 320) String> senderEmails,
      @Schema(nullable = true, description = "发件人域名（精确匹配，忽略大小写）")
          List<@NotBlank @Size(max = 200) String> senderDomains,
      @Schema(nullable = true, description = "关键词（主题 / 正文，字面包含，忽略大小写）")
          List<@NotBlank @Size(max = 100) String> keywords,
      @Schema(nullable = true, description = "工单标签码（必须是字典里真实存在的标签）")
          List<@NotBlank @Size(max = 64) String> ticketTagCodes,
      @Schema(nullable = true, description = "同一维度内多个值的命中方式：ANY（默认）· ALL")
          String matchMode,
      @Schema(nullable = true, description = "自动分配目标类型：TEAM（组）· AGENT（人）")
          String assignTargetType,
      @Schema(nullable = true, description = "自动分配目标（组码 / 人工号）")
          String assignTargetCode,
      @Schema(nullable = true, description = "自动回复的模板码")
          String replyTemplateCode,
      @Schema(nullable = true, description = "自动回复的标题")
          String replySubject,
      @Schema(nullable = true, description = "自动回复的正文")
          String replyBody,
      @Schema(nullable = true, description = "🔴 重要沟通标记：命中即视为重要沟通，不可静默")
          List<@NotBlank @Size(max = 100) String> importantMarkers,
      @Size(max = 500) String note) {}

  /** 新建 / 修改一个工单标签（规则条件与打标共用这份字典）。 */
  public record CrmEmailTicketTagInput(
      @NotBlank @Size(max = 64)
          @Schema(description = "标签码（同租户内唯一，建后不可改）")
          String tagCode,
      @NotBlank @Size(max = 120) @Schema(description = "标签名") String name,
      @Schema(nullable = true, description = "状态：ACTIVE（默认）· DISABLED") String status,
      @Size(max = 600) String description) {}

  /** 给一封邮件打上工单标签。 */
  public record CrmEmailTagAssignmentInput(
      @NotBlank @Size(max = 64)
          @Schema(description = "标签码（必须是字典里真实存在的标签）")
          String tagCode,
      @Schema(nullable = true, description = "打标来源：MANUAL（默认）· RULE") String source) {}

  /** 评估一封邮件（触发一次规则评估）。 */
  public record CrmEmailRuleEvaluateInput(
      @NotNull @Schema(description = "要评估的邮件（15.1.2.1 的 crm_buyer_email.id）") UUID emailId,
      @Schema(nullable = true, description = "只评估这一条规则（留空 = 按顺序评估全部启用规则）")
          UUID ruleId) {}

  /** 批量评估：逐条给出结论与原因，失败的条目不会被悄悄跳过。 */
  public record CrmEmailRuleEvaluateBatchInput(
      @NotEmpty @Size(max = 200) List<UUID> emailIds) {}

  /** 试算：这条规则对这封邮件会不会命中（**不写命中记录**）。 */
  public record CrmEmailRulePreviewInput(
      @NotNull @Schema(description = "试算的邮件 id") UUID emailId) {}

  /** 取消一条自动分配结果（必须写理由）。 */
  public record CrmEmailRuleCancelAssignmentInput(
      @NotBlank @Size(max = 600)
          @Schema(description = "取消理由（必填）：为什么这条分配结果不作数")
          String cancelReason) {}

  // ================================================================ 能力声明

  /** 能力声明的一维：有没有、依据是什么、缺了会怎样。 */
  public record CrmEmailRuleCapabilityDimension(
      String dimension, String description, boolean available, String source, String ownerModule,
      String impact) {}

  /** 能力声明：前端在渲染前就知道哪些动作存在，而不是靠试。 */
  public record CrmEmailRuleCapabilities(
      List<CrmEmailRuleCapabilityDimension> dimensions,
      List<String> actions,
      List<String> effectiveActions,
      List<String> assignTargetTypes,
      List<String> matchModes,
      List<String> importantLabels,
      String boundaryNote,
      String highlight) {}

  /** 筛选词表（动作与命中方式是 DB 层允许的取值域；标签码来自本租户真实数据）。 */
  public record CrmEmailRuleOptions(
      List<String> actions,
      List<String> effectiveActions,
      List<String> assignTargetTypes,
      List<String> matchModes,
      List<String> importantLabels,
      List<String> tagCodes,
      String note) {}

  // ================================================================ 规则

  /** 一条邮件规则。 */
  public record CrmEmailRuleRow(
      UUID id,
      String ruleCode,
      String ruleName,
      int priorityOrder,
      boolean enabled,
      String action,
      List<String> senderEmails,
      List<String> senderDomains,
      List<String> keywords,
      List<String> ticketTagCodes,
      String matchMode,
      String assignTargetType,
      String assignTargetCode,
      String replyTemplateCode,
      String replySubject,
      String replyBody,
      List<String> importantMarkers,
      int version,
      String note,
      String createdAt,
      String updatedAt) {}

  /** 规则列表页：真分页（LIMIT/OFFSET + 独立 COUNT(*)）。 */
  public record CrmEmailRulePage(
      List<CrmEmailRuleRow> items, int page, int size, long total) {}

  /** 规则的新增 / 修改 / 删除结果。 */
  public record CrmEmailRuleResult(CrmEmailRuleRow rule, String note) {}

  // ================================================================ 工单标签

  /** 一个工单标签（规则条件与打标共用这份字典）。 */
  public record CrmEmailTicketTagRow(
      UUID id,
      String tagCode,
      String name,
      String status,
      String description,
      long usageCount,
      String createdAt,
      String updatedAt) {}

  /** 标签列表页：真分页 + 真筛选。 */
  public record CrmEmailTicketTagPage(
      List<CrmEmailTicketTagRow> items, int page, int size, long total) {}

  /** 标签的新增 / 修改 / 删除结果。 */
  public record CrmEmailTicketTagResult(CrmEmailTicketTagRow tag, String note) {}

  /** 一条打标记录（邮件 ↔ 工单标签）。 */
  public record CrmEmailTagAssignmentRow(
      UUID id,
      UUID emailId,
      UUID tagId,
      String tagCode,
      String tagName,
      String source,
      UUID assignedBy,
      String assignedAt,
      String createdAt) {}

  // ================================================================ 命中条件

  /** 发件人维度的命中明细。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record CrmEmailRuleSenderCondition(
      boolean matched,
      List<String> emails,
      List<String> domains,
      List<String> matchedEmails,
      List<String> matchedDomains) {}

  /** 关键词维度的命中明细。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record CrmEmailRuleKeywordCondition(
      boolean matched, List<String> keywords, List<String> matchedKeywords) {}

  /** 工单标签维度的命中明细。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record CrmEmailRuleTicketTagCondition(
      boolean matched, List<String> codes, List<String> matchedCodes) {}

  /** 一条规则命中的**条件明细**（三个维度分别命中了什么）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record CrmEmailRuleConditionDetail(
      CrmEmailRuleSenderCondition sender,
      CrmEmailRuleKeywordCondition keywords,
      CrmEmailRuleTicketTagCondition ticketTags) {}

  /** 一条规则的命中情况。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record CrmEmailRuleMatch(
      UUID ruleId,
      String ruleCode,
      String ruleName,
      int ruleOrder,
      int ruleVersion,
      String action,
      boolean won,
      CrmEmailRuleConditionDetail conditions) {}

  /**
   * 完整命中依据（落库在 jsonb 列 {@code match_basis} 上）。
   *
   * @param winningRuleId 最终生效的规则 id（没有规则命中时为 null）
   * @param matches 所有命中过的规则（不只是赢家）—— 只记赢家就看不出「为什么不是它」
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record CrmEmailRuleMatchBasis(
      @Schema(nullable = true) UUID winningRuleId,
      @Schema(nullable = true) String winningRuleCode,
      List<CrmEmailRuleMatch> matches) {}

  // ================================================================ 命中记录

  /** 一条规则命中记录（只追加）。 */
  public record CrmEmailRuleHitRow(
      UUID id,
      String hitNo,
      UUID ruleId,
      String ruleCode,
      String ruleName,
      int ruleVersion,
      UUID emailId,
      String emailNo,
      String buyerEmail,
      String subject,
      CrmEmailRuleMatchBasis matchBasis,
      String action,
      String effectiveAction,
      boolean silenceRefused,
      String refusalReason,
      boolean important,
      String importantSource,
      @Schema(nullable = true) UUID classificationId,
      String classificationLabel,
      @Schema(nullable = true) UUID assignmentId,
      @Schema(nullable = true) String deliveryStatus,
      String matchedAt,
      String createdAt) {}

  /** 命中记录列表页：真分页 + 真筛选。 */
  public record CrmEmailRuleHitPage(
      List<CrmEmailRuleHitRow> items, int page, int size, long total) {}

  /**
   * 命中当时的**规则快照**（冻结内容）。
   *
   * <p>🔴 这是「改规则不追溯改变历史命中记录」的证据：改完规则之后再看历史命中，
   * 看到的仍然是**当时**那一版的条件与动作。
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record CrmEmailRuleSnapshot(
      String ruleCode,
      String ruleName,
      int priorityOrder,
      boolean enabled,
      String action,
      List<String> senderEmails,
      List<String> senderDomains,
      List<String> keywords,
      List<String> ticketTagCodes,
      String matchMode,
      String assignTargetType,
      String assignTargetCode,
      String replyTemplateCode,
      String replySubject,
      String replyBody,
      List<String> importantMarkers,
      int version) {}

  /** 单条命中详情 = 命中记录 + 邮件快照 + 当时的规则快照。 */
  public record CrmEmailRuleHitDetail(
      CrmEmailRuleHitRow hit,
      CrmEmailRuleEmailView email,
      CrmEmailRuleSnapshot ruleSnapshot,
      @Schema(nullable = true) CrmEmailRuleAssignmentRow assignment) {}

  /** 邮件快照（命中记录挂在哪封邮件上）。 */
  public record CrmEmailRuleEmailView(
      UUID id,
      String emailNo,
      String shopName,
      String platformCode,
      String buyerRef,
      String buyerName,
      String buyerEmail,
      String subject,
      String body,
      String direction,
      String status,
      String salesOrderNo,
      String receivedAt) {}

  // ================================================================ 自动分配结果

  /** 一条自动分配结果。 */
  public record CrmEmailRuleAssignmentRow(
      UUID id,
      String assignmentNo,
      UUID hitId,
      UUID ruleId,
      String ruleCode,
      int ruleVersion,
      UUID emailId,
      String emailNo,
      String buyerEmail,
      String subject,
      String assignTargetType,
      String assignTargetCode,
      String status,
      String dispatchNote,
      String cancelReason,
      @Schema(nullable = true) UUID cancelledBy,
      @Schema(nullable = true) String cancelledAt,
      String assignedAt,
      String createdAt) {}

  /** 自动分配结果列表页：真分页 + 真筛选。 */
  public record CrmEmailRuleAssignmentPage(
      List<CrmEmailRuleAssignmentRow> items, int page, int size, long total) {}

  // ================================================================ 评估结果

  /** 单条评估的结果。 */
  public record CrmEmailRuleEvaluateResult(
      UUID emailId,
      String emailNo,
      boolean success,
      boolean matched,
      @Schema(nullable = true) UUID hitId,
      @Schema(nullable = true) String hitNo,
      @Schema(nullable = true) String ruleCode,
      @Schema(nullable = true) String action,
      @Schema(nullable = true) String effectiveAction,
      @Schema(nullable = true) Boolean silenceRefused,
      @Schema(nullable = true) String refusalReason,
      @Schema(nullable = true) Boolean important,
      @Schema(nullable = true) String importantSource,
      @Schema(nullable = true) String classificationLabel,
      @Schema(nullable = true) UUID assignmentId,
      @Schema(nullable = true) String assignTargetCode,
      boolean idempotent,
      String reason) {}

  /** 批量评估的结果：逐条给出结论与原因。 */
  public record CrmEmailRuleEvaluateBatchResult(
      int requestedCount,
      int succeededCount,
      int failedCount,
      int matchedCount,
      int refusedCount,
      List<CrmEmailRuleEvaluateResult> items,
      String boundaryNote) {}

  /**
   * 试算结果：这条规则对这封邮件会不会命中。
   *
   * @param matched 是否命中
   * @param conditions 命中了哪些条件（**不写命中记录**）
   */
  public record CrmEmailRulePreviewResult(
      UUID ruleId,
      String ruleCode,
      UUID emailId,
      String emailNo,
      boolean matched,
      CrmEmailRuleConditionDetail conditions,
      boolean important,
      String importantSource,
      String effectiveAction,
      Boolean silenceRefused,
      String reason) {}

  // ================================================================ 统计

  /** 一档统计（动作 / 规则分组）。 */
  public record CrmEmailRuleBucket(String code, String label, long count) {}

  /** 一条规则的命中统计。 */
  public record CrmEmailRuleStat(
      UUID ruleId,
      String ruleCode,
      String ruleName,
      String action,
      int priorityOrder,
      boolean enabled,
      long hitCount,
      long refusedCount,
      long assignmentCount) {}

  /** 规则命中统计 + 动作分布 + 静默被拒数。 */
  public record CrmEmailRuleStatistics(
      long totalHitCount,
      long refusedCount,
      long importantCount,
      long pendingAssignmentCount,
      List<CrmEmailRuleBucket> byAction,
      List<CrmEmailRuleBucket> byEffectiveAction,
      List<CrmEmailRuleStat> byRule,
      String note) {}
}
