package com.starcon.channel;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 14.2.1.1 竞价管理的展示形状与请求体。
 *
 * <p>蓝图：「管理竞价：动态竞价策略选择（只增只降/降低/提高）、手动竞价调整、
 * 竞价范围上限、按商品/SKU 的目标 ACOS 反推竞价建议。输入：竞价设置；目标 ACOS/ROAS；
 * 历史表现。输出：竞价建议（目标 ACOS → 建议竞价）；批量调整任务。
 * 关系：上游 = 14.1.3.1、14.3.2.1；下游 = 平台广告 API。
 * <b>目标 ACOS 反推竞价是广告与利润打通的接口</b>——没有利润数据，投放只能看平台给的建议，
 * 无法判断是否真的赚钱。」
 *
 * <p>🔴 <b>形状约定</b>：
 *
 * <ul>
 *   <li><b>金额</b>一律 {@code BigDecimal}（后端 {@code numeric(19,4)}），前端只原样展示，
 *       <b>不在前端做算术</b>。
 *   <li><b>比率 / 数量</b>是 {@code numeric(19,6)}（比例是数量，不是金额）。
 *   <li><b>不可比 ≠ 零</b>：比率类字段为 {@code null} 表示<b>不可比</b>（分母为 0），
 *       {@code 0} 表示<b>可比但真的是零</b> —— 两者含义相反，混用会误导。
 *   <li><b>目标 ACOS / ROAS 是比例不是百分数</b>：{@code 0.25} = 25%。
 * </ul>
 */
public final class AdBiddingModels {

  private AdBiddingModels() {}

  // ==================================================================
  // 能力声明
  // ==================================================================

  /** 一个能力维度（能做的写 {@code available=true}，做不了的**必须**写清责任模块与影响）。 */
  public record AdBiddingCapabilityDimension(
      @Schema(description = "维度码") String dimension,
      @Schema(description = "这个维度是干什么的") String description,
      @Schema(description = "本模块是否真的能做") boolean available,
      @Schema(description = "数据落在哪 / 依赖谁") String source,
      @Schema(description = "🔴 做不了的责任模块（做得了时为空串）") String ownerModule,
      @Schema(description = "🔴 做不了的影响（页面上原样显示）") String impact) {}

  /** 能力声明。 */
  public record AdBiddingCapabilities(
      @Schema(description = "能力维度清单") List<AdBiddingCapabilityDimension> dimensions,
      @Schema(description = "策略状态枚举") List<String> statuses,
      @Schema(description = "作用范围枚举") List<String> scopes,
      @Schema(description = "🔴 动态竞价策略枚举（**只有三档**）") List<String> dynamicStrategies,
      @Schema(description = "运行口径枚举") List<String> runModes,
      @Schema(description = "待下发队列状态枚举") List<String> dispatchStatuses,
      @Schema(description = "🔴 闸门码枚举") List<String> guardrailCodes,
      @Schema(description = "🔴 动态竞价策略的口径说明") String strategyNote,
      @Schema(description = "🔴 两道闸的口径说明") String guardrailNote,
      @Schema(description = "🔴 ACOS / ROAS 的口径说明") String ratioNote,
      @Schema(description = "🔴 利润打通的说明") String profitNote,
      @Schema(description = "🔴 上游依赖说明") String upstreamNote,
      @Schema(description = "🔴 待下发队列的说明") String dispatchNote,
      @Schema(description = "🔴 边界说明") String boundaryNote) {}

  // ==================================================================
  // 下拉项
  // ==================================================================

  /** 一个下拉项。 */
  public record AdBiddingOption(
      @Schema(description = "取值") String value,
      @Schema(description = "展示名") String label,
      @Schema(description = "副标题 / 说明") String note) {}

