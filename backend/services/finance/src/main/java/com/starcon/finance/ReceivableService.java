package com.starcon.finance;

import static com.starcon.finance.ReceivableModels.*;

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
 * 12.1.1.1 应收单管理。
 *
 * <p>蓝图 12-finance.md：「管理应收账款：来源（平台结算、平台代扣、B2B 客户账期、其他）、 应收对象、金额与币种、到期日、账龄、明细核销状态。」输出：「应收单 + 明细行 +
 * 账龄分布； 生成催收任务。」
 *
 * <p><b>本模块的三条设计主线</b>：
 *
 * <ol>
 *   <li><b>「什么业务确认为应收」由租户配置，不由系统推导。</b> 每个来源都要有一份**启用的**确认政策（{@code
 *       finance_receivable_policy}）才可能产生应收； 没有政策 ⇒ 该来源一张应收都产生不了，服务层返回一句能直接展示的话。
 *       <b>未发货订单永远不产生应收</b>：本服务没有任何一条「订单存在就建应收」的路径。
 *   <li><b>金额是唯一的，状态是派生的。</b> 单头金额 = 明细合计（DB 触发器派生）；单头核销状态 = 明细核销金额（DB 触发器派生）； 行核销状态 =
 *       行核销金额（生成列）。所以「单头说已核销、明细说未核销」写不进去。
 *   <li><b>汇率快照留在单据上。</b> 建单时的汇率与本位币写在单据上并被 DB 冻结，之后不可改； 报表读的是单据上的快照，<b>不按今天的汇率重算</b>。
 * </ol>
 *
 * <p><b>跨服务边界</b>：trade 的发货确认事实经 {@link TradeReceivablePort} 读（授权 HTTP API， 不跨库不跨服务 SQL）；12.3.1
 * 结算**未交付** ⇒ 平台结算 / 平台代扣两个来源当前**不可产生**， 本服务拒绝用它们建单并在能力声明里写明归属模块，<b>绝不填 0、也绝不造一条假结算</b>。
 */
@Service
public class ReceivableService {

  private static final Logger logger = LoggerFactory.getLogger(ReceivableService.class);

  /** 当前 UTC 日历日：账龄的基准日。 */
  static final LocalDate TODAY_UTC = LocalDate.now(ZoneOffset.UTC);

  /** 一页最大条数。 */
  static final int MAX_PAGE_SIZE = 200;

  /**
   * 取催收任务的查询形状：**必须**把应收单 join 进来。
   *
   * <p>为什么：任务行上只有 {@code receivable_id}，页面上要显示的**单号**在应收单上。 不 join 的话 {@code taskRow} 读不到 {@code
   * receivable_no}，jOOQ 会抛 「Field "receivable_no" is not contained in row type」——
   * 那是一条看不出真相的异常（现场只会看到「接口 500」）。
   */
  private static final String TASK_SELECT =
      "select t.*, r.receivable_no from finance_collection_task t"
          + " join finance_receivable r on r.tenant_id = t.tenant_id and r.id = t.receivable_id";

  private final DSLContext sql;
  private final TradeReceivablePort trade;

  public ReceivableService(DSLContext sql, TradeReceivablePort trade) {
    this.sql = sql;
    this.trade = trade;
  }

  // ================================================================ 能力声明

