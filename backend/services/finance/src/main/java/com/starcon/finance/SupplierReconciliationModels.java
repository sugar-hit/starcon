package com.starcon.finance;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 12.1.2.3 供应商对账（财务侧）的传输模型。
 *
 * <p>蓝图 12-finance.md 原文：
 *
 * <blockquote>
 *
 * 职责：财务侧供应商对账：核对采购侧对账单（6.5.1）与收货/质检数据，差异处理与确认，最终生成应付依据。 输入：供应商对账单；收货与质检明细；差异说明。 输出：对账确认结果 + 差异清单 + 应付生成依据。 关系：上游 = 6.5.1 采购发起；下游 = 12.1.2.1 应付。 <b>财务是最终确认方</b>，采购不能自行确认应付——这是职责分离。
 *
 * </blockquote>
 *
 * <p><b>命名。</b>统一用 {@code FinanceSupplierReconciliation*} 前缀，与既有七个服务的类型名零重叠 ——
 * 契约被 codegen 合并进一个扁平文件，同名后者覆盖前者且不报冲突。
 *
 * <p><b>金额一律 BigDecimal + NUMERIC</b>：本文件里没有一个 {@code double}。数量 {@code numeric(19,6)}、金额 {@code
 * numeric(19,4)}、汇率 {@code numeric(19,8)}。
 *
 * <p><b>币种分开</b>：任何汇总都按币种分组返回（{@link FinanceSupplierReconciliationCurrencySummary}），
 * USD 与 EUR 加起来不是任何一个金额 —— 全仓一致的纪律。
 */
public final class SupplierReconciliationModels {

  private SupplierReconciliationModels() {}

  /** 金额小数位（与 12.1.2.1 一致）。 */
  public static final int MONEY_SCALE = 4;

  /** 数量小数位。 */
  public static final int QUANTITY_SCALE = 6;

  // ================================================================ 枚举

  /** 财务侧对账单状态机。 */
  public enum ReconciliationStatus {
    /** 已导入：采购侧对账单的快照落库，尚未核对。 */
    IMPORTED("已导入"),
    /** 已核对：收货与质检都核过一遍。 */
    MATCHED("已核对"),
    /** 有差异待裁决。 */
    DIFFERENCES("有差异待裁决"),
    /** 财务已确认（终态）：已生成应付依据，整单冻结。 */
    CONFIRMED("财务已确认"),
    /** 财务已驳回（终态）：整单冻结。 */
    REJECTED("财务已驳回");

    private final String label;

    ReconciliationStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 终态：不可再改，只能新建一张对账单。 */
    public boolean terminal() {
      return this == CONFIRMED || this == REJECTED;
    }
  }

  /** 差异类型（与 DB 词表一致）。 */
  public enum DifferenceType {
    /** 收货数量与对账单不符。 */
    RECEIPT_QUANTITY_MISMATCH("收货数量不符"),
    /** 找不到对应的到货事实（依据缺失）。 */
    RECEIPT_EVIDENCE_MISSING("到货依据缺失"),
    /** 质检扣量未在对账单中扣除。 */
    QUALITY_DEDUCTION_NOT_APPLIED("质检扣量未扣除"),
    /** 有仓库收货单号但找不到质检记录（依据缺失）。 */
    QUALITY_EVIDENCE_MISSING("质检依据缺失"),
    /** 金额残差：数量与单价都解释不了的那部分。 */
    AMOUNT_RESIDUAL("金额残差");

    private final String label;

    DifferenceType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 差异处理状态。 */
  public enum DifferenceStatus {
    /** 未处理（还没有说明，也没有裁决）。 */
    OPEN("未处理"),
    /** 已登记说明，未裁决。 */
    EXPLAINED("已说明待裁决"),
    /** 已裁决（财务侧动作）。 */
    RESOLVED("已裁决");

    private final String label;

    DifferenceStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 差异裁决方式。
   *
   * <p>⚠️ 这两个值是**财务侧专属动作**：采购侧能登记说明，不能裁决 —— 裁决决定应付金额怎么调。
   */
  public enum DifferenceResolution {
    /** 采纳财务侧口径：应付按差额调整。 */
    APPLIED("按财务口径调整"),
    /** 不采纳：沿用采购侧口径，不调整金额。 */
    KEPT("沿用采购侧口径");

    private final String label;

