package com.starcon.platform;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 18.4.2.2「选品与需求预测」的传输模型。
 *
 * <p>蓝图 18-integration-and-automation.md 原文：
 *
 * <blockquote>
 *
 * 职责：AI 预测能力：销量预测（含促销与大促影响）、补货量建议、价格弹性与调价建议、爆品识别与机会品发现。
 * 输入：历史销量、库存、在途、价格、促销日历；预测参数。 输出：预测结果 + 置信区间 + 关键影响因素说明。
 * 关系：上游 = 13 分析、6 采购、7 库存；下游 = 6.2.1.2 补货建议、5.4 调价。
 * <b>预测必须给置信区间和影响因素</b>，单点数字会让用户过度信任；建议只作为参考输入，不自动执行。
 *
 * </blockquote>
 *
 * <p><b>命名。</b>统一用 {@code AiForecast*} 前缀。SpringDoc 用**简单类名**当 schema 名，
 * 所以每个嵌套记录都带前缀 —— 否则「Factor」「Suggestion」这种通用名会与别的服务的
 * 同名 schema 撞车（契约生成器的撞名闸会直接中止）。
 *
 * <p><b>🔴 本模块一个 {@code double} 都没有</b>：数量与金额是 {@link BigDecimal}
 * （库里 {@code NUMERIC(19,6)}），比率 {@code NUMERIC(9,6)}，置信水平 {@code NUMERIC(5,4)}。
 *
 * <p><b>🔴 每条预测都带区间与影响因素</b>：{@link AiForecastResultRow} 的
 * {@code point / lower / upper} 三件齐全（**单点数字 = 未完成**），
 * {@link AiForecastFactorRow} 逐条报出因素名、方向、取值与口径说明。
 *
 * <p><b>🔴 建议不自动执行</b>：{@link AiForecastSuggestionRow} 的状态只有
 * {@code SUGGESTED / ADOPTED / REJECTED} —— <b>没有 EXECUTED</b>，
 * 因为执行补货单（6 采购）与改价（5 定价）是别的模块的写入所有权。
 */
public final class AiForecastModels {

  private AiForecastModels() {}

  // ================================================================ 能力声明

  /**
   * 一项能力（或一项「还没接通」的能力）。
   *
   * @param available 该能力**现在**能不能用（不是「将来会有」）
   * @param evidence 凭什么这么判（端点 / 表 / 算法口径的具体名字）
   * @param ownerModule 还没接通的能力，**缺在哪个模块**
   */
  public record AiForecastCapability(
      String moduleId,
      String name,
      boolean available,
      String evidence,
      String ownerModule,
      String note) {}

  /** 一个「还没交付」的下游 / 兄弟能力。 */
  public record AiForecastGap(
      String moduleId, String name, String service, boolean available, String relation, String note) {}

  /**
   * 一条**算法口径**（页面直接显示，不自己编）。
   *
   * @param code 算法码
   * @param params 算法参数（键 = 参数名，值 = 参数取值说明）
   */
  public record AiForecastAlgorithm(
      String code, String name, String description, List<AiForecastParam> params) {}

  /** 一个算法参数。 */
  public record AiForecastParam(String name, String value, String description) {}

  /** 一条判定规则说明（口径写清楚，页面上照着显示）。 */
  public record AiForecastRuleNote(String code, String note) {}

  /** 一个阈值（爆品 / 机会品判定用）。 */
  public record AiForecastThreshold(
      String code, String name, BigDecimal value, String unit, String description) {}

  /**
   * 一个**数据来源**。
   *
   * @param connected 这一格**现在**读不读得到（读不到就是 false，**不假装有数据**）
   * @param ownerModule 读不到时，缺在哪个模块
   */
  public record AiForecastSource(
      String code,
      String name,
      String service,
      boolean connected,
      String endpoint,
      String ownerModule,
      String note) {}

