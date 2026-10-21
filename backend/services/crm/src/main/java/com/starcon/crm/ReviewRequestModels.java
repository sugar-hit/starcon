package com.starcon.crm;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * 15.2.2.1 Request a Review（索评）的传输模型。
 *
 * <p>蓝图 15 卷 15.2.2.1 原文：
 *
 * <blockquote>
 *
 * 职责：按规则向已签收订单的买家发起索评（平台允许范围内），控制频次避免打扰， 支持按店铺/时间/物流状态批量执行。 输入：索评规则（时点、范围、频次上限）；已签收订单集合。 输出：索评任务 + 发送记录 + 评价回收统计。 关系：上游 = 10.3.2.2 签收；下游 = 15.2.1.1 评价。 <b>索评频次必须受限</b>，过度索评会被买家投诉甚至平台处罚。
 *
 * </blockquote>
 *
 * <p><b>建模纪律</b>：
 *
 * <ul>
 *   <li><b>规则是输入，不是配置</b>：{@link CrmReviewRuleInput} 是「这一次索评用什么规则」，
 *       而 {@link CrmReviewPolicyInput} 是**租户级频次硬上限**（兜底闸门）。
 *       两者分开，因为蓝图把「索评规则」列在**输入**里，而加粗句要求的是**上限**——
 *       上限必须有一个**请求改不动**的地方兜着，否则「把规则调松」就是绕过加粗句的后门。
 *   <li><b>数量与时间一律 BigDecimal / OffsetDateTime</b>：小时数用 {@code numeric(19,6)}，
 *       时刻用带时区偏移量的 ISO-8601 字符串（用 {@code Timestamp.valueOf(LocalDateTime)}
 *       会在东八区静默偏 −28800 秒，本仓库反复踩过）。
 *   <li><b>买家标识是输入</b>：本仓没有买家档案（15.3.1.1 未交付），
 *       {@code buyerRef} 由运营按店铺提供，<b>只作频次去重键</b>。
 *       它不是买家主数据，模型层不假装它是。
 * </ul>
 */
public final class ReviewRequestModels {

  private ReviewRequestModels() {}

  // ------------------------------------------------------------ 输入

  /**
   * 一次索评用的规则快照：时点 / 冷却窗口 / 频次上限。
   *
   * <p>⚠️ 这些值**只能比租户硬上限更严**（V220 的 guard_crm_review_request_frequency
   * 会在 DB 层拒写更松的规则）。放宽上限的唯一合法路径是运营显式调整
   * {@code PUT /crm/review-requests/frequency-policy}，而那份策略的每个值本身也有
   * DB 层的硬天花板。
   */
  public record CrmReviewRuleInput(
      @NotNull
          @DecimalMin(value = "0.000001")
          @DecimalMax(value = "8760")
          @Schema(description = "时点：签收后延后多少小时才发索评")
          BigDecimal delayHours,
      @NotNull
          @DecimalMin(value = "0.000001")
          @DecimalMax(value = "8760")
          @Schema(description = "冷却窗口：同一买家两次索评至少隔多少小时")
          BigDecimal cooldownHours,
      @NotNull @Min(1) @Max(10) @Schema(description = "每买家上限（统计窗口内）")
          Integer maxPerBuyer,
      @NotNull @Min(1) @Max(5) @Schema(description = "每订单上限") Integer maxPerOrder) {}

  /**
   * 租户级频次硬上限。
   *
   * <p>这是蓝图加粗句「索评频次必须受限」的**兜底闸门**：一次请求自带的规则
   * 只能比它更严。每个值在 DB 层另有硬天花板（每买家上限最多 10 次/窗口、
   * 每订单上限最多 5 次、窗口最多 365 天、冷却与时点下限最多 8760 小时）。
   */
  public record CrmReviewPolicyInput(
      @NotNull @Min(1) @Max(10) Integer maxPerBuyerPerWindow,
      @NotNull @Min(1) @Max(365) Integer windowDays,
      @NotNull
          @DecimalMin(value = "0.000001")
          @DecimalMax(value = "8760")
          BigDecimal minCooldownHours,
      @NotNull @Min(1) @Max(5) Integer maxPerOrder,
      @NotNull
          @DecimalMin(value = "0.000001")
          @DecimalMax(value = "8760")
          BigDecimal minDelayHours,
      @NotNull @Min(1) @Max(1000) Integer maxBatchSize) {}

