package com.starcon.finance;

import static com.starcon.finance.PayableModels.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 12.1.2.1 应付单管理。
 *
 * <p>蓝图 12-finance.md：「管理应付账款：来源（采购收货、供应商对账、物流费用、平台费用、其他）、
 * 应付对象、金额与币种、到期日、已付金额与未付余额。」输出：「应付单 + 明细 + 未付清单。」
 * 加粗句：「<b>应付、预付款与采购承诺分开建模</b>；<b>确认依据由业务合同和已批准政策决定，不能仅凭下单或收货统一推导</b>。」
 *
 * <p><b>本模块的四条设计主线</b>：
 *
 * <ol>
 *   <li><b>应付 / 预付款 / 采购承诺分开建模。</b> 应付在 {@code finance_payable}，
 *       预付款在 {@code finance_prepayment}（<b>另一张表</b>），采购承诺<b>本服务不建模</b>
 *       （事实所有者是 6 采购）。三者不靠一个 type 字段区分。
 *   <li><b>确认依据由已批准政策决定。</b> 每个（来源 × 义务类型）都要有一份**启用的**政策，
 *       没有 ⇒ 该来源一张单都产生不了，服务层返回一句能直接展示的话。
 *       「不能仅凭收货推导」在代码里落成：<b>采购收货来源必须带单价快照与数量</b>，
 *       上游给不出就不导入（<b>绝不填 0、也绝不编一条合同</b>）。
 *   <li><b>金额是唯一的，已付与未付是派生的。</b> 单头金额 = 明细合计（DB 触发器）；
 *       单头已付 = 明细已付之和（DB 触发器）；单头未付 = 生成列（amount − paid_amount）。
 *       付款的唯一写入口是行级 {@code paid_amount}，归 <b>12.2.2.1</b>（未交付）——
 *       所以当前已付恒为 0、单头恒为「未付」，<b>本模块不提供任何改已付金额的端点</b>。
 *   <li><b>币种分开。</b> 列表汇总、未付清单、预付款余额全部按币种分组，
 *       USD 与 EUR 加起来不是任何一个金额。
 * </ol>
 *
 * <p><b>跨服务边界</b>：上游事实一律经<b>授权 HTTP API</b>读（{@link PurchaseArrivalPort} /
 * {@link FirstMilePort} / {@link AdSpendPort}），不跨库不跨服务 SQL。租户由上游按已认证成员关系解析，
 * 本端口只转发调用方自己的 bearer ⇒ 隔离是<b>两道</b>（finance 侧 RLS + 上游侧 RLS）。
 */
@Service
public class PayableService {

  private static final Logger logger = LoggerFactory.getLogger(PayableService.class);

  /** 当前 UTC 日历日：未付清单的基准日。 */
  static final LocalDate TODAY_UTC = LocalDate.now(ZoneOffset.UTC);

  /** 一页最大条数。 */
  static final int MAX_PAGE_SIZE = 200;

  private final DSLContext sql;
  private final PurchaseArrivalPort arrivals;
  private final FirstMilePort firstMile;
  private final AdSpendPort adSpend;

  public PayableService(
      DSLContext sql, PurchaseArrivalPort arrivals, FirstMilePort firstMile, AdSpendPort adSpend) {
    this.sql = sql;
    this.arrivals = arrivals;
    this.firstMile = firstMile;
    this.adSpend = adSpend;
  }

  // ================================================================ 能力声明

