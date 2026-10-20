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
 * 5.3.2.1 定价模板：把定价策略沉淀成可复用的模板，并「一键应用到商品生成建议价」。
 *
 * <p>蓝图原文：
 *
 * <blockquote>
 *
 * **职责**：沉淀定价策略模板：成本加成、固定毛利、竞品跟随（若允许）、按站点差异化、按库存周转调整，新品与老品用不同模板。
 *
 * **输入**：模板定义（基准 + 调整项）；适用对象（分类/品牌/店铺）。
 *
 * **输出**：模板记录；一键应用到商品生成建议价。
 *
 * **关系**：上游 = 5.3.1.1；下游 = 5.4.1.1。**模板与价目表的关系**：模板是"怎么算"，价目表是"算完是多少"。
 *
 * </blockquote>
 *
 * <p><b>🔴 三条不可让步的纪律</b>：
 *
 * <ol>
 *   <li><b>建议价必须来自 5.3.1.1 的真实测算</b>。本模块不重写任何一套扣减公式：
 *       固定毛利走 5.3.1.1 的目标毛利求解，成本加成的基数取 5.3.1.1 瀑布图里的单位成本合计，
 *       竞品跟随以人工登记的竞品价为起点。<b>「编一个建议价」在本模块是不允许的</b> ——
 *       下游 5.4.1.1 调价单会程序化消费它。
 *   <li><b>成本不完整 ⇒ 不给建议价</b>（不是给一个虚高的价）。5.3.1.1 在扣减不全时
 *       把毛利率 / 目标毛利价置 null，本模块照抄：加成基数缺项时**拒绝**产出建议价，
 *       并把 5.3.1.1 的缺口清单原样带回。
 *   <li><b>模板是「怎么算」，价目表是「算完是多少」</b>。本模块只落模板与一次应用留痕，
 *       <b>不建价目表</b>（5.1.1.2 的写入所有权），<b>不改价</b>（5.4.1.1 的写入所有权）。
 * </ol>
 */
public final class PricingTemplateModels {

  private PricingTemplateModels() {}

  // ================================================================ 词表

  /** 基准方法（模板「怎么算」的第一层）。三种互斥，由 DB 的 CHECK 钉死。 */
  public enum BaseMethod {
    /** 成本加成：建议价 = 单位成本合计 × (1 + 加成率)。 */
    COST_PLUS("成本加成"),
    /** 固定毛利：建议价 = 5.3.1.1 按目标毛利率解出来的目标毛利价。 */
    FIXED_MARGIN("固定毛利"),
    /** 竞品跟随：建议价 = 竞品基准价 × (1 + 跟随率)。竞品价是**人工登记**的输入。 */
    COMPETITOR_FOLLOW("竞品跟随");

    private final String label;

    BaseMethod(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static BaseMethod parse(String raw) {
      if (raw == null) return null;
      for (var m : values()) {
        if (m.name().equalsIgnoreCase(raw.trim())) return m;
      }
      return null;
    }
  }

  /** 商品阶段：新品与老品用不同模板（蓝图职责句点名）。 */
  public enum ProductStage {
    /** 新品：所属 SPU 尚未上架（DRAFT / PENDING_LISTING）。 */
    NEW("新品"),
    /** 老品：所属 SPU 已上架 / 暂停 / 停产。 */
    EXISTING("老品");

    private final String label;

    ProductStage(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static ProductStage parse(String raw) {
      if (raw == null) return null;
      for (var s : values()) {
        if (s.name().equalsIgnoreCase(raw.trim())) return s;
      }
      return null;
    }
  }

  /** 调整项类型。 */
  public enum AdjustmentType {
    /** 按站点差异化：给某个站点的基准率加一个增量。 */
    SITE_DIFFERENTIAL("按站点差异化"),
    /** 按库存周转调整：可售天数超过阈值 ⇒ 建议价打折。 */
    INVENTORY_TURNOVER("按库存周转调整");

    private final String label;

    AdjustmentType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static AdjustmentType parse(String raw) {
      if (raw == null) return null;
      for (var t : values()) {
        if (t.name().equalsIgnoreCase(raw.trim())) return t;
      }
      return null;
    }
  }

