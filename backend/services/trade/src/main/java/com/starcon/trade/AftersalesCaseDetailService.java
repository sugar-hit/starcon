package com.starcon.trade;

import static com.starcon.trade.AftersalesCaseDetailModels.*;

import com.starcon.trade.AftersalesCaseModels.AftersalesCaseStatus;
import com.starcon.trade.AftersalesCaseModels.AftersalesCaseType;
import com.starcon.trade.AftersalesCaseModels.AftersalesDecision;
import com.starcon.trade.AftersalesCaseModels.AftersalesOwnerSource;
import com.starcon.trade.AftersalesCaseModels.AftersalesReasonCode;
import com.starcon.trade.AftersalesCaseModels.AftersalesTicketPriority;
import com.starcon.trade.AftersalesCaseModels.AftersalesTimelinessState;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Service;

/**
 * 11.1.1.2 售后单详情。
 *
 * <p><b>本类是 V157 三张新表的写入所有者</b>（aftersales_case_line / aftersales_message /
 * aftersales_attachment），对 V156 的 aftersales_case 簇与 V99 的 sales_order 簇<b>只读</b>。
 * 刻意不加外键、不 ALTER 既有表 ⇒ 跨车道 DDL 回归面 = 0。
 *
 * <p><b>五条硬纪律</b>：
 *
 * <ol>
 *   <li><b>每一条查询都显式带 {@code tenant_id} 谓词</b>，不依赖 RLS 当唯一防线。
 *       RLS 是最后一道（FORCE 到 owner），查询本身也带条件，两层独立生效。
 *       详情页尤其危险：它是**按 id 直达**的 URL，猜到一个 UUID 就能看，不需要翻列表。
 *   <li><b>时效判定只有一个真身</b>（DB 函数 {@code aftersales_timeliness_state()}，V156）。
 *       Java 侧<b>不</b>重算；参考时刻在 {@link #detail} 开头取一次并整份聚合复用。
 *   <li><b>未交付的下游一律 reported as false + 归属模块号</b>（{@link #relatedModules()}）。
 *       11.2 / 11.3 / 11.4 当前全部未交付 ⇒ <b>不建影子表</b>：一张本服务自造的
 *       「退款记录」会被当成已对接，而退货退款是资金动作，认错账的代价极高。
 *   <li><b>原订单字段不复制</b>。订单号、金额、客户与地址全部从 sales_order 簇实时读 ——
 *       复制一份就会漂移，而「截图上的订单号和系统里的不一样」在纠纷举证里最致命。
 *   <li><b>时间绑定</b>：时间一律 {@code ?::timestamptz} + {@link OffsetDateTime}。
 *       {@code Timestamp.valueOf(LocalDateTime)} 会静默偏 −28800s，
 *       而时间线的顺序错位在申诉里往往不被采信。
 * </ol>
 *
 * <p><b>时间线是对齐的</b>（蓝图加粗句）：十个来源用 {@code UNION ALL} 在**一条 SQL** 里并起来，
 * 统一按 {@code occurred_at}（事件<b>发生</b>的时刻）排序，每条带 {@code actorSide}
 * （BUYER / US / PLATFORM / SYSTEM）。在 Java 里拼时间线需要把七张表各查一遍再排序，
 * 而「先查出来再合并」很容易在某一路失败时静默少一段 —— 举证时间线少一段比没有更糟。
 */
@Service
public class AftersalesCaseDetailService {

  /** 时间线单页上限。单张单的事件量有界，但「一页 10000 条」既慢又没人看得完。 */
  private static final int MAX_TIMELINE_PAGE_SIZE = 200;

  private final DSLContext sql;

  public AftersalesCaseDetailService(DSLContext sql) {
    this.sql = sql;
  }

  // ================================================================ 能力声明

  /**
   * 能力声明：九个职责区块逐块报可用性、下游模块逐个报 Delivered=false 与归属、写入来源如实标注。
   *
   * <p>让界面能说清「为什么没有退款记录」「凭证是谁上传的」，而不是让它们显示成空白
   * 让人以为「这张单没有退款也没有纠纷」。
   */
  public AftersalesDetailCapabilities capabilities() {
    return new AftersalesDetailCapabilities(
        List.of(
            "原订单信息 sales_order / sales_order_party_snapshot / order_address_snapshot（V99，同库只读）",
            "申请内容与买家诉求 aftersales_case（reason_code / reason_note / buyer_request / 金额 / 数量）",
            "商品行与数量 aftersales_case_line（行级涉及量与金额）+ sales_order_line 的 SKU 快照",
            "沟通记录 aftersales_message（追加式，带方向、渠道、发生时刻与采集时刻）",
            "附件与凭证 aftersales_attachment（追加式，带类别、提供方与对象存储引用）",
            "处置流程状态 aftersales_case.status + 时效函数 + 授权 / 工单（V156）",
            "平台侧状态 aftersales_case 的平台受理号 / 受理时刻 / 两个时限（V156）",
            "责任判定 授权决定 + 原因分类 + 责任人流转（合成，环节级归因未交付）",
            "时间线 十个来源 UNION ALL，按发生时刻排序并标出说话方"),
        List.of(
            section("orderInfo", "原订单信息", true, null, null,
                "sales_order 簇（V99，同库只读，不复制字段）"),
            section("buyerRequest", "申请内容与买家诉求", true, null, null,
                "aftersales_case 列（V156）"),
            section("lines", "商品行与数量", true, null, null,
                "aftersales_case_line（V157）+ sales_order_line 的 SKU 快照（V99）"),
            section("messages", "沟通记录", true, "2 平台消息",
                "卷 2 平台消息在 channel 独立库 starcon_channel 且**当前无任何平台消息表**"
                    + "（11.1.1.1 已实查），按 AGENTS.md 模块边界纪律**禁止跨服务 SQL / 视图 / FK** "
                    + "⇒ 本服务读不到真实对话流。照抄 V150 signal_source 与 V156 owner_source 的既有解法："
                    + "落成**租户可维护的真实表** aftersales_message，每行强制 message_source 枚举与 "
                    + "captured_at 采集时刻；正式接口到位后由同步器按**同一形状**写入并把来源改成 "
                    + "MODULE_2_PLATFORM_MESSAGE，**表结构与查询都不用改**。",
                "aftersales_message（V157，租户可维护）"),
            section("attachments", "附件与凭证", true, null,
                "本服务**没有文件服务**（16 合规与对象存储未交付）⇒ 只存对象存储定位符"
                    + "storage_ref，**不存文件内容**：把图片 base64 塞进库会让一行凭证"
                    + "把详情响应顶到几十 MB，而纠纷举证要打包的是**引用**不是字节。"
                    + "文件服务交付后由它按同一形状签发 storage_ref。",
                "aftersales_attachment（V157，租户可维护）"),
            section("processState", "处置流程状态", true, null, null,
                "aftersales_case.status / 授权 / 工单（V156）"),
            section("platformState", "平台侧状态", true, "2 平台同步 + 11.4.1 纠纷",
                "平台受理号 / 受理时刻 / 两个时限在 aftersales_case 上（V156，真数据）；"
                    + "但**平台最终判定结果**（判退 / 不退 / 部分退）属 2 平台同步与 11.4 平台纠纷，"
                    + "两者**均未交付** ⇒ platformDecisionStatus 恒为 null 并报 available=false。"
                    + "填一个占位状态会让运营以为平台已经判过责，而它没判。",
                "aftersales_case 列（V156）"),
            section("relatedRefunds", "退款与退货关联", false, "11.2 退货管理 / 11.3 退款与换货",
                "11.2（退货授权 / 退货实收 / 质检）与 11.3（退款申请 / 退款状态）**当前全部未交付**。"
                    + "本模块**刻意不建影子表**：一张本服务自造的「退款记录」会被当成已对接，"
                    + "而退货退款是资金动作，认错账的代价极高。区块显示「该能力未交付」+ 归属模块号。",
                null),
            section("liability", "责任判定", true, "11.1.2.2 商品问题归因",
                "「我们**认了**还是**不认**」由**真实的**授权决定 + 原因分类 + 责任人流转合成，可得；"
                    + "但**环节级归因**（生产 / 运输 / 仓储 / 描述 + 批次追溯与照片比对）属 11.1.2.2，"
                    + "**未交付** ⇒ attributionDelivered=false 并如实报归属模块号。"
                    + "**不编一条归因结论出来**：编出来的归因会在 6.4.2.2 供应商索赔与 11.5.2 质量分析里"
                    + "被当成真结论用。",
                "aftersales_authorization + aftersales_case.reason_code + "
                    + "aftersales_owner_assignment（V156）"),
            section("timeline", "时间线", true, null, null,
                "十个来源 UNION ALL，按发生时刻排序（V157 聚合查询）")),
        relatedModules(),
        actionEntries(true),
        List.of(
            new AftersalesWriteSource(
                "aftersales_message", "沟通记录", AftersalesDetailSource.TENANT_MAINTAINED.name(),
                AftersalesDetailSource.TENANT_MAINTAINED.label(), false, "2 平台消息",
                "卷 2 平台消息未交付（channel 独立库且无任何平台消息表），跨服务读被模块边界纪律禁止。"
                    + "当前只能由租户**转录**平台对话；同步器到位后写同一张表、把来源改成 "
                    + "MODULE_2_PLATFORM_MESSAGE。渠道（PLATFORM）与来源是两个维度："
                    + "渠道说「在哪说的」，来源说「谁写进来的」，合成一列会让转录件被读成系统原件。"),
            new AftersalesWriteSource(
                "aftersales_attachment", "附件与凭证", AftersalesDetailSource.TENANT_MAINTAINED.name(),
                AftersalesDetailSource.TENANT_MAINTAINED.label(), false, "2 平台消息",
                "同上：平台侧下发的凭证当前只能由租户登记引用。"),
            new AftersalesWriteSource(
                "aftersales_owner_assignment", "责任人", AftersalesOwnerSource.TENANT_MAINTAINED.name(),
                AftersalesOwnerSource.TENANT_MAINTAINED.label(), false, "15 客户与客服",
                "卷 15 的 24 个 L4 里只交付 1 个（15.4.1.1，在 crm 独立库），本服务永远读不到它的表。"
                    + "责任人当前由租户登记，正式接口到位后同步器按同一形状写入。")),
        false,
        "跨服务读**一律禁止**（AGENTS.md 模块边界纪律）。三个上游缺口全部照 V150 / V156 的既有解法落成"
            + "**租户可维护的表 + 来源枚举 + 采集时刻**：平台消息与凭证在 aftersales_message / "
            + "aftersales_attachment，责任人在 aftersales_owner_assignment（V156）。"
            + "正式接口到位后同步器按同一形状写入并改来源，**表结构与查询都不用改**。",
        "附件只存**对象存储定位符**（storage_ref）而不是文件内容：本服务没有文件服务，"
            + "存 base64 会让一行凭证把详情响应顶到几十 MB。文件服务交付后由它签发同一形状的引用。",
        "时间线 = 十个来源 UNION ALL，**按事件发生时刻排序**（不是按录入库的时刻）："
            + "昨天发生的消息今天补录，应当排在昨天授权之后而不是今天授权之后。每条带说话方"
            + "（BUYER / US / PLATFORM / SYSTEM），因为举证要回答的是「谁先说的」——"
            + "错位的时间线在申诉里往往不被采信。");
  }

