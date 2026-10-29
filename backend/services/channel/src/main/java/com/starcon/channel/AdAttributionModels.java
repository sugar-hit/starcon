package com.starcon.channel;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 14.3.2.1 归因分析 的传输模型。
 *
 * <p>蓝图：「广告归因：把广告花费归因到 SKU/订单（支持平台归因窗口口径），计算广告后净利、
 * TACOS、增量效果估计。输入：平台广告归因数据；订单数据；利润数据；归因规则配置（末次点击/平台归因窗口）。
 * 输出：归因明细 + 广告后利润报表。关系：上游 = 14.3.1.3、13.4.1.2；下游 = 13.4 利润、5 定价。
 * <b>归因规则必须固定并公示</b>，规则变了历史报表不可比——这是广告与财务对账最容易吵架的点。」
 *
 * <p>🔴 这个模型层钉死本模块最容易做丢的五件事：
 *
 * <ol>
 *   <li><b>规则快照随行走</b>：{@code ruleCode} / {@code ruleVersion} / {@code attributionModel} /
 *       {@code attributionWindowDays} 出现在**归因明细的每一行**、**报表的每一行**、
 *       **导出的每一行**与**导出留痕**里 —— 规则变了以后，历史报表仍然能被解释清楚。
 *   <li><b>口径原文随响应走</b>：{@code attributionNote}（规则原文）与
 *       {@code metricDefinitionNote}（逐指标公式）随报表、明细、导出一起返回，
 *       前端**原样展示**，不自己写第二份。
 *   <li><b>归不上的单列</b>：{@code unattributedSpend} / {@code unattributedSales} /
 *       {@code unattributedOrders} 单列，<b>不摊给任何 SKU，也不摊成 0</b>。
 *   <li><b>比率列的 {@code null} = 不可比（分母为 0），与 {@code 0} 含义相反</b>。
 *   <li><b>不跨币种相加</b>：本仓没有汇率源 ⇒ 每一行带自己的币种，汇总按币种拆开。
 * </ol>
 *
 * <p>🔴 金额与数量一律 {@link BigDecimal}，**没有 double**：广告花费与广告销售额直接进财务对账。
 */
public final class AdAttributionModels {
  private AdAttributionModels() {}

  // ================================================================ 能力声明

  public record AdAttributionCapabilityDimension(
      @Schema(description = "维度键") String dimension,
      @Schema(description = "维度说明") String description,
      @Schema(description = "本模块是否已交付") boolean available,
      @Schema(description = "数据来源（表 / 上游模块）") String source,
      @Schema(description = "未交付段的责任模块") String ownerModule,
      @Schema(description = "不交付的影响") String impact) {}

  /**
   * 单个指标的口径定义 —— **表头就是从这里渲染的**。
   *
   * <p>🔴 {@code available=false} 的含义是「<b>当前拿不到</b>」，不是「这一列是 0」。
   */
  public record AdAttributionMetricDefinition(
      @Schema(description = "指标键") String metric,
      @Schema(description = "指标显示名") String label,
      @Schema(description = "🔴 当前是否可算（false = 这一格取不到，不是 0）") boolean available,
      @Schema(description = "🔴 口径定义（写进表头的那句话）") String formula,
      @Schema(description = "数据来源") String source,
      @Schema(description = "未交付段的责任模块") String ownerModule,
      @Schema(description = "🔴 取不到的原因（available=false 时说明为什么没有数值）")
          String unavailableReason) {}

  public record AdAttributionCapabilities(
      @Schema(description = "逐维度能力声明；未交付段 available=false")
          List<AdAttributionCapabilityDimension> dimensions,
      @Schema(description = "🔴 逐指标口径定义（TACOS / 增量效果估计的定义写在这里）")
          List<AdAttributionMetricDefinition> metricDefinitions,
      @Schema(description = "归因模型取值") List<String> attributionModels,
      @Schema(description = "归因状态取值") List<String> attributionStatuses,
      @Schema(description = "空态原因取值") List<String> emptyReasons,
      @Schema(description = "🔴 归因口径原文（规则变了历史报表不可比，必须公示）")
          String attributionNote,
      @Schema(description = "🔴 逐指标口径说明（随报表一起返回）") String metricDefinitionNote,
      @Schema(description = "🔴 增量效果估计的口径原文（它是推导值，不是平台原值）")
          String incrementalityNote,
      @Schema(description = "上游依赖说明") String upstreamNote,
      @Schema(description = "下游与边界说明") String boundaryNote) {}

