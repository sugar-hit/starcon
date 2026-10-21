package com.starcon.trade;

import static com.starcon.trade.ServiceTicketModels.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 11.4.2.1 工单管理。
 *
 * <p><b>本类是 V270 的写入所有者</b>：预留表 {@code aftersales_service_ticket}（V156 建、
 * 本模块接管）与四张新附属表 {@code aftersales_ticket_assignment} /
 * {@code aftersales_ticket_event} / {@code aftersales_ticket_satisfaction} /
 * {@code aftersales_ticket_sla_snapshot}。
 *
 * <p><b>八条硬纪律</b>：
 *
 * <ol>
 *   <li><b>每一条查询都显式带 {@code tenant_id} 谓词</b>，不依赖 RLS 当唯一防线。
 *       工单里有买家诉求原文、责任人与 SLA 达标结论 —— 端点又大多是<b>按 id 直达</b>的 URL，
 *       漏掉租户谓词就是跨租户泄露。</li>
 *   <li><b>一张售后单只转一次工单</b>（{@code UNIQUE(tenant_id, case_id)}，预留表自带）。
 *       重复转**不是静默忽略**，而是报失败并附上**已有工单号** ——
 *       两个号、两个负责人，「到底谁在跟这张单」从此没人说得清。</li>
 *   <li><b>SLA 时限不编造</b>：解析不到租户 SLA 档就**报错**并说清「没配置」，
 *       而不是悄悄用 48 小时之类的写死常量 —— 编出来的时限会让
 *       「我们没配 SLA」和「我们承诺 48 小时」在绩效口径上混成同一件事。</li>
 *   <li><b>SLA 判定的真身只有 DB 函数一处</b>（{@code aftersales_ticket_sla_state}，
 *       IMMUTABLE）。Java 与前端都**刻意不重算** —— 两处实现必然漂移，
 *       而 SLA 达标直接决定客服绩效口径。</li>
 *   <li><b>达标结论在关闭那一刻冻结</b>（{@code aftersales_ticket_sla_snapshot}）：
 *       「当时有没有达标」是已经发生的事实，不能随着规则改档翻来覆去。</li>
 *   <li><b>四张附属事实表全部 append-only</b>：转派历史 / 事件流 / 评价 / SLA 快照
 *       都是「已经发生过的事」，能被悄悄改掉的留痕等于没有留痕。</li>
 *   <li><b>时间绑定</b>：时间一律 {@code ?::timestamptz} + {@link OffsetDateTime}。
 *       {@code Timestamp.valueOf(LocalDateTime)} 会静默偏 −28800s，
 *       而 SLA 偏 8 小时会让「还剩多久」与「达没达标」整体算错。</li>
 *   <li><b>状态流转只有一个入口</b>：每次改状态都过版本校验，都写事件留痕。</li>
 * </ol>
 */
@Service
public class ServiceTicketService {

  /** 列表单页上限。 */
  private static final int MAX_PAGE_SIZE = 200;

  /** 满意度分值上限（1~5）。 */
  private static final int MAX_SATISFACTION_SCORE = 5;

  private final DSLContext sql;
  private final AftersalesCaseService cases;
  private final PlatformDisputeService disputes;

  public ServiceTicketService(
      DSLContext sql, AftersalesCaseService cases, PlatformDisputeService disputes) {
    this.sql = sql;
    this.cases = cases;
    this.disputes = disputes;
  }

  // ================================================================ 能力声明