  /**
   * 单条发起索评。
   *
   * <p>{@code upstreamCaseId} 指向上游 **channel** 的签收确认单（10.3.2.2）。
   * 服务层会回源核对它**确实已签收**，读不到 / 没签收一律拒绝 ——
   * 本模块**不造**签收事实。
   */
  public record CrmReviewCreateInput(
      @NotNull UUID upstreamCaseId,
      @NotNull UUID shopId,
      @NotBlank @Size(max = 128) String buyerRef,
      @NotNull @Valid CrmReviewRuleInput rule,
      @Size(max = 500) String note) {}

  /**
   * 批量执行的买家绑定：一张订单对应一个买家标识。
   *
   * <p>上游确认单**不带买家维度**（本仓没有买家档案），所以批量执行时
   * 运营按订单提供买家标识；**没有绑定买家标识的候选单会被跳过并列出原因**，
   * 不会被悄悄放行。
   */
  public record CrmReviewBuyerBinding(
      @NotBlank @Size(max = 64) String salesOrderNo,
      @NotBlank @Size(max = 128) String buyerRef) {}

  /**
   * 批量执行：按店铺 / 时间 / 物流状态批量索评。
   *
   * <p>时间窗是<b>闭开区间</b> {@code [deliveredFrom, deliveredTo)}，
   * 过滤的是上游确认单的<b>签收时刻</b>（不是创建时刻）。
   */
  public record CrmReviewBatchInput(
      @NotNull UUID shopId,
      @Size(max = 64) String carrierCode,
      @Size(max = 32) String batchNo,
      @Schema(nullable = true) String deliveredFrom,
      @Schema(nullable = true) String deliveredTo,
      @NotNull @Valid List<CrmReviewBuyerBinding> buyers,
      @NotNull @Valid CrmReviewRuleInput rule,
      @Size(max = 500) String note) {}

  /**
   * 登记一次触达（发送记录）。
   *
   * <p>{@code transport} 只能填 {@code MANUAL_HANDOFF}：平台侧索评接口**未接入**
   * （capabilities 里 platform_review_api 维度 available=false），
   * 服务层会拒绝 {@code PLATFORM_API} —— 一个「返回成功回执」的假通道
   * 会让「买家已经被索评过」在数据上看起来发生过，而过度索评的代价
   * 正是买家投诉与平台处罚。
   *
   * <p>{@code outcome}：{@code QUEUED} 已登记待发 · {@code SENT} 已发出 ·
   * {@code FAILED} 失败（必须带原因）。
   */
  public record CrmReviewSendRecordInput(
      @NotBlank String transport,
      @NotBlank String outcome,
      @Size(max = 600) String failureReason,
      @Size(max = 500) String note) {}

  /**
   * 登记一条评价回收（收到的评价原文 + 关联到索评请求）。
   *
   * <p>⚠️ 下游 15.2.1.1 评价列表**未交付** ⇒ 本模块只落**回收桶**：
   * 无回复、无申诉、无标签，也**不假装**已交付评价管理。
   */
  public record CrmReviewRecoveryInput(
      @NotNull @Min(1) @Max(5) Integer rating,
      @Size(max = 4000) String content,
      Boolean hasImage,
      Boolean hasVideo,
      @NotBlank
          @Schema(description = "买家在平台上的评价时刻，例如 2026-10-10T08:00:00+08:00")
          String receivedAt,
      @Size(max = 500) String note) {}

  // ------------------------------------------------------------ 输出

  /** 能力维度：每一维都要说清「有没有、依据是什么、缺了会怎样」。 */
  public record CrmReviewCapabilityDimension(
      String dimension,
      String description,
      boolean available,
      String source,
      String ownerModule,
      String impact) {}

  /** 能力声明：前端在渲染前就知道哪些动作存在，而不是靠试。 */
  public record CrmReviewCapabilities(
      List<CrmReviewCapabilityDimension> dimensions,
      List<String> requestStatuses,
      List<String> sendStatuses,
      List<String> transports,
      List<String> recoverySources,
      List<String> rejectCodes,
      String boundaryNote,
      String highlight) {}

  /** 租户级频次硬上限。 */
  public record CrmReviewFrequencyPolicy(
      UUID id,
      Integer maxPerBuyerPerWindow,
      Integer windowDays,
      BigDecimal minCooldownHours,
      Integer maxPerOrder,
      BigDecimal minDelayHours,
      Integer maxBatchSize,
      String updatedAt) {}

