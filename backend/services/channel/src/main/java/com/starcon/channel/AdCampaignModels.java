package com.starcon.channel;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 14.1.1.1 广告活动列表 的传输模型。
 *
 * <p>蓝图：「广告活动主列表：平台、店铺、活动名、类型、状态、预算、消耗、曝光、点击、订单、销售额、ACOS/ROAS，
 * 按维度筛选与排序，支持批量暂停/启用/调预算。输入：筛选条件（平台、店铺、状态、日期）；广告数据同步结果；用户数据范围。
 * 输出：分页列表 + 汇总；批量操作任务。关系：上游 = 平台广告 API 同步；下游 = 14.1.1.2 详情、14.2.2.1 预算。
 * <b>广告数据同步是本系统的地基</b>：同步失败时页面要明确显示「数据截至 X 时间」，不能用空数据假装没有投放。」
 *
 * <p>🔴 这个模型层钉死本模块最容易做丢的三件事：
 *
 * <ol>
 *   <li><b>「数据截至 X 时间」是模型的一部分，不是文案</b>。{@code dataAsOf}（水位）、
 *       {@code syncStates}（每个平台 + 店铺的同步状态与失败原因）、{@code emptyReason}
 *       （三种「空」的区别）都是**返回结构里的字段** —— 它们不在模型里，页面就只能靠猜。
 *   <li><b>归一类型与原始类型必须并存</b>。{@code platformAdType}（原始标记）与
 *       {@code internalAdType}（归一后类型）同表并存、同条返回，谁也不能只给后者 ——
 *       只给归一类型，「平台 API 原文是什么」就永远答不出来，而那正是 14.1.2.2 的加粗句要保住的东西。
 *   <li><b>ACOS / ROAS 的口径写在返回值里</b>。{@code metricDefinitionNote} 随汇总一起返回，
 *       界面上的表头因此能写明「ACOS = 消耗 / 销售额」，跨部门沟通才不会各说各话。
 * </ol>
 *
 * <p>🔴 金额与数量一律 {@link BigDecimal}，**没有 double**：预算 / 消耗 / 销售额直接进财务与归因，
 * 曝光 / 点击 / 订单是跨平台可比的计数。
 *
 * <p>🔴 比率的 {@code null} 与 {@code 0} 含义相反：{@code null} = 不可比（分母为 0），
 * {@code 0} = 可比但真的是零。
 */
public final class AdCampaignModels {
  private AdCampaignModels() {}

  // ================================================================ 能力声明

  public record AdCampaignCapabilityDimension(
      @Schema(description = "维度键") String dimension,
      @Schema(description = "维度说明") String description,
      @Schema(description = "本模块是否已交付") boolean available,
      @Schema(description = "数据来源（表 / 上游模块）") String source,
      @Schema(description = "未交付段的责任模块") String ownerModule,
      @Schema(description = "不交付的影响") String impact) {}

  /**
   * 批量操作的护栏上限。
   *
   * <p>🔴 蓝图 14 章关键约束：「AI/规则自动调整必须有上限保护」——
   * 误操作一次可能烧掉整月预算。单次上限与总上限**一起返回给界面**，
   * 让运营在点下去之前就知道天花板在哪，而不是点完才被拒。
   */
  public record AdBudgetGuardrails(
      @Schema(description = "单个活动一次允许调整的预算上限（绝对值）", type = "string")
          BigDecimal maxSingleBudgetDelta,
      @Schema(description = "一次批量请求允许的总预算调整上限（绝对值）", type = "string")
          BigDecimal maxTotalBudgetDelta,
      @Schema(description = "单个活动的预算上限（调预算后的结果值不得超过）", type = "string")
          BigDecimal maxCampaignBudget,
      @Schema(description = "一次批量请求最多允许的活动条数") int maxCampaignsPerRequest,
      @Schema(description = "护栏说明") String note) {}

