package com.starcon.trade;

import static com.starcon.trade.ReturnReceiptAllocationModels.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Service;

/**
 * 11.2.2.2 实收分配。
 *
 * <p>蓝图 11-aftersales-and-returns.md：「把实收退货分配到对应的退货授权行与原订单行，
 * 处理实收多于授权（<b>按实际退回</b>）、少收、超期退回等情况。」输出：「分配记录 +
 * <b>超收/少收说明</b>；<b>订单行已退数量更新</b>。」加粗句：「<b>按收货事件去重分配</b>，
 * 同一批退货不重复记已收数量。」
 *
 * <p><b>本类是 V178 五张新表的写入所有者</b>（return_receipt_allocation /
 * return_receipt_allocation_line / return_receipt_allocation_variance /
 * order_line_returned_qty / return_receipt_allocation_event），
 * 对 V172 的 return_receipt 簇、V161 的 rma_authorization 簇与售后 / 订单表<b>只读</b>。
 * 刻意不加外键、不 ALTER 既有表 ⇒ 跨车道 DDL 回归面 = 0。
 *
 * <p><b>六条硬纪律</b>：
 *
 * <ol>
 *   <li><b>每一条查询都显式带 {@code tenant_id} 谓词</b>，不依赖 RLS 当唯一防线。
 *       分配行带着<b>逐条订单行与 SKU 快照</b>，差额说明带着<b>金额差</b>（财务敏感），
 *       而详情 / 对账 / 撤销端点都是<b>按 id 直达</b>的 URL —— 漏掉租户谓词就是跨租户泄露。</li>
 *   <li><b>按收货事件去重</b>：{@link #create} 对同一收货事件<b>返回同一张分配单</b>，
 *       绝不新建第二张、绝不重复累加已退数量。DB 层三道闸门（唯一约束 + 触发器 +
 *       「状态变化才累加」的已退数量触发器）见 V178 第一节。</li>
 *   <li><b>超收按实际退回、不静默截断</b>：分配数<b>不被 clamp</b>，
 *       差额由 DB 触发器算成 {@code over_received_qty} 与 {@code variance_amount}，
 *       服务层<b>只读不算</b>这两个值 —— 调用方传什么都不影响结果。</li>
 *   <li><b>订单行已退数量只能由 DB 触发器改</b>：本服务<b>没有</b>任何
 *       {@code UPDATE order_line_returned_qty} 的代码路径（运行角色也已被 REVOKE）。
 *       ⇒「谁把已退数量改了」在本模块里只有一条代码路径。</li>
 *   <li><b>时间绑定</b>：时间一律 {@code ?::timestamptz} + {@link OffsetDateTime}。
 *       {@code Timestamp.valueOf(LocalDateTime)} 会静默偏 −28800s，
 *       而超期退回的判定完全依赖收货时刻与寄回时限的真实差值。</li>
 *   <li><b>状态流转只有一个入口</b>（{@link #transition}）：每次改状态都过版本校验，
 *       DB 触发器同步把已退数量累加或回冲并追加留痕。</li>
 * </ol>
 */
@Service
public class ReturnReceiptAllocationService {

  /** 列表单页上限。 */
  private static final int MAX_PAGE_SIZE = 200;

  /** 数量统一小数位（NUMERIC(19,6)）。 */
  private static final int QTY_SCALE = 6;

  /** 金额统一小数位（NUMERIC(19,4)）。 */
  private static final int AMOUNT_SCALE = 4;

  private final DSLContext sql;

  public ReturnReceiptAllocationService(DSLContext sql) {
    this.sql = sql;
  }

  // ================================================================ 能力声明

