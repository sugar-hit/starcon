package com.starcon.catalog;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 5.3.2.2 价格规则：把「什么时候该改价、改成什么价」沉淀成规则，并产出**命中记录**与
 * **建议调价列表**。
 *
 * <p>蓝图原文：
 *
 * <blockquote>
 *
 * **职责**：定义自动调价规则：触发条件（库存低于阈值/竞品降价/成本上涨/毛利低于下限/库龄超期）+ 动作（降价/涨价/暂停促销/下架），支持优先级与生效时段。
 *
 * **输入**：规则条件与动作；作用范围；生效时段。
 *
 * **输出**：规则记录 + 命中记录 + 建议调价列表。
 *
 * **关系**：上游 = 7 库存、5.3.2.1；下游 = 5.4.1.1。**自动调价必须先"建议"再"执行"**，高风险规则（降价超过 X%）需人工确认，避免错误规则批量改价造成损失。
 *
 * </blockquote>
 *
 * <p><b>🔴 三条不可让步的纪律</b>：
 *
 * <ol>
 *   <li><b>🔴 先建议，再执行</b>。本模块产出的是<b>建议调价</b>，不是生效价 ——
 *       改价是 5.4.1.1 调价单的写入所有权。「执行」在本模块里 = 把建议放行给下游。
 *   <li><b>🔴 高风险必须人工确认</b>。降价幅度超过规则上的阈值（{@code maxAutoPriceDropRate}）、
 *       或者成本不完整（复核不了毛利）、或者是非价格动作（暂停促销 / 下架）——
 *       三者任一成立都<b>不自动放行</b>。
 *   <li><b>🔴 没有数据就不判定</b>。取不到当前价、取不到库存、取不到成本版本 ——
 *       记一条<b>未命中</b>的留痕并写明原因，<b>不</b>当成 0、<b>不</b>猜一个阈值。
 * </ol>
 */
public final class PricingRuleModels {

  private PricingRuleModels() {}

  // ================================================================ 词表

  /** 触发条件（蓝图职责句点名的五类）。每类的阈值列互斥，由 DB 的 CHECK 钉死。 */
  public enum TriggerType {
    /** 库存低于阈值：可售量低于阈值 ⇒ 命中（数据来自 7 库存）。 */
    INVENTORY_BELOW_THRESHOLD("库存低于阈值"),
    /** 竞品降价：市场价下降幅度超过阈值 ⇒ 命中。 */
    COMPETITOR_PRICE_DROP("竞品降价"),
    /** 成本上涨：单位成本涨幅超过阈值 ⇒ 命中。 */
    COST_INCREASE("成本上涨"),
    /** 毛利低于下限：当前价下的毛利率低于下限 ⇒ 命中。 */
    MARGIN_BELOW_FLOOR("毛利低于下限"),
    /** 库龄超期：最老一批的库龄超过阈值 ⇒ 命中。 */
    AGING_OVER_THRESHOLD("库龄超期");

    private final String label;

    TriggerType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static TriggerType parse(String raw) {
      if (raw == null) return null;
      for (var t : values()) {
        if (t.name().equalsIgnoreCase(raw.trim())) return t;
      }
      return null;
    }
  }

  /** 动作（蓝图职责句点名的四类）。 */
  public enum ActionType {
    /** 降价：建议价 = 当前价 × (1 − 幅度)。 */
    LOWER_PRICE("降价"),
    /** 涨价：建议价 = 当前价 × (1 + 幅度)。 */
    RAISE_PRICE("涨价"),
    /** 暂停促销：非价格动作。 */
    PAUSE_PROMOTION("暂停促销"),
    /** 下架：非价格动作。 */
    DELIST("下架");

    private final String label;

    ActionType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 这个动作改的是价格吗？非价格动作（暂停促销 / 下架）一律要人工确认。 */
    public boolean priceAction() {
      return this == LOWER_PRICE || this == RAISE_PRICE;
    }

    public static ActionType parse(String raw) {
      if (raw == null) return null;
      for (var a : values()) {
        if (a.name().equalsIgnoreCase(raw.trim())) return a;
      }
      return null;
    }
  }