  private static AftersalesSectionCapability section(
      String section, String label, boolean available, String ownerModule, String reason, String landsIn) {
    return new AftersalesSectionCapability(section, label, available, ownerModule, reason, landsIn);
  }

  /**
   * 关联模块可用性。**11.2 / 11.3 / 11.4 当前全部未交付** ⇒ 逐条如实报 false 与归属模块号。
   *
   * <p>{@code href} 刻意恒为 null：未交付的模块没有页面可跳，一个点进去 404 的链接
   * 比没有链接更容易让人误以为功能已交付。
   */
  private List<AftersalesRelatedModule> relatedModules() {
    return List.of(
        new AftersalesRelatedModule(
            "RETURNS", "退货管理", false, "11.2 退货管理",
            "退货是货回来、退款是钱出去（蓝图 11 卷第一句）。11.2 的退货授权 / 退货实收 / "
                + "质检与处置**全部未交付**（warehouse 与 trade 两侧都没有对应表）。"
                + "本模块**刻意不建影子表**：一张本服务自造的「退货记录」会被当成已对接，"
                + "而实收与处置是实物与库存的动作，认错的代价是错发可售库存。",
            null),
        new AftersalesRelatedModule(
            "REFUNDS", "退款与换货", false, "11.3 退款与换货",
            "11.3.1.1 退款申请与 11.3.1.2 退款状态**均未交付**；退款执行由 12 财务负责并回报，"
                + "本服务算不出也不猜平台实际退款额（那是资金动作，认错账就是资损）。",
            null),
        new AftersalesRelatedModule(
            "DISPUTES", "平台纠纷", false, "11.4 平台纠纷与客诉",
            "11.4.1.1 纠纷列表与 11.4.1.2 证据与申诉**均未交付**。"
                + "本详情页的时间线与凭证区块是**申诉材料的来源**，但「打包提交申诉」是 11.4 的主权，"
                + "本模块**不假装**自己有那个动作。",
            null),
        new AftersalesRelatedModule(
            "INTAKE", "售后受理", false, "11.1.1.3 售后受理",
            "11.1.1.3 的受理判定（售后期校验 / 条件判定 / 责任人自动分配 / 回复时限设置）未交付。"
                + "当前登记入口是 11.1.1.1 的 POST /cases，**只做单据落库 + 平台时限登记**。",
            null),
        new AftersalesRelatedModule(
            "ATTRIBUTION", "商品问题归因", false, "11.1.2.2 商品问题归因",
            "环节级归因（生产 / 运输 / 仓储 / 描述）+ 批次追溯与照片比对未交付。",
            null));
  }

