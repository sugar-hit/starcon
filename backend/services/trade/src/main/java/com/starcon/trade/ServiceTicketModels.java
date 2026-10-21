package com.starcon.trade;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 11.4.2.1 工单管理的传输模型。
 *
 * <p>蓝图 11-aftersales-and-returns.md 原文：
 *
 * <blockquote>
 * 职责：管理非售后类的客户服务工单：咨询、投诉、催单、平台政策咨询；支持分配、SLA 计时、转派、关闭与满意度评价。
 * 输入：工单创建（人工/来自消息/自动触发）；SLA 规则；分配规则。
 * 输出：工单（待处理/处理中/待买家/已关闭）+ SLA 达成统计。
 * 关系：上游 = 15 客户与客服；下游 = 各业务模块（需转单处理）。<b>工单与售后单要能互转</b>：咨询升级为投诉、投诉升级为纠纷，需要保留上下文。
 * </blockquote>
 *
 * <p><b>本文件最重要的四件事</b>：
 *
 * <ol>
 *   <li><b>工单是「挂在售后单之上的客服交互」</b>，不是售后单的另一个视图。
 *       预留表 V156 的 {@code case_id uuid NOT NULL} + {@code UNIQUE(tenant_id, case_id)}
 *       把它钉死了：一张售后单**只转一次**工单。
 *       「非售后类」指的是工单**不替代**售后单 —— 售后单管「退/换/补/修」，
 *       工单管「这次来问的这件事有人跟没有、跟得怎么样」。</li>
 *   <li><b>SLA 达成是读取时刻的派生态，达标结论是关闭那一刻冻结的事实</b>。
 *       {@link ServiceTicketSlaState} 由 DB 的 IMMUTABLE 函数现算（判定真身只有一处）；
 *       而「当时有没有达标」由 {@link ServiceTicketSlaSnapshotRow} 冻结 ——
 *       绩效按它算账，不能随着规则改档翻来覆去。</li>
 *   <li><b>「来自消息」是显式的来源引用</b>，不是去读一张不存在的消息表。
 *       15 客户与客服的消息模块（15.1.x / 15.3.x / 15.5.x / 15.6.x）**全部未开发**，
 *       所以 {@link ServiceTicketCreate#sourceRef()} 落的是**调用方给的引用串**，
 *       本服务不去猜「那条消息是哪一条」。</li>
 *   <li><b>转派 / 关闭 / 评价 / 升级都是「已经发生过的事」</b>，
 *       落成 append-only 的附属事实表，不塞进主表的当前态里。</li>
 * </ol>
 *
 * <p><b>命名。</b>统一用 {@code ServiceTicket*} 前缀，与 {@code PlatformDispute*} /
 * {@code AftersalesCase*} 零重叠 —— 契约被 codegen 合并进一个扁平文件、
 * 同名后者覆盖前者且不报冲突。
 */
public final class ServiceTicketModels {

  private ServiceTicketModels() {}

  // ================================================================ 枚举

  /**
   * 工单类型（职责句点名的四类）。
   *
   * <p>⚠️ 咨询与投诉的差别**不是**严重程度，是**承诺**：咨询是我们答复就完事，
   * 投诉意味着买家已经明确表达了不满、后面可能要走售后流程。
   * 所以「咨询升级为投诉」是一次**带方向的类型推进**（不可逆），
   * 而不是把标签换一换。
   */
  public enum ServiceTicketType {
    INQUIRY("咨询"),
    COMPLAINT("投诉"),
    ORDER_CHASE("催单"),
    POLICY_CONSULT("平台政策咨询");

    private final String label;

    ServiceTicketType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 工单状态机（输出句点名的四态 + 取消）。
   *
   * <p>推进顺序：{@code OPEN → IN_PROGRESS ⇄ WAITING_BUYER → RESOLVED}，
   * 任何未关闭状态都可 {@code → CANCELLED}。
   *
   * <p>⚠️ {@link #CANCELLED} 与 {@link #RESOLVED} 是**两个不同的终态**：
   * 前者是「这件事不归我们管了」，后者是「这件事办完了」。
   * 统计上「已关闭」含两者，但「办完了」只含前者。
   */
  public enum ServiceTicketStatus {
    OPEN("待处理"),
    IN_PROGRESS("处理中"),
    WAITING_BUYER("待买家"),
    RESOLVED("已关闭"),
    CANCELLED("已取消");

    private final String label;

    ServiceTicketStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 终态：不可再流转。 */
    public boolean terminal() {
      return this == RESOLVED || this == CANCELLED;
    }

    /** 还在计时（回复时限的倒计时只对未关闭的工单有意义）。 */
    public boolean open() {
      return this != RESOLVED && this != CANCELLED;
    }
  }

  /** 优先级（沿用预留表的四档词表，一个字不改）。 */
  public enum ServiceTicketPriority {
    LOW("低"),
    NORMAL("普通"),
    HIGH("高"),
    URGENT("紧急");

    private final String label;

    ServiceTicketPriority(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 创建来源（输入句「工单创建（人工/来自消息/自动触发）」）。
   *
   * <p>⚠️ {@link #MESSAGE} **不代表**本服务读过消息表：15 客户与客服的消息模块未开发，
   * 所以「来自消息」是一个**调用方声明的来源**，消息号由调用方给出。
   */
  public enum ServiceTicketSource {
    MANUAL("人工创建"),
    MESSAGE("来自消息"),
    AUTOMATIC("自动触发");

    private final String label;

    ServiceTicketSource(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 关闭原因。
   *
   * <p>⚠️ 「关闭」和「办完」不是一回事：{@link #NO_RESPONSE}（买家没回）也是关闭，
   * 但它**不是**已解决 —— 统计上「办完了」不含它。
   */
  public enum ServiceTicketCloseReason {
    RESOLVED("已解决"),
    NO_RESPONSE("买家未回应"),
    DUPLICATE("重复工单"),
    CANCELLED("与本单无关"),
    ESCALATED("已升级转单"),
    ABANDONED("不再跟进");

    private final String label;

    ServiceTicketCloseReason(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 这一关代表「事情办完了」吗（vs 只是不再跟了）。 */
    public boolean resolved() {
      return this == RESOLVED || this == ESCALATED;
    }
  }

  /** 派单动作。 */
  public enum ServiceTicketAssignAction {
    ASSIGN("首派"),
    TRANSFER("转派"),
    CLAIM("认领"),
    RELEASE("释放");

    private final String label;

    ServiceTicketAssignAction(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 派单方式（分配规则）。 */
  public enum ServiceTicketAssignMode {
    MANUAL("人工指定"),
    ROSTER_ROUND_ROBIN("名册加权轮转"),
    AUTO_ROUTE("系统自动路由"),
    CLAIM("主动认领");

    private final String label;

    ServiceTicketAssignMode(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 工单事件类型（留痕表的词表）。
   *
   * <p>⚠️ 词表与 V270 的 {@code aftersales_ticket_event.event_type} CHECK **逐字对齐** ——
   * 两边不一致的话，某一类事件会写不进库（而报错说的是「取值非法」，与根因毫无关系）。
   */
  public enum ServiceTicketEventType {
    CREATED("已创建"),
    ASSIGNED("已派单"),
    TRANSFERRED("已转派"),
    CLAIMED("已认领"),
    RELEASED("已释放"),
    STATUS_CHANGED("状态流转"),
    FIRST_REPLIED("已首次回复"),
    WAITING_BUYER("转待买家"),
    RESUMED("恢复处理"),
    CLOSED("已关闭"),
    CANCELLED("已取消"),
    SLA_BREACHED("SLA 超期"),
    SATISFACTION_RECORDED("已评价"),
    ESCALATED_TO_COMPLAINT("已升级为投诉"),
    ESCALATED_TO_DISPUTE("已升级为纠纷");

    private final String label;

    ServiceTicketEventType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * SLA 判定结果（由 DB 的 IMMUTABLE 函数 {@code aftersales_ticket_sla_state} 现算）。
   *
   * <p>⚠️ Java 与前端都**刻意不重算** —— 两处实现必然漂移，
   * 而 SLA 达标直接决定客服绩效口径。
   */
  public enum ServiceTicketSlaState {
    NO_DEADLINE("无时限"),
    PENDING("计时中"),
    MET("已达标"),
    BREACHED("已超期");

    private final String label;

    ServiceTicketSlaState(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** SLA 时限的来源（取哪个口径算出来的）。 */
  public enum ServiceTicketSlaBasis {
    PLATFORM_GIVEN("平台给定"),
    POLICY_SLA("租户 SLA 策略");

    private final String label;

    ServiceTicketSlaBasis(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 升级去向（蓝图加粗句「工单与售后单要能互转」）。 */
  public enum ServiceTicketEscalateTarget {
    COMPLAINT("升级为投诉"),
    DISPUTE("升级为纠纷");

    private final String label;

    ServiceTicketEscalateTarget(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  // ================================================================ 入参

  /**
   * 建一张工单。
   *
   * <p>⚠️ {@code caseId} **必填**：工单永远挂在一张售后单上（预留表的硬约束）。
   * 挂在哪张单上，就是这次客服交互服务的是哪笔生意。
   *
   * <p>⚠️ {@code replyDueAt} / {@code resolveDueAt} 留空时**从租户 SLA 策略解析**
   * （{@code aftersales_reply_sla}，V164），解析不到就**报错** ——
   * 本服务不替租户编一个「默认 48 小时」：编出来的时限会让
   * 「我们没配 SLA」和「我们承诺 48 小时」在绩效口径上混成同一件事。
   */
  public record ServiceTicketCreate(
      String caseId,
      String ticketType,
      String sourceChannel,
      @Schema(nullable = true) String sourceRef,
      @Schema(nullable = true) String subject,
      @Schema(nullable = true) String priority,
      @Schema(nullable = true) String reasonNote,
      @Schema(nullable = true) String assignedToRef,
      @Schema(nullable = true) OffsetDateTime replyDueAt,
      @Schema(nullable = true) OffsetDateTime resolveDueAt,
      @Schema(nullable = true) UUID knowledgeEntryId) {}

  /** 人工派单：指定一个责任人。 */
  public record ServiceTicketAssign(
      String ownerRef,
      @Schema(nullable = true) UUID ownerUserId,
      @Schema(nullable = true) String reasonNote,
      Long expectedVersion) {}

  /** 按名册**加权最少在办**自动派单（分配规则）。 */
  public record ServiceTicketAutoAssign(@Schema(nullable = true) String reasonNote, Long expectedVersion) {}

  /** 转派给另一个人。必须写理由。 */
  public record ServiceTicketTransfer(
      String toOwnerRef,
      @Schema(nullable = true) UUID toOwnerUserId,
      String reasonNote,
      Long expectedVersion) {}

  /**
   * 主动认领（从未分配或别人放出来的单上）。
   *
   * <p>⚠️ 必须给 {@code ownerRef}：责任人 = **名册里的一行**（11.1.1.3 的名册），
   * 不是一个随手写上去的名字 —— 名字不在名册上就没人可追责。
   */
  public record ServiceTicketClaim(
      String ownerRef,
      @Schema(nullable = true) UUID ownerUserId,
      @Schema(nullable = true) String reasonNote,
      Long expectedVersion) {}

  /** 释放责任人（把单放回待处理池）。必须写理由。 */
  public record ServiceTicketRelease(String reasonNote, Long expectedVersion) {}

  /** 记录首次回复（回复时限的完成时刻）。 */
  public record ServiceTicketFirstReply(
      @Schema(nullable = true) String replyNote, Long expectedVersion) {}

  /** 状态流转（待处理 → 处理中 ⇄ 待买家）。 */
  public record ServiceTicketStatusChange(String targetStatus, @Schema(nullable = true) String note, Long expectedVersion) {}

  /** 关闭工单。必须写关闭原因。 */
  public record ServiceTicketClose(
      String closeReason, @Schema(nullable = true) String closeNote, Long expectedVersion) {}

  /** 满意度评价（1~5 分）。 */
  public record ServiceTicketSatisfactionCreate(
      Integer score, @Schema(nullable = true) String comment) {}

  /**
   * 升级：咨询 → 投诉（新建售后单）/ 投诉 → 纠纷（新建平台纠纷）。
   *
   * <p>⚠️ 两类升级要带的字段**完全不同**，所以放在同一个请求体里按 {@code targetType} 取用：
   * <ul>
   *   <li>{@code targetType=COMPLAINT} ⇒ 用 {@code targetCaseType} / {@code targetReasonCode} /
   *       {@code targetAmount} / {@code targetCurrency} / {@code targetQuantity} 建一张售后单；</li>
   *   <li>{@code targetType=DISPUTE} ⇒ 用 {@code platformCode} / {@code platformName} /
   *       {@code platformDisputeNo} / {@code disputeType} / {@code disputeAmount} /
   *       {@code disputeCurrency} / {@code evidenceDeadline} 建一条平台纠纷（11.4.1.1）。</li>
   * </ul>
   */
  public record ServiceTicketEscalate(
      String targetType,
      String reasonNote,
      @Schema(nullable = true) String targetCaseType,
      @Schema(nullable = true) String targetReasonCode,
      @Schema(nullable = true) BigDecimal targetAmount,
      @Schema(nullable = true) String targetCurrency,
      @Schema(nullable = true) BigDecimal targetQuantity,
      @Schema(nullable = true) String platformCode,
      @Schema(nullable = true) String platformName,
      @Schema(nullable = true) String platformDisputeNo,
      @Schema(nullable = true) String platformOrderNo,
      @Schema(nullable = true) String disputeType,
      @Schema(nullable = true) BigDecimal disputeAmount,
      @Schema(nullable = true) String disputeCurrency,
      @Schema(nullable = true) OffsetDateTime evidenceDeadline,
      Long expectedVersion) {}

  /** 列表查询。真分页 + 多维筛选 + 排序白名单。 */
  public record ServiceTicketListQuery(
      @Schema(nullable = true) Integer page,
      @Schema(nullable = true) Integer size,
      @Schema(nullable = true) String status,
      @Schema(nullable = true) String ticketType,
      @Schema(nullable = true) String priority,
      @Schema(nullable = true) String sourceChannel,
      @Schema(nullable = true) String assignedToRef,
      @Schema(nullable = true) String caseId,
      @Schema(nullable = true) String slaState,
      @Schema(nullable = true) Boolean overdueOnly,
      @Schema(nullable = true) Boolean unassignedOnly,
      @Schema(nullable = true) OffsetDateTime from,
      @Schema(nullable = true) OffsetDateTime to,
      @Schema(nullable = true) String keyword,
      @Schema(nullable = true) String sort) {}

  // ================================================================ 出参

  /**
   * 工单行（列表用）。
   *
   * <p>⚠️ {@code replySlaState} / {@code resolveSlaState} 是**读取时刻**的派生态；
   * {@code secondsToReplyDue} 同理 —— 库里**没有**「剩余小时数」列，
   * 那一列写下去就开始自己过期。
   */
  public record ServiceTicketRow(
      UUID id,
      String ticketNo,
      String ticketType,
      String ticketTypeLabel,
      String ticketStatus,
      String ticketStatusLabel,
      String priority,
      String priorityLabel,
      String sourceChannel,
      String sourceChannelLabel,
      String sourceRef,
      String subject,
      String reasonNote,
      String assignedToRef,
      @Schema(nullable = true) UUID assignedToUserId,
      @Schema(nullable = true) OffsetDateTime assignedAt,
      @Schema(nullable = true) UUID caseId,
      String caseNo,
      String caseStatus,
      @Schema(nullable = true) String caseType,
      @Schema(nullable = true) String platformScope,
      @Schema(nullable = true) OffsetDateTime caseReplyDueAt,
      @Schema(nullable = true) OffsetDateTime caseResolveDueAt,
      @Schema(nullable = true) UUID knowledgeEntryId,
      @Schema(nullable = true) UUID slaPolicyId,
      @Schema(nullable = true) Integer slaReplyHours,
      @Schema(nullable = true) Integer slaResolveHours,
      String slaBasis,
      String slaBasisLabel,
      @Schema(nullable = true) OffsetDateTime replyDueAt,
      @Schema(nullable = true) OffsetDateTime resolveDueAt,
      @Schema(nullable = true) OffsetDateTime firstReplyAt,
      String replySlaState,
      String replySlaStateLabel,
      String resolveSlaState,
      String resolveSlaStateLabel,
      long secondsToReplyDue,
      @Schema(nullable = true) OffsetDateTime waitingBuyerAt,
      @Schema(nullable = true) OffsetDateTime closedAt,
      String closeReason,
      String closeReasonLabel,
      String closeNote,
      @Schema(nullable = true) UUID escalatedCaseId,
      @Schema(nullable = true) String escalatedCaseNo,
      @Schema(nullable = true) UUID escalatedDisputeId,
      @Schema(nullable = true) String escalatedDisputeNo,
      @Schema(nullable = true) Integer satisfactionRating,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt,
      long version) {}

  /** 列表页。 */
  public record ServiceTicketPage(
      List<ServiceTicketRow> rows,
      int page,
      int size,
      long total,
      ServiceTicketSummary summary,
      ServiceTicketSlaStats slaStats,
      OffsetDateTime evaluatedAt,
      String paginationNote,
      String slaNote,
      String sortNote) {}

  /** 汇总（跨全表算，不随分页变小）。 */
  public record ServiceTicketSummary(
      long total,
      long open,
      long inProgress,
      long waitingBuyer,
      long resolved,
      long cancelled,
      long unassigned,
      long overdue,
      long complaint,
      long inquiry) {}

  /**
   * SLA 达成统计（输出句「+ SLA 达成统计」）。
   *
   * <p>⚠️ 口径只认**关闭时冻结的快照**（{@code aftersales_ticket_sla_snapshot}）：
   * 「当时有没有达标」是一个已经发生的事实，不能随着规则改档翻来覆去。
   *
   * <p>⚠️ 两个达成率**可空**：分母为 0 时是 {@code null} 而不是 0% ——
   * 「没测过」与「全没达标」是两件事，0% 会把前者说成后者。
   */
  public record ServiceTicketSlaStats(
      long closedCount,
      long replyMeasured,
      long replyMet,
      long replyBreached,
      long resolveMeasured,
      long resolveMet,
      long resolveBreached,
      long bothMet,
      @Schema(nullable = true) BigDecimal replyAchievementRate,
      @Schema(nullable = true) BigDecimal resolveAchievementRate) {}

  /** 转派历史行。 */
  public record ServiceTicketAssignmentRow(
      UUID id,
      UUID ticketId,
      String assignAction,
      String assignActionLabel,
      String assignMode,
      String assignModeLabel,
      String fromOwnerRef,
      String toOwnerRef,
      String reasonNote,
      @Schema(nullable = true) UUID createdBy,
      OffsetDateTime createdAt) {}

  /** 事件留痕行。 */
  public record ServiceTicketEventRow(
      UUID id,
      UUID ticketId,
      String eventType,
      String eventTypeLabel,
      String fromStatus,
      String toStatus,
      @Schema(nullable = true) UUID actorId,
      String note,
      OffsetDateTime occurredAt) {}

  /** 满意度评价行。 */
  public record ServiceTicketSatisfactionRow(
      UUID id,
      UUID ticketId,
      int score,
      String comment,
      @Schema(nullable = true) UUID ratedBy,
      OffsetDateTime ratedAt) {}

  /** SLA 达成快照行（关闭那一刻冻结的事实）。 */
  public record ServiceTicketSlaSnapshotRow(
      UUID id,
      UUID ticketId,
      @Schema(nullable = true) UUID slaPolicyId,
      @Schema(nullable = true) Integer slaReplyHours,
      @Schema(nullable = true) Integer slaResolveHours,
      @Schema(nullable = true) OffsetDateTime replyDueAt,
      @Schema(nullable = true) OffsetDateTime resolveDueAt,
      @Schema(nullable = true) OffsetDateTime firstReplyAt,
      OffsetDateTime closedAt,
      boolean replyMet,
      @Schema(nullable = true) Boolean resolveMet,
      OffsetDateTime snapshottedAt) {}

  /**
   * 工单全景（头 + 转派历史 + 事件流 + 评价 + SLA 快照 + 当前可执行动作）。
   *
   * <p>⚠️ 评价与 SLA 快照是**列表**而不是「可能为 null 的对象」：
   * OpenAPI 3.0 的 {@code nullable} 表达不了 {@code $ref} 上的可空
   * （SpringDoc 会把 {@code nullable} 丢掉），契约里就会留下一个「声明非空、实际可能是 null」
   * 的字段 —— 前端按声明直接取属性就会在运行时炸。
   * ⇒ 用 0 或 1 个元素的列表表达，契约与运行时永远一致。
   * （本仓库其它模块同样**没有**「可空的嵌套对象」。）
   */
  public record ServiceTicketDetail(
      ServiceTicketRow head,
      List<ServiceTicketAssignmentRow> assignments,
      List<ServiceTicketEventRow> events,
      List<ServiceTicketSatisfactionRow> satisfactions,
      List<ServiceTicketSlaSnapshotRow> slaSnapshots,
      List<ServiceTicketAction> actions,
      OffsetDateTime evaluatedAt,
      String note) {}

  /**
   * 可执行动作。
   *
   * <p>⚠️ 每个动作都带 {@code blockedReason}：不可执行时界面要能**说清楚为什么**，
   * 而不是把按钮灰掉让人猜。
   */
  public record ServiceTicketAction(
      String code,
      String label,
      boolean allowed,
      @Schema(nullable = true) String method,
      @Schema(nullable = true) String path,
      boolean required,
      @Schema(nullable = true) String precondition,
      @Schema(nullable = true) String blockedReason) {}

  // ================================================================ 能力声明

  /** 能力与边界声明（一个块 = 职责句的一个动词）。 */
  public record ServiceTicketSectionCapability(
      String section,
      String label,
      boolean available,
      @Schema(nullable = true) String ownerModule,
      String reason,
      @Schema(nullable = true) String landsIn) {}

  /** 上下游模块的交付状态。 */
  public record ServiceTicketRelatedModule(
      String id,
      String label,
      boolean delivered,
      @Schema(nullable = true) String ownerModule,
      String reason,
      @Schema(nullable = true) String href) {}

  /** 动作声明（与 {@link ServiceTicketAction} 同形，便于能力声明与实际动作对齐）。 */
  public record ServiceTicketCapabilityAction(
      String code,
      String label,
      boolean allowed,
      @Schema(nullable = true) String method,
      @Schema(nullable = true) String path,
      boolean required,
      @Schema(nullable = true) String precondition,
      @Schema(nullable = true) String blockingReason,
      @Schema(nullable = true) String ownerModule,
      @Schema(nullable = true) String unavailableReason) {}

  /** 边界声明：本模块刻意不做的事。 */
  public record ServiceTicketBoundary(
      String boundary, @Schema(nullable = true) String ownerModule, String reason) {}

  /** 能力声明整体。 */
  public record ServiceTicketCapabilities(
      List<String> dataSources,
      List<ServiceTicketSectionCapability> sections,
      List<ServiceTicketRelatedModule> related,
      List<ServiceTicketCapabilityAction> actions,
      List<ServiceTicketBoundary> boundaries,
      String stateMachine,
      String hardRuleOneTicketPerCase,
      String hardRuleSlaSingleTruth,
      String hardRuleAppendOnly,
      String upstreamGap) {}
}