  /**
   * 能力声明：职责句逐块报可用性，下游逐个报 delivered=false 与归属模块号、
   * 本模块<b>刻意不做</b>的事、以及两条硬规则的口径。
   */
  public ReturnAllocationCapabilities capabilities() {
    return new ReturnAllocationCapabilities(
        List.of(
            "退货实收单 return_receipt + return_receipt_line（V172，同库只读，11.2.2.1 已交付）—— 收货事件与实收数量的真值来源",
            "退货授权单 rma_authorization / rma_authorization_line（V161，同库只读）—— 授权量与**寄回时限 return_by_at**（超期退回的判定锚点）",
            "售后单行 aftersales_case_line（V157，同库只读）—— 授权行到原订单行的映射",
            "订单行 sales_order_line（V99，同库只读）—— SKU 与单价的实时真值（不在本模块复制一份）",
            "实收分配单 return_receipt_allocation（V178，**一个收货事件一张**，幂等键 UNIQUE(tenant_id, idempotency_key)）",
            "分配行 return_receipt_allocation_line（V178，**超收/少收量与金额差由 DB 触发器算，不接受调用方传入**）",
            "超收/少收说明 return_receipt_allocation_variance（V178，**追加式**，运行角色 UPDATE/DELETE 已收回）",
            "订单行已退数量 order_line_returned_qty（V178，**本模块的写入所有权**，只由 DB 触发器改，运行角色 UPDATE/DELETE 已收回）",
            "分配留痕 return_receipt_allocation_event（V178，追加式，由 DB 触发器按状态变更自动追加）"),
        List.of(
            section("allocateToRmaLine", "分配到退货授权行", true, null,
                "每一行分配都必须落在一个**具体的授权行**上（target_rma_authorization_line_id NOT NULL）。"
                    + "目标授权行必须属于本分配单所关联的那张退货授权单 —— "
                    + "否则「A 单的分配指向 B 单的授权行」在库里完全合法，而额度算的是 B 单的。",
                "return_receipt_allocation_line.target_rma_authorization_line_id（V178）"),
            section("allocateToOrderLine", "分配到原订单行", true, null,
                "授权行 → 售后单行 → 原订单行三级链实时读出，**不在分配行上复制一份 SKU**。"
                    + "⚠️ 跨行分配（改指同订单的另一条商品行）在本模块里**允许**："
                    + "V172 第四节明说「合并/拆分属 11.2.2.2 实收分配的职责」，"
                    + "而跨行分配正是超收最主要的来源 —— 源授权行额度充足**不代表**目标行额度充足。"
                    + "跨**订单**分配被拒（那会让「这批货退给了谁」变成两笔资金事实）。",
                "return_receipt_allocation_line.order_line_id（V178）"),
            section("overReceived", "实收多于授权（按实际退回）", true, null,
                "★ 分配数**不被截断**：授权 1 件、货来了 3 件，allocatedQty 仍然是 3，"
                    + "而不是悄悄 clamp 成 1。超收 2 件整额落 over_received_qty，"
                    + "金额差落 variance_amount（NUMERIC，禁浮点记账），"
                    + "并且一定伴随一条 OVER_RECEIVED 说明。\n"
                    + "⚠️ 超收量与金额差**由 DB 触发器算**（V178 guard_return_allocation_line），"
                    + "服务层只读不算 ⇒ 调用方传什么都不是它。\n"
                    + "⚠️ 处置结论只有 PENDING_REVIEW / ACCEPTED_AS_ACTUAL 两个取值，"
                    + "**没有「已解决」** —— 超收多退的钱要追回还是认了是财务决定，不是本模块能写下的结论。",
                "return_receipt_allocation_line.over_received_qty / variance_amount（V178）"),
            section("underReceived", "少收", true, null,
                "两条来源，都不静默：① 同一实收行**分摊**到订单行上时，分配数小于实收数 → "
                    + "差额落 short_received_qty；② 对账时授权量减去累计分配仍有差额 → "
                    + "落一条 UNDER_RECEIVED 说明并写清「这批货去哪了」。\n"
                    + "⚠️ 少收的金额差是**负数**（少收的钱要补），表级 CHECK 钉住它与数量差同号。",
                "return_receipt_allocation_line.short_received_qty + variance 表（V178）"),
            section("overdueReturned", "超期退回", true, null,
                "判定锚点是**寄回时限 return_by_at**（rma_authorization，V161），"
                    + "比较对象是**收货事件时刻 received_at**（不是分配时刻 —— "
                    + "货到的时候就已经超期了，这是到场即成立的事实，事后才做分配改不了它）。\n"
                    + "⚠️ return_by_at 存**快照**而不是实时读：分配写完、授权单可能后续被关闭，"
                    + "判定「当时批的时候约定了什么时候寄回」用快照才答得出当时的事实。\n"
                    + "⚠️ 超期**不拒绝**：货已经收到了，退回商品默认进待检（11.2.2.1 硬规则），"
                    + "拒收等于让货无处可去。超期是**可解释**的事实，不是错误。",
                "return_receipt_allocation.return_by_at / overdue_returned / days_overdue（V178）"),
            section("dedupByReceipt", "按收货事件去重分配", true, null,
                "★ 蓝图加粗句「按收货事件去重分配，同一批退货不重复记已收数量」钉在三层：\n"
                    + "① 幂等键 UNIQUE(tenant_id, idempotency_key)，键形恒为 'RECEIPT:' + receipt_id；\n"
                    + "② 触发器 guard_return_allocation_line 在写入时刻再真查一次并报可读错误；\n"
                    + "③ 已退数量由**状态变化**触发累加（不是 INSERT 即累加）—— "
                    + "即使有人绕过 ①② 硬插一张重复分配单，它的初始状态就是 ALLOCATED，累加不执行。\n"
                    + "传输层**刻意没有** force / allowDuplicate 开关：把一次资损做成一次点击。",
                "return_receipt_allocation.idempotency_key + trg_return_allocation_order_qty（V178）"),
            section("orderLineReturnedQty", "订单行已退数量更新", true, null,
                "已退数量落成本模块**拥有**的 order_line_returned_qty（不 ALTER sales_order_line："
                    + "那是 9 订单那条线的写入所有权，且同批另有三条车道在往 trade 落迁移，"
                    + "ALTER 别人正在改的表 = 结构性冲突）。\n"
                    + "⚠️ 运行角色对该表的 UPDATE/DELETE 已在 DB 层**收回**，"
                    + "服务层也**没有**任何直接写它的代码路径 ⇒ 「谁把已退数量改了」只有一条代码路径。\n"
                    + "⚠️ 刻意**不填 0 冒充「没退过」**：没退过的订单行在这张表里**根本没有行**，"
                    + "而「这行从来没退过」在重复退货面前恰恰是最需要被发现的异常。",
                "order_line_returned_qty（V178，唯一写入路径 trg_return_allocation_order_qty）"),
            section("financialReconcile", "财务对账 / 退款发起", false, "11.2.3.3",
                "把分配与差额同步到财务、发起退款**未交付**。"
                    + "本模块产出的是**差额事实**（数量差 + 金额差 + 说明 + 待核实结论），"
                    + "不产出任何「已退款」「已对账完成」的结论 —— "
                    + "那是财务侧的事实，编一个会让财务以为这笔钱已经结清了。",
                null),
            section("orderWriteback", "回写 9 订单的已退数量口径", false, "9 订单",
                "9 订单按 AGENTS.md「走授权 API / event」读本模块的 order_line_returned_qty，"
                    + "消费侧**未交付**。本模块**刻意不 ALTER sales_order_line**，"
                    + "也**不建永远为 0 的影子列** —— 那会让页面显示成「这行从没退过」。",
                null)),
        List.of(
            related("MODULE_11_2_2_1", "11.2.2.1 退货入库", true, null,
                "收货事件与实收记录（V172，已交付）。分配的对象只能是已入库的实收单 —— "
                    + "实收还没入库时货没进待检，已退数量先走一步会让两份真相。", "/trade/return-receipts"),
            related("MODULE_11_2_1_1", "11.2.1.1 退货授权单", true, null,
                "授权量与寄回时限（V161，已交付）。超收与超期的判定真值来源。",
                "/aftersales/returns"),
            related("MODULE_9_ORDER", "9 订单（已退数量）", false, "9 订单",
                "已退数量的**消费侧未交付**：本模块是唯一写入所有者，9 订单按授权 API 读。", null),
            related("MODULE_11_2_3_3", "11.2.3.3 财务对账", false, "11.2.3.3",
                "把分配与差额同步到财务、发起退款**未交付**。本模块只产出差额事实。", null),
            related("MODULE_11_2_3_1", "11.2.3.1 质检与判定", true, null,
                "退回商品默认进待检（11.2.2.1 硬规则）。质检结论属 11.2.3.1，"
                    + "**它改变不了已退数量** —— 退没退是收货事实，判不判质检是另一回事。",
                null)),
        List.of(
            action("ALLOCATE", "按收货事件分配", true, "POST", "/trade/receipt-allocations", true,
                "实收单已入库且未分配过", null, null, null),
            action("RECONCILE", "对账（超收/少收/超期）", true, "POST",
                "/trade/receipt-allocations/{id}/reconcile", true, "仅 ALLOCATED", null, null, null),
            action("REVERSE", "撤销分配", true, "POST",
                "/trade/receipt-allocations/{id}/reverse", true, "仅 ALLOCATED，必须写理由",
                null, null, null),
            action("DUPLICATE", "重复分配同一收货事件", false, null, null, false, null, null,
                "★ 蓝图加粗句「按收货事件去重分配」。传输层**刻意没有**这个开关，"
                    + "重复提交同一收货事件返回**同一张**分配单。", "11.2.2.2"),
            action("WRITE_OFF", "直接改已退数量", false, null, null, false, null, null,
                "运行角色对该表的 UPDATE/DELETE 已在 DB 层收回，"
                    + "本服务也**没有**任何直接写它的代码路径 —— 改已退数量只能通过撤销分配。",
                "11.2.2.2"),
            action("MARK_RESOLVED", "把差额标记为已解决", false, null, null, false, null, null,
                "处置结论只有 PENDING_REVIEW / ACCEPTED_AS_ACTUAL，**刻意没有「已解决」**："
                    + "超收多退的钱要追回还是认了是财务决定，编一个「已解决」会让财务以为钱已结清。",
                "11.2.3.3")),
        List.of(
            boundary("不提供「重复分配 / 强制再记一次已退数量」的开关",
                "11.2.2.2",
                "蓝图加粗句「按收货事件去重分配，同一批退货不重复记已收数量」。"
                    + "传参里出现一个 force 开关，就等于把一次资损做成了一次点击。"),
            boundary("不静默截断超收，也不静默补齐少收",
                "11.2.2.2",
                "「处理实收多于授权（按实际退回）、少收」这句话本身就是反对截断的。"
                    + "把 allocatedQty clamp 成 min(实收, 授权) 会让超收在库里**消失**，"
                    + "而超收正是最需要有人知道的事（多退的钱要追回）。"),
            boundary("不 ALTER sales_order_line、不建影子引用列",
                "9 订单",
                "sales_order_line 属 9 订单那条线的写入所有权，且同批有并行车道往 trade 落迁移。"
                    + "建一列永远为 0 的 received_qty 会让页面显示成「这行从没退过」。"),
            boundary("不产出任何退款 / 结算结论",
                "11.2.3.3",
                "本模块产出的是差额**事实**（数量差 + 金额差 + 说明 + 待核实），"
                    + "「已退款」「已对账完成」是财务侧的事实。"),
            boundary("不改质检结论",
                "11.2.3.1",
                "退没退是**收货事实**，判不判质检是另一回事 —— "
                    + "把质检结论写进已退数量会让「可再销售」看起来像「已退款」。"),
            boundary("不给已退数量填 0 冒充「没退过」",
                "9 订单",
                "没退过的订单行在这张表里**没有行**。填 0 会让「这行从来没退过」"
                    + "在重复退货面前失去唯一提醒。"),
            boundary("跨订单分配被拒",
                "9 订单",
                "跨订单分配会让「这批货退给了谁」变成两笔资金事实。跨**订单行**分配允许。"),
            boundary("已对账 / 已撤销的分配单不可再改",
                "11.2.2.2",
                "已退数量是资金相关的累计量；「对账完又撤销」会让差额说明与已退数量互相矛盾，"
                    + "而这类矛盾在财务对账时几乎无法自证是哪一次操作造成的。")),
        "ALLOCATED → RECONCILED（超收/少收/超期已结转）"
            + " / ALLOCATED → REVERSED（已退数量回冲）。"
            + "RECONCILED 与 REVERSED 均为**终态**，不可再转（含不可转回 ALLOCATED）。"
            + "⚠️ 没有 PARTIAL / PENDING：分配是一次性动作，"
            + "实收行的数量要么全部落到订单行上，要么整张撤销。",
        "★ 蓝图加粗句「按收货事件去重分配，同一批退货不重复记已收数量」，钉在三层："
            + "① 幂等键 UNIQUE(tenant_id, idempotency_key)，键形恒为 'RECEIPT:' + receipt_id；"
            + "② 触发器 guard_return_allocation_line 在写入时刻再真查一次并报可读错误；"
            + "③ 已退数量由**状态变化**触发累加（不是 INSERT 即累加）—— "
            + "即使有人绕过 ①② 硬插一张重复分配单，初始状态就是 ALLOCATED，累加不执行。"
            + "重复提交同一收货事件时，服务层**返回同一张分配单**（幂等），不新建、不重复累加。",
        "★ 分配数**不被截断**：授权 1 件、货来了 3 件，allocatedQty 仍然是 3。"
            + "超收量与金额差由 **DB 触发器**算出（服务层只读不算），落 over_received_qty 与 "
            + "variance_amount（NUMERIC，禁浮点记账），并伴随一条 OVER_RECEIVED 说明。"
            + "少收同理，金额差为**负**（少收的钱要补）。处置结论只有 PENDING_REVIEW / "
            + "ACCEPTED_AS_ACTUAL，**刻意没有「已解决」**。",
        "★ 判定锚点是寄回时限 return_by_at（rma_authorization，V161），"
            + "比较对象是**收货事件时刻 received_at**（不是分配时刻）。"
            + "return_by_at 存**快照**。⚠️ 超期**不拒绝**：货已经收到了，"
            + "退回商品默认进待检（11.2.2.1 硬规则），拒收等于让货无处可去 —— "
            + "超期是**可解释**的事实，不是错误。",
        "已退数量落成本模块**拥有**的 order_line_returned_qty（不 ALTER sales_order_line）。"
            + "运行角色对该表的 UPDATE/DELETE 已在 DB 层收回，服务层也**没有**任何直接写它的代码路径 "
            + "⇒「谁把已退数量改了」在本模块里只有一条代码路径。"
            + "⚠️ 没退过的订单行在这张表里**没有行**（不填 0 冒充「没退过」）。"
            + "已分配量与已退量分列：撤销后两者的差额就是财务要追问的那部分。"
            + "⚠️ 9 订单按授权 API 读它，消费侧**未交付**。");
  }

