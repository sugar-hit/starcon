package com.starcon.crm;

import static com.starcon.crm.ServiceTicketListModels.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 15.4.1.1 工单列表。
 *
 * <p>蓝图 15-crm-and-service.md：
 *
 * <blockquote>
 * 职责：客服工单列表：类型、优先级、状态、SLA 剩余、关联买家与订单、分配客服、转派与升级。
 * 输入：工单创建（自动/人工）；筛选条件。
 * 输出：工单列表 + SLA 预警。
 * 关系：上游 = 15.1.1.3、11.4.2.1；下游 = 15.4.1.2 处理。
 * <b>SLA 计时需按营业时间而非自然时间</b>，否则夜间积压会造成误判。
 * </blockquote>
 *
 * <p><b>三件事必须说清</b>：
 *
 * <ol>
 *   <li><b>工单事实不在本服务</b> —— 工单由 11.4.2.1（trade 服务）拥有，
 *       本模块经 {@link ServiceTicketFactsPort} <b>只读</b>授权 API：
 *       分页 / 筛选 / 排序都由上游执行（真分页，不是前端假分页）。</li>
 *   <li><b>SLA 按营业时间</b> —— 到期时刻在<b>营业时间轴</b>上累加
 *       （{@link BusinessHoursCalendar}），轴的形状读本服务自己的
 *       {@code crm_service_business_hours} / {@code crm_service_business_holiday}，
 *       <b>不写死常量</b>；没配 ⇒ 如实报 {@code NO_CALENDAR}，
 *       <b>不退回自然时间</b>。</li>
 *   <li><b>关联买家与订单不复制一份</b> —— 订单读上游 trade 的授权 API，
 *       买家读本服务自己的 {@code crm_message} / {@code crm_customer_contact}。</li>
 * </ol>
 *
 * <p><b>两条执行路径</b>（口径都在响应里写明）：
 *
 * <ul>
 *   <li><b>快路径</b> —— 没有营业时间口径的筛选、排序也不是「按 SLA 排」时，
 *       分页直接下推上游（{@code LIMIT/OFFSET + COUNT}），总数即上游总数；</li>
 *   <li><b>本地扫描路径</b> —— 需要按<b>营业时间口径</b>筛选（SLA 状态 / 是否逾期）
 *       或按营业时间剩余量排序时：上游的同名筛选走的是<b>自然时间</b>口径，
 *       直接下推会漏掉「自然时间还没到、营业时间已经超了」的单（夜间积压正是这类），
 *       所以本服务把上游结果<b>整批取回</b>后按营业时间口径自己判、自己排、自己分页。</li>
 * </ul>
 */
@Service
public class ServiceTicketListService {

  /** 列表分页上限（真分页的上限，防止一次拉爆）。 */
  static final int MAX_PAGE_SIZE = 50;

  /** 默认页长。 */
  static final int DEFAULT_PAGE_SIZE = 20;

  /** 租户默认营业时间作用域。 */
  static final String DEFAULT_SCOPE = "DEFAULT";

  private static final List<String> TICKET_TYPES =
      List.of("INQUIRY", "COMPLAINT", "ORDER_CHASE", "POLICY_CONSULT");
  private static final List<String> TICKET_STATUSES =
      List.of("OPEN", "IN_PROGRESS", "WAITING_BUYER", "RESOLVED", "CANCELLED");
  private static final List<String> PRIORITIES = List.of("LOW", "NORMAL", "HIGH", "URGENT");
  private static final List<String> SOURCE_CHANNELS = List.of("MANUAL", "MESSAGE", "AUTOMATIC");
  private static final List<String> SORTS =
      List.of("sla_asc", "created_desc", "priority_desc", "assigned_asc");

  private final DSLContext sql;
  private final ServiceTicketFactsPort facts;

  public ServiceTicketListService(DSLContext sql, ServiceTicketFactsPort facts) {
    this.sql = sql;
    this.facts = facts;
  }

  // ================================================================ 能力声明

