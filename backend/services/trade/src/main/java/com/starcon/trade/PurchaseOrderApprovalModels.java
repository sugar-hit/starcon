package com.starcon.trade;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 6.3.1.3 采购单审批的传输模型。
 *
 * <p>蓝图 06 卷 6.3.1.3 原文：「按金额阈值与采购员分级审批，支持批量审批、驳回并附意见，
 * 审批通过后锁定单据生成应付。」输入「待审批采购单；17 组织的审批规则（金额阈值、层级）」，
 * 输出「审批结果 + 审批链路记录；通过后触发 12 生成应付、8 允许收货」。
 *
 * <p><b>本文件最重要的纪律：金额与「有没有」都不许用假值糊过去。</b>
 *
 * <ul>
 *   <li><b>登记额与生效额分两列</b>：应付登记额（{@code registeredAmount}）是审批通过时
 *       那张采购单的金额；生效额（{@code effectiveAmount}）在财务确认之前**恒为 0** ——
 *       12 财务服务在本仓不存在，本模块只登记事实，不假装钱已经付出去。</li>
 *   <li><b>不跨币种相加</b>：汇总里的金额**按币种分列**（{@code pendingAmounts}），
 *       绝不把 USD 与 CNY 加成一个数，也绝不按 1:1 折算。</li>
 *   <li><b>「没有规则」不是「不用审」</b>：一条规则都没命中时 {@code ruleMatch='DEFAULT'}，
 *       仍走最低层级、1 人审批 —— 审批这一环不能被悄悄跳过。</li>
 *   <li><b>审批权限与数据范围</b>：{@code canApprove} / {@code canReject} 由服务端判定，
 *       并给出<b>不能批的原因</b>（{@code blockedReason}）—— 界面据此说明「为什么这一格是灰的」。</li>
 * </ul>
 *
 * <p><b>命名。</b>全仓契约按服务分文件导出，但同名类型仍会在桶文件里撞车，
 * 所以本文件的每一个类型都带 {@code PurchaseOrderApproval} 前缀
 * （已逐个 grep 比对 {@code packages/api-contracts/}，零重叠）。
 */
public final class PurchaseOrderApprovalModels {

  private PurchaseOrderApprovalModels() {}

  // ================================================================ 枚举

  /**
   * 审批层级。
   *
   * <p>L1 采购主管 / L2 采购经理 / L3 采购总监。层级只决定**权限高低**，
   * 不决定金额 —— 金额授权区间在 {@code purchase_order_approval_approver} 上单独配。
   */
  public enum PurchaseOrderApprovalTier {
    L1("采购主管"),
    L2("采购经理"),
    L3("采购总监");

    private final String label;

    PurchaseOrderApprovalTier(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 层级高低（用于「层级够不够」的判定）。 */
    public int rank() {
      return ordinal() + 1;
    }
  }

  /**
   * 采购员分级。
   *
   * <p>蓝图那句「按金额阈值与**采购员分级**审批」里的「分级」就是它：
   * 分级不同的采购员，单子命中的是**不同的金额档**。
   */
  public enum PurchaseOrderApprovalBuyerLevel {
    JUNIOR("初级采购员"),
    SENIOR("高级采购员"),
    MANAGER("采购经理");

    private final String label;

