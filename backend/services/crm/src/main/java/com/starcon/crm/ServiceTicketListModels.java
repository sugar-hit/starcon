package com.starcon.crm;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 15.4.1.1 工单列表的传输模型。
 *
 * <p>蓝图 15-crm-and-service.md 原文：
 *
 * <blockquote>
 * 职责：客服工单列表：类型、优先级、状态、SLA 剩余、关联买家与订单、分配客服、转派与升级。
 * 输入：工单创建（自动/人工）；筛选条件。
 * 输出：工单列表 + SLA 预警。
 * 关系：上游 = 15.1.1.3、11.4.2.1；下游 = 15.4.1.2 处理。
 * <b>SLA 计时需按营业时间而非自然时间</b>，否则夜间积压会造成误判。
 * </blockquote>
 *
 * <p><b>本文件最重要的四件事</b>：
 *
 * <ol>
 *   <li><b>工单事实不在本服务</b> —— 工单由 11.4.2.1（trade 服务，V270）拥有，
 *       本模块只经授权 HTTP API <b>只读</b>，不建跨库 FK / 共享视图 / 直连对方库
 *       （AGENTS.md 模块边界纪律）。</li>
 *   <li><b>SLA 剩余是读取时刻的派生态</b> —— 库里<b>没有</b>会自己过期的
 *       「剩余小时数」列；{@link CrmServiceTicketSla} 里的剩余秒数是
 *       <b>本次读取</b>按<b>营业时间轴</b>算出来的。</li>
 *   <li><b>营业时间口径是租户自己的配置</b> —— 读本服务自己的
 *       {@code crm_service_business_hours} / {@code crm_service_business_holiday}，
 *       <b>不写死常量</b>。</li>
 *   <li><b>关联买家与订单不复制一份</b> —— 订单事实来自 trade 的授权 API，
 *       买家事实来自本服务自己的 {@code crm_message} / {@code crm_customer_contact}。</li>
 * </ol>
 *
 * <p><b>命名</b>：统一用 {@code CrmServiceTicket*} 前缀，与 trade 侧
 * {@code ServiceTicket*} 零重叠 —— 契约被 codegen 合并进同一个文件时
 * 同名后者会覆盖前者且不报冲突。
 */
public final class ServiceTicketListModels {

  private ServiceTicketListModels() {}

  // ================================================================ 枚举

  /**
   * SLA 判定结果（<b>营业时间口径</b>，本服务现算）。
   *
   * <p>⚠️ 与 trade 侧 {@code ServiceTicketSlaState} 词表**刻意不同**：
   * 多了一个 {@link #NO_CALENDAR} —— 「租户没配营业时间口径」与
   * 「配了、还没到期」是两件事：前者算不出来，后者是还在计时。
   * 把两者混成一个 NO_DEADLINE，现场会以为「这单没有时限」，
   * 而真相是「我们还没拿到这家店的营业时间」。
   */
  public enum CrmServiceTicketSlaState {
    NO_DEADLINE("无时限"),
    NO_CALENDAR("未配营业时间"),
    PENDING("计时中"),
    MET("已达标"),
    BREACHED("已超期");

    private final String label;

