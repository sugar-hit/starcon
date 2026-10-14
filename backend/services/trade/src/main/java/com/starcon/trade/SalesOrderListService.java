package com.starcon.trade;

import static com.starcon.trade.SalesOrderListModels.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Service;

/**
 * 9.1.1.1 订单列表（全渠道统一订单列表读投影）。
 *
 * <p><b>本类是读投影，不是写入者。</b>{@code sales_order} 及其六张子表的写入所有权属于
 * 9.1.2.2（手工单）与将来的 9.1.2.1（平台单同步）；本类只读，不 INSERT / UPDATE / DELETE
 * 任何一张表，也不为蓝图要但当前不存在的维度建占位表。
 *
 * <p><b>「拿不到」与「没有」是两回事，代码里必须分得开。</b>蓝图 9.1.1.1 点名的九个维度里，
 * 支付状态 / 履约状态 / 平台店铺 / 发货仓 / 平台状态 / 售后状态在本服务<b>不存在</b>。
 * 本类的做法（照 8.3.1.2 出库单详情已建立的仓库约定）：
 *
 * <ul>
 *   <li>存在的维度照常返回，并支持筛选与排序；</li>
 *   <li>不存在的维度在 {@link #capabilities()} 里逐条 {@code available=false} + ownerModule +
 *       原因，列表响应里对应字段是 <b>null</b>；</li>
 *   <li><b>不提供</b>那些维度的筛选参数与排序项 —— 一个永远筛不出东西的下拉框
 *       比没有这个下拉框更糟，它会让人以为这条链路已经通了。</li>
 * </ul>
 *
 * <p><b>两个最容易犯的语义造假，本类显式拒绝</b>：
 * {@code status}（DRAFT / SUBMITTED，语义是「有没有进审单队列」）<b>不是</b>履约状态；
 * {@code payment_method}（预付 / 货到付 / 电汇 / 账期，语义是「约定」）<b>不是</b>支付状态。
 * 两者在响应里是<b>两个不同字段</b>，且后者的「真身」字段恒为 null。
 *
 * <p><b>时间绑定</b>：时间筛选一律 {@code ?::timestamptz} + {@link OffsetDateTime}。
 * {@code Timestamp.valueOf(LocalDateTime)} 会静默偏 −28800s（本项目所在时区），
 * 「按下单时间筛选」会因此筛出错的区间。
 *
 * <p><b>分页是真分页</b>：{@code LIMIT ? OFFSET ?} + 一条独立的 {@code count(*)} 总数查询。
 * 汇总条与分页无关（统计的是<b>命中筛选条件</b>的全部行，不是当前页）。
 */
@Service
public class SalesOrderListService {

  private static final int MAX_PAGE_SIZE = 200;

  private final DSLContext sql;

  public SalesOrderListService(DSLContext sql) {
    this.sql = sql;
  }

  // ================================================================ 能力声明