  // ================================================================ 选项

  public record AdAttributionOption(
      @Schema(description = "取值") String value, @Schema(description = "显示名") String label) {}

  public record AdAttributionOptions(
      @Schema(description = "归因模型下拉项") List<AdAttributionOption> attributionModels,
      @Schema(description = "归因状态下拉项") List<AdAttributionOption> attributionStatuses,
      @Schema(description = "排序下拉项") List<AdAttributionOption> sorts,
      @Schema(description = "店铺下拉项") List<AdAttributionOption> shops,
      @Schema(description = "平台下拉项") List<AdAttributionOption> platforms,
      @Schema(description = "🔴 逐指标口径定义") List<AdAttributionMetricDefinition> metricDefinitions,
      @Schema(description = "🔴 逐指标口径说明") String metricDefinitionNote,
      @Schema(description = "🔴 归因口径原文") String attributionNote,
      @Schema(description = "🔴 增量效果估计的口径原文") String incrementalityNote,
      @Schema(description = "🔴 时间范围与上限说明") String rangeLimitNote) {}

  // ================================================================ 归因规则

  /**
   * 一条归因规则。
   *
   * <p>🔴 {@code version} 是规则版本：每次改动 +1。归因结果与导出留痕都带它 ⇒ 历史口径可追。
   */
  public record AdAttributionRuleRow(
      @Schema(description = "规则 id") UUID id,
      @Schema(description = "规则编码（租户内唯一）") String ruleCode,
      @Schema(description = "🔴 归因模型：LAST_CLICK / PLATFORM_WINDOW") String attributionModel,
      @Schema(description = "🔴 归因窗口（天）；0 = 只有与下单同时刻的点击才算")
          int attributionWindowDays,
      @Schema(description = "状态：ACTIVE / PAUSED") String status,
      @Schema(description = "🔴 规则版本（每次改动 +1）") long version,
      @Schema(description = "备注") String note,
      @Schema(description = "创建时刻") OffsetDateTime createdAt,
      @Schema(description = "更新时刻") OffsetDateTime updatedAt) {}

  public record AdAttributionRulePage(
      @Schema(description = "当前页数据") List<AdAttributionRuleRow> items,
      @Schema(description = "总条数") long total,
      @Schema(description = "页码，从 0 起") int page,
      @Schema(description = "每页条数") int size) {}

  /**
   * 保存归因规则的请求。
   *
   * <p>🔴 {@code id} 为空 = 新建；不为空 = 改这一条（**版本 +1**）。
   * {@code activate=true} 时它成为本租户**唯一**的生效口径（其余生效规则会被置为 PAUSED）。
   */
  public record SaveAdAttributionRuleRequest(
      @Schema(description = "规则 id（null = 新建）") UUID id,
      @Schema(description = "规则编码（租户内唯一）") String ruleCode,
      @Schema(description = "🔴 归因模型：LAST_CLICK / PLATFORM_WINDOW") String attributionModel,
      @Schema(description = "🔴 归因窗口（天）") int attributionWindowDays,
      @Schema(description = "备注") String note,
      @Schema(description = "是否立即生效（true = 成为本租户唯一生效口径）") boolean activate) {}

  // ================================================================ 平台广告归因数据导入

  /** 一次点击（末次点击口径需要它们）。 */
  public record AdAttributionClickImport(
      @Schema(description = "平台侧点击 id") String clickId,
      @Schema(description = "🔴 点击时刻（timestamptz）") OffsetDateTime clickAt,
      @Schema(description = "🔴 平台自己标记的归因点击（PLATFORM_WINDOW 口径用它）")
          boolean platformAttributed) {}