  // ================================================================ 列表

  /** 列表：真分页 + 多维筛选 + 排序白名单 + 全量汇总。 */
  public ReturnAllocationPage list(ReturnAllocationListQuery query) {
    int page = query.page() == null || query.page() < 0 ? 0 : query.page();
    int size = query.size() == null || query.size() <= 0 ? 20 : Math.min(query.size(), MAX_PAGE_SIZE);
    ReturnAllocationSort sort = ReturnAllocationSort.parse(query.sort());

    List<Object> binds = new ArrayList<>();
    List<String> where = conditions(query, binds);
    String clause = where.isEmpty() ? "" : " where " + String.join(" and ", where);

    String fromBase =
        " from return_receipt_allocation a"
            + " left join return_receipt r"
            + "   on r.tenant_id = a.tenant_id and r.id = a.receipt_id"
            + " left join rma_authorization m"
            + "   on m.tenant_id = a.tenant_id and m.id = a.rma_id"
            + " left join aftersales_case c"
            + "   on c.tenant_id = a.tenant_id and c.id = a.case_id"
            + " left join sales_order o"
            + "   on o.tenant_id = a.tenant_id and o.id = a.order_id";
    String from =
        fromBase
            + " left join lateral ("
            + "   select count(*) as line_count from return_receipt_allocation_line l"
            + "    where l.tenant_id = a.tenant_id and l.allocation_id = a.id) ln on true"
            + " left join lateral ("
            + "   select count(*) as variance_count from return_receipt_allocation_variance v"
            + "    where v.tenant_id = a.tenant_id and v.allocation_id = a.id) vr on true";

    long total =
        sql.fetchOne("select count(*)" + fromBase + clause, binds.toArray()).get(0, Long.class);

    // ⚠️ 占位符顺序必须与 SQL **文本出现顺序**一致：[where 里的] → [limit] → [offset]。
    //   jOOQ 按位置绑定，顺序错了不会报错、只会把值绑到别的 ? 上 —— 那是最难查的一类 bug。
    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(size);
    pageBinds.add((long) page * size);

    List<ReturnAllocationRow> rows =
        sql.fetch(
                "select a.*, r.receipt_no, m.rma_no, c.case_no, o.order_no,"
                    + " ln.line_count, vr.variance_count"
                    + from
                    + clause
                    + " order by "
                    + sort.sql()
                    + " limit ? offset ?",
                pageBinds.toArray())
            .map(this::row);

    return new ReturnAllocationPage(
        rows,
        page,
        size,
        total,
        summary(where, binds),
        OffsetDateTime.now(ZoneOffset.UTC),
        "真分页（LIMIT/OFFSET + 独立 COUNT），汇总与排序不受分页影响。",
        "★ 每一行的 allocatedQty 都可能被 overReceivedQty / shortReceivedQty 修正 —— "
            + "**不得静默截断**：授权 1 件、货来了 3 件时 allocatedQty 是 3 而不是 1。"
            + "varianceAmount 是金额差（正 = 超收要退钱，负 = 少收要补钱），禁浮点记账。",
        "排序白名单四个键（allocated_desc / received_asc / status_asc / overdue_first）；"
            + "其余取值落回 allocated_desc，不接受任意表达式。"
            + "overdue_first 把超期退回排在最前 —— 那是运营每天要盯的那一批。");
  }

  private List<String> conditions(ReturnAllocationListQuery query, List<Object> binds) {
    List<String> where = new ArrayList<>();
    where.add("a.tenant_id = ?::uuid");
    binds.add(currentTenant());
    // ⚠️ 筛选值**先过枚举**再进 SQL：直接绑一个拼错的状态值，表里恰好没有匹配的行时
    //   表级 CHECK 根本不会被求值，结果是静默返回零行 ——
    //   运营会读成「没有这样的分配单」，而真相是筛选条件本身写错了。
    if (isSet(query.status())) {
      where.add("a.allocation_status = ?");
      binds.add(parseEnum(ReturnAllocationStatus.class, query.status(), "分配状态").name());
    }
    if (query.receiptId() != null) {
      where.add("a.receipt_id = ?::uuid");
      binds.add(query.receiptId());
    }
    if (query.rmaId() != null) {
      where.add("a.rma_id = ?::uuid");
      binds.add(query.rmaId());
    }
    if (query.caseId() != null) {
      where.add("a.case_id = ?::uuid");
      binds.add(query.caseId());
    }
    if (query.orderId() != null) {
      where.add("a.order_id = ?::uuid");
      binds.add(query.orderId());
    }
    if (Boolean.TRUE.equals(query.overdueOnly())) {
      where.add("a.overdue_returned and a.allocation_status <> 'REVERSED'");
    }
    if (Boolean.TRUE.equals(query.varianceOnly())) {
      where.add(
          "(a.over_received_qty > 0 or a.short_received_qty > 0"
              + " or exists (select 1 from return_receipt_allocation_variance v"
              + "   where v.tenant_id = a.tenant_id and v.allocation_id = a.id))");
    }
    if (isSet(query.keyword())) {
      String like = "%" + query.keyword().trim() + "%";
      // 真实模糊检索：分配单号 / 收货单号 / 授权单号 / 售后单号 / 订单号。
      where.add("(a.allocation_no ilike ? or r.receipt_no ilike ? or m.rma_no ilike ?"
          + " or c.case_no ilike ? or o.order_no ilike ?)");
      binds.add(like);
      binds.add(like);
      binds.add(like);
      binds.add(like);
      binds.add(like);
    }
    return where;
  }

  private ReturnAllocationRow row(Record r) {
    ReturnAllocationStatus st =
        ReturnAllocationStatus.valueOf(r.get("allocation_status", String.class));
    return new ReturnAllocationRow(
        r.get("id", UUID.class),
        r.get("allocation_no", String.class),
        r.get("receipt_id", UUID.class),
        r.get("receipt_no", String.class),
        r.get("rma_id", UUID.class),
        r.get("rma_no", String.class),
        r.get("case_id", UUID.class),
        r.get("case_no", String.class),
        r.get("order_id", UUID.class),
        r.get("order_no", String.class),
        r.get("received_at", OffsetDateTime.class),
        r.get("return_by_at", OffsetDateTime.class),
        r.get("overdue_returned", Boolean.class),
        r.get("days_overdue", Integer.class),
        st.name(),
        st.label(),
        r.get("allocated_qty", BigDecimal.class),
        r.get("over_received_qty", BigDecimal.class),
        r.get("short_received_qty", BigDecimal.class),
        r.get("variance_amount", BigDecimal.class),
        r.get("line_count", Integer.class),
        r.get("variance_count", Integer.class),
        r.get("version", Long.class));
  }

