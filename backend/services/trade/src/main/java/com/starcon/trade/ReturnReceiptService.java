package com.starcon.trade;

import static com.starcon.trade.ReturnReceiptModels.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Service;

/**
 * 11.2.2.1 退货入库。
 *
 * <p><b>本类是 V172 五张新表的写入所有者</b>（return_receipt / return_receipt_line /
 * return_receipt_photo / return_inventory_posting / return_receipt_event），
 * 对 V161 的 rma_authorization 簇、V165 的 rma_instruction 簇与订单 / 售后表<b>只读</b>。
 * 刻意不加外键、不 ALTER 既有表 ⇒ 跨车道 DDL 回归面 = 0。
 *
 * <p><b>六条硬纪律</b>：
 *
 * <ol>
 *   <li><b>每一条查询都显式带 {@code tenant_id} 谓词</b>，不依赖 RLS 当唯一防线。
 *       实收单带着<b>买家回寄运单号</b>与<b>逐件的 SKU / 序列号</b>，照片带着<b>商品外观证据</b>，
 *       而详情 / 入库 / 取分录端点都是<b>按 id 直达</b>的 URL —— 漏掉租户谓词就是跨租户泄露。</li>
 *   <li><b>退回商品默认进待检，绝不进可售</b>（硬规则 1）。本服务<b>没有</b>任何写入
 *       {@code return_inventory_posting} 的代码路径：分录由 V172 的 DB 触发器在入库那一刻生成，
 *       四个入库列全是常量或直取实收行。<b>「谁把退回商品记成了可售」在本模块里没有代码路径。</b></li>
 *   <li><b>两个判定维度分别判、分别传、分别显示</b>（硬规则 2）。入库时刻两个维度都必须已判
 *       （{@code NOT_INSPECTED} 只在草稿期允许），否则先在这里拦一道。</li>
 *   <li><b>实收挂授权行、超额报错不截断</b>（硬规则 3）：服务层与 DB 触发器<b>各算一次</b>。
 *       只算一次的话，两个并发请求各自校验时都合法，提交时总量已经超了。</li>
 *   <li><b>时间绑定</b>：时间一律 {@code ?::timestamptz} + {@link OffsetDateTime}。
 *       {@code Timestamp.valueOf(LocalDateTime)} 会静默偏 −28800s，
 *       而收货时刻偏 8 小时会让「这批货到了多久」整体算错。</li>
 *   <li><b>状态流转只有一个入口</b>（{@link #transition}）：每次改状态都过版本校验，都写留痕。</li>
 * </ol>
 */
@Service
public class ReturnReceiptService {

  /** 列表单页上限。 */
  private static final int MAX_PAGE_SIZE = 200;

  /** 待检库存状态的**唯一**取值（硬规则 1）。DB CHECK 同一条纪律，服务层这里是唯一的常量来源。 */
  private static final String PENDING_INSPECTION = "PENDING_INSPECTION";

  /** 可售增量的**唯一**取值：0（硬规则 1）。 */
  private static final BigDecimal SELLABLE_DELTA_ZERO = BigDecimal.ZERO.setScale(6);

  private final DSLContext sql;

  public ReturnReceiptService(DSLContext sql) {
    this.sql = sql;
  }

  // ================================================================ 能力声明