  /**
   * 一条订单级归因候选（平台广告归因报告的一行）。
   *
   * <p>🔴 活动可以用内部 {@code campaignId} 指定，也可以用平台侧的
   * {@code externalCampaignId + shopCode + platformCode} 让服务端自己解析
   * （平台报告里没有我们的内部 id）。
   */
  public record AdAttributionOrderImport(
      @Schema(description = "内部活动 id（与下面三项二选一）") UUID campaignId,
      @Schema(description = "平台侧活动 id") String externalCampaignId,
      @Schema(description = "店铺编码") String shopCode,
      @Schema(description = "平台编码") String platformCode,
      @Schema(description = "平台侧订单号") String externalOrderNo,
      @Schema(description = "🔴 下单时刻（timestamptz）") OffsetDateTime orderAt,
      @Schema(description = "🔴 内部 SKU（null = 归得到订单但归不到 SKU）") UUID internalSkuId,
      @Schema(description = "🔴 币种（char3）") String currency,
      @Schema(description = "广告花费") BigDecimal attributedSpend,
      @Schema(description = "广告销售额") BigDecimal attributedSales,
      @Schema(description = "广告归因订单数") BigDecimal attributedOrders,
      @Schema(description = "广告归因件数") BigDecimal attributedUnits,
      @Schema(description = "🔴 点击触点列表") List<AdAttributionClickImport> clicks) {}

  /** 平台广告归因数据导入请求。 */
  public record AdAttributionImportRequest(
      @Schema(description = "🔴 这批数据的数据截至时刻") OffsetDateTime dataAsOf,
      @Schema(description = "订单级归因候选列表") List<AdAttributionOrderImport> orders) {}

  /** 导入结果。 */
  public record AdAttributionImportResult(
      @Schema(description = "读进来的订单行数") int orderRows,
      @Schema(description = "写进去（新增或刷新）的订单行数") int writtenOrderRows,
      @Schema(description = "写进去的点击行数") int writtenClickRows,
      @Schema(description = "🔴 被拒绝的行数与原因（**逐条列明**，不静默丢弃）")
          List<String> rejected,
      @Schema(description = "数据截至时刻") OffsetDateTime dataAsOf,
      @Schema(description = "🔴 导入口径说明") String note) {}

  // ================================================================ 归因计算

  /** 归因计算请求。 */
  public record AdAttributionRunRequest(
      @Schema(description = "规则 id（null = 当前生效规则）") UUID ruleId,
      @Schema(description = "统计起始日（含）") LocalDate from,
      @Schema(description = "统计截止日（含）") LocalDate to,
      @Schema(description = "🔴 是否重算已归因的行（false = 已归因的是事实，不改写）")
          Boolean recompute) {}

  /** 归因计算结果。 */
  public record AdAttributionRunResult(
      @Schema(description = "用的规则 id") UUID ruleId,
      @Schema(description = "规则编码") String ruleCode,
      @Schema(description = "🔴 规则版本") long ruleVersion,
      @Schema(description = "🔴 归因模型") String attributionModel,
      @Schema(description = "🔴 归因窗口（天）") int attributionWindowDays,
      @Schema(description = "窗口内参与计算的订单行数") int candidateOrders,
      @Schema(description = "🔴 写入的归因结果行数") int writtenFacts,
      @Schema(description = "🔴 归因成立（进报表）的行数") int attributedFacts,
      @Schema(description = "🔴 归因不成立的行数") int unattributedFacts,
      @Schema(description = "🔴 归因不成立的原因分布（状态 → 行数）")
          List<AdAttributionStatusCount> statusCounts,
      @Schema(description = "🔴 口径说明") String note) {}

  /** 归因状态计数。 */
  public record AdAttributionStatusCount(
      @Schema(description = "归因状态") String status,
      @Schema(description = "说明") String label,
      @Schema(description = "行数") long count) {}

  // ================================================================ 归因明细