  public record AdCampaignCapabilities(
      @Schema(description = "逐维度能力声明；未交付段 available=false") List<AdCampaignCapabilityDimension> dimensions,
      @Schema(description = "活动状态取值") List<String> statuses,
      @Schema(description = "排序方式取值") List<String> sortOptions,
      @Schema(description = "批量操作类型取值") List<String> operations,
      @Schema(description = "空态原因取值") List<String> emptyReasons,
      @Schema(description = "批量操作护栏上限") AdBudgetGuardrails guardrails,
      @Schema(description = "🔴 ACOS / ROAS 口径定义（随汇总一起给界面，写进表头）") String metricDefinitionNote,
      @Schema(description = "🔴 「数据截至 X 时间」的含义") String dataAsOfNote,
      @Schema(description = "🔴 未映射类型的语义（照抄 14.1.2.2 加粗句）") String unmappedNote,
      @Schema(description = "上游依赖说明") String upstreamNote,
      @Schema(description = "下游与边界说明") String boundaryNote) {}

  // ================================================================ 列表

  /**
   * 活动列表的一行。
   *
   * <p>🔴 「类型」这一列刻意是**两个值**：{@code platformAdType} 是平台原文，
   * {@code internalAdType} 是归一后的内部类型。界面上两者必须同时出现。
   */
  public record AdCampaignRow(
      @Schema(description = "活动 id") UUID id,
      @Schema(description = "平台 id") UUID platformId,
      @Schema(description = "平台编码") String platformCode,
      @Schema(description = "平台名称") String platformName,
      @Schema(description = "店铺编码") String shopCode,
      @Schema(description = "店铺名称") String shopName,
      @Schema(description = "平台侧活动 id") String externalCampaignId,
      @Schema(description = "活动名") String campaignName,
      @Schema(description = "🔴 原始平台类型标记：平台怎么叫就怎么存") String platformAdType,
      @Schema(description = "🔴 归一后的内部类型；UNMAPPED 表示 2.1.2.1 两层都没给归一口径")
          String internalAdType,
      @Schema(description = "🔴 归一口径来源：PLATFORM_DEFAULT / TENANT_OVERRIDE / UNMAPPED")
          String adTypeMappingSource,
      @Schema(description = "🔴 是否参与跨平台类型比较（UNMAPPED 为 false，但活动照样存在）")
          boolean adTypeComparable,
      @Schema(description = "归一后的状态") String status,
      @Schema(description = "币种") String currency,
      @Schema(description = "日预算（null = 平台侧没有预算，不是 0）", type = "string") BigDecimal dailyBudget,
      @Schema(description = "曝光合计（按当前筛选的日期区间）", type = "string") BigDecimal impressions,
      @Schema(description = "点击合计", type = "string") BigDecimal clicks,
      @Schema(description = "订单合计", type = "string") BigDecimal orders,
      @Schema(description = "消耗合计", type = "string") BigDecimal spend,
      @Schema(description = "销售额合计", type = "string") BigDecimal sales,
      @Schema(description = "🔴 点击率（派生列；分母为 0 时为 null=不可比，与 0 含义相反）", type = "string")
          BigDecimal ctr,
      @Schema(description = "🔴 ACOS = 消耗 / 销售额（分母为 0 时为 null=不可比）", type = "string") BigDecimal acos,
      @Schema(description = "🔴 ROAS = 销售额 / 消耗（分母为 0 时为 null=不可比）", type = "string") BigDecimal roas,
      @Schema(description = "开始时间") OffsetDateTime startAt,
      @Schema(description = "结束时间") OffsetDateTime endAt,
      @Schema(description = "🔴 本地已改、尚未回写平台（平台回写队列未交付）")
          boolean pendingPlatformWriteback,
      @Schema(description = "最近一次本地改动时刻") OffsetDateTime localChangedAt,
      @Schema(description = "🔴 这条活动的数据截至时刻") OffsetDateTime dataAsOf,
      @Schema(description = "最近一次同步入库时刻") OffsetDateTime lastSyncedAt,
      @Schema(description = "备注") String note,
      @Schema(description = "乐观锁版本") long version,
      @Schema(description = "创建时刻") OffsetDateTime createdAt,
      @Schema(description = "更新时刻") OffsetDateTime updatedAt) {}