  /**
   * 能力声明：职责句的四个动词逐块报可用性，下游逐个报 delivered=false 与归属模块号。
   *
   * <p>让界面能说清「为什么这批货不能直接转可售」「为什么实收分配还没有」，
   * 而不是让它们显示成空白让人以为「这些能力没做」。
   */
  public ReturnReceiptCapabilities capabilities() {
    return new ReturnReceiptCapabilities(
        List.of(
            "退货授权单 rma_authorization / rma_authorization_line（V161，同库只读）—— 授权量与「批没批」的真值来源",
            "退货指引 rma_instruction（V165，同库只读）—— **可空**关联：买家自己寄回 / 平台 RMA 地址直邮 / 门店收货都可能没有一张由我们下发的指引",
            "退货实收单 return_receipt + return_receipt_line（V172；一次收货事件 = 一个包裹）",
            "拍照留证 return_receipt_photo（V172，**追加式**，运行角色 UPDATE/DELETE 已收回，带 SHA-256 去重）",
            "待检入库分录 return_inventory_posting（V172，**由 DB 触发器在入库那一刻生成**，运行角色 UPDATE/DELETE 已收回）",
            "收货留痕 return_receipt_event（V172，追加式，由 DB 触发器按状态变更自动追加）",
            "商品行真值 sales_order_line（V99 实时读，**不复制** SKU / 单价到实收行上）"),
        List.of(
            section("scanVerify", "扫码核对 SKU 与序列号", true, null,
                "扫**读到**的编码原样落库（scanned_sku_code / scanned_serial_no / batch_no），"
                    + "**不静默改写**成「正确」的 SKU —— 静默改写会让「扫错了」这件事在证据里消失，"
                    + "而质检（11.2.3.1）与批次归因（11.1.2.2）都要引用它。"
                    + "序列号与批次<b>至少要有一个</b>：两列全空的行等于「不知道这是哪一件」。",
                "return_receipt_line.scanned_sku_code / scanned_serial_no / batch_no（V172）"),
            section("countQty", "清点数量", true, null,
                "数量是 {@code NUMERIC(19,6)} + {@code BigDecimal}，**禁浮点**。"
                    + "清点数量挂在**授权行**上（rma_authorization_line_id），"
                    + "而不是挂在订单行上 —— 蓝图输出句要求「关联授权行分配」，"
                    + "而授权 3 件里退 2 件这个事实只存在于授权行上。",
                "return_receipt_line.received_qty（V172）"),
            section("photoEvidence", "拍照留证", true, null,
                "按角色分开（外包装 / 商品 / 序列号标签 / 损坏特写），"
                    + "与两个判定维度一一对应 —— 质检与纠纷举证时按角色取图。"
                    + "拍摄时刻与登记时刻<b>分两列</b>：两者之差就是「货到了多久才被拍下来」，"
                    + "压成一列就答不出「这批证据是不是补拍的」。"
                    + "⚠️ 同一内容不重复登记（sha256 唯一）：重复的举证材料会让「我们拍了几张照」有两个答案。",
                "return_receipt_photo（V172，追加式）"),
            section("packagingVsGoods", "外包装判定与商品状态判定", true, null,
                "⚠️ **两个独立维度，两列两套 CHECK 两个 label**，刻意不合成一个 condition。"
                    + "蓝图 11.2.3.1 的加粗句「**包装破损但商品完好可判可再销售，缺少配件则应判残次**」"
                    + "在任何单值列里都无法表达 —— 压成单值后质检员只能去猜「这个值说的是包装还是货」，"
                    + "而这两个维度的处置结论可能完全相反。"
                    + "配件缺失（MISSING_PARTS）刻意单列而不并进 DEFECTIVE："
                    + "配件缺常常是仓库漏装，商品损坏常常是运输或买家，索赔方向不一样。",
                "return_receipt_line.packaging_condition + goods_condition（V172）"),
            section("pendingInspectionPosting", "待检库存入库分录（不是可售）", true, null,
                "⚠️ **本模块唯一的库存事实，且只能进待检。** 三道 DB 闸门同时钉死："
                    + "① {@code inventory_state} 的 CHECK **只允许一个取值**"
                    + "（不是「待检排第一」，是表级不允许出现第二个取值）；"
                    + "② {@code sellable_qty_delta} 的 CHECK <b>恒为 0</b> —— "
                    + "让「可售增加了多少」变成一个必须被显式填 0 的字段；"
                    + "③ 分录由 <b>DB 触发器</b>在入库那一刻生成，插入语句里的入库列全是常量，"
                    + "服务层不接受调用方传入 ⇒ **绕过服务层直连 SQL 也拿不到一张「已进可售」的分录**。",
                "return_inventory_posting（V172，由 trg_guard_return_receipt_posting 生成）"),
            section("rmaLineAllocation", "关联授权行分配", true, null,
                "实收行<b>必须</b>挂到具体授权行上，且 received_qty ≤ 该行授权量 − 已被占用。"
                    + "**超出报错而不是静默截断**（硬规则 3）。"
                    + "⚠️ **草稿也占额度**：只算已入库的话，两张草稿各自校验时都合法，"
                    + "并发入库时总量已经超了。取「草稿也占」换来的是录错了要先撤销草稿 —— "
                    + "这是有意的代价，错误消息里直接写明被哪几单占了。",
                "return_receipt_line.rma_authorization_line_id + trg_guard_return_receipt_line（V172）"),
            section("allocationToOrderLine", "实收 → 订单行已退数量", false, "11.2.2.2 实收分配",
                "蓝图 11.2.2.2 的职责是「把实收退货分配到对应的退货授权行与原订单行，"
                    + "处理实收多于授权（按实际退回）、少收、超期退回等情况」，"
                    + "输出「分配记录 + 超收/少收说明；<b>订单行已退数量更新</b>」。"
                    + "本模块**未交付**它 ⇒ 已退数量当前没有落点（写入所有者是 11.2.2.2），"
                    + "本模块因此<b>不写任何已退数量</b>，也<b>不报</b>已退数量。"
                    + "⚠️ <b>超收当前的处理结果</b>：整笔拒绝（DB 触发器与本服务都拒），"
                    + "「按实际退回」的容差处理属 11.2.2.2，**未交付** —— "
                    + "在这里放行超收会让「授权 3 件、实收 5 件」变成一个无法解释的状态。",
                null),
            section("qualityInspection", "质检与判定", false, "11.2.3.1 质检与判定",
                "可再销售 / 残次 / 报废的判定与残值估算**未交付**，"
                    + "且**必须等本模块的待检分录生成**。本模块<b>没有</b> INSPECTED / RESALABLE / "
                    + "DEFECTIVE / SCRAPPED 状态 —— 编一个会让界面显示成质检已完成，"
                    + "而退回商品默认进待检，把它当已质检处理就等于跳过质检把残次品当新品卖出去。",
                null),
            section("disposition", "转可售 / 残次 / 报废", false, "11.2.3.2 转可售/残次/报废",
                "把待检转入可售 / 残次 / 报废**未交付**。"
                    + "⚠️ 本模块因此<b>没有「转可售」入口，传输层也不提供</b>能改入库状态的字段 —— "
                    + "不是没做按钮，是<b>接口层面就不接受</b>这个动作。"
                    + "报废还是不可逆的，需要审批与原因（蓝图原话），更不能由本模块顺手做掉。",
                null),
            section("inventoryApply", "分录应用到 7 库存 / 仓库服务", false, "7 库存 / 8 仓库",
                "本服务<b>不碰任何库存表</b>：7 库存与 8 仓库都是<b>独立服务、独立数据库**，"
                    + "按 AGENTS.md 模块边界纪律**禁止跨服务 SQL / 视图 / FK**，本服务写不进去也读不到。"
                    + "⇒ return_inventory_posting 是**跨服务交接凭据**（因此刻意自带 SKU 快照），"
                    + "同步器按同一形状消费；消费侧<b>未交付**。"
                    + "⚠️ 已入库的实收单<b>不可撤销</b>：分录已生成，回冲属 11.2.3.2，未交付。",
                null),
            section("batchAttribution", "批次质量归因", false, "11.1.2.2 商品问题归因",
                "把质量问题归因到生产 / 运输 / 仓储 / 描述，<b>未交付</b>。"
                    + "本模块已落 {@code batch_no} 与序列号，是它将来归因时的输入，"
                    + "但**不产出任何归因结论**。",
                null)),
        List.of(
            related("MODULE_11_2_1_1", "11.2.1.1 退货授权单", true, null,
                "授权量与「批没批」的真值来源（V161，已交付）。本模块所有额度都挂在它的授权行上。",
                "/aftersales/returns"),
            related("MODULE_11_2_1_2", "11.2.1.2 退货指令下发", true, null,
                "退货指引（V165，已交付）。**可空**关联：实收不必然有指引。",
                "/aftersales/return-instructions"),
            related("MODULE_11_2_2_2", "11.2.2.2 实收分配", false, "11.2.2.2",
                "实收 → 订单行分配、超收按实际退回 / 少收说明、订单行已退数量更新**未交付**。"
                    + "⇒ 订单行已退数量当前没有落点，本模块不写也不报。",
                null),
            related("MODULE_11_2_3_1", "11.2.3.1 质检与判定", false, "11.2.3.1",
                "可再销售 / 残次 / 报废判定与残值估算**未交付**。本模块只把货送进待检。",
                null),
            related("MODULE_11_2_3_2", "11.2.3.2 转可售/残次/报废", false, "11.2.3.2",
                "库存状态流转**未交付**。本模块没有「转可售」入口，接口层面也不接受这个动作。",
                null),
            related("MODULE_7_INVENTORY", "7 库存 / 8 仓库", false, "7 库存 / 8 仓库",
                "分录的应用侧（真正改可售 / 待检量）**未交付**，且是**独立数据库** ⇒ "
                    + "本服务按模块边界纪律不碰库存表。",
                null)),
        List.of(
            action("EDIT_DRAFT", "改草稿", true, "PUT", "/trade/return-receipts/{id}", true,
                "仅 DRAFT", null, null, null),
            action("APPEND_PHOTO", "追加照片", true, "POST", "/trade/return-receipts/{id}/photos",
                false, "仅 DRAFT", null, null, null),
            action("POST", "入库（进待检）", true, "POST", "/trade/return-receipts/{id}/post", false,
                "仅 DRAFT，且两个判定维度都已判、有明细行", null, null, null),
            action("CANCEL", "撤销", true, "POST", "/trade/return-receipts/{id}/cancel", false,
                "仅 DRAFT", null, null, null),
            action("RESELL", "转可售", false, null, null, false, "仅已入库且质检判定为可再销售",
                null, "11.2.3.2",
                "转可售 / 残次 / 报废**未交付**。本模块**刻意没有**这个端点："
                    + "退回商品默认进待检是硬规则，而「直接转可售」就是这条硬规则要防的那件事。"),
            action("INSPECT", "质检判定", false, null, null, false, "仅已入库",
                null, "11.2.3.1",
                "可再销售 / 残次 / 报废判定**未交付**。本模块**刻意没有**这个端点。"),
            action("ALLOCATE", "分配到订单行", false, null, null, false, "仅已入库",
                null, "11.2.2.2",
                "实收 → 订单行分配与已退数量更新**未交付**。")),
        List.of(
            boundary("不提供「直接进可售」的入库变体，也不提供改入库状态的字段",
                "11.2.3.2",
                "蓝图加粗句「退回商品**默认进待检状态**」是硬规则。"
                    + "传参里出现一个可选的目标状态，接口就等于在问「这次要不要直接当新品卖」—— "
                    + "而残次品当新品卖出去是这条规则要防的唯一事故。"),
            boundary("不处理超收（实收多于授权）", "11.2.2.2",
                "蓝图 11.2.2.2 写明「处理实收多于授权（**按实际退回**）」。"
                    + "当前 11.2.2.2 未交付 ⇒ 本模块**整笔拒绝**并报错。"
                    + "放行超收会让「授权 3 件、实收 5 件」变成一个库里有、界面上答不出解释的状态。"),
            boundary("不写订单行已退数量", "11.2.2.2",
                "已退数量的写入所有者是 11.2.2.2。本模块写它就是抢别人的写入所有权，"
                    + "而 AGENTS.md 要求每张事实表只有一个写入所有者。"),
            boundary("不碰任何库存表，不回冲分录", "7 库存 / 8 仓库 / 11.2.3.2",
                "跨服务禁止 SQL；回冲属处置决定，且报废是不可逆的、需要审批与原因。"),
            boundary("已入库的实收单不可撤销", "11.2.3.2",
                "入库那一刻待检分录已生成。撤销等于「货不在待检区了」而账上还在 —— "
                    + "账实不符比一次录错更难查。"),
            boundary("不存照片二进制本体", "18 集成",
                "列表查询会把整库图片拖进内存，而列表根本不需要看图。"
                    + "库内存 object_uri + sha256 + bytes，对象存储接入属 18 集成，未交付。")),
        "DRAFT → POSTED → （终态）。"
            + "⚠️ **没有 INSPECTED / RESALABLE / DEFECTIVE / SCRAPPED**：质检与处置属 11.2.3.1 / 11.2.3.2，"
            + "未交付。POSTED 是**不可逆**的（分录已生成，回冲属 11.2.3.2）。"
            + "CANCELLED 只对 DRAFT 可用，且必须说明理由。",
        "⚠️ **退回商品默认进待检，绝不进可售**（硬规则 1）。"
            + "入库分录由 DB 触发器生成，inventory_state 恒 PENDING_INSPECTION、"
            + "sellable_qty_delta 恒 0，两列都由表级 CHECK 钉死。"
            + "本服务**没有任何**写 return_inventory_posting 的代码路径 —— "
            + "「谁把退回商品记成了可售」在本模块里**没有代码路径**。"
            + "分录是**跨服务交接凭据**（因此刻意自带 SKU 快照）；"
            + "真正改待检量 / 可售量在 7 库存 / 8 仓库的独立库里，**未交付**，本服务不碰。",
        "本模块只把货送进待检，**不做质检判定**。"
            + "inspectionStatus 恒为 PENDING_INSPECTION，inspectionOwnerModule 恒为 11.2.3.1。"
            + "⚠️ 质检结论（可再销售 / 残次 / 报废）属 11.2.3.1，处置属 11.2.3.2，**均未交付**；"
            + "本模块**刻意没有**「转可售」入口，传输层也不提供能改入库状态的字段。",
        "「关联授权行」是本模块做的（硬规则 3：挂授权行 + 额度约束）；"
            + "「分配到订单行 + 更新已退数量」属 11.2.2.2，**未交付**。"
            + "⇒ 订单行已退数量当前没有落点，本模块**不写也不报**它"
            + "（报 0 会让运营以为这行从来没退过，在重复退货面前失去唯一提醒）。",
        "照片按角色分开登记（外包装 / 商品 / 序列号标签 / 损坏特写 / 其他），"
            + "与两个判定维度一一对应。追加式 + sha256 唯一去重 + 拍摄与登记时刻分列。"
            + "⚠️ 18 集成对象存储同步**未交付**，当前只有人工上传一个来源。");
  }

