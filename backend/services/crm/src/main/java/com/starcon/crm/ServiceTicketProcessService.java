package com.starcon.crm;

import static com.starcon.crm.ServiceTicketProcessModels.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 15.4.1.2 工单处理。
 *
 * <p>蓝图 15-crm-and-service.md：
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
 * <p><b>六件事必须说清</b>：
 *
 * <ol>
 *   <li><b>工单事实不在本服务</b> —— 工单由 11.4.2.1（trade 服务）拥有。
 *       回复 / 状态流转 / 关闭 / 满意度 / 升级全部经
 *       {@link ServiceTicketProcessPort} <b>转发</b>它的授权 HTTP API，
 *       <b>一个字都不写对方的表</b>（AGENTS.md 模块边界纪律）。</li>
 *   <li><b>关闭 ≠ 解决</b> —— 关闭走上游（落终态）；
 *       <b>解决确认</b>落本模块自己的 {@code crm_service_ticket_resolution}，
 *       且**要求工单已关闭**（先关后确认，顺序本身就是纪律的落点）。</li>
 *   <li><b>SLA 沿营业时间轴，且暂停扣的是营业时间</b> ——
 *       口径读本服务自己的 {@code crm_service_business_hours} /
 *       {@code crm_service_business_holiday}（15.4.1.1 建的，同库同服务）；
 *       暂停区间里的营业时间不计入消耗（{@link ServiceTicketProcessSlaAxis}）；
 *       没配营业时间 ⇒ 一律 NO_CALENDAR，<b>不退回自然时间</b>。</li>
 *   <li><b>满意度不另存</b> —— 上游已有这个事实，只转发（避免两个事实源）。</li>
 *   <li><b>关联单据不复制一份</b> —— 本服务只存**关系**，
 *       订单 / 售后单的当前事实读取时经上游授权 API 现取。</li>
 *   <li><b>处理记录只追加</b> —— 每次成功动作在
 *       {@code crm_service_ticket_action} 留一行（DB 触发器拒绝改删）。</li>
 * </ol>
 *
 * <p><b>处理记录只记成功的动作</b>：失败的动作会随事务回滚一起消失 ——
 * 那才是真相（「写了但没生效」留在时间线里比不写更糟）。
 */
@Service
public class ServiceTicketProcessService {

  /** 列表分页上限（真分页的上限，防止一次拉爆）。 */
  static final int MAX_PAGE_SIZE = 50;

  /** 默认页长。 */
  static final int DEFAULT_PAGE_SIZE = 20;

  /** 租户默认营业时间作用域（工单事实里没有店铺 id ⇒ 口径只能取租户默认）。 */
  static final String DEFAULT_SCOPE = "DEFAULT";

  /** 正文长度上限（太长的备注在工单列表里根本读不完）。 */
  static final int MAX_CONTENT_LENGTH = 4000;

  private static final List<String> NOTE_TYPES = List.of("NOTE", "REPLY");
  private static final List<String> REPLY_CHANNELS =
      List.of("PLATFORM_MESSAGE", "EMAIL", "CHAT", "PHONE", "OTHER");
  private static final List<String> TARGET_STATUSES =
      List.of("OPEN", "IN_PROGRESS", "WAITING_BUYER");
  private static final List<String> CLOSE_REASONS =
      List.of("RESOLVED", "NO_RESPONSE", "DUPLICATE", "CANCELLED", "ESCALATED", "ABANDONED");
  private static final List<String> RESOLUTION_TYPES =
      List.of(
          "REFUND_RECEIVED",
          "REPLACEMENT_SHIPPED",
          "RETURN_RECEIVED",
          "ORDER_CANCELLED",
          "GUIDANCE_ACCEPTED",
          "OTHER");
  private static final List<String> SLA_TARGETS = List.of("REPLY", "RESOLVE", "BOTH");
  private static final List<String> LINK_TYPES = List.of("ORDER", "AFTER_SALES_CASE");
  private static final List<String> ESCALATION_TYPES = List.of("COMPLAINT", "DISPUTE");
  private static final List<String> SORTS = List.of("sla_asc", "created_desc", "priority_desc");

  /** 工单的终态（关闭之后不再流转）。 */
  private static final Set<String> TERMINAL_STATUSES = Set.of("RESOLVED", "CANCELLED");

  private final DSLContext sql;
  private final ServiceTicketFactsPort facts;
  private final ServiceTicketProcessPort writes;

  public ServiceTicketProcessService(
      DSLContext sql, ServiceTicketFactsPort facts, ServiceTicketProcessPort writes) {
    this.sql = sql;
    this.facts = facts;
    this.writes = writes;
  }

  // ================================================================ 能力声明

  /**
   * 能力声明：前端在渲染前就知道哪些能力有、哪些没有，而不是靠试。
   *
   * <p>🔴 如实报出上游与下游的边界：本模块**写**工单动作全部转发 trade，
   * **不建工单表**；**关联单据的上游改写端点不存在** ⇒ 如实标
   * {@code available=false} + ownerModule，<b>不指向</b>任何不存在的入口。
   */
  public CrmServiceTicketProcessCapabilities capabilities() {
    List<CrmServiceTicketProcessCapabilityDimension> dimensions =
        List.of(
            new CrmServiceTicketProcessCapabilityDimension(
                "reply",
                "回复（对买家的回复 + 回复时限事实）",
                true,
                "转发上游 trade 11.4.2.1 的 POST /trade/service-tickets/{id}/first-reply"
                    + "（**回复时限的完成时刻只在上游记一次**）+ 本服务自己的"
                    + " crm_service_ticket_note（回复正文只追加）",
                "",
                "实际投递到买家通道由 15.1.2.1 消息发送负责；"
                    + "本模块负责**工单侧的回复记录**与**回复时限事实**（与上游同一口径）"),
            new CrmServiceTicketProcessCapabilityDimension(
                "note",
                "内部备注",
                true,
                "本服务自己的 crm_service_ticket_note（append-only，DB 触发器拒绝改删）",
                "",
                "改掉一条旧备注，「当时跟买家说了什么」就再也复核不了了 ⇒ 只追加"),
            new CrmServiceTicketProcessCapabilityDimension(
                "status_change",
                "状态流转（待处理 → 处理中 ⇄ 待买家）",
                true,
                "转发上游 trade 11.4.2.1 的 POST /trade/service-tickets/{id}/status",
                "11.4.2.1",
                "终态不走这里 —— 关闭必须写原因（关闭 ≠ 解决）"),
            new CrmServiceTicketProcessCapabilityDimension(
                "close",
                "关闭工单（落终态）",
                true,
                "转发上游 trade 11.4.2.1 的 POST /trade/service-tickets/{id}/close",
                "11.4.2.1",
                "🔴 **关闭不等于解决**：关闭只表示「不再跟了」，"
                    + "「实际结果到了没有」由**解决确认**回答（另一张表、另一个动作）"),
            new CrmServiceTicketProcessCapabilityDimension(
                "resolution_confirm",
                "🔴 解决确认（退款到账 / 换货发出 / 买家接受方案）",
                true,
                "本服务自己的 crm_service_ticket_resolution（V309）",
                "",
                "蓝图加粗句「工单关闭不等于问题解决」的落点："
                    + "关闭落终态（上游），解决确认落本模块；两者**不能合并成一个 status** —— "
                    + "合并之后「关掉了但退款没到」与「退款已到」在库里长得一模一样。"
                    + "确认**要求工单已关闭**（先关后确认）"),
            new CrmServiceTicketProcessCapabilityDimension(
                "satisfaction",
                "满意度回收（1~5 分）",
                true,
                "转发上游 trade 11.4.2.1 的 POST /trade/service-tickets/{id}/satisfaction",
                "11.4.2.1",
                "🔴 **不另存一份满意度表**：同一件事两个事实源迟早打架 ⇒ 只转发。"
                    + "上游只对**已关闭**的工单开放"),
            new CrmServiceTicketProcessCapabilityDimension(
                "sla_pause_resume",
                "🔴 SLA 暂停 / 恢复（沿营业时间轴）",
                true,
                "本服务自己的 crm_service_ticket_sla_pause（V309）+ "
                    + "BusinessHoursCalendar（15.4.1.1 的营业时间轴）",
                "",
                "暂停**必须作用在营业时间轴上**：暂停区间里的营业时间不计入 SLA 消耗。"
                    + "不是 createdAt + N 小时。没配营业时间 ⇒ 不给暂停（没有轴就没有"
                    + "「暂停掉的那段营业时间」），SLA 一律 NO_CALENDAR，**不退回自然时间**"),
            new CrmServiceTicketProcessCapabilityDimension(
                "related_documents",
                "关联订单与售后单",
                true,
                "本服务自己的 crm_service_ticket_link（只存**关系**）+ "
                    + "上游 trade 的订单 / 售后单事实（GET /trade/sales-orders/list、"
                    + "GET /trade/aftersales/cases）",
                "9.1.1.1",
                "关联**必须去上游核实**：查不到的订单号 / 售后单号一律拒绝关联 —— "
                    + "关联一个不存在的单子，比不关联更糟"),
            new CrmServiceTicketProcessCapabilityDimension(
                "linked_document_push",
                "把处理结果**推给**关联的售后单 / 订单",
                false,
                "上游 trade **没有**「改写已有售后单 / 订单」的端点"
                    + "（GET /trade/aftersales/cases 只有读与新建）",
                "11.1.1.1",
                "🔴 本模块**不假装推给了上游**：关联单据的更新只落在本模块自己的"
                    + " crm_service_ticket_link.last_sync_note / last_synced_at 上，"
                    + "并在响应里写明这一点"),
            new CrmServiceTicketProcessCapabilityDimension(
                "escalate",
                "升级（转投诉 / 转纠纷 ⇒ 下游 11 售后）",
                true,
                "转发上游 trade 11.4.2.1 的 POST /trade/service-tickets/{id}/escalate",
                "11.4.2.1",
                "升级物**真的**落在 trade 库里（那边的唯一写入所有者干的活）；"
                    + "本模块只把**关系**记在自己的关联单据表里"),
            new CrmServiceTicketProcessCapabilityDimension(
                "action_log",
                "处理记录（动作时间线）",
                true,
                "本服务自己的 crm_service_ticket_action（append-only，DB 触发器拒绝改删）",
                "",
                "蓝图输出里的「处理记录」：每一次成功动作留一行（谁 / 什么时候 / 做了什么）。"
                    + "失败的动作随事务回滚消失 —— 「写了但没生效」留在时间线里比不写更糟"),
            new CrmServiceTicketProcessCapabilityDimension(
                "ticket_write",
                "工单表本身的写入",
                false,
                "工单事实由 11.4.2.1（trade 服务）拥有，本模块**只转发**",
                "11.4.2.1",
                "本服务**不建工单表**、不建跨库外键、不建共享视图、不直连对方库"));
    List<CrmServiceTicketProcessRelatedModule> related =
        List.of(
            new CrmServiceTicketProcessRelatedModule(
                "15.4.1.1", "工单列表（crm 服务）", true, null,
                "已交付（V306）：本模块的上游；营业时间口径由它建立，"
                    + "本模块的 SLA 暂停 / 恢复作用在**同一条轴**上", null),
            new CrmServiceTicketProcessRelatedModule(
                "11.4.2.1", "工单管理（trade 服务）", true, null,
                "已交付：工单事实的**唯一所有者**，本模块经授权 HTTP API 转发写动作",
                null),
            new CrmServiceTicketProcessRelatedModule(
                "11.1.1.1", "售后单（trade 服务）", true, null,
                "已交付：关联售后单的事实来源；⚠️ 只有读与新建端点，"
                    + "**没有**改写已有售后单的端点", null),
            new CrmServiceTicketProcessRelatedModule(
                "9.1.1.1", "全渠道统一订单列表（trade 服务）", true, null,
                "已交付：关联订单的事实来源；⚠️ 没有改写已有订单的端点", null),
            new CrmServiceTicketProcessRelatedModule(
                "15.1.2.1", "消息发送（crm 服务）", true, null,
                "已交付：实际把回复投递到买家通道由它负责；"
                    + "本模块负责**工单侧的回复记录**与**回复时限事实**", null),
            new CrmServiceTicketProcessRelatedModule(
                "15.4.1.2", "工单处理（本模块）", true, null,
                "已交付：回复 / 备注 / 状态流转 / 关联单据 / 解决确认 / 满意度回收 / "
                    + "SLA 暂停恢复", null));
    List<CrmServiceTicketProcessBoundary> boundaries =
        List.of(
            new CrmServiceTicketProcessBoundary(
                "不建工单表、不建跨库外键、不建共享视图、不直连 trade 库",
                null,
                "AGENTS.md 模块边界纪律：跨服务只经授权 API / event"),
            new CrmServiceTicketProcessBoundary(
                "关闭与解决确认是**两个动作**，不能合并成一个 status",
                null,
                "蓝图加粗句「工单关闭不等于问题解决，需确认实际结果才计为解决」"),
            new CrmServiceTicketProcessBoundary(
                "SLA 不退回自然时间；没配营业时间一律 NO_CALENDAR",
                null,
                "悄悄退回等于把「夜间积压被判逾期」的误判请回来，而且现场看不出来"),
            new CrmServiceTicketProcessBoundary(
                "不另存满意度；回复时限的完成时刻只在上游记一次",
                "11.4.2.1",
                "同一件事两个事实源迟早打架"),
            new CrmServiceTicketProcessBoundary(
                "库里不存会自己过期的「剩余小时数」",
                null,
                "剩余秒数是读取时刻的派生态（库里那一列写下去就开始自己过期）"),
            new CrmServiceTicketProcessBoundary(
                "不假装把处理结果推给了上游的售后单 / 订单",
                "11.1.1.1",
                "上游没有改写已有售后单 / 订单的端点"));
    return new CrmServiceTicketProcessCapabilities(
        dimensions,
        related,
        boundaries,
        NOTE_TYPES,
        REPLY_CHANNELS,
        TARGET_STATUSES,
        CLOSE_REASONS,
        RESOLUTION_TYPES,
        SLA_TARGETS,
        LINK_TYPES,
        ESCALATION_TYPES,
        "🔴 **关闭 ≠ 解决**：关闭（close）把工单落终态，表示「不再跟了」；"
            + "解决确认（resolution）记录**实际结果**（退款到账 / 换货发出 / 买家接受方案），"
            + "**必须先关闭才能确认**。所以一张工单可以「已关闭但没解决」—— "
            + "那正是加粗句要留住的可见性。",
        "SLA 时限来源沿用上游口径（PLATFORM_GIVEN / POLICY_SLA）；"
            + "本模块只把**计时**换成营业时间轴，并让**暂停 / 恢复**作用在同一条轴上。"
            + "🔴 工单事实里**没有店铺 id** ⇒ 口径一律取**租户默认作用域**（DEFAULT）。",
        "工单事实来自上游 trade 11.4.2.1（经授权 HTTP API，转发调用方自己的 bearer，"
            + "租户由上游按已认证成员关系解析并强制 RLS）；本服务**不建工单表**。",
        "下游 11 售后（升级）经上游 escalate 端点**真的**建出售后单 / 平台纠纷；"
            + "下游 9 订单的**当前事实**经上游只读端点取。⚠️ 上游**没有**改写已有"
            + "售后单 / 订单的端点 ⇒ 本模块不假装推给了上游。");
  }

