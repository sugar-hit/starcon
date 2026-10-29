package com.starcon.reporting;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 13.4.2.1 成本结构的**读模型契约**（蓝图「输出」句：占比结构图 + 趋势 + 异常提示）。
 *
 * <p>⚠️ **「算不出来」与「等于 0」是两件事**：本文件里凡是可以「算不出来」的数值
 * 一律用 {@code null} 表示，**不用 0 冒充**。「占比 0.00%」与「这一格没算出来」
 * 在报表里长得一模一样，含义却完全相反 —— 而蓝图那句加粗要消灭的正是前者。
 *
 * <p>⚠️ **占比的分母口径写在响应里**（{@code denominatorCaliber} / {@code denominatorNote}）：
 * 「占总成本」与「占收入」是**两个不同的口径**，混着报等于骗人。
 *
 * <p>⚠️ **多币种绝不相加**：每一行都带自己的币种。
 */
public final class ReportingCostStructureModels {

  private ReportingCostStructureModels() {}

  // ================================================================ 成本类别

  /**
   * 成本类别（本模块自己的口径，穷尽蓝图职责句点名的七类）。
   *
   * <p>⚠️ 类别**不是**上游口径的翻译层：上游给的是「佣金 / FBA 费用 / 头程分摊额 …」，
   * 本枚举把它们归到**七个可相加的桶**里，让占比这件事在查询期成立。
   */
  public enum CostCategory {
    /** 采购成本（12.4.1.2 销售出库成本）。 */
    PURCHASE("采购成本", "12.4.1.2"),
    /** 头程分摊（5.2.1.3 成本分摊明细）。 */
    HEAD_MILE("头程分摊", "5.2.1.3"),
    /** 平台费（5.2.3.1 实际台账 + 12.3.1.2 佣金）。 */
    PLATFORM_FEE("平台费", "5.2.3.1"),
    /** 物流费（5.2.3.2 实际台账 + 12.3.1.2 FBA 费用）。 */
    LOGISTICS_FEE("物流费", "5.2.3.2"),
    /** 广告费（12.3.1.2 广告费明细）。 */
    AD_FEE("广告费", "12.3.1.2"),
    /** 退货（11.1.1.1 售后单列表）。 */
    RETURN("退货", "11.1.1.1"),
    /** 其它（12.4.2.1 费用归集条目：订阅 / 人员 / 办公）。 */
    OTHER("其它", "12.4.2.1");

    private final String label;
    private final String ownerModule;

    CostCategory(String label, String ownerModule) {
      this.label = label;
      this.ownerModule = ownerModule;
    }

    /** 中文名（页面上原样显示）。 */
    public String label() {
      return label;
    }

    /** 这个成本类别的上游归属模块（能力声明里写明「数据来自哪里」）。 */
    public String ownerModule() {
      return ownerModule;
    }

    /** 解析失败返回 null（调用方据此给一句可读错误，而不是抛枚举异常）。 */
    public static CostCategory parse(String raw) {
      if (raw == null || raw.isBlank()) return null;
      for (var c : values()) {
        if (c.name().equalsIgnoreCase(raw.trim())) return c;
      }
      return null;
    }
  }

  // ================================================================ 结构占比表

  /**
   * 成本结构占比的一行。
   *
   * @param costCategory 成本类别
   * @param costCategoryLabel 成本类别中文名
   * @param dimensionKey 归属维度取值（按所选维度：SKU / 店铺 / 类目 / 平台；ALL 口径下为空串）
   * @param dimensionLabel 归属维度中文说明
   * @param currency 币种（**不跨币种相加**）
   * @param amount 该类成本金额（可负 = 退款 / 冲回）
   * @param share 占比（百分数；null = **算不出来**，不是 0）
   * @param denominatorAmount 占比的分母（null = 分母算不出来）
   * @param comparable 占比是否可比（false ⇒ 分母缺失或为 0）
   * @param previousShare 环比基线占比（**前一期同长度窗口**；null = 那期没有可比事实）
   * @param baselineShare 历史基线占比（**前 N 期同口径占比均值**；null = 历史期数不足）
   * @param yoyShare 同比基线占比（**去年同期同长度窗口**；null = 那期没有可比事实）
   * @param deltaVsPrevious 占比环比差（百分点；null = 任一侧不可比）
   * @param deltaVsYoY 占比同比差（百分点；null = 任一侧不可比）
   * @param anomaly 是否构成异常
   * @param anomalyReason 异常判定说明（**为什么**判成异常 / 为什么判不了）
   * @param note 这一行为什么算不出来（或为什么可比）
   */
  public record CostStructureRow(
      String costCategory,
      String costCategoryLabel,
      String dimensionKey,
      String dimensionLabel,
      String currency,
      @Schema(nullable = true) BigDecimal amount,
      @Schema(nullable = true) BigDecimal share,
      @Schema(nullable = true) BigDecimal denominatorAmount,
      boolean comparable,
      @Schema(nullable = true) BigDecimal previousShare,
      @Schema(nullable = true) BigDecimal baselineShare,
      @Schema(nullable = true) BigDecimal yoyShare,
      @Schema(nullable = true) BigDecimal deltaVsPrevious,
      @Schema(nullable = true) BigDecimal deltaVsYoY,
      boolean anomaly,
      String anomalyReason,
      String note) {}