    CrmServiceTicketSlaState(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  // ================================================================ 入参

  /**
   * 列表查询（真分页 + 多维筛选 + 排序白名单）。
   *
   * <p>⚠️ {@code slaState} / {@code overdueOnly} 是<b>本服务</b>的判定
   * （营业时间口径），<b>不能</b>原样下推给上游 ——
   * 上游的同名筛选走的是<b>自然时间</b>口径，两者对同一批单会给出不同的答案。
   */
  public record CrmServiceTicketListQuery(
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
      @Schema(nullable = true) Boolean unassignedOnly,
      @Schema(nullable = true) OffsetDateTime from,
      @Schema(nullable = true) OffsetDateTime to,
      @Schema(nullable = true) String keyword,
      @Schema(nullable = true) String sort,
      @Schema(nullable = true) Boolean enrich,
      @Schema(nullable = true) UUID shopId) {}

  /** 营业时段的一段（ISO-8601 工作日：1 = 周一 … 7 = 周日）。 */
  public record CrmBusinessHourWindow(
      int weekday, LocalTime startTime, LocalTime endTime, boolean enabled) {}

  /** 节假日例外（true = 整日关闭；false = 照常营业的调休上班日）。 */
  public record CrmBusinessHoliday(LocalDate date, String label, boolean closed) {}

  /**
   * 改写某个作用域的营业时段（整段替换）。
   *
   * <p>⚠️ {@code shopId} 为空 ⇒ 改的是<b>租户默认</b>作用域；
   * 带 shopId ⇒ 改的是那一个店铺的作用域（两者互不覆盖）。
   */
  public record CrmBusinessHoursInput(
      @Schema(nullable = true) UUID shopId,
      String timezone,
      List<CrmBusinessHourWindow> windows,
      String note) {}

  /** 改写某个作用域的节假日例外（整段替换）。 */
  public record CrmBusinessHolidayInput(
      @Schema(nullable = true) UUID shopId, List<CrmBusinessHoliday> holidays, String note) {}

  // ================================================================ 出参

  /**
   * 关联买家。
   *
   * <p>⚠️ <b>买家事实来自本服务自己的表</b>（{@code crm_message.buyer_reference} /
   * {@code crm_customer_contact.customer_ref}），<b>不复制一份</b>；
   * 买家名与买家档案 id 则来自上游订单（trade 侧事实）。
   *
   * <p>⚠️ 本仓<b>没有买家档案</b>（15.3.1.1 客户列表未交付），
   * 所以 {@code buyerReference} 只是<b>逻辑引用</b>，不是主数据。
   */
  public record CrmServiceTicketBuyer(
      @Schema(nullable = true) String buyerReference,
      @Schema(nullable = true) String buyerName,
      @Schema(nullable = true) UUID customerPartyId,
      @Schema(nullable = true) UUID shopId,
      @Schema(nullable = true) String shopName,
      @Schema(nullable = true) String platformCode,
      @Schema(nullable = true) String contactRef,
      @Schema(nullable = true) String contactName,
      boolean available,
      String note) {}

  /**
   * 关联订单（<b>上游 trade 的事实</b>，本服务只读不复制）。
   *
   * <p>⚠️ 订单号的唯一来源是「工单 → 售后单 → 订单」这条链（工单挂在售后单上，
   * 售后单挂在订单上）。所以订单上下文<b>可能读不到</b>（上游读不到 / 超时），
   * 这时 {@code available=false} 且 {@code note} 写明原因 ——
   * <b>不显示空白</b>：空白会被客服读成「这一单没有信息」，而不是「我们没查到」。
   */
  public record CrmServiceTicketOrder(
      @Schema(nullable = true) String orderNo,
      @Schema(nullable = true) String externalOrderNo,
      @Schema(nullable = true) String orderSource,
      @Schema(nullable = true) String documentStatus,
      @Schema(nullable = true) String currency,
      @Schema(nullable = true) BigDecimal grandTotalAmount,
      @Schema(nullable = true) String platformStatus,
      @Schema(nullable = true) String paymentStatus,
      @Schema(nullable = true) String fulfillmentStatus,
      @Schema(nullable = true) String aftersalesStatus,
      @Schema(nullable = true) String caseNo,
      boolean available,
      String note) {}

  /**
   * 转派与升级（<b>读上游 API 的既有字段</b>，不另造一套转派模型）。
   *
   * <p>⚠️ {@code transferCount} / {@code lastTransferAt} 等来自上游的
   * <b>转派历史</b>（{@code aftersales_ticket_assignment}，append-only）；
   * 上游读不到时 {@code historyAvailable=false} 且 {@code note} 写明原因，
   * <b>不编一个 0</b> —— 「没有转派过」与「没查到转派记录」是两件事。
   */
  public record CrmServiceTicketTransfer(
      String currentOwnerRef,
      @Schema(nullable = true) UUID currentOwnerUserId,
      @Schema(nullable = true) OffsetDateTime assignedAt,
      int assignmentCount,
      int transferCount,
      @Schema(nullable = true) OffsetDateTime lastTransferAt,
      @Schema(nullable = true) String lastFromOwnerRef,
      @Schema(nullable = true) String lastToOwnerRef,
      @Schema(nullable = true) String lastReasonNote,
      @Schema(nullable = true) String escalatedCaseNo,
      @Schema(nullable = true) String escalatedDisputeNo,
      boolean historyAvailable,
      String note) {}

  /**
   * SLA（<b>营业时间口径</b>）。
   *
   * <p>⚠️ {@code replyBusinessDueAt} 是<b>在营业时间轴上累加</b>算出的到期时刻，
   * <b>不是</b> {@code createdAt + N 小时}；{@code upstreamReplyDueAt} 是上游的
   * <b>自然时间</b>时限，<b>只作对照</b>（两者不等是正常的：夜间积压时
   * 自然时间会立刻逾期，而营业时间口径不会）。
   *
   * <p>⚠️ 剩余秒数<b>负数</b>表示已超期多少秒（营业时间口径）。
   */
  public record CrmServiceTicketSla(
      String basis,
      String basisLabel,
      @Schema(nullable = true) Integer replyHours,
      @Schema(nullable = true) Integer resolveHours,
      @Schema(nullable = true) OffsetDateTime replyDueAt,
      String replyState,
      String replyStateLabel,
      long replyBusinessSecondsRemaining,
      long replyWallClockSecondsRemaining,
      @Schema(nullable = true) OffsetDateTime resolveDueAt,
      String resolveState,
      String resolveStateLabel,
      long resolveBusinessSecondsRemaining,
      long resolveWallClockSecondsRemaining,
      @Schema(nullable = true) OffsetDateTime upstreamReplyDueAt,
      @Schema(nullable = true) OffsetDateTime upstreamResolveDueAt,
      String scopeKey,
      @Schema(nullable = true) String timezone,
      boolean calendarConfigured,
      String note) {}

  /** 工单行（列表用）。 */
  public record CrmServiceTicketRow(
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
      @Schema(nullable = true) String caseNo,
      @Schema(nullable = true) String caseStatus,
      @Schema(nullable = true) String caseType,
      @Schema(nullable = true) String platformScope,
      @Schema(nullable = true) Integer satisfactionRating,
      @Schema(nullable = true) OffsetDateTime firstReplyAt,
      @Schema(nullable = true) OffsetDateTime waitingBuyerAt,
      @Schema(nullable = true) OffsetDateTime closedAt,
      @Schema(nullable = true) String closeReason,
      @Schema(nullable = true) String closeNote,
      CrmServiceTicketBuyer buyer,
      CrmServiceTicketOrder order,
      CrmServiceTicketTransfer transfer,
      CrmServiceTicketSla sla,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt,
      long version) {}

  /**
   * 汇总（<b>与分页无关</b>：口径与当前筛选一致，不随翻页变小）。
   *
   * <p>⚠️ {@code overdue} 用的是<b>营业时间口径</b>（回复时限已过且未首次回复）。
   *
   * @param scopeNote 这几个计数的<b>口径说明</b>（全量口径 / 页面级口径）——
   *     「逾期数是全量还是本页」不写清楚，数字就会被当成全量来用。
   */
  public record CrmServiceTicketSummary(
      long total,
      long open,
      long inProgress,
      long waitingBuyer,
      long resolved,
      long cancelled,
      long unassigned,
      long overdue,
      long complaint,
      long inquiry,
      long noCalendar,
      String scopeNote) {}

  /** 列表页。 */
  public record CrmServiceTicketPage(
      List<CrmServiceTicketRow> rows,
      int page,
      int size,
      long total,
      boolean totalExact,
      long scannedRows,
      boolean scanTruncated,
      CrmServiceTicketSummary summary,
      OffsetDateTime evaluatedAt,
      String paginationNote,
      String slaNote,
      String sourceNote,
      String enrichmentNote) {}

  // ================================================================ 营业时间口径

  /**
   * 营业时间口径（一个作用域的完整口径）。
   *
   * <p>⚠️ {@code configured=false} 表示<b>这个作用域还没配过</b>：
   * 此时 SLA <b>算不出来</b>（状态 = {@code NO_CALENDAR}），
   * 而<b>不是</b>退回自然时间 —— 退回自然时间正是蓝图加粗句要避免的误判。
   */
  public record CrmBusinessHoursConfig(
      String scopeKey,
      String scopeType,
      @Schema(nullable = true) UUID shopId,
      @Schema(nullable = true) String timezone,
      boolean configured,
      List<CrmBusinessHourWindow> windows,
      List<CrmBusinessHoliday> holidays,
      @Schema(nullable = true) UUID updatedBy,
      @Schema(nullable = true) OffsetDateTime updatedAt,
      @Schema(nullable = true) UUID auditId,
      String note) {}

  // ================================================================ 能力声明

  /** 能力与边界声明（一个块 = 职责句的一个动词）。 */
  public record CrmServiceTicketCapabilityDimension(
      String dimension, String description, boolean available, String source,
      @Schema(nullable = true) String ownerModule, String impact) {}

  /** 上下游模块的交付状态。 */
  public record CrmServiceTicketRelatedModule(
      String id, String label, boolean delivered, @Schema(nullable = true) String ownerModule,
      String reason, @Schema(nullable = true) String href) {}

  /** 边界声明：本模块刻意不做的事。 */
  public record CrmServiceTicketBoundary(
      String boundary, @Schema(nullable = true) String ownerModule, String reason) {}

  /** 能力声明整体。 */
  public record CrmServiceTicketCapabilities(
      List<CrmServiceTicketCapabilityDimension> dimensions,
      List<CrmServiceTicketRelatedModule> related,
      List<CrmServiceTicketBoundary> boundaries,
      List<String> ticketTypes,
      List<String> ticketStatuses,
      List<String> priorities,
      List<String> sourceChannels,
      List<String> slaStates,
      List<String> sorts,
      String slaBasisNote,
      String upstreamNote,
      String downstreamNote) {}
}
