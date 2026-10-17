package com.starcon.trade;

import static com.starcon.trade.AftersalesCaseModels.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Service;

/**
 * 11.1.1.1 售后单列表。
 *
 * <p><b>本类是 aftersales_case 簇的写入所有者</b>（本车道自己建的表，V156），
 * 对 {@code sales_order} 簇（V99/V100/V101，9.1.1.1 车道的写入所有者）**只读**——
 * 刻意不加外键、不 ALTER，跨车道 DDL 回归面 = 0。
 *
 * <p><b>三条硬纪律</b>：
 *
 * <ol>
 *   <li><b>筛选、分页、聚合全部在数据库层带 tenant_id 与筛选条件。</b>
 *       「查全量再在 Java 里过滤」在列表模块里是最严重的错误形态：多租户下它会变成
 *       <i>跨租户泄露</i>（A 家看到 B 家的买家与退款金额），数据量上它是全表扫描。
 *       本类每一条 {@code where} 都拼在 SQL 字符串里，没有任何「取回来再 filter」。
 *   <li><b>时效判定只有一个真身。</b>判定的唯一实现是数据库函数
 *       {@code aftersales_timeliness_state(due_at, done_at, at)}（V156）。
 *       Java 侧<b>不</b>重算一遍 —— 两处实现必然漂移，而超时直接关系账号绩效与资金冻结。
 *       「参考时刻 at」由本类在每次查询开始时取一次并<b>整页复用</b>：
 *       整页共用一个基准，翻页时同一张单不会在「临期」和「正常」之间跳。
 *   <li><b>批量操作的逐条失败来自预校验的真实判定</b>（见 {@link #bulk}）。
 *       不是「catch 到什么都标 failed」—— 那样会把数据库故障伪装成「这 18 条不满足条件」，
 *       运营会白跑一趟。写阶段的意外异常照常向上抛、整批回滚。
 * </ol>
 *
 * <p><b>金额区间是闭区间</b>（{@code >= minAmount and <= maxAmount}）。售后场景漏边界的代价是客诉：
 * 「我筛 100 以上的单，100 这张正好卡在边界上不见了」会被当成系统丢单。
 * 半开区间在这里没有任何好处 —— 因为「100 以上」在运营嘴里通常含 100。
 * 该口径由 {@link AftersalesCasePage#amountRangeSemantics()} 原样报给前端显示，并被集成测试逐位断言。
 *
 * <p><b>时间绑定</b>：时间筛选一律 {@code ?::timestamptz} + {@link OffsetDateTime}。
 * {@code Timestamp.valueOf(LocalDateTime)} 会静默偏 −28800s（本项目所在时区），
 * 「按下单时间筛选」会因此筛出错的区间。
 */
@Service
public class AftersalesCaseService {

  /** 单页上限。与 9.1.1.1 的 MAX_PAGE_SIZE 同一档。 */
  private static final int MAX_PAGE_SIZE = 200;

  /** 批量操作单次上限。蓝图要求「批量处理」，但一次批 5000 条既会拖垮事务也只有 100 行的页面可看。 */
  public static final int BULK_MAX_SIZE = 100;

  private final DSLContext sql;

  public AftersalesCaseService(DSLContext sql) {
    this.sql = sql;
  }

  // ================================================================ 能力声明