  /**
   * 列表页汇总（真分页之外的全量口径）。
   *
   * <p>⚠️ 与 {@link #list} 的 where/binds 共用同一份条件列表：汇总与列表页口径一致，
   * 翻页时汇总不会跳。
   */
  private ReturnAllocationSummary summary(List<String> where, List<Object> binds) {
    String clause = where.isEmpty() ? "" : " where " + String.join(" and ", where);
    Record r =
        sql.fetchOne(
            "select count(*) as total,"
                + " count(*) filter (where a.allocation_status = 'ALLOCATED') as allocated,"
                + " count(*) filter (where a.allocation_status = 'RECONCILED') as reconciled,"
                + " count(*) filter (where a.allocation_status = 'REVERSED') as reversed,"
                + " count(*) filter (where a.overdue_returned and a.allocation_status <> 'REVERSED') as overdue,"
                + " count(*) filter (where a.over_received_qty > 0) as with_over,"
                + " count(*) filter (where a.short_received_qty > 0) as with_under,"
                + " coalesce(sum(a.allocated_qty), 0) as allocated_qty,"
                + " coalesce(sum(a.over_received_qty), 0) as over_qty,"
                + " coalesce(sum(a.short_received_qty), 0) as under_qty,"
                + " coalesce(sum(a.variance_amount), 0) as variance_amount"
                + " from return_receipt_allocation a"
                + " left join return_receipt r"
                + "   on r.tenant_id = a.tenant_id and r.id = a.receipt_id"
                + " left join rma_authorization m"
                + "   on m.tenant_id = a.tenant_id and m.id = a.rma_id"
                + " left join aftersales_case c"
                + "   on c.tenant_id = a.tenant_id and c.id = a.case_id"
                + " left join sales_order o"
                + "   on o.tenant_id = a.tenant_id and o.id = a.order_id"
                + clause,
            binds.toArray());

    // 「已分配但不在任何有效分配单上」的量（= 已分配量 − 已退量）：
    // 跨全表算，不能用分页里的 lateral —— 「待对账的差额」是页面上最该盯的那一格，
    // 它不能因为翻到第二页就变小。
    BigDecimal unreconciled =
        sql.fetchOne(
                "select coalesce(sum(allocated_qty - returned_qty), 0)"
                    + " from order_line_returned_qty where tenant_id = ?::uuid",
                currentTenant())
            .get(0, BigDecimal.class);

    return new ReturnAllocationSummary(
        r.get("total", Long.class),
        r.get("allocated", Long.class),
        r.get("reconciled", Long.class),
        r.get("reversed", Long.class),
        r.get("overdue", Long.class),
        r.get("with_over", Long.class),
        r.get("with_under", Long.class),
        r.get("allocated_qty", BigDecimal.class),
        r.get("over_qty", BigDecimal.class),
        r.get("under_qty", BigDecimal.class),
        r.get("variance_amount", BigDecimal.class),
        unreconciled == null ? BigDecimal.ZERO.setScale(QTY_SCALE) : unreconciled);
  }

  // ================================================================ 详情

  /** 单张分配全景。**不存在的 id 与不属于本租户的 id 返回同一条错误**。 */
  public ReturnAllocationDetail detail(UUID id) {
    Record head =
        sql.fetchOne(
            "select a.*, r.receipt_no, r.posted_at, m.rma_no, c.case_no, o.order_no"
                + " from return_receipt_allocation a"
                + " join return_receipt r on r.tenant_id = a.tenant_id and r.id = a.receipt_id"
                + " join rma_authorization m on m.tenant_id = a.tenant_id and m.id = a.rma_id"
                + " join aftersales_case c on c.tenant_id = a.tenant_id and c.id = a.case_id"
                + " left join sales_order o on o.tenant_id = a.tenant_id and o.id = a.order_id"
                + " where a.tenant_id = ?::uuid and a.id = ?::uuid",
            currentTenant(),
            id);
    if (head == null) {
      throw new IllegalArgumentException("实收分配单不存在或不属于当前租户：" + id);
    }

    List<ReturnAllocationLine> lines =
        sql.fetch(
                "select l.* from return_receipt_allocation_line l"
                    + " where l.tenant_id = ?::uuid and l.allocation_id = ?::uuid"
                    + " order by l.line_no, l.id",
                currentTenant(),
                id)
            .map(this::line);

    List<ReturnAllocationVariance> variances =
        sql.fetch(
                "select * from return_receipt_allocation_variance"
                    + " where tenant_id = ?::uuid and allocation_id = ?::uuid"
                    + " order by occurred_at, id",
                currentTenant(),
                id)
            .map(this::variance);

    List<ReturnAllocationEvent> events =
        sql.fetch(
                "select * from return_receipt_allocation_event"
                    + " where tenant_id = ?::uuid and allocation_id = ?::uuid"
                    + " order by occurred_at, id",
                currentTenant(),
                id)
            .map(
                e ->
                    new ReturnAllocationEvent(
                        e.get("id", UUID.class),
                        e.get("event_type", String.class),
                        eventLabel(e.get("event_type", String.class)),
                        e.get("from_status", String.class),
                        e.get("to_status", String.class),
                        e.get("reason_note", String.class),
                        e.get("occurred_by", UUID.class),
                        e.get("occurred_at", OffsetDateTime.class)));

    List<OrderLineReturnedQty> orderLineReturns = orderLineReturns(head);

    BigDecimal allocated = head.get("allocated_qty", BigDecimal.class);
    BigDecimal over = head.get("over_received_qty", BigDecimal.class);
    BigDecimal under = head.get("short_received_qty", BigDecimal.class);
    BigDecimal amount = head.get("variance_amount", BigDecimal.class);

    return new ReturnAllocationDetail(
        head(head),
        lines,
        variances,
        orderLineReturns,
        events,
        allocated,
        over,
        under,
        amount,
        head.get("overdue_returned", Boolean.class),
        head.get("days_overdue", Integer.class),
        "★ 分配数**不被截断**（蓝图「实收多于授权按实际退回」）："
            + "authorizedQty 与 takenQty 是**分配时刻**的快照，"
            + "而 overReceivedQty / shortReceivedQty / varianceAmount 由 **DB 触发器**算出。"
            + "⚠️ 同一收货事件重复提交**返回本单**，不新建、不重复累加已退数量。",
        actions(head, over, under),
        capabilities().relatedModules(),
        OffsetDateTime.now(ZoneOffset.UTC),
        "已退数量（orderLineReturns）是本模块的写入所有权，只能经分配动作变更 —— "
            + "运行角色的 UPDATE/DELETE 已在 DB 层收回。");
  }

  private ReturnAllocationHead head(Record r) {
    ReturnAllocationStatus st =
        ReturnAllocationStatus.valueOf(r.get("allocation_status", String.class));
    return new ReturnAllocationHead(
        r.get("id", UUID.class),
        r.get("allocation_no", String.class),
        r.get("idempotency_key", String.class),
        r.get("receipt_id", UUID.class),
        r.get("receipt_no", String.class),
        r.get("received_at", OffsetDateTime.class),
        r.get("posted_at", OffsetDateTime.class),
        r.get("rma_id", UUID.class),
        r.get("rma_no", String.class),
        r.get("case_id", UUID.class),
        r.get("case_no", String.class),
        r.get("order_id", UUID.class),
        r.get("order_no", String.class),
        r.get("return_by_at", OffsetDateTime.class),
        r.get("overdue_returned", Boolean.class),
        r.get("days_overdue", Integer.class),
        st.name(),
        st.label(),
        r.get("allocated_qty", BigDecimal.class),
        r.get("over_received_qty", BigDecimal.class),
        r.get("short_received_qty", BigDecimal.class),
        r.get("variance_amount", BigDecimal.class),
        lineCount(r.get("id", UUID.class)),
        varianceCount(r.get("id", UUID.class)),
        r.get("allocated_by", UUID.class),
        r.get("allocated_at", OffsetDateTime.class),
        r.get("reconciled_by", UUID.class),
        r.get("reconciled_at", OffsetDateTime.class),
        r.get("reversed_by", UUID.class),
        r.get("reversed_at", OffsetDateTime.class),
        r.get("reverse_reason", String.class),
        r.get("reconcile_note", String.class),
        r.get("version", Long.class));
  }