  // ================================================================ 列表

  /** 列表：真分页 + 多维筛选（含按承运商 / 运单号反查）+ 排序白名单 + 全量汇总。 */
  public ReturnReceiptPage list(ReturnReceiptListQuery query) {
    int page = query.page() == null || query.page() < 0 ? 0 : query.page();
    int size = query.size() == null || query.size() <= 0 ? 20 : Math.min(query.size(), MAX_PAGE_SIZE);
    ReturnReceiptSort sort = ReturnReceiptSort.parse(query.sort());

    OffsetDateTime at = OffsetDateTime.now(ZoneOffset.UTC);

    List<Object> binds = new ArrayList<>();
    List<String> where = conditions(query, binds);
    String clause = where.isEmpty() ? "" : " where " + String.join(" and ", where);

    // 左连退货授权单 / 退货指令 / 售后单 / 订单：单号要显示，
    // 而这几张表若因故缺失**这条实收单仍要显示** ——
    // 「列表里没有这条实收」在退货场景里比「单号缺失」严重得多。
    String fromBase =
        " from return_receipt r"
            + " left join rma_authorization a"
            + "   on a.tenant_id = r.tenant_id and a.id = r.rma_id"
            + " left join rma_instruction i"
            + "   on i.tenant_id = r.tenant_id and i.id = r.instruction_id"
            + " left join aftersales_case c"
            + "   on c.tenant_id = a.tenant_id and c.id = a.case_id"
            + " left join sales_order o"
            + "   on o.tenant_id = c.tenant_id and o.id = c.order_id";
    String from =
        fromBase
            + " left join lateral ("
            + "   select count(*) as line_count, coalesce(sum(l.received_qty), 0) as received_qty"
            + "     from return_receipt_line l"
            + "    where l.tenant_id = r.tenant_id and l.receipt_id = r.id) w on true"
            + " left join lateral ("
            + "   select count(*) as photo_count"
            + "     from return_receipt_photo p"
            + "    where p.tenant_id = r.tenant_id and p.receipt_id = r.id) ph on true"
            + " left join lateral ("
            + "   select count(*) as posting_count,"
            + "          coalesce(sum(g.pending_inspection_qty), 0) as pending_qty,"
            + "          coalesce(sum(g.sellable_qty_delta), 0) as sellable_delta,"
            + "          coalesce((array_agg(g.inventory_state))[1], '') as inventory_state,"
            + "          max(g.posted_at) as last_posted_at"
            + "     from return_inventory_posting g"
            + "    where g.tenant_id = r.tenant_id and g.receipt_id = r.id) g on true";

    long total =
        sql.fetchOne("select count(*)" + fromBase + clause, binds.toArray()).get(0, Long.class);

    // ⚠️ 占位符顺序必须与 SQL **文本出现顺序**一致：[from 里的] → [where 里的] → [limit] → [offset]。
    //   jOOQ 按位置绑定，顺序错了不会报错、只会把值绑到别的 ? 上 —— 那是最难查的一类 bug。
    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(size);
    pageBinds.add((long) page * size);

    List<ReturnReceiptRow> rows =
        sql.fetch(
                "select r.*, a.rma_no, a.rma_status, i.instruction_no,"
                    + " c.id as case_id, c.case_no, o.order_no,"
                    + " w.line_count, w.received_qty, ph.photo_count,"
                    + " g.posting_count, g.pending_qty, g.sellable_delta,"
                    + " g.inventory_state, g.last_posted_at"
                    + from
                    + clause
                    + " order by "
                    + sort.sql()
                    + " limit ? offset ?",
                pageBinds.toArray())
            .map(r -> row(r));

    return new ReturnReceiptPage(
        rows,
        page,
        size,
        total,
        summary(where, binds),
        at,
        "真分页（LIMIT/OFFSET + 独立 COUNT），汇总与排序不受分页影响。",
        "⚠️ 每行都带 sellableQtyDelta，**恒为 0** —— 退回商品默认进待检（硬规则 1）。"
            + "inventoryState 恒为 PENDING_INSPECTION。这两格是给界面做正对照用的："
            + "如果哪天出现非 0，说明有人绕过接口改了库存，那是事故不是特性。",
        "inspectionCompleted 恒为 false，inspectionOwnerModule 恒为 11.2.3.1 —— "
            + "质检与处置属 11.2.3.1 / 11.2.3.2，**未交付**。退回商品默认进待检，"
            + "把它显示成「已质检」等于让运营把未经检验的货当新品处理。",
        "排序白名单四个键（created_desc / received_asc / status_asc / channel_asc）；"
            + "其余取值落回 created_desc，不接受任意表达式。");
  }

  private List<String> conditions(ReturnReceiptListQuery query, List<Object> binds) {
    List<String> where = new ArrayList<>();
    where.add("r.tenant_id = ?::uuid");
    binds.add(currentTenant());
    // ⚠️ 筛选值**先过枚举**再进 SQL：直接绑一个拼错的状态值，表里恰好没有匹配的行时
    //   表级 CHECK 根本不会被求值（CHECK 只在 INSERT/UPDATE 时检查），结果是静默返回零行 ——
    //   运营会读成「没有这样的实收单」，而真相是筛选条件本身写错了。
    //   这与 V161 decision / V165 status 的同一纪律（照抄）。
    if (isSet(query.status())) {
      where.add("r.receipt_status = ?");
      binds.add(parseEnum(ReturnReceiptStatus.class, query.status(), "实收状态").name());
    }
    if (isSet(query.channel())) {
      where.add("r.receipt_channel = ?");
      binds.add(parseEnum(ReturnReceiptChannel.class, query.channel(), "收货渠道").name());
    }
    if (query.rmaId() != null) {
      where.add("r.rma_id = ?::uuid");
      binds.add(query.rmaId());
    }
    if (query.instructionId() != null) {
      where.add("r.instruction_id = ?::uuid");
      binds.add(query.instructionId());
    }
    if (query.caseId() != null) {
      where.add("c.id = ?::uuid");
      binds.add(query.caseId());
    }
    if (isSet(query.carrier())) {
      where.add("r.carrier_code = ?");
      binds.add(query.carrier().trim());
    }
    // ⚠️ 按运单号反查是客服手上只有一个单号时的入口。
    //   这个筛选在多租户下尤其危险：漏掉租户谓词它就成了一台**跨租户搜索引擎**。
    where.add("r.tenant_id = ?::uuid");
    binds.add(currentTenant());
    if (isSet(query.trackingNo())) {
      where.add("r.return_tracking_no = ?");
      binds.add(query.trackingNo().trim());
    }
    if (isSet(query.packagingCondition())) {
      where.add(
          "exists (select 1 from return_receipt_line l where l.tenant_id = r.tenant_id"
              + " and l.receipt_id = r.id and l.packaging_condition = ?)");
      binds.add(
          parseEnum(ReturnPackagingCondition.class, query.packagingCondition(), "外包装判定").name());
    }
    if (isSet(query.goodsCondition())) {
      where.add(
          "exists (select 1 from return_receipt_line l where l.tenant_id = r.tenant_id"
              + " and l.receipt_id = r.id and l.goods_condition = ?)");
      binds.add(parseEnum(ReturnGoodsCondition.class, query.goodsCondition(), "商品状态判定").name());
    }
    if (isSet(query.keyword())) {
      String like = "%" + query.keyword().trim() + "%";
      // 真实模糊检索：单号 / 授权单号 / 售后单号 / 订单号 / 运单号。
      where.add(
          "(r.receipt_no ilike ? or r.return_tracking_no ilike ? or a.rma_no ilike ?"
              + " or c.case_no ilike ? or o.order_no ilike ?)");
      binds.add(like);
      binds.add(like);
      binds.add(like);
      binds.add(like);
      binds.add(like);
    }
    return where;
  }

