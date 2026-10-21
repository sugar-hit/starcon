package com.starcon.channel;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
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
 * 14.2.2.1 预算分配 的传输模型。
 *
 * <p>蓝图：「跨店铺/跨活动的预算分配与再平衡：按销售目标、利润、历史 ROAS 分配预算，识别预算受限的活动与闲置预算。
 * 输入：预算总额；分配规则；各活动历史表现。输出：分配方案 + 再平衡建议 + 执行任务。
 * 关系：上游 = 14.1.1.1、13.4 利润；下游 = 14.2.2.2。<b>预算应向利润高的活动倾斜</b>，按销售额平均分配是常见浪费。」
 *
 * <p>🔴 这个模型层钉死本模块最容易做丢的四件事：
 *
 * <ol>
 *   <li><b>分配方案 + 再平衡建议 + 执行任务是三样不同的东西</b>，所以是三组模型而不是一张大表：
 *       方案改的是「预算总额怎么摊」（{@link AdBudgetPlanRow}），再平衡改的是「总额不变时谁多谁少」
 *       （{@link AdBudgetRebalanceRow}），执行任务改的是「落到活动上的预算」
 *       （{@link AdBudgetTaskRow}）。混在一起就没人说得清「这一笔钱到底动了没有」。
 *   <li><b>权重来源是可插拔的</b>：{@link AdBudgetWeightRow} 的 {@code weightSource} 只有三种取值，
 *       而 {@code PROFIT} 这一档**当前不可用**（13.4 利润未交付）—— 规则里**占着位**，权重为 0。
 *       13.4 到位后放开闸门即可接上，算法一行都不用改。
 *   <li><b>闲置预算与预算受限是两种相反的事实</b>：{@code idleBudget} 与 {@code budgetConstrained}
 *       两个布尔同时存在，{@code utilization} 为 {@code null} 表示**不可比**（窗口内没有可比数据），
 *       与 {@code 0}（可比但真的是零）含义相反。
 *   <li><b>「没参与分配」也要看得见</b>：{@link AdBudgetSkippedRow} 逐条列出被排除的活动与原因
 *       （币种不一致 / 没有评分依据），而不是静默丢掉 —— 静默丢掉会让人以为「系统认为它不值得投」。
 * </ol>
 *
 * <p>🔴 金额与数量一律 {@link BigDecimal}，**没有 double**：预算 / 分配额 / 闲置预算 / 建议调整额 /
 * 销售目标都是钱；权重与比率是量。它们直接决定「改多少预算」，浮点不可接受。
 */
public final class AdBudgetAllocationModels {
  private AdBudgetAllocationModels() {}

  // ================================================================ 能力声明

  public record AdBudgetCapabilityDimension(
      @Schema(description = "维度键") String dimension,
      @Schema(description = "维度说明") String description,
      @Schema(description = "本模块是否已交付") boolean available,
      @Schema(description = "数据来源（表 / 上游模块）") String source,
      @Schema(description = "未交付段的责任模块") String ownerModule,
      @Schema(description = "不交付的影响") String impact) {}

  /**
   * 执行任务的护栏上限。
   *
   * <p>🔴 蓝图 14 章关键约束：「AI/规则自动调整必须有上限保护」—— 一次分配执行烧掉的
   * 是真金白银。上限**随响应一起返回**，让运营在点下去之前就知道天花板在哪。
   */
  public record AdBudgetAllocationGuardrails(
      @Schema(description = "单个活动一次允许调整的预算上限（绝对值）", type = "string")
          BigDecimal maxSingleBudgetDelta,
      @Schema(description = "一次执行任务允许的总预算调整上限（绝对值）", type = "string")
          BigDecimal maxTotalBudgetDelta,
      @Schema(description = "单个活动的预算上限（调预算后的结果值不得超过）", type = "string")
          BigDecimal maxCampaignBudget,
      @Schema(description = "一次执行任务最多允许的活动条数") int maxCampaignsPerTask,
      @Schema(description = "护栏说明") String note) {}

