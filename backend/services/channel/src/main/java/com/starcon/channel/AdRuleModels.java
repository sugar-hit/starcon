package com.starcon.channel;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 14.2.2.2 自动规则 的传输模型。
 *
 * <p>蓝图：「自动化规则：按条件自动调整预算/竞价/启停（ACOS 超标降价、ROAS 达标加预算、消耗完毕暂停、花费无转化停投），
 * 支持规则优先级、单次调整上限与总上限、试运行模式。输入：规则定义；实时广告表现；上下限保护参数。输出：规则命中记录 +
 * 调整执行记录 + 效果回溯。关系：上游 = 14.3 报表；下游 = 平台广告 API。<b>自动规则必须有上限与试运行</b>——没有上限的自动调价一次
 * 异常就能烧掉大量预算。」
 *
 * <p>载荷一律用具名 {@code record}，不用 {@code Map<String,Object>}：Map 的值类型会被 springdoc 推成 object，codegen 出来是空
 * 对象 {@code {}}（9.6.1.3 已踩过）。
 *
 * <p>🔴 本模块最容易被做丢的一件事，在模型层就钉死：**上限与试运行不是可选项，是必填项**。
 *
 * <ol>
 *   <li>单次调整上限（金额 + 比例）、总上限（金额 + 次数 + 窗口）**没有「不填」这一档** ——
 *       请求模型里它们是 {@code @NotNull} 的数值，没有 null 的语义位置。</li>
 *   <li>试运行不是一个「顺手加的开关」：{@code dryRun} 在**规则**上，也���**运行**上，
 *       两处都关掉才是真执行；只要有一处是试运行，这次执行就**不产生实际调整**，
 *       而且执行记录里 {@code outcome=DRY_RUN} 与 {@code outcome=APPLIED} **分得开**。</li>
 *   <li>被拒绝的调整**照样有记录**（{@code outcome=REJECTED} + 拒绝原因），
 *       不是「静默截断」也不是「静默丢弃」—— 截断会让人以为规则按预期工作。</li>
 * </ol>
 *
 * <p>🔴 金额与数量一律 {@link BigDecimal}，**没有 double**：预算 / 花费 / 销售额 / 出价 / 上限金额是钱，
 * 曝光 / 点击 / 转化是计数，ACOS / ROAS 是比率。它们直接决定「改多少预算」，浮点不可接受。
 *
 * <p>🔴 比率的 null 语义在模型层也钉死：{@code acos} / {@code roas} 为 {@code null} 表示
 * <b>不可比</b>（分母为 0 或归因销售额为空），与 {@code 0}（可比但真的是零）含义相反。
 */
public final class AdRuleModels {
  private AdRuleModels() {}

  // ================================================================ 能力声明

  public record AdRuleCapabilityDimension(
      @Schema(description = "维度键") String dimension,
      @Schema(description = "维度说明") String description,
      @Schema(description = "本模块是否已交付") boolean available,
      @Schema(description = "数据来源（表 / 上游模块）") String source,
      @Schema(description = "未交付段的责任模块") String ownerModule,
      @Schema(description = "不交付的影响") String impact) {}

  public record AdRuleCapabilities(
      @Schema(description = "逐维度能力声明；未交付段 available=false") List<AdRuleCapabilityDimension> dimensions,
      @Schema(description = "动作类型") List<String> actionTypes,
      @Schema(description = "调整幅度口径：PERCENT 相对当前值 / ABSOLUTE 绝对值") List<String> adjustmentModes,
      @Schema(description = "可参与条件的指标口径") List<String> metrics,
      @Schema(description = "比较符") List<String> operators,
      @Schema(description = "作用对象层级") List<String> targetTypes,
      @Schema(description = "运行口径：DRY_RUN 试运行 / APPLY 真实执行") List<String> runModes,
      @Schema(description = "执行结论：DRY_RUN / APPLIED / REJECTED") List<String> executionOutcomes,
      @Schema(description = "回溯结论：IMPROVED / WORSENED / UNCHANGED / INSUFFICIENT_DATA")
          List<String> effectVerdicts,
      @Schema(description = "🔴 上限与闸门的口径说明") String guardNote,
      @Schema(description = "🔴 试运行的口径说明") String dryRunNote,
      @Schema(description = "🔴 优先级的口径说明") String priorityNote,
      @Schema(description = "🔴 下游平台下发的边界说明") String dispatchNote,
      @Schema(description = "上游与数据截至时间的说明") String upstreamNote) {}

