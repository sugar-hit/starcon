package com.starcon.crm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * 15.1.1.3 消息分类的传输模型。
 *
 * <p>蓝图 15 卷 15.1.1.3 原文：
 *
 * <blockquote>
 *
 * 职责：对消息自动分类（售前咨询、物流查询、退货退款、投诉、平台合规、垃圾信息），驱动自动回复与优先级，支持人工纠正以改进分类。 输入：消息内容；分类规则（关键词 + 平台类型 + 历史纠正数据）。 输出：分类标签 + 优先级 + 是否可自动回复。 关系：上游 = 15.1.1.1；下游 = 15.5.1 自动回复、1.3 预警。 <b>人工纠正要回流为训练样本</b>，否则分类准确率永远上不去。
 *
 * </blockquote>
 *
 * <p><b>建模纪律</b>：
 *
 * <ul>
 *   <li><b>结论与纠正分开</b>：机器结论（{@link CrmMessageClassificationRow}）与人工纠正
 *       （{@link CrmMessageClassificationFeedbackRow}）是**两张表**，纠正**只追加**、
 *       **不回写**结论 —— 事后能逐条比对「机器说了什么 / 人改成了什么」。</li>
 *   <li><b>纠正自带机器预测快照</b>：{@code predictedLabel} / {@code predictedPriority} /
 *       {@code predictedRuleCode} 是**纠正当时**的机器输出；只有正确答案的训练样本
 *       学不出「错在哪」。</li>
 *   <li><b>人工优先级不被覆写</b>：结论同时给 {@code suggestedPriority}（建议）、
 *       {@code manualPriority}（人工值快照）、{@code effectivePriority}（= 人工值）
 *       与 {@code priorityConflict}（是否分歧）—— 只记录，不改写。</li>
 *   <li><b>命中依据必须能复核</b>：{@link CrmMessageClassificationMatchBasis} 记下
 *       「命中了哪条规则的哪个关键词、在哪个字段命中、哪条规则赢了」。</li>
 *   <li><b>未交付的下游不编</b>：{@code autoReplyEligible} 是**本模块的判定结果字段**，
 *       不代表 15.5.1 自动回复已存在（能力声明里如实标 available=false）。</li>
 * </ul>
 */
public final class MessageClassificationModels {

  private MessageClassificationModels() {}

  // ================================================================ 词表

  /** 分类标签（含兜底的 UNCLASSIFIED）。与 V301 的 CHECK 同词表。 */
  public static final List<String> LABELS =
      List.of(
          "PRE_SALE",
          "LOGISTICS",
          "RETURN_REFUND",
          "COMPLAINT",
          "PLATFORM_COMPLIANCE",
          "SPAM",
          "UNCLASSIFIED");

  /** 规则可指向的标签（六类业务类别；**不含** UNCLASSIFIED —— 未分类是兜底不是类别）。 */
  public static final List<String> RULE_LABELS =
      List.of("PRE_SALE", "LOGISTICS", "RETURN_REFUND", "COMPLAINT", "PLATFORM_COMPLIANCE", "SPAM");

  /** 优先级。与 crm_message.priority 同词表。 */
  public static final List<String> PRIORITIES = List.of("LOW", "NORMAL", "HIGH", "URGENT");

  /** 关键词命中方式。 */
  public static final List<String> MATCH_MODES = List.of("ANY", "ALL");

  // ================================================================ 输入