  // ================================================================ 趋势

  /**
   * 成本结构占比趋势上的一个点。
   *
   * @param share 占比（百分数；null = 该期分母算不出来 ⇒ **不可比**，不是 0%）
   */
  public record CostStructureTrendPoint(
      LocalDate periodStart,
      LocalDate periodEnd,
      String costCategory,
      String costCategoryLabel,
      String dimensionKey,
      String dimensionLabel,
      String currency,
      @Schema(nullable = true) BigDecimal amount,
      @Schema(nullable = true) BigDecimal share,
      @Schema(nullable = true) BigDecimal denominatorAmount,
      boolean comparable) {}

  // ================================================================ 异常提示

  /**
   * 一条异常提示。
   *
   * @param direction 异常方向：{@code UP} 占比突然上升 / {@code DOWN} 占比突然下降
   * @param thresholdMode 阈值口径：{@code FIXED} / {@code STDDEV}
   * @param thresholdValue 配置的阈值取值（百分点 / 标准差倍数）
   * @param effectiveThreshold **本次实际生效的阈值**（STDDEV 模式下 = 倍数 × 基线标准差）
   * @param reason 为什么判成异常 / 为什么判不了
   */
  public record CostStructureAnomaly(
      String costCategory,
      String costCategoryLabel,
      String dimensionKey,
      String dimensionLabel,
      String currency,
      @Schema(nullable = true) BigDecimal share,
      @Schema(nullable = true) BigDecimal baselineShare,
      @Schema(nullable = true) BigDecimal delta,
      String direction,
      String thresholdMode,
      @Schema(nullable = true) BigDecimal thresholdValue,
      @Schema(nullable = true) BigDecimal effectiveThreshold,
      String reason,
      String note) {}

  // ================================================================ 完整响应

  /**
   * 成本结构的完整响应（占比结构表 + 趋势 + 异常提示 + 数据更新时间）。
   *
   * @param granularity 期间粒度：{@code DAY} / {@code WEEK} / {@code MONTH}
   * @param dimension 归属维度：{@code ALL} / {@code SKU} / {@code SHOP} / {@code CATEGORY} / {@code PLATFORM}
   * @param denominatorCaliber 占比分母口径：{@code TOTAL_COST} 总成本 / {@code REVENUE} 收入
   * @param denominatorNote 分母口径的**逐字说明**（页面上原样显示）
   * @param dataUpdatedAt **数据更新时间**（卷 13 卷首「数据延迟要显示」：页面必须常驻显示）
   * @param warnings 异常提示（口径层面的提醒）
   * @param caliberNote 口径说明（七类成本分别来自哪个上游）
   */
  public record CostStructureResponse(
      LocalDate from,
      LocalDate to,
      String granularity,
      String dimension,
      String denominatorCaliber,
      String denominatorNote,
      List<CostStructureRow> rows,
      List<CostStructureTrendPoint> trend,
      List<CostStructureAnomaly> anomalies,
      @Schema(nullable = true) OffsetDateTime dataUpdatedAt,
      String dataUpdatedNote,
      List<String> warnings,
      String caliberNote) {}

  // ================================================================ 能力声明

  /**
   * 能力声明。
   *
   * @param upstreamConnected 本模块的上游是否真接通
   * @param costCategories 本模块覆盖的成本类别
   * @param upstreamModules 本服务**真的读过**的上游模块
   * @param notDelivered **确实拿不到**的部分（每条写清原因与影响）
   * @param defaultThresholdMode 默认阈值口径
   * @param defaultThresholdValue 默认阈值取值
   * @param defaultMinHistoryPeriods 默认最少历史期数
   */
  public record CostStructureCapabilities(
      boolean upstreamConnected,
      List<String> costCategories,
      List<String> upstreamModules,
      List<String> notDelivered,
      String defaultThresholdMode,
      BigDecimal defaultThresholdValue,
      int defaultMinHistoryPeriods,
      String note) {}

  // ================================================================ 阈值规则

  /** 某类成本的异常判定阈值（可配置项）。 */
  public record CostStructureRule(
      String costCategory,
      String costCategoryLabel,
      String thresholdMode,
      BigDecimal thresholdValue,
      int minHistoryPeriods,
      String direction,
      boolean enabled,
      @Schema(nullable = true) OffsetDateTime updatedAt) {}

  /** 改阈值的入参（**全部可选**：只改写进来的那几个字段）。 */
  public record CostStructureRuleInput(
      String thresholdMode,
      @Schema(nullable = true) BigDecimal thresholdValue,
      @Schema(nullable = true) Integer minHistoryPeriods,
      String direction,
      @Schema(nullable = true) Boolean enabled) {}

  // ================================================================ 刷新

  /** 一次刷新里某一路上游的结果。 */
  public record CostStructureRefreshSummary(
      String source,
      String costCategory,
      int factCount,
      String status,
      String note) {}
}
