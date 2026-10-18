package com.starcon.trade;

import static com.starcon.trade.RmaInstructionModels.*;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Service;

/**
 * 11.2.1.2 退货指令下发。
 *
 * <p><b>本类是 V165 五张新表的写入所有者</b>（rma_instruction / rma_instruction_line /
 * rma_label / rma_return_tracking_event / rma_instruction_event），对 V161 的
 * rma_authorization 簇与它下游的订单 / 售后表<b>只读</b>。
 * 刻意不加外键、不 ALTER 既有表 ⇒ 跨车道 DDL 回归面 = 0。
 *
 * <p><b>七条硬纪律</b>：
 *
 * <ol>
 *   <li><b>每一条查询都显式带 {@code tenant_id} 谓词</b>，不依赖 RLS 当唯一防线。
 *       指令带着<b>退货地址</b>（收货人姓名 / 电话）、<b>买家邮箱</b>与<b>买家回寄运单号</b>，
 *       而详情 / 下发 / 取标签文件端点都是<b>按 id 直达</b>的 URL —— 漏掉租户谓词就是跨租户泄露。</li>
 *   <li><b>地址与时限是快照，不是引用</b>（{@link #create}）。指引是<b>发给买家的一份文件</b>，
 *       买家已经照着它寄了之后再改授权单上的地址，不能把这份已发出去的指引悄悄换掉。</li>
 *   <li><b>标签文件是真字节 + SHA-256</b>（{@link #labelContent}）：读取时<b>重算并比对</b>。
 *       被谁改过立刻报「完整性校验失败」，而不是照常渲染一份可能被篡改的地址 ——
 *       买家照着它寄，货寄到谁家由篡改者决定。</li>
 *   <li><b>「已下发」不等于「已寄回」</b>。本模块<b>没有</b> RECEIVED 状态：
 *       响应里的 {@code goodsReceived} 恒为 false 且带归属模块号。
 *       蓝图加粗句「本系统只负责指引与跟踪，<b>不控制实物</b>」。</li>
 *   <li><b>轨迹是追加式事实，不驱动库存</b>。DELIVERED 事件只表示「承运商说送到了」，
 *       本服务<b>不碰任何库存表</b> —— 退回商品默认进待检是 11.2.2.1 / 11.2.3.1 的硬规则。</li>
 *   <li><b>时间绑定</b>：时间一律 {@code ?::timestamptz} + {@link OffsetDateTime}。
 *       {@code Timestamp.valueOf(LocalDateTime)} 会静默偏 −28800s，
 *       而寄回时限算错一天就是「提前一天自动失效」。</li>
 *   <li><b>状态流转只有一个入口</b>（{@link #transition}）：每次改状态都过版本校验，都写留痕。</li>
 * </ol>
 */
@Service
public class RmaInstructionService {

  /** 列表单页上限。 */
  private static final int MAX_PAGE_SIZE = 200;

  /** 临期阈值 24 小时（与 V156 / V161 同一口径）。 */
  private static final long DUE_SOON_SECONDS = 24L * 3600L;

  /**
   * 单次到期扫描上限。
   *
   * <p>不是「怕慢」而是<b>怕静默漏扫</b>：一次扫 10 万行会让事务长到把 RLS 租户设置一直挂着，
   * 而没人愿意手工翻页把剩下的补上。
   */
  private static final int EXPIRE_SCAN_LIMIT = 500;

  private final DSLContext sql;

  public RmaInstructionService(DSLContext sql) {
    this.sql = sql;
  }

  // ================================================================ 能力声明

  /**
   * 能力声明：职责句的三个动词逐块报可用性，下游逐个报 Delivered=false 与归属模块号。
   *
   * <p>让界面能说清「为什么这张单没有已收到标记」「为什么标签是纯文本不是 PDF」，
   * 而不是让它们显示成空白让人以为「这些能力没做」。
   */
  public RmaInstructionCapabilities capabilities() {
    return new RmaInstructionCapabilities(
        List.of(
            "退货授权 rma_authorization 簇（V161，同库只读）—— 指令的上游，地址与时限的真值来源",
            "退货指引 rma_instruction + rma_instruction_line（V165；地址与时限为**快照**，下发即冻结）",
            "退货标签 rma_label（V165；**真字节落库 + SHA-256 校验和**，读取时重算比对）",
            "回寄物流轨迹 rma_return_tracking_event（V165，**追加式**，运行角色 UPDATE/DELETE 已收回）",
            "指引状态留痕 rma_instruction_event（V165，追加式，由 DB 触发器按状态变更自动追加）",
            "商品行真值 sales_order_line（V99 实时读，**不复制** SKU / 单价到指引上）"),
        List.of(
            section("returnInstruction", "退货指引（地址 / 要求 / 时限）", true, null, null,
                "rma_instruction 三段内容 + rma_instruction_line 逐行应寄数量"),
            section("addressSnapshot", "退货地址", true, null,
                "蓝图输入句写「退货地址（3.6.3 或平台 RMA 地址）」。**实查集成头**：3.6.3.1 已交付"
                    + "但迁移在 **catalog 服务**（V31__party_addresses_contacts.sql），是另一个数据库 "
                    + "⇒ 按 AGENTS.md 模块边界纪律**禁止跨服务 SQL / 视图 / FK**，本服务读不到它。"
                    + "卷 2 平台地址同理（channel 独立库）。⇒ 照抄 V150 / V156 / V157 / V161 的既有解法："
                    + "**从授权单复制快照**并连来源一起带过来（address_source + source_reference），"
                    + "3.6.3 交付后由同步器按**同一形状**写入并把来源改成 MODULE_*，"
                    + "**表结构与查询都不用改**。",
                "rma_instruction.return_address_*（V165，来源与 V161 同形）"),
            section("channelSelection", "渠道选择", true, null,
                "**刻意拆成两个渠道**：delivery_channel 是「指引怎么送达买家」"
                    + "（邮箱 / 平台站内消息 / 下载打印 / 外部回调），"
                    + "rma_label.carrier_code + service_level 是「货用哪个承运商寄回」。"
                    + "压成一个字段会让「邮件已发但买家自己买了运费单」这种**真实且常见**的"
                    + "状态无处安放 —— 那时候指引已送达，但承运商还没定。",
                "rma_instruction.delivery_channel + rma_label.carrier_code / service_level"),
            section("returnLabel", "退货标签文件", true, null,
                "⚠️ **刻意不生成 PDF**：真实面单要调 10 物流的承运商接口（**未交付**），"
                    + "自造一个「PDF」只会得到一份长得像面单、扫码扫不通的废纸。"
                    + "两种真能产出的来源：① SELF_GENERATED_TEMPLATE —— 按授权单真实要素渲染的"
                    + "可打印标签文本；② TENANT_PROVIDED_FILE —— 租户已从外部渠道拿到标签、登记其内容。"
                    + "两种都是**真实字节落库**，校验和是内容字节的 SHA-256，读取时重算比对。",
                "rma_label.label_content + label_checksum（V165）"),
            section("returnTracking", "回寄物流轨迹跟踪", true, null,
                "承运商与运单号挂在**事件**上而不是指令头上：买家可以分多个包裹寄回、"
                    + "也可以换承运商重寄（真实且常见），挂在头上会让「第二个包裹」无处安放。"
                    + "**当前状态由「该运单号最后一条事件」实时算出**，不单独存列 —— "
                    + "存一个 current_status 列就得回答「它和事件表哪个为准」。"
                    + "⚠️ 承运商回调**未接入**、10 物流轮询**未交付**，当前只有人工登记一个来源。",
                "rma_return_tracking_event（V165，追加式）"),
            section("carrierWaybill", "退货运单", false, "10 物流",
                "退货运单的**创建与面单接口**属 10 物流，**未交付**。本模块只负责"
                    + "「指引 + 标签 + 轨迹」，不代建运单、不代打面单。"
                    + "枚举里的 MODULE_10_CARRIER_LABEL_API 就是留给它的接入口子。",
                null),
            section("buyerNotification", "通知买家", false, "15 客服",
                "蓝图下游写「15 客服（通知买家）」。本模块交付的是**指引这份数据**，"
                    + "实际往平台站内信 / 邮件通道发出去属 15 客服，**未交付**。"
                    + "所以 delivery_channel 记的是「打算怎么发」，**不是**「已经发出去了」—— "
                    + "真正送达的时点是 issued_at，两者不混。",
                null),
            section("goodsReceived", "退货实收", false, "11.2.2.1 退货入库",
                "蓝图加粗句「本系统只负责指引与跟踪，**不控制实物**」的直接落点："
                    + "登记退回实物（扫码核对 SKU 与序列号、清点数量、拍照留证）属 11.2.2.1，**未交付**。"
                    + "⇒ 本模块**没有 RECEIVED 状态**，响应里的 goodsReceived 恒为 false 并带归属模块号。"
                    + "编一个「已签收」状态会让下游以为货已到手并开始做入库分录 —— "
                    + "而退回商品**默认进待检**，直接当已收货处理会把残次品当新品卖出去。",
                null),
            section("receivedAllocation", "实收分配与已退数量", false, "11.2.2.2 实收分配",
                "把实收分配到授权行与原订单行、处理超收 / 少收、**更新订单行已退数量**均**未交付**。"
                    + "本模块因此只在指令行上记「**应寄回**」数量（≤ 授权量），"
                    + "**不记「已收到」数量** —— 两个口径不能共用一列。",
                null),
            section("inspection", "退货质检与判定", false, "11.2.3.1 / 11.2.3.2",
                "质检结论（可再销售 / 残次 / 报废）与库存状态流转**未交付**，"
                    + "且**必须等实收到货**。本模块的轨迹 DELIVERED 事件**不驱动任何库存分录**。",
                null)),
        relatedModules(),
        actionEntries(null, false, false),
        List.of(
            new RmaInstructionBoundary("登记退回实物与实收分配", "11.2.2.1 / 11.2.2.2",
                "本模块的输出是「买家知道了该往哪寄」。货有没有到、到了几件、是不是残次，"
                    + "是两个模块的写入所有权。本模块既不代写也不建影子表。"),
            new RmaInstructionBoundary("代建退货运单与打面单", "10 物流",
                "运单创建与面单接口属 10 物流。本模块的 SELF_GENERATED_TEMPLATE 标签是"
                    + "**我们自己拼的可打印标签**，不含承运商运单号 —— 带了就等于声称已对接。"),
            new RmaInstructionBoundary("代发通知给买家", "15 客服",
                "delivery_channel 记的是**打算怎么发**，不是「已经发出去了」。"
                    + "真正送达渠道属 15 客服；本模块的 issued_at 是「我们已把指引交出去」的时点。"),
            new RmaInstructionBoundary("改写退货授权单", "11.2.1.1 退货授权单",
                "rma_authorization 是 11.2.1.1 的写入所有者。本模块**只读**它，"
                    + "建单时复制地址与时限快照，之后各改各的 —— "
                    + "代改会让「买家收到的指引上写的地址是谁写的」这个问题出现两个答案。"),
            new RmaInstructionBoundary("从轨迹事件推断已收货", "11.2.2.1",
                "轨迹里的 DELIVERED 只表示「**承运商说**这个包裹送到了」。"
                    + "签收人是不是买家、代收点有没有代拆、包裹里是不是全都在，"
                    + "都得由实收登记按**收货事件去重**判定（11.2.2.1 的硬规则）。")),
        "一个状态列到底：DRAFT → ISSUED →（ACKNOWLEDGED）+ 在途三态 → EXPIRED / CANCELLED。"
            + "**没有 RECEIVED**：蓝图加粗句「本系统只负责指引与跟踪，不控制实物」。"
            + "**EXPIRED 只判「时限过了」，不判「货没回来」** —— 时限过了而货在路上的单子"
            + "在承运商口径里仍可能签收成功，把它算成「已失效」会让下游拒收一件真收到的货。",
        "地址与时限是**快照**而不是引用：建单时从 rma_authorization 复制，"
            + "**下发即冻结**（DB 触发器 guard_rma_instruction_frozen 在数据库层钉死）。"
            + "理由不是「严谨」而是**买家已经照着旧内容寄了** —— 改地址等于让货寄丢，"
            + "改时限等于凭空多给或少给了买家几天（而买家并不知道）。"
            + "要改只能 CANCEL 后重新建一张。快照连来源一起带（address_source + source_reference），"
            + "3.6.3 与平台 RMA 两个口子正式打通后**表结构与查询都不用改**。",
        "标签是**真字节 + SHA-256**：内容由服务层从库里真值渲染（自出模板）或"
            + "由租户登记外部文件（TENANT_PROVIDED_FILE），校验和是内容字节的 SHA-256，"
            + "**读取时重算并比对** —— 被改过立刻报「完整性校验失败」，"
            + "而不是照常渲染一份可能被篡改的地址（买家照着它寄，货寄到谁家由篡改者决定）。"
            + "⚠️ **刻意不生成 PDF**：真实面单要走 10 物流的承运商接口（未交付）。",
        "承运商与运单号挂在**事件**上而不是指令头上：买家可以分多个包裹寄回、"
            + "也可以换承运商重寄。**当前状态按运单号分组、由最后一条事件实时算出**，"
            + "不单独存列。事件是**追加式**（运行角色 UPDATE/DELETE 已在 DB 层收回），"
            + "并靠 (指令, 承运商, 运单号, 事件码, 发生时刻) 唯一索引给承运商重复推送去重。"
            + "⚠️ **不驱动任何库存**：DELIVERED 只表示承运商口径的送达，实收判定归 11.2.2.1。",
        "**没有 RECEIVED**：蓝图加粗句「本系统只负责指引与跟踪，不控制实物」。"
            + "指引下发后货可能在路上，也可能买家压根没寄。所以本模块有寄回时限倒计时与"
            + "时限到期自动失效，却**没有**「已收到」状态 —— 登记实收属 11.2.2.1，未交付。"
            + "响应里 goodsReceived 恒为 false 并带 ownerModule。");
  }