  /** 建策略时要用的下拉项 + 枚举。 */
  public record AdBiddingOptions(
      @Schema(description = "平台下拉项") List<AdBiddingOption> platforms,
      @Schema(description = "店铺下拉项") List<AdBiddingOption> shops,
      @Schema(description = "动态竞价策略下拉项（三档）") List<AdBiddingOption> dynamicStrategies,
      @Schema(description = "策略状态下拉项") List<AdBiddingOption> statuses,
      @Schema(description = "🔴 14.1.3.1 已交付写路径的护栏上限（**继承，不另设**）")
          AdBiddingUpstreamGuardrails upstreamGuardrails,
      @Schema(description = "🔴 口径说明") String caliberNote) {}

  /**
   * 14.1.3.1 已交付写路径（{@code AdKeywordService.bulkAction}）自带的护栏。
   *
   * <p>🔴 本模块**不另设**这一套 —— 一律继承，改价的动作最终都过它。
   */
  public record AdBiddingUpstreamGuardrails(
      @Schema(description = "单次调整幅度上限（比例，0.50 = ±50%）") BigDecimal maxBidDeltaRatio,
      @Schema(description = "单条竞价绝对上限") BigDecimal maxBidAbsolute,
      @Schema(description = "单次总上调上限") BigDecimal maxTotalBidIncrease) {}

  // ==================================================================
  // 竞价策略
  // ==================================================================

  /**
   * 新建 / 修改一条竞价策略。
   *
   * <p>🔴 {@code targetAcos} 与 {@code targetRoas} **二选一**（DB 层 {@code num_nonnulls} 钉死）：
   * ACOS = 花费/销售额、ROAS = 销售额/花费 ⇒ 互为倒数，同时给两个就必须自洽，
   * 不自洽等于给了两个互相矛盾的答案。
   */
  public record AdBiddingPolicyRequest(
      @Schema(description = "策略编码（租户内唯一）") String policyCode,
      @Schema(description = "策略名") String policyName,
      @Schema(description = "平台 id") String platformId,
      @Schema(description = "店铺编码") String shopCode,
      @Schema(description = "🔴 动态竞价策略：UP_AND_DOWN 只增只降 / DOWN_ONLY 只降低 / UP_ONLY 只提高")
          String dynamicBiddingStrategy,
      @Schema(description = "币种（ISO 三位）") String currency,
      @Schema(description = "作用范围：SHOP 店铺级 / CAMPAIGN 活动级") String scope,
      @Schema(description = "目标活动 id（scope=CAMPAIGN 时必填）") String targetCampaignId,
      @Schema(description = "🔴 闸①：竞价范围下限（null = 不设下限）") BigDecimal minBid,
      @Schema(description = "🔴 闸①：竞价范围上限（null = 不设上限）") BigDecimal maxBid,
      @Schema(description = "🔴 闸②：单次调整比例额度（0 = 不额外限制，交给 14.1.3.1 的 ±50%）")
          BigDecimal singleAdjustCapRatio,
      @Schema(description = "🔴 闸②：单次调整金额额度（0 = 不额外限制）")
          BigDecimal singleAdjustCapAmount,
      @Schema(description = "目标 ACOS（**比例**，0.25 = 25%；与 targetRoas 二选一）")
          BigDecimal targetAcos,
      @Schema(description = "目标 ROAS（**比例**，4 = 400%；与 targetAcos 二选一）")
          BigDecimal targetRoas,
      @Schema(description = "试运行（默认 true；与请求侧取更严的那个）") Boolean dryRun,
      @Schema(description = "备注") String note) {}

  /** 改策略状态。 */
  public record AdBiddingStatusRequest(
      @Schema(description = "目标状态：DRAFT / ACTIVE / PAUSED / ARCHIVED") String status,
      @Schema(description = "备注") String note) {}

