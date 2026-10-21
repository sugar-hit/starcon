package com.starcon.channel;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 14.1.1.3 批量操作 的传输模型。
 *
 * <p>蓝图：「跨活动批量调整：预算增减、竞价调整、状态切换、结束时间设置，支持按筛选结果全选并预览变更。
 * 输入：选中活动集合；调整参数；预览确认。输出：批量调整任务（异步）+ 每条执行结果。
 * 关系：上游 = 14.1.1.1；下游 = 平台广告 API（经 2 队列）。
 * <b>批量调价要有单次上限与总消耗上限保护</b>，误操作一次可能烧掉整月预算。」
 *
 * <p>🔴 这个模型层钉死本模块最容易做丢的四件事：
 *
 * <ol>
 *   <li><b>「按筛选结果全选」是服务端能力</b>。{@code AdBulkSelection} 的形状里只有筛选条件，
 *       <b>没有</b>「把 id 拉回前端再循环」的入口 —— 前端能做的只是把筛选条件递过来。
 *   <li><b>预算是钱，钱要能对账</b>。预览同时给 {@code totalBudgetDelta}（净变化，带方向）
 *       与 {@code totalBudgetDeltaAbs}（绝对敞口，护栏按它判）——
 *       「+1000 与 −1000 净变化是 0，但动的是 2000 的钱」，只报净变化会让护栏形同虚设。
 *   <li><b>没有消耗预估模型就不编</b>。{@code spendImpactEstimated} 恒为 false，
 *       {@code spendImpactNote} 说清「为什么没有」—— 编一个假的消耗预估会被当成事实。
 *   <li><b>「已生效」不等于「平台侧已生效」</b>。每条结果都带 {@code pendingPlatformWriteback}，
 *       界面必须显式标出「本地已改、还没回写平台」。
 * </ol>
 *
 * <p>🔴 金额与数量一律 {@link BigDecimal}，**没有 double**；时间用 {@link OffsetDateTime}
 * （timestamptz），**不用** LocalDateTime —— 用 {@code Timestamp.valueOf(LocalDateTime...)}
 * 绑定会静默偏 −28800s。
 */
public final class AdBulkOperationModels {
  private AdBulkOperationModels() {}

  // ================================================================ 能力声明

  public record AdBulkCapabilityDimension(
      @Schema(description = "维度键") String dimension,
      @Schema(description = "维度说明") String description,
      @Schema(description = "本模块是否已交付") boolean available,
      @Schema(description = "数据来源（表 / 上游模块）") String source,
      @Schema(description = "未交付段的责任模块") String ownerModule,
      @Schema(description = "不交付的影响") String impact) {}

  /**
   * 批量调整的护栏上限。
   *
   * <p>🔴 蓝图 14 章关键约束：「批量调价要有**单次上限**与**总消耗上限**保护」。
   * 上限**随响应返回**（界面上要能显示「本次最多 N 个」），并且**随任务快照进表**
   * ——「这一批当时的天花板是多少」是事后可查的事实。
   */
  public record AdBulkGuardrails(
      @Schema(description = "单个活动一次允许调整的预算上限（绝对值）", type = "string")
          BigDecimal maxSingleBudgetDelta,
      @Schema(description = "一次批量允许的总预算调整上限（绝对值，跨条目累加）", type = "string")
          BigDecimal maxTotalBudgetDelta,
      @Schema(description = "单个活动的预算上限（调整后的结果值不得超过）", type = "string")
          BigDecimal maxCampaignBudget,
      @Schema(description = "单个广告组允许的竞价上限（绝对值）", type = "string") BigDecimal maxBid,
      @Schema(description = "单个广告组一次允许调整的竞价上限（绝对值）", type = "string")
          BigDecimal maxSingleBidDelta,
      @Schema(description = "一次批量允许的总竞价调整上限（绝对值，跨条目累加）", type = "string")
          BigDecimal maxTotalBidDelta,
      @Schema(description = "一个批量任务最多允许的活动条数") int maxCampaignsPerTask,
      @Schema(description = "一个批量任务最多允许的广告组条数（竞价任务）") int maxGroupsPerTask,
      @Schema(description = "一次筛选最多允许解析出的条数（超过 ⇒ 整批拒绝，请缩小筛选范围）")
          int maxSelectablePerFilter,
      @Schema(description = "预览最多返回多少条明细（超出部分只给计数）") int maxPreviewItems,
      @Schema(description = "护栏说明") String note) {}

