package com.starcon.crm;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 15.4.1.2 工单处理的传输模型。
 *
 * <p>蓝图 15-crm-and-service.md 原文：
 *
 * <blockquote>
 * 职责：处理工单：回复、备注、状态流转、关联订单与售后单、解决确认、
 * 满意度回收、SLA 暂停/恢复。
 * 输入：处理动作；关联单据；满意度评分。
 * 输出：工单状态 + 处理记录 + 关联单据更新。
 * 关系：上游 = 15.4.1.1；下游 = 11 售后（升级）、9 订单。
 * <b>工单关闭不等于问题解决</b>，需确认实际结果（退款到账、换货发出）才计为解决。
 * </blockquote>
 *
 * <p><b>本文件最重要的四件事</b>：
 *
 * <ol>
 *   <li><b>工单事实不在本服务</b> —— 工单由 11.4.2.1（trade 服务）拥有，
 *       本模块经授权 HTTP API 写（回复 / 状态 / 关闭 / 满意度 / 升级），
 *       不建跨库 FK / 共享视图 / 直连对方库（AGENTS.md 模块边界纪律）。</li>
 *   <li><b>关闭 ≠ 解决</b> —— 关闭走上游（落终态），解决确认落本模块自己的
 *       {@code crm_service_ticket_resolution}；两者是**两个动作**、
 *       **两张表**、**两个响应字段**（{@code ticketStatus} vs {@code resolutionConfirmed}）。</li>
 *   <li><b>SLA 沿营业时间轴</b>，且<b>暂停扣的是营业时间</b> ——
 *       暂停区间里的营业时间不计入消耗，剩余秒数是**本次读取**算出来的派生态。</li>
 *   <li><b>关联单据不复制一份</b> —— 只存**关系**，
 *       订单 / 售后单的**当前事实**读取时经 trade 的授权 API 现取。</li>
 * </ol>
 *
 * <p><b>命名</b>：统一用 {@code CrmServiceTicketProcess*} 前缀，与同服务的
 * {@code CrmServiceTicket*}（15.4.1.1）与 trade 侧 {@code ServiceTicket*}
 * **零重叠** —— 契约被 codegen 合并进同一个文件时同名后者会覆盖前者且不报冲突。
 */
public final class ServiceTicketProcessModels {

  private ServiceTicketProcessModels() {}

  // ================================================================ 枚举

  /**
   * SLA 判定结果（<b>营业时间口径</b>，且<b>已扣除暂停区间</b>）。
   *
   * <p>⚠️ 与 15.4.1.1 的 {@code CrmServiceTicketSlaState} 词表一致
   * （含 {@link #NO_CALENDAR}）：「租户没配营业时间」与「配了、还在计时」
   * 是两件事，混成一个会让现场以为「这单不考核」。
   *
   * <p>🔴 本模块多一个 {@link #PAUSED}：**计时器冻着**是第四种状态 ——
   * 它既不是「还在计时」（没在走），也不是「已经超期」（冻结期间不消耗）。
   * 把它混进 PENDING，现场会以为这单还在走表；而混进 BREACHED 会冤枉客服。
   */
  public enum CrmServiceTicketProcessSlaState {
    NO_DEADLINE("无时限"),
    NO_CALENDAR("未配营业时间"),
    PENDING("计时中"),
    PAUSED("暂停中"),
    MET("已达标"),
    BREACHED("已超期");

    private final String label;