  /** 一条竞价策略的完整视图。 */
  public record AdBiddingPolicyView(
      @Schema(description = "策略 id") String id,
      @Schema(description = "策略编码") String policyCode,
      @Schema(description = "策略名") String policyName,
      @Schema(description = "平台 id") String platformId,
      @Schema(description = "平台编码") String platformCode,
      @Schema(description = "平台名") String platformName,
      @Schema(description = "店铺编码") String shopCode,
      @Schema(description = "店铺名") String shopName,
      @Schema(description = "店铺 id") String shopId,
      @Schema(description = "🔴 动态竞价策略（三档）") String dynamicBiddingStrategy,
      @Schema(description = "币种") String currency,
      @Schema(description = "作用范围") String scope,
      @Schema(description = "目标活动 id") String targetCampaignId,
      @Schema(description = "目标活动名") String targetCampaignName,
      @Schema(description = "策略状态") String status,
      @Schema(description = "🔴 闸①：竞价范围下限") BigDecimal minBid,
      @Schema(description = "🔴 闸①：竞价范围上限") BigDecimal maxBid,
      @Schema(description = "🔴 闸②：单次调整比例额度") BigDecimal singleAdjustCapRatio,
      @Schema(description = "🔴 闸②：单次调整金额额度") BigDecimal singleAdjustCapAmount,
      @Schema(description = "目标 ACOS（比例）") BigDecimal targetAcos,
      @Schema(description = "目标 ROAS（比例）") BigDecimal targetRoas,
      @Schema(description = "试运行") boolean dryRun,
      @Schema(description = "备注") String note,
      @Schema(description = "版本号") long version,
      @Schema(description = "创建时间") OffsetDateTime createdAt,
      @Schema(description = "更新时间") OffsetDateTime updatedAt) {}

  /** 列表行（比详情少几个字段）。 */
  public record AdBiddingPolicySummary(
      @Schema(description = "策略 id") String id,
      @Schema(description = "策略编码") String policyCode,
      @Schema(description = "策略名") String policyName,
      @Schema(description = "平台编码") String platformCode,
      @Schema(description = "店铺编码") String shopCode,
      @Schema(description = "店铺名") String shopName,
      @Schema(description = "🔴 动态竞价策略（三档）") String dynamicBiddingStrategy,
      @Schema(description = "币种") String currency,
      @Schema(description = "作用范围") String scope,
      @Schema(description = "策略状态") String status,
      @Schema(description = "🔴 闸①：竞价范围下限") BigDecimal minBid,
      @Schema(description = "🔴 闸①：竞价范围上限") BigDecimal maxBid,
      @Schema(description = "目标 ACOS（比例）") BigDecimal targetAcos,
      @Schema(description = "目标 ROAS（比例）") BigDecimal targetRoas,
      @Schema(description = "试运行") boolean dryRun,
      @Schema(description = "更新时间") OffsetDateTime updatedAt) {}

  /** 真分页的一页。 */
  public record AdBiddingPolicyPage(
      @Schema(description = "本页条目") List<AdBiddingPolicySummary> items,
      @Schema(description = "筛选后的总条数") long total,
      @Schema(description = "页码（从 0 起）") int page,
      @Schema(description = "每页条数") int size) {}

  // ==================================================================
  // 竞价候选清单（关键词）
  // ==================================================================

  /**
   * 一条可调价的关键词。
   *
   * @param windowSpend 窗口内花费（**平台自报口径**，14.1.3.1 的 {@code ad_keyword_metric}）
   * @param windowSales 窗口内销售额（**平台自报口径**；反推竞价用的是 14.3.2.1 的<b>归因</b>销售额，
   *     两个口径不同，页面上分开写明）
   * @param skuMapped 该关键词有没有归到内部 SKU（false ⇒ 按 SKU 反推目标 ACOS 这一路走不通）
   */
  public record AdBiddingKeywordRow(
      @Schema(description = "关键词 id") String keywordId,
      @Schema(description = "关键词原文") String keyword,
      @Schema(description = "店铺编码") String shopCode,
      @Schema(description = "平台编码") String platformCode,
      @Schema(description = "关键词状态") String status,
      @Schema(description = "当前竞价") BigDecimal bid,
      @Schema(description = "竞价币种") String bidCurrency,
      @Schema(description = "指标币种") String metricCurrency,
      @Schema(description = "窗口内花费（平台自报口径）") BigDecimal windowSpend,
      @Schema(description = "窗口内销售额（平台自报口径）") BigDecimal windowSales,
      @Schema(description = "窗口内有指标的天数") int windowDays,
      @Schema(description = "外部活动 id") String externalCampaignId,
      @Schema(description = "外部关键词 id") String externalKeywordId,
      @Schema(description = "内部 SKU id（归不到时为空串）") String skuId,
      @Schema(description = "SKU 编码（归不到时为空串）") String skuCode,
      @Schema(description = "SPU 编码（归不到时为空串）") String spuCode,
      @Schema(description = "🔴 归到 SKU 的结论") boolean skuMapped,
      @Schema(description = "🔴 归到 SKU 的说明（归不到时写清为什么）") String skuNote,
      @Schema(description = "最近一次指标同步时刻") OffsetDateTime lastSyncedAt) {}