  /** 操作入口。{@code terminal=false} 时把「已结案不允许追加」这一条如实带在 precondition 里。 */
  private List<AftersalesActionEntry> actionEntries(boolean terminal) {
    String open = terminal
        ? "售后单已结案：追加沟通记录 / 凭证 / 商品行 / 备注一律被拒（结案的语义是「这张单办完了」）"
        : null;
    return List.of(
        new AftersalesActionEntry("ADD_NOTE", "加备注", !terminal, "POST",
            "/trade/aftersales/cases/{id}/notes", true, open, null,
            terminal ? "售后单已结案，追加备注被拒" : null),
        new AftersalesActionEntry("ADD_MESSAGE", "登记沟通记录", !terminal, "POST",
            "/trade/aftersales/cases/{id}/messages", true, open, null,
            terminal ? "售后单已结案，追加沟通记录被拒" : null),
        new AftersalesActionEntry("ADD_ATTACHMENT", "上传凭证", !terminal, "POST",
            "/trade/aftersales/cases/{id}/attachments", true, open, null,
            terminal ? "售后单已结案，追加凭证被拒" : null),
        new AftersalesActionEntry("TIMELINE", "查看时间线", true, "GET",
            "/trade/aftersales/cases/{id}/timeline", false, null, null, null),
        new AftersalesActionEntry("ADD_LINE", "登记商品行", !terminal, "POST",
            "/trade/aftersales/cases/{id}/lines", true, open, null,
            terminal ? "售后单已结案，登记商品行被拒" : null),
        new AftersalesActionEntry("BULK_AUTHORIZE", "批量授权（列表页）", true, "POST",
            "/trade/aftersales/cases/bulk", true, "单次上限 100，逐条结果回报", null, null),
        new AftersalesActionEntry("INTAKE", "受理判定", false, null, null, false, null,
            "11.1.1.3 售后受理",
            "受理判定（售后期校验 / 条件判定 / 责任人自动分配 / 回复时限设置）属 11.1.1.3，**未交付**。"),
        new AftersalesActionEntry("CREATE_RETURN", "发起退货授权", false, null, null, false, null,
            "11.2.1.1 退货授权单", "11.2 未交付，本服务没有退货授权这个动作。"),
        new AftersalesActionEntry("CREATE_REFUND", "发起退款", false, null, null, false, null,
            "11.3.1.1 退款申请", "11.3 未交付，且退款执行由 12 财务负责。"),
        new AftersalesActionEntry("FILE_DISPUTE", "提交平台申诉", false, null, null, false, null,
            "11.4.1.2 证据与申诉",
            "11.4 未交付。本页的时间线与凭证是**申诉材料的来源**，但打包提交是 11.4 的主权。"));
  }

  // ================================================================ 详情聚合

  /**
   * 单售后全景：一次请求返回职责句里的九个区块 + 操作入口 + 时间线。
   *
   * <p><b>聚合是多次查询在服务层拼的，不是一段巨型 SQL</b>：七张子表的形状差异太大
   * （有的要 JOIN sales_order 取 SKU 快照、有的只需要主键），硬塞进一个巨型 UNION 会让
   * 「哪一段漏了租户谓词」变得不可审阅。安全靠两条固定纪律保证：每段都带显式 tenant 谓词，
   * 且 RLS FORCE 到 owner 作为第二道。
   */
  public AftersalesCaseDetail detail(UUID caseId) {
    // **整份聚合共用的参考时刻**：判定时效、算 remainingSeconds 用同一个值。
    OffsetDateTime at = OffsetDateTime.now(ZoneOffset.UTC);

    Record head =
        sql.fetchOne(
            "select a.id, a.case_no, a.case_type, a.status, a.reason_code, a.reason_note,"
                + " a.requested_amount, a.currency, a.requested_quantity, a.buyer_request,"
                + " a.reply_due_at, a.resolve_due_at, a.effective_due_at, a.received_at,"
                + " a.resolved_at, a.platform_case_no, a.platform_case_url, a.platform_received_at,"
                + " aftersales_timeliness_state(a.effective_due_at, a.resolved_at, ?::timestamptz)"
                + "   as timeliness_state,"
                + " extract(epoch from (a.effective_due_at - ?::timestamptz))::bigint"
                + "   as remaining_seconds,"
                + " a.order_id,"
                + " o.order_no, o.order_source, o.external_order_no, o.status as order_status,"
                + " o.currency as order_currency, o.payment_method, o.grand_total_amount,"
                + " o.subtotal_amount, o.shipping_amount, o.submitted_at,"
                + " ps.customer_code, ps.customer_name, ps.customer_type,"
                + " ad.recipient_name, ad.recipient_phone, ad.recipient_email, ad.country_code,"
                + " ad.province, ad.city, ad.address_line1, ad.address_line2"
                + " from aftersales_case a"
                + " left join sales_order o on o.tenant_id = a.tenant_id and o.id = a.order_id"
                + " left join sales_order_party_snapshot ps"
                + "   on ps.tenant_id = a.tenant_id and ps.order_id = a.order_id"
                + " left join order_address_snapshot ad"
                + "   on ad.tenant_id = a.tenant_id and ad.order_id = a.order_id"
                + "  and ad.address_role = 'SHIPPING'"
                + " where a.tenant_id = ?::uuid and a.id = ?::uuid",
            at,
            at,
            currentTenant(),
            caseId);
    if (head == null) {
      // 跨租户的 ID 与不存在的 ID **同一条消息**：不告诉调用方「这个 ID 存在但不属于你」。
      throw new IllegalArgumentException("售后单不存在或不属于当前租户：" + caseId);
    }

    AftersalesCaseStatus status = AftersalesCaseStatus.valueOf(head.get("status", String.class));
    AftersalesCaseHeader header = header(head, status, at);
    UUID orderId = head.get("order_id", UUID.class);

    // 订单缺失是**数据异常**（V156 刻意不加外键），界面要显示「关联订单缺失」并让人去查。
    AftersalesOrderInfo order =
        head.get("order_no", String.class) == null ? null : orderInfo(head);

    List<AftersalesOrderLine> orderLines =
        orderId == null ? List.of() : orderLines(orderId, caseId);
    List<AftersalesCaseLine> lines = caseLines(caseId);
    List<AftersalesMessage> messages = messages(caseId);
    List<AftersalesAttachment> attachments = attachments(caseId);
    List<AftersalesCaseNote> notes = notes(caseId);
    List<AftersalesAuthorization> authorizations = authorizations(caseId);
    List<AftersalesOwnerAssignment> owners = ownerAssignments(caseId);
    AftersalesServiceTicket ticket = ticket(caseId);

    List<AftersalesTimelineEvent> timeline = timeline(caseId);
    long buyerEvents = timeline.stream().filter(e -> "BUYER".equals(e.actorSide())).count();
    long ourEvents = timeline.stream().filter(e -> "US".equals(e.actorSide())).count();

    return new AftersalesCaseDetail(
        header,
        order,
        orderLines,
        lines,
        messages,
        attachments,
        notes,
        authorizations,
        owners,
        ticket,
        platformStatus(head, status, at),
        liability(header, authorizations, owners),
        timeline,
        timeline.size(),
        buyerEvents,
        ourEvents,
        relatedModules(),
        actionEntries(status.terminal()),
        at,
        "时间线把「买家说了什么」与「我们做了什么」按**发生时刻**排在一起，并标出说话方"
            + "（买家 / 我方 / 平台 / 系统）。举证要回答的问题是「谁先说的、什么时候说的」——"
            + "按录入时刻排会让昨天补录的消息排到今天发生的授权之后，顺序与事实相反。");
  }