  // ================================================================ 规则

  public record AdRuleRow(
      @Schema(description = "规则 id") UUID id,
      @Schema(description = "规则编码（租户内唯一）") String ruleCode,
      @Schema(description = "规则名称") String ruleName,
      @Schema(description = "平台 id") UUID platformId,
      @Schema(description = "平台编码") String platformCode,
      @Schema(description = "店铺编码") String shopCode,
      @Schema(description = "作用对象层级") String targetType,
      @Schema(description = "🔴 外部活动 id（外部引用，不假设 14.1.1.1 的表存在）")
          String externalCampaignId,
      @Schema(description = "🔴 外部关键词 id") String externalKeywordId,
      @Schema(description = "动作类型") String actionType,
      @Schema(description = "调整幅度口径") String adjustmentMode,
      @Schema(description = "调整幅度") BigDecimal adjustmentValue,
      @Schema(description = "🔴 单次调整金额上限（必填，> 0）") BigDecimal singleAdjustCapAmount,
      @Schema(description = "🔴 单次调整比例上限（0 = 不启用）") BigDecimal singleAdjustCapPercent,
      @Schema(description = "🔴 窗口内累计金额上限（必填，> 0）") BigDecimal totalCapAmount,
      @Schema(description = "🔴 窗口内累计次数上限（必填，> 0）") int totalCapCount,
      @Schema(description = "🔴 累计窗口长度（小时，必填，> 0）") int totalCapWindowHours,
      @Schema(description = "预算下限保护") BigDecimal minBudget,
      @Schema(description = "预算上限保护") BigDecimal maxBudget,
      @Schema(description = "出价下限保护") BigDecimal minBid,
      @Schema(description = "出价上限保护") BigDecimal maxBid,
      @Schema(description = "🔴 优先级：数字小 = 优先高") int priority,
      @Schema(description = "🔴 试运行开关（默认 true）") boolean dryRun,
      @Schema(description = "评估窗口长度（小时）") int evaluationWindowHours,
      @Schema(description = "状态") String status,
      @Schema(description = "条件条数") int conditionCount,
      @Schema(description = "🔴 该目标上最新指标的数据截至时间；为空 = **没有数据**") OffsetDateTime latestMetricsAsOf,
      @Schema(description = "🔴 指标是否已过期（最新指标早于评估窗口）；区分「没有数据」与「没同步到」")
          boolean metricsStale,
      @Schema(description = "备注") String note,
      @Schema(description = "乐观锁版本") long version,
      @Schema(description = "创建时刻") OffsetDateTime createdAt,
      @Schema(description = "更新时刻") OffsetDateTime updatedAt) {}

  public record AdRulePage(
      @Schema(description = "当前页数据") List<AdRuleRow> items,
      @Schema(description = "符合筛选条件的总条数（不受分页影响）") long total,
      @Schema(description = "页码，从 0 起") int page,
      @Schema(description = "每页条数") int size,
      @Schema(description = "其中试运行中的条数") long dryRunCount,
      @Schema(description = "其中启用的条数") long activeCount) {}

  public record AdRuleConditionRow(
      @Schema(description = "条件 id") UUID id,
      @Schema(description = "所属规则 id") UUID ruleId,
      @Schema(description = "指标口径") String metric,
      @Schema(description = "比较符") String operator,
      @Schema(description = "🔴 比率 / 计数阈值（ACOS / ROAS / CONVERSIONS / CLICKS / IMPRESSIONS）")
          BigDecimal thresholdValue,
      @Schema(description = "🔴 金额阈值（SPEND / REVENUE）") BigDecimal thresholdAmount,
      @Schema(description = "备注") String note,
      @Schema(description = "求值顺序") int position,
      @Schema(description = "创建时刻") OffsetDateTime createdAt) {}

