package com.starcon.channel;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 14.3.1.3 商品维度报表 的传输模型。
 *
 * <p>蓝图：「商品维度广告效果：SKU/SPU/Listing 维度的广告花费、广告销售额、总销售额、
 * ACOS、TACOS、广告占比利润。输入：商品维度；时间范围；利润数据（13）。
 * 输出：商品广告报表 + 广告销售占比 + 广告后净利。
 * 关系：上游 = 14.3.1.1、13.4.1.2；下游 = 13.4 利润。
 * <b>广告销售占比（广告单/总单）是判断广告是否带来增量还是抢自然单的关键</b>，
 * 只看广告 ROAS 无法回答这个问题。」
 *
 * <p>🔴 这个模型层钉死本模块最容易做丢的五件事：
 *
 * <ol>
 *   <li><b>归因口径写在返回值里</b>：{@code attributionNote} 给出**两级等分规则**的原文
 *       （活动 → Listing → SKU）。规则变了历史报表不可比 —— 蓝图 14.3.2.1 那句
 *       「归因规则必须固定并公示」在这里同样成立：换算就是编造归因，所以**规则必须公示**。
 *   <li><b>「直接归属」与「等分摊销」分开标记</b>：{@code attributionMode} ∈
 *       DIRECT / ALLOCATED_EVENLY / MIXED。只有投放一个商品的活动才拿到平台原值；
 *       投放多个商品的 ⇒ 数字是**分摊值**，界面必须标出来。
 *   <li><b>归不到商品的广告花费不摊给任何商品</b>：汇总里的
 *       {@code unattributedSpend} / {@code unattributedSales} 单列，
 *       摊成 0 就等于说「这些广告没花钱」。
 *   <li><b>比率列的 {@code null} = 不可比（分母为 0），与 {@code 0} 含义相反</b>
 *       （照抄 14.3.1.1 的 {@code AdRatio} 约定）。
 *   <li><b>不跨币种相加</b>：本仓没有汇率源 ⇒ 每一行带自己的币种，汇总按币种拆开。
 * </ol>
 *
 * <p>🔴 金额与数量一律 {@link BigDecimal}，**没有 double**：广告花费与广告销售额直接进财务对账，
 * 总销售额 / 净利来自利润口径；曝光 / 点击 / 订单是跨平台可比的计数。
 */
public final class AdProductReportModels {
  private AdProductReportModels() {}

  // ================================================================ 能力声明

