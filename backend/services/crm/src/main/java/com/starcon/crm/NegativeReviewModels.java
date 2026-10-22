package com.starcon.crm;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * 15.2.1.3 差评处理的传输模型。
 *
 * <p>蓝图 15 卷 15.2.1.3 原文：
 *
 * <blockquote>
 *
 * 职责：差评专项处理：标记原因（产品/物流/服务/误判）、判定是否申诉（部分平台支持）、联系买家解决后请求修改、跟踪解决率。 输入：差评内容；处理动作；买家沟通记录。 输出：处理记录 + 申诉任务 + 解决状态。 关系：上游 = 15.2.1.1；下游 = 15.1.1.2 联系买家、11 售后（解决问题）。 <b>解决差评的正确路径是先解决买家问题再请求修改，直接申诉容易被驳回且消耗申诉配额。</b>
 *
 * </blockquote>
 *
 * <p><b>建模纪律</b>：
 *
 * <ul>
 *   <li><b>加粗句落成服务端状态机，不只是文案</b>：没走完「联系买家」就不允许请求修改 /
 *       建申诉任务 —— 服务层拒绝并给明确原因（见 {@code NegativeReviewService}）。
 *   <li><b>「解决率」是服务端算的派生读</b>：{@link CrmNegativeReviewStats} 里的分子分母口径
 *       由服务层给出（{@code resolutionRateBasis} / {@code resolutionRateNumerator}），
 *       前端**只原样展示**，不做任何前端算术。
 *   <li><b>「判定依据」与「判定结果」一起落</b>：只给「可申诉 / 不可申诉」不给依据，
 *       这条判定永远无法复核。
 *   <li><b>不编下游 id</b>：下游 11 售后**未交付** ⇒ 转交只落**意图**（原因必填），
 *       本模型里**没有**任何「售后单据 id」字段。
 *   <li><b>时间一律带偏移量的 ISO-8601 字符串</b>（用 {@code Timestamp.valueOf(LocalDateTime)}
 *       会在东八区静默偏 −28800 秒，本仓库反复踩过）。
 *   <li><b>数量与比例一律 BigDecimal / integer</b>，禁浮点。
 * </ul>
 */
public final class NegativeReviewModels {

  private NegativeReviewModels() {}

  // ================================================================ 词表

  /** 原因分类（蓝图点名的四类）。 */
  public static final List<String> CAUSE_CODES =
      List.of("PRODUCT", "LOGISTICS", "SERVICE", "MISJUDGMENT");

  /** 处理状态。 */
  public static final List<String> CASE_STATUSES =
      List.of("OPEN", "IN_PROGRESS", "RESOLVED", "CLOSED");

  /** 解决状态（= 蓝图输出句里的「解决状态」）。 */
  public static final List<String> RESOLUTION_STATUSES =
      List.of("UNRESOLVED", "RESOLVED", "CLOSED_UNRESOLVED");

  /** 处理动作类型。 */
  public static final List<String> ACTION_TYPES =
      List.of(
          "CONTACT_BUYER",
          "REQUEST_MODIFICATION",
          "APPEAL_SUBMISSION",
          "APPEAL_RESULT",
          "APPEAL_CANCEL",
          "AFTER_SALES_HANDOFF",
          "RESOLVE",
          "CLOSE",
          "NOTE");

  /** 动作的触达方式。 */
  public static final List<String> TRANSPORTS = List.of("PLATFORM_API", "MANUAL_HANDOFF", "INTERNAL");

  /** 动作结果。 */
  public static final List<String> OUTCOMES = List.of("SUCCEEDED", "FAILED", "PENDING");

  /** 申诉类型。 */
  public static final List<String> APPEAL_TYPES =
      List.of("REMOVAL", "MODIFICATION", "POLICY_VIOLATION");

  /** 申诉任务状态。 */
  public static final List<String> APPEAL_STATUSES =
      List.of("DRAFT", "SUBMITTED", "APPROVED", "REJECTED", "CANCELLED");

  /** 规则作用域。 */
  public static final List<String> RULE_SCOPES = List.of("PLATFORM", "CAUSE");

  // ================================================================ 输入