  // ================================================================ 处理队列

  /**
   * 处理队列（真分页 + 真筛选 + <b>暂停感知</b>的 SLA + 解决确认状态）。
   *
   * <p>⚠️ 与 15.4.1.1 的列表**刻意不同**：那边是「有哪些工单」，
   * 这边是「<b>哪些工单还在等我处理</b>」——
   * 额外给出 SLA 暂停状态、是否已确认解决（关闭 ≠ 解决）。
   */
  public CrmServiceTicketProcessQueuePage queue(
      CrmServiceTicketProcessQueueQuery query, String bearer) {
    OffsetDateTime at = OffsetDateTime.now();
    int page = Math.max(query.page() == null ? 0 : query.page(), 0);
    int size =
        query.size() == null ? DEFAULT_PAGE_SIZE : Math.min(Math.max(query.size(), 1), MAX_PAGE_SIZE);
    String sort = normalizeSort(query.sort());

    ServiceTicketFactsPort.TicketPage upstream =
        facts.tickets(
            query.status(),
            query.ticketType(),
            query.priority(),
            query.sourceChannel(),
            query.assignedToRef(),
            query.caseId(),
            query.unassignedOnly(),
            query.from(),
            query.to(),
            query.keyword(),
            null,
            page,
            size,
            bearer);

    List<UUID> ticketIds = new ArrayList<>();
    for (ServiceTicketFactsPort.TicketView row : upstream.rows()) {
      ticketIds.add(row.id());
    }
    Map<UUID, TicketState> states = loadStates(ticketIds);
    ServiceTicketProcessSlaAxis axis = axis();

    List<CrmServiceTicketProcessQueueRow> rows = new ArrayList<>();
    for (ServiceTicketFactsPort.TicketView ticket : upstream.rows()) {
      TicketState state = states.getOrDefault(ticket.id(), TicketState.EMPTY);
      CrmServiceTicketProcessSla sla = computeSla(ticket, axisFor(axis, state), at);
      boolean closed = isClosed(ticket.ticketStatus());
      boolean confirmed = state.resolutions.stream().anyMatch(CrmServiceTicketProcessResolution::active);
      rows.add(
          new CrmServiceTicketProcessQueueRow(
              ticket.id(),
              ticket.ticketNo(),
              ticket.ticketType(),
              ticketTypeLabel(ticket.ticketType()),
              ticket.ticketStatus(),
              ticketStatusLabel(ticket.ticketStatus()),
              ticket.priority(),
              priorityLabel(ticket.priority()),
              ticket.subject(),
              ticket.assignedToRef(),
              ticket.caseNo(),
              ticket.createdAt(),
              sla,
              pauseState(state),
              closed,
              confirmed));
    }

    // 本地筛选：SLA 状态 / 是否逾期 / 是否暂停中 / 是否已确认解决 ——
    // 这四个判定都依赖本服务的口径（上游没有「暂停」这个概念），
    // 所以**不能**下推上游，只能整批取回后自己判、自己排、自己分页。
    boolean localFilter =
        isSet(query.slaState())
            || Boolean.TRUE.equals(query.overdueOnly())
            || Boolean.TRUE.equals(query.pausedOnly())
            || isSet(query.resolutionState());
    long total = upstream.total();
    boolean totalExact = true;
    long scanned = upstream.rows().size();

    if (localFilter) {
      List<CrmServiceTicketProcessQueueRow> filtered = new ArrayList<>();
      for (CrmServiceTicketProcessQueueRow row : rows) {
        if (isSet(query.slaState())
            && !query.slaState().trim().toUpperCase(Locale.ROOT).equals(row.sla().replyState())) {
          continue;
        }
        if (Boolean.TRUE.equals(query.overdueOnly()) && !isOverdue(row.sla())) {
          continue;
        }
        if (Boolean.TRUE.equals(query.pausedOnly()) && !row.pause().paused()) {
          continue;
        }
        if (isSet(query.resolutionState())) {
          String wanted = query.resolutionState().trim().toUpperCase(Locale.ROOT);
          boolean confirmed = row.resolutionConfirmed();
          if ("CONFIRMED".equals(wanted) && !confirmed) {
            continue;
          }
          if ("PENDING".equals(wanted) && confirmed) {
            continue;
          }
        }
        filtered.add(row);
      }
      rows = filtered;
      total = filtered.size();
    }

    if ("sla_asc".equals(sort)) {
      rows.sort(Comparator.comparingLong(row -> row.sla().replyRemainingSeconds()));
    } else if ("priority_desc".equals(sort)) {
      rows.sort(Comparator.comparingInt((CrmServiceTicketProcessQueueRow row) -> priorityRank(row.priority())).reversed());
    } else {
      rows.sort(Comparator.comparing(CrmServiceTicketProcessQueueRow::createdAt).reversed());
    }

    CrmServiceTicketProcessQueueSummary summary = summarize(rows);
    return new CrmServiceTicketProcessQueuePage(
        rows,
        page,
        size,
        total,
        summary,
        at,
        localFilter
            ? "真分页：上游整批取回后由本服务按**本服务口径**（SLA 状态 / 逾期 / 暂停中 / "
                + "是否已确认解决）筛选、排序、分页；本次扫描 " + scanned + " 张工单。"
            : "真分页：分页 / 筛选 / 排序全部下推上游（GET /trade/service-tickets，"
                + "LIMIT/OFFSET + 独立 COUNT），总数即上游总数。",
        slaNote(axis),
        "工单事实来自上游 trade 11.4.2.1（经授权 HTTP API，转发调用方自己的 bearer，"
            + "租户由上游按已认证成员关系解析并强制 RLS）；本服务**不建工单表**。",
        "🔴 **关闭 ≠ 解决**：队列里的「已确认解决」是**另一个字段**，"
            + "与「已关闭」互不代表。");
  }

  // ================================================================ 单张工单的处理视图

  /** 单张工单的处理视图（SLA + 暂停状态 + 解决确认状态 + 当前可执行动作）。 */
  public CrmServiceTicketProcessTicket ticket(UUID ticketId, String bearer) {
    ServiceTicketFactsPort.TicketView head = requireTicket(ticketId, bearer);
    TicketState state = loadStates(List.of(ticketId)).getOrDefault(ticketId, TicketState.EMPTY);
    ServiceTicketProcessSlaAxis axis = axisFor(axis(), state);
    OffsetDateTime at = OffsetDateTime.now();

    CrmServiceTicketProcessSla sla = computeSla(head, axis, at);
    boolean closed = isClosed(head.ticketStatus());
    List<String> confirmedTypes =
        state.resolutions.stream()
            .filter(CrmServiceTicketProcessResolution::active)
            .map(CrmServiceTicketProcessResolution::resolutionType)
            .toList();
    boolean confirmed = !confirmedTypes.isEmpty();

    return new CrmServiceTicketProcessTicket(
        head.id(),
        head.ticketNo(),
        head.ticketType(),
        ticketTypeLabel(head.ticketType()),
        head.ticketStatus(),
        ticketStatusLabel(head.ticketStatus()),
        head.priority(),
        priorityLabel(head.priority()),
        head.subject(),
        head.reasonNote(),
        head.assignedToRef(),
        head.assignedToUserId(),
        head.sourceChannel(),
        sourceChannelLabel(head.sourceChannel()),
        head.caseNo(),
        head.createdAt(),
        head.firstReplyAt(),
        head.closedAt(),
        head.closeReason(),
        head.closeNote(),
        head.version(),
        sla,
        pauseState(state),
        closed,
        confirmed,
        confirmedTypes,
        availableActions(head, state, axis, confirmed),
        countOf(ticketId, "crm_service_ticket_note"),
        countOf(ticketId, "crm_service_ticket_action"),
        countOf(ticketId, "crm_service_ticket_link"),
        countOf(ticketId, "crm_service_ticket_resolution"),
        "🔴 **关闭 ≠ 解决**：本单「已关闭」= "
            + (closed ? "是" : "否")
            + "，「已确认解决」= "
            + (confirmed ? "是" : "否")
            + "。两者互不代表 —— 关掉了但退款没到，就是后者为「否」。",
        "回复 / 状态流转 / 关闭 / 满意度 / 升级全部转发上游 trade 11.4.2.1；"
            + "本服务自己的表只存**处理侧**的事实（备注 / 处理记录 / 关联单据 / 解决确认 / SLA 暂停）。");
  }