  public record AdBudgetCapabilities(
      @Schema(description = "逐维度能力声明；未交付段 available=false") List<AdBudgetCapabilityDimension> dimensions,
      @Schema(description = "权重来源取值（可插拔）") List<String> weightSources,
      @Schema(description = "规则状态取值") List<String> ruleStatuses,
      @Schema(description = "方案状态取值") List<String> planStatuses,
      @Schema(description = "任务状态取值") List<String> taskStatuses,
      @Schema(description = "调整建议取值") List<String> recommendations,
      @Schema(description = "未纳入分配的原因取值") List<String> skipReasons,
      @Schema(description = "🔴 执行护栏上限") AdBudgetAllocationGuardrails guardrails,
      @Schema(description = "🔴 分配口径说明（权重 / 归一化 / 余数处理）") String allocationNote,
      @Schema(description = "🔴 利用率与闲置/受限的判定口径") String utilizationNote,
      @Schema(description = "🔴 利润维度当前不可用的说明") String profitNote,
      @Schema(description = "🔴 币种口径说明") String currencyNote,
      @Schema(description = "上游与数据截至时间的说明") String upstreamNote,
      @Schema(description = "下游与边界说明") String boundaryNote) {}

  // ================================================================ 规则

  /** 一个权重位。{@code weightSource=PROFIT} 当前恒为 0（13.4 未交付）。 */
  public record AdBudgetWeightRow(
      @Schema(description = "权重来源：SALES_TARGET 销售目标 / ROAS 历史 ROAS / PROFIT 利润")
          String weightSource,
      @Schema(description = "权重（>= 0；全部权重为 0 的规则建不出来）") BigDecimal weight,
      @Schema(description = "备注") String note,
      @Schema(description = "求值顺序") int position) {}

  /** 规则下逐活动的销售目标（人工登记）。 */
  public record AdBudgetRuleTargetRow(
      @Schema(description = "目标 id") UUID id,
      @Schema(description = "活动 id") UUID campaignId,
      @Schema(description = "活动名") String campaignName,
      @Schema(description = "平台编码") String platformCode,
      @Schema(description = "店铺编码") String shopCode,
      @Schema(description = "销售目标（与规则同币种）", type = "string") BigDecimal salesTarget,
      @Schema(description = "逐活动预算下限（覆盖规则级）", type = "string") BigDecimal minBudget,
      @Schema(description = "逐活动预算上限（覆盖规则级）", type = "string") BigDecimal maxBudget,
      @Schema(description = "备注") String note,
      @Schema(description = "创建时刻") OffsetDateTime createdAt) {}

  public record AdBudgetRuleRow(
      @Schema(description = "规则 id") UUID id,
      @Schema(description = "规则编码（租户内唯一）") String ruleCode,
      @Schema(description = "规则名称") String ruleName,
      @Schema(description = "🔴 币种（一个规则一个币种，不折算）") String currency,
      @Schema(description = "限定平台 id（为空 = 不限）") UUID scopePlatformId,
      @Schema(description = "限定平台编码") String scopePlatformCode,
      @Schema(description = "限定店铺编码（为空 = 不限）") String scopeShopCode,
      @Schema(description = "规则级逐活动预算下限", type = "string") BigDecimal minCampaignBudget,
      @Schema(description = "规则级逐活动预算上限", type = "string") BigDecimal maxCampaignBudget,
      @Schema(description = "🔴 预算受限的利用率阈值（>= 判受限）") BigDecimal constrainedUtilization,
      @Schema(description = "🔴 闲置预算的利用率阈值（< 判闲置）") BigDecimal idleUtilization,
      @Schema(description = "状态") String status,
      @Schema(description = "🔴 权重位（可插拔；PROFIT 当前恒为 0）") List<AdBudgetWeightRow> weights,
      @Schema(description = "销售目标条数") int targetCount,
      @Schema(description = "备注") String note,
      @Schema(description = "乐观锁版本") long version,
      @Schema(description = "创建时刻") OffsetDateTime createdAt,
      @Schema(description = "更新时刻") OffsetDateTime updatedAt) {}

