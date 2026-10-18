package com.starcon.trade;

import static com.starcon.trade.RmaAuthorizationModels.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Service;

/**
 * 11.2.1.1 退货授权单。
 *
 * <p><b>本类是 V161 三张新表的写入所有者</b>（rma_authorization / rma_authorization_line /
 * rma_decision_event），对 V156 的 aftersales_case 簇、V157 的 aftersales_case_line、
 * V99 的 sales_order_line 与 V119 的 order_fulfillment_line_progress <b>只读</b>。
 * 刻意不加外键、不 ALTER 既有表 ⇒ 跨车道 DDL 回归面 = 0。
 *
 * <p><b>六条硬纪律</b>：
 *
 * <ol>
 *   <li><b>每一条查询都显式带 {@code tenant_id} 谓词</b>，不依赖 RLS 当唯一防线。
 *       RLS 是最后一道（FORCE 到 owner），查询本身也带条件，两层独立生效。
 *       授权单带着<b>退货地址</b>（收货人姓名 / 电话）与<b>买家回寄运单号</b>，
 *       且审批入口按 id 直达 —— 漏掉租户谓词就是跨租户泄露。</li>
 *   <li><b>可退量在服务端算，不信前端传来的数</b>（{@link #returnableQuantities}）。
 *       三个来源分别是订购量（V99）、已发量（V119）、其他在途授权累计（本模块）。
 *       审批时 {@link #decide} <b>重新算一遍</b>而不是复用页面传来的数 ——
 *       页面打开到点「授权」之间可能已经过了几十分钟、别的单子又批走了一部分。</li>
 *   <li><b>「授权」不等于「已收到」</b>。本模块<b>没有</b> RECEIVED 状态：
 *       登记退回实物属 11.2.2.1（未交付）。响应里的 {@code received} 恒为 false
 *       且带归属模块号，而不是给个 null 让界面自己猜。</li>
 *   <li><b>未交付的下游一律 delivered=false + 归属模块号</b>，<b>不建影子表</b>。
 *       11.2.1.2 / 11.2.2.x / 11.2.3.x 当前全部未交付。</li>
 *   <li><b>时间绑定</b>：时间一律 {@code ?::timestamptz} + {@link OffsetDateTime}。
 *       {@code Timestamp.valueOf(LocalDateTime)} 会静默偏 −28800s，
 *       而寄回时限算错一天就是「提前一天自动关单」。</li>
 *   <li><b>状态流转只有一个入口</b>（{@link #transition}）：每次改状态都过版本校验，
 *       都写留痕。绕过它直接 UPDATE 的路径不存在（服务层不暴露裸改状态的端点）。</li>
 * </ol>
 */
@Service
public class RmaAuthorizationService {

  /** 列表单页上限。 */
  private static final int MAX_PAGE_SIZE = 200;

  /** 临期阈值 24 小时（与 V156 aftersales_timeliness_state 同一口径）。 */
  private static final long DUE_SOON_SECONDS = 24L * 3600L;

  /**
   * 单次超时扫描上限。
   *
   * <p>不是「怕慢」而是<b>怕静默漏扫</b>：一次扫 10 万行会让事务长到把 RLS 租户设置一直挂着，
   * 而没人愿意手工翻页把剩下的补上。
   */
  private static final int EXPIRE_SCAN_LIMIT = 500;

  private final DSLContext sql;

  public RmaAuthorizationService(DSLContext sql) {
    this.sql = sql;
  }

  // ================================================================ 能力声明

  /**
   * 能力声明：职责句的五个区块逐块报可用性，下游逐个报 Delivered=false 与归属模块号。
   *
   * <p>让界面能说清「为什么这张单没有退款金额」「为什么回寄轨迹是空的」，
   * 而不是让它们显示成空白让人以为「这些能力没做」。
   */
  public RmaCapabilities capabilities() {
    return new RmaCapabilities(
        List.of(
            "退货申请 aftersales_case + aftersales_case_line（V156 / V157，同库只读）",
            "可退数量基数 ① 订购量 sales_order_line.quantity（V99）"
                + "② 已发量 order_fulfillment_line_progress.shipped_qty（V119）"
                + "③ 其他在途授权累计（本模块实时求和）",
            "退货地址 本表 return_address_* 列（来源可枚举，含 3.6.3 与平台 RMA 两个未打通的口子）",
            "买家回寄物流 本表 return_carrier / return_tracking_no / return_shipped_at（三件套同进同出）",
            "目标处理方式 本表 target_disposition（**意图**，非处置结论）",
            "审批留痕 rma_decision_event（追加式，DB 触发器按状态变更自动追加）",
            "SKU 与单价 sales_order_line 的下单快照（实时读，不复制）"),
        List.of(
            section("returnableQuantity", "可退数量校验", true, null, null,
                "rma_authorization_line 的四个闸门基数快照 + 服务层实时重算"),
            section("returnAddress", "退货地址", true, null,
                "蓝图输入句写「3.6.3 或平台 RMA 地址」。**实查集成头 f760ba37**：3.6.3.1 已交付但迁移在 "
                    + "**catalog 服务**（V31__party_addresses_contacts.sql），是另一个数据库 ⇒ 按 "
                    + "AGENTS.md 模块边界纪律**禁止跨服务 SQL / 视图 / FK**，本服务读不到它。"
                    + "卷 2 平台地址同理（channel 独立库，本服务无任何平台地址表）。"
                    + "⇒ 照抄 V150 signal_source / V156 owner_source / V157 message_source 的既有解法："
                    + "落成租户可维护的真实列，每行强制 return_address_source 枚举与 source_reference；"
                    + "正式接口到位后同步器按**同一形状**写入并把来源改成 MODULE_*，"
                    + "**表结构与查询都不用改**。",
                "rma_authorization.return_address_*（V161，租户可维护）"),
            section("buyerReturnLogistics", "买家回寄物流", true, null,
                "承运商 / 运单号 / 寄出时刻可登记，**但轨迹跟踪不可得**：11.2.1.2 退货指令下发"
                    + "（退货指引、标签文件、回寄物流轨迹跟踪）**未交付**，轨迹是 10 物流的真实事。"
                    + "本模块**刻意不建包裹 / 轨迹表**：一张本服务自造的 rma_parcel 会被当成已对接。",
                "rma_authorization.return_carrier / return_tracking_no / return_shipped_at（V161）"),
            section("targetDisposition", "目标处理方式", true, null, null,
                "rma_authorization.target_disposition（六类，**意图**）"),
            section("partialReason", "部分退货理由", true, null, null,
                "rma_authorization.decision_reason（DB CHECK 钉死：部分授权与拒绝必填）"),
            section("receivedGoods", "退货实收", false, "11.2.2.1 退货入库",
                "**蓝图加粗句「授权不等于已收到」的直接落点**：登记退回实物（扫码核对 SKU 与序列号、"
                    + "清点数量、拍照留证、判定外包装与商品状态）属 11.2.2.1，**未交付**。"
                    + "⇒ 本模块**没有 RECEIVED 状态**，响应里的 received 恒为 false 并带归属模块号。"
                    + "编一个「已收到」状态会让下游以为货已到手并开始做质检与入库分录 —— "
                    + "而退回商品**默认进待检**，直接当已收货处理会把残次品当新品卖出去。",
                null),
            section("receivedQuantity", "已退数量", false, "11.2.2.2 实收分配",
                "「订单行已退数量更新」属 11.2.2.2，**未交付**。本模块**不建影子表**："
                    + "一张本服务自造的「已退数量」会被当成已对接，而重复记已退数量会让"
                    + "「这个 SKU 一共退了几件」出现两个答案（实收按收货事件去重分配是它的硬规则）。"
                    + "⇒ 可退量里的 receivedQty 恒为 null 并带 source 说明，**不填 0 冒充「没退过」**。",
                null),
            section("rmaInstruction", "退货指令与标签", false, "11.2.1.2 退货指令下发",
                "向买家发出退货指引、生成退货标签、跟踪回寄物流**均未交付**。"
                    + "本模块只**产出**授权单，不假装自己能发指引。",
                null),
            section("disposition", "退货处置结论", false, "11.2.3.2 转可售/残次/报废",
                "target_disposition 是**意图**；真正的处置决定（转可售 / 转残次 / 报废）"
                    + "属 11.2.3.2，**未交付**，且**必须等实收到货**（11.2.2.1）。"
                    + "此刻任何「已处置」都是假的。",
                null)),
        relatedModules(),
        actionEntries(null),
        List.of(
            new RmaBoundary("登记退回实物与实收分配", "11.2.2.1 / 11.2.2.2",
                "本模块的授权单只到「货可以寄回」为止。实收与已退数量更新是两个模块的写入所有权，"
                    + "本模块既不代写也不建影子表。"),
            new RmaBoundary("质检与最终处置", "11.2.3.1 / 11.2.3.2",
                "退回商品默认进待检是可售性的硬规则；本模块的 target_disposition 只是审批时的意图。"),
            new RmaBoundary("退款金额与退款执行", "11.3.1.1 / 12 财务",
                "授权行回显的 indicativeRefundAmount = 订单行单价 × 本行授权量，"
                    + "是**指示性**金额，不是可退余额校验的结果，更不是实际退款额。"
                    + "可退余额校验必须在服务端做（11.3.1.1），退款执行归 12 财务。"),
            new RmaBoundary("改写售后单状态", "11.1.1.3 售后受理",
                "aftersales_case.status 的流转（RECEIVED → AUTHORIZING → …）属 11.1.1.3，**未交付**，"
                    + "而 aftersales_case 是 11.1.1.1 的写入所有者。本模块**只读**它，"
                    + "不代改它的状态 —— 代改会让「谁受理了这张单」这个问题出现两个答案。")),
        "一个状态列到底：DRAFT →（授权 / 部分授权 / 拒绝 / 撤销）+ 在途两态 → EXPIRED。"
            + "**没有 RECEIVED**：登记实收属 11.2.2.1（未交付）。"
            + "只有 AUTHORIZED 与 PARTIALLY_AUTHORIZED 占用可退量 —— 拒绝 / 撤销 / 超时关闭的单"
            + "不会有货回来（超时正是「没寄回」），量要还给订单行。",
        "可退上限 = min(订购量, 已发量) − 其他在途授权单的累计授权量。三个来源全部**服务端实时查**："
            + "订购量取自 V99 sales_order_line.quantity，已发量取自 V119 "
            + "order_fulfillment_line_progress.shipped_qty，在途累计由本模块求和。"
            + "**没有履约进度投影行时已发量按 0 处理**（宁可拦住也不能放过：没被记录发出过的货不能退）。"
            + "同一套算法在 DB 触发器里**再算一遍**（guard_rma_authorization_line），"
            + "绕过服务层直连 SQL 也绕不过。",
        "**授权不等于已收到**：授权后货可能在路上，也可能根本没寄回。"
            + "本模块因此有超时未寄自动关闭（POST /rma-authorizations/expire-overdue，"
            + "以及列表里逐行算出的 timelinessState），但**没有**「已收到」状态 —— "
            + "登记实收属 11.2.2.1，未交付。响应里 received 恒为 false 并带 ownerModule。");
  }