  /**
   * 归因明细的一行（一行 = 一条订单在某套规则下的归因结论）。
   *
   * <p>🔴 {@code internalSkuId} 为空 = 归到了订单但**归不到唯一内部 SKU**；
   * 这与 {@code attributionStatus}（点击 / 模型口径的结论）是**两个正交维度**。
   */
  public record AdAttributionRecordRow(
      @Schema(description = "归因结果 id") UUID id,
      @Schema(description = "平台侧订单号") String externalOrderNo,
      @Schema(description = "平台编码") String platformCode,
      @Schema(description = "店铺编码") String shopCode,
      @Schema(description = "内部活动 id") UUID campaignId,
      @Schema(description = "活动名") String campaignName,
      @Schema(description = "🔴 内部 SKU（null = 归不到 SKU）") UUID internalSkuId,
      @Schema(description = "🔴 币种") String currency,
      @Schema(description = "下单时刻") OffsetDateTime orderAt,
      @Schema(description = "🔴 归因状态：ATTRIBUTED / NO_ELIGIBLE_CLICK / NO_PLATFORM_ATTRIBUTED_CLICK")
          String attributionStatus,
      @Schema(description = "🔴 归因状态的中文说明") String attributionStatusLabel,
      @Schema(description = "🔴 这一行进不进 SKU 报表（= 状态成立 且 归到了 SKU）")
          boolean attributed,
      @Schema(description = "🔴 命中哪一次点击（空串 = 没命中）") String chosenClickId,
      @Schema(description = "命中点击的时刻（null = 没命中）") OffsetDateTime chosenClickAt,
      @Schema(description = "🔴 窗口内的合格点击数") int eligibleClickCount,
      @Schema(description = "广告花费", type = "string") BigDecimal attributedSpend,
      @Schema(description = "广告销售额", type = "string") BigDecimal attributedSales,
      @Schema(description = "广告归因订单数", type = "string") BigDecimal attributedOrders,
      @Schema(description = "广告归因件数", type = "string") BigDecimal attributedUnits,
      @Schema(description = "🔴 规则编码（快照）") String ruleCode,
      @Schema(description = "🔴 规则版本（快照）") long ruleVersion,
      @Schema(description = "🔴 归因模型（快照）") String attributionModel,
      @Schema(description = "🔴 归因窗口（快照）") int attributionWindowDays,
      @Schema(description = "归因计算时刻") OffsetDateTime attributedAt) {}

  /** 归因明细的汇总（**不受分页影响**：统计的是筛选后的全集，不是当前页）。 */
  public record AdAttributionRecordSummary(
      @Schema(description = "币种") String currency,
      @Schema(description = "符合筛选条件的行数") long rowCount,
      @Schema(description = "🔴 归因成立（进报表）的行数") long attributedRows,
      @Schema(description = "🔴 归不上的行数") long unattributedRows,
      @Schema(description = "广告花费合计（全部行）", type = "string") BigDecimal totalSpend,
      @Schema(description = "广告销售额合计（全部行）", type = "string") BigDecimal totalSales,
      @Schema(description = "广告归因订单数合计（全部行）", type = "string") BigDecimal totalOrders,
      @Schema(description = "广告归因件数合计（全部行）", type = "string") BigDecimal totalUnits,
      @Schema(description = "🔴 归因成立（进报表）的广告花费", type = "string")
          BigDecimal attributedSpend,
      @Schema(description = "🔴 归因成立（进报表）的广告销售额", type = "string")
          BigDecimal attributedSales,
      @Schema(description = "🔴 归不上（不摊给任何 SKU，也不摊成 0）的广告花费", type = "string")
          BigDecimal unattributedSpend,
      @Schema(description = "🔴 归不上的广告销售额", type = "string") BigDecimal unattributedSales,
      @Schema(description = "🔴 归不上的广告订单数", type = "string") BigDecimal unattributedOrders,
      @Schema(description = "🔴 归因状态分布") List<AdAttributionStatusCount> statusCounts,
      @Schema(description = "🔴 归因口径原文") String attributionNote) {}