  /**
   * 能力声明。
   *
   * <p>存在的理由与 8.3.1.2 / 9.3.2.2 一致：把「哪些维度真有依据」「哪些批量操作能落地」
   * 显式报出来，而不是让调用方从 404 或空数组里去猜哪一环没通。
   */
  public SalesOrderListCapabilities capabilities() {
    return new SalesOrderListCapabilities(
        List.of(
            "订单号 sales_order.order_no",
            "下单时间 sales_order.created_at",
            "订单金额与币种 sales_order.grand_total_amount + currency",
            "来源 sales_order.order_source（CHECK 已含 MANUAL 与全部平台来源，"
                + "平台来源当前还没有写入入口）",
            "单据状态 sales_order.status（DRAFT / SUBMITTED，语义是「有没有进审单队列」）",
            "买家 sales_order_party_snapshot（客户快照，一单一客户）",
            "SKU 行数与 SKU 筛选 sales_order_line"),
        List.of(
            new SalesOrderListDimension(
                "platformShop",
                "平台店铺",
                false,
                "2 平台同步（9.1.2.1 平台订单同步 / 2.x 店铺归属）",
                "sales_order **没有店铺列**。平台同步未交付；手工单本就无平台店铺。"
                    + "本服务不建占位列——建一个恒为 NULL 的 shop 列，会让「平台同步没交付」"
                    + "被读成「这家店还没有订单」。"),
            new SalesOrderListDimension(
                "paymentStatus",
                "支付状态",
                false,
                "12 财务（实收流水）",
                "sales_order 只有 **payment_method（付款方式）**，语义是**约定**"
                    + "（预付 / 货到付 / 电汇 / 账期 / 货代代收），不是**实收**。"
                    + "支付状态（已付 / 部分付 / 未付 / 逾期）由 12 财务记账，本服务不越界。"
                    + "把 payment_method 标成 paymentStatus 是语义造假，"
                    + "会让「这张单还没收款」被读成「这张单约定货到付」——两者对催款的含义相反。"),
            new SalesOrderListDimension(
                "fulfillmentStatus",
                "履约状态",
                false,
                "9.3 履约编排（9.3.3.1 履约请求）",
                "sales_order.status 的 CHECK 只有 ('DRAFT','SUBMITTED')，"
                    + "语义是「这张单有没有进审单队列」，**完全没有履约概念**"
                    + "（未拣货 / 已拣货 / 已发货 / 已签收）。本模块把它单独命名为 "
                    + "documentStatus 并如实返回，绝不拿它冒充履约状态——"
                    + "否则一张已发货的单在界面上会显示成「已提交待审」。"),
            new SalesOrderListDimension(
                "shippingWarehouse",
                "发货仓",
                false,
                "9.3.3.1 履约请求",
                "8.3 出库侧存在，但**销售订单与出库单之间尚无履约请求关联**，"
                    + "sales_order 上没有任何列指向发货仓。仓是履约决定的，不是下单时定的。"),
            new SalesOrderListDimension(
                "platformStatus",
                "平台状态",
                false,
                "9.1.2.1 平台订单同步",
                "平台同步未交付，没有任何平台回传状态落库。"),
            new SalesOrderListDimension(
                "aftersalesStatus",
                "售后状态",
                false,
                "11 售后与退货",
                "11 售后一条未做，sales_order 簇里没有任何售后列。"
                    + "已发货订单转售后才会产生这个状态，那是 11 的事。")),
        List.of(
            new SalesOrderListBulkOperation(
                "REVIEW",
                "审单",
                false,
                "9.2 审单与异常（9.2.1.1 待审队列 / 9.2.1.2 审单规则）",
                "9.2 交付待审队列与审单规则后，批量通过 / 驳回才有判定依据",
                "审单未交付：订单只能提交到 SUBMITTED（待审），"
                    + "队列展示与批量通过 / 驳回都还没有出口。本模块是读投影，不抢审单的动作。"),
            new SalesOrderListBulkOperation(
                "NOTE",
                "备注",
                false,
                "9.1.2.2 手工订单（sales_order.note 的写入所有者）",
                "需要先明确备注的写入所有者与并发语义（乐观锁 version 已就位）",
                "备注要写 sales_order.note，而**该列的写入所有权属于 9.1.2.2**。"
                    + "本模块是 sales_order 的**读投影**，抢写它的事实表会出现"
                    + "「两个模块都在改同一列、只有一方知道 version 怎么递增」的局面。"
                    + "蓝图把备注写在本模块职责里，但职责不等于写权限。"),
            new SalesOrderListBulkOperation(
                "CANCEL",
                "取消",
                false,
                "9.1.1.3 订单变更与取消",
                "9.1.1.3 交付取消流程（含取消原因留痕与已发货转售后的硬规则）",
                "9.1.1.3 未交付：取消需要人写明原因并留痕。"
                    + "蓝图硬规则「已发货订单不能取消只能转售后」需要履约状态才能判定，"
                    + "而履约状态在本服务尚不存在。"),
            new SalesOrderListBulkOperation(
                "DUNNING",
                "发催付",
                false,
                "15 客户与客服",
                "15 客服侧交付催付渠道与话术模板",
                "15 客服侧未交付。发催付是**对外发消息**的动作（邮件 / 站内信 / 平台消息），"
                    + "需要话术模板与发送渠道记录，两者都不在本模块。")),
        List.of(SalesOrderListSort.values()).stream()
            .map(s -> new SalesOrderListSortOption(s.name(), s.label()))
            .toList(),
        "可筛选维度：来源 / 单据状态 / 币种 / 买家 / SKU / 下单时间区间 / 金额区间 / 关键字。"
            + "蓝图点名的平台、店铺、支付状态、履约状态、发货仓、售后状态**六个筛选项刻意不提供**——"
            + "它们在本服务不存在，提供一个永远筛不出东西的下拉框会让界面误以为该链路已通。",
        "**导出未交付**：蓝图职责句含「导出」，但导出的可用性取决于「导出哪些维度」，"
            + "而六个关键维度当前不可得。在履约 / 支付维度落地前导出一份缺列的 CSV，"
            + "运营会拿它去对账或对平台，缺列的文件比不提供导出更危险，故本模块不提供导出端点。");
  }

