package com.starcon.reporting;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 13.4.2.2 费用差异的**读模型契约**（蓝图「输出」句：差异表 + 偏差趋势 + 规则调优建议）。
 *
 * <p>⚠️ **「算不出来」与「等于 0」是两件事**：本文件里凡是可以「算不出来」的数值
 * 一律用 {@code null} 表示，**不用 0 冒充**。「差异 0.00」与「这一格没算出来」
 * 在报表里长得一模一样，含义却完全相反 —— 而蓝图那句加粗要消灭的正是前者。
 *
 * <p>⚠️ **符号口径固定为「差异 = 实际 − 预计」**（写在每行的 {@code note} 与页面上）：
 * 差异为**负** ⇒ 实际比预计少 ⇒ **预计高估**了 ⇒ 费用规则配高了；
 * 差异为**正** ⇒ 实际比预计多 ⇒ **预计低估**了 ⇒ 费用规则配低了。
 * 蓝图那句「**持续为负的差异说明费用规则配错了**」就是在这个口径下成立的。
 */
public final class ReportingExpenseModels {

  private ReportingExpenseModels() {}

  // ================================================================ 费用类型

  /** 费用类型（本模块自己的口径，穷尽蓝图职责句点名的四类）。 */
  public enum FeeType {
    /** 平台费（5.2.3.1 平台费用规则 / 12 结算佣金）。 */
    PLATFORM_FEE("平台费", "5.2.3.1"),
    /** 物流费（5.2.3.2 渠道计费规则 / 12 结算 FBA 费用）。 */
    LOGISTICS_FEE("物流费", "5.2.3.2"),
    /** 广告费（14 广告预算分配方案 / 12 结算广告费）。 */
    AD_FEE("广告费", "14.2.1.1"),
    /** 头程分摊（5.2.1.1 成本构成的 FIRST_MILE 分项 / 5.2.1.3 成本分摊）。 */
    HEAD_FREIGHT("头程分摊", "5.2.1.1");

    private final String label;
    private final String ownerModule;

    FeeType(String label, String ownerModule) {
      this.label = label;
      this.ownerModule = ownerModule;
    }

    /** 中文名（页面上原样显示）。 */
    public String label() {
      return label;
    }

    /** 费用规则的归属模块（建议里要写明「去哪个模块改规则」）。 */
    public String ownerModule() {
      return ownerModule;
    }

    /** 解析失败返回 null（调用方据此给一句可读错误，而不是抛枚举异常）。 */
    public static FeeType parse(String raw) {
      if (raw == null || raw.isBlank()) return null;
      for (var t : values()) {
        if (t.name().equalsIgnoreCase(raw.trim())) return t;
      }
      return null;
    }
  }

  // ================================================================ 差异表

  /**
   * 差异表的一行。
   *
   * @param feeType 费用类型
   * @param feeSubType 上游口径子类型（平台费的 COMMISSION / 运费的计费口径 / …），空串 = 上游没给
   * @param periodStart 期间起
   * @param periodEnd 期间止（逐日事实两列相同；窗口型事实不同）
   * @param currency 币种（**不跨币种相加**）
   * @param estimatedAmount 预计费用（null = 算不出来，不是 0）
   * @param actualAmount 实际费用（null = 算不出来，不是 0）
   * @param varianceAmount 差异 = 实际 − 预计（null = 算不出来）
   * @param varianceRate 差异率 = 差异 ÷ 预计 × 100（null = 分母缺失或为 0 ⇒ **不可比**）
   * @param comparable 是否可比（false ⇒ 至少一侧算不出来）
   * @param estimateCount 预计侧的样本笔数
   * @param actualCount 实际侧的样本笔数
   * @param baseAmount 销售额 / 货值基数（用于反推费率；没有基数时为 0）
   * @param scopeLabel 归属维度的中文说明（哪个平台 / 哪个渠道 / 哪个 SKU）
   * @param actualSource 实际费用来自哪个上游（catalog 台账 / 12 结算明细 / 5.2.1.3 分摊单）
   * @param note 这一行为什么算不出来（或为什么可比）
   */
  public record ExpenseVarianceRow(
      String feeType,
      String feeTypeLabel,
      String feeSubType,
      LocalDate periodStart,
      LocalDate periodEnd,
      String currency,
      @Schema(nullable = true) BigDecimal estimatedAmount,
      @Schema(nullable = true) BigDecimal actualAmount,
      @Schema(nullable = true) BigDecimal varianceAmount,
      @Schema(nullable = true) BigDecimal varianceRate,
      boolean comparable,
      long estimateCount,
      long actualCount,
      @Schema(nullable = true) BigDecimal baseAmount,
      String platformCode,
      String siteCode,
      String channelCode,
      String carrierCode,
      String shippingMethodCode,
      String shopCode,
      String skuCode,
      String scopeLabel,
      String actualSource,
      String note) {}

  /**
   * 有实际费用、但**没有可比预计费用**的一行。
   *
   * <p>⚠️ 这一栏是本模块最容易做成「全部已交付」的地方：能力声明写「全部接通」时，
   * 页面会把每一行都画成「有差异率」的样子，而实际上有一半的行**根本算不出差异**。
   */
  public record UnmatchedActualRow(
      String feeType,
      String feeTypeLabel,
      String feeSubType,
      String currency,
      @Schema(nullable = true) BigDecimal actualAmount,
      long actualCount,
      String scopeLabel,
      String actualSource,
      String reason) {}

  // ================================================================ 偏差趋势

