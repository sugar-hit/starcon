package com.starcon.finance;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 12.1.1.3 坏账与核销的 DTO。
 *
 * <p>蓝图 12-finance.md：「处理坏账：争议损失确认、买家拒付、平台冻结无法收回的核销，保留核销依据与审批。」输入：「核销申请；依据材料；审批。」输出：「坏账核销记录 + 会计处理 + 审计留痕。」
 *
 * <p><b>命名空间纪律</b>：本模块用 {@code finance/bad-debt}，与 12.1.1.1 的 {@code finance/receivables}、12.1.1.2 的 {@code finance/receivable-aging} 各自独立 —— 三个模块的端点、契约、页面都归各自所有。
 *
 * <p><b>金额纪律</b>：全部 {@link BigDecimal}（NUMERIC(19,4)），**没有一处浮点**。
 */
public final class BadDebtModels {

  private BadDebtModels() {}

  // ================================================================ 枚举

  /**
   * 坏账事由（蓝图职责里的三个动词 + 一个兜底）。
   *
   * <p>枚举穷举：不在词表里的事由**写不进库**（DB 层 CHECK）。
   */
  public enum BadDebtReasonType {
    /** 争议损失确认（11.4.1.1 纠纷我方败诉）。 */
    DISPUTE_LOSS("争议损失确认"),
    /** 买家拒付。 */
    BUYER_REFUSAL("买家拒付"),
    /** 平台冻结无法收回。 */
    PLATFORM_FROZEN("平台冻结无法收回"),
    /** 其他（必须写明明细）。 */
    OTHER("其他");

    private final String label;

    BadDebtReasonType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 申请单状态机。
   *
   * <p>DRAFT → PENDING_APPROVAL → APPROVED → WRITTEN_OFF；PENDING_APPROVAL → REJECTED；任意非终态 → VOID。
   *
   * <p>⚠️ **没有「批量核销」这个状态，也没有批量核销这个动作** ——
   * 蓝图加粗句「不允许批量一键核销无审批」在枚举层面就已经排除了它。
   */
  public enum BadDebtStatus {
    DRAFT("草稿"),
    PENDING_APPROVAL("待审批"),
    APPROVED("已批准待核销"),
    REJECTED("已驳回"),
    WRITTEN_OFF("已核销"),
    VOID("已作废");

    private final String label;

    BadDebtStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 终态：整行冻结，一列都不许再改。 */
    public boolean terminal() {
      return this == REJECTED || this == WRITTEN_OFF || this == VOID;
    }
  }

  /** 依据材料类型。 */
  public enum BadDebtEvidenceType {
    PLATFORM_DISPUTE_DECISION("平台裁决"),
    PLATFORM_FROZEN_NOTICE("平台冻结通知"),
    BUYER_REFUSAL("买家拒付凭证"),
    CHARGEBACK("拒付/退单记录"),
    INTERNAL_REVIEW("内部复核"),
    OTHER("其他");

    private final String label;

    BadDebtEvidenceType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 核销路径。
   *
   * <p>本模块**只有一条路径**：作废原应收单 + 为仍可收回的余额新建承接单。
   *
   * <p>⚠️ 刻意**没有**「直接写行级 settled_amount」那一条 ——
   * {@code finance_receivable_line.settled_amount} 的写者恒为 12.2.1.2 核销（V338 注释原文）。
   */
  public enum BadDebtWriteOffMethod {
    VOID_AND_REPLACE("作废原单并新建承接单");

    private final String label;