  private ReturnAllocationLine line(Record r) {
    BigDecimal authorized = r.get("target_authorized_qty", BigDecimal.class);
    BigDecimal taken = r.get("target_taken_qty", BigDecimal.class);
    // ⚠️ 剩余可收 = 授权量 − 已被**其它收货事件**占用。
    //   可能为负（别的收货事件已经超收过），界面必须显式显示成「超出授权 N 件」，
    //   不要悄悄 clamp 到 0 —— clamp 掉之后超收就在界面上消失了，而它恰恰最该被看见。
    BigDecimal remaining = authorized.subtract(taken);
    return new ReturnAllocationLine(
        r.get("id", UUID.class),
        r.get("receipt_line_id", UUID.class),
        r.get("source_rma_authorization_line_id", UUID.class),
        r.get("target_rma_authorization_line_id", UUID.class),
        r.get("case_line_id", UUID.class),
        r.get("order_line_id", UUID.class),
        r.get("sku_id", UUID.class),
        r.get("sku_code", String.class),
        r.get("sku_name", String.class),
        r.get("allocated_qty", BigDecimal.class),
        authorized,
        taken,
        remaining,
        r.get("over_received_qty", BigDecimal.class),
        r.get("short_received_qty", BigDecimal.class),
        r.get("variance_amount", BigDecimal.class),
        r.get("overdue_returned", Boolean.class),
        r.get("variance_note", String.class),
        r.get("allocated_by", UUID.class),
        r.get("allocated_at", OffsetDateTime.class));
  }

  private ReturnAllocationVariance variance(Record r) {
    ReturnVarianceType t = ReturnVarianceType.valueOf(r.get("variance_type", String.class));
    ReturnVarianceResolution res =
        ReturnVarianceResolution.valueOf(r.get("resolution", String.class));
    return new ReturnAllocationVariance(
        r.get("id", UUID.class),
        r.get("allocation_line_id", UUID.class),
        t.name(),
        t.label(),
        r.get("variance_qty", BigDecimal.class),
        r.get("variance_amount", BigDecimal.class),
        emptyToNull(r.get("currency", String.class)),
        r.get("explanation", String.class),
        res.name(),
        res.label(),
        r.get("resolution_note", String.class),
        r.get("occurred_by", UUID.class),
        r.get("occurred_at", OffsetDateTime.class));
  }

  private int lineCount(UUID allocationId) {
    return sql.fetchOne(
            "select count(*) from return_receipt_allocation_line"
                + " where tenant_id = ?::uuid and allocation_id = ?::uuid",
            currentTenant(),
            allocationId)
        .get(0, Integer.class);
  }

  private int varianceCount(UUID allocationId) {
    return sql.fetchOne(
            "select count(*) from return_receipt_allocation_variance"
                + " where tenant_id = ?::uuid and allocation_id = ?::uuid",
            currentTenant(),
            allocationId)
        .get(0, Integer.class);
  }

  // ================================================================ 订单行已退数量

  /** 某张订单的已退数量（下游 9 订单的取数面）。 */
  public OrderReturnedQtyPage orderReturns(UUID orderId) {
    Record head =
        sql.fetchOne(
            "select o.order_no from sales_order o where o.tenant_id = ?::uuid and o.id = ?::uuid",
            currentTenant(),
            orderId);
    if (head == null) {
      throw new IllegalArgumentException("销售订单不存在或不属于当前租户：" + orderId);
    }
    List<OrderLineReturnedQty> lines = orderLineReturnsOf(orderId);
    BigDecimal ordered =
        lines.stream()
            .map(l -> l.orderedQty() == null ? BigDecimal.ZERO : l.orderedQty())
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal returned =
        lines.stream().map(OrderLineReturnedQty::returnedQty).reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal allocated =
        lines.stream().map(OrderLineReturnedQty::allocatedQty).reduce(BigDecimal.ZERO, BigDecimal::add);
    int overOrdered =
        (int) lines.stream().filter(OrderLineReturnedQty::exceedsOrderedQty).count();

    return new OrderReturnedQtyPage(
        orderId,
        lines,
        ordered,
        returned,
        allocated,
        overOrdered,
        OffsetDateTime.now(ZoneOffset.UTC),
        "⚠️ **只列出有过退款的订单行**：没退过的行在这张表里没有行，"
            + "刻意**不补 0 行** —— 补 0 会让「这行从来没退过」在重复退货面前失去唯一提醒。"
            + "要拿全量订单行请走订单详情接口自行比对。\n"
            + "⚠️ 本页是 9 订单按授权 API 的取数面（消费侧未交付）；"
            + "returnedQty 只能经分配动作变更，运行角色的 UPDATE/DELETE 已在 DB 层收回。");
  }

  private List<OrderLineReturnedQty> orderLineReturns(Record head) {
    return orderLineReturnsOf(head.get("order_id", UUID.class));
  }

  private List<OrderLineReturnedQty> orderLineReturnsOf(UUID orderId) {
    // ⚠️ order_line_returned_qty 上**没有** line_no（它是派生事实表，不是订单行本体），
    //   按它排会直接报「column does not exist」。要按订单行顺序展示请走订单详情接口。
    return sql.fetch(
            "select * from order_line_returned_qty"
                + " where tenant_id = ?::uuid and order_id = ?::uuid"
                + " order by order_line_id",
            currentTenant(),
            orderId)
        .map(this::returned);
  }

  private OrderLineReturnedQty returned(Record r) {
    BigDecimal ordered = r.get("ordered_qty", BigDecimal.class);
    BigDecimal returnedQty = r.get("returned_qty", BigDecimal.class);
    BigDecimal allocatedQty = r.get("allocated_qty", BigDecimal.class);
    // 已分配但不在任何有效分配单上：撤销分配后两者不同步回退，
    // 差额就是财务要追问的那部分。
    BigDecimal unreconciled = allocatedQty.subtract(returnedQty);
    BigDecimal overDiff = ordered == null ? BigDecimal.ZERO : returnedQty.subtract(ordered);
    BigDecimal overOrdered = overDiff.signum() > 0 ? overDiff : BigDecimal.ZERO;
    return new OrderLineReturnedQty(
        r.get("order_line_id", UUID.class),
        r.get("order_id", UUID.class),
        r.get("sku_id", UUID.class),
        r.get("sku_code", String.class),
        r.get("sku_name", String.class),
        ordered,
        returnedQty,
        allocatedQty,
        unreconciled,
        overOrdered,
        overOrdered.signum() > 0,
        r.get("last_allocation_id", UUID.class),
        r.get("last_allocated_at", OffsetDateTime.class),
        r.get("allocation_count", Integer.class),
        r.get("version", Long.class));
  }

  // ================================================================ 写：分配（硬规则① 的落点）