  /**
   * 列表汇总（**不受分页影响**：统计的是筛选后的全集，不是当前页）。
   *
   * <p>🔴 ACOS / ROAS 是「先汇总再相除」：总消耗 / 总销售额，
   * **不是各活动比率的平均** —— 后者是数学错误，会让运营以为整体在盈亏线上。
   */
  public record AdCampaignSummary(
      @Schema(description = "符合筛选条件的活动条数") long campaignCount,
      @Schema(description = "其中投放中的条数") long enabledCount,
      @Schema(description = "其中已暂停的条数") long pausedCount,
      @Schema(description = "其他状态（已结束 / 归档 / 审核中 / 未知）的条数") long otherStatusCount,
      @Schema(description = "日预算合计", type = "string") BigDecimal totalBudget,
      @Schema(description = "曝光合计", type = "string") BigDecimal totalImpressions,
      @Schema(description = "点击合计", type = "string") BigDecimal totalClicks,
      @Schema(description = "订单合计", type = "string") BigDecimal totalOrders,
      @Schema(description = "消耗合计", type = "string") BigDecimal totalSpend,
      @Schema(description = "销售额合计", type = "string") BigDecimal totalSales,
      @Schema(description = "🔴 汇总点击率（分母为 0 时为 null=不可比）", type = "string") BigDecimal ctr,
      @Schema(description = "🔴 汇总 ACOS = 总消耗 / 总销售额（分母为 0 时为 null）", type = "string") BigDecimal acos,
      @Schema(description = "🔴 汇总 ROAS = 总销售额 / 总消耗（分母为 0 时为 null）", type = "string") BigDecimal roas,
      @Schema(description = "实际统计到的最早日期") LocalDate metricFrom,
      @Schema(description = "实际统计到的最晚日期") LocalDate metricTo,
      @Schema(description = "🔴 口径说明（ACOS / ROAS 的分子分母）") String metricDefinitionNote) {}

  /** 一个平台 + 店铺的同步状态（页面上「数据截至 X 时间」的逐条依据）。 */
  public record AdSyncState(
      @Schema(description = "平台 id") UUID platformId,
      @Schema(description = "平台编码") String platformCode,
      @Schema(description = "平台名称") String platformName,
      @Schema(description = "店铺编码") String shopCode,
      @Schema(description = "店铺名称") String shopName,
      @Schema(description = "最近一次同步的状态：SUCCESS / PARTIAL / FAILED") String status,
      @Schema(description = "🔴 数据截至时刻（失败同步不推进水位，因此可能为 null）") OffsetDateTime dataAsOf,
      @Schema(description = "最近一次同步运行的时刻") OffsetDateTime lastRunAt,
      @Schema(description = "该范围下的活动条数") long campaignCount,
      @Schema(description = "🔴 失败原因（FAILED 时必有内容）") String message) {}

  /**
   * 列表响应。
   *
   * <p>🔴 {@code emptyReason} 把「三种空」变成可判定的枚举，而不是一段文案：
   *
   * <ul>
   *   <li>{@code NO_SYNCED_DATA} —— 本租户**还没有任何成功的广告数据同步**。
   *       这是「还没同步到数据」，**不是**「没有投放」。
   *   <li>{@code NO_CAMPAIGNS} —— 同步成功过，但这个范围里确实一条活动都没有。
   *       这是「没有投放」。
   *   <li>{@code FILTERED_EMPTY} —— 活动存在，但被当前筛选条件全部排除。
   * </ul>
   */
  public record AdCampaignPage(
      @Schema(description = "当前页数据") List<AdCampaignRow> items,
      @Schema(description = "符合筛选条件的总条数（不受分页影响）") long total,
      @Schema(description = "页码，从 0 起") int page,
      @Schema(description = "每页条数") int size,
      @Schema(description = "🔴 筛选后全集的汇总（不受分页影响）") AdCampaignSummary summary,
      @Schema(description = "🔴 数据截至时刻（成功同步的水位；从未同步成功过则为 null）")
          OffsetDateTime dataAsOf,
      @Schema(description = "🔴 「数据截至 X 时间」的口径说明") String dataAsOfNote,
      @Schema(description = "🔴 逐平台 + 店铺的同步状态与失败原因") List<AdSyncState> syncStates,
      @Schema(description = "🔴 空态原因：NO_SYNCED_DATA / NO_CAMPAIGNS / FILTERED_EMPTY / null")
          String emptyReason,
      @Schema(description = "🔴 空态说明文案（三种空的解释各不相同）") String emptyNote,
      @Schema(description = "🔴 ACOS / ROAS 口径定义") String metricDefinitionNote) {}