  /** 适用对象的类型（蓝图输入句：分类 / 品牌 / 店铺）。 */
  public enum ScopeType {
    /** 内部分类（product_category.code）。 */
    CATEGORY("分类"),
    /** 品牌（brand.code）。 */
    BRAND("品牌"),
    /** 店铺（2.1.1.1 平台目录里的站点码）。 */
    SHOP("店铺");

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

  // ================================================================ 输入

  /** 一个调整项（输入）。两类调整项各有专属列，其余列必须为空。 */
  public record TemplateAdjustmentInput(
      @NotNull AdjustmentType type,
      @Schema(nullable = true) String siteCode,
      @Schema(nullable = true) BigDecimal deltaRate,
      @Schema(nullable = true) BigDecimal daysCoverThreshold,
      @Schema(nullable = true) BigDecimal discountRate,
      String note) {}

  /** 一条适用对象（输入）。 */
  public record ScopeInput(@NotNull ScopeType type, @NotNull String ref, String platformCode) {}

  /**
   * 模板定义（新建与修改共用同一个形状）。
   *
   * <p><b>修改是整体替换</b>：调整项与适用对象在同一个事务里「要么全换要么全不换」
   * （与 5.2.3.1 的阶梯整体替换同一手法）。
   */
  public record TemplateInput(
      @NotNull String code,
      @NotNull String name,
      String description,
      @NotNull BaseMethod baseMethod,
      /** 基准率（**小数口径**：0.25 = 25%）。取值域随基准方法而变。 */
      @Schema(nullable = true)
          @DecimalMin(value = "0")
          @DecimalMax(value = "9.999999")
          BigDecimal baseRate,
      /** 竞品基准价（仅竞品跟随时必填）：人工登记的竞品售价。 */
      @Schema(nullable = true) @DecimalMin(value = "0", inclusive = false) BigDecimal competitorReferencePrice,
      @Schema(nullable = true) @Pattern(regexp = "^[A-Za-z]{3}$", message = "币种必须是三位大写字母")
          String competitorCurrency,
      @NotNull ProductStage productStage,
      @NotNull @Pattern(regexp = "^[A-Za-z]{3}$", message = "币种必须是三位大写字母") String currency,
      /** 平台费率（手工假设，5.2.3.1 未接管）。 */
      @Schema(nullable = true)
          @DecimalMin(value = "0")
          @DecimalMax(value = "0.999999")
          BigDecimal platformFeeRate,
      /** 平台固定费（手工假设）。 */
      @Schema(nullable = true) @DecimalMin(value = "0") BigDecimal platformFeeFixed,
      /** 物流每单位运费（手工假设，5.2.3.2 未接管）。 */
      @Schema(nullable = true) @DecimalMin(value = "0") BigDecimal logisticsCostPerUnit,
      /** 成本币种。空 = 与售价同币种。 */
      @Schema(nullable = true) @Pattern(regexp = "^[A-Za-z]{3}$", message = "币种必须是三位大写字母")
          String costCurrency,
      /** 汇率：1 成本币种 = fx_rate 售价币种。跨币种时必填（绝不按 1:1 折算）。 */
      @Schema(nullable = true) @DecimalMin(value = "0", inclusive = false) BigDecimal fxRate,
      Boolean enabled,
      @NotNull OffsetDateTime effectiveFrom,
      @Schema(nullable = true) OffsetDateTime effectiveTo,
      String note,
      @Valid List<TemplateAdjustmentInput> adjustments,
      @Valid List<ScopeInput> scopes,
      /** 乐观锁版本（修改时必填；新建时忽略）。 */
      Long version) {}