  /** 作用范围的类型（蓝图输入句：「作用范围」）。 */
  public enum ScopeType {
    /** 内部分类（product_category.code）。 */
    CATEGORY("分类"),
    /** 品牌（SPU 的品牌字段，存的是品牌编码）。 */
    BRAND("品牌"),
    /** 店铺（2.1.1.1 平台目录里的站点码）。 */
    SHOP("店铺"),
    /** 单个 SKU（sku.code，租户内唯一）。 */
    SKU("SKU");

    private final String label;

    ScopeType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static ScopeType parse(String raw) {
      if (raw == null) return null;
      for (var t : values()) {
        if (t.name().equalsIgnoreCase(raw.trim())) return t;
      }
      return null;
    }
  }

  /** 建议调价的状态机（「先建议再执行」的落点）。 */
  public enum SuggestionStatus {
    /** 高风险：等人工确认。 */
    PENDING_CONFIRMATION("待人工确认"),
    /** 低风险：已放行，可被 5.4.1.1 消费。 */
    READY("已放行"),
    /** 人工确认放行。 */
    CONFIRMED("已确认"),
    /** 人工驳回。 */
    REJECTED("已驳回");

    private final String label;

    SuggestionStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static SuggestionStatus parse(String raw) {
      if (raw == null) return null;
      for (var s : values()) {
        if (s.name().equalsIgnoreCase(raw.trim())) return s;
      }
      return null;
    }
  }

  /** 人工决策（确认 / 驳回）。 */
  public enum Decision {
    /** 确认放行。 */
    CONFIRM("确认"),
    /** 驳回。 */
    REJECT("驳回");

    private final String label;

    Decision(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static Decision parse(String raw) {
      if (raw == null) return null;
      for (var d : values()) {
        if (d.name().equalsIgnoreCase(raw.trim())) return d;
      }
      return null;
    }
  }

  // ================================================================ 输入

  /** 一条作用范围（输入）。 */
  public record PricingRuleScopeInput(@NotNull ScopeType type, @NotNull String ref, String platformCode) {}

  /**
   * 规则定义（新建与修改共用同一个形状）。
   *
   * <p><b>修改是整体替换</b>：作用范围在同一个事务里「要么全换要么全不换」。
   */
  public record PricingRuleInput(
      @NotNull String code,
      @NotNull String name,
      String description,
      @NotNull TriggerType triggerType,
      /** 库存阈值（件）。仅 {@code INVENTORY_BELOW_THRESHOLD} 必填。 */
      @Schema(nullable = true) @DecimalMin(value = "0") BigDecimal thresholdQuantity,
      /** 涨跌幅阈值（**小数口径**：0.1 = 10%）。竞品降价 / 成本上涨必填。 */
      @Schema(nullable = true)
          @DecimalMin(value = "0", inclusive = false)
          @DecimalMax(value = "0.999999")
          BigDecimal thresholdRate,
      /** 毛利下限（**小数口径**：0.2 = 20%）。仅 {@code MARGIN_BELOW_FLOOR} 必填。 */
      @Schema(nullable = true)
          @DecimalMin(value = "0")
          @DecimalMax(value = "0.999999")
          BigDecimal marginFloorRate,
      /** 库龄阈值（天）。仅 {@code AGING_OVER_THRESHOLD} 必填。 */
      @Schema(nullable = true) @DecimalMin(value = "0") Integer agingDaysThreshold,
      @NotNull ActionType actionType,
      /** 调价幅度（**小数口径**：0.05 = 5%）。降价 / 涨价必填，其余动作必须为空。 */
      @Schema(nullable = true)
          @DecimalMin(value = "0", inclusive = false)
          @DecimalMax(value = "0.999999")
          BigDecimal actionRate,
      /** 优先级：数字小者优先。 */
      @Schema(nullable = true) Integer priority,
      @NotNull @Pattern(regexp = "^[A-Za-z]{3}$", message = "币种必须是三位大写字母") String currency,
      /** 自动放行的最大降价幅度（**小数口径**：0.1 = 10%）。超过它 ⇒ 需人工确认。 */
      @Schema(nullable = true)
          @DecimalMin(value = "0")
          @DecimalMax(value = "0.999999")
          BigDecimal maxAutoPriceDropRate,
      Boolean enabled,
      @NotNull OffsetDateTime effectiveFrom,
      @Schema(nullable = true) OffsetDateTime effectiveTo,
      String note,
      @Valid List<PricingRuleScopeInput> scopes,
      /** 乐观锁版本（修改时必填；新建时忽略）。 */
      Long version) {}