  private static RmaInstructionSectionCapability section(
      String section, String label, boolean available, String ownerModule, String reason,
      String landsIn) {
    return new RmaInstructionSectionCapability(section, label, available, ownerModule, reason, landsIn);
  }

  /** 关联模块可用性。当前未交付的一律报 false 与归属，**不建影子表**。 */
  private List<RmaInstructionRelatedModule> relatedModules() {
    return List.of(
        new RmaInstructionRelatedModule("RMA_AUTHORIZATION", "退货授权单", true, "11.2.1.1",
            "**已交付**（V161）。本模块的指令挂在它下面，地址与时限都从它复制快照。",
            "/aftersales/returns"),
        new RmaInstructionRelatedModule("RETURN_WAYBILL", "退货运单", false, "10 物流",
            "退货运单的创建与面单接口**未交付**。本模块只负责指引、标签与轨迹，"
                + "不代建运单、不代打面单 —— 自造一张运单会被当成已对接。", null),
        new RmaInstructionRelatedModule("BUYER_NOTIFICATION", "通知买家", false, "15 客服",
            "蓝图下游写「15 客服（通知买家）」，**未交付**。本模块交付的是**指引这份数据**；"
                + "实际发进平台站内信 / 邮件通道属 15 客服。所以 delivery_channel 记的是"
                + "「打算怎么发」，不是「已经发出去了」。", null),
        new RmaInstructionRelatedModule("RECEIPT", "退货实收登记", false, "11.2.2.1",
            "扫码核对 SKU 与序列号、清点数量、拍照留证、判定外包装与商品状态**未交付**。"
                + "登记实收必须等它落地 —— **本系统只负责指引与跟踪，不控制实物**。", null),
        new RmaInstructionRelatedModule("RECEIPT_ALLOCATION", "实收分配与已退数量", false, "11.2.2.2",
            "把实收分配到授权行与原订单行、处理超收 / 少收、**更新订单行已退数量**均**未交付**。"
                + "本模块因此只在指令行上记「**应寄回**」数量，不记「已收到」数量 —— "
                + "两个口径共用一列会让「该寄 3 件」和「收到了 3 件」永远分不清。", null),
        new RmaInstructionRelatedModule("INSPECTION", "退货质检与判定", false, "11.2.3.1 / 11.2.3.2",
            "质检结论（可再销售 / 残次 / 报废）与库存状态流转**未交付**，"
                + "且**必须等实收到货**。本模块的轨迹 DELIVERED 事件**不驱动任何库存分录** —— "
                + "退回商品默认进待检，直接当已收货处理会把残次品当新品卖出去。", null));
  }

  /**
   * 操作入口。
   *
   * <p>⚠️ <b>标签相关的两个动作的可用性依赖「当前有没有未作废的标签」</b>，
   * 所以它们要吃 {@code hasLiveLabel} / {@code labelDelivered} 两个事实 ——
   * 写死 {@code allowed=false} 会让「有标签可作废」时按钮也灰着，
   * 而写死 {@code true} 会让「还没出标签」时显示一个点了必报错的按钮。
   */
  private List<RmaInstructionAction> actionEntries(
      RmaInstructionStatus status, boolean hasLiveLabel, boolean labelDelivered) {
    boolean draft = status == null || status == RmaInstructionStatus.DRAFT;
    boolean issued = status != null && status.deliveredToBuyer();
    String terminal = status != null && status.terminal()
        ? "退货指令已结案（" + status.label() + "）：内容与行是证据不是草稿，不能再改"
        : null;
    String noLabel = "当前指令没有未作废的标签可以处理";
    return List.of(
        new RmaInstructionAction("CREATE", "登记退货指引草稿", true, "POST",
            "/trade/rma-instructions", false, null, null, null, null),
        new RmaInstructionAction("UPDATE_DRAFT", "修改草稿要素", draft, "PUT",
            "/trade/rma-instructions/{id}", true, terminal,
            terminal != null ? terminal : (draft ? null : "只有草稿可改：指引下发后内容即冻结"),
            null, null),
        new RmaInstructionAction("ISSUE", "下发退货指引", draft, "POST",
            "/trade/rma-instructions/{id}/issue", true, terminal,
            terminal != null ? terminal : (draft ? null : "只有草稿可下发：已下发的指引不能重发"),
            null, null),
        new RmaInstructionAction("ACKNOWLEDGE", "登记买家已确认收到", issued, "POST",
            "/trade/rma-instructions/{id}/acknowledge", true, terminal,
            terminal != null ? terminal
                : (issued ? null : "只有已下发的指令需要登记买家确认（未下发就没有回执）"),
            null, null),
        new RmaInstructionAction("CANCEL", "撤销指引", status != null && status.open(), "POST",
            "/trade/rma-instructions/{id}/cancel", true, terminal,
            terminal != null ? terminal
                : (status != null && status.open() ? null : "已结案的指令不能撤销"),
            null, null),
        new RmaInstructionAction("EXPIRE_OVERDUE", "时限到期自动失效", true, "POST",
            "/trade/rma-instructions/expire-overdue", false, null, null, null, null),
        new RmaInstructionAction("GENERATE_LABEL", "生成退货标签", issued && !hasLiveLabel, "POST",
            "/trade/rma-instructions/{id}/label", false, terminal,
            terminal != null ? terminal
                : (issued
                    ? "这张指令已经有一张未作废的标签了。要换请先作废再重新生成"
                    : "只有已下发的指令才需要出标签：买家没收到指引，标签寄给谁？"),
            null, null),
        new RmaInstructionAction("DELIVER_LABEL", "交付退货标签", hasLiveLabel && !labelDelivered, "POST",
            "/trade/rma-instructions/{id}/label/deliver", false,
            hasLiveLabel && !labelDelivered ? null : terminal,
            terminal != null ? terminal
                : (labelDelivered
                    ? "这张标签已经交付过了（重复交付会让人以为贴了两张，而买家只贴了一张）"
                    : noLabel),
            null, null),
        new RmaInstructionAction("VOID_LABEL", "作废退货标签", hasLiveLabel, "POST",
            "/trade/rma-instructions/{id}/label/void", true,
            hasLiveLabel ? null : terminal,
            terminal != null ? terminal : (hasLiveLabel ? null : noLabel),
            null, null),
        new RmaInstructionAction("GET_LABEL_CONTENT", "查看标签文件内容", hasLiveLabel, "GET",
            "/trade/rma-instructions/{id}/label", false,
            hasLiveLabel ? null : terminal,
            terminal != null ? terminal : (hasLiveLabel ? null : noLabel),
            null, null),
        new RmaInstructionAction("APPEND_TRACKING", "登记回寄轨迹", issued, "POST",
            "/trade/rma-instructions/{id}/tracking", false, terminal,
            terminal != null ? terminal
                : (issued ? null : "只有已下发的指令才可能收到包裹 —— 未下发时买家手上没有指引"),
            null, null),
        new RmaInstructionAction("RECEIVE", "登记退货实收", false, null, null, false, null, null,
            "11.2.2.1 退货入库",
            "登记退回实物（扫码核对、清点、拍照留证）属 11.2.2.1，**未交付**。"
                + "蓝图加粗句「本系统只负责指引与跟踪，**不控制实物**」："
                + "指引已下发不等于货已到手。"),
        new RmaInstructionAction("CREATE_WAYBILL", "创建退货运单", false, null, null, false, null, null,
            "10 物流",
            "运单创建与面单接口属 10 物流，**未交付**。本模块的模板标签是我们自己拼的"
                + "可打印标签，**不含承运商运单号**。"));
  }

  // ================================================================ 列表