  public record AdRuleDetail(
      @Schema(description = "规则") AdRuleRow rule,
      @Schema(description = "🔴 规则条件（AND 语义：全部成立才算命中）") List<AdRuleConditionRow> conditions,
      @Schema(description = "🔴 该规则最近一次评估的结论（命中数 / 执行数 / 被拒数）")
          AdRuleLastRun lastRun,
      @Schema(description = "说明") String note) {}

  public record AdRuleLastRun(
      @Schema(description = "运行 id") UUID runId,
      @Schema(description = "运行口径") String runMode,
      @Schema(description = "状态") String status,
      @Schema(description = "命中条数") int hitCount,
      @Schema(description = "被选中执行的条数") int selectedCount,
      @Schema(description = "真实执行条数") int appliedCount,
      @Schema(description = "试运行条数") int dryRunCount,
      @Schema(description = "被闸门拒绝条数") int rejectedCount,
      @Schema(description = "开始时刻") OffsetDateTime startedAt) {}

  // ================================================================ 指标

  public record AdRuleMetricRow(
      @Schema(description = "指标 id") UUID id,
      @Schema(description = "平台 id") UUID platformId,
      @Schema(description = "平台编码") String platformCode,
      @Schema(description = "店铺编码") String shopCode,
      @Schema(description = "作用对象层级") String metricScope,
      @Schema(description = "🔴 外部活动 id") String externalCampaignId,
      @Schema(description = "🔴 外部关键词 id") String externalKeywordId,
      @Schema(description = "币种") String currency,
      @Schema(description = "当前投放状态") String deliveryStatus,
      @Schema(description = "当前预算") BigDecimal currentBudget,
      @Schema(description = "当前出价") BigDecimal currentBid,
      @Schema(description = "展示量（计数）") BigDecimal impressions,
      @Schema(description = "点击量（计数）") BigDecimal clicks,
      @Schema(description = "转化量（计数）") BigDecimal conversions,
      @Schema(description = "花费") BigDecimal spend,
      @Schema(description = "归因销售额") BigDecimal attributedRevenue,
      @Schema(description = "🔴 ACOS = 花费 / 归因销售额；null = 不可比（分母为 0 或销售额为空），与 0 含义相反")
          BigDecimal acos,
      @Schema(description = "🔴 ROAS = 归因销售额 / 花费；null = 不可比，与 0 含义相反") BigDecimal roas,
      @Schema(description = "🔴 数据截至时间（必填）：区分「没有数据」与「没同步到」") OffsetDateTime metricsAsOf,
      @Schema(description = "统计窗口开始") OffsetDateTime windowStart,
      @Schema(description = "统计窗口结束") OffsetDateTime windowEnd,
      @Schema(description = "来源凭据") String sourceRef,
      @Schema(description = "备注") String note,
      @Schema(description = "创建时刻") OffsetDateTime createdAt,
      @Schema(description = "更新时刻") OffsetDateTime updatedAt) {}

  public record AdRuleMetricPage(
      @Schema(description = "当前页数据") List<AdRuleMetricRow> items,
      @Schema(description = "符合筛选条件的总条数（不受分页影响）") long total,
      @Schema(description = "页码，从 0 起") int page,
      @Schema(description = "每页条数") int size,
      @Schema(description = "🔴 全库最新的数据截至时间") OffsetDateTime latestMetricsAsOf) {}

  // ================================================================ 运行

  public record AdRuleRunRow(
      @Schema(description = "运行 id") UUID id,
      @Schema(description = "🔴 运行口径：DRY_RUN / APPLY") String runMode,
      @Schema(description = "触发来源") String triggerSource,
      @Schema(description = "状态") String status,
      @Schema(description = "评估的规则数") int evaluatedRules,
      @Schema(description = "命中条数") int hitCount,
      @Schema(description = "被选中执行的条数") int selectedCount,
      @Schema(description = "执行记录条数") int executionCount,
      @Schema(description = "真实执行条数") int appliedCount,
      @Schema(description = "试运行条数") int dryRunCount,
      @Schema(description = "被闸门拒绝条数") int rejectedCount,
      @Schema(description = "开始时刻") OffsetDateTime startedAt,
      @Schema(description = "结束时刻") OffsetDateTime finishedAt,
      @Schema(description = "备注") String note) {}