  private static RmaSectionCapability section(
      String section, String label, boolean available, String ownerModule, String reason, String landsIn) {
    return new RmaSectionCapability(section, label, available, ownerModule, reason, landsIn);
  }

  /** 关联模块可用性。11.2.1.2 / 11.2.2.x / 11.2.3.x 当前**全部未交付** ⇒ 逐条报 false 与归属。 */
  private List<RmaRelatedModule> relatedModules() {
    return List.of(
        new RmaRelatedModule("RMA_INSTRUCTION", "退货指令下发", false, "11.2.1.2",
            "向买家发出退货指引（地址 / 要求 / 时限）、生成退货标签、跟踪买家回寄物流**未交付**。"
                + "本模块只**产出**授权单（地址与时限都在上面），不假装自己能发指引。"
                + "多数平台退货由买家寄回，本系统只负责指引与跟踪，不控制实物。", null),
        new RmaRelatedModule("RECEIPT", "退货实收登记", false, "11.2.2.1",
            "扫码核对 SKU 与序列号、清点数量、拍照留证、判定外包装与商品状态**未交付**。"
                + "登记实收必须等它落地，否则会把从没寄回的货按已收货处理。", null),
        new RmaRelatedModule("RECEIPT_ALLOCATION", "实收分配与已退数量", false, "11.2.2.2",
            "把实收分配到授权行与原订单行、处理超收 / 少收 / 超期退回、**更新订单行已退数量**"
                + "均**未交付**。本模块因此把「已退数量」如实报成不可得，而不是自己记一份。", null),
        new RmaRelatedModule("INSPECTION", "退货质检与判定", false, "11.2.3.1 / 11.2.3.2",
            "质检结论（可再销售 / 残次 / 报废）与库存状态流转**未交付**，"
                + "且**必须等实收到货**。可再销售判定要保守：包装破损但商品完好可判可再销售，"
                + "缺少配件则应判残次。", null),
        new RmaRelatedModule("SUPPLIER_CLAIM", "供应商索赔", false, "11.2.3.3",
            "向供应商发起索赔（关联批次与责任判定、索赔金额、协商记录）**未交付**。"
                + "target_disposition = SUPPLIER_CLAIM 只是**意图**，索赔单不在本模块。", null),
        new RmaRelatedModule("REFUND_REQUEST", "退款申请", false, "11.3.1.1",
            "退款申请（选方式、可退余额校验、审批）**未交付**，退款执行归 12 财务。"
                + "授权行回显的指示性金额**不是**可退余额校验的结果。", null));
  }

  /** 操作入口。{@code status} 为 null 时给「当前这张单处于 DRAFT」的通用集合（建单页用）。 */
  private List<RmaActionEntry> actionEntries(RmaStatus status) {
    boolean draft = status == null || status == RmaStatus.DRAFT;
    boolean inflight = status != null && status.awaitingBuyerShipment();
    String terminal = status != null && status.terminal()
        ? "退货授权单已结案（" + status.label() + "）：行是证据不是草稿，不能再改"
        : null;
    return List.of(
        new RmaActionEntry("CREATE", "登记退货授权单", true, "POST",
            "/trade/rma-authorizations", false, null, null, null, null),
        new RmaActionEntry("RETURNABLE_QUANTITIES", "校验可退数量", true, "GET",
            "/trade/rma-authorizations/returnable-quantities?caseId={uuid}", false, null, null, null, null),
        new RmaActionEntry("UPDATE_DRAFT", "修改草稿要素", draft, "PUT",
            "/trade/rma-authorizations/{id}", true, terminal,
            terminal != null ? terminal : (draft ? null : "只有草稿可改目标处理方式与退货地址"),
            null, null),
        new RmaActionEntry("DECIDE", "审批（授权 / 部分授权 / 拒绝）", draft, "POST",
            "/trade/rma-authorizations/{id}/decide", true, terminal,
            terminal != null ? terminal : (draft ? null : "只有草稿可审批：已审批的单不能二次审批"), null, null),
        new RmaActionEntry("RECORD_RETURN_LOGISTICS", "登记买家回寄物流", inflight, "POST",
            "/trade/rma-authorizations/{id}/return-logistics", true, terminal,
            terminal != null ? terminal : (inflight ? null : "只有已授权 / 部分授权的单需要登记回寄物流"),
            null, null),
        new RmaActionEntry("CANCEL", "撤销授权", inflight, "POST",
            "/trade/rma-authorizations/{id}/cancel", false, terminal,
            terminal != null ? terminal : (inflight ? null : "只有已授权 / 部分授权的单可撤销（货还没回来）"),
            null, null),
        new RmaActionEntry("EXPIRE_OVERDUE", "超时未寄自动关闭", true, "POST",
            "/trade/rma-authorizations/expire-overdue", false, null, null, null, null),
        new RmaActionEntry("RECEIVE", "登记退货实收", false, null, null, false, null, null,
            "11.2.2.1 退货入库",
            "登记退回实物（扫码核对、清点、拍照留证、判定状态）属 11.2.2.1，**未交付**。"
                + "**授权不等于已收到**：货可能在路上，也可能根本没寄回。"),
        new RmaActionEntry("ISSUE_INSTRUCTION", "下发退货指引与标签", false, null, null, false, null, null,
            "11.2.1.2 退货指令下发",
            "退货指引、标签文件与回寄物流轨迹跟踪属 11.2.1.2，**未交付**。"));
  }

  // ================================================================ 可退量校验

  /**
   * 一张售后单的可退量校验（职责句输入：「原订单行可退量校验」）。
   *
   * <p>三个来源全部**实时查**，没有一个是前端传进来的：
   *
   * <ul>
   *   <li><b>订购量</b> = sales_order_line.quantity（V99，9 章车道的表，只读）</li>
   *   <li><b>已发量</b> = order_fulfillment_line_progress.shipped_qty（V119，只读）。
   *       ⚠️ <b>没有投影行时按 0 处理并把 source 如实报成 NO_FULFILLMENT_PROGRESS</b>：
   *       没被记录发出过的货不能退，这是宁可拦住也不能放过的方向
   *       （放行的代价是把从没到过买家手上的货按已收货处理）。</li>
   *   <li><b>其他在途授权累计</b> = 本模块对同一订单行在 AUTHORIZED / PARTIALLY_AUTHORIZED 单上的求和</li>
   * </ul>
   *
   * <p><b>已退数量恒为 null</b>（11.2.2.2 未交付）并带说明，<b>不填 0 冒充「没退过」</b>：
   * 填 0 会让运营以为「这行从来没退过」，从而在重复退货面前失去唯一的提醒。
   */
  public RmaReturnableReport returnableQuantities(UUID caseId) {
    if (caseId == null) {
      throw new IllegalArgumentException("必须指定售后单（caseId）");
    }
    OffsetDateTime at = OffsetDateTime.now(ZoneOffset.UTC);

    Record head =
        sql.fetchOne(
            "select c.id, c.case_no, c.case_type, c.status, c.order_id, c.requested_quantity,"
                + " c.currency, c.requested_amount, o.order_no"
                + " from aftersales_case c"
                + " left join sales_order o on o.tenant_id = c.tenant_id and o.id = c.order_id"
                + " where c.tenant_id = ?::uuid and c.id = ?::uuid",
            currentTenant(), caseId);
    if (head == null) {
      // 跨租户的 id 与不存在的 id 同一条消息：不告诉调用方「这个 ID 存在但不属于你」。
      throw new IllegalArgumentException("售后单不存在或不属于当前租户：" + caseId);
    }

    String caseType = head.get("case_type", String.class);
    String caseStatus = head.get("status", String.class);

    List<RmaReturnableLine> lines =
        sql.fetch(
                "select cl.id as case_line_id, cl.order_line_id, cl.quantity as case_requested_qty,"
                    + " l.sku_code, l.sku_name, l.unit_price, l.quantity as ordered_qty,"
                    + " p.shipped_qty"
                    + " from aftersales_case_line cl"
                    + " join sales_order_line l"
                    + "   on l.tenant_id = cl.tenant_id and l.id = cl.order_line_id"
                    + " left join order_fulfillment_line_progress p"
                    + "   on p.tenant_id = cl.tenant_id and p.order_line_id = cl.order_line_id"
                    + " where cl.tenant_id = ?::uuid and cl.case_id = ?::uuid"
                    + " order by l.line_no, cl.id",
                currentTenant(), caseId)
            .map(r -> returnableLine(r, caseType, caseStatus));

    BigDecimal returnable =
        lines.stream()
            .map(RmaReturnableLine::returnableQty)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

    return new RmaReturnableReport(
        head.get("id", UUID.class),
        head.get("case_no", String.class),
        head.get("order_id", UUID.class),
        head.get("order_no", String.class),
        caseType,
        caseStatus,
        lines,
        head.get("requested_quantity", BigDecimal.class),
        returnable,
        false,
        "11.2.2.2 实收分配",
        "**已退数量不可得**：订单行已退数量的写入所有者是 11.2.2.2 实收分配，**未交付**。"
            + "本模块**不建影子表**（一张自造的「已退数量」会被当成已对接，而重复记已退数量会让"
            + "「这个 SKU 一共退了几件」出现两个答案）。⇒ 本列恒为 null 并标 NOT_DELIVERED，"
            + "**不填 0 冒充「没退过」**。可退量当前按「其他在途授权累计」扣减，"
            + "11.2.2.2 交付后会在同一处叠加实收已退量。",
        at,
        "可退上限 = min(订购量, 已发量) − 其他在途授权累计。三个来源全部服务端实时查。"
            + "**没有履约进度投影行时已发量按 0 处理**（宁可拦住也不能放过）。"
            + "整份共用一个参考时刻 evaluatedAt，翻页时同一行不会在「可退」与「不可退」之间跳。");
  }