  /**
   * 能力声明：前端在渲染前就知道哪些能力有、哪些没有，而不是靠试。
   *
   * <p>🔴 刻意如实报出下游缺口：**15.4.1.2 工单处理未交付** ⇒
   * {@code ticket_processing} 这一维 {@code available=false}，
   * ownerModule 指向 15.4.1.2 —— 本模块是<b>只读列表</b>，
   * <b>不提供</b>回复 / 备注 / 状态流转 / 解决确认 / 满意度回收 / SLA 暂停恢复，
   * 也<b>不指向</b>任何不存在的入口。
   */
  public CrmServiceTicketCapabilities capabilities() {
    List<CrmServiceTicketCapabilityDimension> dimensions =
        List.of(
            new CrmServiceTicketCapabilityDimension(
                "ticket_facts",
                "工单事实（类型 / 优先级 / 状态 / 分配客服 / 转派 / 升级）",
                true,
                "上游 trade 11.4.2.1 工单管理（V270 aftersales_service_ticket 及其四张附属事实表），"
                    + "经授权 HTTP API 只读（GET /trade/service-tickets）",
                "11.4.2.1",
                "工单是 trade 侧那张表的唯一写入所有者；本服务**不建工单表**、"
                    + "不建跨库外键、不建共享视图、不直连对方库 —— "
                    + "分页 / 筛选 / 排序全部由上游执行（真分页）"),
            new CrmServiceTicketCapabilityDimension(
                "business_hours_sla",
                "🔴 SLA 剩余（按营业时间计时）",
                true,
                "本服务自己的 crm_service_business_hours / crm_service_business_holiday（V306）+ "
                    + "BusinessHoursCalendar（到期时刻沿营业时间轴累加）",
                "",
                "到期时刻 = 在营业时间轴上累加，**不是** createdAt + N 小时；"
                    + "夜间积压不会被自然时间立刻判逾期。租户未配营业时间时状态为 "
                    + "NO_CALENDAR，**不退回自然时间** —— 悄悄退回等于把误判请回来且看不出来"),
            new CrmServiceTicketCapabilityDimension(
                "related_order",
                "关联订单（订单号 / 平台 / 金额 / 状态）",
                true,
                "上游 trade 9.1.1.1 全渠道统一订单列表，经授权 HTTP API 只读"
                    + "（GET /trade/sales-orders/list）；订单号经「工单 → 售后单 → 订单」取得",
                "9.1.1.1",
                "查不到时该行显示「订单上下文不可用 + 原因」，**不显示空白** —— "
                    + "空白会被客服读成「这一单没有信息」，而不是「我们没查到」"),
            new CrmServiceTicketCapabilityDimension(
                "related_buyer",
                "关联买家",
                true,
                "本服务自己的 crm_message.buyer_reference / crm_customer_contact.customer_ref；"
                    + "买家名与买家档案 id 取自上游订单（trade 侧事实）",
                "",
                "本仓**没有买家档案**（15.3.1.1 客户列表未交付），"
                    + "所以买家标识只是**逻辑引用**，不是主数据；订单事实不复制一份"),
            new CrmServiceTicketCapabilityDimension(
                "transfer_history",
                "转派历史与升级去向",
                true,
                "上游工单详情里的转派留痕（GET /trade/service-tickets/{id} → assignments，append-only）"
                    + "与升级去向字段（escalated_case_no / escalated_dispute_no）",
                "11.4.2.1",
                "转派历史**读上游的既有事实**，不另造一套转派模型；"
                    + "上游读不到时该行标 historyAvailable=false 并写明原因，**不编一个 0**"),
            new CrmServiceTicketCapabilityDimension(
                "sla_alert",
                "SLA 预警（临期 / 逾期计数）",
                true,
                "本服务按营业时间口径现算（列表汇总里的 overdue）",
                "",
                "预警口径与行内剩余量**同源**（同一条轴、同一参照时刻），"
                    + "不会出现「列表说还有 3 小时、汇总说已经逾期」"),
            new CrmServiceTicketCapabilityDimension(
                "ticket_processing",
                "工单处理（回复 / 备注 / 状态流转 / 解决确认 / 满意度回收 / SLA 暂停恢复）",
                false,
                "15.4.1.2 工单处理**未交付**",
                "15.4.1.2",
                "本模块是**只读列表**：不提供处理入口，也不指向任何不存在的入口 —— "
                    + "工单的写动作仍然只在 trade 服务（11.4.2.1）"),
            new CrmServiceTicketCapabilityDimension(
                "ticket_write",
                "工单创建（人工 / 来自消息 / 自动触发）",
                false,
                "工单创建由上游 trade 11.4.2.1 拥有；本模块不重造建单入口",
                "11.4.2.1",
                "本模块只读上游已建好的工单；**不写**工单表，也不代上游建单"),
            new CrmServiceTicketCapabilityDimension(
                "message_classification",
                "消息分类（15.1.1.3）",
                true,
                "本服务 V301 crm_message_classification 已交付；工单 source_channel='MESSAGE' 时"
                    + "其 source_ref 是调用方给的引用串",
                "15.1.1.3",
                "本模块**不重造**分类，也不改写工单；只在补全买家时按引用串关联本服务的消息记录"));
    List<CrmServiceTicketRelatedModule> related =
        List.of(
            new CrmServiceTicketRelatedModule(
                "11.4.2.1", "工单管理（trade 服务）", true, null,
                "已交付：工单事实的**唯一所有者**，本模块经授权 HTTP API 只读", null),
            new CrmServiceTicketRelatedModule(
                "15.1.1.3", "消息分类（crm 服务）", true, null, "已交付（V301）", null),
            new CrmServiceTicketRelatedModule(
                "9.1.1.1", "全渠道统一订单列表（trade 服务）", true, null,
                "已交付：关联订单的事实来源，经授权 HTTP API 只读", null),
            new CrmServiceTicketRelatedModule(
                "15.4.1.2", "工单处理", false, "15.4.1.2",
                "**未交付**：本模块是只读列表，处理动作（回复 / 备注 / 状态流转 / 解决确认 / "
                    + "满意度回收 / SLA 暂停恢复）由它负责；本模块**不指向**不存在的入口",
                null),
            new CrmServiceTicketRelatedModule(
                "15.3.1.1", "客户列表（买家档案）", false, "15.3.1.1",
                "**未交付**：本仓没有买家档案，买家标识只是逻辑引用", null));
    List<CrmServiceTicketBoundary> boundaries =
        List.of(
            new CrmServiceTicketBoundary(
                "不建工单表、不建跨库外键、不建共享视图、不直连 trade 库",
                null,
                "AGENTS.md 模块边界纪律：跨服务只经授权 API / event"),
            new CrmServiceTicketBoundary(
                "不提供任何工单处理动作（回复 / 备注 / 状态流转 / 解决确认 / 满意度 / SLA 暂停恢复）",
                "15.4.1.2",
                "15.4.1.2 未交付；工单的写动作仍然只在 trade 服务"),
            new CrmServiceTicketBoundary(
                "不在库里存会自己过期的「剩余小时数」",
                null,
                "剩余秒数是读取时刻的派生态（库里那一列写下去就开始自己过期）"),
            new CrmServiceTicketBoundary(
                "不把营业时间写死成常量",
                null,
                "营业时段 / 时区 / 节假日例外是租户自己的配置，读本服务自己的配置表"));
    return new CrmServiceTicketCapabilities(
        dimensions,
        related,
        boundaries,
        TICKET_TYPES,
        TICKET_STATUSES,
        PRIORITIES,
        SOURCE_CHANNELS,
        List.of("NO_DEADLINE", "NO_CALENDAR", "PENDING", "MET", "BREACHED"),
        SORTS,
        "SLA 时限来源沿用上游口径：PLATFORM_GIVEN 平台给定 / POLICY_SLA 租户 SLA 策略；"
            + "本模块只把**计时**换成营业时间轴，**不另立一套时限来源**。",
        "上游事实来源：trade 11.4.2.1（GET /trade/service-tickets，GET /trade/service-tickets/{id}）"
            + "与 trade 9.1.1.1（GET /trade/sales-orders/list）；"
            + "转发的是**调用方自己的** Authorization bearer，租户由上游按已认证成员关系解析并强制 RLS。",
        "下游 15.4.1.2 工单处理**未交付**：本模块只读，不提供处理动作。");
  }

  // ================================================================ 列表

