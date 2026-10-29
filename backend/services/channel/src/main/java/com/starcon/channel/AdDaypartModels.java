package com.starcon.channel;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 14.2.1.2 分时策略的展示形状与请求体。
 *
 * <p>蓝图：「按小时设置不同竞价与预算（高峰提价提预算、低谷降价停投），
 * 支持按历史出单时段自动推荐分时曲线。输入：分时规则；13.2.1.2 时段分布数据。
 * 输出：分时策略配置 + 定时调整任务。
 * 关系：上游 = 13.2.1.2；下游 = 14.2.2.2 自动规则。
 * <b>分时策略需按店铺时区配置</b>，跨时区店铺用错时区会完全失效。」
 *
 * <p>🔴 <b>形状约定</b>：
 *
 * <ul>
 *   <li><b>金额</b>一律 {@code BigDecimal}（后端 {@code numeric(19,4)}），前端只原样展示，
 *       <b>不在前端做算术</b>。
 *   <li><b>系数 / 数量</b>是 {@code numeric(19,6)}（比例是数量，不是金额）。
 *   <li><b>不可比 ≠ 零</b>：占比类字段为 {@code null} 表示<b>不可比</b>（分母为 0），
 *       {@code 0} 表示<b>可比但真的是零</b> —— 两者含义相反，混用会误导。
 *   <li><b>时区</b>是 IANA 标识（{@code America/Los_Angeles}），不是固定 UTC 偏移。
 * </ul>
 */
public final class AdDaypartModels {

  private AdDaypartModels() {}

  // ==================================================================
  // 能力声明
  // ==================================================================

  /** 一个能力维度（能做的写 {@code available=true}，做不了的**必须**写清责任模块与影响）。 */
  public record AdDaypartCapabilityDimension(
      @Schema(description = "维度码") String dimension,
      @Schema(description = "这个维度是干什么的") String description,
      @Schema(description = "本模块是否真的能做") boolean available,
      @Schema(description = "数据落在哪 / 依赖谁") String source,
      @Schema(description = "🔴 做不了的责任模块（做得了时为空串）") String ownerModule,
      @Schema(description = "🔴 做不了的影响（页面上原样显示）") String impact) {}

  /** 能力声明。 */
  public record AdDaypartCapabilities(
      @Schema(description = "能力维度清单") List<AdDaypartCapabilityDimension> dimensions,
      @Schema(description = "策略状态枚举") List<String> statuses,
      @Schema(description = "作用范围枚举") List<String> scopes,
      @Schema(description = "小时动作枚举") List<String> actions,
      @Schema(description = "运行口径枚举") List<String> runModes,
      @Schema(description = "触发来源枚举") List<String> triggerSources,
      @Schema(description = "推荐批次的上游结论枚举") List<String> upstreamStatuses,
      @Schema(description = "🔴 时区口径说明") String timezoneNote,
      @Schema(description = "🔴 系数与「停投」的实现口径说明") String multiplierNote,
      @Schema(description = "🔴 调整上限的口径说明") String guardrailNote,
      @Schema(description = "🔴 执行路径说明") String executionNote,
      @Schema(description = "🔴 上游依赖说明") String upstreamNote,
      @Schema(description = "🔴 与下游 / 边界模块的关系说明") String boundaryNote) {}

  // ==================================================================
  // 店铺时区
  // ==================================================================

  /**
   * 一个店铺的时区归属结论。
   *
   * @param shopTimezone IANA 标识；<b>定不了时区时是空串</b>（不是猜一个）
   * @param timezoneStatus RESOLVED 定得了 / UNRESOLVED 定不了 / CONFLICT 与上游解析不一致
   */
  public record AdDaypartShopTimezone(
      @Schema(description = "店铺编码") String shopCode,
      @Schema(description = "店铺名") String shopName,
      @Schema(description = "店铺 id") String shopId,
      @Schema(description = "🔴 店铺本地时区（IANA 标识；定不了时区时是空串）") String shopTimezone,
      @Schema(description = "时区归属状态") String timezoneStatus,
      @Schema(description = "定不了 / 冲突的原因（页面上原样显示）") String timezoneReason,
      @Schema(description = "启用的站点数") int activeSiteCount,
      @Schema(description = "启用的站点各自的默认时区（多个且不一致时这里能看出来）")
          List<String> siteTimezones) {}

  // ==================================================================
  // 分时策略
  // ==================================================================

