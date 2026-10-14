package com.starcon.trade;

import static com.starcon.trade.B2BOrderModels.*;

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
 * 9.7.1.1 批发订单与账期。
 *
 * <p><b>「未发货不产生应收，发货才确认应收」是本类的中心规则</b>，且它有三重表达， 任何一重单独存在都不够：
 *
 * <ol>
 *   <li><b>结构</b>：{@code b2b_receivable.shipment_id} 是 NOT NULL 外键。
 *       应收行在数据层<b>挂不到发货单就写不出来</b>，因此「下单即生成应收」不是一条 没写的代码路径，而是一条走不通的路径。
 *   <li><b>数值</b>：{@code b2b_order.receivable_amount ≤ b2b_order.total_amount} 是 CHECK，
 *       且订单头的这个数由触发器按 {@code SUM(应收)} 复算。
 *   <li><b>算术</b>：本类只在 {@link #ship} 这一个方法里产生应收。其它写方法里没有第二条路径。
 * </ol>
 *
 * <p><b>阶梯价是算出来的，不是填进来的。</b>{@link B2BOrderLineInput} 没有 unitPrice 字段： 服务层按 {@code min_quantity ≤
 * quantity} 取最高档，DB 的 {@code guard_b2b_line_tier} 独立复算一遍落档与单价是否真的属于那一档 —— 算错时提交被挡下，而不是等到对账时才发现
 * 「这批货到底按哪一档算的钱」没人答得上来。
 *
 * <p><b>不跨库读上游。</b>客户分级与账期取自 {@link #upsertCustomerTerms} 维护的 trade 侧只读投影，阶梯价取自 {@link
 * #upsertPriceBook} 维护的 trade 侧投影。 一次 catalog / 价目服务的回查都不发生（集成测试里刻意不提供这两个服务的 stub： 测试里出现 catalog
 * 客户端反而说明边界纪律破了）。
 */
@Service
public class B2BOrderService {

  /** 数量刻度：与 DB 的 numeric(19,6) 逐字对齐。 */
  private static final int QTY_SCALE = 6;

  /** 金额刻度：与 DB 的 numeric(24,4) 逐字对齐。 */
  private static final int MONEY_SCALE = 4;

  private static final int MAX_PAGE_SIZE = 200;

  private final DSLContext sql;

  public B2BOrderService(DSLContext sql) {
    this.sql = sql;
  }

  // ================================================================ 内部结构

  /**
   * 已落档的行：命中了哪一档、单价多少、起订量多少、行小计多少。
   *
   * @param tierId 命中的档位 id（冻结进订单行，事后价目改档也能回答「当时按哪档算的」）
   * @param tierMin 命中的档位下界（含）
   * @param lowestNext 下一档的下界与单价（null 表示已是最高档），只用于给界面提示
   */
  private record Priced(
      int lineNo,
      B2BOrderLineInput in,
      UUID priceBookId,
      UUID tierId,
      BigDecimal tierMin,
      BigDecimal unitPrice,
      BigDecimal moq,
      BigDecimal lineAmount,
      BigDecimal nextMin,
      BigDecimal nextPrice) {}

  // ================================================================ 能力声明

  /**
   * 能力与上下游可用性。
   *
   * <p>存在的理由与 9.1.2.2 / 7.7.2.1 一致：把「哪些输入真有依据」「下游有没有接上」 显式报出来，而不是让调用方从 404 或空数组里猜哪一环没通。
   */
  public B2BCapabilities capabilities() {
    return new B2BCapabilities(
        List.of(B2BOrderType.values()),
        List.of(B2BPaymentMethod.values()),
        List.of(B2BCostBasis.values()),
        List.of(B2BStatus.values()),
        List.of(B2BCustomerLevel.values()),
        List.of(B2BInvoiceState.values()),
        List.of(
            "**与零售的三重区分是结构性的**（不同的表，不是同一个表上的一个标记）："
                + "订单类型 order_type / 收款方式 payment_method / 成本口径 cost_basis "
                + "三者都写在 b2b_order 上，零售走 sales_order，两边互不覆盖、互不 JOIN。",
            "**起订量是硬校验**：数量低于价目表的 moq_quantity 直接 400，落库侧还有 "
                + "b2b_order_line 的 CHECK 与订单确认时的延迟约束触发器各拦一次。",
            "**阶梯价按数量落档**：区间是 (上一档下界, 本档下界]，数量恰好等于某档下界时落在该档；" + "单价不接受调用方传值，由系统落档算出并冻结进订单行。",
            "**未发货不产生应收，发货才确认**：应收必须挂在一张发货单上（NOT NULL 外键），" + "下单 / 确认两个动作都不产生任何应收行。",
            "**账期结算不即时收款**：ORDER_SETTLEMENT 下单结算（款项已收，不产生应收）、"
                + "ON_RECEIPT 确认收货后结算、CREDIT_TERM 账期（到期日 = 发货确认时刻 + 账期天数）。",
            "**开票需求只标记不跨库**：invoice_state 由本服务维护，12.6.2 发票交付后由它读这张表。",
            "金额一律 BigDecimal + NUMERIC：数量 numeric(19,6)、单价 numeric(19,4)、"
                + "金额 numeric(24,4)，全链路无浮点。"),
        true,
        "3.6.1.2 客户档案（catalog V27/V30）已交付。本服务持有它的只读投影 b2b_customer_terms，"
            + "由事件同步写入（PUT /customer-terms，幂等 + 版本单调）。"
            + "不跨库 JOIN、不建跨服务 FK：客户改名或改分级只改投影，"
            + "已下单订单用的是当时快照（customer_level 冻结在订单头）。",
        false,
        "05 价目 / 阶梯价（5.1.1.2，D6 未交付）：b2b_price_book / b2b_price_tier 目前由本服务的"
            + "PUT /price-books 端点维护，source_system='projection'。"
            + "05 价目交付后由事件同步接管同两张表，表结构与所有下游读法都不需要改。",
        false,
        "12.1.1 应收 / 12.6.2 发票未交付：本模块在 trade 侧产出 b2b_receivable 作为应收事实，"
            + "并用 invoice_state 表达开票需求。应收的**核销**（SETTLED）属于 12 财务的写入所有权，"
            + "本模块刻意不提供核销端点 —— 抢在别的服务前写它的状态会让「谁改了应收」无法回答。",
        false,
        "9.3 履约链路当前只认 sales_order 一张表，b2b_order 不在其中，"
            + "因此本模块的批发订单**尚未进入**拣货与发货编排。"
            + "交接方式：9.3.1.1 交付后由它读 b2b_order（两表同在 trade 服务同库，不是跨库读取）。",
        List.of(
            new B2BDownstreamGap(
                "12.1.1",
                "应收",
                "finance",
                false,
                "12.1 应收与资金",
                "应收未交付：b2b_receivable 目前是 trade 侧的应收事实，" + "财务总账与账龄分析还没有出口；核销动作本模块不提供。"),
            new B2BDownstreamGap(
                "12.6.2",
                "发票",
                "finance",
                false,
                "12.6 发票",
                "发票未交付：开票需求目前只落在 invoice_state 上，"
                    + "没有生成发票的入口；12.6.2 交付后由它消费 invoice_state='REQUESTED'。"),
            new B2BDownstreamGap(
                "12.1.2.2",
                "资金计划",
                "finance",
                false,
                "12.1 应收与资金",
                "资金计划未交付：B2B 与零售的现金流方向相反"
                    + "（B2B 先货后款、零售先款后货），资金计划必须分开建模，"
                    + "目前只能按 b2b_receivable 的 due_date 自己排。"),
            new B2BDownstreamGap(
                "9.3.1.1",
                "履约请求",
                "trade",
                false,
                "9.3 履约编排",
                "履约未接入：确认后的 b2b_order 不会自动进入拣货与发货编排，" + "发货由本模块自己的 POST /{id}/shipments 端点登记。"),
            new B2BDownstreamGap(
                "5.1.1.2",
                "价目项批量维护",
                "pricing",
                false,
                "05 价目",
                "阶梯价上游未交付：当前由本服务的投影端点维护，" + "交付后由事件同步接管 b2b_price_book / b2b_price_tier。")));
  }

  // ================================================================ 上游投影：客户

  /**
   * 客户档案投影 upsert（3.6.1.2 → b2b_customer_terms）。
   *
   * <p>幂等键是 customerPartyId；版本号必须单调不减（DB 触发器再拦一次， 因为乱序到达的事件是真实存在的，不该靠调用方保证顺序）。
   */
  @Transactional
  public B2BCustomerTermsRow upsertCustomerTerms(B2BCustomerTermsInput in) {
    UUID tenantId = currentTenant();
    UUID id = UUID.randomUUID();
    long version = in.sourceVersion() == null ? 0L : in.sourceVersion();
    sql.execute(
        "insert into b2b_customer_terms(id,tenant_id,customer_party_id,customer_code,"
            + "customer_name,customer_level,payment_terms_days,default_currency,credit_limit,"
            + "source_version,synced_at)"
            + " values(?,?,?,?,?,?,?,?,?,?,?::timestamptz)"
            + " on conflict(tenant_id,customer_party_id) do update set"
            + " customer_code=excluded.customer_code, customer_name=excluded.customer_name,"
            + " customer_level=excluded.customer_level,"
            + " payment_terms_days=excluded.payment_terms_days,"
            + " default_currency=excluded.default_currency, credit_limit=excluded.credit_limit,"
            + " source_version=excluded.source_version, synced_at=excluded.synced_at"
            + " where excluded.source_version >= b2b_customer_terms.source_version",
        id,
        tenantId,
        in.customerPartyId(),
        text(in.customerCode()),
        text(in.customerName()),
        in.customerLevel().name(),
        in.paymentTermsDays() == null ? 0 : in.paymentTermsDays(),
        blankToNull(in.defaultCurrency()),
        in.creditLimit(),
        version,
        OffsetDateTime.now());
    return customerTerms(in.customerPartyId());
  }

  /** 客户投影详情。 */
  public B2BCustomerTermsRow customerTerms(UUID customerPartyId) {
    Record r =
        sql.fetchOne(
            "select id,customer_party_id,customer_code,customer_name,customer_level,"
                + "payment_terms_days,default_currency,credit_limit,source_version,synced_at"
                + " from b2b_customer_terms where customer_party_id = ?",
            customerPartyId);
    if (r == null) {
      throw notFound(
          "客户 "
              + customerPartyId
              + " 没有投影档案：3.6.1.2 的客户档案尚未同步到 trade 侧，"
              + "B2B 下单需要客户分级与账期，请先同步该客户的投影");
    }
    return termsRow(r);
  }

  /** 客户投影列表（真分页 + 按分级筛选）。 */
  public B2BCustomerTermsPage pageCustomerTerms(String level, String keyword, int page, int size) {
    int p = Math.max(0, page);
    int s = Math.min(MAX_PAGE_SIZE, Math.max(1, size));
    List<Object> binds = new ArrayList<>();
    List<String> where = new ArrayList<>();
    if (isSet(level)) {
      where.add("customer_level = ?");
      binds.add(level.trim());
    }
    if (isSet(keyword)) {
      where.add("(customer_code ilike ? or customer_name ilike ?)");
      String pattern = "%" + keyword.trim() + "%";
      binds.add(pattern);
      binds.add(pattern);
    }
    String clause = where.isEmpty() ? "" : " where " + String.join(" and ", where);
    long total =
        sql.fetchOne("select count(*) from b2b_customer_terms" + clause, binds.toArray())
            .get(0, Long.class);
    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(s);
    pageBinds.add(p * s);
    List<B2BCustomerTermsRow> items =
        sql.fetch(
                "select id,customer_party_id,customer_code,customer_name,customer_level,"
                    + "payment_terms_days,default_currency,credit_limit,source_version,synced_at"
                    + " from b2b_customer_terms"
                    + clause
                    + " order by customer_level, customer_name, id limit ? offset ?",
                pageBinds.toArray())
            .map(B2BOrderService::termsRow);
    return new B2BCustomerTermsPage(items, p, s, total);
  }

  // ================================================================ 上游投影：阶梯价

  /**
   * 阶梯价投影 upsert（05 价目 → b2b_price_book + b2b_price_tier）。
   *
   * <p>档位整体替换（先删后插）：阶梯是**一档集合**，不是可以逐档追加的独立行 —— 追加语义会让「删掉一档」无法表达，而删档是调价时最常见的动作。
   * 起订量与档位的自洽（阶梯必须覆盖得起订量）由 DB 触发器在每次写入后复核。
   */
  @Transactional
  public B2BPriceBookRow upsertPriceBook(B2BPriceBookInput in, List<B2BPriceTierInput> tiers) {
    UUID tenantId = currentTenant();
    List<B2BPriceTierInput> sorted = new ArrayList<>(tiers);
    sorted.sort((a, b) -> a.minQuantity().compareTo(b.minQuantity()));
    // 同一价目表内重复的档位下界必须在写库前拒掉 —— DB 的 UNIQUE 也会拦，
    // 但那样报错只说「违反唯一约束」，不说「同一个价目表里有两个 10 件档」。
    for (int i = 1; i < sorted.size(); i++) {
      if (sorted.get(i).minQuantity().compareTo(sorted.get(i - 1).minQuantity()) == 0) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "阶梯价档位下界重复："
                + sorted.get(i).minQuantity().toPlainString()
                + " 件出现了两次；档位是按数量区间划分的，同一个下界只能有一档");
      }
    }

    UUID id = UUID.randomUUID();
    long version = in.sourceVersion() == null ? 0L : in.sourceVersion();
    sql.execute(
        "insert into b2b_price_book(id,tenant_id,sku_id,sku_code,sku_name,customer_level,"
            + "currency,moq_quantity,source_version,synced_at)"
            + " values(?,?,?,?,?,?,?,?,?,?::timestamptz)"
            + " on conflict(tenant_id,sku_id,customer_level) do update set"
            + " sku_code=excluded.sku_code, sku_name=excluded.sku_name,"
            + " currency=excluded.currency, moq_quantity=excluded.moq_quantity,"
            + " source_version=excluded.source_version, synced_at=excluded.synced_at,"
            + " updated_at=now()",
        id,
        tenantId,
        in.skuId(),
        text(in.skuCode()),
        text(in.skuName()),
        in.customerLevel().name(),
        in.currency(),
        qty(in.moqQuantity()),
        version,
        OffsetDateTime.now());
    UUID bookId =
        sql.fetchOne(
                "select id from b2b_price_book where sku_id = ? and customer_level = ?",
                in.skuId(),
                in.customerLevel().name())
            .get("id", UUID.class);

    sql.execute("delete from b2b_price_tier where price_book_id = ?", bookId);
    // 阶梯必须**覆盖得起订量**：MOQ > 0 时，最低档下界必须 ≤ MOQ，
    // 否则 (MOQ, 最低档) 这一段数量过了起订量却落不进任何档。
    // 在服务层先拦一次是为了给调用方一个能直接读的 400 —— DB 的 guard_b2b_tier_ladder
    // 是同一判据的第二道防线，它触发时只会变成一个完全没有信息量的 409。
    if (in.moqQuantity().signum() > 0
        && !sorted.isEmpty()
        && sorted.get(0).minQuantity().compareTo(in.moqQuantity()) > 0) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "阶梯价覆盖不起订量：起订量是 "
              + in.moqQuantity().toPlainString()
              + " 件，而最低档是 "
              + sorted.get(0).minQuantity().toPlainString()
              + " 件，会存在「过了起订量却落不进任何档」的空洞");
    }
    for (B2BPriceTierInput t : sorted) {
      sql.execute(
          "insert into b2b_price_tier(id,tenant_id,price_book_id,min_quantity,unit_price)"
              + " values(?,?,?,?,?)",
          UUID.randomUUID(),
          tenantId,
          bookId,
          qty(t.minQuantity()),
          money(t.unitPrice()));
    }
    return priceBook(bookId);
  }

  /** 阶梯价表头详情（含全部档位）。 */
  public B2BPriceBookRow priceBook(UUID id) {
    Record r =
        sql.fetchOne(
            "select id,sku_id,sku_code,sku_name,customer_level,currency,moq_quantity,"
                + "source_version,synced_at from b2b_price_book where id = ?",
            id);
    if (r == null) {
      throw notFound("阶梯价表 " + id + " 不存在或无权查看");
    }
    return priceBookRow(r);
  }

  /** 阶梯价列表（真分页 + SKU / 分级筛选）。 */
  public B2BPriceBookPage pagePriceBooks(String level, UUID skuId, int page, int size) {
    int p = Math.max(0, page);
    int s = Math.min(MAX_PAGE_SIZE, Math.max(1, size));
    List<Object> binds = new ArrayList<>();
    List<String> where = new ArrayList<>();
    if (isSet(level)) {
      where.add("customer_level = ?");
      binds.add(level.trim());
    }
    if (skuId != null) {
      where.add("sku_id = ?");
      binds.add(skuId);
    }
    String clause = where.isEmpty() ? "" : " where " + String.join(" and ", where);
    long total =
        sql.fetchOne("select count(*) from b2b_price_book" + clause, binds.toArray())
            .get(0, Long.class);
    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(s);
    pageBinds.add(p * s);
    List<B2BPriceBookRow> items =
        sql.fetch(
                "select id,sku_id,sku_code,sku_name,customer_level,currency,moq_quantity,"
                    + "source_version,synced_at from b2b_price_book"
                    + clause
                    + " order by customer_level, sku_code, id limit ? offset ?",
                pageBinds.toArray())
            .map(this::priceBookRow);
    return new B2BPriceBookPage(items, p, s, total);
  }

  // ================================================================ 阶梯价试算

  /**
   * 阶梯价试算（只读，不落库）。
   *
   * <p>存在的理由是业务性的：批发下单要问「买 120 件多少钱、便宜多少、再买多少进下一档」， 而下单接口是<b>先校验 MOQ 再落档</b>的 —— 在下单页每改一次数量就打一次 400
   * 是坏体验。 试算走的是与建单**完全相同**的落档函数，两者不会算出两个答案。
   */
  public List<B2BPriceQuoteRow> quote(B2BPriceQuoteRequest request) {
    List<B2BPriceQuoteRow> out = new ArrayList<>();
    for (B2BPriceQuoteLineInput line : request.lines()) {
      out.add(
          quoteRow(
              resolveTier(
                  line.skuId(),
                  line.quantity(),
                  request.customerLevel(),
                  1,
                  new B2BOrderLineInput(line.skuId(), "", "", line.quantity(), "")),
              request.customerLevel()));
    }
    return out;
  }

  // ================================================================ 订单列表 / 详情

  /** 订单列表：真分页 + 与分页无关的全量汇总。 */
  public B2BOrderPage pageOrders(
      B2BStatus status,
      B2BCustomerLevel level,
      UUID customerPartyId,
      B2BPaymentMethod paymentMethod,
      String keyword,
      int page,
      int size) {
    int p = Math.max(0, page);
    int s = Math.min(MAX_PAGE_SIZE, Math.max(1, size));
    List<Object> binds = new ArrayList<>();
    List<String> where = new ArrayList<>();
    if (status != null) {
      where.add("o.status = ?");
      binds.add(status.name());
    }
    if (level != null) {
      where.add("o.customer_level = ?");
      binds.add(level.name());
    }
    if (customerPartyId != null) {
      where.add("o.customer_party_id = ?");
      binds.add(customerPartyId);
    }
    if (paymentMethod != null) {
      where.add("o.payment_method = ?");
      binds.add(paymentMethod.name());
    }
    if (isSet(keyword)) {
      where.add("(o.order_no ilike ? or o.note ilike ?)");
      String pattern = "%" + keyword.trim() + "%";
      binds.add(pattern);
      binds.add(pattern);
    }
    String clause = where.isEmpty() ? "" : " where " + String.join(" and ", where);
    long total =
        sql.fetchOne("select count(*) from b2b_order o" + clause, binds.toArray())
            .get(0, Long.class);
    Record summary =
        sql.fetchOne(
            "select count(*) filter (where status='DRAFT') as draft,"
                + " count(*) filter (where status='CONFIRMED') as confirmed,"
                + " count(*) filter (where status='PARTIALLY_SHIPPED') as partially,"
                + " count(*) filter (where status='SHIPPED') as shipped from b2b_order");
    // 账期资金视图：只认「应收已产生且未结」的行 —— 未发货的部分根本不在这里，
    // 所以这个数天然与零售的「平台已收款」不是一个口径（12.1.2.2 要分开建模的原因）。
    Record money =
        sql.fetchOne(
            "select coalesce(sum(amount) filter (where status='OPEN'),0) as open_amount,"
                + " coalesce(sum(amount) filter (where status='OPEN' and due_date < now()),0)"
                + " as overdue_amount from b2b_receivable");
    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(s);
    pageBinds.add(p * s);
    List<B2BOrderRow> items =
        sql.fetch(
                ORDER_SELECT + clause + " order by o.created_at desc, o.id desc limit ? offset ?",
                pageBinds.toArray())
            .map(B2BOrderService::orderRow);
    return new B2BOrderPage(
        items,
        p,
        s,
        total,
        summary.get("draft", Long.class),
        summary.get("confirmed", Long.class),
        summary.get("partially", Long.class),
        summary.get("shipped", Long.class),
        money.get("overdue_amount", BigDecimal.class),
        money.get("open_amount", BigDecimal.class));
  }

  /** 订单详情：一次调用返回整屏（头 + 客户投影 + 行 + 发货 + 应收 + 事件流）。 */
  public B2BOrderDetail order(UUID orderId) {
    Record head = sql.fetchOne(ORDER_SELECT + " where o.id = ?", orderId);
    if (head == null) {
      throw notFound("批发订单 " + orderId + " 不存在或无权查看");
    }
    B2BCustomerTermsRow terms = null;
    UUID partyId =
        sql.fetchOne("select customer_party_id from b2b_order where id = ?", orderId)
            .get(0, UUID.class);
    Record t =
        sql.fetchOne(
            "select id,customer_party_id,customer_code,customer_name,customer_level,"
                + "payment_terms_days,default_currency,credit_limit,source_version,synced_at"
                + " from b2b_customer_terms where customer_party_id = ?",
            partyId);
    if (t != null) {
      terms = termsRow(t);
    }

    Map<UUID, BigDecimal> shippedByLine = shippedQuantities(orderId);
    List<B2BOrderLineRow> lines =
        sql.fetch(
                "select l.id,l.line_no,l.sku_id,l.sku_code,l.sku_name,l.quantity,l.moq_quantity,"
                    + "l.applied_tier_id,l.applied_tier_min_quantity,l.unit_price,l.line_amount,"
                    + "l.note from b2b_order_line l where l.order_id = ? order by l.line_no",
                orderId)
            .map(
                r -> {
                  UUID id = r.get("id", UUID.class);
                  return new B2BOrderLineRow(
                      id,
                      r.get("line_no", Integer.class),
                      r.get("sku_id", UUID.class),
                      r.get("sku_code", String.class),
                      r.get("sku_name", String.class),
                      r.get("quantity", BigDecimal.class),
                      shippedByLine.getOrDefault(id, qty(BigDecimal.ZERO)),
                      r.get("moq_quantity", BigDecimal.class),
                      r.get("applied_tier_id", UUID.class),
                      r.get("applied_tier_min_quantity", BigDecimal.class),
                      r.get("unit_price", BigDecimal.class),
                      r.get("line_amount", BigDecimal.class),
                      blankToNull(r.get("note", String.class)));
                });

    List<B2BReceivableRow> receivables =
        sql.fetch(RECEIVABLE_SELECT + " where r.order_id = ? order by r.due_date, r.id", orderId)
            .map(
                r ->
                    new B2BReceivableRow(
                        r.get("id", UUID.class),
                        r.get("receivable_no", String.class),
                        r.get("order_id", UUID.class),
                        blankToNull(r.get("order_no", String.class)),
                        r.get("shipment_id", UUID.class),
                        r.get("customer_party_id", UUID.class),
                        blankToNull(r.get("customer_name", String.class)),
                        r.get("currency", String.class),
                        B2BPaymentMethod.valueOf(r.get("payment_method", String.class)),
                        r.get("payment_terms_days", Integer.class),
                        r.get("amount", BigDecimal.class),
                        r.get("confirmed_at", OffsetDateTime.class),
                        r.get("due_date", OffsetDateTime.class),
                        B2BReceivableStatus.valueOf(r.get("status", String.class)),
                        B2BInvoiceState.valueOf(r.get("invoice_state", String.class)),
                        r.get("invoice_requested_at", OffsetDateTime.class),
                        receivableLines(r.get("id", UUID.class))));

    Map<UUID, UUID> receivableByShipment = new LinkedHashMap<>();
    for (Record r :
        sql.fetch("select id, shipment_id from b2b_receivable where order_id = ?", orderId)) {
      receivableByShipment.put(r.get("shipment_id", UUID.class), r.get("id", UUID.class));
    }

    List<B2BShipmentRow> shipments =
        sql.fetch(
                "select id,shipment_no,shipped_at,carrier_ref,note from b2b_shipment"
                    + " where order_id = ? order by shipped_at, id",
                orderId)
            .map(
                r -> {
                  UUID id = r.get("id", UUID.class);
                  return new B2BShipmentRow(
                      id,
                      r.get("shipment_no", String.class),
                      r.get("shipped_at", OffsetDateTime.class),
                      blankToNull(r.get("carrier_ref", String.class)),
                      blankToNull(r.get("note", String.class)),
                      receivableByShipment.get(id),
                      sql.fetch(
                              "select sl.id,sl.order_line_id,l.sku_code,sl.quantity,sl.unit_price,"
                                  + "sl.line_amount from b2b_shipment_line sl"
                                  + " join b2b_order_line l on l.tenant_id=sl.tenant_id"
                                  + " and l.id=sl.order_line_id where sl.shipment_id = ?"
                                  + " order by sl.id",
                              id)
                          .map(
                              sl ->
                                  new B2BShipmentLineRow(
                                      sl.get("id", UUID.class),
                                      sl.get("order_line_id", UUID.class),
                                      sl.get("sku_code", String.class),
                                      sl.get("quantity", BigDecimal.class),
                                      sl.get("unit_price", BigDecimal.class),
                                      sl.get("line_amount", BigDecimal.class))));
                });

    List<B2BEventRow> events =
        sql.fetch(
                "select id,event_type,from_status,to_status,reason,actor_id,occurred_at"
                    + " from b2b_order_event where order_id = ? order by occurred_at, id",
                orderId)
            .map(
                r ->
                    new B2BEventRow(
                        r.get("id", UUID.class),
                        B2BEventType.valueOf(r.get("event_type", String.class)),
                        blankToNull(r.get("from_status", String.class)),
                        blankToNull(r.get("to_status", String.class)),
                        blankToNull(r.get("reason", String.class)),
                        r.get("actor_id", UUID.class),
                        r.get("occurred_at", OffsetDateTime.class)));

    return new B2BOrderDetail(orderRow(head), terms, lines, shipments, receivables, events);
  }

  // ================================================================ 创建 / 更新 / 确认

  /**
   * 创建批发订单（草稿）。
   *
   * <p>行小计与落档全部由系统算：先 {@link #resolveTier} 定档（它顺带把起订量这一关守住）， 再落库。DB 的 {@code guard_b2b_line_tier}
   * 会独立复算一遍落档与单价。
   */
  @Transactional
  public B2BSaved create(B2BOrderCreateInput in, AccessService.Access actor) {
    UUID tenantId = currentTenant();
    UUID orderId = UUID.randomUUID();

    // 客户分级与账期取自 trade 侧只读投影，不跨库回查 catalog。
    B2BCustomerTermsRow terms = customerTerms(in.customerPartyId());
    B2BPaymentMethod method = in.paymentMethod();
    int termsDays = termsDays(method, in.paymentTermsDays());

    List<Priced> priced = new ArrayList<>();
    int no = 0;
    for (B2BOrderLineInput line : in.lines()) {
      no++;
      priced.add(resolveTier(line.skuId(), line.quantity(), terms.customerLevel(), no, line));
    }
    BigDecimal total = BigDecimal.ZERO;
    for (Priced p : priced) {
      total = total.add(p.lineAmount());
    }
    total = scale(total);

    try {
      sql.execute(
          "insert into b2b_order(id,tenant_id,order_no,order_type,customer_party_id,customer_level,"
              + "currency,payment_method,payment_terms_days,cost_basis,status,total_amount,note,version)"
              + " values(?,?,?,?,?,?,?,?,?,?, 'DRAFT', ?,?,0)",
          orderId,
          tenantId,
          in.orderNo().trim(),
          in.orderType().name(),
          in.customerPartyId(),
          terms.customerLevel().name(),
          in.currency(),
          method.name(),
          termsDays,
          in.costBasis().name(),
          total,
          text(in.note()));
    } catch (DuplicateKeyException e) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "订单号已存在：同租户内「" + in.orderNo().trim() + "」只能有一张单");
    }

    writeLines(tenantId, orderId, priced);
    writeEvent(
        tenantId,
        orderId,
        B2BEventType.CREATED,
        "",
        B2BStatus.DRAFT.name(),
        "创建批发订单（类型 "
            + in.orderType().name()
            + " / 收款 "
            + method.name()
            + " / 成本口径 "
            + in.costBasis().name()
            + "）",
        actor.membershipId(),
        OffsetDateTime.now());
    return new B2BSaved(
        orderId,
        in.orderNo().trim(),
        B2BStatus.DRAFT,
        total,
        BigDecimal.ZERO.setScale(MONEY_SCALE),
        "草稿已创建：阶梯价已按数量落档，起订量已校验；确认后明细与落档冻结");
  }

  /** 更新草稿（整体替换行）。确认后由 DB 触发器冻结。 */
  @Transactional
  public B2BSaved update(UUID orderId, B2BOrderUpdateInput in, AccessService.Access actor) {
    UUID tenantId = currentTenant();
    Record current = orderHead(orderId);
    if (!B2BStatus.DRAFT.name().equals(current.get("status", String.class))) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "订单已确认，明细与阶梯落档已冻结：阶梯价与起订量是下单当时的判定依据，" + "改它们等于让「当时按哪一档算的钱」在历史里出现两个答案");
    }
    B2BCustomerLevel level = B2BCustomerLevel.valueOf(current.get("customer_level", String.class));
    List<Priced> priced = new ArrayList<>();
    int no = 0;
    for (B2BOrderLineInput line : in.lines()) {
      no++;
      priced.add(resolveTier(line.skuId(), line.quantity(), level, no, line));
    }
    BigDecimal total = BigDecimal.ZERO;
    for (Priced p : priced) {
      total = total.add(p.lineAmount());
    }
    total = scale(total);

    sql.execute("delete from b2b_order_line where order_id = ?", orderId);
    writeLines(tenantId, orderId, priced);
    sql.execute(
        "update b2b_order set total_amount = ?, note = ?, version = version + 1, updated_at = now()"
            + " where id = ?",
        total,
        text(in.note()),
        orderId);
    writeEvent(
        tenantId,
        orderId,
        B2BEventType.UPDATED,
        B2BStatus.DRAFT.name(),
        B2BStatus.DRAFT.name(),
        "草稿更新：行与阶梯落档已重算",
        actor.membershipId(),
        OffsetDateTime.now());
    return new B2BSaved(
        orderId,
        current.get("order_no", String.class),
        B2BStatus.DRAFT,
        total,
        BigDecimal.ZERO.setScale(MONEY_SCALE),
        "草稿已更新");
  }

  /** 确认订单：DRAFT → CONFIRMED，明细与落档随即冻结。 */
  @Transactional
  public B2BSaved confirm(UUID orderId, AccessService.Access actor) {
    UUID tenantId = currentTenant();
    Record current = orderHead(orderId);
    if (!B2BStatus.DRAFT.name().equals(current.get("status", String.class))) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "只有草稿能确认；本单当前不在草稿态");
    }
    OffsetDateTime now = OffsetDateTime.now();
    // 四道闸（行 ≥ 1 / 总额 == Σ 行 / 每行已过起订量）由 V120 的延迟约束触发器在提交时回表核对。
    sql.execute(
        "update b2b_order set status = 'CONFIRMED', confirmed_at = ?::timestamptz,"
            + " version = version + 1, updated_at = now() where id = ?",
        now,
        orderId);
    writeEvent(
        tenantId,
        orderId,
        B2BEventType.CONFIRMED,
        B2BStatus.DRAFT.name(),
        B2BStatus.CONFIRMED.name(),
        "确认订单：明细与阶梯落档冻结。**确认不产生应收** —— 账期从发货确认那天起算",
        actor.membershipId(),
        now);
    return new B2BSaved(
        orderId,
        current.get("order_no", String.class),
        B2BStatus.CONFIRMED,
        current.get("total_amount", BigDecimal.class),
        BigDecimal.ZERO.setScale(MONEY_SCALE),
        "订单已确认：仍未产生应收，B2B 的应收在发货确认时按账期生成");
  }

  // ================================================================ 发货确认（应收的唯一入口）

  /**
   * 发货确认 —— 应收在**这里**、且只在这里产生。
   *
   * <p>「未发货不产生应收」在本方法里有三种收款方式的分支，每一分支都写明理由：
   *
   * <ul>
   *   <li>{@code ORDER_SETTLEMENT}：款项在下单时已收讫，<b>不产生应收</b> （收进来的是预收，不是别人欠我们的钱）。返回值里明确说明原因， 而不是返回
   *       {@code receivableId=null} 让人猜。
   *   <li>{@code ON_RECEIPT}：应收在发货确认时产生，到期日 = 发货确认时刻。
   *   <li>{@code CREDIT_TERM}：应收在发货确认时产生， 到期日 = 发货确认时刻 + 账期天数（DB 触发器独立复算同一公式）。
   * </ul>
   */
  @Transactional
  public B2BSaved ship(UUID orderId, B2BShipmentInput in, AccessService.Access actor) {
    UUID tenantId = currentTenant();
    Record head = orderHead(orderId);
    String status = head.get("status", String.class);
    if (!B2BStatus.CONFIRMED.name().equals(status)
        && !B2BStatus.PARTIALLY_SHIPPED.name().equals(status)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "只有已确认或部分发货的订单能发货；本单当前状态是 " + status + "（草稿要先确认，确认不会产生应收）");
    }

    OffsetDateTime shippedAt = in.shippedAt() == null ? OffsetDateTime.now() : in.shippedAt();
    UUID shipmentId = UUID.randomUUID();
    sql.execute(
        "insert into b2b_shipment(id,tenant_id,order_id,shipment_no,shipped_at,carrier_ref,note)"
            + " values(?,?,?,?,?::timestamptz,?,?)",
        shipmentId,
        tenantId,
        orderId,
        in.shipmentNo().trim(),
        shippedAt,
        text(in.carrierRef()),
        text(in.note()));

    Map<UUID, Record> orderLines = new LinkedHashMap<>();
    for (Record r :
        sql.fetch(
            "select id,sku_code,quantity,unit_price,line_amount from b2b_order_line"
                + " where order_id = ?",
            orderId)) {
      orderLines.put(r.get("id", UUID.class), r);
    }

    Map<UUID, BigDecimal> alreadyShipped = shippedQuantities(orderId);
    BigDecimal shippedAmount = BigDecimal.ZERO;
    // 同一张发货单里同一个订单行出现两次也要合并判断，否则分两行各写一半
    // 能绕过「累计不得超过订单量」—— 服务层先把本单内的重复合并起来。
    Map<UUID, BigDecimal> perLine = new LinkedHashMap<>();
    for (B2BShipmentLineInput sl : in.lines()) {
      perLine.merge(sl.orderLineId(), qty(sl.quantity()), BigDecimal::add);
    }
    for (Map.Entry<UUID, BigDecimal> e : perLine.entrySet()) {
      Record line = orderLines.get(e.getKey());
      if (line == null) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "发货明细引用的订单行不属于本单：" + e.getKey());
      }
      BigDecimal ordered = line.get("quantity", BigDecimal.class);
      BigDecimal shipped = alreadyShipped.getOrDefault(e.getKey(), qty(BigDecimal.ZERO));
      if (shipped.add(e.getValue()).compareTo(ordered) > 0) {
        // 超发是最容易出的错，而且直接放大应收，所以在这里就挡住并说清楚是哪一行。
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "发货量超过订单量：SKU "
                + line.get("sku_code", String.class)
                + " 已发 "
                + shipped.toPlainString()
                + " 件，本次再发 "
                + e.getValue().toPlainString()
                + " 件，订单量只有 "
                + ordered.toPlainString()
                + " 件");
      }
    }
    for (B2BShipmentLineInput sl : in.lines()) {
      Record line = orderLines.get(sl.orderLineId());
      BigDecimal q = qty(sl.quantity());
      BigDecimal price = line.get("unit_price", BigDecimal.class);
      shippedAmount =
          shippedAmount.add(q.multiply(price).setScale(MONEY_SCALE, RoundingMode.HALF_UP));
      sql.execute(
          "insert into b2b_shipment_line(id,tenant_id,shipment_id,order_line_id,quantity,unit_price)"
              + " values(?,?,?,?,?,?)",
          UUID.randomUUID(),
          tenantId,
          shipmentId,
          sl.orderLineId(),
          q,
          price);
    }
    shippedAmount = scale(shippedAmount);

    String paymentMethod = head.get("payment_method", String.class);
    int termsDays = head.get("payment_terms_days", Integer.class);
    UUID receivableId = null;
    String receivableNo = null;
    String note;
    if (B2BPaymentMethod.ORDER_SETTLEMENT.name().equals(paymentMethod)) {
      note =
          "已登记发货。本次**不产生应收**：收款方式是「下单结算」，款项在下单时已收讫，"
              + "收进来的是预收而不是应收（账期应收只属于 ON_RECEIPT / CREDIT_TERM）";
    } else {
      receivableNo = nextReference("AR");
      receivableId = UUID.randomUUID();
      // 账期起算点 = 发货确认时刻。到期日由服务层算一次、DB 触发器再算一次。
      OffsetDateTime dueDate = shippedAt.plusDays(termsDays);
      sql.execute(
          "insert into b2b_receivable(id,tenant_id,receivable_no,order_id,shipment_id,"
              + "customer_party_id,currency,payment_method,payment_terms_days,amount,"
              + "confirmed_at,due_date)"
              + " values(?,?,?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz)",
          receivableId,
          tenantId,
          receivableNo,
          orderId,
          shipmentId,
          head.get("customer_party_id", UUID.class),
          head.get("currency", String.class),
          paymentMethod,
          termsDays,
          shippedAmount,
          shippedAt,
          dueDate);
      // 应收明细是发货明细的不可变副本：发货单日后可能被改，应收不能跟着变。
      for (B2BShipmentLineInput sl : in.lines()) {
        Record line = orderLines.get(sl.orderLineId());
        BigDecimal q = qty(sl.quantity());
        BigDecimal price = line.get("unit_price", BigDecimal.class);
        sql.execute(
            "insert into b2b_receivable_line(id,tenant_id,receivable_id,order_line_id,sku_code,"
                + "quantity,unit_price) values(?,?,?,?,?,?,?)",
            UUID.randomUUID(),
            tenantId,
            receivableId,
            sl.orderLineId(),
            line.get("sku_code", String.class),
            q,
            price);
      }
      note =
          "已登记发货，并按账期确认应收 "
              + receivableNo
              + "（"
              + (termsDays > 0 ? "账期 " + termsDays + " 天" : "确认收货即到期")
              + "，到期日 "
              + dueDate.toLocalDate()
              + "）。开票需求可在应收列表里提出";
    }

    // 状态推进：全部发完 → SHIPPED，否则 PARTIALLY_SHIPPED。
    // 「是否发完」由本次发货量与订单量比较得出，不接受调用方指定状态。
    BigDecimal remaining = remainingQuantity(orderId);
    String newStatus = remaining.signum() <= 0 ? "SHIPPED" : "PARTIALLY_SHIPPED";
    BigDecimal receivableAmount =
        head.get("receivable_amount", BigDecimal.class)
            .add(receivableId == null ? BigDecimal.ZERO.setScale(MONEY_SCALE) : shippedAmount);
    receivableAmount = scale(receivableAmount);
    sql.execute(
        "update b2b_order set status = ?, receivable_amount = ?, version = version + 1,"
            + " updated_at = now() where id = ?",
        newStatus,
        receivableAmount,
        orderId);

    writeEvent(
        tenantId,
        orderId,
        B2BEventType.SHIPPED,
        status,
        newStatus,
        "发货确认 "
            + in.shipmentNo().trim()
            + (receivableId == null
                ? "；未产生应收（下单结算）"
                : "；确认应收 " + receivableNo + " 金额 " + shippedAmount.toPlainString()),
        actor.membershipId(),
        shippedAt);

    return new B2BSaved(
        shipmentId,
        receivableId == null ? in.shipmentNo().trim() : receivableNo,
        B2BStatus.valueOf(newStatus),
        head.get("total_amount", BigDecimal.class),
        receivableAmount,
        note);
  }

  /** 登记开票需求：把本单下所有未结应收标记为「已请求开票」。不跨库写 12 财务。 */
  @Transactional
  public B2BSaved requestInvoice(UUID orderId, AccessService.Access actor) {
    UUID tenantId = currentTenant();
    Record head = orderHead(orderId);
    OffsetDateTime now = OffsetDateTime.now();
    int updated =
        sql.execute(
            "update b2b_receivable set invoice_state = 'REQUESTED',"
                + " invoice_requested_at = ?::timestamptz, updated_at = now()"
                + " where order_id = ? and status = 'OPEN'"
                + " and invoice_state = 'NOT_REQUESTED'",
            now,
            orderId);
    if (updated == 0) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "本单没有可提出开票需求的应收：应收要么尚未产生（未发货）、已结清/已作废，"
              + "要么已经提出过开票需求。**开票需求挂在已产生的应收上**，"
              + "未发货的部分没有金额可以开票");
    }
    writeEvent(
        tenantId,
        orderId,
        B2BEventType.INVOICE_REQUESTED,
        head.get("status", String.class),
        head.get("status", String.class),
        "提出开票需求：" + updated + " 张应收等待 12.6.2 开票（本服务不写财务表）",
        actor.membershipId(),
        now);
    return new B2BSaved(
        orderId,
        head.get("order_no", String.class),
        B2BStatus.valueOf(head.get("status", String.class)),
        head.get("total_amount", BigDecimal.class),
        head.get("receivable_amount", BigDecimal.class),
        "已对 " + updated + " 张应收提出开票需求");
  }

  // ================================================================ 应收列表

  /** 应收列表：真分页 + 账期资金汇总。 */
  public B2BReceivablePage pageReceivables(
      B2BReceivableStatus status, B2BInvoiceState invoiceState, UUID orderId, int page, int size) {
    int p = Math.max(0, page);
    int s = Math.min(MAX_PAGE_SIZE, Math.max(1, size));
    List<Object> binds = new ArrayList<>();
    List<String> where = new ArrayList<>();
    if (status != null) {
      where.add("r.status = ?");
      binds.add(status.name());
    }
    if (invoiceState != null) {
      where.add("r.invoice_state = ?");
      binds.add(invoiceState.name());
    }
    if (orderId != null) {
      where.add("r.order_id = ?");
      binds.add(orderId);
    }
    String clause = where.isEmpty() ? "" : " where " + String.join(" and ", where);
    long total =
        sql.fetchOne("select count(*) from b2b_receivable r" + clause, binds.toArray())
            .get(0, Long.class);
    Record summary =
        sql.fetchOne(
            "select coalesce(sum(amount) filter (where status='OPEN'),0) as open_amount,"
                + " coalesce(sum(amount) filter (where status='OPEN' and due_date < now()),0)"
                + " as overdue_amount,"
                + " count(*) filter (where invoice_state='REQUESTED') as invoice_pending"
                + " from b2b_receivable");
    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(s);
    pageBinds.add(p * s);
    List<B2BReceivableRow> items =
        sql.fetch(
                RECEIVABLE_SELECT + clause + " order by r.due_date, r.id limit ? offset ?",
                pageBinds.toArray())
            .map(this::receivableRow);
    return new B2BReceivablePage(
        items,
        p,
        s,
        total,
        summary.get("open_amount", BigDecimal.class),
        summary.get("overdue_amount", BigDecimal.class),
        summary.get("invoice_pending", Long.class));
  }

  // ================================================================ 阶梯落档（核心算法）

  /**
   * 阶梯落档：给定 SKU、数量与客户分级，返回命中的档位。
   *
   * <p>区间语义是 {@code (上一档下界, 本档下界]}，用一条 {@code min_quantity <= quantity ORDER BY min_quantity DESC
   * LIMIT 1} 表达 —— <b>「恰好等于阈值」不需要任何特殊分支</b>， 它是 {@code <=} 的自然结果。这正是最容易被写成 {@code <} 然后在边界上算错一档的地方。
   *
   * <p>起订量在这里一并守住（{@code moq = 0} 表示不限起订量）。落在服务层而不是只靠 DB CHECK，是为了让调用方拿到一句能直接展示的 400，而不是一条 CHECK
   * 约束名。
   */
  private Priced resolveTier(
      UUID skuId, BigDecimal quantity, B2BCustomerLevel level, int lineNo, B2BOrderLineInput in) {
    Record book =
        sql.fetchOne(
            "select id,sku_code,sku_name,moq_quantity from b2b_price_book"
                + " where sku_id = ? and customer_level = ?",
            skuId,
            level.name());
    if (book == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "SKU "
              + skuId
              + " 没有 "
              + level.name()
              + " 分级的阶梯价："
              + "B2B 单价由分级阶梯价算出（不接受手工填价），"
              + "请先为该 SKU 维护对应分级的阶梯价（05 价目未交付时由本服务的价目投影端点维护）");
    }
    BigDecimal q = qty(quantity);
    BigDecimal moq = book.get("moq_quantity", BigDecimal.class);
    if (moq.signum() > 0 && q.compareTo(moq) < 0) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "起订量不足：SKU "
              + book.get("sku_code", String.class)
              + " 在 "
              + level.name()
              + " 分级下的起订量是 "
              + moq.toPlainString()
              + " 件，本次下单 "
              + q.toPlainString()
              + " 件。**起订量是硬校验**，低于它不能下单");
    }
    Record tier =
        sql.fetchOne(
            "select id,min_quantity,unit_price from b2b_price_tier"
                + " where price_book_id = ? and min_quantity <= ?"
                + " order by min_quantity desc limit 1",
            book.get("id", UUID.class),
            q);
    if (tier == null) {
      // DB 的 guard_b2b_tier_ladder 已经挡住「阶梯覆盖不起订量」的形态，
      // 这里仍给出可读报错：价目表被并发改过，或数据被手工插过。
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "SKU "
              + book.get("sku_code", String.class)
              + " 的阶梯价没有覆盖数量 "
              + q.toPlainString()
              + " 件：最低档下界高于该数量，"
              + "请检查价目表档位与起订量是否自洽");
    }
    Record next =
        sql.fetchOne(
            "select min_quantity,unit_price from b2b_price_tier"
                + " where price_book_id = ? and min_quantity > ?"
                + " order by min_quantity limit 1",
            book.get("id", UUID.class),
            tier.get("min_quantity", BigDecimal.class));

    BigDecimal unitPrice = tier.get("unit_price", BigDecimal.class);
    BigDecimal tierMin = tier.get("min_quantity", BigDecimal.class);
    return new Priced(
        lineNo,
        in,
        book.get("id", UUID.class),
        tier.get("id", UUID.class),
        tierMin,
        unitPrice,
        moq,
        scale(q.multiply(unitPrice)),
        next == null ? null : next.get("min_quantity", BigDecimal.class),
        next == null ? null : next.get("unit_price", BigDecimal.class));
  }

  /** 试算用的对外形状（不落库）。 */
  private B2BPriceQuoteRow quoteRow(Priced p, B2BCustomerLevel level) {
    return new B2BPriceQuoteRow(
        p.in().skuId(),
        p.in().skuCode(),
        level,
        p.moq(),
        p.in().quantity(),
        p.tierMin(),
        p.unitPrice(),
        p.lineAmount(),
        p.nextMin() == null,
        p.nextMin(),
        p.nextPrice());
  }

  // ================================================================ 写入子表

  private void writeLines(UUID tenantId, UUID orderId, List<Priced> priced) {
    for (Priced p : priced) {
      sql.execute(
          "insert into b2b_order_line(id,tenant_id,order_id,line_no,sku_id,sku_code,sku_name,"
              + "quantity,price_book_id,applied_tier_id,applied_tier_min_quantity,unit_price,"
              + "moq_quantity,note) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
          UUID.randomUUID(),
          tenantId,
          orderId,
          p.lineNo(),
          p.in().skuId(),
          text(p.in().skuCode()),
          text(p.in().skuName()),
          qty(p.in().quantity()),
          p.priceBookId(),
          p.tierId(),
          p.tierMin(),
          p.unitPrice(),
          p.moq(),
          text(p.in().note()));
    }
  }

  private void writeEvent(
      UUID tenantId,
      UUID orderId,
      B2BEventType eventType,
      String fromStatus,
      String toStatus,
      String reason,
      UUID actorId,
      OffsetDateTime occurredAt) {
    sql.execute(
        "insert into b2b_order_event(id,tenant_id,order_id,event_type,from_status,to_status,"
            + "reason,actor_id,occurred_at) values(?,?,?,?,?,?,?,?,?::timestamptz)",
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

  // ================================================================ 工具

  /** 订单列表 / 详情共用的列集合（客户名取自投影，缺失时如实给空而不是报错）。 */
  private static final String ORDER_SELECT =
      "select o.id,o.order_no,o.order_type,o.customer_party_id,o.customer_level,o.currency,"
          + "o.payment_method,o.payment_terms_days,o.cost_basis,o.status,o.total_amount,"
          + "o.receivable_amount,o.confirmed_at,o.created_at,o.version,"
          + "t.customer_name,"
          + " (select count(*) from b2b_order_line l where l.order_id = o.id) as line_count"
          + " from b2b_order o left join b2b_customer_terms t"
          + " on t.tenant_id = o.tenant_id and t.customer_party_id = o.customer_party_id";

  private static final String RECEIVABLE_SELECT =
      "select r.id,r.receivable_no,r.order_id,r.shipment_id,r.customer_party_id,r.currency,"
          + "r.payment_method,r.payment_terms_days,r.amount,r.confirmed_at,r.due_date,r.status,"
          + "r.invoice_state,r.invoice_requested_at,o.order_no,t.customer_name"
          + " from b2b_receivable r join b2b_order o on o.tenant_id = r.tenant_id"
          + " and o.id = r.order_id"
          + " left join b2b_customer_terms t on t.tenant_id = r.tenant_id"
          + " and t.customer_party_id = r.customer_party_id";

  private Record orderHead(UUID orderId) {
    Record r =
        sql.fetchOne(
            "select order_no,status,customer_level,payment_method,"
                + "payment_terms_days,total_amount,receivable_amount,customer_party_id,currency"
                + " from b2b_order where id = ?",
            orderId);
    if (r == null) {
      throw notFound("批发订单 " + orderId + " 不存在或无权操作");
    }
    return r;
  }

  private Map<UUID, BigDecimal> shippedQuantities(UUID orderId) {
    Map<UUID, BigDecimal> out = new LinkedHashMap<>();
    for (Record r :
        sql.fetch(
            "select sl.order_line_id, sum(sl.quantity) as shipped from b2b_shipment_line sl"
                + " join b2b_shipment s on s.tenant_id = sl.tenant_id and s.id = sl.shipment_id"
                + " where s.order_id = ? group by sl.order_line_id",
            orderId)) {
      out.put(r.get("order_line_id", UUID.class), r.get("shipped", BigDecimal.class));
    }
    return out;
  }

  /** 尚未发货的总数量（各行「订单量 − 已发量」之和）。 */
  private BigDecimal remainingQuantity(UUID orderId) {
    Record r =
        sql.fetchOne(
            "select coalesce(sum(l.quantity),0) - coalesce((select sum(sl.quantity)"
                + " from b2b_shipment_line sl join b2b_shipment s"
                + " on s.tenant_id = sl.tenant_id and s.id = sl.shipment_id"
                + " where s.order_id = ?),0) as remaining"
                + " from b2b_order_line l where l.order_id = ?",
            orderId,
            orderId);
    return r.get("remaining", BigDecimal.class);
  }

  private List<B2BReceivableLineRow> receivableLines(UUID receivableId) {
    return sql.fetch(
            "select id,order_line_id,sku_code,quantity,unit_price,line_amount"
                + " from b2b_receivable_line where receivable_id = ? order by id",
            receivableId)
        .map(
            r ->
                new B2BReceivableLineRow(
                    r.get("id", UUID.class),
                    r.get("order_line_id", UUID.class),
                    r.get("sku_code", String.class),
                    r.get("quantity", BigDecimal.class),
                    r.get("unit_price", BigDecimal.class),
                    r.get("line_amount", BigDecimal.class)));
  }

  /**
   * 单据号。
   *
   * <p>刻意用<b>随机后缀</b>而不是 {@code max(id)+1} 那类方案：多车道并行提交时两个服务 抢同一个序列会撞号，而撞号在这里的表现是「409 但其实该成功」。
   */
  private String nextReference(String prefix) {
    return prefix
        + "-"
        + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
  }

  private static B2BCustomerTermsRow termsRow(Record r) {
    return new B2BCustomerTermsRow(
        r.get("id", UUID.class),
        r.get("customer_party_id", UUID.class),
        r.get("customer_code", String.class),
        r.get("customer_name", String.class),
        B2BCustomerLevel.valueOf(r.get("customer_level", String.class)),
        r.get("payment_terms_days", Integer.class),
        blankToNull(r.get("default_currency", String.class)),
        r.get("credit_limit", BigDecimal.class),
        r.get("source_version", Long.class),
        r.get("synced_at", OffsetDateTime.class));
  }

  private B2BPriceBookRow priceBookRow(Record r) {
    UUID id = r.get("id", UUID.class);
    List<B2BPriceTierRow> tiers =
        sql.fetch(
                "select id,min_quantity,unit_price,created_at from b2b_price_tier"
                    + " where price_book_id = ? order by min_quantity",
                id)
            .map(
                t ->
                    new B2BPriceTierRow(
                        t.get("id", UUID.class),
                        t.get("min_quantity", BigDecimal.class),
                        t.get("unit_price", BigDecimal.class),
                        t.get("created_at", OffsetDateTime.class)));
    return new B2BPriceBookRow(
        id,
        r.get("sku_id", UUID.class),
        r.get("sku_code", String.class),
        r.get("sku_name", String.class),
        B2BCustomerLevel.valueOf(r.get("customer_level", String.class)),
        r.get("currency", String.class),
        r.get("moq_quantity", BigDecimal.class),
        r.get("source_version", Long.class),
        r.get("synced_at", OffsetDateTime.class),
        tiers);
  }

  private static B2BOrderRow orderRow(Record r) {
    BigDecimal total = r.get("total_amount", BigDecimal.class);
    BigDecimal receivable = r.get("receivable_amount", BigDecimal.class);
    return new B2BOrderRow(
        r.get("id", UUID.class),
        r.get("order_no", String.class),
        B2BOrderType.valueOf(r.get("order_type", String.class)),
        r.get("customer_party_id", UUID.class),
        blankToNull(r.get("customer_name", String.class)),
        B2BCustomerLevel.valueOf(r.get("customer_level", String.class)),
        r.get("currency", String.class),
        B2BPaymentMethod.valueOf(r.get("payment_method", String.class)),
        r.get("payment_terms_days", Integer.class),
        B2BCostBasis.valueOf(r.get("cost_basis", String.class)),
        B2BStatus.valueOf(r.get("status", String.class)),
        total,
        receivable,
        scale(total.subtract(receivable)),
        r.get("line_count", Integer.class),
        r.get("confirmed_at", OffsetDateTime.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("version", Integer.class));
  }

  private B2BReceivableRow receivableRow(Record r) {
    UUID id = r.get("id", UUID.class);
    return new B2BReceivableRow(
        id,
        r.get("receivable_no", String.class),
        r.get("order_id", UUID.class),
        blankToNull(r.get("order_no", String.class)),
        r.get("shipment_id", UUID.class),
        r.get("customer_party_id", UUID.class),
        blankToNull(r.get("customer_name", String.class)),
        r.get("currency", String.class),
        B2BPaymentMethod.valueOf(r.get("payment_method", String.class)),
        r.get("payment_terms_days", Integer.class),
        r.get("amount", BigDecimal.class),
        r.get("confirmed_at", OffsetDateTime.class),
        r.get("due_date", OffsetDateTime.class),
        B2BReceivableStatus.valueOf(r.get("status", String.class)),
        B2BInvoiceState.valueOf(r.get("invoice_state", String.class)),
        r.get("invoice_requested_at", OffsetDateTime.class),
        sql.fetch(
                "select id,order_line_id,sku_code,quantity,unit_price,line_amount"
                    + " from b2b_receivable_line where receivable_id = ? order by id",
                id)
            .map(
                l ->
                    new B2BReceivableLineRow(
                        l.get("id", UUID.class),
                        l.get("order_line_id", UUID.class),
                        l.get("sku_code", String.class),
                        l.get("quantity", BigDecimal.class),
                        l.get("unit_price", BigDecimal.class),
                        l.get("line_amount", BigDecimal.class))));
  }

  /** 租户来自事务内的绑定值，**不是**任何请求头。 */
  private UUID currentTenant() {
    return sql.fetchOne("select nullif(current_setting('app.current_tenant',true),'')::uuid")
        .get(0, UUID.class);
  }

  /**
   * 账期天数与收款方式必须自洽。
   *
   * <p>非账期带账期天数**直接拒**，而不是默默抹成 0：抹掉之后调用方会以为自己配了 30 天账期，而单据上其实写着 0 —— 到对账日才会发现「这单怎么没有账期」。
   */
  private static int termsDays(B2BPaymentMethod method, Integer days) {
    int d = days == null ? 0 : days;
    if (method != B2BPaymentMethod.CREDIT_TERM) {
      if (d != 0) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "收款方式是「"
                + method.name()
                + "」时不能带账期天数（收到 "
                + d
                + " 天）。下单结算与确认收货后结算是即时结算，不占账期；"
                + "要账期请把收款方式选成 CREDIT_TERM");
      }
      return 0;
    }
    if (d <= 0) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "账期结算必须给出账期天数（1–365）：账期为 0 等于「下单即时收款」，" + "那与账期结算是两种业务，收款方式应选「下单结算」或「确认收货后结算」");
    }
    return d;
  }

  private static BigDecimal qty(BigDecimal v) {
    return v.setScale(QTY_SCALE, RoundingMode.HALF_UP);
  }

  private static BigDecimal money(BigDecimal v) {
    return v.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
  }

  private static BigDecimal scale(BigDecimal v) {
    return v.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
  }

  private static String text(String s) {
    return s == null ? "" : s.trim();
  }

  private static String blankToNull(String s) {
    return s == null || s.isBlank() ? null : s;
  }

  private static boolean isSet(String s) {
    return s != null && !s.isBlank();
  }

  private static ResponseStatusException notFound(String message) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
  }
}
