package com.starcon.catalog;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 5.2.1.3 成本分摊：把混合成本的货件按重量/体积/金额/数量规则分摊到各 SKU，支持人工微调并留痕。
 *
 * <p>蓝图原文：
 *
 * <blockquote>
 *
 * **职责**：把混合成本的货件（一次采购多个 SKU 共用头程）按重量/体积/金额/数量规则分摊到各 SKU，支持人工微调并留痕。
 *
 * **输入**：货件成本总额；分摊规则；人工调整值与原因。
 *
 * **输出**：分摊明细（每 SKU 分摊金额）；重算与差异对比。
 *
 * **关系**：上游 = 10 头程费用、12 landed_cost_allocation；下游 = 5.2.1.1 成本构成、13 利润。**分摊规则变更会导致历史利润变化**，因此调整必须留历史版本，不能就地覆盖。
 *
 * </blockquote>
 *
 * <p><b>🔴 本模块最容易被做错的地方：分摊规则一变，历史利润就变了。</b>
 *
 * <p>同一批货，按重量摊是 12.00，按金额摊是 18.00。规则一换，13 利润拿到的每个 SKU 的成本
 * 就变了 —— 而**历史利润已经用旧规则算过了**。所以本模块的一切变更都是
 * <b>追加一个新版本</b>，旧版本的明细行原样留在库里；没有 UPDATE 端点，也永远不会有。
 *
 * <p><b>🔴 三个必须分得开的东西</b>：
 *
 * <ol>
 *   <li><b>计算分摊额</b>（{@code allocatedAmount}）= 总额 × 本行基数 ÷ 全部基数。
 *       逐行之和<b>严格等于</b>货件成本总额（最大余数法 + DB 延迟约束触发器双保险）。
 *   <li><b>人工微调</b>（{@code adjustmentAmount}）= 相对计算分摊额的增减，<b>原因必填</b>。
 *   <li><b>最终分摊额</b>（{@code finalAmount}）= 计算分摊额 + 人工微调。
 *       逐行最终额之和与总额之差是<b>人工决定的差额</b>，它必须被显示出来，不是错误。
 * </ol>
 *
 * <p><b>🔴 基数取不到 ≠ 分摊到 0</b>：一行取不到基数（没申报重量、没维护成本构成）时，
 * 它<b>不参与</b>分母，<b>明确</b>记成「基数不可得」并写明原因（{@code basisAvailable=false} +
 * {@code basisNote}）。所有行都取不到基数时<b>拒绝分摊</b>，而不是摊出一张全 0 的单。
 */
public final class CostAllocationModels {

  private CostAllocationModels() {}

  // ================================================================ 词表

  /**
   * 分摊规则（蓝图：「按重量/体积/金额/数量规则分摊」）。
   *
   * <p><b>枚举只增不改</b>：规则会进版本快照（{@code cost_allocation_revision.basis_rule}），
   * 改语义会让历史分摊读起来是错的。
   */
  public enum BasisRule {
    /** 按重量：基数 = 货件行的申报毛重。 */
    WEIGHT("按重量", "按各行的毛重（克）占比分摊。适合重货（抛货按重量摊才公平）。"),
    /** 按体积：基数 = 货件行的申报体积。 */
    VOLUME("按体积", "按各行的体积（立方厘米）占比分摊。适合抛货（轻泡货按体积摊才公平）。"),
    /** 按金额：基数 = 货件行的申报货值。 */
    AMOUNT("按金额", "按各行的货值占比分摊。货值高的一批摊得多，���合与货值相称的成本承担。"),
    /** 按数量：基数 = 货件行的数量。 */
    QUANTITY("按数量", "按各行的数量占比分摊。最简单，但对重货不公平（一件重货和一件轻货摊一样的钱）。");

    private final String label;
    private final String basis;

    BasisRule(String label, String basis) {
      this.label = label;
      this.basis = basis;
    }

    public String label() {
      return label;
    }

    public String basis() {
      return basis;
    }

