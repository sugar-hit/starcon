package com.starcon.catalog;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 5.5.1.1 价格历史：**价格变更时间轴**、**变更原因字典**与**销量 / 利润对齐视图**的契约。
 *
 * <p>蓝图原文：
 *
 * <blockquote>
 *
 * **职责**：记录 SKU/listing 的价格变更历史（时间、旧价、新价、操作人/来源、原因），支持按时间轴回溯并对比同期销量利润变化。
 *
 * **输入**：价格变更事件（人工与自动规则都记）；查询条件。
 *
 * **输出**：价格时间轴 + 关联的销售/利润变化。
 *
 * **关系**：上游 = 5.4.1、5.3.2.2；下游 = 13 分析。**价格历史 + 销量 + 利润三者对齐，才能回答"降价到底有没有用"**。
 *
 * </blockquote>
 *
 * <p><b>🔴 三个不可让步的口径</b>：
 *
 * <ol>
 *   <li><b>观测 ≠ 变更</b>。5.5.2.1 的 {@code price_anomaly_observation} 是「某时刻看到的价」，
 *       本模块的 {@code price_change_history} 是「价被改了」。前者**不**进本表。
 *   <li><b>listing = (SKU × 店铺)</b>。本仓没有 listing 的本地投影（listing 事实属 channel 的
 *       4.1.1.1），跨服务查表被 AGENTS.md 禁止 ⇒ 不建影子表，按 (SKU × 店铺) 记。
 *   <li><b>null ≠ 0</b>。读不到 / 没测准一律是 {@code null}，前端渲染成「未登记 / 未测准」，
 *       绝不渲染成 0.00 —— 渲染成 0 会让「降价到底有没有用」这个问题答错方向。
 * </ol>
 */
public final class PriceHistoryModels {

  private PriceHistoryModels() {}

  // ================================================================ 词表

  /** 这次价格变更**是谁发起的**。<b>枚举只增不改</b>：它进事件表与契约，改语义会让历史读起来是错的。 */
  public enum PriceHistoryChangeSource {
    /** 人工调价（5.4.1.1 调价单 / 人工登记）。 */
    MANUAL_ADJUSTMENT("人工调价"),
    /** 批量调价（5.4.1.2）。 */
    BATCH_ADJUSTMENT("批量调价"),
    /** 价格规则自动调价（5.3.2.2 产出建议 → 5.4.1.1 执行）。 */
    PRICE_RULE("价格规则自动调价"),
    /** 价目表改价（5.1，**未交付**）。 */
    PRICE_LIST("价目表改价"),
    /** 平台侧价格回流（4.3.2.2 只回流状态、不回流价格）。 */
    PLATFORM_FEEDBACK("平台价格回流"),
    /** 批量导入。 */
    IMPORT("批量导入"),
    /** 其它。 */
    OTHER("其它来源");

    private final String label;

    PriceHistoryChangeSource(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 解析失败返回 null（调用方据此给出可读错误，而不是抛枚举异常）。 */
    public static PriceHistoryChangeSource parse(String raw) {
      if (raw == null) return null;
      for (var s : values()) {
        if (s.name().equalsIgnoreCase(raw.trim())) {
          return s;
        }
      }
      return null;
    }
  }

  /** 价格变动的方向。 */
  public enum PriceHistoryDirection {
    /** 首次定价（此前没有价）。 */
    INITIAL("首次定价"),
    /** 涨价。 */
    UP("涨价"),
    /** 降价。 */
    DOWN("降价"),
    /** 价格未变。 */
    UNCHANGED("价格未变");

    private final String label;

    PriceHistoryDirection(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static PriceHistoryDirection parse(String raw) {
      if (raw == null) return null;
      for (var s : values()) {
        if (s.name().equalsIgnoreCase(raw.trim())) {
          return s;
        }
      }
      return null;
    }
  }

  /** 变更原因的**粗分类**（系统内置，保证跨租户也能做粗口径统计）。 */
  public enum PriceHistoryReasonCategory {
    COMPETITION("竞品"),
    COST("成本变动"),
    STOCK("库存"),
    LIFECYCLE("生命周期"),
    PROMOTION("促销"),
    SEASONAL("季节"),
    MANUAL("其它人工原因"),
    OTHER("其它");

    private final String label;

    PriceHistoryReasonCategory(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static PriceHistoryReasonCategory parse(String raw) {
      if (raw == null) return null;
      for (var s : values()) {
        if (s.name().equalsIgnoreCase(raw.trim())) {
          return s;
        }
      }
      return null;
    }
  }

  // ================================================================ 能力声明

  /** 一个输入维度的能力声明。 */
  public record PriceHistoryInputCapability(
      String code,
      String label,
      String source,
      boolean available,
      String ownerModule,
      String impact,
      String basis,
      String note) {}