  /**
   * 工单列表（真分页 + 真筛选 + 营业时间口径的 SLA 剩余 + 关联买家 / 订单 / 转派与升级）。
   *
   * @param query 查询条件
   * @param bearer 调用方自己的 Authorization bearer（转发给上游，租户由上游解析）
   */
  public CrmServiceTicketPage list(CrmServiceTicketListQuery query, String bearer) {
    OffsetDateTime at = OffsetDateTime.now();
    int page = Math.max(query.page() == null ? 0 : query.page(), 0);
    int size = query.size() == null ? DEFAULT_PAGE_SIZE : Math.min(Math.max(query.size(), 1), MAX_PAGE_SIZE);
    String sort = normalizeSort(query.sort());
    boolean enrich = query.enrich() == null || query.enrich();

    // 营业时间口径：请求指定的店铺作用域优先，没指定（或该作用域没配）就用租户默认口径。
    // ⚠️ **整个请求用同一条轴** —— 筛选与展示必须同源，
    //   否则会出现「按 A 店口径筛出来的单，行里却按 B 店口径显示剩余量」。
    BusinessHoursCalendar calendar = calendarFor(query.shopId());
    String scopeKey = calendar.scopeKey();

    boolean localSlaFilter = isSet(query.slaState()) || Boolean.TRUE.equals(query.overdueOnly());
    boolean localSort = "sla_asc".equals(sort);

    List<ServiceTicketFactsPort.TicketView> candidates;
    long total;
    boolean totalExact = true;
    long scanned = 0;
    boolean truncated = false;
    long upstreamTotal;
    ServiceTicketFactsPort.TicketSummary upstreamSummary = null;

    if (localSlaFilter || localSort) {
      // 本地扫描路径：营业时间口径的判定只能在本服务做（轴在本服务的库里）。
      List<ServiceTicketFactsPort.TicketView> all = new ArrayList<>();
      upstreamTotal = 0;
      for (int p = 0; p < ServiceTicketFactsPort.MAX_PAGES; p++) {
        ServiceTicketFactsPort.TicketPage chunk =
            facts.tickets(
                query.status(), query.ticketType(), query.priority(), query.sourceChannel(),
                query.assignedToRef(), query.caseId(), query.unassignedOnly(), query.from(),
                query.to(), query.keyword(), null, p, ServiceTicketFactsPort.PAGE_SIZE, bearer);
        all.addAll(chunk.rows());
        upstreamTotal = chunk.total();
        upstreamSummary = chunk.summary();
        if (all.size() >= chunk.total() || chunk.rows().isEmpty()) {
          break;
        }
      }
      scanned = all.size();
      truncated = scanned < upstreamTotal;
      candidates = all;
      total = all.size();
    } else {
      // 快路径：分页 / 筛选 / 排序全部下推上游（真分页）。
      ServiceTicketFactsPort.TicketPage chunk =
          facts.tickets(
              query.status(), query.ticketType(), query.priority(), query.sourceChannel(),
              query.assignedToRef(), query.caseId(), query.unassignedOnly(), query.from(),
              query.to(), query.keyword(), sort, page, size, bearer);
      candidates = chunk.rows();
      total = chunk.total();
      upstreamTotal = chunk.total();
      upstreamSummary = chunk.summary();
      scanned = chunk.rows().size();
    }

    // 行内 SLA：按营业时间口径现算（读取时刻的派生态）。
    List<RowWithSla> decorated = new ArrayList<>(candidates.size());
    for (ServiceTicketFactsPort.TicketView ticket : candidates) {
      decorated.add(new RowWithSla(ticket, computeSla(ticket, calendar, at)));
    }

    // 本地筛选：SLA 状态 / 是否逾期（营业时间口径）。
    // ⚠️ SLA 状态筛选**只判回复时限**（与上游的 slaState 筛选同口径）：
    //   上游筛的是 `aftersales_ticket_sla_state(t.reply_due_at, t.first_reply_at, at)`。
    //   两边都判「回复 or 处理」的话，同一个筛选词在两个服务里会给出不同的单集。
    List<RowWithSla> filtered = decorated;
    if (isSet(query.slaState())) {
      String wanted = query.slaState().trim().toUpperCase(Locale.ROOT);
      filtered = new ArrayList<>();
      for (RowWithSla row : decorated) {
        if (wanted.equals(row.sla().replyState())) {
          filtered.add(row);
        }
      }
    }
    if (Boolean.TRUE.equals(query.overdueOnly())) {
      List<RowWithSla> keep = new ArrayList<>();
      for (RowWithSla row : filtered) {
        if (isOverdue(row.sla())) {
          keep.add(row);
        }
      }
      filtered = keep;
    }

    if (localSlaFilter) {
      total = filtered.size();
      totalExact = !truncated;
    }

    // 排序：本地扫描路径由本服务排（快路径由上游排，两边口径一致）。
    if (localSlaFilter || localSort) {
      sortRows(filtered, sort);
    }

    // 分页：快路径已经在上游分过页了；本地扫描路径在这里分。
    List<RowWithSla> pageRows;
    if (localSlaFilter || localSort) {
      int from = Math.min(page * size, filtered.size());
      int to = Math.min(from + size, filtered.size());
      pageRows = filtered.subList(from, to);
    } else {
      pageRows = filtered;
    }

    // 补全：关联订单（上游事实）· 关联买家（本服务自己的表）· 转派历史（上游留痕）。
    List<CrmServiceTicketRow> rows = new ArrayList<>(pageRows.size());
    for (RowWithSla row : pageRows) {
      rows.add(toRow(row, calendar, enrich, bearer));
    }

    CrmServiceTicketSummary summary =
        summarize(
            localSlaFilter || localSort ? filtered : null,
            upstreamSummary,
            localSlaFilter || localSort);

    return new CrmServiceTicketPage(
        rows,
        page,
        size,
        total,
        totalExact,
        scanned,
        truncated,
        summary,
        at,
        localSlaFilter || localSort
            ? "真分页：上游整批取回后由本服务按**营业时间口径**筛选 / 排序 / 分页（"
                + "LIMIT/OFFSET 语义不变）；共扫描 " + scanned + " 张工单。".concat(
                    truncated ? "⚠️ 已达扫描上限（" + ServiceTicketFactsPort.MAX_PAGES + " 页 × "
                        + ServiceTicketFactsPort.PAGE_SIZE + "），总数是**下界**，不是全量。"
                        : "")
            : "真分页：分页 / 筛选 / 排序全部下推上游（GET /trade/service-tickets，LIMIT/OFFSET + 独立 COUNT），"
                + "总数即上游总数，不受翻页影响。",
        "🔴 SLA 剩余按**营业时间**计时：到期时刻在营业时间轴上累加（不是 createdAt + N 小时）。"
            + "本次口径作用域 = " + scopeKey + "（时区 " + (calendar.timezone() == null ? "未配置" : calendar.timezone())
            + "）。参照时刻 = " + at + "，整页共用一个。"
            + (calendar.configured()
                ? ""
                : " ⚠️ 该作用域**未配置营业时间** ⇒ SLA 状态一律 NO_CALENDAR（算不出来），"
                    + "本服务**不退回自然时间**。"),
        "工单事实来自上游 trade 11.4.2.1（经授权 HTTP API 只读，转发调用方自己的 bearer，"
            + "租户由上游按已认证成员关系解析并强制 RLS）；本服务**不建工单表**。",
        enrich
            ? "关联订单 = 上游 trade 订单事实（经售后单取到订单号）；关联买家 = 本服务自己的 "
                + "crm_message / crm_customer_contact；转派历史 = 上游工单详情里的派单留痕。"
            : "本次请求 enrich=false：**不补全**关联买家 / 订单 / 转派历史（只读列表，不逐行回上游取详情）。");
  }

  /** 一行工单 + 它按营业时间口径算出来的 SLA。 */
  private record RowWithSla(ServiceTicketFactsPort.TicketView ticket, CrmServiceTicketSla sla) {}

  /** 逾期判定：回复时限已过且尚未首次回复（营业时间口径）。 */
  private static boolean isOverdue(CrmServiceTicketSla sla) {
    return "BREACHED".equals(sla.replyState())
        || ("PENDING".equals(sla.replyState()) && sla.replyBusinessSecondsRemaining() < 0);
  }