  /**
   * 能力声明：**每一维说清「有没有、依据是什么、缺了会怎样」**。
   *
   * <p>⚠️ 卷 12 的上游有两类，页面上必须分开说： 「**未交付**」（12.3.1 结算）与「**已交付但本模块没接**」（12.2.1.2 核销）。
   * 把两者都写成「不可用」，运维会以为要去催结算，而实际上核销是本服务自己的后续模块。
   */
  public FinanceReceivableCapabilities capabilities() {
    var capabilities = new ArrayList<FinanceReceivableCapability>();
    var gaps = new ArrayList<FinanceReceivableDownstreamGap>();

    capabilities.add(
        new FinanceReceivableCapability(
            "12.1.1.1",
            "应收单管理",
            true,
            "finance_receivable / finance_receivable_line / finance_collection_task",
            "",
            "应收单、明细行、账龄分布与催收任务已落地：数据落 PostgreSQL 16，"
                + "查询走 jOOQ 显式 SQL，全链路 BigDecimal + NUMERIC，无浮点。"));
    capabilities.add(
        new FinanceReceivableCapability(
            "12.1.1.1",
            "应收确认政策",
            true,
            "finance_receivable_policy",
            "",
            "「什么业务确认为应收」是**租户自己维护的一份配置**，不是系统按订单状态推导的。" + "某个来源没有启用的政策 ⇒ 该来源一张应收都产生不了。"));
    capabilities.add(
        new FinanceReceivableCapability(
            "9.7.1.1",
            "B2B 订单发货确认 → 应收",
            true,
            "经 trade 的 GET /trade/b2b-orders/receivables 读（授权 HTTP API，不跨库）",
            "",
            "上游账期应收经授权 API 读入本库，落成 source='B2B_CREDIT_TERM'、"
                + "source_system='trade'、source_reference=上游单号 的应收单。"
                + "**未发货订单不产生应收** —— 上游只在发货确认这一个动作里产出应收事实。"));
    capabilities.add(
        new FinanceReceivableCapability(
            "12.3.1",
            "平台结算 → 应收",
            false,
            "上游 12.3.1.1 结算单管理未交付",
            "12.3.1.1",
            "结算单还不存在 ⇒ 平台结算 / 平台代扣两个来源**当前不可自动产生**。"
                + "字段与来源枚举已按「同一张表承接上游」的方式留好，"
                + "12.3.1 交付后经授权 API / event 写入即可，不需要改结构。"
                + "**本服务不填 0、也不造一条假结算。**"));
    capabilities.add(
        new FinanceReceivableCapability(
            "12.1.1.2",
            "应收账龄（按店铺 / 客户 / 币种下钻 + 逾期清单）",
            false,
            "本模块只提供 12.1.1.1 输出里的账龄分布（五档 × 币种）",
            "12.1.1.2",
            "账龄分布已在 GET /finance/receivables/aging 提供；"
                + "**按店铺 / 客户 / 币种三个维度下钻**与「逾期清单」属 12.1.1.2，"
                + "交付后读同一张表，不需要改结构。"));
    capabilities.add(
        new FinanceReceivableCapability(
            "12.2.1.2",
            "核销（收款核销到明细行）",
            false,
            "写路径未开放：行级 settled_amount 留好，触发器已把单头状态派生好",
            "12.2.1.2",
            "明细行的核销金额是**核销的唯一入口**，12.2.1.2 交付后写它即可，"
                + "单头状态与已核销金额由 DB 触发器自动跟上。"
                + "本模块**不提供**任何改核销状态的端点。"));
    capabilities.add(
        new FinanceReceivableCapability(
            "12.1.1.3",
            "坏账与核销（争议损失 / 拒付 / 冻结）",
            false,
            "作废（红冲的等价物）已在本模块落地；坏账核销的审批链未交付",
            "12.1.1.3",
            "争议中金额已由 dispute_flag 单列并进账龄分布；"
                + "**争议事实来自 11.4.1.1 纠纷（trade），当前只支持人工标注，未自动联动**。"
                + "坏账核销的审批与依据链属 12.1.1.3。"));
    capabilities.add(
        new FinanceReceivableCapability(
            "12.4.1.1",
            "成本算法（先进先出 / 移动加权 / 批次）",
            false,
            "本服务没有成本表",
            "12.4.1.1",
            "卷 12 卷首写死「**系统不替用户决定会计政策**」：" + "计价方法由用户在 12.4.1.1 里选，本模块不预设、也不推断。"));
    capabilities.add(
        new FinanceReceivableCapability(
            "12.5.2.1",
            "凭证（过账后不可改行）",
            false,
            "本模块只保证「应收确认后行不可改」这一半",
            "12.5.2.1",
            "应收单是凭证的**来源**而不是凭证本身；凭证的锁头 / 平衡 / 期间开放校验属总账。"));

    gaps.add(
        new FinanceReceivableDownstreamGap(
            "12.1.1.2",
            "应收账龄",
            "finance",
            false,
            "12.1 应收与资金",
            "账龄分布已在 12.1.1.1 提供；三个维度下钻与逾期清单未交付。"));
    gaps.add(
        new FinanceReceivableDownstreamGap(
            "12.1.1.3",
            "坏账与核销",
            "finance",
            false,
            "12.1 应收与资金",
            "核销写路径未开放；争议事实来自 11.4.1.1（trade），当前只支持人工标注。"));
    gaps.add(
        new FinanceReceivableDownstreamGap(
            "12.2.1.2",
            "核销",
            "finance",
            false,
            "12.2 收付款",
            "核销必须精确到明细行；行级 settled_amount 已留好，写路径未开放。"));
    gaps.add(
        new FinanceReceivableDownstreamGap(
            "12.3.1.1", "结算单管理", "finance", false, "12.3 结算与对账", "结算单未交付 ⇒ 平台结算 / 平台代扣来源当前不可产生。"));
    gaps.add(
        new FinanceReceivableDownstreamGap(
            "12.6.2.1",
            "发票管理",
            "finance",
            false,
            "12.6 税务与发票",
            "开票需求属 trade 侧 b2b_receivable.invoice_state；12.6.2 交付后消费它。"));
    gaps.add(
        new FinanceReceivableDownstreamGap(
            "13.1.1.1", "利润与经营分析", "analytics", false, "13 利润与经营分析", "13 未交付：应收是它的一处上游输入。"));

    return new FinanceReceivableCapabilities(List.copyOf(capabilities), List.copyOf(gaps));
  }

  // ================================================================ 筛选项

  /** 筛选项下拉：全部来自**真实数据**（distinct 查询），不是写死的词表。 */
  public FinanceReceivableOptions options() {
    var sources =
        sql.fetch("select distinct source from finance_receivable order by source")
            .map(r -> r.get(0, String.class));
    var currencies =
        sql.fetch("select distinct currency from finance_receivable order by currency")
            .map(r -> r.get(0, String.class));
    var statuses =
        sql.fetch("select distinct status from finance_receivable order by status")
            .map(r -> r.get(0, String.class));
    var counterparties =
        sql.fetch(
                "select distinct counterparty_ref, counterparty_name from finance_receivable"
                    + " where counterparty_ref is not null order by counterparty_name, counterparty_ref")
            .map(
                r ->
                    new FinanceReceivableOptionRef(
                        r.get(0, UUID.class).toString(), r.get(1, String.class)));
    var shops =
        sql.fetch(
                "select distinct shop_id, shop_name from finance_receivable"
                    + " where shop_id is not null order by shop_name, shop_id")
            .map(
                r ->
                    new FinanceReceivableOptionRef(
                        r.get(0, UUID.class).toString(), r.get(1, String.class)));
    var buckets =
        List.of(AgingBucket.values()).stream()
            .map(b -> new FinanceReceivableBucketOption(b.name(), b.label()))
            .toList();
    return new FinanceReceivableOptions(
        sources, statuses, currencies, counterparties, shops, buckets);
  }

  // ================================================================ 列表

  /**
   * 应收单列表：真分页 + 真筛选 + 真排序。
   *
   * <p>⚠️ **汇总覆盖的是整个筛选结果集，不是当前页**：分页一变汇总就变的话， 「翻到第二页看到总应收变小了」会被读成「数据丢了」。
   */
  public FinanceReceivablePage page(ReceivableQuery query) {
    int p = Math.max(0, query.page());
    int s = Math.min(MAX_PAGE_SIZE, Math.max(1, query.size()));
    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    applyFilters(query, where, binds);
    String clause = where.isEmpty() ? "" : " where " + String.join(" and ", where);

    long total =
        sql.fetchOne("select count(*) from finance_receivable r" + clause, binds.toArray())
            .get(0, Long.class);
    List<FinanceReceivableRow> items =
        sql.fetch(
                "select * from finance_receivable r"
                    + clause
                    + " order by r.due_date, r.id limit ? offset ?",
                concat(binds, s, p * s))
            .map(this::row);

    var summaries = summaryByCurrency(where, binds);
    long overdueCount =
        summaries.stream().mapToLong(FinanceReceivableCurrencySummary::overdueCount).sum();
    return new FinanceReceivablePage(items, p, s, total, summaries, overdueCount);
  }

