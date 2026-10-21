package com.starcon.trade;

import static com.starcon.trade.PlatformDisputeModels.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 11.4.1.1 平台纠纷。
 *
 * <p><b>本类是 V265 四张新表的写入所有者</b>（platform_dispute /
 * platform_dispute_evidence_item / platform_dispute_settlement / platform_dispute_event），
 * 对**任何其它服务的表零依赖**（channel 的平台主数据、crm 的客服工单、finance 的结算
 * 都不在位 ⇒ 本模块一条跨服务 SQL 都不写）。
 *
 * <p><b>七条硬纪律</b>：
 *
 * <ol>
 *   <li><b>每一条查询都显式带 {@code tenant_id} 谓词</b>，不依赖 RLS 当唯一防线。
 *       纠纷单里同时有平台争议金额、我方立场、责任判定与举证清单 ——
 *       端点又大多是<b>按 id 直达</b>的 URL，漏掉租户谓词就是跨租户泄露。</li>
 *   <li><b>倒计时是派生态</b>：库里只有 {@code evidence_deadline} 一个时刻，
 *       剩余秒数与时限状态在<b>读取时刻</b>由 {@code evidence_deadline - now()} 算出。
 *       不存「剩余小时数」—— 那一列写下去就开始自己过期。</li>
 *   <li><b>超期自动判败由 DB 侧强制</b>（V265 守卫①），服务层**不自己判一遍**，
 *       只做**读时收口**：读列表 / 详情前先把已超期未提交的纠纷落成败诉，
 *       让「自动」这件事在界面上看得见（不依赖谁恰好写过一次别的东西）。
 *       服务层的<b>唯一</b>硬判定是「超期后不许提交举证」——
 *       那一条要在**写之前**就拦下来，而不是让 DB 抛一个 409。</li>
 *   <li><b>举证清单不齐不许提交举证</b>：必举证项一个都不能缺。
 *       「缺一项就白丢」这句话要在提交的那一刻就被挡住。</li>
 *   <li><b>「没有财务确认就没有生效」</b>：登记结算影响时生效金额恒为 0，
 *       只有财务确认那一刻才由 DB 把它改成登记金额；服务层<b>刻意没有</b>
 *       「直接置为已生效」的入口。</li>
 *   <li><b>时间绑定</b>：时间一律 {@code ?::timestamptz} + {@link OffsetDateTime}。
 *       {@code Timestamp.valueOf(LocalDateTime)} 会静默偏 −28800s，
 *       而举证截止时刻偏 8 小时会让「还剩多久」整体算错。</li>
 *   <li><b>状态流转只有一个入口</b>：每次改状态都过版本校验，都写留痕。</li>
 * </ol>
 */
@Service
public class PlatformDisputeService {

  /** 列表单页上限。 */
  private static final int MAX_PAGE_SIZE = 200;

  /** 金额刻度：4 位（与 sales_order_line.unit_price、质检残值同刻度）。 */
  private static final int MONEY_SCALE = 4;

  /** 平台码形状（照抄 catalog V145 的 CHECK，跨服务不引表但形状统一）。 */
  private static final String PLATFORM_CODE_PATTERN = "^[A-Z0-9][A-Z0-9_-]{0,31}$";

  private final DSLContext sql;

  public PlatformDisputeService(DSLContext sql) {
    this.sql = sql;
  }

  // ================================================================ 能力声明