  // ================================================================ 单张工单

  /**
   * 单张工单（含转派历史）。
   *
   * <p>⚠️ 详情里的转派历史来自上游的 append-only 留痕表，本模块**不重算、不重造**。
   */
  public CrmServiceTicketRow detail(UUID ticketId, String bearer) {
    ServiceTicketFactsPort.TicketDetail detail = facts.ticketDetail(ticketId, bearer);
    if (detail == null || detail.head() == null) {
      throw new ResponseStatusException(
          org.springframework.http.HttpStatus.NOT_FOUND, "工单不存在或不属于当前租户");
    }
    OffsetDateTime at = OffsetDateTime.now();
    BusinessHoursCalendar calendar = calendarFor(null);
    RowWithSla row = new RowWithSla(detail.head(), computeSla(detail.head(), calendar, at));
    return toRow(row, calendar, true, bearer);
  }

  // ================================================================ 营业时间口径

  /**
   * 读某个作用域的营业时间口径（时段 + 节假日）。
   *
   * <p>⚠️ 店铺作用域没配过时<b>回落到租户默认口径</b>，并如实说明回落到了哪一条。
   */
  public CrmBusinessHoursConfig businessHours(UUID shopId) {
    String scopeKey = scopeKeyOf(shopId);
    CrmBusinessHoursConfig config = readScope(scopeKey);
    if (!config.configured() && !DEFAULT_SCOPE.equals(scopeKey)) {
      CrmBusinessHoursConfig fallback = readScope(DEFAULT_SCOPE);
      return new CrmBusinessHoursConfig(
          fallback.scopeKey(),
          fallback.scopeType(),
          fallback.shopId(),
          fallback.timezone(),
          fallback.configured(),
          fallback.windows(),
          fallback.holidays(),
          fallback.updatedBy(),
          fallback.updatedAt(),
          fallback.auditId(),
          "⚠️ 店铺作用域 " + scopeKey + " **未配置**营业时间 ⇒ 已回落到**租户默认口径**（"
              + DEFAULT_SCOPE + "）。");
    }
    return config;
  }

  /** 读某个作用域的节假日例外。 */
  public CrmBusinessHoursConfig businessHolidays(UUID shopId) {
    return businessHours(shopId);
  }

  /**
   * 改写某个作用域的营业时段（<b>整段替换</b>）。
   *
   * <p>⚠️ 每次改写都留一条<b>只追加</b>的审计（{@code crm_service_business_hours_audit}），
   * 记的是<b>改完之后的口径快照</b> —— 营业时间直接决定客服绩效口径，
   * 「谁在什么时候把营业时间从九点改到八点」必须查得到。
   */
  public CrmBusinessHoursConfig updateBusinessHours(CrmBusinessHoursInput input, UUID actorId) {
    String scopeKey = scopeKeyOf(input.shopId());
    String timezone = input.timezone() == null ? "" : input.timezone().trim();
    if (timezone.isEmpty()) {
      throw new ResponseStatusException(
          org.springframework.http.HttpStatus.BAD_REQUEST, "时区必填（IANA 名，如 Asia/Shanghai）");
    }
    try {
      ZoneId.of(timezone);
    } catch (RuntimeException e) {
      throw new ResponseStatusException(
          org.springframework.http.HttpStatus.BAD_REQUEST, "时区名非法：" + timezone);
    }
    List<CrmBusinessHourWindow> windows =
        input.windows() == null ? List.of() : input.windows();
    for (CrmBusinessHourWindow window : windows) {
      if (window.weekday() < 1 || window.weekday() > 7) {
        throw new ResponseStatusException(
            org.springframework.http.HttpStatus.BAD_REQUEST,
            "工作日必须是 ISO-8601 的 1..7（1=周一），收到 " + window.weekday());
      }
      if (window.startTime() == null
          || window.endTime() == null
          || !window.startTime().isBefore(window.endTime())) {
        throw new ResponseStatusException(
            org.springframework.http.HttpStatus.BAD_REQUEST,
            "营业区间的起点必须早于终点（" + window.startTime() + " → " + window.endTime() + "）");
      }
    }
    UUID tenant = currentTenant();
    // ⚠️ 重叠区间**先在服务层拦下来**（400 + 人话）：
    //   重叠的区间会把同一段时间算两遍，SLA 到期时刻被提前（客服被冤枉逾期）。
    //   DB 层的触发器（guard_crm_service_business_hours_scope）是第二道闸，
    //   绕开服务层直连 SQL 时它仍然拦得住。
    List<CrmBusinessHourWindow> enabled = new ArrayList<>();
    for (CrmBusinessHourWindow window : windows) {
      if (window.enabled()) {
        enabled.add(window);
      }
    }
    for (int i = 0; i < enabled.size(); i++) {
      for (int j = i + 1; j < enabled.size(); j++) {
        CrmBusinessHourWindow a = enabled.get(i);
        CrmBusinessHourWindow b = enabled.get(j);
        if (a.weekday() == b.weekday()
            && a.startTime().isBefore(b.endTime())
            && b.startTime().isBefore(a.endTime())) {
          throw new ResponseStatusException(
              org.springframework.http.HttpStatus.BAD_REQUEST,
              "同一工作日的营业区间互相重叠（第 "
                  + a.weekday()
                  + " 个工作日 "
                  + a.startTime()
                  + "-"
                  + a.endTime()
                  + " 与 "
                  + b.startTime()
                  + "-"
                  + b.endTime()
                  + "）：重叠的区间会把同一段时间算两遍，SLA 到期时刻会被提前。");
        }
      }
    }
    sql.execute(
        "delete from crm_service_business_hours where tenant_id = ?::uuid and scope_key = ?",
        tenant,
        scopeKey);
    for (CrmBusinessHourWindow window : windows) {
      sql.execute(
          "insert into crm_service_business_hours"
              + "(id,tenant_id,scope_key,weekday,start_time,end_time,timezone,enabled,"
              + " created_by,updated_by,created_at,updated_at)"
              + " values (?,?::uuid,?,?,?::time,?::time,?,?,?,?,?::timestamptz,?::timestamptz)",
          UUID.randomUUID(),
          tenant,
          scopeKey,
          window.weekday(),
          window.startTime(),
          window.endTime(),
          timezone,
          window.enabled(),
          actorId,
          actorId,
          OffsetDateTime.now(),
          OffsetDateTime.now());
    }
    UUID auditId = writeAudit(scopeKey, "UPSERT_WINDOWS", windowsPayload(windows, timezone), actorId);
    CrmBusinessHoursConfig config = readScope(scopeKey);
    return new CrmBusinessHoursConfig(
        config.scopeKey(),
        config.scopeType(),
        config.shopId(),
        config.timezone(),
        config.configured(),
        config.windows(),
        config.holidays(),
        config.updatedBy(),
        config.updatedAt(),
        auditId,
        "营业时段已改写并留痕（变更后共 " + windows.size() + " 段，时区 " + timezone + "）。");
  }