  /** 时间线单独分页读（页面刷新、翻页用）。真分页：LIMIT/OFFSET + 独立 COUNT。 */
  public AftersalesTimelinePage timelinePage(UUID caseId, AftersalesTimelineQuery query) {
    int page = Math.max(0, query.page() == null ? 0 : query.page());
    int size =
        Math.min(MAX_TIMELINE_PAGE_SIZE, Math.max(1, query.size() == null ? 50 : query.size()));
    OffsetDateTime at = OffsetDateTime.now(ZoneOffset.UTC);

    Record caseRow =
        sql.fetchOne(
            "select case_no from aftersales_case where tenant_id = ?::uuid and id = ?::uuid",
            currentTenant(),
            caseId);
    if (caseRow == null) {
      // ⚠️ **必须显式判空**：fetchOne 找不到时返回 null，下一行直接 .get(...) 会抛 NPE。
      //   而 NPE 会冒到 HTTP 层变成 500，调用方看到的是「服务器坏了」而不是
      //   「这张单不存在或不属于你」—— 跨租户与不存在两种情况应当给出**同一条**信息。
      throw new IllegalArgumentException("售后单不存在或不属于当前租户：" + caseId);
    }
    String caseNo = caseRow.get(0, String.class);

    Record counts =
        sql.fetchOne(
            "select count(*) as total,"
                + " count(*) filter (where actor_side = 'BUYER') as buyer_count,"
                + " count(*) filter (where actor_side = 'US') as our_count"
                + " from (" + timelineUnion() + ") e",
            // ⚠️ timelineUnion() 里的 CTE 带**两个**占位符（tid / cid），
            //   漏传会让 jOOQ 抛 DataIntegrityViolation —— 而 HTTP 层把约束类异常映射成 409，
            //   一个 GET 返回 409 会让人以为「数据冲突」，与根因毫无关系。
            currentTenant(),
            caseId);
    List<AftersalesTimelineEvent> events =
        sql.fetch(
                "select e.* from (" + timelineUnion() + ") e"
                    + " order by occurred_at asc, kind asc, reference asc nulls last, summary asc,"
                    + " id asc nulls last limit ? offset ?",
                currentTenant(),
                caseId,
                size,
                (long) page * size)
            .map(r -> timelineEvent(r));

    return new AftersalesTimelinePage(
        caseId,
        caseNo,
        events,
        page,
        size,
        counts.get("total", Long.class),
        counts.get("buyer_count", Long.class),
        counts.get("our_count", Long.class),
        at,
        "时间线 = 十个来源 UNION ALL，按事件**发生时刻**排序，每条带说话方"
            + "（买家 / 我方 / 平台 / 系统）。刻意不是按录入库的时刻排序："
            + "补录的历史消息必须落在它真实发生的位置上，否则时间线与事实相反。");
  }

  // ================================================================ 登记子资源

  /** 登记一条商品行。数量累计、订单行归属与金额口径由 DB 触发器复核（V157）。 */
  public AftersalesCaseLine addLine(UUID caseId, AftersalesCaseLineCreate request, UUID actorId) {
    if (request.orderLineId() == null) {
      throw new IllegalArgumentException("必须指定订单行 orderLineId");
    }
    BigDecimal quantity = requirePositive(request.quantity(), "涉及数量");
    AftersalesCaseRowInfo caseInfo = requireOpenCase(caseId);
    boolean moneyType =
        caseInfo.caseType() == AftersalesCaseType.RETURN_REFUND
            || caseInfo.caseType() == AftersalesCaseType.REFUND_ONLY;
    BigDecimal amount = normalize(request.requestedAmount());
    if (!moneyType && amount != null) {
      throw new IllegalArgumentException(
          "类型 " + caseInfo.caseType().name() + " 的商品行不带金额：换货 / 补发 / 维修行是"
              + "「同意办几件」不是一个金额，硬塞一个数字会让财务侧对不上账");
    }
    String currency = amount == null ? null : requireCurrency(request.currency());
    if (amount != null && !currency.equals(caseInfo.currency())) {
      throw new IllegalArgumentException(
          "行金额币种 " + currency + " 与售后单币种 " + caseInfo.currency() + " 不一致");
    }
    String note = blankToEmpty(request.buyerNote());
    if (note.length() > 1000) {
      throw new IllegalArgumentException("商品行备注不能超过 1000 字符");
    }

    UUID id = UUID.randomUUID();
    sql.execute(
        "insert into aftersales_case_line(id,tenant_id,case_id,order_line_id,quantity,"
            + " requested_amount,currency,buyer_note,created_at)"
            + " values (?,?,?,?,?,?,?,?,now())",
        id,
        currentTenant(),
        caseId,
        request.orderLineId(),
        quantity,
        amount,
        currency,
        note);

    return sql.fetchOne(
            "select l.id, l.order_line_id, ol.line_no, ol.sku_code, ol.sku_name, ol.spec_snapshot,"
                + " l.quantity, l.requested_amount, l.currency, l.buyer_note, l.created_at"
                + " from aftersales_case_line l join sales_order_line ol"
                + "   on ol.tenant_id = l.tenant_id and ol.id = l.order_line_id"
                + " where l.tenant_id = ?::uuid and l.id = ?::uuid",
            currentTenant(),
            id)
        .map(r -> caseLine(r));
  }

  /** 登记一条沟通记录。 */
  public AftersalesMessage addMessage(UUID caseId, AftersalesMessageCreate request) {
    AftersalesMessageDirection direction =
        parseEnum(AftersalesMessageDirection.class, request.direction(), "沟通方向");
    AftersalesMessageChannel channel =
        parseEnum(AftersalesMessageChannel.class, request.channel(), "沟通渠道");
    if (request.messageBody() == null || request.messageBody().isBlank()) {
      throw new IllegalArgumentException(
          "沟通正文不能为空：一条没有内容的对话记录在时间线上等于噪声，"
              + "而噪声会让真正那句话被埋掉");
    }
    if (request.messageBody().length() > 4000) {
      throw new IllegalArgumentException("沟通正文不能超过 4000 字符");
    }
    AftersalesDetailSource source =
        request.messageSource() == null
            ? AftersalesDetailSource.TENANT_MAINTAINED
            : parseEnum(AftersalesDetailSource.class, request.messageSource(), "来源");
    String sourceRef = blankToEmpty(request.sourceReference());
    if (source == AftersalesDetailSource.MODULE_2_PLATFORM_MESSAGE && sourceRef.isBlank()) {
      throw new IllegalArgumentException(
          "来源为 MODULE_2_PLATFORM_MESSAGE 时必须给出 sourceReference（哪份来源的哪一段）");
    }
    OffsetDateTime sentAt =
        request.sentAt() == null ? OffsetDateTime.now(ZoneOffset.UTC) : request.sentAt();
    OffsetDateTime capturedAt =
        request.capturedAt() == null ? OffsetDateTime.now(ZoneOffset.UTC) : request.capturedAt();
    // 我们发出的消息必须有作者（DB CHECK 也钉了这一条，这里先给人话错误）。
    UUID authorId = null;
    if (direction == AftersalesMessageDirection.US_TO_BUYER) {
      authorId = currentActor();
    }

    requireOpenCase(caseId);
    UUID id = UUID.randomUUID();
    sql.execute(
        "insert into aftersales_message(id,tenant_id,case_id,direction,channel,message_body,"
            + " external_message_id,message_source,source_reference,sent_at,captured_at,author_id,"
            + " created_at) values (?,?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz,?,now())",
        id,
        currentTenant(),
        caseId,
        direction.name(),
        channel.name(),
        request.messageBody().trim(),
        blankToEmpty(request.externalMessageId()),
        source.name(),
        sourceRef,
        sentAt,
        capturedAt,
        authorId);

    return sql.fetchOne(
            "select id, direction, channel, message_body, external_message_id, message_source,"
                + " source_reference, sent_at, captured_at, author_id"
                + " from aftersales_message where tenant_id = ?::uuid and id = ?::uuid",
            currentTenant(),
            id)
        .map(r -> message(r));
  }