    /** 解析失败返回 null（调用方据此给出可读错误，而不是抛枚举异常）。 */
    public static BasisRule parse(String raw) {
      if (raw == null) return null;
      for (var r : values()) {
        if (r.name().equalsIgnoreCase(raw.trim())) return r;
      }
      return null;
    }
  }

  /** 分摊来源类型。 */
  public enum SourceType {
    /** 头程发运单（warehouse）。一张发运单里多个 SKU 就是「混合成本的货件」。 */
    FIRST_MILE_SHIPMENT("头程发运单", "来源 = 10 头程费用的发运单（warehouse 的授权只读端点）。"),
    /** 人工登记的货件。 */
    MANUAL("人工货件", "来源 = 本服务自持的人工货件（12 财务未交付时的事实落点）。");

    private final String label;
    private final String basis;

    SourceType(String label, String basis) {
      this.label = label;
      this.basis = basis;
    }

    public String label() {
      return label;
    }

    public String basis() {
      return basis;
    }

    public static SourceType parse(String raw) {
      if (raw == null) return null;
      for (var t : values()) {
        if (t.name().equalsIgnoreCase(raw.trim())) return t;
      }
      return null;
    }
  }

  // ================================================================ 能力声明

  /** 一条规则的选项。 */
  public record PricingCostAllocationRuleOption(String value, String label, String basis) {}

  /** 一个来源类型的选项。 */
  public record PricingCostAllocationSourceOption(String value, String label, String basis) {}

  /**
   * 能力声明。页面上把它固定显示在顶部。
   *
   * @param rules 四种分摊规则与各自口径
   * @param sources 两种来源与各自口径
   * @param upstreamNote 上游真值与各自的交付状态
   * @param basisNote 四种基数的取数来源与回落口径
   * @param historyNote 版本与历史纪律
   * @param downstreamNote 下游是谁、怎么读
   * @param actionsNote 可做的动作
   */
  public record PricingCostAllocationCapabilities(
      List<PricingCostAllocationRuleOption> rules,
      List<PricingCostAllocationSourceOption> sources,
      List<String> currencies,
      String upstreamNote,
      String basisNote,
      String historyNote,
      String downstreamNote,
      String actionsNote) {}

  // ================================================================ 来源候选

  /** 一个可分摊的货件候选（头程发运单）。 */
  public record PricingCostAllocationSourceCandidate(
      String sourceRef,
      String sourceLabel,
      String currency,
      BigDecimal totalCost,
      int lineCount,
      @Schema(nullable = true) OffsetDateTime createdAt,
      boolean alreadyAllocated) {}

  /** 来源候选清单。 */
  public record PricingCostAllocationSourceCandidatePage(
      List<PricingCostAllocationSourceCandidate> items, int count, String note, boolean truncated) {}

  // ================================================================ 列表

  /** 列表查询条件。 */
  public record PricingCostAllocationListQuery(
      String keyword, String sourceType, String currency, String basisRule, int page, int size) {}

  /** 列表一行 = 一张分摊单（当前版本口径）。 */
  public record PricingCostAllocationRow(
      UUID allocationId,
      String sourceType,
      String sourceTypeLabel,
      String sourceRef,
      String sourceLabel,
      @Schema(nullable = true) String currency,
      @Schema(nullable = true) BigDecimal totalCost,
      @Schema(nullable = true) String basisRule,
      @Schema(nullable = true) String basisRuleLabel,
      @Schema(nullable = true) Integer revisionNo,
      int revisionCount,
      int lineCount,
      @Schema(nullable = true) BigDecimal allocatedTotal,
      @Schema(nullable = true) BigDecimal adjustmentTotal,
      @Schema(nullable = true) BigDecimal finalTotal,
      @Schema(nullable = true) BigDecimal variance,
      @Schema(nullable = true) OffsetDateTime effectiveFrom,
      @Schema(nullable = true) OffsetDateTime updatedAt) {}

  /** 列表页。 */
  public record PricingCostAllocationListPage(
      List<PricingCostAllocationRow> items, long total, int page, int size, String note) {}

  // ================================================================ 明细行