  // ================================================================ 处理动作

  /**
   * 回复（对买家）。
   *
   * <p>⚠️ <b>顺序</b>：先转发上游（回复时限的完成时刻只在上游记一次），
   * 再写本服务的回复正文。上游失败 ⇒ 一条回复都不写 ——
   * 宁可「没写」也不要「写了但上游不知道」。
   */
  public CrmServiceTicketProcessOutcome reply(
      UUID ticketId, CrmServiceTicketProcessReplyInput input, UUID actorId, String bearer) {
    String content = requireContent(input.content(), "回复内容");
    String channel = normalizeEnum(input.replyChannel(), REPLY_CHANNELS, "回复渠道");
    ServiceTicketFactsPort.TicketView head = requireTicket(ticketId, bearer);
    if (isClosed(head.ticketStatus())) {
      throw badRequest("工单已关闭（" + ticketStatusLabel(head.ticketStatus()) + "），不能再回复");
    }

    boolean firstReply = head.firstReplyAt() == null;
    ServiceTicketFactsPort.TicketDetail after =
        writes.firstReply(ticketId, content, input.expectedVersion(), bearer);

    UUID noteId = UUID.randomUUID();
    OffsetDateTime now = OffsetDateTime.now();
    sql.execute(
        "insert into crm_service_ticket_note"
            + "(id,tenant_id,ticket_id,note_type,content,reply_channel,created_by,created_at)"
            + " values (?,?::uuid,?::uuid,'REPLY',?,?,?::uuid,?::timestamptz)",
        noteId,
        currentTenant(),
        ticketId,
        content,
        channel,
        actorId,
        now);
    CrmServiceTicketProcessNote note =
        new CrmServiceTicketProcessNote(
            noteId,
            ticketId,
            "REPLY",
            "对买家的回复",
            content,
            channel,
            replyChannelLabel(channel),
            actorId,
            now);
    CrmServiceTicketProcessAction action =
        recordAction(
            ticketId,
            CrmServiceTicketProcessActionType.REPLY,
            null,
            null,
            content,
            null,
            null,
            null,
            actorId,
            now);
    return outcome(
        CrmServiceTicketProcessActionType.REPLY,
        firstReply
            ? "回复已记录，并已在上游登记为**首次回复**（回复时限的完成时刻由此起算）。"
            : "回复已记录。上游的**首次回复**早已登记过，本次回复不改变回复时限的完成时刻。",
        after.head(),
        note,
        action,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  /** 内部备注（写给客服看的）。 */
  public CrmServiceTicketProcessOutcome note(
      UUID ticketId, CrmServiceTicketProcessNoteInput input, UUID actorId, String bearer) {
    String content = requireContent(input.content(), "备注内容");
    ServiceTicketFactsPort.TicketView head = requireTicket(ticketId, bearer);
    UUID noteId = UUID.randomUUID();
    OffsetDateTime now = OffsetDateTime.now();
    sql.execute(
        "insert into crm_service_ticket_note"
            + "(id,tenant_id,ticket_id,note_type,content,reply_channel,created_by,created_at)"
            + " values (?,?::uuid,?::uuid,'NOTE',?,null,?::uuid,?::timestamptz)",
        noteId,
        currentTenant(),
        ticketId,
        content,
        actorId,
        now);
    CrmServiceTicketProcessNote note =
        new CrmServiceTicketProcessNote(
            noteId, ticketId, "NOTE", "内部备注", content, null, null, actorId, now);
    CrmServiceTicketProcessAction action =
        recordAction(
            ticketId,
            CrmServiceTicketProcessActionType.NOTE,
            null,
            null,
            content,
            null,
            null,
            null,
            actorId,
            now);
    return outcome(
        CrmServiceTicketProcessActionType.NOTE,
        "备注已记录（只追加：改它会让「当时跟买家说了什么」变得不可复核）。",
        head,
        note,
        action,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  /** 状态流转（待处理 → 处理中 ⇄ 待买家）。 */
  public CrmServiceTicketProcessOutcome changeStatus(
      UUID ticketId, CrmServiceTicketProcessStatusInput input, UUID actorId, String bearer) {
    String target = normalizeEnum(input.targetStatus(), TARGET_STATUSES, "目标状态");
    ServiceTicketFactsPort.TicketView head = requireTicket(ticketId, bearer);
    if (isClosed(head.ticketStatus())) {
      throw badRequest(
          "工单已关闭（" + ticketStatusLabel(head.ticketStatus()) + "），不能再流转状态 —— "
              + "终态只能由「关闭」进入");
    }
    ServiceTicketFactsPort.TicketDetail after =
        writes.changeStatus(ticketId, target, input.note(), input.expectedVersion(), bearer);
    OffsetDateTime now = OffsetDateTime.now();
    CrmServiceTicketProcessAction action =
        recordAction(
            ticketId,
            CrmServiceTicketProcessActionType.STATUS_CHANGE,
            head.ticketStatus(),
            target,
            input.note(),
            null,
            null,
            null,
            actorId,
            now);
    return outcome(
        CrmServiceTicketProcessActionType.STATUS_CHANGE,
        "状态已流转：" + ticketStatusLabel(head.ticketStatus()) + " → " + ticketStatusLabel(target) + "。",
        after.head(),
        null,
        action,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  /**
   * 关闭工单（落终态）。
   *
   * <p>🔴 <b>关闭 ≠ 解决</b>：本动作只让工单不再跟了；
   * 「实际结果到了没有」要由**另一个动作**（解决确认）回答。
   */
  public CrmServiceTicketProcessOutcome close(
      UUID ticketId, CrmServiceTicketProcessCloseInput input, UUID actorId, String bearer) {
    String reason = normalizeEnum(input.closeReason(), CLOSE_REASONS, "关闭原因");
    ServiceTicketFactsPort.TicketView head = requireTicket(ticketId, bearer);
    if (isClosed(head.ticketStatus())) {
      throw badRequest("工单已经关闭（" + ticketStatusLabel(head.ticketStatus()) + "），不能重复关闭");
    }
    ServiceTicketFactsPort.TicketDetail after =
        writes.close(ticketId, reason, input.closeNote(), input.expectedVersion(), bearer);
    OffsetDateTime now = OffsetDateTime.now();
    CrmServiceTicketProcessAction action =
        recordAction(
            ticketId,
            CrmServiceTicketProcessActionType.CLOSE,
            head.ticketStatus(),
            after.head().ticketStatus(),
            "关闭原因：" + closeReasonLabel(reason) + (isSet(input.closeNote()) ? "；" + input.closeNote() : ""),
            null,
            null,
            null,
            actorId,
            now);
    return outcome(
        CrmServiceTicketProcessActionType.CLOSE,
        "工单已关闭（" + closeReasonLabel(reason) + "）。"
            + "🔴 **关闭不等于解决** —— 若实际结果已到（退款到账 / 换货发出），"
            + "请再做一次「解决确认」。",
        after.head(),
        null,
        action,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  /**
   * 🔴 解决确认（实际结果）。
   *
   * <p>⚠️ <b>要求工单已关闭</b>：先关后确认，顺序本身就是纪律的落点 ——
   * 工单还开着就宣布「解决了」，那不是解决确认，那是提前结案。
   */
  public CrmServiceTicketProcessOutcome confirmResolution(
      UUID ticketId, CrmServiceTicketProcessResolutionInput input, UUID actorId, String bearer) {
    String type = normalizeEnum(input.resolutionType(), RESOLUTION_TYPES, "解决结果类型");
    if ("OTHER".equals(type) && !isSet(input.resultNote())) {
      throw badRequest("解决结果类型为「其它」时必须写明结果说明");
    }
    ServiceTicketFactsPort.TicketView head = requireTicket(ticketId, bearer);
    if (!isClosed(head.ticketStatus())) {
      throw badRequest(
          "工单还没关闭（当前 " + ticketStatusLabel(head.ticketStatus()) + "）："
              + "**解决确认是关闭之后的另一个动作** —— 关闭 ≠ 解决。"
              + "请先关闭工单，再确认实际结果。");
    }
    TicketState state = loadStates(List.of(ticketId)).getOrDefault(ticketId, TicketState.EMPTY);
    for (CrmServiceTicketProcessResolution existing : state.resolutions) {
      if (existing.resolutionType().equals(type) && existing.active()) {
        throw new ResponseStatusException(
            HttpStatus.CONFLICT,
            "这张工单已经确认过「" + resolutionTypeLabel(type) + "」了；"
                + "要改的话先撤销那一条确认（撤销会留痕），别直接覆盖。");
      }
    }
    UUID linkCaseId = null;
    String linkOrderNo = null;
    for (CrmServiceTicketProcessLink link : state.links) {
      if ("AFTER_SALES_CASE".equals(link.linkType()) && link.caseId() != null) {
        linkCaseId = link.caseId();
      }
      if ("ORDER".equals(link.linkType()) && link.orderNo() != null) {
        linkOrderNo = link.orderNo();
      }
    }

    UUID id = UUID.randomUUID();
    OffsetDateTime now = OffsetDateTime.now();
    sql.execute(
        "insert into crm_service_ticket_resolution"
            + "(id,tenant_id,ticket_id,resolution_type,result_note,linked_case_id,linked_order_no,"
            + " confirmed_by,confirmed_at)"
            + " values (?,?::uuid,?::uuid,?,?,?::uuid,?,?::uuid,?::timestamptz)",
        id,
        currentTenant(),
        ticketId,
        type,
        input.resultNote(),
        linkCaseId,
        linkOrderNo,
        actorId,
        now);
    // 关联单据更新：把「实际结果到了」记在关联关系上（**不假装推给了上游** ——
    // 上游没有改写已有售后单 / 订单的端点）。
    touchLink(ticketId, "解决确认：" + resolutionTypeLabel(type)
        + (isSet(input.resultNote()) ? "（" + input.resultNote() + "）" : ""), now);

    CrmServiceTicketProcessResolution resolution =
        new CrmServiceTicketProcessResolution(
            id,
            ticketId,
            type,
            resolutionTypeLabel(type),
            input.resultNote(),
            linkCaseId,
            linkOrderNo,
            actorId,
            now,
            null,
            null,
            true);
    CrmServiceTicketProcessAction action =
        recordAction(
            ticketId,
            CrmServiceTicketProcessActionType.RESOLUTION_CONFIRM,
            null,
            null,
            "确认解决：" + resolutionTypeLabel(type)
                + (isSet(input.resultNote()) ? "（" + input.resultNote() + "）" : ""),
            linkOrderNo,
            null,
            type,
            actorId,
            now);
    return outcome(
        CrmServiceTicketProcessActionType.RESOLUTION_CONFIRM,
        "已确认解决：" + resolutionTypeLabel(type) + "。"
            + "🔴 这是**关闭之外**的结论：工单关闭只表示不再跟了，"
            + "这里的确认才表示实际结果确实到了。",
        head,
        null,
        action,
        resolution,
        null,
        null,
        null,
        null,
        null);
  }

  /** 撤销解决确认（写错了要能改 —— 假的「退款已到账」比没有更糟）。 */
  public CrmServiceTicketProcessOutcome revokeResolution(
      UUID ticketId, UUID resolutionId, UUID actorId, String bearer) {
    requireTicket(ticketId, bearer);
    OffsetDateTime now = OffsetDateTime.now();
    int updated =
        sql.execute(
            "update crm_service_ticket_resolution set revoked_at = ?::timestamptz, revoked_by = ?::uuid"
                + " where tenant_id = ?::uuid and ticket_id = ?::uuid and id = ?::uuid"
                + " and revoked_at is null",
            now,
            actorId,
            currentTenant(),
            ticketId,
            resolutionId);
    if (updated == 0) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, "这张工单上没有可撤销的解决确认（或者已经撤销过了）");
    }
    CrmServiceTicketProcessResolution resolution = readResolution(ticketId, resolutionId);
    CrmServiceTicketProcessAction action =
        recordAction(
            ticketId,
            CrmServiceTicketProcessActionType.RESOLUTION_REVOKE,
            null,
            null,
            "撤销解决确认：" + resolutionTypeLabel(resolution.resolutionType()),
            null,
            null,
            resolution.resolutionType(),
            actorId,
            now);
    return outcome(
        CrmServiceTicketProcessActionType.RESOLUTION_REVOKE,
        "已撤销「" + resolutionTypeLabel(resolution.resolutionType()) + "」这条解决确认（撤销留痕，行不删）。",
        null,
        null,
        action,
        resolution,
        null,
        null,
        null,
        null,
        null);
  }

  /**
   * 满意度回收（1~5 分）。
   *
   * <p>🔴 <b>只转发，不另存</b>：上游已有这个事实（11.4.2.1 拥有）。
   */
  public CrmServiceTicketProcessOutcome satisfaction(
      UUID ticketId, CrmServiceTicketProcessSatisfactionInput input, UUID actorId, String bearer) {
    if (input.score() == null || input.score() < 1 || input.score() > 5) {
      throw badRequest("满意度评分必须是 1~5 之间的整数");
    }
    ServiceTicketFactsPort.TicketView head = requireTicket(ticketId, bearer);
    if (!isClosed(head.ticketStatus())) {
      throw badRequest("满意度只对**已关闭**的工单开放");
    }
    if (head.satisfactionRating() != null) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "这张工单已经收过满意度评价了（一张工单一条）");
    }
    ServiceTicketFactsPort.TicketDetail after =
        writes.satisfaction(ticketId, input.score(), input.comment(), bearer);
    OffsetDateTime now = OffsetDateTime.now();
    CrmServiceTicketProcessAction action =
        recordAction(
            ticketId,
            CrmServiceTicketProcessActionType.SATISFACTION,
            null,
            null,
            "满意度评分：" + input.score() + (isSet(input.comment()) ? "（" + input.comment() + "）" : ""),
            null,
            null,
            null,
            actorId,
            now);
    return outcome(
        CrmServiceTicketProcessActionType.SATISFACTION,
        "满意度已回收（评分 " + input.score() + "）。"
            + "🔴 评价**只存在上游**（11.4.2.1 拥有），本服务不另存一份 —— "
            + "同一件事两个事实源迟早打架。",
        after.head(),
        null,
        action,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  /**
   * 🔴 SLA 暂停。
   *
   * <p>⚠️ <b>没有轴就没有暂停</b>：没配营业时间 ⇒ 不给暂停
   * （记一条暂停只会让人以为 SLA 被冻结了，而实际上根本没有这条轴）。
   */
  public CrmServiceTicketProcessOutcome pauseSla(
      UUID ticketId, CrmServiceTicketProcessSlaPauseInput input, UUID actorId, String bearer) {
    String target = normalizeEnum(input.slaTarget(), SLA_TARGETS, "暂停目标");
    if (!isSet(input.pauseReason())) {
      throw badRequest("暂停 SLA 必须写理由：没有理由的暂停事后无从判断该不该恢复");
    }
    ServiceTicketFactsPort.TicketView head = requireTicket(ticketId, bearer);
    if (isClosed(head.ticketStatus())) {
      throw badRequest("工单已关闭，SLA 已停止计时，无需再暂停");
    }
    ServiceTicketProcessSlaAxis base = axis();
    if (!base.configured()) {
      throw badRequest(
          "本租户**未配置营业时间** ⇒ SLA 算不出来（NO_CALENDAR），本服务**不退回自然时间**。"
              + "没有轴就没有「暂停掉的那段营业时间」，所以不给暂停。");
    }
    TicketState state = loadStates(List.of(ticketId)).getOrDefault(ticketId, TicketState.EMPTY);
    if (state.openPause() != null) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "这张工单已经处于暂停中（暂停于 " + state.openPause().pausedAt() + "），请先恢复再暂停。");
    }
    UUID id = UUID.randomUUID();
    OffsetDateTime now = OffsetDateTime.now();
    sql.execute(
        "insert into crm_service_ticket_sla_pause"
            + "(id,tenant_id,ticket_id,sla_target,scope_key,paused_at,pause_reason,created_by,created_at)"
            + " values (?,?::uuid,?::uuid,?,?,?::timestamptz,?,?::uuid,?::timestamptz)",
        id,
        currentTenant(),
        ticketId,
        target,
        base.scopeKey(),
        now,
        input.pauseReason(),
        actorId,
        now);
    CrmServiceTicketProcessAction action =
        recordAction(
            ticketId,
            CrmServiceTicketProcessActionType.SLA_PAUSE,
            null,
            null,
            "暂停 SLA（" + slaTargetLabel(target) + "）：" + input.pauseReason(),
            null,
            null,
            null,
            actorId,
            now);
    TicketState refreshed = loadStates(List.of(ticketId)).getOrDefault(ticketId, TicketState.EMPTY);
    return outcome(
        CrmServiceTicketProcessActionType.SLA_PAUSE,
        "SLA 已暂停（" + slaTargetLabel(target) + "）。"
            + "🔴 暂停作用在**营业时间轴**上：暂停区间里的营业时间不计入 SLA 消耗。",
        head,
        null,
        action,
        null,
        null,
        pauseState(refreshed),
        computeSla(head, axisFor(base, refreshed), now),
        null,
        null);
  }

  /** SLA 恢复（必须写理由：为什么现在恢复）。 */
  public CrmServiceTicketProcessOutcome resumeSla(
      UUID ticketId, CrmServiceTicketProcessSlaResumeInput input, UUID actorId, String bearer) {
    if (!isSet(input.resumeReason())) {
      throw badRequest("恢复 SLA 必须写理由：为什么现在恢复，事后要查得到");
    }
    ServiceTicketFactsPort.TicketView head = requireTicket(ticketId, bearer);
    TicketState state = loadStates(List.of(ticketId)).getOrDefault(ticketId, TicketState.EMPTY);
    if (state.openPause() == null) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "这张工单当前没有处于暂停中的 SLA");
    }
    OffsetDateTime now = OffsetDateTime.now();
    int updated =
        sql.execute(
            "update crm_service_ticket_sla_pause set resumed_at = ?::timestamptz, resume_reason = ?"
                + " where tenant_id = ?::uuid and ticket_id = ?::uuid and id = ?::uuid"
                + " and resumed_at is null",
            now,
            input.resumeReason(),
            currentTenant(),
            ticketId,
            state.openPauseId());
    if (updated == 0) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "这张工单的 SLA 暂停已被恢复，请刷新后重试");
    }
    CrmServiceTicketProcessAction action =
        recordAction(
            ticketId,
            CrmServiceTicketProcessActionType.SLA_RESUME,
            null,
            null,
            "恢复 SLA：" + input.resumeReason(),
            null,
            null,
            null,
            actorId,
            now);
    TicketState refreshed = loadStates(List.of(ticketId)).getOrDefault(ticketId, TicketState.EMPTY);
    return outcome(
        CrmServiceTicketProcessActionType.SLA_RESUME,
        "SLA 已恢复。暂停区间里的营业时间**不计入** SLA 消耗，"
            + "所以到期时刻比不暂停时**更晚**。",
        head,
        null,
        action,
        null,
        null,
        pauseState(refreshed),
        computeSla(head, axisFor(axis(), refreshed), now),
        null,
        null);
  }

