package com.starcon.crm;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * 15.2.1.2 评价回复的传输模型。
 *
 * <p>蓝图 15 卷 15.2.1.2 原文：
 *
 * <blockquote>
 *
 * 职责：回复评价（公开回复 + 部分平台的卖家评论追加），支持模板、多语言、批量回复同类评价。 输入：回复内容；目标评价。 输出：回复记录 + 平台回执。 关系：上游 = 15.2.1.1；下游 = 平台接口。 <b>差评回复是转化机会也是合规风险</b>，模板必须符合平台措辞规范。
 *
 * </blockquote>
 *
 * <p><b>建模纪律</b>：
 *
 * <ul>
 *   <li><b>「模板 + 多语言 + 批量 + 两种形态」都是真的</b>：模板版本化、多语言条目逐语言落库、
 *       批量回复真的按「同类评价」筛选并逐条落库、公开回复与卖家评论追加是两个不同的
 *       {@code replyType}（不是一个布尔开关）。</li>
 *   <li><b>🔴 只有「平台投递」如实答未接入</b>：投递端点返回 <b>409</b> 且
 *       {@code platformReceipt} 恒为空 —— 一条返回「已回复」的假回执会让
 *       「评价已经回复给买家了」看起来发生过，而现场一条真回复都没发出去。</li>
 *   <li><b>🔴 措辞规范要可配置、要版本化</b>：规范落在 {@code crm_review_reply_policy}
 *       （逐平台 × 逐形态，带版本与生效时间）；回复记录落 {@code templateId} +
 *       {@code templateVersion} + {@code templateKey} + {@code templateLocale} +
 *       {@code policyVersion} ⇒ 事后能回答「当时用的哪一版模板、当时的规范是哪一版」。
 *       平台政策会变，这是加粗句「模板必须符合平台措辞规范」的物理落点。</li>
 *   <li><b>数量一律整数</b>：长度上限 / 计数都是 integer；本模块没有金额，
 *       也没有需要 NUMERIC(19,6) 的量。</li>
 *   <li><b>时间一律带偏移量的 ISO-8601 字符串</b>（用 {@code Timestamp.valueOf(LocalDateTime)}
 *       会在东八区静默偏 −28800 秒，本仓库反复踩过）。</li>
 * </ul>
 */
public final class ReviewReplyModels {

  private ReviewReplyModels() {}

  // ================================================================ 词表

  /** 回复形态：公开回复 / 卖家评论追加（部分平台才有后者）。 */
  public static final List<String> REPLY_TYPES =
      List.of("PUBLIC_REPLY", "SELLER_COMMENT_APPEND");

  /** 评价档位：4-5 星 / 3 星 / 1-2 星。 */
  public static final List<String> RATING_SCOPES = List.of("ALL", "POSITIVE", "NEUTRAL", "NEGATIVE");

  /** 回复状态。 */
  public static final List<String> REPLY_STATUSES =
      List.of("PENDING", "DELIVERED", "REJECTED", "CANCELLED");

  /** 模板状态。 */
  public static final List<String> TEMPLATE_STATUSES = List.of("DRAFT", "ACTIVE", "ARCHIVED");

  /** 投递结果。 */
  public static final List<String> DELIVERY_OUTCOMES =
      List.of("NOT_INTEGRATED", "DELIVERED", "REJECTED", "FAILED");

  // ================================================================ 输入

  /**
   * 配置平台措辞规范（新建一版）。
   *
   * <p>⚠️ 同一（平台 × 形态）的**生效区间不允许重叠** —— 「当时生效的是哪一版」必须唯一，
   * 否则同一条回复可能被两版规范同时认领。改规范 = 发新版本（旧版自动截止）。
   */
  public record CrmReviewReplyPolicyInput(
      @NotBlank @Size(max = 32) String platformCode,
      @NotBlank @Size(max = 32) String replyType,
      @Min(0) @Max(5000) Integer minLength,
      @Min(1) @Max(5000) Integer maxLength,
      Boolean allowContactInfo,
      Boolean allowPromotion,
      Boolean allowExternalLink,
      @Min(0) @Max(10) Integer maxExclamation,
      @Min(0) @Max(10) Integer maxQuestionMarks,
      Boolean requireApology,
      @Size(max = 200) List<@Size(max = 100) String> bannedTerms,
      @NotBlank
          @Schema(description = "生效起点，例如 2026-10-01T00:00:00+08:00")
          String effectiveFrom,
      @Size(max = 500) String note) {}

  /** 模板的一条多语言条目。 */
  public record CrmReviewReplyTemplateVariantInput(
      @NotBlank
          @Schema(description = "BCP-47 语言标签，例如 en-US / zh-CN")
          @Size(max = 16)
          String locale,
      @NotBlank @Size(max = 20000) String body,
      Boolean isDefault) {}

