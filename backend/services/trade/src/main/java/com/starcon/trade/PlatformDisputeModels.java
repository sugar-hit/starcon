package com.starcon.trade;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 11.4.1.1 平台纠纷的传输模型。
 *
 * <p>蓝图 11-aftersales-and-returns.md 原文：
 *
 * <blockquote>
 * 职责：管理平台纠纷/争议（Amazon A-to-Z、eBay 争议、速卖通纠纷）：类型、金额、平台时限、举证要求、我方立场、结果记录。
 * 输入：平台纠纷事件/同步；责任判定；举证材料。
 * 输出：纠纷单 + 时效倒计时 + 举证清单。
 * 关系：上游 = 2 平台同步、15 客服；下游 = 11.4.1.2 申诉、12 财务（争议金额影响结算）。
 * <b>平台纠纷有严格举证时限</b>，超期自动判败，是硬性时效要求。
 * </blockquote>
 *
 * <p><b>本文件最重要的三件事</b>：
 *
 * <ol>
 *   <li><b>倒计时是派生态，不是字段</b>。{@link PlatformDisputeRow#secondsRemaining} 与
 *       {@link PlatformDisputeRow#deadlineState} 都是**读取时刻**由
 *       {@code evidenceDeadline} 算出来的；库里<b>没有</b>「剩余小时数」列 ——
 *       那一列写下去就开始自己过期。真正的事实只有 {@code evidenceDeadline} 一个。</li>
 *   <li><b>「超期自动判败」是状态，不是提示</b>。{@link PlatformDisputeStatus#RESOLVED_LOST}
 *       + {@link PlatformDisputeResolution#DEADLINE_EXPIRED} 是 V265 守卫① 在 DB 侧
 *       强制落下来的结论：超期后任何一次写入都改不掉它，且**超期后不可再提交举证**。</li>
 *   <li><b>「登记」与「生效」是两件事</b>。{@link PlatformDisputeSettlementRow#requestedAmount}
 *       （登记）与 {@link PlatformDisputeSettlementRow#effectiveAmount}（已生效）在财务确认之前
 *       <b>必然不相等</b>（effective 恒为 0）—— 「争议金额影响结算」不是自动到账。</li>
 * </ol>
 *
 * <p><b>命名。</b>统一用 {@code PlatformDispute*} 前缀，与 {@code SupplierClaim*} /
 * {@code ReturnQuality*} 零重叠 —— 契约被 codegen 合并进一个扁平文件、同名后者覆盖前者且不报冲突。
 */
public final class PlatformDisputeModels {

  private PlatformDisputeModels() {}

  // ================================================================ 枚举

  /**
   * 平台纠纷类型（蓝图点名的三种 + 兜底）。
   *
   * <p>⚠️ 三种具体类型各自**绑定一个平台**（V265 守卫⑧）：
   * A_TO_Z 只可能是 AMAZON，EBAY_DISPUTE 只可能是 EBAY，ALIEXPRESS_DISPUTE 只可能是 ALIEXPRESS。
   * 「type='A_TO_Z' 但 platform_code='EBAY'」这种行在库里存不进去 ——
   * 事后没人说得清这条纠纷到底是按哪家的规则判的。
   */
  public enum PlatformDisputeType {
    A_TO_Z("Amazon A-to-Z 索赔"),
    EBAY_DISPUTE("eBay 买家争议"),
    ALIEXPRESS_DISPUTE("速卖通纠纷"),
    OTHER("其它平台纠纷");

    private final String label;

    PlatformDisputeType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 该类型必须绑定的平台码；{@link #OTHER} 不绑定（任意平台）。 */
    public String requiredPlatformCode() {
      return switch (this) {
        case A_TO_Z -> "AMAZON";
        case EBAY_DISPUTE -> "EBAY";
        case ALIEXPRESS_DISPUTE -> "ALIEXPRESS";
        case OTHER -> null;
      };
    }
  }

  /**
   * 纠纷单状态机。
   *
   * <p>⚠️ <b>三个终态不可逆</b>：{@link #RESOLVED_WON} / {@link #RESOLVED_LOST} /
   * {@link #WITHDRAWN} 一旦落定，业务要素整行冻结（V265 守卫⑦）。
   * 平台纠纷是要拿去跟平台对质的凭据，「我们当时是什么立场、什么时候交的证据、
   * 结果是什么」必须只有一个答案。
   */
  public enum PlatformDisputeStatus {
    OPEN("已立案"),
    EVIDENCE_PREPARING("举证准备中"),
    EVIDENCE_SUBMITTED("举证已提交"),
    RESOLVED_WON("我方胜诉"),
    RESOLVED_LOST("我方败诉"),
    WITHDRAWN("我方撤诉");

    private final String label;

    PlatformDisputeStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 终态：不可再流转、不可再改业务要素。 */
    public boolean terminal() {
      return this == RESOLVED_WON || this == RESOLVED_LOST || this == WITHDRAWN;
    }

    /** 还在举证窗口内（可以动举证清单）。 */
    public boolean evidenceOpen() {
      return this == OPEN || this == EVIDENCE_PREPARING;
    }
  }

  /**
   * 我方立场（职责句「我方立场」）。
   *
   * <p>⚠️ {@link #UNDETERMINED} 不是「随便选一个」：立场待定时单据**照样有举证时限**，
   * 待定不是拖延的借口。
   */
  public enum PlatformDisputePosition {
    ACCEPT("承认责任"),
    REJECT("否认责任"),
    PARTIAL_ACCEPT("部分承认"),
    NEGOTIATE("协商处理"),
    UNDETERMINED("立场待定");

    private final String label;

    PlatformDisputePosition(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 责任判定（蓝图输入句「责任判定」）。
   *
   * <p>⚠️ 平台纠纷**没有**对应的上游责任判定表（11.1.2.2 商品问题归因是退货链的），
   * 所以这里落的是**本模块自有的判定字段**，不跨库引任何表。
   */
  public enum PlatformLiabilityStage {
    MERCHANT_PRODUCT("商品问题"),
    MERCHANT_FULFILLMENT("履约问题"),
    LOGISTICS("物流问题"),
    BUYER("买家问题"),
    PLATFORM("平台问题"),
    UNDETERMINED("责任待定");

    private final String label;

    PlatformLiabilityStage(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 结果码（职责句「结果记录」）。 */
  public enum PlatformDisputeResolution {
    DEADLINE_EXPIRED("举证超期自动判负"),
    PLATFORM_RULING_FOR("平台判我方胜诉"),
    PLATFORM_RULING_AGAINST("平台判我方败诉"),
    MUTUAL_AGREEMENT("双方达成一致"),
    WITHDRAWN_BY_US("我方主动撤诉");

    private final String label;

    PlatformDisputeResolution(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 举证材料类型（职责句「举证要求」）。 */
  public enum PlatformEvidenceType {
    ORDER_FULFILLMENT_PROOF("订单履约证明"),
    LOGISTICS_TRACKING_PROOF("物流轨迹证明"),
    INVOICE("发票 / 付款凭证"),
    PRODUCT_PHOTO("商品实物照片"),
    PRODUCT_DESCRIPTION_SNAPSHOT("商品描述快照"),
    COMMUNICATION_LOG("与买家沟通记录"),
    PLATFORM_MESSAGE("平台消息截图"),
    OTHER("其它材料");

    private final String label;

    PlatformEvidenceType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 单项举证材料的状态。
   *
   * <p>三态而不是「有 / 没有」两态：<b>PENDING</b>（还没看）与
   * <b>MISSING</b>（确认缺失）在提交前是**两件不同的事** ——
   * 前者不知道要不要交，后者知道交不了。
   */
  public enum PlatformEvidenceState {
    PENDING("待核查"),
    PROVIDED("已具备"),
    MISSING("确认缺失");

    private final String label;

    PlatformEvidenceState(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * **结算分录**的状态（下游 12 财务当前不存在 ⇒ trade 侧自有事实记录）。
   *
   * <p>⚠️ {@link #PENDING_FINANCE_CONFIRM} 不是「已生效但没回执」：
   * 在这个状态下 {@code effectiveAmount} **恒为 0**（V265 表级 CHECK）。
   */
  public enum PlatformSettlementStatus {
    PENDING_FINANCE_CONFIRM("待财务确认"),
    CONFIRMED("财务已确认"),
    REJECTED("财务已驳回");

    private final String label;

    PlatformSettlementStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 已生效（结算影响真的落到结算上了）。 */
    public boolean effective() {
      return this == CONFIRMED;
    }
  }

  /**
   * **纠纷单头上**的结算口径状态（由 DB 从结算分录重算）。
   *
   * <p>⚠️ 与 {@link PlatformSettlementStatus} 是**两个不同的状态空间**，刻意不合并：
   * 分录说「这一笔有没有被确认」，头上说「这个纠纷的结算影响现在是什么口径」。
   * 合并成一个枚举就会出现「分录是 CONFIRMED，头上的状态叫 CONFIRMED」
   * 这种在两处都读得通、却在同一张单据上出现两个答案的口径。
   */
  public enum PlatformSettlementState {
    NOT_APPLIED("未登记结算影响"),
    PENDING_FINANCE_CONFIRM("待财务确认"),
    APPLIED("结算影响已生效"),
    FINANCE_REJECTED("财务已驳回");

    private final String label;

    PlatformSettlementState(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 已生效（结算影响真的落到结算上了）。 */
    public boolean effective() {
      return this == APPLIED;
    }
  }

  /**
   * 举证时限的派生状态（前端呈现倒计时用）。
   *
   * <p>⚠️ 这是**读取时刻**由 {@code evidenceDeadline} 算出来的，不是库里的列。
   */
  public enum PlatformDeadlineState {
    NORMAL("时限充裕"),
    URGENT("即将到期"),
    PASSED("已超期"),
    RELEASED("举证已提交，时限解除");

    private final String label;

    PlatformDeadlineState(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  // ================================================================ 入参

  /**
   * 登记平台纠纷（建单）。
   *
   * <p>⚠️ <b>这是本模块自己的建单入口</b>：channel 服务**没有**纠纷专用同步端点
   * （已交付的是平台目录 / 连接 / 店铺 / 同步任务 / 同步运行 / 回写 / webhook / 增量补拉），
   * crm 服务的客服工单 / 客户模块**未开发** ⇒ 本模块**不假装**有个平台推送接口，
   * 纠纷由平台侧或人工登记，平台来源信息以**快照**落库。
   *
   * <p>⚠️ <b>平台名要显式给</b>：平台主数据在 channel 独立库，跨库读不到，
   * 所以这里收的是「登记那一刻看到的平台名」快照。
   */
  public record PlatformDisputeCreate(
      String platformCode,
      String platformName,
      String platformDisputeNo,
      String disputeType,
      @Schema(nullable = true) String disputeReason,
      @Schema(nullable = true) String platformOrderNo,
      String disputeCurrency,
      BigDecimal disputeAmount,
      OffsetDateTime evidenceDeadline,
      @Schema(nullable = true) String ourPosition,
      @Schema(nullable = true) String liabilityStage,
      @Schema(nullable = true) String positionNote,
      @Schema(nullable = true) String liabilityNote) {}

  /**
   * 加一项举证材料（举证清单的一行）。
   *
   * <p>⚠️ 这里**没有**「材料本体」字段 —— 11.4.1.2 证据与申诉未开发，
   * 本模块只管**清单事实**：该交什么、齐不齐、缺什么。
   */
  public record PlatformEvidenceItemCreate(
      String evidenceType,
      @Schema(nullable = true) String evidenceLabel,
      @Schema(nullable = true) Boolean required,
      @Schema(nullable = true) String evidenceNote) {}

  /**
   * 更新一项举证材料的状态。
   *
   * <p>⚠️ {@code evidenceState='MISSING'} 时必须给 {@code missingReason} ——
   * 只标「缺」而不说缺哪一样，提交前没人知道要补什么（V265 表级 CHECK 钉死）。
   */
  public record PlatformEvidenceMark(
      String evidenceState,
      @Schema(nullable = true) String evidenceNote,
      @Schema(nullable = true) String missingReason) {}

  /** 更新我方立场 + 责任判定。 */
  public record PlatformDisputePositionUpdate(
      String ourPosition,
      String liabilityStage,
      @Schema(nullable = true) String positionNote,
      @Schema(nullable = true) String liabilityNote,
      Long expectedVersion) {}

  /**
   * 提交举证（举证准备中 → 举证已提交）。
   *
   * <p>⚠️ **清单不齐不能提交**（V265 守卫⑥），**超期不能提交**（V265 守卫①）。
   */
  public record PlatformDisputeSubmitEvidence(
      @Schema(nullable = true) String submitNote, Long expectedVersion) {}

  /**
   * 记录平台裁决结果。
   *
   * <p>⚠️ 结果码**不接受** {@code DEADLINE_EXPIRED}：超期判负是系统做的，
   * 不该由人手动声明（V265 守卫⑥）。
   */
  public record PlatformDisputeResolve(
      String resolutionCode, @Schema(nullable = true) String resolutionNote, Long expectedVersion) {}

  /** 我方撤诉。必须写理由。 */
  public record PlatformDisputeWithdraw(
      @Schema(nullable = true) String withdrawReason, Long expectedVersion) {}

  /**
   * 登记结算影响（争议金额进结算）。
   *
   * <p>⚠️ <b>没有生效金额字段</b>：登记时 {@code effectiveAmount} 恒为 0，
   * 只有财务确认那一刻才由 DB 把它改成登记金额。
   */
  public record PlatformDisputeSettlementCreate(BigDecimal requestedAmount) {}

  /**
   * 财务确认结算生效。
   *
   * <p>⚠️ <b>必须带财务单据号</b>：事后要答得出「扣的是哪一笔」。
   */
  public record PlatformDisputeSettlementConfirm(String financeReference) {}

  /** 财务驳回结算影响。必须写理由。 */
  public record PlatformDisputeSettlementReject(String rejectReason) {}

  /** 列表查询。真分页 + 多维筛选 + 排序白名单。 */
  public record PlatformDisputeListQuery(
      @Schema(nullable = true) Integer page,
      @Schema(nullable = true) Integer size,
      @Schema(nullable = true) String status,
      @Schema(nullable = true) String disputeType,
      @Schema(nullable = true) String platformCode,
      @Schema(nullable = true) String currency,
      @Schema(nullable = true) String ourPosition,
      @Schema(nullable = true) String liabilityStage,
      @Schema(nullable = true) String settlementStatus,
      @Schema(nullable = true) Boolean overdueOnly,
      @Schema(nullable = true) BigDecimal minAmount,
      @Schema(nullable = true) BigDecimal maxAmount,
      @Schema(nullable = true) OffsetDateTime from,
      @Schema(nullable = true) OffsetDateTime to,
      @Schema(nullable = true) String keyword,
      @Schema(nullable = true) String sort) {}

  // ================================================================ 出参

  /** 纠纷单行（列表用）。 */
  public record PlatformDisputeRow(
      UUID id,
      String disputeNo,
      String platformCode,
      String platformName,
      String platformDisputeNo,
      String platformOrderNo,
      String disputeType,
      String disputeTypeLabel,
      @Schema(nullable = true) String disputeReason,
      String disputeCurrency,
      BigDecimal disputeAmount,
      OffsetDateTime evidenceDeadline,
      long secondsRemaining,
      String deadlineState,
      String deadlineStateLabel,
      String ourPosition,
      String ourPositionLabel,
      String positionNote,
      String liabilityStage,
      String liabilityStageLabel,
      String liabilityNote,
      String disputeStatus,
      String disputeStatusLabel,
      @Schema(nullable = true) OffsetDateTime evidenceSubmittedAt,
      String settlementState,
      String settlementStateLabel,
      BigDecimal effectiveAmount,
      long evidenceTotal,
      long evidenceProvided,
      long evidenceMissing,
      @Schema(nullable = true) String resolutionCode,
      @Schema(nullable = true) String resolutionCodeLabel,
      @Schema(nullable = true) OffsetDateTime resolvedAt,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt,
      long version) {}

  /** 纠纷单列表页。 */
  public record PlatformDisputePage(
      List<PlatformDisputeRow> rows,
      int page,
      int size,
      long total,
      PlatformDisputeSummary summary,
      List<PlatformDisputeCurrencyTotal> currencyBreakdown,
      OffsetDateTime evaluatedAt,
      String paginationNote,
      String deadlineNote,
      String settlementNote,
      String currencyNote,
      String sortNote) {}

  /**
   * 纠纷单页汇总（跨全表算，不随分页变小）。
   *
   * <p>⚠️ <b>这里没有金额合计</b>：跨币种加总是没有意义的数字。
   * 金额口径在 {@link PlatformDisputeCurrencyTotal} 里**按币种分组**。
   */
  public record PlatformDisputeSummary(
      long total,
      long open,
      long evidencePreparing,
      long evidenceSubmitted,
      long resolvedWon,
      long resolvedLost,
      long withdrawn,
      long overdue,
      long awaitingFinanceConfirm) {}

  /** 按币种分组的金额口径（一单一币种，不做固定汇率折算）。 */
  public record PlatformDisputeCurrencyTotal(
      String currency, long disputeCount, BigDecimal totalAmount, BigDecimal effectiveAmount) {}

  /** 举证清单行。 */
  public record PlatformDisputeEvidenceRow(
      UUID id,
      UUID disputeId,
      int evidenceSeq,
      String evidenceType,
      String evidenceTypeLabel,
      String evidenceLabel,
      boolean required,
      String evidenceState,
      String evidenceStateLabel,
      String evidenceNote,
      String missingReason,
      @Schema(nullable = true) OffsetDateTime providedAt,
      OffsetDateTime createdAt) {}

  /** 结算影响分录行。 */
  public record PlatformDisputeSettlementRow(
      UUID id,
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
  public record PlatformDisputeEventRow(
      UUID id,
      String eventType,
      String eventTypeLabel,
      @Schema(nullable = true) String fromStatus,
      @Schema(nullable = true) String toStatus,
      @Schema(nullable = true) BigDecimal amount,
      String reasonNote,
      OffsetDateTime occurredAt) {}

  /** 纠纷单全景（头 + 举证清单 + 结算分录 + 留痕 + 当前可执行动作）。 */
  public record PlatformDisputeDetail(
      PlatformDisputeRow head,
      List<PlatformDisputeEvidenceRow> evidenceItems,
      List<PlatformDisputeSettlementRow> settlements,
      List<PlatformDisputeEventRow> events,
      List<PlatformDisputeAction> actions,
      OffsetDateTime evaluatedAt,
      String note) {}

  /**
   * 可执行动作。
   *
   * <p>⚠️ 每个动作都带 {@code blockedReason}：不可执行时界面要能**说清楚为什么**，
   * 而不是把按钮灰掉让人猜。
   */
  public record PlatformDisputeAction(
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
  public record PlatformDisputeSectionCapability(
      String section, String label, boolean available, String ownerModule, String reason,
      String landsIn) {}

  /** 上下游模块的交付状态。 */
  public record PlatformDisputeRelatedModule(
      String id, String label, boolean delivered, String ownerModule, String reason, String href) {}

  /** 动作声明（与 {@link PlatformDisputeAction} 同形，便于能力声明与实际动作对齐）。 */
  public record PlatformDisputeCapabilityAction(
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
  public record PlatformDisputeBoundary(String boundary, String ownerModule, String reason) {}

  /** 能力声明整体。 */
  public record PlatformDisputeCapabilities(
      List<String> dataSources,
      List<PlatformDisputeSectionCapability> sections,
      List<PlatformDisputeRelatedModule> related,
      List<PlatformDisputeCapabilityAction> actions,
      List<PlatformDisputeBoundary> boundaries,
      String stateMachine,
      String hardRuleDeadline,
      String hardRuleFinanceConfirm,
      String evidenceListRule,
      String platformSyncGap) {}
}