  /** 归因明细页。 */
  public record AdAttributionRecordPage(
      @Schema(description = "当前页数据") List<AdAttributionRecordRow> items,
      @Schema(description = "符合筛选条件的行数（不受分页影响）") long total,
      @Schema(description = "页码，从 0 起") int page,
      @Schema(description = "每页条数") int size,
      @Schema(description = "🔴 用的规则 id") UUID ruleId,
      @Schema(description = "🔴 规则编码") String ruleCode,
      @Schema(description = "🔴 规则版本") long ruleVersion,
      @Schema(description = "🔴 归因模型") String attributionModel,
      @Schema(description = "🔴 归因窗口（天）") int attributionWindowDays,
      @Schema(description = "🔴 筛选后全集的汇总（按币种拆开，不受分页影响）")
          List<AdAttributionRecordSummary> summaries,
      @Schema(description = "🔴 空态原因：NO_IMPORTED_DATA / NO_FACTS_FOR_RULE / FILTERED_EMPTY / NO_CAMPAIGN_BINDING / null")
          String emptyReason,
      @Schema(description = "🔴 空态说明文案（几种空的解释各不相同）") String emptyNote,
      @Schema(description = "🔴 逐指标口径说明") String metricDefinitionNote,
      @Schema(description = "🔴 归因口径原文") String attributionNote,
      @Schema(description = "🔴 增量效果估计的口径原文") String incrementalityNote) {}

  // ================================================================ 广告后利润报表

  /**
   * 广告后利润报表的一行（一行 = 一个内部 SKU × 一个币种）。
   *
   * <p>🔴 比率列的 {@code null} = 不可比（分母为 0），与 {@code 0}（可比但真的是零）含义相反。
   */
  public record AdAttributionReportRow(
      @Schema(description = "内部 SKU id") UUID skuId,
      @Schema(description = "内部 SKU 编码（catalog 读不到时为空串）") String skuCode,
      @Schema(description = "SPU 编码（catalog 读不到时为空串）") String spuCode,
      @Schema(description = "🔴 币种") String currency,
      @Schema(description = "🔴 归因成立并归到本 SKU 的广告花费", type = "string")
          BigDecimal adSpend,
      @Schema(description = "🔴 归因成立并归到本 SKU 的广告销售额", type = "string")
          BigDecimal adSales,
      @Schema(description = "🔴 归因成立并归到本 SKU 的订单数", type = "string")
          BigDecimal adOrders,
      @Schema(description = "🔴 归因成立并归到本 SKU 的件数", type = "string")
          BigDecimal adUnits,
      @Schema(description = "🔴 总销售额（13.4.1.2 利润口径；上游读不到时为 null）", type = "string")
          BigDecimal totalSales,
      @Schema(description = "🔴 总件数（13.4.1.2 利润口径；上游读不到时为 null）", type = "string")
          BigDecimal totalUnits,
      @Schema(description = "🔴 总净利（13.4.1.2 利润口径；上游读不到时为 null）", type = "string")
          BigDecimal netProfit,
      @Schema(description = "🔴 利润口径的广告费（13.4.1.2 的 AD_FEE；上游读不到时为 null）", type = "string")
          BigDecimal profitAdFee,
      @Schema(description = "🔴 ACOS = Σ广告花费 / Σ广告销售额（分母为 0 时为 null=不可比）", type = "string")
          BigDecimal acos,
      @Schema(description = "🔴 TACOS = Σ广告花费 / 总销售额（分母为 0 时为 null=不可比）", type = "string")
          BigDecimal tacos,
      @Schema(description = "🔴 广告销售占比 = 广告销售额 / 总销售额（分母为 0 时为 null=不可比）", type = "string")
          BigDecimal adSalesShare,
      @Schema(description = "🔴 广告后净利 = 总净利 − 广告花费（任一项缺失时为 null）", type = "string")
          BigDecimal adAfterNetProfit,
      @Schema(description = "🔴 广告占比利润 = 广告花费 / 总净利（分母为 0 或负时为 null=不可比）", type = "string")
          BigDecimal adProfitShare,
      @Schema(description = "🔴 增量销售额估计（**推导值，不是平台原值**；算不出来时为 null）", type = "string")
          BigDecimal incrementalSalesEstimate,
      @Schema(description = "🔴 增量占比估计 = 增量销售额估计 / 总销售额（分母为 0 时为 null）", type = "string")
          BigDecimal incrementalShare,
      @Schema(description = "🔴 利润侧不可用时的原因（可用则为空串）") String profitNote,
      @Schema(description = "🔴 规则编码（快照）") String ruleCode,
      @Schema(description = "🔴 规则版本（快照）") long ruleVersion,
      @Schema(description = "🔴 归因模型（快照）") String attributionModel,
      @Schema(description = "🔴 归因窗口（快照）") int attributionWindowDays) {}