  /**
   * 列表：真分页 + 多维筛选 + 排序白名单 + 与分页无关的全量汇总。
   */
  public RmaInstructionPage list(RmaInstructionListQuery query) {
    int page = query.page() == null || query.page() < 0 ? 0 : query.page();
    int size = query.size() == null || query.size() <= 0 ? 20 : Math.min(query.size(), MAX_PAGE_SIZE);
    RmaInstructionSort sort = RmaInstructionSort.parse(query.sort());

    // 整页共用的参考时刻：判定时效、汇总计数、每行 remainingSeconds 全用它。
    OffsetDateTime at = OffsetDateTime.now(ZoneOffset.UTC);

    List<Object> binds = new ArrayList<>();
    List<String> where = conditions(query, binds, at);
    String clause = where.isEmpty() ? "" : " where " + String.join(" and ", where);

    // 左连退货授权单与订单：单号要显示，而这两张表若因故缺失**这条指令仍要显示**
    // ——「列表里没有这条指引」在售后场景里比「单号缺失」严重得多。
    String fromBase =
        " from rma_instruction r"
            + " left join rma_authorization a"
            + "   on a.tenant_id = r.tenant_id and a.id = r.rma_id"
            + " left join aftersales_case c"
            + "   on c.tenant_id = r.tenant_id and c.id = a.case_id"
            + " left join sales_order o"
            + "   on o.tenant_id = c.tenant_id and o.id = c.order_id";
    String from =
        fromBase
            + " left join lateral ("
            + "   select coalesce(sum(l.expected_qty), 0) as expected_qty, count(*) as line_count"
            + "     from rma_instruction_line l"
            + "    where l.tenant_id = r.tenant_id and l.instruction_id = r.id) w on true"
            + " left join lateral ("
            + "   select lb.label_no, lb.label_status, lb.carrier_code, lb.tracking_no"
            + "     from rma_label lb"
            + "    where lb.tenant_id = r.tenant_id and lb.instruction_id = r.id"
            + "      and lb.label_status <> 'VOIDED'"
            + "    order by lb.generated_at desc limit 1) lb on true"
            + " left join lateral ("
            + "   select count(*) as event_count,"
            + "          (array_agg(e.event_code order by e.occurred_at desc, e.id desc))[1]"
            + "            as latest_event_code"
            + "     from rma_return_tracking_event e"
            + "    where e.tenant_id = r.tenant_id and e.instruction_id = r.id) tr on true";

    long total =
        sql.fetchOne("select count(*)" + fromBase + clause, binds.toArray()).get(0, Long.class);

    // ⚠️ 占位符顺序必须与 SQL **文本出现顺序**一致：
    //   [timelinessCase 里的两个 at] → [from / where 里的 binds] → [limit] → [offset]。
    //   jOOQ 按位置绑定，顺序错了不会报错、只会把值绑到别的 ? 上 —— 那是最难查的一类 bug。
    List<Object> pageBinds = new ArrayList<>();
    pageBinds.add(at);
    pageBinds.add(at);
    pageBinds.add(at);
    pageBinds.addAll(binds);
    pageBinds.add(size);
    pageBinds.add((long) page * size);

    List<RmaInstructionRow> rows =
        sql.fetch(
                "select r.*, a.rma_no, a.rma_status, c.id as case_id, c.case_no, c.order_id, o.order_no,"
                    + " w.expected_qty, w.line_count,"
                    + " lb.label_no, lb.label_status, lb.carrier_code, lb.tracking_no,"
                    + " tr.event_count, tr.latest_event_code,"
                    + timelinessCase()
                    + from
                    + clause
                    + " order by "
                    + sort.sql()
                    + " limit ? offset ?",
                pageBinds.toArray())
            .map(r -> row(r, at));

    return new RmaInstructionPage(
        rows,
        page,
        size,
        total,
        summary(at, where, binds),
        at,
        "真分页（LIMIT/OFFSET + 独立 COUNT），汇总与排序不受分页影响。",
        "临期阈值 24 小时。整页共用一个参考时刻 evaluatedAt，翻页时同一条指令不会在「临期」和「正常」"
            + "之间跳。只有 DRAFT / ISSUED / ACKNOWLEDGED 计入倒计时（其余状态 returnByAt 无意义）。",
        "**没有** goodsReceived 列：登记退货实收属 11.2.2.1，**未交付**。"
            + "蓝图加粗句「本系统只负责指引与跟踪，**不控制实物**」—— "
            + "指引已下发不等于货已到手，所以这里只有寄回时限倒计时与时限到期自动失效。",
        "排序白名单四个键（created_desc / due_asc / status_asc / channel_asc）；"
            + "其余取值落回 created_desc，不接受任意表达式。");
  }

  /**
   * 时效的两个投影列（状态 + 剩余秒数），SQL 文本形状固定。
   *
   * <p>⚠️ <b>占位符恰好三个</b>：两个在 timeliness_state 的 CASE 里，
   * <b>第三个在 remaining_seconds 的 CASE 里</b>。调用方必须按顺序绑<b>三个</b>参考时刻，
   * 再绑 where 里的值 —— 少绑一个不会报错，只会报一句「未设定参数值 6 的内容」。
   *
   * <p>⚠️ 刻意不调用 V156 的 {@code aftersales_timeliness_state}：那个函数判的是
   * <b>售后单</b>的平台回复 / 处理时限（平台什么时候要我们答），这里判的是
   * <b>买家什么时候必须把货寄回</b> —— 两个时钟的起点、后果与责任方都不同。
   */
  private static String timelinessCase() {
    return " case when r.instruction_status not in ('DRAFT','ISSUED','ACKNOWLEDGED') then 'CLOSED'"
        + "      when r.return_by_at < ?::timestamptz then 'OVERDUE'"
        + "      when r.return_by_at <= ?::timestamptz + interval '24 hours' then 'DUE_SOON'"
        + "      else 'ON_TRACK' end as timeliness_state,"
        + " case when r.instruction_status in ('DRAFT','ISSUED','ACKNOWLEDGED')"
        + "      then extract(epoch from (r.return_by_at - ?::timestamptz))::bigint"
        + "      else null end as remaining_seconds";
  }

  private List<String> conditions(RmaInstructionListQuery query, List<Object> binds, OffsetDateTime at) {
    List<String> where = new ArrayList<>();
    where.add("r.tenant_id = ?::uuid");
    binds.add(currentTenant());
    // ⚠️ 筛选值**先过枚举**再进 SQL：直接绑一个拼错的状态值，表里恰好没有匹配的行时
    //   表级 CHECK 根本不会被求值（CHECK 只在 INSERT/UPDATE 时检查），结果是静默返回零行 ——
    //   运营会读成「没有这样的指令」，而真相是筛选条件本身写错了。
    //   这与 11.2.1.1 对 decision 的同一纪律（照抄）。
    if (isSet(query.status())) {
      where.add("r.instruction_status = ?");
      binds.add(parseEnum(RmaInstructionStatus.class, query.status(), "指令状态").name());
    }
    if (isSet(query.channel())) {
      where.add("r.delivery_channel = ?");
      binds.add(parseEnum(RmaDeliveryChannel.class, query.channel(), "指引送达渠道").name());
    }
    if (query.rmaId() != null) {
      where.add("r.rma_id = ?::uuid");
      binds.add(query.rmaId());
    }
    if (Boolean.TRUE.equals(query.overdueOnly())) {
      where.add(
          "r.instruction_status in ('DRAFT','ISSUED','ACKNOWLEDGED')"
              + " and r.return_by_at < ?::timestamptz");
      binds.add(at);
    }
    // ⚠️ 下面两个筛选**只用基表 + EXISTS 子查询**，不引用列表查询里的 lateral 别名：
    //   fromBase（count / summary 用的那条 FROM）**没有** join 那两张 lateral，
    //   而 where 子句是三处查询共用的 —— 在这里写 lb.carrier_code 会让 count 与
    //   summary 直接语法错，而 rows 那条却跑得通，症状是「列表能开、总览一开就 500」。
    if (isSet(query.carrier())) {
      // 真实模糊检索：标签上的承运商 **或** 任何一条轨迹事件的承运商都命中
      // —— 买家换承运商重寄时，标签上的那个承运商已经不是当下真正在跑的那个了。
      where.add("(exists (select 1 from rma_label lb"
          + " where lb.tenant_id = r.tenant_id and lb.instruction_id = r.id"
          + "   and lb.label_status <> 'VOIDED' and lb.carrier_code ilike ?)"
          + " or exists (select 1 from rma_return_tracking_event e"
          + " where e.tenant_id = r.tenant_id and e.instruction_id = r.id"
          + "   and e.carrier_code ilike ?))");
      String like = "%" + query.carrier().trim() + "%";
      binds.add(like);
      binds.add(like);
    }
    if (isSet(query.trackingNo())) {
      // 客服手上只有一个运单号时的入口：标签上的与轨迹里的都算命中。
      where.add("(exists (select 1 from rma_label lb"
          + " where lb.tenant_id = r.tenant_id and lb.instruction_id = r.id"
          + "   and lb.label_status <> 'VOIDED' and lb.tracking_no ilike ?)"
          + " or exists (select 1 from rma_return_tracking_event e"
          + " where e.tenant_id = r.tenant_id and e.instruction_id = r.id"
          + "   and e.tracking_no ilike ?))");
      String like = "%" + query.trackingNo().trim() + "%";
      binds.add(like);
      binds.add(like);
    }
    if (isSet(query.keyword())) {
      // 真实模糊检索：指令单号 / 退货授权单号 / 售后单号 / 订单号 / 买家邮箱。
      where.add("(r.instruction_no ilike ? or a.rma_no ilike ? or c.case_no ilike ?"
          + " or o.order_no ilike ? or r.recipient_email ilike ?)");
      String like = "%" + query.keyword().trim() + "%";
      binds.add(like);
      binds.add(like);
      binds.add(like);
      binds.add(like);
      binds.add(like);
    }
    return where;
  }

  private RmaInstructionSummary summary(OffsetDateTime at, List<String> where, List<Object> binds) {
    String clause = where.isEmpty() ? "" : " where " + String.join(" and ", where);
    Record row =
        sql.fetchOne(
            "select count(*) as total,"
                + " count(*) filter (where r.instruction_status = 'DRAFT') as draft,"
                + " count(*) filter (where r.instruction_status = 'ISSUED') as issued,"
                + " count(*) filter (where r.instruction_status = 'ACKNOWLEDGED') as acked,"
                + " count(*) filter (where r.instruction_status = 'EXPIRED') as expired,"
                + " count(*) filter (where r.instruction_status = 'CANCELLED') as cancelled,"
                + " count(*) filter (where r.instruction_status in ('DRAFT','ISSUED','ACKNOWLEDGED')"
                + "   and r.return_by_at < ?::timestamptz) as overdue,"
                + " count(*) filter (where exists (select 1 from rma_label lb"
                + "   where lb.tenant_id = r.tenant_id and lb.instruction_id = r.id"
                + "     and lb.label_status <> 'VOIDED')) as with_label,"
                + " count(*) filter (where exists (select 1 from rma_return_tracking_event e"
                + "   where e.tenant_id = r.tenant_id and e.instruction_id = r.id)) as with_tracking"
                + " from rma_instruction r"
                + " left join rma_authorization a on a.tenant_id = r.tenant_id and a.id = r.rma_id"
                + " left join aftersales_case c on c.tenant_id = r.tenant_id and c.id = a.case_id"
                + " left join sales_order o on o.tenant_id = c.tenant_id and o.id = c.order_id"
                + " left join lateral ("
                + "   select lb.carrier_code, lb.tracking_no from rma_label lb"
                + "    where lb.tenant_id = r.tenant_id and lb.instruction_id = r.id"
                + "      and lb.label_status <> 'VOIDED'"
                + "    order by lb.generated_at desc limit 1) lb on true"
                + " left join lateral ("
                + "   select count(*) as event_count from rma_return_tracking_event e"
                + "    where e.tenant_id = r.tenant_id and e.instruction_id = r.id) tr on true"
                + clause,
            concat(at, binds));
    long total = row.get("total", Long.class);
    long draft = row.get("draft", Long.class);
    long issued = row.get("issued", Long.class);
    long acked = row.get("acked", Long.class);
    return new RmaInstructionSummary(
        total,
        draft,
        issued,
        acked,
        row.get("expired", Long.class),
        row.get("cancelled", Long.class),
        row.get("overdue", Long.class),
        issued + acked,
        row.get("with_label", Long.class),
        row.get("with_tracking", Long.class),
        // 已收货数恒为 0：登记实收属 11.2.2.1，未交付（见 capabilities 的 goodsReceivedNote）。
        0L);
  }

  private static Object[] concat(OffsetDateTime at, List<Object> binds) {
    List<Object> all = new ArrayList<>();
    all.add(at);
    all.addAll(binds);
    return all.toArray();
  }

  // ================================================================ 详情