  public record AdRuleRunPage(
      @Schema(description = "当前页数据") List<AdRuleRunRow> items,
      @Schema(description = "符合筛选条件的总条数（不受分页影响）") long total,
      @Schema(description = "页码，从 0 起") int page,
      @Schema(description = "每页条数") int size) {}

  /** 一次运行的详情：运行头 + 本次产生的命中记录 + 调整执行记录。 */
  public record AdRuleRunDetail(
      @Schema(description = "运行") AdRuleRunRow run,
      @Schema(description = "🔴 本次的命中记录（命中与未命中都在）") List<AdRuleHitRow> hits,
      @Schema(description = "🔴 本次的调整执行记录（试运行 / 真实执行 / 被拒绝都在）")
          List<AdRuleExecutionRow> executions,
      @Schema(description = "说明") String note) {}

  // ================================================================ 命中记录

  public record AdRuleHitRow(
      @Schema(description = "命中记录 id") UUID id,
      @Schema(description = "运行 id") UUID runId,
      @Schema(description = "规则 id") UUID ruleId,
      @Schema(description = "规则名称") String ruleName,
      @Schema(description = "指标 id") UUID metricId,
      @Schema(description = "平台编码") String platformCode,
      @Schema(description = "店铺编码") String shopCode,
      @Schema(description = "外部活动 id") String externalCampaignId,
      @Schema(description = "外部关键词 id") String externalKeywordId,
      @Schema(description = "🔴 是否命中") boolean matched,
      @Schema(description = "🔴 是否被选中执行（同目标多条命中时按优先级取一条）") boolean selected,
      @Schema(description = "🔴 选中 / 落选的原因说明") String selectionNote,
      @Schema(description = "命中 / 未命中的原因说明（未命中时写清差在哪）") String hitReason,
      @Schema(description = "当时的优先级") int priority,
      @Schema(description = "当时的花费快照") BigDecimal spendSnapshot,
      @Schema(description = "当时的归因销售额快照") BigDecimal revenueSnapshot,
      @Schema(description = "当时的转化数快照") BigDecimal conversionsSnapshot,
      @Schema(description = "当时的点击数快照") BigDecimal clicksSnapshot,
      @Schema(description = "当时的展示数快照") BigDecimal impressionsSnapshot,
      @Schema(description = "🔴 当时的 ACOS 快照；null = 不可比") BigDecimal acosSnapshot,
      @Schema(description = "🔴 当时的 ROAS 快照；null = 不可比") BigDecimal roasSnapshot,
      @Schema(description = "🔴 指标的数据截至时间") OffsetDateTime metricsAsOf,
      @Schema(description = "评估时刻") OffsetDateTime evaluatedAt) {}

  public record AdRuleHitPage(
      @Schema(description = "当前页数据") List<AdRuleHitRow> items,
      @Schema(description = "符合筛选条件的总条数（不受分页影响）") long total,
      @Schema(description = "页码，从 0 起") int page,
      @Schema(description = "每页条数") int size,
      @Schema(description = "其中命中的条数") long matchedCount,
      @Schema(description = "其中被选中执行的条数") long selectedCount) {}

  // ================================================================ 执行记录