  /**
   * 能力声明：职责句的六个要素逐块报可用性，上下游逐个报 delivered=false 与归属模块号、
   * 本模块<b>刻意不做</b>的事、状态机与三条硬规则的口径。
   *
   * <p>让界面能说清「为什么这件纠纷已经判负了」「为什么平台同步还没接上」，
   * 而不是让它们显示成空白让人以为「这些能力没做」。
   */
  public PlatformDisputeCapabilities capabilities() {
    return new PlatformDisputeCapabilities(
        List.of(
            "平台来源快照（平台码 / 平台名 / 平台纠纷号 / 平台订单号）—— 平台主数据在 channel 独立库，"
                + "跨库读不到，这里只落**登记那一刻的快照**",
            "纠纷单 platform_dispute（V265）—— 类型 / 金额 / 平台时限 / 我方立场 / 责任判定 / 结果记录",
            "举证清单 platform_dispute_evidence_item（V265）—— 应举证材料项 + 是否已具备 + 缺失项",
            "结算影响分录 platform_dispute_settlement（V265，**trade 侧自有事实记录**，生效金额由 DB 钉死）",
            "纠纷留痕 platform_dispute_event（V265，追加式，运行角色 UPDATE/DELETE 已收回）"),
        List.of(
            section("disputeType", "类型", true, null,
                "蓝图点名的三种纠纷各自绑定一个平台：A_TO_Z⇒AMAZON / EBAY_DISPUTE⇒EBAY / "
                    + "ALIEXPRESS_DISPUTE⇒ALIEXPRESS，OTHER 不绑定（V265 守卫⑧）。"
                    + "「type='A_TO_Z' 但 platform_code='EBAY'」的行在库里存不进去。",
                "platform_dispute + V265 守卫⑧"),
            section("disputeAmount", "金额", true, null,
                "NUMERIC(19,4) + BigDecimal，**禁浮点**；一单一币种，跨币种**按币种分组**展示，"
                    + "**不做固定汇率折算**（折算出来的合计是假数）。",
                "platform_dispute.dispute_amount / dispute_currency"),
            section("platformDeadline", "平台时限", true, null,
                "举证截止时刻落成**真实的 timestamptz 列**，不是文本、不是「7 天」这种相对描述。"
                    + "倒计时是**派生态**：库里没有「剩余小时数」列，剩余秒数在读取时刻由截止时刻算出。",
                "platform_dispute.evidence_deadline"),
            section("evidenceRequirement", "举证要求", true, null,
                "举证清单一行 = 一项应举证材料（类型 + 是否必举 + 是否已具备 + 缺失原因）。"
                    + "**必举证项一个都不能缺才能提交举证**（V265 守卫⑥）。"
                    + "⚠️ 11.4.1.2 证据与申诉**未开发** ⇒ 这里只落**清单事实**，"
                    + "不上传材料、不提交申诉。",
                "platform_dispute_evidence_item"),
            section("ourPosition", "我方立场", true, null,
                "承认 / 否认 / 部分承认 / 协商处理 / 立场待定；与责任判定一起记录，"
                    + "立场变更走独立端点并留痕。",
                "platform_dispute.our_position / liability_stage"),
            section("resolution", "结果记录", true, null,
                "平台裁决我方胜诉 / 败诉、双方达成一致、我方撤诉，"
                    + "以及**超期自动判负**（系统做的，不挂操作员）。",
                "platform_dispute.resolution_code + V265 守卫⑥"),
            section("deadlineAutoLoss", "超期自动判败", true, null,
                "**硬性时效要求**，落成结构而不是文案：① 截止时刻是真实 timestamptz；"
                    + "② DB 守卫① 在**任何一次写入**上强制「超期且未提交举证 ⇒ RESOLVED_LOST」；"
                    + "③ 举证提交时刻晚于截止时刻直接 RAISE（超期后不可再提交举证）；"
                    + "④ 举证清单在超期后不可再改；⑤ 举证时限**只能收紧不能放宽**（守卫③）。",
                "V265 守卫① / ② / ③"),
            section("platformSync", "与平台同步的自动衔接", false, "2 平台同步",
                "**上游缺口**：channel 服务已交付的是平台目录 / 连接 / 店铺 / 同步任务 / 同步运行 / "
                    + "回写 / webhook / 增量补拉，**没有纠纷专用同步端点**；"
                    + "crm 服务的客服工单 / 客户模块（15.1.x / 15.3.x / 15.4.1.x）**全部未开发**。"
                    + "⇒ 本模块**不假装**有个平台推送接口：纠纷由本模块自己的建单入口登记，"
                    + "平台来源信息以**快照**落库。channel 落地纠纷同步后，"
                    + "经**授权 API / event** 调本模块的建单入口即可衔接。",
                null),
            section("appeal", "证据与申诉提交（下游 11.4.1.2）", false, "11.4.1.2",
                "**未交付**。本模块**不建申诉提交表、不调平台提交接口** —— "
                    + "举证清单是**清单事实**，不是申诉动作。",
                null),
            section("financeImpact", "12 财务（争议金额影响结算）", false, "12 财务",
                "**finance 服务当前不存在**（backend/services/ 下只有 identity / catalog / "
                    + "channel / warehouse / trade / crm）。"
                    + "⇒ 争议金额影响结算当前以 **trade 侧自有事实记录**落地"
                    + "（platform_dispute_settlement），与 12 财务的最终对账"
                    + "留待 finance 落地后经**授权 API / event** 衔接。",
                null)),
        List.of(
            related("MODULE_2_CHANNEL_SYNC", "2 平台同步", false, "2 平台同步 / 10 接口",
                "**已交付部分不含纠纷同步**（平台目录 / 连接 / 店铺 / 同步任务 / 同步运行 / 回写 / "
                    + "webhook / 增量补拉）。⇒ 本模块提供**自己的建单入口**，不调不存在的端点。",
                null),
            related("MODULE_15_CRM_TICKET", "15 客服（工单 / 客户）", false, "15 客服",
                "crm 服务已交付的是索评（15.2.2.1 / 15.2.2.2）与知识库（15.4.2.1）；"
                    + "客服工单 / 客户模块（15.1.x / 15.3.x / 15.4.1.x）**全部未开发**。",
                null),
            related("MODULE_11_4_1_2", "11.4.1.2 证据与申诉", false, "11.4.1.2",
                "**下游缺口**：本模块的举证清单是它的事实输入，申诉提交本身**不在本模块**。",
                null),
            related("MODULE_12_FINANCE", "12 财务（争议金额影响结算）", false, "12 财务",
                "**未交付且服务不存在** ⇒ 本模块只落 trade 侧自有事实记录，"
                    + "跨服务写账被 AGENTS.md 模块边界纪律明令禁止。",
                null),
            related("MODULE_10_INTERFACE", "10 接口（平台目录 / 店铺）", false, "10 接口 / 2 平台同步",
                "平台主数据在 channel 服务的**独立数据库**里，跨库读不到 ⇒ "
                    + "本模块引用**平台码 + 平台名快照**两列。",
                null)),
        List.of(
            action("CREATE_DISPUTE", "登记平台纠纷", true, "POST", "/trade/platform-disputes", false,
                "平台码 / 平台名 / 平台纠纷号必填；类型与平台必须对得上", null, null, null),
            action("ADD_EVIDENCE_ITEM", "加举证材料项", true, "POST",
                "/trade/platform-disputes/{id}/evidence-items", false,
                "仅已立案 / 举证准备中，且未超期", null, null, null),
            action("START_EVIDENCE", "进入举证准备", true, "POST",
                "/trade/platform-disputes/{id}/start-evidence", false,
                "仅已立案，且**已有举证清单**", null, null, null),
            action("MARK_EVIDENCE", "更新举证材料状态", true, "PUT",
                "/trade/platform-disputes/{id}/evidence-items/{itemId}", false,
                "仅已立案 / 举证准备中，且未超期", null, null, null),
            action("UPDATE_POSITION", "更新我方立场与责任判定", true, "POST",
                "/trade/platform-disputes/{id}/position", false,
                "仅已立案 / 举证准备中 / 已提交举证", null, null, null),
            action("SUBMIT_EVIDENCE", "提交举证", true, "POST",
                "/trade/platform-disputes/{id}/submit-evidence", false,
                "仅举证准备中；**必举证项全部具备**且**未超期**", null, null, null),
            action("RESOLVE", "记录平台裁决结果", true, "POST",
                "/trade/platform-disputes/{id}/resolve", true,
                "仅已提交举证；结果码不接受「超期判负」", null, null, null),
            action("WITHDRAW", "我方撤诉", true, "POST", "/trade/platform-disputes/{id}/withdraw", true,
                "仅已立案 / 举证准备中 / 已提交举证；必须写理由", null, null, null),
            action("REGISTER_SETTLEMENT", "登记结算影响", true, "POST",
                "/trade/platform-disputes/{id}/settlements", false,
                "仅已终结（胜诉 / 败诉）；登记后**等待财务确认才生效**", null, null, null),
            action("CONFIRM_SETTLEMENT", "财务确认结算生效", true, "POST",
                "/trade/platform-disputes/{id}/settlements/{settlementId}/confirm", true,
                "仅待财务确认；需回填财务单据号", null, null, null),
            action("REJECT_SETTLEMENT", "财务驳回结算影响", true, "POST",
                "/trade/platform-disputes/{id}/settlements/{settlementId}/reject", true,
                "仅待财务确认；必须写理由", null, null, null),
            action("AUTO_LOSE", "手动标记超期判负", false, null, null, false, null,
                "超期自动判负是**系统做的**（DB 守卫①），**刻意不提供**手动入口 —— "
                    + "一个能提前把自己判负的按钮只会让人用它来提前结案。", null,
                "系统（V265 守卫①）")),
        List.of(
            boundary("不提供「手动标记超期判负」的端点", "系统（V265 守卫①）",
                "超期判负是**推导**出来的：过了截止时刻且未提交举证，就是败诉。"
                    + "手动入口等于让「提前结案」变成一个按钮。"),
            boundary("传输层没有生效金额字段、没有剩余时长字段", "12 财务 / 系统",
                "生效金额由财务确认那一刻的 DB 触发器写；剩余时长是读取时刻的派生态。"
                    + "可填的字段等于把两套口径交给每个调用方自己编。"),
            boundary("不写任何财务分录、不建跨库 FK / 视图、不调不存在的平台端点", "12 财务 / 2 平台同步 / 15 客服",
                "跨服务禁止 SQL；finance / crm 客服 / channel 纠纷同步三处都不在位，"
                    + "本模块只落自己的事实记录与快照。"),
            boundary("举证清单只记录「该交什么 / 齐不齐 / 缺什么」，不上传材料、不提交申诉",
                "11.4.1.2",
                "证据与申诉是下游模块的职责，越界建表等于把两个模块的边界糊在一起。"),
            boundary("举证时限只能收紧不能放宽", "系统（V265 守卫③）",
                "平台的截止时刻是既定事实；能放宽就等于给自己续命。"),
            boundary("终态不可逆：胜诉 / 败诉 / 撤诉后业务要素整行冻结", "11.4.1.2",
                "纠纷是要拿去跟平台对质的凭据，"
                    + "「我们当时是什么立场、什么时候交的证据、结果是什么」必须只有一个答案。"
                    + "（结算口径不在冻结集内 —— 判负之后钱还要结。）")),
        "OPEN → EVIDENCE_PREPARING → EVIDENCE_SUBMITTED → RESOLVED_WON（终态）"
            + " / RESOLVED_LOST（终态）/ WITHDRAWN（终态）。"
            + "⚠️ **超期自动判败**：OPEN / EVIDENCE_PREPARING 且未提交举证时，"
            + "过了举证截止时刻即被 DB 守卫① 强制落成 RESOLVED_LOST。",
        "**「平台纠纷有严格举证时限，超期自动判败」落成结构而不是文案**："
            + "① 举证截止时刻是真实的 timestamptz 列（evidence_deadline），"
            + "不是文本、不是「7 天」这种相对描述；"
            + "② 倒计时是**派生态** —— 库里**没有**「剩余小时数」列，"
            + "剩余秒数在读取时刻由 evidence_deadline - now() 算出；"
            + "③ **DB 守卫① 挂在 BEFORE INSERT OR UPDATE 上**：只要超期且未提交举证，"
            + "任何一次写入都把结论改写成 RESOLVED_LOST + DEADLINE_EXPIRED —— "
            + "绕过服务层直连 SQL 也一样；"
            + "④ 举证提交时刻晚于截止时刻 ⇒ 直接 RAISE（**超期后不可再提交举证**）；"
            + "⑤ 举证清单在超期后不可再改（守卫②）；"
            + "⑥ 举证时限**只能收紧不能放宽**（守卫③）。",
        "「**没有财务确认就没有生效**」落成结构而不是文案："
            + "① 结算分录的 requested_amount（登记）与 effective_amount（已生效）是两列，"
            + "登记时 effective 恒为 0；"
            + "② 表级 CHECK：未 CONFIRMED ⇒ effective = 0，CONFIRMED ⇒ effective = requested "
            + "且确认人 / 确认时刻 / 财务单据号齐全；"
            + "③ 纠纷单头上的 effective_amount 与 settlement_status 由 DB 守卫④ **从分录重算**，"
            + "直连 SQL 也写不进一个假的「已生效」；"
            + "④ 已确认的分录**整行冻结**。"
            + "⚠️ 下游 12 财务（finance 服务）**当前不存在** ⇒ 结算影响当前以 trade 侧自有事实记录落地。",
        "举证清单是**本模块产出的清单事实**，不是材料本体、也不是申诉动作："
            + "一行 = 一项应举证材料（类型 + 是否必举 + 是否已具备 + 缺失原因）；"
            + "**必举证项一个都不能缺才能提交举证**；"
            + "确认缺失必须写清缺什么（表级 CHECK 钉死）。",
        "⚠️ **上游缺口（如实报出，不假装已交付）**：channel 服务**没有纠纷专用同步端点**"
            + "（已交付的是平台目录 / 连接 / 店铺 / 同步任务 / 同步运行 / 回写 / webhook / 增量补拉），"
            + "crm 服务的客服工单 / 客户模块**全部未开发**。"
            + "⇒ 本模块**不调用不存在的端点、不 mock 一个「Amazon 拉到的纠纷」**："
            + "纠纷由本模块自己的建单入口（授权 API）登记，平台来源信息以**快照**落库；"
            + "channel 落地纠纷同步后经**授权 API / event** 调本模块的建单入口即可衔接。");
  }