  /**
   * 能力声明：职责句的六个动词逐块报可用性，上下游逐个报 delivered 与归属模块号、
   * 本模块<b>刻意不做</b>的事、状态机与三条硬规则的口径。
   *
   * <p>让界面能说清「为什么这张工单必须挂在一张售后单上」「为什么没有 SLA 就建不了工单」，
   * 而不是让它们显示成空白让人以为「这些能力没做」。
   */
  public ServiceTicketCapabilities capabilities() {
    return new ServiceTicketCapabilities(
        List.of(
            "售后单 aftersales_case（V156，本服务同库）—— 工单挂靠的上下文来源，单号 / 状态 / 平台时限随行带出",
            "工单 aftersales_service_ticket（V156 预留表 + **V270 扩展**：类型 / 来源 / 责任人 / SLA / 关闭 / 升级去向 / 乐观锁）",
            "转派历史 aftersales_ticket_assignment（V270，**append-only**，运行角色的 UPDATE/DELETE 已收回）",
            "工单事件 aftersales_ticket_event（V270，**append-only**，状态流转 / 分配 / 首次回复 / 关闭 / 评价 / 升级全留痕）",
            "满意度 aftersales_ticket_satisfaction（V270，一张工单一条，**不可改不可删**）",
            "SLA 达成快照 aftersales_ticket_sla_snapshot（V270，关闭那一刻由 DB 用同一个判定函数算出并冻结）",
            "SLA 规则 aftersales_reply_sla（V164，租户可维护、分档生效、内容写定不可改）",
            "责任人名册 aftersales_owner_roster（V164）—— 分配规则的数据源，加权最少在办轮转"),
        List.of(
            section("ticketType", "咨询 / 投诉 / 催单 / 平台政策咨询", true, null,
                "职责句点名的四类，逐类可筛可查。⚠️ 咨询与投诉的差别**不是**严重程度，"
                    + "是**承诺**：咨询是我们答复就完事，投诉意味着买家已明确表达不满、"
                    + "后面可能要走售后流程 ⇒ 「咨询升级为投诉」是一次**带方向的类型推进**（不可逆）。",
                "aftersales_service_ticket.ticket_type"),
            section("createSource", "人工 / 来自消息 / 自动触发", true, null,
                "输入句点名的三种来源。⚠️ **上游缺口**：15 客户与客服的消息模块"
                    + "（15.1.x / 15.3.x / 15.5.x / 15.6.x）**全部未开发** ⇒ "
                    + "「来自消息」是**调用方声明的来源**（消息号由调用方给），"
                    + "本服务**不读任何不存在的消息表**、也不 mock 一个「买家发来的消息」。",
                "aftersales_service_ticket.source_channel / source_ref"),
            section("assign", "分配", true, null,
                "两种方式：① 人工指定一个责任人；② 按名册**加权最少在办**轮转"
                    + "（排序键 = 在办工单数 / 权重，权重 1~100，100 = 十倍于权重 1；"
                    + "同负载时按 owner_ref 字典序定胜负，避免同批单全派给同一个人且无法复现）。"
                    + "⚠️ 名册为空或全都不接新单时**如实报「没人可派」**，"
                    + "不静默留空 —— 空责任人会让这张单在列表里没人认领。",
                "aftersales_service_ticket.assigned_to_* + aftersales_ticket_assignment"),
            section("slaTiming", "SLA 计时", true, null,
                "时限从**租户 SLA 策略**（aftersales_reply_sla，V164）按平台档解析："
                    + "平台精确档 > '' 兜底档，命中的是**当时生效**的那一档（半开区间）。"
                    + "解析不到就**报错**并说清「没配置」—— 本服务不替租户编一个默认小时数。"
                    + "工单上的 reply_due_at / resolve_due_at 是**建单那一刻**的快照，"
                    + "不随规则改档而漂移。",
                "aftersales_service_ticket.reply_due_at / resolve_due_at / sla_*"),
            section("transfer", "转派", true, null,
                "转派 = 换责任人，**必须写理由**，且必须真的有当前责任人"
                    + "（没有责任人可转 —— 那叫首派，不叫转派）。"
                    + "每一次流转都在 aftersales_ticket_assignment 留一条（append-only），"
                    + "「谁在什么时候把单给了谁」是纠纷举证材料。",
                "aftersales_ticket_assignment"),
            section("close", "关闭", true, null,
                "关闭**必须带原因**：已解决 / 买家未回应 / 重复工单 / 与本单无关 / "
                    + "已升级转单 / 不再跟进。⚠️ 「关闭」与「办完」不是一回事："
                    + "「买家未回应」「不再跟进」也是关闭，但**不算已解决** —— "
                    + "状态分别落 CANCELLED 与 RESOLVED，统计上分得开。",
                "aftersales_service_ticket.closed_at / close_reason + status"),
            section("satisfaction", "满意度评价", true, null,
                "1~5 分，一张工单**一条**评价。评价是「这次服务怎么样」的**一次**结论，"
                    + "改了它等于把买家给过的一次反馈重写 ⇒ 不可改不可删。"
                    + "⚠️ 只对**已关闭**的工单开放：还在办的单评价不了。",
                "aftersales_ticket_satisfaction"),
            section("slaStatistics", "SLA 达成统计", true, null,
                "口径只认**关闭时冻结的快照**（aftersales_ticket_sla_snapshot）："
                    + "回复达标率 / 处理达标率 / 双达标数。"
                    + "⚠️ 「没配处理时限」与「处理没达标」是两件事 ⇒ resolve_met 可空，"
                    + "没配时限的工单**不进**处理时限的分母。",
                "aftersales_ticket_sla_snapshot"),
            section("mutualConversion", "工单与售后单互转", true, null,
                "蓝图加粗句「咨询升级为投诉、投诉升级为纠纷，需要保留上下文」落成两条真实通道："
                    + "① **咨询 → 投诉**：在原售后单**之外**新建一张售后单"
                    + "（同服务同库，11.1.1.1 的登记入口），并把新单号记回工单的 escalated_case_id；"
                    + "② **投诉 → 纠纷**：新建一条平台纠纷（11.4.1.1，同服务同库），"
                    + "并把纠纷号记回工单的 escalated_dispute_id。"
                    + "两处都有**真外键**钉住 ⇒ 「升级了但对方根本不存在」存不进库里。"
                    + "上下文（工单号 / 售后单号 / 主题 / 诉求）在升级物里**原文保留**。",
                "aftersales_service_ticket.escalated_case_id / escalated_dispute_id"),
            section("crmUpstream", "15 客户与客服（消息 / 工单 / 客户主数据）", false, "15 客户与客服",
                "**上游缺口**：crm 服务已交付的是索评（15.2.2.1 / 15.2.2.2）与知识库（15.4.2.1）；"
                    + "客服工单 / 客户 / 消息模块（15.1.x / 15.3.x / 15.5.x / 15.6.x）**全部未开发**。"
                    + "⇒ 本模块提供**自己的建单入口**（授权 API），不假装存在一个平台推送接口。",
                null),
            section("knowledge", "11.4.2.2 知识库与话术引用", true, "11.4.2.2",
                "**已交付**（catalog 服务 /catalog/kb）。⚠️ 跨服务**禁止 SQL / FK / 视图** ⇒ "
                    + "工单上只落**逻辑引用** knowledge_entry_id，话术正文由前端经 catalog 的"
                    + "**授权 API**取，trade 侧**不读**它的任何表。",
                "aftersales_service_ticket.knowledge_entry_id（逻辑引用）")),
        List.of(
            related("MODULE_11_1_1_1", "11.1.1.1 售后单列表（转工单）", true, "11.1.1.1",
                "**已交付**。它的批量「转工单」写的正是本模块接管的预留表 —— "
                    + "两处共用一张表、一个号段（aftersales_ticket_no_seq），"
                    + "所以「一张售后单只转一次」这条纪律对两个入口同样成立。",
                "/aftersales"),
            related("MODULE_11_4_1_1", "11.4.1.1 平台纠纷（投诉升级为纠纷的落点）", true, "11.4.1.1",
                "**已交付**，且**同服务同库** ⇒ 升级走**真表**（真外键），不是跨服务调用。",
                "/aftersales/disputes/platform-disputes"),
            related("MODULE_11_4_2_2", "11.4.2.2 知识库与话术", true, "11.4.2.2",
                "**已交付**，但在 **catalog 服务** ⇒ 只能**逻辑引用**（knowledge_entry_id），"
                    + "禁止跨库读它的表。",
                "/aftersales/knowledge"),
            related("MODULE_11_1_1_3", "11.1.1.3 售后受理（SLA 策略 / 责任人名册）", true, "11.1.1.3",
                "**已交付**。SLA 规则档与责任人名册都是它建的，"
                    + "本模块**只读**这两张表（同一个服务、同一个库，合法）。",
                "/aftersales"),
            related("MODULE_15_CRM", "15 客户与客服（工单 / 客户 / 消息）", false, "15 客户与客服",
                "**未开发**（15.1.x / 15.3.x / 15.5.x / 15.6.x）⇒ 本模块不读不存在的消息表。",
                null),
            related("MODULE_11_1_1_2", "11.1.1.2 售后详情（沟通记录 / 附件）", true, "11.1.1.2",
                "**已交付**。工单的详情里**不重复**拉沟通记录 —— "
                    + "那是售后单的上下文，在 11.1.1.2 看；工单只带单号与平台时限做指路。",
                "/aftersales")),
        List.of(
            action("CREATE_TICKET", "创建工单", true, "POST", "/trade/service-tickets", false,
                "必须给 caseId（工单永远挂在一张售后单上）；SLA 档没配会明确报错", null, null, null),
            action("ASSIGN", "人工派单", true, "POST", "/trade/service-tickets/{id}/assign", false,
                "仅未关闭；责任人必须在名册里", null, null, null),
            action("AUTO_ASSIGN", "按名册轮转派单", true, "POST",
                "/trade/service-tickets/{id}/auto-assign", false,
                "仅未关闭；名册为空时如实报「没人可派」", null, null, null),
            action("TRANSFER", "转派", true, "POST", "/trade/service-tickets/{id}/transfer", true,
                "仅未关闭；**必须已有责任人**；必须写理由", null, null, null),
            action("CLAIM", "认领", true, "POST", "/trade/service-tickets/{id}/claim", false,
                "仅未关闭且**当前无责任人**", null, null, null),
            action("RELEASE", "释放责任人", true, "POST", "/trade/service-tickets/{id}/release", true,
                "仅未关闭且**已有责任人**；必须写理由", null, null, null),
            action("FIRST_REPLY", "记录首次回复", true, "POST",
                "/trade/service-tickets/{id}/first-reply", false,
                "仅未关闭且**尚未记录过首次回复**（回复计时停在第一次）", null, null, null),
            action("CHANGE_STATUS", "状态流转（处理中 / 待买家）", true, "POST",
                "/trade/service-tickets/{id}/status", false,
                "仅未关闭；终态不可再流转", null, null, null),
            action("CLOSE", "关闭工单", true, "POST", "/trade/service-tickets/{id}/close", true,
                "仅未关闭；**必须写关闭原因**", null, null, null),
            action("SATISFACTION", "满意度评价", true, "POST",
                "/trade/service-tickets/{id}/satisfaction", false,
                "仅**已关闭**；一张工单一条", null, null, null),
            action("ESCALATE", "升级（转投诉 / 转纠纷）", true, "POST",
                "/trade/service-tickets/{id}/escalate", true,
                "咨询 → 投诉 / 投诉 → 纠纷；必须写理由", null, null, null),
            action("SLA_STATS", "SLA 达成统计", true, "GET",
                "/trade/service-tickets/sla-stats", false,
                "口径只认关闭时冻结的快照", null, null, null),
            action("DELETE_TICKET", "删除工单", false, null, null, false, null,
                "**刻意不提供**：工单是纠纷举证材料，删掉等于毁掉「谁在什么时候把单给了谁」的凭据。"
                    + "要停止跟单就关闭它（写明原因），不要删。",
                null,
                "系统（V270 append-only）")),
        List.of(
            boundary("工单必须挂在一张售后单上，且一张售后单只转一次", "11.1.1.1",
                "预留表 V156 的 case_id NOT NULL + UNIQUE(tenant_id, case_id) 已把这条钉死。"
                    + "「非售后类」说的是工单**不替代**售后单，不是工单可以没有归属。"),
            boundary("不建一张与售后单平行的「纯咨询工单表」", "11.4.2.1",
                "两张表会让「这次工单到底跟的是哪笔生意」有两个答案，"
                    + "而 UNIQUE(tenant_id,case_id) 这条纪律也就守不住了。"),
            boundary("不读 15 客户与客服的消息表、不 mock「买家发来的消息」", "15 客户与客服",
                "那些模块**全部未开发** ⇒ 读就是读一个不存在的表，mock 就是假数据。"),
            boundary("不跨库读 11.4.2.2 知识库的表，只落逻辑引用", "11.4.2.2",
                "跨服务禁止 SQL / FK / 视图；话术正文由前端经 catalog 的授权 API 取。"),
            boundary("SLA 时限解析不到就报错，不编默认小时数", "11.1.1.3",
                "编出来的时限会让「没配 SLA」和「承诺 48 小时」在绩效口径上混成同一件事。"),
            boundary("不提供删除工单的端点", "系统（V270 append-only）",
                "工单是纠纷举证材料；四张附属事实表在数据库层连 UPDATE/DELETE 权限都没有。"),
            boundary("SLA 判定不在 Java / 前端重算", "系统（V270 IMMUTABLE 函数）",
                "两处实现必然漂移，而 SLA 达标直接决定客服绩效口径。")),
        "OPEN（待处理）→ IN_PROGRESS（处理中）⇄ WAITING_BUYER（待买家）→ RESOLVED（已关闭）。"
            + "任何未关闭状态都可 → CANCELLED（已取消）。"
            + "⚠️ RESOLVED 与 CANCELLED 是**两个不同的终态**："
            + "前者是「这件事办完了」，后者是「这件事不归我们管了」——"
            + "统计上「已关闭」含两者，「办完了」只含前者。",
        "「**一张售后单只转一次工单**」落成结构而不是文案："
            + "① 预留表 V156 自带 UNIQUE(tenant_id, case_id)（DB 层兜底，绕过服务层直连 SQL 也一样）；"
            + "② 服务层在建单前**先查一次**，命中就报失败并**附上已有工单号** —— "
            + "重复转不是静默忽略；"
            + "③ V270 的护栏触发器同时钉住「工单必须挂在真实存在且属于当前租户的售后单上」"
            + "与「标识列不可改写」。",
        "「**SLA 计时**」的三层落点："
            + "① 时限是**真实的 timestamptz 列**（reply_due_at / resolve_due_at），"
            + "不是文本、不是「48 小时」这种相对描述；"
            + "② 判定真身**只有 DB 函数一处**（aftersales_ticket_sla_state，IMMUTABLE）："
            + "列表筛选、列表排序、行内展示、达成统计四处共用，Java 与前端刻意不重算；"
            + "③ 「当时有没有达标」在**关闭那一刻**由 DB 算出并冻结进 "
            + "aftersales_ticket_sla_snapshot（同一函数，不另立一套）—— "
            + "绩效按它算账，不随规则改档翻来覆去。",
        "「**四类附属事实全部 append-only**」：转派历史 / 事件流 / 满意度 / SLA 快照，"
            + "运行角色的 UPDATE 与 DELETE 权限在数据库层被收回 —— "
            + "能被悄悄改掉的留痕等于没有留痕。",
        "⚠️ **上游缺口（如实报出，不假装已交付）**：15 客户与客服的消息 / 工单 / 客户模块"
            + "（15.1.x / 15.3.x / 15.5.x / 15.6.x）**全部未开发** ⇒ "
            + "本模块**不调用不存在的端点、不 mock 一条「买家发来的消息」**："
            + "工单由本模块自己的建单入口（授权 API）登记，"
            + "「来自消息」落的是**调用方给的来源引用**（消息号 / 规则号）。"
            + "15 的模块落地后，经**授权 API / event** 调本模块的建单入口即可衔接。");
  }

  // ================================================================ 列表

  /** 工单列表：真分页 + 多维筛选 + 排序白名单 + 跨全表汇总 + SLA 达成统计。 */
  public ServiceTicketPage listTickets(ServiceTicketListQuery query) {
    int p = query.page() == null || query.page() < 0 ? 0 : query.page();
    int s = query.size() == null || query.size() <= 0 ? 20 : Math.min(query.size(), MAX_PAGE_SIZE);
    ServiceTicketSort sort = ServiceTicketSort.parse(query.sort());

    // ⚠️ **参照时刻整页共用一个**：筛选、行、汇总三处复用同一个 at，
    //   否则会「筛了临期、第一条不临期」（V156 已经踩过这个坑）。
    OffsetDateTime at = OffsetDateTime.now(ZoneOffset.UTC);

    // ⚠️ **绑定顺序**（jOOQ 按占位符出现顺序取值，顺序错了就是静默查错行）：
    //   列表 = select 的两个 at → where 的租户 → 各筛选条件 → limit → offset
    //   汇总 = select 的一个 at → where 的租户 → 各筛选条件
    //   统计 = where 的租户 → 各筛选条件（select 里没有占位符）
    List<Object> filterBinds = new ArrayList<>();
    List<String> where = conditions(query, filterBinds, at);
    String clause = " where " + String.join(" and ", where);

    List<Object> scopeBinds = new ArrayList<>();
    scopeBinds.add(currentTenant());
    scopeBinds.addAll(filterBinds);

    long total =
        sql.fetchOne("select count(*)" + from() + clause, scopeBinds.toArray()).get(0, Long.class);

    List<Object> pageBinds = new ArrayList<>();
    pageBinds.add(at);
    pageBinds.add(at);
    pageBinds.addAll(scopeBinds);
    pageBinds.add(s);
    pageBinds.add((long) p * s);
    List<ServiceTicketRow> rows =
        sql.fetch(
                selectColumns() + from() + clause + " order by " + sort.sql() + " limit ? offset ?",
                pageBinds.toArray())
            .map(ServiceTicketService::mapRow);

    List<Object> summaryBinds = new ArrayList<>();
    summaryBinds.add(at);
    summaryBinds.addAll(scopeBinds);
    ServiceTicketSummary summary = summary(clause, summaryBinds);

    ServiceTicketSlaStats slaStats = slaStats(clause, new ArrayList<>(scopeBinds));

    return new ServiceTicketPage(
        rows,
        p,
        s,
        total,
        summary,
        slaStats,
        at,
        "真分页（LIMIT/OFFSET + 独立 COUNT），汇总与排序不受分页影响。",
        "⚠️ **SLA 判定真身只有 DB 函数一处**（aftersales_ticket_sla_state，IMMUTABLE）："
            + "行里的 replySlaState / resolveSlaState 与 secondsToReplyDue 都是**本次读取时刻**算出来的，"
            + "库里**没有**「剩余小时数」列（那一列写下去就开始自己过期）。"
            + "「当时有没有达标」以**关闭时冻结的快照**为准（aftersales_ticket_sla_snapshot）。",
        "排序白名单四个键（sla_asc / created_desc / priority_desc / assigned_asc）；"
            + "其余取值落回 sla_asc（最紧急的排最前），不接受任意表达式。");
  }