  /** 单张退货指令全景：头 + 行 + 状态留痕 + 轨迹 + 当前标签 + 可执行动作 + 上下游可用性。 */
  public RmaInstructionDetail detail(UUID id) {
    OffsetDateTime at = OffsetDateTime.now(ZoneOffset.UTC);

    Record head =
        sql.fetchOne(
            "select r.*, a.rma_no, a.rma_status, c.id as case_id, c.case_no, c.order_id, o.order_no"
                + " from rma_instruction r"
                + " left join rma_authorization a on a.tenant_id = r.tenant_id and a.id = r.rma_id"
                + " left join aftersales_case c on c.tenant_id = r.tenant_id and c.id = a.case_id"
                + " left join sales_order o on o.tenant_id = c.tenant_id and o.id = c.order_id"
                + " where r.tenant_id = ?::uuid and r.id = ?::uuid",
            currentTenant(), id);
    if (head == null) {
      // 跨租户的 ID 与不存在的 ID **同一条消息**（这个端点是按 id 直达的 URL）。
      throw new IllegalArgumentException("退货指令不存在或不属于当前租户：" + id);
    }

    RmaInstructionStatus status =
        RmaInstructionStatus.valueOf(head.get("instruction_status", String.class));

    List<RmaInstructionLine> lines =
        sql.fetch(
                "select l.*, rl.authorized_qty, cl.order_line_id, ol.sku_code, ol.sku_name,"
                    + " ol.spec_snapshot, ol.unit_price, c.currency"
                    + " from rma_instruction_line l"
                    + " join rma_authorization_line rl"
                    + "   on rl.tenant_id = l.tenant_id and rl.id = l.rma_line_id"
                    + " join aftersales_case_line cl"
                    + "   on cl.tenant_id = rl.tenant_id and cl.id = rl.case_line_id"
                    + " join sales_order_line ol"
                    + "   on ol.tenant_id = cl.tenant_id and ol.id = cl.order_line_id"
                    + " left join aftersales_case c"
                    + "   on c.tenant_id = cl.tenant_id and c.id = cl.case_id"
                    + " where l.tenant_id = ?::uuid and l.instruction_id = ?::uuid"
                    + " order by ol.line_no, l.id",
                currentTenant(), id)
            .map(
                r -> {
                  BigDecimal unitPrice = r.get("unit_price", BigDecimal.class);
                  return new RmaInstructionLine(
                      r.get("id", UUID.class),
                      r.get("rma_line_id", UUID.class),
                      cl(r, "order_line_id"),
                      r.get("sku_code", String.class),
                      r.get("sku_name", String.class),
                      r.get("spec_snapshot", String.class),
                      unitPrice == null ? null : unitPrice.toPlainString(),
                      r.get("currency", String.class),
                      nz(r.get("authorized_qty", BigDecimal.class)),
                      nz(r.get("expected_qty", BigDecimal.class)),
                      blankToEmpty(r.get("line_note", String.class)));
                });

    List<RmaInstructionEvent> events =
        sql.fetch(
                "select id, event_type, from_status, to_status, reason_note, occurred_by, occurred_at"
                    + " from rma_instruction_event"
                    + " where tenant_id = ?::uuid and instruction_id = ?::uuid"
                    + " order by occurred_at, id",
                currentTenant(), id)
            .map(
                r ->
                    new RmaInstructionEvent(
                        r.get("id", UUID.class),
                        r.get("event_type", String.class),
                        eventLabel(r.get("event_type", String.class)),
                        r.get("from_status", String.class),
                        r.get("to_status", String.class),
                        r.get("reason_note", String.class),
                        r.get("occurred_by", UUID.class),
                        r.get("occurred_at", OffsetDateTime.class)));

    List<RmaTrackingEvent> tracking = trackingEvents(id);
    List<RmaTrackingSummaryRow> parcels = trackingParcels(id, tracking);

    RmaLabel label = currentLabel(id);

    BigDecimal expectedQty = BigDecimal.ZERO;
    for (RmaInstructionLine line : lines) {
      expectedQty = expectedQty.add(line.expectedQty());
    }

    String timeliness = timeliness(status, head.get("return_by_at", OffsetDateTime.class), at);
    Long remaining = null;
    if (status.open() && head.get("return_by_at", OffsetDateTime.class) != null) {
      remaining = Duration.between(at, head.get("return_by_at", OffsetDateTime.class)).getSeconds();
    }

    return new RmaInstructionDetail(
        head(head),
        lines,
        events,
        tracking,
        parcels,
        label,
        expectedQty,
        timeliness,
        RmaInstructionTimeliness.valueOf(timeliness).label(),
        remaining,
        false,
        "11.2.2.1 退货入库",
        parcels.isEmpty() ? null : parcels.get(0).currentEventCode(),
        parcels.isEmpty() ? null : parcels.get(0).currentEventCodeLabel(),
        actionEntries(status, label != null, label != null && "DELIVERED".equals(label.labelStatus())),
        relatedModules(),
        at,
        "**已下发不等于已寄回**：本指令已经出了门（地址、要求、时限都已给到买家），"
            + "但货可能还在路上，也可能买家压根没寄。所以这里有寄回时限倒计时与时限到期自动失效，"
            + "却**没有**「已收到」状态 —— 登记退回实物属 11.2.2.1，未交付。"
            + "轨迹里的 DELIVERED 只表示**承运商说**这个包裹送到了，"
            + "实收判定（签收人是不是买家、代收点有没有代拆）归 11.2.2.1 按收货事件去重做。");
  }

  /**
   * 一条指令的回寄轨迹事件（追加式，按发生时刻读）。
   *
   * <p>⚠️ 次序用 {@code occurred_at, tracked_at, id} 而不是反过来：真实时间线按<b>发生</b>时刻排，
   * 而承运商重推会让<b>知道</b>的时刻乱序（一条 3 天前的轨迹今天才回传）。
   * 按 tracked_at 排会把整条时间线按「我们什么时候听说」重排一遍，看起来像是刚刚发生的。
   */
  private List<RmaTrackingEvent> trackingEvents(UUID instructionId) {
    return sql.fetch(
            "select id, carrier_code, tracking_no, event_code, event_location, event_description,"
                + " occurred_at, tracked_at, tracking_source, recorded_by, created_at"
                + " from rma_return_tracking_event"
                + " where tenant_id = ?::uuid and instruction_id = ?::uuid"
                + " order by occurred_at, tracked_at, id",
            currentTenant(), instructionId)
        .map(
            r -> {
              OffsetDateTime occurred = r.get("occurred_at", OffsetDateTime.class);
              OffsetDateTime tracked = r.get("tracked_at", OffsetDateTime.class);
              String code = r.get("event_code", String.class);
              RmaTrackingSource source =
                  RmaTrackingSource.valueOf(r.get("tracking_source", String.class));
              return new RmaTrackingEvent(
                  r.get("id", UUID.class),
                  r.get("carrier_code", String.class),
                  r.get("tracking_no", String.class),
                  code,
                  RmaTrackingEventCode.valueOf(code).label(),
                  nullable(r.get("event_location", String.class)),
                  nullable(r.get("event_description", String.class)),
                  occurred,
                  tracked,
                  // 信息延迟：承运商晚回传。混成一个字段就再也算不出延迟了。
                  tracked == null ? null : Duration.between(occurred, tracked).getSeconds(),
                  source.name(),
                  source.label(),
                  r.get("recorded_by", UUID.class),
                  r.get("created_at", OffsetDateTime.class));
            });
  }

  /**
   * 按运单号分组算「当前状态」：每个运单号 {@code occurred_at} 最大的那条事件即当前状态。
   *
   * <p>⚠️ <b>不单独存列</b>：存一个 current_status 列就得回答「它和事件表哪个为准」，
   * 而两者不一致时（「已签收」的事件旁边写着「运输中」的当前值）没人能判对。
   *
   * <p>分组顺序按「最近发生的时刻」倒序：客服打开页面要看的是**最新那个包裹**，
   * 而不是按运单号字典序排出来的第一个。
   */
  private List<RmaTrackingSummaryRow> trackingParcels(UUID instructionId, List<RmaTrackingEvent> events) {
    Map<String, List<RmaTrackingEvent>> byTracking = new LinkedHashMap<>();
    for (RmaTrackingEvent e : events) {
      byTracking.computeIfAbsent(e.carrierCode() + "|" + e.trackingNo(), k -> new ArrayList<>()).add(e);
    }
    List<RmaTrackingSummaryRow> rows = new ArrayList<>();
    for (List<RmaTrackingEvent> group : byTracking.values()) {
      // events 已按 occurred_at 升序 ⇒ 末尾那条就是当前状态。
      RmaTrackingEvent latest = group.get(group.size() - 1);
      rows.add(
          new RmaTrackingSummaryRow(
              latest.carrierCode(),
              latest.trackingNo(),
              latest.eventCode(),
              latest.eventCodeLabel(),
              latest.eventLocation(),
              latest.occurredAt(),
              group.size(),
              group.get(0).occurredAt()));
    }
    rows.sort((a, b) -> b.currentOccurredAt().compareTo(a.currentOccurredAt()));
    return rows;
  }

  /** 轨迹单独分页读出口。 */
  public RmaTrackingTimeline tracking(UUID instructionId) {
    requireRecord(instructionId);
    List<RmaTrackingEvent> events = trackingEvents(instructionId);
    List<RmaTrackingSummaryRow> parcels = trackingParcels(instructionId, events);
    RmaTrackingSummaryRow latest = parcels.isEmpty() ? null : parcels.get(0);
    return new RmaTrackingTimeline(
        instructionId,
        events,
        parcels,
        latest == null ? null : latest.currentEventCode(),
        latest == null ? null : latest.currentEventCodeLabel(),
        OffsetDateTime.now(ZoneOffset.UTC),
        "承运商与运单号挂在**事件**上而不是指令头上：买家可以分多个包裹寄回、也可以换承运商重寄"
            + "（真实且常见）。当前状态按运单号分组、由**最后一条事件**实时算出，不单独存列。"
            + "事件是追加式（运行角色 UPDATE/DELETE 已在 DB 层收回），靠 "
            + "(指令, 承运商, 运单号, 事件码, 发生时刻) 唯一索引给承运商重复推送去重。"
            + "⚠️ **DELIVERED 只表示承运商口径的送达**，不等于我们收到货了 —— "
            + "登记实收属 11.2.2.1（未交付），退回商品默认进待检。");
  }

  private RmaLabel currentLabel(UUID instructionId) {
    Record r =
        sql.fetchOne(
            "select * from rma_label"
                + " where tenant_id = ?::uuid and instruction_id = ?::uuid and label_status <> 'VOIDED'"
                + " order by generated_at desc limit 1",
            currentTenant(), instructionId);
    return r == null ? null : label(r);
  }

  // ================================================================ 写：建单