  // ================================================================ 列表

  /** 纠纷单列表：真分页 + 多维筛选 + 排序白名单 + 跨全表汇总（金额按币种分组）。 */
  public PlatformDisputePage listDisputes(PlatformDisputeListQuery query) {
    int p = query.page() == null || query.page() < 0 ? 0 : query.page();
    int s = query.size() == null || query.size() <= 0 ? 20 : Math.min(query.size(), MAX_PAGE_SIZE);
    PlatformDisputeSort sort = PlatformDisputeSort.parse(query.sort());

    // ⚠️ 读之前先把**已超期未提交**的纠纷落成败诉：
    //   「自动判负」不能依赖「谁恰好写过一次别的东西」才会显形。
    //   这条 UPDATE 走的是与守卫① 完全相同的判据，幂等。
    expireOverdueDisputes();

    List<Object> binds = new ArrayList<>();
    List<String> where = conditions(query, binds);
    String clause = " where " + String.join(" and ", where);

    long total = sql.fetchOne("select count(*)" + from() + clause, binds.toArray()).get(0, Long.class);

    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(s);
    pageBinds.add((long) p * s);
    List<PlatformDisputeRow> rows =
        sql.fetch(
                selectColumns() + from() + clause + " order by " + sort.sql() + " limit ? offset ?",
                pageBinds.toArray())
            .map(PlatformDisputeService::mapDisputeRow);

    return new PlatformDisputePage(
        rows,
        p,
        s,
        total,
        summary(clause, binds),
        currencyBreakdown(clause, binds),
        OffsetDateTime.now(ZoneOffset.UTC),
        "真分页（LIMIT/OFFSET + 独立 COUNT），汇总与排序不受分页影响。",
        "⚠️ **倒计时是派生态**：剩余秒数由举证截止时刻在**本次读取时刻**算出，"
            + "库里没有「剩余小时数」列（那一列写下去就开始自己过期）。"
            + "已超期且未提交举证的纠纷**已自动判负**（DB 守卫① + 读时收口）。",
        "⚠️ **争议金额影响结算在财务确认之前不生效**：汇总里的 effectiveAmount 只累加"
            + "已被财务确认（settlement_status='CONFIRMED'）的生效金额，"
            + "待确认的仍挂在 awaitingFinanceConfirm 上。",
        "金额**按币种分组**统计，不做固定汇率折算 —— 跨币种加出来的是一个没有意义的数字。",
        "排序白名单四个键（deadline_asc / created_desc / amount_desc / platform_asc）；"
            + "其余取值落回 deadline_asc（最紧急的排最前），不接受任意表达式。");
  }

  /** 列表的 from 子句：纠纷单 + 举证清单计数的横向聚合。 */
  private static String from() {
    return " from platform_dispute d"
        + " left join lateral ("
        + "   select count(*) as ev_total,"
        + "          count(*) filter (where evidence_state = 'PROVIDED') as ev_provided,"
        + "          count(*) filter (where evidence_state = 'MISSING') as ev_missing"
        + "     from platform_dispute_evidence_item e"
        + "    where e.tenant_id = d.tenant_id and e.dispute_id = d.id) ev on true";
  }

  /** 列表的 select 列（倒计时与时限状态在 SQL 里就地派生）。 */
  private static String selectColumns() {
    return "select d.*,"
        + " coalesce(ev.ev_total, 0) as ev_total,"
        + " coalesce(ev.ev_provided, 0) as ev_provided,"
        + " coalesce(ev.ev_missing, 0) as ev_missing,"
        + " extract(epoch from (d.evidence_deadline - now()))::bigint as seconds_remaining,"
        + " case"
        + "   when d.evidence_submitted_at is not null then 'RELEASED'"
        + "   when d.resolution_code = 'DEADLINE_EXPIRED' then 'PASSED'"
        + "   when d.dispute_status in ('RESOLVED_WON','RESOLVED_LOST','WITHDRAWN') then 'RELEASED'"
        + "   when d.evidence_deadline <= now() then 'PASSED'"
        + "   when d.evidence_deadline <= now() + interval '72 hours' then 'URGENT'"
        + "   else 'NORMAL' end as deadline_state";
  }

  /** 列表的 where 条件与绑定（筛选维度：状态 / 类型 / 平台 / 币种 / 立场 / 责任 / 结算 / 超期 / 金额 / 时间 / 关键字）。 */
  private List<String> conditions(PlatformDisputeListQuery query, List<Object> binds) {
    List<String> where = new ArrayList<>();
    where.add("d.tenant_id = ?::uuid");
    binds.add(currentTenant());

    if (isSet(query.status())) {
      where.add("d.dispute_status = ?");
      binds.add(parseEnum(PlatformDisputeStatus.class, query.status(), "纠纷状态").name());
    }
    if (isSet(query.disputeType())) {
      where.add("d.dispute_type = ?");
      binds.add(parseEnum(PlatformDisputeType.class, query.disputeType(), "纠纷类型").name());
    }
    if (isSet(query.platformCode())) {
      where.add("d.platform_code = ?");
      binds.add(requirePlatformCode(query.platformCode()));
    }
    if (isSet(query.currency())) {
      where.add("d.dispute_currency = ?");
      binds.add(requireCurrency(query.currency()));
    }
    if (isSet(query.ourPosition())) {
      where.add("d.our_position = ?");
      binds.add(parseEnum(PlatformDisputePosition.class, query.ourPosition(), "我方立场").name());
    }
    if (isSet(query.liabilityStage())) {
      where.add("d.liability_stage = ?");
      binds.add(parseEnum(PlatformLiabilityStage.class, query.liabilityStage(), "责任判定").name());
    }
    if (isSet(query.settlementStatus())) {
      where.add("d.settlement_status = ?");
      binds.add(parseEnum(PlatformSettlementState.class, query.settlementStatus(), "结算状态").name());
    }
    if (Boolean.TRUE.equals(query.overdueOnly())) {
      // 「超期」= 过了举证截止时刻**且**还没提交举证。
      where.add("d.evidence_submitted_at is null and d.evidence_deadline <= now()");
    }
    if (query.minAmount() != null) {
      where.add("d.dispute_amount >= ?::numeric");
      binds.add(money(query.minAmount(), "金额下限"));
    }
    if (query.maxAmount() != null) {
      where.add("d.dispute_amount <= ?::numeric");
      binds.add(money(query.maxAmount(), "金额上限"));
    }
    if (query.from() != null) {
      where.add("d.created_at >= ?::timestamptz");
      binds.add(query.from());
    }
    if (query.to() != null) {
      where.add("d.created_at <= ?::timestamptz");
      binds.add(query.to());
    }
    if (isSet(query.keyword())) {
      where.add("(d.dispute_no ilike ? or d.platform_dispute_no ilike ? or d.platform_order_no ilike ?"
          + " or d.platform_name ilike ? or d.dispute_reason ilike ?)");
      String like = "%" + query.keyword().trim() + "%";
      binds.add(like);
      binds.add(like);
      binds.add(like);
      binds.add(like);
      binds.add(like);
    }
    return where;
  }

  /** 页汇总：跨全表算，不随分页变小。⚠️ 这里**没有金额合计**（跨币种加总没有意义）。 */
  private PlatformDisputeSummary summary(String clause, List<Object> binds) {
    Record r =
        sql.fetchOne(
            "select count(*) as total,"
                + " count(*) filter (where d.dispute_status = 'OPEN') as open_cnt,"
                + " count(*) filter (where d.dispute_status = 'EVIDENCE_PREPARING') as preparing,"
                + " count(*) filter (where d.dispute_status = 'EVIDENCE_SUBMITTED') as submitted,"
                + " count(*) filter (where d.dispute_status = 'RESOLVED_WON') as won,"
                + " count(*) filter (where d.dispute_status = 'RESOLVED_LOST') as lost,"
                + " count(*) filter (where d.dispute_status = 'WITHDRAWN') as withdrawn,"
                + " count(*) filter (where d.evidence_submitted_at is null"
                + "   and d.evidence_deadline <= now()) as overdue,"
                + " count(*) filter (where d.settlement_status = 'PENDING_FINANCE_CONFIRM') as pending_finance"
                + " from platform_dispute d" + clause,
            binds.toArray());
    return new PlatformDisputeSummary(
        r.get("total", Long.class),
        r.get("open_cnt", Long.class),
        r.get("preparing", Long.class),
        r.get("submitted", Long.class),
        r.get("won", Long.class),
        r.get("lost", Long.class),
        r.get("withdrawn", Long.class),
        r.get("overdue", Long.class),
        r.get("pending_finance", Long.class));
  }