  /**
   * 列表的 from 子句：工单 + 售后单上下文 + 平台来源 + 升级去向。
   *
   * <p>⚠️ 升级去向用 {@code LEFT JOIN}：没升级过的工单这两列为空，
   * 而升级过的工单指向的是**同库**的真实售后单 / 平台纠纷（真外键钉住）。
   */
  private static String from() {
    return " from aftersales_service_ticket t"
        + " join aftersales_case c on c.tenant_id = t.tenant_id and c.id = t.case_id"
        + " left join sales_order o on o.tenant_id = c.tenant_id and o.id = c.order_id"
        + " left join aftersales_case ec on ec.tenant_id = t.tenant_id and ec.id = t.escalated_case_id"
        + " left join platform_dispute ed on ed.tenant_id = t.tenant_id and ed.id = t.escalated_dispute_id";
  }

  /**
   * 列表的 select 子句。
   *
   * <p>⚠️ **工单的列逐个列出**，不用 {@code t.*}：{@code t.*} 会与 join 进来的
   * {@code aftersales_case} 的同名列（id / tenant_id / version / created_at / updated_at）
   * 撞名，jOOQ 的 {@code record.get("id")} 在撞名时返回的是**第一个**匹配 ——
   * 哪天有人调整一下列顺序，取到的就是另一张表的 id，而它不会报错。
   */
  private static String selectColumns() {
    return " select t.id, t.tenant_id, t.ticket_no, t.case_id, t.ticket_status, t.priority,"
        + " t.reason_note, t.owner_module, t.batch_id, t.created_by, t.created_at,"
        + " t.ticket_type, t.source_channel, t.source_ref, t.subject,"
        + " t.assigned_to_ref, t.assigned_to_user_id, t.assigned_at, t.knowledge_entry_id,"
        + " t.sla_policy_id, t.sla_reply_hours, t.sla_resolve_hours,"
        + " t.reply_due_at, t.resolve_due_at, t.first_reply_at, t.waiting_buyer_at,"
        + " t.closed_at, t.close_reason, t.close_note,"
        + " t.escalated_case_id, t.escalated_dispute_id, t.version, t.updated_at,"
        + " c.case_no as case_no, c.status as case_status, c.case_type as case_type,"
        + " c.reply_due_at as case_reply_due_at, c.resolve_due_at as case_resolve_due_at,"
        + " o.order_source as platform_scope,"
        + " aftersales_ticket_sla_state(t.reply_due_at, t.first_reply_at, ?::timestamptz)"
        + "   as reply_sla_state,"
        + " aftersales_ticket_sla_state(t.resolve_due_at, t.closed_at, ?::timestamptz)"
        + "   as resolve_sla_state,"
        + " (select s.score from aftersales_ticket_satisfaction s"
        + "   where s.tenant_id = t.tenant_id and s.ticket_id = t.id) as satisfaction_rating,"
        + " ec.case_no as escalated_case_no,"
        + " ed.dispute_no as escalated_dispute_no";
  }

  /**
   * 筛选条件。
   *
   * <p>⚠️ 谓词一律带 {@code t.tenant_id = ?}（显式租户谓词 + FORCE RLS 两道防线）。
   * {@code at} 是 SLA 判定的参照时刻，与列表行、汇总三处复用同一个。
   *
   * <p>⚠️ **租户的绑定值由调用方单独前置**（{@code scopeBinds}），
   * 这里的 {@code filterBinds} 只装**各筛选条件**自己的绑定值 ——
   * 混在一起的话，jOOQ 会按占位符顺序把值对错位（静默查错行）。
   */
  private static List<String> conditions(
      ServiceTicketListQuery q, List<Object> filterBinds, OffsetDateTime at) {
    List<String> where = new ArrayList<>();
    where.add("t.tenant_id = ?::uuid");

    if (isSet(q.status())) {
      where.add("t.ticket_status = ?");
      filterBinds.add(q.status().trim().toUpperCase(java.util.Locale.ROOT));
    }
    if (isSet(q.ticketType())) {
      where.add("t.ticket_type = ?");
      filterBinds.add(q.ticketType().trim().toUpperCase(java.util.Locale.ROOT));
    }
    if (isSet(q.priority())) {
      where.add("t.priority = ?");
      filterBinds.add(q.priority().trim().toUpperCase(java.util.Locale.ROOT));
    }
    if (isSet(q.sourceChannel())) {
      where.add("t.source_channel = ?");
      filterBinds.add(q.sourceChannel().trim().toUpperCase(java.util.Locale.ROOT));
    }
    if (isSet(q.assignedToRef())) {
      where.add("t.assigned_to_ref = ?");
      filterBinds.add(q.assignedToRef().trim());
    }
    if (isSet(q.caseId())) {
      where.add("t.case_id = ?::uuid");
      filterBinds.add(q.caseId().trim());
    }
    if (isSet(q.slaState())) {
      // ⚠️ SLA 状态是**派生态**，筛选也走同一个 DB 函数 —— 判定真身只有一处。
      where.add(
          "aftersales_ticket_sla_state(t.reply_due_at, t.first_reply_at, ?::timestamptz) = ?");
      filterBinds.add(at);
      filterBinds.add(q.slaState().trim().toUpperCase(java.util.Locale.ROOT));
    }
    if (Boolean.TRUE.equals(q.overdueOnly())) {
      where.add(
          "t.closed_at is null and t.reply_due_at is not null"
              + " and t.reply_due_at < ?::timestamptz"
              + " and t.first_reply_at is null");
      filterBinds.add(at);
    }
    if (Boolean.TRUE.equals(q.unassignedOnly())) {
      where.add("t.assigned_to_ref = '' and t.closed_at is null");
    }
    if (q.from() != null) {
      where.add("t.created_at >= ?::timestamptz");
      filterBinds.add(q.from());
    }
    if (q.to() != null) {
      where.add("t.created_at < ?::timestamptz");
      filterBinds.add(q.to());
    }
    if (isSet(q.keyword())) {
      String kw = "%" + q.keyword().trim() + "%";
      where.add(
          "(t.ticket_no ilike ? or t.subject ilike ? or t.reason_note ilike ?"
              + " or t.assigned_to_ref ilike ? or c.case_no ilike ?)");
      filterBinds.add(kw);
      filterBinds.add(kw);
      filterBinds.add(kw);
      filterBinds.add(kw);
      filterBinds.add(kw);
    }
    return where;
  }

  /** 汇总（跨全表算，不随分页变小；口径与当前筛选一致）。 */
  private ServiceTicketSummary summary(String clause, List<Object> binds) {
    Record r =
        sql.fetchOne(
            " select count(*) as total,"
                + " count(*) filter (where t.ticket_status = 'OPEN') as open,"
                + " count(*) filter (where t.ticket_status = 'IN_PROGRESS') as in_progress,"
                + " count(*) filter (where t.ticket_status = 'WAITING_BUYER') as waiting_buyer,"
                + " count(*) filter (where t.ticket_status = 'RESOLVED') as resolved,"
                + " count(*) filter (where t.ticket_status = 'CANCELLED') as cancelled,"
                + " count(*) filter (where t.assigned_to_ref = '' and t.closed_at is null)"
                + "   as unassigned,"
                + " count(*) filter (where t.closed_at is null and t.reply_due_at is not null"
                + "   and t.reply_due_at < ?::timestamptz and t.first_reply_at is null) as overdue,"
                + " count(*) filter (where t.ticket_type = 'COMPLAINT') as complaint,"
                + " count(*) filter (where t.ticket_type = 'INQUIRY') as inquiry"
                + " from aftersales_service_ticket t"
                + " join aftersales_case c on c.tenant_id = t.tenant_id and c.id = t.case_id"
                + clause,
            binds.toArray());
    return new ServiceTicketSummary(
        r.get("total", Long.class),
        r.get("open", Long.class),
        r.get("in_progress", Long.class),
        r.get("waiting_buyer", Long.class),
        r.get("resolved", Long.class),
        r.get("cancelled", Long.class),
        r.get("unassigned", Long.class),
        r.get("overdue", Long.class),
        r.get("complaint", Long.class),
        r.get("inquiry", Long.class));
  }

