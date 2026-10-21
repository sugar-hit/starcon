package com.starcon.trade;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 6.4.2.2 不良品处理的传输模型。
 *
 * <p>蓝图 06-purchase-and-supply.md 6.4.2.2 原文：
 *
 * <blockquote>
 *
 * 职责：不合格品的处置：退换货补货、返修、让步接收、报废、转为促销品，处置结论联动库存状态与供应商索赔。
 * 输入：质检不合格明细；处置决策；索赔金额。输出：处置单 + 库存状态变更指令 + 供应商索赔记录。
 * 关系：上游 = 6.4.2.1；下游 = 7 库存（调整）、12 财务（索赔/退款）。
 * <b>让步接收需要权限与原因，不能由质检员随手放行。</b>
 *
 * </blockquote>
 *
 * <p><b>命名纪律（硬要求）</b>：springdoc 用 record 的<b>简单类名</b>做 {@code components.schemas} 的键，
 * 重名会被<b>静默覆盖</b> —— 别人的字段形状被改掉、单测全绿、只有跨服务契约比对才红。
 * 本仓 trade 服务已有 {@code QualityInspection*}（6.4.2.1 来料质检）与 {@code ReturnQuality*}
 * （11.x 退货质检）两套，本模块的 record / enum 一律以 {@code Defective} 开头。
 *
 * <p><b>七条建模纪律</b>：
 *
 * <ul>
 *   <li><b>数量不接受调用方自报</b>：处置量由后端从 6.4.2.1 质检单回读并按明细行重算，
 *       入参里**没有**处置总量这一栏。</li>
 *   <li><b>可售量上限被显式带到界面上</b>：合格量 + 让步接收量，
 *       处置不得侵占它（处置只作用在不合格量上）。</li>
 *   <li><b>让步接收要权限与原因</b>：两者缺一拒绝，且权限码与「质检结论录入」分开。</li>
 *   <li><b>库存只发指令</b>：指令状态如实报出，绝不出现「已登记」却其实没动库存。</li>
 *   <li><b>索赔金额用 BigDecimal</b>，DB 侧 numeric(19,4)；币种必填。</li>
 *   <li><b>「答不出来」与「不需要」不折叠</b>：12 财务未交付时明确给出
 *       {@code financeStatus}，而不是给一个假的「已入账」。</li>
 *   <li><b>加载 / 空 / 错误三态齐全</b>（前端据此渲染）。</li>
 * </ul>
 */
public final class DefectiveGoodsModels {

  private DefectiveGoodsModels() {}

  /** 中文名。服务层与前端都拿得到 label，不必各写一遍 switch —— 各写一遍迟早漂移。 */
  public interface Labelled {
    String label();
  }

  // ================================================================ 枚举

  /** 处置类型：蓝图 6.4.2.2 职责句点名的五个去向。 */
  public enum DefectiveDispositionType implements Labelled {
    RETURN_EXCHANGE("退换货补货"),
    REPAIR("返修"),
    CONCESSION("让步接收"),
    SCRAP("报废"),
    PROMOTION("转为促销品");

    private final String label;

    DefectiveDispositionType(String label) {
      this.label = label;
    }

    @Override
    public String label() {
      return label;
    }

    /** 这个去向的货会不会回到可售池：让步接收与转促销品会，别的都不会。 */
    public boolean releases() {
      return this == CONCESSION || this == PROMOTION;
    }
  }

  /** 处置单状态机。 */
  public enum DefectiveOrderStatus implements Labelled {
    DRAFT("草稿"),
    DECIDED("已决策"),
    EXECUTING("执行中"),
    COMPLETED("已完成"),
    CANCELLED("已作废");

    private final String label;

    DefectiveOrderStatus(String label) {
      this.label = label;
    }

    @Override
    public String label() {
      return label;
    }

    /** 终态：已决策之后处置结论就是凭据，不再接受任何内容修改。 */
    public boolean terminal() {
      return this == COMPLETED || this == CANCELLED;
    }
  }