  public record AdBudgetRulePage(
      @Schema(description = "当前页数据") List<AdBudgetRuleRow> items,
      @Schema(description = "符合筛选条件的总条数") long total,
      @Schema(description = "页码，从 0 起") int page,
      @Schema(description = "每页条数") int size) {}

  public record AdBudgetRuleDetail(
      @Schema(description = "规则") AdBudgetRuleRow rule,
      @Schema(description = "🔴 销售目标清单") List<AdBudgetRuleTargetRow> targets,
      @Schema(description = "说明") String note) {}

  // ================================================================ 预算池

  public record AdBudgetPoolRow(
      @Schema(description = "预算池 id") UUID id,
      @Schema(description = "预算池编码") String poolCode,
      @Schema(description = "预算池名称") String poolName,
      @Schema(description = "币种") String currency,
      @Schema(description = "🔴 预算总额", type = "string") BigDecimal totalAmount,
      @Schema(description = "周期开始") LocalDate periodStart,
      @Schema(description = "周期结束") LocalDate periodEnd,
      @Schema(description = "分配规则 id") UUID ruleId,
      @Schema(description = "分配规则名称") String ruleName,
      @Schema(description = "状态") String status,
      @Schema(description = "备注") String note,
      @Schema(description = "乐观锁版本") long version,
      @Schema(description = "创建时刻") OffsetDateTime createdAt,
      @Schema(description = "更新时刻") OffsetDateTime updatedAt) {}

  public record AdBudgetPoolPage(
      @Schema(description = "当前页数据") List<AdBudgetPoolRow> items,
      @Schema(description = "符合筛选条件的总条数") long total,
      @Schema(description = "页码，从 0 起") int page,
      @Schema(description = "每页条数") int size) {}

  // ================================================================ 分配方案

  public record AdBudgetPlanRow(
      @Schema(description = "方案 id") UUID id,
      @Schema(description = "预算池 id") UUID poolId,
      @Schema(description = "预算池名称") String poolName,
      @Schema(description = "分配规则 id") UUID ruleId,
      @Schema(description = "分配规则名称") String ruleName,
      @Schema(description = "状态") String status,
      @Schema(description = "币种") String currency,
      @Schema(description = "🔴 预算总额", type = "string") BigDecimal totalAmount,
      @Schema(description = "🔴 已分配额（+ 未分配额 = 总额）", type = "string") BigDecimal allocatedAmount,
      @Schema(description = "🔴 未分配额（受单活动上限挡下的部分）", type = "string")
          BigDecimal unallocatedAmount,
      @Schema(description = "🔴 释放额：收回去的钱", type = "string") BigDecimal releasedAmount,
      @Schema(description = "🔴 闲置预算：有预算却花不出去的钱", type = "string") BigDecimal idleBudget,
      @Schema(description = "🔴 预算受限缺口：想花却没预算的钱", type = "string")
          BigDecimal constrainedShortfall,
      @Schema(description = "纳入分配的活动条数") int campaignCount,
      @Schema(description = "未纳入分配的活动条数") int skippedCount,
      @Schema(description = "🔴 统计窗口开始") LocalDate windowStart,
      @Schema(description = "🔴 统计窗口结束") LocalDate windowEnd,
      @Schema(description = "计算时刻") OffsetDateTime computedAt,
      @Schema(description = "备注") String note) {}

  public record AdBudgetPlanPage(
      @Schema(description = "当前页数据") List<AdBudgetPlanRow> items,
      @Schema(description = "符合筛选条件的总条数") long total,
      @Schema(description = "页码，从 0 起") int page,
      @Schema(description = "每页条数") int size) {}