  public record AdBulkCapabilities(
      @Schema(description = "逐维度能力声明；未交付段 available=false")
          List<AdBulkCapabilityDimension> dimensions,
      @Schema(description = "操作类型取值") List<String> operations,
      @Schema(description = "调整落点：CAMPAIGN（活动）/ AD_GROUP（广告组）") List<String> targetTypes,
      @Schema(description = "活动状态取值") List<String> statuses,
      @Schema(description = "🔴 本次生效的护栏上限") AdBulkGuardrails guardrails,
      @Schema(description = "🔴 竞价调整为什么没有消耗预估") String spendImpactNote,
      @Schema(description = "🔴 「本地已改、还没回写平台」的说明") String writebackNote,
      @Schema(description = "上游依赖说明") String upstreamNote,
      @Schema(description = "下游与边界说明") String boundaryNote) {}

  // ================================================================ 选项

  public record AdBulkOption(
      @Schema(description = "选项值") String value, @Schema(description = "选项显示名") String label) {}

  public record AdBulkOptions(
      @Schema(description = "平台下拉项") List<AdBulkOption> platforms,
      @Schema(description = "店铺下拉项") List<AdBulkOption> shops,
      @Schema(description = "状态下拉项") List<AdBulkOption> statuses,
      @Schema(description = "内部广告类型下拉项") List<AdBulkOption> internalAdTypes,
      @Schema(description = "操作类型下拉项") List<AdBulkOption> operations,
      @Schema(description = "🔴 护栏上限（界面上要能写明天花板）") AdBulkGuardrails guardrails,
      @Schema(description = "🔴 竞价调整为什么没有消耗预估") String spendImpactNote,
      @Schema(description = "说明") String note) {}

  // ================================================================ 选择（按筛选全选）

  /**
   * 服务端按筛选条件解析出的 id 集合的一页。
   *
   * <p>🔴 **分页 + 游标 + 硬上限**：解析发生在 SQL 里，不是在浏览器里把 id 全拉回来再循环。
   * {@code truncated} 为 true 表示「筛选结果超过单次上限」——
   * 这时候批量任务会**整批拒绝**，必须先缩小筛选范围。
   */
  public record AdBulkSelection(
      @Schema(description = "当前页的目标") List<AdBulkSelectedRow> items,
      @Schema(description = "筛选命中的总条数（不受分页影响）") long total,
      @Schema(description = "下一页游标（为空表示没有下一页）") String nextCursor,
      @Schema(description = "🔴 命中条数是否超过单次上限（超过 ⇒ 批量任务整批拒绝）") boolean truncated,
      @Schema(description = "本次生效的单次上限") int limit,
      @Schema(description = "说明") String note) {}

  /** 解析出的一个目标（活动或广告组）。 */
  public record AdBulkSelectedRow(
      @Schema(description = "调整落点：CAMPAIGN / AD_GROUP") String targetType,
      @Schema(description = "活动 id") UUID campaignId,
      @Schema(description = "活动名") String campaignName,
      @Schema(description = "店铺编码") String shopCode,
      @Schema(description = "平台编码") String platformCode,
      @Schema(description = "活动状态") String status,
      @Schema(description = "活动日预算（null = 平台侧没有预算，不是 0）", type = "string")
          BigDecimal dailyBudget,
      @Schema(description = "活动币种") String currency,
      @Schema(description = "活动结束时间") OffsetDateTime endAt,
      @Schema(description = "广告组 id（AD_GROUP 时有值）") UUID groupId,
      @Schema(description = "广告组名") String groupName,
      @Schema(description = "广告组默认竞价（null = 平台侧没有给默认竞价）", type = "string")
          BigDecimal defaultBid,
      @Schema(description = "竞价币种") String bidCurrency) {}

  // ================================================================ 请求