  /** 逐行算可退量：三个来源 + 上限 + 挡住它的原因。 */
  private RmaReturnableLine returnableLine(Record r, String caseType, String caseStatus) {
    UUID tenant = currentTenant();
    UUID caseLineId = r.get("case_line_id", UUID.class);
    UUID orderLineId = r.get("order_line_id", UUID.class);

    BigDecimal orderedQty = nz(r.get("ordered_qty", BigDecimal.class));
    BigDecimal shippedQty = r.get("shipped_qty", BigDecimal.class);

    // 其他在途授权累计：同一订单行、状态占用可退量的那些授权单。
    // ⚠️ **刻意不排除「同一张售后单的其它授权单」**：一张售后单可以开多张退货授权单
    //   （第一张被拒后再开一张），它们引用**同一个 case_line_id**，
    //   按 case_line_id 排除会把先批的那张一起排除掉，累计恒为 0 ——
    //   于是「上限已被别人占满」这条约束在真实使用里形同虚设。
    //   草稿的授权量恒为 0，被拒 / 撤销 / 超时的单被状态过滤掉，所以不排除任何单是对的。
    BigDecimal inflight =
        sql.fetchOne(
                "select coalesce(sum(rl.authorized_qty), 0)"
                    + " from rma_authorization_line rl"
                    + " join aftersales_case_line cl"
                    + "   on cl.tenant_id = rl.tenant_id and cl.id = rl.case_line_id"
                    + " join rma_authorization h on h.tenant_id = rl.tenant_id and h.id = rl.rma_id"
                    + " where rl.tenant_id = ?::uuid"
                    + "   and cl.order_line_id = ?::uuid"
                    + "   and h.rma_status in ('AUTHORIZED','PARTIALLY_AUTHORIZED')",
                tenant, orderLineId)
            .get(0, BigDecimal.class);
    // 已退数量不可得（11.2.2.2 未交付）⇒ null，不填 0。见方法的类注释。
    BigDecimal cap = min(orderedQty, nz(shippedQty)).subtract(inflight);
    if (cap.signum() < 0) {
      cap = BigDecimal.ZERO;
    }

    List<String> blocking = new ArrayList<>();
    if (!"RETURN_REFUND".equals(caseType)) {
      blocking.add("只有 RETURN_REFUND 类的售后单才能发起退货授权，当前类型 " + caseType);
    }
    if (caseStatus != null && List.of("RESOLVED", "REJECTED", "CLOSED").contains(caseStatus)) {
      blocking.add("售后单已结案（" + caseStatus + "），不能再发起退货授权");
    }
    if (shippedQty == null) {
      blocking.add("该订单行没有履约进度投影（order_fulfillment_line_progress），已发数量按 0 处理 —— "
          + "没被记录发出过的货不能退");
    } else if (shippedQty.signum() == 0) {
      blocking.add("该订单行已发数量为 0：货还没发出去，不能退");
    }
    if (cap.signum() <= 0 && blocking.isEmpty()) {
      blocking.add("可退上限已被其他在途退货授权单占满（累计已授权 " + inflight.stripTrailingZeros() + "）");
    }

    BigDecimal unitPrice = r.get("unit_price", BigDecimal.class);
    BigDecimal caseRequestedQty = nz(r.get("case_requested_qty", BigDecimal.class));
    // 指示性金额 = 订单行单价 × 本行申请量，**不是**可退余额校验的结果，也不是实际退款额。
    BigDecimal indicative =
        unitPrice == null ? null : unitPrice.multiply(caseRequestedQty).setScale(4, java.math.RoundingMode.HALF_UP);

    return new RmaReturnableLine(
        caseLineId,
        orderLineId,
        r.get("sku_code", String.class),
        r.get("sku_name", String.class),
        caseRequestedQty,
        orderedQty,
        shippedQty,
        shippedQty == null ? "NO_FULFILLMENT_PROGRESS" : "MODULE_9_3_3_1_FULFILLMENT_PROGRESS",
        null,
        "NOT_DELIVERED_11_2_2_2",
        inflight,
        cap,
        indicative,
        null,
        blocking);
  }

  // ================================================================ 列表

  /** 列表：真分页（LIMIT/OFFSET + 独立 COUNT）+ 全量汇总 + 多维筛选 + 排序白名单。 */
  public RmaPage list(RmaListQuery query) {
    int page = Math.max(0, query.page() == null ? 0 : query.page());
    int size = Math.min(MAX_PAGE_SIZE, Math.max(1, query.size() == null ? 50 : query.size()));
    RmaSort sort = RmaSort.parse(query.sort());

    // 整页共用的参考时刻：判定时效、汇总计数、每行 remainingSeconds 全用它。
    OffsetDateTime at = OffsetDateTime.now(ZoneOffset.UTC);

    List<Object> binds = new ArrayList<>();
    List<String> where = conditions(query, binds, at);
    String clause = where.isEmpty() ? "" : " where " + String.join(" and ", where);

    // 左连售后单与订单：单号与平台来源要显示，而这两张表若因故缺失**这张授权单仍要显示**
    // ——「列表里没有这张单」在售后场景里比「单号缺失」严重得多。
    String fromBase =
        " from rma_authorization r"
            + " left join aftersales_case c on c.tenant_id = r.tenant_id and c.id = r.case_id"
            + " left join sales_order o on o.tenant_id = c.tenant_id and o.id = c.order_id";
    String from =
        fromBase
            + " left join lateral ("
            + "   select coalesce(sum(l.authorized_qty), 0) as authorized_qty,"
            + "          coalesce(sum(l.requested_qty), 0) as requested_qty, count(*) as line_count"
            + "     from rma_authorization_line l"
            + "    where l.tenant_id = r.tenant_id and l.rma_id = r.id) w on true";

    long total =
        sql.fetchOne("select count(*)" + fromBase + clause, binds.toArray()).get(0, Long.class);

    // ⚠️ 占位符顺序必须与 SQL **文本出现顺序**一致：
    //   [timelinessCase 里的三个 at] → [from / where 里的 binds] → [limit] → [offset]。
    //   jOOQ 按位置绑定，顺序错了不会报错、只会把值绑到别的 ? 上 —— 那是最难查的一类 bug。
    List<Object> pageBinds = new ArrayList<>();
    pageBinds.add(at);
    pageBinds.add(at);
    pageBinds.add(at);
    pageBinds.addAll(binds);
    pageBinds.add(size);
    pageBinds.add((long) page * size);

    List<RmaRow> rows =
        sql.fetch(
                "select r.*, c.case_no, c.case_type, c.status as case_status, c.order_id, o.order_no,"
                    + " w.authorized_qty, w.requested_qty, w.line_count,"
                    + timelinessCase()
                    + from
                    + clause
                    + " order by "
                    + sort.sql()
                    + " limit ? offset ?",
                pageBinds.toArray())
            .map(r -> row(r, at));

    return new RmaPage(
        rows,
        page,
        size,
        total,
        summary(at, where, binds),
        at,
        "真分页（LIMIT/OFFSET + 独立 COUNT），汇总与排序不受分页影响。",
        "临期阈值 24 小时。整页共用一个参考时刻 evaluatedAt，翻页时同一张单不会在「临期」和「正常」之间跳。"
            + "只有 AUTHORIZED / PARTIALLY_AUTHORIZED 计入倒计时（其余状态 returnedByAt 无意义）。",
        "**没有** received 一列：登记退货实收属 11.2.2.1，**未交付**。"
            + "蓝图加粗句「授权不等于已收到」—— 货可能在路上，也可能根本没寄回，"
            + "所以本模块只有「寄回时限倒计时」与「超时未寄自动关闭」，没有「已收到」。",
        "排序白名单四个键（created_desc / due_asc / status_asc / disposition_asc）；"
            + "其余取值落回 created_desc，不接受任意表达式。");
  }

  /**
   * 寄回时限的两个投影列（状态 + 剩余秒数），SQL 文本形状固定。
   *
   * <p>⚠️ <b>刻意不调用 V156 的 {@code aftersales_timeliness_state}</b>：那个函数判的是
   * <b>售后单</b>的平台回复 / 处理时限（平台什么时候要我们答），而这里要判的是
   * <b>买家什么时候必须把货寄回</b> —— 两个时钟的起点、后果与责任方都不同。
   * 复用一个名字相似、语义不同的函数，会让「逾期未寄」被当成「平台超期」显示给运营。
   *
   * <p>所以这里是一段内联 CASE，口径写在下面这一处；<b>占位符恰好两个</b>，
   * 调用方必须按顺序先绑两个参考时刻，再绑 where 里的值。
   */
  private static String timelinessCase() {
    return " case when r.rma_status not in ('AUTHORIZED','PARTIALLY_AUTHORIZED') then 'CLOSED'"
        + "      when r.return_by_at is null then 'NOT_APPLICABLE'"
        + "      when r.return_by_at < ?::timestamptz then 'OVERDUE'"
        + "      when r.return_by_at <= ?::timestamptz + interval '24 hours' then 'DUE_SOON'"
        + "      else 'ON_TRACK' end as timeliness_state,"
        + " case when r.rma_status in ('AUTHORIZED','PARTIALLY_AUTHORIZED')"
        + "      then extract(epoch from (r.return_by_at - ?::timestamptz))::bigint"
        + "      else null end as remaining_seconds";
  }