  /**
   * 一次规则评估的请求。
   *
   * <p>🔴 {@code platformFeeRate} / {@code platformFeeFixed} / {@code logisticsCostPerUnit} /
   * {@code fxRate} 是 **5.3.1.1 的手工假设口径**（5.2.3.1 / 5.2.3.2 / 5.2.2.x 未并入测算路径），
   * 它们不是「留空就按 0 处理」：缺了它们，5.3.1.1 会把毛利率判为「不可信」，
   * 于是本模块既判不了「毛利低于下限」，也复核不了建议价的毛利 ——
   * 后者会让高风险建议**无法自动放行**（这是故意的）。
   */
  public record EvaluateRequest(
      @NotNull List<UUID> skuIds,
      /** 只跑这一条规则（空 = 跑全部当前生效的规则）。 */
      @Schema(nullable = true) UUID ruleId,
      /** 目标店铺（站点码）。 */
      @Schema(nullable = true) String siteCode,
      /** 目标平台码。 */
      @Schema(nullable = true) String platformCode,
      @NotNull @Pattern(regexp = "^[A-Za-z]{3}$", message = "币种必须是三位大写字母") String currency,
      /** 成本币种。空 = 与售价同币种。 */
      @Schema(nullable = true) @Pattern(regexp = "^[A-Za-z]{3}$", message = "币种必须是三位大写字母")
          String costCurrency,
      /** 数量（用于阶梯与运费）。空 = 1。 */
      @Schema(nullable = true) @DecimalMin(value = "0", inclusive = false) BigDecimal quantity,
      /** 汇率：1 成本币种 = fxRate 售价币种。跨币种时必填（绝不按 1:1 折算）。 */
      @Schema(nullable = true) @DecimalMin(value = "0", inclusive = false) BigDecimal fxRate,
      /** 手工假设：平台费率（售价的小数比例）。 */
      @Schema(nullable = true)
          @DecimalMin(value = "0")
          @DecimalMax(value = "0.999999")
          BigDecimal platformFeeRate,
      /** 手工假设：平台固定费（每单固定金额）。 */
      @Schema(nullable = true) @DecimalMin(value = "0") BigDecimal platformFeeFixed,
      /** 手工假设：物流每单位运费。 */
      @Schema(nullable = true) @DecimalMin(value = "0") BigDecimal logisticsCostPerUnit,
      String note) {}

  /** 人工确认 / 驳回一条建议调价。 */
  public record DecideRequest(
      @NotNull Decision decision,
      /** 决策说明（驳回时强烈建议写清楚为什么）。 */
      String note) {}

  // ================================================================ 输出

  /** 规则列表的一行。 */
  public record PricingRuleSummary(
      UUID id,
      String code,
      String name,
      TriggerType triggerType,
      String triggerTypeLabel,
      ActionType actionType,
      String actionTypeLabel,
      @Schema(nullable = true) BigDecimal thresholdQuantity,
      @Schema(nullable = true) BigDecimal thresholdRate,
      @Schema(nullable = true) BigDecimal marginFloorRate,
      @Schema(nullable = true) Integer agingDaysThreshold,
      @Schema(nullable = true) BigDecimal actionRate,
      int priority,
      String currency,
      BigDecimal maxAutoPriceDropRate,
      boolean enabled,
      OffsetDateTime effectiveFrom,
      @Schema(nullable = true) OffsetDateTime effectiveTo,
      int scopeCount,
      @Schema(nullable = true) String createdBy,
      OffsetDateTime updatedAt) {}

  /** 一条作用范围。 */
  public record PricingRuleScopeView(
      UUID id, ScopeType type, String typeLabel, String ref, @Schema(nullable = true) String platformCode) {}