  /** 方案明细的一行：算出来那一刻的**冻结快照**。 */
  public record AdBudgetPlanLineRow(
      @Schema(description = "明细 id") UUID id,
      @Schema(description = "活动 id") UUID campaignId,
      @Schema(description = "活动名") String campaignName,
      @Schema(description = "平台编码") String platformCode,
      @Schema(description = "店铺编码") String shopCode,
      @Schema(description = "活动状态") String campaignStatus,
      @Schema(description = "币种") String currency,
      @Schema(description = "🔴 当前日预算（null = 平台侧没有预算）", type = "string") BigDecimal currentBudget,
      @Schema(description = "窗口内消耗", type = "string") BigDecimal historicalSpend,
      @Schema(description = "窗口内销售额", type = "string") BigDecimal historicalSales,
      @Schema(description = "窗口内订单", type = "string") BigDecimal historicalOrders,
      @Schema(description = "🔴 ROAS = 销售额 / 消耗；null = 不可比", type = "string") BigDecimal roas,
      @Schema(description = "🔴 ACOS = 消耗 / 销售额；null = 不可比", type = "string") BigDecimal acos,
      @Schema(description = "🔴 日均消耗（窗口内总消耗 / 窗口自然日数）", type = "string")
          BigDecimal dailyAvgSpend,
      @Schema(description = "🔴 利用率 = 日均消耗 / 日预算；null = 不可比", type = "string")
          BigDecimal utilization,
      @Schema(description = "销售目标（未登记为 null）", type = "string") BigDecimal salesTarget,
      @Schema(description = "评分（各来源归一化后的加权和）", type = "string") BigDecimal score,
      @Schema(description = "权重（= 评分 / 全体评分之和）", type = "string") BigDecimal weight,
      @Schema(description = "权重分额", type = "string") BigDecimal weightShare,
      @Schema(description = "🔴 分配额", type = "string") BigDecimal allocatedBudget,
      @Schema(description = "🔴 相对当前预算的增减额", type = "string") BigDecimal budgetDelta,
      @Schema(description = "🔴 预算受限（利用率触顶）") boolean budgetConstrained,
      @Schema(description = "🔴 闲置预算（利用率过低）") boolean idleBudget,
      @Schema(description = "调整建议") String recommendation,
      @Schema(description = "判定依据（写清为什么给这个建议）") String reason,
      @Schema(description = "排序位") int position) {}

  /** 本次**未纳入分配**的活动与原因（不静默丢掉）。 */
  public record AdBudgetSkippedRow(
      @Schema(description = "记录 id") UUID id,
      @Schema(description = "活动 id") UUID campaignId,
      @Schema(description = "活动名") String campaignName,
      @Schema(description = "平台编码") String platformCode,
      @Schema(description = "店铺编码") String shopCode,
      @Schema(description = "活动状态") String campaignStatus,
      @Schema(description = "币种") String currency,
      @Schema(description = "当前日预算", type = "string") BigDecimal currentBudget,
      @Schema(description = "🔴 未纳入原因：CURRENCY_MISMATCH / NO_SCORE_BASIS / STATUS_NOT_ELIGIBLE / OUT_OF_SCOPE")
          String skipReason,
      @Schema(description = "原因说明") String reason) {}

  public record AdBudgetPlanDetail(
      @Schema(description = "方案") AdBudgetPlanRow plan,
      @Schema(description = "🔴 分配明细（逐活动冻结快照）") List<AdBudgetPlanLineRow> lines,
      @Schema(description = "🔴 未纳入分配的活动与原因") List<AdBudgetSkippedRow> skipped,
      @Schema(description = "🔴 再平衡建议（总额不变，只改分布）") List<AdBudgetRebalanceRow> rebalances,
      @Schema(description = "说明") String note) {}

  // ================================================================ 再平衡建议

