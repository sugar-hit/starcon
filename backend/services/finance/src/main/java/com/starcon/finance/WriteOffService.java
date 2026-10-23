package com.starcon.finance;

import static com.starcon.finance.WriteOffModels.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
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
 * 12.2.1.2 核销。
 *
 * <p>蓝图 12-finance.md：「把收款核销到应收：自动匹配（金额+订单号+日期）或人工指定，支持部分核销、
 * 合并核销、预收挂账、核销撤销。」输出：「核销记录 + 应收余额更新 + 预收余额。」
 * 加粗句：「<b>核销必须精确到明细行</b>，只核销到订单头无法处理部分回款与折让。」
 *
 * <p><b>本模块的五条设计主线</b>：
 *
 * <ol>
 *   <li><b>写入所有权只有一处。</b> 核销金额落在
 *       {@code finance_receivable_line.settled_amount}（V338 原文：「只放行 settled_amount（核销金额）
 *       —— 那是 12.2.1.2 的写入所有权」）与 {@code finance_receipt.settled_amount}
 *       （V341 原文：「核销金额的唯一写入口是 settled_amount」）。
 *       <b>本模块不写应收单头</b>：单头的 {@code settled_amount} 与 {@code status} 由 V338 自己的
 *       派生触发器 {@code guard_finance_receivable_amount} 带出来 ——
 *       所以「单头说已核销、明细还说未核销」这种状态在数据层写不出来。
 *       本模块也**不在上游表上挂任何新触发器、不改上游的 GRANT**。
 *   <li><b>撤销走的是守卫允许的那条路。</b> 撤销把明细行的 {@code settled_amount} 减回去，
 *       单头由 V338 的派生触发器跟着退。V338 的行冻结守卫只冻
 *       「金额/数量/单价/描述/订单号…」这些**账目事实**，{@code settled_amount} 被显式放行。
 *       ⚠️ 但**整单核销完成**（单头 = SETTLED）是 V338 写死的**终态**：
 *       那时单头整单冻结，撤销退不回来 —— 本模块**如实拒绝并说清原因**，
 *       而不是绕开守卫（绕开就等于给账目留后门）。
 *   <li><b>自动匹配是「金额 + 订单号 + 日期」三者对上</b>，**不是「金额相同即匹配」**
 *       （卷 12 对 12.3.2.3 写死的同一条纪律）。三个条件缺一不可，逐条写进命中原因。
 *   <li><b>预收挂账不减未核销余额。</b> 12.2.1.1 的口径是「预收 ⊂ 未核销余额」，
 *       本模块与它同口径：挂账只是把一部分未核销的钱<b>分类</b>成预收
 *       （记进 {@code finance_advance_ledger}），并把它<b>专用</b>起来 ——
 *       挂着的钱不能拿去核销别的应收，要动用先显式释放。
 *   <li><b>下游 13 利润不可用就说不可用。</b> 「回款口径」那一段**当前不可产生**
 *       （reporting 服务不在集成头里）⇒ 能力声明写明归属模块 13，
 *       <b>绝不造利润数据、绝不用假报表顶替</b>。
 * </ol>
 */
@Service
public class WriteOffService {

  private static final Logger logger = LoggerFactory.getLogger(WriteOffService.class);

  /** 一页最大条数。 */
  static final int MAX_PAGE_SIZE = 200;

  /** 自动匹配的日期容差上限（天）。 */
  private static final int MAX_DATE_TOLERANCE_DAYS = 90;

  /** 待核销视图一次最多返回多少行。 */
  private static final int MAX_PENDING_ROWS = 200;

  private final DSLContext sql;

  public WriteOffService(DSLContext sql) {
    this.sql = sql;
  }

  // ================================================================ 能力声明

  /**
   * 能力声明：每一维说清「有没有、依据是什么、缺了会怎样」。
   *
   * <p>⚠️ 三个缺口必须分开说，不能混成一句「部分不可用」：
   * ① 下游 13 利润**未交付**（别的服务的事）；
   * ② 整单核销后的撤销被**上游 V338 的终态守卫**拒绝（本服务内部的数据层纪律）；
   * ③ 上游 12.2.1.1 / 12.1.1.1 **已交付**（本模块真正接通的部分）。
   */
  public FinanceWriteOffCapabilities capabilities() {
    var capabilities = new ArrayList<FinanceWriteOffCapability>();
    var gaps = new ArrayList<FinanceWriteOffDownstreamGap>();

    capabilities.add(
        new FinanceWriteOffCapability(
            "12.2.1.2",
            "核销记录 + 应收余额更新",
            true,
            "finance_write_off / finance_write_off_allocation / finance_write_off_event",
            "",
            "核销落地：数据落 PostgreSQL 16，查询走 jOOQ 显式 SQL，全链路 BigDecimal + NUMERIC(19,4)，无浮点。"
                + "核销金额写在**应收明细行**的 settled_amount 上（V338 留给 12.2.1.2 的写入所有权），"
                + "单头的已核销金额与状态由 V338 自己的派生触发器带出 —— "
                + "本模块**不写单头**，也**不在上游表上挂新触发器**。"));
    capabilities.add(
        new FinanceWriteOffCapability(
            "12.2.1.2",
            "部分核销 / 合并核销",
            true,
            "finance_write_off_allocation（一笔核销多条明细行）",
            "",
            "「核销必须精确到明细行」落成：每条分配都指向一条应收**明细行**，"
                + "分配金额小于该行未核销余额 ⇒ **部分核销**；"
                + "一笔核销单里有多条分配 ⇒ **合并核销**（一笔收款核销多笔应收）。"));
    capabilities.add(
        new FinanceWriteOffCapability(
            "12.2.1.2",
            "自动匹配（金额 + 订单号 + 日期）",
            true,
            "POST /finance/write-off/auto-match",
            "",
            "匹配条件是**三者组合**：订单号完全相等 + 明细行未核销余额等于目标金额 + "
                + "应收单确认日期与比对日期在容差窗口内。**金额相同不构成匹配**"
                + "（卷 12 对 12.3.2.3 的同一条纪律）。命中 0 条就如实报「没命中」，绝不硬凑。"));
    capabilities.add(
        new FinanceWriteOffCapability(
            "12.2.1.2",
            "预收挂账与预收余额",
            true,
            "finance_advance_ledger",
            "",
            "「钱到了但还没有对应应收」的部分挂进预收账，预收余额**真的算出来**（按币种分开）。"
                + "口径与 12.2.1.1 一致：预收 ⊂ 未核销余额（挂账**不**减未核销余额），"
                + "两个口径互不重复计数。挂着的钱是**专用**的：要拿去核销别的应收，先显式释放。"));
    capabilities.add(
        new FinanceWriteOffCapability(
            "12.2.1.2",
            "核销撤销",
            true,
            "POST /finance/write-off/{id}/reverse",
            "",
            "撤销把明细行的 settled_amount **真的退回去**，收款单的已核销金额同步退回，"
                + "核销记录转终态（REVERSED，整单冻结）。"
                + "⚠️ **边界**：应收单**整单核销完成**（单头 = SETTLED）后是 V338 写死的终态"
                + "（整单冻结，一行都不许改），那种情况下撤销会被拒 —— "
                + "替代路径是「作废 + 新单」（红冲），属 12.1.1.3 的域。"));
    capabilities.add(
        new FinanceWriteOffCapability(
            "13.1.1.1",
            "回款口径（利润与经营分析）",
            false,
            "reporting 服务不在集成头里",
            "13",
            "下游 13 **未交付** ⇒ 「回款口径」这一段**当前不可产生**。"
                + "本模块只留归属标记（ownerModule='13'），**绝不造利润数据、绝不用假报表顶替**。"));
    capabilities.add(
        new FinanceWriteOffCapability(
            "12.1.1.3",
            "坏账核销（作废 + 新单）",
            false,
            "12.1.1.3 未交付",
            "12.1.1.3",
            "坏账核销走「作废 + 新单」那条路，**不直接写行级 settled_amount** ⇒ "
                + "该列的写者始终只有 12.2.1.2 一个。"));

    gaps.add(
        new FinanceWriteOffDownstreamGap(
            "13.1.1.1",
            "利润与经营分析（回款口径）",
            "analytics",
            false,
            "13 利润与经营分析",
            "13 未交付：核销是「回款口径」的上游输入，这一段当前不可产生。"));
    gaps.add(
        new FinanceWriteOffDownstreamGap(
            "12.1.1.3",
            "坏账与核销",
            "finance",
            false,
            "12 财务核算",
            "12.1.1.3 未交付：坏账核销走「作废 + 新单」，不碰行级 settled_amount。"));

    return new FinanceWriteOffCapabilities(List.copyOf(capabilities), List.copyOf(gaps));
  }