  /**
   * 按收货事件分配（蓝图「把实收退货分配到对应的退货授权行与原订单行」）。
   *
   * <p>★ <b>硬规则①「按收货事件去重」在服务层的落点</b>：
   * 同一收货事件<b>重复提交返回同一张分配单</b>，不新建、不重复累加已退数量。
   * 这不是「友好」—— 是资损防线：已退数量是可退余额的事实来源，
   * 多累加一次就是多给了一次退款额度。
   *
   * <p>⚠️ <b>服务层先查一次已存在的分配</b>，把「重复提交」变成一次幂等返回；
   * DB 层的三道闸门（唯一约束 + 触发器 + 状态变化才累加）是第二、第三道 ——
   * 绕过服务层直连 SQL 也一样进不来。
   */
  public ReturnAllocationDetail create(ReturnAllocationCreate request, UUID userId) {
    if (request.receiptId() == null) {
      throw new IllegalArgumentException("必须给出收货事件（receiptId）：分配的对象只能是实收记录");
    }
    if (request.lines() == null || request.lines().isEmpty()) {
      throw new IllegalArgumentException("至少要给一条分配行：一笔不做任何分配的收货事件等于白收");
    }

    // ---- 硬规则① 第一层（服务层）：重复提交 → 幂等返回同一张单 ----
    Record existing =
        sql.fetchOne(
            "select id from return_receipt_allocation where tenant_id = ?::uuid and receipt_id = ?::uuid",
            currentTenant(),
            request.receiptId());
    if (existing != null) {
      return detail(existing.get("id", UUID.class));
    }

    Record receipt =
        sql.fetchOne(
            "select r.*, m.case_id, m.return_by_at, c.order_id"
                + " from return_receipt r"
                + " join rma_authorization m on m.tenant_id = r.tenant_id and m.id = r.rma_id"
                + " join aftersales_case c on c.tenant_id = m.tenant_id and c.id = m.case_id"
                + " where r.tenant_id = ?::uuid and r.id = ?::uuid",
            currentTenant(),
            request.receiptId());
    if (receipt == null) {
      throw new IllegalArgumentException("退货实收单不存在或不属于当前租户：" + request.receiptId());
    }
    // 服务层先拦一道（DB 触发器还有一道）：未入库的实收单不分配。
    // 实收还没入库 ⇒ 货没进待检 ⇒ 已退数量先走一步会让两份真相。
    String receiptStatus = receipt.get("receipt_status", String.class);
    if (!"POSTED".equals(receiptStatus)) {
      throw new IllegalArgumentException(
          "只有已入库的退货实收单才能分配（当前 "
              + receiptStatus
              + "）：实收还没入库，已退数量先走一步会让待检分录与已退数量变成两份真相");
    }

    // ---- 超期退回：比较的是**收货时刻**与**寄回时限**，不是分配时刻 ----
    // ⚠️ return_by_at 取**快照**：分配写完、授权单可能后续被关闭，
    //   判定「当时批的时候约定了什么时候寄回」用快照才答得出当时的事实。
    OffsetDateTime receivedAt = receipt.get("received_at", OffsetDateTime.class);
    OffsetDateTime returnByAt = receipt.get("return_by_at", OffsetDateTime.class);
    boolean overdue = returnByAt != null && receivedAt.isAfter(returnByAt);
    int daysOverdue =
        overdue ? (int) java.time.Duration.between(returnByAt, receivedAt).toDays() : 0;

    UUID allocationId = UUID.randomUUID();
    String no = nextAllocationNo();
    OffsetDateTime at = OffsetDateTime.now(ZoneOffset.UTC);

    // ⚠️ 幂等键**由服务层派生**、不接受调用方给定；DB 侧还有一条
    //   CHECK(idempotency_key = 'RECEIPT:' || receipt_id::text) 钉住形状。
    sql.execute(
        "insert into return_receipt_allocation(id,tenant_id,allocation_no,idempotency_key,"
            + "receipt_id,rma_id,case_id,order_id,return_by_at,received_at,overdue_returned,"
            + "days_overdue,allocation_status,allocated_qty,over_received_qty,"
            + "short_received_qty,variance_amount,allocated_by,allocated_at,version,created_at,updated_at)"
            + " values(?,?::uuid,?,?,?::uuid,?::uuid,?::uuid,?::uuid,?::timestamptz,?::timestamptz,?,"
            + "?,'ALLOCATED',?::numeric,0,0,0,?::uuid,?::timestamptz,0,?::timestamptz,?::timestamptz)",
        allocationId,
        currentTenant(),
        no,
        "RECEIPT:" + request.receiptId(),
        request.receiptId(),
        receipt.get("rma_id", UUID.class),
        receipt.get("case_id", UUID.class),
        receipt.get("order_id", UUID.class),
        returnByAt,
        receivedAt,
        overdue,
        daysOverdue,
        BigDecimal.ZERO.setScale(QTY_SCALE),
        userId,
        at,
        at,
        at);

    insertLines(allocationId, request.lines(), userId, at, overdue);

    // 汇总由分配行真查一次算出来（**不是**在内存里加一遍）：
    // 超收/少收量与金额差由 DB 触发器写在行上，这里只做聚合口径。
    return rollup(allocationId, userId);
  }

  private void insertLines(
      UUID allocationId,
      List<ReturnAllocationLineDraft> drafts,
      UUID userId,
      OffsetDateTime at,
      boolean overdue) {
    int lineNo = 1;
    for (ReturnAllocationLineDraft d : drafts) {
      if (d.receiptLineId() == null) {
        throw new IllegalArgumentException("每行分配必须给出 receiptLineId：分配的对象只能是实收行");
      }
      BigDecimal qty = qty(d.allocatedQty());
      if (qty.signum() <= 0) {
        throw new IllegalArgumentException("分配数量必须大于 0");
      }

      // 目标授权行与目标订单行都**实时读真值**（可显式改指 = 跨行分配）。
      // ⚠️ 不在分配行上复制 SKU —— 分配行的 sku_code / sku_name 是**凭据快照**
      //   （它要交给下游 9 订单，订单行改名之后仍要读得出退的是哪个 SKU），
      //   但**判定**用的授权量一律实时读，不读快照。
      Record chain =
          sql.fetchOne(
              "select l.id as rma_line_id, l.rma_id, cl.id as case_line_id, cl.order_line_id,"
                  + " ol.sku_id, ol.sku_code, ol.sku_name"
                  + " from rma_authorization_line l"
                  + " join aftersales_case_line cl on cl.tenant_id = l.tenant_id and cl.id = l.case_line_id"
                  + " join sales_order_line ol on ol.tenant_id = cl.tenant_id and ol.id = cl.order_line_id"
                  + " where l.tenant_id = ?::uuid and l.id = ?::uuid",
              currentTenant(),
              resolveRmaLine(d, allocationId));
      if (chain == null) {
        throw new IllegalArgumentException(
            "目标退货授权行不存在或不属于当前租户：" + resolveRmaLine(d, allocationId));
      }

      UUID orderLineId = d.orderLineId() == null ? chain.get("order_line_id", UUID.class) : d.orderLineId();

      sql.execute(
          "insert into return_receipt_allocation_line(id,tenant_id,allocation_id,receipt_line_id,"
              + "source_rma_authorization_line_id,order_line_id,case_line_id,"
              + "target_rma_authorization_line_id,sku_id,sku_code,sku_name,allocated_qty,"
              + "overdue_returned,allocated_by,allocated_at,line_no,created_at)"
              + " values(?,?::uuid,?::uuid,?::uuid,?::uuid,?::uuid,?::uuid,?::uuid,?::uuid,?,?,"
              + "?::numeric,?,?::uuid,?::timestamptz,?,?::timestamptz)",
          UUID.randomUUID(),
          currentTenant(),
          allocationId,
          d.receiptLineId(),
          resolveRmaLine(d, allocationId),
          orderLineId,
          chain.get("case_line_id", UUID.class),
          resolveRmaLine(d, allocationId),
          chain.get("sku_id", UUID.class),
          chain.get("sku_code", String.class),
          chain.get("sku_name", String.class),
          qty,
          overdue,
          userId,
          at,
          lineNo++,
          at);
    }
  }

  /** 目标授权行：显式指定则用指定的，否则取该实收行登记时挂的那一行。 */
  private UUID resolveRmaLine(ReturnAllocationLineDraft d, UUID allocationId) {
    if (d.targetRmaLineId() != null) {
      return d.targetRmaLineId();
    }
    Record r =
        sql.fetchOne(
            "select rl.rma_authorization_line_id"
                + " from return_receipt_allocation a"
                + " join return_receipt_line rl"
                + "   on rl.tenant_id = a.tenant_id and rl.receipt_id = a.receipt_id"
                + " where a.tenant_id = ?::uuid and a.id = ?::uuid and rl.id = ?::uuid",
            currentTenant(),
            allocationId,
            d.receiptLineId());
    if (r == null) {
      throw new IllegalArgumentException(
          "找不到实收行，或该实收行不属于这次分配对应的收货事件：" + d.receiptLineId());
    }
    return r.get("rma_authorization_line_id", UUID.class);
  }