  /**
   * 预览与提交批量任务**共用**的请求形状。
   *
   * <p>🔴 两个端点吃同一份载荷，是刻意的：预览算的就是提交会执行的 ——
   * 两份形状不同的请求迟早会出现「预览的是一个意思、提交的是另一个意思」。
   *
   * <p>🔴 选择方式二选一：给了 {@code campaignIds} 就是**显式勾选**，没给就是
   * **按筛选结果全选**。两种都由服务端解析与校验，不信任调用方给的 id。
   */
  public record AdBulkOperationRequest(
      @Schema(description = "平台 id（筛选用）") UUID platformId,
      @Schema(description = "店铺编码（筛选用）") String shopCode,
      @Schema(description = "活动状态（筛选用）") String status,
      @Schema(description = "归一后的内部广告类型（筛选用）") String internalAdType,
      @Schema(description = "原始平台类型标记（筛选用）") String platformAdType,
      @Schema(description = "名称 / 平台侧 id 关键词（筛选用）") String keyword,
      @Schema(description = "🔴 显式勾选的活动 id；给了它就不走筛选") List<UUID> campaignIds,
      @Schema(description = "🔴 调整落点：CAMPAIGN（默认）/ AD_GROUP（竞价）") String targetType,
      @Schema(
              requiredMode = Schema.RequiredMode.REQUIRED,
              description = "操作类型：ADJUST_BUDGET / SET_BUDGET / PAUSE / ENABLE / SET_END_AT / ADJUST_BID")
          @NotBlank
          String operation,
      @Schema(description = "SET_BUDGET 用：目标日预算") BigDecimal budget,
      @Schema(description = "ADJUST_BUDGET 用：预算增减额（可正可负）") BigDecimal delta,
      @Schema(description = "ADJUST_BID 用：目标竞价；给了它就是「设为」") BigDecimal bid,
      @Schema(description = "ADJUST_BID 用：竞价增减额（与 bid 二选一）") BigDecimal bidDelta,
      @Schema(description = "SET_END_AT 用：目标结束时刻") OffsetDateTime endAt,
      @Schema(description = "SET_END_AT 用：清空结束时间（与 endAt 二选一）") Boolean clearEndAt,
      @Schema(description = "操作理由（写进审计）") String reason) {}

  // ================================================================ 预览

  /** 预览里的一条：会变成什么样。 */
  public record AdBulkPreviewItem(
      @Schema(description = "调整落点：CAMPAIGN / AD_GROUP") String targetType,
      @Schema(description = "活动 id") UUID campaignId,
      @Schema(description = "活动名") String campaignName,
      @Schema(description = "广告组 id（AD_GROUP 时有值）") UUID groupId,
      @Schema(description = "广告组名") String groupName,
      @Schema(description = "变更前状态") String beforeStatus,
      @Schema(description = "变更后状态") String afterStatus,
      @Schema(description = "变更前日预算", type = "string") BigDecimal beforeBudget,
      @Schema(description = "变更后日预算", type = "string") BigDecimal afterBudget,
      @Schema(description = "预算增量（带方向）", type = "string") BigDecimal budgetDelta,
      @Schema(description = "变更前竞价", type = "string") BigDecimal beforeBid,
      @Schema(description = "变更后竞价", type = "string") BigDecimal afterBid,
      @Schema(description = "竞价增量（带方向）", type = "string") BigDecimal bidDelta,
      @Schema(description = "变更前结束时间") OffsetDateTime beforeEndAt,
      @Schema(description = "变更后结束时间") OffsetDateTime afterEndAt,
      @Schema(description = "预演结论：APPLY / SKIP / REJECT") String verdict,
      @Schema(description = "逐条说明（被拒时给出原因）") String message) {}

  /**
   * 变更预览（**不落库**）。
   *
   * <p>🔴 {@code allowed=false} 时{@code items} 依然给出来 ——
   * 让运营看到「正因为第 7 条越了上限所以整批没动」，比只给一句「超限了」有用得多。
   */
  public record AdBulkPreview(
      @Schema(description = "🔴 是否允许整批执行（超上限 ⇒ 整批拒绝，不做部分执行）") boolean allowed,
      @Schema(description = "整批被拒的原因（allowed=false 时有内容）") String rejectionReason,
      @Schema(description = "操作类型") String operation,
      @Schema(description = "调整落点") String targetType,
      @Schema(description = "解析出的目标总条数") int targetCount,
      @Schema(description = "🔴 命中的总条数（解析阶段的硬上限判定用）") long matchedCount,
      @Schema(description = "预览明细条数") int previewItemCount,
      @Schema(description = "🔴 预览明细是否被截断（超出部分只给计数）") boolean previewTruncated,
      @Schema(description = "🔴 总预算净增量（带方向）", type = "string") BigDecimal totalBudgetDelta,
      @Schema(description = "🔴 总预算绝对敞口（护栏按它判）", type = "string")
          BigDecimal totalBudgetDeltaAbs,
      @Schema(description = "🔴 总竞价净增量（带方向）", type = "string") BigDecimal totalBidDelta,
      @Schema(description = "🔴 总竞价绝对敞口（护栏按它判）", type = "string") BigDecimal totalBidDeltaAbs,
      @Schema(description = "🔴 是否给出了消耗预估（恒为 false：没有预估模型）")
          boolean spendImpactEstimated,
      @Schema(description = "🔴 消耗预估说明（为什么没有）") String spendImpactNote,
      @Schema(description = "🔴 本次生效的护栏上限") AdBulkGuardrails guardrails,
      @Schema(description = "🔴 逐条预演结果") List<AdBulkPreviewItem> items,
      @Schema(description = "说明") String note) {}