  /**
   * 分摊明细的一行（一个 SKU 一行）。
   *
   * @param baseQuantity 本行数量（货件行口径）
   * @param weightGrams 本行毛重（克）；不可得时为 null
   * @param volumeCm3 本行体积（立方厘米）；不可得时为 null
   * @param amountBasis 本行货值；不可得时为 null
   * @param basisValue 这一版实际采用的基数；不可得时为 **null**（不是 0）
   * @param basisAvailable 基数是否可得
   * @param basisNote 基数来源 / 不可得的原因
   * @param allocatedAmount 计算分摊额
   * @param adjustmentAmount 人工微调（相对计算分摊额的增减）
   * @param adjustmentReason 人工微调原因（调整额 ≠ 0 时必填）
   * @param finalAmount 最终分摊额 = 计算分摊额 + 人工微调
   */
  public record PricingCostAllocationLineRow(
      UUID skuId,
      String skuCode,
      String skuName,
      BigDecimal baseQuantity,
      @Schema(nullable = true) BigDecimal weightGrams,
      @Schema(nullable = true) BigDecimal volumeCm3,
      @Schema(nullable = true) BigDecimal amountBasis,
      @Schema(nullable = true) BigDecimal basisValue,
      boolean basisAvailable,
      String basisNote,
      BigDecimal allocatedAmount,
      BigDecimal adjustmentAmount,
      String adjustmentReason,
      BigDecimal finalAmount) {}

  // ================================================================ 版本

  /** 版本快照的表头信息。 */
  public record PricingCostAllocationRevisionRow(
      UUID revisionId,
      int revisionNo,
      String basisRule,
      String basisRuleLabel,
      BigDecimal totalCost,
      String currency,
      int lineCount,
      int eligibleLineCount,
      @Schema(nullable = true) BigDecimal basisTotal,
      BigDecimal allocatedTotal,
      BigDecimal adjustmentTotal,
      BigDecimal finalTotal,
      BigDecimal variance,
      OffsetDateTime effectiveFrom,
      String changeReason,
      OffsetDateTime createdAt) {}

  /** 版本明细（一个版本 + 它的行）。 */
  public record PricingCostAllocationRevisionDetail(
      PricingCostAllocationRevisionRow revision, List<PricingCostAllocationLineRow> lines, String note) {}

  // ================================================================ 差异对比

  /**
   * 差异对比的一行（一个 SKU 一行）。
   *
   * @param change {@code ADDED} 新增 / {@code REMOVED} 移除 / {@code CHANGED} 金额变了 / {@code SAME} 没变
   * @param beforeAmount 旧版本的最终分摊额；新增时为 null
   * @param afterAmount 新版本的最终分摊额；移除时为 null
   * @param delta 差额 = 新 − 旧；新增/移除时为 null（没有可比对象）
   */
  public record PricingCostAllocationDiffRow(
      UUID skuId,
      String skuCode,
      String skuName,
      String change,
      @Schema(nullable = true) BigDecimal beforeAmount,
      @Schema(nullable = true) BigDecimal afterAmount,
      @Schema(nullable = true) BigDecimal delta,
      String note) {}

  /**
   * 差异对比的总体结果。
   *
   * @param ruleChanged 规则是否变了
   * @param beforeRule 旧规则
   * @param afterRule 新规则
   * @param beforeTotal 旧版本的最终分摊合计
   * @param afterTotal 新版本的最终分摊合计
   * @param totalDelta 合计差额
   * @param rows 逐 SKU 的差异
   * @param note 说明
   */
  public record PricingCostAllocationDiff(
      boolean ruleChanged,
      String beforeRule,
      String afterRule,
      BigDecimal beforeTotal,
      BigDecimal afterTotal,
      BigDecimal totalDelta,
      int changedCount,
      List<PricingCostAllocationDiffRow> rows,
      String note) {}

  // ================================================================ 明细

  /** 分摊单明细：表头 + 当前版本 + 当前版本的行 + 与上一版的差异。 */
  public record PricingCostAllocationDetail(
      UUID allocationId,
      String sourceType,
      String sourceTypeLabel,
      String sourceRef,
      String sourceLabel,
      @Schema(nullable = true) PricingCostAllocationRevisionRow currentRevision,
      List<PricingCostAllocationLineRow> lines,
      @Schema(nullable = true) PricingCostAllocationDiff lastDiff,
      List<PricingCostAllocationRevisionRow> revisions,
      String note) {}

