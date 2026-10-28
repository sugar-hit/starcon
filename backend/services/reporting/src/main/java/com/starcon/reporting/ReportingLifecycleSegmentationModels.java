package com.starcon.reporting;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 13.3.1.2 生命周期与分层的**出入参模型**。
 *
 * <p>⚠️ <b>「算不出来」与「等于 0」必须分开</b>（与 13.1.1.1 / 13.2.2.2 / 13.3.1.1 同一纪律）：
 * 档位算不出来时 {@code lifecycleSegment} 是 {@code null}，
 * {@code segmentAvailability} 说明是哪一种（{@code NO_DATA} / {@code NOT_COMPARABLE} /
 * {@code NOT_DELIVERED}），{@code segmentNote} 写清原因与归属模块 —— **不用 0 或某个默认档位冒充**。
 *
 * <p>⚠️ <b>不跨币种相加</b>：金额一律**按币种分行**（{@link LifecycleSegmentationAmountRow}）。
 * 件数与货币无关，走主表的 {@code salesQuantity}。
 *
 * <p>⚠️ <b>名字必须带 {@code Lifecycle} 前缀</b>：SpringDoc 用**简单类名**当 schema 名，
 * 同名时只能加序号消歧（序号取决于**发现顺序**，换个服务先启动就换一个名，生成出来的契约跟着漂）。
 */
public final class ReportingLifecycleSegmentationModels {

  private ReportingLifecycleSegmentationModels() {}

  // ==================================================================
  // 规则
  // ==================================================================