  public record AdRuleExecutionRow(
      @Schema(description = "执行记录 id") UUID id,
      @Schema(description = "运行 id") UUID runId,
      @Schema(description = "规则 id") UUID ruleId,
      @Schema(description = "规则名称") String ruleName,
      @Schema(description = "命中记录 id") UUID hitId,
      @Schema(description = "指标 id") UUID metricId,
      @Schema(description = "动作类型") String actionType,
      @Schema(description = "调整幅度口径") String adjustmentMode,
      @Schema(description = "请求的调整幅度") BigDecimal requestedValue,
      @Schema(description = "🔴 执行结论：DRY_RUN 试运行 / APPLIED 真实执行 / REJECTED 被闸门拒绝")
          String outcome,
      @Schema(description = "🔴 拒绝原因（为空表示未被拒绝）") String rejectReason,
      @Schema(description = "🔴 拒绝详情") String rejectDetail,
      @Schema(description = "调整前的值") BigDecimal beforeValue,
      @Schema(description = "🔴 调整后的值（试运行里是「本来会变成多少」）") BigDecimal afterValue,
      @Schema(description = "🔴 实际调整幅度") BigDecimal appliedDelta,
      @Schema(description = "🔴 启停动作的调整前状态（ENABLED / PAUSED / ENDED）") String beforeStatus,
      @Schema(description = "🔴 启停动作的调整后状态") String afterStatus,
      @Schema(description = "🔴 上限闸门结论") String capCheck,
      @Schema(description = "🔴 上下限闸门结论") String boundCheck,
      @Schema(description = "单次调整金额上限") BigDecimal singleCapAmount,
      @Schema(description = "单次调整比例上限") BigDecimal singleCapPercent,
      @Schema(description = "窗口内累计金额上限") BigDecimal totalCapAmount,
      @Schema(description = "窗口内累计次数上限") int totalCapCount,
      @Schema(description = "累计窗口长度（小时）") int totalCapWindowHours,
      @Schema(description = "🔴 窗口内已用金额（判定时的实际用量，可复算）") BigDecimal windowUsedAmount,
      @Schema(description = "🔴 窗口内已用次数") int windowUsedCount,
      @Schema(description = "🔴 优先级选中的说明") String prioritySelectionNote,
      @Schema(description = "执行时刻") OffsetDateTime executedAt) {}

  public record AdRuleExecutionPage(
      @Schema(description = "当前页数据") List<AdRuleExecutionRow> items,
      @Schema(description = "符合筛选条件的总条数（不受分页影响）") long total,
      @Schema(description = "页码，从 0 起") int page,
      @Schema(description = "每页条数") int size,
      @Schema(description = "其中真实执行的条数") long appliedCount,
      @Schema(description = "其中试运行的条数") long dryRunCount,
      @Schema(description = "其中被闸门拒绝的条数") long rejectedCount) {}

  // ================================================================ 待下发动作

  public record AdRuleDispatchRow(
      @Schema(description = "待下发动作 id") UUID id,
      @Schema(description = "执行记录 id") UUID executionId,
      @Schema(description = "规则 id") UUID ruleId,
      @Schema(description = "规则名称") String ruleName,
      @Schema(description = "平台 id") UUID platformId,
      @Schema(description = "平台编码") String platformCode,
      @Schema(description = "店铺编码") String shopCode,
      @Schema(description = "外部活动 id") String externalCampaignId,
      @Schema(description = "外部关键词 id") String externalKeywordId,
      @Schema(description = "动作类型") String actionType,
      @Schema(description = "🔴 下发载荷（平台适配器要什么就放什么）") JsonNode actionPayload,
      @Schema(description = "🔴 下发状态：PENDING 待下发 / DISPATCHED 已下发 / FAILED 失败 / CANCELLED 已撤销")
          String dispatchStatus,
      @Schema(description = "下发时刻") OffsetDateTime dispatchedAt,
      @Schema(description = "下发凭据") String dispatchRef,
      @Schema(description = "备注") String note,
      @Schema(description = "创建时刻") OffsetDateTime createdAt,
      @Schema(description = "更新时刻") OffsetDateTime updatedAt) {}

  public record AdRuleDispatchPage(
      @Schema(description = "当前页数据") List<AdRuleDispatchRow> items,
      @Schema(description = "符合筛选条件的总条数（不受分页影响）") long total,
      @Schema(description = "页码，从 0 起") int page,
      @Schema(description = "每页条数") int size,
      @Schema(description = "其中待下发的条数") long pendingCount) {}

  // ================================================================ 效果回溯

