package com.starcon.finance;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 12.6.1.1 税率配置的传输模型。
 *
 * <p>蓝图 12-finance.md 原文：
 *
 * <blockquote>
 *
 * 职责：维护税率配置：目的国/地区的销售税率、VAT 税率、IOSS 适用税率、代扣代缴税；支持按店铺站点绑定默认税率与税码。 输入：税率定义；适用站点；生效时间。 输出：税率记录；供订单与申报引用。 关系：上游 = 2 店铺站点、16 合规；下游 = 9 订单（税额）、12.6.1.2 申报。<b>税率必须带生效时间</b>，历史订单用当时税率，不能被新政策追溯修改。
 *
 * </blockquote>
 *
 * <p><b>命名。</b>统一用 {@code FinanceTaxRate*} 前缀，与既有服务的类型名零重叠 —— 契约被 codegen
 * 合并进一个扁平文件，同名后者覆盖前者且不报冲突（见 tools/codegen/catalog-contracts.py 的跨服务重名检测）。
 *
 * <p><b>🔴 税率是用户自己的税务事实，系统零预置行。</b>本文件里的 {@link FinanceTaxRateTypeOption}
 * 只是**四个税种的词表**（值 + 名称 + 口径说明），服务层把它们摆出来，
 * 落库的每一行税率都是租户自己录的 —— 系统替用户默认一个税率就是替用户记账。
 *
 * <p><b>🔴 税率一律 BigDecimal + NUMERIC</b>：本文件里没有一个 {@code double}。
 * 税率是小数率 {@code numeric(9,6)}（0.190000 = 19%）。
 *
 * <p><b>🔴 税率不带币种列</b>：税率是一个**比例**，作用于任何币种的应税基数；
 * 币种属于税额（金额）那一侧，由 12.6.1.2 申报模块与订单侧负责。
 */
public final class TaxRateModels {

  private TaxRateModels() {}

  /** 税率小数位（税率列是 numeric(9,6)）。 */
  public static final int RATE_SCALE = 6;

  // ================================================================ 枚举

  /**
   * 税种（蓝图点名的四种）。
   *
   * <p>🔴 <b>这里面没有美国州销售税</b>：州级销售税归 channel 16.3.2.3 维护，
   * 本模块的 {@code SALES_TAX} 指的是**目的国/地区**的销售税率。
   */
  public enum TaxType {
    /** 目的国/地区的销售税率。 */
    SALES_TAX(
        "目的国/地区销售税率",
        "目的国/地区对进口订单征的**销售税**（不是美国州销售税 —— 州销售税归 channel 16.3.2.3）。"
            + "按目的国/地区的口径登记，税码与税率一并维护。"),
    /** 增值税。 */
    VAT(
        "增值税",
        "目的国/地区的增值税（VAT）。税率与税码是**版本化**的："
            + "政策改税率时追加新版本，历史订单仍按当时那版算。"),
    /** IOSS 适用税率。 */
    IOSS(
        "IOSS 适用税率",
        "欧盟进口一站式商店（IOSS）下的适用税率。IOSS 与 VAT 是**两条不同的税线**，"
            + "不能混成一条。"),
    /** 代扣代缴税。 */
    WITHHOLDING(
        "代扣代缴税",
        "平台或代扣方在结算时替卖家扣缴的税（withholding tax）。"
            + "🔴 本模块只维护**税率口径**，不记「谁代扣了多少」—— 那属渠道侧的结算与合规申报。");

    private final String label;
    private final String basis;

    TaxType(String label, String basis) {
      this.label = label;
      this.basis = basis;
    }

    public String label() {
      return label;
    }

    public String basis() {
      return basis;
    }

    /** 解析税种取值；非法取值返回 {@code null}（由服务层给一句能直接展示的话）。 */
    public static TaxType parse(String raw) {
      if (raw == null || raw.isBlank()) return null;
      for (var t : values()) {
        if (t.name().equalsIgnoreCase(raw.trim())) return t;
      }
      return null;
    }
  }

  /** 税率版本状态。 */
  public enum TaxRateStatus {
    /** 生效中（同一 税种 × 辖区 同时只有一个）。 */
    ACTIVE("生效中"),
    /** 已被新版本取代（历史，保留供版本对比与订单取数）。 */
    SUPERSEDED("已被取代"),
    /** 已撤下（终态，不生效）。 */
    ARCHIVED("已撤下");