  private List<String> conditions(RmaListQuery query, List<Object> binds, OffsetDateTime at) {
    List<String> where = new ArrayList<>();
    where.add("r.tenant_id = ?::uuid");
    binds.add(currentTenant());
    if (isSet(query.status())) {
      where.add("r.rma_status = ?");
      binds.add(query.status().trim().toUpperCase());
    }
    if (isSet(query.decision())) {
      where.add(
          switch (query.decision().trim().toUpperCase()) {
            case "APPROVED" -> "r.rma_status = 'AUTHORIZED'";
            case "PARTIALLY_APPROVED" -> "r.rma_status = 'PARTIALLY_AUTHORIZED'";
            case "REJECTED" -> "r.rma_status = 'REJECTED'";
            case "PENDING" -> "r.rma_status = 'DRAFT'";
            default -> throw new IllegalArgumentException(
                "审批决定只能是 APPROVED / PARTIALLY_APPROVED / REJECTED / PENDING，收到 "
                    + query.decision());
          });
    }
    if (isSet(query.disposition())) {
      where.add("r.target_disposition = ?");
      binds.add(query.disposition().trim().toUpperCase());
    }
    if (query.caseId() != null) {
      where.add("r.case_id = ?::uuid");
      binds.add(query.caseId());
    }
    if (Boolean.TRUE.equals(query.overdueOnly())) {
      where.add("r.rma_status in ('AUTHORIZED','PARTIALLY_AUTHORIZED') and r.return_by_at < ?::timestamptz");
      binds.add(at);
    }
    if (isSet(query.keyword())) {
      // 真实模糊检索：授权单号 / 售后单号 / 订单号三个可读编号都命中。
      where.add("(r.rma_no ilike ? or c.case_no ilike ? or o.order_no ilike ?)");
      String like = "%" + query.keyword().trim() + "%";
      binds.add(like);
      binds.add(like);
      binds.add(like);
    }
    return where;
  }

  private RmaSummary summary(OffsetDateTime at, List<String> where, List<Object> binds) {
    String clause = where.isEmpty() ? "" : " where " + String.join(" and ", where);
    Record row =
        sql.fetchOne(
            "select count(*) as total,"
                + " count(*) filter (where r.rma_status = 'DRAFT') as draft,"
                + " count(*) filter (where r.rma_status = 'AUTHORIZED') as authorized,"
                + " count(*) filter (where r.rma_status = 'PARTIALLY_AUTHORIZED') as partial,"
                + " count(*) filter (where r.rma_status = 'REJECTED') as rejected,"
                + " count(*) filter (where r.rma_status = 'EXPIRED') as expired,"
                + " count(*) filter (where r.rma_status = 'CANCELLED') as cancelled,"
                + " count(*) filter (where r.rma_status in ('AUTHORIZED','PARTIALLY_AUTHORIZED')"
                + "   and r.return_by_at < ?::timestamptz) as overdue"
                + " from rma_authorization r"
                + " left join aftersales_case c on c.tenant_id = r.tenant_id and c.id = r.case_id"
                + " left join sales_order o on o.tenant_id = c.tenant_id and o.id = c.order_id"
                + clause,
            concat(at, binds));
    return new RmaSummary(
        row.get("total", Long.class),
        row.get("draft", Long.class),
        row.get("authorized", Long.class),
        row.get("partial", Long.class),
        row.get("rejected", Long.class),
        row.get("expired", Long.class),
        row.get("cancelled", Long.class),
        row.get("overdue", Long.class),
        row.get("authorized", Long.class) + row.get("partial", Long.class),
        // 已收货数恒为 0：登记实收属 11.2.2.1，未交付（见 capabilities 的 receivedNote）。
        0L);
  }

  private static Object[] concat(OffsetDateTime at, List<Object> binds) {
    List<Object> all = new ArrayList<>();
    all.add(at);
    all.addAll(binds);
    return all.toArray();
  }

  // ================================================================ 详情

  /** 单张授权单全景：头 + 行 + 审批留痕 + 当前可执行动作 + 上下游可用性。 */
  public RmaDetail detail(UUID id) {
    OffsetDateTime at = OffsetDateTime.now(ZoneOffset.UTC);

    Record head =
        sql.fetchOne(
            "select r.*, c.case_no, c.case_type, c.status as case_status, c.order_id, o.order_no"
                + " from rma_authorization r"
                + " left join aftersales_case c on c.tenant_id = r.tenant_id and c.id = r.case_id"
                + " left join sales_order o on o.tenant_id = c.tenant_id and o.id = c.order_id"
                + " where r.tenant_id = ?::uuid and r.id = ?::uuid",
            currentTenant(), id);
    if (head == null) {
      // 跨租户的 ID 与不存在的 ID **同一条消息**（这个端点是按 id 直达的 URL）。
      throw new IllegalArgumentException("退货授权单不存在或不属于当前租户：" + id);
    }

    RmaStatus status = RmaStatus.valueOf(head.get("rma_status", String.class));

    List<RmaLine> lines =
        sql.fetch(
                "select l.*, cl.order_line_id, ol.sku_code, ol.sku_name, ol.spec_snapshot,"
                    + " ol.unit_price, c.currency"
                    + " from rma_authorization_line l"
                    + " join aftersales_case_line cl"
                    + "   on cl.tenant_id = l.tenant_id and cl.id = l.case_line_id"
                    + " join sales_order_line ol"
                    + "   on ol.tenant_id = cl.tenant_id and ol.id = cl.order_line_id"
                    + " left join aftersales_case c"
                    + "   on c.tenant_id = cl.tenant_id and c.id = cl.case_id"
                    + " where l.tenant_id = ?::uuid and l.rma_id = ?::uuid"
                    + " order by ol.line_no, l.id",
                currentTenant(), id)
            .map(
                r -> {
                  BigDecimal unitPrice = r.get("unit_price", BigDecimal.class);
                  BigDecimal authorized = nz(r.get("authorized_qty", BigDecimal.class));
                  BigDecimal ordered = r.get("ordered_qty_snapshot", BigDecimal.class);
                  BigDecimal shipped = r.get("shipped_qty_snapshot", BigDecimal.class);
                  BigDecimal prior = nz(r.get("prior_authorized_qty_snapshot", BigDecimal.class));
                  // 审批那一刻的可退上限：由**快照**还原（快照就是审批时查到的三个基数）。
                  // 草稿行两个基数为 null ⇒ 上限报 null 而不是 0，界面据此显示「未审批」。
                  BigDecimal cap =
                      ordered == null || shipped == null
                          ? null
                          : max0(min(ordered, shipped).subtract(prior));
                  return new RmaLine(
                      r.get("id", UUID.class),
                      r.get("case_line_id", UUID.class),
                      r.get("order_line_id", UUID.class),
                      r.get("sku_code", String.class),
                      r.get("sku_name", String.class),
                      r.get("spec_snapshot", String.class),
                      unitPrice == null ? null : unitPrice.toPlainString(),
                      r.get("currency", String.class),
                      nz(r.get("requested_qty", BigDecimal.class)),
                      authorized,
                      ordered,
                      shipped,
                      prior,
                      cap,
                      unitPrice == null ? null : unitPrice.multiply(authorized)
                          .setScale(4, java.math.RoundingMode.HALF_UP),
                      r.get("line_note", String.class));
                });

    List<RmaDecisionEvent> events =
        sql.fetch(
                "select id, event_type, from_status, to_status, reason_note, occurred_by, occurred_at"
                    + " from rma_decision_event"
                    + " where tenant_id = ?::uuid and rma_id = ?::uuid"
                    + " order by occurred_at, id",
                currentTenant(), id)
            .map(
                r ->
                    new RmaDecisionEvent(
                        r.get("id", UUID.class),
                        r.get("event_type", String.class),
                        eventLabel(r.get("event_type", String.class)),
                        r.get("from_status", String.class),
                        r.get("to_status", String.class),
                        r.get("reason_note", String.class),
                        r.get("occurred_by", UUID.class),
                        r.get("occurred_at", OffsetDateTime.class)));

    BigDecimal authorizedQty = BigDecimal.ZERO;
    BigDecimal requestedQty = BigDecimal.ZERO;
    for (RmaLine line : lines) {
      authorizedQty = authorizedQty.add(line.authorizedQty());
      requestedQty = requestedQty.add(line.requestedQty());
    }

    String timeliness = timeliness(status, head.get("return_by_at", OffsetDateTime.class), at);
    // 剩余秒数在服务层一次算好（整份聚合共用同一个参考时刻）。
    Long remaining = null;
    if (status.awaitingBuyerShipment() && head.get("return_by_at", OffsetDateTime.class) != null) {
      remaining = java.time.Duration.between(at, head.get("return_by_at", OffsetDateTime.class)).getSeconds();
    }

    return new RmaDetail(
        head(head),
        lines,
        events,
        authorizedQty,
        requestedQty,
        timeliness,
        RmaTimelinessState.valueOf(timeliness).label(),
        remaining,
        false,
        "11.2.2.1 退货入库",
        actionEntries(status),
        relatedModules(),
        at,
        "**授权不等于已收到**：本单已发出退货授权，但货可能还在路上，也可能根本没寄回。"
            + "所以这里有寄回时限倒计时与超时自动关闭，却**没有**「已收到」状态 —— "
            + "登记退回实物属 11.2.2.1，未交付。行上的指示性金额 = 订单行单价 × 本行授权量，"
            + "**不是**可退余额校验的结果（11.3.1.1 未交付），更不是实际退款额（12 财务）。");
  }