  /** 一条索评请求（= 蓝图输出句里的「索评任务」）。 */
  public record CrmReviewRequestRow(
      UUID id,
      String requestNo,
      @Schema(nullable = true) UUID upstreamCaseId,
      String upstreamCaseNo,
      String upstreamWaybillNo,
      String upstreamCarrierCode,
      String upstreamSourceRef,
      UUID shopId,
      String shopName,
      String platformCode,
      String salesOrderNo,
      String buyerRef,
      String logisticsStatus,
      String deliveredAt,
      String status,
      BigDecimal ruleDelayHours,
      BigDecimal ruleCooldownHours,
      Integer ruleMaxPerBuyer,
      Integer ruleMaxPerOrder,
      String scheduledAt,
      String rejectCode,
      String rejectReason,
      @Schema(nullable = true) UUID batchId,
      UUID createdBy,
      String note,
      String createdAt,
      String updatedAt,
      Long sendRecordCount,
      Long recoveryCount) {}

  /** 一条发送记录（= 蓝图输出句里的「发送记录」）。 */
  public record CrmReviewSendRecord(
      UUID id,
      UUID requestId,
      String requestNo,
      String transport,
      String status,
      @Schema(nullable = true) String attemptedAt,
      String failureReason,
      UUID operatorId,
      String note,
      String createdAt) {}

  /** 一条评价回收（= 评价回收桶的一行）。 */
  public record CrmReviewRecovery(
      UUID id,
      UUID requestId,
      String requestNo,
      Integer rating,
      String content,
      boolean hasImage,
      boolean hasVideo,
      String platformCode,
      String source,
      String receivedAt,
      UUID recordedBy,
      String note,
      String createdAt) {}

  /** 单条索评请求详情 = 请求本体 + 发送记录 + 评价回收。 */
  public record CrmReviewRequestDetail(
      CrmReviewRequestRow request,
      List<CrmReviewSendRecord> sendRecords,
      List<CrmReviewRecovery> recoveries) {}

  /** 上游已签收确认单（索评候选集的一行）。 */
  public record CrmReviewCandidate(
      UUID upstreamCaseId,
      String caseNo,
      String masterWaybillNo,
      String carrierCode,
      String sourceBatchNo,
      String salesOrderNo,
      @Schema(nullable = true) UUID channelId,
      String destinationCountry,
      String handoverAt,
      String deliveredAt,
      String status,
      String deliveryState) {}

  /** 候选集分页。 */
  public record CrmReviewCandidatePage(
      List<CrmReviewCandidate> items,
      int page,
      int size,
      long totalElements,
      int totalPages,
      boolean truncated) {}

  /** 店铺目录的一行（来自 channel 的真实清单）。 */
  public record CrmReviewShopView(
      UUID id, String code, String name, String platformCode, String platformName, String status) {}

  /** 批量执行的逐条结果。 */
  public record CrmReviewBatchItem(
      String salesOrderNo,
      String buyerRef,
      String upstreamCaseNo,
      String outcome,
      String requestNo,
      String reason) {}

  /** 批量执行的汇总。 */
  public record CrmReviewBatchResult(
      UUID batchId,
      int candidateCount,
      int createdCount,
      int rejectedCount,
      int skippedCount,
      boolean truncated,
      String boundaryNote,
      List<CrmReviewBatchItem> items) {}

  /** 评价回收统计（= 蓝图输出句里的「评价回收统计」）。 */
  public record CrmReviewRecoveryStats(
      Integer scopeDays,
      String scopeFrom,
      String scopeTo,
      long totalRequests,
      long scheduledCount,
      long sentCount,
      long cancelledCount,
      long rejectedCount,
      long recoveredRequests,
      long recoveryRecords,
      @Schema(nullable = true) BigDecimal recoveryRate,
      @Schema(nullable = true) BigDecimal averageRating,
      List<CrmReviewRatingBucket> ratingDistribution,
      List<CrmReviewShopBucket> byShop,
      String note) {}

  /** 星级分布的一档。 */
  public record CrmReviewRatingBucket(int rating, long count) {}

  /** 按店铺分组的回收统计。 */
  public record CrmReviewShopBucket(
      UUID shopId, String shopName, String platformCode, long requests, long recovered,
      @Schema(nullable = true) BigDecimal averageRating) {}
}