    private final String label;

    TaxRateStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 解析状态取值；非法取值返回 {@code null}（页面原样显示，不显示 undefined）。 */
    public static TaxRateStatus parse(String raw) {
      if (raw == null || raw.isBlank()) return null;
      for (var s : values()) {
        if (s.name().equalsIgnoreCase(raw.trim())) return s;
      }
      return null;
    }
  }

  // ================================================================ 能力声明

  /** 能力条目：每一维说清「有没有、依据是什么、缺了会怎样」。 */
  public record FinanceTaxRateCapability(
      String module,
      String name,
      boolean available,
      String basis,
      String ownerModule,
      String detail) {}

  /** 下游缺口：谁没交付、归谁。 */
  public record FinanceTaxRateGap(
      String module,
      String name,
      String ownerService,
      boolean available,
      String group,
      String detail) {}

  /** 12.6.1.1 的能力声明。 */
  public record FinanceTaxRateCapabilities(
      List<FinanceTaxRateCapability> capabilities, List<FinanceTaxRateGap> downstreamGaps) {}

  // ================================================================ 选项

  /**
   * 一个税种选项（值 + 名称 + 口径说明）。
   *
   * <p>🔴 这是「系统提供选项」的那一半：系统把四个税种摆出来，**不替用户选税率**。
   */
  public record FinanceTaxRateTypeOption(String value, String label, String basis) {}

  /** 筛选项下拉（词表来自本模块自己；税码来自真实数据）。 */
  public record FinanceTaxRateOptions(
      List<FinanceTaxRateTypeOption> taxTypes, List<String> statuses, List<String> taxCodes) {}

  /**
   * 一个辖区选项。
   *
   * @param usableForTaxRate 本模块能不能用它录税率（州级销售税辖区归 channel，不可用）
   * @param unusableReason 不可用时的原因（**如实写明**，页面上照着显示）
   */
  public record FinanceTaxJurisdictionOption(
      String code,
      String name,
      String region,
      String countryCode,
      String subdivisionCode,
      String taxRegime,
      boolean vatRequired,
      boolean requiresEpr,
      String defaultFilingCycle,
      String derivationScope,
      boolean usableForTaxRate,
      String unusableReason) {}

  /**
   * 辖区选项清单。
   *
   * @param available 上游读到了吗（false ⇒ 界面显示「读不到」，**不是**「没有辖区」）
   */
  public record FinanceTaxJurisdictionOptions(
      List<FinanceTaxJurisdictionOption> items, int total, boolean available, String note) {}

  /**
   * 一个店铺站点选项（channel 2.3.1.1 店铺目录）。
   *
   * @param available 上游读到了吗
   */
  public record FinanceTaxShopOption(
      String id,
      String code,
      String name,
      String platformCode,
      String platformName,
      String status,
      boolean available,
      String note) {}

  /** 店铺站点选项清单。 */
  public record FinanceTaxShopOptions(
      List<FinanceTaxShopOption> items, int total, boolean available, String note) {}

  // ================================================================ 税率记录