  /**
   * 关联订单或售后单。
   *
   * <p>⚠️ <b>必须去上游核实</b>：查不到的订单号 / 售后单号一律拒绝 ——
   * 关联一个不存在的单子，比不关联更糟（客服会照着错的单子去处理）。
   */
  public CrmServiceTicketProcessOutcome link(
      UUID ticketId, CrmServiceTicketProcessLinkInput input, UUID actorId, String bearer) {
    String type = normalizeEnum(input.linkType(), LINK_TYPES, "关联类型");
    ServiceTicketFactsPort.TicketView head = requireTicket(ticketId, bearer);
    UUID orderId = null;
    UUID caseId = null;
    String orderNo = null;
    String caseNo = null;
    if ("ORDER".equals(type)) {
      if (!isSet(input.orderNo())) {
        throw badRequest("关联订单必须给订单号");
      }
      ServiceTicketFactsPort.OrderView order = facts.orderByNo(input.orderNo().trim(), bearer);
      if (order == null) {
        throw badRequest(
            "上游 trade 里没有与订单号 " + input.orderNo().trim()
                + " **完全相等**的订单 —— 不给关联：关联一个不存在的单子，比不关联更糟。");
      }
      orderId = order.id();
      orderNo = order.orderNo() != null ? order.orderNo() : order.externalOrderNo();
    } else {
      if (!isSet(input.caseNo())) {
        throw badRequest("关联售后单必须给售后单号");
      }
      ServiceTicketFactsPort.CaseView aftersales = facts.caseByNo(input.caseNo().trim(), bearer);
      if (aftersales == null) {
        throw badRequest(
            "上游 trade 里没有与售后单号 " + input.caseNo().trim()
                + " **完全相等**的售后单 —— 不给关联：关联一个不存在的单子，比不关联更糟。");
      }
      caseId = aftersales.id();
      caseNo = aftersales.caseNo();
    }

    OffsetDateTime now = OffsetDateTime.now();
    UUID id = UUID.randomUUID();
    int updated =
        sql.execute(
            "update crm_service_ticket_link set order_id = coalesce(?::uuid, order_id),"
                + " order_no = coalesce(?, order_no), case_id = coalesce(?::uuid, case_id),"
                + " case_no = coalesce(?, case_no), link_source = 'MANUAL', last_sync_note = ?,"
                + " last_synced_at = ?::timestamptz, linked_by = ?::uuid, linked_at = ?::timestamptz,"
                + " updated_at = ?::timestamptz"
                + " where tenant_id = ?::uuid and ticket_id = ?::uuid and link_type = ?",
            orderId,
            orderNo,
            caseId,
            caseNo,
            isSet(input.note()) ? input.note() : "人工关联",
            now,
            actorId,
            now,
            now,
            currentTenant(),
            ticketId,
            type);
    if (updated == 0) {
      sql.execute(
          "insert into crm_service_ticket_link"
              +"(id,tenant_id,ticket_id,link_type,order_id,order_no,case_id,case_no,link_source,"
              + " last_sync_note,last_synced_at,linked_by,linked_at,updated_at)"
              + " values (?,?::uuid,?::uuid,?,?::uuid,?,?::uuid,?,'MANUAL',?,?::timestamptz,?::uuid,"
              + "?::timestamptz,?::timestamptz)",
          id,
          currentTenant(),
          ticketId,
          type,
          orderId,
          orderNo,
          caseId,
          caseNo,
          isSet(input.note()) ? input.note() : "人工关联",
          now,
          actorId,
          now,
          now);
    }
    CrmServiceTicketProcessLink link = readLink(ticketId, type);
    CrmServiceTicketProcessAction action =
        recordAction(
            ticketId,
            "ORDER".equals(type)
                ? CrmServiceTicketProcessActionType.LINK_ORDER
                : CrmServiceTicketProcessActionType.LINK_CASE,
            null,
            null,
            isSet(input.note()) ? input.note() : null,
            orderNo,
            caseNo,
            null,
            actorId,
            now);
    return outcome(
        "ORDER".equals(type)
            ? CrmServiceTicketProcessActionType.LINK_ORDER
            : CrmServiceTicketProcessActionType.LINK_CASE,
        "关联已建立（订单 " + (orderNo == null ? "—" : orderNo) + " / 售后单 "
            + (caseNo == null ? "—" : caseNo) + "）。"
            + "🔴 上游 trade **没有**改写已有售后单 / 订单的端点 ⇒ "
            + "本模块**不假装推给了上游**，关联单据的更新只落在本服务这边。",
        head,
        null,
        action,
        null,
        link,
        null,
        null,
        null,
        null);
  }