  /**
   * SLA 达成统计（输出句「+ SLA 达成统计」）。
   *
   * <p>⚠️ 口径只认**关闭时冻结的快照**：join 回工单是为了让统计与当前筛选口径一致，
   * 但达标结论本身取自快照（不是现算的）—— 「当时有没有达标」是一个已经发生的事实。
   */
  private ServiceTicketSlaStats slaStats(String clause, List<Object> binds) {
    Record r =
        sql.fetchOne(
            " select count(*) as closed_count,"
                + " count(*) filter (where s.reply_due_at is not null) as reply_measured,"
                + " count(*) filter (where s.reply_met) as reply_met,"
                + " count(*) filter (where s.reply_due_at is not null and not s.reply_met)"
                + "   as reply_breached,"
                + " count(*) filter (where s.resolve_due_at is not null) as resolve_measured,"
                + " count(*) filter (where s.resolve_met) as resolve_met,"
                + " count(*) filter (where s.resolve_due_at is not null and s.resolve_met = false)"
                + "   as resolve_breached,"
                + " count(*) filter (where s.reply_met and s.resolve_met) as both_met"
                + " from aftersales_ticket_sla_snapshot s"
                + " join aftersales_service_ticket t on t.tenant_id = s.tenant_id"
                + "   and t.id = s.ticket_id"
                + " join aftersales_case c on c.tenant_id = t.tenant_id and c.id = t.case_id"
                + clause,
            binds.toArray());
    long closed = r.get("closed_count", Long.class);
    long replyMeasured = r.get("reply_measured", Long.class);
    long replyMet = r.get("reply_met", Long.class);
    long resolveMeasured = r.get("resolve_measured", Long.class);
    long resolveMet = r.get("resolve_met", Long.class);
    return new ServiceTicketSlaStats(
        closed,
        replyMeasured,
        replyMet,
        r.get("reply_breached", Long.class),
        resolveMeasured,
        resolveMet,
        r.get("resolve_breached", Long.class),
        r.get("both_met", Long.class),
        rate(replyMet, replyMeasured),
        rate(resolveMet, resolveMeasured));
  }

  /** 达成率（百分比，2 位小数）。分母为 0 时返回 {@code null} —— 0% 会把「没测过」说成「全没达标」。 */
  private static BigDecimal rate(long met, long measured) {
    if (measured <= 0) {
      return null;
    }
    return BigDecimal.valueOf(met)
        .multiply(BigDecimal.valueOf(100))
        .divide(BigDecimal.valueOf(measured), 2, RoundingMode.HALF_UP);
  }

  /** 独立的 SLA 达成统计（不随列表筛选走，按关闭时间窗）。 */
  public ServiceTicketSlaStats slaStatistics(OffsetDateTime from, OffsetDateTime to) {
    List<Object> binds = new ArrayList<>();
    binds.add(currentTenant());
    StringBuilder clause = new StringBuilder(" where s.tenant_id = ?::uuid");
    if (from != null) {
      clause.append(" and s.closed_at >= ?::timestamptz");
      binds.add(from);
    }
    if (to != null) {
      clause.append(" and s.closed_at < ?::timestamptz");
      binds.add(to);
    }
    return slaStats(clause.toString(), binds);
  }

  // ================================================================ 详情

  /** 单张工单全景（头 + 转派历史 + 事件流 + 评价 + SLA 快照 + 当前可执行动作）。 */
  public ServiceTicketDetail ticketDetail(UUID id) {
    ServiceTicketRow head = loadHead(id);
    List<ServiceTicketAssignmentRow> assignments =
        sql.fetch(
                " select * from aftersales_ticket_assignment"
                    + " where tenant_id = ?::uuid and ticket_id = ?::uuid"
                    + " order by created_at desc, id desc",
                currentTenant(),
                id)
            .map(ServiceTicketService::mapAssignment);
    List<ServiceTicketEventRow> events =
        sql.fetch(
                " select * from aftersales_ticket_event"
                    + " where tenant_id = ?::uuid and ticket_id = ?::uuid"
                    + " order by occurred_at desc, id desc",
                currentTenant(),
                id)
            .map(ServiceTicketService::mapEvent);
    List<ServiceTicketSatisfactionRow> satisfactions =
        sql.fetch(
                " select * from aftersales_ticket_satisfaction"
                    + " where tenant_id = ?::uuid and ticket_id = ?::uuid",
                currentTenant(),
                id)
            .map(ServiceTicketService::mapSatisfaction);
    List<ServiceTicketSlaSnapshotRow> slaSnapshots =
        sql.fetch(
                " select * from aftersales_ticket_sla_snapshot"
                    + " where tenant_id = ?::uuid and ticket_id = ?::uuid",
                currentTenant(),
                id)
            .map(ServiceTicketService::mapSnapshot);
    return new ServiceTicketDetail(
        head,
        assignments,
        events,
        satisfactions,
        slaSnapshots,
        actionsOf(head),
        OffsetDateTime.now(ZoneOffset.UTC),
        "工单永远挂在一张售后单上（预留表 case_id NOT NULL + UNIQUE(tenant_id, case_id)）："
            + "本工单服务的是售后单 " + head.caseNo() + "。转派 / 关闭 / 评价 / 升级"
            + "都是「已经发生过的事」，落在 append-only 的附属事实表里，不可改不可删。");
  }

  /** 载入工单头（带售后单上下文）。 */
  private ServiceTicketRow loadHead(UUID id) {
    // ⚠️ 绑定顺序 = select 的两个 at → where 的租户 → where 的 id
    List<Object> binds = new ArrayList<>();
    OffsetDateTime at = OffsetDateTime.now(ZoneOffset.UTC);
    binds.add(at);
    binds.add(at);
    binds.add(currentTenant());
    binds.add(id);
    Record r =
        sql.fetchOne(
            selectColumns() + from() + " where t.tenant_id = ?::uuid and t.id = ?::uuid",
            binds.toArray());
    if (r == null) {
      throw notFound("工单不存在或不属于当前租户：" + id);
    }
    return mapRow(r);
  }

  // ================================================================ 创建

  /**
   * 创建工单。
   *
   * <p>⚠️ 三道前置检查：售后单真实存在且属于当前租户；该售后单**还没转过工单**
   * （重复转要报失败并附上已有工单号）；SLA 时限解析得到（解析不到就报错，不编）。
   */
  public ServiceTicketDetail createTicket(ServiceTicketCreate request, UUID actorId) {
    UUID caseId = parseUuid(request.caseId(), "必须给 caseId（工单必须挂在一张售后单上）");
    ServiceTicketType type =
        parseEnum(ServiceTicketType.class, request.ticketType(), "工单类型");
    ServiceTicketSource source =
        isSet(request.sourceChannel())
            ? parseEnum(ServiceTicketSource.class, request.sourceChannel(), "创建来源")
            : ServiceTicketSource.MANUAL;
    ServiceTicketPriority priority =
        isSet(request.priority())
            ? parseEnum(ServiceTicketPriority.class, request.priority(), "工单优先级")
            : ServiceTicketPriority.NORMAL;

    // ---- 售后单必须真实存在、属于当前租户、且未结案 ----
    Record caseRow =
        sql.fetchOne(
            " select c.id, c.case_no, c.status as case_status, c.order_id,"
                + " o.order_source as platform_scope"
                + " from aftersales_case c"
                + " left join sales_order o on o.tenant_id = c.tenant_id and o.id = c.order_id"
                + " where c.tenant_id = ?::uuid and c.id = ?::uuid",
            currentTenant(),
            caseId);
    if (caseRow == null) {
      throw notFound("售后单不存在或不属于当前租户：" + caseId);
    }
    String caseStatus = caseRow.get("case_status", String.class);
    if (ServiceTicketCaseStatus.terminal(caseStatus)) {
      throw badRequest(
          "售后单 " + caseRow.get("case_no", String.class) + " 已结案（状态 " + caseStatus
              + "），不能再转工单");
    }

    // ---- 一张售后单只转一次工单：重复转要报失败并附上已有工单号 ----
    Record existing =
        sql.fetchOne(
            " select ticket_no from aftersales_service_ticket"
                + " where tenant_id = ?::uuid and case_id = ?::uuid",
            currentTenant(),
            caseId);
    if (existing != null) {
      throw badRequest(
          "该售后单已转过工单（工单号 " + existing.get("ticket_no", String.class) + "）。"
              + "重复转会产生两个号、两个负责人，「到底谁在跟这张单」从此没人说得清");
    }

    // ---- SLA 时限：解析租户 SLA 策略档，解析不到就报错（不编默认小时数） ----
    OffsetDateTime at = OffsetDateTime.now(ZoneOffset.UTC);
    String platformScope = caseRow.get("platform_scope", String.class);
    SlaDue sla = resolveSla(request, platformScope, at);

    UUID id = UUID.randomUUID();
    String ticketNo = nextTicketNo();
    sql.execute(
        " insert into aftersales_service_ticket(id,tenant_id,ticket_no,case_id,ticket_status,"
            + " priority,reason_note,owner_module,batch_id,created_by,created_at,"
            + " ticket_type,source_channel,source_ref,subject,"
            + " assigned_to_ref,assigned_to_user_id,assigned_at,knowledge_entry_id,"
            + " sla_policy_id,sla_reply_hours,sla_resolve_hours,reply_due_at,resolve_due_at,"
            + " version,updated_at)"
            + " values (?::uuid,?::uuid,?,?::uuid,'OPEN',?,?,'11.4.2.1',null,?::uuid,now(),"
            + " ?::varchar,?::varchar,?::varchar,?::varchar,"
            + " ?::varchar,?::uuid,?::timestamptz,?::uuid,"
            + " ?::uuid,?::integer,?::integer,?::timestamptz,?::timestamptz,"
            + " 0,now())",
        id,
        currentTenant(),
        ticketNo,
        caseId,
        priority.name(),
        trimOrEmpty(request.reasonNote()),
        actorId,
        type.name(),
        source.name(),
        trimOrEmpty(request.sourceRef()),
        trimOrEmpty(request.subject()),
        "",
        null,
        null,
        request.knowledgeEntryId(),
        sla.policyId(),
        sla.replyHours(),
        sla.resolveHours(),
        sla.replyDueAt(),
        sla.resolveDueAt());

    writeEvent(id, "CREATED", "", "OPEN", actorId, "已创建工单 " + ticketNo + "（" + type.label() + "）");

    // ---- 首派（可选）：人工指定一个责任人 ----
    if (isSet(request.assignedToRef())) {
      // ⚠️ 版本号：刚插入的行 version=0，首派前它没有被别人改过。
      requireRosterMember(request.assignedToRef().trim());
      assignInternal(
          id,
          request.assignedToRef().trim(),
          null,
          ServiceTicketAssignMode.MANUAL,
          actorId,
          "建单时指定责任人",
          actorId,
          0L);
    }
    return ticketDetail(id);
  }