  /**
   * 按币种的汇总。
   *
   * <p>⚠️ **作废的单不计入**：一张已作废的单据在资金口径上不存在， 把它算进「在册应收」会让总额虚高。
   */
  private List<FinanceReceivableCurrencySummary> summaryByCurrency(
      List<String> where, List<Object> binds) {
    var clauses = new ArrayList<String>();
    clauses.add("r.status <> 'VOID'");
    clauses.addAll(where);
    return sql.fetch(
            "select r.currency,"
                + " sum(r.amount) as amount,"
                + " sum(r.settled_amount) as settled_amount,"
                + " sum(r.amount - r.settled_amount) as open_amount,"
                + " count(*) as cnt,"
                + " count(*) filter (where r.due_date < (now() at time zone 'UTC')::date) as overdue_count,"
                + " coalesce(sum(r.amount - r.settled_amount) filter (where r.due_date < (now() at time zone 'UTC')::date),0) as overdue_amount,"
                + " coalesce(sum(r.amount - r.settled_amount) filter (where r.dispute_flag),0) as dispute_amount"
                + " from finance_receivable r where "
                + String.join(" and ", clauses)
                + " group by r.currency order by r.currency",
            binds.toArray())
        .map(
            r ->
                new FinanceReceivableCurrencySummary(
                    r.get("currency", String.class),
                    r.get("amount", BigDecimal.class),
                    r.get("settled_amount", BigDecimal.class),
                    r.get("open_amount", BigDecimal.class),
                    r.get("cnt", Long.class),
                    r.get("overdue_count", Long.class),
                    r.get("overdue_amount", BigDecimal.class),
                    r.get("dispute_amount", BigDecimal.class)));
  }

  private void applyFilters(ReceivableQuery q, List<String> where, List<Object> binds) {
    if (q.status() != null) {
      where.add("r.status = ?");
      binds.add(q.status().name());
    }
    if (q.source() != null) {
      where.add("r.source = ?");
      binds.add(q.source().name());
    }
    if (q.currency() != null) {
      where.add("r.currency = ?");
      binds.add(q.currency());
    }
    if (q.counterpartyRef() != null) {
      where.add("r.counterparty_ref = ?");
      binds.add(q.counterpartyRef());
    }
    if (q.shopId() != null) {
      where.add("r.shop_id = ?");
      binds.add(q.shopId());
    }
    if (q.disputeOnly() != null && q.disputeOnly()) {
      where.add("r.dispute_flag");
    }
    if (q.overdueOnly() != null && q.overdueOnly()) {
      where.add("r.due_date < (now() at time zone 'UTC')::date");
      where.add("r.status in ('OPEN','PARTIALLY_SETTLED')");
    }
    if (q.dueFrom() != null) {
      where.add("r.due_date >= ?::date");
      binds.add(q.dueFrom());
    }
    if (q.dueTo() != null) {
      where.add("r.due_date <= ?::date");
      binds.add(q.dueTo());
    }
    if (q.keyword() != null && !q.keyword().isBlank()) {
      where.add(
          "(r.receivable_no ilike ? or r.counterparty_name ilike ?"
              + " or r.source_reference ilike ? or r.shop_name ilike ?)");
      String like = "%" + q.keyword().trim() + "%";
      binds.add(like);
      binds.add(like);
      binds.add(like);
      binds.add(like);
    }
  }

  // ================================================================ 详情

  /** 应收单详情：单头 + 明细行 + 事件流 + 催收任务。 */
  public FinanceReceivableDetail detail(UUID id) {
    var head = sql.fetchOne("select * from finance_receivable where id = ?", id);
    if (head == null) throw notFound("应收单 " + id + " 不存在或无权查看");
    var lines =
        sql.fetch(
                "select * from finance_receivable_line where receivable_id = ? order by line_no, id",
                id)
            .map(
                r ->
                    new FinanceReceivableLineRow(
                        r.get("id", UUID.class),
                        r.get("line_no", Integer.class),
                        r.get("description", String.class),
                        r.get("sku_code", String.class),
                        r.get("order_no", String.class),
                        r.get("source_reference", String.class),
                        r.get("quantity", BigDecimal.class),
                        r.get("unit_price", BigDecimal.class),
                        r.get("amount", BigDecimal.class),
                        r.get("settled_amount", BigDecimal.class),
                        r.get("settlement_status", String.class)));
    var events =
        sql.fetch(
                "select * from finance_receivable_event where receivable_id = ? order by occurred_at, id",
                id)
            .map(
                r ->
                    new FinanceReceivableEventRow(
                        r.get("id", UUID.class),
                        r.get("event_type", String.class),
                        r.get("from_status", String.class),
                        r.get("to_status", String.class),
                        r.get("reason", String.class),
                        r.get("actor_id", UUID.class),
                        r.get("occurred_at", OffsetDateTime.class)));
    var tasks = collectionTasksOf(id);
    return new FinanceReceivableDetail(row(head), lines, events, tasks);
  }

  // ================================================================ 建单