  // ================================================================ 列表

  /** 全渠道统一订单列表：真分页 + 与分页无关的全量汇总（只统计命中筛选条件的行）。 */
  public SalesOrderListPage list(SalesOrderListQuery query) {
    int page = Math.max(0, query.page() == null ? 0 : query.page());
    int size = Math.min(MAX_PAGE_SIZE, Math.max(1, query.size() == null ? 50 : query.size()));
    SalesOrderListSort sort = SalesOrderListSort.parse(query.sort());
    List<Object> binds = new ArrayList<>();
    List<String> where = conditions(query, binds);

    // 数据范围 SELF：只看本人经手过的订单。actorId 与 selfOnly 都来自**已认证身份**，
    // 不接受任何请求参数传入 —— 否则「只看我自己的单」会变成一个能越权的开关。
    boolean selfOnly = Boolean.TRUE.equals(query.selfOnly());
    if (selfOnly && query.actorId() == null) {
      throw new IllegalArgumentException("数据范围限定为本人时必须能解析出操作人");
    }

    String clause = where.isEmpty() ? "" : " where " + String.join(" and ", where);
    String from =
        " from sales_order o"
            + " left join sales_order_party_snapshot p"
            + " on p.tenant_id = o.tenant_id and p.order_id = o.id"
            + clause;

    long total =
        sql.fetchOne("select count(*)" + from, binds.toArray()).get(0, Long.class);

    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(size);
    pageBinds.add((long) page * size);
    List<SalesOrderListRow> rows =
        sql.fetch(
                "select o.id, o.order_no, o.external_order_no, o.order_source, o.manual_kind,"
                    + " o.status, o.currency, o.grand_total_amount, o.submitted_at, o.created_at,"
                    + " p.customer_party_id, p.customer_name,"
                    + " (select count(*) from sales_order_line l"
                    + "   where l.tenant_id = o.tenant_id and l.order_id = o.id) as line_count,"
                    + " (select string_agg(l2.sku_code, ',' order by l2.line_no)"
                    + "   from (select ll.sku_code, ll.line_no from sales_order_line ll"
                    + "     where ll.tenant_id = o.tenant_id and ll.order_id = o.id"
                    + "     order by ll.line_no limit 8) l2) as sku_codes"
                    + from
                    + " order by "
                    + sort.sql()
                    + " limit ? offset ?",
                pageBinds.toArray())
            .map(SalesOrderListService::row);

    return new SalesOrderListPage(
        rows,
        page,
        size,
        total,
        summary(from, binds),
        selfOnly ? "SELF" : "ALL",
        selfOnly
            ? "数据范围 SELF：只显示本人经手过的订单（sales_order_status_history.actor_id = 本人）。"
                + "本人身份来自已认证的成员关系，不接受请求参数传入。"
            : "数据范围 ALL：本租户全部订单。多租户隔离由 RLS 在数据库层强制，与本范围无关。");
  }

