package com.starcon.channel;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 14.3.1.1 活动报表 的传输模型。
 *
 * <p>蓝图：「活动维度效果报表：花费、曝光、点击、CTR、CPC、订单、转化率、销售额、ACOS/ROAS/TACOS，
 * 按日/周/月与多维对比，支持导出。输入：时间范围；维度；广告数据。
 * 输出：报表表 + 趋势图 + 口径说明。关系：上游 = 14.1.1.1；下游 = 14.3.2.1。
 * <b>ACOS/ROAS/TACOS 三个口径要在表头写明定义</b>，否则跨部门沟通会各说各话。」
 *
 * <p>🔴 这个模型层钉死本模块最容易做丢的四件事：
 *
 * <ol>
 *   <li><b>三个口径写在返回值里，不写在注释里</b>。{@code metricDefinitions} 逐指标给出
 *       {@code formula}（口径定义）、{@code available}（有没有分母）、{@code unavailableReason}
 *       （为什么没有）。界面的表头直接用它渲染 —— 跨部门沟通时「ACOS 到底是哪个除哪个」
 *       必须是同一份定义，而不是各人自己解释一遍。
 *   <li><b>TACOS 的「没有」是一个被显式声明的事实</b>。总销售额来自 13.4 利润模块，
 *       而卷 13 的主责服务不在集成头 ⇒ TACOS <b>没有分母</b>。
 *       本模型<b>不给</b> TACOS 留数值字段（不是留一个永远为 null 的字段 ——
 *       那会让调用方误以为「取到了，只是恰好是空」），而是让能力声明把这件事说清楚。
 *   <li><b>汇总是「先汇总再相除」的那一份</b>。{@code AdCampaignReportSummary} 里的
 *       {@code acos} / {@code roas} 是 Σ消耗 / Σ销售额，<b>不是</b>各行比率的平均 ——
 *       后者是数学错误，会把两条 ACOS 100% 的活动说成整体 50%。
 *   <li><b>汇总按币种拆开</b>。本仓没有汇率源，把不同币种加在一起等于造一个假总数；
 *       所以 {@code summaries} 是一个列表，每个币种一份。
 * </ol>
 *
 * <p>🔴 金额与数量一律 {@link BigDecimal}，**没有 double**：花费 / 销售额直接进财务与归因，
 * 曝光 / 点击 / 订单是跨平台可比的计数。
 *
 * <p>🔴 比率的 {@code null} 与 {@code 0} 含义相反：{@code null} = 不可比（分母为 0），
 * {@code 0} = 可比但真的是零（照抄 14.1.1.1 的 {@code AdRatio} 约定）。
 */
public final class AdCampaignReportModels {
  private AdCampaignReportModels() {}

  // ================================================================ 能力声明

  public record AdReportCapabilityDimension(
      @Schema(description = "维度键") String dimension,
      @Schema(description = "维度说明") String description,
      @Schema(description = "本模块是否已交付") boolean available,
      @Schema(description = "数据来源（表 / 上游模块）") String source,
      @Schema(description = "未交付段的责任模块") String ownerModule,
      @Schema(description = "不交付的影响") String impact) {}

  /**
   * 单个指标的口径定义 —— **表头就是从这里渲染的**。
   *
   * <p>🔴 {@code available=false} 的含义是「<b>没有分母</b>」，不是「这一列是 0」。
   * TACOS 就是这一类：总销售额口径（13.4）未接入，所以不出数值。
   */
  public record AdReportMetricDefinition(
      @Schema(description = "指标键") String metric,
      @Schema(description = "指标显示名") String label,
      @Schema(description = "🔴 是否有分母可算（false = 当前不出数值，不是 0）") boolean available,
      @Schema(description = "🔴 口径定义（写进表头的那句话）") String formula,
      @Schema(description = "数据来源") String source,
      @Schema(description = "未交付段的责任模块") String ownerModule,
      @Schema(description = "🔴 不可用的原因（available=false 时说明为什么没有数值）")
          String unavailableReason) {}