  /**
   * 能力声明：把「哪些维度真有依据」「上游通没通」「下游交付没交付」显式报出来。
   *
   * <p>让界面能说清「为什么没有店铺维度、责任人是谁登记的」，而不是让它们显示成空白让人以为
   * 「这个租户没有售后单」。
   */
  public AftersalesCaseCapabilities capabilities() {
    return new AftersalesCaseCapabilities(
        List.of(
            "售后号 aftersales_case.case_no（租户内唯一）",
            "关联订单 aftersales_case.order_id + sales_order.order_no（V99，只读）",
            "平台级来源 sales_order.order_source（AMAZON / SHOPIFY / EBAY / TIKTOK_SHOP / ALIBABA_1688）",
            "五种类型 aftersales_case.case_type（退货退款 / 仅退款 / 换货 / 补发 / 维修）",
            "状态 aftersales_case.status（7 态，终态与 resolved_at 严格等价）",
            "原因分类 aftersales_case.reason_code（七类内联词表，对齐 11.1.2.1 原文）",
            "申请金额与币种 aftersales_case.requested_amount numeric(19,4) + currency",
            "申请数量 aftersales_case.requested_quantity numeric(19,6)",
            "平台时效 reply_due_at / resolve_due_at 两个真列 + effective_due_at（触发器维护）",
            "时效状态 DB 函数 aftersales_timeliness_state()，可筛选可排序",
            "责任人 aftersales_owner_assignment（租户可维护，带来源枚举与采集时刻）",
            "授权状态 / 工单号 / 备注条数 aftersales_authorization / aftersales_service_ticket / aftersales_case_note"),
        List.of(
            new AftersalesCaseDimension(
                "platformShopCode",
                "平台店铺",
                false,
                "2 平台与店铺（2.x 店铺归属 / 9.1.2.1 平台订单同步 D5）",
                "sales_order 实查**没有 shop_code 列**（V99 的列只有 order_no / order_source / currency / "
                    + "payment_method / status / 金额），卷 2 的店铺在 channel 服务独立库 starcon_channel，"
                    + "按 AGENTS.md 模块边界纪律**禁止跨服务 SQL / 视图 / FK** ⇒ 本服务读不到店铺。"
                    + "因此店铺维度当前只能落到**平台级** order_source。"
                    + "本模块**刻意不建恒为空的 shop 列**：空列会被读成「这家店没有售后单」，"
                    + "而真相是「系统还不知道有哪些店」。D5 落地后由同步器补列，表结构与查询都不用改。"),
            new AftersalesCaseDimension(
                "ownerFromUpstream",
                "责任人（来自 15 客服）",
                false,
                "15 客户与客服（15.1.x 工单与坐席）",
                "卷 15 的 24 个 L4 里**只交付 1 个**（15.4.1.1 知识库，在 backend/services/crm 的独立库 "
                    + "starcon_crm），按模块边界纪律本服务永远读不到它的表。"
                    + "照抄 V150 order_risk_signal 的既有解法：责任人落成**租户可维护的真实表** "
                    + "aftersales_owner_assignment，每行强制 owner_source 枚举与 captured_at 采集时刻；"
                    + "正式接口到位后由同步器按**同一形状**写入并把来源改成 MODULE_15_SERVICE_STAFF，"
                    + "**表结构与列表查询都不用改**。"),
            new AftersalesCaseDimension(
                "platformMessage",
                "平台消息（买家说了什么）",
                false,
                "2 平台消息",
                "卷 2 平台消息未交付，channel 服务里没有任何平台消息表（本迁移已实查 "
                    + "create table 清单）。本表只存平台受理号与受理时刻，"
                    + "沟通记录属 11.1.1.2 详情且同样读不到平台消息。"),
            new AftersalesCaseDimension(
                "platformRefundAmount",
                "平台实际退款金额",
                false,
                "11.3 退款 + 12 财务",
                "本表的 requested_amount 是**买家申请退的金额**，不是平台实际退款额。"
                    + "后者由 11.3 发起、12 财务执行并回报，本服务算不出来也不猜。"),
            new AftersalesCaseDimension(
                "returnReceived",
                "退货实收",
                false,
                "11.2 退货管理（11.2.2.1 退货入库 / warehouse 质检）",
                "退货是货回来、退款是钱出去（蓝图 11 卷第一句）。本表**刻意没有**「已收到退货」"
                    + "这类状态：那是 11.2 的账，与本模块的处理进展是两个口径。"
                    + "11.2 交付后由它维护，本列表的状态列不受影响。")),
        List.of(
            new AftersalesBulkOperationSpec(
                "AUTHORIZE",
                "授权",
                true,
                BULK_MAX_SIZE,
                "aftersales_authorization（本服务内）",
                "售后单未结案（RESOLVED / REJECTED / CLOSED 会被逐条判失败）",
                "⚠️ 授权类型与售后类型**一一对应**（退货退款→退货退款授权、仅退款→退款授权、换货→换货授权、"
                    + "补发→补发授权、维修→维修授权），DB 触发器 guard_aftersales_authorization_kind 钉死。"
                    + "只有退款类（退货退款 / 仅退款）带授权金额，且不得超过申请金额；换货 / 补发 / 维修"
                    + "**不许带金额** —— 硬塞一个 0 或一个等于申请额的数字进去会让财务侧对不上账。",
                List.of(
                    "AUTHORIZED",
                    "NOT_FOUND",
                    "STATUS_TERMINAL",
                    "TYPE_MISMATCH",
                    "AMOUNT_EXCEEDS",
                    "AMOUNT_REQUIRED",
                    "AMOUNT_NOT_ALLOWED",
                    "CURRENCY_MISMATCH",
                    "DUPLICATE_IN_REQUEST")),
            new AftersalesBulkOperationSpec(
                "NOTE",
                "备注",
                true,
                BULK_MAX_SIZE,
                "aftersales_case_note（本服务内，append-only）",
                "备注正文非空且 ≤ 2000 字符",
                "备注是**追加式**的：改了不改写、补一条不删一条。它带 batch_id，"
                    + "事后能追回「这条备注属于哪一次批量」—— 逐条结果回报的凭据。"
                    + "不需要理由或权限之外的任何前置条件，所以它是三件里最容易批量成功的一件。",
                List.of("NOTE_ADDED", "NOT_FOUND", "NOTE_REQUIRED", "DUPLICATE_IN_REQUEST")),
            new AftersalesBulkOperationSpec(
                "ESCALATE",
                "转工单",
                true,
                BULK_MAX_SIZE,
                "aftersales_service_ticket（本服务内，11.4.2.1 未交付）",
                "一张售后单只转一次工单（UNIQUE(tenant_id, case_id)）",
                "⚠️ **不假装已对接 11.4.2.1 客诉工单**（未开发）：工单号、状态、优先级全是本服务"
                    + "自己管的事实，owner_module 列显式记下「这张工单归 11.4.2.1」，"
                    + "避免将来被误以为是本模块自造的影子表。重复转**不是静默忽略**，"
                    + "而是逐条失败并附上已有工单号 —— 第二个号会让「到底谁在跟这张单」从此没人说得清。",
                List.of("TICKET_CREATED", "NOT_FOUND", "ALREADY_TICKETED", "STATUS_TERMINAL", "DUPLICATE_IN_REQUEST"))),
        Arrays.stream(AftersalesSort.values())
            .map(s -> new AftersalesCaseSortOption(s.name(), s.label()))
            .toList(),
        "可筛选维度：类型 / 状态 / 原因 / 平台级来源 / 时效状态 / 币种 / 授权状态 / 关联订单 / 售后号 / "
            + "订单号 / 责任人 / 受理时间区间 / 金额区间 / 关键字。**店铺维度刻意不提供** —— "
            + "sales_order 没有 shop_code 列、卷 2 店铺在 channel 独立库禁止跨服务读，"
            + "一个永远筛不出东西的下拉框会让界面误以为这条链路已经通了。",
        false,
        "跨服务读**一律禁止**（AGENTS.md 模块边界纪律）。两个上游缺口都照 V150 的既有解法落成"
            + "**租户可维护的表 + 来源枚举 + 采集时刻**：责任人在 aftersales_owner_assignment，"
            + "15 客服到位后由同步器按同一形状写入。平台消息没有落表方案（它是对话流，"
            + "不是一条可维护的信号），如实报 false 并在 unavailableDimensions 里点名。",
        new AftersalesDownstream(
            false,
            false,
            false,
            false,
            false,
            "11.1.1.2 售后单详情",
            "11.1.1.3 售后受理",
            "11.2 退货管理",
            "11.3 退款与换货",
            "11.4.2.1 客诉工单",
            "下游四个模块**当前全部未交付**。列表行**刻意不跳转到任何不存在的路由**："
                + "行上带 ticketNo / authorizationState 这类事实标识就够了，"
                + "页面点不进去比点进去 404 更不容易让人误以为功能已交付。"
                + "转工单落在本服务自己的 aftersales_service_ticket，"
                + "11.4.2.1 交付后由它接管这张表。"),
        "店铺粒度：当前**只有平台级**（order_source）。详见 unavailableDimensions 里的 platformShopCode。",
        "时效：reply_due_at（平台要求回复时限）与 resolve_due_at（平台要求处理时限）是两个真列，"
            + "effective_due_at 由 DB 触发器取**更紧**的那个并建索引 ⇒ 可排序可筛选。"
            + "时效状态由 IMMUTABLE 函数 aftersales_timeliness_state() 现算："
            + "OVERDUE / DUE_SOON（≤ 24 小时）/ ON_TRACK / RESOLVED / NO_DEADLINE。"
            + "**参考时刻整页共用一个**（见 evaluatedAt），翻页不会让同一张单在临期与正常之间跳。"
            + "判定的唯一真身在这个函数里 —— Java 侧刻意不重算一遍，两处实现必然漂移，"
            + "而超时直接关系账号绩效与资金冻结。",
        "登记入口 POST /trade/aftersales/cases 是**11.1.1.3 售后受理未交付期间**的登记入口，"
            + "只做「单据落库 + 平台时限登记」，**不含**受理判定（售后期校验 / 条件判定 / 责任人分配 / "
            + "回复时限设置）—— 那四件事是 11.1.1.3 的职责，它交付后应当接管本入口。"
            + "没有它，列表将永远为空，那才是一条交付不出去的死链。");
  }

  // ================================================================ 列表