    DifferenceResolution(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  // ================================================================ 行 / 列表

  /** 对账单头（一行）。 */
  public record FinanceSupplierReconciliationRow(
      UUID id,
      String reconNo,
      UUID upstreamReconId,
      String upstreamReconNo,
      UUID supplierRefId,
      String supplierCode,
      String supplierName,
      String periodType,
      LocalDate periodStart,
      LocalDate periodEnd,
      String statementCurrency,
      ReconciliationStatus status,
      String statusLabel,
      String upstreamStatus,
      BigDecimal statementReceivedAmount,
      BigDecimal statementDeductionAmount,
      BigDecimal statementClaimAmount,
      BigDecimal statementSuggestedAmount,
      BigDecimal statementTotalQuantity,
      @Schema(nullable = true) BigDecimal financeConfirmedAmount,
      @Schema(nullable = true) BigDecimal financeConfirmedQuantity,
      BigDecimal financeAdjustedAmount,
      int lineCount,
      int differenceCount,
      int openDifferenceCount,
      int matchedLineCount,
      int unmatchedLineCount,
      @Schema(nullable = true) OffsetDateTime upstreamSupplierConfirmedAt,
      String upstreamSupplierReference,
      @Schema(nullable = true) UUID confirmedBy,
      @Schema(nullable = true) OffsetDateTime confirmedAt,
      @Schema(nullable = true) UUID rejectedBy,
      @Schema(nullable = true) OffsetDateTime rejectedAt,
      String rejectReason,
      @Schema(nullable = true) UUID payableId,
      String payableNo,
      String note,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 按币种分开的汇总（**跨币种绝不相加**）。 */
  public record FinanceSupplierReconciliationCurrencySummary(
      String currency,
      BigDecimal statementAmount,
      @Schema(nullable = true) BigDecimal financeAmount,
      BigDecimal adjustmentAmount,
      long reconCount,
      long openDifferenceCount) {}

  /** 对账单列表（真分页 + 真筛选；汇总覆盖整个筛选结果集，不是当前页）。 */
  public record FinanceSupplierReconciliationPage(
      List<FinanceSupplierReconciliationRow> rows,
      int page,
      int size,
      long total,
      List<FinanceSupplierReconciliationCurrencySummary> summaries,
      long openDifferenceCount,
      String note) {}

  /** 对账明细行（一行 = 上游快照 + 财务侧核对结果）。 */
  public record FinanceSupplierReconciliationLineRow(
      UUID id,
      int lineNo,
      String lineType,
      String lineTypeLabel,
      String sourceType,
      String sourceRefCode,
      String sourceLineRefCode,
      OffsetDateTime occurredAt,
      @Schema(nullable = true) BigDecimal quantity,
      @Schema(nullable = true) String unitCode,
      @Schema(nullable = true) BigDecimal baseQuantity,
      @Schema(nullable = true) BigDecimal unitPrice,
      String currency,
      BigDecimal fxRate,
      String fxRateSource,
      BigDecimal amount,
      BigDecimal statementAmount,
      String upstreamQualityStatus,
      @Schema(nullable = true) BigDecimal upstreamQualityQuantity,
      boolean receiptMatched,
      @Schema(nullable = true) BigDecimal receiptArrivedQty,
      String receiptWarehouseCode,
      boolean qualityMatched,
      String qualityStatus,
      @Schema(nullable = true) BigDecimal qualityDeductedQty,
      @Schema(nullable = true) BigDecimal financeQuantity,
      @Schema(nullable = true) BigDecimal financeAmount,
      BigDecimal varianceQuantity,
      BigDecimal varianceAmount,
      String note) {}

  /** 明细行分页。 */
  public record FinanceSupplierReconciliationLinePage(
      List<FinanceSupplierReconciliationLineRow> rows,
      int page,
      int size,
      long total,
      long receiptLines,
      long claimLines,
      long deductionLines,
      BigDecimal statementAmount,
      @Schema(nullable = true) BigDecimal financeAmount,
      String note) {}

  /** 质检证据（一行 = 一张质检单的快照）。 */
  public record FinanceSupplierReconciliationInspectionRow(
      UUID id,
      UUID inspectionId,
      String inspectionNo,
      String receiptCode,
      String skuCode,
      BigDecimal arrivedQuantity,
      BigDecimal qualifiedQuantity,
      BigDecimal defectiveQuantity,
      BigDecimal concessionQuantity,
      BigDecimal deductedQuantity,
      BigDecimal deductedAmount,
      String currencyCode,
      String inspectionStatus,
      String inspectionResult,
      @Schema(nullable = true) OffsetDateTime completedAt) {}

  /** 差异行。 */
  public record FinanceSupplierReconciliationDifferenceRow(
      UUID id,
      @Schema(nullable = true) UUID lineId,
      int lineNo,
      DifferenceType differenceType,
      String differenceTypeLabel,
      DifferenceStatus status,
      String statusLabel,
      @Schema(nullable = true) BigDecimal statementValue,
      @Schema(nullable = true) BigDecimal financeValue,
      BigDecimal varianceQuantity,
      BigDecimal varianceAmount,
      String currency,
      @Schema(nullable = true) DifferenceResolution resolution,
      String resolutionLabel,
      BigDecimal adjustmentAmount,
      String resolutionNote,
      @Schema(nullable = true) UUID resolvedBy,
      @Schema(nullable = true) OffsetDateTime resolvedAt,
      String explanation,
      @Schema(nullable = true) UUID explainedBy,
      @Schema(nullable = true) OffsetDateTime explainedAt,
      OffsetDateTime createdAt) {}

  /** 差异清单分页。 */
  public record FinanceSupplierReconciliationDifferencePage(
      List<FinanceSupplierReconciliationDifferenceRow> rows,
      int page,
      int size,
      long total,
      long openCount,
      long explainedCount,
      long resolvedCount,
      String note) {}

  /** 差异说明（append-only 举证）。 */
  public record FinanceSupplierReconciliationExplanationRow(
      UUID id,
      UUID differenceId,
      String explanation,
      String providedRole,
      UUID providedBy,
      String evidenceRef,
      OffsetDateTime createdAt) {}

  /** 事件流（append-only 举证）。 */
  public record FinanceSupplierReconciliationEventRow(
      UUID id,
      String eventType,
      String fromStatus,
      String toStatus,
      String reason,
      @Schema(nullable = true) UUID actorId,
      String actorRole,
      OffsetDateTime occurredAt) {}

  /** 应付生成依据（对账单 ↔ 应付单）。 */
  public record FinanceSupplierReconciliationPayableBasisRow(
      UUID id,
      UUID payableId,
      String payableNo,
      BigDecimal amount,
      String currency,
      BigDecimal statementAmount,
      BigDecimal adjustmentAmount,
      String basisNote,
      UUID generatedBy,
      OffsetDateTime generatedAt) {}

  /** 对账单全景（头 + 明细 + 质检证据 + 差异 + 说明 + 事件流 + 应付依据）。 */
  public record FinanceSupplierReconciliationDetail(
      FinanceSupplierReconciliationRow head,
      List<FinanceSupplierReconciliationLineRow> lines,
      List<FinanceSupplierReconciliationInspectionRow> inspections,
      List<FinanceSupplierReconciliationDifferenceRow> differences,
      List<FinanceSupplierReconciliationExplanationRow> explanations,
      List<FinanceSupplierReconciliationEventRow> events,
      @Schema(nullable = true) FinanceSupplierReconciliationPayableBasisRow payableBasis,
      List<String> actions,
      String note) {}

  // ================================================================ 能力声明 / 下拉

  /** 一条能力声明。 */
  public record FinanceSupplierReconciliationCapability(
      String moduleId,
      String title,
      boolean available,
      String basis,
      String ownerModule,
      String detail) {}

  /** 一个下游缺口。 */
  public record FinanceSupplierReconciliationGap(
      String moduleId,
      String title,
      String service,
      boolean available,
      String area,
      String detail) {}

  /** 能力声明。 */
  public record FinanceSupplierReconciliationCapabilities(
      List<FinanceSupplierReconciliationCapability> capabilities,
      List<FinanceSupplierReconciliationGap> gaps) {}

  /** 下拉项（全部来自真实数据，不是写死的词表）。 */
  public record FinanceSupplierReconciliationOptions(
      List<String> statuses,
      List<String> upstreamStatuses,
      List<String> currencies,
      List<FinanceSupplierReconciliationSupplierOption> suppliers) {}

  /** 供应商下拉项。 */
  public record FinanceSupplierReconciliationSupplierOption(UUID refId, String code, String name) {}

  // ================================================================ 动作结果

  /** 导入结果（幂等）。 */
  public record FinanceSupplierReconciliationImportResult(
      long scanned,
      long imported,
      long refreshed,
      long skipped,
      boolean truncated,
      List<FinanceSupplierReconciliationRow> rows,
      List<String> warnings) {}

  /** 核对结果。 */
  public record FinanceSupplierReconciliationMatchResult(
      UUID reconId,
      int matchedLines,
      int unmatchedLines,
      int raisedDifferences,
      int openDifferences,
      List<String> notes) {}

  // ================================================================ 入参

  /** 登记差异说明。 */
  public record FinanceSupplierReconciliationExplanationInput(
      String explanation, String providedRole, String evidenceRef) {}

  /** 裁决差异。 */
  public record FinanceSupplierReconciliationResolveInput(
      String resolution, String note) {}

  /** 驳回对账单。 */
  public record FinanceSupplierReconciliationRejectInput(String reason) {}
}