  // ================================================================ 任务

  public record AdBulkTaskRow(
      @Schema(description = "任务 id") UUID id,
      @Schema(description = "操作类型") String operation,
      @Schema(description = "调整落点") String targetType,
      @Schema(description = "🔴 任务状态：PENDING / RUNNING / DONE / PARTIAL / FAILED / REJECTED")
          String status,
      @Schema(description = "目标总条数") int targetCount,
      @Schema(description = "已生效条数") int appliedCount,
      @Schema(description = "被拒条数") int rejectedCount,
      @Schema(description = "跳过条数") int skippedCount,
      @Schema(description = "🔴 总预算净增量", type = "string") BigDecimal totalBudgetDelta,
      @Schema(description = "🔴 总预算绝对敞口", type = "string") BigDecimal totalBudgetDeltaAbs,
      @Schema(description = "🔴 总竞价净增量", type = "string") BigDecimal totalBidDelta,
      @Schema(description = "🔴 总竞价绝对敞口", type = "string") BigDecimal totalBidDeltaAbs,
      @Schema(description = "选择方式：FILTER（按筛选全选）/ EXPLICIT（显式勾选）") String selectionMode,
      @Schema(description = "🔴 当时的选择条件（可读摘要，供复盘）") String selectionSummary,
      @Schema(description = "目标日预算参数", type = "string") BigDecimal paramBudget,
      @Schema(description = "预算增减额参数", type = "string") BigDecimal paramDelta,
      @Schema(description = "目标竞价参数", type = "string") BigDecimal paramBid,
      @Schema(description = "竞价增减额参数", type = "string") BigDecimal paramBidDelta,
      @Schema(description = "目标结束时刻参数") OffsetDateTime paramEndAt,
      @Schema(description = "是否清空结束时间") boolean paramClearEndAt,
      @Schema(description = "操作理由") String reason,
      @Schema(description = "发起人（已认证成员）") UUID requestedBy,
      @Schema(description = "🔴 整批被拒 / 执行失败时的原因") String message,
      @Schema(description = "创建时刻") OffsetDateTime createdAt,
      @Schema(description = "开始执行时刻") OffsetDateTime startedAt,
      @Schema(description = "结束时刻") OffsetDateTime finishedAt) {}

  public record AdBulkTaskItemRow(
      @Schema(description = "任务项 id") UUID id,
      @Schema(description = "调整落点：CAMPAIGN / AD_GROUP") String targetType,
      @Schema(description = "活动 id") UUID campaignId,
      @Schema(description = "活动名") String campaignName,
      @Schema(description = "广告组 id（AD_GROUP 时有值）") UUID groupId,
      @Schema(description = "广告组名") String groupName,
      @Schema(description = "变更前状态") String beforeStatus,
      @Schema(description = "变更后状态") String afterStatus,
      @Schema(description = "变更前日预算", type = "string") BigDecimal beforeBudget,
      @Schema(description = "变更后日预算", type = "string") BigDecimal afterBudget,
      @Schema(description = "变更前竞价", type = "string") BigDecimal beforeBid,
      @Schema(description = "变更后竞价", type = "string") BigDecimal afterBid,
      @Schema(description = "变更前结束时间") OffsetDateTime beforeEndAt,
      @Schema(description = "变更后结束时间") OffsetDateTime afterEndAt,
      @Schema(description = "执行结果：PENDING / APPLIED / REJECTED / SKIPPED") String result,
      @Schema(description = "逐条结果说明（被拒时给出原因）") String message,
      @Schema(description = "🔴 本地已改、尚未回写平台（平台回写队列未交付）")
          boolean pendingPlatformWriteback,
      @Schema(description = "执行时刻") OffsetDateTime executedAt) {}