  /**
   * 「一键应用」的请求。
   *
   * <p>🔴 这里的每一个可空字段都是**口径输入**，不是「留空就按 0 处理」：
   * 竞品价、可售天数、平台费率、物流单价、汇率。
   * 模板上有的口径这里可以**临时覆盖**（一次性的改价谈判用），
   * 没覆盖的用模板上的；两边都没有 ⇒ 那一项按「取不到」处理，**不是 0**。
   */
  public record ApplyRequest(
      @NotNull UUID skuId,
      @Schema(nullable = true) String siteCode,
      @Schema(nullable = true) String platformCode,
      /** 建议价币种。空 = 用模板上的币种。 */
      @Schema(nullable = true) @Pattern(regexp = "^[A-Za-z]{3}$", message = "币种必须是三位大写字母")
          String currency,
      /** 数量（用于阶梯与运费）。空 = 1。 */
      @Schema(nullable = true) @DecimalMin(value = "0", inclusive = false) BigDecimal quantity,
      /** 竞品基准价（临时覆盖模板上的登记值）。 */
      @Schema(nullable = true) @DecimalMin(value = "0", inclusive = false) BigDecimal competitorReferencePrice,
      /** 竞品价币种（临时覆盖）。 */
      @Schema(nullable = true) @Pattern(regexp = "^[A-Za-z]{3}$", message = "币种必须是三位大写字母")
          String competitorCurrency,
      /** 当前可售天数（库存周转调整用）。上游 7 库存 / 09 订单未提供可读端点 ⇒ 人工填。 */
      @Schema(nullable = true) @DecimalMin(value = "0") BigDecimal daysOfCover,
      /** 平台费率（临时覆盖）。 */
      @Schema(nullable = true)
          @DecimalMin(value = "0")
          @DecimalMax(value = "0.999999")
          BigDecimal platformFeeRate,
      /** 平台固定费（临时覆盖）。 */
      @Schema(nullable = true) @DecimalMin(value = "0") BigDecimal platformFeeFixed,
      /** 物流每单位运费（临时覆盖）。 */
      @Schema(nullable = true) @DecimalMin(value = "0") BigDecimal logisticsCostPerUnit,
      /** 成本币种（临时覆盖）。 */
      @Schema(nullable = true) @Pattern(regexp = "^[A-Za-z]{3}$", message = "币种必须是三位大写字母")
          String costCurrency,
      /** 汇率（临时覆盖）。方向显式：1 成本币种 = fx_rate 售价币种。 */
      @Schema(nullable = true) @DecimalMin(value = "0", inclusive = false) BigDecimal fxRate,
      String note) {}

  // ================================================================ 输出

  /** 模板列表的一行。 */
  public record TemplateSummary(
      UUID id,
      String code,
      String name,
      BaseMethod baseMethod,
      String baseMethodLabel,
      @Schema(nullable = true) BigDecimal baseRate,
      ProductStage productStage,
      String productStageLabel,
      String currency,
      @Schema(nullable = true) BigDecimal competitorReferencePrice,
      boolean enabled,
      OffsetDateTime effectiveFrom,
      @Schema(nullable = true) OffsetDateTime effectiveTo,
      int adjustmentCount,
      int scopeCount,
      @Schema(nullable = true) String createdBy,
      OffsetDateTime updatedAt) {}

  /** 一个调整项。 */
  public record AdjustmentView(
      UUID id,
      AdjustmentType type,
      String typeLabel,
      @Schema(nullable = true) String siteCode,
      @Schema(nullable = true) BigDecimal deltaRate,
      @Schema(nullable = true) BigDecimal daysCoverThreshold,
      @Schema(nullable = true) BigDecimal discountRate,
      String note) {}

  /** 一条适用对象。 */
  public record ScopeView(
      UUID id, ScopeType type, String typeLabel, String ref, @Schema(nullable = true) String platformCode) {}