  /**
   * 能力声明：**每一维说清「有没有、依据是什么、缺了会怎样」**。
   *
   * <p>⚠️ 页面必须能分开两件事：<b>「上游未交付」</b>（供应商对账侧的财务确认、平台账单）
   * 与<b>「上游已交付但本模块没接」</b>（付款）。把两者都写成「不可用」，
   * 运维会以为要去催上游，而实际上付款是本服务自己的后续模块。
   */
  public FinancePayableCapabilities capabilities() {
    var capabilities = new ArrayList<FinancePayableCapability>();
    var gaps = new ArrayList<FinancePayableDownstreamGap>();

    capabilities.add(
        new FinancePayableCapability(
            "12.1.2.1",
            "应付单管理",
            true,
            "finance_payable / finance_payable_line / finance_payable_event",
            "",
            "应付单、明细行与未付清单已落地：数据落 PostgreSQL 16，"
                + "查询走 jOOQ 显式 SQL，全链路 BigDecimal + NUMERIC，无浮点。"));
    capabilities.add(
        new FinancePayableCapability(
            "12.1.2.1",
            "应付确认政策",
            true,
            "finance_payable_policy",
            "",
            "「什么业务确认为应付」是**租户自己维护的一份配置**，不是系统按收货单推导的。"
                + "某个来源没有启用的政策 ⇒ 该来源一张应付都产生不了。"
                + "**政策表刻意不预置任何一行** —— 预置 enabled=true 等于替用户把会计政策定了。"));
    capabilities.add(
        new FinancePayableCapability(
            "12.1.2.1",
            "应付 / 预付款 / 采购承诺分开建模",
            true,
            "finance_payable（应付）· finance_prepayment（预付款）· 采购承诺不建模",
            "",
            "蓝图加粗句「应付、预付款与采购承诺分开建模」的落点：**两张表 + 一个义务类型词表**。"
                + "预付款是**独立聚合**（有金额、币种、应付对象、预计付款日与抵扣明细），"
                + "它**不是**应付单，也没有「已付金额」字段 —— 付款是 12.2.2.1 的职责。"
                + "采购承诺的事实所有者是 6 采购（trade 的采购单），本服务**不建第二份**。"));
    capabilities.add(
        new FinancePayableCapability(
            "6.3.2.2",
            "到货应付部分确认 → 应付",
            true,
            "经 trade 的 GET /trade/purchase-arrivals 读、POST .../payable-confirmations/{id}/confirm 写回",
            "",
            "上游按「单价快照 × 本次到货数量」算出应付金额，状态恒为「待财务确认」、生效金额恒为 0，"
                + "并明写「财务确认走独立动作 + 独立权限（trade.purchase.finance）」。"
                + "本模块读它、建本服务的应付单，再把**本服务的应付单号写回**上游。"
                + "**不直接从 warehouse 的 8.2.1.1 收货单推导应付**：收货单没有供应商、没有单价、"
                + "没有币种（它记的是货到了没有），拿它推应付就是蓝图禁止的「仅凭收货统一推导」。"));
    capabilities.add(
        new FinancePayableCapability(
            "10.4.1.1",
            "头程发运单 → 物流费用应付",
            true,
            "经 warehouse 的 GET /warehouse/first-mile-shipments 读（授权 HTTP API，不跨库）",
            "",
            "头程发运单带着**承运商、币种、运费 / 保险费 / 处理费** —— 应付给谁、多少钱、什么币种齐全。"
                + "**关税 estimate 不进应付**（估算 != 账单，真关税属 10.5.1.3 税则口径）。"
                + "10.6.1.1 物流成本分析**不接**：它记的是成本估算与分配口径，不是账单事实。"));
    capabilities.add(
        new FinancePayableCapability(
            "14.3.1.1",
            "活动报表 → 广告费应付",
            true,
            "经 channel 的 GET /channel/ad-reports/rows 读（授权 HTTP API，不跨库）",
            "",
            "平台报回来的**花费（spend）**带币种与平台 ⇒ 应付对象、金额、币种齐全。"
                + "取的是花费不是销售额 —— 销售额是收入侧（12.1.1.1 应收）的事。"));
    capabilities.add(
        new FinancePayableCapability(
            "12.1.2.3",
            "供应商对账单 → 应付",
            false,
            "财务侧供应商对账未交付；采购侧 6.5.1 的对账单不能直接变成应付",
            "12.1.2.3",
            "蓝图 12.1.2.3 写死「**财务是最终确认方，采购不能自行确认应付** —— 这是职责分离」。"
                + "所以即使 6.5.1 采购侧对账单已存在，它也只是**采购侧**的结论，"
                + "没有财务侧的核对与确认就不能变成应付。"
                + "词表里保留 SUPPLIER_STATEMENT 这个取值，12.1.2.3 交付后经授权 API / event 写入即可，不需要改结构。"
                + "**本服务不填 0、也不造一条假对账。**"));
    capabilities.add(
        new FinancePayableCapability(
            "12.3.1.1",
            "平台结算 → 平台费用应付",
            false,
            "上游 12.3.1.1 结算单管理未交付",
            "12.3.1.1",
            "平台账单事实还不存在 ⇒ 平台费用来源**当前不可产生**。"
                + "字段与来源枚举已按「同一张表承接上游」的方式留好，"
                + "12.3.1 交付后经授权 API / event 写入即可，不需要改结构。"
                + "**本服务不填 0、也不造一条假账单。**"));
    capabilities.add(
        new FinancePayableCapability(
            "12.2.2.1",
            "付款单与审批（已付金额的写入口）",
            false,
            "写路径未开放：行级 paid_amount 留好，触发器已把单头已付金额与状态派生好",
            "12.2.2.1",
            "明细行的已付金额是**付款的唯一入口**，12.2.2.1 交付后写它即可，"
                + "单头已付金额、未付余额（生成列）与付款状态由 DB 触发器自动跟上。"
                + "12.2.2.1 未交付 ⇒ **已付金额恒为 0、单头恒为「未付」**（这是事实，不是缺口：钱确实还没付）。"
                + "本模块**不提供**任何改已付金额的端点，也**不提供**任何「手工标记已付」的旁路。"));
    capabilities.add(
        new FinancePayableCapability(
            "12.1.2.2",
            "付款计划（账期 / 里程碑 / 到期提醒 / 资金预测）",
            false,
            "本模块提供到期日与未付余额（按币种分开）",
            "12.1.2.2",
            "付款计划表、到期提醒与资金需求预测属 12.1.2.2；"
                + "12.1.2.2 交付后读同一批表，不需要改结构。"));
    capabilities.add(
        new FinancePayableCapability(
            "6 采购",
            "采购承诺（PO）建模",
            false,
            "本服务不建采购承诺表",
            "6.3.1.1",
            "采购承诺的事实所有者是 6 采购（trade 的采购单）。"
                + "本服务另建一张采购承诺表等于把同一份事实写两遍"
                + "（AGENTS.md：每张事实表只有一个写入所有者）。"
                + "义务类型词表里保留 COMMITMENT 这个取值，但**本服务不产生承诺单**。"));

    gaps.add(
        new FinancePayableDownstreamGap(
            "12.1.2.2",
            "付款计划",
            "finance",
            false,
            "12.1 应收与资金",
            "付款计划、到期提醒与资金预测未交付；到期日与未付余额已在本模块提供。"));
    gaps.add(
        new FinancePayableDownstreamGap(
            "12.1.2.3",
            "供应商对账（财务侧）",
            "finance",
            false,
            "12.1 应收与资金",
            "财务侧核对与最终确认未交付 ⇒ 供应商对账单来源当前不可产生。"));
    gaps.add(
        new FinancePayableDownstreamGap(
            "12.2.2.1",
            "付款单与审批",
            "finance",
            false,
            "12.2 收付款",
            "付款必须精确到**明细行**；行级 paid_amount 已留好，写路径未开放。"));
    gaps.add(
        new FinancePayableDownstreamGap(
            "12.2.2.2",
            "付款执行与回执",
            "finance",
            false,
            "12.2 收付款",
            "付款执行、失败重试与对账未交付。"));
    gaps.add(
        new FinancePayableDownstreamGap(
            "12.3.1.1",
            "结算单管理",
            "finance",
            false,
            "12.3 结算与对账",
            "结算单未交付 ⇒ 平台费用来源当前不可产生。"));
    gaps.add(
        new FinancePayableDownstreamGap(
            "13.1.1.1",
            "利润与经营分析",
            "analytics",
            false,
            "13 利润与经营分析",
            "13 未交付：应付是它的一处上游输入。"));

    return new FinancePayableCapabilities(List.copyOf(capabilities), List.copyOf(gaps));
  }

  // ================================================================ 筛选项

  /** 筛选项下拉：全部来自**真实数据**（distinct 查询），不是写死的词表。 */
  public FinancePayableOptions options() {
    var sources =
        sql.fetch("select distinct source from finance_payable order by source")
            .map(r -> r.get(0, String.class));
    var currencies =
        sql.fetch("select distinct currency from finance_payable order by currency")
            .map(r -> r.get(0, String.class));
    var statuses =
        sql.fetch("select distinct status from finance_payable order by status")
            .map(r -> r.get(0, String.class));
    var counterparties =
        sql.fetch(
                "select distinct counterparty_ref, counterparty_name from finance_payable"
                    + " where counterparty_ref is not null order by counterparty_name, counterparty_ref")
            .map(
                r ->
                    new FinancePayableOptionRef(
                        r.get(0, UUID.class).toString(), r.get(1, String.class)));
    return new FinancePayableOptions(sources, statuses, currencies, counterparties);
  }

  // ================================================================ 列表

  /**
   * 应付单列表：真分页 + 真筛选 + 真排序。
   *
   * <p>⚠️ **汇总覆盖的是整个筛选结果集，不是当前页**：分页一变汇总就变的话，
   * 「翻到第二页看到总应付变小了」会被读成「数据丢了」。
   */
  public FinancePayablePage page(PayableQuery query) {
    int p = Math.max(0, query.page());
    int s = Math.min(MAX_PAGE_SIZE, Math.max(1, query.size()));
    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    applyFilters(query, where, binds);
    String clause = where.isEmpty() ? "" : " where " + String.join(" and ", where);

    long total =
        sql.fetchOne("select count(*) from finance_payable p" + clause, binds.toArray())
            .get(0, Long.class);
    List<FinancePayableRow> items =
        sql.fetch(
                "select * from finance_payable p"
                    + clause
                    + " order by p.due_date, p.id limit ? offset ?",
                concat(binds, s, p * s))
            .map(this::row);

    var summaries = summaryByCurrency(where, binds);
    long overdueCount =
        summaries.stream().mapToLong(FinancePayableCurrencySummary::overdueCount).sum();
    return new FinancePayablePage(items, p, s, total, summaries, overdueCount);
  }