  /**
   * 分配行落完后把头部的汇总**真查一次**算出来。
   *
   * <p>⚠️ 刻意<b>不在内存里加一遍</b>：超收/少收量与金额差是 DB 触发器算的
   * （服务层只读不算），所以汇总必须回查，否则头与行会对不上 ——
   * 而「头说没超收、行说超收了」是财务对账时最难查的一类不一致。
   */
  private ReturnAllocationDetail rollup(UUID allocationId, UUID userId) {
    Record agg =
        sql.fetchOne(
            "select coalesce(sum(allocated_qty), 0) as allocated_qty,"
                + " coalesce(sum(over_received_qty), 0) as over_qty,"
                + " coalesce(sum(short_received_qty), 0) as under_qty,"
                + " coalesce(sum(variance_amount), 0) as amount"
                + " from return_receipt_allocation_line"
                + " where tenant_id = ?::uuid and allocation_id = ?::uuid",
            currentTenant(),
            allocationId);

    BigDecimal allocated = agg.get("allocated_qty", BigDecimal.class);
    if (allocated == null || allocated.signum() <= 0) {
      throw new IllegalArgumentException("分配单一条有效分配行都没有，不该存在");
    }

    sql.execute(
        "update return_receipt_allocation set allocated_qty = ?::numeric,"
            + " over_received_qty = ?::numeric, short_received_qty = ?::numeric,"
            + " variance_amount = ?::numeric, updated_at = ?::timestamptz"
            + " where tenant_id = ?::uuid and id = ?::uuid and allocation_status = 'ALLOCATED'",
        allocated,
        agg.get("over_qty", BigDecimal.class),
        agg.get("under_qty", BigDecimal.class),
        agg.get("amount", BigDecimal.class),
        OffsetDateTime.now(ZoneOffset.UTC),
        currentTenant(),
        allocationId);

    return detail(allocationId);
  }

  // ================================================================ 写：对账（硬规则②）

  /**
   * 对账：把超收 / 少收 / 超期退回三种情形结转成说明（蓝图输出句「超收/少收说明」）。
   *
   * <p>⚠️ <b>对账不改已退数量</b>：已退数量是累计口径，超收/少收只是结转说明。
   * 把差额算进已退数量会让「已退」这个数同时包含「真实收到的」和「还在核对的」，
   * 两者口径必须分开。
   *
   * <p>⚠️ 三种情形**各自独立**给出处置结论与说明正文：
   * 合成一句 note 会让「超收已认可、少收还没核实」显示成一团。
   */
  public ReturnAllocationDetail reconcile(
      UUID id, ReturnAllocationReconcile request, UUID userId) {
    Record head = requireOpen(id);
    requireVersion(head, request.expectedVersion());
    OffsetDateTime at = OffsetDateTime.now(ZoneOffset.UTC);

    BigDecimal over = head.get("over_received_qty", BigDecimal.class);
    BigDecimal under = head.get("short_received_qty", BigDecimal.class);
    boolean overdue = head.get("overdue_returned", Boolean.class);

    // ⚠️ 金额差按类型**分别**从分配行聚合，不用头上的净额：
    //   头.variance_amount 是 over − short 的净额，直接取绝对值再取负，
    //   会让「超收 2 件 + 少收 1 件」的少收金额被超收金额污染。
    //   行级 variance_amount 的符号由表级 CHECK 钉死（超收为正、少收为负）。
    Record amounts =
        sql.fetchOne(
            "select coalesce(sum(greatest(variance_amount, 0)), 0) as over_amount,"
                + " coalesce(sum(least(variance_amount, 0)), 0) as under_amount"
                + " from return_receipt_allocation_line"
                + " where tenant_id = ?::uuid and allocation_id = ?::uuid",
            currentTenant(),
            id);
    BigDecimal overAmount = amounts.get("over_amount", BigDecimal.class);
    BigDecimal underAmount = amounts.get("under_amount", BigDecimal.class);

    // ⚠️ 只为**真实存在**的差额写说明：为不存在的差额写一条「超收 0 件」说明，
    //   会在对账页上凭空多出三行看起来像有问题的记录。
    if (over != null && over.signum() > 0) {
      requireAccept(request.acceptOverReceived(), request.overReceivedNote(), "超收");
      insertVariance(
          id,
          null,
          ReturnVarianceType.OVER_RECEIVED,
          over,
          overAmount,
          currencyOf(head),
          "超收 " + plain(over) + " 件：该授权行授权量已被其它退货占满，"
              + "本批按**实际退回**记账（不截断）。金额差按订单行单价 × 超收数量计。",
          request.acceptOverReceived(),
          request.overReceivedNote(),
          userId,
          at);
    }
    if (under != null && under.signum() > 0) {
      requireAccept(request.acceptUnderReceived(), request.underReceivedNote(), "少收");
      insertVariance(
          id,
          null,
          ReturnVarianceType.UNDER_RECEIVED,
          under,
          underAmount,
          currencyOf(head),
          "少收 " + plain(under) + " 件：实收数大于本次落到订单行上的分配数。"
              + "差额已结转，**不静默补齐** —— 需要说明这批货去哪了。",
          request.acceptUnderReceived(),
          request.underReceivedNote(),
          userId,
          at);
    }
    if (overdue) {
      requireAccept(request.acceptOverdue(), request.overdueNote(), "超期退回");
      insertVariance(
          id,
          null,
          ReturnVarianceType.OVERDUE_RETURNED,
          head.get("allocated_qty", BigDecimal.class),
          BigDecimal.ZERO.setScale(AMOUNT_SCALE),
          currencyOf(head),
          "超期退回：收货时刻晚于授权的寄回时限 "
              + head.get("days_overdue", Integer.class)
              + " 天。⚠️ 超期**不拒收** —— 货已经收到了，退回商品默认进待检（11.2.2.1 硬规则），"
              + "拒收等于让货无处可去；超期是可解释的事实，不是错误。",
          request.acceptOverdue(),
          request.overdueNote(),
          userId,
          at);
    }

    if (!isSet(request.reconcileNote())) {
      throw new IllegalArgumentException(
          "必须给出对账说明：超收/少收/超期三种情形都结了转之后，一句话讲清「差额去哪了」");
    }

    transition(
        id,
        ReturnAllocationStatus.RECONCILED,
        head,
        "reconciled_by",
        userId,
        "reconciled_at",
        at,
        "reconcile_note",
        request.reconcileNote().trim(),
        null);
    return detail(id);
  }

  /** 币种：实时读订单行（不复制），查不到就留空。 */
  private String currencyOf(Record head) {
    Record r =
        sql.fetchOne(
            "select o.currency from return_receipt_allocation a"
                + " join sales_order o on o.tenant_id = a.tenant_id and o.id = a.order_id"
                + " where a.tenant_id = ?::uuid and a.id = ?::uuid",
            currentTenant(),
            head.get("id", UUID.class));
    return r == null ? "" : emptyToNull(r.get("currency", String.class));
  }

  /**
   * 「认可」必须写清依据（表级 CHECK 同一条纪律，服务层先给一句能读懂的话）。
   *
   * <p>⚠️ 不认可（{@code false}）是**合法的**：差额挂 PENDING_REVIEW 等财务核实，
   * 对账照样能完成 —— 逼着运营当场把每一笔差额都核实掉，只会让他们随手点「认可」。
   */
  private void requireAccept(Boolean accept, String note, String label) {
    if (accept == null) {
      throw new IllegalArgumentException("必须给出" + label + "的处置结论（认可 / 待人工核实）");
    }
    if (accept && !isSet(note)) {
      throw new IllegalArgumentException(
          "「认可" + label + "」必须写清依据：超收多退的钱要追回还是认了，是财务决定");
    }
  }

  private void insertVariance(
      UUID allocationId,
      UUID lineId,
      ReturnVarianceType type,
      BigDecimal qty,
      BigDecimal amount,
      String currency,
      String explanation,
      Boolean accept,
      String note,
      UUID userId,
      OffsetDateTime at) {
    ReturnVarianceResolution res =
        Boolean.TRUE.equals(accept)
            ? ReturnVarianceResolution.ACCEPTED_AS_ACTUAL
            : ReturnVarianceResolution.PENDING_REVIEW;
    sql.execute(
        "insert into return_receipt_allocation_variance(id,tenant_id,allocation_id,allocation_line_id,"
            + "variance_type,variance_qty,variance_amount,currency,explanation,resolution,"
            + "resolution_note,occurred_by,occurred_at)"
            + " values(?,?::uuid,?::uuid,?::uuid,?,?::numeric,?::numeric,?,?,?,?,?::uuid,?::timestamptz)",
        UUID.randomUUID(),
        currentTenant(),
        allocationId,
        lineId,
        type.name(),
        qty,
        amount,
        currency == null ? "" : currency,
        explanation,
        res.name(),
        note == null ? "" : note.trim(),
        userId,
        at);
  }

  // ================================================================ 写：撤销