  /** 真分页的一页关键词。 */
  public record AdBiddingKeywordPage(
      @Schema(description = "本页条目") List<AdBiddingKeywordRow> items,
      @Schema(description = "筛选后的总条数") long total,
      @Schema(description = "页码（从 0 起）") int page,
      @Schema(description = "每页条数") int size) {}

  // ==================================================================
  // 竞价建议
  // ==================================================================

  /**
   * 生成一批竞价建议。
   *
   * @param from 口径窗口起（含）
   * @param to 口径窗口止（含）
   * @param idempotencyKey 幂等键（租户内唯一）：同一次生成重投 ⇒ 返回既有批次
   */
  public record AdBiddingSuggestionRequest(
      @Schema(description = "策略 id") String policyId,
      @Schema(description = "口径窗口起（含）") LocalDate from,
      @Schema(description = "口径窗口止（含）") LocalDate to,
      @Schema(description = "🔴 幂等键（租户内唯一）") String idempotencyKey,
      @Schema(description = "备注") String note) {}

  /** 一批建议的头部。 */
  public record AdBiddingSuggestionSummary(
      @Schema(description = "批次 id") String id,
      @Schema(description = "策略 id") String policyId,
      @Schema(description = "策略名") String policyName,
      @Schema(description = "店铺编码") String shopCode,
      @Schema(description = "店铺名") String shopName,
      @Schema(description = "口径窗口起") LocalDate windowFrom,
      @Schema(description = "口径窗口止") LocalDate windowTo,
      @Schema(description = "目标 ACOS 快照（比例）") BigDecimal targetAcos,
      @Schema(description = "目标 ROAS 快照（比例）") BigDecimal targetRoas,
      @Schema(description = "🔴 归因规则编码（口径快照）") String attributionRuleCode,
      @Schema(description = "🔴 归因规则版本") Long attributionRuleVersion,
      @Schema(description = "🔴 归因模型") String attributionModel,
      @Schema(description = "🔴 归因窗口（天）") Integer attributionWindowDays,
      @Schema(description = "🔴 利润侧这次读到了没有") boolean profitAvailable,
      @Schema(description = "🔴 利润侧读不到的原因") String profitUnavailableReason,
      @Schema(description = "🔴 数据截至时刻") OffsetDateTime dataAsOf,
      @Schema(description = "批次状态：READY / PARTIAL / EMPTY") String status,
      @Schema(description = "SKU 数") int skuCount,
      @Schema(description = "给出建议的 SKU 数") int suggestedCount,
      @Schema(description = "给不出建议的 SKU 数") int rejectedCount,
      @Schema(description = "备注") String note,
      @Schema(description = "创建时间") OffsetDateTime createdAt) {}