  // ================================================================ 详情与明细

  public record AdCampaignDailyMetricRow(
      @Schema(description = "自然日（平台当地日）") LocalDate metricDate,
      @Schema(description = "曝光量", type = "string") BigDecimal impressions,
      @Schema(description = "点击量", type = "string") BigDecimal clicks,
      @Schema(description = "订单量", type = "string") BigDecimal orders,
      @Schema(description = "消耗", type = "string") BigDecimal spend,
      @Schema(description = "销售额", type = "string") BigDecimal sales,
      @Schema(description = "点击率（分母为 0 时为 null）", type = "string") BigDecimal ctr,
      @Schema(description = "ACOS = 消耗 / 销售额（分母为 0 时为 null）", type = "string") BigDecimal acos,
      @Schema(description = "ROAS = 销售额 / 消耗（分母为 0 时为 null）", type = "string") BigDecimal roas,
      @Schema(description = "同步入库时刻") OffsetDateTime syncedAt) {}

  public record AdCampaignOperationLogRow(
      @Schema(description = "审计 id") UUID id,
      @Schema(description = "活动 id") UUID campaignId,
      @Schema(description = "操作类型") String operation,
      @Schema(description = "变更前状态") String beforeStatus,
      @Schema(description = "变更后状态") String afterStatus,
      @Schema(description = "变更前日预算", type = "string") BigDecimal beforeBudget,
      @Schema(description = "变更后日预算", type = "string") BigDecimal afterBudget,
      @Schema(description = "发起人（已认证成员）") UUID requestedBy,
      @Schema(description = "操作理由") String reason,
      @Schema(description = "执行结果：APPLIED / REJECTED / SKIPPED") String result,
      @Schema(description = "结果说明（被拒时给出原因）") String message,
      @Schema(description = "发生时刻") OffsetDateTime createdAt) {}

  public record AdCampaignDetail(
      @Schema(description = "活动本体") AdCampaignRow campaign,
      @Schema(description = "每日表现明细（按日期倒序）") List<AdCampaignDailyMetricRow> dailyMetrics,
      @Schema(description = "操作审计（按时刻倒序）") List<AdCampaignOperationLogRow> operationLogs,
      @Schema(description = "说明") String note) {}

  /**
   * 按**平台侧活动 id** 取活动的当前状态与预算 / 消耗。
   *
   * <p>🔴 这是给 14.2.2.2 自动规则（以及 14.2.2.1 预算分配）用的读取形状：
   * 规则引擎要知道「这条活动现在什么状态、预算多少、花了多少」，才能决定要不要调。
   * 形状刻意做窄：**只读、只给当前值**，不改任何东西。
   */
  public record AdCampaignExternalView(
      @Schema(description = "活动 id") UUID id,
      @Schema(description = "平台侧活动 id") String externalCampaignId,
      @Schema(description = "活动名") String campaignName,
      @Schema(description = "平台编码") String platformCode,
      @Schema(description = "店铺编码") String shopCode,
      @Schema(description = "🔴 原始平台类型标记") String platformAdType,
      @Schema(description = "🔴 归一后的内部类型") String internalAdType,
      @Schema(description = "当前状态") String status,
      @Schema(description = "币种") String currency,
      @Schema(description = "当前日预算", type = "string") BigDecimal dailyBudget,
      @Schema(description = "累计消耗（全部日期）", type = "string") BigDecimal spend,
      @Schema(description = "累计销售额（全部日期）", type = "string") BigDecimal sales,
      @Schema(description = "累计订单（全部日期）", type = "string") BigDecimal orders,
      @Schema(description = "🔴 ACOS = 消耗 / 销售额（分母为 0 时为 null）", type = "string") BigDecimal acos,
      @Schema(description = "🔴 ROAS = 销售额 / 消耗（分母为 0 时为 null）", type = "string") BigDecimal roas,
      @Schema(description = "🔴 数据截至时刻") OffsetDateTime dataAsOf,
      @Schema(description = "🔴 本地已改、尚未回写平台") boolean pendingPlatformWriteback,
      @Schema(description = "说明") String note) {}