  // ================================================================ 筛选项

  /** 筛选项下拉：全部来自**真实数据**（distinct 查询）。 */
  public FinanceWriteOffOptions options() {
    var statuses =
        sql.fetch("select distinct status from finance_write_off order by status")
            .map(r -> r.get(0, String.class));
    var modes =
        sql.fetch("select distinct mode from finance_write_off order by mode")
            .map(r -> r.get(0, String.class));
    var currencies =
        sql.fetch("select distinct currency from finance_write_off order by currency")
            .map(r -> r.get(0, String.class));
    return new FinanceWriteOffOptions(statuses, modes, currencies);
  }

  // ================================================================ 列表

  /** 核销记录列表：真分页 + 真筛选 + 真排序 + 汇总（汇总覆盖整个筛选结果集）。 */
  public FinanceWriteOffPage page(WriteOffQuery query) {
    int p = Math.max(0, query.page());
    int s = Math.min(MAX_PAGE_SIZE, Math.max(1, query.size()));
    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    applyFilters(query, where, binds);
    String clause = where.isEmpty() ? "" : " where " + String.join(" and ", where);

    long total =
        sql.fetchOne("select count(*) from finance_write_off w" + clause, binds.toArray())
            .get(0, Long.class);
    List<FinanceWriteOffRow> items =
        sql.fetch(
                "select w.*, r.receipt_no from finance_write_off w"
                    + " join finance_receipt r on r.tenant_id = w.tenant_id and r.id = w.receipt_id"
                    + clause
                    + " order by w.occurred_at desc, w.id desc limit ? offset ?",
                concat(binds, s, p * s))
            .map(this::row);
    return new FinanceWriteOffPage(items, p, s, total, summaryByCurrency(where, binds));
  }

  /** 按币种的汇总（**不跨币种相加**）。 */
  private List<FinanceWriteOffCurrencySummary> summaryByCurrency(
      List<String> where, List<Object> binds) {
    var clauses = new ArrayList<String>();
    clauses.addAll(where);
    // ⚠️ 没有筛选条件时**不能**留下一个空的 where —— 那会拼出 "where  group by" 的坏 SQL。
    String clause = clauses.isEmpty() ? "" : " where " + String.join(" and ", clauses);
    return sql.fetch(
            "select w.currency, sum(w.settlement_amount) as settlement_amount,"
                + " sum(w.advance_amount) as advance_amount, sum(w.total_amount) as total_amount,"
                + " count(*) as cnt from finance_write_off w"
                + clause
                + " group by w.currency order by w.currency",
            binds.toArray())
        .map(
            r ->
                new FinanceWriteOffCurrencySummary(
                    r.get("currency", String.class),
                    r.get("settlement_amount", BigDecimal.class),
                    r.get("advance_amount", BigDecimal.class),
                    r.get("total_amount", BigDecimal.class),
                    r.get("cnt", Long.class)));
  }

  private void applyFilters(WriteOffQuery q, List<String> where, List<Object> binds) {
    if (q.status() != null) {
      where.add("w.status = ?");
      binds.add(q.status().name());
    }
    if (q.mode() != null) {
      where.add("w.mode = ?");
      binds.add(q.mode().name());
    }
    if (q.currency() != null && !q.currency().isBlank()) {
      where.add("w.currency = ?");
      binds.add(q.currency().trim().toUpperCase(java.util.Locale.ROOT));
    }
    if (q.receiptId() != null) {
      where.add("w.receipt_id = ?");
      binds.add(q.receiptId());
    }
    if (q.keyword() != null && !q.keyword().isBlank()) {
      where.add(
          "(w.write_off_no ilike ? or w.note ilike ? or r.receipt_no ilike ? or r.payer_name ilike ?)");
      String like = "%" + q.keyword().trim() + "%";
      binds.add(like);
      binds.add(like);
      binds.add(like);
      binds.add(like);
    }
  }

  // ================================================================ 详情