  /** 售后单主列表：真分页 + 与分页无关的全量汇总（只统计命中筛选条件的行）。 */
  public AftersalesCasePage list(AftersalesCaseQuery query) {
    int page = Math.max(0, query.page() == null ? 0 : query.page());
    int size = Math.min(MAX_PAGE_SIZE, Math.max(1, query.size() == null ? 50 : query.size()));
    AftersalesSort sort = AftersalesSort.parse(query.sort());

    // **整页共用的参考时刻**：判定时效、汇总计数、每行 remainingSeconds 全用它。
    OffsetDateTime at = OffsetDateTime.now(ZoneOffset.UTC);

    List<Object> binds = new ArrayList<>();
    List<String> where = conditions(query, binds, at);
    String clause = where.isEmpty() ? "" : " where " + String.join(" and ", where);

    // ⚠️ **所有 JOIN 必须在 WHERE 之前**。PostgreSQL 不允许 WHERE 之后再接一个 JOIN，
    //   写成 "from … where … left join lateral …" 会得到 `syntax error at or near "left"`。
    //   所以 FROM 分两段：count / 汇总用 base（不必带 lateral），明细行用 lateral（要责任人）。
    // 左连 sales_order 拿订单号与平台级来源（V99，只读）。
    // 用 left join 而不是 inner join：订单若因故缺失，这张售后单**仍要显示**，
    // 只是订单号与平台来源显示为「—」。inner join 会让一张真实的售后单凭空从列表里消失，
    // 而「列表里没有这张单」在售后场景里是比「订单号缺失」严重得多的问题。
    String fromBase =
        " from aftersales_case a"
            + " left join sales_order o on o.tenant_id = a.tenant_id and o.id = a.order_id";
    String from =
        fromBase
            + " left join lateral ("
            + "   select oa.owner_ref, oa.owner_display_name, oa.owner_role_label, oa.owner_source"
            + "     from aftersales_owner_assignment oa"
            + "    where oa.tenant_id = a.tenant_id and oa.case_id = a.id"
            + "    order by oa.captured_at desc, oa.id desc limit 1) w on true";

    long total = sql.fetchOne("select count(*)" + fromBase + clause, binds.toArray()).get(0, Long.class);

    // ⚠️ 占位符顺序必须与 SQL **文本出现顺序**一致：
    //   [select 里的两个 at] → [from / where 里的 binds] → [limit] → [offset]。
    //   jOOQ 按位置绑定，顺序错了不会报错、只会把值绑到别的 ? 上 —— 那是最难查的一类 bug。
    List<Object> pageBinds = new ArrayList<>();
    pageBinds.add(at);
    pageBinds.add(at);
    pageBinds.addAll(binds);
    pageBinds.add(size);
    pageBinds.add((long) page * size);
    List<AftersalesCaseRow> rows =
        sql.fetch(
                "select a.id, a.case_no, a.order_id, o.order_no, o.order_source,"
                    + " a.case_type, a.status, a.reason_code, a.reason_note,"
                    + " a.requested_amount, a.currency, a.requested_quantity,"
                    + " a.reply_due_at, a.resolve_due_at, a.effective_due_at,"
                    + " aftersales_timeliness_state(a.effective_due_at, a.resolved_at, ?::timestamptz)"
                    + "   as timeliness_state,"
                    + " extract(epoch from (a.effective_due_at - ?::timestamptz))::bigint"
                    + "   as remaining_seconds,"
                    + " a.platform_case_no, a.received_at, a.resolved_at,"
                    + " w.owner_ref, w.owner_display_name, w.owner_role_label, w.owner_source,"
                    + " (select count(*) from aftersales_case_note nt"
                    + "   where nt.tenant_id = a.tenant_id and nt.case_id = a.id) as note_count,"
                    + " (select au.decision from aftersales_authorization au"
                    + "   where au.tenant_id = a.tenant_id and au.case_id = a.id"
                    + "   order by au.decided_at desc, au.id desc limit 1) as last_decision,"
                    + " (select t.ticket_no from aftersales_service_ticket t"
                    + "   where t.tenant_id = a.tenant_id and t.case_id = a.id) as ticket_no"
                    + from
                    + clause
                    + " order by "
                    + sort.sql()
                    + " limit ? offset ?",
                pageBinds.toArray())
            .map(r -> row(r, at));

    return new AftersalesCasePage(
        rows,
        page,
        size,
        total,
        summary(fromBase, clause, binds, at),
        at,
        "金额区间是**闭区间**：[minAmount, maxAmount]，两端都取。售后场景漏边界的代价是客诉 —— "
            + "「我筛 100 以上的单，100 这张正好卡在边界上不见了」会被当成系统丢单。"
            + "半开区间在这里没有好处：「100 以上」在运营嘴里通常含 100。",
        "临期阈值 24 小时（写死在 DB 函数 aftersales_timeliness_state 里，语义只有这一处定义）。"
            + "整页共用一个参考时刻 evaluatedAt，翻页时同一张单不会在「临期」和「正常」之间跳。",
        Boolean.TRUE.equals(query.selfOnly()) ? "SELF" : "ALL",
        Boolean.TRUE.equals(query.selfOnly())
            ? "数据范围 SELF：只看责任人流转里有本人的售后单"
                + "（aftersales_owner_assignment.owner_ref = 本人）。"
                + "本人身份来自已认证的成员关系，不接受请求参数传入 —— 否则「只看我自己的单」"
                + "会变成一个能越权的开关。"
            : "数据范围 ALL：本租户全部售后单。多租户隔离由 RLS 在数据库层强制，与本范围无关。");
  }

  /** 筛选项下拉用的租户级词表（币种 / 平台来源来自真实数据，不是硬编码词表）。 */
  public AftersalesFilterOptions filterOptions() {
    Record currencies =
        sql.fetchOne(
            "select coalesce(string_agg(distinct currency, ',' order by currency), '') as c"
                + " from aftersales_case");
    Record platforms =
        sql.fetchOne(
            "select coalesce(string_agg(distinct o.order_source, ',' order by o.order_source), '') as c"
                + " from aftersales_case a join sales_order o"
                + " on o.tenant_id = a.tenant_id and o.id = a.order_id");
    return new AftersalesFilterOptions(
        Arrays.stream(AftersalesCaseType.values()).map(t -> new AftersalesEnumOption(t.name(), t.label())).toList(),
        Arrays.stream(AftersalesCaseStatus.values()).map(s -> new AftersalesEnumOption(s.name(), s.label())).toList(),
        Arrays.stream(AftersalesReasonCode.values()).map(r -> new AftersalesEnumOption(r.name(), r.label())).toList(),
        Arrays.stream(AftersalesTimelinessState.values()).map(s -> new AftersalesEnumOption(s.name(), s.label())).toList(),
        splitList(platforms == null ? null : platforms.get("c", String.class)),
        splitList(currencies == null ? null : currencies.get("c", String.class)),
        // 授权状态筛选项用**字符串码**而不是枚举项：NONE 不是 AftersalesDecision 的值
        // （它是「没有任何授权行」这条独立口径），放进枚举下拉里会让人以为它是第四种授权决定。
        List.of("NONE", "APPROVED", "PARTIALLY_APPROVED", "REJECTED"),
        Arrays.stream(AftersalesSort.values())
            .map(s -> new AftersalesCaseSortOption(s.name(), s.label()))
            .toList(),
        BULK_MAX_SIZE);
  }

  // ================================================================ 登记