  public record AdBudgetRebalanceRow(
      @Schema(description = "建议 id") UUID id,
      @Schema(description = "所属方案 id") UUID planId,
      @Schema(description = "🔴 挪出预算的活动 id") UUID fromCampaignId,
      @Schema(description = "挪出预算的活动名") String fromCampaignName,
      @Schema(description = "🔴 挪入预算的活动 id") UUID toCampaignId,
      @Schema(description = "挪入预算的活动名") String toCampaignName,
      @Schema(description = "🔴 建议调整额", type = "string") BigDecimal amount,
      @Schema(description = "建议依据") String reason,
      @Schema(description = "状态") String status,
      @Schema(description = "决定时刻") OffsetDateTime decidedAt,
      @Schema(description = "创建时刻") OffsetDateTime createdAt) {}

  public record AdBudgetRebalancePage(
      @Schema(description = "当前页数据") List<AdBudgetRebalanceRow> items,
      @Schema(description = "符合筛选条件的总条数") long total,
      @Schema(description = "页码，从 0 起") int page,
      @Schema(description = "每页条数") int size) {}

  // ================================================================ 执行任务

  public record AdBudgetTaskRow(
      @Schema(description = "任务 id") UUID id,
      @Schema(description = "所属方案 id") UUID planId,
      @Schema(description = "预算池名称") String poolName,
      @Schema(description = "任务类型") String taskType,
      @Schema(description = "🔴 状态：PENDING / APPLIED / PARTIALLY_APPLIED / FAILED / CANCELLED")
          String status,
      @Schema(description = "币种") String currency,
      @Schema(description = "发起人（已认证成员）") UUID requestedBy,
      @Schema(description = "执行理由") String reason,
      @Schema(description = "执行时刻") OffsetDateTime appliedAt,
      @Schema(description = "备注") String note,
      @Schema(description = "逐条条数") int lineCount,
      @Schema(description = "已生效条数") int appliedCount,
      @Schema(description = "被护栏拒绝条数") int rejectedCount,
      @Schema(description = "跳过条数") int skippedCount,
      @Schema(description = "创建时刻") OffsetDateTime createdAt) {}

  public record AdBudgetTaskPage(
      @Schema(description = "当前页数据") List<AdBudgetTaskRow> items,
      @Schema(description = "符合筛选条件的总条数") long total,
      @Schema(description = "页码，从 0 起") int page,
      @Schema(description = "每页条数") int size) {}

  public record AdBudgetTaskLineRow(
      @Schema(description = "明细 id") UUID id,
      @Schema(description = "活动 id") UUID campaignId,
      @Schema(description = "活动名") String campaignName,
      @Schema(description = "变更前日预算", type = "string") BigDecimal beforeBudget,
      @Schema(description = "🔴 目标日预算", type = "string") BigDecimal targetBudget,
      @Schema(description = "🔴 实际生效后的日预算（被拒时为 null）", type = "string") BigDecimal appliedBudget,
      @Schema(description = "调整额", type = "string") BigDecimal budgetDelta,
      @Schema(description = "🔴 结果：APPLIED / REJECTED / SKIPPED") String result,
      @Schema(description = "🔴 结果说明（被拒时给出原因）") String message) {}

  public record AdBudgetTaskDetail(
      @Schema(description = "任务") AdBudgetTaskRow task,
      @Schema(description = "🔴 逐条结果（含被护栏拒绝的那几条）") List<AdBudgetTaskLineRow> lines,
      @Schema(description = "说明") String note) {}

  // ================================================================ 请求