  /** 金额口径：**按币种分组**，不做固定汇率折算。 */
  private List<PlatformDisputeCurrencyTotal> currencyBreakdown(String clause, List<Object> binds) {
    return sql.fetch(
            "select d.dispute_currency as currency,"
                + " count(*) as dispute_count,"
                + " coalesce(sum(d.dispute_amount), 0) as total_amount,"
                + " coalesce(sum(d.effective_amount), 0) as effective_amount"
                + " from platform_dispute d" + clause
                + " group by d.dispute_currency order by d.dispute_currency",
            binds.toArray())
        .map(
            r ->
                new PlatformDisputeCurrencyTotal(
                    r.get("currency", String.class),
                    r.get("dispute_count", Long.class),
                    r.get("total_amount", BigDecimal.class),
                    r.get("effective_amount", BigDecimal.class)));
  }

  // ================================================================ 详情

  /** 纠纷单全景：头 + 举证清单 + 结算分录 + 留痕 + 当前可执行动作。 */
  public PlatformDisputeDetail disputeDetail(UUID id) {
    // 与列表同一个「读时收口」：已超期未提交 ⇒ 当场判负。
    expireOverdueDisputes();
    Record head = requireDispute(id);
    return buildDetail(head);
  }

  private PlatformDisputeDetail buildDetail(Record head) {
    UUID disputeId = head.get("id", UUID.class);

    List<PlatformDisputeEvidenceRow> evidenceItems =
        sql.fetch(
                "select * from platform_dispute_evidence_item"
                    + " where tenant_id = ?::uuid and dispute_id = ?::uuid order by evidence_seq",
                currentTenant(),
                disputeId)
            .map(
                r -> {
                  String type = r.get("evidence_type", String.class);
                  String state = r.get("evidence_state", String.class);
                  return new PlatformDisputeEvidenceRow(
                      r.get("id", UUID.class),
                      disputeId,
                      r.get("evidence_seq", Integer.class),
                      type,
                      PlatformEvidenceType.valueOf(type).label(),
                      r.get("evidence_label", String.class),
                      Boolean.TRUE.equals(r.get("required", Boolean.class)),
                      state,
                      PlatformEvidenceState.valueOf(state).label(),
                      r.get("evidence_note", String.class),
                      r.get("missing_reason", String.class),
                      r.get("provided_at", OffsetDateTime.class),
                      r.get("created_at", OffsetDateTime.class));
                });

    List<PlatformDisputeSettlementRow> settlements =
        sql.fetch(
                "select * from platform_dispute_settlement"
                    + " where tenant_id = ?::uuid and dispute_id = ?::uuid order by created_at, id",
                currentTenant(),
                disputeId)
            .map(
                r -> {
                  String status = r.get("settlement_status", String.class);
                  return new PlatformDisputeSettlementRow(
                      r.get("id", UUID.class),
                      r.get("settlement_currency", String.class),
                      r.get("requested_amount", BigDecimal.class),
                      r.get("effective_amount", BigDecimal.class),
                      status,
                      PlatformSettlementStatus.valueOf(status).label(),
                      r.get("finance_reference", String.class),
                      r.get("confirmed_by", UUID.class),
                      r.get("confirmed_at", OffsetDateTime.class),
                      r.get("reject_reason", String.class),
                      r.get("created_at", OffsetDateTime.class),
                      r.get("version", Long.class));
                });

    List<PlatformDisputeEventRow> events =
        sql.fetch(
                "select * from platform_dispute_event where tenant_id = ?::uuid and dispute_id = ?::uuid"
                    + " order by occurred_at, id",
                currentTenant(),
                disputeId)
            .map(
                r ->
                    new PlatformDisputeEventRow(
                        r.get("id", UUID.class),
                        r.get("event_type", String.class),
                        eventLabel(r.get("event_type", String.class)),
                        r.get("from_status", String.class),
                        r.get("to_status", String.class),
                        r.get("amount", BigDecimal.class),
                        r.get("reason_note", String.class),
                        r.get("occurred_at", OffsetDateTime.class)));

    PlatformDisputeRow row = mapDisputeRow(head);
    return new PlatformDisputeDetail(
        row,
        evidenceItems,
        settlements,
        events,
        actionsOf(row, evidenceItems),
        OffsetDateTime.now(ZoneOffset.UTC),
        "纠纷单里唯一的时刻事实是 **evidenceDeadline**（举证截止时刻）："
            + "倒计时由它在读取时刻算出，库里没有会自己过期的「剩余小时数」。"
            + "结算影响分录的 requestedAmount（登记）与 effectiveAmount（已生效）在财务确认之前必然不等。");
  }

  // ================================================================ 写

  /** 登记平台纠纷（建单）。 */
  public PlatformDisputeDetail createDispute(PlatformDisputeCreate request, UUID userId) {
    String platformCode = requirePlatformCode(request.platformCode());
    String platformName = requireText(request.platformName(), "平台名称");
    String platformDisputeNo = requireText(request.platformDisputeNo(), "平台纠纷单号");
    PlatformDisputeType type =
        parseEnum(PlatformDisputeType.class, request.disputeType(), "纠纷类型");
    String required = type.requiredPlatformCode();
    if (required != null && !required.equals(platformCode)) {
      throw badRequest(
          "纠纷类型「" + type.label() + "」只属于平台 " + required + "，与平台码「" + platformCode + "」不一致");
    }
    String currency = requireCurrency(request.disputeCurrency());
    if (request.disputeAmount() == null) {
      throw badRequest("必须给出争议金额");
    }
    BigDecimal amount = money(request.disputeAmount(), "争议金额");
    if (amount.signum() <= 0) {
      throw badRequest("争议金额必须大于 0：" + request.disputeAmount());
    }
    if (request.evidenceDeadline() == null) {
      throw badRequest("必须给出举证截止时刻（平台时限是真实时刻，不是「7 天」这种相对描述）");
    }
    PlatformDisputePosition position =
        isSet(request.ourPosition())
            ? parseEnum(PlatformDisputePosition.class, request.ourPosition(), "我方立场")
            : PlatformDisputePosition.UNDETERMINED;
    PlatformLiabilityStage stage =
        isSet(request.liabilityStage())
            ? parseEnum(PlatformLiabilityStage.class, request.liabilityStage(), "责任判定")
            : PlatformLiabilityStage.UNDETERMINED;

    // 同一平台纠纷号只能登记一次（DB 上还有唯一索引兜底）。
    long exists =
        sql.fetchOne(
                "select count(*) from platform_dispute"
                    + " where tenant_id = ?::uuid and platform_code = ? and platform_dispute_no = ?",
                currentTenant(),
                platformCode,
                platformDisputeNo)
            .get(0, Long.class);
    if (exists > 0) {
      throw badRequest(
          "该平台纠纷单号已经登记过了（同一个平台纠纷只能登记一次，"
              + "否则两条记录各自倒计时、各自判负，事后没人说得清哪条是真的）："
              + platformCode + " / " + platformDisputeNo);
    }

    long next = sql.fetchOne("select nextval('platform_dispute_no_seq')").get(0, Long.class);
    UUID id = UUID.randomUUID();
    sql.execute(
        "insert into platform_dispute(id,tenant_id,dispute_no,platform_code,platform_name,"
            + "platform_dispute_no,platform_order_no,dispute_type,dispute_reason,"
            + "dispute_currency,dispute_amount,evidence_deadline,our_position,position_note,"
            + "liability_stage,liability_note,dispute_status,created_by)"
            + " values(?::uuid,?::uuid,?,?,?,?,?,?,?,?::char(3),?::numeric,?::timestamptz,?,?,?,?,'OPEN',?::uuid)",
        id,
        currentTenant(),
        "PDS" + next,
        platformCode,
        platformName,
        platformDisputeNo,
        trimOrEmpty(request.platformOrderNo()),
        type.name(),
        trimOrEmpty(request.disputeReason()),
        currency,
        amount,
        request.evidenceDeadline(),
        position.name(),
        trimOrEmpty(request.positionNote()),
        stage.name(),
        trimOrEmpty(request.liabilityNote()),
        userId);
    return disputeDetail(id);
  }