  public record AdCampaignReportCapabilities(
      @Schema(description = "逐维度能力声明；未交付段 available=false")
          List<AdReportCapabilityDimension> dimensions,
      @Schema(description = "🔴 逐指标口径定义（ACOS / ROAS / TACOS 的定义写在这里）")
          List<AdReportMetricDefinition> metricDefinitions,
      @Schema(description = "时间粒度取值") List<String> granularities,
      @Schema(description = "分组维度取值") List<String> dimensions_,
      @Schema(description = "排序方式取值") List<String> sortOptions,
      @Schema(description = "空态原因取值") List<String> emptyReasons,
      @Schema(description = "🔴 ACOS / ROAS / TACOS 口径说明（随报表一起返回）")
          String metricDefinitionNote,
      @Schema(description = "🔴 「数据截至 X 时间」的含义") String dataAsOfNote,
      @Schema(description = "上游依赖说明") String upstreamNote,
      @Schema(description = "下游与边界说明") String boundaryNote) {}

  // ================================================================ 选项

  public record AdCampaignReportOption(
      @Schema(description = "取值") String value, @Schema(description = "显示名") String label) {}

  public record AdCampaignReportOptions(
      @Schema(description = "时间粒度下拉项") List<AdCampaignReportOption> granularities,
      @Schema(description = "分组维度下拉项") List<AdCampaignReportOption> dimensions,
      @Schema(description = "平台下拉项") List<AdCampaignReportOption> platforms,
      @Schema(description = "店铺下拉项") List<AdCampaignReportOption> shops,
      @Schema(description = "归一后类型下拉项") List<AdCampaignReportOption> internalAdTypes,
      @Schema(description = "状态下拉项") List<AdCampaignReportOption> statuses,
      @Schema(description = "排序下拉项") List<AdCampaignReportOption> sorts,
      @Schema(description = "🔴 逐指标口径定义") List<AdReportMetricDefinition> metricDefinitions,
      @Schema(description = "🔴 ACOS / ROAS / TACOS 口径说明") String metricDefinitionNote,
      @Schema(description = "🔴 时间范围与粒度的上限说明") String rangeLimitNote) {}

  // ================================================================ 报表

  /**
   * 报表的一行（一个分组）。
   *
   * <p>🔴 比率列的 {@code null} = 不可比（分母为 0），与 {@code 0}（可比但真的是零）含义相反。
   * <p>🔴 这一行<b>没有</b> TACOS 字段：TACOS 没有分母（见 {@link AdReportMetricDefinition}），
   * 留一个恒为 null 的字段只会让调用方误以为「取到了，只是恰好是空」。
   */
  public record AdCampaignReportRow(
      @Schema(description = "分组维度：CAMPAIGN / PLATFORM / SHOP / AD_TYPE / CURRENCY")
          String dimension,
      @Schema(description = "分组键") String dimensionKey,
      @Schema(description = "分组显示名") String dimensionLabel,
      @Schema(description = "🔴 币种（本仓无汇率源，不同币种不合并）") String currency,
      @Schema(description = "平台名（CAMPAIGN / PLATFORM 分组时有值）") String platformName,
      @Schema(description = "店铺名（CAMPAIGN / SHOP 分组时有值）") String shopName,
      @Schema(description = "平台侧活动 id（仅 CAMPAIGN 分组）") String externalCampaignId,
      @Schema(description = "曝光合计", type = "string") BigDecimal impressions,
      @Schema(description = "点击合计", type = "string") BigDecimal clicks,
      @Schema(description = "订单合计", type = "string") BigDecimal orders,
      @Schema(description = "花费合计", type = "string") BigDecimal spend,
      @Schema(description = "销售额合计", type = "string") BigDecimal sales,
      @Schema(description = "🔴 CTR = Σ点击 / Σ曝光（分母为 0 时为 null=不可比）", type = "string")
          BigDecimal ctr,
      @Schema(description = "🔴 CPC = Σ花费 / Σ点击（分母为 0 时为 null=不可比）", type = "string")
          BigDecimal cpc,
      @Schema(description = "🔴 转化率 = Σ订单 / Σ点击（分母为 0 时为 null=不可比）", type = "string")
          BigDecimal cvr,
      @Schema(description = "🔴 ACOS = Σ花费 / Σ销售额（分母为 0 时为 null=不可比）", type = "string")
          BigDecimal acos,
      @Schema(description = "🔴 ROAS = Σ销售额 / Σ花费（分母为 0 时为 null=不可比）", type = "string")
          BigDecimal roas,
      @Schema(description = "该分组实际统计到的最早日期") LocalDate metricFrom,
      @Schema(description = "该分组实际统计到的最晚日期") LocalDate metricTo) {}