  /**
   * 一个 SKU 的口径行。
   *
   * @param spend 窗口内该 SKU 的广告花费
   * @param attributedSales 窗口内该 SKU 的<b>归因</b>销售额（14.3.2.1）
   * @param currentAcos ACOS = 花费 ÷ 归因销售额（null = <b>不可比</b>，分母为 0）
   * @param currentRoas ROAS = 归因销售额 ÷ 花费（null = <b>不可比</b>，分母为 0）
   * @param breakEvenAcos 盈亏平衡 ACOS（null = 利润侧算不出来）
   */
  public record AdBiddingSuggestionSkuView(
      @Schema(description = "SKU 级口径行 id") String id,
      @Schema(description = "内部 SKU id") String skuId,
      @Schema(description = "SKU 编码") String skuCode,
      @Schema(description = "窗口内花费") BigDecimal spend,
      @Schema(description = "窗口内归因销售额") BigDecimal attributedSales,
      @Schema(description = "币种") String currency,
      @Schema(description = "🔴 当前 ACOS（null = 不可比）") BigDecimal currentAcos,
      @Schema(description = "🔴 当前 ROAS（null = 不可比）") BigDecimal currentRoas,
      @Schema(description = "🔴 盈亏平衡 ACOS（null = 算不出来）") BigDecimal breakEvenAcos,
      @Schema(description = "利润侧状态：AVAILABLE / NOT_COMPARABLE / NO_DATA") String profitStatus,
      @Schema(description = "🔴 建议倍率（null = 算不出来）") BigDecimal bidRatio,
      @Schema(description = "结论：SUGGESTED / REJECTED") String status,
      @Schema(description = "🔴 被哪道闸拦下的") String guardrailCode,
      @Schema(description = "原因") String reason,
      @Schema(description = "关键词级建议") List<AdBiddingSuggestionItemView> items) {}

  /**
   * 一个关键词的建议竞价。
   *
   * @param suggestedBid 建议竞价（null = 算不出来）
   * @param bidDelta 与当前竞价的差额（**下调为负**）
   */
  public record AdBiddingSuggestionItemView(
      @Schema(description = "建议明细 id") String id,
      @Schema(description = "关键词 id") String keywordId,
      @Schema(description = "关键词原文") String keywordText,
      @Schema(description = "当前竞价") BigDecimal currentBid,
      @Schema(description = "竞价币种") String bidCurrency,
      @Schema(description = "🔴 建议竞价（null = 算不出来）") BigDecimal suggestedBid,
      @Schema(description = "与当前竞价的差额（下调为负）") BigDecimal bidDelta,
      @Schema(description = "结论：SUGGESTED / REJECTED") String status,
      @Schema(description = "🔴 被哪道闸拦下的") String guardrailCode,
      @Schema(description = "原因") String reason) {}

  /** 一批建议的详情（含 SKU 行与关键词行）。 */
  public record AdBiddingSuggestionView(
      @Schema(description = "批次 id") String id,
      @Schema(description = "策略 id") String policyId,
      @Schema(description = "策略名") String policyName,
      @Schema(description = "店铺编码") String shopCode,
      @Schema(description = "店铺名") String shopName,
      @Schema(description = "口径窗口起") LocalDate windowFrom,
      @Schema(description = "口径窗口止") LocalDate windowTo,
      @Schema(description = "目标 ACOS 快照（比例）") BigDecimal targetAcos,
      @Schema(description = "目标 ROAS 快照（比例）") BigDecimal targetRoas,
      @Schema(description = "🔴 归因规则编码（口径快照）") String attributionRuleCode,
      @Schema(description = "🔴 归因规则版本") Long attributionRuleVersion,
      @Schema(description = "🔴 归因模型") String attributionModel,
      @Schema(description = "🔴 归因窗口（天）") Integer attributionWindowDays,
      @Schema(description = "🔴 利润侧这次读到了没有") boolean profitAvailable,
      @Schema(description = "🔴 利润侧读不到的原因") String profitUnavailableReason,
      @Schema(description = "🔴 数据截至时刻") OffsetDateTime dataAsOf,
      @Schema(description = "批次状态：READY / PARTIAL / EMPTY") String status,
      @Schema(description = "SKU 数") int skuCount,
      @Schema(description = "给出建议的 SKU 数") int suggestedCount,
      @Schema(description = "给不出建议的 SKU 数") int rejectedCount,
      @Schema(description = "🔴 判定参数快照（可复算）") Map<String, Object> basis,
      @Schema(description = "备注") String note,
      @Schema(description = "创建时间") OffsetDateTime createdAt,
      @Schema(description = "SKU 级口径行") List<AdBiddingSuggestionSkuView> skus) {}