  /**
   * 解析 SLA 时限。
   *
   * <p>优先级：请求显式给的（{@code PLATFORM_GIVEN}）> 租户 SLA 策略档（{@code POLICY_SLA}）。
   * 策略档按**平台精确档 &gt; '' 兜底档**取**当时生效**的那一档（半开区间）。
   */
  private SlaDue resolveSla(ServiceTicketCreate request, String platformScope, OffsetDateTime at) {
    if (request.replyDueAt() != null) {
      return new SlaDue(
          request.replyDueAt(),
          request.resolveDueAt(),
          ServiceTicketSlaBasis.PLATFORM_GIVEN,
          null,
          null,
          null);
    }
    String scope = platformScope == null ? "" : platformScope;
    Record sla =
        sql.fetchOne(
            " select s.id, s.reply_sla_hours, s.resolve_sla_hours from aftersales_reply_sla s"
                + " where s.tenant_id = ?::uuid and s.platform_scope in (?,'')"
                + " and s.effective_from <= ?::timestamptz"
                + " and (s.effective_to is null or s.effective_to > ?::timestamptz)"
                + " order by case when s.platform_scope = ? then 0 else 1 end,"
                + "          s.effective_from desc"
                + " limit 1",
            currentTenant(),
            scope,
            at,
            at,
            scope);
    if (sla == null) {
      throw badRequest(
          "既没有本次指定的回复时限，也没有配置租户回复时限策略（aftersales_reply_sla，"
              + "SLA 计时是本模块的核心职责，本服务不会替租户编一个默认小时数）："
              + "请先在受理策略里配一档 SLA，或在本次建单时带上 replyDueAt");
    }
    Integer hours = sla.get("reply_sla_hours", Integer.class);
    Integer resolveHours = sla.get("resolve_sla_hours", Integer.class);
    OffsetDateTime replyDue = at.plusHours(hours);
    OffsetDateTime resolveDue = resolveHours == null ? null : at.plusHours(resolveHours);
    if (request.resolveDueAt() != null) {
      resolveDue = request.resolveDueAt();
    }
    if (resolveDue != null && replyDue.isAfter(resolveDue)) {
      throw badRequest("处理时限不能早于回复时限");
    }
    return new SlaDue(
        replyDue, resolveDue, ServiceTicketSlaBasis.POLICY_SLA, sla.get("id", UUID.class), hours, resolveHours);
  }

  // ================================================================ 分配 / 转派

  /** 人工派单：指定一个责任人。 */
  public ServiceTicketDetail assignTicket(UUID id, ServiceTicketAssign request, UUID actorId) {
    String ownerRef = requireText(request.ownerRef(), "责任人");
    requireRosterMember(ownerRef);
    return assignInternal(
        id, ownerRef, request.ownerUserId(), ServiceTicketAssignMode.MANUAL, actorId,
        request.reasonNote(), actorId, request.expectedVersion());
  }

  /**
   * 按名册**加权最少在办**自动派单（分配规则）。
   *
   * <p>排序键是 {@code 在办工单数 / 权重}：权重 100 的人分到的单是权重 1 的百倍。
   * 同负载时按 {@code owner_ref} 字典序定胜负 ——
   * 不加这一句的话，「同一批单为什么都派给了同一个人」就没法复现。
   */
  public ServiceTicketDetail autoAssignTicket(UUID id, ServiceTicketAutoAssign request, UUID actorId) {
    Record row =
        sql.fetchOne(
            " select r.owner_ref,"
                + " (select count(*) from aftersales_service_ticket t"
                + "   where t.tenant_id = r.tenant_id and t.assigned_to_ref = r.owner_ref"
                + "     and t.closed_at is null)::numeric / r.weight as load_ratio"
                + " from aftersales_owner_roster r"
                + " where r.tenant_id = ?::uuid and r.accepting_new_cases"
                + " order by load_ratio asc, r.owner_ref asc"
                + " limit 1",
            currentTenant());
    if (row == null) {
      // 如实报出「没人可派」：空责任人会让这张单在列表里没人认领，
      // 而「待认领」本身就是一种可读的状态。
      throw badRequest(
          "责任人名册里没有可接新单的人（名册为空，或全部停接新单）："
              + "本模块不替租户编一个「默认客服」—— 那会让这张单在绩效口径上落到一个不存在的人头上");
    }
    return assignInternal(
        id,
        row.get("owner_ref", String.class),
        null,
        ServiceTicketAssignMode.ROSTER_ROUND_ROBIN,
        actorId,
        request.reasonNote(),
        actorId,
        request.expectedVersion());
  }

  /** 内部派单动作。 */
  private ServiceTicketDetail assignInternal(
      UUID ticketId,
      String ownerRef,
      UUID ownerUserId,
      ServiceTicketAssignMode mode,
      UUID actorId,
      String reasonNote,
      UUID eventActor,
      Long expectedVersion) {
    Record head = requireOpenTicket(ticketId, "派单");
    requireVersion(head.get("version", Long.class), expectedVersion, "工单");
    String fromRef = head.get("assigned_to_ref", String.class);
    boolean firstAssign = !isSet(fromRef);

    sql.execute(
        " update aftersales_service_ticket"
            + " set assigned_to_ref = ?, assigned_to_user_id = ?::uuid, assigned_at = ?::timestamptz,"
            + "     version = version + 1, updated_at = ?::timestamptz"
            + " where id = ?::uuid and tenant_id = ?::uuid",
        ownerRef,
        ownerUserId,
        OffsetDateTime.now(ZoneOffset.UTC),
        OffsetDateTime.now(ZoneOffset.UTC),
        ticketId,
        currentTenant());

    sql.execute(
        " insert into aftersales_ticket_assignment(id,tenant_id,ticket_id,assign_action,assign_mode,"
            + " from_owner_ref,from_owner_user_id,to_owner_ref,to_owner_user_id,reason_note,"
            + " created_by,created_at)"
            + " values (?::uuid,?::uuid,?::uuid,?,?,?,?::uuid,?,?::uuid,?,?::uuid,?::timestamptz)",
        UUID.randomUUID(),
        currentTenant(),
        ticketId,
        firstAssign ? ServiceTicketAssignAction.ASSIGN.name() : ServiceTicketAssignAction.TRANSFER.name(),
        mode.name(),
        fromRef,
        head.get("assigned_to_user_id", UUID.class),
        ownerRef,
        ownerUserId,
        trimOrEmpty(reasonNote),
        actorId,
        OffsetDateTime.now(ZoneOffset.UTC));

    writeEvent(
        ticketId,
        firstAssign ? "ASSIGNED" : "TRANSFERRED",
        head.get("ticket_status", String.class),
        head.get("ticket_status", String.class),
        eventActor,
        (firstAssign ? "已派给 " : "已转派给 ") + ownerRef);
    return ticketDetail(ticketId);
  }

  /** 转派：必须已有责任人，必须写理由。 */
  public ServiceTicketDetail transferTicket(UUID id, ServiceTicketTransfer request, UUID actorId) {
    String toRef = requireText(request.toOwnerRef(), "转派目标责任人");
    String reason = requireText(request.reasonNote(), "转派理由");
    requireRosterMember(toRef);
    Record head = requireOpenTicket(id, "转派");
    String fromRef = head.get("assigned_to_ref", String.class);
    if (!isSet(fromRef)) {
      throw badRequest(
          "这张工单当前没有责任人，没有「转」的对象 —— 那叫首派（派单），不叫转派");
    }
    if (fromRef.equals(toRef)) {
      throw badRequest("转派目标与当前责任人相同（" + toRef + "），这次转派什么也没改变");
    }
    return assignInternal(
        id, toRef, request.toOwnerUserId(), ServiceTicketAssignMode.MANUAL, actorId, reason, actorId,
        request.expectedVersion());
  }

  /** 认领：只能认领**当前无责任人**的工单。 */
  public ServiceTicketDetail claimTicket(UUID id, ServiceTicketClaim request, UUID actorId) {
    String ownerRef = requireText(request.ownerRef(), "认领人");
    requireRosterMember(ownerRef);
    Record head = requireOpenTicket(id, "认领");
    if (isSet(head.get("assigned_to_ref", String.class))) {
      throw badRequest(
          "这张工单已有责任人 "
              + head.get("assigned_to_ref", String.class)
              + "，不能认领（要接手请让对方先转派或释放）");
    }
    return assignInternal(
        id, ownerRef, request.ownerUserId(), ServiceTicketAssignMode.CLAIM, actorId,
        request.reasonNote(), actorId, request.expectedVersion());
  }

  /** 释放责任人：把单放回待处理池。必须写理由。 */
  public ServiceTicketDetail releaseTicket(UUID id, ServiceTicketRelease request, UUID actorId) {
    String reason = requireText(request.reasonNote(), "释放理由");
    Record head = requireOpenTicket(id, "释放");
    requireVersion(head.get("version", Long.class), request.expectedVersion(), "工单");
    String fromRef = head.get("assigned_to_ref", String.class);
    if (!isSet(fromRef)) {
      throw badRequest("这张工单当前没有责任人，没有可释放的对象");
    }
    sql.execute(
        " update aftersales_service_ticket"
            + " set assigned_to_ref = '', assigned_to_user_id = null, assigned_at = null,"
            + "     version = version + 1, updated_at = ?::timestamptz"
            + " where id = ?::uuid and tenant_id = ?::uuid",
        OffsetDateTime.now(ZoneOffset.UTC),
        id,
        currentTenant());
    sql.execute(
        " insert into aftersales_ticket_assignment(id,tenant_id,ticket_id,assign_action,assign_mode,"
            + " from_owner_ref,from_owner_user_id,to_owner_ref,to_owner_user_id,reason_note,"
            + " created_by,created_at)"
            + " values (?::uuid,?::uuid,?::uuid,'RELEASE','MANUAL',?,?::uuid,'',null,?,?::uuid,?::timestamptz)",
        UUID.randomUUID(),
        currentTenant(),
        id,
        fromRef,
        head.get("assigned_to_user_id", UUID.class),
        trimOrEmpty(reason),
        actorId,
        OffsetDateTime.now(ZoneOffset.UTC));
    writeEvent(id, "RELEASED", head.get("ticket_status", String.class),
        head.get("ticket_status", String.class), actorId, "已释放责任人 " + fromRef + "：" + reason);
    return ticketDetail(id);
  }

  // ================================================================ 处理与回复

  /** 记录首次回复：回复时限的完成时刻，只能记一次。 */
  public ServiceTicketDetail recordFirstReply(UUID id, ServiceTicketFirstReply request, UUID actorId) {
    Record head = requireOpenTicket(id, "记录首次回复");
    requireVersion(head.get("version", Long.class), request.expectedVersion(), "工单");
    if (head.get("first_reply_at", OffsetDateTime.class) != null) {
      throw badRequest(
          "这张工单已经记录过首次回复（"
              + head.get("first_reply_at", OffsetDateTime.class)
              + "）：回复计时停在第一次，重记它等于改写当时的达标结论");
    }
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    sql.execute(
        " update aftersales_service_ticket"
            + " set first_reply_at = ?::timestamptz, version = version + 1, updated_at = ?::timestamptz"
            + " where id = ?::uuid and tenant_id = ?::uuid",
        now,
        now,
        id,
        currentTenant());
    writeEvent(id, "FIRST_REPLIED", head.get("ticket_status", String.class),
        head.get("ticket_status", String.class), actorId, trimOrEmpty(request.replyNote()));
    return ticketDetail(id);
  }