  /** 登记一份附件 / 凭证。 */
  public AftersalesAttachment addAttachment(
      UUID caseId, AftersalesAttachmentCreate request, UUID actorId) {
    AftersalesEvidenceKind kind =
        parseEnum(AftersalesEvidenceKind.class, request.evidenceKind(), "凭证类别");
    AftersalesEvidenceSide side =
        parseEnum(AftersalesEvidenceSide.class, request.attachedBySide(), "凭证提供方");
    if (request.fileName() == null || request.fileName().isBlank()) {
      throw new IllegalArgumentException("凭证文件名不能为空");
    }
    if (request.storageRef() == null || request.storageRef().isBlank()) {
      throw new IllegalArgumentException(
          "必须给出对象存储定位符 storageRef："
              + "本服务不托管文件内容，只登记引用（存 base64 会把详情响应顶到几十 MB）");
    }
    if (request.storageRef().length() > 512) {
      throw new IllegalArgumentException("对象存储定位符不能超过 512 字符");
    }
    if (request.fileName().length() > 255) {
      throw new IllegalArgumentException("凭证文件名不能超过 255 字符");
    }
    Long size = request.fileSizeBytes();
    if (size != null && size < 0) {
      throw new IllegalArgumentException("文件大小不能为负");
    }
    AftersalesDetailSource source =
        request.evidenceSource() == null
            ? AftersalesDetailSource.TENANT_MAINTAINED
            : parseEnum(AftersalesDetailSource.class, request.evidenceSource(), "来源");
    String sourceRef = blankToEmpty(request.sourceReference());
    if (source == AftersalesDetailSource.MODULE_2_PLATFORM_MESSAGE && sourceRef.isBlank()) {
      throw new IllegalArgumentException(
          "来源为 MODULE_2_PLATFORM_MESSAGE 时必须给出 sourceReference（哪份来源的哪一段）");
    }
    String description = blankToEmpty(request.description());
    if (description.length() > 1000) {
      throw new IllegalArgumentException("凭证说明不能超过 1000 字符");
    }

    requireOpenCase(caseId);
    UUID id = UUID.randomUUID();
    sql.execute(
        "insert into aftersales_attachment(id,tenant_id,case_id,evidence_kind,file_name,content_type,"
            + " storage_ref,file_size_bytes,description,attached_by_side,evidence_source,"
            + " source_reference,captured_at,uploaded_by,created_at)"
            + " values (?,?,?,?,?,?,?,?,?,?,?,?,?::timestamptz,?,now())",
        id,
        currentTenant(),
        caseId,
        kind.name(),
        request.fileName().trim(),
        blankToEmpty(request.contentType()),
        request.storageRef().trim(),
        size,
        description,
        side.name(),
        source.name(),
        sourceRef,
        request.capturedAt() == null
            ? OffsetDateTime.now(ZoneOffset.UTC)
            : request.capturedAt(),
        actorId);

    return sql.fetchOne(
            "select id, evidence_kind, attached_by_side, file_name, content_type, storage_ref,"
                + " file_size_bytes, description, evidence_source, source_reference, captured_at,"
                + " uploaded_by from aftersales_attachment"
                + " where tenant_id = ?::uuid and id = ?::uuid",
            currentTenant(),
            id)
        .map(r -> attachment(r));
  }

  /**
   * 详情页追加单条备注（{@code note_source = 'TENANT_SINGLE'}）。
   *
   * <p>⚠️ V156 的表定义里**已经**留了 TENANT_SINGLE，但 11.1.1.1 只写 TENANT_BATCH（它的入口是批量）
   * ⇒ 这个槽位在本模块交付前**没有任何写入者**。详情页的「加备注」正是它的写入者。
   * 不新增枚举值：V156 是既成事实，本模块是它的读者，读者不该改写者的词表。
   */
  public AftersalesCaseNote addNote(UUID caseId, AftersalesNoteCreate request) {
    if (request.noteBody() == null || request.noteBody().isBlank()) {
      throw new IllegalArgumentException(
          "备注正文不能为空：「点了备注但什么都没写」不该被记成一次成功备注");
    }
    if (request.noteBody().length() > 2000) {
      throw new IllegalArgumentException("备注正文不能超过 2000 字符，本次 " + request.noteBody().length() + " 字符");
    }
    requireOpenCase(caseId);
    UUID authorId = request.authorId() != null && !request.authorId().isBlank()
        ? UUID.fromString(request.authorId().trim())
        : currentActor();
    UUID id = UUID.randomUUID();
    sql.execute(
        "insert into aftersales_case_note(id,tenant_id,case_id,note_body,note_source,batch_id,"
            + " author_id,created_at) values (?,?,?,?,'TENANT_SINGLE',null,?,now())",
        id,
        currentTenant(),
        caseId,
        request.noteBody().trim(),
        authorId);
    return sql.fetchOne(
            "select id, note_body, note_source, batch_id, author_id, created_at"
                + " from aftersales_case_note where tenant_id = ?::uuid and id = ?::uuid",
            currentTenant(),
            id)
        .map(r -> note(r));
  }

  // ================================================================ 内部：读取

  private AftersalesCaseHeader header(
      Record r, AftersalesCaseStatus status, OffsetDateTime at) {
    AftersalesCaseType type = AftersalesCaseType.valueOf(r.get("case_type", String.class));
    AftersalesReasonCode reason = AftersalesReasonCode.valueOf(r.get("reason_code", String.class));
    AftersalesTimelinessState timely =
        AftersalesTimelinessState.valueOf(r.get("timeliness_state", String.class));
    OffsetDateTime due = r.get("effective_due_at", OffsetDateTime.class);
    Long remaining = r.get("remaining_seconds", Long.class);
    return new AftersalesCaseHeader(
        r.get("id", UUID.class),
        r.get("case_no", String.class),
        type.name(),
        type.label(),
        status.name(),
        status.label(),
        status.terminal(),
        reason.name(),
        reason.label(),
        blankToNull(r.get("reason_note", String.class)),
        r.get("requested_amount", BigDecimal.class),
        r.get("currency", String.class),
        r.get("requested_quantity", BigDecimal.class),
        blankToNull(r.get("buyer_request", String.class)),
        timely.name(),
        timely.label(),
        r.get("reply_due_at", OffsetDateTime.class),
        r.get("resolve_due_at", OffsetDateTime.class),
        due,
        // 已结案或无时限时是 null 而不是 0：「剩余 0 秒」会被读成「现在就超时了」。
        (due == null || status.terminal()) ? null : remaining,
        r.get("received_at", OffsetDateTime.class),
        r.get("resolved_at", OffsetDateTime.class));
  }

  private AftersalesOrderInfo orderInfo(Record r) {
    return new AftersalesOrderInfo(
        r.get("order_id", UUID.class),
        r.get("order_no", String.class),
        r.get("order_source", String.class),
        // ↓ 店铺恒为 null：sales_order 没有 shop_code 列（实查 V99），
        //   卷 2 店铺在 channel 独立库、禁止跨服务读。见 capabilities 的 platformShopCode。
        null,
        blankToNull(r.get("external_order_no", String.class)),
        r.get("order_status", String.class),
        r.get("order_currency", String.class),
        r.get("payment_method", String.class),
        r.get("grand_total_amount", BigDecimal.class),
        r.get("subtotal_amount", BigDecimal.class),
        r.get("shipping_amount", BigDecimal.class),
        r.get("submitted_at", OffsetDateTime.class),
        blankToNull(r.get("customer_code", String.class)),
        blankToNull(r.get("customer_name", String.class)),
        r.get("customer_type", String.class),
        blankToNull(r.get("recipient_name", String.class)),
        blankToNull(r.get("recipient_phone", String.class)),
        blankToNull(r.get("recipient_email", String.class)),
        blankToNull(r.get("country_code", String.class)),
        blankToNull(r.get("province", String.class)),
        blankToNull(r.get("city", String.class)),
        joinAddress(
            blankToNull(r.get("province", String.class)),
            blankToNull(r.get("city", String.class)),
            blankToNull(r.get("address_line1", String.class)),
            blankToNull(r.get("address_line2", String.class))));
  }

  private static String joinAddress(String province, String city, String line1, String line2) {
    StringBuilder sb = new StringBuilder();
    for (String part : Arrays.asList(province, city, line1, line2)) {
      if (part == null || part.isBlank()) continue;
      if (sb.length() > 0) sb.append(' ');
      sb.append(part.trim());
    }
    return sb.length() == 0 ? null : sb.toString();
  }