  /** 新建一条差评处理规则（平台策略或原因规则）。 */
  public record CrmNegativeReviewRuleInput(
      @NotBlank
          @Schema(
              description = "PLATFORM（逐平台的差评处理策略）或 CAUSE（逐「平台 × 原因」的规则）")
          String ruleScope,
      @NotBlank String platformCode,
      @Schema(nullable = true, description = "CAUSE 作用域必填；PLATFORM 作用域必须留空")
          String causeCode,
      @NotBlank @Size(max = 200) String label,
      @Size(max = 1000) String description,
      @Min(1)
          @Max(5)
          @Schema(
              nullable = true,
              description = "差评判定阈值：rating <= 该阈值即视为差评（业务口径默认 2）")
          Integer negativeRatingThreshold,
      @Schema(nullable = true, description = "平台是否支持申诉（部分平台不支持）")
          Boolean appealSupported,
      @Schema(nullable = true, description = "是否必须先联系买家（蓝图加粗句的落点）")
          Boolean contactBuyerRequired,
      @Min(0) @Schema(nullable = true, description = "申诉配额（每周期），0 = 不开放申诉")
          Integer appealQuotaLimit,
      @Size(max = 16) String quotaPeriod,
      @Min(0) @Max(365) @Schema(nullable = true, description = "申诉时限（天）")
          Integer appealWindowDays,
      @Schema(nullable = true, description = "该原因默认是否可申诉（CAUSE 作用域）")
          Boolean appealEligible,
      @Size(max = 1000) String appealBasisTemplate,
      @Schema(nullable = true, description = "该原因是否要求留证据（CAUSE 作用域）")
          Boolean evidenceRequired,
      @Schema(nullable = true, description = "展示优先级（数字小 = 靠前）")
          Integer priority,
      @Size(max = 500) String note) {}

  /** 更新一条规则（原地改 = 版本 +1）。 */
  public record CrmNegativeReviewRuleUpdateInput(
      @Size(max = 200) String label,
      @Size(max = 1000) String description,
      @Min(1) @Max(5) Integer negativeRatingThreshold,
      Boolean appealSupported,
      Boolean contactBuyerRequired,
      @Min(0) Integer appealQuotaLimit,
      @Size(max = 16) String quotaPeriod,
      @Min(0) @Max(365) Integer appealWindowDays,
      Boolean appealEligible,
      @Size(max = 1000) String appealBasisTemplate,
      Boolean evidenceRequired,
      Integer priority,
      @Schema(nullable = true, description = "ACTIVE / ARCHIVED")
          String status,
      @Size(max = 500) String note) {}

  /** 为一条差评建处理单。 */
  public record CrmNegativeReviewCaseCreateInput(
      @NotNull @Schema(description = "目标评价（15.2.1.1 的评价 id）") UUID reviewId,
      @Size(max = 500) String note) {}

  /** 标记原因分类（顺带按规则判定是否可申诉）。 */
  public record CrmNegativeReviewClassifyInput(
      @NotBlank
          @Schema(
              description = "原因分类：PRODUCT / LOGISTICS / SERVICE / MISJUDGMENT")
          String causeCode,
      @Size(max = 1000) String causeNote) {}

  /**
   * 记录一个处理动作。
   *
   * <p>🔴 {@code transport=PLATFORM_API} 且动作需要触达买家（联系买家 / 请求修改）时，
   * 服务层会先问平台适配器；适配器未接入 ⇒ 返回 <b>409</b> 且<b>一行都不写</b>。
   */
  public record CrmNegativeReviewActionInput(
      @NotBlank String actionType,
      @Schema(nullable = true, description = "PLATFORM_API / MANUAL_HANDOFF / INTERNAL")
          String transport,
      @NotBlank @Schema(description = "SUCCEEDED / FAILED / PENDING") String outcome,
      @Size(max = 500) String outcomeNote,
      @Size(max = 500) String summary,
      @Size(max = 20000) String detail,
      @Schema(
              nullable = true,
              description = "售后转交原因（AFTER_SALES_HANDOFF 必填；本模块不落下游单据 id）")
          String afterSalesReason,
      @Schema(
              nullable = true,
              description = "动作发生时刻，例如 2026-10-10T08:00:00+08:00")
          String occurredAt,
      @Size(max = 200) String operatorName) {}