  /** 状态流转（待处理 ⇄ 处理中 ⇄ 待买家）。终态不走这里 —— 关闭必须写原因。 */
  public ServiceTicketDetail changeStatus(UUID id, ServiceTicketStatusChange request, UUID actorId) {
    ServiceTicketStatus target =
        parseEnum(ServiceTicketStatus.class, request.targetStatus(), "目标状态");
    Record head = requireOpenTicket(id, "状态流转");
    requireVersion(head.get("version", Long.class), request.expectedVersion(), "工单");
    ServiceTicketStatus current = ServiceTicketStatus.valueOf(head.get("ticket_status", String.class));
    if (!allowedTargets(current).contains(target)) {
      throw badRequest(
          "工单当前是「" + current.label() + "」，不能流转到「" + target.label() + "」（允许："
              + allowedTargets(current).stream().map(ServiceTicketStatus::label).toList() + "）");
    }
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    sql.execute(
        " update aftersales_service_ticket set ticket_status = ?,"
            + " waiting_buyer_at = case when ? = 'WAITING_BUYER' then ?::timestamptz"
            + "   when ? = 'IN_PROGRESS' then null else waiting_buyer_at end,"
            + " version = version + 1, updated_at = ?::timestamptz"
            + " where id = ?::uuid and tenant_id = ?::uuid",
        target.name(),
        target.name(),
        now,
        target.name(),
        now,
        id,
        currentTenant());
    writeEvent(
        id,
        target == ServiceTicketStatus.WAITING_BUYER ? "WAITING_BUYER" : "STATUS_CHANGED",
        current.name(),
        target.name(),
        actorId,
        trimOrEmpty(request.note()));
    return ticketDetail(id);
  }

  /** 允许的目标状态。 */
  private static Set<ServiceTicketStatus> allowedTargets(ServiceTicketStatus current) {
    return switch (current) {
      case OPEN -> Set.of(ServiceTicketStatus.IN_PROGRESS, ServiceTicketStatus.WAITING_BUYER);
      case IN_PROGRESS -> Set.of(ServiceTicketStatus.WAITING_BUYER);
      case WAITING_BUYER -> Set.of(ServiceTicketStatus.IN_PROGRESS);
      case RESOLVED, CANCELLED -> Set.of();
    };
  }

  // ================================================================ 关闭

  /**
   * 关闭工单。
   *
   * <p>⚠️ 关闭**必须带原因**；「关闭」与「办完」是两件事 ——
   * 「买家未回应」「不再跟进」也落终态，但落的是 {@code CANCELLED} 而不是 {@code RESOLVED}。
   *
   * <p>关闭那一刻把 SLA 达成**冻结**成快照（由 DB 用同一个判定函数算出）。
   */
  public ServiceTicketDetail closeTicket(UUID id, ServiceTicketClose request, UUID actorId) {
    ServiceTicketCloseReason reason =
        parseEnum(ServiceTicketCloseReason.class, request.closeReason(), "关闭原因");
    Record head = requireOpenTicket(id, "关闭");
    requireVersion(head.get("version", Long.class), request.expectedVersion(), "工单");
    ServiceTicketStatus current = ServiceTicketStatus.valueOf(head.get("ticket_status", String.class));
    ServiceTicketStatus target =
        reason.resolved() ? ServiceTicketStatus.RESOLVED : ServiceTicketStatus.CANCELLED;
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    sql.execute(
        " update aftersales_service_ticket"
            + " set ticket_status = ?, closed_at = ?::timestamptz, close_reason = ?, close_note = ?,"
            + "     version = version + 1, updated_at = ?::timestamptz"
            + " where id = ?::uuid and tenant_id = ?::uuid",
        target.name(),
        now,
        reason.name(),
        trimOrEmpty(request.closeNote()),
        now,
        id,
        currentTenant());
    // ⚠️ SLA 达成快照：**由 DB 用同一个判定函数算出**（不另立一套口径），
    //   「当时有没有达标」是已经发生的事实，冻结下来给绩效口径用。
    sql.execute(
        " insert into aftersales_ticket_sla_snapshot(id,tenant_id,ticket_id,sla_policy_id,"
            + " sla_reply_hours,sla_resolve_hours,reply_due_at,resolve_due_at,first_reply_at,"
            + " closed_at,reply_met,resolve_met,snapshotted_at)"
            + " select ?::uuid, t.tenant_id, t.id, t.sla_policy_id, t.sla_reply_hours,"
            + "   t.sla_resolve_hours, t.reply_due_at, t.resolve_due_at, t.first_reply_at,"
            + "   t.closed_at,"
            + "   aftersales_ticket_sla_state(t.reply_due_at, t.first_reply_at, t.closed_at) = 'MET',"
            + "   case when t.resolve_due_at is null then null else"
            + "     aftersales_ticket_sla_state(t.resolve_due_at, t.closed_at, t.closed_at) = 'MET'"
            + "   end,"
            + "   ?::timestamptz"
            + " from aftersales_service_ticket t"
            + " where t.tenant_id = ?::uuid and t.id = ?::uuid",
        UUID.randomUUID(),
        now,
        currentTenant(),
        id);
    writeEvent(
        id,
        target == ServiceTicketStatus.RESOLVED ? "CLOSED" : "CANCELLED",
        current.name(),
        target.name(),
        actorId,
        reason.label() + (isSet(request.closeNote()) ? "：" + request.closeNote().trim() : ""));
    return ticketDetail(id);
  }

  // ================================================================ 满意度

  /** 满意度评价：只对**已关闭**的工单开放，一张工单一条。 */
  public ServiceTicketDetail recordSatisfaction(
      UUID id, ServiceTicketSatisfactionCreate request, UUID actorId) {
    Integer score = request.score();
    if (score == null || score < 1 || score > MAX_SATISFACTION_SCORE) {
      throw badRequest("满意度分值必须在 1~5 之间：收到 " + score);
    }
    Record head = requireTicket(id, "评价");
    ServiceTicketStatus status = ServiceTicketStatus.valueOf(head.get("ticket_status", String.class));
    if (!status.terminal()) {
      throw badRequest(
          "工单还没关闭（当前是「" + status.label() + "」），评价不了 —— 评价是针对**已结束**的服务的");
    }
    long exists =
        sql.fetchOne(
                " select count(*) from aftersales_ticket_satisfaction"
                    + " where tenant_id = ?::uuid and ticket_id = ?::uuid",
                currentTenant(),
                id)
            .get(0, Long.class);
    if (exists > 0) {
      throw badRequest(
          "这张工单已经评价过了：评价是「这次服务怎么样」的**一次**结论，"
              + "改它等于把买家给过的一次反馈重写");
    }
    sql.execute(
        " insert into aftersales_ticket_satisfaction(id,tenant_id,ticket_id,score,comment,rated_by,rated_at)"
            + " values (?::uuid,?::uuid,?::uuid,?::smallint,?,?::uuid,?::timestamptz)",
        UUID.randomUUID(),
        currentTenant(),
        id,
        score,
        trimOrEmpty(request.comment()),
        actorId,
        OffsetDateTime.now(ZoneOffset.UTC));
    writeEvent(id, "SATISFACTION_RECORDED", head.get("ticket_status", String.class),
        head.get("ticket_status", String.class), actorId, "满意度 " + score + " 分");
    return ticketDetail(id);
  }

  // ================================================================ 升级

  /**
   * 升级：咨询 → 投诉（新建售后单）/ 投诉 → 纠纷（新建平台纠纷）。
   *
   * <p>⚠️ 两条通道都落在**同服务同库**的真实表上（11.1.1.1 / 11.4.1.1），
   * 并由**真外键**钉住 —— 「升级了但对方根本不存在」存不进库里。
   * 上下文（工单号 / 售后单号 / 主题 / 诉求）在升级物里**原文保留**。
   */
  public ServiceTicketDetail escalateTicket(UUID id, ServiceTicketEscalate request, UUID actorId) {
    ServiceTicketEscalateTarget target =
        parseEnum(ServiceTicketEscalateTarget.class, request.targetType(), "升级去向");
    String reason = requireText(request.reasonNote(), "升级理由");
    Record head = requireOpenTicket(id, "升级");
    requireVersion(head.get("version", Long.class), request.expectedVersion(), "工单");
    ServiceTicketType currentType =
        ServiceTicketType.valueOf(head.get("ticket_type", String.class));
    if (target == ServiceTicketEscalateTarget.COMPLAINT) {
      if (currentType != ServiceTicketType.INQUIRY) {
        throw badRequest(
            "只有「咨询」能升级为投诉（当前是「" + currentType.label() + "」）—— "
                + "类型推进是**带方向**的，不能把投诉退回咨询");
      }
      return escalateToComplaint(id, head, request, reason, actorId);
    }
    if (currentType != ServiceTicketType.COMPLAINT) {
      throw badRequest(
          "只有「投诉」能升级为纠纷（当前是「" + currentType.label() + "」）—— "
              + "咨询要先升级为投诉（那一步会建出一张售后单），再谈纠纷");
    }
    return escalateToDispute(id, head, request, reason, actorId);
  }

  /** 咨询 → 投诉：在原售后单之外新建一张售后单，并把新单号记回工单。 */
  private ServiceTicketDetail escalateToComplaint(
      UUID id, Record head, ServiceTicketEscalate request, String reason, UUID actorId) {
    UUID originCaseId = head.get("case_id", UUID.class);
    Record origin =
        sql.fetchOne(
            " select c.id, c.case_no, c.order_id from aftersales_case c"
                + " where c.tenant_id = ?::uuid and c.id = ?::uuid",
            currentTenant(),
            originCaseId);
    if (origin == null) {
      throw notFound("工单挂靠的售后单不存在或不属于当前租户：" + originCaseId);
    }
    String context =
        "工单 "
            + head.get("ticket_no", String.class)
            + "（原售后单 "
            + origin.get("case_no", String.class)
            + "）升级为投诉。升级理由："
            + reason
            + "。工单主题："
            + head.get("subject", String.class)
            + "。工单诉求："
            + head.get("reason_note", String.class);
    AftersalesCaseModels.AftersalesCaseCreate create =
        new AftersalesCaseModels.AftersalesCaseCreate(
            origin.get("order_id", UUID.class),
            requireText(request.targetCaseType(), "升级后的售后类型"),
            requireText(request.targetReasonCode(), "升级后的原因分类"),
            reason,
            request.targetAmount(),
            requireText(request.targetCurrency(), "升级后的币种"),
            request.targetQuantity(),
            head.get("source_ref", String.class),
            null,
            null,
            context,
            head.get("reply_due_at", OffsetDateTime.class),
            head.get("resolve_due_at", OffsetDateTime.class),
            OffsetDateTime.now(ZoneOffset.UTC),
            head.get("assigned_to_ref", String.class),
            null,
            null,
            "TENANT_MAINTAINED",
            null,
            OffsetDateTime.now(ZoneOffset.UTC));
    AftersalesCaseModels.AftersalesCaseRow created = cases.create(create, actorId);
    sql.execute(
        " update aftersales_service_ticket"
            + " set ticket_type = 'COMPLAINT', escalated_case_id = ?::uuid,"
            + "     version = version + 1, updated_at = ?::timestamptz"
            + " where id = ?::uuid and tenant_id = ?::uuid",
        created.id(),
        OffsetDateTime.now(ZoneOffset.UTC),
        id,
        currentTenant());
    writeEvent(id, "ESCALATED_TO_COMPLAINT", head.get("ticket_status", String.class),
        head.get("ticket_status", String.class), actorId,
        "已升级为投诉，新建售后单 " + created.caseNo() + "：" + reason);
    return ticketDetail(id);
  }