    PurchaseOrderApprovalBuyerLevel(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 审批单状态。 */
  public enum PurchaseOrderApprovalCaseStatus {
    PENDING("待审批"),
    APPROVED("已通过"),
    REJECTED("已驳回");

    private final String label;

    PurchaseOrderApprovalCaseStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  // ================================================================ 能力声明

  /** 一条能力（或一条「刻意不做的事」）。 */
  public record PurchaseOrderApprovalCapability(
      String code, String label, boolean available, String ownerModule, String reason) {}

  /**
   * 能力声明。
   *
   * @param capabilities 逐条列出本模块的能力与边界
   */
  public record PurchaseOrderApprovalCapabilities(
      List<PurchaseOrderApprovalCapability> capabilities, String note) {}

  // ================================================================ 规则与成员

  /**
   * 一条审批规则。
   *
   * @param buyerLevel 适用采购员分级（'ANY' = 通配）
   * @param currency 币种（'ANY' = 通配；金额阈值与币种绑定，绝不跨币种比大小）
   * @param minAmount 金额阈值下限（**含**）
   * @param maxAmount 金额阈值上限（**不含**；null = 无上限）
   */
  public record PurchaseOrderApprovalRuleView(
      UUID id,
      String ruleNo,
      String ruleName,
      String buyerLevel,
      String currency,
      BigDecimal minAmount,
      @Schema(nullable = true) BigDecimal maxAmount,
      String requiredTier,
      int requiredApprovals,
      int priority,
      String status,
      String note,
      long version,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /**
   * 规则入参。
   *
   * @param ruleNo 规则号（租户内唯一，建后不可改）
   * @param buyerLevel 'ANY' / JUNIOR / SENIOR / MANAGER
   * @param currency 'ANY' 或三位大写币种
   * @param version 乐观锁版本（改规则时必填；新建时忽略）
   */
  public record PurchaseOrderApprovalRuleInput(
      String ruleNo,
      String ruleName,
      String buyerLevel,
      String currency,
      @Schema(nullable = true) BigDecimal minAmount,
      @Schema(nullable = true) BigDecimal maxAmount,
      String requiredTier,
      @Schema(nullable = true) Integer requiredApprovals,
      @Schema(nullable = true) Integer priority,
      String note,
      @Schema(nullable = true) Long version) {}

  /**
   * 启用 / 停用（规则与审批人配置）。
   *
   * @param version 乐观锁版本（必填：并发改同一条时后到的那个要拿到 409 而不是覆盖）
   */
  public record PurchaseOrderApprovalStatusRequest(Long version) {}

  /** 采购员分级（谁是哪一档的采购员）。 */
  public record PurchaseOrderApprovalBuyerView(
      UUID id,
      UUID userId,
      String userName,
      String buyerLevel,
      String status,
      String note,
      long version,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 采购员分级入参。 */
  public record PurchaseOrderApprovalBuyerInput(
      UUID userId, String userName, String buyerLevel, String status, String note) {}

  /**
   * 审批人 / 层级配置。
   *
   * @param currency 金额授权区间的币种（'ANY' = 通配）
   * @param minAmount 授权下限（**含**）
   * @param maxAmount 授权上限（**不含**；null = 无上限）
   */
  public record PurchaseOrderApprovalApproverView(
      UUID id,
      UUID userId,
      String userName,
      String approvalTier,
      String currency,
      BigDecimal minAmount,
      @Schema(nullable = true) BigDecimal maxAmount,
      String status,
      String note,
      long version,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 审批人入参。 */
  public record PurchaseOrderApprovalApproverInput(
      UUID userId,
      String userName,
      String approvalTier,
      String currency,
      @Schema(nullable = true) BigDecimal minAmount,
      @Schema(nullable = true) BigDecimal maxAmount,
      String status,
      String note) {}

  /**
   * 审批成员（采购员分级 + 审批人）一次返回。
   *
   * @param buyers 采购员分级
   * @param approvers 审批人与层级配置
   */
  public record PurchaseOrderApprovalMembers(
      List<PurchaseOrderApprovalBuyerView> buyers,
      List<PurchaseOrderApprovalApproverView> approvers,
      String note) {}

  /** 规则列表。 */
  public record PurchaseOrderApprovalRuleList(
      List<PurchaseOrderApprovalRuleView> items, long total, String note) {}

  // ================================================================ 台账

  /**
   * 审批台账的一行。
   *
   * @param orderAmount 待审金额（**单币种**，与 currency 一致）
   * @param requiredTier 这张单要求的审批层级
   * @param approvedCount 已批准人数 / requiredApprovals 需要人数
   * @param canApprove 当前登录人此刻能不能批（服务端判定，不是前端猜的）
   * @param blockedReason 不能批的原因（界面据此说明「为什么这一格是灰的」）
   */
  public record PurchaseOrderApprovalOrderRow(
      UUID orderId,
      String orderNo,
      String orderStatus,
      String supplierCode,
      String supplierName,
      String currency,
      BigDecimal orderAmount,
      UUID buyerUserId,
      String buyerName,
      String buyerLevel,
      String requiredTier,
      int requiredApprovals,
      int approvedCount,
      String caseStatus,
      String ruleName,
      boolean canApprove,
      String blockedReason) {}

  /** 一个币种的合计（**绝不跨币种相加**）。 */
  public record PurchaseOrderApprovalAmountByCurrency(
      String currency, BigDecimal amount, long count) {}

  /**
   * 审批台账汇总。
   *
   * @param pendingAmounts 待审金额**按币种分列**
   */
  public record PurchaseOrderApprovalSummary(
      long pendingCount,
      long approvedCount,
      long rejectedCount,
      List<PurchaseOrderApprovalAmountByCurrency> pendingAmounts) {}

  /**
   * 审批台账分页。
   *
   * @param total 总条数（真分页：与筛选条件一致，不是本页条数）
   */
  public record PurchaseOrderApprovalPage(
      List<PurchaseOrderApprovalOrderRow> items,
      long total,
      int page,
      int size,
      PurchaseOrderApprovalSummary summary) {}

  // ================================================================ 详情

  /**
   * 审批链路的一步（追加式留痕）。
   *
   * @param orderAmount **审批当时**的金额（不是现在的数）
   * @param ruleName 依据的规则（事后要答得出「按哪条规则批的」）
   */
  public record PurchaseOrderApprovalChainRow(
      int seq,
      String action,
      String approvalTier,
      UUID approverUserId,
      String approverName,
      BigDecimal orderAmount,
      String currency,
      String ruleName,
      String buyerLevel,
      String note,
      OffsetDateTime occurredAt) {}

  /** 采购单自身的留痕（6.3.1.1 的 purchase_order_event 读出来的那部分）。 */
  public record PurchaseOrderApprovalEventRow(
      String eventType,
      @Schema(nullable = true) String fromStatus,
      @Schema(nullable = true) String toStatus,
      String note,
      UUID actorId,
      OffsetDateTime occurredAt) {}

  /**
   * 应付登记。
   *
   * <p>🔴 <b>永远不为 null</b>：「还没登记应付」用 {@code status='NONE'} 表达，
   * <b>不是</b>一个空对象 —— 空对象会被界面读成「金额 0」，
   * 而 0 会被读成「这笔应付是 0 元」。本仓契约里对象字段一律不带 null
   * （见 {@code packages/api-contracts} 的既有形状），「有没有」要用状态说。
   *
   * @param id 应付登记 id（status=NONE 时为 null）
   * @param registeredAmount 登记金额（审批通过时的采购单金额；status=NONE 时为 null）
   * @param effectiveAmount <b>生效</b>金额：财务确认之前恒为 0（status=NONE 时为 null）
   * @param status NONE（还没登记）/ PENDING_FINANCE_CONFIRM / CONFIRMED / REJECTED
   */
  public record PurchaseOrderApprovalPayableView(
      @Schema(nullable = true) UUID id,
      String payableNo,
      String currency,
      @Schema(nullable = true) BigDecimal registeredAmount,
      @Schema(nullable = true) BigDecimal effectiveAmount,
      String status,
      String financeReference,
      @Schema(nullable = true) UUID confirmedBy,
      @Schema(nullable = true) OffsetDateTime confirmedAt,
      @Schema(nullable = true) OffsetDateTime createdAt) {}

  /**
   * 收货放行事实。
   *
   * <p>🔴 同样**永远不为 null**：还没放行用 {@code status='NONE'} 表达。
   *
   * @param status NONE（还没放行）/ RELEASED / REVOKED
   */
  public record PurchaseOrderApprovalReceiptReleaseView(
      @Schema(nullable = true) UUID id,
      UUID orderId,
      String status,
      @Schema(nullable = true) UUID releasedBy,
      @Schema(nullable = true) OffsetDateTime releasedAt,
      String releaseNote) {}

  /**
   * 审批详情。
   *
   * @param ruleMatch RULE（命中某条规则）/ DEFAULT（一条都没命中，走默认档）
   * @param chain 审批链路（按发生顺序）
   * @param payable 应付登记（**永远存在**：status=NONE 表示还没登记）
   * @param receiptRelease 收货放行事实（**永远存在**：status=NONE 表示还没放行）
   */
  public record PurchaseOrderApprovalDetail(
      UUID orderId,
      String orderNo,
      String orderStatus,
      String supplierCode,
      String supplierName,
      String currency,
      BigDecimal orderAmount,
      UUID buyerUserId,
      String buyerName,
      String buyerLevel,
      String caseStatus,
      String ruleName,
      String ruleMatch,
      String requiredTier,
      int requiredApprovals,
      int approvedCount,
      List<PurchaseOrderApprovalChainRow> chain,
      List<PurchaseOrderApprovalEventRow> events,
      PurchaseOrderApprovalPayableView payable,
      PurchaseOrderApprovalReceiptReleaseView receiptRelease,
      boolean canApprove,
      boolean canReject,
      String blockedReason) {}

  // ================================================================ 入参

  /**
   * 审批 / 驳回。
   *
   * @param note 审批意见；**驳回时必填**（「驳回而不说为什么」等于没驳回）
   */
  public record PurchaseOrderApprovalDecisionRequest(@Schema(nullable = true) String note) {}

  /**
   * 批量审批 / 批量驳回。
   *
   * @param action APPROVE / REJECT
   * @param note 意见；批量驳回时必填
   */
  public record PurchaseOrderApprovalBatchRequest(
      List<UUID> orderIds, String action, @Schema(nullable = true) String note) {}

  /** 批量里的一条结果（**逐条给结论**，不静默丢弃）。 */
  public record PurchaseOrderApprovalBatchItem(
      UUID orderId,
      String orderNo,
      boolean succeeded,
      String message) {}

  /**
   * 批量结果。
   *
   * @param succeededCount 成功条数
   * @param failedCount 失败条数（失败原因逐条在 items 里）
   */
  public record PurchaseOrderApprovalBatchResult(
      List<PurchaseOrderApprovalBatchItem> items, int succeededCount, int failedCount, String note) {}

  /** 财务确认（把登记的应付变成生效的应付）。 */
  public record PurchaseOrderApprovalPayableConfirmRequest(String financeReference) {}
  /** 财务驳回（登记的应付作废）。 */
  public record PurchaseOrderApprovalPayableRejectRequest(String reason) {}

  /** 应付台账分页。 */
  public record PurchaseOrderApprovalPayablePage(
      List<PurchaseOrderApprovalPayableView> items, long total, int page, int size, String note) {}
}