  /**
   * 升级（转投诉 / 转纠纷 ⇒ 下游 11 售后）。
   *
   * <p>⚠️ 升级物**真的**落在 trade 库里（那边的唯一写入所有者干的活）；
   * 本模块只把**关系**记在自己的关联单据表里。
   */
  public CrmServiceTicketProcessOutcome escalate(
      UUID ticketId, CrmServiceTicketProcessEscalateInput input, UUID actorId, String bearer) {
    String target = normalizeEnum(input.targetType(), ESCALATION_TYPES, "升级目标类型");
    if (!isSet(input.reasonNote())) {
      throw badRequest("升级必须写理由：升级之后这单就不在本模块的处理范围内了");
    }
    ServiceTicketFactsPort.TicketView head = requireTicket(ticketId, bearer);
    if (isClosed(head.ticketStatus())) {
      throw badRequest("工单已关闭，不能再升级");
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("targetType", target);
    body.put("reasonNote", input.reasonNote());
    putIfSet(body, "targetCaseType", input.targetCaseType());
    putIfSet(body, "targetReasonCode", input.targetReasonCode());
    putAmount(body, "targetAmount", input.targetAmount());
    putIfSet(body, "targetCurrency", input.targetCurrency());
    putAmount(body, "targetQuantity", input.targetQuantity());
    putIfSet(body, "platformCode", input.platformCode());
    putIfSet(body, "platformName", input.platformName());
    putIfSet(body, "platformDisputeNo", input.platformDisputeNo());
    putIfSet(body, "platformOrderNo", input.platformOrderNo());
    putIfSet(body, "disputeType", input.disputeType());
    putAmount(body, "disputeAmount", input.disputeAmount());
    putIfSet(body, "disputeCurrency", input.disputeCurrency());
    if (input.evidenceDeadline() != null) {
      body.put("evidenceDeadline", input.evidenceDeadline().toString());
    }
    if (input.expectedVersion() != null) {
      body.put("expectedVersion", input.expectedVersion());
    }
    ServiceTicketFactsPort.TicketDetail after = writes.escalate(ticketId, body, bearer);

    String escalatedCaseNo = after.head().escalatedCaseNo();
    String escalatedDisputeNo = after.head().escalatedDisputeNo();
    OffsetDateTime now = OffsetDateTime.now();
    if (isSet(escalatedCaseNo)) {
      upsertCaseLink(ticketId, escalatedCaseNo, actorId, now);
    }
    CrmServiceTicketProcessAction action =
        recordAction(
            ticketId,
            CrmServiceTicketProcessActionType.ESCALATE,
            head.ticketStatus(),
            after.head().ticketStatus(),
            "升级（" + ("COMPLAINT".equals(target) ? "转投诉" : "转纠纷") + "）：" + input.reasonNote(),
            null,
            escalatedCaseNo,
            null,
            actorId,
            now);
    return outcome(
        CrmServiceTicketProcessActionType.ESCALATE,
        "已升级（" + ("COMPLAINT".equals(target) ? "转投诉" : "转纠纷") + "）。"
            + (isSet(escalatedCaseNo) ? "新建的售后单：" + escalatedCaseNo + "。" : "")
            + (isSet(escalatedDisputeNo) ? "新建的平台纠纷：" + escalatedDisputeNo + "。" : "")
            + "升级物落在 trade 服务（下游 11 售后）的库里，本模块只记关系。",
        after.head(),
        null,
        action,
        null,
        null,
        null,
        null,
        escalatedCaseNo,
        escalatedDisputeNo);
  }

  // ================================================================ 列表

  /** 备注 / 回复列表（真分页）。 */
  public CrmServiceTicketProcessNotePage notes(UUID ticketId, Integer page, Integer size) {
    int[] window = pageWindow(page, size);
    List<Record> rows =
        sql.fetch(
            "select id, ticket_id, note_type, content, reply_channel, created_by, created_at"
                + " from crm_service_ticket_note where tenant_id = ?::uuid and ticket_id = ?::uuid"
                + " order by created_at desc, id desc limit ? offset ?",
            currentTenant(),
            ticketId,
            window[1],
            window[0]);
    long total =
        sql.fetchOne(
                "select count(*) from crm_service_ticket_note"
                    + " where tenant_id = ?::uuid and ticket_id = ?::uuid",
                currentTenant(),
                ticketId)
            .get(0, Long.class);
    List<CrmServiceTicketProcessNote> items = new ArrayList<>();
    for (Record row : rows) {
      String type = row.get("note_type", String.class);
      String channel = row.get("reply_channel", String.class);
      items.add(
          new CrmServiceTicketProcessNote(
              row.get("id", UUID.class),
              row.get("ticket_id", UUID.class),
              type,
              "REPLY".equals(type) ? "对买家的回复" : "内部备注",
              row.get("content", String.class),
              channel,
              channel == null ? null : replyChannelLabel(channel),
              row.get("created_by", UUID.class),
              row.get("created_at", OffsetDateTime.class)));
    }
    return new CrmServiceTicketProcessNotePage(
        items,
        window[2],
        window[1],
        total,
        "备注与回复**只追加**（DB 触发器拒绝改删）：改掉一条旧备注，"
            + "「当时跟买家说了什么」就再也复核不了了。");
  }

  /** 处理记录列表（真分页）。 */
  public CrmServiceTicketProcessActionPage actions(UUID ticketId, Integer page, Integer size) {
    int[] window = pageWindow(page, size);
    List<Record> rows =
        sql.fetch(
            "select id, ticket_id, action_type, from_status, to_status, detail, related_order_no,"
                + " related_case_no, resolution_type, actor_id, occurred_at"
                + " from crm_service_ticket_action where tenant_id = ?::uuid and ticket_id = ?::uuid"
                + " order by occurred_at desc, id desc limit ? offset ?",
            currentTenant(),
            ticketId,
            window[1],
            window[0]);
    long total =
        sql.fetchOne(
                "select count(*) from crm_service_ticket_action"
                    + " where tenant_id = ?::uuid and ticket_id = ?::uuid",
                currentTenant(),
                ticketId)
            .get(0, Long.class);
    List<CrmServiceTicketProcessAction> items = new ArrayList<>();
    for (Record row : rows) {
      items.add(
          new CrmServiceTicketProcessAction(
              row.get("id", UUID.class),
              row.get("ticket_id", UUID.class),
              row.get("action_type", String.class),
              actionTypeLabel(row.get("action_type", String.class)),
              row.get("from_status", String.class),
              row.get("to_status", String.class),
              row.get("detail", String.class),
              row.get("related_order_no", String.class),
              row.get("related_case_no", String.class),
              row.get("resolution_type", String.class),
              row.get("actor_id", UUID.class),
              row.get("occurred_at", OffsetDateTime.class)));
    }
    return new CrmServiceTicketProcessActionPage(
        items,
        window[2],
        window[1],
        total,
        "处理记录**只追加**（DB 触发器拒绝改删）：可改可删的动作历史等于没有历史。"
            + "⚠️ 只记**成功**的动作 —— 失败的动作随事务回滚消失（「写了但没生效」比不写更糟）。");
  }

  /** 关联单据列表（真分页 + 上游当前事实现取）。 */
  public CrmServiceTicketProcessLinkPage links(
      UUID ticketId, Integer page, Integer size, String bearer) {
    int[] window = pageWindow(page, size);
    List<Record> rows =
        sql.fetch(
            "select id, ticket_id, link_type, order_id, order_no, case_id, case_no, link_source,"
                + " last_sync_note, last_synced_at, linked_by, linked_at"
                + " from crm_service_ticket_link where tenant_id = ?::uuid and ticket_id = ?::uuid"
                + " order by link_type, linked_at desc limit ? offset ?",
            currentTenant(),
            ticketId,
            window[1],
            window[0]);
    long total =
        sql.fetchOne(
                "select count(*) from crm_service_ticket_link"
                    + " where tenant_id = ?::uuid and ticket_id = ?::uuid",
                currentTenant(),
                ticketId)
            .get(0, Long.class);
    List<CrmServiceTicketProcessLink> items = new ArrayList<>();
    for (Record row : rows) {
      items.add(enrichLink(row, bearer));
    }
    return new CrmServiceTicketProcessLinkPage(
        items,
        window[2],
        window[1],
        total,
        "关联单据只存**关系**；订单 / 售后单的**当前事实**是本次读取时从上游 trade 现取的，"
            + "**不在本服务复制一份**。🔴 上游没有改写已有售后单 / 订单的端点 ⇒ "
            + "本模块不假装推给了上游。");
  }

  /** 解决确认列表（真分页）。 */
  public CrmServiceTicketProcessResolutionPage resolutions(
      UUID ticketId, Integer page, Integer size) {
    int[] window = pageWindow(page, size);
    List<Record> rows =
        sql.fetch(
            "select id, ticket_id, resolution_type, result_note, linked_case_id, linked_order_no,"
                + " confirmed_by, confirmed_at, revoked_at, revoked_by"
                + " from crm_service_ticket_resolution where tenant_id = ?::uuid and ticket_id = ?::uuid"
                + " order by confirmed_at desc, id desc limit ? offset ?",
            currentTenant(),
            ticketId,
            window[1],
            window[0]);
    long total =
        sql.fetchOne(
                "select count(*) from crm_service_ticket_resolution"
                    + " where tenant_id = ?::uuid and ticket_id = ?::uuid",
                currentTenant(),
                ticketId)
            .get(0, Long.class);
    List<CrmServiceTicketProcessResolution> items = new ArrayList<>();
    for (Record row : rows) {
      items.add(toResolution(row));
    }
    return new CrmServiceTicketProcessResolutionPage(
        items,
        window[2],
        window[1],
        total,
        "🔴 **关闭 ≠ 解决**：这里的每一行都是**实际结果**（退款到账 / 换货发出 / …），"
            + "与工单是否关闭**互不代表**。撤销只标 revoked_at，行不删（留痕）。");
  }

  // ================================================================ 内部实现

  /** 一张工单在本服务这边的处理侧状态（暂停 + 解决确认 + 关联单据）。 */
  private record TicketState(
      List<ServiceTicketProcessSlaAxis.Pause> pauses,
      UUID openPauseId,
      OffsetDateTime openPauseAt,
      String openPauseTarget,
      String openPauseReason,
      long pauseCount,
      List<CrmServiceTicketProcessResolution> resolutions,
      List<CrmServiceTicketProcessLink> links) {

    static final TicketState EMPTY =
        new TicketState(List.of(), null, null, null, null, 0L, List.of(), List.of());

    CrmServiceTicketProcessPauseState openPause() {
      return openPauseId == null ? null : new CrmServiceTicketProcessPauseState(true, openPauseTarget, openPauseAt, openPauseReason, pauseCount);
    }
  }

  /** 批量读一批工单的处理侧状态（一次查询，不逐张往返）。 */
  private Map<UUID, TicketState> loadStates(List<UUID> ticketIds) {
    Map<UUID, TicketState> result = new LinkedHashMap<>();
    for (UUID id : ticketIds) {
      result.put(id, TicketState.EMPTY);
    }
    if (ticketIds.isEmpty()) {
      return result;
    }
    String in = placeholders(ticketIds.size());
    List<Object> args = new ArrayList<>();
    args.add(currentTenant());
    args.addAll(ticketIds);

    Map<UUID, List<ServiceTicketProcessSlaAxis.Pause>> pauses = new LinkedHashMap<>();
    Map<UUID, UUID> openPauseId = new LinkedHashMap<>();
    Map<UUID, OffsetDateTime> openPauseAt = new LinkedHashMap<>();
    Map<UUID, String> openPauseTarget = new LinkedHashMap<>();
    Map<UUID, String> openPauseReason = new LinkedHashMap<>();
    Map<UUID, Long> pauseCount = new LinkedHashMap<>();
    for (Record row :
        sql.fetch(
            "select id, ticket_id, sla_target, paused_at, resumed_at, pause_reason"
                + " from crm_service_ticket_sla_pause"
                + " where tenant_id = ?::uuid and ticket_id in ("
                + in
                + ") order by paused_at",
            args.toArray())) {
      UUID ticketId = row.get("ticket_id", UUID.class);
      pauses
          .computeIfAbsent(ticketId, key -> new ArrayList<>())
          .add(
              new ServiceTicketProcessSlaAxis.Pause(
                  row.get("sla_target", String.class),
                  row.get("paused_at", OffsetDateTime.class),
                  row.get("resumed_at", OffsetDateTime.class)));
      pauseCount.merge(ticketId, 1L, Long::sum);
      if (row.get("resumed_at", OffsetDateTime.class) == null) {
        openPauseId.put(ticketId, row.get("id", UUID.class));
        openPauseAt.put(ticketId, row.get("paused_at", OffsetDateTime.class));
        openPauseTarget.put(ticketId, row.get("sla_target", String.class));
        openPauseReason.put(ticketId, row.get("pause_reason", String.class));
      }
    }

    Map<UUID, List<CrmServiceTicketProcessResolution>> resolutions = new LinkedHashMap<>();
    for (Record row :
        sql.fetch(
            "select id, ticket_id, resolution_type, result_note, linked_case_id, linked_order_no,"
                + " confirmed_by, confirmed_at, revoked_at, revoked_by"
                + " from crm_service_ticket_resolution"
                + " where tenant_id = ?::uuid and ticket_id in ("
                + in
                + ")",
            args.toArray())) {
      resolutions
          .computeIfAbsent(row.get("ticket_id", UUID.class), key -> new ArrayList<>())
          .add(toResolution(row));
    }

    Map<UUID, List<CrmServiceTicketProcessLink>> links = new LinkedHashMap<>();
    for (Record row :
        sql.fetch(
            "select id, ticket_id, link_type, order_id, order_no, case_id, case_no, link_source,"
                + " last_sync_note, last_synced_at, linked_by, linked_at"
                + " from crm_service_ticket_link where tenant_id = ?::uuid and ticket_id in ("
                + in
                + ")",
            args.toArray())) {
      links
          .computeIfAbsent(row.get("ticket_id", UUID.class), key -> new ArrayList<>())
          .add(toLink(row));
    }

    for (UUID id : ticketIds) {
      result.put(
          id,
          new TicketState(
              pauses.getOrDefault(id, List.of()),
              openPauseId.get(id),
              openPauseAt.get(id),
              openPauseTarget.get(id),
              openPauseReason.get(id),
              pauseCount.getOrDefault(id, 0L),
              resolutions.getOrDefault(id, List.of()),
              links.getOrDefault(id, List.of())));
    }
    return result;
  }

  /** 读租户默认作用域的营业时间轴（同库同服务，读的是 15.4.1.1 的配置表）。 */
  private ServiceTicketProcessSlaAxis axis() {
    return new ServiceTicketProcessSlaAxis(readAxis(DEFAULT_SCOPE), List.of());
  }

  /**
   * 把一条轴与某张工单的暂停区间合起来（<b>共用同一条轴</b>，不重读配置表）。
   *
   * <p>⚠️ 处理队列一页最多 50 张工单 —— 若每张都重读一次营业时间配置，
   * 一页就是 50 次查询。
   */
  private ServiceTicketProcessSlaAxis axisFor(
      ServiceTicketProcessSlaAxis base, TicketState state) {
    return base.withPauses(state.pauses());
  }

  /** 读一个作用域的轴（形状来自本服务自己的配置表，**不写死常量**）。 */
  private BusinessHoursCalendar readAxis(String scopeKey) {
    List<Record> windowRows =
        sql.fetch(
            "select weekday, start_time, end_time, timezone from crm_service_business_hours"
                + " where tenant_id = ?::uuid and scope_key = ? and enabled"
                + " order by weekday, start_time",
            currentTenant(),
            scopeKey);
    List<BusinessHoursCalendar.Window> windows = new ArrayList<>();
    String timezone = null;
    for (Record row : windowRows) {
      windows.add(
          new BusinessHoursCalendar.Window(
              row.get("weekday", Integer.class),
              row.get("start_time", LocalTime.class),
              row.get("end_time", LocalTime.class)));
      if (timezone == null) {
        timezone = row.get("timezone", String.class);
      }
    }
    Set<LocalDate> closed = new HashSet<>();
    for (Record row :
        sql.fetch(
            "select holiday_date from crm_service_business_holiday"
                + " where tenant_id = ?::uuid and scope_key = ? and closed",
            currentTenant(),
            scopeKey)) {
      closed.add(row.get("holiday_date", LocalDate.class));
    }
    return BusinessHoursCalendar.of(scopeKey, timezone, windows, closed);
  }

  /**
   * 算一张工单的 SLA（<b>营业时间口径</b>，<b>已扣除暂停区间</b>）。
   *
   * <p>⚠️ 判据与上游逐条对齐（回复时限看 first_reply_at、处理时限看 closed_at），
   * 只把「到期时刻」换成**暂停感知**的营业时间轴累加结果 ——
   * 判据不一样的话，同一张单在两个服务里会给出两个结论。
   */
  private CrmServiceTicketProcessSla computeSla(
      ServiceTicketFactsPort.TicketView ticket, ServiceTicketProcessSlaAxis axis, OffsetDateTime at) {
    Integer replyHours = ticket.slaReplyHours();
    Integer resolveHours = ticket.slaResolveHours();

    OffsetDateTime replyDue = null;
    OffsetDateTime resolveDue = null;
    String replyState;
    String resolveState;
    long replyRemaining = 0L;
    long resolveRemaining = 0L;
    long replyWall = 0L;
    long resolveWall = 0L;

    if (replyHours == null) {
      replyState = CrmServiceTicketProcessSlaState.NO_DEADLINE.name();
    } else if (!axis.configured()) {
      replyState = CrmServiceTicketProcessSlaState.NO_CALENDAR.name();
    } else {
      // ⚠️ 剩余量**无论到期时刻算不算得出来都要给**：
      //   暂停期间到期时刻还没发生（due=null），但「还剩多少营业时间」是清楚的
      //   （暂停区间里的营业时间不计入消耗）—— 留成 0 会被读成「已经超期」。
      replyRemaining =
          axis.remainingSeconds(
              ticket.createdAt(), at, replyHours * 60L, ServiceTicketProcessSlaAxis.REPLY);
      replyWall = axis.wallClockRemainingSeconds(ticket.createdAt(), at, replyHours * 60L);
      replyDue = axis.dueAt(ticket.createdAt(), replyHours * 60L, ServiceTicketProcessSlaAxis.REPLY);
      if (replyDue == null) {
        // 🔴 「暂停中」≠「没超期」：暂停**不能抹掉**暂停之前就已经发生的超期 ——
        //   否则「先拖到超期、再点一下暂停」就成了洗白超时的手法。
        OffsetDateTime dueWithoutPause =
            axis.dueAtIgnoringOpenPauses(
                ticket.createdAt(), replyHours * 60L, ServiceTicketProcessSlaAxis.REPLY);
        replyState =
            axis.paused(ServiceTicketProcessSlaAxis.REPLY)
                ? (dueWithoutPause != null && at.isAfter(dueWithoutPause)
                    ? CrmServiceTicketProcessSlaState.BREACHED.name()
                    : CrmServiceTicketProcessSlaState.PAUSED.name())
                : CrmServiceTicketProcessSlaState.NO_CALENDAR.name();
      } else {
        OffsetDateTime completion = ticket.firstReplyAt();
        replyState =
            completion != null
                ? (completion.isAfter(replyDue)
                    ? CrmServiceTicketProcessSlaState.BREACHED.name()
                    : CrmServiceTicketProcessSlaState.MET.name())
                : (at.isAfter(replyDue)
                    ? CrmServiceTicketProcessSlaState.BREACHED.name()
                    : CrmServiceTicketProcessSlaState.PENDING.name());
      }
    }

    if (resolveHours == null) {
      resolveState = CrmServiceTicketProcessSlaState.NO_DEADLINE.name();
    } else if (!axis.configured()) {
      resolveState = CrmServiceTicketProcessSlaState.NO_CALENDAR.name();
    } else {
      resolveRemaining =
          axis.remainingSeconds(
              ticket.createdAt(), at, resolveHours * 60L, ServiceTicketProcessSlaAxis.RESOLVE);
      resolveWall = axis.wallClockRemainingSeconds(ticket.createdAt(), at, resolveHours * 60L);
      resolveDue =
          axis.dueAt(ticket.createdAt(), resolveHours * 60L, ServiceTicketProcessSlaAxis.RESOLVE);
      if (resolveDue == null) {
        OffsetDateTime dueWithoutPause =
            axis.dueAtIgnoringOpenPauses(
                ticket.createdAt(), resolveHours * 60L, ServiceTicketProcessSlaAxis.RESOLVE);
        resolveState =
            axis.paused(ServiceTicketProcessSlaAxis.RESOLVE)
                ? (dueWithoutPause != null && at.isAfter(dueWithoutPause)
                    ? CrmServiceTicketProcessSlaState.BREACHED.name()
                    : CrmServiceTicketProcessSlaState.PAUSED.name())
                : CrmServiceTicketProcessSlaState.NO_CALENDAR.name();
      } else {
        OffsetDateTime completion = ticket.closedAt();
        resolveState =
            completion != null
                ? (completion.isAfter(resolveDue)
                    ? CrmServiceTicketProcessSlaState.BREACHED.name()
                    : CrmServiceTicketProcessSlaState.MET.name())
                : (at.isAfter(resolveDue)
                    ? CrmServiceTicketProcessSlaState.BREACHED.name()
                    : CrmServiceTicketProcessSlaState.PENDING.name());
      }
    }

    String basis = ticket.slaBasis() == null ? "" : ticket.slaBasis();
    return new CrmServiceTicketProcessSla(
        basis,
        slaBasisLabel(basis),
        replyHours,
        resolveHours,
        replyDue,
        replyState,
        slaStateLabel(replyState),
        replyRemaining,
        replyWall,
        axis.paused(ServiceTicketProcessSlaAxis.REPLY),
        resolveDue,
        resolveState,
        slaStateLabel(resolveState),
        resolveRemaining,
        resolveWall,
        axis.paused(ServiceTicketProcessSlaAxis.RESOLVE),
        axis.scopeKey(),
        axis.timezone(),
        axis.configured(),
        slaNote(axis, replyState, resolveState));
  }

  private String slaNote(ServiceTicketProcessSlaAxis axis, String replyState, String resolveState) {
    if (!axis.configured()) {
      return "⚠️ 该作用域**未配置营业时间** ⇒ SLA 算不出来（NO_CALENDAR）。"
          + "本服务**不退回自然时间**：悄悄退回等于把「夜间积压被判逾期」的误判请回来，"
          + "而且现场看不出来。";
    }
    return "到期时刻在营业时间轴（时区 " + axis.timezone() + "）上累加得出，"
        + "并**扣掉了暂停区间里的营业时间**（暂停 ⇒ 计时器冻着）。"
        + "剩余秒数是**本次读取**算出来的，库里没有会自己过期的剩余列。"
        + "回复时限 " + replyState + " / 处理时限 " + resolveState + "。"
        + (replyState.startsWith("PAUSED") || resolveState.startsWith("PAUSED")
            ? "🔴 **暂停中**：计时器冻着 —— 既不是「还在走表」，也不是「已经超期」。"
                + "恢复之后到期时刻才能定下来。"
            : "");
  }

  private String slaNote(ServiceTicketProcessSlaAxis axis) {
    return slaNote(axis, "-", "-");
  }

  /** 当前可执行的处理动作（服务端算，页面不自己猜）。 */
  private List<String> availableActions(
      ServiceTicketFactsPort.TicketView head,
      TicketState state,
      ServiceTicketProcessSlaAxis axis,
      boolean resolutionConfirmed) {
    List<String> actions = new ArrayList<>();
    boolean closed = isClosed(head.ticketStatus());
    if (!closed) {
      actions.add("REPLY");
      actions.add("STATUS_CHANGE");
      actions.add("CLOSE");
      actions.add("ESCALATE");
      if (axis.configured() && state.openPauseId == null) {
        actions.add("SLA_PAUSE");
      }
    }
    actions.add("NOTE");
    actions.add("LINK_ORDER");
    actions.add("LINK_CASE");
    if (closed) {
      actions.add("RESOLUTION_CONFIRM");
      if (head.satisfactionRating() == null) {
        actions.add("SATISFACTION");
      }
    }
    if (state.openPauseId != null) {
      actions.add("SLA_RESUME");
    }
    if (resolutionConfirmed) {
      actions.add("RESOLUTION_REVOKE");
    }
    return actions;
  }

  /** 写一条处理记录（append-only）。 */
  private CrmServiceTicketProcessAction recordAction(
      UUID ticketId,
      CrmServiceTicketProcessActionType type,
      String fromStatus,
      String toStatus,
      String detail,
      String orderNo,
      String caseNo,
      String resolutionType,
      UUID actorId,
      OffsetDateTime occurredAt) {
    UUID id = UUID.randomUUID();
    sql.execute(
        "insert into crm_service_ticket_action"
            + "(id,tenant_id,ticket_id,action_type,from_status,to_status,detail,related_order_no,"
            + " related_case_no,resolution_type,actor_id,occurred_at)"
            + " values (?,?::uuid,?::uuid,?,?,?,?,?,?,?,?::uuid,?::timestamptz)",
        id,
        currentTenant(),
        ticketId,
        type.name(),
        fromStatus,
        toStatus,
        detail,
        orderNo,
        caseNo,
        resolutionType,
        actorId,
        occurredAt);
    return new CrmServiceTicketProcessAction(
        id,
        ticketId,
        type.name(),
        type.label(),
        fromStatus,
        toStatus,
        detail,
        orderNo,
        caseNo,
        resolutionType,
        actorId,
        occurredAt);
  }

  /** 组装动作结果（一个响应类型覆盖全部写动作）。 */
  private CrmServiceTicketProcessOutcome outcome(
      CrmServiceTicketProcessActionType type,
      String message,
      ServiceTicketFactsPort.TicketView head,
      CrmServiceTicketProcessNote note,
      CrmServiceTicketProcessAction action,
      CrmServiceTicketProcessResolution resolution,
      CrmServiceTicketProcessLink link,
      CrmServiceTicketProcessPauseState pause,
      CrmServiceTicketProcessSla sla,
      String escalatedCaseNo,
      String escalatedDisputeNo) {
    UUID ticketId = action.ticketId();
    // 处理侧状态只读一次：解决确认与否、关闭与否**各自独立**，
    // 但「是否已确认解决」要从本模块自己的表里判（上游没有这个概念）。
    TicketState state = loadStates(List.of(ticketId)).getOrDefault(ticketId, TicketState.EMPTY);
    boolean closed = head != null && isClosed(head.ticketStatus());
    boolean confirmed =
        resolution != null
            ? resolution.active()
            : state.resolutions.stream().anyMatch(CrmServiceTicketProcessResolution::active);
    return new CrmServiceTicketProcessOutcome(
        type.name(),
        type.label(),
        message,
        head == null ? null : head.ticketStatus(),
        head == null ? null : ticketStatusLabel(head.ticketStatus()),
        closed,
        confirmed,
        note,
        action,
        resolution,
        link,
        pause,
        sla,
        escalatedCaseNo,
        escalatedDisputeNo,
        "🔴 **关闭 ≠ 解决**：本响应里「已关闭」与「已确认解决」是**两个字段**，互不代表。");
  }

  /** 上游工单（拿不到就 404 / 503，**不编一张空工单**）。 */
  private ServiceTicketFactsPort.TicketView requireTicket(UUID ticketId, String bearer) {
    ServiceTicketFactsPort.TicketDetail detail = facts.ticketDetail(ticketId, bearer);
    if (detail == null || detail.head() == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "上游 trade 里没有这张工单");
    }
    return detail.head();
  }