  /**
   * 一条分类规则（新建 / 修改共用）。
   *
   * <p>⚠️ {@code keywords} **必填且非空**：一条没有关键词的规则永远不会命中，
   * 却会占掉一个顺序位（比靠前的规则先被检查）—— 那是一条**看起来配了、其实没生效**的规则。
   */
  public record CrmMessageClassificationRuleInput(
      @NotBlank @Size(max = 64)
          @Schema(description = "规则编码（同租户内唯一，建后不可改）")
          String ruleCode,
      @NotBlank @Size(max = 200) @Schema(description = "规则名称") String ruleName,
      @NotNull @PositiveOrZero
          @Schema(description = "显式顺序：数字小的先命中；并列时按 rule_code")
          Integer priorityOrder,
      @NotBlank
          @Pattern(
              regexp = "PRE_SALE|LOGISTICS|RETURN_REFUND|COMPLAINT|PLATFORM_COMPLIANCE|SPAM",
              message = "规则只能指向六个业务类别之一（未分类是兜底，不是类别）")
          String targetLabel,
      @NotBlank @Pattern(regexp = "LOW|NORMAL|HIGH|URGENT")
          @Schema(description = "命中后给出的建议优先级（建议值；不覆写人工设过的优先级）")
          String suggestedPriority,
      @Schema(description = "命中后是否可自动回复（15.5.1 自动回复未交付 ⇒ 只是判定结果）")
          Boolean autoReplyEligible,
      @NotEmpty
          @Schema(description = "关键词（必填非空）：命中方式 ANY = 任一命中，ALL = 全部命中")
          List<@NotBlank @Size(max = 100) String> keywords,
      @Schema(nullable = true, description = "命中方式：ANY（默认）· ALL") String matchMode,
      @Schema(nullable = true, description = "适用平台码（空 = 全部平台）")
          List<@Size(max = 32) String> platformCodes,
      @Schema(nullable = true, description = "适用消息类型（空 = 全部类型）")
          List<@Size(max = 24) String> messageTypes,
      @Schema(nullable = true, description = "是否启用（停用而不删除：历史命中依据要查得到）")
          Boolean enabled,
      @Size(max = 500) String note) {}

  /** 对一条消息发起分类。 */
  public record CrmMessageClassificationClassifyInput(
      @NotNull @Schema(description = "要分类的消息（15.1.1.1 的 crm_message.id）") UUID messageId) {}

  /** 批量分类：逐条给出结论与原因，失败的条目不会被悄悄跳过。 */
  public record CrmMessageClassificationBatchInput(
      @NotEmpty @Size(max = 200) List<UUID> messageIds) {}

  /**
   * 人工纠正（= 训练样本的来源）。
   *
   * <p>🔴 {@code reason} **必填**：没有理由的纠正没法复核，也没法当训练样本用。
   */
  public record CrmMessageClassificationFeedbackInput(
      @NotNull @Schema(description = "被纠正的消息 id") UUID messageId,
      @NotBlank
          @Pattern(
              regexp = "PRE_SALE|LOGISTICS|RETURN_REFUND|COMPLAINT|PLATFORM_COMPLIANCE|SPAM|UNCLASSIFIED")
          @Schema(description = "人工判定的标签")
          String correctedLabel,
      @NotBlank @Pattern(regexp = "LOW|NORMAL|HIGH|URGENT")
          @Schema(description = "人工判定的优先级")
          String correctedPriority,
      @Schema(nullable = true, description = "人工判定后是否可自动回复") Boolean correctedAutoReplyEligible,
      @NotBlank @Size(max = 1000)
          @Schema(description = "纠正理由（必填）：训练样本要能复核")
          String reason) {}

  // ================================================================ 能力声明

  /** 能力声明的一维：有没有、依据是什么、缺了会怎样。 */
  public record CrmMessageClassificationCapabilityDimension(
      String dimension, String description, boolean available, String source, String ownerModule,
      String impact) {}

  /** 能力声明：前端在渲染前就知道哪些动作存在，而不是靠试。 */
  public record CrmMessageClassificationCapabilities(
      List<CrmMessageClassificationCapabilityDimension> dimensions,
      List<String> labels,
      List<String> ruleLabels,
      List<String> priorities,
      List<String> matchModes,
      String boundaryNote,
      String highlight) {}

  /** 筛选词表（标签与优先级是 DB 层允许的取值域；平台与消息类型来自本租户真实数据）。 */
  public record CrmMessageClassificationOptions(
      List<String> labels,
      List<String> ruleLabels,
      List<String> priorities,
      List<String> matchModes,
      List<String> platformCodes,
      List<String> messageTypes,
      String note) {}

  // ================================================================ 规则