  // ================================================================ 同步运行

  public record AdSyncRunRow(
      @Schema(description = "同步运行 id") UUID id,
      @Schema(description = "平台 id") UUID platformId,
      @Schema(description = "平台编码") String platformCode,
      @Schema(description = "店铺编码") String shopCode,
      @Schema(description = "店铺名称") String shopName,
      @Schema(description = "状态：SUCCESS / PARTIAL / FAILED") String status,
      @Schema(description = "🔴 数据截至时刻（失败同步不推进水位）") OffsetDateTime dataAsOf,
      @Schema(description = "开始时刻") OffsetDateTime startedAt,
      @Schema(description = "结束时刻") OffsetDateTime finishedAt,
      @Schema(description = "带回的活动条数") int campaignCount,
      @Schema(description = "带回的每日指标行数") int metricRowCount,
      @Schema(description = "🔴 失败原因（FAILED 时必有内容）") String message,
      @Schema(description = "来源批次标识") String sourceRef,
      @Schema(description = "登记时刻") OffsetDateTime createdAt) {}

  public record AdSyncRunPage(
      @Schema(description = "当前页数据") List<AdSyncRunRow> items,
      @Schema(description = "总条数") long total,
      @Schema(description = "页码，从 0 起") int page,
      @Schema(description = "每页条数") int size) {}

  // ================================================================ 批量操作