  private List<AftersalesOrderLine> orderLines(UUID orderId, UUID caseId) {
    // 绑定顺序 = SQL 文本顺序：left join 的 on 条件里先出现 case_id，再是 where 的 tenant / order。
    return sql.fetch(
            "select ol.id, ol.line_no, ol.sku_id, ol.sku_code, ol.sku_name, ol.spec_snapshot,"
                + " ol.quantity, ol.unit_price, ol.line_amount,"
                + " l.id as l_id, l.quantity as l_quantity, l.requested_amount as l_amount"
                + " from sales_order_line ol"
                + " left join aftersales_case_line l"
                + "   on l.tenant_id = ol.tenant_id and l.order_line_id = ol.id and l.case_id = ?::uuid"
                + " where ol.tenant_id = ?::uuid and ol.order_id = ?::uuid"
                + " order by ol.line_no",
            caseId,
            currentTenant(),
            orderId)
        .map(
            r -> {
              BigDecimal lq = r.get("l_quantity", BigDecimal.class);
              return new AftersalesOrderLine(
                  r.get("id", UUID.class),
                  r.get("line_no", Integer.class),
                  r.get("sku_id", UUID.class),
                  r.get("sku_code", String.class),
                  r.get("sku_name", String.class),
                  blankToNull(r.get("spec_snapshot", String.class)),
                  r.get("quantity", BigDecimal.class),
                  r.get("unit_price", BigDecimal.class),
                  r.get("line_amount", BigDecimal.class),
                  r.get("l_id", UUID.class) != null,
                  lq,
                  r.get("l_amount", BigDecimal.class));
            });
  }

  private List<AftersalesCaseLine> caseLines(UUID caseId) {
    return sql.fetch(
            "select l.id, l.order_line_id, ol.line_no, ol.sku_code, ol.sku_name,"
                + " ol.spec_snapshot, l.quantity, l.requested_amount, l.currency, l.buyer_note,"
                + " l.created_at from aftersales_case_line l"
                + " join sales_order_line ol on ol.tenant_id = l.tenant_id and ol.id = l.order_line_id"
                + " where l.tenant_id = ?::uuid and l.case_id = ?::uuid order by ol.line_no",
            currentTenant(),
            caseId)
        .map(r -> caseLine(r));
  }

  private static AftersalesCaseLine caseLine(Record r) {
    return new AftersalesCaseLine(
        r.get("id", UUID.class),
        r.get("order_line_id", UUID.class),
        r.get("line_no", Integer.class),
        r.get("sku_code", String.class),
        r.get("sku_name", String.class),
        blankToNull(r.get("spec_snapshot", String.class)),
        r.get("quantity", BigDecimal.class),
        r.get("requested_amount", BigDecimal.class),
        blankToNull(r.get("currency", String.class)),
        blankToNull(r.get("buyer_note", String.class)),
        r.get("created_at", OffsetDateTime.class));
  }

  private List<AftersalesMessage> messages(UUID caseId) {
    return sql.fetch(
            "select id, direction, channel, message_body, external_message_id, message_source,"
                + " source_reference, sent_at, captured_at, author_id"
                + " from aftersales_message where tenant_id = ?::uuid and case_id = ?::uuid"
                + " order by sent_at, id",
            currentTenant(),
            caseId)
        .map(r -> message(r));
  }

  private static AftersalesMessage message(Record r) {
    AftersalesMessageDirection direction =
        AftersalesMessageDirection.valueOf(r.get("direction", String.class));
    AftersalesMessageChannel channel =
        AftersalesMessageChannel.valueOf(r.get("channel", String.class));
    AftersalesDetailSource source =
        AftersalesDetailSource.valueOf(r.get("message_source", String.class));
    return new AftersalesMessage(
        r.get("id", UUID.class),
        direction.name(),
        direction.label(),
        channel.name(),
        channel.label(),
        r.get("message_body", String.class),
        blankToNull(r.get("external_message_id", String.class)),
        source.name(),
        source.label(),
        blankToNull(r.get("source_reference", String.class)),
        r.get("sent_at", OffsetDateTime.class),
        r.get("captured_at", OffsetDateTime.class),
        r.get("author_id", UUID.class));
  }

  private List<AftersalesAttachment> attachments(UUID caseId) {
    return sql.fetch(
            "select id, evidence_kind, attached_by_side, file_name, content_type, storage_ref,"
                + " file_size_bytes, description, evidence_source, source_reference, captured_at,"
                + " uploaded_by from aftersales_attachment"
                + " where tenant_id = ?::uuid and case_id = ?::uuid"
                + " order by captured_at, evidence_kind, id",
            currentTenant(),
            caseId)
        .map(r -> attachment(r));
  }

  private static AftersalesAttachment attachment(Record r) {
    AftersalesEvidenceKind kind =
        AftersalesEvidenceKind.valueOf(r.get("evidence_kind", String.class));
    AftersalesEvidenceSide side =
        AftersalesEvidenceSide.valueOf(r.get("attached_by_side", String.class));
    AftersalesDetailSource source =
        AftersalesDetailSource.valueOf(r.get("evidence_source", String.class));
    return new AftersalesAttachment(
        r.get("id", UUID.class),
        kind.name(),
        kind.label(),
        side.name(),
        side.label(),
        r.get("file_name", String.class),
        blankToNull(r.get("content_type", String.class)),
        r.get("storage_ref", String.class),
        r.get("file_size_bytes", Long.class),
        blankToNull(r.get("description", String.class)),
        source.name(),
        source.label(),
        blankToNull(r.get("source_reference", String.class)),
        r.get("captured_at", OffsetDateTime.class),
        r.get("uploaded_by", UUID.class));
  }

  private List<AftersalesCaseNote> notes(UUID caseId) {
    return sql.fetch(
            "select id, note_body, note_source, batch_id, author_id, created_at"
                + " from aftersales_case_note where tenant_id = ?::uuid and case_id = ?::uuid"
                + " order by created_at, id",
            currentTenant(),
            caseId)
        .map(r -> note(r));
  }

  private static AftersalesCaseNote note(Record r) {
    return new AftersalesCaseNote(
        r.get("id", UUID.class),
        r.get("note_body", String.class),
        r.get("note_source", String.class),
        r.get("batch_id", UUID.class),
        r.get("author_id", UUID.class),
        r.get("created_at", OffsetDateTime.class));
  }

  private List<AftersalesAuthorization> authorizations(UUID caseId) {
    return sql.fetch(
            "select id, authorization_kind, decision, authorized_amount, currency, reason_note,"
                + " batch_id, decided_by, decided_at from aftersales_authorization"
                + " where tenant_id = ?::uuid and case_id = ?::uuid"
                + " order by decided_at, id",
            currentTenant(),
            caseId)
        .map(
            r -> {
              AftersalesDecision decision =
                  AftersalesDecision.valueOf(r.get("decision", String.class));
              return new AftersalesAuthorization(
                  r.get("id", UUID.class),
                  r.get("authorization_kind", String.class),
                  labelOf(r.get("authorization_kind", String.class)),
                  decision.name(),
                  decision.label(),
                  r.get("authorized_amount", BigDecimal.class),
                  blankToNull(r.get("currency", String.class)),
                  blankToNull(r.get("reason_note", String.class)),
                  r.get("batch_id", UUID.class),
                  r.get("decided_by", UUID.class),
                  r.get("decided_at", OffsetDateTime.class));
            });
  }