  private static BigDecimal max0(BigDecimal value) {
    return value.signum() < 0 ? BigDecimal.ZERO : value;
  }

  // ================================================================ 写：建单

  /**
   * 登记退货授权单（草稿）。
   *
   * <p><b>建单不是审批</b>：行只带申请量，授权量恒为 0。审批是 {@link #decide} 的事。
   */
  public RmaDetail create(RmaCreate request, UUID actorId) {
    if (request.caseId() == null) {
      throw new IllegalArgumentException("必须关联一张售后单（退货申请的来源）");
    }
    RmaTargetDisposition disposition =
        parseEnum(RmaTargetDisposition.class, request.targetDisposition(), "目标处理方式");
    RmaAddressSource addressSource =
        parseEnum(RmaAddressSource.class, request.returnAddressSource(), "退货地址来源");
    if (addressSource == RmaAddressSource.MODULE_3_6_3_ADDRESS_BOOK && !isSet(request.sourceReference())) {
      throw new IllegalArgumentException(
          "退货地址来源为 MODULE_3_6_3_ADDRESS_BOOK 时必须给出 sourceReference（哪份来源的哪一段）");
    }
    String recipientName = requireText(request.returnRecipientName(), "退货地址收货人");
    String recipientPhone = requireText(request.returnRecipientPhone(), "退货地址联系电话");
    String countryCode = requireCountry(request.returnCountryCode());
    String line1 = requireText(request.returnAddressLine1(), "退货地址详细地址");

    Record head =
        sql.fetchOne(
            "select c.id, c.case_type, c.status, c.order_id, c.requested_quantity, c.currency"
                + " from aftersales_case c where c.tenant_id = ?::uuid and c.id = ?::uuid",
            currentTenant(), request.caseId());
    if (head == null) {
      throw new IllegalArgumentException("售后单不存在或不属于当前租户：" + request.caseId());
    }
    // 只有退货退款类才有退货这回事（REPAIR 的货没出门、REFUND_ONLY 没货要寄、EXCHANGE 属 11.3.2.1）。
    if (!"RETURN_REFUND".equals(head.get("case_type", String.class))) {
      throw new IllegalArgumentException(
          "只有 RETURN_REFUND 类的售后单才能发起退货授权，当前类型 " + head.get("case_type", String.class));
    }
    String caseStatus = head.get("status", String.class);
    if (List.of("RESOLVED", "REJECTED", "CLOSED").contains(caseStatus)) {
      throw new IllegalArgumentException("售后单已结案（" + caseStatus + "），不能再发起退货授权");
    }

    // 行：默认取该售后单的全部商品行（申请量真来自售后商品行，不接受前端另报一个数）。
    List<Record> sourceLines =
        sql.fetch(
            "select cl.id, cl.quantity from aftersales_case_line cl"
                + " where cl.tenant_id = ?::uuid and cl.case_id = ?::uuid order by cl.id",
            currentTenant(), request.caseId());
    if (sourceLines.isEmpty()) {
      throw new IllegalArgumentException("该售后单还没有登记商品行，无法发起退货授权");
    }
    Map<UUID, BigDecimal> source = new LinkedHashMap<>();
    for (Record r : sourceLines) {
      source.put(r.get("id", UUID.class), nz(r.get("quantity", BigDecimal.class)));
    }

    // 调用方点名要的行（不给就是全部）—— 点名了不存在的行要报错，不能静默少一行。
    Map<UUID, String> notes = new LinkedHashMap<>();
    Map<UUID, BigDecimal> overrides = new LinkedHashMap<>();
    if (request.lines() != null && !request.lines().isEmpty()) {
      for (RmaLineDraft draft : request.lines()) {
        if (draft.caseLineId() == null) {
          throw new IllegalArgumentException("逐行明细必须给出 caseLineId");
        }
        if (!source.containsKey(draft.caseLineId())) {
          throw new IllegalArgumentException("售后商品行不属于该售后单：" + draft.caseLineId());
        }
        notes.put(draft.caseLineId(), blankToEmpty(draft.lineNote()));
        if (draft.requestedQty() != null) {
          if (draft.requestedQty().signum() <= 0) {
            throw new IllegalArgumentException("申请数量必须大于 0");
          }
          if (draft.requestedQty().compareTo(source.get(draft.caseLineId())) > 0) {
            throw new IllegalArgumentException(
                "申请数量 " + draft.requestedQty() + " 超过售后商品行数量 " + source.get(draft.caseLineId()));
          }
          overrides.put(draft.caseLineId(), draft.requestedQty());
        }
      }
      if (request.caseLineIds() != null && !request.caseLineIds().isEmpty()) {
        throw new IllegalArgumentException("caseLineIds 与 lines 同时给出，请只用 lines");
      }
    } else if (request.caseLineIds() != null && !request.caseLineIds().isEmpty()) {
      for (UUID id : request.caseLineIds()) {
        if (!source.containsKey(id)) {
          throw new IllegalArgumentException("售后商品行不属于该售后单：" + id);
        }
      }
      Map<UUID, BigDecimal> keep = new LinkedHashMap<>();
      for (UUID id : request.caseLineIds()) {
        keep.put(id, source.get(id));
      }
      source.keySet().retainAll(keep.keySet());
    }

    UUID id = UUID.randomUUID();
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    sql.execute(
        "insert into rma_authorization(id,tenant_id,rma_no,case_id,rma_status,target_disposition,"
            + " return_address_source,source_reference,return_recipient_name,return_recipient_phone,"
            + " return_contact_email,return_country_code,return_province,return_city,return_district,"
            + " return_postcode,return_address_line1,return_address_line2,return_logistics_source,"
            + " version,created_by,created_at,updated_at)"
            + " values(?,?,?,?,'DRAFT',?,?,?,?,?,?,?,?,?,?,?,?,?,'TENANT_MAINTAINED',0,?,?::timestamptz,"
            + " ?::timestamptz)",
        id,
        currentTenant(),
        nextRmaNo(),
        request.caseId(),
        disposition.name(),
        addressSource.name(),
        blankToEmpty(request.sourceReference()),
        recipientName,
        recipientPhone,
        blankToEmpty(request.returnContactEmail()),
        countryCode,
        blankToEmpty(request.returnProvince()),
        blankToEmpty(request.returnCity()),
        blankToEmpty(request.returnDistrict()),
        blankToEmpty(request.returnPostcode()),
        line1,
        blankToEmpty(request.returnAddressLine2()),
        actorId,
        now,
        now);

    for (Map.Entry<UUID, BigDecimal> entry : source.entrySet()) {
      sql.execute(
          "insert into rma_authorization_line(id,tenant_id,rma_id,case_line_id,requested_qty,"
              + " authorized_qty,line_note,created_at,updated_at)"
              + " values(?,?,?,?,?,0,?,?::timestamptz,?::timestamptz)",
          UUID.randomUUID(),
          currentTenant(),
          id,
          entry.getKey(),
          overrides.getOrDefault(entry.getKey(), entry.getValue()),
          notes.getOrDefault(entry.getKey(), ""),
          now,
          now);
    }

    return detail(id);
  }

  // ================================================================ 写：改草稿

  /**
   * 改草稿要素（目标处理方式 / 退货地址 / 逐行说明）。
   *
   * <p><b>只允许 DRAFT 改</b>：授权已发出后，地址与处理方式就是<b>给买家的承诺</b>，
   * 买家可能已经照着旧地址寄了 —— 这时改地址等于让货寄丢。
   */
  public RmaDetail update(UUID id, RmaUpdate request) {
    Record head = requireDraft(id);
    requireVersion(head, request.expectedVersion());

    RmaTargetDisposition disposition =
        parseEnum(RmaTargetDisposition.class, request.targetDisposition(), "目标处理方式");
    RmaAddressSource addressSource =
        parseEnum(RmaAddressSource.class, request.returnAddressSource(), "退货地址来源");
    if (addressSource == RmaAddressSource.MODULE_3_6_3_ADDRESS_BOOK && !isSet(request.sourceReference())) {
      throw new IllegalArgumentException(
          "退货地址来源为 MODULE_3_6_3_ADDRESS_BOOK 时必须给出 sourceReference（哪份来源的哪一段）");
    }
    String recipientName = requireText(request.returnRecipientName(), "退货地址收货人");
    String recipientPhone = requireText(request.returnRecipientPhone(), "退货地址联系电话");
    String countryCode = requireCountry(request.returnCountryCode());
    String line1 = requireText(request.returnAddressLine1(), "退货地址详细地址");

    int updated =
        sql.execute(
            "update rma_authorization set target_disposition = ?, return_address_source = ?,"
                + " source_reference = ?, return_recipient_name = ?, return_recipient_phone = ?,"
                + " return_contact_email = ?, return_country_code = ?, return_province = ?,"
                + " return_city = ?, return_district = ?, return_postcode = ?,"
                + " return_address_line1 = ?, return_address_line2 = ?,"
                + " version = version + 1, updated_at = ?::timestamptz"
                + " where tenant_id = ?::uuid and id = ?::uuid and rma_status = 'DRAFT'",
            disposition.name(),
            addressSource.name(),
            blankToEmpty(request.sourceReference()),
            recipientName,
            recipientPhone,
            blankToEmpty(request.returnContactEmail()),
            countryCode,
            blankToEmpty(request.returnProvince()),
            blankToEmpty(request.returnCity()),
            blankToEmpty(request.returnDistrict()),
            blankToEmpty(request.returnPostcode()),
            line1,
            blankToEmpty(request.returnAddressLine2()),
            OffsetDateTime.now(ZoneOffset.UTC),
            currentTenant(),
            id);
    if (updated == 0) {
      throw new IllegalArgumentException("退货授权单已不是草稿，不能修改：" + id);
    }

    if (request.lines() != null) {
      for (RmaLineDraft draft : request.lines()) {
        if (draft.caseLineId() == null) {
          throw new IllegalArgumentException("逐行明细必须给出 caseLineId");
        }
        sql.execute(
            "update rma_authorization_line set line_note = ?"
                + " where tenant_id = ?::uuid and rma_id = ?::uuid and case_line_id = ?::uuid",
            blankToEmpty(draft.lineNote()), currentTenant(), id, draft.caseLineId());
      }
    }

    return detail(id);
  }