  /**
   * 登记一张售后单（<b>11.1.1.3 售后受理未交付期间的登记入口</b>）。
   *
   * <p>刻意只做「单据落库 + 平台时限登记」，**不含**受理判定：售后期校验、条件判定、
   * 责任人自动分配、回复时限设置是 11.1.1.3 的职责，它交付后应当接管本方法。
   *
   * <p><b>订单必须真实存在</b>（同租户 sales_order 一行）—— 关联订单是职责句里的一列，
   * 「关联订单」指向一个不存在的行会让这整条链路没法对账。
   */
  public AftersalesCaseRow create(AftersalesCaseCreate request, UUID actorId) {
    AftersalesCaseType type = parseEnum(AftersalesCaseType.class, request.caseType(), "售后类型");
    AftersalesReasonCode reason =
        parseEnum(AftersalesReasonCode.class, request.reasonCode(), "原因分类");
    if (request.orderId() == null) {
      throw new IllegalArgumentException("必须关联一张订单");
    }
    if (request.requestedAmount() == null || request.requestedAmount().signum() < 0) {
      throw new IllegalArgumentException("申请金额不能为空且不能为负");
    }
    if (request.requestedQuantity() == null || request.requestedQuantity().signum() <= 0) {
      throw new IllegalArgumentException("申请数量必须大于 0");
    }
    String currency = requireCurrency(request.currency());
    // DB 的 CHECK 要求至少给一个时限；这里先给出人话错误而不是让约束异常冒到 500。
    if (request.replyDueAt() == null && request.resolveDueAt() == null) {
      throw new IllegalArgumentException(
          "必须至少给一个平台时限：平台要求回复时限（replyDueAt）或处理时限（resolveDueAt）");
    }
    if (request.replyDueAt() != null
        && request.resolveDueAt() != null
        && request.replyDueAt().isAfter(request.resolveDueAt())) {
      throw new IllegalArgumentException("平台要求回复时限不能晚于处理时限");
    }

    // 订单存在性真查一次（本服务同库 sales_order，V99 车道的表，只读）。
    Record order =
        sql.fetchOne(
            "select o.id from sales_order o"
                + " where o.tenant_id = ?::uuid and o.id = ?::uuid",
            currentTenant(),
            request.orderId());
    if (order == null) {
      throw new IllegalArgumentException("关联订单不存在或不属于当前租户：" + request.orderId());
    }

    UUID id = UUID.randomUUID();
    OffsetDateTime receivedAt =
        request.receivedAt() == null ? OffsetDateTime.now(ZoneOffset.UTC) : request.receivedAt();
    String caseNo = nextCaseNo();

    // ⚠️ **platform_received_at 必须带 ::timestamptz**：这一列是 timestamptz，
    //   而 jOOQ 把 OffsetDateTime 按 varchar 绑定。不加 cast 时 PG 报
    //   「column platform_received_at is of type timestamp with time zone but expression
    //   is of type character varying」，而且它**只在调用方真的传了平台受理时刻时才炸**
    //   （上游的测试恰好传的是 null，所以一直没暴露）。
    //   平台受理时刻是时效倒计时的起点（内部补录的单不能因为晚录入就多算时限），
    //   所以这个洞会让这一列永远写不进去。
    //   由 11.1.1.2 详情的集成测试首次传入非空 platformReceivedAt 时发现并修正。
    sql.execute(
        "insert into aftersales_case(id,tenant_id,case_no,order_id,case_type,status,reason_code,"
            + " reason_note,requested_amount,currency,requested_quantity,platform_case_no,"
            + " platform_case_url,platform_received_at,buyer_request,reply_due_at,resolve_due_at,"
            + " received_at,created_at,updated_at)"
            + " values (?,?,?,?,?,'RECEIVED',?,?,?,?,?,?,?,?::timestamptz,?,?::timestamptz,"
            + " ?::timestamptz,?::timestamptz,now(),now())",
        id,
        currentTenant(),
        caseNo,
        request.orderId(),
        type.name(),
        reason.name(),
        blankToEmpty(request.reasonNote()),
        request.requestedAmount(),
        currency,
        request.requestedQuantity(),
        blankToEmpty(request.platformCaseNo()),
        blankToEmpty(request.platformCaseUrl()),
        request.platformReceivedAt(),
        blankToEmpty(request.buyerRequest()),
        request.replyDueAt(),
        request.resolveDueAt(),
        receivedAt,
        receivedAt);

    // 责任人：可登记可不登记。登记时来源与采集时刻强制（V150 的同一套纪律）。
    if (isSet(request.ownerRef())) {
      AftersalesOwnerSource ownerSource =
          request.ownerSource() == null
              ? AftersalesOwnerSource.TENANT_MAINTAINED
              : parseEnum(
                  AftersalesOwnerSource.class, request.ownerSource(), "责任人来源");
      if (ownerSource == AftersalesOwnerSource.MODULE_15_SERVICE_STAFF
          && !isSet(request.ownerSourceReference())) {
        throw new IllegalArgumentException(
            "责任人来源为 MODULE_15_SERVICE_STAFF 时必须给出 sourceReference（哪份来源的哪一段）");
      }
      sql.execute(
          "insert into aftersales_owner_assignment(id,tenant_id,case_id,owner_ref,"
              + " owner_display_name,owner_role_label,owner_source,source_reference,captured_at,"
              + " assigned_by,created_at)"
              + " values (?,?,?,?,?,?,?,?,?::timestamptz,?,now())",
          UUID.randomUUID(),
          currentTenant(),
          id,
          request.ownerRef().trim(),
          blankToEmpty(request.ownerDisplayName()),
          blankToEmpty(request.ownerRoleLabel()),
          ownerSource.name(),
          blankToEmpty(request.ownerSourceReference()),
          request.ownerCapturedAt() == null
              ? OffsetDateTime.now(ZoneOffset.UTC)
              : request.ownerCapturedAt(),
          actorId);
    }

    return findOne(id, OffsetDateTime.now(ZoneOffset.UTC));
  }

  // ================================================================ 批量操作