  /** 加一项举证材料。 */
  public PlatformDisputeDetail addEvidenceItem(
      UUID disputeId, PlatformEvidenceItemCreate request, UUID userId) {
    Record head = requireDispute(disputeId);
    requireEvidenceWindow(head, "加举证材料项");
    PlatformEvidenceType type =
        parseEnum(PlatformEvidenceType.class, request.evidenceType(), "举证材料类型");
    boolean required = request.required() == null || request.required();

    sql.execute(
        "insert into platform_dispute_evidence_item(id,tenant_id,dispute_id,evidence_type,"
            + "evidence_label,required,evidence_state,evidence_note,created_by)"
            + " values(?::uuid,?::uuid,?::uuid,?,?,?,'PENDING',?,?::uuid)",
        UUID.randomUUID(),
        currentTenant(),
        disputeId,
        type.name(),
        trimOrEmpty(request.evidenceLabel()),
        required,
        trimOrEmpty(request.evidenceNote()),
        userId);
    return disputeDetail(disputeId);
  }

  /** 更新一项举证材料的状态（PENDING / PROVIDED / MISSING）。 */
  public PlatformDisputeDetail markEvidence(
      UUID disputeId, UUID itemId, PlatformEvidenceMark request, UUID userId) {
    Record head = requireDispute(disputeId);
    requireEvidenceWindow(head, "更新举证材料状态");
    PlatformEvidenceState state =
        parseEnum(PlatformEvidenceState.class, request.evidenceState(), "举证材料状态");
    String missing = trimOrEmpty(request.missingReason());
    if (state == PlatformEvidenceState.MISSING && missing.isEmpty()) {
      throw badRequest(
          "确认缺失必须写清缺什么：只标「缺」而不说缺哪一样，提交前没人知道要补什么");
    }
    if (state != PlatformEvidenceState.MISSING) {
      missing = "";
    }
    if (state == PlatformEvidenceState.PROVIDED && !isSet(request.evidenceNote())) {
      throw badRequest("标记「已具备」必须写说明：这份材料是什么、从哪来的，事后要答得出来");
    }

    int changed =
        sql.execute(
            "update platform_dispute_evidence_item set evidence_state = ?, evidence_note = ?,"
                + " missing_reason = ?, provided_by = ?::uuid, updated_at = now()"
                + " where tenant_id = ?::uuid and id = ?::uuid and dispute_id = ?::uuid",
            state.name(),
            trimOrEmpty(request.evidenceNote()),
            missing,
            state == PlatformEvidenceState.PROVIDED ? userId : null,
            currentTenant(),
            itemId,
            disputeId);
    if (changed == 0) {
      throw badRequest("举证材料项不存在或不属于当前租户：" + itemId);
    }
    return disputeDetail(disputeId);
  }

  /** 移除一项举证材料。 */
  public PlatformDisputeDetail removeEvidenceItem(UUID disputeId, UUID itemId, UUID userId) {
    Record head = requireDispute(disputeId);
    requireEvidenceWindow(head, "移除举证材料项");
    int deleted =
        sql.execute(
            "delete from platform_dispute_evidence_item"
                + " where tenant_id = ?::uuid and id = ?::uuid and dispute_id = ?::uuid",
            currentTenant(),
            itemId,
            disputeId);
    if (deleted == 0) {
      throw badRequest("举证材料项不存在或不属于当前租户：" + itemId);
    }
    return disputeDetail(disputeId);
  }

  /**
   * 进入举证准备（已立案 → 举证准备中）。
   *
   * <p>⚠️ **必须已有举证清单**：举证要求是平台给的，先把该交的列出来，
   * 才知道「缺哪一样」（V265 守卫⑥ ①）。
   */
  public PlatformDisputeDetail startEvidence(UUID disputeId, Long expectedVersion, UUID userId) {
    Record head = requireDispute(disputeId);
    requireVersion(head.get("version", Long.class), expectedVersion, "纠纷单");
    requireStatus(head, java.util.Set.of(PlatformDisputeStatus.OPEN), "进入举证准备");

    long items =
        sql.fetchOne(
                "select count(*) from platform_dispute_evidence_item"
                    + " where tenant_id = ?::uuid and dispute_id = ?::uuid",
                currentTenant(),
                disputeId)
            .get(0, Long.class);
    if (items == 0) {
      throw badRequest(
          "还没有举证清单，不能进入举证准备（举证要求是平台给的，先把该交的列出来）：" + disputeId);
    }

    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    requireChanged(
        sql.execute(
            "update platform_dispute set dispute_status = 'EVIDENCE_PREPARING',"
                + " updated_at = ?::timestamptz, updated_by = ?::uuid, version = version + 1"
                + " where tenant_id = ?::uuid and id = ?::uuid and dispute_status = 'OPEN'",
            now,
            userId,
            currentTenant(),
            disputeId),
        "进入举证准备");
    return disputeDetail(disputeId);
  }

  /** 更新我方立场 + 责任判定。 */
  public PlatformDisputeDetail updatePosition(
      UUID disputeId, PlatformDisputePositionUpdate request, UUID userId) {
    Record head = requireDispute(disputeId);
    requireVersion(head.get("version", Long.class), request.expectedVersion(), "纠纷单");
    PlatformDisputeStatus status = PlatformDisputeStatus.valueOf(head.get("dispute_status", String.class));
    if (!status.evidenceOpen() && status != PlatformDisputeStatus.EVIDENCE_SUBMITTED) {
      throw badRequest("纠纷单已是终态（" + status.label() + "），立场与责任判定不可再改");
    }
    PlatformDisputePosition position =
        parseEnum(PlatformDisputePosition.class, request.ourPosition(), "我方立场");
    PlatformLiabilityStage stage =
        parseEnum(PlatformLiabilityStage.class, request.liabilityStage(), "责任判定");

    requireChanged(
        sql.execute(
            "update platform_dispute set our_position = ?, position_note = ?,"
                + " liability_stage = ?, liability_note = ?, updated_at = ?::timestamptz,"
                + " updated_by = ?::uuid, version = version + 1"
                + " where tenant_id = ?::uuid and id = ?::uuid",
            position.name(),
            trimOrEmpty(request.positionNote()),
            stage.name(),
            trimOrEmpty(request.liabilityNote()),
            OffsetDateTime.now(ZoneOffset.UTC),
            userId,
            currentTenant(),
            disputeId),
        "更新我方立场与责任判定");
    return disputeDetail(disputeId);
  }

  /** 提交举证（举证准备中 → 举证已提交）。 */
  public PlatformDisputeDetail submitEvidence(
      UUID disputeId, PlatformDisputeSubmitEvidence request, UUID userId) {
    Record head = requireDispute(disputeId);
    requireVersion(head.get("version", Long.class), request.expectedVersion(), "纠纷单");

    // ⚠️ **超期后不可再提交举证**：在写之前就拦下来（DB 守卫① 再钉一道）。
    // 这一段刻意排在状态校验**之前** —— 超期时单据已经被自动判负了，
    // 报「当前是『我方败诉』」不如报「举证时限已过」说清楚发生了什么。
    OffsetDateTime deadline = head.get("evidence_deadline", OffsetDateTime.class);
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    if (deadline != null && !now.isBefore(deadline)) {
      throw badRequest(
          "举证时限已过（截止 " + deadline + "），不能再提交举证：超期自动判负是硬性时效要求");
    }

    requireStatus(head, java.util.Set.of(PlatformDisputeStatus.EVIDENCE_PREPARING), "提交举证");

    // 必举证项一个都不能缺。
    long missing =
        sql.fetchOne(
                "select count(*) from platform_dispute_evidence_item"
                    + " where tenant_id = ?::uuid and dispute_id = ?::uuid and required"
                    + " and evidence_state <> 'PROVIDED'",
                currentTenant(),
                disputeId)
            .get(0, Long.class);
    if (missing > 0) {
      throw badRequest(
          "还有 " + missing + " 项必举证材料没有具备，不能提交举证：超期即判负，缺一项就是白丢");
    }

    requireChanged(
        sql.execute(
            "update platform_dispute set dispute_status = 'EVIDENCE_SUBMITTED',"
                + " evidence_submitted_at = ?::timestamptz, evidence_submitted_by = ?::uuid,"
                + " evidence_submit_note = ?, updated_at = ?::timestamptz, updated_by = ?::uuid,"
                + " version = version + 1"
                + " where tenant_id = ?::uuid and id = ?::uuid and dispute_status = 'EVIDENCE_PREPARING'",
            now,
            userId,
            trimOrEmpty(request.submitNote()),
            now,
            userId,
            currentTenant(),
            disputeId),
        "提交举证");
    return disputeDetail(disputeId);
  }

