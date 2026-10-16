package com.starcon.channel;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 10.1.2.2 渠道比价 的传输模型。
 *
 * <p>蓝图：「在满足时效与限制的渠道中按成本/时效/成功率多目标排序，支持按店铺预设优先级自动选择。
 * 输入：比价条件；渠道评分（历史成功率、时效达成率、投诉率）。输出：排序后的推荐渠道 + 评分明细。」
 *
 * <p>载荷一律用具名 {@code record} 而不是 {@code Map<String,Object>}：Map 的值类型会被
 * springdoc 推成 object，codegen 出来是空对象 {@code {}}（9.6.1.3 已踩过）。
 *
 * <p>🔴 金额与数量一律 {@link BigDecimal}，**没有 double**：运费快照是钱，重量是数量，
 * 三维得分与综合分直接决定下游 9.3.2.1 分仓选谁 —— 它们都是钱与决策。
 *
 * <p>🔴 三处口径在模型层就钉死，不留给使用者去猜：
 *
 * <ol>
 *   <li>{@code costBasis} 恒为 {@code RULE_ESTIMATE}：比价的成本来自上游 10.1.2.1 的
 *       <b>试算</b>，不是承运商账单（沿用 10.1.2.1「试算用规则、结算用实际」）。
 *   <li>三个得分（{@code costScore} / {@code transitScore} / {@code reliabilityScore}）为
 *       {@code null} 时表示<b>不可比</b>，与 {@code 0} 分含义完全相反 —— 界面上必须分开显示。
 *   <li>{@code riskFlagged} / {@code sampleSufficient} / {@code ratingStale} 三个闸只挡
 *       <b>自动选择</b>，不挡评分：被标记的渠道照样出现在对照表里并给出完整得分，
 *       否则「它为什么便宜却没被选」在界面上就无从解释。
 * </ol>
 */
public final class RateComparisonModels {
  private RateComparisonModels() {}

  // ================================================================ 能力声明

  public record RateCapabilityDimension(
      String dimension, String description, boolean available, String source, String ownerModule, String impact) {}

  public record RateComparisonCapabilities(
      List<RateCapabilityDimension> dimensions,
      List<String> selectionBases,
      List<String> rankBases,
      List<String> scoreSources,
      List<String> eligibilityCodes,
      /** 多目标权重默认口径，写在接口自述里而不是只写在文档里。 */
      String weightingNote,
      /** 三个闸的说明：只挡自动选择，不挡评分。 */
      String gatingNote,
      /** 试算 ≠ 实际成本这条口径的落点。 */
      String estimationNote,
      String dataNote) {}

  // ================================================================ 渠道评分（比价的输入之一）