  private List<AftersalesOwnerAssignment> ownerAssignments(UUID caseId) {
    return sql.fetch(
            "select id, owner_ref, owner_display_name, owner_role_label, owner_source,"
                + " source_reference, captured_at from aftersales_owner_assignment"
                + " where tenant_id = ?::uuid and case_id = ?::uuid"
                + " order by captured_at, id",
            currentTenant(),
            caseId)
        .map(
            r -> {
              AftersalesOwnerSource source =
                  AftersalesOwnerSource.valueOf(r.get("owner_source", String.class));
              return new AftersalesOwnerAssignment(
                  r.get("id", UUID.class),
                  r.get("owner_ref", String.class),
                  blankToNull(r.get("owner_display_name", String.class)),
                  blankToNull(r.get("owner_role_label", String.class)),
                  source.name(),
                  source.label(),
                  blankToNull(r.get("source_reference", String.class)),
                  r.get("captured_at", OffsetDateTime.class));
            });
  }

  private AftersalesServiceTicket ticket(UUID caseId) {
    Record r =
        sql.fetchOne(
            "select id, ticket_no, ticket_status, priority, reason_note, owner_module, created_by,"
                + " created_at from aftersales_service_ticket"
                + " where tenant_id = ?::uuid and case_id = ?::uuid",
            currentTenant(),
            caseId);
    if (r == null) return null;
    AftersalesTicketStatus ts =
        AftersalesTicketStatus.valueOf(r.get("ticket_status", String.class));
    AftersalesTicketPriority pr =
        AftersalesTicketPriority.valueOf(r.get("priority", String.class));
    return new AftersalesServiceTicket(
        r.get("id", UUID.class),
        r.get("ticket_no", String.class),
        ts.name(),
        ts.label(),
        pr.name(),
        pr.label(),
        blankToNull(r.get("reason_note", String.class)),
        r.get("owner_module", String.class),
        r.get("created_by", UUID.class),
        r.get("created_at", OffsetDateTime.class));
  }

  private AftersalesPlatformStatus platformStatus(
      Record r, AftersalesCaseStatus status, OffsetDateTime at) {
    AftersalesTimelinessState timely =
        AftersalesTimelinessState.valueOf(r.get("timeliness_state", String.class));
    OffsetDateTime due = r.get("effective_due_at", OffsetDateTime.class);
    Long remaining = r.get("remaining_seconds", Long.class);
    return new AftersalesPlatformStatus(
        blankToNull(r.get("platform_case_no", String.class)),
        blankToNull(r.get("platform_case_url", String.class)),
        r.get("platform_received_at", OffsetDateTime.class),
        r.get("reply_due_at", OffsetDateTime.class),
        r.get("resolve_due_at", OffsetDateTime.class),
        due,
        timely.name(),
        timely.label(),
        (due == null || status.terminal()) ? null : remaining,
        // ↓ 恒为 null：平台最终判定属 2 平台同步与 11.4（均未交付）。
        null,
        false,
        "2 平台同步 + 11.4 平台纠纷",
        null,
        false,
        "2 平台与店铺（sales_order 无 shop_code 列，卷 2 店铺在 channel 独立库禁止跨服务读）");
  }

  /**
   * 责任判定：由**真实**事实合成。
   *
   * <p>「认了还是没认」= 最近一次授权决定；没授权过就是「尚未判定」。
   * 环节级归因（批次 / 生产 / 运输 / 仓储）属 11.1.2.2，<b>未交付</b> ⇒ 如实报 false。
   */
  private AftersalesLiability liability(
      AftersalesCaseHeader header,
      List<AftersalesAuthorization> authorizations,
      List<AftersalesOwnerAssignment> owners) {
    AftersalesAuthorization latest =
        authorizations.isEmpty() ? null : authorizations.get(authorizations.size() - 1);
    String decisionCode;
    String decisionLabel;
    String liability;
    if (latest == null) {
      decisionCode = "UNDECIDED";
      decisionLabel = "尚未判定";
      liability = "还没有任何授权记录：责任未定，原因是「"
          + header.reasonLabel()
          + "」。⚠️ 这不等于「判定为无责」—— 无责同样需要一条明确决定。";
    } else {
      decisionCode = latest.decision();
      decisionLabel = latest.decisionLabel();
      liability =
          switch (AftersalesDecision.valueOf(latest.decision())) {
            case APPROVED ->
                "已批准"
                    + (latest.authorizedAmount() == null
                        ? ""
                        : "（" + latest.authorizedAmount().toPlainString() + " " + latest.currency() + "）")
                    + "：我方承认这笔"
                    + (header.caseType().equals("REFUND_ONLY") ? "退款" : "售后") + "成立。";
            case PARTIALLY_APPROVED ->
                "部分批准"
                    + (latest.authorizedAmount() == null
                        ? ""
                        : "（" + latest.authorizedAmount().toPlainString() + " " + latest.currency() + "）")
                    + "：只认一部分，超出部分不予承担。";
            case REJECTED -> "已拒绝：我方不承担本次售后责任" + noteText(latest.reasonNote()) + "。";
          };
    }
    AftersalesOwnerAssignment current =
        owners.isEmpty() ? null : owners.get(owners.size() - 1);
    return new AftersalesLiability(
        decisionCode,
        decisionLabel,
        liability,
        latest == null ? null : latest.decidedAt().toString(),
        latest == null ? null : latest.authorizedAmount(),
        latest == null ? null : latest.currency(),
        false,
        "11.1.2.2 商品问题归因",
        "环节级归因（生产 / 运输 / 仓储 / 描述）+ 批次追溯与照片比对属 11.1.2.2，**未交付**。"
            + "本模块**不编**一条归因结论：编出来的归因会在 6.4.2.2 供应商索赔与 11.5.2 质量分析里"
            + "被当成真结论用，而那两处会据此对供应商与流程下手。",
        current == null
            ? "未分配责任人"
            : (current.ownerDisplayName() == null
                ? current.ownerRef()
                : current.ownerDisplayName() + "（" + current.ownerRef() + "）"),
        current == null ? "未分配" : current.ownerSourceLabel());
  }

  private static String noteText(String note) {
    return note == null || note.isBlank() ? "" : "。理由：" + note;
  }