  /** 改写某个作用域的节假日例外（<b>整段替换</b>）。 */
  public CrmBusinessHoursConfig updateBusinessHolidays(
      CrmBusinessHolidayInput input, UUID actorId) {
    String scopeKey = scopeKeyOf(input.shopId());
    List<CrmBusinessHoliday> holidays = input.holidays() == null ? List.of() : input.holidays();
    UUID tenant = currentTenant();
    sql.execute(
        "delete from crm_service_business_holiday where tenant_id = ?::uuid and scope_key = ?",
        tenant,
        scopeKey);
    for (CrmBusinessHoliday holiday : holidays) {
      if (holiday.date() == null) {
        throw new ResponseStatusException(
            org.springframework.http.HttpStatus.BAD_REQUEST, "节假日日期必填");
      }
      sql.execute(
          "insert into crm_service_business_holiday"
              + "(id,tenant_id,scope_key,holiday_date,label,closed,created_by,updated_by,"
              + " created_at,updated_at)"
              + " values (?,?::uuid,?,?::date,?,?,?,?,?::timestamptz,?::timestamptz)",
          UUID.randomUUID(),
          tenant,
          scopeKey,
          holiday.date(),
          holiday.label() == null ? "" : holiday.label(),
          holiday.closed(),
          actorId,
          actorId,
          OffsetDateTime.now(),
          OffsetDateTime.now());
    }
    UUID auditId =
        writeAudit(scopeKey, "UPSERT_HOLIDAYS", holidaysPayload(holidays), actorId);
    CrmBusinessHoursConfig config = readScope(scopeKey);
    return new CrmBusinessHoursConfig(
        config.scopeKey(),
        config.scopeType(),
        config.shopId(),
        config.timezone(),
        config.configured(),
        config.windows(),
        config.holidays(),
        config.updatedBy(),
        config.updatedAt(),
        auditId,
        "节假日例外已改写并留痕（变更后共 " + holidays.size() + " 条）。");
  }

  /** 作用域键：DEFAULT / SHOP:<uuid>。 */
  static String scopeKeyOf(UUID shopId) {
    return shopId == null ? DEFAULT_SCOPE : "SHOP:" + shopId;
  }

  /** 从作用域键里还原店铺 id（不是店铺作用域时返回 null）。 */
  static UUID shopIdOf(String scopeKey) {
    if (scopeKey == null || !scopeKey.startsWith("SHOP:")) {
      return null;
    }
    try {
      return UUID.fromString(scopeKey.substring("SHOP:".length()));
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  /** 读一个作用域的完整口径。 */
  private CrmBusinessHoursConfig readScope(String scopeKey) {
    List<Record> windowRows =
        sql.fetch(
            "select weekday, start_time, end_time, timezone, enabled, updated_by, updated_at"
                + " from crm_service_business_hours"
                + " where tenant_id = ?::uuid and scope_key = ?"
                + " order by weekday, start_time",
            currentTenant(),
            scopeKey);
    List<Record> holidayRows =
        sql.fetch(
            "select holiday_date, label, closed from crm_service_business_holiday"
                + " where tenant_id = ?::uuid and scope_key = ? order by holiday_date",
            currentTenant(),
            scopeKey);
    List<CrmBusinessHourWindow> windows = new ArrayList<>();
    String timezone = null;
    UUID updatedBy = null;
    OffsetDateTime updatedAt = null;
    for (Record row : windowRows) {
      windows.add(
          new CrmBusinessHourWindow(
              row.get("weekday", Integer.class),
              row.get("start_time", LocalTime.class),
              row.get("end_time", LocalTime.class),
              Boolean.TRUE.equals(row.get("enabled", Boolean.class))));
      if (timezone == null) {
        timezone = row.get("timezone", String.class);
        updatedBy = row.get("updated_by", UUID.class);
        updatedAt = row.get("updated_at", OffsetDateTime.class);
      }
    }
    List<CrmBusinessHoliday> holidays = new ArrayList<>();
    for (Record row : holidayRows) {
      holidays.add(
          new CrmBusinessHoliday(
              row.get("holiday_date", LocalDate.class),
              row.get("label", String.class),
              Boolean.TRUE.equals(row.get("closed", Boolean.class))));
    }
    Record audit =
        sql.fetchOne(
            "select id, occurred_at from crm_service_business_hours_audit"
                + " where tenant_id = ?::uuid and scope_key = ? order by occurred_at desc limit 1",
            currentTenant(),
            scopeKey);
    UUID shopId = shopIdOf(scopeKey);
    return new CrmBusinessHoursConfig(
        scopeKey,
        shopId == null ? "TENANT_DEFAULT" : "SHOP",
        shopId,
        timezone,
        !windows.isEmpty(),
        windows,
        holidays,
        updatedBy,
        updatedAt,
        audit == null ? null : audit.get("id", UUID.class),
        scopeKey.equals(DEFAULT_SCOPE)
            ? "租户默认营业时间口径（作用于所有未单独配置的店铺）。"
            : "店铺营业时间口径（作用域 " + scopeKey + "）。");
  }

  /** 取某个作用域的轴（没配则回落到租户默认）。 */
  private BusinessHoursCalendar calendarFor(UUID shopId) {
    String scopeKey = scopeKeyOf(shopId);
    BusinessHoursCalendar calendar = calendarOf(scopeKey);
    if (!calendar.configured() && !DEFAULT_SCOPE.equals(scopeKey)) {
      calendar = calendarOf(DEFAULT_SCOPE);
    }
    return calendar;
  }

  /** 读一个作用域的轴（<b>不回落</b>：回落与否由调用方决定）。 */
  private BusinessHoursCalendar calendarOf(String scopeKey) {
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

  /** 写一条只追加的审计。 */
  private UUID writeAudit(String scopeKey, String action, Object payload, UUID actorId) {
    UUID id = UUID.randomUUID();
    try {
      sql.execute(
          "insert into crm_service_business_hours_audit"
              + "(id,tenant_id,scope_key,action,payload,actor_id,occurred_at)"
              + " values (?,?::uuid,?,?,?::jsonb,?::uuid,?::timestamptz)",
          id,
          currentTenant(),
          scopeKey,
          action,
          new ObjectMapper().writeValueAsString(payload),
          actorId,
          OffsetDateTime.now());
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
    return id;
  }

  private Map<String, Object> windowsPayload(List<CrmBusinessHourWindow> windows, String timezone) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("timezone", timezone);
    List<Map<String, Object>> rows = new ArrayList<>();
    for (CrmBusinessHourWindow window : windows) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("weekday", window.weekday());
      row.put("startTime", window.startTime().toString());
      row.put("endTime", window.endTime().toString());
      row.put("enabled", window.enabled());
      rows.add(row);
    }
    payload.put("windows", rows);
    return payload;
  }

  private Map<String, Object> holidaysPayload(List<CrmBusinessHoliday> holidays) {
    Map<String, Object> payload = new LinkedHashMap<>();
    List<Map<String, Object>> rows = new ArrayList<>();
    for (CrmBusinessHoliday holiday : holidays) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("date", holiday.date().toString());
      row.put("label", holiday.label());
      row.put("closed", holiday.closed());
      rows.add(row);
    }
    payload.put("holidays", rows);
    return payload;
  }

  // ================================================================ SLA 计算

  /**
   * 按<b>营业时间轴</b>算一张工单的 SLA（读取时刻的派生态）。
   *
   * <p>⚠️ 与上游的自然时间判定<b>逐条对齐判据</b>（回复时限看 first_reply_at，
   * 处理时限看 closed_at），只把「到期时刻」换成营业时间轴上的累加结果 ——
   * 判据不一样的话，同一张单在两个服务里会给出两个结论。
   */
  private CrmServiceTicketSla computeSla(
      ServiceTicketFactsPort.TicketView ticket, BusinessHoursCalendar calendar, OffsetDateTime at) {
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
      replyState = CrmServiceTicketSlaState.NO_DEADLINE.name();
    } else if (!calendar.configured()) {
      replyState = CrmServiceTicketSlaState.NO_CALENDAR.name();
    } else {
      replyDue = calendar.addBusinessMinutes(ticket.createdAt(), replyHours * 60L);
      if (replyDue == null) {
        replyState = CrmServiceTicketSlaState.NO_CALENDAR.name();
      } else {
        OffsetDateTime completion = ticket.firstReplyAt();
        replyState =
            completion != null
                ? (completion.isAfter(replyDue)
                    ? CrmServiceTicketSlaState.BREACHED.name()
                    : CrmServiceTicketSlaState.MET.name())
                : (at.isAfter(replyDue)
                    ? CrmServiceTicketSlaState.BREACHED.name()
                    : CrmServiceTicketSlaState.PENDING.name());
        replyRemaining = signedBusinessSeconds(calendar, at, replyDue);
        replyWall = BusinessHoursCalendar.wallClockSecondsBetween(at, replyDue);
      }
    }

    if (resolveHours == null) {
      resolveState = CrmServiceTicketSlaState.NO_DEADLINE.name();
    } else if (!calendar.configured()) {
      resolveState = CrmServiceTicketSlaState.NO_CALENDAR.name();
    } else {
      resolveDue = calendar.addBusinessMinutes(ticket.createdAt(), resolveHours * 60L);
      if (resolveDue == null) {
        resolveState = CrmServiceTicketSlaState.NO_CALENDAR.name();
      } else {
        OffsetDateTime completion = ticket.closedAt();
        resolveState =
            completion != null
                ? (completion.isAfter(resolveDue)
                    ? CrmServiceTicketSlaState.BREACHED.name()
                    : CrmServiceTicketSlaState.MET.name())
                : (at.isAfter(resolveDue)
                    ? CrmServiceTicketSlaState.BREACHED.name()
                    : CrmServiceTicketSlaState.PENDING.name());
        resolveRemaining = signedBusinessSeconds(calendar, at, resolveDue);
        resolveWall = BusinessHoursCalendar.wallClockSecondsBetween(at, resolveDue);
      }
    }

    String basis = ticket.slaBasis() == null ? "" : ticket.slaBasis();
    return new CrmServiceTicketSla(
        basis,
        slaBasisLabel(basis),
        replyHours,
        resolveHours,
        replyDue,
        replyState,
        slaStateLabel(replyState),
        replyRemaining,
        replyWall,
        resolveDue,
        resolveState,
        slaStateLabel(resolveState),
        resolveRemaining,
        resolveWall,
        ticket.replyDueAt(),
        ticket.resolveDueAt(),
        calendar.scopeKey(),
        calendar.timezone(),
        calendar.configured(),
        slaNote(replyState, resolveState, calendar));
  }