  /** 能力声明。页面顶部固定显示它。 */
  public record PriceHistoryCapabilities(
      List<PriceHistoryInputCapability> inputs,
      String listingNote,
      String changeSourceNote,
      String profitNote,
      String salesNote,
      String upstreamNote,
      String downstreamNote,
      String sourceNote,
      String actionsNote) {}

  // ================================================================ 变更事实

  /** 时间轴的一行 = 一次价格变更。 */
  public record PriceHistoryChangeRow(
      UUID id,
      UUID skuId,
      String skuCode,
      String skuName,
      String platformCode,
      String siteCode,
      String currency,
      @Schema(nullable = true) BigDecimal oldPrice,
      BigDecimal newPrice,
      @Schema(nullable = true) BigDecimal priceDelta,
      @Schema(nullable = true) BigDecimal changeRatio,
      String direction,
      String directionLabel,
      String changeSource,
      String changeSourceLabel,
      String reasonCode,
      String reasonText,
      @Schema(nullable = true) String operatorId,
      String sourceModule,
      String sourceReference,
      String eventKey,
      OffsetDateTime effectiveAt,
      OffsetDateTime recordedAt,
      @Schema(nullable = true) BigDecimal grossProfitBefore,
      @Schema(nullable = true) BigDecimal grossMarginBefore,
      @Schema(nullable = true) BigDecimal grossProfitAfter,
      @Schema(nullable = true) BigDecimal grossMarginAfter,
      boolean decisionReady,
      String profitNote,
      OffsetDateTime createdAt) {}

  /** 时间轴查询条件。 */
  public record PriceHistoryChangeQuery(
      String keyword,
      @Schema(nullable = true) UUID skuId,
      String platformCode,
      String siteCode,
      String changeSource,
      String reasonCode,
      String direction,
      @Schema(nullable = true) LocalDate windowFrom,
      @Schema(nullable = true) LocalDate windowTo,
      String sort,
      int page,
      int size) {}

  /** 时间轴分页结果。 */
  public record PriceHistoryChangeListPage(List<PriceHistoryChangeRow> items, long total, int page, int size, String note) {}

  /** 单条变更详情：变更事实 + 前后相邻的变更（时间轴的上下文）。 */
  public record PriceHistoryChangeDetail(
      PriceHistoryChangeRow change,
      @Schema(nullable = true) PriceHistoryChangeRow previous,
      @Schema(nullable = true) PriceHistoryChangeRow next,
      String basis,
      String note) {}

  // ================================================================ 时间轴

  /** 时间轴上的一格。 */
  public record PriceHistoryTimelineEntry(
      PriceHistoryChangeRow change,
      /**
       * 链条是否接得上：本次的旧价是否等于上一次的��价。
       *
       * <p>🔴 断链是一个**真��实信号**（比如中间有一次变更没进账），不是可以忽略的噪音 ——
       * 链断了之后「从哪天开始降的」这个问题就没有确定答案。
       */
      boolean chainConsistent,
      @Schema(nullable = true) String chainNote,
      /** 从时间轴起点累计到本次的价差合计（新价 − 时间轴第一格的新价）。 */
      @Schema(nullable = true) BigDecimal cumulativeDelta) {}

  /** 一个 listing（SKU × 店铺）的时间轴。 */
  public record PriceHistoryTimeline(
      UUID skuId,
      String skuCode,
      String skuName,
      String platformCode,
      String siteCode,
      String currency,
      @Schema(nullable = true) BigDecimal currentPrice,
      @Schema(nullable = true) OffsetDateTime currentPriceAt,
      int changeCount,
      List<PriceHistoryTimelineEntry> entries,
      List<PriceHistoryPendingSuggestion> pendingSuggestions,
      String note) {}

  /**
   * 5.3.2.2 价格规则的**待执行建议**（只读引用）。
   *
   * <p>🔴 建议**不是生效价**：改价的写入所有权在 5.4.1.1。
   * 所以它出现在时间轴上时必须明确标成「建议（尚未生效）」，
   * 绝不能混进变更事实里 —— 混进去就等于凭空造出一次没发生过的调价。
   */
  public record PriceHistoryPendingSuggestion(
      UUID id,
      UUID ruleId,
      String ruleName,
      UUID skuId,
      String platformCode,
      String siteCode,
      String currency,
      String actionType,
      @Schema(nullable = true) BigDecimal currentPrice,
      @Schema(nullable = true) BigDecimal suggestedPrice,
      @Schema(nullable = true) BigDecimal priceChangeRate,
      String status,
      boolean requiresConfirmation,
      @Schema(nullable = true) BigDecimal grossMarginRate,
      String reason,
      OffsetDateTime createdAt) {}

  // ================================================================ 对比视图