  public record AdBulkTaskDetail(
      @Schema(description = "任务") AdBulkTaskRow task,
      @Schema(description = "逐条执行结果（当前页）") List<AdBulkTaskItemRow> items,
      @Schema(description = "任务项总条数") long itemTotal,
      @Schema(description = "页码，从 0 起") int page,
      @Schema(description = "每页条数") int size,
      @Schema(description = "说明") String note) {}

  public record AdBulkTaskPage(
      @Schema(description = "当前页数据") List<AdBulkTaskRow> items,
      @Schema(description = "总条数") long total,
      @Schema(description = "页码，从 0 起") int page,
      @Schema(description = "每页条数") int size) {}

  // ================================================================ 内部件

  /**
   * 一次解析的结果：目标 + 它们的当前值。
   *
   * <p>🔴 解析**只在服务端做**：「按筛选结果全选」如果在前端做，等于把 id 全拉回来再循环。
   */
  public record AdBulkTarget(
      @Schema(description = "调整落点") String targetType,
      @Schema(description = "活动 id") UUID campaignId,
      @Schema(description = "活动名") String campaignName,
      @Schema(description = "店铺编码") String shopCode,
      @Schema(description = "活动状态") String status,
      @Schema(description = "活动日预算", type = "string") BigDecimal dailyBudget,
      @Schema(description = "活动结束时间") OffsetDateTime endAt,
      @Schema(description = "广告组 id") UUID groupId,
      @Schema(description = "广告组名") String groupName,
      @Schema(description = "广告组默认竞价", type = "string") BigDecimal defaultBid,
      @Schema(description = "竞价币种") String bidCurrency) {}

  /** 解析 + 判护栏的结果。 */
  public record AdBulkResolution(
      @Schema(description = "解析出的目标") List<AdBulkTarget> targets,
      @Schema(description = "筛选命中的总条数") long matchedCount,
      @Schema(description = "🔴 是否超过单次上限（超过 ⇒ 整批拒绝）") boolean overLimit,
      @Schema(description = "🔴 整批被拒的原因") String rejectionReason,
      @Schema(description = "选择方式：FILTER / EXPLICIT") String selectionMode,
      @Schema(description = "选择条件摘要") String selectionSummary) {}

  /** 一条预演 / 执行的结果。 */
  public record AdBulkPlanItem(
      @Schema(description = "调整落点") String targetType,
      @Schema(description = "活动 id") UUID campaignId,
      @Schema(description = "活动名") String campaignName,
      @Schema(description = "广告组 id") UUID groupId,
      @Schema(description = "广告组名") String groupName,
      @Schema(description = "变更前状态") String beforeStatus,
      @Schema(description = "变更后状态") String afterStatus,
      @Schema(description = "变更前日预算", type = "string") BigDecimal beforeBudget,
      @Schema(description = "变更后日预算", type = "string") BigDecimal afterBudget,
      @Schema(description = "预算增量", type = "string") BigDecimal budgetDelta,
      @Schema(description = "变更前竞价", type = "string") BigDecimal beforeBid,
      @Schema(description = "变更后竞价", type = "string") BigDecimal afterBid,
      @Schema(description = "竞价增量", type = "string") BigDecimal bidDelta,
      @Schema(description = "变更前结束时间") OffsetDateTime beforeEndAt,
      @Schema(description = "变更后结束时间") OffsetDateTime afterEndAt,
      @Schema(description = "结论：APPLY / SKIP / REJECT") String verdict,
      @Schema(description = "说明") String message) {}

  /** 计划 + 护栏裁决。 */
  public record AdBulkPlan(
      @Schema(description = "逐条计划") List<AdBulkPlanItem> items,
      @Schema(description = "总预算净增量", type = "string") BigDecimal totalBudgetDelta,
      @Schema(description = "总预算绝对敞口", type = "string") BigDecimal totalBudgetDeltaAbs,
      @Schema(description = "总竞价净增量", type = "string") BigDecimal totalBidDelta,
      @Schema(description = "总竞价绝对敞口", type = "string") BigDecimal totalBidDeltaAbs,
      @Schema(description = "🔴 是否允许整批执行") boolean allowed,
      @Schema(description = "🔴 整批被拒的原因") String rejectionReason) {}