  /** 一个小时的曲线配置。 */
  public record AdDaypartHourConfig(
      @Schema(description = "🔴 店铺本地小时（0..23）") int localHour,
      @Schema(description = "动作：KEEP / BOOST / REDUCE / PAUSE") String action,
      @Schema(description = "🔴 竞价系数（相对基准；1 = 不动）") BigDecimal bidMultiplier,
      @Schema(description = "🔴 预算系数（相对基准；0 = 停投）") BigDecimal budgetMultiplier,
      @Schema(description = "来源：MANUAL 人工 / RECOMMENDED 推荐生成") String source,
      @Schema(description = "判定依据（页面上原样显示）") String basisNote) {}

  /** 策略详情（带 24 小时曲线）。 */
  public record AdDaypartStrategyView(
      String id,
      String strategyCode,
      String strategyName,
      String shopCode,
      String shopName,
      @Schema(description = "🔴 店铺本地时区（IANA 标识；定不了时区时是空串）") String shopTimezone,
      String timezoneStatus,
      String timezoneReason,
      String scope,
      String targetCampaignId,
      String targetCampaignName,
      @Schema(description = "策略币种（跨币种的活动 / 关键词逐条拒绝）") String currency,
      String status,
      BigDecimal singleAdjustCapAmount,
      BigDecimal singleAdjustCapPercent,
      BigDecimal totalCapAmount,
      int totalCapCount,
      int totalCapWindowHours,
      BigDecimal minBudget,
      BigDecimal maxBudget,
      BigDecimal minBid,
      BigDecimal maxBid,
      @Schema(description = "🔴 试运行：true = 只算不调") boolean dryRun,
      boolean scheduleEnabled,
      int runIntervalMinutes,
      BigDecimal recommendPeakShareMultiple,
      BigDecimal recommendTroughShareMultiple,
      BigDecimal recommendPauseShareMultiple,
      BigDecimal recommendBoostBidMultiplier,
      BigDecimal recommendBoostBudgetMultiplier,
      BigDecimal recommendReduceBidMultiplier,
      BigDecimal recommendReduceBudgetMultiplier,
      BigDecimal recommendPauseBidMultiplier,
      BigDecimal recommendPauseBudgetMultiplier,
      OffsetDateTime lastRunAt,
      OffsetDateTime lastAppliedAt,
      @Schema(description = "24 个小时曲线") List<AdDaypartHourConfig> hours,
      String note,
      long version,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 策略列表项。 */
  public record AdDaypartStrategySummary(
      String id,
      String strategyCode,
      String strategyName,
      String shopCode,
      String shopName,
      String shopTimezone,
      String timezoneStatus,
      String timezoneReason,
      String scope,
      String currency,
      String status,
      boolean dryRun,
      boolean scheduleEnabled,
      @Schema(description = "曲线里真正在动的小时数（24 = 全天都在调）") int effectiveHourCount,
      OffsetDateTime lastRunAt,
      OffsetDateTime updatedAt) {}

  /** 策略列表（真分页）。 */
  public record AdDaypartStrategyPage(
      @Schema(description = "当前页数据") List<AdDaypartStrategySummary> items,
      @Schema(description = "符合条件的总条数") long total,
      int page,
      int size) {}

  // ==================================================================
  // 请求体
  // ==================================================================

  /** 新建 / 改一条策略（曲线不在这里，曲线走整体替换端点）。 */
  public record AdDaypartStrategyRequest(
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "策略编码（租户内唯一）")
          String strategyCode,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "策略名") String strategyName,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "店铺编码") String shopCode,
      @Schema(description = "作用范围：ALL_CAMPAIGNS / CAMPAIGN（默认 ALL_CAMPAIGNS）") String scope,
      @Schema(description = "scope=CAMPAIGN 时指定的活动 id") String targetCampaignId,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "币种（大写 3 位）")
          String currency,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "单次调整上限（金额）")
          BigDecimal singleAdjustCapAmount,
      @Schema(description = "单次调整上限（相对基准的偏离比例；0 = 不额外限制）")
          BigDecimal singleAdjustCapPercent,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "窗口内累计上调上限（金额）")
          BigDecimal totalCapAmount,
      @Schema(description = "窗口内累计调整次数上限") Integer totalCapCount,
      @Schema(description = "上限窗口长度（小时）") Integer totalCapWindowHours,
      BigDecimal minBudget,
      BigDecimal maxBudget,
      BigDecimal minBid,
      BigDecimal maxBid,
      @Schema(description = "试运行（默认 true）") Boolean dryRun,
      @Schema(description = "是否参与外部调度（默认 false）") Boolean scheduleEnabled,
      @Schema(description = "调度间隔（分钟，默认 60）") Integer runIntervalMinutes,
      BigDecimal recommendPeakShareMultiple,
      BigDecimal recommendTroughShareMultiple,
      BigDecimal recommendPauseShareMultiple,
      BigDecimal recommendBoostBidMultiplier,
      BigDecimal recommendBoostBudgetMultiplier,
      BigDecimal recommendReduceBidMultiplier,
      BigDecimal recommendReduceBudgetMultiplier,
      BigDecimal recommendPauseBidMultiplier,
      BigDecimal recommendPauseBudgetMultiplier,
      String note) {}

  /** 曲线的一小时（请求体）。 */
  public record AdDaypartHourInput(
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "店铺本地小时（0..23）")
          Integer localHour,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "KEEP / BOOST / REDUCE / PAUSE")
          String action,
      @Schema(description = "竞价系数（不给 = 1）") BigDecimal bidMultiplier,
      @Schema(description = "预算系数（不给 = 1；PAUSE 必须为 0）") BigDecimal budgetMultiplier,
      String basisNote) {}

  /** 整体替换曲线（一次成型，避免逐条改时中间态缺小时）。 */
  public record AdDaypartCurveRequest(
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "完整的小时集合（应覆盖 0..23）")
          List<AdDaypartHourInput> hours) {}

  /** 改策略状态（激活 / 暂停 / 归档）。 */
  public record AdDaypartStatusRequest(
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "DRAFT / ACTIVE / PAUSED / ARCHIVED")
          String status,
      String note) {}

  /** 生成推荐曲线（上游 13.2.1.2）。 */
  public record AdDaypartRecommendationRequest(
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "策略 id（推荐按店铺生成）")
          String strategyId,
      @Schema(description = "口径窗口起（默认：DB 今天往前 29 天）") LocalDate from,
      @Schema(description = "口径窗口止（默认：DB 今天）") LocalDate to) {}

  /** 把推荐曲线套到策略上。 */
  public record AdDaypartApplyRecommendationRequest(
      @Schema(description = "要套用的小时（空 = 24 个小时全套）") List<Integer> hours) {}

  /** 跑一轮定时调整任务。 */
  public record AdDaypartRunRequest(
      @Schema(description = "策略 id（不给 = 跑所有「参与调度且生效中」的策略）") String strategyId,
      @Schema(description = "运行口径：DRY_RUN 只算不调 / APPLY 真调（默认 DRY_RUN）") String runMode,
      @Schema(description = "触发来源：MANUAL / SCHEDULED（默认 MANUAL）") String triggerSource,
      String note) {}

  // ==================================================================
  // 推荐曲线
  // ==================================================================

  /** 推荐批次的一小时。 */
  public record AdDaypartRecommendationHourView(
      @Schema(description = "🔴 店铺本地小时（0..23）") int localHour,
      @Schema(description = "该小时的订单笔数") BigDecimal orderCount,
      @Schema(description = "🔴 该小时的订单占比（null = 不可比，不是 0）") BigDecimal orderShare,
      @Schema(description = "上游的 P90 高峰刻度是否命中") boolean p90Peak,
      @Schema(description = "建议动作") String suggestedAction,
      @Schema(description = "建议竞价系数") BigDecimal suggestedBidMultiplier,
      @Schema(description = "建议预算系数") BigDecimal suggestedBudgetMultiplier,
      @Schema(description = "判定依据（可复算）") String basisNote) {}

  /** 推荐批次（按店铺）。 */
  public record AdDaypartRecommendationView(
      String id,
      String strategyId,
      String shopCode,
      String shopName,
      @Schema(description = "🔴 店铺本地时区（定不了时区时是空串）") String shopTimezone,
      String timezoneStatus,
      String timezoneReason,
      LocalDate windowFrom,
      LocalDate windowTo,
      @Schema(description = "汇总口径（本模块只有 LOCAL_TIME）") String alignment,
      @Schema(description = "🔴 生成时用的判定参数快照（可复算）") Map<String, Object> profile,
      @Schema(description = "上游结论：OK / SHOP_UNRESOLVED / CONFLICT / NO_FACT") String upstreamStatus,
      OffsetDateTime upstreamDataUpdatedAt,
      String upstreamMessage,
      BigDecimal projectedOrderCount,
      int projectedDays,
      int expectedDays,
      @Schema(description = "小时明细（定不了时区时为空数组 —— 不是「24 个小时都没有」）")
          List<AdDaypartRecommendationHourView> hours,
      OffsetDateTime generatedAt) {}

  // ==================================================================
  // 定时调整任务
  // ==================================================================

  /** 任务的一行明细。 */
  public record AdDaypartTaskLineView(
      String id,
      @Schema(description = "CAMPAIGN_BUDGET / KEYWORD_BID") String targetType,
      String campaignId,
      String campaignName,
      String keywordId,
      @Schema(description = "被调整对象（关键词原文）") String targetRef,
      @Schema(description = "🔴 店铺本地小时") int localHour,
      String action,
      @Schema(description = "调整前的值") BigDecimal beforeValue,
      @Schema(description = "调整后的值（试运行时是「本来会变成多少」）") BigDecimal afterValue,
      @Schema(description = "增减额（正 = 调高）") BigDecimal delta,
      String currency,
      @Schema(description = "APPLIED / REJECTED / SKIPPED / DRY_RUN / PENDING") String result,
      @Schema(description = "🔴 被哪一道闸拦下的（可空 = 没过闸，是业务原因）") String guardrailCode,
      @Schema(description = "逐条原因（没成必须写清为什么）") String message) {}

  /** 任务详情。 */
  public record AdDaypartTaskView(
      String id,
      String strategyId,
      String strategyCode,
      String strategyName,
      String shopCode,
      String shopTimezone,
      String timezoneStatus,
      @Schema(description = "🔴 本轮跑的是店铺本地的第几个小时") int localHour,
      String runMode,
      String triggerSource,
      String status,
      String currency,
      BigDecimal singleAdjustCapAmount,
      BigDecimal singleAdjustCapPercent,
      BigDecimal totalCapAmount,
      int totalCapCount,
      int totalCapWindowHours,
      OffsetDateTime windowFrom,
      OffsetDateTime windowTo,
      int appliedCount,
      int rejectedCount,
      int skippedCount,
      @Schema(description = "🔴 本轮上调金额合计（总上限只管「多花了多少」）") BigDecimal totalIncrease,
      OffsetDateTime executedAt,
      String message,
      String note,
      List<AdDaypartTaskLineView> lines,
      OffsetDateTime createdAt) {}

  /** 任务列表项。 */
  public record AdDaypartTaskSummary(
      String id,
      String strategyId,
      String strategyCode,
      String shopCode,
      String shopTimezone,
      int localHour,
      String runMode,
      String triggerSource,
      String status,
      String currency,
      int appliedCount,
      int rejectedCount,
      int skippedCount,
      BigDecimal totalIncrease,
      OffsetDateTime executedAt,
      OffsetDateTime createdAt) {}

  /** 任务列表（真分页）。 */
  public record AdDaypartTaskPage(
      List<AdDaypartTaskSummary> items, long total, int page, int size) {}

  /**
   * 一轮运行里<b>被跳过</b>的策略。
   *
   * <p>🔴 「跳过」与「跑了但什么都没发生」是<b>两件事</b>：前者压根没进任务表
   * （策略不是生效中 / 店铺时区定不了 / 本小时的曲线是「不动」），
   * 后者进了任务表、有逐条明细（全部 SKIPPED）。混起来会让人以为「系统跑过了，没问题」。
   */
  public record AdDaypartRunSkip(
      @Schema(description = "策略 id") String strategyId,
      @Schema(description = "策略编码") String strategyCode,
      @Schema(description = "店铺编码") String shopCode,
      @Schema(description = "🔴 为什么被跳过") String reason) {}

  /** 一轮运行的回执。 */
  public record AdDaypartRunResult(
      @Schema(description = "这次触发了多少条策略") int strategyCount,
      @Schema(description = "真的跑了多少条") int executedCount,
      @Schema(description = "跳过多少条（不是生效中 / 时区定不了 / 本小时不动）") int skippedCount,
      @Schema(description = "逐条任务的运行结果") List<AdDaypartTaskView> tasks,
      @Schema(description = "🔴 被跳过的策略与原因") List<AdDaypartRunSkip> skips) {}

  // ==================================================================
  // 给下游 14.2.2.2 自动规则用的读取形状
  // ==================================================================

  /** 某店铺当前生效的调整上限（14.2.2.2 执行调整时取用，两边取更严的）。 */
  public record AdDaypartCapView(
      String strategyId,
      String strategyCode,
      String strategyName,
      String status,
      BigDecimal singleAdjustCapAmount,
      BigDecimal singleAdjustCapPercent,
      BigDecimal totalCapAmount,
      int totalCapCount,
      int totalCapWindowHours,
      BigDecimal minBudget,
      BigDecimal maxBudget,
      BigDecimal minBid,
      BigDecimal maxBid,
      boolean dryRun) {}

  /** 某店铺的调整上限汇总。 */
  public record AdDaypartShopCapsView(
      String shopCode,
      String shopName,
      String shopTimezone,
      String timezoneStatus,
      String timezoneReason,
      @Schema(description = "该店铺各条策略的上限") List<AdDaypartCapView> strategies,
      @Schema(description = "🔴 合并后的有效上限（取最严的那个）") AdDaypartCapView effectiveCaps,
      @Schema(description = "口径说明") String note) {}
}
