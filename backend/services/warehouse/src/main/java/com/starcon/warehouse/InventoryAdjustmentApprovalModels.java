package com.starcon.warehouse;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 7.4.1.2 调整审批的传输模型。
 *
 * <p>蓝图原话：「**按金额/数量阈值审批调整单，审批通过后才执行库存分录；支持批量审批与驳回。**」
 * 输入：「待审调整单；17 审批规则。」输出：「审批结果；执行后的库存分录；审计记录。」
 * 以及：「**库存调整是舞弊高发区**（无审批的调整 = 任意改账），阈值审批是必要控制。」
 *
 * <p><b>与 7.4.1.1 的分工</b>（上游会话日志已写死，本模块照抄，不另起一套）：
 *
 * <ul>
 *   <li><b>阈值审批规则</b>（金额/数量阈值、批量审批、驳回工作流）⇒ <b>本模块</b>；
 *   <li><b>执行「批准」这一动作</b> ⇒ <b>7.4.1.1</b>（显式端点 + 必填理由 + 提交人≠审批人 + DB 状态机）。
 *       本模块**不重复实现**批准动作，只在它前面接一道阈值判定。
 * </ul>
 *
 * <p><b>金额为什么是「申报值」</b>：warehouse 库里没有任何成本口径（{@code inventory_balance} /
 * {@code stock_bucket} 都没有金额列），12 财务服务也不存在 ⇒ 调整单没有原生金额。
 * 本模块的金额是提交人**申报**、**提交那一刻冻结**、进审批链路的登记额；
 * 真正的库存估值口径随 12 财务落地后经授权 API / event 衔接。
 */
public final class InventoryAdjustmentApprovalModels {

  private InventoryAdjustmentApprovalModels() {}

  // ------------------------------------------------------------ 能力

  /**
   * 一条能力声明。
   *
   * <p>仓库既有约定（7.4.1.1 / 7.4.2.1 / 6.4.2.2 一致）：未接入的东西用
   * {@code available=false} + {@code ownerModule} + {@code note} 表达，<b>不建占位表、
   * 不返回空集冒充成功</b>。
   */
  public record InventoryAdjustmentApprovalCapability(
      String key, String label, boolean available, String ownerModule, String href, String note) {}

  /**
   * 阈值闸门是否启用。
   *
   * @param thresholdEnabled 本租户是否存在生效中的阈值规则（= 阈值闸门是否启用）
   * @param activeRuleCount 生效中的规则条数
   * @param liveCaseCount 还没批完的审批单条数
   */
  public record InventoryAdjustmentApprovalPolicy(
      boolean thresholdEnabled, long activeRuleCount, long liveCaseCount, String note) {}

  /** 调整审批能力说明（前端据此渲染提示，而不是把规则硬编码在界面上）。 */
  public record InventoryAdjustmentApprovalCapabilities(
      List<InventoryAdjustmentApprovalCapability> capabilities,
      InventoryAdjustmentApprovalPolicy policy,
      String note) {}

  // ------------------------------------------------------------ 阈值规则