  /** 建一个申诉任务。 */
  public record CrmNegativeReviewAppealCreateInput(
      @NotBlank
          @Schema(
              description = "REMOVAL（申请移除差评）/ MODIFICATION（申请修改差评内容）/ POLICY_VIOLATION")
          String appealType,
      @Size(max = 24) String causeCode,
      @NotBlank @Size(max = 2000) String reason,
      @Size(max = 20000) String content,
      @Size(max = 500) String note) {}

  /** 人工交接登记：运营已在平台侧实际提交申诉。 */
  public record CrmNegativeReviewAppealSubmittedInput(
      @NotBlank
          @Schema(
              nullable = true,
              description = "平台侧实际提交时刻，例如 2026-10-10T08:00:00+08:00")
          String submittedAt,
      @Size(max = 128) String platformReference,
      @Size(max = 500) String note) {}

  /** 人工交接登记：平台对申诉的判定（通过 / 驳回）。 */
  public record CrmNegativeReviewAppealOutcomeInput(
      @NotBlank @Schema(description = "APPROVED（通过）/ REJECTED（驳回）") String result,
      @NotBlank
          @Schema(
              nullable = true,
              description = "平台判定时刻，例如 2026-10-12T08:00:00+08:00")
          String decidedAt,
      @Size(max = 20000) String platformReceipt,
      @Size(max = 1000) String reason) {}

  /** 撤回申诉任务（撤回归还申诉配额）。 */
  public record CrmNegativeReviewAppealCancelInput(
      @NotBlank @Size(max = 500) String reason) {}

  // ================================================================ 输出

  /** 能力维度：每一维都要说清「有没有、依据是什么、缺了会怎样」。 */
  public record CrmNegativeReviewCapabilityDimension(
      String dimension,
      String description,
      boolean available,
      String source,
      String ownerModule,
      String impact) {}

  /** 能力声明：前端在渲染前就知道哪些动作存在，而不是靠试。 */
  public record CrmNegativeReviewCapabilities(
      List<CrmNegativeReviewCapabilityDimension> dimensions,
      List<String> causeCodes,
      List<String> caseStatuses,
      List<String> resolutionStatuses,
      List<String> actionTypes,
      List<String> transports,
      List<String> outcomes,
      List<String> appealTypes,
      List<String> appealStatuses,
      List<String> ruleScopes,
      String boundaryNote,
      String highlight) {}

  /** 一条差评处理规则。 */
  public record CrmNegativeReviewRuleRow(
      UUID id,
      String ruleNo,
      String ruleScope,
      String platformCode,
      @Schema(nullable = true) String causeCode,
      String label,
      @Schema(nullable = true) String description,
      Integer negativeRatingThreshold,
      boolean appealSupported,
      boolean contactBuyerRequired,
      Integer appealQuotaLimit,
      String quotaPeriod,
      Integer appealWindowDays,
      boolean appealEligible,
      @Schema(nullable = true) String appealBasisTemplate,
      boolean evidenceRequired,
      Integer priority,
      Integer version,
      String status,
      String effectiveFrom,
      @Schema(nullable = true) String effectiveTo,
      @Schema(nullable = true) String note,
      @Schema(nullable = true) UUID createdBy,
      String createdAt,
      String updatedAt) {}

  /** 规则列表（真分页）。 */
  public record CrmNegativeReviewRulePage(
      List<CrmNegativeReviewRuleRow> items, int page, int size, long totalElements, int totalPages) {}