  /**
   * 偏差趋势上的一个点。
   *
   * @param scopeLabel 归属维度说明（聚合到费用类型时为「全部」）
   */
  public record ExpenseVarianceTrendPoint(
      String feeType,
      String feeTypeLabel,
      String feeSubType,
      String currency,
      LocalDate periodStart,
      LocalDate periodEnd,
      @Schema(nullable = true) BigDecimal estimatedAmount,
      @Schema(nullable = true) BigDecimal actualAmount,
      @Schema(nullable = true) BigDecimal varianceAmount,
      @Schema(nullable = true) BigDecimal varianceRate,
      String scopeLabel) {}

  // ================================================================ 规则调优建议

  /**
   * 一条规则调优建议。
   *
   * @param direction 偏差方向：{@code OVER_ESTIMATED} 预计高估 / {@code UNDER_ESTIMATED} 预计低估 /
   *     {@code BALANCED} 偏差在阈值内
   * @param currentRate 当前规则的有效费率（预计 ÷ 基数；没有基数时 null）
   * @param impliedRate 按实际发生额反推的费率（实际 ÷ 基数；没有基数时 null）
   * @param suggestedRate 建议改成多少（= impliedRate；没有基数时 null ⇒ 只能给定性建议）
   * @param sampleCount 可比样本笔数
   */
  public record ExpenseVarianceRecommendation(
      String feeType,
      String feeTypeLabel,
      String feeSubType,
      String currency,
      String scopeLabel,
      String ownerModule,
      String direction,
      @Schema(nullable = true) BigDecimal estimatedTotal,
      @Schema(nullable = true) BigDecimal actualTotal,
      @Schema(nullable = true) BigDecimal varianceAmount,
      @Schema(nullable = true) BigDecimal varianceRate,
      @Schema(nullable = true) BigDecimal currentRate,
      @Schema(nullable = true) BigDecimal impliedRate,
      @Schema(nullable = true) BigDecimal suggestedRate,
      long sampleCount,
      String suggestion,
      String reason) {}

  // ================================================================ 完整响应

  /**
   * 费用差异的完整响应（差异表 + 偏差趋势 + 规则调优建议 + 数据更新时间）。
   *
   * @param granularity 期间粒度：{@code DAY} / {@code WEEK} / {@code MONTH}
   * @param dataUpdatedAt **数据更新时间**（卷 13 卷首「数据延迟要显示」：页面必须常驻显示）
   * @param varianceSignNote 差异的符号口径（写清楚「差异 = 实际 − 预计」）
   * @param warnings 异常提示
   * @param caliberNote 口径说明（预计侧与实际侧分别来自哪个上游）
   */
  public record ExpenseVarianceResponse(
      LocalDate from,
      LocalDate to,
      String granularity,
      List<ExpenseVarianceRow> rows,
      List<UnmatchedActualRow> unmatchedActuals,
      List<ExpenseVarianceTrendPoint> trend,
      List<ExpenseVarianceRecommendation> recommendations,
      @Schema(nullable = true) OffsetDateTime dataUpdatedAt,
      String dataUpdatedNote,
      String varianceSignNote,
      List<String> warnings,
      String caliberNote) {}

  // ================================================================ 能力声明

  /**
   * 能力声明。
   *
   * @param upstreamConnected 本模块的上游是否真接通
   * @param feeTypes 本模块覆盖的费用类型
   * @param upstreamModules 本服务**真的读过**的上游模块
   * @param notDelivered **确实拿不到**的部分（每条写清原因与影响）
   * @param recommendationThreshold 触发建议的差异率阈值（百分数）
   * @param recommendationMinSamples 触发建议所需的最少可比样本数
   */
  public record ExpenseVarianceCapabilities(
      boolean upstreamConnected,
      List<String> feeTypes,
      List<String> upstreamModules,
      List<String> notDelivered,
      BigDecimal recommendationThreshold,
      int recommendationMinSamples,
      String note) {}

  // ================================================================ 下钻（SKU）

  /**
   * SKU 级差异的一行（蓝图职责句点名的「按 SKU 与期间展示差异率」）。
   *
   * @param varianceRate 差异率（null = 不可比）
   */
  public record ExpenseSkuVarianceRow(
      String skuCode,
      String feeType,
      String feeTypeLabel,
      String feeSubType,
      LocalDate periodStart,
      LocalDate periodEnd,
      String currency,
      @Schema(nullable = true) BigDecimal estimatedAmount,
      @Schema(nullable = true) BigDecimal actualAmount,
      @Schema(nullable = true) BigDecimal varianceAmount,
      @Schema(nullable = true) BigDecimal varianceRate,
      boolean comparable,
      long sampleCount,
      String scopeLabel,
      String note) {}

  /** SKU 级差异结果（真分页 + 真计数）。 */
  public record ExpenseSkuVariancePage(
      List<ExpenseSkuVarianceRow> items, int page, int size, long total, String note) {}

  /** 上游事实的来源说明（给能力声明与口径说明用）。 */
  public record ExpenseSourceRef(String sourceService, String sourceModule, String description) {}

  /** 内部用：一次刷新里某一路上游的结果。 */
  public record ExpenseRefreshSummary(
      String source, int eventCount, int appliedCount, int skippedCount, String status, String note) {}

  /** UUID 解析（可选 id 参数）。 */
  static UUID uuidOf(String raw) {
    if (raw == null || raw.isBlank()) return null;
    return UUID.fromString(raw.trim());
  }
}