  /** 库存状态变更指令的派发状态。 */
  public enum DefectiveInventoryStatus implements Labelled {
    NOT_DISPATCHED("未派发"),
    PARTIAL("部分已登记"),
    UNAVAILABLE("上游无写端点"),
    DISPATCHED("已登记"),
    FAILED("派发失败");

    private final String label;

    DefectiveInventoryStatus(String label) {
      this.label = label;
    }

    @Override
    public String label() {
      return label;
    }
  }

  /** 处置单上的索赔汇总状态。 */
  public enum DefectiveClaimStatus implements Labelled {
    NONE("无索赔"),
    PENDING("待提交"),
    SUBMITTED("已提交"),
    SETTLED("已结"),
    REJECTED("已拒赔"),
    WITHDRAWN("已撤回");

    private final String label;

    DefectiveClaimStatus(String label) {
      this.label = label;
    }

    @Override
    public String label() {
      return label;
    }
  }

  /** 索赔单自己的状态（对供应商的过程）。 */
  public enum DefectiveClaimRecordStatus implements Labelled {
    DRAFT("草稿"),
    SUBMITTED("已提交"),
    ACKNOWLEDGED("供应商已确认"),
    AGREED("已达成赔付共识"),
    REJECTED("供应商拒赔"),
    WITHDRAWN("已撤回");

    private final String label;

    DefectiveClaimRecordStatus(String label) {
      this.label = label;
    }

    @Override
    public String label() {
      return label;
    }

    /** 终态：已达成 / 已拒 / 已撤回之后不再接受任何修改。 */
    public boolean terminal() {
      return this == AGREED || this == REJECTED || this == WITHDRAWN;
    }
  }

  /** 索赔类型（由处置明细推导，不是自由文本）。 */
  public enum DefectiveClaimType implements Labelled {
    QUALITY_DEFECT("质量索赔"),
    REPLENISHMENT("退换货补货"),
    REPAIR_COST("返修费用"),
    SCRAP_LOSS("报废损失"),
    PROMOTION_DEDUCTION("转促销品折让");

    private final String label;

    DefectiveClaimType(String label) {
      this.label = label;
    }

    @Override
    public String label() {
      return label;
    }
  }

  /** 库存状态变更指令的动作。 */
  public enum DefectiveInventoryAction implements Labelled {
    SCRAP_OUT("报废离仓"),
    RETURN_OUT("退换货离仓"),
    REPAIR_HOLD("转返修暂存"),
    DEFECTIVE_HOLD("转残次留存"),
    PROMOTION_RELEASE("转促销品"),
    CONCESSION_RELEASE("让步接收放行");

    private final String label;

    DefectiveInventoryAction(String label) {
      this.label = label;
    }

    @Override
    public String label() {
      return label;
    }
  }

  // ================================================================ 能力声明

  /**
   * 一条上下游依赖的可用性。
   *
   * <p>{@code available=false} 时 {@code ownerModule} 与 {@code impact} **必须**有值 ——
   * 「没接通」要连同「归谁」和「因此少做了什么」一起报出来。
   */
  public record DefectiveCapabilityItem(
      String code,
      String label,
      boolean available,
      @Schema(nullable = true) String ownerModule,
      @Schema(nullable = true) String impact,
      @Schema(nullable = true) String reason) {}

  /** 本模块的能力与上下游可用性。 */
  public record DefectiveGoodsCapabilities(
      List<DefectiveCapabilityItem> upstream,
      List<DefectiveCapabilityItem> downstream,
      List<DefectiveCapabilityItem> actions,
      List<String> dispositionTypes,
      List<String> dispositionTypeLabels,
      String quantityNote,
      String concessionNote,
      String inventoryNote,
      String claimNote,
      String note) {}

  // ================================================================ 下拉

