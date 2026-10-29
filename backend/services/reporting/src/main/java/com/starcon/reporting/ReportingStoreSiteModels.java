package com.starcon.reporting;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 13.2.2.1 店铺与站点的**出入参模型**。
 *
 * <p>⚠️ **「算不出来」与「等于 0」必须分开**（与 13.1.1.1 / 13.1.1.2 / 13.2.1.1 / 13.2.2.2 同一纪律）：
 * 基期缺失、基期为 0、事实不存在三种情况一律返回 {@code null} / {@code NO_DATA} /
 * {@code NOT_COMPARABLE}，**不用 0 冒充**。销售额 0 与「这一格没算出来」在页面上长得一样，
 * 含义却相反。
 *
 * <p>⚠️ **不跨币种相加**：金额按币种分别报，**绝不**把不同币种加在一起。
 * 只有与货币无关的量（件数 / 单数）给一个跨币种合计，它的币种显示为空串
 * —— 空串 = 与币种无关，**不是「币种未知」**。
 *
 * <p>⚠️ **名字都带 {@code StoreSite} 前缀**：SpringDoc 用**简单类名**当 schema 名，
 * 同名时只能加序号消歧（那个序号取决于**发现顺序**，换个服务先启动就换一个名，
 * 生成出来的契约跟着漂）。本仓已有 {@code RuleNote} / {@code CurrencyRate} /
 * {@code MetricDefinitionView}，本文件一律避开这三个名字。
 */
public final class ReportingStoreSiteModels {

  private ReportingStoreSiteModels() {}

  // ==================================================================
  // 指标口径目录
  // ==================================================================

  /**
   * 一个指标的口径定义（口径唯一的展示形态）。
   *
   * <p>⚠️ **baseMetricCode 是「被比较 / 被分享的指标」**：
   * 组内占比、增长贡献度、窗口环比都只写引用，不写公式 ⇒ 口径改一处，引用它的地方自动跟着改。
   */
  public record StoreSiteMetricDefinitionView(
      String metricCode,
      String displayName,
      String unit,
      String aggregation,
      @Schema(nullable = true) String baseMetricCode,
      boolean currencyAware,
      String description,
      String ownerModule) {}

  // ==================================================================
  // 分组报表
  // ==================================================================

  /** 报表里一个指标的取值。 */
  public record StoreSiteMetricValue(
      String metricCode,
      /** 币种（**空串 = 与币种无关**：件数 / 单数的跨币种合计）。 */
      String currency,
      @Schema(nullable = true) BigDecimal value,
      /** 基期（上一等长窗口）的同一个指标（null = 没有基期事实，**不是 0**）。 */
      @Schema(nullable = true) BigDecimal baseValue,
      /** 增量 = 当前窗口值 − 基期值（null = 基期缺失，**不是 0**）。 */
      @Schema(nullable = true) BigDecimal delta,
      /** AVAILABLE / NO_DATA / NOT_COMPARABLE。 */
      String availability,
      String availabilityNote) {}

  /**
   * 分组报表的一行 = 一个维度对象（一个店铺 / 一个站点 / 一个平台）。
   *
   * @param dimensionKey 维度键（站点维度是「平台码:站点码」—— 站点码只在平台内唯一）
   * @param storeAge 新店 / 老店（**只对店铺维度有值**：NEW / EXISTING）
   * @param newShopCount 该维度下新店数量（平台 / 站点维度也有）
   * @param existingShopCount 该维度下老店数量
   */
  public record StoreSiteRow(
      String dimensionKey,
      String dimensionLabel,
      String platformCode,
      String platformName,
      String siteCode,
      String siteName,
      String shopCode,
      String shopName,
      String storeAge,
      int newShopCount,
      int existingShopCount,
      List<StoreSiteMetricValue> metrics,
      String note) {}

  // ==================================================================
  // 渠道占比（平台维度）
  // ==================================================================

  /** 一个平台的渠道占比。 */
  public record StoreSiteChannelShare(
      String platformCode,
      String platformName,
      List<StoreSiteMetricValue> metrics,
      String note) {}

  // ==================================================================
  // 新店 / 老店
  // ==================================================================

  /**
   * 新店 / 老店汇总。
   *
   * <p>🔴 **这是「增量来自新店还是老店」的答案** —— 只给绝对值没用，
   * 增长贡献度才告诉老板「增量是哪个店带来的」。
   */
  public record StoreSiteNewVsExisting(
      int newShopCount,
      int existingShopCount,
      List<StoreSiteMetricValue> newStoreMetrics,
      List<StoreSiteMetricValue> existingStoreMetrics,
      /** 新店带来的增量占总增量的比例（每币种一条；null = 算不出来，**不是 0%**）。 */
      List<StoreSiteMetricValue> newStoreContribution,
      String note) {}

  // ==================================================================
  // 无法唯一归属
  // ==================================================================