  /**
   * 批量操作：授权 / 备注 / 转工单。
   *
   * <p><b>逐条结果回报是本方法的核心产出</b>：「30 条批了 12 条」不是可接受的结果，
   * 运营必须知道哪 12 条成功、哪 18 条失败、为什么失败，才能回头一条条处理。
   *
   * <p><b>逐条失败来自预校验阶段的真实判定</b>（{@link #bulk} 的第一遍）——它读的是数据库里的
   * 真实状态、类型、金额、已有工单号与请求内的重复项。<b>不是</b>「catch 到什么都标 failed」：
   * 那样会把一次数据库故障伪装成「这 18 条不满足条件」，运营会白跑一趟，
   * 而且故障本身被吞掉了。第二遍写阶段的意外异常照常向上抛、整批回滚，
   * 接口返回 5xx 而不是一份看起来成功的部分结果。
   *
   * <p><b>只作用于调用者有权限的租户数据</b>：整个方法跑在 {@code AccessService.run} 绑定的租户事务里，
   * RLS 在数据库层强制；预校验用的也是同一个 {@code select ... from aftersales_case}，
   * 所以<b>跨租户的 ID 会判成 NOT_FOUND 而不是被改掉</b> —— 拿 B 家的单 ID 去 A 家批量授权，
   * 得到的答案是「找不到」，这比「静默成功」正确得多。
   */
  public AftersalesBulkResult bulk(AftersalesBulkRequest request, UUID actorId) {
    AftersalesBulkOperation operation = AftersalesBulkOperation.parse(request.operation());
    if (request.caseIds() == null || request.caseIds().isEmpty()) {
      throw new IllegalArgumentException("必须至少指定一张售后单");
    }
    if (request.caseIds().size() > BULK_MAX_SIZE) {
      throw new IllegalArgumentException(
          "单次批量上限 " + BULK_MAX_SIZE + " 张，本次 " + request.caseIds().size()
              + " 张。批量不是没有限制的：一次批几千条既会拖垮事务，也只有 100 行的页面可看");
    }
    // 备注正文是**整批共用**的一个字段：空正文会让 30 条**全部**失败，
    // 报 30 条一模一样的失败原因对运营毫无价值。所以它是请求级错误（400），
    // 而不是逐条判定 —— 逐条只在「因单自身状态/类型/金额而失败」时才用。
    if (operation == AftersalesBulkOperation.NOTE) {
      Rejection noteProblem = validateNote(request);
      if (noteProblem != null) {
        throw new IllegalArgumentException(noteProblem.detail());
      }
    }

    // ---------- 第一遍：预校验，逐条给出真实的判定结果 ----------
    // ⚠️ 结果**按请求下标**存（ordered[i] 对应 caseIds.get(i)），而不是「失败在前、成功在后」
    //    再去重排。重复 ID 的场景会让「按 caseId 归并」把第一次的成功结果顶到第二次的失败位置上 ——
    //    运营看到的是「第 2 条成功、第 2 条又失败」，完全对不上他勾选的那几行。
    List<AftersalesBulkItemResult> ordered =
        new ArrayList<>(java.util.Collections.nCopies(request.caseIds().size(), null));
    Set<String> seen = new LinkedHashSet<>();
    // 通过预校验、可以真正落库的条目，按请求顺序。
    List<UUID> ids = new ArrayList<>();
    List<Integer> idIndexes = new ArrayList<>();
    Map<UUID, Record> approved = new LinkedHashMap<>();

    for (int i = 0; i < request.caseIds().size(); i++) {
      UUID caseId = request.caseIds().get(i);
      if (caseId == null) {
        ordered.set(i, fail(null, null, "NOT_FOUND", "请求里含空的售后单 ID"));
        continue;
      }
      if (!seen.add(caseId.toString())) {
        ordered.set(
            i,
            fail(caseId, null, "DUPLICATE_IN_REQUEST",
                "同一次请求里重复出现的售后单（第 " + (i + 1) + " 个位置），已按第一次出现处理"));
        continue;
      }

      Record row =
          sql.fetchOne(
              "select a.id, a.case_no, a.case_type, a.status, a.requested_amount, a.currency,"
                  + " (select t.ticket_no from aftersales_service_ticket t"
                  + "   where t.tenant_id = a.tenant_id and t.case_id = a.id) as ticket_no"
                  + " from aftersales_case a where a.id = ?::uuid",
              caseId);
      if (row == null) {
        ordered.set(i, fail(caseId, null, "NOT_FOUND", "售后单不存在或不属于当前租户"));
        continue;
      }
      String caseNo = row.get("case_no", String.class);
      String status = row.get("status", String.class);

      if (AftersalesCaseStatus.valueOf(status).terminal()) {
        ordered.set(
            i,
            fail(caseId, caseNo, "STATUS_TERMINAL",
                "售后单已结案（状态 " + status + "），不能再" + operation.label()));
        continue;
      }

      Rejection rejected = switch (operation) {
        case AUTHORIZE -> validateAuthorize(request, row);
        case NOTE -> validateNote(request);
        case ESCALATE -> validateEscalate(request, row);
      };
      if (rejected != null) {
        ordered.set(i, fail(caseId, caseNo, rejected.code(), rejected.detail()));
        continue;
      }

      approved.put(caseId, row);
      ids.add(caseId);
      idIndexes.add(i);
    }

    // ---------- 第二遍：真正落库（意外异常照常向上抛，整批回滚） ----------
    UUID batchId = UUID.randomUUID();
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    for (int k = 0; k < ids.size(); k++) {
      UUID caseId = ids.get(k);
      Record row = approved.get(caseId);
      String caseNo = row.get("case_no", String.class);
      AftersalesBulkItemResult applied =
          switch (operation) {
            case AUTHORIZE -> applyAuthorize(request, caseId, caseNo, batchId, actorId, now);
            case NOTE -> applyNote(request, caseId, caseNo, batchId, actorId);
            case ESCALATE -> applyEscalate(request, caseId, caseNo, batchId, actorId);
          };
      ordered.set(idIndexes.get(k), applied);
    }

    int succeeded = (int) ordered.stream().filter(AftersalesBulkItemResult::success).count();
    return new AftersalesBulkResult(
        operation.name(),
        operation.label(),
        request.caseIds().size(),
        succeeded,
        request.caseIds().size() - succeeded,
        ordered,
        batchId.toString(),
        "ALL（本租户）。批量只作用于调用者已认证成员关系所属租户的数据，"
            + "跨租户 ID 由 RLS 判成 NOT_FOUND，不会被改掉",
        "逐条失败来自**写库前的真实预校验**（数据库里的状态、类型、金额、已有工单号、请求内重复项），"
            + "不是 catch 到什么都标 failed —— 那样会把数据库故障伪装成「不满足条件」。"
            + "写阶段的意外异常会整批回滚并返回 5xx，不会伪装成部分成功。");
  }

  // ================================================================ 内部：查询构造