  // ================================================================ 写：审批

  /**
   * 审批：授权 / 部分授权 / 拒绝（职责句输出：「退货授权单（授权/部分授权/拒绝）+ 拒绝理由」）。
   *
   * <p><b>可退量在审批这一刻重算，不信页面传来的数</b>：页面打开到点「授权」之间可能过了几十分钟，
   * 别的授权单可能已经批走了一部分。DB 触发器 {@code guard_rma_authorization_line} 还会再算一遍，
   * 绕过服务层直连 SQL 也绕不过。
   */
  public RmaDetail decide(UUID id, RmaDecide request, UUID actorId) {
    Record head = requireDraft(id);
    requireVersion(head, request.expectedVersion());
    RmaDecision decision = parseEnum(RmaDecision.class, request.decision(), "审批决定");

    if (request.lines() == null || request.lines().isEmpty()) {
      throw new IllegalArgumentException("必须逐行给出授权数量");
    }

    // 部分授权与拒绝必须说明理由（职责句与输出句原文，DB CHECK 也会兜一道）。
    if ((decision == RmaDecision.PARTIALLY_APPROVED || decision == RmaDecision.REJECTED)
        && !isSet(request.decisionReason())) {
      throw new IllegalArgumentException(
          decision == RmaDecision.PARTIALLY_APPROVED
              ? "部分授权必须说明理由（职责句：部分退货需说明理由）"
              : "拒绝必须说明理由（输出句：+ 拒绝理由）");
    }
    // 授权 / 部分授权必须有寄回时限：没有时限的授权永远不会超时关闭，
    // 而蓝图加粗句要的正是「没寄回就自动关掉」。
    if (decision != RmaDecision.REJECTED && request.returnByAt() == null) {
      throw new IllegalArgumentException(
          "授权 / 部分授权必须给出买家寄回时限（returnByAt）：没有时限的单永远不会超时关闭");
    }
    OffsetDateTime decidedAt = OffsetDateTime.now(ZoneOffset.UTC);
    if (request.returnByAt() != null && !request.returnByAt().isAfter(decidedAt)) {
      throw new IllegalArgumentException("买家寄回时限必须晚于当前时刻");
    }

    // 逐行取真实基数并重算上限 —— 拒绝时授权量一律 0。
    // ⚠️ 基数**只查一次**并同时用于判定与快照：查两遍之间别的请求可能批走了一部分，
    //   那样落库的快照与实际判过的口径会对不上（审计时「当时凭什么批了这个数」就答不出来了）。
    Map<UUID, BigDecimal> quantities = new LinkedHashMap<>();
    Map<UUID, LineBasis> bases = new LinkedHashMap<>();
    for (RmaLineDecision line : request.lines()) {
      if (line.lineId() == null) {
        throw new IllegalArgumentException("逐行明细必须给出 lineId");
      }
      LineBasis basis = lineBasis(line.lineId(), id);
      bases.put(line.lineId(), basis);
      BigDecimal authorized =
          decision == RmaDecision.REJECTED ? BigDecimal.ZERO : requirePositive(line.authorizedQty());
      if (decision != RmaDecision.REJECTED) {
        BigDecimal allowed = cap(basis);
        if (authorized.compareTo(allowed) > 0) {
          throw new IllegalArgumentException(
              "授权数量 "
                  + authorized.stripTrailingZeros()
                  + " 超过可退上限 "
                  + allowed.stripTrailingZeros()
                  + "（订购 "
                  + basis.orderedQty().stripTrailingZeros()
                  + "、已发 "
                  + basis.shippedQty().stripTrailingZeros()
                  + (basis.shippedQty().signum() == 0
                      ? "（没有履约进度投影，已发按 0 处理）"
                      : "")
                  + "、其他在途授权 "
                  + basis.inflight().stripTrailingZeros()
                  + "）");
        }
        if (authorized.compareTo(basis.caseQty()) > 0) {
          throw new IllegalArgumentException(
              "授权数量 " + authorized.stripTrailingZeros() + " 超过售后商品行申请数量 "
                  + basis.caseQty().stripTrailingZeros());
        }
      }
      quantities.put(line.lineId(), authorized);
    }

    // 本单必须**逐行**都给出决定：漏一行会让那一行的授权量永远停在 0，
    // 而「这一行到底批没批」是审批单据上最不能含糊的一个数。
    long declared = quantities.size();
    long actual = sql.fetchOne(
            "select count(*) from rma_authorization_line where tenant_id = ?::uuid and rma_id = ?::uuid",
            currentTenant(), id)
        .get(0, Long.class);
    if (declared != actual) {
      throw new IllegalArgumentException("必须对本单的每一行给出授权数量（共有 " + actual + " 行）");
    }

    RmaStatus target =
        switch (decision) {
          case APPROVED -> RmaStatus.AUTHORIZED;
          case PARTIALLY_APPROVED -> RmaStatus.PARTIALLY_AUTHORIZED;
          case REJECTED -> RmaStatus.REJECTED;
          case PENDING -> throw new IllegalArgumentException("审批决定不能是 PENDING");
        };

    OffsetDateTime snapAt = OffsetDateTime.now(ZoneOffset.UTC);
    for (RmaLineDecision line : request.lines()) {
      LineBasis basis = bases.get(line.lineId());
      // 基数快照在审批这一刻落库：事后审计「当时凭什么批了这个数」要看的就是它。
      // ⚠️ 没有履约进度投影时快照写 0 而不是 null：表级 CHECK 用它判上限，
      //   而「已发未知」在退货语境下的正确读数就是「不能退」。
      sql.execute(
          "update rma_authorization_line"
              + " set authorized_qty = ?, line_note = coalesce(?, line_note),"
              + "   ordered_qty_snapshot = ?, shipped_qty_snapshot = ?,"
              + "   prior_authorized_qty_snapshot = ?, updated_at = ?::timestamptz"
              + " where tenant_id = ?::uuid and id = ?::uuid",
          quantities.get(line.lineId()),
          isSet(line.lineNote()) ? line.lineNote() : null,
          basis.orderedQty(),
          basis.shippedQty(),
          basis.inflight(),
          snapAt,
          currentTenant(),
          line.lineId());
    }

    transition(
        id,
        target,
        request.returnByAt(),
        blankToEmpty(request.decisionReason()),
        actorId,
        decidedAt);

    return detail(id);
  }

  /** 审批那一刻的四个闸门基数（订购 / 已发 / 其他在途授权），一次查齐。 */
  private record LineBasis(
      UUID caseLineId,
      UUID orderLineId,
      BigDecimal caseQty,
      BigDecimal orderedQty,
      BigDecimal shippedQty,
      BigDecimal inflight) {}

  /** 按本单的行 id 查基数：其他在途授权累计**排除本单自己**（审批时本单尚未提交状态）。 */
  private LineBasis lineBasis(UUID lineId, UUID rmaId) {
    Record r =
        sql.fetchOne(
            "select cl.id as case_line_id, cl.order_line_id, cl.quantity as case_qty,"
                + " ol.quantity as ordered_qty, p.shipped_qty,"
                + " (select coalesce(sum(rl.authorized_qty), 0)"
                + "    from rma_authorization_line rl"
                + "    join aftersales_case_line rcl"
                + "      on rcl.tenant_id = rl.tenant_id and rcl.id = rl.case_line_id"
                + "    join rma_authorization h"
                + "      on h.tenant_id = rl.tenant_id and h.id = rl.rma_id"
                + "   where rl.tenant_id = ?::uuid"
                + "     and rl.rma_id <> ?::uuid"
                + "     and rcl.order_line_id = cl.order_line_id"
                + "     and h.rma_status in ('AUTHORIZED','PARTIALLY_AUTHORIZED')) as inflight"
                + " from rma_authorization_line l"
                + " join aftersales_case_line cl"
                + "   on cl.tenant_id = l.tenant_id and cl.id = l.case_line_id"
                + " join sales_order_line ol"
                + "   on ol.tenant_id = cl.tenant_id and ol.id = cl.order_line_id"
                + " left join order_fulfillment_line_progress p"
                + "   on p.tenant_id = cl.tenant_id and p.order_line_id = cl.order_line_id"
                + " where l.tenant_id = ?::uuid and l.rma_id = ?::uuid and l.id = ?::uuid",
            currentTenant(), rmaId, currentTenant(), rmaId, lineId);
    if (r == null) {
      throw new IllegalArgumentException("授权行不存在或不属于当前租户：" + lineId);
    }
    return new LineBasis(
        r.get("case_line_id", UUID.class),
        r.get("order_line_id", UUID.class),
        nz(r.get("case_qty", BigDecimal.class)),
        nz(r.get("ordered_qty", BigDecimal.class)),
        // ⚠️ 没有履约进度投影时按 0 处理（宁可拦住也不能放过：没被记录发出过的货不能退）
        r.get("shipped_qty", BigDecimal.class) == null
            ? BigDecimal.ZERO
            : r.get("shipped_qty", BigDecimal.class),
        nz(r.get("inflight", BigDecimal.class)));
  }

