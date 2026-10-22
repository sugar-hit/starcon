package com.starcon.channel;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 16.6.2.1 数据出境与留存的传输模型。
 *
 * <p>蓝图原文：「管理数据合规：跨境数据传输登记与合法性基础、数据留存期配置、到期清理与删除请求（被遗忘权）、
 * 保留期与财务留存期的区分。输入：数据流登记；留存策略；删除请求。
 * 输出：合规登记 + 清理/删除执行记录。关系：上游 = 17 权限、全部数据域；下游 = 数据清理任务。
 * <b>财务数据有法定留存期，PII 有 GDPR 删除权，两者冲突时按财务留存优先</b>——实体目录 §5 已明确这条边界。」
 *
 * <p>🔴 <b>本文件最重要的一条纪律：「财务留存优先」不是一句说明，是一组可判定的字段。</b>
 *
 * <ul>
 *   <li>{@code RetentionPolicyView.retentionClass} —— {@code STANDARD}（普通留存期）还是
 *       {@code FINANCIAL}（法定财务留存期）。**两类在模型上就是两种东西**，
 *       不是一个带 flag 的期限。</li>
 *   <li>{@code RetentionConflictView} —— 冲突对（普通那条 vs 财务那条）、双方期限、
 *       {@code resolution}（裁决结果）、{@code rationale}（依据）**四件齐全**，
 *       界面上能直接把「为什么这么裁」讲清楚。</li>
 *   <li>{@code RetentionConflictView.deletionBlocked} + {@code deletionBlockedUntil} ——
 *       法定留存期没走完之前，删除**被推迟**（不是被拒绝）：推到哪一天写清楚。</li>
 *   <li>{@code DataGovernanceDeletionRequestView.status = DEFERRED} ——
 *       「推迟」是<b>独立于</b>「驳回」的一个状态。把它折进 REJECTED，
 *       运营就分不清「被拒」与「被推迟到某天」，合规台账上这两件事完全不同。</li>
 * </ul>
 *
 * <p>🔴 第二个纪律：**没登记 / 读不到 / 判不了，三件事在模型上必须能分开。**
 * {@code available=false} + {@code ownerModule} 是「上游还没交付」的形状，
 * {@code configured=false} 是「还没登记」的形状，
 * {@code known=false} 是「查无此物」的形状 —— 折成一个空值，界面就会撒谎。
 */
public final class DataGovernanceModels {

  private DataGovernanceModels() {}

  // ================================================================ 能力声明

  /** 逐个上游读面的可用性。 */
  public record DataGovernanceUpstreamState(
      String sourceCode,
      String sourceLabel,
      boolean available,
      /** 这个上游归哪个模块（available=false 时必填）。 */
      String ownerModule,
      String note) {}

  /** 能力声明。 */
  public record DataGovernanceCapabilities(
      List<DataGovernanceUpstreamState> upstreams,
      String transferSemantics,
      String retentionSemantics,
      String conflictSemantics,
      String deletionSemantics,
      String cleanupSemantics,
      String auditSemantics,
      String boundaryNote,
      String downstreamNote) {}

  // ================================================================ 数据域