  /** 剩余营业时间秒数（已超期为负数）。 */
  private static long signedBusinessSeconds(
      BusinessHoursCalendar calendar, OffsetDateTime at, OffsetDateTime due) {
    long forward = calendar.businessSecondsBetween(at, due);
    if (forward > 0) {
      return forward;
    }
    if (at.isAfter(due)) {
      return -calendar.businessSecondsBetween(due, at);
    }
    return 0L;
  }

  private static String slaStateLabel(String state) {
    for (CrmServiceTicketSlaState value : CrmServiceTicketSlaState.values()) {
      if (value.name().equals(state)) {
        return value.label();
      }
    }
    return state;
  }

  private static String slaBasisLabel(String basis) {
    return switch (basis) {
      case "PLATFORM_GIVEN" -> "平台给定";
      case "POLICY_SLA" -> "租户 SLA 策略";
      default -> "";
    };
  }

  private static String slaNote(String replyState, String resolveState, BusinessHoursCalendar calendar) {
    if (!calendar.configured()) {
      return "⚠️ 该作用域**未配置营业时间** ⇒ SLA 算不出来（NO_CALENDAR）。"
          + "本服务**不退回自然时间**：悄悄退回等于把「夜间积压被判逾期」的误判请回来，"
          + "而且现场看不出来。";
    }
    StringBuilder note = new StringBuilder();
    note
        .append("到期时刻在营业时间轴（时区 ")
        .append(calendar.timezone())
        .append("）上累加得出；剩余秒数是**本次读取**算出来的，库里没有会自己过期的剩余列。")
        .append("回复时限 ")
        .append(replyState)
        .append(" / 处理时限 ")
        .append(resolveState)
        .append("。");
    return note.toString();
  }

  // ================================================================ 行组装

