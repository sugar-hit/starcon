package com.starcon.platform;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 18.3.3.1「自动审批」的契约类型。
 *
 * <p>蓝图：<b>「职责：自动化审批场景：自动通过低风险小额单据、自动驳回明显不合规单据、
 * 金额分层路由审批人、超时自动升级。输入：审批规则配置；单据风险信号。
 * 输出：自动审批结果 + 决策依据记录。关系：上游 = 17.4 审批；下游 = 各业务系统。
 * 自动审批只处理"明确安全"的场景（小额 + 供应商已验证 + 无风险信号），其余仍走人工——审批是内控底线。」</b>
 *
 * <p>🔴 <b>本模块拥有的是「决策」，不是「审批实例」</b>：上游 17.4（identity 服务）独家拥有
 * {@code approval_instance} 与 {@code approval_instance_action}（谁在哪张单上做了什么动作）。
 * 本模块只回答两个问题：<b>这张单该不该自动放行</b>、<b>依据是什么</b>。
 *
 * <p>🔴 <b>调用方不能自带信号的「性质」</b>：{@link AutoApprovalRiskSignalInput} 里只有**码**，
 * 性质（确定性违规 / 主观风险）由**租户自己的信号码表**决定；码表里查不到的码按**主观风险**从严处理
 * ⇒ 编一个码就能把单子驳掉这件事在本模块里不可能发生。
 */
public final class AutoApprovalModels {

  private AutoApprovalModels() {}

  // ================================================================ 规则配置