  private ReturnReceiptRow row(Record r) {
    String status = r.get("receipt_status", String.class);
    ReturnReceiptStatus st = ReturnReceiptStatus.valueOf(status);
    String channel = r.get("receipt_channel", String.class);
    ReturnReceiptChannel ch = ReturnReceiptChannel.valueOf(channel);
    return new ReturnReceiptRow(
        r.get("id", UUID.class),
        r.get("receipt_no", String.class),
        r.get("rma_id", UUID.class),
        r.get("rma_no", String.class),
        r.get("instruction_id", UUID.class),
        r.get("case_id", UUID.class),
        r.get("case_no", String.class),
        r.get("order_no", String.class),
        status,
        st.label(),
        channel,
        ch.label(),
        r.get("carrier_code", String.class),
        r.get("return_tracking_no", String.class),
        r.get("received_at", OffsetDateTime.class),
        r.get("last_posted_at", OffsetDateTime.class),
        r.get("line_count", Integer.class),
        r.get("received_qty", BigDecimal.class),
        r.get("photo_count", Integer.class),
        r.get("posting_count", Integer.class),
        r.get("pending_qty", BigDecimal.class),
        // ⚠️ 恒为 0（硬规则 1）：DB 层 sellable_qty_delta CHECK 恒 0，
        //   这里只是把它显示出来让界面做正对照。
        r.get("sellable_delta", BigDecimal.class),
        PENDING_INSPECTION,
        false,
        "11.2.3.1",
        "退回商品默认进待检（硬规则）。质检与处置属 11.2.3.1 / 11.2.3.2，**未交付**："
            + "本模块没有「转可售」入口，传输层也不提供能改入库状态的字段。",
        r.get("version", Long.class));
  }

  /**
   * 列表页汇总（真分页之外的全量口径）。
   *
   * <p>⚠️ 与 {@link #list} 的 where/binds 共用同一份条件列表：汇总与列表页口径一致，
   * 翻页时汇总不会跳。
   */
  private ReturnReceiptSummary summary(List<String> where, List<Object> binds) {
    String clause = where.isEmpty() ? "" : " where " + String.join(" and ", where);
    String from =
        " from return_receipt r"
            + " left join rma_authorization a"
            + "   on a.tenant_id = r.tenant_id and a.id = r.rma_id"
            + " left join rma_instruction i"
            + "   on i.tenant_id = r.tenant_id and i.id = r.instruction_id"
            + " left join aftersales_case c"
            + "   on c.tenant_id = a.tenant_id and c.id = a.case_id"
            + " left join sales_order o"
            + "   on o.tenant_id = c.tenant_id and o.id = c.order_id";

    Record r =
        sql.fetchOne(
            "select count(*) as total,"
                + " count(*) filter (where r.receipt_status = 'DRAFT') as draft,"
                + " count(*) filter (where r.receipt_status = 'POSTED') as posted,"
                + " count(*) filter (where r.receipt_status = 'CANCELLED') as cancelled,"
                + " count(*) filter (where r.receipt_channel = 'CARRIER') as carrier,"
                + " count(*) filter (where r.receipt_channel in ('BUYER_DROPOFF','WALK_IN')) as walk_in,"
                + " count(*) filter (where exists (select 1 from return_receipt_photo p"
                + "   where p.tenant_id = r.tenant_id and p.receipt_id = r.id)) as with_photos,"
                + " count(*) filter (where exists (select 1 from return_receipt_line l"
                + "   where l.tenant_id = r.tenant_id and l.receipt_id = r.id"
                + "     and l.packaging_condition in ('DAMAGED','DESTROYED'))) as packaging_damaged,"
                + " count(*) filter (where exists (select 1 from return_receipt_line l"
                + "   where l.tenant_id = r.tenant_id and l.receipt_id = r.id"
                + "     and l.goods_condition in ('DAMAGED','DEFECTIVE','MISSING_PARTS'))) as goods_damaged"
                + from
                + clause,
            binds.toArray());

    // 数量类汇总必须跨全表算（不能用分页里的 lateral）——
    // 「待检库存总量」是页面上最该盯的那一格，它不能因为翻到第二页就变小。
    Record q =
        sql.fetchOne(
            "select coalesce(sum(l.received_qty), 0) as received_qty"
                + " from return_receipt_line l"
                + " join return_receipt rr on rr.tenant_id = l.tenant_id and rr.id = l.receipt_id"
                + " left join rma_authorization a"
                + "   on a.tenant_id = rr.tenant_id and a.id = rr.rma_id"
                + " left join aftersales_case c"
                + "   on c.tenant_id = a.tenant_id and c.id = a.case_id"
                + " left join sales_order o"
                + "   on o.tenant_id = c.tenant_id and o.id = c.order_id"
                + clause.replace("r.", "rr."),
            binds.toArray());

    BigDecimal received = q.get("received_qty", BigDecimal.class);
    BigDecimal pending = received;
    return new ReturnReceiptSummary(
        r.get("total", Long.class),
        r.get("draft", Long.class),
        r.get("posted", Long.class),
        r.get("cancelled", Long.class),
        r.get("carrier", Long.class),
        r.get("walk_in", Long.class),
        r.get("with_photos", Long.class),
        received,
        pending,
        // 恒为 0（硬规则 1）
        SELLABLE_DELTA_ZERO,
        r.get("posted", Long.class),
        r.get("packaging_damaged", Long.class),
        r.get("goods_damaged", Long.class));
  }

  // ================================================================ 剩余可收量

  /**
   * 某张退货授权单的剩余可收量（硬规则 3 的可见面）。
   *
   * <p>把「授权量 / 已被占用 / 还能收」三个数并排给出，让操作员在<b>扫码那一刻</b>
   * 就知道还能收几件，而不是录完被后端拒了才知道。
   *
   * <p>⚠️ 占用口径与 DB 触发器完全一致（DRAFT + POSTED，排除 CANCELLED），
   * 服务层与触发器算出同一个数 —— 两边口径不一致的话，界面显示「还能收 3 件」
   * 而点提交被拒，是最消耗信任的一种不一致。
   */
  public ReturnReceivablePage receivable(UUID rmaId) {
    Record head =
        sql.fetchOne(
            "select rma_no, rma_status from rma_authorization"
                + " where tenant_id = ?::uuid and id = ?::uuid",
            currentTenant(),
            rmaId);
    if (head == null) {
      throw new IllegalArgumentException("退货授权单不存在或不属于当前租户：" + rmaId);
    }

    List<ReturnReceivableRow> rows =
        sql.fetch(
                "select l.id as rma_line_id, l.case_line_id, l.authorized_qty,"
                    + " cl.order_line_id, ol.sku_code, ol.sku_name,"
                    + " coalesce(t.taken_qty, 0) as taken_qty, coalesce(t.receipt_count, 0) as receipt_count"
                    + " from rma_authorization_line l"
                    + " join aftersales_case_line cl"
                    + "   on cl.tenant_id = l.tenant_id and cl.id = l.case_line_id"
                    + " join sales_order_line ol"
                    + "   on ol.tenant_id = cl.tenant_id and ol.id = cl.order_line_id"
                    + " left join lateral ("
                    + "   select sum(rl.received_qty) as taken_qty, count(*) as receipt_count"
                    + "     from return_receipt_line rl"
                    + "     join return_receipt r"
                    + "       on r.tenant_id = rl.tenant_id and r.id = rl.receipt_id"
                    + "    where rl.tenant_id = l.tenant_id"
                    + "      and rl.rma_authorization_line_id = l.id"
                    + "      and r.receipt_status in ('DRAFT','POSTED')) t on true"
                    + " where l.tenant_id = ?::uuid and l.rma_id = ?::uuid"
                    // ⚠️ 排序键取 **ol.line_no**：rma_authorization_line 上**没有 line_no**
                    //   （V161 只存 rma_id + case_line_id + 四个基数快照），
                    //   按它排会直接报「column l.line_no does not exist」。
                    + " order by ol.line_no, l.id",
                currentTenant(),
                rmaId)
            .map(
                r -> {
                  BigDecimal authorized = r.get("authorized_qty", BigDecimal.class);
                  BigDecimal taken = r.get("taken_qty", BigDecimal.class);
                  BigDecimal remaining = authorized.subtract(taken);
                  return new ReturnReceivableRow(
                      rmaId,
                      head.get("rma_no", String.class),
                      r.get("rma_line_id", UUID.class),
                      r.get("case_line_id", UUID.class),
                      r.get("order_line_id", UUID.class),
                      r.get("sku_code", String.class),
                      r.get("sku_name", String.class),
                      authorized,
                      taken,
                      remaining,
                      r.get("receipt_count", Integer.class),
                      remaining.signum() < 0);
                });

    BigDecimal authTotal = rows.stream().map(ReturnReceivableRow::authorizedQty)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal takenTotal = rows.stream().map(ReturnReceivableRow::takenQty)
        .reduce(BigDecimal.ZERO, BigDecimal::add);

    return new ReturnReceivablePage(
        rmaId,
        head.get("rma_no", String.class),
        head.get("rma_status", String.class),
        rows,
        authTotal,
        takenTotal,
        authTotal.subtract(takenTotal),
        OffsetDateTime.now(ZoneOffset.UTC),
        "⚠️ 占用口径 = DRAFT + POSTED 两种单（排除 CANCELLED），与 DB 触发器"
            + "trg_guard_return_receipt_line 完全一致：**草稿也占额度**。"
            + "所以录错了要先撤销草稿才能重录 —— 这是有意的代价："
            + "只算已入库的话，两张草稿各自校验时都合法，并发入库时总量已经超了。"
            + "remainingQty 可能为负，界面必须显式显示成「超出授权 N 件」，不要悄悄 clamp 到 0。");
  }