  /** 模板详情 = 模板本身 + 调整项 + 适用对象。 */
  public record TemplateDetail(
      TemplateSummary summary,
      String description,
      BaseMethod baseMethod,
      @Schema(nullable = true) BigDecimal baseRate,
      @Schema(nullable = true) BigDecimal competitorReferencePrice,
      @Schema(nullable = true) String competitorCurrency,
      ProductStage productStage,
      String currency,
      @Schema(nullable = true) BigDecimal platformFeeRate,
      @Schema(nullable = true) BigDecimal platformFeeFixed,
      @Schema(nullable = true) BigDecimal logisticsCostPerUnit,
      @Schema(nullable = true) String costCurrency,
      @Schema(nullable = true) BigDecimal fxRate,
      boolean enabled,
      OffsetDateTime effectiveFrom,
      @Schema(nullable = true) OffsetDateTime effectiveTo,
      String note,
      long version,
      List<AdjustmentView> adjustments,
      List<ScopeView> scopes,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 模板列表（分页）。 */
  public record TemplatePage(List<TemplateSummary> items, int page, int size, long total) {}

  /**
   * 「一键应用」的结果。
   *
   * <p>🔴 {@code suggestedPrice} 是**建议价**，不是生效价 —— 改价是 5.4.1.1 调价单的写入所有权。
   *
   * <p>🔴 {@code decisionReady=false} 时 {@code grossMarginRate} / {@code grossProfit} 必为
   * <b>null</b>（不是 0、不是估算值）：那是 5.3.1.1 的决策就绪闸的原样延续。
   */
  public record ApplyResult(
      UUID applicationId,
      UUID templateId,
      String templateCode,
      String templateName,
      UUID skuId,
      @Schema(nullable = true) String skuCode,
      @Schema(nullable = true) String skuName,
      @Schema(nullable = true) String siteCode,
      @Schema(nullable = true) String platformCode,
      String currency,
      BigDecimal quantity,
      BaseMethod baseMethod,
      String baseMethodLabel,
      /** 生效基准率（含站点差异化后的值）。 */
      @Schema(nullable = true) BigDecimal effectiveRate,
      /** 命中的调整项说明（哪一条、为什么命中 / 为什么没命中）。 */
      List<AppliedAdjustment> appliedAdjustments,
      /** 建议价。null = 成本不完整，**拒绝**产出（见纪律二）。 */
      @Schema(nullable = true) BigDecimal suggestedPrice,
      boolean decisionReady,
      @Schema(nullable = true) BigDecimal grossMarginRate,
      @Schema(nullable = true) BigDecimal grossProfit,
      /** 5.3.1.1 的完整测算结果（瀑布图 / 缺口清单 / 来源）—— 建议价的依据。 */
      @Schema(nullable = true) ProfitCalculatorModels.ProfitCalculation calculation,
      String note,
      OffsetDateTime createdAt) {}

  /** 一条被应用的调整项（含「为什么命中 / 为什么没命中」）。 */
  public record AppliedAdjustment(
      AdjustmentType type,
      String typeLabel,
      @Schema(nullable = true) String siteCode,
      @Schema(nullable = true) BigDecimal deltaRate,
      @Schema(nullable = true) BigDecimal daysCoverThreshold,
      @Schema(nullable = true) BigDecimal discountRate,
      boolean applied,
      String reason) {}

  /** 应用留痕的一行。 */
  public record ApplicationSummary(
      UUID id,
      UUID templateId,
      String templateCode,
      UUID skuId,
      @Schema(nullable = true) String skuCode,
      @Schema(nullable = true) String siteCode,
      String currency,
      BigDecimal quantity,
      BaseMethod baseMethod,
      @Schema(nullable = true) BigDecimal effectiveRate,
      @Schema(nullable = true) BigDecimal suggestedPrice,
      boolean decisionReady,
      @Schema(nullable = true) BigDecimal grossMarginRate,
      OffsetDateTime createdAt,
      @Schema(nullable = true) String appliedBy) {}

  /** 应用留痕（分页）。 */
  public record ApplicationPage(List<ApplicationSummary> items, int page, int size, long total) {}

  // ================================================================ 目录与能力声明

  /** 分类选项（内部分类树）。 */
  public record CategoryOption(String code, String name, @Schema(nullable = true) String parentCode) {}

  /** 品牌选项。 */
  public record BrandOption(String code, String name, String authorizationStatus) {}

  /** 适用对象目录（分类 + 品牌；店铺来自 {@code /sites}）。 */
  public record TemplateDirectory(
      List<CategoryOption> categories, List<BrandOption> brands, String sourceNote) {}

  /** 能力声明里的一个词表项。 */
  public record CapabilityItem(String code, String label, String description, List<String> requiredFields) {}

  /**
   * 能力声明。
   *
   * <p>页面固定显示它 —— 蓝图那句「模板是"怎么算"，价目表是"算完是多少"」必须写在脸上，
   * 否则运营会把「建议价」当成「已经改好的价」。
   */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  public record PricingTemplateCapabilities(
      List<CapabilityItem> baseMethods,
      List<CapabilityItem> adjustmentTypes,
      List<CapabilityItem> scopeTypes,
      List<CapabilityItem> productStages,
      String upstreamNote,
      String downstreamNote,
      String priceListNote,
      String competitorNote,
      String turnoverNote,
      String assumptionNote,
      String stageNote,
      String applyNote,
      String sourceNote) {}
}