  /** 可退上限 = min(订购, 已发) − 其他在途授权累计，下限 0。 */
  private static BigDecimal cap(LineBasis basis) {
    return max0(min(basis.orderedQty(), basis.shippedQty()).subtract(basis.inflight()));
  }

  // ================================================================ 写：回寄物流 / 撤销 / 超时关闭

  /**
   * 登记买家回寄物流（职责句点名要确认「买家回寄物流」）。
   *
   * <p>三件套（承运商 / 运单号 / 寄出时刻）同进同出，DB CHECK 兜底 ——
   * 给了承运商却没单号，在实收核对时无法定位那个包裹。
   */
  public RmaDetail recordReturnLogistics(UUID id, RmaReturnLogistics request, UUID actorId) {
    Record head = requireRecord(id);
    RmaStatus status = RmaStatus.valueOf(head.get("rma_status", String.class));
    if (!status.awaitingBuyerShipment()) {
      throw new IllegalArgumentException(
          "只有已授权 / 部分授权的单需要登记买家回寄物流，当前状态：" + status.label());
    }
    requireVersion(head, request.expectedVersion());
    RmaLogisticsSource source =
        parseEnum(RmaLogisticsSource.class, request.logisticsSource(), "回寄物流来源");

    String carrier = requireText(request.carrier(), "回寄承运商");
    String trackingNo = requireText(request.trackingNo(), "回寄运单号");
    if (request.shippedAt() == null) {
      throw new IllegalArgumentException("必须给出买家寄出时刻（shippedAt）");
    }
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

    int updated =
        sql.execute(
            "update rma_authorization set return_logistics_source = ?, return_carrier = ?,"
                + " return_tracking_no = ?, return_shipped_at = ?::timestamptz,"
                + " return_logistics_recorded_by = ?, return_logistics_recorded_at = ?::timestamptz,"
                + " version = version + 1, updated_at = ?::timestamptz"
                + " where tenant_id = ?::uuid and id = ?::uuid"
                + "   and rma_status in ('AUTHORIZED','PARTIALLY_AUTHORIZED')",
            source.name(), carrier, trackingNo, request.shippedAt(), actorId, now, now,
            currentTenant(), id);
    if (updated == 0) {
      throw new IllegalArgumentException("退货授权单状态已变化，请刷新后重试：" + id);
    }
    return detail(id);
  }

  /** 人工撤销：货还没回来，操作员发现批错了。量立刻还给订单行（可退上限恢复）。 */
  public RmaDetail cancel(UUID id, RmaCancel request, UUID actorId) {
    Record head = requireRecord(id);
    RmaStatus status = RmaStatus.valueOf(head.get("rma_status", String.class));
    if (!status.awaitingBuyerShipment()) {
      throw new IllegalArgumentException(
          "只有已授权 / 部分授权的单可撤销（货还没回来），当前状态：" + status.label());
    }
    requireVersion(head, request.expectedVersion());
    transition(id, RmaStatus.CANCELLED, null, blankToEmpty(request.reason()), actorId,
        OffsetDateTime.now(ZoneOffset.UTC));
    return detail(id);
  }

  /**
   * 超时未寄自动关闭（蓝图加粗句「需要超时未寄自动关闭」的直接落点）。
   *
   * <p><b>这是显式端点而不是后台定时器</b>：定时器要另起一个 worker 进程与调度配置，
   * 那是另一个模块的事；这里给出真实可调用的关闭动作 + 逐条结果，
   * 调度侧以后只要定时调它即可，语义与判据已经在这一处定死。
   *
   * <p>判据只有一个：{@code return_by_at < 现在} 且状态仍在途。
   * <b>已经登记了回寄物流的单也照常关闭</b> —— 登记只说明买家报了个运单号，
   * 货到底有没有到库是 11.2.2.1 的事；在本模块的语义里「时限到了没收到」就该关。
   */
  public RmaExpireResult expireOverdue() {
    OffsetDateTime at = OffsetDateTime.now(ZoneOffset.UTC);
    UUID tenant = currentTenant();

    List<Record> candidates =
        sql.fetch(
            "select id, rma_no, rma_status, return_by_at"
                + " from rma_authorization"
                + " where tenant_id = ?::uuid"
                + "   and rma_status in ('AUTHORIZED','PARTIALLY_AUTHORIZED')"
                + "   and return_by_at is not null and return_by_at < ?::timestamptz"
                + " order by return_by_at limit ?",
            tenant, at, EXPIRE_SCAN_LIMIT);

    List<RmaExpireItem> items = new ArrayList<>();
    int expired = 0;
    for (Record r : candidates) {
      UUID id = r.get("id", UUID.class);
      String rmaNo = r.get("rma_no", String.class);
      OffsetDateTime due = r.get("return_by_at", OffsetDateTime.class);

      Record logistics =
          sql.fetchOne(
              "select return_tracking_no from rma_authorization"
                  + " where tenant_id = ?::uuid and id = ?::uuid",
              tenant, id);
      String trackingNo = logistics == null ? "" : blankToEmpty(logistics.get(0, String.class));

      int updated =
          sql.execute(
              "update rma_authorization"
                  + " set rma_status = 'EXPIRED', expired_at = ?::timestamptz,"
                  + "     version = version + 1, updated_at = ?::timestamptz"
                  + " where tenant_id = ?::uuid and id = ?::uuid"
                  + "   and rma_status in ('AUTHORIZED','PARTIALLY_AUTHORIZED')"
                  + "   and return_by_at is not null and return_by_at < ?::timestamptz",
              at, at, tenant, id, at);

      if (updated == 1) {
        expired++;
        items.add(
            new RmaExpireItem(
                id, rmaNo, r.get("rma_status", String.class), due, at, !trackingNo.isEmpty(),
                "EXPIRED", null));
      } else {
        items.add(
            new RmaExpireItem(
                id, rmaNo, r.get("rma_status", String.class), due, null, !trackingNo.isEmpty(),
                "ALREADY_CLOSED", "扫描期间状态已被别的请求改掉（并发审批或撤销），本次跳过"));
      }
    }

    return new RmaExpireResult(
        candidates.size(),
        expired,
        candidates.size() - expired,
        items,
        at,
        "判据只有一个：return_by_at < 扫描时刻 且状态仍在途（AUTHORIZED / PARTIALLY_AUTHORIZED）。"
            + "**已经登记了回寄物流的单也照常关闭** —— 登记只说明买家报了个运单号，"
            + "货到没到库是 11.2.2.1 的事；本模块的语义是「时限到了没收到就关」。"
            + "单次扫描上限 " + EXPIRE_SCAN_LIMIT + " 张（不是怕慢，是怕一次长事务把 RLS 租户设置一直挂着"
            + "而剩下的没人补扫；剩下的下一轮自然会被扫到，因为判据只看时限）。"
            + "关闭后本单的行是证据不是草稿：DB 触发器拒绝再改授权量，可退量立刻还给订单行。");
  }

  // ================================================================ 内部

  /**
   * 状态流转的唯一入口：版本校验 + 状态写入 + 留痕（留痕由 DB 触发器追加）。
   *
   * <p>⚠️ **每个目标状态一条 SQL，而不是一条带 {@code case when ? in (...)} 的万能语句**：
   *   PostgreSQL 把 {@code ?} 同时当作 JSONB 的存在性运算符，{@code ? in (...)} 会被解析器
   *   抢先当成运算符，报出来的是一句与真实原因毫无关系的语法错误。
   *   顺带这样写也更好读：拒绝与撤销本来要落的列就不同，用一个 {@code decided_by/cancelled_by}
   *   二选一的条件表达式把两种语义糊在一起，读的人得在脑子里跑一遍才知道落了哪几列。
   */
  private void transition(UUID id, RmaStatus target, OffsetDateTime returnByAt,
      String reason, UUID actorId, OffsetDateTime at) {
    String statement =
        switch (target) {
          case AUTHORIZED, PARTIALLY_AUTHORIZED, REJECTED ->
              "update rma_authorization set rma_status = ?,"
                  + " return_by_at = coalesce(?::timestamptz, return_by_at),"
                  + " decision_reason = ?, decided_by = ?::uuid, decided_at = ?::timestamptz,"
                  + " version = version + 1, updated_at = ?::timestamptz"
                  + " where tenant_id = ?::uuid and id = ?::uuid";
          case CANCELLED ->
              "update rma_authorization set rma_status = 'CANCELLED', cancelled_by = ?::uuid,"
                  + " cancelled_at = ?::timestamptz,"
                  + " decision_reason = coalesce(nullif(?, ''), decision_reason),"
                  + " version = version + 1, updated_at = ?::timestamptz"
                  + " where tenant_id = ?::uuid and id = ?::uuid";
          case EXPIRED ->
              "update rma_authorization set rma_status = 'EXPIRED', expired_at = ?::timestamptz,"
                  + " version = version + 1, updated_at = ?::timestamptz"
                  + " where tenant_id = ?::uuid and id = ?::uuid";
          case DRAFT ->
              throw new IllegalArgumentException("退货授权单不能回到草稿：审批已是一次性决定，撤回请用撤销");
        };

    List<Object> binds = new ArrayList<>();
    switch (target) {
      case AUTHORIZED, PARTIALLY_AUTHORIZED, REJECTED -> {
        binds.add(target.name());
        binds.add(returnByAt);
        binds.add(reason);
        binds.add(actorId);
        binds.add(at);
        binds.add(at);
      }
      case CANCELLED -> {
        // ⚠️ 撤销分支的 SQL 里 rma_status 是**字面量**，没有占位符 —— 这里也就不能多绑一个。
        //   多绑一个会让后面每个 ? 整体错位一格，报出来的是「uuid 转不了 timestamptz」这种
        //   与真实原因完全无关的类型错误（jOOQ 按位置绑定，绑多了不会报错，只会绑歪）。
        binds.add(actorId);
        binds.add(at);
        binds.add(reason);
        binds.add(at);
      }
      case EXPIRED -> {
        binds.add(at);
        binds.add(at);
      }
      case DRAFT ->
          throw new IllegalArgumentException("不支持的目标状态：草稿");
    }
    binds.add(currentTenant());
    binds.add(id);

    int updated = sql.execute(statement, binds.toArray());
    if (updated != 1) {
      throw new IllegalArgumentException("退货授权单不存在或不属于当前租户：" + id);
    }
  }