  public record SaveBudgetWeight(
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "权重来源：SALES_TARGET / ROAS / PROFIT")
          @NotBlank
          String weightSource,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "权重（>= 0）") @NotNull
          @DecimalMin("0")
          BigDecimal weight,
      @Schema(description = "备注") String note) {}

  public record SaveBudgetTarget(
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "活动 id") @NotNull UUID campaignId,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "销售目标（> 0，与规则同币种）") @NotNull
          @DecimalMin(value = "0", inclusive = false)
          BigDecimal salesTarget,
      @Schema(description = "逐活动预算下限（覆盖规则级）") BigDecimal minBudget,
      @Schema(description = "逐活动预算上限（覆盖规则级）") BigDecimal maxBudget,
      @Schema(description = "备注") String note) {}

  public record SaveBudgetRuleRequest(
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "规则编码（租户内唯一）") @NotBlank
          String ruleCode,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "规则名称") @NotBlank String ruleName,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "币种（一个规则一个币种）") @NotBlank
          String currency,
      @Schema(description = "限定平台 id（为空 = 不限）") UUID scopePlatformId,
      @Schema(description = "限定店铺编码（为空 = 不限）") String scopeShopCode,
      @Schema(description = "规则级逐活动预算下限") BigDecimal minCampaignBudget,
      @Schema(description = "规则级逐活动预算上限") BigDecimal maxCampaignBudget,
      @Schema(description = "预算受限的利用率阈值（默认 0.95）") BigDecimal constrainedUtilization,
      @Schema(description = "闲置预算的利用率阈值（默认 0.30）") BigDecimal idleUtilization,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "权重位（至少一个权重大于 0）")
          @NotEmpty
          List<@Valid SaveBudgetWeight> weights,
      @Schema(description = "逐活动销售目标") List<@Valid SaveBudgetTarget> targets,
      @Schema(description = "备注") String note) {}

  public record SaveBudgetPoolRequest(
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "预算池编码（租户内唯一）") @NotBlank
          String poolCode,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "预算池名称") @NotBlank String poolName,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "币种（与规则一致）") @NotBlank
          String currency,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "预算总额（> 0）") @NotNull
          @DecimalMin(value = "0", inclusive = false)
          BigDecimal totalAmount,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "周期开始") @NotNull LocalDate periodStart,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "周期结束") @NotNull LocalDate periodEnd,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "分配规则 id") @NotNull UUID ruleId,
      @Schema(description = "备注") String note) {}

  public record ComputeBudgetPlanRequest(
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "预算池 id") @NotNull UUID poolId,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "统计窗口开始（显式给出，不从墙上时钟推导）")
          @NotNull
          LocalDate windowStart,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "统计窗口结束") @NotNull
          LocalDate windowEnd,
      @Schema(description = "备注") String note) {}

  public record CreateBudgetTaskRequest(
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "分配方案 id") @NotNull UUID planId,
      @Schema(description = "执行理由（写进审计）") @Size(max = 500) String reason) {}

  // ================================================================ 选项

  public record AdBudgetOption(
      @Schema(description = "取值") String value,
      @Schema(description = "展示名") String label) {}

  public record AdBudgetOptions(
      @Schema(description = "权重来源下拉项") List<AdBudgetOption> weightSources,
      @Schema(description = "规则状态下拉项") List<AdBudgetOption> ruleStatuses,
      @Schema(description = "方案状态下拉项") List<AdBudgetOption> planStatuses,
      @Schema(description = "任务状态下拉项") List<AdBudgetOption> taskStatuses,
      @Schema(description = "调整建议下拉项") List<AdBudgetOption> recommendations,
      @Schema(description = "未纳入原因下拉项") List<AdBudgetOption> skipReasons,
      @Schema(description = "本租户出现过的币种") List<String> currencies,
      @Schema(description = "🔴 最早可用指标日期（界面据此预填统计窗口）") LocalDate metricFrom,
      @Schema(description = "🔴 最晚可用指标日期") LocalDate metricTo,
      @Schema(description = "执行护栏上限") AdBudgetAllocationGuardrails guardrails,
      @Schema(description = "分配口径说明") String allocationNote,
      @Schema(description = "利用率判定口径") String utilizationNote) {}
}