  /** 提交任务后落库的参数（服务层内部用）。 */
  public record AdBulkTaskParams(
      @Schema(description = "操作类型") String operation,
      @Schema(description = "调整落点") String targetType,
      @Schema(description = "目标日预算", type = "string") BigDecimal budget,
      @Schema(description = "预算增减额", type = "string") BigDecimal delta,
      @Schema(description = "目标竞价", type = "string") BigDecimal bid,
      @Schema(description = "竞价增减额", type = "string") BigDecimal bidDelta,
      @Schema(description = "目标结束时刻") OffsetDateTime endAt,
      @Schema(description = "是否清空结束时间") boolean clearEndAt,
      @Schema(description = "操作理由") String reason) {}

  /** 供测试与其它模块复用的护栏上限（构造任务快照用）。 */
  public record AdBulkTaskGuardrailSnapshot(
      @Schema(description = "单次预算调整上限", type = "string") BigDecimal maxSingleBudgetDelta,
      @Schema(description = "总额预算调整上限", type = "string") BigDecimal maxTotalBudgetDelta,
      @Schema(description = "单活动预算上限", type = "string") BigDecimal maxCampaignBudget,
      @Schema(description = "单组竞价上限", type = "string") BigDecimal maxBid,
      @Schema(description = "单次竞价调整上限", type = "string") BigDecimal maxSingleBidDelta,
      @Schema(description = "总额竞价调整上限", type = "string") BigDecimal maxTotalBidDelta,
      @Schema(description = "单任务活动条数上限") int maxCampaigns,
      @Schema(description = "单任务广告组条数上限") int maxGroups) {}

  /** 校验通过后的请求（服务层内部用）。 */
  public record AdBulkValidatedRequest(
      @Schema(description = "操作类型") String operation,
      @Schema(description = "调整落点") String targetType,
      @Schema(description = "目标日预算", type = "string") BigDecimal budget,
      @Schema(description = "预算增减额", type = "string") BigDecimal delta,
      @Schema(description = "目标竞价", type = "string") BigDecimal bid,
      @Schema(description = "竞价增减额", type = "string") BigDecimal bidDelta,
      @Schema(description = "目标结束时刻") OffsetDateTime endAt,
      @Schema(description = "是否清空结束时间") boolean clearEndAt,
      @Schema(description = "操作理由") String reason) {}

  /** 竞价护栏的只读视图（供前端显示）。 */
  public record AdBulkBidGuardrails(
      @Schema(description = "单组竞价上限", type = "string") @PositiveOrZero BigDecimal maxBid,
      @Schema(description = "单次竞价调整上限", type = "string") BigDecimal maxSingleBidDelta,
      @Schema(description = "总额竞价调整上限", type = "string") BigDecimal maxTotalBidDelta) {}

  /** 任务提交的结果（创建 + 是否被领取）。 */
  public record AdBulkTaskSubmission(
      @Schema(description = "任务") AdBulkTaskRow task,
      @Schema(description = "🔴 任务是否已被执行器领取（true = 已在跑）") boolean claimed,
      @Schema(description = "说明") String note) {}

  /** 供执行器读取的任务参数快照。 */
  public record AdBulkTaskExecution(
      @Schema(description = "任务 id") UUID id,
      @Schema(description = "操作类型") String operation,
      @Schema(description = "调整落点") String targetType,
      @Schema(description = "目标日预算", type = "string") BigDecimal budget,
      @Schema(description = "预算增减额", type = "string") BigDecimal delta,
      @Schema(description = "目标竞价", type = "string") BigDecimal bid,
      @Schema(description = "竞价增减额", type = "string") BigDecimal bidDelta,
      @Schema(description = "目标结束时刻") OffsetDateTime endAt,
      @Schema(description = "是否清空结束时间") boolean clearEndAt,
      @Schema(description = "操作理由") String reason,
      @Schema(description = "发起人") UUID requestedBy,
      @Schema(description = "护栏快照") AdBulkTaskGuardrailSnapshot guardrails) {}
}
