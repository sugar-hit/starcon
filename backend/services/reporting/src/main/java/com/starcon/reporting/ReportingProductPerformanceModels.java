package com.starcon.reporting;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 13.3.1.1 产品表现分析的**出入参模型**。
 *
 * <p>⚠️ **「算不出来」与「等于 0」必须分开**（与 13.1.1.1 / 13.2.2.2 同一纪律）：
 * 分母为 0、事实不存在、上游未交付，三种情况一律返回 {@code null} 或
 * {@code NO_DATA} / {@code NOT_COMPARABLE} / {@code NOT_DELIVERED}，**不用 0 冒充**。
 *
 * <p>⚠️ **不跨币种相加**：金额按币种分别报。**只有件数与次数**（与货币无关的量）
 * 才给出一个跨币种合计，它的 {@code currency} 是**空串** —— 空串 = 与币种无关，
 * 不是「币种未知」。
 *
 * <p>⚠️ **名字必须带 {@code Performance} 前缀**：13.1.1.1 的 {@code ReportingOverviewModels}、
 * 13.2.2.2 的 {@code ReportingProductSalesModels} 里已经有 {@code ProductMetricDefinitionView}、
 * {@code CurrencyAmount} 等同名记录。SpringDoc 用**简单类名**当 schema 名，同名时只能加序号消歧
 * （序号取决于**发现顺序**，换个服务先启动就换一个名，生成出来的契约跟着漂）。
 */
public final class ReportingProductPerformanceModels {

  private ReportingProductPerformanceModels() {}

  // ==================================================================
  // 指标
  // ==================================================================

  /**
   * 一个指标在**当前窗口**与**基线窗口**两侧的取值。
   *
   * <p>🔴 **环比由服务端算**（{@code change} / {@code changeRate} 都是 BigDecimal）——
   * 前端绝不做业务算术。
   *
   * @param currency 币种。**空串 = 与币种无关**（件数 / 次数的跨币种合计）
   * @param value 当前窗口取值。null = **算不出来**（不是 0）
   * @param baselineValue 基线窗口取值。null = 基线**没有事实**（不是 0）
   * @param change 变化量 = value − baselineValue。任一侧为 null ⇒ null
   * @param changeRate 变化率 = change ÷ |baselineValue|。**基线为 0 时是 null**
   *     （「上期是 0」与「本期没变」是两件事）
   * @param availability AVAILABLE / NO_DATA / NOT_COMPARABLE / NOT_DELIVERED
   * @param sourceNote 数据来源标注（如「平台报表口径」）
   */
  public record PerformanceMetric(
      String metricCode,
      String displayName,
      String unit,
      String currency,
      @Schema(nullable = true) BigDecimal value,
      @Schema(nullable = true) BigDecimal baselineValue,
      @Schema(nullable = true) BigDecimal change,
      @Schema(nullable = true) BigDecimal changeRate,
      String availability,
      String availabilityNote,
      String sourceNote) {}

  /** 一个币种的金额。 */
  public record PerformanceCurrencyValue(String currency, BigDecimal amount) {}

  /** 跟卖 / 竞品的一行（**参考指标**，不进自有商品模型）。 */
  public record PerformanceCompetitorRow(
      String sellerId,
      String sellerName,
      /** 跟卖者性质：OWN_SHOP 己方其他店铺 / EXTERNAL 外部跟卖者。 */
      String sellerKind,
      @Schema(nullable = true) BigDecimal hijackerPrice,
      @Schema(nullable = true) BigDecimal referencePrice,
      /** 价格差 = 自家参考价 − 跟卖价。 */
      @Schema(nullable = true) BigDecimal priceGap,
      /** 价格差比例 = 价格差 ÷ 自家参考价（分母为 0 ⇒ null）。 */
      @Schema(nullable = true) BigDecimal priceGapRatio,
      String currency,
      @Schema(nullable = true) OffsetDateTime observedAt,
      String note) {}

  /** 广告活动的一行（**活动级**数据，不是这个商品分摊到的）。 */
  public record PerformanceAdCampaignRow(
      String campaignId,
      String campaignLabel,
      String platformName,
      String shopName,
      @Schema(nullable = true) BigDecimal spend,
      @Schema(nullable = true) BigDecimal impressions,
      @Schema(nullable = true) BigDecimal clicks,
      @Schema(nullable = true) BigDecimal orders,
      @Schema(nullable = true) BigDecimal sales,
      String currency,
      String note) {}

  /** 页面上的一段（蓝图职责句里的十个指标各一段 + 排名分位）。 */
  public record PerformanceSection(
      String sectionCode,
      String title,
      /** 这一段的口径说明（页面上原样显示）。 */
      String description,
      /** AVAILABLE / NO_DATA / NOT_COMPARABLE / NOT_DELIVERED */
      String availability,
      String availabilityNote,
      List<PerformanceMetric> metrics,
      /** 只有「竞品对比」段有值。 */
      List<PerformanceCompetitorRow> competitors,
      /** 只有「广告表现」段有值。 */
      List<PerformanceAdCampaignRow> adCampaigns) {}