  /** 一行「降价到底有没有用」的对比。 */
  public record PriceHistoryComparisonRow(
      UUID id,
      UUID skuId,
      String skuCode,
      String skuName,
      String platformCode,
      String siteCode,
      String currency,
      OffsetDateTime effectiveAt,
      @Schema(nullable = true) BigDecimal oldPrice,
      BigDecimal newPrice,
      @Schema(nullable = true) BigDecimal priceDelta,
      @Schema(nullable = true) BigDecimal changeRatio,
      String direction,
      String changeSource,
      String reasonCode,
      String reasonText,
      @Schema(nullable = true) BigDecimal grossProfitBefore,
      @Schema(nullable = true) BigDecimal grossProfitAfter,
      @Schema(nullable = true) BigDecimal grossProfitDelta,
      @Schema(nullable = true) BigDecimal grossMarginBefore,
      @Schema(nullable = true) BigDecimal grossMarginAfter,
      boolean decisionReady,
      String profitNote,
      /** 销量侧取到了吗？false 时下面三个销量列一律是 null。 */
      boolean salesAvailable,
      String salesNote,
      @Schema(nullable = true) BigDecimal salesQuantityBefore,
      @Schema(nullable = true) BigDecimal salesQuantityAfter,
      @Schema(nullable = true) BigDecimal salesQuantityDelta) {}

  /** 对比视图的分页结果。 */
  public record PriceHistoryComparisonPage(
      List<PriceHistoryComparisonRow> items, long total, int page, int size, boolean salesAvailable, String note) {}

  /** 对比视图的查询条件。 */
  public record PriceHistoryComparisonQuery(
      String keyword,
      @Schema(nullable = true) UUID skuId,
      String platformCode,
      String siteCode,
      @Schema(nullable = true) LocalDate windowFrom,
      @Schema(nullable = true) LocalDate windowTo,
      /** 销量对比窗口（变更前后各取多少天）。 */
      int salesWindowDays,
      int page,
      int size) {}

  // ================================================================ 原因字典

  /** 一条变更原因。 */
  public record PriceHistoryReasonRow(
      UUID id,
      String code,
      String label,
      String category,
      String categoryLabel,
      boolean enabled,
      int sortOrder,
      String note,
      OffsetDateTime updatedAt) {}

  /** 保存原因字典项（按 code 覆盖）。 */
  public record PriceHistoryReasonInput(
      @NotBlank @Size(max = 64) String code,
      @NotBlank @Size(max = 100) String label,
      @NotBlank String category,
      Boolean enabled,
      Integer sortOrder,
      @Schema(nullable = true) @Size(max = 500) String note) {}

  // ================================================================ 登记入口

  /**
   * 人工登记一次价格变更。
   *
   * <p>🔴 {@code oldPrice} 可空 = **首次定价**（此前这个 listing 没有价）。
   * 它**不是**「从 0 涨到新价」—— 那会让时间轴上凭空多出一跳 100% 的涨幅。
   */
  public record PriceHistoryChangeInput(
      @NotNull UUID skuId,
      @NotBlank @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9_-]{0,31}$") String platformCode,
      @NotBlank @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9_-]{0,31}$") String siteCode,
      @NotBlank @Pattern(regexp = "^[A-Za-z]{3}") String currency,
      @Schema(nullable = true) BigDecimal oldPrice,
      @NotNull BigDecimal newPrice,
      @NotBlank String changeSource,
      @Schema(nullable = true) @Size(max = 64) String reasonCode,
      @NotBlank @Size(max = 500) String reasonText,
      @NotNull OffsetDateTime effectiveAt,
      @Schema(nullable = true) BigDecimal quantity,
      @Schema(nullable = true) @Pattern(regexp = "^[A-Z]{3}") String costCurrency,
      @Schema(nullable = true) BigDecimal fxRate,
      @Schema(nullable = true) BigDecimal platformFeeRate,
      @Schema(nullable = true) BigDecimal platformFeeFixed,
      @Schema(nullable = true) BigDecimal logisticsCostPerUnit,
      @Schema(nullable = true) BigDecimal monthlyFixedCost,
      @Schema(nullable = true) LocalDate costWindowFrom,
      @Schema(nullable = true) LocalDate costWindowTo,
      @Schema(nullable = true) @Size(max = 500) String note) {}

  /** 登记结果。 */
  public record PriceHistoryChangeSaved(UUID id, UUID skuId, String platformCode, String siteCode, boolean duplicated, String note) {}

  // ================================================================ 销量

  /**
   * 一段区间的销量事实。
   *
   * @param quantity 销量（数量）。**没有**销量事实时是 null，绝不是 0。
   * @param amount 销售额。与 quantity 一样，取不到就是 null。
   */
  public record PriceHistorySalesTotal(
      UUID skuId,
      String platformCode,
      String siteCode,
      @Schema(nullable = true) BigDecimal quantity,
      @Schema(nullable = true) BigDecimal amount,
      String currency,
      OffsetDateTime from,
      OffsetDateTime to,
      String note) {}
}