  /** 投诉 → 纠纷：新建一条平台纠纷（11.4.1.1），并把纠纷号记回工单。 */
  private ServiceTicketDetail escalateToDispute(
      UUID id, Record head, ServiceTicketEscalate request, String reason, UUID actorId) {
    // ⚠️ 工单表上**没有** case_no 列（只有 case_id）—— 上下文里的售后单号要另查一次。
    String caseNo =
        sql.fetchOne(
                " select c.case_no from aftersales_case c"
                    + " where c.tenant_id = ?::uuid and c.id = ?::uuid",
                currentTenant(),
                head.get("case_id", UUID.class))
            .get("case_no", String.class);
    String context =
        "工单 "
            + head.get("ticket_no", String.class)
            + "（售后单 "
            + caseNo
            + "）升级为纠纷。升级理由："
            + reason
            + "。工单主题："
            + head.get("subject", String.class)
            + "。工单诉求："
            + head.get("reason_note", String.class);
    PlatformDisputeModels.PlatformDisputeCreate create =
        new PlatformDisputeModels.PlatformDisputeCreate(
            requireText(request.platformCode(), "平台码"),
            requireText(request.platformName(), "平台名称"),
            requireText(request.platformDisputeNo(), "平台纠纷单号"),
            requireText(request.disputeType(), "纠纷类型"),
            context,
            trimOrEmpty(request.platformOrderNo()),
            requireText(request.disputeCurrency(), "争议币种"),
            request.disputeAmount(),
            request.evidenceDeadline(),
            null,
            null,
            null,
            null);
    PlatformDisputeModels.PlatformDisputeDetail created = disputes.createDispute(create, actorId);
    sql.execute(
        " update aftersales_service_ticket"
            + " set escalated_dispute_id = ?::uuid, version = version + 1, updated_at = ?::timestamptz"
            + " where id = ?::uuid and tenant_id = ?::uuid",
        created.head().id(),
        OffsetDateTime.now(ZoneOffset.UTC),
        id,
        currentTenant());
    writeEvent(id, "ESCALATED_TO_DISPUTE", head.get("ticket_status", String.class),
        head.get("ticket_status", String.class), actorId,
        "已升级为纠纷，新建平台纠纷 " + created.head().disputeNo() + "：" + reason);
    return ticketDetail(id);
  }

  // ================================================================ 动作清单

  /** 当前可执行的动作（每个都带禁用理由）。 */
  private static List<ServiceTicketAction> actionsOf(ServiceTicketRow head) {
    List<ServiceTicketAction> actions = new ArrayList<>();
    ServiceTicketStatus status = ServiceTicketStatus.valueOf(head.ticketStatus());
    boolean open = status.open();
    boolean hasOwner = isSet(head.assignedToRef());
    boolean firstReplyRecorded = head.firstReplyAt() != null;
    boolean closed = status.terminal();

    actions.add(
        action("ASSIGN", "人工派单", open, "POST", "/trade/service-tickets/{id}/assign", false,
            "仅未关闭", open ? null : "工单已关闭，不能再派单"));
    actions.add(
        action("AUTO_ASSIGN", "按名册轮转派单", open, "POST",
            "/trade/service-tickets/{id}/auto-assign", false, "仅未关闭",
            open ? null : "工单已关闭，不能再派单"));
    actions.add(
        action("TRANSFER", "转派", open && hasOwner, "POST", "/trade/service-tickets/{id}/transfer", true,
            "仅未关闭且已有责任人", blocked(open, hasOwner, "工单已关闭，不能再转派",
                "这张工单当前没有责任人，没有「转」的对象 —— 那叫首派（派单），不叫转派")));
    actions.add(
        action("CLAIM", "认领", open && !hasOwner, "POST", "/trade/service-tickets/{id}/claim", false,
            "仅未关闭且当前无责任人",
            blocked(open, hasOwner, "工单已关闭，不能再认领",
                "这张工单已有责任人 " + head.assignedToRef() + "，不能认领")));
    actions.add(
        action("RELEASE", "释放责任人", open && hasOwner, "POST",
            "/trade/service-tickets/{id}/release", true, "仅未关闭且已有责任人",
            blocked(open, hasOwner, "工单已关闭，不需要再释放",
                "这张工单当前没有责任人，没有可释放的对象")));
    actions.add(
        action("FIRST_REPLY", "记录首次回复", open && !firstReplyRecorded, "POST",
            "/trade/service-tickets/{id}/first-reply", false, "仅未关闭且尚未记录过首次回复",
            blocked(open, firstReplyRecorded, "工单已关闭，不需要再记录首次回复",
                "已经记录过首次回复，回复计时停在第一次")));
    actions.add(
        action("CHANGE_STATUS", "状态流转", open, "POST", "/trade/service-tickets/{id}/status", false,
            "仅未关闭", open ? null : "终态不可再流转"));
    actions.add(
        action("CLOSE", "关闭工单", open, "POST", "/trade/service-tickets/{id}/close", true,
            "仅未关闭；必须写关闭原因", open ? null : "工单已经关闭了"));
    actions.add(
        action("SATISFACTION", "满意度评价", closed, "POST",
            "/trade/service-tickets/{id}/satisfaction", false, "仅已关闭",
            closed ? null : "工单还没关闭，评价不了 —— 评价是针对**已结束**的服务的"));
    actions.add(
        action("ESCALATE", "升级（转投诉 / 转纠纷）", open, "POST",
            "/trade/service-tickets/{id}/escalate", true, "仅未关闭；必须写理由",
            open ? null : "工单已关闭，不能再升级"));
    return actions;
  }

  private static String blocked(
      boolean first, boolean second, String firstMessage, String secondMessage) {
    if (!first) {
      return firstMessage;
    }
    return second ? secondMessage : null;
  }

  // ================================================================ 内部：工具

  private static ServiceTicketSectionCapability section(
      String section, String label, boolean available, String ownerModule, String reason,
      String landsIn) {
    return new ServiceTicketSectionCapability(section, label, available, ownerModule, reason, landsIn);
  }

  private static ServiceTicketRelatedModule related(
      String id, String label, boolean delivered, String ownerModule, String reason, String href) {
    return new ServiceTicketRelatedModule(id, label, delivered, ownerModule, reason, href);
  }

  private static ServiceTicketCapabilityAction action(
      String code, String label, boolean allowed, String method, String path, boolean required,
      String precondition, String blockingReason, String ownerModule, String unavailableReason) {
    return new ServiceTicketCapabilityAction(
        code, label, allowed, method, path, required, precondition, blockingReason, ownerModule,
        unavailableReason);
  }

  private static ServiceTicketBoundary boundary(String boundary, String ownerModule, String reason) {
    return new ServiceTicketBoundary(boundary, ownerModule, reason);
  }

  /** 当前可执行动作（8 参：与 {@link ServiceTicketCapabilityAction} 同形但少两个归属字段）。 */
  private static ServiceTicketAction action(
      String code,
      String label,
      boolean allowed,
      String method,
      String path,
      boolean required,
      String precondition,
      String blockedReason) {
    return new ServiceTicketAction(
        code, label, allowed, method, path, required, precondition, blockedReason);
  }

  /**
   * 当前租户。
   *
   * <p>来自 {@code app.current_tenant}（由 {@code AccessService.run} 在事务内绑定），
   * <b>不是</b>任何请求头。查询里仍显式带 {@code tenant_id = ?} 谓词，
   * 与 FORCE RLS 形成两道独立的防线。
   */
  private UUID currentTenant() {
    // fetchOne + get(..., String.class) 而不是 fetchValue(..., String.class)：
    // 后者在 DSLContext 上有多个重载，类型推断会落到返回 Object 的那个。
    return UUID.fromString(
        sql.fetchOne("select current_setting('app.current_tenant')").get(0, String.class));
  }

  /** 工单号：与 11.1.1.1 的转工单**共用一个号段**（一个号段 ⇒ 一个空间）。 */
  private String nextTicketNo() {
    long n = sql.fetchOne("select nextval('aftersales_ticket_no_seq')").get(0, Long.class);
    return "TK" + n;
  }

  /** 取工单头（不存在 ⇒ 404，与「别的租户的工单」**完全相同**的错误）。 */
  private Record requireTicket(UUID id, String action) {
    Record r =
        sql.fetchOne(
            " select * from aftersales_service_ticket where tenant_id = ?::uuid and id = ?::uuid",
            currentTenant(),
            id);
    if (r == null) {
      throw notFound("工单不存在或不属于当前租户：" + id);
    }
    return r;
  }

  /** 取工单头并要求**未关闭**。 */
  private Record requireOpenTicket(UUID id, String action) {
    Record r = requireTicket(id, action);
    ServiceTicketStatus status = ServiceTicketStatus.valueOf(r.get("ticket_status", String.class));
    if (status.terminal()) {
      throw badRequest(
          "工单当前是「" + status.label() + "」，不能" + action + "（终态不可再流转）");
    }
    return r;
  }

  /** 责任人必须在名册里（名册是分配规则的唯一数据源）。 */
  private void requireRosterMember(String ownerRef) {
    Record r =
        sql.fetchOne(
            " select owner_ref from aftersales_owner_roster"
                + " where tenant_id = ?::uuid and owner_ref = ?",
            currentTenant(),
            ownerRef);
    if (r == null) {
      throw badRequest(
          "责任人 " + ownerRef + " 不在名册里（aftersales_owner_roster）："
              + "本模块不凭空造一个责任人 —— 名字不在名册上就没人可追责");
    }
  }