  /**
   * 按币种的汇总。
   *
   * <p>⚠️ **作废的单不计入**：一张已作废的单据在资金口径上不存在，
   * 把它算进「在册应付」会让总额虚高。
   */
  private List<FinancePayableCurrencySummary> summaryByCurrency(
      List<String> where, List<Object> binds) {
    var clauses = new ArrayList<String>();
    clauses.add("p.status <> 'VOID'");
    clauses.addAll(where);
    return sql.fetch(
            "select p.currency,"
                + " sum(p.amount) as amount,"
                + " sum(p.paid_amount) as paid_amount,"
                + " sum(p.unpaid_amount) as unpaid_amount,"
                + " count(*) as cnt,"
                + " count(*) filter (where p.due_date < (now() at time zone 'UTC')::date) as overdue_count,"
                + " coalesce(sum(p.unpaid_amount) filter (where p.due_date < (now() at time zone 'UTC')::date),0) as overdue_amount"
                + " from finance_payable p where "
                + String.join(" and ", clauses)
                + " group by p.currency order by p.currency",
            binds.toArray())
        .map(
            r ->
                new FinancePayableCurrencySummary(
                    r.get("currency", String.class),
                    r.get("amount", BigDecimal.class),
                    r.get("paid_amount", BigDecimal.class),
                    r.get("unpaid_amount", BigDecimal.class),
                    r.get("cnt", Long.class),
                    r.get("overdue_count", Long.class),
                    r.get("overdue_amount", BigDecimal.class)));
  }

  private void applyFilters(PayableQuery q, List<String> where, List<Object> binds) {
    if (q.status() != null) {
      where.add("p.status = ?");
      binds.add(q.status().name());
    }
    if (q.source() != null) {
      where.add("p.source = ?");
      binds.add(q.source().name());
    }
    if (q.currency() != null) {
      where.add("p.currency = ?");
      binds.add(q.currency());
    }
    if (q.counterpartyRef() != null) {
      where.add("p.counterparty_ref = ?");
      binds.add(q.counterpartyRef());
    }
    if (q.overdueOnly() != null && q.overdueOnly()) {
      where.add("p.due_date < (now() at time zone 'UTC')::date");
      where.add("p.status in ('OPEN','PARTIALLY_PAID')");
    }
    if (q.dueFrom() != null) {
      where.add("p.due_date >= ?::date");
      binds.add(q.dueFrom());
    }
    if (q.dueTo() != null) {
      where.add("p.due_date <= ?::date");
      binds.add(q.dueTo());
    }
    if (q.keyword() != null && !q.keyword().isBlank()) {
      where.add(
          "(p.payable_no ilike ? or p.counterparty_name ilike ?"
              + " or p.source_reference ilike ? or p.note ilike ?)");
      String like = "%" + q.keyword().trim() + "%";
      binds.add(like);
      binds.add(like);
      binds.add(like);
      binds.add(like);
    }
  }

  // ================================================================ 详情

  /** 应付单详情：单头 + 明细行 + 事件流。 */
  public FinancePayableDetail detail(UUID id) {
    var head = sql.fetchOne("select * from finance_payable where id = ?", id);
    if (head == null) throw notFound("应付单 " + id + " 不存在或无权查看");
    var lines =
        sql.fetch(
                "select * from finance_payable_line where payable_id = ? order by line_no, id", id)
            .map(
                r ->
                    new FinancePayableLineRow(
                        r.get("id", UUID.class),
                        r.get("line_no", Integer.class),
                        r.get("description", String.class),
                        r.get("sku_code", String.class),
                        r.get("source_reference", String.class),
                        r.get("quantity", BigDecimal.class),
                        r.get("unit_price", BigDecimal.class),
                        r.get("amount", BigDecimal.class),
                        r.get("paid_amount", BigDecimal.class),
                        r.get("payment_status", String.class)));
    var events =
        sql.fetch(
                "select * from finance_payable_event where payable_id = ? order by occurred_at, id", id)
            .map(
                r ->
                    new FinancePayableEventRow(
                        r.get("id", UUID.class),
                        r.get("event_type", String.class),
                        r.get("from_status", String.class),
                        r.get("to_status", String.class),
                        r.get("reason", String.class),
                        r.get("actor_id", UUID.class),
                        r.get("occurred_at", OffsetDateTime.class)));
    return new FinancePayableDetail(row(head), lines, events);
  }

  // ================================================================ 建单