  /**
   * 「无法唯一归属到站点」的量。
   *
   * <p>🔴 **这一段必须常驻显示**：订单不带站点，店铺启用多个站点时它的销量归不了任何站点 ——
   * 摊给谁是造假，于是单列出来，而不是让用户以为「这个站点没卖出去」。
   */
  public record StoreSiteUnattributed(
      int shopCount,
      List<String> shopCodes,
      List<StoreSiteMetricValue> metrics,
      String reason,
      String note) {}

  // ==================================================================
  // 数据覆盖度
  // ==================================================================

  /**
   * 销量口径的覆盖度。
   *
   * <p>⚠️ **销量只存在于订单明细里**，明细要逐单取（有张数上限）⇒ 成交金额可以全量，
   * 销量只能覆盖一部分订单。本字段如实报出「销量覆盖了多少比例的订单」，
   * 免得用户把「没取到明细」读成「没卖出去」。
   */
  public record StoreSiteCoverage(
      int orderCount,
      int orderCountWithDetail,
      @Schema(nullable = true) BigDecimal detailCoveragePercent,
      boolean truncated,
      String note) {}

  // ==================================================================
  // 响应
  // ==================================================================

  /**
   * 店铺与站点维度拆解的完整响应。
   *
   * @param requestedFrom / requestedTo 调用方请求的原始范围
   * @param baseFrom / baseTo **基期**（上一等长窗口）—— 增长贡献度算不出来时用它解释原因
   * @param dimension SHOP / SITE / PLATFORM（调用方选的拆解维度）
   * @param timezone 本次汇总所用的时区（**跨时区汇总必须显示所用时区**）
   * @param dataUpdatedAt 数据更新时间（**空表时为 null**，不是假时间）
   */
  public record StoreSiteResponse(
      LocalDate requestedFrom,
      LocalDate requestedTo,
      LocalDate baseFrom,
      LocalDate baseTo,
      String dimension,
      String dimensionLabel,
      String timezone,
      long totalRows,
      int page,
      int size,
      List<StoreSiteRow> rows,
      List<StoreSiteMetricDefinitionView> metricDefinitions,
      List<StoreSiteChannelShare> channelShares,
      StoreSiteNewVsExisting newVsExisting,
      StoreSiteUnattributed unattributed,
      StoreSiteCoverage coverage,
      @Schema(nullable = true) OffsetDateTime dataUpdatedAt,
      String dataUpdatedNote,
      String status,
      String statusNote,
      @Schema(nullable = true) String emptyReason,
      String emptyNote,
      List<String> warnings,
      List<String> caliberNotes) {}

  // ==================================================================
  // 能力声明
  // ==================================================================

  /**
   * 能力声明。
   *
   * @param upstreamConnected 上游是否真接通
   * @param notDelivered **确实拿不到**的部分（每条都写清原因与影响）
   * @param rules 判定规则（新老店判据、站点归属边界、增长贡献度口径 …）
   */
  public record StoreSiteCapabilities(
      boolean upstreamConnected,
      boolean breakdownAvailable,
      boolean contributionAvailable,
      boolean channelShareAvailable,
      boolean storeAgeAvailable,
      boolean drilldownAvailable,
      boolean dataUpdatedAtVisible,
      List<String> upstreamModules,
      List<String> notDelivered,
      List<StoreSiteRuleNote> rules,
      List<String> dimensions,
      List<String> metrics) {}

  /** 一条判定规则。 */
  public record StoreSiteRuleNote(String ruleCode, String description) {}

  // ==================================================================
  // 刷新
  // ==================================================================

  /** 一路刷新结果。 */
  public record StoreSiteRefreshResult(
      String source,
      /** 拉到的上游行数。 */
      int fetched,
      /** 落库的事实行数。 */
      int projected,
      /** 结论：SUCCEEDED / FAILED / TRUNCATED。 */
      String status,
      /** 说明（失败原因 / 截断原因）。 */
      String note) {}

  // ==================================================================
  // 下钻
  // ==================================================================

  /** 构成明细的一行（哪个订单 / 哪张订单的哪一行）。 */
  public record StoreSiteDrilldownRow(
      String shopCode,
      String metricCode,
      String currency,
      String sourceKind,
      String referenceId,
      String referenceLabel,
      String orderNo,
      String skuCode,
      String skuName,
      @Schema(nullable = true) OffsetDateTime occurredAt,
      BigDecimal quantity,
      BigDecimal value) {}

  /** 下钻的完整响应。 */
  public record StoreSiteDrilldownResponse(
      LocalDate from,
      LocalDate to,
      String timezone,
      String dimensionKey,
      long totalRows,
      int page,
      int size,
      List<StoreSiteDrilldownRow> rows,
      @Schema(nullable = true) OffsetDateTime dataUpdatedAt,
      String dataUpdatedNote,
      String status,
      String statusNote,
      @Schema(nullable = true) String emptyReason,
      String emptyNote,
      List<String> warnings) {}
}