  /**
   * 新建回复模板（落地为 <b>DRAFT</b>，草稿可编辑；发布后内容冻结）。
   *
   * <p>⚠️ {@code version} **不由调用方指定**：同一（{@code templateKey} × 平台 × 形态）下
   * 由服务层递增。改内容 = 发新版本，不是原地改。
   */
  public record CrmReviewReplyTemplateInput(
      @NotBlank @Size(max = 64) String templateKey,
      @NotBlank @Size(max = 200) String name,
      @NotBlank @Size(max = 32) String platformCode,
      @NotBlank @Size(max = 32) String replyType,
      @Size(max = 16) String ratingScope,
      @NotBlank
          @Schema(description = "生效起点，例如 2026-10-01T00:00:00+08:00")
          String effectiveFrom,
      @Size(max = 64) String effectiveTo,
      @NotEmpty @Size(max = 20) List<@Valid CrmReviewReplyTemplateVariantInput> variants,
      @Size(max = 500) String note) {}

  /** 改草稿模板（只允许改 DRAFT；已生效的模板内容冻结）。 */
  public record CrmReviewReplyTemplateUpdateInput(
      @Size(max = 200) String name,
      @Size(max = 20) List<@Valid CrmReviewReplyTemplateVariantInput> variants,
      @Size(max = 500) String note) {}

  /**
   * 写一条回复。
   *
   * <p>{@code body} 与 {@code templateId} **二选一**：给了模板就取模板该语言的正文
   * （找不到就用模板的兜底语言），没给模板就必须自己写正文 —— 两者都不给会被拒。
   */
  public record CrmReviewReplyCreateInput(
      @NotNull UUID reviewId,
      @NotBlank @Size(max = 32) String replyType,
      @Size(max = 16) String locale,
      @Size(max = 20000) String body,
      @Schema(nullable = true) UUID templateId,
      @Size(max = 500) String note) {}

  /**
   * 批量回复「同类评价」。
   *
   * <p>「同类」的判定口径写死在服务层：**同平台 × 同店铺（可选）× 同档位 × 同形态 × 同语言**。
   */
  public record CrmReviewReplyBatchInput(
      @NotBlank @Size(max = 32) String platformCode,
      @Schema(nullable = true) UUID shopId,
      @NotBlank @Size(max = 32) String replyType,
      @NotBlank @Size(max = 16) String locale,
      @NotBlank @Size(max = 16) String ratingScope,
      @NotNull UUID templateId,
      @Min(1) @Max(200) Integer limit,
      @Size(max = 500) String note) {}

  // ================================================================ 输出

  /** 能力维度：每一维都要说清「有没有、依据是什么、缺了会怎样」。 */
  public record CrmReviewReplyCapabilityDimension(
      String dimension,
      String description,
      boolean available,
      String source,
      String ownerModule,
      String impact) {}

  /** 能力声明：前端在渲染前就知道哪些动作存在，而不是靠试。 */
  public record CrmReviewReplyCapabilities(
      List<CrmReviewReplyCapabilityDimension> dimensions,
      List<String> replyTypes,
      List<String> ratingScopes,
      List<String> replyStatuses,
      List<String> templateStatuses,
      List<String> deliveryOutcomes,
      String boundaryNote,
      String highlight) {}

  /** 平台措辞规范的一行（= 加粗句「模板必须符合平台措辞规范」的载体）。 */
  public record CrmReviewReplyPolicyRow(
      UUID id,
      String policyNo,
      String platformCode,
      String replyType,
      Integer version,
      String effectiveFrom,
      @Schema(nullable = true) String effectiveTo,
      Integer minLength,
      Integer maxLength,
      boolean allowContactInfo,
      boolean allowPromotion,
      boolean allowExternalLink,
      Integer maxExclamation,
      Integer maxQuestionMarks,
      boolean requireApology,
      List<String> bannedTerms,
      String status,
      @Schema(nullable = true) String note,
      String createdAt,
      String updatedAt) {}

  /** 措辞规范分页。 */
  public record CrmReviewReplyPolicyPage(
      List<CrmReviewReplyPolicyRow> items, int page, int size, long totalElements, int totalPages) {}

  /** 模板的一条多语言条目。 */
  public record CrmReviewReplyTemplateVariant(
      UUID id,
      String locale,
      String body,
      boolean isDefault,
      String createdAt,
      String updatedAt) {}

  /** 回复模板的一行（一行 = 一个版本）。 */
  public record CrmReviewReplyTemplateRow(
      UUID id,
      String templateNo,
      String templateKey,
      Integer version,
      String name,
      String platformCode,
      String replyType,
      String ratingScope,
      String effectiveFrom,
      @Schema(nullable = true) String effectiveTo,
      String status,
      @Schema(nullable = true) String note,
      @Schema(nullable = true) UUID createdBy,
      String createdAt,
      String updatedAt) {}