  /** 筛选项下拉用的租户级词表（不随时间筛选变化，避免下拉随翻页漂移）。 */
  public SalesOrderListFilterOptions filterOptions() {
    Record currencies =
        sql.fetchOne(
            "select coalesce(string_agg(distinct currency, ',' order by currency), '') as c"
                + " from sales_order");
    List<String> list = new ArrayList<>();
    String raw = currencies == null ? "" : currencies.get("c", String.class);
    if (raw != null && !raw.isBlank()) {
      for (String c : raw.split(",")) {
        if (!c.isBlank()) list.add(c.trim());
      }
    }
    return new SalesOrderListFilterOptions(
        Arrays.stream(com.starcon.trade.ManualOrderModels.SalesOrderSource.values())
            .map(Enum::name)
            .toList(),
        Arrays.stream(com.starcon.trade.ManualOrderModels.SalesOrderStatus.values())
            .map(Enum::name)
            .toList(),
        list,
        Arrays.stream(SalesOrderListSort.values())
            .map(s -> new SalesOrderListSortOption(s.name(), s.label()))
            .toList());
  }

  // ================================================================ 内部

  /**
   * WHERE 条件。**每一条都对应一个真实存在的列或一条真实存在的聚合** ——
   * 蓝图点名的六个不可得维度刻意不出现在这里。
   */
  private List<String> conditions(SalesOrderListQuery query, List<Object> binds) {
    List<String> where = new ArrayList<>();
    if (isSet(query.orderSource())) {
      where.add("o.order_source = ?");
      binds.add(query.orderSource().trim());
    }
    if (isSet(query.documentStatus())) {
      where.add("o.status = ?");
      binds.add(query.documentStatus().trim());
    }
    if (isSet(query.currency())) {
      String currency = query.currency().trim().toUpperCase();
      if (!currency.matches("^[A-Z]{3}$")) {
        throw new IllegalArgumentException("币种必须是三位大写字母");
      }
      where.add("o.currency = ?");
      binds.add(currency);
    }
    if (query.customerPartyId() != null) {
      where.add("p.customer_party_id = ?");
      binds.add(query.customerPartyId());
    }
    if (isSet(query.buyerKeyword())) {
      where.add("p.customer_name ilike ?");
      binds.add("%" + query.buyerKeyword().trim() + "%");
    }
    if (query.skuId() != null) {
      // 「这个 SKU 出现在哪些订单里」是反向查询，走 V100 的
      // sales_order_line_by_sku(tenant_id, sku_id, order_id)。
      where.add(
          "exists (select 1 from sales_order_line l"
              + " where l.tenant_id = o.tenant_id and l.order_id = o.id and l.sku_id = ?)");
      binds.add(query.skuId());
    }
    // 时间筛选：?::timestamptz + OffsetDateTime，禁 Timestamp.valueOf（静默偏 −28800s）。
    if (query.orderedFrom() != null) {
      where.add("o.created_at >= ?::timestamptz");
      binds.add(query.orderedFrom());
    }
    if (query.orderedTo() != null) {
      where.add("o.created_at <= ?::timestamptz");
      binds.add(query.orderedTo());
    }
    if (query.minAmount() != null) {
      where.add("o.grand_total_amount >= ?");
      binds.add(query.minAmount());
    }
    if (query.maxAmount() != null) {
      where.add("o.grand_total_amount <= ?");
      binds.add(query.maxAmount());
    }
    if (isSet(query.keyword())) {
      // 真实模糊检索：内部单号、外部单号或买家名。
      // 刻意**不含**金额 / 日期的模糊匹配 —— 那会让「搜 100」返回一堆无关订单。
      String pattern = "%" + query.keyword().trim() + "%";
      where.add("(o.order_no ilike ? or o.external_order_no ilike ? or p.customer_name ilike ?)");
      binds.add(pattern);
      binds.add(pattern);
      binds.add(pattern);
    }
    if (Boolean.TRUE.equals(query.selfOnly())) {
      // 本人经手过 = sales_order_status_history 里有本人的留痕。
      // 刻意**不**给 sales_order 加 owner 列：写入所有权属于 9.1.2.2，
      // 读投影不改别人的表结构（V100 只加了两条索引）。
      where.add(
          "exists (select 1 from sales_order_status_history h"
              + " where h.tenant_id = o.tenant_id and h.order_id = o.id and h.actor_id = ?)");
      binds.add(query.actorId());
    }
    return where;
  }