  /** 广告后利润报表的汇总（**不受分页影响**，按币种拆开）。 */
  public record AdAttributionReportSummary(
      @Schema(description = "币种") String currency,
      @Schema(description = "该币种下的 SKU 条数") long skuCount,
      @Schema(description = "🔴 归因成立并归到 SKU 的广告花费", type = "string")
          BigDecimal attributedSpend,
      @Schema(description = "🔴 归因成立并归到 SKU 的广告销售额", type = "string")
          BigDecimal attributedSales,
      @Schema(description = "🔴 归因成立并归到 SKU 的订单数", type = "string")
          BigDecimal attributedOrders,
      @Schema(description = "🔴 归因成立并归到 SKU 的件数", type = "string")
          BigDecimal attributedUnits,
      @Schema(description = "🔴 归不上（不摊给任何 SKU，也不摊成 0）的广告花费", type = "string")
          BigDecimal unattributedSpend,
      @Schema(description = "🔴 归不上的广告销售额", type = "string") BigDecimal unattributedSales,
      @Schema(description = "🔴 归不上的广告订单数", type = "string") BigDecimal unattributedOrders,
      @Schema(description = "🔴 活动级总花费（14.1.1.1 `ad_campaign_daily_metric`；与订单级归因是两种粒度）", type = "string")
          BigDecimal campaignTotalSpend,
      @Schema(description = "🔴 活动总花费里没有落到订单上的部分（对账缺口；可为负）", type = "string")
          BigDecimal campaignSpendGap,
      @Schema(description = "🔴 归因覆盖率 = 归因花费 / 活动总花费（分母为 0 时为 null）", type = "string")
          BigDecimal attributionCoverage,
      @Schema(description = "🔴 总销售额（13.4.1.2；上游读不到时为 null）", type = "string")
          BigDecimal totalRevenue,
      @Schema(description = "🔴 总件数（13.4.1.2；上游读不到时为 null）", type = "string")
          BigDecimal totalUnits,
      @Schema(description = "🔴 总净利（13.4.1.2；上游读不到时为 null）", type = "string")
          BigDecimal totalNetProfit,
      @Schema(description = "🔴 ACOS = Σ广告花费 / Σ广告销售额（分母为 0 时为 null）", type = "string")
          BigDecimal acos,
      @Schema(description = "🔴 TACOS = Σ广告花费 / 总销售额（分母为 0 时为 null）", type = "string")
          BigDecimal tacos,
      @Schema(description = "🔴 广告销售占比 = 广告销售额 / 总销售额（分母为 0 时为 null）", type = "string")
          BigDecimal adSalesShare,
      @Schema(description = "🔴 广告后净利 = 总净利 − 广告花费（任一项缺失时为 null）", type = "string")
          BigDecimal adAfterNetProfit,
      @Schema(description = "🔴 广告占比利润 = 广告花费 / 总净利（分母为 0 或负时为 null）", type = "string")
          BigDecimal adProfitShare,
      @Schema(description = "🔴 增量销售额估计（**推导值**；算不出来时为 null）", type = "string")
          BigDecimal incrementalSalesEstimate,
      @Schema(description = "🔴 增量占比估计（分母为 0 时为 null）", type = "string")
          BigDecimal incrementalShare,
      @Schema(description = "🔴 逐指标口径说明") String metricDefinitionNote,
      @Schema(description = "🔴 归因口径原文") String attributionNote,
      @Schema(description = "🔴 增量效果估计的口径原文") String incrementalityNote) {}