  /**
   * WHERE 条件。**每一条都对应一个真实存在的列、索引或 DB 函数。**
   *
   * <p>⚠️ 刻意没有 shop_code 条件：sales_order 没有这一列（实查 V99），
   * 卷 2 店铺在 channel 独立库、禁止跨服务读。平台级用 {@code o.order_source}。
   */
  private List<String> conditions(AftersalesCaseQuery q, List<Object> binds, OffsetDateTime at) {
    List<String> where = new ArrayList<>();
    // a.tenant_id 谓词显式写出：**不依赖 RLS 当唯一防线**。
    // RLS 是最后一道（FORCE 到 owner），但查询本身也带条件，两层独立生效。
    where.add("a.tenant_id = ?::uuid");
    binds.add(currentTenant());

    if (isSet(q.caseType())) {
      where.add("a.case_type = ?");
      binds.add(parseEnum(AftersalesCaseType.class, q.caseType(), "售后类型").name());
    }
    if (isSet(q.status())) {
      where.add("a.status = ?");
      binds.add(parseEnum(AftersalesCaseStatus.class, q.status(), "售后状态").name());
    }
    if (isSet(q.reasonCode())) {
      where.add("a.reason_code = ?");
      binds.add(parseEnum(AftersalesReasonCode.class, q.reasonCode(), "原因分类").name());
    }
    if (isSet(q.platformSource())) {
      // 平台级筛选（不是店铺级）。order_source 的词表由 V99 的 CHECK 定义，
      // 这里只把非法值挡下来，不自造一份平台列表。
      String source = q.platformSource().trim().toUpperCase();
      if (!source.matches("^(MANUAL|PLATFORM|AMAZON|SHOPIFY|EBAY|TIKTOK_SHOP|ALIBABA_1688)$")) {
        throw new IllegalArgumentException(
            "平台来源只能是 MANUAL / PLATFORM / AMAZON / SHOPIFY / EBAY / TIKTOK_SHOP / ALIBABA_1688");
      }
      where.add("o.order_source = ?");
      binds.add(source);
    }
    if (isSet(q.timelinessState())) {
      // 时效筛选落在**数据库函数**上 —— 可索引、可在 DB 层筛，不在 Java 里比大小。
      // ⚠️ 传的是**整页共用的 at**，不是这里新取的 now()：若两处时刻不同，
      // 一张卡在临期边界上的单会被 WHERE 选中又在行里显示成「正常」——
      // 界面上就会出现「我筛了临期、结果第一条不临期」这种自相矛盾。
      where.add("aftersales_timeliness_state(a.effective_due_at, a.resolved_at, ?::timestamptz) = ?");
      binds.add(at);
      binds.add(parseEnum(AftersalesTimelinessState.class, q.timelinessState(), "时效状态").name());
    }
    if (isSet(q.currency())) {
      where.add("a.currency = ?");
      binds.add(requireCurrency(q.currency()));
    }
    if (isSet(q.authorizationState())) {
      // 「未授权」= 没有任何授权行；其余三个 = 最近一次授权的决定。
      if ("NONE".equals(q.authorizationState().trim().toUpperCase())) {
        where.add(
            "not exists (select 1 from aftersales_authorization au"
                + " where au.tenant_id = a.tenant_id and au.case_id = a.id)");
      } else {
        String decision =
            parseEnum(AftersalesDecision.class, q.authorizationState(), "授权状态").name();
        where.add(
            "(select au.decision from aftersales_authorization au"
                + " where au.tenant_id = a.tenant_id and au.case_id = a.id"
                + " order by au.decided_at desc, au.id desc limit 1) = ?");
        binds.add(decision);
      }
    }
    if (q.orderId() != null) {
      where.add("a.order_id = ?::uuid");
      binds.add(q.orderId());
    }
    if (isSet(q.orderNo())) {
      where.add("o.order_no ilike ?");
      binds.add("%" + q.orderNo().trim() + "%");
    }
    if (isSet(q.caseNo())) {
      where.add("a.case_no ilike ?");
      binds.add("%" + q.caseNo().trim() + "%");
    }
    if (isSet(q.ownerRef())) {
      where.add(
          "exists (select 1 from aftersales_owner_assignment oa"
              + " where oa.tenant_id = a.tenant_id and oa.case_id = a.id and oa.owner_ref = ?)");
      binds.add(q.ownerRef().trim());
    }
    // 时间筛选：?::timestamptz + OffsetDateTime，禁 Timestamp.valueOf（静默偏 −28800s）。
    if (q.receivedFrom() != null) {
      where.add("a.received_at >= ?::timestamptz");
      binds.add(q.receivedFrom());
    }
    if (q.receivedTo() != null) {
      where.add("a.received_at <= ?::timestamptz");
      binds.add(q.receivedTo());
    }
    // 金额区间：**闭区间**。理由见类注释。
    if (q.minAmount() != null) {
      where.add("a.requested_amount >= ?");
      binds.add(q.minAmount());
    }
    if (q.maxAmount() != null) {
      where.add("a.requested_amount <= ?");
      binds.add(q.maxAmount());
    }
    if (q.minAmount() != null && q.maxAmount() != null && q.minAmount().compareTo(q.maxAmount()) > 0) {
      throw new IllegalArgumentException("金额下限不能大于上限");
    }
    if (isSet(q.keyword())) {
      // 真实模糊检索：售后号 / 订单号 / 平台单号 / 买家诉求原文。
      // 刻意**不含**金额与日期的模糊匹配 —— 那会让「搜 100」返回一堆无关单。
      String pattern = "%" + q.keyword().trim() + "%";
      where.add("(a.case_no ilike ? or o.order_no ilike ? or a.platform_case_no ilike ? or a.buyer_request ilike ?)");
      binds.add(pattern);
      binds.add(pattern);
      binds.add(pattern);
      binds.add(pattern);
    }
    if (Boolean.TRUE.equals(q.selfOnly())) {
      if (q.actorId() == null) {
        throw new IllegalArgumentException("数据范围限定为本人时必须能解析出操作人");
      }
      // 「本人经手过」= 责任人流转里有本人这一行。与 9.1.1.1 的 sales_order_status_history
      // 同一套口径：本服务没有独立的 owner 列，不为一个筛选器去改别人的表结构。
      where.add(
          "exists (select 1 from aftersales_owner_assignment oa"
              + " where oa.tenant_id = a.tenant_id and oa.case_id = a.id and oa.owner_ref = ?::uuid::text)");
      binds.add(q.actorId());
    }
    return where;
  }

  /**
   * 汇总条。统计的是<b>命中筛选条件的全部行</b>（与分页无关），不是当前页。
   *
   * <p><b>金额按币种分开报</b>：本服务没有任何汇率来源（12 财务未交付），
   * 把 USD 与 EUR 加成一个数没有意义 —— 那不是任何一个金额。
   */
  private AftersalesCaseSummary summary(
      String from, String clause, List<Object> binds, OffsetDateTime at) {
    // select 列表里有 **4 个** ?::timestamptz（OVERDUE / DUE_SOON / ON_TRACK / NO_DEADLINE 各一个），
    // 它们在 SQL 文本里出现在 where 条件**之前** ⇒ 4 个 at 必须排在 binds 前面。
    List<Object> sumBinds = new ArrayList<>();
    sumBinds.add(at);
    sumBinds.add(at);
    sumBinds.add(at);
    sumBinds.add(at);
    sumBinds.addAll(binds);
    Record head =
        sql.fetchOne(
            "select count(*) as total,"
                + " count(*) filter (where a.resolved_at is null) as open_count,"
                + " count(*) filter (where a.resolved_at is not null) as resolved_count,"
                + " count(*) filter (where a.resolved_at is null"
                + "   and aftersales_timeliness_state(a.effective_due_at, a.resolved_at, ?::timestamptz) = 'OVERDUE')"
                + "   as overdue_count,"
                + " count(*) filter (where a.resolved_at is null"
                + "   and aftersales_timeliness_state(a.effective_due_at, a.resolved_at, ?::timestamptz) = 'DUE_SOON')"
                + "   as due_soon_count,"
                + " count(*) filter (where aftersales_timeliness_state(a.effective_due_at, a.resolved_at, ?::timestamptz) = 'ON_TRACK')"
                + "   as on_track_count,"
                + " count(*) filter (where aftersales_timeliness_state(a.effective_due_at, a.resolved_at, ?::timestamptz) = 'NO_DEADLINE')"
                + "   as no_deadline_count"
                + from
                + clause,
            sumBinds.toArray());
    // 工单数单独查：放进上面的聚合里会引用未分组的 a.tenant_id，PG 直接报
    // "column a.tenant_id must appear in the GROUP BY clause"。
    long ticketCount =
        sql.fetchOne(
                "select count(*) from aftersales_service_ticket where tenant_id = ?::uuid",
                currentTenant())
            .get(0, Long.class);
    Map<String, Record> byCurrency = new LinkedHashMap<>();
    for (Record r :
        sql.fetch(
            "select a.currency, sum(a.requested_amount) as amount, count(*) as cnt"
                + from
                + clause
                + " group by a.currency order by a.currency",
            binds.toArray())) {
      byCurrency.put(r.get("currency", String.class), r);
    }
    List<AftersalesAmountBucket> amounts =
        byCurrency.entrySet().stream()
            .map(
                e ->
                    new AftersalesAmountBucket(
                        e.getKey(),
                        e.getValue().get("amount", BigDecimal.class),
                        e.getValue().get("cnt", Long.class)))
            .toList();
    List<AftersalesTypeBucket> byType = new ArrayList<>();
    for (Record r :
        sql.fetch(
            "select a.case_type, count(*) as cnt"
                + from
                + clause
                + " group by a.case_type order by a.case_type",
            binds.toArray())) {
      AftersalesCaseType t = AftersalesCaseType.valueOf(r.get("case_type", String.class));
      byType.add(new AftersalesTypeBucket(t.name(), t.label(), r.get("cnt", Long.class)));
    }
    return new AftersalesCaseSummary(
        head.get("total", Long.class),
        head.get("open_count", Long.class),
        head.get("overdue_count", Long.class),
        head.get("due_soon_count", Long.class),
        head.get("on_track_count", Long.class),
        head.get("no_deadline_count", Long.class),
        head.get("resolved_count", Long.class),
        ticketCount,
        amounts,
        byType,
        "金额按币种分开报：本服务没有任何汇率来源（12 财务未交付），"
            + "USD 与 EUR 加起来不是任何一个金额。");
  }

