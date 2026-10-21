package com.starcon.trade;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 11.2.3.3 供应商索赔的传输模型。
 *
 * <p>蓝图 11-aftersales-and-returns.md 原文：
 *
 * <blockquote>
 * 职责：对质量原因退货向供应商发起索赔：关联批次与责任判定、索赔金额、协商记录、赔付与冲减应付处理。
 * 输入：质检结论与责任判定；索赔金额；协商记录。
 * 输出：索赔单 + 冲减应付或退款记录。
 * 关系：上游 = 11.1.2.2、11.2.3.1；下游 = 12 财务（冲减应付）、6.5.1 对账。
 * <b>索赔不是自动到账</b>，系统只跟踪流程与金额，冲减应付需财务确认。
 * </blockquote>
 *
 * <p><b>本文件最重要的三件事</b>：
 *
 * <ol>
 *   <li><b>「没有财务确认就没有生效」落成两个字段而不是一个状态</b>。
 *       {@link SupplierClaimSettlementRow#requestedAmount}（登记的赔付金额）与
 *       {@link SupplierClaimSettlementRow#effectiveAmount}（**已生效**的冲减金额）在财务确认之前
 *       <b>必然不相等</b>（effective 恒为 0）。蓝图加粗句「索赔不是自动到账」如果只落成一个
 *       {@code settlementStatus}，那么「PENDING」这个状态和「已生效但在等回执」就没有区别了。</li>
 *   <li><b>索赔基数不由调用方给</b>。{@link SupplierClaimItemCreate} 里<b>没有</b>基数字段，
 *       也没有「原值」字段 —— 基数由 DB 从质检结论取（V188 守卫②），
 *       {@link SupplierClaimItemRow#claimableBaseAmount} 与
 *       {@link SupplierClaimItemRow#maxClaimAmount} 是<b>只读</b>的推导列。</li>
 *   <li><b>达成金额只有一个出处</b>。{@link SupplierClaimNegotiationRow#agreedAmount} 只在
 *       {@code negotiationType='AGREEMENT'} 的协商记录上有值；索赔单头的
 *       {@link SupplierClaimRow#agreedAmount} 由 DB 校验「必须等于那条记录里的金额」
 *       （V188 守卫③），所以「协商成多少」在库里永远只有一个答案。</li>
 * </ol>
 *
 * <p><b>命名。</b>统一用 {@code SupplierClaim*} 前缀，与 {@code ReturnQuality*} /
 * {@code ProductIssue*} 零重叠 —— 契约被 codegen 合并进一个扁平文件、同名后者覆盖前者且不报冲突。
 */
public final class SupplierClaimModels {

  private SupplierClaimModels() {}

  // ================================================================ 枚举

  /**
   * 索赔单状态机。
   *
   * <p>⚠️ <b>三个终态不可逆</b>：{@link #SETTLED} / {@link #REJECTED} / {@link #WITHDRAWN}
   * 一旦落定，整行冻结（V188 守卫⑥）。索赔单要拿去和供应商对质，
   * 「我们报了多少、协商成多少、财务确认冲减了多少」必须只有一个答案。
   */
  public enum SupplierClaimStatus {
    DRAFT("草稿"),
    SUBMITTED("已提交"),
    NEGOTIATING("协商中"),
    AGREED("已达成"),
    SETTLED("已结算"),
    REJECTED("已驳回"),
    WITHDRAWN("已撤回");

    private final String label;

    SupplierClaimStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 终态：不可再流转、不可再改。 */
    public boolean terminal() {
      return this == SETTLED || this == REJECTED || this == WITHDRAWN;
    }
  }

  /**
   * 索赔原因（职责句「对<b>质量原因</b>退货向供应商发起索赔」）。
   *
   * <p>⚠️ 这五个取值是**质量原因**的细分，不是任意理由：发错货、少发货、破损、品质不合格
   * 才构成对供应商的索赔。物流破损是我们与承运商之间的事，不在这里。
   */
  public enum SupplierClaimReason {
    QUALITY_DEFECT("品质不合格"),
    WRONG_ITEM("错发商品"),
    SHORTAGE("短少数量"),
    DAMAGED("到货破损"),
    OTHER("其它质量原因");

    private final String label;

    SupplierClaimReason(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 责任判定环节 —— 只有落在**可向供应商索赔**的集合里才能发起索赔（V188 守卫②）。
   *
   * <p>⚠️ 这里刻意**不是** 11.1.2.2 的六值枚举的全集：
   * 生产与描述不符是供应商的产品责任；运输与仓储的责任在承运商与我们自己身上；
   * 买家因素与责任待定更不能算到供应商头上 ——
   * 拿「责任待定」去和供应商对质，赢了也是赢了个寂寞。
   */
  public enum SupplierClaimableStage {
    PRODUCTION("生产"),
    DESCRIPTION("描述不符");

    private final String label;

    SupplierClaimableStage(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 该环节是否可向供应商索赔。 */
    public static boolean claimable(String stage) {
      return "PRODUCTION".equals(stage) || "DESCRIPTION".equals(stage);
    }

    /** 不可索赔环节的说明（写进报错与能力声明，让界面能自己讲清楚为什么不能索赔）。 */
    public static String blockedReason(String stage) {
      return switch (stage == null ? "" : stage) {
        case "TRANSPORT" -> "运输环节的责任在承运商，不在供应商的产品上，不能向供应商索赔";
        case "WAREHOUSE" -> "仓储环节的责任在我们自己，不在供应商的产品上，不能向供应商索赔";
        case "BUYER_FACTOR" -> "买家因素造成的退货不能向供应商索赔";
        case "UNDETERMINED" -> "责任待定不能向供应商索赔：先把责任判定落到具体环节（生产 / 描述不符）";
        case "PRODUCTION", "DESCRIPTION" -> "";
        default -> "未知责任环节（" + stage + "），不能向供应商索赔";
      };
    }
  }

  /** 协商形态：内部复核 → 供应商答复 → 对方还价 → 达成一致 → 协商破裂。 */
  public enum SupplierNegotiationType {
    CLAIM_REVIEW("内部复核"),
    SUPPLIER_RESPONSE("供应商答复"),
    COUNTER_OFFER("对方还价"),
    AGREEMENT("达成一致"),
    REJECTION("协商破裂");

    private final String label;

    SupplierNegotiationType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 只有「达成一致」带达成金额（V188 表级 CHECK）。 */
    public boolean carriesAgreementAmount() {
      return this == AGREEMENT;
    }
  }

  /** 协商记录是哪一方。 */
  public enum SupplierNegotiationRole {
    INTERNAL("我方"),
    SUPPLIER("供应商");

    private final String label;

    SupplierNegotiationRole(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 赔付方式（蓝图输出句「冲减应付<b>或</b>退款记录」）。
   *
   * <p>⚠️ 两者的财务确认要求**完全一样** —— 蓝图说的是「冲减应付或退款」，
   * 二者都是「供应商认了、钱要落地」这一步，都**不是自动到账**。
   */
  public enum SupplierSettlementMethod {
    OFFSET_PAYABLE("冲减应付"),
    SUPPLIER_REFUND("供应商退款");

    private final String label;

    SupplierSettlementMethod(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 赔付分录状态。
   *
   * <p>⚠️ <b>{@link #PENDING_FINANCE_CONFIRM} 不是「已生效但没回执」</b>：
   * 在这个状态下 {@code effectiveAmount} **恒为 0**（V188 表级 CHECK）。
   */
  public enum SupplierSettlementStatus {
    PENDING_FINANCE_CONFIRM("待财务确认"),
    CONFIRMED("财务已确认"),
    REJECTED("财务已驳回");

    private final String label;

    SupplierSettlementStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 已生效（冲减真的落到应付上了）。 */
    public boolean effective() {
      return this == CONFIRMED;
    }
  }

  /**
   * 索赔单头上的冲减状态（汇总口径，由 DB 从赔付分录重算）。
   *
   * <p>⚠️ {@link #APPLIED} 是唯一「冲减已生效」的状态；
   * {@link #PENDING_FINANCE_CONFIRM} 期间生效金额恒为 0。
   */
  public enum SupplierOffsetStatus {
    NOT_APPLIED("未登记赔付"),
    PENDING_FINANCE_CONFIRM("待财务确认"),
    APPLIED("冲减已生效"),
    FINANCE_REJECTED("财务已驳回");

    private final String label;

    SupplierOffsetStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  // ================================================================ 入参

  /**
   * 建索赔单（草稿）。
   *
   * <p>⚠️ <b>没有金额字段</b>：索赔金额是<b>明细</b>上的量，头上的
   * {@code claimedAmount} 由 DB 触发器从明细重算（V188 守卫⑤）。
   * 币种要在这里给，因为它决定「这笔钱是哪种钱」—— 而每一行的币种由质检结论的原值币种决定，
   * 两者不一致时 DB 拒收（守卫②）。
   */
  public record SupplierClaimCreate(
      UUID supplierRefId,
      String claimReasonCode,
      @Schema(nullable = true) String claimReasonNote,
      String claimCurrency) {}

  /**
   * 加一条索赔明细。
   *
   * <p>⚠️ <b>只接受「哪条质检结论 + 哪条责任判定 + 索赔多少件 + 报多少钱」</b>：
   * 基数、上限、币种、SKU / 批次快照全部由 DB 从上游取，传进来也传不进来。
   *
   * @param claimQty 可空；不给时按该质检结论的实检数量全额索赔
   */
  public record SupplierClaimItemCreate(
      UUID inspectionId,
      UUID attributionId,
      @Schema(nullable = true) BigDecimal claimQty,
      BigDecimal claimAmount) {}

  /**
   * 记一条协商记录。
   *
   * <p>⚠️ 序号由 DB 排（V188 守卫④），传输层没有序号字段 ——
   * 协商的先后顺序是证据链的一部分，让调用方自己填就等于让它变成可以改的东西。
   */
  public record SupplierNegotiationCreate(
      String negotiationType,
      String participantRole,
      @Schema(nullable = true) BigDecimal proposedAmount,
      @Schema(nullable = true) BigDecimal agreedAmount,
      @Schema(nullable = true) String negotiationNote,
      OffsetDateTime occurredAt) {}

  /**
   * 登记赔付 / 冲减应付。
   *
   * <p>⚠️ <b>没有生效金额字段</b>：登记时 {@code effectiveAmount} 恒为 0，
   * 只有财务确认那一刻才由 DB 把它改成登记金额。
   */
  public record SupplierSettlementCreate(
      String settlementMethod, BigDecimal requestedAmount) {}

  /**
   * 财务确认冲减生效。
   *
   * <p>⚠️ <b>必须带财务单据号</b>：事后要答得出「冲的是哪一笔」，
   * 只写「已确认」的话，三个月后没人说得清这笔钱扣在了哪张应付单上。
   */
  public record SupplierSettlementConfirm(String financeReference) {}

  /** 财务驳回赔付。必须写理由。 */
  public record SupplierSettlementReject(String rejectReason) {}

  /** 提交索赔（草稿 → 已提交）。 */
  public record SupplierClaimSubmit(String submitNote, Long expectedVersion) {}

  /** 置为已达成（协商中 → 已达成）。达成金额由 DB 从「达成一致」的协商记录取。 */
  public record SupplierClaimAgree(Long expectedVersion) {}

  /** 驳回索赔。必须写理由。 */
  public record SupplierClaimReject(String rejectReason, Long expectedVersion) {}

  /** 撤回索赔。必须写理由。 */
  public record SupplierClaimWithdraw(String withdrawReason, Long expectedVersion) {}

  /** 列表查询。真分页 + 多维筛选 + 排序白名单。 */
  public record SupplierClaimListQuery(
      @Schema(nullable = true) Integer page,
      @Schema(nullable = true) Integer size,
      @Schema(nullable = true) String status,
      @Schema(nullable = true) UUID supplierRefId,
      @Schema(nullable = true) String liabilityStage,
      @Schema(nullable = true) String offsetStatus,
      @Schema(nullable = true) BigDecimal minAmount,
      @Schema(nullable = true) BigDecimal maxAmount,
      @Schema(nullable = true) OffsetDateTime from,
      @Schema(nullable = true) OffsetDateTime to,
      @Schema(nullable = true) String keyword,
      @Schema(nullable = true) String sort) {}

  // ================================================================ 出参

  /** 索赔单行（列表用）。 */
  public record SupplierClaimRow(
      UUID id,
      String claimNo,
      UUID supplierRefId,
      String supplierCode,
      String supplierName,
      String claimReasonCode,
      String claimReasonLabel,
      String claimCurrency,
      BigDecimal claimedAmount,
      BigDecimal claimableBaseAmount,
      BigDecimal agreedAmount,
      String claimStatus,
      String claimStatusLabel,
      String offsetStatus,
      String offsetStatusLabel,
      BigDecimal appliedOffsetAmount,
      long itemCount,
      OffsetDateTime createdAt,
      OffsetDateTime submittedAt,
      OffsetDateTime updatedAt,
      long version) {}

  /** 索赔单列表页。 */
  public record SupplierClaimPage(
      List<SupplierClaimRow> rows,
      int page,
      int size,
      long total,
      SupplierClaimSummary summary,
      OffsetDateTime evaluatedAt,
      String paginationNote,
      String financeNote,
      String liabilityNote,
      String sortNote) {}

  /** 索赔单页汇总（跨全表算，不随分页变小）。 */
  public record SupplierClaimSummary(
      long total,
      long draft,
      long submitted,
      long negotiating,
      long agreed,
      long settled,
      long rejected,
      long withdrawn,
      BigDecimal totalClaimedAmount,
      BigDecimal totalAgreedAmount,
      BigDecimal totalAppliedOffsetAmount,
      long pendingFinanceConfirmCount) {}

  /**
   * 可索赔候选（已判定 + 已归因 + 环节可索赔 + 尚未被索赔过）。
   *
   * <p>⚠️ 这个集合是「发起索赔」的**唯一入口素材**：界面不该让操作员手填质检结论 id
   * 与责任判定 id —— 手填等于让「哪批货被报上去了」由人凭记忆拼出来。
   */
  public record SupplierClaimCandidateRow(
      UUID inspectionId,
      String inspectionNo,
      String verdict,
      String verdictLabel,
      String conclusionNote,
      UUID attributionId,
      String attributionNo,
      String attributedStage,
      String attributedStageLabel,
      UUID lotId,
      String lotCode,
      UUID lotSupplierRefId,
      UUID skuId,
      String skuCode,
      String skuName,
      String batchNo,
      BigDecimal inspectedQty,
      BigDecimal claimableBaseAmount,
      BigDecimal maxClaimAmount,
      String claimCurrency,
      OffsetDateTime concludedAt) {}

  /** 可索赔候选页。 */
  public record SupplierClaimCandidatePage(
      List<SupplierClaimCandidateRow> rows,
      long total,
      OffsetDateTime evaluatedAt,
      String note) {}

  /** 索赔明细行。 */
  public record SupplierClaimItemRow(
      UUID id,
      UUID inspectionId,
      UUID attributionId,
      String attributedStage,
      String attributedStageLabel,
      UUID lotId,
      String lotCode,
      UUID skuId,
      String skuCode,
      String skuName,
      BigDecimal claimQty,
      BigDecimal claimAmount,
      String claimCurrency,
      BigDecimal claimableBaseAmount,
      BigDecimal maxClaimAmount,
      OffsetDateTime createdAt) {}

  /** 协商记录行。 */
  public record SupplierClaimNegotiationRow(
      UUID id,
      int negotiationSeq,
      String negotiationType,
      String negotiationTypeLabel,
      String participantRole,
      String participantRoleLabel,
      BigDecimal proposedAmount,
      BigDecimal agreedAmount,
      String negotiationNote,
      OffsetDateTime occurredAt,
      OffsetDateTime recordedAt) {}

  /** 赔付 / 冲减应付分录行。 */
  public record SupplierClaimSettlementRow(
      UUID id,
      String settlementMethod,
      String settlementMethodLabel,
      String settlementCurrency,
      BigDecimal requestedAmount,
      BigDecimal effectiveAmount,
      String settlementStatus,
      String settlementStatusLabel,
      String financeReference,
      @Schema(nullable = true) UUID confirmedBy,
      @Schema(nullable = true) OffsetDateTime confirmedAt,
      @Schema(nullable = true) String rejectReason,
      OffsetDateTime createdAt,
      long version) {}

  /** 留痕行。 */
  public record SupplierClaimEventRow(
      UUID id,
      String eventType,
      String eventTypeLabel,
      String fromStatus,
      String toStatus,
      BigDecimal amount,
      String reasonNote,
      OffsetDateTime occurredAt) {}

  /** 索赔单全景（头 + 明细 + 协商 + 赔付 + 留痕 + 当前可执行动作）。 */
  public record SupplierClaimDetail(
      SupplierClaimRow head,
      List<SupplierClaimItemRow> items,
      List<SupplierClaimNegotiationRow> negotiations,
      List<SupplierClaimSettlementRow> settlements,
      List<SupplierClaimEventRow> events,
      List<SupplierClaimAction> actions,
      OffsetDateTime evaluatedAt,
      String note) {}

  /**
   * 可执行动作。
   *
   * <p>⚠️ 每个动作都带 {@code blockedReason}：不可执行时界面要能**说清楚为什么**，
   * 而不是把按钮灰掉让人猜。
   */
  public record SupplierClaimAction(
      String code,
      String label,
      boolean allowed,
      String method,
      String path,
      boolean required,
      String precondition,
      String blockedReason) {}

  // ================================================================ 能力声明

  /** 能力与边界声明（一个块 = 职责句的一个动词）。 */
  public record SupplierClaimSectionCapability(
      String section, String label, boolean available, String ownerModule, String reason,
      String landsIn) {}

  /** 上下游模块的交付状态。 */
  public record SupplierClaimRelatedModule(
      String id, String label, boolean delivered, String ownerModule, String reason, String href) {}

  /** 动作声明（与 {@link SupplierClaimAction} 同形，便于能力声明与实际动作对齐）。 */
  public record SupplierClaimCapabilityAction(
      String code,
      String label,
      boolean allowed,
      String method,
      String path,
      boolean required,
      String precondition,
      String blockingReason,
      String ownerModule,
      String unavailableReason) {}

  /** 边界声明：本模块刻意不做的事。 */
  public record SupplierClaimBoundary(String boundary, String ownerModule, String reason) {}

  /** 能力声明整体。 */
  public record SupplierClaimCapabilities(
      List<String> dataSources,
      List<SupplierClaimSectionCapability> sections,
      List<SupplierClaimRelatedModule> related,
      List<SupplierClaimCapabilityAction> actions,
      List<SupplierClaimBoundary> boundaries,
      String stateMachine,
      String hardRuleClaimBasis,
      String hardRuleFinanceConfirm,
      String negotiationRule) {}
}