  public record AdRuleEffectRow(
      @Schema(description = "回溯 id") UUID id,
      @Schema(description = "执行记录 id") UUID executionId,
      @Schema(description = "规则 id") UUID ruleId,
      @Schema(description = "规则名称") String ruleName,
      @Schema(description = "指标 id") UUID metricId,
      @Schema(description = "执行前的 ACOS；null = 不可比") BigDecimal beforeAcos,
      @Schema(description = "执行后的 ACOS；null = 不可比") BigDecimal afterAcos,
      @Schema(description = "ACOS 变化（负数 = 变好）") BigDecimal acosDelta,
      @Schema(description = "执行前的 ROAS；null = 不可比") BigDecimal beforeRoas,
      @Schema(description = "执行后的 ROAS；null = 不可比") BigDecimal afterRoas,
      @Schema(description = "ROAS 变化（正数 = 变好）") BigDecimal roasDelta,
      @Schema(description = "执行前的花费") BigDecimal beforeSpend,
      @Schema(description = "执行后的花费") BigDecimal afterSpend,
      @Schema(description = "执行前的归因销售额") BigDecimal beforeRevenue,
      @Schema(description = "执行后的归因销售额") BigDecimal afterRevenue,
      @Schema(description = "销售额变化") BigDecimal revenueDelta,
      @Schema(description = "🔴 回溯结论：IMPROVED / WORSENED / UNCHANGED / INSUFFICIENT_DATA")
          String verdict,
      @Schema(description = "结论说明") String verdictNote,
      @Schema(description = "观测时刻") OffsetDateTime observedAt) {}

  public record AdRuleEffectPage(
      @Schema(description = "当前页数据") List<AdRuleEffectRow> items,
      @Schema(description = "符合筛选条件的总条数（不受分页影响）") long total,
      @Schema(description = "页码，从 0 起") int page,
      @Schema(description = "每页条数") int size,
      @Schema(description = "其中变好的条数") long improvedCount,
      @Schema(description = "其中变差的条数") long worsenedCount,
      @Schema(description = "其中数据不足的条数") long insufficientCount) {}

  // ================================================================ 写入请求

  public record SaveRuleConditionRequest(
      @Schema(description = "指标口径") @NotBlank String metric,
      @Schema(description = "比较符") @NotBlank String operator,
      @Schema(description = "比率 / 计数阈值（ACOS / ROAS / CONVERSIONS / CLICKS / IMPRESSIONS 用）")
          @DecimalMin("0")
          BigDecimal thresholdValue,
      @Schema(description = "金额阈值（SPEND / REVENUE 用）") @DecimalMin("0") BigDecimal thresholdAmount,
      @Schema(description = "备注") @Size(max = 1000) String note,
      @Schema(description = "求值顺序") @Min(0) @Max(9999) int position) {}