  private AftersalesCaseRow findOne(UUID id, OffsetDateTime at) {
    // 绑定顺序 = SQL 文本顺序：select 里的两个 at 先于 where 里的 tenant / id。
    List<Object> binds = new ArrayList<>();
    binds.add(at);
    binds.add(at);
    binds.add(currentTenant());
    binds.add(id);
    Record r =
        sql.fetchOne(
                "select a.id, a.case_no, a.order_id, o.order_no, o.order_source,"
                    + " a.case_type, a.status, a.reason_code, a.reason_note,"
                    + " a.requested_amount, a.currency, a.requested_quantity,"
                    + " a.reply_due_at, a.resolve_due_at, a.effective_due_at,"
                    + " aftersales_timeliness_state(a.effective_due_at, a.resolved_at, ?::timestamptz)"
                    + "   as timeliness_state,"
                    + " extract(epoch from (a.effective_due_at - ?::timestamptz))::bigint"
                    + "   as remaining_seconds,"
                    + " a.platform_case_no, a.received_at, a.resolved_at,"
                    + " w.owner_ref, w.owner_display_name, w.owner_role_label, w.owner_source,"
                    + " (select count(*) from aftersales_case_note nt"
                    + "   where nt.tenant_id = a.tenant_id and nt.case_id = a.id) as note_count,"
                    + " (select au.decision from aftersales_authorization au"
                    + "   where au.tenant_id = a.tenant_id and au.case_id = a.id"
                    + "   order by au.decided_at desc, au.id desc limit 1) as last_decision,"
                    + " (select t.ticket_no from aftersales_service_ticket t"
                    + "   where t.tenant_id = a.tenant_id and t.case_id = a.id) as ticket_no"
                    + " from aftersales_case a"
                    + " left join sales_order o on o.tenant_id = a.tenant_id and o.id = a.order_id"
                    + " left join lateral ("
                    + "   select oa.owner_ref, oa.owner_display_name, oa.owner_role_label, oa.owner_source"
                    + "     from aftersales_owner_assignment oa"
                    + "    where oa.tenant_id = a.tenant_id and oa.case_id = a.id"
                    + "    order by oa.captured_at desc, oa.id desc limit 1) w on true"
                    + " where a.tenant_id = ?::uuid and a.id = ?::uuid",
                binds.toArray());
    if (r == null) {
      throw new IllegalArgumentException("售后单不存在或不属于当前租户：" + id);
    }
    return row(r, at);
  }

  private static AftersalesCaseRow row(Record r, OffsetDateTime at) {
    AftersalesCaseType type = AftersalesCaseType.valueOf(r.get("case_type", String.class));
    AftersalesCaseStatus status = AftersalesCaseStatus.valueOf(r.get("status", String.class));
    AftersalesReasonCode reason = AftersalesReasonCode.valueOf(r.get("reason_code", String.class));
    AftersalesTimelinessState timely =
        AftersalesTimelinessState.valueOf(r.get("timeliness_state", String.class));
    String ownerSource = r.get("owner_source", String.class);
    Long remaining = r.get("remaining_seconds", Long.class);
    OffsetDateTime due = r.get("effective_due_at", OffsetDateTime.class);
    return new AftersalesCaseRow(
        r.get("id", UUID.class),
        r.get("case_no", String.class),
        r.get("order_id", UUID.class),
        r.get("order_no", String.class),
        r.get("order_source", String.class),
        // ↓ 店铺恒为 null：sales_order 没有 shop_code 列（实查 V99），
        //   卷 2 店铺在 channel 独立库、禁止跨服务读。见 capabilities 的 platformShopCode。
        null,
        type.name(),
        type.label(),
        status.name(),
        status.label(),
        reason.name(),
        reason.label(),
        blankToNull(r.get("reason_note", String.class)),
        r.get("requested_amount", BigDecimal.class),
        r.get("currency", String.class),
        r.get("requested_quantity", BigDecimal.class),
        timely.name(),
        timely.label(),
        r.get("reply_due_at", OffsetDateTime.class),
        r.get("resolve_due_at", OffsetDateTime.class),
        due,
        // 已结案或无时限时是 null 而不是 0：「剩余 0 秒」会被读成「现在就超时了」。
        (due == null || status.terminal()) ? null : remaining,
        r.get("owner_ref", String.class),
        r.get("owner_display_name", String.class),
        blankToNull(r.get("owner_role_label", String.class)),
        ownerSource,
        ownerSource != null && ownerSource.equals(AftersalesOwnerSource.MODULE_15_SERVICE_STAFF.name()),
        blankToNull(r.get("platform_case_no", String.class)),
        blankToNull(r.get("last_decision", String.class)) == null
            ? "NONE"
            : r.get("last_decision", String.class),
        r.get("ticket_no", String.class),
        r.get("note_count", Long.class),
        r.get("received_at", OffsetDateTime.class),
        r.get("resolved_at", OffsetDateTime.class));
  }

  // ================================================================ 内部：批量校验与写入

  private record Rejection(String code, String detail) {}

  private static Rejection reject(String code, String detail) {
    return new Rejection(code, detail);
  }

  private static Rejection validateNote(AftersalesBulkRequest r) {
    if (r.note() == null || r.note().isBlank()) {
      return reject("NOTE_REQUIRED", "备注正文不能为空：「批量点了备注但什么都没写」不该被记成一次成功备注");
    }
    if (r.note().length() > 2000) {
      return reject("NOTE_REQUIRED", "备注正文不能超过 2000 字符，本次 " + r.note().length() + " 字符");
    }
    return null;
  }

  private static Rejection validateEscalate(AftersalesBulkRequest r, Record row) {
    String ticketNo = row.get("ticket_no", String.class);
    if (ticketNo != null) {
      return reject(
          "ALREADY_TICKETED",
          "已转过工单（工单号 " + ticketNo + "）。重复转会产生两个号两个负责人，"
              + "「到底谁在跟这张单」从此没人说得清");
    }
    if (r.priority() != null && !r.priority().isBlank()) {
      parseEnum(AftersalesTicketPriority.class, r.priority(), "工单优先级");
    }
    return null;
  }

  private static Rejection validateAuthorize(AftersalesBulkRequest r, Record row) {
    String type = row.get("case_type", String.class);
    AftersalesCaseType caseType = AftersalesCaseType.valueOf(type);
    // 类型与授权种类一一对应（DB 触发器也钉了一条，这里先给出人话错误）。
    String expected = AftersalesAuthorizationKind.forType(caseType).name();
    String decisionRaw = r.decision();
    if (decisionRaw == null || decisionRaw.isBlank()) {
      return reject("AMOUNT_REQUIRED", "授权必须给出决定：APPROVED / PARTIALLY_APPROVED / REJECTED");
    }
    AftersalesDecision decision = parseEnum(AftersalesDecision.class, decisionRaw, "授权决定");
    boolean moneyType = caseType == AftersalesCaseType.RETURN_REFUND || caseType == AftersalesCaseType.REFUND_ONLY;
    BigDecimal requested = row.get("requested_amount", BigDecimal.class);

    if (decision == AftersalesDecision.REJECTED) {
      if (r.authorizedAmount() != null) {
        return reject("AMOUNT_EXCEEDS", "拒绝授权不能带金额：带金额的「拒绝」在财务侧会被读成已批的额度");
      }
      return null;
    }
    if (!moneyType) {
      if (r.authorizedAmount() != null) {
        return reject(
            "AMOUNT_NOT_ALLOWED",
            "类型 " + caseType.name() + "（应配授权 " + expected + "）的授权不带金额："
                + "换货 / 补发 / 维修授权的是「同意办」不是一个金额，"
                + "硬塞一个 0 或一个等于申请额的数字进去会让财务侧对不上账");
      }
      return null;
    }
    BigDecimal amount = r.authorizedAmount();
    if (amount == null) {
      return reject(
          "AMOUNT_REQUIRED",
          "类型 " + caseType.name() + "（应配授权 " + expected + "）的授权必须给出金额");
    }
    if (amount.signum() < 0) {
      return reject("AMOUNT_EXCEEDS", "授权金额不能为负");
    }
    if (amount.compareTo(requested) > 0) {
      return reject(
          "AMOUNT_EXCEEDS",
          "授权金额 " + amount.toPlainString() + " 超过申请金额 " + requested.toPlainString()
              + "。部分授权是允许的，**超**授权不是");
    }
    return null;
  }