  /**
   * 撤销分配（跨行分配选错商品行 / 隔壁包裹串了台）。
   *
   * <p>⚠️ <b>已退数量由 DB 触发器回冲</b>，本服务<b>没有</b>任何直接写它的代码路径。
   * 已分配量<b>保留</b>：两者之差就是「已退但已不在任何有效分配单上」的部分，
   * 而那正是财务要追问的部分（表级 CHECK allocated_qty &gt;= returned_qty 也依赖这一点）。
   *
   * <p>⚠️ <b>已对账的单不可撤销</b>：差额说明与已退数量会互相矛盾，
   * 而这类矛盾在财务对账时几乎无法自证是哪一次操作造成的。
   */
  public ReturnAllocationDetail reverse(
      UUID id, ReturnAllocationReverse request, UUID userId) {
    Record head = requireOpen(id);
    requireVersion(head, request.expectedVersion());
    if (!isSet(request.reverseReason())) {
      throw new IllegalArgumentException("必须说明撤销理由：录错分配在退货场景里真实且常见");
    }
    OffsetDateTime at = OffsetDateTime.now(ZoneOffset.UTC);
    transition(
        id,
        ReturnAllocationStatus.REVERSED,
        head,
        "reversed_by",
        userId,
        "reversed_at",
        at,
        "reverse_reason",
        request.reverseReason().trim(),
        null);
    return detail(id);
  }

  /**
   * 状态流转的<b>唯一</b>入口：每次改状态都过版本校验与状态条件。
   *
   * <p>WHERE 里同时钉 {@code allocation_status = 'ALLOCATED'} 与 {@code version = ?}：
   * 只查后不改，会话之间改了状态时 UPDATE 命中 0 行却照样返回成功。
   * DB 触发器 {@code guard_return_allocation_frozen} 是第二道（显式转换规则），
   * {@code trg_return_allocation_order_qty} 在同一刻累加或回冲已退数量。
   */
  private void transition(
      UUID id,
      ReturnAllocationStatus target,
      Record head,
      String whoColumn,
      UUID userId,
      String atColumn,
      OffsetDateTime at,
      String noteColumn,
      String note,
      String unused) {
    String sqlText =
        "update return_receipt_allocation set allocation_status = ?, "
            + whoColumn
            + " = ?::uuid, "
            + atColumn
            + " = ?::timestamptz"
            + (note != null ? ", " + noteColumn + " = ?" : "")
            + ", version = version + 1, updated_at = ?::timestamptz"
            + " where tenant_id = ?::uuid and id = ?::uuid and allocation_status = ?"
            + " and version = ?";

    List<Object> binds = new ArrayList<>();
    binds.add(target.name());
    binds.add(userId);
    binds.add(at);
    if (note != null) {
      binds.add(note);
    }
    binds.add(at);
    binds.add(currentTenant());
    binds.add(id);
    binds.add(ReturnAllocationStatus.ALLOCATED.name());
    binds.add(head.get("version", Long.class));

    int updated = sql.execute(sqlText, binds.toArray());
    if (updated == 0) {
      throw new IllegalArgumentException(
          "实收分配单状态或版本已变（可能被并发改过），请刷新后重试：" + id);
    }
  }

  // ================================================================ 动作

  /** 当前可执行动作。 */
  private List<ReturnAllocationAction> actions(Record head, BigDecimal over, BigDecimal under) {
    ReturnAllocationStatus status =
        ReturnAllocationStatus.valueOf(head.get("allocation_status", String.class));
    boolean open = status.open();
    String path = "/trade/receipt-allocations/" + head.get("id", UUID.class);
    boolean hasVariance =
        (over != null && over.signum() > 0) || (under != null && under.signum() > 0)
            || Boolean.TRUE.equals(head.get("overdue_returned", Boolean.class));
    return List.of(
        action("RECONCILE", "对账（超收/少收/超期）", open, "POST", path + "/reconcile", true,
            "仅 ALLOCATED", open ? null : "分配单已" + status.label() + "，是终态不能再改", null, null),
        action("REVERSE", "撤销分配", open, "POST", path + "/reverse", true, "仅 ALLOCATED",
            open ? null : "分配单已" + status.label() + "，是终态不能再改", null, null),
        action("DUPLICATE", "重复分配同一收货事件", false, null, null, false, null, null,
            "★ 按收货事件去重（蓝图加粗句）。传输层**刻意没有**这个开关，"
                + "重复提交返回**同一张**分配单。", "11.2.2.2"),
        action("WRITE_OFF", "直接改已退数量", false, null, null, false, null, null,
            "运行角色对该表的 UPDATE/DELETE 已在 DB 层收回 —— "
                + "改已退数量只能通过撤销分配。", "11.2.2.2"),
        action("MARK_RESOLVED", "把差额标记为已解决", false, null, null, false, null,
            hasVariance ? "已结转说明但结论只能是待核实 / 按实际退回认可" : null,
            "处置结论刻意没有「已解决」：超收多退的钱要追回还是认了是财务决定（11.2.3.3）。",
            "11.2.3.3"));
  }

  // ================================================================ 工具

  private String nextAllocationNo() {
    long n = sql.fetchOne("select nextval('return_allocation_no_seq')").get(0, Long.class);
    return "RAL" + n;
  }

  private UUID currentTenant() {
    return UUID.fromString(
        sql.fetchOne("select current_setting('app.current_tenant')").get(0, String.class));
  }

  private Record requireOpen(UUID id) {
    Record head =
        sql.fetchOne(
            "select * from return_receipt_allocation where tenant_id = ?::uuid and id = ?::uuid",
            currentTenant(),
            id);
    if (head == null) {
      throw new IllegalArgumentException("实收分配单不存在或不属于当前租户：" + id);
    }
    ReturnAllocationStatus status =
        ReturnAllocationStatus.valueOf(head.get("allocation_status", String.class));
    if (status.terminal()) {
      throw new IllegalArgumentException(
          "实收分配单已"
              + status.label()
              + "，是终态不能再改："
              + id
              + "（已退数量是资金相关的累计量，正了又错回来就是第二次资损）");
    }
    return head;
  }

  /** 乐观锁：版本必须与请求一致。 */
  private void requireVersion(Record head, Long expectedVersion) {
    long current = head.get("version", Long.class);
    if (expectedVersion == null) {
      throw new IllegalArgumentException("必须给出 expectedVersion（乐观锁，避免并发对账互相覆盖）");
    }
    if (expectedVersion != current) {
      throw new IllegalArgumentException(
          "版本不符（当前 " + current + "，请求 " + expectedVersion + "），请刷新后重试");
    }
  }

  private static BigDecimal qty(BigDecimal raw) {
    return raw == null ? null : raw.setScale(QTY_SCALE, RoundingMode.HALF_UP);
  }

  private static <E extends Enum<E>> E parseEnum(Class<E> type, String raw, String label) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException("必须给出" + label);
    }
    try {
      return Enum.valueOf(type, raw.trim());
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          label + "取值非法：" + raw + "（可选：" + java.util.Arrays.toString(type.getEnumConstants()) + "）");
    }
  }

  private static boolean isSet(String value) {
    return value != null && !value.isBlank();
  }

  private static String emptyToNull(String value) {
    return isSet(value) ? value.trim() : null;
  }

  /** 数量按普通十进制输出（去掉 6 位小数里的尾随 0），错误消息可读。 */
  private static String plain(BigDecimal value) {
    return value == null ? "0" : value.stripTrailingZeros().toPlainString();
  }

  private static String eventLabel(String code) {
    return switch (code) {
      case "ALLOCATED" -> "按收货事件分配";
      case "RECONCILED" -> "超收/少收/超期已结转";
      case "REVERSED" -> "人工撤销分配";
      default -> code;
    };
  }

  private static ReturnAllocationSectionCapability section(
      String section, String label, boolean available, String ownerModule, String reason,
      String landsIn) {
    return new ReturnAllocationSectionCapability(section, label, available, ownerModule, reason, landsIn);
  }

  private static ReturnAllocationRelatedModule related(
      String id, String label, boolean delivered, String ownerModule, String reason, String href) {
    return new ReturnAllocationRelatedModule(id, label, delivered, ownerModule, reason, href);
  }

  private static ReturnAllocationAction action(
      String code, String label, boolean allowed, String method, String path, boolean required,
      String precondition, String blockingReason, String ownerModule, String unavailableReason) {
    return new ReturnAllocationAction(
        code, label, allowed, method, path, required, precondition, blockingReason, ownerModule,
        unavailableReason);
  }

  private static ReturnAllocationBoundary boundary(
      String boundary, String ownerModule, String reason) {
    return new ReturnAllocationBoundary(boundary, ownerModule, reason);
  }
}