  // ==================================================================
  // 趋势
  // ==================================================================

  /** 趋势上的一个点（**一天一行**）。 */
  public record PerformanceTrendPoint(
      LocalDate date,
      /** 当日销量（跨币种合计的件数）。 */
      @Schema(nullable = true) BigDecimal salesQuantity,
      /** 当日退货件数。 */
      @Schema(nullable = true) BigDecimal returnedQuantity,
      @Schema(nullable = true) BigDecimal impressions,
      @Schema(nullable = true) BigDecimal clicks,
      @Schema(nullable = true) BigDecimal sessions,
      /** 当日成交金额**按币种分别列出**。 */
      List<PerformanceCurrencyValue> salesAmounts,
      /** 当日出库成本**按币种分别列出**。 */
      List<PerformanceCurrencyValue> cogsAmounts,
      /** 当日毛利额**按币种分别列出**。 */
      List<PerformanceCurrencyValue> grossProfitAmounts) {}

  // ==================================================================
  // 排名分位
  // ==================================================================

  /**
   * 同层级排名分位。
   *
   * @param rank 排名（1 = 最靠前）
   * @param total 同层级可比样本数
   * @param percentile 分位（百分数，越大越好）
   * @param peerMedian 同层级中位数（可对比的锚）
   * @param peerScope 排名范围的口径说明
   */
  public record PerformanceRanking(
      String rankMetricCode,
      String rankMetricDisplayName,
      int rank,
      int total,
      @Schema(nullable = true) BigDecimal percentile,
      @Schema(nullable = true) BigDecimal peerMedian,
      @Schema(nullable = true) BigDecimal peerAverage,
      String peerScope,
      String availability,
      String availabilityNote) {}

  // ==================================================================
  // 口径目录
  // ==================================================================

  /** 一个指标的口径定义（口径唯一的展示形态）。 */
  public record PerformanceMetricDefinitionView(
      String metricCode,
      String displayName,
      String unit,
      String aggregation,
      @Schema(nullable = true) String numeratorMetricCode,
      @Schema(nullable = true) String denominatorMetricCode,
      String currencyScope,
      boolean currencyAware,
      /** 🔴 本轮是否可用（false ⇒ 上游未交付，页面显示「归谁管」）。 */
      boolean available,
      /** 不可用时的归属模块。 */
      String ownerModule,
      String description,
      String availabilityNote) {}

  // ==================================================================
  // 主响应
  // ==================================================================

  /**
   * 产品表现分析的完整结果（**一次性拉齐**：一页滚动看完，不用来回跳）。
   *
   * @param baselineType 基线类型：PREVIOUS_PERIOD（上一个等长周期）
   * @param dimensionType 维度层：SKU / SPU / LISTING
   * @param skuIds 这个维度覆盖的 SKU（SKU 层 = 自己；SPU = 名下变体；LISTING = 挂载的 SKU）
   * @param listingIds 这个维度覆盖的 Listing
   */
  public record ProductPerformanceResponse(
      LocalDate from,
      LocalDate to,
      LocalDate baselineFrom,
      LocalDate baselineTo,
      String baselineType,
      String dimensionType,
      String dimensionId,
      String dimensionKey,
      String dimensionName,
      List<String> platformCodes,
      List<String> listingIds,
      List<String> skuIds,
      int skuCount,
      int listingCount,
      List<PerformanceSection> sections,
      List<PerformanceTrendPoint> trend,
      PerformanceRanking ranking,
      List<PerformanceMetricDefinitionView> metricDefinitions,
      List<String> caliberNotes,
      List<String> timezones,
      /** 数据更新时间（最近一次投影落库时刻）。**空表时是 null**。 */
      @Schema(nullable = true) OffsetDateTime dataUpdatedAt,
      String dataUpdatedNote,
      String status,
      String statusNote,
      @Schema(nullable = true) String emptyReason,
      String emptyNote) {}

  // ==================================================================
  // 能力声明
  // ==================================================================

  /**
   * 能力声明。
   *
   * @param notDelivered **确实拿不到**的部分（每条都写清原因与归属模块）
   */
  public record ProductPerformanceCapabilities(
      boolean upstreamConnected,
      boolean analysisAvailable,
      boolean dataUpdatedAtVisible,
      List<String> upstreamModules,
      List<String> notDelivered,
      List<PerformanceRuleNote> rules) {}

  /** 一条规则 / 口径说明。 */
  public record PerformanceRuleNote(String ruleCode, String description) {}

  // ==================================================================
  // 刷新
  // ==================================================================

  /** 一路刷新结果。 */
  public record ProductPerformanceRefreshResult(
      String source,
      /** 拉到的上游行数。 */
      int fetched,
      /** 落库的事实 / 快照行数。 */
      int projected,
      /** 结论：SUCCEEDED / FAILED / TRUNCATED。 */
      String status,
      String note) {}
}