    BadDebtWriteOffMethod(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  // ================================================================ 记录

  /**
   * 一条坏账核销申请。
   *
   * @param receivableAmount 核销**当时**目标应收的总额（快照）。
   * @param receivableSettled 核销**当时**目标应收的已核销额（快照）。
   * @param openBalance 核销**当时**目标应收的未核销余额（快照 = 总额 − 已核销额）。
   * @param writeOffAmount 本次核销金额。
   * @param accountingStatus 恒为 {@code PENDING_FINANCE}（下游 12.5.2.1 未交付 ⇒ **会计处理不可用**）。
   * @param accountingOwnerModule 会计处理归属模块，恒为 {@code 12.5.2.1}。
   * @param accountingRef 凭证号，**恒为空串** —— 绝不造凭证。
   * @param replacementDueDateAdjusted 承接单到期日是否被调整过（12.1.1.1 的建单端点不允许到期日早于确认日）。
   */
  public record FinanceBadDebtRow(
      UUID id,
      String caseNo,
      UUID receivableId,
      String receivableNo,
      @Schema(nullable = true) UUID shopId,
      String shopName,
      @Schema(nullable = true) UUID counterpartyRef,
      String counterpartyName,
      String currency,
      BigDecimal receivableAmount,
      BigDecimal receivableSettled,
      BigDecimal openBalance,
      BigDecimal writeOffAmount,
      BadDebtReasonType reasonType,
      String reasonDetail,
      @Schema(nullable = true) UUID disputeId,
      String disputeNo,
      String disputeStatus,
      @Schema(nullable = true) BigDecimal disputeAmount,
      @Schema(nullable = true) String disputeCurrency,
      BadDebtStatus status,
      @Schema(nullable = true) UUID submittedBy,
      @Schema(nullable = true) OffsetDateTime submittedAt,
      @Schema(nullable = true) UUID decidedBy,
      @Schema(nullable = true) OffsetDateTime decidedAt,
      String decisionComment,
      @Schema(nullable = true) OffsetDateTime writeOffAt,
      @Schema(nullable = true) BadDebtWriteOffMethod writeOffMethod,
      String voidedReceivableNo,
      @Schema(nullable = true) UUID replacementReceivableId,
      String replacementReceivableNo,
      boolean replacementDueDateAdjusted,
      String accountingStatus,
      String accountingOwnerModule,
      String accountingRef,
      String note,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 一项依据材料。 */
  public record FinanceBadDebtEvidenceRow(
      UUID id,
      UUID caseId,
      int evidenceNo,
      BadDebtEvidenceType evidenceType,
      String title,
      String referenceNo,
      String content,
      String attachmentRef,
      @Schema(nullable = true) LocalDate occurredOn,
      @Schema(nullable = true) UUID createdBy,
      OffsetDateTime createdAt) {}

  /** 一条审计留痕（append-only）。 */
  public record FinanceBadDebtEventRow(
      UUID id,
      UUID caseId,
      String eventType,
      String fromStatus,
      String toStatus,
      String reason,
      @Schema(nullable = true) UUID actorId,
      OffsetDateTime occurredAt) {}

  /**
   * 可执行动作。
   *
   * <p>⚠️ 每个动作都带 {@code blockedReason}：不可执行时界面要能**说清楚为什么**，而不是把按钮灰掉让人猜。
   */
  public record FinanceBadDebtAction(
      String code,
      String label,
      boolean allowed,
      String method,
      String path,
      String blockedReason) {}

  /** 申请单详情：单头 + 依据材料 + 审计留痕 + 当前可执行动作。 */
  public record FinanceBadDebtDetail(
      FinanceBadDebtRow row,
      List<FinanceBadDebtEvidenceRow> evidence,
      List<FinanceBadDebtEventRow> events,
      List<FinanceBadDebtAction> actions) {}

  /** 一个币种的合计。 */
  public record FinanceBadDebtCurrencyTotal(
      String currency, long count, BigDecimal writeOffAmount, long writtenOffCount) {}

  /**
   * 申请单列表页。
   *
   * @param totals 汇总**覆盖整个筛选结果集**，不是当前页。
   */
  public record FinanceBadDebtPage(
      List<FinanceBadDebtRow> items,
      int page,
      int size,
      long total,
      List<FinanceBadDebtCurrencyTotal> totals) {}

  /** 坏账汇总：按状态、按事由、按币种。 */
  public record FinanceBadDebtSummary(
      long totalCount,
      long pendingApprovalCount,
      long approvedCount,
      long writtenOffCount,
      long rejectedCount,
      List<FinanceBadDebtCurrencyTotal> currencyTotals,
      List<FinanceBadDebtReasonTotal> reasonTotals,
      String note) {}

  /** 一个事由的合计。 */
  public record FinanceBadDebtReasonTotal(
      BadDebtReasonType reasonType, String label, long count, BigDecimal writeOffAmount) {}

  /** 筛选项下拉（全部来自**真实数据**）。 */
  public record FinanceBadDebtOptions(
      List<String> statuses,
      List<String> reasonTypes,
      List<String> currencies,
      List<FinanceBadDebtShopOption> shops,
      List<FinanceBadDebtReasonTypeOption> reasonTypeOptions,
      List<FinanceBadDebtStatusOption> statusOptions,
      List<FinanceBadDebtEvidenceTypeOption> evidenceTypeOptions) {}

  /** 店铺筛选项。 */
  public record FinanceBadDebtShopOption(UUID shopId, String shopName, long caseCount) {}

  /** 事由下拉项（带中文标签）。 */
  public record FinanceBadDebtReasonTypeOption(String code, String label) {}

  /** 状态下拉项（带中文标签）。 */
  public record FinanceBadDebtStatusOption(String code, String label) {}

  /** 依据类型下拉项（带中文标签）。 */
  public record FinanceBadDebtEvidenceTypeOption(String code, String label) {}

  // ================================================================ 能力声明

  /** 能力声明：一个职责句的一个要素。 */
  public record FinanceBadDebtCapability(
      String module,
      String name,
      boolean available,
      String basis,
      String ownerModule,
      String detail) {}

  /** 上下游缺口：谁没交付、归谁。 */
  public record FinanceBadDebtGap(
      String module,
      String name,
      String ownerService,
      boolean available,
      String group,
      String detail) {}

  /** 12.1.1.3 的能力声明。 */
  public record FinanceBadDebtCapabilities(
      List<FinanceBadDebtCapability> capabilities, List<FinanceBadDebtGap> gaps) {}

  // ================================================================ 输入

  /**
   * 提交坏账核销申请。
   *
   * @param writeOffAmount 本次核销金额（0 &lt; 金额 ≤ 目标应收的未核销余额）。
   * @param disputeId 11.4.1.1 纠纷（trade 服务）id；可空。
   */
  public record FinanceBadDebtInput(
      UUID receivableId,
      BigDecimal writeOffAmount,
      BadDebtReasonType reasonType,
      String reasonDetail,
      @Schema(nullable = true) UUID disputeId,
      @Schema(nullable = true) String note) {}

  /** 加一条依据材料。 */
  public record FinanceBadDebtEvidenceInput(
      BadDebtEvidenceType evidenceType,
      String title,
      @Schema(nullable = true) String referenceNo,
      String content,
      @Schema(nullable = true) String attachmentRef,
      @Schema(nullable = true) LocalDate occurredOn) {}

  /**
   * 审批（通过 / 驳回）。
   *
   * @param comment 审批意见 —— **必填**（DB 层 CHECK + 服务层各判一次）。
   */
  public record FinanceBadDebtDecisionInput(String comment) {}

  /** 作废申请。理由必填。 */
  public record FinanceBadDebtVoidInput(String reason) {}
}