  // ================================================================ 输入

  /** 人工货件的一行。 */
  public record PricingCostAllocationManualLineInput(
      @NotNull UUID skuId,
      @NotNull BigDecimal quantity,
      @Schema(nullable = true) BigDecimal weightGrams,
      @Schema(nullable = true) BigDecimal volumeCm3,
      @Schema(nullable = true) BigDecimal amountBasis) {}

  /** 人工微调一行。 */
  public record PricingCostAllocationAdjustmentInput(
      @NotNull UUID skuId,
      @NotNull BigDecimal amount,
      @NotBlank @Size(max = 500) String reason) {}

  /**
   * 新建分摊单。
   *
   * @param sourceType 来源类型
   * @param sourceRef 来源标识（发运单 id / 人工货件号）
   * @param sourceLabel 展示用标签
   * @param currency 计价币种（头程发运单来源时必须与上游一致）
   * @param totalCost 货件成本总额（留空 = 取上游发运单的可分摊成本）
   * @param basisRule 分摊规则
   * @param effectiveFrom 生效时间
   * @param changeReason 变更原因（必填）
   * @param lines 人工货件的明细行（MANUAL 来源必填；头程来源忽略）
   * @param adjustments 人工微调（逐行；原因必填）
   */
  public record PricingCostAllocationCreateInput(
      @NotBlank String sourceType,
      @NotBlank @Size(max = 128) String sourceRef,
      @Size(max = 200) String sourceLabel,
      @Size(max = 3) String currency,
      @Schema(nullable = true) BigDecimal totalCost,
      @NotBlank String basisRule,
      @NotNull OffsetDateTime effectiveFrom,
      @NotBlank @Size(max = 500) String changeReason,
      @Valid @Schema(nullable = true) List<PricingCostAllocationManualLineInput> lines,
      @Valid @Schema(nullable = true) List<PricingCostAllocationAdjustmentInput> adjustments) {}

  /**
   * 重算（试算或落库）。
   *
   * @param basisRule 新的分摊规则（留空 = 沿用当前版本的规则）
   * @param totalCost 新的货件成本总额（留空 = 沿用当前版本）
   * @param effectiveFrom 生效时间（落库时必填）
   * @param changeReason 变更原因（落库时必填）
   * @param adjustments 人工微调（逐行）
   * @param lines 人工货件的明细行（MANUAL 来源可更新；留空 = 沿用当前版本）
   */
  public record PricingCostAllocationRecalculateInput(
      @Size(max = 32) String basisRule,
      @Schema(nullable = true) BigDecimal totalCost,
      @Schema(nullable = true) OffsetDateTime effectiveFrom,
      @Size(max = 500) String changeReason,
      @Valid @Schema(nullable = true) List<PricingCostAllocationAdjustmentInput> adjustments,
      @Valid @Schema(nullable = true) List<PricingCostAllocationManualLineInput> lines) {}

  // ================================================================ 其它

  /** 试算结果（不落库）：新的行 + 与当前版本的差异。 */
  public record PricingCostAllocationPreview(
      String basisRule,
      String basisRuleLabel,
      BigDecimal totalCost,
      String currency,
      List<PricingCostAllocationLineRow> lines,
      BigDecimal allocatedTotal,
      BigDecimal adjustmentTotal,
      BigDecimal finalTotal,
      BigDecimal variance,
      PricingCostAllocationDiff diff,
      String note) {}

  /** 保存结果（新建 / 新版本）。 */
  public record PricingCostAllocationSaved(
      UUID allocationId,
      UUID revisionId,
      int revisionNo,
      String basisRule,
      BigDecimal totalCost,
      String currency,
      int lineCount,
      BigDecimal allocatedTotal,
      BigDecimal finalTotal,
      BigDecimal variance,
      OffsetDateTime effectiveFrom,
      long version) {}

  /** 差异对比的查询条件。 */
  public record DiffQuery(Integer from, Integer to) {}
}