  /** 广告后利润报表页。 */
  public record AdAttributionReportPage(
      @Schema(description = "当前页数据") List<AdAttributionReportRow> items,
      @Schema(description = "符合筛选条件的 SKU 条数（不受分页影响）") long total,
      @Schema(description = "页码，从 0 起") int page,
      @Schema(description = "每页条数") int size,
      @Schema(description = "🔴 用的规则 id") UUID ruleId,
      @Schema(description = "🔴 规则编码") String ruleCode,
      @Schema(description = "🔴 规则版本") long ruleVersion,
      @Schema(description = "🔴 归因模型") String attributionModel,
      @Schema(description = "🔴 归因窗口（天）") int attributionWindowDays,
      @Schema(description = "🔴 筛选后全集的汇总（按币种拆开，不受分页影响）")
          List<AdAttributionReportSummary> summaries,
      @Schema(description = "🔴 数据截至时刻") OffsetDateTime dataAsOf,
      @Schema(description = "🔴 空态原因") String emptyReason,
      @Schema(description = "🔴 空态说明文案") String emptyNote,
      @Schema(description = "🔴 逐指标口径定义（表头渲染用）")
          List<AdAttributionMetricDefinition> metricDefinitions,
      @Schema(description = "🔴 逐指标口径说明") String metricDefinitionNote,
      @Schema(description = "🔴 归因口径原文") String attributionNote,
      @Schema(description = "🔴 增量效果估计的口径原文") String incrementalityNote,
      @Schema(description = "🔴 利润上游（13.4.1.2）这次是否读到了") boolean profitAvailable,
      @Schema(description = "🔴 利润上游读不到时的原因（读到了则为空串）")
          String profitUnavailableReason,
      @Schema(description = "🔴 商品主数据上游（catalog）这次是否读到了") boolean catalogAvailable,
      @Schema(description = "🔴 商品主数据上游读不到时的原因（读到了则为空串）")
          String catalogUnavailableReason) {}

  // ================================================================ 导出

  public record AdAttributionExportRow(
      @Schema(description = "导出留痕 id") UUID id,
      @Schema(description = "发起人（已认证成员）") UUID requestedBy,
      @Schema(description = "🔴 规则 id") UUID ruleId,
      @Schema(description = "🔴 规则编码（快照）") String ruleCode,
      @Schema(description = "🔴 规则版本（快照）") long ruleVersion,
      @Schema(description = "🔴 归因模型（快照）") String attributionModel,
      @Schema(description = "🔴 归因窗口（快照）") int attributionWindowDays,
      @Schema(description = "店铺筛选（null = 没筛）") String shopCode,
      @Schema(description = "活动筛选（null = 没筛）") UUID campaignId,
      @Schema(description = "SKU 筛选（null = 没筛）") UUID skuId,
      @Schema(description = "币种筛选（null = 没筛）") String currency,
      @Schema(description = "统计起始日") LocalDate dateFrom,
      @Schema(description = "统计截止日") LocalDate dateTo,
      @Schema(description = "导出的行数") int rowCount,
      @Schema(description = "🔴 这次导出对着哪个水位算出来的") OffsetDateTime dataAsOf,
      @Schema(description = "🔴 导出当时的逐指标口径定义") String metricDefinitionNote,
      @Schema(description = "🔴 导出当时的归因口径原文") String attributionNote,
      @Schema(description = "发生时刻") OffsetDateTime createdAt) {}

  public record AdAttributionExportPage(
      @Schema(description = "当前页数据") List<AdAttributionExportRow> items,
      @Schema(description = "总条数") long total,
      @Schema(description = "页码，从 0 起") int page,
      @Schema(description = "每页条数") int size) {}
}
