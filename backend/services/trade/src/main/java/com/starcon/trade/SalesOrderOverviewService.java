package com.starcon.trade;

import static com.starcon.trade.SalesOrderOverviewModels.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 9.1.1.2 订单详情（控制塔读投影）。
 *
 * <p><b>本类是读投影，不是写入者。</b>{@code sales_order} 及其六张子表的写入所有权属于 9.1.2.2（手工订单）与将来的 9.1.2.1（平台同步）；本类只读，一行
 * INSERT / UPDATE / DELETE 都没有，迁移 V101 也只加了一条索引、不改任何既有表的结构。
 *
 * <p><b>蓝图十一个分区里，只有四个真的有数据。</b>照 8.3.1.2 出库单详情、 9.1.1.1 订单列表已建立的仓库约定：
 *
 * <ul>
 *   <li>有的照常返回，内容真实（买家/地址、商品行、费用构成、状态历史）；
 *   <li>没有的在 {@link #capabilities()} 里逐条 {@code available=false} + {@code ownerModule} +
 *       原因，响应里对应字段是 <b>null</b>；
 *   <li><b>不建占位表、不返回空集冒充成功。</b>
 * </ul>
 *
 * <p><b>本类显式拒绝的三种造假</b>（这是「控制塔」唯一诚实的前提 —— 客服、采购、财务都从这一个页面理解这笔订单为什么长这样，
 * 而「为什么长这样」要求每一段要么是真数据，要么明说它没接上）：
 *
 * <ol>
 *   <li><b>费用分摊</b>：{@code order_charge} 的行是<b>费用构成</b> （这笔单一共收了哪些钱），不是<b>分摊</b>（整单费用按什么比例摊到哪几行）。
 *       {@code order_charge_allocation} 表不存在，V99 第 320-323 行明令 「别拿 charge_kind 当分摊记号」。所以 {@code
 *       chargeAllocations} 恒为 null， <b>绝不拿 chargeTotals 冒充分摊</b>。
 *   <li><b>组件展开</b>：{@code order_line_component} 表在 V99 里根本没建 （实体目录 db/schemas/v2/README.md 第 107
 *       行列了它，是 9.1.2.2 的交付缺口）。 展开是<b>建单那一刻</b>算出来的历史快照，读侧造不出也不该造。
 *   <li><b>用 documentStatus 冒充履约状态</b>：{@code status} 的 CHECK 只有
 *       ('DRAFT','SUBMITTED')，语义是「有没有进审单队列」，完全没有履约概念。 状态时间线照 {@code sales_order_status_history}
 *       <b>真实返回</b>， 但那条表<b>只记录单据维度的状态变化</b>（event_type 只有 CREATED / SUBMITTED），
 *       <b>没有支付或履约事件</b>。所以时间线是真的，而 「支付/履约维度的时间线」另外报 null + ownerModule —— <b>不拿 documentStatus
 *       冒充</b>。
 * </ol>
 *
 * <p><b>时间绑定</b>：一律 {@code ?::timestamptz} + {@link java.time.OffsetDateTime}。 {@code
 * Timestamp.valueOf(LocalDateTime)} 在本项目所在时区静默偏 −28800s， 状态时间线的先后次序会整个错位。
 *
 * <p><b>跨租户与不存在返回同一个 404</b>：让 A 拿 B 的 id 探测存在性本身就是泄露面。 RLS 已经保证跨租户查不到，本类只负责让「查不到」和「不存在」走同一条出口。
 */
@Service
public class SalesOrderOverviewService {

  private final DSLContext sql;

  public SalesOrderOverviewService(DSLContext sql) {
    this.sql = sql;
  }

  // ================================================================ 能力声明

  /**
   * 11 个分区逐段报可得性。
   *
   * <p>存在的理由与 8.3.1.2 / 9.1.1.1 一致：把「哪一段真有数据」「哪一段还没接上、 归谁」显式报出来，而不是让调用方从 404 或空数组里去猜哪一环没通。
   */
  public SalesOrderOverviewCapabilities capabilities() {
    return new SalesOrderOverviewCapabilities(
        List.of(
            "买家 sales_order_party_snapshot（一单一客户，改名改级别都不改写历史）",
            "地址快照 order_address_snapshot（收货 / 账单各一行，已提交后由触发器冻结）",
            "商品行 sales_order_line（SKU / 规格 / 单价 / 数量 / 行税，**全是快照列**，" + "不回查 catalog）",
            "订单行属性快照 order_line_attribute_snapshot（名称 / 值 / 单位 / 来源版本，append-only）",
            "费用构成 order_charge（一行一笔费用，**不是分摊**）",
            "状态历史 sales_order_status_history（**只有单据维度的 CREATED / SUBMITTED**，" + "没有支付或履约事件）"),
        List.of(
            new SalesOrderOverviewSection(
                "chargeAllocations",
                "费用分摊",
                false,
                "9.1.2.2 手工订单（order_charge_allocation 子表的写入所有者）",
                null,
                "**本服务没有费用分摊表**：V99 只建了 order_charge（费用构成），"
                    + "没有 order_charge_allocation。V99 第 320-323 行已明写立场："
                    + "「这里**不是**行级分摊明细……真要做行级分摊时另开子表，"
                    + "**别拿 charge_kind 当分摊记号**」——charge_kind 的 ORDER / LINE "
                    + "说的是「这笔费用挂整单还是记在某行名下」的**归属**，"
                    + "不是「金额按什么比例摊到各行」的**分摊明细**。"
                    + "所以本字段恒为 null：**绝不拿 chargeTotals（费用构成）冒充分摊**。"
                    + "分摊回答「这一行到底买了多少钱」，构成回答「这笔单一共收了哪些钱」，"
                    + "两个问题对运营的含义完全不同。"),
            new SalesOrderOverviewSection(
                "lineComponents",
                "组件展开",
                false,
                "9.1.2.2 手工订单（order_line_component 表的写入所有者）",
                null,
                "**order_line_component 这张表在 V99 里根本没有建**："
                    + "实体目录 db/schemas/v2/README.md 第 107 行列了它（「售出一行展开的组件"
                    + "与数量，历史快照」），但 V99 实际只建了七张表，不含它 —— "
                    + "这是 9.1.2.2 的**交付缺口**，本模块点名上报，不代建。"
                    + "组件展开结果是**建单那一刻**由 BOM 解析与 SKU 映射算出来的历史快照，"
                    + "读侧凭空造不出真数据（更不该造）。"
                    + "catalog 侧 V24 / BundleComponentService 只把 order_line_component "
                    + "当作「下游 09 订单的消费者」提及，catalog 自己也没有这张表。"),
            new SalesOrderOverviewSection(
                "payments",
                "支付记录",
                false,
                "12 财务（实收流水）",
                null,
                "12 财务未交付，本服务没有任何支付流水表。"
                    + "sales_order.payment_method 是**付款方式**（预付 / 货到付 / 电汇 / 账期 / "
                    + "货代代收），语义是**约定**不是**实收**，本模块如实返回在 "
                    + "header.paymentMethod 且**绝不**把它标成支付状态。"),
            new SalesOrderOverviewSection(
                "refunds",
                "退款记录",
                false,
                "12 财务（退款单）",
                null,
                "12 财务未交付。退款是财务侧的单据，没有跨服务 FK 或视图可读"
                    + "（AGENTS.md 模块边界纪律），本模块一律报 null + ownerModule。"),
            new SalesOrderOverviewSection(
                "fulfillmentBatches",
                "履约批次",
                false,
                "9.3.3.1 履约请求",
                "/orders/fulfillment",
                "9.3.3.1 未交付。sales_order 簇里没有任何指向履约请求的列，" + "订单与履约批次之间**尚无关联**。"),
            new SalesOrderOverviewSection(
                "packages",
                "包裹",
                false,
                "8.3.4.1 发货与包裹",
                null,
                "8.3.4.1 未交付。**刻意不返回空数组**：空的包裹列表会被读成" + "「这单没有包裹」而不是「这段能力还没接入」——两者对作业的含义相反。"),
            new SalesOrderOverviewSection(
                "logisticsTrace", "物流轨迹", false, "10 物流与轨迹", null, "10 未交付，本服务没有任何轨迹落库表。"),
            new SalesOrderOverviewSection(
                "aftersalesRefs",
                "售后关联",
                false,
                "11 售后与退货",
                "/aftersales",
                "11 售后一条未做，sales_order 簇里没有任何售后列。"),
            new SalesOrderOverviewSection(
                "platformRawPayload",
                "平台侧原始报文",
                false,
                "9.1.2.1 平台订单同步",
                null,
                "9.1.2.1 未交付，没有任何平台回传报文落库。"
                    + "sales_order.order_source 的 CHECK 已预留全部平台来源词表，"
                    + "但当前只有 MANUAL 有写入入口。"),
            new SalesOrderOverviewSection(
                "fulfillmentStatus",
                "履约状态（时间线维度）",
                false,
                "9.3 履约编排（9.3.3.1 履约请求）",
                "/orders/fulfillment",
                "**sales_order_status_history 的 event_type 只有 ('CREATED','SUBMITTED')** —— "
                    + "它只记录**单据维度**的状态变化（建单、提交审单），"
                    + "**没有支付事件也没有履约事件**。所以本模块返回的时间线是真的，"
                    + "但它是**单据时间线**：绝不拿 documentStatus（DRAFT / SUBMITTED）"
                    + "冒充履约状态 —— 一张已发货的单在界面上会显示成「已提交待审」，"
                    + "而两者对运营的决策含义完全相反。")),
        List.of(
            new SalesOrderOverviewAction(
                "REVIEW",
                "审单",
                false,
                "9.2 审单与异常（9.2.1.1 待审队列 / 9.2.1.2 审单规则）",
                "9.2 交付待审队列与审单规则后，通过 / 驳回才有判定依据",
                "审单未交付：订单只能提交到 SUBMITTED（待审），" + "队列展示与通过 / 驳回都还没有出口。本模块是读投影，不抢审单的动作。"),
            new SalesOrderOverviewAction(
                "NOTE",
                "备注",
                false,
                "9.1.2.2 手工订单（sales_order.note 的写入所有者）",
                "先明确备注的写入所有者与并发语义（乐观锁 version 已就位）",
                "备注要写 sales_order.note，而**该列的写入所有权属于 9.1.2.2**。"
                    + "本模块是读投影，抢写它的事实表会出现「两个模块都在改同一列、"
                    + "只有一方知道 version 怎么递增」的局面。职责不等于写权限。"),
            new SalesOrderOverviewAction(
                "CANCEL",
                "取消",
                false,
                "9.1.1.3 订单变更与取消",
                "9.1.1.3 交付取消流程（含取消原因留痕与已发货转售后的硬规则）",
                "9.1.1.3 未交付：取消需要人写明原因并留痕。"
                    + "蓝图硬规则「已发货订单不能取消只能转售后」需要履约状态才能判定，"
                    + "而履约状态在本服务尚不存在。"),
            new SalesOrderOverviewAction(
                "DUNNING",
                "发催付",
                false,
                "15 客户与客服",
                "15 客服侧交付催付渠道与话术模板",
                "15 客服侧未交付。发催付是**对外发消息**的动作（邮件 / 站内信 / 平台消息），" + "需要话术模板与发送渠道记录，两者都不在本模块。")),
        "GET /trade/sales-orders/detail/fulfillment-reference"
            + "?orderNo=...&orderSource=MANUAL —— 给 warehouse（8.3.1.2 出库单详情）"
            + "调用的授权 API，返回关联销售订单与买家地址。"
            + "**warehouse 必须调这个端点，不得直连 trade 的表，也不建跨库 FK 或视图**"
            + "（AGENTS.md 模块边界纪律）。查询键是订单号 + 来源，"
            + "因为 warehouse 的 outbound_order.source_external_ref 存的就是订单号字符串。",
        "租户**不**来自任何请求头：控制器先向 identity 确认已认证成员关系与权限，"
            + "再在一个事务里绑定 app.current_tenant，由 RLS 在数据库层强制隔离。"
            + "伪造的 X-Tenant-Id 头改不动这个设置。**跨租户与不存在返回同一个 404**，"
            + "让 A 拿 B 的 id 探测存在性本身就是泄露面。",
        "**本模块没有任何写端点。**sales_order 簇的写入所有权属于 9.1.2.2（手工订单）"
            + "与将来的 9.1.2.1（平台同步）。蓝图职责句没点名任何写动作，"
            + "本模块是纯读投影，不抢写别人的事实表。");
  }

  // ================================================================ 详情

  /**
   * 订单全景详情。跨租户在 RLS 下查不到 → 与「不存在」同一个 404。
   *
   * @param selfOnly 数据范围 SELF 时收窄到本人经手过的订单（本人身份来自已认证身份， <b>不接受请求参数传入</b>，否则「只看我自己的单」会变成一个能越权的开关）
   */
  public SalesOrderOverview overview(UUID orderId, UUID actorId, boolean selfOnly) {
    Record head = headerRow(orderId, actorId, selfOnly);
    if (head == null) {
      throw notFound(orderId);
    }
    return assemble(head, orderId);
  }

  /** 拼装整屏详情。head 必须已确认存在（RLS 已过滤跨租户）。 */
  private SalesOrderOverview assemble(Record head, UUID orderId) {
    SalesOrderOverviewHeader header =
        new SalesOrderOverviewHeader(
            head.get("id", UUID.class),
            head.get("order_no", String.class),
            head.get("external_order_no", String.class),
            head.get("order_source", String.class),
            blankToNull(head.get("manual_kind", String.class)),
            head.get("status", String.class),
            head.get("currency", String.class),
            head.get("subtotal_amount", BigDecimal.class),
            head.get("discount_amount", BigDecimal.class),
            head.get("tax_amount", BigDecimal.class),
            head.get("shipping_amount", BigDecimal.class),
            head.get("other_amount", BigDecimal.class),
            head.get("grand_total_amount", BigDecimal.class),
            head.get("payment_method", String.class),
            head.get("payment_terms_days", Integer.class),
            head.get("note", String.class),
            head.get("submitted_at", java.time.OffsetDateTime.class),
            head.get("created_at", java.time.OffsetDateTime.class),
            head.get("updated_at", java.time.OffsetDateTime.class));

    SalesOrderOverviewParty buyer = party(orderId);
    List<SalesOrderOverviewAddress> addresses = addresses(orderId);
    List<SalesOrderOverviewLine> lines = lines(orderId);
    Map<String, List<SalesOrderOverviewLineAttribute>> attributes = attributesByLine(orderId);
    List<SalesOrderOverviewCharge> charges = charges(orderId);

    return new SalesOrderOverview(
        header,
        buyer,
        addresses,
        lines,
        attributes,
        charges,
        totals(head, charges),
        // ↓↓↓ 六段不可得分区：恒为 null，**不是空数组、不是 0**。
        // 理由逐条见 capabilities()，每一段都点名 ownerModule。
        null, // chargeAllocations：没有 order_charge_allocation 表（9.1.2.2）
        null, // lineComponents：order_line_component 表在 V99 里没建（9.1.2.2）
        timeline(orderId),
        null, // payments：12 财务未交付
        null, // refunds：12 财务未交付
        null, // fulfillmentBatches：9.3.3.1 未交付
        null, // packages：8.3.4.1 未交付
        null, // logisticsTrace：10 未交付
        null, // aftersalesRefs：11 未交付
        null, // platformRawPayload：9.1.2.1 未交付
        actions(),
        "SINGLE_ORDER",
        "按订单 id 读取单张订单，不受列表页数据范围影响 —— "
            + "详情页是控制塔，客服 / 采购 / 财务要看到的是这笔订单的全貌。"
            + "租户隔离由 RLS 在数据库层强制：租户来自已认证成员关系，不信任任何请求头。");
  }

  /**
   * 订单头 + 数据范围 SELF 收窄。
   *
   * <p>跨租户在 RLS 下查不到 → 与「不存在」返回同一个 404。 SELF 范围用 {@code exists (sales_order_status_history.actor_id
   * = 本人)} 判定 「本人经手过」，与 9.1.1.1 列表页同一口径、同一张表。
   */
  private Record headerRow(UUID orderId, UUID actorId, boolean selfOnly) {
    StringBuilder sqlText = new StringBuilder();
    sqlText.append("select id, order_no, external_order_no, order_source, manual_kind,");
    sqlText.append(" status, currency, subtotal_amount, discount_amount, tax_amount,");
    sqlText.append(" shipping_amount, other_amount, grand_total_amount, payment_method,");
    sqlText.append(" payment_terms_days, note, submitted_at, created_at, updated_at");
    sqlText.append(" from sales_order where id = ?");
    List<Object> binds = new ArrayList<>();
    binds.add(orderId);
    if (selfOnly) {
      if (actorId == null) {
        throw new IllegalArgumentException("数据范围限定为本人时必须能解析出操作人");
      }
      sqlText.append(
          " and exists (select 1 from sales_order_status_history h"
              + " where h.tenant_id = sales_order.tenant_id"
              + " and h.order_id = sales_order.id and h.actor_id = ?)");
      binds.add(actorId);
    }
    return sql.fetchOne(sqlText.toString(), binds.toArray());
  }

  private SalesOrderOverviewParty party(UUID orderId) {
    Record r =
        sql.fetchOne(
            "select customer_party_id, customer_code, customer_name, customer_type,"
                + " customer_default_currency from sales_order_party_snapshot"
                + " where order_id = ?",
            orderId);
    if (r == null) {
      return null;
    }
    return new SalesOrderOverviewParty(
        r.get("customer_party_id", UUID.class),
        r.get("customer_code", String.class),
        r.get("customer_name", String.class),
        r.get("customer_type", String.class),
        blankToNull(r.get("customer_default_currency", String.class)));
  }

  private List<SalesOrderOverviewAddress> addresses(UUID orderId) {
    return sql.fetch(
            "select address_role, recipient_name, recipient_phone, recipient_email,"
                + " company_name, country_code, province, city, district, postcode,"
                + " address_line1, address_line2, tax_id, note"
                + " from order_address_snapshot where order_id = ?"
                + " order by address_role",
            orderId)
        .map(
            r ->
                new SalesOrderOverviewAddress(
                    r.get("address_role", String.class),
                    r.get("recipient_name", String.class),
                    blankToNull(r.get("recipient_phone", String.class)),
                    blankToNull(r.get("recipient_email", String.class)),
                    blankToNull(r.get("company_name", String.class)),
                    r.get("country_code", String.class),
                    blankToNull(r.get("province", String.class)),
                    blankToNull(r.get("city", String.class)),
                    blankToNull(r.get("district", String.class)),
                    blankToNull(r.get("postcode", String.class)),
                    r.get("address_line1", String.class),
                    blankToNull(r.get("address_line2", String.class)),
                    blankToNull(r.get("tax_id", String.class)),
                    blankToNull(r.get("note", String.class))));
  }

  private List<SalesOrderOverviewLine> lines(UUID orderId) {
    return sql.fetch(
            "select id, line_no, sku_id, sku_code, sku_name, spec_snapshot,"
                + " sku_mapping_version, quantity, unit_price, discount_amount, tax_amount,"
                + " line_amount, note from sales_order_line where order_id = ? order by line_no",
            orderId)
        .map(
            r ->
                new SalesOrderOverviewLine(
                    r.get("id", UUID.class),
                    r.get("line_no", Integer.class),
                    r.get("sku_id", UUID.class),
                    r.get("sku_code", String.class),
                    r.get("sku_name", String.class),
                    blankToNull(r.get("spec_snapshot", String.class)),
                    blankToNull(r.get("sku_mapping_version", String.class)),
                    r.get("quantity", BigDecimal.class),
                    r.get("unit_price", BigDecimal.class),
                    r.get("discount_amount", BigDecimal.class),
                    r.get("tax_amount", BigDecimal.class),
                    r.get("line_amount", BigDecimal.class),
                    blankToNull(r.get("note", String.class))));
  }

  /**
   * 行属性快照按 line_id 分组。
   *
   * <p>键用 {@code String}（uuid 的字符串形式）而不是 {@link UUID}： 响应要序列化成 JSON map，而 JSON 对象的键只能是字符串。
   */
  private Map<String, List<SalesOrderOverviewLineAttribute>> attributesByLine(UUID orderId) {
    Map<String, List<SalesOrderOverviewLineAttribute>> byLine = new LinkedHashMap<>();
    for (Record r :
        sql.fetch(
            "select a.id, a.line_id, a.attribute_name, a.attribute_value, a.attribute_unit,"
                + " a.source_version from order_line_attribute_snapshot a"
                + " where a.order_id = ? order by a.line_id, a.attribute_name",
            orderId)) {
      byLine
          .computeIfAbsent(r.get("line_id", UUID.class).toString(), k -> new ArrayList<>())
          .add(
              new SalesOrderOverviewLineAttribute(
                  r.get("id", UUID.class),
                  r.get("line_id", UUID.class),
                  r.get("attribute_name", String.class),
                  blankToNull(r.get("attribute_value", String.class)),
                  blankToNull(r.get("attribute_unit", String.class)),
                  blankToNull(r.get("source_version", String.class))));
    }
    return byLine;
  }

  private List<SalesOrderOverviewCharge> charges(UUID orderId) {
    return sql.fetch(
            "select id, charge_type, label, amount, charge_kind, note from order_charge"
                + " where order_id = ? order by charge_type, created_at, id",
            orderId)
        .map(
            r ->
                new SalesOrderOverviewCharge(
                    r.get("id", UUID.class),
                    r.get("charge_type", String.class),
                    blankToNull(r.get("label", String.class)),
                    r.get("amount", BigDecimal.class),
                    r.get("charge_kind", String.class),
                    blankToNull(r.get("note", String.class))));
  }

  /**
   * 状态时间线：照 {@code sales_order_status_history} <b>真实返回</b>。
   *
   * <p><b>刻意按 (occurred_at, id) 升序</b>：id 兜底是必要的 —— 同一毫秒发生的事件光看 {@code occurred_at} 顺序不确定，
   * 界面上会随机跳行。
   *
   * <p><b>但它只是「单据时间线」。</b>该表 {@code event_type} 的 CHECK 只有
   * ('CREATED','SUBMITTED')，<b>没有支付事件也没有履约事件</b>； 那些维度另外报 null + ownerModule，见 capabilities() 的
   * {@code fulfillmentStatus} 段。
   */
  private List<SalesOrderOverviewStatusEvent> timeline(UUID orderId) {
    return sql.fetch(
            "select id, event_type, from_status, to_status, reason, actor_id, occurred_at"
                + " from sales_order_status_history where order_id = ?"
                + " order by occurred_at, id",
            orderId)
        .map(
            r ->
                new SalesOrderOverviewStatusEvent(
                    r.get("id", UUID.class),
                    r.get("event_type", String.class),
                    r.get("from_status", String.class),
                    r.get("to_status", String.class),
                    blankToNull(r.get("reason", String.class)),
                    r.get("actor_id", UUID.class),
                    r.get("occurred_at", java.time.OffsetDateTime.class)));
  }

  /**
   * 费用<b>构成</b>汇总 —— <b>不是分摊</b>。
   *
   * <p>逐项对齐订单头上的汇总。V99 的 {@code guard_sales_order_totals} 延迟约束触发器保证头与明细永远相等，所以这里对不上就是读错了，
   * 不是「有业务差额」。刻意保留订单头的那一份作为真值返回， 同时报出 charge 行数让界面能对上「N 笔费用」。
   */
  private SalesOrderOverviewTotals totals(Record head, List<SalesOrderOverviewCharge> charges) {
    return new SalesOrderOverviewTotals(
        head.get("subtotal_amount", BigDecimal.class),
        head.get("discount_amount", BigDecimal.class),
        head.get("tax_amount", BigDecimal.class),
        head.get("shipping_amount", BigDecimal.class),
        head.get("other_amount", BigDecimal.class),
        head.get("grand_total_amount", BigDecimal.class),
        head.get("currency", String.class),
        charges.size(),
        "**这是费用构成，不是费用分摊。**它回答「这笔单一共收了哪些钱」。"
            + "分摊（这笔整单费用按什么比例摊到哪几行）在本服务**没有表**"
            + "（order_charge_allocation 不存在），所以 chargeAllocations 恒为 null。"
            + "两者对运营的含义完全不同：构成用于对账，分摊用于算「这一行买了多少钱」。");
  }

  private List<SalesOrderOverviewAction> actions() {
    return capabilities().actions();
  }

  // ================================================================ 履约引用（给 warehouse）

  /**
   * 给 warehouse 调用的履约引用：关联销售订单 + 买家地址 + 行。
   *
   * <p><b>为什么按 order_no 查而不是按 uuid</b>：warehouse 的 {@code outbound_order.source_type='SALES'} 时，
   * {@code source_external_ref} 存的就是销售订单<b>号</b>字符串 （V72 第 114 行原文：「外部单号：9 销售订单号」，且刻意是 varchar 而不是
   * uuid —— 跨服务不猜对方 id 形态，更重要的是它让「一个订单多张出库单」 （分仓 / 分批）在数据上成立）。warehouse 手上只有这个字符串， 没有 trade 的
   * uuid，所以查询键就是 order_no + order_source。
   *
   * <p><b>order_source 必填且必校验</b>：同一串号码在 MANUAL 与将来的 PLATFORM 下 是两件不相干的事，只按订单号查会把不同来源的同号订单混进一次响应 （与
   * V99 第 539 行 {@code sales_order_external_no_unique} 同一纪律）。
   *
   * <p><b>地址原样返回订单侧冻结的那一份快照</b>：warehouse <b>不</b>抄第二份 —— 抄了以后地址改过两份会分叉，而作业员分不清哪份是对的。 要留寄件快照应由
   * 8.3.4.1 在寄出那一刻自己冻结（与 8.3.1.2 的立场一致）。
   *
   * <p>跨租户与不存在<b>返回同一个 404</b>。
   */
  public SalesOrderOverviewFulfillmentReference fulfillmentReference(
      String orderNo, String orderSource) {
    if (orderNo == null || orderNo.isBlank()) {
      throw new IllegalArgumentException(
          "订单号不能为空：warehouse 按 outbound_order.source_external_ref 查");
    }
    if (orderSource == null || orderSource.isBlank()) {
      throw new IllegalArgumentException(
          "订单来源不能为空：同一串订单号在不同来源下是两件不相干的事，" + "只按订单号查会把 MANUAL 与将来的 PLATFORM 的同号订单混进一次响应");
    }
    Record head =
        sql.fetchOne(
            "select id, order_no, external_order_no, order_source, status, currency,"
                + " grand_total_amount, submitted_at from sales_order"
                + " where order_no = ? and order_source = ?",
            orderNo.trim(),
            orderSource.trim().toUpperCase());
    if (head == null) {
      // 与详情端点**同一个 404 文案形状**：不区分「不存在」与「不是你的」。
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, "销售订单 " + orderNo + "（来源 " + orderSource + "）不存在或无权查看");
    }
    UUID id = head.get("id", UUID.class);
    SalesOrderOverviewAddress shipTo = addressByRole(id, "SHIPPING");
    return new SalesOrderOverviewFulfillmentReference(
        id,
        head.get("order_no", String.class),
        blankToNull(head.get("external_order_no", String.class)),
        head.get("order_source", String.class),
        head.get("status", String.class),
        head.get("currency", String.class),
        head.get("grand_total_amount", BigDecimal.class),
        head.get("submitted_at", java.time.OffsetDateTime.class),
        party(id),
        shipTo,
        addressByRole(id, "BILLING"),
        lines(id),
        "这是**订单侧冻结的那一份地址快照**（V99 硬要求 4：不是引用活地址）。"
            + "warehouse 应当直接展示，不要在出库单上抄第二份 —— "
            + "抄了以后地址改过两份会分叉，而作业员分不清哪份是对的。"
            + "documentStatus 是单据状态（DRAFT / SUBMITTED），"
            + "**不是履约状态**。本响应不含包裹、轨迹、售后与支付，那些归各自的 ownerModule。");
  }

  private SalesOrderOverviewAddress addressByRole(UUID orderId, String role) {
    return addresses(orderId).stream()
        .filter(a -> role.equals(a.addressRole()))
        .findFirst()
        .orElse(null);
  }

  // ================================================================ 内部

  private static ResponseStatusException notFound(UUID orderId) {
    // 跨租户在 RLS 下查不到 → 与「不存在」走**同一个出口**：
    // 让 A 拿 B 的 id 探测存在性本身就是泄露面。
    return new ResponseStatusException(HttpStatus.NOT_FOUND, "订单 " + orderId + " 不存在或无权查看");
  }

  /** 空串一律折成 null：V99 的若干列是 {@code NOT NULL DEFAULT ''}，空串不是「有值」。 */
  private static String blankToNull(String v) {
    return v == null || v.isBlank() ? null : v;
  }
}