  /** 规则详情 = 规则本身 + 作用范围。 */
  public record PricingRuleDetail(
      PricingRuleSummary summary,
      String description,
      TriggerType triggerType,
      @Schema(nullable = true) BigDecimal thresholdQuantity,
      @Schema(nullable = true) BigDecimal thresholdRate,
      @Schema(nullable = true) BigDecimal marginFloorRate,
      @Schema(nullable = true) Integer agingDaysThreshold,
      ActionType actionType,
      @Schema(nullable = true) BigDecimal actionRate,
      int priority,
      String currency,
      BigDecimal maxAutoPriceDropRate,
      boolean enabled,
      OffsetDateTime effectiveFrom,
      @Schema(nullable = true) OffsetDateTime effectiveTo,
      String note,
      long version,
      List<PricingRuleScopeView> scopes,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 规则列表（分页）。 */
  public record PricingRulePage(List<PricingRuleSummary> items, int page, int size, long total) {}

  /** 一条命中记录。 */
  public record HitView(
      UUID id,
      UUID ruleId,
      String ruleCode,
      String ruleName,
      UUID skuId,
      @Schema(nullable = true) String skuCode,
      @Schema(nullable = true) String skuName,
      UUID spuId,
      TriggerType triggerType,
      String triggerTypeLabel,
      boolean matched,
      boolean superseded,
      @Schema(nullable = true) UUID supersededByRuleId,
      @Schema(nullable = true) String supersededByRuleCode,
      String reason,
      /** 判定依据（当时的观测值与阈值）。 */
      @Schema(nullable = true) java.util.Map<String, Object> evidence,
      OffsetDateTime evaluatedAt) {}

  /** 命中记录（分页）。 */
  public record HitPage(List<HitView> items, int page, int size, long total) {}

  /** 一条建议调价。 */
  public record SuggestionView(
      UUID id,
      UUID ruleId,
      String ruleCode,
      String ruleName,
      UUID hitId,
      UUID skuId,
      @Schema(nullable = true) String skuCode,
      @Schema(nullable = true) String skuName,
      UUID spuId,
      @Schema(nullable = true) String siteCode,
      @Schema(nullable = true) String platformCode,
      String currency,
      ActionType actionType,
      String actionTypeLabel,
      @Schema(nullable = true) BigDecimal currentPrice,
      @Schema(nullable = true) BigDecimal suggestedPrice,
      @Schema(nullable = true) BigDecimal priceChangeRate,
      boolean requiresConfirmation,
      SuggestionStatus status,
      String statusLabel,
      boolean decisionReady,
      @Schema(nullable = true) BigDecimal grossMarginRate,
      @Schema(nullable = true) BigDecimal grossProfit,
      String reason,
      @Schema(nullable = true) String decidedBy,
      @Schema(nullable = true) OffsetDateTime decidedAt,
      String decisionNote,
      OffsetDateTime createdAt) {}

  /** 建议调价列表（分页）。 */
  public record SuggestionPage(List<SuggestionView> items, int page, int size, long total) {}

  /**
   * 一次评估的结果。
   *
   * <p>🔴 {@code suggestions} 里装的是**建议价**，不是生效价 —— 改价是 5.4.1.1 的写入所有权。
   */
  public record EvaluationResult(
      int evaluatedSkuCount,
      int evaluatedRuleCount,
      int hitCount,
      int suggestionCount,
      int pendingConfirmationCount,
      /** 本次评估的判定留痕（命中与未命中都在）。 */
      List<HitView> hits,
      /** 本次评估产出的建议调价。 */
      List<SuggestionView> suggestions,
      String note) {}

  // ================================================================ 目录与能力声明

  /** 分类选项（内部分类树）。 */
  public record PricingRuleCategoryOption(String code, String name, @Schema(nullable = true) String parentCode) {}

  /** 品牌选项。 */
  public record PricingRuleBrandOption(String code, String name, String authorizationStatus) {}

  /** 作用范围目录（分类 + 品牌）。 */
  public record PricingRuleDirectory(
      List<PricingRuleCategoryOption> categories, List<PricingRuleBrandOption> brands, String sourceNote) {}

  /** 能力声明里的一个词表项。 */
  public record PricingRuleCapabilityItem(String code, String label, String description, List<String> requiredFields) {}

  /**
   * 能力声明。
   *
   * <p>页面固定显示它 —— 蓝图那句「自动调价必须先"建议"再"执行"」必须写在脸上，
   * 否则运营会把「建议调价」当成「已经改好的价」。
   */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  public record PricingRuleCapabilities(
      List<PricingRuleCapabilityItem> triggerTypes,
      List<PricingRuleCapabilityItem> actionTypes,
      List<PricingRuleCapabilityItem> scopeTypes,
      List<PricingRuleCapabilityItem> statuses,
      String upstreamNote,
      String downstreamNote,
      String suggestFirstNote,
      String competitorNote,
      String priorityNote,
      String assumptionNote,
      String sourceNote,
      String actionNote) {}
}