  /** 数据域视图。 */
  public record DataGovernanceDomainView(
      UUID domainId,
      String domainCode,
      String domainName,
      String ownerModule,
      String dataClass,
      String dataClassLabel,
      boolean containsPii,
      boolean statutoryFinancial,
      String note,
      long version,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 数据域登记 / 修改（upsert，按 domain_code）。 */
  public record DataGovernanceDomainInput(
      @NotBlank @Size(max = 64) String domainCode,
      @NotBlank @Size(max = 120) String domainName,
      @NotBlank @Size(max = 120) String ownerModule,
      @NotBlank @Pattern(regexp = "PII|FINANCIAL|OPERATIONAL|MARKETING") String dataClass,
      @Schema(nullable = true) Boolean containsPii,
      @Schema(nullable = true) Boolean statutoryFinancial,
      @Size(max = 600) String note) {}

  /** 数据域列表（真分页 + 真筛选）。 */
  public record DataGovernanceDomainPage(
      List<DataGovernanceDomainView> items, int page, int size, long total) {}

  // ================================================================ 跨境传输

  /** 跨境数据传输登记视图。 */
  public record DataGovernanceTransferView(
      UUID transferId,
      String transferNo,
      String sourceDomainCode,
      String sourceDomainName,
      String targetRegion,
      String recipient,
      String legalBasis,
      String legalBasisLabel,
      String safeguards,
      String tiaRef,
      String status,
      String statusLabel,
      UUID registeredBy,
      String registeredByName,
      OffsetDateTime registeredAt,
      OffsetDateTime effectiveFrom,
      @Schema(nullable = true) OffsetDateTime effectiveTo,
      String suspendReason,
      String endReason,
      String note,
      long version,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 跨境传输登记入参。 */
  public record DataGovernanceTransferInput(
      @NotBlank @Size(max = 64) String sourceDomainCode,
      @NotBlank @Pattern(regexp = "[A-Z]{2}") String targetRegion,
      @NotBlank @Size(max = 200) String recipient,
      @NotBlank
          @Pattern(regexp = "CONSENT|CONTRACT|LEGAL_OBLIGATION|LEGITIMATE_INTEREST")
          String legalBasis,
      @Size(max = 600) String safeguards,
      @Size(max = 120) String tiaRef,
      @NotNull OffsetDateTime effectiveFrom,
      @Size(max = 600) String note) {}

  /** 暂停 / 终止入参（**理由必填**）。 */
  public record DataGovernanceTransferStatusInput(@NotBlank @Size(max = 600) String reason) {}

  /** 跨境传输列表（真分页 + 真筛选 + 汇总）。 */
  public record DataGovernanceTransferPage(
      List<DataGovernanceTransferView> items,
      int page,
      int size,
      long total,
      DataGovernanceTransferCounts counts) {}

  /** 跨境传输汇总计数。 */
  public record DataGovernanceTransferCounts(long total, long active, long suspended, long ended) {}

  // ================================================================ 留存策略

  /** 留存策略视图。 */
  public record RetentionPolicyView(
      UUID policyId,
      String domainCode,
      String domainName,
      String policyName,
      String retentionClass,
      String retentionClassLabel,
      BigDecimal retentionDays,
      String retentionTrigger,
      String retentionTriggerLabel,
      String legalBasis,
      OffsetDateTime effectiveFrom,
      @Schema(nullable = true) OffsetDateTime effectiveTo,
      String status,
      String statusLabel,
      @Schema(nullable = true) UUID supersedesId,
      int versionNo,
      UUID createdBy,
      OffsetDateTime createdAt) {}

  /** 留存策略入参（**新增一个版本**，旧版本自动置 SUPERSEDED）。 */
  public record RetentionPolicyInput(
      @NotBlank @Size(max = 64) String domainCode,
      @NotBlank @Size(max = 120) String policyName,
      @NotBlank @Pattern(regexp = "STANDARD|FINANCIAL") String retentionClass,
      @NotNull @DecimalMin("0") @Digits(integer = 13, fraction = 6) BigDecimal retentionDays,
      @NotBlank @Pattern(regexp = "CREATION|CLOSURE") String retentionTrigger,
      @Size(max = 200) String legalBasis,
      @NotNull OffsetDateTime effectiveFrom) {}

  /** 留存策略列表（真分页 + 真筛选）。 */
  public record RetentionPolicyPage(
      List<RetentionPolicyView> items, int page, int size, long total) {}

  // ================================================================ 冲突裁决

  /**
   * 「保留期 vs 财务留存期」的冲突裁决视图。
   *
   * <p>🔴 这是本模块的**可见裁决展示**：冲突对、双方期限、裁决结果与依据，四件齐全。
   */
  public record RetentionConflictView(
      String domainCode,
      String domainName,
      @Schema(nullable = true) UUID standardPolicyId,
      @Schema(nullable = true) BigDecimal standardRetentionDays,
      String standardLegalBasis,
      @Schema(nullable = true) UUID financialPolicyId,
      @Schema(nullable = true) BigDecimal financialRetentionDays,
      String financialLegalBasis,
      String conflictKind,
      String conflictKindLabel,
      String resolution,
      String resolutionLabel,
      @Schema(nullable = true) BigDecimal effectiveRetentionDays,
      /** 法定留存期走完的那一天（此前删除被推迟）。 */
      @Schema(nullable = true) OffsetDateTime deletionBlockedUntil,
      /** 现在能不能删（false = 被财务留存期挡住）。 */
      boolean deletionBlocked,
      String rationale,
      @Schema(nullable = true) OffsetDateTime lastAdjudicatedAt) {}

  /** 冲突裁决列表（真分页 + 真筛选 + 汇总）。 */
  public record RetentionConflictPage(
      List<RetentionConflictView> items,
      int page,
      int size,
      long total,
      RetentionConflictCounts counts) {}

  /** 冲突裁决汇总计数。 */
  public record RetentionConflictCounts(long total, long conflicting, long deletionBlocked) {}

  // ================================================================ 删除请求

  /** 删除请求视图。 */
  public record DataGovernanceDeletionRequestView(
      UUID requestId,
      String requestNo,
      String requestType,
      String requestTypeLabel,
      String subjectKind,
      String subjectRef,
      String domainCode,
      String domainName,
      String reason,
      String status,
      String statusLabel,
      UUID requestedBy,
      String requestedByName,
      OffsetDateTime requestedAt,
      @Schema(nullable = true) UUID approvedBy,
      @Schema(nullable = true) String approvedByName,
      @Schema(nullable = true) OffsetDateTime approvedAt,
      String decisionNote,
      @Schema(nullable = true) UUID executedBy,
      @Schema(nullable = true) String executedByName,
      @Schema(nullable = true) OffsetDateTime executedAt,
      /** 被财务留存期推迟 ⇒ 推迟到那一天。 */
      @Schema(nullable = true) OffsetDateTime deferUntil,
      String deferReason,
      @Schema(nullable = true) UUID cleanupWorkOrderId,
      @Schema(nullable = true) String cleanupOrderNo,
      OffsetDateTime createdAt) {}

  /** 删除请求入参（**理由必填**）。 */
  public record DataGovernanceDeletionRequestInput(
      @NotBlank @Pattern(regexp = "FORGETTING_RIGHT|CLEANUP") String requestType,
      @NotBlank @Size(max = 24) String subjectKind,
      @NotBlank @Size(max = 160) String subjectRef,
      @NotBlank @Size(max = 64) String domainCode,
      @NotBlank @Size(max = 600) String reason) {}

  /** 审批入参（备注可空；驳回理由另走 {@link DataGovernanceRejectInput}）。 */
  public record DataGovernanceDecisionInput(@Schema(nullable = true) @Size(max = 600) String decisionNote) {}

  /** 驳回入参（**理由必填**）。 */
  public record DataGovernanceRejectInput(@NotBlank @Size(max = 600) String decisionNote) {}

  /** 删除请求列表（真分页 + 真筛选 + 汇总）。 */
  public record DataGovernanceDeletionPage(
      List<DataGovernanceDeletionRequestView> items,
      int page,
      int size,
      long total,
      DataGovernanceDeletionCounts counts) {}

  /** 删除请求汇总计数。 */
  public record DataGovernanceDeletionCounts(
      long total, long pending, long approved, long deferred, long rejected, long executed) {}

  // ================================================================ 清理工单

  /** 清理工单视图。 */
  public record RetentionCleanupOrderView(
      UUID orderId,
      String orderNo,
      String domainCode,
      String domainName,
      String subjectRef,
      String triggerKind,
      String triggerKindLabel,
      BigDecimal retentionDays,
      String retentionClass,
      String retentionClassLabel,
      BigDecimal affectedRecordCount,
      String status,
      String statusLabel,
      OffsetDateTime eligibleAt,
      OffsetDateTime dueAt,
      UUID requestedBy,
      String requestedByName,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 清理工单入参。 */
  public record RetentionCleanupOrderInput(
      @NotBlank @Size(max = 64) String domainCode,
      @Size(max = 160) String subjectRef,
      @NotBlank @Pattern(regexp = "RETENTION_EXPIRY|DELETION_REQUEST|MANUAL") String triggerKind,
      /** 期望完成清理的截止日（可空 ⇒ 由服务层按留存期算）。 */
      @Schema(nullable = true) OffsetDateTime dueAt,
      @Schema(nullable = true)
          @DecimalMin("0")
          @Digits(integer = 13, fraction = 6)
          BigDecimal affectedRecordCount) {}

  /** 清理工单列表（真分页 + 真筛选 + 汇总）。 */
  public record RetentionCleanupPage(
      List<RetentionCleanupOrderView> items,
      int page,
      int size,
      long total,
      RetentionCleanupCounts counts) {}

  /** 清理工单汇总计数。 */
  public record RetentionCleanupCounts(
      long total, long pending, long inProgress, long completed, long partial, long failed) {}

  // ================================================================ 清理执行记录

  /** 清理执行入参（**结果必填**；条数是数量 ⇒ BigDecimal）。 */
  public record RetentionCleanupExecuteInput(
      @NotBlank @Pattern(regexp = "SUCCESS|PARTIAL|FAILED") String result,
      @NotNull @DecimalMin("0") @Digits(integer = 13, fraction = 6) BigDecimal recordCount,
      @Size(max = 600) String message) {}

  /** 清理执行记录视图。 */
  public record RetentionCleanupRecordView(
      UUID recordId,
      UUID orderId,
      String orderNo,
      String domainCode,
      String domainName,
      String action,
      String actionLabel,
      String result,
      String resultLabel,
      BigDecimal recordCount,
      String message,
      UUID executedBy,
      String executedByName,
      OffsetDateTime executedAt) {}

  /** 清理执行记录列表（真分页 + 真筛选）。 */
  public record RetentionCleanupRecordPage(
      List<RetentionCleanupRecordView> items, int page, int size, long total) {}
}