    CrmServiceTicketProcessSlaState(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 处理动作类型（与 {@code crm_service_ticket_action.action_type} 同一词表）。 */
  public enum CrmServiceTicketProcessActionType {
    REPLY("回复"),
    NOTE("内部备注"),
    STATUS_CHANGE("状态流转"),
    CLOSE("关闭工单"),
    RESOLUTION_CONFIRM("解决确认"),
    RESOLUTION_REVOKE("撤销解决确认"),
    SATISFACTION("满意度回收"),
    SLA_PAUSE("SLA 暂停"),
    SLA_RESUME("SLA 恢复"),
    LINK_ORDER("关联订单"),
    LINK_CASE("关联售后单"),
    ESCALATE("升级");

    private final String label;

    CrmServiceTicketProcessActionType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 解决确认的结果类型（与 {@code crm_service_ticket_resolution.resolution_type} 同一词表）。 */
  public enum CrmServiceTicketProcessResolutionType {
    REFUND_RECEIVED("退款已到账"),
    REPLACEMENT_SHIPPED("换货已发出"),
    RETURN_RECEIVED("退货已签收"),
    ORDER_CANCELLED("订单已取消"),
    GUIDANCE_ACCEPTED("买家接受方案"),
    OTHER("其它结果");

    private final String label;

    CrmServiceTicketProcessResolutionType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  // ================================================================ 入参

  /**
   * 回复（对买家）。
   *
   * <p>⚠️ {@code replyChannel} <b>必填</b>：没有渠道的回复事后说不清这句话发到哪儿去了。
   * ⚠️ {@code expectedVersion} 透传给上游（乐观锁）：并发处理同一张单时，
   * 后到的那个会被上游拒掉，而不是把前一个的处理覆盖掉。
   */
  public record CrmServiceTicketProcessReplyInput(
      String content,
      String replyChannel,
      @Schema(nullable = true) Long expectedVersion) {}

  /** 内部备注（写给客服看的）。 */
  public record CrmServiceTicketProcessNoteInput(
      String content, @Schema(nullable = true) Long expectedVersion) {}

  /**
   * 状态流转（待处理 → 处理中 ⇄ 待买家）。
   *
   * <p>⚠️ 终态不走这里 —— 关闭必须写原因（见 {@link CrmServiceTicketProcessCloseInput}）。
   */
  public record CrmServiceTicketProcessStatusInput(
      String targetStatus, @Schema(nullable = true) String note,
      @Schema(nullable = true) Long expectedVersion) {}

  /**
   * 关闭工单。
   *
   * <p>🔴 <b>关闭不等于解决</b>：这里只把工单落终态；
   * 「实际结果到了没有」由**另一个动作**（解决确认）回答。
   */
  public record CrmServiceTicketProcessCloseInput(
      String closeReason, @Schema(nullable = true) String closeNote,
      @Schema(nullable = true) Long expectedVersion) {}

  /**
   * 解决确认（实际结果）。
   *
   * <p>⚠️ {@code resolutionType='OTHER'} 时 {@code resultNote} 必填 ——
   * 「其它」不带说明等于没写。
   */
  public record CrmServiceTicketProcessResolutionInput(
      String resolutionType,
      @Schema(nullable = true) String resultNote,
      @Schema(nullable = true) Long expectedVersion) {}

  /** 满意度评分（1~5）。转发上游，不在本服务另存一份。 */
  public record CrmServiceTicketProcessSatisfactionInput(
      Integer score, @Schema(nullable = true) String comment) {}

  /**
   * SLA 暂停。
   *
   * <p>⚠️ 暂停**只在营业时间轴上生效**：暂停区间里的营业时间不计入 SLA 消耗。
   * 没配营业时间 ⇒ 不给暂停（没有轴就没有「暂停掉的那段营业时间」）。
   */
  public record CrmServiceTicketProcessSlaPauseInput(
      String slaTarget, String pauseReason) {}

  /** SLA 恢复（必须写理由：为什么现在恢复）。 */
  public record CrmServiceTicketProcessSlaResumeInput(String resumeReason) {}

  /**
   * 关联订单或售后单。
   *
   * <p>⚠️ 服务端会**去上游核实**：订单号 / 售后单号在上游查不到 ⇒ 拒绝关联
   * （关联一个不存在的单子，比不关联更糟）。
   */
  public record CrmServiceTicketProcessLinkInput(
      String linkType, String orderNo, @Schema(nullable = true) String caseNo,
      @Schema(nullable = true) String note) {}

  /**
   * 升级（转投诉 / 转纠纷）。
   *
   * <p>⚠️ 两类升级要带的字段**完全不同**（与上游同一约定）：
   * {@code targetType=COMPLAINT} 用售后单那组字段；
   * {@code targetType=DISPUTE} 用平台纠纷那组字段。
   */
  public record CrmServiceTicketProcessEscalateInput(
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
      @Schema(nullable = true) Long expectedVersion) {}

  /**
   * 处理队列的查询条件（真分页 + 真筛选 + 排序白名单）。
   *
   * <p>⚠️ {@code slaState} / {@code overdueOnly} / {@code pausedOnly} /
   * {@code resolutionState} 是<b>本服务</b>的判定，<b>不能</b>原样下推上游 ——
   * 上游没有「暂停」这个概念，也没有「是否已确认解决」这个概念。
   */
  public record CrmServiceTicketProcessQueueQuery(
      @Schema(nullable = true) Integer page,
      @Schema(nullable = true) Integer size,
      @Schema(nullable = true) String status,
      @Schema(nullable = true) String ticketType,
      @Schema(nullable = true) String priority,
      @Schema(nullable = true) String sourceChannel,
      @Schema(nullable = true) String assignedToRef,
      @Schema(nullable = true) UUID caseId,
      @Schema(nullable = true) String slaState,
      @Schema(nullable = true) Boolean overdueOnly,
      @Schema(nullable = true) Boolean pausedOnly,
      @Schema(nullable = true) String resolutionState,
      @Schema(nullable = true) Boolean unassignedOnly,
      @Schema(nullable = true) OffsetDateTime from,
      @Schema(nullable = true) OffsetDateTime to,
      @Schema(nullable = true) String keyword,
      @Schema(nullable = true) String sort) {}

  // ================================================================ 能力声明

  /** 能力维度：有没有、依据是什么、缺了会怎样。 */
  public record CrmServiceTicketProcessCapabilityDimension(
      String code, String label, boolean available, String basis, String ownerModule, String impact) {}

  /** 上下游模块与交付状态。 */
  public record CrmServiceTicketProcessRelatedModule(
      String moduleId, String label, boolean delivered, @Schema(nullable = true) String ownerModule,
      String note, @Schema(nullable = true) String endpoint) {}

  /** 边界（本模块刻意不做的事）。 */
  public record CrmServiceTicketProcessBoundary(String rule,
      @Schema(nullable = true) String ownerModule, String reason) {}

  /** 能力声明。 */
  public record CrmServiceTicketProcessCapabilities(
      List<CrmServiceTicketProcessCapabilityDimension> dimensions,
      List<CrmServiceTicketProcessRelatedModule> related,
      List<CrmServiceTicketProcessBoundary> boundaries,
      List<String> noteTypes,
      List<String> replyChannels,
      List<String> targetStatuses,
      List<String> closeReasons,
      List<String> resolutionTypes,
      List<String> slaTargets,
      List<String> linkTypes,
      List<String> escalationTypes,
      String closeVersusResolutionNote,
      String slaBasisNote,
      String sourceNote,
      String downstreamNote) {}

  // ================================================================ SLA

  /**
   * 一张工单的 SLA（<b>营业时间口径</b>，<b>已扣除暂停区间</b>）。
   *
   * <p>⚠️ 剩余秒数是**本次读取**算出来的派生态 —— 库里没有会自己过期的剩余列。
   * ⚠️ 自然时间口径的剩余量同时给出，<b>只作对照</b>（页面不得拿它当考核口径）。
   */
  public record CrmServiceTicketProcessSla(
      String basis,
      String basisLabel,
      @Schema(nullable = true) Integer replyHours,
      @Schema(nullable = true) Integer resolveHours,
      @Schema(nullable = true) OffsetDateTime replyDueAt,
      String replyState,
      String replyStateLabel,
      long replyRemainingSeconds,
      long replyWallClockSeconds,
      boolean replyPaused,
      @Schema(nullable = true) OffsetDateTime resolveDueAt,
      String resolveState,
      String resolveStateLabel,
      long resolveRemainingSeconds,
      long resolveWallClockSeconds,
      boolean resolvePaused,
      String scopeKey,
      @Schema(nullable = true) String timezone,
      boolean calendarConfigured,
      String note) {}

  /** 当前的 SLA 暂停状态（没有暂停时 {@code paused=false}）。 */
  public record CrmServiceTicketProcessPauseState(
      boolean paused,
      @Schema(nullable = true) String slaTarget,
      @Schema(nullable = true) OffsetDateTime pausedAt,
      @Schema(nullable = true) String pauseReason,
      long pauseCount) {}

  // ================================================================ 队列与详情

  /** 处理队列的一行。 */
  public record CrmServiceTicketProcessQueueRow(
      UUID id,
      String ticketNo,
      String ticketType,
      String ticketTypeLabel,
      String ticketStatus,
      String ticketStatusLabel,
      String priority,
      String priorityLabel,
      @Schema(nullable = true) String subject,
      @Schema(nullable = true) String assignedToRef,
      @Schema(nullable = true) String caseNo,
      OffsetDateTime createdAt,
      CrmServiceTicketProcessSla sla,
      CrmServiceTicketProcessPauseState pause,
      boolean closed,
      boolean resolutionConfirmed) {}

  /** 处理队列汇总（服务端真算，页面不做算术）。 */
  public record CrmServiceTicketProcessQueueSummary(
      long total,
      long open,
      long inProgress,
      long waitingBuyer,
      long closed,
      long paused,
      long overdue,
      long resolutionConfirmed) {}

  /** 处理队列的一页（真分页）。 */
  public record CrmServiceTicketProcessQueuePage(
      List<CrmServiceTicketProcessQueueRow> rows,
      int page,
      int size,
      long total,
      CrmServiceTicketProcessQueueSummary summary,
      OffsetDateTime asOf,
      String paginationNote,
      String slaNote,
      String sourceNote,
      String boundaryNote) {}

  /**
   * 单张工单的处理视图。
   *
   * <p>🔴 {@code ticketStatus} 与 {@code resolutionConfirmed} 是**两个字段** ——
   * 这正是「关闭不等于解决」的落点：工单可以已关闭而 {@code resolutionConfirmed=false}。
   */
  public record CrmServiceTicketProcessTicket(
      UUID id,
      String ticketNo,
      String ticketType,
      String ticketTypeLabel,
      String ticketStatus,
      String ticketStatusLabel,
      String priority,
      String priorityLabel,
      @Schema(nullable = true) String subject,
      @Schema(nullable = true) String reasonNote,
      @Schema(nullable = true) String assignedToRef,
      @Schema(nullable = true) UUID assignedToUserId,
      String sourceChannel,
      String sourceChannelLabel,
      @Schema(nullable = true) String caseNo,
      OffsetDateTime createdAt,
      @Schema(nullable = true) OffsetDateTime firstReplyAt,
      @Schema(nullable = true) OffsetDateTime closedAt,
      @Schema(nullable = true) String closeReason,
      @Schema(nullable = true) String closeNote,
      long version,
      CrmServiceTicketProcessSla sla,
      CrmServiceTicketProcessPauseState pause,
      boolean closed,
      boolean resolutionConfirmed,
      List<String> confirmedResolutionTypes,
      List<String> availableActions,
      long noteCount,
      long actionCount,
      long linkCount,
      long resolutionCount,
      String closeVersusResolutionNote,
      String note) {}

  // ================================================================ 处理记录

  /** 一条备注 / 回复。 */
  public record CrmServiceTicketProcessNote(
      UUID id,
      UUID ticketId,
      String noteType,
      String noteTypeLabel,
      String content,
      @Schema(nullable = true) String replyChannel,
      @Schema(nullable = true) String replyChannelLabel,
      UUID createdBy,
      OffsetDateTime createdAt) {}

  /** 备注 / 回复的一页（真分页）。 */
  public record CrmServiceTicketProcessNotePage(
      List<CrmServiceTicketProcessNote> rows, int page, int size, long total, String note) {}

  /** 一条处理记录（append-only 动作时间线的一行）。 */
  public record CrmServiceTicketProcessAction(
      UUID id,
      UUID ticketId,
      String actionType,
      String actionTypeLabel,
      @Schema(nullable = true) String fromStatus,
      @Schema(nullable = true) String toStatus,
      @Schema(nullable = true) String detail,
      @Schema(nullable = true) String relatedOrderNo,
      @Schema(nullable = true) String relatedCaseNo,
      @Schema(nullable = true) String resolutionType,
      UUID actorId,
      OffsetDateTime occurredAt) {}

  /** 处理记录的一页（真分页）。 */
  public record CrmServiceTicketProcessActionPage(
      List<CrmServiceTicketProcessAction> rows, int page, int size, long total, String note) {}

  // ================================================================ 关联单据

  /**
   * 一条关联单据（订单 / 售后单）。
   *
   * <p>⚠️ 本表只存**关系**；{@code orderStatus} / {@code caseStatus} 等是
   * **本次读取**从上游 trade 现取的事实，<b>不在本服务复制一份</b>。
   * 上游读不到时 {@code factsNote} 写明原因，<b>不显示空白</b>。
   */
  public record CrmServiceTicketProcessLink(
      UUID id,
      UUID ticketId,
      String linkType,
      String linkTypeLabel,
      String linkSource,
      String linkSourceLabel,
      @Schema(nullable = true) UUID orderId,
      @Schema(nullable = true) String orderNo,
      @Schema(nullable = true) UUID caseId,
      @Schema(nullable = true) String caseNo,
      @Schema(nullable = true) String orderStatus,
      @Schema(nullable = true) String orderPaymentStatus,
      @Schema(nullable = true) String orderFulfillmentStatus,
      @Schema(nullable = true) String orderAftersalesStatus,
      @Schema(nullable = true) String orderCurrency,
      @Schema(nullable = true) BigDecimal orderAmount,
      @Schema(nullable = true) String caseStatus,
      @Schema(nullable = true) String caseType,
      @Schema(nullable = true) String orderNoUpstream,
      @Schema(nullable = true) String factsNote,
      UUID linkedBy,
      OffsetDateTime linkedAt,
      @Schema(nullable = true) String lastSyncNote,
      @Schema(nullable = true) OffsetDateTime lastSyncedAt) {}

  /** 关联单据的一页。 */
  public record CrmServiceTicketProcessLinkPage(
      List<CrmServiceTicketProcessLink> rows, int page, int size, long total, String note) {}

  // ================================================================ 解决确认

  /** 一条解决确认。 */
  public record CrmServiceTicketProcessResolution(
      UUID id,
      UUID ticketId,
      String resolutionType,
      String resolutionTypeLabel,
      @Schema(nullable = true) String resultNote,
      @Schema(nullable = true) UUID linkedCaseId,
      @Schema(nullable = true) String linkedOrderNo,
      UUID confirmedBy,
      OffsetDateTime confirmedAt,
      @Schema(nullable = true) OffsetDateTime revokedAt,
      @Schema(nullable = true) UUID revokedBy,
      boolean active) {}

  /** 解决确认的一页。 */
  public record CrmServiceTicketProcessResolutionPage(
      List<CrmServiceTicketProcessResolution> rows, int page, int size, long total, String note) {}

  // ================================================================ 动作结果

  /**
   * 一次处理动作的结果。
   *
   * <p>⚠️ **一个响应类型覆盖全部写动作**：前端拿到的结构永远一样，
   * 不用按动作类型分支解析。
   * ⚠️ {@code ticketStatus} 与 {@code resolutionConfirmed} 各自独立 ——
   * 关闭动作后 {@code ticketStatus} 变终态而 {@code resolutionConfirmed} 仍为 false，
   * 这就是「关闭 ≠ 解决」在响应里的样子。
   */
  public record CrmServiceTicketProcessOutcome(
      String actionType,
      String actionTypeLabel,
      String message,
      @Schema(nullable = true) String ticketStatus,
      @Schema(nullable = true) String ticketStatusLabel,
      boolean closed,
      boolean resolutionConfirmed,
      @Schema(nullable = true) CrmServiceTicketProcessNote note,
      @Schema(nullable = true) CrmServiceTicketProcessAction action,
      @Schema(nullable = true) CrmServiceTicketProcessResolution resolution,
      @Schema(nullable = true) CrmServiceTicketProcessLink link,
      @Schema(nullable = true) CrmServiceTicketProcessPauseState pause,
      @Schema(nullable = true) CrmServiceTicketProcessSla sla,
      @Schema(nullable = true) String escalatedCaseNo,
      @Schema(nullable = true) String escalatedDisputeNo,
      String noteText) {}
}