  /** 一张处理单。 */
  public record CrmNegativeReviewCaseRow(
      UUID id,
      String caseNo,
      UUID reviewId,
      String reviewNo,
      Integer reviewRating,
      @Schema(nullable = true) String reviewTitle,
      @Schema(nullable = true) String reviewContent,
      @Schema(nullable = true) String reviewExternalId,
      UUID shopId,
      String shopName,
      String platformCode,
      @Schema(nullable = true) String buyerRef,
      @Schema(nullable = true) String buyerName,
      @Schema(nullable = true) String salesOrderNo,
      @Schema(nullable = true) UUID policyRuleId,
      @Schema(nullable = true) Integer policyRuleVersion,
      Integer negativeRatingThreshold,
      boolean appealSupported,
      boolean contactBuyerRequired,
      Integer appealQuotaLimit,
      String quotaPeriod,
      Integer appealWindowDays,
      @Schema(nullable = true) String causeCode,
      @Schema(nullable = true) String causeLabel,
      @Schema(nullable = true) String causeNote,
      @Schema(nullable = true) UUID causeRuleId,
      @Schema(nullable = true) Integer causeRuleVersion,
      boolean appealEligible,
      @Schema(nullable = true) String appealBasis,
      @Schema(nullable = true) String appealAssessedAt,
      String caseStatus,
      String resolutionStatus,
      @Schema(nullable = true) String resolutionNote,
      @Schema(nullable = true) String resolutionAt,
      @Schema(nullable = true) UUID assignedTo,
      @Schema(nullable = true) String note,
      @Schema(nullable = true) UUID createdBy,
      String createdAt,
      String updatedAt) {}

  /** 处理单列表（真分页 + 真筛选）。 */
  public record CrmNegativeReviewCasePage(
      List<CrmNegativeReviewCaseRow> items, int page, int size, long totalElements, int totalPages) {}

  /**
   * 一条**还没建处理单**的差评（来自 15.2.1.1 的评价列表）。
   *
   * <p>「差评清单」与「处理单清单」是**两件事**：处理单是**我们已经开始处理**的那些，
   * 候选清单是**还没人接手**的那些 —— 没有候选清单，第一张处理单永远建不出来。
   */
  public record CrmNegativeReviewCandidateRow(
      UUID reviewId,
      String reviewNo,
      Integer rating,
      @Schema(nullable = true) String title,
      @Schema(nullable = true) String content,
      UUID shopId,
      String shopName,
      String platformCode,
      @Schema(nullable = true) String productRef,
      @Schema(nullable = true) String productTitle,
      @Schema(nullable = true) String variantRef,
      @Schema(nullable = true) String buyerRef,
      @Schema(nullable = true) String buyerName,
      @Schema(nullable = true) String salesOrderNo,
      String platformReviewedAt,
      boolean policyConfigured,
      @Schema(nullable = true) Integer negativeRatingThreshold,
      boolean caseExists,
      @Schema(nullable = true) UUID caseId,
      @Schema(nullable = true) String caseNo) {}

  /** 候选差评分页（真分页）。 */
  public record CrmNegativeReviewCandidatePage(
      List<CrmNegativeReviewCandidateRow> items,
      int page,
      int size,
      long totalElements,
      int totalPages) {}

  /** 一条处理动作。 */
  public record CrmNegativeReviewActionRow(
      UUID id,
      String actionNo,
      UUID caseId,
      String caseNo,
      String actionType,
      String transport,
      String outcome,
      @Schema(nullable = true) String outcomeNote,
      @Schema(nullable = true) String summary,
      @Schema(nullable = true) String detail,
      @Schema(nullable = true) String afterSalesReason,
      @Schema(nullable = true) UUID appealId,
      @Schema(nullable = true) String appealNo,
      @Schema(nullable = true) UUID operatorId,
      @Schema(nullable = true) String operatorName,
      String actionAt,
      String createdAt) {}

  /** 一个申诉任务。 */
  public record CrmNegativeReviewAppealRow(
      UUID id,
      String appealNo,
      UUID caseId,
      String caseNo,
      UUID reviewId,
      String reviewNo,
      UUID shopId,
      String platformCode,
      String appealType,
      String causeCode,
      String reason,
      @Schema(nullable = true) String content,
      String appealStatus,
      String transport,
      @Schema(nullable = true) String submittedAt,
      @Schema(nullable = true) String platformReference,
      @Schema(nullable = true) String platformReceipt,
      @Schema(nullable = true) String decidedAt,
      @Schema(nullable = true) String decidedResult,
      @Schema(nullable = true) String decidedReason,
      @Schema(nullable = true) String cancelledAt,
      @Schema(nullable = true) String cancelReason,
      String quotaPeriodKey,
      Integer quotaIndex,
      Integer quotaLimit,
      Integer quotaUsedBefore,
      @Schema(nullable = true) UUID createdBy,
      String createdAt,
      String updatedAt) {}