  /** 核销记录详情：单头 + 分配明细 + 事件流。 */
  public FinanceWriteOffDetail detail(UUID id) {
    var head =
        sql.fetchOne(
            "select w.*, r.receipt_no from finance_write_off w"
                + " join finance_receipt r on r.tenant_id = w.tenant_id and r.id = w.receipt_id"
                + " where w.id = ?",
            id);
    if (head == null) throw notFound("核销记录 " + id + " 不存在或无权查看");
    var allocations =
        sql.fetch(
                "select a.*, rc.receivable_no, l.line_no from finance_write_off_allocation a"
                    + " left join finance_receivable rc on rc.tenant_id = a.tenant_id and rc.id = a.receivable_id"
                    + " left join finance_receivable_line l on l.tenant_id = a.tenant_id and l.id = a.receivable_line_id"
                    + " where a.write_off_id = ? order by a.created_at, a.id",
                id)
            .map(this::allocationRow);
    var events =
        sql.fetch(
                "select * from finance_write_off_event where write_off_id = ? order by occurred_at, id",
                id)
            .map(
                r ->
                    new FinanceWriteOffEventRow(
                        r.get("id", UUID.class),
                        r.get("event_type", String.class),
                        r.get("from_status", String.class),
                        r.get("to_status", String.class),
                        r.get("reason", String.class),
                        r.get("actor_id", UUID.class),
                        r.get("occurred_at", OffsetDateTime.class)));
    return new FinanceWriteOffDetail(row(head), allocations, events);
  }

  // ================================================================ 人工指定核销