  /** 一条分类规则。 */
  public record CrmMessageClassificationRuleRow(
      UUID id,
      String ruleCode,
      String ruleName,
      int priorityOrder,
      String targetLabel,
      String suggestedPriority,
      boolean autoReplyEligible,
      List<String> keywords,
      String matchMode,
      List<String> platformCodes,
      List<String> messageTypes,
      boolean enabled,
      String note,
      String createdAt,
      String updatedAt) {}

  /** 规则列表页：真分页（LIMIT/OFFSET + 独立 COUNT(*)）。 */
  public record CrmMessageClassificationRulePage(
      List<CrmMessageClassificationRuleRow> items, int page, int size, long total) {}

  // ================================================================ 命中依据

  /** 一个关键词的命中位置。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record CrmMessageClassificationKeywordHit(String keyword, String field) {}

  /**
   * 一条规则的命中情况。
   *
   * @param won 是否是**最终生效**的那条规则（多条命中时按 priority_order 取第一条）
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record CrmMessageClassificationRuleMatch(
      UUID ruleId,
      String ruleCode,
      String ruleName,
      int ruleOrder,
      String targetLabel,
      boolean won,
      List<CrmMessageClassificationKeywordHit> keywords) {}

  /**
   * 完整命中依据（落库在 jsonb 列 {@code match_basis} 上）。
   *
   * @param winningRuleId 最终生效的规则 id（没有规则命中时为 null）
   * @param matches 所有命中过的规则（不只是赢家）—— 只记赢家就看不出「为什么不是它」
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record CrmMessageClassificationMatchBasis(
      @Schema(nullable = true) UUID winningRuleId,
      @Schema(nullable = true) String winningRuleCode,
      List<CrmMessageClassificationRuleMatch> matches) {}

  // ================================================================ 结论

  /** 一条分类结论（含消息快照，前端不必再回查上游）。 */
  public record CrmMessageClassificationRow(
      UUID id,
      String classificationNo,
      UUID messageId,
      String messageNo,
      String subject,
      String platformCode,
      String buyerReference,
      String orderReference,
      String label,
      String suggestedPriority,
      String manualPriority,
      String effectivePriority,
      boolean priorityConflict,
      boolean autoReplyEligible,
      @Schema(nullable = true) UUID matchedRuleId,
      String matchedRuleCode,
      String matchedRuleName,
      CrmMessageClassificationMatchBasis matchBasis,
      String classifierVersion,
      String classifiedAt,
      String createdAt,
      String updatedAt,
      long correctionCount,
      @Schema(nullable = true) String latestCorrectedLabel) {}

  /** 分类结论列表页：真分页 + 真筛选 + 与分页无关的全量汇总。 */
  public record CrmMessageClassificationPage(
      List<CrmMessageClassificationRow> items,
      int page,
      int size,
      long total,
      CrmMessageClassificationSummary summary) {}

  /** 分类结论汇总（与分页无关的全量口径）。 */
  public record CrmMessageClassificationSummary(
      long totalCount,
      long conflictCount,
      long autoReplyEligibleCount,
      long correctedCount,
      List<CrmMessageClassificationBucket> byLabel,
      List<CrmMessageClassificationBucket> bySuggestedPriority) {}

  /** 一档统计（标签 / 优先级分组）。 */
  public record CrmMessageClassificationBucket(String code, String label, long count) {}

  // ================================================================ 纠正

  /** 一条人工纠正（训练样本的来源，只追加）。 */
  public record CrmMessageClassificationFeedbackRow(
      UUID id,
      UUID messageId,
      UUID classificationId,
      String predictedLabel,
      String predictedPriority,
      @Schema(nullable = true) UUID predictedRuleId,
      String predictedRuleCode,
      String correctedLabel,
      String correctedPriority,
      boolean correctedAutoReplyEligible,
      String reason,
      UUID correctedBy,
      String correctedAt) {}

  /** 纠正记录列表页：真分页 + 真筛选。 */
  public record CrmMessageClassificationFeedbackPage(
      List<CrmMessageClassificationFeedbackRow> items, int page, int size, long total) {}