  /** 记录平台裁决结果。 */
  public PlatformDisputeDetail resolve(
      UUID disputeId, PlatformDisputeResolve request, UUID userId) {
    Record head = requireDispute(disputeId);
    requireVersion(head.get("version", Long.class), request.expectedVersion(), "纠纷单");
    requireStatus(head, java.util.Set.of(PlatformDisputeStatus.EVIDENCE_SUBMITTED), "记录平台裁决");
    PlatformDisputeResolution code =
        parseEnum(PlatformDisputeResolution.class, request.resolutionCode(), "结果码");
    if (code == PlatformDisputeResolution.DEADLINE_EXPIRED) {
      throw badRequest(
          "「超期自动判负」是**系统**判的，不接受人工声明："
              + "过了举证截止时刻且未提交举证，DB 守卫① 会自动把纠纷落成败诉");
    }
    PlatformDisputeStatus target =
        switch (code) {
          case PLATFORM_RULING_FOR, MUTUAL_AGREEMENT -> PlatformDisputeStatus.RESOLVED_WON;
          case PLATFORM_RULING_AGAINST -> PlatformDisputeStatus.RESOLVED_LOST;
          case DEADLINE_EXPIRED, WITHDRAWN_BY_US ->
              throw badRequest("结果码「" + code.label() + "」不适用于平台裁决");
        };
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    requireChanged(
        sql.execute(
            "update platform_dispute set dispute_status = ?, resolution_code = ?,"
                + " resolution_note = ?, resolved_at = ?::timestamptz, resolved_by = ?::uuid,"
                + " updated_at = ?::timestamptz, updated_by = ?::uuid, version = version + 1"
                + " where tenant_id = ?::uuid and id = ?::uuid and dispute_status = 'EVIDENCE_SUBMITTED'",
            target.name(),
            code.name(),
            trimOrEmpty(request.resolutionNote()),
            now,
            userId,
            now,
            userId,
            currentTenant(),
            disputeId),
        "记录平台裁决");
    return disputeDetail(disputeId);
  }

  /** 我方撤诉。 */
  public PlatformDisputeDetail withdraw(UUID disputeId, PlatformDisputeWithdraw request, UUID userId) {
    Record head = requireDispute(disputeId);
    requireVersion(head.get("version", Long.class), request.expectedVersion(), "纠纷单");
    PlatformDisputeStatus status = PlatformDisputeStatus.valueOf(head.get("dispute_status", String.class));
    if (!status.evidenceOpen() && status != PlatformDisputeStatus.EVIDENCE_SUBMITTED) {
      throw badRequest("纠纷单已是终态（" + status.label() + "），不可撤回");
    }
    if (!isSet(request.withdrawReason())) {
      throw badRequest("撤回必须写理由：事后要答得出「这件纠纷为什么是我们自己撤的」");
    }
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    requireChanged(
        sql.execute(
            "update platform_dispute set dispute_status = 'WITHDRAWN',"
                + " resolution_code = 'WITHDRAWN_BY_US', resolution_note = ?,"
                + " resolved_at = ?::timestamptz, resolved_by = ?::uuid,"
                + " updated_at = ?::timestamptz, updated_by = ?::uuid, version = version + 1"
                + " where tenant_id = ?::uuid and id = ?::uuid and dispute_status = ?",
            request.withdrawReason().trim(),
            now,
            userId,
            now,
            userId,
            currentTenant(),
            disputeId,
            status.name()),
        "我方撤诉");
    return disputeDetail(disputeId);
  }

  // ================================================================ 结算影响

  /** 登记结算影响（**幂等**：已有待确认分录时返回同一条）。 */
  public PlatformDisputeDetail registerSettlement(
      UUID disputeId, PlatformDisputeSettlementCreate request, UUID userId) {
    Record head = requireDispute(disputeId);
    PlatformDisputeStatus status = PlatformDisputeStatus.valueOf(head.get("dispute_status", String.class));
    if (!status.terminal() || status == PlatformDisputeStatus.WITHDRAWN) {
      throw badRequest(
          "只有已终结（胜诉 / 败诉）的纠纷才有结算影响（当前 " + status.label() + "）");
    }
    if (request.requestedAmount() == null) {
      throw badRequest("必须给出登记的结算影响金额");
    }
    BigDecimal requested = money(request.requestedAmount(), "登记的结算影响金额");
    if (requested.signum() <= 0) {
      throw badRequest("登记的结算影响金额必须大于 0：" + request.requestedAmount());
    }

    // 幂等：已有待确认的分录时返回同一条，不新建第二条（重复登记是资损事故）。
    Record pending =
        sql.fetchOne(
                "select * from platform_dispute_settlement"
                    + " where tenant_id = ?::uuid and dispute_id = ?::uuid"
                    + " and settlement_status = 'PENDING_FINANCE_CONFIRM'"
                    + " order by created_at limit 1",
                currentTenant(),
                disputeId);
    if (pending != null) {
      return disputeDetail(disputeId);
    }

    sql.execute(
        "insert into platform_dispute_settlement(id,tenant_id,dispute_id,settlement_currency,"
            + "requested_amount,effective_amount,settlement_status,created_by)"
            + " values(?::uuid,?::uuid,?::uuid,?::char(3),?::numeric,0,'PENDING_FINANCE_CONFIRM',?::uuid)",
        UUID.randomUUID(),
        currentTenant(),
        disputeId,
        head.get("dispute_currency", String.class),
        requested,
        userId);
    return disputeDetail(disputeId);
  }

  /** 财务确认结算生效。 */
  public PlatformDisputeDetail confirmSettlement(
      UUID disputeId, UUID settlementId, PlatformDisputeSettlementConfirm request, UUID userId) {
    requireSettlement(disputeId, settlementId);
    if (!isSet(request.financeReference())) {
      throw badRequest("财务确认必须回填财务单据号：事后要答得出「扣的是哪一笔」");
    }
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    requireChanged(
        sql.execute(
            "update platform_dispute_settlement set settlement_status = 'CONFIRMED',"
                + " effective_amount = requested_amount, confirmed_by = ?::uuid,"
                + " confirmed_at = ?::timestamptz, finance_reference = ?, updated_at = ?::timestamptz,"
                + " version = version + 1"
                + " where tenant_id = ?::uuid and id = ?::uuid and dispute_id = ?::uuid"
                + " and settlement_status = 'PENDING_FINANCE_CONFIRM'",
            userId,
            now,
            request.financeReference().trim(),
            now,
            currentTenant(),
            settlementId,
            disputeId),
        "财务确认结算生效");
    return disputeDetail(disputeId);
  }

  /** 财务驳回结算影响。 */
  public PlatformDisputeDetail rejectSettlement(
      UUID disputeId, UUID settlementId, PlatformDisputeSettlementReject request, UUID userId) {
    requireSettlement(disputeId, settlementId);
    if (!isSet(request.rejectReason())) {
      throw badRequest("财务驳回必须写理由");
    }
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    requireChanged(
        sql.execute(
            "update platform_dispute_settlement set settlement_status = 'REJECTED',"
                + " effective_amount = 0, rejected_by = ?::uuid, rejected_at = ?::timestamptz,"
                + " reject_reason = ?, updated_at = ?::timestamptz, version = version + 1"
                + " where tenant_id = ?::uuid and id = ?::uuid and dispute_id = ?::uuid"
                + " and settlement_status = 'PENDING_FINANCE_CONFIRM'",
            userId,
            now,
            request.rejectReason().trim(),
            now,
            currentTenant(),
            settlementId,
            disputeId),
        "财务驳回结算影响");
    return disputeDetail(disputeId);
  }

  // ================================================================ 读时收口

  /**
   * **读时收口**：把已超期且未提交举证的纠纷落成败诉。
   *
   * <p>⚠️ 这不是「服务层自己判一遍」—— 判据与 DB 守卫① **完全相同**
   * （{@code evidence_deadline <= now() and evidence_submitted_at is null}），
   * 而**最终结论由 DB 写进那一行**（状态、结果码、结果时刻全部由触发器落）。
   * 服务层只做「让结论显形」这一步：
   * 否则「自动判负」要等下一次**别的**写入才会显形，界面上会一直显示「举证准备中」。
   *
   * <p>幂等：已经是败诉的行不再被选中。
   */
  private void expireOverdueDisputes() {
    sql.execute(
        "update platform_dispute set dispute_status = 'RESOLVED_LOST',"
            + " resolution_code = 'DEADLINE_EXPIRED', resolved_at = now(), resolved_by = null,"
            + " updated_at = now(), version = version + 1"
            + " where tenant_id = ?::uuid"
            + " and dispute_status in ('OPEN','EVIDENCE_PREPARING')"
            + " and evidence_submitted_at is null and evidence_deadline <= now()",
        currentTenant());
  }

  // ================================================================ 动作