  /**
   * 汇总（**不受分页影响**：统计的是筛选后的全集，不是当前页）。
   *
   * <p>🔴 按币种拆开：没有汇率源 ⇒ 不同币种的花费 / 销售额加在一起就是假总数。
   * <p>🔴 ACOS / ROAS 是「先汇总再相除」：总消耗 / 总销售额，
   * **不是各行比率的平均** —— 后者是数学错误。
   */
  public record AdCampaignReportSummary(
      @Schema(description = "币种") String currency,
      @Schema(description = "该币种下的分组条数") long groupCount,
      @Schema(description = "曝光合计", type = "string") BigDecimal totalImpressions,
      @Schema(description = "点击合计", type = "string") BigDecimal totalClicks,
      @Schema(description = "订单合计", type = "string") BigDecimal totalOrders,
      @Schema(description = "花费合计", type = "string") BigDecimal totalSpend,
      @Schema(description = "销售额合计", type = "string") BigDecimal totalSales,
      @Schema(description = "🔴 CTR = Σ点击 / Σ曝光（分母为 0 时为 null=不可比）", type = "string")
          BigDecimal ctr,
      @Schema(description = "🔴 CPC = Σ花费 / Σ点击（分母为 0 时为 null=不可比）", type = "string")
          BigDecimal cpc,
      @Schema(description = "🔴 转化率 = Σ订单 / Σ点击（分母为 0 时为 null=不可比）", type = "string")
          BigDecimal cvr,
      @Schema(description = "🔴 ACOS = Σ花费 / Σ销售额（分母为 0 时为 null=不可比）", type = "string")
          BigDecimal acos,
      @Schema(description = "🔴 ROAS = Σ销售额 / Σ花费（分母为 0 时为 null=不可比）", type = "string")
          BigDecimal roas,
      @Schema(description = "实际统计到的最早日期") LocalDate metricFrom,
      @Schema(description = "实际统计到的最晚日期") LocalDate metricTo,
      @Schema(description = "🔴 口径说明（ACOS / ROAS / TACOS 的分子分母）") String metricDefinitionNote) {}

  public record AdCampaignReportPage(
      @Schema(description = "当前页数据") List<AdCampaignReportRow> items,
      @Schema(description = "符合筛选条件的分组总条数（不受分页影响）") long total,
      @Schema(description = "页码，从 0 起") int page,
      @Schema(description = "每页条数") int size,
      @Schema(description = "时间粒度：DAY / WEEK / MONTH") String granularity,
      @Schema(description = "分组维度") String dimension,
      @Schema(description = "🔴 筛选后全集的汇总（按币种拆开，不受分页影响）")
          List<AdCampaignReportSummary> summaries,
      @Schema(description = "🔴 数据截至时刻（成功同步的水位；从未同步成功过则为 null）")
          OffsetDateTime dataAsOf,
      @Schema(description = "🔴 「数据截至 X 时间」的口径说明") String dataAsOfNote,
      @Schema(description = "🔴 逐指标口径定义（表头渲染用）")
          List<AdReportMetricDefinition> metricDefinitions,
      @Schema(description = "🔴 空态原因：NO_SYNCED_DATA / NO_METRICS_IN_RANGE / FILTERED_EMPTY / null")
          String emptyReason,
      @Schema(description = "🔴 空态说明文案（三种空的解释各不相同）") String emptyNote,
      @Schema(description = "🔴 ACOS / ROAS / TACOS 口径说明") String metricDefinitionNote) {}