  /**
   * 登记退货指引草稿。
   *
   * <p><b>地址与时限从退货授权单复制快照</b>（{@code syncFromRma} 默认 true）：
   * 指引里的地址是<b>买家要看到的那一份</b>，让操作员手工重敲一遍只会敲错，
   * 而敲错的后果是货寄丢。复制的是**快照**——授权单之后改地址不影响已下发的指引。
   */
  public RmaInstructionDetail create(RmaInstructionCreate request, UUID actorId) {
    if (request.rmaId() == null) {
      throw new IllegalArgumentException("必须关联一张退货授权单（指引的上游）");
    }
    RmaDeliveryChannel channel =
        parseEnum(RmaDeliveryChannel.class, request.deliveryChannel(), "指引送达渠道");
    String recipientName = blankToEmpty(request.recipientName());
    String recipientEmail = blankToEmpty(request.recipientEmail());
    String recipientPhone = blankToEmpty(request.recipientPhone());
    if (channel == RmaDeliveryChannel.BUYER_EMAIL && recipientEmail.isEmpty()) {
      throw new IllegalArgumentException("选择买家邮箱渠道时必须给出收件邮箱：没有收件地址的邮件渠道发不出指引");
    }
    String packingRequirements = limited(request.packingRequirements(), 2000, "包装与寄回要求");
    String buyerNote = limited(request.buyerNote(), 2000, "附给买家的话");

    // 上游退货授权单：必须**还在授权状态**。
    Record rma =
        sql.fetchOne(
            "select a.*, c.id as case_id, c.case_no, c.order_id, o.order_no"
                + " from rma_authorization a"
                + " left join aftersales_case c on c.tenant_id = a.tenant_id and c.id = a.case_id"
                + " left join sales_order o on o.tenant_id = c.tenant_id and o.id = c.order_id"
                + " where a.tenant_id = ?::uuid and a.id = ?::uuid",
            currentTenant(), request.rmaId());
    if (rma == null) {
      throw new IllegalArgumentException("退货授权单不存在或不属于当前租户：" + request.rmaId());
    }
    String rmaStatus = rma.get("rma_status", String.class);
    if (!List.of("AUTHORIZED", "PARTIALLY_AUTHORIZED").contains(rmaStatus)) {
      throw new IllegalArgumentException(
          "只有已授权 / 部分授权的退货授权单才能下发退货指引，当前状态 " + rmaStatus
              + "。给一张已拒绝 / 已撤销 / 超时关闭的单发指引，等于通知买家「可以寄回来了」"
              + "而我们并不打算收");
    }
    OffsetDateTime returnByAt = rma.get("return_by_at", OffsetDateTime.class);
    if (returnByAt == null) {
      throw new IllegalArgumentException("退货授权单没有寄回时限，无法据此下发指引");
    }

    UUID id = UUID.randomUUID();
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    sql.execute(
        "insert into rma_instruction(id,tenant_id,instruction_no,rma_id,instruction_status,"
            + " delivery_channel,recipient_name,recipient_email,recipient_phone,"
            + " packing_requirements,buyer_note,"
            + " address_source,source_reference,return_recipient_name,return_recipient_phone,"
            + " return_contact_email,return_country_code,return_province,return_city,return_district,"
            + " return_postcode,return_address_line1,return_address_line2,"
            + " return_by_at,version,created_by,created_at,updated_at)"
            + " values(?,?,?,?,'DRAFT',?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::timestamptz,0,?::uuid,?::timestamptz,?::timestamptz)",
        id,
        currentTenant(),
        nextInstructionNo(),
        request.rmaId(),
        channel.name(),
        recipientName,
        recipientEmail,
        recipientPhone,
        packingRequirements,
        buyerNote,
        // ⚠️ 来源与地址**连着一起复制**：只复制地址不复制来源，
        //   这条指引就会假装地址是租户自己敲的，而它其实来自平台 RMA 或 3.6.3。
        rma.get("return_address_source", String.class),
        rma.get("source_reference", String.class),
        rma.get("return_recipient_name", String.class),
        rma.get("return_recipient_phone", String.class),
        rma.get("return_contact_email", String.class),
        rma.get("return_country_code", String.class),
        rma.get("return_province", String.class),
        rma.get("return_city", String.class),
        rma.get("return_district", String.class),
        rma.get("return_postcode", String.class),
        rma.get("return_address_line1", String.class),
        rma.get("return_address_line2", String.class),
        returnByAt,
        actorId,
        now,
        now);

    // 行：默认取该授权单**授权量 > 0** 的全部授权行（授权量为 0 的行不该出现在指引里 ——
    // 指到它等于请买家寄一件我们没同意退的东西）。
    List<Record> sourceLines =
        sql.fetch(
            "select rl.id, rl.authorized_qty from rma_authorization_line rl"
                + " join rma_authorization a on a.tenant_id = rl.tenant_id and a.id = rl.rma_id"
                + " where rl.tenant_id = ?::uuid and rl.rma_id = ?::uuid and rl.authorized_qty > 0"
                + " order by rl.id",
            currentTenant(), request.rmaId());
    if (sourceLines.isEmpty()) {
      throw new IllegalArgumentException("该退货授权单没有任何授权量大于 0 的授权行，无法下发退货指引");
    }
    Map<UUID, BigDecimal> source = new LinkedHashMap<>();
    for (Record r : sourceLines) {
      source.put(r.get("id", UUID.class), nz(r.get("authorized_qty", BigDecimal.class)));
    }

    Map<UUID, String> notes = new LinkedHashMap<>();
    Map<UUID, BigDecimal> overrides = new LinkedHashMap<>();
    if (request.lines() != null && !request.lines().isEmpty()) {
      for (RmaInstructionLineDraft draft : request.lines()) {
        if (draft.rmaLineId() == null) {
          throw new IllegalArgumentException("逐行明细必须给出 rmaLineId");
        }
        if (!source.containsKey(draft.rmaLineId())) {
          throw new IllegalArgumentException(
              "退货授权行不属于该退货授权单，或授权量为 0（授权量为 0 的行不能写进指引）："
                  + draft.rmaLineId());
        }
        notes.put(draft.rmaLineId(), limited(draft.lineNote(), 1000, "逐行说明"));
        if (draft.expectedQty() != null) {
          if (draft.expectedQty().signum() <= 0) {
            throw new IllegalArgumentException("应寄回数量必须大于 0");
          }
          if (draft.expectedQty().compareTo(source.get(draft.rmaLineId())) > 0) {
            throw new IllegalArgumentException(
                "应寄回数量 " + draft.expectedQty() + " 超过该行授权量 "
                    + source.get(draft.rmaLineId()) + "：指引是在请买家寄东西，"
                    + "不能请他多寄我们没同意退的");
          }
          overrides.put(draft.rmaLineId(), draft.expectedQty());
        }
      }
      // 点名了部分行、其余行**不写进指引**：这是调用方的明确选择，不是遗漏。
      source.keySet().retainAll(notes.keySet());
      source.keySet().retainAll(overrides.isEmpty() ? source.keySet() : overrides.keySet());
    }

    for (Map.Entry<UUID, BigDecimal> entry : source.entrySet()) {
      sql.execute(
          "insert into rma_instruction_line(id,tenant_id,instruction_id,rma_line_id,expected_qty,"
              + " line_note,created_at,updated_at)"
              + " values(?,?,?,?,?,?,?::timestamptz,?::timestamptz)",
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
   * 改草稿要素（收件方 / 渠道 / 包装要求 / 逐行说明）。
   *
   * <p><b>只允许 DRAFT 改</b>：指引下发后内容即冻结（DB 触发器
   * {@code guard_rma_instruction_frozen}）—— 买家可能已经照着旧地址寄了。
   */
  public RmaInstructionDetail update(UUID id, RmaInstructionUpdate request) {
    Record head = requireDraft(id);
    requireVersion(head, request.expectedVersion());

    RmaDeliveryChannel channel =
        parseEnum(RmaDeliveryChannel.class, request.deliveryChannel(), "指引送达渠道");
    String recipientName = blankToEmpty(request.recipientName());
    String recipientEmail = blankToEmpty(request.recipientEmail());
    String recipientPhone = blankToEmpty(request.recipientPhone());
    if (channel == RmaDeliveryChannel.BUYER_EMAIL && recipientEmail.isEmpty()) {
      throw new IllegalArgumentException("选择买家邮箱渠道时必须给出收件邮箱：没有收件地址的邮件渠道发不出指引");
    }

    int updated =
        sql.execute(
            "update rma_instruction set delivery_channel = ?, recipient_name = ?,"
                + " recipient_email = ?, recipient_phone = ?, packing_requirements = ?,"
                + " buyer_note = ?, version = version + 1, updated_at = ?::timestamptz"
                + " where tenant_id = ?::uuid and id = ?::uuid and instruction_status = 'DRAFT'",
            channel.name(),
            recipientName,
            recipientEmail,
            recipientPhone,
            limited(request.packingRequirements(), 2000, "包装与寄回要求"),
            limited(request.buyerNote(), 2000, "附给买家的话"),
            OffsetDateTime.now(ZoneOffset.UTC),
            currentTenant(),
            id);
    if (updated == 0) {
      throw new IllegalArgumentException("退货指令已不是草稿，不能修改：" + id);
    }

    if (request.lines() != null) {
      for (RmaInstructionLineDraft draft : request.lines()) {
        if (draft.rmaLineId() == null) {
          throw new IllegalArgumentException("逐行明细必须给出 rmaLineId");
        }
        sql.execute(
            "update rma_instruction_line set line_note = ?"
                + " where tenant_id = ?::uuid and instruction_id = ?::uuid and rma_line_id = ?::uuid",
            limited(draft.lineNote(), 1000, "逐行说明"), currentTenant(), id, draft.rmaLineId());
      }
    }

    return detail(id);
  }

  // ================================================================ 写：下发 / 确认 / 撤销

  /**
   * 下发指引（内容在这一刻冻结）。
   *
   * <p>下发前<b>再查一次</b>上游授权单还在授权状态：建单到下发之间可能过了几天，
   * 中间授权单被撤销了 —— 这时候把指引发出去等于通知买家寄一件我们不收的东西。
   */
  public RmaInstructionDetail issue(UUID id, RmaInstructionIssue request, UUID actorId) {
    Record head = requireDraft(id);
    requireVersion(head, request.expectedVersion());

    String rmaStatus =
        sql.fetchOne(
                "select a.rma_status from rma_authorization a"
                    + " where a.tenant_id = ?::uuid and a.id = ?::uuid",
                currentTenant(), head.get("rma_id", UUID.class))
            .get(0, String.class);
    if (!List.of("AUTHORIZED", "PARTIALLY_AUTHORIZED").contains(rmaStatus)) {
      throw new IllegalArgumentException(
          "退货授权单已不在授权状态（当前 " + rmaStatus + "），不能再下发退货指引");
    }

    long lines =
        sql.fetchOne(
                "select count(*) from rma_instruction_line"
                    + " where tenant_id = ?::uuid and instruction_id = ?::uuid",
                currentTenant(), id)
            .get(0, Long.class);
    if (lines == 0) {
      throw new IllegalArgumentException(
          "退货指令没有任何明细行：买家拿到一份「请把货寄回来」却不知道寄什么，这不是指引");
    }

    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    String note = limited(request.issueNote(), 2000, "下发备注");
    transition(id, RmaInstructionStatus.ISSUED, note, actorId, now);
    return detail(id);
  }

  /** 登记买家已确认收到指引。⚠️ 这是买家的<b>主动回执</b>，不是我们推测他看到了。 */
  public RmaInstructionDetail acknowledge(UUID id, RmaInstructionAcknowledge request, UUID actorId) {
    Record head = requireRecord(id);
    requireVersion(head, request.expectedVersion());
    RmaInstructionStatus status =
        RmaInstructionStatus.valueOf(head.get("instruction_status", String.class));
    if (!status.deliveredToBuyer()) {
      throw new IllegalArgumentException(
          "只有已下发 / 买家已确认的指令才能登记买家确认，当前状态：" + status.label()
              + "（未下发的指令不存在买家回执）");
    }
    if (status == RmaInstructionStatus.ACKNOWLEDGED) {
      throw new IllegalArgumentException("买家已经确认过这张指引了：" + id);
    }

    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    // ⚠️ acknowledge_note **不进表**：指令正文在下发后已冻结，而这条备注是回执附言。
    //   记进留痕的 reason_note（由触发器带下来）而不是新增一列，
    //   新增一列就要同步改 DB CHECK、契约与前端，而它只是留痕的一段文字。
    transition(
        id,
        RmaInstructionStatus.ACKNOWLEDGED,
        limited(request.acknowledgeNote(), 2000, "买家确认附言"),
        actorId,
        now);
    return detail(id);
  }

  /** 人工撤销指引（授权被撤销 / 买家改主意了）。可随时撤销在途三态。 */
  public RmaInstructionDetail cancel(UUID id, RmaInstructionCancel request, UUID actorId) {
    Record head = requireRecord(id);
    requireVersion(head, request.expectedVersion());
    RmaInstructionStatus status =
        RmaInstructionStatus.valueOf(head.get("instruction_status", String.class));
    if (status.terminal()) {
      throw new IllegalArgumentException("退货指令已结案（" + status.label() + "），不能撤销");
    }
    String reason = requireText(request.reason(), "撤销理由");
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    transition(id, RmaInstructionStatus.CANCELLED, reason, actorId, now);
    return detail(id);
  }

  /**
   * 时限到期自动失效。
   *
   * <p>判据只有一条：{@code return_by_at < 现在} 且状态仍在途。
   *
   * <p><b>判据里刻意不含「有没有轨迹」</b>：时限到了而货在路上的单子，在承运商口径里
   * 仍可能签收成功。把它按「货没回来」关掉会让下游拒收一件真收到的货 ——
   * 实收判定（签收人是不是买家、代收点有没有代拆）归 11.2.2.1 按收货事件去重做。
   * 逐条结果里带 {@code trackingStarted}，让运营知道这条是被时限带走的、且期间有物流活动。
   */
  public RmaInstructionExpireResult expireOverdue() {
    OffsetDateTime at = OffsetDateTime.now(ZoneOffset.UTC);
    List<Record> due =
        sql.fetch(
            "select id, instruction_no, instruction_status, return_by_at from rma_instruction"
                + " where tenant_id = ?::uuid"
                + "   and instruction_status in ('DRAFT','ISSUED','ACKNOWLEDGED')"
                + "   and return_by_at < ?::timestamptz"
                + " order by return_by_at limit ?",
            currentTenant(), at, EXPIRE_SCAN_LIMIT);

    List<RmaInstructionExpireItem> items = new ArrayList<>();
    int expired = 0;
    for (Record r : due) {
      UUID id = r.get("id", UUID.class);
      String no = r.get("instruction_no", String.class);
      String before = r.get("instruction_status", String.class);
      boolean tracking =
          sql.fetchOne(
                      "select count(*) from rma_return_tracking_event"
                          + " where tenant_id = ?::uuid and instruction_id = ?::uuid",
                      currentTenant(), id)
                  .get(0, Long.class)
              > 0;

      // 版本守卫在 UPDATE 的 WHERE 里：两个人同时点「扫描」时只有一个真正改到状态。
      int updated =
          sql.execute(
              "update rma_instruction set instruction_status = 'EXPIRED', expired_at = ?::timestamptz,"
                  + " version = version + 1, updated_at = ?::timestamptz"
                  + " where tenant_id = ?::uuid and id = ?::uuid"
                  + "   and instruction_status in ('DRAFT','ISSUED','ACKNOWLEDGED')"
                  + "   and return_by_at < ?::timestamptz",
              at, at, currentTenant(), id, at);
      if (updated == 0) {
        items.add(
            new RmaInstructionExpireItem(
                id, no, before, r.get("return_by_at", OffsetDateTime.class), null, tracking,
                "ALREADY_CLOSED", "扫描期间这条指令已被其他动作关掉，本次未改动"));
        continue;
      }
      expired++;
      items.add(
          new RmaInstructionExpireItem(
              id, no, before, r.get("return_by_at", OffsetDateTime.class), at, tracking, "EXPIRED",
              tracking
                  ? "寄回时限已到，指引自动失效。⚠️ 期间**有物流活动** —— 时限到期只判「时限过了」，"
                      + "不判「货没回来」：货可能在路上甚至已签收，实收判定归 11.2.2.1"
                  : "寄回时限已到，指引自动失效（期间没有任何回寄物流活动）"));
    }

    return new RmaInstructionExpireResult(
        due.size(),
        expired,
        due.size() - expired,
        items,
        at,
        "判据只有一条：寄回时限已过且状态仍在途。单次扫描上限 " + EXPIRE_SCAN_LIMIT
            + " 条 —— 不是「怕慢」而是**怕静默漏扫**（没人愿意手工翻页把剩下的补上）。"
            + "这是显式端点而不是后台定时器：定时器要另起 worker 进程与调度配置，那是另一个模块的事；"
            + "语义与判据在这一处定死，调度侧以后定时调它即可。");
  }

  /**
   * 状态流转的唯一入口。
   *
   * <p>⚠️ <b>每个目标状态一条 SQL</b>：拒绝与撤销本来要落的列就不同，
   * 用一个 {@code decided_by/cancelled_by} 二选一的条件表达式把两种语义糊在一起，
   * 读的人得在脑子里跑一遍才知道落了哪几列。
   */
  private void transition(UUID id, RmaInstructionStatus target, String reason, UUID actorId,
      OffsetDateTime at) {
    String statement =
        switch (target) {
          case ISSUED ->
              "update rma_instruction set instruction_status = 'ISSUED', issued_by = ?::uuid,"
                  + " issued_at = ?::timestamptz,"
                  + " version = version + 1, updated_at = ?::timestamptz"
                  + " where tenant_id = ?::uuid and id = ?::uuid";
          case ACKNOWLEDGED ->
              "update rma_instruction set instruction_status = 'ACKNOWLEDGED',"
                  + " acknowledged_by = ?::uuid, acknowledged_at = ?::timestamptz,"
                  + " version = version + 1, updated_at = ?::timestamptz"
                  + " where tenant_id = ?::uuid and id = ?::uuid";
          case CANCELLED ->
              "update rma_instruction set instruction_status = 'CANCELLED', cancelled_by = ?::uuid,"
                  + " cancelled_at = ?::timestamptz, cancel_reason = ?,"
                  + " version = version + 1, updated_at = ?::timestamptz"
                  + " where tenant_id = ?::uuid and id = ?::uuid";
          case EXPIRED ->
              "update rma_instruction set instruction_status = 'EXPIRED', expired_at = ?::timestamptz,"
                  + " version = version + 1, updated_at = ?::timestamptz"
                  + " where tenant_id = ?::uuid and id = ?::uuid";
          case DRAFT ->
              throw new IllegalArgumentException("退货指令不能回到草稿：下发是一次性动作，撤回请用撤销");
        };

    List<Object> binds = new ArrayList<>();
    switch (target) {
      case ISSUED, ACKNOWLEDGED -> {
        binds.add(actorId);
        binds.add(at);
        binds.add(at);
      }
      case CANCELLED -> {
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
      throw new IllegalArgumentException("退货指令不存在或不属于当前租户：" + id);
    }
  }

  // ================================================================ 写：退货标签

  /**
   * 生成退货标签。
   *
   * <p>两种真能产出的来源：
   * <ul>
   *   <li>{@code SELF_GENERATED_TEMPLATE} —— 本模块按授权单<b>真实要素</b>渲染的可打印标签文本。
   *       刻意<b>不含运单号</b>：带了就等于声称已经拿到承运商运单，而那件事属 10 物流（未交付）。</li>
   *   <li>{@code TENANT_PROVIDED_FILE} —— 租户已从外部渠道拿到标签文件，登记其内容；
   *       承运商与运单号<b>必填</b>（DB CHECK 兜底），否则买家手上那张贴纸无从核对。</li>
   * </ul>
   * 两种都是<b>真实字节落库</b>，校验和是内容字节的 SHA-256，读取时重算比对。
   */
  public RmaInstructionDetail generateLabel(UUID id, RmaLabelGenerate request, UUID actorId) {
    // ⚠️ 取头时**带上 rma_no**：标签模板里要印退货授权单号，而 requireRecord 只取本表自己的列。
    Record head = requireRecordWithRma(id);
    RmaInstructionStatus status =
        RmaInstructionStatus.valueOf(head.get("instruction_status", String.class));
    if (!status.deliveredToBuyer()) {
      throw new IllegalArgumentException(
          "只有已下发的指令才需要出标签，当前状态：" + status.label()
              + "（买家没收到指引，标签寄给谁？）");
    }
    if (currentLabel(id) != null) {
      throw new IllegalArgumentException(
          "这张指令已经有一张未作废的标签了。要换请先作废再重新生成");
    }

    RmaLabelSource source =
        parseEnum(RmaLabelSource.class, request.labelSource(), "标签来源");
    if (source == RmaLabelSource.MODULE_10_CARRIER_LABEL_API) {
      throw new IllegalArgumentException(
          "MODULE_10_CARRIER_LABEL_API 由 10 物流面单接口生成，**未交付**，"
              + "本服务不代打面单。可用来源：SELF_GENERATED_TEMPLATE（本模块按真值渲染）"
              + "或 TENANT_PROVIDED_FILE（登记你已从外部渠道拿到的标签）");
    }
    RmaLabelFormat format = parseEnum(RmaLabelFormat.class, request.labelFormat(), "标签格式");

    String carrierCode = blankToEmpty(request.carrierCode());
    String trackingNo = blankToEmpty(request.trackingNo());
    if (source == RmaLabelSource.TENANT_PROVIDED_FILE) {
      if (carrierCode.isEmpty() || trackingNo.isEmpty()) {
        throw new IllegalArgumentException(
            "登记外部渠道的标签时必须给出承运商与运单号：两者皆空的话，"
                + "买家手上那张贴纸无从核对，实收时（11.2.2.1）也定位不到包裹");
      }
      if (!isSet(request.content())) {
        throw new IllegalArgumentException("登记外部标签时必须给出标签文件内容（labelContent）");
      }
    } else if (!trackingNo.isEmpty()) {
      throw new IllegalArgumentException(
          "本模块生成的模板标签**不带运单号** —— 承运商分配的运单号请用「登记回寄轨迹」写进轨迹事件，"
              + "那才是运单号的真值来源。在标签上写一个运单号会让它被当成已对接承运商");
    }

    String serviceLevel =
        isSet(request.serviceLevel())
            ? parseEnum(RmaServiceLevel.class, request.serviceLevel(), "快递档位").name()
            : "";

    // 模板标签的内容来自**库里真值**（指令头 + 逐行 SKU + 退货地址），不是调用方传的字符串。
    String content =
        source == RmaLabelSource.TENANT_PROVIDED_FILE
            ? request.content().strip()
            : renderTemplate(head, format);

    BigDecimal weight = requireNonNegative(request.weightKg(), "包裹重量");
    BigDecimal length = requireNonNegative(request.lengthCm(), "包裹长");
    BigDecimal width = requireNonNegative(request.widthCm(), "包裹宽");
    BigDecimal height = requireNonNegative(request.heightCm(), "包裹高");
    boolean anyDim = length != null || width != null || height != null;
    boolean allDim = length != null && width != null && height != null;
    if (anyDim && !allDim) {
      throw new IllegalArgumentException(
          "包裹长 / 宽 / 高必须三个一起给：只给长宽不给高，承运商算出来的运费是错的");
    }

    UUID labelId = UUID.randomUUID();
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    sql.execute(
        "insert into rma_label(id,tenant_id,instruction_id,label_no,label_status,label_source,"
            + " carrier_code,service_level,label_format,label_content,label_checksum,tracking_no,"
            + " weight_kg,length_cm,width_cm,height_cm,generated_at,generated_by,created_at,updated_at)"
            + " values(?,?,?,?,'GENERATED',?,?,?,?,?,?,?,?,?,?,?,?::timestamptz,?::uuid,"
            + " ?::timestamptz,?::timestamptz)",
        labelId,
        currentTenant(),
        id,
        nextLabelNo(),
        source.name(),
        carrierCode,
        serviceLevel,
        format.name(),
        content,
        sha256Hex(content),
        trackingNo,
        weight,
        length,
        width,
        height,
        now,
        actorId,
        now,
        now);

    return detail(id);
  }

  /**
   * 按指令真值渲染标签文件内容。
   *
   * <p>⚠️ **内容全部来自库里真值**（指令号 / 退货授权单号 / 逐行 SKU 与应寄数量 / 退货地址 /
   * 寄回时限），不是调用方传的字符串 —— 一张内容由前端拼出来的标签，
   * 地址少一段城市照样能生成，而货会寄丢。
   */
  private String renderTemplate(Record head, RmaLabelFormat format) {
    UUID instructionId = head.get("id", UUID.class);
    List<Record> lines =
        sql.fetch(
            "select ol.sku_code, ol.sku_name, l.expected_qty, l.line_note"
                + " from rma_instruction_line l"
                + " join rma_authorization_line rl"
                + "   on rl.tenant_id = l.tenant_id and rl.id = l.rma_line_id"
                + " join aftersales_case_line cl"
                + "   on cl.tenant_id = rl.tenant_id and cl.id = rl.case_line_id"
                + " join sales_order_line ol"
                + "   on ol.tenant_id = cl.tenant_id and ol.id = cl.order_line_id"
                + " where l.tenant_id = ?::uuid and l.instruction_id = ?::uuid"
                + " order by ol.line_no, l.id",
            currentTenant(), instructionId);

    String nl = format == RmaLabelFormat.HTML_TEMPLATE ? "<br>" : "\n";
    StringBuilder sb = new StringBuilder();
    sb.append("RETURN INSTRUCTION / 退货指引").append(nl);
    sb.append("Instruction No: ").append(head.get("instruction_no", String.class)).append(nl);
    sb.append("RMA No: ").append(head.get("rma_no", String.class)).append(nl);
    sb.append("Return By: ").append(head.get("return_by_at", OffsetDateTime.class)).append(nl);
    sb.append("--- Items / 应寄商品 ---").append(nl);
    for (Record l : lines) {
      sb.append(l.get("sku_code", String.class))
          .append(" x ")
          .append(stripTrailingZeros(l.get("expected_qty", BigDecimal.class)))
          .append("  ")
          .append(appendIfSet(l.get("sku_name", String.class)))
          .append(appendIfSet(l.get("line_note", String.class)))
          .append(nl);
    }
    sb.append("--- Return Address / 退货地址 ---").append(nl);
    sb.append(head.get("return_recipient_name", String.class)).append(nl);
    sb.append(head.get("return_address_line1", String.class)).append(nl);
    if (isSet(head.get("return_address_line2", String.class))) {
      sb.append(head.get("return_address_line2", String.class)).append(nl);
    }
    sb.append(joinNonEmpty(
            " ",
            blankToEmpty(head.get("return_district", String.class)),
            blankToEmpty(head.get("return_city", String.class)),
            blankToEmpty(head.get("return_province", String.class)),
            blankToEmpty(head.get("return_country_code", String.class))))
        .append(nl);
    if (isSet(head.get("return_postcode", String.class))) {
      sb.append(head.get("return_postcode", String.class)).append(nl);
    }
    if (isSet(head.get("return_recipient_phone", String.class))) {
      sb.append("Tel: ").append(head.get("return_recipient_phone", String.class)).append(nl);
    }
    String requirements = head.get("packing_requirements", String.class);
    if (isSet(requirements)) {
      sb.append("--- Packing Requirements / 包装要求 ---").append(nl).append(requirements).append(nl);
    }
    String note = head.get("buyer_note", String.class);
    if (isSet(note)) {
      sb.append("--- Note / 备注 ---").append(nl).append(note).append(nl);
    }
    return sb.toString();
  }

  /** 作废退货标签（承运商取消 / 地址改了重出 / 指令撤销）。作废后可重新生成一张。 */
  public RmaInstructionDetail voidLabel(UUID id, RmaLabelVoid request, UUID actorId) {
    requireRecord(id);
    RmaLabel label = currentLabel(id);
    if (label == null) {
      throw new IllegalArgumentException("这张指令当前没有未作废的标签可以作废：" + id);
    }
    String reason = requireText(request.reason(), "作废理由");
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    int updated =
        sql.execute(
            "update rma_label set label_status = 'VOIDED', voided_by = ?::uuid,"
                + " voided_at = ?::timestamptz, void_reason = ?, updated_at = ?::timestamptz"
                + " where tenant_id = ?::uuid and id = ?::uuid and label_status <> 'VOIDED'",
            actorId, now, reason, now, currentTenant(), label.id());
    if (updated == 0) {
      throw new IllegalArgumentException("标签已被作废（并发），请刷新后重试：" + label.id());
    }
    return detail(id);
  }

  /**
   * 交付退货标签（贴纸真的到买家手上了）。
   *
   * <p>⚠️ <b>「生成」不等于「交付」</b>：生成只是我们手上有一张能打印的贴纸，
   * 买家手上没有那张纸，退货照样收不到。交付渠道与指引的送达渠道<b>不是同一件事</b> ——
   * 指引可以走平台站内消息，而标签必须以附件 / 图片形式真的到买家手上。
   *
   * <p>已交付的标签**不许再交付**（重复交付会让人以为贴了两张，而买家只贴了一张）。
   */
  public RmaInstructionDetail deliverLabel(UUID id, RmaLabelDeliver request, UUID actorId) {
    requireRecord(id);
    RmaLabel label = currentLabel(id);
    if (label == null) {
      throw new IllegalArgumentException("这张指令当前没有未作废的标签可以交付：" + id);
    }
    if ("DELIVERED".equals(label.labelStatus())) {
      throw new IllegalArgumentException("这张标签已经交付过了：" + label.labelNo()
          + "（重复交付会让人以为贴了两张，而买家只贴了一张）");
    }
    RmaDeliveryChannel via =
        parseEnum(RmaDeliveryChannel.class, request.deliveredVia(), "标签交付渠道");
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    int updated =
        sql.execute(
            "update rma_label set label_status = 'DELIVERED', delivered_by = ?::uuid,"
                + " delivered_at = ?::timestamptz, delivered_via = ?, updated_at = ?::timestamptz"
                + " where tenant_id = ?::uuid and id = ?::uuid and label_status = 'GENERATED'",
            actorId, now, via.name(), now, currentTenant(), label.id());
    if (updated == 0) {
      throw new IllegalArgumentException("标签状态已变化（并发），请刷新后重试：" + label.id());
    }
    return detail(id);
  }

  /**
   * 取退货标签文件内容，并在服务端<b>重算 SHA-256 与库里比对</b>。
   *
   * <p>⚠️ 校验和不符时<b>照常返回内容但 {@code checksumVerified=false}</b>，
   * 而不是直接报错：页面需要把这件事显示出来（标签内容被人动过，货可能寄到别人家），
   * 一个 500 只会让运营以为系统坏了。
   */
  public RmaLabelContent labelContent(UUID instructionId) {
    // ⚠️ **先 requireRecord**：只查标签的话，跨租户会得到「当前没有未作废的标签」，
    //   而「不属于当前租户」与「不存在」必须给**同一条**消息（这个端点是按 id 直达的 URL）。
    requireRecord(instructionId);
    RmaLabel label = currentLabel(instructionId);
    if (label == null) {
      throw new IllegalArgumentException("这张指令当前没有未作废的标签：" + instructionId);
    }
    String content =
        sql.fetchOne(
                "select label_content from rma_label where tenant_id = ?::uuid and id = ?::uuid",
                currentTenant(), label.id())
            .get(0, String.class);
    String computed = sha256Hex(content);
    boolean verified = computed.equals(label.labelChecksum());
    return new RmaLabelContent(
        label.id(),
        label.labelNo(),
        label.labelFormat(),
        label.labelSource(),
        content,
        "text/plain; charset=utf-8",
        label.labelChecksum(),
        computed,
        verified,
        verified
            ? "完整性校验通过（服务端已重算 SHA-256 并与库里比对）"
            : "⚠️ **完整性校验失败**：库里存的内容与它的校验和对不上 —— "
                + "标签文件在入库之后被改过。页面据此显示「完整性校验失败」而不是照常渲染，"
                + "因为买家照着这份地址寄，货寄到谁家由篡改者决定。");
  }

  // ================================================================ 写：回寄轨迹

  /**
   * 登记一条回寄物流轨迹事件（<b>追加式</b>）。
   *
   * <p>承运商重复推送同一条扫描由 DB 唯一索引
   * {@code (tenant_id, instruction_id, carrier_code, tracking_no, event_code, occurred_at)} 去重 ——
   * 重复登记会让「这单退了几件」出现两个答案。
   */
  public RmaInstructionDetail appendTracking(UUID id, RmaTrackingAppend request, UUID actorId) {
    Record head = requireRecord(id);
    RmaInstructionStatus status =
        RmaInstructionStatus.valueOf(head.get("instruction_status", String.class));
    if (!status.deliveredToBuyer()) {
      throw new IllegalArgumentException(
          "只有已下发的指令才可能收到包裹，当前状态：" + status.label()
              + "（未下发时买家手上没有指引，不会有包裹寄回来）");
    }

    String carrierCode = requireText(request.carrierCode(), "承运商");
    String trackingNo = requireText(request.trackingNo(), "运单号");
    RmaTrackingEventCode code =
        parseEnum(RmaTrackingEventCode.class, request.eventCode(), "轨迹事件码");
    RmaTrackingSource source =
        parseEnum(RmaTrackingSource.class, request.trackingSource(), "轨迹来源");
    if (!source.tenantWritable()) {
      throw new IllegalArgumentException(
          "轨迹来源 " + source.name() + " 是系统接入通道（"
              + (source == RmaTrackingSource.CARRIER_WEBHOOK
                  ? "承运商回调**未接入**"
                  : "10 物流轮询**未交付**")
              + "），本服务的人工入口只接受 TENANT_MANUAL_ENTRY");
    }
    if (request.occurredAt() == null) {
      throw new IllegalArgumentException("必须给出轨迹事件的发生时刻（occurredAt）");
    }
    OffsetDateTime trackedAt =
        request.trackedAt() == null ? OffsetDateTime.now(ZoneOffset.UTC) : request.trackedAt();

    int inserted =
        sql.execute(
            "insert into rma_return_tracking_event(id,tenant_id,instruction_id,carrier_code,"
                + " tracking_no,event_code,event_location,event_description,occurred_at,tracked_at,"
                + " tracking_source,recorded_by,created_at)"
                + " values(?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz,?,?::uuid,"
                + " ?::timestamptz)"
                + " on conflict do nothing",
            UUID.randomUUID(),
            currentTenant(),
            id,
            carrierCode,
            trackingNo,
            code.name(),
            limited(request.eventLocation(), 200, "轨迹地点"),
            limited(request.eventDescription(), 1000, "轨迹描述"),
            request.occurredAt(),
            trackedAt,
            source.name(),
            actorId,
            OffsetDateTime.now(ZoneOffset.UTC));
    if (inserted == 0) {
      throw new IllegalArgumentException(
          "这条轨迹已经登记过了（同一指令 + 承运商 + 运单号 + 事件码 + 发生时刻）："
              + carrierCode + " / " + trackingNo + " / " + code.name() + " @ "
              + request.occurredAt() + "。承运商重复推送是真实场景，重复登记会让"
              + "「这单退了几件」出现两个答案");
    }
    return detail(id);
  }

  // ================================================================ 映射

  private RmaInstructionRow row(Record r, OffsetDateTime at) {
    RmaInstructionStatus status =
        RmaInstructionStatus.valueOf(r.get("instruction_status", String.class));
    RmaDeliveryChannel channel =
        RmaDeliveryChannel.valueOf(r.get("delivery_channel", String.class));
    String timeliness = r.get("timeliness_state", String.class);
    String latestEvent = r.get("latest_event_code", String.class);
    String labelStatus = r.get("label_status", String.class);
    return new RmaInstructionRow(
        r.get("id", UUID.class),
        r.get("instruction_no", String.class),
        r.get("rma_id", UUID.class),
        r.get("rma_no", String.class),
        r.get("case_id", UUID.class),
        r.get("case_no", String.class),
        r.get("order_id", UUID.class),
        r.get("order_no", String.class),
        status.name(),
        status.label(),
        channel.name(),
        channel.label(),
        nullable(r.get("recipient_name", String.class)),
        nullable(r.get("recipient_email", String.class)),
        nullable(r.get("return_recipient_name", String.class)),
        nullable(r.get("return_country_code", String.class)),
        r.get("return_by_at", OffsetDateTime.class),
        timeliness,
        RmaInstructionTimeliness.valueOf(timeliness).label(),
        r.get("remaining_seconds", Long.class),
        r.get("line_count", Integer.class),
        nz(r.get("expected_qty", BigDecimal.class)),
        nullable(labelStatus),
        nullable(r.get("label_no", String.class)),
        nullable(r.get("carrier_code", String.class)),
        nullable(r.get("tracking_no", String.class)),
        r.get("event_count", Integer.class),
        latestEvent,
        latestEvent == null ? null : RmaTrackingEventCode.valueOf(latestEvent).label(),
        false,
        "11.2.2.1 退货入库",
        issueAction(status),
        r.get("created_at", OffsetDateTime.class));
  }

  /** 列表每行只带**一个**动作入口（下发），不为每行重建整份动作表。 */
  private RmaInstructionAction issueAction(RmaInstructionStatus status) {
    boolean draft = status == RmaInstructionStatus.DRAFT;
    String blocking = null;
    if (status.terminal()) {
      blocking = "退货指令已结案（" + status.label() + "）";
    } else if (!draft) {
      blocking = "只有草稿可下发：当前状态 " + status.label();
    }
    return new RmaInstructionAction("ISSUE", "下发退货指引", draft, "POST",
        "/trade/rma-instructions/{id}/issue", true, null, blocking, null, null);
  }

  private RmaInstructionHead head(Record r) {
    RmaInstructionStatus status =
        RmaInstructionStatus.valueOf(r.get("instruction_status", String.class));
    RmaDeliveryChannel channel =
        RmaDeliveryChannel.valueOf(r.get("delivery_channel", String.class));
    RmaInstructionAddressSource source =
        RmaInstructionAddressSource.valueOf(r.get("address_source", String.class));
    // frozen 由**状态**派生，不单独存一列：内容在下发的同一刻冻结（DB 触发器
    // guard_rma_instruction_frozen），所以「是不是草稿」就是「内容能不能改」的答案。
    boolean frozen = status != RmaInstructionStatus.DRAFT;
    return new RmaInstructionHead(
        r.get("id", UUID.class),
        r.get("instruction_no", String.class),
        r.get("rma_id", UUID.class),
        r.get("rma_no", String.class),
        nullable(r.get("rma_status", String.class)),
        r.get("case_id", UUID.class),
        r.get("case_no", String.class),
        r.get("order_id", UUID.class),
        r.get("order_no", String.class),
        status.name(),
        status.label(),
        channel.name(),
        channel.label(),
        nullable(r.get("recipient_name", String.class)),
        nullable(r.get("recipient_email", String.class)),
        nullable(r.get("recipient_phone", String.class)),
        blankToEmpty(r.get("packing_requirements", String.class)),
        nullable(r.get("buyer_note", String.class)),
        source.name(),
        source.label(),
        nullable(r.get("source_reference", String.class)),
        blankToEmpty(r.get("return_recipient_name", String.class)),
        nullable(r.get("return_recipient_phone", String.class)),
        nullable(r.get("return_contact_email", String.class)),
        nullable(r.get("return_country_code", String.class)),
        nullable(r.get("return_province", String.class)),
        nullable(r.get("return_city", String.class)),
        nullable(r.get("return_district", String.class)),
        nullable(r.get("return_postcode", String.class)),
        blankToEmpty(r.get("return_address_line1", String.class)),
        nullable(r.get("return_address_line2", String.class)),
        r.get("return_by_at", OffsetDateTime.class),
        r.get("issued_at", OffsetDateTime.class),
        r.get("issued_by", UUID.class),
        r.get("acknowledged_at", OffsetDateTime.class),
        r.get("acknowledged_by", UUID.class),
        r.get("expired_at", OffsetDateTime.class),
        r.get("cancelled_at", OffsetDateTime.class),
        r.get("cancelled_by", UUID.class),
        nullable(r.get("cancel_reason", String.class)),
        frozen,
        frozen ? r.get("issued_at", OffsetDateTime.class) : null,
        r.get("created_by", UUID.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class),
        r.get("version", Long.class));
  }

  private RmaLabel label(Record r) {
    String statusRaw = r.get("label_status", String.class);
    String sourceRaw = r.get("label_source", String.class);
    String serviceRaw = blankToEmpty(r.get("service_level", String.class));
    String content = r.get("label_content", String.class);
    return new RmaLabel(
        r.get("id", UUID.class),
        r.get("label_no", String.class),
        r.get("instruction_id", UUID.class),
        statusRaw,
        RmaLabelStatus.valueOf(statusRaw).label(),
        sourceRaw,
        RmaLabelSource.valueOf(sourceRaw).label(),
        nullable(r.get("carrier_code", String.class)),
        nullable(serviceRaw.isEmpty() ? null : serviceRaw),
        serviceRaw.isEmpty() ? null : RmaServiceLevel.valueOf(serviceRaw).label(),
        r.get("label_format", String.class),
        nullable(r.get("tracking_no", String.class)),
        r.get("weight_kg", BigDecimal.class),
        r.get("length_cm", BigDecimal.class),
        r.get("width_cm", BigDecimal.class),
        r.get("height_cm", BigDecimal.class),
        r.get("label_checksum", String.class),
        content == null ? 0 : content.length(),
        r.get("generated_at", OffsetDateTime.class),
        r.get("generated_by", UUID.class),
        r.get("delivered_at", OffsetDateTime.class),
        r.get("delivered_by", UUID.class),
        nullable(r.get("delivered_via", String.class)),
        r.get("voided_at", OffsetDateTime.class),
        r.get("voided_by", UUID.class),
        nullable(r.get("void_reason", String.class)));
  }

  private static String eventLabel(String raw) {
    if (raw == null) {
      return "";
    }
    return switch (raw) {
      case "CREATED" -> "登记草稿";
      case "ISSUED" -> "下发退货指引";
      case "ACKNOWLEDGED" -> "买家确认收到";
      case "EXPIRED" -> "寄回时限已到，自动失效";
      case "CANCELLED" -> "人工撤销";
      default -> "草稿要素变更";
    };
  }

  // ================================================================ 工具

  private String nextInstructionNo() {
    long n = sql.fetchOne("select nextval('rma_instruction_no_seq')").get(0, Long.class);
    return "RMI" + n;
  }

  private String nextLabelNo() {
    long n = sql.fetchOne("select nextval('rma_label_no_seq')").get(0, Long.class);
    return "RML" + n;
  }

  private UUID currentTenant() {
    return UUID.fromString(
        sql.fetchOne("select current_setting('app.current_tenant')").get(0, String.class));
  }

  /** SHA-256 十六进制小写。标签文件的完整性校验和就是它。 */
  private static String sha256Hex(String content) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of()
          .formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      // SHA-256 是 JDK 必备算法，走到这里说明 JRE 本身有问题，不是业务错误。
      throw new IllegalStateException("运行环境缺少 SHA-256，无法计算标签文件校验和", e);
    }
  }

  private Record requireRecord(UUID id) {
    Record head =
        sql.fetchOne(
            "select * from rma_instruction where tenant_id = ?::uuid and id = ?::uuid",
            currentTenant(), id);
    if (head == null) {
      throw new IllegalArgumentException("退货指令不存在或不属于当前租户：" + id);
    }
    return head;
  }

  /** 取指令头并左连退货授权单（标签模板要印退货授权单号，{@link #requireRecord} 不带那几列）。 */
  private Record requireRecordWithRma(UUID id) {
    Record head =
        sql.fetchOne(
            "select r.*, a.rma_no from rma_instruction r"
                + " left join rma_authorization a on a.tenant_id = r.tenant_id and a.id = r.rma_id"
                + " where r.tenant_id = ?::uuid and r.id = ?::uuid",
            currentTenant(), id);
    if (head == null) {
      throw new IllegalArgumentException("退货指令不存在或不属于当前租户：" + id);
    }
    return head;
  }

  private Record requireDraft(UUID id) {
    Record head = requireRecord(id);
    RmaInstructionStatus status =
        RmaInstructionStatus.valueOf(head.get("instruction_status", String.class));
    if (status != RmaInstructionStatus.DRAFT) {
      throw new IllegalArgumentException(
          "只有草稿可以下发或修改，当前状态：" + status.label()
              + (status.terminal() ? "（已结案：内容与行是证据不是草稿）" : ""));
    }
    return head;
  }

  /**
   * 乐观锁：版本必须与请求一致。
   *
   * <p>两个人同时点「下发」时各写一次而表里只留得下后一个 ——
   * 少的那一次会让「买家到底有没有收到指引」出现两个答案，而这句话是要拿去和买家对质的。
   */
  private void requireVersion(Record head, Long expectedVersion) {
    long current = head.get("version", Long.class);
    if (expectedVersion == null) {
      throw new IllegalArgumentException("必须给出 expectedVersion（乐观锁，避免并发下发互相覆盖）");
    }
    if (expectedVersion != current) {
      throw new IllegalArgumentException(
          "版本不符（当前 " + current + "，请求 " + expectedVersion + "），请刷新后重试");
    }
  }

  private String timeliness(RmaInstructionStatus status, OffsetDateTime due, OffsetDateTime at) {
    if (!status.open()) {
      return "CLOSED";
    }
    if (due == null) {
      return "NOT_APPLICABLE";
    }
    long seconds = Duration.between(at, due).getSeconds();
    if (seconds < 0) {
      return "OVERDUE";
    }
    return seconds <= DUE_SOON_SECONDS ? "DUE_SOON" : "ON_TRACK";
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

  private static BigDecimal requireNonNegative(BigDecimal value, String label) {
    if (value == null) {
      return null;
    }
    if (value.signum() < 0) {
      throw new IllegalArgumentException(label + "不能为负");
    }
    return value;
  }

  private static String requireText(String value, String label) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(label + "不能为空");
    }
    if (value.length() > 1000) {
      throw new IllegalArgumentException(label + "不能超过 1000 个字符");
    }
    return value.trim();
  }

  /** 可空文本的定长上限：超长直接拒（静默截断会让「买家看到的说明和运营写的不一样」）。 */
  private static String limited(String value, int max, String label) {
    String trimmed = blankToEmpty(value);
    if (trimmed.length() > max) {
      throw new IllegalArgumentException(label + "不能超过 " + max + " 个字符（当前 " + trimmed.length() + "）");
    }
    return trimmed;
  }

  private static boolean isSet(String value) {
    return value != null && !value.isBlank();
  }

  private static String blankToEmpty(String value) {
    return value == null ? "" : value.trim();
  }

  private static String appendIfSet(String value) {
    return isSet(value) ? "  " + value.trim() : "";
  }

  private static String joinNonEmpty(String sep, String... parts) {
    StringBuilder sb = new StringBuilder();
    for (String p : parts) {
      if (!isSet(p)) {
        continue;
      }
      if (!sb.isEmpty()) {
        sb.append(sep);
      }
      sb.append(p.trim());
    }
    return sb.toString();
  }

  private static String stripTrailingZeros(BigDecimal value) {
    BigDecimal v = nz(value).stripTrailingZeros();
    // stripTrailingZeros 在整数值上会给出 3E+1 这种科学计数法，直接 toPlainString 出来是错的。
    return v.toPlainString();
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

  /** 从 left join 出来的 {@code order_line_id}：缺失时报 null 而不是 NPE。 */
  private static UUID cl(Record r, String field) {
    return r.get(field, UUID.class);
  }
}