  /** 当前可执行动作（每条都带**为什么不能做**）。 */
  private List<PlatformDisputeAction> actionsOf(
      PlatformDisputeRow head, List<PlatformDisputeEvidenceRow> evidenceItems) {
    PlatformDisputeStatus status = PlatformDisputeStatus.valueOf(head.disputeStatus());
    UUID id = head.id();
    boolean overdue = "PASSED".equals(head.deadlineState());
    long missingRequired =
        evidenceItems.stream()
            .filter(i -> i.required() && !"PROVIDED".equals(i.evidenceState()))
            .count();

    List<PlatformDisputeAction> actions = new ArrayList<>();
    actions.add(
        action(
            "START_EVIDENCE", "进入举证准备",
            status == PlatformDisputeStatus.OPEN && !evidenceItems.isEmpty(), "POST",
            "/trade/platform-disputes/" + id + "/start-evidence", false,
            "仅已立案，且已有举证清单",
            status != PlatformDisputeStatus.OPEN
                ? "只有已立案的纠纷可以进入举证准备"
                : (evidenceItems.isEmpty() ? "还没有举证清单：先把平台要求交的材料列出来" : null)));
    actions.add(
        action(
            "ADD_EVIDENCE_ITEM", "加举证材料项", status.evidenceOpen() && !overdue, "POST",
            "/trade/platform-disputes/" + id + "/evidence-items", false,
            "仅已立案 / 举证准备中，且未超期",
            status.evidenceOpen()
                ? (overdue ? "举证时限已过，不能再补材料" : null)
                : "纠纷单已是「" + status.label() + "」，举证清单已冻结"));
    actions.add(
        action(
            "MARK_EVIDENCE", "更新举证材料状态", status.evidenceOpen() && !overdue, "PUT",
            "/trade/platform-disputes/" + id + "/evidence-items/{itemId}", false,
            "仅已立案 / 举证准备中，且未超期",
            status.evidenceOpen()
                ? (overdue ? "举证时限已过，不能再补材料" : null)
                : "纠纷单已是「" + status.label() + "」，举证清单已冻结"));
    actions.add(
        action(
            "UPDATE_POSITION", "更新我方立场与责任判定",
            status.evidenceOpen() || status == PlatformDisputeStatus.EVIDENCE_SUBMITTED, "POST",
            "/trade/platform-disputes/" + id + "/position", false,
            "仅已立案 / 举证准备中 / 已提交举证",
            (status.evidenceOpen() || status == PlatformDisputeStatus.EVIDENCE_SUBMITTED)
                ? null
                : "纠纷单已是终态（" + status.label() + "），立场不可再改"));
    actions.add(
        action(
            "SUBMIT_EVIDENCE", "提交举证",
            status == PlatformDisputeStatus.EVIDENCE_PREPARING && !overdue && missingRequired == 0,
            "POST", "/trade/platform-disputes/" + id + "/submit-evidence", false,
            "仅举证准备中；必举证项全部具备且未超期",
            submitBlocked(status, overdue, missingRequired)));
    actions.add(
        action(
            "RESOLVE", "记录平台裁决结果", status == PlatformDisputeStatus.EVIDENCE_SUBMITTED,
            "POST", "/trade/platform-disputes/" + id + "/resolve", true,
            "仅已提交举证",
            status == PlatformDisputeStatus.EVIDENCE_SUBMITTED
                ? null
                : "只有已提交举证的纠纷才有平台裁决"));
    actions.add(
        action(
            "WITHDRAW", "我方撤诉",
            status.evidenceOpen() || status == PlatformDisputeStatus.EVIDENCE_SUBMITTED, "POST",
            "/trade/platform-disputes/" + id + "/withdraw", true,
            "仅已立案 / 举证准备中 / 已提交举证；必须写理由",
            (status.evidenceOpen() || status == PlatformDisputeStatus.EVIDENCE_SUBMITTED)
                ? null
                : "纠纷单已是终态（" + status.label() + "），不可撤回"));
    boolean pendingSettlement = "PENDING_FINANCE_CONFIRM".equals(head.settlementState());
    actions.add(
        action(
            "REGISTER_SETTLEMENT", "登记结算影响",
            (status == PlatformDisputeStatus.RESOLVED_WON || status == PlatformDisputeStatus.RESOLVED_LOST)
                && !pendingSettlement,
            "POST", "/trade/platform-disputes/" + id + "/settlements", false,
            "仅已终结（胜诉 / 败诉）；登记后**等待财务确认才生效**",
            (status == PlatformDisputeStatus.RESOLVED_WON || status == PlatformDisputeStatus.RESOLVED_LOST)
                ? (pendingSettlement ? "已有一条待财务确认的分录（重复登记是幂等的，不会重复结算）" : null)
                : "只有已终结的纠纷才有结算影响"));
    actions.add(
        action(
            "CONFIRM_SETTLEMENT", "财务确认结算生效", pendingSettlement, "POST",
            "/trade/platform-disputes/" + id + "/settlements/{settlementId}/confirm", true,
            "仅待财务确认；需回填财务单据号",
            pendingSettlement ? null : "没有待财务确认的结算分录"));
    actions.add(
        action(
            "REJECT_SETTLEMENT", "财务驳回结算影响", pendingSettlement, "POST",
            "/trade/platform-disputes/" + id + "/settlements/{settlementId}/reject", true,
            "仅待财务确认；必须写理由",
            pendingSettlement ? null : "没有待财务确认的结算分录"));
    // 「超期自动判负」**刻意没有手动入口**（见能力声明的 boundaries）。
    actions.add(
        action(
            "AUTO_LOSE", "手动标记超期判负", false, null, null, false, null,
            "超期自动判负是系统做的（DB 守卫①），刻意不提供手动入口"));
    return actions;
  }

  private static String submitBlocked(
      PlatformDisputeStatus status, boolean overdue, long missingRequired) {
    if (status != PlatformDisputeStatus.EVIDENCE_PREPARING) {
      return "只有举证准备中的纠纷可以提交举证";
    }
    if (overdue) {
      return "举证时限已过，超期自动判负，不能再提交举证";
    }
    if (missingRequired > 0) {
      return "还有 " + missingRequired + " 项必举证材料没有具备";
    }
    return null;
  }

  // ================================================================ 小工具

  private UUID currentTenant() {
    return UUID.fromString(sql.fetchOne("select current_setting('app.current_tenant')").get(0, String.class));
  }

  /** 取纠纷单（**按 id 直达**的端点必须显式带租户谓词）。 */
  private Record requireDispute(UUID id) {
    Record r =
        sql.fetchOne(
            "select d.*,"
                + " coalesce(ev.ev_total, 0) as ev_total,"
                + " coalesce(ev.ev_provided, 0) as ev_provided,"
                + " coalesce(ev.ev_missing, 0) as ev_missing,"
                + " extract(epoch from (d.evidence_deadline - now()))::bigint as seconds_remaining,"
                + " case"
                + "   when d.evidence_submitted_at is not null then 'RELEASED'"
                + "   when d.resolution_code = 'DEADLINE_EXPIRED' then 'PASSED'"
                + "   when d.dispute_status in ('RESOLVED_WON','RESOLVED_LOST','WITHDRAWN') then 'RELEASED'"
                + "   when d.evidence_deadline <= now() then 'PASSED'"
                + "   when d.evidence_deadline <= now() + interval '72 hours' then 'URGENT'"
                + "   else 'NORMAL' end as deadline_state"
                + " from platform_dispute d"
                + " left join lateral ("
                + "   select count(*) as ev_total,"
                + "          count(*) filter (where evidence_state = 'PROVIDED') as ev_provided,"
                + "          count(*) filter (where evidence_state = 'MISSING') as ev_missing"
                + "     from platform_dispute_evidence_item e"
                + "    where e.tenant_id = d.tenant_id and e.dispute_id = d.id) ev on true"
                + " where d.tenant_id = ?::uuid and d.id = ?::uuid",
            currentTenant(),
            id);
    if (r == null) {
      // ⚠️ 与「不存在」**完全相同**的错误：拿别的租户的 id 来读，不该得到更多信息。
      throw notFound("纠纷单不存在或不属于当前租户：" + id);
    }
    return r;
  }

  private void requireSettlement(UUID disputeId, UUID settlementId) {
    Record r =
        sql.fetchOne(
            "select * from platform_dispute_settlement"
                + " where tenant_id = ?::uuid and id = ?::uuid and dispute_id = ?::uuid",
            currentTenant(),
            settlementId,
            disputeId);
    if (r == null) {
      throw notFound("结算分录不存在或不属于当前租户：" + settlementId);
    }
  }