  private Record requireRecord(UUID id) {
    Record head =
        sql.fetchOne(
            "select * from rma_authorization where tenant_id = ?::uuid and id = ?::uuid",
            currentTenant(), id);
    if (head == null) {
      throw new IllegalArgumentException("退货授权单不存在或不属于当前租户：" + id);
    }
    return head;
  }

  private Record requireDraft(UUID id) {
    Record head = requireRecord(id);
    RmaStatus status = RmaStatus.valueOf(head.get("rma_status", String.class));
    if (status != RmaStatus.DRAFT) {
      throw new IllegalArgumentException(
          "只有草稿可以审批或修改，当前状态：" + status.label()
              + (status.terminal() ? "（已结案：行是证据不是草稿）" : ""));
    }
    return head;
  }

  /**
   * 乐观锁：版本必须与请求一致。
   *
   * <p>两个人同时点「授权」时，各写一次决定而表里只留得下后一个 ——
   * 少的那一次授权要么被静默覆盖，要么让已退数量对不上。
   */
  private void requireVersion(Record head, Long expectedVersion) {
    long current = head.get("version", Long.class);
    if (expectedVersion == null) {
      throw new IllegalArgumentException("必须给出 expectedVersion（乐观锁，避免并发审批互相覆盖）");
    }
    if (expectedVersion != current) {
      throw new IllegalArgumentException(
          "版本不符（当前 " + current + "，请求 " + expectedVersion + "），请刷新后重试");
    }
  }

  private String timeliness(RmaStatus status, OffsetDateTime due, OffsetDateTime at) {
    if (!status.awaitingBuyerShipment()) {
      return "CLOSED";
    }
    if (due == null) {
      // DB CHECK 保证在途两态必有时限；这里只是不让判空漏成 NPE。
      return "NOT_APPLICABLE";
    }
    long seconds = java.time.Duration.between(at, due).getSeconds();
    if (seconds < 0) {
      return "OVERDUE";
    }
    return seconds <= DUE_SOON_SECONDS ? "DUE_SOON" : "ON_TRACK";
  }

  private RmaRow row(Record r, OffsetDateTime at) {
    RmaStatus status = RmaStatus.valueOf(r.get("rma_status", String.class));
    RmaDecision decision = RmaDecision.of(status);
    RmaTargetDisposition disposition =
        RmaTargetDisposition.valueOf(r.get("target_disposition", String.class));
    // 时效判定**只有 SQL 里那一个真身**（timelinessCase）：Java 侧不重算，
    // 否则两处口径漂移时列表与详情会给出不同的逾期判定。
    String timeliness = r.get("timeliness_state", String.class);

    return new RmaRow(
        r.get("id", UUID.class),
        r.get("rma_no", String.class),
        r.get("case_id", UUID.class),
        r.get("case_no", String.class),
        r.get("order_id", UUID.class),
        r.get("order_no", String.class),
        status.name(),
        status.label(),
        decision.name(),
        decision.label(),
        disposition.name(),
        disposition.label(),
        nz(r.get("authorized_qty", BigDecimal.class)),
        nz(r.get("requested_qty", BigDecimal.class)),
        r.get("line_count", Integer.class) == null ? 0 : r.get("line_count", Integer.class),
        blankToEmpty(r.get("return_country_code", String.class)),
        blankToEmpty(r.get("return_recipient_name", String.class)),
        nullable(r.get("return_carrier", String.class)),
        nullable(r.get("return_tracking_no", String.class)),
        r.get("return_shipped_at", OffsetDateTime.class),
        r.get("return_by_at", OffsetDateTime.class),
        r.get("expired_at", OffsetDateTime.class),
        timeliness,
        RmaTimelinessState.valueOf(timeliness).label(),
        r.get("remaining_seconds", Long.class),
        nullable(r.get("decision_reason", String.class)),
        false,
        "11.2.2.1 退货入库",
        decideAction(status),
        r.get("created_at", OffsetDateTime.class));
  }

  /** 列表每行只带**一个**动作入口（审批），不为每行重建整份动作表。 */
  private RmaActionEntry decideAction(RmaStatus status) {
    boolean draft = status == RmaStatus.DRAFT;
    String blocking = null;
    if (status.terminal()) {
      blocking = "退货授权单已结案（" + status.label() + "）";
    } else if (!draft) {
      blocking = "只有草稿可审批：当前状态 " + status.label();
    }
    return new RmaActionEntry("DECIDE", "审批", draft, "POST",
        "/trade/rma-authorizations/{id}/decide", true, null, blocking, null, null);
  }

  private RmaHead head(Record r) {
    RmaStatus status = RmaStatus.valueOf(r.get("rma_status", String.class));
    RmaDecision decision = RmaDecision.of(status);
    RmaTargetDisposition disposition =
        RmaTargetDisposition.valueOf(r.get("target_disposition", String.class));
    RmaAddressSource addressSource =
        RmaAddressSource.valueOf(r.get("return_address_source", String.class));
    RmaLogisticsSource logisticsSource =
        RmaLogisticsSource.valueOf(r.get("return_logistics_source", String.class));

    return new RmaHead(
        r.get("id", UUID.class),
        r.get("rma_no", String.class),
        r.get("case_id", UUID.class),
        r.get("case_no", String.class),
        r.get("case_type", String.class),
        r.get("case_status", String.class),
        r.get("order_id", UUID.class),
        r.get("order_no", String.class),
        status.name(),
        status.label(),
        decision.name(),
        decision.label(),
        disposition.name(),
        disposition.label(),
        addressSource.name(),
        addressSource.label(),
        nullable(r.get("source_reference", String.class)),
        blankToEmpty(r.get("return_recipient_name", String.class)),
        blankToEmpty(r.get("return_recipient_phone", String.class)),
        nullable(r.get("return_contact_email", String.class)),
        blankToEmpty(r.get("return_country_code", String.class)),
        nullable(r.get("return_province", String.class)),
        nullable(r.get("return_city", String.class)),
        nullable(r.get("return_district", String.class)),
        nullable(r.get("return_postcode", String.class)),
        blankToEmpty(r.get("return_address_line1", String.class)),
        nullable(r.get("return_address_line2", String.class)),
        logisticsSource.name(),
        logisticsSource.label(),
        nullable(r.get("return_carrier", String.class)),
        nullable(r.get("return_tracking_no", String.class)),
        r.get("return_shipped_at", OffsetDateTime.class),
        r.get("return_logistics_recorded_by", UUID.class),
        r.get("return_logistics_recorded_at", OffsetDateTime.class),
        r.get("return_by_at", OffsetDateTime.class),
        r.get("expired_at", OffsetDateTime.class),
        blankToEmpty(r.get("decision_reason", String.class)),
        r.get("decided_by", UUID.class),
        r.get("decided_at", OffsetDateTime.class),
        r.get("cancelled_by", UUID.class),
        r.get("cancelled_at", OffsetDateTime.class),
        r.get("created_by", UUID.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class),
        r.get("version", Long.class));
  }

  private static String eventLabel(String raw) {
    if (raw == null) {
      return "";
    }
    return switch (raw) {
      case "CREATED" -> "登记草稿";
      case "AUTHORIZED" -> "全部授权";
      case "PARTIALLY_AUTHORIZED" -> "部分授权";
      case "REJECTED" -> "拒绝";
      case "EXPIRED" -> "超时未寄，自动关闭";
      case "CANCELLED" -> "人工撤销";
      default -> "草稿要素变更";
    };
  }

  private String nextRmaNo() {
    long n = sql.fetchOne("select nextval('rma_no_seq')").get(0, Long.class);
    return "RMA" + n;
  }

  private UUID currentTenant() {
    return UUID.fromString(
        sql.fetchOne("select current_setting('app.current_tenant')").get(0, String.class));
  }

  private static <E extends Enum<E>> E parseEnum(Class<E> type, String raw, String label) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException(label + "不能为空");
    }
    for (E e : type.getEnumConstants()) {
      if (e.name().equalsIgnoreCase(raw.trim())) {
        return e;
      }
    }
    StringBuilder allowed = new StringBuilder();
    for (E e : type.getEnumConstants()) {
      allowed.append(allowed.isEmpty() ? "" : " / ").append(e.name());
    }
    throw new IllegalArgumentException(label + "只能是 " + allowed + "，收到 " + raw);
  }

  private static BigDecimal requirePositive(BigDecimal value) {
    if (value == null || value.signum() < 0) {
      throw new IllegalArgumentException("授权数量不能为空且不能为负");
    }
    return value;
  }

  private static String requireText(String value, String label) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(label + "不能为空");
    }
    if (value.length() > 300) {
      throw new IllegalArgumentException(label + "不能超过 300 个字符");
    }
    return value.trim();
  }

  private static String requireCountry(String raw) {
    if (raw == null || !raw.trim().matches("^[A-Za-z]{2}$")) {
      throw new IllegalArgumentException("退货地址国家必须是两位大写字母（ISO 3166-1 alpha-2）");
    }
    return raw.trim().toUpperCase();
  }

  private static boolean isSet(String value) {
    return value != null && !value.isBlank();
  }

  private static String blankToEmpty(String value) {
    return value == null ? "" : value.trim();
  }

  /** 空串在响应里报成 null：列表与详情里「没填」应显示为「—」而不是一段空白。 */
  private static String nullable(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private static BigDecimal nz(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }

  private static BigDecimal min(BigDecimal a, BigDecimal b) {
    return a.compareTo(b) <= 0 ? a : b;
  }
}