  /**
   * 人工指定核销。
   *
   * <p>⚠️ 分配里可以混两种类型：{@code SETTLEMENT}（核销到应收明细行）与
   * {@code ADVANCE}（预收挂账）。全���是 ADVANCE 时，核销方式记成「预收挂账」。
   */
  @Transactional
  public FinanceWriteOffDetail create(FinanceWriteOffInput input, AccessService.Access actor) {
    if (input == null || input.receiptId() == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "必须指定要核销的收款单");
    }
    if (input.allocations() == null || input.allocations().isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "必须至少给一条分配明细：一笔核销必须落到具体的应收明细行或预收挂账上");
    }
    var plans = new ArrayList<Plan>();
    for (var allocation : input.allocations()) {
      plans.add(
          new Plan(
              allocation.type() == null ? AllocationType.SETTLEMENT : allocation.type(),
              allocation.receivableLineId(),
              requireAmount(allocation.amount()),
              allocation.note() == null ? "" : allocation.note()));
    }
    boolean anySettlement = plans.stream().anyMatch(p -> p.type() == AllocationType.SETTLEMENT);
    WriteOffMode mode = anySettlement ? WriteOffMode.MANUAL : WriteOffMode.ADVANCE_PARKING;
    return post(
        input.receiptId(),
        mode,
        plans,
        input.note() == null ? "" : input.note(),
        input.occurredAt() == null ? OffsetDateTime.now(ZoneOffset.UTC) : input.occurredAt(),
        actor);
  }

  // ================================================================ 自动匹配

  /**
   * 自动匹配核销。
   *
   * <p>⚠️ **三个条件缺一不可**：订单号完全相等 + 明细行未核销余额等于目标金额 +
   * 应收单确认日期落在比对日期的容差窗口内。
   *
   * <p>⚠️ **命中 0 条就报错**，不硬凑 —— 「自动匹配」如果总能匹配上，它就只是按金额排序而已。
   */
  @Transactional
  public FinanceWriteOffAutoMatchResult autoMatch(
      FinanceWriteOffAutoMatchInput input, AccessService.Access actor) {
    if (input == null || input.receiptId() == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "必须指定要核销的收款单");
    }
    String orderNo = input.orderNo() == null ? "" : input.orderNo().trim();
    if (orderNo.isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "必须写明订单号：自动匹配是「金额 + 订单号 + 日期」三者对上，缺订单号就退化成「金额相同即匹配」");
    }
    var receipt = lockReceipt(input.receiptId());
    String receiptCurrency = receipt.get("currency", String.class);

    BigDecimal target =
        input.amount() == null
            ? receipt.get("unsettled_amount", BigDecimal.class)
            : requireAmount(input.amount());
    LocalDate matchDate =
        input.matchDate() == null
            ? utcDate(receipt.get("received_at", OffsetDateTime.class))
            : input.matchDate();
    int tolerance =
        input.dateToleranceDays() == null ? 0 : Math.min(MAX_DATE_TOLERANCE_DAYS, Math.max(0, input.dateToleranceDays()));

    var candidates = matchCandidates(receiptCurrency, orderNo, target, matchDate, tolerance);
    if (candidates.isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "没有命中任何应收明细行：自动匹配要求「订单号 = "
              + orderNo
              + "」+「该订单下未核销余额合计 = "
              + target.toPlainString()
              + "（或某条明细行的未核销余额恰好等于它）」+「应收单确认日期落在 "
              + matchDate
              + (tolerance == 0 ? " 当天" : " ±" + tolerance + " 天")
              + "」三者同时成立。"
              + "金额相同**不构成**匹配（卷 12 对 12.3.2.3 的同一条纪律）。");
    }

    BigDecimal total = BigDecimal.ZERO;
    for (var candidate : candidates) total = total.add(candidate.matchedAmount());

    var plans = new ArrayList<Plan>();
    for (var candidate : candidates) {
      plans.add(new Plan(AllocationType.SETTLEMENT, candidate.receivableLineId(), candidate.matchedAmount(), ""));
    }
    var detail =
        post(
            input.receiptId(),
            WriteOffMode.AUTO_MATCH,
            plans,
            input.note() == null ? "" : input.note(),
            OffsetDateTime.now(ZoneOffset.UTC),
            actor);
    return new FinanceWriteOffAutoMatchResult(detail.writeOff(), candidates);
  }

  /** 自动匹配的**候选**（只读，不落库）：让操作者先看清「凭什么匹配上」再决定要不要核销。 */
  public List<FinanceWriteOffMatchCandidate> autoMatchCandidates(
      UUID receiptId, String orderNo, BigDecimal amount, LocalDate matchDate, Integer toleranceDays) {
    var receipt = requireReceipt(receiptId);
    String receiptCurrency = receipt.get("currency", String.class);
    String order = orderNo == null ? "" : orderNo.trim();
    if (order.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "必须写明订单号");
    }
    BigDecimal target =
        amount == null ? receipt.get("unsettled_amount", BigDecimal.class) : requireAmount(amount);
    LocalDate date =
        matchDate == null
            ? utcDate(receipt.get("received_at", OffsetDateTime.class))
            : matchDate;
    int tolerance =
        toleranceDays == null ? 0 : Math.min(MAX_DATE_TOLERANCE_DAYS, Math.max(0, toleranceDays));
    return matchCandidates(receiptCurrency, order, target, date, tolerance);
  }

  /**
   * 匹配引擎：三个条件**同时**成立才算命中，命中的是**明细行**。
   *
   * <p><b>金额条件有两档</b>（两者都要求订单号相等 + 日期在窗口内 ⇒ 金额相同永远不构成匹配）：
   *
   * <ol>
   *   <li><b>合计相等</b> —— 候选明细行的未核销余额**合计**等于目标金额 ⇒ 全部命中
   *       （这就是<b>合并核销</b>：一笔收款核销同一订单下的多条明细行）；
   *   <li><b>单行相等</b> —— 合计不等时，取未核销余额**恰好等于**目标金额的那些行
   *       （一笔钱正好核销其中一行）。
   * </ol>
   *
   * <p>⚠️ 比的是明细行的**未核销余额**（amount − settled_amount），不是「单头金额」
   * 也不是「任意接近」—— 否则一笔 100 的收款会把一堆 90 块的应收都算成命中。
   */
  private List<FinanceWriteOffMatchCandidate> matchCandidates(
      String currency, String orderNo, BigDecimal target, LocalDate matchDate, int toleranceDays) {
    var rows =
        sql.fetch(
            "select l.id as line_id, l.receivable_id, r.receivable_no, l.line_no, l.order_no,"
                + " r.currency, l.amount, l.settled_amount, r.confirmed_at"
                + " from finance_receivable_line l"
                + " join finance_receivable r on r.tenant_id = l.tenant_id and r.id = l.receivable_id"
                + " where l.order_no = ? and r.currency = ?"
                + " and r.status in ('OPEN','PARTIALLY_SETTLED')"
                + " and abs((r.confirmed_at at time zone 'UTC')::date - ?::date) <= ?"
                + " order by r.due_date, l.line_no",
            orderNo, currency, matchDate, toleranceDays);
    if (rows.isEmpty()) return List.of();

    BigDecimal total = BigDecimal.ZERO;
    for (var r : rows) {
      total =
          total.add(
              r.get("amount", BigDecimal.class).subtract(r.get("settled_amount", BigDecimal.class)));
    }
    boolean merged = total.compareTo(target) == 0;

    var candidates = new ArrayList<FinanceWriteOffMatchCandidate>();
    for (var r : rows) {
      BigDecimal open =
          r.get("amount", BigDecimal.class).subtract(r.get("settled_amount", BigDecimal.class));
      if (!merged && open.compareTo(target) != 0) continue;
      LocalDate confirmed = utcDate(r.get("confirmed_at", OffsetDateTime.class));
      var reasons = new ArrayList<String>();
      reasons.add("订单号完全相等：" + orderNo);
      reasons.add(
          merged
              ? "该订单下未核销余额合计 "
                  + total.toPlainString()
                  + " = 目标金额 "
                  + target.toPlainString()
                  + "（金额条件 · 合并核销）"
              : "该明细行未核销余额 "
                  + open.toPlainString()
                  + " = 目标金额 "
                  + target.toPlainString()
                  + "（金额条件）");
      reasons.add(
          "应收单确认日期 "
              + confirmed
              + " 与比对日期 "
              + matchDate
              + (toleranceDays == 0
                  ? " 同日"
                  : " 相差 " + Math.abs(daysBetween(confirmed, matchDate)) + " 天（容差 " + toleranceDays + " 天）")
              + "（日期条件）");
      candidates.add(
          new FinanceWriteOffMatchCandidate(
              r.get("line_id", UUID.class),
              r.get("receivable_id", UUID.class),
              r.get("receivable_no", String.class),
              r.get("line_no", Integer.class),
              r.get("order_no", String.class),
              r.get("currency", String.class),
              r.get("amount", BigDecimal.class),
              r.get("settled_amount", BigDecimal.class),
              open,
              open,
              confirmed,
              matchDate,
              List.copyOf(reasons)));
    }
    return List.copyOf(candidates);
  }

  // ================================================================ 核销落库（核心）

  /** 一条待落库的分配。 */
  private record Plan(AllocationType type, UUID receivableLineId, BigDecimal amount, String note) {}

  /**
   * 落库：核销单头 → 分配明细 → 写行级 {@code settled_amount} 与收款单 → 预收挂账行 → 事件。
   *
   * <p>⚠️ **顺序有讲究**：分配明细**先**插（它要外键指向单头），再写钱。
   * 分配明细的 DB 守卫会在插入时校验「金额 ≤ 该明细行未核销余额」与「币种一致」，
   * 这时钱还没写上去，校验读到的就是真实未核销余额。
   */
  private FinanceWriteOffDetail post(
      UUID receiptId,
      WriteOffMode mode,
      List<Plan> plans,
      String note,
      OffsetDateTime occurredAt,
      AccessService.Access actor) {
    var receipt = lockReceipt(receiptId);
    String receiptCurrency = receipt.get("currency", String.class);
    if ("VOID".equals(receipt.get("status", String.class))) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "收款单已作废（终态），不可核销：只能新建一张收款单");
    }
    boolean advanceFlag = receipt.get("advance_flag", Boolean.class);
    BigDecimal unsettled = receipt.get("unsettled_amount", BigDecimal.class);
    BigDecimal openAdvance = openAdvanceOf(receiptId);
    BigDecimal available = unsettled.subtract(openAdvance);
    if (available.signum() < 0) available = BigDecimal.ZERO;

    // ---- 校验分配（服务层判一次，DB 守卫再判一次）
    Set<UUID> seenLines = new LinkedHashSet<>();
    BigDecimal total = BigDecimal.ZERO;
    var settlementPlans = new ArrayList<Plan>();
    for (var plan : plans) {
      if (plan.type() == AllocationType.ADVANCE) {
        if (advanceFlag) {
          throw new ResponseStatusException(
              HttpStatus.BAD_REQUEST,
              "这笔收款已带**预收标记**（12.2.1.1 的口径：带标记的收款单其未核销余额已被算进预收），"
                  + "不能再挂预收账 —— 挂两次会让预收余额重复计数");
        }
        total = total.add(plan.amount());
        continue;
      }
      if (plan.receivableLineId() == null) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "核销到应收必须指定**应收明细行**：核销必须精确到明细行，只核销到订单头无法处理部分回款与折让");
      }
      if (!seenLines.add(plan.receivableLineId())) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "同一条应收明细行在一次核销里只能出现一次");
      }
      var line =
          sql.fetchOne(
              "select l.*, r.receivable_no, r.currency, r.status, r.due_date"
                  + " from finance_receivable_line l"
                  + " join finance_receivable r on r.tenant_id = l.tenant_id and r.id = l.receivable_id"
                  + " where l.id = ? for update",
              plan.receivableLineId());
      if (line == null) {
        throw notFound("应收明细行 " + plan.receivableLineId() + " 不存在或无权操作");
      }
      String lineCurrency = line.get("currency", String.class);
      if (!lineCurrency.equals(receiptCurrency)) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "跨币种不允许核销：收款单是 "
                + receiptCurrency
                + "，应收单是 "
                + lineCurrency
                + "（不同币种的金额加起来不是任何一个金额）");
      }
      String receivableStatus = line.get("status", String.class);
      if ("DRAFT".equals(receivableStatus)) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "应收单还没确认，不可核销：先确认再核销");
      }
      if ("VOID".equals(receivableStatus) || "SETTLED".equals(receivableStatus)) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "应收单已是终态（" + receivableStatus + "），不可核销");
      }
      BigDecimal lineOpen =
          line.get("amount", BigDecimal.class).subtract(line.get("settled_amount", BigDecimal.class));
      if (plan.amount().compareTo(lineOpen) > 0) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "核销金额 "
                + plan.amount().toPlainString()
                + " 超过该应收明细行的未核销余额 "
                + lineOpen.toPlainString());
      }
      total = total.add(plan.amount());
      settlementPlans.add(plan);
    }

    if (total.compareTo(available) > 0) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "本次核销合计 "
              + total.toPlainString()
              + " 超过这笔收款可核销的金额 "
              + available.toPlainString()
              + "（未核销余额 "
              + unsettled.toPlainString()
              + (openAdvance.signum() > 0
                  ? "，其中还有 " + openAdvance.toPlainString() + " 挂在预收账上（专用，要动用请先释放）"
                  : "")
              + "）");
    }

    // ---- 落库
    UUID id = UUID.randomUUID();
    sql.execute(
        "insert into finance_write_off(id,tenant_id,write_off_no,receipt_id,currency,mode,status,"
            + " settlement_amount,advance_amount,total_amount,note,actor_id,occurred_at,"
            + " reversed_at,version,created_at,updated_at)"
            + " values(?,?,?,?,?,?,'POSTED',0,0,0,?,?,?::timestamptz,null,0,?::timestamptz,?::timestamptz)",
        id,
        actor.tenantId(),
        nextReference("WO"),
        receiptId,
        receiptCurrency,
        mode.name(),
        note == null ? "" : note,
        actor.userId(),
        occurredAt,
        occurredAt,
        occurredAt);

    BigDecimal settlementTotal = BigDecimal.ZERO;
    for (var plan : plans) {
      UUID allocationId = UUID.randomUUID();
      if (plan.type() == AllocationType.ADVANCE) {
        sql.execute(
            "insert into finance_write_off_allocation(id,tenant_id,write_off_id,receipt_id,"
                + " receivable_id,receivable_line_id,allocation_type,order_no,amount,status,note,"
                + " created_at,updated_at)"
                + " values(?,?,?,?,null,null,'ADVANCE','',?,'POSTED',?,?::timestamptz,?::timestamptz)",
            allocationId,
            actor.tenantId(),
            id,
            receiptId,
            plan.amount(),
            plan.note(),
            occurredAt,
            occurredAt);
        sql.execute(
            "insert into finance_advance_ledger(id,tenant_id,receipt_id,write_off_id,allocation_id,"
                + " currency,amount,status,note,created_at,updated_at)"
                + " values(?,?,?,?,?,?,?,'OPEN','',?::timestamptz,?::timestamptz)",
            UUID.randomUUID(),
            actor.tenantId(),
            receiptId,
            id,
            allocationId,
            receiptCurrency,
            plan.amount(),
            occurredAt,
            occurredAt);
        continue;
      }

      var line =
          sql.fetchOne(
              "select l.order_no, l.receivable_id from finance_receivable_line l"
                  + " where l.id = ?",
              plan.receivableLineId());
      sql.execute(
          "insert into finance_write_off_allocation(id,tenant_id,write_off_id,receipt_id,"
              + " receivable_id,receivable_line_id,allocation_type,order_no,amount,status,note,"
              + " created_at,updated_at)"
              + " values(?,?,?,?,?,?,'SETTLEMENT',?,?,'POSTED',?,?::timestamptz,?::timestamptz)",
          allocationId,
          actor.tenantId(),
          id,
          receiptId,
          line.get("receivable_id", UUID.class),
          plan.receivableLineId(),
          line.get("order_no", String.class) == null ? "" : line.get("order_no", String.class),
          plan.amount(),
          plan.note(),
          occurredAt,
          occurredAt);
      settlementTotal = settlementTotal.add(plan.amount());
    }

    // ---- 写钱：**行级** settled_amount（12.2.1.2 的写入所有权）+ 收款单已核销金额
    for (var plan : settlementPlans) {
      sql.execute(
          "update finance_receivable_line set settled_amount = settled_amount + ?,"
              + " updated_at = ?::timestamptz where id = ?",
          plan.amount(),
          occurredAt,
          plan.receivableLineId());
    }
    if (settlementTotal.signum() > 0) {
      sql.execute(
          "update finance_receipt set settled_amount = settled_amount + ?, updated_at = ?::timestamptz"
              + " where id = ?",
          settlementTotal,
          occurredAt,
          receiptId);
    }

    writeEvent(
        actor.tenantId(),
        id,
        switch (mode) {
          case AUTO_MATCH -> "AUTO_MATCHED";
          case ADVANCE_PARKING -> "ADVANCE_PARKED";
          case MANUAL -> "MANUAL_ASSIGNED";
        },
        "",
        "POSTED",
        mode.label() + " " + total.toPlainString() + " " + receiptCurrency
            + (settlementTotal.signum() > 0 ? "（核销到应收 " + settlementTotal.toPlainString() + "）" : "")
            + (total.subtract(settlementTotal).signum() > 0
                ? "（预收挂账 " + total.subtract(settlementTotal).toPlainString() + "）"
                : ""),
        actor.userId(),
        occurredAt);

    logger.info(
        "[finance] 核销落账：核销记录 {}（{}）金额 {} {}，分配 {} 条",
        id,
        mode.name(),
        total.toPlainString(),
        receiptCurrency,
        plans.size());
    return detail(id);
  }

  // ================================================================ 核销撤销

  /**
   * 核销撤销：把明细行的已核销金额**真的退回去**，收款单的已核销金额同步退回。
   *
   * <p>⚠️ **为什么这么退不会撞 V338 的冻结守卫**：退的是
   * {@code finance_receivable_line.settled_amount} —— V338 的行冻结守卫
   * （{@code guard_finance_receivable_line_frozen}）只冻「金额/数量/单价/描述/订单号/来源单号/行号」
   * 这些**账目事实**，{@code settled_amount} 被**显式放行**（V338 原文：「只放行 settled_amount
   * —— 那是 12.2.1.2 的写入所有权」）。单头的已核销金额与状态由 V338 自己的派生触发器
   * {@code guard_finance_receivable_amount} 带出来，本模块**不写单头** ⇒ 不碰单头的冻结字段。
   *
   * <p>⚠️ **唯一的边界**：应收单**整单核销完成**（单头 = SETTLED）是 V338 写死的**终态**
   * （整单冻结，一行都不许改）⇒ 那时撤销退不回来。本模块**如实拒绝并写清替代路径**，
   * 而不是绕开守卫（绕开就等于给账目留后门）。
   */
  @Transactional
  public FinanceWriteOffDetail reverse(
      UUID id, FinanceWriteOffReverseInput input, AccessService.Access actor) {
    var head = sql.fetchOne("select * from finance_write_off where id = ?", id);
    if (head == null) throw notFound("核销记录 " + id + " 不存在或无权操作");
    String status = head.get("status", String.class);
    if (WriteOffStatus.valueOf(status).terminal()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "核销记录已是终态（" + status + "），不可再撤销");
    }
    String reason = input == null || input.reason() == null ? "" : input.reason().trim();
    if (reason.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "撤销必须写明理由");
    }
    var receipt = lockReceipt(head.get("receipt_id", UUID.class));
    if ("VOID".equals(receipt.get("status", String.class))) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "收款单已作废（终态），不可撤销核销：作废后的收款单整单冻结");
    }

    var allocations =
        sql.fetch(
            "select * from finance_write_off_allocation where write_off_id = ? order by created_at, id",
            id);

    // ---- 先把不可撤销的情形查清楚（V338 的终态守卫会在 DB 层拦，这里先给一句能看懂的话）
    for (var allocation : allocations) {
      if (!"SETTLEMENT".equals(allocation.get("allocation_type", String.class))) continue;
      var receivable =
          sql.fetchOne(
              "select r.receivable_no, r.status from finance_receivable r"
                  + " where r.tenant_id = ? and r.id = ?",
              allocation.get("tenant_id", UUID.class),
              allocation.get("receivable_id", UUID.class));
      String receivableStatus = receivable == null ? "UNKNOWN" : receivable.get("status", String.class);
      if ("SETTLED".equals(receivableStatus)) {
        throw new ResponseStatusException(
            HttpStatus.CONFLICT,
            "应收单 "
                + receivable.get("receivable_no", String.class)
                + " 已整单核销（终态，整单冻结，一行都不许改），"
                + "核销金额**退不回来** —— 这是 12.1.1.1 写死的账目纪律，不是本模块的缺口。"
                + "替代路径是「作废 + 新单」（红冲），属 12.1.1.3 坏账与核销的域。");
      }
      if ("VOID".equals(receivableStatus)) {
        throw new ResponseStatusException(
            HttpStatus.CONFLICT,
            "应收单 "
                + receivable.get("receivable_no", String.class)
                + " 已作废（终态），核销金额退不回来");
      }
    }

    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    BigDecimal settlementTotal = BigDecimal.ZERO;
    for (var allocation : allocations) {
      BigDecimal amount = allocation.get("amount", BigDecimal.class);
      if ("SETTLEMENT".equals(allocation.get("allocation_type", String.class))) {
        sql.execute(
            "update finance_receivable_line set settled_amount = settled_amount - ?,"
                + " updated_at = ?::timestamptz where id = ?",
            amount,
            now,
            allocation.get("receivable_line_id", UUID.class));
        settlementTotal = settlementTotal.add(amount);
      } else {
        sql.execute(
            "update finance_advance_ledger set status='REVERSED', updated_at=?::timestamptz"
                + " where allocation_id = ? and status <> 'REVERSED'",
            now,
            allocation.get("id", UUID.class));
      }
      sql.execute(
          "update finance_write_off_allocation set status='REVERSED', updated_at=?::timestamptz"
              + " where id = ?",
          now,
          allocation.get("id", UUID.class));
    }
    if (settlementTotal.signum() > 0) {
      sql.execute(
          "update finance_receipt set settled_amount = settled_amount - ?, updated_at = ?::timestamptz"
              + " where id = ?",
          settlementTotal,
          now,
          head.get("receipt_id", UUID.class));
    }
    sql.execute(
        "update finance_write_off set status='REVERSED', reversed_at=?::timestamptz,"
            + " updated_at=?::timestamptz, version=version+1 where id = ?",
        now,
        now,
        id);
    writeEvent(
        actor.tenantId(),
        id,
        "REVERSED",
        "POSTED",
        "REVERSED",
        reason,
        actor.userId(),
        now);
    return detail(id);
  }

  // ================================================================ 预收挂账：释放

  /**
   * 释放一笔预收挂账：这笔钱不再专用，可以拿去核销应收了。
   *
   * <p>⚠️ 挂着的钱是**专用**的 —— 核销时按「未核销余额 − 未释放挂账额」封顶，
   * 所以要动用它必须先显式释放（留痕，不静默改变口径）。
   */
  @Transactional
  public FinanceWriteOffAdvanceRow releaseAdvance(UUID ledgerId, AccessService.Access actor) {
    var ledger =
        sql.fetchOne(
            "select l.*, r.receipt_no, w.write_off_no from finance_advance_ledger l"
                + " join finance_receipt r on r.tenant_id = l.tenant_id and r.id = l.receipt_id"
                + " join finance_write_off w on w.tenant_id = l.tenant_id and w.id = l.write_off_id"
                + " where l.id = ? for update",
            ledgerId);
    if (ledger == null) throw notFound("预收挂账 " + ledgerId + " 不存在或无权操作");
    String status = ledger.get("status", String.class);
    if (!AdvanceLedgerStatus.valueOf(status).open()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "这笔预收挂账已经是「" + status + "」了，不可再释放");
    }
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    sql.execute(
        "update finance_advance_ledger set status='RELEASED', updated_at=?::timestamptz where id = ?",
        now,
        ledgerId);
    writeEvent(
        actor.tenantId(),
        ledger.get("write_off_id", UUID.class),
        "RELEASED",
        "OPEN",
        "RELEASED",
        "释放预收挂账 " + ledger.get("amount", BigDecimal.class).toPlainString(),
        actor.userId(),
        now);
    return advanceRow(
        sql.fetchOne(
            "select l.*, r.receipt_no, w.write_off_no from finance_advance_ledger l"
                + " join finance_receipt r on r.tenant_id = l.tenant_id and r.id = l.receipt_id"
                + " join finance_write_off w on w.tenant_id = l.tenant_id and w.id = l.write_off_id"
                + " where l.id = ?",
            ledgerId));
  }

  // ================================================================ 预收余额

  /**
   * 预收余额（12.2.1.2 的输出之一）：**真的算出来**，按币种分开。
   *
   * <p>⚠️ 两个口径**互不重复计数**：
   * <ul>
   *   <li>{@code parkedAmount} —— 本模块挂进预收账的（ledger 里 OPEN 的合计），
   *       来自**未标记**预收的收款单；</li>
   *   <li>{@code flaggedAmount} —— 12.2.1.1 预收**标记**收款单的未核销余额。</li>
   * </ul>
   * 服务层拒绝在带预收标记的收款单上挂账 ⇒ 同一个收款单不会同时出现在两边。
   */
  public FinanceWriteOffAdvanceBalance advanceBalance() {
    // 两个口径的币种并集（**按币种分开**，不跨币种相加）。
    var currencies = new java.util.TreeSet<String>();
    for (var r :
        sql.fetch(
            "select distinct currency from finance_advance_ledger where status = 'OPEN'")) {
      currencies.add(r.get(0, String.class));
    }
    for (var r :
        sql.fetch(
            "select distinct currency from finance_receipt where advance_flag and status <> 'VOID'")) {
      currencies.add(r.get(0, String.class));
    }

    var summaries = new ArrayList<FinanceWriteOffAdvanceCurrencySummary>();
    for (String currency : currencies) {
      BigDecimal parkedAmount =
          sql.fetchOne(
                  "select coalesce(sum(amount),0) from finance_advance_ledger"
                      + " where status = 'OPEN' and currency = ?",
                  currency)
              .get(0, BigDecimal.class);
      BigDecimal flaggedAmount =
          sql.fetchOne(
                  "select coalesce(sum(unsettled_amount),0) from finance_receipt"
                      + " where advance_flag and status <> 'VOID' and currency = ?",
                  currency)
              .get(0, BigDecimal.class);
      long count =
          sql.fetchOne(
                  "select count(*) from finance_advance_ledger where status = 'OPEN' and currency = ?",
                  currency)
              .get(0, Long.class);
      summaries.add(
          new FinanceWriteOffAdvanceCurrencySummary(
              currency,
              parkedAmount,
              flaggedAmount,
              parkedAmount.add(flaggedAmount),
              count));
    }
    long ledgerCount =
        sql.fetchOne("select count(*) from finance_advance_ledger where status = 'OPEN'")
            .get(0, Long.class);
    return new FinanceWriteOffAdvanceBalance(
        OffsetDateTime.now(ZoneOffset.UTC), List.copyOf(summaries), ledgerCount);
  }

  /** 预收挂账行列表（真分页 + 真筛选）。 */
  public List<FinanceWriteOffAdvanceRow> advanceRows(String status, String currency) {
    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    if (status != null && !status.isBlank()) {
      where.add("l.status = ?");
      binds.add(status.trim().toUpperCase(java.util.Locale.ROOT));
    }
    if (currency != null && !currency.isBlank()) {
      where.add("l.currency = ?");
      binds.add(currency.trim().toUpperCase(java.util.Locale.ROOT));
    }
    String clause = where.isEmpty() ? "" : " where " + String.join(" and ", where);
    return sql.fetch(
            "select l.*, r.receipt_no, w.write_off_no from finance_advance_ledger l"
                + " join finance_receipt r on r.tenant_id = l.tenant_id and r.id = l.receipt_id"
                + " join finance_write_off w on w.tenant_id = l.tenant_id and w.id = l.write_off_id"
                + clause
                + " order by l.created_at desc, l.id desc limit ?",
            concat(binds, MAX_PENDING_ROWS))
        .map(this::advanceRow);
  }

  // ================================================================ 待核销视图

  /**
   * 待核销视图：可核销的收款单 + 可核销的应收明细行。
   *
   * <p>⚠️ **两侧都要能选**才可能核销 —— 核销是「一笔收款 ↔ 若干条应收明细行」的关系。
   */
  public FinanceWriteOffPending pending() {
    var receipts =
        sql.fetch(
                "select r.*, coalesce((select sum(l.amount) from finance_advance_ledger l"
                        + "   where l.receipt_id = r.id and l.status = 'OPEN'),0) as open_advance"
                        + " from finance_receipt r"
                        + " where r.status <> 'VOID' and r.unsettled_amount > 0"
                        + " order by r.received_at desc, r.id desc limit ?",
                MAX_PENDING_ROWS)
            .map(
                r -> {
                  BigDecimal unsettled = r.get("unsettled_amount", BigDecimal.class);
                  BigDecimal openAdvance = r.get("open_advance", BigDecimal.class);
                  BigDecimal available = unsettled.subtract(openAdvance);
                  if (available.signum() < 0) available = BigDecimal.ZERO;
                  return new FinanceWriteOffPendingReceipt(
                      r.get("id", UUID.class),
                      r.get("receipt_no", String.class),
                      r.get("currency", String.class),
                      r.get("amount", BigDecimal.class),
                      r.get("settled_amount", BigDecimal.class),
                      unsettled,
                      r.get("advance_flag", Boolean.class),
                      openAdvance,
                      available,
                      r.get("received_at", OffsetDateTime.class),
                      r.get("payer_name", String.class));
                });
    var lines =
        sql.fetch(
                "select l.*, r.receivable_no, r.currency, r.due_date, r.confirmed_at"
                    + " from finance_receivable_line l"
                    + " join finance_receivable r on r.tenant_id = l.tenant_id and r.id = l.receivable_id"
                    + " where r.status in ('OPEN','PARTIALLY_SETTLED') and l.settled_amount < l.amount"
                    + " order by r.due_date, l.line_no limit ?",
                MAX_PENDING_ROWS)
            .map(
                r ->
                    new FinanceWriteOffPendingLine(
                        r.get("id", UUID.class),
                        r.get("receivable_id", UUID.class),
                        r.get("receivable_no", String.class),
                        r.get("line_no", Integer.class),
                        r.get("order_no", String.class),
                        r.get("currency", String.class),
                        r.get("amount", BigDecimal.class),
                        r.get("settled_amount", BigDecimal.class),
                        r.get("amount", BigDecimal.class)
                            .subtract(r.get("settled_amount", BigDecimal.class)),
                        r.get("due_date", LocalDate.class),
                        r.get("confirmed_at", OffsetDateTime.class)));
    return new FinanceWriteOffPending(receipts, lines);
  }

  // ================================================================ 工具

  /** 锁住收款单：同一笔收款的并发核销在这里串行化。 */
  private Record lockReceipt(UUID id) {
    var receipt = sql.fetchOne("select * from finance_receipt where id = ? for update", id);
    if (receipt == null) throw notFound("收款单 " + id + " 不存在或无权操作");
    return receipt;
  }

  private Record requireReceipt(UUID id) {
    var receipt = sql.fetchOne("select * from finance_receipt where id = ?", id);
    if (receipt == null) throw notFound("收款单 " + id + " 不存在或无权查看");
    return receipt;
  }

  /** 这笔收款上还挂着多少没释放的预收（**专用**，不能拿去核销别的应收）。 */
  private BigDecimal openAdvanceOf(UUID receiptId) {
    return sql.fetchOne(
            "select coalesce(sum(amount),0) from finance_advance_ledger"
                + " where receipt_id = ? and status = 'OPEN'",
            receiptId)
        .get(0, BigDecimal.class);
  }

  private void writeEvent(
      UUID tenantId,
      UUID writeOffId,
      String type,
      String from,
      String to,
      String reason,
      UUID actorId,
      OffsetDateTime at) {
    sql.execute(
        "insert into finance_write_off_event(id,tenant_id,write_off_id,event_type,from_status,"
            + " to_status,reason,actor_id,occurred_at) values(?,?,?,?,?,?,?,?,?::timestamptz)",
        UUID.randomUUID(),
        tenantId,
        writeOffId,
        type,
        from,
        to,
        reason == null ? "" : reason,
        actorId,
        at);
  }

  private FinanceWriteOffRow row(Record r) {
    return new FinanceWriteOffRow(
        r.get("id", UUID.class),
        r.get("write_off_no", String.class),
        r.get("receipt_id", UUID.class),
        r.get("receipt_no", String.class),
        r.get("currency", String.class),
        WriteOffMode.valueOf(r.get("mode", String.class)),
        WriteOffStatus.valueOf(r.get("status", String.class)),
        r.get("settlement_amount", BigDecimal.class),
        r.get("advance_amount", BigDecimal.class),
        r.get("total_amount", BigDecimal.class),
        r.get("note", String.class),
        r.get("actor_id", UUID.class),
        r.get("occurred_at", OffsetDateTime.class),
        r.get("reversed_at", OffsetDateTime.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  private FinanceWriteOffAllocationRow allocationRow(Record r) {
    return new FinanceWriteOffAllocationRow(
        r.get("id", UUID.class),
        r.get("write_off_id", UUID.class),
        r.get("receipt_id", UUID.class),
        r.get("receivable_id", UUID.class),
        r.get("receivable_no", String.class),
        r.get("receivable_line_id", UUID.class),
        r.get("line_no", Integer.class),
        r.get("order_no", String.class),
        AllocationType.valueOf(r.get("allocation_type", String.class)),
        r.get("amount", BigDecimal.class),
        AllocationStatus.valueOf(r.get("status", String.class)),
        r.get("note", String.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  private FinanceWriteOffAdvanceRow advanceRow(Record r) {
    return new FinanceWriteOffAdvanceRow(
        r.get("id", UUID.class),
        r.get("receipt_id", UUID.class),
        r.get("receipt_no", String.class),
        r.get("write_off_id", UUID.class),
        r.get("write_off_no", String.class),
        r.get("currency", String.class),
        r.get("amount", BigDecimal.class),
        AdvanceLedgerStatus.valueOf(r.get("status", String.class)),
        r.get("note", String.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  private static ResponseStatusException notFound(String message) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
  }

  private static String nextReference(String prefix) {
    return prefix
        + "-"
        + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
  }

  private static BigDecimal money(BigDecimal value) {
    return value == null ? null : value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
  }

  private static BigDecimal requireAmount(BigDecimal amount) {
    if (amount == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "必须写明金额");
    }
    BigDecimal value = money(amount);
    if (value.signum() <= 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "金额必须大于 0");
    }
    return value;
  }

  private static long daysBetween(LocalDate from, LocalDate to) {
    return java.time.temporal.ChronoUnit.DAYS.between(from, to);
  }

  /**
   * 把 {@code timestamptz} 取成 **UTC 日期**。
   *
   * <p>⚠️ **必须统一到 UTC**：jOOQ 读 {@code timestamptz} 时返回的是**本机时区**的
   * {@code OffsetDateTime}，直接 {@code toLocalDate()} 会拿到本地日期，
   * 而 SQL 侧 {@code (confirmed_at at time zone 'UTC')::date} 拿的是 UTC 日期 ——
   * 两者差一天就会让匹配**静默失配**（本机在东八区时尤其明显）。
   */
  private static LocalDate utcDate(OffsetDateTime value) {
    return value.atZoneSameInstant(ZoneOffset.UTC).toLocalDate();
  }

  private static Object[] concat(List<Object> binds, Object... more) {
    var all = new ArrayList<Object>(binds);
    all.addAll(List.of(more));
    return all.toArray();
  }

  /** 排序辅助：按 id 升序取行锁，避免多行并发核销时互相死锁。 */
  static Comparator<UUID> byId() {
    return Comparator.comparing(UUID::toString);
  }
}