  /** 举证清单的可改窗口：已立案 / 举证准备中 **且** 未超期。 */
  private void requireEvidenceWindow(Record head, String action) {
    PlatformDisputeStatus status =
        PlatformDisputeStatus.valueOf(head.get("dispute_status", String.class));
    if (!status.evidenceOpen()) {
      throw badRequest(
          "纠纷单当前是「" + status.label() + "」，举证清单已冻结，不能" + action);
    }
    OffsetDateTime deadline = head.get("evidence_deadline", OffsetDateTime.class);
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    if (deadline != null && !now.isBefore(deadline)) {
      throw badRequest(
          "举证时限已过（截止 " + deadline + "），不能" + action + "：超期自动判负是硬性时效要求");
    }
  }

  private static void requireVersion(Long current, Long expected, String label) {
    if (expected == null) {
      throw badRequest("必须带 expectedVersion（改状态要走乐观锁，避免两个人同时改同一件纠纷）");
    }
    if (!expected.equals(current)) {
      throw badRequest(
          label + "已被别人改过（当前版本 " + current + "，你拿到的是 " + expected + "），请刷新后重试");
    }
  }

  private static void requireStatus(Record head, java.util.Set<PlatformDisputeStatus> allowed, String action) {
    PlatformDisputeStatus status =
        PlatformDisputeStatus.valueOf(head.get("dispute_status", String.class));
    if (!allowed.contains(status)) {
      throw badRequest(
          "纠纷单当前是「" + status.label() + "」，不能" + action + "（允许的状态："
              + allowed.stream().map(PlatformDisputeStatus::label).toList() + "）");
    }
  }

  private static void requireChanged(int changed, String action) {
    if (changed == 0) {
      throw badRequest(action + "失败：状态已经变了（可能被别人抢先生效），请刷新后重试");
    }
  }

  /** 金额：4 位小数，禁浮点。 */
  private static BigDecimal money(BigDecimal value, String label) {
    if (value == null) {
      throw badRequest("必须给出" + label);
    }
    BigDecimal scaled = value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    if (scaled.signum() < 0) {
      throw badRequest(label + "不能为负：" + value);
    }
    return scaled;
  }

  private static String requireCurrency(String raw) {
    if (!isSet(raw)) {
      throw badRequest("必须给出币种（一单一币种，跨币种按币种分组，不做固定汇率折算）");
    }
    String currency = raw.trim().toUpperCase(java.util.Locale.ROOT);
    if (!currency.matches("^[A-Z]{3}$")) {
      throw badRequest("币种必须是三位大写字母：" + raw);
    }
    return currency;
  }

  private static String requirePlatformCode(String raw) {
    if (!isSet(raw)) {
      throw badRequest("必须给出平台码");
    }
    String code = raw.trim().toUpperCase(java.util.Locale.ROOT);
    if (!code.matches(PLATFORM_CODE_PATTERN)) {
      throw badRequest(
          "平台码形状必须是「大写字母 / 数字开头，后接大写字母、数字、下划线或连字符」（最多 32 位）：" + raw);
    }
    return code;
  }

  private static String requireText(String raw, String label) {
    if (!isSet(raw)) {
      throw badRequest("必须给出" + label);
    }
    return raw.trim();
  }

  private static <E extends Enum<E>> E parseEnum(Class<E> type, String raw, String label) {
    if (!isSet(raw)) {
      throw badRequest("必须给出" + label);
    }
    try {
      return Enum.valueOf(type, raw.trim());
    } catch (IllegalArgumentException e) {
      throw badRequest(
          label + "取值非法：" + raw + "（可选：" + java.util.Arrays.toString(type.getEnumConstants()) + "）");
    }
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

  /** 行 → 传输模型。 */
  private static PlatformDisputeRow mapDisputeRow(Record r) {
    String type = r.get("dispute_type", String.class);
    String status = r.get("dispute_status", String.class);
    String position = r.get("our_position", String.class);
    String stage = r.get("liability_stage", String.class);
    String settlement = r.get("settlement_status", String.class);
    String deadlineState = r.get("deadline_state", String.class);
    String resolution = r.get("resolution_code", String.class);
    Long seconds = r.get("seconds_remaining", Long.class);

    return new PlatformDisputeRow(
        r.get("id", UUID.class),
        r.get("dispute_no", String.class),
        r.get("platform_code", String.class),
        r.get("platform_name", String.class),
        r.get("platform_dispute_no", String.class),
        r.get("platform_order_no", String.class),
        type,
        PlatformDisputeType.valueOf(type).label(),
        r.get("dispute_reason", String.class),
        r.get("dispute_currency", String.class),
        r.get("dispute_amount", BigDecimal.class),
        r.get("evidence_deadline", OffsetDateTime.class),
        seconds == null ? 0L : seconds,
        deadlineState,
        PlatformDeadlineState.valueOf(deadlineState).label(),
        position,
        PlatformDisputePosition.valueOf(position).label(),
        r.get("position_note", String.class),
        stage,
        PlatformLiabilityStage.valueOf(stage).label(),
        r.get("liability_note", String.class),
        status,
        PlatformDisputeStatus.valueOf(status).label(),
        r.get("evidence_submitted_at", OffsetDateTime.class),
        settlement,
        PlatformSettlementState.valueOf(settlement).label(),
        r.get("effective_amount", BigDecimal.class),
        r.get("ev_total", Long.class),
        r.get("ev_provided", Long.class),
        r.get("ev_missing", Long.class),
        resolution,
        resolution == null ? null : PlatformDisputeResolution.valueOf(resolution).label(),
        r.get("resolved_at", OffsetDateTime.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class),
        r.get("version", Long.class));
  }

  private static String eventLabel(String code) {
    return switch (code) {
      case "CREATED" -> "登记平台纠纷";
      case "EVIDENCE_ITEM_ADDED" -> "加入举证材料项";
      case "EVIDENCE_ITEM_UPDATED" -> "更新举证材料项";
      case "EVIDENCE_ITEM_REMOVED" -> "移除举证材料项";
      case "POSITION_UPDATED" -> "更新我方立场与责任判定";
      case "EVIDENCE_SUBMITTED" -> "提交举证";
      case "RESOLVED_WON" -> "我方胜诉";
      case "RESOLVED_LOST" -> "我方败诉";
      case "WITHDRAWN" -> "我方撤诉";
      case "SETTLEMENT_REGISTERED" -> "登记结算影响";
      case "SETTLEMENT_CONFIRMED" -> "财务确认结算生效";
      case "SETTLEMENT_REJECTED" -> "财务驳回结算影响";
      default -> code;
    };
  }

  private static PlatformDisputeSectionCapability section(
      String section, String label, boolean available, String ownerModule, String reason,
      String landsIn) {
    return new PlatformDisputeSectionCapability(section, label, available, ownerModule, reason, landsIn);
  }

  private static PlatformDisputeRelatedModule related(
      String id, String label, boolean delivered, String ownerModule, String reason, String href) {
    return new PlatformDisputeRelatedModule(id, label, delivered, ownerModule, reason, href);
  }

  private static PlatformDisputeCapabilityAction action(
      String code, String label, boolean allowed, String method, String path, boolean required,
      String precondition, String blockingReason, String ownerModule, String unavailableReason) {
    return new PlatformDisputeCapabilityAction(
        code, label, allowed, method, path, required, precondition, blockingReason, ownerModule,
        unavailableReason);
  }

  private static PlatformDisputeBoundary boundary(String boundary, String ownerModule, String reason) {
    return new PlatformDisputeBoundary(boundary, ownerModule, reason);
  }

  private static PlatformDisputeAction action(
      String code,
      String label,
      boolean allowed,
      String method,
      String path,
      boolean required,
      String precondition,
      String blockedReason) {
    return new PlatformDisputeAction(
        code, label, allowed, method, path, required, precondition, blockedReason);
  }

  /** 纠纷单排序白名单。 */
  private enum PlatformDisputeSort {
    DEADLINE_ASC("d.evidence_deadline asc nulls last, d.created_at desc, d.id desc"),
    CREATED_DESC("d.created_at desc, d.id desc"),
    AMOUNT_DESC("d.dispute_amount desc nulls last, d.created_at desc, d.id desc"),
    PLATFORM_ASC("d.platform_code asc, d.evidence_deadline asc nulls last, d.id desc");

    private final String sql;

    PlatformDisputeSort(String sql) {
      this.sql = sql;
    }

    String sql() {
      return sql;
    }

    static PlatformDisputeSort parse(String raw) {
      if (raw == null) {
        return DEADLINE_ASC;
      }
      return switch (raw) {
        case "deadline_asc" -> DEADLINE_ASC;
        case "created_desc" -> CREATED_DESC;
        case "amount_desc" -> AMOUNT_DESC;
        case "platform_asc" -> PLATFORM_ASC;
        default -> DEADLINE_ASC;
      };
    }
  }
}