  /** 把一行工单组装成响应行（含关联买家 / 订单 / 转派与升级）。 */
  private CrmServiceTicketRow toRow(
      RowWithSla row, BusinessHoursCalendar calendar, boolean enrich, String bearer) {
    ServiceTicketFactsPort.TicketView ticket = row.ticket();

    ServiceTicketFactsPort.OrderView order = null;
    ServiceTicketFactsPort.CaseView aftersalesCase = null;
    String orderNote = null;
    if (!enrich) {
      orderNote = "本次请求 enrich=false：未补全关联订单。";
    } else {
      try {
        aftersalesCase = facts.caseByNo(ticket.caseNo(), bearer);
        if (aftersalesCase == null) {
          orderNote =
              "上游 trade 里没有与售后单号 "
                  + ticket.caseNo()
                  + " **完全相等**的售后单（可能号写错，或该售后单尚未同步到 trade）。";
        } else if (aftersalesCase.orderNo() == null || aftersalesCase.orderNo().isBlank()) {
          orderNote = "上游售后单 " + ticket.caseNo() + " 没有关联订单号（售后单必须挂在订单上）。";
        } else {
          order = facts.orderByNo(aftersalesCase.orderNo(), bearer);
          orderNote =
              order == null
                  ? "上游 trade 订单服务里没有订单号 "
                      + aftersalesCase.orderNo()
                      + " 这一单（可能订单号写错，或该订单尚未同步到 trade）。"
                  : null;
        }
      } catch (RuntimeException e) {
        orderNote = "关联订单读不到：" + e.getMessage();
      }
    }

    CrmServiceTicketOrder orderView = toOrderView(order, ticket, aftersalesCase, orderNote);
    CrmServiceTicketBuyer buyerView = toBuyerView(ticket, order, aftersalesCase, orderNote);
    CrmServiceTicketTransfer transferView = toTransferView(ticket, enrich, bearer);
    return new CrmServiceTicketRow(
        ticket.id(),
        ticket.ticketNo(),
        ticket.ticketType(),
        ticketTypeLabel(ticket.ticketType()),
        ticket.ticketStatus(),
        ticketStatusLabel(ticket.ticketStatus()),
        ticket.priority(),
        priorityLabel(ticket.priority()),
        ticket.sourceChannel(),
        sourceChannelLabel(ticket.sourceChannel()),
        ticket.sourceRef(),
        ticket.subject(),
        ticket.reasonNote(),
        ticket.assignedToRef(),
        ticket.assignedToUserId(),
        ticket.assignedAt(),
        ticket.caseId(),
        ticket.caseNo(),
        ticket.caseStatus(),
        ticket.caseType(),
        ticket.platformScope(),
        ticket.satisfactionRating(),
        ticket.firstReplyAt(),
        ticket.waitingBuyerAt(),
        ticket.closedAt(),
        ticket.closeReason(),
        ticket.closeNote(),
        buyerView,
        orderView,
        transferView,
        row.sla(),
        ticket.createdAt(),
        ticket.updatedAt(),
        ticket.version());
  }

  private CrmServiceTicketOrder toOrderView(
      ServiceTicketFactsPort.OrderView order,
      ServiceTicketFactsPort.TicketView ticket,
      ServiceTicketFactsPort.CaseView aftersalesCase,
      String orderNote) {
    if (order == null) {
      return new CrmServiceTicketOrder(
          null, null, null, null, null, null, null, null, null, null, ticket.caseNo(), false,
          orderNote == null ? "关联订单不可用。" : orderNote);
    }
    return new CrmServiceTicketOrder(
        order.orderNo(),
        order.externalOrderNo(),
        order.orderSource(),
        order.documentStatus(),
        order.currency(),
        order.grandTotalAmount(),
        order.platformStatus(),
        order.paymentStatus(),
        order.fulfillmentStatus(),
        order.aftersalesStatus(),
        ticket.caseNo(),
        true,
        "订单事实来自上游 trade 9.1.1.1（经授权 HTTP API 只读），本服务**不复制一份**。");
  }

  /**
   * 关联买家。
   *
   * <p>⚠️ 买家事实来自<b>本服务自己的表</b>（{@code crm_message.buyer_reference} /
   * {@code crm_customer_contact.customer_ref}，按订单号关联）；
   * 买家名与买家档案 id 取自上游订单（trade 侧事实）。
   */
  private CrmServiceTicketBuyer toBuyerView(
      ServiceTicketFactsPort.TicketView ticket,
      ServiceTicketFactsPort.OrderView order,
      ServiceTicketFactsPort.CaseView aftersalesCase,
      String orderNote) {
    String orderNo =
        aftersalesCase != null && aftersalesCase.orderNo() != null
            ? aftersalesCase.orderNo()
            : null;
    String buyerReference = null;
    String shopId = null;
    String shopName = null;
    String platformCode = null;
    String contactRef = null;
    String contactName = null;
    if (orderNo != null) {
      Record message =
          sql.fetchOne(
              "select buyer_reference, shop_id, shop_name, platform_code from crm_message"
                  + " where tenant_id = ?::uuid and order_reference = ?"
                  + " order by platform_time desc limit 1",
              currentTenant(),
              orderNo);
      if (message != null) {
        buyerReference = message.get("buyer_reference", String.class);
        UUID shop = message.get("shop_id", UUID.class);
        shopId = shop == null ? null : shop.toString();
        shopName = message.get("shop_name", String.class);
        platformCode = message.get("platform_code", String.class);
      }
      Record contact =
          sql.fetchOne(
              "select customer_ref, customer_name from crm_customer_contact"
                  + " where tenant_id = ?::uuid and order_reference = ?"
                  + " order by occurred_at desc limit 1",
              currentTenant(),
              orderNo);
      if (contact != null) {
        contactRef = contact.get("customer_ref", String.class);
        contactName = contact.get("customer_name", String.class);
      }
    }
    boolean available = buyerReference != null || contactRef != null || order != null;
    String note;
    if (!available) {
      note =
          orderNote == null
              ? "本服务里没有这条订单的买家记录（crm_message / crm_customer_contact 按订单号关联）。"
              : orderNote;
    } else {
      note =
          "买家标识来自本服务自己的 crm_message / crm_customer_contact（逻辑引用）；"
              + "买家名与买家档案 id 来自上游订单。";
    }
    return new CrmServiceTicketBuyer(
        buyerReference,
        order == null ? null : order.buyerName(),
        order == null ? null : order.customerPartyId(),
        shopId == null ? null : UUID.fromString(shopId),
        shopName,
        platformCode,
        contactRef,
        contactName,
        available,
        note);
  }