  /**
   * 汇总条：订单数、金额、待处理数。
   *
   * <p>统计的是<b>命中筛选条件的全部行</b>（与分页无关），而不是当前页。
   *
   * <p><b>金额按币种分开报</b>：租户内同时存在多种币种时，把 USD 与 EUR 加成一个数
   * 没有意义 —— 那不是任何一个金额。
   */
  private SalesOrderListSummary summary(String from, List<Object> binds) {
    Record head =
        sql.fetchOne(
            "select count(*) as total,"
                + " count(*) filter (where o.status = 'DRAFT') as draft,"
                + " count(*) filter (where o.status = 'SUBMITTED') as submitted"
                + from,
            binds.toArray());
    Map<String, Record> byCurrency = new LinkedHashMap<>();
    for (Record r :
        sql.fetch(
            "select o.currency, sum(o.grand_total_amount) as amount, count(*) as cnt"
                + from
                + " group by o.currency order by o.currency",
            binds.toArray())) {
      byCurrency.put(r.get("currency", String.class), r);
    }
    List<SalesOrderListCurrencyAmount> amounts =
        byCurrency.entrySet().stream()
            .map(
                e ->
                    new SalesOrderListCurrencyAmount(
                        e.getKey(),
                        e.getValue().get("amount", BigDecimal.class),
                        e.getValue().get("cnt", Long.class)))
            .toList();
    return new SalesOrderListSummary(
        head.get("total", Long.class),
        head.get("draft", Long.class),
        head.get("submitted", Long.class),
        amounts,
        null,
        "9.2 审单与异常",
        "「待处理数」报 available=false：它的口径由 9.2 审单定义（待审？待付？待发？），"
            + "9.2 未交付。本模块**刻意不用**「DRAFT 算待处理」自造一个替代口径"
            + "却在界面上宣称它就是待处理数 —— 那会让运营照着一个从未被定义过的口径做决策。"
            + "页面请显示单据状态（DRAFT / SUBMITTED）作为事实，并在「待处理」一栏说明它尚无口径。");
  }

  private static SalesOrderListRow row(Record r) {
    String skuCodes = r.get("sku_codes", String.class);
    return new SalesOrderListRow(
        r.get("id", UUID.class),
        r.get("order_no", String.class),
        r.get("external_order_no", String.class),
        r.get("order_source", String.class),
        blankToNull(r.get("manual_kind", String.class)),
        r.get("status", String.class),
        r.get("currency", String.class),
        r.get("grand_total_amount", BigDecimal.class),
        r.get("line_count", Integer.class),
        r.get("customer_party_id", UUID.class),
        r.get("customer_name", String.class),
        skuCodes == null || skuCodes.isBlank() ? List.of() : List.of(skuCodes.split(",")),
        r.get("submitted_at", OffsetDateTime.class),
        r.get("created_at", OffsetDateTime.class),
        // ↓↓↓ 六个恒为 null 的字段（见 SalesOrderListModels 的类注释）。
        //     它们不是「本单没有这些值」，而是「本服务还没有这些概念」——
        //     返回 0 或假枚举会让运营照着一个从未存在过的口径做决策。
        null, // platformStatus    → 9.1.2.1 平台订单同步
        null, // paymentStatus     → 12 财务（sales_order 只有付款方式 payment_method）
        null, // fulfillmentStatus → 9.3 履约（status 是 DRAFT/SUBMITTED，不是履约状态）
        null, // shippingWarehouse → 9.3.3.1 履约请求
        null // aftersalesStatus  → 11 售后
        );
  }

  private static boolean isSet(String v) {
    return v != null && !v.isBlank();
  }

  private static String blankToNull(String v) {
    return v == null || v.isBlank() ? null : v;
  }
}
