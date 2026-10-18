package com.starcon.trade;

import static com.starcon.trade.ExchangeOrderLinkModels.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 11.3.2.2 换货新单。
 *
 * <p>蓝图 11-aftersales-and-returns.md：「<b>建立换货关联（原售后 ↔ 新销售单/发货单），
 * 跟踪换货闭环状态，防止重复换货。</b>」输入：「换货授权；新单创建请求。」
 * 输出：「换货关联记录（exchange_order_link）+ 新单状态跟踪。」
 *
 * <p><b>本模块与「假交付」的分界线</b>：最容易做成假的写法是记一行
 * {@code new_order_no='EX-2026-0001', new_order_status='SHIPPED'}，界面上「换货已发货」——
 * 而 sales_order 里根本没有这张单、仓库也没收到任何需求。那不是新单，
 * 那是把「打算发一单」记成「已经发了」。所以本模块的每一次建单都**真的**在
 * {@code sales_order} 建单并写齐行 / 收货地址快照 / 客户快照（V99 的延迟约束触发器
 * 在提交时逐项验收），每一次「已发出」都只承认**读到的**真实发货记录
 * （{@code shipment_record}，9.4.1.1 拥有），读不到就停在「已建立」。
 *
 * <p><b>并发为什么必须先锁授权再建单</b>：本模块防的是「同一张换货授权并发建两张换货新单」。
 * 两次请求都会真的建出两张 sales_order（各拿到一个真单号），
 * 所以唯一的防线是 {@code UNIQUE(tenant_id, authorization_id)}；
 * 而要让它在**发出副作用之前**判掉第二次，必须先 {@code SELECT ... FOR UPDATE}
 * 锁住 exchange_authorization。顺序反了，第二次请求就会先在 sales_order 里
 * 留下一张没人认领的新单 —— 那正是「重复换货」最常见的落地形态。
 *
 * <p><b>不修改原出库事实</b>：对 sales_order 的写入路径只有「新建换货新单」与
 * 「这张新单自己 DRAFT → SUBMITTED」，从不 UPDATE 原订单的任何一列。
 * 原单号 / 原 SKU 以快照落在关联头上，换货完成后「替哪一件换的」仍然查得到。
 *
 * <p><b>闭环只认真实表</b>：CLOSED 要求「已发出」<b>且</b>旧货回寄要求已结清。
 * 旧货实收归 11.2，而实查 V161 的 rma_authorization 词表里没有 RECEIVED，
 * 所以本模块自造 RETURNED 就会与将来的实收状态机打架 —— 与 11.3.2.1
 * 当初不落 RETURNED 同一条理由。capabilities 如实报
 * {@code oldGoodsReturnReceivedDelivered=false}。
 */
@Service
public class ExchangeOrderLinkService {

  /** 列表排序白名单：只认这几个键，其余一律回落默认，绝不把任意表达式拼进 ORDER BY。 */
  private static final Map<String, String> SORTS =
      Map.of(
          "created_at", "k.created_at desc, k.id desc",
          "created_at_asc", "k.created_at asc, k.id asc",
          "link_no", "k.link_no asc, k.id asc",
          "new_order_no", "k.new_order_no asc, k.id asc",
          "case_no", "k.case_no asc, k.id asc",
          // 「还没发出的排最前」是履约视角最高频的一问。
          "attention_first",
              "case when k.closure_status = 'CREATED' then 0 else 1 end,"
                  + " k.created_at desc, k.id desc");

  private final DSLContext sql;

  public ExchangeOrderLinkService(DSLContext sql) {
    this.sql = sql;
  }

  // ================================================================ 能力声明

  /**
   * 能力声明：逐条报出上游接入情况、闭环口径与两条硬规则。
   *
   * <p>{@code hardRules} 把蓝图那句加粗原样交给前端：建单对话框上必须显示它，
   * 而不是让运营在不知情的情况下建出一张永远不会被履约的新单。
   */
  public ExchangeOrderCapabilities capabilities() {
    return new ExchangeOrderCapabilities(
        true,
        true,
        true,
        "9.4.1.1",
        false,
        "9.3.3.1",
        false,
        "11.2",
        List.of("CREATED", "DISPATCHED", "CLOSED", "CANCELLED"),
        List.of("已建立（待提交履约）", "替换货已发出", "换货已闭环", "已撤销"),
        List.of("SUBMIT", "SYNC", "CANCEL"),
        "换货新单是真的销售单：建单即在 sales_order 里落一行并写齐商品行、收货地址快照与客户快照，"
            + "9 订单与 8 出库接手的是同一张单，不是本模块自己的一串编号。"
            + "「已发出」只承认读到的真实发货记录（9.4.1.1 的 shipment_record），读不到就一直停在「已建立」——"
            + "我们不把「应该已经发出」记成「已经发出」。",
        List.of(
            "同一张换货授权只能有一张换货新单，同一条授权行只能进一张换货新单 —— 防重复换货",
            "换货闭环 = 替换货已发出 **且** 旧货回寄要求已结清；只发不收就是白送，"
                + "所以还有旧货在等回寄时不会显示闭环",
            "换货新单不修改原出库事实：原订单与原 SKU 以快照保留，换货完成后仍可完整追溯"));
  }

  // ================================================================ 列表与筛选