  /** 能力声明总览。 */
  public record AiForecastCapabilities(
      List<AiForecastCapability> capabilities,
      List<AiForecastGap> gaps,
      List<AiForecastAlgorithm> algorithms,
      List<AiForecastRuleNote> rules,
      List<AiForecastThreshold> thresholds,
      List<AiForecastSource> sources) {}

  /** 概览：几个数。 */
  public record AiForecastOverview(
      long runs,
      long forecasts,
      long suggestions,
      long adoptedSuggestions,
      long rejectedSuggestions,
      long promotions,
      long historyRows,
      long historySkus,
      OffsetDateTime generatedAt) {}

  // ================================================================ 促销日历

  /** 促销日历一行。 */
  public record AiForecastPromotionRow(
      UUID id,
      String name,
      String promotionType,
      String promotionTypeLabel,
      String scopeType,
      String scopeValue,
      OffsetDateTime startsAt,
      OffsetDateTime endsAt,
      @Schema(nullable = true) BigDecimal discountRate,
      BigDecimal expectedLift,
      String source,
      String sourceRef,
      String note,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 促销日历写入（新建或改；id 为空即新建）。 */
  public record AiForecastPromotionInput(
      String name,
      String promotionType,
      String scopeType,
      String scopeValue,
      OffsetDateTime startsAt,
      OffsetDateTime endsAt,
      @Schema(nullable = true) BigDecimal discountRate,
      BigDecimal expectedLift,
      String source,
      String sourceRef,
      String note) {}

  /** 促销日历列表（真分页）。 */
  public record AiForecastPromotionPage(
      List<AiForecastPromotionRow> items, int page, int size, long total, long totalPages) {}

  // ================================================================ 需求历史

  /** 需求历史台账一行。 */
  public record AiForecastHistoryRow(
      UUID id,
      String skuCode,
      String skuName,
      LocalDate businessDate,
      BigDecimal units,
      @Schema(nullable = true) BigDecimal unitPrice,
      @Schema(nullable = true) BigDecimal revenue,
      @Schema(nullable = true) String currency,
      String channelCode,
      String source,
      String sourceRef,
      OffsetDateTime recordedAt) {}

  /** 需求历史写入（**人工录入**；id 为空即新建）。 */
  public record AiForecastHistoryInput(
      String skuCode,
      String skuName,
      LocalDate businessDate,
      BigDecimal units,
      @Schema(nullable = true) BigDecimal unitPrice,
      @Schema(nullable = true) BigDecimal revenue,
      @Schema(nullable = true) String currency,
      String channelCode,
      String sourceRef,
      String note) {}

  /** 需求历史列表（真分页）。 */
  public record AiForecastHistoryPage(
      List<AiForecastHistoryRow> items, int page, int size, long total, long totalPages) {}

  /**
   * 一次**上游同步**的结论。
   *
   * @param connected 上游读不读得到（读不到 = false，**一条数据都不写**）
   * @param rowsWritten 写进台账的行数
   * @param rowsSkipped 因为当天已有记录而跳过的行数（**不重复计数**）
   */
  public record AiForecastHistorySyncResult(
      boolean connected,
      String source,
      String sourceEndpoint,
      LocalDate from,
      LocalDate to,
      int rowsWritten,
      int rowsSkipped,
      int seriesRead,
      String note,
      String ownerModule) {}

  // ================================================================ 预测运行

  /** 预测参数（发起一次运行时传入）。 */
  public record AiForecastRunInput(
      String algorithm,
      Integer maWindowDays,
      BigDecimal smoothingAlpha,
      Integer windowDays,
      Integer horizonDays,
      BigDecimal confidenceLevel,
      String note) {}

  /** 一次运行一行。 */
  public record AiForecastRunRow(
      UUID id,
      String runNo,
      String algorithm,
      int maWindowDays,
      BigDecimal smoothingAlpha,
      int windowDays,
      int horizonDays,
      BigDecimal confidenceLevel,
      BigDecimal zValue,
      String historySource,
      String status,
      int evaluatedSkuCount,
      int forecastSkuCount,
      int skippedSkuCount,
      String skippedReason,
      boolean inventoryConnected,
      boolean inTransitConnected,
      boolean purchaseConnected,
      String note,
      OffsetDateTime startedAt,
      OffsetDateTime finishedAt) {}

  /** 运行列表（真分页）。 */
  public record AiForecastRunPage(
      List<AiForecastRunRow> items, int page, int size, long total, long totalPages) {}

  /** 运行详情（运行 + 每个 SKU 的结果 + 影响因素）。 */
  public record AiForecastRunDetail(
      AiForecastRunRow run, List<AiForecastResultDetail> results) {}

  // ================================================================ 预测结果

  /**
   * 一条预测结果。
   *
   * <p>🔴 {@code point / lower / upper} **三件齐全**：蓝图加粗句「预测必须给置信区间」。
   * 单点数字 = 未完成。
   */
  public record AiForecastResultRow(
      UUID id,
      UUID runId,
      String skuCode,
      String skuName,
      String algorithm,
      int maWindowDays,
      BigDecimal smoothingAlpha,
      int windowDays,
      int horizonDays,
      String historySource,
      int sampleDays,
      BigDecimal coverageRate,
      BigDecimal baselineDaily,
      BigDecimal promoFactor,
      BigDecimal point,
      BigDecimal lower,
      BigDecimal upper,
      BigDecimal dailyPoint,
      BigDecimal sigma,
      BigDecimal zValue,
      BigDecimal confidenceLevel,
      @Schema(nullable = true) BigDecimal inventoryQty,
      @Schema(nullable = true) BigDecimal inTransitQty,
      @Schema(nullable = true) BigDecimal purchasePendingQty,
      @Schema(nullable = true) BigDecimal unitPrice,
      @Schema(nullable = true) String currency,
      OffsetDateTime createdAt) {}

  /**
   * 一条**影响因素**。
   *
   * @param direction UP（把预测往上推）/ DOWN（往下压）/ NEUTRAL（说明性）
   * @param impact 该因素对预测期总需求的绝对影响量（件）
   * @param explanation **口径说明**：这个数怎么来的、事实来自哪里
   */
  public record AiForecastFactorRow(
      UUID id,
      UUID resultId,
      String factorCode,
      String factorLabel,
      String direction,
      @Schema(nullable = true) BigDecimal value,
      String valueText,
      BigDecimal impact,
      String explanation,
      String source,
      OffsetDateTime createdAt) {}

  /** 预测详情（结果 + 影响因素）。 */
  public record AiForecastResultDetail(AiForecastResultRow result, List<AiForecastFactorRow> factors) {}

  /** 预测结果列表（真分页）。 */
  public record AiForecastResultPage(
      List<AiForecastResultRow> items, int page, int size, long total, long totalPages) {}

  // ================================================================ 建议

  /**
   * 一条建议（补货 / 调价）。
   *
   * <p>🔴 {@code status} 只有 {@code SUGGESTED / ADOPTED / REJECTED} ——
   * <b>没有 EXECUTED</b>：本模块**不执行**任何下游动作。
   */
  public record AiForecastSuggestionRow(
      UUID id,
      String suggestionType,
      UUID runId,
      UUID resultId,
      String skuCode,
      String skuName,
      @Schema(nullable = true) BigDecimal currentQty,
      @Schema(nullable = true) BigDecimal inTransitQty,
      @Schema(nullable = true) BigDecimal purchasePendingQty,
      @Schema(nullable = true) BigDecimal suggestedQty,
      @Schema(nullable = true) BigDecimal demandPoint,
      @Schema(nullable = true) BigDecimal demandLower,
      @Schema(nullable = true) BigDecimal demandUpper,
      @Schema(nullable = true) BigDecimal currentPrice,
      @Schema(nullable = true) BigDecimal suggestedPrice,
      @Schema(nullable = true) BigDecimal priceDeltaRate,
      @Schema(nullable = true) BigDecimal expectedUnitsDeltaRate,
      @Schema(nullable = true) BigDecimal elasticity,
      @Schema(nullable = true) String currency,
      String status,
      String basis,
      @Schema(nullable = true) UUID decidedBy,
      @Schema(nullable = true) OffsetDateTime decidedAt,
      String decisionNote,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 建议列表（真分页）。 */
  public record AiForecastSuggestionPage(
      List<AiForecastSuggestionRow> items, int page, int size, long total, long totalPages) {}

  /** 人工决策（采纳 / 驳回）时写的一句理由。 */
  public record AiForecastSuggestionDecision(String note) {}

  // ================================================================ 价格弹性

  /**
   * 一个 SKU 的价格弹性。
   *
   * @param available 弹性**算得出来**吗（算不出来 = false，**不给假的 0**）
   * @param elasticity 弹性值（对数比值法；算不出来时为 null）
   * @param samplePairs 用于估计的样本对数
   * @param reason 算不出来 / 给不出建议时的原因（页面直接显示）
   */
  public record AiForecastElasticityRow(
      String skuCode,
      String skuName,
      boolean available,
      @Schema(nullable = true) BigDecimal elasticity,
      int samplePairs,
      int windowDays,
      @Schema(nullable = true) BigDecimal currentPrice,
      @Schema(nullable = true) String currency,
      String method,
      String reason,
      String basis) {}

  /** 价格弹性视图（**含没能算出来的 SKU 与原因**）。 */
  public record AiForecastPriceElasticityView(
      List<AiForecastElasticityRow> items,
      String method,
      int windowDays,
      BigDecimal adjustmentThreshold,
      String note) {}

  // ================================================================ 爆品 / 机会品

  /**
   * 一个爆品判定结果。
   *
   * @param detected 判成爆品了吗
   * @param reason 没判成 / 判不出来的原因（页面直接显示）
   */
  public record AiForecastHotItemRow(
      String skuCode,
      String skuName,
      boolean detected,
      BigDecimal windowUnits,
      @Schema(nullable = true) BigDecimal previousWindowUnits,
      @Schema(nullable = true) BigDecimal growthRate,
      int windowDays,
      int activeDays,
      BigDecimal unitsThreshold,
      BigDecimal growthThreshold,
      String reason,
      String basis) {}

  /** 爆品视图（**含阈值与统计窗口** —— 判定口径不是黑盒）。 */
  public record AiForecastHotItemView(
      List<AiForecastHotItemRow> items, int windowDays, BigDecimal unitsThreshold, BigDecimal growthThreshold, String note) {}

  /**
   * 一个机会品判定结果。
   *
   * @param detected 判成机会品了吗
   */
  public record AiForecastOpportunityRow(
      String skuCode,
      String skuName,
      boolean detected,
      @Schema(nullable = true) BigDecimal recentDailyUnits,
      @Schema(nullable = true) BigDecimal previousDailyUnits,
      @Schema(nullable = true) BigDecimal trendRatio,
      @Schema(nullable = true) BigDecimal forecastPoint,
      @Schema(nullable = true) BigDecimal forecastUpper,
      @Schema(nullable = true) BigDecimal inventoryQty,
      @Schema(nullable = true) BigDecimal coverageDays,
      @Schema(nullable = true) BigDecimal gapQty,
      int windowDays,
      BigDecimal trendThreshold,
      BigDecimal minDailyUnitsThreshold,
      String reason,
      String basis) {}

  /** 机会品视图（含阈值与统计窗口）。 */
  public record AiForecastOpportunityView(
      List<AiForecastOpportunityRow> items,
      int windowDays,
      BigDecimal trendThreshold,
      BigDecimal minDailyUnitsThreshold,
      String note) {}
}