  /**
   * 一条渠道评分。
   *
   * <p>🔴 三个比率（成功率 / 时效达成率 / 投诉率）在 DB 层是 <b>GENERATED 列</b>，由原始计数
   * 派生：服务层写不进去，绕过服务层写 SQL 也写不出一个与计数对不上的比率。
   *
   * <p>🔴 {@code maxComplaintRatePct} 与 {@code minSampleThreshold} 是这条评分自带的两个闸
   * （蓝图加粗句「只看价格选渠道是常见错误」的物理落点），都是租户可维护的数据，不是常量。
   */
  public record ChannelRatingRow(
      UUID id,
      UUID channelId,
      String channelCode,
      String channelName,
      String carrierName,
      String countryCode,
      int dispatched,
      int delivered,
      int onTime,
      int complaint,
      @Schema(nullable = true) BigDecimal deliverySuccessRate,
      @Schema(nullable = true) BigDecimal onTimeRate,
      @Schema(nullable = true) BigDecimal complaintRate,
      OffsetDateTime windowFrom,
      OffsetDateTime windowTo,
      OffsetDateTime observedAt,
      /** MANUAL_ENTERED 人工登记 · TRANSIT_ANALYTICS 10.6.2.1 回灌（未交付）· … */
      String scoreSource,
      String sourceRef,
      /** 投诉率红线（百分比）。超过它 risk_flagged，不许自动选中。 */
      BigDecimal maxComplaintRatePct,
      /** 最小样本量。低于它 sample_sufficient=false，不许自动选中。 */
      int minSampleThreshold,
      String status,
      String note,
      long version,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /**
   * 登记 / 改一条渠道评分。
   *
   * <p>🔔 改动一条<b>生效中</b>评分的计数或窗口会被 DB 层拒（V143 trigger b）：
   * 已经发出的比价单里存着当时那组比率，就地改计数会让那些单据再也复算不出来。
   * 要改请把旧行置为 {@code SUPERSEDED} 再登记一条新的。
   */
  public record SaveRatingRequest(
      @Schema(nullable = true) UUID ratingId,
      @NotNull UUID channelId,
      @NotBlank @Size(min = 2, max = 2) String countryCode,
      @Positive int dispatched,
      @PositiveOrZero int delivered,
      @PositiveOrZero int onTime,
      @PositiveOrZero int complaint,
      @NotNull OffsetDateTime windowFrom,
      @NotNull OffsetDateTime windowTo,
      @NotNull OffsetDateTime observedAt,
      @NotBlank @Size(max = 32) String scoreSource,
      @Size(max = 200) String sourceRef,
      @Schema(nullable = true) @DecimalMin("0") @DecimalMax("100") BigDecimal maxComplaintRatePct,
      @Schema(nullable = true) @Min(1) @Max(1000000) Integer minSampleThreshold,
      @NotBlank @Size(max = 16) String status,
      @Size(max = 2000) String note,
      @PositiveOrZero long version) {}

  public record RatingOverview(
      List<ChannelRatingRow> ratings,
      int activeCount,
      int manualCount,
      /** 没有任何评分的渠道：比价时它们会以「没有评分」落选，这里提前告诉运营。 */
      List<String> unratedChannelCodes,
      String note) {}

  // ================================================================ 店铺预设优先级

  /** 一条店铺预设优先级：{@code priorityNo=1} 最优先。 */
  public record ShopPriorityRow(
      UUID id,
      String shopCode,
      UUID channelId,
      String channelCode,
      String channelName,
      String carrierName,
      int priorityNo,
      boolean autoSelectAllowed,
      BigDecimal minCompositeScore,
      String status,
      String note,
      long version,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /**
   * 登记 / 改一条店铺预设优先级。
   *
   * <p>🔴 生效中的预设必须<b>连续覆盖 1..N</b>（V143 trigger c2）：序号跳空说明中间那一位
   * 被删了，而「第 2 位是谁」从此无人知晓。
   *
   * <p>🔴 生效中的预设不许改店铺编码或渠道（trigger c1）：那会把这一条从旧店铺的链里
   * 悄悄搬走。要换店铺请先 {@code RETIRED} 再在目标店铺登记。
   */
  public record SaveShopPriorityRequest(
      @Schema(nullable = true) UUID priorityId,
      @NotBlank @Size(max = 64) String shopCode,
      @NotNull UUID channelId,
      @Min(1) @Max(999) int priorityNo,
      boolean autoSelectAllowed,
      @Schema(nullable = true) @DecimalMin("0") @DecimalMax("100") BigDecimal minCompositeScore,
      @NotBlank @Size(max = 16) String status,
      @Size(max = 2000) String note,
      @PositiveOrZero long version) {}

  public record ShopPriorityOverview(
      String shopCode, List<ShopPriorityRow> priorities, int activeCount, String note) {}

  // ================================================================ 比价（条件 → 排序结果）

  /**
   * 一次比价的条件。
   *
   * <p>成本 / 时效 / 可承运性<b>全部来自上游 10.1.2.1</b>，本模块不重算钱：要么引用一张
   * 已落库的试算单（{@code sourceQuoteId}，推荐用法，「当时按哪张对照表比的价格」答得出来），
   * 要么直接给包裹参数走一次<b>无副作用</b>试算（{@code parcel}）。
   *
   * <p>🔴 {@code comparedAt} 会被写进库里：评分新鲜度这个闸按它判定，不存这个时刻，
   * 事后没人能复算「当时用的是不是一份过期的评分」。
   */
  public record CompareRequest(
      @Schema(nullable = true) UUID sourceQuoteId,
      @Schema(nullable = true) @Valid FreightCalculatorModels.FreightEstimateRequest parcel,
      @Schema(nullable = true) List<UUID> channelIds,
      @Schema(nullable = true) @PositiveOrZero Integer maxTransitDays,
      @Schema(nullable = true) Boolean applyShopPriority,
      @Schema(nullable = true) @DecimalMin("0") BigDecimal costWeight,
      @Schema(nullable = true) @DecimalMin("0") BigDecimal transitWeight,
      @Schema(nullable = true) @DecimalMin("0") BigDecimal reliabilityWeight,
      @Schema(nullable = true) @DecimalMin("0") BigDecimal successRateWeight,
      @Schema(nullable = true) @DecimalMin("0") BigDecimal onTimeRateWeight,
      @Schema(nullable = true) @DecimalMin("0") BigDecimal complaintRateWeight,
      @Schema(nullable = true) @Min(0) @Max(36500) Integer maxRatingAgeDays,
      @Schema(nullable = true) Boolean requirePassOnly,
      @Schema(nullable = true) OffsetDateTime comparedAt,
      @Schema(nullable = true) @Positive Integer minCompositeScore,
      @Size(max = 2000) String note) {}

  /**
   * 对照表的一行：一条渠道 × (成本分、时效分、可靠性分、综合分、三个闸、排名)。
   *
   * <p>🔴 {@code compositeScore} 由 <b>DB 层</b>按单头权重重算（V143 trigger d），服务层
   * 传什么都不作数 —— 界面上「分项加权和」必须等于「综合分」。
   */
  public record ComparisonLine(
      UUID channelId,
      String channelCode,
      String channelName,
      String carrierName,
      String eligibility,
      @Schema(nullable = true) BigDecimal totalCharge,
      String currency,
      @Schema(nullable = true) Integer transitMinDays,
      @Schema(nullable = true) Integer transitMaxDays,
      @Schema(nullable = true) Integer transitBasisDays,
      /** false = 上游 10.1.2.1 算不出钱（{@code rateSource=NO_RULE}），**不是 0 元**。 */
      boolean priced,
      /** null = 不可比（与 0 分含义相反）。 */
      @Schema(nullable = true) BigDecimal costScore,
      @Schema(nullable = true) BigDecimal transitScore,
      @Schema(nullable = true) BigDecimal reliabilityScore,
      @Schema(nullable = true) BigDecimal compositeScore,
      @Schema(nullable = true) Integer rankNo,
      /** RANKED / EXCLUDED_* —— 见 capabilities.rankBases。 */
      String rankBasis,
      String exclusionReason,
      // ---- 评分来源快照（这一行用的是哪份评分）----
      @Schema(nullable = true) UUID ratingId,
      @Schema(nullable = true) OffsetDateTime ratingWindowFrom,
      @Schema(nullable = true) OffsetDateTime ratingWindowTo,
      @Schema(nullable = true) OffsetDateTime ratingObservedAt,
      @Schema(nullable = true) Integer ratingDispatched,
      @Schema(nullable = true) Integer ratingDelivered,
      @Schema(nullable = true) Integer ratingOnTime,
      @Schema(nullable = true) Integer ratingComplaint,
      @Schema(nullable = true) BigDecimal ratingSuccessRate,
      @Schema(nullable = true) BigDecimal ratingOnTimeRate,
      @Schema(nullable = true) BigDecimal ratingComplaintRate,
      @Schema(nullable = true) String ratingSource,
      // ---- 三个闸：只挡自动选择，不挡评分 ----
      boolean riskFlagged,
      String riskNote,
      boolean sampleSufficient,
      String sampleNote,
      boolean ratingStale,
      String staleNote,
      @Schema(nullable = true) Integer priorityNo,
      boolean autoSelectAllowed,
      @Schema(nullable = true) BigDecimal minCompositeScore,
      // ---- 为什么自动选了 / 没选它 ----
      boolean selected,
      String selectionNote,
      /** 算式与逐条理由的纯文本：界面上要能读出「这 87.4 分是怎么来的」。 */
      List<String> scoreBreakdown,
      List<String> notes) {}

  /**
   * 一整张排序结果 + 推荐。
   *
   * <p>🔴 {@code selectionBasis=NO_ELIGIBLE_CHANNEL} 时 {@code selectedChannelId} 恒为 null，
   * 且每一条 {@code lines} 都带 {@code exclusionReason} —— 一条都不合格时**逐条**说清为什么，
   * 绝不悄悄回落成「就它吧」。
   */
  public record CompareResult(
      /** 恒为 RULE_ESTIMATE：比价的成本来自试算，不是承运商账单。 */
      String costBasis,
      @Schema(nullable = true) UUID sourceQuoteId,
      OffsetDateTime comparedAt,
      String destCountry,
      String shopCode,
      BigDecimal weightKg,
      Integer pieces,
      String currency,
      boolean mixedCurrency,
      @Schema(nullable = true) BigDecimal cheapestTotal,
      @Schema(nullable = true) BigDecimal dearestTotal,
      int candidateCount,
      int rankedCount,
      int riskFlaggedCount,
      int insufficientSampleCount,
      int staleRatingCount,
      // ---- 权重（归一化后的实际值，事后可复算）----
      BigDecimal costWeight,
      BigDecimal transitWeight,
      BigDecimal reliabilityWeight,
      BigDecimal successRateWeight,
      BigDecimal onTimeRateWeight,
      BigDecimal complaintRateWeight,
      // ---- 比价条件回显 ----
      @Schema(nullable = true) Integer maxTransitDays,
      int maxRatingAgeDays,
      boolean requirePassOnly,
      boolean applyShopPriority,
      @Schema(nullable = true) Integer minCompositeScore,
      // ---- 推荐 ----
      @Schema(nullable = true) UUID selectedChannelId,
      String selectedChannelCode,
      String selectedChannelName,
      /** SCORE_RANKED / SHOP_PRESET_PRIORITY / NO_ELIGIBLE_CHANNEL。 */
      String selectionBasis,
      String selectionNote,
      @Schema(nullable = true) UUID savedComparisonId,
      List<ComparisonLine> lines,
      String note) {}

  // ================================================================ 比价单

  public record ComparisonRow(
      UUID id,
      String costBasis,
      @Schema(nullable = true) UUID sourceQuoteId,
      String destCountry,
      String shopCode,
      BigDecimal weightKg,
      int pieces,
      OffsetDateTime comparedAt,
      BigDecimal costWeight,
      BigDecimal transitWeight,
      BigDecimal reliabilityWeight,
      @Schema(nullable = true) Integer maxTransitDays,
      int maxRatingAgeDays,
      boolean requirePassOnly,
      boolean applyShopPriority,
      int candidateCount,
      int rankedCount,
      int riskFlaggedCount,
      int insufficientSampleCount,
      int staleRatingCount,
      String currency,
      boolean mixedCurrency,
      @Schema(nullable = true) BigDecimal cheapestTotal,
      @Schema(nullable = true) BigDecimal dearestTotal,
      @Schema(nullable = true) UUID selectedChannelId,
      String selectedChannelCode,
      String selectionBasis,
      String selectionNote,
      String note,
      OffsetDateTime createdAt) {}

  public record ComparisonDetail(ComparisonRow comparison, List<ComparisonLine> lines) {}

  public record ComparisonSummary(
      long total, long withSelection, long noSelection, long shopPreset, long riskFlagged) {}

  public record ComparisonPage(
      List<ComparisonRow> items, int page, int size, long total, int totalPages, ComparisonSummary summary) {}

  /** 保存比价单的请求体：比价条件 + 「是否落库」。 */
  public record SaveComparisonRequest(@NotNull @Valid CompareRequest request) {}
}