  /** 自动审批规则配置的一行。 */
  public record AutoApprovalPolicyRow(
      UUID id,
      String ruleCode,
      String ruleName,
      String documentTypeCode,
      String currencyCode,
      @Schema(nullable = true) BigDecimal autoApproveMaxAmount,
      @Schema(nullable = true) BigDecimal amountHardLimit,
      @Schema(nullable = true) UUID upstreamFlowId,
      String status,
      int priority,
      String remark,
      @Schema(nullable = true) UUID createdBy,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /**
   * 规则配置的写入体。
   *
   * <p>🔴 {@code autoApproveMaxAmount}（小额天花板）与 {@code amountHardLimit}（硬上限）是**两个**数：
   * 前者之上 ⇒ 走人工（金额大不是「明显不合规」）；后者之上 ⇒ 金额越界 ⇒ **可**自动驳回。
   */
  public record AutoApprovalPolicyInput(
      @NotBlank @Size(max = 64) String ruleCode,
      @NotBlank @Size(max = 120) String ruleName,
      @NotBlank @Size(max = 32) String documentTypeCode,
      @NotBlank @Pattern(regexp = "[A-Z]{3}") String currencyCode,
      @Schema(nullable = true) BigDecimal autoApproveMaxAmount,
      @Schema(nullable = true) BigDecimal amountHardLimit,
      @Schema(nullable = true) UUID upstreamFlowId,
      @Schema(nullable = true) @Pattern(regexp = "ACTIVE|DRAFT|DISABLED") String status,
      @Schema(nullable = true) Integer priority,
      @Schema(nullable = true) @Size(max = 500) String remark) {}

  // ================================================================ 风险信号码表

  /** 风险信号码表的一行。 */
  public record AutoApprovalSignalRow(
      UUID id,
      String signalCode,
      String signalName,
      String signalKind,
      String description,
      String status,
      OffsetDateTime createdAt) {}

  /**
   * 风险信号码的写入体。
   *
   * <p>🔴 {@code signalKind} 决定「能不能自动驳回」：
   * {@code VIOLATION}（确定性违规）可以；{@code RISK}（主观风险，如「风险偏高」）**绝不**可以，只走人工。
   */
  public record AutoApprovalSignalInput(
      @NotBlank @Size(max = 64) String signalCode,
      @NotBlank @Size(max = 120) String signalName,
      @NotNull @Pattern(regexp = "VIOLATION|RISK") String signalKind,
      @Schema(nullable = true) @Size(max = 300) String description) {}

  // ================================================================ 判定

  /** 提交上来的一条风险信号。**只有码**：性质由租户码表决定，不由调用方自称。 */
  public record AutoApprovalRiskSignalInput(
      @NotBlank @Size(max = 64) String code, @Schema(nullable = true) @Size(max = 300) String detail) {}

  /**
   * 一次自动审批判定的入参。
   *
   * <p>🔴 <b>没有 tenantId</b>：租户来自已认证成员的成员关系，{@code X-Tenant-Id} 头不参与判定。
   *
   * <p>⚠️ {@code amount} 与 {@code currencyCode} 要么都有、要么都没有
   * （与上游 17.4 的提交入口同口径：只有金额没有币种，「小额」没有唯一答案）。
   */
  public record AutoApprovalEvaluateInput(
      @NotBlank @Size(max = 32) String documentTypeCode,
      @NotBlank @Size(max = 100) String documentRef,
      @Schema(nullable = true) @Size(max = 200) String subjectTitle,
      @NotBlank @Size(max = 40) String sourceSystem,
      @Schema(nullable = true) @Pattern(regexp = "[A-Z]{3}") String currencyCode,
      @Schema(nullable = true) BigDecimal amount,
      @Schema(nullable = true) UUID supplierId,
      @Schema(nullable = true) Boolean supplierVerified,
      @Schema(nullable = true) List<@Valid AutoApprovalRiskSignalInput> riskSignals) {}

  /** 一次判定里的**逐条依据**：哪几项条件成立、哪一项不成立。 */
  public record AutoApprovalCheck(String code, boolean passed, String detail) {}

  /** 判定当时收到的信号及其**性质**（快照）。 */
  public record AutoApprovalSignalSnapshot(
      String code, String name, String kind, boolean registered, String detail) {}

  /**
   * 金额分层路由的结论（快照）。
   *
   * <p>🔴 分层边界来自**上游那条审批流的金额分支**（{@code approval_flow_amount_branch}），
   * 本模块**只读不另造**；区间语义与上游同口径：{@code [min_amount, max_amount)} 左闭右开。
   */
  public record AutoApprovalRoutingSnapshot(
      boolean routed,
      @Schema(nullable = true) UUID flowId,
      @Schema(nullable = true) String flowName,
      @Schema(nullable = true) String currencyCode,
      @Schema(nullable = true) String branchName,
      @Schema(nullable = true) BigDecimal minAmount,
      @Schema(nullable = true) BigDecimal maxAmount,
      @Schema(nullable = true) String targetNodeKey,
      @Schema(nullable = true) String targetNodeName,
      @Schema(nullable = true) String approverType,
      @Schema(nullable = true) UUID roleId,
      @Schema(nullable = true) UUID positionId,
      @Schema(nullable = true) UUID membershipId,
      @Schema(nullable = true) String upstreamNodeKey,
      @Schema(nullable = true) String upstreamRoutedBy,
      String note) {}

  /** 判定当时生效的那条规则（**快照**：不是事后读当下的规则）。 */
  public record AutoApprovalPolicySnapshot(
      boolean found,
      @Schema(nullable = true) UUID policyId,
      @Schema(nullable = true) String ruleCode,
      @Schema(nullable = true) String ruleName,
      @Schema(nullable = true) String documentTypeCode,
      @Schema(nullable = true) String currencyCode,
      @Schema(nullable = true) BigDecimal autoApproveMaxAmount,
      @Schema(nullable = true) BigDecimal amountHardLimit,
      @Schema(nullable = true) UUID upstreamFlowId,
      @Schema(nullable = true) String status,
      @Schema(nullable = true) OffsetDateTime updatedAt) {}

  /**
   * 一次自动审批判定的结论（输出）。
   *
   * <p>🔴 {@code decision} 只有三个取值，没有第四个：
   * {@code AUTO_APPROVED}（明确安全，自动通过）、{@code AUTO_REJECTED}（有确定性违规，自动驳回）、
   * {@code MANUAL_REQUIRED}（其余一切，走人工）。
   */
  public record AutoApprovalDecisionView(
      UUID id,
      String decisionNo,
      String documentTypeCode,
      String documentRef,
      String sourceSystem,
      @Schema(nullable = true) BigDecimal amount,
      @Schema(nullable = true) String currencyCode,
      @Schema(nullable = true) UUID supplierId,
      boolean supplierVerified,
      String decision,
      String reasonCode,
      String reasonMessage,
      String executionState,
      List<AutoApprovalCheck> checks,
      @Schema(nullable = true) AutoApprovalPolicySnapshot policy,
      List<AutoApprovalSignalSnapshot> riskSignals,
      @Schema(nullable = true) AutoApprovalRoutingSnapshot routing,
      @Schema(nullable = true) UUID upstreamInstanceId,
      @Schema(nullable = true) String upstreamInstanceNo,
      @Schema(nullable = true) String upstreamNodeKey,
      @Schema(nullable = true) String upstreamRoutedBy,
      OffsetDateTime decidedAt,
      List<AutoApprovalEscalationRow> escalations) {}

  /** 决策台账的一行（列表用，比详情少几个大字段）。 */
  public record AutoApprovalDecisionRow(
      UUID id,
      String decisionNo,
      String documentTypeCode,
      String documentRef,
      String sourceSystem,
      @Schema(nullable = true) BigDecimal amount,
      @Schema(nullable = true) String currencyCode,
      String decision,
      String reasonCode,
      String executionState,
      @Schema(nullable = true) UUID upstreamInstanceId,
      OffsetDateTime decidedAt) {}

  // ================================================================ 超时升级

  /** 超时升级台账的一行。 */
  public record AutoApprovalEscalationRow(
      UUID id,
      UUID decisionId,
      UUID upstreamInstanceId,
      UUID upstreamFlowId,
      String nodeKey,
      int timeoutHours,
      String timeoutAction,
      @Schema(nullable = true) String escalateApproverType,
      @Schema(nullable = true) UUID escalateRoleId,
      @Schema(nullable = true) UUID escalatePositionId,
      @Schema(nullable = true) UUID escalateMembershipId,
      BigDecimal elapsedHours,
      long upstreamDwellSeconds,
      String status,
      String executionDetail,
      OffsetDateTime detectedAt,
      @Schema(nullable = true) OffsetDateTime executedAt,
      @Schema(nullable = true) UUID executedBy) {}

  /** 一次扫描的结论：扫了多少、发现了多少条超时。 */
  public record AutoApprovalEscalationScanView(
      int scanned, int detected, List<AutoApprovalEscalationRow> escalations, String note) {}

  // ================================================================ 能力声明

  /**
   * 能力声明。
   *
   * <p>🔴 {@code upstreamProgrammaticDecisionChannel} 恒为 {@code false}，而且**必须**如实写出来：
   * 上游 17.4 的 {@code decide} / {@code transfer} 都绑定**当前用户自己的会话**（actor 恒为调用者、
   * 取单条件是「这条待办归我」），**没有**可供服务间调用的程序化决策通道。
   * 本模块因此是「判定 + 记录 + 经授权端口转交」，**不在平台侧另起一套审批状态机**。
   */
  public record AutoApprovalCapabilities(
      String module,
      String upstream,
      boolean upstreamProgrammaticDecisionChannel,
      String upstreamDecisionChannelNote,
      String upstreamIntakeChannel,
      String decisionOwnership,
      List<String> decisions,
      List<String> checks,
      List<String> permissions) {}

  /** 概览：几个数。 */
  public record AutoApprovalOverview(
      long policyCount,
      long activePolicyCount,
      long signalCount,
      long decisionCount,
      long autoApprovedCount,
      long autoRejectedCount,
      long manualRequiredCount,
      long pendingEscalationCount,
      long executedEscalationCount) {}

  // ================================================================ 上游（identity 17.4）只读视图

  /** 上游审批流详情里的**节点**。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ApprovalNodeView(
      UUID id,
      String nodeKey,
      String nodeName,
      int nodeOrder,
      int stageIndex,
      String approverType,
      @Schema(nullable = true) UUID roleId,
      @Schema(nullable = true) String roleCode,
      @Schema(nullable = true) UUID positionId,
      @Schema(nullable = true) String positionName,
      @Schema(nullable = true) UUID membershipId,
      @Schema(nullable = true) String approverLabel,
      String approvalMode) {}

  /** 上游审批流详情里的**金额分支**（金额分层的唯一来源）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ApprovalBranchView(
      UUID id,
      String branchName,
      String currencyCode,
      @Schema(nullable = true) BigDecimal minAmount,
      @Schema(nullable = true) BigDecimal maxAmount,
      String targetNodeKey,
      int priority,
      String description) {}

  /** 上游审批流详情里的**超时升级规则**。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ApprovalEscalationView(
      UUID id,
      String nodeKey,
      String timeoutAction,
      int timeoutHours,
      @Schema(nullable = true) String escalateApproverType,
      @Schema(nullable = true) UUID escalateRoleId,
      @Schema(nullable = true) UUID escalatePositionId,
      @Schema(nullable = true) UUID escalateMembershipId,
      @Schema(nullable = true) String escalateLabel,
      String description) {}

  /** 上游审批流详情。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ApprovalFlowDetailView(
      ApprovalFlowView flow, List<ApprovalNodeView> nodes, List<ApprovalBranchView> branches,
      List<ApprovalEscalationView> escalations) {}

  /** 上游审批流概览。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ApprovalFlowView(
      UUID id,
      String documentTypeCode,
      String documentTypeName,
      String flowName,
      String status,
      String description,
      long version,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt,
      int nodeCount,
      int branchCount,
      int escalationCount) {}

  /** 上游建单（intake）的结果。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ApprovalSubmitResult(
      UUID id,
      String instanceNo,
      UUID flowId,
      String currentNodeKey,
      String currentNodeName,
      @Schema(nullable = true) UUID applicantId,
      String routedBy,
      OffsetDateTime submittedAt) {}

  /** 上游审批记录视图（超时判定用的停留秒数来自它）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ApprovalRecordView(
      UUID id,
      String instanceNo,
      String documentTypeCode,
      @Schema(nullable = true) BigDecimal amount,
      @Schema(nullable = true) String currencyCode,
      String currentNodeKey,
      @Schema(nullable = true) String currentNodeName,
      String status,
      String sourceSystem,
      String sourceReferenceId,
      OffsetDateTime submittedAt,
      @Schema(nullable = true) OffsetDateTime finishedAt,
      long dwellSeconds) {}

  /** 上游审批记录详情。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ApprovalRecordDetail(ApprovalRecordView instance) {}

  /** 上游成员视图（升级目标解析成具体成员时用）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record MemberView(
      UUID id,
      UUID userId,
      String username,
      String status,
      @Schema(nullable = true) UUID departmentId,
      List<MemberAssignmentView> assignments,
      boolean owner) {}

  /** 成员的角色分配。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record MemberAssignmentView(UUID roleId, String scopeMode) {}
}