  /**
   * 一个训练样本：机器当时的判断 + 人工纠正 + 消息正文。
   *
   * <p>🔴 「人工纠正要回流为训练样本」在本模型里落成：**正文与预测都带上**，
   * 少了正文就只剩标签对，训不出「哪句话被判成了什么」。
   */
  public record CrmMessageClassificationTrainingSample(
      UUID feedbackId,
      UUID messageId,
      String messageNo,
      String subject,
      String body,
      String platformCode,
      String messageType,
      String buyerReference,
      String orderReference,
      String predictedLabel,
      String predictedPriority,
      String predictedRuleCode,
      String correctedLabel,
      String correctedPriority,
      boolean correctedAutoReplyEligible,
      String reason,
      UUID correctedBy,
      String correctedAt,
      boolean labelAgreed) {}

  /** 训练样本列表页：真分页 + 真筛选。 */
  public record CrmMessageClassificationTrainingSamplePage(
      List<CrmMessageClassificationTrainingSample> items, int page, int size, long total) {}

  // ================================================================ 统计

  /** 一条规则的命中统计。 */
  public record CrmMessageClassificationRuleStat(
      UUID ruleId,
      String ruleCode,
      String ruleName,
      String targetLabel,
      int priorityOrder,
      boolean enabled,
      long hitCount,
      long correctionCount,
      long disagreementCount) {}

  /**
   * 分类统计（规则命中 + 标签分布 + 冲突 + 纠正）。
   *
   * @param correctionAgreementRate 纠正与机器判断一致的比例（BigDecimal，**不是**浮点；
   *     AGENTS.md：金额与数量不用浮点）
   */
  public record CrmMessageClassificationStatistics(
      long totalCount,
      long conflictCount,
      long autoReplyEligibleCount,
      long correctionCount,
      long correctionAgreementCount,
      BigDecimal correctionAgreementRate,
      List<CrmMessageClassificationBucket> byLabel,
      List<CrmMessageClassificationBucket> bySuggestedPriority,
      List<CrmMessageClassificationRuleStat> byRule,
      String note) {}

  // ================================================================ 详情

  /** 消息快照（分类结论挂在哪条消息上）。 */
  public record CrmMessageClassificationMessageView(
      UUID id,
      String messageNo,
      String shopName,
      String platformCode,
      String buyerReference,
      String orderReference,
      String subject,
      String body,
      String messageType,
      String direction,
      String manualPriority,
      String platformTime) {}

  /**
   * 单条分类详情 = 结论 + 命中依据 + 消息正文 + 纠正历史。
   *
   * <p>🔴 「人工值 / 建议值 / 是否冲突」三个字段都在这里 —— 冲突时页面必须把
   * **两个值都**显示出来，不能只显示一个。
   */
  public record CrmMessageClassificationDetail(
      CrmMessageClassificationRow classification,
      CrmMessageClassificationMessageView message,
      List<CrmMessageClassificationFeedbackRow> feedback) {}

  // ================================================================ 分类动作

  /** 单条分类的结果。 */
  public record CrmMessageClassificationClassifyResult(
      UUID messageId,
      String messageNo,
      boolean success,
      @Schema(nullable = true) UUID classificationId,
      @Schema(nullable = true) String classificationNo,
      @Schema(nullable = true) String label,
      @Schema(nullable = true) String suggestedPriority,
      @Schema(nullable = true) String manualPriority,
      @Schema(nullable = true) String effectivePriority,
      @Schema(nullable = true) Boolean priorityConflict,
      @Schema(nullable = true) Boolean autoReplyEligible,
      @Schema(nullable = true) String matchedRuleCode,
      @Schema(nullable = true) String classifiedAt,
      String reason) {}

  /** 批量分类的结果：逐条给出结论与原因。 */
  public record CrmMessageClassificationBatchResult(
      int requestedCount,
      int succeededCount,
      int failedCount,
      List<CrmMessageClassificationClassifyResult> items,
      String boundaryNote) {}

  /** 纠正结果（含机器预测快照，页面当场能显示「机器判成什么 / 人改成什么」）。 */
  public record CrmMessageClassificationFeedbackResult(
      CrmMessageClassificationFeedbackRow feedback,
      String note) {}

  /** 规则的新增 / 修改 / 删除结果。 */
  public record CrmMessageClassificationRuleResult(
      CrmMessageClassificationRuleRow rule, String note) {}
}
