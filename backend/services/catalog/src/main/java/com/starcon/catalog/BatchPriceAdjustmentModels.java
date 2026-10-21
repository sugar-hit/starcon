package com.starcon.catalog;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 5.4.1.2 批量调价：对选中的一批 listing 统一改价（固定金额 / 百分比 / 按模板），
 * **必须先看预览再执行**，预览给出影响范围与反向指标。
 *
 * <p>蓝图原文：
 *
 * <blockquote>
 *
 * **职责**：对选中的一批 listing 统一改价（固定金额/百分比/按模板），支持预览影响范围（涉及多少店铺、多少商品、预计利润变化）。
 *
 * **输入**：目标集合；调价参数；预览确认。
 *
 * **输出**：批量调价单 + 影响预览报告。
 *
 * **关系**：上游 = 5.3.1.2、4.1.1.1；下游 = 5.4.2.1。**必须先看预览再执行**，预览要包含"改完后预计亏损的商品数"这类反向指标。
 *
 * </blockquote>
 *
 * <h2>🔴 本模块的三条纪律</h2>
 *
 * <ol>
 *   <li><b>🔴 零个测算算法</b>：改价前后的毛利额与毛利率全部来自 5.3.1.1 的引擎
 *       （{@link ProfitCalculatorService#calculate}）；「按模板」的建议价来自 5.3.2.1 的
 *       {@link PricingTemplateService#apply}。本模块只做「范围 → 新价 → 前后对比 → 汇总」。</li>
 *   <li><b>🔴 预览无副作用</b>：预览**不落库**，它只算出一个「预览令牌」（内容哈希）。
 *       执行时服务端**重算**一次并逐字比对令牌 —— 对不上说明「预览之后目标集合或价格变了」，
 *       此时**拒绝执行**，而不是照着过期的数字改价。</li>
 *   <li><b>🔴 null 绝不等于 0</b>：成本不完整时毛利额与毛利率是 <b>null</b>（不可信），
 *       亏损标记也是 <b>null</b>；「没测准」与「确实是 0」是两件事。</li>
 * </ol>
 */
public final class BatchPriceAdjustmentModels {

  private BatchPriceAdjustmentModels() {}

  // ================================================================ 词表

  /** 调价方式（三选一，DB CHECK 钉死互斥）。 */
  public enum AdjustMode {
    /** 每个 listing 加/减同一个固定金额（可负 = 降价）。 */
    FIXED_AMOUNT("固定金额"),
    /** 每个 listing 乘 (1 + rate)（rate 是小数口径，0.1 = +10%）。 */
    PERCENT("百分比"),
    /** 用 5.3.2.1 的定价模板算建议价当新价。 */
    TEMPLATE("按模板");

    private final String label;

    AdjustMode(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static AdjustMode parse(String raw) {
      if (raw == null) return null;
      for (var m : values()) {
        if (m.name().equalsIgnoreCase(raw.trim())) return m;
      }
      return null;
    }
  }

  /** 明细行的状态。 */
  public enum ItemStatus {
    /** 会被改价（新旧价与前后利润都有值）。 */
    WILL_ADJUST("将调价"),
    /** 被跳过（skip_reason 写明为什么，不是空串）。 */
    SKIPPED("已跳过");

    private final String label;

    ItemStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static ItemStatus parse(String raw) {
      if (raw == null) return null;
      for (var s : values()) {
        if (s.name().equalsIgnoreCase(raw.trim())) return s;
      }
      return null;
    }
  }

  /** 明细排序。 */
  public enum Sort {
    /** 价差升序（跌得最多的排前面）—— 默认。 */
    DELTA_ASC("价差 从低到高"),
    /** 价差降序。 */
    DELTA_DESC("价差 从高到低"),
    /** 旧价升序。 */
    OLD_PRICE_ASC("旧价 从低到高"),
    /** 旧价降序。 */
    OLD_PRICE_DESC("旧价 从高到低"),
    /** 改价后毛利率升序（最差的排前面）。 */
    MARGIN_ASC("改价后毛利率 从低到高"),
    /** 改价后毛利率降序。 */
    MARGIN_DESC("改价后毛利率 从高到低");

    private final String label;

    Sort(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static Sort parse(String raw) {
      if (raw == null) return null;
      for (var s : values()) {
        if (s.name().equalsIgnoreCase(raw.trim())) return s;
      }
      return null;
    }
  }

  // ================================================================ 输入

  /**
   * 目标店铺（平台 + 站点）。
   *
   * <p>平台码与站点码沿用 2.1.1.1 平台目录的同一套约定：**字符串码 + 不建码表副本**。
   */
  public record ShopRef(
      @NotBlank(message = "必须指定平台") String platformCode,
      @NotBlank(message = "必须指定店铺（站点）") String siteCode) {}

  /**
   * 批量调价的参数（预览与执行**共用**同一份）。
   *
   * <p>🔴 {@code adjustRate} 是**小数口径**（0.1 = +10%）：写成 10 会被读成 +1000%。
   * 🔴 {@code adjustAmount} **可负**（负数 = 降价）—— 调价方向由正负号表达。
   * 🔴 跨币种（售价币种 ≠ 成本币种）时 {@code fxRate} 必填 —— 绝不按 1:1 折算。
   */
  public record BatchPriceAdjustmentRequest(
      /** 调价方式（三选一）。 */
      @NotNull(message = "必须指定调价方式") AdjustMode adjustMode,
      /** FIXED_AMOUNT：每个 listing 加/减的固定金额（可负 = 降价）。 */
      @Schema(nullable = true) @DecimalMin(value = "0", inclusive = false) BigDecimal adjustAmount,
      /** PERCENT：涨跌幅（小数口径，0.1 = +10%，可负 = 降价）。 */
      @Schema(nullable = true)
          @DecimalMin(value = "-0.999999")
          @DecimalMax(value = "9.999999")
          BigDecimal adjustRate,
      /** TEMPLATE：定价模板 id（5.3.2.1）。 */
      @Schema(nullable = true) UUID templateId,
      /** 售价币种（ISO 4217 三位大写码）= 店铺的计价币种。 */
      @NotNull @Pattern(regexp = "^[A-Za-z]{3}$", message = "币种必须是三位大写字母") String currency,
      /** 成本币种。空 = 与售价同币种。 */
      @Pattern(regexp = "^[A-Za-z]{3}$", message = "币种必须是三位大写字母") String costCurrency,
      /** 汇率：1 成本币种 = fx_rate 售价币种。跨币种时必填。 */
      @Schema(nullable = true) @DecimalMin(value = "0", inclusive = false) BigDecimal fxRate,
      /** 目标店铺（可以多个 —— 预览报告里的「涉及多少店铺」就是这个数）。 */
      @NotNull(message = "必须指定目标店铺") List<@Valid ShopRef> shops,
      /** 显式选中的 SKU。空 = 不按 SKU 限定。 */
      @Schema(nullable = true) List<UUID> skuIds,
      /** 分类（spu.category 精确匹配）。空 = 不限定。 */
      String category,
      /** 品牌（spu.brand 精确匹配）。空 = 不限定。 */
      String brand,
      /** 数量（用于阶梯与运费）。空 = 1。 */
      @Schema(nullable = true) @DecimalMin(value = "0", inclusive = false) BigDecimal quantity,
      /** 平台费率（售价的小数比例，0.15 = 15%）。🔴 5.2.3.1 未交付 ⇒ 手工假设。 */
      @Schema(nullable = true)
          @DecimalMin(value = "0")
          @DecimalMax(value = "0.999999")
          BigDecimal platformFeeRate,
      /** 平台固定费（每单固定金额）。🔴 5.2.3.1 未交付 ⇒ 手工假设。 */
      @Schema(nullable = true) @DecimalMin(value = "0") BigDecimal platformFeeFixed,
      /** 物流每单位运费。🔴 5.2.3.2 未交付 ⇒ 手工假设。 */
      @Schema(nullable = true) @DecimalMin(value = "0") BigDecimal logisticsCostPerUnit,
      /** 月度固定成本（盈亏平衡销量的固定费用基数）。 */
      @Schema(nullable = true) @DecimalMin(value = "0") BigDecimal monthlyFixedCost,
      /** 成本口径窗口起（含）。空 = 默认最近 90 天。 */
      @Schema(nullable = true) LocalDate costWindowFrom,
      /** 成本口径窗口止（含）。空 = 今天。 */
      @Schema(nullable = true) LocalDate costWindowTo,
      /**
       * 当前可售天数（仅「按模板」方式用）：模板配了按库存周转调整的规则时必填。
       *
       * <p>🔴 上游 7 库存 / 09 订单没有给本服务可读端点 ⇒ 这一项只能由人填（手工假设）。
       */
      @Schema(nullable = true) @DecimalMin(value = "0") BigDecimal daysOfCover,
      /** 竞品基准价（仅「按模板」方式用；临时覆盖模板上登记的值）。 */
      @Schema(nullable = true) @DecimalMin(value = "0", inclusive = false)
          BigDecimal competitorReferencePrice,
      /** 竞品价币种（仅「按模板」方式用；临时覆盖模板上登记的值）。 */
      @Schema(nullable = true) @Pattern(regexp = "^[A-Za-z]{3}$", message = "币种必须是三位大写字母")
          String competitorCurrency,
      /** 备注：这一批调价是为了什么。 */
      String note) {}

  /**
   * 执行批量调价（{@code POST /catalog/batch-price-adjustments}）。
   *
   * <p>🔴 {@code previewToken} 是**预览确认**（蓝图的输入句：「目标集合；调价参数；**预览确认**」）：
   * 它必须是 {@code POST /preview} 返回的那一个。服务端在执行时**重算**一遍预览并逐字比对，
   * 对不上 ⇒ 409 拒绝（目标集合或价格在预览之后变了）。
   */
  public record BatchPriceAdjustmentExecuteRequest(
      @NotNull @Valid BatchPriceAdjustmentRequest request,
      @NotBlank(message = "必须先预览并带上预览确认令牌") String previewToken) {}

  // ================================================================ 能力声明

  /**
   * 能力声明。
   *
   * <p>页面顶部固定显示它 —— 蓝图那句「**必须先看预览再执行**」意味着运营会拿预览里的
   * 数字去决定「这批到底改不改」，所以**口径必须写在数字旁边**。
   */
  public record BatchPriceAdjustmentCapabilities(
      List<ProfitCalculatorModels.SiteOption> sites,
      String listingDefinitionNote,
      String priceSourceNote,
      String upstreamNote,
      String downstreamNote,
      String previewGateNote,
      String reverseMetricNote,
      String roundingNote,
      String skipNote,
      String limitNote,
      String permissionNote) {}

  // ================================================================ 预览

  /**
   * 影响预览报告（蓝图的输出之一：「批量调价单 + **影响预览报告**」）。
   *
   * <p>🔴 这份报告**在预览时不落库**：预览无副作用。确认执行时，它与调价单一起写入。
   */
  public record BatchPriceAdjustmentPreview(
      /** 预览令牌：执行时必须原样带回。 */
      String previewToken,
      AdjustMode adjustMode,
      String adjustModeLabel,
      @Schema(nullable = true) BigDecimal adjustAmount,
      @Schema(nullable = true) BigDecimal adjustRate,
      @Schema(nullable = true) UUID templateId,
      @Schema(nullable = true) String templateCode,
      @Schema(nullable = true) String templateName,
      String currency,
      String costCurrency,
      @Schema(nullable = true) BigDecimal fxRate,
      /** 涉及多少店铺。 */
      int shopCount,
      /** 目标店铺（编码成「平台码:站点码」）。 */
      List<String> shops,
      /** 涉及多少商品（去重 SKU 数）。 */
      int skuCount,
      /** 目标 listing 数（SKU × 店铺）。 */
      int listingCount,
      /** 其中会被改价的。 */
      int willAdjustCount,
      /** 其中被跳过的。 */
      int skippedCount,
      /** 🔴 反向指标：改价前的亏损 listing 数。 */
      int lossBeforeCount,
      /** 🔴 反向指标：改价**后**的亏损 listing 数。 */
      int lossAfterCount,
      /** 🔴 反向指标：改价**后**预计亏损的**商品**数（至少一个 listing 亏损的去重 SKU）。 */
      int lossAfterSkuCount,
      /** 改价前的毛利额合计。 */
      @Schema(nullable = true) BigDecimal profitBeforeTotal,
      /** 改价后的毛利额合计。 */
      @Schema(nullable = true) BigDecimal profitAfterTotal,
      /** 🔴 预计利润变化（改价后 − 改价前）。 */
      @Schema(nullable = true) BigDecimal profitDeltaTotal,
      /** 改价前的平均毛利率。 */
      @Schema(nullable = true) BigDecimal avgMarginBefore,
      /** 改价后的平均毛利率。 */
      @Schema(nullable = true) BigDecimal avgMarginAfter,
      List<BatchPriceAdjustmentItem> items,
      String note) {}

  // ================================================================ 调价单

  /**
   * 一次批量调价（列表与详情共用同一个形状）。
   *
   * <p>🔴 汇总数字是**预览报告的快照**，与明细行在同一次写入里产生，所以两者不会漂移。
   */
  public record BatchPriceAdjustmentDetail(
      UUID id,
      AdjustMode adjustMode,
      String adjustModeLabel,
      @Schema(nullable = true) BigDecimal adjustAmount,
      @Schema(nullable = true) BigDecimal adjustRate,
      @Schema(nullable = true) UUID templateId,
      @Schema(nullable = true) String templateCode,
      @Schema(nullable = true) String templateName,
      String currency,
      String costCurrency,
      @Schema(nullable = true) BigDecimal fxRate,
      String scopeCategory,
      String scopeBrand,
      String scopeSkuIds,
      String scopeShops,
      BigDecimal quantity,
      @Schema(nullable = true) BigDecimal platformFeeRate,
      @Schema(nullable = true) BigDecimal platformFeeFixed,
      @Schema(nullable = true) BigDecimal logisticsCostPerUnit,
      @Schema(nullable = true) BigDecimal monthlyFixedCost,
      @Schema(nullable = true) LocalDate costWindowFrom,
      @Schema(nullable = true) LocalDate costWindowTo,
      int shopCount,
      int skuCount,
      int listingCount,
      int willAdjustCount,
      int skippedCount,
      int lossBeforeCount,
      int lossAfterCount,
      int lossAfterSkuCount,
      @Schema(nullable = true) BigDecimal profitBeforeTotal,
      @Schema(nullable = true) BigDecimal profitAfterTotal,
      @Schema(nullable = true) BigDecimal profitDeltaTotal,
      @Schema(nullable = true) BigDecimal avgMarginBefore,
      @Schema(nullable = true) BigDecimal avgMarginAfter,
      String previewToken,
      String note,
      @Schema(nullable = true) String createdBy,
      OffsetDateTime createdAt) {}

  /** 调价单列表（分页）。 */
  public record BatchPriceAdjustmentPage(
      List<BatchPriceAdjustmentDetail> items, int page, int size, long total) {}

  /**
   * 调价单明细的一行（= 目标集合里的一个 listing）。
   *
   * <p>🔴 {@code status='SKIPPED'} 时新旧价与前后利润都可能是 <b>null</b>；
   * 🔴 {@code decisionReady=false} 时前后利润与毛利率都是 <b>null</b>（不可信，不是 0），
   * 亏损标记也是 <b>null</b>。
   */
  public record BatchPriceAdjustmentItem(
      UUID id,
      UUID adjustmentId,
      UUID skuId,
      String skuCode,
      String skuName,
      String platformCode,
      String siteCode,
      String currency,
      ItemStatus status,
      String skipReason,
      @Schema(nullable = true) BigDecimal oldPrice,
      @Schema(nullable = true) BigDecimal newPrice,
      @Schema(nullable = true) BigDecimal priceDelta,
      @Schema(nullable = true) String priceSource,
      @Schema(nullable = true) OffsetDateTime priceObservedAt,
      @Schema(nullable = true) BigDecimal grossProfitBefore,
      @Schema(nullable = true) BigDecimal grossMarginBefore,
      @Schema(nullable = true) BigDecimal grossProfitAfter,
      @Schema(nullable = true) BigDecimal grossMarginAfter,
      boolean decisionReady,
      @Schema(nullable = true) Boolean lossBefore,
      @Schema(nullable = true) Boolean lossAfter) {}

  /** 明细列表（分页）。 */
  public record BatchPriceAdjustmentItemPage(
      List<BatchPriceAdjustmentItem> items, int page, int size, long total) {}

  /** 明细筛选（真筛选在数据库层）。 */
  public record ItemQuery(
      /** 状态筛选：WILL_ADJUST / SKIPPED / 空 = 全部。 */
      @Schema(nullable = true) String status,
      /** 亏损筛选：LOSS_AFTER / LOSS_BEFORE / 空 = 全部。 */
      @Schema(nullable = true) String loss,
      /** 关键字（SKU 编码 / 名称模糊匹配）。 */
      @Schema(nullable = true) String keyword,
      /** 排序。 */
      @Schema(nullable = true) String sort,
      int page,
      int size) {}
}
