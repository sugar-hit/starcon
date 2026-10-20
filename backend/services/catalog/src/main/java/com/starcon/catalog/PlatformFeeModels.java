package com.starcon.catalog;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
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
 * 5.2.3.1 平台费用：费用规则（事前测算口径）与实际费用（事后对账口径）两个独立事实，
 * 以及两者的差额 —— 「费用差异」。
 *
 * <p>蓝图原文：
 *
 * <blockquote>
 *
 * **职责**：维护平台侧费用的计算规则：佣金比例或阶梯、订阅费分摊、Referral Fee、交易费、罚款类型，用于利润测算的扣项。
 *
 * **输入**：平台费用规则配置（按平台/站点/类目）；实际费用的历史值用于校准。
 *
 * **输出**：费用规则；测算时的费用项。
 *
 * **关系**：上游 = 2 平台目录；下游 = 5.3.1.1 测算、13 利润、14 广告。**规则参数用于事前测算，实际费用来自 12 的结算对账**——两者差额要在利润报表中显性展示为"费用差异"，否则测算永远不准却不知道为什么。
 *
 * </blockquote>
 *
 * <p><b>🔴 本模块唯一不可让步的一条：规则与实际是两个事实，差异要能算出来并被展示。</b>
 *
 * <p>蓝图那句话里有两个口径，答的是两个不同的问题：
 *
 * <ul>
 *   <li><b>规则</b>（{@code platform_fee_rule}）：「这笔单按规则应该扣多少」——
 *       事前测算口径，5.3.1.1 利润测算器与 13 利润消费它。
 *   <li><b>实际费用</b>（{@code platform_fee_actual}）：「平台实际扣了多少」——
 *       事后对账口径。
 * </ul>
 *
 * <p>把两者合并成一个字段（「费用金额」列）就再也答不出「这个月是规则定错了，
 * 还是平台多扣了」—— 而那正是蓝图加粗句要解决的事。合并之后差异恒等于 0，
 * 页面看起来「测算永远准」，实际是差异根本无处可藏。
 *
 * <p><b>🔴 没有实际费用时，差异是「算不出来」，不是 0。</b>
 * 本服务把这种情况表达成 {@code available=false} + 一句原因
 * （见 {@link FeeVarianceReport#available()} / {@link FeeVarianceReport#reason()}），
 * 前端据此显示「无法计算差异」而不是「差异 0.00」。
 *
 * <p><b>⚠️ 12 财务的结算对账当前没有来源</b>：{@code backend/services} 下没有 finance 服务。
 * 所以实际费用现在的<b>唯一</b>来源是**人工登记的平台账单观察值**
 * （{@code source='MANUAL_OBSERVATION'}，对应蓝图输入句「实际费用的历史值用于校准」）。
 * 本模块<b>不</b>编一个假的「历史实际费用」数据源去冒充 12 的对账结果 ——
 * 能力声明里 {@code actual_fee_reconciliation} 维度显式标成 {@code available=false}
 * + {@code ownerModule=12}。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class PlatformFeeModels {

  private PlatformFeeModels() {}

  // ================================================================ 词表

  /**
   * 平台费用类型。
   *
   * <p>枚举<b>只增不改</b>：它会进规则、实际费用与差异对照的口径，
   * 改语义会让历史读起来是错的。
   */
  public enum FeeType {
    /** 平台佣金（最常见的那一笔）。 */
    COMMISSION("平台佣金", "平台按成交额收取的佣金。"),
    /** Referral Fee（推荐费 / 引流费）。 */
    REFERRAL("Referral Fee", "平台为导流收取的推荐费，与佣金常常叠加。"),
    /** 交易费（按笔 / 按成交收取的其它平台侧费用）。 */
    TRANSACTION("交易费", "平台按交易收取的其它费用（支付手续费等）。"),
    /** 订阅费（平台店铺的月费 / 年费，按分摊口径摊到单据上）。 */
    SUBSCRIPTION("订阅费", "平台店铺的订阅费，是一笔周期总额，按分摊口径摊到每一单 / 每一件。"),
    /** 罚款（违规、迟到、差评等）。 */
    PENALTY("罚款", "平台因违规、迟交、低评分等收取的罚款。"),
    /** 其它平台侧费用。 */
    OTHER("其它平台费用", "不属于以上分类的平台侧费用。");

    private final String label;
    private final String basis;

    FeeType(String label, String basis) {
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

  /**
   * 计价形态。
   *
   * <p>四种形态**互斥**：两个都填时「这条费率到底怎么算」没有答案，
   * 而服务层只会安静地挑一个用。互斥由 DB 层的 CHECK 钉死。
   */
  public enum ChargeBasis {
    /** 销售额的一个比例（费率是小数：0.15 = 15%）。 */
    PERCENTAGE_OF_SALE("销售额比例", "费用 = 销售额 × 费率（费率是小数口径，0.15 = 15%）。"),
    /** 每单一个固定额。 */
    FIXED_PER_ORDER("每单固定额", "费用 = 固定额（每笔订单一次）。"),
    /** 每件一个固定额。 */
    FIXED_PER_UNIT("每件固定额", "费用 = 固定额 × 数量。"),
    /** 按金额分档的比例（阶梯）。 */
    TIERED_PERCENTAGE("阶梯比例", "按销售额落到的档位取费率，费用 = 销售额 × 该档费率。");

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

  /** 订阅费的分摊口径。 */
  public enum AllocationBasis {
    /** 摊到每一单。 */
    PER_ORDER("按单分摊", "周期总额 ÷ 期间单量 = 每单分摊额。"),
    /** 摊到每一件。 */
    PER_UNIT("按件分摊", "周期总额 ÷ 期间件量 = 每件分摊额。");

    private final String label;
    private final String basis;

    AllocationBasis(String label, String basis) {
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
  public record FeeCapabilityDimension(
      @Schema(description = "维度码") String code,
      @Schema(description = "维度名") String label,
      @Schema(description = "是否已交付 / 可取到") boolean available,
      @Schema(description = "归属模块（未交付时点名）") String ownerModule,
      @Schema(description = "缺了它会怎样") String impact,
      @Schema(description = "口径说明") String basis,
      @Schema(description = "备注") String note) {}

  /** 费用类型选项。 */
  public record FeeTypeOption(
      @Schema(description = "取值") String value,
      @Schema(description = "中文名") String label,
      @Schema(description = "口径说明") String basis) {}

  /** 计价形态选项。 */
  public record ChargeBasisOption(
      @Schema(description = "取值") String value,
      @Schema(description = "中文名") String label,
      @Schema(description = "口径说明") String basis) {}

  /**
   * 能力声明：费用类型与计价形态的词表、六个维度（每个维度写明取不到没有 / 归谁管），
   * 以及四条必须讲清的说明。
   *
   * <p>页面固定把它显示在顶部。
   */
  public record PlatformFeeCapabilities(
      @Schema(description = "费用类型词表") List<FeeTypeOption> feeTypes,
      @Schema(description = "计价形态词表") List<ChargeBasisOption> chargeBases,
      @Schema(description = "订阅费分摊口径词表") List<FeeTypeOption> allocationBases,
      @Schema(description = "逐维度能力声明") List<FeeCapabilityDimension> dimensions,
      @Schema(description = "规则口径说明") String ruleBasis,
      @Schema(description = "实际费用口径说明") String actualBasis,
      @Schema(description = "费用差异口径说明") String varianceBasis,
      @Schema(description = "上游说明") String upstreamNote,
      @Schema(description = "下游说明") String downstreamNote,
      @Schema(description = "本模块动作说明") String actionsNote) {}

  // ================================================================ 费用规则

  /** 阶梯的一档。 */
  public record FeeTierRow(
      @Schema(description = "阶梯行 id") UUID id,
      @Schema(description = "档位序号") int seq,
      @Schema(description = "下界（含）") String minAmount,
      @Schema(description = "上界（不含，null = 无上限）") String maxAmount,
      @Schema(description = "该档费率（小数口径）") String rate) {}

  /** 费用规则台账的一行。 */
  public record FeeRuleRow(
      @Schema(description = "规则 id") UUID id,
      @Schema(description = "平台码") String platformCode,
      @Schema(description = "站点码（null = 该平台全部站点）") String siteCode,
      @Schema(description = "内部分类 id") UUID internalCategoryId,
      @Schema(description = "内部分类名") String internalCategoryName,
      @Schema(description = "平台类目 id") UUID platformCategoryId,
      @Schema(description = "平台类目名") String platformCategoryName,
      @Schema(description = "费用类型") String feeType,
      @Schema(description = "费用类型中文名") String feeTypeLabel,
      @Schema(description = "计价形态") String chargeBasis,
      @Schema(description = "计价形态中文名") String chargeBasisLabel,
      @Schema(description = "费率（小数口径）") String rate,
      @Schema(description = "固定额") String fixedAmount,
      @Schema(description = "固定额币种") String currency,
      @Schema(description = "订阅费周期总额") String periodTotalAmount,
      @Schema(description = "订阅费周期总额币种") String periodCurrency,
      @Schema(description = "订阅费分摊口径") String allocationBasis,
      @Schema(description = "订阅费分摊基数") String allocationDivisor,
      @Schema(description = "生效起点") String effectiveFrom,
      @Schema(description = "生效终点（null = 长期有效）") String effectiveTo,
      @Schema(description = "是否启用") boolean enabled,
      @Schema(description = "备注") String note,
      @Schema(description = "乐观锁版本") long version,
      @Schema(description = "阶梯档数") int tierCount,
      @Schema(description = "创建时间") String createdAt,
      @Schema(description = "更新时间") String updatedAt) {}

  /** 规则台账分页结果。 */
  public record FeeRuleListPage(
      @Schema(description = "本页规则") List<FeeRuleRow> items,
      @Schema(description = "总条数") long total,
      @Schema(description = "页码（从 0 起）") int page,
      @Schema(description = "每页条数") int size,
      @Schema(description = "口径说明") String note) {}

  /** 规则明细（规则 + 阶梯 + 口径说明）。 */
  public record FeeRuleDetail(
      @Schema(description = "规则") FeeRuleRow rule,
      @Schema(description = "阶梯（无阶梯时为空数组）") List<FeeTierRow> tiers,
      @Schema(description = "这条规则的作用域口径") String scopeBasis,
      @Schema(description = "说明") String note) {}

  /** 保存规则的结果。 */
  public record FeeRuleSaved(
      @Schema(description = "规则 id") String id,
      @Schema(description = "费用类型") String feeType,
      @Schema(description = "计价形态") String chargeBasis,
      @Schema(description = "生效起点") String effectiveFrom,
      @Schema(description = "乐观锁版本") long version) {}

  // ================================================================ 规则入参

  /** 新建 / 修改一条费用规则。 */
  public record FeeRuleInput(
      @Schema(description = "平台码（必填）") @NotBlank @Pattern(regexp = "^[A-Z0-9][A-Z0-9_-]{0,31}$") String platformCode,
      @Schema(description = "站点码（可空 = 该平台全部站点）") @Pattern(regexp = "^[A-Z0-9][A-Z0-9_-]{0,31}$") String siteCode,
      @Schema(description = "内部分类 id（可空 = 不限定类目）") UUID internalCategoryId,
      @Schema(description = "平台类目 id（可空 = 不限定类目）") UUID platformCategoryId,
      @Schema(description = "费用类型") @NotBlank String feeType,
      @Schema(description = "计价形态") @NotBlank String chargeBasis,
      @Schema(description = "费率（小数口径，0.15 = 15%）") @DecimalMin("0") BigDecimal rate,
      @Schema(description = "固定额") @DecimalMin("0") BigDecimal fixedAmount,
      @Schema(description = "固定额币种") @Pattern(regexp = "^[A-Z]{3}$") String currency,
      @Schema(description = "订阅费周期总额") @DecimalMin("0") BigDecimal periodTotalAmount,
      @Schema(description = "订阅费周期总额币种") @Pattern(regexp = "^[A-Z]{3}$") String periodCurrency,
      @Schema(description = "订阅费分摊口径（PER_ORDER / PER_UNIT）") String allocationBasis,
      @Schema(description = "订阅费分摊基数（期间单量 / 件量）") @DecimalMin("0") BigDecimal allocationDivisor,
      @Schema(description = "生效起点（必填）") @NotNull OffsetDateTime effectiveFrom,
      @Schema(description = "生效终点（可空 = 长期有效）") OffsetDateTime effectiveTo,
      @Schema(description = "是否启用（默认启用）") Boolean enabled,
      @Schema(description = "备注") @Size(max = 500) String note,
      @Schema(description = "乐观锁版本（修改时必填）") Long version) {}

  /** 阶梯的一档（入参）。 */
  public record FeeTierInput(
      @Schema(description = "档位序号（从 1 起）") int seq,
      @Schema(description = "下界（含）") @NotNull @DecimalMin("0") BigDecimal minAmount,
      @Schema(description = "上界（不含，null = 无上限）") @DecimalMin("0") BigDecimal maxAmount,
      @Schema(description = "该档费率（小数口径）") @NotNull @DecimalMin("0") BigDecimal rate) {}

  /** 整体替换阶梯的入参。 */
  public record TierListInput(@Schema(description = "阶梯（空数组 = 清空阶梯）") @Valid List<FeeTierInput> tiers) {}

  /** 作废理由。 */
  public record FeeVoidInput(@Schema(description = "作废理由（必填）") @NotBlank String reason) {}

  // ================================================================ 实际费用

  /** 实际费用的一行。 */
  public record FeeActualRow(
      @Schema(description = "实际费用 id") UUID id,
      @Schema(description = "平台码") String platformCode,
      @Schema(description = "站点码") String siteCode,
      @Schema(description = "内部分类 id") UUID internalCategoryId,
      @Schema(description = "平台类目 id") UUID platformCategoryId,
      @Schema(description = "费用类型") String feeType,
      @Schema(description = "费用类型中文名") String feeTypeLabel,
      @Schema(description = "实际发生额") String amount,
      @Schema(description = "币种") String currency,
      @Schema(description = "销售额基数（差异计算用）") String saleAmount,
      @Schema(description = "数量基数（差异计算用）") String quantity,
      @Schema(description = "费用发生日") String occurredOn,
      @Schema(description = "来源码") String source,
      @Schema(description = "来源中文名") String sourceLabel,
      @Schema(description = "平台账单号等外部凭据") String sourceRef,
      @Schema(description = "备注") String note,
      @Schema(description = "是否已作废") boolean isVoid,
      @Schema(description = "作废理由") String voidReason,
      @Schema(description = "作废时间") String voidedAt,
      @Schema(description = "登记时间") String createdAt) {}

  /** 实际费用台账分页结果。 */
  public record FeeActualListPage(
      @Schema(description = "本页实际费用") List<FeeActualRow> items,
      @Schema(description = "总条数") long total,
      @Schema(description = "页码（从 0 起）") int page,
      @Schema(description = "每页条数") int size,
      @Schema(description = "口径说明") String note) {}

  /** 登记一条实际费用的入参。 */
  public record FeeActualInput(
      @Schema(description = "平台码（必填）") @NotBlank @Pattern(regexp = "^[A-Z0-9][A-Z0-9_-]{0,31}$") String platformCode,
      @Schema(description = "站点码（可空 = 平台级）") @Pattern(regexp = "^[A-Z0-9][A-Z0-9_-]{0,31}$") String siteCode,
      @Schema(description = "内部分类 id（可空）") UUID internalCategoryId,
      @Schema(description = "平台类目 id（可空）") UUID platformCategoryId,
      @Schema(description = "费用类型") @NotBlank String feeType,
      @Schema(description = "实际发生额（可负 = 冲回）") @NotNull BigDecimal amount,
      @Schema(description = "币种（必填）") @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String currency,
      @Schema(description = "销售额基数（差异计算用，必填）") @NotNull @DecimalMin("0") BigDecimal saleAmount,
      @Schema(description = "数量基数（差异计算用，必填）") @NotNull @DecimalMin("0") BigDecimal quantity,
      @Schema(description = "费用发生日（必填）") @NotNull LocalDate occurredOn,
      @Schema(description = "平台账单号等外部凭据") @Size(max = 200) String sourceRef,
      @Schema(description = "备注") @Size(max = 500) String note) {}

  /** 登记 / 作废实际费用的结果。 */
  public record FeeActualSaved(
      @Schema(description = "实际费用 id") String id,
      @Schema(description = "费用类型") String feeType,
      @Schema(description = "实际发生额") String amount,
      @Schema(description = "币种") String currency,
      @Schema(description = "费用发生日") String occurredOn) {}

  // ================================================================ 测算（费用项）

  /** 测算出的一个费用项。 */
  public record FeeEstimateItem(
      @Schema(description = "费用类型") String feeType,
      @Schema(description = "费用类型中文名") String feeTypeLabel,
      @Schema(description = "计价形态") String chargeBasis,
      @Schema(description = "计价形态中文名") String chargeBasisLabel,
      @Schema(description = "命中的规则 id（null = 该类型没有生效规则）") UUID ruleId,
      @Schema(description = "命中规则的作用域口径") String scopeBasis,
      @Schema(description = "测算出的费用（null = 该类型算不出来）") String amount,
      @Schema(description = "币种") String currency,
      @Schema(description = "本次用到的费率 / 档位") String rate,
      @Schema(description = "算式说明") String basis,
      @Schema(description = "是否适用（false = 规则存在但不适用于本次金额）") boolean applicable,
      @Schema(description = "说明") String note) {}

  /**
   * 一次测算的完整结果。
   *
   * <p>🔴 {@code totalAmount} 只在**全部费用项都算得出来**时才是合计；
   * 任何一个费用项算不出来（没有规则 / 规则不适用），
   * {@code complete=false} 且 {@code totalAmount=null} ——
   * 把「算不出来」当 0 加进去，就是在报一个偏低的费用。
   */
  public record FeeEstimate(
      @Schema(description = "平台码") String platformCode,
      @Schema(description = "站点码") String siteCode,
      @Schema(description = "参与测算的销售额") String saleAmount,
      @Schema(description = "参与测算的数量") String quantity,
      @Schema(description = "测算基准时刻") String evaluatedAt,
      @Schema(description = "逐项费用") List<FeeEstimateItem> items,
      @Schema(description = "费用合计（不完整时为 null）") String totalAmount,
      @Schema(description = "合计币种") String currency,
      @Schema(description = "合计是否完整") boolean complete,
      @Schema(description = "说明") String note) {}

  // ================================================================ 费用差异

  /**
   * 一个费用类型的差异对照行。
   *
   * <p>🔴 {@code estimatedAmount} 与 {@code varianceAmount} 都是**可空**的：
   * 为空表示「算不出来」（该范围没有生效的规则 / 规则不适用），
   * <b>不是 0</b>。把它们当成 0 就会报出「差异 0.00」——
   * 那正是蓝图加粗句要消灭的现象。
   */
  public record FeeVarianceGroup(
      @Schema(description = "费用类型") String feeType,
      @Schema(description = "费用类型中文名") String feeTypeLabel,
      @Schema(description = "币种") String currency,
      @Schema(description = "实际费用合计") String actualAmount,
      @Schema(description = "按规则测算的合计（null = 算不出来）") String estimatedAmount,
      @Schema(description = "费用差异 = 实际 − 测算（null = 算不出来）") String varianceAmount,
      @Schema(description = "实际费用笔数") long actualCount,
      @Schema(description = "可比笔数（有生效规则可对照的）") long comparableCount,
      @Schema(description = "算不出来时的原因") String note) {}

  /**
   * 费用差异对照报告（规则 vs 实际）。
   *
   * <p>🔴 {@code available=false} 表示**这个范围算不出差异**
   * （没有任何实际费用记录），{@code reason} 说清为什么。
   * 这**不是**「差异为 0」—— 页面必须显示成「无法计算差异」。
   */
  public record FeeVarianceReport(
      @Schema(description = "是否算得出差异") boolean available,
      @Schema(description = "算不出来时的原因") String reason,
      @Schema(description = "平台码") String platformCode,
      @Schema(description = "站点码") String siteCode,
      @Schema(description = "区间起") String from,
      @Schema(description = "区间止") String to,
      @Schema(description = "逐费用类型对照") List<FeeVarianceGroup> groups,
      @Schema(description = "口径说明") String note) {}

  // ================================================================ 平台目录（上游 2）

  /** 平台目录里的一个站点。 */
  public record DirectoryMarketplace(
      @Schema(description = "站点码") String code,
      @Schema(description = "站点名") String name,
      @Schema(description = "国家码") String countryCode,
      @Schema(description = "默认币种") String defaultCurrency,
      @Schema(description = "币种小数位") int currencyScale,
      @Schema(description = "是否需要增值税") boolean vatRequired) {}

  /** 平台目录里的一个平台。 */
  public record DirectoryPlatform(
      @Schema(description = "平台码") String code,
      @Schema(description = "平台名") String name,
      @Schema(description = "站点") List<DirectoryMarketplace> marketplaces) {}

  /**
   * 平台 / 站点目录（来自 2 平台目录，channel 服务）。
   *
   * <p>供规则配置页选择挂载维度，并让服务层能校验「这个平台码 / 站点码真实存在」。
   */
  public record PlatformDirectory(
      @Schema(description = "平台清单") List<DirectoryPlatform> platforms,
      @Schema(description = "来源说明") String sourceNote) {}
}