  /**
   * 时间线：十个来源 UNION ALL，**一条 SQL** 并起来，按 {@code occurred_at} 排序。
   *
   * <p>⚠️ 用一个只含两行的 {@code t} CTE 承载 tenant / case 两个绑定值：
   *   十个分支各写一遍 {@code where x.tenant_id = ? and x.case_id = ?} 会需要 20 个占位符，
   *   而占位符顺序错位是 jOOQ **不报错**的一类 bug（值绑到别的 ? 上）。
   *   CTE 之后整条语句只有两个绑定，顺序一眼可见。
   *
   * <p>⚠️ **ORDER BY 必须排到全序**：只按 {@code occurred_at, kind} 排不够 ——
   *   同一事务里追加的 6 条备注共享**同一个** {@code created_at}（PG 的 now() 是事务局部的），
   *   它们之间的先后是不确定的。而时间线是真分页（LIMIT/OFFSET）：排序不全序时，
   *   翻页可能漏掉一条或重复一条 —— 这正是「假分页」最难被发现的那种表现。
   *   补上 {@code reference}（各产物主键，两页内唯一）与 {@code summary}（区分三类聚合事件）
   *   之后，(occurred_at, kind, reference, summary) 对本聚合而言是全序。
   *
   * <p>⚠️ 各分支的列类型必须能统一：{@code reference} 一律是 {@code text}
   *   （uuid 与单号都在同一次读里转成字符串），否则 UNION 会报类型不一致。
   */
  private String timelineUnion() {
    return "with t as (select ?::uuid as tid, ?::uuid as cid)"
        + " select 'CASE_RECEIVED' as kind, 'BUYER' as actor_side, a.received_at as occurred_at,"
        + "   '收到售后申请（' || a.case_no || '）' as summary,"
        + "   nullif(a.buyer_request,'')::text as body, null::text as id, null::text as reference"
        + " from aftersales_case a, t"
        + " where a.tenant_id = t.tid and a.id = t.cid"
        + " union all"
        + " select 'PLATFORM_RECEIVED', 'PLATFORM', a.platform_received_at,"
        + "   '平台受理（' || coalesce(nullif(a.platform_case_no,''),'无单号') || '）',"
        + "   null::text, null::text, null::text"
        + " from aftersales_case a, t"
        + " where a.tenant_id = t.tid and a.id = t.cid and a.platform_received_at is not null"
        + " union all"
        + " select 'OWNER_ASSIGNED', 'SYSTEM', oa.captured_at,"
        + "   coalesce(nullif(oa.owner_display_name,''), oa.owner_ref) || ' 被指派为责任人',"
        + "   nullif(oa.owner_role_label,'')::text, null::text, oa.owner_ref"
        + " from aftersales_owner_assignment oa, t"
        + " where oa.tenant_id = t.tid and oa.case_id = t.cid"
        + " union all"
        + " select 'NOTE_ADDED', 'US', n.created_at, '追加备注', n.note_body,"
        + "   n.id::text, n.id::text"
        + " from aftersales_case_note n, t"
        + " where n.tenant_id = t.tid and n.case_id = t.cid"
        + " union all"
        + " select 'AUTHORIZED', 'US', au.decided_at, '授权决定：' || au.decision,"
        + "   nullif(au.reason_note,'')::text, au.id::text, au.id::text"
        + " from aftersales_authorization au, t"
        + " where au.tenant_id = t.tid and au.case_id = t.cid"
        + " union all"
        + " select 'MESSAGE',"
        + "   case when m.direction = 'BUYER_TO_US' then 'BUYER' else 'US' end, m.sent_at,"
        + "   case when m.direction = 'BUYER_TO_US' then '买家说' else '我方回复' end,"
        + "   m.message_body, m.id::text, m.id::text"
        + " from aftersales_message m, t"
        + " where m.tenant_id = t.tid and m.case_id = t.cid"
        + " union all"
        + " select 'ATTACHMENT',"
        + "   case when a.attached_by_side = 'BUYER' then 'BUYER' else 'US' end, a.captured_at,"
        + "   '凭证：' || a.file_name, nullif(a.description,'')::text, a.id::text, a.id::text"
        + " from aftersales_attachment a, t"
        + " where a.tenant_id = t.tid and a.case_id = t.cid"
        + " union all"
        + " select 'LINE_ADDED', 'US', l.created_at,"
        + "   '登记商品行：' || ol.sku_code, nullif(l.buyer_note,'')::text, l.id::text, l.id::text"
        + " from aftersales_case_line l"
        + " join sales_order_line ol on ol.tenant_id = l.tenant_id and ol.id = l.order_line_id,"
        + " t"
        + " where l.tenant_id = t.tid and l.case_id = t.cid"
        + " union all"
        + " select 'TICKET_CREATED', 'US', tk.created_at, '转工单：' || tk.ticket_no,"
        + "   nullif(tk.reason_note,'')::text, tk.id::text, tk.ticket_no"
        + " from aftersales_service_ticket tk, t"
        + " where tk.tenant_id = t.tid and tk.case_id = t.cid"
        + " union all"
        + " select 'CASE_RESOLVED', 'SYSTEM', a.resolved_at,"
        + "   '售后单结案（' || a.status || '）', null::text, null::text, null::text"
        + " from aftersales_case a, t"
        + " where a.tenant_id = t.tid and a.id = t.cid and a.resolved_at is not null";
  }

  private List<AftersalesTimelineEvent> timeline(UUID caseId) {
    return sql.fetch(
            "select e.* from (" + timelineUnion() + ") e"
                + " order by occurred_at asc, kind asc, reference asc nulls last, summary asc,"
                + " id asc nulls last",
            currentTenant(),
            caseId)
        .map(r -> timelineEvent(r));
  }

  private static AftersalesTimelineEvent timelineEvent(Record r) {
    AftersalesTimelineKind kind =
        AftersalesTimelineKind.valueOf(r.get("kind", String.class));
    AftersalesTimelineSide side =
        AftersalesTimelineSide.valueOf(r.get("actor_side", String.class));
    return new AftersalesTimelineEvent(
        r.get("id", UUID.class),
        kind.name(),
        kind.label(),
        side.name(),
        side.label(),
        r.get("occurred_at", OffsetDateTime.class),
        r.get("summary", String.class),
        blankToNull(r.get("body", String.class)),
        blankToNull(r.get("reference", String.class)));
  }

  // ================================================================ 内部：校验与工具

  private record AftersalesCaseRowInfo(AftersalesCaseType caseType, String currency) {}

  /**
   * 取一张**未结案**的单供追加类写入使用。
   *
   * <p>结案后不再收新证据：终态的语义是「这张单办完了」。纠纷举证要补的材料属 11.4 的地盘，
   * 回填进已结案的单会让「结案时到底有什么证据」永远没有答案。
   */
  private AftersalesCaseRowInfo requireOpenCase(UUID caseId) {
    Record r =
        sql.fetchOne(
            "select case_type, status, currency from aftersales_case"
                + " where tenant_id = ?::uuid and id = ?::uuid",
            currentTenant(),
            caseId);
    if (r == null) {
      throw new IllegalArgumentException("售后单不存在或不属于当前租户：" + caseId);
    }
    AftersalesCaseStatus status = AftersalesCaseStatus.valueOf(r.get("status", String.class));
    if (status.terminal()) {
      throw new IllegalArgumentException(
          "售后单已结案（状态 " + status.name() + "），不能再追加内容："
              + "结案的语义是「这张单办完了」，事后往里塞的东西会让"
              + "「结案时到底有什么证据」永远没有答案");
    }
    return new AftersalesCaseRowInfo(
        AftersalesCaseType.valueOf(r.get("case_type", String.class)),
        r.get("currency", String.class));
  }

  /** 当前租户。来自 {@code app.current_tenant}（由 AccessService.run 在事务内绑定），不是任何请求头。 */
  private UUID currentTenant() {
    return UUID.fromString(
        sql.fetchOne("select current_setting('app.current_tenant')").get(0, String.class));
  }

  /**
   * 当前操作人。
   *
   * <p>与 11.1.1.1 同一套：请求体**不接受**作者 ID 冒充（{@link #addNote} 里显式带
   * {@code authorId} 是为了兼容批量导入场景，但为空时一律回落到已认证身份）。
   */
  private UUID currentActor() {
    // ⚠️ 设置名是 `app.actor`（AccessService.run 绑定的已认证用户 ID），不是请求头也不是请求体。
    //   `set_config(..., true)` 是**事务局部**的 ⇒ 事务结束即失效，不会漏给下一个租户。
    return UUID.fromString(sql.fetchOne("select current_setting('app.actor')").get(0, String.class));
  }

  private static BigDecimal requirePositive(BigDecimal value, String label) {
    if (value == null || value.signum() <= 0) {
      throw new IllegalArgumentException(label + "必须大于 0");
    }
    return value;
  }

  /** 金额归一到 4 位小数（DB 是 numeric(19,4)）。禁浮点，全程 BigDecimal。 */
  private static BigDecimal normalize(BigDecimal value) {
    return value == null ? null : value.setScale(4, RoundingMode.HALF_UP);
  }

  private static String labelOf(String code) {
    if (code == null) return null;
    for (var kind : AftersalesCaseModels.AftersalesAuthorizationKind.values()) {
      if (kind.name().equals(code)) return kind.label();
    }
    return code;
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

  private static String blankToNull(String v) {
    return v == null || v.isBlank() ? null : v;
  }

  private static String blankToEmpty(String v) {
    return v == null ? "" : v;
  }
}