  /** 列表：真分页（独立 COUNT）+ 汇总 + 筛选 + 排序白名单。 */
  public ExchangeOrderPage list(ExchangeOrderQuery q) {
    int page = q.page() == null || q.page() < 0 ? 0 : q.page();
    int size = q.size() == null || q.size() < 1 ? 20 : Math.min(q.size(), 100);
    UUID tenant = currentTenant();

    var where = new StringBuilder(" where k.tenant_id = ?::uuid");
    List<Object> binds = new ArrayList<>();
    binds.add(tenant);
    if (notBlank(q.authorizationId())) {
      where.append(" and k.authorization_id = ?::uuid");
      binds.add(UUID.fromString(q.authorizationId()));
    }
    if (notBlank(q.caseId())) {
      where.append(" and k.case_id = ?::uuid");
      binds.add(UUID.fromString(q.caseId()));
    }
    if (notBlank(q.orderId())) {
      where.append(" and (k.original_order_id = ?::uuid or k.new_order_id = ?::uuid)");
      binds.add(UUID.fromString(q.orderId()));
      binds.add(UUID.fromString(q.orderId()));
    }
    if (notBlank(q.status())) {
      where.append(" and k.closure_status = ?");
      binds.add(q.status());
    }
    if (notBlank(q.keyword())) {
      where.append(
          " and (k.link_no ilike ? or k.new_order_no ilike ? or k.case_no ilike ?"
              + " or k.authorization_no ilike ? or k.original_order_no ilike ?"
              + " or exists (select 1 from exchange_order_link_line l"
              + " where l.tenant_id = k.tenant_id and l.link_id = k.id"
              + " and (l.sku_code ilike ? or l.sku_name ilike ? or l.original_sku_code ilike ?)))");
      String kw = "%" + q.keyword() + "%";
      for (int i = 0; i < 8; i++) {
        binds.add(kw);
      }
    }

    long total =
        one("select count(*) from exchange_order_link k" + where, binds.toArray()).get(0, Long.class);

    String sort = SORTS.getOrDefault(q.sort() == null ? "" : q.sort(), SORTS.get("created_at"));

    var rows =
        sql.fetch(
            "select k.id,k.link_no,k.authorization_id,k.authorization_no,k.case_id,k.case_no,"
                + "k.original_order_id,k.original_order_no,k.new_order_id,k.new_order_no,"
                + "k.new_order_status,k.closure_status,k.currency,k.total_quantity,"
                + "k.replacement_sku_count,k.line_count,k.new_order_amount,k.shipment_count,"
                + "k.first_shipped_at,k.shipment_record_no,k.last_tracking_status,"
                + "k.old_goods_return_status,k.reason_note,k.created_by,k.created_at,"
                + "k.submitted_at,k.dispatched_at,k.closed_at,k.cancelled_by,k.cancelled_at,"
                + "k.cancel_reason,k.version"
                + " from exchange_order_link k"
                + where
                + " order by "
                + sort
                + " limit ? offset ?",
            bindsWith(binds, size, page * size));

    var items = rows.stream().map(ExchangeOrderLinkService::toRow).toList();
    return new ExchangeOrderPage(items, total, page, size, summary(where, binds));
  }

  /** 汇总：与列表同一套筛选，所以「第一页 1 条却说有 3 条」这种矛盾不会出现。 */
  private ExchangeOrderSummary summary(StringBuilder where, List<Object> binds) {
    var row =
        one(
            "select count(*) as total_count,"
                + "count(*) filter (where k.closure_status='CREATED') as created_count,"
                + "count(*) filter (where k.closure_status='DISPATCHED') as dispatched_count,"
                + "count(*) filter (where k.closure_status='CLOSED') as closed_count,"
                + "count(*) filter (where k.closure_status='CANCELLED') as cancelled_count,"
                + "coalesce(sum(k.total_quantity),0) as sum_qty"
                + " from exchange_order_link k"
                + where,
            binds.toArray());
    return new ExchangeOrderSummary(
        row.get("total_count", Long.class),
        row.get("created_count", Long.class),
        row.get("dispatched_count", Long.class),
        row.get("closed_count", Long.class),
        row.get("cancelled_count", Long.class),
        qty(row.get("sum_qty", BigDecimal.class)),
        row.get("created_count", Long.class));
  }

  /**
   * 下拉项：可建换货新单的换货授权单。
   *
   * <p>只列 ACTIVE 且**尚未建过换货新单**的授权 —— 已经建过的新点进来必然被
   * UNIQUE 拒掉，撤销过的授权连新单都建不出来。把它放进下拉等于让运营填完一整张单再收一个 409。
   */
  public ExchangeOrderFilterOptions filters() {
    UUID tenant = currentTenant();
    var rows =
        sql.fetch(
            "select a.id,a.authorization_no,a.case_id,a.case_no,a.order_id,"
                + "coalesce(o.order_no,'') as order_no,a.status,a.decision,"
                + "a.authorized_quantity,"
                + "(select count(distinct l.replacement_sku_id) from exchange_authorization_line l"
                + " where l.tenant_id = a.tenant_id and l.authorization_id = a.id) as sku_count,"
                + "(select count(*) from order_address_snapshot ad"
                + " where ad.tenant_id = a.tenant_id and ad.order_id = a.order_id"
                + " and ad.address_role = 'SHIPPING') as shipping_count"
                + " from exchange_authorization a"
                + " left join sales_order o on o.tenant_id = a.tenant_id and o.id = a.order_id"
                + " where a.tenant_id = ?::uuid and a.status = 'ACTIVE'"
                + " and not exists (select 1 from exchange_order_link k"
                + " where k.tenant_id = a.tenant_id and k.authorization_id = a.id)"
                + " order by a.authorized_at desc limit 200",
            tenant);

    var options =
        rows.stream()
            .map(
                r -> {
                  UUID authorizationId = r.get("id", UUID.class);
                  var lines =
                      sql.fetch(
                              "select l.id,l.replacement_sku_id,coalesce(l.replacement_sku_code,'') as sku_code,"
                                  + "coalesce(l.replacement_sku_name,'') as sku_name,"
                                  + "coalesce(l.original_sku_code,'') as original_sku_code,"
                                  + "l.authorized_quantity"
                                  + " from exchange_authorization_line l"
                                  + " where l.tenant_id = ?::uuid and l.authorization_id = ?::uuid"
                                  + " order by l.original_sku_code, l.id",
                              tenant,
                              authorizationId)
                          .stream()
                          .map(
                              ln ->
                                  new ExchangeOrderAuthorizationLineOption(
                                      ln.get("id", UUID.class),
                                      ln.get("replacement_sku_id", UUID.class),
                                      ln.get("sku_code", String.class),
                                      ln.get("sku_name", String.class),
                                      ln.get("original_sku_code", String.class),
                                      qty(ln.get("authorized_quantity", BigDecimal.class))))
                          .toList();
                  return new ExchangeOrderAuthorizationOption(
                      authorizationId,
                      r.get("authorization_no", String.class),
                      r.get("case_id", UUID.class),
                      r.get("case_no", String.class),
                      r.get("order_id", UUID.class),
                      r.get("order_no", String.class) == null ? "" : r.get("order_no", String.class),
                      r.get("status", String.class),
                      "生效中",
                      r.get("decision", String.class),
                      decisionLabel(r.get("decision", String.class)),
                      qty(r.get("authorized_quantity", BigDecimal.class)),
                      r.get("sku_count", Integer.class),
                      // 建单要照抄原单的收货地址快照；原单没有快照时建单会被 V99 的
                      // 延迟约束触发器拒掉，所以这里提前把「不能建」标出来而不是让人填完再收 409。
                      r.get("shipping_count", Integer.class) > 0,
                      lines);
                })
            .toList();

    return new ExchangeOrderFilterOptions(
        List.of("CREATED", "DISPATCHED", "CLOSED", "CANCELLED"),
        List.of("已建立（待提交履约）", "替换货已发出", "换货已闭环", "已撤销"),
        List.of("attention_first", "created_at", "created_at_asc", "link_no", "new_order_no", "case_no"),
        List.of("待处理优先", "建单时间（新→旧）", "建单时间（旧→新）", "换货新单号", "新销售单号", "售后单号"),
        options,
        options.size());
  }