  /** 可处置的质检单（已完结且仍有不合格量）。 */
  public record DefectiveInspectionOption(
      UUID id,
      String inspectionNo,
      String result,
      @Schema(nullable = true) UUID skuId,
      String skuCode,
      @Schema(nullable = true) String skuName,
      @Schema(nullable = true) UUID supplierId,
      @Schema(nullable = true) String supplierName,
      BigDecimal lotSize,
      BigDecimal defectiveQuantity,
      BigDecimal qualifiedQuantity,
      BigDecimal concessionQuantity,
      BigDecimal sellableCeiling,
      BigDecimal grossDeductedQuantity,
      BigDecimal disposedQuantity,
      BigDecimal remainingQuantity,
      @Schema(nullable = true) OffsetDateTime completedAt) {}

  /** 下拉与词表。 */
  public record DefectiveOptions(
      List<DefectiveInspectionOption> inspections,
      List<String> dispositionTypes,
      List<String> dispositionTypeLabels,
      List<String> statuses,
      List<String> statusLabels,
      List<String> inventoryStatuses,
      List<String> inventoryStatusLabels,
      List<String> claimStatuses,
      List<String> claimStatusLabels,
      String note) {}

  // ================================================================ 处置单

  /** 处置明细（录入用）。 */
  public record DefectiveLineInput(
      @NotNull DefectiveDispositionType dispositionType,
      @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal quantity,
      /** 放行量：只有让步接收与转促销品可大于 0。 */
      @Schema(nullable = true) @DecimalMin("0") BigDecimal releasedQuantity,
      /** 让步接收的原因（仅 CONCESSION 必填）。 */
      @Schema(nullable = true) @Size(max = 2000) String concessionReason,
      /** 让步接收的权限码（仅 CONCESSION 必填，且记录人必须真的持有它）。 */
      @Schema(nullable = true) @Size(max = 64) String permissionCode,
      @Schema(nullable = true) LocalDate expectedReturnDate,
      @Schema(nullable = true) @Size(max = 200) String repairVendor,
      @Schema(nullable = true) @Size(max = 200) String promotionPlan,
      @Schema(nullable = true) @Size(max = 2000) String note) {}

  /** 建 / 改处置单（录入用）。**没有处置总量这一栏** —— 数量由后端从质检单回读并按明细重算。 */
  public record DefectiveOrderInput(
      @NotNull UUID inspectionOrderId,
      /** 处置理由：决策要留理由。 */
      @NotBlank @Size(max = 2000) String reason,
      /** 币种（索赔跨币种，绝不合并）。 */
      @Schema(nullable = true) @Size(max = 8) String currencyCode,
      /** 索赔金额（登记口径）。 */
      @Schema(nullable = true) @DecimalMin("0") BigDecimal claimAmount,
      @Schema(nullable = true) @Size(max = 2000) String note,
      @NotNull @Valid List<DefectiveLineInput> lines) {}

  /** 处置单行（列表）。 */
  public record DefectiveOrderRow(
      UUID id,
      String dispositionNo,
      String status,
      String statusLabel,
      UUID inspectionOrderId,
      String inspectionNo,
      String inspectionResult,
      @Schema(nullable = true) UUID skuId,
      String skuCode,
      @Schema(nullable = true) String skuName,
      @Schema(nullable = true) UUID supplierId,
      @Schema(nullable = true) String supplierName,
      @Schema(nullable = true) String receiptCode,
      @Schema(nullable = true) String batchNo,
      BigDecimal inspectionLotSize,
      BigDecimal inspectionDefectiveQuantity,
      BigDecimal inspectionQualifiedQuantity,
      BigDecimal inspectionConcessionQuantity,
      BigDecimal sellableCeiling,
      BigDecimal grossDeductedQuantity,
      BigDecimal disposedQuantity,
      BigDecimal releasedQuantity,
      BigDecimal remainingQuantity,
      String dispositionSummary,
      String dispositionSummaryLabel,
      String inventoryStatus,
      String inventoryStatusLabel,
      String claimStatus,
      String claimStatusLabel,
      @Schema(nullable = true) String currencyCode,
      @Schema(nullable = true) BigDecimal claimAmount,
      long lineCount,
      long instructionCount,
      long claimCount,
      @Schema(nullable = true) UUID decidedBy,
      @Schema(nullable = true) OffsetDateTime decidedAt,
      OffsetDateTime createdAt,
      @Schema(nullable = true) OffsetDateTime completedAt) {}