  /**
   * 人工录入一张应付单（建单 + 确认在**同一个事务**里完成）。
   *
   * <p>⚠️ <b>只有 MANUAL / OTHER 能手工建单</b>：采购收货 / 物流费用 / 广告费三个来源
   * <b>只能</b>由上游事实产生（手工录入等于自己编一条上游事实）。
   *
   * <p>⚠️ <b>必须先有启用的确认政策</b>：这是「什么业务确认为应付」的闸门。
   */
  @Transactional
  public FinancePayableRow create(FinancePayableInput input, AccessService.Access actor) {
    PayableSource source = input.source() == null ? PayableSource.MANUAL : input.source();
    if (!source.producible()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "「"
              + source.label()
              + "」来源当前**不可产生**应付（上游归属模块 "
              + ownerModuleOf(source)
              + "）。"
              + "请改用「人工录入」或「其他」，或等该上游交付后由它自动产生");
    }
    if (source != PayableSource.MANUAL && source != PayableSource.OTHER) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "「"
              + source.label()
              + "」来源的应付**只能**由上游事实产生（请用「从上游导入」），"
              + "手工录入等于自己编一条上游事实");
    }
    var policy = requireEnabledPolicy(source, ObligationKind.PAYABLE);

    if (input.lines() == null || input.lines().isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "应付单至少要有一条明细行");
    }
    String currency = requireCurrency(input.currency());
    validateCounterparty(input.counterpartyType(), input.counterpartyName());
    BigDecimal rate = input.exchangeRate();
    String baseCurrency = input.baseCurrency();
    if ((rate == null) != (baseCurrency == null)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "汇率与本位币必须成对出现：要么都填，要么都不填");
    }
    if (rate != null) {
      if (rate.signum() <= 0) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "汇率必须大于 0");
      }
      requireCurrency(baseCurrency);
    }

    OffsetDateTime confirmedAt = OffsetDateTime.now(ZoneOffset.UTC);
    LocalDate confirmedDate = confirmedAt.toLocalDate();
    LocalDate dueDate =
        input.dueDate() == null ? confirmedDate.plusDays(policy.dueDays()) : input.dueDate();
    if (dueDate.isBefore(confirmedDate)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "到期日不能早于确认日");
    }

    UUID id = UUID.randomUUID();
    String no = nextReference("AP");
    sql.execute(
        "insert into finance_payable(id,tenant_id,payable_no,source,source_system,source_reference,"
            + " counterparty_type,counterparty_ref,counterparty_name,currency,"
            + " exchange_rate,base_currency,due_date,confirmed_at,note,status,created_at,updated_at)"
            + " values(?,?,?,?,'manual',null,?,?,?,?,?,?,?::date,?::timestamptz,?,'DRAFT',?::timestamptz,?::timestamptz)",
        id,
        actor.tenantId(),
        no,
        source.name(),
        input.counterpartyType().name(),
        input.counterpartyRef(),
        input.counterpartyName().trim(),
        currency,
        rate,
        baseCurrency,
        dueDate,
        confirmedAt,
        input.note() == null ? "" : input.note(),
        confirmedAt,
        confirmedAt);
    writeEvent(actor.tenantId(), id, "CREATED", "", "DRAFT", "人工录入", actor.userId(), confirmedAt);

    int lineNo = 0;
    for (var line : input.lines()) {
      lineNo++;
      BigDecimal amount = money(line.amount());
      if (amount.signum() <= 0) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "明细行金额必须大于 0");
      }
      BigDecimal quantity = line.quantity() == null ? null : quantity(line.quantity());
      BigDecimal unitPrice = line.unitPrice() == null ? null : money(line.unitPrice());
      if (quantity != null && quantity.signum() <= 0) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "明细行数量必须大于 0");
      }
      sql.execute(
          "insert into finance_payable_line(id,tenant_id,payable_id,line_no,description,sku_code,"
              + " source_reference,quantity,unit_price,amount)"
              + " values(?,?,?,?,?,?,?,?,?,?)",
          UUID.randomUUID(),
          actor.tenantId(),
          id,
          lineNo,
          line.description() == null ? "" : line.description(),
          line.skuCode() == null ? "" : line.skuCode(),
          blankToNull(line.sourceReference()),
          quantity,
          unitPrice,
          amount);
    }

    confirm(actor.tenantId(), id, actor.userId(), confirmedAt);
    return detail(id).payable();
  }

  /** 确认：草稿 → 未付。DB 触发器在这里校验「至少一条明细行」并把金额派生到单头。 */
  private void confirm(UUID tenantId, UUID id, UUID userId, OffsetDateTime at) {
    sql.execute(
        "update finance_payable set status='OPEN', updated_at=?::timestamptz, version=version+1"
            + " where id = ?",
        at,
        id);
    writeEvent(tenantId, id, "CONFIRMED", "DRAFT", "OPEN", "", userId, at);
  }

  // ================================================================ 导入

  /**
   * 从上游 **6.3.2.2 到货跟踪的应付部分确认**导入应付（幂等）。
   *
   * <p>⚠️ **幂等**：幂等键是 {@code (source, source_reference)} = ('PURCHASE_RECEIPT', 上游确认单号)，
   * 重复导入命中同一条，不会长出第二张单。
   *
   * <p>⚠️ **上游读不到就整体失败**（503），绝不返回「导入 0 条」冒充「上游没有数据」。
   *
   * <p>⚠️ **写回确认**：建完应付单后把**本服务的应付单号**写回上游（上游的独立权限
   * {@code trade.purchase.finance}）。写回失败**不吞掉**：本单的应付单号会出现在告警里，
   * 重新导入时会被补写（上游仍是「待财务确认」就会再写一次）。
   */
  @Transactional
  public FinancePayableImportResult importFromPurchaseArrivals(
      String bearer, AccessService.Access actor) {
    var policy = requireEnabledPolicy(PayableSource.PURCHASE_RECEIPT, ObligationKind.PAYABLE);
    int scanned = 0;
    int imported = 0;
    int skipped = 0;
    boolean truncated = false;
    var rows = new ArrayList<FinancePayableRow>();
    var warnings = new ArrayList<String>();

    for (int page = 0; page < PurchaseArrivalPort.MAX_PAGES; page++) {
      var upstream = arrivals.arrivals(page, bearer);
      if (upstream.rows().isEmpty()) break;
      scanned += upstream.rows().size();
      for (var arrival : upstream.rows()) {
        var detail = arrivals.arrival(arrival.id(), bearer);
        var pending = PurchaseArrivalPort.pendingConfirmations(detail);
        if (pending.isEmpty()) {
          skipped++;
          continue;
        }
        for (var confirmation : pending) {
          if (confirmation.confirmationNo() == null || confirmation.confirmationNo().isBlank()) {
            skipped++;
            warnings.add("上游有一条应付部分确认没有单号，已跳过（无法确定幂等键）");
            continue;
          }
          var fact = PurchaseArrivalPort.toFact(arrival, confirmation);
          if (fact.amount() == null || fact.amount().signum() <= 0) {
            skipped++;
            warnings.add(
                "上游确认单 " + confirmation.confirmationNo() + " 的金额不是正数，已跳过（不造 0 元应付）");
            continue;
          }
          if (fact.unitPrice() == null || fact.quantity() == null) {
            skipped++;
            warnings.add(
                "上游确认单 "
                    + confirmation.confirmationNo()
                    + " 缺单价快照或数量，已跳过：没有合同依据就不能确认应付"
                    + "（蓝图：确认依据由业务合同和已批准政策决定，不能仅凭收货统一推导）");
            continue;
          }
          var existing =
              sql.fetchOne(
                  "select id, payable_no from finance_payable where source = ? and source_reference = ?",
                  PayableSource.PURCHASE_RECEIPT.name(),
                  confirmation.confirmationNo());
          if (existing != null) {
            skipped++;
            // 上游仍是「待财务确认」⇒ 上次没写回成功，这次补写。
            tryConfirmUpstream(arrival.id(), confirmation.id(), existing.get("payable_no", String.class), bearer, warnings);
            continue;
          }
          var created =
              createFromFact(
                  PayableSource.PURCHASE_RECEIPT, "trade", fact, policy.dueDays(), actor, "IMPORTED");
          rows.add(created);
          imported++;
          tryConfirmUpstream(arrival.id(), confirmation.id(), created.payableNo(), bearer, warnings);
        }
      }
      if (upstream.rows().size() < PurchaseArrivalPort.PAGE_SIZE) break;
      if (page == PurchaseArrivalPort.MAX_PAGES - 1) truncated = true;
    }
    if (truncated) {
      warnings.add(
          "上游翻页触到上限（"
              + (PurchaseArrivalPort.MAX_PAGES * PurchaseArrivalPort.PAGE_SIZE)
              + " 条），本次只扫描了前 "
              + scanned
              + " 条");
    }
    if (!warnings.isEmpty()) {
      logger.warn("[finance] 导入到货应付有 {} 条未进入：{}", warnings.size(), warnings);
    }
    return new FinancePayableImportResult(scanned, imported, skipped, truncated, rows, warnings);
  }

  /** 写回上游的「财务已确认」；失败不吞掉 —— 告警里写明是哪一笔。 */
  private void tryConfirmUpstream(
      UUID arrivalId, UUID confirmationId, String payableNo, String bearer, List<String> warnings) {
    try {
      arrivals.confirmPayable(arrivalId, confirmationId, payableNo, bearer);
    } catch (org.springframework.web.server.ResponseStatusException e) {
      warnings.add(
          "应付单 "
              + payableNo
              + " 已建好，但写回上游的财务确认失败："
              + e.getReason()
              + "（重新导入会补写）");
    }
  }

  /**
   * 从上游 **10.4.1.1 头程发运单**导入物流费用应付（幂等）。
   *
   * <p>⚠️ **上游读不到就整体失败**（503）。
   */
  @Transactional
  public FinancePayableImportResult importFromFirstMile(
      String bearer, AccessService.Access actor) {
    var policy = requireEnabledPolicy(PayableSource.LOGISTICS_FEE, ObligationKind.PAYABLE);
    int scanned = 0;
    int imported = 0;
    int skipped = 0;
    boolean truncated = false;
    var rows = new ArrayList<FinancePayableRow>();
    var warnings = new ArrayList<String>();

    for (int page = 0; page < FirstMilePort.MAX_PAGES; page++) {
      var upstream = firstMile.shipments(page, bearer);
      if (upstream.rows().isEmpty()) break;
      scanned += upstream.rows().size();
      for (var shipment : upstream.rows()) {
        var fact = FirstMilePort.toFact(shipment);
        if (fact == null) {
          skipped++;
          warnings.add(
              "头程发运单 "
                  + shipment.shipmentCode()
                  + " 没有承运商或三项费用全为 0，已跳过（不造 0 元应付）");
          continue;
        }
        var existing =
            sql.fetchOne(
                "select id from finance_payable where source = ? and source_reference = ?",
                PayableSource.LOGISTICS_FEE.name(),
                shipment.shipmentCode());
        if (existing != null) {
          skipped++;
          continue;
        }
        rows.add(
            createFromFact(
                PayableSource.LOGISTICS_FEE, "warehouse", fact, policy.dueDays(), actor, "IMPORTED"));
        imported++;
      }
      if (upstream.rows().size() < FirstMilePort.PAGE_SIZE) break;
      if (page == FirstMilePort.MAX_PAGES - 1) truncated = true;
    }
    if (truncated) {
      warnings.add(
          "上游翻页触到上限（"
              + (FirstMilePort.MAX_PAGES * FirstMilePort.PAGE_SIZE)
              + " 单），本次只扫描了前 "
              + scanned
              + " 单");
    }
    if (!warnings.isEmpty()) {
      logger.warn("[finance] 导入物流费用应付有 {} 条未进入：{}", warnings.size(), warnings);
    }
    return new FinancePayableImportResult(scanned, imported, skipped, truncated, rows, warnings);
  }

  /**
   * 从上游 **14.3.1.1 活动报表**导入广告费应付（幂等）。
   *
   * <p>⚠️ **上游读不到就整体失败**（503）。
   */
  @Transactional
  public FinancePayableImportResult importFromAdSpend(String bearer, AccessService.Access actor) {
    var policy = requireEnabledPolicy(PayableSource.ADVERTISING_FEE, ObligationKind.PAYABLE);
    int scanned = 0;
    int imported = 0;
    int skipped = 0;
    boolean truncated = false;
    var rows = new ArrayList<FinancePayableRow>();
    var warnings = new ArrayList<String>();

    for (int page = 0; page < AdSpendPort.MAX_PAGES; page++) {
      var upstream = adSpend.rows(page, bearer);
      if (upstream.items().isEmpty()) break;
      scanned += upstream.items().size();
      for (var row : upstream.items()) {
        var fact = AdSpendPort.toFact(row);
        if (fact == null) {
          skipped++;
          warnings.add("上游有一条广告花费没有金额或币种，已跳过（不造 0 元应付）");
          continue;
        }
        var existing =
            sql.fetchOne(
                "select id from finance_payable where source = ? and source_reference = ?",
                PayableSource.ADVERTISING_FEE.name(),
                fact.sourceReference());
        if (existing != null) {
          skipped++;
          continue;
        }
        rows.add(
            createFromFact(
                PayableSource.ADVERTISING_FEE, "channel", fact, policy.dueDays(), actor, "IMPORTED"));
        imported++;
      }
      if (upstream.items().size() < AdSpendPort.PAGE_SIZE) break;
      if (page == AdSpendPort.MAX_PAGES - 1) truncated = true;
    }
    if (truncated) {
      warnings.add(
          "上游翻页触到上限（"
              + (AdSpendPort.MAX_PAGES * AdSpendPort.PAGE_SIZE)
              + " 行），本次只扫描了前 "
              + scanned
              + " 行");
    }
    if (!warnings.isEmpty()) {
      logger.warn("[finance] 导入广告费应付有 {} 条未进入：{}", warnings.size(), warnings);
    }
    return new FinancePayableImportResult(scanned, imported, skipped, truncated, rows, warnings);
  }

  /**
   * 把一条上游事实落成本服务的应付单（**三个来源共用这一条路径**）。
   *
   * <p>⚠️ 金额、币种、数量、单价**全部取上游的值**，本模块一个都不自己算 ——
   * 上游的金额是按「单价快照 × 到货数量」算出来的，改动它就是篡改依据。
   */
  private FinancePayableRow createFromFact(
      PayableSource source,
      String sourceSystem,
      PayableSourceFact fact,
      int dueDays,
      AccessService.Access actor,
      String eventType) {
    BigDecimal amount = money(fact.amount());
    if (amount.signum() <= 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "上游事实的金额必须大于 0");
    }
    String currency = requireCurrency(fact.currency());
    if (fact.counterpartyName() == null || fact.counterpartyName().isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "上游事实没有应付对象名称，无法建应付单");
    }
    OffsetDateTime confirmedAt =
        fact.occurredAt() == null ? OffsetDateTime.now(ZoneOffset.UTC) : fact.occurredAt();
    LocalDate dueDate = confirmedAt.toLocalDate().plusDays(dueDays);

    UUID id = UUID.randomUUID();
    String no = nextReference("AP");
    sql.execute(
        "insert into finance_payable(id,tenant_id,payable_no,source,source_system,source_reference,"
            + " counterparty_type,counterparty_ref,counterparty_name,currency,"
            + " exchange_rate,base_currency,due_date,confirmed_at,note,status,created_at,updated_at)"
            + " values(?,?,?,?,?,?,?,?,?,?,null,null,?::date,?::timestamptz,?,'DRAFT',?::timestamptz,?::timestamptz)",
        id,
        actor.tenantId(),
        no,
        source.name(),
        sourceSystem,
        fact.sourceReference(),
        fact.counterpartyType().name(),
        fact.counterpartyRef(),
        fact.counterpartyName(),
        currency,
        dueDate,
        confirmedAt,
        fact.note() == null ? "" : fact.note(),
        confirmedAt,
        confirmedAt);
    writeEvent(actor.tenantId(), id, "CREATED", "", "DRAFT", "上游事实导入", actor.userId(), confirmedAt);

    sql.execute(
        "insert into finance_payable_line(id,tenant_id,payable_id,line_no,description,sku_code,"
            + " source_reference,quantity,unit_price,amount)"
            + " values(?,?,?,1,?,'',?,?,?,?)",
        UUID.randomUUID(),
        actor.tenantId(),
        id,
        fact.description() == null ? "" : fact.description(),
        fact.sourceReference(),
        fact.quantity() == null ? null : quantity(fact.quantity()),
        fact.unitPrice() == null ? null : money(fact.unitPrice()),
        amount);

    confirm(actor.tenantId(), id, actor.userId(), confirmedAt);
    writeEvent(actor.tenantId(), id, eventType, "OPEN", "OPEN", "上游单号 " + fact.sourceReference(), actor.userId(), confirmedAt);
    return detail(id).payable();
  }

  // ================================================================ 作废

  /**
   * 作废一张应付单（红冲的等价物）：终态，整单冻结。
   *
   * <p>⚠️ **已付（哪怕只付了一部分）的应付不能作废** —— 那笔钱已经动过，
   * 要冲销得走 12.2.2.1 付款与核销域（带审批与依据链）。
   */
  @Transactional
  public FinancePayableRow voidPayable(
      UUID id, FinancePayableVoidInput input, AccessService.Access actor) {
    var head = sql.fetchOne("select * from finance_payable where id = ?", id);
    if (head == null) throw notFound("应付单 " + id + " 不存在或无权操作");
    String reason = input == null || input.reason() == null ? "" : input.reason().trim();
    if (reason.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "作废必须写明理由");
    }
    String status = head.get("status", String.class);
    if (PayableStatus.valueOf(status).terminal()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "应付单已是终态（" + status + "），不可再作废");
    }
    if (head.get("paid_amount", BigDecimal.class).signum() > 0) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "这张应付已付 "
              + head.get("paid_amount", BigDecimal.class).toPlainString()
              + "，不能作废：冲减已付款的应付要走 12.2.2.1 付款与核销域（带审批与依据链）");
    }
    OffsetDateTime at = OffsetDateTime.now(ZoneOffset.UTC);
    sql.execute(
        "update finance_payable set status='VOID', updated_at=?::timestamptz, version=version+1"
            + " where id = ?",
        at,
        id);
    writeEvent(actor.tenantId(), id, "VOIDED", status, "VOID", reason, actor.userId(), at);
    return detail(id).payable();
  }

  // ================================================================ 未付清单

  /**
   * 未付清单：12.1.2.1 的输出之一（「应付单 + 明细 + 未付清单」）。
   *
   * <p>⚠️ **按币种分开**：USD 与 EUR 加起来不是任何一个金额。
   * ⚠️ **只列未付清的**：已付清与已作废的单不在清单里 —— 清单是「还要付什么」。
   */
  public FinancePayableUnpaidList unpaid() {
    LocalDate asOf = LocalDate.now(ZoneOffset.UTC);
    var items =
        sql.fetch(
                "select id, payable_no, source, counterparty_type, counterparty_name, currency,"
                    + " amount, paid_amount, unpaid_amount, due_date, status"
                    + " from finance_payable"
                    + " where status in ('OPEN','PARTIALLY_PAID')"
                    + " order by due_date, id")
            .map(
                r ->
                    new FinancePayableUnpaidItem(
                        r.get("id", UUID.class),
                        r.get("payable_no", String.class),
                        PayableSource.valueOf(r.get("source", String.class)),
                        CounterpartyType.valueOf(r.get("counterparty_type", String.class)),
                        r.get("counterparty_name", String.class),
                        r.get("currency", String.class),
                        r.get("amount", BigDecimal.class),
                        r.get("paid_amount", BigDecimal.class),
                        r.get("unpaid_amount", BigDecimal.class),
                        r.get("due_date", LocalDate.class),
                        Math.max(0, java.time.temporal.ChronoUnit.DAYS.between(r.get("due_date", LocalDate.class), asOf)),
                        PayableStatus.valueOf(r.get("status", String.class))));
    var summaries =
        sql.fetch(
                "select currency, sum(amount) as amount, sum(paid_amount) as paid_amount,"
                    + " sum(unpaid_amount) as unpaid_amount, count(*) as cnt,"
                    + " count(*) filter (where due_date < (now() at time zone 'UTC')::date) as overdue_count,"
                    + " coalesce(sum(unpaid_amount) filter (where due_date < (now() at time zone 'UTC')::date),0) as overdue_amount"
                    + " from finance_payable where status in ('OPEN','PARTIALLY_PAID')"
                    + " group by currency order by currency")
            .map(
                r ->
                    new FinancePayableCurrencySummary(
                        r.get("currency", String.class),
                        r.get("amount", BigDecimal.class),
                        r.get("paid_amount", BigDecimal.class),
                        r.get("unpaid_amount", BigDecimal.class),
                        r.get("cnt", Long.class),
                        r.get("overdue_count", Long.class),
                        r.get("overdue_amount", BigDecimal.class)));
    return new FinancePayableUnpaidList(asOf, summaries, items, items.size());
  }

  // ================================================================ 政策

  /**
   * 政策列表：**每个（来源 × 义务类型）都列**，没配过的也列（configured=false）。
   *
   * <p>「未配置」与「已禁用」是两件事，界面上必须能分开：前者意味着「你还没决定」，
   * 后者意味着「你决定了这个来源不产生应付」。
   */
  public List<FinancePayablePolicyView> policies() {
    var configured = new LinkedHashMap<String, Record>();
    for (var r : sql.fetch("select * from finance_payable_policy")) {
      configured.put(r.get("source", String.class) + "|" + r.get("obligation_kind", String.class), r);
    }
    var out = new ArrayList<FinancePayablePolicyView>();
    for (var source : PayableSource.values()) {
      for (var kind : new ObligationKind[] {ObligationKind.PAYABLE, ObligationKind.PREPAYMENT}) {
        var row = configured.get(source.name() + "|" + kind.name());
        out.add(
            new FinancePayablePolicyView(
                source,
                source.label(),
                kind,
                kind.label(),
                row != null,
                row != null && row.get("enabled", Boolean.class),
                row == null ? 0 : row.get("due_days", Integer.class),
                row == null ? "" : row.get("note", String.class),
                ownerModuleOf(source),
                source.producible(),
                detailOf(source, kind, row)));
      }
    }
    // 采购承诺：词表保留，但**本服务不建模**，明确写成不可产生。
    out.add(
        new FinancePayablePolicyView(
            PayableSource.MANUAL,
            "采购承诺",
            ObligationKind.COMMITMENT,
            ObligationKind.COMMITMENT.label(),
            false,
            false,
            0,
            "",
            "6.3.1.1",
            false,
            "采购承诺的事实所有者是 6 采购（trade 的采购单），**本服务不建模、也不产生承诺单**。"
                + "另建一张采购承诺表等于把同一份事实写两遍。"));
    return out;
  }

  /** 配置一个（来源 × 义务类型）的确认政策（幂等 upsert）。 */
  @Transactional
  public FinancePayablePolicyRow updatePolicy(
      PayableSource source,
      ObligationKind kind,
      FinancePayablePolicyInput input,
      AccessService.Access actor) {
    if (source == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "必须指定来源");
    }
    if (kind == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "必须指定义务类型");
    }
    if (kind == ObligationKind.COMMITMENT) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "采购承诺由 6 采购（trade 的采购单）持有，本服务不建模 ⇒ 不可配置应付政策");
    }
    boolean enabled = input.enabled() == null || input.enabled();
    int dueDays = input.dueDays() == null ? 0 : input.dueDays();
    if (dueDays < 0 || dueDays > 365) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "账期天数必须在 0 到 365 之间");
    }
    String note = input.note() == null ? "" : input.note();
    OffsetDateTime at = OffsetDateTime.now(ZoneOffset.UTC);
    sql.execute(
        "insert into finance_payable_policy(id,tenant_id,source,obligation_kind,enabled,due_days,note,"
            + " owner_module,created_at,updated_at)"
            + " values(?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz)"
            + " on conflict (tenant_id, source, obligation_kind) do update set enabled=excluded.enabled,"
            + " due_days=excluded.due_days, note=excluded.note, updated_at=excluded.updated_at",
        UUID.randomUUID(),
        actor.tenantId(),
        source.name(),
        kind.name(),
        enabled,
        dueDays,
        note,
        ownerModuleOf(source),
        at,
        at);
    var row =
        sql.fetchOne(
            "select * from finance_payable_policy where source = ? and obligation_kind = ?",
            source.name(),
            kind.name());
    return new FinancePayablePolicyRow(
        row.get("id", UUID.class),
        source,
        kind,
        row.get("enabled", Boolean.class),
        row.get("due_days", Integer.class),
        row.get("note", String.class),
        row.get("owner_module", String.class),
        row.get("created_at", OffsetDateTime.class),
        row.get("updated_at", OffsetDateTime.class));
  }

  /**
   * 取一个（来源 × 义务类型）的**已启用**政策；没有就拒绝。
   *
   * <p>这就是「确认依据由已批准政策决定」的闸门：没有它，这个来源**一张单都产生不了**。
   */
  private FinancePayablePolicyRow requireEnabledPolicy(PayableSource source, ObligationKind kind) {
    var row =
        sql.fetchOne(
            "select * from finance_payable_policy where source = ? and obligation_kind = ?",
            source.name(),
            kind.name());
    if (row == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "「"
              + source.label()
              + "」还没有配置应付确认政策：这个来源**现在一张应付都产生不了**。"
              + "请先在「应付政策」里启用它并设定账期天数 —— 什么业务确认为应付由租户决定，"
              + "系统不会替用户默认一个");
    }
    if (!row.get("enabled", Boolean.class)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "「" + source.label() + "」的应付确认政策当前是**禁用**的，这个来源不产生应付");
    }
    return new FinancePayablePolicyRow(
        row.get("id", UUID.class),
        source,
        kind,
        true,
        row.get("due_days", Integer.class),
        row.get("note", String.class),
        row.get("owner_module", String.class),
        row.get("created_at", OffsetDateTime.class),
        row.get("updated_at", OffsetDateTime.class));
  }

  private String ownerModuleOf(PayableSource source) {
    return switch (source) {
      case PURCHASE_RECEIPT -> "6.3.2.2";
      case LOGISTICS_FEE -> "10.4.1.1";
      case ADVERTISING_FEE -> "14.3.1.1";
      case SUPPLIER_STATEMENT -> "12.1.2.3";
      case PLATFORM_FEE -> "12.3.1.1";
      default -> "";
    };
  }

  private String detailOf(PayableSource source, ObligationKind kind, Record row) {
    if (kind == ObligationKind.COMMITMENT) {
      return "采购承诺由 6 采购持有，本服务不建模。";
    }
    if (!source.producible()) {
      return "上游是 "
          + ownerModuleOf(source)
          + "（**未交付**）：这个来源现在一张应付都产生不了。"
          + "配置它也不会让上游事实凭空出现 —— 等该上游交付后由它产生。";
    }
    if (row == null) return "未配置：启用后这个来源才可以产生应付。";
    if (!row.get("enabled", Boolean.class)) return "已禁用：这个来源当前不产生应付。";
    int days = row.get("due_days", Integer.class);
    return "已启用：到期日 = 确认日 + " + days + " 天" + (days == 0 ? "（确认即到期）" : "") + "。";
  }

  // ================================================================ 预付款（独立聚合）

  /** 预付款列表：真分页 + 真筛选。 */
  public FinancePrepaymentPage prepayments(
      PrepaymentStatus status, String currency, UUID counterpartyRef, String keyword, int page, int size) {
    int p = Math.max(0, page);
    int s = Math.min(MAX_PAGE_SIZE, Math.max(1, size));
    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    if (status != null) {
      where.add("status = ?");
      binds.add(status.name());
    }
    if (currency != null && !currency.isBlank()) {
      where.add("currency = ?");
      binds.add(currency);
    }
    if (counterpartyRef != null) {
      where.add("counterparty_ref = ?");
      binds.add(counterpartyRef);
    }
    if (keyword != null && !keyword.isBlank()) {
      where.add("(prepayment_no ilike ? or counterparty_name ilike ? or source_reference ilike ?)");
      String like = "%" + keyword.trim() + "%";
      binds.add(like);
      binds.add(like);
      binds.add(like);
    }
    String clause = where.isEmpty() ? "" : " where " + String.join(" and ", where);
    long total =
        sql.fetchOne("select count(*) from finance_prepayment" + clause, binds.toArray())
            .get(0, Long.class);
    var items =
        sql.fetch(
                "select * from finance_prepayment"
                    + clause
                    + " order by expected_paid_date, id limit ? offset ?",
                concat(binds, s, p * s))
            .map(this::prepaymentRow);
    var unallocated = new ArrayList<FinancePrepaymentCurrencyTotal>();
    for (var r :
        sql.fetch(
            "select currency, coalesce(sum(unallocated_amount),0) as unallocated from finance_prepayment"
                + " where status <> 'VOID' group by currency order by currency")) {
      unallocated.add(
          new FinancePrepaymentCurrencyTotal(
              r.get("currency", String.class), r.get("unallocated", BigDecimal.class)));
    }
    return new FinancePrepaymentPage(items, p, s, total, unallocated);
  }

  /** 预付款详情：单头 + 抵扣明细。 */
  public FinancePrepaymentDetail prepaymentDetail(UUID id) {
    var head = sql.fetchOne("select * from finance_prepayment where id = ?", id);
    if (head == null) throw notFound("预付款 " + id + " 不存在或无权查看");
    var allocations =
        sql.fetch(
                "select a.*, p.payable_no from finance_prepayment_allocation a"
                    + " left join finance_payable p on p.tenant_id = a.tenant_id and p.id = a.payable_id"
                    + " where a.prepayment_id = ? order by a.allocated_at, a.id",
                id)
            .map(
                r ->
                    new FinancePrepaymentAllocationRow(
                        r.get("id", UUID.class),
                        PrepaymentTargetType.valueOf(r.get("target_type", String.class)),
                        r.get("payable_id", UUID.class),
                        r.get("payable_no", String.class),
                        r.get("target_reference", String.class),
                        r.get("amount", BigDecimal.class),
                        r.get("allocated_at", OffsetDateTime.class),
                        r.get("note", String.class)));
    return new FinancePrepaymentDetail(prepaymentRow(head), allocations);
  }

  /**
   * 登记一笔预付款（**独立聚合**：不是应付单，也**不是**付款）。
   *
   * <p>⚠️ 本端点**不产生任何「已付款」回执**：它登记的是「这笔钱预付给谁、约定什么时候付」，
   * 实际付款走 12.2.2.1（未交付）。它也**不改**任何应付单的已付金额。
   */
  @Transactional
  public FinancePrepaymentRow createPrepayment(
      FinancePrepaymentInput input, AccessService.Access actor) {
    requireEnabledPolicy(PayableSource.MANUAL, ObligationKind.PREPAYMENT);
    String currency = requireCurrency(input.currency());
    validateCounterparty(input.counterpartyType(), input.counterpartyName());
    BigDecimal amount = money(input.amount());
    if (amount.signum() <= 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "预付金额必须大于 0");
    }
    if (input.expectedPaidDate() == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "必须写明预计付款日");
    }
    UUID id = UUID.randomUUID();
    String no = nextReference("PP");
    OffsetDateTime at = OffsetDateTime.now(ZoneOffset.UTC);
    sql.execute(
        "insert into finance_prepayment(id,tenant_id,prepayment_no,counterparty_type,counterparty_ref,"
            + " counterparty_name,currency,amount,allocated_amount,status,expected_paid_date,"
            + " source_reference,note,version,created_at,updated_at)"
            + " values(?,?,?,?,?,?,?,?,0,'OPEN',?::date,?,?,0,?::timestamptz,?::timestamptz)",
        id,
        actor.tenantId(),
        no,
        input.counterpartyType().name(),
        input.counterpartyRef(),
        input.counterpartyName().trim(),
        currency,
        amount,
        input.expectedPaidDate(),
        blankToNull(input.sourceReference()),
        input.note() == null ? "" : input.note(),
        at,
        at);
    return prepaymentRow(sql.fetchOne("select * from finance_prepayment where id = ?", id));
  }

  /**
   * 登记一笔预付款抵扣。
   *
   * <p>🔴 **抵扣不改应付单的已付金额**：应付的「已付」只有一个来源 —— 12.2.2.1 付款写明细行。
   * 抵扣在这里只是**预付款的账**（这笔预付款花到哪里了），它给付款模块当输入用。
   */
  @Transactional
  public FinancePrepaymentDetail allocatePrepayment(
      UUID prepaymentId, FinancePrepaymentAllocationInput input, AccessService.Access actor) {
    var head = sql.fetchOne("select * from finance_prepayment where id = ?", prepaymentId);
    if (head == null) throw notFound("预付款 " + prepaymentId + " 不存在或无权操作");
    String status = head.get("status", String.class);
    if (PrepaymentStatus.valueOf(status) == PrepaymentStatus.VOID) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "预付款已作废，不可再抵扣");
    }
    if (input.targetType() == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "必须指定抵扣目标类型");
    }
    BigDecimal amount = money(input.amount());
    if (amount.signum() <= 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "抵扣金额必须大于 0");
    }
    if (input.targetType() == PrepaymentTargetType.PAYABLE) {
      if (input.payableId() == null) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "抵扣到应付单时必须指定应付单");
      }
      if (input.targetReference() != null && !input.targetReference().isBlank()) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "抵扣到应付单时不要再填采购单号（两种目标各写各的）");
      }
      var payable = sql.fetchOne("select id from finance_payable where id = ?", input.payableId());
      if (payable == null) throw notFound("应付单 " + input.payableId() + " 不存在或无权操作");
    } else {
      if (input.payableId() != null) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "抵扣到采购单时不要指定应付单");
      }
      if (input.targetReference() == null || input.targetReference().isBlank()) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "抵扣到采购单时必须写明采购单号 / 合同号");
      }
    }
    BigDecimal unallocated = head.get("unallocated_amount", BigDecimal.class);
    if (amount.compareTo(unallocated) > 0) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "抵扣金额 " + amount.toPlainString() + " 超过可抵扣余额 " + unallocated.toPlainString());
    }
    OffsetDateTime at = OffsetDateTime.now(ZoneOffset.UTC);
    sql.execute(
        "insert into finance_prepayment_allocation(id,tenant_id,prepayment_id,target_type,payable_id,"
            + " target_reference,amount,allocated_at,note)"
            + " values(?,?,?,?,?,?,?,?::timestamptz,?)",
        UUID.randomUUID(),
        actor.tenantId(),
        prepaymentId,
        input.targetType().name(),
        input.targetType() == PrepaymentTargetType.PAYABLE ? input.payableId() : null,
        input.targetType() == PrepaymentTargetType.PURCHASE_ORDER
            ? input.targetReference().trim()
            : null,
        amount,
        at,
        input.note() == null ? "" : input.note());
    return prepaymentDetail(prepaymentId);
  }

  /**
   * 作废预付款。
   *
   * <p>⚠️ **已有抵扣的预付款不可作废**：撤销抵扣属 12.2.2.1 付款与核销域（未交付），
   * 本模块不代它开撤销形状 —— 宁可少一个按钮，也不要一条改不回去的账。
   */
  @Transactional
  public FinancePrepaymentRow voidPrepayment(
      UUID id, FinancePrepaymentVoidInput input, AccessService.Access actor) {
    var head = sql.fetchOne("select * from finance_prepayment where id = ?", id);
    if (head == null) throw notFound("预付款 " + id + " 不存在或无权操作");
    String reason = input == null || input.reason() == null ? "" : input.reason().trim();
    if (reason.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "作废必须写明理由");
    }
    String status = head.get("status", String.class);
    if (status.equals("VOID") || status.equals("ALLOCATED")) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "预付款已是终态（" + status + "），不可再作废");
    }
    if (head.get("allocated_amount", BigDecimal.class).signum() > 0) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "这笔预付款已有抵扣，不能作废：撤销抵扣属 12.2.2.1 付款与核销域（未交付），本模块不代它开撤销形状");
    }
    OffsetDateTime at = OffsetDateTime.now(ZoneOffset.UTC);
    sql.execute(
        "update finance_prepayment set status='VOID', updated_at=?::timestamptz, version=version+1"
            + " where id = ?",
        at,
        id);
    return prepaymentRow(sql.fetchOne("select * from finance_prepayment where id = ?", id));
  }

  // ================================================================ 工具

  private void writeEvent(
      UUID tenantId,
      UUID payableId,
      String type,
      String from,
      String to,
      String reason,
      UUID actorId,
      OffsetDateTime at) {
    sql.execute(
        "insert into finance_payable_event(id,tenant_id,payable_id,event_type,from_status,"
            + " to_status,reason,actor_id,occurred_at) values(?,?,?,?,?,?,?,?,?::timestamptz)",
        UUID.randomUUID(),
        tenantId,
        payableId,
        type,
        from,
        to,
        reason == null ? "" : reason,
        actorId,
        at);
  }

  private FinancePayableRow row(Record r) {
    return new FinancePayableRow(
        r.get("id", UUID.class),
        r.get("payable_no", String.class),
        PayableSource.valueOf(r.get("source", String.class)),
        r.get("source_system", String.class),
        r.get("source_reference", String.class),
        CounterpartyType.valueOf(r.get("counterparty_type", String.class)),
        r.get("counterparty_ref", UUID.class),
        r.get("counterparty_name", String.class),
        r.get("currency", String.class),
        r.get("amount", BigDecimal.class),
        r.get("exchange_rate", BigDecimal.class),
        r.get("base_currency", String.class),
        r.get("base_amount", BigDecimal.class),
        r.get("paid_amount", BigDecimal.class),
        r.get("unpaid_amount", BigDecimal.class),
        PayableStatus.valueOf(r.get("status", String.class)),
        r.get("due_date", LocalDate.class),
        r.get("confirmed_at", OffsetDateTime.class),
        r.get("note", String.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  private FinancePrepaymentRow prepaymentRow(Record r) {
    return new FinancePrepaymentRow(
        r.get("id", UUID.class),
        r.get("prepayment_no", String.class),
        CounterpartyType.valueOf(r.get("counterparty_type", String.class)),
        r.get("counterparty_ref", UUID.class),
        r.get("counterparty_name", String.class),
        r.get("currency", String.class),
        r.get("amount", BigDecimal.class),
        r.get("allocated_amount", BigDecimal.class),
        r.get("unallocated_amount", BigDecimal.class),
        PrepaymentStatus.valueOf(r.get("status", String.class)),
        r.get("expected_paid_date", LocalDate.class),
        r.get("source_reference", String.class),
        r.get("note", String.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  private static String nextReference(String prefix) {
    return prefix
        + "-"
        + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
  }

  private static BigDecimal money(BigDecimal value) {
    return value == null ? null : value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
  }

  private static BigDecimal quantity(BigDecimal value) {
    return value.setScale(QUANTITY_SCALE, RoundingMode.HALF_UP);
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  private static String requireCurrency(String currency) {
    if (currency == null || !currency.matches("^[A-Z]{3}$")) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "币种必须是三个大写字母（如 USD）");
    }
    return currency;
  }

  private static void validateCounterparty(CounterpartyType type, String name) {
    if (type == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "必须指定应付对象类型");
    }
    if (name == null || name.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "必须写明应付对象名称");
    }
  }

  private static ResponseStatusException notFound(String detail) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, detail);
  }

  private static Object[] concat(List<Object> binds, Object... tail) {
    var out = new Object[binds.size() + tail.length];
    for (int i = 0; i < binds.size(); i++) out[i] = binds.get(i);
    System.arraycopy(tail, 0, out, binds.size(), tail.length);
    return out;
  }
}