  /**
   * 人工录入一张应收单（建单 + 确认在**同一个事务**里完成）。
   *
   * <p>⚠️ <b>只有 MANUAL / OTHER 能手工建单</b>： B2B 账期应收**只能**由发货确认事实产生（手工录入等于自己编一个发货确认）， 平台两个来源的上游还没交付。
   *
   * <p>⚠️ <b>必须先有启用的确认政策</b>：这是「什么业务确认为应收」的闸门。
   */
  @Transactional
  public FinanceReceivableRow create(FinanceReceivableInput input, AccessService.Access actor) {
    ReceivableSource source = input.source() == null ? ReceivableSource.MANUAL : input.source();
    if (source == ReceivableSource.B2B_CREDIT_TERM) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "B2B 客户账期应收**只能**由发货确认产生（请用「从发货确认导入」），" + "手工录入等于自己编一个发货确认事实");
    }
    if (!source.producible()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "「"
              + source.label()
              + "」来源的上游是 12.3.1 结算，当前**未交付**，这张应收无法产生。"
              + "请改用「人工录入」或「其他」，或等结算单落地后由结算数据自动产生");
    }
    var policy = requireEnabledPolicy(source);

    if (input.lines() == null || input.lines().isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "应收单至少要有一条明细行");
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
    String no = nextReference("AR");
    sql.execute(
        "insert into finance_receivable(id,tenant_id,receivable_no,source,source_system,source_reference,"
            + " counterparty_type,counterparty_ref,counterparty_name,shop_id,shop_name,currency,"
            + " exchange_rate,base_currency,due_date,confirmed_at,note,status,created_at,updated_at)"
            + " values(?,?,?,?,'manual',null,?,?,?,?,?,?,?,?,?::date,?::timestamptz,?,'DRAFT',?::timestamptz,?::timestamptz)",
        id,
        actor.tenantId(),
        no,
        source.name(),
        input.counterpartyType().name(),
        input.counterpartyRef(),
        input.counterpartyName().trim(),
        input.shopId(),
        input.shopName() == null ? "" : input.shopName(),
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
          "insert into finance_receivable_line(id,tenant_id,receivable_id,line_no,description,sku_code,"
              + " order_no,source_reference,quantity,unit_price,amount)"
              + " values(?,?,?,?,?,?,?,?,?,?,?)",
          UUID.randomUUID(),
          actor.tenantId(),
          id,
          lineNo,
          line.description() == null ? "" : line.description(),
          line.skuCode() == null ? "" : line.skuCode(),
          blankToNull(line.orderNo()),
          blankToNull(line.sourceReference()),
          quantity,
          unitPrice,
          amount);
    }

    confirm(actor.tenantId(), id, actor.userId(), confirmedAt);
    return detail(id).receivable();
  }

  /** 确认：草稿 → 未核销。DB 触发器在这里校验「至少一条明细行」并把金额派生到单头。 */
  private void confirm(UUID tenantId, UUID id, UUID userId, OffsetDateTime at) {
    sql.execute(
        "update finance_receivable set status='OPEN', updated_at=?::timestamptz, version=version+1"
            + " where id = ?",
        at,
        id);
    writeEvent(tenantId, id, "CONFIRMED", "DRAFT", "OPEN", "", userId, at);
  }

  // ================================================================ 从发货确认导入

  /**
   * 从上游（trade 9.7.1.1 批发订单与账期）导入**发货确认**产生的账期应收。
   *
   * <p>⚠️ **幂等**：幂等键是 {@code (source, source_reference)} = ('B2B_CREDIT_TERM', 上游单号)，
   * 重复导入命中同一条，不会长出第二张单。
   *
   * <p>⚠️ **上游读不到就整体失败**（503），绝不返回「导入 0 条」冒充「上游没有数据」。
   */
  @Transactional
  public FinanceReceivableImportResult importFromShipmentConfirmations(
      String bearer, AccessService.Access actor) {
    var policy = requireEnabledPolicy(ReceivableSource.B2B_CREDIT_TERM);
    int scanned = 0;
    int imported = 0;
    int skipped = 0;
    boolean truncated = false;
    var rows = new ArrayList<FinanceReceivableRow>();
    var warnings = new ArrayList<String>();

    for (int page = 0; page < TradeReceivablePort.MAX_PAGES; page++) {
      var upstream = trade.receivables(page, bearer);
      if (upstream.items().isEmpty()) break;
      scanned += upstream.items().size();
      for (var view : upstream.items()) {
        if (view.status() != null && !"OPEN".equals(view.status())) {
          skipped++;
          continue;
        }
        if (view.receivableNo() == null || view.receivableNo().isBlank()) {
          skipped++;
          warnings.add("上游有一条应收没有单号，已跳过（无法确定幂等键）");
          continue;
        }
        var existing =
            sql.fetchOne(
                "select id from finance_receivable where source = ? and source_reference = ?",
                ReceivableSource.B2B_CREDIT_TERM.name(),
                view.receivableNo());
        if (existing != null) {
          skipped++;
          continue;
        }
        var built = buildFromUpstream(view, actor);
        if (built == null) {
          skipped++;
          warnings.add(
              "上游单号 "
                  + view.receivableNo()
                  + " 的单头金额与明细合计不一致（单头 "
                  + view.amount()
                  + "），已跳过以免造出一张对不上的应收");
          continue;
        }
        rows.add(built);
        imported++;
      }
      if (upstream.items().size() < TradeReceivablePort.PAGE_SIZE) break;
      if (page == TradeReceivablePort.MAX_PAGES - 1) truncated = true;
    }
    if (truncated) {
      warnings.add(
          "上游翻页触到上限（"
              + (TradeReceivablePort.MAX_PAGES * TradeReceivablePort.PAGE_SIZE)
              + " 条），本次只扫描了前 "
              + scanned
              + " 条");
    }
    if (!warnings.isEmpty()) {
      // 警告不是秘密：它要出现在导入结果里，让人知道「有几条没进来」。
      // ⚠️ 警告**不写事件流** —— 事件流挂在某一张应收单上，而「没导入进来的那条」
      //   本来就没有本库的应收单，挂上去只能造假。
      logger.warn("[finance] 导入发货确认应收有 {} 条未进入：{}", warnings.size(), warnings);
    }
    return new FinanceReceivableImportResult(scanned, imported, skipped, truncated, rows, warnings);
  }

  /**
   * 把上游的一条账期应收落成本库的应收单。
   *
   * @return 落库后的应收单；上游数据自相矛盾时返回 null（调用方据此跳过并报警告）。
   */
  private FinanceReceivableRow buildFromUpstream(
      TradeReceivablePort.TradeReceivableView view, AccessService.Access actor) {
    OffsetDateTime confirmedAt =
        view.confirmedAt() == null ? OffsetDateTime.now(ZoneOffset.UTC) : view.confirmedAt();
    LocalDate dueDate = TradeReceivablePort.dueDateOf(view);
    if (dueDate == null) {
      dueDate = confirmedAt.toLocalDate().plusDays(0);
    }

    var lines = new ArrayList<TradeReceivablePort.TradeReceivableLineView>();
    if (view.lines() != null) lines.addAll(view.lines());
    BigDecimal upstreamAmount = money(view.amount());

    List<BigDecimal> amounts = new ArrayList<>();
    if (lines.isEmpty()) {
      // 上游没有明细：金额本身就是真值，落成一行（**不编明细**）。
      amounts.add(upstreamAmount);
    } else {
      for (var l : lines) {
        BigDecimal q = l.quantity() == null ? null : quantity(l.quantity());
        BigDecimal p = l.unitPrice() == null ? null : money(l.unitPrice());
        BigDecimal amount =
            l.lineAmount() != null
                ? money(l.lineAmount())
                : (q == null || p == null ? upstreamAmount : money(q.multiply(p)));
        amounts.add(amount);
      }
      BigDecimal sum = amounts.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
      if (sum.compareTo(upstreamAmount) != 0) {
        // 单头与明细对不上：**不导入**。造一张金额对不上的应收比不导入更糟。
        return null;
      }
    }

    UUID id = UUID.randomUUID();
    String no = nextReference("AR");
    sql.execute(
        "insert into finance_receivable(id,tenant_id,receivable_no,source,source_system,source_reference,"
            + " counterparty_type,counterparty_ref,counterparty_name,shop_id,shop_name,currency,"
            + " exchange_rate,base_currency,due_date,confirmed_at,note,status,created_at,updated_at)"
            + " values(?,?,?,?,'trade',?,?,?,?,null,'',?,null,null,?::date,?::timestamptz,?,'DRAFT',?::timestamptz,?::timestamptz)",
        id,
        actor.tenantId(),
        no,
        ReceivableSource.B2B_CREDIT_TERM.name(),
        view.receivableNo(),
        CounterpartyType.CUSTOMER.name(),
        view.customerPartyId(),
        view.customerName() == null ? "" : view.customerName(),
        view.currency(),
        dueDate,
        confirmedAt,
        "由发货确认导入（上游单号 " + view.receivableNo() + "）",
        confirmedAt,
        confirmedAt);
    writeEvent(
        actor.tenantId(), id, "CREATED", "", "DRAFT", "从发货确认导入", actor.userId(), confirmedAt);

    int lineNo = 0;
    for (int i = 0; i < amounts.size(); i++) {
      lineNo++;
      var l = i < lines.size() ? lines.get(i) : null;
      sql.execute(
          "insert into finance_receivable_line(id,tenant_id,receivable_id,line_no,description,sku_code,"
              + " order_no,source_reference,quantity,unit_price,amount)"
              + " values(?,?,?,?,?,?,?,?,?,?,?)",
          UUID.randomUUID(),
          actor.tenantId(),
          id,
          lineNo,
          l == null ? "上游账期应收（上游未提供明细）" : "上游账期应收明细",
          l == null ? "" : (l.skuCode() == null ? "" : l.skuCode()),
          blankToNull(view.orderNo()),
          blankToNull(view.receivableNo()),
          l == null || l.quantity() == null ? null : quantity(l.quantity()),
          l == null || l.unitPrice() == null ? null : money(l.unitPrice()),
          amounts.get(i));
    }

    confirm(actor.tenantId(), id, actor.userId(), confirmedAt);
    writeEvent(
        actor.tenantId(),
        id,
        "IMPORTED",
        "OPEN",
        "OPEN",
        "上游单号 " + view.receivableNo(),
        actor.userId(),
        confirmedAt);
    return detail(id).receivable();
  }

  // ================================================================ 作废 / 争议

  /**
   * 作废一张应收单（红冲的等价物）：终态，整单冻结。
   *
   * <p>⚠️ **已核销（哪怕只核销了一部分）的应收不能作废** —— 那笔钱已经动过， 要冲销得走 12.1.1.3 坏账核销（带审批与依据链）。
   */
  @Transactional
  public FinanceReceivableRow voidReceivable(
      UUID id, FinanceReceivableVoidInput input, AccessService.Access actor) {
    var head = sql.fetchOne("select * from finance_receivable where id = ?", id);
    if (head == null) throw notFound("应收单 " + id + " 不存在或无权操作");
    String reason = input == null || input.reason() == null ? "" : input.reason().trim();
    if (reason.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "作废必须写明理由");
    }
    String status = head.get("status", String.class);
    if (ReceivableStatus.valueOf(status).terminal()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "应收单已是终态（" + status + "），不可再作废");
    }
    if (head.get("settled_amount", BigDecimal.class).signum() > 0) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "这张应收已核销 "
              + head.get("settled_amount", BigDecimal.class).toPlainString()
              + "，不能作废：冲减已核销的应收要走 12.1.1.3 坏账核销（带审批与依据链）");
    }
    OffsetDateTime at = OffsetDateTime.now(ZoneOffset.UTC);
    sql.execute(
        "update finance_receivable set status='VOID', updated_at=?::timestamptz, version=version+1"
            + " where id = ?",
        at,
        id);
    writeEvent(actor.tenantId(), id, "VOIDED", status, "VOID", reason, actor.userId(), at);
    return detail(id).receivable();
  }

  /**
   * 标注 / 取消「争议中」。
   *
   * <p>⚠️ 争议事实来自 11.4.1.1 纠纷（trade 服务），**当前未自动联动**： 本模块只记录**人工标注**的状态，并在能力声明里写明上游归属。
   * 之所以仍然做：跨境应收最大的风险是平台争议冻结， 账龄分布必须能把它**单独标识**出来（卷 12 12.1.1.2 明确要求）。
   */
  @Transactional
  public FinanceReceivableRow flagDispute(
      UUID id, FinanceReceivableDisputeInput input, AccessService.Access actor) {
    var head = sql.fetchOne("select * from finance_receivable where id = ?", id);
    if (head == null) throw notFound("应收单 " + id + " 不存在或无权操作");
    String status = head.get("status", String.class);
    if (ReceivableStatus.valueOf(status).terminal()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "应收单已是终态（" + status + "），不可再改争议标记");
    }
    String note = input.note() == null ? "" : input.note().trim();
    if (input.flagged() && note.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "标注争议必须写明情况");
    }
    OffsetDateTime at = OffsetDateTime.now(ZoneOffset.UTC);
    sql.execute(
        "update finance_receivable set dispute_flag=?, dispute_note=?, updated_at=?::timestamptz,"
            + " version=version+1 where id = ?",
        input.flagged(),
        note,
        at,
        id);
    writeEvent(
        actor.tenantId(),
        id,
        input.flagged() ? "DISPUTE_FLAGGED" : "DISPUTE_CLEARED",
        status,
        status,
        note,
        actor.userId(),
        at);
    return detail(id).receivable();
  }

  // ================================================================ 账龄

  /**
   * 账龄分布：五档 × 币种。
   *
   * <p>⚠️ **按币种分开**：USD 与 EUR 加起来不是任何一个金额。 ⚠️ **按 UTC 日历日分桶**：用会话时区的话，换一条连接就换一套分桶，分布会凭空多出空桶。 ⚠️
   * **争议中金额单列**：跨境应收最大的风险是平台争议冻结。
   */
  public FinanceReceivableAging aging() {
    LocalDate asOf = LocalDate.now(ZoneOffset.UTC);
    var buckets =
        sql.fetch(
                "select case"
                    + " when ((now() at time zone 'UTC')::date - due_date) <= 0 then 'NOT_DUE'"
                    + " when ((now() at time zone 'UTC')::date - due_date) <= 30 then 'D1_30'"
                    + " when ((now() at time zone 'UTC')::date - due_date) <= 60 then 'D31_60'"
                    + " when ((now() at time zone 'UTC')::date - due_date) <= 90 then 'D61_90'"
                    + " else 'D90_PLUS' end as bucket,"
                    + " currency,"
                    + " sum(amount - settled_amount) as amount,"
                    + " count(*) as cnt,"
                    + " coalesce(sum(amount - settled_amount) filter (where dispute_flag),0) as dispute_amount"
                    + " from finance_receivable"
                    + " where status in ('OPEN','PARTIALLY_SETTLED')"
                    + " group by 1, currency")
            .map(
                r -> {
                  var bucket = AgingBucket.valueOf(r.get("bucket", String.class));
                  return new FinanceReceivableAgingBucket(
                      bucket,
                      bucket.label(),
                      r.get("currency", String.class),
                      r.get("amount", BigDecimal.class),
                      r.get("cnt", Long.class),
                      r.get("dispute_amount", BigDecimal.class));
                });
    var totals =
        sql.fetch(
                "select currency, sum(amount - settled_amount) as open_amount, count(*) as cnt,"
                    + " coalesce(sum(amount - settled_amount) filter (where dispute_flag),0) as dispute_amount"
                    + " from finance_receivable where status in ('OPEN','PARTIALLY_SETTLED')"
                    + " group by currency order by currency")
            .map(
                r ->
                    new FinanceReceivableCurrencySummary(
                        r.get("currency", String.class),
                        r.get("open_amount", BigDecimal.class),
                        BigDecimal.ZERO.setScale(MONEY_SCALE),
                        r.get("open_amount", BigDecimal.class),
                        r.get("cnt", Long.class),
                        0L,
                        BigDecimal.ZERO.setScale(MONEY_SCALE),
                        r.get("dispute_amount", BigDecimal.class)));
    long openCount = totals.stream().mapToLong(FinanceReceivableCurrencySummary::count).sum();
    return new FinanceReceivableAging(asOf, buckets, totals, openCount);
  }

  // ================================================================ 催收任务

  /** 催收任务列表：真分页 + 真筛选。 */
  public FinanceCollectionTaskPage collectionTasks(CollectionTaskQuery query) {
    int p = Math.max(0, query.page());
    int s = Math.min(MAX_PAGE_SIZE, Math.max(1, query.size()));
    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    if (query.status() != null) {
      where.add("t.status = ?");
      binds.add(query.status().name());
    }
    if (query.level() != null) {
      where.add("t.level = ?");
      binds.add(query.level().name());
    }
    if (query.receivableId() != null) {
      where.add("t.receivable_id = ?");
      binds.add(query.receivableId());
    }
    String clause = where.isEmpty() ? "" : " where " + String.join(" and ", where);
    long total =
        sql.fetchOne("select count(*) from finance_collection_task t" + clause, binds.toArray())
            .get(0, Long.class);
    var items =
        sql.fetch(
                TASK_SELECT + clause + " order by t.due_date, t.id limit ? offset ?",
                concat(binds, s, p * s))
            .map(this::taskRow);
    return new FinanceCollectionTaskPage(items, p, s, total);
  }

  /**
   * 给一张应收单建催收任务。
   *
   * <p>同一张应收同一级别只允许一条任务（DB UNIQUE）：重复催收是同一次催收。
   */
  @Transactional
  public FinanceCollectionTaskRow createCollectionTask(
      UUID receivableId, FinanceCollectionTaskInput input, AccessService.Access actor) {
    var head = sql.fetchOne("select * from finance_receivable where id = ?", receivableId);
    if (head == null) throw notFound("应收单 " + receivableId + " 不存在或无权操作");
    String status = head.get("status", String.class);
    if (ReceivableStatus.valueOf(status).terminal()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "应收单已是终态（" + status + "），不必再催收");
    }
    if (input.level() == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "必须指定催收级别");
    }
    LocalDate dueDate =
        input.dueDate() == null ? head.get("due_date", LocalDate.class) : input.dueDate();
    UUID id = UUID.randomUUID();
    OffsetDateTime at = OffsetDateTime.now(ZoneOffset.UTC);
    try {
      sql.execute(
          "insert into finance_collection_task(id,tenant_id,receivable_id,task_no,level,due_date,status,"
              + " assignee_name,reason,created_at,updated_at)"
              + " values(?,?,?,?,?,?::date,'OPEN',?,?,?::timestamptz,?::timestamptz)",
          id,
          actor.tenantId(),
          receivableId,
          nextReference("CT"),
          input.level().name(),
          dueDate,
          input.assigneeName() == null ? "" : input.assigneeName(),
          input.reason() == null ? "" : input.reason(),
          at,
          at);
    } catch (org.springframework.dao.DataAccessException e) {
      // Spring Boot 的 jOOQ 异常翻译器会把唯一键冲突翻成 DuplicateKeyException
      // （**不是** jOOQ 自己的 DataAccessException）—— 只抓后者会漏掉它，
      // 于是「重复催收」会以一条看不懂的 SQL 异常冒到用户面前。
      throw new ResponseStatusException(HttpStatus.CONFLICT, "这张应收已经有同级别的催收任务了（重复催收是同一次催收）");
    }
    writeEvent(
        actor.tenantId(),
        receivableId,
        "COLLECTION_TASK_CREATED",
        status,
        status,
        input.level().name(),
        actor.userId(),
        at);
    return taskRow(sql.fetchOne(TASK_SELECT + " where t.id = ?", id));
  }

  /**
   * 批量生成逾期催收任务：给**所有已逾期且还没有 OVERDUE 任务**的应收补一条。
   *
   * <p>这是 12.1.1.1 输出「生成催收任务」的批量入口： 幂等（已有同级别任务的不重复建），返回新建了几条。
   */
  @Transactional
  public List<FinanceCollectionTaskRow> generateOverdueTasks(AccessService.Access actor) {
    var candidates =
        sql.fetch(
            "select r.id, r.due_date from finance_receivable r"
                + " where r.status in ('OPEN','PARTIALLY_SETTLED')"
                + " and r.due_date < (now() at time zone 'UTC')::date"
                + " and not exists (select 1 from finance_collection_task t"
                + " where t.tenant_id = r.tenant_id and t.receivable_id = r.id and t.level = 'OVERDUE')"
                + " order by r.due_date, r.id");
    var created = new ArrayList<FinanceCollectionTaskRow>();
    OffsetDateTime at = OffsetDateTime.now(ZoneOffset.UTC);
    for (var c : candidates) {
      UUID id = UUID.randomUUID();
      UUID receivableId = c.get("id", UUID.class);
      LocalDate dueDate = c.get("due_date", LocalDate.class);
      sql.execute(
          "insert into finance_collection_task(id,tenant_id,receivable_id,task_no,level,due_date,status,"
              + " assignee_name,reason,created_at,updated_at)"
              + " values(?,?,?,?, 'OVERDUE',?::date,'OPEN','',?,?::timestamptz,?::timestamptz)",
          id,
          actor.tenantId(),
          receivableId,
          nextReference("CT"),
          dueDate,
          "账龄已逾期，自动生成逾期催收任务",
          at,
          at);
      writeEvent(
          actor.tenantId(),
          receivableId,
          "COLLECTION_TASK_CREATED",
          "OPEN",
          "OPEN",
          "批量生成逾期催收任务",
          actor.userId(),
          at);
      created.add(taskRow(sql.fetchOne(TASK_SELECT + " where t.id = ?", id)));
    }
    return created;
  }

  /** 关闭催收任务。 */
  @Transactional
  public FinanceCollectionTaskRow closeCollectionTask(
      UUID taskId, FinanceCollectionTaskCloseInput input, AccessService.Access actor) {
    var task = sql.fetchOne("select * from finance_collection_task where id = ?", taskId);
    if (task == null) throw notFound("催收任务 " + taskId + " 不存在或无权操作");
    if (!"OPEN".equals(task.get("status", String.class))) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "催收任务已是终态（" + task.get("status", String.class) + "）");
    }
    OffsetDateTime at = OffsetDateTime.now(ZoneOffset.UTC);
    sql.execute(
        "update finance_collection_task set status='DONE', completed_at=?::timestamptz, updated_at=?::timestamptz"
            + " where id = ?",
        at,
        at,
        taskId);
    writeEvent(
        actor.tenantId(),
        task.get("receivable_id", UUID.class),
        "COLLECTION_TASK_CLOSED",
        "OPEN",
        "OPEN",
        input == null || input.reason() == null ? "" : input.reason(),
        actor.userId(),
        at);
    return taskRow(sql.fetchOne(TASK_SELECT + " where t.id = ?", taskId));
  }

  private List<FinanceCollectionTaskRow> collectionTasksOf(UUID receivableId) {
    return sql.fetch(
            TASK_SELECT + " where t.receivable_id = ? order by t.due_date, t.id", receivableId)
        .map(this::taskRow);
  }

  // ================================================================ 政策

  /**
   * 政策列表：**五个来源全列**，没配过的来源也列（configured=false）。
   *
   * <p>「未配置」与「已禁用」是两件事，界面上必须能分开： 前者意味着「你还没决定」，后者意味着「你决定了这个来源不产生应收」。
   */
  public List<FinanceReceivablePolicyView> policies() {
    var configured = new LinkedHashMap<String, Record>();
    for (var r : sql.fetch("select * from finance_receivable_policy")) {
      configured.put(r.get("source", String.class), r);
    }
    var out = new ArrayList<FinanceReceivablePolicyView>();
    for (var source : ReceivableSource.values()) {
      var row = configured.get(source.name());
      out.add(
          new FinanceReceivablePolicyView(
              source,
              source.label(),
              row != null,
              row != null && row.get("enabled", Boolean.class),
              row == null ? 0 : row.get("due_days", Integer.class),
              row == null ? "" : row.get("note", String.class),
              ownerModuleOf(source),
              source.producible(),
              detailOf(source, row)));
    }
    return out;
  }

  /** 配置一个来源的确认政策（幂等 upsert）。 */
  @Transactional
  public FinanceReceivablePolicyRow updatePolicy(
      ReceivableSource source, FinanceReceivablePolicyInput input, AccessService.Access actor) {
    if (source == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "必须指定来源");
    }
    boolean enabled = input.enabled() == null || input.enabled();
    int dueDays = input.dueDays() == null ? 0 : input.dueDays();
    if (dueDays < 0 || dueDays > 365) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "账期天数必须在 0 到 365 之间");
    }
    String note = input.note() == null ? "" : input.note();
    OffsetDateTime at = OffsetDateTime.now(ZoneOffset.UTC);
    sql.execute(
        "insert into finance_receivable_policy(id,tenant_id,source,enabled,due_days,note,owner_module,"
            + " created_at,updated_at)"
            + " values(?,?,?,?,?,?,?,?::timestamptz,?::timestamptz)"
            + " on conflict (tenant_id, source) do update set enabled=excluded.enabled,"
            + " due_days=excluded.due_days, note=excluded.note, updated_at=excluded.updated_at",
        UUID.randomUUID(),
        actor.tenantId(),
        source.name(),
        enabled,
        dueDays,
        note,
        ownerModuleOf(source),
        at,
        at);
    var row =
        sql.fetchOne("select * from finance_receivable_policy where source = ?", source.name());
    return new FinanceReceivablePolicyRow(
        row.get("id", UUID.class),
        source,
        row.get("enabled", Boolean.class),
        row.get("due_days", Integer.class),
        row.get("note", String.class),
        row.get("owner_module", String.class),
        row.get("created_at", OffsetDateTime.class),
        row.get("updated_at", OffsetDateTime.class));
  }

  /**
   * 取一个来源的**已启用**政策；没有就拒绝。
   *
   * <p>这就是「应收确认政策需单独配置」的闸门：没有它，这个来源**一张应收都产生不了**。
   */
  private FinanceReceivablePolicyRow requireEnabledPolicy(ReceivableSource source) {
    var row =
        sql.fetchOne("select * from finance_receivable_policy where source = ?", source.name());
    if (row == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "「"
              + source.label()
              + "」还没有配置应收确认政策：这个来源**现在一张应收都产生不了**。"
              + "请先在「应收政策」里启用它并设定账期天数 —— 什么业务确认为应收由租户决定，"
              + "系统不会替用户默认一个");
    }
    if (!row.get("enabled", Boolean.class)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "「" + source.label() + "」的应收确认政策当前是**禁用**的，这个来源不产生应收");
    }
    return new FinanceReceivablePolicyRow(
        row.get("id", UUID.class),
        source,
        true,
        row.get("due_days", Integer.class),
        row.get("note", String.class),
        row.get("owner_module", String.class),
        row.get("created_at", OffsetDateTime.class),
        row.get("updated_at", OffsetDateTime.class));
  }

  private String ownerModuleOf(ReceivableSource source) {
    return switch (source) {
      case PLATFORM_SETTLEMENT, PLATFORM_WITHHOLDING -> "12.3.1.1";
      case B2B_CREDIT_TERM -> "9.7.1.1";
      default -> "";
    };
  }

  private String detailOf(ReceivableSource source, Record row) {
    if (!source.producible()) {
      return "上游是 12.3.1 结算（**未交付**）：这个来源当前**不可产生**应收。"
          + "配置它也不会让结算数据凭空出现 —— 等 12.3.1.1 落地后由结算单产生。";
    }
    if (row == null) return "未配置：启用后这个来源才可以产生应收。";
    if (!row.get("enabled", Boolean.class)) return "已禁用：这个来源当前不产生应收。";
    int days = row.get("due_days", Integer.class);
    return "已启用：到期日 = 确认日 + " + days + " 天" + (days == 0 ? "（确认即到期）" : "") + "。";
  }

  // ================================================================ 工具

  private void writeEvent(
      UUID tenantId,
      UUID receivableId,
      String type,
      String from,
      String to,
      String reason,
      UUID actorId,
      OffsetDateTime at) {
    sql.execute(
        "insert into finance_receivable_event(id,tenant_id,receivable_id,event_type,from_status,"
            + " to_status,reason,actor_id,occurred_at) values(?,?,?,?,?,?,?,?,?::timestamptz)",
        UUID.randomUUID(),
        tenantId,
        receivableId,
        type,
        from,
        to,
        reason == null ? "" : reason,
        actorId,
        at);
  }

  private FinanceReceivableRow row(Record r) {
    return new FinanceReceivableRow(
        r.get("id", UUID.class),
        r.get("receivable_no", String.class),
        ReceivableSource.valueOf(r.get("source", String.class)),
        r.get("source_system", String.class),
        r.get("source_reference", String.class),
        CounterpartyType.valueOf(r.get("counterparty_type", String.class)),
        r.get("counterparty_ref", UUID.class),
        r.get("counterparty_name", String.class),
        r.get("shop_id", UUID.class),
        r.get("shop_name", String.class),
        r.get("currency", String.class),
        r.get("amount", BigDecimal.class),
        r.get("exchange_rate", BigDecimal.class),
        r.get("base_currency", String.class),
        r.get("base_amount", BigDecimal.class),
        r.get("settled_amount", BigDecimal.class),
        ReceivableStatus.valueOf(r.get("status", String.class)),
        r.get("due_date", LocalDate.class),
        r.get("confirmed_at", OffsetDateTime.class),
        r.get("dispute_flag", Boolean.class),
        r.get("dispute_note", String.class),
        r.get("note", String.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  private FinanceCollectionTaskRow taskRow(Record r) {
    return new FinanceCollectionTaskRow(
        r.get("id", UUID.class),
        r.get("task_no", String.class),
        r.get("receivable_id", UUID.class),
        r.get("receivable_no", String.class),
        CollectionTaskLevel.valueOf(r.get("level", String.class)),
        r.get("due_date", LocalDate.class),
        CollectionTaskStatus.valueOf(r.get("status", String.class)),
        r.get("assignee_name", String.class),
        r.get("reason", String.class),
        r.get("completed_at", OffsetDateTime.class),
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
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "必须指定应收对象类型");
    }
    if (name == null || name.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "必须写明应收对象名称");
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