  public record SaveRuleRequest(
      @Schema(description = "规则 id；留空表示新建") UUID id,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "规则编码（租户内唯一）") @NotBlank
          @Size(max = 64)
          String ruleCode,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "规则名称") @NotBlank @Size(max = 128)
          String ruleName,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "平台 id") @NotNull UUID platformId,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "店铺编码") @NotBlank @Size(max = 64)
          String shopCode,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "作用对象层级") @NotBlank String targetType,
      @Schema(description = "🔴 外部活动 id（外部引用）") @Size(max = 128) String externalCampaignId,
      @Schema(description = "🔴 外部关键词 id（外部引用）") @Size(max = 128) String externalKeywordId,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "动作类型") @NotBlank String actionType,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "调整幅度口径") @NotBlank
          String adjustmentMode,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "调整幅度") @NotNull @DecimalMin("0")
          BigDecimal adjustmentValue,
      @Schema(
              requiredMode = Schema.RequiredMode.REQUIRED,
              description = "🔴 单次调整金额上限（必填，> 0）—— 没有上限的规则建不出来")
          @NotNull
          @DecimalMin(value = "0.0001")
          BigDecimal singleAdjustCapAmount,
      @Schema(description = "🔴 单次调整比例上限（0 = 不启用；与金额上限取更严的）") @DecimalMin("0")
          BigDecimal singleAdjustCapPercent,
      @Schema(
              requiredMode = Schema.RequiredMode.REQUIRED,
              description = "🔴 窗口内累计金额上限（必填，> 0）")
          @NotNull
          @DecimalMin(value = "0.0001")
          BigDecimal totalCapAmount,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "🔴 窗口内累计次数上限（必填，> 0）") @NotNull
          @Min(1)
          @Max(10000)
          int totalCapCount,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "🔴 累计窗口长度（小时，必填，> 0）") @NotNull
          @Min(1)
          @Max(8760)
          int totalCapWindowHours,
      @Schema(description = "预算下限保护") @DecimalMin("0") BigDecimal minBudget,
      @Schema(description = "预算上限保护") @DecimalMin("0") BigDecimal maxBudget,
      @Schema(description = "出价下限保护") @DecimalMin("0") BigDecimal minBid,
      @Schema(description = "出价上限保护") @DecimalMin("0") BigDecimal maxBid,
      @Schema(description = "🔴 优先级：数字小 = 优先高") @Min(0) @Max(9999) int priority,
      @Schema(description = "🔴 试运行开关（默认 true；新建规则一律先试运行）") boolean dryRun,
      @Schema(description = "评估窗口长度（小时）") @Min(1) @Max(8760) int evaluationWindowHours,
      @Schema(description = "状态：ACTIVE / PAUSED / ARCHIVED") @NotBlank String status,
      @Schema(description = "备注") @Size(max = 1000) String note,
      @Schema(
              requiredMode = Schema.RequiredMode.REQUIRED,
              description = "🔴 规则条件（AND 语义：全部成立才算命中；启用的规则至少一条）")
          @NotEmpty
          List<@Valid SaveRuleConditionRequest> conditions) {}

  public record SaveMetricRequest(
      @Schema(description = "指标 id；留空表示新建") UUID id,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "平台 id") @NotNull UUID platformId,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "店铺编码") @NotBlank @Size(max = 64)
          String shopCode,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "作用对象层级") @NotBlank String metricScope,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "🔴 外部活动 id") @NotBlank @Size(max = 128)
          String externalCampaignId,
      @Schema(description = "🔴 外部关键词 id（关键词级指标必填）") @Size(max = 128) String externalKeywordId,
      @Schema(description = "币种") @Size(max = 3) String currency,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "当前投放状态") @NotBlank
          String deliveryStatus,
      @Schema(description = "当前预算") @DecimalMin("0") BigDecimal currentBudget,
      @Schema(description = "当前出价") @DecimalMin("0") BigDecimal currentBid,
      @Schema(description = "展示量（计数）") @DecimalMin("0") BigDecimal impressions,
      @Schema(description = "点击量（计数）") @DecimalMin("0") BigDecimal clicks,
      @Schema(description = "转化量（计数）") @DecimalMin("0") BigDecimal conversions,
      @Schema(description = "花费") @DecimalMin("0") BigDecimal spend,
      @Schema(description = "归因销售额") @DecimalMin("0") BigDecimal attributedRevenue,
      @Schema(
              requiredMode = Schema.RequiredMode.REQUIRED,
              description = "🔴 数据截至时间（必填）—— 用来区分「没有数据」与「没同步到」")
          @NotNull
          OffsetDateTime metricsAsOf,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "统计窗口开始（ISO-8601 带时区）") @NotNull
          OffsetDateTime windowStart,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "统计窗口结束（ISO-8601 带时区）") @NotNull
          OffsetDateTime windowEnd,
      @Schema(description = "来源凭据") @Size(max = 1000) String sourceRef,
      @Schema(description = "备注") @Size(max = 1000) String note) {}

  public record RunRequest(
      @Schema(description = "🔴 运行口径：DRY_RUN 试运行 / APPLY 真实执行；规则的 dryRun=true 时**一律试运行**")
          @NotBlank
          String runMode,
      @Schema(description = "只跑这一条规则；留空 = 跑全部启用的规则") UUID ruleId,
      @Schema(description = "备注") @Size(max = 1000) String note) {}

  public record EffectEvaluateRequest(
      @Schema(description = "只回溯这一条规则；留空 = 回溯全部待回溯的执行") UUID ruleId,
      @Schema(description = "备注") @Size(max = 1000) String note) {}

  public record CancelDispatchRequest(
      @Schema(description = "撤销原因") @NotBlank @Size(max = 500) String reason) {}

  public record AdRuleActionResult(
      @Schema(description = "结论") String result,
      @Schema(description = "说明") String note) {}
}