  /** 真分页的一页建议批次。 */
  public record AdBiddingSuggestionPage(
      @Schema(description = "本页条目") List<AdBiddingSuggestionSummary> items,
      @Schema(description = "筛选后的总条数") long total,
      @Schema(description = "页码（从 0 起）") int page,
      @Schema(description = "每页条数") int size) {}

  // ==================================================================
  // 批量调整
  // ==================================================================

  /** 一条待调价的关键词（**绝对值**：直接设成某个竞价）。 */
  public record AdBiddingAdjustTarget(
      @Schema(description = "关键词 id") String keywordId,
      @Schema(description = "期望的竞价（绝对值）") BigDecimal bidValue) {}

  /**
   * 一次批量竞价调整。
   *
   * <p>🔴 <b>二选一</b>：给 {@code suggestionId} ⇒ 目标**从建议批次里取**
   * （只取给出建议的那几条，被闸拦下的自动跳过并留痕）；
   * 给 {@code targets} ⇒ 手工指定（关键词 id + 期望的绝对竞价）。
   * 两条都不给 ⇒ 400。
   *
   * @param dryRun 试运行（默认 true；与策略上的 dry_run <b>取更严的那个</b>）
   * @param idempotencyKey 幂等键（租户内唯一）：重投同一批不会重复调价
   */
  public record AdBiddingAdjustmentRequest(
      @Schema(description = "策略 id") String policyId,
      @Schema(description = "建议批次 id（给了就以建议里的建议竞价为目标，与 targets 二选一）")
          String suggestionId,
      @Schema(description = "调价币种（逐条按币种核对；不给则用策略的币种）") String currency,
      @Schema(description = "试运行（默认 true）") Boolean dryRun,
      @Schema(description = "调价目标（逐条给期望的绝对竞价；与 suggestionId 二选一）")
          List<AdBiddingAdjustTarget> targets,
      @Schema(description = "🔴 幂等键（租户内唯一）") String idempotencyKey,
      @Schema(description = "备注") String note) {}

  /** 逐条调整结果。 */
  public record AdBiddingAdjustmentItemView(
      @Schema(description = "明细 id") String id,
      @Schema(description = "关键词 id") String keywordId,
      @Schema(description = "关键词原文") String targetRef,
      @Schema(description = "调价前的竞价") BigDecimal beforeBid,
      @Schema(description = "调价后的竞价") BigDecimal afterBid,
      @Schema(description = "请求里给的期望竞价") BigDecimal requestedBid,
      @Schema(description = "结果：APPLIED / REJECTED / SKIPPED / PENDING") String status,
      @Schema(description = "🔴 被哪道闸拦下的") String guardrailCode,
      @Schema(description = "🔴 原因（没成必须写清为什么）") String reason) {}

  /** 一次调整任务的头部。 */
  public record AdBiddingAdjustmentSummary(
      @Schema(description = "任务 id") String id,
      @Schema(description = "策略 id") String policyId,
      @Schema(description = "策略名") String policyName,
      @Schema(description = "店铺 id") String shopId,
      @Schema(description = "店铺编码") String shopCode,
      @Schema(description = "动作类型") String actionType,
      @Schema(description = "运行口径：DRY_RUN / APPLY") String runMode,
      @Schema(description = "任务状态：RECEIVED / APPLIED / PARTIAL / REJECTED") String status,
      @Schema(description = "币种") String currency,
      @Schema(description = "调价方式") String bidMode,
      @Schema(description = "请求条目数") int requestedCount,
      @Schema(description = "成功条目数") int appliedCount,
      @Schema(description = "失败条目数") int failedCount,
      @Schema(description = "🔴 14.1.3.1 批量任务的 id（写路径的凭证）") String bulkActionId,
      @Schema(description = "🔴 护栏快照") Map<String, Object> guardrail,
      @Schema(description = "幂等键") String idempotencyKey,
      @Schema(description = "整批被拒的原因") String reason,
      @Schema(description = "备注") String note,
      @Schema(description = "创建时间") OffsetDateTime createdAt) {}