  // ================================================================ 详情

  /** 单张实收全景。**不存在的 id 与不属于本租户的 id 返回同一条错误** —— 见类注释。 */
  public ReturnReceiptDetail detail(UUID id) {
    Record head =
        sql.fetchOne(
            "select r.*, a.rma_no, a.rma_status, i.instruction_no,"
                + " c.id as case_id, c.case_no, o.id as order_id, o.order_no"
                + " from return_receipt r"
                + " join rma_authorization a on a.tenant_id = r.tenant_id and a.id = r.rma_id"
                + " join aftersales_case c on c.tenant_id = a.tenant_id and c.id = a.case_id"
                + " left join rma_instruction i on i.tenant_id = r.tenant_id and i.id = r.instruction_id"
                + " left join sales_order o on o.tenant_id = c.tenant_id and o.id = c.order_id"
                + " where r.tenant_id = ?::uuid and r.id = ?::uuid",
            currentTenant(),
            id);
    if (head == null) {
      throw new IllegalArgumentException("退货实收单不存在或不属于当前租户：" + id);
    }

    List<ReturnReceiptLine> lines =
        sql.fetch(
                "select l.*, cl.id as case_line_id, cl.order_line_id, ol.sku_code, ol.sku_name,"
                    + " ol.spec_snapshot, l2.authorized_qty"
                    + " from return_receipt_line l"
                    + " join rma_authorization_line l2"
                    + "   on l2.tenant_id = l.tenant_id and l2.id = l.rma_authorization_line_id"
                    + " join aftersales_case_line cl"
                    + "   on cl.tenant_id = l2.tenant_id and cl.id = l2.case_line_id"
                    + " join sales_order_line ol"
                    + "   on ol.tenant_id = cl.tenant_id and ol.id = cl.order_line_id"
                    + " where l.tenant_id = ?::uuid and l.receipt_id = ?::uuid"
                    + " order by l.line_no, l.id",
                currentTenant(),
                id)
            .map(this::line);

    List<ReturnPhoto> photos =
        sql.fetch(
                "select * from return_receipt_photo"
                    + " where tenant_id = ?::uuid and receipt_id = ?::uuid"
                    + " order by registered_at, id",
                currentTenant(),
                id)
            .map(this::photo);

    List<ReturnInventoryPosting> postings =
        sql.fetch(
                "select * from return_inventory_posting"
                    + " where tenant_id = ?::uuid and receipt_id = ?::uuid"
                    + " order by posted_at, id",
                currentTenant(),
                id)
            .map(
                p -> {
                  ReturnPackagingCondition pkg =
                      ReturnPackagingCondition.valueOf(p.get("packaging_condition", String.class));
                  ReturnGoodsCondition goods =
                      ReturnGoodsCondition.valueOf(p.get("goods_condition", String.class));
                  return new ReturnInventoryPosting(
                      p.get("id", UUID.class),
                      p.get("receipt_id", UUID.class),
                      p.get("receipt_line_id", UUID.class),
                      p.get("posting_type", String.class),
                      p.get("sku_id", UUID.class),
                      p.get("sku_code", String.class),
                      p.get("sku_name", String.class),
                      p.get("batch_no", String.class),
                      p.get("inventory_state", String.class),
                      p.get("sellable_qty_delta", BigDecimal.class),
                      p.get("pending_inspection_qty", BigDecimal.class),
                      pkg.name(),
                      goods.name(),
                      p.get("posted_at", OffsetDateTime.class),
                      p.get("posted_by", UUID.class));
                });

    List<ReturnReceiptEvent> events =
        sql.fetch(
                "select * from return_receipt_event"
                    + " where tenant_id = ?::uuid and receipt_id = ?::uuid"
                    + " order by occurred_at, id",
                currentTenant(),
                id)
            .map(
                e ->
                    new ReturnReceiptEvent(
                        e.get("id", UUID.class),
                        e.get("event_type", String.class),
                        eventLabel(e.get("event_type", String.class)),
                        e.get("from_status", String.class),
                        e.get("to_status", String.class),
                        e.get("reason_note", String.class),
                        e.get("occurred_by", UUID.class),
                        e.get("occurred_at", OffsetDateTime.class)));

    BigDecimal received =
        lines.stream().map(ReturnReceiptLine::receivedQty).reduce(BigDecimal.ZERO, BigDecimal::add);
    // ⚠️ 汇总列同样把可售增量显式摆出来（恒 0）：页面据此显示「进待检 N 件，可售 0」
    //   而不是含糊地写一句「已入库」。
    BigDecimal pending =
        postings.stream()
            .map(ReturnInventoryPosting::pendingInspectionQty)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal sellable =
        postings.stream()
            .map(ReturnInventoryPosting::sellableQtyDelta)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

    int unjudged =
        (int)
            lines.stream()
                .filter(
                    l ->
                        !l.bothDimensionsJudged())
                .count();

    String rmaStatus = head.get("rma_status", String.class);
    List<ReturnReceivableRow> receivable =
        "AUTHORIZED".equals(rmaStatus) || "PARTIALLY_AUTHORIZED".equals(rmaStatus)
            ? receivable(head.get("rma_id", UUID.class)).rows()
            : List.of();

    return new ReturnReceiptDetail(
        head(head),
        lines,
        photos,
        postings,
        events,
        received,
        pending,
        sellable,
        PENDING_INSPECTION,
        "⚠️ **退回商品默认进待检，绝不进可售**（蓝图加粗句的硬规则）。"
            + "inventoryState 恒 PENDING_INSPECTION、sellableQtyDelta 恒 0，"
            + "两者由 V172 表级 CHECK 在数据库层钉死，分录由 DB 触发器生成，"
            + "**绕过服务层直连 SQL 也拿不到一张「已进可售」的分录**。",
        PENDING_INSPECTION,
        "待质检",
        "11.2.3.1",
        unjudged == 0,
        unjudged,
        receivable,
        actions(head, lines, unjudged),
        relatedModules(),
        OffsetDateTime.now(ZoneOffset.UTC),
        "「关联授权行」由本模块做（挂授权行 + 额度约束）；"
            + "「分配到订单行 + 更新已退数量」属 11.2.2.2，**未交付** ⇒ 详情不报已退数量。"
            + "两个判定维度分别判定、分别显示：外箱压损但里面的货完好是真实且常见的情况。");
  }

  private ReturnReceiptHead head(Record r) {
    ReturnReceiptStatus st =
        ReturnReceiptStatus.valueOf(r.get("receipt_status", String.class));
    ReturnReceiptChannel ch = ReturnReceiptChannel.valueOf(r.get("receipt_channel", String.class));
    return new ReturnReceiptHead(
        r.get("id", UUID.class),
        r.get("receipt_no", String.class),
        r.get("rma_id", UUID.class),
        r.get("rma_no", String.class),
        r.get("rma_status", String.class),
        r.get("instruction_id", UUID.class),
        r.get("instruction_no", String.class),
        r.get("case_id", UUID.class),
        r.get("case_no", String.class),
        r.get("order_id", UUID.class),
        r.get("order_no", String.class),
        st.name(),
        st.label(),
        ch.name(),
        ch.label(),
        r.get("carrier_code", String.class),
        r.get("return_tracking_no", String.class),
        r.get("package_note", String.class),
        r.get("received_at", OffsetDateTime.class),
        r.get("posted_at", OffsetDateTime.class),
        r.get("posted_by", UUID.class),
        r.get("cancelled_at", OffsetDateTime.class),
        r.get("cancel_reason", String.class),
        r.get("version", Long.class),
        r.get("created_by", UUID.class),
        r.get("created_at", OffsetDateTime.class));
  }