  /** 一条阈值规则。 */
  public record InventoryAdjustmentApprovalRule(
      UUID id,
      String ruleNo,
      String ruleName,
      String reasonCode,
      String currency,
      BigDecimal minAmount,
      @Schema(nullable = true) BigDecimal maxAmount,
      BigDecimal minQty,
      @Schema(nullable = true) BigDecimal maxQty,
      String requiredTier,
      int requiredApprovals,
      int priority,
      String status,
      String note,
      long version,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /**
   * 阈值规则入参。
   *
   * @param reasonCode 适用调整单类型（受控原因码；{@code ANY} = 通配）
   * @param currency 币种（{@code ANY} = 通配；金额区间按单据自身币种比较）
   * @param minAmount 金额区间下限（含）
   * @param maxAmount 金额区间上限（不含）；{@code null} = 无上限
   * @param minQty 数量区间下限（含）；量纲 = 绝对调整量 Σ|qty_delta|
   * @param maxQty 数量区间上限（不含）；{@code null} = 无上限
   */
  public record InventoryAdjustmentApprovalRuleInput(
      @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 64)
          String ruleNo,
      @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 200)
          String ruleName,
      @Schema(nullable = true) String reasonCode,
      @jakarta.validation.constraints.NotBlank String currency,
      @Schema(nullable = true) BigDecimal minAmount,
      @Schema(nullable = true) BigDecimal maxAmount,
      @Schema(nullable = true) BigDecimal minQty,
      @Schema(nullable = true) BigDecimal maxQty,
      @jakarta.validation.constraints.NotBlank String requiredTier,
      @jakarta.validation.constraints.NotNull Integer requiredApprovals,
      // 优先级与备注是**可选**的：不填走默认值（优先级 100、空备注）。
      // 不标 nullable 的话，契约会要求调用方每次都带上 —— 而「可选」才是真实语义。
      @Schema(nullable = true) Integer priority,
      @Schema(nullable = true) String note) {}

  /** 启用 / 停用（规则与审批人共用）。 */
  public record InventoryAdjustmentApprovalStatusInput(
      @jakarta.validation.constraints.NotBlank
          @jakarta.validation.constraints.Pattern(regexp = "ACTIVE|INACTIVE")
          String status) {}

  // ------------------------------------------------------------ 审批人授权

  /** 审批人授权：层级 + 金额授权区间 + 数量授权上限。 */
  public record InventoryAdjustmentApprovalApprover(
      UUID id,
      UUID userId,
      String userName,
      String approvalTier,
      String currency,
      BigDecimal minAmount,
      @Schema(nullable = true) BigDecimal maxAmount,
      @Schema(nullable = true) BigDecimal maxQty,
      String status,
      String note,
      OffsetDateTime createdAt) {}

  /** 审批人授权入参。 */
  public record InventoryAdjustmentApprovalApproverInput(
      @jakarta.validation.constraints.NotNull UUID userId,
      @Schema(nullable = true) String userName,
      @jakarta.validation.constraints.NotBlank String approvalTier,
      @jakarta.validation.constraints.NotBlank String currency,
      @Schema(nullable = true) BigDecimal minAmount,
      @Schema(nullable = true) BigDecimal maxAmount,
      @Schema(nullable = true) BigDecimal maxQty,
      @Schema(nullable = true) String note) {}

  // ------------------------------------------------------------ 判定与提交

  /**
   * 阈值判定入参（只读试算）。
   *
   * @param orderId 待判定的调整单
   * @param amount 试算用的金额（不填则取该单已登记的登记额）
   * @param currency 试算用的币种（不填则取该单已登记的币种）
   */
  public record InventoryAdjustmentApprovalEvaluateInput(
      @jakarta.validation.constraints.NotNull UUID orderId,
      @Schema(nullable = true) BigDecimal amount,
      @Schema(nullable = true) String currency) {}

  /**
   * 阈值判定结果。
   *
   * @param ruleMatch {@code RULE} = 命中某条规则；{@code DEFAULT} = 一条都没命中，走最低档（L1、1 人）
   * @param approvable 当前登录人此刻能不能批这张单（授权 + 职责分离 + 人数）
   * @param approvableReason 不能批时写清原因（前端直接显示，不猜）
   */
  public record InventoryAdjustmentApprovalEvaluation(
      UUID orderId,
      String orderCode,
      String orderStatus,
      String reasonCode,
      BigDecimal orderQty,
      BigDecimal netQty,
      BigDecimal amount,
      String currency,
      String ruleMatch,
      @Schema(nullable = true) UUID ruleId,
      String ruleName,
      String requiredTier,
      int requiredApprovals,
      long approvedCount,
      @Schema(nullable = true) String caseStatus,
      boolean approvable,
      String approvableReason) {}

  /**
   * 提交待审（7.4.1.2 的提交入口）。
   *
   * @param amount **申报的调整估值**（登记额）：提交那一刻冻结，之后不可改
   * @param currency 币种（三个大写字母；不建外键）
   * @param note 提交说明
   */
  public record InventoryAdjustmentApprovalSubmitInput(
      @jakarta.validation.constraints.NotNull BigDecimal amount,
      @jakarta.validation.constraints.NotBlank String currency,
      @Schema(nullable = true) String note) {}

  /** 审批 / 驳回的意见。 */
  public record InventoryAdjustmentApprovalNoteInput(@Schema(nullable = true) String note) {}

  // ------------------------------------------------------------ 审批单

  /** 一条审批单（一张调整单的一次提交 = 一条链）。 */
  public record InventoryAdjustmentApprovalCase(
      UUID id,
      UUID orderId,
      String orderCode,
      String caseNo,
      int attemptNo,
      @Schema(nullable = true) UUID ruleId,
      String ruleName,
      String ruleMatch,
      String reasonCode,
      BigDecimal orderAmount,
      String currency,
      BigDecimal orderQty,
      String requiredTier,
      int requiredApprovals,
      int approvedCount,
      String status,
      UUID submittedBy,
      OffsetDateTime submittedAt,
      @Schema(nullable = true) UUID rejectedBy,
      @Schema(nullable = true) OffsetDateTime rejectedAt,
      String rejectReason) {}

  /** 审批链路记录上的一条（追加式）。 */
  public record InventoryAdjustmentApprovalRecord(
      UUID id,
      UUID caseId,
      UUID orderId,
      String action,
      String actionLabel,
      UUID actorUserId,
      OffsetDateTime occurredAt,
      String approvalTier,
      BigDecimal orderAmount,
      String currency,
      BigDecimal orderQty,
      String detail) {}

  /** 审批单详情 = 审批单 + 全部链路记录。 */
  public record InventoryAdjustmentApprovalCaseDetail(
      InventoryAdjustmentApprovalCase approvalCase, List<InventoryAdjustmentApprovalRecord> records) {}

  // ------------------------------------------------------------ 待我审批

  /** 待我审批列表上的一条。 */
  public record InventoryAdjustmentApprovalPendingItem(
      InventoryAdjustmentApprovalCase approvalCase,
      String orderCode,
      String orderStatus,
      String reasonCode,
      String reasonLabel,
      long lineCount,
      BigDecimal netQty,
      BigDecimal orderQty,
      OffsetDateTime submittedAt,
      boolean approvable,
      String approvableReason) {}

  /** 待我审批（真分页）。 */
  public record InventoryAdjustmentApprovalPendingPage(
      List<InventoryAdjustmentApprovalPendingItem> items, long total, int page, int size) {}

  // ------------------------------------------------------------ 批量

  /** 批量审批 / 批量驳回入参。 */
  public record InventoryAdjustmentApprovalBatchInput(
      @jakarta.validation.constraints.NotEmpty List<UUID> orderIds,
      @Schema(nullable = true) String note) {}

  /** 批量里的一条结论（成功与否都**逐条返回**，不静默丢弃）。 */
  public record InventoryAdjustmentApprovalBatchItem(
      UUID orderId, String orderCode, boolean success, String message) {}

  /** 批量结果。 */
  public record InventoryAdjustmentApprovalBatchResult(
      List<InventoryAdjustmentApprovalBatchItem> items, int successCount, int failureCount) {}

  // ------------------------------------------------------------ 会计影响

  /**
   * 调整的会计影响（warehouse 侧自有事实）。
   *
   * @param registeredAmount 登记额（提交时申报并冻结）
   * @param effectiveAmount 生效额（未过账恒为 0；过账后 = 登记额）
   */
  public record InventoryAdjustmentApprovalValuation(
      UUID id,
      UUID orderId,
      String orderCode,
      BigDecimal registeredAmount,
      String currency,
      BigDecimal effectiveAmount,
      String status,
      UUID registeredBy,
      OffsetDateTime registeredAt,
      @Schema(nullable = true) OffsetDateTime effectiveAt) {}

  /** 会计影响台账（真分页）。 */
  public record InventoryAdjustmentApprovalValuationPage(
      List<InventoryAdjustmentApprovalValuation> items, long total, int page, int size) {}
}