  /**
   * 分层规则配置（**可配置、可审计**）。
   *
   * @param version 版本号：改一次规则 = 加一个版本（旧的停用，历史结果仍指向旧版本）
   * @param effectiveFrom 生效起始日
   * @param effectiveTo 生效结束日（空 = 一直生效）
   * @param enabled 是否启用
   * @param createdBy 变更人
   */
  public record LifecycleSegmentationRuleView(
      String ruleCode,
      String ruleName,
      String dimensionType,
      int version,
      int windowDays,
      int newMaxDaysSinceFirstSale,
      int firstSaleLookbackDays,
      int minHistoryDays,
      BigDecimal decliningMinDeclineRatio,
      BigDecimal hotMinSalesShareRatio,
      BigDecimal strongMinSalesShareRatio,
      BigDecimal steadyMinSalesShareRatio,
      BigDecimal slowMaxSalesPerDay,
      BigDecimal abcACumulativeRatio,
      BigDecimal abcBCumulativeRatio,
      String quantityMetricCode,
      String amountMetricCode,
      String grossProfitMetricCode,
      LocalDate effectiveFrom,
      @Schema(nullable = true) LocalDate effectiveTo,
      boolean enabled,
      String createdBy,
      String note,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /**
   * 更新分层规则（**只改阈值与生效区间**，口径码由服务端固定 ——
   * 口径引用的是别人的口径表，改它等于换了一个真相）。
   */
  public record LifecycleSegmentationRuleInput(
      @Schema(nullable = true) String ruleName,
      @Schema(nullable = true) Integer windowDays,
      @Schema(nullable = true) Integer newMaxDaysSinceFirstSale,
      @Schema(nullable = true) Integer firstSaleLookbackDays,
      @Schema(nullable = true) Integer minHistoryDays,
      @Schema(nullable = true) BigDecimal decliningMinDeclineRatio,
      @Schema(nullable = true) BigDecimal hotMinSalesShareRatio,
      @Schema(nullable = true) BigDecimal strongMinSalesShareRatio,
      @Schema(nullable = true) BigDecimal steadyMinSalesShareRatio,
      @Schema(nullable = true) BigDecimal slowMaxSalesPerDay,
      @Schema(nullable = true) BigDecimal abcACumulativeRatio,
      @Schema(nullable = true) BigDecimal abcBCumulativeRatio,
      @Schema(nullable = true) LocalDate effectiveFrom,
      @Schema(nullable = true) LocalDate effectiveTo,
      @Schema(nullable = true) String note) {}

  // ==================================================================
  // 分层结果
  // ==================================================================

  /**
   * 一个商品维度的分层结果。
   *
   * @param lifecycleSegment 档位。**null = 算不出来**（不是 0，也不是某个默认档）
   * @param segmentAvailability AVAILABLE / NO_DATA / NOT_COMPARABLE / NOT_DELIVERED
   * @param salesQuantity 窗口内销量（件数，与币种无关）
   * @param salesShare 窗口内销量份额。**null = 算不出来**（窗口内整体没有销量）
   * @param declineRatio 下滑比例。**null = 算不出来**（对比窗口没有销量事实）
   * @param firstSaleDate 首次销售日。**null = 查不到**（回溯窗口内没有销售事实）
   * @param abcByQuantity ABC 分档（按销量）。**null = 不进榜**（没有销量事实）
   * @param recommendedAction 建议动作。**null = 算不出来**（档位算不出来）
   */
  public record LifecycleSegmentationRow(
      String dimensionType,
      String dimensionId,
      String dimensionKey,
      String dimensionName,
      LocalDate bucketDate,
      LocalDate windowFrom,
      LocalDate windowTo,
      BigDecimal salesQuantity,
      @Schema(nullable = true) LocalDate firstSaleDate,
      int historyDays,
      BigDecimal recentQuantity,
      BigDecimal priorQuantity,
      @Schema(nullable = true) BigDecimal declineRatio,
      @Schema(nullable = true) BigDecimal salesShare,
      @Schema(nullable = true) String lifecycleSegment,
      String segmentAvailability,
      String segmentNote,
      @Schema(nullable = true) String abcByQuantity,
      @Schema(nullable = true) Integer abcRankByQuantity,
      @Schema(nullable = true) Integer abcTotalByQuantity,
      @Schema(nullable = true) String recommendedAction,
      String actionNote,
      BigDecimal onHandQuantity,
      @Schema(nullable = true) BigDecimal agingDays,
      /** 🔴 **上游快照的观测时刻**（库存 / 库龄快照）。空 = 上游没投影过快照。 */
      @Schema(nullable = true) OffsetDateTime sourceObservedAt,
      List<LifecycleSegmentationAmountRow> amounts) {}

  /**
   * 一个币种的金额行（**绝不跨币种相加**）。
   *
   * @param currency 币种（3 位大写；金额行**必须**带币种）
   * @param grossProfitAmount 毛利额。**null = 算不出来**（出库成本没投影），不是 0
   * @param abcByAmount ABC 分档（按金额，**在同一个币种内部**排名）。null = 不进榜
   */
  public record LifecycleSegmentationAmountRow(
      String currency,
      BigDecimal salesAmount,
      @Schema(nullable = true) BigDecimal grossProfitAmount,
      String grossProfitAvailability,
      String grossProfitNote,
      @Schema(nullable = true) String abcByAmount,
      @Schema(nullable = true) Integer abcRankByAmount,
      @Schema(nullable = true) Integer abcTotalByAmount) {}

  /**
   * 一个档位的汇总（**分层结果 + 各层建议**的「各层」那一半）。
   *
   * @param productCount 该档的商品数
   * @param salesQuantity 该档的销量合计（件数，与币种无关）
   * @param salesAmounts 该档的销售额，**按币种分别列出**
   * @param grossProfitAmounts 该档的毛利额，**按币种分别列出**
   */
  public record LifecycleSegmentSummary(
      String segment,
      String segmentLabel,
      @Schema(nullable = true) String recommendedAction,
      String actionLabel,
      String actionNote,
      int productCount,
      BigDecimal salesQuantity,
      List<LifecycleSegmentationAmountRow> amounts) {}

  // ==================================================================
  // 主响应
  // ==================================================================

  /**
   * 分层结果（一次拉齐：分档汇总 + 商品明细 + 规则 + 口径说明）。
   *
   * @param requestedFrom 请求的窗口起日
   * @param requestedTo 请求的窗口末日
   * @param from **实际**的窗口起日（与请求不一致时给出实际值 + 差异说明）
   * @param to **实际**的窗口末日
   * @param windowMatched 实际窗口是否与请求一致
   * @param page 当前页（0 基）
   * @param size 每页条数
   * @param total 分层结果总条数
   * @param archivedProductCount 商品档案里的商品数（**没有分层结果的**商品数在 coverageNote 里）
   */
  public record LifecycleSegmentationResponse(
      LocalDate requestedFrom,
      LocalDate requestedTo,
      LocalDate from,
      LocalDate to,
      boolean windowMatched,
      String windowNote,
      String dimensionType,
      String ruleCode,
      int ruleVersion,
      List<LifecycleSegmentationRuleView> ruleViews,
      List<LifecycleSegmentSummary> summary,
      List<LifecycleSegmentationRow> rows,
      int page,
      int size,
      long total,
      int archivedProductCount,
      int notSegmentedProductCount,
      String coverageNote,
      List<LifecycleMetricReference> metricReferences,
      List<String> caliberNotes,
      List<LifecycleSegmentationRuleNote> rules,
      @Schema(nullable = true) OffsetDateTime dataUpdatedAt,
      String dataUpdatedNote,
      String status,
      String statusNote,
      @Schema(nullable = true) String emptyReason,
      String emptyNote) {}

  /** 一个**被引用**的口径（口径引用别人的表，这里只把「按它算」这件事显示出来）。 */
  public record LifecycleMetricReference(
      String metricCode,
      String displayName,
      String unit,
      String aggregation,
      @Schema(nullable = true) String numeratorMetricCode,
      @Schema(nullable = true) String denominatorMetricCode,
      String caliberOwner,
      String description,
      boolean available,
      String ownerModule,
      String availabilityNote) {}

  /** 一条判定规则 / 口径说明。 */
  public record LifecycleSegmentationRuleNote(String ruleCode, String description) {}

  // ==================================================================
  // 能力声明
  // ==================================================================

  /**
   * 能力声明。
   *
   * @param notDelivered **确实拿不到**的部分（每条都写清原因与归属模块）
   */
  public record LifecycleSegmentationCapabilities(
      boolean upstreamConnected,
      boolean segmentationAvailable,
      boolean ruleConfigurable,
      boolean dataUpdatedAtVisible,
      List<String> upstreamModules,
      List<String> notDelivered,
      List<LifecycleSegmentationRuleNote> rules) {}

  // ==================================================================
  // 刷新
  // ==================================================================

  /**
   * 一路刷新结果。
   *
   * @param status SUCCEEDED / FAILED / TRUNCATED
   */
  public record LifecycleSegmentationRefreshResult(
      String source,
      int fetched,
      int projected,
      String status,
      String note) {}
}