  private AftersalesBulkItemResult applyAuthorize(
      AftersalesBulkRequest r, UUID caseId, String caseNo, UUID batchId, UUID actorId, OffsetDateTime now) {
    Record row =
        sql.fetchOne(
            "select case_type, currency from aftersales_case where id = ?::uuid", caseId);
    AftersalesCaseType caseType = AftersalesCaseType.valueOf(row.get("case_type", String.class));
    AftersalesDecision decision = parseEnum(AftersalesDecision.class, r.decision(), "授权决定");
    BigDecimal amount =
        r.authorizedAmount() == null ? null : r.authorizedAmount().setScale(4, java.math.RoundingMode.HALF_UP);
    String currency = amount == null ? null : row.get("currency", String.class);
    UUID id = UUID.randomUUID();
    sql.execute(
        "insert into aftersales_authorization(id,tenant_id,case_id,authorization_kind,decision,"
            + " authorized_amount,currency,reason_note,batch_id,decided_by,decided_at,created_at)"
            + " values (?,?,?,?,?,?,?,?,?,?,?::timestamptz,now())",
        id,
        currentTenant(),
        caseId,
        AftersalesAuthorizationKind.forType(caseType).name(),
        decision.name(),
        amount,
        currency,
        blankToEmpty(r.reasonNote()),
        batchId,
        actorId,
        now);
    // 授权后状态推进：RECEIVED / AUTHORIZING → IN_PROCESS。
    // 刻意**不**推进到 RESOLVED：授权只是「同意办」，办没办完是另一件事
    //（11.2 退货实收、11.3 退款都还没交付，把它标成已解决会凭空造出一个已完成的售后）。
    sql.execute(
        "update aftersales_case set status = 'IN_PROCESS', updated_at = now()"
            + " where id = ?::uuid and status in ('RECEIVED','AUTHORIZING')",
        caseId);
    return new AftersalesBulkItemResult(
        caseId, caseNo, true, "AUTHORIZED",
        "已" + decision.label() + "（" + AftersalesAuthorizationKind.forType(caseType).label() + "）"
            + (amount == null ? "" : "，授权金额 " + amount.toPlainString() + " " + currency),
        id.toString());
  }

  private AftersalesBulkItemResult applyNote(
      AftersalesBulkRequest r, UUID caseId, String caseNo, UUID batchId, UUID actorId) {
    UUID id = UUID.randomUUID();
    sql.execute(
        "insert into aftersales_case_note(id,tenant_id,case_id,note_body,note_source,batch_id,"
            + " author_id,created_at)"
            + " values (?,?,?,?,'TENANT_BATCH',?,?,now())",
        id,
        currentTenant(),
        caseId,
        r.note().trim(),
        batchId,
        actorId);
    return new AftersalesBulkItemResult(
        caseId, caseNo, true, "NOTE_ADDED", "已追加备注", id.toString());
  }

  private AftersalesBulkItemResult applyEscalate(
      AftersalesBulkRequest r, UUID caseId, String caseNo, UUID batchId, UUID actorId) {
    AftersalesTicketPriority priority =
        r.priority() == null || r.priority().isBlank()
            ? AftersalesTicketPriority.NORMAL
            : parseEnum(AftersalesTicketPriority.class, r.priority(), "工单优先级");
    String ticketNo = nextTicketNo();
    sql.execute(
        "insert into aftersales_service_ticket(id,tenant_id,ticket_no,case_id,ticket_status,"
            + " priority,reason_note,batch_id,created_by,created_at)"
            + " values (?,?,?,?,'OPEN',?,?,?,?,now())",
        UUID.randomUUID(),
        currentTenant(),
        ticketNo,
        caseId,
        priority.name(),
        blankToEmpty(r.reasonNote()),
        batchId,
        actorId);
    return new AftersalesBulkItemResult(
        caseId, caseNo, true, "TICKET_CREATED",
        "已转工单 " + ticketNo + "（优先级 " + priority.label() + "）。"
            + "⚠️ 11.4.2.1 客诉工单未交付，工单落在本服务自己的表里", ticketNo);
  }

  private static AftersalesBulkItemResult fail(
      UUID caseId, String caseNo, String outcome, String detail) {
    return new AftersalesBulkItemResult(caseId, caseNo, false, outcome, detail, null);
  }

  // ================================================================ 内部：工具

  /**
   * 当前租户。
   *
   * <p>来自 {@code app.current_tenant}（由 {@code AccessService.run} 在事务内绑定），
   * <b>不是</b>任何请求头。查询里仍显式带 {@code a.tenant_id = ?} 谓词，
   * 与 FORCE RLS 形成两道独立的防线。
   */
  private UUID currentTenant() {
    // fetchOne + get(..., String.class) 而不是 fetchValue(..., String.class)：
    // 后者在 DSLContext 上有多个重载，类型推断会落到返回 Object 的那个。
    return UUID.fromString(
        sql.fetchOne("select current_setting('app.current_tenant')").get(0, String.class));
  }

  private String nextCaseNo() {
    long n = sql.fetchOne("select nextval('aftersales_case_no_seq')").get(0, Long.class);
    return "AS" + n;
  }

  private String nextTicketNo() {
    long n = sql.fetchOne("select nextval('aftersales_ticket_no_seq')").get(0, Long.class);
    return "TK" + n;
  }

  private static <E extends Enum<E>> E parseEnum(Class<E> type, String raw, String label) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException(label + "不能为空");
    }
    try {
      return Enum.valueOf(type, raw.trim().toUpperCase());
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          label + "只能是 " + Arrays.toString(type.getEnumConstants()) + "：收到 " + raw);
    }
  }

  private static String requireCurrency(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException("币种不能为空");
    }
    String c = raw.trim().toUpperCase();
    if (!c.matches("^[A-Z]{3}$")) {
      throw new IllegalArgumentException("币种必须是三位大写字母（ISO 4217）：收到 " + raw);
    }
    return c;
  }

  private static boolean isSet(String v) {
    return v != null && !v.isBlank();
  }

  private static String blankToNull(String v) {
    return v == null || v.isBlank() ? null : v;
  }

  private static String blankToEmpty(String v) {
    return v == null ? "" : v;
  }

  private static List<String> splitList(String raw) {
    List<String> out = new ArrayList<>();
    if (raw != null && !raw.isBlank()) {
      Set<String> seen = new LinkedHashSet<>(Arrays.asList(raw.split(",")));
      for (String s : seen) if (!s.isBlank()) out.add(s.trim());
    }
    return out;
  }
}
