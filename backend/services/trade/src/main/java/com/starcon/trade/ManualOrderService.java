package com.starcon.trade;

import static com.starcon.trade.ManualOrderModels.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 9.1.2.2 手工订单。
 *
 * <p><b>来源是字段不是表。</b>本类写入的是<b>通用</b>销售订单 {@code sales_order}， {@code order_source='MANUAL'}；9.1.2.1
 * 平台订单同步（D5）将写入<b>同一张表</b>的 {@code order_source='AMAZON'/'SHOPIFY'/...}。这正是蓝图那句「手工单与平台单共用同一条履约链路，
 * 只有来源标记不同，避免两套流程」在代码里的落法 —— 9.3 履约、9.4 发货回传、13 销量 只需要认 {@code sales_order} 一张表，不需要知道这单是怎么来的。
 *
 * <p><b>本服务不引入任何价格来源。</b>05 价目系统（D6）未交付，因此单价是 {@link SalesOrderLineInput#unitPrice()}
 * 这个必填表单字段。刻意<b>不</b>引用不存在的 价目服务、<b>不</b>内置任何默认单价 —— 单价是表数据不是代码常量。
 *
 * <p><b>快照纪律。</b>行与地址的每一列都是写入那一刻的冻结取值，服务层 <b>不</b>回查 catalog / crm 的活数据。商品事后改名、改规格、改映射版本，
 * 或买家事后改地址，历史订单<b>一个字节都不会变</b>。
 *
 * <p><b>金额口径与 DB 触发器逐条对齐。</b>{@link Totals} 的公式与 V99 第 8.2 节 {@code guard_sales_order_totals}
 * 的公式是<b>同一条</b>，两边各算一次是刻意的： 服务层算错时提交会被 DB 挡下，而不是等到对账时才发现总额对不上。
 */
@Service
public class ManualOrderService {

  /** 数量刻度：与 DB 的 numeric(19,6) 逐字对齐。 */
  private static final int QTY_SCALE = 6;

  /** 金额刻度：与 DB 的 numeric(19,4) 逐字对齐。 */
  private static final int MONEY_SCALE = 4;

  private static final int MAX_PAGE_SIZE = 200;

  private final DSLContext sql;

  public ManualOrderService(DSLContext sql) {
    this.sql = sql;
  }

  // ================================================================ 内部结构

  /** 已按 DB 口径算好的行（含行小计与该行的负折扣）。 */
  private record Prepared(int lineNo, SalesOrderLineInput in, BigDecimal lineAmount) {}

  /** 订单头六项汇总，公式见类注释。 */
  private record Totals(
      BigDecimal subtotal,
      BigDecimal discount,
      BigDecimal tax,
      BigDecimal shipping,
      BigDecimal other,
      BigDecimal grandTotal) {}

  // ================================================================ 能力声明

  /**
   * 能力与上下游可用性。
   *
   * <p>存在的理由与 9.3.2.2 / 6.4.1.1 一致：把「哪些输入真有依据」「下游有没有接上」 显式报出来，而不是让调用方从 404 或空数组里去猜哪一环没通。
   */
  public SalesOrderCapabilities capabilities() {
    return new SalesOrderCapabilities(
        List.of(SalesOrderSource.values()),
        List.of(SalesOrderManualKind.values()),
        List.of(SalesOrderStatus.values()),
        List.of(SalesOrderPaymentMethod.values()),
        List.of(SalesOrderAddressRole.values()),
        List.of(SalesOrderChargeType.values()),
        List.of(
            "DRAFT → SUBMITTED（提交待审，9.2 审单的入口；提交时行 / 收货地址 / 客户快照 / 汇总金额" + "由 DB 延迟约束触发器复核并冻结）",
            "SUBMITTED 是本模块的终态：审单结论（通过 / 驳回）由 9.2 用「加值迁移」扩展，本模块不预留",
            "本模块**没有**取消：草稿的处置方式是「不提交」，它从未进过审单队列，不需要作废；" + "取消 / 作废属于 9.2 审单的驳回动作"),
        false,
        "05 价目系统（D6）未交付：本模块**不引用**任何价目服务、**不内置**任何默认单价，"
            + "单价是表单必填项并原样冻结进订单行。价目交付后由服务层取建议价填进表单，"
            + "表结构与校验都不需要改。",
        false,
        "手工单与平台单共用同一条履约链路：两者写的是同一张 sales_order，"
            + "只有 order_source 字段不同（MANUAL / AMAZON / SHOPIFY / ...）。9.3 履约、"
            + "9.4 发货回传、13 销量只认 sales_order 一张表，不需要知道这单是怎么来的 —— "
            + "这是蓝图「避免两套流程」的实现形式。",
        List.of(
            new SalesOrderDownstreamGap(
                "9.2.1.1",
                "待审队列",
                "trade",
                false,
                "9.2 审单与异常",
                "审单未交付：手工订单只能提交到 SUBMITTED（待审），" + "队列展示、批量通过/驳回与规则命中都还没有出口。"),
            new SalesOrderDownstreamGap(
                "9.2.1.2",
                "审单规则",
                "trade",
                false,
                "9.2 审单与异常",
                "审单规则未交付：当前没有任何自动放行/转人工的判定，" + "SUBMITTED 的订单一律等人工处理。"),
            new SalesOrderDownstreamGap(
                "9.1.2.1",
                "平台订单同步",
                "trade",
                false,
                "9.1.2 订单来源",
                "同组兄弟模块未交付：order_source 为平台来源的订单当前没有任何写入入口。"
                    + "本模块已把 sales_order 的形状做成通用表（来源是字段，CHECK 值域已含全部平台来源），"
                    + "平台单落地时直接写同一张表，不需要改表结构。"),
            new SalesOrderDownstreamGap(
                "9.3.1.1", "履约请求", "trade", false, "9.3 履约编排", "履约请求未交付：手工订单暂不进入拣货与发货链路。")));
  }

  // ================================================================ 列表

  /** 订单列表：真分页 + 与分页无关的全量汇总。 */
  public SalesOrderPage pageOrders(SalesOrderQuery query) {
    int page = Math.max(0, query.page());
    int size = Math.min(MAX_PAGE_SIZE, Math.max(1, query.size()));
    List<Object> binds = new ArrayList<>();
    List<String> where = new ArrayList<>();
    if (isSet(query.manualKind())) {
      where.add("o.manual_kind = ?");
      binds.add(manualKind(query.manualKind()).name());
    }
    if (isSet(query.status())) {
      where.add("o.status = ?");
      binds.add(status(query.status()).name());
    }
    if (isSet(query.paymentMethod())) {
      where.add("o.payment_method = ?");
      binds.add(paymentMethod(query.paymentMethod()).name());
    }
    if (query.customerPartyId() != null) {
      where.add("p.customer_party_id = ?");
      binds.add(query.customerPartyId());
    }
    if (isSet(query.keyword())) {
      // 真实模糊检索：命中内部单号、外部单号或客户名。
      // 刻意**不含金额/日期的模糊匹配** —— 那会让「搜 100」返回一堆无关订单。
      where.add("(o.order_no ilike ? or o.external_order_no ilike ? or p.customer_name ilike ?)");
      String pattern = "%" + query.keyword().trim() + "%";
      binds.add(pattern);
      binds.add(pattern);
      binds.add(pattern);
    }
    String clause = where.isEmpty() ? "" : " where " + String.join(" and ", where);
    long total =
        sql.fetchOne(
                "select count(*) from sales_order o"
                    + " join sales_order_party_snapshot p"
                    + " on p.tenant_id=o.tenant_id and p.order_id=o.id"
                    + clause,
                binds.toArray())
            .get(0, Long.class);
    Record summary =
        sql.fetchOne(
            "select count(*) filter (where status='DRAFT') as draft,"
                + " count(*) filter (where status='SUBMITTED') as submitted,"
                + " coalesce(sum(grand_total_amount),0) as grand_total"
                + " from sales_order");
    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(size);
    pageBinds.add(page * size);
    List<SalesOrderRow> items =
        sql.fetch(
                "select o.id,o.order_no,o.order_source,o.manual_kind,o.external_order_no,o.currency,"
                    + "o.status,o.payment_method,o.payment_terms_days,o.submitted_at,o.created_at,"
                    + "o.version,o.grand_total_amount,"
                    + "p.customer_party_id,p.customer_name,"
                    + " (select count(*) from sales_order_line l where l.order_id=o.id) as line_count"
                    + " from sales_order o"
                    + " join sales_order_party_snapshot p"
                    + " on p.tenant_id=o.tenant_id and p.order_id=o.id"
                    + clause
                    + " order by o.created_at desc, o.id desc limit ? offset ?",
                pageBinds.toArray())
            .map(ManualOrderService::orderRow);
    return new SalesOrderPage(
        items,
        page,
        size,
        total,
        summary.get("draft", Long.class),
        summary.get("submitted", Long.class),
        summary.get("grand_total", BigDecimal.class));
  }

  // ================================================================ 详情

  /** 订单详情：一次调用返回整屏。跨租户在 RLS 下查不到 → 404。 */
  public SalesOrderDetail order(UUID orderId) {
    Record head = headWithCustomer(orderId);
    if (head == null) {
      throw notFound("订单 " + orderId + " 不存在或无权查看");
    }
    Record party =
        sql.fetchOne(
            "select customer_party_id,customer_code,customer_name,"
                + " customer_type,customer_default_currency from sales_order_party_snapshot"
                + " where order_id = ?",
            orderId);
    SalesOrderCustomerRow customer =
        party == null
            ? null
            : new SalesOrderCustomerRow(
                party.get("customer_party_id", UUID.class),
                party.get("customer_code", String.class),
                party.get("customer_name", String.class),
                SalesOrderCustomerType.valueOf(party.get("customer_type", String.class)),
                blankToNull(party.get("customer_default_currency", String.class)));

    Map<UUID, List<SalesOrderAttributeRow>> attributesByLine = new LinkedHashMap<>();
    for (Record r :
        sql.fetch(
            "select a.id,a.line_id,a.attribute_name,a.attribute_value,a.attribute_unit,a.source_version"
                + " from order_line_attribute_snapshot a join sales_order_line l on l.id=a.line_id"
                + " where l.order_id = ? order by l.line_no, a.attribute_name",
            orderId)) {
      attributesByLine
          .computeIfAbsent(r.get("line_id", UUID.class), k -> new ArrayList<>())
          .add(
              new SalesOrderAttributeRow(
                  r.get("id", UUID.class),
                  r.get("attribute_name", String.class),
                  r.get("attribute_value", String.class),
                  r.get("attribute_unit", String.class),
                  r.get("source_version", String.class)));
    }

    List<SalesOrderLineRow> lines =
        sql.fetch(
                "select id,line_no,sku_id,sku_code,sku_name,spec_snapshot,sku_mapping_version,"
                    + "quantity,unit_price,discount_amount,tax_amount,line_amount,note,created_at"
                    + " from sales_order_line where order_id = ? order by line_no",
                orderId)
            .map(
                r -> {
                  UUID id = r.get("id", UUID.class);
                  return new SalesOrderLineRow(
                      id,
                      r.get("line_no", Integer.class),
                      r.get("sku_id", UUID.class),
                      r.get("sku_code", String.class),
                      r.get("sku_name", String.class),
                      r.get("spec_snapshot", String.class),
                      r.get("sku_mapping_version", String.class),
                      r.get("quantity", BigDecimal.class),
                      r.get("unit_price", BigDecimal.class),
                      r.get("discount_amount", BigDecimal.class),
                      r.get("tax_amount", BigDecimal.class),
                      r.get("line_amount", BigDecimal.class),
                      r.get("note", String.class),
                      attributesByLine.getOrDefault(id, List.of()),
                      r.get("created_at", OffsetDateTime.class));
                });

    List<SalesOrderAddressRow> addresses =
        sql.fetch(
                "select id,address_role,recipient_name,recipient_phone,recipient_email,company_name,"
                    + "country_code,province,city,district,postcode,address_line1,address_line2,"
                    + "tax_id,note,created_at from order_address_snapshot"
                    + " where order_id = ? order by address_role",
                orderId)
            .map(
                r ->
                    new SalesOrderAddressRow(
                        r.get("id", UUID.class),
                        SalesOrderAddressRole.valueOf(r.get("address_role", String.class)),
                        r.get("recipient_name", String.class),
                        r.get("recipient_phone", String.class),
                        r.get("recipient_email", String.class),
                        r.get("company_name", String.class),
                        r.get("country_code", String.class),
                        r.get("province", String.class),
                        r.get("city", String.class),
                        r.get("district", String.class),
                        r.get("postcode", String.class),
                        r.get("address_line1", String.class),
                        r.get("address_line2", String.class),
                        r.get("tax_id", String.class),
                        r.get("note", String.class),
                        r.get("created_at", OffsetDateTime.class)));

    List<SalesOrderChargeRow> charges =
        sql.fetch(
                "select id,charge_type,label,amount,charge_kind,note,created_at from order_charge"
                    + " where order_id = ? order by charge_type, id",
                orderId)
            .map(
                r ->
                    new SalesOrderChargeRow(
                        r.get("id", UUID.class),
                        SalesOrderChargeType.valueOf(r.get("charge_type", String.class)),
                        r.get("label", String.class),
                        r.get("amount", BigDecimal.class),
                        SalesOrderChargeLevel.valueOf(r.get("charge_kind", String.class)),
                        r.get("note", String.class),
                        r.get("created_at", OffsetDateTime.class)));

    // 事件流按 (occurred_at, id) 升序：id 兜底是必要的，同一毫秒发生的事件
    // 光看 occurred_at 顺序不确定，界面上会随机跳行（与 8.3.1.2 同一理由）。
    List<SalesOrderEventRow> events =
        sql.fetch(
                "select id,event_type,from_status,to_status,reason,actor_id,occurred_at"
                    + " from sales_order_status_history where order_id = ?"
                    + " order by occurred_at, id",
                orderId)
            .map(
                r ->
                    new SalesOrderEventRow(
                        r.get("id", UUID.class),
                        SalesOrderEventType.valueOf(r.get("event_type", String.class)),
                        r.get("from_status", String.class),
                        r.get("to_status", String.class),
                        r.get("reason", String.class),
                        r.get("actor_id", UUID.class),
                        r.get("occurred_at", OffsetDateTime.class)));

    return new SalesOrderDetail(orderRow(head), customer, lines, addresses, charges, events);
  }

  // ================================================================ 创建

  /**
   * 创建手工销售订单。
   *
   * <p>整个单头 + 客户快照 + 行 + 属性 + 地址 + 费用 + 首条事件在<b>一个事务</b>里写入： 半张单（只有头没有行）在业务上不是一张订单。这里算好全部汇总与行小计，让
   * V99 的延迟 约束触发器在提交时成为兜底防线而不是唯一防线。
   */
  @Transactional
  public SalesOrderSaved create(SalesOrderCreateInput input, AccessService.Access actor) {
    UUID tenantId = currentTenant();
    UUID orderId = UUID.randomUUID();
    List<Prepared> prepared = prepare(input.lines());
    Totals totals = totals(prepared, input.charges());

    try {
      sql.execute(
          "insert into sales_order(id,tenant_id,order_no,order_source,manual_kind,external_order_no,"
              + "currency,payment_method,payment_terms_days,status,subtotal_amount,discount_amount,"
              + "tax_amount,shipping_amount,other_amount,grand_total_amount,note,version)"
              + " values(?,?,?,'MANUAL',?,?,?,?,?, 'DRAFT',?,?,?,?,?,?,?,0)",
          orderId,
          tenantId,
          input.orderNo().trim(),
          input.manualKind().name(),
          text(input.externalOrderNo()),
          input.currency(),
          input.paymentMethod().name(),
          termsDays(input.paymentMethod(), input.paymentTermsDays()),
          totals.subtotal(),
          totals.discount(),
          totals.tax(),
          totals.shipping(),
          totals.other(),
          totals.grandTotal(),
          text(input.note()));
    } catch (DuplicateKeyException e) {
      // 内部单号撞了，或（带来源维的）外部单号撞了 —— 两种都是「同一个号两张单」，
      // 对账时无法回答「客户说的那个单是哪张」，所以明确报冲突而不是让 DB 错误冒成 500。
      throw new ResponseStatusException(HttpStatus.CONFLICT, "订单号已存在，或本来源下的外部订单号已存在：同租户同单号只能有一张单");
    }

    writeCustomer(tenantId, orderId, input.customer());
    writeLines(tenantId, orderId, prepared);
    writeAddresses(tenantId, orderId, input.addresses());
    writeCharges(tenantId, orderId, input.charges());
    writeEvent(
        tenantId,
        orderId,
        SalesOrderEventType.CREATED,
        "",
        SalesOrderStatus.DRAFT.name(),
        "手工录入创建",
        actor.membershipId(),
        OffsetDateTime.now());

    return new SalesOrderSaved(
        orderId,
        input.orderNo().trim(),
        SalesOrderStatus.DRAFT,
        totals.grandTotal(),
        "手工订单已创建（来源 MANUAL）；提交后进入 9.2 审单，明细与金额随即冻结");
  }

  // ================================================================ 更新草稿

  /** 更新草稿：客户 / 行 / 属性 / 地址 / 费用整体替换。提交后由 DB 触发器冻结，不许再改。 */
  @Transactional
  public SalesOrderSaved update(
      UUID orderId, SalesOrderUpdateInput input, AccessService.Access actor) {
    UUID tenantId = currentTenant();
    Record current = sql.fetchOne("select status,version from sales_order where id = ?", orderId);
    if (current == null) {
      throw notFound("订单 " + orderId + " 不存在或无权修改");
    }
    if (!SalesOrderStatus.DRAFT.name().equals(current.get("status", String.class))) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "订单已提交进审单队列，明细与金额已冻结，不能再改：" + "订单上的商品、地址、客户都是下单当时的快照，改了等于让「当时那笔交易是什么」" + "在历史里出现两个答案");
    }

    List<Prepared> prepared = prepare(input.lines());
    Totals totals = totals(prepared, input.charges());

    sql.execute(
        "update sales_order set manual_kind = ?, currency = ?, payment_method = ?,"
            + " payment_terms_days = ?, note = ?, subtotal_amount = ?, discount_amount = ?,"
            + " tax_amount = ?, shipping_amount = ?, other_amount = ?, grand_total_amount = ?,"
            + " version = version + 1, updated_at = now() where id = ?",
        input.manualKind().name(),
        input.currency(),
        input.paymentMethod().name(),
        termsDays(input.paymentMethod(), input.paymentTermsDays()),
        text(input.note()),
        totals.subtotal(),
        totals.discount(),
        totals.tax(),
        totals.shipping(),
        totals.other(),
        totals.grandTotal(),
        orderId);

    // 子表整体替换。顺序刻意是「先删后插」，且删除都在 DRAFT 下发生 ——
    // V99 的冻结触发器只拦 SUBMITTED，草稿可以自由重排。
    sql.execute("delete from sales_order_party_snapshot where order_id = ?", orderId);
    sql.execute("delete from sales_order_line where order_id = ?", orderId);
    sql.execute("delete from order_address_snapshot where order_id = ?", orderId);
    sql.execute("delete from order_charge where order_id = ?", orderId);
    writeCustomer(tenantId, orderId, input.customer());
    writeLines(tenantId, orderId, prepared);
    writeAddresses(tenantId, orderId, input.addresses());
    writeCharges(tenantId, orderId, input.charges());

    return new SalesOrderSaved(
        orderId,
        sql.fetchOne("select order_no from sales_order where id = ?", orderId)
            .get("order_no", String.class),
        SalesOrderStatus.DRAFT,
        totals.grandTotal(),
        "草稿已更新");
  }

  // ================================================================ 提交待审

  /**
   * 提交待审：DRAFT → SUBMITTED，9.2 审单的入口。
   *
   * <p>提交时的四道闸（行 ≥ 1、有收货地址快照、有客户快照、五项汇总与明细相等）由 V99 的 <b>延迟约束触发器</b>在提交时回表核对。之所以是「延迟」而不是当场：服务层的写入顺序是
   * 「先插头 → 写行/地址/费用 → 提交」，当场算会把「还没写完」误判成「不完整」。
   *
   * <p>所以本方法<b>刻意与建单分开</b>成独立事务：提交一旦发生，行/地址/费用立刻被冻结， 与建单放在同一个事务里会让「刚建的单」在还没确认前就被锁死。
   */
  @Transactional
  public SalesOrderSaved submit(UUID orderId, AccessService.Access actor) {
    UUID tenantId = currentTenant();
    Record current = sql.fetchOne("select status,order_no from sales_order where id = ?", orderId);
    if (current == null) {
      throw notFound("订单 " + orderId + " 不存在或无权提交");
    }
    if (!SalesOrderStatus.DRAFT.name().equals(current.get("status", String.class))) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "只有草稿能提交待审；本单当前不在草稿态");
    }
    OffsetDateTime now = OffsetDateTime.now();
    sql.execute(
        "update sales_order set status = 'SUBMITTED', submitted_at = ?::timestamptz,"
            + " version = version + 1, updated_at = now() where id = ?",
        now,
        orderId);
    writeEvent(
        tenantId,
        orderId,
        SalesOrderEventType.SUBMITTED,
        SalesOrderStatus.DRAFT.name(),
        SalesOrderStatus.SUBMITTED.name(),
        "提交待审（9.2 审单入口）",
        actor.membershipId(),
        now);
    return new SalesOrderSaved(
        orderId,
        current.get("order_no", String.class),
        SalesOrderStatus.SUBMITTED,
        sql.fetchOne("select grand_total_amount from sales_order where id = ?", orderId)
            .get("grand_total_amount", BigDecimal.class),
        "已提交待审：明细与金额已冻结，进入 9.2 审单队列");
  }

  // ================================================================ 写入子表

  private void writeCustomer(UUID tenantId, UUID orderId, SalesOrderCustomerInput c) {
    sql.execute(
        "insert into sales_order_party_snapshot(id,tenant_id,order_id,customer_party_id,"
            + "customer_code,customer_name,customer_type,customer_default_currency)"
            + " values(?,?,?,?,?,?,?,?)",
        UUID.randomUUID(),
        tenantId,
        orderId,
        c.customerPartyId(),
        c.customerCode().trim(),
        c.customerName().trim(),
        c.customerType().name(),
        blankToNull(c.customerDefaultCurrency()));
  }

  private void writeLines(UUID tenantId, UUID orderId, List<Prepared> prepared) {
    for (Prepared p : prepared) {
      UUID lineId = UUID.randomUUID();
      SalesOrderLineInput in = p.in();
      sql.execute(
          "insert into sales_order_line(id,tenant_id,order_id,line_no,sku_id,sku_code,sku_name,"
              + "spec_snapshot,sku_mapping_version,quantity,unit_price,discount_amount,tax_amount,"
              + "line_amount,note) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
          lineId,
          tenantId,
          orderId,
          p.lineNo(),
          in.skuId(),
          in.skuCode().trim(),
          in.skuName().trim(),
          text(in.specSnapshot()),
          text(in.skuMappingVersion()),
          in.quantity().setScale(QTY_SCALE, RoundingMode.HALF_UP),
          in.unitPrice().setScale(MONEY_SCALE, RoundingMode.HALF_UP),
          money(in.discountAmount()),
          money(in.taxAmount()),
          p.lineAmount(),
          text(in.note()));
      if (in.attributes() == null) {
        continue;
      }
      for (SalesOrderAttributeInput a : in.attributes()) {
        sql.execute(
            "insert into order_line_attribute_snapshot(id,tenant_id,order_id,line_id,"
                + "attribute_name,attribute_value,attribute_unit,source_version)"
                + " values(?,?,?,?,?,?,?,?)",
            UUID.randomUUID(),
            tenantId,
            orderId,
            lineId,
            a.attributeName().trim(),
            text(a.attributeValue()),
            text(a.attributeUnit()),
            text(a.sourceVersion()));
      }
    }
  }

  private void writeAddresses(UUID tenantId, UUID orderId, List<SalesOrderAddressInput> addresses) {
    if (addresses == null) {
      return;
    }
    for (SalesOrderAddressInput a : addresses) {
      sql.execute(
          "insert into order_address_snapshot(id,tenant_id,order_id,address_role,recipient_name,"
              + "recipient_phone,recipient_email,company_name,country_code,province,city,district,"
              + "postcode,address_line1,address_line2,tax_id,note)"
              + " values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
          UUID.randomUUID(),
          tenantId,
          orderId,
          a.addressRole().name(),
          a.recipientName().trim(),
          text(a.recipientPhone()),
          text(a.recipientEmail()),
          text(a.companyName()),
          a.countryCode(),
          text(a.province()),
          text(a.city()),
          text(a.district()),
          text(a.postcode()),
          a.addressLine1().trim(),
          text(a.addressLine2()),
          text(a.taxId()),
          text(a.note()));
    }
  }

  private void writeCharges(UUID tenantId, UUID orderId, List<SalesOrderChargeInput> charges) {
    if (charges == null) {
      return;
    }
    for (SalesOrderChargeInput c : charges) {
      sql.execute(
          "insert into order_charge(id,tenant_id,order_id,charge_type,label,amount,charge_kind,note)"
              + " values(?,?,?,?,?,?,?,?)",
          UUID.randomUUID(),
          tenantId,
          orderId,
          c.chargeType().name(),
          text(c.label()),
          c.amount().setScale(MONEY_SCALE, RoundingMode.HALF_UP),
          c.chargeLevel().name(),
          text(c.note()));
    }
  }

  private void writeEvent(
      UUID tenantId,
      UUID orderId,
      SalesOrderEventType eventType,
      String fromStatus,
      String toStatus,
      String reason,
      UUID actorId,
      OffsetDateTime occurredAt) {
    sql.execute(
        "insert into sales_order_status_history(id,tenant_id,order_id,event_type,from_status,"
            + "to_status,reason,actor_id,occurred_at) values(?,?,?,?,?,?,?,?,?::timestamptz)",
        UUID.randomUUID(),
        tenantId,
        orderId,
        eventType.name(),
        fromStatus,
        toStatus,
        reason,
        actorId,
        occurredAt);
  }

  // ================================================================ 计算

  /**
   * 行预处理：定行号、算行小计。
   *
   * <p>行小计口径 {@code round(unitPrice × quantity, 4)} 与 DB 的 {@code sales_order_line_amount_matches}
   * CHECK <b>同一条</b>：服务层算一次、DB 复算一次， 服务层算错时提交会在这里被挡下，而不是等到对账时才发现总额对不上。
   */
  private static List<Prepared> prepare(List<SalesOrderLineInput> lines) {
    List<Prepared> out = new ArrayList<>();
    int no = 0;
    for (SalesOrderLineInput in : lines) {
      no++;
      BigDecimal lineAmount =
          in.unitPrice().multiply(in.quantity()).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
      out.add(new Prepared(no, in, lineAmount));
    }
    return out;
  }

  /**
   * 订单头汇总。公式与 V99 {@code guard_sales_order_totals} 逐条一致：
   *
   * <pre>
   *   小计 = Σ 行小计
   *   折扣 = −Σ 行折扣（行折扣恒为负）− Σ DISCOUNT 费用（费用折扣恒为负）→ 取正
   *   税额 = Σ 行税 + Σ TAX 费用
   *   运费 = Σ SHIPPING 费用
   *   其他 = Σ PACKAGING / OTHER 费用
   *   合计 = 小计 − 折扣 + 税额 + 运费 + 其他
   * </pre>
   */
  private static Totals totals(List<Prepared> prepared, List<SalesOrderChargeInput> charges) {
    BigDecimal subtotal = BigDecimal.ZERO;
    BigDecimal lineDiscount = BigDecimal.ZERO;
    BigDecimal lineTax = BigDecimal.ZERO;
    for (Prepared p : prepared) {
      subtotal = subtotal.add(p.lineAmount());
      lineDiscount = lineDiscount.add(money(p.in().discountAmount()));
      lineTax = lineTax.add(money(p.in().taxAmount()));
    }
    BigDecimal shipping = BigDecimal.ZERO;
    BigDecimal chargeDiscount = BigDecimal.ZERO;
    BigDecimal chargeTax = BigDecimal.ZERO;
    BigDecimal other = BigDecimal.ZERO;
    if (charges != null) {
      for (SalesOrderChargeInput c : charges) {
        BigDecimal amt = c.amount();
        switch (c.chargeType()) {
          case SHIPPING -> shipping = shipping.add(amt);
          case DISCOUNT -> chargeDiscount = chargeDiscount.add(amt);
          case TAX -> chargeTax = chargeTax.add(amt);
          case PACKAGING, OTHER -> other = other.add(amt);
        }
      }
    }
    BigDecimal discount = lineDiscount.negate().add(chargeDiscount.negate());
    BigDecimal tax = lineTax.add(chargeTax);
    BigDecimal grand = subtotal.subtract(discount).add(tax).add(shipping).add(other);
    return new Totals(
        scale(subtotal), scale(discount), scale(tax), scale(shipping), scale(other), scale(grand));
  }

  // ================================================================ 工具

  private Record headWithCustomer(UUID orderId) {
    return sql.fetchOne(
        "select o.id,o.order_no,o.order_source,o.manual_kind,o.external_order_no,o.currency,"
            + "o.status,o.payment_method,o.payment_terms_days,o.submitted_at,o.created_at,"
            + "o.version,o.grand_total_amount,"
            + "p.customer_party_id,p.customer_name,"
            + " (select count(*) from sales_order_line l where l.order_id=o.id) as line_count"
            + " from sales_order o join sales_order_party_snapshot p"
            + " on p.tenant_id=o.tenant_id and p.order_id=o.id where o.id = ?",
        orderId);
  }

  private static SalesOrderRow orderRow(Record r) {
    return new SalesOrderRow(
        r.get("id", UUID.class),
        r.get("order_no", String.class),
        SalesOrderSource.valueOf(r.get("order_source", String.class)),
        SalesOrderManualKind.valueOf(r.get("manual_kind", String.class)),
        blankToNull(r.get("external_order_no", String.class)),
        r.get("currency", String.class),
        SalesOrderStatus.valueOf(r.get("status", String.class)),
        SalesOrderPaymentMethod.valueOf(r.get("payment_method", String.class)),
        r.get("payment_terms_days", Integer.class),
        r.get("customer_party_id", UUID.class).toString(),
        r.get("customer_name", String.class),
        r.get("line_count", Integer.class),
        r.get("grand_total_amount", BigDecimal.class),
        r.get("submitted_at", OffsetDateTime.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("version", Long.class));
  }

  /** 租户来自事务内的绑定值，**不是**任何请求头。 */
  private UUID currentTenant() {
    return sql.fetchOne("select nullif(current_setting('app.current_tenant',true),'')::uuid")
        .get(0, UUID.class);
  }

  /** 账期与非账期必须自洽：非账期不许带天数（V99 的同名 CHECK 在 DB 侧再拦一次）。 */
  private static int termsDays(SalesOrderPaymentMethod method, Integer days) {
    int d = days == null ? 0 : days;
    return method == SalesOrderPaymentMethod.CREDIT_TERM ? d : 0;
  }

  private static BigDecimal money(BigDecimal v) {
    return v == null
        ? BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP)
        : v.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
  }

  private static BigDecimal scale(BigDecimal v) {
    return v.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
  }

  /** 可空文本统一落成 {@code ''}：V99 的这些列都是 NOT NULL DEFAULT ''，不是 NULL。 */
  private static String text(String s) {
    return s == null ? "" : s.trim();
  }

  private static String blankToNull(String s) {
    return s == null || s.isBlank() ? null : s;
  }

  private static boolean isSet(String s) {
    return s != null && !s.isBlank();
  }

  private static SalesOrderManualKind manualKind(String v) {
    try {
      return SalesOrderManualKind.valueOf(v.trim());
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "手工单子类不合法：" + v);
    }
  }

  private static SalesOrderStatus status(String v) {
    try {
      return SalesOrderStatus.valueOf(v.trim());
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "订单状态不合法：" + v);
    }
  }

  private static SalesOrderPaymentMethod paymentMethod(String v) {
    try {
      return SalesOrderPaymentMethod.valueOf(v.trim());
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "支付方式不合法：" + v);
    }
  }

  private static ResponseStatusException notFound(String message) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
  }
}