  /** 写一条事件留痕（append-only）。 */
  private void writeEvent(
      UUID ticketId,
      String eventType,
      String fromStatus,
      String toStatus,
      UUID actorId,
      String note) {
    sql.execute(
        " insert into aftersales_ticket_event(id,tenant_id,ticket_id,event_type,from_status,to_status,"
            + " actor_id,note,occurred_at)"
            + " values (?::uuid,?::uuid,?::uuid,?,?,?,?::uuid,?,?::timestamptz)",
        UUID.randomUUID(),
        currentTenant(),
        ticketId,
        eventType,
        fromStatus == null ? "" : fromStatus,
        toStatus == null ? "" : toStatus,
        actorId,
        trimOrEmpty(note),
        OffsetDateTime.now(ZoneOffset.UTC));
  }

  private static void requireVersion(Long current, Long expected, String label) {
    if (expected == null) {
      throw badRequest("必须带 expectedVersion（改状态要走乐观锁，避免两个人同时改同一张工单）");
    }
    if (!expected.equals(current)) {
      throw badRequest(
          label + "已被别人改过（当前版本 " + current + "，你拿到的是 " + expected + "），请刷新后重试");
    }
  }

  private static <E extends Enum<E>> E parseEnum(Class<E> type, String raw, String label) {
    if (!isSet(raw)) {
      throw badRequest("必须给出" + label);
    }
    try {
      return Enum.valueOf(type, raw.trim().toUpperCase(java.util.Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw badRequest(
          label + "取值非法：" + raw + "（可选：" + java.util.Arrays.toString(type.getEnumConstants()) + "）");
    }
  }

  private static UUID parseUuid(String raw, String message) {
    if (!isSet(raw)) {
      throw badRequest(message);
    }
    try {
      return UUID.fromString(raw.trim());
    } catch (IllegalArgumentException e) {
      throw badRequest(message + "（不是合法 UUID：" + raw + "）");
    }
  }

  private static String requireText(String raw, String label) {
    if (!isSet(raw)) {
      throw badRequest("必须给出" + label);
    }
    return raw.trim();
  }

  private static boolean isSet(String value) {
    return value != null && !value.isBlank();
  }

  private static String trimOrEmpty(String value) {
    return isSet(value) ? value.trim() : "";
  }

  private static ResponseStatusException badRequest(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  private static ResponseStatusException notFound(String message) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
  }

  // ================================================================ 行 → 传输模型

  private static ServiceTicketRow mapRow(Record r) {
    String type = r.get("ticket_type", String.class);
    String status = r.get("ticket_status", String.class);
    String priority = r.get("priority", String.class);
    String source = r.get("source_channel", String.class);
    String replyState = r.get("reply_sla_state", String.class);
    String resolveState = r.get("resolve_sla_state", String.class);
    OffsetDateTime replyDue = r.get("reply_due_at", OffsetDateTime.class);
    OffsetDateTime firstReply = r.get("first_reply_at", OffsetDateTime.class);
    String closeReason = r.get("close_reason", String.class);
    OffsetDateTime evaluatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    // ⚠️ SLA 口径的来源是**派生态**：命中了租户 SLA 档（sla_policy_id 非空）就是策略档算的，
    //   否则就是本次显式给的（平台给定）。判定真身不在前端，也不在这里另立一套。
    String slaBasis =
        r.get("sla_policy_id", UUID.class) != null
            ? ServiceTicketSlaBasis.POLICY_SLA.name()
            : (replyDue != null ? ServiceTicketSlaBasis.PLATFORM_GIVEN.name() : "");
    String slaBasisLabel = label(ServiceTicketSlaBasis.class, slaBasis);
    long secondsToReplyDue =
        replyDue == null || firstReply != null
            ? 0
            : Math.max(0, java.time.Duration.between(evaluatedAt, replyDue).getSeconds());
    return new ServiceTicketRow(
        r.get("id", UUID.class),
        r.get("ticket_no", String.class),
        type,
        label(ServiceTicketType.class, type),
        status,
        label(ServiceTicketStatus.class, status),
        priority,
        label(ServiceTicketPriority.class, priority),
        source,
        label(ServiceTicketSource.class, source),
        r.get("source_ref", String.class),
        r.get("subject", String.class),
        r.get("reason_note", String.class),
        r.get("assigned_to_ref", String.class),
        r.get("assigned_to_user_id", UUID.class),
        r.get("assigned_at", OffsetDateTime.class),
        r.get("case_id", UUID.class),
        r.get("case_no", String.class),
        r.get("case_status", String.class),
        r.get("case_type", String.class),
        r.get("platform_scope", String.class),
        r.get("case_reply_due_at", OffsetDateTime.class),
        r.get("case_resolve_due_at", OffsetDateTime.class),
        r.get("knowledge_entry_id", UUID.class),
        r.get("sla_policy_id", UUID.class),
        r.get("sla_reply_hours", Integer.class),
        r.get("sla_resolve_hours", Integer.class),
        slaBasis,
        slaBasisLabel,
        replyDue,
        r.get("resolve_due_at", OffsetDateTime.class),
        firstReply,
        replyState,
        label(ServiceTicketSlaState.class, replyState),
        resolveState,
        label(ServiceTicketSlaState.class, resolveState),
        secondsToReplyDue,
        r.get("waiting_buyer_at", OffsetDateTime.class),
        r.get("closed_at", OffsetDateTime.class),
        closeReason,
        isSet(closeReason) ? label(ServiceTicketCloseReason.class, closeReason) : "",
        r.get("close_note", String.class),
        r.get("escalated_case_id", UUID.class),
        r.get("escalated_case_no", String.class),
        r.get("escalated_dispute_id", UUID.class),
        r.get("escalated_dispute_no", String.class),
        r.get("satisfaction_rating", Integer.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class),
        r.get("version", Long.class));
  }

  private static ServiceTicketAssignmentRow mapAssignment(Record r) {
    String action = r.get("assign_action", String.class);
    String mode = r.get("assign_mode", String.class);
    return new ServiceTicketAssignmentRow(
        r.get("id", UUID.class),
        r.get("ticket_id", UUID.class),
        action,
        label(ServiceTicketAssignAction.class, action),
        mode,
        label(ServiceTicketAssignMode.class, mode),
        r.get("from_owner_ref", String.class),
        r.get("to_owner_ref", String.class),
        r.get("reason_note", String.class),
        r.get("created_by", UUID.class),
        r.get("created_at", OffsetDateTime.class));
  }

  private static ServiceTicketEventRow mapEvent(Record r) {
    String type = r.get("event_type", String.class);
    return new ServiceTicketEventRow(
        r.get("id", UUID.class),
        r.get("ticket_id", UUID.class),
        type,
        label(ServiceTicketEventType.class, type),
        r.get("from_status", String.class),
        r.get("to_status", String.class),
        r.get("actor_id", UUID.class),
        r.get("note", String.class),
        r.get("occurred_at", OffsetDateTime.class));
  }

  private static ServiceTicketSatisfactionRow mapSatisfaction(Record r) {
    return new ServiceTicketSatisfactionRow(
        r.get("id", UUID.class),
        r.get("ticket_id", UUID.class),
        r.get("score", Integer.class),
        r.get("comment", String.class),
        r.get("rated_by", UUID.class),
        r.get("rated_at", OffsetDateTime.class));
  }

  private static ServiceTicketSlaSnapshotRow mapSnapshot(Record r) {
    return new ServiceTicketSlaSnapshotRow(
        r.get("id", UUID.class),
        r.get("ticket_id", UUID.class),
        r.get("sla_policy_id", UUID.class),
        r.get("sla_reply_hours", Integer.class),
        r.get("sla_resolve_hours", Integer.class),
        r.get("reply_due_at", OffsetDateTime.class),
        r.get("resolve_due_at", OffsetDateTime.class),
        r.get("first_reply_at", OffsetDateTime.class),
        r.get("closed_at", OffsetDateTime.class),
        Boolean.TRUE.equals(r.get("reply_met", Boolean.class)),
        r.get("resolve_met", Boolean.class),
        r.get("snapshotted_at", OffsetDateTime.class));
  }

  /** 枚举取值 → 中文标签（取不到就原样返回，不吞掉未知值）。 */
  private static String label(Class<? extends Enum<?>> type, String raw) {
    if (raw == null) {
      return "";
    }
    for (Object constant : type.getEnumConstants()) {
      Enum<?> e = (Enum<?>) constant;
      if (e.name().equals(raw)) {
        return switch (e) {
          case ServiceTicketType v -> v.label();
          case ServiceTicketStatus v -> v.label();
          case ServiceTicketPriority v -> v.label();
          case ServiceTicketSource v -> v.label();
          case ServiceTicketCloseReason v -> v.label();
          case ServiceTicketAssignAction v -> v.label();
          case ServiceTicketAssignMode v -> v.label();
          case ServiceTicketSlaState v -> v.label();
          case ServiceTicketEventType v -> v.label();
          default -> raw;
        };
      }
    }
    return raw;
  }

  // ================================================================ 内部：值对象

  /** SLA 时限解析结果。 */
  private record SlaDue(
      OffsetDateTime replyDueAt,
      OffsetDateTime resolveDueAt,
      ServiceTicketSlaBasis basis,
      UUID policyId,
      Integer replyHours,
      Integer resolveHours) {}

  /** 售后单终态判定（复用 11.1.1.1 的词表，不另立一套）。 */
  private static final class ServiceTicketCaseStatus {
    private ServiceTicketCaseStatus() {}

    static boolean terminal(String status) {
      return "RESOLVED".equals(status) || "REJECTED".equals(status) || "CLOSED".equals(status);
    }
  }

  // ================================================================ 排序白名单

  private enum ServiceTicketSort {
    SLA_ASC("t.reply_due_at asc nulls last, t.created_at desc, t.id desc"),
    CREATED_DESC("t.created_at desc, t.id desc"),
    PRIORITY_DESC("case t.priority when 'URGENT' then 1 when 'HIGH' then 2"
        + " when 'NORMAL' then 3 else 4 end asc, t.reply_due_at asc nulls last, t.id desc"),
    ASSIGNED_ASC("t.assigned_to_ref asc, t.reply_due_at asc nulls last, t.id desc");

    private final String sql;

    ServiceTicketSort(String sql) {
      this.sql = sql;
    }

    String sql() {
      return sql;
    }

    static ServiceTicketSort parse(String raw) {
      if (raw == null) {
        return SLA_ASC;
      }
      return switch (raw) {
        case "sla_asc" -> SLA_ASC;
        case "created_desc" -> CREATED_DESC;
        case "priority_desc" -> PRIORITY_DESC;
        case "assigned_asc" -> ASSIGNED_ASC;
        default -> SLA_ASC;
      };
    }
  }
}