  // ================================================================ 趋势

  public record AdCampaignReportTrendPoint(
      @Schema(description = "该桶的起始日（DAY = 当天；WEEK = 该周周一；MONTH = 当月 1 日）")
          LocalDate periodStart,
      @Schema(description = "曝光合计", type = "string") BigDecimal impressions,
      @Schema(description = "点击合计", type = "string") BigDecimal clicks,
      @Schema(description = "订单合计", type = "string") BigDecimal orders,
      @Schema(description = "花费合计", type = "string") BigDecimal spend,
      @Schema(description = "销售额合计", type = "string") BigDecimal sales,
      @Schema(description = "🔴 CTR（分母为 0 时为 null=不可比）", type = "string") BigDecimal ctr,
      @Schema(description = "🔴 CPC（分母为 0 时为 null=不可比）", type = "string") BigDecimal cpc,
      @Schema(description = "🔴 转化率（分母为 0 时为 null=不可比）", type = "string") BigDecimal cvr,
      @Schema(description = "🔴 ACOS（分母为 0 时为 null=不可比）", type = "string") BigDecimal acos,
      @Schema(description = "🔴 ROAS（分母为 0 时为 null=不可比）", type = "string") BigDecimal roas) {}

  /** 一条趋势线（按币种拆开 —— 没有汇率源就不许把两条线加在一起）。 */
  public record AdCampaignReportTrendSeries(
      @Schema(description = "币种") String currency,
      @Schema(description = "按时间升序的点") List<AdCampaignReportTrendPoint> points) {}

  public record AdCampaignReportTrend(
      @Schema(description = "时间粒度：DAY / WEEK / MONTH") String granularity,
      @Schema(description = "🔴 趋势线（按币种拆开）") List<AdCampaignReportTrendSeries> series,
      @Schema(description = "🔴 数据截至时刻") OffsetDateTime dataAsOf,
      @Schema(description = "🔴 逐指标口径定义") List<AdReportMetricDefinition> metricDefinitions,
      @Schema(description = "🔴 空态原因") String emptyReason,
      @Schema(description = "🔴 空态说明文案") String emptyNote,
      @Schema(description = "🔴 ACOS / ROAS / TACOS 口径说明") String metricDefinitionNote) {}

  // ================================================================ 导出

  public record AdCampaignReportExportRow(
      @Schema(description = "导出留痕 id") UUID id,
      @Schema(description = "发起人（已认证成员）") UUID requestedBy,
      @Schema(description = "时间粒度") String granularity,
      @Schema(description = "分组维度") String dimension,
      @Schema(description = "平台 id 筛选（null = 没筛）") UUID platformId,
      @Schema(description = "店铺筛选（null = 没筛）") String shopCode,
      @Schema(description = "归一后类型筛选（null = 没筛）") String internalAdType,
      @Schema(description = "状态筛选（null = 没筛）") String status,
      @Schema(description = "统计起始日") LocalDate dateFrom,
      @Schema(description = "统计截止日") LocalDate dateTo,
      @Schema(description = "导出的分组行数") int rowCount,
      @Schema(description = "🔴 这次导出对着哪个水位算出来的（成功同步的水位）")
          OffsetDateTime dataAsOf,
      @Schema(description = "🔴 导出当时的三个口径定义") String metricDefinitionNote,
      @Schema(description = "发生时刻") OffsetDateTime createdAt) {}

  public record AdCampaignReportExportPage(
      @Schema(description = "当前页数据") List<AdCampaignReportExportRow> items,
      @Schema(description = "总条数") long total,
      @Schema(description = "页码，从 0 起") int page,
      @Schema(description = "每页条数") int size) {}
}