  /**
   * 批量暂停 / 启用 / 调预算的请求。
   *
   * <p>🔴 只接受**显式选中的一批活动 id**：「按筛选结果全选 + 变更预览 + 异步任务」是
   * <b>14.1.1.3 批量操作</b>的职责，尚未交付。本模块刻意不做，也不假装做了。
   */
  public record BulkOperationRequest(
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "🔴 显式选中的活动 id（不支持按筛选全选）")
          @NotEmpty
          @Size(max = 200)
          List<UUID> campaignIds,
      @Schema(
              requiredMode = Schema.RequiredMode.REQUIRED,
              description = "操作类型：PAUSE / ENABLE / SET_BUDGET / ADJUST_BUDGET")
          @NotBlank
          String operation,
      @Schema(description = "SET_BUDGET 用：目标日预算") BigDecimal budget,
      @Schema(description = "ADJUST_BUDGET 用：预算增减额（可正可负）") BigDecimal delta,
      @Schema(description = "操作理由（写进审计）") String reason) {}

  public record BulkOperationItem(
      @Schema(description = "活动 id") UUID campaignId,
      @Schema(description = "活动名") String campaignName,
      @Schema(description = "执行结果：APPLIED / REJECTED / SKIPPED") String result,
      @Schema(description = "变更前状态") String beforeStatus,
      @Schema(description = "变更后状态") String afterStatus,
      @Schema(description = "变更前日预算", type = "string") BigDecimal beforeBudget,
      @Schema(description = "变更后日预算", type = "string") BigDecimal afterBudget,
      @Schema(description = "🔴 逐条结果说明（被拒时给出原因）") String message) {}

  public record BulkOperationResult(
      @Schema(description = "提交的活动条数") int total,
      @Schema(description = "已生效条数") int applied,
      @Schema(description = "被拒条数") int rejected,
      @Schema(description = "跳过条数（本来就已经是目标状态）") int skipped,
      @Schema(description = "🔴 本次生效的护栏上限") AdBudgetGuardrails guardrails,
      @Schema(description = "🔴 逐条执行结果") List<BulkOperationItem> items,
      @Schema(description = "说明") String note) {}

  // ================================================================ 导入（真实写入口）

  /** 一次同步运行的登记信息。 */
  public record ImportSyncRun(
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "同步状态：SUCCESS / PARTIAL / FAILED")
          @NotBlank
          String status,
      @Schema(description = "🔴 数据截至时刻（FAILED 时必须为空：失败的同步不推进水位）")
          OffsetDateTime dataAsOf,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "同步开始时刻") @NotNull
          OffsetDateTime startedAt,
      @Schema(description = "同步结束时刻") OffsetDateTime finishedAt,
      @Schema(description = "🔴 失败原因（FAILED 时必填）") String message,
      @Schema(description = "来源批次标识") String sourceRef) {}

  /** 一条活动的每日表现。 */
  public record ImportDailyMetric(
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "自然日（平台当地日）") @NotNull
          LocalDate metricDate,
      @Schema(description = "曝光量") BigDecimal impressions,
      @Schema(description = "点击量") BigDecimal clicks,
      @Schema(description = "订单量") BigDecimal orders,
      @Schema(description = "消耗") BigDecimal spend,
      @Schema(description = "销售额") BigDecimal sales) {}

  /** 一条广告活动。 */
  public record ImportCampaign(
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "平台侧活动 id") @NotBlank
          String externalCampaignId,
      @Schema(description = "活动名") String campaignName,
      @Schema(
              requiredMode = Schema.RequiredMode.REQUIRED,
              description =
                  "🔴 广告类型档案 id（上游 14.1.2.2）；归一类型与原始标记由档案带下来，"
                      + "本模块不自行归一")
          @NotNull
          UUID adTypeProfileId,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "状态") @NotBlank String status,
      @Schema(description = "币种") String currency,
      @Schema(description = "日预算") BigDecimal dailyBudget,
      @Schema(description = "开始时间") OffsetDateTime startAt,
      @Schema(description = "结束时间") OffsetDateTime endAt,
      @Schema(description = "每日表现明细") @Valid List<ImportDailyMetric> metrics,
      @Schema(description = "备注") String note) {}

  /** 一次登记请求：同步运行 + 它带回的活动与每日指标。 */
  public record ImportRequest(
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "平台 id") @NotNull UUID platformId,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "店铺编码") @NotBlank String shopCode,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "同步运行信息") @NotNull
          ImportSyncRun syncRun,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "活动列表") @NotEmpty
          List<ImportCampaign> campaigns) {}

  public record ImportResult(
      @Schema(description = "同步运行 id") String syncRunId,
      @Schema(description = "同步状态") String syncStatus,
      @Schema(description = "🔴 数据截至时刻") OffsetDateTime dataAsOf,
      @Schema(description = "带回的活动条数") int campaignCount,
      @Schema(description = "带回的每日指标行数") int metricRowCount,
      @Schema(description = "新建的活动条数") int createdCount,
      @Schema(description = "更新的活动条数") int updatedCount,
      @Schema(description = "登记后的活动（列表形态）") List<AdCampaignRow> campaigns,
      @Schema(description = "说明") String note) {}

  // ================================================================ 选项

  public record AdCampaignOption(
      @Schema(description = "取值") String value,
      @Schema(description = "展示名") String label) {}

  public record AdCampaignOptions(
      @Schema(description = "平台下拉项") List<AdCampaignOption> platforms,
      @Schema(description = "店铺下拉项") List<AdCampaignOption> shops,
      @Schema(description = "状态下拉项") List<AdCampaignOption> statuses,
      @Schema(description = "内部广告类型下拉项（含显式的未映射）") List<AdCampaignOption> internalAdTypes,
      @Schema(description = "排序方式下拉项") List<AdCampaignOption> sortOptions,
      @Schema(description = "批量操作护栏上限") AdBudgetGuardrails guardrails,
      @Schema(description = "ACOS / ROAS 口径定义") String metricDefinitionNote) {}
}