  private ReturnReceiptLine line(Record r) {
    ReturnPackagingCondition pkg =
        ReturnPackagingCondition.valueOf(r.get("packaging_condition", String.class));
    ReturnGoodsCondition goods =
        ReturnGoodsCondition.valueOf(r.get("goods_condition", String.class));
    // ⚠️ **NOT_APPLICABLE 不算「未判定」**（硬规则 2 的判定闸门）：
    //   「这件货本来就没有这个维度」（散装 / 无包装无配件的 SKU）与「还没判」必须分开，
    //   合成一个值会让入库闸门把「不适用」当成「还没判」一直拦住。
    return new ReturnReceiptLine(
        r.get("id", UUID.class),
        r.get("rma_authorization_line_id", UUID.class),
        r.get("case_line_id", UUID.class),
        r.get("order_line_id", UUID.class),
        r.get("sku_code", String.class),
        r.get("sku_name", String.class),
        r.get("spec_snapshot", String.class),
        r.get("scanned_sku_code", String.class),
        r.get("scanned_serial_no", String.class),
        r.get("batch_no", String.class),
        r.get("authorized_qty", BigDecimal.class),
        r.get("received_qty", BigDecimal.class),
        pkg.name(),
        pkg.label(),
        goods.name(),
        goods.label(),
        !pkg.undecided() && !goods.undecided(),
        r.get("count_note", String.class),
        r.get("counted_by", UUID.class),
        r.get("counted_at", OffsetDateTime.class),
        r.get("line_no", Integer.class));
  }

  private ReturnPhoto photo(Record r) {
    ReturnPhotoRole role = ReturnPhotoRole.valueOf(r.get("photo_role", String.class));
    ReturnPhotoSource source = ReturnPhotoSource.valueOf(r.get("photo_source", String.class));
    return new ReturnPhoto(
        r.get("id", UUID.class),
        source.name(),
        source.label(),
        r.get("source_reference", String.class),
        role.name(),
        role.label(),
        r.get("object_uri", String.class),
        r.get("content_sha256", String.class),
        r.get("content_bytes", Long.class),
        r.get("content_type", String.class),
        r.get("captured_at", OffsetDateTime.class),
        r.get("registered_at", OffsetDateTime.class),
        r.get("caption", String.class),
        r.get("registered_by", UUID.class));
  }

  // ================================================================ 写：建单 / 改草稿

  /**
   * 登记一张退货实收草稿。
   *
   * <p><b>建单不是入库</b>：此刻不产生任何库存分录。要入库走 {@link #post}。
   */
  public ReturnReceiptDetail create(ReturnReceiptCreate request, UUID userId) {
    if (request.lines() == null || request.lines().isEmpty()) {
      throw new IllegalArgumentException("至少要给一件明细：一张没有明细的实收单无法入库，也不该存在");
    }
    ReturnReceiptChannel channel =
        parseEnum(ReturnReceiptChannel.class, request.receiptChannel(), "收货渠道");
    // ⚠️ 承运商渠道必须带运单号（DB 表级 CHECK 同一条纪律，服务层先给一句能读懂的话）：
    //   没有运单号的承运商收货答不出「是哪一票」，而这正是退货最常见的收货方式。
    if (channel.requiresWaybill()
        && (!isSet(request.carrierCode()) || !isSet(request.returnTrackingNo()))) {
      throw new IllegalArgumentException("承运商送达必须给出承运商与回寄运单号（答不出是哪一票）");
    }
    if (request.receivedAt() == null) {
      throw new IllegalArgumentException("必须给出收货时刻：收货时间决定这批货压了多久");
    }

    UUID id = UUID.randomUUID();
    String no = nextReceiptNo();

    sql.execute(
        "insert into return_receipt(id,tenant_id,receipt_no,rma_id,instruction_id,receipt_channel,"
            + "carrier_code,return_tracking_no,package_note,received_at,receipt_status,"
            + "created_by,created_at,updated_at)"
            + " values(?,?::uuid,?,?::uuid,?::uuid,?,?,?,?,?::timestamptz,'DRAFT',"
            + "?::uuid,?::timestamptz,?::timestamptz)",
        id,
        currentTenant(),
        no,
        request.rmaId(),
        request.instructionId(),
        channel.name(),
        // ⚠️ 两列 NOT NULL DEFAULT ''：门店收货 / 买家自送**没有**承运商与运单号，
        //   那是正常业务状态（表级 CHECK 只对 CARRIER 渠道要求非空），空值写空串不写 null。
        trimOrEmpty(request.carrierCode()),
        trimOrEmpty(request.returnTrackingNo()),
        request.packageNote() == null ? "" : request.packageNote().trim(),
        request.receivedAt(),
        userId,
        OffsetDateTime.now(ZoneOffset.UTC),
        OffsetDateTime.now(ZoneOffset.UTC));

    insertLines(id, request.lines(), userId, request.receivedAt());
    if (request.photos() != null) {
      for (ReturnPhotoDraft p : request.photos()) {
        appendPhoto(id, p, userId);
      }
    }
    return detail(id);
  }

  /**
   * 改草稿要素。
   *
   * <p><b>只允许 DRAFT 改</b>：入库后行与照片是证据（质检与索赔都要引用），
   * DB 触发器 {@code guard_return_receipt_frozen} 同一条纪律。
   *
   * <p>⚠️ <b>整单替换明细</b>：不是增量 patch。扫码台的工作面是「这一包裹一共几件」，
   * 增量追加会让「删掉扫错的那一行」在界面上无处安放。
   */
  public ReturnReceiptDetail update(UUID id, ReturnReceiptUpdate request, UUID userId) {
    Record head = requireDraft(id);
    requireVersion(head, request.expectedVersion());

    ReturnReceiptChannel channel =
        parseEnum(ReturnReceiptChannel.class, request.receiptChannel(), "收货渠道");
    if (channel.requiresWaybill()
        && (!isSet(request.carrierCode()) || !isSet(request.returnTrackingNo()))) {
      throw new IllegalArgumentException("承运商送达必须给出承运商与回寄运单号（答不出是哪一票）");
    }
    if (request.receivedAt() == null) {
      throw new IllegalArgumentException("必须给出收货时刻：收货时间决定这批货压了多久");
    }

    int updated =
        sql.execute(
            "update return_receipt set instruction_id = ?::uuid, receipt_channel = ?,"
                + " carrier_code = ?, return_tracking_no = ?, package_note = ?,"
                + " received_at = ?::timestamptz, version = version + 1, updated_at = ?::timestamptz"
                + " where tenant_id = ?::uuid and id = ?::uuid and receipt_status = 'DRAFT'",
            request.instructionId(),
            channel.name(),
            trimToNull(request.carrierCode()),
            trimToNull(request.returnTrackingNo()),
            request.packageNote() == null ? "" : request.packageNote().trim(),
            request.receivedAt(),
            OffsetDateTime.now(ZoneOffset.UTC),
            currentTenant(),
            id);
    if (updated == 0) {
      throw new IllegalArgumentException("退货实收单已不是草稿，不能修改：" + id);
    }

    if (request.lines() != null) {
      if (request.lines().isEmpty()) {
        throw new IllegalArgumentException("至少要给一件明细：一张没有明细的实收单无法入库");
      }
      // 额度占用是按**授权行**算的，整单替换时先把旧行清干净，
      // 否则自己占的额度会把自己挡住（同一张单内的行彼此不占用，
      // 但删行与插行之间的中间态仍会让「按授权行汇总」短暂看到一个不完整的数）。
      sql.execute(
          "delete from return_receipt_line where tenant_id = ?::uuid and receipt_id = ?::uuid",
          currentTenant(),
          id);
      insertLines(id, request.lines(), userId, request.receivedAt());
    }
    return detail(id);
  }

  private void insertLines(
      UUID receiptId, List<ReturnReceiptLineDraft> lines, UUID countedBy, OffsetDateTime countedAt) {
    int lineNo = 1;
    for (ReturnReceiptLineDraft d : lines) {
      if (d.rmaLineId() == null) {
        throw new IllegalArgumentException("每行明细必须给出 rmaLineId：实收要挂在授权行上才有额度");
      }
      BigDecimal qty =
          d.receivedQty() == null
              ? null
              : d.receivedQty().setScale(6, java.math.RoundingMode.HALF_UP);
      if (qty == null || qty.signum() <= 0) {
        throw new IllegalArgumentException("清点数量必须大于 0");
      }
      ReturnPackagingCondition pkg =
          parseEnum(
              ReturnPackagingCondition.class,
              d.packagingCondition() == null ? "NOT_INSPECTED" : d.packagingCondition(),
              "外包装判定");
      ReturnGoodsCondition goods =
          parseEnum(
              ReturnGoodsCondition.class,
              d.goodsCondition() == null ? "NOT_INSPECTED" : d.goodsCondition(),
              "商品状态判定");
      // ⚠️ 序列号 / 批次至少要有一个（DB 表级 CHECK 同一条纪律）：
      //   两列全空的行等于「不知道这是哪一件」，质检（11.2.3.1）与批次归因（11.1.2.2）都无从下手。
      if (!isSet(d.scannedSerialNo()) && !isSet(d.batchNo())) {
        throw new IllegalArgumentException("每行明细必须给出序列号或批次：两列全空等于不知道这是哪一件");
      }
      sql.execute(
          "insert into return_receipt_line(id,tenant_id,receipt_id,rma_authorization_line_id,"
              + "scanned_sku_code,scanned_serial_no,batch_no,received_qty,packaging_condition,"
              + "goods_condition,count_note,counted_by,counted_at,line_no,created_at)"
              + " values(?,?::uuid,?::uuid,?::uuid,?,?,?,?::numeric,?,?,?,?::uuid,?::timestamptz,?,"
              + "?::timestamptz)",
          UUID.randomUUID(),
          currentTenant(),
          receiptId,
          d.rmaLineId(),
          // ⚠️ 三列都是 NOT NULL DEFAULT ''：**空串而不是 NULL**。
          //   传 null 会在第一次写入时就报「null value in column ... violates not-null
          //   constraint」—— 而「这一行没有批次号」是完全正常的业务状态，
          //   它该由「序列号 / 批次至少有一个」那条 CHECK 去表达，不是靠列可空。
          trimOrEmpty(d.scannedSkuCode()),
          trimOrEmpty(d.scannedSerialNo()),
          trimOrEmpty(d.batchNo()),
          qty,
          pkg.name(),
          goods.name(),
          d.countNote() == null ? "" : d.countNote().trim(),
          countedBy,
          countedAt,
          lineNo++,
          OffsetDateTime.now(ZoneOffset.UTC));
    }
  }