  /** 一条税率记录（版本链上的一版）。 */
  public record FinanceTaxRateRow(
      UUID id,
      String taxType,
      String taxTypeLabel,
      String jurisdictionCode,
      String jurisdictionName,
      String countryCode,
      String taxCode,
      BigDecimal rate,
      OffsetDateTime effectiveFrom,
      @Schema(nullable = true) OffsetDateTime effectiveTo,
      String status,
      String statusLabel,
      int version,
      @Schema(nullable = true) UUID supersedesId,
      String note,
      @Schema(nullable = true) UUID createdBy,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /**
   * 登记 / 换税率（同一 税种 × 辖区 已有生效版本 ⇒ **追加新版本**）。
   *
   * <p>⚠️ {@code effectiveFrom} 是<b>必填</b>：没有生效时间的税率等于没有 ——
   * 上线之后改税率，会把历史订单的税额变成另一个口径，而那笔订单没人能解释。
   */
  public record FinanceTaxRateInput(
      String taxType,
      String jurisdictionCode,
      String taxCode,
      BigDecimal rate,
      OffsetDateTime effectiveFrom,
      String note) {}

  /** 税率记录列表页（真分页 + 真筛选）。 */
  public record FinanceTaxRatePage(
      List<FinanceTaxRateRow> items, int page, int size, long total, long activeCount) {}

  /** 版本对比的一行差异。 */
  public record FinanceTaxRateDiff(String field, String fieldLabel, String fromValue, String toValue, boolean changed) {}

  /**
   * 版本对比（改税率前 / 后）。
   *
   * <p>⚠️ 对比的是<b>税率配置本身</b>，**不是**税额 —— 税额属 9 订单与 12.6.1.2。
   */
  public record FinanceTaxRateComparison(
      String taxType,
      String jurisdictionCode,
      String jurisdictionName,
      FinanceTaxRateRow from,
      FinanceTaxRateRow to,
      List<FinanceTaxRateDiff> differences,
      boolean rateChanged,
      String note) {}

  // ================================================================ 店铺绑定

  /** 一条店铺站点绑定（版本链上的一版）。 */
  public record FinanceTaxBindingRow(
      UUID id,
      UUID shopId,
      String shopCode,
      String shopName,
      String taxType,
      String taxTypeLabel,
      String jurisdictionCode,
      String jurisdictionName,
      String taxCode,
      BigDecimal rate,
      OffsetDateTime effectiveFrom,
      @Schema(nullable = true) OffsetDateTime effectiveTo,
      String status,
      String statusLabel,
      int version,
      @Schema(nullable = true) UUID supersedesId,
      String note,
      OffsetDateTime createdAt) {}

  /** 绑定 / 换绑（同一 店铺 × 税种 × 辖区 已有生效绑定 ⇒ **追加新版本**）。 */
  public record FinanceTaxBindingInput(
      UUID shopId,
      String shopCode,
      String shopName,
      String taxType,
      String jurisdictionCode,
      String taxCode,
      OffsetDateTime effectiveFrom,
      String note) {}

  /** 绑定列表页（真分页 + 真筛选）。 */
  public record FinanceTaxBindingPage(
      List<FinanceTaxBindingRow> items, int page, int size, long total, long activeCount) {}

  // ================================================================ 按生效时间取税率

  /**
   * 「某一时点生效的税率」一次命中。
   *
   * <p>🔴 命中不到就报错（服务层抛 404 / 409），**绝不退回最新一条**、**绝不按 0% 处理** ——
   * 「这个时点没有税率」和「这个时点税率是 0」是两件完全不同的事。
   */
  public record FinanceTaxResolution(
      /** 命中方式：{@code SHOP_BINDING}（按店铺站点绑定）/ {@code JURISDICTION}（按辖区直接取）。 */
      String resolvedBy,
      String resolvedByLabel,
      UUID taxRateId,
      String taxType,
      String taxTypeLabel,
      String jurisdictionCode,
      String jurisdictionName,
      String taxCode,
      BigDecimal rate,
      @Schema(nullable = true) UUID shopId,
      @Schema(nullable = true) String shopCode,
      @Schema(nullable = true) UUID bindingId,
      @Schema(nullable = true) Integer bindingVersion,
      OffsetDateTime asOf,
      String note) {}

  // ================================================================ 州销售税（channel 只读引用）

  /**
   * 一版州销售税规则（**来自 channel 16.3.2.3，本模块只读引用**）。
   *
   * <p>🔴 这不是本模块的税率记录，也不是本模块写的 —— 它是 channel 的
   * {@code sales_tax_rate_rule}。放在这里是为了让页面**一处**说清
   * 「美国州销售税归 channel，这里只读」。
   */
  public record FinanceTaxStateRateRule(
      String id,
      String stateCode,
      String stateName,
      int ruleVersion,
      String effectiveFrom,
      @Schema(nullable = true) String effectiveTo,
      BigDecimal baseRate,
      BigDecimal thresholdAmount,
      BigDecimal exemptionAmount,
      boolean marketplaceFacilitator,
      String filingCycle,
      String status,
      String note) {}

  /**
   * 州销售税规则清单（只读引用）。
   *
   * @param available channel 读到了吗（false ⇒ 页面显示「读不到」，**不是**「没有州销售税」）
   */
  public record FinanceTaxStateRateRules(
      List<FinanceTaxStateRateRule> items,
      long total,
      boolean available,
      String sourceModule,
      String sourceService,
      String note) {}
}