  /**
   * 转派与升级（读上游既有字段）。
   *
   * <p>⚠️ 上游没有「批量取转派历史」的接口，只有单张详情里带着它 ——
   * 所以这里<b>逐行</b>取；取不到时 {@code historyAvailable=false} 且写明原因，
   * <b>不编一个 0</b>（「没有转派过」与「没查到转派记录」是两件事）。
   */
  private CrmServiceTicketTransfer toTransferView(
      ServiceTicketFactsPort.TicketView ticket, boolean enrich, String bearer) {
    if (!enrich) {
      return new CrmServiceTicketTransfer(
          ticket.assignedToRef(), ticket.assignedToUserId(), ticket.assignedAt(), 0, 0, null, null,
          null, null, ticket.escalatedCaseNo(), ticket.escalatedDisputeNo(), false,
          "本次请求 enrich=false：未逐行取上游转派历史（当前责任人与升级去向仍来自上游列表字段）。");
    }
    List<ServiceTicketFactsPort.AssignmentView> assignments = List.of();
    try {
      ServiceTicketFactsPort.TicketDetail detail = facts.ticketDetail(ticket.id(), bearer);
      if (detail != null && detail.assignments() != null) {
        assignments = detail.assignments();
      }
    } catch (RuntimeException e) {
      return new CrmServiceTicketTransfer(
          ticket.assignedToRef(), ticket.assignedToUserId(), ticket.assignedAt(), 0, 0, null, null,
          null, null, ticket.escalatedCaseNo(), ticket.escalatedDisputeNo(), false,
          "转派历史读不到：" + e.getMessage() + "（当前责任人与升级去向仍来自上游列表字段）。");
    }
    int transferCount = 0;
    ServiceTicketFactsPort.AssignmentView last = null;
    for (ServiceTicketFactsPort.AssignmentView assignment : assignments) {
      if ("TRANSFER".equals(assignment.assignAction())) {
        transferCount++;
        if (last == null
            || (assignment.createdAt() != null
                && (last.createdAt() == null || assignment.createdAt().isAfter(last.createdAt())))) {
          last = assignment;
        }
      }
    }
    return new CrmServiceTicketTransfer(
        ticket.assignedToRef(),
        ticket.assignedToUserId(),
        ticket.assignedAt(),
        assignments.size(),
        transferCount,
        last == null ? null : last.createdAt(),
        last == null ? null : last.fromOwnerRef(),
        last == null ? null : last.toOwnerRef(),
        last == null ? null : last.reasonNote(),
        ticket.escalatedCaseNo(),
        ticket.escalatedDisputeNo(),
        true,
        "转派历史来自上游工单详情里的派单留痕（append-only）；升级去向来自上游的升级字段。"
            + "本模块**不另造**一套转派模型。");
  }

  // ================================================================ 汇总

  /**
   * 排序（与上游的白名单<b>同一套键</b>，但 sla_asc 用的是**营业时间**剩余量）。
   *
   * <p>⚠️ 上游的 {@code sla_asc} 按<b>自然时间</b>的时限排；本模块在本地扫描路径里
   * 按<b>营业时间</b>剩余量排 —— 两边键名相同、语义不同，所以排序必须由本服务做，
   * 否则「按 SLA 排」排出来的顺序与行里显示的剩余量对不上。
   */
  private static void sortRows(List<RowWithSla> rows, String sort) {
    switch (sort) {
      case "priority_desc" ->
          rows.sort(
              Comparator.comparingInt((RowWithSla row) -> priorityRank(row.ticket().priority()))
                  .thenComparing(row -> row.ticket().createdAt(), Comparator.reverseOrder()));
      case "assigned_asc" ->
          rows.sort(
              Comparator.comparing(
                      (RowWithSla row) -> row.ticket().assignedToRef() == null ? "" : row.ticket().assignedToRef())
                  .thenComparing(row -> row.ticket().createdAt(), Comparator.reverseOrder()));
      case "sla_asc" ->
          rows.sort(
              Comparator.comparingLong(
                      (RowWithSla row) ->
                          Math.min(
                              row.sla().replyBusinessSecondsRemaining(),
                              row.sla().resolveBusinessSecondsRemaining()))
                  .thenComparing(row -> row.ticket().createdAt(), Comparator.reverseOrder()));
      default ->
          rows.sort(
              Comparator.comparing(
                      (RowWithSla row) -> row.ticket().createdAt(), Comparator.reverseOrder()));
    }
  }

  /** 优先级排序位（与上游一致：紧急 → 高 → 普通 → 低）。 */
  private static int priorityRank(String priority) {
    return switch (priority == null ? "" : priority) {
      case "URGENT" -> 1;
      case "HIGH" -> 2;
      case "NORMAL" -> 3;
      default -> 4;
    };
  }

  /**
   * 汇总。
   *
   * @param rows 本地扫描路径下的全量命中行；快路径下为 {@code null}（改用上游汇总）
   * @param upstreamSummary 上游的汇总条（与上游筛选条件一致，与分页无关）
   */
  private CrmServiceTicketSummary summarize(
      List<RowWithSla> rows, ServiceTicketFactsPort.TicketSummary upstreamSummary, boolean fullSet) {
    long open = 0;
    long inProgress = 0;
    long waitingBuyer = 0;
    long resolved = 0;
    long cancelled = 0;
    long unassigned = 0;
    long overdue = 0;
    long complaint = 0;
    long inquiry = 0;
    long noCalendar = 0;
    if (rows != null) {
      for (RowWithSla row : rows) {
        String status = row.ticket().ticketStatus();
        switch (status == null ? "" : status) {
          case "OPEN" -> open++;
          case "IN_PROGRESS" -> inProgress++;
          case "WAITING_BUYER" -> waitingBuyer++;
          case "RESOLVED" -> resolved++;
          case "CANCELLED" -> cancelled++;
          default -> { }
        }
        if (row.ticket().assignedToRef() == null || row.ticket().assignedToRef().isBlank()) {
          unassigned++;
        }
        if (isOverdue(row.sla())) {
          overdue++;
        }
        if ("COMPLAINT".equals(row.ticket().ticketType())) {
          complaint++;
        }
        if ("INQUIRY".equals(row.ticket().ticketType())) {
          inquiry++;
        }
        if ("NO_CALENDAR".equals(row.sla().replyState())) {
          noCalendar++;
        }
      }
      return new CrmServiceTicketSummary(
          rows.size(), open, inProgress, waitingBuyer, resolved, cancelled, unassigned, overdue,
          complaint, inquiry, noCalendar,
          "全部计数口径 = **当前筛选命中的全部工单**（与分页无关）；逾期按**营业时间**口径算。");
    }
    // 快路径：上游汇总（与上游筛选条件一致）+ 当前可见页的营业时间口径计数。
    if (upstreamSummary == null) {
      return new CrmServiceTicketSummary(
          0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
          "⚠️ 上游没有返回汇总条：分类型 / 分状态计数不可用。");
    }
    return new CrmServiceTicketSummary(
        upstreamSummary.total(),
        upstreamSummary.open(),
        upstreamSummary.inProgress(),
        upstreamSummary.waitingBuyer(),
        upstreamSummary.resolved(),
        upstreamSummary.cancelled(),
        upstreamSummary.unassigned(),
        overdue,
        upstreamSummary.complaint(),
        upstreamSummary.inquiry(),
        noCalendar,
        "总数 / 分状态 / 分类型 / 未分配来自上游汇总（与上游筛选条件一致、与分页无关）；"
            + "⚠️ **逾期**与**未配营业时间**这两个按营业时间口径的计数只覆盖**当前可见页**"
            + "（要全量口径请按 SLA 状态筛选 —— 那条路径会把上游整批取回后自己算）。");
  }

  // ================================================================ 工具

  private static String normalizeSort(String sort) {
    if (!isSet(sort)) {
      return "created_desc";
    }
    String value = sort.trim().toLowerCase(Locale.ROOT);
    return SORTS.contains(value) ? value : "created_desc";
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
          org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
          "租户上下文未绑定：本模块的所有读写都在绑定 app.current_tenant 的事务里进行");
    }
    return tenant;
  }

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
}