  // ================================================================ 详情

  /**
   * 单张换货新单详情。
   *
   * <p>不存在的 id 与不属于本租户的 id 返回<b>同一条</b> 404 ——
   * 「这个 id 存在但不属于你」本身也是一条信息泄露。
   */
  public ExchangeOrderDetail detail(UUID id) {
    UUID tenant = currentTenant();
    var head =
        one(
            "select k.id,k.link_no,k.authorization_id,k.authorization_no,k.case_id,k.case_no,"
                + "k.original_order_id,k.original_order_no,k.new_order_id,k.new_order_no,"
                + "k.new_order_status,k.closure_status,k.currency,k.total_quantity,"
                + "k.replacement_sku_count,k.line_count,k.new_order_amount,k.shipment_count,"
                + "k.first_shipped_at,k.shipment_record_no,k.last_tracking_status,"
                + "k.old_goods_return_status,k.reason_note,k.created_by,k.created_at,"
                + "k.submitted_at,k.dispatched_at,k.closed_at,k.cancelled_by,k.cancelled_at,"
                + "k.cancel_reason,k.version"
                + " from exchange_order_link k where k.tenant_id = ?::uuid and k.id = ?::uuid",
            tenant,
            id);
    if (head == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "换货新单不存在或不属于当前租户");
    }
    return new ExchangeOrderDetail(
        toRow(head),
        lines(id),
        events(id),
        allowedActions(head.get("closure_status", String.class)),
        closureBlockers(head));
  }

  /** 逐行视图：授权行 → 新单行、换几件、替的是哪一件。 */
  private List<ExchangeOrderLineView> lines(UUID linkId) {
    return sql
        .fetch(
            "select l.id,l.authorization_line_id,l.new_order_line_id,l.new_order_line_no,"
                + "l.sku_id,l.sku_code,l.sku_name,l.original_sku_id,l.original_sku_code,l.quantity"
                + " from exchange_order_link_line l"
                + " where l.tenant_id = ?::uuid and l.link_id = ?::uuid"
                + " order by l.new_order_line_no, l.id",
            currentTenant(),
            linkId)
        .stream()
        .map(
            r ->
                new ExchangeOrderLineView(
                    r.get("id", UUID.class),
                    r.get("authorization_line_id", UUID.class),
                    r.get("new_order_line_id", UUID.class),
                    r.get("new_order_line_no", Integer.class),
                    r.get("sku_id", UUID.class),
                    r.get("sku_code", String.class),
                    r.get("sku_name", String.class),
                    r.get("original_sku_id", UUID.class),
                    r.get("original_sku_code", String.class),
                    qty(r.get("quantity", BigDecimal.class))))
        .toList();
  }

  /** 闭环事件时间线。 */
  private List<ExchangeOrderEventView> events(UUID linkId) {
    return sql
        .fetch(
            "select e.id,e.event_kind,e.from_closure_status,e.to_closure_status,e.detail,"
                + "e.actor_membership_id,e.occurred_at"
                + " from exchange_order_link_event e"
                + " where e.tenant_id = ?::uuid and e.link_id = ?::uuid"
                + " order by e.occurred_at, e.id",
            currentTenant(),
            linkId)
        .stream()
        .map(
            r ->
                new ExchangeOrderEventView(
                    r.get("id", UUID.class),
                    r.get("event_kind", String.class),
                    eventLabel(r.get("event_kind", String.class)),
                    r.get("from_closure_status", String.class),
                    r.get("to_closure_status", String.class),
                    r.get("detail", String.class),
                    r.get("actor_membership_id", UUID.class),
                    r.get("occurred_at", OffsetDateTime.class)))
        .toList();
  }

  /**
   * 没闭环的真实原因。
   *
   * <p>只给一个「已建立」而不说清还差什么，页面就只能显示灰状态 —— 而灰状态会被读成
   * 「系统在处理中」，于是「9.3.3.1 根本没接手」这件事永远没人知道。
   */
  private List<String> closureBlockers(Record head) {
    UUID tenant = currentTenant();
    String status = head.get("closure_status", String.class);
    if ("CLOSED".equals(status) || "CANCELLED".equals(status)) {
      return List.of();
    }
    List<String> blockers = new ArrayList<>();
    if (!"SUBMITTED".equals(head.get("new_order_status", String.class))) {
      blockers.add("换货新单还没提交进审单队列（9.2 审单未交付，提交后需人工处理）");
    }
    if (head.get("shipment_count", Integer.class) == 0) {
      blockers.add("还没有真实发货记录：8 出库 / 9.3.3.1 未接手这批发货需求，本服务只读发货记录、不代写");
    }
    long waiting =
        one(
                "select count(*) from exchange_return_requirement x"
                    + " where x.tenant_id = ?::uuid and x.authorization_id = ?::uuid"
                    + " and x.status = 'WAITING'",
                tenant,
                head.get("authorization_id", UUID.class))
            .get(0, Long.class);
    if (waiting > 0) {
      blockers.add("还有 " + waiting + " 行旧货在等买家寄回 —— 只发不收就是白送，所以还不算闭环");
    }
    return blockers;
  }

  private List<String> allowedActions(String status) {
    return "CREATED".equals(status) ? List.of("SUBMIT", "SYNC", "CANCEL") : List.of("SYNC");
  }

  // ================================================================ 建换货新单

  /**
   * <b>建立换货新单</b>：真在 sales_order 建单 + 写齐快照 + 落换货关联。
   *
   * <p>顺序是 load-bearing 的：<b>先锁换货授权、再建单</b>，理由见类注释。
   *
   * <p>快照三件套（客户 / 收货地址）<b>照抄原订单</b>：换货发给同一个买家，
   * 而 V99 硬要求 4 明说地址是快照不是引用活地址 —— 买家事后改地址改不动任何历史单，
   * 换货单也一样。原单没有收货地址快照时这里 409 拒掉（V99 的延迟约束触发器
   * 在提交时同样会拒），而不是编一个空地址把单建出来。
   */
  @Transactional
  public ExchangeOrderDetail create(ExchangeOrderCreateRequest req, UUID actorId, UUID membershipId) {
    if (req == null || req.authorizationId() == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "必须给 authorizationId");
    }
    UUID tenant = currentTenant();

    // ---- ① 锁住换货授权（并发下真正起作用的那道防线的第一半）----
    var auth =
        one(
            "select id,authorization_no,case_id,case_no,order_id,status"
                + " from exchange_authorization"
                + " where tenant_id = ?::uuid and id = ?::uuid for update",
            tenant,
            req.authorizationId());
    if (auth == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "换货授权单不存在或不属于当前租户");
    }
    if (!"ACTIVE".equals(auth.get("status", String.class))) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY, "换货授权单已撤销，不能建换货新单");
    }

    // ---- ② 同一张授权只能有一张换货新单（在发出建单副作用**之前**拒掉第二次）----
    var existing =
        one(
            "select link_no from exchange_order_link where tenant_id = ?::uuid and authorization_id = ?::uuid",
            tenant,
            req.authorizationId());
    if (existing != null) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "这张换货授权已经建过换货新单（" + existing.get(0, String.class) + "），不能重复换货");
    }

    // ---- ③ 读授权行（换什么、几件的真值全在这里，数量由授权钉死）----
    var authLines =
        sql.fetch(
            "select l.id,l.replacement_sku_id,coalesce(l.replacement_sku_code,'') as sku_code,"
                + "coalesce(l.replacement_sku_name,'') as sku_name,"
                + "coalesce(l.original_sku_code,'') as original_sku_code,"
                + "l.original_sku_id,l.authorized_quantity"
                + " from exchange_authorization_line l"
                + " where l.tenant_id = ?::uuid and l.authorization_id = ?::uuid"
                + " order by l.original_sku_code, l.id",
            tenant,
            req.authorizationId());
    if (authLines.isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY, "换货授权单没有任何明细行，无法建换货新单");
    }

    // ---- ④ 原订单：币种、收货地址快照、客户快照都从它来（不回查 catalog、不编）----
    UUID originalOrderId = auth.get("order_id", UUID.class);
    var order =
        one(
            "select id,order_no,currency from sales_order where tenant_id = ?::uuid and id = ?::uuid",
            tenant,
            originalOrderId);
    if (order == null) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, "原销售订单不存在或不属于当前租户：" + originalOrderId);
    }
    var shipping =
        one(
            "select id from order_address_snapshot"
                + " where tenant_id = ?::uuid and order_id = ?::uuid and address_role = 'SHIPPING'",
            tenant,
            originalOrderId);
    if (shipping == null) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "原订单 " + order.get("order_no", String.class) + " 没有收货地址快照，无法建换货新单："
              + "换货新单的收货地址只能照抄原单，编一个地址等于把货寄给一个不存在的地方");
    }
    var party =
        one(
            "select id from sales_order_party_snapshot where tenant_id = ?::uuid and order_id = ?::uuid",
            tenant,
            originalOrderId);
    if (party == null) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "原订单 " + order.get("order_no", String.class) + " 没有客户快照，无法建换货新单");
    }

    String reason = req.reasonNote() == null ? "" : req.reasonNote();
    OffsetDateTime now = OffsetDateTime.now().truncatedTo(ChronoUnit.MILLIS);

    // ---- ⑤ 真的在 sales_order 建单（9 订单与 8 出库接手的就是这一张）----
    UUID newOrderId = UUID.randomUUID();
    String newOrderNo = nextNewOrderNo();
    // payment_method 固定 PREPAID：换货不发生第二笔收款，原单已付过；金额恒 0（见 V167 第二节）。
    // ⚠️ 订单号不采信调用方传入的值：EXO- 前缀 + 本服务发号器，与手工单 MO- 天然不撞。
    sql.execute(
        "insert into sales_order(id,tenant_id,order_no,order_source,manual_kind,external_order_no,"
            + "currency,payment_method,payment_terms_days,status,subtotal_amount,discount_amount,"
            + "tax_amount,shipping_amount,other_amount,grand_total_amount,note,version,"
            + "created_at,updated_at)"
            + " values(?,?::uuid,?,'MANUAL','EXCHANGE','',?,'PREPAID',0,'DRAFT',0,0,0,0,0,0,?,0,"
            + "?::timestamptz,?::timestamptz)",
        newOrderId,
        tenant,
        newOrderNo,
        order.get("currency", String.class),
        "换货新单：替代原单 " + order.get("order_no", String.class) + (reason.isBlank() ? "" : "。" + reason),
        now,
        now);

    // 客户快照照抄原单（V99：一单一客户；改客户档案不改历史单）。
    sql.execute(
        "insert into sales_order_party_snapshot(id,tenant_id,order_id,customer_party_id,"
            + "customer_code,customer_name,customer_type,customer_default_currency,created_at)"
            + " select ?::uuid,?::uuid,?::uuid,p.customer_party_id,p.customer_code,p.customer_name,"
            + "p.customer_type,p.customer_default_currency,?::timestamptz"
            + " from sales_order_party_snapshot p where p.tenant_id = ?::uuid and p.order_id = ?::uuid",
        UUID.randomUUID(),
        tenant,
        newOrderId,
        now,
        tenant,
        originalOrderId);

    // 收货地址快照照抄原单（换货发给同一个买家）。
    sql.execute(
        "insert into order_address_snapshot(id,tenant_id,order_id,address_role,recipient_name,"
            + "recipient_phone,recipient_email,company_name,country_code,province,city,district,"
            + "postcode,address_line1,address_line2,tax_id,note,created_at)"
            + " select ?::uuid,?::uuid,?::uuid,a.address_role,a.recipient_name,a.recipient_phone,"
            + "a.recipient_email,a.company_name,a.country_code,a.province,a.city,a.district,"
            + "a.postcode,a.address_line1,a.address_line2,a.tax_id,?::varchar,?::timestamptz"
            + " from order_address_snapshot a"
            + " where a.tenant_id = ?::uuid and a.order_id = ?::uuid and a.address_role = 'SHIPPING'",
        UUID.randomUUID(),
        tenant,
        newOrderId,
        "换货新单收货地址照抄原单 " + order.get("order_no", String.class),
        now,
        tenant,
        originalOrderId);

    // ---- ⑥ 商品行：数量恒等于授权量，单价 0（换货不二次收费）----
    var newLines = new ArrayList<NewOrderLine>();
    BigDecimal totalQuantity = BigDecimal.ZERO;
    int seq = 0;
    for (var l : authLines) {
      seq++;
      UUID lineId = UUID.randomUUID();
      BigDecimal quantity =
          l.get("authorized_quantity", BigDecimal.class).setScale(6, RoundingMode.HALF_UP);
      totalQuantity = totalQuantity.add(quantity);
      sql.execute(
          "insert into sales_order_line(id,tenant_id,order_id,line_no,sku_id,sku_code,sku_name,"
              + "spec_snapshot,sku_mapping_version,quantity,unit_price,discount_amount,tax_amount,"
              + "line_amount,note,created_at)"
              + " values(?,?::uuid,?::uuid,?,?::uuid,?,?,'','',?::numeric,0,0,0,0,?::varchar,"
              + "?::timestamptz)",
          lineId,
          tenant,
          newOrderId,
          seq,
          l.get("replacement_sku_id", UUID.class),
          l.get("sku_code", String.class),
          l.get("sku_name", String.class),
          quantity,
          "换货替换品：替原 SKU " + l.get("original_sku_code", String.class),
          now);
      newLines.add(new NewOrderLine(l.get("id", UUID.class), lineId, seq, quantity));
    }

    // ---- ⑦ 换货关联（输出句点名的 exchange_order_link）----
    UUID linkId = UUID.randomUUID();
    int skuCount =
        (int)
            authLines.stream()
                .map(r -> r.get("replacement_sku_id", UUID.class))
                .distinct()
                .count();
    sql.execute(
        "insert into exchange_order_link(id,tenant_id,link_no,authorization_id,case_id,"
            + "original_order_id,new_order_id,closure_status,currency,total_quantity,"
            + "replacement_sku_count,line_count,charge_mode,new_order_amount,version,"
            + "reason_note,created_by,created_at,updated_at)"
            + " values(?,?::uuid,?,?::uuid,?::uuid,?::uuid,?::uuid,'CREATED',?::char(3),"
            + "?::numeric,?,?,'NO_CHARGE',0,1,?,?::uuid,?::timestamptz,?::timestamptz)",
        linkId,
        tenant,
        nextLinkNo(),
        auth.get("id", UUID.class),
        auth.get("case_id", UUID.class),
        originalOrderId,
        newOrderId,
        order.get("currency", String.class),
        totalQuantity,
        skuCount,
        newLines.size(),
        reason,
        actorId,
        now,
        now);

    // ---- ⑧ 逐行关联 + 事件留痕 ----
    for (var nl : newLines) {
      var authLine =
          authLines.stream()
              .filter(r -> r.get("id", UUID.class).equals(nl.authorizationLineId()))
              .findFirst()
              .orElseThrow();
      sql.execute(
          "insert into exchange_order_link_line(id,tenant_id,link_id,authorization_line_id,"
              + "new_order_line_id,new_order_line_no,sku_id,sku_code,sku_name,original_sku_id,"
              + "original_sku_code,quantity,created_at)"
              + " values(?,?::uuid,?::uuid,?::uuid,?::uuid,?,?::uuid,?,?,?::uuid,?,?::numeric,"
              + "?::timestamptz)",
          UUID.randomUUID(),
          tenant,
          linkId,
          nl.authorizationLineId(),
          nl.newOrderLineId(),
          nl.lineNo(),
          authLine.get("replacement_sku_id", UUID.class),
          authLine.get("sku_code", String.class),
          authLine.get("sku_name", String.class),
          authLine.get("original_sku_id", UUID.class),
          authLine.get("original_sku_code", String.class),
          nl.quantity(),
          now);
    }
    appendEvent(linkId, "LINK_CREATED", "", "CREATED", "换货新单 " + newOrderNo + " 已建立，替代原单 "
        + order.get("order_no", String.class), membershipId);

    return detail(linkId);
  }

  // ================================================================ 提交 / 同步 / 撤销

  /**
   * 提交换货新单进审单队列：真把 sales_order 从 DRAFT 推到 SUBMITTED。
   *
   * <p>与建单**分成两个事务**（与 9.3.2.2 手工单提交同一理由）：提交一旦发生，
   * V99 的冻结触发器就把行 / 地址 / 费用冻住 —— 建单与提交放同一个事务里，
   * 会让「刚建的单」在还没确认前就被锁死。
   */
  @Transactional
  public ExchangeOrderDetail submit(UUID id, ExchangeOrderSubmitRequest req, UUID membershipId) {
    UUID tenant = currentTenant();
    var link = lockLink(id);
    // 乐观锁先于状态判断：拿着旧版本号的人看到的是一个过期视图，
    // 告诉他「版本冲突」比告诉他「已经不是草稿态」准确（后者会让人以为是别人提交了）。
    expectVersion(link, req == null ? null : req.expectedVersion());
    if (!"CREATED".equals(link.get("closure_status", String.class))) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "只有「已建立」的换货新单能提交；本单当前是 "
              + closureLabel(link.get("closure_status", String.class)));
    }
    if (!"DRAFT".equals(link.get("new_order_status", String.class))) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "换货新单已经不是草稿态");
    }

    OffsetDateTime now = now();
    sql.execute(
        "update sales_order set status = 'SUBMITTED', submitted_at = ?::timestamptz,"
            + " version = version + 1, updated_at = ?::timestamptz"
            + " where tenant_id = ?::uuid and id = ?::uuid and status = 'DRAFT'",
        now,
        now,
        tenant,
        link.get("new_order_id", UUID.class));
    sql.execute(
        "update exchange_order_link set new_order_status = 'SUBMITTED', submitted_at = ?::timestamptz,"
            + " version = version + 1, updated_at = ?::timestamptz"
            + " where tenant_id = ?::uuid and id = ?::uuid",
        now,
        now,
        tenant,
        id);
    appendEvent(
        id,
        "SUBMITTED",
        "CREATED",
        "CREATED",
        "换货新单 " + link.get("new_order_no", String.class) + " 已提交进审单队列，等待 9.2 审单与 8 出库接手",
        membershipId);
    return detail(id);
  }

  /**
   * <b>同步闭环状态</b>：只去读真实发货记录与旧货回寄要求，然后据此推进。
   *
   * <p>刻意不接受任何状态入参 —— 「请把状态置成已发货」这种形状在这里不存在，
   * 那正是假交付的入口。发货事实归 8 出库（读 {@code shipment_record}，9.4.1.1 拥有），
   * 旧货回寄状态归 11.3.2.1（读 {@code exchange_return_requirement}），
   * 本服务**只读不写**这两处事实。
   *
   * <p>DB 层另有 {@code trg_exchange_order_link_dispatch} 兜底：绕过服务层直接
   * UPDATE 状态成已发出，而没有真实发货记录时，提交时同样被数据库拒掉。
   */
  @Transactional
  public ExchangeOrderDetail sync(UUID id, ExchangeOrderSyncRequest req, UUID membershipId) {
    UUID tenant = currentTenant();
    var link = lockLink(id);
    expectVersion(link, req == null ? null : req.expectedVersion());
    String from = link.get("closure_status", String.class);
    if ("CANCELLED".equals(from)) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "换货新单已撤销，闭环状态不再推进");
    }

    // ---- ① 真实发货事实 ----
    var shipment =
        one(
            "select count(*) as cnt, min(shipped_at) as first_shipped_at,"
                + "(array_agg(record_no order by shipped_at desc, id))[1] as record_no,"
                + "(array_agg(tracking_status order by shipped_at desc, id))[1] as tracking"
                + " from shipment_record"
                + " where tenant_id = ?::uuid and sales_order_id = ?::uuid and status <> 'VOID'",
            tenant,
            link.get("new_order_id", UUID.class));
    int shipmentCount = shipment == null ? 0 : shipment.get("cnt", Integer.class);
    OffsetDateTime firstShippedAt = shipment == null ? null : shipment.get("first_shipped_at", OffsetDateTime.class);
    String recordNo = shipment == null ? "" : orEmpty(shipment.get("record_no", String.class));
    // ⚠️ 聚合查询**永远返回一行**：没有匹配记录时 array_agg 给出的是 NULL 而不是「没有行」。
    //   直接把 NULL 当 tracking 写进去会撞上 last_tracking_status 的 CHECK，
    //   而这里的语义应该是「从来没有观测过」= UNSET。
    String tracking =
        shipment == null || shipment.get("tracking", String.class) == null
            ? "UNSET"
            : shipment.get("tracking", String.class);

    // ---- ② 旧货回寄要求（11.3.2.1 拥有，本服务只读）----
    var returns =
        one(
            "select count(*) filter (where x.status='WAITING') as waiting,"
                + " count(*) filter (where x.status='WAIVED') as waived"
                + " from exchange_return_requirement x"
                + " where x.tenant_id = ?::uuid and x.authorization_id = ?::uuid",
            tenant,
            link.get("authorization_id", UUID.class));
    long waiting = returns == null ? 0L : returns.get("waiting", Long.class);
    long waived = returns == null ? 0L : returns.get("waived", Long.class);
    String returnStatus = waiting > 0 ? "WAITING" : "WAIVED";

    // ---- ③ 推进：只按读到的真事实走 ----
    String to = from;
    if (!"SUBMITTED".equals(link.get("new_order_status", String.class))) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "换货新单还没提交进审单队列，不可能有发货记录；请先提交");
    }
    // 曾经推进到已发出、而真实发货记录现在全被作废（VOID）时**不许悄悄退回「已建立」**：
    // 「发出去了又作废」是一条要人判断的业务事实（货可能已经离仓），系统不该自己抹平。
    if (shipmentCount == 0 && !"CREATED".equals(from)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "换货新单 "
              + link.get("new_order_no", String.class)
              + " 此前已标记为「"
              + closureLabel(from)
              + "」，但现在读不到任何有效发货记录（全部被作废或已删除）。"
              + "这属于货是否真的发出去了的业务判断，系统不会自行退回状态，请人工核实后处理");
    }
    if (shipmentCount > 0) {
      to = waiting == 0 ? "CLOSED" : "DISPATCHED";
    }

    OffsetDateTime now = now();
    sql.execute(
        "update exchange_order_link set closure_status = ?, shipment_count = ?,"
            + " first_shipped_at = ?::timestamptz, shipment_record_no = ?,"
            + " last_tracking_status = ?, old_goods_return_status = ?,"
            + " dispatched_at = case when ? <> 'CREATED' then ?::timestamptz else dispatched_at end,"
            + " closed_at = case when ? = 'CLOSED' then ?::timestamptz else closed_at end,"
            + " version = version + 1, updated_at = ?::timestamptz"
            + " where tenant_id = ?::uuid and id = ?::uuid",
        to,
        shipmentCount,
        firstShippedAt,
        recordNo,
        tracking,
        returnStatus,
        to,
        now,
        to,
        now,
        now,
        tenant,
        id);

    // 事件只在**状态真的变了**时留：每次点同步都塞一行会把真正的状态变化淹掉。
    if (!from.equals(to)) {
      appendEvent(
          id,
          "CLOSURE_SYNCED",
          from,
          to,
          "读到 "
              + shipmentCount
              + " 条真实发货记录"
              + (recordNo.isBlank() ? "" : "（" + recordNo + "）")
              + "，旧货回寄："
              + (waiting > 0 ? waiting + " 行在等" : waived + " 行已免回"),
          membershipId);
    }
    return detail(id);
  }

  /**
   * 撤销换货新单：只在<b>新单仍是草稿且从未发货</b>时允许。
   *
   * <p>已提交的单要作废得走 9.2 审单的驳回，已发货的单要按真实退货处理 ——
   * 这两种情况一律 409，不假装能撤。
   *
   * <p>⚠️ 撤销时会一并把 11.3.2.1 的新发货需求置 {@code CANCELLED}：
   * 新单都不发了，需求还挂在那儿 PENDING，9.3.3.1 接手时就会为一件不该发的货建出库单 ——
   * 那正是「只发不收」的另一个入口。
   *
   * <p>⚠️ 替换货预占<b>不在这里释放</b>：预占的写入所有者是 warehouse，
   * 释放要走 11.3.2.1 的撤销授权。撤销新单后预占仍被圈着这件事必须让操作员看见，
   * 所以 {@code allowedActions} 与 detail 都会把它讲清楚。
   */
  @Transactional
  public ExchangeOrderDetail cancel(UUID id, ExchangeOrderCancelRequest req, UUID actorId, UUID membershipId) {
    UUID tenant = currentTenant();
    var link = lockLink(id);
    expectVersion(link, req == null ? null : req.expectedVersion());
    if ("CANCELLED".equals(link.get("closure_status", String.class))) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "换货新单已撤销过，不能重复撤销");
    }
    if (!"DRAFT".equals(link.get("new_order_status", String.class))) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "换货新单已提交进审单队列，不能在这里撤销：作废要走 9.2 审单的驳回动作");
    }
    long shipped =
        one(
                "select count(*) from shipment_record"
                    + " where tenant_id = ?::uuid and sales_order_id = ?::uuid",
                tenant,
                link.get("new_order_id", UUID.class))
            .get(0, Long.class);
    if (shipped > 0) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "换货新单已经有真实发货记录，不能撤销：已发出的货要按真实退货处理");
    }

    String reason = req == null || req.reason() == null ? "换货新单撤销" : req.reason();
    OffsetDateTime now = now();
    sql.execute(
        "update exchange_order_link set closure_status = 'CANCELLED', cancelled_by = ?::uuid,"
            + " cancelled_at = ?::timestamptz, cancel_reason = ?, version = version + 1,"
            + " updated_at = ?::timestamptz where tenant_id = ?::uuid and id = ?::uuid",
        actorId,
        now,
        reason,
        now,
        tenant,
        id);
    // 新单都不发了，发货需求必须一起取消（见方法注释）。
    sql.execute(
        "update exchange_outbound_demand set status = 'CANCELLED',"
            + " cancel_reason = ?, updated_at = ?::timestamptz"
            + " where tenant_id = ?::uuid and authorization_id = ?::uuid and status = 'PENDING'",
        "换货新单已撤销：" + reason,
        now,
        tenant,
        link.get("authorization_id", UUID.class));
    appendEvent(
        id,
        "CANCELLED",
        link.get("closure_status", String.class),
        "CANCELLED",
        "换货新单 " + link.get("new_order_no", String.class) + " 已撤销，发货需求同步取消。"
            + "替换货预占仍被仓库圈着（如需真正释放，请到 11.3.2.1 撤销这张换货授权）",
        membershipId);
    return detail(id);
  }

  // ================================================================ 内部

  /** 新单商品行的中间态：授权行 → 新单行。 */
  private record NewOrderLine(UUID authorizationLineId, UUID newOrderLineId, int lineNo, BigDecimal quantity) {}

  private Record lockLink(UUID id) {
    var link =
        one(
            "select id,link_no,authorization_id,new_order_id,new_order_no,new_order_status,"
                + "closure_status,version from exchange_order_link"
                + " where tenant_id = ?::uuid and id = ?::uuid for update",
            currentTenant(),
            id);
    if (link == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "换货新单不存在或不属于当前租户");
    }
    return link;
  }

  /** 乐观锁：两个人同时点同一个动作时，后到的那个必须被 409 拒掉。 */
  private void expectVersion(Record link, Integer expected) {
    if (expected == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "必须给 expectedVersion");
    }
    int version = link.get("version", Integer.class);
    if (expected != version) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "换货新单已被其他人改动（当前版本 "
              + version
              + "，你看到的是 "
              + expected
              + "），请刷新后重试");
    }
  }

  private void appendEvent(
      UUID linkId,
      String kind,
      String from,
      String to,
      String detail,
      UUID membershipId) {
    sql.execute(
        "insert into exchange_order_link_event(id,tenant_id,link_id,event_kind,"
            + "from_closure_status,to_closure_status,detail,actor_membership_id,occurred_at)"
            + " values(?::uuid,?::uuid,?::uuid,?,?,?,?,?::uuid,?::timestamptz)",
        UUID.randomUUID(),
        currentTenant(),
        linkId,
        kind,
        from,
        to,
        detail == null || detail.length() > 800 ? (detail == null ? "" : detail.substring(0, 800)) : detail,
        membershipId,
        now());
  }

  private static ExchangeOrderRow toRow(Record r) {
    return new ExchangeOrderRow(
        r.get("id", UUID.class),
        r.get("link_no", String.class),
        r.get("authorization_id", UUID.class),
        r.get("authorization_no", String.class),
        r.get("case_id", UUID.class),
        r.get("case_no", String.class),
        r.get("original_order_id", UUID.class),
        r.get("original_order_no", String.class),
        r.get("new_order_id", UUID.class),
        r.get("new_order_no", String.class),
        r.get("new_order_status", String.class),
        "SUBMITTED".equals(r.get("new_order_status", String.class)) ? "已提交待审" : "草稿",
        r.get("closure_status", String.class),
        closureLabel(r.get("closure_status", String.class)),
        r.get("currency", String.class),
        qty(r.get("total_quantity", BigDecimal.class)),
        r.get("replacement_sku_count", Integer.class),
        r.get("line_count", Integer.class),
        amount(r.get("new_order_amount", BigDecimal.class)),
        r.get("shipment_count", Integer.class),
        r.get("first_shipped_at", OffsetDateTime.class),
        r.get("shipment_record_no", String.class),
        r.get("last_tracking_status", String.class),
        r.get("old_goods_return_status", String.class),
        "WAIVED".equals(r.get("old_goods_return_status", String.class)) ? "已结清（免回）" : "等买家寄回",
        r.get("reason_note", String.class),
        r.get("created_by", UUID.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("submitted_at", OffsetDateTime.class),
        r.get("dispatched_at", OffsetDateTime.class),
        r.get("closed_at", OffsetDateTime.class),
        r.get("cancelled_by", UUID.class),
        r.get("cancelled_at", OffsetDateTime.class),
        r.get("cancel_reason", String.class),
        r.get("version", Integer.class));
  }

  private static String closureLabel(String s) {
    return switch (s == null ? "" : s) {
      case "DISPATCHED" -> "替换货已发出";
      case "CLOSED" -> "换货已闭环";
      case "CANCELLED" -> "已撤销";
      default -> "已建立（待提交履约）";
    };
  }

  private static String eventLabel(String kind) {
    return switch (kind == null ? "" : kind) {
      case "LINK_CREATED" -> "建立换货新单";
      case "SUBMITTED" -> "提交进审单队列";
      case "CLOSURE_SYNCED" -> "同步闭环状态";
      case "CANCELLED" -> "撤销换货新单";
      default -> kind;
    };
  }

  private static String decisionLabel(String d) {
    return "PARTIALLY_APPROVED".equals(d) ? "部分批准（库存不足）" : "整量批准";
  }

  private static String orEmpty(String s) {
    return s == null ? "" : s;
  }

  /** 数量一律 {@code BigDecimal} → 字符串，Java 侧全程禁浮点（AGENTS.md）。 */
  private static String qty(BigDecimal v) {
    return v == null ? "0.000000" : v.setScale(6, RoundingMode.HALF_UP).toPlainString();
  }

  /** 金额一律 {@code BigDecimal} → 字符串，scale 4（与 sales_order 的 numeric(19,4) 对齐）。 */
  private static String amount(BigDecimal v) {
    return v == null ? "0.0000" : v.setScale(4, RoundingMode.HALF_UP).toPlainString();
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now().truncatedTo(ChronoUnit.MILLIS);
  }

  private static boolean notBlank(String s) {
    return s != null && !s.isBlank();
  }

  private Object[] bindsWith(List<Object> binds, int size, int offset) {
    var all = new ArrayList<>(binds);
    all.add(size);
    all.add(offset);
    return all.toArray();
  }

  /**
   * 取单行，取不到就返回 {@code null}。
   *
   * <p>刻意不用 {@code sql.fetchOne(sql, Object.class, binds)}：jOOQ **没有**
   * {@code fetchOne(String, Class, Object...)} 这个重载，那个调用会退化成
   * {@code fetchOne(String, Object...)}，于是 {@code Object.class} 被当成**绑参**
   * 塞进 SQL —— 运行时报「Type class java.lang.Class is not supported」。
   */
  private Record one(String query, Object... binds) {
    var result = sql.fetch(query, binds);
    return result.isEmpty() ? null : result.get(0);
  }

  private UUID currentTenant() {
    return UUID.fromString(
        sql.fetchOne("select current_setting('app.current_tenant')").get(0, String.class));
  }

  private String nextLinkNo() {
    long n = sql.fetchOne("select nextval('exchange_order_link_no_seq')").get(0, Long.class);
    return "EXL-" + OffsetDateTime.now().toLocalDate().toString().replace("-", "")
        + String.format("%06d", n);
  }

  private String nextNewOrderNo() {
    // 新单号与换货新单号同源同发号器：本服务没有第二个发号器，
    // 多造一个序列只会让「号从哪来」这件事多一个答案（与 11.3.2.1 同一理由）。
    long n = sql.fetchOne("select nextval('exchange_order_link_no_seq')").get(0, Long.class);
    return "EXO-" + OffsetDateTime.now().toLocalDate().toString().replace("-", "")
        + String.format("%06d", n);
  }
}