  /**
   * 关联订单上下文（**跨服务只读**，来自上游授权 HTTP 端口）。
   *
   * <p>🔴 {@code available=false} 时 {@code reason} 必填，页面**必须把它显示出来** ——
   * 客服看到一片空白会以为这条差评没有关联订单，而真相可能是「上游读不到」。
   */
  public record CrmNegativeReviewOrderContext(
      boolean available,
      String reason,
      @Schema(nullable = true) String salesOrderNo,
      @Schema(nullable = true) String orderNo,
      @Schema(nullable = true) String orderStatus,
      @Schema(nullable = true) String deliveredAt,
      @Schema(nullable = true) String deliveryState,
      @Schema(nullable = true) String carrierCode,
      @Schema(nullable = true) String caseNo) {}

  /** 处理单详情 = 处理单 + 动作流水 + 申诉任务 + 订单上下文 + 当时用的规则。 */
  public record CrmNegativeReviewDetail(
      CrmNegativeReviewCaseRow negativeReview,
      List<CrmNegativeReviewActionRow> actions,
      List<CrmNegativeReviewAppealRow> appeals,
      CrmNegativeReviewOrderContext orderContext,
      @Schema(nullable = true) CrmNegativeReviewRuleRow platformPolicy,
      @Schema(nullable = true) CrmNegativeReviewRuleRow causeRule) {}

  /** 原因分类的一档（解决率按原因拆开看）。 */
  public record CrmNegativeReviewCauseBucket(
      String causeCode, String label, long count, long resolvedCount, long appealEligibleCount) {}

  /** 处理状态的一档。 */
  public record CrmNegativeReviewStatusBucket(String status, long count) {}

  /** 申诉状态的一档。 */
  public record CrmNegativeReviewAppealBucket(String status, long count) {}

  /** 一个平台的申诉配额使用情况。 */
  public record CrmNegativeReviewQuotaBucket(
      String platformCode,
      String quotaPeriod,
      String quotaPeriodKey,
      Integer quotaLimit,
      long quotaUsed,
      @Schema(nullable = true) Long quotaRemaining) {}

  /**
   * 解决率统计（= 蓝图输出句里的「跟踪解决率」）。
   *
   * <p>🔴 全部数值**由 SQL 现算**，前端**只原样展示**（不做任何前端算术）。
   * 分子分母口径由 {@code resolutionRateBasis} / {@code resolutionRateNumerator} 写清。
   */
  public record CrmNegativeReviewStats(
      int scopeDays,
      String scopeFrom,
      String scopeTo,
      long totalCases,
      long resolvedCases,
      long unresolvedCases,
      long openCases,
      long inProgressCases,
      long closedCases,
      @Schema(nullable = true) BigDecimal resolutionRate,
      String resolutionRateBasis,
      String resolutionRateNumerator,
      long appealEligibleCases,
      long appealIneligibleCases,
      long appealedCases,
      long contactBuyerDoneCases,
      List<CrmNegativeReviewCauseBucket> byCause,
      List<CrmNegativeReviewStatusBucket> byStatus,
      List<CrmNegativeReviewAppealBucket> byAppealStatus,
      List<CrmNegativeReviewQuotaBucket> byPlatformQuota,
      String note) {}

  /**
   * 店铺目录的一行（来自上游 channel 的真实清单）+ 该平台的差评处理策略状态。
   *
   * <p>⚠️ {@code policyConfigured=false} ⇒ 该平台**还没有配置**差评处理策略，
   * 页面据此提示「先配置策略再建处理单」—— 因为**差评阈值与申诉配额都是规则**，
   * 没有规则就没有「差评」这个口径。
   */
  public record CrmNegativeReviewShopView(
      UUID id,
      String code,
      String name,
      String platformCode,
      String platformName,
      String status,
      boolean policyConfigured,
      @Schema(nullable = true) Integer negativeRatingThreshold,
      boolean appealSupported,
      boolean contactBuyerRequired,
      @Schema(nullable = true) Integer appealQuotaLimit,
      @Schema(nullable = true) String quotaPeriod,
      String policyNote) {}
}
