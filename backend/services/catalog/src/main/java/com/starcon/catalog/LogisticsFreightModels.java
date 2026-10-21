package com.starcon.catalog;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 5.2.3.2 物流与头程费用：物流渠道的计费规则（按重量 / 体积 / 件数、燃油附加、旺季附加、
 * 偏远附加）与头程费用结构，以及实际运费这个独立事实 —— 三者并列，差额现算不落库。
 *
 * <p>蓝图原文：
 *
 * <blockquote>
 *
 * **职责**：维护物流渠道的计费规则（按重量 / 体积 / 件数、燃油附加、旺季附加、偏远附加）与头程费用结构，用于测算运费占成本比。
 *
 * **输入**：渠道计费规则 / 附加费参数 / 头程运输方式费用。
 *
 * **输出**：费用规则集 + 测算输入。
 *
 * **关系**：上游 = 10 物流与头程（卷级引用）；下游 = 5.3.1.1 测算。
 *
 * </blockquote>
 *
 * <p><b>🔴 本模块的三条不可让步的纪律</b>：
 *
 * <ol>
 *   <li><b>规则与实际是两个事实</b>（{@code logistics_freight_rule} /
 *       {@code logistics_freight_actual} 两张表）。合并成一个「运费」列就再也答不出
 *       「是规则定错了，还是物流商多收了」—— 而那正是测算运费占成本比时最该被看见的一件事。
 *   <li><b>算不出来就是算不出来</b>：没有生效规则、规则落不进档、附加费币种不一致，
 *       一律表达成 {@code null} + 一句原因，**绝不**用 0 冒充。
 *   <li><b>已生效的规则与档位不可改写</b>：改规则 = 新增一条新版本 + 停用旧版本。
 * </ol>
 *
 * <p><b>⚠️ 上游没有实际运费结算事实</b>：channel 的 {@code freight_quote.cost_basis}
 * 恒为 {@code RULE_ESTIMATE}（按规则估的报价，不是物流商的结算单），
 * trade / warehouse 侧也没有可用的实际运费结算表。所以实际运费现在的<b>唯一</b>来源是
 * <b>人工登记的观察值</b>（{@code source='MANUAL_OBSERVATION'}）。能力声明里
 * 「10 的实际运费对账」维度显式标成 {@code available=false} + {@code ownerModule=10}。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class LogisticsFreightModels {

  private LogisticsFreightModels() {}

  // ================================================================ 词表

  /**
   * 计费口径：按重量 / 体积 / 件数。
   *
   * <p>三种口径**互斥**：同时填两个时「这一票按什么计」没有答案，
   * 而服务层只会安静地挑一个用。互斥由 DB 层的 CHECK 钉死。
   */
  public enum ChargeBasis {
    /** 按重量（kg）：计费重 = 实重；配了体积重换算系数时取 max(实重, 体积重)。 */
    BY_WEIGHT("按重量", "按重量计费，计费量单位是公斤。"),
    /** 按体积（m³）：计费量 = 长 × 宽 × 高（cm³）÷ 1,000,000。 */
    BY_VOLUME("按体积", "按体积计费，计费量单位是立方米。"),
    /** 按件数：计费量 = 件数。 */
    BY_PIECE("按件数", "按件数计费，计费量单位是件。");

    private final String label;
    private final String basis;

    ChargeBasis(String label, String basis) {
      this.label = label;
      this.basis = basis;
    }

    public String label() {
      return label;
    }

    public String basis() {
      return basis;
    }
  }

  /** 附加费类型。枚举**只增不改**：它会进历史快照的口径对照。 */
  public enum SurchargeType {
    /** 燃油附加（按基础运费的一个比例）。 */
    FUEL("燃油附加", "承运商按油价上浮收取的附加费，经典口径是基础运费的一个百分比。"),
    /** 旺季附加（每单一个固定额）。 */
    PEAK_SEASON("旺季附加", "旺季（双十一 / 黑色星期五 / 圣诞）期间每单加收的固定额。"),
    /** 偏远附加（每件或每单一个固定额）。 */
    REMOTE_AREA("偏远附加", "派送到偏远区域加收的固定额。"),
    /** 其它附加费。 */
    OTHER("其它附加费", "不属于以上分类的附加费。");

    private final String label;
    private final String basis;

    SurchargeType(String label, String basis) {
      this.label = label;
      this.basis = basis;
    }

    public String label() {
      return label;
    }

    public String basis() {
      return basis;
    }
  }

  /** 附加费的计算口径。 */
  public enum SurchargeBasis {
    /** 基础运费的一个比例（费率是小数口径：0.15 = 15%）。 */
    PERCENT_OF_BASE_FREIGHT("基础运费比例", "附加费 = 基础运费 × 费率（费率是小数口径，0.15 = 15%）。"),
    /** 每单一个固定额。 */
    FIXED_PER_ORDER("每单固定额", "附加费 = 固定额（每票一次）。"),
    /** 每件一个固定额。 */
    FIXED_PER_UNIT("每件固定额", "附加费 = 固定额 × 件数。");

    private final String label;
    private final String basis;

    SurchargeBasis(String label, String basis) {
      this.label = label;
      this.basis = basis;
    }

    public String label() {
      return label;
    }

    public String basis() {
      return basis;
    }
  }

  /** 档位的计价形态。 */
  public enum PriceMode {
    /** 整档一口价：落在该档就一个价。 */
    FLAT_PER_TIER("整档一口价", "落在该档的计费量按一个固定价收（不看具体多重 / 多大）。"),
    /** 首段 + 续段：首段一个价，超出首段按续段单价。 */
    FIRST_PLUS_ADDITIONAL("首段加续段", "首段（首重 / 首件）一个价，超出首段的部分按续段单价。");

    private final String label;
    private final String basis;

    PriceMode(String label, String basis) {
      this.label = label;
      this.basis = basis;
    }

    public String label() {
      return label;
    }

    public String basis() {
      return basis;
    }
  }

  /** 头程成本项类型。 */
  public enum HeadComponentType {
    /** 主运费（海运 / 空运 / 快递的干线运费）。 */
    LINE_HAUL("主运费", "干线运输费（海运 / 空运 / 快递的干线段）。"),
    /** 报关费。 */
    CUSTOMS("报关费", "报关与清关费用。"),
    /** 拖车 / 提送柜费。 */
    TRUCKING("拖车费", "码头与仓库之间的拖车 / 提送柜费用。"),
    /** 保险费。 */
    INSURANCE("保险费", "货运保险费。"),
    /** 操作 / 装卸费。 */
    HANDLING("操作费", "装卸、分拣等操作费用。"),
    /** 其它成本项。 */
    OTHER("其它成本项", "不属于以上分类的头程成本项。");

    private final String label;
    private final String basis;

    HeadComponentType(String label, String basis) {
      this.label = label;
      this.basis = basis;
    }

    public String label() {
      return label;
    }

    public String basis() {
      return basis;
    }
  }

  /** 头程成本项的计价基数。 */
  public enum HeadCalcBasis {
    /** 每票（整票一个固定额）。 */
    PER_SHIPMENT("每票", "成本项 = 单位费用 × 票数（整票一个固定额）。"),
    /** 每立方米。 */
    PER_CBM("每立方米", "成本项 = 单位费用 × 立方米数。"),
    /** 每柜。 */
    PER_CONTAINER("每柜", "成本项 = 单位费用 × 柜数。"),
    /** 每公斤。 */
    PER_KG("每公斤", "成本项 = 单位费用 × 公斤数。");

    private final String label;
    private final String basis;

    HeadCalcBasis(String label, String basis) {
      this.label = label;
      this.basis = basis;
    }

    public String label() {
      return label;
    }

    public String basis() {
      return basis;
    }
  }

  /** 版本状态。 */
  public enum VersionStatus {
    /** 生效中。 */
    ACTIVE("生效中", "当前生效的版本。"),
    /** 已被新版本取代。 */
    SUPERSEDED("已被取代", "已被新版本取代，历史测算仍按当时生效的版本算。"),
    /** 已停用。 */
    DISABLED("已停用", "已被租户停用，不再参与测算。");

    private final String label;
    private final String basis;

    VersionStatus(String label, String basis) {
      this.label = label;
      this.basis = basis;
    }

    public String label() {
      return label;
    }

    public String basis() {
      return basis;
    }
  }

  // ================================================================ 能力声明

  /**
   * 能力声明里的一个维度。
   *
   * <p>{@code available=false} 表示**取不到 / 未交付**，**不是**「值为 0」。
   * {@code ownerModule} 点名归属模块，{@code impact} 说清缺了它会怎样。
   */
  public record LogisticsFreightCapabilityDimension(
      @Schema(description = "维度码") String code,
      @Schema(description = "维度名") String label,
      @Schema(description = "是否已交付 / 可取到") boolean available,
      @Schema(description = "归属模块（未交付时点名）") String ownerModule,
      @Schema(description = "缺了它会怎样") String impact,
      @Schema(description = "口径说明") String basis,
      @Schema(description = "备注") String note) {}

  /** 词表项。 */
  public record FreightOption(
      @Schema(description = "取值") String value,
      @Schema(description = "中文名") String label,
      @Schema(description = "口径说明") String basis) {}

  /**
   * 能力声明：四张词表 + 六个维度（每个维度写明取不到没有 / 归谁管）+ 七条口径说明。
   *
   * <p>页面固定把它显示在顶部。
   */
  public record LogisticsFreightCapabilities(
      @Schema(description = "计费口径词表") List<FreightOption> chargeBases,
      @Schema(description = "附加费类型词表") List<FreightOption> surchargeTypes,
      @Schema(description = "附加费计算口径词表") List<FreightOption> surchargeBases,
      @Schema(description = "档位计价形态词表") List<FreightOption> priceModes,
      @Schema(description = "头程成本项类型词表") List<FreightOption> headComponentTypes,
      @Schema(description = "头程成本项计价基数词表") List<FreightOption> headCalcBases,
      @Schema(description = "逐维度能力声明") List<LogisticsFreightCapabilityDimension> dimensions,
      @Schema(description = "渠道计费规则口径说明") String ruleBasis,
      @Schema(description = "附加费口径说明") String surchargeBasis,
      @Schema(description = "头程费用结构口径说明") String headBasis,
      @Schema(description = "实际运费口径说明") String actualBasis,
      @Schema(description = "运费差异口径说明") String varianceBasis,
      @Schema(description = "上游说明") String upstreamNote,
      @Schema(description = "下游说明") String downstreamNote,
      @Schema(description = "本模块动作说明") String actionsNote) {}

  // ================================================================ 渠道计费规则

  /** 阶梯的一档。 */
  public record FreightTierRow(
      @Schema(description = "阶梯行 id") UUID id,
      @Schema(description = "档位序号") int seq,
      @Schema(description = "下界（含）") String minValue,
      @Schema(description = "上界（不含，null = 无上限）") String maxValue,
      @Schema(description = "计价形态") String priceMode,
      @Schema(description = "计价形态中文名") String priceModeLabel,
      @Schema(description = "整档一口价") String flatPrice,
      @Schema(description = "首段上限") String firstLimit,
      @Schema(description = "首段单价") String firstPrice,
      @Schema(description = "续段单价") String additionalPrice) {}

  /** 渠道计费规则台账的一行。 */
  public record FreightRuleRow(
      @Schema(description = "规则 id") UUID id,
      @Schema(description = "渠道码") String channelCode,
      @Schema(description = "渠道名（快照）") String channelName,
      @Schema(description = "承运商码（可空）") String carrierCode,
      @Schema(description = "承运商名（快照）") String carrierName,
      @Schema(description = "运输方式码") String shippingMethodCode,
      @Schema(description = "运输方式名（快照）") String shippingMethodName,
      @Schema(description = "计费口径") String chargeBasis,
      @Schema(description = "计费口径中文名") String chargeBasisLabel,
      @Schema(description = "币种") String currency,
      @Schema(description = "体积重换算系数（cm³/kg）") String volumetricDivisor,
      @Schema(description = "最低收费") String minimumCharge,
      @Schema(description = "生效起点") String effectiveFrom,
      @Schema(description = "生效终点（null = 长期有效）") String effectiveTo,
      @Schema(description = "版本号") int versionNo,
      @Schema(description = "上一版本 id") UUID supersedesId,
      @Schema(description = "后继版本 id") UUID supersededById,
      @Schema(description = "状态") String status,
      @Schema(description = "状态中文名") String statusLabel,
      @Schema(description = "停用理由") String disableReason,
      @Schema(description = "备注") String note,
      @Schema(description = "阶梯档数") int tierCount,
      @Schema(description = "创建时间") String createdAt) {}

  /** 规则台账分页结果。 */
  public record FreightRuleListPage(
      @Schema(description = "本页规则") List<FreightRuleRow> items,
      @Schema(description = "总条数") long total,
      @Schema(description = "页码（从 0 起）") int page,
      @Schema(description = "每页条数") int size,
      @Schema(description = "口径说明") String note) {}

  /** 规则明细（规则 + 阶梯 + 口径说明）。 */
  public record FreightRuleDetail(
      @Schema(description = "规则") FreightRuleRow rule,
      @Schema(description = "阶梯（无阶梯时为空数组）") List<FreightTierRow> tiers,
      @Schema(description = "这条规则的作用域口径") String scopeBasis,
      @Schema(description = "说明") String note) {}

  /** 保存规则的结果。 */
  public record FreightRuleSaved(
      @Schema(description = "规则 id") String id,
      @Schema(description = "版本号") int versionNo,
      @Schema(description = "状态") String status,
      @Schema(description = "生效起点") String effectiveFrom) {}

  /** 阶梯的一档（入参）。 */
  public record FreightTierInput(
      @Schema(description = "档位序号（从 1 起）") int seq,
      @Schema(description = "下界（含）") @NotNull @DecimalMin("0") BigDecimal minValue,
      @Schema(description = "上界（不含，null = 无上限）") @DecimalMin("0") BigDecimal maxValue,
      @Schema(description = "计价形态（FLAT_PER_TIER / FIRST_PLUS_ADDITIONAL）") @NotBlank String priceMode,
      @Schema(description = "整档一口价") @DecimalMin("0") BigDecimal flatPrice,
      @Schema(description = "首段上限") @DecimalMin("0") BigDecimal firstLimit,
      @Schema(description = "首段单价") @DecimalMin("0") BigDecimal firstPrice,
      @Schema(description = "续段单价") @DecimalMin("0") BigDecimal additionalPrice) {}

  /** 新建 / 新增版本一条渠道计费规则（规则与阶梯**同事务**写入）。 */
  public record FreightRuleInput(
      @Schema(description = "渠道码（必填）")
          @NotBlank
          @Pattern(regexp = "^[A-Z0-9][A-Z0-9_-]{0,31}$")
          String channelCode,
      @Schema(description = "渠道名（快照）") @Size(max = 128) String channelName,
      @Schema(description = "承运商码（可空）")
          @Pattern(regexp = "^[A-Z0-9][A-Z0-9_-]{0,31}$")
          String carrierCode,
      @Schema(description = "承运商名（快照）") @Size(max = 128) String carrierName,
      @Schema(description = "运输方式码（必填）")
          @NotBlank
          @Pattern(regexp = "^[A-Z0-9][A-Z0-9_-]{0,31}$")
          String shippingMethodCode,
      @Schema(description = "运输方式名（快照）") @Size(max = 128) String shippingMethodName,
      @Schema(description = "计费口径（BY_WEIGHT / BY_VOLUME / BY_PIECE）") @NotBlank String chargeBasis,
      @Schema(description = "币种（必填）") @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String currency,
      @Schema(description = "体积重换算系数（cm³/kg，可空）") @DecimalMin("0") BigDecimal volumetricDivisor,
      @Schema(description = "最低收费（可空）") @DecimalMin("0") BigDecimal minimumCharge,
      @Schema(description = "生效起点（必填）") @NotNull OffsetDateTime effectiveFrom,
      @Schema(description = "生效终点（可空 = 长期有效）") OffsetDateTime effectiveTo,
      @Schema(description = "备注") @Size(max = 500) String note,
      @Schema(description = "阶梯（与规则同事务写入；至少一档）")
          @NotEmpty
          @Valid
          List<FreightTierInput> tiers) {}

  /** 停用理由。 */
  public record FreightDisableInput(@Schema(description = "停用理由（必填）") @NotBlank String reason) {}

  // ================================================================ 附加费

  /** 附加费台账的一行。 */
  public record FreightSurchargeRow(
      @Schema(description = "附加费 id") UUID id,
      @Schema(description = "渠道码") String channelCode,
      @Schema(description = "渠道名（快照）") String channelName,
      @Schema(description = "承运商码（可空）") String carrierCode,
      @Schema(description = "承运商名（快照）") String carrierName,
      @Schema(description = "运输方式码") String shippingMethodCode,
      @Schema(description = "运输方式名（快照）") String shippingMethodName,
      @Schema(description = "附加费类型") String surchargeType,
      @Schema(description = "附加费类型中文名") String surchargeTypeLabel,
      @Schema(description = "计算口径") String calcBasis,
      @Schema(description = "计算口径中文名") String calcBasisLabel,
      @Schema(description = "费率（小数口径）") String rate,
      @Schema(description = "固定额") String fixedAmount,
      @Schema(description = "币种") String currency,
      @Schema(description = "生效起点") String effectiveFrom,
      @Schema(description = "生效终点（null = 长期有效）") String effectiveTo,
      @Schema(description = "版本号") int versionNo,
      @Schema(description = "上一版本 id") UUID supersedesId,
      @Schema(description = "后继版本 id") UUID supersededById,
      @Schema(description = "状态") String status,
      @Schema(description = "状态中文名") String statusLabel,
      @Schema(description = "停用理由") String disableReason,
      @Schema(description = "备注") String note,
      @Schema(description = "创建时间") String createdAt) {}

  /** 附加费台账分页结果。 */
  public record FreightSurchargeListPage(
      @Schema(description = "本页附加费") List<FreightSurchargeRow> items,
      @Schema(description = "总条数") long total,
      @Schema(description = "页码（从 0 起）") int page,
      @Schema(description = "每页条数") int size,
      @Schema(description = "口径说明") String note) {}

  /** 登记一条附加费。 */
  public record FreightSurchargeInput(
      @Schema(description = "渠道码（必填）")
          @NotBlank
          @Pattern(regexp = "^[A-Z0-9][A-Z0-9_-]{0,31}$")
          String channelCode,
      @Schema(description = "渠道名（快照）") @Size(max = 128) String channelName,
      @Schema(description = "承运商码（可空）")
          @Pattern(regexp = "^[A-Z0-9][A-Z0-9_-]{0,31}$")
          String carrierCode,
      @Schema(description = "承运商名（快照）") @Size(max = 128) String carrierName,
      @Schema(description = "运输方式码（必填）")
          @NotBlank
          @Pattern(regexp = "^[A-Z0-9][A-Z0-9_-]{0,31}$")
          String shippingMethodCode,
      @Schema(description = "运输方式名（快照）") @Size(max = 128) String shippingMethodName,
      @Schema(description = "附加费类型（FUEL / PEAK_SEASON / REMOTE_AREA / OTHER）") @NotBlank String surchargeType,
      @Schema(description = "计算口径") @NotBlank String calcBasis,
      @Schema(description = "费率（小数口径，0.15 = 15%）") @DecimalMin("0") BigDecimal rate,
      @Schema(description = "固定额") @DecimalMin("0") BigDecimal fixedAmount,
      @Schema(description = "币种（必填）") @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String currency,
      @Schema(description = "生效起点（必填）") @NotNull OffsetDateTime effectiveFrom,
      @Schema(description = "生效终点（可空 = 长期有效）") OffsetDateTime effectiveTo,
      @Schema(description = "备注") @Size(max = 500) String note) {}

  /** 保存附加费的结果。 */
  public record FreightSurchargeSaved(
      @Schema(description = "附加费 id") String id,
      @Schema(description = "附加费类型") String surchargeType,
      @Schema(description = "版本号") int versionNo,
      @Schema(description = "状态") String status,
      @Schema(description = "生效起点") String effectiveFrom) {}

  // ================================================================ 头程费用结构

  /** 头程成本项的一行。 */
  public record HeadFreightComponentRow(
      @Schema(description = "成本项 id") UUID id,
      @Schema(description = "序号") int seq,
      @Schema(description = "成本项类型") String componentType,
      @Schema(description = "成本项类型中文名") String componentTypeLabel,
      @Schema(description = "人话标签") String label,
      @Schema(description = "计价基数") String calcBasis,
      @Schema(description = "计价基数中文名") String calcBasisLabel,
      @Schema(description = "单位费用") String unitAmount,
      @Schema(description = "币种") String currency) {}

  /** 头程费用结构台账的一行。 */
  public record HeadFreightStructureRow(
      @Schema(description = "结构 id") UUID id,
      @Schema(description = "运输方式码") String shippingMethodCode,
      @Schema(description = "运输方式名（快照）") String shippingMethodName,
      @Schema(description = "起运地码") String originCode,
      @Schema(description = "起运地名（快照）") String originName,
      @Schema(description = "目的地码") String destinationCode,
      @Schema(description = "目的地名（快照）") String destinationName,
      @Schema(description = "币种") String currency,
      @Schema(description = "生效起点") String effectiveFrom,
      @Schema(description = "生效终点（null = 长期有效）") String effectiveTo,
      @Schema(description = "版本号") int versionNo,
      @Schema(description = "上一版本 id") UUID supersedesId,
      @Schema(description = "后继版本 id") UUID supersededById,
      @Schema(description = "状态") String status,
      @Schema(description = "状态中文名") String statusLabel,
      @Schema(description = "停用理由") String disableReason,
      @Schema(description = "备注") String note,
      @Schema(description = "成本项数") int componentCount,
      @Schema(description = "创建时间") String createdAt) {}

  /** 头程费用结构台账分页结果。 */
  public record HeadFreightStructureListPage(
      @Schema(description = "本页结构") List<HeadFreightStructureRow> items,
      @Schema(description = "总条数") long total,
      @Schema(description = "页码（从 0 起）") int page,
      @Schema(description = "每页条数") int size,
      @Schema(description = "口径说明") String note) {}

  /** 头程费用结构明细。 */
  public record HeadFreightStructureDetail(
      @Schema(description = "结构") HeadFreightStructureRow structure,
      @Schema(description = "成本项") List<HeadFreightComponentRow> components,
      @Schema(description = "说明") String note) {}

  /** 头程成本项（入参）。 */
  public record HeadFreightComponentInput(
      @Schema(description = "序号（从 1 起）") int seq,
      @Schema(description = "成本项类型") @NotBlank String componentType,
      @Schema(description = "人话标签") @Size(max = 128) String label,
      @Schema(description = "计价基数（PER_SHIPMENT / PER_CBM / PER_CONTAINER / PER_KG）") @NotBlank String calcBasis,
      @Schema(description = "单位费用") @NotNull @DecimalMin("0") BigDecimal unitAmount,
      @Schema(description = "币种") @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String currency) {}

  /** 新建 / 新增版本一条头程费用结构（结构与成本项**同事务**写入）。 */
  public record HeadFreightStructureInput(
      @Schema(description = "运输方式码（必填）")
          @NotBlank
          @Pattern(regexp = "^[A-Z0-9][A-Z0-9_-]{0,31}$")
          String shippingMethodCode,
      @Schema(description = "运输方式名（快照）") @Size(max = 128) String shippingMethodName,
      @Schema(description = "起运地码（必填）")
          @NotBlank
          @Pattern(regexp = "^[A-Z0-9][A-Z0-9_-]{0,31}$")
          String originCode,
      @Schema(description = "起运地名（快照）") @Size(max = 128) String originName,
      @Schema(description = "目的地码（必填）")
          @NotBlank
          @Pattern(regexp = "^[A-Z0-9][A-Z0-9_-]{0,31}$")
          String destinationCode,
      @Schema(description = "目的地名（快照）") @Size(max = 128) String destinationName,
      @Schema(description = "币种（必填）") @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String currency,
      @Schema(description = "生效起点（必填）") @NotNull OffsetDateTime effectiveFrom,
      @Schema(description = "生效终点（可空 = 长期有效）") OffsetDateTime effectiveTo,
      @Schema(description = "备注") @Size(max = 500) String note,
      @Schema(description = "成本项（与结构同事务写入；至少一项）")
          @NotEmpty
          @Valid
          List<HeadFreightComponentInput> components) {}

  /** 保存头程费用结构的结果。 */
  public record HeadFreightStructureSaved(
      @Schema(description = "结构 id") String id,
      @Schema(description = "版本号") int versionNo,
      @Schema(description = "状态") String status,
      @Schema(description = "生效起点") String effectiveFrom) {}

  // ================================================================ 实际运费

  /** 实际运费的一行。 */
  public record FreightActualRow(
      @Schema(description = "实际运费 id") UUID id,
      @Schema(description = "渠道码") String channelCode,
      @Schema(description = "渠道名（快照）") String channelName,
      @Schema(description = "承运商码（可空）") String carrierCode,
      @Schema(description = "承运商名（快照）") String carrierName,
      @Schema(description = "运输方式码") String shippingMethodCode,
      @Schema(description = "运输方式名（快照）") String shippingMethodName,
      @Schema(description = "计费口径") String chargeBasis,
      @Schema(description = "计费口径中文名") String chargeBasisLabel,
      @Schema(description = "实际发生额") String amount,
      @Schema(description = "基础运费（拆分登记，可空）") String baseAmount,
      @Schema(description = "附加费合计（拆分登记，可空）") String surchargeAmount,
      @Schema(description = "币种") String currency,
      @Schema(description = "计费量（差异计算用）") String chargeableQuantity,
      @Schema(description = "件数（差异计算用）") String quantity,
      @Schema(description = "运费发生日") String occurredOn,
      @Schema(description = "来源码") String source,
      @Schema(description = "来源中文名") String sourceLabel,
      @Schema(description = "物流商账单号等外部凭据") String sourceRef,
      @Schema(description = "备注") String note,
      @Schema(description = "是否已作废") boolean isVoid,
      @Schema(description = "作废理由") String voidReason,
      @Schema(description = "作废时间") String voidedAt,
      @Schema(description = "登记时间") String createdAt) {}

  /** 实际运费台账分页结果。 */
  public record FreightActualListPage(
      @Schema(description = "本页实际运费") List<FreightActualRow> items,
      @Schema(description = "总条数") long total,
      @Schema(description = "页码（从 0 起）") int page,
      @Schema(description = "每页条数") int size,
      @Schema(description = "口径说明") String note) {}

  /** 登记一条实际运费。 */
  public record FreightActualInput(
      @Schema(description = "渠道码（必填）")
          @NotBlank
          @Pattern(regexp = "^[A-Z0-9][A-Z0-9_-]{0,31}$")
          String channelCode,
      @Schema(description = "渠道名（快照）") @Size(max = 128) String channelName,
      @Schema(description = "承运商码（可空）")
          @Pattern(regexp = "^[A-Z0-9][A-Z0-9_-]{0,31}$")
          String carrierCode,
      @Schema(description = "承运商名（快照）") @Size(max = 128) String carrierName,
      @Schema(description = "运输方式码（必填）")
          @NotBlank
          @Pattern(regexp = "^[A-Z0-9][A-Z0-9_-]{0,31}$")
          String shippingMethodCode,
      @Schema(description = "运输方式名（快照）") @Size(max = 128) String shippingMethodName,
      @Schema(description = "计费口径") @NotBlank String chargeBasis,
      @Schema(description = "实际发生额（可负 = 冲回）") @NotNull BigDecimal amount,
      @Schema(description = "基础运费（可空）") @DecimalMin("0") BigDecimal baseAmount,
      @Schema(description = "附加费合计（可空）") @DecimalMin("0") BigDecimal surchargeAmount,
      @Schema(description = "币种（必填）") @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String currency,
      @Schema(description = "计费量（差异计算用，必填）") @NotNull @DecimalMin("0") BigDecimal chargeableQuantity,
      @Schema(description = "件数（差异计算用，必填）") @NotNull @DecimalMin("0") BigDecimal quantity,
      @Schema(description = "运费发生日（必填）") @NotNull LocalDate occurredOn,
      @Schema(description = "物流商账单号等外部凭据") @Size(max = 200) String sourceRef,
      @Schema(description = "备注") @Size(max = 500) String note) {}

  /** 登记 / 作废实际运费的结果。 */
  public record FreightActualSaved(
      @Schema(description = "实际运费 id") String id,
      @Schema(description = "实际发生额") String amount,
      @Schema(description = "币种") String currency,
      @Schema(description = "运费发生日") String occurredOn) {}

  // ================================================================ 测算

  /** 测算里的一步（逐步拆解，不是裸数字）。 */
  public record FreightEstimateStep(
      @Schema(description = "步骤码") String step,
      @Schema(description = "步骤名") String label,
      @Schema(description = "算式说明（人话）") String basis,
      @Schema(description = "这一步的金额（null = 这一步算不出来）") String amount,
      @Schema(description = "币种") String currency,
      @Schema(description = "这一步是否成立（false = 算不出来）") boolean applicable,
      @Schema(description = "说明") String note) {}

  /**
   * 一次渠道运费测算的完整结果。
   *
   * <p>🔴 {@code totalAmount} 只在**每一步都算得出来**时才是合计；
   * 任何一步算不出来（没有生效规则 / 规则落不进档 / 附加费币种不一致），
   * {@code complete=false} 且 {@code totalAmount=null} ——
   * 把「算不出来」当 0 加进去，就是在报一个偏低的运费。
   */
  public record FreightEstimate(
      @Schema(description = "渠道码") String channelCode,
      @Schema(description = "承运商码（可空）") String carrierCode,
      @Schema(description = "运输方式码") String shippingMethodCode,
      @Schema(description = "计费口径") String chargeBasis,
      @Schema(description = "币种") String currency,
      @Schema(description = "测算基准时刻") String evaluatedAt,
      @Schema(description = "计费量") String chargeableQuantity,
      @Schema(description = "计费量单位") String quantityUnit,
      @Schema(description = "件数") String quantity,
      @Schema(description = "逐步拆解") List<FreightEstimateStep> steps,
      @Schema(description = "基础运费（不完整时为 null）") String baseAmount,
      @Schema(description = "附加费合计（不完整时为 null）") String surchargeAmount,
      @Schema(description = "运费合计（不完整时为 null）") String totalAmount,
      @Schema(description = "合计是否完整") boolean complete,
      @Schema(description = "说明") String note) {}

  /** 头程费测算里的一步。 */
  public record HeadFreightEstimateStep(
      @Schema(description = "步骤码") String step,
      @Schema(description = "步骤名") String label,
      @Schema(description = "算式说明（人话）") String basis,
      @Schema(description = "这一步的金额（null = 这一步算不出来）") String amount,
      @Schema(description = "币种") String currency,
      @Schema(description = "这一步是否成立（false = 算不出来）") boolean applicable,
      @Schema(description = "说明") String note) {}

  /**
   * 一次头程费测算的完整结果。
   *
   * <p>🔴 成本项**可以有不同的币种**，而跨币种绝不相加 ⇒ 混币时
   * {@code totalAmount=null} + {@code complete=false} + 一句原因。
   */
  public record HeadFreightEstimate(
      @Schema(description = "运输方式码") String shippingMethodCode,
      @Schema(description = "起运地码") String originCode,
      @Schema(description = "目的地码") String destinationCode,
      @Schema(description = "测算基准时刻") String evaluatedAt,
      @Schema(description = "逐步拆解") List<HeadFreightEstimateStep> steps,
      @Schema(description = "头程费合计（不完整时为 null）") String totalAmount,
      @Schema(description = "合计币种（混币时为 null）") String currency,
      @Schema(description = "合计是否完整") boolean complete,
      @Schema(description = "说明") String note) {}

  // ================================================================ 运费差异

  /**
   * 一组运费差异对照。
   *
   * <p>🔴 {@code estimatedAmount} 与 {@code varianceAmount} 都是**可空**的：
   * 为空表示「算不出来」（该范围没有生效规则 / 规则不适用 / 币种不一致），
   * <b>不是 0</b>。把它们当成 0 就会报出「差异 0.00」。
   */
  public record FreightVarianceGroup(
      @Schema(description = "渠道码") String channelCode,
      @Schema(description = "运输方式码") String shippingMethodCode,
      @Schema(description = "币种") String currency,
      @Schema(description = "实际运费合计") String actualAmount,
      @Schema(description = "按规则测算的合计（null = 算不出来）") String estimatedAmount,
      @Schema(description = "运费差异 = 实际 − 测算（null = 算不出来）") String varianceAmount,
      @Schema(description = "实际运费笔数") long actualCount,
      @Schema(description = "可比笔数（有生效规则可对照的）") long comparableCount,
      @Schema(description = "算不出来时的原因") String note) {}

  /**
   * 运费差异对照报告（规则测算 vs 实际）。
   *
   * <p>🔴 {@code available=false} 表示**这个范围算不出差异**
   * （没有任何实际运费记录），{@code reason} 说清为什么。
   * 这**不是**「差异为 0」—— 页面必须显示成「无法计算差异」。
   */
  public record FreightVarianceReport(
      @Schema(description = "是否算得出差异") boolean available,
      @Schema(description = "算不出来时的原因") String reason,
      @Schema(description = "区间起") String from,
      @Schema(description = "区间止") String to,
      @Schema(description = "逐组对照") List<FreightVarianceGroup> groups,
      @Schema(description = "口径说明") String note) {}
}