  // ================================================================ 写：入库 / 撤销

  /**
   * 入库：清点完成 → <b>生成待检入库分录</b>（硬规则 1 的落点）。
   *
   * <p>⚠️ <b>本方法不接受任何「目标库存状态」参数</b>，传输层也没有这样一个字段。
   * 分录由 V172 的 DB 触发器在状态推到 POSTED 的那一刻生成，
   * 其 inventory_state 恒为 PENDING_INSPECTION、sellable_qty_delta 恒为 0。
   *
   * <p>入库前必须两个判定维度都已判（{@code NOT_INSPECTED} 只在草稿期允许）：
   * 「已入库但商品状态还没判」的货既进不了待检也进不了残次，质检队列会漏掉它。
   */
  public ReturnReceiptDetail post(UUID id, ReturnReceiptPost request, UUID userId) {
    Record head = requireDraft(id);
    requireVersion(head, request.expectedVersion());

    // 服务层先拦一道（DB 触发器还有一道）：两个维度都必须已判。
    List<ReturnReceiptLine> lines = linesOf(id);
    if (lines.isEmpty()) {
      throw new IllegalArgumentException("退货实收单没有任何明细行，不能入库");
    }
    for (ReturnReceiptLine l : lines) {
      if (!l.bothDimensionsJudged()) {
        throw new IllegalArgumentException(
            "入库前必须判定外包装与商品状态两个维度（SKU "
                + l.skuCode()
                + " 仍为「未判定」）");
      }
    }

    OffsetDateTime at = OffsetDateTime.now(ZoneOffset.UTC);
    transition(id, ReturnReceiptStatus.POSTED, head, "posted_by", userId, "posted_at", at, null);
    return detail(id);
  }

  /** 人工撤销（录错了 / 买家又把货拿回去了）。<b>已入库的单不可撤销</b>。 */
  public ReturnReceiptDetail cancel(UUID id, ReturnReceiptCancel request, UUID userId) {
    Record head = requireRecord(id);
    // ⚠️ 撤销与「改草稿」是**两件事**，报错必须分开：
    //   早期版本两条路都走 requireDraft，于是操作员点「撤销」看到的是
    //   「不能再改」—— 而他真正需要知道的是「这单已入库、撤销不了、回冲要去 11.2.3.2」。
    ReturnReceiptStatus status =
        ReturnReceiptStatus.valueOf(head.get("receipt_status", String.class));
    if (status.posted()) {
      throw new IllegalArgumentException(
          "退货实收单已入库，不能撤销："
              + id
              + "（待检入库分录已生成，回冲属 11.2.3.2，未交付）");
    }
    if (status != ReturnReceiptStatus.DRAFT) {
      throw new IllegalArgumentException("退货实收单已撤销，不能再撤：" + id);
    }
    requireVersion(head, request.expectedVersion());
    if (!isSet(request.cancelReason())) {
      throw new IllegalArgumentException("必须说明撤销理由：录错单在退货场景里真实且常见");
    }
    OffsetDateTime at = OffsetDateTime.now(ZoneOffset.UTC);
    transition(id, ReturnReceiptStatus.CANCELLED, head, "cancelled_by", userId, "cancelled_at", at,
        request.cancelReason().trim());
    return detail(id);
  }

  /**
   * 状态流转的**唯一**入口：每次改状态都过版本校验与状态条件，都写留痕（留痕由 DB 触发器追加）。
   *
   * <p>两个人同时点「入库」时各写一次而表里只留得下后一个 ——
   * 少的那一次会让「这批货收了几件」出现两个答案，而这两个答案都要拿去和买家对质。
   * 所以 WHERE 里同时钉 {@code receipt_status = 'DRAFT'} 与 {@code version = ?}：
   * 只查后不改，会话之间改了状态时 UPDATE 命中 0 行却照样返回成功。
   *
   * <p>⚠️ {@code cancelReason} <b>只在 CANCELLED 时写进 cancel_reason</b>：
   * 早期版本把它拼进一条动态 SQL，导致入库时那句备注被写进 {@code package_note} ——
   * 「包裹整体完好描述」这个字段从此会混进操作员的临场吐槽，两个语义不同的东西挤在一列里。
   */
  private void transition(
      UUID id,
      ReturnReceiptStatus target,
      Record head,
      String whoColumn,
      UUID userId,
      String atColumn,
      OffsetDateTime at,
      String cancelReason) {
    String sqlText =
        "update return_receipt set receipt_status = ?, "
            + whoColumn
            + " = ?::uuid, "
            + atColumn
            + " = ?::timestamptz"
            + (target == ReturnReceiptStatus.CANCELLED ? ", cancel_reason = ?" : "")
            + ", version = version + 1, updated_at = ?::timestamptz"
            + " where tenant_id = ?::uuid and id = ?::uuid and receipt_status = ?"
            + " and version = ?";

    int updated;
    if (target == ReturnReceiptStatus.CANCELLED) {
      updated =
          sql.execute(
              sqlText,
              target.name(),
              userId,
              at,
              cancelReason,
              at,
              currentTenant(),
              id,
              ReturnReceiptStatus.DRAFT.name(),
              head.get("version", Long.class));
    } else {
      updated =
          sql.execute(
              sqlText,
              target.name(),
              userId,
              at,
              at,
              currentTenant(),
              id,
              ReturnReceiptStatus.DRAFT.name(),
              head.get("version", Long.class));
    }
    if (updated == 0) {
      throw new IllegalArgumentException(
          "退货实收单状态或版本已变（可能被并发改过），请刷新后重试：" + id);
    }
  }

  // ================================================================ 写：照片

  /** 追加一张照片（拍照留证可在草稿期持续补）。 */
  public ReturnReceiptDetail appendPhoto(UUID id, ReturnPhotoAppend request, UUID userId) {
    requireDraft(id);
    appendPhoto(
        id,
        new ReturnPhotoDraft(
            request.photoSource(),
            request.sourceReference(),
            request.photoRole(),
            request.objectUri(),
            request.contentSha256(),
            request.contentBytes(),
            request.contentType(),
            request.capturedAt(),
            request.caption()),
        userId);
    // 追加照片不构成状态变更，但明细变了要推版本，避免并发覆盖。
    sql.execute(
        "update return_receipt set version = version + 1, updated_at = ?::timestamptz"
            + " where tenant_id = ?::uuid and id = ?::uuid and receipt_status = 'DRAFT'",
        OffsetDateTime.now(ZoneOffset.UTC),
        currentTenant(),
        id);
    return detail(id);
  }