  /** 一次调整任务的详情（含逐条明细）。 */
  public record AdBiddingAdjustmentView(
      @Schema(description = "任务 id") String id,
      @Schema(description = "策略 id") String policyId,
      @Schema(description = "策略名") String policyName,
      @Schema(description = "店铺 id") String shopId,
      @Schema(description = "店铺编码") String shopCode,
      @Schema(description = "动作类型") String actionType,
      @Schema(description = "运行口径：DRY_RUN / APPLY") String runMode,
      @Schema(description = "任务状态：RECEIVED / APPLIED / PARTIAL / REJECTED") String status,
      @Schema(description = "币种") String currency,
      @Schema(description = "调价方式") String bidMode,
      @Schema(description = "请求条目数") int requestedCount,
      @Schema(description = "成功条目数") int appliedCount,
      @Schema(description = "失败条目数") int failedCount,
      @Schema(description = "🔴 14.1.3.1 批量任务的 id（写路径的凭证）") String bulkActionId,
      @Schema(description = "🔴 护栏快照") Map<String, Object> guardrail,
      @Schema(description = "幂等键") String idempotencyKey,
      @Schema(description = "整批被拒的原因") String reason,
      @Schema(description = "备注") String note,
      @Schema(description = "创建时间") OffsetDateTime createdAt,
      @Schema(description = "逐条明细") List<AdBiddingAdjustmentItemView> items) {}

  /** 真分页的一页调整任务。 */
  public record AdBiddingAdjustmentPage(
      @Schema(description = "本页条目") List<AdBiddingAdjustmentSummary> items,
      @Schema(description = "筛选后的总条数") long total,
      @Schema(description = "页码（从 0 起）") int page,
      @Schema(description = "每页条数") int size) {}

  // ==================================================================
  // 待下发队列
  // ==================================================================

  /**
   * 一条待下发记录。
   *
   * @param dispatchStatus PENDING <b>待下发</b> / DISPATCHED 已下发 / FAILED 失败 / CANCELLED 已撤销
   */
  public record AdBiddingDispatchView(
      @Schema(description = "下发记录 id") String id,
      @Schema(description = "策略 id（**每条下发都挂一条策略**：可见性随策略 → 店铺收敛）")
          String policyId,
      @Schema(description = "调整任务 id") String adjustmentId,
      @Schema(description = "调整明细 id") String adjustmentItemId,
      @Schema(description = "平台 id") String platformId,
      @Schema(description = "平台编码") String platformCode,
      @Schema(description = "店铺编码") String shopCode,
      @Schema(description = "外部活动 id") String externalCampaignId,
      @Schema(description = "外部关键词 id") String externalKeywordId,
      @Schema(description = "关键词原文（快照）") String keywordText,
      @Schema(description = "改价前的竞价（快照）") BigDecimal beforeBid,
      @Schema(description = "改价后的竞价（快照）") BigDecimal afterBid,
      @Schema(description = "动作类型：SET_BID / SET_BIDDING_STRATEGY") String actionType,
      @Schema(description = "🔴 下发载荷（平台适配器要什么就放什么）") Map<String, Object> actionPayload,
      @Schema(description = "🔴 队列状态：PENDING 待下发 / DISPATCHED / FAILED / CANCELLED")
          String dispatchStatus,
      @Schema(description = "下发时刻") OffsetDateTime dispatchedAt,
      @Schema(description = "下发回执") String dispatchRef,
      @Schema(description = "撤销时刻") OffsetDateTime cancelledAt,
      @Schema(description = "撤销原因") String cancelReason,
      @Schema(description = "备注") String note,
      @Schema(description = "入队时刻") OffsetDateTime createdAt) {}

  /** 真分页的一页待下发记录。 */
  public record AdBiddingDispatchPage(
      @Schema(description = "本页条目") List<AdBiddingDispatchView> items,
      @Schema(description = "筛选后的总条数") long total,
      @Schema(description = "页码（从 0 起）") int page,
      @Schema(description = "每页条数") int size) {}

  /** 撤销一条待下发记录。 */
  public record AdBiddingDispatchCancelRequest(
      @Schema(description = "🔴 撤销原因（必填：不写清楚为什么撤销，下周就没人知道这条为什么没了）")
          String reason) {}
}