  /** 读一条关联单据（不带上游事实）。 */
  private CrmServiceTicketProcessLink readLink(UUID ticketId, String linkType) {
    Record row =
        sql.fetchOne(
            "select id, ticket_id, link_type, order_id, order_no, case_id, case_no, link_source,"
                + " last_sync_note, last_synced_at, linked_by, linked_at"
                + " from crm_service_ticket_link where tenant_id = ?::uuid and ticket_id = ?::uuid"
                + " and link_type = ?",
            currentTenant(),
            ticketId,
            linkType);
    if (row == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "关联单据不存在");
    }
    return toLink(row);
  }

  /** 读一条解决确认。 */
  private CrmServiceTicketProcessResolution readResolution(UUID ticketId, UUID resolutionId) {
    Record row =
        sql.fetchOne(
            "select id, ticket_id, resolution_type, result_note, linked_case_id, linked_order_no,"
                + " confirmed_by, confirmed_at, revoked_at, revoked_by"
                + " from crm_service_ticket_resolution"
                + " where tenant_id = ?::uuid and ticket_id = ?::uuid and id = ?::uuid",
            currentTenant(),
            ticketId,
            resolutionId);
    if (row == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "解决确认不存在");
    }
    return toResolution(row);
  }

  private CrmServiceTicketProcessLink toLink(Record row) {
    String linkType = row.get("link_type", String.class);
    return new CrmServiceTicketProcessLink(
        row.get("id", UUID.class),
        row.get("ticket_id", UUID.class),
        linkType,
        "ORDER".equals(linkType) ? "关联订单" : "关联售后单",
        row.get("link_source", String.class),
        linkSourceLabel(row.get("link_source", String.class)),
        row.get("order_id", UUID.class),
        row.get("order_no", String.class),
        row.get("case_id", UUID.class),
        row.get("case_no", String.class),
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        row.get("linked_by", UUID.class),
        row.get("linked_at", OffsetDateTime.class),
        row.get("last_sync_note", String.class),
        row.get("last_synced_at", OffsetDateTime.class));
  }

  /**
   * 给关联单据补上上游的**当前事实**（现取，不复制一份）。
   *
   * <p>⚠️ 上游读不到时写明原因，<b>不显示空白</b> —— 空白会被客服读成
   * 「这一单没有信息」，而不是「我们没查到」。
   */
  private CrmServiceTicketProcessLink enrichLink(Record row, String bearer) {
    CrmServiceTicketProcessLink base = toLink(row);
    String factsNote = null;
    String orderStatus = null;
    String orderPaymentStatus = null;
    String orderFulfillmentStatus = null;
    String orderAftersalesStatus = null;
    String orderCurrency = null;
    BigDecimal orderAmount = null;
    String caseStatus = null;
    String caseType = null;
    String orderNoUpstream = null;
    if ("ORDER".equals(base.linkType()) && base.orderNo() != null) {
      try {
        ServiceTicketFactsPort.OrderView order = facts.orderByNo(base.orderNo(), bearer);
        if (order == null) {
          factsNote = "上游 trade 里没有与订单号 " + base.orderNo() + " 完全相等的订单。";
        } else {
          orderStatus = order.platformStatus();
          orderPaymentStatus = order.paymentStatus();
          orderFulfillmentStatus = order.fulfillmentStatus();
          orderAftersalesStatus = order.aftersalesStatus();
          orderCurrency = order.currency();
          orderAmount = order.grandTotalAmount();
          orderNoUpstream = order.orderNo();
        }
      } catch (ResponseStatusException e) {
        factsNote = "上游 trade 读不到订单事实：" + e.getReason();
      }
    } else if ("AFTER_SALES_CASE".equals(base.linkType()) && base.caseNo() != null) {
      try {
        ServiceTicketFactsPort.CaseView aftersales = facts.caseByNo(base.caseNo(), bearer);
        if (aftersales == null) {
          factsNote = "上游 trade 里没有与售后单号 " + base.caseNo() + " 完全相等的售后单。";
        } else {
          caseStatus = aftersales.status();
          caseType = aftersales.caseType();
        }
      } catch (ResponseStatusException e) {
        factsNote = "上游 trade 读不到售后单事实：" + e.getReason();
      }
    }
    return new CrmServiceTicketProcessLink(
        base.id(),
        base.ticketId(),
        base.linkType(),
        base.linkTypeLabel(),
        base.linkSource(),
        base.linkSourceLabel(),
        base.orderId(),
        base.orderNo(),
        base.caseId(),
        base.caseNo(),
        orderStatus,
        orderPaymentStatus,
        orderFulfillmentStatus,
        orderAftersalesStatus,
        orderCurrency,
        orderAmount,
        caseStatus,
        caseType,
        orderNoUpstream,
        factsNote,
        base.linkedBy(),
        base.linkedAt(),
        base.lastSyncNote(),
        base.lastSyncedAt());
  }

  private CrmServiceTicketProcessResolution toResolution(Record row) {
    String type = row.get("resolution_type", String.class);
    OffsetDateTime revokedAt = row.get("revoked_at", OffsetDateTime.class);
    return new CrmServiceTicketProcessResolution(
        row.get("id", UUID.class),
        row.get("ticket_id", UUID.class),
        type,
        resolutionTypeLabel(type),
        row.get("result_note", String.class),
        row.get("linked_case_id", UUID.class),
        row.get("linked_order_no", String.class),
        row.get("confirmed_by", UUID.class),
        row.get("confirmed_at", OffsetDateTime.class),
        revokedAt,
        row.get("revoked_by", UUID.class),
        revokedAt == null);
  }

  /** 记一次「关联单据更新」（**不假装推给了上游**）。 */
  private void touchLink(UUID ticketId, String note, OffsetDateTime at) {
    sql.execute(
        "update crm_service_ticket_link set last_sync_note = ?, last_synced_at = ?::timestamptz,"
            + " updated_at = ?::timestamptz where tenant_id = ?::uuid and ticket_id = ?::uuid",
        note,
        at,
        at,
        currentTenant(),
        ticketId);
  }

  /** 升级动作新建出来的售后单 ⇒ 记一条关联（关系，不复制对方的列）。 */
  private void upsertCaseLink(UUID ticketId, String caseNo, UUID actorId, OffsetDateTime at) {
    sql.execute(
        "insert into crm_service_ticket_link"
            +"(id,tenant_id,ticket_id,link_type,case_no,link_source,last_sync_note,last_synced_at,"
            + " linked_by,linked_at,updated_at)"
            + " values (?,?::uuid,?::uuid,'AFTER_SALES_CASE',?,'ESCALATION',?,?::timestamptz,?::uuid,"
            + "?::timestamptz,?::timestamptz)"
            + " on conflict (tenant_id, ticket_id, link_type) do update"
            + " set case_no = excluded.case_no, link_source = 'ESCALATION',"
            + " last_sync_note = excluded.last_sync_note, last_synced_at = excluded.last_synced_at,"
            + " linked_by = excluded.linked_by, linked_at = excluded.linked_at,"
            + " updated_at = excluded.updated_at",
        UUID.randomUUID(),
        currentTenant(),
        ticketId,
        caseNo,
        "升级时新建的售后单（下游 11 售后）",
        at,
        actorId,
        at,
        at);
  }

  /** 队列汇总（服务端真算）。 */
  private CrmServiceTicketProcessQueueSummary summarize(
      List<CrmServiceTicketProcessQueueRow> rows) {
    long open = 0;
    long inProgress = 0;
    long waitingBuyer = 0;
    long closed = 0;
    long paused = 0;
    long overdue = 0;
    long confirmed = 0;
    for (CrmServiceTicketProcessQueueRow row : rows) {
      String status = row.ticketStatus();
      if ("OPEN".equals(status)) {
        open++;
      } else if ("IN_PROGRESS".equals(status)) {
        inProgress++;
      } else if ("WAITING_BUYER".equals(status)) {
        waitingBuyer++;
      }
      if (row.closed()) {
        closed++;
      }
      if (row.pause().paused()) {
        paused++;
      }
      if (isOverdue(row.sla())) {
        overdue++;
      }
      if (row.resolutionConfirmed()) {
        confirmed++;
      }
    }
    return new CrmServiceTicketProcessQueueSummary(
        rows.size(), open, inProgress, waitingBuyer, closed, paused, overdue, confirmed);
  }

  private static boolean isOverdue(CrmServiceTicketProcessSla sla) {
    return CrmServiceTicketProcessSlaState.BREACHED.name().equals(sla.replyState());
  }

  private static boolean isClosed(String status) {
    return TERMINAL_STATUSES.contains(status);
  }

  /** 当前暂停状态（没暂停时 paused=false，但给出历史条数）。 */
  private CrmServiceTicketProcessPauseState pauseState(TicketState state) {
    if (state.openPauseId() == null) {
      return new CrmServiceTicketProcessPauseState(false, null, null, null, state.pauseCount());
    }
    return new CrmServiceTicketProcessPauseState(
        true, state.openPauseTarget(), state.openPauseAt(), state.openPauseReason(), state.pauseCount());
  }

  private long countOf(UUID ticketId, String table) {
    return sql.fetchOne(
            "select count(*) from " + table + " where tenant_id = ?::uuid and ticket_id = ?::uuid",
            currentTenant(),
            ticketId)
        .get(0, Long.class);
  }

  /** 分页窗口（offset / size / page）。 */
  private int[] pageWindow(Integer page, Integer size) {
    int p = Math.max(page == null ? 0 : page, 0);
    int s = size == null ? DEFAULT_PAGE_SIZE : Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    return new int[] {p * s, s, p};
  }

  private static String placeholders(int count) {
    StringBuilder builder = new StringBuilder();
    for (int i = 0; i < count; i++) {
      if (i > 0) {
        builder.append(",");
      }
      builder.append("?::uuid");
    }
    return builder.toString();
  }

  private static void putIfSet(Map<String, Object> body, String key, String value) {
    if (isSet(value)) {
      body.put(key, value);
    }
  }

  private static void putAmount(Map<String, Object> body, String key, BigDecimal value) {
    if (value != null) {
      body.put(key, value);
    }
  }

  private static String requireContent(String content, String what) {
    if (!isSet(content)) {
      throw badRequest(what + "不能为空");
    }
    String trimmed = content.trim();
    if (trimmed.length() > MAX_CONTENT_LENGTH) {
      throw badRequest(what + "太长了（上限 " + MAX_CONTENT_LENGTH + " 字）");
    }
    return trimmed;
  }

  private static String normalizeEnum(String value, List<String> allowed, String what) {
    if (!isSet(value)) {
      throw badRequest(what + "不能为空");
    }
    String upper = value.trim().toUpperCase(Locale.ROOT);
    if (!allowed.contains(upper)) {
      throw badRequest(what + "「" + value + "」不在允许的取值里：" + String.join(" / ", allowed));
    }
    return upper;
  }

  private static String normalizeSort(String sort) {
    if (!isSet(sort)) {
      return "sla_asc";
    }
    String upper = sort.trim().toLowerCase(Locale.ROOT);
    return SORTS.contains(upper) ? upper : "sla_asc";
  }

  private static int priorityRank(String priority) {
    return switch (priority == null ? "" : priority) {
      case "URGENT" -> 4;
      case "HIGH" -> 3;
      case "NORMAL" -> 2;
      case "LOW" -> 1;
      default -> 0;
    };
  }

  private static boolean isSet(String value) {
    return value != null && !value.isBlank();
  }

  private UUID currentTenant() {
    UUID tenant =
        sql.fetchOne("select nullif(current_setting('app.current_tenant', true),'')::uuid")
            .get(0, UUID.class);
    if (tenant == null) {
      throw new ResponseStatusException(
          HttpStatus.INTERNAL_SERVER_ERROR,
          "租户上下文未绑定：本模块的所有读写都在绑定 app.current_tenant 的事务里进行");
    }
    return tenant;
  }

  private static ResponseStatusException badRequest(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  // ================================================================ 标签

  private static String ticketTypeLabel(String type) {
    return switch (type == null ? "" : type) {
      case "INQUIRY" -> "咨询";
      case "COMPLAINT" -> "投诉";
      case "ORDER_CHASE" -> "催单";
      case "POLICY_CONSULT" -> "平台政策咨询";
      default -> "";
    };
  }

  private static String ticketStatusLabel(String status) {
    return switch (status == null ? "" : status) {
      case "OPEN" -> "待处理";
      case "IN_PROGRESS" -> "处理中";
      case "WAITING_BUYER" -> "待买家";
      case "RESOLVED" -> "已关闭";
      case "CANCELLED" -> "已取消";
      default -> "";
    };
  }

  private static String priorityLabel(String priority) {
    return switch (priority == null ? "" : priority) {
      case "LOW" -> "低";
      case "NORMAL" -> "普通";
      case "HIGH" -> "高";
      case "URGENT" -> "紧急";
      default -> "";
    };
  }

  private static String sourceChannelLabel(String source) {
    return switch (source == null ? "" : source) {
      case "MANUAL" -> "人工创建";
      case "MESSAGE" -> "来自消息";
      case "AUTOMATIC" -> "自动触发";
      default -> "";
    };
  }

  private static String closeReasonLabel(String reason) {
    return switch (reason == null ? "" : reason) {
      case "RESOLVED" -> "已解决";
      case "NO_RESPONSE" -> "买家未回应";
      case "DUPLICATE" -> "重复工单";
      case "CANCELLED" -> "与本单无关";
      case "ESCALATED" -> "已升级转单";
      case "ABANDONED" -> "不再跟进";
      default -> "";
    };
  }

  private static String resolutionTypeLabel(String type) {
    for (CrmServiceTicketProcessResolutionType value : CrmServiceTicketProcessResolutionType.values()) {
      if (value.name().equals(type)) {
        return value.label();
      }
    }
    return type;
  }

  private static String actionTypeLabel(String type) {
    for (CrmServiceTicketProcessActionType value : CrmServiceTicketProcessActionType.values()) {
      if (value.name().equals(type)) {
        return value.label();
      }
    }
    return type;
  }

  private static String replyChannelLabel(String channel) {
    return switch (channel == null ? "" : channel) {
      case "PLATFORM_MESSAGE" -> "平台站内信";
      case "EMAIL" -> "邮件";
      case "CHAT" -> "在线会话";
      case "PHONE" -> "电话";
      case "OTHER" -> "其它渠道";
      default -> "";
    };
  }

  private static String linkSourceLabel(String source) {
    return switch (source == null ? "" : source) {
      case "MANUAL" -> "人工关联";
      case "UPSTREAM" -> "工单自带";
      case "ESCALATION" -> "升级时新建";
      default -> "";
    };
  }

  private static String slaTargetLabel(String target) {
    return switch (target == null ? "" : target) {
      case "REPLY" -> "回复时限";
      case "RESOLVE" -> "处理时限";
      case "BOTH" -> "回复与处理时限";
      default -> "";
    };
  }

  private static String slaStateLabel(String state) {
    for (CrmServiceTicketProcessSlaState value : CrmServiceTicketProcessSlaState.values()) {
      if (value.name().equals(state)) {
        return value.label();
      }
    }
    return state;
  }

  private static String slaBasisLabel(String basis) {
    return switch (basis == null ? "" : basis) {
      case "PLATFORM_GIVEN" -> "平台给定";
      case "POLICY_SLA" -> "租户 SLA 策略";
      default -> "";
    };
  }
}