  public record AdProductCapabilityDimension(
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
   * 利润侧指标（总销售额 / TACOS / 广告销售占比 / 广告后净利）依赖 13.4.1.2 的只读接口，
   * 上游读不到时它们是 {@code available=false} 并带一句原因。
   */
  public record AdProductMetricDefinition(
      @Schema(description = "指标键") String metric,
      @Schema(description = "指标显示名") String label,
      @Schema(description = "🔴 当前是否可算（false = 这一格取不到，不是 0）") boolean available,
      @Schema(description = "🔴 口径定义（写进表头的那句话）") String formula,
      @Schema(description = "数据来源") String source,
      @Schema(description = "未交付段的责任模块") String ownerModule,
      @Schema(description = "🔴 取不到的原因（available=false 时说明为什么没有数值）")
          String unavailableReason) {}

  public record AdProductReportCapabilities(
      @Schema(description = "逐维度能力声明；未交付段 available=false")
          List<AdProductCapabilityDimension> dimensions,
      @Schema(description = "🔴 逐指标口径定义（ACOS / TACOS / 广告销售占比的定义写在这里）")
          List<AdProductMetricDefinition> metricDefinitions,
      @Schema(description = "商品维度取值") List<String> dimensions_,
      @Schema(description = "排序方式取值") List<String> sortOptions,
      @Schema(description = "空态原因取值") List<String> emptyReasons,
      @Schema(description = "🔴 归因口径原文（两级等分规则；规则变了历史报表不可比）")
          String attributionNote,
      @Schema(description = "🔴 逐指标口径说明（随报表一起返回）") String metricDefinitionNote,
      @Schema(description = "🔴 「数据截至 X 时间」的口径说明") String dataAsOfNote,
      @Schema(description = "上游依赖说明") String upstreamNote,
      @Schema(description = "下游与边界说明") String boundaryNote) {}

  // ================================================================ 选项

  public record AdProductReportOption(
      @Schema(description = "取值") String value, @Schema(description = "显示名") String label) {}

  public record AdProductReportOptions(
      @Schema(description = "商品维度下拉项") List<AdProductReportOption> dimensions,
      @Schema(description = "平台下拉项") List<AdProductReportOption> platforms,
      @Schema(description = "店铺下拉项") List<AdProductReportOption> shops,
      @Schema(description = "归一后类型下拉项") List<AdProductReportOption> internalAdTypes,
      @Schema(description = "状态下拉项") List<AdProductReportOption> statuses,
      @Schema(description = "排序下拉项") List<AdProductReportOption> sorts,
      @Schema(description = "🔴 逐指标口径定义") List<AdProductMetricDefinition> metricDefinitions,
      @Schema(description = "🔴 逐指标口径说明") String metricDefinitionNote,
      @Schema(description = "🔴 归因口径原文") String attributionNote,
      @Schema(description = "🔴 时间范围与粒度的上限说明") String rangeLimitNote) {}

  // ================================================================ 报表

  /**
   * 商品广告报表的一行（一个商品分组 × 一个币种）。
   *
   * <p>🔴 比率列的 {@code null} = 不可比（分母为 0），与 {@code 0}（可比但真的是零）含义相反。
   * <p>🔴 利润侧的列（总销售额 / TACOS / 广告销售占比 / 总净利 / 广告后净利 / 广告占比利润）
   * 在上游 13.4.1.2 读不到时是 {@code null}，响应里另有
   * {@code profitUnavailableReason} 说明原因 —— **不是 0**。
   */
  public record AdProductReportRow(
      @Schema(description = "商品维度：LISTING / SKU / SPU") String dimension,
      @Schema(description = "分组键（LISTING = listing id；SKU = 内部 SKU id；SPU = SPU 编码）")
          String dimensionKey,
      @Schema(description = "分组显示名") String dimensionLabel,
      @Schema(description = "🔴 币种（本仓无汇率源，不同币种不合并）") String currency,
      @Schema(description = "平台名") String platformName,
      @Schema(description = "店铺名") String shopName,
      @Schema(description = "平台侧商品 id（原文照抄，不折叠大小写）") String externalItemId,
      @Schema(description = "listing id（LISTING 维度有值）") String listingId,
      @Schema(description = "listing 标题") String listingTitle,
      @Schema(description = "内部 SKU id（SKU / SPU 维度有值）") String skuId,
      @Schema(description = "内部 SKU 编码（SKU / SPU 维度有值；catalog 读不到时为空串）")
          String skuCode,
      @Schema(description = "SPU 编码（SPU 维度有值；catalog 读不到时为空串）") String spuCode,
      @Schema(description = "曝光合计", type = "string") BigDecimal impressions,
      @Schema(description = "点击合计", type = "string") BigDecimal clicks,
      @Schema(description = "广告带来的订单合计", type = "string") BigDecimal orders,
      @Schema(description = "广告花费合计", type = "string") BigDecimal spend,
      @Schema(description = "广告销售额合计", type = "string") BigDecimal sales,
      @Schema(description = "🔴 CTR = Σ点击 / Σ曝光（分母为 0 时为 null=不可比）", type = "string")
          BigDecimal ctr,
      @Schema(description = "🔴 CPC = Σ花费 / Σ点击（分母为 0 时为 null=不可比）", type = "string")
          BigDecimal cpc,
      @Schema(description = "🔴 转化率 = Σ订单 / Σ点击（分母为 0 时为 null=不可比）", type = "string")
          BigDecimal cvr,
      @Schema(description = "🔴 ACOS = Σ花费 / Σ广告销售额（分母为 0 时为 null=不可比）", type = "string")
          BigDecimal acos,
      @Schema(description = "🔴 ROAS = Σ广告销售额 / Σ广告花费（分母为 0 时为 null=不可比）", type = "string")
          BigDecimal roas,
      @Schema(description = "🔴 总销售额（13.4.1.2 利润口径；上游读不到时为 null）", type = "string")
          BigDecimal totalSales,
      @Schema(description = "🔴 TACOS = Σ广告花费 / 总销售额（分母为 0 时为 null=不可比）", type = "string")
          BigDecimal tacos,
      @Schema(description = "🔴 广告销售占比 = 广告销售额 / 总销售额（分母为 0 时为 null=不可比）", type = "string")
          BigDecimal adSalesShare,
      @Schema(description = "🔴 总净利（13.4.1.2 利润口径；上游读不到时为 null）", type = "string")
          BigDecimal netProfit,
      @Schema(description = "🔴 广告后净利 = 总净利 − 广告花费（任一项缺失时为 null）", type = "string")
          BigDecimal adAfterNetProfit,
      @Schema(description = "🔴 广告占比利润 = 广告花费 / 总净利（分母为 0 或负时为 null=不可比）", type = "string")
          BigDecimal adProfitShare,
      @Schema(description = "🔴 归因方式：DIRECT / ALLOCATED_EVENLY / MIXED") String attributionMode,
      @Schema(description = "🔴 归因方式的中文说明") String attributionLabel,
      @Schema(description = "贡献到本行的投放商品数（SKU / SPU 维度有值）") int sourceListingCount,
      @Schema(description = "🔴 这一行的利润列为空时的原因（取到了就是空串）") String profitNote,
      @Schema(description = "该分组实际统计到的最早日期") LocalDate metricFrom,
      @Schema(description = "该分组实际统计到的最晚日期") LocalDate metricTo) {}

  /**
   * 汇总（**不受分页影响**：统计的是筛选后的全集，不是当前页）。
   *
   * <p>🔴 按币种拆开：没有汇率源 ⇒ 不同币种的花费 / 销售额加在一起就是假总数。
   * <p>🔴 ACOS / TACOS 是「先汇总再相除」：总消耗 / 总销售额，
   * <b>不是各行比率的平均</b> —— 后者是数学错误。
   * <p>🔴 {@code unattributedSpend} / {@code unattributedSales} 是
   * <b>归不到任何商品</b>的广告花费与销售额（活动没登记投放商品）：
   * 它们单列，**不摊给任何商品，也不摊成 0**。
   */
  public record AdProductReportSummary(
      @Schema(description = "币种") String currency,
      @Schema(description = "该币种下的分组条数") long groupCount,
      @Schema(description = "曝光合计", type = "string") BigDecimal totalImpressions,
      @Schema(description = "点击合计", type = "string") BigDecimal totalClicks,
      @Schema(description = "广告订单合计", type = "string") BigDecimal totalOrders,
      @Schema(description = "广告花费合计", type = "string") BigDecimal totalSpend,
      @Schema(description = "广告销售额合计", type = "string") BigDecimal totalSales,
      @Schema(description = "🔴 CTR = Σ点击 / Σ曝光（分母为 0 时为 null=不可比）", type = "string")
          BigDecimal ctr,
      @Schema(description = "🔴 CPC = Σ花费 / Σ点击（分母为 0 时为 null=不可比）", type = "string")
          BigDecimal cpc,
      @Schema(description = "🔴 转化率 = Σ订单 / Σ点击（分母为 0 时为 null=不可比）", type = "string")
          BigDecimal cvr,
      @Schema(description = "🔴 ACOS = Σ花费 / Σ广告销售额（分母为 0 时为 null=不可比）", type = "string")
          BigDecimal acos,
      @Schema(description = "🔴 ROAS = Σ广告销售额 / Σ广告花费（分母为 0 时为 null=不可比）", type = "string")
          BigDecimal roas,
      @Schema(description = "🔴 总销售额（13.4.1.2 利润口径；上游读不到时为 null）", type = "string")
          BigDecimal totalRevenue,
      @Schema(description = "🔴 TACOS = Σ广告花费 / 总销售额（分母为 0 时为 null=不可比）", type = "string")
          BigDecimal tacos,
      @Schema(description = "🔴 广告销售占比 = 广告销售额 / 总销售额（分母为 0 时为 null=不可比）", type = "string")
          BigDecimal adSalesShare,
      @Schema(description = "🔴 总净利（上游读不到时为 null）", type = "string") BigDecimal totalNetProfit,
      @Schema(description = "🔴 广告后净利 = 总净利 − 广告花费（任一项缺失时为 null）", type = "string")
          BigDecimal adAfterNetProfit,
      @Schema(description = "🔴 广告占比利润 = 广告花费 / 总净利（分母为 0 或负时为 null=不可比）", type = "string")
          BigDecimal adProfitShare,
      @Schema(description = "🔴 与利润侧同一范围的广告花费（TACOS / 广告销售占比的分子）", type = "string")
          BigDecimal scopedSpend,
      @Schema(description = "🔴 与利润侧同一范围的广告销售额（广告销售占比的分子）", type = "string")
          BigDecimal scopedSales,
      @Schema(description = "🔴 与利润侧同一范围的分组条数") long scopedGroupCount,
      @Schema(description = "🔴 「同一范围」是什么意思（口径说明）") String scopedScopeNote,
      @Schema(description = "🔴 范围内广告花费总额（= 行合计 + 未归属）", type = "string")
          BigDecimal campaignTotalSpend,
      @Schema(description = "🔴 范围内广告销售额总额（= 行合计 + 未归属）", type = "string")
          BigDecimal campaignTotalSales,
      @Schema(description = "🔴 归不到当前维度的广告花费（活动没登记投放商品 / 商品没映射 SKU / 候选集被截断）", type = "string")
          BigDecimal unattributedSpend,
      @Schema(description = "🔴 归不到任何商品的广告销售额", type = "string")
          BigDecimal unattributedSales,
      @Schema(description = "实际统计到的最早日期") LocalDate metricFrom,
      @Schema(description = "实际统计到的最晚日期") LocalDate metricTo,
      @Schema(description = "🔴 逐指标口径说明") String metricDefinitionNote,
      @Schema(description = "🔴 归因口径原文") String attributionNote) {}

  public record AdProductReportPage(
      @Schema(description = "当前页数据") List<AdProductReportRow> items,
      @Schema(description = "符合筛选条件的分组总条数（不受分页影响）") long total,
      @Schema(description = "页码，从 0 起") int page,
      @Schema(description = "每页条数") int size,
      @Schema(description = "商品维度：LISTING / SKU / SPU") String dimension,
      @Schema(description = "🔴 筛选后全集的汇总（按币种拆开，不受分页影响）")
          List<AdProductReportSummary> summaries,
      @Schema(description = "🔴 数据截至时刻（成功同步的水位；从未同步成功过则为 null）")
          OffsetDateTime dataAsOf,
      @Schema(description = "🔴 「数据截至 X 时间」的口径说明") String dataAsOfNote,
      @Schema(description = "🔴 逐指标口径定义（表头渲染用）")
          List<AdProductMetricDefinition> metricDefinitions,
      @Schema(description = "🔴 空态原因：NO_SYNCED_DATA / NO_METRICS_IN_RANGE / FILTERED_EMPTY / NO_PRODUCT_BINDING / null")
          String emptyReason,
      @Schema(description = "🔴 空态说明文案（几种空的解释各不相同）") String emptyNote,
      @Schema(description = "🔴 逐指标口径说明") String metricDefinitionNote,
      @Schema(description = "🔴 归因口径原文") String attributionNote,
      @Schema(description = "🔴 利润上游（13.4.1.2）这次是否读到了") boolean profitAvailable,
      @Schema(description = "🔴 利润上游读不到时的原因（读到了则为空串）")
          String profitUnavailableReason,
      @Schema(description = "🔴 商品主数据上游（catalog）这次是否读到了") boolean catalogAvailable,
      @Schema(description = "🔴 商品主数据上游读不到时的原因（读到了则为空串）")
          String catalogUnavailableReason,
      @Schema(description = "🔴 候选集是否被截断（SPU 维度会先取 SKU 候选集再归并）")
          boolean candidateTruncated,
      @Schema(description = "🔴 截断说明（未截断时为空串）") String candidateTruncationNote) {}

  // ================================================================ 导出

  public record AdProductReportExportRow(
      @Schema(description = "导出留痕 id") UUID id,
      @Schema(description = "发起人（已认证成员）") UUID requestedBy,
      @Schema(description = "商品维度") String dimension,
      @Schema(description = "平台 id 筛选（null = 没筛）") UUID platformId,
      @Schema(description = "店铺筛选（null = 没筛）") String shopCode,
      @Schema(description = "归一后类型筛选（null = 没筛）") String internalAdType,
      @Schema(description = "状态筛选（null = 没筛）") String status,
      @Schema(description = "统计起始日") LocalDate dateFrom,
      @Schema(description = "统计截止日") LocalDate dateTo,
      @Schema(description = "导出的分组行数") int rowCount,
      @Schema(description = "🔴 这次导出对着哪个水位算出来的（成功同步的水位）")
          OffsetDateTime dataAsOf,
      @Schema(description = "🔴 导出当时的逐指标口径定义") String metricDefinitionNote,
      @Schema(description = "🔴 导出当时的归因口径原文") String attributionNote,
      @Schema(description = "发生时刻") OffsetDateTime createdAt) {}

  public record AdProductReportExportPage(
      @Schema(description = "当前页数据") List<AdProductReportExportRow> items,
      @Schema(description = "总条数") long total,
      @Schema(description = "页码，从 0 起") int page,
      @Schema(description = "每页条数") int size) {}
}