  /** 处置明细（读出）。 */
  public record DefectiveLineRow(
      UUID id,
      String dispositionType,
      String dispositionTypeLabel,
      BigDecimal quantity,
      BigDecimal releasedQuantity,
      @Schema(nullable = true) String concessionReason,
      @Schema(nullable = true) String permissionCode,
      @Schema(nullable = true) LocalDate expectedReturnDate,
      @Schema(nullable = true) String repairVendor,
      @Schema(nullable = true) String promotionPlan,
      String note,
      OffsetDateTime createdAt) {}

  /** 库存状态变更指令（读出）。 */
  public record DefectiveInstructionRow(
      UUID id,
      UUID lineId,
      String action,
      String actionLabel,
      BigDecimal quantity,
      String ownerModule,
      String status,
      String statusLabel,
      String reason,
      @Schema(nullable = true) String externalReference,
      int attemptCount,
      @Schema(nullable = true) OffsetDateTime dispatchedAt,
      OffsetDateTime createdAt) {}

  /** 供应商索赔记录（读出）。 */
  public record DefectiveClaimRow(
      UUID id,
      String claimNo,
      String claimType,
      String claimTypeLabel,
      @Schema(nullable = true) UUID supplierId,
      @Schema(nullable = true) String supplierName,
      String currencyCode,
      BigDecimal claimedAmount,
      @Schema(nullable = true) BigDecimal agreedAmount,
      BigDecimal effectiveAmount,
      String status,
      String statusLabel,
      String financeStatus,
      String financeStatusLabel,
      @Schema(nullable = true) String financeReference,
      @Schema(nullable = true) OffsetDateTime submittedAt,
      @Schema(nullable = true) OffsetDateTime acknowledgedAt,
      @Schema(nullable = true) OffsetDateTime settledAt,
      String decidedReason,
      String note,
      OffsetDateTime createdAt) {}

  /** 处置事件（读出）。 */
  public record DefectiveEventRow(
      UUID id,
      String eventType,
      String eventTypeLabel,
      OffsetDateTime occurredAt,
      UUID actorId,
      String note,
      OffsetDateTime createdAt) {}

  /** 处置单详情。 */
  public record DefectiveOrderDetail(
      DefectiveOrderRow order,
      List<DefectiveLineRow> lines,
      List<DefectiveInstructionRow> instructions,
      List<DefectiveClaimRow> claims,
      List<DefectiveEventRow> events,
      String quantityNote,
      String concessionNote,
      String inventoryNote,
      String claimNote,
      List<String> actions,
      String note) {}

  /** 处置单列表页。 */
  public record DefectiveOrderPage(
      List<DefectiveOrderRow> rows, int page, int size, long total, int totalPages, String note) {}

  /** 汇总视图。 */
  public record DefectiveSummary(
      long orderCount,
      long draftCount,
      long decidedCount,
      long executingCount,
      long completedCount,
      long cancelledCount,
      long concessionOrderCount,
      long scrapOrderCount,
      long repairOrderCount,
      long returnExchangeOrderCount,
      long promotionOrderCount,
      BigDecimal disposedQuantity,
      BigDecimal releasedQuantity,
      BigDecimal pendingClaimAmount,
      BigDecimal settledClaimAmount,
      @Schema(nullable = true) String currencyCode,
      long instructionDispatchedCount,
      long instructionUnavailableCount,
      long instructionFailedCount,
      String note) {}

  /** 作废处置单（录入用）。理由必填 —— 作废掉的是一份凭据。 */
  public record DefectiveCancelInput(@NotBlank @Size(max = 2000) String cancelReason) {}

  /** 供应商认可赔付（录入用）。 */
  public record DefectiveClaimAgreeInput(
      @NotNull @DecimalMin("0") BigDecimal agreedAmount,
      @Schema(nullable = true) @Size(max = 2000) String note) {}

  /** 供应商拒赔 / 撤回索赔（录入用）。理由必填。 */
  public record DefectiveClaimReasonInput(@NotBlank @Size(max = 2000) String reason) {}
}