  private void appendPhoto(UUID receiptId, ReturnPhotoDraft p, UUID userId) {
    ReturnPhotoSource source =
        parseEnum(ReturnPhotoSource.class, p.photoSource(), "照片来源");
    if (source.needsSourceReference() && !isSet(p.sourceReference())) {
      throw new IllegalArgumentException(
          "照片来源是「18 集成对象存储同步器」时必须给出 source_reference（哪个桶 / 哪条同步规则）");
    }
    ReturnPhotoRole role = parseEnum(ReturnPhotoRole.class, p.photoRole(), "照片角色");
    if (!isSet(p.objectUri())) {
      throw new IllegalArgumentException("必须给出照片的 object_uri（对象存储接入属 18 集成，未交付）");
    }
    if (!isSet(p.contentSha256()) || p.contentSha256().trim().length() != 64) {
      throw new IllegalArgumentException("必须给出 64 位十六进制的 content_sha256（照片是举证材料，要能校验）");
    }
    if (p.contentBytes() <= 0) {
      throw new IllegalArgumentException("content_bytes 必须大于 0");
    }
    if (p.capturedAt() == null) {
      throw new IllegalArgumentException("必须给出拍摄时刻：与登记时刻之差就是证据是不是补拍的");
    }
    sql.execute(
        "insert into return_receipt_photo(id,tenant_id,receipt_id,photo_source,source_reference,"
            + "photo_role,object_uri,content_sha256,content_bytes,content_type,captured_at,"
            + "caption,registered_by)"
            + " values(?,?::uuid,?::uuid,?,?,?,?,?,?::bigint,?,?::timestamptz,?,?::uuid)",
        UUID.randomUUID(),
        currentTenant(),
        receiptId,
        source.name(),
        // ⚠️ 同 insertLines：source_reference 是 NOT NULL DEFAULT ''，空值写空串不写 null。
        trimOrEmpty(p.sourceReference()),
        role.name(),
        p.objectUri().trim(),
        p.contentSha256().trim().toLowerCase(),
        p.contentBytes(),
        trimToNull(p.contentType()),
        p.capturedAt(),
        p.caption() == null ? "" : p.caption().trim(),
        userId);
  }

  // ================================================================ 工具

  private List<ReturnReceiptLine> linesOf(UUID id) {
    return sql.fetch(
            "select l.*, cl.id as case_line_id, cl.order_line_id, ol.sku_code, ol.sku_name,"
                + " ol.spec_snapshot, l2.authorized_qty"
                + " from return_receipt_line l"
                + " join rma_authorization_line l2"
                + "   on l2.tenant_id = l.tenant_id and l2.id = l.rma_authorization_line_id"
                + " join aftersales_case_line cl"
                + "   on cl.tenant_id = l2.tenant_id and cl.id = l2.case_line_id"
                + " join sales_order_line ol"
                + "   on ol.tenant_id = cl.tenant_id and ol.id = cl.order_line_id"
                + " where l.tenant_id = ?::uuid and l.receipt_id = ?::uuid"
                + " order by l.line_no, l.id",
            currentTenant(),
            id)
        .map(this::line);
  }

  private String nextReceiptNo() {
    long n = sql.fetchOne("select nextval('return_receipt_no_seq')").get(0, Long.class);
    return "RTR" + n;
  }

  private UUID currentTenant() {
    return UUID.fromString(
        sql.fetchOne("select current_setting('app.current_tenant')").get(0, String.class));
  }

  private Record requireRecord(UUID id) {
    Record head =
        sql.fetchOne(
            "select * from return_receipt where tenant_id = ?::uuid and id = ?::uuid",
            currentTenant(), id);
    if (head == null) {
      throw new IllegalArgumentException("退货实收单不存在或不属于当前租户：" + id);
    }
    return head;
  }

  private Record requireDraft(UUID id) {
    Record head = requireRecord(id);
    ReturnReceiptStatus status =
        ReturnReceiptStatus.valueOf(head.get("receipt_status", String.class));
    if (status.posted()) {
      throw new IllegalArgumentException(
          "退货实收单已入库，不能再改："
              + id
              + "（待检入库分录已生成，回冲属 11.2.3.2，未交付）");
    }
    if (status != ReturnReceiptStatus.DRAFT) {
      throw new IllegalArgumentException(
          "退货实收单已撤销（内容与行是证据不是草稿）：" + id);
    }
    return head;
  }

  /**
   * 乐观锁：版本必须与请求一致。
   *
   * <p>两个人同时扫同一个包裹时各写一次而表里只留得下后一个 ——
   * 少的那一次会让「这批货收了几件、判定了什么」出现两个答案，而这两个答案都要拿去和买家对质。
   */
  private void requireVersion(Record head, Long expectedVersion) {
    long current = head.get("version", Long.class);
    if (expectedVersion == null) {
      throw new IllegalArgumentException("必须给出 expectedVersion（乐观锁，避免并发入库互相覆盖）");
    }
    if (expectedVersion != current) {
      throw new IllegalArgumentException(
          "版本不符（当前 " + current + "，请求 " + expectedVersion + "），请刷新后重试");
    }
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

  private static String trimToNull(String value) {
    return isSet(value) ? value.trim() : null;
  }

  /** NOT NULL 列的空值表示：**空串**，不是 null（见 {@link #insertLines} 的注释）。 */
  private static String trimOrEmpty(String value) {
    return isSet(value) ? value.trim() : "";
  }

  private static String eventLabel(String code) {
    return switch (code) {
      case "CREATED" -> "登记草稿";
      case "UPDATED" -> "草稿要素变更";
      case "POSTED" -> "入库（进待检）";
      case "CANCELLED" -> "人工撤销";
      case "PHOTO_ADDED" -> "追加照片";
      default -> code;
    };
  }

  private static ReturnReceiptSectionCapability section(
      String section, String label, boolean available, String ownerModule, String reason,
      String landsIn) {
    return new ReturnReceiptSectionCapability(section, label, available, ownerModule, reason, landsIn);
  }

  private static ReturnReceiptRelatedModule related(
      String id, String label, boolean delivered, String ownerModule, String reason, String href) {
    return new ReturnReceiptRelatedModule(id, label, delivered, ownerModule, reason, href);
  }

  private static ReturnReceiptAction action(
      String code, String label, boolean allowed, String method, String path, boolean required,
      String precondition, String blockingReason, String ownerModule, String unavailableReason) {
    return new ReturnReceiptAction(
        code, label, allowed, method, path, required, precondition, blockingReason, ownerModule,
        unavailableReason);
  }

  private static ReturnReceiptBoundary boundary(
      String boundary, String ownerModule, String reason) {
    return new ReturnReceiptBoundary(boundary, ownerModule, reason);
  }

  private List<ReturnReceiptRelatedModule> relatedModules() {
    return capabilities().relatedModules();
  }

  /**
   * 当前可执行动作。
   *
   * <p>⚠️ {@code RESELL} / {@code INSPECT} / {@code ALLOCATE} <b>永远 allowed=false</b>：
   * 不是「暂时没做」，而是**刻意没有** —— 退回商品默认进待检是硬规则，
   * 而「直接转可售」正是这条规则要防的那件事。
   */
  private List<ReturnReceiptAction> actions(Record head, List<ReturnReceiptLine> lines, int unjudged) {
    ReturnReceiptStatus status =
        ReturnReceiptStatus.valueOf(head.get("receipt_status", String.class));
    boolean draft = status == ReturnReceiptStatus.DRAFT;
    String path = "/trade/return-receipts/" + head.get("id", UUID.class);
    return List.of(
        action("EDIT_DRAFT", "改草稿", draft, "PUT", path, true, "仅 DRAFT",
            draft ? null : "实收单已" + status.label() + "，行与照片是证据不是草稿", null, null),
        action("APPEND_PHOTO", "追加照片", draft, "POST", path + "/photos", false, "仅 DRAFT",
            draft ? null : "实收单已" + status.label() + "，证据不能在事后改", null, null),
        action("POST", "入库（进待检）", draft && !lines.isEmpty() && unjudged == 0, "POST",
            path + "/post", false, "仅 DRAFT，且两个判定维度都已判、有明细行",
            postBlocked(status, lines.isEmpty(), unjudged), null, null),
        action("CANCEL", "撤销", draft, "POST", path + "/cancel", false, "仅 DRAFT",
            draft ? null : "实收单已" + status.label() + "，撤销入口只在草稿期开放", null, null),
        action("RESELL", "转可售", false, null, null, false, "仅已入库且质检判定为可再销售",
            null, "11.2.3.2",
            "转可售 / 残次 / 报废**未交付**。本模块**刻意没有**这个端点："
                + "退回商品默认进待检是硬规则，而「直接转可售」就是这条规则要防的那件事。"),
        action("INSPECT", "质检判定", false, null, null, false, "仅已入库", null, "11.2.3.1",
            "可再销售 / 残次 / 报废判定**未交付**。本模块**刻意没有**这个端点。"),
        action("ALLOCATE", "分配到订单行", false, null, null, false, "仅已入库", null, "11.2.2.2",
            "实收 → 订单行分配与已退数量更新**未交付**。"));
  }

  private static String postBlocked(ReturnReceiptStatus status, boolean empty, int unjudged) {
    if (status.posted()) {
      return "实收单已入库，待检入库分录已生成（回冲属 11.2.3.2，未交付）";
    }
    if (status != ReturnReceiptStatus.DRAFT) {
      return "实收单已撤销，内容与行是证据不是草稿";
    }
    if (empty) {
      return "没有任何明细行：空单入库等于什么都没收到却生成了入库记录";
    }
    if (unjudged > 0) {
      return "还有 " + unjudged + " 行未判定外包装与商品状态两个维度";
    }
    return null;
  }
}