  /** 模板详情 = 模板本体 + 它的多语言条目。 */
  public record CrmReviewReplyTemplateDetail(
      CrmReviewReplyTemplateRow template, List<CrmReviewReplyTemplateVariant> variants) {}

  /** 模板分页。 */
  public record CrmReviewReplyTemplatePage(
      List<CrmReviewReplyTemplateRow> items, int page, int size, long totalElements, int totalPages) {}

  /** 一条回复记录。 */
  public record CrmReviewReplyRow(
      UUID id,
      String replyNo,
      UUID reviewId,
      String reviewNo,
      UUID shopId,
      String shopName,
      String platformCode,
      Integer reviewRating,
      String reviewScope,
      String replyType,
      String locale,
      String body,
      @Schema(nullable = true) UUID templateId,
      @Schema(nullable = true) Integer templateVersion,
      @Schema(nullable = true) String templateKey,
      @Schema(nullable = true) String templateLocale,
      @Schema(nullable = true) Integer policyVersion,
      @Schema(nullable = true) UUID batchId,
      @Schema(nullable = true) String batchNo,
      String status,
      @Schema(nullable = true) String platformReplyId,
      @Schema(nullable = true) String platformReceipt,
      @Schema(nullable = true) String deliveredAt,
      @Schema(nullable = true) String lastDeliveryNote,
      @Schema(nullable = true) String note,
      @Schema(nullable = true) UUID createdBy,
      String createdAt,
      String updatedAt) {}

  /** 回复记录分页。 */
  public record CrmReviewReplyPage(
      List<CrmReviewReplyRow> items, int page, int size, long totalElements, int totalPages) {}

  /** 一次投递尝试（= 蓝图输出句里的「平台回执」的载体）。 */
  public record CrmReviewReplyDeliveryRow(
      UUID id,
      String deliveryNo,
      UUID replyId,
      String replyNo,
      Integer attemptNo,
      String platformCode,
      String outcome,
      @Schema(nullable = true) String platformReplyId,
      @Schema(nullable = true) String platformReceipt,
      String attemptedAt,
      @Schema(nullable = true) String note) {}

  /** 单条回复详情 = 回复本体 + 投递历史。 */
  public record CrmReviewReplyDetail(
      CrmReviewReplyRow reply, List<CrmReviewReplyDeliveryRow> deliveries) {}

  /** 批量回复批次的一行。 */
  public record CrmReviewReplyBatchRow(
      UUID id,
      String batchNo,
      String platformCode,
      @Schema(nullable = true) UUID shopId,
      String replyType,
      String locale,
      @Schema(nullable = true) UUID templateId,
      @Schema(nullable = true) Integer templateVersion,
      @Schema(nullable = true) String templateKey,
      String ratingScope,
      int targetCount,
      int createdCount,
      int skippedCount,
      String status,
      @Schema(nullable = true) String note,
      @Schema(nullable = true) UUID createdBy,
      String createdAt,
      String updatedAt) {}

  /** 批次详情 = 批次本体 + 该批次下的回复 + 被跳过的评价与原因。 */
  public record CrmReviewReplyBatchDetail(
      CrmReviewReplyBatchRow batch,
      List<CrmReviewReplyRow> replies,
      List<CrmReviewReplySkipped> skipped) {}

  /** 批量回复时被跳过的一条评价与原因。 */
  public record CrmReviewReplySkipped(String reviewId, String reviewNo, String reason) {}

  /** 批次分页。 */
  public record CrmReviewReplyBatchPage(
      List<CrmReviewReplyBatchRow> items, int page, int size, long totalElements, int totalPages) {}

  /**
   * 批量回复的结果。
   *
   * <p>🔴 {@code createdCount} 与 {@code skipped} 必须**同时**看：只报成功数会让人以为
   * 「这一批全都回上了」，而被跳过的那几条其实一条没回。
   */
  public record CrmReviewReplyBatchResult(
      CrmReviewReplyBatchRow batch,
      List<CrmReviewReplyRow> replies,
      List<CrmReviewReplySkipped> skipped,
      String note) {}

  /**
   * 一次投递尝试的结果。
   *
   * <p>🔴 {@code available=false} 时 {@code reason} 必填 —— 界面**必须**显示它，
   * 绝不显示空白（空白会被读成「已投递成功但没有回执」）。
   */
  public record CrmReviewReplyDeliveryResult(
      CrmReviewReplyDeliveryRow delivery,
      boolean available,
      String reason,
      String replyStatus,
      String note) {}
}
